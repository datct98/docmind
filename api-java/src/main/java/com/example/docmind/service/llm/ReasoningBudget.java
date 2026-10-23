package com.example.docmind.service.llm;

import com.example.docmind.service.reasoning.QueryComplexity;

/**
 * Ngân sách suy luận thích ứng (Adaptive Reasoning Budget) theo độ phức tạp câu hỏi (F8).
 * Định hình max_tokens, timeout_seconds, target latency và mức độ suy luận (reasoning_effort)
 * gửi tới các mô hình reasoning (OpenAI o1/o3/o4, Grok 4.x/3-mini, DeepSeek-R1).
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
        Double repeatPenalty
) {
    public static final int SIMPLE_TIMEOUT_SECONDS = 15;
    public static final int SIMPLE_MAX_TOKENS = 600;
    public static final int SALES_CHAT_MAX_TOKENS = 700;
    public static final double SIMPLE_TEMPERATURE = 0.1;
    public static final double DEFAULT_TEMPERATURE = 0.1;
    public static final double SALES_CHAT_TEMPERATURE = 0.25;
    public static final double SALES_TOP_P = 0.9;
    public static final double SALES_REPEAT_PENALTY = 1.08;

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
                null
        );
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
                null
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
                    SALES_REPEAT_PENALTY
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
                SALES_REPEAT_PENALTY
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
                INTERNAL_QA_REPEAT_PENALTY
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
                EXTRACTION_REPEAT_PENALTY
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
