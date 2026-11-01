package com.example.docmind.service.llm;

import com.example.docmind.config.LlmProperties;
import com.example.docmind.service.bot.ThinkingMode;
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
import tools.jackson.databind.node.ObjectNode;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 *   <li>Tự động strip trường {@code reasoning_brief} khỏi JSON phản hồi trước khi trả ra ngoài và tuyệt đối không gửi trường {@code think} đặc thù của Ollama.</li>
 *   <li>Xử lý lỗi và HTTP status linh hoạt, fallback an toàn không làm crash luồng nghiệp vụ.</li>
 * </ul>
 */
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);
    private static final int FAST_REASONING_ATTEMPT_TIMEOUT_MS = 90_000;
    private static final int MAX_UPSTREAM_NETWORK_RETRIES = 1;
    private static final long UPSTREAM_RETRY_BACKOFF_MS = 350L;
    private static final long READ_TIMEOUT_STALL_FAIL_FAST_THRESHOLD_MS = 25_000L;

    public record OpenAiCallTelemetry(
            String provider,
            String model,
            ThinkingMode thinkingMode,
            int maxTokens,
            boolean containedThinkField,
            String reasoningBrief,
            long latencyMs,
            int promptChars,
            int promptTokens,
            int completionTokens,
            int reasoningTokens,
            int cachedTokens
    ) {
        public OpenAiCallTelemetry(
                String provider,
                String model,
                ThinkingMode thinkingMode,
                int maxTokens,
                boolean containedThinkField,
                String reasoningBrief,
                long latencyMs
        ) {
            this(provider, model, thinkingMode, maxTokens, containedThinkField, reasoningBrief, latencyMs, 0, 0, 0, 0, 0);
        }

        public int thinkingChars() {
            return reasoningBrief != null ? reasoningBrief.length() : 0;
        }

        public long thinkingLatencyMs() {
            return 0L;
        }

        public long contentLatencyMs() {
            return latencyMs;
        }
    }

    private final LlmProperties props;
    private final ObjectMapper objectMapper;
    private final RestClient http;
    private final RestClient simpleHttp;
    private final RestClient deepHttp;
    private final String provider;
    private final String model;
    private final String endpoint;
    private final String reasoningEffort;
    private volatile OpenAiCallTelemetry lastCallTelemetry = null;

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

    public OpenAiCallTelemetry getLastCallTelemetry() {
        return lastCallTelemetry;
    }

    @Override
    public String complete(String prompt) {
        return complete(prompt, null);
    }

    private boolean isOfficialDashScopeEndpoint() {
        if (endpoint == null) {
            return false;
        }
        String lower = endpoint.toLowerCase(Locale.ROOT);
        return lower.contains("dashscope") || lower.contains("aliyuncs.com");
    }

    private boolean isQwenModelOrProvider() {
        return "qwen".equalsIgnoreCase(provider)
                || (model != null && model.toLowerCase(Locale.ROOT).contains("qwen"));
    }

    private String resolveEffectiveReasoningEffort(ReasoningBudget budget) {
        boolean suppressBudgetReasoningEffortForQwenDashScope = isQwenModelOrProvider()
                && isOfficialDashScopeEndpoint()
                && (this.reasoningEffort == null || this.reasoningEffort.isBlank());
        if (suppressBudgetReasoningEffortForQwenDashScope) {
            return null;
        }
        if (isQwenModelOrProvider()
                && !isOfficialDashScopeEndpoint()
                && (this.reasoningEffort == null || this.reasoningEffort.isBlank())
                && budget != null
                && budget.thinkingMode() == ThinkingMode.FAST_NO_THINK) {
            return "none";
        }
        if (budget != null && budget.reasoningEffort() != null && !budget.reasoningEffort().isBlank()) {
            return budget.reasoningEffort();
        }
        return this.reasoningEffort;
    }

    @Override
    public String complete(String prompt, ReasoningBudget budget) {
        long startNs = System.nanoTime();
        String safePrompt = prompt != null ? prompt : "";
        String resolvedEffort = resolveEffectiveReasoningEffort(budget);
        String effort = (resolvedEffort != null && !resolvedEffort.isBlank()) ? resolvedEffort : "default";
        ThinkingMode thinkingMode = budget != null ? budget.thinkingMode() : null;
        int targetTokens = (budget != null && budget.maxTokens() > 0)
                ? budget.maxTokens()
                : (thinkingMode != null ? thinkingMode.maxOutputTokens() : props.getMaxTokens());
        log.info("[LLM] Calling provider={}, model={}, endpoint={}, thinkingMode={}, reasoningEffort={}, maxTokens={}, budgetTier={}, promptChars={}",
                provider, model, endpoint,
                thinkingMode != null ? thinkingMode.name() : "NONE",
                effort, targetTokens,
                budget != null && budget.complexity() != null ? budget.complexity().name() : "DEFAULT", safePrompt.length());
        try {
            Map<String, Object> body = buildRequestBody(safePrompt, true, budget);
            boolean hasThinkField = body.containsKey("think");
            String rawJson;
            try {
                rawJson = executePostWithRetry(body, budget);
            } catch (HttpClientErrorException.BadRequest badReq) {
                if (body.containsKey("reasoning_effort") || body.containsKey("max_tokens") || body.containsKey("enable_thinking")) {
                    log.info("[LLM] Provider {} rejected optional reasoning_effort/max_tokens/enable_thinking (400), retrying standard payload", provider);
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

            StrippedReasoningResult stripped = stripReasoningBriefFromJson(parsed.text());
            String result = stripped.cleanedText();
            long latencyMs = (System.nanoTime() - startNs) / 1_000_000L;
            this.lastCallTelemetry = new OpenAiCallTelemetry(
                    provider,
                    model,
                    thinkingMode,
                    targetTokens,
                    hasThinkField,
                    stripped.reasoningBrief(),
                    latencyMs,
                    safePrompt.length(),
                    parsed.promptTokens(),
                    parsed.completionTokens(),
                    parsed.reasoningTokens(),
                    parsed.cachedTokens()
            );
            boolean isFallback = result != null && result.startsWith("[" + provider + "-error]");
            log.info("[LLM] Response received from provider={}, model={}, latencyMs={}, promptTokens={}, completionTokens={}, reasoningTokens={}, cachedTokens={}, status={}",
                    provider, model, latencyMs,
                    parsed.promptTokens(), parsed.completionTokens(), parsed.reasoningTokens(), parsed.cachedTokens(),
                    isFallback ? "fallback" : "ok");
            return result;
        } catch (Exception e) {
            long latencyMs = (System.nanoTime() - startNs) / 1_000_000L;
            this.lastCallTelemetry = new OpenAiCallTelemetry(
                    provider,
                    model,
                    thinkingMode,
                    targetTokens,
                    false,
                    "",
                    latencyMs
            );
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
        int splitIdx = safePrompt != null ? safePrompt.indexOf("<!-- SYSTEM_USER_SPLIT -->") : -1;
        if (splitIdx >= 0) {
            String systemPart = safePrompt.substring(0, splitIdx).strip();
            String userPart = safePrompt.substring(splitIdx + "<!-- SYSTEM_USER_SPLIT -->".length()).strip();
            body.put("messages", List.of(
                    Map.of("role", "system", "content", systemPart),
                    Map.of("role", "user", "content", userPart)
            ));
        } else {
            body.put("messages", List.of(Map.of("role", "user", "content", safePrompt != null ? safePrompt : "")));
        }
        double temp = (budget != null && budget.temperature() >= 0) ? budget.temperature() : props.getTemperature();
        body.put("temperature", temp);
        body.put("stream", false);
        if (includeOptionalTuning) {
            String effort = resolveEffectiveReasoningEffort(budget);
            if (effort != null && !effort.isBlank()) {
                body.put("reasoning_effort", effort);
            }
            ThinkingMode thinkingMode = budget != null ? budget.thinkingMode() : null;
            if ("qwen".equalsIgnoreCase(provider)
                    || (isQwenModelOrProvider() && (thinkingMode == null || thinkingMode == ThinkingMode.FAST_NO_THINK))) {
                body.put("enable_thinking", false);
            }
            int maxToks = (budget != null && budget.maxTokens() > 0)
                    ? budget.maxTokens()
                    : (thinkingMode != null ? thinkingMode.maxOutputTokens() : props.getMaxTokens());
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
        // Tuyệt đối không gửi trường "think", "options", "keep_alive" đặc thù của Ollama sang OpenAI-compatible API
        body.remove("think");
        body.remove("options");
        body.remove("keep_alive");
        return body;
    }

    private String executePostWithRetry(Map<String, Object> body) {
        return executePostWithRetry(body, null);
    }

    private String executePostWithRetry(Map<String, Object> body, ReasoningBudget budget) {
        int timeoutSec = budget != null && budget.timeoutSeconds() > 0
                ? budget.timeoutSeconds()
                : props.getTimeoutSeconds();
        long attempt1StartNs = System.nanoTime();
        try {
            return executePost(body, budget);
        } catch (ResourceAccessException timeoutOrIo) {
            long attempt1ElapsedMs = (System.nanoTime() - attempt1StartNs) / 1_000_000L;
            Object currentEffortObj = body != null ? body.get("reasoning_effort") : null;
            String currentEffort = currentEffortObj != null ? String.valueOf(currentEffortObj) : "";
            if (attempt1ElapsedMs >= READ_TIMEOUT_STALL_FAIL_FAST_THRESHOLD_MS) {
                boolean alreadyMinEffort = currentEffort.isBlank() || "none".equalsIgnoreCase(currentEffort);
                if (alreadyMinEffort || attempt1ElapsedMs >= 35_000L) {
                    log.warn("[LLM] Provider {} attempt 1 hit long read-timeout stall ({}ms >= {}ms, timeout={}s, effort={}, err={}); skipping redundant retry to prevent 2x latency stall",
                            provider, attempt1ElapsedMs, READ_TIMEOUT_STALL_FAIL_FAST_THRESHOLD_MS, timeoutSec,
                            currentEffort.isBlank() ? "default" : currentEffort, timeoutOrIo.getMessage());
                    throw timeoutOrIo;
                }
                Map<String, Object> downgradedBody = new LinkedHashMap<>(body);
                downgradedBody.put("reasoning_effort", "none");
                log.warn("[LLM] Provider {} attempt 1 stalled at {}ms with effort={}; retrying once with downgraded reasoning_effort=none...",
                        provider, attempt1ElapsedMs, currentEffort);
                sleepBoundedBackoff(UPSTREAM_RETRY_BACKOFF_MS);
                return executePost(downgradedBody, budget);
            }
            log.warn("[LLM] Provider {} attempt 1/{} transient I/O failure after {}ms (timeout={}s, backoff={}ms, err={}), retrying max {} time...",
                    provider, MAX_UPSTREAM_NETWORK_RETRIES + 1, attempt1ElapsedMs, timeoutSec, UPSTREAM_RETRY_BACKOFF_MS,
                    timeoutOrIo.getMessage(), MAX_UPSTREAM_NETWORK_RETRIES);
            sleepBoundedBackoff(UPSTREAM_RETRY_BACKOFF_MS);
            return executePost(body, budget);
        } catch (HttpServerErrorException.BadGateway
                 | HttpServerErrorException.ServiceUnavailable
                 | HttpServerErrorException.GatewayTimeout gatewayErr) {
            log.warn("[LLM] Provider {} attempt 1/{} returned upstream gateway error ({}, timeout={}s, backoff={}ms), retrying max {} time...",
                    provider, MAX_UPSTREAM_NETWORK_RETRIES + 1, gatewayErr.getStatusCode(),
                    timeoutSec, UPSTREAM_RETRY_BACKOFF_MS, MAX_UPSTREAM_NETWORK_RETRIES);
            sleepBoundedBackoff(UPSTREAM_RETRY_BACKOFF_MS);
            return executePost(body, budget);
        }
    }

    private static void sleepBoundedBackoff(long backoffMs) {
        if (backoffMs <= 0L) {
            return;
        }
        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private String executePost(Map<String, Object> body) {
        return executePost(body, null);
    }

    private String executePost(Map<String, Object> body, ReasoningBudget budget) {
        RestClient clientToUse = this.http;
        if (budget != null) {
            if (budget.timeoutSeconds() > ReasoningBudget.DEEP_TIMEOUT_SECONDS) {
                clientToUse = this.http;
            } else if (budget.complexity() == QueryComplexity.SIMPLE
                    && (budget.timeoutSeconds() <= 0 || budget.timeoutSeconds() <= ReasoningBudget.SIMPLE_TIMEOUT_SECONDS)
                    && simpleHttp != null) {
                clientToUse = simpleHttp;
            } else if ((budget.complexity() == QueryComplexity.DEEP_REASONING
                    || budget.timeoutSeconds() > ReasoningBudget.SIMPLE_TIMEOUT_SECONDS)
                    && deepHttp != null) {
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

            JsonNode choice0 = choices.get(0);
            String finishReason = choice0.path("finish_reason").asText("");
            JsonNode message = choice0.path("message");
            String content = message.hasNonNull("content") ? message.get("content").asText() : "";
            String reasoning = message.hasNonNull("reasoning_content") ? message.get("reasoning_content").asText() : "";

            // Loại bỏ thẻ <think>...</think> (kể cả thẻ <think> bị cắt cụt chưa đóng do chạm trần max_tokens)
            String text = content.replaceAll("(?s)<think>.*?(?:</think>|$)", "").strip();

            // Chỉ trích xuất từ reasoning_content khi bên trong có phần trả lời thực tế nằm sau thẻ đóng </think>
            // (TUYỆT ĐỐI KHÔNG chép nguyên văn chuỗi suy luận nội tâm thô ra ngoài làm câu trả lời khách hàng khi content rỗng / finish_reason=length!)
            if (text.isEmpty() && !reasoning.isBlank() && reasoning.contains("</think>") && !"length".equalsIgnoreCase(finishReason)) {
                text = reasoning.replaceAll("(?s)<think>.*?</think>", "").strip();
            }

            if (text.isEmpty() || isTruncatedReasoningMonologueOnLengthLimit(text, finishReason, prompt)) {
                boolean hadHiddenReasoning = reasoningTokens > 0
                        || !reasoning.isBlank()
                        || content.contains("<think>")
                        || "length".equalsIgnoreCase(finishReason);
                return new ParsedCompletion(buildFallbackAnswer(prompt, "Nội dung trả lời bị cắt cụt/rỗng từ " + provider),
                        promptTokens, completionTokens, reasoningTokens, cachedTokens, hadHiddenReasoning);
            }

            return new ParsedCompletion(text, promptTokens, completionTokens, reasoningTokens, cachedTokens, false);
        } catch (Exception e) {
            log.warn("[{}] Lỗi parse JSON phản hồi LLM: {}", provider, e.getMessage());
            return new ParsedCompletion(buildFallbackAnswer(prompt, "Lỗi giải mã JSON phản hồi: " + e.getMessage()), 0, 0, 0, 0, false);
        }
    }

    private static boolean isTruncatedReasoningMonologueOnLengthLimit(String text, String finishReason, String prompt) {
        if (!"length".equalsIgnoreCase(finishReason) || text == null || text.isBlank()) {
            return false;
        }
        String stripped = text.strip();
        if (stripped.startsWith("{") && stripped.endsWith("}")) {
            return false;
        }
        String low = stripped.toLowerCase(java.util.Locale.ROOT);
        if (low.startsWith("we need ")
                || low.startsWith("let's ")
                || low.startsWith("the user ")
                || low.contains("need produce final json")) {
            return true;
        }
        boolean expectsStructuredJson = prompt != null
                && (prompt.contains("\"bubbles\"") || prompt.contains("CHỈ XUẤT 1 JSON OBJECT"));
        return expectsStructuredJson && (!stripped.contains("{") || !stripped.contains("\"bubbles\""));
    }

    private record StrippedReasoningResult(String cleanedText, String reasoningBrief) {
    }

    private StrippedReasoningResult stripReasoningBriefFromJson(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return new StrippedReasoningResult(rawText, "");
        }
        String candidate = rawText.strip();
        int braceStart = candidate.indexOf('{');
        int braceEnd = candidate.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart && candidate.contains("reasoning_brief")) {
            String jsonSegment = candidate.substring(braceStart, braceEnd + 1);
            try {
                JsonNode parsed = objectMapper.readTree(jsonSegment);
                if (parsed != null && parsed.isObject() && parsed.has("reasoning_brief")) {
                    ObjectNode objNode = (ObjectNode) parsed;
                    String brief = objNode.path("reasoning_brief").asText("").strip();
                    objNode.remove("reasoning_brief");
                    String cleanedJson = objectMapper.writeValueAsString(objNode);
                    return new StrippedReasoningResult(cleanedJson, brief);
                }
            } catch (Exception ignored) {
                // Fallback regex strip if JSON was wrapped or partially formatted
                String regexStripped = candidate.replaceAll("\"reasoning_brief\"\\s*:\\s*\"(?:\\\\.|[^\"\\\\])*\"\\s*,?\\s*", "");
                return new StrippedReasoningResult(regexStripped.strip(), "");
            }
        }
        return new StrippedReasoningResult(rawText, "");
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
