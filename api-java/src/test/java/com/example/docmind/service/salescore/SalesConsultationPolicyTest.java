package com.example.docmind.service.salescore;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.example.docmind.service.salescore.SalesContracts.*;

class SalesConsultationPolicyTest {
    @Test void optionalMemoryCannotInvalidateOtherwiseUsablePlan() {
        var raw = plan("CONSULTATION", true, null).replace("\"memoryUpdates\":[]", "\"memoryUpdates\":[{\"key\":\"location\",\"value\":\"Hoàn Kiếm\",\"customerQuote\":\"Hoàn Kiếm\",\"sourceTurn\":2}]");
        var parsed = SalesConversationPlanner.parse(raw);
        assertThat(parsed.memoryProposalRejected()).isFalse();
        assertThat(parsed.memoryUpdates().getFirst().remove()).isFalse();
        var badMemory = SalesConversationPlanner.parse(raw.replace("\"sourceTurn\":2", "\"sourceTurn\":\"not-a-turn\""));
        assertThat(badMemory.memoryProposalRejected()).isTrue();
        assertThat(badMemory.decisionCheck().stage()).isEqualTo(ConsultationStage.CONSULTATION);
        assertThat(badMemory.memoryUpdates()).isEmpty();
        var strayAuthority = SalesConversationPlanner.parse(plan("DISCOVERY", false, "Chào mình")
            .replace("\"handoffReason\":\"NONE\",", "").replace("\"memoryUpdates\":[]", "\"handoffReason\":\"EXPLICIT_HUMAN_REQUEST\",\"memoryUpdates\":[]"));
        assertThat(strayAuthority.decisionCheck().handoffReason()).isEqualTo(HandoffReason.NONE);
    }
    static String check(String stage) {
        return "\"decisionCheck\":{\"customerBenefit\":\"Giúp đúng nhu cầu\",\"questionPurpose\":\"Làm rõ phần thiếu\","
            + "\"readinessEvidence\":\"UNKNOWN\",\"previousApproach\":\"NONE\",\"responseSignal\":\"UNKNOWN\",\"unresolvedConcern\":\"NONE\","
            + "\"stage\":\"" + stage + "\",\"contextEvidence\":{\"need\":\"cổ vai gáy chân tay\"},\"handoffReason\":\"NONE\",\"handoffQuote\":\"\"}";
    }
    static String plan(String stage, boolean evidence, String reply) {
        return "{\"latestContribution\":\"Khách vừa nêu thông tin\",\"nextDecision\":\"Hỏi đúng điều còn thiếu\",\"reason\":\"Chưa biết đủ\",\"needsBusinessEvidence\":"
            + evidence + "," + check(stage) + ",\"memoryUpdates\":[],\"conversationalReply\":" + new tools.jackson.databind.ObjectMapper().writeValueAsString(reply) + "}";
    }
    static InteractionPlan plan(ConsultationStage stage, Map<String,String> context, HandoffReason reason, String quote) {
        return new InteractionPlan("Giúp tiếp", "Đúng nhu cầu", "Làm rõ", "UNKNOWN", "NONE", stage, context,
            List.of(new ContextRequirement("need", "Nhu cầu"), new ContextRequirement("service_area", "Khu vực")), reason, quote);
    }
    @Test void missingPlanCannotAuthorizeContinuation() {
        var input = SalesCoreTest.input("GENERIC", "Giá");
        var decision = new tools.jackson.databind.ObjectMapper().readValue(SalesContinuationTest.QUOTE, TurnDecision.class);
        assertThat(new SalesDecisionValidator().validate(decision, input)).contains("FOLLOWUP_WITHOUT_DECISION_PURPOSE");
        assertThatThrownBy(() -> SalesConversationPlanner.parse("{\"latestContribution\":\"x\",\"nextDecision\":\"x\",\"reason\":\"x\",\"needsBusinessEvidence\":true}"))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void knownCityCannotStandInForUnknownNeedAndBotWordsCannotAuthorizeLocation() {
        var input = SalesCoreTest.input("BEAUTY_SPA", "HN").withInteractionPlan(plan(ConsultationStage.CONSULTATION,
            Map.of("service_area", "Bích Câu"), HandoffReason.NONE, ""));
        assertThat(SalesConsultationPolicy.missingContext(input)).contains("need", "service_area");
        var quote = new tools.jackson.databind.ObjectMapper().readValue(SalesContinuationTest.QUOTE.replace("\"action\":\"ANSWER\"", "\"action\":\"RECOMMEND\""), TurnDecision.class);
        assertThat(new SalesDecisionValidator().validate(quote, input)).contains("OFFER_BEFORE_REQUIRED_CUSTOMER_CONTEXT");
    }
    @Test void discoveryCannotQuoteEvenIfCatalogAndMisleadingPlanAreAvailable() {
        var input = SalesCoreTest.input("BEAUTY_SPA", "Bích Câu").withInteractionPlan(plan(ConsultationStage.DISCOVERY,
            Map.of("need", "cổ vai gáy chân tay", "service_area", "Bích Câu"), HandoffReason.NONE, ""));
        var quote = new tools.jackson.databind.ObjectMapper().readValue(SalesContinuationTest.QUOTE, TurnDecision.class);
        assertThat(new SalesDecisionValidator().validate(quote, input)).contains("OFFER_BEFORE_REQUIRED_CUSTOMER_CONTEXT");
    }
    @Test void normalBookingAndBotCriticismNeverGrantHandoffPermission() {
        var raw = SalesCoreTest.answer("Em ghi nhận yêu cầu.", "HANDOFF", "[]").replace("\"handoffSummary\":\"\"", "\"handoffSummary\":\"Cần hỗ trợ\"");
        var decision = new tools.jackson.databind.ObjectMapper().readValue(raw, TurnDecision.class);
        for (var stage : List.of(ConsultationStage.COMMITMENT, ConsultationStage.TRUST_REPAIR)) {
            var input = SalesCoreTest.input("GENERIC", "Mình muốn đặt").withInteractionPlan(plan(stage, Map.of(), HandoffReason.NONE, ""));
            assertThat(SalesConsultationPolicy.validate(decision, input)).contains("HANDOFF_REQUIRES_CUSTOMER_SERVICE_REASON");
        }
        var permitted = SalesCoreTest.input("GENERIC", "Tôi muốn gặp quản lý").withInteractionPlan(plan(
            ConsultationStage.ESCALATION, Map.of(), HandoffReason.EXPLICIT_HUMAN_REQUEST, "Tôi muốn gặp quản lý"));
        assertThat(SalesConsultationPolicy.validate(decision, permitted)).isEmpty();
    }
    @Test void runtimePromptsNeverContainEvaluationScenarios() {
        var registry = new IndustryProfileRegistry();
        var input = SalesCoreTest.input("MOTORBIKE_RENTAL", "Chào");
        var profile = registry.load("MOTORBIKE_RENTAL");
        assertThat(SalesConversationPlanner.prompt(input, profile)).doesNotContain("evaluationScenarios", profile.evaluationScenarios().getFirst());
        assertThat(SalesPromptBuilder.build(input, profile)).doesNotContain("evaluationScenarios", profile.evaluationScenarios().getFirst());
    }
}
