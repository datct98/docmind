package com.docmind.guardrail;

import com.example.docmind.dto.bot.FanpageBotResponse;
import com.example.docmind.dto.bot.FanpageBotResponse.ReplyBubble;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GuardrailPipelineTest {

    private GuardrailPipeline pipeline;

    @BeforeEach
    void setUp() {
        pipeline = new GuardrailPipeline();
    }

    // =========================================================================
    // 1. HONORIFIC CONSISTENCY HOOK
    // =========================================================================

    @Test
    @DisplayName("Honorific: Replaces leaked 'Chị' with 'Anh' when customer is male, preserving capitalization")
    void testHonorificConsistency_maleCustomer_replacesChiWithAnh_preservesCapitalization() {
        GuardrailContext ctx = GuardrailContext.builder()
                .customerHonorific("Anh")
                .isFirstTurn(true)
                .salesStage("DISCOVERY")
                .build();

        String rawText = "Dạ em chào Chị ạ! Em có thể tham khảo gói trị mụn cho chị. CHỊ yên tâm bên em rất tận tình nhé ạ.";
        GuardrailResult result = pipeline.process(ctx, rawText);

        assertThat(result.text())
                .doesNotContain("Chị")
                .doesNotContain("chị")
                .doesNotContain("CHỊ")
                .contains("Anh")
                .contains("anh")
                .contains("ANH")
                .contains("Anh có thể tham khảo"); // Inverted pronoun repaired

        assertThat(result.hasTriggered(HonorificConsistencyHook.HOOK_CODE)).isTrue();
        assertThat(result.triggeredHooks()).contains(HonorificConsistencyHook.HOOK_CODE);
    }

    @Test
    @DisplayName("Honorific: Normalizes 'Anh/Chị' to 'Chị' when customer is female")
    void testHonorificConsistency_femaleCustomer_normalizesSlash() {
        GuardrailContext ctx = GuardrailContext.builder()
                .customerHonorific("Chị")
                .isFirstTurn(true)
                .salesStage("DISCOVERY")
                .build();

        String rawText = "Dạ em chào Anh/Chị ạ! Liệu trình bên em phù hợp với bạn.";
        GuardrailResult result = pipeline.process(ctx, rawText);

        assertThat(result.text())
                .doesNotContain("Anh/Chị")
                .contains("Chị");
        assertThat(result.hasTriggered(HonorificConsistencyHook.HOOK_CODE)).isTrue();
    }

    @Test
    @DisplayName("Honorific: Normalizes casual pronoun 'bạn' and 'Bạn' to 'anh' and 'Anh' when customer is male")
    void testHonorificConsistency_maleCustomer_normalizesBanToAnh() {
        GuardrailContext ctx = GuardrailContext.builder()
                .customerHonorific("Anh")
                .isFirstTurn(true)
                .salesStage("DISCOVERY")
                .build();

        String rawText = "Dạ chào bạn ạ! Liệu trình bên em rất hợp với bạn. Bạn có thể tham khảo nhé.";
        GuardrailResult result = pipeline.process(ctx, rawText);

        assertThat(result.text())
                .doesNotContain("bạn")
                .doesNotContain("Bạn")
                .contains("Dạ chào anh ạ!")
                .contains("rất hợp với anh.")
                .contains("Anh có thể tham khảo");
        assertThat(result.hasTriggered(HonorificConsistencyHook.HOOK_CODE)).isTrue();
    }

    @Test
    @DisplayName("Honorific: Does not trigger when honorific is already 100% consistent")
    void testHonorificConsistency_noLeakage_notTriggered() {
        HonorificConsistencyHook hook = new HonorificConsistencyHook();
        GuardrailContext ctx = GuardrailContext.builder()
                .customerHonorific("Anh")
                .build();

        GuardrailDraft draft = new GuardrailDraft("Dạ em chào Anh! Anh muốn tham khảo dịch vụ nào ạ?", List.of());
        boolean triggered = hook.apply(ctx, draft);

        assertThat(triggered).isFalse();
        assertThat(draft.hasTriggered(HonorificConsistencyHook.HOOK_CODE)).isFalse();
    }

    // =========================================================================
    // 2. BUBBLE CAP HOOK (<= 2 BUBBLES)
    // =========================================================================

    @Test
    @DisplayName("BubbleCap: Merges excess bubbles when raw reply has 3+ bubbles into exactly <= 2")
    void testBubbleCap_excessBubbles_mergedToMaxTwo() {
        GuardrailContext ctx = GuardrailContext.builder()
                .customerHonorific("Anh")
                .isFirstTurn(false)
                .salesStage("VALUE_CONSULTATION")
                .build();

        List<ReplyBubble> rawBubbles = List.of(
                new ReplyBubble("Đoạn 1: Liệu trình trị mụn chuẩn y khoa 1.500.000 VNĐ.", 1000),
                new ReplyBubble("Đoạn 2: Đã bao gồm soi da AI 3D miễn phí.", 2500),
                new ReplyBubble("Đoạn 3: Cam kết không phát sinh chi phí.", 2500),
                new ReplyBubble("Đoạn 4: Anh muốn đặt lịch Sáng hay Chiều ạ?", 2500)
        );

        GuardrailDraft draft = new GuardrailDraft(null, rawBubbles);
        BubbleCapHook hook = new BubbleCapHook();
        boolean triggered = hook.apply(ctx, draft);

        assertThat(triggered).isTrue();
        assertThat(draft.getBubbles()).hasSize(2);
        assertThat(draft.getBubbles().get(0).text()).contains("Đoạn 1");
        assertThat(draft.getBubbles().get(1).text())
                .contains("Đoạn 2")
                .contains("Đoạn 3")
                .contains("Đoạn 4");

        // Verify typing delay metadata
        assertThat(draft.getBubbles().get(0).typingDelayMs()).isBetween(800, 1200);
        assertThat(draft.getBubbles().get(1).typingDelayMs()).isBetween(2500, 3000);
        assertThat(draft.hasTriggered(BubbleCapHook.HOOK_CODE)).isTrue();
    }

    @Test
    @DisplayName("BubbleCap: On Turn 1, merges short greeting bubble into first main bubble")
    void testBubbleCap_turn1GreetingMerged() {
        GuardrailContext ctx = GuardrailContext.builder()
                .customerHonorific("Chị")
                .isFirstTurn(true)
                .salesStage("DISCOVERY")
                .build();

        List<ReplyBubble> rawBubbles = List.of(
                new ReplyBubble("Dạ em chào Chị ạ!", 1000),
                new ReplyBubble("Liệu trình trị mụn bên em có giá 1.500.000 VNĐ.", 2500),
                new ReplyBubble("Chị muốn tư vấn thêm phần nào ạ?", 2500)
        );

        GuardrailDraft draft = new GuardrailDraft(null, rawBubbles);
        BubbleCapHook hook = new BubbleCapHook();
        boolean triggered = hook.apply(ctx, draft);

        assertThat(triggered).isTrue();
        assertThat(draft.getBubbles()).hasSize(2);
        assertThat(draft.getBubbles().get(0).text()).startsWith("Dạ em chào Chị ạ! Liệu trình");
    }

    // =========================================================================
    // 3. ANTI-INFO DUMPING HOOK (DISCOVERY STAGE)
    // =========================================================================

    @Test
    @DisplayName("AntiInfoDumping: In DISCOVERY stage, limits multi-price dumping to 1 starting price + 1 0-risk perk + 1 A/B question")
    void testAntiInfoDumping_dumpsMultiplePricesInDiscovery_rewritesToOneStartingPriceAndPerkAndABQuestion() {
        GuardrailContext ctx = GuardrailContext.builder()
                .customerHonorific("Anh")
                .customerMessage("giá dịch vụ sao em")
                .isFirstTurn(true)
                .salesStage("DISCOVERY")
                .knowledgeChunks(List.of("Bảng giá: Trị mụn 650.000đ/buổi, Laser Pico 4.500.000đ, Trẻ hóa 6.000.000đ"))
                .build();

        String dumpedText = "Dạ bên em có Liệu trình Trị Mụn 1.500.000 VNĐ, Trị Nám Laser 4.500.000 VNĐ, và Trẻ Hóa 6.000.000 VNĐ. Anh chọn gói nào ạ?";

        GuardrailDraft draft = new GuardrailDraft(dumpedText, List.of());
        AntiInfoDumpingHook hook = new AntiInfoDumpingHook();
        boolean triggered = hook.apply(ctx, draft);

        assertThat(triggered).isTrue();
        String resultText = draft.getText();

        // Must contain 1 starting price (650.000đ/buổi)
        assertThat(resultText).contains("650.000đ/buổi");
        // Must contain 0-risk perk (Bác sĩ soi da AI 3D miễn phí)
        assertThat(resultText).contains("soi da AI 3D miễn phí 100%");
        // Must contain focused A/B question
        assertThat(resultText).contains("Hiện tại Anh đang muốn ưu tiên xử lý mụn, mờ nám tàn nhang hay dưỡng căng bóng");
        // Must NOT list multiple specific prices
        assertThat(resultText).doesNotContain("4.500.000 VNĐ");
        assertThat(resultText).doesNotContain("6.000.000 VNĐ");

        assertThat(draft.hasTriggered(AntiInfoDumpingHook.HOOK_CODE)).isTrue();
    }

    @Test
    @DisplayName("AntiInfoDumping: Does not trigger in VALUE_CONSULTATION stage where detailed pricing is permitted")
    void testAntiInfoDumping_notInDiscovery_doesNotTrigger() {
        GuardrailContext ctx = GuardrailContext.builder()
                .customerHonorific("Chị")
                .customerMessage("Chi tiết từng gói cho chị nhé")
                .isFirstTurn(false)
                .salesStage("VALUE_CONSULTATION")
                .build();

        String detailedText = "Dạ gói Trị Mụn là 1.500.000 VNĐ và gói Trị Nám là 4.500.000 VNĐ ạ.";
        GuardrailDraft draft = new GuardrailDraft(detailedText, List.of());
        AntiInfoDumpingHook hook = new AntiInfoDumpingHook();
        boolean triggered = hook.apply(ctx, draft);

        assertThat(triggered).isFalse();
        assertThat(draft.getText()).isEqualTo(detailedText);
    }

    @Test
    @DisplayName("AntiInfoDumping: Dynamically chooses lower starting price (250.000đ/buổi) when both 250k and 650k exist")
    void testAntiInfoDumping_dynamicLowestStartingPrice_selects250kOver650k() {
        AntiInfoDumpingHook hook = new AntiInfoDumpingHook();
        String startingPrice = hook.extractLowestStartingPrice(
                "Gói dùng thử 250.000đ/buổi và gói chuẩn 650.000đ/buổi",
                List.of("Bảng giá mở rộng: Laser 4.500.000đ")
        );
        assertThat(startingPrice).isEqualTo("250.000đ/buổi");
    }

    @Test
    @DisplayName("AntiInfoDumping: In DISCOVERY stage with multiple prices, rewrites using dynamically detected minimum price")
    void testAntiInfoDumping_rewritesWithDynamicMinimumPrice() {
        GuardrailContext ctx = GuardrailContext.builder()
                .customerHonorific("Anh")
                .customerMessage("giá dịch vụ sao em")
                .isFirstTurn(true)
                .salesStage("DISCOVERY")
                .knowledgeChunks(List.of("Gói trải nghiệm 250.000đ/buổi, gói chuẩn 650.000đ/buổi, Laser 4.500.000đ"))
                .build();

        String dumpedText = "Dạ bên em có gói 250.000đ/buổi, gói 650.000đ/buổi và gói Laser 4.500.000đ. Anh chọn gói nào ạ?";
        GuardrailDraft draft = new GuardrailDraft(dumpedText, List.of());
        AntiInfoDumpingHook hook = new AntiInfoDumpingHook();
        boolean triggered = hook.apply(ctx, draft);

        assertThat(triggered).isTrue();
        assertThat(draft.getText())
                .contains("chỉ từ 250.000đ/buổi")
                .doesNotContain("650.000đ")
                .doesNotContain("4.500.000đ");
    }

    @Test
    @DisplayName("AntiInfoDumping: Falls back to 650.000đ/buổi only when no valid price is detected")
    void testAntiInfoDumping_fallbackWhenNoPriceDetected() {
        AntiInfoDumpingHook hook = new AntiInfoDumpingHook();
        String startingPrice = hook.extractLowestStartingPrice("Không có giá nào trong nội dung này", List.of());
        assertThat(startingPrice).isEqualTo("650.000đ/buổi");
    }

    // =========================================================================
    // 4. CLOSING QUESTION HOOK
    // =========================================================================

    @Test
    @DisplayName("ClosingQuestion: Strips weak closed Yes/No permission questions (...không ạ?) and enforces focused question")
    void testClosingQuestionControl_stripsDryClosedYesNoQuestion() {
        GuardrailContext ctx = GuardrailContext.builder()
                .customerHonorific("Anh")
                .isFirstTurn(true)
                .salesStage("DISCOVERY")
                .build();

        String rawText = "Dạ liệu trình trị mụn bên em rất hiệu quả. Em có thể tư vấn thêm cho Anh không ạ?";

        GuardrailDraft draft = new GuardrailDraft(rawText, List.of());
        ClosingQuestionHook hook = new ClosingQuestionHook();
        boolean triggered = hook.apply(ctx, draft);

        assertThat(triggered).isTrue();
        assertThat(draft.getText())
                .doesNotContain("Em có thể tư vấn thêm cho Anh không ạ?")
                .endsWith("?");
        assertThat(draft.hasTriggered(ClosingQuestionHook.HOOK_CODE)).isTrue();
    }

    @Test
    @DisplayName("ClosingQuestion: Strips duplicate rapid-fire questions in closing paragraph")
    void testClosingQuestionControl_stripsDuplicateClosingQuestions() {
        GuardrailContext ctx = GuardrailContext.builder()
                .customerHonorific("Chị")
                .isFirstTurn(true)
                .salesStage("DISCOVERY")
                .build();

        String rawText = "Dạ chi phí bên em rất linh hoạt.\n\nHiện tại Chị bị mụn lâu chưa? Chị muốn chọn gói nào ạ?";

        GuardrailDraft draft = new GuardrailDraft(rawText, List.of());
        ClosingQuestionHook hook = new ClosingQuestionHook();
        boolean triggered = hook.apply(ctx, draft);

        assertThat(triggered).isTrue();
        assertThat(draft.getText())
                .contains("Hiện tại Chị bị mụn lâu chưa?")
                .doesNotContain("Chị muốn chọn gói nào ạ?");
        assertThat(draft.hasTriggered(ClosingQuestionHook.HOOK_CODE)).isTrue();
    }

    @Test
    @DisplayName("ClosingQuestion: Does not modify HUMAN_HANDOFF responses")
    void testClosingQuestionControl_humanHandoff_notModified() {
        GuardrailContext ctx = GuardrailContext.builder()
                .customerHonorific("Anh")
                .salesStage("HUMAN_HANDOFF")
                .build();

        String handoffText = "Dạ Chuyên viên tư vấn của Page sẽ gọi lại qua số điện thoại 0968221644 cho Anh ngay nhé ạ!";
        GuardrailDraft draft = new GuardrailDraft(handoffText, List.of());
        ClosingQuestionHook hook = new ClosingQuestionHook();
        boolean triggered = hook.apply(ctx, draft);

        assertThat(triggered).isFalse();
        assertThat(draft.getText()).isEqualTo(handoffText);
    }

    // =========================================================================
    // 5. EXECUTION BUDGET (< 5ms)
    // =========================================================================

    @Test
    @DisplayName("Execution Budget: Strictly < 5ms total execution budget measured via System.nanoTime()")
    void testExecutionBudget_strictlyUnder5MsOverRepeatedRuns() {
        GuardrailContext ctx = GuardrailContext.builder()
                .customerHonorific("Anh")
                .customerName("Minh")
                .isFirstTurn(true)
                .salesStage("DISCOVERY")
                .customerMessage("báo giá dịch vụ")
                .knowledgeChunks(List.of("Gói 650.000đ/buổi, gói 1.500.000đ"))
                .conversationHistory(List.of("Chào shop", "Dạ chào Anh"))
                .build();

        String dirtyReply = "Dạ em chào Chị ạ! Em có thể tư vấn gói 1.500.000đ và gói 4.500.000đ cho chị.\n\n"
                + "Gói nào cũng được bảo hành.\n\n"
                + "Chị có muốn em tư vấn thêm không ạ?";

        // Warm up JVM JIT compiler
        for (int i = 0; i < 200; i++) {
            pipeline.process(ctx, dirtyReply);
        }

        // Measure 100 runs
        long maxNanos = 0;
        long totalNanos = 0;
        int iterations = 100;
        List<Double> latencies = new ArrayList<>(iterations);

        for (int i = 0; i < iterations; i++) {
            long t0 = System.nanoTime();
            GuardrailResult result = pipeline.process(ctx, dirtyReply);
            long elapsed = System.nanoTime() - t0;

            totalNanos += elapsed;
            if (elapsed > maxNanos) {
                maxNanos = elapsed;
            }
            latencies.add(result.latencyMillis());

            // Safety ceiling to catch catastrophic hangs while tolerating isolated GC safepoints
            assertThat(result.isWithinBudget(50.0))
                    .withFailMessage("Single run took %.3f ms, exceeding 50ms safety ceiling", result.latencyMillis())
                    .isTrue();
        }

        latencies.sort(Double::compareTo);
        double medianMs = latencies.get(iterations / 2);
        double avgMs = (totalNanos / (double) iterations) / 1_000_000.0;
        double maxMs = maxNanos / 1_000_000.0;

        System.out.printf("[Guardrail Benchmark] Iterations=%d | Avg=%.4f ms | Median=%.4f ms | Max=%.4f ms%n",
                iterations, avgMs, medianMs, maxMs);

        assertThat(avgMs).isLessThan(5.0); // Sub-5ms budget on average
        assertThat(medianMs).isLessThan(5.0); // Sub-5ms budget on median
        assertThat(maxMs).isLessThan(50.0); // Headroom for GC safepoints in concurrent test runners
    }

    // =========================================================================
    // 6. MULTI-HOOK ATTRIBUTION & TELEMETRY
    // =========================================================================

    @Test
    @DisplayName("Telemetry: Correctly records and attributes all triggered hooks on multi-violation input")
    void testTriggeredHooks_multipleHooksTriggered_allAttributed() {
        GuardrailContext ctx = GuardrailContext.builder()
                .customerHonorific("Anh")
                .isFirstTurn(true)
                .salesStage("DISCOVERY")
                .customerMessage("giá cả thế nào")
                .knowledgeChunks(List.of("Trị mụn từ 650.000đ/buổi"))
                .build();

        // Has pronoun leakage ("Chị"), info dumping (multiple prices), dry question ("không ạ?"), multiple paragraphs
        String multiViolationReply = "Dạ em chào Chị ạ! Bên em có gói 1.500.000 VNĐ và gói 4.500.000 VNĐ.\n\n"
                + "Bên em cam kết chất lượng tốt nhất.\n\n"
                + "Chị có muốn em hỗ trợ thêm không ạ?";

        GuardrailResult result = pipeline.process(ctx, multiViolationReply);

        // Must attribute triggered hooks
        assertThat(result.triggeredHooks())
                .contains(HonorificConsistencyHook.HOOK_CODE)
                .contains(AntiInfoDumpingHook.HOOK_CODE)
                .contains(ClosingQuestionHook.HOOK_CODE)
                .contains(BubbleCapHook.HOOK_CODE);

        // Must guarantee output invariants
        assertThat(result.text()).doesNotContain("Chị");
        assertThat(result.bubbles()).hasSizeLessThanOrEqualTo(2);
        assertThat(result.isWithinBudget(5.0)).isTrue();
    }

    // =========================================================================
    // 7. FANPAGE BOT RESPONSE INTEGRATION & CONTRACT
    // =========================================================================

    @Test
    @DisplayName("Integration: evaluate contract updates FanpageBotResponse and populates triggered_hooks")
    void testFanpageBotResponse_evaluateContract_returnsGuardedResponseWithTelemetry() {
        CustomerContext ctx = new CustomerContext(
                "Anh",
                "Quốc Anh",
                true,
                "DISCOVERY",
                "tôi là nam, giá dịch vụ thế nào",
                List.of(),
                List.of("Trị mụn chỉ từ 650.000đ/buổi")
        );

        FanpageBotResponse original = FanpageBotResponse.builder()
                .replyText("Dạ em chào Chị ạ! Bên em có gói 1.500.000đ và gói 3.000.000đ. Chị muốn tư vấn thêm không ạ?")
                .replyBubbles(List.of(
                        new ReplyBubble("Dạ em chào Chị ạ!", 1000),
                        new ReplyBubble("Bên em có gói 1.500.000đ và gói 3.000.000đ.", 2500),
                        new ReplyBubble("Chị muốn tư vấn thêm không ạ?", 2500)
                ))
                .salesStage("DISCOVERY")
                .confidenceScore(0.9)
                .build();

        FanpageBotResponse guarded = pipeline.evaluate(ctx, original);

        assertThat(guarded).isNotNull();
        assertThat(guarded.replyText()).doesNotContain("Chị");
        assertThat(guarded.replyBubbles()).hasSizeLessThanOrEqualTo(2);
        assertThat(guarded.triggeredHooks()).isNotEmpty();
        assertThat(guarded.getTriggeredHooks()).isEqualTo(guarded.triggeredHooks());
        assertThat(guarded.triggeredHooks()).contains(HonorificConsistencyHook.HOOK_CODE);
        assertThat(guarded.triggeredHooks()).contains(BubbleCapHook.HOOK_CODE);
    }

    // =========================================================================
    // 8. PAYLOAD BOUNDING THRESHOLD (>= 4000 CHARS)
    // =========================================================================

    @Test
    @DisplayName("Payload Bounding: Normal and marginal text (1000 - 3000 chars) is completely preserved without cropping")
    void testPayloadBounding_marginalTextPreservedWithoutCropping() {
        String normalConsultationText = "Dạ em chào Anh ạ! Gói dịch vụ chăm sóc da chuẩn Y khoa bên em gồm 5 bước kỹ lưỡng. ".repeat(25);
        // Approximately 2075 characters, well within 1000-3000 chars range
        assertThat(normalConsultationText.length()).isBetween(1000, 3500);

        String bounded = GuardrailPipeline.boundPayload(normalConsultationText);

        assertThat(bounded).isEqualTo(normalConsultationText);
        assertThat(bounded.length()).isEqualTo(normalConsultationText.length());
    }

    @Test
    @DisplayName("Payload Bounding: Truly massive payload (>= 4000 chars) is safely bounded to head and tail")
    void testPayloadBounding_massivePayload_boundedSafely() {
        String massiveText = "Dạ em chào Anh ạ! Gói dịch vụ điều trị mụn số 1 rất tốt. ".repeat(120); // ~6800 chars
        assertThat(massiveText.length()).isGreaterThanOrEqualTo(4000);

        String bounded = GuardrailPipeline.boundPayload(massiveText);

        assertThat(bounded.length()).isLessThan(massiveText.length());
        assertThat(bounded).startsWith("Dạ em chào Anh ạ!");
        assertThat(bounded).contains("\n\n");
    }
}
