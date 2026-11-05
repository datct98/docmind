package com.example.docmind.service.bot;

import com.example.docmind.dto.bot.AgentSkillDto;
import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.dto.bot.FanpageBotResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PostCp3Wave1CustomerUnderstandingTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static AgentSkillDto skill(String code, String name, String category, int priority) {
        return new AgentSkillDto(
                UUID.randomUUID(),
                code,
                name,
                category,
                null,
                null,
                "prompt",
                null,
                null,
                priority,
                true,
                "2850a8ef-ca4a-40eb-87fa-4f808640efee",
                null,
                null
        );
    }

    private static List<AgentSkillDto> nangSenSkills() {
        return List.of(
                skill("discovery_empathy_and_scope", "Discovery", "CONSULTATION", 10),
                skill("economical_moderate_package_guidance", "Economical", "CONSULTATION", 20),
                skill("package_comparison_and_scope_clarity", "Comparison", "CONSULTATION", 22),
                skill("catalog_breadth_and_alternative_options", "Breadth", "CONSULTATION", 24),
                skill("price_objection_sensory_value", "Objection", "OBJECTION", 30),
                skill("service_recovery_to_consultation_pivot", "Recovery", "RECOVERY", 35),
                skill("booking_Readiness_Gentle_Next_Step", "Booking", "BOOKING", 50)
        );
    }

    @Test
    @DisplayName("Wave 1 (1.1): 'Ơ, dở hơi à' đồng bộ Pre-LLM, Skill Selector, Reflection & Sanitizer vào PROCESS_FEEDBACK / FEEDBACK_RECOVERY")
    void testNegativeReactionUtteranceUnifiedAcrossPipeline() throws Exception {
        String t9Msg = "Ơ, dở hơi à";
        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Đắt thế, gói 239k với 399k khác nhau thế nào?"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ bên em có gói 239k và gói 399k ạ.")
        );

        // 1. Pre-LLM intent & plan
        Set<AdaptiveConversationIntelligence.CanonicalTurnIntent> intents =
                AdaptiveConversationIntelligence.detectCanonicalTurnIntents(t9Msg, history, null, null);
        assertThat(intents).containsExactly(AdaptiveConversationIntelligence.CanonicalTurnIntent.PROCESS_FEEDBACK);

        AdaptiveConversationIntelligence.LightweightCustomerUnderstandingPlan plan =
                AdaptiveConversationIntelligence.buildLightweightCustomerUnderstandingPlan(t9Msg, history, null, null);
        assertThat(plan.recommendedAction()).isEqualTo(AdaptiveConversationIntelligence.TurnAction.FEEDBACK_RECOVERY);
        assertThat(plan.actionExecutionDirective()).contains("0 báo giá").contains("0 câu hỏi");

        // 2. Pre-LLM skill selector: 0 sales/comparison skills on pure negative reaction
        List<AgentSkillDto> selectedSkills = CleanAgentOrchestratorService.selectPreLlmTurnRelevantSkills(
                nangSenSkills(), t9Msg, history, ConversationState.StructuredBookingState.none());
        assertThat(selectedSkills).isEmpty();

        // 3. Reflection reconciliation: drops ungrounded price_reaction ("đáp ứng mơ hồ") even when history had price objection
        JsonNode rawRef = objectMapper.readTree("""
                {
                  "customer_understanding": {
                    "turn_intent": "SERVICE_RECOMMENDATION",
                    "conversation_state": "ACTIVE_CONSULTATION",
                    "cost_concern_expressed": true
                  },
                  "confirmed_facts_delta": [
                    {"key": "price_reaction", "value": "đáp ứng mơ hồ", "op": "ADD", "confidence": "HIGH"}
                  ],
                  "response_plan": {
                    "strategy": "Soft Recommendation",
                    "cta_level": "SOFT_CTA",
                    "next_action": "ASK_ONE_CLARIFYING_QUESTION"
                  }
                }
                """);
        AdaptiveConversationIntelligence.AdaptiveCognitiveReflection parsed =
                AdaptiveConversationIntelligence.parseAdaptiveCognitiveReflection(rawRef, rawRef);
        AdaptiveConversationIntelligence.AdaptiveCognitiveReflection reconciled =
                parsed.reconcileWithCurrentUtterance(t9Msg, "Đắt thế, gói 239k với 399k khác nhau thế nào?");

        assertThat(reconciled.customerUnderstanding().detectedIntent()).isEqualTo("PROCESS_FEEDBACK");
        assertThat(reconciled.customerUnderstanding().conversationState()).isEqualTo("FRICTION_RECOVERY");
        assertThat(reconciled.customerUnderstanding().costConcernExpressed()).isFalse();
        assertThat(reconciled.confirmedFactsDelta()).isEmpty();
        assertThat(reconciled.responsePlan().ctaLevel()).isEqualTo(AdaptiveConversationIntelligence.CtaLevel.NO_CTA);

        // 4. Sanitizer preserves sincere context-aware apology without stripping 86% of content
        List<FanpageBotResponse.ReplyBubble> apologyBubbles = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Dạ em thành thật xin lỗi mình vì câu trả lời vừa rồi của em chưa rõ ràng làm mình khó hiểu và bực mình ạ. Để em giải thích ngắn gọn và đi thẳng vào đúng điều mình đang quan tâm nhé ạ.",
                        0
                )
        );
        List<FanpageBotResponse.ReplyBubble> afterStrip =
                CleanAgentOutputRefereeAndSanitizer.stripUnsolicitedPackagePitchOnNegativeReactionInPlace(
                        apologyBubbles, t9Msg, List.of(), List.of(), "em", "mình");
        assertThat(afterStrip).hasSize(1);
        assertThat(afterStrip.get(0).text()).isEqualTo(apologyBubbles.get(0).text());

        // 5. Factual denial with clause ("ơ bị dở à sao lại cổ vai gáy") still classifies as CUSTOMER_CORRECTION
        String factualDenial = "ơ bị dở à sao lại cổ vai gáy";
        assertThat(AdaptiveConversationIntelligence.isCustomerCorrectionOrComponentRejectionMessage(factualDenial)).isTrue();
        assertThat(CleanAgentOutputRefereeAndSanitizer.classifyNegativeReactionSubtype(factualDenial, List.of()))
                .isEqualTo(CleanAgentOutputRefereeAndSanitizer.NegativeReactionSubtype.CUSTOMER_CORRECTION);
    }

    @Test
    @DisplayName("Wave 1 (1.2): Câu trả lời ngắn theo ngữ cảnh ('Đầu và vai gáy', 'Cả hai', 'Chân và lưng') hiểu đúng SCOPE_CONFIRMATION, không biến thành triệu chứng hay selected_product_id")
    void testContextualShortScopeSelectionAndMemoryIntegrity() throws Exception {
        List<FanpageBotRequest.ConversationTurn> historyWithBotScopeQuestion = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Giá"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ bên em có các gói từ 239k ạ. Mình đang muốn chăm sóc vùng nào ạ?"),
                new FanpageBotRequest.ConversationTurn("user", "Tôi hoa mắt chóng mặt tê chân tay"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ các liệu trình bên em hướng tới thư giãn cơ thể, không thay thế thăm khám y khoa ạ. Hiện mình muốn ưu tiên chăm sóc thư giãn vùng cơ thể nào nhất (như đầu, cổ vai gáy hay chân tay) ạ?")
        );

        for (String shortScope : List.of("Đầu và vai gáy", "Cả hai", "Chân và lưng")) {
            assertThat(AdaptiveConversationIntelligence.isContextualShortScopeSelectionReply(
                    shortScope, historyWithBotScopeQuestion, null))
                    .as("With prior scope question: " + shortScope)
                    .isTrue();
            Set<AdaptiveConversationIntelligence.CanonicalTurnIntent> intents =
                    AdaptiveConversationIntelligence.detectCanonicalTurnIntents(
                            shortScope, historyWithBotScopeQuestion, null, null);
            assertThat(intents).contains(AdaptiveConversationIntelligence.CanonicalTurnIntent.SCOPE_CONFIRMATION);

            AdaptiveConversationIntelligence.LightweightCustomerUnderstandingPlan plan =
                    AdaptiveConversationIntelligence.buildLightweightCustomerUnderstandingPlan(
                            shortScope, historyWithBotScopeQuestion, null, null);
            assertThat(plan.recommendedAction()).isEqualTo(AdaptiveConversationIntelligence.TurnAction.SERVICE_RECOMMENDATION);
        }

        // Without prior bot scope question in history, bare body-area phrase is NOT forced into SCOPE_CONFIRMATION
        assertThat(AdaptiveConversationIntelligence.isContextualShortScopeSelectionReply(
                "Đầu và vai gáy", List.of(), null)).isFalse();
        assertThat(AdaptiveConversationIntelligence.detectCanonicalTurnIntents(
                "Đầu và vai gáy", List.of(), null, null))
                .doesNotContain(AdaptiveConversationIntelligence.CanonicalTurnIntent.SCOPE_CONFIRMATION);

        // Homonyms of "đầu"/"mặt" ("Đầu tiên tôi muốn tư vấn") are not misclassified as body-area scope
        assertThat(AdaptiveConversationIntelligence.isBareBodyAreaScopePhraseWithoutSymptoms("Đầu tiên tôi muốn tư vấn")).isFalse();
        assertThat(AdaptiveConversationIntelligence.isContextualShortScopeSelectionReply(
                "Đầu tiên tôi muốn tư vấn", historyWithBotScopeQuestion, null)).isFalse();

        // Reflection reconciliation converts symptom mislabeling to need_scope and clears unrequested selected_product_id
        JsonNode rawRef = objectMapper.readTree("""
                {
                  "customer_understanding": {
                    "turn_intent": "SYMPTOM_OR_NEED_SHARED",
                    "conversation_state": "NEED_DISCOVERY",
                    "booking_readiness": "EXPLORING"
                  },
                  "conversation_goal": "Điều trị hoa mắt, chóng mặt, tê chân tay và chăm sóc vùng đầu, vai gáy",
                  "confirmed_facts_delta": [
                    {"key": "self_reported_symptom", "value": "Đầu và vai gáy", "op": "ADD", "confidence": "HIGH"}
                  ],
                  "proposed_booking_patch": {
                    "selected_product_id": "NS02"
                  }
                }
                """);
        AdaptiveConversationIntelligence.AdaptiveCognitiveReflection parsed =
                AdaptiveConversationIntelligence.parseAdaptiveCognitiveReflection(rawRef, rawRef);
        AdaptiveConversationIntelligence.AdaptiveCognitiveReflection reconciled =
                parsed.reconcileWithCurrentUtterance("Đầu và vai gáy", "Tôi hoa mắt chóng mặt tê chân tay");

        assertThat(reconciled.confirmedFactsDelta())
                .extracting(AdaptiveConversationIntelligence.FactDeltaProposal::key)
                .containsOnly("need_scope");
        assertThat(reconciled.proposedBookingPatch().selectedProductId()).isNull();
        assertThat(reconciled.interestedPackageId()).isEqualTo("NS02");

        // Authoritative booking patch does not lock selected_product_id when customer only states body area scope
        List<PackageCatalogService.PackageSummary> catalog = List.of(
                new PackageCatalogService.PackageSummary(
                        "NS01", "NS01", "Gói Gội Đầu Dưỡng Sinh Cơ Bản", "Gói 239k", "HAIR",
                        List.of("đầu", "cổ", "vai", "gáy"), 60, "60 phút", 239_000L, "239k",
                        "gội đầu, massage cổ vai gáy", "Phòng chung có rèm riêng", "", "ALL", false),
                new PackageCatalogService.PackageSummary(
                        "NS02", "NS02", "Gói Chăm Sóc Chuyên Sâu Cổ Vai Gáy", "Gói 399k", "MASSAGE",
                        List.of("cổ", "vai", "gáy"), 75, "75 phút", 399_000L, "399k",
                        "massage cổ vai gáy chuyên sâu", "Phòng chung có rèm riêng", "", "ALL", false)
        );
        ConversationState.StructuredBookingState bookingAfterScope =
                AdaptiveConversationIntelligence.applyAuthoritativeBookingPatch(
                        ConversationState.StructuredBookingState.none(),
                        new AdaptiveConversationIntelligence.ProposedBookingPatch("NS02", null, null),
                        "Đầu và vai gáy",
                        "Hiện mình muốn ưu tiên chăm sóc vùng nào ạ?",
                        catalog,
                        catalog,
                        ZoneId.of("Asia/Ho_Chi_Minh"),
                        AdaptiveConversationIntelligence.BookingReadiness.EXPLORING,
                        "CONSULTATION"
                );
        assertThat(bookingAfterScope.selectedProductId()).isNull();

        // Sanitizer preserves canonical package description "massage cổ vai gáy"
        List<FanpageBotResponse.ReplyBubble> pkgBubbles = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Dạ với nhu cầu chăm sóc vùng đầu và vai gáy của mình, bên em có Gói Gội Đầu Dưỡng Sinh Cơ Bản (60 phút - 239k) kết hợp gội đầu thảo dược và massage cổ vai gáy giúp thư giãn dễ chịu ạ.",
                        0
                )
        );
        List<FanpageBotResponse.ReplyBubble> sanitizedPkg =
                CleanAgentOutputRefereeAndSanitizer.enforceP37B2TurnActionAndBubbleIdeaDisciplineInPlace(
                        pkgBubbles,
                        "Đầu và vai gáy",
                        historyWithBotScopeQuestion,
                        ConversationState.StructuredBookingState.none(),
                        catalog,
                        "em",
                        "mình",
                        "kỹ thuật viên",
                        AdaptiveConversationIntelligence.buildLightweightCustomerUnderstandingPlan(
                                "Đầu và vai gáy", historyWithBotScopeQuestion, null, null)
                );
        assertThat(sanitizedPkg.get(0).text()).contains("massage cổ vai gáy");
    }

    @Test
    @DisplayName("Wave 1 (1.3): Câu ghép 'Đắt thế, gói 239k với 399k khác nhau thế nào?' kích hoạt đồng thời PRICE_OBJECTION + PACKAGE_COMPARISON")
    void testMixedPriceObjectionAndPackageComparison() {
        String t7Msg = "Đắt thế, gói 239k với 399k khác nhau thế nào?";
        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Đầu và vai gáy"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ bên em có gói 239k (60 phút) và gói 399k (75 phút) ạ.")
        );

        Set<AdaptiveConversationIntelligence.CanonicalTurnIntent> intents =
                AdaptiveConversationIntelligence.detectCanonicalTurnIntents(t7Msg, history, null, null);
        assertThat(intents).contains(
                AdaptiveConversationIntelligence.CanonicalTurnIntent.PRICE_OBJECTION,
                AdaptiveConversationIntelligence.CanonicalTurnIntent.PACKAGE_COMPARISON
        );

        AdaptiveConversationIntelligence.LightweightCustomerUnderstandingPlan plan =
                AdaptiveConversationIntelligence.buildLightweightCustomerUnderstandingPlan(t7Msg, history, null, null);
        assertThat(plan.actionExecutionDirective())
                .contains("PRICE_OBJECTION_WITH_PACKAGE_COMPARISON")
                .contains("So sánh TRỰC TIẾP và CỤ THỂ");

        List<AgentSkillDto> selectedSkills = CleanAgentOrchestratorService.selectPreLlmTurnRelevantSkills(
                nangSenSkills(), t7Msg, history, ConversationState.StructuredBookingState.none());
        assertThat(selectedSkills)
                .extracting(AgentSkillDto::code)
                .contains("price_objection_sensory_value", "package_comparison_and_scope_clarity");
    }

    @Test
    @DisplayName("Wave 1 (1.4): Phân biệt 4 trường hợp triệu chứng sức khỏe ('Tôi hoa mắt chóng mặt tê chân tay' giữ NEED_DISCOVERY & ranh giới y khoa)")
    void testHealthSymptomBoundaryFourCases() {
        List<FanpageBotRequest.ConversationTurn> historyWithPriorPriceAsk = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Giá"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ bên em có các gói từ 239k ạ. Mình đang muốn chăm sóc vùng nào ạ?")
        );

        // Case 1: Isolated neurological/vascular symptoms without relaxation/care context
        String case1 = "Tôi hoa mắt chóng mặt tê chân tay";
        assertThat(AdaptiveConversationIntelligence.isIsolatedNeurologicalSymptomOnly(case1)).isTrue();
        assertThat(CleanAgentOutputRefereeAndSanitizer.isUnconfirmedHealthSymptomWithoutServiceDemand(
                case1, historyWithPriorPriceAsk)).isTrue();

        AdaptiveConversationIntelligence.LightweightCustomerUnderstandingPlan planCase1 =
                AdaptiveConversationIntelligence.buildLightweightCustomerUnderstandingPlan(
                        case1, historyWithPriorPriceAsk, null, null);
        assertThat(planCase1.recommendedAction()).isEqualTo(AdaptiveConversationIntelligence.TurnAction.NEED_DISCOVERY);
        assertThat(planCase1.actionExecutionDirective())
                .contains("ISOLATED_NEUROLOGICAL_OR_VASCULAR_SYMPTOM")
                .contains("không thay thế thăm khám hay điều trị y khoa")
                .contains("KHÔNG tự ý chốt/chào bán gói dịch vụ");

        // Case 2: Symptoms accompanied by musculoskeletal/relaxation care context
        String case2 = "Tôi đau đầu mỏi cổ vai gáy muốn thư giãn";
        assertThat(AdaptiveConversationIntelligence.isIsolatedNeurologicalSymptomOnly(case2)).isFalse();
        AdaptiveConversationIntelligence.LightweightCustomerUnderstandingPlan planCase2 =
                AdaptiveConversationIntelligence.buildLightweightCustomerUnderstandingPlan(
                        case2, historyWithPriorPriceAsk, null, null);
        assertThat(planCase2.recommendedAction()).isEqualTo(AdaptiveConversationIntelligence.TurnAction.SERVICE_RECOMMENDATION);

        // Case 3: Direct health suitability question -> SUITABILITY_INQUIRY_HANDOFF
        String case3 = "Tình trạng hoa mắt chóng mặt tê chân tay của tôi có làm massage được không?";
        assertThat(AdaptiveConversationIntelligence.isHealthSuitabilityInquiryMessage(case3)).isTrue();
        AdaptiveConversationIntelligence.LightweightCustomerUnderstandingPlan planCase3 =
                AdaptiveConversationIntelligence.buildLightweightCustomerUnderstandingPlan(
                        case3, historyWithPriorPriceAsk, null, null);
        assertThat(planCase3.recommendedAction()).isEqualTo(AdaptiveConversationIntelligence.TurnAction.SUITABILITY_INQUIRY_HANDOFF);

        // Case 4: Acute medical emergency -> Universal emergency guard
        String case4 = "Tôi bị đau ngực dữ dội khó thở liệt nửa người";
        assertThat(FanpageBotService.isUniversalAcuteMedicalEmergency(case4)).isTrue();

        // Negative Control isolation check (Trang 'Thẩm mỹ viện' has 0 skills, and generic greeting triggers 0 scope/comparison intents)
        List<AgentSkillDto> thamMyVienSkills = List.of();
        assertThat(CleanAgentOrchestratorService.selectPreLlmTurnRelevantSkills(
                thamMyVienSkills, "Đắt thế, gói 239k với 399k khác nhau thế nào?", historyWithPriorPriceAsk, ConversationState.StructuredBookingState.none()))
                .isEmpty();
        assertThat(AdaptiveConversationIntelligence.detectCanonicalTurnIntents("Xin chào shop", List.of(), null, null))
                .doesNotContain(
                        AdaptiveConversationIntelligence.CanonicalTurnIntent.SCOPE_CONFIRMATION,
                        AdaptiveConversationIntelligence.CanonicalTurnIntent.PACKAGE_COMPARISON
                );
    }
}
