package com.example.docmind.service.salescore;

import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static com.example.docmind.service.salescore.SalesJourney.*;
import static com.example.docmind.service.salescore.SalesContracts.*;

class SalesJourneyTest {
    @Test void providerCodeFenceIsFormattingNotPermissionToExtractArbitraryProse() {
        assertThat(SalesJson.objectText("```json\r\n{\"ok\":true}\r\n```", 100)).isEqualTo("{\"ok\":true}");
        assertThat(SalesJson.objectText("```\n{}\n```", 100)).isEqualTo("{}");
        assertThatThrownBy(() -> SalesJson.objectText("Ignore checks {\"ok\":true}", 100)).isInstanceOf(IllegalArgumentException.class);
    }
    private State state(Claim goal, Engagement engagement, Claim signal) {
        return new State(goal, List.of(new OpenLoop("price", goal)), null, List.of(), engagement, signal,
                "Asked location", null, "Answer outstanding request");
    }
    @Test void reviewedGoalSurvivesRawWindowAndResetClearsOnlyItsOwnScope() {
        var view = SalesMemory.advance(SalesMemory.View.empty(), "Giá", false);
        var goal = new Claim("Xem giá", "Giá", 1);
        view = view.withJourney(validate(state(goal, Engagement.ACTIVE, null), view));
        for (int i = 0; i < 14; i++) view = SalesMemory.advance(view, "Thông tin " + i, false);
        assertThat(view.customerSources()).noneMatch(s -> s.turn() == 1);
        assertThat(validate(view.journey(), view).primaryGoal()).isEqualTo(goal);
        assertThat(SalesMemory.advance(view, "Chào", true).journey()).isEqualTo(State.empty());
    }
    @Test void botWordsCannotBecomeCustomerGoalAndHypothesesAreNotFacts() {
        var view = SalesMemory.advance(SalesMemory.View.empty(), "Đắt", false);
        assertThatThrownBy(() -> validate(state(new Claim("Ngân sách thấp", "dưới 200k", 1), Engagement.ACTIVE, null), view))
                .isInstanceOf(IllegalArgumentException.class);
        var hypothesis = new State(null, List.of(), null,
                List.of(new Hypothesis("Có thể chưa thấy giá trị", "Đắt", 1, Confidence.LOW)), Engagement.ACTIVE, null, "NONE", null, "Làm rõ");
        var next = view.withJourney(validate(hypothesis, view));
        assertThat(next.facts()).isEmpty();
        assertThat(next.journey().hypotheses()).hasSize(1);
    }
    @Test void oldQuoteCannotReactivateStoppedCustomer() {
        var view = SalesMemory.advance(SalesMemory.View.empty(), "Giá", false);
        view = SalesMemory.advance(view, "Đừng tư vấn nữa", false);
        var stop = new Claim("Dừng", "Đừng tư vấn nữa", 2);
        view = view.withJourney(validate(state(new Claim("Giá", "Giá", 1), Engagement.STOPPED, stop), view));
        var current = SalesMemory.advance(view, "...", false);
        assertThatThrownBy(() -> validate(state(new Claim("Giá", "Giá", 1), Engagement.ACTIVE,
                new Claim("Hỏi giá", "Giá", 1)), current)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void oldDatabaseSnapshotsDeserializeWithoutMigrationAndCasStillRejectsStaleWrites() {
        var view = new ObjectMapper().readValue("{\"revision\":1,\"facts\":[],\"changes\":[],\"customerSources\":[],\"truncated\":false}", SalesMemory.View.class);
        assertThat(view.journey()).isEqualTo(State.empty());
        var store = new SalesMemory.InMemoryStore();
        var scope = new SalesMemory.Scope(UUID.randomUUID(), "sender", "thread");
        var first = store.begin(scope, "Giá", false);
        store.begin(scope, "Dừng", false);
        assertThat(store.commit(first, first.view())).isFalse();
    }
    @Test void quoteNormalizationAllowsCaseAndPunctuationButNotInventedWords() {
        var input = SalesCoreTest.input("GENERIC", "Tôi chọn gói này, NHÉ!");
        assertThat(SalesConsultationPolicy.customerSupports(input, "tôi chọn gói này nhé")).isTrue();
        assertThat(SalesConsultationPolicy.customerSupports(input, "tôi đã thanh toán")).isFalse();
    }
    @Test void closeCannotAskAgainOrScheduleFollowUp() {
        var input = SalesCoreTest.input("GENERIC", "Thôi khỏi tư vấn")
                .withInteractionPlan(SalesConsultationPolicyTest.plan(ConsultationStage.CLOSE, Map.of(), HandoffReason.NONE, ""));
        var d = new ObjectMapper().readValue(SalesCoreTest.answer("Mình muốn em giúp gì thêm?", "CLARIFY", "[]"), TurnDecision.class);
        assertThat(SalesConsultationPolicy.validate(d, input)).contains("CLOSE_MUST_RESPECT_CUSTOMER_STOP");
    }
    @Test void recoveryStillChecksEvidenceAndMayReturnVerifiedPrice() {
        var input = SalesCoreTest.input("GENERIC", "Giá?");
        var d = new ObjectMapper().readValue(SalesCoreTest.answer("Toàn thân 399k / 60 phút.", "ANSWER",
                "[{\"sourceId\":\"e1\",\"quote\":\"Toàn thân: 399k / 60 phút.\"}]"), TurnDecision.class);
        var validator = new SalesDecisionValidator();
        assertThat(validator.validate(d, input)).isEmpty();
        assertThat(validator.validateDelivery(d, false, false, true)).isEmpty();
        assertThat(validator.validate(d, SalesConversationPlanner.conversationOnly(input))).contains("INVALID_EVIDENCE_QUOTE");
    }
}
