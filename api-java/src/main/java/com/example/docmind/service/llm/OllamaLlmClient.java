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

    @Autowired
    public OllamaLlmClient(OllamaProperties props) {
        this.props = props;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(Math.max(props.getReadTimeoutMs(), 10000));
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
    @SuppressWarnings("unchecked")
    public String complete(String prompt) {
        long startNs = System.nanoTime();
        String base = props.getBaseUrl() != null ? props.getBaseUrl().replaceAll("/+$", "") : "http://localhost:11434";
        String endpoint = base + "/api/generate";
        log.info("[LLM] Calling provider=ollama, model={}, endpoint={}", props.getModel(), endpoint);
        try {
            Map<String, Object> body = Map.of(
                    "model", props.getModel(),
                    "prompt", prompt,
                    "stream", false,
                    "options", Map.of(
                            "temperature", props.getTemperature(),
                            "num_ctx", props.getNumCtx(),
                            "num_predict", props.getNumPredict()
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
