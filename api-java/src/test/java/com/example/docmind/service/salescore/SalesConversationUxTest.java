package com.example.docmind.service.salescore;

import com.example.docmind.service.llm.LlmClient;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.example.docmind.service.salescore.SalesContracts.*;

/** Permission/transport regressions; actual tone and helpfulness are assessed in live customer journeys. */
class SalesConversationUxTest {
    private final tools.jackson.databind.ObjectMapper json = new tools.jackson.databind.ObjectMapper();
    private final SalesDecisionValidator validator = new SalesDecisionValidator();

    @Test void mentioningMoneyDoesNotForceAnotherGenerationOrForbidARelevantQuestion() {
        var answer = json.readValue(SalesCoreTest.answer("Giá là 399k. Mình muốn biết phần nào trong dịch vụ?", "ANSWER",
                "[{\"sourceId\":\"e1\",\"quote\":\"Toàn thân: 399k / 60 phút.\"}]"), TurnDecision.class);
        assertThat(validator.validateDelivery(answer, true, false, false, ReplyMode.DIRECT)).isEmpty();
        assertThat(validator.validateDelivery(answer, true, false, false, ReplyMode.PRICE_MOMENT))
                .contains("PRICE_BUBBLE_QUESTION_BELONGS_IN_CONTINUATION");
        var split = new TurnDecision(answer.understanding(), answer.strategy(),
                List.of(answer.bubbles().getFirst(), new Bubble("Phần tay chân vẫn là điều mình ưu tiên.", List.of())), "", List.of(), null);
        assertThat(validator.validateDelivery(split, true, false, false, ReplyMode.DIRECT)).isEmpty();
        assertThat(validator.validateDelivery(split, true, false, false, ReplyMode.PRICE_MOMENT)).isNotEmpty();
    }

    @Test void specificPriceAnswerDoesNotRequirePersonalDiscoveryButRecommendationStillDoes() {
        var input = SalesCoreTest.input("BEAUTY_SPA", "Gói toàn thân bao nhiêu?")
                .withInteractionPlan(SalesConsultationPolicyTest.plan(ConsultationStage.CONSULTATION, Map.of(), HandoffReason.NONE, ""));
        var answer = json.readValue(SalesCoreTest.answer("Toàn thân 399k / 60 phút.", "ANSWER",
                "[{\"sourceId\":\"e1\",\"quote\":\"Toàn thân: 399k / 60 phút.\"}]"), TurnDecision.class);
        assertThat(validator.validate(answer, input)).isEmpty();
        var recommendation = new TurnDecision(answer.understanding(), new Strategy(Action.RECOMMEND, "Phù hợp", "Chọn"),
                answer.bubbles(), "", List.of(), null);
        assertThat(validator.validate(recommendation, input)).contains("OFFER_BEFORE_REQUIRED_CUSTOMER_CONTEXT");
    }

    @Test void plannerChoosesWholeBubblesWithoutSentenceSplittingOrUnsolicitedContinuation() {
        List<String> bubbles = List.of("Chào mình, em sẵn lòng tư vấn.", "Mình đang muốn thư giãn phần nào ạ?");
        LlmClient client = new LlmClient() {
            public String name() { return "ux-fixture"; }
            public String complete(String prompt) {
                assertThat(prompt).startsWith("SALES_PLAN");
                return SalesConsultationPolicyTest.plan("DISCOVERY", false, null)
                        .replace("\"conversationalReply\":null", "\"conversationalBubbles\":" + json.writeValueAsString(bubbles));
            }
        };
        try (var core = new SalesTurnOrchestrator(client, new IndustryProfileRegistry(), 5000)) {
            core.setPlanningEnabled(true);
            var result = core.runPaced(SalesCoreTest.input("BEAUTY_SPA", "Giá"), () -> { throw new AssertionError("Not a catalog turn"); });
            assertThat(result.texts()).containsExactlyElementsOf(bubbles);
            assertThat(result.attempts()).isEqualTo(1);
            assertThat(result.decision().followUp()).isNull();
            assertThat(result.handoff()).isFalse();
        }
    }

    @Test void helpingChooseABranchIsNotMistakenForPitchingAProduct() {
        var input = SalesCoreTest.input("BEAUTY_SPA", "Cơ sở nào gần khu tôi?")
                .withInteractionPlan(SalesConsultationPolicyTest.plan(ConsultationStage.LOCATION, Map.of(), HandoffReason.NONE, ""));
        var branch = json.readValue(SalesCoreTest.answer("Cơ sở ở khu mình có thể thuận tiện hơn.", "RECOMMEND", "[]"), TurnDecision.class);
        assertThat(SalesConsultationPolicy.validate(branch, input)).isEmpty();
        var prematureQuote = json.readValue(SalesCoreTest.answer("Gói này 399k.", "RECOMMEND", "[]"), TurnDecision.class);
        assertThat(SalesConsultationPolicy.validate(prematureQuote, input)).contains("LOCATION_TURN_ANSWER_BRANCH_ONLY_NO_PRODUCT_PRICE");
    }

    @Test void streetAddressesAreNotMistakenForDongCurrency() {
        assertThat(SalesDecisionValidator.monetaryNumbers("319 Đường Hồng Tiến, 6 đường Bích Câu, 2 địa điểm")).isEmpty();
        for (var amount : List.of("399.000đ", "399.000 đ", "399.000VNĐ", "399k", "399000 đồng"))
            assertThat(SalesDecisionValidator.monetaryNumbers(amount)).containsExactly("399000");
    }
}
