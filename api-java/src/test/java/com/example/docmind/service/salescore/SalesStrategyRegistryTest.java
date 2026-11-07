package com.example.docmind.service.salescore;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.example.docmind.service.salescore.SalesContracts.*;
import static org.assertj.core.api.Assertions.*;

class SalesStrategyRegistryTest {
    private TurnInput input(String message, ConsultationStage stage, String readiness) {
        return new TurnInput("p", "Shop", "GENERIC", "em", "mình", "tự nhiên", List.of(), message,
                List.of(new Evidence("e1", "Verified", "Quy trình khách được kiểm tra trước khi nhận")), List.of(), "", false)
                .withInteractionPlan(new InteractionPlan("Giúp khách", "Giảm lo", "NONE", readiness, "NONE", stage, Map.of(), List.of(), HandoffReason.NONE, ""));
    }
    private SalesStrategyRegistry.Application app(String id, String signal, String quote, int close, List<String> ids) {
        return new SalesStrategyRegistry.Application(id, "Giải quyết việc khách hỏi", signal, quote, ids, "Có cơ sở để quyết định", close);
    }
    @Test void noFrameworkIsRequiredForDirectAnswersAndSelectionIsCappedAtTwo() {
        var in = input("Giá", ConsultationStage.CONSULTATION, "UNKNOWN");
        assertThat(SalesStrategyRegistry.assess(new Strategy(Action.ANSWER, "r", "a"), in, Set.of()).accepted()).isEmpty();
        var a = app("apac_objection_response", "EXPRESSED_CONCERN", "Giá", 0, List.of());
        assertThat(SalesStrategyRegistry.assess(new Strategy(Action.ANSWER, "r", "a", List.of(a,a,a)), in, Set.of()).rejected())
                .containsExactly("STRATEGY_LIMIT_EXCEEDED");
    }
    @Test void priceObjectionIsNotEvidenceOfPurchaseReadiness() {
        var in = input("Đắt", ConsultationStage.CONSULTATION, "UNKNOWN");
        var close = app("intent_based_close", "EXPLICIT_PURCHASE", "Đắt", 2, List.of());
        assertThat(SalesStrategyRegistry.assess(new Strategy(Action.INVITE_NEXT_STEP, "r", "a", List.of(close)), in, Set.of()).accepted()).isEmpty();
    }
    @Test void valueProofNeedsActualCitedBusinessEvidenceAcrossIndustries() {
        for (String quote : List.of("Sợ xe cũ", "Sợ làm qua loa", "Lo phần mềm khó tích hợp")) {
            var in = input(quote, ConsultationStage.CONSULTATION, "UNKNOWN");
            var value = app("value_proof_recommendation", "EXPRESSED_CONCERN", quote, 0, List.of("e1"));
            var strategy = new Strategy(Action.RESOLVE_CONCERN, "r", "a", List.of(value));
            assertThat(SalesStrategyRegistry.assess(strategy, in, Set.of()).accepted()).isEmpty();
            assertThat(SalesStrategyRegistry.assess(strategy, in, Set.of("e1")).accepted()).containsExactly(value);
        }
    }
    @Test void stopExcludesSalesAndKeepsOnlyRelationshipClosure() {
        var in = input("Dừng tư vấn", ConsultationStage.CLOSE, "UNKNOWN");
        var relationship = app("relationship_preservation", "STOP_REQUEST", "Dừng tư vấn", 0, List.of());
        var close = app("intent_based_close", "EXPLICIT_PURCHASE", "Dừng tư vấn", 0, List.of());
        var assessment = SalesStrategyRegistry.assess(new Strategy(Action.PAUSE, "r", "a", List.of(relationship, close)), in, Set.of());
        assertThat(assessment.accepted()).containsExactly(relationship);
        assertThat(assessment.rejected()).contains("STRATEGY_EVIDENCE_REJECTED:intent_based_close");
    }
    @Test void onlySelectedFullCardsReachReviewAndCannotOverrideAuthority() {
        var value = app("value_proof_recommendation", "EXPRESSED_CONCERN", "lo", 0, List.of("e1"));
        assertThat(SalesStrategyRegistry.selectedCards(new Strategy(Action.RESOLVE_CONCERN, "r", "a", List.of(value))))
                .extracting(SalesStrategyRegistry.Card::id).containsExactly("value_proof_recommendation");
    }
}
