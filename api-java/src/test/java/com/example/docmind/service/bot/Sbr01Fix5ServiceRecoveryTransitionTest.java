package com.example.docmind.service.bot;

import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.dto.bot.FanpageBotResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SBR-01 Wave 1 — CP2 / FIX-5: Service Recovery & Mixed Intent Refactor Unit Tests.
 *
 * <p>Kiểm chứng đầy đủ 6 kịch bản trong {@code FIX5_SERVICE_RECOVERY_STATE_DESIGN.md}
 * cộng thêm 2 biến thể diễn đạt khác nhau cho ý phàn nàn thiếu gói (chứng minh không overfit riêng T15)
 * và cờ bật/tắt {@code docmind.aci.fix5.enabled}.</p>
 */
class Sbr01Fix5ServiceRecoveryTransitionTest {

    private static final List<String> NANG_SEN_TAXONOMY = List.of(
            "đầu", "cổ", "vai", "gáy", "lưng", "thắt lưng", "toàn thân", "body", "chân", "tay", "mắt"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        CleanAgentOutputRefereeAndSanitizer.setFix5MixedIntentRecoveryEnabled(true);
    }

    @AfterEach
    void tearDown() {
        CleanAgentOutputRefereeAndSanitizer.setFix5MixedIntentRecoveryEnabled(null);
    }

    @Test
    @DisplayName("FIX-5 Test 1 (T7): Phàn nàn thuần túy 'Hỏi lại lắm thế' -> vào FRICTION_RECOVERY / SERVICE_RECOVERY, cắt pitch & câu hỏi")
    void testPureOverIntrusionComplaintEntersFrictionRecovery() throws Exception {
        String t7Msg = "Hỏi lại lắm thế";

        assertThat(CleanAgentOutputRefereeAndSanitizer.isProcessOrConsultationFeedbackComplaint(t7Msg)).isTrue();
        assertThat(CleanAgentOutputRefereeAndSanitizer.hasBusinessOrCatalogClarificationDemandOnComplaintTurn(t7Msg)).isFalse();

        AdaptiveConversationIntelligence.LightweightCustomerUnderstandingPlan plan =
                AdaptiveConversationIntelligence.buildLightweightCustomerUnderstandingPlan(
                        t7Msg, List.of(), null, null);
        assertThat(plan.actionExecutionDirective())
                .contains("OVER_INTRUSION")
                .contains("0 báo giá");

        JsonNode rawRef = objectMapper.readTree("""
                {
                  "customer_understanding": {
                    "turn_intent": "SERVICE_OR_OPERATIONAL_INQUIRY",
                    "secondary_intents": [],
                    "conversation_state": "ACTIVE_CONSULTATION",
                    "emotion": "FRUSTRATED"
                  },
                  "priority": "DISCOVERY",
                  "response_plan": {
                    "strategy": "Direct Answer",
                    "tone": "Warm",
                    "depth": "MODERATE",
                    "cta_level": "SOFT_CTA",
                    "next_action": "ASK_ONE_DISCOVERY_QUESTION",
                    "adaptation": "KEEP"
                  }
                }
                """);
        AdaptiveConversationIntelligence.AdaptiveCognitiveReflection reconciled =
                AdaptiveConversationIntelligence.parseAndReconcileAdaptiveCognitiveReflection(rawRef, t7Msg);

        assertThat(reconciled.customerUnderstanding().detectedIntent()).isEqualTo("PROCESS_FEEDBACK");
        assertThat(reconciled.customerUnderstanding().conversationState()).isEqualTo("FRICTION_RECOVERY");
        assertThat(reconciled.priority().primary()).isEqualTo("SERVICE_RECOVERY");
        assertThat(reconciled.responsePlan().nextAction()).isEqualTo("NO_ADDITIONAL_ACTION");
        assertThat(reconciled.responsePlan().ctaLevel()).isEqualTo(AdaptiveConversationIntelligence.CtaLevel.NO_CTA);

        List<FanpageBotResponse.ReplyBubble> leakedPitchOnT7 = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Em xin lỗi nếu cách tư vấn của em khiến mình cảm thấy bực bội ạ. Gói dưỡng sinh cơ bản 60 phút — 239k, gói massage cơ bản 60 phút — 399k. Mình muốn tìm hiểu thêm gói nào không ạ?",
                        0
                )
        );
        List<FanpageBotResponse.ReplyBubble> cleaned =
                CleanAgentOutputRefereeAndSanitizer.stripUnsolicitedPackagePitchOnNegativeReactionInPlace(
                        leakedPitchOnT7, t7Msg, List.of("hoa mắt", "ù tai"), NANG_SEN_TAXONOMY, "em", "mình");
        String joined = joinBubbles(cleaned);
        assertThat(joined)
                .containsIgnoringCase("xin lỗi")
                .doesNotContain("239k")
                .doesNotContain("399k")
                .doesNotContain("?");
    }

    @Test
    @DisplayName("FIX-5 Test 2 (T9): Lượt hỏi so sánh gói sau phàn nàn thoát sang ACTIVE_CONSULTATION dù secondary_intents còn STYLE_COMPLAINT")
    void testFollowUpComparisonAfterFrictionExitsToActiveConsultation() throws Exception {
        String t9Msg = "thế rốt cuộc 2 gói khác j nhau";

        assertThat(CleanAgentOutputRefereeAndSanitizer.isProcessOrConsultationFeedbackComplaint(t9Msg)).isFalse();

        JsonNode rawRefWithSecondaryComplaint = objectMapper.readTree("""
                {
                  "customer_understanding": {
                    "turn_intent": "PROCESS_FEEDBACK",
                    "secondary_intents": ["STYLE_COMPLAINT", "PROCESS_FEEDBACK"],
                    "conversation_state": "FRICTION_RECOVERY",
                    "emotion": "FRUSTRATED"
                  },
                  "priority": "SERVICE_RECOVERY",
                  "response_plan": {
                    "strategy": "Direct Answer",
                    "tone": "Warm",
                    "depth": "MODERATE",
                    "cta_level": "NO_CTA",
                    "next_action": "NO_ADDITIONAL_ACTION",
                    "adaptation": "PIVOT"
                  }
                }
                """);
        AdaptiveConversationIntelligence.AdaptiveCognitiveReflection reconciled =
                AdaptiveConversationIntelligence.parseAndReconcileAdaptiveCognitiveReflection(rawRefWithSecondaryComplaint, t9Msg);

        assertThat(reconciled.customerUnderstanding().conversationState()).isEqualTo("ACTIVE_CONSULTATION");
        assertThat(reconciled.customerUnderstanding().detectedIntent()).isEqualTo("SERVICE_OR_OPERATIONAL_INQUIRY");
        assertThat(reconciled.priority().primary()).isEqualTo("CLARIFICATION");
        // secondary_intents được bảo toàn làm ngữ cảnh cảm xúc
        assertThat(reconciled.customerUnderstanding().secondaryIntents()).contains("STYLE_COMPLAINT");
    }

    @Test
    @DisplayName("FIX-5 Test 3 (T11): Phàn nàn kèm câu hỏi phạm vi ('chăm 1 vùng rồi bỏ các vùng khác à') giữ nguyên giải thích phạm vi & gói")
    void testComplaintWithScopeQuestionAnswersScopeDirectlyWithoutStripping() throws Exception {
        String t11Msg = "Đã mệt rồi còn hỏi vùng nào chăm sóc hơn, chăm 1 vùng rồi bỏ các vùng khác à";

        assertThat(CleanAgentOutputRefereeAndSanitizer.isProcessOrConsultationFeedbackComplaint(t11Msg)).isTrue();
        assertThat(CleanAgentOutputRefereeAndSanitizer.hasBusinessOrCatalogClarificationDemandOnComplaintTurn(t11Msg)).isTrue();

        Set<AdaptiveConversationIntelligence.CanonicalTurnIntent> intents =
                AdaptiveConversationIntelligence.detectCanonicalTurnIntents(
                        t11Msg, List.of(), null, null);
        assertThat(intents).contains(
                AdaptiveConversationIntelligence.CanonicalTurnIntent.SERVICE_OR_OPERATIONAL_INQUIRY,
                AdaptiveConversationIntelligence.CanonicalTurnIntent.PROCESS_FEEDBACK
        );

        AdaptiveConversationIntelligence.LightweightCustomerUnderstandingPlan plan =
                AdaptiveConversationIntelligence.buildLightweightCustomerUnderstandingPlan(
                        t11Msg, List.of(), null, null);
        assertThat(plan.actionExecutionDirective())
                .contains("COMPLAINT_WITH_BUSINESS_OR_CATALOG_CLARIFICATION")
                .doesNotContain("0 báo giá");

        List<FanpageBotResponse.ReplyBubble> llmReply = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Em xin lỗi vì đã khiến mình bực bội ạ. Gói 239k là gội đầu + massage cổ vai gáy 60 phút, gói 399k là massage trị liệu toàn thân 60 phút chăm sóc đầy đủ các vùng chứ không bỏ qua vùng nào ạ.",
                        0
                )
        );
        List<FanpageBotResponse.ReplyBubble> sanitized =
                CleanAgentOutputRefereeAndSanitizer.stripUnsolicitedPackagePitchOnNegativeReactionInPlace(
                        llmReply, t11Msg, List.of("hoa mắt", "ù tai"), NANG_SEN_TAXONOMY, "em", "mình");
        String joined = joinBubbles(sanitized);
        assertThat(joined)
                .contains("239k")
                .contains("399k")
                .contains("toàn thân")
                .doesNotContain("?");
    }

    @Test
    @DisplayName("FIX-5 Test 4 (T13): Câu hỏi 'haizzz, ngoài 2 gói trên còn gói nào khác k' thoát khỏi SERVICE_RECOVERY và giữ danh sách gói khác")
    void testOtherPackageInquiryWithSighExitsServiceRecovery() throws Exception {
        String t13Msg = "haizzz, ngoài 2 gói trên còn gói nào khác k";

        assertThat(CleanAgentOutputRefereeAndSanitizer.isProcessOrConsultationFeedbackComplaint(t13Msg)).isFalse();

        JsonNode rawRef = objectMapper.readTree("""
                {
                  "customer_understanding": {
                    "turn_intent": "PROCESS_FEEDBACK",
                    "secondary_intents": ["STYLE_COMPLAINT"],
                    "conversation_state": "FRICTION_RECOVERY",
                    "emotion": "TIRED"
                  },
                  "priority": "SERVICE_RECOVERY",
                  "response_plan": {
                    "strategy": "Direct Answer",
                    "tone": "Warm",
                    "depth": "MODERATE",
                    "cta_level": "NO_CTA",
                    "next_action": "NO_ADDITIONAL_ACTION",
                    "adaptation": "KEEP"
                  }
                }
                """);
        AdaptiveConversationIntelligence.AdaptiveCognitiveReflection reconciled =
                AdaptiveConversationIntelligence.parseAndReconcileAdaptiveCognitiveReflection(rawRef, t13Msg);

        assertThat(reconciled.customerUnderstanding().conversationState()).isEqualTo("ACTIVE_CONSULTATION");
        assertThat(reconciled.priority().primary()).isEqualTo("CLARIFICATION");

        List<FanpageBotResponse.ReplyBubble> llmReply = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Bên em còn có thêm gói Massage phục hồi 100 phút 580k và gói Massage phục hồi + dưỡng sinh 120 phút 670k ạ.",
                        0
                )
        );
        List<FanpageBotResponse.ReplyBubble> sanitized =
                CleanAgentOutputRefereeAndSanitizer.stripUnsolicitedPackagePitchOnNegativeReactionInPlace(
                        llmReply, t13Msg, List.of("hoa mắt", "ù tai"), NANG_SEN_TAXONOMY, "em", "mình");
        assertThat(joinBubbles(sanitized))
                .contains("580k")
                .contains("670k");
    }

    @Test
    @DisplayName("FIX-5 Test 5 (T15): Phàn nàn hiểu lầm chỉ có 2 gói -> không khóa '0 báo giá' và giữ nguyên câu làm rõ danh mục gói")
    void testCatalogMisunderstandingComplaintClarifiesRepresentativePackages() throws Exception {
        String t15Msg = "thế mà lúc giới thiệu nói có 2 gói, làm người ta tưởng chỉ có 2 gói , cách nói rất gây hiểu lầm";

        assertThat(CleanAgentOutputRefereeAndSanitizer.hasBusinessOrCatalogClarificationDemandOnComplaintTurn(t15Msg)).isTrue();
        assertThat(VietnameseScopeSignalExtractor.isGroupHColloquialPushback(t15Msg)).isFalse();

        AdaptiveConversationIntelligence.LightweightCustomerUnderstandingPlan plan =
                AdaptiveConversationIntelligence.buildLightweightCustomerUnderstandingPlan(
                        t15Msg, List.of(), null, null);
        assertThat(plan.actionExecutionDirective())
                .contains("COMPLAINT_WITH_BUSINESS_OR_CATALOG_CLARIFICATION")
                .doesNotContain("0 báo giá");

        JsonNode rawRef = objectMapper.readTree("""
                {
                  "customer_understanding": {
                    "turn_intent": "PROCESS_FEEDBACK",
                    "secondary_intents": [],
                    "conversation_state": "FRICTION_RECOVERY",
                    "emotion": "FRUSTRATED"
                  },
                  "priority": "SERVICE_RECOVERY",
                  "response_plan": {
                    "strategy": "Active Listening",
                    "tone": "Sincere",
                    "depth": "CONCISE",
                    "cta_level": "NO_CTA",
                    "next_action": "NO_ADDITIONAL_ACTION",
                    "adaptation": "PIVOT"
                  }
                }
                """);
        AdaptiveConversationIntelligence.AdaptiveCognitiveReflection reconciled =
                AdaptiveConversationIntelligence.parseAndReconcileAdaptiveCognitiveReflection(rawRef, t15Msg);
        assertThat(reconciled.customerUnderstanding().conversationState()).isEqualTo("ACTIVE_CONSULTATION");
        assertThat(reconciled.customerUnderstanding().detectedIntent()).isEqualTo("SERVICE_OR_OPERATIONAL_INQUIRY");
        assertThat(reconciled.customerUnderstanding().secondaryIntents()).contains("PROCESS_FEEDBACK");
        assertThat(reconciled.priority().primary()).isEqualTo("CLARIFICATION");

        List<FanpageBotResponse.ReplyBubble> llmReply = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Em xin lỗi vì cách nói khiến mình hiểu lầm ạ. Ngoài 2 gói đã nêu, bên em còn có gói dưỡng sinh cao cấp 80 phút 339k, gói phục hồi 100 phút 580k và nhiều gói khác phù hợp với nhu cầu của mình ạ.",
                        0
                )
        );
        List<FanpageBotResponse.ReplyBubble> sanitized =
                CleanAgentOutputRefereeAndSanitizer.stripUnsolicitedPackagePitchOnNegativeReactionInPlace(
                        llmReply, t15Msg, List.of("hoa mắt", "ù tai"), NANG_SEN_TAXONOMY, "em", "mình");
        String joined = joinBubbles(sanitized);
        assertThat(joined)
                .contains("339k")
                .contains("580k")
                .contains("nhiều gói khác");
    }

    @Test
    @DisplayName("FIX-5 Test 6 (T15 CTA Guard): Giữ nguyên giải thích danh mục gói trên T15 nhưng cắt câu CTA đặt lịch hoặc câu hỏi ép chọn")
    void testBookingOrHardSellCtaStillStrippedOnCatalogClarificationTurn() {
        String t15Msg = "thế mà lúc giới thiệu nói có 2 gói, làm người ta tưởng chỉ có 2 gói , cách nói rất gây hiểu lầm";

        List<FanpageBotResponse.ReplyBubble> withStandaloneBookingCta = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Em xin lỗi vì cách nói khiến mình hiểu lầm ạ. Ngoài 2 gói tiêu biểu đó, bên em còn có gói dưỡng sinh cao cấp 80 phút 339k và gói phục hồi 100 phút 580k ạ. Mình đặt lịch khung 19h tối nay luôn nhé ạ?",
                        0
                )
        );
        List<FanpageBotResponse.ReplyBubble> cleanedStandalone =
                CleanAgentOutputRefereeAndSanitizer.stripUnsolicitedPackagePitchOnNegativeReactionInPlace(
                        withStandaloneBookingCta, t15Msg, List.of(), NANG_SEN_TAXONOMY, "em", "mình");
        String joinedStandalone = joinBubbles(cleanedStandalone);
        assertThat(joinedStandalone)
                .contains("339k")
                .contains("580k")
                .doesNotContainIgnoringCase("đặt lịch")
                .doesNotContain("19h")
                .doesNotContain("?");

        List<FanpageBotResponse.ReplyBubble> withTrailingCommaCta = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Em xin lỗi vì cách nói khiến mình hiểu lầm ạ. Ngoài 2 gói đã nêu, bên em còn có gói dưỡng sinh cao cấp 80 phút 339k và gói phục hồi 100 phút 580k, mình muốn tìm hiểu gói nào hơn ạ?",
                        0
                )
        );
        List<FanpageBotResponse.ReplyBubble> cleanedComma =
                CleanAgentOutputRefereeAndSanitizer.stripUnsolicitedPackagePitchOnNegativeReactionInPlace(
                        withTrailingCommaCta, t15Msg, List.of(), NANG_SEN_TAXONOMY, "em", "mình");
        String joinedComma = joinBubbles(cleanedComma);
        assertThat(joinedComma)
                .contains("339k")
                .contains("580k")
                .doesNotContain("?");
    }

    @Test
    @DisplayName("FIX-5 Rule 0.6 (2 Paraphrases): Không overfit riêng câu chữ T15 — kiểm chứng 2 cách diễn đạt phàn nàn thiếu gói khác nhau")
    void testParaphrasedCatalogCompletenessComplaintsNotOverfittedToT15() {
        List<String> paraphrases = List.of(
                "Sao không nói hết các gói từ đầu mà chỉ nêu 2 gói?",
                "Giới thiệu thiếu quá làm mình tưởng bên em chỉ có mỗi 2 gói đó"
        );

        for (String msg : paraphrases) {
            assertThat(CleanAgentOutputRefereeAndSanitizer.hasBusinessOrCatalogClarificationDemandOnComplaintTurn(msg))
                    .as("Paraphrase must be recognized as catalog clarification demand: %s", msg)
                    .isTrue();

            AdaptiveConversationIntelligence.LightweightCustomerUnderstandingPlan plan =
                    AdaptiveConversationIntelligence.buildLightweightCustomerUnderstandingPlan(
                            msg, List.of(), null, null);
            assertThat(plan.actionExecutionDirective())
                    .as("Paraphrase directive must not lock '0 báo giá': %s", msg)
                    .contains("COMPLAINT_WITH_BUSINESS_OR_CATALOG_CLARIFICATION")
                    .doesNotContain("0 báo giá");

            List<FanpageBotResponse.ReplyBubble> draft = List.of(
                    new FanpageBotResponse.ReplyBubble(
                            "Dạ em xin lỗi vì lúc nãy giới thiệu chưa đầy đủ ạ. Ngoài 2 gói tiêu biểu đó, bên em còn có gói dưỡng sinh cao cấp 80 phút 339k và gói Massage phục hồi 100 phút 580k ạ. Mình đặt lịch luôn nhé ạ?",
                            0
                    )
            );
            List<FanpageBotResponse.ReplyBubble> cleaned =
                    CleanAgentOutputRefereeAndSanitizer.stripUnsolicitedPackagePitchOnNegativeReactionInPlace(
                            draft, msg, List.of(), NANG_SEN_TAXONOMY, "em", "mình");
            String joined = joinBubbles(cleaned);
            assertThat(joined)
                    .contains("339k")
                    .contains("580k")
                    .doesNotContainIgnoringCase("đặt lịch")
                    .doesNotContain("?");
        }
    }

    @Test
    @DisplayName("FIX-5 Rule 0.2: Cờ cấu hình docmind.aci.fix5.enabled có thể tắt để đối chứng hành vi độc lập")
    void testFeatureFlagToggleRestoresLegacyBehaviorWhenDisabled() {
        String t15Msg = "thế mà lúc giới thiệu nói có 2 gói, làm người ta tưởng chỉ có 2 gói , cách nói rất gây hiểu lầm";
        CleanAgentOutputRefereeAndSanitizer.setFix5MixedIntentRecoveryEnabled(false);
        assertThat(CleanAgentOutputRefereeAndSanitizer.hasBusinessOrCatalogClarificationDemandOnComplaintTurn(t15Msg)).isFalse();
    }

    private static String joinBubbles(List<FanpageBotResponse.ReplyBubble> bubbles) {
        return bubbles.stream()
                .map(FanpageBotResponse.ReplyBubble::text)
                .collect(Collectors.joining(" "));
    }
}
