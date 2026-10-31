package com.example.docmind.service.bot;

import com.example.docmind.dto.PagePersonaConfig;
import com.example.docmind.dto.bot.AgentSkillDto;
import com.example.docmind.dto.bot.FanpageBotResponse.ReplyBubble;
import com.example.docmind.service.bot.SentimentIntentLayer.DetectedIntent;
import com.example.docmind.service.retrieval.PromptBuilder;
import com.example.docmind.service.retrieval.ReciprocalRankFusion;
import com.example.docmind.service.security.PromptSecurityGuard;
import com.example.docmind.service.text.VietnameseQueryNormalizer;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bộ định tuyến hành động chính theo trạng thái hội thoại (Domain-Agnostic Primary Action Router v3.2).
 * <p>
 * Thứ tự ưu tiên thiết kế:
 * {@code Safety > Emotion/Mismatch rescue > State progression > Value defense > Downsell > Style}
 */
public final class PrimaryActionRouter {

    private PrimaryActionRouter() {
    }

    public enum PrimaryAction {
        BOT_MUTED,
        HARD_HANDOFF_SAFETY,
        HARD_HANDOFF_COMPLAINT,
        SAFE_REFUSAL_OR_HANDOFF_IF_REPEATED,
        OPENING_THEN_RESPOND,
        ADVANCE_AFTER_ACK,
        EXPLAIN_UNIT_VALUE,
        HANDLE_PRICE_OBJECTION_FIRST_TIME,
        HANDLE_PRICE_OBJECTION_REPEAT,
        ANSWER_PRICE_ONCE,
        CLARIFY_SERVICE_DURATION,
        ACKNOWLEDGE_AND_ASK_PREFERENCE,
        RECOMMEND_ONE_PACKAGE,
        CONFIRM_BOOKING_DETAILS,
        SERVICE_DETAIL_AND_FAQ_QA,
        REPAIR_AND_RECONFIRM,
        DIRECT_CONCRETE_SOLUTION,
        GENERIC_SHORT_DISCOVERY
    }

    /**
     * v3.8: chính sách mở đầu Turn 1 — tách khỏi boolean {@code openingRequired} để việc "chọn action" và việc
     * "prepend bong bóng mở đầu cấu hình" không còn dùng chung một cờ.
     */
    public enum OpeningPolicy {
        /** Không phải lượt mở đầu (hoặc BOT_MUTED). */
        NONE,
        /** Turn 1 chỉ chào/hỏi giá/tư vấn trống không: 2 bong bóng opening_bubbles cấu hình + bong bóng mời nêu nhu cầu. */
        BRAND_INTRO,
        /** Turn 1 đã nêu rõ nhóm dịch vụ: chào ngắn, không prepend opening_bubbles, không hỏi lại nhóm dịch vụ. */
        SHORT_GREETING,
        /** Turn 1 hỏi thẳng gói/triệu chứng/FAQ: trả lời trực tiếp, không prepend opening_bubbles. */
        DIRECT_ANSWER,
        /** Turn 1 có cờ an toàn/phàn nàn: xử lý ngay, không mở đầu thương hiệu. */
        SAFETY_OR_REPAIR
    }

    public record RoutingDecision(
            PrimaryAction primaryAction,
            PrimaryAction effectiveResponseAction,
            OpeningPolicy openingPolicy,
            int maxResponseBubblesForLlm,
            String detectedIntent,
            String correspondingMicroSkillCode,
            String routerRuleId
    ) {
        /** Tương thích ngược: true => BRAND_INTRO (prepend opening cấu hình), false => NONE. */
        public RoutingDecision(
                PrimaryAction primaryAction,
                PrimaryAction effectiveResponseAction,
                boolean openingRequired,
                int maxResponseBubblesForLlm,
                String detectedIntent,
                String correspondingMicroSkillCode,
                String routerRuleId
        ) {
            this(primaryAction, effectiveResponseAction,
                    openingRequired ? OpeningPolicy.BRAND_INTRO : OpeningPolicy.NONE,
                    maxResponseBubblesForLlm, detectedIntent, correspondingMicroSkillCode, routerRuleId);
        }

        /** Có phải lượt mở đầu thực sự (đã/ sẽ chào) — dùng để đánh dấu greetingSent. */
        public boolean openingRequired() {
            return openingPolicy == OpeningPolicy.BRAND_INTRO
                    || openingPolicy == OpeningPolicy.SHORT_GREETING
                    || openingPolicy == OpeningPolicy.DIRECT_ANSWER;
        }

        /** Chỉ BRAND_INTRO mới prepend các bong bóng opening_bubbles cố định từ persona_config. */
        public boolean prependsConfiguredOpening() {
            return openingPolicy == OpeningPolicy.BRAND_INTRO;
        }

        public RoutingDecision(
                PrimaryAction primaryAction,
                PrimaryAction effectiveResponseAction,
                boolean openingRequired,
                int maxResponseBubblesForLlm,
                String detectedIntent,
                String correspondingMicroSkillCode
        ) {
            this(
                    primaryAction,
                    effectiveResponseAction,
                    openingRequired,
                    maxResponseBubblesForLlm,
                    detectedIntent,
                    correspondingMicroSkillCode,
                    resolveRouterRuleId(effectiveResponseAction, detectedIntent)
            );
        }

        public String forcedAction() {
            String i = detectedIntent != null ? detectedIntent : "";
            if (effectiveResponseAction == PrimaryAction.REPAIR_AND_RECONFIRM
                    || "REPAIR_AND_RECONFIRM".equals(i)
                    || "ENTITY_MISMATCH_RECONSULT".equals(i)
                    || "PUSHBACK_ON_ASSUMPTION_REPAIR".equals(i)) {
                return "REPAIR_AND_RECONFIRM";
            }
            if (effectiveResponseAction == PrimaryAction.DIRECT_CONCRETE_SOLUTION
                    || "FRUSTRATION_DEMANDING_SOLUTION".equals(i)
                    || "DIRECT_CONCRETE_SOLUTION".equals(i)) {
                return "DIRECT_CONCRETE_SOLUTION";
            }
            if ("ANTI_APOLOGY_PUSHBACK".equals(i)) {
                return "LIGHT_WARM_REPAIR";
            }
            if ("POLITE_DEFERRAL_EXIT".equals(i) || "STAGE_3_GRACEFUL_EXIT".equals(i)) {
                return "GRACEFUL_EXIT";
            }
            return effectiveResponseAction != null ? effectiveResponseAction.name() : "DEFAULT";
        }

        public static String resolveRouterRuleId(PrimaryAction effective, String intent) {
            String i = intent != null ? intent : "";
            if (effective == PrimaryAction.BOT_MUTED) {
                return "PRE-01-BOT-MUTED";
            }
            if ("PROMPT_INJECTION".equals(i)) {
                return "PRE-01B-PROMPT-INJECTION";
            }
            if (effective == PrimaryAction.HARD_HANDOFF_SAFETY) {
                return "PRE-02-HIGH-RISK-SAFETY-GATE";
            }
            if ("EXPLICIT_OPT_OUT".equals(i)) {
                return "PRE-03-EXPLICIT-OPT-OUT";
            }
            if ("STRONG_REJECTION".equals(i) || "STAGE_3_GRACEFUL_EXIT".equals(i)) {
                return "R00-STRONG-REJECTION-GRACEFUL-EXIT";
            }
            if (effective == PrimaryAction.REPAIR_AND_RECONFIRM || "REPAIR_AND_RECONFIRM".equals(i)) {
                return "R00A-SCOPE-REPAIR-AND-RECONFIRM";
            }
            if (effective == PrimaryAction.DIRECT_CONCRETE_SOLUTION || "FRUSTRATION_DEMANDING_SOLUTION".equals(i)) {
                return "R01D-FRUSTRATION-DIRECT-CONCRETE-SOLUTION";
            }
            if (i.startsWith("MISMATCH_CORRECTION") || "ENTITY_MISMATCH_RECONSULT".equals(i)) {
                return "R00-ENTITY-MISMATCH-CORRECTION";
            }
            if ("PUSHBACK_ON_ASSUMPTION_REPAIR".equals(i)) {
                return "R00B-PUSHBACK-ON-ASSUMPTION";
            }
            if ("WORDING_FEEDBACK_REPAIR".equals(i) || "WORDING_FEEDBACK_PRESERVE_BOOKING_FUNNEL".equals(i)) {
                return "R00W-WORDING-FEEDBACK-REPAIR";
            }
            if ("MID_FUNNEL_LUKEWARM_REENGAGE".equals(i)) {
                return "R12L-MID-FUNNEL-LUKEWARM-REENGAGE";
            }
            if (i.startsWith("META_")) {
                return "R00C-META-CONVERSATION";
            }
            if (i.startsWith("CONFIRM_PREVIOUS_INFO")) {
                return "R00D-CONFIRM-PREVIOUS-INFO";
            }
            if (i.contains("FRUSTRATION")) {
                return "R01-FRUSTRATION-RESCUE";
            }
            if (effective == PrimaryAction.SERVICE_DETAIL_AND_FAQ_QA || "UNSUPPORTED_DETAIL_OR_FAQ_QUESTION".equals(i)) {
                return "R01A-CONTEXTUAL-DETAIL-FAQ";
            }
            if (effective == PrimaryAction.ACKNOWLEDGE_AND_ASK_PREFERENCE || "ASK_CONDITION_ONCE".equals(i)) {
                return "R02-CONDITION-FIRST-DISCOVERY";
            }
            if ("RECOMMEND_AND_QUOTE_IF_PRICE_REQUESTED".equals(i)) {
                return "R03-RECOMMEND-AND-QUOTE-WHEN-PRICE-REQUESTED";
            }
            if ("ALTERNATIVE_PACKAGE_REQUEST".equals(i)) {
                return "R03B-ALTERNATIVE-PACKAGE-REQUEST";
            }
            if ("RECOMMEND_AFTER_CONDITION".equals(i)) {
                return "R04-RECOMMEND-AFTER-CONDITION";
            }
            if ("CONDITION_FOLLOWUP_AFTER_QUOTE_ADVANCE".equals(i)) {
                return "R04B-CONDITION-FOLLOWUP-AFTER-QUOTE";
            }
            if ("CLARIFY_SERVICE_TYPE_ONCE".equals(i)) {
                return "R06-CLARIFY-SERVICE-TYPE-ONCE";
            }
            if ("CLARIFY_DURATION_ONCE".equals(i)) {
                return "R07-CLARIFY-DURATION-ONCE";
            }
            if (effective == PrimaryAction.HANDLE_PRICE_OBJECTION_FIRST_TIME) {
                return "R11-PRICE-OBJECTION-STEP-1";
            }
            if (effective == PrimaryAction.HANDLE_PRICE_OBJECTION_REPEAT) {
                return "R12-PRICE-OBJECTION-STEP-2";
            }
            if (effective == PrimaryAction.CONFIRM_BOOKING_DETAILS) {
                return "R13-CONFIRM-BOOKING";
            }
            if (effective == PrimaryAction.ADVANCE_AFTER_ACK) {
                return "R13D-ADVANCE-AFTER-ACK";
            }
            if ("AMBIGUOUS_CONFIRM_ONCE".equals(i)) {
                return "R99-AMBIGUOUS-FALLBACK";
            }
            return "R-" + (effective != null ? effective.name() : "DEFAULT") + (i.isEmpty() ? "" : ":" + i);
        }
    }

    public record CatalogOption(
            String name,
            String unitLabel,
            int unitNumeric,
            String priceDisplay,
            long priceVnd,
            String benefitSummary,
            String headingContext
    ) {
        public CatalogOption(
                String name,
                String unitLabel,
                int unitNumeric,
                String priceDisplay,
                long priceVnd,
                String benefitSummary
        ) {
            this(name, unitLabel, unitNumeric, priceDisplay, priceVnd, benefitSummary, "");
        }
    }

    private static final Pattern ACK_MESSAGE_PATTERN = Pattern.compile(
            "(?iU)^\\s*(?:dạ\\s+)?(?:uk|ukm|ưk|ừ|ừm|ừa|uh|uhm|ok|oke|okay|oki|rồi|vâng|dạ|dạ\\s+vâng|ok\\s+em|ừ\\s+em|uk\\s+em|ok\\s+shop)[.!~\\s]*$"
    );

    /**
     * Lệnh opt-out tường minh tuyệt đối ở tầng System Gate (Mục 2.3 — đã xóa {@code "không cần nữa"} để sửa S06).
     */
    private static final Pattern EXPLICIT_OPT_OUT_PATTERN = Pattern.compile(
            "(?isU)^\\s*(?:stop|unsubscribe|hủy\\s+đăng\\s+ký|huỷ\\s+đăng\\s+ký)[.!~\\s]*$"
            + "|(?isU).*\\b(đừng\\s+nhắn(?:\\s+nữa)?|dừng\\s+lại|làm\\s+ơn\\s+đừng(?:\\s+nhắn)?|thôi\\s+đừng\\s+nhắn|ngừng\\s+nhắn|ngừng\\s+liên\\s+hệ|đừng\\s+liên\\s+hệ\\s+nữa)\\b.*"
    );

    /**
     * Bảo toàn nguyên bản 100% hành vi baseline cho chế độ đối chứng / rollback {@code DETERMINISTIC_V1} (Mục 1.2).
     */
    private static final Pattern LEGACY_V1_EXPLICIT_OPT_OUT_PATTERN = Pattern.compile(
            "(?isU).*\\b(đừng\\s+nhắn|dừng\\s+lại|không\\s+cần\\s+nữa|làm\\s+ơn\\s+đừng|thôi\\s+đừng\\s+nhắn|ngừng\\s+nhắn)\\b.*"
    );

    private static final Pattern FRUSTRATION_FALSE_POSITIVE_PATTERN = Pattern.compile(
            "(?isU).*\\b(canh\\s+hầm|hầm\\s+thuốc\\s+bắc|hầm\\s+ngải\\s+cứu|tức\\s+ngực|đau\\s+ngực|khó\\s+thở)\\b.*"
    );

    private static final Pattern FRUSTRATION_SIGNAL_PATTERN = Pattern.compile(
            "(?isU).*(?:\\b(hâm\\s+à|hâm\\s+hả|hâm\\s+không|điên\\s+à|khùng\\s+à|ngáo\\s+à|dở\\s+à|dở\\s+hơi\\s+à|"
            + "bị\\s+làm\\s+sao\\s+đấy|bị\\s+làm\\s+sao\\s+thế|nói\\s+cái\\s+gì\\s+thế|nói\\s+gì\\s+đấy|không\\s+hiểu\\s+à|"
            + "lặp\\s+à|sao\\s+lặp\\s+thế|vớ\\s+vẩn|vớ\\s+vânr|vo\\s+vanr|nhảm\\s+nhí|tào\\s+lao|"
            + "nói\\s+linh\\s+ta\\s+linh\\s+tinh|nói\\s+linh\\s+tinh|trả\\s+lời\\s+linh\\s+tinh|linh\\s+ta\\s+linh\\s+tinh|linh\\s+ta\\s+linh\\s+tỉnh|là\\s+sao\\s+mẹ|vồ\\s+vập|"
            + "lại\\s+hỏi|hỏi\\s+mãi|hỏi\\s+đi\\s+hỏi\\s+lại|nói\\s+rồi\\s+mà|vừa\\s+nói\\s+xong|"
            + "bực\\s+mình|bực\\s+thật|bực\\s+quá|bực\\s+vãi|tức\\s+thật|khó\\s+chịu\\s+quá|phiền\\s+quá|chán\\s+thật|thất\\s+vọng|"
            + "mất\\s+thời\\s+gian|lằng\\s+nhằng|rắc\\s+rối|vòng\\s+vo|"
            + "khó\\s+hiểu|chả\\s+hiểu\\s+gì|không\\s+hiểu\\s+gì|nói\\s+mãi\\s+không\\s+hiểu|"
            + "tư\\s+vấn\\s+kiểu\\s+gì|trả\\s+lời\\s+kiểu\\s+gì|nói\\s+chuyện\\s+kiểu\\s+gì|"
            + "máy\\s+móc|bot\\s+à|như\\s+cái\\s+máy|trả\\s+lời\\s+tự\\s+động\\s+à)\\b"
            + "|^\\s*(?:hâm|nhảm|vớ\\s+vẩn|vớ\\s+vânr|linh\\s+tinh|linh\\s+ta\\s+linh\\s+tinh|tào\\s+lao|khó\\s+hiểu|dở\\s+à|lặp\\s+à)[.!?~\\s]*$).*"
    );

    private static final Pattern SERVICE_FAMILY_SELECTION_PATTERN = Pattern.compile(
            "(?isU)^\\s*(?:dạ\\s+|cho\\s+mình\\s+|mình\\s+chọn\\s+|mình\\s+làm\\s+|làm\\s+|chọn\\s+|lấy\\s+|thử\\s+)?"
            + "(?:massage|mát\\s+xa|massage\\s+body|body|gội\\s+đầu(?:\\s+dưỡng\\s+sinh)?|ngâm\\s+chân|vệ\\s+sinh\\s+tai|gói\\s+đó|gói\\s+này|\\d{2,3}\\s*(?:phút|p|buổi))"
            + "(?:\\s+(?:đi|nhé|nha|em|shop|ạ|trước|thôi))*[.!~\\s]*$"
    );

    private static final Pattern CONDITION_FOLLOW_UP_PATTERN = Pattern.compile(
            "(?isU).*\\b(mới\\s+bị|hôm\\s+qua|hôm\\s+kia|vài\\s+hôm|mấy\\s+hôm|gần\\s+đây|lâu\\s+rồi|"
            + "vài\\s+tuần|mấy\\s+tháng|thường\\s+xuyên|kinh\\s+niên|mãn\\s+tính|thi\\s+thoảng|thỉnh\\s+thoảng|thi\\s+thoải|đôi\\s+khi|không\\s+rõ|k\\s+rõ|"
            + "ngồi\\s+lâu|ngồi\\s+máy\\s+tính|máy\\s+tính|văn\\s+phòng|làm\\s+việc|vận\\s+động|tập\\s+thể\\s+thao|mang\\s+vác|ngủ\\s+sai\\s+tư\\s+thế|"
            + "ăn\\s+đồ\\s+lạnh|uống\\s+đá|nhai\\s+cứng)\\b.*"
    );

    private static final Pattern EXPLICIT_DURATION_PATTERN = Pattern.compile(
            "(?isU).*\\b(15|20|30|40|45|60|70|75|80|90|100|120)\\s*(?:phút|p|buổi)\\b.*"
    );

    private static final Pattern PREFERENCE_ANSWER_PATTERN = Pattern.compile(
            "(?isU).*\\b(lực\\s+nhẹ|lực\\s+vừa|lực\\s+mạnh|nhẹ\\s+nhàng|ấn\\s+sâu|vừa\\s+phải|ít\\s+ê|nhanh\\s+gọn)\\b.*"
    );

    private static final Pattern UNIT_COMPARISON_PATTERN = Pattern.compile(
            "(?isU).*(?:"
            + "(?:\\d+\\s*(?:p|phút|ml|g|buổi)\\b.*(?:đắt\\s+hơn|nhỉnh\\s+hơn|cao\\s+hơn|rẻ\\s+hơn).*\\d+\\s*(?:p|phút|ml|g|buổi)\\b)"
            + "|(?:\\d+\\s*(?:p|phút|ml|g|buổi)\\b.*(?:sao|lại).*(?:đắt|nhỉnh|cao))"
            + "|(?:tính\\s+ra\\s+(?:gói\\s+ngắn|phút|mỗi\\s+phút|đơn\\s+giá).*(?:đắt|cao|nhỉnh))"
            + "|(?:gói\\s+ngắn\\s+(?:sao\\s+|lại\\s+)?(?:đắt\\s+hơn|cao\\s+hơn|nhỉnh\\s+hơn))"
            + ").*"
    );

    private static final Pattern PRICE_OBJECTION_MSG_PATTERN = Pattern.compile(
            "(?isU).*\\b(đắt|hơi\\s+cao|cao\\s+hơn|đắt\\s+hơn|cao\\s+thế|cao\\s+vậy|cao\\s+quá|đắt\\s+thế|đắt\\s+vậy|đắt\\s+quá|vẫn\\s+đắt|"
            + "chỗ\\s+khác\\s+rẻ\\s+hơn|tiệm\\s+(?:khác|gần\\s+nhà|ngoài)\\s+rẻ\\s+hơn|vượt\\s+ngân\\s+sách|chưa\\s+đủ\\s+ngân\\s+sách|nhiều\\s+tiền|hơi\\s+căng|mắc\\s+quá|mềm\\s+hơn|rẻ\\s+hơn|ngắn\\s+hơn|thấp\\s+hơn)\\b.*"
    );

    private static final Pattern PRICE_INQUIRY_MSG_PATTERN = Pattern.compile(
            "(?isU).*\\b(giá|bảng\\s+giá|bao\\s+nhiêu|chi\\s+phí|rổ\\s+giá|mấy\\s+tiền|nhiêu\\s+tiền)\\b.*"
    );

    private static final Pattern PROBLEM_OR_SYMPTOM_PATTERN = Pattern.compile(
            "(?isU).*\\b(mỏi|đau|nhức|nhói|tê|tê\\s+tay|tê\\s+chân|tê\\s+bì|căng|cứng|nặng|mệt|stress|mất\\s+ngủ|khó\\s+ngủ|"
            + "căng\\s+thẳng|căng\\s+cơ|căng\\s+cổ|đau\\s+cơ|đau\\s+đầu|đau\\s+lưng|mỏi\\s+vai|đau\\s+vai|nhức\\s+gáy|mỏi\\s+cổ|"
            + "ê\\s+buốt|ố\\s+vàng|xỉn\\s+màu|(?<!cạo\\s|lấy\\s)vôi\\s+răng|chảy\\s+máu\\s+nướu|sâu\\s+răng|lệch\\s+khớp|mụn|thâm|nám)\\b.*"
    );

    private static final Pattern BOOKING_INTENT_PATTERN = Pattern.compile(
            "(?isU).*\\b(đặt\\s+lịch|giữ\\s+lịch|còn\\s+lịch|chốt\\s+gói|lấy\\s+gói|chọn\\s+gói|thử\\s+gói|"
            + "tối\\s+nay|chiều\\s+nay|sáng\\s+mai|chiều\\s+mai|ghé\\s+lúc|qua\\s+lúc|\\d{1,2}h\\d{0,2})\\b.*"
    );

    private static final Pattern SCHEDULE_LATER_PATTERN = Pattern.compile(
            "(?isU).*\\b(xem\\s+lại\\s+lịch|báo\\s+sau|suy\\s+nghĩ\\s+thêm|để\\s+chị\\s+xem|để\\s+anh\\s+xem|để\\s+mình\\s+xem)\\b.*"
    );

    public static boolean isAcknowledgementMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        return ACK_MESSAGE_PATTERN.matcher(message.strip()).matches();
    }

    public static boolean isExplicitOptOutMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        return LEGACY_V1_EXPLICIT_OPT_OUT_PATTERN.matcher(message.strip()).matches()
                || SentimentIntentLayer.classify(message, null) == DetectedIntent.OPT_OUT;
    }

    /**
     * Lệnh opt-out tường minh tuyệt đối ở tầng System Gate (STOP, UNSUBSCRIBE, HỦY ĐĂNG KÝ, ĐỪNG NHẮN NỮA...).
     * Tuyệt đối KHÔNG bắt câu từ chối lịch sự như {@code "Thôi, không cần nữa"} (S06).
     */
    public static boolean isStrictSystemOptOutMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String norm = Normalizer.normalize(message.strip(), Normalizer.Form.NFC);
        return EXPLICIT_OPT_OUT_PATTERN.matcher(norm).matches();
    }

    public static boolean isFrustrationMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String trimmed = message.strip();
        if (FRUSTRATION_FALSE_POSITIVE_PATTERN.matcher(trimmed).matches()) {
            return false;
        }
        if (SentimentIntentLayer.isMetaConversationOnBotBehavior(trimmed)
                || SentimentIntentLayer.isConfirmPreviousInfoMessage(trimmed)) {
            return false;
        }
        if (CustomerInsightEngine.hasHighRiskHealthSignal(trimmed.toLowerCase(Locale.ROOT))) {
            return false;
        }
        if (FRUSTRATION_SIGNAL_PATTERN.matcher(trimmed).matches()) {
            return true;
        }
        String normClass = VietnameseQueryNormalizer.normalizeForClassification(trimmed);
        return FRUSTRATION_SIGNAL_PATTERN.matcher(normClass).matches();
    }

    public static boolean isServiceFamilySelectionMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String trimmed = message.strip();
        if (SentimentIntentLayer.isEntityMismatchCorrection(trimmed)
                || SentimentIntentLayer.isDetailOrFaqQuestion(trimmed, null, null)
                || isPriceInquiryMessage(trimmed)
                || isPriceObjectionMessage(trimmed)
                || isFrustrationMessage(trimmed)
                || PROBLEM_OR_SYMPTOM_PATTERN.matcher(trimmed).matches()) {
            return false;
        }
        return SERVICE_FAMILY_SELECTION_PATTERN.matcher(trimmed).matches();
    }

    public static boolean isConditionFollowUpMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String trimmed = message.strip();
        if (CONDITION_FOLLOW_UP_PATTERN.matcher(trimmed).matches()) {
            return true;
        }
        String norm = VietnameseQueryNormalizer.normalizeForClassification(trimmed);
        return CONDITION_FOLLOW_UP_PATTERN.matcher(norm).matches();
    }

    public static boolean hasSymptomOrConditionSignal(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        if (SentimentIntentLayer.isNegationOrPushbackOnAssumption(message)) {
            return false;
        }
        String norm = Normalizer.normalize(message.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        return PROBLEM_OR_SYMPTOM_PATTERN.matcher(norm).matches() || isConditionFollowUpMessage(norm);
    }

    public static boolean hasExplicitDurationInMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        return EXPLICIT_DURATION_PATTERN.matcher(message.strip()).matches();
    }

    public static boolean isUnitPriceComparisonMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        return UNIT_COMPARISON_PATTERN.matcher(message.strip()).matches();
    }

    public static boolean isPriceObjectionMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        if (isUnitPriceComparisonMessage(message)
                || SentimentIntentLayer.isEntityMismatchCorrection(message)
                || SentimentIntentLayer.isAlternativePackageRequest(message)
                || SentimentIntentLayer.isDetailOrFaqQuestion(message, null, null)) {
            return false;
        }
        if (SentimentIntentLayer.isTrialAcceptance(message, null) || SentimentIntentLayer.isStrongRejection(message)) {
            return false;
        }
        return PRICE_OBJECTION_MSG_PATTERN.matcher(message.strip()).matches()
                || SentimentIntentLayer.isPriceObjection(message)
                || SentimentIntentLayer.isExplicitDownsellRequest(message, null)
                || SentimentIntentLayer.isPackageRejection(message);
    }

    public static boolean isPriceInquiryMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        if (isAcknowledgementMessage(message)
                || isUnitPriceComparisonMessage(message)
                || isPriceObjectionMessage(message)
                || SentimentIntentLayer.isAlternativePackageRequest(message)
                || SentimentIntentLayer.isEntityMismatchCorrection(message)) {
            return false;
        }
        return PRICE_INQUIRY_MSG_PATTERN.matcher(message.strip()).matches();
    }

    public static boolean matchesRawPriceInquiryPattern(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        return PRICE_INQUIRY_MSG_PATTERN.matcher(message.strip()).matches();
    }

    public static RoutingDecision decide(
            ConversationState state,
            String message,
            boolean isFirstTurn,
            List<ReciprocalRankFusion.FusedChunk> fusedChunks,
            com.example.docmind.dto.PagePersonaConfig personaConfig
    ) {
        List<String> keywords = personaConfig != null ? personaConfig.resolvedScopeTaxonomy() : List.of();
        boolean allowDomainHealth = personaConfig == null || personaConfig.isMedicalOrBodySafetyIndustry();
        ConversationState base = state != null ? state : ConversationState.initial();
        ConversationState enriched = base.ensureTurnEnriched(message, keywords);
        return route(message, enriched.priorTurnState(), enriched, fusedChunks, keywords, allowDomainHealth);
    }

    /**
     * Chọn duy nhất 1 {@link PrimaryAction} dựa trên tin nhắn và {@link ConversationState} theo bảng định tuyến v3.2.
     */
    public static RoutingDecision route(
            String message,
            ConversationState state,
            List<ReciprocalRankFusion.FusedChunk> fusedChunks,
            List<String> domainKeywords
    ) {
        ConversationState base = state != null ? state : ConversationState.initial();
        ConversationState enriched = base.ensureTurnEnriched(message, domainKeywords);
        return route(message, enriched.priorTurnState(), enriched, fusedChunks, domainKeywords, true);
    }

    /**
     * v3.8 HỢP ĐỒNG ENRICH-MỘT-LẦN: {@code priorState} = state các lượt TRƯỚC (từ hydrateForTurn);
     * {@code enrichedForThisTurn} = priorState + dữ kiện của tin nhắn HIỆN TẠI, được tính đúng 1 lần bởi caller.
     * Router không enrich lại.
     */
    public static RoutingDecision route(
            String message,
            ConversationState priorStateIn,
            ConversationState enrichedForThisTurn,
            List<ReciprocalRankFusion.FusedChunk> fusedChunks,
            List<String> domainKeywords
    ) {
        return route(message, priorStateIn, enrichedForThisTurn, fusedChunks, domainKeywords, true);
    }

    public static RoutingDecision route(
            String message,
            ConversationState priorStateIn,
            ConversationState enrichedForThisTurn,
            List<ReciprocalRankFusion.FusedChunk> fusedChunks,
            List<String> domainKeywords,
            boolean allowDomainHealthContraindications
    ) {
        RoutingDecision d = routeInternal(
                message,
                priorStateIn,
                enrichedForThisTurn,
                fusedChunks,
                domainKeywords,
                allowDomainHealthContraindications
        );
        boolean firstTurn = priorStateIn == null || !priorStateIn.greetingSent();
        if (d != null && firstTurn && d.openingPolicy() == OpeningPolicy.NONE
                && (d.effectiveResponseAction() == PrimaryAction.HARD_HANDOFF_SAFETY
                    || d.effectiveResponseAction() == PrimaryAction.HARD_HANDOFF_COMPLAINT
                    || d.effectiveResponseAction() == PrimaryAction.SAFE_REFUSAL_OR_HANDOFF_IF_REPEATED)) {
            return new RoutingDecision(d.primaryAction(), d.effectiveResponseAction(), OpeningPolicy.SAFETY_OR_REPAIR,
                    d.maxResponseBubblesForLlm(), d.detectedIntent(), d.correspondingMicroSkillCode(), d.routerRuleId());
        }
        return d;
    }

    private static RoutingDecision routeInternal(
            String message,
            ConversationState priorStateIn,
            ConversationState enrichedForThisTurn,
            List<ReciprocalRankFusion.FusedChunk> fusedChunks,
            List<String> domainKeywords,
            boolean allowDomainHealthContraindications
    ) {
        ConversationState enriched = enrichedForThisTurn != null
                ? enrichedForThisTurn
                : (priorStateIn != null ? priorStateIn : ConversationState.initial());
        ConversationState priorState = priorStateIn != null ? priorStateIn : enriched.priorTurnState();
        String msg = message != null ? message.strip() : "";
        String lower = Normalizer.normalize(msg, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        DetectedIntent classified = SentimentIntentLayer.classify(msg, priorState, allowDomainHealthContraindications);

        // PRE-01-BOT-MUTED
        if (priorState.humanTakeover() || priorState.optedOut()) {
            return new RoutingDecision(
                    PrimaryAction.BOT_MUTED,
                    PrimaryAction.BOT_MUTED,
                    false,
                    0,
                    "MUTED",
                    "safety_handoff_high_risk"
            );
        }

        if (classified == DetectedIntent.PROMPT_INJECTION || PromptSecurityGuard.isDirectJailbreakAttempt(msg)) {
            return new RoutingDecision(
                    PrimaryAction.SAFE_REFUSAL_OR_HANDOFF_IF_REPEATED,
                    PrimaryAction.SAFE_REFUSAL_OR_HANDOFF_IF_REPEATED,
                    false,
                    1,
                    "PROMPT_INJECTION",
                    "greeting_warm_intro"
            );
        }

        // PRE-02-HIGH-RISK-HEALTH-UPDATED (Includes NEUROLOGICAL_RISK!)
        boolean isUniversalNeuroEmergency = classified == DetectedIntent.NEUROLOGICAL_RISK || enriched.neurologicalRisk();
        boolean isDomainSafetyRisk = allowDomainHealthContraindications
                && (classified == DetectedIntent.SAFETY_RISK || CustomerInsightEngine.hasHighRiskHealthSignal(lower));
        if (isUniversalNeuroEmergency || isDomainSafetyRisk) {
            return new RoutingDecision(
                    PrimaryAction.HARD_HANDOFF_SAFETY,
                    PrimaryAction.HARD_HANDOFF_SAFETY,
                    false,
                    1,
                    isUniversalNeuroEmergency
                            ? "NEUROLOGICAL_RISK"
                            : "HIGH_RISK_SAFETY",
                    "safety_handoff_high_risk"
            );
        }

        // PRE-02B-CAUTIOUS-SYMPTOM-CLARIFICATION (Safety tầng 2): tê/chóng mặt... CHƯA có dấu hiệu cấp tính.
        // Ưu tiên hơn mọi nhánh bán hàng/báo giá: trấn an + hỏi khởi phát/bên ảnh hưởng/diễn biến, không chào gói.
        if (allowDomainHealthContraindications && classified == DetectedIntent.CAUTIOUS_SYMPTOM_CLARIFICATION) {
            return new RoutingDecision(
                    PrimaryAction.ACKNOWLEDGE_AND_ASK_PREFERENCE,
                    PrimaryAction.ACKNOWLEDGE_AND_ASK_PREFERENCE,
                    false,
                    1,
                    "CAUTIOUS_SYMPTOM_CLARIFICATION",
                    "spa_condition_first_discovery"
            );
        }

        // PRE-02C-CAUTIOUS-MEDICAL-RECOMMENDATION (v3.8): sau lượt CAUTIOUS_SYMPTOM_CLARIFICATION, khi khách trả lời kèm dữ kiện
        // kéo dài / hai bên (slot khởi phát, phân bổ) -> khuyến nghị đánh giá y tế + chuyển chuyên viên, KHÔNG bán gói.
        // pendingQuestionSlot chỉ là ngữ cảnh định tuyến; căn cứ chính là nhóm triệu chứng thận trọng đã phân loại ở lượt trước.
        if (allowDomainHealthContraindications
                && "CAUTIOUS_SYMPTOM_CLARIFICATION".equals(priorState.lastIntent())
                && classified != DetectedIntent.CAUTIOUS_SYMPTOM_CLARIFICATION) {
            String onset = enriched.knownOnsetDuration() != null ? enriched.knownOnsetDuration().toLowerCase(Locale.ROOT) : "";
            String dist = enriched.knownSymptomDistribution() != null ? enriched.knownSymptomDistribution().toLowerCase(Locale.ROOT) : "";
            boolean prolonged = onset.matches("(?isU).*(tuần|tháng|năm|lâu\\s+(rồi|năm)).*");
            boolean bilateral = dist.matches("(?isU).*(hai|2|cả\\s+hai)\\s+(bên|chân|tay|vai).*");
            if (prolonged || bilateral) {
                return new RoutingDecision(
                        PrimaryAction.HARD_HANDOFF_SAFETY,
                        PrimaryAction.HARD_HANDOFF_SAFETY,
                        false,
                        1,
                        "CAUTIOUS_MEDICAL_RECOMMENDATION",
                        "safety_handoff_high_risk"
                );
            }
        }

        // PRE-03-EXPLICIT-OPT-OUT
        if (classified == DetectedIntent.OPT_OUT || isExplicitOptOutMessage(msg)) {
            return new RoutingDecision(
                    PrimaryAction.GENERIC_SHORT_DISCOVERY,
                    PrimaryAction.GENERIC_SHORT_DISCOVERY,
                    false,
                    1,
                    "EXPLICIT_OPT_OUT",
                    "spa_ambiguous_confirm_once"
            );
        }

        // PRE-04-SERVICE-COMPLAINT & PRICE_ESCALATION_REQUEST (Priority 2: Complaint/Refund/Manager before consultation branches)
        if (CustomerInsightEngine.hasComplaintSignal(lower)) {
            return new RoutingDecision(
                    PrimaryAction.HARD_HANDOFF_COMPLAINT,
                    PrimaryAction.HARD_HANDOFF_COMPLAINT,
                    false,
                    1,
                    "COMPLAINT",
                    "complaint_immediate_handoff"
            );
        }
        if (classified == DetectedIntent.PRICE_ESCALATION_REQUEST) {
            return new RoutingDecision(
                    PrimaryAction.HARD_HANDOFF_COMPLAINT,
                    PrimaryAction.HARD_HANDOFF_COMPLAINT,
                    false,
                    1,
                    "PRICE_ESCALATION_HANDOFF",
                    "spa_price_escalation_handoff"
            );
        }

        // R00W-WORDING-FEEDBACK-REPAIR (Soft Signal):
        // Chỉ định tuyến sang nhánh điều chỉnh giao tiếp khi tin nhắn của khách KHÔNG chứa câu hỏi nghiệp vụ,
        // câu hỏi chi tiết/giá/đơn vị tính/quyền lợi, câu hỏi chưa được giải đáp, hoặc tín hiệu quan tâm tới gói đang trao đổi.
        String activePkgForSoftCheck = priorState.downsellPackage() != null
                ? priorState.downsellPackage()
                : (priorState.heldPackage() != null ? priorState.heldPackage() : priorState.knownRecommendedPackage());
        boolean hasActivePackageOrQuoteContext = activePkgForSoftCheck != null
                || priorState.packageRecommended()
                || priorState.priceDiscussed();
        boolean hasServiceQuestionOrInterestSignal = classified == DetectedIntent.DETAIL_OR_FAQ_QUESTION
                || classified == DetectedIntent.ALTERNATIVE_PACKAGE_REQUEST
                || classified == DetectedIntent.TRIAL_ACCEPTANCE
                || SentimentIntentLayer.isDetailOrFaqQuestion(msg, priorState.lastAssistantMessage(), activePkgForSoftCheck)
                || isPriceInquiryMessage(msg)
                || isServiceFamilySelectionMessage(msg)
                || hasExplicitDurationInMessage(lower)
                || SentimentIntentLayer.isAlternativePackageRequest(msg)
                || SentimentIntentLayer.hasPositiveServiceInterestSignal(msg)
                || (enriched.mostRecentUnresolvedRequest() != null)
                || (hasActivePackageOrQuoteContext && msg.contains("?"));

        if (!hasServiceQuestionOrInterestSignal && SentimentIntentLayer.isWordingOrExpressionFeedback(msg)) {
            boolean preserveBookingFunnel = (priorState.packageInterestAccepted() || enriched.packageInterestAccepted())
                    && "COLLECTING_INFO".equals(priorState.bookingStatus() != null && !"NONE".equals(priorState.bookingStatus()) ? priorState.bookingStatus() : enriched.bookingStatus())
                    && !SentimentIntentLayer.isStrongRejection(msg)
                    && !SentimentIntentLayer.isPackageRejection(msg);
            return new RoutingDecision(
                    PrimaryAction.GENERIC_SHORT_DISCOVERY,
                    PrimaryAction.GENERIC_SHORT_DISCOVERY,
                    false,
                    2,
                    preserveBookingFunnel ? "WORDING_FEEDBACK_PRESERVE_BOOKING_FUNNEL" : "WORDING_FEEDBACK_REPAIR",
                    preserveBookingFunnel ? "spa_trial_acceptance_booking" : "greeting_warm_intro"
            );
        }
        if (!hasServiceQuestionOrInterestSignal
                && SentimentIntentLayer.isConversationStyleComplaint(msg)
                && !SentimentIntentLayer.isStrongRejection(msg)) {
            return new RoutingDecision(
                    PrimaryAction.GENERIC_SHORT_DISCOVERY,
                    PrimaryAction.GENERIC_SHORT_DISCOVERY,
                    false,
                    2,
                    "CONVERSATION_STYLE_REPAIR",
                    "spa_frustration_rescue"
            );
        }
        if (!hasServiceQuestionOrInterestSignal
                && SentimentIntentLayer.isAlreadyAnsweredSlotPushback(msg)
                && !SentimentIntentLayer.isStrongRejection(msg)) {
            if (priorState.packageRecommended() || priorState.priceDiscussed()) {
                return new RoutingDecision(
                        PrimaryAction.GENERIC_SHORT_DISCOVERY,
                        PrimaryAction.GENERIC_SHORT_DISCOVERY,
                        false,
                        2,
                        "WORDING_FEEDBACK_REPAIR",
                        "greeting_warm_intro"
                );
            }
            if (enriched.conditionClarified()
                    && (!enriched.knownSymptoms().isEmpty() || !enriched.knownBodyAreas().isEmpty() || priorState.serviceFamilyKnown())) {
                CatalogOption grounded = selectBestSingleCatalogOption(msg, enriched, fusedChunks);
                if (grounded == null && fusedChunks != null && !fusedChunks.isEmpty()) {
                    return new RoutingDecision(
                            PrimaryAction.HARD_HANDOFF_COMPLAINT,
                            PrimaryAction.HARD_HANDOFF_COMPLAINT,
                            false,
                            1,
                            "UNGROUNDED_SCOPE_HANDOFF",
                            "complaint_immediate_handoff"
                    );
                }
                if (priorState.priceRequestedInSession()) {
                    return new RoutingDecision(
                            PrimaryAction.ANSWER_PRICE_ONCE,
                            PrimaryAction.ANSWER_PRICE_ONCE,
                            false,
                            2,
                            "RECOMMEND_AND_QUOTE_IF_PRICE_REQUESTED",
                            "spa_recommend_and_quote_if_requested"
                    );
                }
                return new RoutingDecision(
                        PrimaryAction.RECOMMEND_ONE_PACKAGE,
                        PrimaryAction.RECOMMEND_ONE_PACKAGE,
                        false,
                        2,
                        "RECOMMEND_AFTER_CONDITION",
                        "spa_recommend_after_condition"
                );
            }
        }

        boolean isHarshEntityMismatchCritique = SentimentIntentLayer.hasHarshPronouns(msg)
                && (classified == DetectedIntent.ENTITY_MISMATCH_CORRECTION || SentimentIntentLayer.isEntityMismatchCorrection(msg));
        boolean hasFullBodyHeldPackageForMismatch = (classified == DetectedIntent.ENTITY_MISMATCH_CORRECTION || SentimentIntentLayer.isEntityMismatchCorrection(msg))
                && !SentimentIntentLayer.hasHarshPronouns(msg)
                && priorState.frustrationCount() == 0
                && priorState.priceDiscussed()
                && priorState.heldPackage() != null
                && (priorState.heldPackage().contains("co_ban")
                    || priorState.heldPackage().contains("nang_cao")
                    || priorState.heldPackage().contains("phuc_hoi"));

        // R00A-SCOPE-REPAIR-AND-RECONFIRM (Priority 5: Khách đính chính phạm vi — Nhóm D & Nhóm H)
        if (!isHarshEntityMismatchCritique
                && !hasFullBodyHeldPackageForMismatch
                && !SentimentIntentLayer.isMetaConversationOnBotBehavior(msg)
                && !SentimentIntentLayer.isConfirmPreviousInfoMessage(msg)
                && !SentimentIntentLayer.isAntiApologyPushback(msg)
                && (classified == DetectedIntent.SCOPE_CORRECTION_PUSHBACK
                    || SentimentIntentLayer.isScopeCorrectionPushback(msg)
                    || enriched.possibleCorrection()
                    || VietnameseScopeSignalExtractor.extract(msg, domainKeywords).explicitCorrectionGroupD())) {
            return new RoutingDecision(
                    PrimaryAction.REPAIR_AND_RECONFIRM,
                    PrimaryAction.REPAIR_AND_RECONFIRM,
                    false,
                    1,
                    "REPAIR_AND_RECONFIRM",
                    "spa_frustration_rescue"
            );
        }

        // R00-MISMATCH-CORRECTION-RESCUE (Priority 5B: Khách phản ứng vì bot tư vấn lệch vùng đau / lệch nhu cầu)
        if (classified == DetectedIntent.ENTITY_MISMATCH_CORRECTION || SentimentIntentLayer.isEntityMismatchCorrection(msg)) {
            boolean harshOrRepeated = SentimentIntentLayer.hasHarshPronouns(msg) || priorState.frustrationCount() >= 1;
            CatalogOption groundedRescueOpt = selectBestSingleCatalogOption(msg, enriched, fusedChunks);
            if (harshOrRepeated || groundedRescueOpt == null) {
                return new RoutingDecision(
                        PrimaryAction.HARD_HANDOFF_COMPLAINT,
                        PrimaryAction.HARD_HANDOFF_COMPLAINT,
                        false,
                        1,
                        "MISMATCH_CORRECTION_HANDOFF",
                        "complaint_immediate_handoff"
                );
            }
            boolean hasFullBodyHeldPackage = priorState.priceDiscussed()
                    && priorState.heldPackage() != null
                    && (priorState.heldPackage().contains("co_ban")
                        || priorState.heldPackage().contains("nang_cao")
                        || priorState.heldPackage().contains("phuc_hoi"));
            if (hasFullBodyHeldPackage) {
                return new RoutingDecision(
                        PrimaryAction.RECOMMEND_ONE_PACKAGE,
                        PrimaryAction.RECOMMEND_ONE_PACKAGE,
                        false,
                        2,
                        "ENTITY_MISMATCH_RECONSULT",
                        "spa_frustration_rescue"
                );
            }
            if (priorState.priceRequestedInSession()) {
                return new RoutingDecision(
                        PrimaryAction.ANSWER_PRICE_ONCE,
                        PrimaryAction.ANSWER_PRICE_ONCE,
                        false,
                        2,
                        "MISMATCH_CORRECTION_RESCUE_QUOTE",
                        "spa_frustration_rescue"
                );
            }
            return new RoutingDecision(
                    PrimaryAction.RECOMMEND_ONE_PACKAGE,
                    PrimaryAction.RECOMMEND_ONE_PACKAGE,
                    false,
                    2,
                    "MISMATCH_CORRECTION_RESCUE_RECOMMEND",
                    "spa_frustration_rescue"
            );
        }

        // R00M-MOOD-DROP-RECOVERY (Priority 8: Khách bày tỏ tụt cảm xúc / chán chả buồn nói do câu tư vấn trước chưa khéo)
        if (priorState.greetingSent() && SentimentIntentLayer.isMoodDropRecoveryMessage(msg)) {
            return new RoutingDecision(
                    PrimaryAction.GENERIC_SHORT_DISCOVERY,
                    PrimaryAction.GENERIC_SHORT_DISCOVERY,
                    false,
                    1,
                    "MOOD_DROP_RECOVERY",
                    "spa_frustration_rescue"
            );
        }

        PrimaryAction coreAction;
        String intent;
        String skillCode;
        boolean hasKnownContext = enriched.serviceFamilyKnown()
                || !enriched.knownBodyAreas().isEmpty()
                || !enriched.knownSymptoms().isEmpty()
                || priorState.packageRecommended()
                || priorState.heldPackage() != null;
        String activePkgContext = priorState.heldPackage() != null
                ? priorState.heldPackage()
                : priorState.knownRecommendedPackage();
        boolean isDetailFaqQuestion = classified == DetectedIntent.DETAIL_OR_FAQ_QUESTION
                || SentimentIntentLayer.isDetailOrFaqQuestion(msg, priorState.lastAssistantMessage(), activePkgContext);
        boolean isComprehensionFailure = classified == DetectedIntent.COMPREHENSION_FAILURE
                || SentimentIntentLayer.isComprehensionFailureMessage(msg);
        boolean isHesitationAfterObjection = SentimentIntentLayer.isHesitationAfterObjection(msg);
        boolean isPoliteDeferral = SentimentIntentLayer.isPoliteDeferralExit(msg);
        boolean isPoliteDeferralExitTrigger = isPoliteDeferral
                && !isDetailFaqQuestion
                && !isComprehensionFailure
                && classified != DetectedIntent.ALTERNATIVE_PACKAGE_REQUEST
                && !SentimentIntentLayer.isAlternativePackageRequest(msg)
                && classified != DetectedIntent.TRIAL_ACCEPTANCE
                && !SentimentIntentLayer.isAntiApologyPushback(msg)
                && (priorState.downsellOffered() || priorState.objectionCount() == 0);
        boolean isStage3Trigger = priorState.downsellOffered()
                && !isDetailFaqQuestion
                && !isComprehensionFailure
                && classified != DetectedIntent.ALTERNATIVE_PACKAGE_REQUEST
                && !SentimentIntentLayer.isAlternativePackageRequest(msg)
                && classified != DetectedIntent.TRIAL_ACCEPTANCE
                && classified != DetectedIntent.FRUSTRATION
                && !isFrustrationMessage(msg)
                && (classified == DetectedIntent.STRONG_REJECTION
                    || classified == DetectedIntent.PRICE_OBJECTION
                    || isPriceObjectionMessage(msg)
                    || isHesitationAfterObjection);
        boolean hasKnownSymptomsOrScopes = !enriched.confirmedScopes().isEmpty()
                || !enriched.knownSymptoms().isEmpty()
                || !priorState.confirmedScopes().isEmpty()
                || !priorState.knownSymptoms().isEmpty();
        boolean afterAskConditionWithoutTimeAnswer = !enriched.conditionClarified()
                && (!priorState.knownSymptoms().isEmpty() || !priorState.confirmedScopes().isEmpty())
                && ("ASK_CONDITION_ONCE".equals(priorState.lastIntent())
                    || "CONVERSATION_STYLE_REPAIR".equals(priorState.lastIntent())
                    || "PUSHBACK_ON_ASSUMPTION_REPAIR".equals(priorState.lastIntent())
                    || "ACKNOWLEDGE_AND_ASK_PREFERENCE".equals(priorState.lastBotAction())
                    || "ASK_CONDITION".equals(priorState.lastQuestionIntent())
                    || (priorState.conditionAskedCount() >= 1 && !priorState.priceDiscussed() && !priorState.packageRecommended()));
        boolean customerActivelyAsksPriceOrPackageNow = isPriceInquiryMessage(msg)
                || isServiceFamilySelectionMessage(msg)
                || hasExplicitDurationInMessage(lower)
                || classified == DetectedIntent.ALTERNATIVE_PACKAGE_REQUEST
                || SentimentIntentLayer.isAlternativePackageRequest(msg)
                || isDetailFaqQuestion
                || classified == DetectedIntent.TRIAL_ACCEPTANCE
                || PREFERENCE_ANSWER_PATTERN.matcher(lower).matches();

        // POLITE_DEFERRAL_EXIT: Khách hẹn cân nhắc / suy nghĩ thêm lịch sự ("Ukm, để t cân nhắc thêm", "Để mình suy nghĩ thêm") -> 0 xin lỗi, 0 câu hỏi
        if (isPoliteDeferralExitTrigger) {
            coreAction = PrimaryAction.GENERIC_SHORT_DISCOVERY;
            intent = "POLITE_DEFERRAL_EXIT";
            skillCode = "spa_graceful_exit_after_rejection";
        }
        // STAGE 3 RETENTION: Customer still hesitates or declines after 1 lower alternative was offered -> graceful exit, 0 push
        else if (isStage3Trigger) {
            coreAction = PrimaryAction.GENERIC_SHORT_DISCOVERY;
            intent = "STAGE_3_GRACEFUL_EXIT";
            skillCode = "spa_graceful_exit_after_rejection";
        }
        // STRONG_REJECTION ("đắt quá, thôi khỏi cần", "thôi không làm", "tụt hứng thôi dẹp") -> Polite breakup, 0 push
        else if (classified == DetectedIntent.STRONG_REJECTION) {
            coreAction = PrimaryAction.GENERIC_SHORT_DISCOVERY;
            intent = "STRONG_REJECTION";
            skillCode = "spa_graceful_exit_after_rejection";
        }
        // R13B-TRIAL-ACCEPTANCE-BOOKING-CORRECTED (Priority 13: "Thử xem sao", "Đắt thế nhưng thôi thử xem sao")
        else if (classified == DetectedIntent.TRIAL_ACCEPTANCE
                && (priorState.trialInviteOffered()
                    || "INVITE_ONE_SESSION_TRIAL".equals(priorState.lastQuestionIntent())
                    || priorState.downsellOffered()
                    || priorState.packageRecommended()
                    || priorState.heldPackage() != null
                    || priorState.priceDiscussed())) {
            coreAction = PrimaryAction.CONFIRM_BOOKING_DETAILS;
            intent = "TRIAL_ACCEPTANCE";
            skillCode = "spa_trial_acceptance_booking";
        }
        // R01A-CONTEXTUAL-SERVICE-DETAIL-AND-FAQ-QA ("Thực dưỡng là j", "K tôi thấy có buffet thực dưỡng, nó là j", "Buffet có món gì?")
        else if (isDetailFaqQuestion) {
            ConversationState.UnresolvedCustomerRequest detailReq = enriched.latestUnresolvedRequest();
            boolean groundedByHeldCatalog = fusedChunks != null && fusedChunks.isEmpty()
                    && hasHeldPackageCatalogEvidence(msg, enriched);
            if (fusedChunks != null && !groundedByHeldCatalog
                    && (fusedChunks.isEmpty()
                        || detectConflictingCatalogOrFaqEvidence(msg, enriched, fusedChunks)
                        || !hasGroundedEvidenceForDetailRequest(detailReq, enriched, fusedChunks))) {
                return new RoutingDecision(
                        PrimaryAction.SAFE_REFUSAL_OR_HANDOFF_IF_REPEATED,
                        PrimaryAction.SAFE_REFUSAL_OR_HANDOFF_IF_REPEATED,
                        false,
                        1,
                        "UNSUPPORTED_DETAIL_OR_FAQ_QUESTION",
                        "spa_service_detail_faq_qa"
                );
            }
            coreAction = PrimaryAction.SERVICE_DETAIL_AND_FAQ_QA;
            intent = SentimentIntentLayer.hasContrastOrClarificationPrefix(msg)
                    ? "CLARIFICATION_DETAIL_OR_FAQ_QA"
                    : "SERVICE_DETAIL_AND_FAQ_QA";
            skillCode = "spa_service_detail_faq_qa";
        }
        // R01B-COMPREHENSION-FAILURE-RECOVERY ("Chả hiểu j", "Khó hiểu quá", "Vẫn chả hiểu j")
        else if (isComprehensionFailure) {
            ConversationState.UnresolvedCustomerRequest compReq = enriched.latestUnresolvedRequest() != null
                    ? enriched.latestUnresolvedRequest()
                    : priorState.latestRequest();
            boolean priorReplyWasSimplifiedExplanation = "COMPREHENSION_RECOVERY_SIMPLIFY_FAQ".equals(priorState.lastIntent())
                    || (priorState.lastAssistantMessage() != null
                        && priorState.lastAssistantMessage().toLowerCase(Locale.ROOT).contains("giải thích ngắn gọn"));
            boolean shouldEscalateComprehension = (compReq != null && (compReq.requiresHumanAssistance() || compReq.repeatOrChallengeCount() >= 2))
                    || priorReplyWasSimplifiedExplanation
                    || priorState.frustrationCount() >= 2
                    || (fusedChunks != null
                        && (fusedChunks.isEmpty() || !hasGroundedEvidenceForDetailRequest(compReq, enriched, fusedChunks)));
            if (shouldEscalateComprehension) {
                return new RoutingDecision(
                        PrimaryAction.HARD_HANDOFF_COMPLAINT,
                        PrimaryAction.HARD_HANDOFF_COMPLAINT,
                        false,
                        1,
                        "COMPREHENSION_ESCALATION_HANDOFF",
                        "spa_frustration_rescue"
                );
            }
            coreAction = PrimaryAction.SERVICE_DETAIL_AND_FAQ_QA;
            intent = "COMPREHENSION_RECOVERY_SIMPLIFY_FAQ";
            skillCode = "spa_frustration_rescue";
        }
        // R00-CONVERSATION-STYLE-REPAIR: Sau ASK_CONDITION_ONCE, chỉ kích hoạt sửa văn phong khi khách THỰC SỰ góp ý/phàn nàn về cách nói chuyện;
        // nếu khách chỉ đang trả lời bình thường câu hỏi khám phá tình trạng -> để đi tiếp xuống R03 (RECOMMEND_AND_QUOTE_IF_PRICE_REQUESTED) hoặc R04!
        else if (afterAskConditionWithoutTimeAnswer && !customerActivelyAsksPriceOrPackageNow
                && !priorState.priceDiscussed()
                && (SentimentIntentLayer.isConversationStyleComplaint(msg) || SentimentIntentLayer.isWordingOrExpressionFeedback(msg))
                && classified != DetectedIntent.PRICE_OBJECTION
                && !isPriceObjectionMessage(msg)
                && !(isFrustrationMessage(msg) && priorState.priceRequestedInSession() && !priorState.priceDiscussed()
                    && priorState.conditionAskedCount() >= 1 && priorState.frustrationCount() == 0)
                && classified != DetectedIntent.PUSHBACK_ON_ASSUMPTION
                && !SentimentIntentLayer.isNegationOrPushbackOnAssumption(msg)
                && classified != DetectedIntent.META_CONVERSATION_ON_BOT_BEHAVIOR
                && classified != DetectedIntent.ANTI_APOLOGY_PUSHBACK
                && !SentimentIntentLayer.isAntiApologyPushback(msg)
                && !SentimentIntentLayer.isMetaConversationOnBotBehavior(msg)) {
            coreAction = PrimaryAction.GENERIC_SHORT_DISCOVERY;
            intent = "CONVERSATION_STYLE_REPAIR";
            skillCode = "spa_frustration_rescue";
        }
        // R00B-PUSHBACK-ON-ASSUMPTION ("Căng gì, tôi bị căng đâu", "không bị căng", "đâu có mỏi")
        else if (classified == DetectedIntent.PUSHBACK_ON_ASSUMPTION
                || SentimentIntentLayer.isNegationOrPushbackOnAssumption(msg)) {
            coreAction = PrimaryAction.ACKNOWLEDGE_AND_ASK_PREFERENCE;
            intent = "PUSHBACK_ON_ASSUMPTION_REPAIR";
            skillCode = "spa_condition_first_discovery";
        }
        // R00C-META-CONVERSATION-ON-BOT-BEHAVIOR & ANTI_APOLOGY_PUSHBACK ("Sao chào lắm thế", "Hâm à mắc j xin loi", "Nghe hay đấy cơ mà làm gì mà phải xin lỗi")
        else if (classified == DetectedIntent.META_CONVERSATION_ON_BOT_BEHAVIOR
                || classified == DetectedIntent.ANTI_APOLOGY_PUSHBACK
                || SentimentIntentLayer.isAntiApologyPushback(msg)
                || SentimentIntentLayer.isMetaConversationOnBotBehavior(msg)) {
            if (SentimentIntentLayer.isGreetingMetaComment(msg)) {
                coreAction = PrimaryAction.GENERIC_SHORT_DISCOVERY;
                intent = "META_COMMENT_ON_GREETING";
                skillCode = "spa_discovery_ask_one_need";
            } else if (SentimentIntentLayer.isAntiApologyPushback(msg)
                    && !SentimentIntentLayer.hasPositiveServiceInterestSignal(msg)) {
                coreAction = PrimaryAction.GENERIC_SHORT_DISCOVERY;
                intent = "ANTI_APOLOGY_PUSHBACK";
                skillCode = hasKnownSymptomsOrScopes ? "spa_condition_first_discovery" : "spa_discovery_ask_one_need";
            } else if (priorState.priceRequestedInSession() && !priorState.priceDiscussed() && hasKnownContext && enriched.conditionClarified()) {
                coreAction = PrimaryAction.ANSWER_PRICE_ONCE;
                intent = "META_APOLOGY_POSITIVE_INTEREST_QUOTE";
                skillCode = "spa_recommend_and_quote_if_requested";
            } else {
                coreAction = PrimaryAction.RECOMMEND_ONE_PACKAGE;
                intent = "META_APOLOGY_POSITIVE_INTEREST_ADVANCE";
                skillCode = "spa_recommend_after_condition";
            }
        }
        // R00D-CONFIRM-PREVIOUS-INFO ("Hiểu đúng rồi còn gì, có phải hiểu lầm đâu", "Đúng rồi đó em")
        else if (classified == DetectedIntent.CONFIRM_PREVIOUS_INFO
                || SentimentIntentLayer.isConfirmPreviousInfoMessage(msg)) {
            if (priorState.priceRequestedInSession() && !priorState.priceDiscussed() && hasKnownContext && enriched.conditionClarified()) {
                coreAction = PrimaryAction.ANSWER_PRICE_ONCE;
                intent = "CONFIRM_PREVIOUS_INFO_QUOTE";
                skillCode = "spa_recommend_and_quote_if_requested";
            } else {
                coreAction = PrimaryAction.RECOMMEND_ONE_PACKAGE;
                intent = "CONFIRM_PREVIOUS_INFO_ADVANCE";
                skillCode = "spa_recommend_after_condition";
            }
        }
        // R01D-FRUSTRATION-DIRECT-CONCRETE-SOLUTION (Priority 7: "Dở vừa, ns linh ta linh tinh", "Rồi định chăm sóc cái j cho t, t phát bực r")
        else if (classified == DetectedIntent.FRUSTRATION_DEMANDING_SOLUTION
                || SentimentIntentLayer.isFrustrationDemandingSolution(msg, priorState)) {
            coreAction = PrimaryAction.DIRECT_CONCRETE_SOLUTION;
            intent = "FRUSTRATION_DEMANDING_SOLUTION";
            skillCode = "spa_frustration_rescue";
        }
        // R01-FRUSTRATION-RESCUE (Priority 10: Resolve outstanding customer request first; never default to sales pitch!)
        else if (classified == DetectedIntent.FRUSTRATION || isFrustrationMessage(msg)) {
            if (enriched.hasUnresolvedCustomerRequest()) {
                ConversationState.UnresolvedCustomerRequest unresolved = enriched.latestUnresolvedRequest();
                boolean hasGroundedAnswer = fusedChunks == null
                        || (!fusedChunks.isEmpty() && hasGroundedEvidenceForDetailRequest(unresolved, enriched, fusedChunks));
                if (unresolved.requiresHumanAssistance() || !hasGroundedAnswer) {
                    return new RoutingDecision(
                            PrimaryAction.HARD_HANDOFF_COMPLAINT,
                            PrimaryAction.HARD_HANDOFF_COMPLAINT,
                            false,
                            1,
                            "UNRESOLVED_FAQ_HANDOFF",
                            "spa_frustration_rescue"
                    );
                }
                coreAction = PrimaryAction.SERVICE_DETAIL_AND_FAQ_QA;
                intent = "FRUSTRATION_RECOVERY_ANSWER_UNRESOLVED_FAQ";
                skillCode = "spa_frustration_rescue";
            } else if (priorState.priceRequestedInSession()
                    && !priorState.priceDiscussed()
                    && hasKnownContext
                    && (enriched.conditionClarified() || priorState.conditionAskedCount() >= 1)
                    && priorState.frustrationCount() == 0) {
                CatalogOption grounded = fusedChunks == null || fusedChunks.isEmpty()
                        ? null
                        : selectBestSingleCatalogOption(msg, enriched, fusedChunks);
                if (fusedChunks != null && !fusedChunks.isEmpty() && grounded == null) {
                    return new RoutingDecision(
                            PrimaryAction.HARD_HANDOFF_COMPLAINT,
                            PrimaryAction.HARD_HANDOFF_COMPLAINT,
                            false,
                            1,
                            "UNGROUNDED_SCOPE_HANDOFF",
                            "complaint_immediate_handoff"
                    );
                }
                coreAction = PrimaryAction.ANSWER_PRICE_ONCE;
                intent = "FRUSTRATION_RECOVERY_QUOTE_PENDING_PRICE";
                skillCode = "spa_recommend_and_quote_if_requested";
            } else if ((priorState.frustrationCount() >= 1 && !priorState.packageRecommended() && !priorState.priceDiscussed() && !priorState.packageInterestAccepted())
                    || priorState.frustrationCount() >= 2
                    || (!hasKnownContext && !priorState.priceRequestedInSession())) {
                return new RoutingDecision(
                        PrimaryAction.HARD_HANDOFF_COMPLAINT,
                        PrimaryAction.HARD_HANDOFF_COMPLAINT,
                        false,
                        1,
                        "FRUSTRATION_HANDOFF",
                        "spa_frustration_rescue"
                );
            } else {
                coreAction = PrimaryAction.GENERIC_SHORT_DISCOVERY;
                intent = "FRUSTRATION_CONCERN_ACKNOWLEDGEMENT";
                skillCode = "spa_frustration_rescue";
            }
        }
        // R12L-MID-FUNNEL-LUKEWARM-REENGAGE (v3.3.4): Khách trả lời hời hợt giữa phễu ("Ừm để xem đã", "Vậy à",
        // "Ok em" khi Bot không có câu hỏi đang chờ). Không được rơi vào AMBIGUOUS hay ép chốt lịch: giữ nguyên gói
        // đang thảo luận, khơi gợi 1 chi tiết cảm xúc đã xác minh + 1 câu hỏi mở nhẹ. KHÔNG lặp lại giá.
        else if (SentimentIntentLayer.isMidFunnelLukewarmReply(msg, priorState)) {
            coreAction = PrimaryAction.RECOMMEND_ONE_PACKAGE;
            intent = "MID_FUNNEL_LUKEWARM_REENGAGE";
            skillCode = "spa_lukewarm_mood_boost";
        }
        // R12-ACK-ADVANCE
        else if ((classified == DetectedIntent.ACK || isAcknowledgementMessage(msg))
                && !isPriceInquiryMessage(msg)) {
            if (priorState.trialInviteOffered()
                    || priorState.downsellOffered()
                    || priorState.packageInterestAccepted()
                    || priorState.objectionCount() >= 1
                    || "COLLECTING_INFO".equals(priorState.bookingStatus())
                    || "INVITE_ONE_SESSION_TRIAL".equals(priorState.lastQuestionIntent())
                    || "CONFIRM_BOOKING_TIME".equals(priorState.lastQuestionIntent())
                    || "ASK_BOOKING_TIME".equals(priorState.lastQuestionIntent())) {
                coreAction = PrimaryAction.CONFIRM_BOOKING_DETAILS;
                intent = "TRIAL_ACCEPTANCE";
                skillCode = "spa_trial_acceptance_booking";
            } else {
                coreAction = PrimaryAction.ADVANCE_AFTER_ACK;
                intent = "ACKNOWLEDGEMENT";
                skillCode = "spa_booking_advance_after_ack";
            }
        }
        // R10B-STYLE-COMPLAINT-FIRST (v3.8): góp ý văn phong (kể cả kèm chê đắt trong cùng lượt) -> xử lý giao tiếp trước,
        // KHÔNG hạ gói; chỉ quay lại thương lượng giá khi lượt sau khách vẫn bàn về giá.
        else if (SentimentIntentLayer.isConversationStyleComplaint(msg)
                && !SentimentIntentLayer.isStrongRejection(msg)) {
            coreAction = PrimaryAction.GENERIC_SHORT_DISCOVERY;
            intent = "CONVERSATION_STYLE_REPAIR";
            skillCode = "spa_frustration_rescue";
        }
        // R11-UNIT-VALUE
        else if (classified == DetectedIntent.PRICE_COMPARISON || isUnitPriceComparisonMessage(msg)) {
            coreAction = PrimaryAction.EXPLAIN_UNIT_VALUE;
            intent = "PRICE_COMPARISON";
            skillCode = "price_objection_soft_anchor";
        }
        // R11B — ALTERNATIVE_PACKAGE_REQUEST ("Có gói khác k", "Còn gói nào nữa không")
        else if (classified == DetectedIntent.ALTERNATIVE_PACKAGE_REQUEST
                || SentimentIntentLayer.isAlternativePackageRequest(msg)) {
            List<CatalogOption> altOpts = selectAlternativeCatalogOptions(priorState, fusedChunks, msg);
            if (!altOpts.isEmpty()) {
                if (priorState.priceRequestedInSession() || priorState.priceDiscussed()) {
                    coreAction = PrimaryAction.ANSWER_PRICE_ONCE;
                    intent = "ALTERNATIVE_PACKAGE_REQUEST";
                    skillCode = "spa_recommend_and_quote_if_requested";
                } else {
                    coreAction = PrimaryAction.RECOMMEND_ONE_PACKAGE;
                    intent = "ALTERNATIVE_PACKAGE_REQUEST";
                    skillCode = "spa_recommend_after_condition";
                }
            } else {
                return new RoutingDecision(
                        PrimaryAction.HARD_HANDOFF_COMPLAINT,
                        PrimaryAction.HARD_HANDOFF_COMPLAINT,
                        false,
                        1,
                        "NO_LOWER_PACKAGE_HANDOFF",
                        "spa_objection_no_lower_package_handoff"
                );
            }
        }
        // R11 & R12 — THREE-STAGE RETENTION ON PRICE OBJECTION / HESITATION
        else if (classified == DetectedIntent.PRICE_OBJECTION
                || classified == DetectedIntent.EXPLICIT_DOWNSELL_REQUEST
                || classified == DetectedIntent.PACKAGE_REJECTION
                || isPriceObjectionMessage(msg)
                || (priorState.objectionCount() >= 1 && !priorState.downsellOffered() && isHesitationAfterObjection)) {
            boolean isExplicitDownsellOrReject = (classified == DetectedIntent.EXPLICIT_DOWNSELL_REQUEST
                    || classified == DetectedIntent.PACKAGE_REJECTION
                    || SentimentIntentLayer.isExplicitDownsellRequest(msg, priorState)
                    || SentimentIntentLayer.isPackageRejection(msg));
            boolean asksPromoOrDiscount = lower.matches("(?isU).*\\b(giảm\\s+giá|khuyến\\s+mãi|ưu\\s+đãi|voucher|thẻ\\s+liệu\\s+trình)\\b.*");
            CatalogOption lowerOpt = selectLowerCatalogOption(priorState, fusedChunks, msg);

            if (priorState.objectionCount() == 0 && !priorState.usedTemplates().contains("PRICE_OBJECTION_FIRST_TIME")) {
                // R11-FIRST-PRICE-OBJECTION-CORRECTED (Stage 1: Hold package + Value defense + 1-session trial 🥰)
                if (isExplicitDownsellOrReject && !asksPromoOrDiscount) {
                    if (lowerOpt != null) {
                        coreAction = PrimaryAction.HANDLE_PRICE_OBJECTION_FIRST_TIME;
                        intent = "EXPLICIT_DOWNSELL_WITH_PACKAGE";
                        skillCode = "spa_objection_explicit_downsell";
                    } else {
                        return new RoutingDecision(
                                PrimaryAction.HARD_HANDOFF_COMPLAINT,
                                PrimaryAction.HARD_HANDOFF_COMPLAINT,
                                false,
                                1,
                                "NO_LOWER_PACKAGE_HANDOFF",
                                "spa_objection_no_lower_package_handoff"
                        );
                    }
                } else {
                    coreAction = PrimaryAction.HANDLE_PRICE_OBJECTION_FIRST_TIME;
                    intent = asksPromoOrDiscount ? "PRICE_OBJECTION_WITH_PROMO_INQUIRY" : "PRICE_OBJECTION_VALUE_TRIAL";
                    skillCode = "spa_objection_value_trial";
                }
            } else {
                // R12-SECOND-PRICE-OBJECTION-CORRECTED (Stage 2: Respect consideration + Clarify + 1 lower KB option)
                if (!priorState.downsellOffered()) {
                    if (lowerOpt != null) {
                        coreAction = PrimaryAction.HANDLE_PRICE_OBJECTION_REPEAT;
                        intent = "SECOND_OBJECTION_DOWNSELL";
                        skillCode = "spa_objection_dynamic_downsell";
                    } else {
                        return new RoutingDecision(
                                PrimaryAction.HARD_HANDOFF_COMPLAINT,
                                PrimaryAction.HARD_HANDOFF_COMPLAINT,
                                false,
                                1,
                                "NO_LOWER_PACKAGE_HANDOFF",
                                "spa_objection_no_lower_package_handoff"
                        );
                    }
                } else {
                    // Stage 3: Already offered downsell and customer still objects/hesitates -> Graceful exit
                    coreAction = PrimaryAction.GENERIC_SHORT_DISCOVERY;
                    intent = "STAGE_3_GRACEFUL_EXIT";
                    skillCode = "spa_stage3_graceful_exit";
                }
            }
        }
        // R09-BOOKING-ADVANCE
        else if (BOOKING_INTENT_PATTERN.matcher(lower).matches()
                && (priorState.packageRecommended()
                    || (!PROBLEM_OR_SYMPTOM_PATTERN.matcher(lower).matches() && !lower.contains("có gói nào")))) {
            coreAction = PrimaryAction.CONFIRM_BOOKING_DETAILS;
            intent = "BOOKING_INTENT";
            skillCode = lower.contains("thêm") || lower.contains("kết hợp")
                    ? "upsell_one_addon"
                    : "spa_booking_advance_after_ack";
        }
        // R02-CONDITION-FIRST-DISCOVERY (Priority 20: Condition first, pressure later)
        else if ((PROBLEM_OR_SYMPTOM_PATTERN.matcher(lower).matches()
                    || (!enriched.knownSymptoms().isEmpty() && !isPriceInquiryMessage(msg))
                    || (!ConversationState.extractTargetScopes(lower, domainKeywords).isEmpty()
                        && !isServiceFamilySelectionMessage(msg)
                        && !isPriceInquiryMessage(msg)))
                && priorState.conditionAskedCount() == 0
                && !enriched.conditionClarified()
                && !hasExplicitDurationInMessage(lower)) {
            coreAction = PrimaryAction.ACKNOWLEDGE_AND_ASK_PREFERENCE;
            intent = "ASK_CONDITION_ONCE";
            skillCode = "spa_condition_first_discovery";
        }
        // R03-RECOMMEND-AND-QUOTE-WHEN-PRICE-WAS-REQUESTED (Priority 30 — Scope-Grounded!)
        else if (priorState.priceRequestedInSession()
                && (!priorState.priceDiscussed()
                    || (!ConversationState.extractTargetScopes(lower, domainKeywords).isEmpty()
                        && !priorState.knownBodyAreas().containsAll(ConversationState.extractTargetScopes(lower, domainKeywords))))
                && !isPriceInquiryMessage(msg)
                && (enriched.conditionClarified() || priorState.conditionAskedCount() >= 1)
                && hasKnownContext) {
            CatalogOption grounded = selectBestSingleCatalogOption(msg, enriched, fusedChunks);
            if (grounded == null) {
                return new RoutingDecision(
                        PrimaryAction.HARD_HANDOFF_COMPLAINT,
                        PrimaryAction.HARD_HANDOFF_COMPLAINT,
                        false,
                        1,
                        "UNGROUNDED_SCOPE_HANDOFF",
                        "complaint_immediate_handoff"
                );
            }
            coreAction = PrimaryAction.ANSWER_PRICE_ONCE;
            intent = "RECOMMEND_AND_QUOTE_IF_PRICE_REQUESTED";
            skillCode = "spa_recommend_and_quote_if_requested";
        }
        // R04-RECOMMEND-AFTER-CONDITION-ONCE (Priority 40 — Scope-Grounded!)
        else if (!isPriceInquiryMessage(msg)
                && !isServiceFamilySelectionMessage(msg)
                && (enriched.conditionClarified() || priorState.conditionAskedCount() >= 1 || PREFERENCE_ANSWER_PATTERN.matcher(lower).matches())
                && (!priorState.packageRecommended()
                    || (!ConversationState.extractTargetScopes(lower, domainKeywords).isEmpty()
                        && !priorState.knownBodyAreas().containsAll(ConversationState.extractTargetScopes(lower, domainKeywords))))
                && hasKnownContext) {
            CatalogOption grounded = selectBestSingleCatalogOption(msg, enriched, fusedChunks);
            if (grounded == null) {
                return new RoutingDecision(
                        PrimaryAction.HARD_HANDOFF_COMPLAINT,
                        PrimaryAction.HARD_HANDOFF_COMPLAINT,
                        false,
                        1,
                        "UNGROUNDED_SCOPE_HANDOFF",
                        "complaint_immediate_handoff"
                );
            }
            coreAction = PrimaryAction.RECOMMEND_ONE_PACKAGE;
            intent = "RECOMMEND_AFTER_CONDITION";
            skillCode = "spa_recommend_after_condition";
        }
        // R04B-CONDITION-FOLLOWUP-AFTER-QUOTE (When customer answers condition frequency e.g. "thi thoảng bị" after package/price was already recommended)
        else if (!isPriceInquiryMessage(msg)
                && !isServiceFamilySelectionMessage(msg)
                && isConditionFollowUpMessage(msg)
                && (priorState.packageRecommended() || priorState.priceDiscussed())
                && hasKnownContext) {
            coreAction = PrimaryAction.RECOMMEND_ONE_PACKAGE;
            intent = "CONDITION_FOLLOWUP_AFTER_QUOTE_ADVANCE";
            skillCode = "spa_recommend_after_condition";
        }
        // R08-SERVICE-FAMILY-SELECTION-SHORTCUT
        else if (isServiceFamilySelectionMessage(msg)) {
            if (priorState.packageRecommended() && priorState.priceDiscussed()) {
                coreAction = PrimaryAction.CONFIRM_BOOKING_DETAILS;
                intent = "BOOKING_AFTER_SERVICE_SELECTED";
                skillCode = "spa_booking_advance_after_ack";
            } else if (priorState.priceRequestedInSession() && !priorState.priceDiscussed()) {
                if (hasExplicitDurationInMessage(lower) || priorState.clarifyDurationCount() >= 1) {
                    coreAction = PrimaryAction.ANSWER_PRICE_ONCE;
                    intent = "EXPLICIT_PRICE_INQUIRY";
                    skillCode = "price_answer_from_docs";
                } else {
                    coreAction = PrimaryAction.CLARIFY_SERVICE_DURATION;
                    intent = "CLARIFY_DURATION_ONCE";
                    skillCode = "spa_clarify_duration_once";
                }
            } else if (!enriched.conditionClarified() && priorState.conditionAskedCount() == 0 && !enriched.knownSymptoms().isEmpty()) {
                coreAction = PrimaryAction.ACKNOWLEDGE_AND_ASK_PREFERENCE;
                intent = "ASK_CONDITION_ONCE";
                skillCode = "spa_condition_first_discovery";
            } else {
                coreAction = PrimaryAction.RECOMMEND_ONE_PACKAGE;
                intent = "SET_SERVICE_FAMILY_AND_ADVANCE";
                skillCode = "spa_service_family_shortcut";
            }
        }
        // R05 / R06 / R07 / R10 — Price Inquiries
        else if (isPriceInquiryMessage(msg)) {
            boolean asksSpecificDifferent = hasExplicitDurationInMessage(lower)
                    || matchSpecificCatalogOption(msg, null, fusedChunks) != null;
            if (priorState.priceDiscussed() && !asksSpecificDifferent) {
                if (priorState.priceRepeatGuardCount() >= 2) {
                    return new RoutingDecision(
                            PrimaryAction.HARD_HANDOFF_COMPLAINT,
                            PrimaryAction.HARD_HANDOFF_COMPLAINT,
                            false,
                            1,
                            "PRICE_REPEAT_HANDOFF",
                            "spa_price_repeat_guard"
                    );
                }
                coreAction = PrimaryAction.ANSWER_PRICE_ONCE;
                intent = "POLITE_PRICE_REMINDER";
                skillCode = "price_answer_from_docs";
            } else if (!enriched.knownBodyAreas().isEmpty()
                    && fusedChunks != null && !fusedChunks.isEmpty()
                    && selectBestSingleCatalogOption(msg, enriched, fusedChunks) == null) {
                return new RoutingDecision(
                        PrimaryAction.HARD_HANDOFF_COMPLAINT,
                        PrimaryAction.HARD_HANDOFF_COMPLAINT,
                        false,
                        1,
                        "UNGROUNDED_SCOPE_HANDOFF",
                        "complaint_immediate_handoff"
                );
            } else if (matchSpecificCatalogOption(msg, enriched, fusedChunks) != null
                    || priorState.packageRecommended()
                    || (enriched.serviceFamilyKnown() && (hasExplicitDurationInMessage(lower) || priorState.clarifyDurationCount() >= 1))) {
                coreAction = PrimaryAction.ANSWER_PRICE_ONCE;
                intent = "EXPLICIT_PRICE_INQUIRY";
                skillCode = "price_answer_from_docs";
            } else if ((priorState.conditionAskedCount() >= 1
                        || enriched.conditionClarified()
                        || (priorState.confirmedScopes() != null && !priorState.confirmedScopes().isEmpty()
                            && (!priorState.forbiddenScopes().isEmpty() || !priorState.apologizedErrorIds().isEmpty())))
                    && (!enriched.knownBodyAreas().isEmpty() || !enriched.confirmedScopes().isEmpty())) {
                coreAction = PrimaryAction.ANSWER_PRICE_ONCE;
                intent = "RECOMMEND_AND_QUOTE_IF_PRICE_REQUESTED";
                skillCode = "spa_recommend_and_quote_if_requested";
            } else if ((enriched.serviceFamilyKnown() || !enriched.knownBodyAreas().isEmpty())
                    && priorState.clarifyDurationCount() == 0) {
                coreAction = PrimaryAction.CLARIFY_SERVICE_DURATION;
                intent = "CLARIFY_DURATION_ONCE";
                skillCode = "spa_clarify_duration_once";
            } else if (priorState.clarifyServiceTypeCount() == 0 && !enriched.serviceFamilyKnown()) {
                coreAction = PrimaryAction.CLARIFY_SERVICE_DURATION;
                intent = "CLARIFY_SERVICE_TYPE_ONCE";
                skillCode = "spa_clarify_service_type_once";
            } else {
                coreAction = PrimaryAction.ANSWER_PRICE_ONCE;
                intent = "EXPLICIT_PRICE_INQUIRY";
                skillCode = "price_answer_from_docs";
            }
        } else if (priorState.priceRequestedInSession() && !priorState.priceDiscussed() && hasExplicitDurationInMessage(lower)) {
            coreAction = PrimaryAction.ANSWER_PRICE_ONCE;
            intent = "EXPLICIT_PRICE_INQUIRY";
            skillCode = "price_answer_from_docs";
        } else if (priorState.known().hasCustomerProblemOrNeed() && PREFERENCE_ANSWER_PATTERN.matcher(lower).matches()) {
            coreAction = PrimaryAction.RECOMMEND_ONE_PACKAGE;
            intent = "RECOMMEND_AFTER_CONDITION";
            skillCode = "spa_recommend_after_condition";
        } else if (SCHEDULE_LATER_PATTERN.matcher(lower).matches()) {
            coreAction = PrimaryAction.GENERIC_SHORT_DISCOVERY;
            intent = "HESITATION_LATER";
            skillCode = "discovery_ask_one_need";
        } else if (!priorState.greetingSent() && CustomerInsightEngine.isPureGreetingOrOpening(lower, true)) {
            coreAction = PrimaryAction.GENERIC_SHORT_DISCOVERY;
            intent = "GREETING";
            skillCode = "greeting_warm_intro";
        } else if (enriched.hasUnresolvedCustomerRequest()) {
            ConversationState.UnresolvedCustomerRequest unresolved = enriched.latestUnresolvedRequest();
            if (fusedChunks != null
                    && (fusedChunks.isEmpty()
                        || detectConflictingCatalogOrFaqEvidence(unresolved != null ? unresolved.referencedEntity() : msg, enriched, fusedChunks)
                        || !hasGroundedEvidenceForDetailRequest(unresolved, enriched, fusedChunks))) {
                return new RoutingDecision(
                        PrimaryAction.SAFE_REFUSAL_OR_HANDOFF_IF_REPEATED,
                        PrimaryAction.SAFE_REFUSAL_OR_HANDOFF_IF_REPEATED,
                        false,
                        1,
                        "UNSUPPORTED_DETAIL_OR_FAQ_QUESTION",
                        "spa_service_detail_faq_qa"
                );
            }
            coreAction = PrimaryAction.SERVICE_DETAIL_AND_FAQ_QA;
            intent = "SERVICE_DETAIL_AND_FAQ_QA";
            skillCode = "spa_service_detail_faq_qa";
        } else if (priorState.greetingSent()
                && (priorState.ambiguousCount() >= 1 || "CLARIFY_AMBIGUOUS".equals(priorState.lastQuestionIntent()))) {
            coreAction = PrimaryAction.GENERIC_SHORT_DISCOVERY;
            intent = "AMBIGUOUS_FOLLOWUP_ASSIST";
            skillCode = "spa_frustration_rescue";
        } else if (hasKnownSymptomsOrScopes) {
            coreAction = PrimaryAction.GENERIC_SHORT_DISCOVERY;
            intent = "CONVERSATION_STYLE_REPAIR";
            skillCode = "spa_frustration_rescue";
        } else {
            coreAction = PrimaryAction.GENERIC_SHORT_DISCOVERY;
            intent = !priorState.greetingSent() ? "SERVICE_DISCOVERY" : "AMBIGUOUS_CONFIRM_ONCE";
            skillCode = !priorState.greetingSent() ? "greeting_warm_intro" : "spa_ambiguous_confirm_once";
        }

        // Hard lock: Một khi đã có confirmedScopes hoặc knownSymptoms, TUYỆT ĐỐI CẤM trả về spa_ambiguous_confirm_once
        if (hasKnownSymptomsOrScopes
                && ("spa_ambiguous_confirm_once".equals(skillCode) || "AMBIGUOUS_CONFIRM_ONCE".equals(intent))) {
            coreAction = PrimaryAction.GENERIC_SHORT_DISCOVERY;
            intent = "CONVERSATION_STYLE_REPAIR";
            skillCode = "spa_frustration_rescue";
        }

        OpeningPolicy openingPolicy = resolveOpeningPolicy(priorState, enriched, coreAction);
        PrimaryAction topAction = openingPolicy == OpeningPolicy.BRAND_INTRO ? PrimaryAction.OPENING_THEN_RESPOND : coreAction;
        int maxBubblesForLlm = (coreAction == PrimaryAction.SERVICE_DETAIL_AND_FAQ_QA
                || coreAction == PrimaryAction.REPAIR_AND_RECONFIRM
                || coreAction == PrimaryAction.DIRECT_CONCRETE_SOLUTION
                || coreAction == PrimaryAction.CLARIFY_SERVICE_DURATION
                || coreAction == PrimaryAction.ADVANCE_AFTER_ACK
                || "CONVERSATION_STYLE_REPAIR".equals(intent)
                || "WORDING_FEEDBACK_REPAIR".equals(intent)
                || "WORDING_FEEDBACK_PRESERVE_BOOKING_FUNNEL".equals(intent)
                || "META_COMMENT_ON_GREETING".equals(intent)
                || "META_APOLOGY_POSITIVE_INTEREST_ADVANCE".equals(intent)
                || "CONFIRM_PREVIOUS_INFO_ADVANCE".equals(intent)
                || "CONDITION_FOLLOWUP_AFTER_QUOTE_ADVANCE".equals(intent)
                || "FRUSTRATION_CONCERN_ACKNOWLEDGEMENT".equals(intent)
                || "MID_FUNNEL_LUKEWARM_REENGAGE".equals(intent)
                || "AMBIGUOUS_FOLLOWUP_ASSIST".equals(intent)
                || "POLITE_DEFERRAL_EXIT".equals(intent)
                || "ANTI_APOLOGY_PUSHBACK".equals(intent)
                || "STAGE_3_GRACEFUL_EXIT".equals(intent)
                || "STRONG_REJECTION".equals(intent)
                || "EXPLICIT_OPT_OUT".equals(intent)) ? 1 : 2;

        return new RoutingDecision(
                topAction,
                coreAction,
                openingPolicy,
                maxBubblesForLlm,
                intent,
                skillCode,
                RoutingDecision.resolveRouterRuleId(coreAction, intent)
        );
    }

    /** Chọn OpeningPolicy cho lượt hiện tại dựa trên NỘI DUNG khách vừa nói (không dùng boolean dùng chung). */
    static OpeningPolicy resolveOpeningPolicy(ConversationState priorState, ConversationState enriched, PrimaryAction coreAction) {
        if (priorState.greetingSent() || coreAction == PrimaryAction.BOT_MUTED) {
            return OpeningPolicy.NONE;
        }
        if (coreAction == PrimaryAction.HARD_HANDOFF_SAFETY
                || coreAction == PrimaryAction.HARD_HANDOFF_COMPLAINT
                || coreAction == PrimaryAction.SAFE_REFUSAL_OR_HANDOFF_IF_REPEATED) {
            return OpeningPolicy.SAFETY_OR_REPAIR;
        }
        ConversationState facts = enriched != null ? enriched : priorState;
        boolean hasSymptomOrScope = (facts.knownSymptoms() != null && !facts.knownSymptoms().isEmpty())
                || (facts.confirmedScopes() != null && !facts.confirmedScopes().isEmpty());
        boolean directAction = coreAction == PrimaryAction.SERVICE_DETAIL_AND_FAQ_QA
                || coreAction == PrimaryAction.ANSWER_PRICE_ONCE
                || coreAction == PrimaryAction.RECOMMEND_ONE_PACKAGE
                || coreAction == PrimaryAction.CONFIRM_BOOKING_DETAILS
                || coreAction == PrimaryAction.DIRECT_CONCRETE_SOLUTION
                || coreAction == PrimaryAction.EXPLAIN_UNIT_VALUE
                || coreAction == PrimaryAction.REPAIR_AND_RECONFIRM;
        if (hasSymptomOrScope || directAction) {
            return OpeningPolicy.DIRECT_ANSWER;
        }
        if (facts.serviceFamilyKnown()) {
            return OpeningPolicy.SHORT_GREETING;
        }
        return OpeningPolicy.BRAND_INTRO;
    }

    /**
     * Khởi tạo danh sách {@link ReplyBubble} chuẩn xác theo {@link RoutingDecision},
     * dữ liệu Catalog từ {@code fusedChunks} và cấu hình {@link PromptBuilder.PagePersona} của Tenant.
     */
    public static List<ReplyBubble> executeActionBubbles(
            RoutingDecision decision,
            String userMessage,
            ConversationState state,
            String honorific,
            PromptBuilder.PagePersona pagePersona,
            List<ReciprocalRankFusion.FusedChunk> fusedChunks,
            AgentSkillDto activeMicroSkill
    ) {
        if (decision == null || decision.primaryAction() == PrimaryAction.BOT_MUTED) {
            return List.of();
        }

        PagePersonaConfig cfg = pagePersona != null ? pagePersona.resolvedConfig() : PagePersonaConfig.empty();
        ConversationState base = state != null ? state : ConversationState.initial();
        ConversationState enriched = base.ensureTurnEnriched(userMessage, cfg.safeDomainKeywords());
        ConversationState priorState = enriched.priorTurnState();

        String h = (honorific != null && !honorific.isBlank()) ? honorific.strip() : cfg.resolvedDefaultCustomerPronoun();
        String capH = Character.toUpperCase(h.charAt(0)) + h.substring(1);
        String selfP = cfg.resolvedSelfPronoun();
        String capSelfP = Character.toUpperCase(selfP.charAt(0)) + selfP.substring(1);
        String shopName = (pagePersona != null && pagePersona.name() != null && !pagePersona.name().isBlank())
                ? pagePersona.name().strip()
                : "Shop";
        String shopDesc = pagePersona != null ? pagePersona.description() : null;
        int postPriceDelayMs = cfg.resolvedPostPriceDelayMs();

        String intent = decision.detectedIntent() != null ? decision.detectedIntent() : "";
        PrimaryAction eff = decision.effectiveResponseAction();

        // PRE-02-NEUROLOGICAL-OR-SAFETY-HANDOFF
        if (eff == PrimaryAction.HARD_HANDOFF_SAFETY) {
            if ("NEUROLOGICAL_RISK".equals(intent) || SentimentIntentLayer.isNeurologicalRiskMessage(userMessage)) {
                String neuroText = "Dạ những dấu hiệu này có thể cần được xử trí y tế khẩn cấp, nên " + h
                        + " hãy gọi cấp cứu hoặc đến cơ sở y tế gần nhất ngay, đừng chờ đợi thêm ạ. "
                        + capSelfP + " cũng chuyển chuyên viên hỗ trợ " + h + " ngay nhé.";
                return List.of(new ReplyBubble(neuroText, 0));
            }
            String safetyText = "Dạ trường hợp này cần chuyên viên kiểm tra trực tiếp để an toàn và chính xác cho "
                    + h + " ạ. " + capSelfP + " chuyển người hỗ trợ " + h + " ngay nhé.";
            return List.of(new ReplyBubble(safetyText, 0));
        }

        // PRE-03-EXPLICIT-OPT-OUT
        if ("EXPLICIT_OPT_OUT".equals(intent)) {
            String stopText = "Dạ " + selfP + " xin phép dừng nhắc lại để tránh làm phiền " + h
                    + " ạ. Khi nào cần tư vấn, " + h + " nhắn giúp " + selfP + " từ khóa \"tư vấn\" nhé.";
            return List.of(new ReplyBubble(stopText, 0));
        }

        // STRONG_REJECTION (1 bubble, 0 sales CTA, 0 trial push, 0 questions)
        if ("STRONG_REJECTION".equals(intent)) {
            String normReject = VietnameseQueryNormalizer.normalizeForClassification(userMessage);
            boolean lostMood = normReject.contains("tụt hứng")
                    || normReject.contains("mất hứng")
                    || normReject.contains("hết hứng")
                    || normReject.contains("dẹp");
            String needVerb = cfg.isNonSpaIndustry() ? "cần tư vấn dịch vụ" : "cần thư giãn";
            String breakupText = lostMood
                    ? "Dạ " + selfP + " xin lỗi vì cuộc trò chuyện vừa rồi làm " + h + " chưa thoải mái ạ. "
                        + "Khi nào " + h + " " + needVerb + " hay cần hỗ trợ thêm thì nhắn " + selfP + " bất cứ lúc nào nhé ạ."
                    : "Dạ không sao ạ, " + selfP + " cảm ơn " + h + " đã quan tâm " + shopName
                        + ". Khi nào " + h + " " + needVerb + " hay hỗ trợ thêm thì nhắn " + selfP + " bất cứ lúc nào nhé ạ.";
            return List.of(new ReplyBubble(breakupText, 0));
        }

        // R00W-WORDING-FEEDBACK-REPAIR (v3.8): 1 bubble, tiếp nhận góp ý ngắn gọn, tự nhiên, tuyệt đối không kèm quảng cáo dịch vụ hay icon 💙 trong cùng lượt.
        if ("WORDING_FEEDBACK_PRESERVE_BOOKING_FUNNEL".equals(intent)) {
            String preserveText = "Dạ cảm ơn " + h + " đã góp ý nha, " + selfP
                    + " rút kinh nghiệm nói chuyện tự nhiên hơn ạ. Hôm nay hay ngày mai " + h
                    + " tiện ghé vào tầm mấy giờ để " + selfP + " sắp xếp lịch đón " + h + " nhé?";
            return List.of(new ReplyBubble(preserveText, 0));
        }
        if ("WORDING_FEEDBACK_REPAIR".equals(intent)) {
            String wordingText = "Dạ cảm ơn " + h + " đã góp ý thẳng thắn ạ, " + selfP
                    + " xin rút kinh nghiệm để nói chuyện ngắn gọn, tự nhiên và đi thẳng vào trọng tâm hơn với " + h + " nhé ạ.";
            return List.of(new ReplyBubble(wordingText, 0));
        }

        // R00-MISMATCH-CORRECTION-HANDOFF & UNGROUNDED_SCOPE_HANDOFF (1 bubble, 0 questions)
        if ("MISMATCH_CORRECTION_HANDOFF".equals(intent) || "UNGROUNDED_SCOPE_HANDOFF".equals(intent)) {
            String trueProblem = ConversationState.extractProblemOrNeedPhrase(userMessage, cfg.safeDomainKeywords());
            if (trueProblem == null || trueProblem.isBlank()) {
                trueProblem = enriched.known().hasCustomerProblemOrNeed()
                        ? enriched.known().customerProblemOrNeed()
                        : null;
            }
            if ("MISMATCH_CORRECTION_HANDOFF".equals(intent)) {
                String mismatchText = "Dạ " + selfP + " thành thật xin lỗi " + h + " vì đã tư vấn chưa đúng tình trạng "
                        + (trueProblem != null ? trueProblem + " của " + h : "của " + h)
                        + " ạ. " + capSelfP + " xin phép chuyển quản lý và nhân viên hỗ trợ trực tiếp cho " + h + " ngay nhé.";
                return List.of(new ReplyBubble(mismatchText, 0));
            }
            String ungroundedText = "Dạ với tình trạng " + (trueProblem != null ? trueProblem : "hiện tại") + " của " + h
                    + ", hiện trong bảng dịch vụ tiêu chuẩn bên " + selfP + " chưa có gói chuyên biệt khớp đúng vùng này ạ. "
                    + capSelfP + " xin phép chuyển chuyên viên kiểm tra phương án phù hợp nhất cho " + h + " ngay nhé.";
            return List.of(new ReplyBubble(ungroundedText, 0));
        }

        // NO_LOWER_PACKAGE_HANDOFF (1 bubble, 0 questions)
        if ("NO_LOWER_PACKAGE_HANDOFF".equals(intent)
                || "spa_objection_no_lower_package_handoff".equalsIgnoreCase(decision.correspondingMicroSkillCode())) {
            String noLowerText = "Dạ hiện bên " + selfP + " không có gói tiết kiệm hơn mức này. "
                    + capSelfP + " chuyển chuyên viên xem có phương án phù hợp cho " + h + " nhé.";
            return List.of(new ReplyBubble(noLowerText, 0));
        }

        // PRICE_ESCALATION_HANDOFF (1 bubble, 0 questions)
        if ("PRICE_ESCALATION_HANDOFF".equals(intent)
                || "spa_price_escalation_handoff".equalsIgnoreCase(decision.correspondingMicroSkillCode())) {
            String escText = "Dạ " + selfP + " hiểu ạ. Phần chi phí này " + selfP
                    + " cần chuyên viên kiểm tra phương án phù hợp nhất cho " + h + ". "
                    + capSelfP + " chuyển chuyên viên tư vấn ngay nhé.";
            return List.of(new ReplyBubble(escText, 0));
        }

        // R01-FRUSTRATION-HANDOFF & UNRESOLVED-FAQ-HANDOFF
        if (eff == PrimaryAction.HARD_HANDOFF_COMPLAINT && "UNRESOLVED_FAQ_HANDOFF".equals(intent)) {
            String handoffText = "Dạ " + selfP + " thành thật xin lỗi " + h
                    + " vì tài liệu hiện tại chưa có thông tin xác thực để giải đáp chính xác câu hỏi này ạ. "
                    + capSelfP + " xin phép chuyển chuyên viên kiểm tra và phản hồi chi tiết ngay cho " + h + " nhé.";
            return List.of(new ReplyBubble(handoffText, 0));
        }

        if (eff == PrimaryAction.HARD_HANDOFF_COMPLAINT && "COMPREHENSION_ESCALATION_HANDOFF".equals(intent)) {
            String handoffText = "Dạ " + selfP + " xin lỗi vì cách giải thích vừa rồi vẫn làm " + h + " khó theo dõi ạ. "
                    + capSelfP + " xin phép chuyển chuyên viên hỗ trợ giải đáp trực tiếp và rõ ràng nhất cho " + h + " ngay nhé.";
            return List.of(new ReplyBubble(handoffText, 0));
        }

        if (eff == PrimaryAction.HARD_HANDOFF_COMPLAINT && "FRUSTRATION_HANDOFF".equals(intent)) {
            String handoffText = "Dạ " + selfP + " xin lỗi vì làm " + h + " thấy bất tiện ạ. "
                    + "Để tránh trả lời chưa sát ý " + h + ", " + selfP + " chuyển nhân viên hỗ trợ trực tiếp cho " + h + " ngay nhé.";
            return List.of(new ReplyBubble(handoffText, 0));
        }

        if (eff == PrimaryAction.HARD_HANDOFF_COMPLAINT) {
            String handoffText = "Dạ " + selfP + " xin lỗi vì trải nghiệm chưa như ý của " + h + " ạ. "
                    + capSelfP + " xin phép chuyển nhân viên hỗ trợ trực tiếp cho " + h + " ngay nhé.";
            return List.of(new ReplyBubble(handoffText, 0));
        }

        List<ReplyBubble> openingBubbles = new ArrayList<>();
        if (decision.prependsConfiguredOpening() && eff != PrimaryAction.SAFE_REFUSAL_OR_HANDOFF_IF_REPEATED) {
            List<String> openTexts = cfg.resolvedOpeningBubbles(shopName, shopDesc, h);
            if (!openTexts.isEmpty()) {
                openingBubbles.add(new ReplyBubble(openTexts.get(0), 0));
                if (openTexts.size() >= 2 && openTexts.get(1) != null && !openTexts.get(1).isBlank()) {
                    openingBubbles.add(new ReplyBubble(openTexts.get(1), 1000));
                }
                if (openTexts.size() >= 3 && openTexts.get(2) != null && !openTexts.get(2).isBlank()
                        && eff != PrimaryAction.ANSWER_PRICE_ONCE) {
                    openingBubbles.add(new ReplyBubble(openTexts.get(2), 2000));
                    return openingBubbles;
                }
            }
        }

        String lowerMsg = userMessage != null ? userMessage.toLowerCase(Locale.ROOT) : "";

        // Khi openingRequired == true và action là ANSWER_PRICE_ONCE, gộp 2 câu mở đầu thành Bubble 1
        // để Bubble 2 báo đúng 1 mức giá và Bubble 3 neo cảm xúc với delay >= postPriceDelayMs.
        if (decision.prependsConfiguredOpening() && eff == PrimaryAction.ANSWER_PRICE_ONCE) {
            CatalogOption matched = matchSpecificCatalogOption(userMessage, enriched, fusedChunks);
            if (matched == null) {
                matched = selectBestSingleCatalogOption(userMessage, enriched, fusedChunks);
            }
            if (matched != null) {
                List<String> openTexts = cfg.resolvedOpeningBubbles(shopName, shopDesc, h);
                String combinedOpen = String.join(" ", openTexts).strip();
                String priceText = "Dạ " + matched.name() + " " + matched.unitLabel() + " bên " + selfP
                        + " đang có giá " + matched.priceDisplay() + " ạ.";
                String naturalAnchorSentence = buildNaturalGroundedEmotionalAnchorSentence(matched, enriched, cfg, h, selfP);
                String anchorText = naturalAnchorSentence
                        + " Không biết với gói " + matched.unitLabel() + " này " + h
                        + " thấy phù hợp với nhu cầu hiện tại hay cần " + selfP + " giải thích rõ hơn bước nào ạ?";
                return List.of(
                        new ReplyBubble(combinedOpen, 0),
                        new ReplyBubble(priceText, 1200),
                        new ReplyBubble(anchorText, postPriceDelayMs)
                );
            }
        }

        List<ReplyBubble> responseBubbles = switch (eff) {
            case SERVICE_DETAIL_AND_FAQ_QA, SAFE_REFUSAL_OR_HANDOFF_IF_REPEATED -> buildServiceDetailAndFaqBubbles(
                    decision, userMessage, priorState, enriched, h, capH, selfP, capSelfP, cfg, fusedChunks
            );
            case REPAIR_AND_RECONFIRM -> {
                String careActor = cfg.resolvedCareActor();
                List<String> confirmed = !enriched.confirmedScopes().isEmpty()
                        ? new ArrayList<>(enriched.confirmedScopes())
                        : new ArrayList<>(enriched.knownBodyAreas());
                String trueScopePhrase = !confirmed.isEmpty()
                        ? String.join(" và ", confirmed)
                        : "nhu cầu thực tế của " + h;
                String activeErr = enriched.activeErrorId();
                boolean canApologize = activeErr != null && !activeErr.isBlank()
                        && (priorState.apologizedErrorIds() == null || !priorState.apologizedErrorIds().contains(activeErr));
                String negLabel = activeErr != null && activeErr.startsWith("WRONG_SCOPE:")
                        ? activeErr.substring("WRONG_SCOPE:".length())
                        : (!enriched.forbiddenScopes().isEmpty() ? enriched.forbiddenScopes().iterator().next() : "");
                String prefix = canApologize && !negLabel.isBlank()
                        ? "Dạ " + selfP + " xin lỗi " + h + " vì vừa nghe nhầm sang " + negLabel + " ạ. "
                        : "Dạ vâng ạ, ";
                String reconfirmText = prefix + capSelfP + " xin phép ghi chú lại để " + careActor
                        + " lưu ý chăm sóc thật kỹ phần " + trueScopePhrase + " cho " + h
                        + ". Không biết tình trạng này " + h + " mới gặp gần đây hay đã kéo dài lâu rồi ạ?";
                yield List.of(new ReplyBubble(reconfirmText, 0));
            }
            case DIRECT_CONCRETE_SOLUTION -> {
                String careActor = cfg.resolvedCareActor();
                List<String> confirmed = !enriched.confirmedScopes().isEmpty()
                        ? new ArrayList<>(enriched.confirmedScopes())
                        : new ArrayList<>(enriched.knownBodyAreas());
                String scopePhrase = !confirmed.isEmpty()
                        ? String.join(" và ", confirmed)
                        : "phần " + h + " đang cần chăm sóc";
                CatalogOption matched = selectBestSingleCatalogOption(userMessage, enriched, fusedChunks);
                String pkgText = matched != null
                        ? matched.name() + " " + matched.unitLabel()
                          + (priorState.priceRequestedInSession() && !priorState.priceDiscussed() && matched.priceDisplay() != null
                              ? " (" + matched.priceDisplay() + ")" : "")
                        : "liệu trình phù hợp";
                String solutionText = !confirmed.isEmpty()
                        ? "Dạ với tình trạng ở vùng " + scopePhrase + " của " + h + ", bên " + selfP + " có " + pkgText
                          + "; khi " + h + " ghé, " + selfP + " sẽ dặn " + careActor
                          + " ưu tiên chăm sóc thật kỹ vùng " + scopePhrase + " để " + h + " thấy dễ chịu nhất nha."
                        : "Dạ bên " + selfP + " sẽ ưu tiên sắp xếp " + pkgText
                          + " để chăm sóc thật chu đáo đúng phần " + h + " đang cần nha.";
                yield List.of(new ReplyBubble(solutionText, 0));
            }
            case CLARIFY_SERVICE_DURATION -> {
                if ("CLARIFY_DURATION_ONCE".equals(intent) || enriched.serviceFamilyKnown()) {
                    String famLabel = resolveServiceFamilyDisplay(enriched, fusedChunks);
                    String unitPair = extractTwoPrimaryUnitLabels(fusedChunks, famLabel, null);
                    String clarifyDur = "Dạ với " + famLabel + ", " + h
                            + " muốn xem gói " + unitPair.replace(" hoặc ", " hay ")
                            + " để " + selfP + " báo giá chuẩn cho " + h + " ạ?";
                    yield List.of(new ReplyBubble(clarifyDur, decision.prependsConfiguredOpening() ? 2500 : 0));
                }
                String categories = extractCatalogCategorySummary(fusedChunks);
                String clarifyText = "Dạ " + h + " đang quan tâm " + categories
                        + " để " + selfP + " gửi đúng mức giá theo thời lượng cho " + h + " nha?";
                yield List.of(new ReplyBubble(clarifyText, decision.prependsConfiguredOpening() ? 2500 : 0));
            }
            case ACKNOWLEDGE_AND_ASK_PREFERENCE -> {
                String problemPhrase = ConversationState.extractProblemOrNeedPhrase(userMessage, cfg.safeDomainKeywords());
                if (problemPhrase == null || problemPhrase.isBlank()) {
                    problemPhrase = enriched.known().hasCustomerProblemOrNeed()
                            ? enriched.known().customerProblemOrNeed()
                            : "cần tư vấn gói phù hợp";
                }
                if ("PUSHBACK_ON_ASSUMPTION_REPAIR".equals(intent)) {
                    String b1 = "Dạ " + selfP + " xin lỗi vì vừa diễn đạt chưa chuẩn làm " + h + " hiểu nhầm ạ. "
                            + "Ý " + selfP + " là muốn hỏi kỹ hơn về tình trạng " + problemPhrase + " của " + h + " để chọn đúng gói phù hợp ạ.";
                    String b2 = "Dạ tình trạng " + problemPhrase + " này " + h
                            + " mới gặp gần đây hay đã kéo dài để " + selfP + " báo gói và chi phí chuẩn cho " + h + " ạ?";
                    yield List.of(
                            new ReplyBubble(b1, 0),
                            new ReplyBubble(b2, 2000)
                    );
                }
                String condQuestion = cfg.resolvedConditionDiscoveryPrompt(h);
                String ackSentence = "cần tư vấn gói phù hợp".equals(problemPhrase) && !enriched.knownBodyAreas().isEmpty()
                        ? "Dạ với vùng " + String.join(" ", enriched.knownBodyAreas()) + " mà " + h + " đang quan tâm ạ."
                        : "Dạ tình trạng " + problemPhrase + " chắc đang làm " + h + " khá khó chịu ạ.";
                if (decision.prependsConfiguredOpening()) {
                    String singleBubble = ackSentence + " " + condQuestion;
                    yield List.of(new ReplyBubble(singleBubble, 2500));
                } else {
                    yield List.of(
                            new ReplyBubble(ackSentence, 0),
                            new ReplyBubble(condQuestion, 2000)
                    );
                }
            }
            case RECOMMEND_ONE_PACKAGE -> {
                if ("ALTERNATIVE_PACKAGE_REQUEST".equals(intent)) {
                    List<CatalogOption> altOpts = selectAlternativeCatalogOptions(priorState, fusedChunks, userMessage);
                    if (!altOpts.isEmpty()) {
                        if (altOpts.size() >= 2) {
                            CatalogOption o1 = altOpts.get(0);
                            CatalogOption o2 = altOpts.get(1);
                            String choiceLabel = o1.unitLabel().equals(o2.unitLabel())
                                    ? (o1.name() + " hay " + o2.name())
                                    : (o1.unitLabel() + " hay " + o2.unitLabel());
                            String b1 = "Dạ ngoài gói trên, bên " + selfP + " còn có gói "
                                    + o1.name() + " " + o1.unitLabel() + " và gói " + o2.name() + " " + o2.unitLabel() + " ạ.";
                            String b2 = capH + " muốn tìm hiểu thêm gói " + choiceLabel + " để " + selfP + " báo chi phí chi tiết ạ?";
                            yield List.of(new ReplyBubble(b1, 0), new ReplyBubble(b2, 2000));
                        } else {
                            CatalogOption o1 = altOpts.get(0);
                            String b1 = "Dạ ngoài gói trên, bên " + selfP + " còn có gói "
                                    + o1.name() + " " + o1.unitLabel() + " ạ.";
                            String b2 = capH + " muốn trải nghiệm gói " + o1.unitLabel() + " này hay cần " + selfP + " báo chi phí chi tiết ạ?";
                            yield List.of(new ReplyBubble(b1, 0), new ReplyBubble(b2, 2000));
                        }
                    }
                }
                CatalogOption best = selectBestSingleCatalogOption(userMessage, enriched, fusedChunks);
                if (best == null) {
                    String handoffText = "Dạ với nhu cầu hiện tại của " + h + ", " + selfP
                            + " xin phép chuyển chuyên viên kiểm tra và tư vấn gói phù hợp nhất cho " + h + " ngay nhé.";
                    yield List.of(new ReplyBubble(handoffText, 0));
                }
                String problemPhrase = enriched.known().hasCustomerProblemOrNeed()
                        ? enriched.known().customerProblemOrNeed()
                        : "nhu cầu của " + h;
                if ("MID_FUNNEL_LUKEWARM_REENGAGE".equals(intent)) {
                    // v3.3.4: 1 bubble, giữ gói đang thảo luận, khơi 1 chi tiết cảm xúc đã xác minh + 1 câu hỏi mở. KHÔNG lặp giá.
                    String heldRaw = priorState.heldPackage() != null ? priorState.heldPackage() : priorState.lastQuotedPackage();
                    CatalogOption heldOpt = heldRaw != null
                            ? matchSpecificCatalogOption(ConversationState.toDisplayPackageName(heldRaw, priorState.heldDuration()), enriched, fusedChunks)
                            : null;
                    String anchor = buildNaturalGroundedEmotionalAnchorSentence(heldOpt != null ? heldOpt : best, enriched, cfg, h, selfP);
                    String lukewarmText = "Dạ " + h + " cứ thong thả cân nhắc nha. " + anchor
                            + " Không biết " + h + " còn băn khoăn điểm nào để " + selfP + " chia sẻ thêm không ạ?";
                    yield List.of(new ReplyBubble(lukewarmText, 0));
                }
                if ("META_APOLOGY_POSITIVE_INTEREST_ADVANCE".equals(intent)) {
                    String pkgLabel = ("gói " + best.name() + " " + best.unitLabel()).replaceAll("(?iU)^gói\\s+gói\\s+", "gói ");
                    String priceHint = (!priorState.priceDiscussed() && best.priceDisplay() != null && !best.priceDisplay().isBlank())
                            ? " (giá " + best.priceDisplay() + ")"
                            : "";
                    String b1 = "Dạ tại " + selfP + " quen miệng nói năng cẩn thận quá đó " + h + " ơi, "
                            + selfP + " rút kinh nghiệm liền nha. "
                            + capH + " thấy " + pkgLabel + priceHint + " hợp ý mình rồi hay muốn xem thêm gói thời lượng dài hơn ạ?";
                    yield List.of(new ReplyBubble(b1, 0));
                }
                if ("CONFIRM_PREVIOUS_INFO_ADVANCE".equals(intent)) {
                    String pkgLabel = ("gói " + best.name() + " " + best.unitLabel()).replaceAll("(?iU)^gói\\s+gói\\s+", "gói ");
                    String benefitClause = (best.benefitSummary() != null && !best.benefitSummary().isBlank())
                            ? " (" + best.benefitSummary() + ")"
                            : "";
                    String b1 = "Dạ vâng " + h + ", với " + pkgLabel + benefitClause
                            + " sẽ giúp làm ấm người và thả lỏng cơ rất dễ chịu ạ. "
                            + capH + " đang muốn thư giãn nhẹ nhàng với gói " + best.unitLabel() + " này hay muốn kèm thêm xông hơi phòng riêng ạ?";
                    yield List.of(new ReplyBubble(b1, 0));
                }
                if ("CONDITION_FOLLOWUP_AFTER_QUOTE_ADVANCE".equals(intent)) {
                    String pkgLabel = ("gói " + best.name() + " " + best.unitLabel()).replaceAll("(?iU)^gói\\s+gói\\s+", "gói ");
                    String benefitClause = (best.benefitSummary() != null && !best.benefitSummary().isBlank())
                            ? " (" + best.benefitSummary() + ")"
                            : "";
                    String b1 = "Dạ nếu tình trạng " + problemPhrase + " thi thoảng mới xuất hiện thì " + h + " chăm sóc bằng "
                            + pkgLabel + benefitClause + " là rất vừa vặn để thả lỏng vùng đang mỏi ạ. "
                            + capH + " muốn tìm hiểu thêm về các bước trong gói này hay khung giờ trống để ghé trải nghiệm ạ?";
                    yield List.of(new ReplyBubble(b1, 0));
                }
                if ("MISMATCH_CORRECTION_RESCUE_RECOMMEND".equals(intent)) {
                    String b1 = "Dạ " + selfP + " xin lỗi " + h + " vì sự nhầm lẫn vừa rồi ạ. Với tình trạng " + problemPhrase
                            + " của " + h + ", bên " + selfP + " phù hợp nhất là gói " + best.name() + " " + best.unitLabel() + " ạ.";
                    String b2 = capH + " muốn xem chi phí gói " + best.unitLabel() + " này hay giữ lịch trải nghiệm ạ?";
                    yield List.of(new ReplyBubble(b1, 0), new ReplyBubble(b2, 2000));
                }
                if ("SET_SERVICE_FAMILY_AND_ADVANCE".equals(intent)) {
                    String b1 = "Dạ với nhu cầu của " + h + ", bên " + selfP + " gợi ý gói "
                            + best.name() + " " + best.unitLabel() + " tập trung chăm sóc đúng vùng đang cần ạ.";
                    String b2 = capH + " muốn xem chi phí gói " + best.unitLabel() + " này hay giữ khung giờ ghé trải nghiệm ạ?";
                    yield List.of(new ReplyBubble(b1, 0), new ReplyBubble(b2, 2000));
                }
                if (decision.prependsConfiguredOpening()) {
                    String b3 = "Dạ với tình trạng " + problemPhrase + ", " + selfP + " gợi ý " + h + " thử "
                            + best.name() + " " + best.unitLabel() + " ạ. "
                            + capH + " muốn trải nghiệm gói này hay cần " + selfP + " báo chi phí chi tiết ạ?";
                    yield List.of(new ReplyBubble(b3, 2500));
                } else {
                    String focusClause = (best.benefitSummary() != null && !best.benefitSummary().isBlank())
                            ? best.benefitSummary()
                            : (cfg.isNonSpaIndustry() ? "phù hợp với tình trạng hiện tại" : "tập trung chăm sóc đúng vùng đang mỏi");
                    String b1 = "Dạ với tình trạng " + problemPhrase + ", " + selfP + " gợi ý " + h + " thử gói "
                            + best.name() + " " + best.unitLabel() + " " + focusClause + " ạ.";
                    String b2 = capH + " muốn trải nghiệm gói " + best.unitLabel() + " này hay cần " + selfP + " báo chi phí chi tiết ạ?";
                    yield List.of(new ReplyBubble(b1, 0), new ReplyBubble(b2, 2000));
                }
            }
            case ANSWER_PRICE_ONCE -> {
                if ("ALTERNATIVE_PACKAGE_REQUEST".equals(intent)) {
                    List<CatalogOption> altOpts = selectAlternativeCatalogOptions(priorState, fusedChunks, userMessage);
                    if (!altOpts.isEmpty()) {
                        if (altOpts.size() >= 2) {
                            CatalogOption o1 = altOpts.get(0);
                            CatalogOption o2 = altOpts.get(1);
                            String choiceLabel = o1.unitLabel().equals(o2.unitLabel())
                                    ? ("gói " + o1.priceDisplay() + " hay " + o2.priceDisplay())
                                    : ("gói " + o1.unitLabel() + " hay " + o2.unitLabel());
                            String b1 = "Dạ ngoài gói trên, bên " + selfP + " còn có gói "
                                    + o1.name() + " " + o1.unitLabel() + " giá " + o1.priceDisplay()
                                    + " và gói " + o2.name() + " " + o2.unitLabel() + " giá " + o2.priceDisplay() + " ạ.";
                            String b2 = "Cả hai gói đều tập trung chăm sóc kỹ vùng " + h + " đang cần ạ. "
                                    + "Không biết " + h + " nghiêng về " + choiceLabel + " hơn để " + selfP + " tư vấn kỹ thêm cho " + h + " ạ?";
                            yield List.of(new ReplyBubble(b1, 0), new ReplyBubble(b2, postPriceDelayMs));
                        } else {
                            CatalogOption o1 = altOpts.get(0);
                            String b1 = "Dạ ngoài gói trên, bên " + selfP + " còn có gói "
                                    + o1.name() + " " + o1.unitLabel() + " có giá " + o1.priceDisplay() + " ạ.";
                            String b2 = "Gói này tập trung chăm sóc kỹ vùng " + h + " đang cần ạ. "
                                    + "Không biết " + h + " thấy gói " + o1.unitLabel() + " này phù hợp hơn chưa ạ?";
                            yield List.of(new ReplyBubble(b1, 0), new ReplyBubble(b2, postPriceDelayMs));
                        }
                    }
                }
                CatalogOption matched = matchSpecificCatalogOption(userMessage, enriched, fusedChunks);
                if (matched == null) {
                    matched = selectBestSingleCatalogOption(userMessage, enriched, fusedChunks);
                }
                if (matched == null) {
                    String handoffText = "Dạ về mức chi phí cho nhu cầu này của " + h + ", " + selfP
                            + " xin phép chuyển chuyên viên kiểm tra và báo chính xác ngay cho " + h + " nhé.";
                    yield List.of(new ReplyBubble(handoffText, 0));
                }
                String naturalAnchorSentence = buildNaturalGroundedEmotionalAnchorSentence(matched, enriched, cfg, h, selfP);
                if ("META_APOLOGY_POSITIVE_INTEREST_QUOTE".equals(intent)) {
                    String b1 = "Dạ tại " + selfP + " quen miệng cẩn thận quá đó " + h + " ơi, " + selfP
                            + " báo giá liền cho " + h + " nha: gói " + matched.name() + " " + matched.unitLabel()
                            + " hiện có giá " + matched.priceDisplay() + " ạ.";
                    String b2 = naturalAnchorSentence
                            + " Không biết với gói " + matched.unitLabel() + " này " + h
                            + " thấy phù hợp với nhu cầu hiện tại hay muốn xem thêm gói dài hơn ạ?";
                    yield List.of(new ReplyBubble(b1, 0), new ReplyBubble(b2, postPriceDelayMs));
                }
                if ("CONFIRM_PREVIOUS_INFO_QUOTE".equals(intent)) {
                    String b1 = "Dạ vâng " + h + ", vậy " + selfP + " gửi chi tiết gói " + matched.name() + " " + matched.unitLabel()
                            + " bên " + selfP + " hiện có mức chi phí là " + matched.priceDisplay() + " ạ.";
                    String b2 = naturalAnchorSentence
                            + " Với gói " + matched.unitLabel() + " này, " + h
                            + " muốn tìm hiểu thêm về quy trình hay muốn kèm thêm xông hơi phòng riêng ạ?";
                    yield List.of(new ReplyBubble(b1, 0), new ReplyBubble(b2, postPriceDelayMs));
                }
                if ("MISMATCH_CORRECTION_RESCUE_QUOTE".equals(intent)) {
                    String symPhrase = enriched.known().hasCustomerProblemOrNeed()
                            ? enriched.known().customerProblemOrNeed()
                            : "nhu cầu";
                    String b1 = "Dạ " + selfP + " xin lỗi " + h + " vì sự nhầm lẫn vừa rồi ạ. Với tình trạng " + symPhrase
                            + " của " + h + ", " + selfP + " gợi ý gói " + matched.name() + " " + matched.unitLabel()
                            + " (chi phí " + matched.priceDisplay() + ") ạ.";
                    String b2 = naturalAnchorSentence
                            + " Với gói " + matched.unitLabel() + " này, " + h
                            + " thấy đã hợp nhu cầu của mình hay muốn hỏi thêm quyền lợi nào trong gói ạ?";
                    yield List.of(new ReplyBubble(b1, 0), new ReplyBubble(b2, postPriceDelayMs));
                }
                if ("FRUSTRATION_RECOVERY_QUOTE_PENDING_PRICE".equals(intent)) {
                    String symPhrase = enriched.known().hasCustomerProblemOrNeed()
                            ? enriched.known().customerProblemOrNeed()
                            : "nhu cầu";
                    String b1 = "Dạ " + selfP + " xin lỗi " + h + " vì vừa hỏi hơi vòng vo ạ. Với tình trạng "
                            + symPhrase + " của " + h + ", " + selfP + " gợi ý gói " + matched.name() + " "
                            + matched.unitLabel() + " (chi phí " + matched.priceDisplay() + ") ạ.";
                    String b2 = naturalAnchorSentence
                            + " Với gói " + matched.unitLabel() + " này, " + h
                            + " thấy đã hợp nhu cầu của mình hay muốn hỏi thêm quyền lợi nào trong gói ạ?";
                    yield List.of(new ReplyBubble(b1, 0), new ReplyBubble(b2, postPriceDelayMs));
                }
                if ("RECOMMEND_AND_QUOTE_IF_PRICE_REQUESTED".equals(intent)) {
                    String symPhrase = enriched.known().hasCustomerProblemOrNeed()
                            ? enriched.known().customerProblemOrNeed()
                            : "nhu cầu";
                    String freqDesc = (lowerMsg.contains("thi thoảng") || lowerMsg.contains("thỉnh thoảng"))
                            ? " thi thoảng xuất hiện"
                            : "";
                    String b1 = "Dạ khi cần chăm sóc " + symPhrase + freqDesc + " kịp lúc cho " + h + ", bên " + selfP
                            + " có gói " + matched.name() + " " + matched.unitLabel() + " (chi phí "
                            + matched.priceDisplay() + ") rất phù hợp ạ.";
                    String b2 = naturalAnchorSentence
                            + " Với gói " + matched.unitLabel() + " này, " + h
                            + " thấy đã hợp ý mình hay muốn " + selfP + " chia sẻ thêm về quyền lợi đi kèm ạ?";
                    yield List.of(new ReplyBubble(b1, 0), new ReplyBubble(b2, postPriceDelayMs));
                }
                String b1 = "Dạ gói " + matched.name() + " " + matched.unitLabel() + " bên " + selfP
                        + " hiện có chi phí là " + matched.priceDisplay() + " ạ.";
                String b2 = naturalAnchorSentence
                        + " Với gói " + matched.unitLabel() + " này, " + h
                        + " thấy đã hợp ý mình hay muốn " + selfP + " chia sẻ thêm về quyền lợi đi kèm ạ?";
                yield List.of(new ReplyBubble(b1, 0), new ReplyBubble(b2, postPriceDelayMs));
            }
            case ADVANCE_AFTER_ACK -> {
                boolean hasMultipleOptions = priorState.recommendedOptions() != null && priorState.recommendedOptions().size() >= 2;
                String ackReply;
                if (hasMultipleOptions && !priorState.priceDiscussed()) {
                    List<String> opts = priorState.recommendedOptions();
                    ackReply = "Dạ " + h + " đang nghiêng về gói " + opts.get(0) + " hay " + opts.get(1)
                            + " hơn để " + selfP + " tư vấn kỹ thêm cho " + h + " nhé ạ?";
                } else if (priorState.priceDiscussed()
                        || (priorState.knownRecommendedPackage() != null && !priorState.knownRecommendedPackage().isBlank())
                        || enriched.serviceFamilyKnown()) {
                    String optLabel = (priorState.knownRecommendedPackage() != null && !priorState.knownRecommendedPackage().isBlank())
                            ? "gói " + priorState.knownRecommendedPackage()
                            : ((priorState.heldPackage() != null && !priorState.heldPackage().isBlank())
                                ? "gói " + ConversationState.toDisplayPackageName(priorState.heldPackage(), priorState.heldDuration())
                                : "gói " + resolveServiceFamilyDisplay(enriched, fusedChunks));
                    optLabel = optLabel.replaceAll("(?iU)^gói\\s+gói\\s+", "gói ");
                    ackReply = "Dạ vâng " + h + ", vậy " + selfP + " chuẩn bị " + optLabel + " cho " + h
                            + " nhé. " + capH + " tiện ghé bên " + selfP + " vào khung giờ nào hôm nay hay ngày mai để " + selfP + " giữ lịch trước cho " + h + " ạ?";
                } else {
                    String unitPair = extractTwoPrimaryUnitLabels(fusedChunks, null, null);
                    ackReply = "Dạ " + h + " đang nghiêng về gói " + unitPair.replace(" hoặc ", " hay ")
                            + " hơn để " + selfP + " tư vấn chi tiết cho " + h + " nhé ạ?";
                }
                yield List.of(new ReplyBubble(ackReply, 0));
            }
            case EXPLAIN_UNIT_VALUE -> {
                String unitPair = extractTwoPrimaryUnitLabels(fusedChunks, null, null);
                String[] parts = unitPair.split("\\s+(?:hoặc|hay)\\s+");
                String u1 = parts.length >= 1 ? parts[0] : "ngắn hơn";
                String u2 = parts.length >= 2 ? parts[1] : "dài hơn";
                String b1 = "Dạ ý " + h + " là tính theo đơn vị thời gian thì gói " + u1 + " có đơn giá nhỉnh hơn gói " + u2 + " một chút ạ.";
                String b2 = "Nếu " + h + " muốn làm nhanh gọn thì gói " + u1 + " vẫn hợp; còn muốn thư giãn lâu và tiết kiệm hơn trên mỗi phút thì gói " + u2 + " đáng chọn hơn. "
                        + capH + " ưu tiên làm nhanh hay thư giãn dài hơn ạ?";
                if (decision.prependsConfiguredOpening()) {
                    yield List.of(new ReplyBubble(b1 + " " + capH + " ưu tiên làm nhanh hay thư giãn dài hơn ạ?", 2500));
                }
                yield List.of(new ReplyBubble(b1, 0), new ReplyBubble(b2, 2000));
            }
            case HANDLE_PRICE_OBJECTION_FIRST_TIME -> {
                // Sub-branch A: Explicit downsell request on 1st objection ("Có gói nào ngắn hơn rẻ hơn không?")
                if ("EXPLICIT_DOWNSELL_WITH_PACKAGE".equals(intent)) {
                    CatalogOption lowerOpt = selectLowerCatalogOption(priorState, fusedChunks, userMessage);
                    if (lowerOpt != null) {
                        String b1PkgLabel = formatDownsellPackageLabel(lowerOpt, priorState);
                        String b2PkgLabel = "gói " + lowerOpt.unitLabel();
                        String focusDesc = (lowerOpt.benefitSummary() != null && !lowerOpt.benefitSummary().isBlank())
                                ? lowerOpt.benefitSummary()
                                : (cfg.isNonSpaIndustry() ? "phù hợp nhu cầu cơ bản" : "tập trung đúng vùng đang mỏi");
                        String b1 = "Dạ " + selfP + " hiểu " + h + " muốn gọn chi phí cho lần đầu. Bên " + selfP
                                + " có " + b1PkgLabel + " có giá " + lowerOpt.priceDisplay()
                                + " " + focusDesc + " ạ.";
                        String b2 = (cfg.isNonSpaIndustry()
                                ? "Gói này giúp " + h + " trải nghiệm bước cơ bản trước mà vẫn đảm bảo hiệu quả nhé. "
                                : "Gói này giúp " + h + " làm quen kỹ thuật trước mà vẫn thư giãn vùng mỏi nhé. ")
                                + "Không biết " + h + " thấy " + b2PkgLabel + " này phù hợp hơn chưa ạ?";
                        yield List.of(new ReplyBubble(b1, 0), new ReplyBubble(b2, postPriceDelayMs));
                    }
                }
                // Sub-branch B: Normal 1st price objection ("đắt thế", "hơi cao", "Đắt") -> Value Defense + Invite 1-session trial 🥰
                String heldPkgRaw = priorState.heldPackage() != null
                        ? priorState.heldPackage()
                        : (priorState.lastQuotedPackage() != null
                            ? priorState.lastQuotedPackage()
                            : priorState.knownRecommendedPackage());
                String heldPkgDisplay = ConversationState.toDisplayPackageName(heldPkgRaw, priorState.heldDuration());
                CatalogOption heldOpt = matchSpecificCatalogOption(heldPkgDisplay, enriched, fusedChunks);
                boolean isNonSpaTenant = cfg.isNonSpaIndustry();
                String b1;
                if (isNonSpaTenant) {
                    String summary = (heldOpt != null && heldOpt.benefitSummary() != null && !heldOpt.benefitSummary().isBlank())
                            ? heldOpt.benefitSummary()
                            : "được thực hiện bài bản đúng tình trạng của " + h;
                    b1 = "Dạ " + selfP + " hiểu " + h + " đang cân nhắc chi phí ạ. Dịch vụ bên " + selfP + " "
                            + summary + ", quy trình chuẩn chỉnh và tận tâm ạ.";
                } else if (enriched.docsHasFeedback()) {
                    b1 = "Dạ " + selfP + " hiểu " + h + " đang cân nhắc chi phí ạ. Bên " + selfP
                            + " kỹ thuật viên làm kỹ đúng vùng mỏi, nhiều anh/chị sau buổi đầu trải nghiệm có phản hồi khá tốt ạ.";
                } else if (heldOpt != null && heldOpt.benefitSummary() != null && heldOpt.benefitSummary().startsWith("gồm ")) {
                    b1 = "Dạ " + selfP + " hiểu " + h + " đang cân nhắc chi phí ạ. Gói bên " + selfP
                            + " " + heldOpt.benefitSummary() + ", kỹ thuật viên làm kỹ đúng vùng mỏi ạ.";
                } else {
                    b1 = "Dạ " + selfP + " hiểu " + h + " đang cân nhắc chi phí ạ. Bên " + selfP
                            + " kỹ thuật viên làm kỹ đúng vùng mỏi, không gian yên tĩnh và sạch sẽ ạ.";
                }
                String b2 = "Theo " + selfP + ", " + h
                        + " có thể trải nghiệm thử 1 buổi " + heldPkgDisplay + " trước để cảm nhận trực tiếp cách bên " + selfP + " chăm sóc nha 🥰";
                if (decision.prependsConfiguredOpening()) {
                    yield List.of(new ReplyBubble(b1 + " " + b2, 2500));
                }
                // VR-014A: No numeric price in B1 -> delay 2000ms on B2!
                yield List.of(new ReplyBubble(b1, 0), new ReplyBubble(b2, 2000));
            }
            case HANDLE_PRICE_OBJECTION_REPEAT -> {
                // R12-SECOND-PRICE-OBJECTION-CORRECTED
                CatalogOption lowerOpt = selectLowerCatalogOption(priorState, fusedChunks, userMessage);
                if (lowerOpt != null) {
                    String b1PkgLabel = formatDownsellPackageLabel(lowerOpt, priorState);
                    String focusDesc = (lowerOpt.benefitSummary() != null && !lowerOpt.benefitSummary().isBlank())
                            ? lowerOpt.benefitSummary()
                            : (cfg.isNonSpaIndustry() ? "phù hợp nhu cầu cơ bản" : "tập trung đúng vùng đang mỏi");
                    if (isPriceObjectionMessage(userMessage)) {
                        String cleanFocus = focusDesc
                                .replaceAll("\\s*\\+\\s*", ", ")
                                .replaceAll("\\s*&\\s*", " và ");
                        String b1 = "Dạ nếu " + h + " muốn bắt đầu nhẹ nhàng để gọn chi phí cho buổi đầu, bên " + selfP
                                + " có " + b1PkgLabel + " có giá " + lowerOpt.priceDisplay() + " ạ.";
                        String b2 = "Gói " + lowerOpt.unitLabel() + " này " + cleanFocus
                                + ", vẫn chăm sóc dịu vùng " + h + " đang mỏi mà tiết kiệm hơn nhiều ạ. Không biết " + h
                                + " thấy lựa chọn này vừa vặn hơn chưa ạ?";
                        yield List.of(new ReplyBubble(b1, 0), new ReplyBubble(b2, postPriceDelayMs));
                    }
                    String procLabel = cfg.isNonSpaIndustry() ? "quy trình dịch vụ" : "liệu trình";
                    String b1 = "Dạ " + h + " cứ thoải mái cân nhắc ạ. Không biết " + h
                            + " đang lăn tăn chủ yếu về chi phí cho buổi đầu hay còn điểm nào trong " + procLabel
                            + " chưa yên tâm để " + selfP + " giải đáp đúng điều " + h + " cần ạ?";
                    String b2 = "Nếu " + h + " muốn bắt đầu với mức chi phí nhẹ hơn, " + selfP
                            + " có thể giới thiệu " + b1PkgLabel + " có giá " + lowerOpt.priceDisplay()
                            + " " + focusDesc + " ạ.";
                    yield List.of(new ReplyBubble(b1, 0), new ReplyBubble(b2, postPriceDelayMs));
                }
                String escText = "Dạ hiện bên " + selfP + " không có gói tiết kiệm hơn mức này. "
                        + capSelfP + " chuyển chuyên viên xem có phương án phù hợp cho " + h + " nhé.";
                yield List.of(new ReplyBubble(escText, 0));
            }
            case CONFIRM_BOOKING_DETAILS -> {
                // R13B-TRIAL-ACCEPTANCE-BOOKING-CORRECTED
                if ("TRIAL_ACCEPTANCE".equals(intent)
                        || "spa_trial_acceptance_booking".equalsIgnoreCase(decision.correspondingMicroSkillCode())) {
                    String offeredName = priorState.lastOfferedPackageName();
                    String offeredPrice = priorState.lastOfferedPrice();
                    if (priorState.downsellOffered()) {
                        String dsDur = priorState.downsellDuration() != null
                                ? priorState.downsellDuration()
                                : (priorState.heldDuration() != null ? priorState.heldDuration() : "30");
                        String pkgLabel = (offeredName != null && !offeredName.isBlank())
                                ? (offeredName.toLowerCase(Locale.ROOT).contains(dsDur) ? offeredName : (offeredName + " " + dsDur + " phút"))
                                : ("gói " + dsDur + " phút");
                        if (!pkgLabel.toLowerCase(Locale.ROOT).startsWith("gói")) {
                            pkgLabel = "gói " + pkgLabel;
                        }
                        String priceSuffix = (offeredPrice != null && !offeredPrice.isBlank())
                                ? " (" + offeredPrice + ")"
                                : "";
                        String focusText = cfg.isNonSpaIndustry() ? "phù hợp nhu cầu cơ bản" : "tập trung đúng vùng đang mỏi";
                        String b1 = "Dạ vâng, vậy " + h + " trải nghiệm thử 1 buổi " + pkgLabel + priceSuffix
                                + " " + focusText + " để cảm nhận tay nghề bên " + selfP + " nhé 🥰";
                        String b2 = capH + " muốn ghé cơ sở nào và khung giờ nào để "
                                + selfP + " giữ lịch trước cho " + h + " ạ?";
                        yield List.of(new ReplyBubble(b1, 0), new ReplyBubble(b2, 2000));
                    } else {
                        String heldPkgRaw = priorState.heldPackage() != null
                                ? priorState.heldPackage()
                                : (priorState.lastQuotedPackage() != null
                                    ? priorState.lastQuotedPackage()
                                    : priorState.knownRecommendedPackage());
                        String heldPkgDisplay = (offeredName != null && !offeredName.isBlank())
                                ? offeredName
                                : ConversationState.toDisplayPackageName(heldPkgRaw, priorState.heldDuration());
                        String priceSuffix = (offeredPrice != null && !offeredPrice.isBlank()
                                && !heldPkgDisplay.toLowerCase(Locale.ROOT).contains(offeredPrice.toLowerCase(Locale.ROOT)))
                                ? " (" + offeredPrice + ")"
                                : "";
                        String b1 = "Dạ vâng, vậy " + h + " trải nghiệm thử 1 buổi " + heldPkgDisplay + priceSuffix
                                + " để cảm nhận tay nghề bên " + selfP + " nhé 🥰";
                        String b2 = capH + " muốn ghé cơ sở nào và khung giờ nào để "
                                + selfP + " giữ lịch trước cho " + h + " ạ?";
                        yield List.of(new ReplyBubble(b1, 0), new ReplyBubble(b2, 2000));
                    }
                }
                boolean asksAddon = lowerMsg.contains("thêm") || lowerMsg.contains("ngâm chân") || lowerMsg.contains("kết hợp");
                boolean selectsPackage = lowerMsg.matches("(?isU).*\\b(thử\\s+gói|chốt\\s+gói|lấy\\s+gói|chọn\\s+gói)\\b.*");
                if (asksAddon || selectsPackage) {
                    CatalogOption addonOpt = findAddonCatalogOption(fusedChunks);
                    String addonDesc = addonOpt != null
                            ? (addonOpt.name() + " " + addonOpt.unitLabel())
                            : "gói chăm sóc bổ trợ ngắn";
                    String b1 = "Dạ " + selfP + " ghi nhận gói dịch vụ cho " + h + " nha. Nhiều khách thường kết hợp thêm "
                            + addonDesc + " cho ấm người và dễ ngủ hơn ạ.";
                    String b2 = capH + " muốn giữ nguyên gói chính hay kết hợp thêm để " + selfP + " chuẩn bị lịch cho " + h + " ạ?";
                    yield List.of(new ReplyBubble(b1, 1000), new ReplyBubble(b2, 2000));
                }
                if ("BOOKING_AFTER_SERVICE_SELECTED".equals(intent)) {
                    String pkgName = (priorState.knownRecommendedPackage() != null && !priorState.knownRecommendedPackage().isBlank())
                            ? priorState.knownRecommendedPackage()
                            : resolveServiceFamilyDisplay(enriched, fusedChunks);
                    String b1 = "Dạ " + selfP + " ghi nhận " + h + " chọn " + pkgName + " ạ.";
                    String b2 = capH + " dự kiến ghé khung giờ nào hôm nay hay ngày mai để " + selfP + " giữ lịch phù hợp cho " + h + " nhé?";
                    yield List.of(new ReplyBubble(b1, 1000), new ReplyBubble(b2, 2000));
                }
                String b1 = "Dạ để " + selfP + " kiểm tra lịch trống cho " + h + ", " + h
                        + " đang muốn đặt gói dịch vụ nào và dự kiến ghé khoảng mấy giờ để " + selfP + " giữ khung giờ phù hợp nha?";
                yield List.of(new ReplyBubble(b1, decision.prependsConfiguredOpening() ? 1200 : 0));
            }
            case GENERIC_SHORT_DISCOVERY -> {
                if ("STRONG_REJECTION".equals(intent)) {
                    String politeClose = "Dạ " + selfP + " xin lỗi nếu trải nghiệm tư vấn vừa rồi chưa làm " + h
                            + " thoải mái ạ. Khi nào " + h + " cần hỗ trợ thêm, " + h + " cứ nhắn cho " + selfP
                            + " bất cứ lúc nào nhé. Chúc " + h + " nhiều sức khỏe ạ!";
                    yield List.of(new ReplyBubble(politeClose, 0));
                }
                if ("POLITE_DEFERRAL_EXIT".equals(intent) || "STAGE_3_GRACEFUL_EXIT".equals(intent) || "EXPLICIT_OPT_OUT".equals(intent)) {
                    String areaRef = !enriched.knownBodyAreas().isEmpty()
                            ? "vùng " + String.join(", ", enriched.knownBodyAreas())
                            : "cơ thể";
                    String politeClose = "Dạ vâng ạ, " + h + " cứ thoải mái cân nhắc thêm nha. Khi nào " + areaRef + " thấy mỏi hay " + h
                            + " muốn ghé thư giãn thì cứ nhắn " + selfP + " bất cứ lúc nào nhé ạ 🌿";
                    yield List.of(new ReplyBubble(politeClose, 0));
                }
                if ("ANTI_APOLOGY_PUSHBACK".equals(intent)) {
                    String symptomRef = !enriched.knownSymptoms().isEmpty()
                            ? String.join(", ", enriched.knownSymptoms())
                            : (!enriched.knownBodyAreas().isEmpty() ? "mỏi vùng " + String.join(", ", enriched.knownBodyAreas()) : null);
                    String lightReply = symptomRef != null
                            ? "Dạ tại " + selfP + " quen miệng hay khách sáo quá đó ạ. Tình trạng " + symptomRef + " của " + h
                              + " mới xuất hiện gần đây hay đã kéo dài lâu rồi để " + selfP + " chọn liệu trình phù hợp nhất cho " + h + " ạ?"
                            : "Dạ tại " + selfP + " quen miệng hay khách sáo quá đó ạ, " + h
                              + " đừng để bụng nha. Hôm nay " + h + " đang muốn thư giãn vùng nào để " + selfP + " tư vấn ngay cho " + h + " ạ?";
                    yield List.of(new ReplyBubble(lightReply, 0));
                }
                if ("WORDING_FEEDBACK_PRESERVE_BOOKING_FUNNEL".equals(intent)) {
                    String preserveText = "Dạ cảm ơn " + h + " đã góp ý nha, " + selfP
                            + " rút kinh nghiệm nói chuyện tự nhiên hơn ạ. Hôm nay hay ngày mai " + h
                            + " tiện ghé vào tầm mấy giờ để " + selfP + " sắp xếp lịch đón " + h + " nhé?";
                    yield List.of(new ReplyBubble(preserveText, 0));
                }
                if ("WORDING_FEEDBACK_REPAIR".equals(intent)) {
                    String wittyRepair = "Dạ cảm ơn " + h + " đã góp ý thẳng thắn ạ, " + selfP
                            + " xin rút kinh nghiệm để nói chuyện ngắn gọn, tự nhiên và đi thẳng vào trọng tâm hơn với " + h + " nhé ạ.";
                    yield List.of(new ReplyBubble(wittyRepair, 0));
                }
                if ("META_COMMENT_ON_GREETING".equals(intent)) {
                    String categories = extractCatalogCategorySummary(fusedChunks);
                    String lightReply = "Dạ tại " + selfP + " hào hứng đón " + h + " ghé thăm quá nên chào hơi kỹ một chút ạ. "
                            + "Hôm nay " + h + " đang muốn " + categories + " hay chăm sóc vùng nào để " + selfP + " tư vấn ngay cho " + h + " nha?";
                    yield List.of(new ReplyBubble(lightReply, 0));
                }
                if ("FRUSTRATION_CONCERN_ACKNOWLEDGEMENT".equals(intent)) {
                    String activePkgLabel = (priorState.knownRecommendedPackage() != null && !priorState.knownRecommendedPackage().isBlank())
                            ? "gói " + priorState.knownRecommendedPackage()
                            : ((priorState.heldPackage() != null && !priorState.heldPackage().isBlank())
                                ? "gói " + ConversationState.toDisplayPackageName(priorState.heldPackage(), priorState.heldDuration())
                                : (enriched.serviceFamilyKnown()
                                    ? "gói " + resolveServiceFamilyDisplay(enriched, fusedChunks)
                                    : null));
                    if (activePkgLabel != null) {
                        activePkgLabel = activePkgLabel.replaceAll("(?iU)^gói\\s+gói\\s+", "gói ")
                                .replaceAll("(?iU)\\s*\\(\\d+[\\d.,]*\\s*(?:k|đ|vnđ)\\)", "")
                                .strip();
                        String areaNote = !enriched.knownBodyAreas().isEmpty()
                                ? "để chăm sóc vùng " + String.join(", ", enriched.knownBodyAreas()) + " đang mỏi"
                                : "phù hợp với nhu cầu thư giãn của " + h;
                        String concernText = "Dạ " + selfP + " xin lỗi " + h + " nhiều nha, vừa rồi " + selfP + " hơi lơ đễnh nên trả lời chưa khéo làm " + h + " mất vui ạ. "
                                + "Với " + activePkgLabel + " " + areaNote + ", " + h
                                + " muốn " + selfP + " chia sẻ kỹ hơn về quy trình chăm sóc không ạ?";
                        yield List.of(new ReplyBubble(concernText, 0));
                    }
                    String concernText = "Dạ " + selfP + " xin lỗi " + h + " nhiều nha vì vừa rồi phản hồi chưa khéo ạ. "
                            + capH + " đang muốn tìm hiểu chi tiết phần nào để " + selfP
                            + " chia sẻ lại thật rõ ràng cho " + h + " nhé?";
                    yield List.of(new ReplyBubble(concernText, 0));
                }
                if ("AMBIGUOUS_FOLLOWUP_ASSIST".equals(intent)) {
                    String followupText = "Dạ " + selfP + " xin lỗi vì chưa hiểu rõ ý " + h + " ạ. "
                            + capH + " đang muốn hỏi thêm chi tiết về quyền lợi trong gói, bảng giá hay cần nhân viên hỗ trợ trực tiếp ạ?";
                    yield List.of(new ReplyBubble(followupText, 0));
                }
                if ("PRICE_REPEAT_GUARD".equals(intent)) {
                    String pkgName = (priorState.knownRecommendedPackage() != null && !priorState.knownRecommendedPackage().isBlank())
                            ? priorState.knownRecommendedPackage()
                            : "gói " + resolveServiceFamilyDisplay(enriched, fusedChunks);
                    String priceNote = (priorState.lastOfferedPrice() != null && !priorState.lastOfferedPrice().isBlank())
                            ? " đang có mức giá trọn gói là " + priorState.lastOfferedPrice() + " (không nhận Tip)"
                            : "";
                    String text = "Dạ " + pkgName + " bên " + selfP + priceNote + " ạ. " + capH
                            + " muốn ghé trải nghiệm vào khung giờ nào để " + selfP + " chuẩn bị giường chu đáo cho " + h + " nhé?";
                    yield List.of(new ReplyBubble(text, 0));
                }
                if (SCHEDULE_LATER_PATTERN.matcher(lowerMsg).matches()) {
                    String b1 = "Dạ vâng, " + h + " cứ thong thả xem lại lịch cá nhân cho thoải mái nha ạ.";
                    String b2 = "Khi nào " + h + " sắp xếp được khung giờ ghé trải nghiệm, " + h
                            + " cứ nhắn trước để " + selfP + " giữ lịch cho " + h + " nhé ạ?";
                    yield List.of(new ReplyBubble(b1, 0), new ReplyBubble(b2, 1500));
                }
                if ("AMBIGUOUS_CONFIRM_ONCE".equals(intent) && enriched.serviceFamilyKnown()) {
                    String famLabel = resolveServiceFamilyDisplay(enriched, fusedChunks);
                    String heldPkgLabel = (priorState.heldPackage() != null && !priorState.heldPackage().isBlank())
                            ? ConversationState.toDisplayPackageName(priorState.heldPackage(), priorState.heldDuration())
                            : famLabel;
                    String confirmText = "Dạ " + selfP + " chưa nắm rõ hết ý " + h + " vừa nhắn ạ. "
                            + "Hiện " + selfP + " đang tư vấn " + heldPkgLabel + ", " + h
                            + " muốn tìm hiểu thêm về các bước trong gói hay khung giờ trống ạ?";
                    if (priorState.lastBotBubbleHashes() != null
                            && priorState.lastBotBubbleHashes().contains(ConversationState.shortHash(confirmText))) {
                        String altConfirmText = "Dạ " + selfP + " xin lỗi vì chưa hiểu trọn ý " + h + " ạ. "
                                + capH + " muốn " + selfP + " chia sẻ kỹ hơn về quyền lợi của " + heldPkgLabel
                                + " hay chuyển chuyên viên hỗ trợ trực tiếp cho " + h + " nhé?";
                        yield List.of(new ReplyBubble(altConfirmText, 0));
                    }
                    yield List.of(new ReplyBubble(confirmText, 0));
                }
                if (activeMicroSkill != null && activeMicroSkill.defaultFallbackReply() != null && !activeMicroSkill.defaultFallbackReply().isBlank()
                        && !decision.prependsConfiguredOpening()) {
                    String candidateFallback = activeMicroSkill.resolvedFallbackReply(h);
                    if (priorState.lastBotBubbleHashes() == null
                            || !priorState.lastBotBubbleHashes().contains(ConversationState.shortHash(candidateFallback))) {
                        yield List.of(new ReplyBubble(candidateFallback, 0));
                    }
                }
                String categories = extractCatalogCategorySummary(fusedChunks);
                String discText = "Hôm nay " + h + " đang muốn " + categories
                        + " hay chăm sóc gói nào để " + selfP + " tư vấn nhẹ nhàng cho " + h + " nha?";
                yield List.of(new ReplyBubble(discText, decision.prependsConfiguredOpening() ? 1200 : 0));
            }
            default -> List.of();
        };

        if (openingBubbles.isEmpty()) {
            if (!responseBubbles.isEmpty() && responseBubbles.get(0).typingDelayMs() != 0) {
                List<ReplyBubble> normalized = new ArrayList<>(responseBubbles);
                normalized.set(0, new ReplyBubble(normalized.get(0).text(), 0));
                return normalized;
            }
            return responseBubbles;
        }
        List<ReplyBubble> combined = new ArrayList<>(openingBubbles);
        if (!responseBubbles.isEmpty()) {
            ReplyBubble second = responseBubbles.get(0);
            int thirdBubbleDelay = openingBubbles.size() >= 2 ? 2000 : (second.typingDelayMs() > 0 ? second.typingDelayMs() : 1200);
            combined.add(new ReplyBubble(second.text(), thirdBubbleDelay));
        }
        return combined;
    }

    private static String formatDownsellPackageLabel(CatalogOption lowerOpt, ConversationState priorState) {
        if (lowerOpt == null) {
            return "gói tiết kiệm hơn";
        }
        String heldRaw = priorState != null && priorState.heldPackage() != null
                ? priorState.heldPackage()
                : (priorState != null ? priorState.lastQuotedPackage() : null);
        String heldDisplay = heldRaw != null
                ? ConversationState.toDisplayPackageName(heldRaw, null).toLowerCase(Locale.ROOT)
                : "";
        String heldBaseName = heldDisplay.replaceAll("\\b\\d{1,3}\\s*(?:phút|p|ml|buổi|gói)\\b.*", "")
                .replaceAll("^gói\\s+(?:ngắn\\s+)?", "")
                .strip();
        String lowerName = lowerOpt.name() != null ? lowerOpt.name().toLowerCase(Locale.ROOT).strip() : "";
        int prevDur = priorState != null ? ConversationState.parseDurationInt(priorState.heldDuration()) : 0;
        boolean sameServiceName = !heldBaseName.isEmpty() && !lowerName.isEmpty()
                && (heldBaseName.equals(lowerName) || heldBaseName.contains(lowerName) || lowerName.contains(heldBaseName));
        if (!lowerName.isEmpty() && (!sameServiceName || (prevDur > 0 && lowerOpt.unitNumeric() == prevDur))) {
            return "gói " + lowerOpt.name() + " " + lowerOpt.unitLabel();
        }
        return "gói " + lowerOpt.unitLabel();
    }

    /**
     * Chọn gói thấp hơn đúng 1 bậc theo quy tắc R12 / R11-Explicit-Downsell hoàn toàn động từ {@code fusedChunks}:
     * 1. Cùng nhóm dịch vụ, thời lượng/đơn giá thấp hơn gói đang giữ.
     * 2. Không nằm trong {@code rejectedDurations} hoặc {@code rejectedPackages}.
     * 3. Tài liệu {@code fusedChunks} có niêm yết gói đó.
     * Nếu không tìm được gói thỏa mãn -> trả về {@code null}.
     */
    public static CatalogOption selectLowerCatalogOption(
            ConversationState priorState,
            List<ReciprocalRankFusion.FusedChunk> fusedChunks,
            String currentMessage
    ) {
        String lowerMsg = currentMessage != null ? currentMessage.toLowerCase(Locale.ROOT) : "";
        if (lowerMsg.contains("docs_has_lower_package=false")) {
            return null;
        }
        String region = ConversationState.detectPreferredRegion(
                lowerMsg,
                priorState != null ? priorState.preferredRegion() : "HN"
        );
        List<CatalogOption> catalog = parseCatalogFromChunks(fusedChunks, region);
        if (catalog.isEmpty() && priorState != null && priorState.availablePackageCatalog() != null && !priorState.availablePackageCatalog().isEmpty()) {
            catalog = priorState.availablePackageCatalog().stream()
                    .filter(p -> p != null)
                    .map(p -> new CatalogOption(
                            p.displayName(),
                            p.durationLabel() != null ? p.durationLabel()
                                    : (p.durationMinutes() != null && p.durationMinutes() > 0
                                        ? (p.durationMinutes() + " phút")
                                        : (p.formattedSpecsSummary() != null ? p.formattedSpecsSummary() : "")),
                            p.durationMinutes() != null ? p.durationMinutes() : 0,
                            p.priceDisplay(),
                            p.priceVnd(),
                            p.includesSummary() != null ? p.includesSummary() : "",
                            p.serviceFamily() != null ? p.serviceFamily() : ""
                    ))
                    .toList();
        }
        if (catalog.isEmpty()) {
            return null;
        }

        int currentDur = 0;
        long currentPriceVnd = 0L;
        String currentPkgRaw = null;
        if (priorState != null) {
            currentPkgRaw = priorState.heldPackage() != null
                    ? priorState.heldPackage()
                    : (priorState.lastQuotedPackage() != null
                        ? priorState.lastQuotedPackage()
                        : priorState.knownRecommendedPackage());
            currentDur = ConversationState.parseDurationInt(priorState.heldDuration());
            if (currentDur == 0) {
                currentDur = ConversationState.parseDurationInt(priorState.lastQuotedDuration());
            }
            if (currentDur == 0 && priorState.knownRecommendedPackage() != null) {
                currentDur = ConversationState.parseDurationInt(
                        ConversationState.extractDurationFromText(priorState.knownRecommendedPackage()));
            }
            if (priorState.pricesShown() != null) {
                for (String p : priorState.pricesShown()) {
                    long pVal = parsePriceVndFromString(p);
                    if (pVal > currentPriceVnd) {
                        currentPriceVnd = pVal;
                    }
                    if (currentDur == 0 && pVal > 0) {
                        for (CatalogOption opt : catalog) {
                            if (opt.priceVnd() == pVal && opt.unitNumeric() > 0) {
                                currentDur = opt.unitNumeric();
                                break;
                            }
                        }
                    }
                }
            }
        }

        String primaryServiceName = resolvePrimaryMultiTierServiceName(catalog, currentPkgRaw);
        List<CatalogOption> familyOptions = new ArrayList<>();
        for (CatalogOption opt : catalog) {
            if (primaryServiceName.isEmpty()
                    || opt.name().equalsIgnoreCase(primaryServiceName)
                    || primaryServiceName.contains(opt.name().toLowerCase(Locale.ROOT))
                    || opt.name().toLowerCase(Locale.ROOT).contains(primaryServiceName)) {
                familyOptions.add(opt);
            }
        }
        if (familyOptions.isEmpty()) {
            familyOptions.addAll(catalog);
        }

        if (currentDur == 0 && familyOptions.size() >= 2) {
            List<CatalogOption> sortedAsc = new ArrayList<>(familyOptions);
            sortedAsc.sort(Comparator.comparingInt(CatalogOption::unitNumeric).thenComparingLong(CatalogOption::priceVnd));
            CatalogOption mid = sortedAsc.get(Math.min(2, sortedAsc.size() - 1));
            currentDur = mid.unitNumeric();
            if (currentPriceVnd == 0L) {
                currentPriceVnd = mid.priceVnd();
            }
        }

        Set<String> rejectedDurs = priorState != null && priorState.rejectedDurations() != null
                ? priorState.rejectedDurations()
                : Set.of();
        Set<String> rejectedPkgs = priorState != null && priorState.rejectedPackages() != null
                ? priorState.rejectedPackages()
                : Set.of();

        String requestedDurStr = SentimentIntentLayer.extractDurationNumber(lowerMsg);
        int requestedDur = ConversationState.parseDurationInt(requestedDurStr);

        List<CatalogOption> candidates = new ArrayList<>();
        for (CatalogOption opt : familyOptions) {
            if (currentPriceVnd > 0 && opt.priceVnd() > 0 && opt.priceVnd() >= currentPriceVnd) {
                continue;
            }
            boolean isLower = (currentDur > 0 && opt.unitNumeric() > 0)
                    ? opt.unitNumeric() < currentDur
                    : (currentPriceVnd > 0 && opt.priceVnd() < currentPriceVnd);
            if (!isLower) {
                continue;
            }
            if (requestedDur > 0 && opt.unitNumeric() != requestedDur) {
                continue;
            }
            String durStr = String.valueOf(opt.unitNumeric());
            String pkgCode = ConversationState.toCanonicalPackageCode(opt.name() + " " + opt.unitLabel(), durStr);
            if (rejectedDurs.contains(durStr) || (pkgCode != null && rejectedPkgs.contains(pkgCode))) {
                continue;
            }
            candidates.add(opt);
        }

        // Fallback khi mỗi gói trong bảng giá là một mã riêng (single-tier per name): tìm gói hợp phạm vi có mức giá thấp hơn
        if (candidates.isEmpty() && currentPriceVnd > 0) {
            List<String> knownScopes = (priorState != null && priorState.knownBodyAreas() != null)
                    ? priorState.knownBodyAreas()
                    : List.of();
            for (CatalogOption opt : catalog) {
                if (opt.priceVnd() <= 0 || opt.priceVnd() >= currentPriceVnd) {
                    continue;
                }
                if (opt.unitNumeric() > 0 && opt.unitNumeric() <= 20 && catalog.size() > 2) {
                    continue;
                }
                if (requestedDur > 0 && opt.unitNumeric() != requestedDur) {
                    continue;
                }
                if (isOptionRejectedByMismatchOrState(opt, priorState, currentMessage)) {
                    continue;
                }
                String durStr = String.valueOf(opt.unitNumeric());
                String pkgCode = ConversationState.toCanonicalPackageCode(opt.name() + " " + opt.unitLabel(), durStr);
                if (rejectedDurs.contains(durStr) || (pkgCode != null && rejectedPkgs.contains(pkgCode))) {
                    continue;
                }
                if (!knownScopes.isEmpty() && computeScopeOverlapScore(opt, knownScopes) <= 0) {
                    continue;
                }
                candidates.add(opt);
            }
            if (!candidates.isEmpty()) {
                final int refDur = currentDur;
                candidates.sort((a, b) -> {
                    boolean aNotLonger = refDur <= 0 || a.unitNumeric() <= refDur;
                    boolean bNotLonger = refDur <= 0 || b.unitNumeric() <= refDur;
                    if (aNotLonger != bNotLonger) {
                        return aNotLonger ? -1 : 1;
                    }
                    int priceCmp = Long.compare(b.priceVnd(), a.priceVnd());
                    if (priceCmp != 0) {
                        return priceCmp;
                    }
                    return Integer.compare(b.unitNumeric(), a.unitNumeric());
                });
                return candidates.get(0);
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort(Comparator.comparingInt(CatalogOption::unitNumeric)
                .thenComparingLong(CatalogOption::priceVnd)
                .reversed());
        return candidates.get(0);
    }

    /**
     * Chọn 1-2 gói thay thế chưa từng báo giá / chưa bị từ chối trong phiên khi khách hỏi
     * {@code ALTERNATIVE_PACKAGE_REQUEST} ("Có gói khác k", "Còn gói nào nữa không").
     */
    public static List<CatalogOption> selectAlternativeCatalogOptions(
            ConversationState priorState,
            List<ReciprocalRankFusion.FusedChunk> fusedChunks,
            String currentMessage
    ) {
        String lowerMsg = currentMessage != null ? currentMessage.toLowerCase(Locale.ROOT) : "";
        String region = ConversationState.detectPreferredRegion(
                lowerMsg,
                priorState != null ? priorState.preferredRegion() : "HN"
        );
        List<CatalogOption> catalog = parseCatalogFromChunks(fusedChunks, region);
        if (catalog.isEmpty()) {
            return List.of();
        }
        Set<Long> shownPriceVnds = new LinkedHashSet<>();
        long maxShownPriceVnd = 0L;
        Set<String> mentionedNames = new LinkedHashSet<>();
        Set<String> mentionedCanonicals = new LinkedHashSet<>();
        if (priorState != null) {
            if (priorState.pricesShown() != null) {
                for (String p : priorState.pricesShown()) {
                    long pv = parsePriceVndFromString(p);
                    if (pv > 0) {
                        shownPriceVnds.add(pv);
                        if (pv > maxShownPriceVnd) {
                            maxShownPriceVnd = pv;
                        }
                    }
                }
            }
            if (priorState.knownRecommendedPackage() != null) {
                mentionedNames.add(priorState.knownRecommendedPackage().toLowerCase(Locale.ROOT));
            }
            if (priorState.heldPackage() != null) {
                mentionedNames.add(ConversationState.toDisplayPackageName(priorState.heldPackage(), null).toLowerCase(Locale.ROOT));
                mentionedCanonicals.add(priorState.heldPackage().toLowerCase(Locale.ROOT));
            }
            if (priorState.lastQuotedPackage() != null) {
                mentionedNames.add(ConversationState.toDisplayPackageName(priorState.lastQuotedPackage(), null).toLowerCase(Locale.ROOT));
                mentionedCanonicals.add(priorState.lastQuotedPackage().toLowerCase(Locale.ROOT));
            }
            if (priorState.downsellPackage() != null) {
                mentionedNames.add(ConversationState.toDisplayPackageName(priorState.downsellPackage(), null).toLowerCase(Locale.ROOT));
                mentionedCanonicals.add(priorState.downsellPackage().toLowerCase(Locale.ROOT));
            }
            if (priorState.mentionedPackages() != null) {
                for (String mp : priorState.mentionedPackages()) {
                    if (mp != null && !mp.isBlank()) {
                        mentionedCanonicals.add(mp.toLowerCase(Locale.ROOT));
                        mentionedNames.add(ConversationState.toDisplayPackageName(mp, null).toLowerCase(Locale.ROOT));
                    }
                }
            }
            if (priorState.quotedPackages() != null) {
                for (String qp : priorState.quotedPackages()) {
                    if (qp != null && !qp.isBlank()) {
                        mentionedCanonicals.add(qp.toLowerCase(Locale.ROOT));
                        mentionedNames.add(ConversationState.toDisplayPackageName(qp, null).toLowerCase(Locale.ROOT));
                    }
                }
            }
        }

        List<String> knownScopes = (priorState != null && priorState.knownBodyAreas() != null)
                ? priorState.knownBodyAreas()
                : List.of();
        boolean hadPriceObjection = priorState != null && priorState.objectionCount() > 0;
        long refPrice = maxShownPriceVnd > 0 ? maxShownPriceVnd : Long.MAX_VALUE;

        List<CatalogOption> scopedUnmentioned = new ArrayList<>();
        List<CatalogOption> generalUnmentioned = new ArrayList<>();

        for (CatalogOption opt : catalog) {
            if (isOptionRejectedByMismatchOrState(opt, priorState, currentMessage)) {
                continue;
            }
            if (opt.unitNumeric() > 0 && opt.unitNumeric() <= 20 && catalog.size() > 2) {
                continue;
            }
            String optLower = opt.name().toLowerCase(Locale.ROOT);
            String optCanon = ConversationState.toCanonicalPackageCode(
                    opt.name() + " " + opt.unitLabel(),
                    String.valueOf(opt.unitNumeric())
            );
            boolean alreadyMentionedByName = optCanon != null && mentionedCanonicals.contains(optCanon.toLowerCase(Locale.ROOT));
            if (!alreadyMentionedByName) {
                for (String m : mentionedNames) {
                    if (!m.isBlank() && (m.contains(optLower) || optLower.contains(m))) {
                        alreadyMentionedByName = true;
                        break;
                    }
                }
            }
            boolean alreadyShownPrice = opt.priceVnd() > 0 && shownPriceVnds.contains(opt.priceVnd());
            if (alreadyMentionedByName || alreadyShownPrice) {
                continue;
            }
            int scopeScore = !knownScopes.isEmpty() ? computeScopeOverlapScore(opt, knownScopes) : 1;
            if (scopeScore > 0) {
                scopedUnmentioned.add(opt);
            } else {
                generalUnmentioned.add(opt);
            }
        }

        List<CatalogOption> pool = !scopedUnmentioned.isEmpty() ? scopedUnmentioned : generalUnmentioned;
        if (pool.isEmpty()) {
            return List.of();
        }

        pool.sort((a, b) -> {
            if (hadPriceObjection) {
                boolean aCheaper = a.priceVnd() > 0 && a.priceVnd() < refPrice && a.unitNumeric() >= 60;
                boolean bCheaper = b.priceVnd() > 0 && b.priceVnd() < refPrice && b.unitNumeric() >= 60;
                if (aCheaper != bCheaper) {
                    return aCheaper ? -1 : 1;
                }
            }
            int scA = !knownScopes.isEmpty() ? computeScopeOverlapScore(a, knownScopes) : 0;
            int scB = !knownScopes.isEmpty() ? computeScopeOverlapScore(b, knownScopes) : 0;
            if (scA != scB) {
                return Integer.compare(scB, scA);
            }
            return Long.compare(a.priceVnd(), b.priceVnd());
        });

        List<CatalogOption> picked = new ArrayList<>();
        Set<Long> pickedPrices = new LinkedHashSet<>();
        for (CatalogOption opt : pool) {
            if (pickedPrices.add(opt.priceVnd())) {
                picked.add(opt);
                if (picked.size() >= 2) {
                    break;
                }
            }
        }
        if (picked.isEmpty() && !pool.isEmpty()) {
            picked.add(pool.get(0));
        }
        return picked;
    }

    private static long parsePriceVndFromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        String digits = raw.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            return 0L;
        }
        long val = Long.parseLong(digits);
        return val < 10_000L ? val * 1000L : val;
    }

    private static String resolvePrimaryMultiTierServiceName(List<CatalogOption> catalog, String currentPkgRaw) {
        if (currentPkgRaw != null && !currentPkgRaw.isBlank()) {
            String disp = ConversationState.toDisplayPackageName(currentPkgRaw, null).toLowerCase(Locale.ROOT);
            String stripped = disp.replaceAll("\\b\\d{1,3}\\s*(?:phút|p|ml|buổi|gói)\\b.*", "")
                    .replaceAll("^gói\\s+(?:ngắn\\s+)?", "")
                    .strip();
            if (!stripped.isBlank()) {
                return stripped;
            }
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (CatalogOption opt : catalog) {
            String key = opt.name().toLowerCase(Locale.ROOT);
            counts.merge(key, 1, Integer::sum);
        }
        String bestName = "";
        int bestCount = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestCount = entry.getValue();
                bestName = entry.getKey();
            }
        }
        return bestName;
    }

    private static String resolveServiceFamilyDisplay(
            ConversationState state,
            List<ReciprocalRankFusion.FusedChunk> fusedChunks
    ) {
        List<CatalogOption> catalog = parseCatalogFromChunks(fusedChunks);
        if (state != null && state.knownServiceFamily() != null && !state.knownServiceFamily().isBlank()) {
            String fam = state.knownServiceFamily().toLowerCase(Locale.ROOT);
            if ("body".equals(fam) || "massage_body".equals(fam)) {
                for (CatalogOption opt : catalog) {
                    if (opt.name().toLowerCase(Locale.ROOT).contains("body")) {
                        return opt.name();
                    }
                }
                return "gói chăm sóc toàn thân";
            }
            if ("herbal_soak".equals(fam)) {
                for (CatalogOption opt : catalog) {
                    if (opt.name().toLowerCase(Locale.ROOT).contains("ngâm")) {
                        return opt.name();
                    }
                }
                for (CatalogOption opt : catalog) {
                    String ben = opt.benefitSummary() != null ? opt.benefitSummary().toLowerCase(Locale.ROOT) : "";
                    if (ben.contains("ngâm bồn") || ben.contains("ngâm")) {
                        return opt.name() + " " + opt.unitLabel();
                    }
                }
            }
            if ("ear_care".equals(fam) || "ve_sinh_tai".equals(fam)) {
                for (CatalogOption opt : catalog) {
                    if (opt.name().toLowerCase(Locale.ROOT).contains("tai")) {
                        return opt.name();
                    }
                }
            }
            if (!"neck_shoulder".equals(fam) && !"massage_generic".equals(fam) && !"massage".equals(fam)) {
                String disp = ConversationState.toDisplayPackageName(fam, null);
                if (!disp.contains("_")) {
                    return disp;
                }
            }
        }
        if (state != null && state.knownBodyAreas() != null && !state.knownBodyAreas().isEmpty()) {
            String bestScopedName = null;
            int bestScopeScore = 0;
            for (CatalogOption opt : catalog) {
                if (isOptionRejectedByMismatchOrState(opt, state, null)) {
                    continue;
                }
                int sc = computeScopeOverlapScore(opt, state.knownBodyAreas());
                if (sc > bestScopeScore) {
                    bestScopeScore = sc;
                    bestScopedName = opt.name();
                }
            }
            if (bestScopedName != null && !bestScopedName.isBlank()) {
                return bestScopedName;
            }
        }
        String primary = resolvePrimaryMultiTierServiceName(catalog, null);
        return !primary.isBlank() ? primary : "gói dịch vụ tiêu chuẩn";
    }

    private static boolean isOptionRejectedByMismatchOrState(
            CatalogOption opt,
            ConversationState state,
            String rawMessage
    ) {
        if (opt == null) {
            return false;
        }
        String optLower = opt.name().toLowerCase(Locale.ROOT);
        String primaryNameBeforeCombo = optLower.contains("kết hợp")
                ? optLower.substring(0, optLower.indexOf("kết hợp"))
                : optLower;
        if (rawMessage != null && SentimentIntentLayer.isEntityMismatchCorrection(rawMessage)) {
            String wrongOffer = SentimentIntentLayer.extractRejectedWrongOffer(rawMessage);
            if (wrongOffer != null && !wrongOffer.isBlank()) {
                String wrongLower = wrongOffer.toLowerCase(Locale.ROOT);
                if (optLower.contains(wrongLower) || wrongLower.contains(optLower)) {
                    return true;
                }
                List<String> wrongScopes = ConversationState.extractTargetScopes(wrongLower, List.of());
                for (String ws : wrongScopes) {
                    if (primaryNameBeforeCombo.contains(ws.toLowerCase(Locale.ROOT))) {
                        return true;
                    }
                }
            }
        }
        if (state != null && state.rejectedPackages() != null && !state.rejectedPackages().isEmpty()) {
            String optCanonical = ConversationState.toCanonicalPackageCode(
                    opt.name() + " " + opt.unitLabel(),
                    String.valueOf(opt.unitNumeric())
            );
            for (String rej : state.rejectedPackages()) {
                if (rej == null || rej.isBlank()) {
                    continue;
                }
                String rejLower = rej.toLowerCase(Locale.ROOT);
                if (rejLower.contains("_")) {
                    if (optCanonical != null && rejLower.equalsIgnoreCase(optCanonical)) {
                        return true;
                    }
                } else {
                    if (optLower.contains(rejLower) || rejLower.contains(optLower)) {
                        return true;
                    }
                    List<String> rejScopes = ConversationState.extractTargetScopes(rejLower, List.of());
                    for (String rs : rejScopes) {
                        if (primaryNameBeforeCombo.contains(rs.toLowerCase(Locale.ROOT))) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    public static CatalogOption matchSpecificCatalogOption(
            String message,
            ConversationState state,
            List<ReciprocalRankFusion.FusedChunk> fusedChunks
    ) {
        String lower = message != null ? Normalizer.normalize(message.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT) : "";
        String region = ConversationState.detectPreferredRegion(
                lower,
                state != null ? state.preferredRegion() : "HN"
        );
        List<CatalogOption> catalog = parseCatalogFromChunks(fusedChunks, region, state);
        if (catalog.isEmpty()) {
            return null;
        }
        String cleanLower = SentimentIntentLayer.stripMismatchCritiqueClause(lower)
                .replaceAll("(?iU)(?:thôi\\s+)?(?:mình\\s+|tôi\\s+|anh\\s+|chị\\s+|em\\s+)?(?:không\\s+lấy|ko\\s+lấy|k\\s+lấy|không\\s+chọn|bỏ|đổi)\\s+gói\\s+[^,;.!?]+?(?:\\s+nữa)?(?=[,;.!?]|\\s+đổi|\\s+chuyển|$)", " ")
                .strip();
        int explicitDur = ConversationState.parseDurationInt(ConversationState.extractDurationFromText(cleanLower));
        List<String> msgScopes = ConversationState.extractTargetScopes(cleanLower, List.of());

        // 1. Khớp trực tiếp theo tên dịch vụ hoặc từ khóa đặc trưng trong câu hỏi + thời lượng (nếu có)
        CatalogOption bestDirect = null;
        int bestDirectScore = 0;
        for (CatalogOption opt : catalog) {
            if (isOptionRejectedByMismatchOrState(opt, state, message)) {
                continue;
            }
            String optName = opt.name().toLowerCase(Locale.ROOT);
            int score = 0;
            if (!cleanLower.isBlank() && cleanLower.contains(optName)) {
                score += 20;
            } else {
                String[] tokens = optName.split("\\s+");
                List<String> matchedTokenList = new ArrayList<>();
                for (String tk : tokens) {
                    if (tk.length() >= 3 && !tk.equals("gói") && !tk.equals("dịch") && !tk.equals("massage")
                            && cleanLower.contains(tk)) {
                        matchedTokenList.add(tk);
                    }
                }
                int matchedTokens = matchedTokenList.size();
                if (matchedTokens >= 2) {
                    long optionsSharingAllMatchedTokens = explicitDur > 0 ? 1 : catalog.stream()
                            .filter(other -> !isOptionRejectedByMismatchOrState(other, state, message))
                            .filter(other -> {
                                String otherName = other.name().toLowerCase(Locale.ROOT);
                                for (String mt : matchedTokenList) {
                                    if (!otherName.contains(mt)) {
                                        return false;
                                    }
                                }
                                return true;
                            })
                            .count();
                    if (optionsSharingAllMatchedTokens <= 1) {
                        score += matchedTokens * 6;
                    }
                } else if (matchedTokens == 1 && (optName.contains("đá nóng") && cleanLower.contains("đá nóng")
                        || optName.contains("ngâm chân") && cleanLower.contains("ngâm")
                        || optName.contains("body") && (cleanLower.contains("body") || cleanLower.contains("toàn thân"))
                        || optName.contains("tai") && cleanLower.contains("tai"))) {
                    score += 12;
                } else if (cleanLower.contains("ngâm")
                        && opt.benefitSummary() != null
                        && opt.benefitSummary().toLowerCase(Locale.ROOT).contains("ngâm bồn")) {
                    score += 13;
                }
            }
            boolean matchedKnownFamilyAndDuration = false;
            if (explicitDur > 0) {
                if (opt.unitNumeric() == explicitDur) {
                    score += 15;
                    if (state != null && state.knownServiceFamily() != null && !state.knownServiceFamily().isBlank()) {
                        String famLower = state.knownServiceFamily().toLowerCase(Locale.ROOT);
                        if ((optName.contains(famLower) || ("gội đầu".equals(famLower) && optName.contains("dưỡng sinh")))
                                && !optName.contains("+")) {
                            score += 12;
                            matchedKnownFamilyAndDuration = true;
                        }
                    }
                } else {
                    score -= 20;
                }
            }
            if (!msgScopes.isEmpty() && !matchedKnownFamilyAndDuration) {
                int scopeOverlap = computeScopeOverlapScore(opt, msgScopes);
                if (scopeOverlap == 0) {
                    score -= 25;
                } else {
                    score += scopeOverlap;
                }
            }
            if (score > bestDirectScore && score >= 12) {
                bestDirectScore = score;
                bestDirect = opt;
            }
        }
        if (bestDirect != null) {
            return bestDirect;
        }

        // 2. Nếu khách đã có knownRecommendedPackage hoặc recommendedOptions trong state và đang hỏi giá ("Giá sao")
        if (state != null && state.knownRecommendedPackage() != null && !state.knownRecommendedPackage().isBlank()) {
            String recPkg = state.knownRecommendedPackage().toLowerCase(Locale.ROOT);
            int recDur = ConversationState.parseDurationInt(ConversationState.extractDurationFromText(recPkg));
            for (CatalogOption opt : catalog) {
                if (isOptionRejectedByMismatchOrState(opt, state, message)) {
                    continue;
                }
                boolean nameMatch = recPkg.contains(opt.name().toLowerCase(Locale.ROOT));
                boolean durMatch = recDur == 0 || opt.unitNumeric() == recDur;
                if (nameMatch && durMatch) {
                    return opt;
                }
            }
            if (recDur > 0) {
                String primaryName = resolvePrimaryMultiTierServiceName(catalog, recPkg);
                for (CatalogOption opt : catalog) {
                    if (isOptionRejectedByMismatchOrState(opt, state, message)) {
                        continue;
                    }
                    if (opt.unitNumeric() == recDur && opt.name().equalsIgnoreCase(primaryName)) {
                        return opt;
                    }
                }
            }
        }

        if (state != null && state.recommendedOptions() != null && !state.recommendedOptions().isEmpty()) {
            String firstRec = state.recommendedOptions().get(0);
            int recDur = ConversationState.parseDurationInt(ConversationState.extractDurationFromText(firstRec));
            if (recDur > 0) {
                String primaryName = resolvePrimaryMultiTierServiceName(catalog, state.knownServiceFamily());
                for (CatalogOption opt : catalog) {
                    if (isOptionRejectedByMismatchOrState(opt, state, message)) {
                        continue;
                    }
                    if (opt.unitNumeric() == recDur && (primaryName.isEmpty() || opt.name().equalsIgnoreCase(primaryName))) {
                        return opt;
                    }
                }
            }
        }

        return null;
    }

    /**
     * Chọn gói dịch vụ phù hợp nhất từ {@code fusedChunks} có kiểm tra chặt chẽ phạm vi {@code targetScopes} (Scope-Grounded Recommendation).
     * <p>
     * Quy tắc cốt lõi:
     * <ul>
     *   <li>Nếu khách có {@code targetScopes} (ví dụ {@code ["chân", "gối"]}), chỉ giữ lại các {@link CatalogOption} có độ khớp phạm vi &gt; 0.</li>
     *   <li>Nghiêm cấm chọn gói lệch phạm vi (như {@code cổ/vai} khi khách chỉ đau {@code chân/gối}).</li>
     *   <li>Nếu không có gói nào trong {@code fusedChunks} khớp {@code targetScopes} -> trả về {@code null} để định tuyến sang Handoff.</li>
     * </ul>
     */
    public static CatalogOption selectBestSingleCatalogOption(
            String message,
            ConversationState state,
            List<ReciprocalRankFusion.FusedChunk> fusedChunks
    ) {
        CatalogOption direct = matchSpecificCatalogOption(message, state, fusedChunks);
        if (direct != null) {
            return direct;
        }

        String lowerMsg = message != null ? message.toLowerCase(Locale.ROOT) : "";
        String region = ConversationState.detectPreferredRegion(
                lowerMsg,
                state != null ? state.preferredRegion() : "HN"
        );
        List<CatalogOption> catalog = parseCatalogFromChunks(fusedChunks, region, state);
        if (catalog.isEmpty()) {
            return null;
        }

        boolean isMildOccasional = lowerMsg.contains("thi thoảng")
                || lowerMsg.contains("thỉnh thoảng")
                || lowerMsg.contains("thi thoải")
                || (state != null && state.knownSeverity() != null && state.knownSeverity().contains("thi thoảng"));

        Set<String> targetScopes = new LinkedHashSet<>();
        if (state != null && state.confirmedScopes() != null) {
            targetScopes.addAll(state.confirmedScopes());
        }
        if (state != null && state.knownBodyAreas() != null) {
            targetScopes.addAll(state.knownBodyAreas());
        }
        targetScopes.addAll(ConversationState.extractTargetScopes(message, List.of()));
        if (state != null && state.forbiddenScopes() != null && !state.forbiddenScopes().isEmpty()) {
            targetScopes.removeAll(state.forbiddenScopes());
            if (state.forbiddenScopes().contains("đầu gối") || state.forbiddenScopes().contains("khớp gối")) {
                targetScopes.remove("gối");
            }
        }

        if (!targetScopes.isEmpty()) {
            List<String> scopeList = new ArrayList<>(targetScopes);
            Map<String, List<CatalogOption>> scopedByService = new LinkedHashMap<>();
            Map<String, Integer> serviceBestScopeScore = new LinkedHashMap<>();

            for (CatalogOption opt : catalog) {
                if (isOptionRejectedByMismatchOrState(opt, state, message)) {
                    continue;
                }
                int score = computeScopeOverlapScore(opt, scopeList);
                if (score > 0) {
                    String key = opt.name().toLowerCase(Locale.ROOT);
                    scopedByService.computeIfAbsent(key, k -> new ArrayList<>()).add(opt);
                    serviceBestScopeScore.merge(key, score, Math::max);
                }
            }

            if (scopedByService.isEmpty()) {
                // Scope-Grounded Gate: Không có gói nào trong Active Chunks khớp targetScopes -> trả về null để Handoff!
                return null;
            }

            String bestServiceKey = null;
            int maxScore = -1;
            for (Map.Entry<String, Integer> entry : serviceBestScopeScore.entrySet()) {
                if (entry.getValue() > maxScore) {
                    maxScore = entry.getValue();
                    bestServiceKey = entry.getKey();
                }
            }

            List<CatalogOption> tiers = scopedByService.get(bestServiceKey);
            return pickTierForService(tiers, isMildOccasional, state);
        }

        // Khi không có targetScopes cụ thể, chọn theo knownServiceFamily hoặc nhóm dịch vụ chính nhiều bậc nhất
        String famService = resolveServiceFamilyDisplay(state, fusedChunks).toLowerCase(Locale.ROOT);
        boolean isHerbalSoakFamily = state != null && "herbal_soak".equalsIgnoreCase(state.knownServiceFamily());
        List<CatalogOption> familyTiers = new ArrayList<>();
        for (CatalogOption opt : catalog) {
            if (isOptionRejectedByMismatchOrState(opt, state, message)) {
                continue;
            }
            String optLower = opt.name().toLowerCase(Locale.ROOT);
            String optFullLower = (opt.name() + " " + opt.unitLabel()).toLowerCase(Locale.ROOT);
            String benLower = opt.benefitSummary() != null ? opt.benefitSummary().toLowerCase(Locale.ROOT) : "";
            if (optLower.contains(famService)
                    || famService.contains(optLower)
                    || optFullLower.equals(famService)
                    || (isHerbalSoakFamily && (optLower.contains("ngâm") || benLower.contains("ngâm bồn")))
                    || (famService.contains("gội") && optLower.contains("dưỡng sinh") && !optLower.contains("+"))) {
                familyTiers.add(opt);
            }
        }
        if (familyTiers.isEmpty()) {
            for (CatalogOption opt : catalog) {
                if (!isOptionRejectedByMismatchOrState(opt, state, message)) {
                    familyTiers.add(opt);
                }
            }
        }
        return pickTierForService(familyTiers, isMildOccasional, state);
    }

    private static int computeScopeOverlapScore(CatalogOption opt, List<String> targetScopes) {
        if (opt == null || targetScopes == null || targetScopes.isEmpty()) {
            return 0;
        }
        String nameLower = opt.name().toLowerCase(Locale.ROOT);
        String benefitLower = opt.benefitSummary() != null ? opt.benefitSummary().toLowerCase(Locale.ROOT) : "";
        String headingLower = opt.headingContext() != null ? opt.headingContext().toLowerCase(Locale.ROOT) : "";
        String combinedDetailLower = benefitLower + " " + headingLower;
        String primaryNameBeforeCombo = nameLower.contains("kết hợp")
                ? nameLower.substring(0, nameLower.indexOf("kết hợp"))
                : nameLower;

        boolean hasFullBodyProcess = (combinedDetailLower.contains("massage trị liệu toàn thân")
                || combinedDetailLower.contains("full body")
                || combinedDetailLower.contains("tinh dầu thảo dược toàn thân")
                || combinedDetailLower.contains("ấn huyệt toàn thân")
                || combinedDetailLower.contains("massage trị liệu + đi đá nóng")
                || headingLower.contains("trị liệu cơ thể"))
                && !nameLower.contains("gội đầu")
                && !headingLower.contains("gội đầu");
        List<String> optionNameScopes = ConversationState.extractTargetScopes(primaryNameBeforeCombo, List.of());
        // Nếu gói có phạm vi chuyên biệt trên tên chính (VD: "tai", "cổ", "vai", "chân") mà lệch hoàn toàn với targetScopes -> loại trừ!
        // Ngoại trừ khi quy trình/bao gồm (combinedDetailLower) nêu rõ gói có chăm sóc trị liệu toàn thân VÀ có làm đúng vùng targetScope.
        if (!optionNameScopes.isEmpty()) {
            boolean anyPrimaryMatch = false;
            for (String s : targetScopes) {
                String sl = s.toLowerCase(Locale.ROOT);
                boolean hairWashCoversScope = (nameLower.contains("gội đầu") || headingLower.contains("gội đầu"))
                        && ("cổ".equals(sl) || "vai".equals(sl) || "gáy".equals(sl))
                        && (combinedDetailLower.contains("massage " + sl)
                            || combinedDetailLower.contains("trị liệu " + sl)
                            || combinedDetailLower.contains("điều trị " + sl)
                            || combinedDetailLower.contains("– " + sl));
                if (primaryNameBeforeCombo.contains(sl)
                        || hairWashCoversScope
                        || (hasFullBodyProcess && (combinedDetailLower.contains(sl) || ("gối".equals(sl) && combinedDetailLower.contains("chân"))))) {
                    anyPrimaryMatch = true;
                    break;
                }
            }
            if (!anyPrimaryMatch) {
                return 0;
            }
        }

        int score = 0;
        for (String scope : targetScopes) {
            String s = scope.toLowerCase(Locale.ROOT);
            if (primaryNameBeforeCombo.contains(s)) {
                score += 12;
            } else if (nameLower.contains(s)) {
                score += 5;
            }
            if (benefitLower.contains(s)) {
                score += 4;
            } else if (headingLower.contains(s)) {
                score += 4;
            } else if ("gối".equals(s)
                    && !nameLower.contains("gội đầu")
                    && !headingLower.contains("gội đầu")
                    && (combinedDetailLower.contains("chân") || hasFullBodyProcess)) {
                score += 5;
            }
            if (hasFullBodyProcess && (combinedDetailLower.contains(s) || ("gối".equals(s) && combinedDetailLower.contains("chân")))) {
                score += 9;
            }
        }
        if (score > 0 && (nameLower.contains("body") || nameLower.contains("toàn thân"))) {
            score += 2;
        }
        if (score > 0 && opt.unitNumeric() > 0 && opt.unitNumeric() <= 20 && targetScopes.size() >= 2) {
            score = Math.max(1, score - 10);
        }
        return score;
    }

    private static CatalogOption pickTierForService(
            List<CatalogOption> tiers,
            boolean isMildOccasional,
            ConversationState state
    ) {
        if (tiers == null || tiers.isEmpty()) {
            return null;
        }
        List<CatalogOption> sorted = new ArrayList<>(tiers);
        sorted.sort(Comparator.comparingInt(CatalogOption::unitNumeric).thenComparingLong(CatalogOption::priceVnd));
        if (sorted.size() == 1) {
            return sorted.get(0);
        }
        if (sorted.size() == 2) {
            return sorted.get(0);
        }
        // Khi dịch vụ có >= 3 mức thời lượng (ví dụ: 30, 45, 60, 90):
        // Index 0 (30p) là gói ngắn để downsell; Index 1 (45p) là gói tiêu chuẩn mặc định/thi thoảng;
        // Index 2 (60p) là gói chuyên sâu khi khách nêu rõ nguyên nhân căng mỏi kéo dài.
        if (!isMildOccasional && state != null && state.knownTrigger() != null && !state.knownTrigger().isBlank()) {
            return sorted.get(Math.min(2, sorted.size() - 1));
        }
        return sorted.get(1);
    }

    private static String extractTwoPrimaryUnitLabels(
            List<ReciprocalRankFusion.FusedChunk> fusedChunks,
            String serviceNameFilter,
            String defaultSingleUnit
    ) {
        List<CatalogOption> catalog = parseCatalogFromChunks(fusedChunks);
        String targetService = (serviceNameFilter != null && !serviceNameFilter.isBlank())
                ? serviceNameFilter.toLowerCase(Locale.ROOT)
                : resolvePrimaryMultiTierServiceName(catalog, null);
        List<CatalogOption> matched = new ArrayList<>();
        for (CatalogOption opt : catalog) {
            String optLower = opt.name().toLowerCase(Locale.ROOT);
            if (targetService.isEmpty()
                    || optLower.contains(targetService)
                    || targetService.contains(optLower)
                    || (targetService.contains("gội") && optLower.contains("dưỡng sinh") && !optLower.contains("+"))) {
                matched.add(opt);
            }
        }
        matched.sort(Comparator.comparingInt(CatalogOption::unitNumeric).thenComparingLong(CatalogOption::priceVnd));
        if (matched.size() >= 3) {
            if (matched.get(0).unitNumeric() > 0 && matched.get(0).unitNumeric() < 45) {
                return matched.get(1).unitLabel() + " hoặc " + matched.get(2).unitLabel();
            }
            return matched.get(0).unitLabel() + " hoặc " + matched.get(1).unitLabel();
        }
        if (matched.size() == 2) {
            return matched.get(0).unitLabel() + " hoặc " + matched.get(1).unitLabel();
        }
        if (matched.size() == 1) {
            return matched.get(0).unitLabel();
        }
        return defaultSingleUnit != null && !defaultSingleUnit.isBlank() ? defaultSingleUnit : "tiêu chuẩn hoặc chuyên sâu";
    }

    private static CatalogOption findAddonCatalogOption(List<ReciprocalRankFusion.FusedChunk> fusedChunks) {
        List<CatalogOption> catalog = parseCatalogFromChunks(fusedChunks);
        for (CatalogOption opt : catalog) {
            if (opt.unitNumeric() > 0 && opt.unitNumeric() <= 25 && opt.name().toLowerCase(Locale.ROOT).contains("chân")) {
                return opt;
            }
        }
        for (CatalogOption opt : catalog) {
            if (opt.unitNumeric() > 0 && opt.unitNumeric() <= 25) {
                return opt;
            }
        }
        return null;
    }

    public static List<CatalogOption> parseCatalogFromChunks(List<ReciprocalRankFusion.FusedChunk> fusedChunks) {
        return parseCatalogFromChunks(fusedChunks, "HN");
    }

    public static List<CatalogOption> parseCatalogFromChunks(
            List<ReciprocalRankFusion.FusedChunk> fusedChunks,
            String preferredRegion
    ) {
        return parseCatalogFromChunks(fusedChunks, preferredRegion, null);
    }

    public static List<CatalogOption> parseCatalogFromChunks(
            List<ReciprocalRankFusion.FusedChunk> fusedChunks,
            String preferredRegion,
            ConversationState state
    ) {
        if ((fusedChunks == null || fusedChunks.isEmpty())
                && (state == null || state.availablePackageCatalog() == null || state.availablePackageCatalog().isEmpty())) {
            return List.of();
        }
        List<CatalogOption> result = new ArrayList<>();
        Set<String> seenKeys = new LinkedHashSet<>();

        // 0. Ưu tiên bảng giá chuẩn nhiều cột từ HTML/Spreadsheet qua PackageCatalogService (Mã gói, Tên gói, Giá, Tổng thời gian, Bao gồm, Quy trình chi tiết)
        String targetRegion = (preferredRegion != null && !preferredRegion.isBlank()) ? preferredRegion : "HN";
        List<PackageCatalogService.PackageSummary> pkgSummaries = new ArrayList<>();
        if (fusedChunks != null && !fusedChunks.isEmpty()) {
            pkgSummaries.addAll(PackageCatalogService.extractCatalog(fusedChunks, List.of()));
        }
        if (state != null && state.availablePackageCatalog() != null && !state.availablePackageCatalog().isEmpty()) {
            pkgSummaries.addAll(state.availablePackageCatalog());
        }
        if (!pkgSummaries.isEmpty()) {
            List<PackageCatalogService.PackageSummary> regionFiltered = PackageCatalogService.filterByRegion(pkgSummaries, targetRegion);
            if (regionFiltered.isEmpty()) {
                regionFiltered = pkgSummaries;
            }
            for (PackageCatalogService.PackageSummary pkg : regionFiltered) {
                String name = cleanCatalogServiceName(pkg.displayName());
                if (name.isBlank()) {
                    continue;
                }
                int unitNum = (pkg.durationMinutes() != null && pkg.durationMinutes() > 0) ? pkg.durationMinutes() : 0;
                String unitLabel = unitNum > 0
                        ? (unitNum + " phút")
                        : (pkg.durationLabel() != null && !pkg.durationLabel().isBlank()
                            ? pkg.durationLabel()
                            : (pkg.formattedSpecsSummary() != null && !pkg.formattedSpecsSummary().isBlank()
                                ? pkg.formattedSpecsSummary()
                                : ""));
                String priceDisp = (pkg.priceDisplay() != null && !pkg.priceDisplay().isBlank())
                        ? pkg.priceDisplay()
                        : formatPriceK(pkg.priceVnd() / 1000L);
                String conciseBenefit = buildConciseBenefitFromSummary(pkg);
                String fullContext = String.join(" | ",
                        pkg.code() != null ? pkg.code() : "",
                        pkg.serviceFamily() != null ? pkg.serviceFamily() : "",
                        pkg.includesSummary() != null ? pkg.includesSummary() : "",
                        pkg.suitableFor() != null ? pkg.suitableFor() : "",
                        pkg.processSummary() != null ? pkg.processSummary() : ""
                );
                String key = name.toLowerCase(Locale.ROOT) + "|" + unitLabel;
                if (seenKeys.add(key)) {
                    result.add(new CatalogOption(
                            name,
                            unitLabel,
                            unitNum,
                            priceDisp,
                            pkg.priceVnd(),
                            conciseBenefit,
                            fullContext
                    ));
                }
            }
            if (!result.isEmpty()) {
                return result;
            }
        }

        Pattern bulletOrSentencePattern = Pattern.compile(
                "(?iU)(?:^|[\\n;]|(?<!\\d)\\.(?!\\d)|[-•*])\\s*(?:bảng\\s+giá[^:\\n]*:\\s*)?(?:dịch\\s+vụ\\s+)?"
                + "([^.:\\n—–|()]{3,75}?)(?:\\s*:\\s*)?(?:\\s*(?:thời\\s+lượng|gói))?\\s+(\\d{1,3})\\s*(phút|p|ml|buổi|lần|răng|hàm|chiếc|bộ|tháng|ngày)\\s*"
                + "(?:có\\s+giá|giá|là|:|-|—|–)?\\s*(\\d{2,5})\\s*k(?:\\s*\\([^)]*\\))?(?:\\s*(?:—|–|-|,)\\s*([^.\\n]+))?"
        );
        Pattern multiTierHeaderPattern = Pattern.compile(
                "(?iU)(?:^|[\\n;]|(?<!\\d)\\.(?!\\d)|[-•*])\\s*(?:dịch\\s+vụ\\s+)?([^:\\n]{3,65}?)(?:\\s*:\\s*|\\s+)có\\s+\\d+\\s+mức[^:\\n]*(?::|\\s+là)([^\\n]+)"
        );
        Pattern inlineTierPattern = Pattern.compile(
                "(?iU)(?:\\bgói\\s+)?(\\d{1,3})\\s*(phút|p|ml|buổi|lần|răng|chiếc|bộ|tháng)\\s*(?:có\\s+giá|giá|là|:|-|—|–)?\\s*(\\d{2,5})\\s*k"
        );
        Pattern trailingBenefitPattern = Pattern.compile(
                "(?iU)\\.\\s*((?:giúp|đá\\s+nóng|phù\\s+hợp|hỗ\\s+trợ)[^.\\n]+)"
        );

        for (ReciprocalRankFusion.FusedChunk fc : fusedChunks) {
            if (fc == null || fc.chunk() == null || fc.chunk().text() == null) {
                continue;
            }
            String text = fc.chunk().text();
            if (text.contains("|") && text.contains("---")) {
                continue;
            }
            String headingService = extractHeadingServiceName(fc.chunk().headingPath());
            String headingContext = (fc.chunk().headingPath() != null && !fc.chunk().headingPath().isEmpty())
                    ? String.join(" > ", fc.chunk().headingPath())
                    : "";
            String chunkTrailingBenefit = "";
            Matcher trailM = trailingBenefitPattern.matcher(text);
            if (trailM.find()) {
                chunkTrailingBenefit = trailM.group(1).strip();
            }

            // 1. Multi-tier prose sentences ("Dịch vụ X có 2 mức thời lượng: gói A phút giá Y ... và gói B phút giá Z")
            Matcher headerM = multiTierHeaderPattern.matcher(text);
            while (headerM.find()) {
                String serviceName = !headingService.isBlank()
                        ? headingService
                        : cleanCatalogServiceName(headerM.group(1));
                String lineTiers = headerM.group(2);
                Matcher tierM = inlineTierPattern.matcher(lineTiers);
                while (tierM.find()) {
                    int unitNum = Integer.parseInt(tierM.group(1));
                    String unitWord = normalizeUnitWord(tierM.group(2));
                    String unitLabel = unitNum + " " + unitWord;
                    long kVal = Long.parseLong(tierM.group(3));
                    String priceDisp = formatPriceK(kVal);
                    String key = serviceName.toLowerCase(Locale.ROOT) + "|" + unitLabel;
                    if (seenKeys.add(key)) {
                        result.add(new CatalogOption(
                                serviceName,
                                unitLabel,
                                unitNum,
                                priceDisp,
                                kVal * 1000L,
                                chunkTrailingBenefit,
                                headingContext
                        ));
                    }
                }
            }

            // 2. Standard bullet or prose sentences
            Matcher m = bulletOrSentencePattern.matcher(text);
            while (m.find()) {
                String rawName = m.group(1).strip();
                String rawLower = rawName.toLowerCase(Locale.ROOT);
                if (rawLower.contains("có 2 mức")
                        || rawLower.contains("mức thời lượng")
                        || rawLower.contains("000")
                        || rawLower.contains("giá ")
                        || rawName.equalsIgnoreCase("gói")) {
                    continue;
                }
                String name = cleanCatalogServiceName(rawName);
                if (name.isBlank()) {
                    continue;
                }
                int unitNum = Integer.parseInt(m.group(2));
                String unitWord = normalizeUnitWord(m.group(3));
                String unitLabel = unitNum + " " + unitWord;
                long kVal = Long.parseLong(m.group(4));
                String priceDisp = formatPriceK(kVal);
                String inlineBenefit = m.group(5) != null ? m.group(5).strip() : chunkTrailingBenefit;
                String key = name.toLowerCase(Locale.ROOT) + "|" + unitLabel;
                if (seenKeys.add(key)) {
                    result.add(new CatalogOption(
                            name,
                            unitLabel,
                            unitNum,
                            priceDisp,
                            kVal * 1000L,
                            inlineBenefit,
                            headingContext
                    ));
                }
            }
        }

        // 3. Fallback cho Tenant dùng bảng Markdown 4 cột (`| Tên dịch vụ | 300k | 30 phút | Mô tả |`) nếu không có prose/bullet
        if (result.isEmpty()) {
            Pattern tableRowPattern = Pattern.compile(
                    "(?iU)\\|\\s*([^|\\n]{3,60}?)\\s*\\|\\s*(\\d{2,5})\\s*k\\s*\\|\\s*~?(\\d{1,3})\\s*(phút|p|buổi|lần|răng|chiếc|bộ)\\s*\\|\\s*([^|\\n]*)"
            );
            for (ReciprocalRankFusion.FusedChunk fc : fusedChunks) {
                if (fc == null || fc.chunk() == null || fc.chunk().text() == null) continue;
                String headingContext = (fc.chunk().headingPath() != null && !fc.chunk().headingPath().isEmpty())
                        ? String.join(" > ", fc.chunk().headingPath())
                        : "";
                Matcher tm = tableRowPattern.matcher(fc.chunk().text());
                while (tm.find()) {
                    String name = cleanCatalogServiceName(tm.group(1));
                    long kVal = Long.parseLong(tm.group(2));
                    int unitNum = Integer.parseInt(tm.group(3));
                    String unitLabel = unitNum + " " + normalizeUnitWord(tm.group(4));
                    String benefit = tm.group(5) != null ? tm.group(5).strip() : "";
                    String key = name.toLowerCase(Locale.ROOT) + "|" + unitLabel;
                    if (seenKeys.add(key)) {
                        result.add(new CatalogOption(name, unitLabel, unitNum, formatPriceK(kVal), kVal * 1000L, benefit, headingContext));
                    }
                }
            }
        }
        return result;
    }

    private static String buildConciseBenefitFromSummary(PackageCatalogService.PackageSummary pkg) {
        if (pkg == null || pkg.includesSummary() == null || pkg.includesSummary().isBlank()) {
            return "";
        }
        String cleaned = pkg.includesSummary().replaceAll("\\([^)]*\\)", "").replaceAll("\\s+", " ").strip();
        String[] parts = cleaned.split("\\s*(?:,|\\+|;)\\s*");
        List<String> items = new ArrayList<>();
        boolean hasBuffet = false;
        boolean hasLightSnack = false;
        boolean hasSeasonalFruit = false;
        for (String p : parts) {
            String trimmed = p.strip().toLowerCase(Locale.ROOT);
            if (trimmed.isEmpty()) continue;
            if (trimmed.contains("buffet")) {
                hasBuffet = true;
                continue;
            }
            if (trimmed.contains("ăn nhẹ")) {
                hasLightSnack = true;
                if (trimmed.contains("trái cây theo mùa")) {
                    hasSeasonalFruit = true;
                }
                continue;
            }
            if (trimmed.contains("trái cây theo mùa")) {
                hasSeasonalFruit = true;
                continue;
            }
            if (items.size() < 4) {
                items.add(trimmed);
            }
        }
        if (items.isEmpty()) {
            return "";
        }
        String joined = String.join(", ", items);
        if (hasBuffet) {
            joined = joined + ", kèm buffet thực dưỡng";
        } else if (hasLightSnack && hasSeasonalFruit) {
            joined = joined + ", kèm ăn nhẹ và trái cây theo mùa";
        } else if (hasLightSnack) {
            joined = joined + ", kèm ăn nhẹ";
        } else if (hasSeasonalFruit) {
            joined = joined + ", kèm trái cây theo mùa";
        }
        return "gồm " + joined;
    }

    private static String extractHeadingServiceName(List<String> headingPath) {
        if (headingPath == null || headingPath.size() < 2) {
            return "";
        }
        String candidate = headingPath.get(1);
        if (candidate == null || candidate.isBlank()) {
            return "";
        }
        String lower = candidate.strip().toLowerCase(Locale.ROOT);
        if (isStructuralHeading(lower)) {
            return "";
        }
        return lower;
    }

    private static boolean isStructuralHeading(String lower) {
        if (lower == null || lower.isBlank()) {
            return true;
        }
        String norm = lower.replace('_', ' ').replaceAll("\\s+", " ").strip();
        return norm.matches("^(?:\\d+\\.|[ivx]+\\.)\\s*.*")
                || norm.contains("bảng giá")
                || norm.contains("quy trình")
                || norm.contains("chính sách")
                || norm.contains("thẻ liệu trình")
                || norm.contains("voucher")
                || norm.contains("khuyến mãi")
                || norm.contains("cơ sở")
                || norm.contains("khu vực")
                || norm.contains("địa chỉ")
                || norm.contains("phần ")
                || norm.contains("faq")
                || norm.contains("thực đơn")
                || norm.contains("lưu ý")
                || norm.contains("đối tượng")
                || norm.contains("cam kết");
    }

    private static String cleanCatalogServiceName(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.replaceAll("(?iU)^[\\s\\-•*]+", "")
                .replaceAll("(?iU)^(?:gói\\s+dịch\\s+vụ\\s+|dịch\\s+vụ\\s+|gói\\s+)", "")
                .strip()
                .toLowerCase(Locale.ROOT);
        if (cleaned.endsWith(" thư giãn sâu")) {
            cleaned = cleaned.substring(0, cleaned.length() - " thư giãn sâu".length()).strip();
        }
        return cleaned;
    }

    private static String normalizeUnitWord(String rawUnit) {
        if (rawUnit == null) {
            return "phút";
        }
        String u = rawUnit.strip().toLowerCase(Locale.ROOT);
        return "p".equals(u) ? "phút" : u;
    }

    private static String formatPriceK(long kVal) {
        return kVal + "k";
    }

    private static String extractCatalogCategorySummary(List<ReciprocalRankFusion.FusedChunk> fusedChunks) {
        if (fusedChunks != null && !fusedChunks.isEmpty()) {
            Set<String> headings = new LinkedHashSet<>();
            for (ReciprocalRankFusion.FusedChunk fc : fusedChunks) {
                if (fc == null || fc.chunk() == null || fc.chunk().headingPath() == null) continue;
                List<String> hp = fc.chunk().headingPath();
                if (hp.size() >= 2 && hp.get(1) != null && !hp.get(1).isBlank()) {
                    String h1 = hp.get(1).strip().toLowerCase(Locale.ROOT);
                    if (!isStructuralHeading(h1)) {
                        headings.add(h1);
                        continue;
                    }
                }
                if (!hp.isEmpty() && hp.get(0) != null && !hp.get(0).isBlank()) {
                    String h0 = hp.get(0).strip().toLowerCase(Locale.ROOT);
                    if (!isStructuralHeading(h0)) {
                        headings.add(h0);
                    }
                }
            }
            if (headings.size() >= 2) {
                List<String> list = new ArrayList<>(headings);
                if (list.size() >= 3) {
                    return list.get(0) + ", " + list.get(1) + " hay " + list.get(2);
                }
                return list.get(0) + " hay " + list.get(1);
            }
            List<PackageCatalogService.PackageSummary> pkgSummaries =
                    PackageCatalogService.extractCatalog(fusedChunks, List.of());
            if (!pkgSummaries.isEmpty()) {
                Set<String> families = new LinkedHashSet<>();
                for (PackageCatalogService.PackageSummary ps : pkgSummaries) {
                    if (ps != null && ps.serviceFamily() != null && !ps.serviceFamily().isBlank()) {
                        families.add(ps.serviceFamily().strip().toLowerCase(Locale.ROOT));
                    }
                }
                if (families.size() >= 2) {
                    List<String> famList = new ArrayList<>(families);
                    return famList.size() >= 3
                            ? famList.get(0) + ", " + famList.get(1) + " hay " + famList.get(2)
                            : famList.get(0) + " hay " + famList.get(1);
                }
            }
        }
        List<CatalogOption> catalog = parseCatalogFromChunks(fusedChunks);
        Set<String> names = new LinkedHashSet<>();
        for (CatalogOption opt : catalog) {
            names.add(opt.name());
            if (names.size() >= 3) break;
        }
        if (names.size() >= 2) {
            List<String> list = new ArrayList<>(names);
            return list.size() >= 3
                    ? list.get(0) + ", " + list.get(1) + " hay " + list.get(2)
                    : list.get(0) + " hay " + list.get(1);
        }
        return "nhóm dịch vụ cơ bản hay chuyên sâu";
    }

    /**
     * Xây dựng câu neo cảm xúc tự nhiên, bám sát dữ liệu thực tế của gói dịch vụ và nhu cầu khách đã nêu,
     * tránh ghép chuỗi cơ học kiểu "Gói này gồm ... rất dễ chịu ạ" và tuyệt đối không cam kết chữa khỏi bệnh lý.
     */
    private static String buildNaturalGroundedEmotionalAnchorSentence(
            CatalogOption matched,
            ConversationState enriched,
            PagePersonaConfig cfg,
            String h,
            String selfP
    ) {
        if (matched == null) {
            return "Bên " + selfP + " luôn thực hiện nhẹ nhàng theo cảm giác của " + h + " ạ.";
        }
        String benefitLower = matched.benefitSummary() != null ? matched.benefitSummary().toLowerCase(Locale.ROOT) : "";
        String contextLower = matched.headingContext() != null ? matched.headingContext().toLowerCase(Locale.ROOT) : "";
        String combinedLower = benefitLower + " " + contextLower;

        if (cfg != null && cfg.isNonSpaIndustry()) {
            if (!benefitLower.isBlank() && (benefitLower.startsWith("giúp ") || benefitLower.startsWith("hỗ trợ ") || benefitLower.startsWith("phục hồi "))) {
                return "Liệu trình này " + matched.benefitSummary() + ", được thực hiện nhẹ nhàng theo tình trạng của " + h + " ạ.";
            }
            return "Liệu trình này được thực hiện bài bản, phù hợp với tình trạng hiện tại của " + h + " ạ.";
        }

        boolean hasBuffet = combinedLower.contains("buffet thực dưỡng");
        boolean hasLightSnack = !hasBuffet && (combinedLower.contains("ăn nhẹ") || combinedLower.contains("điểm tâm"));
        List<String> bodyAreas = (enriched != null && enriched.knownBodyAreas() != null)
                ? enriched.knownBodyAreas()
                : List.of();
        List<String> safeAreas = new ArrayList<>();
        for (String area : bodyAreas) {
            if (area != null && !area.isBlank() && !area.equalsIgnoreCase("toàn thân") && !area.equalsIgnoreCase("body")) {
                safeAreas.add(area.strip().toLowerCase(Locale.ROOT));
                if (safeAreas.size() >= 2) {
                    break;
                }
            }
        }
        String areaTarget = !safeAreas.isEmpty()
                ? "vùng " + String.join(", ", safeAreas)
                : "cơ thể";
        if (hasBuffet) {
            return "Gói này kỹ thuật viên sẽ ấn day vừa sức để thả lỏng " + areaTarget + " của " + h + ", làm xong " + h + " còn được dùng thêm buffet thực dưỡng ấm bụng nữa ạ.";
        }
        if (hasLightSnack) {
            return "Gói này kỹ thuật viên sẽ chăm sóc nhẹ nhàng " + areaTarget + " theo đúng cảm nhận của " + h + ", làm xong có kèm phần ăn nhẹ thư thái ạ.";
        }
        return "Gói này kỹ thuật viên sẽ điều chỉnh lực ấn vừa vặn ở " + areaTarget + " để " + h + " thả lỏng cơ thể dễ chịu nhất ạ.";
    }

    /**
     * Kiểm tra xem {@code fusedChunks} có dữ liệu xác thực và không mâu thuẫn để trả lời câu hỏi chi tiết/FAQ hay không.
     */
    public static boolean hasGroundedEvidenceForDetailRequest(
            ConversationState.UnresolvedCustomerRequest req,
            ConversationState state,
            List<ReciprocalRankFusion.FusedChunk> fusedChunks
    ) {
        if (fusedChunks == null || fusedChunks.isEmpty()) {
            // v3.3.4 RC-04: Lượt FAQ có thể không truy xuất lại chunk bảng giá; khi đó chỉ được dựa vào
            // catalog đã xác minh đang lưu trong state cho đúng gói đang giữ, và chỉ với thuộc tính catalog trả lời được.
            return hasHeldPackageCatalogEvidence(req != null ? req.rawCustomerMessage() : null, state);
        }
        String refEntity = req != null && req.referencedEntity() != null
                ? req.referencedEntity()
                : "service_detail";
        String semIntent = req != null && req.semanticIntent() != null
                ? req.semanticIntent()
                : "GENERAL_SERVICE_FAQ";
        String rawQuestion = req != null && req.rawCustomerMessage() != null
                ? req.rawCustomerMessage()
                : "";

        if (detectConflictingCatalogOrFaqEvidence(refEntity, state, fusedChunks)) {
            return false;
        }

        String normQuestion = VietnameseQueryNormalizer.normalizeForSearch(rawQuestion);
        StringBuilder allTextBuilder = new StringBuilder();
        for (ReciprocalRankFusion.FusedChunk fc : fusedChunks) {
            if (fc != null && fc.chunk() != null && fc.chunk().text() != null) {
                allTextBuilder.append(fc.chunk().text().toLowerCase(Locale.ROOT)).append("\n");
            }
        }
        String allText = Normalizer.normalize(allTextBuilder.toString(), Normalizer.Form.NFC);

        // Nếu khách hỏi đích danh một món ăn hoặc dịch vụ cụ thể không có trong tài liệu (VD: "bào ngư", "vi cá", "tôm hùm")
        Matcher specificDishMatcher = Pattern.compile(
                "(?isU)\\bcó\\s+món\\s+([\\p{L}\\s]{3,25}?)(?:\\s+không|\\s+ko|\\s+k|\\s+hả|\\s+ạ|\\?|$)"
        ).matcher(normQuestion);
        if (specificDishMatcher.find()) {
            String askedDish = specificDishMatcher.group(1).strip();
            if (!askedDish.equals("gì") && !askedDish.contains("gì") && !askedDish.contains("nào")
                    && !allText.contains(askedDish)) {
                return false;
            }
        }

        // Nếu khách hỏi định nghĩa một khái niệm/dịch vụ cụ thể "<chủ thể> là gì", kiểm tra chủ thể có thực sự xuất hiện trong KB hay không
        Matcher defSubjectMatcher = Pattern.compile(
                "(?isU)^\\s*([\\p{L}\\d\\s]{2,70}?)\\s+là\\s+(?:cái\\s+)?(?:gì|j)\\b"
        ).matcher(normQuestion);
        if (defSubjectMatcher.find()) {
            String rawSubj = defSubjectMatcher.group(1)
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("(?iU)\\b(?:bên\\s+mình|bên\\s+em|bên\\s+shop|ở\\s+đây|tại\\s+spa|trong\\s+gói|của\\s+gói|dạ|cho\\s+hỏi|mình\\s+hỏi|tôi\\s+hỏi)\\b", " ")
                    .replaceAll("\\s+", " ")
                    .strip();
            boolean isPronounRef = rawSubj.matches("(?iU)^(?:nó|đó|đấy|cái\\s+đó|cái\\s+này|phần\\s+đó|mục\\s+đó|gói\\s+này|gói\\s+đó|liệu\\s+trình\\s+này)$");
            if (!isPronounRef && !rawSubj.isBlank() && !allText.contains(rawSubj)) {
                Set<String> genericWords = Set.of("phòng", "gói", "dịch", "vụ", "liệu", "trình", "massage", "bên", "mình", "shop", "spa");
                List<String> distinctiveTokens = new ArrayList<>();
                for (String tok : rawSubj.split("\\s+")) {
                    if (tok.length() >= 2 && !genericWords.contains(tok)) {
                        distinctiveTokens.add(tok);
                    }
                }
                if (!distinctiveTokens.isEmpty()) {
                    long matchedCount = distinctiveTokens.stream()
                            .filter(tok -> Pattern.compile("(?iU)(?<![\\p{L}\\d])" + Pattern.quote(tok) + "(?![\\p{L}\\d])").matcher(allText).find())
                            .count();
                    if (matchedCount * 2 <= distinctiveTokens.size()) {
                        return false;
                    }
                }
            }
        }

        if ("MENU_ITEMS_EXPLANATION".equals(semIntent) || "BUFFET_MENU_ITEMS_FAQ".equals(semIntent)) {
            return !extractGroundedMenuItemsFromChunks(fusedChunks).isEmpty();
        }
        if (refEntity.contains("buffet") || refEntity.contains("thực dưỡng") || refEntity.contains("ăn nhẹ")
                || refEntity.contains("điểm tâm") || refEntity.contains("nước ép")) {
            return allText.contains("thực dưỡng")
                    || allText.contains("ăn nhẹ")
                    || allText.contains("điểm tâm")
                    || allText.contains("trà dưỡng sinh")
                    || !extractGroundedMenuItemsFromChunks(fusedChunks).isEmpty();
        }
        if ("ROOM_AND_FACILITY_DETAIL".equals(semIntent) || "SERVICE_FACILITY_OR_ROOM_FAQ".equals(semIntent)) {
            return allText.contains("phòng") || allText.contains("không gian") || allText.contains("rèm");
        }
        if ("SERVICE_POLICY_OR_FACILITY_FAQ".equals(semIntent)) {
            if (refEntity.contains("tip") || refEntity.contains("phụ phí")) {
                return allText.contains("tip") || allText.contains("phụ phí") || allText.contains("trọn gói");
            }
            if (refEntity.contains("cọc") || refEntity.contains("đặt lịch")) {
                return allText.contains("cọc") || allText.contains("giữ giường") || allText.contains("đặt lịch");
            }
            if (refEntity.contains("vắng") || refEntity.contains("khung giờ")) {
                return allText.contains("vắng") || allText.contains("thông thoáng") || allText.contains("khung giờ");
            }
        }
        if ("PACKAGE_COMPONENTS_AND_PROCESS".equals(semIntent)
                || "SERVICE_INCLUDED_BENEFITS_FAQ".equals(semIntent)
                || "DURATION_DETAIL".equals(semIntent)
                || "SERVICE_DURATION_FAQ".equals(semIntent)
                || "THERAPIST_AND_TECHNIQUE_DETAIL".equals(semIntent)
                || "SERVICE_PROCESS_OR_TECHNIQUE_FAQ".equals(semIntent)) {
            return !parseCatalogFromChunks(fusedChunks).isEmpty()
                    || !PackageCatalogService.extractCatalog(fusedChunks, List.of()).isEmpty()
                    || extractBestFaqAnswerSentenceFromChunks(rawQuestion, refEntity, false, fusedChunks) != null;
        }
        return !allText.isBlank();
    }

    /**
     * Phát hiện xung đột dữ liệu trong {@code fusedChunks} (ví dụ: cùng một quyền lợi vừa ghi miễn phí vừa ghi thu phụ phí,
     * hoặc cùng một mã gói/tên gói trong cùng khu vực nhưng có 2 mức giá mâu thuẫn nhau).
     */
    public static boolean detectConflictingCatalogOrFaqEvidence(
            String referencedEntity,
            ConversationState state,
            List<ReciprocalRankFusion.FusedChunk> fusedChunks
    ) {
        if (fusedChunks == null || fusedChunks.isEmpty()) {
            return false;
        }
        StringBuilder allTextBuilder = new StringBuilder();
        for (ReciprocalRankFusion.FusedChunk fc : fusedChunks) {
            if (fc != null && fc.chunk() != null && fc.chunk().text() != null) {
                allTextBuilder.append(fc.chunk().text().toLowerCase(Locale.ROOT)).append("\n");
            }
        }
        String allText = Normalizer.normalize(allTextBuilder.toString(), Normalizer.Form.NFC);

        boolean asksRefreshment = referencedEntity != null
                && (referencedEntity.contains("buffet") || referencedEntity.contains("thực dưỡng")
                    || referencedEntity.contains("ăn nhẹ") || referencedEntity.contains("điểm tâm"));
        if (asksRefreshment) {
            boolean claimsFree = allText.contains("miễn phí sau liệu trình")
                    || allText.contains("đã bao gồm trong giá niêm yết")
                    || allText.contains("không cần thanh toán thêm");
            boolean claimsExtraFee = false;
            Pattern affirmativeExtraFeePattern = Pattern.compile(
                    "(?isU)\\b(?:buffet\\s+thực\\s+dưỡng|đồ\\s+ăn\\s+nhẹ|điểm\\s+tâm)\\s+([^.?|\\n]{0,45}?)\\b(?:tính\\s+thêm\\s+phí|phụ\\s+thu\\s+\\d+|mất\\s+thêm\\s+phí)\\b([^|\\n]*)"
            );
            Matcher feeMatcher = affirmativeExtraFeePattern.matcher(allText);
            while (feeMatcher.find()) {
                String between = feeMatcher.group(1).toLowerCase(Locale.ROOT);
                String after = feeMatcher.group(2).toLowerCase(Locale.ROOT);
                boolean isQuestionOrNegated = between.contains("không")
                        || between.contains("ko")
                        || between.contains("chưa")
                        || between.contains("có ")
                        || after.contains("?")
                        || after.contains("không");
                if (!isQuestionOrNegated) {
                    claimsExtraFee = true;
                    break;
                }
            }
            if (claimsFree && claimsExtraFee) {
                return true;
            }
        }

        // Kiểm tra mâu thuẫn giá của cùng một gói (cùng mã gói hoặc cùng tên + thời lượng) trong cùng khu vực trực tiếp trên các dòng bảng
        Map<String, String> seenRowKeyToPrice = new LinkedHashMap<>();
        Pattern priceTokenPattern = Pattern.compile("(?iU)(\\d{2,4}(?:\\.\\d{3}|\\s*k))");
        Pattern durationTokenPattern = Pattern.compile("(?iU)(\\d{2,3}\\s*(?:phút|p\\b))");
        for (ReciprocalRankFusion.FusedChunk fc : fusedChunks) {
            if (fc == null || fc.chunk() == null || fc.chunk().text() == null) continue;
            String chunkRegion = "HN";
            if (fc.chunk().headingPath() != null) {
                String hp = String.join(" ", fc.chunk().headingPath()).toLowerCase(Locale.ROOT);
                if (hp.contains("hồ chí minh") || hp.contains("hcm") || hp.contains("sài gòn") || hp.contains("quận 1")) {
                    chunkRegion = "HCM";
                }
            }
            for (String rawLine : fc.chunk().text().split("\\r?\\n")) {
                String line = rawLine.strip();
                if (!line.startsWith("|") || line.matches("^\\|[\\s\\-:|]+\\|$")) continue;
                String[] cells = line.split("\\|");
                if (cells.length < 4) continue;
                String c1 = cells[1].strip().toLowerCase(Locale.ROOT);
                String c2 = cells[2].strip().toLowerCase(Locale.ROOT);
                if (c1.contains("mã gói") || c1.equals("---") || c2.contains("tên gói")) continue;
                if (!c1.matches("^[a-z]{2,4}\\d{1,3}$") && !line.toLowerCase(Locale.ROOT).contains("phút")) continue;
                Matcher pm = priceTokenPattern.matcher(line);
                if (!pm.find()) continue;
                String normPrice = pm.group(1).toLowerCase(Locale.ROOT).replaceAll("\\s+", "")
                        .replace(".000", "k");
                Matcher dm = durationTokenPattern.matcher(line);
                String normDur = dm.find() ? dm.group(1).toLowerCase(Locale.ROOT).replaceAll("\\s+", "") : "";
                String pkgKey = chunkRegion + "|" + (!c1.isBlank() ? c1 : c2) + "|" + normDur;
                String prevPrice = seenRowKeyToPrice.putIfAbsent(pkgKey, normPrice);
                if (prevPrice != null && !prevPrice.equals(normPrice)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Trích xuất danh sách các món trong thực đơn ăn nhẹ / buffet thực dưỡng từ {@code fusedChunks}
     * hoàn toàn động từ tài liệu của Tenant (không hardcode tên món trong Java).
     */
    public static List<String> extractGroundedMenuItemsFromChunks(List<ReciprocalRankFusion.FusedChunk> fusedChunks) {
        if (fusedChunks == null || fusedChunks.isEmpty()) {
            return List.of();
        }
        Set<String> items = new LinkedHashSet<>();
        Pattern proseMenuPattern = Pattern.compile(
                "(?isU)thực\\s+đơn[^:\\n]{0,80}(?:gồm|bao\\s+gồm)\\s*:\\s*([^.\\n]+)"
        );

        for (ReciprocalRankFusion.FusedChunk fc : fusedChunks) {
            if (fc == null || fc.chunk() == null || fc.chunk().text() == null) {
                continue;
            }
            String text = Normalizer.normalize(fc.chunk().text(), Normalizer.Form.NFC);
            String headingJoined = fc.chunk().headingPath() != null
                    ? String.join(" > ", fc.chunk().headingPath()).toLowerCase(Locale.ROOT)
                    : "";

            // 1. Câu tổng hợp "Thực đơn ... gồm: món A, món B, ..."
            Matcher pm = proseMenuPattern.matcher(text);
            while (pm.find()) {
                String rawList = pm.group(1);
                for (String part : rawList.split("\\s*[,;•]\\s*")) {
                    String cleaned = part.replaceAll("\\([^)]*\\)", "").replaceAll("\\s+", " ").strip();
                    if (cleaned.length() >= 2 && !cleaned.equalsIgnoreCase("các món ăn nhẹ")) {
                        items.add(cleaned);
                    }
                }
            }

            // 2. Bảng thực đơn (1 cột hoặc nhiều cột dưới heading Thực đơn / Buffet)
            boolean isMenuChunk = headingJoined.contains("thực đơn")
                    || headingJoined.contains("buffet")
                    || text.toLowerCase(Locale.ROOT).contains("món mặn")
                    || text.toLowerCase(Locale.ROOT).contains("các món ăn nhẹ / điểm tâm")
                    || text.toLowerCase(Locale.ROOT).contains("các món trong thực đơn");
            if (isMenuChunk && text.contains("|")) {
                String[] lines = text.split("\\r?\\n");
                for (int lineIdx = 0; lineIdx < lines.length; lineIdx++) {
                    String trimmed = lines[lineIdx].strip();
                    if (!trimmed.startsWith("|") || trimmed.contains("---")) {
                        continue;
                    }
                    // Bỏ qua dòng header ngay trước dòng phân cách | --- |
                    if (lineIdx + 1 < lines.length && lines[lineIdx + 1].strip().contains("---")) {
                        continue;
                    }
                    List<String> nonEmptyCells = new ArrayList<>();
                    for (String rawCell : trimmed.split("\\|")) {
                        String c = rawCell.replaceAll("\\([^)]*\\)", "").replaceAll("\\s+", " ").strip();
                        if (!c.isEmpty()) {
                            nonEmptyCells.add(c);
                        }
                    }
                    if (nonEmptyCells.isEmpty()) {
                        continue;
                    }
                    if (nonEmptyCells.size() >= 2) {
                        String firstLower = nonEmptyCells.get(0).toLowerCase(Locale.ROOT);
                        if (firstLower.contains("giải thích")) {
                            continue;
                        }
                        if (firstLower.contains("các món") || firstLower.contains("thực đơn")) {
                            String valCell = nonEmptyCells.get(1);
                            for (String part : valCell.split("\\s*[,;•]\\s*")) {
                                String dish = part.replaceAll("\\([^)]*\\)", "").replaceAll("\\s+", " ").strip();
                                if (dish.length() >= 2 && dish.length() <= 38) {
                                    items.add(dish);
                                }
                            }
                            continue;
                        }
                    }
                    for (String c : nonEmptyCells) {
                        String cl = c.toLowerCase(Locale.ROOT);
                        if (cl.matches("^\\d+$")
                                || cl.equals("stt")
                                || cl.equals("hạng mục")
                                || cl.equals("nội dung")
                                || cl.equals("món mặn")
                                || cl.equals("món ngọt")
                                || cl.equals("đồ uống")
                                || cl.equals("trái cây")
                                || cl.contains("câu hỏi")
                                || cl.contains("trả lời")
                                || cl.contains("giải thích")
                                || cl.contains("chi tiết")
                                || cl.contains("các món")
                                || cl.contains("thực đơn")
                                || cl.length() > 38) {
                            continue;
                        }
                        items.add(c);
                    }
                }
            }
        }
        return new ArrayList<>(items);
    }

    /**
     * Xây dựng bong bóng phản hồi cho {@link PrimaryAction#SERVICE_DETAIL_AND_FAQ_QA},
     * giải đáp trực tiếp câu hỏi chi tiết dịch vụ / quyền lợi / FAQ từ tài liệu,
     * đồng thời xử lý khôi phục khi khách bức xúc vì câu hỏi trước chưa được trả lời (Scenario E)
     * hoặc khi khách chưa hiểu câu giải thích trước đó (Scenario F).
     */
    private static List<ReplyBubble> buildServiceDetailAndFaqBubbles(
            RoutingDecision decision,
            String userMessage,
            ConversationState priorState,
            ConversationState enriched,
            String h,
            String capH,
            String selfP,
            String capSelfP,
            PagePersonaConfig cfg,
            List<ReciprocalRankFusion.FusedChunk> fusedChunks
    ) {
        String intent = decision != null && decision.detectedIntent() != null ? decision.detectedIntent() : "";
        ConversationState.UnresolvedCustomerRequest activeReq = enriched != null && enriched.latestUnresolvedRequest() != null
                ? enriched.latestUnresolvedRequest()
                : (priorState != null ? priorState.latestRequest() : null);

        String activePkgContext = (enriched != null && enriched.heldPackage() != null)
                ? enriched.heldPackage()
                : (enriched != null ? enriched.knownRecommendedPackage() : null);
        String refEntity = activeReq != null && activeReq.referencedEntity() != null
                ? activeReq.referencedEntity()
                : SentimentIntentLayer.extractReferencedDetailEntity(
                        userMessage,
                        priorState != null ? priorState.lastAssistantMessage() : null,
                        activePkgContext);
        if (refEntity == null || refEntity.isBlank()) {
            refEntity = "quyền lợi trong gói";
        }
        String semIntent = activeReq != null && activeReq.semanticIntent() != null
                ? activeReq.semanticIntent()
                : ConversationState.resolveDetailSemanticIntent(userMessage, refEntity);
        if (VietnameseQueryNormalizer.normalizeForSearch(userMessage).matches("(?isU).*\\b(món\\s+gì|những\\s+món\\s+gì|thực\\s+đơn|menu|ăn\\s+gì)\\b.*")) {
            semIntent = "BUFFET_MENU_ITEMS_FAQ";
        }

        // Kiểm tra dữ liệu thiếu hoặc mâu thuẫn (Scenario J)
        boolean hasConflict = detectConflictingCatalogOrFaqEvidence(refEntity, enriched, fusedChunks);
        boolean hasEvidence = !hasConflict && hasGroundedEvidenceForDetailRequest(activeReq, enriched, fusedChunks);
        if (!hasEvidence) {
            String safeMissingReply = hasConflict
                    ? ("Dạ hiện thông tin chi tiết về " + refEntity + " trong tài liệu đang cần đối chiếu lại để đảm bảo chính xác nhất cho " + h
                        + " ạ. " + capSelfP + " xin phép nhờ chuyên viên kiểm tra và báo chuẩn xác ngay cho " + h + " nhé.")
                    : ("Dạ hiện trong bảng thông tin tiêu chuẩn bên " + selfP + " chưa ghi chú chi tiết mục này ạ. "
                        + capSelfP + " xin phép nhờ chuyên viên kiểm tra thực tế và phản hồi chính xác ngay cho " + h + " nhé.");
            return List.of(new ReplyBubble(safeMissingReply, 1000));
        }

        boolean isFrustrationRecovery = "FRUSTRATION_RECOVERY_ANSWER_UNRESOLVED_FAQ".equals(intent);
        boolean isComprehensionSimplification = "COMPREHENSION_RECOVERY_SIMPLIFY_FAQ".equals(intent);
        boolean isClarificationContrast = "CLARIFICATION_DETAIL_OR_FAQ_QA".equals(intent)
                || SentimentIntentLayer.hasContrastOrClarificationPrefix(userMessage);
        boolean hadPriorUnansweredAttempt = priorState != null && priorState.hasUnresolvedCustomerRequest();

        String prefixAck;
        if (isFrustrationRecovery) {
            prefixAck = "Dạ " + selfP + " xin lỗi " + h + " vì vừa rồi trả lời chưa đúng câu hỏi về " + refEntity + " ạ. ";
        } else if (isComprehensionSimplification) {
            prefixAck = "Dạ để " + selfP + " giải thích ngắn gọn và dễ hiểu hơn cho " + h + " nhé ạ: ";
        } else if (isClarificationContrast && hadPriorUnansweredAttempt) {
            prefixAck = "Dạ " + selfP + " xin lỗi vì vừa rồi trả lời chưa sát ý " + h + " ạ. ";
        } else if (isClarificationContrast) {
            prefixAck = "Dạ vâng ạ, ";
        } else {
            prefixAck = "Dạ ";
        }

        boolean hasApologySentence = isFrustrationRecovery || isComprehensionSimplification || (isClarificationContrast && hadPriorUnansweredAttempt);
        PackageCatalogService.PackageSummary pkgSummary = resolveTargetPackageSummaryForDetailQa(userMessage, enriched, fusedChunks);
        CatalogOption catOpt = matchSpecificCatalogOption(userMessage, enriched, fusedChunks);
        if (catOpt == null) {
            catOpt = selectBestSingleCatalogOption(userMessage, enriched, fusedChunks);
        }

        // 1. Câu hỏi về Thực đơn / Buffet có món gì (Scenario D)
        if ("BUFFET_MENU_ITEMS_FAQ".equals(semIntent) || "MENU_ITEMS_EXPLANATION".equals(semIntent)) {
            List<String> menuItems = extractGroundedMenuItemsFromChunks(fusedChunks);
            List<String> displayItems = menuItems.size() > 6 ? menuItems.subList(0, 6) : menuItems;
            String itemsJoined = String.join(", ", displayItems);
            boolean isFreeIncluded = chunksMentionIncludedRefreshment(fusedChunks);
            String body;
            if (isComprehensionSimplification) {
                body = "sau buổi làm, " + h + " được dùng các món ăn nhẹ như " + itemsJoined
                        + (isFreeIncluded ? " miễn phí, đã tính sẵn trong gói ạ." : " tại spa ạ.");
            } else if (hasApologySentence) {
                body = "Thực đơn ăn nhẹ sau liệu trình bên " + selfP + " gồm: " + itemsJoined
                        + (isFreeIncluded ? ", đã bao gồm sẵn trong gói ạ." : " ạ.");
            } else {
                body = "thực đơn buffet thực dưỡng và ăn nhẹ sau liệu trình bên " + selfP + " gồm: "
                        + itemsJoined
                        + (isFreeIncluded ? ", đã bao gồm trong gói và không tính thêm phí ạ." : " phục vụ sau buổi làm ạ.");
            }
            ReplyBubble primaryBubble = new ReplyBubble(prefixAck + body, decision != null && decision.prependsConfiguredOpening() ? 2500 : 1000);
            return appendSecondaryCompoundFaqBubbleIfPresent(
                    primaryBubble, decision, userMessage, semIntent, refEntity, h, selfP, pkgSummary, catOpt, fusedChunks
            );
        }

        // 2. Câu hỏi giải thích "Thực dưỡng là j" / "Buffet thực dưỡng là gì" / "Ăn nhẹ là gì" (Scenario A, B, E, F)
        if (refEntity.contains("buffet") || refEntity.contains("thực dưỡng") || refEntity.contains("ăn nhẹ")
                || refEntity.contains("điểm tâm") || refEntity.contains("nước ép")) {
            List<String> menuItems = extractGroundedMenuItemsFromChunks(fusedChunks);
            boolean isFreeIncluded = chunksMentionIncludedRefreshment(fusedChunks);
            String groundedTeaLabel = extractGroundedTeaLabelFromChunks(fusedChunks);
            boolean mentionsHerbalTea = groundedTeaLabel != null;
            String sampleDishesClause = "";
            if (!menuItems.isEmpty()) {
                List<String> sample = menuItems.subList(0, Math.min(2, menuItems.size()));
                sampleDishesClause = " (như " + String.join(", ", sample) + ")";
            }
            String body;
            if (isComprehensionSimplification) {
                body = "sau khi làm xong, " + h + " được dùng bữa ăn nhẹ" + sampleDishesClause
                        + (mentionsHerbalTea ? " và " + groundedTeaLabel : "")
                        + (isFreeIncluded ? " miễn phí ngay tại spa mà không tốn thêm tiền ạ." : " ngay tại spa ạ.");
            } else if (hasApologySentence) {
                body = "Buffet thực dưỡng là phần điểm tâm ăn nhẹ"
                        + (mentionsHerbalTea ? " và " + groundedTeaLabel + " ấm" : "")
                        + " phục vụ miễn phí sau liệu trình giúp hồi phục năng lượng"
                        + (isFreeIncluded ? ", đã bao gồm sẵn trong giá gói ạ." : " ạ.");
            } else {
                body = refEntity + " bên " + selfP + " là phần điểm tâm ăn nhẹ" + sampleDishesClause
                        + (mentionsHerbalTea ? " kèm " + groundedTeaLabel + " ấm" : "")
                        + " phục vụ miễn phí sau liệu trình giúp hồi phục năng lượng"
                        + (isFreeIncluded ? ", đã bao gồm trong giá gói ạ." : " ạ.");
            }
            ReplyBubble primaryBubble = new ReplyBubble(prefixAck + body, decision != null && decision.prependsConfiguredOpening() ? 2500 : 1000);
            return appendSecondaryCompoundFaqBubbleIfPresent(
                    primaryBubble, decision, userMessage, semIntent, refEntity, h, selfP, pkgSummary, catOpt, fusedChunks
            );
        }

        // 2B. Câu hỏi giải thích bước ngâm bồn trong liệu trình (không bịa tên thảo mộc ngoài tài liệu)
        if (refEntity.contains("ngâm") && pkgSummary != null) {
            String pkgName = pkgSummary.displayName().toLowerCase(Locale.ROOT);
            String inc = (pkgSummary.includesSummary() != null && !pkgSummary.includesSummary().isBlank())
                    ? pkgSummary.includesSummary().toLowerCase(Locale.ROOT).replace(" + ", ", ")
                    : "10 phút ngâm bồn ấm kết hợp massage";
            boolean quotePendingPrice = priorState != null
                    && priorState.priceRequestedInSession()
                    && !priorState.priceDiscussed()
                    && pkgSummary.priceDisplay() != null
                    && !pkgSummary.priceDisplay().isBlank();
            String priceClause = quotePendingPrice
                    ? " (giá " + pkgSummary.priceDisplay() + ")"
                    : "";
            String body = (hasApologySentence ? "Bước " : "bước ")
                    + "này nằm trong gói " + pkgName + " " + pkgSummary.durationMinutes() + " phút"
                    + priceClause + " gồm " + inc
                    + " giúp làm ấm cơ thể; còn thành phần lá cụ thể bảng dịch vụ tiêu chuẩn chưa liệt kê từng vị ạ.";
            ReplyBubble primaryBubble = new ReplyBubble(prefixAck + body, decision != null && decision.prependsConfiguredOpening() ? 2500 : 1000);
            return appendSecondaryCompoundFaqBubbleIfPresent(
                    primaryBubble, decision, userMessage, semIntent, refEntity, h, selfP, pkgSummary, catOpt, fusedChunks
            );
        }

        // 3. Câu hỏi chính sách / FAQ chuyên biệt (tip, phụ phí, đặt cọc, giờ vắng, so sánh gói VIP/cơ bản) -> ưu tiên khớp bảng FAQ trước
        if ("SERVICE_POLICY_OR_FACILITY_FAQ".equals(semIntent)
                || refEntity.contains("tip")
                || refEntity.contains("cọc")
                || refEntity.contains("khác biệt")
                || refEntity.contains("khung giờ vắng")) {
            String faqPolicyAns = extractBestFaqAnswerSentenceFromChunks(userMessage, refEntity, hasApologySentence, fusedChunks);
            if (faqPolicyAns != null && !faqPolicyAns.isBlank()) {
                ReplyBubble primaryBubble = new ReplyBubble(prefixAck + faqPolicyAns, decision != null && decision.prependsConfiguredOpening() ? 2500 : 1000);
                return appendSecondaryCompoundFaqBubbleIfPresent(
                        primaryBubble, decision, userMessage, semIntent, refEntity, h, selfP, pkgSummary, catOpt, fusedChunks
                );
            }
        }

        // 4. Câu hỏi về Không gian phòng, Quy trình, Thời lượng, Kỹ thuật viên hoặc Quyền lợi gói (Scenario G)
        if ("SERVICE_FACILITY_OR_ROOM_FAQ".equals(semIntent) || "ROOM_AND_FACILITY_DETAIL".equals(semIntent)) {
            String normMsg = VietnameseQueryNormalizer.normalizeForSearch(userMessage);
            if (normMsg.contains("2 người") || normMsg.contains("cặp đôi") || normMsg.contains("bạn bè")) {
                String coupleFaq = extractBestFaqAnswerSentenceFromChunks(userMessage, refEntity, hasApologySentence, fusedChunks);
                if (coupleFaq != null && !coupleFaq.isBlank()) {
                    ReplyBubble primaryBubble = new ReplyBubble(prefixAck + coupleFaq, 1000);
                    return appendSecondaryCompoundFaqBubbleIfPresent(
                            primaryBubble, decision, userMessage, semIntent, refEntity, h, selfP, pkgSummary, catOpt, fusedChunks
                    );
                }
            }
            if ((normMsg.contains("phòng chung") || normMsg.contains("sao lại phòng"))
                    && !normMsg.contains("phòng riêng hay")
                    && !normMsg.contains("hay phòng chung")) {
                String pkgClause = (pkgSummary != null && pkgSummary.displayName() != null)
                        ? "gói " + pkgSummary.displayName().toLowerCase(Locale.ROOT) + " (" + pkgSummary.durationMinutes() + " phút)"
                        : "gói cơ bản";
                String body = (hasApologySentence ? "Với " : "với ") + pkgClause
                        + ", bên " + selfP + " thiết kế khu trị liệu chung để giữ mức chi phí nhẹ nhàng, dễ tiếp cận cho khách hàng ạ. "
                        + "Tuy nhiên mỗi giường đều có rèm che riêng kín đáo, không gian yên tĩnh và sạch thoáng để " + h + " nằm nghỉ ngơi thoải mái nha.";
                ReplyBubble primaryBubble = new ReplyBubble(prefixAck + body, 1000);
                return appendSecondaryCompoundFaqBubbleIfPresent(
                        primaryBubble, decision, userMessage, semIntent, refEntity, h, selfP, pkgSummary, catOpt, fusedChunks
                );
            }
            if (pkgSummary != null && pkgSummary.roomType() != null && !pkgSummary.roomType().isBlank()) {
                String pkgName = pkgSummary.displayName().toLowerCase(Locale.ROOT);
                String body = (hasApologySentence ? "Với " : "với ") + "gói " + pkgName + " (" + pkgSummary.durationMinutes() + " phút), không gian phục vụ là "
                        + pkgSummary.roomType().toLowerCase(Locale.ROOT) + " đảm bảo yên tĩnh và kín đáo cho " + h + " ạ.";
                ReplyBubble primaryBubble = new ReplyBubble(prefixAck + body, 1000);
                return appendSecondaryCompoundFaqBubbleIfPresent(
                        primaryBubble, decision, userMessage, semIntent, refEntity, h, selfP, pkgSummary, catOpt, fusedChunks
                );
            }
            String body = (hasApologySentence ? "Không gian " : "không gian ") + "bên " + selfP + " có phòng cộng đồng có rèm riêng tư và phòng VIP khép kín tùy theo gói dịch vụ " + h + " chọn ạ.";
            ReplyBubble primaryBubble = new ReplyBubble(prefixAck + body, 1000);
            return appendSecondaryCompoundFaqBubbleIfPresent(
                    primaryBubble, decision, userMessage, semIntent, refEntity, h, selfP, pkgSummary, catOpt, fusedChunks
            );
        }

        if ("SERVICE_DURATION_FAQ".equals(semIntent) || "DURATION_DETAIL".equals(semIntent)) {
            String normDurMsg = VietnameseQueryNormalizer.normalizeForClassification(userMessage);
            boolean isDurationPushback = normDurMsg.contains("tưởng")
                    || normDurMsg.contains("thời lượng gì nữa")
                    || normDurMsg.contains("thời lượng nào nữa");
            if (pkgSummary != null && pkgSummary.durationMinutes() != null && pkgSummary.durationMinutes() > 0) {
                String pkgName = pkgSummary.displayName().toLowerCase(Locale.ROOT);
                String body;
                if (isDurationPushback) {
                    body = "Dạ đúng rồi ạ, gói " + pkgName + " bên " + selfP + " đang tư vấn cho " + h
                            + " chuẩn là " + pkgSummary.durationMinutes() + " phút"
                            + (pkgSummary.includesSummary() != null && !pkgSummary.includesSummary().isBlank()
                                ? " (" + pkgSummary.includesSummary().toLowerCase(Locale.ROOT) + ")"
                                : "")
                            + "; còn các mức 80 phút hay 100 phút là gói nâng cao có thêm ngâm bồn thảo dược nếu " + h + " muốn làm lâu hơn thôi ạ.";
                    return List.of(new ReplyBubble(body, 1000));
                }
                body = (hasApologySentence ? "Gói " : "gói ") + pkgName + " có tổng thời gian thực hiện là "
                        + pkgSummary.durationMinutes() + " phút"
                        + (pkgSummary.includesSummary() != null && !pkgSummary.includesSummary().isBlank()
                            ? " (gồm " + pkgSummary.includesSummary().toLowerCase(Locale.ROOT) + ") ạ."
                            : " ạ.");
                ReplyBubble primaryBubble = new ReplyBubble(prefixAck + body, 1000);
                return appendSecondaryCompoundFaqBubbleIfPresent(
                        primaryBubble, decision, userMessage, semIntent, refEntity, h, selfP, pkgSummary, catOpt, fusedChunks
                );
            } else if (catOpt != null) {
                String body = isDurationPushback
                        ? "Dạ đúng rồi ạ, gói " + catOpt.name() + " bên " + selfP + " đang tư vấn cho " + h + " có thời lượng chuẩn là " + catOpt.unitLabel() + " ạ."
                        : (hasApologySentence ? "Gói " : "gói ") + catOpt.name() + " có thời lượng thực hiện là " + catOpt.unitLabel() + " ạ.";
                ReplyBubble primaryBubble = new ReplyBubble(isDurationPushback ? body : (prefixAck + body), 1000);
                return appendSecondaryCompoundFaqBubbleIfPresent(
                        primaryBubble, decision, userMessage, semIntent, refEntity, h, selfP, pkgSummary, catOpt, fusedChunks
                );
            }
        }

        // Nếu câu hỏi khớp trực tiếp một dòng FAQ trong tài liệu (và không hỏi "gói này gồm những gì" / "quyền lợi"), ưu tiên câu trả lời FAQ chuẩn
        boolean asksPackageComponentsDirectly = VietnameseQueryNormalizer.normalizeForSearch(userMessage)
                .matches("(?isU).*\\b(bao\\s+gồm|gồm\\s+những|gồm\\s+gì|trong\\s+gói\\s+có|quy\\s+trình|các\\s+bước|quyền\\s+lợi|được\\s+hưởng)\\b.*");
        if (!asksPackageComponentsDirectly) {
            String matchedFaq = extractBestFaqAnswerSentenceFromChunks(userMessage, refEntity, hasApologySentence, fusedChunks);
            if (matchedFaq != null && !matchedFaq.isBlank()) {
                ReplyBubble primaryBubble = new ReplyBubble(prefixAck + matchedFaq, 1000);
                return appendSecondaryCompoundFaqBubbleIfPresent(
                        primaryBubble, decision, userMessage, semIntent, refEntity, h, selfP, pkgSummary, catOpt, fusedChunks
                );
            }
        }

        if ("SERVICE_PROCESS_OR_TECHNIQUE_FAQ".equals(semIntent)
                || "SERVICE_INCLUDED_BENEFITS_FAQ".equals(semIntent)
                || "THERAPIST_AND_TECHNIQUE_DETAIL".equals(semIntent)
                || "PACKAGE_COMPONENTS_AND_PROCESS".equals(semIntent)) {
            if (pkgSummary != null && !cfg.isNonSpaIndustry()) {
                String pkgName = pkgSummary.displayName().toLowerCase(Locale.ROOT);
                String inc = pkgSummary.includesSummary() != null && !pkgSummary.includesSummary().isBlank()
                        ? "gồm " + pkgSummary.includesSummary().toLowerCase(Locale.ROOT)
                        : "thực hiện bài bản theo quy trình";
                String space = pkgSummary.roomType() != null && !pkgSummary.roomType().isBlank()
                        ? ", tại " + pkgSummary.roomType().toLowerCase(Locale.ROOT)
                        : "";
                boolean hasFreeBuffet = (pkgSummary.includesSummary() != null && pkgSummary.includesSummary().toLowerCase(Locale.ROOT).contains("buffet thực dưỡng"))
                        || (pkgSummary.processSummary() != null && pkgSummary.processSummary().toLowerCase(Locale.ROOT).contains("buffet thực dưỡng"));
                String buffetTail = hasFreeBuffet ? " và kèm buffet thực dưỡng sau liệu trình" : "";
                String body = (hasApologySentence ? "Gói " : "gói ") + pkgName + " (" + pkgSummary.durationMinutes() + " phút) "
                        + inc + space + buffetTail + " ạ.";
                ReplyBubble primaryBubble = new ReplyBubble(prefixAck + body, 1000);
                return appendSecondaryCompoundFaqBubbleIfPresent(
                        primaryBubble, decision, userMessage, semIntent, refEntity, h, selfP, pkgSummary, catOpt, fusedChunks
                );
            } else if (catOpt != null) {
                String benefit = (catOpt.benefitSummary() != null && !catOpt.benefitSummary().isBlank())
                        ? catOpt.benefitSummary()
                        : "được thực hiện đầy đủ các bước theo tiêu chuẩn";
                String body = (hasApologySentence ? "Gói " : "gói ") + catOpt.name() + " (" + catOpt.unitLabel() + ") "
                        + benefit + " ạ.";
                ReplyBubble primaryBubble = new ReplyBubble(prefixAck + body, 1000);
                return appendSecondaryCompoundFaqBubbleIfPresent(
                        primaryBubble, decision, userMessage, semIntent, refEntity, h, selfP, pkgSummary, catOpt, fusedChunks
                );
            }
        }

        // Fallback grounded FAQ synthesis từ đoạn văn bản khớp nhất trong fusedChunks
        String bestFaqAnswer = extractBestFaqAnswerSentenceFromChunks(userMessage, refEntity, hasApologySentence, fusedChunks);
        if (bestFaqAnswer != null && !bestFaqAnswer.isBlank()) {
            return List.of(new ReplyBubble(prefixAck + bestFaqAnswer, 1000));
        }

        String cleanRefLabel = refEntity.toLowerCase(Locale.ROOT).startsWith("chi tiết")
                ? refEntity
                : ("chi tiết " + refEntity);
        String safeFallback = "Dạ về " + cleanRefLabel + ", " + selfP
                + " xin phép nhờ chuyên viên kiểm tra và gửi thông tin cụ thể nhất cho " + h + " ngay nhé.";
        return List.of(new ReplyBubble(safeFallback, 1000));
    }

    private static List<ReplyBubble> appendSecondaryCompoundFaqBubbleIfPresent(
            ReplyBubble primaryBubble,
            RoutingDecision decision,
            String userMessage,
            String primarySemIntent,
            String primaryRefEntity,
            String h,
            String selfP,
            PackageCatalogService.PackageSummary pkgSummary,
            CatalogOption catOpt,
            List<ReciprocalRankFusion.FusedChunk> fusedChunks
    ) {
        if (primaryBubble == null || userMessage == null || userMessage.isBlank()
                || (decision != null && decision.prependsConfiguredOpening())) {
            return primaryBubble != null ? List.of(primaryBubble) : List.of();
        }
        String normMsg = VietnameseQueryNormalizer.normalizeForSearch(userMessage);
        boolean hasCompoundConnector = normMsg.contains(" và ")
                || normMsg.contains(" với ")
                || normMsg.contains(" còn ")
                || userMessage.contains(",")
                || userMessage.contains(";");
        if (!hasCompoundConnector) {
            return List.of(primaryBubble);
        }

        String primaryLower = primaryBubble.text().toLowerCase(Locale.ROOT);

        // 1. Secondary Room / Privacy question
        boolean asksRoom = normMsg.matches("(?isU).*\\b(phòng\\s+riêng|phòng\\s+chung|phòng\\s+vip|phòng\\s+cộng\\s+đồng|rèm\\s+riêng|không\\s+gian)\\b.*");
        if (asksRoom && !primaryLower.contains("phòng")) {
            if (pkgSummary != null && pkgSummary.roomType() != null && !pkgSummary.roomType().isBlank()) {
                String sec = "Còn với gói " + pkgSummary.displayName().toLowerCase(Locale.ROOT) + " (" + pkgSummary.durationMinutes()
                        + " phút), không gian phục vụ là " + pkgSummary.roomType().toLowerCase(Locale.ROOT) + " kín đáo cho " + h + " ạ.";
                return List.of(primaryBubble, new ReplyBubble(sec, 2000));
            }
        }

        // 2. Secondary Duration question
        boolean asksDuration = normMsg.matches("(?isU).*\\b(bao\\s+lâu|mấy\\s+phút|bao\\s+nhiêu\\s+phút|thời\\s+lượng)\\b.*");
        if (asksDuration && !primaryLower.contains("phút")) {
            if (pkgSummary != null && pkgSummary.durationMinutes() != null && pkgSummary.durationMinutes() > 0) {
                String sec = "Gói " + pkgSummary.displayName().toLowerCase(Locale.ROOT) + " có tổng thời gian thực hiện là "
                        + pkgSummary.durationMinutes() + " phút ạ.";
                return List.of(primaryBubble, new ReplyBubble(sec, 2000));
            } else if (catOpt != null) {
                String sec = "Gói " + catOpt.name() + " có thời lượng thực hiện là " + catOpt.unitLabel() + " ạ.";
                return List.of(primaryBubble, new ReplyBubble(sec, 2000));
            }
        }

        // 3. Secondary Tip / Extra Fee policy question
        boolean asksTip = normMsg.matches("(?isU).*\\b(tiền\\s+tip|nhận\\s+tip|cần\\s+tip|phụ\\s+phí|phụ\\s+thu)\\b.*");
        if (asksTip && !primaryLower.contains("tip") && !primaryLower.contains("phụ phí")) {
            String tipFaq = extractBestFaqAnswerSentenceFromChunks("tiền tip phụ phí", "chính sách tip", true, fusedChunks);
            if (tipFaq != null && !tipFaq.isBlank()) {
                return List.of(primaryBubble, new ReplyBubble("Ngoài ra, " + Character.toLowerCase(tipFaq.charAt(0)) + tipFaq.substring(1), 2000));
            }
        }

        // 4. Secondary Menu Items question
        boolean asksMenu = normMsg.matches("(?isU).*\\b(món\\s+gì|những\\s+món\\s+gì|thực\\s+đơn|ăn\\s+gì)\\b.*");
        if (asksMenu && !"BUFFET_MENU_ITEMS_FAQ".equals(primarySemIntent) && !primaryLower.contains("thực đơn")) {
            List<String> menuItems = extractGroundedMenuItemsFromChunks(fusedChunks);
            if (!menuItems.isEmpty()) {
                List<String> displayItems = menuItems.size() > 6 ? menuItems.subList(0, 6) : menuItems;
                String sec = "Thực đơn ăn nhẹ đi kèm gồm: " + String.join(", ", displayItems) + " phục vụ miễn phí sau liệu trình ạ.";
                return List.of(primaryBubble, new ReplyBubble(sec, 2000));
            }
        }

        return List.of(primaryBubble);
    }

    private static boolean chunksMentionIncludedRefreshment(List<ReciprocalRankFusion.FusedChunk> fusedChunks) {
        if (fusedChunks == null) return false;
        for (ReciprocalRankFusion.FusedChunk fc : fusedChunks) {
            if (fc == null || fc.chunk() == null || fc.chunk().text() == null) continue;
            String lower = fc.chunk().text().toLowerCase(Locale.ROOT);
            if (lower.contains("miễn phí sau liệu trình")
                    || lower.contains("đã bao gồm trong giá niêm yết")
                    || lower.contains("không cần thanh toán thêm")) {
                return true;
            }
        }
        return false;
    }

    private static boolean chunksMentionKeyword(List<ReciprocalRankFusion.FusedChunk> fusedChunks, String keyword) {
        if (fusedChunks == null || keyword == null) return false;
        String kwLower = keyword.toLowerCase(Locale.ROOT);
        for (ReciprocalRankFusion.FusedChunk fc : fusedChunks) {
            if (fc == null || fc.chunk() == null || fc.chunk().text() == null) continue;
            if (fc.chunk().text().toLowerCase(Locale.ROOT).contains(kwLower)) {
                return true;
            }
        }
        return false;
    }

    // v3.3.4 RC-04: Thuộc tính gói mà catalog (bảng giá đã trích xuất) THỰC SỰ chứa: thời lượng, đơn vị buổi/lần, quyền lợi/bao gồm, không gian phòng.
    private static final Pattern HELD_PACKAGE_ATTRIBUTE_QUESTION_PATTERN = Pattern.compile(
            "(?isU).*\\b(thời\\s+lượng|bao\\s+lâu|mấy\\s+phút|\\d{2,3}\\s*(?:p|phút)|(?:\\d+|một|mấy)\\s*(?:buổi|lần|suất)|tính\\s+theo\\s+buổi|trọn\\s+gói|quyền\\s+lợi|bao\\s+gồm|"
            + "gồm\\s+(?:những\\s+)?(?:gì|j)|phòng|không\\s+gian|rèm)\\b.*"
    );
    // Câu hỏi về món ăn / thực đơn KHÔNG được trả lời từ catalog (catalog không liệt kê món) -> tránh bịa đặt.
    private static final Pattern MENU_OR_FOOD_QUESTION_PATTERN = Pattern.compile(
            "(?isU).*\\b(món|thực\\s+đơn|menu|buffet|đồ\\s+ăn|đồ\\s+uống|ăn\\s+gì|uống\\s+gì)\\b.*"
    );

    /**
     * v3.3.4 RC-04: Có bằng chứng từ catalog đã lưu trong state cho câu hỏi thuộc tính của gói ĐANG GIỮ hay không.
     */
    public static boolean hasHeldPackageCatalogEvidence(String question, ConversationState state) {
        if (question == null || question.isBlank() || state == null) {
            return false;
        }
        if (resolveHeldPackageFromStateCatalog(state) == null) {
            return false;
        }
        String q = VietnameseQueryNormalizer.normalizeForSearch(question);
        return HELD_PACKAGE_ATTRIBUTE_QUESTION_PATTERN.matcher(q).matches()
                && !MENU_OR_FOOD_QUESTION_PATTERN.matcher(q).matches();
    }

    /**
     * v3.3.4 RC-04: Tìm gói đang giữ trong catalog đã cache ở state, ưu tiên khớp đúng giá + thời lượng đã báo.
     */
    public static PackageCatalogService.PackageSummary resolveHeldPackageFromStateCatalog(ConversationState state) {
        if (state == null || state.availablePackageCatalog() == null || state.availablePackageCatalog().isEmpty()) {
            return null;
        }
        if (state.lastOfferedPriceVnd() != null && state.lastOfferedPriceVnd().longValue() > 0L) {
            long activePrice = state.lastOfferedPriceVnd().longValue();
            int activeDur = ConversationState.parseDurationInt(state.lastOfferedDuration());
            for (PackageCatalogService.PackageSummary s : state.availablePackageCatalog()) {
                if (s != null && s.priceVnd() == activePrice
                        && (activeDur <= 0 || (s.durationMinutes() != null && s.durationMinutes() == activeDur))) {
                    return s;
                }
            }
        }
        String heldRaw = state.heldPackage() != null ? state.heldPackage() : state.lastQuotedPackage();
        if (heldRaw != null && !heldRaw.isBlank()) {
            String heldDisp = ConversationState.toDisplayPackageName(heldRaw, state.heldDuration()).toLowerCase(Locale.ROOT);
            int heldDur = ConversationState.parseDurationInt(state.heldDuration());
            for (PackageCatalogService.PackageSummary s : state.availablePackageCatalog()) {
                String nameLower = s != null && s.displayName() != null ? s.displayName().toLowerCase(Locale.ROOT) : "";
                if (!nameLower.isBlank() && heldDisp.contains(nameLower)
                        && (heldDur <= 0 || (s.durationMinutes() != null && s.durationMinutes() == heldDur))) {
                    return s;
                }
            }
        }
        return null;
    }

    private static PackageCatalogService.PackageSummary resolveTargetPackageSummaryForDetailQa(
            String userMessage,
            ConversationState state,
            List<ReciprocalRankFusion.FusedChunk> fusedChunks
    ) {
        String region = state != null && state.preferredRegion() != null ? state.preferredRegion() : "HN";
        List<PackageCatalogService.PackageSummary> all = new ArrayList<>();
        if (fusedChunks != null && !fusedChunks.isEmpty()) {
            all.addAll(PackageCatalogService.filterByRegion(PackageCatalogService.extractCatalog(fusedChunks, List.of()), region));
            if (all.isEmpty()) {
                all.addAll(PackageCatalogService.extractCatalog(fusedChunks, List.of()));
            }
        }
        // v3.3.4 RC-04: CHỈ khi lượt FAQ không truy xuất lại chunk bảng giá mới dùng catalog đã xác minh trong state.
        // Khi có chunk, giữ nguyên hành vi cũ để không đè lên gói khách vừa gọi tên trong câu hỏi.
        boolean usingStateCatalogOnly = fusedChunks == null || fusedChunks.isEmpty();
        if (usingStateCatalogOnly && state != null && state.availablePackageCatalog() != null) {
            for (PackageCatalogService.PackageSummary cachedPkg : state.availablePackageCatalog()) {
                if (cachedPkg != null && all.stream().noneMatch(p -> p.code() != null && p.code().equalsIgnoreCase(cachedPkg.code())
                        && p.priceVnd() == cachedPkg.priceVnd())) {
                    all.add(cachedPkg);
                }
            }
        }
        if (all.isEmpty()) {
            return null;
        }
        String normMsg = VietnameseQueryNormalizer.normalizeForSearch(userMessage);
        int msgDur = ConversationState.parseDurationInt(ConversationState.extractDurationFromText(normMsg));
        for (PackageCatalogService.PackageSummary s : all) {
            if (s.priceVnd() >= 1000) {
                long priceK = s.priceVnd() / 1000;
                if (normMsg.matches("(?isU).*\\b" + priceK + "\\s*k\\b.*")
                        || normMsg.contains(String.valueOf(s.priceVnd()))
                        || normMsg.contains(String.format(Locale.ROOT, "%,d", s.priceVnd()).replace(',', '.'))) {
                    return s;
                }
            }
            if (s.code() != null && !s.code().isBlank()
                    && normMsg.matches("(?isU).*\\b" + Pattern.quote(s.code().toLowerCase(Locale.ROOT)) + "\\b.*")) {
                return s;
            }
        }
        for (PackageCatalogService.PackageSummary s : all) {
            String nameLower = s.displayName().toLowerCase(Locale.ROOT);
            if (normMsg.contains(nameLower) && (msgDur <= 0 || (s.durationMinutes() != null && s.durationMinutes() == msgDur))) {
                return s;
            }
        }
        String heldRaw = state != null && state.heldPackage() != null
                ? state.heldPackage()
                : (state != null && state.lastQuotedPackage() != null
                    ? state.lastQuotedPackage()
                    : (state != null ? state.knownRecommendedPackage() : null));
        int heldDur = state != null ? ConversationState.parseDurationInt(state.heldDuration()) : 0;
        if (heldRaw != null && !heldRaw.isBlank()) {
            String heldDisp = ConversationState.toDisplayPackageName(heldRaw, state != null ? state.heldDuration() : null)
                    .toLowerCase(Locale.ROOT);
            for (PackageCatalogService.PackageSummary s : all) {
                String nameLower = s.displayName().toLowerCase(Locale.ROOT);
                if (heldDisp.contains(nameLower) && (heldDur <= 0 || (s.durationMinutes() != null && s.durationMinutes() == heldDur))) {
                    return s;
                }
            }
        }
        if (normMsg.contains("ngâm") || (state != null && "herbal_soak".equalsIgnoreCase(state.knownServiceFamily()))) {
            for (PackageCatalogService.PackageSummary s : all) {
                String incLower = s.includesSummary() != null ? s.includesSummary().toLowerCase(Locale.ROOT) : "";
                String procLower = s.processSummary() != null ? s.processSummary().toLowerCase(Locale.ROOT) : "";
                String nameLower = s.displayName().toLowerCase(Locale.ROOT);
                if (nameLower.contains("ngâm") || incLower.contains("ngâm bồn") || procLower.contains("ngâm bồn")) {
                    return s;
                }
            }
        }
        // v3.3.4 RC-04: Không có tín hiệu nào khác -> ưu tiên gói đang giữ (khớp giá + thời lượng đã báo) thay vì gói đầu bảng
        if (usingStateCatalogOnly) {
            PackageCatalogService.PackageSummary activeFromState = resolveHeldPackageFromStateCatalog(state);
            if (activeFromState != null) {
                return activeFromState;
            }
        }
        return all.get(0);
    }

    private static String extractBestFaqAnswerSentenceFromChunks(
            String userMessage,
            String refEntity,
            boolean capitalizeFirst,
            List<ReciprocalRankFusion.FusedChunk> fusedChunks
    ) {
        if (fusedChunks == null || fusedChunks.isEmpty()) {
            return null;
        }
        String normMsg = VietnameseQueryNormalizer.normalizeForSearch(userMessage);
        String normRef = VietnameseQueryNormalizer.normalizeForSearch(refEntity);
        Set<String> stopWords = Set.of(
                "dạ", "cho", "hỏi", "mình", "tôi", "em", "chị", "anh", "shop", "spa", "bên",
                "có", "không", "ko", "k", "là", "gì", "j", "sao", "vậy", "thế", "nào", "ntn",
                "ạ", "nhỉ", "hả", "với", "và", "hay", "hoặc", "của", "trong", "khi", "đi",
                "làm", "dịch", "vụ", "chính", "sách", "chi", "tiết", "cần", "phải", "được"
        );
        Set<String> queryTokens = new LinkedHashSet<>();
        for (String tok : (normMsg + " " + normRef).split("\\s+")) {
            String clean = tok.replaceAll("[^\\p{L}\\d]", "").strip();
            if (clean.length() >= 2 && !stopWords.contains(clean)) {
                queryTokens.add(clean);
            }
        }
        if (queryTokens.isEmpty()) {
            return null;
        }

        String bestAnswer = null;
        int bestScore = 0;

        for (ReciprocalRankFusion.FusedChunk fc : fusedChunks) {
            if (fc == null || fc.chunk() == null || fc.chunk().text() == null) continue;
            String[] lines = fc.chunk().text().split("\\r?\\n");
            for (String line : lines) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.contains("---") || !trimmed.contains("|")) continue;
                List<String> cells = new ArrayList<>();
                for (String c : trimmed.split("\\|")) {
                    String s = c.strip();
                    if (!s.isEmpty()) {
                        cells.add(s);
                    }
                }
                if (cells.size() < 2) continue;
                String qCell = cells.size() >= 3 ? cells.get(cells.size() - 2) : cells.get(0);
                String aCell = cells.get(cells.size() - 1);
                String qLower = Normalizer.normalize(qCell, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
                String aLower = Normalizer.normalize(aCell, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
                if (qLower.equals("câu hỏi") || aLower.equals("trả lời") || aCell.length() < 15) continue;
                // Bỏ qua dòng bảng giá dịch vụ (có cột giá VNĐ)
                if (trimmed.toLowerCase(Locale.ROOT).contains("vnđ") || trimmed.toLowerCase(Locale.ROOT).contains("mã gói")) {
                    continue;
                }

                int score = 0;
                for (String qt : queryTokens) {
                    if (qLower.contains(qt)) {
                        score += 3;
                    } else if (aLower.contains(qt)) {
                        score += 1;
                    }
                }
                if (score > bestScore && score >= 3) {
                    bestScore = score;
                    bestAnswer = aCell.strip();
                }
            }
        }

        if (bestAnswer == null || bestAnswer.isBlank()) {
            return null;
        }
        String cleanedAnswer = bestAnswer
                .replace("✨", "")
                .replaceAll("(?isU)^dạ[,\\s]+", "")
                .strip();
        // Giới hạn tối đa 2 câu, <= 40 từ để đảm bảo hợp đồng bong bóng
        String[] sentences = cleanedAnswer.split("(?<=[.!?])\\s+");
        String concise = sentences.length >= 2 ? (sentences[0] + " " + sentences[1]).strip() : cleanedAnswer;
        String[] words = concise.split("\\s+");
        if (words.length > 38 && sentences.length >= 1) {
            concise = sentences[0].strip();
        }
        if (concise.endsWith(".")) {
            concise = concise.substring(0, concise.length() - 1).strip();
        }
        if (!concise.toLowerCase(Locale.ROOT).endsWith("ạ")) {
            concise = concise + " ạ.";
        } else {
            concise = concise + ".";
        }
        if (!capitalizeFirst && !concise.isEmpty()) {
            concise = Character.toLowerCase(concise.charAt(0)) + concise.substring(1);
        } else if (capitalizeFirst && !concise.isEmpty()) {
            concise = Character.toUpperCase(concise.charAt(0)) + concise.substring(1);
        }
        return concise;
    }

    private static String extractGroundedTeaLabelFromChunks(List<ReciprocalRankFusion.FusedChunk> fusedChunks) {
        if (fusedChunks == null || fusedChunks.isEmpty()) {
            return null;
        }
        Pattern teaPat = Pattern.compile("(?isU)\\b(trà\\s+[\\p{L}]+\\s+[\\p{L}]+)\\b");
        for (ReciprocalRankFusion.FusedChunk fc : fusedChunks) {
            if (fc == null || fc.chunk() == null || fc.chunk().text() == null) {
                continue;
            }
            String norm = Normalizer.normalize(fc.chunk().text(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
            Matcher m = teaPat.matcher(norm);
            if (m.find()) {
                return m.group(1).replaceAll("\\s+", " ").strip();
            }
        }
        return null;
    }
}
