package com.example.docmind.service.playbook;

import com.example.docmind.dto.LearnedRuleDto;
import com.example.docmind.dto.SampleDialogueDto;
import com.example.docmind.entity.LearnedRuleEntity;
import com.example.docmind.entity.SampleDialogueEntity;
import com.example.docmind.repository.LearnedRuleRepository;
import com.example.docmind.repository.SampleDialogueRepository;
import com.example.docmind.service.embedding.EmbeddingClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PlaybookUploadServiceTest {

    private LearnedRuleRepository learnedRuleRepository;
    private SampleDialogueRepository sampleDialogueRepository;
    private EmbeddingClient embeddingClient;
    private PlaybookUploadService playbookUploadService;

    private final List<LearnedRuleEntity> savedRules = new ArrayList<>();
    private final List<SampleDialogueEntity> savedDialogues = new ArrayList<>();

    @BeforeEach
    void setUp() {
        learnedRuleRepository = mock(LearnedRuleRepository.class);
        sampleDialogueRepository = mock(SampleDialogueRepository.class);
        embeddingClient = mock(EmbeddingClient.class);

        when(embeddingClient.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f, 0.3f});

        when(learnedRuleRepository.findFirstByRuleCodeAndPageId(anyString(), any(UUID.class)))
                .thenReturn(Optional.empty());
        when(learnedRuleRepository.findFirstByRuleCodeAndPageIdIsNull(anyString()))
                .thenReturn(Optional.empty());
        when(learnedRuleRepository.save(any(LearnedRuleEntity.class))).thenAnswer(inv -> {
            LearnedRuleEntity entity = inv.getArgument(0);
            savedRules.removeIf(r -> r.getRuleCode() != null && r.getRuleCode().equals(entity.getRuleCode()));
            savedRules.add(entity);
            return entity;
        });

        when(sampleDialogueRepository.findFirstByDialogueCodeAndPageId(anyString(), any(UUID.class)))
                .thenReturn(Optional.empty());
        when(sampleDialogueRepository.findFirstByDialogueCodeAndPageIdIsNull(anyString()))
                .thenReturn(Optional.empty());
        when(sampleDialogueRepository.save(any(SampleDialogueEntity.class))).thenAnswer(inv -> {
            SampleDialogueEntity entity = inv.getArgument(0);
            savedDialogues.removeIf(d -> d.getDialogueCode() != null && d.getDialogueCode().equals(entity.getDialogueCode()));
            savedDialogues.add(entity);
            return entity;
        });
        when(sampleDialogueRepository.findActiveApprovedForPage(any())).thenAnswer(inv ->
                savedDialogues.stream()
                        .filter(d -> d.isActive() && "APPROVED".equalsIgnoreCase(d.getReviewStatus()))
                        .toList()
        );

        playbookUploadService = new PlaybookUploadService(
                learnedRuleRepository,
                sampleDialogueRepository,
                embeddingClient,
                new ObjectMapper()
        );
    }

    @Test
    void uploadYamlPlaybook_autoActivatesValidItemsAndQuarantinesNegativeSamples() throws Exception {
        Path yamlPath = Path.of("../docs/playbooks/nang_sen_spa_playbook.yaml");
        assertTrue(Files.exists(yamlPath), "nang_sen_spa_playbook.yaml must exist");
        String yamlContent = Files.readString(yamlPath);
        String pageId = "b23cd382-4f7a-4829-8c54-1d410da14bf8";

        PlaybookUploadService.PlaybookUploadResult result = playbookUploadService.uploadPlaybookContent(
                yamlContent, "nang_sen_spa_playbook.yaml", pageId
        );

        assertEquals("YAML", result.format());
        assertEquals(pageId, result.pageId());
        assertEquals(14, result.totalPlaybookRulesParsed());
        assertEquals(14, result.activePlaybookRulesSaved());
        assertEquals(0, result.rejectedPlaybookRulesCount());

        // All 14 PB-SPA-* rules are immediately ACTIVE + APPROVED (skipping manual approval step)
        for (LearnedRuleDto rule : result.activeRules()) {
            assertTrue(rule.active(), "Rule " + rule.ruleCode() + " must be immediately ACTIVE");
            assertEquals("APPROVED", rule.reviewStatus());
            assertNotNull(rule.ruleCode());
            assertTrue(rule.ruleCode().startsWith("PB-SPA-"));
        }

        // 11 dialogues total: 9 valid FD-SPA-001..009 -> ACTIVE + APPROVED; 2 negative FD-SPA-BAD-001..002 -> REJECTED + inactive
        assertEquals(11, result.totalSampleDialoguesParsed());
        assertEquals(9, result.activeSampleDialoguesSaved());
        assertEquals(2, result.rejectedSampleDialoguesCount());

        for (SampleDialogueDto d : result.activeDialogues()) {
            assertTrue(d.active());
            assertEquals("APPROVED", d.reviewStatus());
            assertFalse(d.dialogueCode().contains("BAD"));
        }

        List<String> rejectedCodes = result.rejectedDialogues().stream()
                .map(SampleDialogueDto::dialogueCode)
                .toList();
        assertTrue(rejectedCodes.contains("FD-SPA-BAD-001"));
        assertTrue(rejectedCodes.contains("FD-SPA-BAD-002"));
        for (SampleDialogueDto bad : result.rejectedDialogues()) {
            assertFalse(bad.active());
            assertEquals("REJECTED", bad.reviewStatus());
            assertNotNull(bad.quarantinedReason());
        }
    }

    @Test
    void uploadMarkdownPlaybook_parsesRulesAndDialoguesAndEnforcesImmediateActiveStatus() throws Exception {
        Path mdPath = Path.of("../docs/playbooks/nang_sen_spa_playbook.md");
        assertTrue(Files.exists(mdPath), "nang_sen_spa_playbook.md must exist");
        String mdContent = Files.readString(mdPath);
        String pageId = "b23cd382-4f7a-4829-8c54-1d410da14bf8";

        PlaybookUploadService.PlaybookUploadResult result = playbookUploadService.uploadPlaybookContent(
                mdContent, "nang_sen_spa_playbook.md", pageId
        );

        assertEquals("MARKDOWN", result.format());
        assertEquals(14, result.totalPlaybookRulesParsed());
        assertEquals(14, result.activePlaybookRulesSaved());
        assertEquals(0, result.rejectedPlaybookRulesCount());

        assertEquals(11, result.totalSampleDialoguesParsed());
        assertEquals(9, result.activeSampleDialoguesSaved());
        assertEquals(2, result.rejectedSampleDialoguesCount());
    }

    @Test
    void uploadJsonPlaybook_parsesRulesAndDialoguesAndRetrievesRelevantDialoguesByIntent() throws Exception {
        Path jsonPath = Path.of("../docs/playbooks/nang_sen_spa_playbook.json");
        assertTrue(Files.exists(jsonPath), "nang_sen_spa_playbook.json must exist");
        String jsonContent = Files.readString(jsonPath);
        UUID pageUuid = UUID.fromString("b23cd382-4f7a-4829-8c54-1d410da14bf8");

        PlaybookUploadService.PlaybookUploadResult result = playbookUploadService.uploadPlaybookContent(
                jsonContent, "nang_sen_spa_playbook.json", pageUuid.toString()
        );

        assertEquals("JSON", result.format());
        assertEquals(14, result.activePlaybookRulesSaved());
        assertEquals(9, result.activeSampleDialoguesSaved());
        assertEquals(2, result.rejectedSampleDialoguesCount());

        // Verify retrieval for pregnancy safety query returns FD-SPA-SAFETY-001 (or FD-SPA-005) and never returns FD-SPA-BAD-*
        List<SampleDialogueDto> pregnancyHits = playbookUploadService.retrieveRelevantDialogues(
                "Chị đang mang bầu 5 tháng có làm trị liệu cổ vai gáy được không?",
                pageUuid,
                2
        );
        assertFalse(pregnancyHits.isEmpty());
        assertTrue(Set.of("FD-SPA-SAFETY-001", "FD-SPA-005").contains(pregnancyHits.get(0).dialogueCode()));
        assertTrue(pregnancyHits.stream().noneMatch(d -> d.dialogueCode().contains("BAD")));

        // Verify retrieval for price objection returns FD-SPA-PRICE-001 (or FD-SPA-006)
        List<SampleDialogueDto> priceHits = playbookUploadService.retrieveRelevantDialogues(
                "Giá 580k hơi cao em ơi, có gói nào mềm hơn không?",
                pageUuid,
                2
        );
        assertFalse(priceHits.isEmpty());
        assertTrue(Set.of("FD-SPA-PRICE-001", "FD-SPA-006").contains(priceHits.get(0).dialogueCode()));
    }

    @Test
    void allThreePlaybookFormats_containOnlyGroundedNangSenKbPricesAndZeroHallucinatedPrices() throws Exception {
        List<Path> files = List.of(
                Path.of("../docs/playbooks/nang_sen_spa_playbook.yaml"),
                Path.of("../docs/playbooks/nang_sen_spa_playbook.md"),
                Path.of("../docs/playbooks/nang_sen_spa_playbook.json")
        );
        for (Path path : files) {
            String raw = Files.readString(path);
            // Grounded KB prices must be present
            assertTrue(raw.contains("129.000đ"), path.getFileName() + " must contain KB price 129.000đ (Lấy ráy tai 30p)");
            assertTrue(raw.contains("239.000đ"), path.getFileName() + " must contain KB price 239.000đ (Gội đầu dưỡng sinh 60p)");
            assertTrue(raw.contains("399.000đ"), path.getFileName() + " must contain KB price 399.000đ (Massage NS01 70p)");
            assertTrue(raw.contains("530.000đ"), path.getFileName() + " must contain KB price 530.000đ (Massage NS02 80p)");
            assertTrue(raw.contains("580.000đ"), path.getFileName() + " must contain KB price 580.000đ (Massage VIP NS03 90-100p)");
            assertTrue(raw.contains("670.000đ"), path.getFileName() + " must contain KB price 670.000đ (Combo 120p)");
            assertTrue(raw.contains("780.000đ"), path.getFileName() + " must contain KB price 780.000đ (Silver VIP 120p)");
            assertTrue(raw.contains("10%"), path.getFileName() + " must contain KB promo 10% (5 buổi/voucher)");

            // Hallucinated prices from prior draft must be completely absent
            assertFalse(raw.contains("450.000đ"), path.getFileName() + " must NOT contain ungrounded price 450.000đ");
            assertFalse(raw.contains("620.000đ"), path.getFileName() + " must NOT contain ungrounded price 620.000đ");
            assertFalse(raw.contains("220.000đ"), path.getFileName() + " must NOT contain ungrounded price 220.000đ");
            assertFalse(raw.contains("820.000đ"), path.getFileName() + " must NOT contain ungrounded price 820.000đ");
            assertFalse(raw.contains("giảm 12%"), path.getFileName() + " must NOT contain ungrounded discount 12%");
            assertFalse(raw.contains("giảm 20%"), path.getFileName() + " must NOT contain ungrounded discount 20%");
        }
    }

    @Test
    void uploadMarkdownPlaybook_preservesMultipleUserAndAssistantTurns() {
        String multiTurnMd = """
                ## PHẦN 2: SAMPLE DIALOGUES
                ### [FD-SPA-MULTI-001] Tư vấn nhiều lượt
                - **intent**: `service_consultation`
                - **stage**: `consulting`
                - **tags**: `massage, multi_turn`
                - **user**: Chị bị mỏi cổ vai gáy nhiều em ơi.
                - **assistant**: Dạ chị mỏi lâu chưa và thích lực ấn đằm tay hay nhẹ nhàng ạ?
                - **user**: Chị mỏi cả tuần nay rồi, thích ấn đằm tay.
                - **assistant**: Dạ vậy chị chọn gói Massage trị liệu cơ bản NS01 70 phút (399.000đ) là hợp nhất ạ.
                - **why_good**: Khai thác nhu cầu 2 lượt trước khi chốt gói NS01 399.000đ.
                """;
        PlaybookUploadService.PlaybookUploadResult res = playbookUploadService.uploadPlaybookContent(
                multiTurnMd, "multi.md", "b23cd382-4f7a-4829-8c54-1d410da14bf8"
        );
        assertEquals(1, res.activeSampleDialoguesSaved());
        SampleDialogueDto dto = res.activeDialogues().get(0);
        assertTrue(dto.userSummary().contains("Chị bị mỏi cổ vai gáy nhiều em ơi."));
        assertTrue(dto.userSummary().contains("Chị mỏi cả tuần nay rồi, thích ấn đằm tay."));
        assertTrue(dto.expectedBotAnswer().contains("Dạ chị mỏi lâu chưa"));
        assertTrue(dto.expectedBotAnswer().contains("399.000đ"));
    }

    @Test
    void uploadSpaRuntimePlaybookV2Markdown_replacesExistingAndParsesAll7RulesAnd10MultiBubbleDialogues() throws Exception {
        String v2Md = Files.readString(Path.of("../docs/playbooks/SPA_RUNTIME_PLAYBOOK_V2.md"));
        PlaybookUploadService.PlaybookUploadResult result = playbookUploadService.uploadPlaybookContent(
                v2Md,
                "SPA_RUNTIME_PLAYBOOK_V2.md",
                "b23cd382-4f7a-4829-8c54-1d410da14bf8",
                true
        );

        assertEquals("MARKDOWN", result.format());
        assertEquals(7, result.totalPlaybookRulesParsed());
        assertEquals(7, result.activePlaybookRulesSaved());
        assertEquals(0, result.rejectedPlaybookRulesCount());
        assertEquals(10, result.totalSampleDialoguesParsed());
        assertEquals(10, result.activeSampleDialoguesSaved());
        assertEquals(0, result.rejectedSampleDialoguesCount());

        SampleDialogueDto priceDialogue = result.activeDialogues().stream()
                .filter(d -> "FD-SPA-V2-003".equals(d.dialogueCode()))
                .findFirst()
                .orElseThrow();
        assertTrue(priceDialogue.expectedBotAnswer().contains("350k cho 60 phút và 480k cho 90 phút"));
        assertTrue(priceDialogue.expectedBotAnswer().contains("Liệu trình tập trung thả lỏng cơ lưng"));
    }

    @Test
    void uploadUnifiedV301PlaybookWithReplaceExisting_activatesSafeNeuro001AndAllV301RulesAndQuarantinesBadSamples() throws Exception {
        String yamlContent = Files.readString(Path.of("../docs/playbooks/nang_sen_spa_playbook.yaml"));
        String jsonContent = Files.readString(Path.of("../docs/playbooks/nang_sen_spa_playbook.json"));
        assertFalse(jsonContent.contains("recommended_ollama_model"), "nang_sen_spa_playbook.json must not hardcode recommended_ollama_model");
        assertTrue(yamlContent.contains("SPA-PLAYBOOK-NANG-SEN-V3-0-1-UNIFIED"));
        assertTrue(jsonContent.contains("SPA-PLAYBOOK-NANG-SEN-V3-0-1-UNIFIED"));

        PlaybookUploadService.PlaybookUploadResult result = playbookUploadService.uploadPlaybookContent(
                yamlContent,
                "nang_sen_spa_playbook.yaml",
                "b23cd382-4f7a-4829-8c54-1d410da14bf8",
                true
        );

        assertEquals(15, result.activePlaybookRulesSaved());
        Set<String> activeRuleCodes = result.activeRules().stream()
                .filter(r -> r.active() && "APPROVED".equals(r.reviewStatus()))
                .map(LearnedRuleDto::ruleCode)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(activeRuleCodes.containsAll(Set.of(
                "SAFE-NEURO-001",
                "PB-SPA-OPEN-001",
                "PB-SPA-DISCOVER-001",
                "PB-SPA-MATCH-001",
                "PB-SPA-PRICE-ANCHOR-001",
                "PB-SPA-PRICE-OBJECTION-001",
                "PB-SPA-PRICE-OBJECTION-002",
                "PB-SPA-TRIAL-ACCEPT-001",
                "PB-SPA-CORRECTION-001",
                "PB-SPA-EMOTIONAL-REPAIR-001",
                "PB-SPA-MID-FUNNEL-LUKEWARM-001"
        )));

        for (SampleDialogueDto good : result.activeDialogues()) {
            assertTrue(good.active());
            assertEquals("APPROVED", good.reviewStatus());
            assertEquals("APPROVED_GOOD", good.qualityLabel());
        }
        for (SampleDialogueDto bad : result.rejectedDialogues()) {
            assertFalse(bad.active());
            assertEquals("REJECTED", bad.reviewStatus());
            assertEquals("QUARANTINED_BAD", bad.qualityLabel());
        }
    }
}
