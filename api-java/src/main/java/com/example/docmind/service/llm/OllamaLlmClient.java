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
 * Client gọi Ollama chạy local theo cấu hình từ {@link com.example.docmind.config.LlmProperties}.
 * Tối ưu cho máy chạy CPU 16GB RAM (giới hạn num_ctx=4096, temperature=0.2, readTimeout=45s).
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
    private volatile String activeRuntimeModel = null;

    @Autowired
    public OllamaLlmClient(OllamaProperties props) {
        this.props = props;
        this.enableTimeoutCooldown = true;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        int readTimeoutMs = 22000;
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
        String base = props.getBaseUrl() != null
                ? props.getBaseUrl().replaceAll("/+$", "")
                : new com.example.docmind.config.LlmProperties().resolveBaseUrl();
        String endpoint = base + "/api/generate";
        int configuredPredict = props.getNumPredict() > 0 ? props.getNumPredict() : 600;
        int numPredict = (budget != null && budget.maxTokens() > 0)
                ? (budget.maxTokens() <= 900 ? budget.maxTokens() : Math.max(configuredPredict, 700))
                : configuredPredict;
        double temp = (budget != null && budget.temperature() >= 0)
                ? budget.temperature()
                : (props.getTemperature() > 0 ? props.getTemperature() : 0.2);
        double topP = (budget != null && budget.topP() != null && budget.topP() > 0.0 && budget.topP() <= 1.0)
                ? budget.topP()
                : (props.getTopP() > 0 ? props.getTopP() : 0.9);
        double repeatPenalty = (budget != null && budget.repeatPenalty() != null && budget.repeatPenalty() > 0.0)
                ? budget.repeatPenalty()
                : (props.getRepeatPenalty() > 0 ? props.getRepeatPenalty() : 1.08);
        int effectiveNumCtx = props.getNumCtx() > 0 ? props.getNumCtx() : 4096;

        String effectiveModel = activeRuntimeModel != null ? activeRuntimeModel : props.getModel();

        log.info("[LLM] Calling provider=ollama, model={}, endpoint={}, numPredict={}, numCtx={}, temp={}, topP={}, repeatPenalty={}, budgetTier={}",
                effectiveModel, endpoint, numPredict, effectiveNumCtx, temp, topP, repeatPenalty,
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
                    "model", effectiveModel,
                    "prompt", prompt != null ? prompt : "",
                    "stream", false,
                    "keep_alive", -1,
                    "options", Map.of(
                            "temperature", temp,
                            "top_p", topP,
                            "repeat_penalty", repeatPenalty,
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
            if (enableTimeoutCooldown && latencyMs >= 18_000) {
                cooldownUntilEpochMs = System.currentTimeMillis() + 30_000L;
            }
            boolean isFallback = result != null && result.startsWith("[ollama-offline]");
            log.info("[LLM] Response received from provider=ollama, model={}, latencyMs={}, status={}",
                    effectiveModel, latencyMs, isFallback ? "fallback" : "ok");
            return result;
        } catch (Exception e) {
            String errMsg = e.getMessage() != null ? e.getMessage() : "";
            boolean isModelNotFound = errMsg.contains("404")
                    || errMsg.toLowerCase(java.util.Locale.ROOT).contains("not found");
            boolean isOomOrModelKilled = errMsg.contains("signal: killed")
                    || errMsg.contains("llama-server process has terminated")
                    || errMsg.contains("more system memory");
            String fallbackCandidate = (props.getFallbackModel() != null
                    && !props.getFallbackModel().isBlank()
                    && !props.getFallbackModel().equalsIgnoreCase(effectiveModel))
                    ? props.getFallbackModel().strip()
                    : null;
            if ((isModelNotFound || isOomOrModelKilled) && fallbackCandidate != null) {
                log.warn("[LLM] Model {} unavailable or OOM ({}) -> switching runtime model to {} and retrying immediately",
                        effectiveModel, errMsg, fallbackCandidate);
                activeRuntimeModel = fallbackCandidate;
                try {
                    Map<String, Object> retryBody = Map.of(
                            "model", fallbackCandidate,
                            "prompt", prompt != null ? prompt : "",
                            "stream", false,
                            "keep_alive", "2h",
                            "options", Map.of(
                                    "temperature", temp,
                                    "top_p", topP,
                                    "repeat_penalty", repeatPenalty,
                                    "num_ctx", effectiveNumCtx,
                                    "num_predict", numPredict
                            )
                    );
                    String retryJson = http.post()
                            .uri("/api/generate")
                            .body(retryBody)
                            .retrieve()
                            .body(String.class);
                    Map<String, Object> retryResp = (retryJson != null && !retryJson.isBlank())
                            ? MAPPER.readValue(retryJson, Map.class)
                            : null;
                    String retryResult = extractResponseText(retryResp, prompt);
                    long totalMs = (System.nanoTime() - startNs) / 1_000_000L;
                    log.info("[LLM] Auto-recovery succeeded with model={} in {} ms", fallbackCandidate, totalMs);
                    return retryResult;
                } catch (Exception retryEx) {
                    log.warn("[LLM] Auto-recovery retry with {} failed: {}", fallbackCandidate, retryEx.getMessage());
                }
            }
            long latencyMs = (System.nanoTime() - startNs) / 1_000_000L;
            if (enableTimeoutCooldown && latencyMs >= 18_000) {
                cooldownUntilEpochMs = System.currentTimeMillis() + 30_000L;
            }
            log.warn("[LLM] Response received from provider=ollama, model={}, latencyMs={}, status=fallback (error={})",
                    effectiveModel, latencyMs, e.getMessage());
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
