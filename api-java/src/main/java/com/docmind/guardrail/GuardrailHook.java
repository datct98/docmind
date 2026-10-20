package com.docmind.guardrail;

/**
 * Giao diện đại diện cho một In-Loop Deterministic Guardrail Hook.
 * <p>
 * Triết lý từ Harness²: "Prose can be stopped reading; a hook cannot."
 * Mỗi hook chịu trách nhiệm kiểm tra và thực thi tất định một bất biến (invariant)
 * của hệ thống tư vấn trong ngân sách thời gian cực thấp (< 5ms cho toàn bộ pipeline).
 */
public interface GuardrailHook {

    /**
     * Mã định danh telemetry duy nhất của Hook (ví dụ: HOOK_HONORIFIC_CONSISTENCY).
     */
    String getHookCode();

    /**
     * Kiểm tra xem Hook có cần thiết phải kích hoạt đối với ngữ cảnh và bản thảo hiện tại hay không.
     */
    default boolean isApplicable(GuardrailContext context, GuardrailDraft draft) {
        return true;
    }

    /**
     * Thực thi việc sửa đổi tất định (deterministic repair) trên bản thảo.
     *
     * @param context Ngữ cảnh khách hàng và hội thoại
     * @param draft   Bản thảo có thể biến đổi (mutable draft)
     * @return true nếu bản thảo đã bị sửa đổi (hook đã kích hoạt), false nếu không thay đổi
     */
    boolean apply(GuardrailContext context, GuardrailDraft draft);
}
