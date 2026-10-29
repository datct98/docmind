package com.example.docmind.service.bot;

import com.docmind.guardrail.BubbleCapHook;
import com.docmind.guardrail.ReplyContractValidator;
import com.example.docmind.dto.LearnedRuleDto;
import com.example.docmind.dto.bot.AgentSkillDto;
import com.example.docmind.dto.bot.CustomerInsightDto;
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
            + "để\\s+(?:tối\\s+)?(?:về\\s+)?(?:em\\s+|mình\\s+|anh\\s+|chị\\s+|tôi\\s+|t\\s+)?(?:xem|tính|nghĩ|suy\\s+nghĩ|cân\\s+nhắc|hỏi|bàn|tham\\s+khảo)(?:\\s+lại|\\s+thêm|\\s+trước)?(?:\\s+đã|\\s+nhé|\\s+nha|\\s+ạ)?|"
            + "(?:em\\s+|mình\\s+|anh\\s+|chị\\s+|tôi\\s+|t\\s+)?(?:xem|tính|nghĩ|suy\\s+nghĩ|cân\\s+nhắc|tham\\s+khảo)(?:\\s+lại|\\s+thêm)?\\s+(?:đã|trước\\s+đã|thêm\\s+đã)(?:\\s+nhé|\\s+nha|\\s+ạ|\\s+em)?|"
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
            + "(?:một|1|nửa|cả|\\d+)\\s*(?:ngày\\s+công|ngày\\s+lương|tháng\\s+lương)\\s+của\\s+(?:t|tôi|mình|em|chị|anh)"
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

    private static final Pattern POSITIVE_TRIAL_INTEREST_CLEAN_PATTERN = Pattern.compile(
            "(?isU).*\\b(nghe\\s+(?:cũng\\s+)?ổn|ổn\\s+(?:hơn\\s+)?(?:đó|đấy|nhỉ|phết|ạ)|nghe\\s+hợp\\s+lý|nghe\\s+được\\s+(?:đó|đấy)|thử\\s+xem|đi\\s+thử\\s+1\\s+buổi|thử\\s+1\\s+buổi)\\b.*"
    );

    private static final Pattern BARE_ACKNOWLEDGMENT_CLEAN_PATTERN = Pattern.compile(
            "(?isU)^\\s*(?:dạ\\s+)?(?:ok|oke|okay|okie|okela|ừ|ừa|ừm|uk|uki|ukm|uh|uhm|vâng|dạ|cảm\\s+ơn|thanks|thank\\s+you|tks)"
            + "(?:\\s+(?:em|chị|anh|bạn|shop|mình|nhé|nha|ạ|nhé\\s+em|nha\\s+em|rồi|được\\s+rồi|cảm\\s+ơn|cảm\\s+ơn\\s+em))*\\s*[.!~]*\\s*$"
    );

    private static final Pattern PROACTIVE_BOOKING_OR_PACKAGE_SELECTION_CLEAN_PATTERN = Pattern.compile(
            "(?isU).*\\b("
            + "hôm\\s+nay|ngày\\s+mai|chiều\\s+nay|tối\\s+nay|sáng\\s+mai|chiều\\s+mai|sáng\\s+nay|trưa\\s+nay|lát\\s+nữa|tí\\s+nữa|cuối\\s+tuần|thứ\\s+\\d|chủ\\s+nhật|\\d{1,2}\\s*(?:h|giờ|g\\b)|"
            + "(?:đặt|giữ|book|xếp)\\s+(?:lịch\\s+)?giúp\\s+(?:mình|chị|anh|em|tôi)|"
            + "đặt\\s+lịch|giữ\\s+lịch|giữ\\s+chỗ|book\\s+lịch|chốt\\s+(?:gói|lịch)|đăng\\s+ký\\s+(?:gói|lịch|thử)|"
            + "thử\\s+(?:gói|1\\s+buổi|trước|xem|lần)|trải\\s+nghiệm\\s+(?:thử|gói|1\\s+buổi|trước)|"
            + "cho\\s+(?:mình|chị|anh|tôi)\\s+(?:thử|đặt|lấy|chốt|đăng\\s+ký|làm\\s+gói)|"
            + "(?:mình|chị|anh|tôi)\\s+(?:qua|ghé|đến|sang|chọn\\s+gói|lấy\\s+gói)|qua\\s+(?:làm|thử|trải\\s+nghiệm)|"
            + "(?:ok|oke|okay|ừ|uk|vâng|dạ|vậy)\\b.*\\b(?:\\d{2,3}\\s*k\\b|\\d{2,3}\\s*phút\\b|ns0\\d|ds0\\d|gói\\s+(?:dưỡng\\s+sinh|massage|cơ\\s+bản|chuyên\\s+sâu))"
            + ")\\b.*"
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
            "(?isU).*\\b(thử\\s+trải\\s+nghiệm|trải\\s+nghiệm\\s+(?:trước|thử)\\s+1\\s+(?:buổi|lần)|"
            + "khung\\s+giờ\\s+nào|mấy\\s+giờ|giờ\\s+nào\\s+(?:để\\s+em|tiện|ạ|nhỉ)|"
            + "hôm\\s+nay\\s+(?:hay|hoặc)\\s+ngày\\s+mai|tiện\\s+ghé)\\b.*"
    );

    private LlmClient llmClient;
    private AgentBrainService agentBrainService;
    private AgentSkillService agentSkillService;
    private volatile ThinkingModePolicy.TurnTelemetry lastTurnTelemetry;

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

    public void setLlmClient(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public void setAgentBrainService(AgentBrainService agentBrainService) {
        this.agentBrainService = agentBrainService;
    }

    public void setAgentSkillService(AgentSkillService agentSkillService) {
        this.agentSkillService = agentSkillService;
    }

    public ThinkingModePolicy.TurnTelemetry getLastTurnTelemetry() {
        return lastTurnTelemetry;
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
                if (!SenderEpochRegistry.checkThreadEpochStillCurrent(
                        request != null ? request.pageId() : null,
                        request != null ? request.senderId() : null,
                        SenderEpochRegistry.Checkpoint.BEFORE_STATE_AND_LEDGER_COMMIT)) {
                    return buildSupersededAbortedResponse(extractedLead, customerInsight);
                }
                String introText = String.join("\n\n", introBubbles.stream().map(FanpageBotResponse.ReplyBubble::text).toList());
                String ledgerKeyIntro = SentBubbleLedger.key(request.pageId(), request.senderId(), request.conversationId());
                String ledgerTurnIdIntro = SentBubbleLedger.stableUserTurnId(ledgerKeyIntro, mergedMessage);
                SentBubbleLedger.recordCustomerTurn(ledgerKeyIntro, ledgerTurnIdIntro, mergedMessage);
                SentBubbleLedger.recordAssistantTurn(
                        ledgerKeyIntro,
                        ledgerTurnIdIntro + ":a",
                        introBubbles.stream().map(FanpageBotResponse.ReplyBubble::text).toList(),
                        SentBubbleLedger.DeliveryStatus.DELIVERED,
                        List.of()
                );
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
                                safeConv.preferredRegion() != null ? safeConv.preferredRegion() : "HN");

        List<String> knownSymptoms = safeConv.knownSymptoms() != null ? safeConv.knownSymptoms() : List.of();
        List<String> knownBodyAreas = ScopeMatcher.mergeScopes(
                mergedMessage,
                safeConv.knownBodyAreas() != null ? safeConv.knownBodyAreas() : List.of(),
                allCatalog,
                effectivePersonaConfig
        );
        String statedContextOrTiming = resolveStatedContextOrTiming(safeConv, safePrior, safeHistory, mergedMessage, isMetaOrChallenge);
        int discoveryQuestionCount = resolveDiscoveryQuestionCount(safePrior, safeHistory);
        boolean conditionClarified = safeConv.conditionClarified()
                || discoveryQuestionCount >= 1
                || !"chưa có".equals(statedContextOrTiming);
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

        CleanAgentCatalogAndPromptBuilder.TieredCatalogResult tieredCatalog =
                CleanAgentCatalogAndPromptBuilder.rankAndTierCatalogForCleanAgent(
                        allCatalog,
                        knownSymptoms,
                        knownBodyAreas,
                        safeConv.rejectedPackages(),
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
                && "LUKEWARM_POST_PRICE_QUOTE_EVALUATION".equals(shortUtteranceRes.decodedMeaning());

        if (brainDecodedPriceHesitation || brainDecodedPoliteDeferral) {
            isHesitation = true;
        }

        boolean isBareAck = !isMetaOrChallenge
                && !hasPriceObjectionKeyword
                && !isHesitation
                && isBareAcknowledgmentMessage(mergedMessage);
        boolean hasProactiveBookingInfo = !isMetaOrChallenge
                && !hasPriceObjectionKeyword
                && !isHesitation
                && hasProactiveBookingOrPackageSelectionInMessage(mergedMessage);
        boolean isBookingAlreadyConfirmed = "CONFIRMED".equalsIgnoreCase(safeConv.bookingStatus())
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

        String replyToMid = request != null && request.replyToMid() != null ? request.replyToMid().strip() : "";
        String quotedMessageText = request != null && request.quotedMessageText() != null ? request.quotedMessageText().strip() : "";
        if (quotedMessageText.isEmpty() && !replyToMid.isEmpty() && request != null) {
            String lookedUpQuoted = SentBubbleLedger.lookupMessageByMid(request.pageId(), request.senderId(), replyToMid);
            quotedMessageText = lookedUpQuoted != null ? lookedUpQuoted.strip() : "";
        }
        boolean hasReplyToContext = !replyToMid.isEmpty() || !quotedMessageText.isEmpty();

        boolean isOperationalQuestion = isOperationalOrLogisticsQuestion(mergedMessage);
        boolean isDetailOrUnitQuestion = isAckForOfferedDetails
                || brainDecodedOperationalAck
                || isOperationalQuestion
                || SentimentIntentLayer.isDetailOrFaqQuestion(mergedMessage, safeConv)
                || isNormalPriceOrDetailInquiryMessage(mergedMessage)
                || isPromoOrDiscountInquiryMessage(mergedMessage)
                || (hasReplyToContext && !hasProactiveBookingInfo && !isBareAck && !hasPriceObjectionKeyword && !isHesitation)
                || (mergedMessage != null && mergedMessage.toLowerCase(Locale.ROOT).matches("(?isU).*\\b(?:1|một)\\s+buổi\\s+(?:à|hả|phải\\s+không|đúng\\s+không|hay\\s+sao)\\b.*"));

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
                        || "COLLECTING_INFO".equals(safePrior.bookingStatus())
                        || "COLLECTING_VISIT_TIME".equals(safePrior.bookingStatus()))));
        if (packageInterestAccepted) {
            inPriceObjectionContext = false;
        }
        String bookingFunnelStatus = isBookingAlreadyConfirmed
                ? "CONFIRMED"
                : (packageInterestAccepted ? "COLLECTING_VISIT_TIME" : "NONE");

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

        boolean customerProactivelyPickedDownsell = packageInterestAccepted
                && tieredCatalog.economicalAlternatives() != null
                && !tieredCatalog.economicalAlternatives().isEmpty()
                && (CleanAgentCatalogAndPromptBuilder.customerExplicitlyAskedForEconomicalGroup(
                        mergedMessage, tieredCatalog.economicalAlternatives())
                    || hasPackagePriceMentionedInText(mergedMessage, tieredCatalog.economicalAlternatives())
                    || (hasPresentedDownsell && CleanAgentCatalogAndPromptBuilder.isAnyPackagePresentedInBotText(
                        prevBotTurnText, tieredCatalog.economicalAlternatives())));
        if (!isMetaOrChallenge
                && !isPoliteClosingAck
                && customerProactivelyPickedDownsell
                && tieredCatalog.economicalAlternatives() != null
                && !tieredCatalog.economicalAlternatives().isEmpty()) {
            PackageCatalogService.PackageSummary downsellPkg = tieredCatalog.economicalAlternatives().get(0);
            heldPackageDisplay = downsellPkg.canonicalLabel();
            heldPriceDisplay = downsellPkg.priceDisplay();
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
        PackageCatalogService.PackageSummary activeTurnPkg = (quotedReplyResolution.quotedPackage() != null)
                ? quotedReplyResolution.quotedPackage()
                : ((quotedReplyResolution.unresolvedReplyToMid() || quotedReplyResolution.ambiguousMultiplePackages())
                    ? null
                    : (explicitlyNamedPkgInTurn != null
                        ? explicitlyNamedPkgInTurn
                        : ((!isMetaOrChallenge
                            && !isPoliteClosingAck
                            && (isDownsellOrRepeatObjectionStage || isExplicitDownsell || isPriceContextHesitationTurn || customerProactivelyPickedDownsell)
                            && tieredCatalog.economicalAlternatives() != null
                            && !tieredCatalog.economicalAlternatives().isEmpty())
                            ? tieredCatalog.economicalAlternatives().get(0)
                            : CleanAgentCatalogAndPromptBuilder.findHeldOrTopPrimaryPackage(
                                    tieredCatalog, heldPackageDisplay, heldPriceDisplay))));

        CustomerInsightDto enrichedTurnInsight = customerInsight;
        if (agentBrainService != null && (enrichedTurnInsight == null || enrichedTurnInsight.careNotes() == null)) {
            enrichedTurnInsight = agentBrainService.enrichCustomerInsight(pageId, request.senderId(), enrichedTurnInsight, null);
        }

        List<AgentSkillDto> alignedActiveSkills = alignCleanAgentActiveSkills(
                activeSkills,
                candidateSkills,
                cleanStage,
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
                safeConv.visitTime() != null && !safeConv.visitTime().isBlank()
                        ? Map.of("visit_time", safeConv.visitTime().strip())
                        : Map.of(),
                quotedReplyResolution,
                pageId,
                appliedRules
        );

        ThinkingMode selectedThinkingMode = resolveCleanAgentThinkingMode(
                isChallengeAssumption,
                isStyleOrRepetitionComplaint,
                isMismatch,
                hasPriceObjectionKeyword || isPriceContextHesitationTurn,
                isPureDeferralOrBusyTurn || isPostBookingClosingAck,
                conditionClarified,
                packageInterestAccepted,
                isDetailOrUnitQuestion,
                knownSymptoms
        );
        ReasoningBudget cleanBudget = ReasoningBudget.forThinkingMode(selectedThinkingMode);
        String rawReply = null;
        int llmRegenerationCount = 0;
        long llmStartNs = System.nanoTime();
        try {
            if (llmClient != null) {
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
                        hasHigherPriorityNonObjectionIntent
                )
        );
        for (String replyGuardViolation : CleanAgentOutputRefereeAndSanitizer.checkReplyResolutionGuard(
                candidateBubbles,
                quotedReplyResolution,
                allCatalog,
                mergedMessage
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

        boolean repairApplied = false;
        boolean usedSafeHandoffFallback = false;
        List<String> telemetryErrors = new ArrayList<>();
        if (!allViolations.isEmpty()) {
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
                            candidateBubbles = repairedBubbles;
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
                            hasHigherPriorityNonObjectionIntent
                    )
            );
            for (String replyGuardViolation : CleanAgentOutputRefereeAndSanitizer.checkReplyResolutionGuard(
                    candidateBubbles,
                    quotedReplyResolution,
                    allCatalog,
                    mergedMessage
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
                        safeHonorific
                );
                repairApplied = true;
            } else if (needsQuotedPackageGuardRebuild) {
                log.warn("[CleanAgentMode] Post-repair still violates Quoted Package Focus Guard ({}) -> rebuilding deterministically from quoted PackageSummary without 3rd LLM call",
                        postRepairViolations);
                candidateBubbles = CleanAgentOutputRefereeAndSanitizer.buildDeterministicQuotedPackageReplyBubbles(
                        quotedReplyResolution.quotedPackage(),
                        mergedMessage,
                        selfPronoun,
                        safeHonorific
                );
                repairApplied = true;
            } else if (needsPromoFabricationGuardRebuild) {
                log.warn("[CleanAgentMode] Post-repair still has UNGROUNDED_PROMO_POLICY_FABRICATION ({}) -> rebuilding deterministically from active PackageSummary without 3rd LLM call",
                        postRepairViolations);
                candidateBubbles = CleanAgentOutputRefereeAndSanitizer.buildDeterministicQuotedPackageReplyBubbles(
                        activeTurnPkg,
                        mergedMessage,
                        selfPronoun,
                        safeHonorific
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
                                hasHigherPriorityNonObjectionIntent
                        )
                );
                for (String replyGuardViolation : CleanAgentOutputRefereeAndSanitizer.checkReplyResolutionGuard(
                        candidateBubbles,
                        quotedReplyResolution,
                        allCatalog,
                        mergedMessage
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
                                hasHigherPriorityNonObjectionIntent
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
                normText = ReplyContractValidator.normalizeDeclarativeQuestionMarks(normText);
                normText = BubbleCapHook.capitalizeFirstLetter(normText);
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
            fallbackText = ReplyContractValidator.normalizeDeclarativeQuestionMarks(fallbackText);
            fallbackText = BubbleCapHook.capitalizeFirstLetter(fallbackText);
            finalBubbles.add(new FanpageBotResponse.ReplyBubble(fallbackText, 0));
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
        if (!SenderEpochRegistry.checkThreadEpochStillCurrent(
                request != null ? request.pageId() : null,
                request != null ? request.senderId() : null,
                SenderEpochRegistry.Checkpoint.BEFORE_STATE_AND_LEDGER_COMMIT)) {
            return buildSupersededAbortedResponse(extractedLead, customerInsight);
        }
        String ledgerKey = SentBubbleLedger.key(request.pageId(), request.senderId(), request.conversationId());
        String ledgerUserTurnId = SentBubbleLedger.stableUserTurnId(ledgerKey, mergedMessage);
        SentBubbleLedger.recordCustomerTurn(ledgerKey, ledgerUserTurnId, mergedMessage);
        if (!finalBubbles.isEmpty()) {
            SentBubbleLedger.recordAssistantTurn(
                    ledgerKey,
                    ledgerUserTurnId + ":a",
                    finalBubbles.stream().map(FanpageBotResponse.ReplyBubble::text).toList(),
                    SentBubbleLedger.DeliveryStatus.DELIVERED,
                    List.of()
            );
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
        }

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
                + "quotedMessageTextFound={} quotedPackageId={} heldPackageId={} activePackageId={} presentedPackagesInHistory={} "
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

    public static boolean isNormalPriceOrDetailInquiryMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String trimmed = message.strip();
        if ((CLEAN_PRICE_OBJECTION_EXTRA_PATTERN.matcher(trimmed).matches()
                || EXPLICIT_DOWNSELL_OR_LOW_BUDGET_CLEAN_PATTERN.matcher(trimmed).matches()
                || METAPHORICAL_WAGE_COMPARISON_PRICE_OBJECTION_PATTERN.matcher(trimmed).matches())
                && !NON_PRICE_CAO_EXCLUSION_PATTERN.matcher(trimmed).matches()) {
            return false;
        }
        return NORMAL_PRICE_OR_DETAIL_INQUIRY_CLEAN_PATTERN.matcher(trimmed).matches()
                || PROMO_OR_DISCOUNT_INQUIRY_CLEAN_PATTERN.matcher(trimmed).matches();
    }

    public static boolean isConfirmedCustomerPriceObjection(String message) {
        return isConfirmedCustomerPriceObjection(message, true);
    }

    public static boolean isConfirmedCustomerPriceObjection(String message, boolean priceAlreadyDiscussedOrQuoted) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String trimmed = message.strip();
        if (NON_PRICE_CAO_EXCLUSION_PATTERN.matcher(trimmed).matches()
                && !trimmed.toLowerCase(Locale.ROOT).matches("(?isU).*\\b(giá|chi\\s+phí|tiền|gói|đắt|mắc|chát)\\b.*")) {
            return false;
        }
        if ((NORMAL_PRICE_OR_DETAIL_INQUIRY_CLEAN_PATTERN.matcher(trimmed).matches()
                || PROMO_OR_DISCOUNT_INQUIRY_CLEAN_PATTERN.matcher(trimmed).matches())
                && !CLEAN_PRICE_OBJECTION_EXTRA_PATTERN.matcher(trimmed).matches()
                && !METAPHORICAL_WAGE_COMPARISON_PRICE_OBJECTION_PATTERN.matcher(trimmed).matches()
                && !EXPLICIT_DOWNSELL_OR_LOW_BUDGET_CLEAN_PATTERN.matcher(trimmed).matches()) {
            return false;
        }
        boolean matchedWageComparison = METAPHORICAL_WAGE_COMPARISON_PRICE_OBJECTION_PATTERN.matcher(trimmed).matches();
        boolean matchedObjectionPattern = CLEAN_PRICE_OBJECTION_EXTRA_PATTERN.matcher(trimmed).matches()
                || matchedWageComparison
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
                if (ReplyContractValidator.PRICE_PATTERN.matcher(text).find()
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
        if (isHesitationOrDeferralMessage(trimmed)) {
            return false;
        }
        return PROACTIVE_BOOKING_OR_PACKAGE_SELECTION_CLEAN_PATTERN.matcher(trimmed).matches();
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
        return INVITE_TRIAL_OR_ASK_TIME_BOT_REPLY_CLEAN_PATTERN.matcher(botText.strip()).matches();
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
        if (safeSignals.contains("ACKNOWLEDGED_OPERATIONAL_INFO")) {
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
            if ("ACKNOWLEDGED_OPERATIONAL_INFO".equals(shortRes.decodedMeaning())) {
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
        Matcher pm = ReplyContractValidator.PRICE_PATTERN.matcher(message);
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
                "spa_trial_acceptance_booking",
                "spa_booking_advance_after_ack",
                "booking_soft_confirm",
                "sales_micro_commitment_close",
                "sales_appointment_hook_close"
        ).contains(codeLower)
                || codeLower.contains("booking")
                || codeLower.contains("closing")) {
            return true;
        }
        String nameLower = skill.name() != null ? skill.name().strip().toLowerCase(Locale.ROOT) : "";
        return nameLower.contains("chốt khung giờ")
                || nameLower.contains("đặt lịch")
                || nameLower.contains("giữ lịch")
                || nameLower.contains("trial acceptance");
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
        return "spa_graceful_exit_after_rejection".equals(codeLower)
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
        return "price_objection_soft_anchor".equals(codeLower)
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
        return "spa_condition_first_discovery".equals(codeLower)
                || "discovery_ask_one_need".equals(codeLower)
                || "spa_cautious_symptom_clarification".equals(codeLower)
                || codeLower.contains("discovery")
                || nameLower.contains("condition-first")
                || nameLower.contains("khám phá tình trạng");
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
                    || isGracefulExitSkill(s)
                    || (s.category() != null && ("SALES".equalsIgnoreCase(s.category()) || "CLOSING".equalsIgnoreCase(s.category()) || "DISCOVERY".equalsIgnoreCase(s.category()))));
            return current;
        }

        boolean isPriceObjectionStage = "STAGE_2_FIRST_PRICE_OBJECTION_HOLD_VALUE".equals(finalStage)
                || "STAGE_3_DOWNSELL_OR_REPEAT_OBJECTION".equals(finalStage);
        if (isPriceObjectionStage) {
            current.removeIf(s -> isGracefulExitSkill(s)
                    || isLukewarmMoodBoostSkill(s)
                    || isBookingOrClosingSkill(s)
                    || isDiscoveryOrSymptomSkill(s));
            if (current.stream().noneMatch(CleanAgentOrchestratorService::isPriceObjectionEmpathySkill)) {
                for (AgentSkillDto cand : sanitizedCandidates) {
                    if (isPriceObjectionEmpathySkill(cand)) {
                        current.add(0, cand);
                        break;
                    }
                }
            }
            if (current.stream().noneMatch(CleanAgentOrchestratorService::isPriceObjectionEmpathySkill)) {
                current.add(0, new AgentSkillDto(
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
            if (current.size() > 3) {
                return new ArrayList<>(current.subList(0, 3));
            }
            return current;
        }

        boolean isPositiveBookingStage = "STAGE_4_POSITIVE_TRIAL_OR_BOOKING".equals(finalStage);
        if (isPositiveBookingStage) {
            current.removeIf(s -> isGracefulExitSkill(s) || isLukewarmMoodBoostSkill(s));
            if (current.stream().noneMatch(CleanAgentOrchestratorService::isBookingOrClosingSkill)) {
                for (AgentSkillDto cand : sanitizedCandidates) {
                    if (isBookingOrClosingSkill(cand)) {
                        current.add(0, cand);
                        break;
                    }
                }
            }
            if (current.size() > 3) {
                return new ArrayList<>(current.subList(0, 3));
            }
            return current;
        }

        boolean isFinalHesitationOrPauseStage = (finalStage != null && !finalStage.isBlank())
                ? ("STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL".equals(finalStage)
                    || "STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE".equals(finalStage))
                : isHesitationOrPoliteClosing;

        if (!isFinalHesitationOrPauseStage && !isPostBookingClosingAck && !isPureDetailOrFaqTurn) {
            if (current.size() > 3) {
                return new ArrayList<>(current.subList(0, 3));
            }
            return current;
        }

        current.removeIf(s -> isBookingOrClosingSkill(s) || isLukewarmMoodBoostSkill(s));

        if (isFinalHesitationOrPauseStage) {
            for (AgentSkillDto cand : sanitizedCandidates) {
                if (isGracefulExitSkill(cand)) {
                    boolean alreadyIn = current.stream().anyMatch(s -> s != null && cand.code() != null && cand.code().equalsIgnoreCase(s.code()));
                    if (!alreadyIn) {
                        current.add(0, cand);
                    }
                    break;
                }
            }
            if (current.stream().noneMatch(CleanAgentOrchestratorService::isGracefulExitSkill)) {
                current.add(0, new AgentSkillDto(
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
            current.removeIf(s -> !isGracefulExitSkill(s) && (s.category() == null || !"OBJECTION".equalsIgnoreCase(s.category())));
        }

        if (current.size() > 3) {
            return new ArrayList<>(current.subList(0, 3));
        }
        return current;
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
        if (isOperationalQuestion) {
            signals.add("OPERATIONAL_OR_LOGISTICS_QUESTION");
        }
        if ((isDetailOrUnitQuestion || isPromoQuestion || isOperationalQuestion) && !isPriceObj) {
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

    public static int resolveDiscoveryQuestionCount(
            ConversationState priorState,
            List<FanpageBotRequest.ConversationTurn> history
    ) {
        int fromState = priorState != null ? Math.max(0, priorState.conditionAskedCount()) : 0;
        int fromHistory = 0;
        if (history != null) {
            for (FanpageBotRequest.ConversationTurn t : history) {
                if (t == null || t.content() == null || t.content().isBlank()) continue;
                if (!"assistant".equalsIgnoreCase(t.role()) && !"bot".equalsIgnoreCase(t.role())) continue;
                String low = t.content().toLowerCase(Locale.ROOT);
                if (low.contains("?") && (low.contains("lâu chưa")
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
                        || low.contains("ngồi lâu hay vận động"))) {
                    fromHistory++;
                }
            }
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
}
