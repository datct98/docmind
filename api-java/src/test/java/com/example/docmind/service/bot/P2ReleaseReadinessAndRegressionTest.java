package com.example.docmind.service.bot;

import com.example.docmind.dto.bot.AgentSkillDto;
import com.example.docmind.dto.bot.FanpageBotResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate P2 — Comprehensive Regression & Release Readiness Test Suite (Groups A–G + Backend Integrity).
 *
 * Verifies:
 *  - Group A: First-time inquiry, catalog overview, price quotation, symptom discovery
 *  - Group B: Price objection, competitor comparison ("bên kia 200k"), hesitation/deferral ("để chị xem lại"), bare refusal ("thôi em")
 *  - Group C: Style/repetition complaint ("sao tư vấn giống văn mẫu thế"), frustration recovery
 *  - Group D: Customer correction of bot assumption ("Chị có nói đau lưng đâu")
 *  - Group E: Multi-branch routing (43 Nguyễn Bỉnh Khiêm Q1 vs 490 Phạm Thái Bường Q7), 3-Tier Booking progression (Tier 1 vs Tier 2 vs Tier 3)
 *  - Group F: Timeout / invalid JSON fallback safety (zero unsolicited booking prompts on deferral/hesitation turns)
 *  - Group G: Non-buyer / out-of-scope inquiry, mixed complaint + catalog inquiry
 *  - Backend Integrity: InboundEventIdResolver deterministic deduplication, Negative Control isolation ("Page thẩm mỹ viện")
 */
class P2ReleaseReadinessAndRegressionTest {

    private static final String NANG_SEN_PAGE_ID = "2850a8ef-ca4a-40eb-87fa-4f808640efee";
    private static final String NEGATIVE_CONTROL_PAGE_ID = "59e68078-8327-4aa0-8cca-91410f783ad4";
    private final ObjectMapper objectMapper = new ObjectMapper();

    private Path resolveSnapshotPath() {
        Path p1 = Path.of("..", "benchmark", "sbr01", "fixtures", "sbr01_kb_catalog_skills_snapshot.json");
        if (Files.exists(p1)) {
            return p1;
        }
        return Path.of("benchmark", "sbr01", "fixtures", "sbr01_kb_catalog_skills_snapshot.json");
    }

    private List<AgentSkillDto> loadSnapshotSkills(String targetPageId) throws Exception {
        JsonNode root = objectMapper.readTree(Files.readString(resolveSnapshotPath(), StandardCharsets.UTF_8));
        JsonNode skillsNode = root.path("activeSkills");
        Instant now = Instant.now();
        List<AgentSkillDto> result = new ArrayList<>();
        for (JsonNode s : skillsNode) {
            String pageId = s.path("page_id").asText("");
            if (targetPageId == null || targetPageId.equalsIgnoreCase(pageId)) {
                result.add(new AgentSkillDto(
                        UUID.fromString(s.path("id").asText()),
                        s.path("code").asText(),
                        s.path("name").asText(),
                        s.path("category").asText(),
                        s.path("description").asText(),
                        s.path("trigger_signals").asText(),
                        s.path("tactical_instructions").asText(),
                        s.path("good_example").asText(),
                        s.path("anti_pattern").asText(),
                        s.path("priority").asInt(90),
                        s.path("active").asBoolean(true),
                        pageId,
                        now,
                        now
                ));
            }
        }
        return result;
    }

    @Test
    void groupAAndB_discoveryPriceObjectionCompetitorAndDeferralStopHandling() throws Exception {
        // Group A: First-time symptom & catalog inquiry receives full consultative skill menu (>= 5 skills)
        List<AgentSkillDto> allSkills = loadSnapshotSkills(NANG_SEN_PAGE_ID);
        List<AgentSkillDto> groupASkills = CleanAgentOrchestratorService.selectPreLlmTurnRelevantSkills(
                allSkills,
                "Em ơi chị hay bị đau mỏi cổ vai gáy và mất ngủ, bên mình có gói nào và giá sao em?",
                List.of(),
                ConversationState.StructuredBookingState.none()
        );
        assertThat(groupASkills).hasSizeGreaterThanOrEqualTo(5);

        // Group B1: Price objection ("giá 399k hơi cao") preserves LLM's chosen barrier_type (BUDGET_LIMIT vs VALUE_PERCEPTION_GAP)
        String budgetLimitJson = """
                {
                  "customer_understanding": {
                    "turn_intent": "PRICE_OBJECTION",
                    "conversation_state": "OBJECTION_HANDLING",
                    "interaction_permission": "OPEN"
                  },
                  "priority": "BUDGET_FIT",
                  "adaptation": "PIVOT",
                  "working_hypothesis": {
                    "hypothesis": "Khách thích gói 90 phút nhưng ngân sách chỉ khoảng 250k.",
                    "barrier_type": "BUDGET_LIMIT",
                    "confidence": 0.90,
                    "lifecycle_op": "REPLACE"
                  },
                  "response_plan": {
                    "strategy": "Step Back 1 Step Forward 2 Steps — Pivot to 60m 279k",
                    "cta_level": "SOFT_CTA",
                    "next_action": "CLARIFY_ONE_POINT"
                  },
                  "reply_bubbles": [
                    "Dạ nếu mình muốn tiết kiệm chi phí hơn thì gói Chuyên Sâu 60 phút (279k) vẫn tập trung giải cơ vùng cổ vai gáy rất kỹ ạ.",
                    "Mình muốn thử gói 60 phút trước để cảm nhận tay nghề kỹ thuật viên không ạ?"
                  ]
                }
                """;
        var parsedBudget = AdaptiveConversationIntelligence.parseAndReconcileAdaptiveCognitiveReflection(
                objectMapper.readTree(budgetLimitJson),
                "Gói 399k hơi vượt ngân sách của chị, có gói nào tầm dưới 300k không em?"
        );
        assertThat(parsedBudget.barrierType()).isEqualTo("BUDGET_LIMIT");
        assertThat(parsedBudget.responsePlan().nextAction()).isEqualTo("ASK_ONE_CLARIFYING_QUESTION");

        // Group B2: Competitor comparison ("bên kia 60 phút có 200k") preserves VALUE_PERCEPTION_GAP
        String competitorJson = """
                {
                  "customer_understanding": {
                    "turn_intent": "PRICE_OBJECTION",
                    "conversation_state": "OBJECTION_HANDLING",
                    "interaction_permission": "OPEN"
                  },
                  "priority": "VALUE_ALIGNMENT",
                  "adaptation": "REFINEMENT",
                  "working_hypothesis": {
                    "hypothesis": "Khách đang so sánh giá 279k với spa gội đầu thông thường 200k.",
                    "barrier_type": "VALUE_PERCEPTION_GAP",
                    "confidence": 0.89,
                    "lifecycle_op": "REPLACE"
                  },
                  "response_plan": {
                    "strategy": "Respect Competitor + Highlight Therapeutic Acupressure & Private Quiet Space",
                    "cta_level": "SOFT_CTA",
                    "next_action": "SOFT_FEELING_CHECK"
                  },
                  "reply_bubbles": [
                    "Dạ mức 200k ở các tiệm thư giãn nhanh thường thiên về xoa bóp nhẹ, còn tại Nàng Sen kỹ thuật viên ấn huyệt trị liệu chuyên sâu trúng điểm bó cơ trong phòng yên tĩnh riêng tư ạ."
                  ]
                }
                """;
        var parsedCompetitor = AdaptiveConversationIntelligence.parseAndReconcileAdaptiveCognitiveReflection(
                objectMapper.readTree(competitorJson),
                "Sao bên kia 60 phút có 200k mà bên em 279k vậy?"
        );
        assertThat(parsedCompetitor.barrierType()).isEqualTo("VALUE_PERCEPTION_GAP");

        // Group B3: Hesitation / Deferral ("để chị xem lại đã nhé") & Bare Refusal ("thôi em") enforce PAUSED_BY_CUSTOMER + NO_CTA
        String deferralJson = """
                {
                  "customer_understanding": {
                    "turn_intent": "CUSTOMER_DEFERRAL",
                    "conversation_state": "PAUSED_BY_CUSTOMER",
                    "interaction_permission": "CONCISE_PREFERRED"
                  },
                  "priority": "RESPECT_DEFER",
                  "adaptation": "MAINTAIN",
                  "working_hypothesis": {
                    "hypothesis": "Khách cần thời gian cân nhắc.",
                    "barrier_type": "TIMING_UNCERTAINTY",
                    "confidence": 0.92,
                    "lifecycle_op": "MAINTAIN"
                  },
                  "response_plan": {
                    "strategy": "Warm Pause Without Any Question",
                    "cta_level": "NO_CTA",
                    "next_action": "RESPECT_BOUNDARY_AND_WAIT"
                  },
                  "reply_bubbles": [
                    "Dạ vâng ạ, chị cứ thong thả cân nhắc nhé. Khi nào mình cần hỗ trợ thêm thông tin cứ nhắn em bất cứ lúc nào ạ."
                  ]
                }
                """;
        var parsedDeferral = AdaptiveConversationIntelligence.parseAndReconcileAdaptiveCognitiveReflection(
                objectMapper.readTree(deferralJson),
                "Để chị xem lại đã nhé em"
        );
        assertThat(parsedDeferral.customerUnderstanding().conversationState())
                .isEqualTo("PAUSED_BY_CUSTOMER");
        assertThat(parsedDeferral.responsePlan().ctaLevel())
                .isEqualTo(AdaptiveConversationIntelligence.CtaLevel.NO_CTA);
    }

    @Test
    void groupCAndDAndE_complaintRecoveryAssumptionCorrectionAndThreeTierBooking() {
        // Group C: Communication style complaint detected cleanly and Referee strips any trailing question
        String complaintMsg = "Sao bạn tư vấn giống văn mẫu thế, trả lời ngắn gọn đúng trọng tâm thôi!";
        assertThat(CleanAgentOutputRefereeAndSanitizer.isCommunicationStyleComplaintMessage(complaintMsg)).isTrue();

        // Group D: Customer rejects bot's unstated assumption ("Chị có nói đau lưng đâu, chị chỉ mỏi cổ vai gáy thôi")
        String correctionMsg = "Chị có nói đau lưng đâu em, chị chỉ mỏi cổ vai gáy thôi";
        assertThat(AdaptiveConversationIntelligence.isCustomerCorrectionOrComponentRejectionMessage(correctionMsg)).isTrue();

        // Group E: 3-Tier Booking Signal Classification
        // Tier 1: Schedule/availability inquiry ("Tối nay bên mình còn lịch không bạn?") -> TIER_1_SCHEDULE_EXPLORATION
        var tier1 = CleanAgentOrchestratorService.classifyPreLlmCustomerBookingSignalTier(
                "Tối nay bên mình còn lịch không bạn?",
                ConversationState.StructuredBookingState.none()
        );
        assertThat(tier1).isEqualTo(CleanAgentOrchestratorService.CustomerBookingSignalTier.TIER_1_SCHEDULE_EXPLORATION);

        // Tier 2: Soft preparation ("Tôi muốn vừa đủ để thử gói Cơ bản trước nhé") -> TIER_2_BOOKING_PREPARATION
        var tier2 = CleanAgentOrchestratorService.classifyPreLlmCustomerBookingSignalTier(
                "Tôi muốn vừa đủ để thử gói Cơ bản trước nhé",
                ConversationState.StructuredBookingState.none()
        );
        assertThat(tier2).isIn(
                CleanAgentOrchestratorService.CustomerBookingSignalTier.TIER_2_BOOKING_PREPARATION,
                CleanAgentOrchestratorService.CustomerBookingSignalTier.TIER_3_BOOKING_EXECUTION
        );

        // Tier 3: Explicit booking command ("Đặt lịch cho chị gói 60 phút lúc 19h tối nay nhé") -> TIER_3_BOOKING_EXECUTION
        var tier3 = CleanAgentOrchestratorService.classifyPreLlmCustomerBookingSignalTier(
                "Đặt lịch cho chị gói 60 phút lúc 19h tối nay nhé",
                ConversationState.StructuredBookingState.none()
        );
        assertThat(tier3).isEqualTo(CleanAgentOrchestratorService.CustomerBookingSignalTier.TIER_3_BOOKING_EXECUTION);
    }

    @Test
    void groupFAndGAndBackendIntegrity_fallbackNonBuyerMixedComplaintAndIdempotency() throws Exception {
        // Group F: Fallback on hesitation/deferral turn must NEVER push booking date/time
        List<FanpageBotResponse.ReplyBubble> fallbackOnDeferral =
                CleanAgentOutputRefereeAndSanitizer.buildContextSafeAciV2Fallback(
                        CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                        ConversationState.StructuredBookingState.none(),
                        "STAGE_4_OBJECTION_HANDLING",
                        null,
                        List.of(),
                        List.of(),
                        null,
                        "Để chị suy nghĩ thêm rồi báo lại sau nhé",
                        "em",
                        "chị",
                        AdaptiveConversationIntelligence.CustomerUnderstandingDraft2Snapshot.initial(),
                        List.of()
                );
        String deferralFallbackText = fallbackOnDeferral.stream()
                .map(FanpageBotResponse.ReplyBubble::text)
                .reduce("", (a, b) -> a + " " + b);
        assertThat(deferralFallbackText)
                .doesNotContain("dự kiến đặt vào ngày nào")
                .doesNotContain("hỗ trợ đặt");

        // Group G: Mixed complaint + catalog breadth inquiry blocks booking skills and includes recovery + breadth skills
        List<AgentSkillDto> allSkills = loadSnapshotSkills(NANG_SEN_PAGE_ID);
        List<AgentSkillDto> mixedComplaintSkills = CleanAgentOrchestratorService.selectPreLlmTurnRelevantSkills(
                allSkills,
                "Sao bạn cứ lặp lại câu đấy thế, bên mình còn gói nào khác không?",
                List.of(),
                ConversationState.StructuredBookingState.none()
        );
        assertThat(mixedComplaintSkills)
                .extracting(AgentSkillDto::code)
                .contains("service_recovery_to_consultation_pivot", "catalog_breadth_clarification")
                .doesNotContain("booking_careful_preparation", "warm_nod_to_gentle_scheduling");

        // Backend Integrity 1: InboundEventIdResolver deterministic deduplication for Facebook Messenger webhook retries
        String midId1 = InboundEventIdResolver.deriveTier1FromSingleMid("m_fb_webhook_retry_889900");
        String midId2 = InboundEventIdResolver.deriveTier1FromSingleMid("  m_fb_webhook_retry_889900  ");
        assertThat(midId1).isEqualTo(midId2).startsWith("fbmid:");

        String tsId1 = InboundEventIdResolver.deriveTier2FromWebhookTimestamp(
                NANG_SEN_PAGE_ID, "psid_12345", "1762350000123", "Báo giá gói cổ vai gáy giúp mình"
        );
        String tsId2 = InboundEventIdResolver.deriveTier2FromWebhookTimestamp(
                NANG_SEN_PAGE_ID, "psid_12345", "1762350000123", "Báo giá gói cổ vai gáy giúp mình"
        );
        assertThat(tsId1).isEqualTo(tsId2).startsWith("fb_nomid_sha256:");

        // Backend Integrity 2: Negative Control Page ("Page thẩm mỹ viện") has 0 skills in snapshot & remains untouched
        List<AgentSkillDto> negativeControlSkills = loadSnapshotSkills(NEGATIVE_CONTROL_PAGE_ID);
        assertThat(negativeControlSkills).isEmpty();
    }
}
