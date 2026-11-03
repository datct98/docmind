package com.example.docmind.service.bot;

import com.example.docmind.dto.PagePersonaConfig;
import com.example.docmind.dto.bot.AgentSkillDto;
import com.example.docmind.dto.bot.CustomerInsightDto;
import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.service.retrieval.PromptBuilder;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
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
 * Hệ thần kinh {@code AgentBrainService} dựa trên bằng chứng & điều hướng đa ngành (Checkpoint C3-A):
 * <ul>
 *   <li><b>Minh bạch nguồn gốc suy luận ({@link BrainReasoningProvenance}):</b> Báo cáo rõ nguồn tính toán của
 *       {@code stage}, {@code archetype}, {@code confidence}, {@code skill} và {@code hypothesis} trong cả
 *       {@link #recallAndAdvise} lẫn {@link #retainTurnWithReflection}.</li>
 *   <li><b>{@code Living Hypothesis} có bằng chứng ({@link LivingHypothesisState}):</b> Chỉ cập nhật khi khách cung cấp
 *       bằng chứng mới, giữ nguyên khi nhất quán, hạ độ tin cậy / chuyển hướng ngay khi khách bác bỏ hoặc bẻ lái sang
 *       câu hỏi vận hành, trả về {@code INSUFFICIENT_EVIDENCE} / {@code UNKNOWN} khi câu quá ngắn chưa đủ căn cứ,
 *       và cấm tuyệt đối dùng lời quảng cáo của bot làm bằng chứng tâm lý khách hàng.</li>
 *   <li><b>5 thao tác bộ nhớ sự thật ({@link FactMemoryOperation}):</b> {@code ADD}, {@code UPDATE}, {@code SUPERSEDE},
 *       {@code RETRACT}, {@code UNCERTAIN} trên 5 nhóm slot đa ngành chuẩn hóa ({@link FactSlotGroup}).</li>
 *   <li><b>Giải mã câu nói ngắn theo ngữ cảnh ({@link #decodeContextualShortUtterance}):</b> Phân giải
 *       {@code "Biết thế"}, {@code "Cũng được"}, {@code "Để xem đã"}, {@code "Ừ"} dựa trên lượt liền trước + lịch sử hội thoại.</li>
 * </ul>
 */
@Service
public class AgentBrainService {

    private static final Logger log = LoggerFactory.getLogger(AgentBrainService.class);

    public static final int MAX_CARE_NOTES_PER_CUSTOMER = 4;
    public static final int MAX_VERIFIED_FACTS_PER_CUSTOMER = 5;

    /** 5 nhóm slot đa ngành chuẩn hóa (Checkpoint C3-A.2). */
    public enum FactSlotGroup {
        SLOT_TARGET_SCOPE("slot_target_scope"),
        SLOT_PREFERENCE_SPEC("slot_preference_spec"),
        SLOT_BUDGET_SENSITIVITY("slot_budget_sensitivity"),
        SLOT_PRODUCT_FOCUS("slot_product_focus"),
        SLOT_LOGISTICS_OR_CARE("slot_logistics_or_care");

        private final String code;

        FactSlotGroup(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** 5 thao tác bộ nhớ cho Facts (Checkpoint C3-A.2). */
    public enum FactMemoryOperation {
        ADD,
        UPDATE,
        SUPERSEDE,
        RETRACT,
        UNCERTAIN
    }

    /** Trạng thái của Living Hypothesis (Checkpoint C3-A.1). */
    public enum HypothesisStatus {
        UNKNOWN,
        INSUFFICIENT_EVIDENCE,
        ACTIVE_NEW_EVIDENCE,
        MAINTAINED_CONSISTENT,
        DOWNGRADED_OPERATIONAL_PIVOT,
        REJECTED_BY_CUSTOMER,
        REJECTED_BOT_PROMOTIONAL_FLUFF
    }

    /** Hồ sơ kiểm toán 1 thao tác biến đổi Fact trong bộ nhớ khách hàng. */
    public record FactMutationRecord(
            FactMemoryOperation operation,
            String slotGroup,
            String subAspect,
            String candidateFact,
            String affectedPriorFact,
            String reason,
            Instant timestamp
    ) {
    }

    /** Trạng thái Living Hypothesis dựa trên bằng chứng từ phía khách hàng. */
    public record LivingHypothesisState(
            String hypothesisText,
            HypothesisStatus status,
            double confidence,
            String customerEvidenceQuote,
            String provenanceReason,
            Instant updatedAt
    ) {
        public static LivingHypothesisState initialUnknown() {
            return new LivingHypothesisState(
                    "",
                    HypothesisStatus.UNKNOWN,
                    0.0,
                    "",
                    "INITIAL_NO_TURNS",
                    Instant.now()
            );
        }
    }

    /** Minh bạch hóa nguồn gốc suy luận của AgentBrain cho từng lượt. */
    public record BrainReasoningProvenance(
            String stage,
            String stageSource,
            String buyerArchetype,
            String archetypeSource,
            double confidence,
            String confidenceFormula,
            String selectedSkillCode,
            String skillSource,
            String workingHypothesis,
            HypothesisStatus hypothesisStatus,
            String hypothesisSource,
            String supportingCustomerEvidence,
            String proposedAction,
            Instant computedAt
    ) {
    }

    /** Kết quả giải mã câu nói ngắn / lấp lửng theo ngữ cảnh lượt liền trước + lịch sử hội thoại. */
    public record ShortUtteranceResolution(
            boolean isContextualShortUtterance,
            String utteranceType,
            String decodedMeaning,
            String proposedAction,
            String recommendedStage,
            List<String> recommendedSignals,
            double confidence,
            String evidenceSummary
    ) {
        public static ShortUtteranceResolution notApplicable() {
            return new ShortUtteranceResolution(
                    false,
                    "NONE",
                    "NOT_SHORT_AMBIGUOUS_UTTERANCE",
                    "STANDARD_PIPELINE",
                    "",
                    List.of(),
                    0.0,
                    ""
            );
        }
    }

    private static final Pattern CONCESSIVE_SHORT_UTTERANCE_PATTERN = Pattern.compile(
            "(?isU)^\\s*(?:dạ\\s+|ừ\\s+|à\\s+|thì\\s+)?(?:biết\\s+thế|biết\\s+vậy|biết\\s+là\\s+(?:thế|vậy)|nghe\\s+thì\\s+(?:thế|vậy|hay\\s+đấy)|thì\\s+biết\\s+(?:thế|vậy))(?:\\s+(?:nhưng|mà|em|shop|ạ|nhỉ))?\\s*[.!?~…]*\\s*$"
    );

    private static final Pattern LUKEWARM_SHORT_UTTERANCE_PATTERN = Pattern.compile(
            "(?isU)^\\s*(?:dạ\\s+|ừ\\s+|à\\s+|nghe\\s+)?(?:cũng\\s+được|tạm\\s+được|cũng\\s+tạm|cũng\\s+ổn|được\\s+đấy)(?:\\s+(?:em|chị|anh|shop|ạ|nhé|nha|nhỉ))?\\s*[.!?~…]*\\s*$"
    );

    private static final Pattern BARE_AFFIRM_SHORT_UTTERANCE_PATTERN = Pattern.compile(
            "(?isU)^\\s*(?:dạ\\s+)?(?:ừ|ừa|ừm|uh|uhm|uk|uki|ukm|ok|oke|okay|vâng|dạ)(?:\\s+(?:em|chị|anh|shop|ạ|nhé|nha))?\\s*[.!?~]*\\s*$"
    );

    private static final Pattern DEFERRAL_SHORT_UTTERANCE_PATTERN = Pattern.compile(
            "(?isU)^\\s*(?:ok\\s+|oke\\s+|ừ\\s+|vâng\\s+|dạ\\s+|thôi\\s+)?(?:để\\s+)?(?:mình\\s+|chị\\s+|anh\\s+|em\\s+|tôi\\s+)?(?:xem\\s+đã|cân\\s+nhắc\\s+thêm|suy\\s+nghĩ\\s+thêm|nghĩ\\s+thêm|tham\\s+khảo\\s+thêm)(?:\\s+(?:đã|chút|nhé|nha|ạ|em))*\\s*[.!?~]*\\s*$"
    );

    private static final Pattern EXPLICIT_APPOINTMENT_PROPOSAL_BOT_PATTERN = Pattern.compile(
            "(?isU).*\\b(khung\\s+giờ\\s+nào|mấy\\s+giờ|(?:hôm\\s+nay|tối\\s+nay|chiều\\s+nay|ngày\\s+mai)\\s+(?:hay|hoặc|\\p{L}+\\s+){0,4}(?:tiện|ghé|qua|lúc)|"
            + "(?:giữ|đặt|xếp|chốt)\\s+lịch(?:\\s+hẹn)?\\s+(?:cho\\s+|gói\\s+|mẫu\\s+|khóa\\s+|lúc\\s+|khung\\s+|vào\\s+)|"
            + "(?:chốt|đặt)\\s+(?:gói|mẫu|khóa)\\b[^.!?]{0,60}\\b(?:lúc|vào|khung|tối\\s+nay|chiều\\s+nay|ngày\\s+mai)\\b|"
            + "(?:qua|ghé|hẹn)\\s+(?:lúc|khung|tầm|vào)\\s+\\d{1,2}\\s*(?:(?:h|g)(?:\\d{1,2})?|giờ(?:\\s*\\d{1,2})?|:\\d{2})|"
            + "tiện\\s+ghé\\s+(?:vào\\s+)?(?:khung\\s+giờ|lúc|tối|chiều|sáng)|lịch\\s+hẹn\\s+lúc)\\b.*"
    );

    private static final Pattern OPERATIONAL_ANSWER_BOT_PATTERN = Pattern.compile(
            "(?isU).*\\b(mở\\s+cửa|đóng\\s+cửa|giờ\\s+hoạt\\s+động|giờ\\s+làm\\s+việc|"
            + "chỗ\\s+(?:đỗ|đậu|gửi)\\s+xe|bãi\\s+(?:đỗ\\s+|đậu\\s+|gửi\\s+)?xe|đỗ\\s+ô\\s+tô|gửi\\s+xe|"
            + "địa\\s+chỉ\\s+(?:bên\\s+em|cơ\\s+sở|chi\\s+nhánh|tại|ở)|chi\\s+nhánh\\s+tại)\\b.*"
    );

    private static final Pattern EXPLICIT_SUPERSEDE_MARKER_PATTERN = Pattern.compile(
            "(?isU).*\\b(đổi\\s+sang|chuyển\\s+sang|đổi\\s+từ|thay\\s+vì|thay\\s+bằng|đổi\\s+lại|chuyển\\s+qua|sửa\\s+lại|cập\\s+nhật\\s+lại|đổi\\s+lịch|đổi\\s+gói|đổi\\s+mẫu|đổi\\s+khóa)\\b.*"
    );

    private static final Pattern EXPLICIT_RETRACT_CLAUSE_PATTERN = Pattern.compile(
            "(?isU)(?:à\\s+thôi|thôi|tạm\\s+thời)?\\s*(?:mình\\s+|chị\\s+|anh\\s+|em\\s+|tôi\\s+)?"
            + "(?:không\\s+(?:làm|chăm\\s+sóc|massage|ốp|lát|lấy|chọn|học|đăng\\s+ký|cần)|bỏ(?:\\s+qua)?|hủy\\s+bỏ|rút\\s+lại)\\s+"
            + "(?:vùng\\s+|phần\\s+|khu\\s+vực\\s+|phòng\\s+|gói\\s+|mẫu\\s+|khóa\\s+)?"
            + "([\\p{L}0-9\\s]{2,32}?)(?:\\s+nữa|\\s+nhé|\\s+nha|\\s+ạ|\\s+đâu|[,.!;]|$)"
    );

    private static final Pattern EXPLORATORY_UNCERTAIN_QUESTION_PATTERN = Pattern.compile(
            "(?isU)^\\s*(?:cho\\s+(?:mình|chị|anh|em)\\s+hỏi\\s+|không\\s+biết\\s+|shop\\s+ơi\\s+|em\\s+ơi\\s+)?"
            + "(?:bên\\s+(?:mình|em|shop)\\s+có\\s+.+\\s+(?:không|ko|k|chưa)|"
            + "nếu\\s+(?:làm|ốp|lát|học|lấy|chọn)\\s+.+\\s+thì\\s+(?:sao|giá|bao\\s+nhiêu)|"
            + ".+\\s+(?:giá\\s+bao\\s+nhiêu|bao\\s+nhiêu\\s+tiền|gồm\\s+những\\s+gì|như\\s+thế\\s+nào)\\s*\\?*|"
            + "hỏi\\s+thử\\s+.+|tham\\s+khảo\\s+thử\\s+.+)\\s*\\?*\\s*$"
    );

    private static final Pattern FIRST_PERSON_COMMITMENT_FACT_PATTERN = Pattern.compile(
            "(?isU).*\\b((?:mình|chị|anh|tôi)\\s+(?:bị|đang\\s+bị|đang\\s+cần|cần\\s+ốp|cần\\s+lát|muốn\\s+ốp|muốn\\s+lát|muốn\\s+làm|muốn\\s+học|chốt|đặt|chọn|đổi\\s+sang)|"
            + "cho\\s+(?:mình|chị|anh|tôi)\\s+(?:đặt|chốt|đổi))\\b.*"
    );

    private static final Pattern BOT_PROMOTIONAL_FLUFF_MARKERS = Pattern.compile(
            "(?isU)\\b(đả\\s+thông\\s+kinh\\s+lạc|quý\\s+hiếm\\s+độc\\s+quyền|tinh\\s+dầu\\s+thiên\\s+nhiên\\s+cao\\s+cấp|"
            + "hoàng\\s+gia\\s+sang\\s+trọng|bữa\\s+ăn\\s+nhẹ\\s+thực\\s+dưỡng|men\\s+kim\\s+cương\\s+siêu\\s+bóng|"
            + "cam\\s+kết\\s+đầu\\s+ra\\s+100%|ưu\\s+đãi\\s+độc\\s+quyền|đẳng\\s+cấp\\s+5\\s+sao)\\b"
    );

    private static final Set<String> HYPOTHESIS_META_STOPWORDS = Set.of(
            "khách", "hàng", "đang", "muốn", "quan", "tâm", "cân", "nhắc", "tham", "khảo",
            "tìm", "hiểu", "phương", "án", "dịch", "vụ", "sản", "phẩm", "gói", "mẫu", "khóa",
            "giá", "mức", "chi", "phí", "bên", "mình", "chị", "anh", "cần", "hỏi", "thêm",
            "giả", "thuyết", "cập", "nhật", "lượt", "thứ", "hiện", "tại", "với", "cho", "của"
    );

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PsychologicalSkillDirectiveDto(
            @JsonProperty("skill_code") String skillCode,
            @JsonProperty("skill_name") String skillName,
            @JsonProperty("academic_origin") String academicOrigin,
            @JsonProperty("trigger_reason") String triggerReason,
            @JsonProperty("bubble_plan") List<String> bubblePlan,
            @JsonProperty("subtle_flex_hint") String subtleFlexHint,
            @JsonProperty("scarcity_or_close_hint") String scarcityOrCloseHint,
            @JsonProperty("prompt_directive") String promptDirective,
            @JsonProperty("audience_voice_cadence") String audienceVoiceCadence,
            @JsonProperty("price_disclosure_gate") String priceDisclosureGate
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MemoryFactDto(
            @JsonProperty("id") Long id,
            @JsonProperty("fact_key") String factKey,
            @JsonProperty("fact_value") String factValue,
            @JsonProperty("source_type") String sourceType,
            @JsonProperty("confidence") double confidence,
            @JsonProperty("is_active") boolean isActive,
            @JsonProperty("superseded_by") Long supersededBy
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ObjectionItemDto(
            @JsonProperty("type") String type,
            @JsonProperty("quote") String quote,
            @JsonProperty("count") int count,
            @JsonProperty("confidence") double confidence
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RecallAndAdviseResponse(
            @JsonProperty("tenant_id") String tenantId,
            @JsonProperty("customer_id") String customerId,
            @JsonProperty("conversation_id") String conversationId,
            @JsonProperty("normalized_message") String normalizedMessage,
            @JsonProperty("masked_message") String maskedMessage,
            @JsonProperty("honorific") String honorific,
            @JsonProperty("purchase_stage") String purchaseStage,
            @JsonProperty("sentiment") String sentiment,
            @JsonProperty("intent") String intent,
            @JsonProperty("buyer_archetype") String buyerArchetype,
            @JsonProperty("buying_temperature") int buyingTemperature,
            @JsonProperty("final_confidence") double finalConfidence,
            @JsonProperty("next_best_action") String nextBestAction,
            @JsonProperty("requires_human") boolean requiresHuman,
            @JsonProperty("handoff_reason") String handoffReason,
            @JsonProperty("active_skill") PsychologicalSkillDirectiveDto activeSkill,
            @JsonProperty("customer_memory_block") String customerMemoryBlock,
            @JsonProperty("active_facts") List<MemoryFactDto> activeFacts,
            @JsonProperty("objections") List<ObjectionItemDto> objections,
            @JsonProperty("latency_ms") double latencyMs
    ) {}

    @Autowired(required = false)
    private volatile AciSessionMemoryPersistenceService aciSessionMemoryPersistenceService;

    @Value("${docmind.aci.db-persistence.enabled:true}")
    private volatile boolean aciDbPersistenceEnabled = true;

    public void setAciSessionMemoryPersistenceService(
            AciSessionMemoryPersistenceService aciSessionMemoryPersistenceService
    ) {
        this.aciSessionMemoryPersistenceService = aciSessionMemoryPersistenceService;
    }

    public AciSessionMemoryPersistenceService getAciSessionMemoryPersistenceService() {
        return this.aciSessionMemoryPersistenceService;
    }

    public boolean isAciDbPersistenceEnabled() {
        return this.aciDbPersistenceEnabled && this.aciSessionMemoryPersistenceService != null;
    }

    public void setAciDbPersistenceEnabled(boolean aciDbPersistenceEnabled) {
        this.aciDbPersistenceEnabled = aciDbPersistenceEnabled;
    }

    @Autowired
    public AgentBrainService() {
    }

    public AgentBrainService(String ignoredModeHint) {
    }

    public RecallAndAdviseResponse recallAndAdvise(
            UUID pageId,
            String senderId,
            String customerName,
            String message,
            double retrievalQuality,
            double groundedness) {
        return recallAndAdvise(pageId, senderId, customerName, message, retrievalQuality, groundedness, false, null);
    }

    public RecallAndAdviseResponse recallAndAdvise(
            UUID pageId,
            String senderId,
            String customerName,
            String message,
            double retrievalQuality,
            double groundedness,
            boolean isFirstTurn,
            PromptBuilder.PagePersona pagePersona) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String tenantId = pageId != null ? pageId.toString() : "global";
        String customerId = (senderId != null && !senderId.isBlank()) ? senderId.strip() : "anonymous_customer";

        CustomerBrainMemory mem = getOrHydrateMemoryForMutation(pageId, customerId);
        double boundedRetrieval = Math.max(0.0, Math.min(1.0, retrievalQuality));
        double boundedGroundedness = Math.max(0.0, Math.min(1.0, groundedness));

        BrainReasoningProvenance provenance = computeRecallProvenance(
                mem,
                message,
                boundedRetrieval,
                boundedGroundedness,
                isFirstTurn,
                null
        );
        synchronized (mem) {
            mem.lastReasoningProvenance = provenance;
        }
        log.info("[AgentBrainProvenance:Recall] tenant={} customer={} stage={} (src={}) archetype={} (src={}) "
                        + "confidence={} (formula=\"{}\") skill={} (src={}) hypothesisStatus={} hypothesis=\"{}\" (src={}) evidence=\"{}\" proposedAction={}",
                tenantId,
                customerId,
                provenance.stage(),
                provenance.stageSource(),
                provenance.buyerArchetype(),
                provenance.archetypeSource(),
                String.format(Locale.ROOT, "%.2f", provenance.confidence()),
                provenance.confidenceFormula(),
                provenance.selectedSkillCode(),
                provenance.skillSource(),
                provenance.hypothesisStatus(),
                provenance.workingHypothesis(),
                provenance.hypothesisSource(),
                provenance.supportingCustomerEvidence(),
                provenance.proposedAction());

        return null;
    }

    /**
     * Bộ nhớ In-JVM có kiểm soát dung lượng, lịch sử biến đổi 5 thao tác & Living Hypothesis theo từng khách hàng.
     */
    public static final class CustomerBrainMemory {
        private final List<String> verifiedFacts = new ArrayList<>();
        private final List<String> supersededOrRetractedFacts = new ArrayList<>();
        private final List<String> uncertainExplorations = new ArrayList<>();
        private final List<FactMutationRecord> factMutations = new ArrayList<>();
        private final List<String> careNotes = new ArrayList<>();
        private final List<ConversationState.CustomerCareNoteArtifact> careNoteArtifacts = new ArrayList<>();
        private String workingHypothesis = "";
        private LivingHypothesisState livingHypothesisState = LivingHypothesisState.initialUnknown();
        private BrainReasoningProvenance lastReasoningProvenance = null;
        private String lastActualSentSpeechAct = "";
        private int priceObjectionCount = 0;
        private boolean inPriceObjectionContext = false;
        private boolean hasPresentedDownsell = false;
        private ConversationState.StructuredBookingState structuredBookingState = ConversationState.StructuredBookingState.none();

        // === ACI (LLM_COGNITIVE_V2) 5-Tier Memory, Strategy Adaptation & Session-Level Commitment Management ===
        private final List<AdaptiveConversationIntelligence.CustomerStatementRecord> customerStatements = new ArrayList<>();
        private AdaptiveConversationIntelligence.WorkingHypothesisRecord aciWorkingHypothesis =
                AdaptiveConversationIntelligence.WorkingHypothesisRecord.initialEmpty();
        private List<AdaptiveConversationIntelligence.UnverifiedParserCandidate> latestUnverifiedParserCandidates = List.of();
        private final List<AdaptiveConversationIntelligence.VerifiedBusinessFact> authoritativeBusinessFacts = new ArrayList<>();
        private AdaptiveConversationIntelligence.OperationalStateSnapshot operationalState =
                AdaptiveConversationIntelligence.OperationalStateSnapshot.initial();
        private AdaptiveConversationIntelligence.PreviousStrategyRecord previousStrategyRecord =
                AdaptiveConversationIntelligence.PreviousStrategyRecord.initialNone();
        private final List<AdaptiveConversationIntelligence.CommitmentRecord> sessionCommitments = new ArrayList<>();
        private String inferredCustomerPronoun = null;
        private double inferredPronounConfidence = 0.0;
        private String inferredPronounEvidenceTurnId = null;
        private String verifiedSymptomDuration = null;
        private String communicationStylePreference = "STANDARD";

        // === ACI V2 Customer Understanding Model (Draft 2 — 3 Layers & Versioned Facts) + Adaptive Response Planner ===
        private String draft2CurrentTopic = "INITIAL";
        private final List<String> draft2TopicHistory = new ArrayList<>();
        private final List<AdaptiveConversationIntelligence.UnresolvedRequestItem> draft2UnresolvedRequests = new ArrayList<>();
        private final List<String> draft2PendingReferences = new ArrayList<>();
        private String draft2LastBotAction = "NONE";
        private String draft2LastTurnIntent = "";
        private final List<String> draft2SecondaryIntents = new ArrayList<>();
        private String draft2ConversationState = "ACTIVE_CONSULTATION";
        private String draft2ConversationGoal = "EXPLORE_SERVICES";
        private final List<AdaptiveConversationIntelligence.VersionedCustomerFact> draft2ConfirmedFacts = new ArrayList<>();
        private final List<AdaptiveConversationIntelligence.VersionedCustomerFact> draft2SelfReportedSymptoms = new ArrayList<>();
        private AdaptiveConversationIntelligence.BarrierHypothesis draft2BarrierHypothesis =
                AdaptiveConversationIntelligence.BarrierHypothesis.initialNone();
        private AdaptiveConversationIntelligence.EmotionalState draft2Emotion =
                AdaptiveConversationIntelligence.EmotionalState.empty();
        private String draft2EffectiveTurnDepth = "STANDARD";
        private AdaptiveConversationIntelligence.InteractionPermission draft2InteractionPermission =
                AdaptiveConversationIntelligence.InteractionPermission.OPEN;
        private String draft2InterestedPackageId = null;
        private AdaptiveConversationIntelligence.AdaptiveResponsePlan draft2LastResponsePlan =
                AdaptiveConversationIntelligence.AdaptiveResponsePlan.empty();

        // === ACI V3: Commitment Ledger & Negative Preferences ===
        private String draft2CommittedSelectedPackageId = null;
        private AdaptiveConversationIntelligence.CommitmentLevel draft2CommitmentLevel =
                AdaptiveConversationIntelligence.CommitmentLevel.NONE;
        private String draft2CommitmentEvidence = null;
        private final List<AdaptiveConversationIntelligence.NegativePreferenceEntry> draft2NegativePreferences = new ArrayList<>();
        private final List<String> draft2LastOfferedPackageIds = new ArrayList<>();

        // === Unit P2.4 (V35): Optimistic Version & Turn Monotonicity Tracking ===
        private UUID lastSyncedSessionId = null;
        private Long lastSyncedDbVersion = null;
        private String lastUpdatedTurnId = "T0";
        private int highestObservedTurnSeq = 0;

        public synchronized UUID lastSyncedSessionIdSnapshot() {
            return lastSyncedSessionId;
        }

        public synchronized Long lastSyncedDbVersionSnapshot() {
            return lastSyncedDbVersion;
        }

        public synchronized int highestObservedTurnSeqSnapshot() {
            return highestObservedTurnSeq;
        }

        public synchronized AciCustomerBrainMemorySnapshot toPersistedSnapshot() {
            String nowIso = Instant.now().toString();
            List<AciCustomerBrainMemorySnapshot.PersistedUnresolvedRequest> persistedReqs = new ArrayList<>();
            for (AdaptiveConversationIntelligence.UnresolvedRequestItem r : draft2UnresolvedRequests) {
                if (r != null) {
                    persistedReqs.add(AciCustomerBrainMemorySnapshot.PersistedUnresolvedRequest.fromDomain(r, nowIso));
                }
            }
            List<AciCustomerBrainMemorySnapshot.PersistedVersionedFact> persistedConfirmed = new ArrayList<>();
            for (AdaptiveConversationIntelligence.VersionedCustomerFact f : draft2ConfirmedFacts) {
                if (f != null) {
                    persistedConfirmed.add(AciCustomerBrainMemorySnapshot.PersistedVersionedFact.fromDomain(f));
                }
            }
            List<AciCustomerBrainMemorySnapshot.PersistedVersionedFact> persistedSymptoms = new ArrayList<>();
            for (AdaptiveConversationIntelligence.VersionedCustomerFact s : draft2SelfReportedSymptoms) {
                if (s != null) {
                    persistedSymptoms.add(AciCustomerBrainMemorySnapshot.PersistedVersionedFact.fromDomain(s));
                }
            }
            List<AciCustomerBrainMemorySnapshot.PersistedCustomerStatement> persistedStmts = new ArrayList<>();
            for (AdaptiveConversationIntelligence.CustomerStatementRecord st : customerStatements) {
                if (st != null) {
                    persistedStmts.add(AciCustomerBrainMemorySnapshot.PersistedCustomerStatement.fromDomain(st));
                }
            }
            List<AciCustomerBrainMemorySnapshot.PersistedBusinessFact> persistedBizFacts = new ArrayList<>();
            for (AdaptiveConversationIntelligence.VerifiedBusinessFact bf : authoritativeBusinessFacts) {
                if (bf != null) {
                    persistedBizFacts.add(AciCustomerBrainMemorySnapshot.PersistedBusinessFact.fromDomain(bf));
                }
            }
            List<AciCustomerBrainMemorySnapshot.PersistedCommitment> persistedCmts = new ArrayList<>();
            for (AdaptiveConversationIntelligence.CommitmentRecord c : sessionCommitments) {
                if (c != null) {
                    persistedCmts.add(AciCustomerBrainMemorySnapshot.PersistedCommitment.fromDomain(c));
                }
            }
            return new AciCustomerBrainMemorySnapshot(
                    AciCustomerBrainMemorySnapshot.CURRENT_SCHEMA_VERSION,
                    lastUpdatedTurnId,
                    highestObservedTurnSeq,
                    nowIso,
                    draft2CurrentTopic,
                    List.copyOf(draft2TopicHistory),
                    List.copyOf(persistedReqs),
                    List.copyOf(draft2PendingReferences),
                    draft2LastBotAction,
                    draft2LastTurnIntent,
                    draft2ConversationGoal,
                    List.copyOf(persistedConfirmed),
                    List.copyOf(persistedSymptoms),
                    AciCustomerBrainMemorySnapshot.PersistedBarrierHypothesis.fromDomain(draft2BarrierHypothesis),
                    AciCustomerBrainMemorySnapshot.PersistedEmotion.fromDomain(draft2Emotion),
                    communicationStylePreference != null ? communicationStylePreference : "STANDARD",
                    draft2EffectiveTurnDepth != null ? draft2EffectiveTurnDepth : "STANDARD",
                    draft2InteractionPermission != null
                            ? draft2InteractionPermission.name()
                            : AdaptiveConversationIntelligence.InteractionPermission.OPEN.name(),
                    draft2InterestedPackageId,
                    AciCustomerBrainMemorySnapshot.PersistedResponsePlan.fromDomain(draft2LastResponsePlan),
                    priceObjectionCount,
                    inPriceObjectionContext,
                    hasPresentedDownsell,
                    AciCustomerBrainMemorySnapshot.PersistedBookingState.fromDomain(structuredBookingState),
                    inferredCustomerPronoun,
                    inferredPronounConfidence,
                    inferredPronounEvidenceTurnId,
                    verifiedSymptomDuration,
                    List.copyOf(persistedStmts),
                    AciCustomerBrainMemorySnapshot.PersistedWorkingHypothesis.fromDomain(aciWorkingHypothesis),
                    List.copyOf(persistedBizFacts),
                    AciCustomerBrainMemorySnapshot.PersistedOperationalState.fromDomain(operationalState),
                    AciCustomerBrainMemorySnapshot.PersistedPreviousStrategy.fromDomain(previousStrategyRecord),
                    List.copyOf(persistedCmts),
                    List.copyOf(verifiedFacts),
                    List.copyOf(supersededOrRetractedFacts),
                    List.copyOf(uncertainExplorations),
                    List.copyOf(careNotes),
                    workingHypothesis,
                    lastActualSentSpeechAct,
                    null,
                    List.copyOf(draft2SecondaryIntents),
                    draft2ConversationState
            );
        }

        public synchronized void hydrateFromPersistedSnapshot(
                AciCustomerBrainMemorySnapshot snap,
                UUID dbSessionId,
                Long dbVersion,
                boolean rowOptedOut
        ) {
            if (dbSessionId != null) {
                this.lastSyncedSessionId = dbSessionId;
            }
            if (dbVersion != null) {
                this.lastSyncedDbVersion = dbVersion;
            }
            if (snap == null) {
                if (rowOptedOut && this.operationalState != null && !this.operationalState.optedOut()) {
                    this.operationalState = new AdaptiveConversationIntelligence.OperationalStateSnapshot(
                            this.operationalState.bookingStatus(),
                            this.operationalState.handoffStatus(),
                            true,
                            this.operationalState.activeHandoffTicketId(),
                            this.operationalState.lastHandoffError(),
                            this.operationalState.communicationStylePreference()
                    );
                }
                return;
            }
            this.lastUpdatedTurnId = snap.lastUpdatedTurnId() != null ? snap.lastUpdatedTurnId() : "T0";
            this.highestObservedTurnSeq = Math.max(this.highestObservedTurnSeq, snap.lastUpdatedTurnSeq());

            this.draft2CurrentTopic = snap.currentTopic() != null ? snap.currentTopic() : "INITIAL";
            this.draft2TopicHistory.clear();
            if (snap.topicHistory() != null) {
                this.draft2TopicHistory.addAll(snap.topicHistory());
            }
            this.draft2UnresolvedRequests.clear();
            if (snap.unresolvedRequests() != null) {
                for (AciCustomerBrainMemorySnapshot.PersistedUnresolvedRequest r : snap.unresolvedRequests()) {
                    if (r != null) {
                        this.draft2UnresolvedRequests.add(r.toDomain());
                    }
                }
            }
            this.draft2PendingReferences.clear();
            if (snap.pendingReferences() != null) {
                this.draft2PendingReferences.addAll(snap.pendingReferences());
            }
            this.draft2LastBotAction = snap.lastBotAction() != null ? snap.lastBotAction() : "NONE";
            this.draft2LastTurnIntent = snap.lastTurnIntent() != null ? snap.lastTurnIntent() : "";
            this.draft2ConversationGoal = snap.conversationGoal() != null ? snap.conversationGoal() : "EXPLORE_SERVICES";

            this.draft2ConfirmedFacts.clear();
            if (snap.confirmedFacts() != null) {
                for (AciCustomerBrainMemorySnapshot.PersistedVersionedFact f : snap.confirmedFacts()) {
                    if (f != null) {
                        this.draft2ConfirmedFacts.add(f.toDomain());
                    }
                }
            }
            this.draft2SelfReportedSymptoms.clear();
            if (snap.selfReportedSymptoms() != null) {
                for (AciCustomerBrainMemorySnapshot.PersistedVersionedFact s : snap.selfReportedSymptoms()) {
                    if (s != null) {
                        this.draft2SelfReportedSymptoms.add(s.toDomain());
                    }
                }
            }
            this.draft2BarrierHypothesis = snap.barrierHypothesis() != null
                    ? snap.barrierHypothesis().toDomain()
                    : AdaptiveConversationIntelligence.BarrierHypothesis.initialNone();
            this.draft2Emotion = snap.emotion() != null
                    ? snap.emotion().toDomain()
                    : AdaptiveConversationIntelligence.EmotionalState.empty();
            this.communicationStylePreference = snap.communicationStylePreference() != null
                    ? snap.communicationStylePreference()
                    : "STANDARD";
            this.draft2EffectiveTurnDepth = snap.effectiveTurnDepth() != null
                    ? snap.effectiveTurnDepth()
                    : "STANDARD";
            this.draft2InteractionPermission = AdaptiveConversationIntelligence.InteractionPermission
                    .fromStringOrDefault(snap.interactionPermission(),
                            AdaptiveConversationIntelligence.InteractionPermission.OPEN);
            this.draft2SecondaryIntents.clear();
            if (snap.secondaryIntents() != null) {
                for (String sec : snap.secondaryIntents()) {
                    if (sec != null && !sec.isBlank()) {
                        this.draft2SecondaryIntents.add(sec.strip());
                    }
                }
            }
            this.draft2ConversationState = AdaptiveConversationIntelligence.normalizeConversationStateLabel(
                    snap.conversationState(),
                    this.draft2LastTurnIntent,
                    this.draft2SecondaryIntents,
                    this.draft2BarrierHypothesis != null ? this.draft2BarrierHypothesis.barrierType() : null,
                    null,
                    AdaptiveConversationIntelligence.BuyingSignal.NONE,
                    this.draft2InteractionPermission
            );
            this.draft2InterestedPackageId = snap.interestedPackageId();
            this.draft2LastResponsePlan = snap.lastResponsePlan() != null
                    ? snap.lastResponsePlan().toDomain()
                    : AdaptiveConversationIntelligence.AdaptiveResponsePlan.empty();

            this.priceObjectionCount = Math.max(0, snap.priceObjectionCount());
            this.inPriceObjectionContext = snap.inPriceObjectionContext();
            this.hasPresentedDownsell = snap.hasPresentedDownsell();
            this.structuredBookingState = snap.structuredBookingState() != null
                    ? snap.structuredBookingState().toDomain()
                    : ConversationState.StructuredBookingState.none();
            this.inferredCustomerPronoun = snap.inferredCustomerPronoun();
            this.inferredPronounConfidence = snap.inferredPronounConfidence();
            this.inferredPronounEvidenceTurnId = snap.inferredPronounEvidenceTurnId();
            this.verifiedSymptomDuration = snap.verifiedSymptomDuration();

            this.customerStatements.clear();
            if (snap.customerStatements() != null) {
                for (AciCustomerBrainMemorySnapshot.PersistedCustomerStatement st : snap.customerStatements()) {
                    if (st != null) {
                        this.customerStatements.add(st.toDomain());
                    }
                }
            }
            this.aciWorkingHypothesis = snap.aciWorkingHypothesis() != null
                    ? snap.aciWorkingHypothesis().toDomain()
                    : AdaptiveConversationIntelligence.WorkingHypothesisRecord.initialEmpty();
            this.authoritativeBusinessFacts.clear();
            if (snap.authoritativeBusinessFacts() != null) {
                for (AciCustomerBrainMemorySnapshot.PersistedBusinessFact bf : snap.authoritativeBusinessFacts()) {
                    if (bf != null) {
                        this.authoritativeBusinessFacts.add(bf.toDomain());
                    }
                }
            }
            AdaptiveConversationIntelligence.OperationalStateSnapshot hydratedOp = snap.operationalState() != null
                    ? snap.operationalState().toDomain()
                    : AdaptiveConversationIntelligence.OperationalStateSnapshot.initial();
            this.operationalState = new AdaptiveConversationIntelligence.OperationalStateSnapshot(
                    hydratedOp.bookingStatus(),
                    hydratedOp.handoffStatus(),
                    hydratedOp.optedOut() || rowOptedOut,
                    hydratedOp.activeHandoffTicketId(),
                    hydratedOp.lastHandoffError(),
                    hydratedOp.communicationStylePreference()
            );
            this.previousStrategyRecord = snap.previousStrategyRecord() != null
                    ? snap.previousStrategyRecord().toDomain()
                    : AdaptiveConversationIntelligence.PreviousStrategyRecord.initialNone();
            this.sessionCommitments.clear();
            if (snap.sessionCommitments() != null) {
                for (AciCustomerBrainMemorySnapshot.PersistedCommitment c : snap.sessionCommitments()) {
                    if (c != null) {
                        this.sessionCommitments.add(c.toDomain());
                    }
                }
            }

            this.verifiedFacts.clear();
            if (snap.verifiedFacts() != null) {
                this.verifiedFacts.addAll(snap.verifiedFacts());
            }
            this.supersededOrRetractedFacts.clear();
            if (snap.supersededOrRetractedFacts() != null) {
                this.supersededOrRetractedFacts.addAll(snap.supersededOrRetractedFacts());
            }
            this.uncertainExplorations.clear();
            if (snap.uncertainExplorations() != null) {
                this.uncertainExplorations.addAll(snap.uncertainExplorations());
            }
            this.careNotes.clear();
            if (snap.careNotes() != null) {
                this.careNotes.addAll(snap.careNotes());
            }
            this.workingHypothesis = snap.workingHypothesis() != null ? snap.workingHypothesis() : "";
            this.lastActualSentSpeechAct = snap.lastActualSentSpeechAct() != null ? snap.lastActualSentSpeechAct() : "";
        }

        public synchronized List<String> verifiedFactsSnapshot() {
            return List.copyOf(verifiedFacts);
        }

        public synchronized List<String> supersededOrRetractedFactsSnapshot() {
            return List.copyOf(supersededOrRetractedFacts);
        }

        public synchronized List<String> uncertainExplorationsSnapshot() {
            return List.copyOf(uncertainExplorations);
        }

        public synchronized List<FactMutationRecord> factMutationsSnapshot() {
            return List.copyOf(factMutations);
        }

        public synchronized List<String> careNotesSnapshot() {
            return List.copyOf(careNotes);
        }

        public synchronized List<ConversationState.CustomerCareNoteArtifact> careNoteArtifactsSnapshot() {
            return List.copyOf(careNoteArtifacts);
        }

        public synchronized String workingHypothesisSnapshot() {
            return workingHypothesis;
        }

        public synchronized LivingHypothesisState livingHypothesisStateSnapshot() {
            return livingHypothesisState;
        }

        public synchronized BrainReasoningProvenance lastReasoningProvenanceSnapshot() {
            return lastReasoningProvenance;
        }

        public synchronized String lastActualSentSpeechActSnapshot() {
            return lastActualSentSpeechAct;
        }

        public synchronized int priceObjectionCountSnapshot() {
            return priceObjectionCount;
        }

        public synchronized boolean inPriceObjectionContextSnapshot() {
            return inPriceObjectionContext;
        }

        public synchronized boolean hasPresentedDownsellSnapshot() {
            return hasPresentedDownsell;
        }

        public synchronized ConversationState.StructuredBookingState structuredBookingStateSnapshot() {
            return structuredBookingState;
        }

        public synchronized List<AdaptiveConversationIntelligence.CustomerStatementRecord> customerStatementsSnapshot() {
            return List.copyOf(customerStatements);
        }

        public synchronized AdaptiveConversationIntelligence.WorkingHypothesisRecord aciWorkingHypothesisSnapshot() {
            return aciWorkingHypothesis;
        }

        public synchronized List<AdaptiveConversationIntelligence.UnverifiedParserCandidate> latestUnverifiedParserCandidatesSnapshot() {
            return List.copyOf(latestUnverifiedParserCandidates);
        }

        public synchronized List<AdaptiveConversationIntelligence.VerifiedBusinessFact> authoritativeBusinessFactsSnapshot() {
            return List.copyOf(authoritativeBusinessFacts);
        }

        public synchronized AdaptiveConversationIntelligence.OperationalStateSnapshot operationalStateSnapshot() {
            return operationalState;
        }

        public synchronized AdaptiveConversationIntelligence.PreviousStrategyRecord previousStrategyRecordSnapshot() {
            return previousStrategyRecord;
        }

        public synchronized List<AdaptiveConversationIntelligence.CommitmentRecord> sessionCommitmentsSnapshot() {
            return List.copyOf(sessionCommitments);
        }

        public synchronized String inferredCustomerPronounSnapshot() {
            return inferredCustomerPronoun;
        }

        public synchronized double inferredPronounConfidenceSnapshot() {
            return inferredPronounConfidence;
        }

        public synchronized String inferredPronounEvidenceTurnIdSnapshot() {
            return inferredPronounEvidenceTurnId;
        }

        public synchronized String verifiedSymptomDurationSnapshot() {
            return verifiedSymptomDuration;
        }

        public synchronized String communicationStylePreferenceSnapshot() {
            return communicationStylePreference != null ? communicationStylePreference : "STANDARD";
        }

        public synchronized AdaptiveConversationIntelligence.CustomerUnderstandingDraft2Snapshot draft2Snapshot() {
            String bookingStatusStr = structuredBookingState != null
                    ? structuredBookingState.status().name()
                    : ConversationState.BookingStatus.NONE.name();
            String committedPkg = (structuredBookingState != null
                    && structuredBookingState.selectedProductId() != null
                    && !structuredBookingState.selectedProductId().isBlank())
                    ? structuredBookingState.selectedProductId()
                    : draft2CommittedSelectedPackageId;
            int pendingCmts = 0;
            for (AdaptiveConversationIntelligence.CommitmentRecord c : sessionCommitments) {
                if (c != null && c.commitmentStatus() == AdaptiveConversationIntelligence.CommitmentStatus.pending) {
                    pendingCmts++;
                }
            }
            AdaptiveConversationIntelligence.ConversationalContextLayer layer1 =
                    new AdaptiveConversationIntelligence.ConversationalContextLayer(
                            draft2CurrentTopic,
                            List.copyOf(draft2TopicHistory),
                            List.copyOf(draft2UnresolvedRequests),
                            List.copyOf(draft2PendingReferences),
                            draft2LastBotAction
                    );
            AdaptiveConversationIntelligence.ConsultativeProfileLayer layer2 =
                    new AdaptiveConversationIntelligence.ConsultativeProfileLayer(
                            draft2LastTurnIntent,
                            List.copyOf(draft2SecondaryIntents),
                            draft2ConversationState != null ? draft2ConversationState : "ACTIVE_CONSULTATION",
                            draft2ConversationGoal,
                            List.copyOf(draft2ConfirmedFacts),
                            List.copyOf(draft2SelfReportedSymptoms),
                            draft2BarrierHypothesis,
                            draft2Emotion,
                            communicationStylePreference != null ? communicationStylePreference : "STANDARD",
                            draft2EffectiveTurnDepth != null ? draft2EffectiveTurnDepth : "STANDARD",
                            draft2InteractionPermission != null
                                    ? draft2InteractionPermission
                                    : AdaptiveConversationIntelligence.InteractionPermission.OPEN
                    );
            AdaptiveConversationIntelligence.BusinessCommitmentLayer layer3 =
                    new AdaptiveConversationIntelligence.BusinessCommitmentLayer(
                            committedPkg == null ? draft2InterestedPackageId : null,
                            committedPkg,
                            bookingStatusStr,
                            operationalState != null ? operationalState.handoffStatus() : AdaptiveConversationIntelligence.HandoffLifecycleStatus.NONE,
                            pendingCmts,
                            draft2CommitmentLevel != null
                                    ? draft2CommitmentLevel
                                    : AdaptiveConversationIntelligence.CommitmentLevel.NONE,
                            draft2CommitmentEvidence,
                            List.copyOf(draft2LastOfferedPackageIds),
                            List.copyOf(draft2NegativePreferences)
                    );
            return new AdaptiveConversationIntelligence.CustomerUnderstandingDraft2Snapshot(
                    layer1,
                    layer2,
                    layer3,
                    draft2LastResponsePlan
            );
        }
    }

    private final Map<String, CustomerBrainMemory> customerMemoryStore = new ConcurrentHashMap<>();

    private volatile List<ConversationState.CustomerCareNoteArtifact> lastRetainedCareNotes = List.of();

    public List<ConversationState.CustomerCareNoteArtifact> getLastRetainedCareNotes() {
        return lastRetainedCareNotes;
    }

    public static String toConversationMemoryKey(UUID pageId, String senderId) {
        String tenantId = pageId != null ? pageId.toString() : "global";
        String customerId = (senderId != null && !senderId.isBlank()) ? senderId.strip() : "anonymous_customer";
        return tenantId + ":" + customerId;
    }

    /**
     * Unit P2.4 (V35): Lazily hydrates {@link CustomerBrainMemory} from {@code conversation_sessions.aci_memory_state}
     * ({@code JSONB}) on a RAM cache miss when {@code docmind.aci.db-persistence.enabled=true}.
     */
    private CustomerBrainMemory resolveMemoryReadOnly(UUID pageId, String senderId) {
        String convKey = toConversationMemoryKey(pageId, senderId);
        CustomerBrainMemory cached = customerMemoryStore.get(convKey);
        if (cached != null) {
            return cached;
        }
        if (!isAciDbPersistenceEnabled() || senderId == null || senderId.isBlank()) {
            return null;
        }
        AciSessionMemoryPersistenceService.HydratedSessionRecord hydrated =
                aciSessionMemoryPersistenceService.loadLatestSessionMemory(pageId, senderId);
        if (hydrated == null) {
            return null;
        }
        return customerMemoryStore.computeIfAbsent(convKey, k -> {
            CustomerBrainMemory mem = new CustomerBrainMemory();
            mem.hydrateFromPersistedSnapshot(
                    hydrated.snapshot(),
                    hydrated.sessionId(),
                    hydrated.dbVersion(),
                    hydrated.optedOut()
            );
            return mem;
        });
    }

    private CustomerBrainMemory getOrHydrateMemoryForMutation(UUID pageId, String senderId) {
        CustomerBrainMemory resolved = resolveMemoryReadOnly(pageId, senderId);
        if (resolved != null) {
            return resolved;
        }
        return customerMemoryStore.computeIfAbsent(
                toConversationMemoryKey(pageId, senderId),
                k -> new CustomerBrainMemory()
        );
    }

    /**
     * Synchronizes the turn-start JPA {@code @Version} baseline and merges any external DB updates
     * (e.g. {@code opted_out}, {@code HUMAN_TAKEOVER}, or another JVM replica's commit) before executing an ACI turn.
     */
    public void refreshTurnBaseFromDb(UUID pageId, String senderId) {
        if (!isAciDbPersistenceEnabled() || senderId == null || senderId.isBlank()) {
            return;
        }
        AciSessionMemoryPersistenceService.HydratedSessionRecord hydrated =
                aciSessionMemoryPersistenceService.loadLatestSessionMemory(pageId, senderId);
        if (hydrated == null) {
            return;
        }
        String convKey = toConversationMemoryKey(pageId, senderId);
        CustomerBrainMemory mem = customerMemoryStore.computeIfAbsent(convKey, k -> new CustomerBrainMemory());
        synchronized (mem) {
            AciCustomerBrainMemorySnapshot merged = AciCustomerBrainMemorySnapshot.monotonicMerge(
                    hydrated.snapshot(),
                    mem.toPersistedSnapshot()
            );
            mem.hydrateFromPersistedSnapshot(
                    merged != null ? merged : hydrated.snapshot(),
                    hydrated.sessionId(),
                    hydrated.dbVersion(),
                    hydrated.optedOut()
            );
        }
    }

    private AciSessionMemoryPersistenceService.PersistedSessionOutcome persistMemoryToDbIfEnabled(
            UUID pageId,
            String senderId,
            String customerName,
            CustomerBrainMemory mem
    ) {
        if (!isAciDbPersistenceEnabled() || mem == null || senderId == null || senderId.isBlank()) {
            return null;
        }
        UUID knownSessionId;
        Long expectedBaseVersion;
        AciCustomerBrainMemorySnapshot candidateSnapshot;
        synchronized (mem) {
            knownSessionId = mem.lastSyncedSessionId;
            expectedBaseVersion = mem.lastSyncedDbVersion;
            candidateSnapshot = mem.toPersistedSnapshot();
        }
        AciSessionMemoryPersistenceService.PersistedSessionOutcome outcome =
                aciSessionMemoryPersistenceService.persistMemorySnapshot(
                        pageId,
                        senderId,
                        customerName,
                        knownSessionId,
                        expectedBaseVersion,
                        candidateSnapshot
                );
        if (outcome != null) {
            synchronized (mem) {
                mem.hydrateFromPersistedSnapshot(
                        outcome.mergedSnapshot(),
                        outcome.sessionId(),
                        outcome.dbVersion(),
                        outcome.optedOut()
                );
            }
        }
        return outcome;
    }

    /**
     * Evicts only the in-JVM cache entry without touching PostgreSQL {@code aci_memory_state},
     * used to simulate JVM / container restarts in empirical Lazy Hydration tests.
     */
    public void evictInMemoryCacheOnly(UUID pageId, String senderId) {
        customerMemoryStore.remove(toConversationMemoryKey(pageId, senderId));
    }

    public void evictAllInMemoryCachesOnly() {
        customerMemoryStore.clear();
    }

    public boolean hasInMemoryCacheEntry(UUID pageId, String senderId) {
        return customerMemoryStore.containsKey(toConversationMemoryKey(pageId, senderId));
    }

    public String getInferredCustomerPronoun(UUID pageId, String senderId) {
        CustomerBrainMemory mem = resolveMemoryReadOnly(pageId, senderId);
        return mem != null ? mem.inferredCustomerPronounSnapshot() : null;
    }

    public double getInferredPronounConfidence(UUID pageId, String senderId) {
        CustomerBrainMemory mem = resolveMemoryReadOnly(pageId, senderId);
        return mem != null ? mem.inferredPronounConfidenceSnapshot() : 0.0;
    }

    public String getInferredPronounEvidenceTurnId(UUID pageId, String senderId) {
        CustomerBrainMemory mem = resolveMemoryReadOnly(pageId, senderId);
        return mem != null ? mem.inferredPronounEvidenceTurnIdSnapshot() : null;
    }

    public String getVerifiedSymptomDuration(UUID pageId, String senderId) {
        CustomerBrainMemory mem = resolveMemoryReadOnly(pageId, senderId);
        return mem != null ? mem.verifiedSymptomDurationSnapshot() : null;
    }

    public String getCommunicationStylePreference(UUID pageId, String senderId) {
        CustomerBrainMemory mem = resolveMemoryReadOnly(pageId, senderId);
        return mem != null ? mem.communicationStylePreferenceSnapshot() : "STANDARD";
    }

    public AdaptiveConversationIntelligence.CustomerUnderstandingDraft2Snapshot getCustomerUnderstandingDraft2Snapshot(
            UUID pageId,
            String senderId
    ) {
        CustomerBrainMemory mem = resolveMemoryReadOnly(pageId, senderId);
        return mem != null ? mem.draft2Snapshot() : AdaptiveConversationIntelligence.CustomerUnderstandingDraft2Snapshot.initial();
    }

    public List<String> getRecalledCareNotes(UUID pageId, String senderId) {
        CustomerBrainMemory mem = resolveMemoryReadOnly(pageId, senderId);
        return mem != null ? mem.careNotesSnapshot() : List.of();
    }

    public List<String> getRecalledVerifiedFacts(UUID pageId, String senderId) {
        CustomerBrainMemory mem = resolveMemoryReadOnly(pageId, senderId);
        return mem != null ? mem.verifiedFactsSnapshot() : List.of();
    }

    public List<FactMutationRecord> getFactMutationHistory(UUID pageId, String senderId) {
        CustomerBrainMemory mem = resolveMemoryReadOnly(pageId, senderId);
        return mem != null ? mem.factMutationsSnapshot() : List.of();
    }

    public List<String> getSupersededOrRetractedFacts(UUID pageId, String senderId) {
        CustomerBrainMemory mem = resolveMemoryReadOnly(pageId, senderId);
        return mem != null ? mem.supersededOrRetractedFactsSnapshot() : List.of();
    }

    public List<String> getUncertainExplorations(UUID pageId, String senderId) {
        CustomerBrainMemory mem = resolveMemoryReadOnly(pageId, senderId);
        return mem != null ? mem.uncertainExplorationsSnapshot() : List.of();
    }

    public String getRecalledWorkingHypothesis(UUID pageId, String senderId) {
        CustomerBrainMemory mem = resolveMemoryReadOnly(pageId, senderId);
        return mem != null ? mem.workingHypothesisSnapshot() : "";
    }

    public LivingHypothesisState getLivingHypothesisState(UUID pageId, String senderId) {
        CustomerBrainMemory mem = resolveMemoryReadOnly(pageId, senderId);
        return mem != null ? mem.livingHypothesisStateSnapshot() : LivingHypothesisState.initialUnknown();
    }

    public BrainReasoningProvenance getLastReasoningProvenance(UUID pageId, String senderId) {
        CustomerBrainMemory mem = resolveMemoryReadOnly(pageId, senderId);
        return mem != null ? mem.lastReasoningProvenanceSnapshot() : null;
    }

    public String getRecalledActualSentSpeechAct(UUID pageId, String senderId) {
        CustomerBrainMemory mem = resolveMemoryReadOnly(pageId, senderId);
        return mem != null ? mem.lastActualSentSpeechActSnapshot() : "";
    }

    public int getRecalledPriceObjectionCount(UUID pageId, String senderId) {
        CustomerBrainMemory mem = resolveMemoryReadOnly(pageId, senderId);
        return mem != null ? mem.priceObjectionCountSnapshot() : 0;
    }

    public int getPriceObjectionCount(UUID pageId, String senderId) {
        return getRecalledPriceObjectionCount(pageId, senderId);
    }

    public boolean isRecalledInPriceObjectionContext(UUID pageId, String senderId) {
        CustomerBrainMemory mem = resolveMemoryReadOnly(pageId, senderId);
        return mem != null && mem.inPriceObjectionContextSnapshot();
    }

    public boolean hasRecalledPresentedDownsell(UUID pageId, String senderId) {
        CustomerBrainMemory mem = resolveMemoryReadOnly(pageId, senderId);
        return mem != null && mem.hasPresentedDownsellSnapshot();
    }

    public void recordCommercialFunnelState(
            UUID pageId,
            String senderId,
            int priceObjectionCount,
            boolean inPriceObjectionContext,
            boolean hasPresentedDownsell) {
        CustomerBrainMemory mem = getOrHydrateMemoryForMutation(pageId, senderId);
        synchronized (mem) {
            mem.priceObjectionCount = Math.max(0, priceObjectionCount);
            mem.inPriceObjectionContext = inPriceObjectionContext;
            mem.hasPresentedDownsell = hasPresentedDownsell;
        }
        persistMemoryToDbIfEnabled(pageId, senderId, null, mem);
    }

    public void recordBookingState(
            UUID pageId,
            String senderId,
            ConversationState.StructuredBookingState bookingState) {
        if (bookingState == null) {
            return;
        }
        CustomerBrainMemory mem = getOrHydrateMemoryForMutation(pageId, senderId);
        synchronized (mem) {
            mem.structuredBookingState = bookingState;
        }
        persistMemoryToDbIfEnabled(pageId, senderId, null, mem);
    }

    public ConversationState.StructuredBookingState recallBookingState(UUID pageId, String senderId) {
        CustomerBrainMemory mem = resolveMemoryReadOnly(pageId, senderId);
        return mem != null && mem.structuredBookingStateSnapshot() != null
                ? mem.structuredBookingStateSnapshot()
                : ConversationState.StructuredBookingState.none();
    }

    public void clearCustomerMemory(UUID pageId, String senderId) {
        customerMemoryStore.remove(toConversationMemoryKey(pageId, senderId));
        if (isAciDbPersistenceEnabled()) {
            aciSessionMemoryPersistenceService.resetPersistedMemory(pageId, senderId);
        }
    }

    // =========================================================================
    // ACI (LLM_COGNITIVE_V2) 5-TIER MEMORY & SESSION-LEVEL COMMITMENT API
    // =========================================================================

    public List<AdaptiveConversationIntelligence.CustomerStatementRecord> getAciCustomerStatements(UUID pageId, String senderId) {
        CustomerBrainMemory mem = resolveMemoryReadOnly(pageId, senderId);
        return mem != null ? mem.customerStatementsSnapshot() : List.of();
    }

    public AdaptiveConversationIntelligence.WorkingHypothesisRecord getAciWorkingHypothesis(UUID pageId, String senderId) {
        CustomerBrainMemory mem = resolveMemoryReadOnly(pageId, senderId);
        return mem != null ? mem.aciWorkingHypothesisSnapshot() : AdaptiveConversationIntelligence.WorkingHypothesisRecord.initialEmpty();
    }

    public List<AdaptiveConversationIntelligence.UnverifiedParserCandidate> getLatestUnverifiedParserCandidates(UUID pageId, String senderId) {
        CustomerBrainMemory mem = resolveMemoryReadOnly(pageId, senderId);
        return mem != null ? mem.latestUnverifiedParserCandidatesSnapshot() : List.of();
    }

    public List<AdaptiveConversationIntelligence.VerifiedBusinessFact> getAuthoritativeBusinessFacts(UUID pageId, String senderId) {
        CustomerBrainMemory mem = resolveMemoryReadOnly(pageId, senderId);
        return mem != null ? mem.authoritativeBusinessFactsSnapshot() : List.of();
    }

    public AdaptiveConversationIntelligence.OperationalStateSnapshot getAciOperationalState(UUID pageId, String senderId) {
        CustomerBrainMemory mem = resolveMemoryReadOnly(pageId, senderId);
        return mem != null ? mem.operationalStateSnapshot() : AdaptiveConversationIntelligence.OperationalStateSnapshot.initial();
    }

    public AdaptiveConversationIntelligence.PreviousStrategyRecord getPreviousStrategyRecord(UUID pageId, String senderId) {
        CustomerBrainMemory mem = resolveMemoryReadOnly(pageId, senderId);
        return mem != null ? mem.previousStrategyRecordSnapshot() : AdaptiveConversationIntelligence.PreviousStrategyRecord.initialNone();
    }

    public List<AdaptiveConversationIntelligence.CommitmentRecord> getSessionCommitments(UUID pageId, String senderId) {
        CustomerBrainMemory mem = resolveMemoryReadOnly(pageId, senderId);
        return mem != null ? mem.sessionCommitmentsSnapshot() : List.of();
    }

    /**
     * Chỉ Java Core hoặc nguồn nghiệp vụ có thẩm quyền mới được phép ghi vào Tier 4 ({@code Verified Business Fact}).
     */
    public void recordAuthoritativeBusinessFact(
            UUID pageId,
            String senderId,
            String category,
            String factKey,
            String factValue,
            String authoritativeSource
    ) {
        if (factKey == null || factKey.isBlank() || factValue == null || factValue.isBlank()) {
            return;
        }
        CustomerBrainMemory mem = getOrHydrateMemoryForMutation(pageId, senderId);
        synchronized (mem) {
            mem.authoritativeBusinessFacts.removeIf(f -> f != null && factKey.strip().equalsIgnoreCase(f.factKey()));
            mem.authoritativeBusinessFacts.add(new AdaptiveConversationIntelligence.VerifiedBusinessFact(
                    null,
                    category,
                    factKey,
                    factValue,
                    authoritativeSource != null ? authoritativeSource : "JAVA_CORE_AUTHORITY",
                    Instant.now()
            ));
        }
        persistMemoryToDbIfEnabled(pageId, senderId, null, mem);
    }

    /**
     * Chỉ Java Core hoặc nguồn nghiệp vụ có thẩm quyền mới được xác nhận hoàn thành một cam kết
     * ({@code commitment_status = completed} &amp; {@code execution_status = completed}) khi có {@code verificationEvidence}.
     */
    public boolean confirmCommitmentCompletedByAuthority(
            UUID pageId,
            String senderId,
            String commitmentIdOrTaskId,
            String verificationEvidence
    ) {
        if (commitmentIdOrTaskId == null || commitmentIdOrTaskId.isBlank()
                || verificationEvidence == null || verificationEvidence.isBlank()) {
            return false;
        }
        CustomerBrainMemory mem = resolveMemoryReadOnly(pageId, senderId);
        if (mem == null) {
            return false;
        }
        String key = commitmentIdOrTaskId.strip();
        boolean updated = false;
        synchronized (mem) {
            for (int i = 0; i < mem.sessionCommitments.size(); i++) {
                AdaptiveConversationIntelligence.CommitmentRecord c = mem.sessionCommitments.get(i);
                if (c != null && (key.equalsIgnoreCase(c.commitmentId())
                        || (c.taskId() != null && key.equalsIgnoreCase(c.taskId())))) {
                    mem.sessionCommitments.set(i, new AdaptiveConversationIntelligence.CommitmentRecord(
                            c.commitmentId(),
                            c.createdTurnId(),
                            c.updatedTurnId(),
                            c.content(),
                            c.owner(),
                            c.verifiedTimeframe(),
                            AdaptiveConversationIntelligence.CommitmentStatus.completed,
                            AdaptiveConversationIntelligence.TaskExecutionStatus.completed,
                            c.taskId(),
                            verificationEvidence.strip(),
                            Instant.now()
                    ));
                    updated = true;
                    break;
                }
            }
        }
        if (updated) {
            persistMemoryToDbIfEnabled(pageId, senderId, null, mem);
        }
        return updated;
    }

    /**
     * Đồng bộ tức thì cờ {@code optedOut} trong bộ nhớ phiên ACI V2 với trạng thái bền vững trên PostgreSQL (Unit P0.4).
     */
    public void syncOptedOutState(UUID pageId, String senderId, boolean nextOptedOut) {
        if (senderId == null || senderId.isBlank()) {
            return;
        }
        CustomerBrainMemory mem = getOrHydrateMemoryForMutation(pageId, senderId);
        synchronized (mem) {
            AdaptiveConversationIntelligence.OperationalStateSnapshot prev = mem.operationalState;
            if (prev != null) {
                mem.operationalState = new AdaptiveConversationIntelligence.OperationalStateSnapshot(
                        prev.bookingStatus(),
                        prev.handoffStatus(),
                        nextOptedOut,
                        prev.activeHandoffTicketId(),
                        prev.lastHandoffError(),
                        prev.communicationStylePreference()
                );
            } else if (nextOptedOut) {
                mem.operationalState = new AdaptiveConversationIntelligence.OperationalStateSnapshot(
                        "NONE",
                        AdaptiveConversationIntelligence.HandoffLifecycleStatus.NONE,
                        true,
                        null,
                        null,
                        mem.communicationStylePreference
                );
            }
        }
        persistMemoryToDbIfEnabled(pageId, senderId, null, mem);
    }

    /**
     * Cập nhật nguyên tử ({@code synchronized} trên khóa {@code pageId + ":" + senderId}) toàn bộ 5 tầng bộ nhớ,
     * vòng đời {@code Working Hypothesis} 5 thao tác, bản ghi chiến thuật lượt trước và danh sách {@code CommitmentRecord}.
     *
     * <p>Các bất biến phân quyền được cưỡng chế:
     * <ul>
     *   <li>{@code unverifiedParserCandidates} và {@code customerStatements} tuyệt đối KHÔNG được nâng lên {@code Verified Business Fact}.</li>
     *   <li>{@code Working Hypothesis} cập nhật theo 5 thao tác ({@code MAINTAIN | REINFORCE | ADJUST | DOWNGRADE | REJECT})
     *       mà không bị xóa trắng bởi giới hạn {@code < 12} ký tự hay đếm trùng từ mặt chữ.</li>
     *   <li>LLM không thể đánh dấu {@code completed} trên {@code CommitmentRecord}; SLA cụ thể chỉ được lưu nếu {@code verifiedPageSlaPolicy} có giá trị.</li>
     * </ul>
     */
    public void applyAciTurnMemoryUpdates(
            UUID pageId,
            String senderId,
            String currentTurnId,
            String originalCustomerMessage,
            List<AdaptiveConversationIntelligence.UnverifiedParserCandidate> unverifiedParserCandidates,
            AdaptiveConversationIntelligence.AdaptiveCognitiveReflection reflection,
            ConversationState.StructuredBookingState authoritativeBookingState,
            AdaptiveConversationIntelligence.ConditionalHandoffDecision handoffDecision,
            boolean optedOut,
            String verifiedPageSlaPolicy,
            String actualSentSpeechAct
    ) {
        CustomerBrainMemory mem = getOrHydrateMemoryForMutation(pageId, senderId);
        String safeTurnId = (currentTurnId != null && !currentTurnId.isBlank()) ? currentTurnId.strip() : "T_CURRENT";
        String cleanVerifiedSla = (verifiedPageSlaPolicy != null && !verifiedPageSlaPolicy.isBlank())
                ? verifiedPageSlaPolicy.strip()
                : null;

        synchronized (mem) {
            java.util.OptionalInt explicitTurnOrder = AciCustomerBrainMemorySnapshot.extractTurnOrder(safeTurnId);
            if (explicitTurnOrder.isPresent()) {
                mem.lastUpdatedTurnId = safeTurnId;
                mem.highestObservedTurnSeq = explicitTurnOrder.getAsInt();
            } else {
                mem.highestObservedTurnSeq = mem.highestObservedTurnSeq + 1;
                mem.lastUpdatedTurnId = "T" + mem.highestObservedTurnSeq;
            }

            // Tier 3: Unverified Parser Candidates (chỉ lưu làm ứng viên chưa xác minh, cấm ghi sang verifiedFacts / authoritativeBusinessFacts)
            mem.latestUnverifiedParserCandidates = unverifiedParserCandidates != null
                    ? List.copyOf(unverifiedParserCandidates)
                    : List.of();

            // Tier 1: Customer Statements (lời khách tự khai từ LLM + các tuyên bố nhạy cảm như thanh toán / lời hứa nhân viên)
            List<String> proposedStatements = new ArrayList<>();
            if (reflection != null && reflection.customerStatements() != null) {
                proposedStatements.addAll(reflection.customerStatements());
            }
            if (originalCustomerMessage != null && !originalCustomerMessage.isBlank()
                    && (AdaptiveConversationIntelligence.isPaymentClaimMessage(originalCustomerMessage)
                        || AdaptiveConversationIntelligence.isStaffPromiseClaimMessage(originalCustomerMessage)
                        || AdaptiveConversationIntelligence.isConcreteServiceIncidentMessage(originalCustomerMessage))) {
                String trimmedOriginal = originalCustomerMessage.strip();
                boolean alreadyCovered = proposedStatements.stream()
                        .anyMatch(s -> s != null && s.strip().equalsIgnoreCase(trimmedOriginal));
                if (!alreadyCovered) {
                    proposedStatements.add(trimmedOriginal);
                }
            }
            for (String stmt : proposedStatements) {
                if (stmt == null || stmt.isBlank()) {
                    continue;
                }
                String cleanStmt = stmt.strip();
                boolean duplicate = mem.customerStatements.stream()
                        .anyMatch(r -> r != null && r.content().equalsIgnoreCase(cleanStmt));
                if (!duplicate) {
                    if (mem.customerStatements.size() >= 16) {
                        mem.customerStatements.remove(0);
                    }
                    mem.customerStatements.add(new AdaptiveConversationIntelligence.CustomerStatementRecord(
                            null,
                            safeTurnId,
                            AdaptiveConversationIntelligence.categorizeCustomerStatement(cleanStmt),
                            cleanStmt,
                            false,
                            Instant.now()
                    ));
                }
            }

            // Tier 2: Working Hypothesis (Vòng đời 5 thao tác không xóa trắng bởi <12 ký tự hay đếm trùng từ mặt chữ)
            if (reflection != null && reflection.workingHypothesis() != null) {
                mem.aciWorkingHypothesis = AdaptiveConversationIntelligence.applyWorkingHypothesisLifecycle(
                        mem.aciWorkingHypothesis,
                        reflection.workingHypothesis(),
                        safeTurnId
                );
                mem.workingHypothesis = mem.aciWorkingHypothesis.content();
                HypothesisStatus bridgedStatus = switch (mem.aciWorkingHypothesis.lastLifecycleOp()) {
                    case MAINTAIN, REINFORCE -> HypothesisStatus.MAINTAINED_CONSISTENT;
                    case ADJUST -> HypothesisStatus.ACTIVE_NEW_EVIDENCE;
                    case DOWNGRADE -> HypothesisStatus.DOWNGRADED_OPERATIONAL_PIVOT;
                    case REJECT -> mem.aciWorkingHypothesis.content().isEmpty()
                            ? HypothesisStatus.REJECTED_BY_CUSTOMER
                            : HypothesisStatus.ACTIVE_NEW_EVIDENCE;
                };
                mem.livingHypothesisState = new LivingHypothesisState(
                        mem.aciWorkingHypothesis.content(),
                        bridgedStatus,
                        mem.aciWorkingHypothesis.confidence(),
                        String.join(",", mem.aciWorkingHypothesis.evidenceTurnIds()),
                        "ACI_V2_LIFECYCLE_" + mem.aciWorkingHypothesis.lastLifecycleOp().name(),
                        Instant.now()
                );
            }

            boolean isTimeoutFallbackTurn = reflection != null
                    && !reflection.parsedFromV2Schema()
                    && reflection.customerUnderstanding().detectedIntent().isBlank()
                    && reflection.strategy().chosen().isBlank();

            // Tier 5: Operational State (Java Core nắm quyền tuyệt đối; khi LLM timeout không hạ cấp bookingState đang active về NONE)
            if (authoritativeBookingState != null) {
                boolean wouldClobberActiveBookingOnTimeout = isTimeoutFallbackTurn
                        && authoritativeBookingState.status() == ConversationState.BookingStatus.NONE
                        && mem.structuredBookingState != null
                        && mem.structuredBookingState.status() != ConversationState.BookingStatus.NONE;
                if (!wouldClobberActiveBookingOnTimeout) {
                    mem.structuredBookingState = authoritativeBookingState;
                }
            }
            String bookingStatusStr = mem.structuredBookingState != null
                    ? mem.structuredBookingState.status().name()
                    : ConversationState.BookingStatus.NONE.name();
            AdaptiveConversationIntelligence.HandoffLifecycleStatus nextHandoffStatus = mem.operationalState.handoffStatus();
            String nextTicketId = mem.operationalState.activeHandoffTicketId();
            String nextHandoffError = mem.operationalState.lastHandoffError();
            if (handoffDecision != null && handoffDecision.resultingStatus() != AdaptiveConversationIntelligence.HandoffLifecycleStatus.NONE) {
                nextHandoffStatus = handoffDecision.resultingStatus();
                if (handoffDecision.ticketId() != null) {
                    nextTicketId = handoffDecision.ticketId();
                    nextHandoffError = null;
                } else if (handoffDecision.failureReason() != null) {
                    nextHandoffError = handoffDecision.failureReason();
                }
            }
            if (reflection != null && reflection.customerUnderstanding() != null) {
                String proposedPref = reflection.customerUnderstanding().communicationStylePreference();
                if ("CONCISE".equalsIgnoreCase(proposedPref)) {
                    mem.communicationStylePreference = "CONCISE";
                    mem.draft2EffectiveTurnDepth = "CONCISE";
                } else if ("DETAILED_ON_DEMAND".equalsIgnoreCase(proposedPref)) {
                    // Linh hoạt mở rộng chiều sâu cho đúng lượt khách chủ động hỏi chi tiết mà không phá mặc định CONCISE của phiên
                    mem.draft2EffectiveTurnDepth = "DETAILED_ON_DEMAND";
                } else if ("DETAILED".equalsIgnoreCase(proposedPref)) {
                    mem.communicationStylePreference = "DETAILED";
                    mem.draft2EffectiveTurnDepth = "DETAILED";
                } else if ("STANDARD".equalsIgnoreCase(proposedPref)
                        && !"CONCISE".equalsIgnoreCase(mem.communicationStylePreference)) {
                    // Giữ CONCISE xuyên suốt các lượt tiếp theo cho tới khi khách thực sự yêu cầu giải thích chi tiết (DETAILED)
                    mem.communicationStylePreference = "STANDARD";
                    mem.draft2EffectiveTurnDepth = "STANDARD";
                } else {
                    mem.draft2EffectiveTurnDepth = mem.communicationStylePreference;
                }
            }
            mem.operationalState = new AdaptiveConversationIntelligence.OperationalStateSnapshot(
                    bookingStatusStr,
                    nextHandoffStatus,
                    optedOut,
                    nextTicketId,
                    nextHandoffError,
                    mem.communicationStylePreference
            );

            // Adaptive Strategy Selection, Speaker Pronoun Inference & LLM-Verified Price Objection Tracking
            if (reflection != null) {
                AdaptiveConversationIntelligence.CustomerUnderstanding cu = reflection.customerUnderstanding();
                String explicitUtterancePronoun =
                        AdaptiveConversationIntelligence.inferExplicitCustomerSelfPronounFromUtterance(originalCustomerMessage);
                if (explicitUtterancePronoun != null) {
                    // ACI V3: Khi khách tự xưng tường minh ("anh"/"chị") trong phát ngôn, khóa cứng đại từ theo phát ngôn gốc
                    mem.inferredCustomerPronoun = explicitUtterancePronoun;
                    mem.inferredPronounConfidence = Math.max(0.95, cu.pronounConfidence());
                    mem.inferredPronounEvidenceTurnId = safeTurnId;
                } else if (cu.hasHighConfidenceInferredPronoun()
                        && (mem.inferredCustomerPronoun == null || mem.inferredPronounConfidence < 0.92)) {
                    mem.inferredCustomerPronoun = cu.inferredCustomerPronoun();
                    mem.inferredPronounConfidence = cu.pronounConfidence();
                    mem.inferredPronounEvidenceTurnId = safeTurnId;
                }
                if (cu.verifiedSymptomDuration() != null && !cu.verifiedSymptomDuration().isBlank()) {
                    mem.verifiedSymptomDuration = cu.verifiedSymptomDuration().strip();
                }
                boolean isMetaStyleComplaint = CleanAgentOutputRefereeAndSanitizer.isCommunicationStyleComplaintMessage(originalCustomerMessage)
                        || CleanAgentOutputRefereeAndSanitizer.isProcessOrConsultationFeedbackComplaint(originalCustomerMessage);
                boolean isNonObjectionTurnIntent = CleanAgentOrchestratorService.isPureBudgetBoundedServiceInquiry(originalCustomerMessage)
                        || AdaptiveConversationIntelligence.isPrivacyConcernMessage(originalCustomerMessage)
                        || AdaptiveConversationIntelligence.isCustomerDeferralMessage(originalCustomerMessage)
                        || AdaptiveConversationIntelligence.isBarePauseOrAcknowledgementMessage(originalCustomerMessage)
                        || AdaptiveConversationIntelligence.isExplicitGenderOrPronounCorrectionMessage(originalCustomerMessage)
                        || CleanAgentOrchestratorService.isExplicitConversationRefusalStopMessage(originalCustomerMessage)
                        || CleanAgentOrchestratorService.isPoliteThankYouClosingAckMessage(originalCustomerMessage)
                        || (CleanAgentOrchestratorService.hasCompetitorOrExternalPriceComparisonCue(originalCustomerMessage)
                            && !AdaptiveConversationIntelligence.hasGenuinePriceObjectionCue(originalCustomerMessage));
                boolean llmCostConcern = !isMetaStyleComplaint
                        && !isNonObjectionTurnIntent
                        && AdaptiveConversationIntelligence.hasGenuinePriceObjectionCue(originalCustomerMessage);
                if (llmCostConcern) {
                    mem.priceObjectionCount = mem.priceObjectionCount + 1;
                    mem.inPriceObjectionContext = true;
                } else if (Boolean.FALSE.equals(cu.costConcernExpressed())
                        && !"VALUE_ALIGNMENT".equalsIgnoreCase(reflection.priority().primary())
                        && !"BUDGET_FIT".equalsIgnoreCase(reflection.priority().primary())) {
                    mem.inPriceObjectionContext = false;
                }

                boolean llmOutputUnavailable = !reflection.parsedFromV2Schema()
                        && cu.detectedIntent().isBlank()
                        && reflection.strategy().chosen().isBlank();
                if (!llmOutputUnavailable) {
                    String prio = reflection.priority().primary();
                    String chosenStrat = !reflection.strategy().chosen().isBlank()
                            ? reflection.strategy().chosen()
                            : (actualSentSpeechAct != null ? actualSentSpeechAct : "");
                    String evidenceStr = !reflection.strategy().evidenceTurnIds().isEmpty()
                            ? String.join(",", reflection.strategy().evidenceTurnIds())
                            : safeTurnId;
                    mem.previousStrategyRecord = new AdaptiveConversationIntelligence.PreviousStrategyRecord(
                            prio,
                            chosenStrat,
                            reflection.strategy().previousOutcome(),
                            evidenceStr,
                            safeTurnId,
                            Instant.now()
                    );
                } else if (mem.previousStrategyRecord == null) {
                    mem.previousStrategyRecord = new AdaptiveConversationIntelligence.PreviousStrategyRecord(
                            "NONE",
                            "LLM_OUTPUT_UNAVAILABLE",
                            AdaptiveConversationIntelligence.StrategyOutcome.NOT_APPLICABLE,
                            safeTurnId,
                            safeTurnId,
                            Instant.now()
                    );
                }
            }
            if (actualSentSpeechAct != null && !actualSentSpeechAct.isBlank()) {
                mem.lastActualSentSpeechAct = actualSentSpeechAct.strip();
            }

            // Session-Level Commitment Management (tách bạch commitment_status vs execution_status)
            AdaptiveConversationIntelligence.TaskExecutionStatus defaultNewExecStatus =
                    (handoffDecision != null && handoffDecision.isHandoffCreated())
                            ? AdaptiveConversationIntelligence.TaskExecutionStatus.created
                            : ((handoffDecision != null
                                && handoffDecision.resultingStatus() == AdaptiveConversationIntelligence.HandoffLifecycleStatus.HANDOFF_FAILED)
                                ? AdaptiveConversationIntelligence.TaskExecutionStatus.failed
                                : AdaptiveConversationIntelligence.TaskExecutionStatus.not_created);
            String dispatchedTaskId = (handoffDecision != null && handoffDecision.isHandoffCreated())
                    ? handoffDecision.ticketId()
                    : null;

            boolean createdOrUpdatedCommitmentThisTurn = false;
            if (reflection != null && reflection.commitmentDelta() != null) {
                for (AdaptiveConversationIntelligence.CommitmentDeltaItem delta : reflection.commitmentDelta()) {
                    if (delta == null || delta.operation() == null) {
                        continue;
                    }
                    // Quyền hạn SLA: Nếu Page không có SLA đã xác minh, cấm lưu SLA tự bịa của LLM!
                    String effectiveTimeframe = cleanVerifiedSla != null ? delta.verifiedTimeframe() : null;
                    switch (delta.operation()) {
                        case CREATE -> {
                            int existingIdx = findMatchingPendingCommitmentIndex(mem.sessionCommitments, delta.owner(), delta.content());
                            if (existingIdx >= 0) {
                                AdaptiveConversationIntelligence.CommitmentRecord prev = mem.sessionCommitments.get(existingIdx);
                                AdaptiveConversationIntelligence.TaskExecutionStatus mergedExec =
                                        prev.executionStatus() != AdaptiveConversationIntelligence.TaskExecutionStatus.not_created
                                                ? prev.executionStatus()
                                                : defaultNewExecStatus;
                                String mergedTaskId = prev.taskId() != null ? prev.taskId() : dispatchedTaskId;
                                mem.sessionCommitments.set(existingIdx, new AdaptiveConversationIntelligence.CommitmentRecord(
                                        prev.commitmentId(),
                                        prev.createdTurnId(),
                                        safeTurnId,
                                        !delta.content().isBlank() ? delta.content() : prev.content(),
                                        delta.owner(),
                                        effectiveTimeframe != null ? effectiveTimeframe : prev.verifiedTimeframe(),
                                        prev.commitmentStatus(),
                                        mergedExec,
                                        mergedTaskId,
                                        prev.verificationEvidence(),
                                        Instant.now()
                                ));
                            } else {
                                mem.sessionCommitments.add(new AdaptiveConversationIntelligence.CommitmentRecord(
                                        null,
                                        safeTurnId,
                                        safeTurnId,
                                        delta.content(),
                                        delta.owner(),
                                        effectiveTimeframe,
                                        AdaptiveConversationIntelligence.CommitmentStatus.pending,
                                        defaultNewExecStatus,
                                        dispatchedTaskId,
                                        null,
                                        Instant.now()
                                ));
                            }
                            createdOrUpdatedCommitmentThisTurn = true;
                        }
                        case UPDATE_CONTENT -> {
                            int targetIdx = findLatestPendingCommitmentIndex(mem.sessionCommitments, delta.owner());
                            if (targetIdx >= 0) {
                                AdaptiveConversationIntelligence.CommitmentRecord prev = mem.sessionCommitments.get(targetIdx);
                                mem.sessionCommitments.set(targetIdx, new AdaptiveConversationIntelligence.CommitmentRecord(
                                        prev.commitmentId(),
                                        prev.createdTurnId(),
                                        safeTurnId,
                                        !delta.content().isBlank() ? delta.content() : prev.content(),
                                        delta.owner() != null ? delta.owner() : prev.owner(),
                                        effectiveTimeframe != null ? effectiveTimeframe : prev.verifiedTimeframe(),
                                        prev.commitmentStatus(),
                                        prev.executionStatus(),
                                        prev.taskId(),
                                        prev.verificationEvidence(),
                                        Instant.now()
                                ));
                                createdOrUpdatedCommitmentThisTurn = true;
                            }
                        }
                        case REMIND -> {
                            int targetIdx = findLatestPendingCommitmentIndex(mem.sessionCommitments, delta.owner());
                            if (targetIdx >= 0) {
                                AdaptiveConversationIntelligence.CommitmentRecord prev = mem.sessionCommitments.get(targetIdx);
                                mem.sessionCommitments.set(targetIdx, new AdaptiveConversationIntelligence.CommitmentRecord(
                                        prev.commitmentId(),
                                        prev.createdTurnId(),
                                        safeTurnId,
                                        prev.content(),
                                        prev.owner(),
                                        prev.verifiedTimeframe(),
                                        prev.commitmentStatus(),
                                        prev.executionStatus(),
                                        prev.taskId(),
                                        prev.verificationEvidence(),
                                        Instant.now()
                                ));
                                createdOrUpdatedCommitmentThisTurn = true;
                            }
                        }
                        case CANCEL_BY_CUSTOMER -> {
                            int targetIdx = findLatestPendingCommitmentIndex(mem.sessionCommitments, delta.owner());
                            if (targetIdx >= 0) {
                                AdaptiveConversationIntelligence.CommitmentRecord prev = mem.sessionCommitments.get(targetIdx);
                                mem.sessionCommitments.set(targetIdx, new AdaptiveConversationIntelligence.CommitmentRecord(
                                        prev.commitmentId(),
                                        prev.createdTurnId(),
                                        safeTurnId,
                                        prev.content(),
                                        prev.owner(),
                                        prev.verifiedTimeframe(),
                                        AdaptiveConversationIntelligence.CommitmentStatus.cancelled,
                                        prev.executionStatus(),
                                        prev.taskId(),
                                        prev.verificationEvidence(),
                                        Instant.now()
                                ));
                                createdOrUpdatedCommitmentThisTurn = true;
                            }
                        }
                    }
                }
            }

            // Nếu Java Core vừa tạo sự kiện xác minh/handoff thực tế (VD: S08 đối soát chuyển khoản, S09 xác minh cam kết nhân viên, A08 sự cố lịch hẹn)
            // mà LLM chưa kịp ghi vào commitment_delta -> tự động đồng bộ 1 CommitmentRecord pending kèm execution_status & task_id thực tế!
            if (!createdOrUpdatedCommitmentThisTurn
                    && handoffDecision != null
                    && handoffDecision.policyApprovedCreation()) {
                AdaptiveConversationIntelligence.CommitmentOwner inferredOwner =
                        "PAYMENT_VERIFICATION_HANDOFF".equals(handoffDecision.handoffCategory())
                                ? AdaptiveConversationIntelligence.CommitmentOwner.CASHIER
                                : AdaptiveConversationIntelligence.CommitmentOwner.MANAGER;
                int existingIdx = findLatestPendingCommitmentIndex(mem.sessionCommitments, inferredOwner);
                if (existingIdx < 0) {
                    mem.sessionCommitments.add(new AdaptiveConversationIntelligence.CommitmentRecord(
                            null,
                            safeTurnId,
                            safeTurnId,
                            handoffDecision.handoffReason() != null ? handoffDecision.handoffReason() : "Chuyển bộ phận phụ trách kiểm tra và phản hồi",
                            inferredOwner,
                            cleanVerifiedSla,
                            AdaptiveConversationIntelligence.CommitmentStatus.pending,
                            defaultNewExecStatus,
                            dispatchedTaskId,
                            null,
                            Instant.now()
                    ));
                }
            }

            // Cập nhật Customer Understanding Model (Draft 2 — 3 Tầng Ngữ cảnh & Versioned Facts) + Adaptive Response Planner
            applyDraft2CustomerUnderstandingUpdatesLocked(
                    mem,
                    safeTurnId,
                    originalCustomerMessage,
                    reflection,
                    authoritativeBookingState,
                    actualSentSpeechAct
            );
        }
        persistMemoryToDbIfEnabled(pageId, senderId, null, mem);
    }

    private static boolean isGenericOpeningPlaceholderRequest(String item) {
        if (item == null || item.isBlank()) {
            return false;
        }
        String norm = Normalizer.normalize(item.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        return norm.equals("tư vấn")
                || norm.equals("tư vấn chung")
                || norm.equals("tư vấn dịch vụ")
                || norm.equals("cần tư vấn")
                || norm.equals("hỏi giá")
                || norm.equals("hỏi giá chung")
                || norm.equals("hỏi bảng giá");
    }

    private static boolean containsPastOrNegatedSymptomQualifier(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return false;
        }
        String norm = Normalizer.normalize(rawMessage.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        return norm.matches("(?isU).*\\b(từng\\s+bị|trước\\s+đây|hồi\\s+trước|đã\\s+khỏi|khỏi\\s+rồi|hết\\s+bị|hết\\s+rồi|không\\s+bị|không\\s+còn)\\b.*");
    }

    private static boolean looksLikeConcretePainOrSymptomClause(String clause) {
        if (clause == null || clause.isBlank()) {
            return false;
        }
        String norm = Normalizer.normalize(clause.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        String[] words = norm.split("\\s+");
        if (words.length < 2 || words.length > 9) {
            return false;
        }
        return norm.matches("(?isU).*\\b(đau|mỏi|nhức|buốt|tê|căng|cứng|nặng|khó\\s+ngủ|mất\\s+ngủ|chóng\\s+mặt|hoa\\s+mắt)\\b.*");
    }

    private static void applyDraft2CustomerUnderstandingUpdatesLocked(
            CustomerBrainMemory mem,
            String safeTurnId,
            String originalCustomerMessage,
            AdaptiveConversationIntelligence.AdaptiveCognitiveReflection reflection,
            ConversationState.StructuredBookingState authoritativeBookingState,
            String actualSentSpeechAct
    ) {
        if (reflection == null) {
            return;
        }
        String nowIso = Instant.now().toString();
        AdaptiveConversationIntelligence.CustomerUnderstanding cu = reflection.customerUnderstanding();
        boolean llmOutputUnavailable = !reflection.parsedFromV2Schema()
                && cu.detectedIntent().isBlank()
                && reflection.strategy().chosen().isBlank();
        if (llmOutputUnavailable) {
            // P3.7-A Requirement II.1.3 & II.1.4: Khi LLM timeout (LLM_OUTPUT_UNAVAILABLE),
            // tuyệt đối KHÔNG để fallback ghi đè trạng thái đặt lịch, lựa chọn gói, ý định hay chiến lược trước đó.
            mem.draft2LastBotAction = (actualSentSpeechAct != null && !actualSentSpeechAct.isBlank())
                    ? actualSentSpeechAct.strip()
                    : "LLM_OUTPUT_UNAVAILABLE";
            return;
        }

        // 1. Tầng 1 — Conversational Context (không lưu lặp current_user_message vào Profile dài hạn)
        String turnIntent = !cu.detectedIntent().isBlank() ? cu.detectedIntent() : "GENERAL_INQUIRY";
        mem.draft2LastTurnIntent = turnIntent;
        mem.draft2SecondaryIntents.clear();
        if (cu.secondaryIntents() != null) {
            mem.draft2SecondaryIntents.addAll(cu.secondaryIntents());
        }
        mem.draft2ConversationState = cu.conversationState();
        String nextTopic = reflection.currentTopic() != null && !reflection.currentTopic().isBlank()
                ? reflection.currentTopic()
                : turnIntent;
        mem.draft2CurrentTopic = nextTopic;
        if (mem.draft2TopicHistory.isEmpty()
                || !mem.draft2TopicHistory.get(mem.draft2TopicHistory.size() - 1).equalsIgnoreCase(nextTopic)) {
            if (mem.draft2TopicHistory.size() >= 12) {
                mem.draft2TopicHistory.remove(0);
            }
            mem.draft2TopicHistory.add(nextTopic);
        }

        // Vòng đời unresolved_requests: OPEN -> ANSWERED | CANCELLED | SUPERSEDED
        if (reflection.needRevisionDetected()) {
            for (int i = 0; i < mem.draft2UnresolvedRequests.size(); i++) {
                AdaptiveConversationIntelligence.UnresolvedRequestItem oldReq = mem.draft2UnresolvedRequests.get(i);
                if (oldReq != null && oldReq.status() == AdaptiveConversationIntelligence.UnresolvedRequestStatus.OPEN) {
                    mem.draft2UnresolvedRequests.set(i, new AdaptiveConversationIntelligence.UnresolvedRequestItem(
                            oldReq.item(),
                            AdaptiveConversationIntelligence.UnresolvedRequestStatus.SUPERSEDED,
                            oldReq.sourceTurnId(),
                            safeTurnId
                    ));
                }
            }
        }
        if (mem.highestObservedTurnSeq > 1 && !"GENERAL_INQUIRY".equalsIgnoreCase(turnIntent)) {
            for (int i = 0; i < mem.draft2UnresolvedRequests.size(); i++) {
                AdaptiveConversationIntelligence.UnresolvedRequestItem oldReq = mem.draft2UnresolvedRequests.get(i);
                if (oldReq != null
                        && oldReq.status() == AdaptiveConversationIntelligence.UnresolvedRequestStatus.OPEN
                        && !safeTurnId.equalsIgnoreCase(oldReq.sourceTurnId())
                        && isGenericOpeningPlaceholderRequest(oldReq.item())) {
                    mem.draft2UnresolvedRequests.set(i, new AdaptiveConversationIntelligence.UnresolvedRequestItem(
                            oldReq.item(),
                            AdaptiveConversationIntelligence.UnresolvedRequestStatus.ANSWERED,
                            oldReq.sourceTurnId(),
                            safeTurnId
                    ));
                }
            }
        }
        if (!reflection.unresolvedRequestsDelta().isEmpty()) {
            for (AdaptiveConversationIntelligence.UnresolvedRequestItem deltaReq : reflection.unresolvedRequestsDelta()) {
                if (deltaReq == null || deltaReq.item().isBlank()) continue;
                int matchIdx = -1;
                String normDeltaItem = Normalizer.normalize(deltaReq.item().strip(), Normalizer.Form.NFC)
                        .toLowerCase(Locale.ROOT);
                for (int i = 0; i < mem.draft2UnresolvedRequests.size(); i++) {
                    AdaptiveConversationIntelligence.UnresolvedRequestItem existing = mem.draft2UnresolvedRequests.get(i);
                    if (existing == null) continue;
                    String normExisting = Normalizer.normalize(existing.item().strip(), Normalizer.Form.NFC)
                            .toLowerCase(Locale.ROOT);
                    boolean semanticCloseMatch = deltaReq.status() != AdaptiveConversationIntelligence.UnresolvedRequestStatus.OPEN
                            && existing.status() == AdaptiveConversationIntelligence.UnresolvedRequestStatus.OPEN
                            && ((normExisting.contains(normDeltaItem) || normDeltaItem.contains(normExisting))
                                || (isGenericOpeningPlaceholderRequest(normExisting)
                                    && isGenericOpeningPlaceholderRequest(normDeltaItem)));
                    if (normExisting.equals(normDeltaItem) || semanticCloseMatch) {
                        matchIdx = i;
                        break;
                    }
                }
                if (matchIdx >= 0) {
                    AdaptiveConversationIntelligence.UnresolvedRequestItem existing = mem.draft2UnresolvedRequests.get(matchIdx);
                    mem.draft2UnresolvedRequests.set(matchIdx, new AdaptiveConversationIntelligence.UnresolvedRequestItem(
                            existing.item(),
                            deltaReq.status(),
                            existing.sourceTurnId(),
                            safeTurnId
                    ));
                } else {
                    if (mem.draft2UnresolvedRequests.size() >= 10) {
                        mem.draft2UnresolvedRequests.remove(0);
                    }
                    mem.draft2UnresolvedRequests.add(new AdaptiveConversationIntelligence.UnresolvedRequestItem(
                            deltaReq.item(),
                            deltaReq.status(),
                            safeTurnId,
                            safeTurnId
                    ));
                }
            }
        }

        if (!reflection.pendingReferences().isEmpty()) {
            mem.draft2PendingReferences.clear();
            for (String ref : reflection.pendingReferences()) {
                if (ref != null && !ref.isBlank() && mem.draft2PendingReferences.size() < 6) {
                    mem.draft2PendingReferences.add(ref.strip());
                }
            }
        }

        // Cập nhật InteractionPermission (OPEN | CONCISE_PREFERRED | BOUNDED_INFO_ONLY | PAUSED)
        AdaptiveConversationIntelligence.AdaptiveResponsePlan plan = reflection.responsePlan();
        if (cu.interactionPermission() != null) {
            mem.draft2InteractionPermission = cu.interactionPermission();
        } else if ("Respectful Pause".equalsIgnoreCase(plan.strategy())
                || plan.adaptation() == AdaptiveConversationIntelligence.StrategyAdaptationMode.STOP) {
            mem.draft2InteractionPermission = AdaptiveConversationIntelligence.InteractionPermission.PAUSED;
        } else if (cu.buyingSignal() == AdaptiveConversationIntelligence.BuyingSignal.STRONG
                || cu.buyingSignal() == AdaptiveConversationIntelligence.BuyingSignal.ACTION) {
            mem.draft2InteractionPermission = "CONCISE".equalsIgnoreCase(mem.communicationStylePreference)
                    ? AdaptiveConversationIntelligence.InteractionPermission.CONCISE_PREFERRED
                    : AdaptiveConversationIntelligence.InteractionPermission.OPEN;
        } else if ("CONCISE".equalsIgnoreCase(mem.communicationStylePreference)
                && mem.draft2InteractionPermission == AdaptiveConversationIntelligence.InteractionPermission.OPEN) {
            mem.draft2InteractionPermission = AdaptiveConversationIntelligence.InteractionPermission.CONCISE_PREFERRED;
        }

        if ("DETAILED_ON_DEMAND".equalsIgnoreCase(plan.depth())
                && "CONCISE".equalsIgnoreCase(mem.communicationStylePreference)) {
            mem.draft2EffectiveTurnDepth = "DETAILED_ON_DEMAND";
        }
        mem.draft2LastResponsePlan = new AdaptiveConversationIntelligence.AdaptiveResponsePlan(
                plan.objective(),
                plan.strategy(),
                plan.tone(),
                mem.draft2EffectiveTurnDepth,
                plan.nextAction(),
                plan.ctaLevel(),
                plan.adaptation(),
                plan.previousOutcome()
        );
        mem.draft2LastBotAction = !plan.strategy().isBlank()
                ? plan.strategy()
                : (actualSentSpeechAct != null && !actualSentSpeechAct.isBlank() ? actualSentSpeechAct : "Direct Answer");

        // 2. Tầng 2 — Consultative Profile: Tách turn_intent vs conversation_goal (bảo toàn goal khi Topic Shift!)
        boolean hasReviseOnNeed = reflection.needRevisionDetected()
                || reflection.confirmedFactsDelta().stream().anyMatch(d ->
                        d != null && (d.op() == AdaptiveConversationIntelligence.FactMutationOp.REVISE
                                || d.effectiveValidity() == AdaptiveConversationIntelligence.FactValidity.SUPERSEDED)
                                && "need_scope".equals(d.key()));
        String proposedGoal = reflection.conversationGoal();
        boolean isEstablishedGoal = mem.draft2ConversationGoal != null
                && !mem.draft2ConversationGoal.isBlank()
                && !"EXPLORE_SERVICES".equalsIgnoreCase(mem.draft2ConversationGoal);
        if (proposedGoal != null && !isSideTopicOrOperationalGoalString(proposedGoal)) {
            if (!isEstablishedGoal || hasReviseOnNeed) {
                mem.draft2ConversationGoal = proposedGoal;
            }
        }

        // Quản lý vòng đời 5 trạng thái triệu chứng nguyên tử (CURRENT | RESOLVED | PAST | NEGATED | SUPERSEDED) & Versioned Facts
        for (AdaptiveConversationIntelligence.FactDeltaProposal delta : reflection.confirmedFactsDelta()) {
            if (delta == null || delta.value().isBlank()) continue;
            AdaptiveConversationIntelligence.FactValidity targetValidity = delta.effectiveValidity();
            if ("self_reported_symptom".equals(delta.key())) {
                List<String> atomicSymptoms = AdaptiveConversationIntelligence.splitAtomicHealthExpressions(delta.value());
                for (String atomSym : atomicSymptoms) {
                    String canon = AdaptiveConversationIntelligence.canonicalSymptomKey(atomSym);
                    if (canon.isBlank()) continue;
                    int existingIdx = -1;
                    for (int i = 0; i < mem.draft2SelfReportedSymptoms.size(); i++) {
                        AdaptiveConversationIntelligence.VersionedCustomerFact f = mem.draft2SelfReportedSymptoms.get(i);
                        if (f != null && AdaptiveConversationIntelligence.canonicalSymptomKey(f.value()).equals(canon)) {
                            existingIdx = i;
                            break;
                        }
                    }
                    String statusLabel = targetValidity == AdaptiveConversationIntelligence.FactValidity.CURRENT
                            ? "CONFIRMED"
                            : targetValidity.name();
                    if (existingIdx >= 0) {
                        AdaptiveConversationIntelligence.VersionedCustomerFact existing = mem.draft2SelfReportedSymptoms.get(existingIdx);
                        if (existing.validity() != targetValidity) {
                            mem.draft2SelfReportedSymptoms.set(existingIdx,
                                    new AdaptiveConversationIntelligence.VersionedCustomerFact(
                                            "self_reported_symptom",
                                            existing.value(),
                                            statusLabel,
                                            "CUSTOMER_MESSAGE",
                                            safeTurnId,
                                            nowIso,
                                            targetValidity,
                                            delta.confidence()
                                    ));
                        }
                    } else {
                        if (mem.draft2SelfReportedSymptoms.size() >= 18) {
                            mem.draft2SelfReportedSymptoms.remove(0);
                        }
                        mem.draft2SelfReportedSymptoms.add(new AdaptiveConversationIntelligence.VersionedCustomerFact(
                                "self_reported_symptom",
                                atomSym,
                                statusLabel,
                                "CUSTOMER_MESSAGE",
                                safeTurnId,
                                nowIso,
                                targetValidity,
                                delta.confidence()
                        ));
                    }
                }
                continue;
            }

            if (targetValidity != AdaptiveConversationIntelligence.FactValidity.CURRENT) {
                int existingIdx = -1;
                for (int i = 0; i < mem.draft2ConfirmedFacts.size(); i++) {
                    AdaptiveConversationIntelligence.VersionedCustomerFact existing = mem.draft2ConfirmedFacts.get(i);
                    if (existing != null
                            && existing.key().equals(delta.key())
                            && existing.value().equalsIgnoreCase(delta.value())) {
                        existingIdx = i;
                        break;
                    }
                }
                if (existingIdx >= 0) {
                    mem.draft2ConfirmedFacts.set(existingIdx,
                            mem.draft2ConfirmedFacts.get(existingIdx).withValidity(targetValidity));
                } else {
                    if (mem.draft2ConfirmedFacts.size() >= 18) {
                        mem.draft2ConfirmedFacts.remove(0);
                    }
                    mem.draft2ConfirmedFacts.add(new AdaptiveConversationIntelligence.VersionedCustomerFact(
                            delta.key(),
                            delta.value(),
                            targetValidity.name(),
                            "CUSTOMER_MESSAGE",
                            safeTurnId,
                            nowIso,
                            targetValidity,
                            delta.confidence()
                    ));
                }
                continue;
            }

            boolean shouldSupersedePriorSameKey = delta.op() == AdaptiveConversationIntelligence.FactMutationOp.REVISE
                    || (hasReviseOnNeed && "need_scope".equals(delta.key()));
            if (shouldSupersedePriorSameKey) {
                for (int i = 0; i < mem.draft2ConfirmedFacts.size(); i++) {
                    AdaptiveConversationIntelligence.VersionedCustomerFact existing = mem.draft2ConfirmedFacts.get(i);
                    if (existing != null
                            && existing.key().equals(delta.key())
                            && existing.validity() == AdaptiveConversationIntelligence.FactValidity.CURRENT
                            && !existing.value().equalsIgnoreCase(delta.value())) {
                        mem.draft2ConfirmedFacts.set(i, existing.markSuperseded());
                    }
                }
            }
            boolean alreadyCurrent = mem.draft2ConfirmedFacts.stream()
                    .anyMatch(f -> f != null
                            && f.key().equals(delta.key())
                            && f.validity() == AdaptiveConversationIntelligence.FactValidity.CURRENT
                            && f.value().equalsIgnoreCase(delta.value()));
            if (!alreadyCurrent) {
                if (mem.draft2ConfirmedFacts.size() >= 18) {
                    mem.draft2ConfirmedFacts.remove(0);
                }
                mem.draft2ConfirmedFacts.add(new AdaptiveConversationIntelligence.VersionedCustomerFact(
                        delta.key(),
                        delta.value(),
                        "CONFIRMED",
                        "CUSTOMER_MESSAGE",
                        safeTurnId,
                        nowIso,
                        AdaptiveConversationIntelligence.FactValidity.CURRENT,
                        delta.confidence()
                ));
            }
        }

        // P3.6 (P2 — Context Continuity): Nếu khách chia sẻ câu đa triệu chứng hiện hành (VD: "Tôi bị đau mỏi chân tay và buốt vai")
        // mà LLM chỉ xuất 1 vế vào confirmed_facts_delta (do rút gọn 6 từ), tự động bảo toàn đầy đủ các vế triệu chứng đồng đẳng còn thiếu.
        boolean addedCurrentSymptomThisTurn = reflection.confirmedFactsDelta().stream()
                .anyMatch(d -> d != null && "self_reported_symptom".equals(d.key())
                        && d.effectiveValidity() == AdaptiveConversationIntelligence.FactValidity.CURRENT);
        boolean hasNonCurrentSymptomThisTurn = reflection.confirmedFactsDelta().stream()
                .anyMatch(d -> d != null && "self_reported_symptom".equals(d.key())
                        && d.effectiveValidity() != AdaptiveConversationIntelligence.FactValidity.CURRENT);
        if (addedCurrentSymptomThisTurn
                && !hasNonCurrentSymptomThisTurn
                && originalCustomerMessage != null
                && !originalCustomerMessage.isBlank()
                && !containsPastOrNegatedSymptomQualifier(originalCustomerMessage)) {
            List<String> verbatimClauses = AdaptiveConversationIntelligence.splitAtomicHealthExpressions(originalCustomerMessage);
            if (verbatimClauses.size() >= 2) {
                for (String clause : verbatimClauses) {
                    if (!looksLikeConcretePainOrSymptomClause(clause)) {
                        continue;
                    }
                    String canon = AdaptiveConversationIntelligence.canonicalSymptomKey(clause);
                    if (canon.isBlank()) continue;
                    boolean alreadyTracked = mem.draft2SelfReportedSymptoms.stream()
                            .anyMatch(f -> f != null && AdaptiveConversationIntelligence.canonicalSymptomKey(f.value()).equals(canon));
                    if (!alreadyTracked) {
                        if (mem.draft2SelfReportedSymptoms.size() >= 18) {
                            mem.draft2SelfReportedSymptoms.remove(0);
                        }
                        mem.draft2SelfReportedSymptoms.add(new AdaptiveConversationIntelligence.VersionedCustomerFact(
                                "self_reported_symptom",
                                clause,
                                "CONFIRMED",
                                "CUSTOMER_MESSAGE",
                                safeTurnId,
                                nowIso,
                                AdaptiveConversationIntelligence.FactValidity.CURRENT,
                                "HIGH"
                        ));
                    }
                }
            }
        }

        // Khi khách chê "Đắt": Fact chỉ ghi nhận khách phản ứng giá đắt; lý do đằng sau bắt buộc lưu ở Hypothesis!
        boolean llmCostConcern = Boolean.TRUE.equals(cu.costConcernExpressed())
                || "Handle Objection".equalsIgnoreCase(plan.strategy());
        if (llmCostConcern) {
            boolean hasPriceFact = mem.draft2ConfirmedFacts.stream()
                    .anyMatch(f -> f != null
                            && "price_reaction".equals(f.key())
                            && f.validity() == AdaptiveConversationIntelligence.FactValidity.CURRENT);
            if (!hasPriceFact) {
                mem.draft2ConfirmedFacts.add(new AdaptiveConversationIntelligence.VersionedCustomerFact(
                        "price_reaction",
                        "khách phản ứng giá đắt",
                        "CONFIRMED",
                        "CUSTOMER_MESSAGE",
                        safeTurnId,
                        nowIso,
                        AdaptiveConversationIntelligence.FactValidity.CURRENT,
                        "HIGH"
                ));
            }
        }

        String effectiveBarrier = reflection.barrierType();
        if (llmCostConcern && ("NONE".equalsIgnoreCase(effectiveBarrier) || effectiveBarrier == null)) {
            effectiveBarrier = "VALUE_PERCEPTION_GAP";
        }
        String currentHypContent = mem.aciWorkingHypothesis.content();
        if ((currentHypContent == null || currentHypContent.isBlank()
                || currentHypContent.toLowerCase(Locale.ROOT).contains("chưa rõ nhu cầu"))
                && (!mem.draft2SelfReportedSymptoms.isEmpty()
                    || (originalCustomerMessage != null && looksLikeConcretePainOrSymptomClause(originalCustomerMessage)))) {
            String cleanSymptomSummary = originalCustomerMessage != null ? originalCustomerMessage.strip() : "triệu chứng mỏi";
            mem.aciWorkingHypothesis = new AdaptiveConversationIntelligence.WorkingHypothesisRecord(
                    "Khách chia sẻ tình trạng: " + cleanSymptomSummary + ", cần liệu trình chăm sóc phù hợp",
                    AdaptiveConversationIntelligence.HypothesisLifecycleOp.ADJUST,
                    Math.max(0.80, mem.aciWorkingHypothesis.confidence()),
                    mem.aciWorkingHypothesis.evidenceTurnIds(),
                    Instant.now()
            );
            mem.workingHypothesis = mem.aciWorkingHypothesis.content();
        }
        mem.draft2BarrierHypothesis = new AdaptiveConversationIntelligence.BarrierHypothesis(
                effectiveBarrier,
                mem.aciWorkingHypothesis.content(),
                reflection.hypothesisConfidenceLevel(),
                mem.aciWorkingHypothesis.confidence(),
                mem.aciWorkingHypothesis.evidenceTurnIds(),
                mem.aciWorkingHypothesis.lastLifecycleOp()
        );
        mem.draft2Emotion = cu.emotionalState();

        // 3. Tầng 3 — Business & Commitment State: Tách interested_package vs selected_package
        String committedSelectedPkg = authoritativeBookingState != null
                ? authoritativeBookingState.selectedProductId()
                : null;
        if (committedSelectedPkg != null && !committedSelectedPkg.isBlank()) {
            mem.draft2InterestedPackageId = committedSelectedPkg;
        } else if (reflection.interestedPackageId() != null && !reflection.interestedPackageId().isBlank()) {
            mem.draft2InterestedPackageId = reflection.interestedPackageId();
        }
        if (mem.draft2InterestedPackageId != null && mem.draft2PendingReferences.isEmpty()) {
            mem.draft2PendingReferences.add(mem.draft2InterestedPackageId);
        }

        // 4. ACI V3: Commitment Ledger & Negative Preferences (CUSTOMER_CORRECTION > CUSTOMER_COMMITMENT > BOT_RECOMMENDATION)
        String safeOriginalMsg = originalCustomerMessage != null ? originalCustomerMessage.strip() : "";
        String releasedPackageId = null;
        if (AdaptiveConversationIntelligence.isCustomerCorrectionOrComponentRejectionMessage(safeOriginalMsg)) {
            String corrLow = java.text.Normalizer.normalize(safeOriginalMsg, java.text.Normalizer.Form.NFC)
                    .toLowerCase(Locale.ROOT);
            boolean rejectsHair = corrLow.matches("(?isU).*(?:tóc|gội|chỉ\\s+(?:muốn\\s+|cần\\s+)?(?:massage|mát\\s*xa)|chủ\\s+yếu\\s+(?:là\\s+)?(?:massage|mát\\s*xa)|không\\s+có\\s+phần\\s+đó).*");
            boolean rejectsWholePackage = corrLow.matches("(?isU).*(?:gói\\s+(?:đó|này|kia)|cái\\s+(?:đó|này)|đổi\\s+(?:sang\\s+)?gói\\s+khác).*");
            AdaptiveConversationIntelligence.NegativePreferenceEntry entry;
            if (rejectsHair) {
                entry = new AdaptiveConversationIntelligence.NegativePreferenceEntry(
                        "hair_care",
                        AdaptiveConversationIntelligence.RejectionScope.COMPONENT,
                        safeOriginalMsg,
                        safeTurnId,
                        true);
            } else if (rejectsWholePackage && mem.draft2CommittedSelectedPackageId != null) {
                entry = new AdaptiveConversationIntelligence.NegativePreferenceEntry(
                        mem.draft2CommittedSelectedPackageId,
                        AdaptiveConversationIntelligence.RejectionScope.PACKAGE,
                        safeOriginalMsg,
                        safeTurnId,
                        true);
            } else {
                entry = new AdaptiveConversationIntelligence.NegativePreferenceEntry(
                        "current_focus",
                        AdaptiveConversationIntelligence.RejectionScope.FOCUS,
                        safeOriginalMsg,
                        safeTurnId,
                        true);
            }
            final String entryKey = entry.targetKey();
            mem.draft2NegativePreferences.removeIf(e -> e != null && entryKey.equalsIgnoreCase(e.targetKey()));
            mem.draft2NegativePreferences.add(entry);

            // Giải phóng gói đã cam kết nếu thuộc nhóm bị từ chối
            String committedId = mem.draft2CommittedSelectedPackageId;
            if (committedId != null && !committedId.isBlank()) {
                String committedLabelLow = (mem.structuredBookingState != null
                        && committedId.equals(mem.structuredBookingState.selectedProductId())
                        && mem.structuredBookingState.selectedProductLabel() != null)
                        ? mem.structuredBookingState.selectedProductLabel().toLowerCase(Locale.ROOT)
                        : "";
                String committedIdUpper = committedId.strip().toUpperCase(Locale.ROOT);
                boolean committedHasHair = committedIdUpper.matches("(?:.*[^A-Z0-9])?DS0?[123](?:[^0-9].*)?")
                        || committedLabelLow.contains("tóc")
                        || committedLabelLow.contains("gội");
                if ((rejectsHair && committedHasHair) || (rejectsWholePackage && !rejectsHair)) {
                    releasedPackageId = committedId;
                    mem.draft2CommittedSelectedPackageId = null;
                    mem.draft2CommitmentLevel = AdaptiveConversationIntelligence.CommitmentLevel.NONE;
                    mem.draft2CommitmentEvidence = safeOriginalMsg;
                    if (releasedPackageId.equals(mem.draft2InterestedPackageId)) {
                        mem.draft2InterestedPackageId = null;
                    }
                    mem.draft2PendingReferences.remove(releasedPackageId);
                }
            }
        }

        String authoritativeSelectedId = authoritativeBookingState != null
                ? authoritativeBookingState.selectedProductId()
                : null;
        if (authoritativeSelectedId != null && !authoritativeSelectedId.isBlank()
                && !authoritativeSelectedId.equals(releasedPackageId)) {
            mem.draft2CommittedSelectedPackageId = authoritativeSelectedId;
            boolean hasDateOrTime = (authoritativeBookingState.bookingDate() != null && !authoritativeBookingState.bookingDate().isBlank())
                    || (authoritativeBookingState.bookingTime() != null && !authoritativeBookingState.bookingTime().isBlank());
            if (hasDateOrTime) {
                boolean hasTime = authoritativeBookingState.bookingTime() != null && !authoritativeBookingState.bookingTime().isBlank();
                mem.draft2CommitmentLevel = hasTime
                        ? AdaptiveConversationIntelligence.CommitmentLevel.BOOKING_REQUEST
                        : AdaptiveConversationIntelligence.CommitmentLevel.BOOKING_INTENT;
            } else if (AdaptiveConversationIntelligence.isTrialOrRelativeOptionSelectionMessage(safeOriginalMsg)) {
                mem.draft2CommitmentLevel = AdaptiveConversationIntelligence.CommitmentLevel.TRIAL_COMMITMENT;
            } else {
                mem.draft2CommitmentLevel = AdaptiveConversationIntelligence.CommitmentLevel.PREFERENCE;
            }
            if (!safeOriginalMsg.isEmpty()) {
                mem.draft2CommitmentEvidence = safeOriginalMsg;
            }
        }

        // Ghi nhận danh sách gói vừa được giới thiệu (ứng viên) để phân giải lựa chọn tương đối lượt sau
        if (authoritativeBookingState != null && authoritativeBookingState.candidateProductIds() != null
                && !authoritativeBookingState.candidateProductIds().isEmpty()) {
            mem.draft2LastOfferedPackageIds.clear();
            mem.draft2LastOfferedPackageIds.addAll(authoritativeBookingState.candidateProductIds());
        }
    }

    private static boolean isSideTopicOrOperationalGoalString(String rawGoal) {
        if (rawGoal == null || rawGoal.isBlank()) {
            return true;
        }
        String low = java.text.Normalizer.normalize(rawGoal.strip(), java.text.Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT);
        return low.contains("ask_location")
                || low.contains("location")
                || low.contains("address")
                || low.contains("địa chỉ")
                || low.contains("ở đâu")
                || low.contains("parking")
                || low.contains("đỗ xe")
                || low.contains("opening_hours")
                || low.contains("mấy giờ")
                || low.contains("concise")
                || low.contains("nói ngắn")
                || low.contains("ăn nhẹ")
                || low.contains("ask_inclusion")
                || low.contains("amenity");
    }

    private static int findMatchingPendingCommitmentIndex(
            List<AdaptiveConversationIntelligence.CommitmentRecord> list,
            AdaptiveConversationIntelligence.CommitmentOwner owner,
            String content
    ) {
        if (list == null || list.isEmpty()) {
            return -1;
        }
        String normContent = content != null ? content.strip().toLowerCase(Locale.ROOT) : "";
        for (int i = list.size() - 1; i >= 0; i--) {
            AdaptiveConversationIntelligence.CommitmentRecord r = list.get(i);
            if (r == null || r.commitmentStatus() != AdaptiveConversationIntelligence.CommitmentStatus.pending) {
                continue;
            }
            if (owner != null && r.owner() == owner) {
                if (normContent.isEmpty() || r.content().toLowerCase(Locale.ROOT).equals(normContent)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int findLatestPendingCommitmentIndex(
            List<AdaptiveConversationIntelligence.CommitmentRecord> list,
            AdaptiveConversationIntelligence.CommitmentOwner preferredOwner
    ) {
        if (list == null || list.isEmpty()) {
            return -1;
        }
        if (preferredOwner != null) {
            for (int i = list.size() - 1; i >= 0; i--) {
                AdaptiveConversationIntelligence.CommitmentRecord r = list.get(i);
                if (r != null
                        && r.commitmentStatus() == AdaptiveConversationIntelligence.CommitmentStatus.pending
                        && r.owner() == preferredOwner) {
                    return i;
                }
            }
        }
        for (int i = list.size() - 1; i >= 0; i--) {
            AdaptiveConversationIntelligence.CommitmentRecord r = list.get(i);
            if (r != null && r.commitmentStatus() == AdaptiveConversationIntelligence.CommitmentStatus.pending) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Giải mã câu nói ngắn / lấp lửng ({@code "Biết thế"}, {@code "Cũng được"}, {@code "Để xem đã"}, {@code "Ừ"})
     * dựa trên lượt liền trước + lịch sử hội thoại + cấu trúc {@code ECONOMICAL_OPTION} của Catalog (Checkpoint C3-A.3).
     */
    public ShortUtteranceResolution decodeContextualShortUtterance(
            String customerMessage,
            String precedingBotText,
            String precedingBotSpeechAct,
            List<FanpageBotRequest.ConversationTurn> history,
            int priceObjectionCount,
            boolean inPriceObjectionContext,
            boolean hasPresentedDownsell,
            boolean catalogHasEconomicalOption,
            String bookingStatus
    ) {
        return decodeContextualShortUtteranceStatic(
                customerMessage,
                precedingBotText,
                precedingBotSpeechAct,
                history,
                priceObjectionCount,
                inPriceObjectionContext,
                hasPresentedDownsell,
                catalogHasEconomicalOption,
                bookingStatus
        );
    }

    public static ShortUtteranceResolution decodeContextualShortUtteranceStatic(
            String customerMessage,
            String precedingBotText,
            String precedingBotSpeechAct,
            List<FanpageBotRequest.ConversationTurn> history,
            int priceObjectionCount,
            boolean inPriceObjectionContext,
            boolean hasPresentedDownsell,
            boolean catalogHasEconomicalOption,
            String bookingStatus
    ) {
        if (customerMessage == null || customerMessage.isBlank()) {
            return ShortUtteranceResolution.notApplicable();
        }
        String trimmed = customerMessage.strip();
        boolean isConcessive = CONCESSIVE_SHORT_UTTERANCE_PATTERN.matcher(trimmed).matches();
        boolean isLukewarm = LUKEWARM_SHORT_UTTERANCE_PATTERN.matcher(trimmed).matches();
        boolean isDeferral = DEFERRAL_SHORT_UTTERANCE_PATTERN.matcher(trimmed).matches();
        boolean isBareAffirm = !isConcessive && !isLukewarm && !isDeferral
                && BARE_AFFIRM_SHORT_UTTERANCE_PATTERN.matcher(trimmed).matches();

        if (!isConcessive && !isLukewarm && !isDeferral && !isBareAffirm) {
            return ShortUtteranceResolution.notApplicable();
        }

        String utteranceType = isConcessive
                ? "CONCESSIVE_SKEPTICAL"
                : (isLukewarm ? "LUKEWARM_ACQUIESCENCE" : (isDeferral ? "POLITE_DEFERRAL" : "BARE_AFFIRMATION"));
        String botText = (precedingBotText != null && !precedingBotText.isBlank())
                ? precedingBotText.strip()
                : CleanAgentOrchestratorService.resolveImmediatelyPrecedingBotText(history, null);
        String speechAct = precedingBotSpeechAct != null ? precedingBotSpeechAct.strip() : "";

        boolean precedingWasBookingConfirmed = "CONFIRMED".equalsIgnoreCase(bookingStatus)
                || CleanAgentOrchestratorService.SPEECH_ACT_BOOKING_CONFIRMED.equalsIgnoreCase(speechAct);
        boolean precedingWasOperationalAnswer = "ANSWERED_OPERATIONAL_INFO".equalsIgnoreCase(speechAct)
                || wasImmediatelyPrecedingTurnOperationalQa(botText, history);
        boolean precedingWasRespectedPause = CleanAgentOrchestratorService.SPEECH_ACT_RESPECTED_PAUSE.equalsIgnoreCase(speechAct)
                || CleanAgentOrchestratorService.isPauseOrDeferralBotReply(botText)
                || CleanAgentOrchestratorService.wasPriorCustomerTurnHesitation(history);
        boolean precedingWasConcreteTimeSlotProposal = !precedingWasOperationalAnswer
                && !precedingWasRespectedPause
                && CleanAgentOrchestratorService.isConcreteTimeSlotProposalBotReply(botText, null, null);
        boolean precedingWasExplicitAppointmentProposal = !precedingWasOperationalAnswer
                && !CleanAgentOrchestratorService.isPauseOrDeferralBotReply(botText)
                && (precedingWasConcreteTimeSlotProposal
                    || (botText != null && EXPLICIT_APPOINTMENT_PROPOSAL_BOT_PATTERN.matcher(botText).matches())
                    || ("COLLECTING_VISIT_TIME".equalsIgnoreCase(bookingStatus)
                        || "AWAITING_DATE_OR_TIME".equalsIgnoreCase(bookingStatus)
                        || "AWAITING_PRODUCT_SELECTION".equalsIgnoreCase(bookingStatus)
                        || "AWAITING_CUSTOMER_CONFIRMATION".equalsIgnoreCase(bookingStatus)));
        boolean precedingWasOfferedDetails = CleanAgentOrchestratorService.SPEECH_ACT_OFFERED_DETAILS.equalsIgnoreCase(speechAct)
                || CleanAgentOrchestratorService.isOfferPackageDetailsBotReply(botText);
        boolean precedingWasTrialInvite = CleanAgentOrchestratorService.SPEECH_ACT_INVITED_TRIAL_OR_TIME.equalsIgnoreCase(speechAct)
                || CleanAgentOrchestratorService.isInviteTrialOrAskTimeBotReply(botText);
        boolean hasPriorQuotedPrice = (botText != null && CleanAgentOutputRefereeAndSanitizer.hasQuotedPriceInText(botText))
                || hasAnyPriceInHistory(history)
                || inPriceObjectionContext
                || priceObjectionCount > 0;

        // 0. Nếu khách chủ động xin cân nhắc thêm / suy nghĩ thêm ("Để xem đã", "Ok để cân nhắc thêm")
        if (isDeferral) {
            if (inPriceObjectionContext
                    && !hasPresentedDownsell
                    && !precedingWasRespectedPause
                    && catalogHasEconomicalOption) {
                return new ShortUtteranceResolution(
                        true,
                        utteranceType,
                        "PRICE_CONTEXT_HESITATION_WITH_AVAILABLE_ECONOMICAL_OPTION",
                        "SOFT_SUGGEST_ECONOMICAL_OPTION",
                        "STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL",
                        List.of("HESITATION_OR_DEFERRAL"),
                        0.85,
                        "inPriceObjectionContext=true, downsellPresented=false, economicalOptionAvailable=true, msg=\"" + trimmed + "\""
                );
            }
            return new ShortUtteranceResolution(
                    true,
                    utteranceType,
                    "EXPLICIT_POLITE_DEFERRAL_PAUSE",
                    "RESPECT_CUSTOMER_PAUSE_NO_NEW_PRODUCT",
                    "STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE",
                    List.of("HESITATION_OR_DEFERRAL", "POLITE_CLOSING_ACKNOWLEDGMENT"),
                    0.90,
                    "deferralAfterDownsellOrNoPriceContext(downsellPresented=" + hasPresentedDownsell
                            + ", respectedPause=" + precedingWasRespectedPause + "), msg=\"" + trimmed + "\""
            );
        }

        // 1. Sau khi lịch hẹn đã CONFIRMED
        if (precedingWasBookingConfirmed && (isBareAffirm || isLukewarm)) {
            return new ShortUtteranceResolution(
                    true,
                    utteranceType,
                    "POST_BOOKING_CLOSING_ACK",
                    "WARM_CLOSING_AFTER_CONFIRMED_BOOKING",
                    "STAGE_4B_POST_BOOKING_CONFIRMED_CLOSING",
                    List.of("POST_BOOKING_CLOSING_ACK"),
                    0.92,
                    "bookingStatus=CONFIRMED, msg=\"" + trimmed + "\""
            );
        }

        // 2. NGỮ CẢNH 2: Ngay sau khi bot vừa giải thích thông tin vận hành (giờ mở cửa, chỗ đỗ xe, địa chỉ...)
        if (precedingWasOperationalAnswer) {
            return new ShortUtteranceResolution(
                    true,
                    utteranceType,
                    "ACKNOWLEDGED_OPERATIONAL_INFO",
                    "CONTINUE_NATURAL_CONSULTATION_NO_DOWNSELL",
                    "STAGE_1_DISCOVERY_AND_FIRST_QUOTE",
                    List.of("ACKNOWLEDGED_OPERATIONAL_INFO", "SERVICE_OR_UNIT_DETAIL_QUESTION"),
                    0.86,
                    "precedingTurn=OPERATIONAL_INFO_ANSWER(\"" + abbreviate(botText, 70) + "\"), msg=\"" + trimmed + "\""
            );
        }

        // 3. Sau khi bot vừa tôn trọng tạm dừng (STAGE_3B / STAGE_6) và khách đáp ngắn ("Ừ", "Ok", "Cũng được")
        if (precedingWasRespectedPause && (isBareAffirm || isConcessive)) {
            return new ShortUtteranceResolution(
                    true,
                    utteranceType,
                    "POLITE_CLOSING_ACKNOWLEDGMENT",
                    "RESPECT_CUSTOMER_PAUSE_NO_NEW_PRODUCT",
                    "STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE",
                    List.of("POLITE_CLOSING_ACKNOWLEDGMENT"),
                    0.88,
                    "precedingTurn=RESPECTED_PAUSE, msg=\"" + trimmed + "\""
            );
        }

        // 4. NGỮ CẢNH C / NGỮ CẢNH 3: Ngay sau khi bot đề xuất khung giờ hẹn / sản phẩm đặt lịch cụ thể (khách đáp "Cũng được", "Ừ", "Ok", "uk")
        if (precedingWasExplicitAppointmentProposal && (isLukewarm || isBareAffirm)) {
            List<String> confirmSignals = precedingWasConcreteTimeSlotProposal
                    ? List.of("POSITIVE_CONFIRMATION_SIGNAL", "POSITIVE_INTEREST_READY_FOR_TRIAL_INVITE", "ACCEPTED_PROPOSED_TIME_SLOT")
                    : List.of("POSITIVE_CONFIRMATION_SIGNAL", "POSITIVE_INTEREST_READY_FOR_TRIAL_INVITE");
            return new ShortUtteranceResolution(
                    true,
                    utteranceType,
                    "POSITIVE_CONFIRMATION_SIGNAL",
                    "PROCEED_BOOKING_CONFIRMATION_OR_SLOT_COLLECTION",
                    "STAGE_4_POSITIVE_TRIAL_OR_BOOKING",
                    confirmSignals,
                    0.88,
                    "precedingTurn=" + (precedingWasConcreteTimeSlotProposal ? "CONCRETE_TIME_SLOT_PROPOSAL" : "APPOINTMENT_OR_BOOKING_PROPOSAL")
                            + "(\"" + abbreviate(botText, 70) + "\"), msg=\"" + trimmed + "\""
            );
        }

        // 5. Khi bot vừa hỏi có muốn nghe chi tiết gói không (OFFERED_PACKAGE_DETAILS)
        if (precedingWasOfferedDetails && (isBareAffirm || isLukewarm)) {
            return new ShortUtteranceResolution(
                    true,
                    utteranceType,
                    "ACCEPTED_PACKAGE_DETAIL_OFFER",
                    "PROVIDE_GROUNDED_PACKAGE_DETAILS",
                    "STAGE_1_DISCOVERY_AND_FIRST_QUOTE",
                    List.of("SERVICE_OR_UNIT_DETAIL_QUESTION"),
                    0.84,
                    "precedingTurn=OFFERED_PACKAGE_DETAILS, msg=\"" + trimmed + "\""
            );
        }

        // 5b. NGỮ CẢNH A: Khi bot vừa mời trải nghiệm hoặc gợi ý một sản phẩm cụ thể đã khớp nhu cầu -> khách gật đầu ("uk", "ừ", "ok", "cũng được")
        if (!precedingWasRespectedPause
                && precedingWasTrialInvite
                && (isBareAffirm || (isLukewarm && (!inPriceObjectionContext || hasPresentedDownsell)))) {
            return new ShortUtteranceResolution(
                    true,
                    utteranceType,
                    "POSITIVE_CONFIRMATION_SIGNAL",
                    "PROCEED_BOOKING_CONFIRMATION_OR_SLOT_COLLECTION",
                    "STAGE_4_POSITIVE_TRIAL_OR_BOOKING",
                    List.of("POSITIVE_CONFIRMATION_SIGNAL", "POSITIVE_INTEREST_READY_FOR_TRIAL_INVITE"),
                    0.87,
                    "precedingTurn=INVITED_PRODUCT_EXPERIENCE_OR_TRIAL(\"" + abbreviate(botText, 70) + "\"), msg=\"" + trimmed + "\""
            );
        }

        // 6. NGỮ CẢNH 1: Ngay sau khi bot vừa bảo vệ giá ở STAGE_2 (hoặc vừa báo giá) và khách nói câu lấp lửng ("Biết thế", "Cũng được")
        if (isConcessive || isLukewarm) {
            if (inPriceObjectionContext) {
                if (!hasPresentedDownsell && catalogHasEconomicalOption) {
                    return new ShortUtteranceResolution(
                            true,
                            utteranceType,
                            "PRICE_HESITATION_AFTER_VALUE_DEFENSE",
                            "SOFT_SUGGEST_ECONOMICAL_OPTION",
                            "STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL",
                            List.of("HESITATION_OR_DEFERRAL", "PRICE_HESITATION_AFTER_VALUE_DEFENSE"),
                            0.84,
                            "precedingTurn=STAGE_2_VALUE_DEFENSE, priceObjectionCount=" + priceObjectionCount
                                    + ", economicalOptionAvailable=true, msg=\"" + trimmed + "\""
                    );
                }
                if (isConcessive || !hasPresentedDownsell) {
                    return new ShortUtteranceResolution(
                            true,
                            utteranceType,
                            "PRICE_HESITATION_NO_UNPRESENTED_ECONOMICAL_OPTION",
                            "RESPECT_PACE_OR_BRIEF_CHECKIN",
                            "STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE",
                            List.of("HESITATION_OR_DEFERRAL", "POLITE_CLOSING_ACKNOWLEDGMENT"),
                            0.76,
                            "inPriceObjectionContext=true, economicalOptionAvailable=" + catalogHasEconomicalOption
                                    + ", downsellPresented=" + hasPresentedDownsell + ", msg=\"" + trimmed + "\""
                    );
                }
            }
            if (isConcessive && hasPriorQuotedPrice) {
                return new ShortUtteranceResolution(
                        true,
                        utteranceType,
                        "LUKEWARM_POST_PRICE_QUOTE_EVALUATION",
                        "GENTLE_CHECKIN_OR_SOFT_CLARIFY_VALUE",
                        catalogHasEconomicalOption && !hasPresentedDownsell
                                ? "STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL"
                                : "STAGE_1_DISCOVERY_AND_FIRST_QUOTE",
                        List.of("LUKEWARM_POST_QUOTE_CHECKIN"),
                        0.72,
                        "precedingTurn=INITIAL_PRICE_QUOTE, priceObjectionCount=0, msg=\"" + trimmed + "\""
                );
            }
            if (isLukewarm && hasPriorQuotedPrice && !inPriceObjectionContext) {
                return new ShortUtteranceResolution(
                        true,
                        utteranceType,
                        "LUKEWARM_POST_PRICE_QUOTE_EVALUATION",
                        "GENTLE_CHECKIN_OR_SOFT_CLARIFY_VALUE",
                        "STAGE_1_DISCOVERY_AND_FIRST_QUOTE",
                        List.of("LUKEWARM_POST_QUOTE_CHECKIN"),
                        0.72,
                        "precedingTurn=INITIAL_PRICE_QUOTE, priceObjectionCount=0, msg=\"" + trimmed + "\""
                );
            }
        }

        // 7a. Khách đáp ngắn ("Ừ", "uk") ngay sau câu giải thích bảo vệ giá lần 1 (chưa mời thử & chưa giới thiệu phương án tiết kiệm)
        if (isBareAffirm
                && !precedingWasTrialInvite
                && !precedingWasExplicitAppointmentProposal
                && inPriceObjectionContext
                && !hasPresentedDownsell
                && catalogHasEconomicalOption) {
            return new ShortUtteranceResolution(
                    true,
                    utteranceType,
                    "PRICE_HESITATION_PASSIVE_ACK_AFTER_VALUE_DEFENSE",
                    "SOFT_SUGGEST_ECONOMICAL_OPTION",
                    "STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL",
                    List.of("HESITATION_OR_DEFERRAL", "PRICE_HESITATION_AFTER_VALUE_DEFENSE"),
                    0.78,
                    "precedingTurn=VALUE_DEFENSE_NO_TRIAL_QUESTION, inPriceObjectionContext=true, msg=\"" + trimmed + "\""
            );
        }

        // 7b. NGỮ CẢNH B: Khi bot vừa báo thông tin / giá chung / so sánh phương án (chưa mời thử / chưa đề xuất giờ hẹn) và khách đáp "uk", "ừ", "ok", "cũng được"
        // -> Tiếp nhận thông tin: giữ nhịp cầu trò chuyện ấm áp gần gũi ở STAGE_1 (hỏi nhẹ cảm nhận hoặc điều khách còn băn khoăn), KHÔNG ép chốt lịch thô bạo và KHÔNG ngắt ngang về STAGE_6!
        if ((isBareAffirm || isLukewarm) && (hasPriorQuotedPrice || (botText != null && !botText.isBlank()))) {
            return new ShortUtteranceResolution(
                    true,
                    utteranceType,
                    "ACKNOWLEDGED_GENERAL_INFO_WARM_BRIDGE",
                    "KEEP_WARM_BRIDGE_ASK_FEELING_OR_CONCERN",
                    "STAGE_1_DISCOVERY_AND_FIRST_QUOTE",
                    List.of("LUKEWARM_POST_QUOTE_CHECKIN", "ACKNOWLEDGED_GENERAL_INFO"),
                    0.80,
                    "precedingTurn=GENERAL_INFO_OR_QUOTE_NO_TRIAL_INVITE(\"" + abbreviate(botText, 70) + "\"), msg=\"" + trimmed + "\""
            );
        }

        // 8. Câu quá ngắn khi chưa có ngữ cảnh trước đó -> INSUFFICIENT_EVIDENCE
        if (!hasPriorQuotedPrice && (botText == null || botText.isBlank())) {
            return new ShortUtteranceResolution(
                    true,
                    utteranceType,
                    "INSUFFICIENT_EVIDENCE",
                    "ASK_OPEN_CLARIFICATION_QUESTION",
                    "STAGE_1_DISCOVERY_AND_FIRST_QUOTE",
                    List.of("INSUFFICIENT_EVIDENCE_SHORT_TURN"),
                    0.25,
                    "shortUtteranceWithoutPriorContext, msg=\"" + trimmed + "\""
            );
        }

        return ShortUtteranceResolution.notApplicable();
    }

    public static boolean isOperationalAnswerContext(
            String precedingBotText,
            List<FanpageBotRequest.ConversationTurn> history
    ) {
        return wasImmediatelyPrecedingTurnOperationalQa(precedingBotText, history);
    }

    private static boolean wasImmediatelyPrecedingTurnOperationalQa(
            String precedingBotText,
            List<FanpageBotRequest.ConversationTurn> history
    ) {
        if (precedingBotText != null
                && OPERATIONAL_ANSWER_BOT_PATTERN.matcher(precedingBotText).matches()
                && !EXPLICIT_APPOINTMENT_PROPOSAL_BOT_PATTERN.matcher(precedingBotText).matches()) {
            return true;
        }
        if (history != null && !history.isEmpty()) {
            for (int i = history.size() - 1; i >= 0; i--) {
                FanpageBotRequest.ConversationTurn t = history.get(i);
                if (t == null || t.content() == null || t.content().isBlank()) {
                    continue;
                }
                boolean isBot = "assistant".equalsIgnoreCase(t.role()) || "bot".equalsIgnoreCase(t.role());
                if (!isBot) {
                    return CleanAgentOrchestratorService.isOperationalOrLogisticsQuestion(t.content());
                }
            }
        }
        return false;
    }

    private static boolean hasAnyPriceInHistory(List<FanpageBotRequest.ConversationTurn> history) {
        if (history == null || history.isEmpty()) {
            return false;
        }
        for (FanpageBotRequest.ConversationTurn t : history) {
            if (t != null && t.content() != null && CleanAgentOutputRefereeAndSanitizer.hasQuotedPriceInText(t.content())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Kiểm tra độ trùng lặp từ vựng > 70% giữa hai ghi chú hoặc sự thật.
     */
    public static boolean hasWordOverlapAbove70Percent(String candidate, String existing) {
        if (candidate == null || candidate.isBlank() || existing == null || existing.isBlank()) {
            return false;
        }
        String candNorm = stripSlotPrefix(candidate).toLowerCase(Locale.ROOT);
        String existNorm = stripSlotPrefix(existing).toLowerCase(Locale.ROOT);
        if (candNorm.equals(existNorm)) {
            return true;
        }
        List<String> candTokens = tokenizeWords(candNorm);
        List<String> existTokens = tokenizeWords(existNorm);
        if (candTokens.isEmpty() || existTokens.isEmpty()) {
            return false;
        }
        Set<String> existSet = new LinkedHashSet<>(existTokens);
        int hits = 0;
        for (String t : candTokens) {
            if (existSet.contains(t)) {
                hits++;
            }
        }
        double ratioAgainstCandidate = hits / (double) candTokens.size();
        double ratioAgainstSmaller = hits / (double) Math.min(candTokens.size(), existTokens.size());
        return ratioAgainstCandidate > 0.70d || (Math.min(candTokens.size(), existTokens.size()) >= 4 && ratioAgainstSmaller > 0.75d);
    }

    private static String stripSlotPrefix(String item) {
        if (item == null) {
            return "";
        }
        String s = item.strip();
        int eqIdx = s.indexOf('=');
        int colonIdx = s.indexOf(':');
        if (eqIdx > 0 && eqIdx <= 32 && (colonIdx < 0 || eqIdx < colonIdx)) {
            String key = s.substring(0, eqIdx).strip().toLowerCase(Locale.ROOT);
            if (key.startsWith("slot_")) {
                return s.substring(eqIdx + 1).strip();
            }
        }
        return s;
    }

    private static List<String> tokenizeWords(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String cleaned = Normalizer.normalize(text, Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\d\\s]+", " ")
                .replaceAll("\\s+", " ")
                .strip();
        if (cleaned.isEmpty()) {
            return List.of();
        }
        return List.of(cleaned.split("\\s+"));
    }

    /**
     * Ánh xạ một dữ kiện sang 5 nhóm slot đa ngành chuẩn hóa ({@link FactSlotGroup}) + {@code subAspect}.
     */
    public record ClassifiedFactSlot(FactSlotGroup group, String subAspect, boolean singleValuedByDefault) {
    }

    public static ClassifiedFactSlot classifyFactSlot(String item) {
        if (item == null || item.isBlank()) {
            return new ClassifiedFactSlot(FactSlotGroup.SLOT_LOGISTICS_OR_CARE, "general", false);
        }
        String low = Normalizer.normalize(item.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        String explicitPrefix = "";
        int eqIdx = low.indexOf('=');
        int colonIdx = low.indexOf(':');
        if (eqIdx > 0 && eqIdx <= 32 && (colonIdx < 0 || eqIdx < colonIdx)) {
            explicitPrefix = low.substring(0, eqIdx).strip();
        }

        if (explicitPrefix.equals("slot_target_scope")
                || explicitPrefix.equals("slot_body_area")
                || explicitPrefix.equals("slot_scope")
                || explicitPrefix.equals("slot_room")
                || explicitPrefix.equals("slot_area")) {
            return new ClassifiedFactSlot(FactSlotGroup.SLOT_TARGET_SCOPE, "target_scope", false);
        }
        if (explicitPrefix.equals("slot_preference_spec")
                || explicitPrefix.equals("slot_spec")
                || explicitPrefix.equals("slot_size")
                || explicitPrefix.equals("slot_intensity")) {
            return new ClassifiedFactSlot(FactSlotGroup.SLOT_PREFERENCE_SPEC, "preference_spec", false);
        }
        if (explicitPrefix.equals("slot_budget_sensitivity")
                || explicitPrefix.equals("slot_budget")
                || explicitPrefix.equals("slot_price_sensitivity")) {
            return new ClassifiedFactSlot(FactSlotGroup.SLOT_BUDGET_SENSITIVITY, "budget", true);
        }
        if (explicitPrefix.equals("slot_product_focus")
                || explicitPrefix.equals("slot_package")
                || explicitPrefix.equals("slot_product")
                || explicitPrefix.equals("slot_selected_product")) {
            return new ClassifiedFactSlot(FactSlotGroup.SLOT_PRODUCT_FOCUS, "selected_product", true);
        }
        if (explicitPrefix.equals("slot_visit_time") || explicitPrefix.equals("slot_booking_time")) {
            return new ClassifiedFactSlot(FactSlotGroup.SLOT_LOGISTICS_OR_CARE, "visit_time", true);
        }
        if (explicitPrefix.equals("slot_logistics_or_care")) {
            boolean isVisitTime = low.contains("khung giờ") || low.contains("giờ ghé") || low.contains("lịch hẹn")
                    || low.contains("tối nay") || low.contains("chiều mai") || low.contains("sáng mai") || low.contains("chiều nay");
            return new ClassifiedFactSlot(FactSlotGroup.SLOT_LOGISTICS_OR_CARE, isVisitTime ? "visit_time" : "care_or_logistics", isVisitTime);
        }

        if (low.contains("khung giờ") || low.contains("giờ ghé") || low.contains("lịch hẹn")
                || low.contains("đổi lịch") || low.contains("đổi sang khung giờ")
                || low.matches(".*\\b(?:sáng|trưa|chiều|tối)\\s+(?:nay|mai|mốt)\\b.*")) {
            return new ClassifiedFactSlot(FactSlotGroup.SLOT_LOGISTICS_OR_CARE, "visit_time", true);
        }
        if (low.contains("ngân sách") || low.contains("tầm giá") || low.contains("chê đắt") || low.contains("so sánh giá")) {
            return new ClassifiedFactSlot(FactSlotGroup.SLOT_BUDGET_SENSITIVITY, "budget", true);
        }
        if (low.matches(".*\\b(?:chốt\\s+gói|chọn\\s+gói|đổi\\s+sang\\s+gói|đổi\\s+từ\\s+gói|gói\\s+\\d{2,4}\\s*k|mẫu\\s+[a-z0-9_-]+|khóa\\s+ielts|ds0\\d|ns0\\d)\\b.*")) {
            return new ClassifiedFactSlot(FactSlotGroup.SLOT_PRODUCT_FOCUS, "selected_product", true);
        }
        if (low.matches(".*\\b(\\d{2,4}\\s*[x×]\\s*\\d{2,4}\\s*(?:cm|mm)|lực\\s+(?:nhẹ|vừa|mạnh)|\\d{1,3}\\s*(?:buổi|m2|m²))\\b.*")) {
            return new ClassifiedFactSlot(FactSlotGroup.SLOT_PREFERENCE_SPEC, "preference_spec", false);
        }
        if (!ScopeMatcher.extractScopesFromText(low, List.of()).isEmpty()
                || low.contains("vùng ") || low.contains("phòng ")) {
            return new ClassifiedFactSlot(FactSlotGroup.SLOT_TARGET_SCOPE, "target_scope", false);
        }
        return new ClassifiedFactSlot(FactSlotGroup.SLOT_LOGISTICS_OR_CARE, "general", false);
    }

    /**
     * Thực thi trực tiếp 1 thao tác bộ nhớ có phân loại ({@code ADD}, {@code UPDATE}, {@code SUPERSEDE}, {@code RETRACT}, {@code UNCERTAIN})
     * từ lời nhắn khách hàng và dữ kiện quan sát được, lưu vết kiểm toán đầy đủ vào {@link CustomerBrainMemory}.
     */
    public FactMutationRecord applyStructuredFactObservation(
            UUID pageId,
            String senderId,
            String rawFactOrUtterance,
            String customerUtteranceContext
    ) {
        CustomerBrainMemory mem = getOrHydrateMemoryForMutation(pageId, senderId);
        FactMutationRecord rec;
        synchronized (mem) {
            rec = applyFactMemoryMutationLocked(mem, rawFactOrUtterance, customerUtteranceContext, MAX_VERIFIED_FACTS_PER_CUSTOMER);
        }
        if (rec != null) {
            persistMemoryToDbIfEnabled(pageId, senderId, null, mem);
        }
        return rec;
    }

    private static FactMutationRecord applyFactMemoryMutationLocked(
            CustomerBrainMemory mem,
            String rawCandidate,
            String customerUtterance,
            int maxCapacity
    ) {
        String utterance = customerUtterance != null ? customerUtterance.strip() : "";
        String candidate = rawCandidate != null ? rawCandidate.strip() : "";
        String basisText = !candidate.isEmpty() ? candidate : utterance;
        if (basisText.isEmpty()) {
            return null;
        }

        // 1. Kiểm tra SUPERSEDE tường minh trước ("đổi sang", "chuyển sang", "đổi từ phương án A sang phương án B", "đổi lịch từ tối nay sang chiều mai")
        boolean hasExplicitSupersedeMarker = EXPLICIT_SUPERSEDE_MARKER_PATTERN.matcher(basisText).matches()
                || (!utterance.isEmpty() && EXPLICIT_SUPERSEDE_MARKER_PATTERN.matcher(utterance).matches());

        // 2. Kiểm tra RETRACT (Rút lại thông tin cũ — ví dụ: "À thôi mình không làm vùng lưng nữa", "Bỏ phòng bếp nhé")
        if (!hasExplicitSupersedeMarker) {
            String retractSource = (!utterance.isEmpty() && EXPLICIT_RETRACT_CLAUSE_PATTERN.matcher(utterance).find())
                    ? utterance
                    : (EXPLICIT_RETRACT_CLAUSE_PATTERN.matcher(basisText).find() ? basisText : "");
            if (!retractSource.isEmpty()) {
                Matcher rm = EXPLICIT_RETRACT_CLAUSE_PATTERN.matcher(retractSource);
                if (rm.find()) {
                    String retractedTarget = rm.group(1) != null ? rm.group(1).strip().toLowerCase(Locale.ROOT) : "";
                    List<String> targetScopes = ScopeMatcher.extractScopesFromText(retractedTarget, List.of());
                    String removedFact = null;
                    for (int i = mem.verifiedFacts.size() - 1; i >= 0; i--) {
                        String existing = mem.verifiedFacts.get(i);
                        String existLow = existing.toLowerCase(Locale.ROOT);
                        boolean matchesTarget = (!retractedTarget.isEmpty() && existLow.contains(retractedTarget))
                                || targetScopes.stream().anyMatch(sc -> existLow.contains(sc)
                                    || ("lưng".equals(sc) && existLow.contains("thắt lưng")));
                        if (matchesTarget) {
                            removedFact = mem.verifiedFacts.remove(i);
                            mem.supersededOrRetractedFacts.add("RETRACTED:" + removedFact);
                        }
                    }
                    FactMutationRecord rec = new FactMutationRecord(
                            FactMemoryOperation.RETRACT,
                            FactSlotGroup.SLOT_TARGET_SCOPE.code(),
                            "retracted_scope",
                            retractSource,
                            removedFact != null ? removedFact : retractedTarget,
                            "CUSTOMER_EXPLICIT_NEGATION_RETRACT",
                            Instant.now()
                    );
                    mem.factMutations.add(rec);
                    return rec;
                }
            }
        }

        // 3. Kiểm tra UNCERTAIN (Khách mới hỏi thăm dò chưa xác nhận -> KHÔNG lưu thành verifiedFact)
        String checkExploratoryText = !utterance.isEmpty() ? utterance : candidate;
        boolean hasFirstPersonCommitment = FIRST_PERSON_COMMITMENT_FACT_PATTERN.matcher(checkExploratoryText).matches();
        boolean isPureExploratoryInquiry = EXPLORATORY_UNCERTAIN_QUESTION_PATTERN.matcher(checkExploratoryText).matches()
                && !hasFirstPersonCommitment;
        boolean looksExploratoryQuestion = isPureExploratoryInquiry
                || (checkExploratoryText.contains("?")
                    && !hasFirstPersonCommitment
                    && (candidate.isEmpty() || candidate.equals(utterance) || candidate.toLowerCase(Locale.ROOT).contains("hỏi thăm dò") || candidate.contains("?")));
        if (looksExploratoryQuestion) {
            if (!mem.uncertainExplorations.contains(basisText)) {
                mem.uncertainExplorations.add(basisText);
            }
            FactMutationRecord rec = new FactMutationRecord(
                    FactMemoryOperation.UNCERTAIN,
                    classifyFactSlot(basisText).group().code(),
                    "exploratory_inquiry",
                    basisText,
                    null,
                    "EXPLORATORY_UNCONFIRMED_QUESTION_NOT_SAVED_AS_VERIFIED",
                    Instant.now()
            );
            mem.factMutations.add(rec);
            return rec;
        }

        if (candidate.isEmpty()) {
            return null;
        }

        ClassifiedFactSlot candSlot = classifyFactSlot(candidate);
        String candBody = stripSlotPrefix(candidate);

        // 4. Phân biệt SUPERSEDE vs UPDATE vs ADD trong cùng nhóm slot
        for (int i = mem.verifiedFacts.size() - 1; i >= 0; i--) {
            String existing = mem.verifiedFacts.get(i);
            ClassifiedFactSlot existSlot = classifyFactSlot(existing);
            String existBody = stripSlotPrefix(existing);

            // Chống trùng lặp nguyên văn (>70% từ vựng mà không phải đính chính)
            if (hasWordOverlapAbove70Percent(candBody, existBody) && !hasExplicitSupersedeMarker) {
                // Nếu thông tin mới chi tiết hơn (dài hơn và chứa toàn bộ thông tin cũ, ví dụ "tối nay" -> "tối nay lúc 19h") -> UPDATE tại chỗ
                if (candBody.length() > existBody.length() + 3
                        && candBody.toLowerCase(Locale.ROOT).contains(existBody.toLowerCase(Locale.ROOT))) {
                    mem.verifiedFacts.set(i, candidate);
                    FactMutationRecord rec = new FactMutationRecord(
                            FactMemoryOperation.UPDATE,
                            candSlot.group().code(),
                            candSlot.subAspect(),
                            candidate,
                            existing,
                            "REFINED_EXISTING_FACT_IN_PLACE",
                            Instant.now()
                    );
                    mem.factMutations.add(rec);
                    return rec;
                }
                return null;
            }

            boolean sameSlotAndSubAspect = candSlot.group() == existSlot.group()
                    && candSlot.subAspect().equals(existSlot.subAspect());
            if (!sameSlotAndSubAspect) {
                continue;
            }

            // Kiểm tra UPDATE tinh chỉnh bổ sung chi tiết vào cùng 1 thực thể (ví dụ "tối nay" -> "tối nay 19h", "phòng khách" -> "phòng khách 40m2")
            String candLow = candBody.toLowerCase(Locale.ROOT);
            String existLow = existBody.toLowerCase(Locale.ROOT);
            if (!hasExplicitSupersedeMarker && candLow.contains(existLow) && candLow.length() > existLow.length() + 2) {
                mem.verifiedFacts.set(i, candidate);
                FactMutationRecord rec = new FactMutationRecord(
                        FactMemoryOperation.UPDATE,
                        candSlot.group().code(),
                        candSlot.subAspect(),
                        candidate,
                        existing,
                        "DETAIL_ENRICHMENT_UPDATE",
                        Instant.now()
                );
                mem.factMutations.add(rec);
                return rec;
            }

            // Chỉ SUPERSEDE khi thực sự mâu thuẫn (slot đơn trị như selected_product, visit_time, budget) HOẶC khách nói rõ "đổi sang / thay vì"
            boolean genuinelyConflicting = hasExplicitSupersedeMarker || candSlot.singleValuedByDefault();
            if (genuinelyConflicting) {
                String superseded = mem.verifiedFacts.remove(i);
                mem.supersededOrRetractedFacts.add("SUPERSEDED:" + superseded + " -> " + candidate);
                mem.verifiedFacts.add(candidate);
                while (mem.verifiedFacts.size() > maxCapacity) {
                    mem.verifiedFacts.remove(0);
                }
                FactMutationRecord rec = new FactMutationRecord(
                        FactMemoryOperation.SUPERSEDE,
                        candSlot.group().code(),
                        candSlot.subAspect(),
                        candidate,
                        superseded,
                        hasExplicitSupersedeMarker ? "EXPLICIT_CHOICE_CHANGE_SUPERSEDE" : "CONFLICTING_SINGLE_VALUED_SLOT_SUPERSEDE",
                        Instant.now()
                );
                mem.factMutations.add(rec);
                return rec;
            }
            // Ngược lại (ví dụ slot_target_scope: đang ốp "phòng khách", nói thêm "phòng bếp"; hoặc bổ sung thêm phạm vi/khu vực mới) -> KHÔNG xóa mục cũ, đi tiếp sang ADD!
        }

        // Nếu không cùng slot prefix nhưng là câu đính chính trực tiếp có giao thoa >= 2 từ khóa nội dung -> SUPERSEDE mục liên quan
        if (hasExplicitSupersedeMarker && !mem.verifiedFacts.isEmpty()) {
            List<String> candTokens = tokenizeWords(candBody);
            for (int i = mem.verifiedFacts.size() - 1; i >= 0; i--) {
                String existing = mem.verifiedFacts.get(i);
                List<String> existTokens = tokenizeWords(stripSlotPrefix(existing));
                long shared = candTokens.stream().filter(t -> t.length() >= 3 && existTokens.contains(t)).count();
                if (shared >= 2) {
                    String superseded = mem.verifiedFacts.remove(i);
                    mem.supersededOrRetractedFacts.add("SUPERSEDED:" + superseded + " -> " + candidate);
                    mem.verifiedFacts.add(candidate);
                    while (mem.verifiedFacts.size() > maxCapacity) {
                        mem.verifiedFacts.remove(0);
                    }
                    FactMutationRecord rec = new FactMutationRecord(
                            FactMemoryOperation.SUPERSEDE,
                            candSlot.group().code(),
                            candSlot.subAspect(),
                            candidate,
                            superseded,
                            "TOPIC_OVERLAP_EXPLICIT_CORRECTION_SUPERSEDE",
                            Instant.now()
                    );
                    mem.factMutations.add(rec);
                    return rec;
                }
            }
        }

        // 5. ADD: Mở rộng nhu cầu không mâu thuẫn với nhu cầu cũ -> giữ cả hai trong verifiedFacts
        mem.verifiedFacts.add(candidate);
        while (mem.verifiedFacts.size() > maxCapacity) {
            mem.verifiedFacts.remove(0);
        }
        FactMutationRecord rec = new FactMutationRecord(
                FactMemoryOperation.ADD,
                candSlot.group().code(),
                candSlot.subAspect(),
                candidate,
                null,
                "NON_CONFLICTING_FACT_ADDED",
                Instant.now()
        );
        mem.factMutations.add(rec);
        return rec;
    }

    private static void addBoundedCareNote(
            List<String> careNotesList,
            String rawItem,
            int maxCapacity
    ) {
        if (rawItem == null || rawItem.isBlank()) {
            return;
        }
        String candidate = rawItem.strip();
        if (candidate.isEmpty()) {
            return;
        }
        boolean isCorrection = EXPLICIT_SUPERSEDE_MARKER_PATTERN.matcher(candidate).matches();
        if (isCorrection && !careNotesList.isEmpty()) {
            List<String> candTokens = tokenizeWords(candidate);
            for (int i = careNotesList.size() - 1; i >= 0; i--) {
                List<String> existTokens = tokenizeWords(careNotesList.get(i));
                long shared = candTokens.stream().filter(t -> t.length() >= 3 && existTokens.contains(t)).count();
                if (shared >= 2) {
                    careNotesList.remove(i);
                    break;
                }
            }
        }
        for (String existing : careNotesList) {
            if (hasWordOverlapAbove70Percent(candidate, existing)) {
                return;
            }
        }
        careNotesList.add(candidate);
        while (careNotesList.size() > maxCapacity) {
            careNotesList.remove(0);
        }
    }

    /**
     * Đánh giá và cập nhật {@code Living Hypothesis} dựa trên bằng chứng thực tế từ phía khách hàng (Checkpoint C3-A.1).
     * Cấm tuyệt đối lấy câu quảng cáo do chính bot viết ra làm bằng chứng rằng khách có nhu cầu hay cảm xúc đó.
     */
    public static LivingHypothesisState evaluateLivingHypothesis(
            LivingHypothesisState priorState,
            String priorHypothesisText,
            String userMessage,
            String assistantReply,
            String candidateHypothesis,
            List<String> verifiedFacts,
            boolean factChangedThisTurn
    ) {
        LivingHypothesisState safePrior = priorState != null ? priorState : LivingHypothesisState.initialUnknown();
        String existingHyp = (priorHypothesisText != null && !priorHypothesisText.isBlank())
                ? priorHypothesisText.strip()
                : safePrior.hypothesisText();
        String userTrim = userMessage != null ? userMessage.strip() : "";
        String candTrim = candidateHypothesis != null ? candidateHypothesis.strip() : "";
        double priorConf = safePrior.confidence() > 0.0 ? safePrior.confidence() : (existingHyp.isEmpty() ? 0.0 : 0.72);

        // Quy tắc 1: Khách bác bỏ giả thuyết cũ / hỏi vặn suy diễn của bot
        if (!userTrim.isEmpty() && CleanAgentOrchestratorService.isChallengeOrQuestionBotAssumption(userTrim)) {
            return new LivingHypothesisState(
                    "",
                    HypothesisStatus.REJECTED_BY_CUSTOMER,
                    Math.min(0.20, priorConf * 0.3),
                    userTrim,
                    "CUSTOMER_CHALLENGED_OR_REJECTED_PRIOR_ASSUMPTION",
                    Instant.now()
            );
        }

        // Quy tắc 2: Khách bẻ lái sang câu hỏi vận hành ("Tối mấy giờ đóng cửa?", "Có chỗ đỗ xe không?") -> hạ confidence & đổi trạng thái ngay
        if (!userTrim.isEmpty() && CleanAgentOrchestratorService.isOperationalOrLogisticsQuestion(userTrim)) {
            double downgradedConf = Math.max(0.30, Math.round((priorConf > 0 ? priorConf - 0.30 : 0.35) * 100.0) / 100.0);
            String pivotedHyp = "Khách tạm chuyển hướng hỏi thông tin vận hành (" + userTrim + "), chưa chốt quyết định gói";
            return new LivingHypothesisState(
                    pivotedHyp,
                    HypothesisStatus.DOWNGRADED_OPERATIONAL_PIVOT,
                    downgradedConf,
                    userTrim,
                    "OPERATIONAL_QUESTION_PIVOT_DOWNGRADED_PRIOR_HYPOTHESIS",
                    Instant.now()
            );
        }

        // Quy tắc 3: Chặn tuyệt đối việc lấy câu quảng cáo của chính bot làm bằng chứng tâm lý khách
        if (!candTrim.isEmpty() && isDerivedOnlyFromBotPromotionalFluff(candTrim, userTrim, assistantReply, verifiedFacts)) {
            if (!existingHyp.isEmpty()) {
                return new LivingHypothesisState(
                        existingHyp,
                        HypothesisStatus.REJECTED_BOT_PROMOTIONAL_FLUFF,
                        priorConf,
                        safePrior.customerEvidenceQuote(),
                        "BLOCKED_BOT_PROMOTIONAL_FLUFF_KEPT_PRIOR_HYPOTHESIS",
                        Instant.now()
                );
            }
            return new LivingHypothesisState(
                    "",
                    HypothesisStatus.REJECTED_BOT_PROMOTIONAL_FLUFF,
                    0.20,
                    userTrim,
                    "BLOCKED_BOT_PROMOTIONAL_FLUFF_NO_CUSTOMER_EVIDENCE",
                    Instant.now()
            );
        }

        // Quy tắc 4: Câu nói quá ngắn và chưa đủ căn cứ suy diễn tâm lý -> UNKNOWN / INSUFFICIENT_EVIDENCE
        List<String> userWords = tokenizeWords(userTrim);
        boolean ultraShortMessage = userWords.size() <= 2
                || BARE_AFFIRM_SHORT_UTTERANCE_PATTERN.matcher(userTrim).matches()
                || LUKEWARM_SHORT_UTTERANCE_PATTERN.matcher(userTrim).matches();
        boolean hasPriorCustomerContext = !existingHyp.isEmpty() || (verifiedFacts != null && !verifiedFacts.isEmpty()) || factChangedThisTurn;
        if (ultraShortMessage && !hasPriorCustomerContext) {
            return new LivingHypothesisState(
                    "",
                    HypothesisStatus.INSUFFICIENT_EVIDENCE,
                    0.25,
                    userTrim,
                    "SHORT_UTTERANCE_WITHOUT_PRIOR_CUSTOMER_EVIDENCE",
                    Instant.now()
            );
        }

        // Quy tắc 5: Nếu lời nhắn ngắn/đồng thuận nhất quán, không có bằng chứng làm thay đổi nhận định -> giữ nguyên hypothesis cũ & làm mới thời điểm
        if (candTrim.isEmpty()
                || (!existingHyp.isEmpty()
                    && (candTrim.equalsIgnoreCase(existingHyp)
                        || (!factChangedThisTurn && hasWordOverlapAbove70Percent(candTrim, existingHyp))))) {
            if (!existingHyp.isEmpty()) {
                String refreshedEvidence = !userTrim.isEmpty() ? userTrim : safePrior.customerEvidenceQuote();
                return new LivingHypothesisState(
                        existingHyp,
                        HypothesisStatus.MAINTAINED_CONSISTENT,
                        Math.min(0.92, Math.max(0.65, priorConf)),
                        refreshedEvidence,
                        "MAINTAINED_CONSISTENT_HYPOTHESIS_REFRESHED_TIMESTAMP",
                        Instant.now()
                );
            }
            return new LivingHypothesisState(
                    "",
                    HypothesisStatus.INSUFFICIENT_EVIDENCE,
                    0.25,
                    userTrim,
                    "NO_HYPOTHESIS_EVIDENCE_YET",
                    Instant.now()
            );
        }

        // Nếu câu khách chỉ là lời xác nhận cụt ("Ừ", "Ok") không mang thông tin mới mà đã có existingHyp hợp lệ -> giữ nguyên existingHyp trừ khi factChangedThisTurn
        if (BARE_AFFIRM_SHORT_UTTERANCE_PATTERN.matcher(userTrim).matches() && !existingHyp.isEmpty() && !factChangedThisTurn) {
            return new LivingHypothesisState(
                    existingHyp,
                    HypothesisStatus.MAINTAINED_CONSISTENT,
                    Math.min(0.90, Math.max(0.65, priorConf)),
                    userTrim,
                    "BARE_ACK_KEPT_CONSISTENT_PRIOR_HYPOTHESIS",
                    Instant.now()
            );
        }

        // Quy tắc 6: Khách cung cấp bằng chứng mới làm thay đổi/cập nhật nhận định
        double newConf = factChangedThisTurn ? 0.85 : 0.78;
        return new LivingHypothesisState(
                candTrim,
                HypothesisStatus.ACTIVE_NEW_EVIDENCE,
                newConf,
                userTrim,
                "UPDATED_FROM_NEW_CUSTOMER_EVIDENCE",
                Instant.now()
        );
    }

    /**
     * Phát hiện giả thuyết được suy diễn mù quáng từ chính văn mẫu quảng cáo của bot mà khách chưa từng nói tới.
     */
    public static boolean isDerivedOnlyFromBotPromotionalFluff(
            String candidateHypothesis,
            String userMessage,
            String assistantReply,
            List<String> verifiedFacts
    ) {
        if (candidateHypothesis == null || candidateHypothesis.isBlank()
                || assistantReply == null || assistantReply.isBlank()) {
            return false;
        }
        String candNorm = Normalizer.normalize(candidateHypothesis, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        String userNorm = userMessage != null ? Normalizer.normalize(userMessage, Normalizer.Form.NFC).toLowerCase(Locale.ROOT) : "";
        String botNorm = Normalizer.normalize(assistantReply, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        String factsNorm = verifiedFacts != null
                ? Normalizer.normalize(String.join(" ", verifiedFacts), Normalizer.Form.NFC).toLowerCase(Locale.ROOT)
                : "";
        String customerCorpus = (userNorm + " " + factsNorm).strip();

        // 1. Nếu hypothesis chứa cụm từ quảng cáo của bot mà khách chưa từng nhắc tới -> chặn ngay
        Matcher fluffMatcher = BOT_PROMOTIONAL_FLUFF_MARKERS.matcher(candNorm);
        while (fluffMatcher.find()) {
            String phrase = fluffMatcher.group(1);
            if (phrase != null && botNorm.contains(phrase) && !customerCorpus.contains(phrase)) {
                return true;
            }
        }

        // 2. Đối chiếu tập từ khóa nội dung đặc thù: nếu khớp nhiều từ trong câu trả lời quảng cáo của bot nhưng 0 từ trong lời khách + verifiedFacts
        List<String> candContentTokens = tokenizeWords(candNorm).stream()
                .filter(t -> t.length() >= 3 && !HYPOTHESIS_META_STOPWORDS.contains(t) && !t.matches("\\d+"))
                .toList();
        if (candContentTokens.size() < 2) {
            return false;
        }
        Set<String> botTokens = new LinkedHashSet<>(tokenizeWords(botNorm));
        Set<String> customerTokens = new LinkedHashSet<>(tokenizeWords(customerCorpus));

        int hitsInBotOnly = 0;
        int hitsInCustomer = 0;
        for (String tok : candContentTokens) {
            if (customerTokens.contains(tok)) {
                hitsInCustomer++;
            } else if (botTokens.contains(tok)) {
                hitsInBotOnly++;
            }
        }
        return hitsInCustomer == 0 && hitsInBotOnly >= 2;
    }

    private static BrainReasoningProvenance computeRecallProvenance(
            CustomerBrainMemory mem,
            String message,
            double retrievalQuality,
            double groundedness,
            boolean isFirstTurn,
            RecallAndAdviseResponse remoteRes
    ) {
        List<String> localFacts;
        LivingHypothesisState hypState;
        int priceObjCount;
        boolean inPriceCtx;
        boolean presentedDownsell;
        String lastSpeechAct;
        synchronized (mem) {
            localFacts = List.copyOf(mem.verifiedFacts);
            hypState = mem.livingHypothesisState;
            priceObjCount = mem.priceObjectionCount;
            inPriceCtx = mem.inPriceObjectionContext;
            presentedDownsell = mem.hasPresentedDownsell;
            lastSpeechAct = mem.lastActualSentSpeechAct;
        }

        ShortUtteranceResolution shortRes = decodeContextualShortUtteranceStatic(
                message,
                null,
                lastSpeechAct,
                List.of(),
                priceObjCount,
                inPriceCtx,
                presentedDownsell,
                true,
                "NONE"
        );

        String stage;
        String stageSource;
        if (remoteRes != null && remoteRes.purchaseStage() != null && !remoteRes.purchaseStage().isBlank()) {
            stage = remoteRes.purchaseStage();
            stageSource = "EXPLICIT_BRAIN_ADVICE_OVERRIDE";
        } else if (shortRes.isContextualShortUtterance() && !shortRes.recommendedStage().isEmpty()) {
            stage = shortRes.recommendedStage();
            stageSource = "IN_JVM_SHORT_UTTERANCE_DECODER(" + shortRes.decodedMeaning() + ")";
        } else if (CleanAgentOrchestratorService.isOperationalOrLogisticsQuestion(message)) {
            stage = "STAGE_1_OPERATIONAL_INFO_QA";
            stageSource = "IN_JVM_OPERATIONAL_PIVOT_DETECTOR";
        } else if (CleanAgentOrchestratorService.isConfirmedCustomerPriceObjection(message, inPriceCtx || priceObjCount > 0)) {
            stage = (priceObjCount == 0) ? "STAGE_2_FIRST_PRICE_OBJECTION_HOLD_VALUE" : "STAGE_3_DOWNSELL_OR_REPEAT_OBJECTION";
            stageSource = "IN_JVM_PRICE_OBJECTION_COUNTER(prior=" + priceObjCount + ")";
        } else if (CleanAgentOrchestratorService.hasProactiveBookingOrPackageSelectionInMessage(message)) {
            stage = "STAGE_4_POSITIVE_TRIAL_OR_BOOKING";
            stageSource = "IN_JVM_PROACTIVE_BOOKING_SIGNAL";
        } else {
            stage = isFirstTurn ? "STAGE_1_INITIAL_DISCOVERY" : "STAGE_1_DISCOVERY_AND_FIRST_QUOTE";
            stageSource = "IN_JVM_CONSULTATIVE_DEFAULT";
        }

        List<String> words = tokenizeWords(message);
        boolean insufficientContext = words.size() <= 2 && localFacts.isEmpty() && hypState.hypothesisText().isEmpty();
        String archetype;
        String archetypeSource;
        if (remoteRes != null && remoteRes.buyerArchetype() != null && !remoteRes.buyerArchetype().isBlank()) {
            archetype = remoteRes.buyerArchetype();
            archetypeSource = "EXPLICIT_BRAIN_ADVICE_OVERRIDE";
        } else if (insufficientContext) {
            archetype = "UNKNOWN";
            archetypeSource = "SHORT_UTTERANCE_INSUFFICIENT_EVIDENCE";
        } else if (inPriceCtx || CleanAgentOrchestratorService.isConfirmedCustomerPriceObjection(message, true)) {
            archetype = "PRICE_SENSITIVE_EVALUATOR";
            archetypeSource = "IN_JVM_PRICE_OBJECTION_EVIDENCE";
        } else if (CleanAgentOrchestratorService.isOperationalOrLogisticsQuestion(message)) {
            archetype = "PRAGMATIC_LOGISTICS_CHECKER";
            archetypeSource = "IN_JVM_OPERATIONAL_QUERY_EVIDENCE";
        } else if (CleanAgentOrchestratorService.hasProactiveBookingOrPackageSelectionInMessage(message)) {
            archetype = "DECISIVE_ACTION_TAKER";
            archetypeSource = "IN_JVM_BOOKING_INTENT_EVIDENCE";
        } else {
            archetype = "CONSULTATIVE_SEEKER";
            archetypeSource = "IN_JVM_NEED_SHARING_EVIDENCE";
        }

        double baseConf = insufficientContext ? 0.25 : 0.45;
        double factBonus = Math.min(0.20, localFacts.size() * 0.05);
        double retrievalBonus = Math.round(retrievalQuality * 0.20 * 100.0) / 100.0;
        double groundedBonus = Math.round(groundedness * 0.10 * 100.0) / 100.0;
        double opPenalty = CleanAgentOrchestratorService.isOperationalOrLogisticsQuestion(message) && inPriceCtx ? -0.15 : 0.0;
        double computedConf = remoteRes != null && remoteRes.finalConfidence() > 0.0
                ? remoteRes.finalConfidence()
                : Math.max(0.20, Math.min(0.95, baseConf + factBonus + retrievalBonus + groundedBonus + opPenalty));
        String confFormula = remoteRes != null && remoteRes.finalConfidence() > 0.0
                ? ("explicit_final_confidence=" + String.format(Locale.ROOT, "%.2f", remoteRes.finalConfidence()))
                : String.format(Locale.ROOT, "base=%.2f + facts=%.2f + retrieval=%.2f + grounded=%.2f + pivotAdj=%.2f => %.2f",
                        baseConf, factBonus, retrievalBonus, groundedBonus, opPenalty, computedConf);

        String skillCode = (remoteRes != null && remoteRes.activeSkill() != null && remoteRes.activeSkill().skillCode() != null)
                ? remoteRes.activeSkill().skillCode()
                : (stage.contains("STAGE_2") ? "price_objection_soft_anchor"
                    : (stage.contains("STAGE_3B") || stage.contains("STAGE_6") ? "spa_graceful_exit_after_rejection"
                        : (stage.contains("STAGE_4") ? "booking_soft_confirm" : "consultative_discovery")));
        String skillSource = (remoteRes != null && remoteRes.activeSkill() != null)
                ? "EXPLICIT_BRAIN_ADVICE_ACTIVE_SKILL"
                : "IN_JVM_STAGE_GOVERNED_MAP(" + stage + ")";

        HypothesisStatus effHypStatus = insufficientContext ? HypothesisStatus.INSUFFICIENT_EVIDENCE : hypState.status();
        String effHyp = hypState.hypothesisText();
        String proposedAction = shortRes.isContextualShortUtterance()
                ? shortRes.proposedAction()
                : (CleanAgentOrchestratorService.isOperationalOrLogisticsQuestion(message)
                    ? "ANSWER_OPERATIONAL_FACT_DIRECTLY_NO_DOWNSELL"
                    : "EXECUTE_STAGE_CONTRACT(" + stage + ")");

        return new BrainReasoningProvenance(
                stage,
                stageSource,
                archetype,
                archetypeSource,
                computedConf,
                confFormula,
                skillCode,
                skillSource,
                effHyp,
                effHypStatus,
                hypState.provenanceReason(),
                !hypState.customerEvidenceQuote().isEmpty() ? hypState.customerEvidenceQuote() : message.strip(),
                proposedAction,
                Instant.now()
        );
    }

    public void retainTurnAsync(
            UUID pageId,
            String senderId,
            String customerName,
            String userMessage,
            String assistantReply) {
        retainTurnAsync(pageId, senderId, customerName, userMessage, assistantReply, List.of());
    }

    public void retainTurnAsync(
            UUID pageId,
            String senderId,
            String customerName,
            String userMessage,
            String assistantReply,
            List<ConversationState.CustomerCareNoteArtifact> customerCareNotes) {
        retainTurnWithReflection(
                pageId,
                senderId,
                customerName,
                userMessage,
                assistantReply,
                customerCareNotes,
                "",
                "",
                "",
                List.of()
        );
    }

    public void retainTurnWithReflection(
            UUID pageId,
            String senderId,
            String customerName,
            String userMessage,
            String assistantReply,
            List<ConversationState.CustomerCareNoteArtifact> customerCareNotes,
            String observedFact,
            String careNoteDelta,
            String workingHypothesis,
            Collection<String> confirmedScopes) {
        retainTurnWithReflection(
                pageId,
                senderId,
                customerName,
                userMessage,
                assistantReply,
                customerCareNotes,
                observedFact,
                careNoteDelta,
                workingHypothesis,
                "",
                confirmedScopes
        );
    }

    public void retainTurnWithReflection(
            UUID pageId,
            String senderId,
            String customerName,
            String userMessage,
            String assistantReply,
            List<ConversationState.CustomerCareNoteArtifact> customerCareNotes,
            String observedFact,
            String careNoteDelta,
            String workingHypothesis,
            String actualSentSpeechAct,
            Collection<String> confirmedScopes) {
        retainTurnWithReflection(
                pageId,
                senderId,
                customerName,
                userMessage,
                assistantReply,
                customerCareNotes,
                observedFact,
                careNoteDelta,
                workingHypothesis,
                actualSentSpeechAct,
                confirmedScopes,
                "chuyên viên phụ trách"
        );
    }

    public void retainTurnWithReflection(
            UUID pageId,
            String senderId,
            String customerName,
            String userMessage,
            String assistantReply,
            List<ConversationState.CustomerCareNoteArtifact> customerCareNotes,
            String observedFact,
            String careNoteDelta,
            String workingHypothesis,
            String actualSentSpeechAct,
            Collection<String> confirmedScopes,
            String careActor) {
        String tenantId = pageId != null ? pageId.toString() : "global";
        String customerId = (senderId != null && !senderId.isBlank()) ? senderId.strip() : "anonymous_customer";
        String conversationId = tenantId + ":" + customerId;
        String resolvedActor = (careActor != null && !careActor.isBlank()) ? careActor.strip() : "chuyên viên phụ trách";

        CustomerBrainMemory mem = getOrHydrateMemoryForMutation(pageId, customerId);
        List<ConversationState.CustomerCareNoteArtifact> effectiveArtifacts;
        FactMutationRecord mutationThisTurn = null;
        LivingHypothesisState updatedHypState;
        BrainReasoningProvenance retainProvenance;

        synchronized (mem) {
            if (observedFact != null && !observedFact.isBlank()) {
                mutationThisTurn = applyFactMemoryMutationLocked(
                        mem,
                        observedFact,
                        userMessage,
                        MAX_VERIFIED_FACTS_PER_CUSTOMER
                );
            } else if (userMessage != null && !userMessage.isBlank()
                    && (EXPLICIT_RETRACT_CLAUSE_PATTERN.matcher(userMessage).find()
                        || EXPLORATORY_UNCERTAIN_QUESTION_PATTERN.matcher(userMessage).matches())) {
                mutationThisTurn = applyFactMemoryMutationLocked(
                        mem,
                        "",
                        userMessage,
                        MAX_VERIFIED_FACTS_PER_CUSTOMER
                );
            }

            String sanitizedCareDelta = CleanAgentOutputRefereeAndSanitizer.validateCleanCareNoteDelta(careNoteDelta, userMessage, assistantReply);
            if (sanitizedCareDelta != null && !sanitizedCareDelta.isBlank()) {
                int beforeSize = mem.careNotes.size();
                List<String> prevNotes = new ArrayList<>(mem.careNotes);
                addBoundedCareNote(mem.careNotes, sanitizedCareDelta, MAX_CARE_NOTES_PER_CUSTOMER);
                if (!mem.careNotes.equals(prevNotes) || (beforeSize == 0 && !mem.careNotes.isEmpty())) {
                    rebuildArtifactsFromCareNotes(mem, tenantId, conversationId, confirmedScopes, resolvedActor);
                }
            }
            if (customerCareNotes != null && !customerCareNotes.isEmpty()) {
                for (ConversationState.CustomerCareNoteArtifact art : customerCareNotes) {
                    if (art != null && art.noteText() != null && !art.noteText().isBlank()) {
                        String sanitizedArtifactNote = CleanAgentOutputRefereeAndSanitizer.validateCleanCareNoteDelta(art.noteText(), userMessage, assistantReply);
                        if (sanitizedArtifactNote != null && !sanitizedArtifactNote.isBlank()) {
                            addBoundedCareNote(mem.careNotes, sanitizedArtifactNote, MAX_CARE_NOTES_PER_CUSTOMER);
                        }
                    }
                }
                rebuildArtifactsFromCareNotes(mem, tenantId, conversationId, confirmedScopes, resolvedActor);
            }

            boolean factChanged = mutationThisTurn != null
                    && mutationThisTurn.operation() != FactMemoryOperation.UNCERTAIN;
            updatedHypState = evaluateLivingHypothesis(
                    mem.livingHypothesisState,
                    mem.workingHypothesis,
                    userMessage,
                    assistantReply,
                    workingHypothesis,
                    mem.verifiedFacts,
                    factChanged
            );
            mem.livingHypothesisState = updatedHypState;
            mem.workingHypothesis = updatedHypState.hypothesisText();

            if (actualSentSpeechAct != null && !actualSentSpeechAct.isBlank()) {
                mem.lastActualSentSpeechAct = actualSentSpeechAct.strip();
            }

            BrainReasoningProvenance prevProv = mem.lastReasoningProvenance;
            String retainedStage = prevProv != null ? prevProv.stage() : "RETAINED_TURN";
            String retainedStageSrc = prevProv != null ? prevProv.stageSource() : "POST_TURN_RETENTION";
            String retainedArch = updatedHypState.status() == HypothesisStatus.INSUFFICIENT_EVIDENCE
                    ? "UNKNOWN"
                    : (prevProv != null ? prevProv.buyerArchetype() : "CONSULTATIVE_SEEKER");
            String retainedArchSrc = updatedHypState.status() == HypothesisStatus.INSUFFICIENT_EVIDENCE
                    ? "SHORT_UTTERANCE_INSUFFICIENT_EVIDENCE"
                    : (prevProv != null ? prevProv.archetypeSource() : "POST_TURN_RETENTION");
            String retainedSkill = prevProv != null ? prevProv.selectedSkillCode() : "none";
            String retainedSkillSrc = prevProv != null ? prevProv.skillSource() : "POST_TURN_RETENTION";
            retainProvenance = new BrainReasoningProvenance(
                    retainedStage,
                    retainedStageSrc,
                    retainedArch,
                    retainedArchSrc,
                    updatedHypState.confidence(),
                    "living_hypothesis_confidence=" + String.format(Locale.ROOT, "%.2f", updatedHypState.confidence())
                            + " (status=" + updatedHypState.status() + ")",
                    retainedSkill,
                    retainedSkillSrc,
                    updatedHypState.hypothesisText(),
                    updatedHypState.status(),
                    updatedHypState.provenanceReason(),
                    updatedHypState.customerEvidenceQuote(),
                    mutationThisTurn != null ? ("FACT_" + mutationThisTurn.operation().name()) : "NO_FACT_MUTATION",
                    Instant.now()
            );
            mem.lastReasoningProvenance = retainProvenance;
            effectiveArtifacts = List.copyOf(mem.careNoteArtifacts);
        }

        this.lastRetainedCareNotes = effectiveArtifacts;
        persistMemoryToDbIfEnabled(pageId, customerId, customerName, mem);
        log.info("[AgentBrainProvenance:Retain] page={} sender={} factOp={} verifiedFacts={}/{} careNotes={}/{} "
                        + "actualSentSpeechAct=\"{}\" hypothesisStatus={} confidence={} hypothesis=\"{}\" (reason={})",
                pageId,
                senderId,
                mutationThisTurn != null ? (mutationThisTurn.operation() + ":" + mutationThisTurn.slotGroup()) : "NONE",
                mem.verifiedFactsSnapshot().size(),
                MAX_VERIFIED_FACTS_PER_CUSTOMER,
                effectiveArtifacts.size(),
                MAX_CARE_NOTES_PER_CUSTOMER,
                mem.lastActualSentSpeechActSnapshot(),
                updatedHypState.status(),
                String.format(Locale.ROOT, "%.2f", updatedHypState.confidence()),
                updatedHypState.hypothesisText(),
                updatedHypState.provenanceReason());
    }

    private static void rebuildArtifactsFromCareNotes(
            CustomerBrainMemory mem,
            String tenantId,
            String conversationId,
            Collection<String> confirmedScopes,
            String careActor
    ) {
        mem.careNoteArtifacts.clear();
        Set<String> safeScopes = confirmedScopes != null && !confirmedScopes.isEmpty()
                ? Collections.unmodifiableSet(new LinkedHashSet<>(confirmedScopes))
                : Set.of("general_care");
        String safeActor = (careActor != null && !careActor.isBlank()) ? careActor.strip() : "chuyên viên phụ trách";
        int idx = 1;
        for (String note : mem.careNotes) {
            mem.careNoteArtifacts.add(new ConversationState.CustomerCareNoteArtifact(
                    tenantId,
                    conversationId,
                    "turn-" + idx++,
                    safeScopes,
                    safeActor,
                    note,
                    Instant.now()
            ));
        }
    }

    public List<AgentSkillDto> synchronizeActiveSkills(
            List<AgentSkillDto> currentActiveSkills,
            List<AgentSkillDto> candidateSkills,
            RecallAndAdviseResponse brainAdvice) {
        if (brainAdvice == null || brainAdvice.activeSkill() == null
                || brainAdvice.activeSkill().skillCode() == null) {
            if (currentActiveSkills == null || currentActiveSkills.isEmpty()) {
                return List.of();
            }
            return List.of(currentActiveSkills.get(0));
        }
        String targetCode = brainAdvice.activeSkill().skillCode().strip();
        List<AgentSkillDto> working = currentActiveSkills != null
                ? new ArrayList<>(currentActiveSkills)
                : new ArrayList<>();

        if (!working.isEmpty() && targetCode.equalsIgnoreCase(working.get(0).code())) {
            return List.of(working.get(0));
        }

        AgentSkillDto matched = null;
        for (AgentSkillDto s : working) {
            if (s != null && targetCode.equalsIgnoreCase(s.code())) {
                matched = s;
                break;
            }
        }
        if (matched == null && candidateSkills != null) {
            for (AgentSkillDto s : candidateSkills) {
                if (s != null && targetCode.equalsIgnoreCase(s.code())) {
                    matched = s;
                    break;
                }
            }
        }
        if (matched != null) {
            return List.of(matched);
        }
        return working.isEmpty() ? List.of() : List.of(working.get(0));
    }

    public CustomerInsightDto enrichCustomerInsight(
            CustomerInsightDto currentInsight,
            RecallAndAdviseResponse brainAdvice) {
        return enrichCustomerInsight(null, null, currentInsight, brainAdvice);
    }

    public CustomerInsightDto enrichCustomerInsight(
            UUID pageId,
            String senderId,
            CustomerInsightDto currentInsight,
            RecallAndAdviseResponse brainAdvice) {
        List<String> localFacts = (pageId != null || senderId != null)
                ? getRecalledVerifiedFacts(pageId, senderId)
                : List.of();
        List<String> localCareNotes = (pageId != null || senderId != null)
                ? getRecalledCareNotes(pageId, senderId)
                : List.of();
        String localHypothesis = (pageId != null || senderId != null)
                ? getRecalledWorkingHypothesis(pageId, senderId)
                : "";
        LivingHypothesisState hypState = (pageId != null || senderId != null)
                ? getLivingHypothesisState(pageId, senderId)
                : LivingHypothesisState.initialUnknown();
        String localSpeechAct = (pageId != null || senderId != null)
                ? getRecalledActualSentSpeechAct(pageId, senderId)
                : "";

        if (currentInsight == null && brainAdvice == null
                && localFacts.isEmpty() && localCareNotes.isEmpty()
                && localHypothesis.isEmpty() && localSpeechAct.isEmpty()
                && hypState.status() == HypothesisStatus.UNKNOWN) {
            return null;
        }

        String defaultArch = hypState.status() == HypothesisStatus.INSUFFICIENT_EVIDENCE
                ? "UNKNOWN"
                : (brainAdvice != null && brainAdvice.buyerArchetype() != null ? brainAdvice.buyerArchetype() : "VALUE_CONSCIOUS");
        CustomerInsightDto base = currentInsight != null
                ? currentInsight
                : new CustomerInsightDto(
                        defaultArch,
                        "UNKNOWN".equals(defaultArch) ? "Chưa đủ dữ kiện tâm lý" : "Khách hàng cân nhắc kỹ",
                        "UNKNOWN".equals(defaultArch) ? 35 : 55,
                        "UNKNOWN".equals(defaultArch) ? "Trung tính" : "Ấm",
                        "mình",
                        List.of(),
                        List.of(),
                        ""
                );

        CustomerBrainMemory tempMerger = new CustomerBrainMemory();
        for (String bp : base.extractedPains()) {
            applyFactMemoryMutationLocked(tempMerger, bp, "", MAX_VERIFIED_FACTS_PER_CUSTOMER);
        }
        if (brainAdvice != null && brainAdvice.activeFacts() != null) {
            for (MemoryFactDto f : brainAdvice.activeFacts()) {
                if (f != null && f.isActive() && f.factKey() != null && f.factValue() != null) {
                    String factSummary = f.factKey() + "=" + f.factValue();
                    applyFactMemoryMutationLocked(tempMerger, factSummary, "", MAX_VERIFIED_FACTS_PER_CUSTOMER);
                }
            }
        }
        for (String lf : localFacts) {
            if (!tempMerger.verifiedFacts.contains(lf)) {
                applyFactMemoryMutationLocked(tempMerger, lf, "", MAX_VERIFIED_FACTS_PER_CUSTOMER);
            }
        }
        List<String> mergedPains = tempMerger.verifiedFactsSnapshot();

        List<String> mergedCareNotes = new ArrayList<>(base.careNotes());
        for (String cn : localCareNotes) {
            addBoundedCareNote(mergedCareNotes, cn, MAX_CARE_NOTES_PER_CUSTOMER);
        }

        List<String> mergedConcerns = new ArrayList<>(base.hiddenConcerns());
        if (brainAdvice != null && brainAdvice.objections() != null) {
            for (ObjectionItemDto obj : brainAdvice.objections()) {
                if (obj != null && obj.type() != null) {
                    String objLabel = "Rào cản [" + obj.type() + "]: " + (obj.quote() != null ? obj.quote() : "");
                    if (mergedConcerns.stream().noneMatch(c -> c.contains("[" + obj.type() + "]"))) {
                        mergedConcerns.add(objLabel);
                    }
                }
            }
        }
        String enrichedStrategy = base.recommendedStrategy();
        if (brainAdvice != null && brainAdvice.activeSkill() != null) {
            String cadence = brainAdvice.activeSkill().audienceVoiceCadence();
            String directive = brainAdvice.activeSkill().promptDirective();
            if ("WITHHOLD_NUMBERS_HOOK_FIRST".equalsIgnoreCase(brainAdvice.activeSkill().priceDisclosureGate())
                    && directive != null && !directive.isBlank()) {
                enrichedStrategy = (cadence != null && !cadence.isBlank() ? "[" + cadence + "] " : "") + directive;
            } else if (cadence != null && !cadence.isBlank() && enrichedStrategy != null && !enrichedStrategy.contains(cadence)) {
                enrichedStrategy = enrichedStrategy + " | Âm hưởng tệp KH: " + cadence;
            }
        }

        String resolvedHypothesis;
        if (hypState.status() == HypothesisStatus.REJECTED_BY_CUSTOMER) {
            resolvedHypothesis = localHypothesis;
        } else if (!localHypothesis.isBlank()) {
            resolvedHypothesis = localHypothesis;
        } else {
            resolvedHypothesis = base.workingHypothesis();
        }

        String resolvedSpeechAct = !localSpeechAct.isBlank()
                ? localSpeechAct
                : base.lastActualSentSpeechAct();
        CustomerBrainMemory storedMem = (pageId != null || senderId != null)
                ? resolveMemoryReadOnly(pageId, senderId)
                : null;
        int resolvedPriceObjectionCount = storedMem != null
                ? Math.max(base.priceObjectionCount(), storedMem.priceObjectionCountSnapshot())
                : base.priceObjectionCount();
        boolean resolvedInPriceObjectionContext = storedMem != null
                ? storedMem.inPriceObjectionContextSnapshot()
                : base.inPriceObjectionContext();
        boolean resolvedHasPresentedDownsell = storedMem != null
                ? (base.hasPresentedDownsell() || storedMem.hasPresentedDownsellSnapshot())
                : base.hasPresentedDownsell();
        String resolvedHonorific = (storedMem != null && storedMem.inferredCustomerPronounSnapshot() != null)
                ? storedMem.inferredCustomerPronounSnapshot()
                : base.honorific();

        return new CustomerInsightDto(
                base.buyerArchetype(),
                base.archetypeLabel(),
                base.buyingTemperature(),
                base.previousTemperature(),
                base.temperatureDelta(),
                base.momentum(),
                base.temperatureLabel(),
                base.decayReason(),
                resolvedHonorific,
                mergedPains,
                mergedConcerns,
                enrichedStrategy,
                mergedCareNotes,
                resolvedHypothesis,
                resolvedSpeechAct,
                resolvedPriceObjectionCount,
                resolvedInPriceObjectionContext,
                resolvedHasPresentedDownsell
        );
    }

    private static String abbreviate(String s, int maxLen) {
        if (s == null || s.isBlank()) {
            return "";
        }
        String clean = s.replaceAll("\\s+", " ").strip();
        return clean.length() <= maxLen ? clean : clean.substring(0, maxLen) + "...";
    }
}
