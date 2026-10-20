package com.docmind.guardrail;

import com.example.docmind.dto.bot.FanpageBotResponse.ReplyBubble;

import java.util.Collections;
import java.util.List;

/**
 * Kết quả sau khi chạy qua In-Loop Deterministic Guardrail Pipeline.
 * Bao gồm nội dung đã được chuẩn hóa, danh sách bong bóng, telemetry các hooks đã kích hoạt,
 * và thời gian thực thi (nanoseconds và milliseconds).
 */
public record GuardrailResult(
        String text,
        List<ReplyBubble> bubbles,
        List<String> triggeredHooks,
        long latencyNanos,
        double latencyMillis
) {
    public GuardrailResult {
        if (triggeredHooks == null) {
            triggeredHooks = Collections.emptyList();
        }
        if (bubbles == null) {
            bubbles = Collections.emptyList();
        }
    }

    public boolean isWithinBudget(double maxBudgetMs) {
        return latencyMillis <= maxBudgetMs;
    }

    public boolean hasTriggered(String hookCode) {
        return triggeredHooks.contains(hookCode);
    }
}
