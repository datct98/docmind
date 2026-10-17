package com.example.docmind.service.llm;

import com.example.docmind.config.GeminiEnabledCondition;
import com.example.docmind.config.GeminiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * GeminiLlmClient — CHỈ kích hoạt khi gemini.api.keys có giá trị
 * (@ConditionalOnProperty, không matchIfMissing). Khi active, nó là primary LlmClient
 * (@Primary) và lấn át StubLlmClient.
 *
 * <p>Skeleton: round-robin key đơn giản + gọi generativelanguage API. Nếu lỗi,
 * fallback trả về thông báo an toàn (không ném) để chat không chết. Hoàn thiện
 * (retry, nhiều model fallback, streaming) để lại TODO cho giai đoạn sau.
 */
@Component
@org.springframework.context.annotation.Primary
@Conditional(GeminiEnabledCondition.class)
public class GeminiLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiLlmClient.class);

    private final GeminiProperties props;
    private final RestClient http;
    private int rrIndex = 0;

    public GeminiLlmClient(GeminiProperties props) {
        this.props = props;
        this.http = RestClient.builder().baseUrl(props.getBaseUrl()).build();
        log.info("GeminiLlmClient active (model={}, keys={})",
                props.getModel(), props.getApi().getKeys().size());
    }

    @Override
    @SuppressWarnings("unchecked")
    public String complete(String prompt) {
        long startNs = System.nanoTime();
        String endpoint = props.getBaseUrl() + "/models/" + props.getModel() + ":generateContent";
        log.info("[LLM] Calling provider=gemini, model={}, endpoint={}", props.getModel(), endpoint);
        String key = nextKey();
        if (key == null) {
            long latencyMs = (System.nanoTime() - startNs) / 1_000_000L;
            log.warn("[LLM] Response received from provider=gemini, model={}, latencyMs={}, status=fallback (reason=missing_key)",
                    props.getModel(), latencyMs);
            return "[gemini-unavailable] Chưa cấu hình được Gemini key hợp lệ.";
        }
        try {
            Map<String, Object> body = Map.of(
                    "contents", List.of(Map.of(
                            "parts", List.of(Map.of("text", prompt))
                    ))
            );
            Map<String, Object> resp = http.post()
                    .uri("/models/{model}:generateContent?key={key}", props.getModel(), key)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            String result = extractText(resp);
            long latencyMs = (System.nanoTime() - startNs) / 1_000_000L;
            boolean isFallback = result != null && result.startsWith("[gemini-");
            log.info("[LLM] Response received from provider=gemini, model={}, latencyMs={}, status={}",
                    props.getModel(), latencyMs, isFallback ? "fallback" : "ok");
            return result;
        } catch (Exception e) {
            long latencyMs = (System.nanoTime() - startNs) / 1_000_000L;
            log.warn("[LLM] Response received from provider=gemini, model={}, latencyMs={}, status=fallback (error={})",
                    props.getModel(), latencyMs, e.getMessage());
            return "[gemini-error] Không gọi được Gemini: " + e.getMessage();
        }
    }

    @Override
    public String name() {
        return "gemini:" + props.getModel();
    }

    private synchronized String nextKey() {
        List<String> keys = props.getApi().getKeys();
        if (keys == null || keys.isEmpty()) {
            return null;
        }
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.get(rrIndex % keys.size());
            rrIndex = (rrIndex + 1) % keys.size();
            if (k != null && !k.isBlank()) {
                return k.trim();
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String extractText(Map<String, Object> resp) {
        if (resp == null) {
            return "[gemini-empty]";
        }
        try {
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) resp.get("candidates");
            if (candidates == null || candidates.isEmpty()) {
                return "[gemini-empty]";
            }
            Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
            List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> part : parts) {
                Object text = part.get("text");
                if (text != null) {
                    sb.append(text);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Parse Gemini response lỗi: {}", e.getMessage());
            return "[gemini-parse-error]";
        }
    }
}
