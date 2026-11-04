package com.example.docmind.service.bot;

import com.example.docmind.config.ChatProperties;
import com.example.docmind.dto.PagePersonaConfig;
import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.dto.bot.FanpageBotResponse;
import com.example.docmind.entity.DocumentEntity;
import com.example.docmind.entity.KnowledgePageEntity;
import com.example.docmind.repository.DocumentRepository;
import com.example.docmind.repository.KnowledgePageRepository;
import com.example.docmind.repository.RetrievedChunk;
import com.example.docmind.service.bot.AdaptiveConversationIntelligence.AdaptiveCognitiveReflection;
import com.example.docmind.service.bot.AdaptiveConversationIntelligence.BookingReadiness;
import com.example.docmind.service.bot.AdaptiveConversationIntelligence.CustomerUnderstandingDraft2Snapshot;
import com.example.docmind.service.bot.AdaptiveConversationIntelligence.FactProvenance;
import com.example.docmind.service.bot.AdaptiveConversationIntelligence.FactValidity;
import com.example.docmind.service.bot.AdaptiveConversationIntelligence.InteractionPermission;
import com.example.docmind.service.bot.AdaptiveConversationIntelligence.OperationalStateSnapshot;
import com.example.docmind.service.bot.AdaptiveConversationIntelligence.PreviousStrategyRecord;
import com.example.docmind.service.bot.AdaptiveConversationIntelligence.ThreeStreamTurnInput;
import com.example.docmind.service.bot.AdaptiveConversationIntelligence.VersionedCustomerFact;
import com.example.docmind.service.bot.AdaptiveConversationIntelligence.WorkingHypothesisRecord;
import com.example.docmind.service.bot.CleanAgentCatalogAndPromptBuilder.HistoryBudgetAssemblyReport;
import com.example.docmind.service.bot.CleanAgentCatalogAndPromptBuilder.HistoryTokenBudgetPolicy;
import com.example.docmind.service.bot.CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution;
import com.example.docmind.service.llm.LlmClient;
import com.example.docmind.service.llm.ReasoningBudget;
import com.example.docmind.service.retrieval.PromptBuilder;
import com.example.docmind.service.retrieval.ReciprocalRankFusion;
import com.example.docmind.service.retrieval.RetrievalService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bộ kiểm thử nghiệm thu toàn diện cho P3.3 — Session Context &amp; Evidence Memory (TC01–TC10).
 *
 * <ul>
 *   <li>TC01: Hội thoại ngắn (6–10 lượt), đầy đủ ngữ cảnh, nhớ nhu cầu + chi nhánh + ngân sách, prompt chứa trọn lịch sử</li>
 *   <li>TC02: Hội thoại dài &gt; 16 lượt (20 lượt) trong ngân sách token, giữ 100% nguyên văn từ [T1] đến [T20]</li>
 *   <li>TC03: Hội thoại dài vượt ngân sách token, tạo {@code <OLDER_HISTORY_EVIDENCE_SUMMARY>} + giữ nguyên văn các lượt mới nhất</li>
 *   <li>TC04: Khách thay đổi quyết định giữa chừng (chi nhánh / gói / ngân sách / giờ) -&gt; cũ SUPERSEDED, mới CURRENT + CUSTOMER_CORRECTED</li>
 *   <li>TC05: Bot giới thiệu gói != khách đã chọn gói (nằm ở lastOfferedPackageIds / interestedPackageId, KHÔNG ở selectedPackageId)</li>
 *   <li>TC06: Phản ứng chê đắt ("Giá 399k cao quá") != ngân sách của khách (ghi nhận PRICE_OBJECTION, KHÔNG lưu customer_budget = 399k)</li>
 *   <li>TC07: Khách tạm hoãn quyết định ("Để tôi xem đã") -&gt; CUSTOMER_DEFERRAL / PAUSED_BY_CUSTOMER, giữ nguyên ngữ cảnh</li>
 *   <li>TC08: Tóm tắt lịch sử cũ giữ đúng nguồn gốc thông tin (Provenance: CUSTOMER_CONFIRMED, CUSTOMER_CORRECTED, BOT_PROPOSED, INFERRED)</li>
 *   <li>TC09: Hành trình tư vấn 14 lượt thực tế từ đầu đến đặt lịch giả lập qua MockBookingGateway (CONFIRMED)</li>
 *   <li>TC10: Không phá vỡ P3.1.1 (Outbound Delivery) và P3.2 (MockBookingGateway + BookingStateMachine + Referee/Sanitizer)</li>
 * </ul>
 */
class P33SessionContextAndEvidenceMemoryTest {

    private static final UUID PAGE_ID = UUID.fromString("b23cd382-4f7a-4829-8c54-1d410da14bf8");
    private static final UUID DOC_ID = UUID.fromString("33333333-4444-5555-6666-777777777777");
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private static final String SPA_CATALOG_TABLE = """
            | Mã gói | Tên gói | Thời lượng | Giá | Vùng chăm sóc | Vai trò thương mại |
            | --- | --- | --- | --- | --- | --- |
            | NS01 | Gội đầu dưỡng sinh chuyên sâu Cổ Vai Gáy | 65 phút | 399k | đầu, cổ, vai, gáy | gói chính |
            | CB01 | Gội đầu dưỡng sinh Thư Giãn Cơ Bản | 45 phút | 199k | đầu, cổ, vai | gói tiết kiệm |
            | MS01 | Massage Body Đá Nóng Toàn Thân | 75 phút | 450k | lưng, vai, gáy, toàn thân | gói chuyên sâu |
            """;

    private MockBookingGateway mockGateway;
    private AgentBrainService agentBrainService;
    private CleanAgentOrchestratorService orchestrator;
    private FanpageBotService botService;
    private LlmClient llmClient;
    private PromptBuilder.PagePersona nangSenPersona;
    private List<ReciprocalRankFusion.FusedChunk> catalogChunks;
    private List<PackageCatalogService.PackageSummary> catalogPackages;
    private List<PackageCatalogService.PackageSummary> primaryPackages;
    private List<PackageCatalogService.PackageSummary> economicalPackages;

    @BeforeEach
    void setUp() {
        CleanAgentCatalogAndPromptBuilder.resetConfiguredHistoryTokenBudgetPolicy();
        SentBubbleLedger.useStore(new SentBubbleLedger.InMemoryStore());
        SentBubbleLedger.clear();
        SenderEpochRegistry.clearAll();
        ConversationState.clearSessionCache();

        mockGateway = new MockBookingGateway();
        agentBrainService = new AgentBrainService("LLM_COGNITIVE_V2");

        catalogChunks = List.of(
                new ReciprocalRankFusion.FusedChunk(
                        new RetrievedChunk(
                                UUID.randomUUID(),
                                DOC_ID,
                                List.of("Bảng giá dịch vụ Nàng Sen Spa"),
                                List.of(UUID.randomUUID()),
                                1,
                                new double[]{0, 0, 100, 100},
                                SPA_CATALOG_TABLE,
                                0.98
                        ),
                        0.98
                )
        );
        catalogPackages = PackageCatalogService.extractCatalog(catalogChunks, List.of());
        primaryPackages = catalogPackages.stream()
                .filter(p -> !p.isAddon() && p.commercialRole() != PackageCatalogService.CommercialRole.ECONOMICAL_OPTION)
                .toList();
        economicalPackages = catalogPackages.stream()
                .filter(p -> !p.isAddon() && p.commercialRole() == PackageCatalogService.CommercialRole.ECONOMICAL_OPTION)
                .toList();

        RetrievalService retrievalService = Mockito.mock(RetrievalService.class);
        Mockito.when(retrievalService.retrieveWithRules(
                        Mockito.any(), Mockito.anyString(), Mockito.anyInt(), Mockito.anyList(), Mockito.any()))
                .thenReturn(new RetrievalService.RetrievalResult(catalogChunks, List.of()));

        PagePersonaConfig personaCfg = PagePersonaConfig.fromJson("""
                {
                  "industry_code": "BEAUTY_SPA",
                  "bot_name": "Lễ tân Nàng Sen",
                  "self_pronoun": "em",
                  "default_customer_pronoun": "chị",
                  "scope_taxonomy": ["đầu", "cổ", "vai", "gáy", "lưng", "toàn thân"]
                }
                """);
        nangSenPersona = new PromptBuilder.PagePersona(
                "Nàng Sen Spa", "BEAUTY_SPA", "Khách hàng spa", "Ấm áp", personaCfg);

        KnowledgePageRepository pageRepo = Mockito.mock(KnowledgePageRepository.class);
        KnowledgePageEntity pageEntity = new KnowledgePageEntity();
        pageEntity.setId(PAGE_ID);
        pageEntity.setName("Nàng Sen Spa");
        pageEntity.setIndustry("BEAUTY_SPA");
        pageEntity.setPersonaConfig(personaCfg);
        Mockito.when(pageRepo.findById(PAGE_ID)).thenReturn(Optional.of(pageEntity));

        llmClient = Mockito.mock(LlmClient.class);
        Mockito.when(llmClient.name()).thenReturn("mock-aci-v2-p33");

        DocumentRepository docRepo = Mockito.mock(DocumentRepository.class);
        DocumentEntity readyDoc = new DocumentEntity();
        readyDoc.setId(DOC_ID);
        readyDoc.setPageId(PAGE_ID);
        readyDoc.setStatus("READY");
        readyDoc.setActive(true);
        Mockito.when(docRepo.findByPageIdOrderByCreatedAtDesc(PAGE_ID)).thenReturn(List.of(readyDoc));

        botService = new FanpageBotService(
                retrievalService,
                llmClient,
                new ChatProperties(),
                docRepo,
                pageRepo,
                null,
                null,
                null,
                null,
                null
        );
        botService.setAgentBrainService(agentBrainService);
        orchestrator = botService.getCleanAgentOrchestratorService();
        orchestrator.setBookingGateway(mockGateway);
        orchestrator.setBookingGatewayMode(BookingGateway.Mode.MOCK);
    }

    @AfterEach
    void tearDown() {
        CleanAgentCatalogAndPromptBuilder.resetConfiguredHistoryTokenBudgetPolicy();
        mockGateway.reset();
        SentBubbleLedger.clear();
        SenderEpochRegistry.clearAll();
        ConversationState.clearSessionCache();
    }

    private FanpageBotRequest buildBotRequest(
            String senderId,
            String customerName,
            String message,
            List<FanpageBotRequest.ConversationTurn> history
    ) {
        return new FanpageBotRequest(
                PAGE_ID.toString(),
                senderId,
                customerName,
                message,
                null,
                history != null ? history : List.of(),
                List.of(DOC_ID),
                5,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "conv-" + senderId,
                null
        );
    }

    private AdaptiveCognitiveReflection parseAndReconcile(
            String rawJson,
            String currentUtterance,
            List<FanpageBotRequest.ConversationTurn> history
    ) {
        String historicalUserEvidence = history == null ? "" : history.stream()
                .filter(t -> t != null && t.content() != null && !"assistant".equalsIgnoreCase(t.role()))
                .map(FanpageBotRequest.ConversationTurn::content)
                .reduce("", (a, b) -> a.isEmpty() ? b : (a + "\n" + b));
        CleanAgentOutputRefereeAndSanitizer.StructuredLlmOutput parsed =
                CleanAgentOutputRefereeAndSanitizer.parseStructuredLlmJsonOutput(rawJson, 0);
        AdaptiveCognitiveReflection rawRef = parsed != null && parsed.aciReflection() != null
                ? parsed.aciReflection()
                : AdaptiveCognitiveReflection.empty();
        return rawRef.reconcileWithCurrentUtterance(currentUtterance, historicalUserEvidence);
    }

    @Test
    @DisplayName("TC01 — Hội thoại ngắn (6–10 lượt), đầy đủ ngữ cảnh, nhớ nhu cầu + chi nhánh + ngân sách, prompt chứa trọn lịch sử")
    void tc01_shortConversationFullContextAndMemory() {
        String psid = "psid-p33-tc01";
        List<FanpageBotRequest.ConversationTurn> history = new ArrayList<>();

        String turn1User = "Chào shop, chị bị mỏi cổ vai gáy nhiều và muốn tìm cơ sở ở Quận 1 tầm 250k.";
        AdaptiveCognitiveReflection reconciled1 = parseAndReconcile("""
                {
                  "brain_reflection": {
                    "conversational_context": {"current_topic": "Tư vấn mỏi cổ vai gáy ở Quận 1 tầm 250k"},
                    "customer_understanding": {
                      "turn_intent": "SYMPTOM_AND_BUDGET_SHARING",
                      "conversation_goal": "FIND_RELIEF",
                      "interaction_permission": "OPEN",
                      "buying_signal": "WARM",
                      "cost_concern_expressed": false,
                      "booking_readiness": "EXPLORING",
                      "self_reported_symptoms": [
                        {"value": "mỏi cổ vai gáy", "validity": "CURRENT"}
                      ],
                      "confirmed_facts": [
                        {"key": "preferred_branch", "value": "Quận 1", "validity": "CURRENT"},
                        {"key": "customer_budget", "value": "tầm 250k", "validity": "CURRENT"}
                      ]
                    },
                    "priority": {"primary": "CONSULTATIVE_RECOMMENDATION", "secondary": []},
                    "proposed_booking_patch": {}
                  },
                  "bubbles": ["Dạ ở Quận 1 với ngân sách tầm 250k bên em có gói CB01 199k (45 phút) rất hợp ạ."]
                }
                """, turn1User, history);

        ConversationState.StructuredBookingState booking1 = AdaptiveConversationIntelligence.applyAuthoritativeBookingPatch(
                ConversationState.StructuredBookingState.none(),
                reconciled1.proposedBookingPatch(),
                turn1User,
                "",
                catalogPackages,
                catalogPackages,
                VN_ZONE,
                reconciled1.customerUnderstanding().bookingReadiness(),
                reconciled1.priority().primary()
        );
        agentBrainService.applyAciTurnMemoryUpdates(
                PAGE_ID, psid, "T1", turn1User, List.of(), reconciled1, booking1, null, false, null, "Direct Answer");

        history.add(new FanpageBotRequest.ConversationTurn("user", turn1User));
        history.add(new FanpageBotRequest.ConversationTurn("assistant",
                "Dạ em chào chị ạ! Ở Quận 1 với ngân sách tầm 250k và tình trạng mỏi cổ vai gáy, bên em có Gói Gội đầu dưỡng sinh Thư Giãn Cơ Bản 199k (45 phút) rất hợp với mình ạ."));
        history.add(new FanpageBotRequest.ConversationTurn("user", "Gói 199k đó có bấm huyệt vùng gáy không em?"));
        history.add(new FanpageBotRequest.ConversationTurn("assistant",
                "Dạ gói 199k có massage nhẹ vùng đầu - cổ - vai ạ. Nếu chị muốn ấn huyệt chuyên sâu vùng gáy hơn thì có thể cân nhắc thêm ạ."));
        history.add(new FanpageBotRequest.ConversationTurn("user", "Thời gian làm gói 199k là bao lâu vậy em?"));
        history.add(new FanpageBotRequest.ConversationTurn("assistant", "Dạ gói 199k thời lượng 45 phút chị nhé."));
        history.add(new FanpageBotRequest.ConversationTurn("user", "Chị đi tầm chiều tối có cần đặt trước không?"));
        history.add(new FanpageBotRequest.ConversationTurn("assistant",
                "Dạ khung chiều tối bên em khá đông nên chị báo trước giúp em tầm 30 phút để em giữ giường chu đáo cho chị nha."));

        CustomerUnderstandingDraft2Snapshot draft2 = agentBrainService.getCustomerUnderstandingDraft2Snapshot(PAGE_ID, psid);

        // Kiểm chứng bộ nhớ giữ đúng nhu cầu, chi nhánh Quận 1 và ngân sách tầm 250k với CUSTOMER_CONFIRMED
        assertThat(draft2.consultativeProfile().selfReportedSymptoms())
                .anySatisfy(sym -> {
                    assertThat(sym.value()).containsIgnoringCase("vai gáy");
                    assertThat(sym.validity()).isEqualTo(FactValidity.CURRENT);
                    assertThat(sym.provenance()).isEqualTo(FactProvenance.CUSTOMER_CONFIRMED);
                });
        assertThat(draft2.consultativeProfile().confirmedFacts())
                .anySatisfy(fact -> {
                    assertThat(fact.key()).isEqualTo("preferred_branch");
                    assertThat(fact.value()).containsIgnoringCase("Quận 1");
                    assertThat(fact.validity()).isEqualTo(FactValidity.CURRENT);
                    assertThat(fact.provenance()).isEqualTo(FactProvenance.CUSTOMER_CONFIRMED);
                })
                .anySatisfy(fact -> {
                    assertThat(fact.key()).isEqualTo("customer_budget");
                    assertThat(fact.value()).containsIgnoringCase("250k");
                    assertThat(fact.validity()).isEqualTo(FactValidity.CURRENT);
                    assertThat(fact.provenance()).isEqualTo(FactProvenance.CUSTOMER_CONFIRMED);
                });

        // Kiểm chứng prompt chứa toàn bộ 8 lượt [T1]..[T8], không bị cắt và không phải tóm tắt
        FanpageBotRequest req = buildBotRequest(psid, "Chị Lan", "Vậy tư vấn kỹ hơn giúp chị nhé", history);
        ThreeStreamTurnInput streams = AdaptiveConversationIntelligence.buildThreeStreamInput(
                req, catalogPackages, nangSenPersona.resolvedConfig(), VN_ZONE);

        String prompt = CleanAgentCatalogAndPromptBuilder.buildAciSinglePassPrompt(
                nangSenPersona,
                "chị",
                "chị",
                "chị",
                streams,
                primaryPackages,
                economicalPackages,
                QuotedReplyResolution.empty(),
                List.of(),
                WorkingHypothesisRecord.initialEmpty(),
                List.of(),
                booking1,
                OperationalStateSnapshot.initial(),
                PreviousStrategyRecord.initialNone(),
                List.of(),
                null,
                false,
                catalogChunks,
                history,
                List.of(),
                List.of(),
                draft2,
                null
        );

        HistoryBudgetAssemblyReport report = CleanAgentCatalogAndPromptBuilder.getLastHistoryBudgetReport();
        assertThat(report).isNotNull();
        assertThat(report.totalTranscriptTurns()).isEqualTo(8);
        assertThat(report.usedOlderHistorySummary()).isFalse();
        assertThat(report.summarizedOlderTurnCount()).isZero();
        assertThat(report.verbatimIncludedTurnCount()).isEqualTo(8);
        assertThat(prompt)
                .contains("[T1] Khách: Chào shop, chị bị mỏi cổ vai gáy nhiều và muốn tìm cơ sở ở Quận 1 tầm 250k.")
                .contains("[T8] Bot: Dạ khung chiều tối bên em khá đông")
                .doesNotContain("<OLDER_HISTORY_EVIDENCE_SUMMARY");
    }

    @Test
    @DisplayName("TC02 — Hội thoại dài > 16 lượt (20 lượt) trong ngân sách token: giữ nguyên văn 100% từ [T1] đến [T20], không bị cắt ở 8/10/16 lượt")
    void tc02_twentyTurnsWithinTokenBudgetIncludesFullTranscriptVerbatim() {
        String psid = "psid-p33-tc02";
        List<FanpageBotRequest.ConversationTurn> history = new ArrayList<>();

        history.add(new FanpageBotRequest.ConversationTurn("user",
                "Lượt 1: Chị tên Minh Anh, bị đau mỏi cổ vai gáy và tê nhẹ bả vai phải, muốn làm ở Quận 1 ngân sách 250k."));
        history.add(new FanpageBotRequest.ConversationTurn("assistant",
                "Lượt 2: Dạ em chào chị Minh Anh, bên em ghi nhận chị bị mỏi cổ vai gáy, ưu tiên Quận 1 và ngân sách 250k ạ."));

        for (int turn = 3; turn <= 20; turn++) {
            if (turn % 2 == 1) {
                history.add(new FanpageBotRequest.ConversationTurn("user",
                        "Lượt " + turn + ": Chị hỏi thêm thông tin ngắn gọn số " + turn + " về dịch vụ spa."));
            } else {
                history.add(new FanpageBotRequest.ConversationTurn("assistant",
                        "Lượt " + turn + ": Dạ em xin giải đáp ngắn gọn câu hỏi số " + turn + " cho chị Minh Anh ạ."));
            }
        }

        assertThat(history).hasSize(20);

        FanpageBotRequest req = buildBotRequest(
                psid, "Chị Minh Anh", "Chị cần nhắc lại gói phù hợp với thông tin ở lượt 1 nhé", history);
        ThreeStreamTurnInput streams = AdaptiveConversationIntelligence.buildThreeStreamInput(
                req, catalogPackages, nangSenPersona.resolvedConfig(), VN_ZONE);

        // 1. Kiểm tra buildAciSinglePassPrompt (trước đây cắt cứng history.size() - 10)
        String aciPrompt = CleanAgentCatalogAndPromptBuilder.buildAciSinglePassPrompt(
                nangSenPersona,
                "chị",
                "chị",
                "chị",
                streams,
                primaryPackages,
                economicalPackages,
                QuotedReplyResolution.empty(),
                List.of(),
                WorkingHypothesisRecord.initialEmpty(),
                List.of(),
                ConversationState.StructuredBookingState.none(),
                OperationalStateSnapshot.initial(),
                PreviousStrategyRecord.initialNone(),
                List.of(),
                null,
                false,
                catalogChunks,
                history,
                List.of(),
                List.of(),
                CustomerUnderstandingDraft2Snapshot.initial(),
                null
        );
        HistoryBudgetAssemblyReport aciReport = CleanAgentCatalogAndPromptBuilder.getLastHistoryBudgetReport();
        assertThat(aciReport.totalTranscriptTurns()).isEqualTo(20);
        assertThat(aciReport.usedOlderHistorySummary()).isFalse();
        assertThat(aciReport.summarizedOlderTurnCount()).isZero();
        assertThat(aciReport.verbatimIncludedTurnCount()).isEqualTo(20);
        assertThat(aciPrompt)
                .contains("[T1] Khách: Lượt 1: Chị tên Minh Anh, bị đau mỏi cổ vai gáy và tê nhẹ bả vai phải, muốn làm ở Quận 1 ngân sách 250k.")
                .contains("[T2] Bot: Lượt 2: Dạ em chào chị Minh Anh")
                .contains("[T10] Bot: Lượt 10:")
                .contains("[T11] Khách: Lượt 11:")
                .contains("[T20] Bot: Lượt 20:");

        // 2. Kiểm tra FanpageBotPromptBuilder.appendTokenBudgetedFanpageHistory (trước đây cắt cứng maxRecentTurns = 16)
        StringBuilder fanpageHistorySb = new StringBuilder();
        CleanAgentCatalogAndPromptBuilder.appendTokenBudgetedFanpageHistory(
                fanpageHistorySb, history, null, "Lễ tân", "Chị");
        assertThat(fanpageHistorySb.toString())
                .contains("Chị: Lượt 1: Chị tên Minh Anh, bị đau mỏi cổ vai gáy")
                .contains("Lễ tân: Lượt 2: Dạ em chào chị Minh Anh")
                .contains("Lễ tân: Lượt 20:");
    }

    @Test
    @DisplayName("TC03 — Hội thoại dài vượt ngân sách token: tóm tắt các lượt cũ trong <OLDER_HISTORY_EVIDENCE_SUMMARY> và giữ nguyên văn các lượt mới nhất theo ranh giới lượt")
    void tc03_longConversationExceedingTokenBudgetSummarizesOlderTurnsAndKeepsNewestTurnsVerbatim() {
        String psid = "psid-p33-tc03";
        // Thiết lập ngân sách token chặt để kiểm chứng cơ chế vượt ngân sách một cách tất định
        CleanAgentCatalogAndPromptBuilder.setConfiguredHistoryTokenBudgetPolicy(
                new HistoryTokenBudgetPolicy(6950, 1200, 256));

        List<FanpageBotRequest.ConversationTurn> history = new ArrayList<>();
        String turn1User = "Chào em, chị bị mỏi cổ vai gáy và đau đầu nhẹ 1 tuần nay, muốn tìm gói ở chi nhánh Quận 1.";
        AdaptiveCognitiveReflection d1 = parseAndReconcile("""
                {
                  "brain_reflection": {
                    "customer_understanding": {
                      "turn_intent": "SYMPTOM_SHARING",
                      "self_reported_symptoms": [
                        {"value": "mỏi cổ vai gáy", "validity": "CURRENT"},
                        {"value": "đau đầu nhẹ", "validity": "CURRENT"}
                      ],
                      "confirmed_facts": [
                        {"key": "preferred_branch", "value": "Quận 1", "validity": "CURRENT"}
                      ]
                    }
                  },
                  "bubbles": ["Dạ ở Quận 1 bên em có gói NS01 399k ạ."]
                }
                """, turn1User, history);
        ConversationState.StructuredBookingState b1 = new ConversationState.StructuredBookingState(
                null, null, null, null, null, VN_ZONE.getId(),
                ConversationState.BookingStatus.NONE, List.of("NS01"));
        agentBrainService.applyAciTurnMemoryUpdates(
                PAGE_ID, psid, "T1", turn1User, List.of(), d1, b1, null, false, null, "Direct Answer");
        history.add(new FanpageBotRequest.ConversationTurn("user", turn1User));
        history.add(new FanpageBotRequest.ConversationTurn("assistant",
                "Dạ ở Quận 1 bên em có gói NS01 Gội đầu dưỡng sinh chuyên sâu Cổ Vai Gáy 399k (65 phút) giúp giải tỏa mỏi cổ vai gáy và đau đầu rất tốt ạ."));

        String turn3User = "Giá 399k cao quá em ơi, chị chỉ định làm tầm 250k đổ lại thôi, có gói nào nhẹ hơn không?";
        AdaptiveCognitiveReflection d3 = parseAndReconcile("""
                {
                  "brain_reflection": {
                    "customer_understanding": {
                      "turn_intent": "PRICE_OBJECTION",
                      "cost_concern_expressed": true,
                      "confirmed_facts": [
                        {"key": "customer_budget", "value": "tầm 250k", "validity": "CURRENT"}
                      ]
                    }
                  },
                  "bubbles": ["Dạ vậy chị tham khảo gói CB01 199k nha."]
                }
                """, turn3User, history);
        ConversationState.StructuredBookingState b3 = new ConversationState.StructuredBookingState(
                null, null, null, null, null, VN_ZONE.getId(),
                ConversationState.BookingStatus.NONE, List.of("NS01", "CB01"));
        agentBrainService.applyAciTurnMemoryUpdates(
                PAGE_ID, psid, "T3", turn3User, List.of(), d3, b3, null, false, null, "Handle Objection");
        history.add(new FanpageBotRequest.ConversationTurn("user", turn3User));
        history.add(new FanpageBotRequest.ConversationTurn("assistant",
                "Dạ vậy chị tham khảo gói CB01 Gội đầu dưỡng sinh Thư Giãn Cơ Bản 199k (45 phút) nha, rất vừa ngân sách tầm 250k của chị ạ."));

        // Thêm 24 lượt trao đổi dài để tổng token vượt ngân sách 3400 tokens
        for (int i = 5; i <= 28; i++) {
            if (i % 2 == 1) {
                history.add(new FanpageBotRequest.ConversationTurn("user",
                        "Câu hỏi chi tiết lượt " + i + ": Chị muốn hỏi thêm về quy trình gội thảo dược, tinh dầu sử dụng có dịu nhẹ cho da đầu nhạy cảm không và phòng ốc có yên tĩnh không em?"));
            } else {
                history.add(new FanpageBotRequest.ConversationTurn("assistant",
                        "Trả lời chi tiết lượt " + i + ": Dạ bên em nấu nước bồ kết thảo dược tươi mỗi ngày, tinh dầu thiên nhiên lành tính và phòng chăm sóc riêng tư yên tĩnh tuyệt đối cho chị nghỉ ngơi ạ."));
            }
        }

        CustomerUnderstandingDraft2Snapshot draft2 = agentBrainService.getCustomerUnderstandingDraft2Snapshot(PAGE_ID, psid);
        FanpageBotRequest req = buildBotRequest(
                psid, "Chị Lan", "Chị muốn hỏi lại gói 199k ở chi nhánh của chị có còn chỗ tối nay không?", history);
        ThreeStreamTurnInput streams = AdaptiveConversationIntelligence.buildThreeStreamInput(
                req, catalogPackages, nangSenPersona.resolvedConfig(), VN_ZONE);

        String prompt = CleanAgentCatalogAndPromptBuilder.buildAciSinglePassPrompt(
                nangSenPersona,
                "chị",
                "chị",
                "chị",
                streams,
                primaryPackages,
                economicalPackages,
                QuotedReplyResolution.empty(),
                List.of(),
                WorkingHypothesisRecord.initialEmpty(),
                List.of(),
                b3,
                OperationalStateSnapshot.initial(),
                PreviousStrategyRecord.initialNone(),
                List.of(),
                null,
                false,
                catalogChunks,
                history,
                List.of(),
                List.of(),
                draft2,
                null
        );

        HistoryBudgetAssemblyReport report = CleanAgentCatalogAndPromptBuilder.getLastHistoryBudgetReport();
        assertThat(report).isNotNull();
        assertThat(report.usedOlderHistorySummary()).isTrue();
        assertThat(report.summarizedOlderTurnCount()).isGreaterThan(0);
        assertThat(report.verbatimIncludedTurnCount()).isGreaterThan(0);
        assertThat(report.summarizedOlderTurnCount() + report.verbatimIncludedTurnCount()).isEqualTo(history.size());
        assertThat(report.totalPromptTokens())
                .isLessThanOrEqualTo(report.contextWindowTokens() - report.outputTokenReserve());

        // Kiểm chứng cấu trúc <OLDER_HISTORY_EVIDENCE_SUMMARY> xuất hiện TRƯỚC các lượt nguyên văn mới nhất
        int summaryIdx = prompt.indexOf("<OLDER_HISTORY_EVIDENCE_SUMMARY");
        int lastTurnIdx = prompt.indexOf("[T28] Bot: Trả lời chi tiết lượt 28:");
        assertThat(summaryIdx).isGreaterThanOrEqualTo(0);
        assertThat(lastTurnIdx).isGreaterThan(summaryIdx);

        // Kiểm chứng lượt mới nhất được giữ nguyên văn trọn vẹn, không bị cắt giữa câu
        assertThat(prompt).contains(
                "[T28] Bot: Trả lời chi tiết lượt 28: Dạ bên em nấu nước bồ kết thảo dược tươi mỗi ngày, tinh dầu thiên nhiên lành tính và phòng chăm sóc riêng tư yên tĩnh tuyệt đối cho chị nghỉ ngơi ạ.");

        // Kiểm chứng bản tóm tắt giữ đủ 9 nhóm thông tin bắt buộc + dữ kiện Quận 1, 250k, PRICE_OBJECTION
        assertThat(prompt)
                .contains("- customer_needs_and_symptoms [provenance=CUSTOMER_CONFIRMED]:")
                .contains("- package_interest_vs_selection:")
                .contains("- customer_budget_and_price_sensitivity:")
                .contains("- branch_preferences_and_exclusions:")
                .contains("- bot_introduced_packages [provenance=BOT_PROPOSED, unconfirmed_by_customer]:")
                .contains("- objections_concerns_and_unresolved_questions:")
                .contains("- customer_corrections_and_revisions [provenance=CUSTOMER_CORRECTED]:")
                .contains("- booking_intent_and_state [provenance=SYSTEM_DERIVED]:")
                .contains("- customer_confirmation_status:")
                .containsIgnoringCase("Quận 1")
                .containsIgnoringCase("250k");
    }

    @Test
    @DisplayName("TC04 — Khách thay đổi quyết định giữa chừng: thông tin cũ chuyển sang SUPERSEDED, thông tin mới là CURRENT với CUSTOMER_CORRECTED")
    void tc04_customerChangesDecisionMidConversationSupersedesOldFactsWithCustomerCorrectedProvenance() {
        String psid = "psid-p33-tc04";
        List<FanpageBotRequest.ConversationTurn> history = new ArrayList<>();

        // Lượt 1: Khách chọn Quận 1, ngân sách tầm 300k, hỏi massage toàn thân
        String u1 = "Chị muốn hỏi gói massage toàn thân ở Quận 1, ngân sách tầm 300k.";
        AdaptiveCognitiveReflection rec1 = parseAndReconcile("""
                {
                  "brain_reflection": {
                    "customer_understanding": {
                      "turn_intent": "SERVICE_INQUIRY",
                      "confirmed_facts": [
                        {"key": "preferred_branch", "value": "Quận 1", "validity": "CURRENT"},
                        {"key": "customer_budget", "value": "tầm 300k", "validity": "CURRENT"},
                        {"key": "preferred_package", "value": "massage toàn thân", "validity": "CURRENT"}
                      ]
                    }
                  },
                  "bubbles": ["Dạ bên em có cơ sở Quận 1 ạ."]
                }
                """, u1, history);
        agentBrainService.applyAciTurnMemoryUpdates(
                PAGE_ID, psid, "T1", u1, List.of(), rec1,
                ConversationState.StructuredBookingState.none(), null, false, null, "Direct Answer");
        history.add(new FanpageBotRequest.ConversationTurn("user", u1));
        history.add(new FanpageBotRequest.ConversationTurn("assistant", "Dạ bên em có cơ sở Quận 1 ạ."));

        // Lượt 2: Khách đổi ý sang Quận 3, đổi sang gội đầu dưỡng sinh CB01, đổi ngân sách xuống dưới 250k
        String u2 = "À thôi đổi giúp chị sang Quận 3 nhé, chị chuyển sang gói gội đầu dưỡng sinh CB01 dưới 250k thay vì massage toàn thân.";
        AdaptiveCognitiveReflection rec2 = parseAndReconcile("""
                {
                  "brain_reflection": {
                    "customer_understanding": {
                      "turn_intent": "PREFERENCE_REVISION",
                      "confirmed_facts": [
                        {"key": "preferred_branch", "value": "Quận 3", "validity": "CURRENT", "operation": "REVISE"},
                        {"key": "customer_budget", "value": "dưới 250k", "validity": "CURRENT", "operation": "REVISE"},
                        {"key": "preferred_package", "value": "gội đầu dưỡng sinh CB01", "validity": "CURRENT", "operation": "REVISE"}
                      ]
                    }
                  },
                  "bubbles": ["Dạ em đã đổi sang Quận 3 và gói gội đầu dưỡng sinh CB01 dưới 250k cho chị ạ."]
                }
                """, u2, history);
        agentBrainService.applyAciTurnMemoryUpdates(
                PAGE_ID, psid, "T3", u2, List.of(), rec2,
                ConversationState.StructuredBookingState.none(), null, false, null, "Direct Answer");

        CustomerUnderstandingDraft2Snapshot draft2 = agentBrainService.getCustomerUnderstandingDraft2Snapshot(PAGE_ID, psid);
        List<VersionedCustomerFact> facts = draft2.consultativeProfile().confirmedFacts();

        // Chi nhánh cũ Quận 1 phải là SUPERSEDED, chi nhánh mới Quận 3 phải là CURRENT + CUSTOMER_CORRECTED
        assertThat(facts).anySatisfy(f -> {
            assertThat(f.key()).isEqualTo("preferred_branch");
            assertThat(f.value()).isEqualTo("Quận 1");
            assertThat(f.validity()).isEqualTo(FactValidity.SUPERSEDED);
        });
        assertThat(facts).anySatisfy(f -> {
            assertThat(f.key()).isEqualTo("preferred_branch");
            assertThat(f.value()).isEqualTo("Quận 3");
            assertThat(f.validity()).isEqualTo(FactValidity.CURRENT);
            assertThat(f.provenance()).isEqualTo(FactProvenance.CUSTOMER_CORRECTED);
        });

        // Ngân sách cũ 300k phải là SUPERSEDED, ngân sách mới dưới 250k phải là CURRENT + CUSTOMER_CORRECTED
        assertThat(facts).anySatisfy(f -> {
            assertThat(f.key()).isEqualTo("customer_budget");
            assertThat(f.value()).contains("300k");
            assertThat(f.validity()).isEqualTo(FactValidity.SUPERSEDED);
        });
        assertThat(facts).anySatisfy(f -> {
            assertThat(f.key()).isEqualTo("customer_budget");
            assertThat(f.value()).contains("250k");
            assertThat(f.validity()).isEqualTo(FactValidity.CURRENT);
            assertThat(f.provenance()).isEqualTo(FactProvenance.CUSTOMER_CORRECTED);
        });
    }

    @Test
    @DisplayName("TC05 — Bot giới thiệu gói không đồng nghĩa khách đã chọn gói (lastOfferedPackageIds / interestedPackageId != selectedPackageId)")
    void tc05_botProposalDoesNotEqualCustomerSelection() {
        String psid = "psid-p33-tc05";
        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Chị bị mỏi cổ vai gáy, tư vấn giúp chị."),
                new FanpageBotRequest.ConversationTurn("assistant",
                        "Dạ em xin giới thiệu gói NS01 Gội đầu dưỡng sinh chuyên sâu Cổ Vai Gáy giá 399k (65 phút) ạ.")
        );
        String userInquiry = "Ok, gói đó gồm những gì?";

        // Giả lập trường hợp LLM nhầm lẫn gán selected_product_id = "NS01" khi khách chỉ hỏi "Ok, gói đó gồm những gì?"
        AdaptiveCognitiveReflection reconciled = parseAndReconcile("""
                {
                  "brain_reflection": {
                    "customer_understanding": {
                      "turn_intent": "PACKAGE_DETAIL_INQUIRY",
                      "booking_readiness": "EXPLORING",
                      "interested_package_id": "NS01"
                    },
                    "priority": {"primary": "ANSWER_PACKAGE_DETAILS", "secondary": []},
                    "proposed_booking_patch": {
                      "selected_product_id": "NS01",
                      "selected_product_label": "Gội đầu dưỡng sinh chuyên sâu Cổ Vai Gáy"
                    }
                  },
                  "bubbles": ["Dạ gói NS01 399k gồm rửa mặt, gội thảo dược và bấm huyệt cổ vai gáy 65 phút ạ."]
                }
                """, userInquiry, history);

        // ProposedBookingPatch sau khi đối chiếu với lời khách phải hạ selectedProductId về null
        assertThat(reconciled.proposedBookingPatch().selectedProductId()).isNull();
        assertThat(reconciled.interestedPackageId()).isEqualTo("NS01");

        ConversationState.StructuredBookingState priorWithBotOffered = new ConversationState.StructuredBookingState(
                null, null, null, null, null, VN_ZONE.getId(),
                ConversationState.BookingStatus.NONE, List.of("NS01")
        );
        ConversationState.StructuredBookingState updatedBooking = AdaptiveConversationIntelligence.applyAuthoritativeBookingPatch(
                priorWithBotOffered,
                reconciled.proposedBookingPatch(),
                userInquiry,
                history.get(1).content(),
                catalogPackages,
                catalogPackages,
                VN_ZONE,
                reconciled.customerUnderstanding().bookingReadiness(),
                reconciled.priority().primary()
        );

        // StructuredBookingState tuyệt đối không khóa selectedProductId, nhưng giữ candidateProductIds ("BOT_PROPOSED")
        assertThat(updatedBooking.selectedProductId()).isNull();
        assertThat(updatedBooking.candidateProductIds()).contains("NS01");
        assertThat(updatedBooking.status()).isEqualTo(ConversationState.BookingStatus.NONE);

        agentBrainService.applyAciTurnMemoryUpdates(
                PAGE_ID, psid, "T3", userInquiry, List.of(), reconciled, updatedBooking, null, false, null, "Direct Answer");
        CustomerUnderstandingDraft2Snapshot draft2 = agentBrainService.getCustomerUnderstandingDraft2Snapshot(PAGE_ID, psid);

        assertThat(draft2.businessCommitment().lastOfferedPackageIds()).contains("NS01");
        assertThat(draft2.businessCommitment().interestedPackageId()).isEqualTo("NS01");
        assertThat(draft2.businessCommitment().selectedPackageId()).isNull();
        assertThat(agentBrainService.recallBookingState(PAGE_ID, psid).selectedProductId()).isNull();
    }

    @Test
    @DisplayName("TC06 — Phản ứng chê đắt ('Giá 399k cao quá, có gói nào nhẹ hơn không?') ghi nhận PRICE_OBJECTION, KHÔNG lưu customer_budget = 399k")
    void tc06_priceObjectionIsNotStoredAsCustomerBudget() {
        String psid = "psid-p33-tc06";
        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Tư vấn gói cổ vai gáy giúp chị."),
                new FanpageBotRequest.ConversationTurn("assistant",
                        "Dạ bên em có gói NS01 Gội đầu dưỡng sinh chuyên sâu Cổ Vai Gáy giá 399k ạ.")
        );
        String objectionUtterance = "Giá 399k cao quá, có gói nào nhẹ hơn không?";

        // Giả lập LLM mắc lỗi gán customer_budget = "399k" và selected_product_id = "NS01" khi thấy số 399k trong câu của khách
        AdaptiveCognitiveReflection reconciled = parseAndReconcile("""
                {
                  "brain_reflection": {
                    "customer_understanding": {
                      "turn_intent": "PRICE_OBJECTION",
                      "cost_concern_expressed": true,
                      "booking_readiness": "EXPLORING",
                      "confirmed_facts": [
                        {"key": "customer_budget", "value": "399k", "validity": "CURRENT"}
                      ]
                    },
                    "priority": {"primary": "BUDGET_FIT", "secondary": []},
                    "proposed_booking_patch": {
                      "selected_product_id": "NS01"
                    }
                  },
                  "bubbles": ["Dạ nếu mình muốn gói nhẹ nhàng hơn thì bên em có gói CB01 199k (45 phút) ạ."]
                }
                """, objectionUtterance, history);

        // 1. Không có bất kỳ fact nào mang key customer_budget / budget_note chứa 399k
        assertThat(reconciled.confirmedFactsDelta())
                .noneMatch(f -> (f.key().equals("customer_budget") || f.key().equals("budget_note"))
                        && f.value().contains("399"));
        // 2. Phải có bản ghi price_reaction ghi nhận PRICE_OBJECTION đối với 399k
        assertThat(reconciled.confirmedFactsDelta())
                .anySatisfy(f -> {
                    assertThat(f.key()).isEqualTo("price_reaction");
                    assertThat(f.value()).contains("PRICE_OBJECTION").contains("399k");
                });
        assertThat(reconciled.customerUnderstanding().costConcernExpressed()).isTrue();
        assertThat(reconciled.proposedBookingPatch().selectedProductId()).isNull();

        ConversationState.StructuredBookingState updatedBooking = AdaptiveConversationIntelligence.applyAuthoritativeBookingPatch(
                new ConversationState.StructuredBookingState(
                        null, null, null, null, null, VN_ZONE.getId(),
                        ConversationState.BookingStatus.NONE, List.of("NS01")),
                reconciled.proposedBookingPatch(),
                objectionUtterance,
                history.get(1).content(),
                catalogPackages,
                catalogPackages,
                VN_ZONE,
                reconciled.customerUnderstanding().bookingReadiness(),
                reconciled.priority().primary()
        );
        assertThat(updatedBooking.selectedProductId()).isNull();

        agentBrainService.applyAciTurnMemoryUpdates(
                PAGE_ID, psid, "T3", objectionUtterance, List.of(), reconciled, updatedBooking, null, false, null, "Handle Objection");
        CustomerUnderstandingDraft2Snapshot draft2 = agentBrainService.getCustomerUnderstandingDraft2Snapshot(PAGE_ID, psid);

        assertThat(draft2.consultativeProfile().confirmedFacts())
                .noneMatch(f -> (f.key().equals("customer_budget") || f.key().equals("budget_note"))
                        && f.value().contains("399"))
                .anyMatch(f -> f.key().equals("price_reaction")
                        && f.value().contains("PRICE_OBJECTION")
                        && f.provenance() == FactProvenance.CUSTOMER_CONFIRMED);
        assertThat(draft2.businessCommitment().selectedPackageId()).isNull();
    }

    @Test
    @DisplayName("TC07 — Khách tạm hoãn quyết định ('Để tôi xem đã'): ghi nhận CUSTOMER_DEFERRAL / PAUSED_BY_CUSTOMER, không mất ngữ cảnh gói đang cân nhắc")
    void tc07_customerDeferralPreservesContextWithoutLockingBookingOrWipingMemory() {
        String psid = "psid-p33-tc07";
        List<FanpageBotRequest.ConversationTurn> history = new ArrayList<>();

        // Lượt 1: Khách nêu triệu chứng mỏi cổ vai gáy + Quận 1
        String u1 = "Chị bị mỏi cổ vai gáy nhiều, muốn làm ở Quận 1.";
        AdaptiveCognitiveReflection d1 = parseAndReconcile("""
                {
                  "brain_reflection": {
                    "customer_understanding": {
                      "turn_intent": "SYMPTOM_SHARING",
                      "self_reported_symptoms": [
                        {"value": "mỏi cổ vai gáy", "validity": "CURRENT"}
                      ],
                      "confirmed_facts": [
                        {"key": "preferred_branch", "value": "Quận 1", "validity": "CURRENT"}
                      ]
                    }
                  },
                  "bubbles": ["Dạ ở Quận 1 bên em có gói NS01 399k ạ."]
                }
                """, u1, history);
        ConversationState.StructuredBookingState b1 = new ConversationState.StructuredBookingState(
                null, null, null, null, null, VN_ZONE.getId(),
                ConversationState.BookingStatus.NONE, List.of("NS01")
        );
        agentBrainService.applyAciTurnMemoryUpdates(
                PAGE_ID, psid, "T1", u1, List.of(), d1, b1, null, false, null, "Direct Answer");

        // Lượt 2: Bot tư vấn gói NS01 399k và hỏi đặt lịch -> Khách trả lời "Để tôi xem đã"
        history.add(new FanpageBotRequest.ConversationTurn("user", u1));
        history.add(new FanpageBotRequest.ConversationTurn("assistant",
                "Dạ ở Quận 1 bên em có gói NS01 Gội đầu dưỡng sinh chuyên sâu Cổ Vai Gáy 399k. Em giữ lịch tối nay cho chị nhé?"));
        String deferralText = "Để tôi xem đã";

        AdaptiveCognitiveReflection d2 = parseAndReconcile("""
                {
                  "brain_reflection": {
                    "customer_understanding": {
                      "turn_intent": "CUSTOMER_DEFERRAL",
                      "interaction_permission": "PAUSED",
                      "conversation_state": "PAUSED_BY_CUSTOMER",
                      "booking_readiness": "NOT_READY"
                    },
                    "priority": {"primary": "RESPECTFUL_CLOSURE_OR_PAUSE", "secondary": []}
                  },
                  "bubbles": ["Dạ vâng ạ, chị cứ thong thả xem thêm nha!"]
                }
                """, deferralText, history);
        ConversationState.StructuredBookingState b2 = AdaptiveConversationIntelligence.applyAuthoritativeBookingPatch(
                b1,
                d2.proposedBookingPatch(),
                deferralText,
                history.get(1).content(),
                catalogPackages,
                catalogPackages,
                VN_ZONE,
                d2.customerUnderstanding().bookingReadiness(),
                d2.priority().primary()
        );
        agentBrainService.applyAciTurnMemoryUpdates(
                PAGE_ID, psid, "T3", deferralText, List.of(), d2, b2, null, false, null, "Respectful Pause");

        CustomerUnderstandingDraft2Snapshot draft2 = agentBrainService.getCustomerUnderstandingDraft2Snapshot(PAGE_ID, psid);
        OperationalStateSnapshot opState = agentBrainService.getAciOperationalState(PAGE_ID, psid);

        // 1. Trạng thái là PAUSED_BY_CUSTOMER (CUSTOMER_DEFERRAL), KHÔNG phải OPTED_OUT (optedOut == false)
        assertThat(draft2.consultativeProfile().turnIntent())
                .isIn("CUSTOMER_DEFERRAL", "CUSTOMER_PAUSE_OR_ACKNOWLEDGEMENT");
        assertThat(draft2.consultativeProfile().conversationState())
                .isEqualTo("PAUSED_BY_CUSTOMER");
        assertThat(draft2.consultativeProfile().interactionPermission())
                .isIn(InteractionPermission.PAUSED, InteractionPermission.CONCISE_PREFERRED);
        assertThat(opState.optedOut()).isFalse();

        // 2. Không ghi nhận khách đã đồng ý đặt lịch và không khóa selectedPackageId
        assertThat(b2.status()).isEqualTo(ConversationState.BookingStatus.NONE);
        assertThat(b2.selectedProductId()).isNull();
        assertThat(draft2.businessCommitment().selectedPackageId()).isNull();

        // 3. Không xóa ngữ cảnh gói đang cân nhắc (NS01 được giữ ở lastOfferedPackageIds và interestedPackageId)
        assertThat(draft2.businessCommitment().lastOfferedPackageIds()).contains("NS01");
        assertThat(draft2.businessCommitment().interestedPackageId()).isEqualTo("NS01");

        // 4. Giữ nguyên triệu chứng và chi nhánh Quận 1 đã xác nhận trước đó
        assertThat(draft2.consultativeProfile().selfReportedSymptoms())
                .anyMatch(s -> s.value().toLowerCase().contains("vai gáy") && s.validity() == FactValidity.CURRENT);
        assertThat(draft2.consultativeProfile().confirmedFacts())
                .anyMatch(f -> f.key().equals("preferred_branch") && f.value().contains("Quận 1")
                        && f.validity() == FactValidity.CURRENT);
    }

    @Test
    @DisplayName("TC08 — Tóm tắt lịch sử cũ giữ đúng nguồn gốc thông tin (Provenance) trên cả 9 nhóm bắt buộc")
    void tc08_olderHistorySummaryPreservesProvenanceAndAllNineCategories() {
        String psid = "psid-p33-tc08";
        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Chị bị mỏi cổ vai gáy, ban đầu định qua Quận 1."),
                new FanpageBotRequest.ConversationTurn("assistant",
                        "Dạ bên em giới thiệu gói NS01 Gội đầu dưỡng sinh chuyên sâu Cổ Vai Gáy 399k ở Quận 1 ạ."),
                new FanpageBotRequest.ConversationTurn("user",
                        "Giá 399k cao quá, chị đổi sang Quận 3 và chỉ tìm gói tầm 200k thôi."),
                new FanpageBotRequest.ConversationTurn("assistant",
                        "Dạ ở Quận 3 có gói CB01 Gội đầu dưỡng sinh Thư Giãn Cơ Bản 199k ạ."),
                new FanpageBotRequest.ConversationTurn("user", "Để tôi xem đã")
        );

        // Nạp trạng thái qua các lượt để kiểm tra đồng thời dữ liệu trong Draft2 + dữ liệu trích xuất từ transcript cũ
        AdaptiveCognitiveReflection r1 = parseAndReconcile("""
                {
                  "brain_reflection": {
                    "customer_understanding": {
                      "turn_intent": "SYMPTOM_SHARING",
                      "self_reported_symptoms": [{"value": "mỏi cổ vai gáy", "validity": "CURRENT"}],
                      "confirmed_facts": [{"key": "preferred_branch", "value": "Quận 1", "validity": "CURRENT"}]
                    }
                  },
                  "bubbles": ["Dạ bên em giới thiệu gói NS01 399k ạ."]
                }
                """, history.get(0).content(), List.of());
        ConversationState.StructuredBookingState b1 = new ConversationState.StructuredBookingState(
                null, null, null, null, null, VN_ZONE.getId(),
                ConversationState.BookingStatus.NONE, List.of("NS01")
        );
        agentBrainService.applyAciTurnMemoryUpdates(
                PAGE_ID, psid, "T1", history.get(0).content(), List.of(), r1, b1, null, false, null, "Direct Answer");

        AdaptiveCognitiveReflection r2 = parseAndReconcile("""
                {
                  "brain_reflection": {
                    "customer_understanding": {
                      "turn_intent": "PRICE_OBJECTION",
                      "cost_concern_expressed": true,
                      "confirmed_facts": [
                        {"key": "preferred_branch", "value": "Quận 3", "validity": "CURRENT", "operation": "REVISE"},
                        {"key": "customer_budget", "value": "tầm 200k", "validity": "CURRENT"}
                      ]
                    }
                  },
                  "bubbles": ["Dạ ở Quận 3 có gói CB01 199k ạ."]
                }
                """, history.get(2).content(), history.subList(0, 2));
        ConversationState.StructuredBookingState b2 = new ConversationState.StructuredBookingState(
                null, null, null, null, null, VN_ZONE.getId(),
                ConversationState.BookingStatus.NONE, List.of("NS01", "CB01")
        );
        agentBrainService.applyAciTurnMemoryUpdates(
                PAGE_ID, psid, "T3", history.get(2).content(), List.of(), r2, b2, null, false, null, "Handle Objection");

        AdaptiveCognitiveReflection r3 = parseAndReconcile("""
                {
                  "brain_reflection": {
                    "customer_understanding": {
                      "turn_intent": "CUSTOMER_DEFERRAL",
                      "interaction_permission": "PAUSED",
                      "conversation_state": "PAUSED_BY_CUSTOMER",
                      "interested_package_id": "CB01"
                    }
                  },
                  "bubbles": ["Dạ chị cứ thong thả xem thêm nha."]
                }
                """, history.get(4).content(), history.subList(0, 4));
        agentBrainService.applyAciTurnMemoryUpdates(
                PAGE_ID, psid, "T5", history.get(4).content(), List.of(), r3, b2, null, false, null, "Respectful Pause");

        CustomerUnderstandingDraft2Snapshot draft2 = agentBrainService.getCustomerUnderstandingDraft2Snapshot(PAGE_ID, psid);
        String summary = CleanAgentCatalogAndPromptBuilder.buildOlderHistoryEvidenceSummary(
                history, draft2, b2, catalogPackages);

        // Kiểm chứng đủ 9 nhóm bắt buộc và giữ nguyên nhãn Provenance riêng biệt, không nâng cấp BOT_PROPOSED / INFERRED thành CUSTOMER_CONFIRMED
        assertThat(summary)
                .contains("<OLDER_HISTORY_EVIDENCE_SUMMARY turns=\"T1..T5\">")
                .contains("- customer_needs_and_symptoms [provenance=CUSTOMER_CONFIRMED]:")
                .contains("mỏi cổ")
                .contains("mỏi vai")
                .contains("mỏi gáy")
                .contains("- package_interest_vs_selection:")
                .contains("interested_package=CB01 [status=CONSIDERING], selected_package=NONE [chưa chốt gói]")
                .contains("- customer_budget_and_price_sensitivity:")
                .contains("tầm 200k")
                .contains("- branch_preferences_and_exclusions:")
                .contains("Quận 3 [provenance=CUSTOMER_CORRECTED")
                .contains("- bot_introduced_packages [provenance=BOT_PROPOSED, unconfirmed_by_customer]:")
                .contains("NS01")
                .contains("CB01")
                .contains("- objections_concerns_and_unresolved_questions:")
                .contains("PRICE_OBJECTION")
                .contains("- customer_corrections_and_revisions [provenance=CUSTOMER_CORRECTED]:")
                .contains("preferred_branch=Quận 1 [validity=SUPERSEDED")
                .contains("- booking_intent_and_state [provenance=SYSTEM_DERIVED]:")
                .contains("- customer_confirmation_status:")
                .contains("CUSTOMER_DEFERRAL (PAUSED_BY_CUSTOMER");
    }

    @Test
    @DisplayName("TC09 — Hành trình tư vấn 14 lượt từ đầu đến đặt lịch giả lập qua MockBookingGateway (CONFIRMED)")
    void tc09_endToEndMultiTurnConsultationToMockBookingConfirmed() {
        String psid = "psid-p33-tc09";
        List<FanpageBotRequest.ConversationTurn> history = new ArrayList<>();
        AtomicReference<String> lastCapturedPrompt = new AtomicReference<>("");

        // Chuỗi phản hồi LLM cho 7 lượt của khách (tổng cộng 14 lượt user + assistant)
        List<String> scriptedLlmJsonResponses = List.of(
                // Turn 1 (User): Chào và nêu triệu chứng mỏi cổ vai gáy, đau đầu nhẹ ở Quận 1
                """
                {
                  "brain_reflection": {
                    "conversational_context": {"current_topic": "Tư vấn triệu chứng mỏi cổ vai gáy"},
                    "customer_understanding": {
                      "turn_intent": "SYMPTOM_SHARING",
                      "conversation_goal": "FIND_RELIEF",
                      "interaction_permission": "OPEN",
                      "buying_signal": "WARM",
                      "cost_concern_expressed": false,
                      "booking_readiness": "EXPLORING",
                      "self_reported_symptoms": [
                        {"value": "mỏi cổ vai gáy", "validity": "CURRENT"},
                        {"value": "đau đầu nhẹ", "validity": "CURRENT"}
                      ],
                      "confirmed_facts": [
                        {"key": "preferred_branch", "value": "Quận 1", "validity": "CURRENT"}
                      ],
                      "interested_package_id": "NS01"
                    },
                    "priority": {"primary": "CONSULTATIVE_RECOMMENDATION", "secondary": []},
                    "proposed_booking_patch": {}
                  },
                  "bubbles": [
                    "Dạ em chào chị Lan ạ! Với tình trạng mỏi cổ vai gáy kèm đau đầu nhẹ ở cơ sở Quận 1, chị tham khảo gói Gội đầu dưỡng sinh chuyên sâu Cổ Vai Gáy giá 399k (65 phút) giúp đả thông kinh lạc rất hiệu quả ạ."
                  ]
                }
                """,
                // Turn 2 (User): "Ok, gói đó gồm những gì?" -> Bot giải thích chi tiết gói NS01, không khóa selectedProductId
                """
                {
                  "brain_reflection": {
                    "conversational_context": {"current_topic": "Chi tiết gói NS01"},
                    "customer_understanding": {
                      "turn_intent": "PACKAGE_DETAIL_INQUIRY",
                      "conversation_goal": "UNDERSTAND_PACKAGE",
                      "interaction_permission": "OPEN",
                      "buying_signal": "WARM",
                      "cost_concern_expressed": false,
                      "booking_readiness": "EXPLORING",
                      "interested_package_id": "NS01"
                    },
                    "priority": {"primary": "ANSWER_PACKAGE_DETAILS", "secondary": []},
                    "proposed_booking_patch": {
                      "selected_product_id": "NS01"
                    }
                  },
                  "bubbles": [
                    "Dạ gói Gội đầu dưỡng sinh chuyên sâu Cổ Vai Gáy 399k (65 phút) bao gồm gội thảo dược bồ kết tươi, ấn huyệt chuyên sâu vùng đầu - cổ - vai - gáy và chườm thảo dược ấm giúp dịu hẳn đau đầu nhẹ cho chị ạ."
                  ]
                }
                """,
                // Turn 3 (User): "Giá 399k cao quá, chị chỉ tìm gói tầm 250k đổ lại thôi, có gói nào nhẹ hơn không?"
                """
                {
                  "brain_reflection": {
                    "conversational_context": {"current_topic": "Tìm gói tiết kiệm hơn 399k"},
                    "customer_understanding": {
                      "turn_intent": "PRICE_OBJECTION",
                      "conversation_goal": "FIND_AFFORDABLE_OPTION",
                      "interaction_permission": "OPEN",
                      "buying_signal": "HESITANT",
                      "cost_concern_expressed": true,
                      "booking_readiness": "EXPLORING",
                      "confirmed_facts": [
                        {"key": "customer_budget", "value": "tầm 250k", "validity": "CURRENT"}
                      ],
                      "interested_package_id": "CB01"
                    },
                    "priority": {"primary": "BUDGET_FIT", "secondary": []},
                    "proposed_booking_patch": {}
                  },
                  "bubbles": [
                    "Dạ nếu chị muốn gói nhẹ nhàng trong tầm 250k thì bên em có gói Gội đầu dưỡng sinh Thư Giãn Cơ Bản giá 199k (45 phút), vẫn chăm sóc vùng đầu - cổ - vai rất thư giãn ạ. Em giữ lịch cho chị nha?"
                  ]
                }
                """,
                // Turn 4 (User): "Để tôi xem đã" -> Tạm hoãn quyết định
                """
                {
                  "brain_reflection": {
                    "conversational_context": {"current_topic": "Khách tạm cân nhắc gói CB01"},
                    "customer_understanding": {
                      "turn_intent": "CUSTOMER_DEFERRAL",
                      "conversation_goal": "PAUSE_RESPECTFULLY",
                      "interaction_permission": "PAUSED",
                      "conversation_state": "PAUSED_BY_CUSTOMER",
                      "buying_signal": "HESITANT",
                      "cost_concern_expressed": false,
                      "booking_readiness": "NOT_READY",
                      "interested_package_id": "CB01"
                    },
                    "priority": {"primary": "RESPECTFUL_CLOSURE_OR_PAUSE", "secondary": []},
                    "proposed_booking_patch": {}
                  },
                  "bubbles": [
                    "Dạ vâng ạ, chị cứ thong thả xem thêm nha, khi nào cần hỗ trợ hoặc giải đáp thêm về gói Gội đầu dưỡng sinh Thư Giãn Cơ Bản 199k chị cứ nhắn em nhé ạ!"
                  ]
                }
                """,
                // Turn 5 (User): "À mà tối nay chị tiện bên Quận 3 hơn, đổi giúp chị sang Quận 3 nhé, gói Gội đầu dưỡng sinh Thư Giãn Cơ Bản 199k bên đó còn nhận lúc 19:30 tối nay không em?"
                """
                {
                  "brain_reflection": {
                    "conversational_context": {"current_topic": "Đổi sang Quận 3 và hỏi đặt gói CB01 lúc 19:30 tối nay"},
                    "customer_understanding": {
                      "turn_intent": "PREFERENCE_REVISION",
                      "conversation_goal": "BOOK_APPOINTMENT",
                      "interaction_permission": "OPEN",
                      "buying_signal": "STRONG",
                      "cost_concern_expressed": false,
                      "booking_readiness": "READY_TO_BOOK",
                      "confirmed_facts": [
                        {"key": "preferred_branch", "value": "Quận 3", "validity": "CURRENT", "operation": "REVISE"}
                      ],
                      "interested_package_id": "CB01"
                    },
                    "priority": {"primary": "BOOKING_COMPLETION", "secondary": []},
                    "proposed_booking_patch": {
                      "selected_product_id": "CB01",
                      "proposed_date": "tối nay",
                      "proposed_time": "19:30"
                    }
                  },
                  "bubbles": [
                    "Dạ cơ sở Quận 3 khung 19:30 tối nay vẫn còn giường trống cho gói Gội đầu dưỡng sinh Thư Giãn Cơ Bản 199k ạ. Chị xác nhận đặt khung giờ này giúp em nha!"
                  ]
                }
                """,
                // Turn 6 (User): "Ok chốt giúp chị nhé"
                """
                {
                  "brain_reflection": {
                    "conversational_context": {"current_topic": "Xác nhận chốt đặt lịch gói CB01 Quận 3 19:30 tối nay"},
                    "customer_understanding": {
                      "turn_intent": "BOOKING_CONFIRMATION",
                      "conversation_goal": "BOOK_APPOINTMENT",
                      "interaction_permission": "OPEN",
                      "buying_signal": "ACTION",
                      "cost_concern_expressed": false,
                      "booking_readiness": "READY_TO_BOOK"
                    },
                    "priority": {"primary": "BOOKING_COMPLETION", "secondary": []},
                    "proposed_booking_patch": {
                      "selected_product_id": "CB01",
                      "proposed_date": "tối nay",
                      "proposed_time": "19:30"
                    }
                  },
                  "bubbles": [
                    "Dạ em xác nhận lịch hẹn gói Gội đầu dưỡng sinh Thư Giãn Cơ Bản (199k) lúc 19:30 tối nay cho chị Lan ạ."
                  ]
                }
                """,
                // Turn 7 (User): "Cảm ơn em nhé, lát chị ghé đúng giờ"
                """
                {
                  "brain_reflection": {
                    "conversational_context": {"current_topic": "Chào hẹn gặp khách"},
                    "customer_understanding": {
                      "turn_intent": "GRATITUDE_CLOSING",
                      "conversation_goal": "WRAP_UP",
                      "interaction_permission": "OPEN",
                      "buying_signal": "ACTION",
                      "cost_concern_expressed": false,
                      "booking_readiness": "READY_TO_BOOK"
                    },
                    "priority": {"primary": "POLITE_CLOSURE", "secondary": []},
                    "proposed_booking_patch": {}
                  },
                  "bubbles": [
                    "Dạ em cảm ơn chị Lan nhiều ạ, hẹn gặp chị lúc 19:30 tối nay tại Nàng Sen Spa Quận 3 nha!"
                  ]
                }
                """
        );

        final int[] callIdx = {0};
        Mockito.when(llmClient.complete(Mockito.anyString(), Mockito.any(ReasoningBudget.class)))
                .thenAnswer(inv -> {
                    lastCapturedPrompt.set(inv.getArgument(0));
                    int idx = Math.min(callIdx[0]++, scriptedLlmJsonResponses.size() - 1);
                    return scriptedLlmJsonResponses.get(idx);
                });
        Mockito.when(llmClient.complete(Mockito.anyString()))
                .thenAnswer(inv -> {
                    lastCapturedPrompt.set(inv.getArgument(0));
                    int idx = Math.min(callIdx[0]++, scriptedLlmJsonResponses.size() - 1);
                    return scriptedLlmJsonResponses.get(idx);
                });

        List<String> customerMessages = List.of(
                "Chào shop, chị Lan bị mỏi cổ vai gáy và đau đầu nhẹ 1 tuần nay, muốn tìm gói ở Quận 1.",
                "Ok, gói đó gồm những gì?",
                "Giá 399k cao quá, chị chỉ tìm gói tầm 250k đổ lại thôi, có gói nào nhẹ hơn không?",
                "Để tôi xem đã",
                "À mà tối nay chị tiện bên Quận 3 hơn, đổi giúp chị sang Quận 3 và đặt cho chị gói Gội đầu dưỡng sinh Thư Giãn Cơ Bản lúc 19:30 tối nay nhé",
                "Ok chốt giúp chị nhé",
                "Cảm ơn em nhé, lát chị ghé đúng giờ"
        );

        FanpageBotResponse turn2Resp = null;
        FanpageBotResponse turn4Resp = null;
        FanpageBotResponse turn5Resp = null;
        FanpageBotResponse turn6Resp = null;

        for (int i = 0; i < customerMessages.size(); i++) {
            String userMsg = customerMessages.get(i);
            FanpageBotRequest req = buildBotRequest(psid, "Chị Lan", userMsg, new ArrayList<>(history));
            FanpageBotResponse resp = botService.handleCustomerMessage(req);

            if (i == 1) {
                turn2Resp = resp;
                // Tại Turn 2 ("Ok, gói đó gồm những gì?"): chưa khóa selectedProductId
                assertThat(agentBrainService.recallBookingState(PAGE_ID, psid).selectedProductId()).isNull();
            }
            if (i == 3) {
                turn4Resp = resp;
                // Tại Turn 4 ("Để tôi xem đã"): trạng thái PAUSED_BY_CUSTOMER / CUSTOMER_DEFERRAL, chưa khóa selectedProductId
                CustomerUnderstandingDraft2Snapshot snapTurn4 = agentBrainService.getCustomerUnderstandingDraft2Snapshot(PAGE_ID, psid);
                assertThat(snapTurn4.consultativeProfile().turnIntent())
                        .isIn("CUSTOMER_DEFERRAL", "CUSTOMER_PAUSE_OR_ACKNOWLEDGEMENT");
                assertThat(snapTurn4.consultativeProfile().conversationState())
                        .isEqualTo("PAUSED_BY_CUSTOMER");
                assertThat(snapTurn4.consultativeProfile().interactionPermission())
                        .isIn(InteractionPermission.PAUSED, InteractionPermission.CONCISE_PREFERRED);
                assertThat(snapTurn4.businessCommitment().selectedPackageId()).isNull();
            }
            if (i == 4) {
                turn5Resp = resp;
                // Tại Turn 5: checkAvailability đã chạy -> AWAITING_CUSTOMER_CONFIRMATION
                assertThat(agentBrainService.recallBookingState(PAGE_ID, psid).status())
                        .isEqualTo(ConversationState.BookingStatus.AWAITING_CUSTOMER_CONFIRMATION);
                assertThat(mockGateway.getCheckAvailabilityCallCount()).isEqualTo(1);
            }
            if (i == 5) {
                turn6Resp = resp;
            }

            history.add(new FanpageBotRequest.ConversationTurn("user", userMsg));
            history.add(new FanpageBotRequest.ConversationTurn("assistant", resp.replyText()));
        }

        // Sau 7 lượt tương tác đầy đủ, history có đúng 14 lượt (7 user + 7 assistant)
        assertThat(history).hasSize(14);
        assertThat(turn2Resp).isNotNull();
        assertThat(turn4Resp).isNotNull();
        assertThat(turn5Resp).isNotNull();
        assertThat(turn6Resp).isNotNull();

        // Kiểm chứng tại Turn 6: MockBookingGateway đã được gọi thành công và trả về mã đặt lịch giả lập MOCK-BK-...
        String bookingId = agentBrainService.recallBookingId(PAGE_ID, psid);
        String bookingSource = agentBrainService.recallBookingSource(PAGE_ID, psid);
        assertThat(bookingId).isNotNull().startsWith("MOCK-BK-");
        assertThat(bookingSource).isEqualTo("MOCK");
        assertThat(mockGateway.getCreateBookingCallCount()).isEqualTo(1);
        assertThat(mockGateway.getCreatedBookingsCount()).isEqualTo(1);
        assertThat(turn6Resp.replyText()).contains(bookingId);

        // Kiểm chứng toàn bộ trạng thái bộ nhớ cuối phiên (14 lượt):
        CustomerUnderstandingDraft2Snapshot finalDraft2 = agentBrainService.getCustomerUnderstandingDraft2Snapshot(PAGE_ID, psid);
        ConversationState.StructuredBookingState finalBooking = agentBrainService.recallBookingState(PAGE_ID, psid);

        // - Nhu cầu mỏi cổ vai gáy + đau đầu nhẹ vẫn còn CURRENT
        assertThat(finalDraft2.consultativeProfile().selfReportedSymptoms())
                .extracting(VersionedCustomerFact::value)
                .anyMatch(s -> s.toLowerCase().contains("vai gáy"));
        // - Chi nhánh Quận 1 đã chuyển sang SUPERSEDED, Quận 3 là CURRENT + CUSTOMER_CORRECTED
        assertThat(finalDraft2.consultativeProfile().confirmedFacts())
                .anyMatch(f -> f.key().equals("preferred_branch")
                        && f.value().equals("Quận 1")
                        && f.validity() == FactValidity.SUPERSEDED)
                .anyMatch(f -> f.key().equals("preferred_branch")
                        && f.value().equals("Quận 3")
                        && f.validity() == FactValidity.CURRENT
                        && f.provenance() == FactProvenance.CUSTOMER_CORRECTED);
        // - Phản ứng chê đắt 399k được lưu là price_reaction (PRICE_OBJECTION), còn customer_budget là tầm 250k
        assertThat(finalDraft2.consultativeProfile().confirmedFacts())
                .anyMatch(f -> f.key().equals("price_reaction") && f.value().contains("PRICE_OBJECTION"))
                .anyMatch(f -> f.key().equals("customer_budget") && f.value().contains("250k"))
                .noneMatch(f -> f.key().equals("customer_budget") && f.value().contains("399"));
        // - Cả hai gói NS01 và CB01 đều được ghi nhận trong lastOfferedPackageIds, và gói khách chốt là CB01
        assertThat(finalDraft2.businessCommitment().lastOfferedPackageIds())
                .contains("NS01", "CB01");
        assertThat(finalBooking.selectedProductId()).isEqualTo("CB01");
        assertThat(finalBooking.status()).isEqualTo(ConversationState.BookingStatus.CONFIRMED);

        // - Prompt ở lượt thứ 7 (khi đã có 12 lượt lịch sử trước đó) chứa đầy đủ từ [T1] đến [T12]
        assertThat(lastCapturedPrompt.get())
                .contains("[T1] Khách: Chào shop, chị Lan bị mỏi cổ vai gáy")
                .contains("[T12] Bot:");
    }

    @Test
    @DisplayName("TC10 — Không phá vỡ P3.1.1 (Outbound Delivery Ledger) và P3.2 (MockBookingGateway + BookingStateMachine + Referee)")
    void tc10_doesNotBreakP311OutboundDeliveryAndP32MockBookingAndReferee() {
        // 1. Kiểm chứng Outbound Delivery Ledger (P3.1.1): PENDING -> ACCEPTED_BY_CHANNEL -> DELIVERED
        String ledgerKey = SentBubbleLedger.key(PAGE_ID.toString(), "psid-tc10", "conv-tc10");
        SentBubbleLedger.recordCustomerTurn(ledgerKey, "u1", "Xin chào shop");
        SentBubbleLedger.recordAssistantTurn(
                ledgerKey,
                "u1:a",
                List.of("Dạ chào chị ạ!"),
                SentBubbleLedger.DeliveryStatus.PENDING,
                List.of()
        );

        // Khi còn ở PENDING, reconcile không được coi bong bóng là đã hiển thị cho khách
        assertThat(SentBubbleLedger.reconcile(ledgerKey, List.of()))
                .extracting(FanpageBotRequest.ConversationTurn::role)
                .containsExactly("user");

        SentBubbleLedger.transitionBubbleStatusMonotonically(
                ledgerKey, "u1:a", 0, SentBubbleLedger.DeliveryStatus.ACCEPTED_BY_CHANNEL, "mid.p33.tc10.1", null);
        assertThat(SentBubbleLedger.snapshot(ledgerKey).get(1).bubbles().get(0).status())
                .isEqualTo(SentBubbleLedger.DeliveryStatus.ACCEPTED_BY_CHANNEL);

        int reconciledCount = SentBubbleLedger.markBubbleDeliveredByExternalMid(
                PAGE_ID.toString(), "psid-tc10", "mid.p33.tc10.1");
        assertThat(reconciledCount).isEqualTo(1);
        assertThat(SentBubbleLedger.snapshot(ledgerKey).get(1).bubbles().get(0).status())
                .isEqualTo(SentBubbleLedger.DeliveryStatus.DELIVERED);

        // 2. Kiểm chứng BookingStateMachine + MockBookingGateway (P3.2): không gọi gateway khi thiếu giờ hẹn
        ConversationState.StructuredBookingState incompleteStep =
                AdaptiveConversationIntelligence.applyAuthoritativeBookingPatch(
                        ConversationState.StructuredBookingState.none(),
                        new AdaptiveConversationIntelligence.ProposedBookingPatch("CB01", null, null),
                        "Chị muốn đặt gói CB01",
                        "",
                        catalogPackages,
                        catalogPackages,
                        VN_ZONE,
                        BookingReadiness.READY_TO_BOOK,
                        "BOOKING_COMPLETION"
                );
        assertThat(incompleteStep.status()).isEqualTo(ConversationState.BookingStatus.AWAITING_DATE_OR_TIME);
        assertThat(mockGateway.getCreatedBookingsCount()).isZero();
    }
}
