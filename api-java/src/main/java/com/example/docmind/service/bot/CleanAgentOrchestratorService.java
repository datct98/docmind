package com.example.docmind.service.bot;

import com.example.docmind.dto.LearnedRuleDto;
import com.example.docmind.dto.bot.AgentSkillDto;
import com.example.docmind.dto.bot.CustomerInsightDto;
import com.example.docmind.dto.bot.DecisionTrace;
import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.dto.bot.FanpageBotResponse;
import com.example.docmind.service.llm.LlmClient;
import com.example.docmind.service.llm.ReasoningBudget;
import com.example.docmind.service.retrieval.PromptBuilder;
import com.example.docmind.service.retrieval.ReciprocalRankFusion;
import com.example.docmind.service.security.PromptSecurityGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Điều phối Trung tâm của CLEAN_AGENT_MODE:
 * Quản lý Brain, Funnel Stage, ConversationState, Tín hiệu Lượt hiện tại & Luồng thực thi.
 */
@Service
public class CleanAgentOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(CleanAgentOrchestratorService.class);

    public static final String SPEECH_ACT_RESPECTED_PAUSE = "RESPECTED_PAUSE_WITH_SOFT_OPTION";
    public static final String SPEECH_ACT_OFFERED_DETAILS = "OFFERED_PACKAGE_DETAILS";
    public static final String SPEECH_ACT_INVITED_TRIAL_OR_TIME = "INVITED_TRIAL_OR_ASKED_TIME";
    public static final String SPEECH_ACT_BOOKING_CONFIRMED = "BOOKING_CONFIRMED_ACK";
    public static final String SPEECH_ACT_ANSWERED_OPERATIONAL_INFO = "ANSWERED_OPERATIONAL_INFO";
    public static final String SPEECH_ACT_GENERAL_CONSULTATION = "GENERAL_CONSULTATION";

    public static final String BRAIN_INTENT_PRICE_OBJECTION = "PRICE_OBJECTION";
    public static final String BRAIN_INTENT_PRICE_HESITATION = "PRICE_HESITATION";
    public static final String BRAIN_INTENT_GENERAL_DEFER_OR_BUSY = "GENERAL_DEFER_OR_BUSY";
    public static final String BRAIN_INTENT_PRICE_OR_DETAIL_INQUIRY = "PRICE_OR_DETAIL_INQUIRY";
    public static final String BRAIN_INTENT_SYMPTOM_OR_CONTEXT_SHARING = "SYMPTOM_OR_CONTEXT_SHARING";
    public static final String BRAIN_INTENT_BOOKING_OR_TRIAL_ACCEPTANCE = "BOOKING_OR_TRIAL_ACCEPTANCE";
    public static final String BRAIN_INTENT_POLITE_CLOSING_ACK = "POLITE_CLOSING_ACK";

    private static final Pattern CHALLENGE_ASSUMPTION_PATTERN = Pattern.compile(
            "(?isU).*\\b(sao\\s+(?:em\\s+|bạn\\s+|shop\\s+|mày\\s+)?biết|biết\\s+hay\\s+(?:vậy|thế|nhỉ)|"
            + "ai\\s+(?:bảo|nói)(?:\\s+thế|\\s+vậy|\\s+em)?|đoán\\s+mò|thầy\\s+bói|tự\\s+suy\\s+diễn|"
            + "hồi\\s+nào|lúc\\s+nào\\s+mà\\s+bảo|đâu\\s+ra\\s+mà)\\b.*"
    );

    private static final Pattern HESITATION_OR_DEFERRAL_CLEAN_PATTERN = Pattern.compile(
            "(?isU).*\\b("
            + "để\\s+(?:tối\\s+)?(?:về\\s+)?(?:em\\s+|mình\\s+|anh\\s+|chị\\s+|tôi\\s+|t\\s+)?(?:xem|tính|nghĩ|suy\\s+nghĩ|cân\\s+nhắc|hỏi|bàn|tham\\s+khảo|kiểm\\s+tra\\s+lịch)(?:\\s+lại|\\s+thêm|\\s+trước|\\s+của\\s+(?:mình|tôi|em|anh|chị))?(?:\\s+đã|\\s+nhé|\\s+nha|\\s+ạ)?|"
            + "(?:em\\s+|mình\\s+|anh\\s+|chị\\s+|tôi\\s+|t\\s+)?(?:xem|tính|nghĩ|suy\\s+nghĩ|cân\\s+nhắc|tham\\s+khảo|kiểm\\s+tra\\s+lịch)(?:\\s+lại|\\s+thêm|\\s+của\\s+(?:mình|tôi|em|anh|chị))?\\s+(?:đã|trước\\s+đã|thêm\\s+đã)(?:\\s+nhé|\\s+nha|\\s+ạ|\\s+em)?|"
            + "(?:em\\s+|mình\\s+|anh\\s+|chị\\s+|tôi\\s+|t\\s+)?(?:nghĩ|suy\\s+nghĩ|cân\\s+nhắc|tham\\s+khảo)\\s+thêm(?:\\s+đã|\\s+chút|\\s+nhé|\\s+nha|\\s+ạ|\\s+em)?|"
            + "chưa\\s+quyết(?:\\s+định)?(?:\\s+được|\\s+đã|\\s+nhé|\\s+ạ)?|"
            + "chưa\\s+biết(?:\\s+nữa|\\s+được|\\s+ạ)?|từ\\s+từ(?:\\s+đã|\\s+nhé|\\s+ạ)?|để\\s+sau|để\\s+tối|đang\\s+bận|bận\\s+chút|bận\\s+rồi|khi\\s+khác|hôm\\s+khác|lúc\\s+khác"
            + ")\\b.*"
    );

    private static final Pattern PURE_BUSY_OR_STOP_DEFERRAL_PATTERN = Pattern.compile(
            "(?isU).*\\b(đang\\s+bận|bận\\s+chút|bận\\s+rồi|bận\\s+việc|thôi\\s+để|(?:để\\s+)?hôm\\s+khác|(?:để\\s+)?khi\\s+khác|(?:để\\s+)?lúc\\s+khác|để\\s+sau\\s+nhé)\\b.*"
    );

    private static final Pattern PROMO_OR_DISCOUNT_INQUIRY_CLEAN_PATTERN = Pattern.compile(
            "(?isU).*\\b("
            + "sale\\b|có\\s+sale|đang\\s+sale|khuyến\\s+mãi|khuyến\\s+mại|km\\b|ưu\\s+đãi|giảm\\s+giá|voucher|deal\\b|code\\b|mã\\s+giảm|chương\\s+trình\\s+(?:giảm|ưu\\s+đãi|khuyến\\s+mãi|sale)|"
            + "có\\s+(?:được\\s+)?(?:giảm|km|khuyến\\s+mãi|khuyến\\s+mại|ưu\\s+đãi|tặng|sale|deal|voucher|code)(?:\\s+(?:gì|không|k|ko|hem|hông|chưa|nào))?"
            + ")\\b.*"
    );

    private static final Pattern NORMAL_PRICE_OR_DETAIL_INQUIRY_CLEAN_PATTERN = Pattern.compile(
            "(?isU).*\\b("
            + "giá\\s+(?:sao|thế\\s+nào|như\\s+thế\\s+nào|bao\\s+nhiêu|bên\\s+mình|các\\s+gói|gói)|"
            + "bảng\\s+giá|xin\\s+giá|báo\\s+giá|bao\\s+nhiêu\\s+tiền|nhiêu\\s+tiền|mấy\\s+tiền|"
            + "\\d{2,4}\\s*k\\s+là\\s+(?:1|một|\\d+)\\s*(?:buổi|lần|phút)|"
            + "gói\\s+\\d{2,4}\\s*k\\s+(?:là\\s+)?(?:mấy|bao\\s+nhiêu)\\s*(?:phút|buổi|lần)|"
            + "(?:là\\s+)?(?:mấy|bao\\s+nhiêu)\\s*(?:phút|buổi|tiếng)|"
            + "gồm\\s+những\\s+gì|bao\\s+gồm\\s+gì|có\\s+những\\s+gì|gồm\\s+gì|"
            + "sale\\b|có\\s+sale|đang\\s+sale|khuyến\\s+mãi|khuyến\\s+mại|km\\b|ưu\\s+đãi|giảm\\s+giá|voucher|deal\\b|code\\b"
            + ")\\b.*"
    );

    private static final Pattern OPERATIONAL_OR_LOGISTICS_QUESTION_PATTERN = Pattern.compile(
            "(?isU).*\\b("
            + "(?:tối\\s+|sáng\\s+|trưa\\s+|chiều\\s+)?mấy\\s*(?:h|giờ)\\s*(?:đóng|mở|nghỉ)|"
            + "đóng\\s+cửa|mở\\s+cửa|giờ\\s+(?:mở\\s+cửa|đóng\\s+cửa|hoạt\\s+động|làm\\s+việc)|"
            + "đỗ\\s+xe|đậu\\s+xe|gửi\\s+xe|chỗ\\s+(?:đỗ|đậu|gửi|để)\\s+xe|bãi\\s+(?:đỗ\\s+|đậu\\s+|gửi\\s+)?xe|đỗ\\s+ô\\s+tô|gửi\\s+ô\\s+tô|"
            + "địa\\s+chỉ(?:\\s+ở\\s+đâu)?|ở\\s+đâu\\s+(?:vậy|thế|ạ|nhỉ|shop|em)|chi\\s+nhánh|cơ\\s+sở\\s+(?:ở\\s+đâu|nào)|"
            + "thanh\\s+toán|chuyển\\s+khoản|quẹt\\s+thẻ|nhân\\s+viên\\s+(?:nam|nữ)|ktv\\s+(?:nam|nữ)|chuyên\\s+viên\\s+(?:nam|nữ)"
            + ")\\b.*"
    );

    private static final Pattern NON_PRICE_CAO_EXCLUSION_PATTERN = Pattern.compile(
            "(?isU).*\\b(huyết\\s+áp\\s+cao|cao\\s+huyết\\s+áp|sốt\\s+cao|gối\\s+cao|nằm\\s+cao|kê\\s+cao|tay\\s+nghề\\s+cao|chuyên\\s+môn\\s+cao|đánh\\s+giá\\s+cao|hiệu\\s+quả\\s+cao)\\b.*"
    );

    private static final Pattern CLEAN_PRICE_OBJECTION_EXTRA_PATTERN = Pattern.compile(
            "(?isU).*\\b("
            + "(?:vẫn\\s+|hơi\\s+|cũng\\s+|thấy\\s+|sao\\s+)?(?:đắt|mắc|chát)(?:\\s+(?:nhỉ|thế|vậy|quá|ghê|dữ|phết|nha|nhé|ạ|hả|lắm|thật|rồi))?|"
            + "cao\\s+(?:nhỉ|thế|quá|vậy|ghê|dữ|phết|ạ)|"
            + "hơi\\s+(?:cao|đắt|mắc|chát|căng)|"
            + "giá\\s+(?:hơi\\s+|cũng\\s+|khá\\s+)?(?:cao|đắt|mắc|chát|căng)|"
            + "vẫn\\s+(?:hơi\\s+)?(?:cao|đắt|mắc|chát|căng)|"
            + "căng\\s+(?:nhỉ|thế|quá|vậy|ghê)|"
            + "đau\\s+ví|quá\\s+sức|nhiều\\s+tiền\\s+quá|vượt\\s+ngân\\s+sách|chưa\\s+đủ\\s+tiền|không\\s+đủ\\s+tiền"
            + ")\\b.*"
    );

    private static final Pattern METAPHORICAL_WAGE_COMPARISON_PRICE_OBJECTION_PATTERN = Pattern.compile(
            "(?isU).*\\b("
            + "(?:gần|bằng|hơn|mất|tốn|đi\\s+đứt)\\s+(?:cả\\s+)?(?:một|1|nửa|hai|2|mấy|\\d+)\\s*(?:ngày\\s+công|ngày\\s+lương|tháng\\s+lương|buổi\\s+lương|tuần\\s+lương)|"
            + "(?:gần|bằng|hơn|mất|tốn|đi\\s+đứt)\\s+cả\\s+(?:ngày\\s+công|ngày\\s+lương|tháng\\s+lương|tuần\\s+lương)|"
            + "(?:một|1|nửa|cả|\\d+)\\s*(?:ngày\\s+công|ngày\\s+lương|tháng\\s+lương)(?:\\s+(?:của\\s+(?:t|tôi|mình|em|chị|anh)|rồi|r\\b|đấy|luôn|ạ))?"
            + ")\\b.*"
    );

    private static final Pattern EXPLICIT_DOWNSELL_OR_LOW_BUDGET_CLEAN_PATTERN = Pattern.compile(
            "(?isU).*\\b("
            + "gói\\s+(?:nào\\s+)?(?:rẻ|tiết\\s+kiệm|thấp|nhẹ\\s+tiền|mềm)\\s+hơn|"
            + "có\\s+gói\\s+(?:nào\\s+)?(?:rẻ|tiết\\s+kiệm|mềm|thấp)|"
            + "ngân\\s+sách|"
            + "(?:chỉ\\s+có|tầm|khoảng|dưới|mức)\\s+(?:[12]\\d{2}\\s*(?:k|nghìn|ngàn)|[12]\\d{2}[.,]000\\s*(?:đ|đồng|vnđ|vnd)?)|"
            + "(?:có|tìm|xin|tư\\s+vấn)\\s+gói\\s+(?:nào\\s+)?(?:ở\\s+mức\\s+|tầm\\s+|khoảng\\s+|dưới\\s+)?(?:[12]\\d{2}\\s*(?:k|nghìn|ngàn)|[12]\\d{2}[.,]000\\s*(?:đ|đồng|vnđ|vnd)?)|"
            + "(?:[12]\\d{2}\\s*(?:k|nghìn|ngàn)|[12]\\d{2}[.,]000\\s*(?:đ|đồng|vnđ|vnd)?)\\s+có\\s+gói\\s+nào"
            + ")\\b.*"
    );

    private static final Pattern COMPETITOR_OR_EXTERNAL_PRICE_COMPARISON_PATTERN = Pattern.compile(
            "(?isU).*\\b("
            + "(?:bên\\s+ngoài|tiệm\\s+ngoài|ở\\s+ngoài|ngoài\\s+kia|chỗ\\s+khác|tiệm\\s+khác|bên\\s+khác|quán\\s+khác|nơi\\s+khác).*\\b(?:\\d{2,4}\\s*(?:k|nghìn|ngàn)|rẻ\\s+hơn|chứ\\s+mấy)\\b|"
            + "\\b\\d{2,4}\\s*(?:k|nghìn|ngàn)\\b.*\\bchứ\\s+mấy\\b"
            + ").*"
    );

    private static final Pattern EXPLICIT_CONVERSATION_REFUSAL_STOP_PATTERN = Pattern.compile(
            "(?isU).*\\b("
            + "đừng\\s+tư\\s+vấn(?:\\s+thêm)?(?:\\s+nữa)?|"
            + "khỏi\\s+(?:cần\\s+)?tư\\s+vấn(?:\\s+thêm)?|"
            + "dừng\\s+tư\\s+vấn|"
            + "(?:thôi\\s+)?(?:anh\\s+|chị\\s+|mình\\s+|tôi\\s+|em\\s+)?(?:không|ko)\\s+(?:làm|đặt|đi|dùng|cần)(?:\\s+dịch\\s+vụ|\\s+nữa)?\\s+(?:đâu|nữa)|"
            + "thôi\\s+(?:anh\\s+|chị\\s+|mình\\s+|tôi\\s+|em\\s+)?(?:không|ko)\\s+(?:làm|cần|đặt)(?:\\s*$|[.,!?;])"
            + ").*"
    );

    private static final Pattern POSITIVE_TRIAL_INTEREST_CLEAN_PATTERN = Pattern.compile(
            "(?isU).*\\b(nghe\\s+(?:cũng\\s+)?ổn|ổn\\s+(?:hơn\\s+)?(?:đó|đấy|nhỉ|phết|ạ)|nghe\\s+hợp\\s+lý|nghe\\s+được\\s+(?:đó|đấy)|thử\\s+xem|đi\\s+thử\\s+1\\s+buổi|thử\\s+1\\s+buổi)\\b.*"
    );

    private static final Pattern BARE_ACKNOWLEDGMENT_CLEAN_PATTERN = Pattern.compile(
            "(?isU)^\\s*(?:dạ\\s+)?(?:ok|oke|okay|okie|okela|ừ|ừa|ừm|uk|uki|ukm|uh|uhm|vâng|dạ|cảm\\s+ơn|cám\\s+ơn|thanks|thank\\s+you|tks)"
            + "(?:\\s+(?:em|e|chị|anh|bạn|shop|mình|nhé|nha|ạ|nhiều|lắm|nhé\\s+em|nhé\\s+e|nha\\s+em|nha\\s+e|rồi|được\\s+rồi|cảm\\s+ơn|cám\\s+ơn|cảm\\s+ơn\\s+em|cảm\\s+ơn\\s+e))*\\s*[.!~]*\\s*$"
    );

    private static final Pattern POLITE_THANK_YOU_CLOSING_ACK_PATTERN = Pattern.compile(
            "(?isU)^\\s*(?:dạ\\s+)?(?:ok|oke|okay|okie|okela|ừ|ừa|ừm|uk|uki|ukm|uh|uhm|vâng|dạ|rồi|thế\\s+thôi)?\\s*"
            + "(?:cảm\\s+ơn|cám\\s+ơn|thanks|thank\\s+you|tks)"
            + "(?:\\s+(?:em|e|chị|anh|bạn|shop|mình|nhé|nha|ạ|nhiều|lắm))*\\s*[.!~]*\\s*$"
    );

    public static boolean isPoliteThankYouClosingAckMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        return POLITE_THANK_YOU_CLOSING_ACK_PATTERN.matcher(message.strip()).matches();
    }

    private static final Pattern PROACTIVE_BOOKING_OR_PACKAGE_SELECTION_CLEAN_PATTERN = Pattern.compile(
            "(?isU).*\\b("
            + "hôm\\s+nay|ngày\\s+mai|chiều\\s+nay|tối\\s+nay|sáng\\s+mai|chiều\\s+mai|tối\\s+mai|sáng\\s+nay|trưa\\s+nay|lát\\s+nữa|tí\\s+nữa|cuối\\s+tuần|thứ\\s+\\d|chủ\\s+nhật|\\d{1,2}\\s*(?:h|giờ|g\\b)|"
            + "(?:đặt|giữ|book|xếp|lên)\\s+(?:lịch\\s+|đơn\\s+|hàng\\s+)?giúp\\s+(?:mình|chị|anh|em|tôi)|"
            + "đặt\\s+(?:lịch|gói|mẫu|khóa|lớp|sản\\s+phẩm|hàng|đơn|chỗ|bàn|cho\\s+(?:mình|chị|anh|em|tôi))|"
            + "giữ\\s+(?:lịch|chỗ|suất|gói|mẫu)|book\\s+(?:lịch|gói|chỗ)|"
            + "chốt\\s+(?:gói|mẫu|khóa|lớp|sản\\s+phẩm|đơn|lịch)|"
            + "đăng\\s+ký\\s+(?:gói|khóa|lớp|học|lịch|thử|test|kiểm\\s+tra)|"
            + "hẹn\\s+(?:lịch|test|kiểm\\s+tra|qua|ghé|xem\\s+mẫu)|"
            + "thử\\s+(?:gói|1\\s+buổi|trước|xem|lần)|trải\\s+nghiệm\\s+(?:thử|gói|1\\s+buổi|trước)|"
            + "cho\\s+(?:mình|chị|anh|tôi)\\s+(?:thử|đặt|lấy|chốt|đăng\\s+ký|làm\\s+gói|mua)|"
            + "(?:mình|chị|anh|tôi)\\s+(?:qua|ghé|đến|sang|chọn\\s+(?:gói|mẫu|khóa)|lấy\\s+(?:gói|mẫu|khóa))|qua\\s+(?:làm|thử|trải\\s+nghiệm|xem\\s+mẫu)|"
            + "(?:ok|oke|okay|ừ|uk|vâng|dạ|vậy)\\b.*\\b(?:\\d{2,4}\\s*k\\b|\\d+[.,]\\d{3}(?:[.,]\\d{3})?|\\d{2,3}\\s*(?:phút|buổi|m2|m²)\\b|ns0\\d|ds0\\d|gói\\s+(?:dưỡng\\s+sinh|massage|cơ\\s+bản|chuyên\\s+sâu))"
            + ")\\b.*"
    );

    private static final Pattern DECLARATIVE_PRODUCT_SELECTION_CLEAN_PATTERN = Pattern.compile(
            "(?isU)^\\s*(?:dạ\\s+|vậy\\s+|thế\\s+)?(?:cho\\s+(?:mình|chị|anh|tôi)\\s+|lấy\\s+|chọn\\s+)?(?:gói|mẫu|khóa|lớp|sản\\s+phẩm)\\s+[^?]+\\b(?:nhé|nha|luôn|đi|ạ|cho\\s+(?:mình|chị|anh|tôi))\\s*[.!~]*\\s*$"
    );

    private static final Pattern PAUSE_OR_DEFERRAL_BOT_REPLY_CLEAN_PATTERN = Pattern.compile(
            "(?isU).*\\b("
            + "khi\\s+nào\\s+(?:tiện|rảnh|sẵn\\s+sàng|thu\\s+xếp\\s+được|muốn|cần)(?:\\s+mình|\\s+chị|\\s+anh|\\s+bạn)?\\s+(?:thì\\s+)?(?:cứ\\s+)?nhắn|"
            + "cứ\\s+nhắn\\s+(?:lại\\s+)?(?:cho\\s+)?(?:em|bên\\s+em|shop)(?:\\s+nhé|\\s+nha|\\s+ạ|\\s+bất\\s+cứ\\s+lúc\\s+nào)?|"
            + "(?:cứ|thoải\\s+mái|thong\\s+thả)\\s+(?:tham\\s+khảo|cân\\s+nhắc|suy\\s+nghĩ)(?:\\s+thêm)?|"
            + "(?:tham\\s+khảo|cân\\s+nhắc|suy\\s+nghĩ)\\s+thêm\\s*,?\\s*khi\\s+nào|"
            + "chưa\\s+cần\\s+(?:vội\\s+)?(?:quyết\\s+định|đặt\\s+lịch|chốt)(?:\\s+ngay)?|"
            + "chúc\\s+(?:mình|chị|anh|bạn)\\s+(?:một\\s+ngày|buổi\\s+tối|buổi\\s+chiều|cuối\\s+tuần)"
            + ")\\b.*"
    );

    private static final Pattern OFFER_PACKAGE_DETAILS_BOT_REPLY_CLEAN_PATTERN = Pattern.compile(
            "(?isU).*\\b(muốn\\s+(?:nghe|xem|tìm\\s+hiểu)\\s+(?:thêm\\s+)?chi\\s+tiết|"
            + "nghe\\s+chi\\s+tiết\\s+hơn|"
            + "gửi\\s+chi\\s+tiết\\s+(?:quyền\\s+lợi|quy\\s+trình|gói)|"
            + "tư\\s+vấn\\s+chi\\s+tiết\\s+hơn\\s+về\\s+gói)\\b.*"
    );

    private static final Pattern INVITE_TRIAL_OR_ASK_TIME_BOT_REPLY_CLEAN_PATTERN = Pattern.compile(
            "(?isU).*\\b(thử\\s+trải\\s+nghiệm|trải\\s+nghiệm\\s+(?:trước\\s+|thử\\s+)?(?:1|một)\\s+(?:buổi|lần)|"
            + "thử\\s+(?:trước\\s+)?(?:1|một)\\s+(?:buổi|lần)|(?:làm|dùng|tham\\s+khảo|trải\\s+nghiệm)\\s+thử\\b|"
            + "thử\\s+(?:phương\\s+án|gói|mẫu|khóa|lộ\\s+trình|dịch\\s+vụ|dòng)\\b|thử\\s+xem\\b|"
            + "(?:mình|chị|anh|bạn)\\s+thử\\b[^.!?]{0,180}\\b(?:nhé|nha|chứ|không)\\s*\\?|"
            + "hợp\\s+lý\\s+để\\s+(?:mình\\s+|chị\\s+|anh\\s+)?thử|"
            + "(?:ghé|qua)\\s+(?:thăm\\s+khám|kiểm\\s+tra|xem\\s+mẫu|trải\\s+nghiệm)\\b[^.!?]{0,180}\\b(?:nhé|nha|ạ)?\\s*\\?|"
            + "khung\\s+giờ\\s+nào|mấy\\s+giờ|giờ\\s+nào\\s+(?:để\\s+em|tiện|ạ|nhỉ)|"
            + "thời\\s+gian\\s+nào|lịch\\s+nào\\s+(?:tiện|thuận\\s+tiện|phù\\s+hợp)|"
            + "(?:muốn|cần\\s+em|để\\s+em)\\s+(?:sắp\\s+xếp|đặt|lên)\\s+(?:thời\\s+gian|lịch)|"
            + "sắp\\s+xếp\\s+(?:thời\\s+gian|lịch)\\s+nào|kiểm\\s+tra\\s+lịch\\s+trống|"
            + "hôm\\s+nay\\s+(?:hay|hoặc)\\s+ngày\\s+mai|tiện\\s+(?:ghé|qua)|"
            + "(?:ghé|qua|hẹn)\\s+(?:lúc|khung|vào|tầm)\\s+\\d{1,2}\\s*(?:(?:h|g)(?:\\d{1,2})?|giờ(?:\\s*\\d{1,2})?|:\\d{2}))\\b.*"
    );

    private static final Pattern SINGLE_PRODUCT_FIT_CONFIRMATION_QUESTION_CLEAN_PATTERN = Pattern.compile(
            "(?isU).*\\b(?:xem\\s+thử|thấy)\\s+(?:hướng|phương\\s+án|gói|liệu\\s+trình|mẫu|khóa|dòng|lựa\\s+chọn|mức)[^.!?]{0,120}\\b(?:vừa\\s+vặn|phù\\s+hợp|hợp\\s+lý|đúng\\s+ý|ưng|ổn)\\b[^.!?]{0,80}\\b(?:nhé|nha|chưa|không)(?:\\s+ạ)?\\s*\\?\\s*$"
    );

    private static final Pattern CONCRETE_TIME_PROPOSAL_CUE_CLEAN_PATTERN = Pattern.compile(
            "(?isU).*(?:\\?|\\b(?:nhé|nha|ghé\\s+lúc|qua\\s+lúc|hẹn\\s+lúc|vào\\s+lúc|còn\\s+khung|còn\\s+lịch|giữ\\s+lịch|sắp\\s+xếp\\s+lúc)\\b).*"
    );

    private static final Pattern PROACTIVE_PRICE_OR_PRODUCT_REQUEST_CLEAN_PATTERN = Pattern.compile(
            "(?isU).*\\b(giá|bảng\\s+giá|chi\\s+phí|học\\s+phí|bao\\s+nhiêu|báo\\s+giá|tư\\s+vấn\\s+giá|menu|"
            + "các\\s+(?:gói|mẫu|khóa|dòng)|(?:gói|mẫu|khóa|dòng)\\s+nào|toàn\\s+thân|toàn\\s+hàm|cả\\s+người|full\\s+body)\\b.*"
    );

    private static final Pattern PRODUCT_COMPARISON_QUESTION_CLEAN_PATTERN = Pattern.compile(
            "(?isU).*\\b(khác\\s+(?:gì|j|nhau|sao|thế\\s+nào)|so\\s+với|so\\s+sánh|hơn\\s+(?:gì|j|nhau))\\b.*"
    );

    private LlmClient llmClient;
    private AgentBrainService agentBrainService;
    private AgentSkillService agentSkillService;
    @Autowired(required = false)
    private InboundSessionPersistenceService inboundSessionPersistenceService;
    private volatile java.util.function.BiPredicate<UUID, String> preCommitOutboundPermitChecker = null;
    private volatile AdaptiveConversationIntelligence.CognitiveMode cognitiveMode =
            AdaptiveConversationIntelligence.CognitiveMode.LLM_COGNITIVE_V2;
    private volatile boolean aciCognitiveLoopActive = false;
    private volatile AdaptiveConversationIntelligence.HandoffEventDispatcher handoffEventDispatcher;
    private volatile AdaptiveConversationIntelligence.AdaptiveCognitiveReflection lastAciReflection;
    private volatile AdaptiveConversationIntelligence.ConditionalHandoffDecision lastHandoffDecision;
    private volatile AdaptiveConversationIntelligence.TurnContextSnapshot lastTurnContextSnapshot;
    private volatile ThinkingModePolicy.AciTurnTelemetry lastAciTurnTelemetry;
    private volatile String lastBuiltPrompt;
    private volatile ThinkingModePolicy.TurnTelemetry lastTurnTelemetry;
    private volatile ConversationState.StructuredBookingState lastTurnBookingState =
            ConversationState.StructuredBookingState.none();
    private volatile BookingGateway.Mode bookingGatewayMode = BookingGateway.Mode.DISABLED;
    @Autowired(required = false)
    private BookingGateway bookingGateway = new MockBookingGateway();
    private volatile BookingExecutionResult lastTurnBookingExecutionResult = BookingExecutionResult.disabled();
    private volatile ThinkingMode lastSelectedThinkingMode = ThinkingMode.FAST_NO_THINK;

    public CleanAgentOrchestratorService() {
    }

    @Autowired
    public CleanAgentOrchestratorService(
            @Autowired(required = false) LlmClient llmClient,
            @Autowired(required = false) AgentBrainService agentBrainService,
            @Autowired(required = false) AgentSkillService agentSkillService
    ) {
        this.llmClient = llmClient;
        this.agentBrainService = agentBrainService;
        this.agentSkillService = agentSkillService;
    }

    @org.springframework.beans.factory.annotation.Value("${docmind.bot.cognitive-mode:LLM_COGNITIVE_V2}")
    public void setCognitiveModeProperty(String rawMode) {
        this.cognitiveMode = AdaptiveConversationIntelligence.CognitiveMode.fromProperty(rawMode);
        this.aciCognitiveLoopActive = true;
    }

    @org.springframework.beans.factory.annotation.Value("${booking.gateway.mode:DISABLED}")
    public void setBookingGatewayModeProperty(String rawMode) {
        this.bookingGatewayMode = BookingGateway.Mode.fromProperty(rawMode);
    }

    public void setBookingGatewayMode(BookingGateway.Mode mode) {
        this.bookingGatewayMode = mode != null ? mode : BookingGateway.Mode.DISABLED;
    }

    public BookingGateway.Mode getBookingGatewayMode() {
        return bookingGatewayMode;
    }

    public void setBookingGateway(BookingGateway bookingGateway) {
        this.bookingGateway = bookingGateway != null ? bookingGateway : new MockBookingGateway();
    }

    public BookingGateway getBookingGateway() {
        return bookingGateway;
    }

    public BookingExecutionResult getLastTurnBookingExecutionResult() {
        return lastTurnBookingExecutionResult;
    }

    public void setCognitiveMode(AdaptiveConversationIntelligence.CognitiveMode cognitiveMode) {
        this.cognitiveMode = cognitiveMode != null
                ? cognitiveMode
                : AdaptiveConversationIntelligence.CognitiveMode.LLM_COGNITIVE_V2;
        this.aciCognitiveLoopActive = (cognitiveMode != null);
    }

    public AdaptiveConversationIntelligence.CognitiveMode getCognitiveMode() {
        return cognitiveMode;
    }

    public void setHandoffEventDispatcher(AdaptiveConversationIntelligence.HandoffEventDispatcher handoffEventDispatcher) {
        this.handoffEventDispatcher = handoffEventDispatcher;
    }

    public AdaptiveConversationIntelligence.HandoffEventDispatcher getHandoffEventDispatcher() {
        return handoffEventDispatcher;
    }

    public AdaptiveConversationIntelligence.AdaptiveCognitiveReflection getLastAciReflection() {
        return lastAciReflection;
    }

    public AdaptiveConversationIntelligence.ConditionalHandoffDecision getLastHandoffDecision() {
        return lastHandoffDecision;
    }

    public AdaptiveConversationIntelligence.TurnContextSnapshot getLastTurnContextSnapshot() {
        return lastTurnContextSnapshot;
    }

    public ThinkingModePolicy.AciTurnTelemetry getLastAciTurnTelemetry() {
        return lastAciTurnTelemetry;
    }

    public String getLastBuiltPrompt() {
        return lastBuiltPrompt;
    }

    public void setLlmClient(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public void setAgentBrainService(AgentBrainService agentBrainService) {
        this.agentBrainService = agentBrainService;
    }

    public void setAgentSkillService(AgentSkillService agentSkillService) {
        this.agentSkillService = agentSkillService;
    }

    public void setInboundSessionPersistenceService(InboundSessionPersistenceService inboundSessionPersistenceService) {
        this.inboundSessionPersistenceService = inboundSessionPersistenceService;
    }

    public void setPreCommitOutboundPermitChecker(java.util.function.BiPredicate<UUID, String> checker) {
        this.preCommitOutboundPermitChecker = checker;
    }

    private boolean isOutboundStillPermittedLayer2(UUID pageId, FanpageBotRequest request) {
        String senderId = request != null ? request.senderId() : null;
        if (preCommitOutboundPermitChecker != null && !preCommitOutboundPermitChecker.test(pageId, senderId)) {
            return false;
        }
        return inboundSessionPersistenceService == null
                || inboundSessionPersistenceService.verifyOutboundStillPermittedFresh(pageId, senderId);
    }

    public ThinkingModePolicy.TurnTelemetry getLastTurnTelemetry() {
        return lastTurnTelemetry;
    }

    public ThinkingMode getLastSelectedThinkingMode() {
        return lastSelectedThinkingMode;
    }

    public ConversationState.StructuredBookingState getLastTurnBookingState() {
        return lastTurnBookingState;
    }

    public FanpageBotResponse executeCleanAgentTurn(
            UUID pageId,
            FanpageBotRequest request,
            String mergedMessage,
            String honorific,
            PromptBuilder.PagePersona pagePersona,
            ConversationState priorStateWithChunks,
            ConversationState convState,
            List<ReciprocalRankFusion.FusedChunk> fused,
            List<LearnedRuleDto> appliedRules,
            FanpageBotResponse.ExtractedLead extractedLead,
            CustomerInsightDto customerInsight,
            List<AgentSkillDto> activeSkills,
            List<AgentSkillDto> candidateSkills,
            FanpageBotPromptBuilder.SalesStage salesStage,
            int incomingHistorySize,
            long turnStartNs,
            long retrievalMs
    ) {
        return executeCleanAgentTurn(
                pageId,
                request,
                mergedMessage,
                honorific,
                pagePersona,
                priorStateWithChunks,
                convState,
                fused,
                appliedRules,
                extractedLead,
                customerInsight,
                activeSkills,
                candidateSkills,
                salesStage,
                incomingHistorySize,
                turnStartNs,
                retrievalMs,
                null
        );
    }

    public FanpageBotResponse executeCleanAgentTurn(
            UUID pageId,
            FanpageBotRequest request,
            String mergedMessage,
            String honorific,
            PromptBuilder.PagePersona pagePersona,
            ConversationState priorStateWithChunks,
            ConversationState convState,
            List<ReciprocalRankFusion.FusedChunk> fused,
            List<LearnedRuleDto> appliedRules,
            FanpageBotResponse.ExtractedLead extractedLead,
            CustomerInsightDto customerInsight,
            List<AgentSkillDto> activeSkills,
            List<AgentSkillDto> candidateSkills,
            FanpageBotPromptBuilder.SalesStage salesStage,
            int incomingHistorySize,
            long turnStartNs,
            long retrievalMs,
            AdaptiveConversationIntelligence.ThreeStreamTurnInput prebuiltThreeStreamInput
    ) {
        if (this.aciCognitiveLoopActive && this.cognitiveMode == AdaptiveConversationIntelligence.CognitiveMode.LLM_COGNITIVE_V2) {
            return executeAciCognitiveTurn(
                    pageId,
                    request,
                    mergedMessage,
                    honorific,
                    pagePersona,
                    priorStateWithChunks,
                    convState,
                    fused,
                    appliedRules,
                    extractedLead,
                    customerInsight,
                    activeSkills,
                    candidateSkills,
                    salesStage,
                    incomingHistorySize,
                    turnStartNs,
                    retrievalMs,
                    prebuiltThreeStreamInput
            );
        }
        ConversationState safePrior = priorStateWithChunks != null ? priorStateWithChunks : ConversationState.initial();
        ConversationState safeConv = convState != null ? convState : safePrior;
        String safeHonorific = (honorific != null && !honorific.isBlank()) ? honorific.strip() : "mình";
        String selfPronoun = (pagePersona != null && pagePersona.resolvedConfig() != null)
                ? pagePersona.resolvedConfig().resolvedSelfPronoun()
                : "em";
        int minDelayMs = pagePersona != null && pagePersona.resolvedConfig() != null
                ? pagePersona.resolvedConfig().resolvedPostPriceDelayMs()
                : 10_000;

        if (safePrior.optedOut() || safePrior.humanTakeover()) {
            FanpageBotService.recordCurrentTurnGuard("BLOCK", "BOT_MUTED");
            FanpageBotService.markProvenanceV38Logged();
            return new FanpageBotResponse(
                    "",
                    List.of(),
                    List.of(),
                    "HUMAN_HANDOFF",
                    1.0,
                    safePrior.humanTakeover(),
                    null,
                    null,
                    extractedLead,
                    List.of(),
                    customerInsight,
                    List.of(),
                    List.of("BOT_MUTED")
            );
        }

        boolean isFirstTurnBrandIntro = !safePrior.greetingSent()
                && (safeConv.knownSymptoms() == null || safeConv.knownSymptoms().isEmpty())
                && (safeConv.knownBodyAreas() == null || safeConv.knownBodyAreas().isEmpty())
                && pagePersona != null
                && pagePersona.resolvedConfig() != null
                && pagePersona.resolvedConfig().openingBubbles() != null
                && !pagePersona.resolvedConfig().openingBubbles().isEmpty();
        if (isFirstTurnBrandIntro) {
            PrimaryActionRouter.RoutingDecision introDecision = new PrimaryActionRouter.RoutingDecision(
                    PrimaryActionRouter.PrimaryAction.CLARIFY_SERVICE_DURATION,
                    PrimaryActionRouter.PrimaryAction.CLARIFY_SERVICE_DURATION,
                    true,
                    3,
                    "CLARIFY_SERVICE_TYPE_ONCE",
                    "spa_clarify_service_type_once",
                    "R01-BRAND-INTRO-3-BUBBLES"
            );
            List<FanpageBotResponse.ReplyBubble> introBubbles = PrimaryActionRouter.executeActionBubbles(
                    introDecision,
                    mergedMessage,
                    safeConv,
                    safeHonorific,
                    pagePersona,
                    fused != null ? fused : List.of(),
                    null
            );
            if (introBubbles != null && !introBubbles.isEmpty()) {
                String ledgerKeyIntro = SentBubbleLedger.key(request.pageId(), request.senderId(), request.conversationId());
                String ledgerTurnIdIntro = SentBubbleLedger.stableUserTurnId(ledgerKeyIntro, mergedMessage);
                SentBubbleLedger.recordCustomerTurn(ledgerKeyIntro, ledgerTurnIdIntro, mergedMessage);
                SentBubbleLedger.recordAssistantTurn(
                        ledgerKeyIntro,
                        ledgerTurnIdIntro + ":a",
                        introBubbles.stream().map(FanpageBotResponse.ReplyBubble::text).toList(),
                        SentBubbleLedger.DeliveryStatus.PENDING,
                        List.of()
                );
                if (!SenderEpochRegistry.checkThreadEpochStillCurrent(
                        request != null ? request.pageId() : null,
                        request != null ? request.senderId() : null,
                        SenderEpochRegistry.Checkpoint.BEFORE_STATE_AND_LEDGER_COMMIT)) {
                    SentBubbleLedger.markAssistantBubblesSuppressedByFenceFrom(
                            ledgerKeyIntro, ledgerTurnIdIntro + ":a", 0, "SUPERSEDED_BY_NEWER_EPOCH");
                    return buildSupersededAbortedResponse(extractedLead, customerInsight);
                }
                if (!isOutboundStillPermittedLayer2(pageId, request)) {
                    SentBubbleLedger.markAssistantBubblesSuppressedByFenceFrom(
                            ledgerKeyIntro, ledgerTurnIdIntro + ":a", 0, "SUPPRESSED_STALE_TURN_AFTER_STATE_LOCK");
                    log.warn("[CLEAN-INTRO-LAYER2-FENCE] Stale intro suppressed before ledger commit (page_id={} sender_id={})",
                            pageId, request != null ? request.senderId() : null);
                    FanpageBotService.markProvenanceV38Logged();
                    return FanpageBotService.buildSilentSuppressedResponse("SUPPRESSED_STALE_TURN_AFTER_STATE_LOCK", null);
                }
                String introText = String.join("\n\n", introBubbles.stream().map(FanpageBotResponse.ReplyBubble::text).toList());
                ConversationState.updateAfterSend(
                        pageId,
                        request.senderId(),
                        request.conversationId(),
                        safeConv,
                        mergedMessage,
                        introDecision,
                        introBubbles,
                        false,
                        incomingHistorySize
                );
                List<Integer> introDelays = introBubbles.stream().map(FanpageBotResponse.ReplyBubble::typingDelayMs).toList();
                FanpageBotService.markProvenanceV38Logged();
                return new FanpageBotResponse(
                        introText,
                        introBubbles,
                        introDelays,
                        FanpageBotPromptBuilder.SalesStage.DISCOVERY.code(),
                        computeConfidenceScore(fused != null ? fused : List.of()),
                        false,
                        null,
                        null,
                        extractedLead,
                        appliedRules != null ? appliedRules : List.of(),
                        customerInsight,
                        activeSkills != null ? activeSkills : List.of(),
                        List.of("CLEAN_AGENT_BRAND_INTRO")
                );
            }
        }

        List<FanpageBotRequest.ConversationTurn> safeHistory = request.history() != null ? request.history() : List.of();
        String prevBotTurnText = resolveImmediatelyPrecedingBotText(safeHistory, safePrior.lastAssistantMessage());
        String recalledSpeechAct = (agentBrainService != null)
                ? agentBrainService.getRecalledActualSentSpeechAct(pageId, request.senderId())
                : "";
        String effectivePrecedingSpeechAct = inferPrecedingBotSpeechAct(
                customerInsight,
                prevBotTurnText,
                safeHistory,
                safePrior.bookingStatus(),
                recalledSpeechAct
        );

        boolean isMismatch = SentimentIntentLayer.isEntityMismatchCorrection(mergedMessage)
                || "ENTITY_MISMATCH_CORRECTION".equals(safeConv.lastIntent());
        boolean isChallengeAssumption = isChallengeOrQuestionBotAssumption(mergedMessage);
        boolean isStyleOrRepetitionComplaint = SentimentIntentLayer.isConversationStyleComplaint(mergedMessage)
                || SentimentIntentLayer.isWordingOrExpressionFeedback(mergedMessage)
                || SentimentIntentLayer.isAlreadyAnsweredSlotPushback(mergedMessage)
                || (PrimaryActionRouter.isFrustrationMessage(mergedMessage)
                    && !isConfirmedCustomerPriceObjection(mergedMessage, true));
        boolean isMetaOrChallenge = isMismatch || isChallengeAssumption || isStyleOrRepetitionComplaint;

        boolean hadPriorPackageInConversation = safePrior.packageRecommended()
                || safePrior.priceDiscussed()
                || (safePrior.heldPackage() != null && !safePrior.heldPackage().isBlank())
                || (safePrior.lastQuotedPackage() != null && !safePrior.lastQuotedPackage().isBlank())
                || hasQuotedPriceInAssistantHistory(safeHistory);

        boolean isExplicitDownsell = !isMetaOrChallenge
                && isExplicitDownsellOrLowBudgetRequest(mergedMessage, safeConv);
        boolean isHesitation = !isMetaOrChallenge && isHesitationOrDeferralMessage(mergedMessage);
        boolean confirmedPriceObjectionThisTurn = !isMetaOrChallenge
                && !isHesitation
                && (isConfirmedCustomerPriceObjection(mergedMessage, hadPriorPackageInConversation || safeConv.priceDiscussed())
                    || isExplicitDownsell);
        boolean hasPriceObjectionKeyword = confirmedPriceObjectionThisTurn;

        int recalledPriceObjCount = agentBrainService != null
                ? agentBrainService.getRecalledPriceObjectionCount(pageId, request.senderId())
                : 0;
        boolean recalledInPriceContext = agentBrainService != null
                && agentBrainService.isRecalledInPriceObjectionContext(pageId, request.senderId());
        boolean recalledHasPresentedDownsell = agentBrainService != null
                && agentBrainService.hasRecalledPresentedDownsell(pageId, request.senderId());

        int historyConfirmedPriceObjections = countConfirmedCustomerPriceObjectionsInHistory(safeHistory);
        int priorPriceObjectionCount = !safeHistory.isEmpty()
                ? Math.max(historyConfirmedPriceObjections, recalledPriceObjCount)
                : Math.max(recalledPriceObjCount, Math.max(0, safePrior.objectionCount()));
        int priceObjectionCount = confirmedPriceObjectionThisTurn
                ? (priorPriceObjectionCount + 1)
                : priorPriceObjectionCount;
        boolean inPriceObjectionContext = priceObjectionCount >= 1
                || recalledInPriceContext
                || (customerInsight != null && customerInsight.inPriceObjectionContext());
        boolean priorHasPresentedDownsell = safePrior.downsellOffered()
                || recalledHasPresentedDownsell
                || (customerInsight != null && customerInsight.hasPresentedDownsell());

        com.example.docmind.dto.PagePersonaConfig effectivePersonaConfig = pagePersona != null
                ? pagePersona.resolvedConfig()
                : null;
        List<String> domainKeywords = effectivePersonaConfig != null
                ? effectivePersonaConfig.resolvedScopeTaxonomy()
                : List.of();
        List<PackageCatalogService.PackageSummary> allCatalog =
                (safeConv.availablePackageCatalog() != null && !safeConv.availablePackageCatalog().isEmpty())
                        ? safeConv.availablePackageCatalog()
                        : PackageCatalogService.filterByRegion(
                                PackageCatalogService.extractCatalog(fused != null ? fused : List.of(), domainKeywords),
                                safeConv.preferredRegion());

        List<String> knownSymptoms = safeConv.knownSymptoms() != null ? safeConv.knownSymptoms() : List.of();
        List<String> knownBodyAreas = ScopeMatcher.mergeScopes(
                mergedMessage,
                safeConv.knownBodyAreas() != null ? safeConv.knownBodyAreas() : List.of(),
                allCatalog,
                effectivePersonaConfig
        );
        boolean isOperationalQuestion = isOperationalOrLogisticsQuestion(mergedMessage);
        String statedContextOrTiming = resolveStatedContextOrTiming(safeConv, safePrior, safeHistory, mergedMessage, isMetaOrChallenge);
        int discoveryQuestionCount = resolveDiscoveryQuestionCount(safePrior, safeHistory);
        boolean customerProactivelyAskedPriceOrProduct = safeConv.priceRequestedInSession()
                || isProactivePriceOrProductRequest(mergedMessage, allCatalog);
        boolean pageRequiresTargetScopeDiscovery = !domainKeywords.isEmpty() && !allCatalog.isEmpty();
        boolean narrowedTargetScopePresent = hasNarrowedTargetScope(knownBodyAreas, domainKeywords);
        boolean missingInitialTargetScopeForDiscovery = pageRequiresTargetScopeDiscovery
                && !hadPriorPackageInConversation
                && discoveryQuestionCount == 0
                && !narrowedTargetScopePresent
                && !customerProactivelyAskedPriceOrProduct
                && !isOperationalQuestion;
        boolean conditionClarified = !missingInitialTargetScopeForDiscovery
                && (safeConv.conditionClarified()
                    || discoveryQuestionCount >= 1
                    || narrowedTargetScopePresent
                    || customerProactivelyAskedPriceOrProduct
                    || !"chưa có".equals(statedContextOrTiming));
        String rawHeldPackage = (isMismatch || !hadPriorPackageInConversation)
                ? null
                : (safeConv.heldPackage() != null ? safeConv.heldPackage()
                    : (safeConv.lastQuotedPackage() != null ? safeConv.lastQuotedPackage()
                        : safeConv.knownRecommendedPackage()));
        String rawHeldDuration = (isMismatch || !hadPriorPackageInConversation)
                ? null
                : (safeConv.heldDuration() != null ? safeConv.heldDuration() : safeConv.lastQuotedDuration());
        java.math.BigDecimal rawHeldPrice = (isMismatch || !hadPriorPackageInConversation)
                ? null
                : (safeConv.heldPrice() != null ? safeConv.heldPrice() : safeConv.lastQuotedPrice());

        boolean isProductComparison = !isMetaOrChallenge && isProductComparisonQuestion(mergedMessage);
        CleanAgentCatalogAndPromptBuilder.TieredCatalogResult tieredCatalog =
                CleanAgentCatalogAndPromptBuilder.rankAndTierCatalogForCleanAgent(
                        allCatalog,
                        knownSymptoms,
                        knownBodyAreas,
                        isProductComparison ? Set.of() : safeConv.rejectedPackages(),
                        isMismatch
                );
        boolean hasEconomicalAltInCatalog = tieredCatalog.economicalAlternatives() != null
                && !tieredCatalog.economicalAlternatives().isEmpty();
        boolean hasPresentedDownsell = !safeHistory.isEmpty()
                ? (hasEconomicalAltInCatalog && CleanAgentCatalogAndPromptBuilder.hasPackageMentionedInBotHistory(
                        safeHistory, tieredCatalog.economicalAlternatives()))
                : priorHasPresentedDownsell;

        AgentBrainService.ShortUtteranceResolution shortUtteranceRes = (!isMetaOrChallenge && !hasPriceObjectionKeyword)
                ? (agentBrainService != null
                    ? agentBrainService.decodeContextualShortUtterance(
                            mergedMessage,
                            prevBotTurnText,
                            effectivePrecedingSpeechAct,
                            safeHistory,
                            priceObjectionCount,
                            inPriceObjectionContext,
                            hasPresentedDownsell,
                            hasEconomicalAltInCatalog,
                            safePrior.bookingStatus())
                    : AgentBrainService.decodeContextualShortUtteranceStatic(
                            mergedMessage,
                            prevBotTurnText,
                            effectivePrecedingSpeechAct,
                            safeHistory,
                            priceObjectionCount,
                            inPriceObjectionContext,
                            hasPresentedDownsell,
                            hasEconomicalAltInCatalog,
                            safePrior.bookingStatus()))
                : AgentBrainService.ShortUtteranceResolution.notApplicable();

        boolean brainDecodedPositiveConfirm = shortUtteranceRes.isContextualShortUtterance()
                && "POSITIVE_CONFIRMATION_SIGNAL".equals(shortUtteranceRes.decodedMeaning());
        boolean brainDecodedOperationalAck = shortUtteranceRes.isContextualShortUtterance()
                && "ACKNOWLEDGED_OPERATIONAL_INFO".equals(shortUtteranceRes.decodedMeaning());
        boolean brainDecodedPriceHesitation = shortUtteranceRes.isContextualShortUtterance()
                && ("PRICE_HESITATION_AFTER_VALUE_DEFENSE".equals(shortUtteranceRes.decodedMeaning())
                    || "PRICE_CONTEXT_HESITATION_WITH_AVAILABLE_ECONOMICAL_OPTION".equals(shortUtteranceRes.decodedMeaning()));
        boolean brainDecodedPoliteDeferral = shortUtteranceRes.isContextualShortUtterance()
                && ("EXPLICIT_POLITE_DEFERRAL_PAUSE".equals(shortUtteranceRes.decodedMeaning())
                    || "POLITE_CLOSING_ACKNOWLEDGMENT".equals(shortUtteranceRes.decodedMeaning())
                    || "PRICE_HESITATION_NO_UNPRESENTED_ECONOMICAL_OPTION".equals(shortUtteranceRes.decodedMeaning()));
        boolean brainDecodedLukewarmPostQuote = shortUtteranceRes.isContextualShortUtterance()
                && ("LUKEWARM_POST_PRICE_QUOTE_EVALUATION".equals(shortUtteranceRes.decodedMeaning())
                    || "ACKNOWLEDGED_GENERAL_INFO_WARM_BRIDGE".equals(shortUtteranceRes.decodedMeaning()));

        if (brainDecodedPriceHesitation || brainDecodedPoliteDeferral) {
            isHesitation = true;
        }

        if (request != null && Boolean.TRUE.equals(request.resetSession()) && agentBrainService != null) {
            agentBrainService.recordBookingState(pageId, request.senderId(), ConversationState.StructuredBookingState.none());
        }
        ConversationState.StructuredBookingState priorBookingState = safePrior.structuredBookingState();
        if ((priorBookingState == null || priorBookingState.status() == ConversationState.BookingStatus.NONE)
                && agentBrainService != null
                && !safeHistory.isEmpty()) {
            ConversationState.StructuredBookingState brainRecalledBooking =
                    agentBrainService.recallBookingState(pageId, request.senderId());
            if (brainRecalledBooking != null && brainRecalledBooking.status() != ConversationState.BookingStatus.NONE) {
                priorBookingState = brainRecalledBooking;
            }
        }
        if (priorBookingState == null) {
            priorBookingState = ConversationState.StructuredBookingState.none();
        }
        java.time.ZoneId pageZoneId = java.time.ZoneId.of("Asia/Ho_Chi_Minh");

        String replyToMid = request != null && request.replyToMid() != null ? request.replyToMid().strip() : "";
        String quotedMessageText = request != null && request.quotedMessageText() != null ? request.quotedMessageText().strip() : "";
        if (quotedMessageText.isEmpty() && !replyToMid.isEmpty() && request != null) {
            String lookedUpQuoted = SentBubbleLedger.lookupMessageByMid(request.pageId(), request.senderId(), replyToMid);
            quotedMessageText = lookedUpQuoted != null ? lookedUpQuoted.strip() : "";
        }
        boolean hasReplyToContext = !replyToMid.isEmpty() || !quotedMessageText.isEmpty();

        List<PackageCatalogService.PackageSummary> presentedPackagesInHistory =
                CleanAgentCatalogAndPromptBuilder.extractPresentedPackagesFromHistory(safeHistory, safePrior, allCatalog);
        CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution quotedReplyResolution =
                CleanAgentCatalogAndPromptBuilder.resolveQuotedReplyContext(
                        replyToMid,
                        quotedMessageText,
                        allCatalog,
                        presentedPackagesInHistory
                );
        CleanAgentCatalogAndPromptBuilder.ReplyResolutionState replyResolutionState =
                quotedReplyResolution.resolutionState();

        boolean isQuestionSentence = mergedMessage != null && mergedMessage.contains("?");
        PackageCatalogService.PackageSummary preMatchedExplicitSelection = (!isMetaOrChallenge
                && !hasPriceObjectionKeyword
                && !isHesitation
                && !isOperationalQuestion
                && !isQuestionSentence
                && !isNormalPriceOrDetailInquiryMessage(mergedMessage)
                && !isPromoOrDiscountInquiryMessage(mergedMessage))
                ? matchExplicitProductSelectionInMessage(
                        mergedMessage,
                        presentedPackagesInHistory,
                        allCatalog,
                        tieredCatalog,
                        quotedReplyResolution
                )
                : null;
        ConversationState.ParsedBookingDateTime preParsedTurnDateTime = (!isMetaOrChallenge
                && !hasPriceObjectionKeyword
                && !isHesitation
                && !isOperationalQuestion
                && !isQuestionSentence)
                ? ConversationState.parseBookingDateTime(mergedMessage, null, null, pageZoneId, null)
                : new ConversationState.ParsedBookingDateTime(null, null, null, pageZoneId.getId());
        boolean isActiveBookingSlotContinuation = priorBookingState.status().isActiveOrConfirmed()
                && priorBookingState.status() != ConversationState.BookingStatus.CONFIRMED
                && !isMetaOrChallenge
                && !hasPriceObjectionKeyword
                && !isHesitation
                && !isOperationalQuestion
                && !isQuestionSentence
                && !isNormalPriceOrDetailInquiryMessage(mergedMessage)
                && !isPromoOrDiscountInquiryMessage(mergedMessage)
                && (preMatchedExplicitSelection != null
                    || preParsedTurnDateTime.bookingDate() != null
                    || preParsedTurnDateTime.bookingTime() != null);

        boolean isBareAck = !isMetaOrChallenge
                && !hasPriceObjectionKeyword
                && !isHesitation
                && !isActiveBookingSlotContinuation
                && isBareAcknowledgmentMessage(mergedMessage);
        boolean hasProactiveBookingInfo = !isMetaOrChallenge
                && !hasPriceObjectionKeyword
                && !isHesitation
                && (isActiveBookingSlotContinuation || hasProactiveBookingOrPackageSelectionInMessage(mergedMessage));
        boolean isBookingAlreadyConfirmed = priorBookingState.status() == ConversationState.BookingStatus.CONFIRMED
                || "CONFIRMED".equalsIgnoreCase(safeConv.bookingStatus())
                || "CONFIRMED".equalsIgnoreCase(safePrior.bookingStatus())
                || SPEECH_ACT_BOOKING_CONFIRMED.equalsIgnoreCase(effectivePrecedingSpeechAct);
        boolean isPostBookingClosingAck = (isBareAck && isBookingAlreadyConfirmed)
                || (shortUtteranceRes.isContextualShortUtterance()
                    && "POST_BOOKING_CLOSING_ACK".equals(shortUtteranceRes.decodedMeaning()));
        boolean isPoliteClosingAck = brainDecodedPoliteDeferral
                || (isBareAck
                    && !isPostBookingClosingAck
                    && !brainDecodedOperationalAck
                    && (SPEECH_ACT_RESPECTED_PAUSE.equalsIgnoreCase(effectivePrecedingSpeechAct)
                        || wasPriorCustomerTurnHesitation(safeHistory)
                        || isPauseOrDeferralBotReply(prevBotTurnText)));
        boolean isAckForOfferedDetails = (isBareAck
                && !isPostBookingClosingAck
                && !isPoliteClosingAck
                && SPEECH_ACT_OFFERED_DETAILS.equalsIgnoreCase(effectivePrecedingSpeechAct))
                || (shortUtteranceRes.isContextualShortUtterance()
                    && "ACCEPTED_PACKAGE_DETAIL_OFFER".equals(shortUtteranceRes.decodedMeaning()));

        boolean isDetailOrUnitQuestion = !isActiveBookingSlotContinuation
                && (isAckForOfferedDetails
                    || brainDecodedOperationalAck
                    || isOperationalQuestion
                    || isProductComparison
                    || (!hasProactiveBookingInfo && SentimentIntentLayer.isDetailOrFaqQuestion(mergedMessage, safeConv))
                    || isNormalPriceOrDetailInquiryMessage(mergedMessage)
                    || isPromoOrDiscountInquiryMessage(mergedMessage)
                    || (hasReplyToContext && !hasProactiveBookingInfo && !isBareAck && !hasPriceObjectionKeyword && !isHesitation)
                    || (mergedMessage != null && mergedMessage.toLowerCase(Locale.ROOT).matches("(?isU).*\\b(?:1|một)\\s+buổi\\s+(?:à|hả|phải\\s+không|đúng\\s+không|hay\\s+sao)\\b.*")));

        boolean isPositiveTrialInterest = !isMetaOrChallenge
                && !hasPriceObjectionKeyword
                && !isHesitation
                && !isPoliteClosingAck
                && !isPostBookingClosingAck
                && !isDetailOrUnitQuestion
                && !brainDecodedLukewarmPostQuote
                && (brainDecodedPositiveConfirm
                    || isPositiveInterestForTrialInvite(mergedMessage)
                    || (isBareAck && SPEECH_ACT_INVITED_TRIAL_OR_TIME.equalsIgnoreCase(effectivePrecedingSpeechAct)));
        boolean isAckOrAcceptNow = !isMetaOrChallenge
                && !hasPriceObjectionKeyword
                && !isHesitation
                && !isPoliteClosingAck
                && !isPostBookingClosingAck
                && !isDetailOrUnitQuestion
                && !brainDecodedLukewarmPostQuote
                && (isPositiveTrialInterest
                    || hasProactiveBookingInfo
                    || SentimentIntentLayer.isTrialAcceptanceMessage(mergedMessage)
                    || ((safeConv.packageRecommended() || safeConv.priceDiscussed())
                        && PrimaryActionRouter.isAcknowledgementMessage(mergedMessage)));
        boolean priorTurnWasPause = SPEECH_ACT_RESPECTED_PAUSE.equalsIgnoreCase(effectivePrecedingSpeechAct)
                || wasPriorCustomerTurnHesitation(safeHistory);
        boolean packageInterestAccepted = !hasPriceObjectionKeyword
                && !isHesitation
                && !isPoliteClosingAck
                && !isPostBookingClosingAck
                && !isDetailOrUnitQuestion
                && !brainDecodedLukewarmPostQuote
                && (isAckOrAcceptNow
                    || (!priorTurnWasPause && (safeConv.packageInterestAccepted()
                        || priorBookingState.status().isActiveOrConfirmed()
                        || "COLLECTING_INFO".equals(safePrior.bookingStatus())
                        || "COLLECTING_VISIT_TIME".equals(safePrior.bookingStatus()))));
        if (packageInterestAccepted) {
            inPriceObjectionContext = false;
        }

        ConversationState.StructuredBookingState rawBookingState = resolveMultiIndustryBookingState(
                mergedMessage,
                priorBookingState,
                packageInterestAccepted || isBookingAlreadyConfirmed,
                isHesitation || isPoliteClosingAck || isChallengeAssumption || isMismatch,
                isBookingAlreadyConfirmed,
                presentedPackagesInHistory,
                allCatalog,
                tieredCatalog,
                hasProactiveBookingInfo ? quotedReplyResolution : CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                pageZoneId,
                null,
                prevBotTurnText
        );
        BookingLifecycleEvaluation bookingLifecycleEval = applyBookingGatewayLifecycle(
                pageId,
                request != null ? request.senderId() : null,
                priorBookingState,
                rawBookingState,
                mergedMessage,
                safeConv.preferredRegion()
        );
        ConversationState.StructuredBookingState bookingState = bookingLifecycleEval.bookingState();
        String bookingFunnelStatus = bookingState.status().name();

        List<String> currentTurnSignals = new ArrayList<>(classifyCleanCurrentTurnSignals(
                mergedMessage,
                isChallengeAssumption,
                isStyleOrRepetitionComplaint,
                isMismatch,
                isDetailOrUnitQuestion,
                hasPriceObjectionKeyword,
                priceObjectionCount,
                isAckOrAcceptNow,
                packageInterestAccepted,
                knownSymptoms,
                knownBodyAreas,
                statedContextOrTiming,
                effectivePrecedingSpeechAct,
                bookingFunnelStatus,
                safeHistory
        ));
        if (shortUtteranceRes.isContextualShortUtterance()) {
            for (String sig : shortUtteranceRes.recommendedSignals()) {
                if (!currentTurnSignals.contains(sig)) {
                    currentTurnSignals.add(sig);
                }
            }
        }

        if (quotedReplyResolution.quotedPackage() != null) {
            if (!currentTurnSignals.contains("QUOTED_PACKAGE_RESOLVED")) {
                currentTurnSignals.add("QUOTED_PACKAGE_RESOLVED");
            }
        } else if (quotedReplyResolution.ambiguousMultiplePackages()) {
            if (!currentTurnSignals.contains("QUOTED_MESSAGE_AMBIGUOUS_PACKAGES")) {
                currentTurnSignals.add("QUOTED_MESSAGE_AMBIGUOUS_PACKAGES");
            }
        } else if (quotedReplyResolution.unresolvedReplyToMid()) {
            if (!currentTurnSignals.contains("UNRESOLVED_REPLY_TO_MID")) {
                currentTurnSignals.add("UNRESOLVED_REPLY_TO_MID");
            }
        }

        String cleanStage = (shortUtteranceRes.isContextualShortUtterance()
                && !shortUtteranceRes.recommendedStage().isBlank()
                && !hasReplyToContext)
                ? shortUtteranceRes.recommendedStage()
                : resolveCleanFunnelStage(
                        currentTurnSignals,
                        mergedMessage,
                        priceObjectionCount,
                        inPriceObjectionContext,
                        hasPresentedDownsell,
                        packageInterestAccepted,
                        tieredCatalog,
                        safeHistory
                );
        String stageBeforeReconcile = cleanStage;
        String priceObjectionUpdateReason = confirmedPriceObjectionThisTurn
                ? (isExplicitDownsell
                    ? "EXPLICIT_DOWNSELL_OR_LOW_BUDGET_IN_CURRENT_TURN"
                    : "CONFIRMED_PRICE_OBJECTION_IN_CURRENT_TURN")
                : "UNCHANGED_NO_CURRENT_TURN_OBJECTION";

        boolean isPriceContextHesitationTurn = "STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL".equals(cleanStage);
        boolean isPureDeferralOrBusyTurn = "STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE".equals(cleanStage);
        boolean holdSinglePackageFirstObjection = "STAGE_2_FIRST_PRICE_OBJECTION_HOLD_VALUE".equals(cleanStage);
        boolean isDownsellOrRepeatObjectionStage = "STAGE_3_DOWNSELL_OR_REPEAT_OBJECTION".equals(cleanStage);

        if (isPureDeferralOrBusyTurn || isPriceContextHesitationTurn || holdSinglePackageFirstObjection || isDownsellOrRepeatObjectionStage) {
            bookingState = ConversationState.StructuredBookingState.none();
            bookingFunnelStatus = bookingState.status().name();
        }

        List<PackageCatalogService.PackageSummary> scopeFilteredCatalog;
        List<PackageCatalogService.PackageSummary> excludedCatalog;
        if (isPureDeferralOrBusyTurn) {
            // Checkpoint C3-A.3: Ở STAGE_6 (khách xin tạm dừng/cân nhắc thêm), khóa chặt không cho nhắc bất kỳ sản phẩm bóng ma nào ngoài presentedPackagesInHistory
            scopeFilteredCatalog = new ArrayList<>(presentedPackagesInHistory);
            excludedCatalog = new ArrayList<>();
            for (PackageCatalogService.PackageSummary ap : allCatalog) {
                if (ap == null || ap.isAddon()) continue;
                boolean wasPresented = presentedPackagesInHistory.stream()
                        .anyMatch(pp -> pp != null && (pp.equals(ap) || pp.priceAmountVnd() == ap.priceAmountVnd()));
                if (!wasPresented) {
                    excludedCatalog.add(ap);
                }
            }
        } else if ((isOperationalQuestion || brainDecodedOperationalAck) && !hasPresentedDownsell) {
            // Khách hỏi giờ đóng cửa / chỗ đỗ xe sau khi chê đắt -> KHÔNG bị ép chèn gói tiết kiệm chưa từng giới thiệu
            scopeFilteredCatalog = new ArrayList<>(tieredCatalog.primaryRecommendations());
            excludedCatalog = new ArrayList<>(tieredCatalog.excludedCatalog());
            if (tieredCatalog.economicalAlternatives() != null) {
                excludedCatalog.addAll(tieredCatalog.economicalAlternatives());
            }
        } else {
            scopeFilteredCatalog = new ArrayList<>(
                    holdSinglePackageFirstObjection
                            ? tieredCatalog.primaryRecommendations()
                            : tieredCatalog.allAllowedPackages()
            );
            excludedCatalog = new ArrayList<>(tieredCatalog.excludedCatalog());
            if (holdSinglePackageFirstObjection && tieredCatalog.economicalAlternatives() != null) {
                excludedCatalog.addAll(tieredCatalog.economicalAlternatives());
            }
            if (isProductComparison && !presentedPackagesInHistory.isEmpty()) {
                for (PackageCatalogService.PackageSummary pp : deduplicateNonAddonProducts(presentedPackagesInHistory)) {
                    boolean alreadyAllowed = scopeFilteredCatalog.stream()
                            .anyMatch(a -> a != null && (a.equals(pp) || a.priceAmountVnd() == pp.priceAmountVnd()));
                    if (!alreadyAllowed) {
                        scopeFilteredCatalog.add(pp);
                    }
                    excludedCatalog.removeIf(ex -> ex != null && (ex.equals(pp) || ex.priceAmountVnd() == pp.priceAmountVnd()));
                }
            }
        }
        boolean enforceReplyScopeGuard = !isMetaOrChallenge
                && !isPoliteClosingAck
                && !isPostBookingClosingAck
                && !isPureDeferralOrBusyTurn;
        if (replyResolutionState == CleanAgentCatalogAndPromptBuilder.ReplyResolutionState.RESOLVED_QUOTED_PACKAGE
                && quotedReplyResolution.quotedPackage() != null
                && enforceReplyScopeGuard) {
            PackageCatalogService.PackageSummary qPkg = quotedReplyResolution.quotedPackage();
            Set<Long> customerMsgPrices = CleanAgentOutputRefereeAndSanitizer.extractPricesInVndFromText(mergedMessage);
            scopeFilteredCatalog = new ArrayList<>();
            scopeFilteredCatalog.add(qPkg);
            excludedCatalog = new ArrayList<>();
            for (PackageCatalogService.PackageSummary ap : allCatalog) {
                if (ap == null || ap.isAddon()) continue;
                if (ap.equals(qPkg) || ap.priceVnd() == qPkg.priceVnd() || customerMsgPrices.contains(ap.priceVnd())) {
                    if (!scopeFilteredCatalog.contains(ap)) {
                        scopeFilteredCatalog.add(ap);
                    }
                } else {
                    excludedCatalog.add(ap);
                }
            }
        } else if ((replyResolutionState == CleanAgentCatalogAndPromptBuilder.ReplyResolutionState.AMBIGUOUS_PACKAGE_REFERENCE
                || replyResolutionState == CleanAgentCatalogAndPromptBuilder.ReplyResolutionState.UNRESOLVED_QUOTED_PACKAGE)
                && enforceReplyScopeGuard) {
            List<PackageCatalogService.PackageSummary> candidates = quotedReplyResolution.candidatePackages();
            scopeFilteredCatalog = new ArrayList<>(candidates);
            excludedCatalog = new ArrayList<>();
            for (PackageCatalogService.PackageSummary ap : allCatalog) {
                if (ap == null || ap.isAddon()) continue;
                boolean inCandidates = candidates.stream()
                        .anyMatch(c -> c != null && (c.equals(ap) || c.priceVnd() == ap.priceVnd()));
                if (!inCandidates) {
                    excludedCatalog.add(ap);
                }
            }
        }

        String heldPackageDisplay = CleanAgentCatalogAndPromptBuilder.resolveHeldPackageDisplay(
                rawHeldPackage, rawHeldDuration, rawHeldPrice, allCatalog);
        String heldPriceDisplay = CleanAgentCatalogAndPromptBuilder.resolveHeldPriceDisplay(
                rawHeldPrice, rawHeldPackage, allCatalog);

        PackageCatalogService.PackageSummary selectedBookingPkg = (bookingState.selectedProductId() != null)
                ? findProductByIdInCatalog(bookingState.selectedProductId(), presentedPackagesInHistory, allCatalog)
                : null;

        boolean customerProactivelyPickedDownsell = packageInterestAccepted
                && bookingState.status() != ConversationState.BookingStatus.AWAITING_PRODUCT_SELECTION
                && tieredCatalog.economicalAlternatives() != null
                && !tieredCatalog.economicalAlternatives().isEmpty()
                && (CleanAgentCatalogAndPromptBuilder.customerExplicitlyAskedForEconomicalGroup(
                        mergedMessage, tieredCatalog.economicalAlternatives())
                    || hasPackagePriceMentionedInText(mergedMessage, tieredCatalog.economicalAlternatives())
                    || (selectedBookingPkg != null && tieredCatalog.economicalAlternatives().stream()
                        .anyMatch(e -> e != null && e.productId().equalsIgnoreCase(selectedBookingPkg.productId()))));
        if (selectedBookingPkg != null && !isMetaOrChallenge && !isPoliteClosingAck) {
            heldPackageDisplay = selectedBookingPkg.canonicalLabel();
            heldPriceDisplay = selectedBookingPkg.priceDisplay();
            if (replyResolutionState == CleanAgentCatalogAndPromptBuilder.ReplyResolutionState.NONE) {
                Set<Long> customerMsgPrices = CleanAgentOutputRefereeAndSanitizer.extractPricesInVndFromText(mergedMessage);
                scopeFilteredCatalog = new ArrayList<>();
                scopeFilteredCatalog.add(selectedBookingPkg);
                excludedCatalog = new ArrayList<>();
                for (PackageCatalogService.PackageSummary ap : allCatalog) {
                    if (ap == null || ap.isAddon()) continue;
                    if (ap.equals(selectedBookingPkg)
                            || ap.productId().equalsIgnoreCase(selectedBookingPkg.productId())
                            || ap.priceAmountVnd() == selectedBookingPkg.priceAmountVnd()
                            || customerMsgPrices.contains(ap.priceAmountVnd())) {
                        if (!scopeFilteredCatalog.contains(ap)) {
                            scopeFilteredCatalog.add(ap);
                        }
                    } else {
                        excludedCatalog.add(ap);
                    }
                }
            }
        } else if (bookingState.status() == ConversationState.BookingStatus.AWAITING_PRODUCT_SELECTION) {
            List<PackageCatalogService.PackageSummary> distinctPresented = deduplicateNonAddonProducts(presentedPackagesInHistory);
            if (!distinctPresented.isEmpty()) {
                scopeFilteredCatalog = new ArrayList<>(distinctPresented);
                excludedCatalog = new ArrayList<>();
                for (PackageCatalogService.PackageSummary ap : allCatalog) {
                    if (ap == null || ap.isAddon()) continue;
                    boolean inPresented = distinctPresented.stream()
                            .anyMatch(pp -> pp != null && (pp.equals(ap)
                                    || pp.productId().equalsIgnoreCase(ap.productId())
                                    || pp.priceAmountVnd() == ap.priceAmountVnd()));
                    if (!inPresented) {
                        excludedCatalog.add(ap);
                    }
                }
            }
        }
        Set<String> turnTargetScopeSet = new LinkedHashSet<>(
                ConversationState.extractTargetScopes(mergedMessage, List.of()));
        boolean postObjectionEconMatch = !holdSinglePackageFirstObjection
                && !isMetaOrChallenge
                && !isPoliteClosingAck
                && tieredCatalog.economicalAlternatives() != null
                && !tieredCatalog.economicalAlternatives().isEmpty()
                && (CleanAgentCatalogAndPromptBuilder.customerExplicitlyAskedForEconomicalGroup(
                        mergedMessage, tieredCatalog.economicalAlternatives())
                    || ((priceObjectionCount >= 1 || hasPresentedDownsell)
                        && !turnTargetScopeSet.isEmpty()
                        && tieredCatalog.economicalAlternatives().stream().anyMatch(e ->
                                CleanAgentCatalogAndPromptBuilder.computePackageScopeDirectHits(
                                        e, turnTargetScopeSet, safeConv.knownSymptoms()) > 0)));
        PackageCatalogService.PackageSummary preferredEconForTurn =
                (tieredCatalog.economicalAlternatives() != null && !tieredCatalog.economicalAlternatives().isEmpty())
                        ? CleanAgentCatalogAndPromptBuilder.selectPreferredEconomicalAlternative(
                                tieredCatalog.economicalAlternatives(),
                                request != null ? request.history() : List.of(),
                                heldPackageDisplay,
                                heldPriceDisplay,
                                mergedMessage,
                                turnTargetScopeSet,
                                safeConv.knownSymptoms()
                        )
                        : null;
        if (selectedBookingPkg == null
                && bookingState.status() != ConversationState.BookingStatus.AWAITING_PRODUCT_SELECTION
                && !isMetaOrChallenge
                && !isPoliteClosingAck
                && (customerProactivelyPickedDownsell || postObjectionEconMatch)
                && preferredEconForTurn != null) {
            heldPackageDisplay = preferredEconForTurn.canonicalLabel();
            heldPriceDisplay = preferredEconForTurn.priceDisplay();
        }
        boolean heldPkgIsNull = (rawHeldPackage == null || rawHeldPackage.isBlank())
                && ("null".equalsIgnoreCase(heldPackageDisplay) || heldPackageDisplay == null || heldPackageDisplay.isBlank());
        PackageCatalogService.PackageSummary explicitlyNamedPkgInTurn = null;
        if (replyResolutionState == CleanAgentCatalogAndPromptBuilder.ReplyResolutionState.NONE
                && isDetailOrUnitQuestion
                && mergedMessage != null
                && !mergedMessage.isBlank()) {
            CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution msgMatch =
                    CleanAgentCatalogAndPromptBuilder.resolveQuotedReplyContext("", mergedMessage, allCatalog);
            if (msgMatch.quotedPackage() != null) {
                explicitlyNamedPkgInTurn = msgMatch.quotedPackage();
                PackageCatalogService.PackageSummary named = explicitlyNamedPkgInTurn;
                if (!scopeFilteredCatalog.contains(named)) {
                    scopeFilteredCatalog.add(0, named);
                }
                excludedCatalog.removeIf(ex -> ex != null && (ex.equals(named) || ex.priceVnd() == named.priceVnd()));
            }
        }
        PackageCatalogService.PackageSummary activeTurnPkg = (bookingState.status() == ConversationState.BookingStatus.AWAITING_PRODUCT_SELECTION)
                ? null
                : (selectedBookingPkg != null
                    ? selectedBookingPkg
                    : ((quotedReplyResolution.quotedPackage() != null)
                        ? quotedReplyResolution.quotedPackage()
                        : ((quotedReplyResolution.unresolvedReplyToMid() || quotedReplyResolution.ambiguousMultiplePackages())
                            ? null
                            : (explicitlyNamedPkgInTurn != null
                                ? explicitlyNamedPkgInTurn
                                : ((!isMetaOrChallenge
                                    && !isPoliteClosingAck
                                    && (isDownsellOrRepeatObjectionStage || isExplicitDownsell || isPriceContextHesitationTurn || customerProactivelyPickedDownsell || postObjectionEconMatch)
                                    && preferredEconForTurn != null)
                                    ? preferredEconForTurn
                                    : CleanAgentCatalogAndPromptBuilder.findHeldOrTopPrimaryPackage(
                                            tieredCatalog, heldPackageDisplay, heldPriceDisplay))))));

        CustomerInsightDto enrichedTurnInsight = customerInsight;
        if (agentBrainService != null && (enrichedTurnInsight == null || enrichedTurnInsight.careNotes() == null)) {
            enrichedTurnInsight = agentBrainService.enrichCustomerInsight(pageId, request.senderId(), enrichedTurnInsight, null);
        }

        List<AgentSkillDto> alignedActiveSkills = alignCleanAgentActiveSkills(
                activeSkills,
                candidateSkills,
                cleanStage,
                bookingFunnelStatus,
                conditionClarified,
                mergedMessage,
                isHesitation || isPoliteClosingAck || isPriceContextHesitationTurn || isPureDeferralOrBusyTurn,
                isPostBookingClosingAck,
                isDetailOrUnitQuestion && !packageInterestAccepted
        );
        if (agentSkillService != null && alignedActiveSkills != null && !alignedActiveSkills.isEmpty()) {
            try {
                agentSkillService.recordSkillsApplied(alignedActiveSkills);
            } catch (Exception e) {
                log.warn("Non-fatal: failed to record clean-agent skill telemetry: {}", e.getMessage());
            }
        }

        Map<String, String> pendingBookingSlots = new java.util.LinkedHashMap<>();
        if (bookingState.bookingDate() != null) {
            pendingBookingSlots.put("booking_date", bookingState.bookingDate());
        }
        if (bookingState.bookingTime() != null) {
            pendingBookingSlots.put("booking_time", bookingState.bookingTime());
        }
        if (pendingBookingSlots.isEmpty() && safeConv.visitTime() != null && !safeConv.visitTime().isBlank()) {
            pendingBookingSlots.put("visit_time", safeConv.visitTime().strip());
        }

        String lastBotQuestion = extractLastBotQuestion(prevBotTurnText, safePrior.lastQuestion());
        String prompt = CleanAgentCatalogAndPromptBuilder.buildCleanAgentPrompt(
                pagePersona,
                safeHonorific,
                knownSymptoms,
                knownBodyAreas,
                statedContextOrTiming,
                conditionClarified,
                heldPackageDisplay,
                heldPriceDisplay,
                priceObjectionCount,
                packageInterestAccepted,
                bookingFunnelStatus,
                currentTurnSignals,
                lastBotQuestion,
                discoveryQuestionCount,
                tieredCatalog,
                fused != null ? fused : List.of(),
                safeHistory,
                mergedMessage,
                enrichedTurnInsight,
                alignedActiveSkills,
                pendingBookingSlots,
                quotedReplyResolution,
                pageId,
                appliedRules,
                bookingState
        );

        ThinkingMode selectedThinkingMode = resolveCleanAgentThinkingMode(
                mergedMessage,
                isChallengeAssumption,
                isStyleOrRepetitionComplaint,
                isMismatch,
                hasPriceObjectionKeyword || isPriceContextHesitationTurn,
                isHesitation,
                isOperationalQuestion,
                isBareAck,
                isPureDeferralOrBusyTurn || isPostBookingClosingAck,
                conditionClarified,
                packageInterestAccepted,
                isDetailOrUnitQuestion,
                knownSymptoms
        );
        this.lastSelectedThinkingMode = selectedThinkingMode;
        ReasoningBudget cleanBudget = ReasoningBudget.forThinkingMode(selectedThinkingMode);
        String rawReply = null;
        int llmRegenerationCount = 0;
        long llmStartNs = System.nanoTime();
        try {
            if (llmClient != null) {
                FanpageBotService.markCurrentTurnLlmStarted();
                rawReply = llmClient.complete(prompt, cleanBudget);
                if (rawReply == null || rawReply.isBlank()) {
                    rawReply = llmClient.complete(prompt);
                }
            }
        } catch (Exception e) {
            log.warn("[CleanAgentMode] Exception calling llmClient {}: {}", llmClient != null ? llmClient.name() : "null", e.getMessage());
        }

        if (!SenderEpochRegistry.checkThreadEpochStillCurrent(
                request != null ? request.pageId() : null,
                request != null ? request.senderId() : null,
                SenderEpochRegistry.Checkpoint.AFTER_LLM_BEFORE_REFEREE)) {
            return buildSupersededAbortedResponse(extractedLead, customerInsight);
        }

        String sanitizedReply = PromptSecurityGuard.sanitizeModelOutput(rawReply);
        CleanAgentOutputRefereeAndSanitizer.StructuredLlmOutput structured =
                CleanAgentOutputRefereeAndSanitizer.parseStructuredLlmJsonOutput(sanitizedReply, minDelayMs);
        if ((structured == null || !structured.parsedFromJson() || structured.bubbles() == null || structured.bubbles().isEmpty())
                && llmRegenerationCount < 1) {
            try {
                if (llmClient != null) {
                    llmRegenerationCount++;
                    String retryRaw = llmClient.complete(prompt, ReasoningBudget.forThinkingMode(ThinkingMode.FAST_NO_THINK));
                    if (retryRaw != null && !retryRaw.isBlank()) {
                        rawReply = retryRaw;
                        sanitizedReply = PromptSecurityGuard.sanitizeModelOutput(rawReply);
                        structured = CleanAgentOutputRefereeAndSanitizer.parseStructuredLlmJsonOutput(sanitizedReply, minDelayMs);
                    }
                }
            } catch (Exception e) {
                log.warn("[CleanAgentMode] JSON retry exception: {}", e.getMessage());
            }
            if (!SenderEpochRegistry.checkThreadEpochStillCurrent(
                    request != null ? request.pageId() : null,
                    request != null ? request.senderId() : null,
                    SenderEpochRegistry.Checkpoint.AFTER_LLM_BEFORE_REFEREE)) {
                return buildSupersededAbortedResponse(extractedLead, customerInsight);
            }
        }
        long llmElapsedMs = Math.max(0L, (System.nanoTime() - llmStartNs) / 1_000_000L);

        List<FanpageBotResponse.ReplyBubble> candidateBubbles =
                CleanAgentOutputRefereeAndSanitizer.extractCleanBubblesFromOutput(structured, sanitizedReply, minDelayMs);
        com.example.docmind.dto.PagePersonaConfig turnPersonaConfig =
                (pagePersona != null && pagePersona.personaConfig() != null)
                        ? pagePersona.personaConfig()
                        : com.example.docmind.dto.PagePersonaConfig.empty();
        boolean usedStructuredFallbackOnLlmFailure = false;
        if (candidateBubbles.isEmpty()) {
            log.warn("[CleanAgentMode] LLM returned empty/unreachable output after retry (userMsg='{}', replyToMid='{}') -> activating Safe Multi-Industry Structural Fallback",
                    mergedMessage, request != null ? request.replyToMid() : null);
            candidateBubbles = CleanAgentOutputRefereeAndSanitizer.buildSafeMultiIndustryTurnFallbackBubbles(
                    quotedReplyResolution,
                    bookingState,
                    cleanStage,
                    activeTurnPkg,
                    scopeFilteredCatalog,
                    allCatalog,
                    turnPersonaConfig,
                    mergedMessage,
                    selfPronoun,
                    safeHonorific
            );
            usedStructuredFallbackOnLlmFailure = true;
        }

        CleanAgentOutputRefereeAndSanitizer.BrainReflection initialReflection =
                structured != null && structured.brainReflection() != null
                        ? structured.brainReflection()
                        : CleanAgentOutputRefereeAndSanitizer.BrainReflection.empty();
        if (!confirmedPriceObjectionThisTurn
                && shouldReconcilePostLlmPriceObjection(
                        initialReflection.detectedCustomerIntent(),
                        mergedMessage,
                        hadPriorPackageInConversation || safeConv.priceDiscussed() || !heldPkgIsNull,
                        isMetaOrChallenge,
                        isHesitation,
                        isBareAck,
                        hasProactiveBookingInfo,
                        packageInterestAccepted
                )) {
            confirmedPriceObjectionThisTurn = true;
            hasPriceObjectionKeyword = true;
            priceObjectionCount = priorPriceObjectionCount + 1;
            priceObjectionUpdateReason = "POST_LLM_RECONCILED_PRICE_OBJECTION";
            inPriceObjectionContext = true;
            cleanStage = priceObjectionCount == 1
                    ? "STAGE_2_FIRST_PRICE_OBJECTION_HOLD_VALUE"
                    : "STAGE_3_DOWNSELL_OR_REPEAT_OBJECTION";
            holdSinglePackageFirstObjection = "STAGE_2_FIRST_PRICE_OBJECTION_HOLD_VALUE".equals(cleanStage);
            isDownsellOrRepeatObjectionStage = "STAGE_3_DOWNSELL_OR_REPEAT_OBJECTION".equals(cleanStage);
            if (replyResolutionState == CleanAgentCatalogAndPromptBuilder.ReplyResolutionState.NONE || !enforceReplyScopeGuard) {
                scopeFilteredCatalog = new ArrayList<>(
                        holdSinglePackageFirstObjection
                                ? tieredCatalog.primaryRecommendations()
                                : tieredCatalog.allAllowedPackages()
                );
                excludedCatalog = new ArrayList<>(tieredCatalog.excludedCatalog());
                if (holdSinglePackageFirstObjection && tieredCatalog.economicalAlternatives() != null) {
                    excludedCatalog.addAll(tieredCatalog.economicalAlternatives());
                }
            }
            alignedActiveSkills = alignCleanAgentActiveSkills(
                    alignedActiveSkills,
                    candidateSkills,
                    cleanStage,
                    false,
                    isPostBookingClosingAck,
                    false
            );
            log.info("[CleanAgentMode] Post-LLM Brain-to-State Reconciliation confirmed PRICE_OBJECTION: msg='{}' -> priceObjectionCount={} stage={}",
                    mergedMessage, priceObjectionCount, cleanStage);
        }

        boolean hasHigherPriorityNonObjectionIntent = isOperationalQuestion
                || (isDetailOrUnitQuestion && !confirmedPriceObjectionThisTurn)
                || isMetaOrChallenge
                || isPoliteClosingAck
                || isPostBookingClosingAck;

        if (!usedStructuredFallbackOnLlmFailure) {
            // Mốc C3-D (Nhóm 3): Sửa nhẹ đại từ sai vai / mở câu không chủ ngữ tại chỗ 0ms thay vì gọi lại LLM lần 2
            candidateBubbles = CleanAgentOutputRefereeAndSanitizer.repairWrongPronounOpenersInPlace(
                    candidateBubbles, selfPronoun, safeHonorific);
        }
        candidateBubbles = alignBubblesWithBookingLifecycle(
                candidateBubbles,
                bookingLifecycleEval,
                priorBookingState,
                mergedMessage,
                allCatalog,
                presentedPackagesInHistory,
                selfPronoun,
                safeHonorific
        );

        List<String> allViolations = new ArrayList<>(
                CleanAgentOutputRefereeAndSanitizer.checkCleanCatalogGrounding(
                        candidateBubbles,
                        allCatalog,
                        scopeFilteredCatalog,
                        excludedCatalog,
                        priceObjectionCount,
                        isExplicitDownsell || isPriceContextHesitationTurn || !holdSinglePackageFirstObjection,
                        activeTurnPkg,
                        heldPkgIsNull && !hasReplyToContext,
                        bookingFunnelStatus,
                        mergedMessage,
                        confirmedPriceObjectionThisTurn,
                        hasPresentedDownsell,
                        hasHigherPriorityNonObjectionIntent,
                        cleanStage,
                        presentedPackagesInHistory,
                        bookingState,
                        turnPersonaConfig
                )
        );
        for (String replyGuardViolation : CleanAgentOutputRefereeAndSanitizer.checkReplyResolutionGuard(
                candidateBubbles,
                quotedReplyResolution,
                allCatalog,
                mergedMessage,
                turnPersonaConfig
        )) {
            if (!allViolations.contains(replyGuardViolation)) {
                allViolations.add(replyGuardViolation);
            }
        }
        if (CleanAgentOutputRefereeAndSanitizer.detectCleanCrossTurnRepetition(
                candidateBubbles,
                prevBotTurnText,
                safeHistory,
                isDetailOrUnitQuestion || hasReplyToContext)) {
            allViolations.add("CROSS_TURN_REPETITION");
        }

        boolean repairApplied = usedStructuredFallbackOnLlmFailure;
        boolean usedSafeHandoffFallback = false;
        List<String> telemetryErrors = new ArrayList<>();
        if (!allViolations.isEmpty() && !usedStructuredFallbackOnLlmFailure) {
            log.warn("[CleanAgentMode] Holistic Referee detected violations: {} (userMsg='{}') -> regenerating full turn (llmRegenerationCount={})",
                    allViolations, mergedMessage, llmRegenerationCount);
            if (llmClient != null && llmRegenerationCount < 1) {
                String repairHintPrompt = CleanAgentOutputRefereeAndSanitizer.buildCleanRepairPrompt(
                        prompt, allViolations, selfPronoun, safeHonorific, activeTurnPkg);
                try {
                    llmRegenerationCount++;
                    String repairedRaw = llmClient.complete(repairHintPrompt, ReasoningBudget.forThinkingMode(ThinkingMode.FAST_NO_THINK));
                    if (repairedRaw != null && !repairedRaw.isBlank()) {
                        String repairedSanitized = PromptSecurityGuard.sanitizeModelOutput(repairedRaw);
                        CleanAgentOutputRefereeAndSanitizer.StructuredLlmOutput repairedStructured =
                                CleanAgentOutputRefereeAndSanitizer.parseStructuredLlmJsonOutput(repairedSanitized, minDelayMs);
                        List<FanpageBotResponse.ReplyBubble> repairedBubbles =
                                CleanAgentOutputRefereeAndSanitizer.extractCleanBubblesFromOutput(
                                        repairedStructured, repairedSanitized, minDelayMs);
                        if (!repairedBubbles.isEmpty()) {
                            candidateBubbles = CleanAgentOutputRefereeAndSanitizer.repairWrongPronounOpenersInPlace(
                                    repairedBubbles, selfPronoun, safeHonorific);
                            structured = repairedStructured;
                            rawReply = repairedRaw;
                            repairApplied = true;
                        }
                    }
                } catch (Exception e) {
                    log.warn("[CleanAgentMode] Holistic Referee regeneration exception: {}", e.getMessage());
                }
                if (!SenderEpochRegistry.checkThreadEpochStillCurrent(
                        request != null ? request.pageId() : null,
                        request != null ? request.senderId() : null,
                        SenderEpochRegistry.Checkpoint.AFTER_LLM_BEFORE_REFEREE)) {
                    return buildSupersededAbortedResponse(extractedLead, customerInsight);
                }
            }
            List<String> postRepairViolations = new ArrayList<>(
                    CleanAgentOutputRefereeAndSanitizer.checkCleanCatalogGrounding(
                            candidateBubbles,
                            allCatalog,
                            scopeFilteredCatalog,
                            excludedCatalog,
                            priceObjectionCount,
                            isExplicitDownsell || isPriceContextHesitationTurn || !holdSinglePackageFirstObjection,
                            activeTurnPkg,
                            heldPkgIsNull && !hasReplyToContext,
                            bookingFunnelStatus,
                            mergedMessage,
                            confirmedPriceObjectionThisTurn,
                            hasPresentedDownsell,
                            hasHigherPriorityNonObjectionIntent,
                            cleanStage,
                            presentedPackagesInHistory,
                            bookingState,
                            turnPersonaConfig
                    )
            );
            for (String replyGuardViolation : CleanAgentOutputRefereeAndSanitizer.checkReplyResolutionGuard(
                    candidateBubbles,
                    quotedReplyResolution,
                    allCatalog,
                    mergedMessage,
                    turnPersonaConfig
            )) {
                if (!postRepairViolations.contains(replyGuardViolation)) {
                    postRepairViolations.add(replyGuardViolation);
                }
            }

            boolean needsClarificationGuardRebuild = enforceReplyScopeGuard
                    && (replyResolutionState == CleanAgentCatalogAndPromptBuilder.ReplyResolutionState.UNRESOLVED_QUOTED_PACKAGE
                        || replyResolutionState == CleanAgentCatalogAndPromptBuilder.ReplyResolutionState.AMBIGUOUS_PACKAGE_REFERENCE)
                    && postRepairViolations.stream().anyMatch(v -> v != null
                        && (v.startsWith("UNRESOLVED_OR_AMBIGUOUS_REPLY_GUARD_VIOLATION:")
                            || v.startsWith("UNGROUNDED_PRICE_WHEN_CATALOG_EMPTY:")
                            || v.startsWith("UNGROUNDED_OR_EXCLUDED_PRICE:")
                            || v.startsWith("EXCLUDED_PACKAGE_MENTIONED:")
                            || v.startsWith("PRICE_ENTITY_MISMATCH:")
                            || v.startsWith("UNGROUNDED_PROMO_POLICY_FABRICATION")));
            boolean needsQuotedPackageGuardRebuild = enforceReplyScopeGuard
                    && replyResolutionState == CleanAgentCatalogAndPromptBuilder.ReplyResolutionState.RESOLVED_QUOTED_PACKAGE
                    && quotedReplyResolution.quotedPackage() != null
                    && postRepairViolations.stream().anyMatch(v -> v != null
                        && (v.startsWith("QUOTED_PACKAGE_FOCUS_MISMATCH:")
                            || v.startsWith("UNGROUNDED_OR_EXCLUDED_PRICE:")
                            || v.startsWith("EXCLUDED_PACKAGE_MENTIONED:")
                            || v.startsWith("PRICE_ENTITY_MISMATCH:")
                            || v.startsWith("UNGROUNDED_PROMO_POLICY_FABRICATION")));
            boolean needsPromoFabricationGuardRebuild = !needsClarificationGuardRebuild
                    && !needsQuotedPackageGuardRebuild
                    && activeTurnPkg != null
                    && postRepairViolations.stream().anyMatch(v -> v != null && v.startsWith("UNGROUNDED_PROMO_POLICY_FABRICATION"));

            if (needsClarificationGuardRebuild) {
                log.warn("[CleanAgentMode] Post-repair still violates Unresolved/Ambiguous Reply Guard ({}) -> applying Deterministic Clarification Guard without 3rd LLM call",
                        postRepairViolations);
                candidateBubbles = CleanAgentOutputRefereeAndSanitizer.buildDeterministicReplyClarificationBubbles(
                        quotedReplyResolution,
                        mergedMessage,
                        selfPronoun,
                        safeHonorific,
                        turnPersonaConfig,
                        allCatalog
                );
                repairApplied = true;
            } else if (needsQuotedPackageGuardRebuild) {
                log.warn("[CleanAgentMode] Post-repair still violates Quoted Package Focus Guard ({}) -> rebuilding deterministically from quoted PackageSummary without 3rd LLM call",
                        postRepairViolations);
                candidateBubbles = CleanAgentOutputRefereeAndSanitizer.buildDeterministicQuotedPackageReplyBubbles(
                        quotedReplyResolution.quotedPackage(),
                        mergedMessage,
                        selfPronoun,
                        safeHonorific,
                        turnPersonaConfig
                );
                repairApplied = true;
            } else if (needsPromoFabricationGuardRebuild) {
                log.warn("[CleanAgentMode] Post-repair still has UNGROUNDED_PROMO_POLICY_FABRICATION ({}) -> rebuilding deterministically from active PackageSummary without 3rd LLM call",
                        postRepairViolations);
                candidateBubbles = CleanAgentOutputRefereeAndSanitizer.buildDeterministicQuotedPackageReplyBubbles(
                        activeTurnPkg,
                        mergedMessage,
                        selfPronoun,
                        safeHonorific,
                        turnPersonaConfig
                );
                repairApplied = true;
            }

            if (needsClarificationGuardRebuild || needsQuotedPackageGuardRebuild || needsPromoFabricationGuardRebuild) {
                postRepairViolations = new ArrayList<>(
                        CleanAgentOutputRefereeAndSanitizer.checkCleanCatalogGrounding(
                                candidateBubbles,
                                allCatalog,
                                scopeFilteredCatalog,
                                excludedCatalog,
                                priceObjectionCount,
                                isExplicitDownsell || isPriceContextHesitationTurn || !holdSinglePackageFirstObjection,
                                activeTurnPkg,
                                heldPkgIsNull && !hasReplyToContext,
                                bookingFunnelStatus,
                                mergedMessage,
                                confirmedPriceObjectionThisTurn,
                                hasPresentedDownsell,
                                hasHigherPriorityNonObjectionIntent,
                                cleanStage,
                                presentedPackagesInHistory,
                                bookingState,
                                turnPersonaConfig
                        )
                );
                for (String replyGuardViolation : CleanAgentOutputRefereeAndSanitizer.checkReplyResolutionGuard(
                        candidateBubbles,
                        quotedReplyResolution,
                        allCatalog,
                        mergedMessage,
                        turnPersonaConfig
                )) {
                    if (!postRepairViolations.contains(replyGuardViolation)) {
                        postRepairViolations.add(replyGuardViolation);
                    }
                }
            }

            boolean stillHasPriceEntityMismatch = postRepairViolations.stream()
                    .anyMatch(v -> v != null && v.startsWith("PRICE_ENTITY_MISMATCH:"));
            if (stillHasPriceEntityMismatch) {
                log.warn("[CleanAgentMode] Post-repair still has PRICE_ENTITY_MISMATCH: violations={} -> rebuilding from structured Catalog without 3rd LLM call",
                        postRepairViolations);
                candidateBubbles = CleanAgentOutputRefereeAndSanitizer.rebuildBubblesFromStructuredCatalog(
                        candidateBubbles,
                        activeTurnPkg,
                        allCatalog,
                        selfPronoun,
                        safeHonorific,
                        turnPersonaConfig
                );
                repairApplied = true;
                postRepairViolations = new ArrayList<>(
                        CleanAgentOutputRefereeAndSanitizer.checkCleanCatalogGrounding(
                                candidateBubbles,
                                allCatalog,
                                scopeFilteredCatalog,
                                excludedCatalog,
                                priceObjectionCount,
                                isExplicitDownsell || isPriceContextHesitationTurn || !holdSinglePackageFirstObjection,
                                activeTurnPkg,
                                heldPkgIsNull && !hasReplyToContext,
                                bookingFunnelStatus,
                                mergedMessage,
                                confirmedPriceObjectionThisTurn,
                                hasPresentedDownsell,
                                hasHigherPriorityNonObjectionIntent,
                                cleanStage,
                                presentedPackagesInHistory,
                                bookingState,
                                turnPersonaConfig
                        )
                );
            }

            boolean stillHasGhostProductOnClosing = postRepairViolations.stream()
                    .anyMatch(v -> v != null && v.startsWith("UNPRESENTED_GHOST_PRODUCT_ON_CLOSING:"));
            boolean stillHasUngroundedPriceBeforeStructuralFallback = postRepairViolations.stream()
                    .anyMatch(v -> v != null
                            && (v.startsWith("UNGROUNDED_OR_EXCLUDED_PRICE:")
                                || v.startsWith("UNGROUNDED_PRICE_WHEN_CATALOG_EMPTY:")));
            boolean stillHasCommercialOrStateTruthViolation = postRepairViolations.stream()
                    .anyMatch(v -> v != null
                            && (v.startsWith("FALSE_PRODUCT_SELECTION_CLAIM")
                                || v.startsWith("UNVERIFIED_BOOKING_CONFIRMATION_CLAIM")
                                || v.startsWith("CROSS_TIER_EXCLUSIVE_ATTRIBUTE_MISMATCH:")
                                || v.startsWith("UNGROUNDED_PRODUCT_SPECIFICATION:")));
            if (stillHasGhostProductOnClosing
                    || (!stillHasUngroundedPriceBeforeStructuralFallback && stillHasCommercialOrStateTruthViolation)) {
                log.warn("[CleanAgentMode] Post-repair still violates C3-D Commercial/Booking/Closing Truth Guard ({}) -> applying Safe Multi-Industry Structural Fallback without 3rd LLM call",
                        postRepairViolations);
                candidateBubbles = CleanAgentOutputRefereeAndSanitizer.buildSafeMultiIndustryTurnFallbackBubbles(
                        quotedReplyResolution,
                        bookingState,
                        cleanStage,
                        activeTurnPkg,
                        scopeFilteredCatalog,
                        allCatalog,
                        turnPersonaConfig,
                        mergedMessage,
                        selfPronoun,
                        safeHonorific
                );
                repairApplied = true;
                postRepairViolations = new ArrayList<>(
                        CleanAgentOutputRefereeAndSanitizer.checkCleanCatalogGrounding(
                                candidateBubbles,
                                allCatalog,
                                scopeFilteredCatalog,
                                excludedCatalog,
                                priceObjectionCount,
                                isExplicitDownsell || isPriceContextHesitationTurn || !holdSinglePackageFirstObjection,
                                activeTurnPkg,
                                heldPkgIsNull && !hasReplyToContext,
                                bookingFunnelStatus,
                                mergedMessage,
                                confirmedPriceObjectionThisTurn,
                                hasPresentedDownsell,
                                hasHigherPriorityNonObjectionIntent,
                                cleanStage,
                                presentedPackagesInHistory,
                                bookingState,
                                turnPersonaConfig
                        )
                );
            }

            boolean stillHasUngroundedPrice = postRepairViolations.stream()
                    .anyMatch(v -> v != null
                            && (v.startsWith("UNGROUNDED_OR_EXCLUDED_PRICE:")
                                || v.startsWith("UNGROUNDED_PRICE_WHEN_CATALOG_EMPTY:")));
            if (stillHasUngroundedPrice) {
                log.warn("[CleanAgentMode] Post-repair still has UNGROUNDED_PRICE: violations={} userMsg='{}' -> triggering SAFE_HANDOFF",
                        postRepairViolations, mergedMessage);
                candidateBubbles = List.of(new FanpageBotResponse.ReplyBubble(FanpageBotService.SAFE_HANDOFF_BUBBLE_TEXT, 0));
                usedSafeHandoffFallback = true;
                telemetryErrors.add("UNGROUNDED_CATALOG_PRICE");
            }
        }

        List<FanpageBotResponse.ReplyBubble> splitReadyBubbles = usedSafeHandoffFallback
                ? candidateBubbles
                : CleanAgentOutputRefereeAndSanitizer.splitClosingWishBubbleIfApplicable(
                        CleanAgentOutputRefereeAndSanitizer.splitNaturalTrialInviteBubbleIfApplicable(
                                candidateBubbles,
                                cleanStage,
                                minDelayMs
                        ),
                        cleanStage,
                        minDelayMs
                );

        List<FanpageBotResponse.ReplyBubble> finalBubbles = new ArrayList<>(splitReadyBubbles.size());
        for (int i = 0; i < splitReadyBubbles.size(); i++) {
            FanpageBotResponse.ReplyBubble b = splitReadyBubbles.get(i);
            if (b == null || b.text() == null || b.text().isBlank()) {
                continue;
            }
            String normText = usedSafeHandoffFallback
                    ? b.text().strip()
                    : CleanAgentOutputRefereeAndSanitizer.sanitizeMinorCleanAgentWordingInPlace(
                            b.text().strip(),
                            selfPronoun,
                            safeHonorific,
                            activeTurnPkg,
                            heldPkgIsNull,
                            bookingFunnelStatus
                    );
            if (!usedSafeHandoffFallback) {
                // SBR-01 FIX-3: không tự đổi dấu '?'/'.' của LLM; chỉ viết hoa chữ cái đầu.
                normText = BubbleTextSanitizer.capitalizeFirstLetter(normText);
            }
            int delay = (i == 0)
                    ? 0
                    : (!finalBubbles.isEmpty() && CleanAgentOutputRefereeAndSanitizer.hasQuotedPriceInText(finalBubbles.get(0).text()) ? minDelayMs : 2000);
            finalBubbles.add(new FanpageBotResponse.ReplyBubble(normText, delay));
        }

        if (finalBubbles.isEmpty() && sanitizedReply != null && !sanitizedReply.isBlank()) {
            String fallbackText = CleanAgentOutputRefereeAndSanitizer.sanitizeMinorCleanAgentWordingInPlace(
                    sanitizedReply.strip(),
                    selfPronoun,
                    safeHonorific,
                    activeTurnPkg,
                    heldPkgIsNull,
                    bookingFunnelStatus
            );
            fallbackText = BubbleTextSanitizer.capitalizeFirstLetter(fallbackText);
            finalBubbles.add(new FanpageBotResponse.ReplyBubble(fallbackText, 0));
        }
        if (!usedSafeHandoffFallback) {
            finalBubbles = new ArrayList<>(alignBubblesWithBookingLifecycle(
                    finalBubbles,
                    bookingLifecycleEval,
                    priorBookingState,
                    mergedMessage,
                    allCatalog,
                    presentedPackagesInHistory,
                    selfPronoun,
                    safeHonorific
            ));
        }

        boolean shouldHandoff = usedSafeHandoffFallback || (structured != null && Boolean.TRUE.equals(structured.shouldHandoff()));
        String combinedReplyText = String.join("\n\n", finalBubbles.stream().map(FanpageBotResponse.ReplyBubble::text).toList());
        boolean replyHasPrice = CleanAgentOutputRefereeAndSanitizer.hasQuotedPriceInText(combinedReplyText);

        boolean presentedDownsellThisTurn = hasEconomicalAltInCatalog
                && CleanAgentCatalogAndPromptBuilder.isAnyPackagePresentedInBotText(
                        combinedReplyText, tieredCatalog.economicalAlternatives());
        boolean finalHasPresentedDownsell = hasPresentedDownsell || presentedDownsellThisTurn;
        boolean finalInPriceObjectionContext = ("STAGE_4_POSITIVE_TRIAL_OR_BOOKING".equals(cleanStage)
                || "STAGE_4B_POST_BOOKING_CONFIRMED_CLOSING".equals(cleanStage)
                || isMismatch)
                ? false
                : (inPriceObjectionContext || confirmedPriceObjectionThisTurn);

        PrimaryActionRouter.RoutingDecision stateSyncDecision = buildCleanStateSyncDecision(
                isMismatch,
                isChallengeAssumption || isStyleOrRepetitionComplaint,
                isDetailOrUnitQuestion,
                hasPriceObjectionKeyword,
                priceObjectionCount,
                isAckOrAcceptNow || packageInterestAccepted,
                isHesitation || isPoliteClosingAck || isPureDeferralOrBusyTurn,
                isPriceContextHesitationTurn,
                conditionClarified,
                replyHasPrice,
                shouldHandoff
        );
        String ledgerKey = SentBubbleLedger.key(request.pageId(), request.senderId(), request.conversationId());
        String ledgerUserTurnId = SentBubbleLedger.stableUserTurnId(ledgerKey, mergedMessage);
        SentBubbleLedger.recordCustomerTurn(ledgerKey, ledgerUserTurnId, mergedMessage);
        if (!finalBubbles.isEmpty()) {
            SentBubbleLedger.recordAssistantTurn(
                    ledgerKey,
                    ledgerUserTurnId + ":a",
                    finalBubbles.stream().map(FanpageBotResponse.ReplyBubble::text).toList(),
                    SentBubbleLedger.DeliveryStatus.PENDING,
                    List.of()
            );
        }
        if (!SenderEpochRegistry.checkThreadEpochStillCurrent(
                request != null ? request.pageId() : null,
                request != null ? request.senderId() : null,
                SenderEpochRegistry.Checkpoint.BEFORE_STATE_AND_LEDGER_COMMIT)) {
            SentBubbleLedger.markAssistantBubblesSuppressedByFenceFrom(
                    ledgerKey, ledgerUserTurnId + ":a", 0, "SUPERSEDED_BY_NEWER_EPOCH");
            return buildSupersededAbortedResponse(extractedLead, customerInsight);
        }
        if (!isOutboundStillPermittedLayer2(pageId, request)) {
            SentBubbleLedger.markAssistantBubblesSuppressedByFenceFrom(
                    ledgerKey, ledgerUserTurnId + ":a", 0, "SUPPRESSED_STALE_TURN_AFTER_STATE_LOCK");
            log.warn("[CLEAN-V1-LAYER2-FENCE] Stale turn suppressed before ledger commit (page_id={} sender_id={})",
                    pageId, request != null ? request.senderId() : null);
            FanpageBotService.markProvenanceV38Logged();
            return FanpageBotService.buildSilentSuppressedResponse("SUPPRESSED_STALE_TURN_AFTER_STATE_LOCK", null);
        }
        if (!finalBubbles.isEmpty()) {
            ConversationState.updateAfterSend(
                    pageId,
                    request.senderId(),
                    request.conversationId(),
                    safeConv,
                    mergedMessage,
                    stateSyncDecision,
                    finalBubbles,
                    shouldHandoff,
                    incomingHistorySize
            );
            ConversationState.updateStructuredBookingStateInSession(
                    pageId,
                    request.senderId(),
                    request.conversationId(),
                    bookingState
            );
        }
        this.lastTurnBookingState = bookingState;
        this.lastTurnBookingExecutionResult = bookingLifecycleEval.executionResult();

        String traceCorrelationId = org.slf4j.MDC.get("correlation_id");
        if (traceCorrelationId == null || traceCorrelationId.isBlank()) {
            traceCorrelationId = UUID.randomUUID().toString();
        }
        String traceMessageId = org.slf4j.MDC.get("fb_message_id");
        if (traceMessageId == null || traceMessageId.isBlank()) {
            traceMessageId = ledgerUserTurnId;
        }
        String traceQuotedPkgId = quotedReplyResolution.quotedPackage() != null
                ? (quotedReplyResolution.quotedPackage().code() + ":" + quotedReplyResolution.quotedPackage().priceDisplay())
                : "null";
        String traceActivePkgId = activeTurnPkg != null
                ? (activeTurnPkg.code() + ":" + activeTurnPkg.priceDisplay())
                : "null";
        List<String> tracePresentedHistoryIds = presentedPackagesInHistory.stream()
                .map(p -> (p.code() != null ? p.code() : p.displayName()) + ":" + p.priceDisplay())
                .toList();
        List<String> traceFinalBubbleTexts = finalBubbles.stream()
                .map(FanpageBotResponse.ReplyBubble::text)
                .toList();
        log.info("[CleanAgentTrace] correlation_id={} pageId={} senderPsid={} messageId={} replyToMid={} replyResolutionState={} "
                + "quotedMessageTextFound={} quotedPackageId={} heldPackageId={} activePackageId={} selectedProductId={} "
                + "bookingDate={} bookingTime={} bookingStatus={} presentedPackagesInHistory={} "
                + "intentSignals={} priceObjectionCount={} priceObjectionUpdateReason={} stageBeforeReconcile={} stageAfterReconcile={} "
                + "refereeViolations={} regenerationCount={} finalBubbles={}",
                traceCorrelationId,
                request.pageId(),
                request.senderId(),
                traceMessageId,
                replyToMid.isEmpty() ? "none" : replyToMid,
                replyResolutionState.name(),
                !quotedMessageText.isEmpty(),
                traceQuotedPkgId,
                !heldPkgIsNull ? heldPackageDisplay : "null",
                traceActivePkgId,
                bookingState.selectedProductId() != null ? bookingState.selectedProductId() : "null",
                bookingState.bookingDate() != null ? bookingState.bookingDate() : "null",
                bookingState.bookingTime() != null ? bookingState.bookingTime() : "null",
                bookingState.status().name(),
                tracePresentedHistoryIds,
                currentTurnSignals,
                priceObjectionCount,
                priceObjectionUpdateReason,
                stageBeforeReconcile,
                cleanStage,
                allViolations,
                llmRegenerationCount,
                traceFinalBubbleTexts
        );

        CleanAgentOutputRefereeAndSanitizer.BrainReflection rawReflection =
                structured != null && structured.brainReflection() != null
                        ? structured.brainReflection()
                        : CleanAgentOutputRefereeAndSanitizer.BrainReflection.empty();
        String validatedObservedFact = CleanAgentOutputRefereeAndSanitizer.validateCleanCareNoteDelta(
                rawReflection.observedFact(), excludedCatalog, activeTurnPkg, mergedMessage, combinedReplyText);
        String validatedCareNoteDelta = CleanAgentOutputRefereeAndSanitizer.validateCleanCareNoteDelta(
                rawReflection.careNoteDelta(), excludedCatalog, activeTurnPkg, mergedMessage, combinedReplyText);
        String validatedWorkingHypothesis = CleanAgentOutputRefereeAndSanitizer.validateCleanCareNoteDelta(
                rawReflection.workingHypothesis(), excludedCatalog, null, mergedMessage, combinedReplyText);
        String actualSentSpeechAct = CleanAgentOutputRefereeAndSanitizer.resolveActualSentSpeechAct(
                cleanStage,
                finalBubbles,
                bookingFunnelStatus,
                rawReflection.intendedSpeechAct(),
                isOperationalQuestion
        );

        if (agentBrainService != null) {
            try {
                agentBrainService.recordBookingState(pageId, request.senderId(), bookingState);
                agentBrainService.recordBookingExecutionMetadata(
                        pageId, request.senderId(), bookingState, bookingLifecycleEval.executionResult());
                agentBrainService.recordCommercialFunnelState(
                        pageId,
                        request.senderId(),
                        priceObjectionCount,
                        finalInPriceObjectionContext,
                        finalHasPresentedDownsell
                );
                if (!finalBubbles.isEmpty()) {
                    agentBrainService.retainTurnWithReflection(
                            pageId,
                            request.senderId(),
                            request.customerName(),
                            mergedMessage,
                            combinedReplyText,
                            List.of(),
                            validatedObservedFact,
                            validatedCareNoteDelta,
                            validatedWorkingHypothesis,
                            actualSentSpeechAct,
                            knownBodyAreas,
                            CleanAgentCatalogAndPromptBuilder.resolveCareActor(pagePersona)
                    );
                }
                enrichedTurnInsight = agentBrainService.enrichCustomerInsight(pageId, request.senderId(), enrichedTurnInsight, null);
            } catch (Exception e) {
                log.debug("[CleanAgentMode] Non-fatal retainTurnWithReflection exception: {}", e.getMessage());
            }
        }
        if (enrichedTurnInsight != null) {
            enrichedTurnInsight = new CustomerInsightDto(
                    enrichedTurnInsight.buyerArchetype(),
                    enrichedTurnInsight.archetypeLabel(),
                    enrichedTurnInsight.buyingTemperature(),
                    enrichedTurnInsight.previousTemperature(),
                    enrichedTurnInsight.temperatureDelta(),
                    enrichedTurnInsight.momentum(),
                    enrichedTurnInsight.temperatureLabel(),
                    enrichedTurnInsight.decayReason(),
                    enrichedTurnInsight.honorific(),
                    enrichedTurnInsight.extractedPains(),
                    enrichedTurnInsight.hiddenConcerns(),
                    enrichedTurnInsight.recommendedStrategy(),
                    enrichedTurnInsight.careNotes(),
                    enrichedTurnInsight.workingHypothesis(),
                    !actualSentSpeechAct.isBlank() ? actualSentSpeechAct : enrichedTurnInsight.lastActualSentSpeechAct(),
                    priceObjectionCount,
                    finalInPriceObjectionContext,
                    finalHasPresentedDownsell
            );
        }

        String resolvedStage = shouldHandoff
                ? "HUMAN_HANDOFF"
                : (packageInterestAccepted
                    ? FanpageBotPromptBuilder.SalesStage.SOFT_BOOKING_CLOSE.code()
                    : (replyHasPrice || safeConv.priceDiscussed()
                        ? FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION.code()
                        : salesStage.code()));
        List<Integer> delays = finalBubbles.stream().map(FanpageBotResponse.ReplyBubble::typingDelayMs).toList();
        long totalTurnMs = Math.max(1L, (System.nanoTime() - turnStartNs) / 1_000_000L);
        final String bubbleSourceTag = usedSafeHandoffFallback ? "SAFE_HANDOFF_FALLBACK" : "CLEAN_AGENT_LLM";
        this.lastTurnTelemetry = new ThinkingModePolicy.TurnTelemetry(
                stateSyncDecision.effectiveResponseAction(),
                selectedThinkingMode,
                stateSyncDecision.detectedIntent(),
                List.of(),
                List.of(),
                !usedSafeHandoffFallback,
                usedSafeHandoffFallback ? "UNGROUNDED_CATALOG_PRICE" : null,
                retrievalMs,
                llmElapsedMs,
                0L,
                totalTurnMs,
                totalTurnMs,
                cleanStage,
                false,
                cleanBudget.maxTokens(),
                prompt.length(),
                0L,
                0L,
                10_000,
                0L,
                finalBubbles.size(),
                usedSafeHandoffFallback,
                0L,
                0L,
                0L,
                0L,
                0,
                llmElapsedMs,
                usedSafeHandoffFallback,
                usedSafeHandoffFallback ? "UNGROUNDED_CATALOG_PRICE" : null,
                bubbleSourceTag,
                repairApplied,
                repairApplied ? 1 : 0,
                usedSafeHandoffFallback ? "BLOCKED" : "PASS",
                List.copyOf(telemetryErrors),
                "clean_agent",
                llmClient != null ? llmClient.name() : "none",
                "chat",
                finalBubbles.stream().map(b -> bubbleSourceTag).toList()
        );
        ThinkingModePolicy.recordTurnTelemetry(this.lastTurnTelemetry);
        log.info("[CleanAgentMode] page_id={} sender_id={} stage={} signals={} thinkingMode={} priceObjectionCount={} conditionClarified={} heldPkg={} primaryCatalog={} downsellCatalog={} bubbles={} careNoteDeltaPresent={} actualSentSpeechAct={} totalTurnMs={} finalBubbles={}",
                pageId,
                request.senderId(),
                cleanStage,
                currentTurnSignals,
                selectedThinkingMode,
                priceObjectionCount,
                conditionClarified,
                heldPackageDisplay,
                tieredCatalog.primaryRecommendations().size(),
                tieredCatalog.economicalAlternatives().size(),
                finalBubbles.size(),
                !validatedCareNoteDelta.isEmpty(),
                actualSentSpeechAct,
                totalTurnMs,
                finalBubbles.stream().map(FanpageBotResponse.ReplyBubble::text).toList());

        if (shouldHandoff) {
            alignedActiveSkills = alignCleanAgentActiveSkills(
                    alignedActiveSkills,
                    candidateSkills,
                    "HUMAN_HANDOFF",
                    false,
                    false,
                    false
            );
        }
        FanpageBotService.markProvenanceV38Logged();
        return new FanpageBotResponse(
                combinedReplyText,
                finalBubbles,
                delays,
                resolvedStage,
                computeConfidenceScore(fused != null ? fused : List.of()),
                shouldHandoff,
                shouldHandoff ? "COMPLAINT_OR_DISTRESS" : null,
                shouldHandoff && structured != null ? structured.handoffReason() : null,
                extractedLead,
                appliedRules != null ? appliedRules : List.of(),
                enrichedTurnInsight,
                alignedActiveSkills != null ? alignedActiveSkills : List.of(),
                List.of("CLEAN_AGENT_MODE")
        );
    }

    private static FanpageBotResponse buildSupersededAbortedResponse(
            FanpageBotResponse.ExtractedLead extractedLead,
            CustomerInsightDto customerInsight
    ) {
        FanpageBotService.recordCurrentTurnGuard("SUPPRESS", "EPOCH_SUPERSEDED");
        return new FanpageBotResponse(
                "",
                List.of(),
                List.of(),
                "SUPERSEDED_ABORTED",
                0.0,
                false,
                null,
                null,
                extractedLead,
                List.of(),
                customerInsight,
                List.of(),
                List.of("SUPERSEDED_BY_NEWER_EPOCH")
        );
    }

    public static double computeConfidenceScore(List<ReciprocalRankFusion.FusedChunk> fused) {
        if (fused == null || fused.isEmpty()) {
            return 0.0;
        }
        double topRrf = fused.get(0).score();
        double normalized = Math.min(0.96, 0.65 + (topRrf * 8.5));
        return Math.round(normalized * 100.0) / 100.0;
    }

    private static boolean hasQuotedPriceInAssistantHistory(List<FanpageBotRequest.ConversationTurn> history) {
        if (history == null || history.isEmpty()) {
            return false;
        }
        for (FanpageBotRequest.ConversationTurn t : history) {
            if (t != null && ("assistant".equalsIgnoreCase(t.role()) || "bot".equalsIgnoreCase(t.role()))
                    && t.content() != null) {
                if (CleanAgentOutputRefereeAndSanitizer.hasQuotedPriceInText(t.content())) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isChallengeOrQuestionBotAssumption(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        return SentimentIntentLayer.isNegationOrPushbackOnAssumption(message)
                || CHALLENGE_ASSUMPTION_PATTERN.matcher(message.strip()).matches();
    }

    public static boolean isHesitationOrDeferralMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String trimmed = message.strip();
        if (trimmed.matches("(?isU).*\\b(\\d{1,2}\\s*(?:h|giờ)|(?:đặt|giữ|book|xếp)\\s+(?:lịch\\s+)?giúp\\s+(?:mình|chị|anh|em|tôi)|đặt\\s+lịch\\s+lúc)\\b.*")) {
            return false;
        }
        return HESITATION_OR_DEFERRAL_CLEAN_PATTERN.matcher(trimmed).matches();
    }

    public static boolean isPureBusyOrStopDeferralMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        return PURE_BUSY_OR_STOP_DEFERRAL_PATTERN.matcher(message.strip()).matches();
    }

    public static boolean isOperationalOrLogisticsQuestion(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        return OPERATIONAL_OR_LOGISTICS_QUESTION_PATTERN.matcher(message.strip()).matches();
    }

    public static boolean isPromoOrDiscountInquiryMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        return PROMO_OR_DISCOUNT_INQUIRY_CLEAN_PATTERN.matcher(message.strip()).matches();
    }

    public static boolean isExplicitConversationRefusalStopMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String trimmed = message.strip();
        String lower = java.text.Normalizer.normalize(trimmed, java.text.Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        if (lower.matches("(?isU).*\\b(cho\\s+(?:tôi|mình|anh|chị|em|xin)\\s+bảng\\s+giá|gửi\\s+bảng\\s+giá|xem\\s+bảng\\s+giá|bảng\\s+giá\\s+đầy\\s+đủ|đừng\\s+tư\\s+vấn\\s+dài)\\b.*")) {
            return false;
        }
        if (PROACTIVE_BOOKING_OR_PACKAGE_SELECTION_CLEAN_PATTERN.matcher(trimmed).matches()
                && !lower.matches("(?isU).*\\b(đừng\\s+tư\\s+vấn|khỏi\\s+(?:cần\\s+)?tư\\s+vấn|dừng\\s+tư\\s+vấn)\\b.*")) {
            return false;
        }
        return EXPLICIT_CONVERSATION_REFUSAL_STOP_PATTERN.matcher(trimmed).matches();
    }

    public static boolean isNormalPriceOrDetailInquiryMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String trimmed = message.strip();
        if ((CLEAN_PRICE_OBJECTION_EXTRA_PATTERN.matcher(trimmed).matches()
                || EXPLICIT_DOWNSELL_OR_LOW_BUDGET_CLEAN_PATTERN.matcher(trimmed).matches()
                || METAPHORICAL_WAGE_COMPARISON_PRICE_OBJECTION_PATTERN.matcher(trimmed).matches()
                || COMPETITOR_OR_EXTERNAL_PRICE_COMPARISON_PATTERN.matcher(trimmed).matches())
                && !NON_PRICE_CAO_EXCLUSION_PATTERN.matcher(trimmed).matches()) {
            return false;
        }
        return NORMAL_PRICE_OR_DETAIL_INQUIRY_CLEAN_PATTERN.matcher(trimmed).matches()
                || PROMO_OR_DISCOUNT_INQUIRY_CLEAN_PATTERN.matcher(trimmed).matches();
    }

    public static boolean isConfirmedCustomerPriceObjection(String message) {
        return isConfirmedCustomerPriceObjection(message, true);
    }

    private static final Pattern PURE_BUDGET_BOUNDED_SERVICE_INQUIRY_PATTERN = Pattern.compile(
            "(?isU).*\\b("
            + "(?:có|tìm|xin|tư\\s+vấn)\\s+(?:gói|dịch\\s+vụ|liệu\\s+trình)\\s+(?:nào\\s+)?(?:ở\\s+mức\\s+|tầm\\s+|khoảng\\s+|dưới\\s+|giá\\s+)?(?:\\d{2,4}\\s*(?:k|nghìn|ngàn)|\\d{2,4}[.,]000\\s*(?:đ|đồng|vnđ|vnd)?)|"
            + "(?:tầm|khoảng|dưới|mức|với)\\s*(?:\\d{2,4}\\s*(?:k|nghìn|ngàn)|\\d{2,4}[.,]000\\s*(?:đ|đồng|vnđ|vnd)?)\\s+.*\\b(?:có\\s+)?(?:gói|dịch\\s+vụ|liệu\\s+trình)|"
            + "(?:\\d{2,4}\\s*(?:k|nghìn|ngàn)|\\d{2,4}[.,]000\\s*(?:đ|đồng|vnđ|vnd)?)\\s+có\\s+(?:gói|dịch\\s+vụ|liệu\\s+trình)\\s+nào"
            + ")\\b.*"
    );

    public static boolean isPureBudgetBoundedServiceInquiry(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String trimmed = message.strip();
        if (CleanAgentOutputRefereeAndSanitizer.isCommunicationStyleComplaintMessage(trimmed)
                || isExplicitConversationRefusalStopMessage(trimmed)
                || CLEAN_PRICE_OBJECTION_EXTRA_PATTERN.matcher(trimmed).matches()
                || METAPHORICAL_WAGE_COMPARISON_PRICE_OBJECTION_PATTERN.matcher(trimmed).matches()
                || COMPETITOR_OR_EXTERNAL_PRICE_COMPARISON_PATTERN.matcher(trimmed).matches()) {
            return false;
        }
        String lower = java.text.Normalizer.normalize(trimmed, java.text.Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT);
        if (lower.matches("(?isU).*\\b(rẻ\\s+hơn|mềm\\s+hơn|nhẹ\\s+tiền\\s+hơn|nhẹ\\s+chi\\s+phí\\s+hơn|nhẹ\\s+hơn|thấp\\s+hơn|tiết\\s+kiệm\\s+hơn|vượt\\s+ngân\\s+sách|chưa\\s+đủ\\s+tiền|không\\s+đủ\\s+tiền)\\b.*")) {
            return false;
        }
        return PURE_BUDGET_BOUNDED_SERVICE_INQUIRY_PATTERN.matcher(trimmed).matches();
    }

    static boolean hasCompetitorOrExternalPriceComparisonCue(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String trimmed = message.strip();
        if (CleanAgentOutputRefereeAndSanitizer.isCommunicationStyleComplaintMessage(trimmed)
                || isExplicitConversationRefusalStopMessage(trimmed)) {
            return false;
        }
        return COMPETITOR_OR_EXTERNAL_PRICE_COMPARISON_PATTERN.matcher(trimmed).matches();
    }

    public static boolean isConfirmedCustomerPriceObjection(String message, boolean priceAlreadyDiscussedOrQuoted) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String trimmed = message.strip();
        if (CleanAgentOutputRefereeAndSanitizer.isCommunicationStyleComplaintMessage(trimmed)
                || isExplicitConversationRefusalStopMessage(trimmed)
                || isPureBudgetBoundedServiceInquiry(trimmed)) {
            return false;
        }
        if (NON_PRICE_CAO_EXCLUSION_PATTERN.matcher(trimmed).matches()
                && !trimmed.toLowerCase(Locale.ROOT).matches("(?isU).*\\b(giá|chi\\s+phí|tiền|gói|đắt|mắc|chát)\\b.*")) {
            return false;
        }
        boolean matchedCompetitorComparison = COMPETITOR_OR_EXTERNAL_PRICE_COMPARISON_PATTERN.matcher(trimmed).matches();
        if ((NORMAL_PRICE_OR_DETAIL_INQUIRY_CLEAN_PATTERN.matcher(trimmed).matches()
                || PROMO_OR_DISCOUNT_INQUIRY_CLEAN_PATTERN.matcher(trimmed).matches())
                && !CLEAN_PRICE_OBJECTION_EXTRA_PATTERN.matcher(trimmed).matches()
                && !METAPHORICAL_WAGE_COMPARISON_PRICE_OBJECTION_PATTERN.matcher(trimmed).matches()
                && !EXPLICIT_DOWNSELL_OR_LOW_BUDGET_CLEAN_PATTERN.matcher(trimmed).matches()
                && !matchedCompetitorComparison) {
            return false;
        }
        boolean matchedWageComparison = METAPHORICAL_WAGE_COMPARISON_PRICE_OBJECTION_PATTERN.matcher(trimmed).matches();
        boolean matchedObjectionPattern = CLEAN_PRICE_OBJECTION_EXTRA_PATTERN.matcher(trimmed).matches()
                || matchedWageComparison
                || matchedCompetitorComparison
                || EXPLICIT_DOWNSELL_OR_LOW_BUDGET_CLEAN_PATTERN.matcher(trimmed).matches()
                || PrimaryActionRouter.isPriceObjectionMessage(trimmed);
        if (!matchedObjectionPattern) {
            return false;
        }
        if (priceAlreadyDiscussedOrQuoted) {
            return true;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return lower.matches("(?isU).*\\b(giá|chi\\s+phí|tiền|phí|đắt|mắc|chát|đau\\s+ví|quá\\s+sức|mềm\\s+hơn|rẻ\\s+hơn|tiết\\s+kiệm|\\d{2,3}\\s*k)\\b.*")
                || lower.matches("(?isU)^\\s*(?:dạ\\s+|ơi\\s+|shop\\s+ơi\\s+|bạn\\s+ơi\\s+|em\\s+ơi\\s+|ủa\\s+|ơ\\s+|ui\\s+|ôi\\s+|trời\\s+ơi\\s+|sao\\s+)*(?:hơi\\s+|cũng\\s+|thấy\\s+|nghe\\s+)?(?:cao|căng)(?:\\s+(?:nhỉ|thế|quá|vậy|ghê|dữ|phết|nha|nhé|ạ|hả|vậy\\s+em|vậy\\s+shop|thế\\s+em|thế\\s+shop|quá\\s+nhỉ|quá\\s+ạ|quá\\s+ha))?\\s*[.!?~]*\\s*$");
    }

    public static boolean isCleanPriceObjectionMessage(String message) {
        return isConfirmedCustomerPriceObjection(message, true);
    }

    public static int countConfirmedCustomerPriceObjectionsInHistory(List<FanpageBotRequest.ConversationTurn> history) {
        if (history == null || history.isEmpty()) {
            return 0;
        }
        int count = 0;
        boolean priceSeenInConversation = false;
        for (FanpageBotRequest.ConversationTurn turn : history) {
            if (turn == null || turn.content() == null || turn.content().isBlank()) {
                continue;
            }
            String text = turn.content().strip();
            boolean isBot = "assistant".equalsIgnoreCase(turn.role()) || "bot".equalsIgnoreCase(turn.role());
            if (isBot) {
                if (BubbleTextSanitizer.PRICE_PATTERN.matcher(text).find()
                        || text.toLowerCase(Locale.ROOT).matches("(?isU).*\\b(\\d{2,3}\\s*k|gói\\s+dưỡng\\s+sinh|gói\\s+massage|liệu\\s+trình)\\b.*")) {
                    priceSeenInConversation = true;
                }
            } else {
                if (isConfirmedCustomerPriceObjection(text, priceSeenInConversation)) {
                    count++;
                    priceSeenInConversation = true;
                }
            }
        }
        return count;
    }

    public static boolean shouldReconcilePostLlmPriceObjection(
            String detectedCustomerIntent,
            String customerMessage,
            boolean priceAlreadyQuotedOrDiscussed,
            boolean isMetaOrChallenge,
            boolean isHesitation,
            boolean isBareAck,
            boolean hasProactiveBookingInfo,
            boolean packageInterestAccepted
    ) {
        if (isMetaOrChallenge || isHesitation || isBareAck || hasProactiveBookingInfo || packageInterestAccepted) {
            return false;
        }
        return shouldReconcilePostLlmPriceObjection(
                false,
                detectedCustomerIntent,
                priceAlreadyQuotedOrDiscussed,
                customerMessage
        );
    }

    public static boolean shouldReconcilePostLlmPriceObjection(
            boolean preLlmConfirmedPriceObjection,
            String detectedCustomerIntent,
            boolean priceAlreadyQuotedOrDiscussed,
            String customerMessage
    ) {
        if (preLlmConfirmedPriceObjection) {
            return false;
        }
        if (detectedCustomerIntent == null
                || !BRAIN_INTENT_PRICE_OBJECTION.equalsIgnoreCase(detectedCustomerIntent.strip())) {
            return false;
        }
        if (!priceAlreadyQuotedOrDiscussed) {
            return false;
        }
        if (customerMessage == null || customerMessage.isBlank()) {
            return false;
        }
        String trimmed = customerMessage.strip();
        if (isNormalPriceOrDetailInquiryMessage(trimmed)
                || isOperationalOrLogisticsQuestion(trimmed)
                || (isPromoOrDiscountInquiryMessage(trimmed) && !isConfirmedCustomerPriceObjection(trimmed, true))
                || isBareAcknowledgmentMessage(trimmed)
                || hasProactiveBookingOrPackageSelectionInMessage(trimmed)
                || isHesitationOrDeferralMessage(trimmed)) {
            return false;
        }
        return true;
    }

    public static List<String> extractCanonicalBodyAreas(String customerMessage) {
        return extractCanonicalBodyAreas(customerMessage, List.of(), List.of());
    }

    public static List<String> extractCanonicalBodyAreas(
            String customerMessage,
            List<String> priorBodyAreas,
            List<String> domainKeywords
    ) {
        return ScopeMatcher.mergeScopes(
                customerMessage,
                priorBodyAreas != null ? priorBodyAreas : List.of(),
                domainKeywords != null ? domainKeywords : List.of()
        );
    }

    public static boolean isExplicitDownsellOrLowBudgetRequest(String message, ConversationState convState) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String trimmed = message.strip();
        if (PROACTIVE_BOOKING_OR_PACKAGE_SELECTION_CLEAN_PATTERN.matcher(trimmed).matches()
                && !EXPLICIT_DOWNSELL_OR_LOW_BUDGET_CLEAN_PATTERN.matcher(trimmed).matches()
                && !CLEAN_PRICE_OBJECTION_EXTRA_PATTERN.matcher(trimmed).matches()) {
            return false;
        }
        return SentimentIntentLayer.isExplicitDownsellRequest(trimmed, convState)
                || EXPLICIT_DOWNSELL_OR_LOW_BUDGET_CLEAN_PATTERN.matcher(trimmed).matches();
    }

    public static boolean isPositiveInterestForTrialInvite(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        return POSITIVE_TRIAL_INTEREST_CLEAN_PATTERN.matcher(message.strip()).matches();
    }

    public static boolean isBareAcknowledgmentMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String trimmed = message.strip();
        if (isHesitationOrDeferralMessage(trimmed) || hasProactiveBookingOrPackageSelectionInMessage(trimmed)) {
            return false;
        }
        return BARE_ACKNOWLEDGMENT_CLEAN_PATTERN.matcher(trimmed).matches();
    }

    public static boolean hasProactiveBookingOrPackageSelectionInMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String trimmed = message.strip();
        if (trimmed.contains("?")
                || isHesitationOrDeferralMessage(trimmed)
                || isOperationalOrLogisticsQuestion(trimmed)
                || isPromoOrDiscountInquiryMessage(trimmed)
                || isNormalPriceOrDetailInquiryMessage(trimmed)
                || isConfirmedCustomerPriceObjection(trimmed, true)) {
            return false;
        }
        return PROACTIVE_BOOKING_OR_PACKAGE_SELECTION_CLEAN_PATTERN.matcher(trimmed).matches()
                || DECLARATIVE_PRODUCT_SELECTION_CLEAN_PATTERN.matcher(trimmed).matches();
    }

    /**
     * Máy trạng thái Đặt lịch / Đặt hàng đa ngành 6 trạng thái (Checkpoint C3-B):
     * 1. NONE
     * 2. AWAITING_PRODUCT_SELECTION (tuyệt đối không lấy selectedProductId từ heldPackage khi lịch sử có >= 2 sản phẩm)
     * 3. AWAITING_DATE_OR_TIME
     * 4. AWAITING_AVAILABILITY_CHECK
     * 5. AWAITING_CUSTOMER_CONFIRMATION
     * 6. CONFIRMED (chỉ khi đã có xác nhận chính thức từ công cụ đặt lịch hoặc nhân sự)
     */
    public static ConversationState.StructuredBookingState resolveMultiIndustryBookingState(
            String mergedMessage,
            ConversationState.StructuredBookingState priorBookingState,
            boolean bookingIntentActiveThisTurn,
            boolean shouldCancelActiveBooking,
            boolean officiallyConfirmedByToolOrStaff,
            List<PackageCatalogService.PackageSummary> presentedPackagesInHistory,
            List<PackageCatalogService.PackageSummary> allCatalog,
            CleanAgentCatalogAndPromptBuilder.TieredCatalogResult tieredCatalog,
            CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution quotedReplyResolution,
            java.time.ZoneId pageZone,
            java.time.LocalDate referenceDate
    ) {
        return resolveMultiIndustryBookingState(
                mergedMessage,
                priorBookingState,
                bookingIntentActiveThisTurn,
                shouldCancelActiveBooking,
                officiallyConfirmedByToolOrStaff,
                presentedPackagesInHistory,
                allCatalog,
                tieredCatalog,
                quotedReplyResolution,
                pageZone,
                referenceDate,
                null
        );
    }

    public static ConversationState.StructuredBookingState resolveMultiIndustryBookingState(
            String mergedMessage,
            ConversationState.StructuredBookingState priorBookingState,
            boolean bookingIntentActiveThisTurn,
            boolean shouldCancelActiveBooking,
            boolean officiallyConfirmedByToolOrStaff,
            List<PackageCatalogService.PackageSummary> presentedPackagesInHistory,
            List<PackageCatalogService.PackageSummary> allCatalog,
            CleanAgentCatalogAndPromptBuilder.TieredCatalogResult tieredCatalog,
            CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution quotedReplyResolution,
            java.time.ZoneId pageZone,
            java.time.LocalDate referenceDate,
            String precedingBotTurnText
    ) {
        java.time.ZoneId safeZone = pageZone != null ? pageZone : java.time.ZoneId.of("Asia/Ho_Chi_Minh");
        ConversationState.StructuredBookingState safePrior = priorBookingState != null
                ? priorBookingState
                : ConversationState.StructuredBookingState.none();

        if (AdaptiveConversationIntelligence.isExplicitBookingCancelOrRefusalMessage(mergedMessage)) {
            return ConversationState.StructuredBookingState.none();
        }
        if (shouldCancelActiveBooking) {
            if (safePrior.status() == ConversationState.BookingStatus.AWAITING_CUSTOMER_CONFIRMATION
                    && isHesitationOrDeferralMessage(mergedMessage)) {
                return safePrior;
            }
            return ConversationState.StructuredBookingState.none();
        }
        if (officiallyConfirmedByToolOrStaff) {
            ConversationState.ParsedBookingDateTime dt = ConversationState.parseBookingDateTime(
                    mergedMessage, safePrior.bookingDate(), safePrior.bookingTime(), safeZone, referenceDate);
            return new ConversationState.StructuredBookingState(
                    safePrior.selectedProductId(),
                    safePrior.selectedProductLabel(),
                    dt.bookingDate(),
                    dt.resolvedIsoDate(),
                    dt.bookingTime(),
                    dt.timezoneId(),
                    ConversationState.BookingStatus.CONFIRMED,
                    safePrior.candidateProductIds()
            );
        }
        if (!bookingIntentActiveThisTurn) {
            return ConversationState.StructuredBookingState.none();
        }

        List<PackageCatalogService.PackageSummary> distinctPresented = deduplicateNonAddonProducts(presentedPackagesInHistory);
        PackageCatalogService.PackageSummary explicitPkgInTurn = matchExplicitProductSelectionInMessage(
                mergedMessage,
                distinctPresented,
                allCatalog,
                tieredCatalog,
                quotedReplyResolution
        );

        boolean shortAffirmativeNod = isShortAffirmativeBookingNod(mergedMessage);
        boolean concreteTimeProposalFromBot = shortAffirmativeNod
                && isConcreteTimeSlotProposalBotReply(precedingBotTurnText, safeZone, referenceDate);
        PackageCatalogService.PackageSummary singleInvitedPkgFromBot = (explicitPkgInTurn == null && shortAffirmativeNod)
                ? matchSingleInvitedProductInPrecedingBotTurn(
                        precedingBotTurnText,
                        distinctPresented,
                        allCatalog,
                        safeZone,
                        referenceDate
                )
                : null;

        PackageCatalogService.PackageSummary selectedPkg = null;
        if (explicitPkgInTurn != null) {
            selectedPkg = explicitPkgInTurn;
        } else if (safePrior.selectedProductId() != null && !safePrior.selectedProductId().isBlank()) {
            selectedPkg = findProductByIdInCatalog(safePrior.selectedProductId(), distinctPresented, allCatalog);
        } else if (singleInvitedPkgFromBot != null) {
            selectedPkg = singleInvitedPkgFromBot;
        } else if (distinctPresented.size() == 1) {
            selectedPkg = distinctPresented.get(0);
        }
        // Khi distinctPresented.size() >= 2 hoặc == 0 mà khách chưa chủ động chỉ định sản phẩm (và câu bot liền trước không mời đích danh 1 sản phẩm duy nhất):
        // selectedPkg BẮT BUỘC giữ nguyên null (tuyệt đối không lấy từ heldPackage!)

        String selectedProductId = selectedPkg != null
                ? selectedPkg.productId()
                : (explicitPkgInTurn == null && safePrior.selectedProductId() != null && !safePrior.selectedProductId().isBlank()
                    ? safePrior.selectedProductId()
                    : null);
        String selectedProductLabel = selectedPkg != null
                ? (selectedPkg.canonicalLabel() + " (" + selectedPkg.priceDisplay() + ")")
                : safePrior.selectedProductLabel();

        List<String> candidateProductIds;
        if (selectedProductId != null) {
            candidateProductIds = List.of(selectedProductId);
        } else if (!distinctPresented.isEmpty()) {
            candidateProductIds = distinctPresented.stream()
                    .map(PackageCatalogService.PackageSummary::productId)
                    .toList();
        } else if (tieredCatalog != null && tieredCatalog.allAllowedPackages() != null) {
            candidateProductIds = deduplicateNonAddonProducts(tieredCatalog.allAllowedPackages()).stream()
                    .map(PackageCatalogService.PackageSummary::productId)
                    .toList();
        } else {
            candidateProductIds = List.of();
        }

        ConversationState.ParsedBookingDateTime parsedDT = ConversationState.parseBookingDateTime(
                mergedMessage,
                safePrior.bookingDate(),
                safePrior.bookingTime(),
                safeZone,
                referenceDate
        );
        if (concreteTimeProposalFromBot && (parsedDT.bookingTime() == null || parsedDT.bookingDate() == null)) {
            ConversationState.ParsedBookingDateTime botProposedDT = ConversationState.parseBookingDateTime(
                    precedingBotTurnText,
                    parsedDT.bookingDate(),
                    parsedDT.bookingTime(),
                    safeZone,
                    referenceDate
            );
            if (botProposedDT.bookingTime() != null) {
                String inheritedDate = botProposedDT.bookingDate() != null
                        ? botProposedDT.bookingDate()
                        : (parsedDT.bookingDate() != null ? parsedDT.bookingDate() : "hôm nay");
                parsedDT = ConversationState.parseBookingDateTime(
                        inheritedDate + " " + botProposedDT.bookingTime(),
                        inheritedDate,
                        botProposedDT.bookingTime(),
                        safeZone,
                        referenceDate
                );
            }
        }

        boolean slotChangedFromPrior = !java.util.Objects.equals(selectedProductId, safePrior.selectedProductId())
                || !java.util.Objects.equals(parsedDT.bookingDate(), safePrior.bookingDate())
                || !java.util.Objects.equals(parsedDT.bookingTime(), safePrior.bookingTime());

        ConversationState.BookingStatus nextStatus;
        if (selectedProductId == null || selectedProductId.isBlank()) {
            nextStatus = ConversationState.BookingStatus.AWAITING_PRODUCT_SELECTION;
        } else if (parsedDT.bookingDate() == null || parsedDT.bookingTime() == null) {
            nextStatus = ConversationState.BookingStatus.AWAITING_DATE_OR_TIME;
        } else if (safePrior.status() == ConversationState.BookingStatus.CONFIRMED && !slotChangedFromPrior) {
            nextStatus = ConversationState.BookingStatus.CONFIRMED;
        } else if (safePrior.status() == ConversationState.BookingStatus.AWAITING_CUSTOMER_CONFIRMATION && !slotChangedFromPrior) {
            nextStatus = ConversationState.BookingStatus.AWAITING_CUSTOMER_CONFIRMATION;
        } else {
            nextStatus = ConversationState.BookingStatus.AWAITING_AVAILABILITY_CHECK;
        }

        return new ConversationState.StructuredBookingState(
                selectedProductId,
                selectedProductLabel,
                parsedDT.bookingDate(),
                parsedDT.resolvedIsoDate(),
                parsedDT.bookingTime(),
                parsedDT.timezoneId(),
                nextStatus,
                candidateProductIds
        );
    }

    private static final Pattern EXPLICIT_CUSTOMER_BOOKING_CONFIRMATION_PATTERN = Pattern.compile(
            "(?isU)^\\s*(?:dạ\\s+|vâng\\s+|ừ\\s+|uk\\s+|ok\\s+|oke\\s+|okay\\s+)?(?:"
                    + "ok(?:e|ay)?\\s+(?:chốt|đặt|giữ\\s+lịch|lên\\s+lịch|xác\\s+nhận|nhé|nha|luôn|đi|giúp\\s+(?:mình|tôi|anh|chị|em))|"
                    + "(?:chốt|xác\\s+nhận|đồng\\s+ý)(?:\\s+(?:lịch|đặt|giữ\\s+lịch|khung\\s+giờ\\s+này|giúp\\s+(?:mình|tôi|anh|chị|em)|nhé|nha|luôn|đi|ạ))*|"
                    + "(?:vâng|dạ|ừ|uk)\\s+(?:chốt|đặt|giữ\\s+lịch|xác\\s+nhận)(?:\\s+(?:giúp\\s+(?:mình|tôi|anh|chị|em)|nhé|nha|luôn|ạ))*|"
                    + "đặt\\s+luôn\\s+(?:giúp\\s+(?:mình|tôi|anh|chị|em)|nhé|nha|ạ)"
                    + ")[^?]*$"
    );

    public static boolean isExplicitCustomerBookingConfirmationMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String trimmed = java.text.Normalizer.normalize(message.strip(), java.text.Normalizer.Form.NFC);
        if (trimmed.contains("?")
                || isHesitationOrDeferralMessage(trimmed)
                || AdaptiveConversationIntelligence.isExplicitBookingCancelOrRefusalMessage(trimmed)
                || isOperationalOrLogisticsQuestion(trimmed)
                || isNormalPriceOrDetailInquiryMessage(trimmed)) {
            return false;
        }
        if (EXPLICIT_CUSTOMER_BOOKING_CONFIRMATION_PATTERN.matcher(trimmed).matches()) {
            return true;
        }
        return isBareAcknowledgmentMessage(trimmed);
    }

    public record BookingLifecycleEvaluation(
            ConversationState.StructuredBookingState bookingState,
            BookingExecutionResult executionResult,
            String lifecycleAction,
            String unavailableRequestedTime
    ) {
    }

    private BookingLifecycleEvaluation applyBookingGatewayLifecycle(
            UUID pageId,
            String senderId,
            ConversationState.StructuredBookingState priorState,
            ConversationState.StructuredBookingState resolvedAfterPatch,
            String customerMessage,
            String branch
    ) {
        ConversationState.StructuredBookingState safePrior = priorState != null
                ? priorState
                : ConversationState.StructuredBookingState.none();
        ConversationState.StructuredBookingState safeCurrent = resolvedAfterPatch != null
                ? resolvedAfterPatch
                : ConversationState.StructuredBookingState.none();

        // 0. Explicit cancellation / refusal resets booking state and invalidates any prior mock availability
        if (AdaptiveConversationIntelligence.isExplicitBookingCancelOrRefusalMessage(customerMessage)
                || (safePrior.status() != ConversationState.BookingStatus.NONE
                    && safeCurrent.status() == ConversationState.BookingStatus.NONE)) {
            if (bookingGateway != null) {
                bookingGateway.invalidateAvailabilityForConversation(pageId, senderId);
            }
            return new BookingLifecycleEvaluation(
                    ConversationState.StructuredBookingState.none(),
                    BookingExecutionResult.disabled(),
                    safePrior.status() != ConversationState.BookingStatus.NONE ? "EXPLICIT_BOOKING_CANCEL" : "NONE",
                    null
            );
        }

        // Multi-product ambiguity guard (Scenario H)
        if (safeCurrent.status() == ConversationState.BookingStatus.AWAITING_PRODUCT_SELECTION) {
            return new BookingLifecycleEvaluation(
                    safeCurrent,
                    BookingExecutionResult.disabled(),
                    "MULTI_PRODUCT_SELECTION_REQUIRED",
                    null
            );
        }

        boolean slotMutatedFromPrior = safePrior.status() != ConversationState.BookingStatus.NONE && (
                !java.util.Objects.equals(safeCurrent.selectedProductId(), safePrior.selectedProductId())
                || !java.util.Objects.equals(safeCurrent.bookingDate(), safePrior.bookingDate())
                || !java.util.Objects.equals(safeCurrent.bookingTime(), safePrior.bookingTime())
        );

        // 1. Production Default (DISABLED): never call MockBookingGateway, never transition to CONFIRMED via mock
        if (bookingGatewayMode != BookingGateway.Mode.MOCK || bookingGateway == null) {
            ConversationState.StructuredBookingState safeDisabledState = safeCurrent;
            if (safeDisabledState.status() == ConversationState.BookingStatus.CONFIRMED
                    || safeDisabledState.status() == ConversationState.BookingStatus.AWAITING_CUSTOMER_CONFIRMATION) {
                safeDisabledState = new ConversationState.StructuredBookingState(
                        safeDisabledState.selectedProductId(),
                        safeDisabledState.selectedProductLabel(),
                        safeDisabledState.bookingDate(),
                        safeDisabledState.resolvedIsoDate(),
                        safeDisabledState.bookingTime(),
                        safeDisabledState.timezoneId(),
                        ConversationState.BookingStatus.AWAITING_AVAILABILITY_CHECK,
                        safeDisabledState.candidateProductIds()
                );
            }
            String disabledAction = (safeDisabledState.status() == ConversationState.BookingStatus.AWAITING_AVAILABILITY_CHECK
                    && isExplicitCustomerBookingConfirmationMessage(customerMessage))
                    ? "DISABLED_GATEWAY_HOLD"
                    : "NONE";
            return new BookingLifecycleEvaluation(
                    safeDisabledState,
                    BookingExecutionResult.disabled(),
                    disabledAction,
                    null
            );
        }

        // 2. MOCK Mode
        if (slotMutatedFromPrior) {
            bookingGateway.invalidateAvailabilityForConversation(pageId, senderId);
        }

        // Case 2A: Customer hesitates/defers while in AWAITING_CUSTOMER_CONFIRMATION (Scenario D)
        if (safePrior.status() == ConversationState.BookingStatus.AWAITING_CUSTOMER_CONFIRMATION
                && !slotMutatedFromPrior
                && isHesitationOrDeferralMessage(customerMessage)) {
            BookingGateway.BookingSlotRequest slotReq = new BookingGateway.BookingSlotRequest(
                    pageId,
                    senderId,
                    safePrior.selectedProductId(),
                    safePrior.selectedProductLabel(),
                    safePrior.bookingDate(),
                    safePrior.resolvedIsoDate(),
                    safePrior.bookingTime(),
                    safePrior.timezoneId(),
                    branch
            );
            return new BookingLifecycleEvaluation(
                    safePrior,
                    BookingExecutionResult.mockAvailable(slotReq.slotSignature(), branch),
                    "HESITATION_ON_CONFIRMATION",
                    null
            );
        }

        // Case 2B: Customer confirms a previously verified slot (Step 4 & Step 5 of Section 3.3)
        if (safePrior.status() == ConversationState.BookingStatus.AWAITING_CUSTOMER_CONFIRMATION
                && !slotMutatedFromPrior
                && isExplicitCustomerBookingConfirmationMessage(customerMessage)) {
            BookingGateway.BookingSlotRequest slotReq = new BookingGateway.BookingSlotRequest(
                    pageId,
                    senderId,
                    safePrior.selectedProductId(),
                    safePrior.selectedProductLabel(),
                    safePrior.bookingDate(),
                    safePrior.resolvedIsoDate(),
                    safePrior.bookingTime(),
                    safePrior.timezoneId(),
                    branch
            );
            BookingExecutionResult createRes = bookingGateway.createBooking(slotReq);
            if (createRes.isConfirmedSuccess()) {
                ConversationState.StructuredBookingState confirmedState = new ConversationState.StructuredBookingState(
                        safePrior.selectedProductId(),
                        safePrior.selectedProductLabel(),
                        safePrior.bookingDate(),
                        safePrior.resolvedIsoDate(),
                        safePrior.bookingTime(),
                        safePrior.timezoneId(),
                        ConversationState.BookingStatus.CONFIRMED,
                        safePrior.candidateProductIds()
                );
                return new BookingLifecycleEvaluation(confirmedState, createRes, "CREATE_BOOKING_SUCCESS", null);
            } else {
                return new BookingLifecycleEvaluation(safePrior, createRes, "CREATE_BOOKING_FAILURE", null);
            }
        }

        // Case 2C: Duplicate confirmation event when already CONFIRMED (Scenario G — Idempotency)
        if (safePrior.status() == ConversationState.BookingStatus.CONFIRMED
                && !slotMutatedFromPrior
                && isExplicitCustomerBookingConfirmationMessage(customerMessage)) {
            BookingGateway.BookingSlotRequest slotReq = new BookingGateway.BookingSlotRequest(
                    pageId,
                    senderId,
                    safePrior.selectedProductId(),
                    safePrior.selectedProductLabel(),
                    safePrior.bookingDate(),
                    safePrior.resolvedIsoDate(),
                    safePrior.bookingTime(),
                    safePrior.timezoneId(),
                    branch
            );
            BookingExecutionResult dedupRes = bookingGateway.createBooking(slotReq);
            return new BookingLifecycleEvaluation(safePrior, dedupRes, "CREATE_BOOKING_IDEMPOTENT_REPLAY", null);
        }

        // Case 2D: Slot has all 3 required parameters (product + date + time) and needs availability check (Step 2 & Step 3)
        boolean hasFullSlot = safeCurrent.selectedProductId() != null && !safeCurrent.selectedProductId().isBlank()
                && safeCurrent.bookingDate() != null && !safeCurrent.bookingDate().isBlank()
                && safeCurrent.bookingTime() != null && !safeCurrent.bookingTime().isBlank();
        if (hasFullSlot && (safeCurrent.status() == ConversationState.BookingStatus.AWAITING_AVAILABILITY_CHECK || slotMutatedFromPrior)) {
            BookingGateway.BookingSlotRequest slotReq = new BookingGateway.BookingSlotRequest(
                    pageId,
                    senderId,
                    safeCurrent.selectedProductId(),
                    safeCurrent.selectedProductLabel(),
                    safeCurrent.bookingDate(),
                    safeCurrent.resolvedIsoDate(),
                    safeCurrent.bookingTime(),
                    safeCurrent.timezoneId(),
                    branch
            );
            BookingExecutionResult checkRes = bookingGateway.checkAvailability(slotReq);
            if (checkRes.status() == BookingExecutionResult.Status.SUCCESS && checkRes.available()) {
                ConversationState.StructuredBookingState awaitingConfirm = new ConversationState.StructuredBookingState(
                        safeCurrent.selectedProductId(),
                        safeCurrent.selectedProductLabel(),
                        safeCurrent.bookingDate(),
                        safeCurrent.resolvedIsoDate(),
                        safeCurrent.bookingTime(),
                        safeCurrent.timezoneId(),
                        ConversationState.BookingStatus.AWAITING_CUSTOMER_CONFIRMATION,
                        safeCurrent.candidateProductIds()
                );
                return new BookingLifecycleEvaluation(awaitingConfirm, checkRes, "CHECK_AVAILABILITY_AVAILABLE", null);
            } else if (checkRes.status() == BookingExecutionResult.Status.SUCCESS && !checkRes.available()) {
                ConversationState.StructuredBookingState awaitingNewTime = new ConversationState.StructuredBookingState(
                        safeCurrent.selectedProductId(),
                        safeCurrent.selectedProductLabel(),
                        safeCurrent.bookingDate(),
                        safeCurrent.resolvedIsoDate(),
                        null,
                        safeCurrent.timezoneId(),
                        ConversationState.BookingStatus.AWAITING_DATE_OR_TIME,
                        safeCurrent.candidateProductIds()
                );
                return new BookingLifecycleEvaluation(
                        awaitingNewTime,
                        checkRes,
                        "CHECK_AVAILABILITY_UNAVAILABLE",
                        safeCurrent.bookingTime()
                );
            } else {
                return new BookingLifecycleEvaluation(safeCurrent, checkRes, "CHECK_AVAILABILITY_FAILURE", null);
            }
        }

        // Preserve existing MOCK booking execution metadata when already CONFIRMED and customer asks an unrelated follow-up
        if (safeCurrent.status() == ConversationState.BookingStatus.CONFIRMED && agentBrainService != null) {
            String existingBookingId = agentBrainService.recallBookingId(pageId, senderId);
            if (existingBookingId != null) {
                BookingGateway.BookingSlotRequest slotReq = new BookingGateway.BookingSlotRequest(
                        pageId,
                        senderId,
                        safeCurrent.selectedProductId(),
                        safeCurrent.selectedProductLabel(),
                        safeCurrent.bookingDate(),
                        safeCurrent.resolvedIsoDate(),
                        safeCurrent.bookingTime(),
                        safeCurrent.timezoneId(),
                        branch
                );
                return new BookingLifecycleEvaluation(
                        safeCurrent,
                        BookingExecutionResult.mockCreated(
                                existingBookingId,
                                slotReq.slotSignature(),
                                branch),
                        "NONE",
                        null
                );
            }
        }

        return new BookingLifecycleEvaluation(safeCurrent, BookingExecutionResult.disabled(), "NONE", null);
    }

    private static List<FanpageBotResponse.ReplyBubble> alignBubblesWithBookingLifecycle(
            List<FanpageBotResponse.ReplyBubble> currentBubbles,
            BookingLifecycleEvaluation eval,
            ConversationState.StructuredBookingState priorState,
            String customerMessage,
            List<PackageCatalogService.PackageSummary> allCatalog,
            List<PackageCatalogService.PackageSummary> presentedPackagesInHistory,
            String selfPronoun,
            String safeHonorific
    ) {
        if (eval == null || eval.lifecycleAction() == null || "NONE".equals(eval.lifecycleAction())) {
            return currentBubbles != null ? currentBubbles : List.of();
        }
        String sp = (selfPronoun != null && !selfPronoun.isBlank()) ? selfPronoun.strip() : "em";
        String hon = (safeHonorific != null && !safeHonorific.isBlank()) ? safeHonorific.strip() : "mình";
        String capHon = BubbleTextSanitizer.capitalizeFirstLetter(hon);
        ConversationState.StructuredBookingState st = eval.bookingState() != null
                ? eval.bookingState()
                : ConversationState.StructuredBookingState.none();
        BookingExecutionResult res = eval.executionResult() != null
                ? eval.executionResult()
                : BookingExecutionResult.disabled();

        PackageCatalogService.PackageSummary matchedPkg = st.selectedProductId() != null
                ? AdaptiveConversationIntelligence.resolveCatalogProductByFlexibleIdentifier(
                        st.selectedProductId(), presentedPackagesInHistory, allCatalog)
                : null;
        String productDisplayWithPrice = matchedPkg != null
                ? (matchedPkg.canonicalLabel() + " (" + matchedPkg.priceDisplay() + ")")
                : (st.selectedProductLabel() != null && !st.selectedProductLabel().isBlank()
                    ? st.selectedProductLabel()
                    : "dịch vụ đã chọn");

        return switch (eval.lifecycleAction()) {
            case "CHECK_AVAILABILITY_AVAILABLE" -> List.of(new FanpageBotResponse.ReplyBubble(
                    "Dạ khung giờ " + st.bookingTime() + " " + st.bookingDate() + " cho " + productDisplayWithPrice
                            + " hiện đang còn chỗ trống trên lịch mô phỏng ạ. " + capHon + " xác nhận giúp " + sp
                            + " để " + sp + " tiến hành ghi nhận lịch hẹn nhé ạ?",
                    0
            ));
            case "CHECK_AVAILABILITY_UNAVAILABLE" -> List.of(new FanpageBotResponse.ReplyBubble(
                    "Dạ rất tiếc khung giờ " + (eval.unavailableRequestedTime() != null ? eval.unavailableRequestedTime() + " " : "")
                            + (st.bookingDate() != null ? st.bookingDate() + " " : "")
                            + "cho " + productDisplayWithPrice + " hiện đã kín lịch trên hệ thống mô phỏng ạ. "
                            + capHon + " muốn đổi sang khung giờ khác (ví dụ 20:00) để " + sp + " kiểm tra lại lịch trống giúp " + hon + " nhé ạ?",
                    0
            ));
            case "CREATE_BOOKING_SUCCESS", "CREATE_BOOKING_IDEMPOTENT_REPLAY" -> List.of(new FanpageBotResponse.ReplyBubble(
                    "Dạ " + sp + " đã đặt lịch thành công cho " + hon + " " + productDisplayWithPrice
                            + " vào lúc " + st.bookingTime() + " " + st.bookingDate()
                            + " ạ. Mã đặt lịch mô phỏng (MOCK): " + res.bookingId()
                            + ". Khi ghé cơ sở " + hon + " chỉ cần báo mã này tại quầy lễ tân nhé ạ.",
                    0
            ));
            case "CREATE_BOOKING_FAILURE" -> List.of(new FanpageBotResponse.ReplyBubble(
                    "Dạ hiện hệ thống đặt lịch mô phỏng đang gặp gián đoạn tạm thời ("
                            + (res.failureReason() != null ? res.failureReason() : "BOOKING_CREATION_FAILED")
                            + ") nên " + sp + " chưa thể tạo mã đặt lịch cho khung giờ " + st.bookingTime() + " " + st.bookingDate()
                            + " của " + hon + " ạ. " + capHon + " vui lòng thử xác nhận lại hoặc để " + sp
                            + " chuyển nhân sự hỗ trợ giữ lịch trực tiếp cho " + hon + " nhé ạ?",
                    0
            ));
            case "EXPLICIT_BOOKING_CANCEL" -> List.of(new FanpageBotResponse.ReplyBubble(
                    "Dạ vâng ạ, " + sp + " đã hủy yêu cầu đặt lịch theo ý " + hon + " rồi ạ. Khi nào " + hon
                            + " thu xếp được thời gian phù hợp và muốn đặt lịch lại thì cứ nhắn " + sp + " hỗ trợ bất cứ lúc nào nhé ạ.",
                    0
            ));
            case "HESITATION_ON_CONFIRMATION" -> {
                ConversationState.StructuredBookingState slotRef = st.selectedProductId() != null ? st : (priorState != null ? priorState : st);
                yield List.of(new FanpageBotResponse.ReplyBubble(
                        "Dạ vâng ạ, " + hon + " cứ kiểm tra lại lịch cá nhân thoải mái nhé ạ. Khung giờ "
                                + (slotRef.bookingTime() != null ? slotRef.bookingTime() + " " : "")
                                + (slotRef.bookingDate() != null ? slotRef.bookingDate() + " " : "")
                                + "cho " + productDisplayWithPrice + " vẫn đang chờ " + hon
                                + " xác nhận, khi nào chốt được " + hon + " nhắn lại giúp " + sp + " nhé ạ.",
                        0
                ));
            }
            case "DISABLED_GATEWAY_HOLD" -> List.of(new FanpageBotResponse.ReplyBubble(
                    "Dạ " + sp + " đã ghi nhận thông tin đặt lịch " + productDisplayWithPrice
                            + " vào lúc " + st.bookingTime() + " " + st.bookingDate() + " của " + hon
                            + " và đang chờ bộ phận điều phối kiểm tra lịch trống thực tế tại cơ sở để xác nhận chính thức với " + hon + " nhé ạ.",
                    0
            ));
            case "MULTI_PRODUCT_SELECTION_REQUIRED" -> {
                List<PackageCatalogService.PackageSummary> distinct = deduplicateNonAddonProducts(presentedPackagesInHistory);
                if (distinct.size() >= 2) {
                    String optionsList = distinct.stream()
                            .map(p -> p.canonicalLabel() + " (" + p.priceDisplay() + ")")
                            .reduce((a, b) -> a + " hay " + b)
                            .orElse("các gói đã tư vấn");
                    String timePart = (st.bookingTime() != null && st.bookingDate() != null)
                            ? (" cho khung giờ " + st.bookingTime() + " " + st.bookingDate())
                            : (st.bookingDate() != null ? (" vào " + st.bookingDate()) : "");
                    yield List.of(new FanpageBotResponse.ReplyBubble(
                            "Dạ để " + sp + " kiểm tra lịch chính xác" + timePart + ", " + hon + " muốn chọn "
                                    + optionsList + " ạ?",
                            0
                    ));
                }
                yield currentBubbles != null ? currentBubbles : List.of();
            }
            default -> currentBubbles != null ? currentBubbles : List.of();
        };
    }

    private static boolean isShortAffirmativeBookingNod(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String trimmed = message.strip();
        if (trimmed.contains("?") || isHesitationOrDeferralMessage(trimmed)) {
            return false;
        }
        if (isBareAcknowledgmentMessage(trimmed)) {
            return true;
        }
        return trimmed.toLowerCase(Locale.ROOT).matches(
                "(?isU)^\\s*(?:dạ\\s+|ừ\\s+|à\\s+|nghe\\s+)?(?:cũng\\s+được|tạm\\s+được|cũng\\s+ổn|được\\s+đấy|nhất\\s+trí|đồng\\s+ý|chốt\\s+nhé|ok\\s+nhé|ừ\\s+nhé|uk\\s+nhé)(?:\\s+(?:em|chị|anh|shop|ạ|nhé|nha|nhỉ))?\\s*[.!?~…]*\\s*$"
        );
    }

    private static PackageCatalogService.PackageSummary matchSingleInvitedProductInPrecedingBotTurn(
            String botText,
            List<PackageCatalogService.PackageSummary> distinctPresented,
            List<PackageCatalogService.PackageSummary> allCatalog,
            java.time.ZoneId safeZone,
            java.time.LocalDate referenceDate
    ) {
        if (botText == null || botText.isBlank() || isPauseOrDeferralBotReply(botText)) {
            return null;
        }
        boolean invitedProductOrTime = isInviteTrialOrAskTimeBotReply(botText)
                || isConcreteTimeSlotProposalBotReply(botText, safeZone, referenceDate);
        if (!invitedProductOrTime) {
            return null;
        }
        List<PackageCatalogService.PackageSummary> searchPool = (distinctPresented != null && !distinctPresented.isEmpty())
                ? distinctPresented
                : deduplicateNonAddonProducts(allCatalog);
        if (searchPool.isEmpty()) {
            return null;
        }
        PackageCatalogService.PackageSummary wholeTurnMatch = matchUniqueProductInInviteText(botText, searchPool, distinctPresented);
        if (wholeTurnMatch != null) {
            return wholeTurnMatch;
        }
        String trailingInviteQuestion = extractTrailingInviteSentence(botText);
        if (!trailingInviteQuestion.isEmpty()
                && !trailingInviteQuestion.equals(botText.strip())
                && (isInviteTrialOrAskTimeBotReply(trailingInviteQuestion)
                    || isConcreteTimeSlotProposalBotReply(trailingInviteQuestion, safeZone, referenceDate))) {
            return matchUniqueProductInInviteText(trailingInviteQuestion, searchPool, distinctPresented);
        }
        return null;
    }

    private static String extractTrailingInviteSentence(String botText) {
        if (botText == null || botText.isBlank()) {
            return "";
        }
        String[] parts = botText.strip().split("(?<=[.!?\\n])\\s+");
        for (int i = parts.length - 1; i >= 0; i--) {
            String candidate = parts[i].strip();
            if (!candidate.isEmpty()) {
                return candidate;
            }
        }
        return "";
    }

    private static PackageCatalogService.PackageSummary matchUniqueProductInInviteText(
            String text,
            List<PackageCatalogService.PackageSummary> searchPool,
            List<PackageCatalogService.PackageSummary> distinctPresented
    ) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String lowText = java.text.Normalizer.normalize(text.strip(), java.text.Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT);
        if (lowText.matches("(?isU).*\\b(phương\\s+án\\s+nào|gói\\s+nào|mẫu\\s+nào|khóa\\s+nào|dòng\\s+nào)\\b.*")) {
            return null;
        }
        Set<Long> botPrices = CleanAgentOutputRefereeAndSanitizer.extractPricesInVndFromText(text);
        if (!botPrices.isEmpty()) {
            List<PackageCatalogService.PackageSummary> priceMatched = searchPool.stream()
                    .filter(p -> p != null && botPrices.contains(p.priceAmountVnd()))
                    .toList();
            if (priceMatched.size() > 1) {
                return null;
            }
        }
        CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution botPkgRes =
                CleanAgentCatalogAndPromptBuilder.resolveQuotedReplyContext("", text, searchPool, distinctPresented);
        if (!botPkgRes.ambiguousMultiplePackages() && botPkgRes.quotedPackage() != null) {
            return botPkgRes.quotedPackage();
        }
        if (botPrices.size() == 1) {
            long singlePrice = botPrices.iterator().next();
            List<PackageCatalogService.PackageSummary> exactByPrice = searchPool.stream()
                    .filter(p -> p != null && p.priceAmountVnd() == singlePrice)
                    .toList();
            if (exactByPrice.size() == 1) {
                return exactByPrice.get(0);
            }
        }
        return null;
    }

    public static PackageCatalogService.PackageSummary matchExplicitProductSelectionInMessage(
            String message,
            List<PackageCatalogService.PackageSummary> presentedHistoryPackages,
            List<PackageCatalogService.PackageSummary> allCatalog,
            CleanAgentCatalogAndPromptBuilder.TieredCatalogResult tieredCatalog,
            CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution quotedReplyResolution
    ) {
        if (quotedReplyResolution != null
                && quotedReplyResolution.quotedPackage() != null
                && !quotedReplyResolution.ambiguousMultiplePackages()
                && !quotedReplyResolution.unresolvedReplyToMid()) {
            return quotedReplyResolution.quotedPackage();
        }
        if (message == null || message.isBlank()) {
            return null;
        }
        String normMsg = java.text.Normalizer.normalize(message.strip(), java.text.Normalizer.Form.NFC);
        String lowerMsg = normMsg.toLowerCase(Locale.ROOT);

        List<PackageCatalogService.PackageSummary> distinctPresented = deduplicateNonAddonProducts(presentedHistoryPackages);
        List<PackageCatalogService.PackageSummary> distinctAll = deduplicateNonAddonProducts(allCatalog);
        List<List<PackageCatalogService.PackageSummary>> searchPools = new ArrayList<>();
        if (!distinctPresented.isEmpty()) {
            searchPools.add(distinctPresented);
        }
        if (!distinctAll.isEmpty()) {
            searchPools.add(distinctAll);
        }

        // 1. Khớp trực tiếp mã SKU / ProductId trong câu nói của khách (ví dụ: DS02, NS01, G8080, IELTS24)
        for (List<PackageCatalogService.PackageSummary> pool : searchPools) {
            List<PackageCatalogService.PackageSummary> codeMatches = new ArrayList<>();
            for (PackageCatalogService.PackageSummary p : pool) {
                if (p == null) continue;
                String pid = p.productId();
                if (pid != null && !pid.isBlank() && !pid.startsWith("PKG_")) {
                    String escaped = Pattern.quote(pid.strip().toLowerCase(Locale.ROOT));
                    String compact = Pattern.quote(pid.strip().toLowerCase(Locale.ROOT).replaceAll("[-_]", ""));
                    if (lowerMsg.matches("(?isU).*\\b" + escaped + "\\b.*")
                            || lowerMsg.matches("(?isU).*\\b" + compact + "\\b.*")) {
                        codeMatches.add(p);
                    }
                }
            }
            if (codeMatches.size() == 1) {
                return codeMatches.get(0);
            }
        }

        // 2. Khớp theo mức giá niêm yết khách nêu tường minh trong câu đặt lịch (ví dụ: nêu đúng con số giá của mục muốn chọn)
        Set<Long> mentionedPrices = CleanAgentOutputRefereeAndSanitizer.extractPricesInVndFromText(normMsg);
        if (!mentionedPrices.isEmpty()) {
            for (List<PackageCatalogService.PackageSummary> pool : searchPools) {
                List<PackageCatalogService.PackageSummary> priceMatches = new ArrayList<>();
                for (PackageCatalogService.PackageSummary p : pool) {
                    if (p != null && mentionedPrices.contains(p.priceAmountVnd())) {
                        priceMatches.add(p);
                    }
                }
                if (priceMatches.size() == 1) {
                    return priceMatches.get(0);
                }
            }
        }

        // 3. Khớp theo tên chuẩn / thông số định danh duy nhất (ví dụ: "mẫu 80x80", "khóa 24 buổi", "gói dưỡng sinh cơ bản")
        for (List<PackageCatalogService.PackageSummary> pool : searchPools) {
            CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution textMatch =
                    CleanAgentCatalogAndPromptBuilder.resolveQuotedReplyContext("", normMsg, pool, distinctPresented);
            if (!textMatch.ambiguousMultiplePackages() && textMatch.quotedPackage() != null) {
                return textMatch.quotedPackage();
            }
            List<PackageCatalogService.PackageSummary> specMatches = new ArrayList<>();
            for (PackageCatalogService.PackageSummary p : pool) {
                if (p == null || p.normalized() == null || p.normalized().specifications() == null) continue;
                for (PackageCatalogService.SpecificationAttr spec : p.normalized().specifications()) {
                    if (spec == null || spec.rawValue() == null || spec.rawValue().isBlank()) continue;
                    String sLow = spec.rawValue().strip().toLowerCase(Locale.ROOT);
                    String sNoCm = sLow.replaceAll("\\s*cm\\b", "").strip();
                    if ((sLow.length() >= 3 && lowerMsg.contains(sLow))
                            || (sNoCm.matches("\\d+\\s*[x×]\\s*\\d+") && lowerMsg.contains(sNoCm))) {
                        specMatches.add(p);
                        break;
                    }
                }
            }
            if (specMatches.size() == 1) {
                return specMatches.get(0);
            }
        }

        // 4. Khách chỉ định rõ nhóm tiết kiệm ("đặt gói tiết kiệm", "lấy gói rẻ hơn") khi chỉ có 1 phương án tiết kiệm phù hợp
        List<PackageCatalogService.PackageSummary> ecoCandidates = (tieredCatalog != null && tieredCatalog.economicalAlternatives() != null)
                ? deduplicateNonAddonProducts(tieredCatalog.economicalAlternatives())
                : List.of();
        if (ecoCandidates.size() == 1
                && CleanAgentCatalogAndPromptBuilder.customerExplicitlyAskedForEconomicalGroup(normMsg, ecoCandidates)) {
            return ecoCandidates.get(0);
        }
        return null;
    }

    private static List<PackageCatalogService.PackageSummary> deduplicateNonAddonProducts(
            List<PackageCatalogService.PackageSummary> list
    ) {
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<PackageCatalogService.PackageSummary> out = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        for (PackageCatalogService.PackageSummary p : list) {
            if (p == null || p.isAddon()) continue;
            String key = p.productId() != null ? p.productId().toUpperCase(Locale.ROOT) : (p.canonicalName() + ":" + p.priceAmountVnd());
            if (seenIds.add(key)) {
                out.add(p);
            }
        }
        return out;
    }

    private static PackageCatalogService.PackageSummary findProductByIdInCatalog(
            String productId,
            List<PackageCatalogService.PackageSummary> primaryPool,
            List<PackageCatalogService.PackageSummary> fallbackPool
    ) {
        if (productId == null || productId.isBlank()) {
            return null;
        }
        String norm = productId.strip().toUpperCase(Locale.ROOT).replaceAll("[-_]", "");
        if (primaryPool != null) {
            for (PackageCatalogService.PackageSummary p : primaryPool) {
                if (p == null) continue;
                String pid = p.productId() != null ? p.productId().strip().toUpperCase(Locale.ROOT).replaceAll("[-_]", "") : "";
                if (norm.equals(pid)) {
                    return p;
                }
            }
        }
        if (fallbackPool != null) {
            for (PackageCatalogService.PackageSummary p : fallbackPool) {
                if (p == null) continue;
                String pid = p.productId() != null ? p.productId().strip().toUpperCase(Locale.ROOT).replaceAll("[-_]", "") : "";
                if (norm.equals(pid)) {
                    return p;
                }
            }
        }
        return null;
    }

    public static boolean isPauseOrDeferralBotReply(String botText) {
        if (botText == null || botText.isBlank()) {
            return false;
        }
        String trimmed = botText.strip();
        if (CleanAgentOutputRefereeAndSanitizer.BOOKING_TIME_QUESTION_PHRASE_PATTERN.matcher(trimmed).matches()) {
            return false;
        }
        return PAUSE_OR_DEFERRAL_BOT_REPLY_CLEAN_PATTERN.matcher(trimmed).matches();
    }

    public static boolean isOfferPackageDetailsBotReply(String botText) {
        if (botText == null || botText.isBlank() || isPauseOrDeferralBotReply(botText)) {
            return false;
        }
        return OFFER_PACKAGE_DETAILS_BOT_REPLY_CLEAN_PATTERN.matcher(botText.strip()).matches();
    }

    public static boolean isInviteTrialOrAskTimeBotReply(String botText) {
        if (botText == null || botText.isBlank() || isPauseOrDeferralBotReply(botText)) {
            return false;
        }
        String trimmed = botText.strip();
        if (INVITE_TRIAL_OR_ASK_TIME_BOT_REPLY_CLEAN_PATTERN.matcher(trimmed).matches()) {
            return true;
        }
        if (CleanAgentOutputRefereeAndSanitizer.hasQuotedPriceInText(trimmed)
                && SINGLE_PRODUCT_FIT_CONFIRMATION_QUESTION_CLEAN_PATTERN.matcher(trimmed).matches()) {
            String trailingQuestion = extractTrailingInviteSentence(trimmed).toLowerCase(Locale.ROOT);
            return !trailingQuestion.matches("(?isU).*\\bhay\\b.*");
        }
        return false;
    }

    public static boolean isConcreteTimeSlotProposalBotReply(
            String botText,
            java.time.ZoneId pageZone,
            java.time.LocalDate referenceDate
    ) {
        if (botText == null || botText.isBlank() || isPauseOrDeferralBotReply(botText)) {
            return false;
        }
        String trimmed = botText.strip();
        if (AgentBrainService.isOperationalAnswerContext(trimmed, List.of())) {
            return false;
        }
        if (!CONCRETE_TIME_PROPOSAL_CUE_CLEAN_PATTERN.matcher(trimmed).matches()) {
            return false;
        }
        java.time.ZoneId safeZone = pageZone != null ? pageZone : java.time.ZoneId.of("Asia/Ho_Chi_Minh");
        ConversationState.ParsedBookingDateTime dt = ConversationState.parseBookingDateTime(
                trimmed, null, null, safeZone, referenceDate
        );
        return dt.bookingTime() != null && !dt.bookingTime().isBlank();
    }

    public static boolean isProactivePriceOrProductRequest(
            String message,
            List<PackageCatalogService.PackageSummary> allCatalog
    ) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String trimmed = message.strip();
        if (isNormalPriceOrDetailInquiryMessage(trimmed)
                || isExplicitDownsellOrLowBudgetRequest(trimmed, null)
                || hasProactiveBookingOrPackageSelectionInMessage(trimmed)
                || PROACTIVE_PRICE_OR_PRODUCT_REQUEST_CLEAN_PATTERN.matcher(trimmed).matches()) {
            return true;
        }
        return allCatalog != null
                && !allCatalog.isEmpty()
                && matchExplicitProductSelectionInMessage(trimmed, List.of(), allCatalog, null, null) != null;
    }

    public static boolean isProductComparisonQuestion(String message) {
        return message != null
                && !message.isBlank()
                && PRODUCT_COMPARISON_QUESTION_CLEAN_PATTERN.matcher(message.strip()).matches();
    }

    public static boolean wasPriorCustomerTurnHesitation(List<FanpageBotRequest.ConversationTurn> history) {
        if (history == null || history.isEmpty()) {
            return false;
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            FanpageBotRequest.ConversationTurn t = history.get(i);
            if (t == null || t.content() == null || t.content().isBlank()) continue;
            if (!"assistant".equalsIgnoreCase(t.role()) && !"bot".equalsIgnoreCase(t.role())) {
                return isHesitationOrDeferralMessage(t.content());
            }
        }
        return false;
    }

    public static String inferPrecedingBotSpeechAct(
            CustomerInsightDto customerInsight,
            String prevBotTurnText,
            List<FanpageBotRequest.ConversationTurn> history,
            String bookingStatus
    ) {
        return inferPrecedingBotSpeechAct(customerInsight, prevBotTurnText, history, bookingStatus, null);
    }

    public static String inferPrecedingBotSpeechAct(
            CustomerInsightDto customerInsight,
            String prevBotTurnText,
            List<FanpageBotRequest.ConversationTurn> history,
            String bookingStatus,
            String recalledSpeechAct
    ) {
        if ("CONFIRMED".equalsIgnoreCase(bookingStatus)) {
            return SPEECH_ACT_BOOKING_CONFIRMED;
        }
        String botText = (prevBotTurnText != null && !prevBotTurnText.isBlank())
                ? prevBotTurnText
                : resolveImmediatelyPrecedingBotText(history, null);
        if (isPauseOrDeferralBotReply(botText) || wasPriorCustomerTurnHesitation(history)) {
            return SPEECH_ACT_RESPECTED_PAUSE;
        }
        if (AgentBrainService.isOperationalAnswerContext(botText, history)) {
            return SPEECH_ACT_ANSWERED_OPERATIONAL_INFO;
        }
        if (isOfferPackageDetailsBotReply(botText)) {
            return SPEECH_ACT_OFFERED_DETAILS;
        }
        if (isInviteTrialOrAskTimeBotReply(botText)) {
            return SPEECH_ACT_INVITED_TRIAL_OR_TIME;
        }
        if (customerInsight != null
                && customerInsight.lastActualSentSpeechAct() != null
                && !customerInsight.lastActualSentSpeechAct().isBlank()) {
            return customerInsight.lastActualSentSpeechAct().strip();
        }
        if (recalledSpeechAct != null && !recalledSpeechAct.isBlank()) {
            return recalledSpeechAct.strip();
        }
        return "";
    }

    public static String resolveCleanFunnelStage(
            List<String> currentTurnSignals,
            String customerMessage,
            int priceObjectionCount,
            boolean packageInterestAccepted,
            CleanAgentCatalogAndPromptBuilder.TieredCatalogResult tieredCatalog,
            List<FanpageBotRequest.ConversationTurn> history
    ) {
        return resolveCleanFunnelStage(
                currentTurnSignals,
                customerMessage,
                priceObjectionCount,
                priceObjectionCount >= 1 && priceObjectionCount < 2,
                priceObjectionCount >= 2,
                packageInterestAccepted,
                tieredCatalog,
                history
        );
    }

    public static String resolveCleanFunnelStage(
            List<String> currentTurnSignals,
            String customerMessage,
            int priceObjectionCount,
            boolean inPriceObjectionContext,
            boolean hasPresentedDownsell,
            boolean packageInterestAccepted,
            CleanAgentCatalogAndPromptBuilder.TieredCatalogResult tieredCatalog,
            List<FanpageBotRequest.ConversationTurn> history
    ) {
        List<String> safeSignals = currentTurnSignals != null ? currentTurnSignals : List.of();
        boolean isMetaCommunicationTurn = (safeSignals.contains("STYLE_OR_REPETITION_FEEDBACK")
                || safeSignals.contains("CHALLENGE_OR_QUESTION_BOT_ASSUMPTION"))
                && !safeSignals.contains("ENTITY_MISMATCH_CORRECTION");
        if (isMetaCommunicationTurn) {
            return "STAGE_5_META_COMMUNICATION_REPAIR";
        }
        if (safeSignals.contains("POST_BOOKING_CLOSING_ACK")) {
            return "STAGE_4B_POST_BOOKING_CONFIRMED_CLOSING";
        }
        if (safeSignals.contains("POLITE_CLOSING_ACKNOWLEDGMENT")) {
            return "STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE";
        }
        if (safeSignals.contains("ACKNOWLEDGED_OPERATIONAL_INFO")
                || safeSignals.contains("ACKNOWLEDGED_GENERAL_INFO")) {
            return "STAGE_1_DISCOVERY_AND_FIRST_QUOTE";
        }
        if (safeSignals.contains("POSITIVE_CONFIRMATION_SIGNAL")) {
            return "STAGE_4_POSITIVE_TRIAL_OR_BOOKING";
        }

        boolean hasEconomicalAlt = tieredCatalog != null
                && tieredCatalog.economicalAlternatives() != null
                && !tieredCatalog.economicalAlternatives().isEmpty();
        boolean downsellAlreadyOffered = hasPresentedDownsell
                || (hasEconomicalAlt && CleanAgentCatalogAndPromptBuilder.hasPackageMentionedInBotHistory(
                        history, tieredCatalog.economicalAlternatives()));

        AgentBrainService.ShortUtteranceResolution shortRes = AgentBrainService.decodeContextualShortUtteranceStatic(
                customerMessage,
                resolveImmediatelyPrecedingBotText(history, null),
                null,
                history,
                priceObjectionCount,
                inPriceObjectionContext,
                downsellAlreadyOffered,
                hasEconomicalAlt,
                "NONE"
        );
        if (shortRes.isContextualShortUtterance() && !shortRes.recommendedStage().isEmpty()) {
            if ("ACKNOWLEDGED_OPERATIONAL_INFO".equals(shortRes.decodedMeaning())
                    || "ACKNOWLEDGED_GENERAL_INFO_WARM_BRIDGE".equals(shortRes.decodedMeaning())) {
                return "STAGE_1_DISCOVERY_AND_FIRST_QUOTE";
            }
            if ("POSITIVE_CONFIRMATION_SIGNAL".equals(shortRes.decodedMeaning())) {
                return "STAGE_4_POSITIVE_TRIAL_OR_BOOKING";
            }
            if ("LUKEWARM_POST_PRICE_QUOTE_EVALUATION".equals(shortRes.decodedMeaning())) {
                return (inPriceObjectionContext && !downsellAlreadyOffered && hasEconomicalAlt)
                        ? "STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL"
                        : "STAGE_1_DISCOVERY_AND_FIRST_QUOTE";
            }
            if ("PRICE_HESITATION_AFTER_VALUE_DEFENSE".equals(shortRes.decodedMeaning())
                    || "PRICE_HESITATION_PASSIVE_ACK_AFTER_VALUE_DEFENSE".equals(shortRes.decodedMeaning())
                    || "PRICE_CONTEXT_HESITATION_WITH_AVAILABLE_ECONOMICAL_OPTION".equals(shortRes.decodedMeaning())) {
                return (!downsellAlreadyOffered && hasEconomicalAlt)
                        ? "STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL"
                        : "STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE";
            }
            if ("EXPLICIT_POLITE_DEFERRAL_PAUSE".equals(shortRes.decodedMeaning())
                    || "PRICE_HESITATION_NO_UNPRESENTED_ECONOMICAL_OPTION".equals(shortRes.decodedMeaning())
                    || "POLITE_CLOSING_ACKNOWLEDGMENT".equals(shortRes.decodedMeaning())) {
                return "STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE";
            }
        }

        boolean isExplicitDownsell = isExplicitDownsellOrLowBudgetRequest(customerMessage, null)
                || safeSignals.contains("PRICE_OBJECTION_REPEAT_OR_DOWNSELL_REQUEST");
        boolean hasPriceObjectionSignal = isExplicitDownsell
                || safeSignals.contains("PRICE_OBJECTION")
                || safeSignals.contains("PRICE_OBJECTION_FIRST_TIME")
                || safeSignals.contains("PRICE_OBJECTION_REPEAT_OR_DOWNSELL_REQUEST");
        boolean isServiceDetailQuestionTurn = (safeSignals.contains("SERVICE_OR_UNIT_DETAIL_QUESTION")
                || safeSignals.contains("OPERATIONAL_OR_LOGISTICS_QUESTION"))
                && !hasPriceObjectionSignal
                && !safeSignals.contains("PACKAGE_ACCEPTANCE_OR_BOOKING_PROGRESS")
                && !safeSignals.contains("POSITIVE_INTEREST_READY_FOR_TRIAL_INVITE");
        boolean currentTurnHasPriceObjection = !isServiceDetailQuestionTurn && hasPriceObjectionSignal;
        boolean isHesitationTurn = !currentTurnHasPriceObjection
                && (safeSignals.contains("HESITATION_OR_DEFERRAL")
                    || safeSignals.contains("PRICE_HESITATION_AFTER_VALUE_DEFENSE")
                    || isHesitationOrDeferralMessage(customerMessage));
        boolean isPureBusyOrStop = isHesitationTurn && isPureBusyOrStopDeferralMessage(customerMessage);
        if (isHesitationTurn) {
            if (!isPureBusyOrStop
                    && inPriceObjectionContext
                    && !downsellAlreadyOffered
                    && hasEconomicalAlt) {
                return "STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL";
            }
            return "STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE";
        }
        if (isServiceDetailQuestionTurn || safeSignals.contains("LUKEWARM_POST_QUOTE_CHECKIN")) {
            return "STAGE_1_DISCOVERY_AND_FIRST_QUOTE";
        }
        boolean isBookingOrPositiveTrialTurn = !currentTurnHasPriceObjection
                && (packageInterestAccepted
                    || safeSignals.contains("POSITIVE_INTEREST_READY_FOR_TRIAL_INVITE")
                    || safeSignals.contains("PACKAGE_ACCEPTANCE_OR_BOOKING_PROGRESS"));
        if (isBookingOrPositiveTrialTurn) {
            return "STAGE_4_POSITIVE_TRIAL_OR_BOOKING";
        }
        if (safeSignals.contains("PRICE_OBJECTION_FIRST_TIME") && !isExplicitDownsell) {
            return "STAGE_2_FIRST_PRICE_OBJECTION_HOLD_VALUE";
        }
        if (safeSignals.contains("PRICE_OBJECTION_REPEAT_OR_DOWNSELL_REQUEST")
                || isExplicitDownsell
                || (currentTurnHasPriceObjection && priceObjectionCount >= 2)) {
            return "STAGE_3_DOWNSELL_OR_REPEAT_OBJECTION";
        }
        return "STAGE_1_DISCOVERY_AND_FIRST_QUOTE";
    }

    public static String resolveCleanFunnelStage(
            boolean isMetaCommunicationTurn,
            boolean isPureDeferralOrBusyTurn,
            boolean isPriceContextHesitationTurn,
            boolean isBookingOrPositiveTrialTurn,
            boolean isFirstPriceObjectionHoldTurn,
            boolean isDownsellOrRepeatObjectionTurn
    ) {
        if (isMetaCommunicationTurn) {
            return "STAGE_5_META_COMMUNICATION_REPAIR";
        }
        if (isPureDeferralOrBusyTurn) {
            return "STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE";
        }
        if (isPriceContextHesitationTurn) {
            return "STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL";
        }
        if (isBookingOrPositiveTrialTurn) {
            return "STAGE_4_POSITIVE_TRIAL_OR_BOOKING";
        }
        if (isFirstPriceObjectionHoldTurn) {
            return "STAGE_2_FIRST_PRICE_OBJECTION_HOLD_VALUE";
        }
        if (isDownsellOrRepeatObjectionTurn) {
            return "STAGE_3_DOWNSELL_OR_REPEAT_OBJECTION";
        }
        return "STAGE_1_DISCOVERY_AND_FIRST_QUOTE";
    }

    public static boolean hasPackagePriceMentionedInText(
            String message,
            List<PackageCatalogService.PackageSummary> packages
    ) {
        if (message == null || message.isBlank() || packages == null || packages.isEmpty()) {
            return false;
        }
        Matcher pm = BubbleTextSanitizer.PRICE_PATTERN.matcher(message);
        while (pm.find()) {
            java.math.BigDecimal bd = ConversationState.parsePriceToBigDecimal(pm.group());
            if (bd != null) {
                long vnd = bd.longValue();
                for (PackageCatalogService.PackageSummary p : packages) {
                    if (p != null && p.priceVnd() == vnd) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static final String VALUE_HOLD_PRICE_OBJECTION_SKILL_DISPLAY_NAME =
            "Đồng cảm chi phí & bảo vệ giá trị liệu trình (Value Hold)";

    public static AgentSkillDto sanitizeSkillDisplayNameForUi(AgentSkillDto skill) {
        if (skill == null || skill.name() == null) {
            return skill;
        }
        String cleanedName = skill.name().replaceFirst("^(?:🎯\\s*)+", "").strip();
        String codeLower = skill.code() != null ? skill.code().strip().toLowerCase(Locale.ROOT) : "";
        if ("spa_objection_value_trial".equals(codeLower)
                || cleanedName.equalsIgnoreCase("Xử lý phản đối giá lần 1 bám sát Catalog đã xác minh (Tối đa 2 bong bóng)")
                || cleanedName.contains("Xử lý phản đối giá lần 1 bám sát Catalog đã xác minh")) {
            cleanedName = VALUE_HOLD_PRICE_OBJECTION_SKILL_DISPLAY_NAME;
        }
        if (cleanedName.equals(skill.name())) {
            return skill;
        }
        return new AgentSkillDto(
                skill.id(),
                skill.code(),
                cleanedName,
                skill.category(),
                skill.description(),
                skill.triggerSignals(),
                skill.tacticalInstructions(),
                skill.goodExample(),
                skill.antiPattern(),
                skill.priority(),
                skill.active(),
                skill.pageId(),
                skill.appliedCount(),
                skill.conversionCount(),
                skill.confidenceScore(),
                skill.createdAt(),
                skill.updatedAt(),
                skill.governanceAction(),
                skill.governanceMessage(),
                skill.conflictDetected(),
                skill.conflictingSkill(),
                skill.conflictReason(),
                skill.recommendedAction()
        );
    }

    public static boolean isBookingOrClosingSkill(AgentSkillDto skill) {
        if (skill == null) {
            return false;
        }
        if (skill.category() != null && "CLOSING".equalsIgnoreCase(skill.category().strip())) {
            return true;
        }
        String codeLower = skill.code() != null ? skill.code().strip().toLowerCase(Locale.ROOT) : "";
        if (Set.of(
                "warm_nod_to_gentle_scheduling",
                "booking_careful_preparation",
                "spa_trial_acceptance_booking",
                "spa_booking_advance_after_ack",
                "booking_soft_confirm",
                "sales_micro_commitment_close",
                "sales_appointment_hook_close"
        ).contains(codeLower)
                || codeLower.contains("booking")
                || codeLower.contains("closing")
                || codeLower.contains("scheduling")) {
            return true;
        }
        String nameLower = skill.name() != null ? skill.name().strip().toLowerCase(Locale.ROOT) : "";
        return nameLower.contains("chốt khung giờ")
                || nameLower.contains("đặt lịch")
                || nameLower.contains("giữ lịch")
                || nameLower.contains("khung giờ thuận tiện")
                || nameLower.contains("trial acceptance");
    }

    private static boolean isWarmNodSchedulingSkill(AgentSkillDto skill) {
        if (skill == null || skill.code() == null) {
            return false;
        }
        String codeLower = skill.code().strip().toLowerCase(Locale.ROOT);
        return "warm_nod_to_gentle_scheduling".equals(codeLower);
    }

    private static boolean isBookingCarefulPreparationSkill(AgentSkillDto skill) {
        if (skill == null || skill.code() == null) {
            return false;
        }
        String codeLower = skill.code().strip().toLowerCase(Locale.ROOT);
        return "booking_careful_preparation".equals(codeLower);
    }

    private static boolean isSoftEconomicalBridgeOrCompareSkill(AgentSkillDto skill) {
        if (skill == null || skill.code() == null) {
            return false;
        }
        String codeLower = skill.code().strip().toLowerCase(Locale.ROOT);
        return "soft_economical_bridge_and_compare".equals(codeLower);
    }

    private static boolean isLukewarmMoodBoostSkill(AgentSkillDto skill) {
        if (skill == null) {
            return false;
        }
        String codeLower = skill.code() != null ? skill.code().strip().toLowerCase(Locale.ROOT) : "";
        String nameLower = skill.name() != null ? skill.name().strip().toLowerCase(Locale.ROOT) : "";
        return "spa_lukewarm_mood_boost".equals(codeLower)
                || "lukewarm_mood_boost".equals(codeLower)
                || nameLower.contains("lukewarm");
    }

    private static boolean isGracefulExitSkill(AgentSkillDto skill) {
        if (skill == null) {
            return false;
        }
        String codeLower = skill.code() != null ? skill.code().strip().toLowerCase(Locale.ROOT) : "";
        String nameLower = skill.name() != null ? skill.name().strip().toLowerCase(Locale.ROOT) : "";
        return "graceful_pause_keep_warmth".equals(codeLower)
                || "spa_graceful_exit_after_rejection".equals(codeLower)
                || "graceful_exit_after_rejection".equals(codeLower)
                || "spa_stage3_graceful_exit".equals(codeLower)
                || nameLower.contains("graceful exit");
    }

    private static boolean isPriceObjectionEmpathySkill(AgentSkillDto skill) {
        if (skill == null) {
            return false;
        }
        String codeLower = skill.code() != null ? skill.code().strip().toLowerCase(Locale.ROOT) : "";
        String nameLower = skill.name() != null ? skill.name().strip().toLowerCase(Locale.ROOT) : "";
        return "price_objection_sensory_value".equals(codeLower)
                || "price_objection_soft_anchor".equals(codeLower)
                || "spa_objection_value_trial".equals(codeLower)
                || "spa_objection_dynamic_downsell".equals(codeLower)
                || nameLower.contains("đồng cảm chi phí")
                || nameLower.contains("bảo vệ giá trị liệu trình")
                || nameLower.contains("value hold")
                || nameLower.contains("neo giá trị");
    }

    private static boolean isDiscoveryOrSymptomSkill(AgentSkillDto skill) {
        if (skill == null) {
            return false;
        }
        String cat = skill.category() != null ? skill.category().strip().toUpperCase(Locale.ROOT) : "";
        if ("DISCOVERY".equals(cat)) {
            return true;
        }
        String codeLower = skill.code() != null ? skill.code().strip().toLowerCase(Locale.ROOT) : "";
        String nameLower = skill.name() != null ? skill.name().strip().toLowerCase(Locale.ROOT) : "";
        return "discovery_empathy_and_scope".equals(codeLower)
                || "spa_condition_first_discovery".equals(codeLower)
                || "discovery_ask_one_need".equals(codeLower)
                || "spa_cautious_symptom_clarification".equals(codeLower)
                || codeLower.contains("discovery")
                || nameLower.contains("condition-first")
                || nameLower.contains("khám phá tình trạng");
    }

    private static List<AgentSkillDto> capSingleFocalSkill(List<AgentSkillDto> skills) {
        if (skills == null || skills.isEmpty()) {
            return List.of();
        }
        return skills.size() > 1 ? new ArrayList<>(skills.subList(0, 1)) : skills;
    }

    private static AgentSkillDto findFirstMatchingSkill(
            List<AgentSkillDto> current,
            List<AgentSkillDto> candidates,
            java.util.function.Predicate<AgentSkillDto> predicate
    ) {
        if (current != null) {
            for (AgentSkillDto s : current) {
                if (s != null && predicate.test(s)) {
                    return s;
                }
            }
        }
        if (candidates != null) {
            for (AgentSkillDto c : candidates) {
                if (c != null && predicate.test(c)) {
                    return c;
                }
            }
        }
        return null;
    }

    public static List<AgentSkillDto> alignCleanAgentActiveSkills(
            List<AgentSkillDto> activeSkills,
            List<AgentSkillDto> candidateSkills,
            boolean isHesitationOrPoliteClosing,
            boolean isPostBookingClosingAck,
            boolean isPureDetailOrFaqTurn
    ) {
        return alignCleanAgentActiveSkills(
                activeSkills,
                candidateSkills,
                null,
                null,
                true,
                null,
                isHesitationOrPoliteClosing,
                isPostBookingClosingAck,
                isPureDetailOrFaqTurn
        );
    }

    public static List<AgentSkillDto> alignCleanAgentActiveSkills(
            List<AgentSkillDto> activeSkills,
            List<AgentSkillDto> candidateSkills,
            String finalStage,
            boolean isHesitationOrPoliteClosing,
            boolean isPostBookingClosingAck,
            boolean isPureDetailOrFaqTurn
    ) {
        return alignCleanAgentActiveSkills(
                activeSkills,
                candidateSkills,
                finalStage,
                null,
                true,
                null,
                isHesitationOrPoliteClosing,
                isPostBookingClosingAck,
                isPureDetailOrFaqTurn
        );
    }

    public static List<AgentSkillDto> alignCleanAgentActiveSkills(
            List<AgentSkillDto> activeSkills,
            List<AgentSkillDto> candidateSkills,
            String finalStage,
            String bookingFunnelStatus,
            boolean conditionClarified,
            String customerMessage,
            boolean isHesitationOrPoliteClosing,
            boolean isPostBookingClosingAck,
            boolean isPureDetailOrFaqTurn
    ) {
        List<AgentSkillDto> current = new ArrayList<>();
        if (activeSkills != null) {
            for (AgentSkillDto s : activeSkills) {
                if (s != null) {
                    current.add(sanitizeSkillDisplayNameForUi(s));
                }
            }
        }
        List<AgentSkillDto> sanitizedCandidates = new ArrayList<>();
        if (candidateSkills != null) {
            for (AgentSkillDto c : candidateSkills) {
                if (c != null) {
                    sanitizedCandidates.add(sanitizeSkillDisplayNameForUi(c));
                }
            }
        }

        if ("HUMAN_HANDOFF".equalsIgnoreCase(finalStage)
                || "COMPLAINT_OR_DISTRESS".equalsIgnoreCase(finalStage)
                || "STAGE_5_META_COMMUNICATION_REPAIR".equalsIgnoreCase(finalStage)) {
            current.removeIf(s -> isBookingOrClosingSkill(s)
                    || isDiscoveryOrSymptomSkill(s)
                    || isLukewarmMoodBoostSkill(s)
                    || isPriceObjectionEmpathySkill(s)
                    || isSoftEconomicalBridgeOrCompareSkill(s)
                    || isGracefulExitSkill(s)
                    || (s.category() != null && ("SALES".equalsIgnoreCase(s.category()) || "CLOSING".equalsIgnoreCase(s.category()) || "DISCOVERY".equalsIgnoreCase(s.category()))));
            return capSingleFocalSkill(current);
        }

        // 1. STAGE_2: Chê đắt lần đầu -> ưu tiên price_objection_sensory_value (1 skill trọng tâm duy nhất)
        if ("STAGE_2_FIRST_PRICE_OBJECTION_HOLD_VALUE".equals(finalStage)) {
            current.removeIf(s -> isGracefulExitSkill(s)
                    || isLukewarmMoodBoostSkill(s)
                    || isBookingOrClosingSkill(s)
                    || isDiscoveryOrSymptomSkill(s)
                    || isSoftEconomicalBridgeOrCompareSkill(s));
            AgentSkillDto matched = findFirstMatchingSkill(current, sanitizedCandidates, CleanAgentOrchestratorService::isPriceObjectionEmpathySkill);
            if (matched != null) {
                return List.of(matched);
            }
            return List.of(new AgentSkillDto(
                    null,
                    "price_objection_soft_anchor",
                    VALUE_HOLD_PRICE_OBJECTION_SKILL_DISPLAY_NAME,
                    "OBJECTION",
                    "Hóa giải khi khách chê đắt lần đầu bằng công thức Đồng cảm - Bảo vệ giá trị liệu trình - Đổi góc nhìn.",
                    "đắt quá, cao nhỉ, giá căng, hơi mắc, gần 1 ngày công",
                    "Đồng cảm tự nhiên, làm nổi bật giá trị trọn gói và mời khách trải nghiệm thử 1 buổi.",
                    "Dạ mức giá này đã bao gồm trọn gói chuyên viên tay nghề cao chăm sóc chuyên sâu ạ.",
                    "Tự động hạ giá ngay ở lần đầu khách cảm thán giá.",
                    96,
                    true,
                    null,
                    null,
                    null
            ));
        }

        // 2. STAGE_3 (Downsell / chê đắt lần 2): ưu tiên soft_economical_bridge_and_compare, fallback về priceObjectionEmpathySkill
        if ("STAGE_3_DOWNSELL_OR_REPEAT_OBJECTION".equals(finalStage)) {
            AgentSkillDto bridgeSkill = findFirstMatchingSkill(current, sanitizedCandidates, CleanAgentOrchestratorService::isSoftEconomicalBridgeOrCompareSkill);
            if (bridgeSkill != null) {
                return List.of(bridgeSkill);
            }
            AgentSkillDto fallbackEmpathy = findFirstMatchingSkill(current, sanitizedCandidates, CleanAgentOrchestratorService::isPriceObjectionEmpathySkill);
            if (fallbackEmpathy != null) {
                return List.of(fallbackEmpathy);
            }
            return List.of(new AgentSkillDto(
                    null,
                    "price_objection_soft_anchor",
                    VALUE_HOLD_PRICE_OBJECTION_SKILL_DISPLAY_NAME,
                    "OBJECTION",
                    "Đồng cảm chi phí và giới thiệu phương án tiết kiệm phù hợp nhu cầu.",
                    "đắt quá, có gói rẻ hơn không",
                    "Giới thiệu phương án tiết kiệm phù hợp phạm vi nhu cầu mà không dìm hàng gói nhỏ.",
                    "Dạ bên em có phương án tiết kiệm hơn tập trung đúng phạm vi nhu cầu của mình ạ.",
                    "Chê bai phương án tiết kiệm chỉ làm qua loa.",
                    96,
                    true,
                    null,
                    null,
                    null
            ));
        }

        // 3. STAGE_4 / STAGE_4B: Đặt lịch / gật đầu đồng thuận -> phân biệt warm_nod_to_gentle_scheduling vs booking_careful_preparation
        if ("STAGE_4_POSITIVE_TRIAL_OR_BOOKING".equals(finalStage)
                || "STAGE_4B_POST_BOOKING_CONFIRMED_CLOSING".equals(finalStage)
                || isPostBookingClosingAck) {
            current.removeIf(s -> isGracefulExitSkill(s) || isLukewarmMoodBoostSkill(s));
            boolean isPreparationOrConfirmPhase = isPostBookingClosingAck
                    || "STAGE_4B_POST_BOOKING_CONFIRMED_CLOSING".equals(finalStage)
                    || "AWAITING_AVAILABILITY_CHECK".equalsIgnoreCase(bookingFunnelStatus)
                    || "AWAITING_CUSTOMER_CONFIRMATION".equalsIgnoreCase(bookingFunnelStatus)
                    || "CONFIRMED".equalsIgnoreCase(bookingFunnelStatus);
            if (isPreparationOrConfirmPhase) {
                AgentSkillDto prepSkill = findFirstMatchingSkill(current, sanitizedCandidates, CleanAgentOrchestratorService::isBookingCarefulPreparationSkill);
                if (prepSkill != null) {
                    return List.of(prepSkill);
                }
            } else {
                AgentSkillDto nodSkill = findFirstMatchingSkill(current, sanitizedCandidates, CleanAgentOrchestratorService::isWarmNodSchedulingSkill);
                if (nodSkill != null) {
                    return List.of(nodSkill);
                }
            }
            if (!isPostBookingClosingAck) {
                AgentSkillDto anyBookingSkill = findFirstMatchingSkill(current, sanitizedCandidates, CleanAgentOrchestratorService::isBookingOrClosingSkill);
                if (anyBookingSkill != null) {
                    return List.of(anyBookingSkill);
                }
            }
            return capSingleFocalSkill(current);
        }

        // 4. STAGE_3B (Ngập ngừng vì giá -> cầu nối mềm sang phương án tiết kiệm): ưu tiên soft_economical_bridge_and_compare, fallback về gracefulExitSkill cho mock test cũ
        if ("STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL".equals(finalStage)) {
            AgentSkillDto bridgeSkill = findFirstMatchingSkill(current, sanitizedCandidates, CleanAgentOrchestratorService::isSoftEconomicalBridgeOrCompareSkill);
            if (bridgeSkill != null) {
                return List.of(bridgeSkill);
            }
        }

        boolean isFinalHesitationOrPauseStage = (finalStage != null && !finalStage.isBlank())
                ? ("STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL".equals(finalStage)
                    || "STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE".equals(finalStage))
                : isHesitationOrPoliteClosing;

        if (isFinalHesitationOrPauseStage) {
            AgentSkillDto gracefulSkill = findFirstMatchingSkill(current, sanitizedCandidates, CleanAgentOrchestratorService::isGracefulExitSkill);
            if (gracefulSkill != null) {
                return List.of(gracefulSkill);
            }
            return List.of(new AgentSkillDto(
                    null,
                    "spa_graceful_exit_after_rejection",
                    "Tôn trọng quyết định cân nhắc & Giữ thiện cảm (Graceful Exit)",
                    "OBJECTION",
                    "Tôn trọng quyết định cân nhắc của khách, không thúc ép đặt lịch.",
                    "xem đã, để suy nghĩ thêm, có gì nhắn sau",
                    "Tôn trọng nhịp độ của khách, hẹn khi nào tiện nhắn lại.",
                    "Dạ vâng ạ, em đã lưu lại thông tin gói cho mình rồi ạ.",
                    "Cố ép chốt khung giờ đặt lịch khi khách xin tạm dừng.",
                    95,
                    true,
                    null,
                    null,
                    null
            ));
        }

        // 5. STAGE_1: Nếu khách đang hỏi so sánh 2 phương án ("khác gì", "khác j", "so với") -> ưu tiên soft_economical_bridge_and_compare
        if (isProductComparisonQuestion(customerMessage)) {
            AgentSkillDto compareSkill = findFirstMatchingSkill(current, sanitizedCandidates, CleanAgentOrchestratorService::isSoftEconomicalBridgeOrCompareSkill);
            if (compareSkill != null) {
                return List.of(compareSkill);
            }
        }

        // 6. STAGE_1: Khám phá nhu cầu & phạm vi ưu tiên -> ưu tiên discovery_empathy_and_scope
        if (!conditionClarified) {
            AgentSkillDto discSkill = findFirstMatchingSkill(current, sanitizedCandidates, CleanAgentOrchestratorService::isDiscoveryOrSymptomSkill);
            if (discSkill != null) {
                return List.of(discSkill);
            }
        }

        if (isPureDetailOrFaqTurn) {
            current.removeIf(s -> isBookingOrClosingSkill(s) || isLukewarmMoodBoostSkill(s) || isGracefulExitSkill(s));
        } else {
            current.removeIf(s -> isGracefulExitSkill(s) || isLukewarmMoodBoostSkill(s));
            if (current.isEmpty()) {
                AgentSkillDto discFallback = findFirstMatchingSkill(List.of(), sanitizedCandidates, CleanAgentOrchestratorService::isDiscoveryOrSymptomSkill);
                if (discFallback != null) {
                    return List.of(discFallback);
                }
            }
        }

        return capSingleFocalSkill(current);
    }

    public static List<String> classifyCleanCurrentTurnSignals(
            String mergedMessage,
            boolean isChallengeAssumption,
            boolean isStyleOrRepetitionComplaint,
            boolean isMismatch,
            boolean isDetailOrUnitQuestion,
            boolean hasPriceObjectionKeyword,
            int priceObjectionCount,
            boolean isAckOrAcceptNow,
            boolean packageInterestAccepted,
            List<String> knownSymptoms,
            List<String> knownBodyAreas,
            String statedContextOrTiming
    ) {
        return classifyCleanCurrentTurnSignals(
                mergedMessage,
                isChallengeAssumption,
                isStyleOrRepetitionComplaint,
                isMismatch,
                isDetailOrUnitQuestion,
                hasPriceObjectionKeyword,
                priceObjectionCount,
                isAckOrAcceptNow,
                packageInterestAccepted,
                knownSymptoms,
                knownBodyAreas,
                statedContextOrTiming,
                null,
                "NONE",
                List.of()
        );
    }

    public static List<String> classifyCleanCurrentTurnSignals(
            String mergedMessage,
            boolean isChallengeAssumption,
            boolean isStyleOrRepetitionComplaint,
            boolean isMismatch,
            boolean isDetailOrUnitQuestion,
            boolean hasPriceObjectionKeyword,
            int priceObjectionCount,
            boolean isAckOrAcceptNow,
            boolean packageInterestAccepted,
            List<String> knownSymptoms,
            List<String> knownBodyAreas,
            String statedContextOrTiming,
            String precedingBotSpeechAct,
            String bookingStatus,
            List<FanpageBotRequest.ConversationTurn> history
    ) {
        List<String> signals = new ArrayList<>();
        boolean isHesitation = isHesitationOrDeferralMessage(mergedMessage);
        boolean isExplicitDownsell = isExplicitDownsellOrLowBudgetRequest(mergedMessage, null);
        boolean isPriceObj = hasPriceObjectionKeyword || isCleanPriceObjectionMessage(mergedMessage);
        boolean isBareAck = !isPriceObj && !isHesitation && isBareAcknowledgmentMessage(mergedMessage);
        boolean hasProactiveBookingInfo = !isPriceObj && !isHesitation && hasProactiveBookingOrPackageSelectionInMessage(mergedMessage);
        String immediateBotText = resolveImmediatelyPrecedingBotText(history, null);
        String effectivePrecedingAct = (precedingBotSpeechAct != null && !precedingBotSpeechAct.isBlank())
                ? precedingBotSpeechAct.strip()
                : inferPrecedingBotSpeechAct(null, immediateBotText, history, bookingStatus);

        if (isMismatch) {
            signals.add("ENTITY_MISMATCH_CORRECTION");
        }
        if (isChallengeAssumption) {
            signals.add("CHALLENGE_OR_QUESTION_BOT_ASSUMPTION");
        }
        if (isStyleOrRepetitionComplaint && !isChallengeAssumption && !isMismatch) {
            signals.add("STYLE_OR_REPETITION_FEEDBACK");
        }

        if (!isPriceObj && !isMetaSignalOnly(signals)) {
            AgentBrainService.ShortUtteranceResolution shortRes =
                    AgentBrainService.decodeContextualShortUtteranceStatic(
                            mergedMessage,
                            immediateBotText,
                            effectivePrecedingAct,
                            history,
                            priceObjectionCount,
                            priceObjectionCount >= 1 && priceObjectionCount < 2,
                            priceObjectionCount >= 2,
                            true,
                            bookingStatus
                    );
            if (shortRes.isContextualShortUtterance() && !shortRes.recommendedSignals().isEmpty()) {
                signals.addAll(shortRes.recommendedSignals());
                return signals;
            }
        }

        if (isPriceObj) {
            signals.add("PRICE_OBJECTION");
            if (priceObjectionCount <= 1 && !isExplicitDownsell) {
                signals.add("PRICE_OBJECTION_FIRST_TIME");
            } else {
                signals.add("PRICE_OBJECTION_REPEAT_OR_DOWNSELL_REQUEST");
            }
        } else if (isHesitation) {
            signals.add("HESITATION_OR_DEFERRAL");
        }

        if (!isPriceObj && !isHesitation && !isMetaSignalOnly(signals) && isBareAck) {
            if ("CONFIRMED".equalsIgnoreCase(bookingStatus)
                    || SPEECH_ACT_BOOKING_CONFIRMED.equalsIgnoreCase(effectivePrecedingAct)) {
                signals.add("POST_BOOKING_CLOSING_ACK");
                return signals;
            }
            if (SPEECH_ACT_RESPECTED_PAUSE.equalsIgnoreCase(effectivePrecedingAct)
                    || wasPriorCustomerTurnHesitation(history)) {
                signals.add("POLITE_CLOSING_ACKNOWLEDGMENT");
                return signals;
            }
            if (SPEECH_ACT_ANSWERED_OPERATIONAL_INFO.equalsIgnoreCase(effectivePrecedingAct)) {
                signals.add("ACKNOWLEDGED_OPERATIONAL_INFO");
                signals.add("SERVICE_OR_UNIT_DETAIL_QUESTION");
                return signals;
            }
            if (SPEECH_ACT_OFFERED_DETAILS.equalsIgnoreCase(effectivePrecedingAct)) {
                signals.add("SERVICE_OR_UNIT_DETAIL_QUESTION");
                return signals;
            }
            if (SPEECH_ACT_INVITED_TRIAL_OR_TIME.equalsIgnoreCase(effectivePrecedingAct)) {
                signals.add("POSITIVE_INTEREST_READY_FOR_TRIAL_INVITE");
                return signals;
            }
        }

        boolean isPositiveInterest = !isPriceObj
                && !isHesitation
                && isPositiveInterestForTrialInvite(mergedMessage);
        boolean isPromoQuestion = isPromoOrDiscountInquiryMessage(mergedMessage);
        boolean isOperationalQuestion = isOperationalOrLogisticsQuestion(mergedMessage);
        boolean isComparisonQuestion = isProductComparisonQuestion(mergedMessage);
        if (isOperationalQuestion) {
            signals.add("OPERATIONAL_OR_LOGISTICS_QUESTION");
        }
        if ((isDetailOrUnitQuestion || isComparisonQuestion || isPromoQuestion || isOperationalQuestion) && !isPriceObj) {
            signals.add("SERVICE_OR_UNIT_DETAIL_QUESTION");
        }
        if (isPromoQuestion) {
            signals.add("PROMO_OR_DISCOUNT_QUESTION");
        }
        if (!isPriceObj && !isHesitation && !isMetaSignalOnly(signals) && !isDetailOrUnitQuestion) {
            if (isPositiveInterest) {
                signals.add("POSITIVE_INTEREST_READY_FOR_TRIAL_INVITE");
            } else if (hasProactiveBookingInfo || isAckOrAcceptNow || packageInterestAccepted) {
                signals.add("PACKAGE_ACCEPTANCE_OR_BOOKING_PROGRESS");
            }
        }
        boolean sharesSymptomOrContextNow = mergedMessage != null
                && !isPriceObj
                && !isHesitation
                && !isBareAck
                && (!ConversationState.extractAllNeeds(mergedMessage, List.of()).isEmpty()
                    || !ConversationState.extractTargetScopes(mergedMessage, List.of()).isEmpty()
                    || ConversationState.hasConditionTimeOrProgressionSignal(mergedMessage));
        if (sharesSymptomOrContextNow) {
            signals.add("SHARING_CONDITION_OR_CONTEXT");
        }
        if (signals.isEmpty()) {
            signals.add("SHARING_CONDITION_OR_CONTEXT");
        }
        return signals;
    }

    private static boolean isMetaSignalOnly(List<String> signals) {
        return signals.contains("ENTITY_MISMATCH_CORRECTION")
                || signals.contains("CHALLENGE_OR_QUESTION_BOT_ASSUMPTION")
                || signals.contains("STYLE_OR_REPETITION_FEEDBACK");
    }

    private static final Pattern SIMPLE_GREETING_MESSAGE_PATTERN = Pattern.compile(
            "(?iU)^\\s*(?:alo|hi|hello|hey|xin\\s+chào|chào\\s+(?:shop|bạn|em|anh|chị|ad|admin|page|mình)|shop\\s+ơi|em\\s+ơi|bạn\\s+ơi|có\\s+ai\\s+(?:ở\\s+đó\\s+)?không)[\\s,!?.ạnha]*$"
    );

    private static final Pattern SIMPLE_FACTUAL_INFO_QUERY_PATTERN = Pattern.compile(
            "(?iU)^\\s*(?:cho\\s+(?:mình|anh|chị|em)\\s+(?:hỏi|xin)\\s+)?(?:bảng\\s+giá|menu|danh\\s+sách|các\\s+(?:gói|mẫu|khóa|khoá|sản\\s+phẩm)|giá\\s+(?:bao\\s+nhiêu|sao|thế\\s+nào)|ở\\s+đâu|địa\\s+chỉ|mấy\\s+giờ|giờ\\s+mở\\s+cửa|chỗ\\s+đỗ\\s+xe|giao\\s+hàng|ship|vận\\s+chuyển|lịch\\s+khai\\s+giảng|học\\s+phí)[^.!?]{0,60}[?\\sạ]*$"
    );

    private static final Pattern EMOTIONAL_OR_DISTRESS_SIGNAL_PATTERN = Pattern.compile(
            "(?iU)\\b(đau|nhức|mỏi|căng|tê|mệt|mất\\s+ngủ|stress|căng\\s+thẳng|khó\\s+chịu|sợ|lo\\s+lắng|phân\\s+vân|băn\\s+khoăn|ngại|mất\\s+gốc|yếu|kém|áp\\s+lực|buồn)\\b"
    );

    public static ThinkingMode resolveCleanAgentThinkingMode(
            boolean isChallengeAssumption,
            boolean isStyleOrRepetitionComplaint,
            boolean isMismatch,
            boolean hasPriceObjectionOrPriceHesitation,
            boolean isPureDeferralOrBusyTurn,
            boolean conditionClarified,
            boolean packageInterestAccepted,
            boolean isDetailOrUnitQuestion,
            List<String> knownSymptoms
    ) {
        if (isPureDeferralOrBusyTurn || packageInterestAccepted || (isDetailOrUnitQuestion && !hasPriceObjectionOrPriceHesitation)) {
            return ThinkingMode.FAST_NO_THINK;
        }
        if (hasPriceObjectionOrPriceHesitation) {
            return ThinkingMode.OBJECTION_REFRAME_THINK;
        }
        if (isChallengeAssumption || isStyleOrRepetitionComplaint || isMismatch) {
            return ThinkingMode.EMOTIONAL_WARM_THINK;
        }
        if (conditionClarified || (knownSymptoms != null && !knownSymptoms.isEmpty())) {
            return ThinkingMode.PRICE_ANCHOR_THINK;
        }
        return ThinkingMode.EMOTIONAL_WARM_THINK;
    }

    public static ThinkingMode resolveCleanAgentThinkingMode(
            String customerMessage,
            boolean isChallengeAssumption,
            boolean isStyleOrRepetitionComplaint,
            boolean isMismatch,
            boolean hasPriceObjectionOrPriceHesitation,
            boolean isHesitation,
            boolean isOperationalQuestion,
            boolean isBareAck,
            boolean isPureDeferralOrBusyTurn,
            boolean conditionClarified,
            boolean packageInterestAccepted,
            boolean isDetailOrUnitQuestion,
            List<String> knownSymptoms
    ) {
        if (hasPriceObjectionOrPriceHesitation) {
            return ThinkingMode.OBJECTION_REFRAME_THINK;
        }
        if (isChallengeAssumption || isStyleOrRepetitionComplaint || isMismatch) {
            return ThinkingMode.EMOTIONAL_WARM_THINK;
        }
        String msg = customerMessage != null ? customerMessage.strip() : "";
        boolean hasEmotionalOrDistress = (!msg.isEmpty() && EMOTIONAL_OR_DISTRESS_SIGNAL_PATTERN.matcher(msg).find());
        if ((isHesitation && !isPureDeferralOrBusyTurn)
                || (hasEmotionalOrDistress && !conditionClarified && !isDetailOrUnitQuestion && !isOperationalQuestion)) {
            return ThinkingMode.EMOTIONAL_WARM_THINK;
        }
        if (isPureDeferralOrBusyTurn
                || packageInterestAccepted
                || isOperationalQuestion
                || isDetailOrUnitQuestion
                || isBareAck
                || (!msg.isEmpty() && SIMPLE_GREETING_MESSAGE_PATTERN.matcher(msg).matches())
                || (!msg.isEmpty() && !hasEmotionalOrDistress && SIMPLE_FACTUAL_INFO_QUERY_PATTERN.matcher(msg).matches())) {
            return ThinkingMode.FAST_NO_THINK;
        }
        if (hasEmotionalOrDistress) {
            return ThinkingMode.EMOTIONAL_WARM_THINK;
        }
        if (conditionClarified || (knownSymptoms != null && !knownSymptoms.isEmpty())) {
            return ThinkingMode.PRICE_ANCHOR_THINK;
        }
        if (!msg.isEmpty() && msg.split("\\s+").length <= 6) {
            return ThinkingMode.FAST_NO_THINK;
        }
        return ThinkingMode.EMOTIONAL_WARM_THINK;
    }

    public static String resolveStatedContextOrTiming(
            ConversationState state,
            ConversationState priorState,
            List<FanpageBotRequest.ConversationTurn> history,
            String currentMessage,
            boolean isMetaOrChallenge
    ) {
        Set<String> parts = new LinkedHashSet<>();
        if (state != null) {
            if (state.knownDurationOfProblem() != null && !state.knownDurationOfProblem().isBlank()) {
                parts.add(state.knownDurationOfProblem().strip());
            }
            if (state.knownFrequency() != null && !state.knownFrequency().isBlank()) {
                parts.add(state.knownFrequency().strip());
            }
            if (state.knownTrigger() != null && !state.knownTrigger().isBlank()) {
                parts.add(state.knownTrigger().strip());
            }
            if (state.knownSeverity() != null && !state.knownSeverity().isBlank()) {
                parts.add(state.knownSeverity().strip());
            }
            if (state.knownEpisodeDuration() != null && !state.knownEpisodeDuration().isBlank()) {
                parts.add(state.knownEpisodeDuration().strip());
            }
            if (state.knownSymptomDistribution() != null && !state.knownSymptomDistribution().isBlank()) {
                parts.add(state.knownSymptomDistribution().strip());
            }
        }
        if (parts.isEmpty() && currentMessage != null && !currentMessage.isBlank() && !isMetaOrChallenge) {
            boolean afterDiscoveryAsk = (priorState != null && priorState.conditionAskedCount() >= 1)
                    || resolveDiscoveryQuestionCount(priorState, history) >= 1;
            if (afterDiscoveryAsk && !SentimentIntentLayer.isPriceObjection(currentMessage) && !isCleanPriceObjectionMessage(currentMessage)) {
                parts.add(currentMessage.strip());
            }
        }
        return parts.isEmpty() ? "chưa có" : String.join(", ", parts);
    }

    private static boolean hasNarrowedTargetScope(List<String> knownBodyAreas, List<String> taxonomyKeywords) {
        if (knownBodyAreas == null || knownBodyAreas.isEmpty()) {
            return false;
        }
        List<String> cleanScopes = knownBodyAreas.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.strip().toLowerCase(Locale.ROOT))
                .toList();
        if (cleanScopes.isEmpty()) {
            return false;
        }
        if (cleanScopes.size() > 1 || cleanScopes.get(0).contains(" ")) {
            return true;
        }
        String singleToken = cleanScopes.get(0);
        if (taxonomyKeywords == null || taxonomyKeywords.isEmpty()) {
            return true;
        }
        long refiningMultiWordCount = taxonomyKeywords.stream()
                .filter(k -> k != null && !k.isBlank())
                .map(k -> k.strip().toLowerCase(Locale.ROOT))
                .distinct()
                .filter(k -> k.contains(" ") && k.matches("(?U).*\\b" + Pattern.quote(singleToken) + "\\b.*"))
                .count();
        return refiningMultiWordCount < 4;
    }

    public static int resolveDiscoveryQuestionCount(
            ConversationState priorState,
            List<FanpageBotRequest.ConversationTurn> history
    ) {
        int fromState = priorState != null ? Math.max(0, priorState.conditionAskedCount()) : 0;
        if (history == null || history.isEmpty()) {
            return fromState;
        }
        int fromHistory = 0;
        boolean sawSubstantiveCustomerTurn = false;
        for (FanpageBotRequest.ConversationTurn t : history) {
            if (t == null || t.content() == null || t.content().isBlank()) continue;
            boolean isAssistant = "assistant".equalsIgnoreCase(t.role()) || "bot".equalsIgnoreCase(t.role());
            String trimmed = t.content().strip();
            String low = trimmed.toLowerCase(Locale.ROOT);
            if (!isAssistant) {
                boolean isBareOpener = SIMPLE_GREETING_MESSAGE_PATTERN.matcher(trimmed).matches()
                        || low.matches("^(?:dạ\\s+)?(?:cần\\s+|muốn\\s+|cho\\s+(?:mình|tôi|anh|chị|em)\\s+)?tư\\s+vấn(?:\\s+(?:giúp|với|ạ|nhé|shop|em))*[.!?]*$");
                if (!isBareOpener) {
                    sawSubstantiveCustomerTurn = true;
                }
                continue;
            }
            boolean askedTimingOrContext = low.contains("lâu chưa")
                    || low.contains("mới gần đây")
                    || low.contains("mới xuất hiện")
                    || low.contains("mấy hôm nay")
                    || low.contains("hay đã kéo dài")
                    || low.contains("thời điểm nào")
                    || low.contains("khi nào")
                    || low.contains("bao lâu")
                    || low.contains("tần suất")
                    || low.contains("thường xảy ra")
                    || low.contains("tự nhiên bị")
                    || low.contains("vận động/tập luyện")
                    || low.contains("ngồi lâu hay vận động");
            boolean askedPriorityScopeAfterNeed = sawSubstantiveCustomerTurn
                    && (low.matches("(?isU).*\\b(vùng|vị\\s+trí|khu\\s+vực|phạm\\s+vi|mảng|hạng\\s+mục|chiếc|phòng)\\b[^.?]{0,24}\\bnào\\b.*")
                        || low.contains("ưu tiên")
                        || low.contains("nhiều nhất")
                        || low.contains("rõ nhất")
                        || low.contains("mỏi nhất")
                        || low.contains("khó chịu nhất")
                        || low.contains("nhức nhất"));
            if (low.contains("?") && (askedTimingOrContext || askedPriorityScopeAfterNeed)) {
                fromHistory++;
            }
        }
        if (!sawSubstantiveCustomerTurn) {
            return 0;
        }
        return Math.max(fromState, fromHistory);
    }

    public static String extractLastBotQuestion(String prevBotTurnText, String stateLastQuestion) {
        if (prevBotTurnText != null && prevBotTurnText.contains("?")) {
            int qIdx = prevBotTurnText.lastIndexOf('?');
            int startIdx = Math.max(
                    prevBotTurnText.lastIndexOf('.', qIdx),
                    Math.max(prevBotTurnText.lastIndexOf('!', qIdx), prevBotTurnText.lastIndexOf('\n', qIdx))
            );
            return prevBotTurnText.substring(startIdx >= 0 ? startIdx + 1 : 0, qIdx + 1).strip();
        }
        return (stateLastQuestion != null && !stateLastQuestion.isBlank()) ? stateLastQuestion.strip() : "không có";
    }

    public static String resolveImmediatelyPrecedingBotText(
            List<FanpageBotRequest.ConversationTurn> history,
            String fallbackLastAssistantMessage
    ) {
        if (history != null && !history.isEmpty()) {
            for (int i = history.size() - 1; i >= 0; i--) {
                FanpageBotRequest.ConversationTurn t = history.get(i);
                if (t != null && ("assistant".equalsIgnoreCase(t.role()) || "bot".equalsIgnoreCase(t.role()))
                        && t.content() != null && !t.content().isBlank()) {
                    return t.content().strip();
                }
            }
        }
        return fallbackLastAssistantMessage;
    }

    public static PrimaryActionRouter.RoutingDecision buildCleanStateSyncDecision(
            boolean isMismatch,
            boolean isStyleOrChallengeFeedback,
            boolean isDetailOrUnitQuestion,
            boolean hasPriceObjectionKeyword,
            int priceObjectionCount,
            boolean isAckOrAcceptNow,
            boolean isHesitationOrPoliteClosing,
            boolean conditionClarified,
            boolean replyHasPrice,
            boolean shouldHandoff
    ) {
        return buildCleanStateSyncDecision(
                isMismatch,
                isStyleOrChallengeFeedback,
                isDetailOrUnitQuestion,
                hasPriceObjectionKeyword,
                priceObjectionCount,
                isAckOrAcceptNow,
                isHesitationOrPoliteClosing,
                false,
                conditionClarified,
                replyHasPrice,
                shouldHandoff
        );
    }

    public static PrimaryActionRouter.RoutingDecision buildCleanStateSyncDecision(
            boolean isMismatch,
            boolean isStyleOrChallengeFeedback,
            boolean isDetailOrUnitQuestion,
            boolean hasPriceObjectionKeyword,
            int priceObjectionCount,
            boolean isAckOrAcceptNow,
            boolean isHesitationOrPoliteClosing,
            boolean isPriceContextHesitationTurn,
            boolean conditionClarified,
            boolean replyHasPrice,
            boolean shouldHandoff
    ) {
        if (shouldHandoff) {
            return new PrimaryActionRouter.RoutingDecision(
                    PrimaryActionRouter.PrimaryAction.HARD_HANDOFF_COMPLAINT,
                    PrimaryActionRouter.PrimaryAction.HARD_HANDOFF_COMPLAINT,
                    false,
                    1,
                    "CLEAN_AGENT_HANDOFF",
                    "spa_frustration_rescue"
            );
        }
        if (isMismatch) {
            return new PrimaryActionRouter.RoutingDecision(
                    replyHasPrice
                            ? PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE
                            : PrimaryActionRouter.PrimaryAction.RECOMMEND_ONE_PACKAGE,
                    replyHasPrice
                            ? PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE
                            : PrimaryActionRouter.PrimaryAction.RECOMMEND_ONE_PACKAGE,
                    false,
                    2,
                    "ENTITY_MISMATCH_CORRECTION",
                    "spa_mismatch_correction"
            );
        }
        if (hasPriceObjectionKeyword) {
            if (priceObjectionCount == 1) {
                return new PrimaryActionRouter.RoutingDecision(
                        PrimaryActionRouter.PrimaryAction.HANDLE_PRICE_OBJECTION_FIRST_TIME,
                        PrimaryActionRouter.PrimaryAction.HANDLE_PRICE_OBJECTION_FIRST_TIME,
                        false,
                        2,
                        "PRICE_OBJECTION_1",
                        "spa_objection_value_trial"
                );
            }
            return new PrimaryActionRouter.RoutingDecision(
                    PrimaryActionRouter.PrimaryAction.HANDLE_PRICE_OBJECTION_REPEAT,
                    PrimaryActionRouter.PrimaryAction.HANDLE_PRICE_OBJECTION_REPEAT,
                    false,
                    2,
                    "PRICE_OBJECTION_2",
                    "spa_objection_dynamic_downsell"
            );
        }
        if (isPriceContextHesitationTurn) {
            return new PrimaryActionRouter.RoutingDecision(
                    PrimaryActionRouter.PrimaryAction.SAFE_REFUSAL_OR_HANDOFF_IF_REPEATED,
                    PrimaryActionRouter.PrimaryAction.SAFE_REFUSAL_OR_HANDOFF_IF_REPEATED,
                    false,
                    2,
                    "PRICE_HESITATION_SOFT_DOWNSELL",
                    "spa_price_hesitation_soft_downsell"
            );
        }
        if (isHesitationOrPoliteClosing) {
            return new PrimaryActionRouter.RoutingDecision(
                    PrimaryActionRouter.PrimaryAction.SAFE_REFUSAL_OR_HANDOFF_IF_REPEATED,
                    PrimaryActionRouter.PrimaryAction.SAFE_REFUSAL_OR_HANDOFF_IF_REPEATED,
                    false,
                    2,
                    "POLITE_DEFERRAL_EXIT",
                    "spa_graceful_exit_after_rejection"
            );
        }
        if (isDetailOrUnitQuestion && !isAckOrAcceptNow) {
            return new PrimaryActionRouter.RoutingDecision(
                    PrimaryActionRouter.PrimaryAction.SERVICE_DETAIL_AND_FAQ_QA,
                    PrimaryActionRouter.PrimaryAction.SERVICE_DETAIL_AND_FAQ_QA,
                    false,
                    1,
                    "SERVICE_DETAIL_FAQ",
                    "spa_service_detail_faq"
            );
        }
        if (isStyleOrChallengeFeedback && !replyHasPrice) {
            return new PrimaryActionRouter.RoutingDecision(
                    !conditionClarified
                            ? PrimaryActionRouter.PrimaryAction.ACKNOWLEDGE_AND_ASK_PREFERENCE
                            : PrimaryActionRouter.PrimaryAction.GENERIC_SHORT_DISCOVERY,
                    !conditionClarified
                            ? PrimaryActionRouter.PrimaryAction.ACKNOWLEDGE_AND_ASK_PREFERENCE
                            : PrimaryActionRouter.PrimaryAction.GENERIC_SHORT_DISCOVERY,
                    false,
                    1,
                    !conditionClarified ? "ASK_CONDITION_ONCE" : "STYLE_REPAIR",
                    !conditionClarified ? "spa_condition_first_discovery" : null
            );
        }
        if (isAckOrAcceptNow) {
            return new PrimaryActionRouter.RoutingDecision(
                    PrimaryActionRouter.PrimaryAction.ADVANCE_AFTER_ACK,
                    PrimaryActionRouter.PrimaryAction.ADVANCE_AFTER_ACK,
                    false,
                    1,
                    "TRIAL_ACCEPTANCE_BOOKING",
                    "spa_trial_acceptance_booking"
            );
        }
        if (replyHasPrice) {
            return new PrimaryActionRouter.RoutingDecision(
                    PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE,
                    PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE,
                    false,
                    2,
                    "RECOMMEND_AND_QUOTE",
                    "spa_recommend_and_quote_if_requested"
            );
        }
        if (!conditionClarified) {
            return new PrimaryActionRouter.RoutingDecision(
                    PrimaryActionRouter.PrimaryAction.ACKNOWLEDGE_AND_ASK_PREFERENCE,
                    PrimaryActionRouter.PrimaryAction.ACKNOWLEDGE_AND_ASK_PREFERENCE,
                    false,
                    1,
                    "ASK_CONDITION_ONCE",
                    "spa_condition_first_discovery"
            );
        }
        return new PrimaryActionRouter.RoutingDecision(
                PrimaryActionRouter.PrimaryAction.RECOMMEND_ONE_PACKAGE,
                PrimaryActionRouter.PrimaryAction.RECOMMEND_ONE_PACKAGE,
                false,
                2,
                "CLEAN_AGENT_CONSULTATION",
                null
        );
    }

    // =========================================================================
    // ACI – ADAPTIVE CONVERSATION INTELLIGENCE (LLM_COGNITIVE_V2) EXECUTION
    // =========================================================================

    private record LlmTokenUsageSnapshot(
            int promptTokens,
            int completionTokens,
            int reasoningTokens
    ) {
        static LlmTokenUsageSnapshot zero() {
            return new LlmTokenUsageSnapshot(0, 0, 0);
        }

        LlmTokenUsageSnapshot plus(LlmTokenUsageSnapshot other) {
            if (other == null) return this;
            return new LlmTokenUsageSnapshot(
                    this.promptTokens + other.promptTokens,
                    this.completionTokens + other.completionTokens,
                    this.reasoningTokens + other.reasoningTokens
            );
        }
    }

    /**
     * ACI V3 (P3 Fix-A/B): nguồn triệu chứng cho các guard tầng output. Luồng LLM_COGNITIVE_V2
     * hydrate state tối giản nên {@code knownSymptoms} thường rỗng — khi đó fallback trích trực tiếp
     * từ các lượt user trong lịch sử (đã lọc clause phủ định) để lời xin lỗi/câu hỏi mở vẫn bám
     * đúng triệu chứng khách đã chia sẻ.
     */
    private static List<String> resolveKnownSymptomsForOutputGuards(
            ConversationState safeConv,
            List<FanpageBotRequest.ConversationTurn> safeHistory
    ) {
        List<String> fromState = safeConv != null && safeConv.knownSymptoms() != null
                ? safeConv.knownSymptoms() : List.of();
        if (!fromState.isEmpty()) {
            return fromState;
        }
        if (safeHistory == null || safeHistory.isEmpty()) {
            return List.of();
        }
        java.util.LinkedHashSet<String> fromHistory = new java.util.LinkedHashSet<>();
        for (FanpageBotRequest.ConversationTurn t : safeHistory) {
            if (t == null || t.content() == null
                    || !("user".equalsIgnoreCase(t.role()) || "customer".equalsIgnoreCase(t.role()))) {
                continue;
            }
            fromHistory.addAll(ConversationState.extractSymptomsMentionedInText(t.content()));
        }
        return List.copyOf(fromHistory);
    }

    public FanpageBotResponse executeAciCognitiveTurn(
            UUID pageId,
            FanpageBotRequest request,
            String legacyNormalizedMessage,
            String honorific,
            PromptBuilder.PagePersona pagePersona,
            ConversationState priorStateWithChunks,
            ConversationState convState,
            List<ReciprocalRankFusion.FusedChunk> fused,
            List<LearnedRuleDto> appliedRules,
            FanpageBotResponse.ExtractedLead extractedLead,
            CustomerInsightDto customerInsight,
            List<AgentSkillDto> activeSkills,
            List<AgentSkillDto> candidateSkills,
            FanpageBotPromptBuilder.SalesStage salesStage,
            int incomingHistorySize,
            long turnStartNs,
            long retrievalMs,
            AdaptiveConversationIntelligence.ThreeStreamTurnInput prebuiltThreeStreamInput
    ) {
        ConversationState safePrior = priorStateWithChunks != null ? priorStateWithChunks : ConversationState.initial();
        ConversationState safeConv = convState != null ? convState : safePrior;
        com.example.docmind.dto.PagePersonaConfig effectivePersonaConfig = (pagePersona != null && pagePersona.resolvedConfig() != null)
                ? pagePersona.resolvedConfig()
                : com.example.docmind.dto.PagePersonaConfig.empty();
        if (request != null && Boolean.TRUE.equals(request.resetSession()) && agentBrainService != null && !FanpageBotService.isCurrentTurnAlreadyReset()) {
            agentBrainService.clearCustomerMemory(pageId, request.senderId());
        }
        String senderIdForPronoun = request != null ? request.senderId() : null;
        String rawDefaultCustomerPronoun = (pagePersona != null && pagePersona.resolvedConfig() != null)
                ? effectivePersonaConfig.resolvedDefaultCustomerPronoun()
                : ((honorific != null && !honorific.isBlank()) ? honorific.strip() : "mình");
        String priorInferredCustomerPronoun = (agentBrainService != null && pageId != null && senderIdForPronoun != null && !senderIdForPronoun.isBlank())
                ? agentBrainService.getInferredCustomerPronoun(pageId, senderIdForPronoun)
                : null;
        // ACI V3 Pronoun Policy (§7.1): Khi chưa xác nhận giới tính khách (priorInferredCustomerPronoun == null),
        // không mặc định gọi "Chị" hay "Anh/Chị" mà chuyển về đại từ trung tính "mình" (vẫn giữ "bạn" nếu Page cấu hình "bạn").
        String defaultCustomerPronoun = ((priorInferredCustomerPronoun == null || priorInferredCustomerPronoun.isBlank())
                && ("chị".equalsIgnoreCase(rawDefaultCustomerPronoun) || "anh/chị".equalsIgnoreCase(rawDefaultCustomerPronoun)))
                ? "mình"
                : rawDefaultCustomerPronoun;
        String safeHonorific = (priorInferredCustomerPronoun != null && !priorInferredCustomerPronoun.isBlank())
                ? priorInferredCustomerPronoun.strip()
                : defaultCustomerPronoun;
        String selfPronoun = effectivePersonaConfig.resolvedSelfPronoun();
        int minDelayMs = effectivePersonaConfig.resolvedPostPriceDelayMs() > 0
                ? effectivePersonaConfig.resolvedPostPriceDelayMs()
                : (int) PriceQuoteReadingBudgetScheduler.PRICE_QUOTE_READING_BUDGET_MS;
        java.time.ZoneId pageZoneId = java.time.ZoneId.of("Asia/Ho_Chi_Minh");

        // System Gate 1: Phiên đã tắt bot (HUMAN_TAKEOVER hoặc lệnh hệ thống OPT_OUT thực sự trước đó)
        if (safePrior.optedOut() || safePrior.humanTakeover()) {
            FanpageBotService.recordCurrentTurnGuard("BLOCK", "BOT_MUTED");
            FanpageBotService.markProvenanceV38Logged();
            return new FanpageBotResponse(
                    "",
                    List.of(),
                    List.of(),
                    "HUMAN_HANDOFF",
                    1.0,
                    safePrior.humanTakeover(),
                    null,
                    null,
                    extractedLead,
                    List.of(),
                    customerInsight,
                    List.of(),
                    List.of("BOT_MUTED")
            );
        }

        // Khôi phục Catalog đầy đủ (không bao giờ làm mù nhận thức LLM)
        List<String> domainKeywords = effectivePersonaConfig.resolvedScopeTaxonomy();
        List<PackageCatalogService.PackageSummary> extractedFromChunks = PackageCatalogService.filterByRegion(
                PackageCatalogService.extractCatalog(fused != null ? fused : List.of(), domainKeywords),
                safeConv.preferredRegion()
        );
        List<PackageCatalogService.PackageSummary> allCatalog = new ArrayList<>();
        Set<String> seenCatalogIds = new LinkedHashSet<>();
        if (safeConv.availablePackageCatalog() != null) {
            for (PackageCatalogService.PackageSummary p : safeConv.availablePackageCatalog()) {
                if (p != null && seenCatalogIds.add(p.productId())) {
                    allCatalog.add(p);
                }
            }
        }
        if (safePrior.availablePackageCatalog() != null) {
            for (PackageCatalogService.PackageSummary p : safePrior.availablePackageCatalog()) {
                if (p != null && seenCatalogIds.add(p.productId())) {
                    allCatalog.add(p);
                }
            }
        }
        for (PackageCatalogService.PackageSummary p : extractedFromChunks) {
            if (p != null && seenCatalogIds.add(p.productId())) {
                allCatalog.add(p);
            }
        }

        // Tách biệt 3 luồng dữ liệu đầu vào (Mục 2.1): Original Message vs Normalized Query vs Unverified Parser Candidates
        AdaptiveConversationIntelligence.ThreeStreamTurnInput threeStreamInput = prebuiltThreeStreamInput != null
                ? prebuiltThreeStreamInput
                : AdaptiveConversationIntelligence.buildThreeStreamInput(request, allCatalog, effectivePersonaConfig, pageZoneId);
        String originalVerbatim = !threeStreamInput.originalMessage().isBlank()
                ? threeStreamInput.originalMessage()
                : (legacyNormalizedMessage != null ? legacyNormalizedMessage : "");
        String sameTurnSelfPronoun = AdaptiveConversationIntelligence
                .inferExplicitCustomerSelfPronounFromUtterance(originalVerbatim);
        double priorPronounConfidence = (agentBrainService != null && pageId != null && senderIdForPronoun != null && !senderIdForPronoun.isBlank())
                ? agentBrainService.getInferredPronounConfidence(pageId, senderIdForPronoun)
                : 0.0;
        boolean pronounLockedFromSelfReference = (sameTurnSelfPronoun != null && !sameTurnSelfPronoun.isBlank())
                || (priorInferredCustomerPronoun != null && !priorInferredCustomerPronoun.isBlank() && priorPronounConfidence >= 0.95);
        if (sameTurnSelfPronoun != null && !sameTurnSelfPronoun.isBlank()) {
            priorInferredCustomerPronoun = sameTurnSelfPronoun;
            safeHonorific = sameTurnSelfPronoun;
            pronounLockedFromSelfReference = true;
        }

        // System Gate 2: Lệnh Opt-out hệ thống tường minh (STOP, UNSUBSCRIBE, HỦY ĐĂNG KÝ, ĐỪNG NHẮN NỮA).
        // Câu từ chối hội thoại lịch sự như "Thôi, không cần nữa" (S06) KHÔNG đi vào nhánh này và mặc định opted_out = false!
        if (PrimaryActionRouter.isStrictSystemOptOutMessage(originalVerbatim)) {
            FanpageBotService.recordCurrentTurnGuard("ALLOW", "EXPLICIT_OPT_OUT");
            String stopText = "Dạ " + selfPronoun + " xin phép dừng nhắn tin để tránh làm phiền " + safeHonorific
                    + " ạ. Khi nào cần hỗ trợ, " + safeHonorific + " cứ nhắn lại cho " + selfPronoun + " bất cứ lúc nào nhé.";
            List<FanpageBotResponse.ReplyBubble> optOutBubbles = List.of(
                    new FanpageBotResponse.ReplyBubble(stopText, 0)
            );
            PrimaryActionRouter.RoutingDecision optOutDecision = new PrimaryActionRouter.RoutingDecision(
                    PrimaryActionRouter.PrimaryAction.GENERIC_SHORT_DISCOVERY,
                    PrimaryActionRouter.PrimaryAction.GENERIC_SHORT_DISCOVERY,
                    false,
                    1,
                    "EXPLICIT_OPT_OUT",
                    "spa_ambiguous_confirm_once"
            );
            if (agentBrainService != null) {
                agentBrainService.applyAciTurnMemoryUpdates(
                        pageId,
                        request != null ? request.senderId() : null,
                        CleanAgentCatalogAndPromptBuilder.resolveCurrentSequentialTurnId(request != null ? request.history() : List.of()),
                        originalVerbatim,
                        threeStreamInput.unverifiedParserCandidates(),
                        AdaptiveConversationIntelligence.AdaptiveCognitiveReflection.empty(),
                        agentBrainService.recallBookingState(pageId, request != null ? request.senderId() : null),
                        null,
                        true,
                        null,
                        "SYSTEM_OPT_OUT_ACK"
                );
            }
            ConversationState.updateAfterSend(
                    pageId,
                    request != null ? request.senderId() : null,
                    request != null ? request.conversationId() : null,
                    safeConv,
                    originalVerbatim,
                    optOutDecision,
                    optOutBubbles,
                    false,
                    incomingHistorySize
            );
            FanpageBotService.markProvenanceV38Logged();
            return new FanpageBotResponse(
                    stopText,
                    optOutBubbles,
                    List.of(0),
                    "DISCOVERY",
                    1.0,
                    false,
                    null,
                    null,
                    extractedLead,
                    List.of(),
                    customerInsight,
                    List.of(),
                    List.of("EXPLICIT_OPT_OUT")
            );
        }

        List<FanpageBotRequest.ConversationTurn> safeHistory = (request != null && request.history() != null)
                ? request.history()
                : List.of();

        // Shadow Scope Candidates (chỉ dùng để xếp thứ tự gợi ý PRIMARY vs ECONOMICAL_OPTION, KHÔNG ẩn gói hợp lệ!)
        // P3.7-A Requirement II.1.2: Không lấy từ khóa trích từ câu phàn nàn về lời bot làm bằng chứng khách có nhu cầu dịch vụ.
        List<String> shadowScopeCandidates = new ArrayList<>();
        if (safePrior.knownBodyAreas() != null) {
            shadowScopeCandidates.addAll(safePrior.knownBodyAreas());
        }
        if (!CleanAgentOutputRefereeAndSanitizer.isCommunicationStyleComplaintMessage(originalVerbatim)) {
            for (AdaptiveConversationIntelligence.UnverifiedParserCandidate cand : threeStreamInput.unverifiedParserCandidates()) {
                if ("SCOPE_OR_AREA_CANDIDATE".equals(cand.candidateType()) && !shadowScopeCandidates.contains(cand.rawValue())) {
                    shadowScopeCandidates.add(cand.rawValue());
                }
            }
        }
        // ACI V3: giữ nguyên bối cảnh đa triệu chứng từ các lượt khách trước (không làm rơi triệu chứng cũ).
        List<String> historySymptomKeys = List.of("đau đầu", "khó ngủ", "mất ngủ", "cổ vai gáy", "mỏi gối", "tê tay", "lưng");
        for (FanpageBotRequest.ConversationTurn turn : safeHistory) {
            if (turn == null || turn.content() == null || turn.role() == null
                    || !("user".equalsIgnoreCase(turn.role()) || "customer".equalsIgnoreCase(turn.role()))) {
                continue;
            }
            if (CleanAgentOutputRefereeAndSanitizer.isCommunicationStyleComplaintMessage(turn.content())) {
                continue;
            }
            // ACI V3 P1 §3.3: KHÔNG quét từ khóa triệu chứng trên lượt phản bác/đính chính
            // ("ơ bị dở à sao lại cổ vai gáy") — từ khóa trong câu phủ định không phải bằng chứng nhu cầu.
            VietnameseScopeSignalExtractor.ScopeSignalResult historyTurnSignal =
                    VietnameseScopeSignalExtractor.analyze(turn.content(), List.of());
            if (historyTurnSignal.possibleCorrection() || historyTurnSignal.explicitCorrectionGroupD()) {
                continue;
            }
            String turnLower = turn.content().toLowerCase(Locale.ROOT);
            for (String symptomKey : historySymptomKeys) {
                if (turnLower.contains(symptomKey) && !shadowScopeCandidates.contains(symptomKey)) {
                    shadowScopeCandidates.add(symptomKey);
                }
            }
        }
        CleanAgentCatalogAndPromptBuilder.TieredCatalogResult tieredCatalog =
                CleanAgentCatalogAndPromptBuilder.rankAndTierCatalogForCleanAgent(
                        allCatalog,
                        List.of(),
                        shadowScopeCandidates,
                        Set.of(),
                        false
                );

        // Epistemic Unblinding (Mục 3.2): Mở đầy đủ tất cả gói non-addon hợp lệ của Page với nhãn PRIMARY / ECONOMICAL_OPTION
        List<PackageCatalogService.PackageSummary> unblindedPrimaries = new ArrayList<>(tieredCatalog.primaryRecommendations());
        List<PackageCatalogService.PackageSummary> unblindedEconomicals = new ArrayList<>(tieredCatalog.economicalAlternatives());
        Set<String> includedProductIds = new LinkedHashSet<>();
        for (PackageCatalogService.PackageSummary p : unblindedPrimaries) {
            if (p != null) includedProductIds.add(p.productId());
        }
        for (PackageCatalogService.PackageSummary p : unblindedEconomicals) {
            if (p != null) includedProductIds.add(p.productId());
        }
        long maxCatalogPrice = allCatalog.stream()
                .filter(p -> p != null && !p.isAddon())
                .mapToLong(PackageCatalogService.PackageSummary::priceAmountVnd)
                .max()
                .orElse(0L);
        long minCatalogPrice = allCatalog.stream()
                .filter(p -> p != null && !p.isAddon() && p.priceAmountVnd() > 0L)
                .mapToLong(PackageCatalogService.PackageSummary::priceAmountVnd)
                .min()
                .orElse(0L);
        long midpointPrice = (maxCatalogPrice > 0L && minCatalogPrice > 0L)
                ? ((maxCatalogPrice + minCatalogPrice) / 2L)
                : 300_000L;
        for (PackageCatalogService.PackageSummary p : allCatalog) {
            if (p == null || p.isAddon() || !includedProductIds.add(p.productId())) {
                continue;
            }
            if (p.priceAmountVnd() > 0L && p.priceAmountVnd() < midpointPrice && !unblindedPrimaries.isEmpty()) {
                unblindedEconomicals.add(p);
            } else {
                unblindedPrimaries.add(p);
            }
        }
        List<PackageCatalogService.PackageSummary> unblindedEligibleCatalog = new ArrayList<>(unblindedPrimaries);
        unblindedEligibleCatalog.addAll(unblindedEconomicals);
        List<PackageCatalogService.PackageSummary> excludedAddonsOnly = new ArrayList<>();
        for (PackageCatalogService.PackageSummary p : allCatalog) {
            if (p != null && p.isAddon()) {
                excludedAddonsOnly.add(p);
            }
        }

        // Quote-Reply Metadata Resolution (Sự thật hệ thống)
        String replyToMid = request != null && request.replyToMid() != null ? request.replyToMid().strip() : "";
        String quotedMessageText = request != null && request.quotedMessageText() != null ? request.quotedMessageText().strip() : "";
        if (quotedMessageText.isEmpty() && !replyToMid.isEmpty() && request != null) {
            String lookedUpQuoted = SentBubbleLedger.lookupMessageByMid(request.pageId(), request.senderId(), replyToMid);
            quotedMessageText = lookedUpQuoted != null ? lookedUpQuoted.strip() : "";
        }
        List<PackageCatalogService.PackageSummary> presentedPackagesInHistory =
                CleanAgentCatalogAndPromptBuilder.extractPresentedPackagesFromHistory(safeHistory, safePrior, allCatalog);
        CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution quotedReplyResolution =
                CleanAgentCatalogAndPromptBuilder.resolveQuotedReplyContext(
                        replyToMid,
                        quotedMessageText,
                        allCatalog,
                        presentedPackagesInHistory
                );
        String prevBotTurnText = resolveImmediatelyPrecedingBotText(safeHistory, safePrior.lastAssistantMessage());

        // Khôi phục 5 Tầng Bộ nhớ & Giữ nguyên priorBookingState (TUYỆT ĐỐI KHÔNG reset bookingState về NONE khi đắn đo/báo CK!)
        String senderId = request != null ? request.senderId() : null;
        ConversationState.StructuredBookingState priorBookingState = ConversationState.StructuredBookingState.none();
        if (agentBrainService != null) {
            ConversationState.StructuredBookingState fromBrain = agentBrainService.recallBookingState(pageId, senderId);
            if (fromBrain != null && (fromBrain.status() != ConversationState.BookingStatus.NONE
                    || (fromBrain.selectedProductId() != null && !fromBrain.selectedProductId().isBlank()))) {
                priorBookingState = fromBrain;
            }
        }
        ConversationState.StructuredBookingState priorFromSession = safePrior.structuredBookingState();
        boolean priorHasLockedSelection = priorBookingState.selectedProductId() != null
                && !priorBookingState.selectedProductId().isBlank();
        if (priorBookingState.status() == ConversationState.BookingStatus.NONE
                && !priorHasLockedSelection
                && priorFromSession != null
                && (priorFromSession.status() != ConversationState.BookingStatus.NONE
                    || (priorFromSession.selectedProductId() != null && !priorFromSession.selectedProductId().isBlank()))) {
            priorBookingState = priorFromSession;
        }

        List<AdaptiveConversationIntelligence.CustomerStatementRecord> tier1Statements =
                agentBrainService != null ? agentBrainService.getAciCustomerStatements(pageId, senderId) : List.of();
        AdaptiveConversationIntelligence.WorkingHypothesisRecord tier2Hypothesis =
                agentBrainService != null ? agentBrainService.getAciWorkingHypothesis(pageId, senderId)
                        : AdaptiveConversationIntelligence.WorkingHypothesisRecord.initialEmpty();
        List<AdaptiveConversationIntelligence.VerifiedBusinessFact> tier4Facts =
                agentBrainService != null ? agentBrainService.getAuthoritativeBusinessFacts(pageId, senderId) : List.of();
        AdaptiveConversationIntelligence.OperationalStateSnapshot tier5OpState =
                agentBrainService != null ? agentBrainService.getAciOperationalState(pageId, senderId)
                        : AdaptiveConversationIntelligence.OperationalStateSnapshot.initial();
        AdaptiveConversationIntelligence.PreviousStrategyRecord previousStrategy =
                agentBrainService != null ? agentBrainService.getPreviousStrategyRecord(pageId, senderId)
                        : AdaptiveConversationIntelligence.PreviousStrategyRecord.initialNone();
        List<AdaptiveConversationIntelligence.CommitmentRecord> sessionCommitments =
                agentBrainService != null ? agentBrainService.getSessionCommitments(pageId, senderId) : List.of();
        AdaptiveConversationIntelligence.CustomerUnderstandingDraft2Snapshot draft2Before =
                agentBrainService != null ? agentBrainService.getCustomerUnderstandingDraft2Snapshot(pageId, senderId)
                        : AdaptiveConversationIntelligence.CustomerUnderstandingDraft2Snapshot.initial();

        boolean hasAuthoritativePaymentVerification = tier4Facts.stream()
                .anyMatch(f -> f != null
                        && "PAYMENT_VERIFIED".equalsIgnoreCase(f.factKey())
                        && "true".equalsIgnoreCase(f.factValue()));
        String verifiedPageSlaPolicy = tier4Facts.stream()
                .filter(f -> f != null && "VERIFIED_SLA_POLICY".equalsIgnoreCase(f.factKey()))
                .map(AdaptiveConversationIntelligence.VerifiedBusinessFact::factValue)
                .findFirst()
                .orElse(null);

        String previousBotSpeechAct = agentBrainService != null
                ? agentBrainService.getRecalledActualSentSpeechAct(pageId, senderId)
                : "";
        int priceObjectionCountBefore = agentBrainService != null
                ? agentBrainService.getPriceObjectionCount(pageId, senderId)
                : 0;
        String brainVerifiedSymptomDurBefore = agentBrainService != null
                ? agentBrainService.getVerifiedSymptomDuration(pageId, senderId)
                : null;
        String symptomDurationBefore = (brainVerifiedSymptomDurBefore != null && !brainVerifiedSymptomDurBefore.isBlank())
                ? brainVerifiedSymptomDurBefore
                : safePrior.knownDurationOfProblem();
        AdaptiveConversationIntelligence.WorkingHypothesisRecord workingHypothesisBefore = tier2Hypothesis;

        List<AgentSkillDto> rawPageSkills = (candidateSkills != null && !candidateSkills.isEmpty())
                ? candidateSkills
                : (agentSkillService != null && pageId != null ? agentSkillService.getActiveSkillsForPage(pageId) : List.of());
        List<AgentSkillDto> enabledPageSkills = CleanAgentCatalogAndPromptBuilder.filterPageConfiguredSkills(rawPageSkills, pageId);
        List<LearnedRuleDto> activePageRules = appliedRules != null
                ? appliedRules.stream().filter(r -> r != null && r.active()).toList()
                : List.of();
        List<LearnedRuleDto> turnRelevantRules = CleanAgentCatalogAndPromptBuilder.selectTurnRelevantCompactRules(
                activePageRules,
                originalVerbatim,
                safeHistory.isEmpty(),
                5
        );
        // ACI V3 P2 (§4.7, cấm #6): vùng prompt chỉ đến từ cue tường minh (lượt hiện tại -> lịch sử user -> session);
        // khách chưa nêu chi nhánh -> null (giữ đủ biến thể đa chi nhánh + nhãn branch="..."), TUYỆT ĐỐI không default "HN".
        String effectivePromptRegion = ConversationState.resolveExplicitRegionAcrossTurns(
                originalVerbatim,
                safeHistory,
                safeConv.preferredRegion()
        );
        List<PackageCatalogService.PackageSummary> regionScopedPrimaries = effectivePromptRegion != null
                ? PackageCatalogService.filterByRegion(unblindedPrimaries, effectivePromptRegion)
                : unblindedPrimaries;
        List<PackageCatalogService.PackageSummary> regionScopedEconomicals = effectivePromptRegion != null
                ? PackageCatalogService.filterByRegion(unblindedEconomicals, effectivePromptRegion)
                : unblindedEconomicals;
        List<PackageCatalogService.PackageSummary> promptPrimaries =
                CleanAgentCatalogAndPromptBuilder.deduplicateCatalogByCanonicalSignature(
                        regionScopedPrimaries.isEmpty() ? unblindedPrimaries : regionScopedPrimaries);
        List<PackageCatalogService.PackageSummary> promptEconomicals =
                CleanAgentCatalogAndPromptBuilder.deduplicateCatalogByCanonicalSignature(
                        regionScopedEconomicals.isEmpty() && !unblindedEconomicals.isEmpty() && regionScopedPrimaries.isEmpty()
                                ? unblindedEconomicals
                                : regionScopedEconomicals);

        List<AgentSkillDto> availableSkills = enabledPageSkills != null ? List.copyOf(enabledPageSkills) : List.of();
        List<AgentSkillDto> preSelectedTurnSkills = selectPreLlmTurnRelevantSkills(
                availableSkills,
                originalVerbatim,
                safeHistory,
                priorBookingState
        );

        // ACI V3: Pre-resolve lựa chọn tương đối/đính chính của khách NGAY lượt này (trước Snapshot & Prompt)
        // để LLM thấy selected_product_id đã khóa trong <SESSION_MEMORY_5_TIER>; loại gói có tóc nếu khách đã từ chối hair_care.
        if (AdaptiveConversationIntelligence.isTrialOrRelativeOptionSelectionMessage(originalVerbatim)
                || AdaptiveConversationIntelligence.isCustomerCorrectionOrComponentRejectionMessage(originalVerbatim)) {
            boolean excludeHairCare = draft2Before != null
                    && draft2Before.businessCommitment() != null
                    && draft2Before.businessCommitment().negativePreferences() != null
                    && draft2Before.businessCommitment().negativePreferences().stream()
                        .anyMatch(e -> e != null && "hair_care".equalsIgnoreCase(e.targetKey()));
            PackageCatalogService.PackageSummary preResolved =
                    AdaptiveConversationIntelligence.resolveContextualOptionFromUtterance(
                            originalVerbatim,
                            prevBotTurnText,
                            presentedPackagesInHistory,
                            allCatalog,
                            excludeHairCare
                    );
            if (preResolved != null && preResolved.productId() != null && !preResolved.productId().isBlank()) {
                priorBookingState = new ConversationState.StructuredBookingState(
                        preResolved.productId(),
                        preResolved.canonicalLabel(),
                        priorBookingState.bookingDate(),
                        priorBookingState.resolvedIsoDate(),
                        priorBookingState.bookingTime(),
                        priorBookingState.timezoneId(),
                        priorBookingState.status(),
                        priorBookingState.candidateProductIds() != null ? priorBookingState.candidateProductIds() : List.of()
                );
            }
        }

        // ACI V3 Guardrail 2: Xây dựng Snapshot bất biến của lượt hiện tại đúng 1 lần, dùng chung cho Prompt Builder và Guard
        String sequentialTurnId = CleanAgentCatalogAndPromptBuilder.resolveCurrentSequentialTurnId(safeHistory);
        AdaptiveConversationIntelligence.TurnContextSnapshot turnContextSnapshot =
                AdaptiveConversationIntelligence.buildTurnContextSnapshot(
                        sequentialTurnId,
                        originalVerbatim,
                        safeHonorific,
                        pronounLockedFromSelfReference,
                        priceObjectionCountBefore,
                        safeHistory,
                        draft2Before,
                        priorBookingState
                );
        this.lastTurnContextSnapshot = turnContextSnapshot;

        // Xây dựng Single-Pass Cognitive Contract Prompt (và đo kích thước prompt cũ tương đương cho Telemetry Mục 7.1)
        long promptStartNs = System.nanoTime();
        String aciPrompt = CleanAgentCatalogAndPromptBuilder.buildAciSinglePassPrompt(
                pagePersona,
                defaultCustomerPronoun,
                priorInferredCustomerPronoun,
                safeHonorific,
                threeStreamInput,
                promptPrimaries,
                promptEconomicals,
                quotedReplyResolution,
                tier1Statements,
                tier2Hypothesis,
                tier4Facts,
                priorBookingState,
                tier5OpState,
                previousStrategy,
                sessionCommitments,
                verifiedPageSlaPolicy,
                hasAuthoritativePaymentVerification,
                fused != null ? fused : List.of(),
                safeHistory,
                preSelectedTurnSkills,
                turnRelevantRules,
                draft2Before,
                turnContextSnapshot.plan()
        );
        this.lastBuiltPrompt = aciPrompt;
        // Unit P1.1: Ngừng dựng ngầm Prompt V1 (~22.400 ký tự) trên hot-path LLM_COGNITIVE_V2
        int legacyEquivalentPromptChars = 0;
        long promptBuildMs = Math.max(0L, (System.nanoTime() - promptStartNs) / 1_000_000L);

        // Chọn chế độ ThinkingMode thích ứng (tôn trọng BenchmarkConfigMode cho thực nghiệm FAST_NO_THINK vs DEEP_THINK)
        ThinkingMode selectedThinkingMode = selectAciAdaptiveThinkingMode(originalVerbatim, threeStreamInput, sessionCommitments);
        this.lastSelectedThinkingMode = selectedThinkingMode;
        ReasoningBudget aciBudget = buildAciReasoningBudget(selectedThinkingMode);

        String rawReply = null;
        int llmRegenerationCount = 0;
        int llmCallCount = 0;
        final long turnDeadlineMs = 65_000L;
        String retryTriggerReason = "NONE";
        LlmTokenUsageSnapshot tokenUsage = LlmTokenUsageSnapshot.zero();
        long modelLatencyMs = 0L;
        boolean initialUpstreamError = false;
        try {
            if (llmClient != null) {
                FanpageBotService.markCurrentTurnLlmStarted();
                llmCallCount++;
                long callStartNs = System.nanoTime();
                rawReply = llmClient.complete(aciPrompt, aciBudget);
                if (rawReply == null) {
                    rawReply = llmClient.complete(aciPrompt);
                }
                modelLatencyMs += Math.max(1L, (System.nanoTime() - callStartNs) / 1_000_000L);
                tokenUsage = tokenUsage.plus(extractLlmTokenUsage(llmClient));
                if (isUpstreamLlmClientErrorPayload(rawReply)) {
                    initialUpstreamError = true;
                    retryTriggerReason = "UPSTREAM_ERROR_OR_TIMEOUT";
                    log.warn("[ACI-V2] Upstream LLM network/gateway error on initial call (msg='{}') — skipping redundant JSON retry",
                            originalVerbatim);
                } else if (rawReply == null || rawReply.isBlank()) {
                    long elapsedBeforeRetryMs = Math.max(0L, (System.nanoTime() - turnStartNs) / 1_000_000L);
                    if (elapsedBeforeRetryMs >= 65_000L) {
                        initialUpstreamError = true;
                        retryTriggerReason = "SKIPPED_RETRY_DEADLINE_EXCEEDED";
                    } else {
                        retryTriggerReason = "EMPTY_INITIAL_RESPONSE";
                        log.warn("[ACI-V2] Triggering LLM retry #{}: retryTriggerReason={} (msg='{}')",
                                llmRegenerationCount + 1, retryTriggerReason, originalVerbatim);
                        llmRegenerationCount++;
                        llmCallCount++;
                        long fallbackStartNs = System.nanoTime();
                        rawReply = llmClient.complete(aciPrompt, buildAciReasoningBudget(ThinkingMode.FAST_NO_THINK));
                        if (rawReply == null) {
                            rawReply = llmClient.complete(aciPrompt);
                        }
                        modelLatencyMs += Math.max(1L, (System.nanoTime() - fallbackStartNs) / 1_000_000L);
                        tokenUsage = tokenUsage.plus(extractLlmTokenUsage(llmClient));
                        if (rawReply == null || rawReply.isBlank() || isUpstreamLlmClientErrorPayload(rawReply)) {
                            initialUpstreamError = true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            initialUpstreamError = true;
            retryTriggerReason = "UPSTREAM_ERROR_OR_TIMEOUT";
            log.warn("[ACI-V2] Exception calling llmClient {}: {}", llmClient != null ? llmClient.name() : "null", e.getMessage());
        }

        if (!SenderEpochRegistry.checkThreadEpochStillCurrent(
                request != null ? request.pageId() : null,
                request != null ? request.senderId() : null,
                SenderEpochRegistry.Checkpoint.AFTER_LLM_BEFORE_REFEREE)) {
            return buildSupersededAbortedResponse(extractedLead, customerInsight);
        }

        // Unit P3.3: Parse Single-Pass JSON trước khi gọi PromptSecurityGuard.sanitizeModelOutput trên văn bản thô
        // (bảo vệ trường hợp brain_reflection nội bộ nhắc <DOCUMENT_CONTEXT> / <CONVERSATION_HISTORY> ở T3 bị xóa nhầm thành plain-text gây retry ngầm 24s;
        // mọi bubble gửi khách đều đã được lọc qua PromptSecurityGuard.sanitizeModelOutput bên trong extractBubblesFromRootNode)
        boolean rawIsUpstreamError = isUpstreamLlmClientErrorPayload(rawReply);
        CleanAgentOutputRefereeAndSanitizer.StructuredLlmOutput structured =
                (rawIsUpstreamError || rawReply == null || rawReply.isBlank())
                        ? null
                        : CleanAgentOutputRefereeAndSanitizer.parseStructuredLlmJsonOutput(rawReply, 0);
        String sanitizedReply = rawIsUpstreamError
                ? ""
                : ((structured != null && structured.parsedFromJson())
                        ? rawReply
                        : PromptSecurityGuard.sanitizeModelOutput(rawReply));
        String effectiveStructuredOutputForProfiling = sanitizedReply;
        if (!initialUpstreamError
                && (structured == null || !structured.parsedFromJson() || structured.bubbles() == null || structured.bubbles().isEmpty())
                && llmRegenerationCount < 1) {
            long elapsedBeforeJsonRetryMs = Math.max(0L, (System.nanoTime() - turnStartNs) / 1_000_000L);
            if (elapsedBeforeJsonRetryMs >= 65_000L) {
                retryTriggerReason = "SKIPPED_RETRY_DEADLINE_EXCEEDED";
            } else {
                retryTriggerReason = (structured == null || !structured.parsedFromJson())
                        ? "JSON_PARSE_FAILURE"
                        : "EMPTY_BUBBLES_IN_JSON";
                log.warn("[ACI-V2] Triggering LLM retry #{}: retryTriggerReason={} rawChars={} (msg='{}')",
                        llmRegenerationCount + 1,
                        retryTriggerReason,
                        rawReply != null ? rawReply.length() : 0,
                        originalVerbatim);
                try {
                    if (llmClient != null) {
                        llmRegenerationCount++;
                        llmCallCount++;
                        long retryStartNs = System.nanoTime();
                        String retryRaw = llmClient.complete(aciPrompt, buildAciReasoningBudget(ThinkingMode.FAST_NO_THINK));
                        if (retryRaw == null) {
                            retryRaw = llmClient.complete(aciPrompt);
                        }
                        modelLatencyMs += Math.max(1L, (System.nanoTime() - retryStartNs) / 1_000_000L);
                        tokenUsage = tokenUsage.plus(extractLlmTokenUsage(llmClient));
                        if (retryRaw != null && !retryRaw.isBlank() && !isUpstreamLlmClientErrorPayload(retryRaw)) {
                            rawReply = retryRaw;
                            CleanAgentOutputRefereeAndSanitizer.StructuredLlmOutput retryStructured =
                                    CleanAgentOutputRefereeAndSanitizer.parseStructuredLlmJsonOutput(rawReply, 0);
                            sanitizedReply = (retryStructured != null && retryStructured.parsedFromJson())
                                    ? rawReply
                                    : PromptSecurityGuard.sanitizeModelOutput(rawReply);
                            effectiveStructuredOutputForProfiling = sanitizedReply;
                            structured = retryStructured;
                        }
                    }
                } catch (Exception e) {
                    log.warn("[ACI-V2] JSON retry exception: {}", e.getMessage());
                }
                if (!SenderEpochRegistry.checkThreadEpochStillCurrent(
                        request != null ? request.pageId() : null,
                        request != null ? request.senderId() : null,
                        SenderEpochRegistry.Checkpoint.AFTER_LLM_BEFORE_REFEREE)) {
                    return buildSupersededAbortedResponse(extractedLead, customerInsight);
                }
            }
        }

        // ACI V3 P1 §3.1: bằng chứng grounding gồm phát ngôn hiện tại + toàn bộ phát ngôn khách
        // trước đó trong phiên (fact được LLM mang từ lượt trước sang vẫn hợp lệ).
        String historicalCustomerEvidence = safeHistory.stream()
                .filter(t -> t != null && t.content() != null
                        && !"assistant".equalsIgnoreCase(t.role()) && !"bot".equalsIgnoreCase(t.role()))
                .map(FanpageBotRequest.ConversationTurn::content)
                .collect(java.util.stream.Collectors.joining("\n"));
        AdaptiveConversationIntelligence.AdaptiveCognitiveReflection reflection =
                ((structured != null && structured.aciReflection() != null)
                        ? structured.aciReflection()
                        : AdaptiveConversationIntelligence.AdaptiveCognitiveReflection.empty())
                        .reconcileWithCurrentUtterance(originalVerbatim, historicalCustomerEvidence);
        if (!pronounLockedFromSelfReference && reflection.customerUnderstanding().hasHighConfidenceInferredPronoun()) {
            safeHonorific = reflection.customerUnderstanding().inferredCustomerPronoun();
        }

        List<FanpageBotResponse.ReplyBubble> candidateBubbles =
                (isUpstreamLlmClientErrorPayload(sanitizedReply) || isLeakedReasoningWithoutJsonContract(structured, sanitizedReply))
                        ? List.of()
                        : CleanAgentOutputRefereeAndSanitizer.extractCleanBubblesFromOutput(structured, sanitizedReply, 0);
        List<String> rawLlmBubbleTexts = candidateBubbles.stream()
                .map(FanpageBotResponse.ReplyBubble::text)
                .filter(java.util.Objects::nonNull)
                .toList();
        boolean llmOutputUnavailable = candidateBubbles.isEmpty();
        boolean usedStructuredFallbackOnLlmFailure = false;
        if (candidateBubbles.isEmpty()) {
            log.warn("[ACI-V2] Empty/unreachable LLM output (msg='{}') -> activating Context-Safe Fallback (LLM_OUTPUT_UNAVAILABLE)", originalVerbatim);
            candidateBubbles = CleanAgentOutputRefereeAndSanitizer.buildContextSafeAciV2Fallback(
                    quotedReplyResolution,
                    priorBookingState,
                    "ACI_COGNITIVE_V2",
                    !unblindedPrimaries.isEmpty() ? unblindedPrimaries.get(0) : null,
                    unblindedEligibleCatalog,
                    allCatalog,
                    effectivePersonaConfig,
                    originalVerbatim,
                    selfPronoun,
                    safeHonorific,
                    draft2Before,
                    fused != null ? fused : List.of()
            );
            usedStructuredFallbackOnLlmFailure = true;
        } else {
            candidateBubbles = CleanAgentOutputRefereeAndSanitizer.repairWrongPronounOpenersInPlace(
                    candidateBubbles, selfPronoun, safeHonorific);
            // Unit P3.3: Ưu tiên in-place sanitization cho lỗi hình thức (cụm "thấy thương/xót quá", mã SKU nội bộ, câu dẫn dấu :)
            // trước khi chạy Referee để không tốn 24s LLM retry cho lỗi hình thức, trong khi giữ nguyên Referee cho mọi lỗi bịa đặt nội dung!
            candidateBubbles = CleanAgentOutputRefereeAndSanitizer.sanitizePatronizingEmpathyPhrasesInPlace(
                    candidateBubbles, selfPronoun, safeHonorific);
            if (CleanAgentOutputRefereeAndSanitizer.isCommunicationStyleComplaintMessage(originalVerbatim)) {
                candidateBubbles = CleanAgentOutputRefereeAndSanitizer.enforceAciV2StyleComplaintNoCtaAndAntiClicheInPlace(
                        candidateBubbles, originalVerbatim, prevBotTurnText, selfPronoun, safeHonorific);
            }
            if (isPoliteThankYouClosingAckMessage(originalVerbatim)) {
                candidateBubbles = CleanAgentOutputRefereeAndSanitizer.enforcePoliteClosingPauseSingleBubbleNoQuestionInPlace(
                        candidateBubbles, selfPronoun, safeHonorific, priorBookingState, prevBotTurnText);
            }
            candidateBubbles = CleanAgentOutputRefereeAndSanitizer.stripInternalCatalogSkuCodesInPlace(
                    candidateBubbles, allCatalog);
            candidateBubbles = CleanAgentOutputRefereeAndSanitizer.sanitizeAmbiguousPriceFormatsInPlace(
                    candidateBubbles);
            if (originalVerbatim == null
                    || !originalVerbatim.toLowerCase(Locale.ROOT).matches("(?isU).*\\b(tip|tiền\\s+bo)\\b.*")) {
                candidateBubbles = CleanAgentOutputRefereeAndSanitizer.stripUngroundedTipPolicyClaimsInPlace(
                        candidateBubbles);
            }
            candidateBubbles = CleanAgentOutputRefereeAndSanitizer.mergeTrailingColonAndTurn1PriceIntroBubbles(
                    candidateBubbles, originalVerbatim, safeHistory.isEmpty());
            // ACI V3 P1 §3.5: False Apology Guard — chặn lời xin lỗi tham chiếu nội dung cụ thể
            // mà bot chưa từng nói trong lịch sử ("xin lỗi vì vừa nhắc tới cổ vai gáy" khi bot chưa hề nhắc)
            {
                List<String> priorAssistantTexts = safeHistory.stream()
                        .filter(t -> t != null
                                && ("assistant".equalsIgnoreCase(t.role()) || "bot".equalsIgnoreCase(t.role())))
                        .map(FanpageBotRequest.ConversationTurn::content)
                        .filter(java.util.Objects::nonNull)
                        .toList();
                List<String> knownSymptomsForApologyGuard =
                        resolveKnownSymptomsForOutputGuards(safeConv, safeHistory);
                candidateBubbles = CleanAgentOutputRefereeAndSanitizer.enforceGroundedApologyAgainstHistoryInPlace(
                        candidateBubbles, priorAssistantTexts, knownSymptomsForApologyGuard, originalVerbatim,
                        selfPronoun, safeHonorific);
            }
        }

        // Áp dụng ProposedBookingPatch qua cổng thẩm quyền Java Core & đánh giá Conditional Handoff (Mục 3.4 & Mục 4)
        ConversationState.StructuredBookingState rawAuthoritativeBookingState =
                AdaptiveConversationIntelligence.applyAuthoritativeBookingPatch(
                        priorBookingState,
                        reflection.proposedBookingPatch(),
                        originalVerbatim,
                        prevBotTurnText,
                        allCatalog,
                        presentedPackagesInHistory,
                        pageZoneId,
                        reflection.customerUnderstanding().bookingReadiness(),
                        reflection.priority().primary()
                );
        BookingLifecycleEvaluation bookingLifecycleEval = applyBookingGatewayLifecycle(
                pageId,
                senderId,
                priorBookingState,
                rawAuthoritativeBookingState,
                originalVerbatim,
                effectivePromptRegion
        );
        ConversationState.StructuredBookingState authoritativeBookingState = bookingLifecycleEval.bookingState();
        candidateBubbles = alignBubblesWithBookingLifecycle(
                candidateBubbles,
                bookingLifecycleEval,
                priorBookingState,
                originalVerbatim,
                allCatalog,
                presentedPackagesInHistory,
                selfPronoun,
                safeHonorific
        );
        AdaptiveConversationIntelligence.ConditionalHandoffDecision handoffDecision =
                AdaptiveConversationIntelligence.evaluateAndExecuteConditionalHandoff(
                        pageId,
                        senderId,
                        originalVerbatim,
                        reflection,
                        pagePersona,
                        handoffEventDispatcher
                );
        boolean isAcuteOrNeuro = FanpageBotService.isUniversalAcuteMedicalEmergency(originalVerbatim);
        if (isAcuteOrNeuro || ("MEDICAL_SAFETY_HANDOFF".equals(handoffDecision.handoffCategory()) && handoffDecision.isHandoffCreated())) {
            boolean customerAlsoRequestedStaff = AdaptiveConversationIntelligence.isExplicitHumanEscalationMessage(originalVerbatim);
            String combinedCandidateLower = candidateBubbles.stream()
                    .map(FanpageBotResponse.ReplyBubble::text)
                    .filter(java.util.Objects::nonNull)
                    .reduce("", (a, b) -> a + " " + b)
                    .toLowerCase(Locale.ROOT);
            if (isAcuteOrNeuro) {
                boolean hasNegatedEmergency = combinedCandidateLower.matches(
                        "(?isU).*\\b(không\\s+cần|khỏi\\s+cần|chưa\\s+cần)\\s+(?:gọi\\s+)?(?:115|cấp\\s+cứu|đi\\s+bệnh\\s+viện|đến\\s+bệnh\\s+viện).*");
                boolean has115OrEmergency = !hasNegatedEmergency
                        && combinedCandidateLower.matches("(?isU).*\\b(115|cấp\\s+cứu|bệnh\\s+viện|cơ\\s+sở\\s+y\\s+tế)\\b.*");
                boolean promisesSpaServiceForEmergency = combinedCandidateLower.matches(
                        "(?isU).*\\b(đặt\\s+lịch|giữ\\s+lịch|qua\\s+cơ\\s+sở\\s+làm|ghé\\s+spa\\s+làm|kỹ\\s+thuật\\s+viên\\s+sẽ\\s+massage|bấm\\s+huyệt\\s+cho)\\b.*");
                boolean mentionsStaffSupport = combinedCandidateLower.matches(
                        "(?isU).*\\b(chuyên\\s+viên|quản\\s+lý|nhân\\s+viên|nhân\\s+sự|bộ\\s+phận\\s+hỗ\\s+trợ).*");
                if (!has115OrEmergency || promisesSpaServiceForEmergency) {
                    String safeAcuteEmergencyText = "Dạ với các dấu hiệu cấp tính như vậy, để đảm bảo an toàn sức khỏe tuyệt đối, "
                            + safeHonorific + " hãy tạm dừng vận động, nằm nghỉ ngơi tại chỗ ngay và nhờ người thân gọi cấp cứu 115 hoặc đưa "
                            + safeHonorific + " đến bệnh viện/cơ sở y tế gần nhất để bác sĩ kiểm tra khẩn cấp ngay nhé ạ."
                            + (customerAlsoRequestedStaff
                                ? (" Đồng thời " + selfPronoun + " đã chuyển thông tin khẩn tới quản lý/nhân viên phụ trách của cơ sở để nắm tình hình và phối hợp hỗ trợ " + safeHonorific + " ngay ạ.")
                                : "");
                    candidateBubbles = List.of(new FanpageBotResponse.ReplyBubble(safeAcuteEmergencyText, 0));
                } else if (customerAlsoRequestedStaff && !mentionsStaffSupport && !candidateBubbles.isEmpty()) {
                    int lastIdx = candidateBubbles.size() - 1;
                    FanpageBotResponse.ReplyBubble lastB = candidateBubbles.get(lastIdx);
                    String augmented = lastB.text().strip()
                            + " Đồng thời " + selfPronoun + " đã chuyển thông tin tới quản lý/nhân viên phụ trách của cơ sở để hỗ trợ "
                            + safeHonorific + " ngay ạ.";
                    List<FanpageBotResponse.ReplyBubble> updated = new ArrayList<>(candidateBubbles);
                    updated.set(lastIdx, new FanpageBotResponse.ReplyBubble(augmented, lastB.typingDelayMs()));
                    candidateBubbles = List.copyOf(updated);
                }
            } else if (reflection.handoffRequest() == null || !reflection.handoffRequest().requested()) {
                boolean alreadyMentionsInternalSpecialist = combinedCandidateLower.matches(
                        "(?isU).*\\b(chuyên\\s+viên|quản\\s+lý|bộ\\s+phận\\s+chuyên\\s+môn|bác\\s+sĩ|nhân\\s+sự\\s+phụ\\s+trách).*");
                if (!alreadyMentionsInternalSpecialist) {
                    String safeMedicalText = "Dạ với trường hợp này, để đảm bảo an toàn tuyệt đối cho " + safeHonorific + ", "
                            + selfPronoun + " xin phép kết nối chuyên viên phụ trách kiểm tra và tư vấn trực tiếp cho "
                            + safeHonorific + " ngay nhé ạ.";
                    candidateBubbles = List.of(new FanpageBotResponse.ReplyBubble(safeMedicalText, 0));
                }
            }
        }

        // Kiểm định Referee V2 (CHỈ giữ Sự thật, Quyền hạn & An toàn — TẮT ép chiến thuật bán hàng thay LLM)
        long validationStartNs = System.nanoTime();
        List<String> refereeViolations = usedStructuredFallbackOnLlmFailure
                ? new ArrayList<>()
                : new ArrayList<>(
                        CleanAgentOutputRefereeAndSanitizer.checkAciV2TruthAuthorityAndSafety(
                                candidateBubbles,
                                allCatalog,
                                unblindedEligibleCatalog,
                                excludedAddonsOnly,
                                quotedReplyResolution,
                                authoritativeBookingState,
                                presentedPackagesInHistory,
                                effectivePersonaConfig,
                                originalVerbatim,
                                hasAuthoritativePaymentVerification,
                                verifiedPageSlaPolicy,
                                handoffDecision,
                                reflection,
                                fused != null ? fused : List.of(),
                                pagePersona != null ? pagePersona.name() : null,
                                prevBotTurnText,
                                safeHistory
                        )
                );

        boolean repairApplied = usedStructuredFallbackOnLlmFailure;
        boolean usedSafeHandoffFallback = false;
        boolean repairUpstreamError = false;
        if (!refereeViolations.isEmpty() && !usedStructuredFallbackOnLlmFailure) {
            log.warn("[ACI-V2] Referee detected truth/authority/safety violations: {} (msg='{}')",
                    refereeViolations, originalVerbatim);
            PackageCatalogService.PackageSummary focusPkgForRepair = authoritativeBookingState.selectedProductId() != null
                    ? AdaptiveConversationIntelligence.resolveCatalogProductByFlexibleIdentifier(
                            authoritativeBookingState.selectedProductId(), presentedPackagesInHistory, allCatalog)
                    : (quotedReplyResolution.quotedPackage() != null
                        ? quotedReplyResolution.quotedPackage()
                        : (!unblindedPrimaries.isEmpty() ? unblindedPrimaries.get(0) : null));
            long elapsedBeforeRefereeRetryMs = Math.max(0L, (System.nanoTime() - turnStartNs) / 1_000_000L);
            if (llmClient != null && llmRegenerationCount < 1 && !initialUpstreamError && elapsedBeforeRefereeRetryMs >= 65_000L) {
                retryTriggerReason = "SKIPPED_RETRY_DEADLINE_EXCEEDED";
            } else if (llmClient != null && llmRegenerationCount < 1 && !initialUpstreamError) {
                retryTriggerReason = "REFEREE_VIOLATION:" + refereeViolations.get(0);
                log.warn("[ACI-V2] Triggering Referee targeted repair retry #{}: retryTriggerReason={} allViolations={} (msg='{}')",
                        llmRegenerationCount + 1, retryTriggerReason, refereeViolations, originalVerbatim);
                String repairPrompt = CleanAgentOutputRefereeAndSanitizer.buildCleanRepairPrompt(
                        aciPrompt, refereeViolations, selfPronoun, safeHonorific, focusPkgForRepair);
                try {
                    llmRegenerationCount++;
                    llmCallCount++;
                    long repairCallStartNs = System.nanoTime();
                    String repairedRaw = llmClient.complete(repairPrompt, buildAciReasoningBudget(ThinkingMode.FAST_NO_THINK));
                    if (repairedRaw == null) {
                        repairedRaw = llmClient.complete(repairPrompt);
                    }
                    modelLatencyMs += Math.max(1L, (System.nanoTime() - repairCallStartNs) / 1_000_000L);
                    tokenUsage = tokenUsage.plus(extractLlmTokenUsage(llmClient));
                    if (repairedRaw == null || repairedRaw.isBlank() || isUpstreamLlmClientErrorPayload(repairedRaw)) {
                        repairUpstreamError = true;
                        log.warn("[ACI-V2] Referee targeted repair hit UPSTREAM_NETWORK_ERROR (msg='{}')", originalVerbatim);
                    } else {
                        CleanAgentOutputRefereeAndSanitizer.StructuredLlmOutput repairedStructured =
                                CleanAgentOutputRefereeAndSanitizer.parseStructuredLlmJsonOutput(repairedRaw, 0);
                        String repairedSanitized = (repairedStructured != null && repairedStructured.parsedFromJson())
                                ? repairedRaw
                                : PromptSecurityGuard.sanitizeModelOutput(repairedRaw);
                        List<FanpageBotResponse.ReplyBubble> repairedBubbles =
                                (isUpstreamLlmClientErrorPayload(repairedSanitized)
                                        || isLeakedReasoningWithoutJsonContract(repairedStructured, repairedSanitized))
                                        ? List.of()
                                        : CleanAgentOutputRefereeAndSanitizer.extractCleanBubblesFromOutput(
                                                repairedStructured, repairedSanitized, 0);
                        if (!repairedBubbles.isEmpty()) {
                            effectiveStructuredOutputForProfiling = repairedSanitized;
                            candidateBubbles = CleanAgentOutputRefereeAndSanitizer.repairWrongPronounOpenersInPlace(
                                    repairedBubbles, selfPronoun, safeHonorific);
                            structured = repairedStructured;
                            if (repairedStructured != null && repairedStructured.aciReflection() != null
                                    && repairedStructured.aciReflection().parsedFromV2Schema()) {
                                reflection = repairedStructured.aciReflection()
                                        .reconcileWithCurrentUtterance(originalVerbatim, historicalCustomerEvidence);
                                if (!pronounLockedFromSelfReference && reflection.customerUnderstanding().hasHighConfidenceInferredPronoun()) {
                                    safeHonorific = reflection.customerUnderstanding().inferredCustomerPronoun();
                                }
                                candidateBubbles = CleanAgentOutputRefereeAndSanitizer.repairWrongPronounOpenersInPlace(
                                        candidateBubbles, selfPronoun, safeHonorific);
                                ConversationState.StructuredBookingState repairedRawBookingState =
                                        AdaptiveConversationIntelligence.applyAuthoritativeBookingPatch(
                                                priorBookingState,
                                                reflection.proposedBookingPatch(),
                                                originalVerbatim,
                                                prevBotTurnText,
                                                allCatalog,
                                                presentedPackagesInHistory,
                                                pageZoneId,
                                                reflection.customerUnderstanding().bookingReadiness(),
                                                reflection.priority().primary()
                                        );
                                bookingLifecycleEval = applyBookingGatewayLifecycle(
                                        pageId,
                                        senderId,
                                        priorBookingState,
                                        repairedRawBookingState,
                                        originalVerbatim,
                                        effectivePromptRegion
                                );
                                authoritativeBookingState = bookingLifecycleEval.bookingState();
                                handoffDecision = AdaptiveConversationIntelligence.evaluateAndExecuteConditionalHandoff(
                                        pageId,
                                        senderId,
                                        originalVerbatim,
                                        reflection,
                                        pagePersona,
                                        handoffEventDispatcher
                                );
                            }
                            candidateBubbles = alignBubblesWithBookingLifecycle(
                                    candidateBubbles,
                                    bookingLifecycleEval,
                                    priorBookingState,
                                    originalVerbatim,
                                    allCatalog,
                                    presentedPackagesInHistory,
                                    selfPronoun,
                                    safeHonorific
                            );
                            candidateBubbles = CleanAgentOutputRefereeAndSanitizer.sanitizePatronizingEmpathyPhrasesInPlace(
                                    candidateBubbles, selfPronoun, safeHonorific);
                            candidateBubbles = CleanAgentOutputRefereeAndSanitizer.stripInternalCatalogSkuCodesInPlace(
                                    candidateBubbles, allCatalog);
                            candidateBubbles = CleanAgentOutputRefereeAndSanitizer.mergeTrailingColonAndTurn1PriceIntroBubbles(
                                    candidateBubbles, originalVerbatim, safeHistory.isEmpty());
                            repairApplied = true;
                        }
                    }
                } catch (Exception e) {
                    repairUpstreamError = true;
                    log.warn("[ACI-V2] Referee targeted repair exception (UPSTREAM_NETWORK_ERROR): {}", e.getMessage());
                }
                if (!SenderEpochRegistry.checkThreadEpochStillCurrent(
                        request != null ? request.pageId() : null,
                        request != null ? request.senderId() : null,
                        SenderEpochRegistry.Checkpoint.AFTER_LLM_BEFORE_REFEREE)) {
                    return buildSupersededAbortedResponse(extractedLead, customerInsight);
                }
            }

            List<String> postRepairViolations = CleanAgentOutputRefereeAndSanitizer.checkAciV2TruthAuthorityAndSafety(
                    candidateBubbles,
                    allCatalog,
                    unblindedEligibleCatalog,
                    excludedAddonsOnly,
                    quotedReplyResolution,
                    authoritativeBookingState,
                    presentedPackagesInHistory,
                    effectivePersonaConfig,
                    originalVerbatim,
                    hasAuthoritativePaymentVerification,
                    verifiedPageSlaPolicy,
                    handoffDecision,
                    reflection,
                    fused != null ? fused : List.of(),
                    pagePersona != null ? pagePersona.name() : null,
                    prevBotTurnText,
                    safeHistory
            );
            if (!postRepairViolations.isEmpty()) {
                boolean hasSecurityOrCrossTenantLeak = postRepairViolations.stream()
                        .anyMatch(v -> v != null && (v.startsWith("INTERNAL_POLICY_OR_PROMPT_LEAKAGE")
                                || v.startsWith("CROSS_TENANT_BRAND_OR_CATALOG_LEAKAGE")));
                boolean hasUngroundedPrice = postRepairViolations.stream()
                        .anyMatch(v -> v != null && v.startsWith("UNGROUNDED_PRICE_MENTIONED:"));
                if (hasSecurityOrCrossTenantLeak) {
                    log.warn("[ACI-V2] Post-repair still has internal/cross-tenant leakage ({}) -> building neutral NO_CTA scope response", postRepairViolations);
                    candidateBubbles = buildSafeAciAuthorityFallbackBubbles(
                            postRepairViolations,
                            selfPronoun,
                            safeHonorific,
                            authoritativeBookingState,
                            handoffDecision,
                            originalVerbatim
                    );
                    repairApplied = true;
                } else if (repairUpstreamError) {
                    log.warn("[ACI-V2] Repair call hit UPSTREAM_NETWORK_ERROR while resolving ({}) -> activating Safe Structural Fallback (without conflating network failure as persistent content violation)",
                            postRepairViolations);
                    candidateBubbles = CleanAgentOutputRefereeAndSanitizer.buildSafeMultiIndustryTurnFallbackBubbles(
                            quotedReplyResolution,
                            authoritativeBookingState,
                            "ACI_COGNITIVE_V2",
                            focusPkgForRepair,
                            unblindedEligibleCatalog,
                            allCatalog,
                            effectivePersonaConfig,
                            originalVerbatim,
                            selfPronoun,
                            safeHonorific
                    );
                    usedStructuredFallbackOnLlmFailure = true;
                    repairApplied = true;
                } else if (hasUngroundedPrice) {
                    log.warn("[ACI-V2] Post-repair still has UNGROUNDED_PRICE_MENTIONED ({}) -> triggering SAFE_HANDOFF with synchronized handoffStatus",
                            postRepairViolations);
                    handoffDecision = AdaptiveConversationIntelligence.executeAuthoritativeSafeRefereeHandoff(
                            pageId,
                            senderId,
                            "REFEREE_SAFE_HANDOFF_UNGROUNDED_PRICE",
                            "Referee chặn mức giá dịch vụ không có căn cứ sau bước sửa lỗi: " + postRepairViolations,
                            handoffEventDispatcher
                    );
                    candidateBubbles = List.of(new FanpageBotResponse.ReplyBubble(FanpageBotService.SAFE_HANDOFF_BUBBLE_TEXT, 0));
                    usedSafeHandoffFallback = true;
                    repairApplied = true;
                } else if (postRepairViolations.stream().anyMatch(v -> v != null
                        && (v.startsWith("QUOTED_PACKAGE_FOCUS_MISMATCH:")
                            || v.startsWith("UNRESOLVED_OR_AMBIGUOUS_REPLY_GUARD_VIOLATION:")
                            || ("UNGROUNDED_PROMO_POLICY_FABRICATION".equals(v)
                                && quotedReplyResolution != null
                                && quotedReplyResolution.resolutionState() != CleanAgentCatalogAndPromptBuilder.ReplyResolutionState.NONE)))) {
                    log.warn("[ACI-V2] Post-repair still has quote-reply/promo guard violation ({}) -> building deterministic quoted-context fallback",
                            postRepairViolations);
                    candidateBubbles = CleanAgentOutputRefereeAndSanitizer.buildSafeMultiIndustryTurnFallbackBubbles(
                            quotedReplyResolution,
                            authoritativeBookingState,
                            "ACI_COGNITIVE_V2",
                            focusPkgForRepair,
                            unblindedEligibleCatalog,
                            allCatalog,
                            effectivePersonaConfig,
                            originalVerbatim,
                            selfPronoun,
                            safeHonorific
                    );
                    repairApplied = true;
                } else if (postRepairViolations.stream().anyMatch(v -> v != null && v.startsWith("PRICE_ENTITY_MISMATCH:"))) {
                    candidateBubbles = CleanAgentOutputRefereeAndSanitizer.rebuildBubblesFromStructuredCatalog(
                            candidateBubbles,
                            focusPkgForRepair,
                            allCatalog,
                            selfPronoun,
                            safeHonorific,
                            effectivePersonaConfig
                    );
                    repairApplied = true;
                } else if (CleanAgentOutputRefereeAndSanitizer.isStyleOrToneOnlyViolationList(postRepairViolations)) {
                    candidateBubbles = CleanAgentOutputRefereeAndSanitizer.enforceAciV2StyleComplaintNoCtaAndAntiClicheInPlace(
                            candidateBubbles,
                            originalVerbatim,
                            prevBotTurnText,
                            selfPronoun,
                            safeHonorific
                    );
                    repairApplied = true;
                } else if (postRepairViolations.size() == 1
                        && "UNGROUNDED_TIP_POLICY_CLAIM".equals(postRepairViolations.get(0))) {
                    candidateBubbles = CleanAgentOutputRefereeAndSanitizer.stripUngroundedTipPolicyClaimsInPlace(candidateBubbles);
                    repairApplied = true;
                } else {
                    log.warn("[ACI-V2] Post-repair still has authority/safety violation ({}) -> building safe factual response", postRepairViolations);
                    candidateBubbles = buildSafeAciAuthorityFallbackBubbles(
                            postRepairViolations,
                            selfPronoun,
                            safeHonorific,
                            authoritativeBookingState,
                            handoffDecision,
                            originalVerbatim
                    );
                    repairApplied = true;
                }
            }
        }
        if (!usedSafeHandoffFallback) {
            if (CleanAgentOutputRefereeAndSanitizer.isCommunicationStyleComplaintMessage(originalVerbatim)) {
                reflection = reflection.withForcedNoCta();
            }
            if (!usedStructuredFallbackOnLlmFailure) {
                candidateBubbles = CleanAgentOutputRefereeAndSanitizer.enforceAciV2StyleComplaintNoCtaAndAntiClicheInPlace(
                        candidateBubbles,
                        originalVerbatim,
                        prevBotTurnText,
                        selfPronoun,
                        safeHonorific
                );
            }
            candidateBubbles = CleanAgentOutputRefereeAndSanitizer.stripInternalCatalogSkuCodesInPlace(
                    candidateBubbles,
                    allCatalog
            );
            candidateBubbles = CleanAgentOutputRefereeAndSanitizer.sanitizeAmbiguousPriceFormatsInPlace(
                    candidateBubbles);
            boolean isFirstSubstantiveTurn = safeHistory.isEmpty()
                    || safeHistory.stream()
                            .filter(t -> t != null && !"assistant".equalsIgnoreCase(t.role()) && !"bot".equalsIgnoreCase(t.role()))
                            .allMatch(t -> CleanAgentCatalogAndPromptBuilder.isBareTurnOneGreetingOrConsultationRequest(t.content()));
            candidateBubbles = CleanAgentOutputRefereeAndSanitizer.mergeTrailingColonAndTurn1PriceIntroBubbles(
                    candidateBubbles,
                    originalVerbatim,
                    isFirstSubstantiveTurn
            );
        }
        long validationMs = Math.max(0L, (System.nanoTime() - validationStartNs) / 1_000_000L);
        long llmElapsedMs = Math.max(1L, modelLatencyMs);

        // Chuẩn hóa nhẹ hình thức câu chữ (chỉ viết hoa chữ cái đầu — SBR-01 FIX-3: KHÔNG đổi dấu '?'/'.' của LLM)
        List<FanpageBotResponse.ReplyBubble> formattedBubbles = new ArrayList<>(candidateBubbles.size());
        for (FanpageBotResponse.ReplyBubble b : candidateBubbles) {
            if (b == null || b.text() == null || b.text().isBlank()) continue;
            String txt = b.text().strip();
            if (!usedSafeHandoffFallback) {
                txt = BubbleTextSanitizer.capitalizeFirstLetter(txt);
            }
            formattedBubbles.add(new FanpageBotResponse.ReplyBubble(txt, 0));
        }
        if (!usedSafeHandoffFallback && !usedStructuredFallbackOnLlmFailure) {
            formattedBubbles = CleanAgentOutputRefereeAndSanitizer.enforceP37B2TurnActionAndBubbleIdeaDisciplineInPlace(
                    formattedBubbles,
                    originalVerbatim,
                    safeHistory,
                    authoritativeBookingState,
                    unblindedEligibleCatalog,
                    selfPronoun,
                    safeHonorific,
                    effectivePersonaConfig != null ? effectivePersonaConfig.resolvedCareActor() : "kỹ thuật viên",
                    turnContextSnapshot.plan()
            );
        }
        if (!usedSafeHandoffFallback) {
            formattedBubbles = new ArrayList<>(alignBubblesWithBookingLifecycle(
                    formattedBubbles,
                    bookingLifecycleEval,
                    priorBookingState,
                    originalVerbatim,
                    allCatalog,
                    presentedPackagesInHistory,
                    selfPronoun,
                    safeHonorific
            ));
        }

        // ACI V3 — Stage P3 Gate (tc2/tc3/tc5): tuyến phòng thủ CUỐI trên output hậu Referee-repair.
        // Nhánh repair của Referee (REFEREE_VIOLATION retry) không đi qua các guard đầu pipeline, nên cả
        // False Apology Guard lẫn Zero Unsolicited Pitch đều phải chạy lại ở đây trên formattedBubbles:
        //  (a) False Apology Guard: chặn lời xin lỗi tự nhận "đã nhắc sang/đoán nhầm/suy diễn..." nội dung
        //      bot chưa từng nói; khi viết lại thì nối vế ghi nhận triệu chứng khách đã chia sẻ;
        //  (b) Zero Unsolicited Pitch: lượt CUSTOMER_CORRECTION / OVER_INTRUSION / COMMUNICATION_COMPLAINT
        //      mà khách KHÔNG đòi giá tường minh -> cắt mọi câu chứa giá/tên gói/CTA chốt chi nhánh và nối
        //      câu hỏi mở bám đúng triệu chứng khách.
        // Lượt đang có flow đặt lịch dở (bookingState active) được miễn để không phá nhịp xác nhận lịch.
        if (!usedSafeHandoffFallback && !usedStructuredFallbackOnLlmFailure
                && (authoritativeBookingState == null
                    || authoritativeBookingState.status() == null
                    || authoritativeBookingState.status() == ConversationState.BookingStatus.NONE)) {
            List<String> priorAssistantTextsFinalGate = safeHistory.stream()
                    .filter(t -> t != null
                            && ("assistant".equalsIgnoreCase(t.role()) || "bot".equalsIgnoreCase(t.role())))
                    .map(FanpageBotRequest.ConversationTurn::content)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            List<String> knownSymptomsFinalGate =
                    resolveKnownSymptomsForOutputGuards(safeConv, safeHistory);
            formattedBubbles = new ArrayList<>(
                    CleanAgentOutputRefereeAndSanitizer.enforceGroundedApologyAgainstHistoryInPlace(
                            formattedBubbles, priorAssistantTextsFinalGate, knownSymptomsFinalGate, originalVerbatim,
                            selfPronoun, safeHonorific));
            formattedBubbles = new ArrayList<>(
                    CleanAgentOutputRefereeAndSanitizer.stripUnsolicitedPackagePitchOnNegativeReactionInPlace(
                            formattedBubbles, originalVerbatim, knownSymptomsFinalGate, domainKeywords,
                            selfPronoun, safeHonorific));
        }

        // Price Quote Reading Budget (10s) (Mục 6):
        // Chỉ kích hoạt ở lượt thực sự báo giá/giới thiệu gói; tìm đúng chỉ số bong bóng chứa giá + gói (không gán cứng bubble 0);
        // tuyệt đối không kích hoạt trên lượt phản đối giá như S03B ("Bằng cả ngày đi làm").
        boolean isQuoteIntroTurn = !usedSafeHandoffFallback
                && PriceQuoteReadingBudgetScheduler.isActualPriceQuoteIntroductionTurn(
                        originalVerbatim,
                        safeHistory,
                        formattedBubbles,
                        allCatalog,
                        reflection
                );
        int quoteBubbleIndex = PriceQuoteReadingBudgetScheduler.findActualPriceQuoteBubbleIndex(
                formattedBubbles,
                allCatalog,
                isQuoteIntroTurn
        );
        List<FanpageBotResponse.ReplyBubble> finalBubbles = PriceQuoteReadingBudgetScheduler.applyReadingBudgetTypingDelays(
                formattedBubbles,
                quoteBubbleIndex,
                minDelayMs,
                PriceQuoteReadingBudgetScheduler.DEFAULT_INTER_BUBBLE_TYPING_DELAY_MS
        );

        String ledgerKey = SentBubbleLedger.key(
                request != null ? request.pageId() : null,
                senderId,
                request != null ? request.conversationId() : null
        );
        String ledgerUserTurnId = SentBubbleLedger.stableUserTurnId(ledgerKey, originalVerbatim);
        SentBubbleLedger.recordCustomerTurn(ledgerKey, ledgerUserTurnId, originalVerbatim);
        if (!finalBubbles.isEmpty()) {
            SentBubbleLedger.recordAssistantTurn(
                    ledgerKey,
                    ledgerUserTurnId + ":a",
                    finalBubbles.stream().map(FanpageBotResponse.ReplyBubble::text).toList(),
                    SentBubbleLedger.DeliveryStatus.PENDING,
                    List.of()
            );
        }

        if (!SenderEpochRegistry.checkThreadEpochStillCurrent(
                request != null ? request.pageId() : null,
                request != null ? request.senderId() : null,
                SenderEpochRegistry.Checkpoint.BEFORE_STATE_AND_LEDGER_COMMIT)) {
            SentBubbleLedger.markAssistantBubblesSuppressedByFenceFrom(
                    ledgerKey, ledgerUserTurnId + ":a", 0, "SUPERSEDED_BY_NEWER_EPOCH");
            return buildSupersededAbortedResponse(extractedLead, customerInsight);
        }
        if (!isOutboundStillPermittedLayer2(pageId, request)) {
            SentBubbleLedger.markAssistantBubblesSuppressedByFenceFrom(
                    ledgerKey, ledgerUserTurnId + ":a", 0, "SUPPRESSED_STALE_TURN_AFTER_STATE_LOCK");
            log.warn("[ACI-V2-LAYER2-FENCE] Stale turn suppressed before memory/ledger commit (page_id={} sender_id={})",
                    pageId, senderId);
            FanpageBotService.markProvenanceV38Logged();
            return FanpageBotService.buildSilentSuppressedResponse("SUPPRESSED_STALE_TURN_AFTER_STATE_LOCK", null);
        }

        boolean shouldHandoff = usedSafeHandoffFallback || handoffDecision.isHandoffCreated();
        String combinedReplyText = String.join("\n\n", finalBubbles.stream().map(FanpageBotResponse.ReplyBubble::text).toList());
        boolean replyHasPrice = CleanAgentOutputRefereeAndSanitizer.hasQuotedPriceInText(combinedReplyText);
        String selectedSpeechAct = llmOutputUnavailable
                ? "LLM_OUTPUT_UNAVAILABLE"
                : (!reflection.responsePlan().strategy().isBlank()
                    ? reflection.responsePlan().strategy()
                    : (!reflection.strategy().chosen().isBlank() ? reflection.strategy().chosen() : "Direct Answer"));
        String generatedSpeechAct = llmOutputUnavailable
                ? "LLM_OUTPUT_UNAVAILABLE"
                : CleanAgentOutputRefereeAndSanitizer.resolveActualSentSpeechActV2(
                        reflection,
                        authoritativeBookingState.status().name(),
                        structured != null && structured.brainReflection() != null ? structured.brainReflection().intendedSpeechAct() : ""
                );
        String refereeDecision = llmOutputUnavailable
                ? "SKIPPED_LLM_UNAVAILABLE"
                : (usedSafeHandoffFallback
                    ? "FALLBACK_SAFE_HANDOFF"
                    : (repairApplied
                        ? "REPAIRED_OR_FALLBACK"
                        : (refereeViolations.isEmpty() ? "PASS" : "REPAIRED_ON_RETRY")));
        String fallbackReason = llmOutputUnavailable
                ? "LLM_OUTPUT_UNAVAILABLE"
                : (usedSafeHandoffFallback
                    ? "REFEREE_SAFE_HANDOFF"
                    : (usedStructuredFallbackOnLlmFailure ? "REFEREE_POST_REPAIR_FALLBACK" : "NONE"));
        String actualSentSpeechAct = usedSafeHandoffFallback
                ? CleanAgentOutputRefereeAndSanitizer.SPEECH_ACT_RESPECTED_PAUSE
                : (usedStructuredFallbackOnLlmFailure
                    ? CleanAgentOutputRefereeAndSanitizer.resolveFallbackActualSentSpeechAct(
                            originalVerbatim,
                            authoritativeBookingState,
                            quotedReplyResolution,
                            draft2Before
                    )
                    : generatedSpeechAct);
        // P3.3 Mục 2.4 (TC05): Ghi nhận các gói bot vừa giới thiệu ở lượt hiện tại vào candidateProductIds (BOT_PROPOSED / lastOfferedPackageIds)
        // nhưng tuyệt đối không gán vào selectedProductId khi khách chưa chọn.
        if (!combinedReplyText.isBlank() && allCatalog != null && !allCatalog.isEmpty() && authoritativeBookingState != null) {
            List<PackageCatalogService.PackageSummary> newlyOffered =
                    CleanAgentCatalogAndPromptBuilder.extractPresentedPackagesFromHistory(
                            List.of(new FanpageBotRequest.ConversationTurn("assistant", combinedReplyText)),
                            null,
                            allCatalog
                    );
            if (!newlyOffered.isEmpty()) {
                Set<String> mergedCandidates = new LinkedHashSet<>();
                if (authoritativeBookingState.candidateProductIds() != null) {
                    mergedCandidates.addAll(authoritativeBookingState.candidateProductIds());
                }
                for (PackageCatalogService.PackageSummary pkg : newlyOffered) {
                    if (pkg != null && pkg.productId() != null && !pkg.productId().isBlank()) {
                        mergedCandidates.add(pkg.productId());
                    }
                }
                authoritativeBookingState = new ConversationState.StructuredBookingState(
                        authoritativeBookingState.selectedProductId(),
                        authoritativeBookingState.selectedProductLabel(),
                        authoritativeBookingState.bookingDate(),
                        authoritativeBookingState.resolvedIsoDate(),
                        authoritativeBookingState.bookingTime(),
                        authoritativeBookingState.timezoneId(),
                        authoritativeBookingState.status(),
                        List.copyOf(mergedCandidates)
                );
            }
        }
        // Ghi nhận nguyên tử 5 tầng bộ nhớ, vòng đời Working Hypothesis, chiến thuật lượt trước & Session Commitments (Mục 4)
        if (agentBrainService != null) {
            agentBrainService.applyAciTurnMemoryUpdates(
                    pageId,
                    senderId,
                    sequentialTurnId,
                    originalVerbatim,
                    threeStreamInput.unverifiedParserCandidates(),
                    reflection,
                    authoritativeBookingState,
                    handoffDecision,
                    false,
                    verifiedPageSlaPolicy,
                    actualSentSpeechAct
            );
            agentBrainService.recordBookingState(pageId, senderId, authoritativeBookingState);
            agentBrainService.recordBookingExecutionMetadata(
                    pageId, senderId, authoritativeBookingState, bookingLifecycleEval.executionResult());
        }

        // Đồng bộ ConversationState (giữ opted_out = false cho mọi câu hội thoại thông thường như S06!)
        PrimaryActionRouter.RoutingDecision v2StateSyncDecision = new PrimaryActionRouter.RoutingDecision(
                shouldHandoff
                        ? PrimaryActionRouter.PrimaryAction.HARD_HANDOFF_COMPLAINT
                        : (replyHasPrice
                            ? PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE
                            : PrimaryActionRouter.PrimaryAction.RECOMMEND_ONE_PACKAGE),
                shouldHandoff
                        ? PrimaryActionRouter.PrimaryAction.HARD_HANDOFF_COMPLAINT
                        : (replyHasPrice
                            ? PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE
                            : PrimaryActionRouter.PrimaryAction.RECOMMEND_ONE_PACKAGE),
                false,
                Math.max(1, finalBubbles.size()),
                !reflection.customerUnderstanding().detectedIntent().isBlank()
                        ? reflection.customerUnderstanding().detectedIntent()
                        : reflection.priority().primary(),
                null
        );
        if (!finalBubbles.isEmpty()) {
            String verifiedSymptomDurationAfterSync = agentBrainService != null
                    ? agentBrainService.getVerifiedSymptomDuration(pageId, senderId)
                    : reflection.customerUnderstanding().verifiedSymptomDuration();
            String authoritativeSymptomDuration = (verifiedSymptomDurationAfterSync != null && !verifiedSymptomDurationAfterSync.isBlank())
                    ? verifiedSymptomDurationAfterSync
                    : symptomDurationBefore;
            // Unit P1.1: Ghi nhận trạng thái phiên tối giản (0 V1 regex ensureTurnEnriched / updateAfterSend)
            String authoritativeRegionForSession = (effectivePromptRegion != null && !effectivePromptRegion.isBlank())
                    ? effectivePromptRegion
                    : safeConv.preferredRegion();
            ConversationState safeConvWithCatalogAndRegion = safeConv.withCatalogAndRegion(allCatalog, authoritativeRegionForSession);
            ConversationState.recordAciV2TurnMinimalState(
                    pageId,
                    senderId,
                    request != null ? request.conversationId() : null,
                    safeConvWithCatalogAndRegion,
                    finalBubbles,
                    replyHasPrice,
                    authoritativeBookingState,
                    authoritativeSymptomDuration
            );
        }

        this.lastTurnBookingState = authoritativeBookingState;
        this.lastTurnBookingExecutionResult = bookingLifecycleEval.executionResult();
        this.lastAciReflection = reflection;
        this.lastHandoffDecision = handoffDecision;

        long plannedWaitMs = (quoteBubbleIndex >= 0 && quoteBubbleIndex + 1 < finalBubbles.size())
                ? finalBubbles.get(quoteBubbleIndex + 1).typingDelayMs()
                : 0L;
        long totalTurnMs = Math.max(1L, (System.nanoTime() - turnStartNs) / 1_000_000L);
        int reflectionTokens = CleanAgentOutputRefereeAndSanitizer.estimateReflectionTokensFromJsonOutput(
                effectiveStructuredOutputForProfiling,
                tokenUsage.completionTokens(),
                tokenUsage.reasoningTokens()
        );

        List<AgentSkillDto> activatedSkills = (usedSafeHandoffFallback
                || CleanAgentOutputRefereeAndSanitizer.isCommunicationStyleComplaintMessage(originalVerbatim))
                ? List.of()
                : resolveAciTurnActivatedSkills(
                        preSelectedTurnSkills,
                        preSelectedTurnSkills,
                        reflection,
                        authoritativeBookingState
                );
        // Unit P2.3: Đồng bộ nhất quán selectedSkills <-> activatedSkills để chỉ ghi nhận đúng skill thực sự phục vụ lượt hiện tại
        List<AgentSkillDto> selectedSkills = activatedSkills;
        if (agentSkillService != null && !activatedSkills.isEmpty()) {
            try {
                agentSkillService.recordSkillsApplied(activatedSkills);
            } catch (Exception e) {
                log.debug("[ACI-V2] Non-fatal: failed to record activated skills telemetry: {}", e.getMessage());
            }
        }

        FanpageBotResponse.TurnProfiling turnProfiling = new FanpageBotResponse.TurnProfiling(
                tokenUsage.promptTokens(),
                tokenUsage.completionTokens(),
                reflectionTokens,
                llmElapsedMs,
                retrievalMs,
                llmRegenerationCount,
                totalTurnMs
        );

        List<String> finalBubbleTexts = finalBubbles.stream()
                .map(FanpageBotResponse.ReplyBubble::text)
                .filter(java.util.Objects::nonNull)
                .toList();
        double sanitizerMutationRate = computeSanitizerMutationRate(rawLlmBubbleTexts, finalBubbleTexts);
        ThinkingModePolicy.AciTurnTelemetry aciTelemetry = new ThinkingModePolicy.AciTurnTelemetry(
                AdaptiveConversationIntelligence.CognitiveMode.LLM_COGNITIVE_V2,
                selectedThinkingMode,
                aciPrompt.length(),
                legacyEquivalentPromptChars,
                tokenUsage.promptTokens(),
                tokenUsage.completionTokens(),
                tokenUsage.reasoningTokens(),
                llmElapsedMs,
                quoteBubbleIndex,
                plannedWaitMs,
                totalTurnMs,
                repairApplied,
                refereeViolations,
                reflection.priority().primary(),
                reflection.strategy().chosen(),
                reflection.strategy().adaptation().name(),
                handoffDecision.resultingStatus().name(),
                authoritativeBookingState.status().name(),
                reflectionTokens,
                retrievalMs,
                llmRegenerationCount,
                retryTriggerReason,
                llmCallCount,
                turnDeadlineMs,
                rawLlmBubbleTexts,
                finalBubbleTexts,
                sanitizerMutationRate
        );
        this.lastAciTurnTelemetry = aciTelemetry;
        ThinkingModePolicy.recordAciTurnTelemetry(aciTelemetry);

        final String bubbleSourceTag = usedSafeHandoffFallback ? "SAFE_HANDOFF_FALLBACK" : "ACI_COGNITIVE_V2_LLM";
        List<String> telemetryValidationErrors = new ArrayList<>(refereeViolations);
        if (usedSafeHandoffFallback && !telemetryValidationErrors.contains("UNGROUNDED_CATALOG_PRICE")) {
            telemetryValidationErrors.add("UNGROUNDED_CATALOG_PRICE");
        }
        this.lastTurnTelemetry = new ThinkingModePolicy.TurnTelemetry(
                v2StateSyncDecision.effectiveResponseAction(),
                selectedThinkingMode,
                v2StateSyncDecision.detectedIntent(),
                List.of(),
                List.of(),
                !usedSafeHandoffFallback,
                usedSafeHandoffFallback ? "UNGROUNDED_CATALOG_PRICE" : null,
                llmElapsedMs,
                0L,
                0L,
                llmElapsedMs,
                plannedWaitMs,
                "ACI_" + reflection.priority().primary(),
                selectedThinkingMode.isDeepThink(),
                aciBudget.maxTokens(),
                reflectionTokens,
                0L,
                llmElapsedMs,
                quoteBubbleIndex >= 0 ? minDelayMs : 0,
                0L,
                (int) plannedWaitMs,
                false,
                promptBuildMs,
                retrievalMs,
                validationMs,
                0L,
                llmRegenerationCount,
                totalTurnMs,
                usedSafeHandoffFallback,
                usedSafeHandoffFallback ? "UNGROUNDED_CATALOG_PRICE" : null,
                bubbleSourceTag,
                repairApplied,
                llmRegenerationCount,
                usedSafeHandoffFallback ? "BLOCKED" : (repairApplied ? "REPAIRED_PASS" : "PASS"),
                List.copyOf(telemetryValidationErrors),
                "aci_cognitive_v2",
                llmClient != null ? llmClient.name() : "none",
                "chat",
                finalBubbles.stream().map(b -> bubbleSourceTag).toList()
        );
        ThinkingModePolicy.recordTurnTelemetry(this.lastTurnTelemetry);

        AdaptiveConversationIntelligence.CustomerUnderstandingDraft2Snapshot draft2After =
                agentBrainService != null ? agentBrainService.getCustomerUnderstandingDraft2Snapshot(pageId, senderId)
                        : AdaptiveConversationIntelligence.CustomerUnderstandingDraft2Snapshot.initial();
        String inferredPronounAfter = agentBrainService != null
                ? agentBrainService.getInferredCustomerPronoun(pageId, senderId)
                : reflection.customerUnderstanding().inferredCustomerPronoun();
        double inferredPronounConfAfter = agentBrainService != null
                ? agentBrainService.getInferredPronounConfidence(pageId, senderId)
                : reflection.customerUnderstanding().pronounConfidence();
        ConversationState postTurnSessionState = ConversationState.getSessionState(
                pageId,
                senderId,
                request != null ? request.conversationId() : null
        );
        String symptomDurationAfter = postTurnSessionState != null
                ? postTurnSessionState.knownDurationOfProblem()
                : (agentBrainService != null ? agentBrainService.getVerifiedSymptomDuration(pageId, senderId) : null);
        AdaptiveConversationIntelligence.WorkingHypothesisRecord workingHypothesisAfter = agentBrainService != null
                ? agentBrainService.getAciWorkingHypothesis(pageId, senderId)
                : AdaptiveConversationIntelligence.applyWorkingHypothesisLifecycle(
                        workingHypothesisBefore, reflection.workingHypothesis(), sequentialTurnId);
        int priceObjectionCountAfter = agentBrainService != null
                ? agentBrainService.getPriceObjectionCount(pageId, senderId)
                : (Boolean.TRUE.equals(reflection.customerUnderstanding().costConcernExpressed()) ? priceObjectionCountBefore + 1 : priceObjectionCountBefore);

        // Unit P1.1: Ánh xạ trực tiếp CustomerInsightDto từ kết quả nhận thức ACI V2 (bỏ qua CustomerInsightEngine V1 chạy ngầm trước LLM)
        CustomerInsightDto enrichedInsight = buildCustomerInsightFromAciTurn(
                pageId,
                senderId,
                safeHonorific,
                request != null ? request.previousTemperature() : null,
                request != null ? request.idleSeconds() : null,
                request != null ? request.action() : null,
                reflection,
                draft2After,
                workingHypothesisAfter,
                priceObjectionCountAfter,
                actualSentSpeechAct,
                authoritativeBookingState,
                safeConv,
                agentBrainService
        );
        String resolvedSalesStage = shouldHandoff
                ? "HUMAN_HANDOFF"
                : (authoritativeBookingState.status().isActiveOrConfirmed()
                    ? FanpageBotPromptBuilder.SalesStage.SOFT_BOOKING_CLOSE.code()
                    : (replyHasPrice || safeConv.priceDiscussed()
                        ? FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION.code()
                        : (salesStage != null ? salesStage.code() : FanpageBotPromptBuilder.SalesStage.DISCOVERY.code())));
        List<Integer> delays = finalBubbles.stream().map(FanpageBotResponse.ReplyBubble::typingDelayMs).toList();

        log.info("[ACI-V2] page_id={} sender_id={} turn={} thinkingMode={} promptChars={} legacyPromptChars={} promptBuildMs={} promptTokens={} completionTokens={} reasoningTokens={} reflectionTokens={} llmMs={} retrievalMs={} validationMs={} llmCallCount={} retryCount={} retryTriggerReason={} turnDeadlineMs={} sanitizerMutationRate={} totalTurnMs={} selectedSpeechAct={} generatedSpeechAct={} refereeDecision={} fallbackReason={} actualSentSpeechAct={} priority={} adaptation={} hypOp={} bookingStatus={} handoffStatus={} quoteBubbleIdx={} waitMs={} activatedSkills={} refereeViolations={}",
                pageId,
                senderId,
                sequentialTurnId,
                selectedThinkingMode,
                aciPrompt.length(),
                legacyEquivalentPromptChars,
                promptBuildMs,
                tokenUsage.promptTokens(),
                tokenUsage.completionTokens(),
                tokenUsage.reasoningTokens(),
                reflectionTokens,
                llmElapsedMs,
                retrievalMs,
                validationMs,
                llmCallCount,
                llmRegenerationCount,
                retryTriggerReason,
                turnDeadlineMs,
                String.format(Locale.ROOT, "%.3f", sanitizerMutationRate),
                totalTurnMs,
                selectedSpeechAct,
                generatedSpeechAct,
                refereeDecision,
                fallbackReason,
                actualSentSpeechAct,
                llmOutputUnavailable ? "LLM_OUTPUT_UNAVAILABLE" : reflection.priority().primary(),
                llmOutputUnavailable ? "LLM_OUTPUT_UNAVAILABLE" : reflection.strategy().adaptation(),
                llmOutputUnavailable ? "LLM_OUTPUT_UNAVAILABLE" : reflection.workingHypothesis().lifecycleOp(),
                authoritativeBookingState.status(),
                handoffDecision.resultingStatus(),
                quoteBubbleIndex,
                plannedWaitMs,
                activatedSkills.stream().map(AgentSkillDto::code).toList(),
                refereeViolations
        );
        log.info("[ACI-V2-PROFILING] page_id={} sender_id={} turn={} promptBuildMs={} inputTokens={} outputTokens={} reasoningTokens={} reflectionTokens={} modelLatencyMs={} retrievalLatencyMs={} validationMs={} retryCount={} retryTriggerReason={} totalTurnMs={} availableSkills={} selectedSkills={} activatedSkills={}",
                pageId,
                senderId,
                sequentialTurnId,
                promptBuildMs,
                tokenUsage.promptTokens(),
                tokenUsage.completionTokens(),
                tokenUsage.reasoningTokens(),
                reflectionTokens,
                llmElapsedMs,
                retrievalMs,
                validationMs,
                llmRegenerationCount,
                retryTriggerReason,
                totalTurnMs,
                availableSkills.size(),
                selectedSkills.stream().map(AgentSkillDto::code).toList(),
                activatedSkills.stream().map(AgentSkillDto::code).toList()
        );
        log.info("[ACI-V2-DRAFT2] page_id={} sender_id={} turn={} promptVersion={} promptSource={} thinkingMode={} modelLatencyMs={} layer1_context={{current_topic=\"{}\", topic_history={}, unresolved_requests={}, pending_references={}, last_bot_action=\"{}\"}} layer2_profile={{turn_intent=\"{}\", secondary_intents={}, conversation_state=\"{}\", conversation_goal=\"{}\", need_revision={}, confirmed_facts={}, self_reported_symptoms={}, barrier_hypothesis={{type=\"{}\", confidence=\"{}\", summary=\"{}\"}}, emotion=\"{}\", communication_preference=\"{}\", effective_turn_depth=\"{}\"}} layer3_commitment={{interested_package={}, selected_package={}, booking_status=\"{}\", booking_readiness=\"{}\"}} response_plan={{objective=\"{}\", strategy=\"{}\", tone=\"{}\", depth=\"{}\", next_action=\"{}\"}}",
                pageId,
                senderId,
                sequentialTurnId,
                CleanAgentCatalogAndPromptBuilder.getActiveAciPromptVersion(),
                CleanAgentCatalogAndPromptBuilder.getActiveAciPromptSource(),
                selectedThinkingMode,
                llmElapsedMs,
                draft2After.conversationalContext().currentTopic(),
                draft2After.conversationalContext().topicHistory(),
                draft2After.conversationalContext().unresolvedRequests(),
                draft2After.conversationalContext().pendingReferences(),
                draft2After.conversationalContext().lastBotAction(),
                draft2After.consultativeProfile().turnIntent(),
                draft2After.consultativeProfile().secondaryIntents(),
                draft2After.consultativeProfile().conversationState(),
                draft2After.consultativeProfile().conversationGoal(),
                reflection.needRevisionDetected(),
                draft2After.consultativeProfile().confirmedFacts(),
                draft2After.consultativeProfile().selfReportedSymptoms(),
                draft2After.consultativeProfile().barrierHypothesis().barrierType(),
                draft2After.consultativeProfile().barrierHypothesis().confidenceLevel(),
                draft2After.consultativeProfile().barrierHypothesis().summary(),
                draft2After.consultativeProfile().emotion(),
                draft2After.consultativeProfile().communicationPreference(),
                draft2After.consultativeProfile().effectiveTurnDepth(),
                draft2After.businessCommitment().interestedPackageId(),
                draft2After.businessCommitment().selectedPackageId(),
                draft2After.businessCommitment().bookingStatus(),
                draft2After.businessCommitment().bookingReadiness(),
                draft2After.lastResponsePlan().objective(),
                draft2After.lastResponsePlan().strategy(),
                draft2After.lastResponsePlan().tone(),
                draft2After.lastResponsePlan().depth(),
                draft2After.lastResponsePlan().nextAction()
        );
        log.info("[ACI-V2-BRAIN-TRACE] page_id={} sender_id={} turn={} rawVerbatimMessage=\"{}\" previousBotSpeechAct={} selectedSpeechAct={} generatedSpeechAct={} refereeDecision={} fallbackReason={} actualSentSpeechAct={} defaultCustomerPronoun=\"{}\" inferredCustomerPronounBefore={} inferredCustomerPronounAfter={} pronounConfidence={} effectiveCustomerPronoun=\"{}\" unverifiedParserCandidates={} symptomDurationBefore={} symptomDurationAfter={} workingHypothesisBefore={{content=\"{}\", op={}, conf={}, evidence={}}} workingHypothesisAfter={{content=\"{}\", lifecycle_op={}, confidence={}, evidence_turn_ids={}}} priority={{primary={}, secondary={}}} strategy={{chosen=\"{}\", adaptation={}, previous_outcome={}, evidence_turn_ids={}}} priceObjectionCountBefore={} priceObjectionCountAfter={} bubbleCount={} typingDelaysMs={} llmMs={} totalTurnMs={}",
                pageId,
                senderId,
                sequentialTurnId,
                originalVerbatim,
                previousBotSpeechAct,
                selectedSpeechAct,
                generatedSpeechAct,
                refereeDecision,
                fallbackReason,
                actualSentSpeechAct,
                defaultCustomerPronoun,
                priorInferredCustomerPronoun,
                inferredPronounAfter,
                String.format(Locale.ROOT, "%.2f", inferredPronounConfAfter),
                safeHonorific,
                threeStreamInput.unverifiedParserCandidates(),
                symptomDurationBefore,
                symptomDurationAfter,
                workingHypothesisBefore.content(),
                workingHypothesisBefore.lastLifecycleOp(),
                String.format(Locale.ROOT, "%.2f", workingHypothesisBefore.confidence()),
                workingHypothesisBefore.evidenceTurnIds(),
                workingHypothesisAfter.content(),
                workingHypothesisAfter.lastLifecycleOp(),
                String.format(Locale.ROOT, "%.2f", workingHypothesisAfter.confidence()),
                workingHypothesisAfter.evidenceTurnIds(),
                llmOutputUnavailable ? "LLM_OUTPUT_UNAVAILABLE" : reflection.priority().primary(),
                llmOutputUnavailable ? "LLM_OUTPUT_UNAVAILABLE" : reflection.priority().secondary(),
                llmOutputUnavailable ? "LLM_OUTPUT_UNAVAILABLE" : reflection.strategy().chosen(),
                llmOutputUnavailable ? "LLM_OUTPUT_UNAVAILABLE" : reflection.strategy().adaptation(),
                llmOutputUnavailable ? "LLM_OUTPUT_UNAVAILABLE" : reflection.strategy().previousOutcome(),
                reflection.strategy().evidenceTurnIds(),
                priceObjectionCountBefore,
                priceObjectionCountAfter,
                finalBubbles.size(),
                delays,
                llmElapsedMs,
                totalTurnMs
        );

        FanpageBotService.markProvenanceV38Logged();
        // ACI V3 — Stage P3 (Directive §4.9/§5.1): Decision Trace chuẩn hóa cho debug/audit.
        DecisionTrace decisionTrace = buildAciV3DecisionTrace(
                sequentialTurnId,
                originalVerbatim,
                reflection,
                draft2After,
                safePrior,
                postTurnSessionState,
                effectivePromptRegion,
                availableSkills,
                selectedSkills,
                activePageRules,
                turnRelevantRules,
                promptPrimaries,
                promptEconomicals,
                rawLlmBubbleTexts,
                finalBubbles,
                usedSafeHandoffFallback,
                effectivePersonaConfig.resolvedScopeTaxonomy()
        );
        return new FanpageBotResponse(
                combinedReplyText,
                finalBubbles,
                delays,
                resolvedSalesStage,
                computeConfidenceScore(fused != null ? fused : List.of()),
                shouldHandoff,
                shouldHandoff ? (handoffDecision.handoffCategory() != null ? handoffDecision.handoffCategory() : "HUMAN_HANDOFF") : null,
                shouldHandoff ? handoffDecision.handoffReason() : null,
                extractedLead,
                turnRelevantRules,
                enrichedInsight,
                activatedSkills,
                List.of("LLM_COGNITIVE_V2", "ACI_SINGLE_PASS")
        ).withSkillTiersAndProfiling(
                availableSkills,
                selectedSkills,
                activatedSkills,
                turnProfiling
        ).withDecisionTrace(decisionTrace);
    }

    /**
     * ACI V3 — Stage P3 (Directive §4.9/§5.1): Lắp {@link DecisionTrace} từ các biến quyết định
     * đã có sẵn tại cuối lượt ACI Cognitive V2. Không gọi LLM, không đọc DB; thuần ánh xạ dữ liệu
     * trong bộ nhớ của lượt hiện tại. Tin nhắn khách chỉ xuất hiện dưới dạng SHA-256 rút gọn (PII-safe).
     *
     * <p>Ngữ nghĩa {@code sanitizer_mutations}: phát hiện từ khác biệt quan sát được giữa raw LLM
     * bubbles và final bubbles — {@code false_apology_rewritten} khi bubble cuối chứa đúng văn bản
     * rewrite trung thực của False Apology Guard (§3.5); {@code cliche_empathy_stripped} khi raw LLM
     * output chứa văn mẫu đồng cảm bị cấm nhưng output cuối không còn; {@code price_grounding_applied}
     * khi câu trả lời cuối có báo giá và lượt không rơi vào safe-handoff do giá không grounded.</p>
     */
    static DecisionTrace buildAciV3DecisionTrace(
            String sequentialTurnId,
            String originalVerbatim,
            AdaptiveConversationIntelligence.AdaptiveCognitiveReflection reflection,
            AdaptiveConversationIntelligence.CustomerUnderstandingDraft2Snapshot draft2After,
            ConversationState priorState,
            ConversationState postTurnState,
            String effectivePromptRegion,
            List<AgentSkillDto> availableSkills,
            List<AgentSkillDto> selectedSkills,
            List<LearnedRuleDto> activePageRules,
            List<LearnedRuleDto> turnRelevantRules,
            List<PackageCatalogService.PackageSummary> promptPrimaries,
            List<PackageCatalogService.PackageSummary> promptEconomicals,
            List<String> rawLlmBubbleTexts,
            List<FanpageBotResponse.ReplyBubble> finalBubbles,
            boolean usedSafeHandoffFallback,
            List<String> scopeTaxonomy
    ) {
        CleanAgentOutputRefereeAndSanitizer.NegativeReactionSubtype subtype =
                CleanAgentOutputRefereeAndSanitizer.classifyNegativeReactionSubtype(originalVerbatim, scopeTaxonomy);

        String detectedIntent = "";
        if (reflection != null && reflection.customerUnderstanding() != null
                && reflection.customerUnderstanding().detectedIntent() != null
                && !reflection.customerUnderstanding().detectedIntent().isBlank()) {
            detectedIntent = reflection.customerUnderstanding().detectedIntent();
        } else if (draft2After != null && draft2After.consultativeProfile() != null) {
            detectedIntent = draft2After.consultativeProfile().turnIntent();
        }

        // fact_changes: extracted_pains lấy từ confirmed_facts_delta đã qua grounding (P1 §3.1);
        // known_* lấy từ session state SAU lượt; retracted = forbiddenScopes mới xuất hiện sau lượt.
        List<String> extractedPains = new ArrayList<>();
        if (reflection != null && reflection.confirmedFactsDelta() != null) {
            for (AdaptiveConversationIntelligence.FactDeltaProposal fd : reflection.confirmedFactsDelta()) {
                if (fd != null && "self_reported_symptom".equalsIgnoreCase(fd.key())
                        && fd.op() == AdaptiveConversationIntelligence.FactMutationOp.ADD
                        && fd.value() != null && !fd.value().isBlank()) {
                    extractedPains.add(fd.value());
                }
            }
        }
        List<String> knownSymptomsAfter = postTurnState != null && postTurnState.knownSymptoms() != null
                ? postTurnState.knownSymptoms() : List.of();
        List<String> knownBodyAreasAfter = postTurnState != null && postTurnState.knownBodyAreas() != null
                ? postTurnState.knownBodyAreas() : List.of();
        List<String> scopesBefore = priorState != null && priorState.knownBodyAreas() != null
                ? priorState.knownBodyAreas() : List.of();
        java.util.Set<String> forbiddenBefore = priorState != null && priorState.forbiddenScopes() != null
                ? priorState.forbiddenScopes() : java.util.Set.of();
        List<String> retractedBotAssumptions = new ArrayList<>();
        if (postTurnState != null && postTurnState.forbiddenScopes() != null) {
            for (String fs : postTurnState.forbiddenScopes()) {
                if (fs != null && !forbiddenBefore.contains(fs)) {
                    retractedBotAssumptions.add(fs);
                }
            }
        }
        VietnameseScopeSignalExtractor.ScopeSignalResult turnScopeSignal = VietnameseScopeSignalExtractor.analyze(
                originalVerbatim,
                scopeTaxonomy != null ? scopeTaxonomy : List.of()
        );
        if (turnScopeSignal != null) {
            for (String fs : turnScopeSignal.forbiddenScopes()) {
                if (fs != null && !forbiddenBefore.contains(fs) && !retractedBotAssumptions.contains(fs)) {
                    retractedBotAssumptions.add(fs);
                }
            }
            if (knownBodyAreasAfter.isEmpty() && !turnScopeSignal.confirmedScopes().isEmpty()) {
                knownBodyAreasAfter = new ArrayList<>(turnScopeSignal.confirmedScopes());
            }
        }
        String preferredRegion = effectivePromptRegion != null ? effectivePromptRegion
                : (postTurnState != null ? postTurnState.preferredRegion() : null);

        // selected_skills kèm lý do chọn; excluded_* chỉ đổ khi lượt là correction (gate P1 §3.4/P2 §4.1).
        List<DecisionTrace.SkillSelection> skillSelections = new ArrayList<>();
        List<String> activatedByReflection = reflection != null && reflection.activatedSkillCodes() != null
                ? reflection.activatedSkillCodes() : List.of();
        if (selectedSkills != null) {
            for (AgentSkillDto s : selectedSkills) {
                if (s == null) {
                    continue;
                }
                String reason = activatedByReflection.contains(s.code())
                        ? "ACTIVATED_BY_BRAIN_REFLECTION"
                        : "PRE_LLM_TURN_RELEVANT_SELECTION";
                skillSelections.add(new DecisionTrace.SkillSelection(s.code(), reason));
            }
        }
        List<String> excludedOnCorrection = new ArrayList<>();
        boolean correctionTurn = subtype == CleanAgentOutputRefereeAndSanitizer.NegativeReactionSubtype.CUSTOMER_CORRECTION
                || subtype == CleanAgentOutputRefereeAndSanitizer.NegativeReactionSubtype.OVER_INTRUSION;
        if (correctionTurn) {
            java.util.Set<String> selectedCodes = new HashSet<>();
            if (selectedSkills != null) {
                for (AgentSkillDto s : selectedSkills) {
                    if (s != null && s.code() != null) {
                        selectedCodes.add(s.code());
                    }
                }
            }
            if (availableSkills != null) {
                for (AgentSkillDto s : availableSkills) {
                    if (s != null && s.code() != null && !selectedCodes.contains(s.code())) {
                        excludedOnCorrection.add("skill:" + s.code() + " (CORRECTION_TURN_GATE)");
                    }
                }
            }
            java.util.Set<String> relevantRuleIds = new HashSet<>();
            if (turnRelevantRules != null) {
                for (LearnedRuleDto r : turnRelevantRules) {
                    if (r != null && r.id() != null) {
                        relevantRuleIds.add(r.id().toString());
                    }
                }
            }
            if (activePageRules != null) {
                for (LearnedRuleDto r : activePageRules) {
                    if (r != null && r.id() != null && !relevantRuleIds.contains(r.id().toString())) {
                        excludedOnCorrection.add("rule:" + r.id() + " (CORRECTION_TURN_GATE)");
                    }
                }
            }
        }

        // catalog_candidates: các biến thể đưa vào prompt lượt này (đã region-filter + dedup theo giá),
        // giới hạn 12 dòng để trace gọn (đủ cho audit ranking).
        List<DecisionTrace.CatalogCandidate> catalogCandidates = new ArrayList<>();
        java.util.Set<String> seenProductIds = new LinkedHashSet<>();
        List<PackageCatalogService.PackageSummary> candidateSource = new ArrayList<>();
        if (promptPrimaries != null) {
            candidateSource.addAll(promptPrimaries);
        }
        if (promptEconomicals != null) {
            candidateSource.addAll(promptEconomicals);
        }
        for (PackageCatalogService.PackageSummary p : candidateSource) {
            if (p == null || !seenProductIds.add(p.packageUid())) {
                continue;
            }
            catalogCandidates.add(new DecisionTrace.CatalogCandidate(
                    p.packageUid(),
                    p.region(),
                    p.priceDisplay(),
                    p.durationLabel()
            ));
            if (catalogCandidates.size() >= 12) {
                break;
            }
        }

        String recommendedAction = draft2After != null && draft2After.lastResponsePlan() != null
                ? draft2After.lastResponsePlan().nextAction() : null;
        String communicationStylePreference = draft2After != null && draft2After.consultativeProfile() != null
                ? draft2After.consultativeProfile().communicationPreference() : null;
        String turnDepth = draft2After != null && draft2After.consultativeProfile() != null
                ? draft2After.consultativeProfile().effectiveTurnDepth() : null;
        boolean explicitPriceDemand =
                CleanAgentOutputRefereeAndSanitizer.hasExplicitPriceDemandInMessage(originalVerbatim);

        boolean falseApologyRewritten = finalBubbles != null && finalBubbles.stream()
                .anyMatch(b -> b != null && b.text() != null
                        && b.text().contains("xin lỗi vì câu trả lời trước chưa bám sát"));
        boolean rawHadCliche = rawLlmBubbleTexts != null && rawLlmBubbleTexts.stream()
                .anyMatch(t -> t != null && CleanAgentOutputRefereeAndSanitizer.detectForbiddenThaoMaiEmpathyCliche(t));
        boolean finalStillHasCliche = finalBubbles != null && finalBubbles.stream()
                .anyMatch(b -> b != null && b.text() != null
                        && CleanAgentOutputRefereeAndSanitizer.detectForbiddenThaoMaiEmpathyCliche(b.text()));
        boolean clicheEmpathyStripped = rawHadCliche && !finalStillHasCliche;
        boolean priceGroundingApplied = !usedSafeHandoffFallback && finalBubbles != null && finalBubbles.stream()
                .anyMatch(b -> b != null && CleanAgentOutputRefereeAndSanitizer.hasQuotedPriceInText(b.text()));

        return new DecisionTrace(
                sequentialTurnId,
                DecisionTrace.sha256Short(originalVerbatim),
                detectedIntent,
                subtype.name(),
                new DecisionTrace.FactChanges(
                        extractedPains,
                        knownSymptomsAfter,
                        knownBodyAreasAfter,
                        retractedBotAssumptions,
                        preferredRegion
                ),
                scopesBefore,
                knownBodyAreasAfter,
                skillSelections,
                excludedOnCorrection,
                catalogCandidates,
                new DecisionTrace.ResponsePlanTrace(
                        recommendedAction,
                        communicationStylePreference,
                        turnDepth,
                        explicitPriceDemand
                ),
                new DecisionTrace.SanitizerMutations(
                        falseApologyRewritten,
                        clicheEmpathyStripped,
                        priceGroundingApplied
                )
        );
    }

    static List<AgentSkillDto> selectAciTurnCandidateSkills(
            List<AgentSkillDto> availableSkills,
            AdaptiveConversationIntelligence.AdaptiveCognitiveReflection reflection,
            ConversationState.StructuredBookingState bookingState,
            boolean replyHasPrice
    ) {
        if (availableSkills == null || availableSkills.isEmpty()) {
            return List.of();
        }
        if (reflection != null && reflection.customerUnderstanding() != null
                && ("SELF_IDENTIFY_GENDER".equalsIgnoreCase(reflection.customerUnderstanding().detectedIntent())
                    || "HUMAN_HANDOFF".equalsIgnoreCase(reflection.customerUnderstanding().detectedIntent()))) {
            return List.of();
        }
        String prio = (reflection != null && reflection.priority() != null && reflection.priority().primary() != null)
                ? reflection.priority().primary().toUpperCase(Locale.ROOT)
                : "";
        AdaptiveConversationIntelligence.StrategyAdaptationMode adaptation =
                (reflection != null && reflection.strategy() != null && reflection.strategy().adaptation() != null)
                        ? reflection.strategy().adaptation()
                        : AdaptiveConversationIntelligence.StrategyAdaptationMode.CONTINUATION;
        boolean costConcern = reflection != null
                && reflection.customerUnderstanding() != null
                && Boolean.TRUE.equals(reflection.customerUnderstanding().costConcernExpressed());
        boolean bookingActive = bookingState != null && bookingState.status().isActiveOrConfirmed();

        List<AgentSkillDto> candidates = new ArrayList<>();
        for (AgentSkillDto s : availableSkills) {
            if (s == null || !s.active()) continue;
            String code = (s.code() != null ? s.code() : "").toLowerCase(Locale.ROOT);
            String cat = (s.category() != null ? s.category() : "").toUpperCase(Locale.ROOT);
            boolean match = false;
            if ("POLITE_CLOSURE".equals(prio) || adaptation == AdaptiveConversationIntelligence.StrategyAdaptationMode.STOP) {
                match = code.contains("pause") || code.contains("graceful") || code.contains("close");
            } else if ("BOOKING_COMPLETION".equals(prio) || bookingActive) {
                match = code.contains("booking") || code.contains("schedul") || code.contains("nod");
            } else if (costConcern || "BUDGET_FIT".equals(prio) || "VALUE_ALIGNMENT".equals(prio)) {
                if (adaptation == AdaptiveConversationIntelligence.StrategyAdaptationMode.PIVOT || "BUDGET_FIT".equals(prio)) {
                    match = code.contains("economical") || code.contains("compare") || code.contains("objection") || code.contains("price");
                } else {
                    match = code.contains("objection") || code.contains("value") || code.contains("price");
                }
            } else if ("PRICE_INFORMATION".equals(prio) || replyHasPrice) {
                match = code.contains("compare") || code.contains("economical") || code.contains("price") || code.contains("discovery");
            } else {
                match = code.contains("discovery") || code.contains("empathy") || code.contains("scope") || "PSYCHOLOGY".equals(cat);
            }
            if (match && candidates.size() < 3) {
                candidates.add(s);
            }
        }
        if (candidates.isEmpty() && !availableSkills.isEmpty()) {
            candidates.add(availableSkills.get(0));
        }
        return List.copyOf(candidates);
    }

    static List<AgentSkillDto> resolveAciTurnActivatedSkills(
            List<AgentSkillDto> availableSkills,
            List<AgentSkillDto> selectedSkills,
            AdaptiveConversationIntelligence.AdaptiveCognitiveReflection reflection
    ) {
        return resolveAciTurnActivatedSkills(availableSkills, selectedSkills, reflection, null);
    }

    static List<AgentSkillDto> resolveAciTurnActivatedSkills(
            List<AgentSkillDto> availableSkills,
            List<AgentSkillDto> selectedSkills,
            AdaptiveConversationIntelligence.AdaptiveCognitiveReflection reflection,
            ConversationState.StructuredBookingState bookingState
    ) {
        if (availableSkills == null || availableSkills.isEmpty()) {
            return List.of();
        }
        boolean bookingActive = bookingState != null && bookingState.status().isActiveOrConfirmed();
        boolean readyToBook = bookingActive
                || (reflection != null
                    && ((reflection.customerUnderstanding() != null
                            && reflection.customerUnderstanding().bookingReadiness() == AdaptiveConversationIntelligence.BookingReadiness.READY_TO_BOOK)
                        || (reflection.priority() != null
                            && "BOOKING_COMPLETION".equalsIgnoreCase(reflection.priority().primary()))));

        List<AgentSkillDto> activated = new ArrayList<>();
        if (reflection != null && reflection.activatedSkillCodes() != null && !reflection.activatedSkillCodes().isEmpty()) {
            for (String rawCode : reflection.activatedSkillCodes()) {
                if (rawCode == null || rawCode.isBlank()) continue;
                String normCode = rawCode.strip().toLowerCase(Locale.ROOT);
                for (AgentSkillDto sk : availableSkills) {
                    if (sk == null) continue;
                    String skCode = sk.code() != null ? sk.code().strip().toLowerCase(Locale.ROOT) : "";
                    String skName = sk.name() != null ? sk.name().strip().toLowerCase(Locale.ROOT) : "";
                    if ((!skCode.isEmpty() && (skCode.equals(normCode) || normCode.contains(skCode)))
                            || (!skName.isEmpty() && skName.equals(normCode))) {
                        if (!readyToBook && isBookingOrSchedulingSkillCode(skCode)) {
                            break;
                        }
                        if (!activated.contains(sk)) {
                            activated.add(sk);
                        }
                        break;
                    }
                }
                if (activated.size() >= 2) {
                    break;
                }
            }
        }
        if (!activated.isEmpty()) {
            return List.copyOf(activated);
        }
        // Không tự động gán skill mặc định khi Brain không chọn hoặc chỉ trả lời vận hành / thông tin trực tiếp
        return List.of();
    }

    static List<AgentSkillDto> selectPreLlmTurnRelevantSkills(
            List<AgentSkillDto> availableSkills,
            String currentMessage,
            List<FanpageBotRequest.ConversationTurn> history,
            ConversationState.StructuredBookingState priorBookingState
    ) {
        if (availableSkills == null || availableSkills.isEmpty()) {
            return List.of();
        }
        String msg = currentMessage != null ? currentMessage.strip() : "";
        if (CleanAgentOutputRefereeAndSanitizer.isCommunicationStyleComplaintMessage(msg)
                || PromptSecurityGuard.isDirectJailbreakAttempt(msg)
                || (AdaptiveConversationIntelligence.isExplicitGenderOrPronounCorrectionMessage(msg)
                    && AdaptiveConversationIntelligence.classifyPriceIntentSubMode(msg) == AdaptiveConversationIntelligence.PriceIntentSubMode.NONE
                    && !AdaptiveConversationIntelligence.hasGenuinePriceObjectionCue(msg))) {
            return List.of();
        }
        // ACI V3 P1 §3.4: Lượt phản bác giả định sai của bot (CUSTOMER_CORRECTION / REJECTED_BOT_ASSUMPTION)
        // — discovery_empathy_and_scope không được chi phối (tránh gợi ý vùng cơ thể giả định lần nữa),
        // đồng thời loại trừ mọi skill chốt lịch/bán hàng pushy. Phản hồi lượt này thuộc về xin lỗi
        // trung thực + bám lại đúng lời khách, không phải khai thác thêm nhu cầu.
        if (!msg.isEmpty()) {
            VietnameseScopeSignalExtractor.ScopeSignalResult correctionSignal =
                    VietnameseScopeSignalExtractor.analyze(msg, List.of());
            if (correctionSignal.possibleCorrection() || correctionSignal.explicitCorrectionGroupD()
                    || AdaptiveConversationIntelligence.isCustomerCorrectionOrComponentRejectionMessage(msg)) {
                return List.of();
            }
        }
        String normLower = java.text.Normalizer.normalize(msg, java.text.Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        boolean bookingActive = priorBookingState != null && priorBookingState.status().isActiveOrConfirmed();
        boolean explicitStopRefusal = isExplicitConversationRefusalStopMessage(msg);
        boolean competitorPriceComparison = COMPETITOR_OR_EXTERNAL_PRICE_COMPARISON_PATTERN.matcher(msg).matches();
        boolean explicitBookingCue = !explicitStopRefusal && (bookingActive || normLower.matches(
                "(?isU).*\\b(đặt\\s+lịch|đăng\\s+ký(?:\\s+đặt)?(?:\\s+lịch|\\s+gói)?|giữ\\s+chỗ|giữ\\s+lịch|còn\\s+lịch|còn\\s+giường|còn\\s+phòng|tối\\s+nay|chiều\\s+nay|sáng\\s+mai|chiều\\s+mai|hôm\\s+nay|\\d{1,2}\\s*h\\d{0,2}|mấy\\s+giờ\\s+còn)\\b.*"
        ));
        boolean deferOrBareClose = explicitStopRefusal
                || (!competitorPriceComparison && (isHesitationOrDeferralMessage(msg)
                    || AdaptiveConversationIntelligence.isCustomerDeferralMessage(msg)
                    || (!bookingActive && (isBareAcknowledgmentMessage(msg)
                        || AdaptiveConversationIntelligence.isBarePauseOrAcknowledgementMessage(msg)))));
        boolean priceObjection = !explicitStopRefusal && isConfirmedCustomerPriceObjection(msg, true);
        boolean priceOrPackageQuery = normLower.matches(
                "(?isU).*\\b(giá|bao\\s+nhiêu|mấy\\s+tiền|chi\\s+phí|gói|liệu\\s+trình|khác\\s+gì|so\\s+sánh|\\d{2,4}\\s*k)\\b.*"
        );
        boolean hasPriorCustomerNeedInHistory = history != null && history.stream().anyMatch(t ->
                t != null && t.content() != null && !t.content().isBlank()
                        && !"assistant".equalsIgnoreCase(t.role()) && !"bot".equalsIgnoreCase(t.role())
                        && !CleanAgentCatalogAndPromptBuilder.isBareTurnOneGreetingOrConsultationRequest(t.content())
                        && t.content().strip().split("\\s+").length >= 3);
        boolean isScopeSelectionAfterNeedDiscovery = hasPriorCustomerNeedInHistory
                && normLower.matches("(?isU)^\\s*(?:dạ\\s+)?(?:cả\\s*(?:2|hai)|làm\\s+cả\\s*(?:2|hai)|cả\\s+hai\\s+luôn|gói\\s+(?:cơ\\s+bản|chuyên\\s+sâu|đó|này))\\s*[.!]*\\s*$");

        List<AgentSkillDto> matched = new ArrayList<>();
        List<AgentSkillDto> customFallback = new ArrayList<>();
        for (AgentSkillDto s : availableSkills) {
            if (s == null || !s.active()) continue;
            String code = (s.code() != null ? s.code() : "").strip().toLowerCase(Locale.ROOT);
            boolean isBookingSkill = isBookingOrSchedulingSkillCode(code);
            if (isBookingSkill && !explicitBookingCue) {
                continue;
            }
            boolean isPauseSkill = code.contains("pause") || code.contains("graceful") || code.contains("close");
            boolean isObjectionSkill = code.contains("objection") || code.contains("sensory_value");
            boolean isCompareOrPriceSkill = !isObjectionSkill
                    && (code.contains("economical") || code.contains("compare") || code.contains("price"));
            boolean isDiscoverySkill = code.contains("discovery") || code.contains("empathy") || code.contains("scope");
            boolean hasStandardTag = isBookingSkill || isPauseSkill || isObjectionSkill || isCompareOrPriceSkill || isDiscoverySkill;
            if (!hasStandardTag) {
                customFallback.add(s);
            }

            boolean keep = false;
            if (deferOrBareClose) {
                keep = isPauseSkill;
            } else if (explicitBookingCue) {
                keep = isBookingSkill || (!hasPriorCustomerNeedInHistory && isDiscoverySkill) || (priceOrPackageQuery && isCompareOrPriceSkill);
            } else if (priceObjection) {
                keep = isObjectionSkill || isCompareOrPriceSkill;
            } else if (isScopeSelectionAfterNeedDiscovery) {
                keep = isCompareOrPriceSkill;
            } else if (priceOrPackageQuery) {
                keep = isCompareOrPriceSkill || (!hasPriorCustomerNeedInHistory && isDiscoverySkill);
            } else {
                keep = isDiscoverySkill || (hasPriorCustomerNeedInHistory && isCompareOrPriceSkill);
            }
            if (keep && matched.size() < 3) {
                matched.add(s);
            }
        }
        for (AgentSkillDto custom : customFallback) {
            if (matched.size() >= 3) break;
            if (!matched.contains(custom)) {
                matched.add(custom);
            }
        }
        return List.copyOf(matched);
    }

    private static boolean isBookingOrSchedulingSkillCode(String lowerCode) {
        if (lowerCode == null || lowerCode.isBlank()) {
            return false;
        }
        return lowerCode.contains("booking")
                || lowerCode.contains("schedul")
                || lowerCode.contains("warm_nod");
    }

    static ThinkingMode selectAciAdaptiveThinkingMode(
            String originalMessage,
            AdaptiveConversationIntelligence.ThreeStreamTurnInput threeStreamInput,
            List<AdaptiveConversationIntelligence.CommitmentRecord> sessionCommitments
    ) {
        ThinkingModePolicy.BenchmarkConfigMode bench = ThinkingModePolicy.getBenchmarkConfigMode();
        if (bench == ThinkingModePolicy.BenchmarkConfigMode.FORCE_NO_THINK_A) {
            return ThinkingMode.FAST_NO_THINK;
        }
        if (bench == ThinkingModePolicy.BenchmarkConfigMode.FORCE_ALWAYS_THINK_B) {
            return ThinkingMode.OBJECTION_REFRAME_THINK;
        }
        String msg = originalMessage != null ? originalMessage.strip() : "";
        boolean multiBubbleBurst = threeStreamInput != null && threeStreamInput.originalBurstBubbles().size() >= 2;
        boolean hasPendingCommitments = sessionCommitments != null && sessionCommitments.stream()
                .anyMatch(c -> c != null && c.commitmentStatus() == AdaptiveConversationIntelligence.CommitmentStatus.pending);
        if (!multiBubbleBurst
                && !hasPendingCommitments
                && (CleanAgentOutputRefereeAndSanitizer.isCommunicationStyleComplaintMessage(msg)
                    || isExplicitConversationRefusalStopMessage(msg))) {
            return ThinkingMode.FAST_NO_THINK;
        }
        if (multiBubbleBurst
                || hasPendingCommitments
                || AdaptiveConversationIntelligence.isPaymentClaimMessage(msg)
                || AdaptiveConversationIntelligence.isStaffPromiseClaimMessage(msg)
                || AdaptiveConversationIntelligence.isConcreteServiceIncidentMessage(msg)
                || PrimaryActionRouter.isFrustrationMessage(msg)
                || isConfirmedCustomerPriceObjection(msg, true)) {
            return ThinkingMode.OBJECTION_REFRAME_THINK;
        }
        return ThinkingMode.FAST_NO_THINK;
    }

    private static boolean isUpstreamLlmClientErrorPayload(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String stripped = raw.strip();
        if (stripped.matches("(?s)^\\[[A-Za-z0-9_.-]+-error\\].*")) {
            return true;
        }
        return !stripped.startsWith("{")
                && stripped.contains("Không thể kết nối hoặc nhận phản hồi từ");
    }

    private static boolean isLeakedReasoningWithoutJsonContract(
            CleanAgentOutputRefereeAndSanitizer.StructuredLlmOutput structured,
            String sanitizedReply
    ) {
        if (structured != null && structured.parsedFromJson()) {
            return false;
        }
        if (sanitizedReply == null || sanitizedReply.isBlank()) {
            return false;
        }
        String low = sanitizedReply.strip().toLowerCase(Locale.ROOT);
        return low.contains("<think>")
                || low.startsWith("we need ")
                || low.startsWith("let's analyze")
                || low.contains("need produce final json")
                || low.contains("user's request")
                || low.contains("brain_reflection");
    }

    private static ReasoningBudget buildAciReasoningBudget(ThinkingMode mode) {
        ThinkingMode effective = mode != null ? mode : ThinkingMode.FAST_NO_THINK;
        boolean deep = effective.isDeepThink();
        com.example.docmind.service.reasoning.QueryComplexity complexity = deep
                ? com.example.docmind.service.reasoning.QueryComplexity.DEEP_REASONING
                : com.example.docmind.service.reasoning.QueryComplexity.SIMPLE;
        // Unit P3.6 (P0): Đã kiểm nghiệm thực nghiệm A/B với qwen3.8-flash:
        // reasoning_effort="none" trên FAST_NO_THINK giảm ~64% reasoningTokens (~1200 tokens + ~650 tokens JSON < 2500 maxTokens)
        // và reasoning_effort="low" trên DEEP_THINK (~2050 tokens + ~650 tokens JSON < 3200 maxTokens)
        int maxTokens = deep ? 3200 : 2500;
        String effort = deep ? "low" : "none";
        int timeoutSec = deep ? 90 : 75;
        return new ReasoningBudget(
                complexity,
                effort,
                timeoutSec,
                complexity.getMinLatencyMs(),
                complexity.getMaxLatencyMs(),
                maxTokens,
                ReasoningBudget.SALES_CHAT_TEMPERATURE,
                ReasoningBudget.SALES_TOP_P,
                ReasoningBudget.SALES_REPEAT_PENALTY,
                effective
        );
    }

    private static LlmTokenUsageSnapshot extractLlmTokenUsage(LlmClient client) {
        if (client == null) {
            return LlmTokenUsageSnapshot.zero();
        }
        LlmClient target = client;
        if (target instanceof com.example.docmind.service.llm.ResilientFallbackLlmClient resilient) {
            target = resilient.getLastUsedClient();
        }
        if (target instanceof com.example.docmind.service.llm.OpenAiCompatibleLlmClient openAi) {
            com.example.docmind.service.llm.OpenAiCompatibleLlmClient.OpenAiCallTelemetry tel = openAi.getLastCallTelemetry();
            if (tel != null) {
                return new LlmTokenUsageSnapshot(tel.promptTokens(), tel.completionTokens(), tel.reasoningTokens());
            }
        }
        if (target instanceof com.example.docmind.service.llm.OllamaLlmClient ollama) {
            com.example.docmind.service.llm.OllamaLlmClient.OllamaCallTelemetry tel = ollama.getLastCallTelemetry();
            if (tel != null) {
                return new LlmTokenUsageSnapshot(tel.promptEvalCount(), tel.evalCount(), tel.thinkingChars());
            }
        }
        return LlmTokenUsageSnapshot.zero();
    }

    /**
     * Unit P1.1: Ánh xạ trực tiếp {@link CustomerInsightDto} từ {@link AdaptiveConversationIntelligence.AdaptiveCognitiveReflection}
     * và bộ nhớ 5 tầng của {@code ACI V2}, loại bỏ hoàn toàn bước chạy {@code CustomerInsightEngine.analyzeInsight} V1 trước LLM.
     */
    static CustomerInsightDto buildCustomerInsightFromAciTurn(
            UUID pageId,
            String senderId,
            String safeHonorific,
            Integer previousTemperatureInput,
            AdaptiveConversationIntelligence.AdaptiveCognitiveReflection reflection,
            AdaptiveConversationIntelligence.CustomerUnderstandingDraft2Snapshot draft2After,
            AdaptiveConversationIntelligence.WorkingHypothesisRecord workingHypothesisAfter,
            int priceObjectionCountAfter,
            String actualSentSpeechAct,
            ConversationState.StructuredBookingState authoritativeBookingState,
            ConversationState safeConv,
            AgentBrainService agentBrainService
    ) {
        return buildCustomerInsightFromAciTurn(
                pageId,
                senderId,
                safeHonorific,
                previousTemperatureInput,
                null,
                null,
                reflection,
                draft2After,
                workingHypothesisAfter,
                priceObjectionCountAfter,
                actualSentSpeechAct,
                authoritativeBookingState,
                safeConv,
                agentBrainService
        );
    }

    static CustomerInsightDto buildCustomerInsightFromAciTurn(
            UUID pageId,
            String senderId,
            String safeHonorific,
            Integer previousTemperatureInput,
            Integer idleSecondsInput,
            String requestAction,
            AdaptiveConversationIntelligence.AdaptiveCognitiveReflection reflection,
            AdaptiveConversationIntelligence.CustomerUnderstandingDraft2Snapshot draft2After,
            AdaptiveConversationIntelligence.WorkingHypothesisRecord workingHypothesisAfter,
            int priceObjectionCountAfter,
            String actualSentSpeechAct,
            ConversationState.StructuredBookingState authoritativeBookingState,
            ConversationState safeConv,
            AgentBrainService agentBrainService
    ) {
        AdaptiveConversationIntelligence.AdaptiveCognitiveReflection ref = reflection != null
                ? reflection
                : AdaptiveConversationIntelligence.AdaptiveCognitiveReflection.empty();
        AdaptiveConversationIntelligence.CustomerUnderstanding cu = ref.customerUnderstanding();
        AdaptiveConversationIntelligence.BookingReadiness readiness = cu.bookingReadiness();
        AdaptiveConversationIntelligence.BuyingSignal signal = cu.buyingSignal();
        boolean costConcern = Boolean.TRUE.equals(cu.costConcernExpressed())
                || "PRICE".equalsIgnoreCase(ref.barrierType())
                || priceObjectionCountAfter > 0;
        boolean bookingActive = authoritativeBookingState != null
                && authoritativeBookingState.status().isActiveOrConfirmed();
        String primaryPrio = ref.priority().primary();
        boolean stopOrClosure = "POLITE_CLOSURE".equalsIgnoreCase(primaryPrio)
                || ref.strategy().adaptation() == AdaptiveConversationIntelligence.StrategyAdaptationMode.STOP;
        String emotionLabel = (draft2After != null && draft2After.consultativeProfile() != null
                && draft2After.consultativeProfile().emotion() != null
                && draft2After.consultativeProfile().emotion().primary() != null
                && !"UNKNOWN".equalsIgnoreCase(draft2After.consultativeProfile().emotion().primary()))
                ? draft2After.consultativeProfile().emotion().primary()
                : (cu.emotionalState() != null && cu.emotionalState().primary() != null ? cu.emotionalState().primary() : "");

        String buyerArchetype;
        String archetypeLabel;
        if ("SAFETY".equalsIgnoreCase(primaryPrio)) {
            buyerArchetype = "SAFETY_CONSCIOUS";
            archetypeLabel = "Cần lưu ý sức khỏe & an toàn";
        } else if (bookingActive || readiness == AdaptiveConversationIntelligence.BookingReadiness.READY_TO_BOOK
                || signal == AdaptiveConversationIntelligence.BuyingSignal.ACTION
                || signal == AdaptiveConversationIntelligence.BuyingSignal.STRONG) {
            buyerArchetype = "DECISIVE_BUYER";
            archetypeLabel = "Khách sẵn sàng trải nghiệm / đặt lịch";
        } else if (costConcern) {
            buyerArchetype = "PRICE_CONSCIOUS";
            archetypeLabel = "Khách hàng cân nhắc chi phí";
        } else if (emotionLabel.toUpperCase(Locale.ROOT).contains("FRUSTRAT")
                || emotionLabel.toUpperCase(Locale.ROOT).contains("IRRITAT")
                || emotionLabel.toUpperCase(Locale.ROOT).contains("DISAPPOINT")) {
            buyerArchetype = "SKEPTICAL";
            archetypeLabel = "Cần thấu cảm & điều chỉnh giao tiếp";
        } else if (readiness == AdaptiveConversationIntelligence.BookingReadiness.CONSIDERING) {
            buyerArchetype = "VALUE_CONSCIOUS";
            archetypeLabel = "Khách hàng đang cân nhắc giải pháp";
        } else {
            buyerArchetype = "EXPLORING_CONSULTATION";
            archetypeLabel = "Đang tìm hiểu dịch vụ";
        }

        // ACI V3: ánh xạ buyerArchetype sang BehaviorBuyingState dựa trên tín hiệu quan sát được của lượt hiện tại.
        boolean hasDisclosedNeed = draft2After != null
                && draft2After.consultativeProfile() != null
                && draft2After.consultativeProfile().selfReportedSymptoms() != null
                && !draft2After.consultativeProfile().selfReportedSymptoms().isEmpty();
        AdaptiveConversationIntelligence.BehaviorBuyingState behaviorBuyingState;
        if (stopOrClosure) {
            behaviorBuyingState = AdaptiveConversationIntelligence.BehaviorBuyingState.DEFERRAL;
        } else if ("SKEPTICAL".equals(buyerArchetype)) {
            behaviorBuyingState = AdaptiveConversationIntelligence.BehaviorBuyingState.CUSTOMER_CORRECTION;
        } else if ("PRICE_CONSCIOUS".equals(buyerArchetype)) {
            behaviorBuyingState = AdaptiveConversationIntelligence.BehaviorBuyingState.OBJECTION_HESITATION;
        } else if (bookingActive
                || readiness == AdaptiveConversationIntelligence.BookingReadiness.READY_TO_BOOK
                || signal == AdaptiveConversationIntelligence.BuyingSignal.ACTION) {
            behaviorBuyingState = AdaptiveConversationIntelligence.BehaviorBuyingState.BOOKING_INTENT;
        } else if (signal == AdaptiveConversationIntelligence.BuyingSignal.STRONG) {
            behaviorBuyingState = AdaptiveConversationIntelligence.BehaviorBuyingState.TRIAL_PREFERENCE;
        } else if (readiness == AdaptiveConversationIntelligence.BookingReadiness.CONSIDERING
                || "VALUE_CONSCIOUS".equals(buyerArchetype)) {
            behaviorBuyingState = AdaptiveConversationIntelligence.BehaviorBuyingState.SOLUTION_EVALUATION;
        } else if (hasDisclosedNeed || "SAFETY_CONSCIOUS".equals(buyerArchetype)) {
            behaviorBuyingState = AdaptiveConversationIntelligence.BehaviorBuyingState.NEED_DISCLOSURE;
        } else {
            behaviorBuyingState = AdaptiveConversationIntelligence.BehaviorBuyingState.EXPLORATION;
        }
        log.debug("[ACI-V3] buyerArchetype={} -> behaviorBuyingState={}", buyerArchetype, behaviorBuyingState);

        Integer prevTemp = previousTemperatureInput;
        int buyingTemp;
        if (bookingActive || signal == AdaptiveConversationIntelligence.BuyingSignal.ACTION) {
            buyingTemp = Math.min(100, Math.max(85, prevTemp != null ? prevTemp + 3 : 85));
        } else if (readiness == AdaptiveConversationIntelligence.BookingReadiness.READY_TO_BOOK
                || signal == AdaptiveConversationIntelligence.BuyingSignal.STRONG) {
            buyingTemp = Math.min(100, Math.max(78, prevTemp != null ? prevTemp + 2 : 78));
        } else if (stopOrClosure) {
            buyingTemp = 36;
        } else if (signal == AdaptiveConversationIntelligence.BuyingSignal.MODERATE
                || readiness == AdaptiveConversationIntelligence.BookingReadiness.CONSIDERING) {
            buyingTemp = costConcern ? 52 : 62;
        } else if (signal == AdaptiveConversationIntelligence.BuyingSignal.WEAK) {
            buyingTemp = 44;
        } else {
            buyingTemp = 48;
        }

        boolean isIdleDecayTurn = "REENGAGE".equalsIgnoreCase(requestAction)
                || (idleSecondsInput != null && idleSecondsInput >= 180);
        if (isIdleDecayTurn) {
            int baseTemp = prevTemp != null ? prevTemp : buyingTemp;
            int idleDecay = (idleSecondsInput != null && idleSecondsInput > 0)
                    ? Math.max(15, (int) Math.round(7.5 * Math.log1p(idleSecondsInput / 30.0)))
                    : 18;
            buyingTemp = Math.max(0, Math.min(100, baseTemp - idleDecay));
        }
        int tempDelta = prevTemp != null ? (buyingTemp - prevTemp) : 0;
        String momentum;
        String decayReason = null;
        if (isIdleDecayTurn) {
            momentum = "COOLING_IDLE";
            decayReason = "COOLING_IDLE";
        } else if (stopOrClosure || tempDelta <= -8) {
            momentum = "COOLING_HALF_HEARTED";
            decayReason = stopOrClosure ? "POLITE_DEFERRAL_OR_CLOSURE" : "COST_OR_HESITATION_COOLING";
        } else if (tempDelta >= 8 || buyingTemp >= 75) {
            momentum = "HEATING_UP";
        } else {
            momentum = "STABLE";
        }
        String temperatureLabel = buyingTemp >= 72 ? "HOT" : (buyingTemp >= 45 ? "WARM" : "COLD");

        Set<String> painsSet = new LinkedHashSet<>();
        if (draft2After != null && draft2After.consultativeProfile() != null) {
            if (draft2After.consultativeProfile().selfReportedSymptoms() != null) {
                for (AdaptiveConversationIntelligence.VersionedCustomerFact s : draft2After.consultativeProfile().selfReportedSymptoms()) {
                    if (s != null
                            && !"NEGATED".equalsIgnoreCase(s.status())
                            && s.validity() != AdaptiveConversationIntelligence.FactValidity.NEGATED
                            && s.validity() != AdaptiveConversationIntelligence.FactValidity.SUPERSEDED
                            && s.validity() != AdaptiveConversationIntelligence.FactValidity.RESOLVED
                            && s.value() != null && !s.value().isBlank()) {
                        painsSet.add(s.value().strip());
                    }
                }
            }
            if (draft2After.consultativeProfile().confirmedFacts() != null) {
                for (AdaptiveConversationIntelligence.VersionedCustomerFact f : draft2After.consultativeProfile().confirmedFacts()) {
                    if (f != null
                            && !"NEGATED".equalsIgnoreCase(f.status())
                            && f.validity() != AdaptiveConversationIntelligence.FactValidity.NEGATED
                            && f.validity() != AdaptiveConversationIntelligence.FactValidity.SUPERSEDED
                            && f.validity() != AdaptiveConversationIntelligence.FactValidity.RESOLVED
                            && f.value() != null && !f.value().isBlank()) {
                        painsSet.add(f.value().strip());
                    }
                }
            }
        }
        if (agentBrainService != null && (pageId != null || senderId != null)) {
            for (String rf : agentBrainService.getRecalledVerifiedFacts(pageId, senderId)) {
                if (rf != null && !rf.isBlank()) painsSet.add(rf.strip());
            }
        }
        if (painsSet.isEmpty() && ref.confirmedFactsDelta() != null) {
            for (AdaptiveConversationIntelligence.FactDeltaProposal fd : ref.confirmedFactsDelta()) {
                if (fd != null
                        && fd.effectiveValidity() != AdaptiveConversationIntelligence.FactValidity.NEGATED
                        && fd.effectiveValidity() != AdaptiveConversationIntelligence.FactValidity.SUPERSEDED
                        && fd.effectiveValidity() != AdaptiveConversationIntelligence.FactValidity.RESOLVED
                        && fd.value() != null && !fd.value().isBlank()) {
                    painsSet.add(fd.value().strip());
                }
            }
        }

        List<String> hiddenConcerns = new ArrayList<>();
        if (draft2After != null && draft2After.consultativeProfile() != null
                && draft2After.consultativeProfile().barrierHypothesis() != null
                && draft2After.consultativeProfile().barrierHypothesis().summary() != null
                && !draft2After.consultativeProfile().barrierHypothesis().summary().isBlank()) {
            hiddenConcerns.add(draft2After.consultativeProfile().barrierHypothesis().summary().strip());
        } else if (ref.workingHypothesis() != null
                && ref.workingHypothesis().content() != null
                && !ref.workingHypothesis().content().isBlank()) {
            hiddenConcerns.add(ref.workingHypothesis().content().strip());
        } else if (costConcern) {
            hiddenConcerns.add("Đắn đo mức giá / ngân sách");
        }

        List<String> careNotes = (agentBrainService != null && (pageId != null || senderId != null))
                ? agentBrainService.getRecalledCareNotes(pageId, senderId)
                : List.of();
        String strategyText = !ref.strategy().chosen().isBlank()
                ? ref.strategy().chosen()
                : (draft2After != null && draft2After.lastResponsePlan() != null
                    ? draft2After.lastResponsePlan().strategy()
                    : "");
        String workingHypContent = (workingHypothesisAfter != null && workingHypothesisAfter.content() != null)
                ? workingHypothesisAfter.content()
                : "";
        boolean hasDownsell = (safeConv != null && safeConv.downsellOffered())
                || (agentBrainService != null && agentBrainService.hasRecalledPresentedDownsell(pageId, senderId));

        return new CustomerInsightDto(
                buyerArchetype,
                archetypeLabel,
                buyingTemp,
                prevTemp,
                tempDelta,
                momentum,
                temperatureLabel,
                decayReason,
                (safeHonorific != null && !safeHonorific.isBlank()) ? safeHonorific : "mình",
                List.copyOf(painsSet),
                List.copyOf(hiddenConcerns),
                strategyText,
                careNotes,
                workingHypContent,
                actualSentSpeechAct != null ? actualSentSpeechAct : "",
                priceObjectionCountAfter,
                costConcern,
                hasDownsell
        );
    }

    public static double computeSanitizerMutationRate(List<String> rawBubbles, List<String> finalBubbles) {
        String rawJoined = rawBubbles == null ? "" : String.join(" ", rawBubbles).replaceAll("\\s+", " ").strip();
        String finalJoined = finalBubbles == null ? "" : String.join(" ", finalBubbles).replaceAll("\\s+", " ").strip();
        if (rawJoined.isEmpty() && finalJoined.isEmpty()) {
            return 0.0;
        }
        if (rawJoined.equals(finalJoined)) {
            return 0.0;
        }
        int maxLen = Math.max(rawJoined.length(), finalJoined.length());
        if (maxLen == 0) {
            return 0.0;
        }
        int commonPrefix = 0;
        int minLen = Math.min(rawJoined.length(), finalJoined.length());
        while (commonPrefix < minLen && rawJoined.charAt(commonPrefix) == finalJoined.charAt(commonPrefix)) {
            commonPrefix++;
        }
        int commonSuffix = 0;
        while (commonSuffix < (minLen - commonPrefix)
                && rawJoined.charAt(rawJoined.length() - 1 - commonSuffix) == finalJoined.charAt(finalJoined.length() - 1 - commonSuffix)) {
            commonSuffix++;
        }
        int unchangedChars = commonPrefix + commonSuffix;
        double rate = 1.0 - ((double) unchangedChars / (double) maxLen);
        return Math.max(0.0, Math.min(1.0, rate));
    }

    private static List<FanpageBotResponse.ReplyBubble> buildSafeAciAuthorityFallbackBubbles(
            List<String> violations,
            String selfPronoun,
            String custPronoun,
            ConversationState.StructuredBookingState bookingState,
            AdaptiveConversationIntelligence.ConditionalHandoffDecision handoffDecision,
            String originalCustomerMessage
    ) {
        if (violations != null && violations.stream().anyMatch(v -> v != null
                && (v.startsWith("INTERNAL_POLICY_OR_PROMPT_LEAKAGE")
                    || v.startsWith("CROSS_TENANT_BRAND_OR_CATALOG_LEAKAGE")))) {
            String lowerSelf = (selfPronoun != null && !selfPronoun.isBlank())
                    ? selfPronoun.strip().toLowerCase(Locale.ROOT)
                    : "em";
            String neutralScopeText = "Dạ " + lowerSelf
                    + " chỉ hỗ trợ cung cấp thông tin dịch vụ và lịch hẹn thực tế của bên mình thôi ạ.";
            return List.of(new FanpageBotResponse.ReplyBubble(neutralScopeText, 0));
        }
        if (violations != null && violations.contains("UNCONFIRMED_SYMPTOM_PREMATURE_SERVICE_PITCH")) {
            String text = "Dạ " + selfPronoun + " ghi nhận tình trạng " + custPronoun
                    + " vừa chia sẻ ạ. Nếu triệu chứng hay tái diễn hoặc kéo dài " + custPronoun
                    + " nên lưu ý kiểm tra sức khỏe để yên tâm hơn, còn hiện tại " + custPronoun
                    + " đang muốn tìm hiểu thông tin gì để " + selfPronoun + " hỗ trợ đúng ý " + custPronoun + " ạ?";
            return List.of(new FanpageBotResponse.ReplyBubble(text, 0));
        }
        if (AdaptiveConversationIntelligence.isPaymentClaimMessage(originalCustomerMessage)
                || violations.contains("UNVERIFIED_PAYMENT_CONFIRMATION_CLAIM")) {
            String bookingSuffix = (bookingState != null && bookingState.bookingTime() != null)
                    ? (" Thông tin yêu cầu lịch hẹn lúc " + bookingState.bookingTime()
                        + (bookingState.bookingDate() != null ? (" " + bookingState.bookingDate()) : "")
                        + " của " + custPronoun + " vẫn đang được lưu giữ nguyên ạ.")
                    : "";
            String text = "Dạ " + selfPronoun + " đã ghi nhận thông tin " + custPronoun
                    + " báo chuyển khoản và đang chuyển bộ phận thu ngân đối soát giao dịch thực tế ạ."
                    + bookingSuffix
                    + " Ngay khi có kết quả đối soát, bên " + selfPronoun + " sẽ phản hồi " + custPronoun + " sớm nhất nhé ạ.";
            return List.of(new FanpageBotResponse.ReplyBubble(text, 0));
        }
        if (handoffDecision != null && handoffDecision.resultingStatus() == AdaptiveConversationIntelligence.HandoffLifecycleStatus.HANDOFF_FAILED) {
            String text = "Dạ " + selfPronoun + " đã ghi nhận đầy đủ thông tin của " + custPronoun
                    + " ạ. Hiện hệ thống chuyển tiếp tự động đang tạm gián đoạn nên " + selfPronoun
                    + " chưa thể bàn giao phiếu ngay lúc này; " + custPronoun
                    + " vui lòng để lại số điện thoại hoặc nhắn thêm nội dung cần ưu tiên để bên " + selfPronoun + " hỗ trợ trực tiếp nhé ạ.";
            return List.of(new FanpageBotResponse.ReplyBubble(text, 0));
        }
        if (bookingState != null && bookingState.status() == ConversationState.BookingStatus.AWAITING_AVAILABILITY_CHECK) {
            String pkgPart = bookingState.selectedProductLabel() != null ? (" cho gói " + bookingState.selectedProductLabel()) : "";
            String timePart = bookingState.bookingTime() != null
                    ? (" vào lúc " + bookingState.bookingTime() + (bookingState.bookingDate() != null ? (" " + bookingState.bookingDate()) : ""))
                    : "";
            String text = "Dạ " + selfPronoun + " đã ghi nhận yêu cầu đặt lịch" + pkgPart + timePart + " của " + custPronoun
                    + " và đang kiểm tra lịch trống thực tế tại cơ sở. Bên " + selfPronoun + " sẽ báo lại kết quả kiểm tra lịch sớm nhất cho " + custPronoun + " nhé ạ.";
            return List.of(new FanpageBotResponse.ReplyBubble(text, 0));
        }
        String text = "Dạ " + selfPronoun + " đã ghi nhận thông tin của " + custPronoun
                + " và đang phối hợp bộ phận phụ trách kiểm tra chi tiết để phản hồi chính xác nhất cho " + custPronoun + " nhé ạ.";
        return List.of(new FanpageBotResponse.ReplyBubble(text, 0));
    }
}
