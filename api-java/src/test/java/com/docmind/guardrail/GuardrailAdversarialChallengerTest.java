package com.docmind.guardrail;

import com.example.docmind.dto.bot.FanpageBotResponse.ReplyBubble;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Empirical Adversarial Challenger Test Suite for Milestone M1 Guardrail Hooks.
 * Designed to stress-test boundary conditions, Vietnamese nuances, info dumping,
 * closing question control, and multi-threaded concurrency.
 */
class GuardrailAdversarialChallengerTest {

    private GuardrailPipeline pipeline;

    @BeforeEach
    void setUp() {
        pipeline = new GuardrailPipeline();
    }

    // =========================================================================
    // 1. VIETNAMESE HONORIFIC EDGE CASES
    // =========================================================================

    @Test
    @DisplayName("Challenge 1A: Mixed case 'cHi', 'CHỊ', 'ChỊ' for male customer")
    void testHonorific_mixedCaseVariations() {
        GuardrailContext ctx = GuardrailContext.builder()
                .customerHonorific("Anh")
                .isFirstTurn(true)
                .salesStage("VALUE_CONSULTATION")
                .build();

        String rawText = "Dạ cHi ơi, gói này rất hợp với CHỊ và ChỊ. CHị cứ yên tâm.";
        GuardrailResult result = pipeline.process(ctx, rawText);

        System.out.println("[Challenge 1A Output]: " + result.text());

        // Note: 'cHi' (without diacritic dot below) is not replaced because pattern expects 'chị' with dot below.
        boolean chiPreservedWithoutDot = result.text().contains("cHi");
        System.out.println("[Challenge 1A] 'cHi' without diacritic dot below was preserved: " + chiPreservedWithoutDot);

        assertThat(result.text())
                .doesNotContain("CHỊ")
                .doesNotContain("ChỊ")
                .contains("ANH")
                .contains("Anh");
    }

    @Test
    @DisplayName("Challenge 1B: Idiom 'chị em mình' for male customer")
    void testHonorific_idiomChiEmMinh() {
        GuardrailContext ctx = GuardrailContext.builder()
                .customerHonorific("Anh")
                .isFirstTurn(false)
                .salesStage("VALUE_CONSULTATION")
                .build();

        String rawText = "Vấn đề này để chị em mình cùng trao đổi nhé!";
        GuardrailResult result = pipeline.process(ctx, rawText);

        System.out.println("[Challenge 1B Output]: " + result.text());

        // 'chị em mình' -> 'anh em mình'
        assertThat(result.text())
                .doesNotContain("chị em mình")
                .containsIgnoringCase("anh em mình");
    }

    @Test
    @DisplayName("Challenge 1C: Third-person reference 'chị Google' for male customer")
    void testHonorific_thirdPersonEntityChiGoogle() {
        GuardrailContext ctx = GuardrailContext.builder()
                .customerHonorific("Anh")
                .isFirstTurn(false)
                .salesStage("VALUE_CONSULTATION")
                .build();

        String rawText = "Nhiều khách hàng hay tra cứu qua giọng đọc chị Google, nhưng bên em tư vấn trực tiếp chuẩn hơn ạ.";
        GuardrailResult result = pipeline.process(ctx, rawText);

        System.out.println("[Challenge 1C Output]: " + result.text());

        boolean convertedToAnhGoogle = result.text().contains("anh Google") || result.text().contains("Anh Google");
        System.out.println("[Challenge 1C] Third-party entity 'chị Google' converted to 'anh Google': " + convertedToAnhGoogle);
    }

    @Test
    @DisplayName("Challenge 1D: 'anh chị' without slash is cleanly normalized without 'anh anh' stutter bug")
    void testHonorific_anhChiWithoutSlash_stutterBug() {
        GuardrailContext ctx = GuardrailContext.builder()
                .customerHonorific("Anh")
                .isFirstTurn(true)
                .salesStage("DISCOVERY")
                .build();

        String rawText = "Dạ em chào anh chị ạ! Rất vui được đón tiếp quý anh chị.";
        GuardrailResult result = pipeline.process(ctx, rawText);

        System.out.println("[Challenge 1D Output]: " + result.text());

        boolean hasStutterAnhAnh = result.text().toLowerCase().contains("anh anh");
        System.out.println("[Challenge 1D] Stutter 'anh anh' eliminated: " + !hasStutterAnhAnh);

        assertThat(hasStutterAnhAnh)
                .withFailMessage("Stutter 'anh anh' must not occur in output: %s", result.text())
                .isFalse();
        assertThat(result.text()).contains("Dạ em chào Anh ạ! Rất vui được đón tiếp quý Anh.");
    }

    @Test
    @DisplayName("Challenge 1E: 'anh chị' without slash for female customer is normalized to 'Chị'")
    void testHonorific_anhChiWithoutSlash_femaleCustomerNotNormalized() {
        GuardrailContext ctx = GuardrailContext.builder()
                .customerHonorific("Chị")
                .isFirstTurn(true)
                .salesStage("DISCOVERY")
                .build();

        String rawText = "Dạ em chào anh chị ạ! Kính chúc anh chị một ngày tốt lành.";
        GuardrailResult result = pipeline.process(ctx, rawText);

        System.out.println("[Challenge 1E Output]: " + result.text());

        assertThat(result.text())
                .doesNotContain("anh chị")
                .contains("Chị");
    }

    @Test
    @DisplayName("Challenge 1F: Single-letter customer initial like 'Anh K' is preserved")
    void testHonorific_singleLetterInitialStripped() {
        GuardrailContext ctx = GuardrailContext.builder()
                .customerHonorific("Anh")
                .customerName("K")
                .isFirstTurn(true)
                .salesStage("VALUE_CONSULTATION")
                .build();

        String rawText = "Dạ Anh K, bên em có liệu trình phù hợp với anh.";
        GuardrailResult result = pipeline.process(ctx, rawText);

        System.out.println("[Challenge 1F Output]: " + result.text());

        boolean initialStripped = !result.text().contains("Anh K");
        System.out.println("[Challenge 1F] Initial 'K' preserved: " + !initialStripped);

        assertThat(initialStripped)
                .withFailMessage("Customer name initial 'K' must be preserved in 'Anh K'")
                .isFalse();
        assertThat(result.text()).contains("Anh K");
    }

    @Test
    @DisplayName("Challenge 1G: Casual pronoun 'bạn' and 'Bạn' for male customer is normalized to 'anh' and 'Anh'")
    void testHonorific_maleCustomerCasualBanNormalized() {
        GuardrailContext ctx = GuardrailContext.builder()
                .customerHonorific("Anh")
                .isFirstTurn(true)
                .salesStage("DISCOVERY")
                .build();

        String rawText = "Dạ chào bạn ạ! Liệu trình bên em rất hợp với bạn. Bạn có thể tham khảo nhé.";
        GuardrailResult result = pipeline.process(ctx, rawText);

        System.out.println("[Challenge 1G Output]: " + result.text());

        assertThat(result.text())
                .doesNotContain("bạn")
                .doesNotContain("Bạn")
                .contains("Dạ chào anh ạ!")
                .contains("rất hợp với anh.")
                .contains("Anh có thể tham khảo");
    }

    @Test
    @DisplayName("Challenge 1H: Specific dispatch verification of 'Chào bạn', 'bạn có thể', 'Bạn ơi' for male customer")
    void testHonorific_maleCustomerSpecificDispatchPronouns() {
        GuardrailContext ctx = GuardrailContext.builder()
                .customerHonorific("Anh")
                .isFirstTurn(true)
                .salesStage("DISCOVERY")
                .build();

        String rawText = "Chào bạn! Bạn ơi, bạn có thể cho em xin tình trạng da hiện tại của bạn được không ạ? Bên em sẵn sàng hỗ trợ bạn ngay.";
        GuardrailResult result = pipeline.process(ctx, rawText);

        System.out.println("[Challenge 1H Output]: " + result.text());

        assertThat(result.text())
                .doesNotContain("bạn")
                .doesNotContain("Bạn")
                .contains("Chào anh!")
                .contains("Anh ơi,")
                .contains("anh có thể")
                .contains("của anh")
                .contains("hỗ trợ anh ngay");
    }

    @Test
    @DisplayName("Challenge 1I: Non-pronoun compound nouns ('kết bạn Zalo', 'bạn bè') are preserved and never falsely replaced")
    void testHonorific_nonPronounCompoundNounsPreserved() {
        GuardrailContext ctx = GuardrailContext.builder()
                .customerHonorific("Anh")
                .isFirstTurn(false)
                .salesStage("VALUE_CONSULTATION")
                .build();

        String rawText = "Anh có thể kết bạn Zalo với em hoặc rủ bạn bè đi cùng để nhận ưu đãi nhé.";
        GuardrailResult result = pipeline.process(ctx, rawText);

        System.out.println("[Challenge 1I Output]: " + result.text());

        assertThat(result.text())
                .contains("kết bạn Zalo")
                .contains("bạn bè")
                .doesNotContain("kết anh Zalo")
                .doesNotContain("anh bè");
    }

    @Test
    @DisplayName("Challenge 1J: Uppercase 'CHÀO BẠN' and 'BẠN ƠI' correctly normalize to uppercase 'CHÀO ANH' and 'ANH ƠI'")
    void testHonorific_allCapsPronounNormalization() {
        GuardrailContext ctx = GuardrailContext.builder()
                .customerHonorific("Anh")
                .isFirstTurn(true)
                .salesStage("DISCOVERY")
                .build();

        String rawText = "CHÀO BẠN! BẠN ƠI, BẠN CÓ THỂ THAM KHẢO NGAY.";
        GuardrailResult result = pipeline.process(ctx, rawText);

        System.out.println("[Challenge 1J Output]: " + result.text());

        assertThat(result.text())
                .doesNotContain("BẠN")
                .doesNotContain("Bạn")
                .doesNotContain("bạn")
                .contains("CHÀO ANH!")
                .contains("ANH ƠI,")
                .containsIgnoringCase("anh có thể");
    }

    // =========================================================================
    // 2. ANTI-INFO DUMPING BOUNDARY
    // =========================================================================

    @Test
    @DisplayName("Challenge 2A: Single starting price concise reply in DISCOVERY stage does NOT trigger hook")
    void testAntiInfoDumping_singleStartingPriceConcise_notTriggered() {
        GuardrailContext ctx = GuardrailContext.builder()
                .customerHonorific("Anh")
                .customerMessage("giá dịch vụ sao em")
                .isFirstTurn(true)
                .salesStage("DISCOVERY")
                .build();

        String conciseText = "Dạ em chào Anh ạ! Gói trị mụn chuẩn y khoa bên em chỉ từ 650.000đ/buổi. Anh muốn bên em tư vấn khung giờ sáng hay chiều ạ?";
        GuardrailResult result = pipeline.process(ctx, conciseText);

        System.out.println("[Challenge 2A Output]: " + result.text());

        assertThat(result.triggeredHooks()).doesNotContain(AntiInfoDumpingHook.HOOK_CODE);
        assertThat(result.text()).contains("650.000đ/buổi");
    }

    @Test
    @DisplayName("Challenge 2B: Non-price numbers formatted with dots in DISCOVERY stage do NOT trigger false positive")
    void testAntiInfoDumping_nonPriceNumbersWithDots_notTriggered() {
        GuardrailContext ctx = GuardrailContext.builder()
                .customerHonorific("Anh")
                .customerMessage("Phòng khám có uy tín không em?")
                .isFirstTurn(false)
                .salesStage("DISCOVERY")
                .build();

        String socialProofText = "Dạ Anh yên tâm, phòng khám bên em đã phục vụ hơn 10.000 khách hàng và nhận hơn 5.000 đánh giá 5 sao. Anh muốn đặt lịch sáng hay chiều ạ?";
        GuardrailResult result = pipeline.process(ctx, socialProofText);

        System.out.println("[Challenge 2B Output]: " + result.text());

        boolean triggered = result.triggeredHooks().contains(AntiInfoDumpingHook.HOOK_CODE);
        System.out.println("[Challenge 2B] False positive triggered: " + triggered);

        // Verifies the bug is fixed: false positive must not trigger on counts with dots
        assertThat(triggered)
                .withFailMessage("REMEDIATED: False positive eliminated on non-price metrics with dots")
                .isFalse();
        assertThat(result.text())
                .contains("10.000 khách hàng")
                .contains("5.000 đánh giá")
                .doesNotContain("Chi phí các liệu trình chuẩn Y khoa");
    }

    @Test
    @DisplayName("Challenge 2C: Multiple prices without dots (500000đ, 1500000đ) correctly trigger anti-dumping hook")
    void testAntiInfoDumping_multiplePricesWithoutDots_triggersHook() {
        GuardrailContext ctx = GuardrailContext.builder()
                .customerHonorific("Anh")
                .customerMessage("giá sao em")
                .isFirstTurn(true)
                .salesStage("DISCOVERY")
                .build();

        String rawText = "Dạ bên em có gói cơ bản 500000đ và gói chuyên sâu 1500000đ. Anh muốn chọn gói nào?";
        GuardrailResult result = pipeline.process(ctx, rawText);

        System.out.println("[Challenge 2C Output]: " + result.text());

        boolean hookTriggered = result.triggeredHooks().contains(AntiInfoDumpingHook.HOOK_CODE);
        System.out.println("[Challenge 2C] Multi-price dumping detected for un-dotted numbers: " + hookTriggered);

        // Verifies the limitation is fixed: un-dotted prices trigger AntiInfoDumpingHook
        assertThat(hookTriggered)
                .withFailMessage("REMEDIATED: Un-dotted prices correctly trigger AntiInfoDumpingHook")
                .isTrue();
        assertThat(result.text())
                .contains("500.000đ")
                .contains("soi da AI 3D miễn phí 100%");
    }

    @Test
    @DisplayName("Challenge 2D: Multiple prices in non-discovery stage (VALUE_CONSULTATION) are preserved")
    void testAntiInfoDumping_nonDiscoveryStage_multiplePricesAllowed() {
        GuardrailContext ctx = GuardrailContext.builder()
                .customerHonorific("Anh")
                .customerMessage("Báo giá chi tiết từng gói cho anh")
                .isFirstTurn(false)
                .salesStage("VALUE_CONSULTATION")
                .build();

        String detailedPricingText = "Dạ gói Trị Mụn 1.500.000 VNĐ, Laser Nám 4.500.000 VNĐ và Trẻ Hóa 6.000.000 VNĐ ạ.\n\nAnh muốn trải nghiệm trước 1 buổi hay chọn gói Combo ạ?";
        GuardrailResult result = pipeline.process(ctx, detailedPricingText);

        System.out.println("[Challenge 2D Output]: " + result.text());

        assertThat(result.triggeredHooks()).doesNotContain(AntiInfoDumpingHook.HOOK_CODE);
        assertThat(result.text())
                .contains("1.500.000 VNĐ")
                .contains("4.500.000 VNĐ")
                .contains("6.000.000 VNĐ");
    }

    @Test
    @DisplayName("Challenge 2E: Dynamic price selection chooses cheaper starting price (250.000đ/buổi) when both 250k and 650k are present")
    void testAntiInfoDumping_dynamicSelectionPrefers250kOver650k() {
        GuardrailContext ctx = GuardrailContext.builder()
                .customerHonorific("Anh")
                .customerMessage("giá sao em")
                .isFirstTurn(true)
                .salesStage("DISCOVERY")
                .knowledgeChunks(List.of("Gói trải nghiệm 250.000đ/buổi, gói chuẩn 650.000đ/buổi"))
                .build();

        String rawText = "Dạ bên em có gói 250.000đ/buổi và gói 650.000đ/buổi. Anh muốn tư vấn gói nào?";
        GuardrailResult result = pipeline.process(ctx, rawText);

        System.out.println("[Challenge 2E Output]: " + result.text());

        assertThat(result.triggeredHooks()).contains(AntiInfoDumpingHook.HOOK_CODE);
        assertThat(result.text())
                .contains("250.000đ/buổi")
                .doesNotContain("650.000đ");
    }

    @Test
    @DisplayName("Challenge 2F: Dynamic price formatting for '1.5 triệu vs 500k' selects 500.000đ into rewrite template")
    void testAntiInfoDumping_dynamicSelectionPrefers500kOver1Point5Million() {
        GuardrailContext ctx = GuardrailContext.builder()
                .customerHonorific("Anh")
                .customerMessage("giá rổ thế nào shop")
                .isFirstTurn(true)
                .salesStage("DISCOVERY")
                .build();

        String rawText = "Dạ bên em có gói chuyên sâu 1.5 triệu và gói cơ bản 500k. Anh muốn tư vấn gói nào ạ?";
        GuardrailResult result = pipeline.process(ctx, rawText);

        System.out.println("[Challenge 2F Output]: " + result.text());

        assertThat(result.triggeredHooks()).contains(AntiInfoDumpingHook.HOOK_CODE);
        assertThat(result.text())
                .contains("500.000đ")
                .doesNotContain("1.5 triệu")
                .doesNotContain("1.500.000")
                .doesNotContain("/buổi"); // Neither 500k nor 1.5 triệu specified /buổi
    }

    @Test
    @DisplayName("Challenge 2G: Dynamic price formatting supports comma decimal '1,5 triệu' vs '500k' and '1.2tr/buổi' vs '350k/buổi'")
    void testAntiInfoDumping_commaDecimalAndTrSuffix() {
        GuardrailContext ctx1 = GuardrailContext.builder()
                .customerHonorific("Anh")
                .customerMessage("báo giá giúp mình")
                .isFirstTurn(false)
                .salesStage("DISCOVERY")
                .build();

        String rawText1 = "Dạ gói 1: 1,5 triệu, gói 2: 500k. Anh quan tâm gói nào ạ?";
        GuardrailResult result1 = pipeline.process(ctx1, rawText1);

        System.out.println("[Challenge 2G-1 Output]: " + result1.text());

        assertThat(result1.triggeredHooks()).contains(AntiInfoDumpingHook.HOOK_CODE);
        assertThat(result1.text())
                .contains("500.000đ")
                .doesNotContain("1,5 triệu");

        GuardrailContext ctx2 = GuardrailContext.builder()
                .customerHonorific("Anh")
                .customerMessage("chi phí bao nhiêu bạn")
                .isFirstTurn(false)
                .salesStage("DISCOVERY")
                .build();

        String rawText2 = "Dạ bên em có gói VIP 1.2tr/buổi và gói trải nghiệm 350k/buổi. Anh chọn gói nào ạ?";
        GuardrailResult result2 = pipeline.process(ctx2, rawText2);

        System.out.println("[Challenge 2G-2 Output]: " + result2.text());

        assertThat(result2.triggeredHooks()).contains(AntiInfoDumpingHook.HOOK_CODE);
        assertThat(result2.text())
                .contains("350.000đ/buổi")
                .doesNotContain("1.2tr");
    }

    @Test
    @DisplayName("Challenge 2H: Dynamic price selection across draft text and knowledge chunks picks lowest overall")
    void testAntiInfoDumping_lowestAcrossTextAndKnowledgeChunks() {
        GuardrailContext ctx = GuardrailContext.builder()
                .customerHonorific("Anh")
                .customerMessage("chi phí sao em")
                .isFirstTurn(true)
                .salesStage("DISCOVERY")
                .knowledgeChunks(List.of(
                        "Bảng giá phòng khám: Trị mụn học sinh sinh viên chỉ từ 200k/buổi.",
                        "Gói Laser Fractional CO2: 3.500.000đ/buổi."
                ))
                .build();

        String rawText = "Dạ gói cơ bản 1.500.000đ và gói VIP 3.000.000đ. Anh muốn chọn gói nào?";
        GuardrailResult result = pipeline.process(ctx, rawText);

        System.out.println("[Challenge 2H Output]: " + result.text());

        assertThat(result.triggeredHooks()).contains(AntiInfoDumpingHook.HOOK_CODE);
        assertThat(result.text())
                .contains("200.000đ/buổi")
                .doesNotContain("1.500.000")
                .doesNotContain("3.000.000");
    }

    @Test
    @DisplayName("Challenge 2I: Dynamic 250k vs 650k without per-session suffix outputs clean '250.000đ'")
    void testAntiInfoDumping_250kVs650kWithoutPerSessionSuffix() {
        GuardrailContext ctx = GuardrailContext.builder()
                .customerHonorific("Anh")
                .customerMessage("cho anh bảng giá")
                .isFirstTurn(true)
                .salesStage("DISCOVERY")
                .build();

        String rawText = "Dạ bên em có mức giá 250k và 650k tùy nhu cầu. Anh muốn tư vấn gói nào ạ?";
        GuardrailResult result = pipeline.process(ctx, rawText);

        System.out.println("[Challenge 2I Output]: " + result.text());

        assertThat(result.triggeredHooks()).contains(AntiInfoDumpingHook.HOOK_CODE);
        assertThat(result.text())
                .contains("250.000đ")
                .doesNotContain("250.000đ/buổi")
                .doesNotContain("650k");
    }

    // =========================================================================
    // 3. CLOSING QUESTION CONTROL
    // =========================================================================

    @Test
    @DisplayName("Challenge 3A: Rhetorical question in middle paragraph is preserved")
    void testClosingQuestion_rhetoricalQuestionInMiddle_preserved() {
        GuardrailContext ctx = GuardrailContext.builder()
                .customerHonorific("Anh")
                .isFirstTurn(false)
                .salesStage("VALUE_CONSULTATION")
                .build();

        String rawText = "Anh có biết tại sao da hay đổ dầu vào mùa hè không? Đó là do da thiếu ẩm sâu.\n\nAnh muốn đặt lịch soi da miễn phí vào khung giờ Sáng hay Chiều ạ?";
        GuardrailResult result = pipeline.process(ctx, rawText);

        System.out.println("[Challenge 3A Output]: " + result.text());

        assertThat(result.text())
                .contains("Anh có biết tại sao da hay đổ dầu vào mùa hè không?")
                .contains("Sáng hay Chiều ạ?");
    }

    @Test
    @DisplayName("Challenge 3B: Rapid-fire question in last paragraph eliminates preliminary question and preserves closing CTA")
    void testClosingQuestion_rapidFireTruncationInLastParagraph() {
        GuardrailContext ctx = GuardrailContext.builder()
                .customerHonorific("Anh")
                .isFirstTurn(false)
                .salesStage("VALUE_CONSULTATION")
                .build();

        String rawText = "Liệu trình bên em rất an toàn.\n\nDa bị mụn có khó chịu không? Anh muốn bên em đặt lịch soi da Sáng hay Chiều ạ?";
        GuardrailResult result = pipeline.process(ctx, rawText);

        System.out.println("[Challenge 3B Output]: " + result.text());

        assertThat(result.text())
                .contains("Anh muốn bên em đặt lịch soi da Sáng hay Chiều ạ?")
                .doesNotContain("Da bị mụn có khó chịu không?");
    }

    @Test
    @DisplayName("Challenge 3C: Legitimate diagnostic question ending with 'không ạ?' is preserved")
    void testClosingQuestion_diagnosticQuestionEndingWithKhongAErased() {
        GuardrailContext ctx = GuardrailContext.builder()
                .customerHonorific("Anh")
                .isFirstTurn(false)
                .salesStage("VALUE_CONSULTATION")
                .build();

        String rawText = "Dạ da anh đang có dấu hiệu khô căng.\n\nAnh có dùng kem chống nắng hằng ngày không ạ?";
        GuardrailResult result = pipeline.process(ctx, rawText);

        System.out.println("[Challenge 3C Output]: " + result.text());

        boolean questionErased = !result.text().contains("kem chống nắng");
        System.out.println("[Challenge 3C] Sunscreen question preserved: " + !questionErased);

        assertThat(questionErased)
                .withFailMessage("Diagnostic question was falsely replaced with generic closing")
                .isFalse();
        assertThat(result.text()).contains("kem chống nắng");
    }

    @Test
    @DisplayName("Challenge 3D: Missing closing question is automatically appended")
    void testClosingQuestion_missingClosingQuestion_appendsNaturalClosing() {
        GuardrailContext ctx = GuardrailContext.builder()
                .customerHonorific("Anh")
                .isFirstTurn(true)
                .salesStage("DISCOVERY")
                .build();

        String rawText = "Dạ em chào Anh ạ! Liệu trình trị mụn bên em chuẩn Y khoa gồm 5 bước kỹ lưỡng.";
        GuardrailResult result = pipeline.process(ctx, rawText);

        System.out.println("[Challenge 3D Output]: " + result.text());

        assertThat(result.text()).endsWith("?");
        assertThat(result.triggeredHooks()).contains(ClosingQuestionHook.HOOK_CODE);
    }

    // =========================================================================
    // 4. CONCURRENCY & THREAD SAFETY UNDER HIGH LOAD
    // =========================================================================

    @Test
    @DisplayName("Challenge 4: High concurrent stress test across 50 threads with 1,000 requests")
    void testConcurrency_highLoadMultiThreadStressTest() throws InterruptedException, ExecutionException {
        int threadCount = 50;
        int totalRequests = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        List<Callable<GuardrailResult>> tasks = new ArrayList<>(totalRequests);
        AtomicInteger maleSuccessCount = new AtomicInteger();
        AtomicInteger femaleSuccessCount = new AtomicInteger();
        AtomicInteger totalViolations = new AtomicInteger();

        long startTime = System.nanoTime();

        for (int i = 0; i < totalRequests; i++) {
            final int index = i;
            tasks.add(() -> {
                boolean isMale = (index % 2 == 0);
                String honorific = isMale ? "Anh" : "Chị";
                String salesStage = (index % 3 == 0) ? "DISCOVERY" : "VALUE_CONSULTATION";

                GuardrailContext ctx = GuardrailContext.builder()
                        .customerHonorific(honorific)
                        .customerName("User" + index)
                        .isFirstTurn(index % 4 == 0)
                        .salesStage(salesStage)
                        .customerMessage("Chi phí thế nào shop?")
                        .knowledgeChunks(List.of("Gói mụn 650.000đ/buổi, gói nám 4.500.000đ"))
                        .conversationHistory(List.of("Chào shop", "Dạ chào bạn"))
                        .build();

                String rawText;
                if (isMale) {
                    rawText = "Dạ em chào Chị " + index + " ạ! Gói trị mụn 1.500.000 VNĐ và gói 4.500.000 VNĐ. Chị muốn tư vấn thêm không ạ?";
                } else {
                    rawText = "Dạ em chào Anh/Chị " + index + " ạ! Gói trị mụn 1.500.000 VNĐ và gói 4.500.000 VNĐ. Bạn có thể tham khảo nhé.";
                }

                GuardrailResult res = pipeline.process(ctx, rawText);

                // Thread-safety verification
                if (isMale) {
                    if (res.text().contains("Chị") || res.text().contains("chị")) {
                        totalViolations.incrementAndGet();
                    } else {
                        maleSuccessCount.incrementAndGet();
                    }
                } else {
                    if (res.text().contains("Anh/Chị")) {
                        totalViolations.incrementAndGet();
                    } else {
                        femaleSuccessCount.incrementAndGet();
                    }
                }

                // Bubble cap verification
                if (res.bubbles().size() > 2) {
                    totalViolations.incrementAndGet();
                }

                return res;
            });
        }

        List<Future<GuardrailResult>> futures = executor.invokeAll(tasks);
        executor.shutdown();
        boolean finished = executor.awaitTermination(30, TimeUnit.SECONDS);

        long elapsedNanos = System.nanoTime() - startTime;
        double elapsedMs = elapsedNanos / 1_000_000.0;
        double throughput = (totalRequests / (elapsedMs / 1000.0));

        List<Double> latencies = new ArrayList<>();
        for (Future<GuardrailResult> f : futures) {
            GuardrailResult r = f.get();
            latencies.add(r.latencyMillis());
        }

        Collections.sort(latencies);
        double p50 = latencies.get((int) (latencies.size() * 0.50));
        double p95 = latencies.get((int) (latencies.size() * 0.95));
        double p99 = latencies.get((int) (latencies.size() * 0.99));
        double max = latencies.get(latencies.size() - 1);

        System.out.printf("[Concurrency Stress Test Results]%n"
                + "Threads: %d | Total Requests: %d | Finished in: %.2f ms | Throughput: %.1f req/sec%n"
                + "Latencies: p50=%.3f ms | p95=%.3f ms | p99=%.3f ms | Max=%.3f ms%n"
                + "Violations: %d | Male Success: %d | Female Success: %d%n",
                threadCount, totalRequests, elapsedMs, throughput,
                p50, p95, p99, max,
                totalViolations.get(), maleSuccessCount.get(), femaleSuccessCount.get());

        assertThat(finished).isTrue();
        assertThat(totalViolations.get()).isZero();
        assertThat(maleSuccessCount.get() + femaleSuccessCount.get()).isEqualTo(totalRequests);
        assertThat(p50).isLessThan(5.0); // Median per-request latency is strictly < 5ms
    }
}
