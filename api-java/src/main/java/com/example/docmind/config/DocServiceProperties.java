package com.example.docmind.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Cấu hình client tới doc-service-py (parser Python). */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "docmind.doc-service")
public class DocServiceProperties {

    /** Base URL của parser, mặc định http://localhost:8091. */
    private String baseUrl = "http://localhost:8091";

    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 60000;

    /** Chu kỳ poll job (ms) khi chờ parse. */
    private long pollIntervalMs = 1500;

    /** Số lần poll tối đa trước khi coi là timeout. */
    private int pollMaxAttempts = 40;

    /** Shared HMAC secret token cho giao tiếp nội bộ với FastAPI LangGraph Engine. */
    private String internalToken = resolveToken();

    private static String resolveToken() {
        String token = System.getenv("DOCMIND_INTERNAL_TOKEN");
        if (token == null || token.isBlank()) {
            token = System.getenv("INTERNAL_API_TOKEN");
        }
        if (token == null || token.isBlank()) {
            token = System.getenv("DOC_SERVICE_INTERNAL_TOKEN");
        }
        if (token == null || token.isBlank()) {
            token = "docmind-internal-secret-token-2026";
        }
        return token;
    }
}
