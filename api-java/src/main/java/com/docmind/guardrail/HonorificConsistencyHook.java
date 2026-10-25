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

    // Hỗ trợ cả có slash ("anh/chị") lẫn không slash ("anh chị", "chị anh", "anh, chị"), trừ cụm số nhiều "nhiều anh/chị"
    private static final Pattern ANH_CHI_PATTERN = Pattern.compile(
            "(?iU)(?<!nhiều\\s)(?<!\\p{L})(?:anh\\s*[/,]?\\s*chị|chị\\s*[/,]?\\s*anh)(?!\\p{L})"
    );
    private static final Pattern REPEATED_ANH_PATTERN = Pattern.compile("(?iU)(?<!\\p{L})anh\\s*,?\\s+anh(?!\\p{L})");
    private static final Pattern REPEATED_CHI_PATTERN = Pattern.compile("(?iU)(?<!\\p{L})chị\\s*,?\\s+chị(?!\\p{L})");

    // Thay thế variable-length lookbehind (?<=[.!?\n,;:\-]\s*) bằng capturing group forward O(N)
    private static final Pattern INVERTED_PRONOUN_PATTERN = Pattern.compile(
            "(?iU)(^|[.!?\\n,;:\\-]\\s*)em\\s+(có\\s+thể\\s+(?:tham\\s+khảo|lựa\\s+chọn|chọn|trải\\s+nghiệm|dùng|sử\\s+dụng|đăng\\s+ký|cân\\s+nhắc)|"
            + "nên\\s+(?:tham\\s+khảo|lựa\\s+chọn|chọn|dùng|trải\\s+nghiệm)|"
            + "sẽ\\s+(?:nhận\\s+được|được\\s+tặng|được\\s+giảm|được\\s+hưởng|được\\s+miễn\\s+phí)|"
            + "đang\\s+(?:quan\\s+tâm|tìm\\s+hiểu|tìm|bị|gặp|mỏi|đau|nhức|cần|muốn|lăn\\s+tăn|phân\\s+vân)|"
            + "muốn\\s+(?:tìm\\s+hiểu|tham\\s+khảo|trải\\s+nghiệm|làm|chọn|đặt\\s+lịch|thư\\s+giãn|chăm\\s+sóc|ghé)|"
            + "cần\\s+(?:tư\\s+vấn|chăm\\s+sóc|thư\\s+giãn|giải\\s+tỏa))"
    );

    // Chỉ bắt chữ thường 't' hoặc đại từ xưng hô, KHÔNG dùng [a-z] case-insensitive để tránh nuốt chữ cái tên viết tắt như 'Anh K'
    private static final Pattern CONFUSED_ABBREVIATED_NAME_PATTERN = Pattern.compile(
            "(?<!\\p{L})((?i:anh|chị))\\s+(t|(?i:mình|mìn|minh|mk|mik|tui|tớ))(?=[,\\s.!?:;]|$)"
    );

    // Thay thế chính xác giữ nguyên capitalization trong 1 lượt duy nhất (bảo vệ cụm "nhiều anh/chị")
    private static final Pattern CHI_REPLACEMENT_PATTERN = Pattern.compile("(?iU)(?<!nhiều\\sanh/)(?<!\\p{L})chị(?!\\p{L})");

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

    private static final Pattern INVERTED_APOLOGY_PATTERN = Pattern.compile(
            "(?iU)\\b(dạ\\s+)?xin\\s+lỗi\\s+em\\s+vì\\b"
    );
    private static final Pattern INVERTED_CONSULTANT_ASK_PATTERN = Pattern.compile(
            "(?iU)(^|[.!?\\n,;:\\-]\\s*)(?:mình|anh|chị)\\s+có\\s+thể\\s+hỏi\\s+(?:thêm\\s+)?một\\s+chút\\s+để\\s+em\\s+tư\\s+vấn"
    );

    public static boolean matchesInvertedCustomerPronoun(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return INVERTED_PRONOUN_PATTERN.matcher(text).find()
                || INVERTED_APOLOGY_PATTERN.matcher(text).find()
                || INVERTED_CONSULTANT_ASK_PATTERN.matcher(text).find();
    }

    public static String repairInvertedCustomerPronoun(String text, String honorific) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String target = (honorific != null && !honorific.isBlank()) ? honorific.strip() : "mình";
        String cap = Character.toUpperCase(target.charAt(0)) + target.substring(1);
        String working = INVERTED_PRONOUN_PATTERN.matcher(text)
                .replaceAll(mr -> mr.group(1) + cap + " " + mr.group(2));
        working = INVERTED_APOLOGY_PATTERN.matcher(working)
                .replaceAll(mr -> (mr.group(1) != null ? mr.group(1) : "") + "em xin lỗi " + target + " vì");
        working = INVERTED_CONSULTANT_ASK_PATTERN.matcher(working)
                .replaceAll(mr -> mr.group(1) + "Em có thể hỏi " + target + " một chút để tư vấn");
        return working;
    }

    /**
     * v3.3.4 GAP-02: Sửa đảo ngược đại từ có NGỮ CẢNH, chống "đóng băng" sai câu đúng vai.
     * <p>
     * {@link #repairInvertedCustomerPronoun} chỉ nhìn cụm "em + động từ" nên có thể đảo nhầm câu Bot đang tự nói
     * về hành động của mình ("Em đang tìm khung giờ trống cho mình" -> "Mình đang tìm khung giờ trống cho mình").
     * Quy tắc tổng quát ở đây: trong CÙNG một mệnh đề, nếu sau cụm động từ khách hàng xuất hiện ở vai
     * tân ngữ ("cho/giúp/với/hỗ trợ/tư vấn/chăm sóc + đại từ khách"), thì chủ ngữ "em" chính là Bot -> giữ nguyên.
     * Các trường hợp còn lại ủy quyền cho {@link #repairInvertedCustomerPronoun}.
     */
    public static String repairIfReversed(String text, String customerHonorific) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String target = (customerHonorific != null && !customerHonorific.isBlank()) ? customerHonorific.strip() : "mình";
        String cap = Character.toUpperCase(target.charAt(0)) + target.substring(1);
        Pattern customerAsObject = Pattern.compile(
                "(?iU)\\b(?:cho|giúp|với|tới|đến|hỗ\\s+trợ|tư\\s+vấn|chăm\\s+sóc|gửi)\\s+(?:"
                        + Pattern.quote(target) + "|anh|chị|mình|bạn)(?!\\p{L})"
        );
        Matcher m = INVERTED_PRONOUN_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        boolean anyChange = false;
        while (m.find()) {
            int clauseEnd = text.length();
            for (int i = m.end(); i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '.' || c == '!' || c == '?' || c == '\n') {
                    clauseEnd = i;
                    break;
                }
            }
            String restOfClause = text.substring(m.end(), clauseEnd);
            // Chỉ nhóm động từ có thể là hành động của chính Bot ("em đang/muốn/cần ...") mới được bảo vệ theo ngữ cảnh.
            // Nhóm "có thể tham khảo/chọn/trải nghiệm", "nên ...", "sẽ nhận được ..." luôn là hành động của KHÁCH.
            String verbGroup = m.group(2) != null ? m.group(2).toLowerCase(Locale.ROOT) : "";
            boolean agentAmbiguousVerb = verbGroup.startsWith("đang") || verbGroup.startsWith("muốn") || verbGroup.startsWith("cần");
            if (agentAmbiguousVerb && customerAsObject.matcher(restOfClause).find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + cap + " " + m.group(2)));
                anyChange = true;
            }
        }
        m.appendTail(sb);
        String working = anyChange ? sb.toString() : text;
        working = INVERTED_APOLOGY_PATTERN.matcher(working)
                .replaceAll(mr -> (mr.group(1) != null ? mr.group(1) : "") + "em xin lỗi " + target + " vì");
        working = INVERTED_CONSULTANT_ASK_PATTERN.matcher(working)
                .replaceAll(mr -> mr.group(1) + "Em có thể hỏi " + target + " một chút để tư vấn");
        return working;
    }

    private static boolean hasInvertedPronoun(String lower) {
        return lower.contains("em có thể") || lower.contains("em nên") || lower.contains("em sẽ")
                || lower.contains("em đang") || lower.contains("em muốn") || lower.contains("em cần")
                || lower.contains("xin lỗi em vì") || lower.contains("hỏi một chút để em tư vấn");
    }

    @Override
    public boolean isApplicable(GuardrailContext context, GuardrailDraft draft) {
        if (context == null || draft == null) {
            return false;
        }
        String text = draft.getText();
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        boolean isNeutralAnhChi = "Anh/Chị".equalsIgnoreCase(context.getCustomerHonorific());
        boolean isNeutralMinh = "mình".equalsIgnoreCase(context.getCustomerHonorific());
        if (context.isMale()) {
            return lower.contains("chị") || lower.contains("bạn") || hasInvertedPronoun(lower)
                    || lower.contains("anh anh") || lower.contains("anh/chị") || lower.contains("anh chị")
                    || lower.contains("liệu ");
        } else if (isNeutralMinh) {
            return lower.contains("chị") || lower.contains("bạn") || hasInvertedPronoun(lower)
                    || lower.contains("anh/chị") || lower.contains("anh chị")
                    || lower.contains("/chị") || lower.contains("/anh")
                    || lower.contains("liệu ");
        } else if (isNeutralAnhChi) {
            return lower.contains("bạn") || hasInvertedPronoun(lower)
                    || lower.contains("/chị") || lower.contains("/anh")
                    || lower.contains("liệu ");
        } else {
            return lower.contains("chị chị") || lower.contains("anh") || lower.contains("bạn")
                    || hasInvertedPronoun(lower) || lower.contains("anh/chị") || lower.contains("anh chị")
                    || lower.contains("liệu ");
        }
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

        // 1. Sửa lỗi đảo ngược đại từ ("Em có thể tham khảo..." / "Em đang quan tâm..." / "xin lỗi em vì" -> "Anh/Chị/Mình ...")
        if (hasInvertedPronoun(lower)) {
            working = repairIfReversed(working, honorific);
        }

        // 1.5. Dọn câu dịch máy "Liệu mình/Chị/Anh có muốn..." -> "Mình/Chị/Anh có muốn..."
        if (lower.contains("liệu ")) {
            working = working.replaceAll("(?iU)(^|[.!?\\n,;:\\-]\\s*)liệu\\s+(mình|chị|anh)\\s+có\\s+muốn\\b", "$1$2 có muốn");
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

        // Dọn dẹp dấu gạch chéo bị đứt gãy ở đầu câu ("Dạ /Chị ạ!" -> "Dạ ")
        working = working.replaceAll("(?iU)(?<=^|\\b(?:dạ|chào)\\s+)/\\s*(?:chị|anh)(?:\\s+ạ)?[!.,~]*\\s*", "");

        boolean isNeutralAnhChi = "Anh/Chị".equalsIgnoreCase(honorific);
        boolean isNeutralMinh = "mình".equalsIgnoreCase(honorific);
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
        } else if (isNeutralMinh) {
            if (lower.contains("anh") && lower.contains("chị")) {
                working = ANH_CHI_PATTERN.matcher(working).replaceAll("mình");
            }
            if (lower.contains("chị")) {
                working = CHI_REPLACEMENT_PATTERN.matcher(working).replaceAll(mr -> {
                    String match = mr.group();
                    if ("CHỊ".equals(match)) {
                        return "MÌNH";
                    } else if ("Chị".equals(match)) {
                        return "Mình";
                    } else {
                        return "mình";
                    }
                });
            }
            if (lower.contains("bạn")) {
                working = BAN1_PATTERN.matcher(working).replaceAll(mr -> mr.group(1) + " mình");
                working = BAN2_PATTERN.matcher(working).replaceAll("mình");
            }
            if (working.toLowerCase(Locale.ROOT).contains("mình")) {
                working = working.replaceAll("(?iU)(?<!\\p{L})mình\\s*,?\\s+mình(?!\\p{L})", "mình");
            }
        } else if (isNeutralAnhChi) {
            if (lower.contains("bạn")) {
                working = BAN1_PATTERN.matcher(working).replaceAll(mr -> mr.group(1) + " Anh/Chị");
                working = BAN2_PATTERN.matcher(working).replaceAll("Anh/Chị");
            }
            if (lower.contains("chị")) {
                working = CHI_REPLACEMENT_PATTERN.matcher(working).replaceAll("Anh/Chị");
                working = working.replaceAll("(?iU)(?<!\\p{L})(?:anh\\s*/\\s*)+chị(?!\\p{L})", "Anh/Chị");
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
