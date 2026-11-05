package com.example.docmind.service.bot;

import com.example.docmind.dto.bot.AgentSkillDto;
import com.example.docmind.dto.bot.FanpageBotRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test cho SBR-01 Wave 1 — CP3.4:
 * Kiểm toán 6 skill hiện có + bổ sung 4 skill nguyên tắc ngắn cho Nàng Sen
 * và kiểm chứng cách ly cross-page (Negative Control: Page thẩm mỹ viện).
 */
class Sbr01Cp3SkillAuditAndIsolationTest {

    private static final String NANG_SEN_PAGE_ID = "2850a8ef-ca4a-40eb-87fa-4f808640efee";
    private static final String NEGATIVE_CONTROL_BEAUTY_CLINIC_PAGE_ID = "59e68078-8327-4aa0-8cca-91410f783ad4";

    @AfterEach
    void tearDown() {
        CleanAgentOrchestratorService.setCp3SkillAuditEnabled(null);
        CleanAgentOrchestratorService.setCp3ThreeTierBookingEnabled(null);
        CleanAgentCatalogAndPromptBuilder.setCp3SkillGoodExampleEnabled(null);
    }

    private List<AgentSkillDto> loadSnapshotSkillsForPage(String targetPageId) throws Exception {
        Path snapshotPath = Path.of("..", "benchmark", "sbr01", "fixtures", "sbr01_kb_catalog_skills_snapshot.json");
        if (!Files.exists(snapshotPath)) {
            snapshotPath = Path.of("benchmark", "sbr01", "fixtures", "sbr01_kb_catalog_skills_snapshot.json");
        }
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(Files.readString(snapshotPath));
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
    @DisplayName("CP3.4-1: Toàn bộ 10 skill của Nàng Sen đều ở dạng nguyên tắc ngắn, không nhúng câu thoại mẫu dài hay cụm 'đi tập nặng về'")
    void allTenNangSenSkillsArePrincipleBasedWithoutEmbeddedDialogueTemplates() throws Exception {
        List<AgentSkillDto> nangSenSkills = loadSnapshotSkillsForPage(NANG_SEN_PAGE_ID);
        assertEquals(10, nangSenSkills.size(), "Nàng Sen phải có đúng 10 active skills (6 skill đã rút gọn + 4 skill nguyên tắc mới)");

        List<String> codes = nangSenSkills.stream().map(AgentSkillDto::code).toList();
        assertTrue(codes.contains("package_comparison_and_scope_clarity"));
        assertTrue(codes.contains("catalog_breadth_clarification"));
        assertTrue(codes.contains("service_recovery_to_consultation_pivot"));
        assertTrue(codes.contains("gentle_next_step_by_signal"));

        for (AgentSkillDto skill : nangSenSkills) {
            assertFalse(skill.tacticalInstructions().contains("[Các hướng diễn đạt gợi cảm xúc theo ngữ cảnh]"),
                    "Skill " + skill.code() + " không được nhúng câu thoại mẫu trong tactical_instructions");
            assertFalse(skill.tacticalInstructions().toLowerCase().contains("tập nặng"),
                    "Skill " + skill.code() + " không được chứa giả định 'tập nặng'");
            assertFalse(skill.goodExample().toLowerCase().contains("tập nặng"),
                    "Skill " + skill.code() + " good_example không được chứa 'tập nặng'");
            assertTrue(skill.goodExample().startsWith("Nguyên tắc:"),
                    "Skill " + skill.code() + " phải dùng good_example dạng nguyên tắc ngắn thay vì câu trả lời mẫu nguyên văn");
        }
    }

    @Test
    @DisplayName("CP3.4-2: Định tuyến đúng 4 skill nguyên tắc mới theo tín hiệu lượt chat và hỗ trợ cờ bật/tắt CP3.4")
    void routesFourNewPrincipleSkillsAccuratelyAndRespectsToggle() throws Exception {
        List<AgentSkillDto> nangSenSkills = loadSnapshotSkillsForPage(NANG_SEN_PAGE_ID);
        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Mình bị đau mỏi cổ vai gáy quá"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ bên em có gói Cơ bản (599K, 85 phút) và Chuyên sâu (899K, 105 phút) ạ.")
        );

        // 1. So sánh gói -> package_comparison_and_scope_clarity
        List<AgentSkillDto> compareSkills = CleanAgentOrchestratorService.selectPreLlmTurnRelevantSkills(
                nangSenSkills,
                "2 gói này khác nhau chỗ nào thế bạn?",
                history,
                ConversationState.StructuredBookingState.none()
        );
        assertTrue(compareSkills.stream().anyMatch(s -> "package_comparison_and_scope_clarity".equals(s.code())),
                "Lượt hỏi so sánh gói phải chọn package_comparison_and_scope_clarity");

        // 2. Hỏi độ rộng danh mục -> catalog_breadth_clarification
        List<AgentSkillDto> breadthSkills = CleanAgentOrchestratorService.selectPreLlmTurnRelevantSkills(
                nangSenSkills,
                "Bên mình còn gói nào khác không chứ mình tưởng chỉ có 2 gói?",
                history,
                ConversationState.StructuredBookingState.none()
        );
        assertTrue(breadthSkills.stream().anyMatch(s -> "catalog_breadth_clarification".equals(s.code())),
                "Lượt hỏi còn gói nào khác phải chọn catalog_breadth_clarification");

        // 3. Phàn nàn kèm câu hỏi nghiệp vụ (Mixed Turn) -> service_recovery_to_consultation_pivot + catalog_breadth_clarification, chặn booking
        List<AgentSkillDto> mixedRecoverySkills = CleanAgentOrchestratorService.selectPreLlmTurnRelevantSkills(
                nangSenSkills,
                "Sao bạn cứ lặp lại câu đấy thế, bên mình còn gói nào khác không?",
                history,
                ConversationState.StructuredBookingState.none()
        );
        assertTrue(mixedRecoverySkills.stream().anyMatch(s -> "service_recovery_to_consultation_pivot".equals(s.code())),
                "Lượt phàn nàn kèm câu hỏi nghiệp vụ phải chọn service_recovery_to_consultation_pivot");
        assertTrue(mixedRecoverySkills.stream().anyMatch(s -> "catalog_breadth_clarification".equals(s.code())),
                "Lượt phàn nàn kèm hỏi gói khác phải chọn thêm catalog_breadth_clarification");
        assertFalse(mixedRecoverySkills.stream().anyMatch(s -> s.code().contains("booking") || s.code().contains("warm_nod") || s.code().contains("gentle_next_step")),
                "Lượt phàn nàn kèm câu hỏi nghiệp vụ không được mở bất kỳ booking skill nào");

        // 4. Tín hiệu tìm hiểu lịch (Cấp 1) -> gentle_next_step_by_signal
        List<AgentSkillDto> tier1ScheduleSkills = CleanAgentOrchestratorService.selectPreLlmTurnRelevantSkills(
                nangSenSkills,
                "Tối nay bên mình còn lịch không bạn?",
                history,
                ConversationState.StructuredBookingState.none()
        );
        assertTrue(tier1ScheduleSkills.stream().anyMatch(s -> "gentle_next_step_by_signal".equals(s.code())),
                "Lượt hỏi lịch trống Cấp 1 phải chọn gentle_next_step_by_signal");

        // 5. Khi tắt cờ CP3.4 -> 4 skill mới bị ngắt hoàn toàn
        CleanAgentOrchestratorService.setCp3SkillAuditEnabled(false);
        List<AgentSkillDto> legacyCompareSkills = CleanAgentOrchestratorService.selectPreLlmTurnRelevantSkills(
                nangSenSkills,
                "2 gói này khác nhau chỗ nào thế bạn?",
                history,
                ConversationState.StructuredBookingState.none()
        );
        assertFalse(legacyCompareSkills.stream().anyMatch(s -> "package_comparison_and_scope_clarity".equals(s.code())),
                "Khi tắt cờ CP3.4, không được chọn package_comparison_and_scope_clarity");
    }

    @Test
    @DisplayName("CP3.4-3: Kiểm chứng Negative Control — Page thẩm mỹ viện hoàn toàn cách ly, không nạp skill hoặc dữ liệu Nàng Sen")
    void negativeControlBeautyClinicPageIsStrictlyIsolatedFromNangSen() throws Exception {
        List<AgentSkillDto> beautyClinicSkills = loadSnapshotSkillsForPage(NEGATIVE_CONTROL_BEAUTY_CLINIC_PAGE_ID);
        assertTrue(beautyClinicSkills.isEmpty(),
                "Page thẩm mỹ viện (negative control) phải có 0 skill Nàng Sen trong snapshot");

        var threeStream = new AdaptiveConversationIntelligence.ThreeStreamTurnInput(
                "Tư vấn dịch vụ chăm sóc da giúp mình",
                List.of("Tư vấn dịch vụ chăm sóc da giúp mình"),
                "tư vấn dịch vụ chăm sóc da giúp mình",
                List.of()
        );
        var draft2 = AdaptiveConversationIntelligence.CustomerUnderstandingDraft2Snapshot.initial();
        var snap = AdaptiveConversationIntelligence.buildTurnContextSnapshot(
                "T1", "Tư vấn dịch vụ chăm sóc da giúp mình", "chị", false, 0,
                List.of(), draft2, ConversationState.StructuredBookingState.none()
        );

        String beautyClinicPrompt = CleanAgentCatalogAndPromptBuilder.buildAciSinglePassPrompt(
                null, "chị", null, "chị", threeStream,
                List.of(), List.of(), CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                List.of(), null,
                List.of(), ConversationState.StructuredBookingState.none(),
                null,
                null,
                List.of(), null, false, List.of(), List.of(),
                beautyClinicSkills, List.of(), draft2, snap.plan()
        );

        assertFalse(beautyClinicPrompt.contains("Nàng Sen"),
                "Prompt của Page thẩm mỹ viện tuyệt đối không được rò rỉ tên thương hiệu hoặc skill Nàng Sen");
        assertFalse(beautyClinicPrompt.contains("discovery_empathy_and_scope"),
                "Prompt của Page thẩm mỹ viện không được chứa bất kỳ skill_code nào của Nàng Sen");
        assertFalse(beautyClinicPrompt.contains("package_comparison_and_scope_clarity"),
                "Prompt của Page thẩm mỹ viện không được chứa skill mới của Nàng Sen");
    }
}
