package com.example.docmind.service.bot;

import com.example.docmind.dto.bot.CustomerInsightDto;
import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.repository.RetrievedChunk;
import com.example.docmind.service.bot.PrimaryActionRouter.PrimaryAction;
import com.example.docmind.service.bot.PrimaryActionRouter.RoutingDecision;
import com.example.docmind.service.sales.PriceLadderTracker;
import com.example.docmind.service.retrieval.ReciprocalRankFusion;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Chính sách điều phối suy luận thích ứng (Adaptive Thinking Router Policy) và
 * điều phối nhịp trễ động sau báo giá (Dynamic Post-Price Delay Coordinator).
 * <p>
 * Thiết kế hoàn toàn độc lập với domain (Domain-Agnostic) và độc lập với LLM Provider
 * (không chứa bất kỳ trường đặc thù nào của Ollama hay Cloud API).
 */
public final class ThinkingModePolicy {

    public static final long DEFAULT_TARGET_PRICE_GAP_MS = 10_000L;

    private static final Pattern EXPLICIT_REFUSAL_OR_THINK_LATER_PATTERN = Pattern.compile(
            "(?isU).*(?:không\\s+cảm\\s+ơn|thôi\\s+không\\s+làm|không\\s+làm\\s+đâu|thôi\\s+bỏ\\s+đi|"
            + "thôi\\s+để\\s+sau|để\\s+(?:chị|anh|mình|em|tôi)\\s+suy\\s+nghĩ|suy\\s+nghĩ\\s+(?:đã|thêm)|"
            + "cân\\s+nhắc\\s+(?:đã|thêm)|để\\s+xem\\s+đã|chưa\\s+quyết\\s+định|chưa\\s+cần\\s+đâu|khi\\s+khác\\s+nhé).*"
    );

    // v3.3.4: Câu hỏi NỘI DUNG cụ thể trong tin nhắn (dù khách đang bực) -> vẫn phải REPAIR_FIRST để trả lời trực diện.
    // "cái quái gì đấy" KHÔNG khớp vì "quái" không phải danh từ/động từ nội dung.
    private static final Pattern CONCRETE_CONTENT_QUESTION_PATTERN = Pattern.compile(
            "(?isU).*(?:\\b(?:gồm|là|có|món|bước|loại|quyền\\s+lợi)\\s+(?:những\\s+)?(?:gì|j|nào)\\b"
            + "|\\bbao\\s+nhiêu\\b|\\bmấy\\s+(?:phút|giờ|bước|món|tiếng)\\b|\\bở\\s+đâu\\b|\\bkhi\\s+nào\\b|\\bthế\\s+nào\\b).*"
    );

    private static final Pattern MISMATCH_OR_REPAIR_COMPLAINT_PATTERN = Pattern.compile(
            "(?isU).*(?:sao\\s+hỏi\\s+[^?]{1,40}\\s+mà\\s+(?:cứ|lại)|hỏi\\s+một\\s+đằng\\s+trả\\s+lời\\s+một\\s+nẻo|"
            + "trả\\s+lời\\s+lạc\\s+đề|không\\s+trả\\s+lời\\s+câu\\s+hỏi|chưa\\s+trả\\s+lời|"
            + "cứ\\s+tư\\s+vấn\\s+[^?]{1,30}\\s+thế|tư\\s+vấn\\s+sai\\s+rồi|nhầm\\s+rồi|vòng\\s+vo|dở\\s+à).*|"
            + "^\\s*(?:k|ko|không)\\s*[,\\s]+(?:tôi|mình|chị|anh|em)\\s+(?:thấy|hỏi|đang\\s+hỏi)\\b.*"
    );

    private static final Pattern EXTERNAL_COMPARISON_OBJECTION_PATTERN = Pattern.compile(
            "(?isU).*(?:chỗ\\s+khác|bên\\s+khác|tiệm\\s+khác|spa\\s+khác|quán\\s+khác|gần\\s+nhà|"
            + "bên\\s+kia|chỗ\\s+kia|so\\s+với\\s+bên|sao\\s+bên\\s+(?:em|mình|shop)\\s+(?:cao|đắt)\\s+hơn).*"
    );

    private static final Pattern VALUE_DOUBT_OBJECTION_PATTERN = Pattern.compile(
            "(?isU).*(?:có\\s+(?:thật\\s+sự\\s+)?hiệu\\s+quả|có\\s+đỡ\\s+(?:thật\\s+)?không|sợ\\s+không\\s+đỡ|"
            + "chỉ\\s+đỡ\\s+lúc\\s+đó|chỉ\\s+quảng\\s+cáo|làm\\s+1\\s+buổi\\s+có\\s+đỡ).*"
    );

    private static final Pattern LOW_TRUST_OBJECTION_PATTERN = Pattern.compile(
            "(?isU).*(?:sợ\\s+phát\\s+sinh|phụ\\s+phí|chèo\\s+kéo|ép\\s+mua|cam\\s+kết\\s+không|tin\\s+được\\s+không).*"
    );

    private static final Pattern UNCLEAR_PACKAGE_OBJECTION_PATTERN = Pattern.compile(
            "(?isU).*(?:sao\\s+(?:lại\\s+)?(?:cao|đắt)|gồm\\s+những\\s+gì\\s+mà|khác\\s+gì\\s+nhau|chênh\\s+nhau\\s+gì).*"
    );

    private static final Pattern BUDGET_CONSTRAINT_OBJECTION_PATTERN = Pattern.compile(
            "(?isU).*(?:vẫn\\s+(?:thấy\\s+|hơi\\s+)?(?:đắt|cao)|đắt\\s+quá|vượt\\s+ngân\\s+sách|chưa\\s+đủ\\s+ngân\\s+sách|"
            + "mềm\\s+hơn|rẻ\\s+hơn|tiết\\s+kiệm\\s+hơn|nhẹ\\s+tiền\\s+hơn|sinh\\s+viên|không\\s+đủ\\s+tiền).*"
    );

    private static final Pattern LOCATION_OR_HOURS_FAST_PATTERN = Pattern.compile(
            "(?isU).*(?:địa\\s+chỉ|ở\\s+đâu|chi\\s+nhánh|cơ\\s+sở|giờ\\s+mở\\s+cửa|mấy\\s+giờ\\s+mở|"
            + "mấy\\s+giờ\\s+đóng|làm\\s+việc\\s+đến\\s+mấy\\s+giờ).*"
    );

    /**
     * Chế độ cấu hình phục vụ đánh giá thực nghiệm A/B/C (Section VII).
     */
    public enum BenchmarkConfigMode {
        /** Config C (Mặc định): Bật/tắt suy luận thông minh theo ThinkingModePolicy. */
        ADAPTIVE_C,
        /** Config A: Tắt suy luận ở mọi lượt (ép FAST_NO_THINK). */
        FORCE_NO_THINK_A,
        /** Config B: Bật suy luận ở mọi lượt. */
        FORCE_ALWAYS_THINK_B
    }

    /**
     * Phân loại nguyên nhân gốc rễ khi khách hàng chê đắt hoặc ngập ngừng (Section V.A).
     */
    public enum ObjectionRootCause {
        NONE,
        FIRST_OBJECTION_VALUE_BUILD,
        GENERAL_PRICE_HESITATION,
        UNSEEN_VALUE,
        VALUE_DOUBT,
        LOW_TRUST,
        TRUST_HESITATION,
        UNCLEAR_PACKAGE,
        BUDGET_CONSTRAINT,
        BUDGET_MISMATCH,
        EXTERNAL_COMPARISON,
        EXPLICIT_REFUSAL
    }

    /**
     * Quyết định suy luận đầu ra của {@link ThinkingModePolicy}.
     */
    public record ThinkingDecision(
            ThinkingMode thinkingMode,
            int maxOutputTokens,
            String reasonCode,
            ObjectionRootCause objectionRootCause,
            int objectionTurnIndex,
            List<String> previouslyUsedArguments,
            List<String> unresolvedQuestionIds
    ) {
        public ThinkingDecision(
                ThinkingMode thinkingMode,
                String reasonCode,
                ObjectionRootCause objectionRootCause,
                List<String> previouslyUsedArguments
        ) {
            this(
                    thinkingMode != null ? thinkingMode : ThinkingMode.FAST_NO_THINK,
                    thinkingMode != null ? thinkingMode.maxOutputTokens() : ThinkingMode.FAST_NO_THINK.maxOutputTokens(),
                    reasonCode != null ? reasonCode : "DEFAULT",
                    objectionRootCause != null ? objectionRootCause : ObjectionRootCause.NONE,
                    1,
                    previouslyUsedArguments != null ? List.copyOf(previouslyUsedArguments) : List.of(),
                    List.of()
            );
        }

        public ThinkingDecision(
                ThinkingMode thinkingMode,
                int maxOutputTokens,
                ObjectionRootCause objectionRootCause,
                List<String> previouslyUsedArguments,
                List<String> unresolvedQuestionIds,
                String rationale
        ) {
            this(
                    thinkingMode != null ? thinkingMode : ThinkingMode.FAST_NO_THINK,
                    maxOutputTokens > 0 ? maxOutputTokens : (thinkingMode != null ? thinkingMode.maxOutputTokens() : 220),
                    rationale != null ? rationale : "DEFAULT",
                    objectionRootCause != null ? objectionRootCause : ObjectionRootCause.NONE,
                    1,
                    previouslyUsedArguments != null ? List.copyOf(previouslyUsedArguments) : List.of(),
                    unresolvedQuestionIds != null ? List.copyOf(unresolvedQuestionIds) : List.of()
            );
        }

        public boolean thinkEnabled() {
            return thinkingMode != null && thinkingMode.thinkEnabled();
        }

        public String triggerReason() {
            return reasonCode;
        }

        public String rationale() {
            return reasonCode;
        }

        public List<String> unresolvedQuestions() {
            return unresolvedQuestionIds;
        }
    }

    /**
     * Số liệu đo đạc thời gian chờ động giữa Bubble 1 (báo giá) và Bubble 2 (neo cảm xúc) theo đồng hồ đơn điệu.
     */
    public record PostPriceDelayMetrics(
            int configuredDelayMs,
            long priceBubbleShownAtEpochMs,
            long secondBubbleReadyAtEpochMs,
            long elapsedSincePriceBubbleMs,
            int remainingDelayMs,
            long effectiveInterBubbleGapMs
    ) {
        public long targetPriceGapMs() {
            return configuredDelayMs;
        }
    }

    /**
     * Cấu trúc Telemetry đầy đủ cho mỗi lượt hội thoại (Section VI, VII.2 & v3.2.4 Section 4).
     */
    public record TurnTelemetry(
            PrimaryAction primaryAction,
            ThinkingMode thinkingMode,
            String detectedIntent,
            List<String> unresolvedQuestionIds,
            List<String> retrievedSourceIds,
            boolean responseValidated,
            String fallbackReason,
            long llmElapsedMs,
            long priceBubbleShownAt,
            long secondBubbleSentAt,
            long timeToFirstBubbleMs,
            long interBubbleGapMs,
            String reasonCode,
            boolean thinkEnabled,
            int maxOutputTokens,
            int thinkingChars,
            long thinkingLatencyMs,
            long contentLatencyMs,
            int configuredDelayMs,
            long elapsedSincePriceBubbleMs,
            int remainingDelayMs,
            boolean cancelledSecondBubble,
            long promptBuildMs,
            long retrievalMs,
            long validationMs,
            long repairElapsedMs,
            int retryCount,
            long totalTurnMs,
            boolean usedTemplateFallback,
            String fallbackTriggerRule,
            String generationSource,
            boolean repairApplied,
            int repairAttemptCount,
            String validationOutcome,
            List<String> validationErrors,
            String provider,
            String model,
            String endpoint,
            List<String> bubbleSources,
            long loadDurationMs,
            int promptEvalCount,
            long promptEvalDurationMs,
            int evalCount,
            long evalDurationMs,
            long totalDurationMs
    ) {
        public TurnTelemetry {
            unresolvedQuestionIds = unresolvedQuestionIds != null ? List.copyOf(unresolvedQuestionIds) : List.of();
            retrievedSourceIds = retrievedSourceIds != null ? List.copyOf(retrievedSourceIds) : List.of();
            validationErrors = validationErrors != null ? List.copyOf(validationErrors) : List.of();
            bubbleSources = bubbleSources != null ? List.copyOf(bubbleSources) : List.of();
            if (generationSource == null || generationSource.isBlank()) {
                generationSource = usedTemplateFallback ? "FALLBACK_TEMPLATE" : (repairApplied ? "LLM_REPAIRED" : "LLM_LIVE");
            }
            if (validationOutcome == null || validationOutcome.isBlank()) {
                validationOutcome = responseValidated ? "PASS" : "FAIL";
            }
            if (provider == null || provider.isBlank()) {
                provider = "ollama";
            }
        }

        public TurnTelemetry(
                PrimaryAction primaryAction,
                ThinkingMode thinkingMode,
                String detectedIntent,
                List<String> unresolvedQuestionIds,
                List<String> retrievedSourceIds,
                boolean responseValidated,
                String fallbackReason,
                long llmElapsedMs,
                long priceBubbleShownAt,
                long secondBubbleSentAt,
                long timeToFirstBubbleMs,
                long interBubbleGapMs,
                String reasonCode,
                boolean thinkEnabled,
                int maxOutputTokens,
                int thinkingChars,
                long thinkingLatencyMs,
                long contentLatencyMs,
                int configuredDelayMs,
                long elapsedSincePriceBubbleMs,
                int remainingDelayMs,
                boolean cancelledSecondBubble,
                long promptBuildMs,
                long retrievalMs,
                long validationMs,
                long repairElapsedMs,
                int retryCount,
                long totalTurnMs,
                boolean usedTemplateFallback,
                String fallbackTriggerRule,
                String generationSource,
                boolean repairApplied,
                int repairAttemptCount,
                String validationOutcome,
                List<String> validationErrors,
                String provider,
                String model,
                String endpoint,
                List<String> bubbleSources
        ) {
            this(
                    primaryAction,
                    thinkingMode,
                    detectedIntent,
                    unresolvedQuestionIds,
                    retrievedSourceIds,
                    responseValidated,
                    fallbackReason,
                    llmElapsedMs,
                    priceBubbleShownAt,
                    secondBubbleSentAt,
                    timeToFirstBubbleMs,
                    interBubbleGapMs,
                    reasonCode,
                    thinkEnabled,
                    maxOutputTokens,
                    thinkingChars,
                    thinkingLatencyMs,
                    contentLatencyMs,
                    configuredDelayMs,
                    elapsedSincePriceBubbleMs,
                    remainingDelayMs,
                    cancelledSecondBubble,
                    promptBuildMs,
                    retrievalMs,
                    validationMs,
                    repairElapsedMs,
                    retryCount,
                    totalTurnMs,
                    usedTemplateFallback,
                    fallbackTriggerRule,
                    generationSource,
                    repairApplied,
                    repairAttemptCount,
                    validationOutcome,
                    validationErrors,
                    provider,
                    model,
                    endpoint,
                    bubbleSources,
                    0L,
                    0,
                    0L,
                    0,
                    0L,
                    llmElapsedMs
            );
        }

        public TurnTelemetry(
                PrimaryAction primaryAction,
                ThinkingMode thinkingMode,
                String detectedIntent,
                List<String> unresolvedQuestionIds,
                List<String> retrievedSourceIds,
                boolean responseValidated,
                String fallbackReason,
                long llmElapsedMs,
                long priceBubbleShownAt,
                long secondBubbleSentAt,
                long timeToFirstBubbleMs,
                long interBubbleGapMs,
                String reasonCode,
                boolean thinkEnabled,
                int maxOutputTokens,
                int thinkingChars,
                long thinkingLatencyMs,
                long contentLatencyMs,
                int configuredDelayMs,
                long elapsedSincePriceBubbleMs,
                int remainingDelayMs,
                boolean cancelledSecondBubble,
                long promptBuildMs,
                long retrievalMs,
                long validationMs,
                long repairElapsedMs,
                int retryCount,
                long totalTurnMs
        ) {
            this(
                    primaryAction,
                    thinkingMode,
                    detectedIntent,
                    unresolvedQuestionIds,
                    retrievedSourceIds,
                    responseValidated,
                    fallbackReason,
                    llmElapsedMs,
                    priceBubbleShownAt,
                    secondBubbleSentAt,
                    timeToFirstBubbleMs,
                    interBubbleGapMs,
                    reasonCode,
                    thinkEnabled,
                    maxOutputTokens,
                    thinkingChars,
                    thinkingLatencyMs,
                    contentLatencyMs,
                    configuredDelayMs,
                    elapsedSincePriceBubbleMs,
                    remainingDelayMs,
                    cancelledSecondBubble,
                    promptBuildMs,
                    retrievalMs,
                    validationMs,
                    repairElapsedMs,
                    retryCount,
                    totalTurnMs,
                    fallbackReason != null && !fallbackReason.isBlank(),
                    fallbackReason,
                    (fallbackReason != null && !fallbackReason.isBlank())
                            ? "FALLBACK_TEMPLATE"
                            : (retryCount > 0 ? "LLM_REPAIRED" : "LLM_LIVE"),
                    retryCount > 0,
                    retryCount,
                    responseValidated ? (retryCount > 0 ? "REPAIRED_PASS" : "PASS") : "FALLBACK",
                    List.of(),
                    "ollama",
                    "qwen3:14b",
                    "/api/chat",
                    List.of()
            );
        }

        public TurnTelemetry(
                PrimaryAction primaryAction,
                ThinkingMode thinkingMode,
                String detectedIntent,
                List<String> unresolvedQuestionIds,
                List<String> retrievedSourceIds,
                boolean responseValidated,
                String fallbackReason,
                long llmElapsedMs,
                long priceBubbleShownAt,
                long secondBubbleSentAt,
                long timeToFirstBubbleMs,
                long interBubbleGapMs,
                String reasonCode,
                boolean thinkEnabled,
                int maxOutputTokens,
                int thinkingChars,
                long thinkingLatencyMs,
                long contentLatencyMs,
                int configuredDelayMs,
                long elapsedSincePriceBubbleMs,
                int remainingDelayMs,
                boolean cancelledSecondBubble,
                long promptBuildMs,
                long retrievalMs,
                long validationMs,
                int retryCount,
                long totalTurnMs
        ) {
            this(
                    primaryAction,
                    thinkingMode,
                    detectedIntent,
                    unresolvedQuestionIds,
                    retrievedSourceIds,
                    responseValidated,
                    fallbackReason,
                    llmElapsedMs,
                    priceBubbleShownAt,
                    secondBubbleSentAt,
                    timeToFirstBubbleMs,
                    interBubbleGapMs,
                    reasonCode,
                    thinkEnabled,
                    maxOutputTokens,
                    thinkingChars,
                    thinkingLatencyMs,
                    contentLatencyMs,
                    configuredDelayMs,
                    elapsedSincePriceBubbleMs,
                    remainingDelayMs,
                    cancelledSecondBubble,
                    promptBuildMs,
                    retrievalMs,
                    validationMs,
                    0L,
                    retryCount,
                    totalTurnMs
            );
        }

        public TurnTelemetry(
                PrimaryAction primaryAction,
                ThinkingMode thinkingMode,
                String detectedIntent,
                List<String> unresolvedQuestionIds,
                List<String> retrievedSourceIds,
                boolean responseValidated,
                String fallbackReason,
                long llmElapsedMs,
                long priceBubbleShownAt,
                long secondBubbleSentAt,
                long timeToFirstBubbleMs,
                long interBubbleGapMs,
                String reasonCode,
                boolean thinkEnabled,
                int maxOutputTokens,
                int thinkingChars,
                long thinkingLatencyMs,
                long contentLatencyMs,
                int configuredDelayMs,
                long elapsedSincePriceBubbleMs,
                int remainingDelayMs,
                boolean cancelledSecondBubble
        ) {
            this(
                    primaryAction,
                    thinkingMode,
                    detectedIntent,
                    unresolvedQuestionIds,
                    retrievedSourceIds,
                    responseValidated,
                    fallbackReason,
                    llmElapsedMs,
                    priceBubbleShownAt,
                    secondBubbleSentAt,
                    timeToFirstBubbleMs,
                    interBubbleGapMs,
                    reasonCode,
                    thinkEnabled,
                    maxOutputTokens,
                    thinkingChars,
                    thinkingLatencyMs,
                    contentLatencyMs,
                    configuredDelayMs,
                    elapsedSincePriceBubbleMs,
                    remainingDelayMs,
                    cancelledSecondBubble,
                    0L,
                    0L,
                    0L,
                    0L,
                    0,
                    Math.max(timeToFirstBubbleMs, llmElapsedMs) + Math.max(0L, interBubbleGapMs)
            );
        }

        public TurnTelemetry(
                ThinkingMode thinkingMode,
                String reasonCode,
                boolean thinkEnabled,
                int maxOutputTokens,
                int thinkingChars,
                long thinkingLatencyMs,
                long contentLatencyMs,
                int configuredDelayMs,
                long elapsedSincePriceBubbleMs,
                int remainingDelayMs,
                boolean cancelledSecondBubble
        ) {
            this(
                    PrimaryAction.GENERIC_SHORT_DISCOVERY,
                    thinkingMode,
                    "",
                    List.of(),
                    List.of(),
                    true,
                    null,
                    thinkingLatencyMs + contentLatencyMs,
                    0L,
                    0L,
                    thinkingLatencyMs + contentLatencyMs,
                    elapsedSincePriceBubbleMs + remainingDelayMs,
                    reasonCode,
                    thinkEnabled,
                    maxOutputTokens,
                    thinkingChars,
                    thinkingLatencyMs,
                    contentLatencyMs,
                    configuredDelayMs,
                    elapsedSincePriceBubbleMs,
                    remainingDelayMs,
                    cancelledSecondBubble
            );
        }

        public long firstBubbleSendMs() {
            return timeToFirstBubbleMs;
        }
    }

    /**
     * Telemetry chuẩn hóa cho kiến trúc {@code LLM_COGNITIVE_V2} / {@code ACI} (Mục 7.1):
     * báo cáo chính xác {@code promptChars}, {@code promptTokens}, {@code completionTokens},
     * {@code llmLatencyMs}, {@code actualWaitMs}, {@code totalTurnLatencyMs}.
     */
    public record AciTurnTelemetry(
            AdaptiveConversationIntelligence.CognitiveMode cognitiveMode,
            ThinkingMode thinkingMode,
            int promptChars,
            int legacyEquivalentPromptChars,
            int promptTokens,
            int completionTokens,
            int reasoningTokens,
            long llmLatencyMs,
            int quoteBubbleIndex,
            long actualWaitMs,
            long totalTurnLatencyMs,
            boolean repairApplied,
            List<String> refereeViolations,
            String priorityPrimary,
            String strategyChosen,
            String strategyAdaptation,
            String handoffStatus,
            String bookingStatus,
            int reflectionTokens,
            long retrievalLatencyMs,
            int retryCount,
            String retryTriggerReason,
            int llmCallCount,
            long turnDeadlineMs,
            List<String> rawLlmBubbles,
            List<String> finalBubbles,
            double sanitizerMutationRate
    ) {
        public AciTurnTelemetry {
            cognitiveMode = cognitiveMode != null
                    ? cognitiveMode
                    : AdaptiveConversationIntelligence.CognitiveMode.LLM_COGNITIVE_V2;
            thinkingMode = thinkingMode != null ? thinkingMode : ThinkingMode.FAST_NO_THINK;
            refereeViolations = refereeViolations != null ? List.copyOf(refereeViolations) : List.of();
            retryTriggerReason = (retryTriggerReason == null || retryTriggerReason.isBlank())
                    ? (retryCount > 0 ? "UNSPECIFIED_RETRY" : "NONE")
                    : retryTriggerReason.strip();
            llmCallCount = Math.max(0, llmCallCount);
            turnDeadlineMs = turnDeadlineMs > 0L ? turnDeadlineMs : 65_000L;
            rawLlmBubbles = rawLlmBubbles != null ? List.copyOf(rawLlmBubbles) : List.of();
            finalBubbles = finalBubbles != null ? List.copyOf(finalBubbles) : List.of();
            sanitizerMutationRate = Double.isNaN(sanitizerMutationRate)
                    ? 0.0
                    : Math.max(0.0, Math.min(1.0, sanitizerMutationRate));
        }

        public AciTurnTelemetry(
                AdaptiveConversationIntelligence.CognitiveMode cognitiveMode,
                ThinkingMode thinkingMode,
                int promptChars,
                int legacyEquivalentPromptChars,
                int promptTokens,
                int completionTokens,
                int reasoningTokens,
                long llmLatencyMs,
                int quoteBubbleIndex,
                long actualWaitMs,
                long totalTurnLatencyMs,
                boolean repairApplied,
                List<String> refereeViolations,
                String priorityPrimary,
                String strategyChosen,
                String strategyAdaptation,
                String handoffStatus,
                String bookingStatus,
                int reflectionTokens,
                long retrievalLatencyMs,
                int retryCount,
                String retryTriggerReason
        ) {
            this(
                    cognitiveMode,
                    thinkingMode,
                    promptChars,
                    legacyEquivalentPromptChars,
                    promptTokens,
                    completionTokens,
                    reasoningTokens,
                    llmLatencyMs,
                    quoteBubbleIndex,
                    actualWaitMs,
                    totalTurnLatencyMs,
                    repairApplied,
                    refereeViolations,
                    priorityPrimary,
                    strategyChosen,
                    strategyAdaptation,
                    handoffStatus,
                    bookingStatus,
                    reflectionTokens,
                    retrievalLatencyMs,
                    retryCount,
                    retryTriggerReason,
                    1 + Math.max(0, retryCount),
                    65_000L,
                    List.of(),
                    List.of(),
                    0.0
            );
        }

        public AciTurnTelemetry(
                AdaptiveConversationIntelligence.CognitiveMode cognitiveMode,
                ThinkingMode thinkingMode,
                int promptChars,
                int legacyEquivalentPromptChars,
                int promptTokens,
                int completionTokens,
                int reasoningTokens,
                long llmLatencyMs,
                int quoteBubbleIndex,
                long actualWaitMs,
                long totalTurnLatencyMs,
                boolean repairApplied,
                List<String> refereeViolations,
                String priorityPrimary,
                String strategyChosen,
                String strategyAdaptation,
                String handoffStatus,
                String bookingStatus,
                int reflectionTokens,
                long retrievalLatencyMs,
                int retryCount
        ) {
            this(
                    cognitiveMode,
                    thinkingMode,
                    promptChars,
                    legacyEquivalentPromptChars,
                    promptTokens,
                    completionTokens,
                    reasoningTokens,
                    llmLatencyMs,
                    quoteBubbleIndex,
                    actualWaitMs,
                    totalTurnLatencyMs,
                    repairApplied,
                    refereeViolations,
                    priorityPrimary,
                    strategyChosen,
                    strategyAdaptation,
                    handoffStatus,
                    bookingStatus,
                    reflectionTokens,
                    retrievalLatencyMs,
                    retryCount,
                    retryCount > 0 ? "UNSPECIFIED_RETRY" : "NONE"
            );
        }

        public AciTurnTelemetry(
                AdaptiveConversationIntelligence.CognitiveMode cognitiveMode,
                ThinkingMode thinkingMode,
                int promptChars,
                int legacyEquivalentPromptChars,
                int promptTokens,
                int completionTokens,
                int reasoningTokens,
                long llmLatencyMs,
                int quoteBubbleIndex,
                long actualWaitMs,
                long totalTurnLatencyMs,
                boolean repairApplied,
                List<String> refereeViolations,
                String priorityPrimary,
                String strategyChosen,
                String strategyAdaptation,
                String handoffStatus,
                String bookingStatus
        ) {
            this(
                    cognitiveMode,
                    thinkingMode,
                    promptChars,
                    legacyEquivalentPromptChars,
                    promptTokens,
                    completionTokens,
                    reasoningTokens,
                    llmLatencyMs,
                    quoteBubbleIndex,
                    actualWaitMs,
                    totalTurnLatencyMs,
                    repairApplied,
                    refereeViolations,
                    priorityPrimary,
                    strategyChosen,
                    strategyAdaptation,
                    handoffStatus,
                    bookingStatus,
                    reasoningTokens,
                    0L,
                    0,
                    "NONE"
            );
        }

        public int inputTokens() {
            return promptTokens;
        }

        public int outputTokens() {
            return completionTokens;
        }

        public long modelLatencyMs() {
            return llmLatencyMs;
        }

        public long totalTurnMs() {
            return totalTurnLatencyMs;
        }
    }

    private record SessionBubbleState(
            long turnSequence,
            long priceBubbleShownAtNs,
            long priceBubbleShownAtEpochMs,
            boolean pendingSecondBubble
    ) {
    }

    private static final Map<String, AtomicLong> SESSION_TURN_SEQUENCES = new ConcurrentHashMap<>();
    private static final Map<String, SessionBubbleState> SESSION_PENDING_BUBBLE_STATES = new ConcurrentHashMap<>();
    private static volatile BenchmarkConfigMode activeBenchmarkMode = BenchmarkConfigMode.ADAPTIVE_C;
    private static final ThreadLocal<TurnTelemetry> LAST_TURN_TELEMETRY = new ThreadLocal<>();
    private static final ThreadLocal<AciTurnTelemetry> LAST_ACI_TURN_TELEMETRY = new ThreadLocal<>();

    private ThinkingModePolicy() {
    }

    public static void setBenchmarkConfigMode(BenchmarkConfigMode mode) {
        activeBenchmarkMode = mode != null ? mode : BenchmarkConfigMode.ADAPTIVE_C;
    }

    public static BenchmarkConfigMode getBenchmarkConfigMode() {
        String sysProp = System.getProperty("docmind.thinking.benchmark.mode");
        if (sysProp == null || sysProp.isBlank()) {
            sysProp = System.getenv("DOCMIND_THINKING_BENCHMARK_MODE");
        }
        if (sysProp != null && !sysProp.isBlank()) {
            String norm = sysProp.strip().toUpperCase(Locale.ROOT);
            if ("A".equals(norm) || "FORCE_NO_THINK_A".equals(norm) || "NO_THINK".equals(norm)) {
                return BenchmarkConfigMode.FORCE_NO_THINK_A;
            }
            if ("B".equals(norm) || "FORCE_ALWAYS_THINK_B".equals(norm) || "ALWAYS_THINK".equals(norm)) {
                return BenchmarkConfigMode.FORCE_ALWAYS_THINK_B;
            }
        }
        return activeBenchmarkMode;
    }

    public static void recordTurnTelemetry(TurnTelemetry telemetry) {
        if (telemetry != null) {
            LAST_TURN_TELEMETRY.set(telemetry);
        }
    }

    public static TurnTelemetry getLastTurnTelemetry() {
        return LAST_TURN_TELEMETRY.get();
    }

    public static void recordAciTurnTelemetry(AciTurnTelemetry telemetry) {
        if (telemetry != null) {
            LAST_ACI_TURN_TELEMETRY.set(telemetry);
        }
    }

    public static AciTurnTelemetry getLastAciTurnTelemetry() {
        return LAST_ACI_TURN_TELEMETRY.get();
    }

    public static void clearAciTurnTelemetry() {
        LAST_ACI_TURN_TELEMETRY.remove();
    }

    /**
     * Đăng ký lượt tin nhắn mới của khách hàng trong phiên.
     * Nếu phiên đang có một Bubble 2 (neo cảm xúc sau báo giá) chờ gửi ở lượt trước,
     * lượt mới này sẽ tự động làm mất hiệu lực (supersede/cancel) Bubble 2 cũ.
     */
    public static long registerIncomingCustomerTurn(UUID pageId, String senderId) {
        String key = buildSessionKey(pageId, senderId);
        AtomicLong counter = SESSION_TURN_SEQUENCES.computeIfAbsent(key, k -> new AtomicLong(0L));
        long seq = counter.incrementAndGet();
        SessionBubbleState prev = SESSION_PENDING_BUBBLE_STATES.get(key);
        if (prev != null && prev.pendingSecondBubble()) {
            SESSION_PENDING_BUBBLE_STATES.put(key, new SessionBubbleState(
                    seq, prev.priceBubbleShownAtNs(), prev.priceBubbleShownAtEpochMs(), false
            ));
        }
        return seq;
    }

    public static void markPriceBubble1ShownAndBubble2Pending(
            UUID pageId,
            String senderId,
            long turnSequence,
            long priceBubbleShownAtNs
    ) {
        markPriceBubble1ShownAndBubble2Pending(
                pageId,
                senderId,
                turnSequence,
                priceBubbleShownAtNs,
                System.currentTimeMillis()
        );
    }

    public static void markPriceBubble1ShownAndBubble2Pending(
            UUID pageId,
            String senderId,
            long turnSequence,
            long priceBubbleShownAtNs,
            long priceBubbleShownAtEpochMs
    ) {
        String key = buildSessionKey(pageId, senderId);
        AtomicLong counter = SESSION_TURN_SEQUENCES.computeIfAbsent(key, k -> new AtomicLong(turnSequence));
        counter.compareAndSet(0L, turnSequence);
        if (counter.get() == turnSequence) {
            SESSION_PENDING_BUBBLE_STATES.put(key, new SessionBubbleState(
                    turnSequence, priceBubbleShownAtNs, priceBubbleShownAtEpochMs, true
            ));
        }
    }

    public static Long getPendingPriceBubbleShownAtNs(UUID pageId, String senderId, long expectedTurnSequence) {
        String key = buildSessionKey(pageId, senderId);
        SessionBubbleState state = SESSION_PENDING_BUBBLE_STATES.get(key);
        if (state != null && state.pendingSecondBubble()
                && (expectedTurnSequence <= 0L || state.turnSequence() == expectedTurnSequence)
                && state.priceBubbleShownAtNs() > 0L) {
            return state.priceBubbleShownAtNs();
        }
        return null;
    }

    public static boolean isPendingSecondBubbleSuperseded(UUID pageId, String senderId, long turnSequence) {
        String key = buildSessionKey(pageId, senderId);
        AtomicLong counter = SESSION_TURN_SEQUENCES.get(key);
        SessionBubbleState state = SESSION_PENDING_BUBBLE_STATES.get(key);
        if (state != null && !state.pendingSecondBubble()) {
            return true;
        }
        return counter != null && turnSequence > 0L && counter.get() > turnSequence;
    }

    public static void clearSessionState(UUID pageId, String senderId) {
        String key = buildSessionKey(pageId, senderId);
        SESSION_TURN_SEQUENCES.remove(key);
        SESSION_PENDING_BUBBLE_STATES.remove(key);
    }

    private static String buildSessionKey(UUID pageId, String senderId) {
        String p = pageId != null ? pageId.toString() : "default-page";
        String s = (senderId != null && !senderId.isBlank()) ? senderId.strip() : "anon-sender";
        return p + "::" + s;
    }

    /**
     * Tính toán thời gian trễ còn lại (remainingDelayMs) cho Bubble 2 sau báo giá dựa trên đồng hồ đơn điệu.
     */
    public static PostPriceDelayMetrics computeDynamicPostPriceDelay(
            int configuredDelayMs,
            long priceBubbleShownAtNs,
            long secondBubbleReadyAtNs
    ) {
        long nowEpochMs = System.currentTimeMillis();
        return computeDynamicPostPriceDelay(
                priceBubbleShownAtNs,
                secondBubbleReadyAtNs,
                configuredDelayMs > 0 ? configuredDelayMs : DEFAULT_TARGET_PRICE_GAP_MS,
                nowEpochMs,
                nowEpochMs
        );
    }

    public static PostPriceDelayMetrics computeDynamicPostPriceDelay(
            long priceBubbleShownAtNs,
            long secondBubbleReadyAtNs,
            long targetPriceGapMs,
            long priceBubbleShownAtEpochMs,
            long secondBubbleReadyAtEpochMs
    ) {
        int target = (int) (targetPriceGapMs > 0 ? targetPriceGapMs : DEFAULT_TARGET_PRICE_GAP_MS);
        long elapsedMs = Math.max(0L, (secondBubbleReadyAtNs - priceBubbleShownAtNs) / 1_000_000L);
        int remainingDelayMs = (int) Math.max(0L, target - elapsedMs);
        long effectiveGapMs = elapsedMs + remainingDelayMs;
        return new PostPriceDelayMetrics(
                target,
                priceBubbleShownAtEpochMs,
                secondBubbleReadyAtEpochMs + remainingDelayMs,
                elapsedMs,
                remainingDelayMs,
                effectiveGapMs
        );
    }

    public static ThinkingDecision decide(
            String customerMessage,
            ConversationState convState,
            RoutingDecision actionDecision,
            CustomerInsightDto customerInsight
    ) {
        return decide(customerMessage, convState, actionDecision, customerInsight, getBenchmarkConfigMode());
    }

    public static ThinkingDecision decide(
            String customerMessage,
            ConversationState convState,
            RoutingDecision actionDecision,
            CustomerInsightDto customerInsight,
            BenchmarkConfigMode benchmarkMode
    ) {
        String rawMsg = customerMessage != null ? customerMessage.strip() : "";
        String normMsg = Normalizer.normalize(rawMsg, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        ConversationState safeState = (convState != null ? convState : ConversationState.initial())
                .ensureTurnEnriched(rawMsg, List.of());
        PrimaryAction effectiveAction = actionDecision != null && actionDecision.effectiveResponseAction() != null
                ? actionDecision.effectiveResponseAction()
                : PrimaryAction.GENERIC_SHORT_DISCOVERY;
        String detectedIntent = actionDecision != null && actionDecision.detectedIntent() != null
                ? actionDecision.detectedIntent()
                : "";

        List<String> unresolvedIds = extractUnresolvedQuestionIds(safeState);
        List<String> priorArgs = extractPreviouslyUsedArgumentsFromState(safeState);

        BenchmarkConfigMode effectiveBench = benchmarkMode != null ? benchmarkMode : BenchmarkConfigMode.ADAPTIVE_C;
        if (effectiveBench == BenchmarkConfigMode.FORCE_NO_THINK_A) {
            return new ThinkingDecision(
                    ThinkingMode.FAST_NO_THINK,
                    ThinkingMode.FAST_NO_THINK.maxOutputTokens(),
                    "BENCHMARK_FORCE_NO_THINK_A",
                    ObjectionRootCause.NONE,
                    Math.max(1, safeState.objectionCount()),
                    priorArgs,
                    unresolvedIds
            );
        }

        // 0A (v3.4.2). Khách phản ứng việc bot xin lỗi ("Hâm à mắc j xin loi", "Sao phải xin lỗi") -> EMOTIONAL_WARM_THINK
        // Phải ưu tiên trước Fast-path (vì ở Turn 6 objectionStage có thể đã là STAGE_3_GRACEFUL_EXIT) và trước REPAIR_FIRST.
        if ("ANTI_APOLOGY_PUSHBACK".equals(detectedIntent)
                || (SentimentIntentLayer.isAntiApologyPushback(rawMsg)
                    && !SentimentIntentLayer.hasPositiveServiceInterestSignal(rawMsg))) {
            return new ThinkingDecision(
                    ThinkingMode.EMOTIONAL_WARM_THINK,
                    ThinkingMode.EMOTIONAL_WARM_THINK.maxOutputTokens(),
                    "ANTI_APOLOGY_PUSHBACK_WARM_HUMOR",
                    ObjectionRootCause.NONE,
                    0,
                    priorArgs,
                    unresolvedIds
            );
        }

        // 0. Fast-path: Đặt lịch kèm SĐT, Opt-out, Polite Deferral Exit, Stage 3 Graceful Exit hoặc Quy tắc an toàn / Handoff trực tiếp -> FAST_NO_THINK
        if (effectiveAction == PrimaryAction.CONFIRM_BOOKING_DETAILS
                || effectiveAction == PrimaryAction.HARD_HANDOFF_SAFETY
                || effectiveAction == PrimaryAction.HARD_HANDOFF_COMPLAINT
                || effectiveAction == PrimaryAction.SAFE_REFUSAL_OR_HANDOFF_IF_REPEATED
                || effectiveAction == PrimaryAction.BOT_MUTED
                || "EXPLICIT_OPT_OUT".equals(detectedIntent)
                || "POLITE_DEFERRAL_EXIT".equals(detectedIntent)
                || "STAGE_3_GRACEFUL_EXIT".equals(detectedIntent)
                || "STAGE_3_GRACEFUL_EXIT".equals(safeState.objectionStage())
                || PrimaryActionRouter.isExplicitOptOutMessage(rawMsg)
                || hasPhoneBookingSignal(normMsg)) {
            return applyBenchmarkOverrideIfNeeded(new ThinkingDecision(
                    ThinkingMode.FAST_NO_THINK,
                    ThinkingMode.FAST_NO_THINK.maxOutputTokens(),
                    "FAST_PATH_BOOKING_OR_HANDOFF",
                    ObjectionRootCause.NONE,
                    0,
                    priorArgs,
                    unresolvedIds
            ), effectiveBench);
        }

        // 0B (v3.3.4). Từ chối dứt khoát ("tụt hứng thôi dẹp", "không cần nữa") -> dừng bán hàng ngay, TẮT thinking.
        // Trước đây nhánh này rơi vào OBJECTION_REFRAME_THINK, khiến model cố "thuyết phục lại" khách đã muốn dừng.
        if ("STRONG_REJECTION".equals(detectedIntent) || SentimentIntentLayer.isStrongRejection(rawMsg)) {
            return applyBenchmarkOverrideIfNeeded(new ThinkingDecision(
                    ThinkingMode.FAST_NO_THINK,
                    ThinkingMode.FAST_NO_THINK.maxOutputTokens(),
                    "STRONG_REJECTION_GRACEFUL_EXIT_FAST",
                    ObjectionRootCause.NONE,
                    0,
                    priorArgs,
                    unresolvedIds
            ), effectiveBench);
        }

        // 0C (v3.3.4 / v3.5.0). Khách bắt bẻ câu chữ của Bot hoặc bày tỏ tụt cảm xúc ("Tôi thấy tụt cảm xúc quá chán chả buồn nói") -> EMOTIONAL_WARM_THINK
        if ("WORDING_FEEDBACK_REPAIR".equals(detectedIntent)
                || "WORDING_FEEDBACK_PRESERVE_BOOKING_FUNNEL".equals(detectedIntent)
                || "CONVERSATION_STYLE_REPAIR".equals(detectedIntent)
                || "MOOD_DROP_RECOVERY".equals(detectedIntent)
                || SentimentIntentLayer.isMoodDropRecoveryMessage(rawMsg)) {
            return new ThinkingDecision(
                    ThinkingMode.EMOTIONAL_WARM_THINK,
                    ThinkingMode.EMOTIONAL_WARM_THINK.maxOutputTokens(),
                    "MOOD_DROP_RECOVERY".equals(detectedIntent) ? "MOOD_DROP_EMOTIONAL_RECOVERY"
                            : ("CONVERSATION_STYLE_REPAIR".equals(detectedIntent) ? "CONVERSATION_STYLE_WARM_REPAIR" : "WORDING_FEEDBACK_WARM_REPAIR"),
                    ObjectionRootCause.NONE,
                    0,
                    priorArgs,
                    unresolvedIds
            );
        }

        // 0D (v3.3.4). Trả lời hời hợt giữa phễu ("ừm để xem đã", "vậy à") khi KHÔNG còn câu hỏi tồn đọng
        // -> EMOTIONAL_WARM_THINK để khơi lại cảm xúc thay vì trả lời cụt hoặc ép chốt lịch.
        if (!safeState.hasUnresolvedCustomerRequest()
                && ("MID_FUNNEL_LUKEWARM_REENGAGE".equals(detectedIntent)
                    || SentimentIntentLayer.isMidFunnelLukewarmReply(rawMsg, safeState))) {
            return new ThinkingDecision(
                    ThinkingMode.EMOTIONAL_WARM_THINK,
                    ThinkingMode.EMOTIONAL_WARM_THINK.maxOutputTokens(),
                    "MID_FUNNEL_LUKEWARM_REENGAGE",
                    ObjectionRootCause.NONE,
                    0,
                    priorArgs,
                    unresolvedIds
            );
        }

        // 1. Mode 4: REPAIR_FIRST — Khách bức xúc, phàn nàn bot tư vấn lệch ý, bác bỏ giả định, hoặc còn câu hỏi tồn đọng chưa được giải quyết
        boolean insightFrustrated = customerInsight != null
                && ((customerInsight.temperatureDelta() != null && customerInsight.temperatureDelta() <= -10)
                    || "DECAYING".equalsIgnoreCase(customerInsight.momentum()));
        boolean isPriceObjectionAction = effectiveAction == PrimaryAction.HANDLE_PRICE_OBJECTION_FIRST_TIME
                || effectiveAction == PrimaryAction.HANDLE_PRICE_OBJECTION_REPEAT
                || effectiveAction == PrimaryAction.EXPLAIN_UNIT_VALUE;
        boolean isNonRepairMetaOrAckAdvance = effectiveAction == PrimaryAction.ADVANCE_AFTER_ACK
                || "META_APOLOGY_POSITIVE_INTEREST_ADVANCE".equals(detectedIntent)
                || "CONFIRM_PREVIOUS_INFO_ADVANCE".equals(detectedIntent)
                || "META_COMMENT_ON_GREETING".equals(detectedIntent);
        boolean hasRepairComplaint = insightFrustrated
                || safeState.frustrationCount() >= 1
                || MISMATCH_OR_REPAIR_COMPLAINT_PATTERN.matcher(normMsg).matches()
                || PrimaryActionRouter.isFrustrationMessage(rawMsg)
                || SentimentIntentLayer.isNegativeOrFrustratedMood(rawMsg)
                || SentimentIntentLayer.isEntityMismatchCorrection(rawMsg)
                || SentimentIntentLayer.isComprehensionFailureMessage(rawMsg)
                || SentimentIntentLayer.isNegationOrPushbackOnAssumption(rawMsg)
                || detectedIntent.contains("FRUSTRATION_RECOVERY")
                || detectedIntent.contains("COMPREHENSION_RECOVERY")
                || detectedIntent.contains("COMPREHENSION_REPAIR")
                || detectedIntent.contains("MISMATCH_CORRECTION")
                || "PUSHBACK_ON_ASSUMPTION_REPAIR".equals(detectedIntent)
                || "FRUSTRATION_CONCERN_ACKNOWLEDGEMENT".equals(detectedIntent)
                || "CLARIFICATION_DETAIL_OR_FAQ_QA".equals(detectedIntent);
        boolean isPureServiceDetailFaqWithoutComplaint = !hasRepairComplaint
                && effectiveAction == PrimaryAction.SERVICE_DETAIL_AND_FAQ_QA
                && !"CLARIFICATION_DETAIL_OR_FAQ_QA".equals(detectedIntent)
                && safeState.priceDiscussed();
        boolean hasUnresolvedQuestionFromPriorTurns = !isPureServiceDetailFaqWithoutComplaint
                && safeState.hasUnresolvedCustomerRequest();

        if (!isPriceObjectionAction && !isNonRepairMetaOrAckAdvance && (hasRepairComplaint || hasUnresolvedQuestionFromPriorTurns)) {
            // v3.3.4: Tách 2 chiến lược tâm lý:
            //  - REPAIR_FIRST: còn NỘI DUNG phải giải quyết (câu hỏi tồn đọng, câu hỏi chi tiết ngay trong tin nhắn,
            //    khách không hiểu, khách sửa sai/bác bỏ giả định của Bot) -> nhận lỗi ngắn + trả lời trực diện.
            //  - EMOTIONAL_WARM_THINK: chỉ là CẢM XÚC tiêu cực với cách tư vấn, không có nội dung tồn đọng
            //    -> hạ cái tôi, xoa dịu và kéo lại thiện cảm.
            boolean hasConcreteContentToResolve = hasUnresolvedQuestionFromPriorTurns
                    || SentimentIntentLayer.isDetailOrFaqQuestion(rawMsg, safeState)
                    || CONCRETE_CONTENT_QUESTION_PATTERN.matcher(normMsg).matches()
                    || SentimentIntentLayer.isComprehensionFailureMessage(rawMsg)
                    || SentimentIntentLayer.isEntityMismatchCorrection(rawMsg)
                    || SentimentIntentLayer.isNegationOrPushbackOnAssumption(rawMsg)
                    || detectedIntent.contains("COMPREHENSION_RECOVERY")
                    || detectedIntent.contains("COMPREHENSION_REPAIR")
                    || detectedIntent.contains("MISMATCH_CORRECTION")
                    || detectedIntent.contains("PENDING_PRICE")
                    || "PUSHBACK_ON_ASSUMPTION_REPAIR".equals(detectedIntent)
                    || "CLARIFICATION_DETAIL_OR_FAQ_QA".equals(detectedIntent)
                    || effectiveAction == PrimaryAction.SERVICE_DETAIL_AND_FAQ_QA;
            if (!hasConcreteContentToResolve && SentimentIntentLayer.isNegativeOrFrustratedMood(rawMsg)) {
                return new ThinkingDecision(
                        ThinkingMode.EMOTIONAL_WARM_THINK,
                        ThinkingMode.EMOTIONAL_WARM_THINK.maxOutputTokens(),
                        "NEGATIVE_MOOD_DEESCALATION",
                        ObjectionRootCause.NONE,
                        Math.max(1, safeState.objectionCount()),
                        priorArgs,
                        unresolvedIds
                );
            }
            return new ThinkingDecision(
                    ThinkingMode.REPAIR_FIRST,
                    ThinkingMode.REPAIR_FIRST.maxOutputTokens(),
                    "CUSTOMER_FRUSTRATION_OR_UNRESOLVED_REPAIR",
                    ObjectionRootCause.NONE,
                    Math.max(1, safeState.objectionCount()),
                    priorArgs,
                    unresolvedIds
            );
        }

        // 2. Mode 3: OBJECTION_REFRAME_THINK — Khách chê đắt, so sánh đắt/rẻ, nghi ngờ hiệu quả, ngập ngừng
        boolean isExplicitRefusalOrThinkLater = EXPLICIT_REFUSAL_OR_THINK_LATER_PATTERN.matcher(normMsg).matches()
                || "STRONG_REJECTION".equals(detectedIntent)
                || "HESITATION_LATER".equals(detectedIntent);
        boolean isPriceObjectionTurn = effectiveAction == PrimaryAction.HANDLE_PRICE_OBJECTION_FIRST_TIME
                || effectiveAction == PrimaryAction.HANDLE_PRICE_OBJECTION_REPEAT
                || effectiveAction == PrimaryAction.EXPLAIN_UNIT_VALUE
                || PrimaryActionRouter.isPriceObjectionMessage(rawMsg)
                || SentimentIntentLayer.isExplicitDownsellRequest(rawMsg, safeState)
                || EXTERNAL_COMPARISON_OBJECTION_PATTERN.matcher(normMsg).matches()
                || VALUE_DOUBT_OBJECTION_PATTERN.matcher(normMsg).matches();

        if (isExplicitRefusalOrThinkLater || isPriceObjectionTurn) {
            ObjectionRootCause rootCause = classifyObjectionRootCause(
                    rawMsg, normMsg, safeState, effectiveAction, isExplicitRefusalOrThinkLater
            );
            int objTurnIdx = effectiveAction == PrimaryAction.HANDLE_PRICE_OBJECTION_REPEAT
                    ? Math.max(2, safeState.objectionCount() + 1)
                    : Math.max(1, safeState.objectionCount() + 1);
            return new ThinkingDecision(
                    ThinkingMode.OBJECTION_REFRAME_THINK,
                    ThinkingMode.OBJECTION_REFRAME_THINK.maxOutputTokens(),
                    "PRICE_OBJECTION_OR_HESITATION_REFRAME",
                    rootCause,
                    objTurnIdx,
                    priorArgs,
                    unresolvedIds
            );
        }

        // 3. Mode 2: PRICE_ANCHOR_THINK — Báo giá lần đầu / gợi ý gói kèm giá / gợi ý gói thay thế (chỉ khi thực sự báo giá, không bật khi đang hỏi làm rõ hoặc khi tiến bước sau meta/confirm mà giá đã báo)
        boolean isClarifyingBeforePrice = effectiveAction == PrimaryAction.CLARIFY_SERVICE_DURATION
                || effectiveAction == PrimaryAction.ACKNOWLEDGE_AND_ASK_PREFERENCE
                || isNonRepairMetaOrAckAdvance;
        boolean isPriceAnchorTurn = !isClarifyingBeforePrice
                && (effectiveAction == PrimaryAction.ANSWER_PRICE_ONCE
                    || effectiveAction == PrimaryAction.RECOMMEND_ONE_PACKAGE
                    || detectedIntent.contains("ALTERNATIVE_PACKAGE")
                    || "RECOMMEND_AND_QUOTE_IF_PRICE_REQUESTED".equals(detectedIntent)
                    || "EXPLICIT_PRICE_INQUIRY".equals(detectedIntent));
        if (isPriceAnchorTurn) {
            return new ThinkingDecision(
                    ThinkingMode.PRICE_ANCHOR_THINK,
                    ThinkingMode.PRICE_ANCHOR_THINK.maxOutputTokens(),
                    "FIRST_TIME_OR_ALTERNATIVE_PRICE_ANCHOR",
                    ObjectionRootCause.NONE,
                    0,
                    priorArgs,
                    unresolvedIds
            );
        }

        // 4. Mode 1: FAST_NO_THINK — Chào hỏi, hỏi triệu chứng, địa chỉ/giờ mở cửa, câu hỏi chi tiết/FAQ ngắn đã có chunk rõ
        String fastReason = effectiveAction == PrimaryAction.SERVICE_DETAIL_AND_FAQ_QA
                ? "DIRECT_SERVICE_DETAIL_OR_FAQ_FAST"
                : (LOCATION_OR_HOURS_FAST_PATTERN.matcher(normMsg).matches()
                    ? "LOCATION_OR_HOURS_FAST"
                    : "STANDARD_DISCOVERY_FAST");
        return applyBenchmarkOverrideIfNeeded(new ThinkingDecision(
                ThinkingMode.FAST_NO_THINK,
                ThinkingMode.FAST_NO_THINK.maxOutputTokens(),
                fastReason,
                ObjectionRootCause.NONE,
                0,
                priorArgs,
                unresolvedIds
        ), effectiveBench);
    }

    public static ThinkingDecision decide(
            RoutingDecision routingDecision,
            String customerMessage,
            List<FanpageBotRequest.ConversationTurn> history,
            ConversationState state,
            List<ReciprocalRankFusion.FusedChunk> fusedChunks,
            PriceLadderTracker.PriceLadderResult priceLadder,
            boolean isGeneratingPostPriceSecondBubble
    ) {
        ThinkingDecision base = decide(customerMessage, state, routingDecision, null, getBenchmarkConfigMode());
        if (base.thinkingMode() == ThinkingMode.FAST_NO_THINK && isGeneratingPostPriceSecondBubble) {
            return new ThinkingDecision(
                    ThinkingMode.PRICE_ANCHOR_THINK,
                    ThinkingMode.PRICE_ANCHOR_THINK.maxOutputTokens(),
                    "POST_PRICE_SECOND_BUBBLE_ANCHOR",
                    ObjectionRootCause.NONE,
                    0,
                    base.previouslyUsedArguments(),
                    base.unresolvedQuestionIds()
            );
        }
        return base;
    }

    private static ThinkingDecision applyBenchmarkOverrideIfNeeded(
            ThinkingDecision baseDecision,
            BenchmarkConfigMode benchMode
    ) {
        if (benchMode == BenchmarkConfigMode.FORCE_ALWAYS_THINK_B
                && baseDecision.thinkingMode() == ThinkingMode.FAST_NO_THINK) {
            return new ThinkingDecision(
                    ThinkingMode.PRICE_ANCHOR_THINK,
                    ThinkingMode.PRICE_ANCHOR_THINK.maxOutputTokens(),
                    "BENCHMARK_FORCE_ALWAYS_THINK_B",
                    baseDecision.objectionRootCause(),
                    baseDecision.objectionTurnIndex(),
                    baseDecision.previouslyUsedArguments(),
                    baseDecision.unresolvedQuestionIds()
            );
        }
        return baseDecision;
    }

    /**
     * Phân loại nguyên nhân gốc rễ của phản ứng chê đắt / ngập ngừng để tránh lặp lại lý lẽ cũ (Section V.A).
     */
    public static ObjectionRootCause classifyObjectionRootCause(
            String rawMsg,
            String normMsg,
            ConversationState state,
            PrimaryAction effectiveAction,
            boolean isExplicitRefusalOrThinkLater
    ) {
        if (isExplicitRefusalOrThinkLater) {
            return ObjectionRootCause.EXPLICIT_REFUSAL;
        }
        if (EXTERNAL_COMPARISON_OBJECTION_PATTERN.matcher(normMsg).matches()) {
            return ObjectionRootCause.EXTERNAL_COMPARISON;
        }
        if (VALUE_DOUBT_OBJECTION_PATTERN.matcher(normMsg).matches()) {
            return ObjectionRootCause.VALUE_DOUBT;
        }
        if (LOW_TRUST_OBJECTION_PATTERN.matcher(normMsg).matches()) {
            return ObjectionRootCause.LOW_TRUST;
        }
        ConversationState safeState = state != null ? state : ConversationState.initial();
        boolean asksCheaperExplicitly = BUDGET_CONSTRAINT_OBJECTION_PATTERN.matcher(normMsg).matches()
                || SentimentIntentLayer.isExplicitDownsellRequest(rawMsg, safeState);
        if (asksCheaperExplicitly || effectiveAction == PrimaryAction.HANDLE_PRICE_OBJECTION_REPEAT || safeState.objectionCount() >= 1) {
            return ObjectionRootCause.BUDGET_CONSTRAINT;
        }
        if (UNCLEAR_PACKAGE_OBJECTION_PATTERN.matcher(normMsg).matches()
                || effectiveAction == PrimaryAction.EXPLAIN_UNIT_VALUE) {
            return ObjectionRootCause.UNCLEAR_PACKAGE;
        }
        return ObjectionRootCause.GENERAL_PRICE_HESITATION;
    }

    public static List<String> extractPreviouslyUsedArgumentsFromState(ConversationState state) {
        if (state == null) {
            return List.of();
        }
        Set<String> used = new LinkedHashSet<>();
        if (state.deliveredValueAngles() != null) {
            used.addAll(state.deliveredValueAngles());
        }
        List<String> sources = new ArrayList<>();
        if (state.lastAssistantMessage() != null && !state.lastAssistantMessage().isBlank()) {
            String lastMsg = state.lastAssistantMessage().strip();
            sources.add(lastMsg);
            if (state.priceDiscussed()) {
                String[] parts = lastMsg.split("\\n\\n+");
                if (parts.length >= 2 && !parts[parts.length - 1].isBlank()) {
                    used.add(parts[parts.length - 1].strip());
                } else {
                    used.add(lastMsg);
                }
            }
        }
        if (state.usedTemplates() != null) {
            sources.addAll(state.usedTemplates());
        }
        for (String reply : sources) {
            if (reply == null || reply.isBlank()) {
                continue;
            }
            String low = reply.toLowerCase(Locale.ROOT);
            if (low.contains("trọn gói") || low.contains("đã bao gồm") || low.contains("tắm xông") || low.contains("ăn nhẹ")) {
                used.add("full_service_inclusion");
            }
            if (low.contains("không nhận tip") || low.contains("không thu phụ phí")) {
                used.add("no_tip_policy");
            }
            if (low.contains("trải nghiệm thử 1 buổi") || low.contains("thử 1 buổi")) {
                used.add("single_session_trial");
            }
            if (low.contains("giảm 10%") || low.contains("thẻ từ 5 buổi") || low.contains("voucher")) {
                used.add("multi_session_discount");
            }
        }
        if (state.trialInviteOffered()) {
            used.add("single_session_trial");
        }
        return List.copyOf(used);
    }

    public static List<String> extractPreviouslyUsedArguments(List<FanpageBotRequest.ConversationTurn> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        Set<String> used = new LinkedHashSet<>();
        for (FanpageBotRequest.ConversationTurn turn : history) {
            if (turn == null || turn.content() == null) {
                continue;
            }
            if (!"assistant".equalsIgnoreCase(turn.role()) && !"bot".equalsIgnoreCase(turn.role())) {
                continue;
            }
            String low = turn.content().toLowerCase(Locale.ROOT);
            if (low.contains("trọn gói") || low.contains("đã bao gồm") || low.contains("tắm xông") || low.contains("ăn nhẹ")) {
                used.add("full_service_inclusion");
            }
            if (low.contains("không nhận tip") || low.contains("không thu phụ phí")) {
                used.add("no_tip_policy");
            }
            if (low.contains("trải nghiệm thử 1 buổi") || low.contains("thử 1 buổi")) {
                used.add("single_session_trial");
            }
            if (low.contains("giảm 10%") || low.contains("thẻ từ 5 buổi") || low.contains("voucher")) {
                used.add("multi_session_discount");
            }
        }
        return List.copyOf(used);
    }

    public static List<String> extractUnresolvedQuestionIds(ConversationState state) {
        if (state == null || state.outstandingRequests() == null || state.outstandingRequests().isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (ConversationState.UnresolvedCustomerRequest req : state.outstandingRequests()) {
            if (req != null && !req.resolved()) {
                String id = req.semanticIntent() + ":" + (req.referencedEntity() != null ? req.referencedEntity() : req.turnIndex());
                ids.add(id);
            }
        }
        return Collections.unmodifiableList(ids);
    }

    public static List<String> extractRetrievedSourceIds(List<ReciprocalRankFusion.FusedChunk> fusedChunks) {
        if (fusedChunks == null || fusedChunks.isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (ReciprocalRankFusion.FusedChunk fc : fusedChunks) {
            if (fc == null || fc.chunk() == null) {
                continue;
            }
            RetrievedChunk rc = fc.chunk();
            if (rc.chunkId() != null) {
                ids.add(rc.chunkId().toString());
            } else if (rc.documentId() != null) {
                ids.add(rc.documentId().toString());
            }
        }
        return Collections.unmodifiableList(ids);
    }

    private static boolean hasPhoneBookingSignal(String normMsg) {
        if (normMsg == null || normMsg.isBlank()) {
            return false;
        }
        return normMsg.matches("(?isU).*(?:\\b0\\d{8,10}\\b|\\+84\\d{8,10}).*");
    }
}
