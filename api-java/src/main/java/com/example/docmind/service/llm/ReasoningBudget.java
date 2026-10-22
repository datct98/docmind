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
        double temperature
) {
    public static final int SIMPLE_TIMEOUT_SECONDS = 15;
    public static final int SIMPLE_MAX_TOKENS = 600;
    public static final double SIMPLE_TEMPERATURE = 0.1;
    public static final double DEFAULT_TEMPERATURE = 0.1;

    public static final int DEEP_TIMEOUT_SECONDS = 45;
    public static final int DEEP_MAX_TOKENS = 2000;
    public static final double DEEP_TEMPERATURE = 0.2;

    public static ReasoningBudget simple() {
        return new ReasoningBudget(
                QueryComplexity.SIMPLE,
                QueryComplexity.SIMPLE.getReasoningEffort(), // "low"
                SIMPLE_TIMEOUT_SECONDS,
                QueryComplexity.SIMPLE.getMinLatencyMs(),    // 4000
                QueryComplexity.SIMPLE.getMaxLatencyMs(),    // 8000
                SIMPLE_MAX_TOKENS,
                SIMPLE_TEMPERATURE
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
                DEEP_TEMPERATURE
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
