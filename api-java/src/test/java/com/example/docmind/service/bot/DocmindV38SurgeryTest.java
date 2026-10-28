package com.example.docmind.service.bot;

import com.docmind.guardrail.ReplyContractValidator;
import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.dto.bot.FanpageBotRequest.ConversationTurn;
import com.example.docmind.dto.bot.FanpageBotResponse;
import com.example.docmind.dto.bot.FanpageBotResponse.ReplyBubble;
import com.example.docmind.service.llm.LlmClient;
import com.example.docmind.service.llm.ReasoningBudget;
import com.example.docmind.service.retrieval.PromptBuilder;
import com.example.docmind.service.retrieval.ReciprocalRankFusion;
import com.example.docmind.service.sales.PriceLadderTracker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v3.8 — Java không viết lại văn bản LLM, Blocking/Warning, OpeningPolicy, state slots,
 * Catalog là nguồn giá duy nhất, Final Output Gate và SentBubbleLedger (in-memory reconcile).
 * Phần durable-store sau restart thật nằm ở {@link SentBubbleLedgerDurabilityIT}.
 */
class DocmindV38SurgeryTest {

    private static final UUID PAGE_ID = UUID.fromString("b23cd382-4f7a-4829-8c54-1d410da14bf8");
    private static final UUID DOC_ID = UUID.fromString("c9481efb-f3a9-4613-a2e0-fe50e9b99b39");

    private static ConversationState symptomState() {
        return ConversationState.initial().enrichWithCurrentMessage("Tôi bị đau mỏi vai gáy", List.of());
    }

    // =============================================================== 1. Validator không sửa văn bản LLM
    @Test
    @DisplayName("Validator ở chế độ LLM giữ NGUYÊN văn bản (không đổi ?->., không gỡ opener, không thêm 'ạ.')")
    void validatorDoesNotRewriteLlmText() {
        ConversationState st = symptomState();
        String t1 = "Dạ em hiểu mình đang trải qua cảm giác đó. Mình bị lâu chưa ạ?";
        var r1 = ReplyContractValidator.validateAndEnforce(List.of(new ReplyBubble(t1, 0)), st, "thỉnh thoảng",
                PrimaryActionRouter.PrimaryAction.GENERIC_SHORT_DISCOVERY, false, 10_000,
                ThinkingMode.FAST_NO_THINK, null);
        assertThat(r1.validatedBubbles().get(0).text()).isEqualTo(t1);

        String t2 = "Mình bị lâu chưa ạ? Mình hay đau vào lúc nào ạ?";
        var r2 = ReplyContractValidator.validateAndEnforce(List.of(new ReplyBubble(t2, 0)), st, "thỉnh thoảng",
                PrimaryActionRouter.PrimaryAction.GENERIC_SHORT_DISCOVERY, false, 10_000,
                ThinkingMode.FAST_NO_THINK, null);
        assertThat(r2.validatedBubbles().get(0).text()).isEqualTo(t2);
        assertThat(r2.errors()).contains("TOO_MANY_QUESTIONS");
    }

    @Test
    @DisplayName("Mã lỗi BLOCKING có thật trong Validator; lỗi cấu trúc là WARNING")
    void blockingErrorCodesAreRealAndClassified() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/docmind/guardrail/ReplyContractValidator.java"));
        for (String code : ReplyContractValidator.BLOCKING_ERRORS) {
            assertThat(src).as("error code thật: " + code).contains("errors.add(\"" + code + "\")");
        }
        assertThat(ReplyContractValidator.hasBlockingError(List.of("UNGROUNDED_CATALOG_PRICE"))).isTrue();
        assertThat(ReplyContractValidator.hasBlockingError(List.of("FORBIDDEN_PACKAGE_CONFIRMATION_QUESTION"))).isTrue();
        assertThat(ReplyContractValidator.hasBlockingError(List.of("REPEATED_PRICE_AFTER_DISCUSSED"))).isTrue();
        assertThat(ReplyContractValidator.hasBlockingError(List.of("TOO_MANY_QUESTIONS", "TOO_LONG"))).isFalse();
    }

    @Test
    @DisplayName("Các hàm String-surgery đã bị xoá khỏi Validator/Service và đường lắp bong bóng LLM")
    void stringSurgeryRemoved() throws Exception {
        String v = Files.readString(Path.of("src/main/java/com/docmind/guardrail/ReplyContractValidator.java"));
        String s = Files.readString(Path.of("src/main/java/com/example/docmind/service/bot/FanpageBotService.java"));
        String referee = Files.readString(Path.of("src/main/java/com/example/docmind/service/bot/CleanAgentOutputRefereeAndSanitizer.java"));
        String orchestrator = Files.readString(Path.of("src/main/java/com/example/docmind/service/bot/CleanAgentOrchestratorService.java"));
        for (String name : List.of("sanitizeMechanicalOpenersInBubbles", "rewriteOrStripMechanicalOpener",
                "rewriteSingleSentenceMechanicalOpener", "fixBoundaryViolations")) {
            assertThat(v).as(name).doesNotContain(name + "(");
            assertThat(s).as(name).doesNotContain(name + "(");
            assertThat(referee).as(name).doesNotContain(name + "(");
            assertThat(orchestrator).as(name).doesNotContain(name + "(");
        }
        assertThat(s).contains("cleanAgentOrchestratorService.executeCleanAgentTurn(");
        int a = referee.indexOf("public static List<FanpageBotResponse.ReplyBubble> extractCleanBubblesFromOutput(");
        int b = referee.indexOf("public static boolean hasQuotedPriceInText(", a);
        String assembly = referee.substring(a, b);
        assertThat(assembly).doesNotContain("replaceAll(\"(?iU)\\\\s*\\\\+\\\\s*\"")
                .doesNotContain("stripRedundantGreetingPrefixPreservingGrammar")
                .doesNotContain("HonorificConsistencyHook")
                .doesNotContain("deliberatePostPriceMoodPreservationPass")
                .doesNotContain("postProcessReply")
                .doesNotContain("polishConversationalVietnamese")
                .doesNotContain("polishClosingQuestions");
    }

    // =============================================================== 2. OpeningPolicy
    private static PrimaryActionRouter.RoutingDecision route(String msg, ConversationState st) {
        List<String> kws = List.of("vai", "gáy", "cổ", "chân", "đầu", "lưng", "massage", "gội đầu");
        return PrimaryActionRouter.route(msg, st.enrichWithCurrentMessage(msg, kws), List.of(), kws);
    }

    @Test
    @DisplayName("OpeningPolicy: Turn 1 chào/hỏi giá trống không -> BRAND_INTRO (prepend opening cấu hình)")
    void openingPolicyBrandIntro() {
        for (String m : List.of("Chào shop", "Giá sao", "Tư vấn")) {
            var d = route(m, ConversationState.initial());
            assertThat(d.openingPolicy()).as(m).isEqualTo(PrimaryActionRouter.OpeningPolicy.BRAND_INTRO);
            assertThat(d.prependsConfiguredOpening()).isTrue();
        }
    }

    @Test
    @DisplayName("OpeningPolicy: Turn 1 đã nêu triệu chứng/FAQ -> DIRECT_ANSWER, không prepend opening cố định")
    void openingPolicyDirectAnswer() {
        var d = route("Tôi bị đau mỏi vai gáy", ConversationState.initial());
        assertThat(d.openingPolicy()).isEqualTo(PrimaryActionRouter.OpeningPolicy.DIRECT_ANSWER);
        assertThat(d.prependsConfiguredOpening()).isFalse();
    }

    @Test
    @DisplayName("OpeningPolicy: Turn 1 đã nêu nhóm dịch vụ -> SHORT_GREETING, không prepend và không hỏi lại nhóm dịch vụ")
    void openingPolicyShortGreeting() {
        var d = route("Xin giá dịch vụ massage", ConversationState.initial());
        assertThat(d.openingPolicy()).isIn(PrimaryActionRouter.OpeningPolicy.SHORT_GREETING,
                PrimaryActionRouter.OpeningPolicy.DIRECT_ANSWER);
        assertThat(d.prependsConfiguredOpening()).isFalse();
    }

    @Test
    @DisplayName("OpeningPolicy: Turn 1 có cờ an toàn -> SAFETY_OR_REPAIR, không mở đầu thương hiệu")
    void openingPolicySafety() {
        var d = route("Tôi bị tức ngực khó thở", ConversationState.initial());
        assertThat(d.openingPolicy()).isEqualTo(PrimaryActionRouter.OpeningPolicy.SAFETY_OR_REPAIR);
        assertThat(d.prependsConfiguredOpening()).isFalse();
    }

    // =============================================================== 3. State slots tách biệt
    @Test
    @DisplayName("Slots: tần suất/mức độ/thời gian khởi phát/phân bổ là 4 trường riêng; tần suất ghi đè (không nối mâu thuẫn)")
    void stateSlotsAreSeparate() {
        ConversationState s = ConversationState.initial()
                .enrichWithCurrentMessage("Tôi bị đau chân, thường xuyên", List.of())
                .enrichWithCurrentMessage("ừ nhưng thi thoảng thôi, hai bên chân, hai tuần nay, khá nặng", List.of());
        assertThat(s.knownFrequency()).isEqualTo("thi thoảng");
        assertThat(s.knownSeverity()).isEqualTo("khá nặng");
        assertThat(s.knownOnsetDuration()).isEqualTo("hai tuần");
        assertThat(s.knownSymptomDistribution()).isEqualTo("hai bên");

        ConversationState f = ConversationState.initial().enrichWithCurrentMessage("thỉnh thoảng", List.of());
        assertThat(f.knownFrequency()).isEqualTo("thỉnh thoảng");
        assertThat(f.knownSeverity()).isNull();
        assertThat(f.knownOnsetDuration()).isNull();
    }

    @Test
    @DisplayName("pendingQuestionSlot: chỉ đặt từ câu hỏi ĐÃ giao; xoá khi khách trả lời đúng slot; giữ khi nói ngoài luồng")
    void pendingQuestionSlotLifecycle() {
        assertThat(ConversationState.inferPendingQuestionSlot("Mình bị lâu chưa hay mới gần đây ạ?"))
                .isEqualTo(ConversationState.SLOT_ONSET);
        assertThat(ConversationState.inferPendingQuestionSlot("Dạ em ghi nhận rồi ạ.")).isNull();

        ConversationState base = ConversationState.initial().enrichWithCurrentMessage("Tôi bị đau vai", List.of());
        var decision = new PrimaryActionRouter.RoutingDecision(
                PrimaryActionRouter.PrimaryAction.GENERIC_SHORT_DISCOVERY,
                PrimaryActionRouter.PrimaryAction.GENERIC_SHORT_DISCOVERY,
                false, 1, "SERVICE_DISCOVERY", null);
        ConversationState asked = ConversationState.updateAfterSend(UUID.randomUUID(), "u-slot", "c-slot", base,
                "Tôi bị đau vai", decision,
                List.of(new ReplyBubble("Mình bị lâu chưa hay mới gần đây ạ?", 0)), false, 0);
        assertThat(asked.pendingQuestionSlot()).isEqualTo(ConversationState.SLOT_ONSET);

        // câu hỏi nằm ở bong bóng bị huỷ => không có bong bóng giao => KHÔNG phát sinh slot
        ConversationState cancelledAsk = ConversationState.updateAfterSend(UUID.randomUUID(), "u-slot2", "c-slot2", base,
                "Tôi bị đau vai", decision, List.of(new ReplyBubble("Dạ em hiểu vai mình đang mỏi ạ.", 0)), false, 0);
        assertThat(cancelledAsk.pendingQuestionSlot()).isNull();

        ConversationState offTopic = asked.enrichWithCurrentMessage("Em trả lời nghe máy móc quá", List.of());
        assertThat(offTopic.pendingQuestionSlot()).isEqualTo(ConversationState.SLOT_ONSET);
        ConversationState answered = asked.enrichWithCurrentMessage("hai tuần nay rồi", List.of());
        assertThat(answered.pendingQuestionSlot()).isNull();
        assertThat(answered.knownOnsetDuration()).isEqualTo("hai tuần");
    }

    // =============================================================== 4. Style complaint vs price objection
    @Test
    @DisplayName("Góp ý văn phong kèm chê đắt cùng lượt -> CONVERSATION_STYLE_REPAIR, KHÔNG hạ gói")
    void styleComplaintBeatsPriceObjection() {
        assertThat(SentimentIntentLayer.isConversationStyleComplaint("Em nói chuyện máy móc quá, mà còn đắt nữa")).isTrue();
        assertThat(SentimentIntentLayer.isConversationStyleComplaint("đắt thế")).isFalse();
        ConversationState st = ConversationState.initial()
                .enrichWithCurrentMessage("Tôi bị đau vai gáy", List.of());
        var d = route("Em nói chuyện máy móc quá, mà còn đắt nữa", st);
        assertThat(d.detectedIntent()).isIn("CONVERSATION_STYLE_REPAIR", "FRUSTRATION_CONCERN_ACKNOWLEDGEMENT");
        assertThat(d.effectiveResponseAction()).isNotIn(
                PrimaryActionRouter.PrimaryAction.HANDLE_PRICE_OBJECTION_FIRST_TIME,
                PrimaryActionRouter.PrimaryAction.HANDLE_PRICE_OBJECTION_REPEAT);
    }

    // =============================================================== 5. Catalog là nguồn giá duy nhất
    private static PackageCatalogService.PackageSummary pkg(String code, String name, long price, String region) {
        return new PackageCatalogService.PackageSummary(region + "_" + code, code, region, name, "massage", List.of(),
                60, "60 phút", price, price / 1000 + "k", "phòng chung", "massage", "đau mỏi", "", false);
    }

    @Test
    @DisplayName("PriceLadderTracker: không còn ladder/giá sàn/khuyến mãi cứng trong production code")
    void priceLadderHasNoHardcodedTiers() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/example/docmind/service/sales/PriceLadderTracker.java"));
        for (String banned : List.of("HANOI_LADDER", "SAIGON_LADDER", "CATALOG_FLOOR_PRICE", "DEFAULT_PROMO_POLICY",
                "399_000L", "239_000L", "129_000L", "570_000L", "Giảm 10% khi mua liệu trình")) {
            assertThat(src).as(banned).doesNotContain(banned);
        }
        String pb = Files.readString(Path.of("src/main/java/com/example/docmind/service/bot/FanpageBotPromptBuilder.java"));
        assertThat(pb).doesNotContain("\"399.000 VNĐ -\"").doesNotContain("\"239.000 VNĐ\"").doesNotContain("Gói cơ bản (DS01)");
    }

    @Test
    @DisplayName("PriceLadderTracker: Catalog rỗng -> CATALOG_UNVERIFIED, không bịa giá; có Catalog -> hạ đúng 1 bậc theo Catalog")
    void priceLadderUsesOnlyVerifiedCatalog() {
        List<ConversationTurn> history = List.of(
                new ConversationTurn("user", "giá massage"),
                new ConversationTurn("assistant", "Dạ gói Massage cơ bản 60 phút giá 399k ạ."));
        var empty = PriceLadderTracker.evaluate("đắt quá", history, List.of(), "hanoi");
        assertThat(empty.isPriceObjection()).isTrue();
        assertThat(empty.targetPrice()).isNull();
        assertThat(empty.rationale()).startsWith("CATALOG_UNVERIFIED");

        var catalog = List.of(pkg("A1", "Gói A", 399_000, "HN"), pkg("B1", "Gói B", 239_000, "HN"),
                pkg("S1", "Gói Sài Gòn", 111_000, "SG"));
        var res = PriceLadderTracker.evaluate("đắt quá", history, catalog, "hanoi");
        assertThat(res.targetPrice()).isEqualTo(239_000L);
        assertThat(res.targetPackageName()).isEqualTo("Gói B");
        assertThat(res.discountPolicy()).isNull();
    }

    // =============================================================== 6. Ledger (in-memory) reconcile
    @Test
    @DisplayName("Ledger: history rỗng -> khôi phục ĐỦ user + assistant đúng thứ tự (bong bóng huỷ không hiển thị)")
    void ledgerRestoresFullTranscriptWhenClientHistoryEmpty() {
        SentBubbleLedger.useStore(new SentBubbleLedger.InMemoryStore());
        String key = SentBubbleLedger.key("p", "u", "c");
        SentBubbleLedger.recordCustomerTurn(key, "t1", "Giá");
        SentBubbleLedger.recordAssistantTurn(key, "t1:a", List.of("B1", "B2", "B3"), SentBubbleLedger.DeliveryStatus.DELIVERED);
        SentBubbleLedger.recordCustomerTurn(key, "t2", "Tôi đau vai");
        SentBubbleLedger.recordAssistantTurn(key, "t2:a", List.of("Đồng cảm"), SentBubbleLedger.DeliveryStatus.DELIVERED,
                List.of("Câu hỏi bị huỷ?"));
        List<ConversationTurn> restored = SentBubbleLedger.reconcile(key, List.of());
        assertThat(restored).extracting(ConversationTurn::role).containsExactly("user", "assistant", "user", "assistant");
        assertThat(restored.get(1).content()).isEqualTo("B1\nB2\nB3");
        assertThat(restored.get(3).content()).isEqualTo("Đồng cảm").doesNotContain("huỷ");
        SentBubbleLedger.clear();
    }

    @Test
    @DisplayName("Ledger: history client không rỗng nhưng thiếu bong bóng/bị cắt -> bản đầy đủ ghi đè; lượt lạ không bị bịa; không ghi đè ngược")
    void ledgerReconcilesNonEmptyTruncatedHistory() {
        SentBubbleLedger.useStore(new SentBubbleLedger.InMemoryStore());
        String key = SentBubbleLedger.key("p", "u", "c2");
        SentBubbleLedger.recordCustomerTurn(key, "t1", "Giá");
        SentBubbleLedger.recordAssistantTurn(key, "t1:a", List.of("B1 đầy đủ", "B2 đầy đủ", "B3 đầy đủ"),
                SentBubbleLedger.DeliveryStatus.DELIVERED);
        List<ConversationTurn> merged = SentBubbleLedger.reconcile(key, List.of(
                new ConversationTurn("user", "Giá"),
                new ConversationTurn("assistant", "B1 đầy đủ…"),
                new ConversationTurn("user", "Tôi đau vai")));
        assertThat(merged).hasSize(3);
        assertThat(merged.get(1).content()).isEqualTo("B1 đầy đủ\nB2 đầy đủ\nB3 đầy đủ");
        assertThat(merged.get(2).content()).isEqualTo("Tôi đau vai");

        // client có lượt không hề tồn tại trong ledger (history do simulator cung cấp): giữ nguyên
        List<ConversationTurn> foreign = List.of(new ConversationTurn("user", "xin chào"), new ConversationTurn("assistant", "chào"));
        assertThat(SentBubbleLedger.reconcile(key, foreign)).containsExactlyElementsOf(foreign);

        // idempotent khi webhook retry: cùng turnId ghi 2 lần chỉ còn 1 bản
        SentBubbleLedger.recordCustomerTurn(key, "t1", "Giá");
        SentBubbleLedger.recordAssistantTurn(key, "t1:a", List.of("x"), SentBubbleLedger.DeliveryStatus.DELIVERED);
        assertThat(SentBubbleLedger.snapshot(key)).hasSize(2);
        SentBubbleLedger.clear();
    }

    @Test
    @DisplayName("Ledger: resetSession xoá cả store lẫn cache")
    void ledgerResetClearsStoreAndCache() {
        SentBubbleLedger.Store store = new SentBubbleLedger.InMemoryStore();
        SentBubbleLedger.useStore(store);
        String key = SentBubbleLedger.key("p", "u", "c3");
        SentBubbleLedger.recordCustomerTurn(key, "t1", "Giá");
        assertThat(store.load(key)).hasSize(1);
        SentBubbleLedger.reset(key);
        assertThat(store.load(key)).isEmpty();
        assertThat(SentBubbleLedger.snapshot(key)).isEmpty();
        SentBubbleLedger.clear();
    }

    // =============================================================== 7. Referee + Final Output Gate (service, LLM giả lập)
    private record Harness(FanpageBotService service, AtomicReference<java.util.function.Supplier<String>> llm, AtomicInteger calls) {
    }

    private static Harness harness() {
        List<ReciprocalRankFusion.FusedChunk> chunks = ThinkingModePolicyAndAdaptiveRouterTest.buildTestChunks();
        PromptBuilder.PagePersona persona = ThinkingModePolicyAndAdaptiveRouterTest.buildTestPersona();
        var retrievalService = Mockito.mock(com.example.docmind.service.retrieval.RetrievalService.class);
        Mockito.when(retrievalService.retrieveWithRules(Mockito.anyList(), Mockito.anyString(), Mockito.anyInt(),
                Mockito.anyList(), Mockito.any()))
                .thenReturn(new com.example.docmind.service.retrieval.RetrievalService.RetrievalResult(chunks, List.of()));
        var pageRepo = Mockito.mock(com.example.docmind.repository.KnowledgePageRepository.class);
        var pageEntity = new com.example.docmind.entity.KnowledgePageEntity();
        pageEntity.setId(PAGE_ID);
        pageEntity.setName(persona.name());
        pageEntity.setIndustry(persona.industry());
        pageEntity.setTargetAudience(persona.targetAudience());
        pageEntity.setDescription(persona.description());
        pageEntity.setPersonaConfig(persona.resolvedConfig());
        Mockito.when(pageRepo.findById(PAGE_ID)).thenReturn(java.util.Optional.of(pageEntity));

        AtomicReference<java.util.function.Supplier<String>> llm = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        LlmClient client = Mockito.mock(LlmClient.class);
        Mockito.when(client.name()).thenReturn("ollama:qwen3:14b");
        Mockito.when(client.complete(Mockito.anyString(), Mockito.any(ReasoningBudget.class)))
                .thenAnswer(inv -> {
                    calls.incrementAndGet();
                    return llm.get().get();
                });
        FanpageBotService service = new FanpageBotService(retrievalService, client,
                new com.example.docmind.config.ChatProperties(), null, pageRepo,
                null, null, null, null, null, null, null);
        return new Harness(service, llm, calls);
    }

    private static String json(String text) {
        return "{\"reply_bubbles\":[{\"text\":\"" + text + "\",\"typing_delay_ms\":0}],\"should_handoff\":false}";
    }

    private static FanpageBotResponse send(Harness h, String sender, String msg, List<ConversationTurn> history) {
        return h.service().handleCustomerMessage(new FanpageBotRequest(
                PAGE_ID.toString(), sender, "Khách", msg, null, new ArrayList<>(history), List.of(DOC_ID), 5));
    }

    @Test
    @DisplayName("Referee: giá ngoài Catalog -> regenerate đúng 1 lần -> vẫn sai => KHÔNG gửi bubble vi phạm, chuyển handoff an toàn")
    void blockingPriceIsNeverSentAndRegeneratedOnce() {
        SentBubbleLedger.useStore(new SentBubbleLedger.InMemoryStore());
        Harness h = harness();
        String sender = "u-v38-block";
        List<ConversationTurn> history = new ArrayList<>();
        h.llm().set(() -> json("Để em báo đúng gói phù hợp nhất, hiện mình đang muốn tìm hiểu dịch vụ nào ạ?"));
        FanpageBotResponse r1 = send(h, sender, "giá", history);
        history.add(new ConversationTurn("user", "giá"));
        history.add(new ConversationTurn("assistant", r1.replyText()));

        h.calls().set(0);
        String bad = "Dạ với tình trạng này em gợi ý gói trị liệu giá 777k nhé ạ.";
        h.llm().set(() -> json(bad));
        FanpageBotResponse r2 = send(h, sender, "Tôi bị đau mỏi vai gáy thi thoảng", history);
        assertThat(h.calls().get()).as("1 lần gọi gốc + tối đa 1 lần regenerate").isLessThanOrEqualTo(2);
        assertThat(r2.replyText()).doesNotContain("777k");
        assertThat(r2.replyBubbles()).extracting(ReplyBubble::text).doesNotContain(bad);
        assertThat(r2.needsHumanHandoff()).isTrue();
        assertThat(r2.replyBubbles()).hasSize(1);
        assertThat(r2.replyBubbles().get(0).text()).isEqualTo(FanpageBotService.SAFE_HANDOFF_BUBBLE_TEXT);
        var tel = h.service().getLastTurnTelemetry();
        assertThat(tel.usedTemplateFallback()).isTrue();
        assertThat(tel.validationErrors()).contains("UNGROUNDED_CATALOG_PRICE");
        SentBubbleLedger.clear();
    }

    @Test
    @DisplayName("Referee: regenerate thành công -> bubble cuối là NGUYÊN VĂN output LLM lần 2 (provenance, không Java viết lại)")
    void regenerateSuccessKeepsRawLlmTextVerbatim() {
        SentBubbleLedger.useStore(new SentBubbleLedger.InMemoryStore());
        Harness h = harness();
        String sender = "u-v38-regen";
        List<ConversationTurn> history = new ArrayList<>();
        h.llm().set(() -> json("Để em báo đúng gói phù hợp nhất, hiện mình đang muốn tìm hiểu dịch vụ nào ạ?"));
        FanpageBotResponse r1 = send(h, sender, "giá", history);
        history.add(new ConversationTurn("user", "giá"));
        history.add(new ConversationTurn("assistant", r1.replyText()));

        AtomicInteger n = new AtomicInteger();
        String good = "Nghe vai gáy mỏi thi thoảng mới bị thì cũng dễ chịu hơn nhiều, mình thường bị vào lúc nào trong ngày ạ?";
        h.llm().set(() -> n.getAndIncrement() == 0
                ? json("Dạ em gợi ý gói trị liệu giá 777k nhé ạ.")
                : json(good));
        FanpageBotResponse r2 = send(h, sender, "Tôi bị đau mỏi vai gáy", history);
        assertThat(r2.replyBubbles()).extracting(ReplyBubble::text).contains(good);
        assertThat(r2.replyText()).doesNotContain("777k");
        assertThat(h.service().getLastTurnTelemetry().repairApplied()).isTrue();
        SentBubbleLedger.clear();
    }

    @Test
    @DisplayName("State Lifecycle: packageExplicitlySelected & selectedPackageId qua 5 bước (Gợi ý A -> Chọn A -> Góp ý cách nói -> Từ chối A -> Chọn B)")
    void packageExplicitlySelectedLifecycle() {
        List<ReciprocalRankFusion.FusedChunk> chunks = ThinkingModePolicyAndAdaptiveRouterTest.buildTestChunks();
        List<String> kws = List.of("vai", "gáy", "cổ", "massage", "dưỡng sinh", "399k", "239k");
        UUID pageId = PAGE_ID;
        String sender = "u-pkg-lifecycle";
        String convId = "c-pkg-lifecycle";

        // Bước 1: Bot mới gợi ý gói A (NS01 - Massage cơ bản 60 phút 399k) -> packageExplicitlySelected == false, selectedPackageId == null
        ConversationState s0 = ConversationState.hydrateForTurn(pageId, sender, convId, true, "Tôi bị mỏi vai gáy", List.of(), kws, chunks)
                .enrichWithCurrentMessage("Tôi bị mỏi vai gáy", kws);
        PrimaryActionRouter.RoutingDecision d1 = PrimaryActionRouter.route("Tôi bị mỏi vai gáy", s0, chunks, kws);
        ConversationState afterBotRecommendA = ConversationState.updateAfterSend(
                pageId, sender, convId, s0, "Tôi bị mỏi vai gáy", d1,
                List.of(new ReplyBubble("Dạ với tình trạng mỏi vai gáy, mình tham khảo gói Massage cơ bản 60 phút giá 399k ạ.", 0)),
                false, 0
        );
        assertThat(afterBotRecommendA.lastOfferedPackageId()).isEqualTo("massage_co_ban_60");
        assertThat(afterBotRecommendA.packageExplicitlySelected()).isFalse();
        assertThat(afterBotRecommendA.selectedPackageId()).isNull();

        // Bước 2: Khách chủ động chọn gói A -> packageExplicitlySelected == true, selectedPackageId == "massage_co_ban_60"
        String msgSelectA = "Ok cho mình chốt gói massage cơ bản 60 phút nhé";
        ConversationState sSelectA = afterBotRecommendA.enrichWithCurrentMessage(msgSelectA, kws);
        assertThat(sSelectA.packageExplicitlySelected()).isTrue();
        assertThat(sSelectA.selectedPackageId()).isEqualTo("massage_co_ban_60");
        PrimaryActionRouter.RoutingDecision d2 = PrimaryActionRouter.route(msgSelectA, sSelectA, chunks, kws);
        ConversationState afterSelectA = ConversationState.updateAfterSend(
                pageId, sender, convId, sSelectA, msgSelectA, d2,
                List.of(new ReplyBubble("Dạ vâng, em giữ gói Massage cơ bản 60 phút cho mình ạ. Mình ghé khung giờ nào ạ?", 0)),
                false, 2
        );
        assertThat(afterSelectA.packageExplicitlySelected()).isTrue();
        assertThat(afterSelectA.selectedPackageId()).isEqualTo("massage_co_ban_60");

        // Bước 3: Khách góp ý cách nói (chưa từ chối gói) -> giữ nguyên gói A đã chọn
        String msgStyle = "Mà em nói chuyện hơi máy móc nhé";
        ConversationState sStyle = afterSelectA.enrichWithCurrentMessage(msgStyle, kws);
        assertThat(sStyle.packageExplicitlySelected()).isTrue();
        assertThat(sStyle.selectedPackageId()).isEqualTo("massage_co_ban_60");
        PrimaryActionRouter.RoutingDecision d3 = PrimaryActionRouter.route(msgStyle, sStyle, chunks, kws);
        ConversationState afterStyle = ConversationState.updateAfterSend(
                pageId, sender, convId, sStyle, msgStyle, d3,
                List.of(new ReplyBubble("Dạ em xin lỗi mình vì cách diễn đạt vừa rồi hơi cứng nhắc ạ.", 0)),
                false, 4
        );
        assertThat(afterStyle.packageExplicitlySelected()).isTrue();
        assertThat(afterStyle.selectedPackageId()).isEqualTo("massage_co_ban_60");

        // Bước 4: Khách đổi ý / từ chối gói A -> packageExplicitlySelected == false, selectedPackageId == null, thêm A vào rejectedPackages
        String msgRejectA = "Thôi mình không lấy gói massage cơ bản nữa, đổi gói khác đi";
        ConversationState sRejectA = afterStyle.enrichWithCurrentMessage(msgRejectA, kws);
        assertThat(sRejectA.packageExplicitlySelected()).isFalse();
        assertThat(sRejectA.selectedPackageId()).isNull();
        assertThat(sRejectA.rejectedPackages()).contains("massage_co_ban_60");
        PrimaryActionRouter.RoutingDecision d4 = PrimaryActionRouter.route(msgRejectA, sRejectA, chunks, kws);
        ConversationState afterRejectA = ConversationState.updateAfterSend(
                pageId, sender, convId, sRejectA, msgRejectA, d4,
                List.of(new ReplyBubble("Dạ vậy mình tham khảo gói Dưỡng sinh cơ bản 60 phút giá 239k nhẹ nhàng hơn nhé ạ.", 0)),
                false, 6
        );
        assertThat(afterRejectA.packageExplicitlySelected()).isFalse();
        assertThat(afterRejectA.selectedPackageId()).isNull();
        assertThat(afterRejectA.rejectedPackages()).contains("massage_co_ban_60");

        // Bước 5: Khách chọn gói B (DS02 - Dưỡng sinh cơ bản 60 phút 239k) -> packageExplicitlySelected == true, selectedPackageId == "duong_sinh_co_ban_60"
        String msgSelectB = "Vậy cho mình chốt gói dưỡng sinh cơ bản 60 phút 239k đi";
        ConversationState sSelectB = afterRejectA.enrichWithCurrentMessage(msgSelectB, kws);
        assertThat(sSelectB.packageExplicitlySelected()).isTrue();
        assertThat(sSelectB.selectedPackageId()).isEqualTo("duong_sinh_co_ban_60");
    }

    @Test
    @DisplayName("Single-Enrichment & 3-Bubble BRAND_INTRO: chỉ FanpageBotService gọi enrichWithCurrentMessage; BRAND_INTRO trả đủ 3 bong bóng cấu hình")
    void singleEnrichmentAndThreeBubbleBrandIntro() throws Exception {
        for (String rel : List.of(
                "src/main/java/com/example/docmind/service/bot/PrimaryActionRouter.java",
                "src/main/java/com/docmind/guardrail/ReplyContractValidator.java",
                "src/main/java/com/example/docmind/service/bot/FanpageBotPromptBuilder.java",
                "src/main/java/com/example/docmind/service/bot/CleanAgentOrchestratorService.java")) {
            String content = Files.readString(Path.of(rel));
            assertThat(content).as("No duplicate enrichWithCurrentMessage in " + rel)
                    .doesNotContain(".enrichWithCurrentMessage(");
        }
        String convSrc = Files.readString(Path.of("src/main/java/com/example/docmind/service/bot/ConversationState.java"));
        assertThat(convSrc).doesNotContain(".enrichWithCurrentMessage(");

        String svcSrc = Files.readString(Path.of("src/main/java/com/example/docmind/service/bot/FanpageBotService.java"));
        int firstIdx = svcSrc.indexOf(".enrichWithCurrentMessage(");
        int lastIdx = svcSrc.lastIndexOf(".enrichWithCurrentMessage(");
        assertThat(firstIdx).isGreaterThan(0).isEqualTo(lastIdx);

        // Verify 3-Bubble BRAND_INTRO configuration returns all 3 bubbles (0ms, 1000ms, 2000ms)
        String b1 = "Dạ chào mình, em là An Nhiên — chuyên viên chăm sóc khách hàng của Nàng Sen Spa ạ.";
        String b2 = "Nàng Sen là góc nhỏ tĩnh lặng để mình gác lại mệt mỏi, thả lỏng cơ thể với liệu trình massage giãn cơ êm sâu và gội đầu thảo dược ấm áp, giúp nhẹ đầu và dễ vào giấc hơn";
        String b3 = "Để em gửi mức giá trọn gói vừa vặn nhất, hôm nay cơ thể mình đang nhức mỏi vùng nào hay mình chỉ muốn nằm thư giãn toàn thân cho nhẹ người ạ?";
        com.example.docmind.dto.PagePersonaConfig cfg3 = new com.example.docmind.dto.PagePersonaConfig(
                "BEAUTY_SPA", "An Nhiên", "em", "mình", "ấm áp", "MODERATE",
                List.of(), List.of("massage", "vai gáy"), null, null,
                List.of(b1, b2, b3), 10000, null
        );
        PromptBuilder.PagePersona persona3 = new PromptBuilder.PagePersona(
                "Nàng Sen Spa", "BEAUTY_SPA", "Khách spa", "Ấm áp", cfg3);
        ConversationState init = ConversationState.initial().enrichWithCurrentMessage("Giá sao", List.of("massage", "vai gáy"));
        PrimaryActionRouter.RoutingDecision dec = PrimaryActionRouter.route("Giá sao", init, List.of(), List.of("massage", "vai gáy"));
        assertThat(dec.openingPolicy()).isEqualTo(PrimaryActionRouter.OpeningPolicy.BRAND_INTRO);
        List<ReplyBubble> bubbles = PrimaryActionRouter.executeActionBubbles(dec, "Giá sao", init, "mình", persona3, List.of(), null);
        assertThat(bubbles).hasSize(3);
        assertThat(bubbles.get(0)).isEqualTo(new ReplyBubble(b1, 0));
        assertThat(bubbles.get(1)).isEqualTo(new ReplyBubble(b2, 1000));
        assertThat(bubbles.get(2)).isEqualTo(new ReplyBubble(b3, 2000));
    }

    @Test
    @DisplayName("P0-1: Turn 2 kể triệu chứng sau BRAND_INTRO định tuyến đúng sang khám phá tình trạng (không Frustration Recovery); 'Hả là sao? Sao lại xin lỗi' không gán Graceful Exit")
    void p01_turn2AfterBrandIntroRoutesToSymptomDiscoveryNotFrustrationRepairAndAntiApologyDoesNotExit() {
        List<ReciprocalRankFusion.FusedChunk> chunks = ThinkingModePolicyAndAdaptiveRouterTest.buildTestChunks();
        List<String> kws = List.of("vai", "gáy", "cổ", "đầu", "mất ngủ", "massage", "dưỡng sinh");
        String b1 = "Dạ chào mình, em là An Nhiên — chuyên viên chăm sóc khách hàng của Nàng Sen Spa ạ.";
        String b2 = "Nàng Sen là góc nhỏ tĩnh lặng để mình gác lại mệt mỏi, thả lỏng cơ thể với liệu trình massage giãn cơ êm sâu và gội đầu thảo dược ấm áp, giúp nhẹ đầu và dễ vào giấc hơn";
        String b3 = "Để em gửi mức giá trọn gói vừa vặn nhất, hôm nay cơ thể mình đang nhức mỏi vùng nào hay mình chỉ muốn nằm thư giãn toàn thân cho nhẹ người ạ?";

        ConversationState s1 = ConversationState.initial().enrichWithCurrentMessage("Tư vấn", kws);
        PrimaryActionRouter.RoutingDecision d1 = PrimaryActionRouter.route("Tư vấn", s1, chunks, kws);
        ConversationState afterT1 = ConversationState.updateAfterSend(
                PAGE_ID, "u-p01", "c-p01", s1, "Tư vấn", d1,
                List.of(new ReplyBubble(b1, 0), new ReplyBubble(b2, 1000), new ReplyBubble(b3, 2000)),
                false, 0
        );
        assertThat(afterT1.conditionAskedCount()).isZero();
        assertThat(afterT1.lastQuestionIntent()).isEqualTo("ASK_INITIAL_NEED");

        String turn2Msg = "Tôi đau đầu, dễ mất ngủ và đau nhức vai gáy";
        ConversationState s2 = afterT1.enrichWithCurrentMessage(turn2Msg, kws);
        PrimaryActionRouter.RoutingDecision d2 = PrimaryActionRouter.route(turn2Msg, s2, chunks, kws);
        assertThat(d2.effectiveResponseAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.ACKNOWLEDGE_AND_ASK_PREFERENCE);
        assertThat(d2.detectedIntent()).isNotEqualTo("CONVERSATION_STYLE_REPAIR");
        assertThat(d2.correspondingMicroSkillCode()).isEqualTo("spa_condition_first_discovery");

        String turn3Msg = "Hả là sao? Sao lại xin lỗi";
        ConversationState s3 = s2.enrichWithCurrentMessage(turn3Msg, kws);
        PrimaryActionRouter.RoutingDecision d3 = PrimaryActionRouter.route(turn3Msg, s3, chunks, kws);
        assertThat(d3.detectedIntent()).isEqualTo("ANTI_APOLOGY_PUSHBACK");
        assertThat(d3.correspondingMicroSkillCode()).isNotEqualTo("spa_graceful_exit_after_rejection");
        assertThat(d3.correspondingMicroSkillCode()).isEqualTo("spa_condition_first_discovery");
    }

    @Test
    @DisplayName("P0-2: 'Nghe thảo mai vãi' định tuyến sang WORDING_FEEDBACK_REPAIR, chặn quảng cáo dịch vụ & icon 💙; chặn câu hỏi xác nhận hiển nhiên '... phải không?'")
    void p02_wordingFeedbackThaoMaiRoutesToWordingFeedbackRepairAndBlocksSalesPitchEmojiAndConfirmationEcho() {
        List<ReciprocalRankFusion.FusedChunk> chunks = ThinkingModePolicyAndAdaptiveRouterTest.buildTestChunks();
        List<String> kws = List.of("vai", "gáy", "cổ", "đầu", "massage");
        ConversationState st = ConversationState.initial().enrichWithCurrentMessage("Tôi đau đầu, mỏi vai gáy", kws);

        assertThat(SentimentIntentLayer.isWordingOrExpressionFeedback("Nghe thảo mai vãi")).isTrue();
        ConversationState stFeedback = st.enrichWithCurrentMessage("Nghe thảo mai vãi", kws);
        PrimaryActionRouter.RoutingDecision dec = PrimaryActionRouter.route("Nghe thảo mai vãi", stFeedback, chunks, kws);
        assertThat(dec.detectedIntent()).isEqualTo("WORDING_FEEDBACK_REPAIR");
        assertThat(dec.correspondingMicroSkillCode()).isEqualTo("greeting_warm_intro");

        // Validator blocks sales pitch and 💙 on wording feedback
        var badPitch = ReplyContractValidator.validateAndEnforce(
                List.of(new ReplyBubble("Dạ em cảm ơn mình góp ý nha 💙 Bên em có gói massage trị liệu chuyên sâu chăm sóc kỹ vùng vai gáy ạ.", 0)),
                stFeedback, "Nghe thảo mai vãi",
                PrimaryActionRouter.PrimaryAction.GENERIC_SHORT_DISCOVERY, false, 10_000,
                ThinkingMode.FAST_NO_THINK, null
        );
        assertThat(badPitch.errors()).contains("FORBIDDEN_SALES_PITCH_ON_WORDING_FEEDBACK", "VR-015A_FORBIDDEN_EMOJI");
        assertThat(ReplyContractValidator.hasBlockingError(badPitch.errors())).isTrue();

        var goodAck = ReplyContractValidator.validateAndEnforce(
                List.of(new ReplyBubble("Dạ em cảm ơn mình đã góp ý thẳng thắn nha, để em nói chuyện ngắn gọn và tự nhiên hơn ạ.", 0)),
                stFeedback, "Nghe thảo mai vãi",
                PrimaryActionRouter.PrimaryAction.GENERIC_SHORT_DISCOVERY, false, 10_000,
                ThinkingMode.FAST_NO_THINK, null
        );
        assertThat(ReplyContractValidator.hasBlockingError(goodAck.errors())).isFalse();

        // Validator blocks "... phải không?" / "... đúng không?"
        var badEcho = ReplyContractValidator.validateAndEnforce(
                List.of(new ReplyBubble("Dạ mình đang bị đau đầu, dễ mất ngủ và đau nhức vai gáy phải không ạ?", 0)),
                st, "Tôi đau đầu, dễ mất ngủ và đau nhức vai gáy",
                PrimaryActionRouter.PrimaryAction.ACKNOWLEDGE_AND_ASK_PREFERENCE, false, 10_000,
                ThinkingMode.FAST_NO_THINK, null
        );
        assertThat(badEcho.errors()).contains("FORBIDDEN_AWKWARD_CLOSING_QUESTION");
        assertThat(ReplyContractValidator.hasBlockingError(badEcho.errors())).isTrue();
    }

    @Test
    @DisplayName("P0-3: Chặn gán sai 'tinh dầu thảo dược' cho gói không có trong spec và chặn tuyên bố 'giảm đau đầu hiệu quả'")
    void p03_blocksUngroundedTinhDauThaoDuocAndMedicalHeadacheEfficacyClaim() {
        List<ReciprocalRankFusion.FusedChunk> chunks = ThinkingModePolicyAndAdaptiveRouterTest.buildTestChunks();
        List<String> kws = List.of("vai", "gáy", "cổ", "đầu", "massage");
        ConversationState st = ConversationState.hydrateForTurn(
                PAGE_ID, "u-p03", "c-p03", true, "Giá sao", List.of(), kws, chunks
        ).enrichWithCurrentMessage("Tôi đau đầu, mỏi vai gáy thi thoảng", kws);

        var badHerbOil = ReplyContractValidator.validateAndEnforce(
                List.of(new ReplyBubble("Dạ em gợi ý gói Massage cơ bản 60 phút giá 399k kết hợp tinh dầu thảo dược giúp giảm đau đầu hiệu quả ạ.", 0)),
                st, "Giá sao",
                PrimaryActionRouter.PrimaryAction.RECOMMEND_ONE_PACKAGE, false, 10_000,
                ThinkingMode.FAST_NO_THINK, null
        );
        assertThat(badHerbOil.errors()).contains("UNGROUNDED_PACKAGE_BENEFIT", "MEDICAL_CLAIM");
        assertThat(ReplyContractValidator.hasBlockingError(badHerbOil.errors())).isTrue();
    }

    @Test
    @DisplayName("P0-4: Chặn thời lượng '75 phút, 90 phút' không tồn tại trong nhóm dịch vụ massage của Nàng Sen Spa")
    void p04_blocksUngrounded75And90MinuteMassageDurations() {
        String realNangSenMassageCatalog = """
                | Mã dịch vụ | Tên dịch vụ | Giá | Thời gian | Quy trình tóm tắt | Không gian / Phòng | Nhân sự thực hiện | Điểm nổi bật | Phù hợp với khách nào |
                | --- | --- | --- | --- | --- | --- | --- | --- | --- |
                | NS01 | Massage cơ bản | 399.000VNĐ | 60 phút | Full body massage trị liệu cơ bản | Phòng chung (có rèm riêng) | KTV tiêu chuẩn | Giá tốt nhất trong nhóm massage trị liệu | Khách lần đầu trải nghiệm |
                | NS02 | Massage cơ bản nâng cao | 530.000VNĐ | 80 phút | 10p ngâm bồn thảo dược + tắm tráng + 70p massage | Phòng chung (có rèm riêng) | KTV tiêu chuẩn | Có ngâm bồn thảo dược giúp giãn cơ | Khách mỏi nhiều vùng |
                | NS03 | Massage phục hồi | 580.000VNĐ | 100 phút | 10p ngâm bồn thảo dược + 5p xông hơi + 85p massage | Phòng VIP riêng | KTV cứng tay | Gói được chọn nhiều nhất | Khách đau cổ vai gáy – lưng |
                | DS04 | Silver – Gói dưỡng sinh phục hồi | 500.000VNĐ | 90 phút | Gội 30p + Spa 60p | Khu dưỡng sinh + phòng spa | KTV dưỡng sinh | Linh hoạt theo nhu cầu | Khách muốn kết hợp gội và massage |
                """;
        List<ReciprocalRankFusion.FusedChunk> realChunks = List.of(
                new ReciprocalRankFusion.FusedChunk(
                        new com.example.docmind.repository.RetrievedChunk(
                                UUID.fromString("c9481efb-0010-4613-a2e0-fe50e9b99b39"),
                                DOC_ID,
                                List.of("MASSAGE", "Cơ sở Hà Nội"),
                                List.of(),
                                1,
                                new double[]{0, 0, 10, 10},
                                realNangSenMassageCatalog,
                                0.96
                        ),
                        0.96
                )
        );
        List<String> kws = List.of("massage", "giá");
        ConversationState st = ConversationState.hydrateForTurn(
                PAGE_ID, "u-p04", "c-p04", true, "Xin giá dịch vụ massage", List.of(), kws, realChunks
        ).enrichWithCurrentMessage("Xin giá dịch vụ massage", kws);

        var badDuration = ReplyContractValidator.validateAndEnforce(
                List.of(new ReplyBubble("Dạ mình muốn tham khảo gói massage 60 phút, 75 phút hay 90 phút để em báo giá chuẩn cho mình ạ?", 0)),
                st, "Xin giá dịch vụ massage",
                PrimaryActionRouter.PrimaryAction.CLARIFY_SERVICE_DURATION, false, 10_000,
                ThinkingMode.FAST_NO_THINK, null
        );
        assertThat(badDuration.errors()).contains("UNGROUNDED_CATALOG_DURATION");
        assertThat(ReplyContractValidator.hasBlockingError(badDuration.errors())).isTrue();

        var validDuration = ReplyContractValidator.validateAndEnforce(
                List.of(new ReplyBubble("Dạ bên em có các gói massage 60 phút, 80 phút và 100 phút, hiện mình đang nhức mỏi vùng nào để em gửi gói hợp nhất ạ?", 0)),
                st, "Xin giá dịch vụ massage",
                PrimaryActionRouter.PrimaryAction.CLARIFY_SERVICE_DURATION, false, 10_000,
                ThinkingMode.FAST_NO_THINK, null
        );
        assertThat(validDuration.errors()).doesNotContain("UNGROUNDED_CATALOG_DURATION");
    }

    @Test
    @DisplayName("P0-5 & P1-6: FAQ giờ mở cửa không tiêm nhầm gói báo giá; <CONVERSATION_HISTORY> không còn ký tự ' | ' gây rò rỉ ra bong bóng")
    void p05_and_p16_openingHoursFaqDoesNotInjectPackageQuoteAndHistoryHasNoPipeDelimiter() {
        List<ReciprocalRankFusion.FusedChunk> chunks = ThinkingModePolicyAndAdaptiveRouterTest.buildTestChunks();
        PromptBuilder.PagePersona persona = ThinkingModePolicyAndAdaptiveRouterTest.buildTestPersona();
        List<String> kws = List.of("mở cửa", "mấy giờ", "massage");

        List<ConversationTurn> history = List.of(
                new ConversationTurn("user", "Tư vấn"),
                new ConversationTurn("assistant", "Dạ chào mình, em là An Nhiên ạ.\nBên em chuyên massage thư giãn.\nHôm nay mình đang mỏi vùng nào ạ?")
        );
        ConversationState st = ConversationState.hydrateForTurn(
                PAGE_ID, "u-p05", "c-p05", false, "Bên mình mở cửa tới mấy giờ ạ?", history, kws, chunks
        ).enrichWithCurrentMessage("Bên mình mở cửa tới mấy giờ ạ?", kws);
        PrimaryActionRouter.RoutingDecision dec = PrimaryActionRouter.route("Bên mình mở cửa tới mấy giờ ạ?", st, chunks, kws);
        assertThat(dec.effectiveResponseAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.SERVICE_DETAIL_AND_FAQ_QA);

        String prompt = FanpageBotPromptBuilder.buildDynamicRuntimePrompt(
                "Bên mình mở cửa tới mấy giờ ạ?",
                "Khách",
                history,
                chunks,
                List.of(),
                persona,
                FanpageBotPromptBuilder.SalesStage.DISCOVERY,
                "mình",
                null,
                List.of(),
                null,
                null,
                List.of(),
                st,
                dec,
                List.of(),
                null,
                false
        );
        int histStart = prompt.indexOf("<CONVERSATION_HISTORY>");
        int histEnd = prompt.indexOf("</CONVERSATION_HISTORY>");
        assertThat(histStart).isGreaterThanOrEqualTo(0);
        String historyBlock = prompt.substring(histStart, histEnd);
        assertThat(historyBlock).doesNotContain(" | ");

        int factsStart = prompt.indexOf("<GROUNDED_TURN_FACTS>");
        int factsEnd = prompt.indexOf("</GROUNDED_TURN_FACTS>");
        if (factsStart >= 0 && factsEnd > factsStart) {
            String factsBlock = prompt.substring(factsStart, factsEnd);
            assertThat(factsBlock).doesNotContain("target_catalog_package_name");
            assertThat(factsBlock).doesNotContain("target_catalog_price");
        }
    }

    @Test
    @DisplayName("P0.1 & P0.2: Tách onset/frequency/duration, đóng pendingQuestionSlot khi khách trả lời 'Thi thoảng bị lâu lâu mất ngủ' và chặn lặp nguyên văn >70% token")
    void p01_and_p02_pendingQuestionSlotClosureAndCrossTurnRepetitionGate() {
        List<ReciprocalRankFusion.FusedChunk> chunks = ThinkingModePolicyAndAdaptiveRouterTest.buildTestChunks();
        List<String> kws = List.of("đau đầu", "mỏi gối", "tê tay", "mất ngủ", "massage");

        List<ConversationTurn> historyTurn3 = List.of(
                new ConversationTurn("user", "Xin giá"),
                new ConversationTurn("assistant", "Dạ chào mình, em là An Nhiên ạ.\nBên em chuyên massage thư giãn.\nMình đang muốn thư giãn vùng nào ạ?"),
                new ConversationTurn("user", "Đau đầu mỏi gối tê tay"),
                new ConversationTurn("assistant", "Em hiểu rồi ạ. Mình đang cảm thấy đau đầu, mỏi gối, tê tay. Tình trạng này bắt đầu từ khi nào ạ?")
        );
        String msgTurn3 = "Thi thoảng bị lâu lâu mất ngủ";
        ConversationState st3 = ConversationState.hydrateForTurn(
                PAGE_ID, "u-p02", "c-p02", false, msgTurn3, historyTurn3, kws, chunks
        ).enrichWithCurrentMessage(msgTurn3, kws);

        assertThat(st3.frequency()).isEqualTo("thi thoảng");
        assertThat(st3.frequencyCategory()).isEqualTo("OCCASIONAL");
        assertThat(st3.knownSymptoms()).contains("mất ngủ");
        assertThat(st3.pendingQuestionSlot()).isNull();

        PrimaryActionRouter.RoutingDecision dec3 = PrimaryActionRouter.route(msgTurn3, st3, chunks, kws);
        assertThat(dec3.effectiveResponseAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE);

        // Chặn nhại lại danh sách triệu chứng đã xác nhận ở đầu lượt
        var repeatSymptomRes = ReplyContractValidator.validateAndEnforce(
                List.of(new ReplyBubble("Em hiểu rồi ạ. Mình đang cảm thấy đau đầu, mỏi gối, tê tay, và thỉnh thoảng bị mất ngủ.", 0)),
                st3, msgTurn3, dec3.effectiveResponseAction(), false, 10000, ThinkingMode.FAST_NO_THINK, null
        );
        assertThat(repeatSymptomRes.errors()).contains("FORBIDDEN_SYMPTOM_LIST_PARROTING_REPEAT");

        // Chặn lặp câu quảng cáo/câu mời trải nghiệm có độ trùng lặp > 70% token so với lượt liền trước
        List<ConversationTurn> historyTurn5 = List.of(
                new ConversationTurn("user", "Xin giá"),
                new ConversationTurn("assistant", "Dạ chào mình ạ."),
                new ConversationTurn("user", "Đau đầu mỏi gối tê tay"),
                new ConversationTurn("assistant", "Em hiểu rồi ạ. Tình trạng này bắt đầu từ khi nào ạ?"),
                new ConversationTurn("user", "Ơ bảo thi thoảmg bị mà"),
                new ConversationTurn("assistant", "Dạ với tình trạng đau đầu, mỏi gối, em gợi ý gói Massage cơ bản 60 phút giá 399k ạ.\n\nGói Massage cơ bản sẽ giúp mình thư giãn toàn thân, nhẹ đầu và dễ vào giấc hơn nhờ liệu trình êm sâu và không gian yên tĩnh. Mình muốn thử trải nghiệm một buổi không ạ?")
        );
        ConversationState st5 = ConversationState.hydrateForTurn(
                PAGE_ID, "u-p01-t5", "c-p01-t5", false, "Mà đắt quá", historyTurn5, kws, chunks
        ).enrichWithCurrentMessage("Mà đắt quá", kws);
        var repeatPromoRes = ReplyContractValidator.validateAndEnforce(
                List.of(
                        new ReplyBubble("Dạ em hiểu mình cân nhắc chi phí ạ. Gói Massage cơ bản sẽ giúp mình thư giãn toàn thân, nhẹ đầu và dễ vào giấc hơn nhờ liệu trình êm sâu và không gian yên tĩnh.", 0),
                        new ReplyBubble("Mình có muốn thử trải nghiệm một buổi không ạ?", 2000)
                ),
                st5, "Mà đắt quá", PrimaryActionRouter.PrimaryAction.HANDLE_PRICE_OBJECTION_FIRST_TIME, false, 10000, ThinkingMode.FAST_NO_THINK, null
        );
        assertThat(repeatPromoRes.errors()).contains("CROSS_TURN_SENTENCE_REPETITION");
    }

    @Test
    @DisplayName("P0.3 & P0.4: Tách 2 tầng STYLE_REPAIR, tách packageInterestAccepted vs COLLECTING_INFO và bảo toàn vị trí phễu ở Turn 6 -> Turn 7")
    void p03_and_p04_styleRepairAndFunnelPreservationAcrossTurn6AndTurn7() {
        List<ReciprocalRankFusion.FusedChunk> chunks = ThinkingModePolicyAndAdaptiveRouterTest.buildTestChunks();
        List<String> kws = List.of("đau đầu", "mỏi gối", "massage");

        // Turn 5: "Hiểu j mà cứ nhại đi nhạu lại thế" -> Nhận diện STYLE_REPAIR, cấm kèm bán hàng
        String msgTurn5 = "Hiểu j mà cứ nhại đi nhạu lại thế";
        assertThat(SentimentIntentLayer.isWordingOrExpressionFeedback(msgTurn5)).isTrue();

        List<ConversationTurn> historyTurn5 = List.of(
                new ConversationTurn("user", "Xin giá"),
                new ConversationTurn("assistant", "Dạ chào mình ạ."),
                new ConversationTurn("user", "Đau đầu mỏi gối"),
                new ConversationTurn("assistant", "Dạ với tình trạng đau đầu, mỏi gối, em gợi ý gói Massage cơ bản 60 phút giá 399k ạ.\n\nGói giúp thư giãn toàn thân. Mình muốn thử trải nghiệm một buổi không ạ?")
        );
        ConversationState st5 = ConversationState.hydrateForTurn(
                PAGE_ID, "u-p03", "c-p03", false, msgTurn5, historyTurn5, kws, chunks
        ).enrichWithCurrentMessage(msgTurn5, kws);
        PrimaryActionRouter.RoutingDecision dec5 = PrimaryActionRouter.route(msgTurn5, st5, chunks, kws);
        assertThat(dec5.detectedIntent()).isEqualTo("WORDING_FEEDBACK_REPAIR");

        // Turn 6: "Uk thử xem" -> packageInterestAccepted = true, bookingStatus = COLLECTING_INFO; chặn "Em vui mừng khi..."
        String msgTurn6 = "Uk thử xem";
        List<ConversationTurn> historyTurn6 = List.of(
                new ConversationTurn("user", "Xin giá"),
                new ConversationTurn("assistant", "Dạ với tình trạng của mình, em gợi ý gói Massage cơ bản 60 phút giá 399k ạ."),
                new ConversationTurn("user", msgTurn5),
                new ConversationTurn("assistant", "Dạ em cảm ơn mình đã nhắc thẳng thắn ạ, để em nói chuyện ngắn gọn và tự nhiên hơn nha.")
        );
        ConversationState st6 = ConversationState.hydrateForTurn(
                PAGE_ID, "u-p04", "c-p04", false, msgTurn6, historyTurn6, kws, chunks
        ).enrichWithCurrentMessage(msgTurn6, kws);
        assertThat(st6.packageInterestAccepted()).isTrue();
        assertThat(st6.bookingStatus()).isEqualTo("COLLECTING_INFO");
        PrimaryActionRouter.RoutingDecision dec6 = PrimaryActionRouter.route(msgTurn6, st6, chunks, kws);
        assertThat(dec6.effectiveResponseAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.CONFIRM_BOOKING_DETAILS);

        var machineTransTrialRes = ReplyContractValidator.validateAndEnforce(
                List.of(new ReplyBubble("Em vui mừng khi mình muốn thử gói Massage cơ bản 60 phút giá 399k ạ. Mình dự kiến ghé khung giờ nào ạ?", 0)),
                st6, msgTurn6, dec6.effectiveResponseAction(), false, 10000, ThinkingMode.FAST_NO_THINK, null
        );
        assertThat(machineTransTrialRes.errors()).contains("FORBIDDEN_MACHINE_TRANSLATION_TRIAL_ACCEPT");

        // Turn 7: "Lạy chúa k ai nói là 'em vui mừng khi…' khi khách đồng ý cả gà vậy" -> Giữ nguyên COLLECTING_INFO, chặn lùi phễu hỏi lại thử gói
        String msgTurn7 = "Lạy chúa k ai nói là 'em vui mừng khi…' khi khách đồng ý cả gà vậy";
        assertThat(SentimentIntentLayer.isWordingOrExpressionFeedback(msgTurn7)).isTrue();
        List<ConversationTurn> historyTurn7 = new java.util.ArrayList<>(historyTurn6);
        historyTurn7.add(new ConversationTurn("user", msgTurn6));
        historyTurn7.add(new ConversationTurn("assistant", "Dạ vâng ạ. Mình dự kiến ghé bên em vào khung giờ nào để em hỗ trợ tiếp nhé?"));

        ConversationState st7 = ConversationState.hydrateForTurn(
                PAGE_ID, "u-p04", "c-p04", false, msgTurn7, historyTurn7, kws, chunks
        ).enrichWithCurrentMessage(msgTurn7, kws);
        assertThat(st7.packageInterestAccepted()).isTrue();
        assertThat(st7.bookingStatus()).isEqualTo("COLLECTING_INFO");

        PrimaryActionRouter.RoutingDecision dec7 = PrimaryActionRouter.route(msgTurn7, st7, chunks, kws);
        assertThat(dec7.detectedIntent()).isEqualTo("WORDING_FEEDBACK_PRESERVE_BOOKING_FUNNEL");

        var regressedFunnelRes = ReplyContractValidator.validateAndEnforce(
                List.of(
                        new ReplyBubble("Dạ em cảm ơn mình đã góp ý ạ.", 0),
                        new ReplyBubble("Mình muốn trải nghiệm buổi này không ạ?", 2000)
                ),
                st7, msgTurn7, dec7.effectiveResponseAction(), false, 10000, ThinkingMode.FAST_NO_THINK, null
        );
        assertThat(regressedFunnelRes.errors()).contains("FORBIDDEN_FUNNEL_REGRESSION_AFTER_ACCEPT");

        var preservedFunnelRes = ReplyContractValidator.validateAndEnforce(
                List.of(new ReplyBubble("Dạ cảm ơn mình đã góp ý thẳng thắn ạ, em xin rút kinh nghiệm nói chuyện tự nhiên hơn nha. Mình dự kiến ghé bên em vào khung giờ nào để em hỗ trợ tiếp ạ?", 0)),
                st7, msgTurn7, dec7.effectiveResponseAction(), false, 10000, ThinkingMode.FAST_NO_THINK, null
        );
        assertThat(preservedFunnelRes.errors()).doesNotContain("FORBIDDEN_FUNNEL_REGRESSION_AFTER_ACCEPT", "FORBIDDEN_SALES_PITCH_ON_WORDING_FEEDBACK");
    }

    @Test
    @DisplayName("P1.5 & P1.6: Chê đắt lần 1 tối đa 2 bong bóng & cấm tự đổi gói; lịch sử giữ nguyên văn không cắt substring(0,260); cho phép câu hỏi xác nhận hợp lệ")
    void p15_and_p16_priceObjectionStep1AndTurnBoundaryHistoryAndConfirmationQuestion() {
        List<ReciprocalRankFusion.FusedChunk> chunks = ThinkingModePolicyAndAdaptiveRouterTest.buildTestChunks();
        PromptBuilder.PagePersona persona = ThinkingModePolicyAndAdaptiveRouterTest.buildTestPersona();
        List<String> kws = List.of("đau đầu", "mỏi gối", "massage");

        List<ConversationTurn> history = List.of(
                new ConversationTurn("user", "Xin giá"),
                new ConversationTurn("assistant", "Dạ với tình trạng của mình, em gợi ý gói Massage cơ bản 60 phút giá 399k ạ.")
        );
        ConversationState st = ConversationState.hydrateForTurn(
                PAGE_ID, "u-p15", "c-p15", false, "Mà đắt quá", history, kws, chunks
        ).enrichWithCurrentMessage("Mà đắt quá", kws);

        // Chặn 3 bong bóng và chặn tự đề xuất đổi sang gói khác ở lần chê đắt đầu tiên
        var step1InvalidRes = ReplyContractValidator.validateAndEnforce(
                List.of(
                        new ReplyBubble("Dạ em hiểu mình cân nhắc chi phí ạ.", 0),
                        new ReplyBubble("Gói 60 phút chăm sóc toàn thân kèm ăn nhẹ và phòng chung có rèm riêng ạ.", 2000),
                        new ReplyBubble("Hoặc em gợi ý đổi sang gói khác phù hợp hơn với ngân sách của mình nhé?", 2000)
                ),
                st, "Mà đắt quá", PrimaryActionRouter.PrimaryAction.HANDLE_PRICE_OBJECTION_FIRST_TIME, false, 10000, ThinkingMode.FAST_NO_THINK, null
        );
        assertThat(step1InvalidRes.errors()).contains("TOO_MANY_BUBBLES_ON_PRICE_OBJECTION_STEP1", "FORBIDDEN_PREMATURE_DOWNSELL_OFFER_STEP1");

        // Cho phép câu hỏi xác nhận khung giờ hợp lệ ("... đúng không ạ?"), nhưng chặn câu hỏi xác nhận nhại triệu chứng hiển nhiên
        var validBookingConfirm = ReplyContractValidator.validateAndEnforce(
                List.of(new ReplyBubble("Dạ mình muốn đặt khung giờ 18h tối nay tại cơ sở Bích Câu đúng không ạ?", 0)),
                st, "Cho anh đặt 18h tối nay", PrimaryActionRouter.PrimaryAction.CONFIRM_BOOKING_DETAILS, false, 10000, ThinkingMode.FAST_NO_THINK, null
        );
        assertThat(validBookingConfirm.errors()).doesNotContain("FORBIDDEN_AWKWARD_CLOSING_QUESTION");

        var parrotingSymptomConfirm = ReplyContractValidator.validateAndEnforce(
                List.of(new ReplyBubble("Dạ mình đang bị đau đầu và mỏi gối phải không ạ?", 0)),
                st, "Đau đầu mỏi gối", PrimaryActionRouter.PrimaryAction.ACKNOWLEDGE_AND_ASK_PREFERENCE, false, 10000, ThinkingMode.FAST_NO_THINK, null
        );
        assertThat(parrotingSymptomConfirm.errors()).contains("FORBIDDEN_AWKWARD_CLOSING_QUESTION");

        // Kiểm tra lịch sử > 300 ký tự không bị cắt cụt bằng substring(0, 260) + "..."
        String longTurnWithQuoteAtEnd = "Mình đã chia sẻ rất kỹ tình trạng từ đầu buổi trò chuyện rồi, công việc văn phòng ngồi máy tính nhiều nên thỉnh thoảng mới bị nhức mỏi thôi chứ không phải lúc nào cũng đau liên tục, thế mà bạn lại trả lời câu máy móc kiểu 'em vui mừng khi mình muốn thử gói' nghe rất thiếu tự nhiên";
        assertThat(longTurnWithQuoteAtEnd.length()).isGreaterThan(260);
        List<ConversationTurn> longHistory = List.of(
                new ConversationTurn("user", longTurnWithQuoteAtEnd),
                new ConversationTurn("assistant", "Dạ cảm ơn mình đã góp ý thẳng thắn ạ.")
        );
        PrimaryActionRouter.RoutingDecision dec = PrimaryActionRouter.route("Mà đắt quá", st, chunks, kws);
        String prompt = FanpageBotPromptBuilder.buildDynamicRuntimePrompt(
                "Mà đắt quá", "Khách", longHistory, chunks, List.of(), persona,
                FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION, "mình", null, List.of(),
                null, null, List.of(), st, dec, List.of(), null, false
        );
        assertThat(prompt).contains("'em vui mừng khi mình muốn thử gói' nghe rất thiếu tự nhiên");
    }
}
