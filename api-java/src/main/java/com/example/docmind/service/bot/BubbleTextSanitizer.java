package com.example.docmind.service.bot;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Pure text-hygiene utilities shared by the ACI V2 cognitive pipeline
 * ({@code CleanAgent*} + {@code FanpageBotService}) without any dependency on
 * legacy V1 {@code com.docmind.guardrail.*}.
 */
public final class BubbleTextSanitizer {

    private BubbleTextSanitizer() {
    }

    public static final Pattern PRICE_PATTERN = Pattern.compile(
            "(?iU)(?<![\\d.,])(?:\\d{1,3}(?:\\.\\d{3})+|\\d{2,4})\\s*(?:k\\b|đ\\b|vnđ\\b|vnd\\b)|(?<![\\d.,])\\d{1,3}(?:[.,]\\d{1,2})?\\s*(?:triệu|tr)\\b"
    );

    public static String sanitizeBubbleText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String cleaned = text
                .replaceAll("\\[\\[([^\\]|]+)(?:\\|([^\\]]+))?\\]\\]", "$1")
                .replaceAll("\\[\\d+\\]", "")
                .replaceAll("\\*\\*([^*]+)\\*\\*", "$1")
                .replaceAll("(?m)^\\s*#{1,6}\\s+", "")
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .strip();
        // SBR-01 FIX-3: không còn tự đổi '?' -> '.' (normalizeDeclarativeQuestionMarks); dấu câu của LLM được bảo toàn.
        return capitalizeFirstLetter(cleaned);
    }

    public static String capitalizeFirstLetter(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String trimmed = text.strip();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isLetter(c)) {
                if (Character.isLowerCase(c)) {
                    return trimmed.substring(0, i) + Character.toUpperCase(c) + trimmed.substring(i + 1);
                }
                return trimmed;
            }
        }
        return trimmed;
    }

    public static List<String> splitIntoSentencesPreservingPunctuation(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String[] parts = text.strip().split("(?<!\\d)(?<=[.!?])\\s+");
        List<String> sentences = new ArrayList<>(parts.length);
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                String trimmed = p.strip();
                if (!trimmed.matches("(?s).*[\\p{L}\\d].*") && !sentences.isEmpty()) {
                    int lastIdx = sentences.size() - 1;
                    sentences.set(lastIdx, (sentences.get(lastIdx) + " " + trimmed).strip());
                } else {
                    sentences.add(trimmed);
                }
            }
        }
        return sentences;
    }
}
