package com.docmind.guardrail;

import com.example.docmind.dto.bot.FanpageBotResponse.ReplyBubble;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-Loop Deterministic Guardrail Hook: Honorific Consistency.
 * <p>
 * Loại bỏ triệt để hiện tượng rò rỉ đại từ xưng hô (Pronoun Leakage).
 * Khi khách hàng được xác định là Nam ("Anh"), thay thế toàn bộ "Chị", "chị", "CHỊ" thành "Anh", "anh", "ANH",
 * giữ nguyên định dạng viết hoa, sửa lỗi đảo ngược đại từ ("Em có thể tham khảo" -> "Anh có thể tham khảo"),
 * và xóa bỏ hoàn toàn xưng hô hỗn hợp "Anh/Chị" cũng như "anh chị".
 */
public class HonorificConsistencyHook implements GuardrailHook {

    public static final String HOOK_CODE = "HOOK_HONORIFIC_CONSISTENCY";
    public static final String CODE = HOOK_CODE;

    // Hỗ trợ cả có slash ("anh/chị") lẫn không slash ("anh chị", "chị anh", "anh, chị")
    private static final Pattern ANH_CHI_PATTERN = Pattern.compile(
            "(?iU)(?<!\\p{L})(?:anh\\s*[/,]?\\s*chị|chị\\s*[/,]?\\s*anh)(?!\\p{L})"
    );
    private static final Pattern REPEATED_ANH_PATTERN = Pattern.compile("(?iU)(?<!\\p{L})anh\\s*,?\\s+anh(?!\\p{L})");
    private static final Pattern REPEATED_CHI_PATTERN = Pattern.compile("(?iU)(?<!\\p{L})chị\\s*,?\\s+chị(?!\\p{L})");

    // Thay thế variable-length lookbehind (?<=[.!?\n,;:\-]\s*) bằng capturing group forward O(N)
    private static final Pattern INVERTED_PRONOUN_PATTERN = Pattern.compile(
            "(?iU)(^|[.!?\\n,;:\\-]\\s*)em\\s+(có\\s+thể\\s+(?:tham\\s+khảo|lựa\\s+chọn|chọn|trải\\s+nghiệm|dùng|sử\\s+dụng|đăng\\s+ký|cân\\s+nhắc)|"
            + "nên\\s+(?:tham\\s+khảo|lựa\\s+chọn|chọn|dùng|trải\\s+nghiệm)|"
            + "sẽ\\s+(?:nhận\\s+được|được\\s+tặng|được\\s+giảm|được\\s+hưởng|được\\s+miễn\\s+phí))"
    );

    // Chỉ bắt chữ thường 't' hoặc đại từ xưng hô, KHÔNG dùng [a-z] case-insensitive để tránh nuốt chữ cái tên viết tắt như 'Anh K'
    private static final Pattern CONFUSED_ABBREVIATED_NAME_PATTERN = Pattern.compile(
            "(?<!\\p{L})((?i:anh|chị))\\s+(t|(?i:mình|mìn|minh|mk|mik|tui|tớ))(?=[,\\s.!?:;]|$)"
    );

    // Thay thế chính xác giữ nguyên capitalization trong 1 lượt duy nhất
    private static final Pattern CHI_REPLACEMENT_PATTERN = Pattern.compile("(?iU)(?<!\\p{L})chị(?!\\p{L})");

    // Thay thế variable-length lookbehind bằng capturing group forward để triệt tiêu ReDoS
    private static final Pattern BAN1_PATTERN = Pattern.compile(
            "(?iU)\\b(chào|của|cho|với|hỗ\\s+trợ|tư\\s+vấn|mời|giúp|gửi|hẹn)\\s+(bạn)(?!\\p{L})"
    );
    private static final Pattern BAN2_PATTERN = Pattern.compile(
            "(?iU)(?<!\\p{L})(bạn)(?=\\s+(?:có\\s+thể|muốn|cần|đang|sẽ|nên|ơi|nhé|nha|ạ|vui\\s+lòng|hãy))"
    );

    private static String replaceBan(String banToken, boolean isMale) {
        if ("BẠN".equals(banToken)) {
            return isMale ? "ANH" : "CHỊ";
        } else if ("Bạn".equalsIgnoreCase(banToken) && Character.isUpperCase(banToken.charAt(0))) {
            return isMale ? "Anh" : "Chị";
        } else {
            return isMale ? "anh" : "chị";
        }
    }

    @Override
    public String getHookCode() {
        return HOOK_CODE;
    }

    @Override
    public boolean apply(GuardrailContext context, GuardrailDraft draft) {
        String originalText = draft.getText();
        if (originalText == null || originalText.isBlank()) {
            return false;
        }

        String honorific = context.getCustomerHonorific();
        boolean isMale = context.isMale();
        String customerName = context.getCustomerName();
        String updated = repairHonorific(originalText, honorific, isMale, customerName);

        boolean changed = !updated.equals(originalText);

        // Đồng bộ các bong bóng nếu có
        List<ReplyBubble> originalBubbles = draft.getBubbles();
        if (originalBubbles != null && !originalBubbles.isEmpty()) {
            List<ReplyBubble> updatedBubbles = new ArrayList<>(originalBubbles.size());
            for (ReplyBubble b : originalBubbles) {
                String fixedBubbleText = repairHonorific(b.text(), honorific, isMale, customerName);
                if (!fixedBubbleText.equals(b.text())) {
                    changed = true;
                }
                updatedBubbles.add(new ReplyBubble(fixedBubbleText, b.typingDelayMs()));
            }
            draft.setBubbles(updatedBubbles);
        }

        if (changed) {
            draft.setText(updated);
            draft.addTriggeredHook(HOOK_CODE);
            return true;
        }

        return false;
    }

    private String repairHonorific(String text, String honorific, boolean isMale, String customerName) {
        if (text == null || text.isBlank()) {
            return "";
        }

        // 0. Chuẩn hóa Unicode NFC để triệt tiêu hiện tượng rò rỉ đại từ do NFD decomposed accents
        String working = Normalizer.isNormalized(text, Normalizer.Form.NFC)
                ? text
                : Normalizer.normalize(text, Normalizer.Form.NFC);

        String lower = working.toLowerCase(Locale.ROOT);

        // 1. Sửa lỗi đảo ngược đại từ ("Em có thể tham khảo..." -> "Anh/Chị có thể tham khảo...")
        if (lower.contains("em có thể") || lower.contains("em nên") || lower.contains("em sẽ")) {
            working = INVERTED_PRONOUN_PATTERN.matcher(working).replaceAll("$1" + honorific + " $2");
        }

        // 2. Xóa lỗi nhầm đại từ viết tắt đầu câu của khách thành tên riêng ("Dạ Chị t,", "Dạ Anh Mìn,")
        // Bảo vệ tên viết tắt hợp lệ (ví dụ: "Anh K") và tôn trọng context.getCustomerName()
        boolean hasPossibleAbbrev = lower.contains(" t") || lower.contains("mình") || lower.contains("mìn")
                || lower.contains("minh") || lower.contains("mk") || lower.contains("mik")
                || lower.contains("tui") || lower.contains("tớ");
        if (hasPossibleAbbrev && (lower.contains("anh") || lower.contains("chị"))) {
            if (customerName != null && !customerName.isBlank()) {
                Matcher m = CONFUSED_ABBREVIATED_NAME_PATTERN.matcher(working);
                StringBuilder sb = new StringBuilder();
                while (m.find()) {
                    String abbrev = m.group(2);
                    if (abbrev != null && abbrev.equalsIgnoreCase(customerName.trim())) {
                        m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                    } else {
                        m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1)));
                    }
                }
                m.appendTail(sb);
                working = sb.toString();
            } else {
                working = CONFUSED_ABBREVIATED_NAME_PATTERN.matcher(working).replaceAll("$1");
            }
        }

        if (isMale) {
            if (lower.contains("chị") || lower.contains("anh") || lower.contains("bạn")) {
                // Chuẩn hóa Anh/Chị và anh chị (không slash) -> Anh
                if (lower.contains("chị") && lower.contains("anh")) {
                    working = ANH_CHI_PATTERN.matcher(working).replaceAll("Anh");
                }

                // Thay thế chính xác giữ nguyên capitalization trong 1 lượt duy nhất
                if (lower.contains("chị")) {
                    working = CHI_REPLACEMENT_PATTERN.matcher(working).replaceAll(mr -> {
                        String match = mr.group();
                        if ("CHỊ".equals(match)) {
                            return "ANH";
                        } else if ("Chị".equals(match)) {
                            return "Anh";
                        } else {
                            return "anh";
                        }
                    });
                }

                // Chuẩn hóa đại từ thân mật "bạn" / "Bạn" -> "anh" / "Anh" cho khách nam
                if (lower.contains("bạn")) {
                    working = BAN1_PATTERN.matcher(working).replaceAll(mr -> mr.group(1) + " " + replaceBan(mr.group(2), true));
                    working = BAN2_PATTERN.matcher(working).replaceAll(mr -> replaceBan(mr.group(1), true));
                }

                // Dọn dẹp triệt để hiện tượng vấp lặp từ ("anh anh" -> "Anh") SAU KHI đã thay thế mọi 'chị' và 'bạn'
                if (working.toLowerCase(Locale.ROOT).contains("anh")) {
                    working = REPEATED_ANH_PATTERN.matcher(working).replaceAll("Anh");
                }
            }
        } else {
            // Nữ giới ("Chị")
            if (lower.contains("chị") || lower.contains("anh") || lower.contains("bạn")) {
                // Chuẩn hóa Anh/Chị và anh chị (không slash) -> Chị
                working = ANH_CHI_PATTERN.matcher(working).replaceAll("Chị");

                // Chuẩn hóa đại từ thân mật "bạn" / "Bạn" -> "chị" / "Chị" cho khách nữ
                if (lower.contains("bạn")) {
                    working = BAN1_PATTERN.matcher(working).replaceAll(mr -> mr.group(1) + " " + replaceBan(mr.group(2), false));
                    working = BAN2_PATTERN.matcher(working).replaceAll(mr -> replaceBan(mr.group(1), false));
                }

                // Dọn dẹp hiện tượng lặp từ ("chị chị" -> "Chị") nếu có
                working = REPEATED_CHI_PATTERN.matcher(working).replaceAll("Chị");
            }
        }

        return working;
    }
}
