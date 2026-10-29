package com.example.docmind.service.bot;

import com.example.docmind.client.AgentBrainClient;
import com.example.docmind.client.AgentBrainClient.RecallAndAdviseRequest;
import com.example.docmind.client.AgentBrainClient.RecallAndAdviseResponse;
import com.example.docmind.client.AgentBrainClient.ReflectRequest;
import com.example.docmind.client.AgentBrainClient.ReflectResponse;
import com.example.docmind.client.AgentBrainClient.RetainTurnRequest;
import com.example.docmind.client.AgentBrainClient.RetainTurnResponse;
import com.example.docmind.dto.PagePersonaConfig;
import com.example.docmind.dto.bot.AgentSkillDto;
import com.example.docmind.dto.bot.CustomerInsightDto;
import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.service.retrieval.PromptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.net.http.HttpClient;
import java.text.Normalizer;
import java.time.Duration;
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
import java.util.concurrent.CompletableFuture;
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
            + "(?:qua|ghé|hẹn)\\s+(?:lúc|khung|tầm)\\s+\\d{1,2}\\s*(?:h|giờ)|"
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
            "(?isU)\\b(đả\\s+thông\\s+kinh\\s+lạc|thảo\\s+dược\\s+quý|tinh\\s+dầu\\s+thiên\\s+nhiên\\s+cao\\s+cấp|"
            + "rèm\\s+che\\s+riêng\\s+kín\\s+đáo|bữa\\s+ăn\\s+nhẹ\\s+thực\\s+dưỡng|men\\s+kim\\s+cương\\s+siêu\\s+bóng|"
            + "cam\\s+kết\\s+đầu\\s+ra\\s+100%|ưu\\s+đãi\\s+độc\\s+quyền|đẳng\\s+cấp\\s+5\\s+sao)\\b"
    );

    private static final Set<String> HYPOTHESIS_META_STOPWORDS = Set.of(
            "khách", "hàng", "đang", "muốn", "quan", "tâm", "cân", "nhắc", "tham", "khảo",
            "tìm", "hiểu", "phương", "án", "dịch", "vụ", "sản", "phẩm", "gói", "mẫu", "khóa",
            "giá", "mức", "chi", "phí", "bên", "mình", "chị", "anh", "cần", "hỏi", "thêm",
            "giả", "thuyết", "cập", "nhật", "lượt", "thứ", "hiện", "tại", "với", "cho", "của"
    );

    private final AgentBrainClient client;
    private final String baseUrl;

    @Autowired
    public AgentBrainService(
            @Value("${agent.brain.base-url:${AGENT_BRAIN_BASE_URL:http://localhost:8095}}") String configuredBaseUrl) {
        this.baseUrl = (configuredBaseUrl != null && !configuredBaseUrl.isBlank())
                ? configuredBaseUrl.strip()
                : "http://localhost:8095";

        HttpClient jdkHttp = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(800))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(jdkHttp);
        requestFactory.setReadTimeout(Duration.ofMillis(1500));

        RestClient restClient = RestClient.builder()
                .baseUrl(this.baseUrl)
                .requestFactory(requestFactory)
                .build();

        HttpServiceProxyFactory factory = HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build();
        this.client = factory.createClient(AgentBrainClient.class);
    }

    public AgentBrainService(AgentBrainClient customClient) {
        this.client = customClient;
        this.baseUrl = "custom";
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
        String conversationId = tenantId + ":" + customerId;

        CustomerBrainMemory mem = customerMemoryStore.computeIfAbsent(conversationId, k -> new CustomerBrainMemory());
        double boundedRetrieval = Math.max(0.0, Math.min(1.0, retrievalQuality));
        double boundedGroundedness = Math.max(0.0, Math.min(1.0, groundedness));

        RecallAndAdviseResponse remoteRes = null;
        if (client != null) {
            Map<String, Object> domainMeta = new java.util.LinkedHashMap<>();
            domainMeta.put("is_first_turn", isFirstTurn);
            if (pagePersona != null) {
                if (pagePersona.name() != null && !pagePersona.name().isBlank()) {
                    domainMeta.put("page_name", pagePersona.name().strip());
                }
                if (pagePersona.industry() != null && !pagePersona.industry().isBlank()) {
                    domainMeta.put("industry", pagePersona.industry().strip());
                }
                if (pagePersona.targetAudience() != null && !pagePersona.targetAudience().isBlank()) {
                    domainMeta.put("target_audience", pagePersona.targetAudience().strip());
                }
                if (pagePersona.personaConfig() != null && pagePersona.personaConfig().toneStyle() != null) {
                    domainMeta.put("tone_style", pagePersona.personaConfig().toneStyle().strip());
                } else if (pagePersona.description() != null && !pagePersona.description().isBlank()) {
                    domainMeta.put("tone_style", pagePersona.description().strip());
                }
            }
            try {
                remoteRes = client.recallAndAdvise(new RecallAndAdviseRequest(
                        tenantId,
                        customerId,
                        conversationId,
                        message,
                        customerName,
                        boundedRetrieval,
                        boundedGroundedness,
                        domainMeta
                ));
            } catch (Exception ex) {
                log.debug("[AgentBrain] recallAndAdvise unreachable at {} ({}), computing in-JVM evidence provenance",
                        baseUrl, ex.getMessage());
            }
        }

        BrainReasoningProvenance provenance = computeRecallProvenance(
                mem,
                message,
                boundedRetrieval,
                boundedGroundedness,
                isFirstTurn,
                remoteRes
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

        return remoteRes;
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

    public List<String> getRecalledCareNotes(UUID pageId, String senderId) {
        CustomerBrainMemory mem = customerMemoryStore.get(toConversationMemoryKey(pageId, senderId));
        return mem != null ? mem.careNotesSnapshot() : List.of();
    }

    public List<String> getRecalledVerifiedFacts(UUID pageId, String senderId) {
        CustomerBrainMemory mem = customerMemoryStore.get(toConversationMemoryKey(pageId, senderId));
        return mem != null ? mem.verifiedFactsSnapshot() : List.of();
    }

    public List<FactMutationRecord> getFactMutationHistory(UUID pageId, String senderId) {
        CustomerBrainMemory mem = customerMemoryStore.get(toConversationMemoryKey(pageId, senderId));
        return mem != null ? mem.factMutationsSnapshot() : List.of();
    }

    public List<String> getSupersededOrRetractedFacts(UUID pageId, String senderId) {
        CustomerBrainMemory mem = customerMemoryStore.get(toConversationMemoryKey(pageId, senderId));
        return mem != null ? mem.supersededOrRetractedFactsSnapshot() : List.of();
    }

    public List<String> getUncertainExplorations(UUID pageId, String senderId) {
        CustomerBrainMemory mem = customerMemoryStore.get(toConversationMemoryKey(pageId, senderId));
        return mem != null ? mem.uncertainExplorationsSnapshot() : List.of();
    }

    public String getRecalledWorkingHypothesis(UUID pageId, String senderId) {
        CustomerBrainMemory mem = customerMemoryStore.get(toConversationMemoryKey(pageId, senderId));
        return mem != null ? mem.workingHypothesisSnapshot() : "";
    }

    public LivingHypothesisState getLivingHypothesisState(UUID pageId, String senderId) {
        CustomerBrainMemory mem = customerMemoryStore.get(toConversationMemoryKey(pageId, senderId));
        return mem != null ? mem.livingHypothesisStateSnapshot() : LivingHypothesisState.initialUnknown();
    }

    public BrainReasoningProvenance getLastReasoningProvenance(UUID pageId, String senderId) {
        CustomerBrainMemory mem = customerMemoryStore.get(toConversationMemoryKey(pageId, senderId));
        return mem != null ? mem.lastReasoningProvenanceSnapshot() : null;
    }

    public String getRecalledActualSentSpeechAct(UUID pageId, String senderId) {
        CustomerBrainMemory mem = customerMemoryStore.get(toConversationMemoryKey(pageId, senderId));
        return mem != null ? mem.lastActualSentSpeechActSnapshot() : "";
    }

    public int getRecalledPriceObjectionCount(UUID pageId, String senderId) {
        CustomerBrainMemory mem = customerMemoryStore.get(toConversationMemoryKey(pageId, senderId));
        return mem != null ? mem.priceObjectionCountSnapshot() : 0;
    }

    public boolean isRecalledInPriceObjectionContext(UUID pageId, String senderId) {
        CustomerBrainMemory mem = customerMemoryStore.get(toConversationMemoryKey(pageId, senderId));
        return mem != null && mem.inPriceObjectionContextSnapshot();
    }

    public boolean hasRecalledPresentedDownsell(UUID pageId, String senderId) {
        CustomerBrainMemory mem = customerMemoryStore.get(toConversationMemoryKey(pageId, senderId));
        return mem != null && mem.hasPresentedDownsellSnapshot();
    }

    public void recordCommercialFunnelState(
            UUID pageId,
            String senderId,
            int priceObjectionCount,
            boolean inPriceObjectionContext,
            boolean hasPresentedDownsell) {
        CustomerBrainMemory mem = customerMemoryStore.computeIfAbsent(
                toConversationMemoryKey(pageId, senderId),
                k -> new CustomerBrainMemory()
        );
        synchronized (mem) {
            mem.priceObjectionCount = Math.max(0, priceObjectionCount);
            mem.inPriceObjectionContext = inPriceObjectionContext;
            mem.hasPresentedDownsell = hasPresentedDownsell;
        }
    }

    public void clearCustomerMemory(UUID pageId, String senderId) {
        customerMemoryStore.remove(toConversationMemoryKey(pageId, senderId));
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
        boolean precedingWasExplicitAppointmentProposal = !precedingWasOperationalAnswer
                && !CleanAgentOrchestratorService.isPauseOrDeferralBotReply(botText)
                && ((botText != null && EXPLICIT_APPOINTMENT_PROPOSAL_BOT_PATTERN.matcher(botText).matches())
                    || ("COLLECTING_VISIT_TIME".equalsIgnoreCase(bookingStatus)
                        || "AWAITING_DATE_OR_TIME".equalsIgnoreCase(bookingStatus)
                        || "AWAITING_PRODUCT_SELECTION".equalsIgnoreCase(bookingStatus)
                        || "AWAITING_CUSTOMER_CONFIRMATION".equalsIgnoreCase(bookingStatus)));
        boolean precedingWasRespectedPause = CleanAgentOrchestratorService.SPEECH_ACT_RESPECTED_PAUSE.equalsIgnoreCase(speechAct)
                || CleanAgentOrchestratorService.isPauseOrDeferralBotReply(botText)
                || CleanAgentOrchestratorService.wasPriorCustomerTurnHesitation(history);
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

        // 4. NGỮ CẢNH 3: Ngay sau khi bot đề xuất khung giờ hẹn / gói đặt lịch cụ thể (khách đáp "Cũng được", "Ừ", "Ok")
        if (precedingWasExplicitAppointmentProposal && (isLukewarm || isBareAffirm)) {
            return new ShortUtteranceResolution(
                    true,
                    utteranceType,
                    "POSITIVE_CONFIRMATION_SIGNAL",
                    "PROCEED_BOOKING_CONFIRMATION_OR_SLOT_COLLECTION",
                    "STAGE_4_POSITIVE_TRIAL_OR_BOOKING",
                    List.of("POSITIVE_CONFIRMATION_SIGNAL", "POSITIVE_INTEREST_READY_FOR_TRIAL_INVITE"),
                    0.88,
                    "precedingTurn=APPOINTMENT_OR_BOOKING_PROPOSAL(\"" + abbreviate(botText, 70) + "\"), msg=\"" + trimmed + "\""
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
            if (hasPriorQuotedPrice) {
                return new ShortUtteranceResolution(
                        true,
                        utteranceType,
                        "LUKEWARM_POST_PRICE_QUOTE_EVALUATION",
                        "GENTLE_CHECKIN_OR_SOFT_CLARIFY_VALUE",
                        catalogHasEconomicalOption && !hasPresentedDownsell && isConcessive
                                ? "STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL"
                                : "STAGE_1_DISCOVERY_AND_FIRST_QUOTE",
                        List.of("LUKEWARM_POST_QUOTE_CHECKIN"),
                        0.72,
                        "precedingTurn=INITIAL_PRICE_QUOTE, priceObjectionCount=0, msg=\"" + trimmed + "\""
                );
            }
        }

        // 7. Khách đáp đồng thuận ngắn ("Ok", "Ừ", "Ukm") ngay sau câu mời thử trải nghiệm 1 buổi
        if (isBareAffirm && precedingWasTrialInvite) {
            return new ShortUtteranceResolution(
                    true,
                    utteranceType,
                    "POSITIVE_CONFIRMATION_SIGNAL",
                    "PROCEED_BOOKING_CONFIRMATION_OR_SLOT_COLLECTION",
                    "STAGE_4_POSITIVE_TRIAL_OR_BOOKING",
                    List.of("POSITIVE_CONFIRMATION_SIGNAL", "POSITIVE_INTEREST_READY_FOR_TRIAL_INVITE"),
                    0.85,
                    "precedingTurn=INVITED_TRIAL_OR_TIME, msg=\"" + trimmed + "\""
            );
        }

        // 7b. Khách chỉ đáp nhát gừng ("Ừ", "Ừm") sau câu giải thích bảo vệ giá (không có câu hỏi mời thử/đặt lịch)
        if (isBareAffirm && !precedingWasTrialInvite && !precedingWasExplicitAppointmentProposal && inPriceObjectionContext) {
            if (!hasPresentedDownsell && catalogHasEconomicalOption) {
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
            return new ShortUtteranceResolution(
                    true,
                    utteranceType,
                    "PASSIVE_ACK_AFTER_VALUE_DEFENSE_NO_DOWNSELL",
                    "RESPECT_PACE_OR_BRIEF_CHECKIN",
                    "STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE",
                    List.of("HESITATION_OR_DEFERRAL", "POLITE_CLOSING_ACKNOWLEDGMENT"),
                    0.74,
                    "precedingTurn=VALUE_DEFENSE_NO_TRIAL_QUESTION, downsellPresented=" + hasPresentedDownsell + ", msg=\"" + trimmed + "\""
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
        String conversationId = toConversationMemoryKey(pageId, senderId);
        CustomerBrainMemory mem = customerMemoryStore.computeIfAbsent(conversationId, k -> new CustomerBrainMemory());
        synchronized (mem) {
            return applyFactMemoryMutationLocked(mem, rawFactOrUtterance, customerUtteranceContext, MAX_VERIFIED_FACTS_PER_CUSTOMER);
        }
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

        // 1. Kiểm tra SUPERSEDE tường minh trước ("đổi sang", "chuyển sang", "đổi từ gói 530k sang 239k", "đổi lịch từ tối nay sang chiều mai")
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
            // Ngược lại (ví dụ slot_target_scope: đang ốp "phòng khách", nói thêm "phòng bếp"; hoặc đau "vai gáy", thêm "thắt lưng") -> KHÔNG xóa mục cũ, đi tiếp sang ADD!
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
            stageSource = "REMOTE_AGENT_BRAIN_HTTP";
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
            archetypeSource = "REMOTE_AGENT_BRAIN_HTTP";
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
                ? ("remote_final_confidence=" + String.format(Locale.ROOT, "%.2f", remoteRes.finalConfidence()))
                : String.format(Locale.ROOT, "base=%.2f + facts=%.2f + retrieval=%.2f + grounded=%.2f + pivotAdj=%.2f => %.2f",
                        baseConf, factBonus, retrievalBonus, groundedBonus, opPenalty, computedConf);

        String skillCode = (remoteRes != null && remoteRes.activeSkill() != null && remoteRes.activeSkill().skillCode() != null)
                ? remoteRes.activeSkill().skillCode()
                : (stage.contains("STAGE_2") ? "price_objection_soft_anchor"
                    : (stage.contains("STAGE_3B") || stage.contains("STAGE_6") ? "spa_graceful_exit_after_rejection"
                        : (stage.contains("STAGE_4") ? "booking_soft_confirm" : "consultative_discovery")));
        String skillSource = (remoteRes != null && remoteRes.activeSkill() != null)
                ? "REMOTE_AGENT_BRAIN_ACTIVE_SKILL"
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

        CustomerBrainMemory mem = customerMemoryStore.computeIfAbsent(conversationId, k -> new CustomerBrainMemory());
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

        if (client == null || userMessage == null || userMessage.isBlank()) {
            return;
        }
        StringBuilder enrichedReplySb = new StringBuilder(assistantReply != null ? assistantReply : "");
        if (!effectiveArtifacts.isEmpty()) {
            enrichedReplySb.append("\n[CUSTOMER_CARE_NOTES: ").append(effectiveArtifacts).append("]");
        }
        if ((observedFact != null && !observedFact.isBlank())
                || (careNoteDelta != null && !careNoteDelta.isBlank())
                || !updatedHypState.hypothesisText().isBlank()
                || (actualSentSpeechAct != null && !actualSentSpeechAct.isBlank())) {
            enrichedReplySb.append("\n[BRAIN_REFLECTION: observed_fact=")
                    .append(observedFact != null ? observedFact.strip() : "")
                    .append(" | care_note_delta=")
                    .append(careNoteDelta != null ? careNoteDelta.strip() : "")
                    .append(" | working_hypothesis=")
                    .append(updatedHypState.hypothesisText())
                    .append(" | hypothesis_status=")
                    .append(updatedHypState.status())
                    .append(" | actual_sent_speech_act=")
                    .append(actualSentSpeechAct != null ? actualSentSpeechAct.strip() : "")
                    .append("]");
        }
        String enrichedAssistantReply = enrichedReplySb.toString();

        CompletableFuture.runAsync(() -> {
            try {
                RetainTurnResponse retained = client.retainTurn(new RetainTurnRequest(
                        tenantId,
                        customerId,
                        conversationId,
                        userMessage,
                        enrichedAssistantReply,
                        customerName,
                        true
                ));
                if (retained != null) {
                    log.info("[AgentBrain] Retained turn (tenant={}, customer={}, extractedFacts={}, supersededFacts={}, careNotes={}, stage={})",
                            tenantId,
                            customerId,
                            retained.extractedFacts() != null ? retained.extractedFacts().size() : 0,
                            retained.supersededFactIds() != null ? retained.supersededFactIds().size() : 0,
                            effectiveArtifacts.size(),
                            retained.purchaseStage());
                }
            } catch (Exception ex) {
                log.debug("[AgentBrain] retainTurnAsync skipped ({})", ex.getMessage());
            }
        });
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

    public ReflectResponse reflectCustomer(UUID pageId, String senderId) {
        if (client == null) {
            return null;
        }
        String tenantId = pageId != null ? pageId.toString() : "global";
        String customerId = (senderId != null && !senderId.isBlank()) ? senderId.strip() : "anonymous_customer";
        String conversationId = tenantId + ":" + customerId;
        try {
            return client.reflect(new ReflectRequest(tenantId, customerId, conversationId));
        } catch (Exception ex) {
            log.debug("[AgentBrain] reflectCustomer unreachable ({})", ex.getMessage());
            return null;
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
            for (AgentBrainClient.MemoryFactDto f : brainAdvice.activeFacts()) {
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
            for (AgentBrainClient.ObjectionItemDto obj : brainAdvice.objections()) {
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
                ? customerMemoryStore.get(toConversationMemoryKey(pageId, senderId))
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

        return new CustomerInsightDto(
                base.buyerArchetype(),
                base.archetypeLabel(),
                base.buyingTemperature(),
                base.previousTemperature(),
                base.temperatureDelta(),
                base.momentum(),
                base.temperatureLabel(),
                base.decayReason(),
                base.honorific(),
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
