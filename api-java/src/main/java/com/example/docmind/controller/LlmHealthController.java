package com.example.docmind.controller;

import com.example.docmind.config.LlmProperties;
import com.example.docmind.config.OllamaProperties;
import com.example.docmind.service.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Endpoint kiểm tra sức khỏe (Healthcheck) cấu hình LLM, Base URL, Model và API Key theo thời gian thực.
 */
@RestController
@RequestMapping("/api/llm")
public class LlmHealthController {

    private static final Logger log = LoggerFactory.getLogger(LlmHealthController.class);

    private final LlmProperties llmProps;
    private final OllamaProperties ollamaProps;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private volatile Map<String, Object> lastPingSnapshot = null;

    public LlmHealthController(LlmProperties llmProps,
                               OllamaProperties ollamaProps,
                               LlmClient llmClient,
                               ObjectMapper objectMapper) {
        this.llmProps = llmProps;
        this.ollamaProps = ollamaProps;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(6))
                .version(HttpClient.Version.HTTP_2)
                .build();
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> getHealth(
            @RequestParam(name = "ping", defaultValue = "false") boolean ping) {
        if (ping) {
            return ResponseEntity.ok(executeLivePing());
        }
        return ResponseEntity.ok(buildConfigStatus());
    }

    @PostMapping("/ping")
    public ResponseEntity<Map<String, Object>> pingLlm() {
        return ResponseEntity.ok(executeLivePing());
    }

    private Map<String, Object> buildConfigStatus() {
        String provider = llmProps.getNormalizedProvider();
        String model = "ollama".equals(provider) ? ollamaProps.getModel() : llmProps.resolveModel();
        String baseUrl = "ollama".equals(provider) ? ollamaProps.getBaseUrl() : llmProps.resolveBaseUrl();
        String endpoint = resolveEndpoint(provider, baseUrl);
        String rawKey = llmProps.getApiKey();
        boolean keyConfigured = rawKey != null && !rawKey.isBlank();
        String maskedKey = maskApiKey(rawKey);
        String reasoningEffort = llmProps.resolveReasoningEffort();

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("provider", provider);
        res.put("model", model);
        res.put("base_url", baseUrl);
        res.put("endpoint", endpoint);
        res.put("engine_name", llmClient != null ? llmClient.name() : (provider + ":" + model));
        res.put("api_key_configured", keyConfigured);
        res.put("api_key_masked", maskedKey);
        res.put("reasoning_effort", reasoningEffort != null ? reasoningEffort : "default");
        res.put("temperature", "ollama".equals(provider) ? ollamaProps.getTemperature() : llmProps.getTemperature());
        res.put("top_p", "ollama".equals(provider) ? ollamaProps.getTopP() : (llmProps.getTopP() != null ? llmProps.getTopP() : 0.9));
        res.put("repeat_penalty", "ollama".equals(provider) ? ollamaProps.getRepeatPenalty() : 1.08);
        res.put("num_ctx", "ollama".equals(provider) ? ollamaProps.getNumCtx() : 4096);
        res.put("num_predict", "ollama".equals(provider) ? ollamaProps.getNumPredict() : (llmProps.getMaxTokens() > 0 ? llmProps.getMaxTokens() : 600));
        res.put("fallback_model", "ollama".equals(provider) ? ollamaProps.getFallbackModel() : llmProps.getFallbackModel());
        res.put("timeout_seconds", llmProps.getTimeoutSeconds());

        boolean needsApiKey = !"ollama".equals(provider) && !"stub".equals(provider);
        if (needsApiKey && !keyConfigured) {
            res.put("status", "MISSING_KEY");
            res.put("healthy", false);
            res.put("status_message", "Chưa cấu hình LLM_API_KEY cho nhà cung cấp " + provider.toUpperCase());
        } else if (lastPingSnapshot != null
                && provider.equals(lastPingSnapshot.get("provider"))
                && model.equals(lastPingSnapshot.get("model"))
                && maskedKey.equals(lastPingSnapshot.get("api_key_masked"))) {
            res.put("status", lastPingSnapshot.get("status"));
            res.put("healthy", lastPingSnapshot.get("healthy"));
            res.put("status_message", lastPingSnapshot.get("status_message"));
            res.put("http_status", lastPingSnapshot.get("http_status"));
            res.put("latency_ms", lastPingSnapshot.get("latency_ms"));
            res.put("sample_reply", lastPingSnapshot.get("sample_reply"));
            res.put("error_detail", lastPingSnapshot.get("error_detail"));
            res.put("checked_at", lastPingSnapshot.get("checked_at"));
        } else {
            res.put("status", "CONFIGURED");
            res.put("healthy", true);
            res.put("status_message", needsApiKey
                    ? "Đã nhận cấu hình API Key (" + maskedKey + ") · Sẵn sàng kiểm tra kết nối"
                    : "Đang cấu hình chế độ Local (" + provider + ")");
            res.put("checked_at", Instant.now().toString());
        }

        return res;
    }

    private Map<String, Object> executeLivePing() {
        String provider = llmProps.getNormalizedProvider();
        String model = "ollama".equals(provider) ? ollamaProps.getModel() : llmProps.resolveModel();
        String baseUrl = "ollama".equals(provider) ? ollamaProps.getBaseUrl() : llmProps.resolveBaseUrl();
        String endpoint = resolveEndpoint(provider, baseUrl);
        String rawKey = llmProps.getApiKey();
        boolean keyConfigured = rawKey != null && !rawKey.isBlank();
        String maskedKey = maskApiKey(rawKey);
        String reasoningEffort = llmProps.resolveReasoningEffort();

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("provider", provider);
        res.put("model", model);
        res.put("base_url", baseUrl);
        res.put("endpoint", endpoint);
        res.put("engine_name", llmClient != null ? llmClient.name() : (provider + ":" + model));
        res.put("api_key_configured", keyConfigured);
        res.put("api_key_masked", maskedKey);
        res.put("reasoning_effort", reasoningEffort != null ? reasoningEffort : "default");
        res.put("temperature", "ollama".equals(provider) ? ollamaProps.getTemperature() : llmProps.getTemperature());
        res.put("top_p", "ollama".equals(provider) ? ollamaProps.getTopP() : (llmProps.getTopP() != null ? llmProps.getTopP() : 0.9));
        res.put("repeat_penalty", "ollama".equals(provider) ? ollamaProps.getRepeatPenalty() : 1.08);
        res.put("num_ctx", "ollama".equals(provider) ? ollamaProps.getNumCtx() : 4096);
        res.put("num_predict", "ollama".equals(provider) ? ollamaProps.getNumPredict() : (llmProps.getMaxTokens() > 0 ? llmProps.getMaxTokens() : 600));
        res.put("fallback_model", "ollama".equals(provider) ? ollamaProps.getFallbackModel() : llmProps.getFallbackModel());
        res.put("timeout_seconds", llmProps.getTimeoutSeconds());
        res.put("checked_at", Instant.now().toString());

        boolean needsApiKey = !"ollama".equals(provider) && !"stub".equals(provider);
        if (needsApiKey && !keyConfigured) {
            res.put("status", "MISSING_KEY");
            res.put("healthy", false);
            res.put("http_status", 0);
            res.put("latency_ms", 0);
            res.put("status_message", "Chưa có LLM_API_KEY trong cấu hình (.env)");
            res.put("error_detail", "Vui lòng điền LLM_API_KEY vào file .env rồi khởi động lại container api-java.");
            this.lastPingSnapshot = res;
            return res;
        }

        long startNs = System.nanoTime();
        try {
            if ("ollama".equals(provider)) {
                Map<String, Object> body = Map.of(
                        "model", model,
                        "prompt", "Reply OK",
                        "stream", false,
                        "options", Map.of("num_predict", 8, "temperature", 0.0)
                );
                String jsonBody = objectMapper.writeValueAsString(body);
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> httpRes = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                long latencyMs = (System.nanoTime() - startNs) / 1_000_000L;
                int code = httpRes.statusCode();
                res.put("http_status", code);
                res.put("latency_ms", latencyMs);

                if (code >= 200 && code < 300) {
                    res.put("status", "HEALTHY");
                    res.put("healthy", true);
                    res.put("active_runtime_model", model);
                    res.put("status_message", "Kết nối Ollama (" + model + ") hoạt động tốt (" + latencyMs + "ms)");
                    res.put("sample_reply", "OK");
                } else {
                    String fbModel = ollamaProps.getFallbackModel();
                    if ((code == 404 || code >= 500) && fbModel != null && !fbModel.isBlank() && !fbModel.equals(model)) {
                        Map<String, Object> fbBody = Map.of(
                                "model", fbModel.trim(),
                                "prompt", "Reply OK",
                                "stream", false,
                                "options", Map.of("num_predict", 8, "temperature", 0.0)
                        );
                        HttpRequest fbReq = HttpRequest.newBuilder()
                                .uri(URI.create(endpoint))
                                .timeout(Duration.ofSeconds(15))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(fbBody), StandardCharsets.UTF_8))
                                .build();
                        HttpResponse<String> fbRes = httpClient.send(fbReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                        long fbLatency = (System.nanoTime() - startNs) / 1_000_000L;
                        if (fbRes.statusCode() >= 200 && fbRes.statusCode() < 300) {
                            res.put("http_status", fbRes.statusCode());
                            res.put("latency_ms", fbLatency);
                            res.put("status", "HEALTHY");
                            res.put("healthy", true);
                            res.put("active_runtime_model", fbModel.trim());
                            res.put("status_message", "Kết nối Ollama hoạt động tốt qua Fallback Model '" + fbModel.trim() + "' (Model chính '" + model + "' chưa pull, " + fbLatency + "ms)");
                            res.put("sample_reply", "OK");
                        } else {
                            res.put("status", "ENDPOINT_ERROR");
                            res.put("healthy", false);
                            res.put("status_message", "Ollama trả về mã lỗi HTTP " + code);
                            res.put("error_detail", truncateError(httpRes.body()));
                        }
                    } else {
                        res.put("status", "ENDPOINT_ERROR");
                        res.put("healthy", false);
                        res.put("status_message", "Ollama trả về mã lỗi HTTP " + code);
                        res.put("error_detail", truncateError(httpRes.body()));
                    }
                }
            } else {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", model);
                body.put("messages", List.of(
                        Map.of("role", "user", "content", "Reply with one word: OK")
                ));
                body.put("temperature", 0.0);
                body.put("max_tokens", 16);
                if (reasoningEffort != null && !reasoningEffort.isBlank()) {
                    body.put("reasoning_effort", reasoningEffort);
                }

                String jsonBody = objectMapper.writeValueAsString(body);
                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .timeout(Duration.ofSeconds(25))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));

                if (keyConfigured) {
                    reqBuilder.header("Authorization", "Bearer " + rawKey.trim());
                }

                HttpResponse<String> httpRes = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                long latencyMs = (System.nanoTime() - startNs) / 1_000_000L;
                int code = httpRes.statusCode();
                res.put("http_status", code);
                res.put("latency_ms", latencyMs);

                if (code >= 200 && code < 300) {
                    String sampleReply = extractOpenAiSampleReply(httpRes.body());
                    res.put("status", "HEALTHY");
                    res.put("healthy", true);
                    res.put("status_message", "API Key & Model " + model + " hoạt động tốt (" + latencyMs + "ms)");
                    res.put("sample_reply", sampleReply != null && !sampleReply.isBlank() ? sampleReply : "OK (HTTP 200)");
                    log.info("[LLM-HEALTH] Ping SUCCESS provider={}, model={}, latencyMs={}ms, reply='{}'",
                            provider, model, latencyMs, res.get("sample_reply"));
                } else if (code == 401 || code == 403) {
                    res.put("status", "INVALID_KEY");
                    res.put("healthy", false);
                    res.put("status_message", "API Key không hợp lệ, sai định dạng hoặc đã hết hạn (HTTP " + code + ")");
                    res.put("error_detail", truncateError(httpRes.body()));
                    log.warn("[LLM-HEALTH] Ping INVALID_KEY provider={}, model={}, httpStatus={}", provider, model, code);
                } else if (code == 402 || code == 429) {
                    res.put("status", "QUOTA_EXCEEDED");
                    res.put("healthy", false);
                    res.put("status_message", "Tài khoản API Key hết số dư (Quota) hoặc bị giới hạn Rate Limit (HTTP " + code + ")");
                    res.put("error_detail", truncateError(httpRes.body()));
                } else if (code == 404) {
                    res.put("status", "MODEL_OR_URL_NOT_FOUND");
                    res.put("healthy", false);
                    res.put("status_message", "Không tìm thấy Model '" + model + "' hoặc sai đường dẫn Base URL (HTTP 404)");
                    res.put("error_detail", truncateError(httpRes.body()));
                } else {
                    res.put("status", "ENDPOINT_ERROR");
                    res.put("healthy", false);
                    res.put("status_message", "Máy chủ LLM trả về lỗi HTTP " + code + " (" + latencyMs + "ms)");
                    res.put("error_detail", truncateError(httpRes.body()));
                }
            }
        } catch (Exception e) {
            long latencyMs = (System.nanoTime() - startNs) / 1_000_000L;
            res.put("status", "CONNECTION_ERROR");
            res.put("healthy", false);
            res.put("http_status", 0);
            res.put("latency_ms", latencyMs);
            res.put("status_message", "Không thể kết nối tới " + baseUrl + " (" + e.getClass().getSimpleName() + ")");
            res.put("error_detail", e.getMessage());
            log.warn("[LLM-HEALTH] Ping CONNECTION_ERROR provider={}, model={}, endpoint={}, error={}",
                    provider, model, endpoint, e.getMessage());
        }

        this.lastPingSnapshot = res;
        return res;
    }

    private String extractOpenAiSampleReply(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) return "";
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode msg = choices.get(0).path("message");
                String content = msg.path("content").asText("");
                if (content != null && !content.isBlank()) {
                    return content.replaceAll("(?s)<think>.*?</think>", "").strip();
                }
                String reasoning = msg.path("reasoning_content").asText("");
                if (reasoning != null && !reasoning.isBlank()) {
                    return "(Reasoning OK) " + truncateError(reasoning);
                }
            }
        } catch (Exception ignored) {
        }
        return "OK";
    }

    private static String resolveEndpoint(String provider, String baseUrl) {
        if ("ollama".equals(provider)) {
            String clean = (baseUrl != null && !baseUrl.isBlank())
                    ? baseUrl.replaceAll("/+$", "")
                    : new LlmProperties().resolveBaseUrl();
            return clean + "/api/generate";
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            return "https://api.openai.com/v1/chat/completions";
        }
        String clean = baseUrl.trim().replaceAll("/+$", "");
        if (clean.endsWith("/chat/completions")) {
            return clean;
        }
        return clean + "/chat/completions";
    }

    private static String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return "Chưa cấu hình (Trống)";
        }
        String trimmed = apiKey.trim();
        if (trimmed.length() <= 10) {
            return trimmed.substring(0, Math.min(3, trimmed.length())) + "***";
        }
        return trimmed.substring(0, 6) + "..." + trimmed.substring(trimmed.length() - 4);
    }

    private static String truncateError(String text) {
        if (text == null) return "";
        String clean = text.strip();
        return clean.length() > 300 ? clean.substring(0, 300) + "..." : clean;
    }
}
