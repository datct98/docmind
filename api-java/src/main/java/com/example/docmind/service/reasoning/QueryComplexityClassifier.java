package com.example.docmind.service.reasoning;

import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.service.sales.PriceLadderTracker;
import com.example.docmind.service.sales.PriceLadderTracker.PriceLadderResult;
import com.example.docmind.service.text.VietnameseQueryNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Bộ phân loại độ phức tạp câu hỏi (F8: QueryComplexityClassifier).
 * 
 * Phân định 2 cấp độ:
 * 1. SIMPLE (4-8s target latency, reasoningEffort = "low"):
 *    - Lời chào hỏi (Greetings)
 *    - Giờ mở cửa / giờ hoạt động (Operating Hours)
 *    - Địa chỉ cơ sở / hotline / chi nhánh (Branch Locations / Hotline)
 *    - Câu hỏi FAQ đơn lẻ, tra cứu bảng giá khởi điểm / đặt lịch cơ bản
 * 
 * 2. DEEP_REASONING (15-30s target latency, reasoningEffort = "medium"):
 *    - So sánh các gói dịch vụ (Package Comparisons, e.g. "gói 670k và 780k khác gì nhau")
 *    - Phản đối giá / bậc thang hạ giá (Down-sell Price Objections, e.g. "cao thế", "nhưng hơi đắt", "có gói nào rẻ hơn không")
 *    - Tư vấn đa triệu chứng (Multi-symptom Consultations, e.g. "vừa mụn vừa thâm", "mụn ẩn kèm sẹo rỗ", "đau vai gáy và mất ngủ")
 *    - Câu hỏi dịch vụ phức tạp, điều kiện khuyến mãi nhiều nấc (Complex Inquiries)
 */
@Service
public class QueryComplexityClassifier {

    private static final Logger log = LoggerFactory.getLogger(QueryComplexityClassifier.class);

    // =========================================================================
    // DEEP REASONING PATTERNS
    // =========================================================================

    /** So sánh các gói dịch vụ hoặc thắc mắc chênh lệch giá. */
    public static final Pattern PACKAGE_COMPARISON_PATTERN = Pattern.compile(
            "(?isU).*(?:\\b(?:khác\\s+gì|khác\\s+nhau|so\\s+sánh|chênh\\s+giá|chênh\\s+nhau|tại\\s+sao\\s+chênh|sao\\s+chênh|hơn\\s+gì|phân\\s+biệt|"
            + "nên\\s+chọn\\s+gói|chọn\\s+gói\\s+nào|gói\\s+nào\\s+(?:tốt|hợp|rẻ|được)\\s+hơn|gói\\s+nào\\s+hơn)\\b|"
            + "(?:gói\\s+)?(?:\\d{2,4}k|\\d{1,3}(?:\\.\\d{3})+)\\s*(?:vnđ|vnd|đ)?\\s*(?:và|với|hay|hoặc)\\s*(?:gói\\s+)?(?:\\d{2,4}k|\\d{1,3}(?:\\.\\d{3})+)).*"
    );

    /** Phản đối giá / chê đắt / xin hạ giá / tìm gói rẻ hơn từ PriceLadder. */
    public static final Pattern PRICE_OBJECTION_PATTERN = PriceLadderTracker.PRICE_OBJECTION_PATTERN;

    /** Mở rộng phản đối giá (ngân sách, ví, chát, căng ví). */
    public static final Pattern EXTENDED_PRICE_OBJECTION_PATTERN = Pattern.compile(
            "(?isU).*(?:\\b(?:vượt\\s+ngân\\s+sách|quá\\s+ngân\\s+sách|hạn\\s+hẹp\\s+ngân\\s+sách|không\\s+đủ\\s+ngân\\s+sách|"
            + "hơi\\s+chát|giá\\s+hơi\\s+chát|chát\\s+thế|chát\\s+vậy|hơi\\s+căng|căng\\s+ví|xót\\s+tiền|tiết\\s+kiệm\\s+hơn)\\b).*"
    );

    /** Tư vấn kết hợp nhiều bệnh lý / triệu chứng / nhu cầu cùng lúc. */
    public static final Pattern MULTI_SYMPTOM_CONNECTIVE_PATTERN = Pattern.compile(
            "(?isU).*(?:\\b(?:vừa.*?vừa|kèm\\s+(?:theo|với)?|lẫn|với\\s+cả|và\\s+cũng\\s+bị|đồng\\s+thời|kết\\s+hợp)\\b).*"
    );

    public static final Pattern SYMPTOM_TOKEN_PATTERN = Pattern.compile(
            "(?iU)\\b(mụn\\s+ẩn|mụn\\s+viêm|mụn\\s+bọc|mụn\\s+đầu\\s+đen|mụn|thâm\\s+mụn|thâm|sẹo\\s+rỗ|sẹo|"
            + "nám|tàn\\s+nhang|sạm|đốm\\s+nâu|lão\\s+hóa|nếp\\s+nhăn|chảy\\s+xệ|"
            + "da\\s+dầu|da\\s+khô|da\\s+nhạy\\s+cảm|lỗ\\s+chân\\s+lông|"
            + "đau\\s+cổ\\s+vai\\s+gáy|đau\\s+vai\\s+gáy|đau\\s+lưng|nhức\\s+mỏi|mỏi\\s+người|mất\\s+ngủ|stress|căng\\s+thẳng|"
            + "gội\\s+đầu\\s+dưỡng\\s+sinh|gội\\s+đầu|dưỡng\\s+sinh|massage|rụng\\s+tóc|gàu)\\b"
    );

    /** Câu hỏi đa ý (chứa nhiều câu hỏi, nhiều dấu hỏi ? hoặc cấu trúc câu hỏi kép). */
    public static final Pattern MULTI_PART_QUESTION_PATTERN = Pattern.compile(
            "(?isU).*(?:\\?.*?\\?|\\bvừa\\s+muốn.*?vừa\\s+muốn|không\\s+biết.*?và.*?bao\\s+lâu|vừa\\s+hỏi.*?vừa\\s+hỏi).*"
    );

    /** Tính toán khuyến mãi có điều kiện / combo nhiều buổi / voucher. */
    public static final Pattern CONDITIONAL_PROMO_PATTERN = Pattern.compile(
            "(?isU).*(?:\\b(?:mua\\s+\\d+\\s+buổi|combo\\s+\\d+\\s+voucher|tổng\\s+(?:hết)?\\s+bao\\s+nhiêu|được\\s+giảm\\s+(?:thế\\s+nào|bao\\s+nhiêu|mấy\\s+%)|chiết\\s+khấu\\s+thế\\s+nào)\\b).*"
    );

    // =========================================================================
    // SIMPLE PATTERNS
    // =========================================================================

    /** Lời chào hỏi thông thường. */
    public static final Pattern GREETING_PATTERN = Pattern.compile(
            "(?iU)^\\s*(dạ\\s+)?(chào\\s*(shop|em|bạn|ad|page)?|hello|hi|alo|ad\\s+ơi|shop\\s+ơi|em\\s+ơi)\\s*[!.,~?]*\\s*$"
    );

    /** Giờ mở cửa / giờ hoạt động. */
    public static final Pattern OPERATING_HOURS_PATTERN = Pattern.compile(
            "(?isU).*(?:mấy\\s+giờ\\s+mở\\s+cửa|giờ\\s+mở\\s+cửa|mở\\s+cửa\\s+lúc\\s+mấy\\s+giờ|"
            + "đóng\\s+cửa\\s+mấy\\s+giờ|mấy\\s+giờ\\s+đóng\\s+cửa|làm\\s+việc\\s+đến\\s+mấy\\s+giờ|giờ\\s+làm\\s+việc|"
            + "có\\s+làm\\s+trưa\\s+không|cuối\\s+tuần\\s+có\\s+làm\\s+không).*"
    );

    /** Địa chỉ / chi nhánh / hotline. */
    public static final Pattern LOCATION_HOTLINE_PATTERN = Pattern.compile(
            "(?isU).*(?:địa\\s+chỉ\\s+(?:ở\\s+đâu|chi\\s+nhánh|cơ\\s+sở)|chi\\s+nhánh\\s+ở\\s+đâu|cơ\\s+sở\\s+ở\\s+đâu|"
            + "ở\\s+đâu\\s+ạ|hotline|sđt|số\\s+điện\\s+thoại\\s+liên\\s+hệ|tìm\\s+địa\\s+chỉ).*"
    );

    public QueryComplexity classify(String message) {
        return classify(message, null, null);
    }

    public QueryComplexity classify(String message, List<FanpageBotRequest.ConversationTurn> history) {
        return classify(message, history, null);
    }

    public QueryComplexity classify(String message,
                                    List<FanpageBotRequest.ConversationTurn> history,
                                    PriceLadderResult priceLadder) {
        if (message == null || message.isBlank()) {
            return QueryComplexity.SIMPLE;
        }

        // 1. Kiểm tra Down-sell Price Objection từ PriceLadder (ưu tiên cao nhất nếu session có state)
        if (priceLadder != null && (priceLadder.isPriceObjection() || priceLadder.isCatalogFloor())) {
            log.debug("[Reasoning] Classified as DEEP_REASONING: PriceLadder objection/floor active");
            return QueryComplexity.DEEP_REASONING;
        }

        // 2. Bounded length guard at entry (capped to 500 chars) to prevent ReDoS quadratic backtracking on extreme payloads
        String bounded = message.length() > 500 ? message.substring(0, 500) : message;
        String rawMessage = bounded.trim();

        // 3. Fast-path: Lời chào hỏi ngắn đơn thuần -> SIMPLE ngay lập tức
        if (GREETING_PATTERN.matcher(rawMessage).matches()) {
            log.debug("[Reasoning] Classified as SIMPLE: Matched pure greeting");
            return QueryComplexity.SIMPLE;
        }

        String normMessage = VietnameseQueryNormalizer.normalize(bounded).trim();
        if (normMessage.length() > 500) {
            normMessage = normMessage.substring(0, 500);
        }
        boolean isDifferent = !normMessage.equals(rawMessage);
        String normLower = normMessage.toLowerCase(Locale.ROOT);
        String rawLower = isDifferent ? rawMessage.toLowerCase(Locale.ROOT) : normLower;

        // 4. Kiểm tra So sánh các gói dịch vụ (Package Comparisons)
        if ((containsAnyComparisonKeyword(normLower) || (isDifferent && containsAnyComparisonKeyword(rawLower)))
                && matchesAny(PACKAGE_COMPARISON_PATTERN, normMessage, rawMessage, isDifferent)) {
            log.debug("[Reasoning] Classified as DEEP_REASONING: Matched package comparison pattern");
            return QueryComplexity.DEEP_REASONING;
        }

        // 5. Kiểm tra Down-sell Price Objection
        if ((containsAnyPriceKeyword(normLower) || (isDifferent && containsAnyPriceKeyword(rawLower)))
                && (matchesAny(PRICE_OBJECTION_PATTERN, normMessage, rawMessage, isDifferent)
                || matchesAny(EXTENDED_PRICE_OBJECTION_PATTERN, normMessage, rawMessage, isDifferent))) {
            log.debug("[Reasoning] Classified as DEEP_REASONING: Matched price objection pattern");
            return QueryComplexity.DEEP_REASONING;
        }

        // 6. Kiểm tra Câu hỏi tính toán khuyến mãi có điều kiện
        if ((containsAnyPromoKeyword(normLower) || (isDifferent && containsAnyPromoKeyword(rawLower)))
                && matchesAny(CONDITIONAL_PROMO_PATTERN, normMessage, rawMessage, isDifferent)) {
            log.debug("[Reasoning] Classified as DEEP_REASONING: Matched conditional promo calculation");
            return QueryComplexity.DEEP_REASONING;
        }

        // 7. Kiểm tra Câu hỏi đa ý / đa triệu chứng
        if (checkMultiSymptom(normMessage, rawMessage, isDifferent, normLower, rawLower)) {
            log.debug("[Reasoning] Classified as DEEP_REASONING: Matched multi-symptom consultation");
            return QueryComplexity.DEEP_REASONING;
        }

        if ((containsMultiPartCandidate(normLower) || (isDifferent && containsMultiPartCandidate(rawLower)))
                && matchesAny(MULTI_PART_QUESTION_PATTERN, normMessage, rawMessage, isDifferent)) {
            log.debug("[Reasoning] Classified as DEEP_REASONING: Matched multi-part question");
            return QueryComplexity.DEEP_REASONING;
        }

        // 8. Nếu không khớp bất kỳ điều kiện Deep Reasoning nào -> Phân loại là SIMPLE
        log.debug("[Reasoning] Classified as SIMPLE (fast response budget 4-8s)");
        return QueryComplexity.SIMPLE;
    }

    private static boolean matchesAny(Pattern pattern, String norm, String raw, boolean isDifferent) {
        if (pattern.matcher(norm).matches()) {
            return true;
        }
        return isDifferent && pattern.matcher(raw).matches();
    }

    private boolean checkMultiSymptom(String norm, String raw, boolean isDifferent, String normLower, String rawLower) {
        if (isMultiSymptomConsultation(norm, normLower)) {
            return true;
        }
        return isDifferent && isMultiSymptomConsultation(raw, rawLower);
    }

    private boolean isMultiSymptomConsultation(String text, String lower) {
        if (containsConnectiveCandidate(lower) && MULTI_SYMPTOM_CONNECTIVE_PATTERN.matcher(text).matches()) {
            return true;
        }
        var matcher = SYMPTOM_TOKEN_PATTERN.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
            if (count >= 2) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAnyComparisonKeyword(String lower) {
        if (lower.contains("khác") || lower.contains("khac")
                || lower.contains("so sánh") || lower.contains("so sanh")
                || lower.contains("chênh") || lower.contains("chenh")
                || lower.contains("hơn") || lower.contains("hon")
                || lower.contains("phân biệt") || lower.contains("phan biet")
                || lower.contains("chọn") || lower.contains("chon")
                || lower.contains("gói nào") || lower.contains("goi nao")) {
            return true;
        }
        boolean hasDigit = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c >= '0' && c <= '9') {
                hasDigit = true;
                break;
            }
        }
        if (hasDigit) {
            return lower.contains("và") || lower.contains("va")
                    || lower.contains("với") || lower.contains("voi")
                    || lower.contains("hay")
                    || lower.contains("hoặc") || lower.contains("hoac");
        }
        return false;
    }

    private static boolean containsAnyPriceKeyword(String lower) {
        return lower.contains("cao")
                || lower.contains("đắt") || lower.contains("dat")
                || lower.contains("mắc") || lower.contains("mac")
                || lower.contains("chát") || lower.contains("chat")
                || lower.contains("rẻ") || lower.contains("re")
                || lower.contains("mềm") || lower.contains("mem")
                || lower.contains("thấp") || lower.contains("thap")
                || lower.contains("bớt") || lower.contains("bot")
                || lower.contains("giảm") || lower.contains("giam")
                || lower.contains("tiết kiệm") || lower.contains("tiet kiem")
                || lower.contains("nhỏ") || lower.contains("nho")
                || lower.contains("mặc cả") || lower.contains("mac ca")
                || lower.contains("ngân sách") || lower.contains("ngan sach")
                || lower.contains("căng") || lower.contains("cang")
                || lower.contains("xót") || lower.contains("xot");
    }

    private static boolean containsAnyPromoKeyword(String lower) {
        return lower.contains("buổi") || lower.contains("buoi")
                || lower.contains("voucher")
                || lower.contains("tổng") || lower.contains("tong")
                || lower.contains("chiết khấu") || lower.contains("chiet khau")
                || lower.contains("giảm") || lower.contains("giam")
                || lower.contains("combo");
    }

    private static boolean containsConnectiveCandidate(String lower) {
        return lower.contains("vừa") || lower.contains("vua")
                || lower.contains("kèm") || lower.contains("kem")
                || lower.contains("lẫn") || lower.contains("lan")
                || lower.contains("với cả") || lower.contains("voi ca")
                || lower.contains("và cũng") || lower.contains("va cung")
                || lower.contains("đồng thời") || lower.contains("dong thoi")
                || lower.contains("kết hợp") || lower.contains("ket hop");
    }

    private static boolean containsMultiPartCandidate(String lower) {
        int qMarkCount = 0;
        for (int i = 0; i < lower.length(); i++) {
            if (lower.charAt(i) == '?') {
                qMarkCount++;
                if (qMarkCount >= 2) return true;
            }
        }
        return lower.contains("vừa") || lower.contains("vua")
                || lower.contains("không biết") || lower.contains("khong biet");
    }
}
