package com.example.docmind.service.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SBR-01 Wave 1 — CP3.1: {@code conversation_goal} Lifecycle Verification.
 */
class Sbr01Cp3GoalLifecycleTest {

    private static final UUID NANG_SEN_PAGE_ID = UUID.fromString("2850a8ef-ca4a-40eb-87fa-4f808640efee");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        AgentBrainService.setCp3GoalLifecycleEnabled(null);
    }

    private AdaptiveConversationIntelligence.AdaptiveCognitiveReflection reflectionWithGoalAndDelta(
            String intent,
            String state,
            String proposedGoal,
            boolean needRevisionDetected,
            List<AdaptiveConversationIntelligence.FactDeltaProposal> deltas
    ) throws Exception {
        String json = """
                {
                  "customer_understanding": {
                    "turn_intent": "%s",
                    "secondary_intents": [],
                    "conversation_state": "%s",
                    "conversation_goal": "%s",
                    "need_revision": %s,
                    "emotion": "NEUTRAL"
                  },
                  "priority": "CLARIFICATION",
                  "response_plan": {
                    "strategy": "Direct Answer",
                    "tone": "Warm",
                    "depth": "STANDARD",
                    "cta_level": "NO_CTA",
                    "next_action": "ANSWER",
                    "adaptation": "KEEP"
                  }
                }
                """.formatted(intent, state, proposedGoal, needRevisionDetected);
        JsonNode root = objectMapper.readTree(json);
        AdaptiveConversationIntelligence.AdaptiveCognitiveReflection parsed =
                AdaptiveConversationIntelligence.parseAdaptiveCognitiveReflection(root, null);
        return deltas != null && !deltas.isEmpty() ? parsed.withConfirmedFactsDelta(deltas) : parsed;
    }

    @Test
    @DisplayName("CP3.1-1: Transient emotional/complaint/recovery goals never become sticky conversation_goal")
    void transientEmotionOrComplaintNeverBecomesStickyGoal() throws Exception {
        AgentBrainService brain = new AgentBrainService();
        String senderId = "cp3-1-emotion-" + UUID.randomUUID();

        var r1 = reflectionWithGoalAndDelta(
                "PROCESS_FEEDBACK",
                "FRICTION_RECOVERY",
                "Xoa dịu bức xúc và xin lỗi khách hàng",
                false,
                List.of()
        );
        brain.applyAciTurnMemoryUpdates(
                NANG_SEN_PAGE_ID, senderId, "T1", "Hỏi lại lắm thế",
                List.of(), r1, ConversationState.StructuredBookingState.none(), null, false, null, "Empathetic Repair"
        );
        var snap1 = brain.getCustomerUnderstandingDraft2Snapshot(NANG_SEN_PAGE_ID, senderId);
        assertThat(snap1.consultativeProfile().conversationGoal()).isEqualTo("EXPLORE_SERVICES");

        var r2 = reflectionWithGoalAndDelta(
                "PRICE_OBJECTION",
                "ACTIVE_CONSULTATION",
                "GIẢI THOÁT BĂN KHOĂN GIÁ",
                false,
                List.of()
        );
        brain.applyAciTurnMemoryUpdates(
                NANG_SEN_PAGE_ID, senderId, "T2", "Sao đắt thế",
                List.of(), r2, ConversationState.StructuredBookingState.none(), null, false, null, "Value Reframe"
        );
        var snap2 = brain.getCustomerUnderstandingDraft2Snapshot(NANG_SEN_PAGE_ID, senderId);
        assertThat(snap2.consultativeProfile().conversationGoal()).isEqualTo("EXPLORE_SERVICES");
    }

    @Test
    @DisplayName("CP3.1-2: Generic Turn-1 placeholder upgrades to concrete need goal, survives pure complaints and side questions, and updates on new need/pivot")
    void placeholderUpgradesToNeedGoalAndUpdatesOnlyOnNewNeedOrExplicitPivot() throws Exception {
        AgentBrainService brain = new AgentBrainService();
        String senderId = "cp3-1-journey-" + UUID.randomUUID();

        // T1: Customer asks general price -> LLM proposes placeholder "CUNG_CẤP_BẢNG_GIÁ"
        var rT1 = reflectionWithGoalAndDelta(
                "GENERAL_PRICE_INQUIRY", "ACTIVE_CONSULTATION", "CUNG_CẤP_BẢNG_GIÁ", false, List.of()
        );
        brain.applyAciTurnMemoryUpdates(
                NANG_SEN_PAGE_ID, senderId, "T1", "giá",
                List.of(), rT1, ConversationState.StructuredBookingState.none(), null, false, null, "Direct Answer"
        );

        // T3: Customer shares symptoms -> upgrades from placeholder to concrete symptom consultation goal
        var rT3 = reflectionWithGoalAndDelta(
                "NEED_DISCOVERY",
                "ACTIVE_CONSULTATION",
                "Tư vấn gói thư giãn phù hợp triệu chứng hoa mắt, ù tai, mệt mỏi",
                false,
                List.of(new AdaptiveConversationIntelligence.FactDeltaProposal(
                        "self_reported_symptom", "hoa mắt, ù tai, mệt mỏi",
                        AdaptiveConversationIntelligence.FactMutationOp.ADD,
                        "HIGH",
                        AdaptiveConversationIntelligence.FactValidity.CURRENT
                ))
        );
        brain.applyAciTurnMemoryUpdates(
                NANG_SEN_PAGE_ID, senderId, "T3", "tôi hoa mắt trong mắt ù tai mệt mỏi",
                List.of(), rT3, ConversationState.StructuredBookingState.none(), null, false, null, "Empathy + Scoped Recommendation"
        );
        assertThat(brain.getCustomerUnderstandingDraft2Snapshot(NANG_SEN_PAGE_ID, senderId).consultativeProfile().conversationGoal())
                .isEqualTo("Tư vấn gói thư giãn phù hợp triệu chứng hoa mắt, ù tai, mệt mỏi");

        // T5: Customer asks "Nó khác j nhau" -> LLM proposes "SO_SANH_GOI" placeholder -> must NOT overwrite concrete goal
        var rT5 = reflectionWithGoalAndDelta(
                "SERVICE_COMPARISON", "ACTIVE_CONSULTATION", "SO_SANH_GOI", false, List.of()
        );
        brain.applyAciTurnMemoryUpdates(
                NANG_SEN_PAGE_ID, senderId, "T5", "Nó khác j nhau",
                List.of(), rT5, ConversationState.StructuredBookingState.none(), null, false, null, "Direct Answer"
        );
        assertThat(brain.getCustomerUnderstandingDraft2Snapshot(NANG_SEN_PAGE_ID, senderId).consultativeProfile().conversationGoal())
                .isEqualTo("Tư vấn gói thư giãn phù hợp triệu chứng hoa mắt, ù tai, mệt mỏi");

        // T7: Customer complains "Hỏi lại lắm thế" -> pure complaint must NOT overwrite concrete goal
        var rT7 = reflectionWithGoalAndDelta(
                "PROCESS_FEEDBACK", "FRICTION_RECOVERY", "Xin lỗi khách hàng vì hỏi nhiều", true, List.of()
        );
        brain.applyAciTurnMemoryUpdates(
                NANG_SEN_PAGE_ID, senderId, "T7", "Hỏi lại lắm thế",
                List.of(), rT7, ConversationState.StructuredBookingState.none(), null, false, null, "Empathetic Repair"
        );
        assertThat(brain.getCustomerUnderstandingDraft2Snapshot(NANG_SEN_PAGE_ID, senderId).consultativeProfile().conversationGoal())
                .isEqualTo("Tư vấn gói thư giãn phù hợp triệu chứng hoa mắt, ù tai, mệt mỏi");

        // T11: Customer clarifies scope = full body ("chăm sóc cả chứ còn vùng nào hơn") -> new need info updates goal!
        var rT11 = reflectionWithGoalAndDelta(
                "SERVICE_OR_OPERATIONAL_INQUIRY",
                "ACTIVE_CONSULTATION",
                "Tìm gói massage chăm sóc toàn thân giải tỏa mệt mỏi",
                false,
                List.of(new AdaptiveConversationIntelligence.FactDeltaProposal(
                        "need_scope", "toàn thân",
                        AdaptiveConversationIntelligence.FactMutationOp.ADD,
                        "HIGH",
                        AdaptiveConversationIntelligence.FactValidity.CURRENT
                ))
        );
        brain.applyAciTurnMemoryUpdates(
                NANG_SEN_PAGE_ID, senderId, "T11",
                "Đã mệt rồi còn hỏi vùng nào chăm sóc hơn, chăm sóc cả chứ còn vùng nào hơn, cách nói chuyện rất thiếu thông cảm vs khách hàng",
                List.of(), rT11, ConversationState.StructuredBookingState.none(), null, false, null, "Direct Answer"
        );
        assertThat(brain.getCustomerUnderstandingDraft2Snapshot(NANG_SEN_PAGE_ID, senderId).consultativeProfile().conversationGoal())
                .isEqualTo("Tìm gói massage chăm sóc toàn thân giải tỏa mệt mỏi");

        // T13: Customer explicitly pivots to exploring broader catalog ("haizzz, ngoài 2 gói trên còn gói nào khác k") -> updates goal!
        var rT13 = reflectionWithGoalAndDelta(
                "SERVICE_OR_OPERATIONAL_INQUIRY",
                "ACTIVE_CONSULTATION",
                "Tham khảo thêm các gói phục hồi toàn thân chuyên sâu ngoài 2 gói cơ bản",
                false,
                List.of()
        );
        brain.applyAciTurnMemoryUpdates(
                NANG_SEN_PAGE_ID, senderId, "T13", "haizzz, ngoài 2 gói trên còn gói nào khác k",
                List.of(), rT13, ConversationState.StructuredBookingState.none(), null, false, null, "Direct Answer"
        );
        assertThat(brain.getCustomerUnderstandingDraft2Snapshot(NANG_SEN_PAGE_ID, senderId).consultativeProfile().conversationGoal())
                .isEqualTo("Tham khảo thêm các gói phục hồi toàn thân chuyên sâu ngoài 2 gói cơ bản");
    }

    @Test
    @DisplayName("CP3.1-3: Toggle flag docmind.aci.cp3.goal-lifecycle.enabled switches behavior cleanly")
    void toggleFlagSwitchesGoalLifecycleCleanly() {
        AgentBrainService.setCp3GoalLifecycleEnabled(false);
        assertThat(AgentBrainService.isCp3GoalLifecycleEnabled()).isFalse();
        AgentBrainService.setCp3GoalLifecycleEnabled(true);
        assertThat(AgentBrainService.isCp3GoalLifecycleEnabled()).isTrue();
    }
}
