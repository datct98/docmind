package com.example.docmind.service.llm;

import com.example.docmind.service.bot.ThinkingMode;
import com.example.docmind.service.bot.ThinkingModePolicy;
import com.example.docmind.service.reasoning.QueryComplexity;

/**
 * Ngân sách suy luận thích ứng (Adaptive Reasoning Budget) theo độ phức tạp câu hỏi (F8)
 * và chế độ suy luận nội tâm provider-agnostic ({@link ThinkingMode}).
 * Định hình max_tokens, timeout_seconds, target latency và mức độ suy luận (reasoning_effort / ThinkingMode)
 * gửi tới các adapter LLM (Ollama, OpenAI-compatible, v.v.).
 */
public record ReasoningBudget(
        QueryComplexity complexity,
        String reasoningEffort,
        int timeoutSeconds,
        int targetMinLatencyMs,
        int targetMaxLatencyMs,
        int maxTokens,
        double temperature,
        Double topP,
        Double repeatPenalty,
        ThinkingMode thinkingMode
) {
    public static final int SIMPLE_TIMEOUT_SECONDS = 15;
    public static final int SIMPLE_MAX_TOKENS = 600;
    public static final int SALES_CHAT_MAX_TOKENS = 600;
    public static final double SIMPLE_TEMPERATURE = 0.1;
    public static final double DEFAULT_TEMPERATURE = 0.1;
    public static final double SALES_CHAT_TEMPERATURE = resolveSalesChatTemperature();
    public static final double SALES_TOP_P = 0.9;
    public static final double SALES_REPEAT_PENALTY = 1.08;

    private static double resolveSalesChatTemperature() {
        String envTemp = System.getenv("LLM_TEMPERATURE");
        if (envTemp != null && !envTemp.isBlank()) {
            try {
                double parsed = Double.parseDouble(envTemp.strip());
                if (parsed >= 0.0 && parsed <= 2.0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return 0.2;
    }

    public static final int DEEP_TIMEOUT_SECONDS = 45;
    public static final int DEEP_MAX_TOKENS = 2000;
    public static final double DEEP_TEMPERATURE = 0.2;

    /** Internal Knowledge QA profile: temperature=0.1, top_p=0.85, num_predict=900, repeat_penalty=1.05. */
    public static final int INTERNAL_QA_MAX_TOKENS = 900;
    public static final double INTERNAL_QA_TEMPERATURE = 0.1;
    public static final double INTERNAL_QA_TOP_P = 0.85;
    public static final double INTERNAL_QA_REPEAT_PENALTY = 1.05;

    /** Memory / Rule Extraction & QA Judge profile: temperature=0.0, top_p=0.8, repeat_penalty=1.05. */
    public static final int EXTRACTION_MAX_TOKENS = 700;
    public static final double EXTRACTION_TEMPERATURE = 0.0;
    public static final double EXTRACTION_TOP_P = 0.8;
    public static final double EXTRACTION_REPEAT_PENALTY = 1.05;

    public ReasoningBudget(
            QueryComplexity complexity,
            String reasoningEffort,
            int timeoutSeconds,
            int targetMinLatencyMs,
            int targetMaxLatencyMs,
            int maxTokens,
            double temperature,
            Double topP,
            Double repeatPenalty
    ) {
        this(
                complexity,
                reasoningEffort,
                timeoutSeconds,
                targetMinLatencyMs,
                targetMaxLatencyMs,
                maxTokens,
                temperature,
                topP,
                repeatPenalty,
                null
        );
    }

    public ReasoningBudget(
            QueryComplexity complexity,
            String reasoningEffort,
            int timeoutSeconds,
            int targetMinLatencyMs,
            int targetMaxLatencyMs,
            int maxTokens,
            double temperature
    ) {
        this(
                complexity,
                reasoningEffort,
                timeoutSeconds,
                targetMinLatencyMs,
                targetMaxLatencyMs,
                maxTokens,
                temperature,
                null,
                null,
                null
        );
    }

    public boolean hasExplicitThinkingMode() {
        return thinkingMode != null;
    }

    public boolean thinkEnabled() {
        return thinkingMode != null && thinkingMode.isDeepThink();
    }

    public static ReasoningBudget simple() {
        return new ReasoningBudget(
                QueryComplexity.SIMPLE,
                QueryComplexity.SIMPLE.getReasoningEffort(), // "low"
                SIMPLE_TIMEOUT_SECONDS,
                QueryComplexity.SIMPLE.getMinLatencyMs(),    // 4000
                QueryComplexity.SIMPLE.getMaxLatencyMs(),    // 8000
                SIMPLE_MAX_TOKENS,
                SIMPLE_TEMPERATURE,
                null,
                null,
                null
        );
    }

    public static ReasoningBudget deepReasoning() {
        return new ReasoningBudget(
                QueryComplexity.DEEP_REASONING,
                QueryComplexity.DEEP_REASONING.getReasoningEffort(), // "medium"
                DEEP_TIMEOUT_SECONDS,
                QueryComplexity.DEEP_REASONING.getMinLatencyMs(),    // 15000
                QueryComplexity.DEEP_REASONING.getMaxLatencyMs(),    // 30000
                DEEP_MAX_TOKENS,
                DEEP_TEMPERATURE,
                null,
                null,
                null
        );
    }

    /**
     * Tạo ReasoningBudget theo chế độ suy luận nội tâm {@link ThinkingMode} (Adaptive Thinking Router).
     */
    public static ReasoningBudget forThinkingMode(ThinkingMode mode) {
        ThinkingMode effective = mode != null ? mode : ThinkingMode.FAST_NO_THINK;
        QueryComplexity complexity = effective.isDeepThink()
                ? QueryComplexity.DEEP_REASONING
                : QueryComplexity.SIMPLE;
        int timeoutSeconds = effective.isDeepThink() ? DEEP_TIMEOUT_SECONDS : SIMPLE_TIMEOUT_SECONDS;
        return new ReasoningBudget(
                complexity,
                complexity.getReasoningEffort(),
                timeoutSeconds,
                complexity.getMinLatencyMs(),
                complexity.getMaxLatencyMs(),
                effective.maxOutputTokens(),
                SALES_CHAT_TEMPERATURE,
                SALES_TOP_P,
                SALES_REPEAT_PENALTY,
                effective
        );
    }

    /**
     * Tạo ReasoningBudget từ {@link ThinkingModePolicy.ThinkingDecision}.
     */
    public static ReasoningBudget forThinkingDecision(ThinkingModePolicy.ThinkingDecision decision) {
        if (decision == null) {
            return forThinkingMode(ThinkingMode.FAST_NO_THINK);
        }
        ThinkingMode effective = decision.thinkingMode() != null
                ? decision.thinkingMode()
                : ThinkingMode.FAST_NO_THINK;
        QueryComplexity complexity = effective.isDeepThink()
                ? QueryComplexity.DEEP_REASONING
                : QueryComplexity.SIMPLE;
        int timeoutSeconds = effective.isDeepThink() ? DEEP_TIMEOUT_SECONDS : SIMPLE_TIMEOUT_SECONDS;
        int maxTokens = decision.maxOutputTokens() > 0 ? decision.maxOutputTokens() : effective.maxOutputTokens();
        return new ReasoningBudget(
                complexity,
                complexity.getReasoningEffort(),
                timeoutSeconds,
                complexity.getMinLatencyMs(),
                complexity.getMaxLatencyMs(),
                maxTokens,
                SALES_CHAT_TEMPERATURE,
                SALES_TOP_P,
                SALES_REPEAT_PENALTY,
                effective
        );
    }

    /**
     * Task profile 1 — Customer Sales Chat (Fanpage Bot):
     * temperature = 0.2, top_p = 0.9, repeat_penalty = 1.08, num_predict = 600 (SIMPLE) / 700..2000 (DEEP).
     */
    public static ReasoningBudget salesChat(QueryComplexity complexity) {
        if (complexity == QueryComplexity.DEEP_REASONING) {
            return new ReasoningBudget(
                    QueryComplexity.DEEP_REASONING,
                    QueryComplexity.DEEP_REASONING.getReasoningEffort(),
                    DEEP_TIMEOUT_SECONDS,
                    QueryComplexity.DEEP_REASONING.getMinLatencyMs(),
                    QueryComplexity.DEEP_REASONING.getMaxLatencyMs(),
                    DEEP_MAX_TOKENS,
                    SALES_CHAT_TEMPERATURE,
                    SALES_TOP_P,
                    SALES_REPEAT_PENALTY,
                    null
            );
        }
        return new ReasoningBudget(
                QueryComplexity.SIMPLE,
                QueryComplexity.SIMPLE.getReasoningEffort(),
                SIMPLE_TIMEOUT_SECONDS,
                QueryComplexity.SIMPLE.getMinLatencyMs(),
                QueryComplexity.SIMPLE.getMaxLatencyMs(),
                SALES_CHAT_MAX_TOKENS,
                SALES_CHAT_TEMPERATURE,
                SALES_TOP_P,
                SALES_REPEAT_PENALTY,
                null
        );
    }

    /**
     * Task profile 2 — Internal Knowledge QA (ChatService):
     * temperature = 0.1, top_p = 0.85, num_predict = 900, repeat_penalty = 1.05.
     */
    public static ReasoningBudget internalQa() {
        return new ReasoningBudget(
                QueryComplexity.SIMPLE,
                QueryComplexity.SIMPLE.getReasoningEffort(),
                DEEP_TIMEOUT_SECONDS,
                QueryComplexity.SIMPLE.getMinLatencyMs(),
                QueryComplexity.DEEP_REASONING.getMinLatencyMs(),
                INTERNAL_QA_MAX_TOKENS,
                INTERNAL_QA_TEMPERATURE,
                INTERNAL_QA_TOP_P,
                INTERNAL_QA_REPEAT_PENALTY,
                null
        );
    }

    /**
     * Task profile 3 — Memory / Rule Extraction & QA Judge (MetaLearningService, HarnessOptimizerService):
     * temperature = 0.0, top_p = 0.8, repeat_penalty = 1.05.
     */
    public static ReasoningBudget extractionOrJudge() {
        return new ReasoningBudget(
                QueryComplexity.SIMPLE,
                QueryComplexity.SIMPLE.getReasoningEffort(),
                SIMPLE_TIMEOUT_SECONDS,
                QueryComplexity.SIMPLE.getMinLatencyMs(),
                QueryComplexity.SIMPLE.getMaxLatencyMs(),
                EXTRACTION_MAX_TOKENS,
                EXTRACTION_TEMPERATURE,
                EXTRACTION_TOP_P,
                EXTRACTION_REPEAT_PENALTY,
                null
        );
    }

    public static ReasoningBudget forComplexity(QueryComplexity complexity) {
        if (complexity == QueryComplexity.DEEP_REASONING) {
            return deepReasoning();
        }
        return simple();
    }

    public static ReasoningBudget defaultBudget() {
        return simple();
    }
}
