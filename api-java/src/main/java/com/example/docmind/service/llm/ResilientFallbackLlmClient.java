package com.example.docmind.service.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

/**
 * Decorator bọc hai {@link LlmClient} (Primary + Secondary Fallback) để đảm bảo High Availability (HA):
 * Khi nhà cung cấp chính (VD: Ollama local bị quá tải/offline hoặc Cloud API bị rate-limit 429 / 5xx / timeout)
 * trả về chuỗi lỗi {@code [*-offline]} / {@code [*-error]} hoặc ném ngoại lệ, tự động chuyển hướng tức thì
 * sang nhà cung cấp dự phòng (VD: Gemini Flash / DeepSeek / OpenAI) mà không làm gián đoạn hội thoại của khách.
 */
public class ResilientFallbackLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(ResilientFallbackLlmClient.class);

    private static final Pattern ERROR_OR_OFFLINE_MARKER = Pattern.compile(
            "(?s)^\\[[a-z0-9_\\-]+-(?:error|offline|unavailable)\\].*"
    );

    private final LlmClient primary;
    private final LlmClient fallback;
    private volatile LlmClient lastUsedClient;

    public ResilientFallbackLlmClient(LlmClient primary, LlmClient fallback) {
        if (primary == null) {
            throw new IllegalArgumentException("Primary LlmClient must not be null");
        }
        this.primary = primary;
        this.fallback = fallback;
        this.lastUsedClient = primary;
    }

    public LlmClient getPrimary() {
        return primary;
    }

    public LlmClient getFallback() {
        return fallback;
    }

    public LlmClient getLastUsedClient() {
        return lastUsedClient != null ? lastUsedClient : primary;
    }

    @Override
    public String complete(String prompt) {
        return complete(prompt, null);
    }

    @Override
    public String complete(String prompt, ReasoningBudget budget) {
        String primaryResult = null;
        Exception primaryException = null;
        try {
            primaryResult = budget != null
                    ? primary.complete(prompt, budget)
                    : primary.complete(prompt);
        } catch (Exception e) {
            primaryException = e;
        }

        if (!isFailureResult(primaryResult, primaryException)) {
            this.lastUsedClient = primary;
            return primaryResult;
        }

        // A caller canceled/deadlined this work. Do not start a second network request after cancellation.
        if (Thread.currentThread().isInterrupted()) return "[llm-unavailable] Request canceled.";
        if (fallback == null) {
            this.lastUsedClient = primary;
            if (primaryException != null) {
                log.warn("[LLM-Fallback] Primary engine={} threw exception and no fallback configured: {}",
                        primary.name(), primaryException.getMessage());
                return "[llm-error] " + primaryException.getMessage();
            }
            return primaryResult;
        }

        String reason = primaryException != null
                ? "exception=" + primaryException.getClass().getSimpleName() + ": " + primaryException.getMessage()
                : "marker=" + summarizeMarker(primaryResult);
        log.warn("[LLM-Fallback] Primary engine={} unavailable ({}) -> auto-switching to fallback engine={}",
                primary.name(), reason, fallback.name());

        try {
            String fallbackResult = budget != null
                    ? fallback.complete(prompt, budget)
                    : fallback.complete(prompt);
            if (fallbackResult != null && !fallbackResult.isBlank() && !isFailureResult(fallbackResult, null)) {
                this.lastUsedClient = fallback;
                return fallbackResult;
            }
        } catch (Exception fallbackEx) {
            log.error("[LLM-Fallback] Fallback engine={} also failed: {}",
                    fallback.name(), fallbackEx.getMessage());
        }

        this.lastUsedClient = primary;
        return primaryResult != null ? primaryResult : "[llm-unavailable] Both primary and fallback LLM failed.";
    }

    public static boolean isFailureResult(String result, Exception exception) {
        if (exception != null) {
            return true;
        }
        if (result == null || result.isBlank()) {
            return true;
        }
        return ERROR_OR_OFFLINE_MARKER.matcher(result.strip()).matches();
    }

    private static String summarizeMarker(String result) {
        if (result == null) {
            return "null";
        }
        String trimmed = result.strip();
        int endBracket = trimmed.indexOf(']');
        if (endBracket > 0 && endBracket <= 40) {
            return trimmed.substring(0, endBracket + 1);
        }
        return trimmed.length() > 40 ? trimmed.substring(0, 40) + "..." : trimmed;
    }

    @Override
    public String name() {
        if (fallback == null) {
            return primary.name();
        }
        return primary.name() + "+fallback(" + fallback.name() + ")";
    }
}
