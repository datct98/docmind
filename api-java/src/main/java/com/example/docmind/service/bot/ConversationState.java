package com.example.docmind.service.bot;

import com.example.docmind.dto.bot.FanpageBotRequest.ConversationTurn;
import com.example.docmind.dto.bot.FanpageBotResponse.ReplyBubble;
import com.example.docmind.service.retrieval.ReciprocalRankFusion;
import com.example.docmind.service.text.VietnameseQueryNormalizer;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Máy trạng thái hội thoại bán hàng đa ngành (Domain-Agnostic Conversation State v3.1.5.3).
 * <p>
 * Hỗ trợ chiến thuật xử lý chê đắt 2 bước của chuyên viên sale lâu năm:
 * <ul>
 *   <li>Chê đắt lần 1 (bình thường): Bảo vệ giá trị + mời thử 1 buổi 🥰, giữ nguyên {@code heldPackage}/{@code heldDuration},
 *       tuyệt đối KHÔNG đưa gói đang giữ vào {@code rejectedPackages}/{@code rejectedDurations}.</li>
 *   <li>Chê đắt lần 2 hoặc khách chủ động xin gói rẻ/ngắn hơn: Hạ đúng 1 bậc xuống gói thấp hơn còn hiệu lực trong tài liệu,
 *       đưa gói cũ vào {@code rejectedPackages}/{@code rejectedDurations}.</li>
 *   <li>Khách đồng ý thử ("Thử xem sao"): Chốt đúng {@code heldPackage} (nếu chưa downsell) hoặc {@code downsellPackage} (nếu đã downsell).</li>
 * </ul>
 */
public record ConversationState(
        boolean greetingSent,
        boolean introSent,
        ConversationStage stage,
        String lastIntent,
        String lastQuestion,
        String lastQuestionIntent,
        Set<String> usedQuestionIntents,
        String lastBotAction,
        KnownFacts known,
        boolean priceDiscussed,
        List<String> pricesShown,
        List<String> recommendedOptions,
        int objectionCount,
        int ackCount,
        int clarificationCount,
        int conditionAskedCount,
        int preferenceAskedCount,
        int clarifyServiceTypeCount,
        int clarifyDurationCount,
        int frustrationCount,
        int ambiguousCount,
        int recommendationCount,
        int priceRepeatGuardCount,
        boolean conditionClarified,
        boolean serviceFamilyKnown,
        boolean packageRecommended,
        boolean priceRequestedInSession,
        List<String> knownSymptoms,
        List<String> knownBodyAreas,
        String knownSeverity,
        String knownDurationOfProblem,
        String knownTrigger,
        String knownServiceFamily,
        String knownPreferredPressure,
        String knownRecommendedPackage,
        String lastQuotePackage,
        String lastSentiment,
        String lastEmotion,
        List<String> usedTemplates,
        boolean humanTakeover,
        boolean optedOut,
        // === v3.1.5.3 Veteran Sales Two-Step Objection & Safety Fields ===
        boolean downsellOffered,
        boolean trialInviteOffered,
        boolean explicitDownsellRequest,
        boolean packageRejectionDetected,
        String lastQuotedPackage,
        String lastQuotedDuration,
        BigDecimal lastQuotedPrice,
        String heldPackage,
        String heldDuration,
        String downsellPackage,
        String downsellDuration,
        BigDecimal downsellPrice,
        Set<String> rejectedDurations,
        Set<String> rejectedPackages,
        String objectionStage,
        String telemetrySubtype,
        boolean docsHasFeedback,
        boolean docsHasRelaxationBenefit,
        boolean docsHasPrivateRoom,
        boolean neurologicalRisk,
        // === v3.2.1-FINAL LLM-Native Guarded Runtime & Real HTML Catalog State Fields ===
        String preferredRegion,
        List<String> mentionedPackages,
        List<String> quotedPackages,
        BigDecimal heldPrice,
        int alternativeRequestCount,
        boolean customerCorrectionDetected,
        String rejectedWrongOffer,
        List<PackageCatalogService.PackageSummary> availablePackageCatalog,
        List<String> lastBotBubbleHashes,
        List<UnresolvedCustomerRequest> outstandingRequests,
        String lastAssistantMessage
) {

    public ConversationState(
            boolean greetingSent,
            boolean introSent,
            ConversationStage stage,
            String lastIntent,
            String lastQuestion,
            String lastQuestionIntent,
            Set<String> usedQuestionIntents,
            String lastBotAction,
            KnownFacts known,
            boolean priceDiscussed,
            List<String> pricesShown,
            List<String> recommendedOptions,
            int objectionCount,
            int ackCount,
            int clarificationCount,
            int conditionAskedCount,
            int preferenceAskedCount,
            int clarifyServiceTypeCount,
            int clarifyDurationCount,
            int frustrationCount,
            int ambiguousCount,
            int recommendationCount,
            int priceRepeatGuardCount,
            boolean conditionClarified,
            boolean serviceFamilyKnown,
            boolean packageRecommended,
            boolean priceRequestedInSession,
            List<String> knownSymptoms,
            List<String> knownBodyAreas,
            String knownSeverity,
            String knownDurationOfProblem,
            String knownTrigger,
            String knownServiceFamily,
            String knownPreferredPressure,
            String knownRecommendedPackage,
            String lastQuotePackage,
            String lastSentiment,
            String lastEmotion,
            List<String> usedTemplates,
            boolean humanTakeover,
            boolean optedOut,
            boolean downsellOffered,
            boolean trialInviteOffered,
            boolean explicitDownsellRequest,
            boolean packageRejectionDetected,
            String lastQuotedPackage,
            String lastQuotedDuration,
            BigDecimal lastQuotedPrice,
            String heldPackage,
            String heldDuration,
            String downsellPackage,
            String downsellDuration,
            BigDecimal downsellPrice,
            Set<String> rejectedDurations,
            Set<String> rejectedPackages,
            String objectionStage,
            String telemetrySubtype,
            boolean docsHasFeedback,
            boolean docsHasRelaxationBenefit,
            boolean docsHasPrivateRoom,
            boolean neurologicalRisk,
            String preferredRegion,
            List<String> mentionedPackages,
            List<String> quotedPackages,
            BigDecimal heldPrice,
            int alternativeRequestCount,
            boolean customerCorrectionDetected,
            String rejectedWrongOffer,
            List<PackageCatalogService.PackageSummary> availablePackageCatalog,
            List<String> lastBotBubbleHashes
    ) {
        this(
                greetingSent, introSent, stage, lastIntent, lastQuestion, lastQuestionIntent,
                usedQuestionIntents, lastBotAction, known, priceDiscussed, pricesShown, recommendedOptions,
                objectionCount, ackCount, clarificationCount, conditionAskedCount, preferenceAskedCount,
                clarifyServiceTypeCount, clarifyDurationCount, frustrationCount, ambiguousCount,
                recommendationCount, priceRepeatGuardCount, conditionClarified, serviceFamilyKnown,
                packageRecommended, priceRequestedInSession, knownSymptoms, knownBodyAreas,
                knownSeverity, knownDurationOfProblem, knownTrigger, knownServiceFamily,
                knownPreferredPressure, knownRecommendedPackage, lastQuotePackage, lastSentiment,
                lastEmotion, usedTemplates, humanTakeover, optedOut,
                downsellOffered, trialInviteOffered, explicitDownsellRequest, packageRejectionDetected,
                lastQuotedPackage, lastQuotedDuration, lastQuotedPrice, heldPackage, heldDuration,
                downsellPackage, downsellDuration, downsellPrice, rejectedDurations, rejectedPackages,
                objectionStage, telemetrySubtype, docsHasFeedback, docsHasRelaxationBenefit,
                docsHasPrivateRoom, neurologicalRisk, preferredRegion, mentionedPackages, quotedPackages,
                heldPrice, alternativeRequestCount, customerCorrectionDetected, rejectedWrongOffer,
                availablePackageCatalog, lastBotBubbleHashes, List.of(), null
        );
    }

    public int priceObjectionCount() {
        return objectionCount;
    }

    public boolean hasUnresolvedCustomerRequest() {
        return latestUnresolvedRequest() != null;
    }

    public UnresolvedCustomerRequest latestUnresolvedRequest() {
        if (outstandingRequests == null || outstandingRequests.isEmpty()) {
            return null;
        }
        for (int i = outstandingRequests.size() - 1; i >= 0; i--) {
            UnresolvedCustomerRequest req = outstandingRequests.get(i);
            if (req != null && !req.resolved()) {
                return req;
            }
        }
        return null;
    }

    public UnresolvedCustomerRequest mostRecentUnresolvedRequest() {
        return latestUnresolvedRequest();
    }

    public UnresolvedCustomerRequest latestRequest() {
        if (outstandingRequests == null || outstandingRequests.isEmpty()) {
            return null;
        }
        return outstandingRequests.get(outstandingRequests.size() - 1);
    }

    /**
     * Biểu diễn tường minh và kiểm thử được cho câu hỏi / yêu cầu chi tiết dịch vụ của khách hàng
     * đang chờ giải đáp hoặc đã được giải đáp trong hội thoại.
     */
    public record UnresolvedCustomerRequest(
            String rawCustomerMessage,
            String semanticQuestion,
            String semanticIntent,
            String referencedEntity,
            String contextBotExcerpt,
            int turnIndex,
            boolean answeredByBot,
            boolean answerValidatedAndDelivered,
            int repeatOrChallengeCount,
            boolean customerChallengedAnswer,
            boolean resolved,
            boolean requiresHumanAssistance
    ) {
        public String canonicalQuestion() {
            return semanticQuestion;
        }

        public UnresolvedCustomerRequest withBotAttempt(boolean answered, boolean validatedAndDelivered) {
            boolean nowResolved = answered && validatedAndDelivered;
            return new UnresolvedCustomerRequest(
                    rawCustomerMessage,
                    semanticQuestion,
                    semanticIntent,
                    referencedEntity,
                    contextBotExcerpt,
                    turnIndex,
                    answered,
                    validatedAndDelivered,
                    repeatOrChallengeCount,
                    customerChallengedAnswer,
                    nowResolved,
                    requiresHumanAssistance && !nowResolved
            );
        }

        public UnresolvedCustomerRequest withCustomerRepeatOrChallenge(
                String latestCustomerMsg,
                boolean challenged,
                boolean needsHuman
        ) {
            return new UnresolvedCustomerRequest(
                    latestCustomerMsg != null && !latestCustomerMsg.isBlank() ? latestCustomerMsg : rawCustomerMessage,
                    semanticQuestion,
                    semanticIntent,
                    referencedEntity,
                    contextBotExcerpt,
                    turnIndex,
                    answeredByBot,
                    answerValidatedAndDelivered,
                    repeatOrChallengeCount + 1,
                    customerChallengedAnswer || challenged,
                    false,
                    requiresHumanAssistance || needsHuman
            );
        }
    }

    public enum ConversationStage {
        OPEN,
        NEW,
        GREETING,
        DISCOVERY,
        CONDITION_CLARIFIED,
        RECOMMENDATION,
        PRICE_CLARIFY,
        PRICE_QUOTED,
        PRICE_ANSWERED,
        OBJECTION,
        COMPARISON,
        CHOICE_PENDING,
        BOOKING,
        BOOKING_INTENT,
        HANDOFF,
        HARD_HANDOFF
    }

    public record KnownFacts(
            String itemInterest,
            String customerProblemOrNeed,
            String preferenceAttribute,
            String preferredOption,
            String preferredTime
    ) {
        public KnownFacts {
            if (customerProblemOrNeed != null && isServiceOrModalityKeyword(customerProblemOrNeed)) {
                if (itemInterest == null || itemInterest.isBlank()) {
                    itemInterest = customerProblemOrNeed;
                }
                customerProblemOrNeed = null;
            }
        }

        public static KnownFacts empty() {
            return new KnownFacts(null, null, null, null, null);
        }

        public boolean hasPreferenceAttribute() {
            return preferenceAttribute != null && !preferenceAttribute.isBlank();
        }

        public boolean hasCustomerProblemOrNeed() {
            return customerProblemOrNeed != null && !customerProblemOrNeed.isBlank();
        }
    }

    private record LastTurnPreStateSnapshot(
            int historySize,
            String normalizedUserMessage,
            ConversationState preTurnState,
            ConversationState postTurnState
    ) {
    }

    private static final Map<String, ConversationState> SESSION_STORE = new ConcurrentHashMap<>();
    private static final Map<String, Object> WEBHOOK_EVENT_DEDUP_STORE = new ConcurrentHashMap<>();
    private static final Map<String, LastTurnPreStateSnapshot> LAST_TURN_PRE_STATE_STORE = new ConcurrentHashMap<>();

    private static final Pattern PRICE_NUMBER_PATTERN = Pattern.compile(
            "(?iU)\\b(\\d{2,3}(?:\\.\\d{3})+|\\d{2,4})\\s*(?:k\\b|đ\\b|vnđ\\b|vnd\\b)"
    );
    private static final Pattern OPTION_UNIT_PATTERN = Pattern.compile(
            "(?iU)\\b(\\d{2,3}\\s*(?:phút|p\\b|ml\\b|g\\b|buổi|tháng|ngày)|size\\s*[smlx]{1,3})\\b"
    );
    private static final Pattern DURATION_ONLY_PATTERN = Pattern.compile(
            "(?iU)\\b(15|20|30|40|45|50|60|70|75|80|90|100|120)\\s*(?:phút|phut|p)\\b"
    );
    private static final Pattern PREFERENCE_PATTERN = Pattern.compile(
            "(?iU)\\b(lực\\s+nhẹ|lực\\s+vừa|lực\\s+mạnh|nhẹ\\s+nhàng|ấn\\s+sâu|mạnh\\s+tay|"
            + "form\\s+rộng|form\\s+ôm|size\\s+[smlx]{1,3}|da\\s+dầu|da\\s+khô|da\\s+nhạy\\s+cảm|"
            + "làm\\s+nhanh|thư\\s+giãn\\s+lâu|chi\\s+phí\\s+gọn|tiết\\s+kiệm)\\b"
    );
    private static final String ATOMIC_SCOPE_REGEX =
            "(?:cổ\\s+vai\\s+" + "gáy|vai\\s+" + "gáy|cổ\\s+vai|cổ\\s+" + "gáy|"
            + "cổ|vai|gáy|thắt\\s+lưng|lưng|eo|chân\\s+răng|bắp\\s+chân|bàn\\s+chân|chân|cánh\\s+tay|bàn\\s+tay|tay|"
            + "đầu\\s+gối|khớp\\s+gối|gối|(?<!lần\\s|buổi\\s|bắt\\s|từ\\s|ban\\s)đầu|cơ|tai|toàn\\s+thân|body|"
            + "răng\\s+hàm|răng\\s+khôn|răng\\s+cửa|răng|nướu|(?<!quyền\\s|tiện\\s|thuận\\s|có\\s)lợi(?!\\s+ích)|tủy|hàm|"
            + "da\\s+mặt|da\\s+đầu|da|mụn|nám|tóc|sofa|ghế|đệm|nệm|giường|tủ)";
    private static final String ATOMIC_MODIFIER_REGEX =
            "(?:đau\\s+mỏi|mỏi\\s+nhức|đau\\s+nhức|tê\\s+rần|tê\\s+bì|tê\\s+buốt|ê\\s+buốt|căng\\s+cứng|chảy\\s+máu|ố\\s+vàng|xỉn\\s+màu|"
            + "đau|mỏi|nhức|nhói|tê|căng|cứng|sưng|viêm|buốt|ê|sâu|mẻ)";
    private static final Pattern SYMPTOM_TOKEN_PATTERN = Pattern.compile(
            "(?iU)\\b(đau\\s+đầu\\s+dữ\\s+dội|đau\\s+đầu|đau\\s+cơ|"
            + "(?:đau|mỏi|nhức)\\s+(?:cổ\\s+vai\\s+" + "gáy|vai\\s+" + "gáy|cổ\\s+vai)|"
            + "đau|mỏi|nhức|nhói|tê\\s+chân\\s+tay|tê\\s+tay|tê\\s+chân|tê\\s+rần|tê\\s+bì|tê|"
            + "yếu\\s+tay|yếu\\s+chân|mất\\s+cảm\\s+giác|chóng\\s+mặt|ù\\s+tai|hoa\\s+mắt|"
            + "căng\\s+cứng|căng\\s+cổ|căng|cứng|sưng|viêm|ê\\s+buốt|buốt|chảy\\s+máu|ố\\s+vàng|xỉn\\s+màu|sâu\\s+răng|"
            + "mệt\\s+mỏi|mệt|stress|căng\\s+thẳng|mất\\s+ngủ|khó\\s+ngủ|nặng\\s+đầu|khó\\s+chịu)\\b"
    );
    private static final Pattern BODY_AREA_TOKEN_PATTERN = Pattern.compile(
            "(?iU)\\b(" + ATOMIC_SCOPE_REGEX + ")\\b"
    );
    private static final Pattern MULTI_ENTITY_NEED_PATTERN = Pattern.compile(
            "(?iU)\\b(?:"
            + "(?<fwdMod>" + ATOMIC_MODIFIER_REGEX + ")\\s+(?:vùng\\s+|ở\\s+|phần\\s+|khớp\\s+|cả\\s+|hai\\s+|2\\s+|một\\s+|1\\s+|bên\\s+)*(?<fwdScope>" + ATOMIC_SCOPE_REGEX + ")"
            + "(?:\\s*(?:,|và)\\s*(?:vùng\\s+|ở\\s+|phần\\s+|khớp\\s+|cả\\s+|hai\\s+|2\\s+|một\\s+|1\\s+|bên\\s+)*(?<fwdScopeChain>" + ATOMIC_SCOPE_REGEX + ")(?!\\s+(?:bị\\s+|đang\\s+|hơi\\s+|rất\\s+|hay\\s+)?" + ATOMIC_MODIFIER_REGEX + "))?"
            + "(?:\\s+(?<fwdSide>2\\s+bên|hai\\s+bên))?"
            + "|(?<invScope>" + ATOMIC_SCOPE_REGEX + ")\\s+(?:bị\\s+|đang\\s+|hơi\\s+|rất\\s+|hay\\s+)?(?<invMod>" + ATOMIC_MODIFIER_REGEX + ")(?!\\s+(?:vùng\\s+|ở\\s+|phần\\s+|khớp\\s+|cả\\s+|hai\\s+|2\\s+)?" + ATOMIC_SCOPE_REGEX + ")"
            + "|(?<standalone>mất\\s+ngủ|khó\\s+ngủ|nặng\\s+đầu|chóng\\s+mặt|hoa\\s+mắt|ù\\s+tai|căng\\s+thẳng|stress|tê\\s+rần|tê\\s+bì)"
            + ")\\b"
    );
    private static final Pattern SEVERITY_TOKEN_PATTERN = Pattern.compile(
            "(?iU)\\b(thi\\s+thoảng|thỉnh\\s+thoảng|thi\\s+thoải|đôi\\s+khi|nhói\\s+vai\\s+2\\s+bên|2\\s+bên|hai\\s+bên|"
            + "nhiều\\s+lắm|rất\\s+mỏi|rất\\s+đau|khá\\s+nặng|dữ\\s+dội|nhiều|nặng|nhẹ)\\b"
    );
    private static final Pattern PROBLEM_DURATION_TOKEN_PATTERN = Pattern.compile(
            "(?iU)\\b(lâu\\s+rồi|lâu\\s+năm|mới\\s+gần\\s+đây|mới\\s+bị|dạo\\s+này|thường\\s+xuyên|liên\\s+tục|mấy\\s+ngày|\\d+\\s*(?:ngày|tuần|tháng|năm))\\b"
    );
    private static final Pattern TRIGGER_TOKEN_PATTERN = Pattern.compile(
            "(?iU)\\b(ngồi\\s+lâu|ngồi\\s+máy\\s+tính|máy\\s+tính|văn\\s+phòng|vận\\s+động|sai\\s+tư\\s+thế|mang\\s+vác|tập\\s+gym|chơi\\s+thể\\s+thao)\\b"
    );
    private static final Pattern TRIAL_INVITE_BOT_PATTERN = Pattern.compile(
            "(?isU).*(?:thử\\s+1\\s+buổi|thử\\s+một\\s+buổi|trải\\s+nghiệm\\s+lẻ\\s+1\\s+buổi|thử\\s+xem\\s+sao|cảm\\s+nhận\\s+tay\\s+nghề|cảm\\s+nhận\\s+trực\\s+tiếp).*"
    );
    private static final Map<String, String> CANONICAL_TO_DISPLAY_CACHE = new ConcurrentHashMap<>();

    public List<String> quotedPrices() {
        return pricesShown;
    }

    public String lastOfferedPackageId() {
        if (downsellOffered && downsellPackage != null && !downsellPackage.isBlank()) {
            return downsellPackage;
        }
        return heldPackage != null && !heldPackage.isBlank() ? heldPackage : lastQuotedPackage;
    }

    public String lastOfferedDuration() {
        if (downsellOffered && downsellDuration != null && !downsellDuration.isBlank()) {
            return downsellDuration;
        }
        return heldDuration != null && !heldDuration.isBlank() ? heldDuration : lastQuotedDuration;
    }

    public String lastOfferedPackageName() {
        String id = lastOfferedPackageId();
        String dur = lastOfferedDuration();
        if (id != null && !id.isBlank()) {
            return toDisplayPackageName(id, dur);
        }
        return knownRecommendedPackage;
    }

    public BigDecimal lastOfferedPriceVnd() {
        if (downsellOffered && downsellPrice != null) {
            return downsellPrice;
        }
        return heldPrice != null ? heldPrice : lastQuotedPrice;
    }

    public String lastOfferedPrice() {
        BigDecimal bd = lastOfferedPriceVnd();
        if (bd != null && bd.compareTo(BigDecimal.ZERO) > 0) {
            long val = bd.longValue();
            if (val >= 1000 && val % 1000 == 0) {
                return (val / 1000) + "k";
            }
            return bd.toPlainString();
        }
        if (pricesShown != null && !pricesShown.isEmpty()) {
            return pricesShown.get(pricesShown.size() - 1);
        }
        return null;
    }

    public String offerStage() {
        return objectionStage != null ? objectionStage : "NONE";
    }

    public int offerTurnId() {
        return pricesShown != null ? pricesShown.size() : 0;
    }

    public static void clearSessionCache() {
        SESSION_STORE.clear();
        WEBHOOK_EVENT_DEDUP_STORE.clear();
        LAST_TURN_PRE_STATE_STORE.clear();
    }

    public static ConversationState getSessionState(UUID pageId, String senderId) {
        return getSessionState(pageId, senderId, null);
    }

    public static ConversationState getSessionState(UUID pageId, String senderId, String conversationId) {
        String key = buildSessionKey(pageId, senderId, conversationId);
        return key != null ? SESSION_STORE.get(key) : null;
    }

    public static void resetSessionState(UUID pageId, String senderId, String conversationId) {
        String key = buildSessionKey(pageId, senderId, conversationId);
        if (key != null) {
            SESSION_STORE.remove(key);
            LAST_TURN_PRE_STATE_STORE.remove(key);
        }
        String legacyKey = buildSessionKey(pageId, senderId, null);
        if (legacyKey != null) {
            SESSION_STORE.remove(legacyKey);
            LAST_TURN_PRE_STATE_STORE.remove(legacyKey);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T getCachedWebhookResponse(String eventKey) {
        if (eventKey == null || eventKey.isBlank()) {
            return null;
        }
        return (T) WEBHOOK_EVENT_DEDUP_STORE.get(eventKey);
    }

    public static void cacheWebhookResponse(String eventKey, Object response) {
        if (eventKey == null || eventKey.isBlank() || response == null) {
            return;
        }
        if (WEBHOOK_EVENT_DEDUP_STORE.size() > 2000) {
            WEBHOOK_EVENT_DEDUP_STORE.clear();
        }
        WEBHOOK_EVENT_DEDUP_STORE.put(eventKey, response);
    }

    public static String buildWebhookEventKey(
            UUID pageId,
            String senderId,
            String message,
            List<ConversationTurn> history,
            String explicitEventId
    ) {
        return buildWebhookEventKey(pageId, senderId, null, false, message, history, explicitEventId);
    }

    public static String buildWebhookEventKey(
            UUID pageId,
            String senderId,
            String conversationId,
            boolean resetSession,
            String message,
            List<ConversationTurn> history,
            String explicitEventId
    ) {
        if (resetSession) {
            return null;
        }
        String pageStr = pageId != null ? pageId.toString() : "default";
        String senderStr = senderId != null && !senderId.isBlank() ? senderId.strip() : "";
        String convStr = conversationId != null && !conversationId.isBlank() ? "::conv::" + conversationId.strip() : "";
        if (explicitEventId != null && !explicitEventId.isBlank()) {
            return pageStr + "::" + senderStr + convStr + "::mid::" + explicitEventId.strip();
        }
        if ((senderStr.isEmpty() && convStr.isEmpty()) || message == null || message.isBlank()) {
            return null;
        }
        StringBuilder histSig = new StringBuilder();
        if (history != null && !history.isEmpty()) {
            histSig.append(history.size());
            for (ConversationTurn t : history) {
                if (t != null) {
                    histSig.append('|')
                            .append(t.role() != null ? t.role() : "")
                            .append(':')
                            .append(t.content() != null ? t.content().hashCode() : 0);
                }
            }
        } else {
            ConversationState existing = getSessionState(pageId, senderId, conversationId);
            if (existing != null && existing.lastBotBubbleHashes() != null && !existing.lastBotBubbleHashes().isEmpty()) {
                histSig.append("sess:").append(existing.lastBotBubbleHashes().size())
                        .append(':').append(existing.lastBotBubbleHashes().get(existing.lastBotBubbleHashes().size() - 1));
            } else {
                histSig.append("0");
            }
        }
        return pageStr + "::" + senderStr + convStr + "::" + histSig + "::" + message.strip();
    }

    public static ConversationState initial() {
        return new ConversationState(
                false,
                false,
                ConversationStage.OPEN,
                null,
                null,
                null,
                Set.of(),
                null,
                KnownFacts.empty(),
                false,
                List.of(),
                List.of(),
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                false,
                false,
                false,
                false,
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "NEUTRAL",
                "NONE",
                List.of(),
                false,
                false,
                false,
                false,
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Set.of(),
                Set.of(),
                "NONE",
                null,
                false,
                true,
                false,
                false,
                "HN",
                List.of(),
                List.of(),
                null,
                0,
                false,
                null,
                List.of(),
                List.of(),
                List.of(),
                null
        );
    }

    public static ConversationState hydrate(
            UUID pageId,
            String senderId,
            List<ConversationTurn> history,
            List<String> domainKeywords
    ) {
        return hydrate(pageId, senderId, history, domainKeywords, List.of());
    }

    /**
     * Khôi phục trạng thái hội thoại tuần tự theo thời gian từ {@code history} kết hợp {@code SESSION_STORE} và {@code fusedChunks}.
     */
    public static ConversationState hydrate(
            UUID pageId,
            String senderId,
            List<ConversationTurn> history,
            List<String> domainKeywords,
            List<ReciprocalRankFusion.FusedChunk> fusedChunks
    ) {
        return hydrateForTurn(pageId, senderId, null, false, null, history, domainKeywords, fusedChunks);
    }

    public static ConversationState hydrate(
            UUID pageId,
            String senderId,
            String conversationId,
            boolean resetSession,
            List<ConversationTurn> history,
            List<String> domainKeywords,
            List<ReciprocalRankFusion.FusedChunk> fusedChunks
    ) {
        return hydrateForTurn(pageId, senderId, conversationId, resetSession, null, history, domainKeywords, fusedChunks);
    }

    public static ConversationState hydrateForTurn(
            UUID pageId,
            String senderId,
            String currentMessage,
            List<ConversationTurn> history,
            List<String> domainKeywords,
            List<ReciprocalRankFusion.FusedChunk> fusedChunks
    ) {
        return hydrateForTurn(pageId, senderId, null, false, currentMessage, history, domainKeywords, fusedChunks);
    }

    public static ConversationState hydrateForTurn(
            UUID pageId,
            String senderId,
            String conversationId,
            boolean resetSession,
            String currentMessage,
            List<ConversationTurn> history,
            List<String> domainKeywords,
            List<ReciprocalRankFusion.FusedChunk> fusedChunks
    ) {
        String sessionKey = buildSessionKey(pageId, senderId, conversationId);
        boolean hasStableConversationId = conversationId != null && !conversationId.isBlank();
        boolean docsFeedback = detectDocsHasFeedback(fusedChunks);
        boolean docsRelaxation = detectDocsHasRelaxationBenefit(fusedChunks);
        boolean docsPrivateRoom = detectDocsHasPrivateRoom(fusedChunks);
        List<PackageCatalogService.PackageSummary> fullCatalog = PackageCatalogService.extractCatalog(fusedChunks, domainKeywords);

        if (resetSession && sessionKey != null) {
            SESSION_STORE.remove(sessionKey);
            LAST_TURN_PRE_STATE_STORE.remove(sessionKey);
        }

        if (history == null || history.isEmpty()) {
            if (!resetSession && hasStableConversationId && sessionKey != null) {
                ConversationState existingSession = SESSION_STORE.get(sessionKey);
                if (existingSession != null) {
                    String reg = existingSession.preferredRegion() != null ? existingSession.preferredRegion() : "HN";
                    List<PackageCatalogService.PackageSummary> regCat = PackageCatalogService.filterByRegion(fullCatalog, reg);
                    return existingSession
                            .withDocFlags(docsFeedback, docsRelaxation, docsPrivateRoom)
                            .withCatalogAndRegion(regCat, reg);
                }
            }
            if (!resetSession && !hasStableConversationId && sessionKey != null && currentMessage != null && !currentMessage.isBlank()) {
                LastTurnPreStateSnapshot snap = LAST_TURN_PRE_STATE_STORE.get(sessionKey);
                String normCurrent = VietnameseQueryNormalizer.normalize(currentMessage).toLowerCase(Locale.ROOT).strip();
                if (snap != null && snap.historySize() == 0 && normCurrent.equals(snap.normalizedUserMessage()) && snap.preTurnState() != null) {
                    List<PackageCatalogService.PackageSummary> regCat = PackageCatalogService.filterByRegion(fullCatalog, snap.preTurnState().preferredRegion());
                    return snap.preTurnState()
                            .withDocFlags(docsFeedback, docsRelaxation, docsPrivateRoom)
                            .withCatalogAndRegion(regCat, snap.preTurnState().preferredRegion());
                }
            }
            if (sessionKey != null) {
                SESSION_STORE.remove(sessionKey);
                LAST_TURN_PRE_STATE_STORE.remove(sessionKey);
            }
            List<PackageCatalogService.PackageSummary> hnCatalog = PackageCatalogService.filterByRegion(fullCatalog, "HN");
            return initial()
                    .withDocFlags(docsFeedback, docsRelaxation, docsPrivateRoom)
                    .withCatalogAndRegion(hnCatalog, "HN");
        }

        ConversationState cached = sessionKey != null ? SESSION_STORE.get(sessionKey) : null;
        if (sessionKey != null) {
            LastTurnPreStateSnapshot snap = LAST_TURN_PRE_STATE_STORE.get(sessionKey);
            if (snap != null && (snap.historySize() < 0 || snap.historySize() == history.size())) {
                if (currentMessage != null && !currentMessage.isBlank()
                        && VietnameseQueryNormalizer.normalize(currentMessage).toLowerCase(Locale.ROOT).strip().equals(snap.normalizedUserMessage())
                        && snap.preTurnState() != null) {
                    List<PackageCatalogService.PackageSummary> regCat = PackageCatalogService.filterByRegion(fullCatalog, snap.preTurnState().preferredRegion());
                    return snap.preTurnState()
                            .withDocFlags(docsFeedback, docsRelaxation, docsPrivateRoom)
                            .withCatalogAndRegion(regCat, snap.preTurnState().preferredRegion());
                }
                if (currentMessage == null || currentMessage.isBlank()) {
                    cached = snap.preTurnState();
                }
            }
        }
        boolean hasAssistantTurnInHistory = history.stream()
                .anyMatch(t -> t != null && t.role() != null
                        && ("assistant".equalsIgnoreCase(t.role().strip()) || "bot".equalsIgnoreCase(t.role().strip())));

        boolean greetingSent = !hasAssistantTurnInHistory && cached != null && cached.greetingSent();
        boolean introSent = !hasAssistantTurnInHistory && cached != null && cached.introSent();
        ConversationStage stage = !hasAssistantTurnInHistory && cached != null ? cached.stage() : ConversationStage.GREETING;
        String lastIntent = cached != null ? cached.lastIntent() : null;
        String lastQuestion = !hasAssistantTurnInHistory && cached != null ? cached.lastQuestion() : null;
        String lastQuestionIntent = cached != null ? cached.lastQuestionIntent() : null;
        Set<String> usedQuestionIntents = new LinkedHashSet<>();
        if (cached != null && cached.usedQuestionIntents() != null) {
            usedQuestionIntents.addAll(cached.usedQuestionIntents());
        }
        String lastBotAction = cached != null ? cached.lastBotAction() : null;
        String itemInterest = cached != null && cached.known() != null ? cached.known().itemInterest() : null;
        String problemOrNeed = cached != null && cached.known() != null ? cached.known().customerProblemOrNeed() : null;
        String preferenceAttr = cached != null && cached.known() != null ? cached.known().preferenceAttribute() : null;
        String preferredOption = cached != null && cached.known() != null ? cached.known().preferredOption() : null;
        String preferredTime = cached != null && cached.known() != null ? cached.known().preferredTime() : null;

        Set<String> pricesShown = new LinkedHashSet<>();
        if (!hasAssistantTurnInHistory && cached != null && cached.pricesShown() != null) {
            pricesShown.addAll(cached.pricesShown());
        }
        Set<String> recommendedOptions = new LinkedHashSet<>();
        if (!hasAssistantTurnInHistory && cached != null && cached.recommendedOptions() != null) {
            recommendedOptions.addAll(cached.recommendedOptions());
        }
        Set<String> usedTemplates = new LinkedHashSet<>();
        if (cached != null && cached.usedTemplates() != null) {
            usedTemplates.addAll(cached.usedTemplates());
        }

        Set<String> knownSymptoms = new LinkedHashSet<>();
        Set<String> knownBodyAreas = new LinkedHashSet<>();
        String knownSeverity = cached != null ? cached.knownSeverity() : null;
        String knownDurationOfProblem = cached != null ? cached.knownDurationOfProblem() : null;
        String knownTrigger = cached != null ? cached.knownTrigger() : null;
        String knownServiceFamily = cached != null ? cached.knownServiceFamily() : null;
        String knownPreferredPressure = cached != null ? cached.knownPreferredPressure() : null;
        String knownRecommendedPackage = cached != null ? cached.knownRecommendedPackage() : null;
        String lastQuotePackage = cached != null ? cached.lastQuotePackage() : null;
        String lastSentiment = "NEUTRAL";
        String lastEmotion = "NONE";

        int objectionCount = 0;
        int ackCount = 0;
        int clarificationCount = 0;
        int conditionAskedCount = 0;
        int preferenceAskedCount = 0;
        int clarifyServiceTypeCount = 0;
        int clarifyDurationCount = 0;
        int frustrationCount = 0;
        int ambiguousCount = 0;
        int recommendationCount = 0;
        int priceRepeatGuardCount = 0;
        boolean priceRequestedInSession = false;
        boolean humanTakeover = cached != null && cached.humanTakeover();
        boolean optedOut = cached != null && cached.optedOut();
        int customerSymptomTurns = 0;

        // v3.1.5.3 + v3.2.1 state variables reconstructed sequentially
        boolean downsellOffered = cached != null && cached.downsellOffered();
        boolean trialInviteOffered = cached != null && cached.trialInviteOffered();
        boolean explicitDownsellRequest = false;
        boolean packageRejectionDetected = cached != null && cached.packageRejectionDetected();
        String lastQuotedPackage = cached != null ? cached.lastQuotedPackage() : null;
        String lastQuotedDuration = cached != null ? cached.lastQuotedDuration() : null;
        BigDecimal lastQuotedPrice = cached != null ? cached.lastQuotedPrice() : null;
        String heldPackage = cached != null ? cached.heldPackage() : null;
        String heldDuration = cached != null ? cached.heldDuration() : null;
        String downsellPackage = cached != null ? cached.downsellPackage() : null;
        String downsellDuration = cached != null ? cached.downsellDuration() : null;
        BigDecimal downsellPrice = cached != null ? cached.downsellPrice() : null;
        Set<String> rejectedDurations = new LinkedHashSet<>();
        if (cached != null && cached.rejectedDurations() != null) {
            rejectedDurations.addAll(cached.rejectedDurations());
        }
        Set<String> rejectedPackages = new LinkedHashSet<>();
        if (cached != null && cached.rejectedPackages() != null) {
            rejectedPackages.addAll(cached.rejectedPackages());
        }
        String objectionStage = cached != null && cached.objectionStage() != null ? cached.objectionStage() : "NONE";
        String telemetrySubtype = cached != null ? cached.telemetrySubtype() : null;
        boolean neurologicalRisk = cached != null && cached.neurologicalRisk();

        String preferredRegion = cached != null && cached.preferredRegion() != null ? cached.preferredRegion() : "HN";
        Set<String> mentionedPackages = new LinkedHashSet<>();
        if (!hasAssistantTurnInHistory && cached != null && cached.mentionedPackages() != null) {
            mentionedPackages.addAll(cached.mentionedPackages());
        }
        Set<String> quotedPackages = new LinkedHashSet<>();
        if (!hasAssistantTurnInHistory && cached != null && cached.quotedPackages() != null) {
            quotedPackages.addAll(cached.quotedPackages());
        }
        int alternativeRequestCount = 0;
        boolean customerCorrectionDetected = cached != null && cached.customerCorrectionDetected();
        String rejectedWrongOffer = cached != null ? cached.rejectedWrongOffer() : null;
        List<String> lastBotBubbleHashes = new ArrayList<>();
        if (!hasAssistantTurnInHistory && cached != null && cached.lastBotBubbleHashes() != null) {
            lastBotBubbleHashes.addAll(cached.lastBotBubbleHashes());
        }
        List<UnresolvedCustomerRequest> outstandingRequests = new ArrayList<>();
        if (!hasAssistantTurnInHistory && cached != null && cached.outstandingRequests() != null) {
            outstandingRequests.addAll(cached.outstandingRequests());
        }
        String lastAssistantMessage = !hasAssistantTurnInHistory && cached != null ? cached.lastAssistantMessage() : null;
        boolean prevTurnWasAssistant = false;
        int turnIdx = 0;

        for (ConversationTurn turn : history) {
            if (turn == null || turn.content() == null || turn.content().isBlank()) {
                turnIdx++;
                continue;
            }
            String role = turn.role() != null ? turn.role().strip().toLowerCase(Locale.ROOT) : "user";
            String content = turn.content().strip();
            String lower = Normalizer.normalize(content, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);

            // Check optional test context overrides in history
            if (lower.contains("docs_has_feedback=true")) docsFeedback = true;
            if (lower.contains("docs_has_feedback=false")) docsFeedback = false;
            if (lower.contains("docs_has_private_room=true")) docsPrivateRoom = true;
            if (lower.contains("docs_has_private_room=false")) docsPrivateRoom = false;
            if (lower.contains("docs_has_relaxation_benefit=false")) docsRelaxation = false;

            if ("assistant".equals(role) || "bot".equals(role)) {
                greetingSent = true;
                introSent = true;
                usedTemplates.add("OPENING_GREETING_1");
                usedTemplates.add("OPENING_INTRO_2");
                appendBubbleHashes(lastBotBubbleHashes, content);
                if (prevTurnWasAssistant && lastAssistantMessage != null && !lastAssistantMessage.isBlank()) {
                    lastAssistantMessage = lastAssistantMessage + "\n" + content;
                } else {
                    lastAssistantMessage = content;
                }
                prevTurnWasAssistant = true;
                boolean resolvedFaqHere = markBotTurnAgainstOutstandingRequests(outstandingRequests, lastAssistantMessage);

                Matcher pm = PRICE_NUMBER_PATTERN.matcher(content);
                boolean botQuotedPriceHere = false;
                BigDecimal turnPriceVal = null;
                while (pm.find()) {
                    String rawPrice = pm.group().strip();
                    pricesShown.add(rawPrice);
                    botQuotedPriceHere = true;
                    BigDecimal parsed = parsePriceToBigDecimal(rawPrice);
                    if (parsed != null) {
                        turnPriceVal = parsed;
                    }
                }

                String turnDuration = extractDurationFromText(lower);
                boolean turnHasOptionUnit = false;
                Matcher om = OPTION_UNIT_PATTERN.matcher(content);
                while (om.find()) {
                    recommendedOptions.add(om.group(1).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip());
                    turnHasOptionUnit = true;
                }

                String turnPkgDisplay = extractPackageDisplayFromBotText(lower, turnDuration);
                String turnPkgCanonical = toCanonicalPackageCode(turnPkgDisplay, turnDuration);

                for (String mentionedCanon : extractAllCanonicalPackagesFromBotText(lower, turnDuration)) {
                    mentionedPackages.add(mentionedCanon);
                    if (botQuotedPriceHere) {
                        quotedPackages.add(mentionedCanon);
                    }
                }
                if (turnPkgCanonical != null) {
                    mentionedPackages.add(turnPkgCanonical);
                    if (botQuotedPriceHere) {
                        quotedPackages.add(turnPkgCanonical);
                    }
                }

                if (turnPkgDisplay != null && (botQuotedPriceHere || knownRecommendedPackage == null || !resolvedFaqHere)) {
                    knownRecommendedPackage = turnPkgDisplay;
                }

                List<PackageCatalogService.PackageSummary> activeRegionCatalog =
                        PackageCatalogService.filterByRegion(fullCatalog, preferredRegion);

                // Rule: extract_price_quote_history & downsell_detection_from_bot
                if (botQuotedPriceHere) {
                    String prevHeldPkg = heldPackage;
                    String prevHeldDur = heldDuration;
                    BigDecimal prevQuotedPrice = lastQuotedPrice;

                    boolean isDownsellBubble = (objectionCount > 0 || explicitDownsellRequest
                            || lower.contains("gọn chi phí") || lower.contains("gói ngắn")
                            || lower.contains("chi phí nhẹ hơn") || lower.contains("lăn tăn chủ yếu về chi phí"))
                            && ((prevHeldDur != null && turnDuration != null && parseDurationInt(turnDuration) < parseDurationInt(prevHeldDur))
                                || (prevQuotedPrice != null && turnPriceVal != null && turnPriceVal.compareTo(prevQuotedPrice) < 0));

                    if (isDownsellBubble) {
                        downsellOffered = true;
                        packageRejectionDetected = true;
                        if (prevHeldDur != null
                                && !prevHeldDur.equals(turnDuration)
                                && !hasAlternativePackageWithSameDuration(activeRegionCatalog, prevHeldDur, prevHeldPkg)) {
                            rejectedDurations.add(prevHeldDur);
                        }
                        if (prevHeldPkg != null) {
                            rejectedPackages.add(prevHeldPkg);
                        }
                        downsellPackage = turnPkgCanonical != null ? turnPkgCanonical : turnPkgDisplay;
                        downsellDuration = turnDuration;
                        downsellPrice = turnPriceVal;
                        heldPackage = downsellPackage;
                        heldDuration = downsellDuration;
                        lastQuotedPackage = downsellPackage;
                        lastQuotedDuration = downsellDuration;
                        lastQuotedPrice = turnPriceVal != null ? turnPriceVal : prevQuotedPrice;
                        lastQuotePackage = turnPkgDisplay != null ? turnPkgDisplay : downsellPackage;
                        objectionStage = "DOWNSELL_OFFERED";
                    } else {
                        if (turnPkgCanonical != null) {
                            lastQuotedPackage = turnPkgCanonical;
                            heldPackage = turnPkgCanonical;
                        } else if (turnPkgDisplay != null) {
                            lastQuotedPackage = turnPkgDisplay;
                            heldPackage = turnPkgDisplay;
                        }
                        if (turnDuration != null) {
                            lastQuotedDuration = turnDuration;
                            heldDuration = turnDuration;
                        }
                        if (turnPriceVal != null) {
                            lastQuotedPrice = turnPriceVal;
                        }
                        if (turnPkgDisplay != null) {
                            lastQuotePackage = turnPkgDisplay;
                        }
                    }
                } else if (turnPkgCanonical != null && heldPackage == null) {
                    heldPackage = turnPkgCanonical;
                    if (turnDuration != null) {
                        heldDuration = turnDuration;
                    }
                }

                // Rule: trial_invite_detection_from_bot
                if (TRIAL_INVITE_BOT_PATTERN.matcher(lower).matches()) {
                    trialInviteOffered = true;
                    lastQuestionIntent = "INVITE_ONE_SESSION_TRIAL";
                    usedQuestionIntents.add("INVITE_ONE_SESSION_TRIAL");
                    usedTemplates.add("VALUE_ANGLE_2");
                    if ("NONE".equals(objectionStage) || "VALUE_DEFENSE".equals(objectionStage)) {
                        objectionStage = "TRIAL_INVITED";
                    }
                }
                if ((botQuotedPriceHere && (content.contains("\n") || lower.contains("điều chỉnh lực") || lower.contains("chăm sóc êm ái")))
                        || (!botQuotedPriceHere && !pricesShown.isEmpty()
                            && (lower.contains("điều chỉnh lực") || lower.contains("chăm sóc êm ái") || lower.contains("thả lỏng thư giãn")))) {
                    usedTemplates.add("VALUE_ANGLE_1");
                }

                if (content.contains("?")) {
                    int qIdx = content.lastIndexOf('?');
                    int startSent = Math.max(
                            content.lastIndexOf('.', qIdx),
                            Math.max(content.lastIndexOf('!', qIdx), content.lastIndexOf('\n', qIdx))
                    );
                    lastQuestion = content.substring(startSent >= 0 ? startSent + 1 : 0, qIdx + 1).strip();
                }

                if (lower.contains("lâu chưa") || lower.contains("mới gần đây")
                        || lower.contains("gần đây hay") || lower.contains("hay đã kéo dài")
                        || lower.contains("ngồi lâu hay vận động") || lower.contains("mỏi vùng nào")
                        || lower.contains("lâu rồi") || lower.contains("mới bị")
                        || lower.contains("máy tính") || lower.contains("bao lâu")) {
                    conditionAskedCount++;
                    lastQuestionIntent = "ASK_CONDITION";
                    usedQuestionIntents.add("ASK_CONDITION");
                }
                if (lower.contains("lực nhẹ") || lower.contains("lực vừa") || lower.contains("lực mạnh")) {
                    preferenceAskedCount++;
                    lastQuestionIntent = "ASK_PRESSURE_PREFERENCE";
                    usedQuestionIntents.add("ASK_PRESSURE_PREFERENCE");
                    usedTemplates.add("ACKNOWLEDGE_AND_ASK_PREFERENCE");
                }
                if ((lower.contains("quan tâm") || lower.contains("đang hỏi") || lower.contains("nhóm dịch vụ"))
                        && lower.contains("hay") && lower.contains("?") && !botQuotedPriceHere
                        && !lower.contains("phút hay") && !lower.contains("phút và")) {
                    clarifyServiceTypeCount++;
                    clarificationCount++;
                    lastQuestionIntent = "CLARIFY_SERVICE_TYPE";
                    usedQuestionIntents.add("CLARIFY_SERVICE_TYPE");
                    lastBotAction = "CLARIFY_SERVICE_DURATION";
                    stage = ConversationStage.PRICE_CLARIFY;
                } else if ((lower.contains("phút và ") || lower.contains("phút hay ") || lower.contains("gửi mức giá gói nào"))
                        && !botQuotedPriceHere) {
                    clarifyDurationCount++;
                    clarificationCount++;
                    lastQuestionIntent = "CLARIFY_DURATION";
                    usedQuestionIntents.add("CLARIFY_DURATION");
                    lastBotAction = "CLARIFY_SERVICE_DURATION";
                    stage = ConversationStage.PRICE_CLARIFY;
                }

                if (lower.contains("vừa gửi ở tin trên") || lower.contains("ở tin nhắn trên")) {
                    priceRepeatGuardCount++;
                }

                if (lower.contains("băn khoăn của mình về chi phí") || lower.contains("cân nhắc chi phí") || lower.contains("cân nhắc về chi phí")) {
                    usedTemplates.add("PRICE_OBJECTION_FIRST_TIME");
                    usedTemplates.add("VALUE_ANGLE_2");
                    lastBotAction = "HANDLE_PRICE_OBJECTION_FIRST_TIME";
                    stage = ConversationStage.OBJECTION;
                    if ("NONE".equals(objectionStage)) {
                        objectionStage = trialInviteOffered ? "TRIAL_INVITED" : "VALUE_DEFENSE";
                    }
                } else if (lower.contains("vẫn đang cân nhắc")
                        || lower.contains("gọn chi phí cho lần đầu")
                        || lower.contains("chi phí gọn trước")
                        || lower.contains("mức chi phí nhẹ hơn")
                        || lower.contains("lăn tăn chủ yếu về chi phí")) {
                    usedTemplates.add("PRICE_OBJECTION_REPEAT");
                    lastBotAction = "HANDLE_PRICE_OBJECTION_REPEAT";
                    stage = ConversationStage.OBJECTION;
                    downsellOffered = true;
                    objectionStage = "DOWNSELL_OFFERED";
                } else if (lower.contains("đơn giá") || lower.contains("tính theo phút") || lower.contains("trên mỗi phút")) {
                    usedTemplates.add("EXPLAIN_UNIT_VALUE");
                    lastBotAction = "EXPLAIN_UNIT_VALUE";
                    stage = ConversationStage.COMPARISON;
                } else if (botQuotedPriceHere) {
                    lastBotAction = "ANSWER_PRICE_ONCE";
                    stage = ConversationStage.PRICE_QUOTED;
                    recommendationCount++;
                } else if (resolvedFaqHere) {
                    lastBotAction = "SERVICE_DETAIL_AND_FAQ_QA";
                } else if (turnPkgDisplay != null || turnHasOptionUnit || lower.contains("em gợi ý") || lower.contains("hợp hơn")) {
                    lastBotAction = "RECOMMEND_ONE_PACKAGE";
                    stage = ConversationStage.RECOMMENDATION;
                    recommendationCount++;
                }
            } else {
                // User turn analysis
                prevTurnWasAssistant = false;
                String normalizedTurnContent = VietnameseQueryNormalizer.normalizeForClassification(content);
                if (normalizedTurnContent != null && !normalizedTurnContent.isBlank()) {
                    content = normalizedTurnContent.strip();
                    lower = Normalizer.normalize(content, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
                }
                String activePkgForContext = heldPackage != null ? heldPackage : knownRecommendedPackage;
                recordUserTurnIntoOutstandingRequests(
                        outstandingRequests,
                        content,
                        lastAssistantMessage,
                        activePkgForContext,
                        turnIdx
                );
                preferredRegion = detectPreferredRegion(lower, preferredRegion);
                boolean isMismatchHere = SentimentIntentLayer.isEntityMismatchCorrection(content);
                String cleanContent = SentimentIntentLayer.stripMismatchCritiqueClause(content);
                String cleanLower = Normalizer.normalize(cleanContent, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);

                if (PrimaryActionRouter.isPriceInquiryMessage(content)
                        || lower.matches("(?isU).*\\b(giá|bảng\\s+giá|bao\\s+nhiêu|chi\\s+phí|phí|cost|fee|giá\\s+sao|giá\\s+vậy)\\b.*")) {
                    priceRequestedInSession = true;
                }

                if (SentimentIntentLayer.hasNeurologicalRisk(content, null)) {
                    neurologicalRisk = true;
                }

                boolean isPkgRejectHere = SentimentIntentLayer.isPackageRejection(content);
                boolean isExplicitDownsellHere = SentimentIntentLayer.isExplicitDownsellRequest(content, null);
                boolean isPriceObjHere = SentimentIntentLayer.isPriceObjection(content);
                boolean isHesitationAfterObjHere = SentimentIntentLayer.isHesitationAfterObjection(content);
                boolean isAltReqHere = SentimentIntentLayer.isAlternativePackageRequest(content);
                boolean isDetailFaqHere = SentimentIntentLayer.isDetailOrFaqQuestion(content, lastAssistantMessage, activePkgForContext);
                boolean isComprehensionFailHere = SentimentIntentLayer.isComprehensionFailureMessage(content);
                boolean isPushbackHere = SentimentIntentLayer.isNegationOrPushbackOnAssumption(content);

                if (isAltReqHere) {
                    alternativeRequestCount++;
                }

                if (isMismatchHere) {
                    customerCorrectionDetected = true;
                    // Xóa ngay gói tư vấn sai khỏi heldPackage / lastQuotedPackage / knownRecommendedPackage
                    if (heldPackage != null) rejectedPackages.add(heldPackage);
                    if (lastQuotedPackage != null) rejectedPackages.add(lastQuotedPackage);
                    String wrongOffer = SentimentIntentLayer.extractRejectedWrongOffer(content);
                    if (wrongOffer != null && !wrongOffer.isBlank()) {
                        rejectedWrongOffer = wrongOffer.toLowerCase(Locale.ROOT).strip();
                        rejectedPackages.add(rejectedWrongOffer);
                        String canonicalWrong = toCanonicalPackageCode(wrongOffer, null);
                        if (canonicalWrong != null) {
                            rejectedPackages.add(canonicalWrong);
                        }
                    }
                    heldPackage = null;
                    heldDuration = null;
                    lastQuotedPackage = null;
                    lastQuotedDuration = null;
                    lastQuotedPrice = null;
                    knownRecommendedPackage = null;
                    lastQuotePackage = null;
                    knownServiceFamily = null;
                    lastIntent = "ENTITY_MISMATCH_CORRECTION";
                    if (SentimentIntentLayer.hasHarshPronouns(content) || PrimaryActionRouter.isFrustrationMessage(content)) {
                        frustrationCount++;
                        lastEmotion = "ANGER";
                        lastSentiment = "NEGATIVE";
                    }
                    // Reset polluted scopes/needs so true scopes from cleanContent take precedence
                    List<String> correctedScopes = extractTargetScopes(cleanContent, domainKeywords);
                    if (!correctedScopes.isEmpty()) {
                        knownBodyAreas.clear();
                        knownBodyAreas.addAll(correctedScopes);
                    }
                    String correctedProb = extractProblemOrNeedPhrase(cleanContent, domainKeywords);
                    if (correctedProb != null) {
                        problemOrNeed = correctedProb;
                    }
                } else if (isPkgRejectHere) {
                    packageRejectionDetected = true;
                    String mentionedDur = SentimentIntentLayer.extractDurationNumber(lower);
                    if (mentionedDur != null) {
                        rejectedDurations.add(mentionedDur);
                        if (heldPackage != null) {
                            rejectedPackages.add(toCanonicalPackageCode(heldPackage, mentionedDur));
                        }
                    } else {
                        if (heldDuration != null) rejectedDurations.add(heldDuration);
                        if (heldPackage != null) rejectedPackages.add(heldPackage);
                    }
                }
                if (isExplicitDownsellHere) {
                    explicitDownsellRequest = true;
                }

                // Rule: extract_objection_history_corrected
                if (!isMismatchHere && downsellOffered
                        && (isPriceObjHere || isHesitationAfterObjHere || SentimentIntentLayer.isStrongRejection(content))
                        && !isAltReqHere && !isDetailFaqHere) {
                    objectionCount++;
                    lastIntent = "STAGE_3_GRACEFUL_EXIT";
                    objectionStage = "STAGE_3_GRACEFUL_EXIT";
                } else if (!isMismatchHere && (isPriceObjHere || isExplicitDownsellHere || isPkgRejectHere
                        || (objectionCount >= 1 && !downsellOffered && isHesitationAfterObjHere))
                        && (!pricesShown.isEmpty() || (cached != null && cached.priceDiscussed()))) {
                    objectionCount++;
                    lastIntent = isExplicitDownsellHere ? "EXPLICIT_DOWNSELL_REQUEST"
                            : (isPkgRejectHere ? "PACKAGE_REJECTION"
                                : (isHesitationAfterObjHere && !isPriceObjHere ? "HESITATION_AFTER_OBJECTION" : "PRICE_OBJECTION"));
                    usedTemplates.add("PRICE_OBJECTION_FIRST_TIME");
                } else if (!isMismatchHere && isPushbackHere) {
                    lastIntent = "PUSHBACK_ON_ASSUMPTION";
                } else if (!isMismatchHere && isAltReqHere) {
                    lastIntent = "ALTERNATIVE_PACKAGE_REQUEST";
                } else if (!isMismatchHere && isDetailFaqHere) {
                    lastIntent = "DETAIL_OR_FAQ_QUESTION";
                } else if (!isMismatchHere && isComprehensionFailHere) {
                    lastIntent = "COMPREHENSION_FAILURE";
                } else if (!isMismatchHere && PrimaryActionRouter.isFrustrationMessage(content)) {
                    frustrationCount++;
                    lastEmotion = "FRUSTRATION";
                    lastSentiment = "NEGATIVE";
                    lastIntent = "FRUSTRATION";
                } else if (!isMismatchHere && SentimentIntentLayer.isTrialAcceptance(content, null)) {
                    lastIntent = "TRIAL_ACCEPTANCE";
                    objectionStage = "BOOKED";
                } else if (!isMismatchHere && PrimaryActionRouter.isAcknowledgementMessage(content)) {
                    ackCount++;
                    lastIntent = "ACKNOWLEDGEMENT";
                } else if (!isMismatchHere && PrimaryActionRouter.isUnitPriceComparisonMessage(content)) {
                    lastIntent = "PRICE_COMPARISON";
                }

                Matcher symM = SYMPTOM_TOKEN_PATTERN.matcher(cleanContent);
                boolean hadSymptomInTurn = false;
                while (symM.find()) {
                    knownSymptoms.add(symM.group(1).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip());
                    hadSymptomInTurn = true;
                }
                if (hadSymptomInTurn) {
                    customerSymptomTurns++;
                }

                for (String scope : extractTargetScopes(cleanContent, domainKeywords)) {
                    knownBodyAreas.add(scope);
                }
                purgeNegatedSymptomsAndScopes(content, cleanContent, knownSymptoms, knownBodyAreas, domainKeywords);

                Matcher sevM = SEVERITY_TOKEN_PATTERN.matcher(cleanContent);
                if (sevM.find()) {
                    String rawSev = sevM.group(1).toLowerCase(Locale.ROOT).strip();
                    knownSeverity = "thi thoải".equals(rawSev) ? "thi thoảng" : rawSev;
                }

                Matcher durM = PROBLEM_DURATION_TOKEN_PATTERN.matcher(cleanContent);
                if (durM.find()) {
                    knownDurationOfProblem = durM.group(1).toLowerCase(Locale.ROOT).strip();
                }

                Matcher trigM = TRIGGER_TOKEN_PATTERN.matcher(cleanContent);
                if (trigM.find()) {
                    knownTrigger = trigM.group(1).toLowerCase(Locale.ROOT).strip();
                }

                Matcher prefM = PREFERENCE_PATTERN.matcher(cleanContent);
                if (prefM.find()) {
                    preferenceAttr = prefM.group(1).strip();
                    knownPreferredPressure = preferenceAttr;
                }

                Matcher optM = OPTION_UNIT_PATTERN.matcher(cleanContent);
                if (optM.find() && !PrimaryActionRouter.isUnitPriceComparisonMessage(cleanContent)) {
                    preferredOption = optM.group(1).strip();
                }

                String resolvedFam = resolveServiceFamilyFromText(cleanLower, knownBodyAreas, domainKeywords);
                if (resolvedFam != null) {
                    knownServiceFamily = resolvedFam;
                    itemInterest = resolvedFam;
                }

                String extractedProblem = extractProblemOrNeedPhrase(cleanContent, domainKeywords);
                if (extractedProblem != null) {
                    if (isMismatchHere || problemOrNeed == null || problemOrNeed.isBlank()) {
                        problemOrNeed = extractedProblem;
                    } else if (!problemOrNeed.contains(extractedProblem)) {
                        problemOrNeed = problemOrNeed + ", " + extractedProblem;
                    }
                } else if ((problemOrNeed == null || problemOrNeed.isBlank())
                        && !knownBodyAreas.isEmpty()
                        && cleanLower.contains("vùng")) {
                    problemOrNeed = "mỏi vùng " + String.join(" ", knownBodyAreas);
                }
            }
            turnIdx++;
        }

        if (cached != null) {
            objectionCount = Math.max(objectionCount, cached.objectionCount());
            ackCount = Math.max(ackCount, cached.ackCount());
            conditionAskedCount = Math.max(conditionAskedCount, cached.conditionAskedCount());
            preferenceAskedCount = Math.max(preferenceAskedCount, cached.preferenceAskedCount());
            clarifyServiceTypeCount = Math.max(clarifyServiceTypeCount, cached.clarifyServiceTypeCount());
            clarifyDurationCount = Math.max(clarifyDurationCount, cached.clarifyDurationCount());
            frustrationCount = Math.max(frustrationCount, cached.frustrationCount());
            priceRepeatGuardCount = Math.max(priceRepeatGuardCount, cached.priceRepeatGuardCount());
            priceRequestedInSession = priceRequestedInSession || cached.priceRequestedInSession();
            alternativeRequestCount = Math.max(alternativeRequestCount, cached.alternativeRequestCount());
            if (outstandingRequests.isEmpty() && cached.outstandingRequests() != null && !cached.outstandingRequests().isEmpty()) {
                outstandingRequests.addAll(cached.outstandingRequests());
            }
            if ((lastAssistantMessage == null || lastAssistantMessage.isBlank()) && cached.lastAssistantMessage() != null) {
                lastAssistantMessage = cached.lastAssistantMessage();
            }
        }

        if (knownServiceFamily == null && !knownBodyAreas.isEmpty()) {
            knownServiceFamily = resolveServiceFamilyFromText("", knownBodyAreas, domainKeywords);
        }
        boolean serviceFamilyKnown = knownServiceFamily != null && !knownServiceFamily.isBlank();

        boolean conditionClarified = (cached != null && cached.conditionClarified())
                || (!knownSymptoms.isEmpty() && !knownBodyAreas.isEmpty()
                    && (knownSeverity != null || knownDurationOfProblem != null || knownTrigger != null
                        || customerSymptomTurns >= 2))
                || (conditionAskedCount >= 1 && !"PUSHBACK_ON_ASSUMPTION".equals(lastIntent)
                    && (!knownSymptoms.isEmpty() || !knownBodyAreas.isEmpty() || preferenceAttr != null))
                || (frustrationCount >= 1 && (!knownSymptoms.isEmpty() || !knownBodyAreas.isEmpty() || serviceFamilyKnown))
                || (preferenceAttr != null && !preferenceAttr.isBlank());

        boolean packageRecommended = !recommendedOptions.isEmpty()
                || recommendationCount > 0
                || (cached != null && cached.packageRecommended());

        if (knownRecommendedPackage == null && packageRecommended) {
            knownRecommendedPackage = inferDefaultPackageName(knownServiceFamily, knownBodyAreas, recommendedOptions);
        }
        if (heldPackage == null && knownRecommendedPackage != null) {
            heldDuration = extractDurationFromText(knownRecommendedPackage);
            heldPackage = toCanonicalPackageCode(knownRecommendedPackage, heldDuration);
        }

        boolean priceDiscussed = !pricesShown.isEmpty() || (cached != null && cached.priceDiscussed());
        List<PackageCatalogService.PackageSummary> availablePackageCatalog =
                PackageCatalogService.filterByRegion(fullCatalog, preferredRegion);
        if (availablePackageCatalog.isEmpty() && cached != null && cached.availablePackageCatalog() != null) {
            availablePackageCatalog = cached.availablePackageCatalog();
        }

        return new ConversationState(
                greetingSent,
                introSent,
                stage,
                lastIntent,
                lastQuestion,
                lastQuestionIntent,
                Collections.unmodifiableSet(new LinkedHashSet<>(usedQuestionIntents)),
                lastBotAction,
                new KnownFacts(itemInterest, problemOrNeed, preferenceAttr, preferredOption, preferredTime),
                priceDiscussed,
                Collections.unmodifiableList(new ArrayList<>(pricesShown)),
                Collections.unmodifiableList(new ArrayList<>(recommendedOptions)),
                objectionCount,
                ackCount,
                clarificationCount,
                conditionAskedCount,
                preferenceAskedCount,
                clarifyServiceTypeCount,
                clarifyDurationCount,
                frustrationCount,
                ambiguousCount,
                recommendationCount,
                priceRepeatGuardCount,
                conditionClarified,
                serviceFamilyKnown,
                packageRecommended,
                priceRequestedInSession,
                Collections.unmodifiableList(new ArrayList<>(knownSymptoms)),
                Collections.unmodifiableList(new ArrayList<>(knownBodyAreas)),
                knownSeverity,
                knownDurationOfProblem,
                knownTrigger,
                knownServiceFamily,
                knownPreferredPressure,
                knownRecommendedPackage,
                lastQuotePackage,
                lastSentiment,
                lastEmotion,
                Collections.unmodifiableList(new ArrayList<>(usedTemplates)),
                humanTakeover,
                optedOut,
                downsellOffered,
                trialInviteOffered,
                explicitDownsellRequest,
                packageRejectionDetected,
                lastQuotedPackage,
                lastQuotedDuration,
                lastQuotedPrice,
                heldPackage,
                heldDuration,
                downsellPackage,
                downsellDuration,
                downsellPrice,
                Collections.unmodifiableSet(new LinkedHashSet<>(rejectedDurations)),
                Collections.unmodifiableSet(new LinkedHashSet<>(rejectedPackages)),
                objectionStage,
                telemetrySubtype,
                docsFeedback,
                docsRelaxation,
                docsPrivateRoom,
                neurologicalRisk,
                preferredRegion,
                Collections.unmodifiableList(new ArrayList<>(mentionedPackages)),
                Collections.unmodifiableList(new ArrayList<>(quotedPackages)),
                lastQuotedPrice,
                alternativeRequestCount,
                customerCorrectionDetected,
                rejectedWrongOffer,
                Collections.unmodifiableList(new ArrayList<>(availablePackageCatalog)),
                Collections.unmodifiableList(new ArrayList<>(lastBotBubbleHashes)),
                Collections.unmodifiableList(new ArrayList<>(outstandingRequests)),
                lastAssistantMessage
        );
    }

    public ConversationState withDocFlags(boolean hasFeedback, boolean hasRelaxation, boolean hasPrivateRoom) {
        return new ConversationState(
                greetingSent, introSent, stage, lastIntent, lastQuestion, lastQuestionIntent,
                usedQuestionIntents, lastBotAction, known, priceDiscussed, pricesShown, recommendedOptions,
                objectionCount, ackCount, clarificationCount, conditionAskedCount, preferenceAskedCount,
                clarifyServiceTypeCount, clarifyDurationCount, frustrationCount, ambiguousCount,
                recommendationCount, priceRepeatGuardCount, conditionClarified, serviceFamilyKnown,
                packageRecommended, priceRequestedInSession, knownSymptoms, knownBodyAreas,
                knownSeverity, knownDurationOfProblem, knownTrigger, knownServiceFamily,
                knownPreferredPressure, knownRecommendedPackage, lastQuotePackage, lastSentiment,
                lastEmotion, usedTemplates, humanTakeover, optedOut,
                downsellOffered, trialInviteOffered, explicitDownsellRequest, packageRejectionDetected,
                lastQuotedPackage, lastQuotedDuration, lastQuotedPrice, heldPackage, heldDuration,
                downsellPackage, downsellDuration, downsellPrice, rejectedDurations, rejectedPackages,
                objectionStage, telemetrySubtype, hasFeedback, hasRelaxation, hasPrivateRoom, neurologicalRisk,
                preferredRegion, mentionedPackages, quotedPackages, heldPrice, alternativeRequestCount,
                customerCorrectionDetected, rejectedWrongOffer, availablePackageCatalog, lastBotBubbleHashes,
                outstandingRequests, lastAssistantMessage
        );
    }

    public ConversationState withCatalogAndRegion(
            List<PackageCatalogService.PackageSummary> catalog,
            String region
    ) {
        return new ConversationState(
                greetingSent, introSent, stage, lastIntent, lastQuestion, lastQuestionIntent,
                usedQuestionIntents, lastBotAction, known, priceDiscussed, pricesShown, recommendedOptions,
                objectionCount, ackCount, clarificationCount, conditionAskedCount, preferenceAskedCount,
                clarifyServiceTypeCount, clarifyDurationCount, frustrationCount, ambiguousCount,
                recommendationCount, priceRepeatGuardCount, conditionClarified, serviceFamilyKnown,
                packageRecommended, priceRequestedInSession, knownSymptoms, knownBodyAreas,
                knownSeverity, knownDurationOfProblem, knownTrigger, knownServiceFamily,
                knownPreferredPressure, knownRecommendedPackage, lastQuotePackage, lastSentiment,
                lastEmotion, usedTemplates, humanTakeover, optedOut,
                downsellOffered, trialInviteOffered, explicitDownsellRequest, packageRejectionDetected,
                lastQuotedPackage, lastQuotedDuration, lastQuotedPrice, heldPackage, heldDuration,
                downsellPackage, downsellDuration, downsellPrice, rejectedDurations, rejectedPackages,
                objectionStage, telemetrySubtype, docsHasFeedback, docsHasRelaxationBenefit, docsHasPrivateRoom, neurologicalRisk,
                region != null ? region : preferredRegion,
                mentionedPackages, quotedPackages, heldPrice, alternativeRequestCount,
                customerCorrectionDetected, rejectedWrongOffer,
                catalog != null ? Collections.unmodifiableList(new ArrayList<>(catalog)) : availablePackageCatalog,
                lastBotBubbleHashes,
                outstandingRequests,
                lastAssistantMessage
        );
    }

    /**
     * Phân tích tin nhắn hiện tại của khách hàng và kết hợp vào {@link ConversationState}
     * ngay trước khi chạy {@link PrimaryActionRouter#route}.
     */
    public ConversationState enrichWithCurrentMessage(String currentMessage, List<String> domainKeywords) {
        if (currentMessage == null || currentMessage.isBlank()) {
            return this;
        }
        String normalizedCurrent = VietnameseQueryNormalizer.normalizeForClassification(currentMessage);
        String content = (normalizedCurrent != null && !normalizedCurrent.isBlank() ? normalizedCurrent : currentMessage).strip();
        String lower = Normalizer.normalize(content, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        String nextRegion = detectPreferredRegion(lower, this.preferredRegion);
        boolean isMismatch = SentimentIntentLayer.isEntityMismatchCorrection(content);
        String cleanContent = SentimentIntentLayer.stripMismatchCritiqueClause(content);
        String cleanLower = Normalizer.normalize(cleanContent, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);

        Set<String> symptoms = new LinkedHashSet<>(this.knownSymptoms);
        Set<String> areas = new LinkedHashSet<>(this.knownBodyAreas);
        String severity = this.knownSeverity;
        String probDuration = this.knownDurationOfProblem;
        String trigger = this.knownTrigger;
        String serviceFam = this.knownServiceFamily;
        String prefPressure = this.knownPreferredPressure;
        String prefOption = this.known != null ? this.known.preferredOption() : null;
        String probPhrase = this.known != null ? this.known.customerProblemOrNeed() : null;
        boolean priceReq = this.priceRequestedInSession;
        String sentiment = "NEUTRAL";
        String emotion = "NONE";
        String nextHeldPkg = this.heldPackage;
        String nextHeldDur = this.heldDuration;
        String nextLastQuotedPkg = this.lastQuotedPackage;
        String nextLastQuotedDur = this.lastQuotedDuration;
        BigDecimal nextLastQuotedPrice = this.lastQuotedPrice;
        String recPkg = this.knownRecommendedPackage;
        String nextLastQuotePkg = this.lastQuotePackage;
        Set<String> nextRejectedPkgs = new LinkedHashSet<>(this.rejectedPackages);
        boolean nextCorrectionDetected = this.customerCorrectionDetected;
        String nextRejectedWrongOffer = this.rejectedWrongOffer;
        List<UnresolvedCustomerRequest> nextOutstanding = new ArrayList<>(
                this.outstandingRequests != null ? this.outstandingRequests : List.of()
        );
        String activePkgContext = nextHeldPkg != null ? nextHeldPkg : recPkg;
        recordUserTurnIntoOutstandingRequests(
                nextOutstanding,
                content,
                this.lastAssistantMessage,
                activePkgContext,
                nextOutstanding.size()
        );
        boolean isDetailFaqCurrent = SentimentIntentLayer.isDetailOrFaqQuestion(content, this.lastAssistantMessage, activePkgContext);
        boolean isComprehensionCurrent = SentimentIntentLayer.isComprehensionFailureMessage(content);

        if (PrimaryActionRouter.isPriceInquiryMessage(content)
                || lower.matches("(?isU).*\\b(giá|bảng\\s+giá|bao\\s+nhiêu|chi\\s+phí|phí|cost|fee|giá\\s+sao|giá\\s+vậy)\\b.*")) {
            priceReq = true;
        }

        if (isMismatch) {
            nextCorrectionDetected = true;
            if (nextHeldPkg != null) nextRejectedPkgs.add(nextHeldPkg);
            if (nextLastQuotedPkg != null) nextRejectedPkgs.add(nextLastQuotedPkg);
            String wrongOffer = SentimentIntentLayer.extractRejectedWrongOffer(content);
            if (wrongOffer != null && !wrongOffer.isBlank()) {
                nextRejectedWrongOffer = wrongOffer.toLowerCase(Locale.ROOT).strip();
                nextRejectedPkgs.add(nextRejectedWrongOffer);
                String canonicalWrong = toCanonicalPackageCode(wrongOffer, null);
                if (canonicalWrong != null) {
                    nextRejectedPkgs.add(canonicalWrong);
                }
            }
            nextHeldPkg = null;
            nextHeldDur = null;
            nextLastQuotedPkg = null;
            nextLastQuotedDur = null;
            nextLastQuotedPrice = null;
            recPkg = null;
            nextLastQuotePkg = null;
            serviceFam = null;
            if (SentimentIntentLayer.hasHarshPronouns(content) || PrimaryActionRouter.isFrustrationMessage(content)) {
                sentiment = "NEGATIVE";
                emotion = "ANGER";
            } else {
                sentiment = "NEGATIVE";
                emotion = "FRUSTRATION";
            }
            List<String> correctedScopes = extractTargetScopes(cleanContent, domainKeywords);
            if (!correctedScopes.isEmpty()) {
                areas.clear();
                areas.addAll(correctedScopes);
            }
        } else if (PrimaryActionRouter.isFrustrationMessage(content)) {
            sentiment = "NEGATIVE";
            emotion = "FRUSTRATION";
        } else if (CustomerInsightEngine.hasComplaintSignal(lower)) {
            sentiment = "NEGATIVE";
            emotion = "ANGER";
        }

        boolean neuroRisk = this.neurologicalRisk || SentimentIntentLayer.hasNeurologicalRisk(content, this);
        boolean expDownsell = SentimentIntentLayer.isExplicitDownsellRequest(content, this);
        boolean pkgReject = this.packageRejectionDetected || SentimentIntentLayer.isPackageRejection(content);

        Matcher symM = SYMPTOM_TOKEN_PATTERN.matcher(cleanContent);
        while (symM.find()) {
            symptoms.add(symM.group(1).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip());
        }
        for (String scope : extractTargetScopes(cleanContent, domainKeywords)) {
            areas.add(scope);
        }
        purgeNegatedSymptomsAndScopes(content, cleanContent, symptoms, areas, domainKeywords);
        Matcher sevM = SEVERITY_TOKEN_PATTERN.matcher(cleanContent);
        if (sevM.find()) {
            String rawSev = sevM.group(1).toLowerCase(Locale.ROOT).strip();
            severity = "thi thoải".equals(rawSev) ? "thi thoảng" : rawSev;
        }
        Matcher durM = PROBLEM_DURATION_TOKEN_PATTERN.matcher(cleanContent);
        if (durM.find()) {
            probDuration = durM.group(1).toLowerCase(Locale.ROOT).strip();
        }
        Matcher trigM = TRIGGER_TOKEN_PATTERN.matcher(cleanContent);
        if (trigM.find()) {
            trigger = trigM.group(1).toLowerCase(Locale.ROOT).strip();
        }
        Matcher prefM = PREFERENCE_PATTERN.matcher(cleanContent);
        if (prefM.find()) {
            prefPressure = prefM.group(1).strip();
        }
        Matcher optM = OPTION_UNIT_PATTERN.matcher(cleanContent);
        if (optM.find() && !PrimaryActionRouter.isUnitPriceComparisonMessage(cleanContent)) {
            prefOption = optM.group(1).strip();
        }

        String detectedFam = resolveServiceFamilyFromText(cleanLower, areas, domainKeywords);
        if (detectedFam != null) {
            serviceFam = detectedFam;
        } else if (serviceFam == null && !areas.isEmpty()) {
            serviceFam = resolveServiceFamilyFromText("", areas, domainKeywords);
        }
        boolean famKnown = serviceFam != null && !serviceFam.isBlank();

        String extractedProb = extractProblemOrNeedPhrase(cleanContent, domainKeywords);
        if (extractedProb != null) {
            if (isMismatch || probPhrase == null || probPhrase.isBlank()) {
                probPhrase = extractedProb;
            } else if (!probPhrase.contains(extractedProb)) {
                probPhrase = probPhrase + ", " + extractedProb;
            }
        } else if ((probPhrase == null || probPhrase.isBlank())
                && !areas.isEmpty()
                && cleanLower.contains("vùng")) {
            probPhrase = "mỏi vùng " + String.join(" ", areas);
        }

        boolean isPushbackCurrent = SentimentIntentLayer.isNegationOrPushbackOnAssumption(content);
        boolean condClarified = this.conditionClarified
                || (!symptoms.isEmpty() && !areas.isEmpty()
                    && (severity != null || probDuration != null || trigger != null))
                || (this.conditionAskedCount >= 1 && !isPushbackCurrent && (!symptoms.isEmpty() || !areas.isEmpty() || severity != null || probDuration != null || trigger != null || prefPressure != null || PrimaryActionRouter.isAcknowledgementMessage(content) || PrimaryActionRouter.isConditionFollowUpMessage(content)))
                || (("FRUSTRATION".equals(emotion) || "ANGER".equals(emotion)) && (!symptoms.isEmpty() || !areas.isEmpty() || famKnown))
                || (prefPressure != null && !prefPressure.isBlank());

        String nextLastIntent = isMismatch ? "ENTITY_MISMATCH_CORRECTION"
                : (isPushbackCurrent ? "PUSHBACK_ON_ASSUMPTION"
                : (isDetailFaqCurrent ? "DETAIL_OR_FAQ_QUESTION"
                : (isComprehensionCurrent ? "COMPREHENSION_FAILURE" : this.lastIntent)));

        return new ConversationState(
                this.greetingSent,
                this.introSent,
                this.stage,
                nextLastIntent,
                this.lastQuestion,
                this.lastQuestionIntent,
                this.usedQuestionIntents,
                this.lastBotAction,
                new KnownFacts(
                        serviceFam != null ? serviceFam : (this.known != null ? this.known.itemInterest() : null),
                        probPhrase,
                        prefPressure,
                        prefOption,
                        this.known != null ? this.known.preferredTime() : null
                ),
                this.priceDiscussed,
                this.pricesShown,
                this.recommendedOptions,
                this.objectionCount,
                this.ackCount,
                this.clarificationCount,
                this.conditionAskedCount,
                this.preferenceAskedCount,
                this.clarifyServiceTypeCount,
                this.clarifyDurationCount,
                this.frustrationCount,
                this.ambiguousCount,
                this.recommendationCount,
                this.priceRepeatGuardCount,
                condClarified,
                famKnown,
                this.packageRecommended,
                priceReq,
                Collections.unmodifiableList(new ArrayList<>(symptoms)),
                Collections.unmodifiableList(new ArrayList<>(areas)),
                severity,
                probDuration,
                trigger,
                serviceFam,
                prefPressure,
                recPkg,
                nextLastQuotePkg,
                sentiment,
                emotion,
                this.usedTemplates,
                this.humanTakeover,
                this.optedOut,
                this.downsellOffered,
                this.trialInviteOffered,
                expDownsell,
                pkgReject,
                nextLastQuotedPkg,
                nextLastQuotedDur,
                nextLastQuotedPrice,
                nextHeldPkg,
                nextHeldDur,
                this.downsellPackage,
                this.downsellDuration,
                this.downsellPrice,
                this.rejectedDurations,
                Collections.unmodifiableSet(nextRejectedPkgs),
                this.objectionStage,
                this.telemetrySubtype,
                this.docsHasFeedback,
                this.docsHasRelaxationBenefit,
                this.docsHasPrivateRoom,
                neuroRisk,
                nextRegion,
                this.mentionedPackages,
                this.quotedPackages,
                nextLastQuotedPrice,
                this.alternativeRequestCount,
                nextCorrectionDetected,
                nextRejectedWrongOffer,
                this.availablePackageCatalog,
                this.lastBotBubbleHashes,
                Collections.unmodifiableList(nextOutstanding),
                this.lastAssistantMessage
        );
    }

    public static ConversationState updateAfterSend(
            UUID pageId,
            String senderId,
            ConversationState prevState,
            String userMessage,
            PrimaryActionRouter.RoutingDecision decision,
            List<ReplyBubble> finalBubbles,
            boolean needsHumanHandoff
    ) {
        return updateAfterSend(pageId, senderId, null, prevState, userMessage, decision, finalBubbles, needsHumanHandoff, -1);
    }

    public static ConversationState updateAfterSend(
            UUID pageId,
            String senderId,
            ConversationState prevState,
            String userMessage,
            PrimaryActionRouter.RoutingDecision decision,
            List<ReplyBubble> finalBubbles,
            boolean needsHumanHandoff,
            int incomingHistorySize
    ) {
        return updateAfterSend(pageId, senderId, null, prevState, userMessage, decision, finalBubbles, needsHumanHandoff, incomingHistorySize);
    }

    public static ConversationState updateAfterSend(
            UUID pageId,
            String senderId,
            String conversationId,
            ConversationState prevState,
            String userMessage,
            PrimaryActionRouter.RoutingDecision decision,
            List<ReplyBubble> finalBubbles,
            boolean needsHumanHandoff,
            int incomingHistorySize
    ) {
        String sessionKey = buildSessionKey(pageId, senderId, conversationId);
        String normUserMsg = VietnameseQueryNormalizer.normalize(userMessage != null ? userMessage : "")
                .toLowerCase(Locale.ROOT).strip();
        ConversationState effectivePrev = prevState != null ? prevState : initial();
        if (sessionKey != null) {
            LastTurnPreStateSnapshot snap = LAST_TURN_PRE_STATE_STORE.get(sessionKey);
            if (snap != null && normUserMsg.equals(snap.normalizedUserMessage())) {
                boolean sameHistoryLen = incomingHistorySize >= 0 && incomingHistorySize == snap.historySize();
                boolean samePostState = prevState == snap.postTurnState()
                        || (prevState != null && snap.postTurnState() != null
                            && prevState.lastBotBubbleHashes().equals(snap.postTurnState().lastBotBubbleHashes())
                            && prevState.frustrationCount() == snap.postTurnState().frustrationCount()
                            && prevState.objectionCount() == snap.postTurnState().objectionCount()
                            && prevState.clarificationCount() == snap.postTurnState().clarificationCount());
                if (sameHistoryLen || samePostState) {
                    effectivePrev = snap.preTurnState() != null ? snap.preTurnState() : initial();
                }
            }
        }
        ConversationState base = effectivePrev.enrichWithCurrentMessage(userMessage, List.of());
        Set<String> prices = new LinkedHashSet<>(base.pricesShown());
        Set<String> options = new LinkedHashSet<>(base.recommendedOptions());
        Set<String> templates = new LinkedHashSet<>(base.usedTemplates());
        Set<String> usedQIntents = new LinkedHashSet<>(base.usedQuestionIntents());
        Set<String> nextRejectedDurations = new LinkedHashSet<>(base.rejectedDurations());
        Set<String> nextRejectedPackages = new LinkedHashSet<>(base.rejectedPackages());
        Set<String> nextMentionedPackages = new LinkedHashSet<>(base.mentionedPackages());
        Set<String> nextQuotedPackages = new LinkedHashSet<>(base.quotedPackages());
        List<String> nextBubbleHashes = new ArrayList<>(base.lastBotBubbleHashes());
        List<UnresolvedCustomerRequest> nextOutstanding = new ArrayList<>(
                base.outstandingRequests() != null ? base.outstandingRequests() : List.of()
        );

        boolean greetingNowSent = base.greetingSent()
                || (decision != null && decision.openingRequired())
                || (finalBubbles != null && !finalBubbles.isEmpty());
        if (greetingNowSent) {
            templates.add("OPENING_GREETING_1");
            templates.add("OPENING_INTRO_2");
        }

        String lastQ = base.lastQuestion();
        boolean anyPriceInReply = false;
        BigDecimal replyPriceVal = null;
        String combinedBotReplyRaw = "";
        String combinedBotReplyLower = "";
        if (finalBubbles != null) {
            StringBuilder sb = new StringBuilder();
            for (ReplyBubble b : finalBubbles) {
                if (b == null || b.text() == null) continue;
                String t = b.text();
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(t);
                appendBubbleHashes(nextBubbleHashes, t);
                Matcher pm = PRICE_NUMBER_PATTERN.matcher(t);
                while (pm.find()) {
                    String rawP = pm.group().strip();
                    prices.add(rawP);
                    anyPriceInReply = true;
                    BigDecimal parsed = parsePriceToBigDecimal(rawP);
                    if (parsed != null) {
                        replyPriceVal = parsed;
                    }
                }
                Matcher om = OPTION_UNIT_PATTERN.matcher(t);
                while (om.find()) {
                    options.add(om.group(1).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip());
                }
                if (t.contains("?")) {
                    lastQ = t;
                }
            }
            combinedBotReplyRaw = sb.toString().strip();
            combinedBotReplyLower = combinedBotReplyRaw.toLowerCase(Locale.ROOT);
        }

        if (!combinedBotReplyRaw.isBlank()) {
            markDeliveredBotReplyAgainstOutstandingRequests(nextOutstanding, combinedBotReplyRaw, needsHumanHandoff);
        }

        String effAction = decision != null ? decision.effectiveResponseAction().name() : base.lastBotAction();
        if (effAction != null) {
            templates.add(effAction);
        }

        String skillCode = decision != null && decision.correspondingMicroSkillCode() != null
                ? decision.correspondingMicroSkillCode()
                : "";
        String detectedIntent = decision != null && decision.detectedIntent() != null
                ? decision.detectedIntent()
                : "";

        if (finalBubbles != null && !finalBubbles.isEmpty()) {
            if ((decision != null
                    && decision.effectiveResponseAction() == PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE
                    && finalBubbles.size() >= 2)
                    || "GENERATE_PRICE_ANCHOR_BUBBLE".equalsIgnoreCase(detectedIntent)) {
                templates.add("VALUE_ANGLE_1");
            }
            if ((decision != null
                    && decision.effectiveResponseAction() == PrimaryActionRouter.PrimaryAction.HANDLE_PRICE_OBJECTION_FIRST_TIME)
                    || "spa_objection_value_trial".equalsIgnoreCase(skillCode)
                    || "PRICE_OBJECTION_VALUE_TRIAL".equalsIgnoreCase(detectedIntent)) {
                templates.add("VALUE_ANGLE_2");
            }
        }

        int nextObjectionCount = base.objectionCount();
        boolean nextDownsellOffered = base.downsellOffered();
        boolean nextTrialInviteOffered = base.trialInviteOffered();
        boolean nextPkgRejectionDetected = base.packageRejectionDetected();
        String nextLastQuotedPkg = base.lastQuotedPackage();
        String nextLastQuotedDur = base.lastQuotedDuration();
        BigDecimal nextLastQuotedPrice = base.lastQuotedPrice();
        String nextHeldPkg = base.heldPackage();
        String nextHeldDur = base.heldDuration();
        String nextDownsellPkg = base.downsellPackage();
        String nextDownsellDur = base.downsellDuration();
        BigDecimal nextDownsellPrice = base.downsellPrice();
        String nextObjectionStage = base.objectionStage();
        String nextTelemetrySubtype = base.telemetrySubtype();
        String nextQuestionIntent = base.lastQuestionIntent();
        int nextAlternativeReqCount = base.alternativeRequestCount();

        if ("ALTERNATIVE_PACKAGE_REQUEST".equalsIgnoreCase(detectedIntent)
                || "spa_alternative_package_request".equalsIgnoreCase(skillCode)
                || SentimentIntentLayer.isAlternativePackageRequest(userMessage)) {
            nextAlternativeReqCount++;
        }

        String replyDuration = extractDurationFromText(combinedBotReplyLower);
        String replyPkgDisplay = extractPackageDisplayFromBotText(combinedBotReplyLower, replyDuration);
        if ((replyPkgDisplay == null || replyDuration == null)
                && replyPriceVal != null
                && base.availablePackageCatalog() != null
                && !base.availablePackageCatalog().isEmpty()) {
            long targetVnd = replyPriceVal.longValue();
            int parsedReplyDur = parseDurationInt(replyDuration);
            for (PackageCatalogService.PackageSummary s : base.availablePackageCatalog()) {
                if (s != null && s.priceVnd() == targetVnd
                        && (parsedReplyDur <= 0 || s.durationMinutes() == parsedReplyDur)) {
                    if (replyDuration == null && s.durationMinutes() > 0) {
                        replyDuration = String.valueOf(s.durationMinutes());
                    }
                    if (replyPkgDisplay == null && s.displayName() != null && !s.displayName().isBlank()) {
                        String disp = s.displayName().toLowerCase(Locale.ROOT).strip();
                        replyPkgDisplay = (replyDuration != null && !disp.contains("phút"))
                                ? disp + " " + replyDuration + " phút"
                                : disp;
                    }
                    break;
                }
            }
        }
        String replyPkgCanonical = toCanonicalPackageCode(replyPkgDisplay, replyDuration);

        for (String mentionedCanon : extractAllCanonicalPackagesFromBotText(combinedBotReplyLower, replyDuration)) {
            nextMentionedPackages.add(mentionedCanon);
            if (anyPriceInReply) {
                nextQuotedPackages.add(mentionedCanon);
            }
        }
        if (replyPkgCanonical != null) {
            nextMentionedPackages.add(replyPkgCanonical);
            if (anyPriceInReply) {
                nextQuotedPackages.add(replyPkgCanonical);
            }
        }

        // === v3.1.5.3 State Transitions for R11, R12, R13B ===
        if ("spa_objection_value_trial".equalsIgnoreCase(skillCode)
                || "PRICE_OBJECTION_VALUE_TRIAL".equalsIgnoreCase(detectedIntent)) {
            // Normal first objection: Hold package, DO NOT add to rejectedPackages / rejectedDurations!
            nextObjectionCount = Math.max(1, base.objectionCount() + 1);
            nextTrialInviteOffered = true;
            nextDownsellOffered = false;
            nextPkgRejectionDetected = false;
            nextObjectionStage = "TRIAL_INVITED";
            nextQuestionIntent = "INVITE_ONE_SESSION_TRIAL";
            usedQIntents.add("INVITE_ONE_SESSION_TRIAL");
            templates.add("PRICE_OBJECTION_FIRST_TIME");
            if (finalBubbles != null && !finalBubbles.isEmpty()) {
                templates.add("VALUE_ANGLE_2");
            }
        } else if ("spa_objection_explicit_downsell".equalsIgnoreCase(skillCode)
                || "spa_objection_dynamic_downsell".equalsIgnoreCase(skillCode)
                || "EXPLICIT_DOWNSELL_WITH_PACKAGE".equalsIgnoreCase(detectedIntent)
                || "SECOND_OBJECTION_DOWNSELL".equalsIgnoreCase(detectedIntent)) {
            nextObjectionCount = base.objectionCount() + 1;
            nextDownsellOffered = true;
            nextPkgRejectionDetected = true;
            nextObjectionStage = "DOWNSELL_OFFERED";
            String prevDurToReject = base.heldDuration() != null ? base.heldDuration() : base.lastQuotedDuration();
            String prevPkgToReject = base.heldPackage() != null ? base.heldPackage() : base.lastQuotedPackage();
            if (prevDurToReject != null
                    && !prevDurToReject.equals(replyDuration)
                    && !hasAlternativePackageWithSameDuration(base.availablePackageCatalog(), prevDurToReject, prevPkgToReject)) {
                nextRejectedDurations.add(prevDurToReject);
            }
            if (prevPkgToReject != null) {
                nextRejectedPackages.add(prevPkgToReject);
            }
            if (replyDuration != null) {
                nextDownsellDur = replyDuration;
                nextHeldDur = replyDuration;
                nextLastQuotedDur = replyDuration;
            }
            if (replyPkgCanonical != null) {
                nextDownsellPkg = replyPkgCanonical;
                nextHeldPkg = replyPkgCanonical;
                nextLastQuotedPkg = replyPkgCanonical;
            }
            if (replyPriceVal != null) {
                nextDownsellPrice = replyPriceVal;
                nextLastQuotedPrice = replyPriceVal;
            }
            templates.add("PRICE_OBJECTION_REPEAT");
        } else if ("spa_objection_no_lower_package_handoff".equalsIgnoreCase(skillCode)
                || "NO_LOWER_PACKAGE_HANDOFF".equalsIgnoreCase(detectedIntent)) {
            nextObjectionCount = base.objectionCount() + 1;
            nextDownsellOffered = false;
            nextObjectionStage = "NO_LOWER_PACKAGE_HANDOFF";
            nextTelemetrySubtype = "NO_LOWER_PACKAGE_HANDOFF";
        } else if ("spa_stage3_graceful_exit".equalsIgnoreCase(skillCode)
                || "STAGE_3_GRACEFUL_EXIT".equalsIgnoreCase(detectedIntent)) {
            nextObjectionCount = base.objectionCount() + 1;
            nextDownsellOffered = true;
            nextObjectionStage = "STAGE_3_GRACEFUL_EXIT";
            nextTelemetrySubtype = "STAGE_3_GRACEFUL_EXIT";
        } else if ("spa_price_escalation_handoff".equalsIgnoreCase(skillCode)
                || "PRICE_ESCALATION_HANDOFF".equalsIgnoreCase(detectedIntent)) {
            nextObjectionCount = base.objectionCount() + 1;
            nextObjectionStage = "PRICE_ESCALATION_HANDOFF";
            nextTelemetrySubtype = "PRICE_ESCALATION_HANDOFF";
        } else if ("spa_trial_acceptance_booking".equalsIgnoreCase(skillCode)
                || "TRIAL_ACCEPTANCE".equalsIgnoreCase(detectedIntent)) {
            nextObjectionStage = "BOOKED";
            nextQuestionIntent = "CONFIRM_BOOKING_TIME";
            usedQIntents.add("CONFIRM_BOOKING_TIME");
        } else if (decision != null && decision.effectiveResponseAction() == PrimaryActionRouter.PrimaryAction.HARD_HANDOFF_SAFETY) {
            nextTelemetrySubtype = "SAFETY_HANDOFF";
        }

        int nextAckCount = base.ackCount();
        if (decision != null && decision.effectiveResponseAction() == PrimaryActionRouter.PrimaryAction.ADVANCE_AFTER_ACK) {
            nextAckCount++;
        }

        int nextCondAsked = base.conditionAskedCount();
        int nextPrefAsked = base.preferenceAskedCount();
        int nextClarifyService = base.clarifyServiceTypeCount();
        int nextClarifyDuration = base.clarifyDurationCount();
        int nextFrustration = base.frustrationCount();
        int nextAmbiguous = base.ambiguousCount();
        int nextRecCount = base.recommendationCount();
        int nextPriceRepeatGuard = base.priceRepeatGuardCount();
        boolean nextPkgRecommended = base.packageRecommended();
        String nextKnownRecPkg = base.knownRecommendedPackage();
        String nextLastQuotePkg = base.lastQuotePackage();

        if ("FRUSTRATION".equals(base.lastEmotion())) {
            nextFrustration++;
        }

        if ("spa_condition_first_discovery".equalsIgnoreCase(skillCode)
                || combinedBotReplyLower.contains("bị lâu chưa")
                || combinedBotReplyLower.contains("mỏi vùng nào")) {
            nextCondAsked++;
            nextQuestionIntent = "ASK_CONDITION";
            usedQIntents.add("ASK_CONDITION");
        } else if (combinedBotReplyLower.contains("lực nhẹ") || combinedBotReplyLower.contains("lực vừa")) {
            nextPrefAsked++;
            nextQuestionIntent = "ASK_PRESSURE_PREFERENCE";
            usedQIntents.add("ASK_PRESSURE_PREFERENCE");
        } else if ("spa_clarify_service_type_once".equalsIgnoreCase(skillCode)
                || (decision != null && decision.effectiveResponseAction() == PrimaryActionRouter.PrimaryAction.CLARIFY_SERVICE_DURATION
                    && !base.serviceFamilyKnown())) {
            nextClarifyService++;
            nextQuestionIntent = "CLARIFY_SERVICE_TYPE";
            usedQIntents.add("CLARIFY_SERVICE_TYPE");
        } else if ("spa_clarify_duration_once".equalsIgnoreCase(skillCode)
                || (decision != null && decision.effectiveResponseAction() == PrimaryActionRouter.PrimaryAction.CLARIFY_SERVICE_DURATION
                    && base.serviceFamilyKnown())) {
            nextClarifyDuration++;
            nextQuestionIntent = "CLARIFY_DURATION";
            usedQIntents.add("CLARIFY_DURATION");
        } else if ("spa_price_repeat_guard".equalsIgnoreCase(skillCode)
                || combinedBotReplyLower.contains("vừa gửi ở tin trên")
                || combinedBotReplyLower.contains("ở tin nhắn trên")) {
            nextPriceRepeatGuard++;
        } else if ("spa_ambiguous_confirm_once".equalsIgnoreCase(skillCode)) {
            nextAmbiguous++;
            nextQuestionIntent = "CLARIFY_AMBIGUOUS";
            usedQIntents.add("CLARIFY_AMBIGUOUS");
        } else if (combinedBotReplyLower.contains("khung giờ nào") || combinedBotReplyLower.contains("khoảng mấy giờ")) {
            nextQuestionIntent = "CONFIRM_BOOKING_TIME";
            usedQIntents.add("CONFIRM_BOOKING_TIME");
        } else if (combinedBotReplyLower.contains("trải nghiệm thử")
                || combinedBotReplyLower.contains("thử 1 buổi")
                || combinedBotReplyLower.contains("trải nghiệm gói")
                || combinedBotReplyLower.contains("muốn trải nghiệm")) {
            nextQuestionIntent = "INVITE_ONE_SESSION_TRIAL";
            nextTrialInviteOffered = true;
            usedQIntents.add("INVITE_ONE_SESSION_TRIAL");
        }

        boolean isFaqOrDetailOrHandoffAction = decision != null
                && (decision.effectiveResponseAction() == PrimaryActionRouter.PrimaryAction.SERVICE_DETAIL_AND_FAQ_QA
                    || decision.effectiveResponseAction() == PrimaryActionRouter.PrimaryAction.SAFE_REFUSAL_OR_HANDOFF_IF_REPEATED
                    || decision.effectiveResponseAction() == PrimaryActionRouter.PrimaryAction.HARD_HANDOFF_COMPLAINT
                    || decision.effectiveResponseAction() == PrimaryActionRouter.PrimaryAction.HARD_HANDOFF_SAFETY
                    || needsHumanHandoff);
        boolean isHandoffReplyText = combinedBotReplyLower.contains("chuyển chuyên viên")
                || combinedBotReplyLower.contains("chuyển nhân viên")
                || combinedBotReplyLower.contains("chuyển quản lý")
                || combinedBotReplyLower.contains("nhờ chuyên viên");
        boolean detailFaqFulfilledPendingPriceQuote = decision != null
                && decision.effectiveResponseAction() == PrimaryActionRouter.PrimaryAction.SERVICE_DETAIL_AND_FAQ_QA
                && base.heldPackage() == null
                && anyPriceInReply
                && replyPkgDisplay != null;
        if ((!isFaqOrDetailOrHandoffAction || detailFaqFulfilledPendingPriceQuote)
                && !isHandoffReplyText
                && decision != null
                && (decision.effectiveResponseAction() == PrimaryActionRouter.PrimaryAction.RECOMMEND_ONE_PACKAGE
                    || decision.effectiveResponseAction() == PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE
                    || replyPkgDisplay != null)) {
            nextRecCount++;
            nextPkgRecommended = true;
            if (replyPkgDisplay != null) {
                nextKnownRecPkg = replyPkgDisplay;
            } else if (nextKnownRecPkg == null) {
                nextKnownRecPkg = inferDefaultPackageName(base.knownServiceFamily(), base.knownBodyAreas(), options);
            }
            if (anyPriceInReply) {
                nextLastQuotePkg = nextKnownRecPkg;
                if (replyPkgCanonical != null) {
                    nextLastQuotedPkg = replyPkgCanonical;
                    nextHeldPkg = replyPkgCanonical;
                }
                if (replyDuration != null) {
                    nextLastQuotedDur = replyDuration;
                    nextHeldDur = replyDuration;
                }
                if (replyPriceVal != null) {
                    nextLastQuotedPrice = replyPriceVal;
                }
            }
        }

        boolean isOptOut = PrimaryActionRouter.isExplicitOptOutMessage(userMessage);

        ConversationStage nextStage = switch (decision != null ? decision.effectiveResponseAction() : PrimaryActionRouter.PrimaryAction.GENERIC_SHORT_DISCOVERY) {
            case HARD_HANDOFF_SAFETY, HARD_HANDOFF_COMPLAINT, BOT_MUTED -> ConversationStage.HANDOFF;
            case CLARIFY_SERVICE_DURATION -> ConversationStage.PRICE_CLARIFY;
            case ANSWER_PRICE_ONCE -> ConversationStage.PRICE_QUOTED;
            case HANDLE_PRICE_OBJECTION_FIRST_TIME, HANDLE_PRICE_OBJECTION_REPEAT -> ConversationStage.OBJECTION;
            case EXPLAIN_UNIT_VALUE -> ConversationStage.COMPARISON;
            case RECOMMEND_ONE_PACKAGE -> ConversationStage.RECOMMENDATION;
            case ADVANCE_AFTER_ACK, CONFIRM_BOOKING_DETAILS -> ConversationStage.BOOKING;
            default -> base.priceDiscussed() ? ConversationStage.PRICE_QUOTED : ConversationStage.DISCOVERY;
        };

        String nextLastAssistantMsg = !combinedBotReplyRaw.isBlank() ? combinedBotReplyRaw : base.lastAssistantMessage();

        ConversationState updated = new ConversationState(
                greetingNowSent,
                greetingNowSent,
                nextStage,
                decision != null ? decision.detectedIntent() : base.lastIntent(),
                lastQ,
                nextQuestionIntent,
                Collections.unmodifiableSet(usedQIntents),
                effAction,
                base.known(),
                base.priceDiscussed() || anyPriceInReply,
                Collections.unmodifiableList(new ArrayList<>(prices)),
                Collections.unmodifiableList(new ArrayList<>(options)),
                nextObjectionCount,
                nextAckCount,
                base.clarificationCount() + (decision != null && decision.effectiveResponseAction() == PrimaryActionRouter.PrimaryAction.CLARIFY_SERVICE_DURATION ? 1 : 0),
                nextCondAsked,
                nextPrefAsked,
                nextClarifyService,
                nextClarifyDuration,
                nextFrustration,
                nextAmbiguous,
                nextRecCount,
                nextPriceRepeatGuard,
                base.conditionClarified(),
                base.serviceFamilyKnown(),
                nextPkgRecommended,
                base.priceRequestedInSession(),
                base.knownSymptoms(),
                base.knownBodyAreas(),
                base.knownSeverity(),
                base.knownDurationOfProblem(),
                base.knownTrigger(),
                base.knownServiceFamily(),
                base.knownPreferredPressure(),
                nextKnownRecPkg,
                nextLastQuotePkg,
                base.lastSentiment(),
                base.lastEmotion(),
                Collections.unmodifiableList(new ArrayList<>(templates)),
                (base.humanTakeover() || needsHumanHandoff) && !isOptOut,
                base.optedOut() || isOptOut,
                nextDownsellOffered,
                nextTrialInviteOffered,
                base.explicitDownsellRequest(),
                nextPkgRejectionDetected,
                nextLastQuotedPkg,
                nextLastQuotedDur,
                nextLastQuotedPrice,
                nextHeldPkg,
                nextHeldDur,
                nextDownsellPkg,
                nextDownsellDur,
                nextDownsellPrice,
                Collections.unmodifiableSet(nextRejectedDurations),
                Collections.unmodifiableSet(nextRejectedPackages),
                nextObjectionStage,
                nextTelemetrySubtype,
                base.docsHasFeedback(),
                base.docsHasRelaxationBenefit(),
                base.docsHasPrivateRoom(),
                base.neurologicalRisk(),
                base.preferredRegion(),
                Collections.unmodifiableList(new ArrayList<>(nextMentionedPackages)),
                Collections.unmodifiableList(new ArrayList<>(nextQuotedPackages)),
                nextLastQuotedPrice,
                nextAlternativeReqCount,
                base.customerCorrectionDetected(),
                base.rejectedWrongOffer(),
                base.availablePackageCatalog(),
                Collections.unmodifiableList(nextBubbleHashes),
                Collections.unmodifiableList(nextOutstanding),
                nextLastAssistantMsg
        );

        if (sessionKey != null) {
            LAST_TURN_PRE_STATE_STORE.put(sessionKey, new LastTurnPreStateSnapshot(
                    incomingHistorySize,
                    normUserMsg,
                    effectivePrev,
                    updated
            ));
            SESSION_STORE.put(sessionKey, updated);
        }
        return updated;
    }

    private static void recordUserTurnIntoOutstandingRequests(
            List<UnresolvedCustomerRequest> requests,
            String userMessage,
            String lastBotMessage,
            String activePkgContext,
            int turnIndex
    ) {
        if (requests == null || userMessage == null || userMessage.isBlank()) {
            return;
        }
        String trimmed = userMessage.strip();
        if (PrimaryActionRouter.isExplicitOptOutMessage(trimmed)
                || SentimentIntentLayer.isHardPackageRejection(trimmed)) {
            return;
        }
        if (SentimentIntentLayer.isDetailOrFaqQuestion(trimmed, lastBotMessage, activePkgContext)) {
            String refEntity = SentimentIntentLayer.extractReferencedDetailEntity(trimmed, lastBotMessage, activePkgContext);
            if (refEntity == null || refEntity.isBlank()) {
                refEntity = "service_detail";
            }
            String semIntent = resolveDetailSemanticIntent(trimmed, refEntity);
            String semQuestion = buildCanonicalSemanticQuestion(trimmed, refEntity);
            boolean hasContrast = SentimentIntentLayer.hasContrastOrClarificationPrefix(trimmed);

            int existingIdx = -1;
            for (int i = requests.size() - 1; i >= 0; i--) {
                UnresolvedCustomerRequest r = requests.get(i);
                if (r == null) continue;
                boolean sameEntity = r.referencedEntity() != null && r.referencedEntity().equalsIgnoreCase(refEntity);
                if (!r.resolved() && (sameEntity || hasContrast)) {
                    existingIdx = i;
                    break;
                }
            }
            if (existingIdx >= 0) {
                UnresolvedCustomerRequest prev = requests.get(existingIdx);
                boolean sameSemanticIntent = prev.semanticIntent() != null && prev.semanticIntent().equalsIgnoreCase(semIntent);
                int nextRepeat = sameSemanticIntent ? prev.repeatOrChallengeCount() + 1 : 0;
                requests.set(existingIdx, new UnresolvedCustomerRequest(
                        trimmed,
                        semQuestion,
                        semIntent,
                        refEntity,
                        lastBotMessage != null ? lastBotMessage : prev.contextBotExcerpt(),
                        prev.turnIndex(),
                        false,
                        false,
                        nextRepeat,
                        prev.customerChallengedAnswer() || hasContrast,
                        false,
                        prev.requiresHumanAssistance() || nextRepeat >= 3
                ));
            } else {
                requests.add(new UnresolvedCustomerRequest(
                        trimmed,
                        semQuestion,
                        semIntent,
                        refEntity,
                        lastBotMessage,
                        turnIndex,
                        false,
                        false,
                        0,
                        hasContrast,
                        false,
                        false
                ));
            }
            return;
        }

        if (SentimentIntentLayer.isComprehensionFailureMessage(trimmed)) {
            int targetIdx = -1;
            for (int i = requests.size() - 1; i >= 0; i--) {
                if (requests.get(i) != null && !requests.get(i).resolved()) {
                    targetIdx = i;
                    break;
                }
            }
            if (targetIdx < 0 && !requests.isEmpty()) {
                targetIdx = requests.size() - 1;
            }
            if (targetIdx >= 0) {
                UnresolvedCustomerRequest prev = requests.get(targetIdx);
                boolean needsHuman = prev.requiresHumanAssistance()
                        || (prev.answeredByBot() && prev.customerChallengedAnswer() && prev.repeatOrChallengeCount() >= 1);
                requests.set(targetIdx, prev.withCustomerRepeatOrChallenge(prev.rawCustomerMessage(), true, needsHuman));
            } else if (lastBotMessage != null && !lastBotMessage.isBlank()) {
                String refEntity = SentimentIntentLayer.extractReferencedDetailEntity(trimmed, lastBotMessage, activePkgContext);
                if (refEntity == null || refEntity.isBlank()) {
                    refEntity = activePkgContext != null && !activePkgContext.isBlank() ? activePkgContext : "bot_explanation";
                }
                requests.add(new UnresolvedCustomerRequest(
                        trimmed,
                        "Giải thích ngắn gọn, dễ hiểu hơn về " + refEntity,
                        "COMPREHENSION_CLARIFICATION",
                        refEntity,
                        lastBotMessage,
                        turnIndex,
                        true,
                        false,
                        1,
                        true,
                        false,
                        false
                ));
            }
            return;
        }

        if (PrimaryActionRouter.isFrustrationMessage(trimmed)) {
            for (int i = requests.size() - 1; i >= 0; i--) {
                UnresolvedCustomerRequest prev = requests.get(i);
                if (prev != null && !prev.resolved()) {
                    boolean needsHuman = prev.requiresHumanAssistance()
                            || (prev.answeredByBot() && prev.repeatOrChallengeCount() >= 2);
                    requests.set(i, prev.withCustomerRepeatOrChallenge(prev.rawCustomerMessage(), true, needsHuman));
                    break;
                }
            }
        }
    }

    private static boolean markBotTurnAgainstOutstandingRequests(
            List<UnresolvedCustomerRequest> requests,
            String botReply
    ) {
        if (requests == null || requests.isEmpty() || botReply == null || botReply.isBlank()) {
            return false;
        }
        for (int i = requests.size() - 1; i >= 0; i--) {
            UnresolvedCustomerRequest req = requests.get(i);
            if (req != null && !req.resolved()) {
                boolean answered = doesBotReplyAnswerDetailRequest(botReply, req);
                requests.set(i, req.withBotAttempt(answered, answered));
                return answered;
            }
        }
        return false;
    }

    private static void markDeliveredBotReplyAgainstOutstandingRequests(
            List<UnresolvedCustomerRequest> requests,
            String deliveredBotReply,
            boolean needsHumanHandoff
    ) {
        if (requests == null || requests.isEmpty() || deliveredBotReply == null || deliveredBotReply.isBlank()) {
            return;
        }
        for (int i = requests.size() - 1; i >= 0; i--) {
            UnresolvedCustomerRequest req = requests.get(i);
            if (req != null && !req.resolved()) {
                boolean answered = !needsHumanHandoff && doesBotReplyAnswerDetailRequest(deliveredBotReply, req);
                UnresolvedCustomerRequest updated = req.withBotAttempt(answered, answered);
                if (needsHumanHandoff && !answered) {
                    updated = new UnresolvedCustomerRequest(
                            updated.rawCustomerMessage(),
                            updated.semanticQuestion(),
                            updated.semanticIntent(),
                            updated.referencedEntity(),
                            updated.contextBotExcerpt(),
                            updated.turnIndex(),
                            false,
                            false,
                            updated.repeatOrChallengeCount(),
                            updated.customerChallengedAnswer(),
                            false,
                            true
                    );
                }
                requests.set(i, updated);
                break;
            }
        }
    }

    public static boolean doesBotReplyAnswerDetailRequest(String botReply, UnresolvedCustomerRequest req) {
        if (botReply == null || botReply.isBlank() || req == null) {
            return false;
        }
        String lower = Normalizer.normalize(botReply.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        if ((lower.contains("nhóm dịch vụ chính") && lower.contains("quan tâm"))
                || (lower.contains("gửi mức giá gói nào") && !lower.contains("bao gồm"))
                || lower.contains("chưa có thông tin chi tiết")
                || lower.contains("chưa ghi chú chi tiết mục này")
                || lower.contains("chưa được ghi rõ trong bảng thông tin")) {
            return false;
        }
        String entity = req.referencedEntity() != null ? req.referencedEntity().toLowerCase(Locale.ROOT) : "";
        String semIntent = req.semanticIntent() != null ? req.semanticIntent() : "";
        if ("COMPREHENSION_CLARIFICATION".equals(semIntent)) {
            return PRICE_NUMBER_PATTERN.matcher(lower).find()
                    || lower.contains("bao gồm")
                    || lower.contains("gồm")
                    || lower.contains("trọn gói")
                    || lower.contains("kết hợp")
                    || lower.contains("tập trung")
                    || lower.contains("quyền lợi")
                    || lower.contains("phục vụ")
                    || lower.contains("hỗ trợ");
        }
        if ("BUFFET_MENU_ITEMS_FAQ".equals(semIntent)) {
            return lower.contains("thực đơn")
                    || lower.contains("gồm các món")
                    || lower.contains("các món ăn nhẹ")
                    || (lower.contains("gồm:") && (lower.contains("ăn nhẹ") || lower.contains("buffet") || lower.contains("thực dưỡng") || lower.contains("điểm tâm")))
                    || (lower.contains("món") && lower.contains(","));
        }
        if (entity.contains("buffet") || entity.contains("thực dưỡng") || entity.contains("ăn nhẹ") || entity.contains("điểm tâm")) {
            // Post-price mechanical mention ("kèm buffet thực dưỡng rất dễ chịu ạ") is NOT an explanation of the concept
            boolean hasExplanation = lower.contains("điểm tâm")
                    || lower.contains("ăn nhẹ")
                    || lower.contains("phục hồi")
                    || lower.contains("hồi phục")
                    || lower.contains("miễn phí")
                    || lower.contains("sau liệu trình")
                    || lower.contains("sau khi làm")
                    || lower.contains("thực đơn")
                    || lower.contains("món")
                    || lower.contains("nước ép")
                    || lower.contains("trà thảo mộc")
                    || lower.contains("ấm bụng")
                    || lower.contains("bổ sung năng lượng");
            return hasExplanation;
        }
        if (entity.contains("phòng")) {
            return lower.contains("phòng") && (lower.contains("riêng") || lower.contains("rèm") || lower.contains("cặp đôi") || lower.contains("tiêu chuẩn") || lower.contains("không gian"));
        }
        if (entity.contains("thời lượng")) {
            return lower.contains("phút") || lower.contains("thời lượng") || lower.contains("tiếng");
        }
        if (entity.contains("quy trình") || entity.contains("kỹ thuật") || entity.contains("ngâm") || entity.contains("xông") || entity.contains("chườm") || entity.contains("tai")) {
            return lower.contains("quy trình") || lower.contains("bước") || lower.contains("kỹ thuật")
                    || lower.contains("bấm huyệt") || lower.contains("massage") || lower.contains("ngâm")
                    || lower.contains("thảo dược") || lower.contains("xông") || lower.contains("gồm") || lower.contains("bao gồm");
        }
        if (entity.contains("kỹ thuật viên")) {
            return lower.contains("kỹ thuật viên") || lower.contains("ktv") || lower.contains("tay nghề");
        }
        if (entity.contains("tip") || entity.contains("phụ phí")) {
            return lower.contains("tip") || lower.contains("phụ phí") || lower.contains("tùy tâm") || lower.contains("trọn gói");
        }
        if (entity.contains("đặt lịch") || entity.contains("cọc")) {
            return lower.contains("đặt lịch") || lower.contains("cọc") || lower.contains("giữ chỗ") || lower.contains("giữ giường");
        }
        return lower.contains("bao gồm") || lower.contains("gồm") || lower.contains("quyền lợi") || lower.contains("phục vụ") || lower.contains("hỗ trợ");
    }

    public static String resolveDetailSemanticIntent(String rawQuestion, String referencedEntity) {
        String norm = com.example.docmind.service.text.VietnameseQueryNormalizer.normalizeForIntentMatching(rawQuestion);
        String entity = referencedEntity != null ? referencedEntity.toLowerCase(Locale.ROOT) : "";
        if (norm.matches("(?isU).*\\b(món\\s+gì|những\\s+món\\s+gì|món\\s+nào|thực\\s+đơn|menu|ăn\\s+gì|uống\\s+gì)\\b.*")
                || entity.contains("thực đơn")) {
            return "BUFFET_MENU_ITEMS_FAQ";
        }
        if (entity.contains("buffet") || entity.contains("thực dưỡng") || entity.contains("ăn nhẹ") || entity.contains("điểm tâm")) {
            return "SERVICE_BENEFIT_OR_CONCEPT_DEFINITION";
        }
        if (entity.contains("phòng")) {
            return "SERVICE_FACILITY_OR_ROOM_FAQ";
        }
        if (entity.contains("thời lượng")) {
            return "SERVICE_DURATION_FAQ";
        }
        if (entity.contains("quy trình") || entity.contains("kỹ thuật") || entity.contains("ngâm") || entity.contains("xông") || entity.contains("chườm") || entity.contains("tai")) {
            return "SERVICE_PROCESS_OR_TECHNIQUE_FAQ";
        }
        if (entity.contains("tip") || entity.contains("cọc") || entity.contains("đặt lịch") || entity.contains("kỹ thuật viên") || entity.contains("địa chỉ")) {
            return "SERVICE_POLICY_OR_FACILITY_FAQ";
        }
        return "SERVICE_INCLUDED_BENEFITS_FAQ";
    }

    public static String buildCanonicalSemanticQuestion(String rawQuestion, String referencedEntity) {
        String semIntent = resolveDetailSemanticIntent(rawQuestion, referencedEntity);
        String entity = (referencedEntity != null && !referencedEntity.isBlank()) ? referencedEntity : "chi tiết dịch vụ";
        return switch (semIntent) {
            case "BUFFET_MENU_ITEMS_FAQ" -> "Thực đơn buffet thực dưỡng / điểm tâm ăn nhẹ gồm những món gì?";
            case "SERVICE_BENEFIT_OR_CONCEPT_DEFINITION" -> "Giải thích ý nghĩa và quyền lợi của " + entity + " trong gói dịch vụ";
            case "SERVICE_FACILITY_OR_ROOM_FAQ" -> "Thông tin về không gian phòng và sự riêng tư tại cơ sở";
            case "SERVICE_DURATION_FAQ" -> "Thông tin về thời lượng thực hiện của gói dịch vụ";
            case "SERVICE_PROCESS_OR_TECHNIQUE_FAQ" -> "Thông tin về quy trình và kỹ thuật thực hiện của " + entity;
            case "SERVICE_POLICY_OR_FACILITY_FAQ" -> "Thông tin chính sách và tiện ích liên quan đến " + entity;
            default -> "Thông tin chi tiết về các quyền lợi bao gồm trong " + entity;
        };
    }

    public static String detectPreferredRegion(String lowerText, String currentRegion) {
        String fallback = (currentRegion != null && !currentRegion.isBlank()) ? currentRegion : "HN";
        if (lowerText == null || lowerText.isBlank()) {
            return fallback;
        }
        if (lowerText.matches("(?isU).*\\b(sài\\s+gòn|sai\\s+gon|quận\\s+1|quan\\s+1|tp\\.?\\s*hcm|hồ\\s+chí\\s+minh|nguyễn\\s+công\\s+trứ|miền\\s+nam)\\b.*")) {
            return "SG";
        }
        if (lowerText.matches("(?isU).*\\b(hà\\s+nội|ha\\s+noi|đống\\s+đa|cầu\\s+giấy|văn\\s+quán|hà\\s+đông|miền\\s+bắc)\\b.*")) {
            return "HN";
        }
        return fallback;
    }

    private static boolean hasAlternativePackageWithSameDuration(
            List<PackageCatalogService.PackageSummary> catalog,
            String durationStr,
            String rejectedPkgCanonical
    ) {
        if (catalog == null || catalog.size() < 2 || durationStr == null || durationStr.isBlank()) {
            return false;
        }
        int targetDur = parseDurationInt(durationStr);
        if (targetDur <= 0) {
            return false;
        }
        int matchCount = 0;
        for (PackageCatalogService.PackageSummary pkg : catalog) {
            if (pkg == null || pkg.durationMinutes() != targetDur) {
                continue;
            }
            String pkgCanon = toCanonicalPackageCode(pkg.displayName(), String.valueOf(pkg.durationMinutes()));
            if (rejectedPkgCanonical == null || !rejectedPkgCanonical.equalsIgnoreCase(pkgCanon)) {
                matchCount++;
            }
        }
        return matchCount > 0;
    }

    public static String shortHash(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String norm = Normalizer.normalize(text, Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .strip();
        return Integer.toHexString(norm.hashCode());
    }

    private static void appendBubbleHashes(List<String> hashes, String text) {
        if (hashes == null || text == null || text.isBlank()) {
            return;
        }
        String hash = shortHash(text);
        if (!hashes.contains(hash)) {
            hashes.add(hash);
            while (hashes.size() > 12) {
                hashes.remove(0);
            }
        }
    }

    public static String extractDurationFromText(String lowerText) {
        if (lowerText == null || lowerText.isBlank()) {
            return null;
        }
        if (lowerText.contains("1 tiếng rưỡi") || lowerText.contains("90 phút") || lowerText.contains("90p")) {
            return "90";
        }
        Matcher m = DURATION_ONLY_PATTERN.matcher(lowerText);
        String lastFound = null;
        while (m.find()) {
            lastFound = m.group(1);
        }
        return lastFound;
    }

    public static int parseDurationInt(String dur) {
        if (dur == null || dur.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(dur.replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static BigDecimal parsePriceToBigDecimal(String rawPrice) {
        if (rawPrice == null || rawPrice.isBlank()) {
            return null;
        }
        String lower = rawPrice.toLowerCase(Locale.ROOT).strip();
        String digits = lower.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            return null;
        }
        try {
            long val = Long.parseLong(digits);
            if (val < 10_000L && lower.contains("k")) {
                val *= 1000L;
            }
            return BigDecimal.valueOf(val);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final Pattern BOT_PACKAGE_MENTION_PATTERN = Pattern.compile(
            "(?iU)(?:gói\\s+dịch\\s+vụ\\s+|dịch\\s+vụ\\s+|liệu\\s+trình\\s+|gói\\s+|thử\\s+1\\s+buổi\\s+|thử\\s+)"
            + "((?:(?:[a-z]{2,3}\\d{2}\\s+)?(?:massage|dưỡng\\s+sinh|ngâm|vệ\\s+sinh|chăm\\s+sóc|trị\\s+liệu|gội\\s+đầu|niềng|bọc|tẩy\\s+trắng|nhổ|trồng|cạo\\s+vôi|lấy\\s+tủy|combo|sofa|đệm|nệm|bàn|ghế))[^,.?!:;()\\n]{2,85}?)"
            + "(?=\\s+(?:bên\\s+em|có\\s+giá|với\\s+giá|giá\\s+|nhé|ạ|trước|sẽ|giúp|là\\s+|\\(|,|\\.|\\?|$))"
    );

    public static List<String> extractAllCanonicalPackagesFromBotText(String lower, String fallbackDuration) {
        if (lower == null || lower.isBlank()) {
            return List.of();
        }
        if ((lower.contains("quan tâm") || lower.contains("nhóm dịch vụ"))
                && lower.contains("hay") && lower.contains("?")
                && !lower.contains("có giá") && fallbackDuration == null) {
            return List.of();
        }
        Set<String> result = new LinkedHashSet<>();
        Matcher m = BOT_PACKAGE_MENTION_PATTERN.matcher(lower);
        while (m.find()) {
            String rawPkg = m.group(1).replaceAll("\\s+", " ").strip();
            rawPkg = rawPkg.replaceAll("(?iU)\\s+(?:bên\\s+em|trước|nhé|ạ)$", "").strip();
            String localDur = extractDurationFromText(rawPkg);
            if (localDur == null) {
                localDur = fallbackDuration;
            }
            if (localDur != null && !rawPkg.contains("phút") && !rawPkg.contains("tháng") && !rawPkg.contains("buổi")) {
                rawPkg = rawPkg + " " + localDur + " phút";
            }
            String canon = toCanonicalPackageCode(rawPkg, localDur);
            if (canon != null) {
                result.add(canon);
            }
        }
        return new ArrayList<>(result);
    }

    public static String extractPackageDisplayFromBotText(String lower, String duration) {
        if (lower == null || lower.isBlank()) {
            return null;
        }
        // Không trích xuất package từ câu hỏi menu nhóm dịch vụ rộng ở Turn 1
        if ((lower.contains("quan tâm") || lower.contains("nhóm dịch vụ"))
                && lower.contains("hay") && lower.contains("?")
                && !lower.contains("có giá") && duration == null) {
            return null;
        }
        String dur = duration != null ? duration : extractDurationFromText(lower);
        boolean hasRecommendationCue = dur != null
                || lower.contains("gợi ý")
                || lower.contains("phù hợp nhất")
                || lower.contains("có giá")
                || lower.contains("với giá")
                || lower.contains("trải nghiệm thử");
        if (!hasRecommendationCue) {
            return null;
        }
        if (dur != null && lower.contains("gói ngắn " + dur + " phút")) {
            String shortPkg = "gói ngắn " + dur + " phút";
            CANONICAL_TO_DISPLAY_CACHE.put("goi_ngan_" + dur, shortPkg);
            return shortPkg;
        }
        Matcher m = BOT_PACKAGE_MENTION_PATTERN.matcher(lower);
        if (m.find()) {
            String rawPkg = m.group(1).replaceAll("\\s+", " ").strip();
            rawPkg = rawPkg.replaceAll("(?iU)\\s+(?:bên\\s+em|trước|nhé|ạ)$", "").strip();
            String localDur = extractDurationFromText(rawPkg);
            if (localDur == null) {
                localDur = dur;
            }
            if (localDur != null && !rawPkg.contains("phút") && !rawPkg.contains("tháng") && !rawPkg.contains("buổi")) {
                rawPkg = rawPkg + " " + localDur + " phút";
            }
            toCanonicalPackageCode(rawPkg, localDur);
            return rawPkg;
        }
        return null;
    }

    public static String toCanonicalPackageCode(String pkgDisplay, String duration) {
        if ((pkgDisplay == null || pkgDisplay.isBlank()) && (duration == null || duration.isBlank())) {
            return null;
        }
        String lower = pkgDisplay != null ? pkgDisplay.toLowerCase(Locale.ROOT).strip() : "";
        String dur = duration != null ? duration : extractDurationFromText(lower);
        if (lower.matches("^[a-z0-9_]+$") && lower.contains("_")) {
            return lower;
        }
        String ascii = SentimentIntentLayer.removeDiacritics(lower);
        ascii = ascii.replaceFirst("^(?:goi\\s+dich\\s+vu|dich\\s+vu|lieu\\s+trinh|goi)\\s+", "");
        ascii = ascii.replaceAll("\\b(?:am|ngoai\\s+thu\\s+gian|toan\\s+than|thu\\s+gian)\\b", " ");
        ascii = ascii.replaceAll("\\b\\d{1,3}\\s*(?:phut|p)\\b", " ");
        String slug = ascii.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (slug.isEmpty()) {
            slug = "package";
        }
        String canonical = (dur != null && !slug.endsWith("_" + dur)) ? slug + "_" + dur : slug;
        if (!lower.isEmpty()) {
            String displayWithDur = (dur != null && !lower.contains("phút") && !lower.contains("tháng"))
                    ? lower + " " + dur + " phút"
                    : lower;
            CANONICAL_TO_DISPLAY_CACHE.put(canonical, displayWithDur);
        }
        return canonical;
    }

    public static String toDisplayPackageName(String canonicalOrDisplay, String fallbackDuration) {
        if (canonicalOrDisplay == null || canonicalOrDisplay.isBlank()) {
            return fallbackDuration != null ? "gói " + fallbackDuration + " phút" : "gói tiêu chuẩn";
        }
        String trimmed = canonicalOrDisplay.strip();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        String cached = CANONICAL_TO_DISPLAY_CACHE.get(lower);
        if (cached != null && !cached.isBlank()) {
            return cached;
        }
        if (!lower.contains("_")) {
            if (fallbackDuration != null && !lower.contains("phút") && !lower.contains("tháng")) {
                return trimmed + " " + fallbackDuration + " phút";
            }
            return trimmed;
        }
        String dur = extractDurationFromText(lower.replace('_', ' '));
        if (dur == null) {
            Matcher dm = Pattern.compile("_(\\d{2,3})$").matcher(lower);
            if (dm.find()) {
                dur = dm.group(1);
            } else {
                dur = fallbackDuration;
            }
        }
        for (Map.Entry<String, String> entry : CANONICAL_TO_DISPLAY_CACHE.entrySet()) {
            String keyPrefix = entry.getKey().replaceAll("_\\d+$", "");
            String targetPrefix = lower.replaceAll("_\\d+$", "");
            if (!keyPrefix.isEmpty() && keyPrefix.equals(targetPrefix) && dur != null) {
                return entry.getValue().replaceAll("\\b\\d{2,3}\\s*phút\\b", dur + " phút");
            }
        }
        String readable = lower.replaceAll("_\\d+$", "").replace('_', ' ').strip();
        return dur != null ? readable + " " + dur + " phút" : readable;
    }

    private static boolean detectDocsHasFeedback(List<ReciprocalRankFusion.FusedChunk> fusedChunks) {
        if (fusedChunks == null || fusedChunks.isEmpty()) {
            return false;
        }
        for (ReciprocalRankFusion.FusedChunk fc : fusedChunks) {
            if (fc == null || fc.chunk() == null || fc.chunk().text() == null) continue;
            String t = fc.chunk().text().toLowerCase(Locale.ROOT);
            if (t.contains("phản hồi khá tốt") || t.contains("phản hồi tốt") || t.contains("khách hàng đánh giá") || t.contains("testimonial")) {
                return true;
            }
        }
        return false;
    }

    private static boolean detectDocsHasRelaxationBenefit(List<ReciprocalRankFusion.FusedChunk> fusedChunks) {
        if (fusedChunks == null || fusedChunks.isEmpty()) {
            return true;
        }
        for (ReciprocalRankFusion.FusedChunk fc : fusedChunks) {
            if (fc == null || fc.chunk() == null || fc.chunk().text() == null) continue;
            String t = fc.chunk().text().toLowerCase(Locale.ROOT);
            if (t.contains("làm mềm cơ") || t.contains("thả lỏng") || t.contains("thư giãn") || t.contains("dễ chịu")) {
                return true;
            }
        }
        return false;
    }

    private static boolean detectDocsHasPrivateRoom(List<ReciprocalRankFusion.FusedChunk> fusedChunks) {
        if (fusedChunks == null || fusedChunks.isEmpty()) {
            return false;
        }
        for (ReciprocalRankFusion.FusedChunk fc : fusedChunks) {
            if (fc == null || fc.chunk() == null || fc.chunk().text() == null) continue;
            String t = fc.chunk().text().toLowerCase(Locale.ROOT);
            if (t.contains("phòng riêng")) {
                return true;
            }
        }
        return false;
    }

    public static String normalizeScopeToken(String rawScope) {
        if (rawScope == null || rawScope.isBlank()) {
            return "";
        }
        String s = rawScope.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
        if (s.equals("bắp chân") || s.equals("bàn chân")) return "chân";
        if (s.equals("đầu gối") || s.equals("khớp gối")) return "gối";
        if (s.equals("cánh tay") || s.equals("bàn tay")) return "tay";
        if (s.equals("thắt lưng")) return "lưng";
        if (s.equals("chân răng") || s.equals("răng hàm") || s.equals("răng khôn") || s.equals("răng cửa")) return "răng";
        return s;
    }

    private static void addNormalizedAtomicScopes(Set<String> scopes, String rawScope) {
        if (rawScope == null || rawScope.isBlank()) {
            return;
        }
        String norm = normalizeScopeToken(rawScope);
        if ("cơ".equals(norm)) {
            return;
        }
        if (norm.contains(" ") && (norm.contains("cổ") || norm.contains("vai") || norm.contains("gáy"))) {
            for (String part : norm.split("\\s+")) {
                if (!part.isBlank()) {
                    scopes.add(part);
                }
            }
            return;
        }
        scopes.add(norm);
    }

    /**
     * Trích xuất toàn bộ vùng tác động / phạm vi mục tiêu ({@code targetScopes}) từ câu nói của khách,
     * hỗ trợ cả cấu trúc xuôi ("đau lưng") và cấu trúc đảo ("chân đau") sau khi đã lọc mệnh đề phản bác.
     */
    public static List<String> extractTargetScopes(String text, List<String> domainKeywords) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String cleanText = SentimentIntentLayer.stripMismatchCritiqueClause(text);
        Set<String> scopes = new LinkedHashSet<>();
        Matcher needM = MULTI_ENTITY_NEED_PATTERN.matcher(cleanText);
        while (needM.find()) {
            String fwdScope = needM.group("fwdScope");
            String fwdScopeChain = needM.group("fwdScopeChain");
            String invScope = needM.group("invScope");
            if (fwdScope != null && !fwdScope.isBlank()) {
                addNormalizedAtomicScopes(scopes, fwdScope);
            }
            if (fwdScopeChain != null && !fwdScopeChain.isBlank()) {
                addNormalizedAtomicScopes(scopes, fwdScopeChain);
            }
            if (invScope != null && !invScope.isBlank()) {
                addNormalizedAtomicScopes(scopes, invScope);
            }
        }
        String maskedText = cleanText.replaceAll("(?iU)\\bchân\\s+răng\\b", "răng")
                .replaceAll("(?iU)\\bchân\\s+không\\b", " ")
                .replaceAll("(?iU)\\b(?:quyền\\s+lợi|lợi\\s+ích|tiện\\s+lợi|thuận\\s+lợi|có\\s+lợi)\\b", " ")
                .replaceAll("(?iU)\\b(?:gội|lần|bắt|ban)\\s+đầu\\b", " ");
        Matcher areaM = BODY_AREA_TOKEN_PATTERN.matcher(maskedText);
        while (areaM.find()) {
            String raw = areaM.group(1);
            if (raw != null && !raw.isBlank()) {
                addNormalizedAtomicScopes(scopes, raw);
            }
        }
        if (domainKeywords != null && !domainKeywords.isEmpty()) {
            String lowerClean = Normalizer.normalize(maskedText, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
            for (String kw : domainKeywords) {
                if (kw == null || kw.isBlank()) continue;
                String kwLower = Normalizer.normalize(kw.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
                if (kwLower.contains("gội đầu")) continue;
                if (kwLower.split("\\s+").length <= 2 && lowerClean.contains(kwLower)) {
                    Matcher km = BODY_AREA_TOKEN_PATTERN.matcher(kwLower);
                    if (km.find()) {
                        addNormalizedAtomicScopes(scopes, km.group(1));
                    }
                }
            }
        }
        return new ArrayList<>(scopes);
    }

    /**
     * Trích xuất toàn bộ danh sách vấn đề/nhu cầu của khách hàng ({@code extractedNeeds})
     * theo đúng thứ tự xuất hiện, tự động chuẩn hóa cấu trúc đảo tiếng Việt ("chân đau" -> "đau chân").
     */
    public static List<String> extractAllNeeds(String text, List<String> domainKeywords) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String cleanText = SentimentIntentLayer.stripMismatchCritiqueClause(text);
        String lower = Normalizer.normalize(cleanText, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        Set<String> needs = new LinkedHashSet<>();

        Matcher m = MULTI_ENTITY_NEED_PATTERN.matcher(lower);
        while (m.find()) {
            String fwdMod = m.group("fwdMod");
            String fwdScope = m.group("fwdScope");
            String fwdScopeChain = m.group("fwdScopeChain");
            String fwdSide = m.group("fwdSide");
            String invScope = m.group("invScope");
            String invMod = m.group("invMod");
            String standalone = m.group("standalone");

            if (fwdMod != null && fwdScope != null) {
                String mod = fwdMod.replaceAll("\\s+", " ").strip();
                String scope = fwdScope.replaceAll("\\s+", " ").strip();
                if (mod.contains("nhói") && "vai".equals(scope) && fwdSide != null) {
                    needs.add("mỏi và thỉnh thoảng nhói vùng vai");
                } else {
                    needs.add(mod + " " + scope);
                }
                if (fwdScopeChain != null && !fwdScopeChain.isBlank()) {
                    String chainScope = fwdScopeChain.replaceAll("\\s+", " ").strip();
                    needs.add(mod + " " + chainScope);
                }
            } else if (invScope != null && invMod != null) {
                String mod = invMod.replaceAll("\\s+", " ").strip();
                String scope = invScope.replaceAll("\\s+", " ").strip();
                if ("ố vàng".equals(mod) || "xỉn màu".equals(mod) || "mẻ".equals(mod)) {
                    needs.add(scope + " " + mod);
                } else {
                    needs.add(mod + " " + scope);
                }
            } else if (standalone != null) {
                String st = standalone.replaceAll("\\s+", " ").strip();
                if ("stress".equals(st)) {
                    needs.add("căng thẳng");
                } else {
                    needs.add(st);
                }
            }
        }

        if (needs.isEmpty() && domainKeywords != null) {
            List<String> sortedKws = new ArrayList<>();
            for (String kw : domainKeywords) {
                if (kw != null && !kw.isBlank()) {
                    sortedKws.add(kw.strip().toLowerCase(Locale.ROOT));
                }
            }
            sortedKws.sort(java.util.Comparator.comparingInt(String::length).reversed());
            for (String kwLower : sortedKws) {
                if (lower.contains(kwLower)) {
                    boolean alreadyCovered = needs.stream().anyMatch(existing -> existing.contains(kwLower));
                    if (!alreadyCovered) {
                        if (lower.contains("vùng " + kwLower)) {
                            needs.add("mỏi vùng " + kwLower);
                        } else if (!isServiceOrModalityKeyword(kwLower)
                                && (lower.contains("đau") || lower.contains("mỏi") || lower.contains("nhức")
                                    || lower.contains("tê") || lower.contains("bị") || lower.contains("ố")
                                    || lower.contains("sâu") || lower.contains("viêm") || lower.contains("sưng"))) {
                            needs.add(kwLower);
                        }
                    }
                }
            }
        }
        return new ArrayList<>(needs);
    }

    public static boolean isServiceOrModalityKeyword(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String low = Normalizer.normalize(text.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        return low.contains("massage") || low.contains("mát xa") || low.contains("xoa bóp")
                || low.contains("ngâm") || low.contains("thảo dược") || low.contains("bồn")
                || low.contains("xông") || low.contains("gội") || low.contains("dưỡng sinh")
                || low.contains("vệ sinh tai") || low.contains("lấy ráy") || low.contains("tẩy da")
                || low.contains("chăm sóc") || low.contains("liệu trình") || low.contains("buffet")
                || low.contains("thực dưỡng") || low.contains("niềng") || low.contains("bọc")
                || low.contains("tẩy trắng") || low.contains("trồng răng") || low.contains("nhổ răng")
                || low.contains("cạo vôi") || low.contains("lấy tủy") || low.contains("sofa");
    }

    public static String joinNeedsNaturally(List<String> needs) {
        if (needs == null || needs.isEmpty()) {
            return null;
        }
        List<String> filtered = new ArrayList<>();
        for (String n : needs) {
            if (n != null && !n.isBlank() && !isServiceOrModalityKeyword(n)) {
                filtered.add(n);
            }
        }
        if (filtered.isEmpty()) {
            return null;
        }
        if (filtered.size() == 1) {
            return filtered.get(0);
        }
        if (filtered.size() == 2) {
            return filtered.get(0) + " và " + filtered.get(1);
        }
        return String.join(", ", filtered.subList(0, filtered.size() - 1)) + " và " + filtered.get(filtered.size() - 1);
    }

    public static String extractProblemOrNeedPhrase(String text, List<String> domainKeywords) {
        List<String> needs = extractAllNeeds(text, domainKeywords);
        return joinNeedsNaturally(needs);
    }

    public static String resolveServiceFamilyFromText(String lowerText, Set<String> knownBodyAreas) {
        return resolveServiceFamilyFromText(lowerText, knownBodyAreas, List.of());
    }

    public static String resolveServiceFamilyFromText(String lowerText, Set<String> knownBodyAreas, List<String> domainKeywords) {
        String lower = lowerText != null ? lowerText : "";
        if (lower.matches("(?isU).*\\b(gội\\s+đầu(?:\\s+dưỡng\\s+sinh)?)\\b.*")) {
            return "gội đầu";
        }
        if (lower.matches("(?isU).*\\b(vệ\\s+sinh\\s+tai)\\b.*")) {
            return "ear_care";
        }
        if (lower.matches("(?isU).*\\b(ngâm\\s+chân|ngâm\\s+bồn|bồn\\s+thảo\\s+dược|thảo\\s+dược)\\b.*")) {
            return "herbal_soak";
        }
        if (lower.matches("(?isU).*\\b(massage\\s+body|toàn\\s+thân|đá\\s+nóng)\\b.*")) {
            return "body";
        }
        if (lower.matches("(?isU).*\\b(massage|mát\\s+xa|xoa\\s+bóp)\\b.*")) {
            if (knownBodyAreas != null && (knownBodyAreas.contains("vai") || knownBodyAreas.contains("gáy") || knownBodyAreas.contains("cổ"))) {
                return "neck_shoulder";
            }
            return "massage_generic";
        }
        if (lower.matches("(?isU).*\\b(niềng\\s+răng|bọc\\s+răng\\s+sứ|tẩy\\s+trắng\\s+răng|trồng\\s+răng|nhổ\\s+răng|cạo\\s+vôi\\s+răng|lấy\\s+tủy)\\b.*")) {
            Matcher dm = Pattern.compile("(?isU)\\b(niềng\\s+răng|bọc\\s+răng\\s+sứ|tẩy\\s+trắng\\s+răng|trồng\\s+răng|nhổ\\s+răng|cạo\\s+vôi\\s+răng|lấy\\s+tủy)\\b").matcher(lower);
            if (dm.find()) {
                return dm.group(1).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            }
        }
        if (domainKeywords != null) {
            for (String kw : domainKeywords) {
                if (kw == null || kw.isBlank()) continue;
                String kwLower = kw.strip().toLowerCase(Locale.ROOT);
                if (kwLower.split("\\s+").length >= 2 && lower.contains(kwLower)) {
                    return kwLower;
                }
            }
        }
        if (knownBodyAreas != null && !knownBodyAreas.isEmpty()) {
            boolean hasNeckShoulder = knownBodyAreas.contains("vai") || knownBodyAreas.contains("gáy") || knownBodyAreas.contains("cổ");
            boolean hasLowerLimb = knownBodyAreas.contains("chân") || knownBodyAreas.contains("gối");
            if (hasNeckShoulder && !hasLowerLimb) {
                return "neck_shoulder";
            }
            if (hasLowerLimb && !hasNeckShoulder) {
                return "lower_limb_care";
            }
            if (knownBodyAreas.contains("lưng") || knownBodyAreas.contains("toàn thân") || knownBodyAreas.contains("body")) {
                return "body";
            }
        }
        return null;
    }

    public String knownServiceInterest() {
        if ("herbal_soak".equals(knownServiceFamily)) {
            return "ngâm bồn thảo dược";
        }
        if ("ear_care".equals(knownServiceFamily)) {
            return "vệ sinh tai ngoài";
        }
        if ("gội đầu".equals(knownServiceFamily)) {
            return "gội đầu dưỡng sinh";
        }
        if ("body".equals(knownServiceFamily)) {
            return "massage toàn thân";
        }
        if ("neck_shoulder".equals(knownServiceFamily)) {
            return "massage cổ vai";
        }
        if (known != null && known.itemInterest() != null && !known.itemInterest().isBlank()) {
            return known.itemInterest();
        }
        return knownServiceFamily;
    }

    public String lastDiscussedPackageId() {
        String offered = lastOfferedPackageId();
        if (offered != null && !offered.isBlank()) {
            return offered;
        }
        if (knownRecommendedPackage != null && !knownRecommendedPackage.isBlank()) {
            String canon = toCanonicalPackageCode(knownRecommendedPackage, extractDurationFromText(knownRecommendedPackage));
            return canon != null ? canon : knownRecommendedPackage;
        }
        if ("herbal_soak".equals(knownServiceFamily) && availablePackageCatalog != null) {
            for (PackageCatalogService.PackageSummary s : availablePackageCatalog) {
                if (s != null && s.includesSummary() != null
                        && s.includesSummary().toLowerCase(Locale.ROOT).contains("ngâm bồn")) {
                    return s.packageUid() != null ? s.packageUid() : s.code();
                }
            }
        }
        return null;
    }

    public String lastQuotedPackageId() {
        if (!priceDiscussed) {
            return null;
        }
        return lastOfferedPackageId();
    }

    public String lastBotQuestionType() {
        String lastQ = lastQuestion != null ? lastQuestion.toLowerCase(Locale.ROOT) : "";
        String lastMsg = lastAssistantMessage != null ? lastAssistantMessage.toLowerCase(Locale.ROOT) : "";
        if (lastQ.contains("khung giờ") || lastQ.contains("mấy giờ") || lastQ.contains("giữ lịch")
                || lastQ.contains("giữ chỗ") || lastQ.contains("số điện thoại") || lastQ.contains("sđt")
                || "CONFIRM_BOOKING_DETAILS".equals(lastBotAction)) {
            return "ASK_BOOKING_TIME";
        }
        if (trialInviteOffered
                || "INVITE_ONE_SESSION_TRIAL".equals(lastQuestionIntent)
                || lastQ.contains("thử 1 buổi") || lastQ.contains("thử trải nghiệm")
                || lastQ.contains("phù hợp với thời gian") || lastQ.contains("muốn thử")
                || lastQ.contains("muốn trải nghiệm")) {
            return "INVITE_TRIAL";
        }
        if ("ASK_CONDITION".equals(lastQuestionIntent)
                || "ACKNOWLEDGE_AND_ASK_PREFERENCE".equals(lastBotAction)
                || lastQ.contains("mới bị") || lastQ.contains("lâu chưa") || lastQ.contains("kéo dài")
                || lastQ.contains("thi thoảng") || lastQ.contains("tần suất")) {
            return "ASK_CONDITION";
        }
        if ("CLARIFY_SERVICE_TYPE".equals(lastQuestionIntent)
                || "CLARIFY_DURATION".equals(lastQuestionIntent)
                || "CLARIFY_SERVICE_DURATION".equals(lastBotAction)
                || (lastQ.contains("dịch vụ nào") && lastQ.contains("hay"))) {
            return "CLARIFY_SERVICE_TYPE";
        }
        if (lastQ.contains("tìm hiểu thêm") || lastQ.contains("hỗ trợ gì") || lastQ.contains("đúng không")) {
            return "CONFIRM_INFO";
        }
        if (greetingSent && (lastQ.contains("dịch vụ gì") || lastQ.contains("dịch vụ nào") || lastMsg.contains("quan tâm"))) {
            return "ASK_SERVICE_INTEREST";
        }
        return "NONE";
    }

    public boolean botApologizedInLastTurn() {
        if (lastAssistantMessage == null || lastAssistantMessage.isBlank()) {
            return false;
        }
        return lastAssistantMessage.toLowerCase(Locale.ROOT).contains("xin lỗi");
    }

    public String stateSnapshotSummary() {
        return String.format(
                Locale.ROOT,
                "{serviceInterest=%s, symptoms=%s, bodyAreas=%s, discussedPkg=%s, quotedPkg=%s, quotedPrice=%s, priceReq=%s, priceDiscussed=%s, lastAction=%s, lastQType=%s}",
                knownServiceInterest(),
                knownSymptoms,
                knownBodyAreas,
                lastDiscussedPackageId(),
                lastQuotedPackageId(),
                priceDiscussed ? lastOfferedPrice() : null,
                priceRequestedInSession,
                priceDiscussed,
                lastBotAction,
                lastBotQuestionType()
        );
    }

    public static String inferDefaultPackageName(String serviceFamily, List<String> bodyAreas, Set<String> recommendedOptions) {
        return inferDefaultPackageName(serviceFamily, bodyAreas != null ? new LinkedHashSet<>(bodyAreas) : Set.of(), recommendedOptions);
    }

    public static String inferDefaultPackageName(String serviceFamily, Set<String> bodyAreas, Set<String> recommendedOptions) {
        for (Map.Entry<String, String> entry : CANONICAL_TO_DISPLAY_CACHE.entrySet()) {
            if (recommendedOptions != null) {
                for (String opt : recommendedOptions) {
                    if (entry.getValue().contains(opt)) {
                        return entry.getValue();
                    }
                }
            }
        }
        if (serviceFamily != null && !serviceFamily.isBlank()
                && !"neck_shoulder".equals(serviceFamily)
                && !"lower_limb_care".equals(serviceFamily)
                && !"body".equals(serviceFamily)
                && !"herbal_soak".equals(serviceFamily)
                && !"ear_care".equals(serviceFamily)
                && !"massage_generic".equals(serviceFamily)) {
            return serviceFamily;
        }
        if (recommendedOptions != null && !recommendedOptions.isEmpty()) {
            return "gói " + recommendedOptions.iterator().next();
        }
        return null;
    }

    private static String buildSessionKey(UUID pageId, String senderId) {
        return buildSessionKey(pageId, senderId, null);
    }

    public static String buildSessionKey(UUID pageId, String senderId, String conversationId) {
        String pagePart = pageId != null ? pageId.toString() : "default";
        String senderPart = (senderId != null && !senderId.isBlank()) ? senderId.strip() : null;
        String convPart = (conversationId != null && !conversationId.isBlank()) ? conversationId.strip() : null;
        if (senderPart == null && convPart == null) {
            return null;
        }
        if (convPart != null) {
            return pagePart + "::" + (senderPart != null ? senderPart : "anon") + "::" + convPart;
        }
        return pagePart + "::" + senderPart;
    }

    public int retentionAttemptCount() {
        return downsellOffered ? 1 : 0;
    }

    public String lastServiceOffered() {
        if (heldPackage != null && !heldPackage.isBlank()) {
            return heldPackage;
        }
        if (lastQuotedPackage != null && !lastQuotedPackage.isBlank()) {
            return lastQuotedPackage;
        }
        return knownRecommendedPackage;
    }

    public BigDecimal lastPriceOffered() {
        return lastQuotedPrice != null ? lastQuotedPrice : downsellPrice;
    }

    public String lastObjectionResponse() {
        if (stage == ConversationStage.OBJECTION
                || (lastBotAction != null && lastBotAction.contains("OBJECTION"))
                || "STAGE_3_GRACEFUL_EXIT".equals(objectionStage)) {
            return lastAssistantMessage;
        }
        return null;
    }

    public List<String> unresolvedQuestions() {
        if (outstandingRequests == null || outstandingRequests.isEmpty()) {
            return List.of();
        }
        List<String> unresolved = new ArrayList<>();
        for (UnresolvedCustomerRequest item : outstandingRequests) {
            if (item != null && !item.resolved() && item.rawCustomerMessage() != null && !item.rawCustomerMessage().isBlank()) {
                unresolved.add(item.rawCustomerMessage());
            }
        }
        return Collections.unmodifiableList(unresolved);
    }

    /**
     * Danh sách các góc độ giá trị (Progressive Value Reveal: VALUE_ANGLE_1, VALUE_ANGLE_2)
     * đã thực sự được gửi tới khách hàng trong hội thoại.
     */
    public List<String> deliveredValueAngles() {
        if (usedTemplates == null || usedTemplates.isEmpty()) {
            return List.of();
        }
        Set<String> angles = new LinkedHashSet<>();
        for (String tpl : usedTemplates) {
            if ("VALUE_ANGLE_1".equals(tpl) || "VALUE_ANGLE_2".equals(tpl)) {
                angles.add(tpl);
            }
        }
        return List.copyOf(angles);
    }

    private static void purgeNegatedSymptomsAndScopes(
            String rawMessage,
            String cleanContent,
            Set<String> symptoms,
            Set<String> areas,
            List<String> domainKeywords
    ) {
        List<String> negatedClauses = SentimentIntentLayer.extractNegatedSymptomClauses(rawMessage);
        if (negatedClauses.isEmpty()) {
            return;
        }
        Set<String> positiveSymptoms = new LinkedHashSet<>();
        Matcher posSym = SYMPTOM_TOKEN_PATTERN.matcher(cleanContent);
        while (posSym.find()) {
            positiveSymptoms.add(posSym.group(1).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip());
        }
        Set<String> positiveScopes = new LinkedHashSet<>(extractTargetScopes(cleanContent, domainKeywords));
        for (String clause : negatedClauses) {
            Matcher negSym = SYMPTOM_TOKEN_PATTERN.matcher(clause);
            while (negSym.find()) {
                String ns = negSym.group(1).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
                if (!positiveSymptoms.contains(ns)) {
                    symptoms.remove(ns);
                }
            }
            for (String negScope : extractTargetScopes(clause, domainKeywords)) {
                if (!positiveScopes.contains(negScope)) {
                    areas.remove(negScope);
                }
            }
        }
    }
}
