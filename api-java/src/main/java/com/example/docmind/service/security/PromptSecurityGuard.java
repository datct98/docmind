package com.example.docmind.service.security;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Lớp bảo vệ đa tầng chống Prompt Injection (Direct Injection, Indirect Document Injection,
 * Chat-Template Special Token Spoofing, và Meta-Learning Playbook Poisoning).
 *
 * <p>Đặc biệt quan trọng khi sử dụng các mô hình Local LLM (7B/3B trên Ollama) vốn nhạy cảm
 * với các token điều khiển như {@code <|im_start|>}, {@code [INST]}, {@code <<SYS>>} hoặc
 * các lệnh ghi đè vai trò.
 */
public final class PromptSecurityGuard {

    private PromptSecurityGuard() {
    }

    /** Các mẫu câu tấn công trực tiếp nhằm vượt rào (Jailbreak) hoặc đánh cắp System Prompt. */
    private static final List<Pattern> DIRECT_JAILBREAK_PATTERNS = List.of(
            Pattern.compile("(?iU)\\b(ignore|disregard|forget)\\s+(all\\s+)?(previous|above|prior|system)\\s+(instructions?|prompts?|rules?)\\b"),
            Pattern.compile("(?iU)\\b(reveal|print|show|output|repeat|dump)\\s+(your\\s+|the\\s+)?(system\\s+prompt|hidden\\s+instructions?|initial\\s+prompt)\\b"),
            Pattern.compile("(?iU)(bỏ\\s+qua|quên\\s+hết|phớt\\s+lờ|xóa\\s+bỏ)\\s+(mọi\\s+|tất\\s+cả\\s+|các\\s+)?(hướng\\s+dẫn|chỉ\\s+thị|quy\\s+tắc|luật)\\s+(trên|trước|hệ\\s+thống|ban\\s+đầu)"),
            Pattern.compile("(?iU)(tiết\\s+lộ|in\\s+ra|hiển\\s+thị|cho\\s+tôi\\s+xem|đọc\\s+lại)\\s+(toàn\\s+bộ\\s+)?(system\\s+prompt|chỉ\\s+thị\\s+hệ\\s+thống|prompt\\s+gốc|câu\\s+lệnh\\s+hệ\\s+thống)"),
            Pattern.compile("(?iU)(system\\s+override|developer\\s+mode|dan\\s+mode|jailbreak\\s+mode)"),
            Pattern.compile("(?iU)(hãy\\s+đóng\\s+vai|giả\\s+vờ\\s+bạn\\s+là|bạn\\s+bây\\s+giờ\\s+là)\\s+(giám\\s+đốc|chủ\\s+shop|admin|hệ\\s+thống).*?(duyệt|tặng|giảm\\s+giá\\s+100%|miễn\\s+phí|0\\s*đ)"),
            Pattern.compile("(?iU)<\\|im_start\\|>\\s*system"),
            Pattern.compile("(?iU)<<SYS>>")
    );

    /**
     * Các mẫu tấn công đầu độc Bộ nhớ Kinh nghiệm AI (Playbook Poisoning) khi người dùng
     * lợi dụng tính năng "Báo sai & Dạy AI" để tiêm quy tắc phá hoại.
     */
    private static final List<Pattern> PLAYBOOK_POISON_PATTERNS = List.of(
            Pattern.compile("(?iU)(bỏ\\s+qua|không\\s+cần\\s+đọc|không\\s+dựa\\s+vào)\\s+(mọi\\s+|tất\\s+cả\\s+)?(tài\\s+liệu|ngữ\\s+cảnh|dữ\\s+liệu)"),
            Pattern.compile("(?iU)(luôn\\s+luôn\\s+|từ\\s+giờ\\s+)?(báo\\s+giá\\s+0\\s*đ|miễn\\s+phí\\s+100%|giảm\\s+giá\\s+100%)\\s+(cho\\s+mọi|tất\\s+cả)"),
            Pattern.compile("(?iU)(tiết\\s+lộ|in\\s+ra|hiển\\s+thị)\\s+(system\\s+prompt|chỉ\\s+thị\\s+hệ\\s+thống|mật\\s+khẩu|api\\s+key)"),
            Pattern.compile("(?iU)\\b(ignore|bypass)\\s+(all\\s+)?(documents?|context|security|citations?)\\b")
    );

    public static final String SAFE_JAILBREAK_REFUSAL =
            "Yêu cầu của bạn chứa chỉ thị can thiệp vào cấu hình bảo mật hoặc vượt ngoài phạm vi tra cứu tài liệu. "
            + "Tôi chỉ có thể hỗ trợ trả lời các câu hỏi nghiệp vụ dựa trên nội dung tài liệu đã cung cấp.";

    /**
     * Kiểm tra câu hỏi của người dùng có phải là nỗ lực tấn công trực tiếp (Direct Prompt Injection / Jailbreak) hay không.
     */
    public static boolean isDirectJailbreakAttempt(String userQuestion) {
        if (userQuestion == null || userQuestion.isBlank()) {
            return false;
        }
        for (Pattern p : DIRECT_JAILBREAK_PATTERNS) {
            if (p.matcher(userQuestion).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Kiểm tra phản hồi dạy AI hoặc quy tắc rút ra có chứa lệnh đầu độc Playbook hay không.
     */
    public static boolean isPoisonedRuleAttempt(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (isDirectJailbreakAttempt(text)) {
            return true;
        }
        for (Pattern p : PLAYBOOK_POISON_PATTERNS) {
            if (p.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Làm sạch toàn bộ chuỗi văn bản không tin cậy (chunk tài liệu, tiêu đề, quy tắc, câu hỏi):
     * - Vô hiệu hóa thẻ đóng/mở vùng dữ liệu (<DOCUMENT_CONTEXT>, <USER_QUESTION>, <CONVERSATION_HISTORY>, <CUSTOMER_MESSAGE>).
     * - Vô hiệu hóa các token điều khiển đặc biệt của các dòng model Qwen / Llama / Mistral / Gemma.
     * - Vô hiệu hóa việc giả mạo tiêu đề section hệ thống và thẻ [[HANDOFF:...]].
     */
    public static String sanitizeUntrustedContent(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("<DOCUMENT_CONTEXT>", "[DOCUMENT_CONTEXT]")
                .replace("</DOCUMENT_CONTEXT>", "[/DOCUMENT_CONTEXT]")
                .replace("<USER_QUESTION>", "[USER_QUESTION]")
                .replace("</USER_QUESTION>", "[/USER_QUESTION]")
                .replace("<CONVERSATION_HISTORY>", "[CONVERSATION_HISTORY]")
                .replace("</CONVERSATION_HISTORY>", "[/CONVERSATION_HISTORY]")
                .replace("<CUSTOMER_MESSAGE>", "[CUSTOMER_MESSAGE]")
                .replace("</CUSTOMER_MESSAGE>", "[/CUSTOMER_MESSAGE]")
                .replace("[[HANDOFF:", "[HANDOFF:")
                .replace("<|im_start|>", "[im_start]")
                .replace("<|im_end|>", "[im_end]")
                .replace("<|endoftext|>", "[endoftext]")
                .replace("<|system|>", "[system]")
                .replace("<|user|>", "[user]")
                .replace("<|assistant|>", "[assistant]")
                .replace("[INST]", "(INST)")
                .replace("[/INST]", "(/INST)")
                .replace("<<SYS>>", "(SYS)")
                .replace("<</SYS>>", "(/SYS)")
                .replace("# VAI TRÒ", "VAI TRÒ")
                .replace("# QUY TẮC", "QUY TẮC")
                .replace("# HỒ SƠ NHÂN CÁCH", "HỒ SƠ NHÂN CÁCH")
                .replace("# NHỊP ĐỘ TƯ VẤN", "NHỊP ĐỘ TƯ VẤN")
                .replace("# BỘ QUY TẮC RÚT KINH NGHIỆM (AI PLAYBOOK)", "AI PLAYBOOK")
                .replace("# LƯU Ý BẢO MẬT CUỐI CÙNG", "LƯU Ý BẢO MẬT");
    }

    /**
     * Kiểm tra và lọc đầu ra của LLM để đảm bảo LLM không bị lừa in ngược lại System Prompt nội bộ.
     */
    public static String sanitizeModelOutput(String answer) {
        if (answer == null || answer.isBlank()) {
            return "";
        }
        // Nếu phản hồi của LLM rò rỉ nguyên mẫu cấu trúc chỉ thị nội bộ
        if (answer.contains("<DOCUMENT_CONTEXT>")
                || answer.contains("<CONVERSATION_HISTORY>")
                || (answer.contains("# VAI TRÒ") && answer.contains("# QUY TẮC") && answer.contains("CHỐNG TIÊM LỆNH"))) {
            return SAFE_JAILBREAK_REFUSAL;
        }
        return answer;
    }
}

