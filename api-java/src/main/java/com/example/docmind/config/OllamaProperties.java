package com.example.docmind.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Cấu hình kết nối Ollama chạy local.
 * Mặc định phân giải qua {@link LlmProperties}.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ollama")
public class OllamaProperties {

    /** Bật/tắt sử dụng Ollama khi không cấu hình Gemini API key. */
    private boolean enabled = true;

    /** Địa chỉ máy chủ Ollama local. */
    private String baseUrl = new LlmProperties().resolveBaseUrl();

    /** Tên model trong Ollama (kế thừa từ LlmProperties). */
    private String model = new LlmProperties().resolveModel();

    /** Nhiệt độ sinh văn bản (0.25 cho tư vấn bán hàng tự nhiên nhưng bám sát ngữ cảnh RAG). */
    private double temperature = 0.3;

    /** Giới hạn cửa sổ ngữ cảnh vừa đủ (3072 tokens) để tăng tốc prompt eval và giữ KV-cache trên máy 16GB RAM. */
    private int numCtx = 8192;

    /** Giới hạn số token sinh ra tối đa (550 tokens cho tư vấn bán hàng Fanpage) để cân bằng tốc độ và độ chi tiết. */
    private int numPredict = 550; // fallback khi không có cấu hình Spring; runtime lấy ollama.num-predict (mặc định 2048)

    /** Top-P sampling (mặc định 0.9 cho tư vấn bán hàng tự nhiên nhưng bám sát ngữ cảnh). */
    private double topP = 0.9;

    /** Hệ số phạt lặp từ (repeat_penalty = 1.08). */
    private double repeatPenalty = 1.08;

    /** Chuỗi thời gian giữ model nóng trên RAM/VRAM (mặc định "30m" ở top-level /api/chat). */
    private String keepAlive = "30m";

    /**
     * Khi chạy trên Ollama local (CPU / Unified Memory), nếu ngữ cảnh sự thật đã được chuẩn bị sẵn
     * trong {@code <GROUNDED_TURN_FACTS>}, tắt chuỗi suy luận nội bộ dài dòng ở tầng model để giảm trễ 25-40s.
     */
    private boolean suppressRedundantThinkWhenGrounded = true;

    /** Model dự phòng tùy chọn (mặc định để trống, không dùng fallback 7B). */
    private String fallbackModel = "";

    /** Timeout đọc kết quả từ Ollama (ms) — chạy CPU + nạp model lần đầu cần khoảng 120-240s. */
    private int readTimeoutMs = 240000;
}
