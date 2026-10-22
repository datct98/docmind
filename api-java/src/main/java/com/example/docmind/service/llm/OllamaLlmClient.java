package com.example.docmind.service.llm;

import com.example.docmind.config.OllamaEnabledCondition;
import com.example.docmind.config.OllamaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Client gọi Ollama chạy local (mặc định http://localhost:11434, model qwen2.5:7b hoặc qwen2.5:3b).
 * Tối ưu cho máy chạy CPU 16GB RAM (giới hạn num_ctx=4096, temperature=0.1, readTimeout=120s).
 * Nếu Ollama chưa bật hoặc chưa pull model, tự động fallback an toàn để ứng dụng không bị treo/lỗi.
 */
@Component
@Primary
@Conditional(OllamaEnabledCondition.class)
public class OllamaLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaLlmClient.class);

    private final OllamaProperties props;
    private final RestClient http;
    private final java.util.concurrent.Semaphore concurrencyGate = new java.util.concurrent.Semaphore(1);

    @Autowired
    public OllamaLlmClient(OllamaProperties props) {
        this.props = props;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(7500);
        this.http = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(factory)
                .build();
        log.info("OllamaLlmClient active (baseUrl={}, model={}, numCtx={})",
                props.getBaseUrl(), props.getModel(), props.getNumCtx());
    }

    public OllamaLlmClient(OllamaProperties props, RestClient customHttp) {
        this.props = props;
        this.http = customHttp;
    }

    @Override
    public String complete(String prompt) {
        return complete(prompt, null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public String complete(String prompt, ReasoningBudget budget) {
        long startNs = System.nanoTime();
        String base = props.getBaseUrl() != null ? props.getBaseUrl().replaceAll("/+$", "") : "http://localhost:11434";
        String endpoint = base + "/api/generate";
        int maxPredictCap = 120;
        int numPredict = (budget != null && budget.maxTokens() > 0)
                ? Math.min(budget.maxTokens(), maxPredictCap)
                : Math.min(props.getNumPredict(), maxPredictCap);
        double temp = (budget != null && budget.temperature() > 0)
                ? budget.temperature()
                : props.getTemperature();

        log.info("[LLM] Calling provider=ollama, model={}, endpoint={}, numPredict={}, budgetTier={}",
                props.getModel(), endpoint, numPredict, budget != null && budget.complexity() != null ? budget.complexity().name() : "DEFAULT");

        if (!concurrencyGate.tryAcquire()) {
            log.info("[LLM] Ollama concurrency limit reached (1 active slot) -> load-shedding to dynamic grounded fallback");
            return buildFallbackAnswer(prompt, "Ollama concurrency load shedding");
        }
        try {
            Map<String, Object> body = Map.of(
                    "model", props.getModel(),
                    "prompt", prompt != null ? prompt : "",
                    "stream", false,
                    "keep_alive", "2h",
                    "options", Map.of(
                            "temperature", temp,
                            "num_ctx", Math.min(props.getNumCtx(), 2048),
                            "num_predict", numPredict,
                            "num_thread", 12
                    )
            );
            Map<String, Object> resp = http.post()
                    .uri("/api/generate")
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            String result = extractResponseText(resp, prompt);
            long latencyMs = (System.nanoTime() - startNs) / 1_000_000L;
            boolean isFallback = result != null && result.startsWith("[ollama-offline]");
            log.info("[LLM] Response received from provider=ollama, model={}, latencyMs={}, status={}",
                    props.getModel(), latencyMs, isFallback ? "fallback" : "ok");
            return result;
        } catch (Exception e) {
            long latencyMs = (System.nanoTime() - startNs) / 1_000_000L;
            log.warn("[LLM] Response received from provider=ollama, model={}, latencyMs={}, status=fallback (error={})",
                    props.getModel(), latencyMs, e.getMessage());
            return buildFallbackAnswer(prompt, e.getMessage());
        } finally {
            concurrencyGate.release();
        }
    }

    @Override
    public String name() {
        return "ollama:" + props.getModel();
    }

    private String extractResponseText(Map<String, Object> resp, String prompt) {
        if (resp == null) {
            return buildFallbackAnswer(prompt, "Phản hồi rỗng từ Ollama");
        }
        Object textObj = resp.get("response");
        if (textObj == null) {
            return buildFallbackAnswer(prompt, "Thiếu trường 'response' từ Ollama");
        }
        String text = textObj.toString().strip();
        // Loại bỏ thẻ <think>...</think> nếu người dùng dùng các model reasoning
        text = text.replaceAll("(?s)<think>.*?</think>", "").strip();
        return text.isEmpty() ? buildFallbackAnswer(prompt, "Nội dung rỗng") : text;
    }

    private String buildFallbackAnswer(String prompt, String reason) {
        String question = "";
        if (prompt != null) {
            int idx = prompt.lastIndexOf("Câu hỏi:");
            if (idx >= 0) {
                String tail = prompt.substring(idx + "Câu hỏi:".length()).trim();
                int nl = tail.indexOf('\n');
                question = nl >= 0 ? tail.substring(0, nl).trim() : tail;
            }
        }
        return "[ollama-offline] Chưa kết nối được tới Ollama local (`" + props.getBaseUrl()
                + "`, model `" + props.getModel() + "`). "
                + "Hãy mở terminal chạy lệnh `ollama run " + props.getModel() + "`. "
                + "Câu hỏi đã ghi nhận: \"" + question + "\". (Chi tiết: " + reason + ")";
    }
}
