package com.example.docmind.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Cấu hình Gemini. Để trống {@code gemini.api.keys} => chat dùng LLM stub.
 * Chỉ khi có ít nhất 1 key, GeminiLlmClient mới được kích hoạt.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "gemini")
public class GeminiProperties {

    /** Danh sách API key (round-robin sau này). Trống => stub. */
    private Api api = new Api();

    private String model = "gemini-2.5-flash";

    private String baseUrl = "https://generativelanguage.googleapis.com/v1beta";

    @Getter
    @Setter
    public static class Api {
        /** Spring tự tách chuỗi CSV "k1,k2" thành List. */
        private List<String> keys = List.of();
    }

    /** True khi có ít nhất 1 key không rỗng. */
    public boolean hasKey() {
        return api.getKeys() != null
                && api.getKeys().stream().anyMatch(k -> k != null && !k.isBlank());
    }
}
