package com.example.docmind.service.bot;

import com.example.docmind.dto.bot.AgentSkillDto;
import com.example.docmind.dto.bot.FanpageBotRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SBR-01 Wave 1 — CP3.2: 3-Tier Signal-Driven Booking Gate Verification.
 */
class Sbr01Cp3ThreeTierBookingTest {

    private static final String NANG_SEN_PAGE_ID = "2850a8ef-ca4a-40eb-87fa-4f808640efee";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        CleanAgentOrchestratorService.setCp3ThreeTierBookingEnabled(null);
    }

    private List<AgentSkillDto> buildNangSenSkillsWithThreeTiers() {
        Instant now = Instant.now();
        return List.of(
                new AgentSkillDto(UUID.randomUUID(), "discovery_empathy_and_scope", "Discovery", "PSYCHOLOGY", "Desc", "Trig", "Tac", "Good", "Anti", 98, true, NANG_SEN_PAGE_ID, now, now),
                new AgentSkillDto(UUID.randomUUID(), "price_objection_sensory_value", "Objection", "PSYCHOLOGY", "Desc", "Trig", "Tac", "Good", "Anti", 97, true, NANG_SEN_PAGE_ID, now, now),
                new AgentSkillDto(UUID.randomUUID(), "economical_alternative_compare", "Compare", "PSYCHOLOGY", "Desc", "Trig", "Tac", "Good", "Anti", 96, true, NANG_SEN_PAGE_ID, now, now),
                new AgentSkillDto(UUID.randomUUID(), "warm_nod_to_gentle_scheduling", "Tier 2 Scheduling", "CLOSING", "Desc", "Trig", "Tac", "Good", "Anti", 95, true, NANG_SEN_PAGE_ID, now, now),
                new AgentSkillDto(UUID.randomUUID(), "gentle_next_step_by_signal", "Tier 2 Next Step", "CLOSING", "Desc", "Trig", "Tac", "Good", "Anti", 94, true, NANG_SEN_PAGE_ID, now, now),
                new AgentSkillDto(UUID.randomUUID(), "booking_careful_preparation", "Tier 3 Execution", "CLOSING", "Desc", "Trig", "Tac", "Good", "Anti", 93, true, NANG_SEN_PAGE_ID, now, now),
                new AgentSkillDto(UUID.randomUUID(), "respectful_pause_and_graceful_close", "Pause", "PSYCHOLOGY", "Desc", "Trig", "Tac", "Good", "Anti", 92, true, NANG_SEN_PAGE_ID, now, now)
        );
    }

    private AdaptiveConversationIntelligence.AdaptiveCognitiveReflection parseReflection(
            String bookingReadiness,
            String buyingSignal,
            String priority,
            List<String> activatedSkillCodes
    ) throws Exception {
        String skillsJson = objectMapper.writeValueAsString(activatedSkillCodes);
        String json = """
                {
                  "customer_understanding": {
                    "turn_intent": "ASK_SERVICE",
                    "conversation_state": "ACTIVE_CONSULTATION",
                    "buying_signal": "%s",
                    "booking_readiness": "%s"
                  },
                  "priority": "%s",
                  "response_plan": {
                    "strategy": "Direct Answer",
                    "tone": "LEVEL_1_NATURAL",
                    "depth": "STANDARD",
                    "cta_level": "SOFT_CTA",
                    "next_action": "GENTLE_SCHEDULE_CHECK",
                    "adaptation": "CONTINUATION"
                  },
                  "activated_skill_codes": %s
                }
                """.formatted(buyingSignal, bookingReadiness, priority, skillsJson);
        JsonNode root = objectMapper.readTree(json);
        return AdaptiveConversationIntelligence.parseAdaptiveCognitiveReflection(root, null);
    }

    @Test
    @DisplayName("CP3.2-1: Tier 1 (hỏi lịch / hỏi chi nhánh / hỏi cách đặt) mở Tier 1-2 trước READY_TO_BOOK và chặn Tier 3")
    void tier1ScheduleOrBranchInquiryOpensTier1And2ButBlocksTier3() throws Exception {
        List<AgentSkillDto> skills = buildNangSenSkillsWithThreeTiers();
        String msg = "Cơ sở Quận 1 bên mình mở cửa đến mấy giờ, muốn đặt lịch thì làm thế nào?";

        assertThat(CleanAgentOrchestratorService.classifyPreLlmCustomerBookingSignalTier(
                msg, ConversationState.StructuredBookingState.none()))
                .isEqualTo(CleanAgentOrchestratorService.CustomerBookingSignalTier.TIER_1_SCHEDULE_EXPLORATION);

        List<AgentSkillDto> preSelected = CleanAgentOrchestratorService.selectPreLlmTurnRelevantSkills(
                skills, msg, List.of(), ConversationState.StructuredBookingState.none());
        assertThat(preSelected).extracting(AgentSkillDto::code)
                .contains("warm_nod_to_gentle_scheduling")
                .doesNotContain("booking_careful_preparation");

        var reflection = parseReflection(
                "EXPLORING",
                "MODERATE",
                "CLARIFICATION",
                List.of("booking_careful_preparation", "warm_nod_to_gentle_scheduling")
        );
        var diag = CleanAgentOrchestratorService.evaluateAciTurnSkillGateDiagnostics(
                skills, preSelected, reflection, ConversationState.StructuredBookingState.none(), msg);

        assertThat(diag.bookingReadiness()).isEqualTo(AdaptiveConversationIntelligence.BookingReadiness.EXPLORING);
        assertThat(diag.activatedSkillCodes()).doesNotContain("booking_careful_preparation");
        assertThat(diag.activatedSkillCodes()).contains("warm_nod_to_gentle_scheduling");
        assertThat(diag.blockedSkillReasons())
                .containsKey("booking_careful_preparation");
        assertThat(diag.blockedSkillReasons().get("booking_careful_preparation"))
                .contains("BLOCKED_TIER_3_REQUIRES_EXPLICIT_BOOKING_REQUEST");
    }

    @Test
    @DisplayName("CP3.2-2: Tier 2 (đồng ý thử gói / CONSIDERING) mở Tier 2 trước READY_TO_BOOK, chặn Tier 3, và hỗ trợ toggle flag")
    void tier2PackageTrialAcceptanceOpensTier2BeforeReadyToBookAndRespectsToggle() throws Exception {
        List<AgentSkillDto> skills = buildNangSenSkillsWithThreeTiers();
        String msg = "Ok thế mình muốn thử gói 399k trước nhé";

        assertThat(CleanAgentOrchestratorService.classifyPreLlmCustomerBookingSignalTier(
                msg, ConversationState.StructuredBookingState.none()))
                .isEqualTo(CleanAgentOrchestratorService.CustomerBookingSignalTier.TIER_2_BOOKING_PREPARATION);

        List<AgentSkillDto> preSelected = CleanAgentOrchestratorService.selectPreLlmTurnRelevantSkills(
                skills, msg, List.of(), ConversationState.StructuredBookingState.none());
        assertThat(preSelected).extracting(AgentSkillDto::code)
                .contains("warm_nod_to_gentle_scheduling")
                .doesNotContain("booking_careful_preparation");

        var reflection = parseReflection(
                "CONSIDERING",
                "STRONG",
                "VALUE_ALIGNMENT",
                List.of("gentle_next_step_by_signal", "booking_careful_preparation")
        );
        var diag = CleanAgentOrchestratorService.evaluateAciTurnSkillGateDiagnostics(
                skills, preSelected, reflection, ConversationState.StructuredBookingState.none(), msg);

        assertThat(diag.customerSignalTier())
                .isEqualTo(CleanAgentOrchestratorService.CustomerBookingSignalTier.TIER_2_BOOKING_PREPARATION);
        assertThat(diag.activatedSkillCodes())
                .containsExactly("gentle_next_step_by_signal")
                .doesNotContain("booking_careful_preparation");
        assertThat(diag.blockedSkillReasons().get("booking_careful_preparation"))
                .contains("BLOCKED_TIER_3_REQUIRES_EXPLICIT_BOOKING_REQUEST");

        // Khi tắt cờ CP3.2 -> quay về cổng legacy chặn toàn bộ skill đặt lịch khi chưa READY_TO_BOOK
        CleanAgentOrchestratorService.setCp3ThreeTierBookingEnabled(false);
        var legacyDiag = CleanAgentOrchestratorService.evaluateAciTurnSkillGateDiagnostics(
                skills, preSelected, reflection, ConversationState.StructuredBookingState.none(), msg);
        assertThat(legacyDiag.activatedSkillCodes()).isEmpty();
        assertThat(legacyDiag.blockedSkillReasons().get("gentle_next_step_by_signal"))
                .contains("LEGACY_GATE_NOT_READY_TO_BOOK");
    }

    @Test
    @DisplayName("CP3.2-3: Tier 3 (khách yêu cầu đặt lịch rõ ràng) mở cả Tier 3 booking_careful_preparation; hội thoại dài 8 lượt không có tín hiệu đặt lịch -> 0 booking skill")
    void tier3ExplicitBookingRequestOpensTier3WhileLongHistoryWithoutSignalOpensZeroBookingSkills() throws Exception {
        List<AgentSkillDto> skills = buildNangSenSkillsWithThreeTiers();

        // 1. Yêu cầu đặt lịch rõ ràng -> mở Tier 3
        String bookMsg = "Đặt lịch cho mình gói 399k lúc 19h tối nay nhé";
        assertThat(CleanAgentOrchestratorService.classifyPreLlmCustomerBookingSignalTier(
                bookMsg, ConversationState.StructuredBookingState.none()))
                .isEqualTo(CleanAgentOrchestratorService.CustomerBookingSignalTier.TIER_3_BOOKING_EXECUTION);

        var tier3Reflection = parseReflection(
                "READY_TO_BOOK",
                "ACTION",
                "BOOKING_COMPLETION",
                List.of("booking_careful_preparation")
        );
        var tier3Diag = CleanAgentOrchestratorService.evaluateAciTurnSkillGateDiagnostics(
                skills, skills, tier3Reflection, ConversationState.StructuredBookingState.none(), bookMsg);
        assertThat(tier3Diag.customerSignalTier())
                .isEqualTo(CleanAgentOrchestratorService.CustomerBookingSignalTier.TIER_3_BOOKING_EXECUTION);
        assertThat(tier3Diag.activatedSkillCodes()).containsExactly("booking_careful_preparation");
        assertThat(tier3Diag.blockedSkillReasons()).isEmpty();

        // 2. Hội thoại 8 lượt nhưng khách chỉ hỏi thông tin gói -> KHÔNG mở theo số lượt (signalTier == NONE)
        List<FanpageBotRequest.ConversationTurn> longHistory = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Chào shop"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ em chào mình ạ"),
                new FanpageBotRequest.ConversationTurn("user", "Mình bị mỏi cổ vai gáy"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ mình mỏi lâu chưa ạ?"),
                new FanpageBotRequest.ConversationTurn("user", "Khoảng 1 tuần nay"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ bên em có gói 239k và 399k ạ"),
                new FanpageBotRequest.ConversationTurn("user", "2 gói đó khác gì nhau?"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ gói 239k thiên về gội dưỡng sinh, gói 399k chuyên massage body ạ")
        );
        String infoMsg = "Mình muốn hỏi gói dưỡng sinh 239k gồm những bước nào?";
        List<AgentSkillDto> preSelectedLong = CleanAgentOrchestratorService.selectPreLlmTurnRelevantSkills(
                skills, infoMsg, longHistory, ConversationState.StructuredBookingState.none());
        assertThat(preSelectedLong).extracting(AgentSkillDto::code)
                .doesNotContain("warm_nod_to_gentle_scheduling", "gentle_next_step_by_signal", "booking_careful_preparation");

        var noSignalReflection = parseReflection(
                "EXPLORING",
                "WEAK",
                "PRICE_INFORMATION",
                List.of("economical_alternative_compare", "warm_nod_to_gentle_scheduling")
        );
        var noSignalDiag = CleanAgentOrchestratorService.evaluateAciTurnSkillGateDiagnostics(
                skills, preSelectedLong, noSignalReflection, ConversationState.StructuredBookingState.none(), infoMsg);
        assertThat(noSignalDiag.customerSignalTier())
                .isEqualTo(CleanAgentOrchestratorService.CustomerBookingSignalTier.NONE);
        assertThat(noSignalDiag.activatedSkillCodes())
                .containsExactly("economical_alternative_compare")
                .doesNotContain("warm_nod_to_gentle_scheduling", "booking_careful_preparation");
        assertThat(noSignalDiag.blockedSkillReasons().get("warm_nod_to_gentle_scheduling"))
                .contains("BLOCKED_NO_CUSTOMER_BOOKING_SIGNAL");
    }
}
