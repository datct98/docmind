package com.example.docmind.service.sales;

import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.repository.RetrievedChunk;
import com.example.docmind.service.retrieval.ReciprocalRankFusion;
import com.example.docmind.service.text.VietnameseQueryNormalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Adversarial Stress Test: PriceLadderTracker Monotonicity, Floor, and Branch Switching")
class PriceLadderStressTest {

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

    // =========================================================================
    // 1. DISPATCH TASK 1: OBJECTION BURSTS & KEYWORD COVERAGE
    // =========================================================================

    @Test
    @DisplayName("Recognizes baseline objection phrases: 'cao the', 'dat qua', 'co gi re hon nua ko'")
    void testBaselineDispatchObjectionPhrases() {
        assertThat(PriceLadderTracker.isPriceObjection("cao the")).isTrue();
        assertThat(PriceLadderTracker.isPriceObjection("dat qua")).isTrue();
        assertThat(PriceLadderTracker.isPriceObjection("co gi re hon nua ko")).isTrue();
    }

    @Test
    @DisplayName("DEFECT 1A: 'bớt đi' must be recognized as a price objection")
    void testDispatchObjectionPhraseBotDi() {
        assertThat(PriceLadderTracker.isPriceObjection("bớt đi"))
                .as("Vietnamese bargaining term 'bớt đi' must be detected by PRICE_OBJECTION_PATTERN")
                .isTrue();
    }

    @Test
    @DisplayName("DEFECT 1B: 'co giam ko' must be recognized as a price objection")
    void testDispatchObjectionPhraseCoGiamKo() {
        assertThat(PriceLadderTracker.isPriceObjection("co giam ko"))
                .as("Discount inquiry 'co giam ko' must be detected by PRICE_OBJECTION_PATTERN")
                .isTrue();
    }

    // =========================================================================
    // 2. DISPATCH TASK 2: BRANCH SWITCHING DURING DOWN-SELL
    // =========================================================================

    @Test
    @DisplayName("Branch switching: Starting in Hanoi (399k) then customer switches to Saigon District 1")
    void testBranchSwitchingHanoiToSaigon() {
        // Turn 1: Quoted in Hanoi (399.000 VNĐ)
        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("user", "giá cơ sở Hà Nội"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ gói Massage cơ bản cơ sở Hà Nội (Mỹ Đình) bên em có giá từ 399.000VNĐ ạ.")
        );

        // Turn 2: Customer switches to Saigon Q1 and objects to price
        String customerMsg = "thế bên cơ sở Sài Gòn Quận 1 có gói nào rẻ hơn không";
        var result = PriceLadderTracker.evaluate(customerMsg, history, List.of(), null);

        assertThat(result.isPriceObjection()).isTrue();
        // In Saigon ladder [570k, 260k, 129k], next tier below 399k is 260k
        assertThat(result.targetPrice())
                .as("Target price in Saigon must be strictly lower than 399.000 VNĐ and map to Saigon tier")
                .isEqualTo(260_000L);
        assertThat(result.targetPackageName()).contains("Dưỡng sinh");
    }

    @Test
    @DisplayName("DEFECT 2: Branch switching from Saigon to Hanoi fails because resolveBranch misses customerMessage check for Hanoi")
    void testBranchSwitchingSaigonToHanoi() {
        // Turn 1: Quoted in Saigon (570.000 VNĐ)
        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("user", "cơ sở Quận 1 có gói nào"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ cơ sở Quận 1 Sài Gòn bên em có gói Massage cơ bản giá 570.000VNĐ ạ.")
        );

        // Turn 2: Customer asks about Hanoi with price objection
        String customerMsg = "thế ở cơ sở Hà Nội có gói nào rẻ hơn không shop";
        String resolvedBranch = PriceLadderTracker.resolveBranch(customerMsg, history, null);

        assertThat(resolvedBranch)
                .as("When customer explicitly mentions 'Hà Nội' in customerMessage, resolveBranch must return 'hanoi', not sticky Saigon from history")
                .isEqualTo("hanoi");

        var result = PriceLadderTracker.evaluate(customerMsg, history, List.of(), null);
        assertThat(result.targetPrice()).isEqualTo(399_000L);
    }

    // =========================================================================
    // 3. DISPATCH TASK 3: MONOTONICITY VERIFICATION (NEVER INCREASES)
    // =========================================================================

    @Test
    @DisplayName("Strict monotonicity: 10 rapid bursts of objections never produce price oscillation")
    void testStrictMonotonicityAcrossTenObjections() {
        List<FanpageBotRequest.ConversationTurn> history = new ArrayList<>();
        history.add(new FanpageBotRequest.ConversationTurn("assistant", "Dạ bên em có gói Massage 570.000VNĐ (Sài Gòn) hoặc 399.000VNĐ (Hà Nội)."));

        String[] objectionBurst = {
                "cao the",
                "hơi đắt",
                "nhưng hơi đắt",
                "đắt quá",
                "mắc quá",
                "có gói nào rẻ hơn không",
                "tiết kiệm hơn",
                "gói nào mềm hơn",
                "rẻ nhất là bao nhiêu",
                "còn gói nào rẻ hơn nữa không"
        };

        long lastPrice = 399_000L;

        for (int i = 0; i < objectionBurst.length; i++) {
            String objection = objectionBurst[i];
            var result = PriceLadderTracker.evaluate(objection, history, List.of(), "hanoi");

            assertThat(result.isPriceObjection()).isTrue();
            long currentTarget = result.targetPrice();

            assertThat(currentTarget)
                    .as("Turn %d: price must be <= previous price (%d vs %d)", i, currentTarget, lastPrice)
                    .isLessThanOrEqualTo(lastPrice);

            assertThat(currentTarget)
                    .as("Price must never drop below catalog floor 129.000 VNĐ")
                    .isGreaterThanOrEqualTo(PriceLadderTracker.CATALOG_FLOOR_PRICE);

            history.add(new FanpageBotRequest.ConversationTurn("user", objection));
            history.add(new FanpageBotRequest.ConversationTurn("assistant", "Dạ bên em có gói giá " + PriceLadderTracker.formatPrice(currentTarget) + " ạ."));
            lastPrice = currentTarget;
        }

        assertThat(lastPrice).isEqualTo(PriceLadderTracker.CATALOG_FLOOR_PRICE);
    }

    @Test
    @DisplayName("DEFECT 3: Auxiliary sub-floor amount (e.g. 50k deposit or voucher) causes target price to jump upwards to 129k")
    void testMonotonicityWhenAuxiliaryAmountInHistory() {
        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ gói Massage 399.000VNĐ, đặt cọc 50k giữ chỗ ạ.")
        );

        Long extractedPrice = PriceLadderTracker.extractPreviousQuotedPrice(history);
        var result = PriceLadderTracker.evaluate("cao the", history, List.of(), "hanoi");

        // The true package price quoted was 399k, not 50k. If extractedPrice is 50k, targetPrice jumps up to 129k.
        assertThat(extractedPrice)
                .as("Deposit or small discount token (50k) must not be extracted as the package quoted price")
                .isEqualTo(399_000L);
        assertThat(result.targetPrice())
                .as("Next tier down-sell from 399k should be 239k, not 129k")
                .isEqualTo(239_000L);
    }

    // =========================================================================
    // 4. DISPATCH TASK 4: CATALOG FLOOR & ZERO VNĐ FREE SERVICE PREVENTION
    // =========================================================================

    @Test
    @DisplayName("Catalog floor at 129.000 VNĐ: isCatalogFloor=true, 10% promo attached, price NEVER 0 VNĐ")
    void testCatalogFloorInvariants() {
        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ gói lấy ráy tai cơ bản bên em có giá 129.000VNĐ/30 phút ạ.")
        );

        var promo = promoChunk("CHÍNH SÁCH ƯU ĐÃI: Giảm 10% khi mua liệu trình từ 5 buổi hoặc từ 5 voucher.");
        var result = PriceLadderTracker.evaluate("có gói nào rẻ hơn không shop", history, List.of(promo), "hanoi");

        // 1. isCatalogFloor == true
        assertThat(result.isCatalogFloor()).isTrue();

        // 2. targetPrice is exactly 129.000 VNĐ (NEVER 0 VNĐ or null)
        assertThat(result.targetPrice()).isEqualTo(129_000L);
        assertThat(result.targetPrice()).isGreaterThan(0L);

        // 3. 10% promo policy attached
        assertThat(result.discountPolicy()).isNotNull();
        assertThat(result.discountPolicy()).contains("Giảm 10%");

        // 4. shouldSuppressHandoff == true
        assertThat(result.shouldSuppressHandoff()).isTrue();

        // 5. Rationale must mention floor price
        assertThat(result.rationale()).contains("129.000");
    }

    @Test
    @DisplayName("Catalog floor: When customer asks for free service, price ladder holds 129k floor and never returns 0 VNĐ")
    void testRejectsZeroVndFreeService() {
        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ gói 129.000VNĐ là gói mềm nhất bên em rồi ạ.")
        );

        var result = PriceLadderTracker.evaluate("có cái nào free không", history, List.of(), "hanoi");
        assertThat(result.targetPrice()).isNotNull();
        assertThat(result.targetPrice()).isGreaterThanOrEqualTo(PriceLadderTracker.CATALOG_FLOOR_PRICE);
    }

    // =========================================================================
    // 5. HOMOPHONE DISAMBIGUATION & BARGAINING KEYWORD STRESS TESTS
    // =========================================================================

    @Test
    @DisplayName("Mitigates unaccented homophone collision: booking inquiries with 'dat' must NOT be classified as price objections")
    void testBookingWithDatDoesNotTriggerPriceObjection() {
        assertThat(PriceLadderTracker.isPriceObjection("chị muon dat lich mai")).isFalse();
        assertThat(PriceLadderTracker.isPriceObjection("dat lich")).isFalse();
        assertThat(PriceLadderTracker.isPriceObjection("dat phong")).isFalse();
        assertThat(PriceLadderTracker.isPriceObjection("dat cho")).isFalse();
        assertThat(PriceLadderTracker.isPriceObjection("dat coc")).isFalse();
        assertThat(PriceLadderTracker.isPriceObjection("dat hen")).isFalse();
        assertThat(PriceLadderTracker.isPriceObjection("dat ban")).isFalse();
        assertThat(PriceLadderTracker.isPriceObjection("cho em dat voi")).isFalse();
        assertThat(PriceLadderTracker.isPriceObjection("em muon dat slot 10h")).isFalse();
        assertThat(PriceLadderTracker.isPriceObjection("dat giup chi")).isFalse();
        assertThat(PriceLadderTracker.isPriceObjection("dat ho em")).isFalse();
    }

    @Test
    @DisplayName("Vietnamese bargaining and discount inquiries must be detected by PRICE_OBJECTION_PATTERN")
    void testBargainingAndDiscountPhrasesDetected() {
        assertThat(PriceLadderTracker.isPriceObjection("có bớt không")).isTrue();
        assertThat(PriceLadderTracker.isPriceObjection("bớt")).isTrue();
        assertThat(PriceLadderTracker.isPriceObjection("bot")).isTrue();
        assertThat(PriceLadderTracker.isPriceObjection("có giảm không")).isTrue();
        assertThat(PriceLadderTracker.isPriceObjection("giam")).isTrue();
        assertThat(PriceLadderTracker.isPriceObjection("giảm")).isTrue();
        assertThat(PriceLadderTracker.isPriceObjection("bớt chút đi")).isTrue();
        assertThat(PriceLadderTracker.isPriceObjection("co bot ko")).isTrue();
        assertThat(PriceLadderTracker.isPriceObjection("giam gia")).isTrue();
        assertThat(PriceLadderTracker.isPriceObjection("xin giảm")).isTrue();
    }

    @Test
    @DisplayName("True objections with 'dat' must be detected by PRICE_OBJECTION_PATTERN")
    void testTrueObjectionsWithDatDetected() {
        assertThat(PriceLadderTracker.isPriceObjection("gia hoi dat")).isTrue();
        assertThat(PriceLadderTracker.isPriceObjection("dat qua")).isTrue();
        assertThat(PriceLadderTracker.isPriceObjection("sao dat the")).isTrue();
        assertThat(PriceLadderTracker.isPriceObjection("sao dat vay")).isTrue();
        assertThat(PriceLadderTracker.isPriceObjection("hoi dat")).isTrue();
        assertThat(PriceLadderTracker.isPriceObjection("dat the")).isTrue();
        assertThat(PriceLadderTracker.isPriceObjection("dat the nhi")).isTrue();
        assertThat(PriceLadderTracker.isPriceObjection("dat vay")).isTrue();
        assertThat(PriceLadderTracker.isPriceObjection("dat hon")).isTrue();
        assertThat(PriceLadderTracker.isPriceObjection("dat nhat")).isTrue();
        assertThat(PriceLadderTracker.isPriceObjection("gia dat")).isTrue();
        assertThat(PriceLadderTracker.isPriceObjection("qua dat")).isTrue();
        assertThat(PriceLadderTracker.isPriceObjection("thay dat")).isTrue();
        assertThat(PriceLadderTracker.isPriceObjection("co dat khong")).isTrue();
        assertThat(PriceLadderTracker.isPriceObjection("co dat ko")).isTrue();
    }

    @Test
    @DisplayName("Medical symptom inquiries and chatbot greetings must NOT be classified as price objections")
    void testMedicalSymptomAndChatbotGreetingsDoNotTriggerPriceObjection() {
        assertThat(PriceLadderTracker.isPriceObjection("liệu trình này có giảm mụn không em")).isFalse();
        assertThat(PriceLadderTracker.isPriceObjection("massage có giảm đau mỏi không")).isFalse();
        assertThat(PriceLadderTracker.isPriceObjection("có giảm thâm không")).isFalse();
        assertThat(PriceLadderTracker.isPriceObjection("có giảm béo bụng không")).isFalse();
        assertThat(PriceLadderTracker.isPriceObjection("giảm stress hiệu quả")).isFalse();
        assertThat(PriceLadderTracker.isPriceObjection("liệu trình có bớt mụn không")).isFalse();
        assertThat(PriceLadderTracker.isPriceObjection("có bớt đau không em")).isFalse();
        assertThat(PriceLadderTracker.isPriceObjection("bot ơi tư vấn giúp mình")).isFalse();
        assertThat(PriceLadderTracker.isPriceObjection("chào bot")).isFalse();
        assertThat(PriceLadderTracker.isPriceObjection("em là bot à")).isFalse();
    }

    // =========================================================================
    // 6. LARGE DEPOSIT & MULTI-TURN DEPOSIT STRESS TESTS
    // =========================================================================

    @Test
    @DisplayName("Adversarial: Large deposit (200k) on VIP package preserves monotonic down-sell")
    void testMonotonicityWithLargeDepositInHistory() {
        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ gói VIP 1.990.000VNĐ, cọc 200k giữ phòng ạ.")
        );

        Long extractedPrice = PriceLadderTracker.extractPreviousQuotedPrice(history);
        var result = PriceLadderTracker.evaluate("cao the", history, List.of(), "hanoi");

        assertThat(extractedPrice).isEqualTo(1_990_000L);
        assertThat(result.targetPrice())
                .as("Down-sell from 1.990k must not clamp to floor 129k immediately")
                .isLessThanOrEqualTo(399_000L)
                .isGreaterThanOrEqualTo(PriceLadderTracker.CATALOG_FLOOR_PRICE);
    }

    @Test
    @DisplayName("Adversarial: Multi-turn deposit isolation preserves down-sell progression")
    void testMonotonicityAcrossDepositFollowUpTurns() {
        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("user", "giá massage"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ gói Massage cơ bản bên em là 399.000VNĐ ạ."),
                new FanpageBotRequest.ConversationTurn("user", "cọc thế nào em"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ anh chỉ cần đặt cọc 50k giữ chỗ thôi ạ.")
        );

        var result = PriceLadderTracker.evaluate("nhưng cao the bớt đi em", history, List.of(), "hanoi");

        assertThat(result.previousPriceQuoted()).isEqualTo(399_000L);
        assertThat(result.targetPrice())
                .as("Down-sell from 399k must step to 239k, ignoring 50k deposit in history")
                .isEqualTo(239_000L);
    }
}
