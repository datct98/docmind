package com.example.docmind.config;

import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Cấu hình chung cho đa nhà cung cấp LLM (Unified Multi-Provider LLM Configuration - R4).
 * Hỗ trợ chuyển đổi linh hoạt qua biến môi trường (.env / application.properties) giữa:
 * ollama, deepseek, grok, openai, gemini, openrouter, custom, stub.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    private static final Logger log = LoggerFactory.getLogger(LlmProperties.class);

    /**
     * Tên provider: ollama | deepseek | grok | openai | gemini | openrouter | custom | stub.
     * Mặc định là 'ollama'.
     */
    private String provider = "ollama";

    /**
     * URL máy chủ LLM (tùy chọn; tự động phân giải theo preset nếu để trống).
     */
    private String baseUrl;

    /**
     * API Key để xác thực Bearer token (đối với DeepSeek, Grok, OpenAI, Gemini, OpenRouter, v.v.).
     */
    private String apiKey;

    /**
     * Tên model sử dụng (tùy chọn; tự động phân giải theo preset nếu để trống).
     */
    private String model;

    /**
     * Nhiệt độ sinh văn bản (mặc định 0.1 để chống bịa và bám sát ngữ cảnh tài liệu RAG).
     */
    private double temperature = 0.1;

    /**
     * Thời gian timeout đọc kết quả tính bằng giây (mặc định 120 giây).
     */
    private int timeoutSeconds = 120;

    /**
     * Mức độ suy luận nội bộ (reasoning_effort) cho các mô hình reasoning (vd: grok-4.x, o1/o3/o4).
     * Giá trị: low | minimal | medium | high | off.
     * Nếu để trống, tự động dùng 'low' cho dòng grok-4.x / grok-3-mini / o1 / o3 / o4 để giảm độ trễ.
     */
    private String reasoningEffort;

    /**
     * Giới hạn số token sinh ra tối đa (tùy chọn; <= 0 nghĩa là không giới hạn cứng ở payload).
     */
    private int maxTokens = 0;

    /**
     * Phân giải tham số reasoning_effort tối ưu cho model hiện tại.
     */
    public String resolveReasoningEffort() {
        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            String trimmed = reasoningEffort.trim().toLowerCase();
            if ("off".equals(trimmed) || "false".equals(trimmed) || "disabled".equals(trimmed)) {
                return null;
            }
            return trimmed;
        }
        String resolvedModel = resolveModel().toLowerCase();
        if (resolvedModel.startsWith("grok-4")
                || resolvedModel.startsWith("grok-3-mini")
                || resolvedModel.startsWith("o1")
                || resolvedModel.startsWith("o3")
                || resolvedModel.startsWith("o4")) {
            return "low";
        }
        return null;
    }

    /**
     * Chuẩn hóa tên provider về dạng chữ thường, cắt khoảng trắng (mặc định 'ollama').
     * Hỗ trợ bí danh 'xai' / 'x.ai' -> 'grok'.
     */
    public String getNormalizedProvider() {
        if (provider == null || provider.isBlank()) {
            return "ollama";
        }
        String norm = provider.trim().toLowerCase();
        if ("xai".equals(norm) || "x.ai".equals(norm)) {
            return "grok";
        }
        return norm;
    }

    /**
     * Kiểm tra xem provider hiện tại có phải là nhà cung cấp đám mây (cloud preset) không.
     */
    public boolean isCloudProvider() {
        return switch (getNormalizedProvider()) {
            case "deepseek", "grok", "openai", "gemini", "openrouter" -> true;
            default -> false;
        };
    }

    /**
     * Tự động phân giải Base URL theo preset nếu người dùng chưa chỉ định rõ ràng,
     * hoặc nếu người dùng đổi sang cloud provider (grok, deepseek, openai, gemini, openrouter)
     * nhưng vẫn để sót URL của Ollama local (:11434 / host.docker.internal / localhost).
     */
    public String resolveBaseUrl() {
        String normProvider = getNormalizedProvider();
        String defaultUrl = switch (normProvider) {
            case "deepseek" -> "https://api.deepseek.com/v1";
            case "grok" -> "https://api.x.ai/v1";
            case "openai" -> "https://api.openai.com/v1";
            case "gemini" -> "https://generativelanguage.googleapis.com/v1beta/openai";
            case "openrouter" -> "https://openrouter.ai/api/v1";
            case "ollama", "stub" -> "http://localhost:11434";
            default -> "http://localhost:11434";
        };
        if (baseUrl != null && !baseUrl.isBlank()) {
            String trimmed = baseUrl.trim();
            if (isCloudProvider() && isOllamaLocalUrl(trimmed)) {
                log.warn("[LLM] Phớt lờ baseUrl của Ollama local ('{}') khi provider='{}' -> tự động dùng '{}'",
                        trimmed, normProvider, defaultUrl);
                return defaultUrl;
            }
            return trimmed;
        }
        return defaultUrl;
    }

    /**
     * Tự động phân giải Model name theo preset nếu người dùng chưa chỉ định rõ ràng,
     * hoặc nếu người dùng đổi sang cloud provider nhưng vẫn để sót model Ollama mặc định (qwen2.5:7b).
     */
    public String resolveModel() {
        String normProvider = getNormalizedProvider();
        String defaultModel = switch (normProvider) {
            case "deepseek" -> "deepseek-chat";
            case "grok" -> "grok-2-latest";
            case "openai" -> "gpt-4o-mini";
            case "gemini" -> "gemini-2.5-flash";
            case "openrouter" -> "openai/gpt-4o-mini";
            case "ollama" -> "qwen2.5:7b";
            case "stub" -> "stub-model";
            default -> "qwen2.5:7b";
        };
        if (model != null && !model.isBlank()) {
            String trimmed = model.trim();
            if (isCloudProvider() && isDefaultOllamaModel(trimmed)) {
                log.warn("[LLM] Phớt lờ model của Ollama local ('{}') khi provider='{}' -> tự động dùng '{}'",
                        trimmed, normProvider, defaultModel);
                return defaultModel;
            }
            return trimmed;
        }
        return defaultModel;
    }

    private static boolean isOllamaLocalUrl(String url) {
        String lower = url.toLowerCase();
        return lower.contains(":11434")
                || lower.contains("host.docker.internal")
                || lower.startsWith("http://localhost")
                || lower.startsWith("http://127.0.0.1")
                || lower.startsWith("https://localhost")
                || lower.startsWith("https://127.0.0.1");
    }

    private static boolean isDefaultOllamaModel(String modelName) {
        String lower = modelName.toLowerCase();
        return lower.equals("qwen2.5:7b")
                || lower.startsWith("qwen")
                || lower.startsWith("llama")
                || lower.startsWith("mistral:")
                || lower.startsWith("gemma")
                || lower.startsWith("phi3:")
                || lower.startsWith("phi4:");
    }
}
