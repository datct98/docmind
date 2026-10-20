package com.docmind.guardrail;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-Loop Deterministic Guardrail Hook: Anti-Info Dumping in DISCOVERY Stage.
 * <p>
 * Trong giai đoạn DISCOVERY, khi khách hàng hỏi giá chung chung hoặc chưa nêu rõ bệnh lý/nền da,
 * nếu LLM xả toàn bộ bảng giá (>= 2 mức giá khác nhau) hoặc liệt kê đồng thời các gói Combo,
 * hook này tự động cô đọng lại theo công thức chuẩn của Sales RAG:
 * 1 Khoảng giá khởi điểm + 1 Quyền lợi 0đ rủi ro (Soi da AI 3D) + 1 Câu hỏi khống chế A/B.
 */
public class AntiInfoDumpingHook implements GuardrailHook {

    public static final String HOOK_CODE = "HOOK_ANTI_INFO_DUMPING_DISCOVERY";
    public static final String HOOK_CODE_SHORT = "HOOK_ANTI_INFO_DUMPING";
    public static final String CODE = HOOK_CODE;

    /**
     * Refactored Price Token Pattern:
     * Requires explicit currency indicators (đ, vnd, vnđ, k, triệu, tr, /buổi, /liệu trình)
     * OR explicit price keywords ("giá", "từ", "chỉ từ", "chi phí", "báo giá").
     * Uses negative lookaheads to rigorously reject counts/statistics
     * (e.g., "10.000 khách", "5.000 đánh giá", "5 sao", "20.000 lượt", "1.000 ca", "10 năm").
     */
    private static final Pattern PRICE_TOKEN_PATTERN = Pattern.compile(
            // Branch 1: Explicit price keyword prefix (giá, chi phí, chỉ từ, báo giá)
            "(?:(?:giá|chi\\s+phí|chỉ\\s+từ|báo\\s+giá)\\s+(?:là\\s+)?"
            + "(?<kwnum>\\d{1,3}(?:\\.\\d{3})+|\\d{4,9})"
            + "(?:\\s*(?<kwunit>(?:vnđ|đ|vnd|k(?!\\p{L})|triệu|tr(?!\\p{L}))?\\s*/\\s*(?:buổi|liệu\\s*trình)|vnđ|đ|vnd|k(?!\\p{L})|triệu|tr(?!\\p{L})))?"
            + "(?!\\s*(?:khách|lượt|đánh\\s+giá|review|người|ca(?!\\p{L})|bệnh\\s+nhân|sao(?!\\p{L})|năm|tháng|ngày|buổi|tuổi|%|phần\\s*trăm)))"
            + "|"
            // Branch 2: Number (dotted or un-dotted 4-9 digits) with mandatory currency or pricing unit suffix
            + "(?:(?<curnum>\\d{1,3}(?:\\.\\d{3})+|\\d{4,9})\\s*"
            + "(?<curunit>(?:vnđ|đ|vnd|k(?!\\p{L})|triệu|tr(?!\\p{L}))?\\s*/\\s*(?:buổi|liệu\\s*trình)|vnđ|đ|vnd|k(?!\\p{L})|triệu|tr(?!\\p{L}))"
            + "(?!\\s*(?:khách|lượt|đánh\\s+giá|review|người|ca(?!\\p{L})|bệnh\\s+nhân|sao(?!\\p{L}))))"
            + "|"
            // Branch 3: Short abbreviation with k, triệu, tr (excluding count nouns)
            + "(?:(?<shortnum>\\d+(?:[.,]\\d+)?)\\s*"
            + "(?<shortunit>(?:k(?!\\p{L})|triệu|tr(?!\\p{L}))(?:\\s*/\\s*(?:buổi|liệu\\s*trình))?)"
            + "(?!\\s*(?:khách|lượt|đánh\\s+giá|review|người|ca(?!\\p{L})|bệnh\\s+nhân|sao(?!\\p{L}))))",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS
    );

    @Override
    public String getHookCode() {
        return HOOK_CODE;
    }

    @Override
    public boolean apply(GuardrailContext context, GuardrailDraft draft) {
        if (!context.isDiscoveryStage()) {
            return false;
        }

        String text = draft.getText();
        if (text == null || text.isBlank()) {
            return false;
        }

        // Kiểm tra xem draft có xả nhiều mức giá hoặc nhiều gói combo không
        Set<Long> distinctPrices = extractDistinctPrices(text);

        String lower = text.toLowerCase(Locale.ROOT);
        boolean dumpsCombos = (lower.contains("combo tiết kiệm") && lower.contains("combo toàn diện"))
                || (lower.contains("3-4 buổi") && lower.contains("5-9 buổi"))
                || (lower.contains("giảm 10%") && lower.contains("giảm 15%"));
        boolean dumpsMultiplePrices = distinctPrices.size() >= 2;

        String customerMsgLower = context.getCustomerMessage().toLowerCase(Locale.ROOT);
        boolean asksBroadPrice = customerMsgLower.contains("giá")
                || customerMsgLower.contains("chi phí")
                || customerMsgLower.contains("bao nhiêu")
                || customerMsgLower.contains("báo giá");

        // Nếu không xả thông tin và không quá dài thì không can thiệp
        if (!dumpsMultiplePrices && !dumpsCombos && (!asksBroadPrice || text.length() <= 280)) {
            return false;
        }

        // Tìm mức giá khởi điểm thấp nhất trong text hoặc chunks
        String startingPrice = extractLowestStartingPrice(text, context.getKnowledgeChunks());

        String honorific = context.getCustomerHonorific();
        boolean isFirstTurn = context.isFirstTurn();

        String opening = isFirstTurn
                ? "Dạ em chào " + honorific + " ạ! "
                : "Dạ ";

        String rewritten = opening + "Chi phí các liệu trình chuẩn Y khoa bên em rất linh hoạt chỉ từ " + startingPrice
                + " tùy tình trạng nền da, đã bao gồm Bác sĩ chuyên khoa soi da AI 3D miễn phí 100% và cam kết trọn gói không phát sinh phụ phí ạ.\n\n"
                + "Hiện tại " + honorific + " đang muốn ưu tiên xử lý mụn, mờ nám tàn nhang hay dưỡng căng bóng trẻ hóa để em báo đúng gói hợp nhất cho mình ạ?";

        if (!rewritten.equals(text)) {
            draft.setText(rewritten);
            // Cập nhật lại bong bóng theo text mới
            draft.setBubbles(null); // Để BubbleCapHook hoặc pipeline tính lại
            draft.addTriggeredHook(HOOK_CODE);
            return true;
        }

        return false;
    }

    /**
     * Trích xuất các mức giá phân biệt (quy đổi về VNĐ) từ chuỗi văn bản.
     */
    private Set<Long> extractDistinctPrices(String text) {
        if (text == null || text.isBlank() || !hasDigit(text)) {
            return Collections.emptySet();
        }
        Matcher pm = PRICE_TOKEN_PATTERN.matcher(text);
        Set<Long> distinctPrices = new LinkedHashSet<>();
        while (pm.find()) {
            Long val = parsePriceToVnd(pm);
            if (val != null && val >= 50_000L) {
                distinctPrices.add(val);
                if (distinctPrices.size() >= 2) {
                    break;
                }
            }
        }
        return distinctPrices;
    }

    private static boolean hasDigit(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Quy đổi match token về giá trị số tiền VNĐ.
     */
    private static Long parsePriceToVnd(Matcher m) {
        String shortNum = m.group("shortnum");
        if (shortNum != null) {
            String shortUnit = m.group("shortunit").toLowerCase(Locale.ROOT);
            try {
                double val = Double.parseDouble(shortNum.replace(",", "."));
                if (shortUnit.startsWith("k")) {
                    return (long) (val * 1_000L);
                } else if (shortUnit.startsWith("triệu") || shortUnit.startsWith("tr")) {
                    return (long) (val * 1_000_000L);
                }
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        String numStr = m.group("curnum") != null ? m.group("curnum") : m.group("kwnum");
        if (numStr == null) {
            return null;
        }

        String unitStr = m.group("curunit") != null
                ? m.group("curunit")
                : (m.group("kwunit") != null ? m.group("kwunit") : "");

        try {
            long val = Long.parseLong(numStr.replace(".", ""));
            String u = unitStr.toLowerCase(Locale.ROOT);
            if (u.startsWith("k") || u.startsWith("k/")) {
                val *= 1_000L;
            } else if (u.startsWith("triệu") || u.startsWith("tr") || u.startsWith("triệu/") || u.startsWith("tr/")) {
                val *= 1_000_000L;
            }
            return val;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    String extractLowestStartingPrice(String text, List<String> chunks) {
        StringBuilder combined = new StringBuilder();
        if (text != null) {
            combined.append(text);
        }
        if (chunks != null) {
            for (String c : chunks) {
                if (c != null) {
                    combined.append(" ").append(c);
                }
            }
        }
        String combinedStr = combined.toString();

        long minVal = Long.MAX_VALUE;
        boolean hasPerSession = false;
        Matcher cm = PRICE_TOKEN_PATTERN.matcher(combinedStr);
        while (cm.find()) {
            Long val = parsePriceToVnd(cm);
            if (val != null && val >= 50_000L && val < minVal) {
                minVal = val;
                String matchedStr = cm.group(0).toLowerCase(Locale.ROOT);
                hasPerSession = matchedStr.contains("buổi") || matchedStr.contains("buoi");
            }
        }

        if (minVal != Long.MAX_VALUE && minVal > 0) {
            String formatted = String.format(Locale.GERMANY, "%,d", minVal);
            return formatted + (hasPerSession ? "đ/buổi" : "đ");
        }

        return "650.000đ/buổi";
    }
}
