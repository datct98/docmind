package com.example.docmind.service.bot;

import com.example.docmind.service.security.PromptSecurityGuard;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tầng phân tích Cảm xúc &amp; Ý định khách hàng có thứ tự ưu tiên nghiêm ngặt (Sentiment &amp; Intent Layer v3.1.5.3).
 * <p>
 * Thứ tự ưu tiên:
 * <ol>
 *   <li>{@code SAFETY_RISK}</li>
 *   <li>{@code OPT_OUT}</li>
 *   <li>{@code NEUROLOGICAL_RISK}</li>
 *   <li>{@code ENTITY_MISMATCH_CORRECTION} (khách phản ứng vì bot tư vấn lệch vùng/nhu cầu hoặc dùng đại từ gay gắt mắng tư vấn sai)</li>
 *   <li>{@code TRIAL_ACCEPTANCE} (chỉ khi không chỉ định gói/thời lượng khác với {@code held_package}/{@code downsell_package})</li>
 *   <li>{@code STRONG_REJECTION} (chỉ match cụm đầy đủ, tuyệt đối không bắt nhầm từ đơn "thôi")</li>
 *   <li>{@code PACKAGE_REJECTION}</li>
 *   <li>{@code FRUSTRATION}</li>
 *   <li>{@code PRICE_ESCALATION_REQUEST}</li>
 *   <li>{@code EXPLICIT_DOWNSELL_REQUEST}</li>
 *   <li>{@code PRICE_OBJECTION}</li>
 *   <li>{@code PRICE_COMPARISON}</li>
 *   <li>{@code PRICE_INQUIRY}</li>
 *   <li>{@code SERVICE_SELECTION}</li>
 *   <li>{@code SYMPTOM_DESCRIPTION}</li>
 *   <li>{@code ACK}</li>
 *   <li>{@code AMBIGUOUS}</li>
 * </ol>
 */
public final class SentimentIntentLayer {

    private SentimentIntentLayer() {
    }

    public enum DetectedIntent {
        PROMPT_INJECTION,
        SAFETY_RISK,
        OPT_OUT,
        NEUROLOGICAL_RISK,
        ENTITY_MISMATCH_CORRECTION,
        TRIAL_ACCEPTANCE,
        STRONG_REJECTION,
        PACKAGE_REJECTION,
        COMPREHENSION_FAILURE,
        FRUSTRATION,
        DETAIL_OR_FAQ_QUESTION,
        PRICE_ESCALATION_REQUEST,
        EXPLICIT_DOWNSELL_REQUEST,
        ALTERNATIVE_PACKAGE_REQUEST,
        PRICE_OBJECTION,
        PRICE_COMPARISON,
        PRICE_INQUIRY,
        SERVICE_SELECTION,
        SYMPTOM_DESCRIPTION,
        ACK,
        AMBIGUOUS
    }

    private static final Pattern DIACRITICS_PATTERN = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");

    // 1. OPT_OUT
    private static final Pattern OPT_OUT_PATTERN = Pattern.compile(
            "(?isU).*\\b(đừng\\s+nhắn|dừng\\s+lại|không\\s+cần\\s+nữa|làm\\s+ơn\\s+đừng|thôi\\s+đừng\\s+nhắn|ngừng\\s+nhắn)\\b.*"
    );
    private static final Pattern OPT_OUT_ASCII_PATTERN = Pattern.compile(
            "(?is).*\\b(dung\\s+nhan|khong\\s+can\\s+nua|thoi\\s+dung\\s+nhan|ngung\\s+nhan)\\b.*"
    );

    // 2. NEUROLOGICAL_RISK
    private static final Pattern ACUTE_NEUROLOGICAL_RED_FLAG_PATTERN = Pattern.compile(
            "(?isU).*\\b(đau\\s+đầu\\s+dữ\\s+dội|yếu\\s+tay|yếu\\s+chân|mất\\s+cảm\\s+giác|liệt\\s+tay|liệt\\s+chân|"
            + "chóng\\s+mặt\\s+nhiều|hoa\\s+mắt\\s+chóng\\s+mặt|ù\\s+tai\\s+chóng\\s+mặt)\\b.*"
    );
    private static final Pattern ACUTE_NEUROLOGICAL_ASCII_PATTERN = Pattern.compile(
            "(?is).*\\b(dau\\s+dau\\s+du\\s+doi|yeu\\s+tay|yeu\\s+chan|mat\\s+cam\\s+giac|hoa\\s+mat\\s+chong\\s+mat)\\b.*"
    );
    private static final Pattern NUMBNESS_OR_DIZZINESS_PATTERN = Pattern.compile(
            "(?isU).*\\b(tê\\s+tay|tê\\s+chân|tê\\s+bì|chóng\\s+mặt|ù\\s+tai|hoa\\s+mắt)\\b.*"
    );
    private static final Pattern SEVERE_OR_PERSISTENT_MODIFIER_PATTERN = Pattern.compile(
            "(?isU).*\\b(dữ\\s+dội|nặng\\s+lắm|rất\\s+nặng|khó\\s+chịu\\s+nhiều|liên\\s+tục|mấy\\s+tuần|mấy\\s+tháng|mất\\s+cảm\\s+giác|yếu)\\b.*"
    );

    // 2B. ENTITY_MISMATCH_CORRECTION & Anti-Keyword-Hijacking
    private static final Pattern ENTITY_MISMATCH_CORRECTION_PATTERN = Pattern.compile(
            "(?isU).*(?:"
            + "\\b(?:đau|mỏi|nhức|tê|bị|hỏi|cần|mua|muốn|tìm)\\b.+?\\bmà\\s+(?:mày|bạn|em|shop|bên\\s+em|lại|đi|sao)\\b.*?(?:tư\\s+vấn|gợi\\s+ý|báo\\s+giá|giới\\s+thiệu)"
            + "|\\b(?:tư\\s+vấn|gợi\\s+ý|báo\\s+giá|giới\\s+thiệu)\\b[^.?!;]*?\\b(?:là\\s+sao|làm\\s+gì)\\b"
            + "|\\bsao\\s+(?:lại\\s+|em\\s+|mày\\s+|bạn\\s+|shop\\s+|bên\\s+em\\s+|đi\\s+)*(?:tư\\s+vấn|gợi\\s+ý|báo\\s+giá|giới\\s+thiệu)\\b"
            + "|\\b(?:tư\\s+vấn|gợi\\s+ý|báo\\s+giá|giới\\s+thiệu)\\s+(?:nhầm|sai|lạc\\s+đề|không\\s+đúng|trật)\\b"
            + "|\\bcó\\s+(?:hỏi|cần|mua|muốn|tìm)\\b.+?\\bđâu\\b"
            + "|\\b(?:đâu\\s+có|không\\s+có)\\s+(?:hỏi|bị|đau|mỏi|cần|mua)\\b"
            + "|\\bchứ\\s+(?:có\\s+phải|không\\s+phải|đâu\\s+phải)\\s+(?:hỏi|bị|đau|mỏi|cần|mua|tư\\s+vấn|gói|massage|dịch\\s+vụ|làm)\\b"
            + "|\\bchứ\\s+(?:có\\s+phải|đâu\\s+phải)\\b.+?\\bđâu\\b"
            + "|\\b(?:mày|tao)\\b.*?(?:tư\\s+vấn|gợi\\s+ý|báo\\s+giá|là\\s+sao)"
            + ").*"
    );
    private static final Pattern ENTITY_MISMATCH_CORRECTION_ASCII_PATTERN = Pattern.compile(
            "(?is).*(?:"
            + "\\b(?:dau|moi|nhuc|te|bi|hoi|can|mua|muon|tim)\\b.+?\\bma\\s+(?:may|ban|em|shop|ben\\s+em|lai|di|sao)\\b.*?(?:tu\\s+van|goi\\s+y|bao\\s+gia|gioi\\s+thieu)"
            + "|\\b(?:tu\\s+van|goi\\s+y|bao\\s+gia|gioi\\s+thieu)\\b[^.?!;]*?\\b(?:la\\s+sao|lam\\s+gi)\\b"
            + "|\\bsao\\s+(?:lai\\s+|em\\s+|may\\s+|ban\\s+|shop\\s+|ben\\s+em\\s+|di\\s+)*(?:tu\\s+van|goi\\s+y|bao\\s+gia|gioi\\s+thieu)\\b"
            + "|\\b(?:tu\\s+van|goi\\s+y|bao\\s+gia|gioi\\s+thieu)\\s+(?:nham|sai|lac\\s+de|khong\\s+dung)\\b"
            + "|\\b(?:tao\\s+.*?\\bmay|may\\s+.*?\\btao)\\b.*?(?:tu\\s+van|goi\\s+y|bao\\s+gia|la\\s+sao)"
            + ").*"
    );
    private static final Pattern HARSH_PRONOUN_PATTERN = Pattern.compile(
            "(?isU).*\\b(mày|tao|bọn\\s+mày|chúng\\s+mày)\\b.*"
    );
    private static final Pattern HARSH_PRONOUN_ASCII_PAIR_PATTERN = Pattern.compile(
            "(?is).*(?:\\btao\\b.*\\bmay\\b|\\bmay\\b.*\\btao\\b).*"
    );
    private static final Pattern[] MISMATCH_CRITIQUE_CLAUSE_PATTERNS = new Pattern[]{
            Pattern.compile("(?isU)\\bmà\\s+(?:mày|bạn|em|shop|bên\\s+em|lại|đi|sao)\\b[^,.?!;]*?\\b(?:tư\\s+vấn|gợi\\s+ý|báo\\s+giá|giới\\s+thiệu)\\s+([^,.?!;]+?)(?:\\s+(?:là\\s+sao|làm\\s+gì|vậy|thế|hả|ạ|nhỉ))?(?=\\s+(?:trong\\s+khi|khi\\s+mà|chứ\\b)|[,.;?!]|$)"),
            Pattern.compile("(?isU)(?:\\b(?:mày|bạn|em|shop|bên\\s+em|lại|đi)\\s+)*(?:đi\\s+|lại\\s+)*\\b(?:tư\\s+vấn|gợi\\s+ý|báo\\s+giá|giới\\s+thiệu)\\s+([^,.?!;]+?)\\s+(?:là\\s+sao|làm\\s+gì)\\b(?:\\s+(?:vậy|thế|hả|ạ|nhỉ))?"),
            Pattern.compile("(?isU)\\bsao\\s+(?:lại\\s+|em\\s+|mày\\s+|bạn\\s+|shop\\s+|bên\\s+em\\s+|đi\\s+)*\\b(?:tư\\s+vấn|gợi\\s+ý|báo\\s+giá|giới\\s+thiệu)\\s+([^,.?!;]+?)(?:\\s+(?:là\\s+sao|làm\\s+gì|vậy|thế|hả|ạ|nhỉ))?(?=\\s+(?:trong\\s+khi|khi\\s+mà|mà\\b|chứ\\b)|[,.;?!]|$)"),
            Pattern.compile("(?isU)\\b(?:chứ\\s+(?:có\\s+phải|không\\s+phải|đâu\\s+phải)\\s*(?:hỏi|bị|đau|mỏi|cần|mua|tư\\s+vấn)?|(?:đâu\\s+có|không\\s+có)\\s+(?:hỏi|bị|đau|mỏi|cần|mua|muốn|tìm)|có\\s+(?:hỏi|cần|mua|muốn|tìm))\\s+([^,.?!;]+?)(?:\\s+đâu(?:\\s+ạ|\\s+em|\\s+shop)?\\b|(?=\\s+(?:trong\\s+khi|mà\\b|chứ\\b)|[,.;?!]|$))"),
            Pattern.compile("(?isU)(?:\\b(?:mày|bạn|em|shop|bên\\s+em)\\s+)?\\b(?:tư\\s+vấn|gợi\\s+ý|báo\\s+giá|giới\\s+thiệu)\\s+(?:nhầm|sai|lạc\\s+đề|không\\s+đúng|trật)\\s*(?:sang|qua|vào|gói|dịch\\s+vụ)*\\s*([^,.?!;]*?)(?:\\s+(?:rồi|vậy|thế|ạ|nhỉ))?(?=\\s+(?:trong\\s+khi|khi\\s+mà|mà\\b|chứ\\b)|[,.;?!]|$)")
    };

    // 3. STRONG_REJECTION (NEVER match standalone "thôi")
    private static final Pattern STRONG_REJECTION_PATTERN = Pattern.compile(
            "(?isU).*\\b(thôi\\s+khỏi(?:\\s+cần)?|thôi\\s+không\\s+cần|thôi\\s+không\\s+làm|không\\s+cần\\s+nữa|"
            + "không\\s+làm\\s+nữa|không\\s+mua\\s+đâu|thôi\\s+để\\s+sau|thôi\\s+bỏ\\s+đi|khỏi\\s+cần\\s+tư\\s+vấn)\\b.*"
    );
    private static final Pattern STRONG_REJECTION_ASCII_PATTERN = Pattern.compile(
            "(?is).*\\b(thoi\\s+khoi(?:\\s+can)?|thoi\\s+khong\\s+can|thoi\\s+khong\\s+lam|khong\\s+mua\\s+dau|thoi\\s+de\\s+sau)\\b.*"
    );

    // 4. TRIAL_ACCEPTANCE
    private static final Pattern TRIAL_ACCEPTANCE_PATTERN = Pattern.compile(
            "(?isU).*\\b(thử\\s+xem\\s+sao|thử\\s+xem|làm\\s+thử|trải\\s+nghiệm\\s+thử|ok\\s+thử\\s+1\\s+buổi|"
            + "thử\\s+1\\s+buổi|thử\\s+một\\s+buổi|đăng\\s+ký\\s+thử|trải\\s+nghiệm\\s+1\\s+buổi)\\b.*"
    );
    private static final Pattern TRIAL_ACCEPTANCE_ASCII_PATTERN = Pattern.compile(
            "(?is).*\\b(thu\\s+xem\\s+sao|lam\\s+thu|trai\\s+nghiem\\s+thu|ok\\s+thu\\s+1\\s+buoi|thu\\s+1\\s+buoi|dang\\s+ky\\s+thu)\\b.*"
    );

    // 5. PACKAGE_REJECTION
    private static final Pattern PACKAGE_REJECTION_PATTERN = Pattern.compile(
            "(?isU).*\\b(khỏi\\s+gói\\s+đó|bỏ\\s+gói|không\\s+làm\\s+gói|không\\s+cần\\s+gói|"
            + "gói\\s+kia\\s+đắt\\s+quá|gói\\s+\\d{2}\\s*(?:phút\\s+|p\\s+)?đắt\\s+quá|đổi\\s+gói\\s+khác|gói\\s+ngắn\\s+hơn\\s+đi)\\b.*"
    );
    private static final Pattern PACKAGE_REJECTION_ASCII_PATTERN = Pattern.compile(
            "(?is).*\\b(khoi\\s+goi\\s+do|bo\\s+goi|khong\\s+lam\\s+goi|khong\\s+can\\s+goi|"
            + "goi\\s+kia\\s+dat\\s+qua|goi\\s+\\d{2}\\s*(?:phut\\s+|p\\s+)?dat\\s+qua|doi\\s+goi\\s+khac|goi\\s+ngan\\s+hon\\s+di)\\b.*"
    );

    // 6. PRICE_ESCALATION_REQUEST
    private static final Pattern PRICE_ESCALATION_PATTERN = Pattern.compile(
            "(?isU).*\\b(gặp\\s+quản\\s+lý|xin\\s+giá\\s+tốt\\s+hơn|có\\s+ưu\\s+đãi\\s+riêng\\s+không|giảm\\s+thêm\\s+được\\s+không|bớt\\s+thêm\\s+đi)\\b.*"
    );
    private static final Pattern PRICE_ESCALATION_ASCII_PATTERN = Pattern.compile(
            "(?is).*\\b(gap\\s+quan\\s+ly|xin\\s+gia\\s+tot\\s+hon|co\\s+uu\\s+dai\\s+rieng\\s+khong|giam\\s+them\\s+duoc\\s+khong)\\b.*"
    );

    // 7. EXPLICIT_DOWNSELL_REQUEST (Never match standalone "dưới")
    private static final Pattern EXPLICIT_DOWNSELL_PATTERN = Pattern.compile(
            "(?isU).*\\b(rẻ\\s+hơn|mềm\\s+hơn|ít\\s+tiền\\s+hơn|gói\\s+ngắn\\s+hơn|lựa\\s+chọn\\s+nào\\s+ngắn\\s+hơn|"
            + "gói\\s+nào\\s+nhẹ\\s+nhàng\\s+hơn|thời\\s+lượng\\s+ngắn\\s+hơn|có\\s+gói\\s+nào\\s+thấp\\s+hơn|"
            + "gói\\s+thấp\\s+hơn|gói\\s+tiết\\s+kiệm|ngân\\s+sách\\s+gọn|"
            + "dưới\\s+\\d+\\s*(?:k\\b|\\.?\\d*000\\b|triệu\\b))\\b.*"
    );
    private static final Pattern EXPLICIT_DOWNSELL_ASCII_PATTERN = Pattern.compile(
            "(?is).*\\b(re\\s+hon|it\\s+tien\\s+hon|goi\\s+ngan\\s+hon|thoi\\s+luong\\s+ngan\\s+hon|"
            + "co\\s+goi\\s+nao\\s+thap\\s+hon|goi\\s+tiet\\s+kiem|ngan\\s+sach\\s+gon|"
            + "duoi\\s+\\d+\\s*(?:k\\b|\\.?\\d*000\\b|trieu\\b))\\b.*"
    );

    // 7B. ALTERNATIVE_PACKAGE_REQUEST ("Có gói khác k", "Còn gói nào nữa không", "Gói khác đi")
    private static final Pattern ALTERNATIVE_PACKAGE_REQUEST_PATTERN = Pattern.compile(
            "(?isU).*\\b(có\\s+gói\\s+(?:nào\\s+)?khác(?:\\s+(?:không|k|ko|hông|ạ|em|shop))?|"
            + "còn\\s+gói\\s+nào\\s+(?:khác|nữa)?(?:\\s*(?:không|k|ko|hông|ạ|em|shop))?|"
            + "còn\\s+những\\s+gói\\s+nào|có\\s+những\\s+gói\\s+nào|tư\\s+vấn\\s+gói\\s+khác|gói\\s+khác\\s+đi|"
            + "ngoài\\s+gói\\s+(?:này|đó|trên)\\s+(?:còn|có)\\s+gói\\s+nào)\\b.*"
    );
    private static final Pattern ALTERNATIVE_PACKAGE_REQUEST_ASCII_PATTERN = Pattern.compile(
            "(?is).*\\b(co\\s+goi\\s+(?:nao\\s+)?khac(?:\\s+(?:khong|k|ko|hong|a|em|shop))?|"
            + "con\\s+goi\\s+nao\\s+(?:khac|nua)?(?:\\s*(?:khong|k|ko|hong|a|em|shop))?|"
            + "con\\s+nhung\\s+goi\\s+nao|co\\s+nhung\\s+goi\\s+nao|tu\\s+van\\s+goi\\s+khac|goi\\s+khac\\s+di|"
            + "ngoai\\s+goi\\s+(?:nay|do|tren)\\s+(?:con|co)\\s+goi\\s+nao)\\b.*"
    );

    // 8. PRICE_OBJECTION
    private static final Pattern PRICE_OBJECTION_PATTERN = Pattern.compile(
            "(?isU).*\\b(đắt|hơi\\s+cao|cao\\s+thế|cao\\s+vậy|cao\\s+quá|đắt\\s+thế|đắt\\s+vậy|đắt\\s+quá|"
            + "vẫn\\s+đắt|nhiều\\s+tiền|chưa\\s+đủ\\s+ngân\\s+sách|chưa\\s+vừa\\s+túi|vượt\\s+ngân\\s+sách|hơi\\s+căng|mắc\\s+quá)\\b.*"
    );
    private static final Pattern PRICE_OBJECTION_ASCII_PATTERN = Pattern.compile(
            "(?is).*\\b(dat\\s+the|dat\\s+qua|van\\s+dat|hoi\\s+cao|cao\\s+qua|nhieu\\s+tien|chua\\s+du\\s+ngan\\s+sach)\\b.*"
    );

    private static final Pattern DURATION_IN_MESSAGE_PATTERN = Pattern.compile(
            "(?iU)\\b(20|30|40|45|60|70|75|80|90|100|120)\\s*(?:phút|phut|p)\\b"
    );

    public static String removeDiacritics(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String nfd = Normalizer.normalize(text, Normalizer.Form.NFD);
        String stripped = DIACRITICS_PATTERN.matcher(nfd).replaceAll("");
        return stripped.replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT).strip();
    }

    public static boolean isEntityMismatchCorrection(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String norm = Normalizer.normalize(message.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        String ascii = removeDiacritics(message);
        return ENTITY_MISMATCH_CORRECTION_PATTERN.matcher(norm).matches()
                || ENTITY_MISMATCH_CORRECTION_ASCII_PATTERN.matcher(ascii).matches();
    }

    public static boolean hasHarshPronouns(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String norm = Normalizer.normalize(message.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        String ascii = removeDiacritics(message);
        return HARSH_PRONOUN_PATTERN.matcher(norm).matches()
                || HARSH_PRONOUN_ASCII_PAIR_PATTERN.matcher(ascii).matches();
    }

    /**
     * Tách phần dịch vụ bị khách phản bác ("massage cổ" trong "mà mày đi tư vấn massage cổ là sao")
     * để hệ thống nhận diện đây là {@code rejectedWrongOffer}, tuyệt đối KHÔNG bắt làm dịch vụ khách chọn.
     */
    public static String extractRejectedWrongOffer(String message) {
        if (!isEntityMismatchCorrection(message)) {
            return null;
        }
        String norm = Normalizer.normalize(message.strip(), Normalizer.Form.NFC);
        for (Pattern p : MISMATCH_CRITIQUE_CLAUSE_PATTERNS) {
            Matcher m = p.matcher(norm);
            if (m.find() && m.groupCount() >= 1) {
                String candidate = m.group(1);
                if (candidate != null && !candidate.isBlank()) {
                    return candidate.strip();
                }
            }
        }
        return null;
    }

    /**
     * Loại bỏ mệnh đề phê phán/phản bác dịch vụ tư vấn sai khỏi câu nói của khách
     * trước khi trích xuất {@code extractedNeeds} và {@code targetScopes} (chống Keyword Hijacking).
     */
    public static String stripMismatchCritiqueClause(String message) {
        if (message == null || message.isBlank() || !isEntityMismatchCorrection(message)) {
            return message;
        }
        String cleaned = Normalizer.normalize(message.strip(), Normalizer.Form.NFC);
        for (Pattern p : MISMATCH_CRITIQUE_CLAUSE_PATTERNS) {
            cleaned = p.matcher(cleaned).replaceAll(" ");
        }
        cleaned = cleaned.replaceAll("\\s{2,}", " ").strip();
        return cleaned.isEmpty() ? message : cleaned;
    }

    public static boolean hasNeurologicalRisk(String message, ConversationState priorState) {
        if (message == null || message.isBlank()) {
            return priorState != null && priorState.neurologicalRisk();
        }
        String norm = Normalizer.normalize(message.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        String ascii = removeDiacritics(message);
        if (ACUTE_NEUROLOGICAL_RED_FLAG_PATTERN.matcher(norm).matches()
                || ACUTE_NEUROLOGICAL_ASCII_PATTERN.matcher(ascii).matches()) {
            return true;
        }
        boolean hasNumbnessOrDizziness = NUMBNESS_OR_DIZZINESS_PATTERN.matcher(norm).matches()
                || (priorState != null && priorState.knownSymptoms().stream()
                    .anyMatch(s -> s.contains("tê") || s.contains("chóng mặt") || s.contains("ù tai")));
        boolean hasSevereModifier = SEVERE_OR_PERSISTENT_MODIFIER_PATTERN.matcher(norm).matches();
        return hasNumbnessOrDizziness && hasSevereModifier;
    }

    public static boolean isNeurologicalRiskMessage(String message) {
        return hasNeurologicalRisk(message, null);
    }

    public static boolean isStrongRejection(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String norm = Normalizer.normalize(message.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        String ascii = removeDiacritics(message);
        return STRONG_REJECTION_PATTERN.matcher(norm).matches()
                || STRONG_REJECTION_ASCII_PATTERN.matcher(ascii).matches();
    }

    public static boolean isStrongRejectionMessage(String message) {
        return isStrongRejection(message);
    }

    public static boolean isTrialAcceptanceMessage(String message) {
        return isTrialAcceptance(message, null);
    }

    public static boolean isPackageRejectionMessage(String message) {
        return isPackageRejection(message);
    }

    public static boolean isExplicitDownsellRequest(String message) {
        return isExplicitDownsellRequest(message, null);
    }

    public static boolean isTrialAcceptance(String message, ConversationState priorState) {
        if (message == null || message.isBlank()) {
            return false;
        }
        if (isStrongRejection(message)) {
            return false;
        }
        String norm = Normalizer.normalize(message.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        String ascii = removeDiacritics(message);
        boolean matchesTrial = TRIAL_ACCEPTANCE_PATTERN.matcher(norm).matches()
                || TRIAL_ACCEPTANCE_ASCII_PATTERN.matcher(ascii).matches();
        if (!matchesTrial) {
            return false;
        }
        // Kiểm tra điều kiện RT-SPA-020: Chỉ classify TRIAL_ACCEPTANCE nếu message KHÔNG chỉ định
        // package/duration khác với held_package / downsell_package!
        String mentionedDur = extractDurationNumber(norm);
        if (mentionedDur != null && priorState != null) {
            String activeDur = priorState.downsellOffered() && priorState.downsellDuration() != null
                    ? priorState.downsellDuration()
                    : priorState.heldDuration();
            if (activeDur != null && !activeDur.equals(mentionedDur)) {
                return false;
            }
            if (activeDur == null && priorState.knownRecommendedPackage() != null
                    && !priorState.knownRecommendedPackage().contains(mentionedDur)) {
                return false;
            }
        }
        return true;
    }

    public static boolean isPackageRejection(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String norm = Normalizer.normalize(message.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        String ascii = removeDiacritics(message);
        return PACKAGE_REJECTION_PATTERN.matcher(norm).matches()
                || PACKAGE_REJECTION_ASCII_PATTERN.matcher(ascii).matches();
    }

    public static boolean isPriceEscalationRequest(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String norm = Normalizer.normalize(message.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        String ascii = removeDiacritics(message);
        return PRICE_ESCALATION_PATTERN.matcher(norm).matches()
                || PRICE_ESCALATION_ASCII_PATTERN.matcher(ascii).matches();
    }

    public static boolean isExplicitDownsellRequest(String message, ConversationState priorState) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String norm = Normalizer.normalize(message.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        String ascii = removeDiacritics(message);
        if (EXPLICIT_DOWNSELL_PATTERN.matcher(norm).matches()
                || EXPLICIT_DOWNSELL_ASCII_PATTERN.matcher(ascii).matches()) {
            return true;
        }
        // RT-SPA-020: "Có gói 30 phút thử xem sao không em?" khi held_package là 45 phút -> EXPLICIT_DOWNSELL_REQUEST
        boolean hasTrialPhrase = TRIAL_ACCEPTANCE_PATTERN.matcher(norm).matches()
                || TRIAL_ACCEPTANCE_ASCII_PATTERN.matcher(ascii).matches();
        String mentionedDur = extractDurationNumber(norm);
        if (hasTrialPhrase && mentionedDur != null && priorState != null) {
            String activeDur = priorState.downsellOffered() && priorState.downsellDuration() != null
                    ? priorState.downsellDuration()
                    : (priorState.heldDuration() != null ? priorState.heldDuration() : extractDurationNumber(priorState.knownRecommendedPackage()));
            if (activeDur != null) {
                try {
                    int mVal = Integer.parseInt(mentionedDur);
                    int aVal = Integer.parseInt(activeDur);
                    if (mVal < aVal) {
                        return true;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return false;
    }

    public static boolean isAlternativePackageRequest(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String norm = Normalizer.normalize(message.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        String ascii = removeDiacritics(message);
        return ALTERNATIVE_PACKAGE_REQUEST_PATTERN.matcher(norm).matches()
                || ALTERNATIVE_PACKAGE_REQUEST_ASCII_PATTERN.matcher(ascii).matches();
    }

    public static boolean isPriceObjection(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        if (PrimaryActionRouter.isUnitPriceComparisonMessage(message)) {
            return false;
        }
        String norm = Normalizer.normalize(message.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        String ascii = removeDiacritics(message);
        return PRICE_OBJECTION_PATTERN.matcher(norm).matches()
                || PRICE_OBJECTION_ASCII_PATTERN.matcher(ascii).matches();
    }

    private static final Pattern COMPREHENSION_FAILURE_PATTERN = Pattern.compile(
            "(?isU)^\\s*(?:dạ\\s+|ơ\\s+|ủa\\s+|mà\\s+|nhưng\\s+)?(?:(?:em|mình|tôi|chị|anh|đọc(?:\\s+mãi)?)\\s+)?(?:vẫn\\s+)?(?:"
            + "chả\\s+hiểu(?:\\s+(?:gì|j|sao|luôn|cả|hết))?"
            + "|chưa\\s+hiểu(?:\\s+(?:gì|j|lắm|ý|luôn))?"
            + "|không\\s+hiểu(?:\\s+(?:gì|j|sao|luôn|cả|hết|ý))?"
            + "|khó\\s+hiểu(?:\\s+(?:quá|thế|vậy|ghê))?"
            + "|nói\\s+(?:gì\\s+)?(?:vẫn\\s+)?(?:chả|không|chưa)\\s+hiểu"
            + "|giải\\s+thích\\s+(?:gì\\s+)?(?:vẫn\\s+)?(?:khó\\s+hiểu|chả\\s+hiểu|không\\s+hiểu|rối\\s+quá)"
            + "|đọc\\s+(?:vẫn\\s+)?(?:chả|không|chưa)\\s+hiểu"
            + ")(?:\\s+(?:gì|j|cả|luôn|hết|ạ|em|shop|vậy|thế))*\\s*[?!.,~]*$"
    );
    private static final Pattern COMPREHENSION_FAILURE_ASCII_PATTERN = Pattern.compile(
            "(?is)^\\s*(?:da\\s+|o\\s+|ua\\s+|ma\\s+|nhung\\s+)?(?:(?:em|minh|toi|chi|anh|doc(?:\\s+mai)?)\\s+)?(?:van\\s+)?(?:"
            + "cha\\s+hieu(?:\\s+(?:gi|j|sao|luon|ca|het))?"
            + "|chua\\s+hieu(?:\\s+(?:gi|j|lam|y|luon))?"
            + "|(?:khong|ko|k)\\s+hieu(?:\\s+(?:gi|j|sao|luon|ca|het|y))?"
            + "|kho\\s+hieu(?:\\s+(?:qua|the|vay|ghe))?"
            + "|noi\\s+(?:gi\\s+)?(?:van\\s+)?(?:cha|khong|ko|k|chua)\\s+hieu"
            + ")(?:\\s+(?:gi|j|ca|luon|het|a|em|shop|vay|the))*\\s*[?!.,~]*$"
    );

    private static final Pattern CONTRAST_OR_CLARIFICATION_PREFIX_PATTERN = Pattern.compile(
            "(?isU)^\\s*(?:không|ko|k|khg|kh|hổng|hong|không\\s+phải|k\\s+phải|ko\\s+phải|đâu\\s+phải)[,\\s]+(?:"
            + "(?:tôi|mình|em|chị|anh)\\s+(?:thấy|đọc|nhìn|đang\\s+hỏi|hỏi|muốn\\s+hỏi|thắc\\s+mắc)"
            + "|thấy\\s+(?:có|ghi|bảo|nói)"
            + "|ý\\s+(?:tôi|mình|em|chị|anh)\\s+(?:là|hỏi)"
            + ").*"
    );
    private static final Pattern CONTRAST_OR_CLARIFICATION_ASCII_PATTERN = Pattern.compile(
            "(?is)^\\s*(?:khong|ko|k|khg|kh|hong|khong\\s+phai|k\\s+phai|ko\\s+phai|dau\\s+phai)[,\\s]+(?:"
            + "(?:toi|minh|em|chi|anh)\\s+(?:thay|doc|nhin|dang\\s+hoi|hoi|muon\\s+hoi|thac\\s+mac)"
            + "|thay\\s+(?:co|ghi|bao|noi)"
            + "|y\\s+(?:toi|minh|em|chi|anh)\\s+(?:la|hoi)"
            + ").*"
    );

    private static final Pattern DETAIL_OR_FAQ_QUESTION_PATTERN = Pattern.compile(
            "(?isU).*(?:"
            + "\\b(?:thực\\s+dưỡng|dinh\\s+dưỡng|buffet|ăn\\s+nhẹ|điểm\\s+tâm|nước\\s+ép|thực\\s+đơn)\\b.*\\b(?:là\\s+(?:gì|j|sao|như\\s+thế\\s+nào|ntn|thế\\s+nào)|có\\s+(?:những\\s+)?(?:món\\s+)?(?:gì|j)|gồm\\s+(?:những\\s+)?(?:món\\s+)?(?:gì|j)|ăn\\s+(?:món\\s+)?(?:gì|j)|miễn\\s+phí|tính\\s+phí|thêm\\s+tiền|không|k|ko)\\b"
            + "|\\b(?:có\\s+món\\s+(?:gì|j)|ăn\\s+món\\s+(?:gì|j)|thực\\s+đơn\\s+(?:có|gồm|là)\\s+(?:những\\s+)?(?:món\\s+)?(?:gì|j)|buffet\\s+(?:có|gồm|là)\\s+(?:những\\s+)?(?:món\\s+)?(?:gì|j)|ăn\\s+nhẹ\\s+(?:có|gồm|là)\\s+(?:những\\s+)?(?:món\\s+)?(?:gì|j)|điểm\\s+tâm\\s+(?:có|gồm|là)\\s+(?:những\\s+)?(?:món\\s+)?(?:gì|j))\\b"
            + "|\\b(?:nó|đó|đấy|cái\\s+đó|cái\\s+này|phần\\s+đó|mục\\s+đó)\\s+là\\s+(?:cái\\s+)?(?:gì|j|sao|như\\s+thế\\s+nào|ntn)\\b"
            + "|\\b[\\p{L}\\d\\s]{2,70}\\s+là\\s+(?:cái\\s+)?(?:gì|j)\\b(?:\\s+(?:vậy|thế|hả|ạ|em|shop|nhỉ))*\\s*[?.!~]*$"
            + "|\\b(?:gói\\s+(?:này|đó|trên|\\d+\\s*(?:phút|p|k))|liệu\\s+trình\\s+(?:này|đó))?\\s*(?:bao\\s+gồm|gồm\\s+những|gồm\\s+các|trong\\s+gói\\s+có)\\s+(?:bước\\s+|món\\s+|dịch\\s+vụ\\s+)?(?:gì|j|nào)\\b"
            + "|\\b(?:quy\\s+trình|kỹ\\s+thuật|các\\s+bước|thao\\s+tác)\\s+(?:làm|massage|trị\\s+liệu|bấm\\s+huyệt|chăm\\s+sóc|gồm|như\\s+thế\\s+nào|ntn|thế\\s+nào|ra\\s+sao|mấy\\s+bước)\\b"
            + "|\\b(?:làm\\s+trong\\s+bao\\s+lâu|kéo\\s+dài\\s+bao\\s+lâu|thời\\s+lượng\\s+(?:bao\\s+lâu|mấy\\s+phút|bao\\s+nhiêu\\s+phút)|mấy\\s+phút\\s+vậy)\\b"
            + "|\\b(?:phòng\\s+(?:riêng|chung|vip|đôi|cộng\\s+đồng)|có\\s+rèm\\s+riêng|không\\s+gian\\s+(?:như\\s+thế\\s+nào|thế\\s+nào|riêng\\s+tư|chung|riêng)|đi\\s+2\\s+người.*?(?:phòng|cùng))\\b"
            + "|\\b(?:có\\s+(?:cần\\s+|phải\\s+|bắt\\s+buộc\\s+|phát\\s+sinh\\s+)?)?(?:ăn\\s+nhẹ|điểm\\s+tâm|buffet|thực\\s+dưỡng|nước\\s+ép|ngâm\\s+bồn|xông\\s+hơi|chườm\\s+ngải\\s+cứu|tẩy\\s+da\\s+chết|tẩy\\s+tế\\s+bào\\s+chết|chăm\\s+sóc\\s+da\\s+mặt|chọn\\s+ktv|chọn\\s+kỹ\\s+thuật\\s+viên|ktv\\s+nữ|kỹ\\s+thuật\\s+viên\\s+nữ|nhận\\s+tip|tiền\\s+tip|cần\\s+tip|tip\\s+thêm|phụ\\s+phí|phụ\\s+thu|đặt\\s+cọc|cọc\\s+trước)\\b.*\\b(?:miễn\\s+phí|không|k|ko|hông|chưa|sao|thế\\s+nào|ntn|ạ|nhỉ|shop|em)\\b"
            + "|\\b(?:gói\\s+cơ\\s+bản|gói\\s+vip|gói\\s+trị\\s+liệu|gói\\s+chuyên\\s+sâu)\\s+khác\\s+(?:gì|j|sao|như\\s+thế\\s+nào)\\b"
            + "|\\b(?:giờ\\s+nào\\s+vắng|khung\\s+giờ\\s+nào\\s+vắng|làm\\s+xong\\s+có\\s+bị\\s+đau)\\b"
            + ").*"
    );
    private static final Pattern DETAIL_OR_FAQ_QUESTION_ASCII_PATTERN = Pattern.compile(
            "(?is).*(?:"
            + "\\b(?:thuc\\s+duong|dinh\\s+duong|buffet|an\\s+nhe|diem\\s+tam|nuoc\\s+ep|thuc\\s+don)\\b.*\\b(?:la\\s+(?:gi|j|sao|nhu\\s+the\\s+nao|ntn|the\\s+nao)|co\\s+(?:nhung\\s+)?(?:mon\\s+)?(?:gi|j)|gom\\s+(?:nhung\\s+)?(?:mon\\s+)?(?:gi|j)|an\\s+(?:mon\\s+)?(?:gi|j)|mien\\s+phi|khong|k|ko)\\b"
            + "|\\b(?:co\\s+mon\\s+(?:gi|j)|an\\s+mon\\s+(?:gi|j)|thuc\\s+don\\s+(?:co|gom|la)\\s+(?:nhung\\s+)?(?:mon\\s+)?(?:gi|j)|buffet\\s+(?:co|gom|la)\\s+(?:nhung\\s+)?(?:mon\\s+)?(?:gi|j))\\b"
            + "|\\b(?:no|do|day|cai\\s+do|cai\\s+nay)\\s+la\\s+(?:cai\\s+)?(?:gi|j|sao|nhu\\s+the\\s+nao|ntn)\\b"
            + "|\\b(?:goi\\s+(?:nay|do|tren|\\d+\\s*(?:phut|p|k))|lieu\\s+trinh\\s+(?:nay|do))?\\s*(?:bao\\s+gom|gom\\s+nhung|trong\\s+goi\\s+co)\\s+(?:buoc\\s+|mon\\s+)?(?:gi|j|nao)\\b"
            + "|\\b(?:lam\\s+trong\\s+bao\\s+lau|keo\\s+dai\\s+bao\\s+lau|thoi\\s+luong\\s+(?:bao\\s+lau|may\\s+phut|bao\\s+nhieu\\s+phut))\\b"
            + "|\\b(?:phong\\s+(?:rieng|chung|vip|doi|cong\\s+dong)|co\\s+rem\\s+rieng|di\\s+2\\s+nguoi.*?(?:phong|cung))\\b"
            + "|\\b(?:co\\s+(?:can\\s+|phai\\s+|bat\\s+buoc\\s+|phat\\s+sinh\\s+)?)?(?:nhan\\s+tip|tien\\s+tip|can\\s+tip|phu\\s+phi|phu\\s+thu|dat\\s+coc|coc\\s+truoc)\\b.*\\b(?:khong|k|ko|chua|sao|the\\s+nao|ntn)\\b"
            + "|\\b(?:goi\\s+co\\s+ban|goi\\s+vip|goi\\s+tri\\s+lieu|goi\\s+chuyen\\s+sau)\\s+khac\\s+(?:gi|j|sao)\\b"
            + "|\\b(?:gio\\s+nao\\s+vang|khung\\s+gio\\s+nao\\s+vang|lam\\s+xong\\s+co\\s+bi\\s+dau)\\b"
            + ").*"
    );

    public static boolean isComprehensionFailureMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String norm = Normalizer.normalize(message.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        String ascii = removeDiacritics(message);
        return COMPREHENSION_FAILURE_PATTERN.matcher(norm).matches()
                || COMPREHENSION_FAILURE_ASCII_PATTERN.matcher(ascii).matches();
    }

    public static boolean hasContrastOrClarificationPrefix(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String norm = Normalizer.normalize(message.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        String ascii = removeDiacritics(message);
        return CONTRAST_OR_CLARIFICATION_PREFIX_PATTERN.matcher(norm).matches()
                || CONTRAST_OR_CLARIFICATION_ASCII_PATTERN.matcher(ascii).matches();
    }

    public static boolean isHardPackageRejection(String message) {
        return isStrongRejection(message) || isPackageRejection(message);
    }

    public static boolean isDetailOrFaqQuestion(String message) {
        return isDetailOrFaqQuestion(message, (ConversationState) null);
    }

    public static boolean isDetailOrFaqQuestion(
            String message,
            String lastAssistantMessage,
            String referencedBenefit,
            boolean hasPriorPriceOrPackageContext
    ) {
        if (isDetailOrFaqQuestion(message, lastAssistantMessage, referencedBenefit)) {
            return true;
        }
        if (!hasPriorPriceOrPackageContext || message == null || message.isBlank()) {
            return false;
        }
        String trimmed = message.strip();
        if (isHardPackageRejection(trimmed) || isPriceObjection(trimmed) || isComprehensionFailureMessage(trimmed)) {
            return false;
        }
        String norm = Normalizer.normalize(trimmed, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        return norm.matches("(?isU).*\\b(cái\\s+đó|cái\\s+này|phần\\s+đó|mục\\s+đó|nó)\\s+(?:là|gồm|có)\\s+.*");
    }

    public static boolean isDetailOrFaqQuestion(String message, String lastAssistantMessage, String referencedBenefit) {
        if (isDetailOrFaqQuestion(message, (ConversationState) null)) {
            return true;
        }
        if (message == null || message.isBlank()) {
            return false;
        }
        String trimmed = message.strip();
        if (isHardPackageRejection(trimmed)
                || isPriceObjection(trimmed)
                || isComprehensionFailureMessage(trimmed)) {
            return false;
        }
        String norm = Normalizer.normalize(trimmed, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        boolean hasContextRef = (lastAssistantMessage != null && !lastAssistantMessage.isBlank())
                || (referencedBenefit != null && !referencedBenefit.isBlank());
        return hasContextRef && norm.matches("(?isU).*\\b(nó|đó|cái\\s+đó|cái\\s+này|phần\\s+đó|mục\\s+đó)\\s+(?:là|gồm|có)\\s+(?:những\\s+)?(?:món\\s+|bước\\s+)?(?:gì|j|sao|ntn|như\\s+thế\\s+nào)\\b.*");
    }

    public static boolean isDetailOrFaqQuestion(String message, ConversationState priorState) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String trimmed = message.strip();
        String norm = Normalizer.normalize(trimmed, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        String ascii = removeDiacritics(trimmed);

        if (OPT_OUT_PATTERN.matcher(norm).matches()
                || OPT_OUT_ASCII_PATTERN.matcher(ascii).matches()
                || isStrongRejection(trimmed)
                || isPackageRejection(trimmed)
                || isPriceObjection(trimmed)
                || isExplicitDownsellRequest(trimmed, priorState)
                || isAlternativePackageRequest(trimmed)
                || isTrialAcceptance(trimmed, priorState)
                || isComprehensionFailureMessage(trimmed)) {
            return false;
        }
        // Do not hijack explicit price inquiries ("Gói này giá bao nhiêu?", "Giá sao shop?")
        if (PrimaryActionRouter.matchesRawPriceInquiryPattern(trimmed)
                && !norm.matches("(?isU).*\\b(thực\\s+dưỡng|buffet|ăn\\s+nhẹ|điểm\\s+tâm|có\\s+món\\s+gì|là\\s+gì|là\\s+j)\\b.*")) {
            return false;
        }
        if (DETAIL_OR_FAQ_QUESTION_PATTERN.matcher(norm).matches()
                || DETAIL_OR_FAQ_QUESTION_ASCII_PATTERN.matcher(ascii).matches()) {
            return true;
        }
        if (hasContrastOrClarificationPrefix(trimmed)
                && (norm.contains("?") || norm.matches("(?isU).*\\b(là\\s+gì|là\\s+j|là\\s+sao|món\\s+gì|như\\s+thế\\s+nào|ntn|gồm\\s+gì|không)\\b.*"))) {
            return true;
        }
        return false;
    }

    public static String extractReferencedDetailEntity(
            String message,
            String lastBotMessage,
            String activePackageName
    ) {
        String norm = message != null
                ? Normalizer.normalize(message.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT)
                : "";
        String ascii = removeDiacritics(message);
        String botNorm = lastBotMessage != null
                ? Normalizer.normalize(lastBotMessage.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT)
                : "";

        // 1. Check explicit food / menu / buffet / thực dưỡng questions
        boolean asksMenuItems = norm.matches("(?isU).*\\b(món\\s+gì|món\\s+j|những\\s+món\\s+gì|ăn\\s+gì|thực\\s+đơn|menu)\\b.*")
                || ascii.matches("(?is).*\\b(mon\\s+gi|mon\\s+j|nhung\\s+mon\\s+gi|an\\s+gi|thuc\\s+don|menu)\\b.*");
        boolean mentionsBuffetOrNutrition = norm.matches("(?isU).*\\b(thực\\s+dưỡng|dinh\\s+dưỡng|buffet|ăn\\s+nhẹ|điểm\\s+tâm|nước\\s+ép|trái\\s+cây|hoa\\s+quả)\\b.*")
                || ascii.matches("(?is).*\\b(thuc\\s+duong|dinh\\s+duong|buffet|an\\s+nhe|diem\\s+tam|nuoc\\s+ep|trai\\s+cay|hoa\\s+qua)\\b.*");
        if (asksMenuItems) {
            return "thực đơn buffet thực dưỡng";
        }
        if (mentionsBuffetOrNutrition) {
            if (norm.contains("nước ép") || ascii.contains("nuoc ep")) {
                return "nước ép sau liệu trình";
            }
            return "buffet thực dưỡng";
        }

        // 2. Check room / privacy / facility questions
        if (norm.matches("(?isU).*\\b(phòng\\s+riêng|phòng\\s+chung|phòng\\s+vip|phòng\\s+đôi|phòng\\s+cộng\\s+đồng|rèm\\s+riêng|không\\s+gian|2\\s+người)\\b.*")
                || ascii.matches("(?is).*\\b(phong\\s+rieng|phong\\s+chung|phong\\s+vip|phong\\s+doi|phong\\s+cong\\s+dong|rem\\s+rieng|khong\\s+gian)\\b.*")) {
            return "không gian phòng";
        }

        // 3. Check duration questions
        if (norm.matches("(?isU).*\\b(bao\\s+lâu|mấy\\s+phút|bao\\s+nhiêu\\s+phút|thời\\s+lượng|kéo\\s+dài)\\b.*")
                || ascii.matches("(?is).*\\b(bao\\s+lau|may\\s+phut|bao\\s+nhieu\\s+phut|thoi\\s+luong)\\b.*")) {
            return "thời lượng liệu trình";
        }

        // 4. Check specific service steps / modalities / policies
        if (norm.contains("ngâm bồn") || ascii.contains("ngam bon")) return "ngâm bồn";
        if (norm.contains("xông hơi") || ascii.contains("xong hoi")) return "xông hơi";
        if (norm.contains("ngải cứu") || ascii.contains("ngai cuu")) return "chườm ngải cứu";
        if (norm.contains("tẩy da chết") || norm.contains("tẩy tế bào chết") || ascii.contains("tay da chet")) return "tẩy da chết";
        if (norm.contains("da mặt") || ascii.contains("da mat")) return "chăm sóc da mặt";
        if (norm.contains("ktv") || norm.contains("kỹ thuật viên") || ascii.contains("ky thuat vien")) return "kỹ thuật viên";
        if (norm.contains("tip") || norm.contains("tiền bo") || norm.contains("phụ phí") || norm.contains("phụ thu")
                || ascii.contains("tien tip") || ascii.contains("phu phi")) return "chính sách tip";
        if (norm.contains("đặt cọc") || norm.contains("cọc trước") || ascii.contains("dat coc")) return "chính sách đặt cọc";
        if (norm.contains("giờ nào vắng") || ascii.contains("gio nao vang")) return "khung giờ vắng khách";
        if (norm.matches("(?isU).*\\b(gói\\s+cơ\\s+bản|gói\\s+vip|gói\\s+trị\\s+liệu|gói\\s+chuyên\\s+sâu)\\s+khác\\s+(?:gì|j|sao)\\b.*")) {
            return "sự khác biệt giữa các gói";
        }
        if (norm.matches("(?isU).*\\b(quy\\s+trình|kỹ\\s+thuật|bấm\\s+huyệt|các\\s+bước|thao\\s+tác)\\b.*")) {
            return "quy trình và kỹ thuật trị liệu";
        }
        if (norm.matches("(?isU).*\\b(bao\\s+gồm|gồm\\s+những\\s+gì|gồm\\s+gì|trong\\s+gói\\s+có)\\b.*")) {
            return "quyền lợi và các bước trong gói";
        }

        // 4B. Explicit definition question "<subject> là gì/j" (e.g. "Cạo vôi răng siêu âm là gì?")
        Matcher defSubjMatcher = Pattern.compile(
                "(?isU)^\\s*(?:dạ\\s+|cho\\s+hỏi\\s+|mình\\s+hỏi\\s+|tôi\\s+hỏi\\s+|shop\\s+ơi\\s+)?([\\p{L}\\d\\s]{2,65}?)\\s+là\\s+(?:cái\\s+)?(?:gì|j|sao|như\\s+thế\\s+nào|ntn)\\b"
        ).matcher(norm);
        if (defSubjMatcher.find()) {
            String rawSubj = defSubjMatcher.group(1)
                    .replaceAll("(?iU)\\b(?:bên\\s+mình|bên\\s+em|bên\\s+shop|ở\\s+đây|tại\\s+spa|trong\\s+gói|của\\s+gói)\\b", " ")
                    .replaceAll("\\s+", " ")
                    .strip();
            boolean isPronoun = rawSubj.matches("(?iU)^(?:nó|đó|đấy|cái\\s+đó|cái\\s+này|phần\\s+đó|mục\\s+đó|gói\\s+này|gói\\s+đó|liệu\\s+trình\\s+này)$");
            if (!isPronoun && rawSubj.length() >= 3) {
                return rawSubj;
            }
        }

        // 5. Pronoun / elliptical reference ("nó là j", "đó là gì") -> inspect lastBotMessage
        if (botNorm.contains("buffet thực dưỡng") || botNorm.contains("thực dưỡng") || botNorm.contains("ăn nhẹ") || botNorm.contains("điểm tâm")) {
            return "buffet thực dưỡng";
        }
        if (activePackageName != null && !activePackageName.isBlank()) {
            return "chi tiết " + activePackageName.strip();
        }
        return "chi tiết dịch vụ";
    }

    public static String extractDurationNumber(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher m = DURATION_IN_MESSAGE_PATTERN.matcher(text);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    public static DetectedIntent classify(String message, ConversationState priorState) {
        if (message == null || message.isBlank()) {
            return DetectedIntent.AMBIGUOUS;
        }
        String trimmed = message.strip();
        String lower = Normalizer.normalize(trimmed, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        String ascii = removeDiacritics(trimmed);

        if (PromptSecurityGuard.isDirectJailbreakAttempt(trimmed)) {
            return DetectedIntent.PROMPT_INJECTION;
        }
        // 1. SAFETY_RISK
        if (CustomerInsightEngine.hasHighRiskHealthSignal(lower)) {
            return DetectedIntent.SAFETY_RISK;
        }
        // 2. OPT_OUT
        if (OPT_OUT_PATTERN.matcher(lower).matches() || OPT_OUT_ASCII_PATTERN.matcher(ascii).matches()) {
            return DetectedIntent.OPT_OUT;
        }
        // 3. NEUROLOGICAL_RISK
        if (hasNeurologicalRisk(trimmed, priorState)) {
            return DetectedIntent.NEUROLOGICAL_RISK;
        }
        // 3A. DETAIL_OR_FAQ_QUESTION with contrast/clarification prefix ("K tôi thấy có buffet thực dưỡng, nó là j")
        // Checked before ENTITY_MISMATCH_CORRECTION so leading "K/Không" clarification questions are never mistaken for mismatch complaints.
        if (hasContrastOrClarificationPrefix(trimmed) && isDetailOrFaqQuestion(trimmed, priorState)) {
            return DetectedIntent.DETAIL_OR_FAQ_QUESTION;
        }
        // 3B. ENTITY_MISMATCH_CORRECTION (anti-keyword-hijacking: check before TRIAL/REJECTION/FRUSTRATION/SERVICE_SELECTION)
        if (isEntityMismatchCorrection(trimmed)) {
            return DetectedIntent.ENTITY_MISMATCH_CORRECTION;
        }
        // 4. TRIAL_ACCEPTANCE (only if no other package specified & not strong rejection)
        if (isTrialAcceptance(trimmed, priorState)) {
            return DetectedIntent.TRIAL_ACCEPTANCE;
        }
        // 5. STRONG_REJECTION
        if (isStrongRejection(trimmed)) {
            return DetectedIntent.STRONG_REJECTION;
        }
        // 6. PACKAGE_REJECTION
        if (isPackageRejection(trimmed)) {
            return DetectedIntent.PACKAGE_REJECTION;
        }
        // 6B. COMPREHENSION_FAILURE ("Chả hiểu j", "Khó hiểu quá")
        if (isComprehensionFailureMessage(trimmed)) {
            return DetectedIntent.COMPREHENSION_FAILURE;
        }
        // 7. FRUSTRATION
        if (PrimaryActionRouter.isFrustrationMessage(trimmed)) {
            return DetectedIntent.FRUSTRATION;
        }
        // 7B. DETAIL_OR_FAQ_QUESTION ("Thực dưỡng là j", "Buffet có món gì?", "Gói này gồm những gì?", "Phòng riêng hay chung?")
        if (isDetailOrFaqQuestion(trimmed, priorState)) {
            return DetectedIntent.DETAIL_OR_FAQ_QUESTION;
        }
        // 8. PRICE_ESCALATION_REQUEST
        if (isPriceEscalationRequest(trimmed)) {
            return DetectedIntent.PRICE_ESCALATION_REQUEST;
        }
        // 9. EXPLICIT_DOWNSELL_REQUEST
        if (isExplicitDownsellRequest(trimmed, priorState)) {
            return DetectedIntent.EXPLICIT_DOWNSELL_REQUEST;
        }
        // 9B. ALTERNATIVE_PACKAGE_REQUEST
        if (isAlternativePackageRequest(trimmed)) {
            return DetectedIntent.ALTERNATIVE_PACKAGE_REQUEST;
        }
        // 10. PRICE_OBJECTION
        if (isPriceObjection(trimmed)) {
            return DetectedIntent.PRICE_OBJECTION;
        }
        // 11. PRICE_COMPARISON
        if (PrimaryActionRouter.isUnitPriceComparisonMessage(trimmed)) {
            return DetectedIntent.PRICE_COMPARISON;
        }
        // 12. PRICE_INQUIRY
        if (PrimaryActionRouter.isPriceInquiryMessage(trimmed)) {
            return DetectedIntent.PRICE_INQUIRY;
        }
        // 13. SERVICE_SELECTION
        if (PrimaryActionRouter.isServiceFamilySelectionMessage(trimmed)) {
            return DetectedIntent.SERVICE_SELECTION;
        }
        // 14. SYMPTOM_DESCRIPTION
        if (PrimaryActionRouter.hasSymptomOrConditionSignal(trimmed)) {
            return DetectedIntent.SYMPTOM_DESCRIPTION;
        }
        // 15. ACK
        if (PrimaryActionRouter.isAcknowledgementMessage(trimmed)) {
            return DetectedIntent.ACK;
        }
        return DetectedIntent.AMBIGUOUS;
    }
}
