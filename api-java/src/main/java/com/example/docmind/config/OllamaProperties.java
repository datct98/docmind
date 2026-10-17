package com.example.docmind.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Cấu hình kết nối Ollama chạy local (CPU / 16GB RAM).
 * Mặc định ưu tiên model {@code qwen2.5:7b} (hoặc {@code qwen2.5:3b} cho máy CPU yếu).
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ollama")
public class OllamaProperties {

    /** Bật/tắt sử dụng Ollama khi không cấu hình Gemini API key. */
    private boolean enabled = true;

    /** Địa chỉ máy chủ Ollama local. */
    private String baseUrl = "http://localhost:11434";

    /** Tên model trong Ollama (khuyến nghị: qwen2.5:7b hoặc qwen2.5:3b). */
    private String model = "qwen2.5:7b";

    /** Nhiệt độ sinh văn bản thấp (0.1) để đảm bảo tính bám sát tài liệu RAG & chống bịa. */
    private double temperature = 0.1;

    /** Giới hạn cửa sổ ngữ cảnh vừa đủ (4096 tokens) để tiết kiệm RAM trên máy 16GB CPU. */
    private int numCtx = 4096;

    /** Giới hạn số token sinh ra tối đa (480 tokens ~ 350 từ tiếng Việt) để tăng tốc CPU và tránh lan man. */
    private int numPredict = 480;

    /** Timeout đọc kết quả từ Ollama (ms) — chạy CPU + nạp model lần đầu cần khoảng 120-240s. */
    private int readTimeoutMs = 240000;
}
