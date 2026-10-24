package com.example.docmind.service.llm;

import com.example.docmind.config.LlmProperties;
import com.example.docmind.service.reasoning.QueryComplexity;
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
 *   <li>Giới hạn read-timeout mỗi lượt thử (tối đa 90s khi bật {@code reasoning_effort}) kèm cơ chế tự động retry khi proxy trung gian bị treo kênh (timeout / 502 / 503 / 504).</li>
 *   <li>Tự động bóc tách và loại bỏ thẻ {@code <think>...</think>} cho các mô hình suy luận (DeepSeek-R1, reasoning).</li>
 *   <li>Hỗ trợ fallback sang trường {@code reasoning_content} nếu {@code content} để trống.</li>
 *   <li>Xử lý lỗi và HTTP status linh hoạt, fallback an toàn không làm crash luồng nghiệp vụ.</li>
 * </ul>
 */
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);
    private static final int FAST_REASONING_ATTEMPT_TIMEOUT_MS = 90_000;

    private final LlmProperties props;
    private final ObjectMapper objectMapper;
    private final RestClient http;
    private final RestClient simpleHttp;
    private final RestClient deepHttp;
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
            this.simpleHttp = customHttp;
            this.deepHttp = customHttp;
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

            JdkClientHttpRequestFactory simpleFactory = new JdkClientHttpRequestFactory(jdkClient);
            simpleFactory.setReadTimeout(Duration.ofSeconds(ReasoningBudget.SIMPLE_TIMEOUT_SECONDS));
            this.simpleHttp = RestClient.builder().requestFactory(simpleFactory).build();

            JdkClientHttpRequestFactory deepFactory = new JdkClientHttpRequestFactory(jdkClient);
            deepFactory.setReadTimeout(Duration.ofSeconds(ReasoningBudget.DEEP_TIMEOUT_SECONDS));
            this.deepHttp = RestClient.builder().requestFactory(deepFactory).build();
        }

        log.info("OpenAiCompatibleLlmClient active (provider={}, model={}, endpoint={}, reasoningEffort={})",
                this.provider, this.model, this.endpoint, this.reasoningEffort != null ? this.reasoningEffort : "default");
    }

    @Override
    public String complete(String prompt) {
        return complete(prompt, null);
    }

    @Override
    public String complete(String prompt, ReasoningBudget budget) {
        long startNs = System.nanoTime();
        String safePrompt = prompt != null ? prompt : "";
        String effort = (budget != null && budget.reasoningEffort() != null)
                ? budget.reasoningEffort()
                : (reasoningEffort != null ? reasoningEffort : "default");
        int targetTokens = (budget != null && budget.maxTokens() > 0) ? budget.maxTokens() : props.getMaxTokens();
        log.info("[LLM] Calling provider={}, model={}, endpoint={}, reasoningEffort={}, maxTokens={}, budgetTier={}, promptChars={}",
                provider, model, endpoint, effort, targetTokens,
                budget != null ? budget.complexity().name() : "DEFAULT", safePrompt.length());
        try {
            Map<String, Object> body = buildRequestBody(safePrompt, true, budget);
            String rawJson;
            try {
                rawJson = executePostWithRetry(body, budget);
            } catch (HttpClientErrorException.BadRequest badReq) {
                if (body.containsKey("reasoning_effort") || body.containsKey("max_tokens")) {
                    log.info("[LLM] Provider {} rejected optional reasoning_effort/max_tokens (400), retrying standard payload", provider);
                    rawJson = executePostWithRetry(buildRequestBody(safePrompt, false, budget), budget);
                } else {
                    throw badReq;
                }
            }

            ParsedCompletion parsed = parseCompletion(rawJson, prompt);
            if (parsed.emptyContentWithReasoning()) {
                log.info("[LLM] Provider {} returned empty content after {} reasoning tokens, retrying once without reasoning_effort",
                        provider, parsed.reasoningTokens());
                rawJson = executePostWithRetry(buildRequestBody(safePrompt, false, budget), budget);
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
        return buildRequestBody(safePrompt, includeOptionalTuning, null);
    }

    private Map<String, Object> buildRequestBody(String safePrompt, boolean includeOptionalTuning, ReasoningBudget budget) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "user", "content", safePrompt)));
        double temp = (budget != null && budget.temperature() >= 0) ? budget.temperature() : props.getTemperature();
        body.put("temperature", temp);
        body.put("stream", false);
        if (includeOptionalTuning) {
            String effort = (budget != null && budget.reasoningEffort() != null && !budget.reasoningEffort().isBlank())
                    ? budget.reasoningEffort()
                    : this.reasoningEffort;
            if (effort != null && !effort.isBlank()) {
                body.put("reasoning_effort", effort);
            }
            int maxToks = (budget != null && budget.maxTokens() > 0)
                    ? budget.maxTokens()
                    : props.getMaxTokens();
            if (maxToks > 0) {
                body.put("max_tokens", maxToks);
            }
            Double effectiveTopP = (budget != null && budget.topP() != null)
                    ? budget.topP()
                    : props.getTopP();
            if (effectiveTopP != null && effectiveTopP > 0.0 && effectiveTopP <= 1.0) {
                body.put("top_p", effectiveTopP);
            }
        }
        if (endpoint != null && endpoint.toLowerCase().endsWith("/messages") && !body.containsKey("max_tokens")) {
            body.put("max_tokens", props.getMaxTokens() > 0 ? props.getMaxTokens() : 1024);
        }
        return body;
    }

    private String executePostWithRetry(Map<String, Object> body) {
        return executePostWithRetry(body, null);
    }

    private String executePostWithRetry(Map<String, Object> body, ReasoningBudget budget) {
        try {
            return executePost(body, budget);
        } catch (ResourceAccessException timeoutOrIo) {
            log.warn("[LLM] Provider {} attempt 1 timed out or network stalled ({}), retrying once...",
                    provider, timeoutOrIo.getMessage());
            return executePost(body, budget);
        } catch (HttpServerErrorException.BadGateway
                 | HttpServerErrorException.ServiceUnavailable
                 | HttpServerErrorException.GatewayTimeout gatewayErr) {
            log.warn("[LLM] Provider {} attempt 1 returned upstream gateway error ({}), retrying once...",
                    provider, gatewayErr.getStatusCode());
            return executePost(body, budget);
        }
    }

    private String executePost(Map<String, Object> body) {
        return executePost(body, null);
    }

    private String executePost(Map<String, Object> body, ReasoningBudget budget) {
        RestClient clientToUse = this.http;
        if (budget != null) {
            if (budget.complexity() == QueryComplexity.SIMPLE && simpleHttp != null) {
                clientToUse = simpleHttp;
            } else if (budget.complexity() == QueryComplexity.DEEP_REASONING && deepHttp != null) {
                clientToUse = deepHttp;
            }
        }
        var requestSpec = clientToUse.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON);

        String apiKey = props.getApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            String trimmedKey = apiKey.trim();
            requestSpec.header(HttpHeaders.AUTHORIZATION, "Bearer " + trimmedKey);
            if ("anthropic".equalsIgnoreCase(provider)) {
                requestSpec.header("x-api-key", trimmedKey);
                requestSpec.header("anthropic-version", "2023-06-01");
            }
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
     * Bóc tách câu trả lời và thông số token từ JSON phản hồi OpenAI Chat Completion (hoặc Anthropic Messages / Gemini API).
     */
    private ParsedCompletion parseCompletion(String rawJson, String prompt) {
        if (rawJson == null || rawJson.isBlank()) {
            return new ParsedCompletion(buildFallbackAnswer(prompt, "Phản hồi rỗng từ " + provider), 0, 0, 0, 0, false);
        }

        try {
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode usage = root.path("usage");
            int promptTokens = usage.path("prompt_tokens").asInt(usage.path("input_tokens").asInt(0));
            int completionTokens = usage.path("completion_tokens").asInt(usage.path("output_tokens").asInt(0));
            int reasoningTokens = usage.path("completion_tokens_details").path("reasoning_tokens").asInt(0);
            int cachedTokens = usage.path("prompt_tokens_details").path("cached_tokens").asInt(0);

            // Hỗ trợ cả cấu trúc phản hồi native của Anthropic (/v1/messages -> content[0].text)
            JsonNode anthropicContent = root.path("content");
            if (anthropicContent.isArray() && !anthropicContent.isEmpty() && !root.has("choices")) {
                StringBuilder anthropicText = new StringBuilder();
                for (JsonNode block : anthropicContent) {
                    if (block.hasNonNull("text")) {
                        anthropicText.append(block.get("text").asText());
                    }
                }
                String cleaned = anthropicText.toString().replaceAll("(?s)<think>.*?</think>", "").strip();
                if (!cleaned.isEmpty()) {
                    return new ParsedCompletion(cleaned, promptTokens, completionTokens, reasoningTokens, cachedTokens, false);
                }
            }

            // Hỗ trợ cả cấu trúc phản hồi native của Gemini (:generateContent -> candidates[0].content.parts[0].text)
            JsonNode geminiCandidates = root.path("candidates");
            if (geminiCandidates.isArray() && !geminiCandidates.isEmpty() && !root.has("choices")) {
                JsonNode parts = geminiCandidates.get(0).path("content").path("parts");
                if (parts.isArray() && !parts.isEmpty()) {
                    StringBuilder geminiText = new StringBuilder();
                    for (JsonNode part : parts) {
                        if (part.hasNonNull("text")) {
                            geminiText.append(part.get("text").asText());
                        }
                    }
                    String cleaned = geminiText.toString().replaceAll("(?s)<think>.*?</think>", "").strip();
                    if (!cleaned.isEmpty()) {
                        return new ParsedCompletion(cleaned, promptTokens, completionTokens, reasoningTokens, cachedTokens, false);
                    }
                }
            }

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
     * Chuẩn hóa URL endpoint cho chuẩn OpenAI chat completion (hoặc giữ nguyên /messages cho Anthropic, :generateContent cho Gemini).
     */
    public static String normalizeEndpoint(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return new LlmProperties().resolveBaseUrl() + "/chat/completions";
        }
        String url = baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1).trim();
        }
        String lower = url.toLowerCase();
        if (!lower.endsWith("/chat/completions")
                && !lower.endsWith("/messages")
                && !lower.contains(":generatecontent")) {
            url += "/chat/completions";
        }
        return url;
    }
}
