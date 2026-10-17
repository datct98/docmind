package com.example.docmind.service.llm;

import com.example.docmind.config.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client LLM thống nhất tương thích chuẩn OpenAI Chat Completions API (POST /chat/completions).
 * Phục vụ các nhà cung cấp đám mây và custom:
 * DeepSeek, Grok (xAI), OpenAI, Gemini (OpenAI mode), OpenRouter, và Custom local endpoints (vLLM, LMStudio).
 *
 * <p>Đặc tính nổi bật:
 * <ul>
 *   <li>Tự động chuẩn hóa endpoint URL (thêm /chat/completions nếu chưa có).</li>
 *   <li>Xác thực qua Bearer Token: {@code Authorization: Bearer <API_KEY>}.</li>
 *   <li>Tối ưu kết nối HTTP/2 Keep-Alive Pool và truyền {@code reasoning_effort} để giảm độ trễ cho model suy luận (Grok 4.x, o1/o3).</li>
 *   <li>Giới hạn read-timeout mỗi lượt thử (tối đa 35s khi bật {@code reasoning_effort}) kèm cơ chế tự động retry khi proxy trung gian bị treo kênh (timeout / 502 / 503 / 504).</li>
 *   <li>Tự động bóc tách và loại bỏ thẻ {@code <think>...</think>} cho các mô hình suy luận (DeepSeek-R1, reasoning).</li>
 *   <li>Hỗ trợ fallback sang trường {@code reasoning_content} nếu {@code content} để trống.</li>
 *   <li>Xử lý lỗi và HTTP status linh hoạt, fallback an toàn không làm crash luồng nghiệp vụ.</li>
 * </ul>
 */
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);
    private static final int FAST_REASONING_ATTEMPT_TIMEOUT_MS = 35_000;

    private final LlmProperties props;
    private final ObjectMapper objectMapper;
    private final RestClient http;
    private final String provider;
    private final String model;
    private final String endpoint;
    private final String reasoningEffort;

    public OpenAiCompatibleLlmClient(LlmProperties props, ObjectMapper objectMapper) {
        this(props, objectMapper, null);
    }

    public OpenAiCompatibleLlmClient(LlmProperties props, ObjectMapper objectMapper, RestClient customHttp) {
        this.props = props;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.provider = props.getNormalizedProvider();
        this.model = props.resolveModel();
        this.endpoint = normalizeEndpoint(props.resolveBaseUrl());
        this.reasoningEffort = props.resolveReasoningEffort();

        if (customHttp != null) {
            this.http = customHttp;
        } else {
            int configuredTimeoutMs = Math.max(props.getTimeoutSeconds() * 1000, 10000);
            int attemptTimeoutMs = (this.reasoningEffort != null && configuredTimeoutMs > FAST_REASONING_ATTEMPT_TIMEOUT_MS)
                    ? FAST_REASONING_ATTEMPT_TIMEOUT_MS
                    : configuredTimeoutMs;
            HttpClient jdkClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(5000))
                    .version(HttpClient.Version.HTTP_2)
                    .build();
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdkClient);
            factory.setReadTimeout(Duration.ofMillis(attemptTimeoutMs));

            this.http = RestClient.builder()
                    .requestFactory(factory)
                    .build();
        }

        log.info("OpenAiCompatibleLlmClient active (provider={}, model={}, endpoint={}, reasoningEffort={})",
                this.provider, this.model, this.endpoint, this.reasoningEffort != null ? this.reasoningEffort : "default");
    }

    @Override
    public String complete(String prompt) {
        long startNs = System.nanoTime();
        String safePrompt = prompt != null ? prompt : "";
        log.info("[LLM] Calling provider={}, model={}, endpoint={}, reasoningEffort={}, promptChars={}",
                provider, model, endpoint, reasoningEffort != null ? reasoningEffort : "default", safePrompt.length());
        try {
            Map<String, Object> body = buildRequestBody(safePrompt, true);
            String rawJson;
            try {
                rawJson = executePostWithRetry(body);
            } catch (HttpClientErrorException.BadRequest badReq) {
                if (body.containsKey("reasoning_effort") || body.containsKey("max_tokens")) {
                    log.info("[LLM] Provider {} rejected optional reasoning_effort/max_tokens (400), retrying standard payload", provider);
                    rawJson = executePostWithRetry(buildRequestBody(safePrompt, false));
                } else {
                    throw badReq;
                }
            }

            ParsedCompletion parsed = parseCompletion(rawJson, prompt);
            if (parsed.emptyContentWithReasoning()) {
                log.info("[LLM] Provider {} returned empty content after {} reasoning tokens, retrying once without reasoning_effort",
                        provider, parsed.reasoningTokens());
                rawJson = executePostWithRetry(buildRequestBody(safePrompt, false));
                parsed = parseCompletion(rawJson, prompt);
            }

            String result = parsed.text();
            long latencyMs = (System.nanoTime() - startNs) / 1_000_000L;
            boolean isFallback = result != null && result.startsWith("[" + provider + "-error]");
            log.info("[LLM] Response received from provider={}, model={}, latencyMs={}, promptTokens={}, completionTokens={}, reasoningTokens={}, cachedTokens={}, status={}",
                    provider, model, latencyMs,
                    parsed.promptTokens(), parsed.completionTokens(), parsed.reasoningTokens(), parsed.cachedTokens(),
                    isFallback ? "fallback" : "ok");
            return result;
        } catch (Exception e) {
            long latencyMs = (System.nanoTime() - startNs) / 1_000_000L;
            log.warn("[LLM] Response received from provider={}, model={}, latencyMs={}, status=fallback (error={})",
                    provider, model, latencyMs, e.getMessage());
            return buildFallbackAnswer(prompt, e.getMessage());
        }
    }

    private Map<String, Object> buildRequestBody(String safePrompt, boolean includeOptionalTuning) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "user", "content", safePrompt)));
        body.put("temperature", props.getTemperature());
        body.put("stream", false);
        if (includeOptionalTuning) {
            if (reasoningEffort != null && !reasoningEffort.isBlank()) {
                body.put("reasoning_effort", reasoningEffort);
            }
            if (props.getMaxTokens() > 0) {
                body.put("max_tokens", props.getMaxTokens());
            }
        }
        return body;
    }

    private String executePostWithRetry(Map<String, Object> body) {
        try {
            return executePost(body);
        } catch (ResourceAccessException timeoutOrIo) {
            log.warn("[LLM] Provider {} attempt 1 timed out or network stalled ({}), retrying once...",
                    provider, timeoutOrIo.getMessage());
            return executePost(body);
        } catch (HttpServerErrorException.BadGateway
                 | HttpServerErrorException.ServiceUnavailable
                 | HttpServerErrorException.GatewayTimeout gatewayErr) {
            log.warn("[LLM] Provider {} attempt 1 returned upstream gateway error ({}), retrying once...",
                    provider, gatewayErr.getStatusCode());
            return executePost(body);
        }
    }

    private String executePost(Map<String, Object> body) {
        var requestSpec = http.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON);

        String apiKey = props.getApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            requestSpec.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.trim());
        }

        return requestSpec
                .body(body)
                .retrieve()
                .body(String.class);
    }

    @Override
    public String name() {
        return provider + ":" + model;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getModel() {
        return model;
    }

    public String getProvider() {
        return provider;
    }

    private record ParsedCompletion(
            String text,
            int promptTokens,
            int completionTokens,
            int reasoningTokens,
            int cachedTokens,
            boolean emptyContentWithReasoning
    ) {
    }

    /**
     * Bóc tách câu trả lời và thông số token từ JSON phản hồi OpenAI Chat Completion.
     */
    private ParsedCompletion parseCompletion(String rawJson, String prompt) {
        if (rawJson == null || rawJson.isBlank()) {
            return new ParsedCompletion(buildFallbackAnswer(prompt, "Phản hồi rỗng từ " + provider), 0, 0, 0, 0, false);
        }

        try {
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode usage = root.path("usage");
            int promptTokens = usage.path("prompt_tokens").asInt(0);
            int completionTokens = usage.path("completion_tokens").asInt(0);
            int reasoningTokens = usage.path("completion_tokens_details").path("reasoning_tokens").asInt(0);
            int cachedTokens = usage.path("prompt_tokens_details").path("cached_tokens").asInt(0);

            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                JsonNode errorNode = root.path("error");
                if (!errorNode.isMissingNode()) {
                    String msg = errorNode.path("message").asText("Lỗi API từ " + provider);
                    return new ParsedCompletion(buildFallbackAnswer(prompt, msg), promptTokens, completionTokens, reasoningTokens, cachedTokens, false);
                }
                return new ParsedCompletion(buildFallbackAnswer(prompt, "Không tìm thấy trường 'choices' trong phản hồi từ " + provider),
                        promptTokens, completionTokens, reasoningTokens, cachedTokens, false);
            }

            JsonNode message = choices.get(0).path("message");
            String content = message.hasNonNull("content") ? message.get("content").asText() : "";
            String reasoning = message.hasNonNull("reasoning_content") ? message.get("reasoning_content").asText() : "";

            // Loại bỏ thẻ <think>...</think> nếu là model reasoning (DeepSeek-R1)
            String text = content.replaceAll("(?s)<think>.*?</think>", "").strip();

            // Nếu content rỗng nhưng có reasoning_content, sử dụng reasoning_content
            if (text.isEmpty() && !reasoning.isBlank()) {
                text = reasoning.replaceAll("(?s)<think>.*?</think>", "").strip();
            }

            if (text.isEmpty()) {
                boolean hadHiddenReasoning = reasoningTokens > 0;
                return new ParsedCompletion(buildFallbackAnswer(prompt, "Nội dung trả lời rỗng từ " + provider),
                        promptTokens, completionTokens, reasoningTokens, cachedTokens, hadHiddenReasoning);
            }

            return new ParsedCompletion(text, promptTokens, completionTokens, reasoningTokens, cachedTokens, false);
        } catch (Exception e) {
            log.warn("[{}] Lỗi parse JSON phản hồi LLM: {}", provider, e.getMessage());
            return new ParsedCompletion(buildFallbackAnswer(prompt, "Lỗi giải mã JSON phản hồi: " + e.getMessage()), 0, 0, 0, 0, false);
        }
    }

    /**
     * Tạo câu trả lời an toàn khi không kết nối được LLM hoặc gặp lỗi mạng/API.
     */
    private String buildFallbackAnswer(String prompt, String reason) {
        String question = extractQuestion(prompt);
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(provider).append("-error] ");
        sb.append("Không thể kết nối hoặc nhận phản hồi từ ").append(provider);
        sb.append(" (model: `").append(model).append("`, endpoint: `").append(endpoint).append("`).");
        if (!question.isBlank()) {
            sb.append(" Câu hỏi đã ghi nhận: \"").append(question).append("\".");
        }
        sb.append(" (Chi tiết: ").append(reason).append(")");
        return sb.toString();
    }

    private String extractQuestion(String prompt) {
        if (prompt == null) {
            return "";
        }
        int idx = prompt.lastIndexOf("Câu hỏi:");
        if (idx >= 0) {
            String tail = prompt.substring(idx + "Câu hỏi:".length()).trim();
            int nl = tail.indexOf('\n');
            return nl >= 0 ? tail.substring(0, nl).trim() : tail;
        }
        return "";
    }

    /**
     * Chuẩn hóa URL endpoint cho chuẩn OpenAI chat completion.
     */
    public static String normalizeEndpoint(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "http://localhost:11434/chat/completions";
        }
        String url = baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1).trim();
        }
        if (!url.toLowerCase().endsWith("/chat/completions")) {
            url += "/chat/completions";
        }
        return url;
    }
}
