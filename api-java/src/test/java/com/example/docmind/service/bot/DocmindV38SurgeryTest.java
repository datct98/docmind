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
        for (String name : List.of("sanitizeMechanicalOpenersInBubbles", "rewriteOrStripMechanicalOpener",
                "rewriteSingleSentenceMechanicalOpener", "fixBoundaryViolations")) {
            assertThat(v).as(name).doesNotContain(name + "(");
            assertThat(s).as(name).doesNotContain(name + "(");
        }
        int a = s.indexOf("private static List<FanpageBotResponse.ReplyBubble> buildCandidateBubblesFromLlmOutput(");
        int b = s.indexOf("private static boolean satisfiesDbSkillAndActionContract(", a);
        String assembly = s.substring(a, b);
        assertThat(assembly).doesNotContain("replaceAll(\"(?iU)\\\\s*\\\\+\\\\s*\"")
                .doesNotContain("stripRedundantGreetingPrefixPreservingGrammar")
                .doesNotContain("HonorificConsistencyHook");
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
                "src/main/java/com/example/docmind/service/bot/FanpageBotPromptBuilder.java")) {
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
}
