package com.example.docmind.service.llm;

import com.example.docmind.config.OllamaEnabledCondition;
import com.example.docmind.config.OllamaProperties;
import com.example.docmind.service.bot.ThinkingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Client gọi Ollama chạy local theo cấu hình từ {@link com.example.docmind.config.LlmProperties}.
 * Tối ưu cho máy chạy Unified Memory / CPU 16GB RAM (giới hạn num_ctx=4096, temperature=0.2, readTimeout=45s).
 *
 * <p>Hỗ trợ song song:
 * <ul>
 *   <li>{@code /api/chat} với trường {@code "think": true/false} và {@code options.num_predict} ánh xạ từ
 *       {@link ThinkingMode} (dành cho Qwen 3 14B và mọi lệnh có {@link ReasoningBudget#hasExplicitThinkingMode()}).
 *       Tách bạch hoàn toàn {@code message.thinking} (chỉ ghi telemetry/log nội bộ) và {@code message.content}
 *       (kèm fallback strip {@code <think>...</think>}).</li>
 *   <li>{@code /api/generate} cho các lệnh gọi legacy không truyền {@link ThinkingMode} trên dòng model Qwen 2.5 cũ.</li>
 * </ul>
 */
@Component
@Primary
@Conditional(OllamaEnabledCondition.class)
public class OllamaLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaLlmClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Telemetry nội bộ của lần gọi Ollama gần nhất để phục vụ kiểm thử và giám sát hiệu năng.
     */
    public record OllamaCallTelemetry(
            String apiPath,
            String endpoint,
            String model,
            ThinkingMode thinkingMode,
            Boolean thinkFlag,
            int numPredict,
            int thinkingChars,
            String internalThinkingPreview,
            long latencyMs,
            long thinkingLatencyMs,
            long contentLatencyMs,
            long loadDurationNs,
            int promptEvalCount,
            long promptEvalDurationNs,
            int evalCount,
            long evalDurationNs,
            long totalDurationNs
    ) {
        public OllamaCallTelemetry(
                String apiPath,
                String endpoint,
                String model,
                ThinkingMode thinkingMode,
                Boolean thinkFlag,
                int numPredict,
                int thinkingChars,
                String internalThinkingPreview,
                long latencyMs,
                long thinkingLatencyMs,
                long contentLatencyMs
        ) {
            this(
                    apiPath,
                    endpoint,
                    model,
                    thinkingMode,
                    thinkFlag,
                    numPredict,
                    thinkingChars,
                    internalThinkingPreview,
                    latencyMs,
                    thinkingLatencyMs,
                    contentLatencyMs,
                    0L,
                    0,
                    0L,
                    0,
                    0L,
                    latencyMs * 1_000_000L
            );
        }

        public OllamaCallTelemetry(
                String apiPath,
                String model,
                ThinkingMode thinkingMode,
                Boolean thinkFlag,
                int numPredict,
                int thinkingChars,
                String internalThinkingPreview,
                long latencyMs,
                long thinkingLatencyMs,
                long contentLatencyMs
        ) {
            this(
                    apiPath,
                    apiPath,
                    model,
                    thinkingMode,
                    thinkFlag,
                    numPredict,
                    thinkingChars,
                    internalThinkingPreview,
                    latencyMs,
                    thinkingLatencyMs,
                    contentLatencyMs,
                    0L,
                    0,
                    0L,
                    0,
                    0L,
                    latencyMs * 1_000_000L
            );
        }

        public OllamaCallTelemetry(
                String apiPath,
                String model,
                ThinkingMode thinkingMode,
                Boolean thinkFlag,
                int numPredict,
                int thinkingChars,
                String internalThinkingPreview,
                long latencyMs
        ) {
            this(
                    apiPath,
                    apiPath,
                    model,
                    thinkingMode,
                    thinkFlag,
                    numPredict,
                    thinkingChars,
                    internalThinkingPreview,
                    latencyMs,
                    (Boolean.TRUE.equals(thinkFlag) && thinkingChars > 0)
                            ? Math.max(1L, Math.round(latencyMs * 0.45))
                            : 0L,
                    (Boolean.TRUE.equals(thinkFlag) && thinkingChars > 0)
                            ? Math.max(0L, latencyMs - Math.max(1L, Math.round(latencyMs * 0.45)))
                            : latencyMs,
                    0L,
                    0,
                    0L,
                    0,
                    0L,
                    latencyMs * 1_000_000L
            );
        }

        public String provider() {
            return "ollama";
        }

        public boolean thinkRequested() {
            return Boolean.TRUE.equals(thinkFlag);
        }

        public long loadDurationMs() {
            return loadDurationNs / 1_000_000L;
        }

        public long promptEvalDurationMs() {
            return promptEvalDurationNs / 1_000_000L;
        }

        public long evalDurationMs() {
            return evalDurationNs / 1_000_000L;
        }

        public long totalDurationMs() {
            return totalDurationNs / 1_000_000L;
        }
    }

    private final OllamaProperties props;
    private final RestClient http;
    private final boolean enableTimeoutCooldown;
    private final java.util.concurrent.Semaphore concurrencyGate = new java.util.concurrent.Semaphore(1);
    private volatile long cooldownUntilEpochMs = 0L;
    private volatile String activeRuntimeModel = null;
    private volatile OllamaCallTelemetry lastCallTelemetry = null;

    @Autowired
    public OllamaLlmClient(OllamaProperties props) {
        this.props = props;
        this.enableTimeoutCooldown = true;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        int readTimeoutMs = props.getReadTimeoutMs() > 0 ? props.getReadTimeoutMs() : 120000;
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

    public OllamaCallTelemetry getLastCallTelemetry() {
        return lastCallTelemetry;
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

        String effectiveModel = activeRuntimeModel != null ? activeRuntimeModel : props.getModel();
        ThinkingMode thinkingMode = budget != null ? budget.thinkingMode() : null;
        boolean useChatEndpoint = (thinkingMode != null) || isNativeThinkingCapableModel(effectiveModel);
        // v3.5.0: Tối ưu tốc độ phản hồi <= 22s trên qwen3:14b khi prompt runtime đã có đầy đủ <GROUNDED_TURN_FACTS> và <AUTHENTIC_EMOTIONAL_COMPASS>.
        boolean suppressModelThinkForGroundedTurn = props.isSuppressRedundantThinkWhenGrounded()
                && prompt != null
                && prompt.contains("<GROUNDED_TURN_FACTS>")
                && !prompt.contains("benchmark_mode: FORCE_ALWAYS_THINK_B");
        Boolean thinkFlag = useChatEndpoint
                ? Boolean.valueOf(thinkingMode != null && thinkingMode.isDeepThink() && !suppressModelThinkForGroundedTurn)
                : null;
        String effectiveKeepAlive = (props.getKeepAlive() != null && !props.getKeepAlive().isBlank())
                ? props.getKeepAlive().strip()
                : "30m";
        String apiPath = useChatEndpoint ? "/api/chat" : "/api/generate";
        String endpoint = base + apiPath;

        int configuredPredict = props.getNumPredict() > 0 ? props.getNumPredict() : 550;
        int numPredict;
        boolean isCleanAgentPrompt = prompt != null && prompt.contains("<CLEAN_AGENT_SYSTEM_PROMPT>");
        boolean isAciV2SinglePassPrompt = prompt != null && prompt.contains("<SINGLE_PASS_COGNITIVE_CONTRACT>");
        boolean forceFullThinkingPredict = Boolean.TRUE.equals(thinkFlag)
                && !isCleanAgentPrompt
                && (configuredPredict >= 2048
                    || (prompt != null && (prompt.contains("<AUTHENTIC_EMOTIONAL_COMPASS>")
                        || prompt.contains("<QWEN3_SELECTIVE_THINKING_PROTOCOL>"))));
        boolean isTargetedRepairCall = prompt != null && prompt.contains("FAST_TARGETED_REPAIR_HINT");
        if (isAciV2SinglePassPrompt) {
            numPredict = (Boolean.TRUE.equals(thinkFlag) && !isTargetedRepairCall) ? 1200 : 750;
        } else if (isCleanAgentPrompt) {
            // Giới hạn ngân sách suy luận hợp lý (960 tokens khi think=true, 450 khi think=false)
            // giúp đủ không gian cho <think> ngắn gọn + JSON 2 bong bóng mà không kéo dài evalMs tới 70-80s.
            numPredict = (Boolean.TRUE.equals(thinkFlag) && !isTargetedRepairCall) ? 960 : 450;
        } else if (suppressModelThinkForGroundedTurn && !isTargetedRepairCall) {
            numPredict = 750;
        } else if (thinkingMode != null) {
            int modeBudget = (budget != null && budget.maxTokens() > 0)
                    ? budget.maxTokens()
                    : thinkingMode.maxOutputTokens();
            // v3.4.1 Mục 0.3: khi think=true thật, bắt buộc gửi numPredict = 2048 (không bị ThinkingMode.maxOutputTokens() ép xuống 400–550).
            // Khi think=false giữ ngân sách nhỏ (400–550) để tối ưu tốc độ.
            numPredict = forceFullThinkingPredict
                    ? Math.max(2048, Math.max(configuredPredict, modeBudget))
                    : modeBudget;
        } else {
            numPredict = forceFullThinkingPredict
                    ? Math.max(2048, configuredPredict)
                    : ((budget != null && budget.maxTokens() > 0)
                        ? (budget.maxTokens() <= 900 ? budget.maxTokens() : Math.max(configuredPredict, 550))
                        : configuredPredict);
        }

        double temp = (suppressModelThinkForGroundedTurn && !isTargetedRepairCall)
                ? 0.3
                : ((budget != null && budget.temperature() >= 0)
                    ? budget.temperature()
                    : (props.getTemperature() > 0 ? props.getTemperature() : 0.3));
        double topP = (budget != null && budget.topP() != null && budget.topP() > 0.0 && budget.topP() <= 1.0)
                ? budget.topP()
                : (props.getTopP() > 0 ? props.getTopP() : 0.9);
        double repeatPenalty = (budget != null && budget.repeatPenalty() != null && budget.repeatPenalty() > 0.0)
                ? budget.repeatPenalty()
                : (props.getRepeatPenalty() > 0 ? props.getRepeatPenalty() : 1.08);
        int effectiveNumCtx = props.getNumCtx() > 0 ? props.getNumCtx() : 3072;
        if (isAciV2SinglePassPrompt && effectiveNumCtx < 12288) {
            effectiveNumCtx = 12288;
        }

        boolean compassInjected = prompt != null && prompt.contains("<AUTHENTIC_EMOTIONAL_COMPASS>");
        log.info("[CompassAudit] thinkingMode={}, thinkFlag={}, numPredict={}, compassInjected={}",
                thinkingMode != null ? thinkingMode.name() : "NONE",
                thinkFlag,
                numPredict,
                compassInjected);
        log.info("[LLM] Calling provider=ollama, model={}, endpoint={}, thinkingMode={}, think={}, keepAlive={}, numPredict={}, numCtx={}, temp={}, topP={}, repeatPenalty={}, budgetTier={}",
                effectiveModel, endpoint,
                thinkingMode != null ? thinkingMode.name() : "NONE",
                thinkFlag,
                effectiveKeepAlive,
                numPredict, effectiveNumCtx, temp, topP, repeatPenalty,
                budget != null && budget.complexity() != null ? budget.complexity().name() : "DEFAULT");

        long cooldownThresholdMs = Math.max(42_000L, (props.getReadTimeoutMs() > 0 ? props.getReadTimeoutMs() : 120_000L) - 3_000L);
        this.lastCallTelemetry = new OllamaCallTelemetry(
                apiPath,
                endpoint,
                effectiveModel,
                thinkingMode,
                thinkFlag,
                numPredict,
                0,
                "",
                0L,
                0L,
                0L,
                0L,
                0,
                0L,
                0,
                0L,
                0L
        );

        if (enableTimeoutCooldown && System.currentTimeMillis() < cooldownUntilEpochMs) {
            log.info("[LLM] Ollama runner in timeout cooldown -> fast-failing in 0ms to dynamic grounded synthesis");
            return buildFallbackAnswer(prompt, "Ollama CPU timeout cooldown");
        }
        if (!concurrencyGate.tryAcquire()) {
            log.info("[LLM] Ollama concurrency limit reached (1 active slot) -> load-shedding to dynamic grounded fallback");
            return buildFallbackAnswer(prompt, "Ollama concurrency load shedding");
        }
        try {
            Map<String, Object> body = buildOllamaRequestBody(
                    effectiveModel,
                    prompt,
                    useChatEndpoint,
                    thinkFlag,
                    effectiveKeepAlive,
                    temp,
                    topP,
                    repeatPenalty,
                    effectiveNumCtx,
                    numPredict
            );
            String rawJson = http.post()
                    .uri(apiPath)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            Map<String, Object> resp = (rawJson != null && !rawJson.isBlank())
                    ? MAPPER.readValue(rawJson, Map.class)
                    : null;
            ExtractedOllamaMessage extracted = extractResponseMessage(resp, prompt);
            long loadDurationNs = extractLongMetric(resp, "load_duration");
            int promptEvalCount = (int) extractLongMetric(resp, "prompt_eval_count");
            long promptEvalDurationNs = extractLongMetric(resp, "prompt_eval_duration");
            int evalCount = (int) extractLongMetric(resp, "eval_count");
            long evalDurationNs = extractLongMetric(resp, "eval_duration");
            long totalDurationNs = extractLongMetric(resp, "total_duration");
            long firstCallLatencyMs = (System.nanoTime() - startNs) / 1_000_000L;
            boolean usedTwoStepFollowup = false;
            boolean isFallback = extracted.content() != null && extracted.content().startsWith("[ollama-offline]");
            boolean isTruncatedByThink = Boolean.TRUE.equals(thinkFlag)
                    && extracted.thinkingChars() > 0
                    && (isFallback || isTruncatedAfterThinking(extracted.content(), resp, prompt));
            if (isTruncatedByThink) {
                log.info("[LLM] Qwen3 thinking trace ({} chars) reached numPredict={} before completing JSON content -> completing JSON with think=false",
                        extracted.thinkingChars(), numPredict);
                Map<String, Object> fastFollowupBody = buildOllamaRequestBody(
                        effectiveModel,
                        prompt,
                        true,
                        Boolean.FALSE,
                        effectiveKeepAlive,
                        temp,
                        topP,
                        repeatPenalty,
                        effectiveNumCtx,
                        numPredict
                );
                String followupJson = http.post()
                        .uri(apiPath)
                        .body(fastFollowupBody)
                        .retrieve()
                        .body(String.class);
                Map<String, Object> followupResp = (followupJson != null && !followupJson.isBlank())
                        ? MAPPER.readValue(followupJson, Map.class)
                        : null;
                ExtractedOllamaMessage followupExtracted = extractResponseMessage(followupResp, prompt);
                if (followupExtracted.content() != null && !followupExtracted.content().startsWith("[ollama-offline]")) {
                    extracted = new ExtractedOllamaMessage(
                            followupExtracted.content(),
                            extracted.thinkingChars(),
                            extracted.thinkingPreview()
                    );
                    loadDurationNs += extractLongMetric(followupResp, "load_duration");
                    promptEvalCount += (int) extractLongMetric(followupResp, "prompt_eval_count");
                    promptEvalDurationNs += extractLongMetric(followupResp, "prompt_eval_duration");
                    evalCount += (int) extractLongMetric(followupResp, "eval_count");
                    evalDurationNs += extractLongMetric(followupResp, "eval_duration");
                    totalDurationNs += extractLongMetric(followupResp, "total_duration");
                    isFallback = false;
                    usedTwoStepFollowup = true;
                }
            }
            long latencyMs = (System.nanoTime() - startNs) / 1_000_000L;
            long measuredThinkingLatencyMs = 0L;
            long measuredContentLatencyMs = latencyMs;
            if (Boolean.TRUE.equals(thinkFlag) && extracted.thinkingChars() > 0) {
                if (usedTwoStepFollowup) {
                    measuredThinkingLatencyMs = firstCallLatencyMs;
                    measuredContentLatencyMs = Math.max(0L, latencyMs - firstCallLatencyMs);
                } else {
                    int contentChars = extracted.content() != null ? extracted.content().length() : 0;
                    int totalChars = Math.max(1, extracted.thinkingChars() + contentChars);
                    measuredThinkingLatencyMs = Math.max(1L, Math.round(latencyMs * ((double) extracted.thinkingChars() / totalChars)));
                    measuredContentLatencyMs = Math.max(0L, latencyMs - measuredThinkingLatencyMs);
                }
            }
            this.lastCallTelemetry = new OllamaCallTelemetry(
                    apiPath,
                    endpoint,
                    effectiveModel,
                    thinkingMode,
                    thinkFlag,
                    numPredict,
                    extracted.thinkingChars(),
                    extracted.thinkingPreview(),
                    latencyMs,
                    measuredThinkingLatencyMs,
                    measuredContentLatencyMs,
                    loadDurationNs,
                    promptEvalCount,
                    promptEvalDurationNs,
                    evalCount,
                    evalDurationNs,
                    totalDurationNs > 0 ? totalDurationNs : latencyMs * 1_000_000L
            );
            if (enableTimeoutCooldown && latencyMs >= cooldownThresholdMs) {
                cooldownUntilEpochMs = System.currentTimeMillis() + 30_000L;
            }
            log.info("[LLM] Response received from provider=ollama, model={}, endpoint={}, think={}, thinkingChars={}, loadMs={}, promptEvalCount={}, promptEvalMs={}, evalCount={}, evalMs={}, totalDurationMs={}, latencyMs={}, status={}",
                    effectiveModel, endpoint, thinkFlag, extracted.thinkingChars(),
                    loadDurationNs / 1_000_000L, promptEvalCount, promptEvalDurationNs / 1_000_000L,
                    evalCount, evalDurationNs / 1_000_000L, totalDurationNs / 1_000_000L,
                    latencyMs, isFallback ? "fallback" : "ok");
            return extracted.content();
        } catch (Exception e) {
            String errMsg = e.getMessage() != null ? e.getMessage() : "";
            boolean isModelNotFound = errMsg.contains("404")
                    || errMsg.toLowerCase(Locale.ROOT).contains("not found");
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
                boolean retryChatEndpoint = (thinkingMode != null) || isNativeThinkingCapableModel(fallbackCandidate);
                Boolean retryThinkFlag = retryChatEndpoint
                        ? Boolean.valueOf(thinkingMode != null && thinkingMode.isDeepThink())
                        : null;
                String retryApiPath = retryChatEndpoint ? "/api/chat" : "/api/generate";
                try {
                    Map<String, Object> retryBody = buildOllamaRequestBody(
                            fallbackCandidate,
                            prompt,
                            retryChatEndpoint,
                            retryThinkFlag,
                            "2h",
                            temp,
                            topP,
                            repeatPenalty,
                            effectiveNumCtx,
                            numPredict
                    );
                    String retryJson = http.post()
                            .uri(retryApiPath)
                            .body(retryBody)
                            .retrieve()
                            .body(String.class);
                    Map<String, Object> retryResp = (retryJson != null && !retryJson.isBlank())
                            ? MAPPER.readValue(retryJson, Map.class)
                            : null;
                    ExtractedOllamaMessage retryExtracted = extractResponseMessage(retryResp, prompt);
                    long totalMs = (System.nanoTime() - startNs) / 1_000_000L;
                    this.lastCallTelemetry = new OllamaCallTelemetry(
                            retryApiPath,
                            base + retryApiPath,
                            fallbackCandidate,
                            thinkingMode,
                            retryThinkFlag,
                            numPredict,
                            retryExtracted.thinkingChars(),
                            retryExtracted.thinkingPreview(),
                            totalMs,
                            0L,
                            totalMs
                    );
                    log.info("[LLM] Auto-recovery succeeded with model={} in {} ms", fallbackCandidate, totalMs);
                    return retryExtracted.content();
                } catch (Exception retryEx) {
                    log.warn("[LLM] Auto-recovery retry with {} failed: {}", fallbackCandidate, retryEx.getMessage());
                }
            }
            long latencyMs = (System.nanoTime() - startNs) / 1_000_000L;
            this.lastCallTelemetry = new OllamaCallTelemetry(
                    apiPath,
                    endpoint,
                    effectiveModel,
                    thinkingMode,
                    thinkFlag,
                    numPredict,
                    0,
                    "",
                    latencyMs,
                    0L,
                    latencyMs
            );
            if (enableTimeoutCooldown && latencyMs >= cooldownThresholdMs) {
                cooldownUntilEpochMs = System.currentTimeMillis() + 30_000L;
            }
            log.warn("[LLM] Response received from provider=ollama, model={}, endpoint={}, latencyMs={}, status=fallback (error={})",
                    effectiveModel, endpoint, latencyMs, e.getMessage());
            return buildFallbackAnswer(prompt, e.getMessage());
        } finally {
            concurrencyGate.release();
        }
    }

    private static boolean isNativeThinkingCapableModel(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return false;
        }
        String lower = modelName.toLowerCase(Locale.ROOT);
        return lower.contains("qwen3") || lower.contains("deepseek-r1") || lower.contains("qwq");
    }

    private static long extractLongMetric(Map<String, Object> resp, String key) {
        if (resp == null || key == null) {
            return 0L;
        }
        Object val = resp.get(key);
        if (val instanceof Number n) {
            return n.longValue();
        }
        if (val != null) {
            try {
                return Long.parseLong(val.toString().strip());
            } catch (NumberFormatException ignored) {
            }
        }
        return 0L;
    }

    private static Map<String, Object> buildOllamaRequestBody(
            String modelName,
            String prompt,
            boolean useChatEndpoint,
            Boolean thinkFlag,
            Object keepAlive,
            double temp,
            double topP,
            double repeatPenalty,
            int numCtx,
            int numPredict
    ) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("temperature", temp);
        options.put("top_p", topP);
        options.put("repeat_penalty", repeatPenalty);
        options.put("num_ctx", numCtx);
        options.put("num_predict", numPredict);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        if (useChatEndpoint) {
            String effectivePrompt = prompt != null ? prompt : "";
            if (Boolean.FALSE.equals(thinkFlag)
                    && isNativeThinkingCapableModel(modelName)
                    && !effectivePrompt.contains("/no_think")) {
                effectivePrompt = effectivePrompt + "\n/no_think";
            }
            int splitIdx = effectivePrompt.indexOf("<!-- SYSTEM_USER_SPLIT -->");
            if (splitIdx >= 0) {
                String systemPart = effectivePrompt.substring(0, splitIdx).strip();
                String userPart = effectivePrompt.substring(splitIdx + "<!-- SYSTEM_USER_SPLIT -->".length()).strip();
                body.put("messages", List.of(
                        Map.of("role", "system", "content", systemPart),
                        Map.of("role", "user", "content", userPart)
                ));
            } else {
                body.put("messages", List.of(Map.of(
                        "role", "user",
                        "content", effectivePrompt
                )));
            }
            body.put("think", Boolean.TRUE.equals(thinkFlag));
        } else {
            body.put("prompt", prompt != null ? prompt.replace("<!-- SYSTEM_USER_SPLIT -->", "\n") : "");
        }
        body.put("stream", false);
        body.put("keep_alive", keepAlive);
        body.put("options", options);
        return body;
    }

    @Override
    public String name() {
        return "ollama:" + props.getModel();
    }

    private record ExtractedOllamaMessage(
            String content,
            int thinkingChars,
            String thinkingPreview
    ) {
    }

    @SuppressWarnings("unchecked")
    private ExtractedOllamaMessage extractResponseMessage(Map<String, Object> resp, String prompt) {
        if (resp == null) {
            return new ExtractedOllamaMessage(buildFallbackAnswer(prompt, "Phản hồi rỗng từ Ollama"), 0, "");
        }
        String rawContent = null;
        String rawThinking = "";

        Object messageObj = resp.get("message");
        if (messageObj instanceof Map<?, ?> messageMap) {
            Object thinkingObj = messageMap.get("thinking");
            if (thinkingObj != null) {
                rawThinking = thinkingObj.toString().strip();
            }
            Object contentObj = messageMap.get("content");
            if (contentObj != null) {
                rawContent = contentObj.toString();
            }
        }

        if (rawContent == null) {
            Object responseObj = resp.get("response");
            if (responseObj != null) {
                rawContent = responseObj.toString();
            }
        }

        if (rawContent == null) {
            return new ExtractedOllamaMessage(
                    buildFallbackAnswer(prompt, "Thiếu trường 'message.content' hoặc 'response' từ Ollama"),
                    rawThinking.length(),
                    abbreviateThinking(rawThinking)
            );
        }

        // Nếu trong rawContent có chứa thẻ <think>...</think>, tách phần đó vào telemetry và xóa khỏi câu trả lời
        java.util.regex.Matcher inlineThinkMatcher = java.util.regex.Pattern
                .compile("(?s)<think>(.*?)</think>")
                .matcher(rawContent);
        StringBuilder inlineThinking = new StringBuilder(rawThinking);
        while (inlineThinkMatcher.find()) {
            String block = inlineThinkMatcher.group(1) != null ? inlineThinkMatcher.group(1).strip() : "";
            if (!block.isEmpty()) {
                if (inlineThinking.length() > 0) {
                    inlineThinking.append("\n");
                }
                inlineThinking.append(block);
            }
        }
        String fullThinking = inlineThinking.toString().strip();
        if (!fullThinking.isEmpty()) {
            log.debug("[LLM][Ollama Internal Thinking] chars={}, preview={}",
                    fullThinking.length(), abbreviateThinking(fullThinking));
        }

        // Tuyệt đối không nối message.thinking vào câu trả lời; strip sạch mọi thẻ <think>...</think>
        String cleanedContent = rawContent
                .replaceAll("(?s)<think>.*?</think>", "")
                .replaceAll("(?s)<think>.*$", "")
                .strip();

        if (cleanedContent.isEmpty()) {
            return new ExtractedOllamaMessage(
                    buildFallbackAnswer(prompt, "Nội dung rỗng"),
                    fullThinking.length(),
                    abbreviateThinking(fullThinking)
            );
        }
        return new ExtractedOllamaMessage(
                cleanedContent,
                fullThinking.length(),
                abbreviateThinking(fullThinking)
        );
    }

    private static boolean isTruncatedAfterThinking(String content, Map<String, Object> resp, String prompt) {
        if (content == null || content.isBlank()) {
            return true;
        }
        if (resp != null && "length".equalsIgnoreCase(String.valueOf(resp.get("done_reason")))) {
            String stripped = content.strip();
            if (!stripped.endsWith("}")) {
                return true;
            }
        }
        boolean expectsStructuredJson = (prompt != null && prompt.contains("reply_bubbles"))
                || content.contains("\"reply_bubbles\"")
                || content.strip().startsWith("{");
        if (expectsStructuredJson) {
            String cleaned = content.strip()
                    .replaceFirst("(?s)^```(?:json)?\\s*", "")
                    .replaceFirst("(?s)\\s*```$", "")
                    .strip();
            int firstBrace = cleaned.indexOf('{');
            int lastBrace = cleaned.lastIndexOf('}');
            if (firstBrace < 0 || lastBrace <= firstBrace) {
                return true;
            }
            try {
                MAPPER.readValue(cleaned.substring(firstBrace, lastBrace + 1), Map.class);
            } catch (Exception e) {
                return true;
            }
        }
        return false;
    }

    private static String abbreviateThinking(String thinking) {
        if (thinking == null || thinking.isBlank()) {
            return "";
        }
        String normalized = thinking.replaceAll("\\s+", " ").strip();
        return normalized.length() <= 180 ? normalized : normalized.substring(0, 180) + "...";
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
