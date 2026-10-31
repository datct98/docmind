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
        cleaned = normalizeDeclarativeQuestionMarks(cleaned);
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

    /**
     * Đổi dấu chấm hỏi '?' ở cuối câu trần thuật (ví dụ câu báo giá, mô tả công dụng kết thúc bằng 'ạ?') thành dấu chấm '.'.
     * Kiểm tra theo ranh giới khoảng trắng/dấu câu tiếng Việt (?iu)(?:^|[\s,;.!])(...) thay vì \b ASCII để tránh khớp nhầm chữ 'à' bên trong từ 'và'.
     */
    public static String normalizeDeclarativeQuestionMarks(String text) {
        if (text == null || !text.contains("?")) {
            return text;
        }
        List<String> sents = splitIntoSentencesPreservingPunctuation(text);
        boolean changed = false;
        for (int i = 0; i < sents.size(); i++) {
            String sItem = sents.get(i).strip();
            if (sItem.endsWith("?") && !isGenuineInterrogativeSentence(sItem)) {
                sents.set(i, sItem.replaceFirst("\\?+$", "."));
                changed = true;
            }
        }
        return changed ? String.join(" ", sents).strip() : text;
    }

    public static boolean isGenuineInterrogativeSentence(String sentence) {
        if (sentence == null) {
            return false;
        }
        String s = sentence.strip();
        if (!s.endsWith("?")) {
            return false;
        }
        boolean hasInterrogativeStructure = s.matches("(?iu).*(?:^|[\\s,;.!])(?:"
                + "phải\\s+không|đúng\\s+không|được\\s+không|hợp\\s+[^?]*\\s+(?:không|chưa)|"
                + "có\\s+[^?]*\\s+(?:không|chưa)|"
                + "(?:không\\s+biết|chưa\\s+biết)\\s+[^?]*\\s+hay\\s+[^?]+|"
                + "(?:đang\\s+(?:muốn|cần|quan\\s+tâm)|muốn\\s+(?:tìm\\s+hiểu|làm|chọn|trải\\s+nghiệm))\\s+[^?]+\\s+hay\\s+(?!bị\\b|đau\\b|mỏi\\b|nhức\\b)[^?]+|"
                + "không\\s+ạ|chưa\\s+ạ|sao\\s+ạ|vậy\\s+ạ|thế\\s+nào|như\\s+nào|nào|"
                + "mấy\\s+giờ|mấy\\s+buổi|mấy\\s+lần|mấy\\s+phút|bao\\s+lâu|bao\\s+nhiêu|khi\\s+nào|ở\\s+đâu|"
                + "là\\s+gì|là\\s+j|món\\s+gì|những\\s+gì|gì\\s+ạ|hả|nhỉ|sao"
                + ")(?:[\\s,;.!?]|$).*");
        if (hasInterrogativeStructure) {
            return true;
        }
        boolean hasChoiceHay = s.matches("(?iu).*(?:^|[\\s,;.!])hay\\s+(?!bị(?:[\\s,;.!?]|$)|đau(?:[\\s,;.!?]|$)|mỏi(?:[\\s,;.!?]|$)|nhức(?:[\\s,;.!?]|$)|tê(?:[\\s,;.!?]|$)|căng(?:[\\s,;.!?]|$)|ngồi(?:[\\s,;.!?]|$)|mất\\s+ngủ|khó\\s+ngủ|chuột\\s+rút)(?:là|mình|chị|anh|bạn|em|mới|đã|lâu|gần\\s+đây|sáng|chiều|tối|hôm\\s+nay|ngày\\s+mai|cuối\\s+tuần|khung\\s+giờ|buổi|gói|mẫu|khóa|khoá|lớp|sản\\s+phẩm|phương\\s+án|mục|loại|dòng|hạng\\s+mục|món|combo|dịch\\s+vụ|vùng|khu\\s+vực|một|hai|ba|\\d+)(?:[\\s,;.!?]|$).*");
        if (hasChoiceHay) {
            return true;
        }
        return s.matches("(?iu).*(?:^|[\\s,;.!])(?:không|chưa|nhé|nha|hả|nhỉ|sao|đâu|gì|nào|à|ư|hở|chứ|ha)(?:\\s+(?:ạ|nhé|nha|ha|em|mình|chị|anh|shop))*\\s*\\?+$");
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
