package com.example.docmind.service.sales;

import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.repository.RetrievedChunk;
import com.example.docmind.service.retrieval.ReciprocalRankFusion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Feature F7: Stateful PriceLadderTracker Unit & Behavioral Tests")
class PriceLadderTrackerTest {

    private static ReciprocalRankFusion.FusedChunk promoChunk(String text) {
        RetrievedChunk rc = new RetrievedChunk(
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of("Chính Sách Ưu Đãi"),
                List.of(UUID.randomUUID()),
                1,
                new double[]{0, 0, 100, 100},
                text,
                0.90
        );
        return new ReciprocalRankFusion.FusedChunk(rc, 0.035);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "cao thế",
            "cao quá",
            "cao vậy shop",
            "hơi đắt",
            "nhưng hơi đắt",
            "đắt thế",
            "đắt quá",
            "mắc quá",
            "hơi mắc",
            "chát quá",
            "có gói nào rẻ hơn không",
            "có gói rẻ hơn không",
            "gói nào mềm hơn không em",
            "tiết kiệm hơn",
            "gói nhỏ hơn"
    })
    @DisplayName("Detects all standard Vietnamese price objection variations")
    void detectsPriceObjectionsCorrectly(String objectionMsg) {
        assertThat(PriceLadderTracker.isPriceObjection(objectionMsg)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "tôi muosn thử trải nghiệm trước",
            "tôi muốn thử trải nghiệm trước",
            "muốn thử trải nghiệm",
            "mình muốn thử xem sao",
            "trải nghiệm trước",
            "thử xem nhé shop",
            "cho chị làm thử"
    })
    @DisplayName("Detects all standard Vietnamese trial request variations")
    void detectsTrialRequestsCorrectly(String trialMsg) {
        assertThat(PriceLadderTracker.isTrialRequest(trialMsg)).isTrue();
    }

    @Test
    @DisplayName("Precedence: When customer combines trial and objection, objection takes priority for down-sell")
    void precedenceObjectionOverTrial() {
        String msg = "muosn thử nhưng cao the";
        var result = PriceLadderTracker.evaluate(msg, List.of(), List.of(), "hanoi");

        assertThat(result.isPriceObjection()).isTrue();
        assertThat(result.isTrialRequest()).isFalse();
        assertThat(result.shouldSuppressHandoff()).isTrue();
    }

    @Test
    @DisplayName("Extracts previously quoted price from assistant history turns")
    void extractsQuotedPricesFromHistory() {
        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("user", "giá"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ bên em có gói Massage cơ bản khởi điểm từ 399.000VNĐ (Hà Nội) ạ.")
        );

        Long price = PriceLadderTracker.extractPreviousQuotedPrice(history);
        assertThat(price).isEqualTo(399_000L);
    }

    @Test
    @DisplayName("Turn 2: Trial request maintains previous package price and moves toward booking")
    void trialRequestMaintainsConsistency() {
        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("user", "giá"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ bên em có gói Massage cơ bản khởi điểm từ 399.000VNĐ (Hà Nội) gồm 60 phút.")
        );

        var result = PriceLadderTracker.evaluate("tôi muosn thử trải nghiệm trước", history, List.of(), "hanoi");

        assertThat(result.isTrialRequest()).isTrue();
        assertThat(result.isPriceObjection()).isFalse();
        assertThat(result.previousPriceQuoted()).isEqualTo(399_000L);
        assertThat(result.targetPrice()).isEqualTo(399_000L);
        assertThat(result.targetPackageName()).contains("Massage cơ bản");
        assertThat(result.isCatalogFloor()).isFalse();
        assertThat(result.shouldSuppressHandoff()).isTrue();
    }

    @Test
    @DisplayName("Turn 3: 'cao thế' down-sells monotonically from 399.000 VNĐ to 239.000 VNĐ")
    void downSellFrom399kTo239k() {
        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("user", "giá"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ gói Massage 399.000VNĐ ạ."),
                new FanpageBotRequest.ConversationTurn("user", "tôi muosn thử trải nghiệm trước"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ gói 399.000VNĐ trải nghiệm rất hợp ạ.")
        );

        var result = PriceLadderTracker.evaluate("cao thế", history, List.of(), "hanoi");

        assertThat(result.isPriceObjection()).isTrue();
        assertThat(result.previousPriceQuoted()).isEqualTo(399_000L);
        assertThat(result.targetPrice()).isEqualTo(239_000L);
        assertThat(result.targetPackageName()).contains("Dưỡng sinh cơ bản");
        assertThat(result.isCatalogFloor()).isFalse();
        assertThat(result.shouldSuppressHandoff()).isTrue();
    }

    @Test
    @DisplayName("Turn 4: 'nhưng hơi đắt' down-sells monotonically from 239.000 VNĐ to 129.000 VNĐ floor")
    void downSellFrom239kTo129kFloor() {
        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("user", "giá"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ gói Massage 399.000VNĐ."),
                new FanpageBotRequest.ConversationTurn("user", "cao thế"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ bên em có gói dưỡng sinh cơ bản chỉ 239.000VNĐ/60 phút rất nhẹ nhàng để chị trải nghiệm trước ạ.")
        );

        var result = PriceLadderTracker.evaluate("nhưng hơi đắt", history, List.of(), "hanoi");

        assertThat(result.isPriceObjection()).isTrue();
        assertThat(result.previousPriceQuoted()).isEqualTo(239_000L);
        assertThat(result.targetPrice()).isEqualTo(129_000L);
        assertThat(result.targetPackageName()).contains("Vệ sinh tai cơ bản");
        assertThat(result.isCatalogFloor()).isTrue();
        assertThat(result.shouldSuppressHandoff()).isTrue();
    }

    @Test
    @DisplayName("Turn 5: 'có gói nào rẻ hơn không' at 129.000 VNĐ floor confirms floor and attaches 10% promo")
    void catalogFloorPromoPolicyAttachment() {
        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("user", "giá"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ gói Massage 399.000VNĐ."),
                new FanpageBotRequest.ConversationTurn("user", "cao thế"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ gói 239.000VNĐ ạ."),
                new FanpageBotRequest.ConversationTurn("user", "nhưng hơi đắt"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ bên em có gói lấy ráy tai cơ bản 129.000VNĐ/30 phút là mức giá mềm nhất rồi ạ.")
        );

        var promo = promoChunk("CHÍNH SÁCH ƯU ĐÃI: Giảm 10% khi mua liệu trình từ 5 buổi hoặc từ 5 voucher.");
        var result = PriceLadderTracker.evaluate("có gói nào rẻ hơn không", history, List.of(promo), "hanoi");

        assertThat(result.isPriceObjection()).isTrue();
        assertThat(result.previousPriceQuoted()).isEqualTo(129_000L);
        assertThat(result.targetPrice()).isEqualTo(129_000L);
        assertThat(result.isCatalogFloor()).isTrue();
        assertThat(result.discountPolicy()).contains("Giảm 10%");
        assertThat(result.shouldSuppressHandoff()).isTrue();
    }

    @Test
    @DisplayName("Saigon branch ladder: 570.000 VNĐ -> 260.000 VNĐ -> 129.000 VNĐ")
    void saigonBranchDownSellLadder() {
        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("user", "cơ sở Quận 1 có gói nào"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ cơ sở Quận 1 Sài Gòn bên em có gói Massage cơ bản từ 570.000VNĐ ạ.")
        );

        var step1 = PriceLadderTracker.evaluate("cao thế shop", history, List.of(), "saigon");
        assertThat(step1.targetPrice()).isEqualTo(260_000L);
        assertThat(step1.targetPackageName()).contains("Dưỡng sinh cơ bản");

        List<FanpageBotRequest.ConversationTurn> history2 = List.of(
                new FanpageBotRequest.ConversationTurn("user", "cơ sở Quận 1 có gói nào"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ gói Massage Quận 1 là 570.000VNĐ."),
                new FanpageBotRequest.ConversationTurn("user", "cao thế"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ bên em có gói dưỡng sinh Quận 1 giá 260.000VNĐ ạ.")
        );

        var step2 = PriceLadderTracker.evaluate("còn gói nào rẻ hơn nữa không", history2, List.of(), "saigon");
        assertThat(step2.targetPrice()).isEqualTo(129_000L);
        assertThat(step2.isCatalogFloor()).isTrue();
    }

    @Test
    @DisplayName("Guarantees monotonic non-increasing target price (zero upward price oscillation)")
    void zeroPriceOscillationGuarantee() {
        Long previousPrice = 399_000L;
        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ gói Massage 399.000VNĐ ạ.")
        );

        var res = PriceLadderTracker.evaluate("cao thế", history, List.of(), "hanoi");
        assertThat(res.targetPrice()).isLessThan(previousPrice);

        // Even with repeated objections, targetPrice never increases
        history = List.of(
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ gói 239.000VNĐ ạ.")
        );
        var res2 = PriceLadderTracker.evaluate("vẫn đắt", history, List.of(), "hanoi");
        assertThat(res2.targetPrice()).isLessThan(239_000L);
    }

    // =========================================================================
    // AUXILIARY TOKEN FILTERING & EXTRACTION UNIT TESTS (Defect 3 Remediation)
    // =========================================================================

    @Test
    @DisplayName("extractPricesFromText prunes sub-floor deposit token (50k) alongside package price")
    void testExtractPricesPrunesDepositToken() {
        String text = "Dạ gói Massage 399.000VNĐ, đặt cọc 50k giữ chỗ ạ.";
        List<Long> prices = PriceLadderTracker.extractPricesFromText(text);

        assertThat(prices)
                .as("Deposit token (50k) must be filtered out, retaining only 399.000 VNĐ")
                .containsExactly(399_000L);
    }

    @Test
    @DisplayName("extractPricesFromText prunes deposit token above floor (200k) via prefix pattern")
    void testExtractPricesPrunesLargeDepositToken() {
        String text = "Dạ gói Massage VIP 1.990.000VNĐ, cọc 200k giữ phòng VIP ạ.";
        List<Long> prices = PriceLadderTracker.extractPricesFromText(text);

        assertThat(prices)
                .as("Large deposit token (200k) must be recognized by prefix and pruned")
                .containsExactly(1_990_000L);
    }

    @Test
    @DisplayName("extractPricesFromText prunes voucher discount tokens")
    void testExtractPricesPrunesVoucherToken() {
        String text = "Dạ gói Dưỡng sinh 239.000VNĐ, voucher giảm 50k cho khách mới ạ.";
        List<Long> prices = PriceLadderTracker.extractPricesFromText(text);

        assertThat(prices)
                .as("Voucher token (50k) must be pruned")
                .containsExactly(239_000L);
    }

    @Test
    @DisplayName("extractPricesFromText prunes holiday surcharge token")
    void testExtractPricesPrunesSurchargeToken() {
        String text = "Dạ gói Lấy ráy tai 129.000VNĐ, phụ thu 50k ngày lễ ạ.";
        List<Long> prices = PriceLadderTracker.extractPricesFromText(text);

        assertThat(prices)
                .as("Surcharge token (50k) must be pruned")
                .containsExactly(129_000L);
    }

    @Test
    @DisplayName("extractPricesFromText returns empty list when text contains only auxiliary deposit")
    void testExtractPricesReturnsEmptyForDepositOnly() {
        String text = "Dạ anh chỉ cần đặt cọc 50k để giữ chỗ thôi ạ.";
        List<Long> prices = PriceLadderTracker.extractPricesFromText(text);

        assertThat(prices)
                .as("Text with only deposit must yield empty package prices")
                .isEmpty();
    }

    @Test
    @DisplayName("extractPreviousQuotedPrice scans past deposit-only turn to find earlier package quote")
    void testExtractPreviousQuotedPriceScansPastDepositTurn() {
        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("user", "giá massage bên em"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ gói Massage 399.000VNĐ ạ."),
                new FanpageBotRequest.ConversationTurn("user", "chị muốn thử"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ chị cọc 50k giữ chỗ giúp em nha.")
        );

        Long price = PriceLadderTracker.extractPreviousQuotedPrice(history);
        assertThat(price)
                .as("Must skip the 50k deposit turn and extract 399.000 VNĐ from Turn 2")
                .isEqualTo(399_000L);
    }

    // =========================================================================
    // SYMMETRICAL BRANCH RESOLUTION UNIT TESTS (Defect 1 Remediation)
    // =========================================================================

    @Test
    @DisplayName("resolveBranchFromText symmetrically resolves latest mentioned branch")
    void testResolveBranchSymmetricResolution() {
        // Destination Hanoi: Saigon mentioned first, Hanoi mentioned last
        String msg1 = "em chuyển từ cơ sở Sài Gòn sang cơ sở Hà Nội nhé";
        assertThat(PriceLadderTracker.resolveBranchFromText(msg1)).isEqualTo("hanoi");

        // Destination Saigon: Hanoi mentioned first, Saigon mentioned last
        String msg2 = "em đang xem ở Hà Nội nhưng muốn qua cơ sở Quận 1 Sài Gòn";
        assertThat(PriceLadderTracker.resolveBranchFromText(msg2)).isEqualTo("saigon");

        // No branch mentioned
        String msg3 = "cho mình hỏi giá dịch vụ nhé";
        assertThat(PriceLadderTracker.resolveBranchFromText(msg3)).isNull();
    }

    @Test
    @DisplayName("resolveBranch ignores multi-branch assistant greeting and defaults to hanoi")
    void testResolveBranchIgnoresMultiBranchAssistantGreeting() {
        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("user", "giá"),
                new FanpageBotRequest.ConversationTurn("assistant",
                        "Dạ em chào Chị ạ! Bên em có gói Massage cơ bản khởi điểm từ 399.000 VNĐ (Hà Nội) hoặc 570.000 VNĐ (Quận 1 Sài Gòn)..."),
                new FanpageBotRequest.ConversationTurn("user", "tôi muosn thử trải nghiệm trước"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ gói 399.000 VNĐ rất phù hợp ạ.")
        );
        String resolved = PriceLadderTracker.resolveBranch("cao thế shop, có gói nào mềm hơn ko", history, null);
        assertThat(resolved)
                .as("Multi-branch assistant message must not latch to saigon")
                .isEqualTo("hanoi");
    }

    @Test
    @DisplayName("resolveBranch prioritizes customer user turn branch preference over assistant turns")
    void testResolveBranchPrioritizesUserBranchPreference() {
        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("user", "mình muốn hỏi cơ sở Quận 1 Sài Gòn"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ bên em có gói 570k tại Quận 1 ạ.")
        );
        String resolved = PriceLadderTracker.resolveBranch("cao thế", history, null);
        assertThat(resolved)
                .as("Customer user turn stating Saigon must be authoritative")
                .isEqualTo("saigon");
    }

    @Test
    @DisplayName("Dynamic Multi-Industry Price Ladder: Extracts arbitrary Education/SaaS price tiers and promo from chunks without hardcoding")
    void dynamicMultiIndustryPriceLadderFromChunks() {
        RetrievedChunk courseTableChunk = new RetrievedChunk(
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of("BẢNG HỌC PHÍ KHÓA HỌC TIẾNG ANH"),
                List.of(UUID.randomUUID()),
                1,
                new double[]{0, 0, 100, 100},
                """
                | Mã gói | Tên khóa học | Học phí | Thời lượng |
                | --- | --- | --- | --- |
                | EDU03 | Khóa IELTS Chuyên Sâu 1-1 | 2.500.000 VNĐ | 3 tháng |
                | EDU02 | Khóa Giao Tiếp Tiêu Chuẩn | 990.000 VNĐ | 2 tháng |
                | EDU01 | Khóa Phát Âm Nền Tảng | 299.000 VNĐ | 1 tháng |
                """,
                0.95
        );
        var fusedTable = new ReciprocalRankFusion.FusedChunk(courseTableChunk, 0.05);
        var fusedPromo = promoChunk("ƯU ĐÃI HỌC PHÍ: Giảm 20% khi đăng ký nhóm từ 2 học viên.");

        // Step 1: Customer was quoted 2.500.000 VNĐ and objects "hơi đắt" -> down-sells to 990.000 VNĐ (EDU02)
        List<FanpageBotRequest.ConversationTurn> history1 = List.of(
                new FanpageBotRequest.ConversationTurn("user", "học phí sao ạ"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ bên mình có Khóa IELTS Chuyên Sâu 1-1 giá 2.500.000 VNĐ ạ.")
        );
        var step1 = PriceLadderTracker.evaluate("hơi đắt shop ơi", history1, List.of(fusedTable, fusedPromo), null);
        assertThat(step1.isPriceObjection()).isTrue();
        assertThat(step1.previousPriceQuoted()).isEqualTo(2_500_000L);
        assertThat(step1.targetPrice()).isEqualTo(990_000L);
        assertThat(step1.targetPackageName()).contains("Khóa Giao Tiếp Tiêu Chuẩn").contains("EDU02");
        assertThat(step1.isCatalogFloor()).isFalse();

        // Step 2: Customer objects again at 990.000 VNĐ -> down-sells to 299.000 VNĐ floor (EDU01) + custom 20% promo
        List<FanpageBotRequest.ConversationTurn> history2 = List.of(
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ bên mình có Khóa Giao Tiếp Tiêu Chuẩn 990.000 VNĐ ạ.")
        );
        var step2 = PriceLadderTracker.evaluate("có gói nào rẻ hơn không", history2, List.of(fusedTable, fusedPromo), null);
        assertThat(step2.targetPrice()).isEqualTo(299_000L);
        assertThat(step2.targetPackageName()).contains("Khóa Phát Âm Nền Tảng").contains("EDU01");
        assertThat(step2.isCatalogFloor()).isTrue();
        assertThat(step2.discountPolicy()).contains("Giảm 20% khi đăng ký nhóm từ 2 học viên");
    }

    @Test
    @DisplayName("Turn 5-7 Live Transcript Regression: Promo inquiry ('Có giảm giá hay KM j k') + Slang trial ('Thử trc 1b cũng được') + Approx price ('T muốn thử gói 390k trước')")
    void liveTranscriptTurn5To7PriceLadderRegression() {
        var promo = promoChunk("CHÍNH SÁCH ƯU ĐÃI: Giảm 10% khi mua liệu trình từ 5 buổi hoặc từ 5 voucher.");

        // Turn 5: Customer asks "Có giảm giá hay KM j k" after 530k/580k comparison
        List<FanpageBotRequest.ConversationTurn> historyTurn5 = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Gói 530 và 580 khác gì nhau"),
                new FanpageBotRequest.ConversationTurn("assistant",
                        "Dạ Anh/Chị ạ, gói Dưỡng Sinh Thân Thể 530.000VNĐ phù hợp cho Anh/Chị muốn trải nghiệm trước. Gói Massage phục hồi 580.000VNĐ thì chuyên sâu hơn nha.")
        );
        var resTurn5 = PriceLadderTracker.evaluate("Có giảm giá hay KM j k", historyTurn5, List.of(promo), "hanoi");
        assertThat(resTurn5.isPriceObjection()).isTrue();
        assertThat(resTurn5.targetPrice()).isEqualTo(399_000L);
        assertThat(resTurn5.targetPackageName()).contains("Massage cơ bản (NS01)");
        assertThat(resTurn5.discountPolicy()).contains("Giảm 10%");

        // Turn 6: Customer says "Thử trc 1b cũng được" after bot pitched 399.000VNĐ
        List<FanpageBotRequest.ConversationTurn> historyTurn6 = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Có giảm giá hay KM j k"),
                new FanpageBotRequest.ConversationTurn("assistant",
                        "Dạ Anh/Chị ạ, hiện bên em có ưu đãi giảm 10% khi mua liệu trình từ 5 buổi nha. Nếu muốn trải nghiệm lẻ trước, gói Massage cơ bản NS01 với giá 399.000VNĐ trong 60 phút là mức giá mềm nhất ạ.")
        );
        var resTurn6 = PriceLadderTracker.evaluate("Thử trc 1b cũng được", historyTurn6, List.of(promo), "hanoi");
        assertThat(resTurn6.isTrialRequest()).isTrue();
        assertThat(resTurn6.targetPrice()).isEqualTo(399_000L);
        assertThat(resTurn6.targetPackageName()).contains("Massage cơ bản (NS01)");

        // Turn 7: Customer says "T muốn thử gói 390k trước" (approximate price 390k -> matches 399.000 VNĐ within 15%)
        List<FanpageBotRequest.ConversationTurn> historyTurn7 = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Thử trc 1b cũng được"),
                new FanpageBotRequest.ConversationTurn("assistant",
                        "Dạ Anh/Chị ạ, gói Dưỡng Sinh Thân Thể 530.000VNĐ sẽ giúp Anh/Chị thư giãn toàn thân trong 100 phút.")
        );
        var resTurn7 = PriceLadderTracker.evaluate("T muốn thử gói 390k trước", historyTurn7, List.of(promo), "hanoi");
        assertThat(resTurn7.isTrialRequest()).isTrue();
        assertThat(resTurn7.targetPrice()).isEqualTo(399_000L);
        assertThat(resTurn7.targetPackageName()).contains("Massage cơ bản (NS01)");
    }
}
