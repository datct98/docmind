package com.example.docmind.service.reasoning;

/**
 * Phân cấp độ phức tạp câu hỏi của khách hàng phục vụ điều phối ngân sách suy luận (F8: ReasoningBudget).
 * - SIMPLE: 4–8s target latency (max 15s guardrail), reasoningEffort = "low".
 * - DEEP_REASONING: 15–30s target latency (max 30s budget), reasoningEffort = "medium".
 */
public enum QueryComplexity {
    SIMPLE(4_000, 8_000, "low"),
    DEEP_REASONING(15_000, 30_000, "medium");

    private final int minLatencyMs;
    private final int maxLatencyMs;
    private final String reasoningEffort;

    QueryComplexity(int minLatencyMs, int maxLatencyMs, String reasoningEffort) {
        this.minLatencyMs = minLatencyMs;
        this.maxLatencyMs = maxLatencyMs;
        this.reasoningEffort = reasoningEffort;
    }

    public int getMinLatencyMs() {
        return minLatencyMs;
    }

    public int getMaxLatencyMs() {
        return maxLatencyMs;
    }

    public String getReasoningEffort() {
        return reasoningEffort;
    }

    public boolean isDeepReasoning() {
        return this == DEEP_REASONING;
    }
}
