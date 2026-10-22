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
import tools.jackson.databind.ObjectMapper;

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
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OllamaProperties props;
    private final RestClient http;
    private final boolean enableTimeoutCooldown;
    private final java.util.concurrent.Semaphore concurrencyGate = new java.util.concurrent.Semaphore(1);
    private volatile long cooldownUntilEpochMs = 0L;

    @Autowired
    public OllamaLlmClient(OllamaProperties props) {
        this.props = props;
        this.enableTimeoutCooldown = true;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        int readTimeoutMs = 30000;
        factory.setReadTimeout(readTimeoutMs);
        this.http = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(factory)
                .build();
        log.info("OllamaLlmClient active (baseUrl={}, model={}, numCtx={}, readTimeoutMs={})",
                props.getBaseUrl(), props.getModel(), props.getNumCtx(), readTimeoutMs);
    }

    public OllamaLlmClient(OllamaProperties props, RestClient customHttp) {
        this.props = props;
        this.http = customHttp;
        this.enableTimeoutCooldown = false;
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
        int maxPredictCap = 135;
        int numPredict = (budget != null && budget.maxTokens() > 0)
                ? Math.min(budget.maxTokens(), maxPredictCap)
                : Math.min(props.getNumPredict(), maxPredictCap);
        double temp = (budget != null && budget.temperature() > 0)
                ? budget.temperature()
                : props.getTemperature();
        int effectiveNumCtx = 2048;

        log.info("[LLM] Calling provider=ollama, model={}, endpoint={}, numPredict={}, numCtx={}, budgetTier={}",
                props.getModel(), endpoint, numPredict, effectiveNumCtx,
                budget != null && budget.complexity() != null ? budget.complexity().name() : "DEFAULT");

        if (enableTimeoutCooldown && System.currentTimeMillis() < cooldownUntilEpochMs) {
            log.info("[LLM] Ollama CPU runner in timeout cooldown -> fast-failing in 0ms to dynamic grounded synthesis");
            return buildFallbackAnswer(prompt, "Ollama CPU timeout cooldown");
        }
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
                            "num_ctx", effectiveNumCtx,
                            "num_predict", numPredict
                    )
            );
            String rawJson = http.post()
                    .uri("/api/generate")
                    .body(body)
                    .retrieve()
                    .body(String.class);
            Map<String, Object> resp = (rawJson != null && !rawJson.isBlank())
                    ? MAPPER.readValue(rawJson, Map.class)
                    : null;
            String result = extractResponseText(resp, prompt);
            long latencyMs = (System.nanoTime() - startNs) / 1_000_000L;
            boolean isFallback = result != null && result.startsWith("[ollama-offline]");
            log.info("[LLM] Response received from provider=ollama, model={}, latencyMs={}, status={}",
                    props.getModel(), latencyMs, isFallback ? "fallback" : "ok");
            return result;
        } catch (Exception e) {
            long latencyMs = (System.nanoTime() - startNs) / 1_000_000L;
            if (enableTimeoutCooldown && latencyMs >= 28_500) {
                cooldownUntilEpochMs = System.currentTimeMillis() + 15_000L;
            }
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
