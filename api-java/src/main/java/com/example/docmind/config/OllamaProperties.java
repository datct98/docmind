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
    private double temperature = 0.25;

    /** Giới hạn cửa sổ ngữ cảnh vừa đủ (4096 tokens) để tiết kiệm RAM trên máy 16GB CPU. */
    private int numCtx = 4096;

    /** Giới hạn số token sinh ra tối đa (700 tokens cho tư vấn bán hàng Fanpage) để cân bằng tốc độ và độ chi tiết. */
    private int numPredict = 700;

    /** Top-P sampling (mặc định 0.9 cho tư vấn bán hàng tự nhiên nhưng bám sát ngữ cảnh). */
    private double topP = 0.9;

    /** Hệ số phạt lặp từ (repeat_penalty = 1.08). */
    private double repeatPenalty = 1.08;

    /** Model dự phòng tùy chọn (mặc định để trống, không dùng fallback 7B). */
    private String fallbackModel = "";

    /** Timeout đọc kết quả từ Ollama (ms) — chạy CPU + nạp model lần đầu cần khoảng 120-240s. */
    private int readTimeoutMs = 240000;
}
