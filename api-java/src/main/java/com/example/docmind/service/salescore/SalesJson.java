package com.example.docmind.service.salescore;

/** Normalize transport formatting only. Never fish a plausible object out of prose/reasoning. */
final class SalesJson {
    private SalesJson() {}
    static String objectText(String raw, int maxChars) {
        if (raw == null || raw.length() > maxChars) throw new IllegalArgumentException("Invalid JSON response size");
        String text = raw.strip();
        if (text.matches("(?s)^```(?:json)?\\s*\\R.*```$")) {
            text = text.substring(text.indexOf('\n') + 1, text.length() - 3).strip();
        }
        if (!text.startsWith("{") || !text.endsWith("}")) throw new IllegalArgumentException("Expected JSON object only");
        return text;
    }
}
