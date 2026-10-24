package com.example.docmind.service.bot;

/**
 * Chế độ suy luận thích ứng (Adaptive Thinking Mode) độc lập với nhà cung cấp LLM.
 * <p>
 * Tầng nghiệp vụ ({@link ThinkingModePolicy}, {@link FanpageBotService}, {@link PrimaryActionRouter})
 * chỉ làm việc với {@link ThinkingMode} và {@code maxOutputTokens}, tuyệt đối không phụ thuộc vào
 * tham số đặc thù của bất kỳ LLM Adapter cụ thể nào.
 */
public enum ThinkingMode {

    /**
     * Mode 1: Phản hồi nhanh không bật suy luận ẩn (Mặc định, ~70-80% số lượt).
     * Dùng cho chào hỏi, hỏi triệu chứng, địa chỉ, giờ mở cửa, câu hỏi chi tiết/FAQ đã có dữ liệu rõ,
     * xác nhận đặt lịch/SĐT và chuyển nhân viên.
     */
    FAST_NO_THINK(false, 250, 2, 65),

    /**
     * Mode 2: Suy luận ngắn để neo giá trị cảm xúc ở Bubble 2 ngay sau khi Bubble 1 báo giá lần đầu.
     * Kết nối nỗi đau/mục tiêu cụ thể của khách với cơ chế, quy trình hoặc phản hồi khách hàng đã xác thực trong KB.
     */
    PRICE_ANCHOR_THINK(true, 500, 3, 65),

    /**
     * Mode 3: Suy luận xử lý chê đắt, ngập ngừng hoặc so sánh giá.
     * Chẩn đoán đúng nguyên nhân gốc rễ và tránh lặp lại lý lẽ đã dùng ở lượt trước.
     */
    OBJECTION_REFRAME_THINK(true, 500, 3, 65),

    /**
     * Mode 4: Suy luận khôi phục niềm tin và giải quyết câu hỏi tồn đọng trước khi bán hàng.
     * Kích hoạt khi khách hàng bức xúc, phàn nàn bot trả lời lệch ý hoặc còn câu hỏi chưa được trả lời.
     */
    REPAIR_FIRST(true, 500, 3, 65);

    private final boolean thinkEnabled;
    private final int maxOutputTokens;
    private final int maxSentencesPerBubble;
    private final int maxWordsPerBubble;

    ThinkingMode(
            boolean thinkEnabled,
            int maxOutputTokens,
            int maxSentencesPerBubble,
            int maxWordsPerBubble
    ) {
        this.thinkEnabled = thinkEnabled;
        this.maxOutputTokens = maxOutputTokens;
        this.maxSentencesPerBubble = maxSentencesPerBubble;
        this.maxWordsPerBubble = maxWordsPerBubble;
    }

    public boolean thinkEnabled() {
        return thinkEnabled;
    }

    public boolean isDeepThink() {
        return thinkEnabled;
    }

    public int maxOutputTokens() {
        return maxOutputTokens;
    }

    public int defaultMaxOutputTokens() {
        return maxOutputTokens;
    }

    public int maxSentencesPerBubble() {
        return maxSentencesPerBubble;
    }

    public int maxWordsPerBubble() {
        return maxWordsPerBubble;
    }

    public boolean allowsExtendedConsultationBubble() {
        return this != FAST_NO_THINK;
    }
}
