package com.example.docmind.service.bot;

import com.example.docmind.client.AgentBrainClient;
import com.example.docmind.client.AgentBrainClient.RecallAndAdviseRequest;
import com.example.docmind.client.AgentBrainClient.RecallAndAdviseResponse;
import com.example.docmind.client.AgentBrainClient.ReflectRequest;
import com.example.docmind.client.AgentBrainClient.ReflectResponse;
import com.example.docmind.client.AgentBrainClient.RetainTurnRequest;
import com.example.docmind.client.AgentBrainClient.RetainTurnResponse;
import com.example.docmind.dto.bot.AgentSkillDto;
import com.example.docmind.dto.bot.CustomerInsightDto;
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
import java.time.Duration;
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

/**
 * Kết nối trực tiếp {@code docmind-api} với bộ não độc lập {@code agent-brain} (cổng 8095):
 * <ul>
 *   <li><b>Hot Path ({@code recallAndAdvise}, &lt; 10ms):</b> Truy hồi {@code customer_memory_block}
 *       (các sự thật đã kiểm chứng, lịch sử bác bỏ {@code superseded_by}, rào cản {@code objections})
 *       và chiến thuật kỹ năng tâm lý {@code active_skill}.</li>
 *   <li><b>Async Cold Path ({@code retainTurnAsync}):</b> Bóc tách thực thể mới, giải quyết xung đột
 *       ({@code Current Explicit Message > Verified Fact > Recent Summary > Inferred Old Fact})
 *       và cập nhật bộ nhớ khách hàng bất đồng bộ sau mỗi lượt hội thoại.</li>
 * </ul>
 */
@Service
public class AgentBrainService {

    private static final Logger log = LoggerFactory.getLogger(AgentBrainService.class);

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
        if (client == null || message == null || message.isBlank()) {
            return null;
        }
        String tenantId = pageId != null ? pageId.toString() : "global";
        String customerId = (senderId != null && !senderId.isBlank()) ? senderId.strip() : "anonymous_customer";
        String conversationId = tenantId + ":" + customerId;
        java.util.Map<String, Object> domainMeta = new java.util.LinkedHashMap<>();
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
            RecallAndAdviseResponse res = client.recallAndAdvise(new RecallAndAdviseRequest(
                    tenantId,
                    customerId,
                    conversationId,
                    message,
                    customerName,
                    Math.max(0.0, Math.min(1.0, retrievalQuality)),
                    Math.max(0.0, Math.min(1.0, groundedness)),
                    domainMeta
            ));
            if (res != null) {
                log.info("[AgentBrain] RecallAndAdvise ok in {} ms (tenant={}, customer={}, stage={}, archetype={}, skill={}, facts={}, confidence={})",
                        res.latencyMs(),
                        tenantId,
                        customerId,
                        res.purchaseStage(),
                        res.buyerArchetype(),
                        res.activeSkill() != null ? res.activeSkill().skillCode() : "none",
                        res.activeFacts() != null ? res.activeFacts().size() : 0,
                        res.finalConfidence());
            }
            return res;
        } catch (Exception ex) {
            log.debug("[AgentBrain] recallAndAdvise unreachable at {} ({}), continuing with in-JVM insight engine",
                    baseUrl, ex.getMessage());
            return null;
        }
    }

    public static final int MAX_CARE_NOTES_PER_CUSTOMER = 4;
    public static final int MAX_VERIFIED_FACTS_PER_CUSTOMER = 5;

    /**
     * Bộ nhớ In-JVM có kiểm soát dung lượng & chống trùng lặp theo từng khách hàng ({@code tenantId:customerId}).
     */
    public static final class CustomerBrainMemory {
        private final List<String> verifiedFacts = new ArrayList<>();
        private final List<String> careNotes = new ArrayList<>();
        private final List<ConversationState.CustomerCareNoteArtifact> careNoteArtifacts = new ArrayList<>();
        private String workingHypothesis = "";
        private String lastActualSentSpeechAct = "";

        public synchronized List<String> verifiedFactsSnapshot() {
            return List.copyOf(verifiedFacts);
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

        public synchronized String lastActualSentSpeechActSnapshot() {
            return lastActualSentSpeechAct;
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

    public String getRecalledWorkingHypothesis(UUID pageId, String senderId) {
        CustomerBrainMemory mem = customerMemoryStore.get(toConversationMemoryKey(pageId, senderId));
        return mem != null ? mem.workingHypothesisSnapshot() : "";
    }

    public String getRecalledActualSentSpeechAct(UUID pageId, String senderId) {
        CustomerBrainMemory mem = customerMemoryStore.get(toConversationMemoryKey(pageId, senderId));
        return mem != null ? mem.lastActualSentSpeechActSnapshot() : "";
    }

    public void clearCustomerMemory(UUID pageId, String senderId) {
        customerMemoryStore.remove(toConversationMemoryKey(pageId, senderId));
    }

    /**
     * Kiểm tra độ trùng lặp từ vựng > 70% giữa hai ghi chú hoặc sự thật.
     */
    public static boolean hasWordOverlapAbove70Percent(String candidate, String existing) {
        if (candidate == null || candidate.isBlank() || existing == null || existing.isBlank()) {
            return false;
        }
        String candNorm = candidate.strip().toLowerCase(Locale.ROOT);
        String existNorm = existing.strip().toLowerCase(Locale.ROOT);
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

    private static List<String> tokenizeWords(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String cleaned = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\d\\s]+", " ")
                .replaceAll("\\s+", " ")
                .strip();
        if (cleaned.isEmpty()) {
            return List.of();
        }
        return List.of(cleaned.split("\\s+"));
    }

    private static String extractSlotPrefixOrCategory(String item) {
        if (item == null || item.isBlank()) {
            return null;
        }
        String low = item.strip().toLowerCase(Locale.ROOT);
        int eqIdx = low.indexOf('=');
        int colonIdx = low.indexOf(':');
        if (eqIdx > 0 && eqIdx <= 28 && (colonIdx < 0 || eqIdx < colonIdx)) {
            return low.substring(0, eqIdx).strip();
        }
        if (low.contains("khung giờ") || low.contains("giờ ghé") || low.contains("lịch hẹn") || low.contains("đổi lịch") || low.contains("đổi sang khung giờ")) {
            return "slot_visit_time";
        }
        if (low.contains("vùng đau") || low.contains("vùng mỏi") || low.contains("đổi vùng") || low.contains("chuyển sang vùng")) {
            return "slot_body_area";
        }
        if (low.contains("ngân sách") || low.contains("tầm giá")) {
            return "slot_budget";
        }
        return null;
    }

    private static boolean isExplicitUpdateOrCorrection(String item) {
        if (item == null || item.isBlank()) {
            return false;
        }
        String low = item.toLowerCase(Locale.ROOT);
        return low.contains("đổi sang")
                || low.contains("chuyển sang")
                || low.contains("thay vì")
                || low.contains("không phải")
                || low.contains("cập nhật lại")
                || low.contains("sửa lại");
    }

    private static void addBoundedAndDeduplicated(
            List<String> targetList,
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
        String candCategory = extractSlotPrefixOrCategory(candidate);
        boolean isCorrection = isExplicitUpdateOrCorrection(candidate);

        // 1. Giải quyết xung đột (Conflict Resolution): Nếu mục mới cập nhật cùng slot/danh mục hoặc đính chính thông tin cũ -> ghi đè mục cũ
        if (candCategory != null) {
            for (int i = targetList.size() - 1; i >= 0; i--) {
                String existing = targetList.get(i);
                String existCategory = extractSlotPrefixOrCategory(existing);
                if (candCategory.equals(existCategory)) {
                    if (hasWordOverlapAbove70Percent(candidate, existing) && !isCorrection) {
                        return;
                    }
                    targetList.remove(i);
                    break;
                }
            }
        } else if (isCorrection && !targetList.isEmpty()) {
            // Nếu là thông tin đính chính trực tiếp có giao thoa từ khóa chủ đề -> thay thế mục cũ liên quan
            List<String> candTokens = tokenizeWords(candidate);
            for (int i = targetList.size() - 1; i >= 0; i--) {
                List<String> existTokens = tokenizeWords(targetList.get(i));
                long shared = candTokens.stream().filter(t -> t.length() >= 3 && existTokens.contains(t)).count();
                if (shared >= 2) {
                    targetList.remove(i);
                    break;
                }
            }
        }

        // 2. Chống trùng lặp (Deduplication): Không thêm nếu trùng > 70% từ vựng với mục đã có
        for (String existing : targetList) {
            if (hasWordOverlapAbove70Percent(candidate, existing)) {
                return;
            }
        }

        // 3. Thêm mới và giới hạn dung lượng (Bounded Memory: giữ các mục mới nhất/quan trọng nhất)
        targetList.add(candidate);
        while (targetList.size() > maxCapacity) {
            targetList.remove(0);
        }
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

    /**
     * Lưu trữ đồng bộ Delta phản tư ({@code observed_fact}, {@code care_note_delta}, {@code working_hypothesis},
     * và {@code actual_sent_speech_act} đã xác thực sau hậu xử lý) vào bộ nhớ có giới hạn dung lượng & chống trùng lặp,
     * đồng thời đẩy bất đồng bộ sang {@code agent-brain}.
     */
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
        String tenantId = pageId != null ? pageId.toString() : "global";
        String customerId = (senderId != null && !senderId.isBlank()) ? senderId.strip() : "anonymous_customer";
        String conversationId = tenantId + ":" + customerId;

        CustomerBrainMemory mem = customerMemoryStore.computeIfAbsent(conversationId, k -> new CustomerBrainMemory());
        List<ConversationState.CustomerCareNoteArtifact> effectiveArtifacts;
        synchronized (mem) {
            if (observedFact != null && !observedFact.isBlank()) {
                addBoundedAndDeduplicated(mem.verifiedFacts, observedFact, MAX_VERIFIED_FACTS_PER_CUSTOMER);
            }
            if (careNoteDelta != null && !careNoteDelta.isBlank()) {
                int beforeSize = mem.careNotes.size();
                List<String> prevNotes = new ArrayList<>(mem.careNotes);
                addBoundedAndDeduplicated(mem.careNotes, careNoteDelta, MAX_CARE_NOTES_PER_CUSTOMER);
                if (!mem.careNotes.equals(prevNotes)) {
                    rebuildArtifactsFromCareNotes(mem, tenantId, conversationId, confirmedScopes);
                } else if (beforeSize == 0 && !mem.careNotes.isEmpty()) {
                    rebuildArtifactsFromCareNotes(mem, tenantId, conversationId, confirmedScopes);
                }
            }
            if (customerCareNotes != null && !customerCareNotes.isEmpty()) {
                for (ConversationState.CustomerCareNoteArtifact art : customerCareNotes) {
                    if (art != null && art.noteText() != null && !art.noteText().isBlank()) {
                        addBoundedAndDeduplicated(mem.careNotes, art.noteText(), MAX_CARE_NOTES_PER_CUSTOMER);
                    }
                }
                rebuildArtifactsFromCareNotes(mem, tenantId, conversationId, confirmedScopes);
            }
            if (workingHypothesis != null && !workingHypothesis.isBlank()) {
                mem.workingHypothesis = workingHypothesis.strip();
            }
            if (actualSentSpeechAct != null && !actualSentSpeechAct.isBlank()) {
                mem.lastActualSentSpeechAct = actualSentSpeechAct.strip();
            }
            effectiveArtifacts = List.copyOf(mem.careNoteArtifacts);
        }

        this.lastRetainedCareNotes = effectiveArtifacts;
        if (!effectiveArtifacts.isEmpty()
                || (observedFact != null && !observedFact.isBlank())
                || (actualSentSpeechAct != null && !actualSentSpeechAct.isBlank())) {
            log.info("[AgentBrain] Retained reflection delta for page={} sender={}: verifiedFacts={}/{}, careNotes={}/{}, actualSentSpeechAct=\"{}\", hypothesis=\"{}\"",
                    pageId,
                    senderId,
                    mem.verifiedFactsSnapshot().size(),
                    MAX_VERIFIED_FACTS_PER_CUSTOMER,
                    effectiveArtifacts.size(),
                    MAX_CARE_NOTES_PER_CUSTOMER,
                    mem.lastActualSentSpeechActSnapshot(),
                    mem.workingHypothesisSnapshot());
        }

        if (client == null || userMessage == null || userMessage.isBlank()) {
            return;
        }
        StringBuilder enrichedReplySb = new StringBuilder(assistantReply != null ? assistantReply : "");
        if (!effectiveArtifacts.isEmpty()) {
            enrichedReplySb.append("\n[CUSTOMER_CARE_NOTES: ").append(effectiveArtifacts).append("]");
        }
        if ((observedFact != null && !observedFact.isBlank())
                || (careNoteDelta != null && !careNoteDelta.isBlank())
                || (workingHypothesis != null && !workingHypothesis.isBlank())
                || (actualSentSpeechAct != null && !actualSentSpeechAct.isBlank())) {
            enrichedReplySb.append("\n[BRAIN_REFLECTION: observed_fact=")
                    .append(observedFact != null ? observedFact.strip() : "")
                    .append(" | care_note_delta=")
                    .append(careNoteDelta != null ? careNoteDelta.strip() : "")
                    .append(" | working_hypothesis=")
                    .append(workingHypothesis != null ? workingHypothesis.strip() : "")
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
            Collection<String> confirmedScopes
    ) {
        mem.careNoteArtifacts.clear();
        Set<String> safeScopes = confirmedScopes != null && !confirmedScopes.isEmpty()
                ? Collections.unmodifiableSet(new LinkedHashSet<>(confirmedScopes))
                : Set.of("general_care");
        int idx = 1;
        for (String note : mem.careNotes) {
            mem.careNoteArtifacts.add(new ConversationState.CustomerCareNoteArtifact(
                    tenantId,
                    conversationId,
                    "turn-" + idx++,
                    safeScopes,
                    "chuyên viên chăm sóc",
                    note,
                    java.time.Instant.now()
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

    /**
     * Đồng bộ kỹ năng chủ đạo do {@code agent-brain} chọn với danh sách {@code activeSkills} của Page (tối đa 1 skill).
     */
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

        // Tìm trong working hoặc candidateSkills
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

    /**
     * Bổ sung các sự thật đã kiểm chứng ({@code active_facts}) và rào cản ({@code objections})
     * từ bộ nhớ {@code agent-brain} vào {@code CustomerInsightDto}.
     */
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
        String localSpeechAct = (pageId != null || senderId != null)
                ? getRecalledActualSentSpeechAct(pageId, senderId)
                : "";

        if (currentInsight == null && brainAdvice == null
                && localFacts.isEmpty() && localCareNotes.isEmpty() && localHypothesis.isEmpty() && localSpeechAct.isEmpty()) {
            return null;
        }

        CustomerInsightDto base = currentInsight != null
                ? currentInsight
                : new CustomerInsightDto(
                        brainAdvice != null && brainAdvice.buyerArchetype() != null ? brainAdvice.buyerArchetype() : "VALUE_CONSCIOUS",
                        "Khách hàng cân nhắc kỹ",
                        55,
                        "Ấm",
                        "mình",
                        List.of(),
                        List.of(),
                        ""
                );

        List<String> mergedPains = new ArrayList<>(base.extractedPains());
        if (brainAdvice != null && brainAdvice.activeFacts() != null) {
            for (AgentBrainClient.MemoryFactDto f : brainAdvice.activeFacts()) {
                if (f != null && f.isActive() && f.factKey() != null && f.factValue() != null) {
                    String factSummary = f.factKey() + "=" + f.factValue();
                    addBoundedAndDeduplicated(mergedPains, factSummary, MAX_VERIFIED_FACTS_PER_CUSTOMER);
                }
            }
        }
        for (String lf : localFacts) {
            addBoundedAndDeduplicated(mergedPains, lf, MAX_VERIFIED_FACTS_PER_CUSTOMER);
        }

        List<String> mergedCareNotes = new ArrayList<>(base.careNotes());
        for (String cn : localCareNotes) {
            addBoundedAndDeduplicated(mergedCareNotes, cn, MAX_CARE_NOTES_PER_CUSTOMER);
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
        String resolvedHypothesis = !localHypothesis.isBlank()
                ? localHypothesis
                : base.workingHypothesis();
        String resolvedSpeechAct = !localSpeechAct.isBlank()
                ? localSpeechAct
                : base.lastActualSentSpeechAct();

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
                resolvedSpeechAct
        );
    }
}
