package com.example.docmind.service.bot;

import com.example.docmind.config.LlmProperties;
import com.example.docmind.service.llm.OpenAiCompatibleLlmClient;
import com.example.docmind.service.llm.ReasoningBudget;
import com.example.docmind.service.reasoning.QueryComplexity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PostCp3Wave3ReasoningAndMemoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final tools.jackson.databind.ObjectMapper toolsObjectMapper = new tools.jackson.databind.ObjectMapper();

    @Test
    @DisplayName("Wave 3 (3.1): FAST_NO_THINK sends enable_thinking=false & reasoning_effort=none; OBJECTION_REFRAME_THINK sends enable_thinking=true & reasoning_effort=low")
    void fastNoThinkVsObjectionReframeThinkPayloadDifferentiation() {
        LlmProperties gwProps = new LlmProperties();
        gwProps.setProvider("qwen");
        gwProps.setBaseUrl("https://api.x-apikey.com/v1");
        gwProps.setModel("qwen3.6-plus");
        gwProps.setApiKey("sk-test-gw");

        RestClient.Builder gwBuilder = RestClient.builder();
        MockRestServiceServer gwServer = MockRestServiceServer.bindTo(gwBuilder).build();
        OpenAiCompatibleLlmClient gwClient = new OpenAiCompatibleLlmClient(gwProps, toolsObjectMapper, gwBuilder.build());

        ReasoningBudget fastBudget = new ReasoningBudget(
                QueryComplexity.SIMPLE,
                "none",
                45,
                300,
                5000,
                2500,
                0.2,
                0.9,
                1.08,
                ThinkingMode.FAST_NO_THINK
        );

        gwServer.expect(requestTo("https://api.x-apikey.com/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.enable_thinking").value(false))
                .andExpect(jsonPath("$.chat_template_kwargs.enable_thinking").value(false))
                .andExpect(jsonPath("$.reasoning_effort").value("none"))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"bubbles\\\":[\\\"Dạ chào chị ạ.\\\"]}\"}}]}",
                        MediaType.APPLICATION_JSON
                ));
        assertThat(gwClient.complete("Giá", fastBudget)).contains("Dạ chào chị ạ.");
        gwServer.verify();
        gwServer.reset();

        ReasoningBudget objectionBudget = new ReasoningBudget(
                QueryComplexity.DEEP_REASONING,
                "low",
                60,
                500,
                13000,
                6400,
                0.2,
                0.9,
                1.08,
                ThinkingMode.OBJECTION_REFRAME_THINK
        );

        gwServer.expect(requestTo("https://api.x-apikey.com/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.enable_thinking").value(true))
                .andExpect(jsonPath("$.chat_template_kwargs.enable_thinking").value(true))
                .andExpect(jsonPath("$.reasoning_effort").value("low"))
                .andExpect(jsonPath("$.max_tokens").value(6400))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"bubbles\\\":[\\\"Dạ gói 239k là 40 phút cơ bản, còn 399k là 60 phút chuyên sâu ạ.\\\"]}\"}}]}",
                        MediaType.APPLICATION_JSON
                ));
        assertThat(gwClient.complete("Đắt thế, gói 239k với 399k khác nhau thế nào?", objectionBudget)).contains("239k");
        gwServer.verify();
    }

    @Test
    @DisplayName("Wave 3 (3.2): unresolved_requests stays OPEN when comparison question is not explained in reply, and transitions to ANSWERED when explained")
    void unresolvedRequestsLifecycleForComparisonAndPriceInquiry() throws Exception {
        AgentBrainService brain = new AgentBrainService();
        UUID pageId = UUID.fromString("2850a8ef-ca4a-40eb-87fa-4f808640efee");
        String senderId = "wave3_sender_unresolved";

        // Turn 1: Customer asks "Giá", bot gives safety/discovery reply without price -> stays OPEN
        String rawJsonT1 = """
                {
                  "customer_understanding": {
                    "turn_intent": "PRICE_INQUIRY",
                    "conversation_goal": "EXPLORE_SERVICES",
                    "unresolved_requests_delta": [
                      {"item": "Hỏi giá", "status": "OPEN"}
                    ]
                  },
                  "response_plan": {
                    "strategy": "Clarify Gently",
                    "cta_level": "NO_CTA",
                    "next_action": "ASK_ONE_DISCOVERY_QUESTION"
                  },
                  "bubbles": ["Dạ mình đang mỏi vùng nào ạ?"]
                }
                """;
        JsonNode rootT1 = objectMapper.readTree(rawJsonT1);
        AdaptiveConversationIntelligence.AdaptiveCognitiveReflection refT1 =
                AdaptiveConversationIntelligence.parseAdaptiveCognitiveReflection(rootT1, rootT1)
                        .reconcileWithCurrentUtterance("Giá", "");
        brain.applyAciTurnMemoryUpdates(
                pageId, senderId, "T1", "Giá", List.of(), refT1,
                ConversationState.StructuredBookingState.none(), null, false, null, "Clarify Gently",
                "Dạ mình đang mỏi vùng nào ạ?"
        );

        // Turn 2: Customer shares isolated neurological symptoms "Tôi hoa mắt chóng mặt tê chân tay", bot gives health boundary without price -> "Hỏi giá" MUST stay OPEN!
        String rawJsonT2 = """
                {
                  "customer_understanding": {
                    "turn_intent": "SYMPTOM_SHARING",
                    "conversation_goal": "EXPLORE_SERVICES",
                    "unresolved_requests_delta": []
                  },
                  "response_plan": {
                    "strategy": "Reassure Honestly",
                    "cta_level": "NO_CTA",
                    "next_action": "ASK_ONE_DISCOVERY_QUESTION"
                  },
                  "bubbles": ["Dạ các liệu trình bên em hỗ trợ chăm sóc thư giãn cơ thể, không thay thế thăm khám y khoa ạ."]
                }
                """;
        JsonNode rootT2 = objectMapper.readTree(rawJsonT2);
        AdaptiveConversationIntelligence.AdaptiveCognitiveReflection refT2 =
                AdaptiveConversationIntelligence.parseAdaptiveCognitiveReflection(rootT2, rootT2)
                        .reconcileWithCurrentUtterance("Tôi hoa mắt chóng mặt tê chân tay", "Giá");
        brain.applyAciTurnMemoryUpdates(
                pageId, senderId, "T2", "Tôi hoa mắt chóng mặt tê chân tay", List.of(), refT2,
                ConversationState.StructuredBookingState.none(), null, false, null, "Reassure Honestly",
                "Dạ các liệu trình bên em hỗ trợ chăm sóc thư giãn cơ thể, không thay thế thăm khám y khoa ạ. Mình đang muốn ưu tiên thư giãn vùng nào ạ?"
        );

        var snapAfterT2 = brain.getCustomerUnderstandingDraft2Snapshot(pageId, senderId);
        assertThat(snapAfterT2.conversationalContext().unresolvedRequests())
                .anySatisfy(r -> {
                    assertThat(r.item()).isEqualToIgnoringCase("Hỏi giá");
                    assertThat(r.status()).isEqualTo(AdaptiveConversationIntelligence.UnresolvedRequestStatus.OPEN);
                });

        // Turn 3: Customer asks "Đắt thế, gói 239k với 399k khác nhau thế nào?", LLM falsely claims ANSWERED, but bot reply only repeats prices without comparison -> MUST stay OPEN!
        String userT3 = "Đắt thế, gói 239k với 399k khác nhau thế nào?";
        String rawJsonT3 = """
                {
                  "customer_understanding": {
                    "turn_intent": "PRICE_OBJECTION",
                    "secondary_intents": ["PACKAGE_COMPARISON"],
                    "conversation_goal": "RELIEVE_HEAD_NECK_SHOULDER_TENSION",
                    "unresolved_requests_delta": [
                      {"item": "So sánh gói 239k và 399k", "status": "ANSWERED"}
                    ]
                  },
                  "response_plan": {
                    "strategy": "Handle Objection",
                    "cta_level": "SOFT_CTA",
                    "next_action": "SOFT_FEELING_CHECK"
                  },
                  "bubbles": ["Dạ em hiểu băn khoăn của mình ạ."]
                }
                """;
        JsonNode rootT3 = objectMapper.readTree(rawJsonT3);
        AdaptiveConversationIntelligence.AdaptiveCognitiveReflection refT3 =
                AdaptiveConversationIntelligence.parseAdaptiveCognitiveReflection(rootT3, rootT3)
                        .reconcileWithCurrentUtterance(userT3, "Giá\nTôi hoa mắt chóng mặt tê chân tay");
        brain.applyAciTurnMemoryUpdates(
                pageId, senderId, "T3", userT3, List.of(), refT3,
                ConversationState.StructuredBookingState.none(), null, false, null, "Handle Objection",
                "Dạ em hiểu băn khoăn của mình ạ."
        );

        var snapAfterT3 = brain.getCustomerUnderstandingDraft2Snapshot(pageId, senderId);
        assertThat(snapAfterT3.conversationalContext().unresolvedRequests())
                .anySatisfy(r -> {
                    assertThat(r.item()).contains("239k");
                    assertThat(r.status()).isEqualTo(AdaptiveConversationIntelligence.UnresolvedRequestStatus.OPEN);
                });

        // Turn 4: Bot actually answers both the price and the comparison between 239k (40 phút cơ bản) and 399k (60 phút chuyên sâu) -> both transition to ANSWERED!
        brain.applyAciTurnMemoryUpdates(
                pageId, senderId, "T4", userT3, List.of(), refT3,
                ConversationState.StructuredBookingState.none(), null, false, null, "Handle Objection",
                "Dạ gói 239.000đ (40 phút) là gói cơ bản tập trung chăm sóc cổ vai gáy, còn gói 399.000đ (60 phút) là gói chuyên sâu bổ sung thêm đả thông kinh lạc vùng đầu và chườm thảo dược ạ."
        );

        var snapAfterT4 = brain.getCustomerUnderstandingDraft2Snapshot(pageId, senderId);
        assertThat(snapAfterT4.conversationalContext().unresolvedRequests())
                .allSatisfy(r -> assertThat(r.status()).isEqualTo(AdaptiveConversationIntelligence.UnresolvedRequestStatus.ANSWERED));
    }

    @Test
    @DisplayName("Wave 3 (3.3): confirmed_facts rejects 'chết' from 'ngại chết', rejects 'đáp ứng mơ hồ', and cleans 'mệt chết' to 'mệt'")
    void confirmedFactsValidationRejectsTruncatedAndVagueInferences() throws Exception {
        String userMsg = "Đắt thế, ngại chết đi được, mình đang mệt chết đây";
        String rawJson = """
                {
                  "customer_understanding": {
                    "turn_intent": "PRICE_OBJECTION",
                    "conversation_goal": "EXPLORE_SERVICES",
                    "confirmed_facts_delta": [
                      {"key": "self_reported_symptom", "value": "chết", "op": "ADD", "confidence": "HIGH", "validity": "CURRENT"},
                      {"key": "self_reported_symptom", "value": "ngại chết", "op": "ADD", "confidence": "HIGH", "validity": "CURRENT"},
                      {"key": "self_reported_symptom", "value": "mệt chết", "op": "ADD", "confidence": "HIGH", "validity": "CURRENT"},
                      {"key": "price_reaction", "value": "đáp ứng mơ hồ", "op": "ADD", "confidence": "HIGH", "validity": "CURRENT"},
                      {"key": "need_scope", "value": "chưa rõ", "op": "ADD", "confidence": "HIGH", "validity": "CURRENT"}
                    ]
                  },
                  "response_plan": {
                    "strategy": "Handle Objection",
                    "cta_level": "NO_CTA",
                    "next_action": "NO_ADDITIONAL_ACTION"
                  },
                  "bubbles": ["Dạ em xin lỗi mình ạ."]
                }
                """;
        JsonNode root = objectMapper.readTree(rawJson);
        AdaptiveConversationIntelligence.AdaptiveCognitiveReflection reflection =
                AdaptiveConversationIntelligence.parseAdaptiveCognitiveReflection(root, root)
                        .reconcileWithCurrentUtterance(userMsg, "");

        assertThat(reflection.confirmedFactsDelta())
                .noneMatch(f -> "chết".equalsIgnoreCase(f.value())
                        || "ngại chết".equalsIgnoreCase(f.value())
                        || f.value().toLowerCase().contains("mơ hồ")
                        || "chưa rõ".equalsIgnoreCase(f.value()));

        AgentBrainService brain = new AgentBrainService();
        UUID pageId = UUID.fromString("2850a8ef-ca4a-40eb-87fa-4f808640efee");
        String senderId = "wave3_sender_facts";
        brain.applyAciTurnMemoryUpdates(
                pageId, senderId, "T1", userMsg, List.of(), reflection,
                ConversationState.StructuredBookingState.none(), null, false, null, "Handle Objection",
                "Dạ em xin lỗi mình ạ."
        );

        var snap = brain.getCustomerUnderstandingDraft2Snapshot(pageId, senderId);
        assertThat(snap.consultativeProfile().selfReportedSymptoms())
                .extracting(AdaptiveConversationIntelligence.VersionedCustomerFact::value)
                .contains("mệt")
                .doesNotContain("chết", "ngại chết", "mệt chết");
        assertThat(snap.consultativeProfile().confirmedFacts())
                .extracting(AdaptiveConversationIntelligence.VersionedCustomerFact::value)
                .doesNotContain("đáp ứng mơ hồ", "chưa rõ", "chết");
    }
}
