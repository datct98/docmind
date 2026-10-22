package com.example.docmind.service.sales;

import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.repository.RetrievedChunk;
import com.example.docmind.service.retrieval.ReciprocalRankFusion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Adversarial Empirical Challenge: PriceLadderTracker Stress Harness")
class PriceLadderAdversarialVerificationTest {

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
    // 1. DISPATCH MANDATE 1: TRANSITIONING BETWEEN BRANCHES WITH MIXED NAMES
    // =========================================================================
    @Nested
    @DisplayName("Branch Transitioning & Mixed Mentions")
    class BranchTransitionTests {

        @Test
        @DisplayName("Explicit phrase: 'em chuyển từ Sài Gòn sang Hà Nội nhé'")
        void testTransitionFromSaigonToHanoi() {
            String msg = "em chuyển từ Sài Gòn sang Hà Nội nhé";
            String branch = PriceLadderTracker.resolveBranchFromText(msg);
            assertThat(branch).isEqualTo("hanoi");

            List<FanpageBotRequest.ConversationTurn> history = List.of(
                    new FanpageBotRequest.ConversationTurn("user", "bên Quận 1 giá sao"),
                    new FanpageBotRequest.ConversationTurn("assistant", "Dạ cơ sở Quận 1 Sài Gòn gói Massage là 570.000VNĐ ạ.")
            );

            var result = PriceLadderTracker.evaluate("em chuyển từ Sài Gòn sang Hà Nội nhé có gói nào rẻ hơn không", history, List.of(), null);
            assertThat(result.isPriceObjection()).isTrue();
            // Hanoi ladder: 399k -> 239k -> 129k. Below 570k is 399k.
            assertThat(result.targetPrice()).isEqualTo(399_000L);
            assertThat(result.targetPackageName()).contains("Massage cơ bản");
        }

        @Test
        @DisplayName("Explicit phrase: 'em chuyển từ Hà Nội sang Sài Gòn nhé'")
        void testTransitionFromHanoiToSaigon() {
            String msg = "em chuyển từ Hà Nội sang Sài Gòn nhé";
            String branch = PriceLadderTracker.resolveBranchFromText(msg);
            assertThat(branch).isEqualTo("saigon");

            List<FanpageBotRequest.ConversationTurn> history = List.of(
                    new FanpageBotRequest.ConversationTurn("user", "Hà Nội giá sao"),
                    new FanpageBotRequest.ConversationTurn("assistant", "Dạ cơ sở Hà Nội gói Massage là 399.000VNĐ ạ.")
            );

            var result = PriceLadderTracker.evaluate("em chuyển từ Hà Nội sang Sài Gòn nhé có gói nào rẻ hơn không", history, List.of(), null);
            assertThat(result.isPriceObjection()).isTrue();
            // Saigon ladder: [570k, 260k, 129k]. Below 399k is 260k.
            assertThat(result.targetPrice()).isEqualTo(260_000L);
            assertThat(result.targetPackageName()).contains("Dưỡng sinh");
        }

        @Test
        @DisplayName("Multi-hop branch switching across 3 turns: Hanoi -> Saigon -> Hanoi")
        void testMultiHopBranchSwitching() {
            List<FanpageBotRequest.ConversationTurn> history = new ArrayList<>();

            // Turn 1: Hanoi
            history.add(new FanpageBotRequest.ConversationTurn("user", "giá ở Hà Nội"));
            history.add(new FanpageBotRequest.ConversationTurn("assistant", "Dạ cơ sở Hà Nội gói Massage cơ bản giá 399.000VNĐ ạ."));

            // Turn 2: Customer asks Saigon cheaper
            var t2 = PriceLadderTracker.evaluate("thế cơ sở Quận 1 Sài Gòn có gói rẻ hơn ko", history, List.of(), null);
            assertThat(t2.targetPrice()).isEqualTo(260_000L);
            history.add(new FanpageBotRequest.ConversationTurn("user", "thế cơ sở Quận 1 Sài Gòn có gói rẻ hơn ko"));
            history.add(new FanpageBotRequest.ConversationTurn("assistant", "Dạ cơ sở Quận 1 có gói Dưỡng sinh 260.000VNĐ ạ."));

            // Turn 3: Customer switches back to Hanoi
            var t3 = PriceLadderTracker.evaluate("thế ngoài cơ sở Mỹ Đình Hà Nội còn gói nào mềm hơn nữa không", history, List.of(), null);
            assertThat(t3.targetPrice()).isEqualTo(239_000L); // Hanoi next tier below 260k is 239k
            assertThat(t3.targetPackageName()).contains("Dưỡng sinh");
        }
    }

    // =========================================================================
    // 2. DISPATCH MANDATE 2: MIXED ACCENTED & UNACCENTED BARGAINING PHRASES
    // =========================================================================
    @Nested
    @DisplayName("Bargaining & Discount Phrases Detection")
    class BargainingPhraseTests {

        @ParameterizedTest
        @ValueSource(strings = {
                "co bot k",
                "bot chut",
                "giam gia k",
                "bớt đi",
                "bot di",
                "co giam ko",
                "có bớt không",
                "bớt chút đi",
                "bớt giá nhé",
                "bớt tiền được không",
                "bớt đc ko",
                "xin bớt chút",
                "xin bot",
                "giam gia ko shop",
                "giam them duoc ko",
                "xin giam",
                "duoc giam ko"
        })
        @DisplayName("Recognizes mandated bargaining phrases as objections")
        void testMandatedBargainingPhrases(String phrase) {
            assertThat(PriceLadderTracker.isPriceObjection(phrase))
                    .as("Phrase '%s' must be classified as a price objection", phrase)
                    .isTrue();
        }
    }

    // =========================================================================
    // 3. DISPATCH MANDATE 3: UNACCENTED BOOKING PHRASES DISAMBIGUATION
    // =========================================================================
    @Nested
    @DisplayName("Unaccented Booking vs Objection Disambiguation")
    class BookingPhraseTests {

        @ParameterizedTest
        @ValueSource(strings = {
                "dat lich massage ngay mai",
                "dat slot 2 nguoi",
                "dat phong vip ngay mai",
                "dat cho minh 1 suat",
                "dat coc 50k",
                "dat hen luc 15h chieu",
                "dat ban cho 3 nguoi",
                "cho chi dat lich",
                "em muon dat slot ngay mai",
                "dat ho ban minh"
        })
        @DisplayName("Unaccented booking phrases must NOT be classified as price objections")
        void testBookingPhrasesNotObjections(String bookingPhrase) {
            assertThat(PriceLadderTracker.isPriceObjection(bookingPhrase))
                    .as("Booking phrase '%s' must NOT be classified as a price objection", bookingPhrase)
                    .isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "hoi dat",
                "dat qua",
                "dat the",
                "dat vay",
                "dat the nhi",
                "dat hon",
                "dat nhat",
                "gia dat",
                "sao dat the",
                "qua dat",
                "co dat khong",
                "co dat ko"
        })
        @DisplayName("True objections with 'dat' MUST be classified as price objections")
        void testTrueObjectionsWithDat(String datObjection) {
            assertThat(PriceLadderTracker.isPriceObjection(datObjection))
                    .as("True objection '%s' must be classified as a price objection", datObjection)
                    .isTrue();
        }
    }

    // =========================================================================
    // 4. DISPATCH MANDATE 4: MEDICAL SYMPTOM QUERIES ISOLATION
    // =========================================================================
    @Nested
    @DisplayName("Medical Symptom Queries Isolation")
    class MedicalSymptomTests {

        @ParameterizedTest
        @ValueSource(strings = {
                "massage co giam dau moi vai gay ko shop",
                "massage có giảm đau mỏi vai gáy không shop",
                "liệu trình có giảm mụn không em",
                "có giảm thâm nám không shop",
                "massage có bớt đau lưng không",
                "có bớt mỏi vai gáy không",
                "liệu trình này có giảm stress không",
                "dịch vụ có giảm béo bụng không em",
                "có giảm cân không",
                "massage có giảm sưng không"
        })
        @DisplayName("Medical symptom inquiries must NOT trigger price objection down-sell")
        void testMedicalSymptomInquiries(String inquiry) {
            assertThat(PriceLadderTracker.isPriceObjection(inquiry))
                    .as("Medical inquiry '%s' must NOT be classified as a price objection", inquiry)
                    .isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "bot ơi tư vấn giúp mình",
                "chào bot nhé",
                "em là bot à",
                "bột tắm trắng",
                "bot tra xanh",
                "bot thuoc bac"
        })
        @DisplayName("Chatbot greetings and cosmetic powders must NOT trigger price objection")
        void testChatbotGreetingsAndPowders(String phrase) {
            assertThat(PriceLadderTracker.isPriceObjection(phrase))
                    .as("Phrase '%s' must NOT be classified as a price objection", phrase)
                    .isFalse();
        }

        @Test
        @DisplayName("Adversarial probe: check edge symptoms like rung toc, nhuc moi, mat ngu, te bi")
        void testAdversarialSymptomProbes() {
            // Documenting behavior on specific symptoms
            boolean rungToc = PriceLadderTracker.isPriceObjection("gội đầu có giảm rụng tóc không");
            boolean nhucMoi = PriceLadderTracker.isPriceObjection("massage có giảm nhức mỏi không");
            boolean matNgu = PriceLadderTracker.isPriceObjection("massage có giảm mất ngủ không");
            boolean teBi = PriceLadderTracker.isPriceObjection("massage có giảm tê bì không");
            boolean botCham = PriceLadderTracker.isPriceObjection("bot trả lời chậm quá");

            System.out.println("ADVERSARIAL_PROBE rungToc: " + rungToc);
            System.out.println("ADVERSARIAL_PROBE nhucMoi: " + nhucMoi);
            System.out.println("ADVERSARIAL_PROBE matNgu: " + matNgu);
            System.out.println("ADVERSARIAL_PROBE teBi: " + teBi);
            System.out.println("ADVERSARIAL_PROBE botCham: " + botCham);
        }
    }

    // =========================================================================
    // 5. DISPATCH MANDATE 5: LARGE DEPOSITS & MULTI-TURN DEPOSIT FOLLOW-UPS
    // =========================================================================
    @Nested
    @DisplayName("Large Deposits & Multi-Turn Isolation")
    class DepositTokenTests {

        @Test
        @DisplayName("Large deposit 200k in VIP quote preserves package price extraction")
        void testLargeDeposit200k() {
            String text = "Dạ gói Massage VIP bên em giá 1.990.000VNĐ, đặt cọc 200k giữ phòng VIP ạ.";
            List<Long> prices = PriceLadderTracker.extractPricesFromText(text);
            assertThat(prices).containsExactly(1_990_000L);
        }

        @Test
        @DisplayName("Large deposit 500k in combo quote preserves package price extraction")
        void testLargeDeposit500k() {
            String text = "Dạ gói Combo toàn diện 2.150.000VNĐ, cọc 500k giữ suất ưu đãi ạ.";
            List<Long> prices = PriceLadderTracker.extractPricesFromText(text);
            assertThat(prices).containsExactly(2_150_000L);
        }

        @Test
        @DisplayName("Multi-turn deposit inquiry followed by objection down-sells correctly")
        void testMultiTurnDepositThenObjection() {
            List<FanpageBotRequest.ConversationTurn> history = List.of(
                    new FanpageBotRequest.ConversationTurn("user", "giá gói massage bên em"),
                    new FanpageBotRequest.ConversationTurn("assistant", "Dạ gói Massage cơ bản bên em là 399.000VNĐ ạ."),
                    new FanpageBotRequest.ConversationTurn("user", "bên em có bắt cọc không"),
                    new FanpageBotRequest.ConversationTurn("assistant", "Dạ bên em chỉ cần cọc 50k giữ chỗ thôi ạ.")
            );

            var result = PriceLadderTracker.evaluate("nhưng giá này hơi đắt bớt đi shop", history, List.of(), "hanoi");
            assertThat(result.isPriceObjection()).isTrue();
            assertThat(result.previousPriceQuoted()).isEqualTo(399_000L);
            assertThat(result.targetPrice()).isEqualTo(239_000L);
            assertThat(result.targetPackageName()).contains("Dưỡng sinh cơ bản");
        }

        @Test
        @DisplayName("Multi-turn with 200k deposit on VIP package followed by objection")
        void testMultiTurnVipDepositThenObjection() {
            List<FanpageBotRequest.ConversationTurn> history = List.of(
                    new FanpageBotRequest.ConversationTurn("user", "giá gói VIP"),
                    new FanpageBotRequest.ConversationTurn("assistant", "Dạ gói VIP là 1.990.000VNĐ ạ."),
                    new FanpageBotRequest.ConversationTurn("user", "cọc sao em"),
                    new FanpageBotRequest.ConversationTurn("assistant", "Dạ anh cọc 200k giữ phòng VIP nhé.")
            );

            var result = PriceLadderTracker.evaluate("cao thế em có gói nào rẻ hơn không", history, List.of(), "hanoi");
            assertThat(result.isPriceObjection()).isTrue();
            assertThat(result.previousPriceQuoted()).isEqualTo(1_990_000L);
            // In Hanoi ladder [399k, 239k, 129k], next tier below 1.990k is 399k
            assertThat(result.targetPrice()).isEqualTo(399_000L);
            assertThat(result.targetPackageName()).contains("Massage cơ bản");
        }
    }

    // =========================================================================
    // 6. MONOTONICITY & CATALOG FLOOR INVARIANTS HARNESS
    // =========================================================================
    @Nested
    @DisplayName("Monotonicity & Catalog Floor Invariant Stress Harness")
    class InvariantHarnessTests {

        @Test
        @DisplayName("Monotonicity invariant: target price monotonically decreases and clamps at 129k floor with 10% promo")
        void testMonotonicityDownSellLadder() {
            List<FanpageBotRequest.ConversationTurn> history = new ArrayList<>();
            history.add(new FanpageBotRequest.ConversationTurn("assistant", "Dạ gói Massage cơ bản Hà Nội giá 399.000VNĐ ạ."));

            var promo = promoChunk("CHÍNH SÁCH ƯU ĐÃI: Giảm 10% khi mua liệu trình từ 5 buổi hoặc từ 5 voucher.");

            // Objection 1: 399k -> 239k
            var r1 = PriceLadderTracker.evaluate("cao thế shop", history, List.of(promo), "hanoi");
            assertThat(r1.targetPrice()).isEqualTo(239_000L);
            assertThat(r1.isCatalogFloor()).isFalse();

            history.add(new FanpageBotRequest.ConversationTurn("user", "cao thế shop"));
            history.add(new FanpageBotRequest.ConversationTurn("assistant", "Dạ bên em có gói Dưỡng sinh 239.000VNĐ ạ."));

            // Objection 2: 239k -> 129k
            var r2 = PriceLadderTracker.evaluate("vẫn hơi đắt bớt đi", history, List.of(promo), "hanoi");
            assertThat(r2.targetPrice()).isEqualTo(129_000L);
            assertThat(r2.isCatalogFloor()).isTrue();
            assertThat(r2.discountPolicy()).contains("Giảm 10%");

            history.add(new FanpageBotRequest.ConversationTurn("user", "vẫn hơi đắt bớt đi"));
            history.add(new FanpageBotRequest.ConversationTurn("assistant", "Dạ gói Lấy ráy tai 129.000VNĐ là mức giá sàn bên em rồi ạ."));

            // Objection 3: 129k -> holds 129k floor
            var r3 = PriceLadderTracker.evaluate("có gói nào rẻ hơn nữa không", history, List.of(promo), "hanoi");
            assertThat(r3.targetPrice()).isEqualTo(129_000L);
            assertThat(r3.isCatalogFloor()).isTrue();
            assertThat(r3.discountPolicy()).contains("Giảm 10%");
            assertThat(r3.shouldSuppressHandoff()).isTrue();

            // Objection 4: asking for free -> retains floor price, never returns 0 VNĐ
            var r4 = PriceLadderTracker.evaluate("có cái nào free 0đ không shop", history, List.of(promo), "hanoi");
            assertThat(r4.targetPrice()).isEqualTo(129_000L);
            assertThat(r4.targetPrice()).isGreaterThan(0L);
        }
    }
}
