package com.example.docmind.service.bot;

import com.example.docmind.config.ChatProperties;
import com.example.docmind.dto.LearnedRuleDto;
import com.example.docmind.dto.PagePersonaConfig;
import com.example.docmind.dto.bot.AgentSkillDto;
import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.dto.bot.FanpageBotResponse;
import com.example.docmind.entity.KnowledgePageEntity;
import com.example.docmind.repository.DocumentRepository;
import com.example.docmind.repository.KnowledgePageRepository;
import com.example.docmind.repository.RetrievedChunk;
import com.example.docmind.service.llm.LlmClient;
import com.example.docmind.service.llm.ReasoningBudget;
import com.example.docmind.service.retrieval.PromptBuilder;
import com.example.docmind.service.retrieval.ReciprocalRankFusion;
import com.example.docmind.service.retrieval.RetrievalService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Cổng nghiệm thu C2-C:
 * 1. Nối thông đầy đủ persona_config (bot_name, tone_style, care_actor, closing_action, forbidden_words, active_prompt_compact)
 *    + nhãn hiển thị Catalog động theo dữ liệu thực tế (không gắn cứng "Thời lượng:", "Giá trọn gói:", "Vùng chăm sóc:").
 * 2. Không suy diễn mù quáng commercialRole = ECONOMICAL_OPTION cho sản phẩm ngang hàng ở ngành khác.
 * 3. Thứ tự ưu tiên bất biến (Hierarchy of Truth) cho agent_skills.tactical_instructions và learned_rules:
 *    chặn cross-page, chặn giá cũ lệch Catalog, chặn rule hạ bậc ở Stage 2 và chặn ghi đè reply_to.mid.
 * 4. Kiểm chứng nguồn gốc Brain Provenance đa ngành (giữ thông tin khách nhắn như 80x80cm / phòng khách / IELTS 6.5,
 *    cắt bỏ câu quảng cáo của bot và suy đoán chưa xác nhận).
 * 5. Cổng an toàn theo phạm vi ngành: "vợ anh đang bầu muốn chọn gạch chống trơn" trên Page gạch/nội thất
 *    không bị chặn nhầm bởi cổng y khoa Spa, trong khi Page Spa và safety_red_flags tùy chỉnh vẫn chặn chuẩn xác.
 */
class MultiIndustryPersonaRulesAndSafetyC2CTest {

    private static ReciprocalRankFusion.FusedChunk chunk(String heading, String text) {
        RetrievedChunk rc = new RetrievedChunk(
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(heading),
                List.of(UUID.randomUUID()),
                1,
                new double[]{0, 0, 100, 100},
                text,
                0.95
        );
        return new ReciprocalRankFusion.FusedChunk(rc, 0.90);
    }

    private static LearnedRuleDto buildRule(
            String ruleCode,
            String pageId,
            String stage,
            String generalizedRule,
            List<String> keywords,
            boolean alwaysOn,
            int priority
    ) {
        return new LearnedRuleDto(
                UUID.randomUUID(),
                "PLAYBOOK",
                "",
                "",
                generalizedRule,
                keywords,
                true,
                pageId,
                0.92,
                1,
                1,
                0,
                1,
                null,
                null,
                Instant.now(),
                Instant.now(),
                Instant.now(),
                "DOM",
                null,
                null,
                alwaysOn,
                "APPROVED",
                null,
                ruleCode,
                ruleCode,
                null,
                stage,
                priority,
                null,
                null,
                "must_use_kb_document_context",
                "none",
                "MANUAL"
        );
    }

    private static AgentSkillDto buildSkill(
            String code,
            String pageId,
            String tacticalInstructions
    ) {
        return new AgentSkillDto(
                UUID.randomUUID(),
                code,
                code,
                "CONSULTATION",
                "",
                "",
                tacticalInstructions,
                "",
                "",
                90,
                true,
                pageId,
                0,
                0,
                0.92,
                Instant.now(),
                Instant.now()
        );
    }

    @Test
    @DisplayName("C2-C.1: Nối thông đủ 6 trường persona_config, nhãn Catalog động và không ép sản phẩm ngang hàng làm downsell")
    void testPersonaConfigDbFieldsAndDynamicCatalogLabels_NoHardcodedSpaLabelsOrPeerDownsell() {
        UUID tilePageId = UUID.randomUUID();
        PagePersonaConfig tileConfig = new PagePersonaConfig(
                "HOME_FURNITURE",
                "Kỹ Sư Minh Phát",
                "em",
                "anh",
                "Chuyên môn kỹ thuật rõ ràng, tư vấn đúng chuẩn bề mặt chống trơn",
                "LOW",
                List.of("gạch trôi nổi", "bao giá rẻ nhất"),
                List.of("gạch ốp lát", "porcelain", "chống trơn", "phòng khách"),
                "Mời anh nhận mẫu gạch thực tế tại công trình",
                null,
                List.of(),
                3000,
                null,
                null,
                null,
                "Luôn nhắc khách tính dư 5% hao hụt diện tích mặt sàn khi cắt gạch thi công.",
                List.of(),
                List.of(),
                Map.of(),
                Map.of(),
                "kỹ thuật viên thi công",
                List.of("phòng khách", "phòng tắm", "sân vườn"),
                List.of(),
                Map.of()
        );
        PromptBuilder.PagePersona tilePersona = new PromptBuilder.PagePersona(
                "Gạch Ốp Lát Minh Phát",
                "Vật liệu xây dựng & Gạch ốp lát cao cấp",
                "Chủ nhà, kiến trúc sư, nhà thầu xây dựng",
                "Trung thực thông số kỹ thuật và đơn giá niêm yết",
                tileConfig
        );

        String tileMarkdown = """
                | Mã SP | Tên sản phẩm | Kích thước | Đơn giá | Khu vực phù hợp | Quyền lợi đi kèm |
                | --- | --- | --- | --- | --- | --- |
                | GACH-6060 | Gạch Granite men Matt chống trơn | 60x60cm | 320k/m² | Phòng khách, Phòng tắm | Hỗ trợ cắt gạch chuẩn kích thước |
                | GACH-8080 | Gạch Porcelain bóng kiếng kháng bẩn | 80x80cm | 450k/m² | Phòng khách | Tặng keo chà mạch chống thấm |
                """;
        List<PackageCatalogService.PackageSummary> tileCatalog = PackageCatalogService.extractCatalog(
                List.of(chunk("Bảng giá gạch ốp lát", tileMarkdown)),
                List.of()
        );
        assertEquals(2, tileCatalog.size());
        assertTrue(tileCatalog.stream().allMatch(p -> p.commercialRole() == PackageCatalogService.CommercialRole.STANDARD),
                "Hai mẫu gạch khác kích thước là sản phẩm ngang hàng (STANDARD), không được gán ECONOMICAL_OPTION");

        CleanAgentCatalogAndPromptBuilder.TieredCatalogResult tiered = CleanAgentCatalogAndPromptBuilder.rankAndTierCatalogForCleanAgent(
                tileCatalog,
                List.of("lát nền chống trơn"),
                List.of("phòng khách"),
                Set.of(),
                false
        );
        assertEquals(2, tiered.primaryRecommendations().size());
        assertTrue(tiered.economicalAlternatives().isEmpty(),
                "Danh mục sản phẩm ngang hàng không có cấu hình downsell phải giữ economicalAlternatives rỗng");

        String prompt = CleanAgentCatalogAndPromptBuilder.buildCleanAgentPrompt(
                tilePersona,
                "anh",
                List.of("lát nền chống trơn"),
                List.of("phòng khách"),
                "công trình sắp hoàn thiện",
                true,
                "null",
                "null",
                0,
                false,
                "NONE",
                List.of("NEW_SYMPTOM_OR_CONTEXT_SHARED"),
                "không có",
                1,
                tiered,
                List.of(),
                List.of(),
                "Tư vấn mẫu gạch lát phòng khách giúp anh",
                null,
                List.of(),
                Map.of(),
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                tilePageId,
                List.of()
        );

        // Xác nhận đủ 6 trường cấu hình từ PagePersonaConfig đi tới prompt
        assertTrue(prompt.contains("Bạn là Kỹ Sư Minh Phát"), "Phải đọc đúng bot_name từ PagePersonaConfig");
        assertTrue(prompt.contains("Chuyên môn kỹ thuật rõ ràng, tư vấn đúng chuẩn bề mặt chống trơn"),
                "Phải đọc đúng tone_style từ PagePersonaConfig kể cả khi Page có description");
        assertTrue(prompt.contains("kỹ thuật viên thi công"), "Phải đọc đúng care_actor từ PagePersonaConfig");
        assertTrue(prompt.contains("Mời anh nhận mẫu gạch thực tế tại công trình"),
                "Phải đọc đúng closing_action từ PagePersonaConfig");
        assertTrue(prompt.contains("gạch trôi nổi") && prompt.contains("bao giá rẻ nhất"),
                "Phải đọc đúng forbidden_words từ PagePersonaConfig");
        assertTrue(prompt.contains("Luôn nhắc khách tính dư 5% hao hụt diện tích mặt sàn"),
                "Phải đọc đúng active_prompt_compact vào khối GOVERNED_TENANT_PLAYBOOK");

        // Xác nhận nhãn hiển thị Catalog động và không còn giá cứng Spa 239k/399k/530k
        String userSection = prompt.substring(prompt.indexOf("<!-- SYSTEM_USER_SPLIT -->"));
        assertTrue(userSection.contains("Kích thước: 60x60cm — Đơn giá: 320k/m² — Phạm vi phù hợp:"));
        assertTrue(userSection.contains("Kích thước: 80x80cm — Đơn giá: 450k/m² — Phạm vi phù hợp:"));
        assertFalse(userSection.contains("Thời lượng:"), "Sản phẩm gạch không được gắn nhãn cứng 'Thời lượng:'");
        assertFalse(userSection.contains("Giá trọn gói:"), "Đơn giá /m² không được gắn nhãn cứng 'Giá trọn gói:'");
        assertFalse(userSection.contains("Vùng chăm sóc:"), "Khu vực lát gạch không được gắn nhãn cứng 'Vùng chăm sóc:'");
        assertFalse(prompt.contains("239k") || prompt.contains("399k") || prompt.contains("530k"),
                "Template dùng chung không được rò rỉ các ví dụ giá cứng 239k/399k/530k");
    }

    @Test
    @DisplayName("C2-C.2: Hierarchy of Truth — Lọc đúng page_id, chặn giá cũ lệch Catalog, chặn rule hạ bậc ở Stage 2 và bảo vệ reply_to.mid")
    void testHierarchyOfTruth_TenantIsolation_NoStalePriceOrStageOrReplyOverride() {
        UUID pageA = UUID.randomUUID();
        UUID pageB = UUID.randomUUID();

        String eduMarkdown = """
                | Mã khóa | Tên khóa học | Số buổi | Học phí | Đối tượng | Hình thức |
                | --- | --- | --- | --- | --- | --- |
                | IELTS-24B | Khóa IELTS 6.5 Cam Kết Đầu Ra | 24 buổi | 3.500k/khóa | Mất gốc đến 6.5 | Online 1-1 |
                """;
        List<PackageCatalogService.PackageSummary> eduCatalog = PackageCatalogService.extractCatalog(
                List.of(chunk("Bảng học phí IELTS", eduMarkdown)),
                List.of()
        );
        CleanAgentCatalogAndPromptBuilder.TieredCatalogResult tiered = CleanAgentCatalogAndPromptBuilder.rankAndTierCatalogForCleanAgent(
                eduCatalog,
                List.of("luyện thi IELTS 6.5"),
                List.of("mất gốc đến 6.5"),
                Set.of(),
                false
        );

        PromptBuilder.PagePersona eduPersona = new PromptBuilder.PagePersona(
                "Học Viện IELTS Master",
                "Giáo dục & Đào tạo ngoại ngữ",
                "Học viên sinh viên và người đi làm",
                "Rõ ràng lộ trình học thuật"
        );

        AgentSkillDto validSamePageSkill = buildSkill(
                "edu_value_anchor",
                pageA.toString(),
                "Làm rõ lộ trình chấm chữa bài 1-1 hàng tuần trước khi mời kiểm tra đầu vào miễn phí."
        );
        AgentSkillDto crossTenantSkill = buildSkill(
                "other_page_skill",
                pageB.toString(),
                "Luôn tặng thêm voucher gội đầu thảo dược miễn phí của Page B."
        );
        AgentSkillDto stage2ConflictingDownsellSkill = buildSkill(
                "stale_downsell_skill",
                pageA.toString(),
                "Chủ động gợi ý gói tiết kiệm rẻ hơn ngay ở lần chê đắt đầu tiên."
        );

        LearnedRuleDto validSamePageRule = buildRule(
                "PB-EDU-01",
                pageA.toString(),
                "STAGE_2",
                "Nhấn mạnh sĩ số 1 kèm 1 và bài thi thử chuẩn IDP hàng tháng khi học viên băn khoăn học phí.",
                List.of("học phí", "cao"),
                false,
                10
        );
        LearnedRuleDto crossTenantRule = buildRule(
                "PB-OTHER-99",
                pageB.toString(),
                "ALL",
                "Cam kết miễn phí lắp đặt nội thất toàn miền Bắc.",
                List.of(),
                true,
                5
        );
        LearnedRuleDto staleWrongPriceRule = buildRule(
                "PB-STALE-PRICE",
                pageA.toString(),
                "ALL",
                "Luôn báo khóa IELTS 6.5 đang giảm còn 1.990k cho mọi học viên hỏi giá.",
                List.of(),
                true,
                1
        );
        LearnedRuleDto stage2DownsellConflictRule = buildRule(
                "PB-STAGE2-DOWNSELL",
                pageA.toString(),
                "STAGE_2",
                "Khi khách chê đắt lần đầu hãy hạ bậc ngay sang gói rẻ hơn để giữ chân khách.",
                List.of("cao"),
                true,
                2
        );

        String stage2Prompt = CleanAgentCatalogAndPromptBuilder.buildCleanAgentPrompt(
                eduPersona,
                "bạn",
                List.of("luyện thi IELTS 6.5"),
                List.of("mất gốc đến 6.5"),
                "cần thi trong 3 tháng",
                true,
                "Khóa IELTS 6.5 Cam Kết Đầu Ra",
                "3.500k/khóa",
                1,
                false,
                "NONE",
                List.of("PRICE_OBJECTION", "PRICE_OBJECTION_FIRST_TIME"),
                "không có",
                1,
                tiered,
                List.of(),
                List.of(),
                "Học phí 3.500k hơi cao nhỉ",
                null,
                List.of(crossTenantSkill, stage2ConflictingDownsellSkill, validSamePageSkill),
                Map.of(),
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                pageA,
                List.of(crossTenantRule, staleWrongPriceRule, stage2DownsellConflictRule, validSamePageRule)
        );

        assertTrue(stage2Prompt.contains("<GOVERNED_TENANT_PLAYBOOK>"));
        assertTrue(stage2Prompt.contains("HIERARCHY OF TRUTH"));
        assertTrue(stage2Prompt.contains("[edu_value_anchor] Làm rõ lộ trình chấm chữa bài 1-1"),
                "Skill hợp lệ đúng page_id và đúng Stage phải được tiêm vào prompt");
        assertTrue(stage2Prompt.contains("[PB-EDU-01] Nhấn mạnh sĩ số 1 kèm 1"),
                "Learned rule hợp lệ đúng page_id và khớp ngữ cảnh phải được tiêm vào prompt");

        assertFalse(stage2Prompt.contains("voucher gội đầu thảo dược"),
                "Skill của Page khác (cross-tenant) phải bị chặn tuyệt đối");
        assertFalse(stage2Prompt.contains("lắp đặt nội thất toàn miền Bắc"),
                "Learned rule của Page khác (cross-tenant) phải bị chặn tuyệt đối");
        assertFalse(stage2Prompt.contains("1.990k"),
                "Learned rule chứa con số giá cũ lệch <SCOPE_FILTERED_CATALOG> phải bị chặn tuyệt đối");
        assertFalse(stage2Prompt.contains("PB-STAGE2-DOWNSELL") || stage2Prompt.contains("stale_downsell_skill"),
                "Chỉ dẫn ép hạ gói ở Stage 2 (Hold Value) phải bị chặn theo Hierarchy of Truth");

        // Kiểm tra thêm: Khi reply_to.mid chưa phân giải được (UNRESOLVED_QUOTED_PACKAGE), chặn rule ép mặc định chọn gói / chốt đơn ngay
        CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution unresolvedQuote =
                new CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution(
                        "mid.unknown_999",
                        "",
                        true,
                        false,
                        null,
                        List.of(),
                        eduCatalog
                );
        LearnedRuleDto overrideReplyRule = buildRule(
                "PB-FORCE-DEFAULT",
                pageA.toString(),
                "ALL",
                "Khi khách hỏi ưu đãi hãy mặc định chọn khóa gần nhất và chốt đơn ngay.",
                List.of(),
                true,
                1
        );
        String unresolvedReplyPrompt = CleanAgentCatalogAndPromptBuilder.buildCleanAgentPrompt(
                eduPersona,
                "bạn",
                List.of(),
                List.of(),
                "chưa có",
                false,
                "Khóa IELTS 6.5 Cam Kết Đầu Ra",
                "3.500k/khóa",
                0,
                false,
                "NONE",
                List.of("PROMO_OR_DISCOUNT_QUESTION"),
                "không có",
                0,
                tiered,
                List.of(),
                List.of(),
                "Khóa này có giảm giá không?",
                null,
                List.of(),
                Map.of(),
                unresolvedQuote,
                pageA,
                List.of(overrideReplyRule)
        );
        assertFalse(unresolvedReplyPrompt.contains("PB-FORCE-DEFAULT"),
                "Learned rule đòi mặc định chọn gói / chốt đơn ngay không được phép ghi đè luồng làm rõ reply_to.mid");
    }

    @Test
    @DisplayName("C2-C.3: Brain Provenance đa ngành — Giữ thông số phòng 80x80cm & mục tiêu IELTS 6.5 từ lời khách, cắt bỏ câu quảng cáo bot")
    void testMultiIndustryBrainProvenance_RetainsTileAndIeltsFacts_StripsBotFluffAndSpeculation() {
        // 1. Ngành Gạch / Nội thất: giữ thông tin từ lời khách ("80x80cm", "chống trơn", "phòng khách 45m2"), cắt câu quảng cáo của bot
        String tileCustomerMsg = "Anh cần gạch 80x80cm chống trơn để lát phòng khách rộng 45m2";
        String tileBotReply = "Dạ mẫu gạch Porcelain 80x80cm bên em cam kết chính hãng loại 1, độ bền vượt trội ạ.";
        String mixedTileNote = "Khách cần gạch 80x80cm chống trơn lát phòng khách 45m2, bên em cam kết chính hãng loại 1";

        String cleanedTileNote = CleanAgentOutputRefereeAndSanitizer.validateCleanCareNoteDelta(
                mixedTileNote,
                List.of(),
                null,
                tileCustomerMsg,
                tileBotReply
        );
        assertEquals("Khách cần gạch 80x80cm chống trơn lát phòng khách 45m2", cleanedTileNote,
                "Phải giữ lại ghi chú diện tích/quy cách gạch có căn cứ từ lời khách và cắt bỏ vế quảng cáo của bot");

        // 2. Ngành Giáo dục: giữ mục tiêu học tập ("IELTS 6.5", "3 tháng", "buổi tối") vào AgentBrainService kèm đúng careActor
        AgentBrainService brainService = new AgentBrainService();
        UUID eduPageId = UUID.randomUUID();
        String senderId = "student_ielts_01";
        brainService.clearCustomerMemory(eduPageId, senderId);

        String eduCustomerMsg = "Em đặt mục tiêu IELTS 6.5 trong 3 tháng và chỉ học được các buổi tối";
        String eduBotReply = "Dạ khóa IELTS 6.5 bên em có lộ trình chuẩn quốc tế và cam kết đầu ra ạ.";
        brainService.retainTurnWithReflection(
                eduPageId,
                senderId,
                "Bạn Minh",
                eduCustomerMsg,
                eduBotReply,
                List.of(),
                "slot_goal=Mục tiêu IELTS 6.5 trong 3 tháng",
                "Mục tiêu IELTS 6.5 trong 3 tháng, chỉ học được buổi tối, lộ trình chuẩn quốc tế",
                "Học viên cần lớp buổi tối linh hoạt",
                "OFFERED_PACKAGE_DETAILS",
                List.of("ielts_6_5"),
                "giảng viên học thuật"
        );

        List<String> recalledNotes = brainService.getRecalledCareNotes(eduPageId, senderId);
        assertEquals(1, recalledNotes.size(), "Ghi chú mục tiêu IELTS 6.5 từ lời khách phải được lưu thành công");
        assertEquals("Mục tiêu IELTS 6.5 trong 3 tháng, chỉ học được buổi tối", recalledNotes.get(0));
        assertEquals("giảng viên học thuật", brainService.getLastRetainedCareNotes().get(0).careActor(),
                "Artifact care note phải gắn đúng care_actor của Page thay vì cố định 'chuyên viên chăm sóc'");

        // 3. Chặn hoàn toàn câu thuần quảng cáo của bot, suy đoán chưa xác nhận, hoặc bịa đặt không có nguồn gốc từ lời khách
        assertEquals("", CleanAgentOutputRefereeAndSanitizer.validateCleanCareNoteDelta(
                "Bên em cam kết chính hãng loại 1, độ bền vượt trội, giao hàng nhanh toàn quốc",
                tileCustomerMsg,
                tileBotReply
        ), "Câu thuần quảng cáo của bot phải bị loại bỏ hoàn toàn");

        assertEquals("", CleanAgentOutputRefereeAndSanitizer.validateCleanCareNoteDelta(
                "Có thể khách đang đi thuê nhà hoặc chưa đủ ngân sách hoàn thiện",
                tileCustomerMsg,
                tileBotReply
        ), "Suy đoán chưa xác nhận về khách hàng phải bị loại bỏ hoàn toàn");

        assertEquals("", CleanAgentOutputRefereeAndSanitizer.validateCleanCareNoteDelta(
                "Khách dự định xây biệt thự nghỉ dưỡng 4 tầng tại Đà Lạt",
                tileCustomerMsg,
                tileBotReply
        ), "Thông tin không có bất kỳ căn cứ từ vựng nào trong lời khách nhắn phải bị từ chối");
    }

    @Test
    @DisplayName("C2-C.4: Cổng an toàn theo ngành — 'vợ anh đang bầu muốn chọn gạch chống trơn' KHÔNG bị chặn nhầm, Spa & safety_red_flags vẫn chặn chuẩn")
    void testDomainScopedSafetyGate_TilePagePregnancyAllowed_SpaPagePregnancyHandoff_CustomRedFlagsEnforced() {
        RetrievalService retrievalService = mock(RetrievalService.class);
        LlmClient llmClient = mock(LlmClient.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        KnowledgePageRepository knowledgePageRepository = mock(KnowledgePageRepository.class);
        when(llmClient.name()).thenReturn("qwen3.8-flash");

        UUID tilePageId = UUID.randomUUID();
        UUID spaPageId = UUID.randomUUID();
        UUID equipmentPageId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();

        // 1. Cấu hình Page Gạch ốp lát (HOME_FURNITURE)
        KnowledgePageEntity tilePage = KnowledgePageEntity.builder()
                .id(tilePageId)
                .name("Gạch Ốp Lát Minh Phát")
                .industry("Vật liệu xây dựng & Gạch ốp lát")
                .targetAudience("Gia đình, chủ nhà, nhà thầu")
                .description("Chuyên gạch lát nền chống trơn")
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
        tilePage.setPersonaConfig(new PagePersonaConfig(
                "HOME_FURNITURE",
                "Tư vấn viên Minh Phát",
                "em",
                "anh",
                "Rõ ràng kỹ thuật, tận tâm",
                "LOW",
                List.of(),
                List.of("gạch chống trơn", "porcelain", "phòng tắm", "phòng khách")
        ));
        when(knowledgePageRepository.findById(tilePageId)).thenReturn(Optional.of(tilePage));

        // 2. Cấu hình Page Spa (BEAUTY_SPA)
        KnowledgePageEntity spaPage = KnowledgePageEntity.builder()
                .id(spaPageId)
                .name("Nàng Sen Spa")
                .industry("Spa dưỡng sinh trị liệu")
                .targetAudience("Khách thư giãn chăm sóc cơ thể")
                .description("Tinh tế, an toàn")
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
        spaPage.setPersonaConfig(new PagePersonaConfig(
                "BEAUTY_SPA",
                "Lễ tân Nàng Sen",
                "em",
                "chị",
                "Dịu dàng, chu đáo",
                "LOW",
                List.of(),
                List.of("massage", "dưỡng sinh")
        ));
        when(knowledgePageRepository.findById(spaPageId)).thenReturn(Optional.of(spaPage));

        // 3. Cấu hình Page Thiết bị điện nước có safety_red_flags tùy chỉnh ("rò rỉ điện")
        KnowledgePageEntity equipmentPage = KnowledgePageEntity.builder()
                .id(equipmentPageId)
                .name("Thiết Bị Gia Dụng An Tâm")
                .industry("Điện gia dụng")
                .targetAudience("Hộ gia đình")
                .description("An toàn kỹ thuật")
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
        equipmentPage.setPersonaConfig(new PagePersonaConfig(
                "HOME_FURNITURE",
                "Kỹ thuật viên An Tâm",
                "em",
                "anh",
                "Chính xác, an toàn",
                "NONE",
                List.of(),
                List.of("bình nóng lạnh"),
                null,
                null,
                List.of(),
                2000,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                Map.of(),
                Map.of(),
                "kỹ thuật viên điện",
                List.of("phòng tắm"),
                List.of("rò rỉ điện", "chập cháy"),
                Map.of()
        ));
        when(knowledgePageRepository.findById(equipmentPageId)).thenReturn(Optional.of(equipmentPage));

        String tileCatalogMd = """
                | Mã SP | Tên sản phẩm | Kích thước | Đơn giá | Khu vực phù hợp |
                | --- | --- | --- | --- | --- |
                | GACH-6060M | Gạch Granite men Matt chống trơn R11 | 60x60cm | 320k/m² | Phòng tắm, Hành lang |
                """;
        when(retrievalService.retrieveWithRules(anyList(), anyString(), anyInt(), anyList(), any()))
                .thenReturn(new RetrievalService.RetrievalResult(List.of(chunk("Bảng giá gạch chống trơn", tileCatalogMd)), List.of()));

        String llmTileReplyJson = """
                {
                  "reply_bubbles": [
                    {"text": "Dạ để đi lại an toàn chống trơn trượt, em gợi ý anh tham khảo mẫu Gạch Granite men Matt chống trơn R11 kích thước 60x60cm với đơn giá 320k/m² ạ.", "typing_delay_ms": 0},
                    {"text": "Không biết anh đang cần lát cho khu vực phòng tắm hay sàn phòng khách khoảng bao nhiêu m² để em tính số lượng chuẩn cho mình ạ?", "typing_delay_ms": 1500}
                  ],
                  "brain_reflection": {
                    "observed_fact": "Vợ khách đang bầu, khách muốn chọn gạch chống trơn",
                    "care_note_delta": "Gia đình có bà bầu, ưu tiên gạch chống trơn",
                    "working_hypothesis": "Khách ưu tiên độ nhám chống trượt an toàn",
                    "intended_speech_act": "OFFERED_PACKAGE_DETAILS",
                    "detected_customer_intent": "SYMPTOM_OR_CONTEXT_SHARING"
                  },
                  "should_handoff": false
                }
                """;
        when(llmClient.name()).thenReturn("ollama:qwen3.8-flash");
        when(llmClient.complete(anyString(), any(ReasoningBudget.class))).thenReturn(llmTileReplyJson);
        when(llmClient.complete(anyString())).thenReturn(llmTileReplyJson);

        AgentBrainService brainService = new AgentBrainService();
        FanpageBotService service = new FanpageBotService(
                retrievalService,
                llmClient,
                new ChatProperties(),
                documentRepository,
                knowledgePageRepository,
                new CustomerInsightEngine(),
                null,
                null
        );
        service.setAgentBrainService(brainService);

        // CASE 1: Trên Page Gạch (HOME_FURNITURE) qua luồng CleanAgentMode thực tế, câu "vợ anh đang bầu muốn chọn gạch chống trơn" KHÔNG bị chặn nhầm bởi cổng y khoa Spa!
        FanpageBotResponse tileResp = service.handleCustomerMessage(new FanpageBotRequest(
                tilePageId.toString(),
                "cust-tile-preg-01",
                "Anh Hùng",
                "vợ anh đang bầu muốn chọn gạch chống trơn",
                null,
                List.of(),
                List.of(docId),
                5
        ));
        assertNotNull(tileResp);
        assertFalse(tileResp.needsHumanHandoff(),
                "Câu 'vợ anh đang bầu muốn chọn gạch chống trơn' trên Page gạch/nội thất TUYỆT ĐỐI KHÔNG được bị chặn nhầm bởi MEDICAL_SAFETY_HANDOFF");
        assertTrue(tileResp.replyText().contains("320k/m²") || tileResp.replyText().contains("chống trơn"),
                "Bot trên Page gạch phải tư vấn sản phẩm gạch chống trơn bình thường: " + tileResp.replyText());
        assertFalse(brainService.getRecalledCareNotes(tilePageId, "cust-tile-preg-01").isEmpty(),
                "CleanAgentOrchestratorService phải lưu thành công care_note_delta có căn cứ từ lời khách trên Page gạch");

        // CASE 2: Trên Page Spa (BEAUTY_SPA), câu mang thai đặt lịch massage PHẢI bị chặn bởi MEDICAL_SAFETY_HANDOFF!
        FanpageBotResponse spaResp = service.handleCustomerMessage(new FanpageBotRequest(
                spaPageId.toString(),
                "cust-spa-preg-01",
                "Chị Mai",
                "chị đang bầu 5 tháng muốn đặt lịch massage",
                null,
                List.of(),
                List.of(docId),
                5
        ));
        assertTrue(spaResp.needsHumanHandoff(), "Trên Page Spa, khách mang thai phải kích hoạt Handoff an toàn");
        assertEquals("MEDICAL_SAFETY_HANDOFF", spaResp.handoffCategory());

        // CASE 3: Trên Page phi y khoa nhưng có cấu hình safety_red_flags ("rò rỉ điện"), tin nhắn khớp red flag PHẢI kích hoạt Handoff an toàn!
        FanpageBotResponse equipResp = service.handleCustomerMessage(new FanpageBotRequest(
                equipmentPageId.toString(),
                "cust-equip-01",
                "Anh Nam",
                "bình nóng lạnh nhà anh có dấu hiệu rò rỉ điện",
                null,
                List.of(),
                List.of(docId),
                5
        ));
        assertTrue(equipResp.needsHumanHandoff(), "Cấu hình safety_red_flags của Page phải kích hoạt Handoff an toàn");
        assertEquals("MEDICAL_SAFETY_HANDOFF", equipResp.handoffCategory());

        // CASE 4: Chính sách an toàn bắt buộc phổ quát (cấp cứu y tế cấp tính / Prompt Injection) luôn giữ hiệu lực trên mọi Page
        FanpageBotResponse acuteEmergencyOnTilePage = service.handleCustomerMessage(new FanpageBotRequest(
                tilePageId.toString(),
                "cust-tile-acute-01",
                "Anh Hùng",
                "anh đang tức ngực khó thở dữ dội quá",
                null,
                List.of(),
                List.of(docId),
                5
        ));
        assertTrue(acuteEmergencyOnTilePage.needsHumanHandoff(),
                "Cấp cứu y tế cấp tính phổ quát (tức ngực khó thở) phải luôn giữ hiệu lực ở mọi ngành");
    }
}
