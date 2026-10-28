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
        CAUTIOUS_SYMPTOM_CLARIFICATION,
        SCOPE_CORRECTION_PUSHBACK,
        ENTITY_MISMATCH_CORRECTION,
        PUSHBACK_ON_ASSUMPTION,
        TRIAL_ACCEPTANCE,
        STRONG_REJECTION,
        PACKAGE_REJECTION,
        COMPREHENSION_FAILURE,
        META_CONVERSATION_ON_BOT_BEHAVIOR,
        ANTI_APOLOGY_PUSHBACK,
        POLITE_DEFERRAL_EXIT,
        CONFIRM_PREVIOUS_INFO,
        FRUSTRATION_DEMANDING_SOLUTION,
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
        GREETING_OR_START_CONSULTATION,
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

    // 2. NEUROLOGICAL_RISK (SAFE-NEURO-001)
    private static final Pattern ACUTE_NEUROLOGICAL_RED_FLAG_PATTERN = Pattern.compile(
            "(?isU).*\\b(đau\\s+đầu\\s+dữ\\s+dội|yếu\\s+tay|yếu\\s+chân|mất\\s+cảm\\s+giác|liệt\\s+tay|liệt\\s+chân|liệt\\s+nửa\\s+người|tê\\s+(?:bì\\s+)?nửa\\s+người|méo\\s+miệng|miệng\\s+méo|nói\\s+khó|nói\\s+ngọng|líu\\s+lưỡi|"
            + "chóng\\s+mặt\\s+nhiều|chóng\\s+mặt\\s+dữ\\s+dội|hoa\\s+mắt\\s+chóng\\s+mặt|chóng\\s+mặt\\s+(?:kèm\\s+)?hoa\\s+mắt|ù\\s+tai\\s+chóng\\s+mặt|"
            + "tê\\s+(?:tay\\s+|chân\\s+|bì\\s+)?lan\\b|lan\\s+xuống\\s+(?:cánh\\s+tay|bàn\\s+tay|tay|bắp\\s+chân|bàn\\s+chân|chân)|"
            + "chèn\\s+ép\\s+(?:rễ\\s+|dây\\s+)?thần\\s+kinh|thoát\\s+vị\\s+đĩa\\s+đệm)\\b.*"
    );
    private static final Pattern ACUTE_NEUROLOGICAL_ASCII_PATTERN = Pattern.compile(
            "(?is).*\\b(dau\\s+dau\\s+du\\s+doi|yeu\\s+tay|yeu\\s+chan|mat\\s+cam\\s+giac|hoa\\s+mat\\s+chong\\s+mat|meo\\s+mieng|noi\\s+kho|"
            + "te\\s+(?:tay\\s+|chan\\s+|bi\\s+)?lan\\b|lan\\s+xuong\\s+(?:canh\\s+tay|ban\\s+tay|tay|chan)|"
            + "chen\\s+ep\\s+(?:re\\s+|day\\s+)?than\\s+kinh|thoat\\s+vi\\s+dia\\s+dem)\\b.*"
    );

    // Tier 1 (HIGH_RISK_IMMEDIATE_HANDOFF): tê/yếu xuất hiện ĐỘT NGỘT, một bên cơ thể, hoặc kèm dấu hiệu đột quỵ / đau đầu dữ dội.
    private static final Pattern SUDDEN_ONSET_PATTERN = Pattern.compile(
            "(?isU).*\\b(đột\\s+ngột|dot\\s+ngot|bất\\s+ngờ|bat\\s+ngo|đột\\s+nhiên|dot\\s+nhien|bỗng\\s+dưng|bỗng\\s+nhiên|bong\\s+dung|vừa\\s+nãy|vừa\\s+xong|mới\\s+đây|tự\\s+dưng|tu\\s+dung|tự\\s+nhiên\\s+(?:bị|thấy)|vừa\\s+mới\\s+(?:bị|thấy)|mới\\s+bị\\s+vài\\s+(?:phút|giờ)|cách\\s+đây\\s+(?:ít|vài)\\s+(?:phút|giờ)|ngay\\s+lúc\\s+này)\\b.*"
    );
    private static final Pattern ONE_SIDED_PATTERN = Pattern.compile(
            "(?isU).*\\b(một\\s+bên|mot\\s+ben|nửa\\s+người|nua\\s+nguoi|nửa\\s+mặt|bên\\s+trái|bên\\s+phải|ben\\s+trai|ben\\s+phai|1\\s+bên)\\b.*"
    );
    private static final Pattern STROKE_LIKE_SIGN_PATTERN = Pattern.compile(
            "(?isU).*\\b(méo\\s+miệng|miệng\\s+(?:hơi\\s+|bị\\s+|đang\\s+)?méo|méo\\s+mặt|mặt\\s+(?:hơi\\s+|bị\\s+)?méo|nói\\s+khó|nói\\s+ngọng|nói\\s+lắp|líu\\s+lưỡi|mờ\\s+mắt\\s+(?:đột|bất)|nhìn\\s+đôi|nhìn\\s+mờ\\s+đột|mất\\s+thăng\\s+bằng|không\\s+đứng\\s+vững|đau\\s+đầu\\s+(?:dữ\\s+dội|kinh\\s+khủng|như\\s+búa\\s+bổ)|đau\\s+đầu\\s+đột\\s+ngột)\\b.*"
    );
    private static final Pattern SEVERE_HEADACHE_PATTERN = Pattern.compile(
            "(?isU).*\\b(đau\\s+đầu|nhức\\s+đầu)\\b.*\\b(dữ\\s+dội|kinh\\s+khủng|không\\s+chịu\\s+nổi)\\b.*|.*\\b(dữ\\s+dội|kinh\\s+khủng)\\b.*\\b(đau\\s+đầu|nhức\\s+đầu)\\b.*"
    );
    private static final Pattern NUMBNESS_OR_DIZZINESS_PATTERN = Pattern.compile(
            "(?isU).*(?:\\b(tê\\s+tay|tê\\s+chân|tê\\s+bì|tê\\s+tê|chóng\\s+mặt|ù\\s+tai|hoa\\s+mắt)\\b|\\b(?:chân|tay|ngón)\\s+(?:\\p{L}+\\s+){0,3}tê\\b).*"
    );
    private static final Pattern SEVERE_OR_PERSISTENT_MODIFIER_PATTERN = Pattern.compile(
            "(?isU).*\\b(dữ\\s+dội|nặng\\s+lắm|rất\\s+nặng|khó\\s+chịu\\s+nhiều|liên\\s+tục|mấy\\s+tuần|mấy\\s+tháng|mất\\s+cảm\\s+giác|yếu)\\b.*"
    );

    // 2B. ENTITY_MISMATCH_CORRECTION & Anti-Keyword-Hijacking
    private static final Pattern ENTITY_MISMATCH_CORRECTION_PATTERN = Pattern.compile(
            "(?isU).*(?:"
            + "\\b(?:đau|mỏi|nhức|tê|bị|hỏi|cần|mua|muốn|tìm)\\b.+?\\bmà\\s+(?:mày|bạn|em|shop|bên\\s+em|lại|đi|sao)\\b.*?(?:tư\\s+vấn|gợi\\s+ý|báo\\s+giá|giới\\s+thiệu|massage|làm)"
            + "|\\b(?:tư\\s+vấn|gợi\\s+ý|báo\\s+giá|giới\\s+thiệu|massage)\\b[^.?!;]*?\\b(?:là\\s+sao|làm\\s+gì|làm\\s+j)\\b"
            + "|\\bsao\\s+(?:lại\\s+|em\\s+|mày\\s+|bạn\\s+|shop\\s+|bên\\s+em\\s+|đi\\s+)*(?:tư\\s+vấn|gợi\\s+ý|báo\\s+giá|giới\\s+thiệu|massage)\\b"
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
            + "\\b(?:dau|moi|nhuc|te|bi|hoi|can|mua|muon|tim)\\b.+?\\bma\\s+(?:may|ban|em|shop|ben\\s+em|lai|di|sao)\\b.*?(?:tu\\s+van|goi\\s+y|bao\\s+gia|gioi\\s+thieu|massage|lam)"
            + "|\\b(?:tu\\s+van|goi\\s+y|bao\\s+gia|gioi\\s+thieu|massage)\\b[^.?!;]*?\\b(?:la\\s+sao|lam\\s+gi|lam\\s+j)\\b"
            + "|\\bsao\\s+(?:lai\\s+|em\\s+|may\\s+|ban\\s+|shop\\s+|ben\\s+em\\s+|di\\s+)*(?:tu\\s+van|goi\\s+y|bao\\s+gia|gioi\\s+thieu|massage)\\b"
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
            Pattern.compile("(?isU)\\bmà\\s+(?:mày|bạn|em|shop|bên\\s+em|lại|đi|sao)\\b[^,.?!;]*?\\b(?:tư\\s+vấn|gợi\\s+ý|báo\\s+giá|giới\\s+thiệu|massage|làm)\\s+([^,.?!;]+?)(?:\\s+(?:là\\s+sao|làm\\s+gì|làm\\s+j|vậy|thế|hả|ạ|nhỉ))?(?=\\s+(?:trong\\s+khi|khi\\s+mà|chứ\\b)|[,.;?!]|$)"),
            Pattern.compile("(?isU)(?:\\b(?:mày|bạn|em|shop|bên\\s+em|lại|đi)\\s+)*(?:đi\\s+|lại\\s+)*\\b(?:tư\\s+vấn|gợi\\s+ý|báo\\s+giá|giới\\s+thiệu|massage)\\s+([^,.?!;]+?)\\s+(?:là\\s+sao|làm\\s+gì|làm\\s+j)\\b(?:\\s+(?:vậy|thế|hả|ạ|nhỉ))?"),
            Pattern.compile("(?isU)\\bsao\\s+(?:lại\\s+|em\\s+|mày\\s+|bạn\\s+|shop\\s+|bên\\s+em\\s+|đi\\s+)*\\b(?:tư\\s+vấn|gợi\\s+ý|báo\\s+giá|giới\\s+thiệu|massage)\\s+([^,.?!;]+?)(?:\\s+(?:là\\s+sao|làm\\s+gì|làm\\s+j|vậy|thế|hả|ạ|nhỉ))?(?=\\s+(?:trong\\s+khi|khi\\s+mà|mà\\b|chứ\\b)|[,.;?!]|$)"),
            Pattern.compile("(?isU)\\b(?:chứ\\s+(?:có\\s+phải|không\\s+phải|đâu\\s+phải)\\s*(?:hỏi|bị|đau|mỏi|cần|mua|tư\\s+vấn)?|(?:đâu\\s+có|không\\s+có)\\s+(?:hỏi|bị|đau|mỏi|cần|mua|muốn|tìm)|có\\s+(?:hỏi|cần|mua|muốn|tìm))\\s+([^,.?!;]+?)(?:\\s+đâu(?:\\s+ạ|\\s+em|\\s+shop)?\\b|(?=\\s+(?:trong\\s+khi|mà\\b|chứ\\b)|[,.;?!]|$))"),
            Pattern.compile("(?isU)(?:\\b(?:mày|bạn|em|shop|bên\\s+em)\\s+)?\\b(?:tư\\s+vấn|gợi\\s+ý|báo\\s+giá|giới\\s+thiệu)\\s+(?:nhầm|sai|lạc\\s+đề|không\\s+đúng|trật)\\s*(?:sang|qua|vào|gói|dịch\\s+vụ)*\\s*([^,.?!;]*?)(?:\\s+(?:rồi|vậy|thế|ạ|nhỉ))?(?=\\s+(?:trong\\s+khi|khi\\s+mà|mà\\b|chứ\\b)|[,.;?!]|$)")
    };

    // 3. STRONG_REJECTION (NEVER match standalone "thôi")
    private static final Pattern STRONG_REJECTION_PATTERN = Pattern.compile(
            "(?isU).*\\b(thôi\\s+khỏi(?:\\s+cần)?|thôi\\s+không\\s+cần|thôi\\s+không\\s+làm|không\\s+cần\\s+nữa|"
            + "không\\s+làm\\s+nữa|không\\s+mua\\s+đâu|thôi\\s+để\\s+sau|thôi\\s+bỏ\\s+đi|khỏi\\s+cần\\s+tư\\s+vấn|"
            + "tụt\\s+hứng|mất\\s+hứng|hết\\s+hứng|thôi\\s+dẹp|dẹp\\s+đi|thôi\\s+nghỉ|nghỉ\\s+khỏe|hết\\s+muốn\\s+làm|"
            + "chán\\s+quá\\s+thôi)\\b.*"
    );
    private static final Pattern STRONG_REJECTION_ASCII_PATTERN = Pattern.compile(
            "(?is).*\\b(thoi\\s+khoi(?:\\s+can)?|thoi\\s+khong\\s+can|thoi\\s+khong\\s+lam|khong\\s+mua\\s+dau|thoi\\s+de\\s+sau|"
            + "tut\\s+hung|mat\\s+hung|het\\s+hung|thoi\\s+dep|dep\\s+di|thoi\\s+nghi|nghi\\s+khoe|het\\s+muon\\s+lam|"
            + "chan\\s+qua\\s+thoi)\\b.*"
    );

    // 3B (v3.3.4 / v3.9). WORDING / EXPRESSION / REPETITION FEEDBACK — khách bắt bẻ cách dùng từ hoặc phê bình bot nhại lời/lặp câu
    // (ví dụ: "Đau lưng chứ đau lòng đâu mà nhẹ nhõm", "Nhại đi nhạu lại câu này k biết chán à", "Ai nói là 'em vui mừng khi…' nghe gà vậy").
    private static final Pattern WORDING_OR_EXPRESSION_FEEDBACK_PATTERN = Pattern.compile(
            "(?isU).*(?:\\bchứ\\s+(?:có\\s+phải\\s+)?đau\\s+lòng\\s+đâu\\b"
            + "|\\bđau\\s+lòng\\s+đâu\\s+mà\\s+(?:nhẹ\\s+nhõm|an\\s+ủi|tâm\\s+sự)\\b"
            + "|\\b(?:cơ\\s+thể|đau\\s+lưng|mỏi\\s+gối|tê\\s+tay|nhức\\s+mỏi).*?\\bchứ\\s+.*?\\bđâu\\s+mà\\s+(?:nhẹ\\s+nhõm|tâm\\s+hồn|buồn)\\b"
            + "|\\bdùng\\s+từ\\s+.*?\\bnghe\\s+(?:cứ\\s+như|giống)\\b"
            + "|\\b(?:nghe\\s+|nói\\s+chuyện\\s+|tư\\s+vấn\\s+|giọng\\s+|văn\\s+)?(?:thảo\\s+mai|sến\\s+súa|sến\\s+quá|sến\\s+vãi|giả\\s+tạo|kịch\\s+quá|diễn\\s+quá|dịch\\s+máy|ngô\\s+nghê)\\b"
            + "|\\b(?:nhại\\s+đi\\s+nhại\\s+lại|nhại\\s+đi\\s+nhạu\\s+lại|cứ\\s+nhại|nhại\\s+lại|nhại\\s+lời|lặp\\s+đi\\s+lặp\\s+lại)\\b"
            + "|\\bcâu\\s+này\\s+(?:không|k|ko|chả)\\s+biết\\s+chán\\b"
            + "|\\bhiểu\\s+(?:gì|j)\\s+mà\\s+cứ\\s+(?:nhại|lặp|hỏi|nói)\\b"
            + "|\\b(?:không\\s+ai|k\\s+ai|ko\\s+ai|chả\\s+ai|ai\\s+lại|ai)\\s+nói\\s+là\\b"
            + "|\\bem\\s+vui\\s+mừng\\s+khi\\b"
            + "|\\b(?:nghe\\s+|nói\\s+|dùng\\s+từ\\s+)?gà\\s+(?:vậy|thế|quá)\\b).*"
    );

    private static final Pattern ALREADY_ANSWERED_SLOT_PUSHBACK_PATTERN = Pattern.compile(
            "(?isU).*(?:\\b(?:ơ|ủa|ơ\\s+hay|ủa\\s+ơ)\\s*[,]?\\s*(?:đã\\s+|vừa\\s+|tôi\\s+|mình\\s+)?(?:bảo|nói|trả\\s+lời)\\b.*?\\b(?:mà|rồi)\\b"
            + "|\\b(?:đã|vừa)\\s+(?:bảo|nói|trả\\s+lời)\\s+(?:là\\s+|ở\\s+trên\\s+)?.*?\\b(?:rồi|mà)\\b"
            + "|\\bnói\\s+ở\\s+trên\\s+rồi\\b).*"
    );

    // 3C (v3.3.4). NEGATIVE / FRUSTRATED MOOD hướng vào CÁCH TƯ VẤN (chưa phải STRONG_REJECTION).
    // "khó chịu" / "nhức đầu quá" chỉ được tính khi hướng vào cuộc trò chuyện, vì trong miền Spa
    // hai cụm này thường là mô tả TRIỆU CHỨNG ("mỏi cổ khó chịu lắm") chứ không phải bực bội với Bot.
    private static final Pattern NEGATIVE_OR_FRUSTRATED_MOOD_PATTERN = Pattern.compile(
            "(?isU).*\\b(linh\\s+tinh|quái\\s+gì|lằng\\s+nhằng|vòng\\s+vo|hỏi\\s+nhiều|hỏi\\s+lắm|"
            + "hỏi\\s+mãi|trả\\s+lời\\s+chán|tư\\s+vấn\\s+chán|bực\\s+mình|mất\\s+thời\\s+gian|"
            + "không\\s+hiểu\\s+à|nói\\s+mãi\\s+không\\s+hiểu|sao\\s+lâu\\s+thế|rắc\\s+rối\\s+quá|phiền\\s+quá|"
            + "mệt\\s+với\\s+em|sao\\s+phức\\s+tạp\\s+thế|"
            + "khó\\s+chịu\\s+(?:với|vì)\\s+(?:em|shop|bên\\s+em|cách)|"
            + "(?:nói\\s+chuyện|tư\\s+vấn|trả\\s+lời|hỏi\\s+han)\\s+(?:gì\\s+mà\\s+)?khó\\s+chịu|"
            + "(?:nghe|đọc)\\s+nhức\\s+đầu|nhức\\s+đầu\\s+quá\\s+với\\s+(?:em|shop|bên\\s+em))\\b.*"
    );

    // 3D (v3.3.4). MID-FUNNEL LUKEWARM — trả lời cụt lủn/hời hợt khi đã qua giai đoạn đầu.
    // Chia 2 nhóm: (a) tín hiệu chần chừ/nguội ("để xem", "vậy à", "tính sau") luôn được tính;
    // (b) tín hiệu xác nhận trơn ("ừ", "ok", "cũng được") chỉ tính khi Bot KHÔNG có câu hỏi đang chờ trả lời,
    //     vì "ok" sau câu hỏi giữ lịch / mời thử là CHẤP NHẬN chứ không phải hời hợt.
    private static final Pattern MID_FUNNEL_HESITANT_PATTERN = Pattern.compile(
            "(?isU)^\\s*(?:dạ\\s+)?(?:(?:ừ|ừm|ờ|ờm|uh|uhm|uk|ukm|ok|oke|okay)[,\\s]+)?"
            + "(?:vậy\\s+à|vậy\\s+hả|thế\\s+à|thế\\s+hả|à\\s+vậy|à\\s+ừ|"
            + "để\\s+xem(?:\\s+đã|\\s+sao|\\s+thêm)?|để\\s+(?:chị|anh|mình|tôi|em)\\s+xem(?:\\s+đã|\\s+thêm)?|"
            + "để\\s+tính(?:\\s+sau)?|biết\\s+rồi|xem\\s+đã|tính\\s+sau)"
            + "(?:\\s+(?:em|nha|nhé|ạ))?[\\s.!?…]*$"
    );
    private static final Pattern MID_FUNNEL_PLAIN_ACK_PATTERN = Pattern.compile(
            "(?isU)^\\s*(?:dạ\\s+)?(?:ừ|ừm|ờ|ờm|uh|uhm|uk|ukm|ok|oke|okay|cũng\\s+được|cũng\\s+ổn|nghe\\s+cũng\\s+được)"
            + "(?:\\s+(?:em|nha|nhé|ạ))?[\\s.!?…]*$"
    );

    // 4. TRIAL_ACCEPTANCE
    private static final Pattern TRIAL_ACCEPTANCE_PATTERN = Pattern.compile(
            "(?isU).*\\b(thử\\s+xem\\s+sao|thử\\s+xem|làm\\s+thử|trải\\s+nghiệm\\s+thử|ok\\s+thử\\s+1\\s+buổi|"
            + "thử\\s+1\\s+buổi|thử\\s+một\\s+buổi|đăng\\s+ký\\s+thử|trải\\s+nghiệm\\s+1\\s+buổi|"
            + "nghe\\s+(?:cũng\\s+)?(?:ổn|được|hợp\\s+lý|hay|thích)(?:\\s+hơn)?(?:\\s+(?:đó|đấy|nhỉ|ha|ạ|nhé|nha|rồi))?|"
            + "(?:ổn|hợp\\s+lý|được)\\s+hơn\\s+(?:đó|đấy|rồi)|"
            + "(?:cũng\\s+)?(?:ổn|hợp\\s+lý)\\s+(?:đó|đấy)(?:\\s+(?:em|ạ|nha|nhé))?)\\b.*"
    );
    private static final Pattern TRIAL_ACCEPTANCE_ASCII_PATTERN = Pattern.compile(
            "(?is).*\\b(thu\\s+xem\\s+sao|lam\\s+thu|trai\\s+nghiem\\s+thu|ok\\s+thu\\s+1\\s+buoi|thu\\s+1\\s+buoi|dang\\s+ky\\s+thu|"
            + "nghe\\s+(?:cung\\s+)?(?:on|duoc|hop\\s+ly|hay|thich)(?:\\s+hon)?(?:\\s+(?:do|day|nhi|ha|a|nhe|nha|roi))?|"
            + "(?:on|hop\\s+ly|duoc)\\s+hon\\s+(?:do|day|roi)|"
            + "(?:cung\\s+)?(?:on|hop\\s+ly)\\s+(?:do|day)(?:\\s+(?:em|a|nha|nhe))?)\\b.*"
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
            "(?isU).*\\b(đắt|hơi\\s+cao|cao\\s+hơn|đắt\\s+hơn|cao\\s+thế|cao\\s+vậy|cao\\s+quá|đắt\\s+thế|đắt\\s+vậy|đắt\\s+quá|"
            + "vẫn\\s+đắt|nhiều\\s+tiền|chưa\\s+đủ\\s+ngân\\s+sách|chưa\\s+vừa\\s+túi|vượt\\s+ngân\\s+sách|hơi\\s+căng|mắc\\s+quá)\\b.*"
    );
    private static final Pattern PRICE_OBJECTION_ASCII_PATTERN = Pattern.compile(
            "(?is).*\\b(dat\\s+the|dat\\s+qua|dat\\s+hon|van\\s+dat|hoi\\s+cao|cao\\s+hon|cao\\s+qua|(?<!bao\\s)nhieu\\s+tien|chua\\s+du\\s+ngan\\s+sach)\\b.*"
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

    private static final String NEGATED_BODY_SCOPE_OPT =
            "(?:\\s+(?:vùng|ở|phần|khớp|cơ|cả|hai|2)?\\s*(?:cổ\\s+vai\\s+gáy|vai\\s+gáy|cổ\\s+gáy|cổ\\s+vai|bàn\\s+chân|bắp\\s+chân|thắt\\s+lưng|cánh\\s+tay|bàn\\s+tay|đầu\\s+gối|cổ|vai|gáy|lưng|chân|tay|đầu|gối|người|cơ))?";

    private static final Pattern NEGATED_SYMPTOM_CLAUSE_PATTERN = Pattern.compile(
            "(?isU)(?:"
            + "\\b(?:căng(?:\\s+cơ)?|mỏi|đau|nhức|tê)" + NEGATED_BODY_SCOPE_OPT + "\\s+(?:gì|j|đâu)(?:\\s+đâu)?(?:\\s+mà\\s+(?:căng|mỏi|đau|nhức|tê)" + NEGATED_BODY_SCOPE_OPT + ")?\\b"
            + "|\\b(?:tôi|mình|em|chị|anh)?\\s*(?:có\\s+|đâu\\s+có\\s+)?(?:bị\\s+)?(?:căng(?:\\s+cơ)?|mỏi|đau|nhức|tê)" + NEGATED_BODY_SCOPE_OPT + "\\s+đâu\\b"
            + "|\\b(?:chứ\\s+)?(?:đâu\\s+có|không\\s+có|không\\s+hề|chẳng\\s+có|chả\\s+có|làm\\s+gì\\s+có|đâu\\s+phải|không\\s+phải|không|ko|k|chưa)\\s+(?:bị\\s+)?(?:căng(?:\\s+cơ)?|mỏi|đau|nhức|tê)" + NEGATED_BODY_SCOPE_OPT + "(?:\\s+đâu)?\\b"
            + ")"
    );
    private static final Pattern SYMPTOM_PUSHBACK_ASCII_PATTERN = Pattern.compile(
            "(?is).*(?:"
            + "\\b(?:cang|moi|dau|nhuc|te)(?:\\s+(?:co|vai|gay|lung|chan|tay|dau|goi))?\\s+(?:gi|j|dau)\\b"
            + "|\\b(?:toi|minh|em|chi|anh)?\\s*(?:co\\s+|dau\\s+co\\s+)?(?:bi\\s+)?(?:cang|moi|dau|nhuc|te)(?:\\s+(?:co|vai|gay|lung|chan|tay|dau|goi))?\\s+dau\\b"
            + "|\\b(?:dau\\s+co|khong\\s+co|khong|ko|k)\\s+(?:bi\\s+)?(?:cang|moi|dau|nhuc|te)(?:\\s+(?:co|vai|gay|lung|chan|tay|dau|goi))?(?:\\s+dau)?\\b"
            + ").*"
    );

    public static java.util.List<String> extractNegatedSymptomClauses(String message) {
        if (message == null || message.isBlank()) {
            return java.util.List.of();
        }
        String norm = Normalizer.normalize(message.strip(), Normalizer.Form.NFC);
        Matcher m = NEGATED_SYMPTOM_CLAUSE_PATTERN.matcher(norm);
        java.util.List<String> clauses = new java.util.ArrayList<>();
        while (m.find()) {
            clauses.add(m.group().strip());
        }
        return clauses;
    }

    public static String stripNegatedSymptomClauses(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        String norm = Normalizer.normalize(message.strip(), Normalizer.Form.NFC);
        String stripped = NEGATED_SYMPTOM_CLAUSE_PATTERN.matcher(norm).replaceAll(" ");
        return stripped.replaceAll("[,.;?!]+", " ").replaceAll("\\s{2,}", " ").strip();
    }

    public static boolean isNegationOrPushbackOnAssumption(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        if (isConfirmPreviousInfoMessage(message) || isMetaConversationOnBotBehavior(message)) {
            return false;
        }
        String norm = Normalizer.normalize(message.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        String ascii = removeDiacritics(message);
        // Must contain a negated symptom/condition clause ("căng gì", "tôi bị căng đâu", "không bị căng", "đâu có mỏi")
        boolean hasNegatedSymptom = NEGATED_SYMPTOM_CLAUSE_PATTERN.matcher(norm).find()
                || SYMPTOM_PUSHBACK_ASCII_PATTERN.matcher(ascii).matches();
        if (!hasNegatedSymptom) {
            return false;
        }
        // Exclude messages that explicitly critique a wrong service recommendation ("tư vấn", "gợi ý", "báo giá", "trong khi")
        if (norm.matches("(?isU).*\\b(tư\\s+vấn|gợi\\s+ý|báo\\s+giá|giới\\s+thiệu|trong\\s+khi|chứ\\s+không\\s+phải\\s+hỏi)\\b.*")) {
            return false;
        }
        // Check if anything positive remains after stripping the negated symptom clause(s)
        String remainder = stripNegatedSymptomClauses(norm)
                .replaceAll("(?iU)\\b(tôi|mình|em|chị|anh|shop|ơi|ạ|hả|vậy|thế|mà|có|bị|đâu|không|ko|k|gì|j)\\b", " ")
                .replaceAll("\\s+", " ")
                .strip();
        return remainder.isEmpty();
    }

    public static boolean isScopeCorrectionPushback(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String norm = Normalizer.normalize(message.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        // Explicit service recommendation critique ("mà mày đi tư vấn/massage ... là sao/làm j") stays on ENTITY_MISMATCH_CORRECTION
        if (norm.matches("(?isU).*\\bmà\\s+(?:mày|bạn|em|shop|bên\\s+em|lại|đi|sao)\\b.*?(?:tư\\s+vấn|gợi\\s+ý|báo\\s+giá|giới\\s+thiệu|massage|làm).*")
                || norm.matches("(?isU).*\\b(?:tư\\s+vấn|gợi\\s+ý|báo\\s+giá|giới\\s+thiệu|massage)\\b[^.?!;]*?\\b(?:là\\s+sao|làm\\s+gì|làm\\s+j)\\b.*")) {
            return false;
        }
        VietnameseScopeSignalExtractor.ScopeSignalResult sig = VietnameseScopeSignalExtractor.extract(message, null);
        return sig.explicitCorrectionGroupD() || sig.possibleCorrection();
    }

    public static boolean isFrustrationDemandingSolution(String message, ConversationState priorState) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String norm = Normalizer.normalize(message.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        String normQuery = com.example.docmind.service.text.VietnameseQueryNormalizer.normalizeForClassification(message);
        boolean explicitDemandOrScold = norm.matches("(?isU).*(?:\\bdở\\s+vừa\\b|\\bđịnh\\s+chăm\\s+sóc\\b|\\bchăm\\s+sóc\\s+cái\\b|\\blàm\\s+được\\s+gì\\b|\\bgiải\\s+quyết\\s+thế\\s+nào\\b|\\bcó\\s+phương\\s+án\\s+gì\\b|\\bt\\s+phát\\s+bực\\b|\\btôi\\s+phát\\s+bực\\b|\\blàm\\s+ăn\\s+chán\\b|\\btư\\s+vấn\\s+chán\\b).*")
                || normQuery.matches("(?isU).*(?:\\bdở\\s+vừa\\b|\\bđịnh\\s+chăm\\s+sóc\\b|\\bchăm\\s+sóc\\s+cái\\b|\\blàm\\s+được\\s+gì\\b|\\bgiải\\s+quyết\\s+thế\\s+nào\\b|\\bcó\\s+phương\\s+án\\s+gì\\b|\\bphát\\s+bực\\b|\\blàm\\s+ăn\\s+chán\\b|\\btư\\s+vấn\\s+chán\\b).*");
        if (explicitDemandOrScold) {
            return true;
        }
        if (PrimaryActionRouter.isFrustrationMessage(message) && priorState != null) {
            boolean hasPriorScopeCorrection = (priorState.forbiddenScopes() != null && !priorState.forbiddenScopes().isEmpty())
                    || (priorState.apologizedErrorIds() != null && !priorState.apologizedErrorIds().isEmpty())
                    || priorState.possibleCorrection();
            if (hasPriorScopeCorrection && priorState.mostRecentUnresolvedRequest() == null) {
                return true;
            }
        }
        return false;
    }

    public static boolean isEntityMismatchCorrection(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        if (isConfirmPreviousInfoMessage(message)
                || isMetaConversationOnBotBehavior(message)
                || isNegationOrPushbackOnAssumption(message)) {
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
        if (message == null || message.isBlank()) {
            return message;
        }
        String cleaned = Normalizer.normalize(message.strip(), Normalizer.Form.NFC);
        if (isEntityMismatchCorrection(message)) {
            for (Pattern p : MISMATCH_CRITIQUE_CLAUSE_PATTERNS) {
                cleaned = p.matcher(cleaned).replaceAll(" ");
            }
        }
        cleaned = NEGATED_SYMPTOM_CLAUSE_PATTERN.matcher(cleaned).replaceAll(" ");
        cleaned = cleaned.replaceAll("\\s{2,}", " ").strip();
        return cleaned.isEmpty() ? "" : cleaned;
    }

    /**
     * Tầng 1 — {@code HIGH_RISK_IMMEDIATE_HANDOFF}: dấu hiệu cấp tính nguy cơ cao. Ưu tiên hơn mọi nhánh bán hàng/báo giá/làm rõ.
     * Gồm: tê/yếu xuất hiện đột ngột; tê/yếu một bên cơ thể kèm đột ngột hoặc đau đầu; dấu hiệu kiểu đột quỵ
     * (méo miệng, nói khó, rối loạn thị giác, mất thăng bằng đột ngột), đau đầu dữ dội; hoặc các cờ đỏ cấp tính khác.
     * Không yêu cầu khách phải dùng đúng chữ "yếu liệt".
     */
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
        if (STROKE_LIKE_SIGN_PATTERN.matcher(norm).matches() || SEVERE_HEADACHE_PATTERN.matcher(norm).matches()) {
            return true;
        }
        boolean numbOrWeak = NUMBNESS_OR_DIZZINESS_OR_WEAK_PATTERN.matcher(norm).matches();
        if (!numbOrWeak) {
            return false;
        }
        boolean sudden = SUDDEN_ONSET_PATTERN.matcher(norm).matches() || SUDDEN_ONSET_PATTERN.matcher(ascii).matches();
        boolean oneSided = ONE_SIDED_PATTERN.matcher(norm).matches() || ONE_SIDED_PATTERN.matcher(ascii).matches();
        boolean headacheOrVertigo = norm.matches("(?isU).*\\b(đau\\s+đầu|nhức\\s+đầu|chóng\\s+mặt|hoa\\s+mắt|nửa\\s+người)\\b.*");
        return sudden || (oneSided && headacheOrVertigo);
    }

    private static final Pattern NUMBNESS_OR_DIZZINESS_OR_WEAK_PATTERN = Pattern.compile(
            "(?isU).*\\b(tê\\s+(?:tay|chân|bì|mặt|nửa|một|bên|người|cứng|rần|buốt)|tê\\b|yếu\\s+(?:tay|chân|nửa|một|bên|người|đi)|liệt|mất\\s+cảm\\s+giác)\\b.*"
    );

    /**
     * Tầng 2 — {@code CAUTIOUS_SYMPTOM_CLARIFICATION}: khách nêu tê/chóng mặt/ù tai... NHƯNG chưa có dấu hiệu cấp tính rõ ràng.
     * Không kết luận cấp cứu, cũng không coi là lành tính: trấn an, giải thích lý do thận trọng, hỏi thời điểm khởi phát,
     * bên bị ảnh hưởng, diễn biến; tuyệt đối không báo giá/chào gói.
     */
    public static boolean hasCautiousNeuroSymptom(String message, ConversationState priorState) {
        if (hasNeurologicalRisk(message, priorState)) {
            return false;
        }
        if (priorState != null
                && ("CAUTIOUS_SYMPTOM_CLARIFICATION".equals(priorState.lastIntent())
                    || priorState.conditionAskedCount() >= 1
                    || priorState.conditionClarified())) {
            return false;
        }
        if (message != null && !message.isBlank()) {
            String norm = Normalizer.normalize(message.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
            if (NUMBNESS_OR_DIZZINESS_PATTERN.matcher(norm).matches()) {
                return true;
            }
        }
        return false;
    }

    public static boolean isNeurologicalRiskMessage(String message) {
        return hasNeurologicalRisk(message, null);
    }

    public static boolean isStrongRejection(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalizedMsg = com.example.docmind.service.text.VietnameseQueryNormalizer.normalizeForClassification(message);
        String candidate = (normalizedMsg != null && !normalizedMsg.isBlank()) ? normalizedMsg : message;
        String norm = Normalizer.normalize(candidate.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        String ascii = removeDiacritics(candidate);
        return STRONG_REJECTION_PATTERN.matcher(norm).matches()
                || STRONG_REJECTION_ASCII_PATTERN.matcher(ascii).matches();
    }

    public static boolean isStrongRejectionMessage(String message) {
        return isStrongRejection(message);
    }

    /** v3.3.4 / v3.9: Khách bắt bẻ cách dùng từ hoặc phê bình bot nhại lời/lặp câu ("đau lưng chứ đau lòng đâu mà nhẹ nhõm", "nhại đi nhạu lại", "ai nói là 'em vui mừng khi…'"). */
    public static boolean isWordingOrExpressionFeedback(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String norm = Normalizer.normalize(message.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        if (WORDING_OR_EXPRESSION_FEEDBACK_PATTERN.matcher(norm).matches()) {
            return true;
        }
        String normalizedMsg = com.example.docmind.service.text.VietnameseQueryNormalizer.normalizeForClassification(message);
        if (normalizedMsg != null && !normalizedMsg.isBlank()) {
            String normClass = Normalizer.normalize(normalizedMsg.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
            return WORDING_OR_EXPRESSION_FEEDBACK_PATTERN.matcher(normClass).matches();
        }
        return false;
    }

    /** v3.9: Khách nhắc lại rằng mình đã trả lời thông tin đó ở lượt trước ("ơ bảo thi thoảmg bị mà", "đã bảo rồi mà"). */
    public static boolean isAlreadyAnsweredSlotPushback(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String norm = Normalizer.normalize(message.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        if (ALREADY_ANSWERED_SLOT_PUSHBACK_PATTERN.matcher(norm).matches()) {
            return true;
        }
        String normalizedMsg = com.example.docmind.service.text.VietnameseQueryNormalizer.normalizeForClassification(message);
        if (normalizedMsg != null && !normalizedMsg.isBlank()) {
            String normClass = Normalizer.normalize(normalizedMsg.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
            return ALREADY_ANSWERED_SLOT_PUSHBACK_PATTERN.matcher(normClass).matches();
        }
        return false;
    }

    private static final Pattern MOOD_DROP_RECOVERY_PATTERN = Pattern.compile(
            "(?isU).*\\b(tụt\\s+cảm\\s+xúc|mất\\s+cảm\\s+xúc|tụt\\s+mood|cụt\\s+hứng|chán\\s+chả\\s+buồn\\s+nói|chán\\s+không\\s+buồn\\s+nói|nghe\\s+mà\\s+chán)\\b.*"
    );

    /**
     * v3.5.0: Khách bày tỏ sự tụt cảm xúc / chán nản ("Tôi thấy tụt cảm xúc quá chán chả buồn nói", "tụt mood")
     * do câu trả lời trước của bot chưa khéo (nhưng chưa phải từ chối dứt khoát "thôi dẹp").
     */
    public static boolean isMoodDropRecoveryMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        if (isStrongRejection(message)) {
            return false;
        }
        String normalized = com.example.docmind.service.text.VietnameseQueryNormalizer.normalizeForClassification(message);
        String candidate = (normalized != null && !normalized.isBlank()) ? normalized : message;
        String norm = Normalizer.normalize(candidate.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        return MOOD_DROP_RECOVERY_PATTERN.matcher(norm).matches();
    }

    /**
     * v3.3.4: Cảm xúc tiêu cực / bực bội / mất kiên nhẫn với cách tư vấn, nhưng CHƯA phải từ chối dứt khoát.
     * Từ chối dứt khoát luôn trả về {@code false} để không bị kéo vào luồng xoa dịu tiếp tục bán hàng.
     */
    public static boolean isNegativeOrFrustratedMood(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        if (isStrongRejection(message)) {
            return false;
        }
        String normalized = com.example.docmind.service.text.VietnameseQueryNormalizer.normalizeForClassification(message);
        String candidate = (normalized != null && !normalized.isBlank()) ? normalized : message;
        String norm = Normalizer.normalize(candidate.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        return NEGATIVE_OR_FRUSTRATED_MOOD_PATTERN.matcher(norm).matches()
                || isWordingOrExpressionFeedback(message);
    }

    /**
     * v3.3.4: Trả lời hời hợt / tụt nhiệt độ mua hàng ở giữa hoặc cuối phễu.
     * <ul>
     *   <li>Chỉ áp dụng khi đã qua giai đoạn đầu: đã chào VÀ (đã rõ tình trạng HOẶC đã báo giá HOẶC đã gợi ý gói).</li>
     *   <li>Nếu Bot vừa hỏi tình trạng ({@code ASK_CONDITION}) và chưa báo giá, câu ngắn của khách là CÂU TRẢ LỜI, không phải hời hợt.</li>
     *   <li>Xác nhận trơn ("ok", "ừ", "cũng được") chỉ là hời hợt khi Bot không có câu hỏi quyết định đang chờ.</li>
     *   <li>Từ chối dứt khoát không bao giờ là hời hợt.</li>
     * </ul>
     */
    public static boolean isMidFunnelLukewarmReply(String message, ConversationState state) {
        if (message == null || message.isBlank() || state == null) {
            return false;
        }
        // v3.4.0 Bước 3.1: Nếu khách đã chê đắt ở lượt trước và chưa downsell, câu phân vân "Để xem đã"
        // phải đi vào HANDLE_PRICE_OBJECTION_REPEAT (Nhịp 2 Downsell 239k), không giữ ở LUKEWARM_REENGAGE.
        if (state.objectionCount() >= 1 && !state.downsellOffered()) {
            return false;
        }
        boolean isPastInitialStage = state.greetingSent()
                && (state.conditionClarified() || state.priceDiscussed() || state.packageRecommended());
        if (!isPastInitialStage) {
            return false;
        }
        String pendingQuestion = state.lastBotQuestionType();
        if ("ASK_CONDITION".equals(pendingQuestion) && !state.priceDiscussed()) {
            return false;
        }
        if (isStrongRejection(message)) {
            return false;
        }
        if (hasPositiveServiceInterestSignal(message)) {
            return false;
        }
        String norm = Normalizer.normalize(message.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        if (MID_FUNNEL_HESITANT_PATTERN.matcher(norm).matches()) {
            return true;
        }
        if (state.trialInviteOffered()
                || state.downsellOffered()
                || state.packageInterestAccepted()
                || state.objectionCount() >= 1
                || "COLLECTING_INFO".equals(state.bookingStatus())) {
            return false;
        }
        boolean botHasPendingDecisionQuestion = pendingQuestion != null && !"NONE".equals(pendingQuestion);
        return !botHasPendingDecisionQuestion && MID_FUNNEL_PLAIN_ACK_PATTERN.matcher(norm).matches();
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
        if (isStrongRejection(message)
                || isPoliteDeferralExit(message)
                || FanpageBotService.isHesitationOrDeferralMessage(message)
                || (priorState != null
                    && FanpageBotService.isBareAcknowledgmentMessage(message)
                    && FanpageBotService.isPauseOrDeferralBotReply(priorState.lastAssistantMessage()))
                || isWordingOrExpressionFeedback(message)
                || isConversationStyleComplaint(message)
                || isAlreadyAnsweredSlotPushback(message)
                || isMetaConversationOnBotBehavior(message)
                || isAntiApologyPushback(message)
                || PrimaryActionRouter.isFrustrationMessage(message)) {
            return false;
        }
        String norm = Normalizer.normalize(message.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        if (norm.matches("(?isU).*\\bkhông\\s+ạ\\s*\\?\\s*$")) {
            return false;
        }
        String ascii = removeDiacritics(message);
        boolean hasOfferedOrDiscussedPkg = priorState != null
                && (priorState.trialInviteOffered()
                    || priorState.downsellOffered()
                    || priorState.packageInterestAccepted()
                    || priorState.objectionCount() >= 1
                    || "COLLECTING_INFO".equals(priorState.bookingStatus())
                    || "INVITE_ONE_SESSION_TRIAL".equals(priorState.lastQuestionIntent()));
        boolean isAckAfterTrialOrDownsell = hasOfferedOrDiscussedPkg
                && PrimaryActionRouter.isAcknowledgementMessage(message);
        boolean matchesTrial = TRIAL_ACCEPTANCE_PATTERN.matcher(norm).matches()
                || TRIAL_ACCEPTANCE_ASCII_PATTERN.matcher(ascii).matches()
                || (hasPositiveServiceInterestSignal(message) && (priorState == null || priorState.packageRecommended() || priorState.priceDiscussed() || hasOfferedOrDiscussedPkg))
                || isAckAfterTrialOrDownsell;
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

    private static final Pattern HESITATION_AFTER_OBJECTION_PATTERN = Pattern.compile(
            "(?isU).*\\b(cân\\s+nhắc(?:\\s+thêm)?|suy\\s+nghĩ(?:\\s+thêm)?|để\\s+(?:tôi|t|mình|chị|anh|em)?\\s*(?:xem|nghĩ|tính|cân\\s+nhắc|suy\\s+nghĩ|tham\\s+khảo)(?:\\s+thêm|\\s+đã|\\s+lại)?|"
            + "có\\s+gì\\s+(?:tôi|t|mình|chị|anh|em)?\\s*(?:nhắn|báo|liên\\s+hệ)\\s+(?:sau|lại)|"
            + "thôi\\s+để\\s+lúc\\s+khác|để\\s+lúc\\s+khác|để\\s+hôm\\s+khác|thôi\\s+để\\s+sau|"
            + "vẫn\\s+chưa\\s+muốn\\s+làm|chưa\\s+muốn\\s+làm|chưa\\s+quyết\\s+định)\\b.*"
    );
    private static final Pattern HESITATION_AFTER_OBJECTION_ASCII_PATTERN = Pattern.compile(
            "(?is).*\\b(can\\s+nhac(?:\\s+them)?|suy\\s+nghi(?:\\s+them)?|de\\s+(?:toi|t|minh|chi|anh|em)?\\s*(?:xem|nghi|tinh|can\\s+nhac|suy\\s+nghi|tham\\s+khao)(?:\\s+them|\\s+da|\\s+lai)?|"
            + "co\\s+gi\\s+(?:toi|t|minh|chi|anh|em)?\\s*(?:nhan|bao|lien\\s+he)\\s+(?:sau|lai)|"
            + "thoi\\s+de\\s+luc\\s+khac|de\\s+luc\\s+khac|de\\s+hom\\s+khac|thoi\\s+de\\s+sau|"
            + "van\\s+chua\\s+muon\\s+lam|chua\\s+muon\\s+lam|chua\\s+quyet\\s+dinh)\\b.*"
    );

    private static final Pattern POLITE_DEFERRAL_EXIT_PATTERN = Pattern.compile(
            "(?isU).*\\b(cân\\s+nhắc\\s+thêm|suy\\s+nghĩ\\s+thêm|"
            + "để\\s+(?:tôi|t|mình|chị|anh|em)\\s*(?:cân\\s+nhắc|suy\\s+nghĩ|xem\\s+lại|tính\\s+lại|tham\\s+khảo)(?:\\s+thêm|\\s+đã|\\s+nhé|\\s+nha)?|"
            + "để\\s+xem\\s+đã\\s+(?:nhé|nha)|"
            + "có\\s+gì\\s+(?:tôi|t|mình|chị|anh|em)?\\s*(?:nhắn|báo|liên\\s+hệ)\\s+(?:sau|lại))\\b.*"
    );
    private static final Pattern POLITE_DEFERRAL_EXIT_ASCII_PATTERN = Pattern.compile(
            "(?is).*\\b(can\\s+nhac\\s+them|suy\\s+nghi\\s+them|"
            + "de\\s+(?:toi|t|minh|chi|anh|em)\\s*(?:can\\s+nhac|suy\\s+nghi|xem\\s+lai|tinh\\s+lai|tham\\s+khao)(?:\\s+them|\\s+da|\\s+nhe|\\s+nha)?|"
            + "de\\s+xem\\s+da\\s+(?:nhe|nha)|"
            + "co\\s+gi\\s+(?:toi|t|minh|chi|anh|em)?\\s*(?:nhan|bao|lien\\s+he)\\s+(?:sau|lai))\\b.*"
    );

    public static boolean isPoliteDeferralExit(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalizedMsg = com.example.docmind.service.text.VietnameseQueryNormalizer.normalizeForClassification(message.strip());
        String target = (normalizedMsg != null && !normalizedMsg.isBlank()) ? normalizedMsg.strip() : message.strip();
        String norm = Normalizer.normalize(target, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        String ascii = removeDiacritics(target);
        if (OPT_OUT_PATTERN.matcher(norm).matches() || OPT_OUT_ASCII_PATTERN.matcher(ascii).matches()) {
            return false;
        }
        return POLITE_DEFERRAL_EXIT_PATTERN.matcher(norm).matches()
                || POLITE_DEFERRAL_EXIT_ASCII_PATTERN.matcher(ascii).matches();
    }

    public static boolean isHesitationAfterObjection(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String norm = Normalizer.normalize(message.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        String ascii = removeDiacritics(message);
        if (OPT_OUT_PATTERN.matcher(norm).matches() || OPT_OUT_ASCII_PATTERN.matcher(ascii).matches()) {
            return false;
        }
        return HESITATION_AFTER_OBJECTION_PATTERN.matcher(norm).matches()
                || HESITATION_AFTER_OBJECTION_ASCII_PATTERN.matcher(ascii).matches();
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

    private static final Pattern GREETING_META_COMMENT_PATTERN = Pattern.compile(
            "(?isU).*\\b(sao\\s+chào\\s+(?:lắm|nhiều|mãi)\\s+(?:thế|vậy)|chào\\s+(?:lắm|nhiều|mãi|hoài)\\s+(?:thế|vậy)|"
            + "chào\\s+mấy\\s+lần|chào\\s+tận\\s+\\d+\\s+lần|sao\\s+chào\\s+hoài)\\b.*"
    );
    private static final Pattern GREETING_META_COMMENT_ASCII_PATTERN = Pattern.compile(
            "(?is).*\\b(sao\\s+chao\\s+(?:lam|nhieu|mai)\\s+(?:the|vay)|chao\\s+(?:lam|nhieu|mai|hoai)\\s+(?:the|vay)|"
            + "chao\\s+may\\s+lan|chao\\s+tan\\s+\\d+\\s+lan)\\b.*"
    );

    private static final Pattern APOLOGY_META_COMMENT_PATTERN = Pattern.compile(
            "(?isU).*\\b(mắc\\s+gì\\s+(?:phải\\s+|cứ\\s+|lại\\s+|mà\\s+)?xin\\s+lỗi|"
            + "làm\\s+gì\\s+mà\\s+(?:phải\\s+|cứ\\s+)?xin\\s+lỗi|sao\\s+(?:lại\\s+|phải\\s+|cứ\\s+)?xin\\s+lỗi|"
            + "việc\\s+gì\\s+(?:mà\\s+)?phải\\s+xin\\s+lỗi|có\\s+gì\\s+đâu\\s+mà\\s+(?:phải\\s+)?xin\\s+lỗi|"
            + "không\\s+cần\\s+(?:phải\\s+)?xin\\s+lỗi|cần\\s+gì\\s+(?:phải\\s+)?xin\\s+lỗi|"
            + "xin\\s+lỗi\\s+(?:gì\\s+lắm|lắm|nhiều|mãi|hoài)(?:\\s+(?:thế|vậy))?)\\b.*"
    );
    private static final Pattern APOLOGY_META_COMMENT_ASCII_PATTERN = Pattern.compile(
            "(?is).*\\b(mac\\s+(?:gi|j)\\s+(?:phai\\s+|cu\\s+|lai\\s+|ma\\s+)?xin\\s+loi|"
            + "lam\\s+gi\\s+ma\\s+(?:phai\\s+|cu\\s+)?xin\\s+loi|sao\\s+(?:lai\\s+|phai\\s+|cu\\s+)?xin\\s+loi|"
            + "viec\\s+gi\\s+(?:ma\\s+)?phai\\s+xin\\s+loi|co\\s+gi\\s+dau\\s+ma\\s+(?:phai\\s+)?xin\\s+loi|"
            + "khong\\s+can\\s+(?:phai\\s+)?xin\\s+loi|can\\s+gi\\s+(?:phai\\s+)?xin\\s+loi|"
            + "xin\\s+loi\\s+(?:gi\\s+lam|lam|nhieu|mai|hoai)(?:\\s+(?:the|vay))?)\\b.*"
    );

    private static final Pattern POSITIVE_SERVICE_INTEREST_SIGNAL_PATTERN = Pattern.compile(
            "(?isU).*\\b(nghe\\s+(?:cũng\\s+)?(?:hay|thích|ổn|hợp\\s+lý|hấp\\s+dẫn|được|thú\\s+vị)(?:\\s+hơn)?\\s*(?:đó|đấy|nhỉ|thế|vậy|ha|ạ|rồi)?|"
            + "(?:ổn|hợp\\s+lý|được)\\s+hơn\\s*(?:đó|đấy|rồi)|"
            + "(?:cũng\\s+)?(?:hay|thú\\s+vị|thích|ổn|hợp\\s+lý|được)\\s+(?:đó|đấy))\\b.*"
    );
    private static final Pattern POSITIVE_SERVICE_INTEREST_SIGNAL_ASCII_PATTERN = Pattern.compile(
            "(?is).*\\b(nghe\\s+(?:cung\\s+)?(?:hay|thich|on|hop\\s+ly|hap\\s+dan|duoc|thu\\s+vi)(?:\\s+hon)?\\s*(?:do|day|nhi|the|vay|ha|a|roi)?|"
            + "(?:on|hop\\s+ly|duoc)\\s+hon\\s*(?:do|day|roi)|"
            + "(?:cung\\s+)?(?:hay|thu\\s+vi|thich|on|hop\\s+ly|duoc)\\s+(?:do|day))\\b.*"
    );

    private static final Pattern CONFIRM_PREVIOUS_INFO_PATTERN = Pattern.compile(
            "(?isU).*\\b(hiểu\\s+đúng\\s+(?:rồi|ý)(?:\\s+còn\\s+gì)?|đúng\\s+rồi\\s+còn\\s+gì|"
            + "có\\s+phải\\s+hiểu\\s+(?:lầm|nhầm)\\s+đâu|đâu\\s+có\\s+hiểu\\s+(?:lầm|nhầm)|"
            + "không\\s+phải\\s+hiểu\\s+(?:lầm|nhầm)\\s+đâu|đúng\\s+ý\\s+(?:tôi|mình|chị|anh)\\s+rồi|"
            + "(?:bạn|em)\\s+hiểu\\s+đúng\\s+rồi)\\b.*"
    );
    private static final Pattern CONFIRM_PREVIOUS_INFO_ASCII_PATTERN = Pattern.compile(
            "(?is).*\\b(hieu\\s+dung\\s+(?:roi|y)(?:\\s+con\\s+gi)?|dung\\s+roi\\s+con\\s+gi|"
            + "co\\s+phai\\s+hieu\\s+(?:lam|nham)\\s+dau|dau\\s+co\\s+hieu\\s+(?:lam|nham)|"
            + "khong\\s+phai\\s+hieu\\s+(?:lam|nham)\\s+dau|dung\\s+y\\s+(?:toi|minh|chi|anh)\\s+roi)\\b.*"
    );

    private static final Pattern GREETING_OR_START_CONSULTATION_PATTERN = Pattern.compile(
            "(?isU)^\\s*(?:dạ\\s+)?(?:tư\\s+vấn(?:\\s+giúp(?:\\s+mình|\\s+tôi|\\s+em)?)?|xin\\s+tư\\s+vấn|cần\\s+tư\\s+vấn|"
            + "chào\\s+shop|xin\\s+chào|hi(?:\\s+shop)?|hello(?:\\s+shop)?|alo(?:\\s+shop)?|shop\\s+ơi)\\s*(?:ạ|nhé|nha)?\\s*[!.?~]*$"
    );
    private static final Pattern GREETING_OR_START_CONSULTATION_ASCII_PATTERN = Pattern.compile(
            "(?is)^\\s*(?:da\\s+)?(?:tu\\s+van(?:\\s+giup(?:\\s+minh|\\s+toi|\\s+em)?)?|xin\\s+tu\\s+van|can\\s+tu\\s+van|"
            + "chao\\s+shop|xin\\s+chao|hi(?:\\s+shop)?|hello(?:\\s+shop)?|alo(?:\\s+shop)?|shop\\s+oi)\\s*(?:a|nhe|nha)?\\s*[!.?~]*$"
    );

    public static boolean isGreetingMetaComment(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String norm = Normalizer.normalize(message.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        String ascii = removeDiacritics(message);
        return GREETING_META_COMMENT_PATTERN.matcher(norm).matches()
                || GREETING_META_COMMENT_ASCII_PATTERN.matcher(ascii).matches();
    }

    public static boolean isApologyMetaComment(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalizedMsg = com.example.docmind.service.text.VietnameseQueryNormalizer.normalizeForClassification(message.strip());
        String target = (normalizedMsg != null && !normalizedMsg.isBlank()) ? normalizedMsg.strip() : message.strip();
        String norm = Normalizer.normalize(target, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        String ascii = removeDiacritics(target);
        String rawAscii = removeDiacritics(message);
        return APOLOGY_META_COMMENT_PATTERN.matcher(norm).matches()
                || APOLOGY_META_COMMENT_ASCII_PATTERN.matcher(ascii).matches()
                || APOLOGY_META_COMMENT_ASCII_PATTERN.matcher(rawAscii).matches();
    }

    public static boolean isAntiApologyPushback(String normalizedMessage) {
        return isApologyMetaComment(normalizedMessage);
    }

    private static final Pattern CONVERSATION_STYLE_COMPLAINT_PATTERN = Pattern.compile(
            "(?isU).*(?:(?:nói\\s+chuyện|trả\\s+lời|cách\\s+nói|cách\\s+trả\\s+lời|giọng\\s+(?:điệu|văn)|tư\\s+vấn)[^.!?]{0,30}(?:kỳ|kì|lạ|dở|chán|máy\\s+móc|robot|cụt|cụt\\s+ngủn|khô\\s+khan|thiếu\\s+sức\\s+sống|nhạt\\s+nhẽo|vô\\s+hồn|lủng\\s+củng|vô\\s+duyên|khó\\s+chịu)"
            + "|thiếu\\s+sức\\s+sống|khô\\s+khan|cụt\\s+ngủn|nhạt\\s+nhẽo|vô\\s+hồn|máy\\s+móc|như\\s+robot|như\\s+máy|văn\\s+mẫu|hỏi\\s+(?:ngược|lại)\\s+(?:vô\\s+duyên|hoài)|lặp\\s+đi\\s+lặp\\s+lại"
            + "|hỏi\\s+kiểu|ép\\s+khách|chèo\\s+kéo).*"
    );

    /**
     * v3.8: khách góp ý văn phong / chất lượng giao tiếp (khác hẳn phản đối giá). Khi xuất hiện cùng lúc với chê đắt trong
     * một lượt, ưu tiên xử lý góp ý giao tiếp và KHÔNG tự hạ gói.
     */
    public static boolean isConversationStyleComplaint(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String norm = Normalizer.normalize(message.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        if (CONVERSATION_STYLE_COMPLAINT_PATTERN.matcher(norm).matches()) {
            return true;
        }
        String normalizedMsg = com.example.docmind.service.text.VietnameseQueryNormalizer.normalizeForClassification(message.strip());
        if (normalizedMsg != null && !normalizedMsg.isBlank()) {
            String normClass = Normalizer.normalize(normalizedMsg.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
            return CONVERSATION_STYLE_COMPLAINT_PATTERN.matcher(normClass).matches();
        }
        return false;
    }

    public static boolean isMetaConversationOnBotBehavior(String message) {
        return isGreetingMetaComment(message) || isApologyMetaComment(message);
    }

    public static boolean hasPositiveServiceInterestSignal(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String norm = Normalizer.normalize(message.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        String ascii = removeDiacritics(message);
        return POSITIVE_SERVICE_INTEREST_SIGNAL_PATTERN.matcher(norm).matches()
                || POSITIVE_SERVICE_INTEREST_SIGNAL_ASCII_PATTERN.matcher(ascii).matches();
    }

    public static boolean isConfirmPreviousInfoMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String norm = Normalizer.normalize(message.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        String ascii = removeDiacritics(message);
        return CONFIRM_PREVIOUS_INFO_PATTERN.matcher(norm).matches()
                || CONFIRM_PREVIOUS_INFO_ASCII_PATTERN.matcher(ascii).matches();
    }

    public static boolean isGreetingOrStartConsultationMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String norm = Normalizer.normalize(message.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        String ascii = removeDiacritics(message);
        return GREETING_OR_START_CONSULTATION_PATTERN.matcher(norm).matches()
                || GREETING_OR_START_CONSULTATION_ASCII_PATTERN.matcher(ascii).matches();
    }

    private static final Pattern DETAIL_OR_FAQ_QUESTION_PATTERN = Pattern.compile(
            "(?isU).*(?:"
            + "\\b(?:thực\\s+dưỡng|dinh\\s+dưỡng|buffet|ăn\\s+nhẹ|điểm\\s+tâm|nước\\s+ép|thực\\s+đơn)\\b.*\\b(?:là\\s+(?:gì|j|sao|như\\s+thế\\s+nào|ntn|thế\\s+nào)|có\\s+(?:những\\s+)?(?:món\\s+)?(?:gì|j)|gồm\\s+(?:những\\s+)?(?:món\\s+)?(?:gì|j)|ăn\\s+(?:món\\s+)?(?:gì|j)|miễn\\s+phí|tính\\s+phí|thêm\\s+tiền|không|k|ko)\\b"
            + "|\\b(?:có\\s+món\\s+(?:gì|j)|ăn\\s+món\\s+(?:gì|j)|thực\\s+đơn\\s+(?:có|gồm|là)\\s+(?:những\\s+)?(?:món\\s+)?(?:gì|j)|buffet\\s+(?:có|gồm|là)\\s+(?:những\\s+)?(?:món\\s+)?(?:gì|j)|ăn\\s+nhẹ\\s+(?:có|gồm|là)\\s+(?:những\\s+)?(?:món\\s+)?(?:gì|j)|điểm\\s+tâm\\s+(?:có|gồm|là)\\s+(?:những\\s+)?(?:món\\s+)?(?:gì|j))\\b"
            + "|\\b(?:nó|đó|đấy|cái\\s+đó|cái\\s+này|phần\\s+đó|mục\\s+đó)\\s+là\\s+(?:cái\\s+)?(?:gì|j|sao|như\\s+thế\\s+nào|ntn)\\b"
            + "|\\b[\\p{L}\\d\\s]{2,70}\\s+là\\s+(?:[\\p{L}\\d]+\\s+){0,2}(?:cái\\s+|loại\\s+|những\\s+|lá\\s+|vị\\s+)?(?:gì|j|nào)\\b(?:\\s+(?:vậy|thế|hả|ạ|em|shop|nhỉ))*\\s*[?.!~]*$"
            + "|\\b(?:ngâm|xông|dùng|gồm)\\s+(?:những\\s+)?(?:loại|lá|vị|thảo\\s+dược)\\s+(?:gì|j|nào)\\b"
            + "|\\b(?:gói\\s+(?:này|đó|trên|\\d+\\s*(?:phút|p|k))|liệu\\s+trình\\s+(?:này|đó))?\\s*(?:bao\\s+gồm|gồm\\s+những|gồm\\s+các|trong\\s+gói\\s+có)\\s+(?:bước\\s+|món\\s+|dịch\\s+vụ\\s+|loại\\s+)?(?:gì|j|nào)\\b"
            + "|\\b(?:quyền\\s+lợi|ưu\\s+đãi\\s+kèm\\s+theo|được\\s+hưởng\\s+(?:những\\s+)?(?:gì|j)|kèm\\s+theo\\s+(?:những\\s+)?(?:gì|j))\\b"
            + "|^\\s*(?:dạ\\s+|vậy\\s+|thế\\s+|còn\\s+)?(?:quyền\\s+lợi|các\\s+bước|quy\\s+trình|chi\\s+tiết\\s+gói|thông\\s+tin\\s+gói)(?:\\s+(?:gói(?:\\s+này|\\s+đó)?|là\\s+gì|như\\s+thế\\s+nào|ra\\s+sao|thì\\s+sao|sao|ạ|nhỉ|em|shop))*\\s*[?.!~]*$"
            + "|\\b(?:quy\\s+trình|kỹ\\s+thuật|các\\s+bước|thao\\s+tác)\\s+(?:làm|massage|trị\\s+liệu|bấm\\s+huyệt|chăm\\s+sóc|gồm|như\\s+thế\\s+nào|ntn|thế\\s+nào|ra\\s+sao|mấy\\s+bước)\\b"
            + "|\\b(?:làm\\s+trong\\s+bao\\s+lâu|kéo\\s+dài\\s+bao\\s+lâu|thời\\s+lượng\\s+(?:bao\\s+lâu|mấy\\s+phút|bao\\s+nhiêu\\s+phút)|mấy\\s+phút\\s+vậy)\\b"
            + "|\\b(?:thời\\s+lượng\\s+(?:gì|j|nào|sao)(?:\\s+nữa)?|tưởng\\s+(?:là\\s+|gói\\s+)?\\d{2,3}\\s*(?:phút|p)\\b|không\\s+phải\\s+\\d{2,3}\\s*(?:phút|p)\\s+(?:à|hả|sao|nhỉ)|sao\\s+lại\\s+hỏi\\s+thời\\s+lượng)\\b"
            + "|\\b(?:phòng\\s+(?:riêng|chung|vip|đôi|cộng\\s+đồng)|có\\s+rèm\\s+riêng|không\\s+gian\\s+(?:như\\s+thế\\s+nào|thế\\s+nào|riêng\\s+tư|chung|riêng)|đi\\s+2\\s+người.*?(?:phòng|cùng))\\b"
            + "|\\b(?:có\\s+(?:cần\\s+|phải\\s+|bắt\\s+buộc\\s+|phát\\s+sinh\\s+)?)?(?:ăn\\s+nhẹ|điểm\\s+tâm|buffet|thực\\s+dưỡng|nước\\s+ép|ngâm\\s+bồn|xông\\s+hơi|chườm\\s+ngải\\s+cứu|tẩy\\s+da\\s+chết|tẩy\\s+tế\\s+bào\\s+chết|chăm\\s+sóc\\s+da\\s+mặt|chọn\\s+ktv|chọn\\s+kỹ\\s+thuật\\s+viên|ktv\\s+nữ|kỹ\\s+thuật\\s+viên\\s+nữ|nhận\\s+tip|tiền\\s+tip|cần\\s+tip|tip\\s+thêm|phụ\\s+phí|phụ\\s+thu|đặt\\s+cọc|cọc\\s+trước)\\b.*\\b(?:miễn\\s+phí|không|k|ko|hông|chưa|sao|thế\\s+nào|ntn|ạ|nhỉ|shop|em)\\b"
            + "|\\b(?:gói\\s+cơ\\s+bản|gói\\s+vip|gói\\s+trị\\s+liệu|gói\\s+chuyên\\s+sâu)\\s+khác\\s+(?:gì|j|sao|như\\s+thế\\s+nào)\\b"
            + "|\\b(?:giờ\\s+nào\\s+vắng|khung\\s+giờ\\s+nào\\s+vắng|làm\\s+xong\\s+có\\s+bị\\s+đau)\\b"
            + "|\\b(?:mở\\s+cửa|đóng\\s+cửa|giờ\\s+mở\\s+cửa|giờ\\s+làm\\s+việc|làm\\s+việc\\s+(?:tới|đến)\\s+mấy\\s+giờ|địa\\s+chỉ\\s+(?:ở\\s+đâu|bên\\s+mình|chi\\s+nhánh)|chi\\s+nhánh\\s+ở\\s+đâu)\\b"
            + "|\\b(?:giá(?:\\s+đó|\\s+này|\\s+trên)?|\\d{2,4}\\s*k?)\\s+(?:đó\\s+|này\\s+)?(?:là\\s+)?(?:giá\\s+)?(?:cho\\s+|của\\s+|tính\\s+theo\\s+)?(?:\\d+|một|mấy)\\s*(?:buổi|lần|suất|người)\\b"
            + "|\\b(?:\\d+|một|mấy)\\s*(?:buổi|lần|suất)\\s+(?:à|hả|vậy|sao|nhỉ|hay\\s+sao|đúng\\s+không|phải\\s+không)\\b"
            + ").*"
    );
    private static final Pattern DETAIL_OR_FAQ_QUESTION_ASCII_PATTERN = Pattern.compile(
            "(?is).*(?:"
            + "\\b(?:thuc\\s+duong|dinh\\s+duong|buffet|an\\s+nhe|diem\\s+tam|nuoc\\s+ep|thuc\\s+don)\\b.*\\b(?:la\\s+(?:gi|j|sao|nhu\\s+the\\s+nao|ntn|the\\s+nao)|co\\s+(?:nhung\\s+)?(?:mon\\s+)?(?:gi|j)|gom\\s+(?:nhung\\s+)?(?:mon\\s+)?(?:gi|j)|an\\s+(?:mon\\s+)?(?:gi|j)|mien\\s+phi|khong|k|ko)\\b"
            + "|\\b(?:co\\s+mon\\s+(?:gi|j)|an\\s+mon\\s+(?:gi|j)|thuc\\s+don\\s+(?:co|gom|la)\\s+(?:nhung\\s+)?(?:mon\\s+)?(?:gi|j)|buffet\\s+(?:co|gom|la)\\s+(?:nhung\\s+)?(?:mon\\s+)?(?:gi|j))\\b"
            + "|\\b(?:no|do|day|cai\\s+do|cai\\s+nay)\\s+la\\s+(?:cai\\s+)?(?:gi|j|sao|nhu\\s+the\\s+nao|ntn)\\b"
            + "|\\b[a-z\\d\\s]{2,70}\\s+la\\s+(?:[a-z\\d]+\\s+){0,2}(?:cai\\s+|loai\\s+|nhung\\s+|la\\s+)?(?:gi|j|nao)\\b(?:\\s+(?:vay|the|ha|a|em|shop|nhi))*\\s*[?.!~]*$"
            + "|\\b(?:ngam|xong|dung|gom)\\s+(?:nhung\\s+)?(?:loai|la|vi|thao\\s+duoc)\\s+(?:gi|j|nao)\\b"
            + "|\\b(?:goi\\s+(?:nay|do|tren|\\d+\\s*(?:phut|p|k))|lieu\\s+trinh\\s+(?:nay|do))?\\s*(?:bao\\s+gom|gom\\s+nhung|trong\\s+goi\\s+co)\\s+(?:buoc\\s+|mon\\s+|loai\\s+)?(?:gi|j|nao)\\b"
            + "|\\b(?:quyen\\s+loi|duoc\\s+huong\\s+(?:nhung\\s+)?(?:gi|j))\\b"
            + "|\\b(?:lam\\s+trong\\s+bao\\s+lau|keo\\s+dai\\s+bao\\s+lau|thoi\\s+luong\\s+(?:bao\\s+lau|may\\s+phut|bao\\s+nhieu\\s+phut|gi\\s+nua|j\\s+nua)|tuong\\s+(?:la\\s+|goi\\s+)?\\d{2,3}\\s*(?:phut|p)\\b)\\b"
            + "|\\b(?:phong\\s+(?:rieng|chung|vip|doi|cong\\s+dong)|co\\s+rem\\s+rieng|di\\s+2\\s+nguoi.*?(?:phong|cung))\\b"
            + "|\\b(?:co\\s+(?:can\\s+|phai\\s+|bat\\s+buoc\\s+|phat\\s+sinh\\s+)?)?(?:nhan\\s+tip|tien\\s+tip|can\\s+tip|phu\\s+phi|phu\\s+thu|dat\\s+coc|coc\\s+truoc)\\b.*\\b(?:khong|k|ko|chua|sao|the\\s+nao|ntn)\\b"
            + "|\\b(?:goi\\s+co\\s+ban|goi\\s+vip|goi\\s+tri\\s+lieu|goi\\s+chuyen\\s+sau)\\s+khac\\s+(?:gi|j|sao)\\b"
            + "|\\b(?:gio\\s+nao\\s+vang|khung\\s+gio\\s+nao\\s+vang|lam\\s+xong\\s+co\\s+bi\\s+dau)\\b"
            + "|\\b(?:gia(?:\\s+do|\\s+nay|\\s+tren)?|\\d{2,4}\\s*k?)\\s+(?:do\\s+|nay\\s+)?(?:la\\s+)?(?:gia\\s+)?(?:cho\\s+|cua\\s+|tinh\\s+theo\\s+)?(?:\\d+|mot|may)\\s*(?:buoi|lan|suat|nguoi)\\b"
            + "|\\b(?:\\d+|mot|may)\\s*(?:buoi|lan|suat)\\s+(?:a|ha|vay|sao|nhi|hay\\s+sao|dung\\s+khong|phai\\s+khong)\\b"
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
        if (isHardPackageRejection(trimmed)
                || isPriceObjection(trimmed)
                || isComprehensionFailureMessage(trimmed)
                || isMetaConversationOnBotBehavior(trimmed)
                || isConfirmPreviousInfoMessage(trimmed)
                || PrimaryActionRouter.isFrustrationMessage(trimmed)) {
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
                || isComprehensionFailureMessage(trimmed)
                || isMetaConversationOnBotBehavior(trimmed)
                || isConfirmPreviousInfoMessage(trimmed)
                || PrimaryActionRouter.isFrustrationMessage(trimmed)) {
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
        String normalizedMsg = com.example.docmind.service.text.VietnameseQueryNormalizer.normalizeForClassification(message);
        String trimmed = (normalizedMsg != null && !normalizedMsg.isBlank() ? normalizedMsg : message).strip();
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
                || isComprehensionFailureMessage(trimmed)
                || isMetaConversationOnBotBehavior(trimmed)
                || isConfirmPreviousInfoMessage(trimmed)
                || PrimaryActionRouter.isFrustrationMessage(trimmed)) {
            return false;
        }
        // Do not hijack explicit price inquiries ("Gói này giá bao nhiêu?", "Giá sao shop?")
        if (PrimaryActionRouter.matchesRawPriceInquiryPattern(trimmed)
                && !norm.matches("(?isU).*\\b(thực\\s+dưỡng|buffet|ăn\\s+nhẹ|điểm\\s+tâm|có\\s+món\\s+gì|là\\s+gì|là\\s+j|quyền\\s+lợi|thời\\s+lượng|(?:\\d+|một|mấy)\\s*(?:buổi|lần|suất)|tính\\s+theo\\s+buổi|trọn\\s+gói)\\b.*")) {
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
        String normalizedMsg = com.example.docmind.service.text.VietnameseQueryNormalizer.normalizeForClassification(message);
        String effectiveMsg = (normalizedMsg != null && !normalizedMsg.isBlank()) ? normalizedMsg : message;
        String norm = effectiveMsg != null
                ? Normalizer.normalize(effectiveMsg.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT)
                : "";
        String ascii = removeDiacritics(effectiveMsg);
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

        // 3. Check duration questions ("thời lượng j nữa nhỉ tưởng 60p", "bao lâu", "mấy phút")
        if (norm.matches("(?isU).*\\b(bao\\s+lâu|mấy\\s+phút|bao\\s+nhiêu\\s+phút|thời\\s+lượng|kéo\\s+dài|tưởng\\s+(?:là\\s+|gói\\s+)?\\d{2,3}\\s*(?:phút|p)\\b)\\b.*")
                || ascii.matches("(?is).*\\b(bao\\s+lau|may\\s+phut|bao\\s+nhieu\\s+phut|thoi\\s+luong|tuong\\s+(?:la\\s+|goi\\s+)?\\d{2,3}\\s*(?:phut|p)\\b)\\b.*")) {
            return "thời lượng liệu trình";
        }

        // 3B. Check unit / session count questions ("239 là giá 1 buổi à", "giá đó là 1 lần làm hả")
        if (norm.matches("(?isU).*\\b((?:\\d+|một|mấy)\\s*(?:buổi|lần|suất)|tính\\s+theo\\s+buổi)\\b.*")
                || ascii.matches("(?is).*\\b((?:\\d+|mot|may)\\s*(?:buoi|lan|suat)|tinh\\s+theo\\s+buoi)\\b.*")) {
            return "đơn vị tính theo buổi (giá trọn gói cho 1 buổi lẻ)";
        }

        // 4. Check specific service steps / modalities / policies
        if (norm.contains("ngâm bồn") || ascii.contains("ngam bon")
                || (norm.contains("ngâm") && norm.contains("thảo dược"))
                || (ascii.contains("ngam") && ascii.contains("thao duoc"))) {
            return "ngâm bồn";
        }
        if (norm.contains("xông hơi") || ascii.contains("xong hoi")) return "xông hơi";
        if (norm.contains("ngải cứu") || ascii.contains("ngai cuu")) return "chườm ngải cứu";
        if (norm.contains("tẩy da chết") || norm.contains("tẩy tế bào chết") || ascii.contains("tay da chet")) return "tẩy da chết";
        if (norm.contains("da mặt") || ascii.contains("da mat")) return "chăm sóc da mặt";
        if (norm.contains("ktv") || norm.contains("kỹ thuật viên") || ascii.contains("ky thuat vien")) return "kỹ thuật viên";
        if (norm.contains("tip") || norm.contains("tiền bo") || norm.contains("phụ phí") || norm.contains("phụ thu")
                || ascii.contains("tien tip") || ascii.contains("phu phi")) return "chính sách tip";
        if (norm.contains("đặt cọc") || norm.contains("cọc trước") || ascii.contains("dat coc")) return "chính sách đặt cọc";
        if (norm.contains("giờ nào vắng") || ascii.contains("gio nao vang")) return "khung giờ vắng khách";
        if (norm.matches("(?isU).*\\b(mở\\s+cửa|đóng\\s+cửa|giờ\\s+làm\\s+việc|địa\\s+chỉ|chi\\s+nhánh)\\b.*")) {
            return "giờ mở cửa và địa chỉ";
        }
        if (norm.matches("(?isU).*\\b(gói\\s+cơ\\s+bản|gói\\s+vip|gói\\s+trị\\s+liệu|gói\\s+chuyên\\s+sâu)\\s+khác\\s+(?:gì|j|sao)\\b.*")) {
            return "sự khác biệt giữa các gói";
        }
        if (norm.matches("(?isU).*\\b(quy\\s+trình|kỹ\\s+thuật|bấm\\s+huyệt|các\\s+bước|thao\\s+tác)\\b.*")) {
            return "quy trình và kỹ thuật trị liệu";
        }
        if (norm.matches("(?isU).*\\b(quyền\\s+lợi|bao\\s+gồm|gồm\\s+những\\s+gì|gồm\\s+gì|trong\\s+gói\\s+có|được\\s+hưởng|ưu\\s+đãi\\s+kèm)\\b.*")
                || ascii.matches("(?is).*\\b(quyen\\s+loi|bao\\s+gom|gom\\s+nhung\\s+gi|duoc\\s+huong)\\b.*")) {
            return "quyền lợi và các bước trong gói";
        }

        // 4B. Explicit definition question "<subject> là gì/j" (e.g. "Cạo vôi răng siêu âm là gì?")
        Matcher defSubjMatcher = Pattern.compile(
                "(?isU)^\\s*(?:dạ\\s+|cho\\s+hỏi\\s+|mình\\s+hỏi\\s+|tôi\\s+hỏi\\s+|shop\\s+ơi\\s+)?([\\p{L}\\d\\s]{2,65}?)\\s+là\\s+(?:[\\p{L}\\d]+\\s+){0,2}(?:cái\\s+|loại\\s+|những\\s+)?(?:gì|j|sao|như\\s+thế\\s+nào|ntn)\\b"
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

    /**
     * Trích xuất các tín hiệu phụ (secondary intents) trong câu đa tín hiệu của khách hàng.
     * Ví dụ:
     * - Câu hỏi chi tiết quy trình/nguyên liệu sau khi đã "Xin giá" -> primary = DETAIL_OR_FAQ_QUESTION, secondary = [SERVICE_SELECTION, PENDING_PRICE_INQUIRY]
     * - "Nghe hay đấy cơ mà làm gì mà phải xin lỗi" -> primary = META_CONVERSATION_ON_BOT_BEHAVIOR, secondary = [POSITIVE_SERVICE_INTEREST]
     */
    public static java.util.List<String> extractSecondaryIntents(
            String message,
            ConversationState priorState,
            DetectedIntent primaryIntent
    ) {
        if (message == null || message.isBlank()) {
            return java.util.List.of();
        }
        String trimmed = message.strip();
        java.util.List<String> secondary = new java.util.ArrayList<>();
        if (hasPositiveServiceInterestSignal(trimmed)) {
            secondary.add("POSITIVE_SERVICE_INTEREST");
        }
        if (primaryIntent != DetectedIntent.SERVICE_SELECTION
                && PrimaryActionRouter.isServiceFamilySelectionMessage(trimmed)) {
            secondary.add("SERVICE_SELECTION");
        }
        if (primaryIntent != DetectedIntent.PRICE_INQUIRY
                && priorState != null
                && priorState.priceRequestedInSession()
                && !priorState.priceDiscussed()) {
            secondary.add("PENDING_PRICE_INQUIRY");
        }
        if (primaryIntent != DetectedIntent.META_CONVERSATION_ON_BOT_BEHAVIOR
                && isMetaConversationOnBotBehavior(trimmed)) {
            secondary.add("META_CONVERSATION_ON_BOT_BEHAVIOR");
        }
        if (primaryIntent != DetectedIntent.CONFIRM_PREVIOUS_INFO
                && isConfirmPreviousInfoMessage(trimmed)) {
            secondary.add("CONFIRM_PREVIOUS_INFO");
        }
        return java.util.List.copyOf(secondary);
    }

    public static DetectedIntent classify(String message, ConversationState priorState) {
        if (message == null || message.isBlank()) {
            return DetectedIntent.AMBIGUOUS;
        }
        String rawTrimmed = message.strip();
        if (PromptSecurityGuard.isDirectJailbreakAttempt(rawTrimmed)) {
            return DetectedIntent.PROMPT_INJECTION;
        }
        String normalizedMsg = com.example.docmind.service.text.VietnameseQueryNormalizer.normalizeForClassification(rawTrimmed);
        String trimmed = (normalizedMsg != null && !normalizedMsg.isBlank()) ? normalizedMsg.strip() : rawTrimmed;
        String lower = Normalizer.normalize(trimmed, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        String ascii = removeDiacritics(trimmed);
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
        // 3-bis. CAUTIOUS_SYMPTOM_CLARIFICATION (tầng 2): tê/chóng mặt... chưa có dấu hiệu cấp tính
        if (hasCautiousNeuroSymptom(trimmed, priorState)
                && !isStrongRejection(trimmed)
                && !isEntityMismatchCorrection(trimmed)
                && !hasHarshPronouns(trimmed)) {
            return DetectedIntent.CAUTIOUS_SYMPTOM_CLARIFICATION;
        }
        // 3A. DETAIL_OR_FAQ_QUESTION with contrast/clarification prefix ("K tôi thấy có buffet thực dưỡng, nó là j")
        // Checked before ENTITY_MISMATCH_CORRECTION so leading "K/Không" clarification questions are never mistaken for mismatch complaints.
        if (hasContrastOrClarificationPrefix(trimmed) && isDetailOrFaqQuestion(trimmed, priorState)) {
            return DetectedIntent.DETAIL_OR_FAQ_QUESTION;
        }
        // 3A-2. CONFIRM_PREVIOUS_INFO ("Hiểu đúng rồi còn gì, có phải hiểu lầm đâu")
        if (isConfirmPreviousInfoMessage(trimmed)) {
            return DetectedIntent.CONFIRM_PREVIOUS_INFO;
        }
        // 3A-2b. ANTI_APOLOGY_PUSHBACK ("Hâm à mắc j xin loi", "Sao lại xin lỗi") when not paired with positive service interest
        if (isAntiApologyPushback(trimmed) && !hasPositiveServiceInterestSignal(trimmed)) {
            return DetectedIntent.ANTI_APOLOGY_PUSHBACK;
        }
        // 3A-3. META_CONVERSATION_ON_BOT_BEHAVIOR ("Sao chào lắm thế", "Nghe hay đấy cơ mà làm gì mà phải xin lỗi")
        if (isMetaConversationOnBotBehavior(trimmed)) {
            return DetectedIntent.META_CONVERSATION_ON_BOT_BEHAVIOR;
        }
        // 3B-0. SCOPE_CORRECTION_PUSHBACK (Group D explicit negation "A chứ B đâu" & Group H dialect/colloquial pushback)
        if (isScopeCorrectionPushback(trimmed)) {
            return DetectedIntent.SCOPE_CORRECTION_PUSHBACK;
        }
        // 3B. ENTITY_MISMATCH_CORRECTION (anti-keyword-hijacking: check before TRIAL/REJECTION/FRUSTRATION/SERVICE_SELECTION)
        if (isEntityMismatchCorrection(trimmed)) {
            return DetectedIntent.ENTITY_MISMATCH_CORRECTION;
        }
        // 3C. PUSHBACK_ON_ASSUMPTION ("Căng gì, tôi bị căng đâu", "không bị căng", "đâu có mỏi")
        if (isNegationOrPushbackOnAssumption(trimmed)) {
            return DetectedIntent.PUSHBACK_ON_ASSUMPTION;
        }
        // 4. TRIAL_ACCEPTANCE (only if no other package specified & not strong rejection)
        if (isTrialAcceptance(trimmed, priorState)) {
            return DetectedIntent.TRIAL_ACCEPTANCE;
        }
        // 5. STRONG_REJECTION
        if (isStrongRejection(trimmed)) {
            return DetectedIntent.STRONG_REJECTION;
        }
        // 5B. POLITE_DEFERRAL_EXIT ("Ukm, để t cân nhắc thêm", "Để mình suy nghĩ thêm")
        if (isPoliteDeferralExit(trimmed)
                && (priorState == null
                    || priorState.downsellOffered()
                    || (!isMidFunnelLukewarmReply(trimmed, priorState) && priorState.objectionCount() == 0))) {
            return DetectedIntent.POLITE_DEFERRAL_EXIT;
        }
        // 6. PACKAGE_REJECTION
        if (isPackageRejection(trimmed)) {
            return DetectedIntent.PACKAGE_REJECTION;
        }
        // 6B. COMPREHENSION_FAILURE ("Chả hiểu j", "Khó hiểu quá")
        if (isComprehensionFailureMessage(trimmed)) {
            return DetectedIntent.COMPREHENSION_FAILURE;
        }
        // 7A. FRUSTRATION_DEMANDING_SOLUTION ("Dở vừa, ns linh ta linh tinh", "Rồi định chăm sóc cái j cho t, t phát bực r")
        if (isFrustrationDemandingSolution(trimmed, priorState)) {
            return DetectedIntent.FRUSTRATION_DEMANDING_SOLUTION;
        }
        // 7. FRUSTRATION ("Ô hay, nói linh ta linh tinh gì đấy", "Dở à")
        if (PrimaryActionRouter.isFrustrationMessage(trimmed)) {
            return DetectedIntent.FRUSTRATION;
        }
        // 7B. DETAIL_OR_FAQ_QUESTION ("Thực dưỡng là j", "Quy trình là loại gì vậy?", "Buffet có món gì?")
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
        // 15. GREETING_OR_START_CONSULTATION ("Tư vấn", "Chào shop", "Hi")
        if (isGreetingOrStartConsultationMessage(trimmed)) {
            return DetectedIntent.GREETING_OR_START_CONSULTATION;
        }
        // 16. ACK
        if (PrimaryActionRouter.isAcknowledgementMessage(trimmed)) {
            return DetectedIntent.ACK;
        }
        return DetectedIntent.AMBIGUOUS;
    }
}
