package com.example.docmind.service.bot;

import com.example.docmind.dto.bot.AgentSkillDto;
import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.dto.bot.FanpageBotResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate P0.3 Verification Suite — Decision Ownership Refactor:
 * <ul>
 *   <li>1. Pre-LLM Layer 4 in {@code <SESSION_MEMORY_5_TIER>} is Evidence-Only on consultative turns (no {@code recommended_action} or {@code contextual_action_guidance: "BẮT BUỘC..."}).</li>
 *   <li>2. Pre-LLM {@code selectPreLlmTurnRelevantSkills()} provides the full Eligible Consultative Skill Menu without {@code < 3} truncation while enforcing 3-Tier Booking and Pause/Complaint gates.</li>
 *   <li>3. Bare {@code "Vừa đủ"} / {@code "Nhẹ nhàng hơn"} after a discovery question does NOT false-lock {@code TRIAL_COMMITMENT_LOCK} or {@code selectedProductId}.</li>
 *   <li>4. Multi-branch catalog ({@code HN} vs {@code SG}) exposes both branch starting prices in {@code starting_package_anchor} and {@code branch_price_variance_notice}, and records {@code customer_branch_location_if_multi_branch_catalog} in {@code unknowns}.</li>
 *   <li>5. Post-LLM {@code reconcileWithCurrentUtterance()} preserves valid LLM {@code strategy}, {@code cta_level}, {@code next_action}, and {@code barrier_type} on consultative/objection/comparison turns.</li>
 *   <li>6. Concise price objection ({@code "Đắt"}) routes to {@code FAST_NO_THINK}, clears unconfirmed pre-slot booking state, and never triggers {@code FALLBACK_BOOKING_HOLD}.</li>
 * </ul>
 */
class P03DecisionOwnershipRefactorTest {

    private static final String NANG_SEN_PAGE_ID = "2850a8ef-ca4a-40eb-87fa-4f808640efee";
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static PackageCatalogService.PackageSummary branchPkg(
            String id,
            String code,
            String name,
            long priceVnd,
            String priceDisplay,
            int minutes,
            String region
    ) {
        return new PackageCatalogService.PackageSummary(
                id,
                code,
                region,
                name,
                "GỘI ĐẦU DƯỠNG SINH",
                List.of("đầu", "cổ", "vai", "gáy"),
                minutes,
                minutes + " phút",
                priceVnd,
                priceDisplay,
                "Phòng chung có rèm riêng",
                "Gội đầu thảo dược, massage cổ vai gáy",
                "Đau mỏi cổ vai gáy",
                "Chăm sóc thư giãn",
                false
        );
    }

    private List<AgentSkillDto> buildTenNangSenSkills() {
        Instant now = Instant.now();
        return List.of(
                new AgentSkillDto(UUID.randomUUID(), "discovery_empathy_and_scope", "Discovery", "PSYCHOLOGY", "Desc", "Trig", "Tac", "Nguyên tắc: [Mục tiêu] -> [Rào cản] -> [Nguyên tắc]", "Anti", 100, true, NANG_SEN_PAGE_ID, now, now),
                new AgentSkillDto(UUID.randomUUID(), "price_objection_sensory_value", "Objection", "PSYCHOLOGY", "Desc", "Trig", "Tac", "Nguyên tắc: [Mục tiêu] -> [Rào cản] -> [Nguyên tắc]", "Anti", 95, true, NANG_SEN_PAGE_ID, now, now),
                new AgentSkillDto(UUID.randomUUID(), "economical_option_before_close", "Economical", "PSYCHOLOGY", "Desc", "Trig", "Tac", "Nguyên tắc: [Mục tiêu] -> [Rào cản] -> [Nguyên tắc]", "Anti", 90, true, NANG_SEN_PAGE_ID, now, now),
                new AgentSkillDto(UUID.randomUUID(), "package_comparison_and_scope_clarity", "Comparison", "PSYCHOLOGY", "Desc", "Trig", "Tac", "Nguyên tắc: [Mục tiêu] -> [Rào cản] -> [Nguyên tắc]", "Anti", 89, true, NANG_SEN_PAGE_ID, now, now),
                new AgentSkillDto(UUID.randomUUID(), "catalog_breadth_clarification", "Breadth", "PSYCHOLOGY", "Desc", "Trig", "Tac", "Nguyên tắc: [Mục tiêu] -> [Rào cản] -> [Nguyên tắc]", "Anti", 88, true, NANG_SEN_PAGE_ID, now, now),
                new AgentSkillDto(UUID.randomUUID(), "service_recovery_to_consultation_pivot", "Recovery", "PSYCHOLOGY", "Desc", "Trig", "Tac", "Nguyên tắc: [Mục tiêu] -> [Rào cản] -> [Nguyên tắc]", "Anti", 87, true, NANG_SEN_PAGE_ID, now, now),
                new AgentSkillDto(UUID.randomUUID(), "warm_nod_to_gentle_scheduling", "Tier 2 Scheduling", "CLOSING", "Desc", "Trig", "Tac", "Nguyên tắc: [Mục tiêu] -> [Rào cản] -> [Nguyên tắc]", "Anti", 85, true, NANG_SEN_PAGE_ID, now, now),
                new AgentSkillDto(UUID.randomUUID(), "gentle_next_step_by_signal", "Tier 1-2 Next Step", "CLOSING", "Desc", "Trig", "Tac", "Nguyên tắc: [Mục tiêu] -> [Rào cản] -> [Nguyên tắc]", "Anti", 84, true, NANG_SEN_PAGE_ID, now, now),
                new AgentSkillDto(UUID.randomUUID(), "booking_careful_preparation", "Tier 3 Booking", "CLOSING", "Desc", "Trig", "Tac", "Nguyên tắc: [Mục tiêu] -> [Rào cản] -> [Nguyên tắc]", "Anti", 80, true, NANG_SEN_PAGE_ID, now, now),
                new AgentSkillDto(UUID.randomUUID(), "graceful_pause_keep_warmth", "Pause", "PSYCHOLOGY", "Desc", "Trig", "Tac", "Nguyên tắc: [Mục tiêu] -> [Rào cản] -> [Nguyên tắc]", "Anti", 75, true, NANG_SEN_PAGE_ID, now, now)
        );
    }

    @Test
    @DisplayName("P0.3-1: Layer 4 in <SESSION_MEMORY_5_TIER> is Evidence-Only on consultative turns and exposes multi-branch starting price anchor")
    void layer4IsEvidenceOnlyOnConsultativeTurnsAndExposesMultiBranchAnchor() {
        PackageCatalogService.PackageSummary hnBasic = branchPkg(
                "c5000001-0000-0000-0000-000000000001", "DS_CB_HN", "Gói Cơ bản", 599_000L, "599.000đ", 85, "HN");
        PackageCatalogService.PackageSummary sgBasic = branchPkg(
                "c6000001-0000-0000-0000-000000000001", "DS_CB_SG", "Gói Cơ bản", 699_000L, "699.000đ", 85, "SG");

        String msg = "Gội đầu dưỡng sinh ở ngoài có 100k mà em";
        var threeStream = new AdaptiveConversationIntelligence.ThreeStreamTurnInput(
                msg, List.of(msg), msg.toLowerCase(), List.of());
        var draft2 = AdaptiveConversationIntelligence.CustomerUnderstandingDraft2Snapshot.initial();
        var snap = AdaptiveConversationIntelligence.buildTurnContextSnapshot(
                "T3", msg, "anh", true, 0, List.of(), draft2, ConversationState.StructuredBookingState.none());

        assertThat(snap.plan().unknowns())
                .contains("customer_branch_location_if_multi_branch_catalog");

        String prompt = CleanAgentCatalogAndPromptBuilder.buildAciSinglePassPrompt(
                null, "anh", "anh", "anh", threeStream,
                List.of(hnBasic, sgBasic), List.of(),
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                List.of(), null,
                List.of(), ConversationState.StructuredBookingState.none(),
                null, null,
                List.of(), null, false,
                List.of(), List.of(),
                buildTenNangSenSkills(), List.of(), draft2, snap.plan()
        );

        assertThat(prompt)
                .contains("4. lightweight_customer_evidence_and_unknowns:")
                .contains("decision_ownership_policy: \"LLM_SINGLE_PASS_AUTONOMOUS")
                .contains("customer_branch_location_if_multi_branch_catalog")
                .contains("599.000đ (chi nhánh Hà Nội) / 699.000đ (chi nhánh TP.HCM)")
                .contains("branch_price_variance_notice: \"MULTI_BRANCH_CATALOG:")
                .contains("Mẫu tham khảo chỉ minh họa tư duy (CẤM chép nguyên văn)")
                .doesNotContain("4. lightweight_customer_understanding_and_next_action_planner:")
                .doesNotContain("- recommended_action:")
                .doesNotContain("- contextual_action_guidance:");
    }

    @Test
    @DisplayName("P0.3-2: selectPreLlmTurnRelevantSkills returns full Eligible Consultative Skill Menu without <3 truncation while blocking unauthorized booking/pause skills")
    void selectPreLlmTurnRelevantSkillsReturnsEligibleConsultativeMenuWithoutTruncation() {
        List<AgentSkillDto> allSkills = buildTenNangSenSkills();

        List<AgentSkillDto> compTurnSkills = CleanAgentOrchestratorService.selectPreLlmTurnRelevantSkills(
                allSkills,
                "Gội đầu dưỡng sinh ở ngoài có 100k mà em",
                List.of(),
                ConversationState.StructuredBookingState.none()
        );

        assertThat(compTurnSkills)
                .extracting(AgentSkillDto::code)
                .contains(
                        "discovery_empathy_and_scope",
                        "price_objection_sensory_value",
                        "economical_option_before_close",
                        "package_comparison_and_scope_clarity",
                        "catalog_breadth_clarification"
                )
                .doesNotContain(
                        "warm_nod_to_gentle_scheduling",
                        "gentle_next_step_by_signal",
                        "booking_careful_preparation",
                        "graceful_pause_keep_warmth"
                );
    }

    @Test
    @DisplayName("P0.3-3: Bare 'Vừa đủ' in reply to discovery question is classified as SCOPE_CONFIRMATION and never locks TRIAL_COMMITMENT_LOCK or selectedProductId")
    void bareVuaDuAfterDiscoveryDoesNotFalseLockTrialCommitment() {
        PackageCatalogService.PackageSummary hnBasic = branchPkg(
                "c5000001-0000-0000-0000-000000000001", "DS_CB_HN", "Gói Cơ bản", 599_000L, "599.000đ", 85, "HN");
        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Mình bị mỏi cổ vai gáy và đau đầu"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ mình muốn trải nghiệm buổi thư giãn vừa đủ hay dành thêm thời gian chăm sóc chuyên sâu hơn ạ?")
        );

        assertThat(AdaptiveConversationIntelligence.isTrialOrRelativeOptionSelectionMessage("Vừa đủ")).isFalse();
        assertThat(AdaptiveConversationIntelligence.isTrialOrRelativeOptionSelectionMessage("tôi muốn vừa đủ để thử")).isTrue();

        Set<AdaptiveConversationIntelligence.CanonicalTurnIntent> intents =
                AdaptiveConversationIntelligence.detectCanonicalTurnIntents(
                        "Vừa đủ",
                        history,
                        AdaptiveConversationIntelligence.CustomerUnderstandingDraft2Snapshot.initial(),
                        ConversationState.StructuredBookingState.none()
                );
        assertThat(intents)
                .contains(AdaptiveConversationIntelligence.CanonicalTurnIntent.SCOPE_CONFIRMATION)
                .doesNotContain(AdaptiveConversationIntelligence.CanonicalTurnIntent.TRIAL_COMMITMENT_SELECTION);

        ConversationState.StructuredBookingState afterTurn =
                AdaptiveConversationIntelligence.applyAuthoritativeBookingPatch(
                        ConversationState.StructuredBookingState.none(),
                        AdaptiveConversationIntelligence.ProposedBookingPatch.empty(),
                        "Vừa đủ",
                        history.get(1).content(),
                        List.of(hnBasic),
                        List.of(),
                        ZoneId.of("Asia/Ho_Chi_Minh"),
                        AdaptiveConversationIntelligence.BookingReadiness.EXPLORING,
                        "NEED_DISCOVERY"
                );
        assertThat(afterTurn.status()).isEqualTo(ConversationState.BookingStatus.NONE);
        assertThat(afterTurn.selectedProductId()).isNull();
    }

    @Test
    @DisplayName("P0.3-4: reconcileWithCurrentUtterance preserves LLM NO_CTA / NO_ADDITIONAL_ACTION on competitor comparison and preserves LLM barrier_type on price objection")
    void reconcileWithCurrentUtterancePreservesLlmAutonomyOnObjectionAndComparison() throws Exception {
        String compJson = """
                {
                  "customer_understanding": {
                    "turn_intent": "COMPETITOR_COMPARISON",
                    "conversation_state": "OBJECTION_NEGOTIATION",
                    "buying_signal": "WEAK",
                    "booking_readiness": "EXPLORING",
                    "interaction_permission": "OPEN"
                  },
                  "priority": "VALUE_ALIGNMENT",
                  "working_hypothesis": {
                    "hypothesis": "Khách so sánh giá gội thường bên ngoài với liệu trình dưỡng sinh",
                    "barrier_type": "COMPETITOR_COMPARISON",
                    "confidence": 0.85,
                    "lifecycle_op": "ADJUST"
                  },
                  "response_plan": {
                    "strategy": "Explain Value",
                    "tone": "LEVEL_1_NATURAL",
                    "depth": "CONCISE",
                    "cta_level": "NO_CTA",
                    "next_action": "NO_ADDITIONAL_ACTION",
                    "adaptation": "REFINEMENT"
                  },
                  "activated_skill_codes": ["price_objection_sensory_value"]
                }
                """;
        JsonNode compNode = objectMapper.readTree(compJson);
        var compRef = AdaptiveConversationIntelligence.parseAdaptiveCognitiveReflection(compNode, null)
                .reconcileWithCurrentUtterance("Gội đầu dưỡng sinh ở ngoài có 100k mà em");
        assertThat(compRef.responsePlan().strategy()).isEqualTo("Explain Value");
        assertThat(compRef.responsePlan().ctaLevel()).isEqualTo(AdaptiveConversationIntelligence.CtaLevel.NO_CTA);
        assertThat(compRef.responsePlan().nextAction()).isEqualTo("NO_ADDITIONAL_ACTION");

        String priceObjJson = """
                {
                  "customer_understanding": {
                    "turn_intent": "PRICE_OBJECTION",
                    "conversation_state": "OBJECTION_NEGOTIATION",
                    "cost_concern_expressed": true,
                    "buying_signal": "WEAK",
                    "booking_readiness": "EXPLORING",
                    "interaction_permission": "OPEN"
                  },
                  "priority": "VALUE_ALIGNMENT",
                  "working_hypothesis": {
                    "hypothesis": "Khách thấy mức giá vượt ngân sách dự kiến cho 1 buổi",
                    "barrier_type": "BUDGET_CONSTRAINT",
                    "confidence": 0.80,
                    "lifecycle_op": "ADJUST"
                  },
                  "response_plan": {
                    "strategy": "Handle Objection",
                    "tone": "LEVEL_1_NATURAL",
                    "depth": "CONCISE",
                    "cta_level": "NO_CTA",
                    "next_action": "NO_ADDITIONAL_ACTION",
                    "adaptation": "PIVOT"
                  },
                  "activated_skill_codes": ["price_objection_sensory_value"]
                }
                """;
        JsonNode priceObjNode = objectMapper.readTree(priceObjJson);
        var priceObjRef = AdaptiveConversationIntelligence.parseAdaptiveCognitiveReflection(priceObjNode, null)
                .reconcileWithCurrentUtterance("Đắt");
        assertThat(priceObjRef.barrierType())
                .isEqualTo("BUDGET_LIMIT")
                .isNotEqualTo("VALUE_PERCEPTION_GAP");
        assertThat(priceObjRef.responsePlan().ctaLevel()).isEqualTo(AdaptiveConversationIntelligence.CtaLevel.NO_CTA);
        assertThat(priceObjRef.responsePlan().nextAction()).isEqualTo("NO_ADDITIONAL_ACTION");
    }

    @Test
    @DisplayName("P0.3-5: Concise price objection ('Đắt') uses FAST_NO_THINK, clears unconfirmed booking state, and never falls back to FALLBACK_BOOKING_HOLD")
    void concisePriceObjectionUsesFastNoThinkAndNeverTriggersFallbackBookingHold() {
        PackageCatalogService.PackageSummary hnBasic = branchPkg(
                "c5000001-0000-0000-0000-000000000001", "DS_CB_HN", "Gói Cơ bản", 599_000L, "599.000đ", 85, "HN");

        var threeStream = new AdaptiveConversationIntelligence.ThreeStreamTurnInput(
                "Đắt", List.of("Đắt"), "đắt", List.of());
        ThinkingMode mode = CleanAgentOrchestratorService.selectAciAdaptiveThinkingMode(
                "Đắt", threeStream, List.of());
        assertThat(mode).isEqualTo(ThinkingMode.FAST_NO_THINK);

        ConversationState.StructuredBookingState pollutedPrior = new ConversationState.StructuredBookingState(
                hnBasic.productId(),
                hnBasic.canonicalLabel(),
                null,
                null,
                null,
                "Asia/Ho_Chi_Minh",
                ConversationState.BookingStatus.AWAITING_DATE_OR_TIME,
                List.of(hnBasic.productId())
        );

        ConversationState.StructuredBookingState cleanedState =
                AdaptiveConversationIntelligence.applyAuthoritativeBookingPatch(
                        pollutedPrior,
                        AdaptiveConversationIntelligence.ProposedBookingPatch.empty(),
                        "Đắt",
                        "Dạ gói Cơ bản 85 phút giá 599.000đ ạ.",
                        List.of(hnBasic),
                        List.of(hnBasic),
                        ZoneId.of("Asia/Ho_Chi_Minh"),
                        AdaptiveConversationIntelligence.BookingReadiness.EXPLORING,
                        "VALUE_ALIGNMENT"
                );
        assertThat(cleanedState.status()).isEqualTo(ConversationState.BookingStatus.NONE);
        assertThat(cleanedState.selectedProductId()).isNull();

        String fallbackSpeechAct = CleanAgentOutputRefereeAndSanitizer.resolveFallbackActualSentSpeechAct(
                "Đắt",
                pollutedPrior,
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                AdaptiveConversationIntelligence.CustomerUnderstandingDraft2Snapshot.initial()
        );
        assertThat(fallbackSpeechAct).isNotEqualTo("FALLBACK_BOOKING_HOLD");

        List<FanpageBotResponse.ReplyBubble> fallbackBubbles =
                CleanAgentOutputRefereeAndSanitizer.buildContextSafeAciV2Fallback(
                        CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                        pollutedPrior,
                        "STAGE_4_OBJECTION_HANDLING",
                        hnBasic,
                        List.of(hnBasic),
                        List.of(hnBasic),
                        null,
                        "Đắt",
                        "em",
                        "anh",
                        AdaptiveConversationIntelligence.CustomerUnderstandingDraft2Snapshot.initial(),
                        List.of()
                );
        String combinedFallback = fallbackBubbles.stream().map(FanpageBotResponse.ReplyBubble::text).reduce("", (a, b) -> a + " " + b);
        assertThat(combinedFallback)
                .doesNotContain("hỗ trợ đặt")
                .doesNotContain("dự kiến đặt vào ngày nào");
    }
}
