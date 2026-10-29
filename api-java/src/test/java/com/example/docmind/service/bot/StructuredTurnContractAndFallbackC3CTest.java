package com.example.docmind.service.bot;

import com.example.docmind.dto.PagePersonaConfig;
import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.dto.bot.FanpageBotResponse;
import com.example.docmind.repository.RetrievedChunk;
import com.example.docmind.service.llm.LlmClient;
import com.example.docmind.service.llm.ReasoningBudget;
import com.example.docmind.service.retrieval.PromptBuilder;
import com.example.docmind.service.retrieval.ReciprocalRankFusion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bộ test nghiệm thu hành vi bắt buộc Mốc C3-C:
 * 1. Hợp đồng {@code <STRUCTURED_TURN_CONTRACT>} 5 trụ cột & dữ liệu 4 lớp {@link PackageCatalogService.PackageSummary}
 *    ({@code source}, {@code normalized}, {@code inferred}, {@code display} + {@code unitNoun}) trên 3 ngành:
 *    Nàng Sen Spa, Vật liệu xây dựng (Gạch ốp lát), Giáo dục (Trung tâm ngoại ngữ).
 * 2. Loại bỏ triệt để từ ngữ ghép cứng đặc thù Spa ("liệu trình", " phút", "chăm sóc vùng", "không phát sinh phụ phí hay nhận tip")
 *    khỏi toàn bộ các hàm fallback cấu trúc.
 * 3. Tầng Fallback Cấu Trúc Đa Ngành An Toàn khi LLM bị timeout / lỗi kết nối / trả rỗng, bảo toàn {@code reply_to.mid}
 *    và máy trạng thái đặt lịch 6 trạng thái.
 * 4. Tối ưu {@link CleanAgentOrchestratorService#resolveCleanAgentThinkingMode}: {@code FAST_NO_THINK} cho câu chào/hỏi thông tin đơn giản;
 *    {@code EMOTIONAL_WARM_THINK} / {@code OBJECTION_REFRAME_THINK} cho các lượt cảm xúc, lưỡng lự hoặc phản đối giá.
 */
class StructuredTurnContractAndFallbackC3CTest {

    private static ReciprocalRankFusion.FusedChunk buildChunk(String heading, String text) {
        RetrievedChunk rc = new RetrievedChunk(
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(heading),
                List.of(UUID.randomUUID()),
                1,
                new double[]{0, 0, 10, 10},
                text,
                0.95
        );
        return new ReciprocalRankFusion.FusedChunk(rc, 0.95);
    }

    private static final String SPA_CATALOG_MD = """
            | Mã gói | Tên gói | Thời lượng | Giá | Bao gồm | Không gian | Phù hợp | Vai trò thương mại |
            | --- | --- | --- | --- | --- | --- | --- | --- |
            | NS01 | Massage cơ bản | 60 phút | 399k | Ấn huyệt toàn thân + trà thảo mộc | Phòng chung | cổ, vai, gáy, lưng | gói chính |
            | DS02 | Gói dưỡng sinh cơ bản | 60 phút | 239k | Gội đầu dưỡng sinh + massage cổ vai | Khu dưỡng sinh | đầu, cổ, vai, gáy | gói tiết kiệm |
            """;

    private static final String TILE_CATALOG_MD = """
            | Mã mẫu | Tên sản phẩm | Kích thước | Đơn giá | Quy cách đóng gói | Hình thức giao hàng | Ứng dụng phù hợp |
            | --- | --- | --- | --- | --- | --- | --- |
            | GACH-8080 | Gạch Porcelain Vân Đá Marble | 80x80cm | 380.000đ/m² | Hộp 3 viên (1.92m²), xương đá chống thấm | Giao tận công trình nội thành | phòng khách, sảnh lớn |
            | GACH-6060 | Gạch Granite Nhám Chống Trơn | 60x60cm | 265.000đ/m² | Hộp 4 viên (1.44m²), bề mặt matt R11 | Giao tận công trình nội thành | phòng bếp, sân thượng |
            """;

    private static final String EDU_CATALOG_MD = """
            | Mã khóa | Tên khóa học | Lộ trình | Học phí | Quyền lợi học viên | Hình thức học | Đối tượng phù hợp |
            | --- | --- | --- | --- | --- | --- | --- |
            | ENG-GT01 | Khóa Tiếng Anh Giao Tiếp Cơ Bản | 24 buổi | 3.900.000đ/khóa | Giáo trình bản quyền + CLB phản xạ cuối tuần | Lớp offline tối đa 10 học viên | mất gốc, người đi làm |
            | IELTS-65 | Khóa IELTS Intensive 6.5+ | 36 buổi | 6.500.000đ/khóa | Chấm chữa Writing 1-1 + thi thử phòng máy | Lớp offline hoặc hybrid | mục tiêu 6.5+, du học |
            """;

    private static PromptBuilder.PagePersona buildTilePersona() {
        return new PromptBuilder.PagePersona(
                "Showroom Gạch Kiến Việt",
                "BUILDING_MATERIALS",
                "Chủ nhà, kiến trúc sư",
                "Chuyên nghiệp, rõ thông số kỹ thuật",
                PagePersonaConfig.fromJson("""
                        {
                          "industry_code": "BUILDING_MATERIALS",
                          "bot_name": "Chuyên viên Kiến Việt",
                          "self_pronoun": "bên mình",
                          "default_customer_pronoun": "Anh",
                          "care_actor": "kỹ thuật viên kho vận",
                          "unit_noun": "mẫu",
                          "scope_taxonomy": ["phòng khách", "phòng bếp", "sảnh lớn", "sân thượng"]
                        }
                        """)
        );
    }

    private static PromptBuilder.PagePersona buildEduPersona() {
        return new PromptBuilder.PagePersona(
                "Học viện Anh ngữ Horizon",
                "EDUCATION_ACADEMY",
                "Sinh viên, người đi làm",
                "Tận tâm, rõ lộ trình đầu ra",
                PagePersonaConfig.fromJson("""
                        {
                          "industry_code": "EDUCATION_ACADEMY",
                          "bot_name": "Cố vấn học tập Horizon",
                          "self_pronoun": "trung tâm",
                          "default_customer_pronoun": "Bạn",
                          "care_actor": "giảng viên học thuật",
                          "unit_noun": "khóa",
                          "scope_taxonomy": ["mất gốc", "người đi làm", "mục tiêu 6.5+", "du học"]
                        }
                        """)
        );
    }

    private static void assertNoHardcodedSpaPhrases(String text) {
        String low = text.toLowerCase();
        assertFalse(low.contains("liệu trình"), "Fallback đa ngành không được chứa từ 'liệu trình': " + text);
        assertFalse(low.contains(" phút"), "Fallback đa ngành không được ghép cứng ' phút': " + text);
        assertFalse(low.contains("chăm sóc vùng"), "Fallback đa ngành không được ghép cứng 'chăm sóc vùng': " + text);
        assertFalse(low.contains("không phát sinh phụ phí"), "Fallback đa ngành không được ghép cứng 'không phát sinh phụ phí': " + text);
        assertFalse(low.contains("nhận tip"), "Fallback đa ngành không được ghép cứng 'nhận tip': " + text);
    }

    @Test
    void testFourLayerPackageSummaryAndStructuredTurnContractAcrossSpaTilesEducation() {
        List<PackageCatalogService.PackageSummary> spaCatalog = PackageCatalogService.extractCatalog(
                List.of(buildChunk("Bảng giá Nàng Sen Spa", SPA_CATALOG_MD)),
                List.of("cổ", "vai", "gáy", "lưng")
        );
        List<PackageCatalogService.PackageSummary> tileCatalog = PackageCatalogService.extractCatalog(
                List.of(buildChunk("Bảng giá Gạch ốp lát", TILE_CATALOG_MD)),
                List.of("phòng khách", "phòng bếp", "sảnh lớn")
        );
        List<PackageCatalogService.PackageSummary> eduCatalog = PackageCatalogService.extractCatalog(
                List.of(buildChunk("Bảng học phí Khóa học", EDU_CATALOG_MD)),
                List.of("mất gốc", "mục tiêu 6.5+")
        );

        assertEquals(2, spaCatalog.size());
        assertEquals("gói", spaCatalog.get(0).display().unitNoun());

        assertEquals(2, tileCatalog.size());
        PackageCatalogService.PackageSummary gach8080 = tileCatalog.get(0);
        assertEquals("GACH-8080", gach8080.source().rawSkuCode());
        assertEquals(380_000L, gach8080.normalized().priceAmountVnd());
        assertEquals("/m²", gach8080.normalized().billingUnit());
        assertTrue(gach8080.inferred().scopeTags().contains("phòng khách"));
        assertEquals("mẫu", gach8080.display().unitNoun());
        assertEquals("380k/m²", gach8080.display().formattedPriceWithUnit());

        assertEquals(2, eduCatalog.size());
        PackageCatalogService.PackageSummary ielts65 = eduCatalog.get(1);
        assertEquals("IELTS-65", ielts65.source().rawSkuCode());
        assertEquals(6_500_000L, ielts65.normalized().priceAmountVnd());
        assertEquals("/khóa", ielts65.normalized().billingUnit());
        assertEquals("khóa", ielts65.display().unitNoun());

        // Kiểm chứng <STRUCTURED_TURN_CONTRACT> 5 trụ cột cho ngành Gạch ốp lát
        PromptBuilder.PagePersona tilePersona = buildTilePersona();
        CleanAgentCatalogAndPromptBuilder.TieredCatalogResult tileTiered =
                CleanAgentCatalogAndPromptBuilder.rankAndTierCatalogForCleanAgent(
                        tileCatalog,
                        List.of(),
                        List.of("phòng khách"),
                        Set.of(),
                        false
                );
        String tilePrompt = CleanAgentCatalogAndPromptBuilder.buildCleanAgentPrompt(
                tilePersona,
                "Anh",
                List.of(),
                List.of("phòng khách"),
                "nhà mới 80m2",
                true,
                "null",
                "null",
                0,
                false,
                "NONE",
                List.of("PRODUCT_RECOMMENDATION_READY"),
                "",
                1,
                tileTiered,
                List.of(buildChunk("Bảng giá Gạch ốp lát", TILE_CATALOG_MD)),
                List.of(),
                "Tư vấn cho anh mẫu gạch lát phòng khách 80m2",
                null,
                List.of(),
                java.util.Map.of(),
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                UUID.randomUUID(),
                List.of(),
                ConversationState.StructuredBookingState.none()
        );

        assertTrue(tilePrompt.contains("<STRUCTURED_TURN_CONTRACT>"));
        assertTrue(tilePrompt.contains("1. Intent:"));
        assertTrue(tilePrompt.contains("2. Conversation & Booking State:"));
        assertTrue(tilePrompt.contains("unit_noun=\"mẫu\""));
        assertTrue(tilePrompt.contains("3. Brain Hypothesis & Skill:"));
        assertTrue(tilePrompt.contains("4. Goal & Allowed Actions:"));
        assertTrue(tilePrompt.contains("5. Commercial Constraints:"));
        assertTrue(tilePrompt.contains("id=GACH-8080") && tilePrompt.contains("billing_unit=\"/m²\"") && tilePrompt.contains("380k/m²"),
                "Commercial Constraints phải truyền đầy đủ dữ liệu 4 lớp của GACH-8080: " + tilePrompt);
    }

    @Test
    void testFallbackBuildersRemoveAllHardcodedSpaPhrasesAcrossTilesAndEducation() {
        List<PackageCatalogService.PackageSummary> tileCatalog = PackageCatalogService.extractCatalog(
                List.of(buildChunk("Bảng giá Gạch ốp lát", TILE_CATALOG_MD)),
                List.of("phòng khách", "phòng bếp")
        );
        List<PackageCatalogService.PackageSummary> eduCatalog = PackageCatalogService.extractCatalog(
                List.of(buildChunk("Bảng học phí Khóa học", EDU_CATALOG_MD)),
                List.of("mất gốc", "mục tiêu 6.5+")
        );
        PagePersonaConfig tileCfg = buildTilePersona().resolvedConfig();
        PagePersonaConfig eduCfg = buildEduPersona().resolvedConfig();

        // 1. rebuildBubblesFromStructuredCatalog trên ngành Gạch (khi LLM râu ông nọ cắm cằm bà kia: ghép Gạch 80x80cm với giá 265k/m²)
        List<FanpageBotResponse.ReplyBubble> mismatchedTile = List.of(
                new FanpageBotResponse.ReplyBubble("Dạ mẫu Gạch Porcelain Vân Đá Marble 80x80cm bên mình đang có giá 265k/m² ạ?", 0)
        );
        List<FanpageBotResponse.ReplyBubble> rebuiltTile =
                CleanAgentOutputRefereeAndSanitizer.rebuildBubblesFromStructuredCatalog(
                        mismatchedTile,
                        tileCatalog.get(0),
                        tileCatalog,
                        "bên mình",
                        "Anh",
                        tileCfg
                );
        assertFalse(rebuiltTile.isEmpty());
        String rebuiltTileText = rebuiltTile.get(0).text();
        assertTrue(rebuiltTileText.contains("Gạch Porcelain Vân Đá Marble") && rebuiltTileText.contains("380k/m²"));
        assertFalse(rebuiltTileText.contains("265k"));
        assertNoHardcodedSpaPhrases(rebuiltTileText);

        // 2. buildDeterministicQuotedPackageReplyBubbles trên ngành Giáo dục
        List<FanpageBotResponse.ReplyBubble> quotedEduBubbles =
                CleanAgentOutputRefereeAndSanitizer.buildDeterministicQuotedPackageReplyBubbles(
                        eduCatalog.get(1),
                        "Khóa này gồm những gì bạn?",
                        "trung tâm",
                        "Bạn",
                        eduCfg
                );
        assertFalse(quotedEduBubbles.isEmpty());
        String combinedEdu = String.join(" ", quotedEduBubbles.stream().map(FanpageBotResponse.ReplyBubble::text).toList());
        assertTrue(combinedEdu.contains("Khóa IELTS Intensive 6.5+")
                && combinedEdu.contains(eduCatalog.get(1).display().formattedPriceWithUnit()),
                "Phải chứa đúng tên khóa và học phí chuẩn hóa: " + combinedEdu);
        assertNoHardcodedSpaPhrases(combinedEdu);

        // 3. buildDeterministicReplyClarificationBubbles cho Tier 1 (>=2 ứng viên), Tier 2 (1 ứng viên), Tier 3 (0 ứng viên) trên ngành Giáo dục & Gạch
        CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution eduTier2 =
                new CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution(
                        "mid_edu_1", null, true, false, null, List.of(), List.of(eduCatalog.get(0))
                );
        List<FanpageBotResponse.ReplyBubble> eduTier2Bubbles =
                CleanAgentOutputRefereeAndSanitizer.buildDeterministicReplyClarificationBubbles(
                        eduTier2,
                        "Khóa này có ưu đãi không?",
                        "trung tâm",
                        "Bạn",
                        eduCfg,
                        eduCatalog
                );
        String eduTier2Text = eduTier2Bubbles.get(0).text();
        assertTrue(eduTier2Text.contains("hay một khóa khác"), "Tier 2 ngành Giáo dục phải hỏi 'hay một khóa khác': " + eduTier2Text);
        assertNoHardcodedSpaPhrases(eduTier2Text);
        assertTrue(
                CleanAgentOutputRefereeAndSanitizer.checkReplyResolutionGuard(
                        eduTier2Bubbles, eduTier2, eduCatalog, "Khóa này có ưu đãi không?"
                ).isEmpty(),
                "Câu hỏi làm rõ Tier 2 theo unitNoun 'khóa' phải PASS checkReplyResolutionGuard 0 vi phạm"
        );

        CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution tileTier3 =
                new CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution(
                        "mid_tile_empty", null, true, false, null, List.of(), List.of()
                );
        List<FanpageBotResponse.ReplyBubble> tileTier3Bubbles =
                CleanAgentOutputRefereeAndSanitizer.buildDeterministicReplyClarificationBubbles(
                        tileTier3,
                        "Mẫu này còn hàng không?",
                        "bên mình",
                        "Anh",
                        tileCfg,
                        tileCatalog
                );
        String tileTier3Text = tileTier3Bubbles.get(0).text();
        assertTrue(tileTier3Text.contains("tên mẫu hoặc mức giá"), "Tier 3 ngành Gạch phải hỏi 'tên mẫu hoặc mức giá': " + tileTier3Text);
        assertNoHardcodedSpaPhrases(tileTier3Text);
    }

    @Test
    void testSimulatedLlmTimeoutAndConnectionFailure_PreservesReplyToMidAnd6StateBookingViaStructuralFallback() {
        ConversationState.clearSessionCache();
        SentBubbleLedger.clear();

        LlmClient failingLlm = new LlmClient() {
            @Override
            public String complete(String prompt) {
                throw new RuntimeException("Simulated LLM connection timeout (SocketTimeoutException)");
            }

            @Override
            public String complete(String prompt, ReasoningBudget budget) {
                throw new RuntimeException("Simulated LLM connection timeout (SocketTimeoutException)");
            }

            @Override
            public String name() {
                return "simulated-timeout-llm";
            }
        };
        AgentBrainService brain = new AgentBrainService((com.example.docmind.client.AgentBrainClient) null);
        CleanAgentOrchestratorService orchestrator = new CleanAgentOrchestratorService(failingLlm, brain, null);

        // Ca A: Ngành Gạch — Khách reply vào mid_tile_8080 hỏi ưu đãi khi LLM chết kết nối
        UUID tilePageId = UUID.randomUUID();
        PromptBuilder.PagePersona tilePersona = buildTilePersona();
        List<ReciprocalRankFusion.FusedChunk> tileChunks = List.of(buildChunk("Bảng giá Gạch ốp lát", TILE_CATALOG_MD));
        List<FanpageBotRequest.ConversationTurn> tileHistory = List.of(
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ bên mình có mẫu Gạch Porcelain Vân Đá Marble 80x80cm đơn giá 380k/m² và Gạch Granite Nhám Chống Trơn 60x60cm đơn giá 265k/m² ạ.")
        );
        String quotedBubble8080 = "Mẫu Gạch Porcelain Vân Đá Marble 80x80cm đơn giá 380k/m², đóng hộp 3 viên ạ.";

        List<String> tileTax = tilePersona.resolvedConfig().resolvedScopeTaxonomy();
        ConversationState preTile = ConversationState.hydrateForTurn(
                tilePageId, "u_tile_timeout", "c_tile_timeout", true, "Mẫu này có giảm giá không?", tileHistory, tileTax, List.of()
        );
        ConversationState convTile = preTile.enrichWithCurrentMessage("Mẫu này có giảm giá không?", tileTax)
                .withRetrievedChunks(tileChunks, tileTax, tileHistory);
        FanpageBotRequest tileReq = new FanpageBotRequest(
                tilePageId.toString(), "u_tile_timeout", "Anh Hùng", "Mẫu này có giảm giá không?", null,
                tileHistory, List.of(), 5, null, null, null, null, null, null, null,
                "c_tile_timeout", true, "mid_tile_8080", quotedBubble8080
        );

        FanpageBotResponse tileResp = orchestrator.executeCleanAgentTurn(
                tilePageId, tileReq, "Mẫu này có giảm giá không?", "Anh", tilePersona,
                preTile, convTile, tileChunks, List.of(), null, null, List.of(), List.of(),
                FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION, 1, System.nanoTime(), 5L
        );
        assertFalse(tileResp.replyBubbles().isEmpty(), "Khi LLM timeout vẫn phải trả về bong bóng Fallback Cấu Trúc Đa Ngành An Toàn");
        assertTrue(tileResp.replyText().contains("Gạch Porcelain Vân Đá Marble") && tileResp.replyText().contains("380k/m²"),
                "Phải bảo toàn đúng sản phẩm từ reply_to.mid=mid_tile_8080 (380k/m²): " + tileResp.replyText());
        assertFalse(tileResp.replyText().contains("265k"), "Không được trượt sang mẫu 265k/m²");
        assertNoHardcodedSpaPhrases(tileResp.replyText());

        // Ca B: Ngành Giáo dục — Khách reply vào mid_edu_missing (không tìm thấy bong bóng cũ) khi lịch sử có 2 khóa học & LLM timeout
        UUID eduPageId = UUID.randomUUID();
        PromptBuilder.PagePersona eduPersona = buildEduPersona();
        List<ReciprocalRankFusion.FusedChunk> eduChunks = List.of(buildChunk("Bảng học phí Khóa học", EDU_CATALOG_MD));
        List<FanpageBotRequest.ConversationTurn> eduHistory = List.of(
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ trung tâm hiện có Khóa Tiếng Anh Giao Tiếp Cơ Bản (3,9 triệu/khóa) và Khóa IELTS Intensive 6.5+ (6,5 triệu/khóa) ạ.")
        );
        List<String> eduTax = eduPersona.resolvedConfig().resolvedScopeTaxonomy();
        ConversationState preEdu = ConversationState.hydrateForTurn(
                eduPageId, "u_edu_timeout", "c_edu_timeout", true, "Khóa này lịch học sao ạ?", eduHistory, eduTax, List.of()
        );
        ConversationState convEdu = preEdu.enrichWithCurrentMessage("Khóa này lịch học sao ạ?", eduTax)
                .withRetrievedChunks(eduChunks, eduTax, eduHistory);
        FanpageBotRequest eduReq = new FanpageBotRequest(
                eduPageId.toString(), "u_edu_timeout", "Bạn Minh", "Khóa này lịch học sao ạ?", null,
                eduHistory, List.of(), 5, null, null, null, null, null, null, null,
                "c_edu_timeout", true, "mid_edu_missing", null
        );

        FanpageBotResponse eduResp = orchestrator.executeCleanAgentTurn(
                eduPageId, eduReq, "Khóa này lịch học sao ạ?", "Bạn", eduPersona,
                preEdu, convEdu, eduChunks, List.of(), null, null, List.of(), List.of(),
                FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION, 1, System.nanoTime(), 5L
        );
        assertFalse(eduResp.replyBubbles().isEmpty());
        assertTrue((eduResp.replyText().contains("3.900k/khóa") || eduResp.replyText().contains("3,9 triệu/khóa"))
                        && (eduResp.replyText().contains("6.500k/khóa") || eduResp.replyText().contains("6,5 triệu/khóa"))
                        && eduResp.replyText().contains("?"),
                "Khi LLM timeout trên reply_to.mid không tìm thấy, phải hỏi làm rõ giữa 2 khóa học trong lịch sử: " + eduResp.replyText());
        assertNoHardcodedSpaPhrases(eduResp.replyText());

        // Ca C: Đặt hàng Gạch đủ sản phẩm + ngày + giờ khi LLM timeout -> giữ đúng AWAITING_AVAILABILITY_CHECK, không tuyên bố CONFIRMED
        String orderMsg = "Đặt cho anh mẫu GACH-8080 chiều mai lúc 3 giờ";
        ConversationState preOrder = ConversationState.hydrateForTurn(
                tilePageId, "u_tile_order_timeout", "c_tile_order_timeout", true, orderMsg, tileHistory, tileTax, List.of()
        );
        ConversationState convOrder = preOrder.enrichWithCurrentMessage(orderMsg, tileTax)
                .withRetrievedChunks(tileChunks, tileTax, tileHistory);
        FanpageBotRequest orderReq = new FanpageBotRequest(
                tilePageId.toString(), "u_tile_order_timeout", "Anh Hùng", orderMsg, null,
                tileHistory, List.of(), 5, null, null, null, null, null, null, null,
                "c_tile_order_timeout", true, null, null
        );
        FanpageBotResponse orderResp = orchestrator.executeCleanAgentTurn(
                tilePageId, orderReq, orderMsg, "Anh", tilePersona,
                preOrder, convOrder, tileChunks, List.of(), null, null, List.of(), List.of(),
                FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION, 2, System.nanoTime(), 5L
        );
        assertEquals(ConversationState.BookingStatus.AWAITING_AVAILABILITY_CHECK, orchestrator.getLastTurnBookingState().status());
        assertEquals("GACH-8080", orchestrator.getLastTurnBookingState().selectedProductId());
        assertEquals("15:00", orchestrator.getLastTurnBookingState().bookingTime());
        assertTrue(orderResp.replyText().contains("kiểm tra") && orderResp.replyText().contains("380k/m²"),
                "Fallback đặt hàng khi LLM timeout phải ghi nhận yêu cầu và báo kiểm tra thực tế trước khi xác nhận chính thức: " + orderResp.replyText());
        assertNoHardcodedSpaPhrases(orderResp.replyText());
    }

    @Test
    void testResolveCleanAgentThinkingModeOptimization() {
        // 1. Câu chào & câu hỏi thông tin đơn giản -> FAST_NO_THINK
        assertEquals(
                ThinkingMode.FAST_NO_THINK,
                CleanAgentOrchestratorService.resolveCleanAgentThinkingMode(
                        "Xin chào shop", false, false, false, false, false, false, false, false, false, false, false, List.of()
                )
        );
        assertEquals(
                ThinkingMode.FAST_NO_THINK,
                CleanAgentOrchestratorService.resolveCleanAgentThinkingMode(
                        "Tối mấy giờ bên mình đóng cửa?", false, false, false, false, false, true, false, false, false, false, false, List.of()
                )
        );
        assertEquals(
                ThinkingMode.FAST_NO_THINK,
                CleanAgentOrchestratorService.resolveCleanAgentThinkingMode(
                        "Cho anh xin bảng giá các mẫu gạch 80x80?", false, false, false, false, false, false, false, false, false, false, true, List.of()
                )
        );

        // 2. Các lượt chia sẻ cảm xúc, đau mỏi hoặc lưỡng lự chung -> EMOTIONAL_WARM_THINK
        assertEquals(
                ThinkingMode.EMOTIONAL_WARM_THINK,
                CleanAgentOrchestratorService.resolveCleanAgentThinkingMode(
                        "Dạo này chị đau mỏi vai gáy mất ngủ mệt quá em ơi", false, false, false, false, false, false, false, false, false, false, false, List.of("đau mỏi vai gáy")
                )
        );
        assertEquals(
                ThinkingMode.EMOTIONAL_WARM_THINK,
                CleanAgentOrchestratorService.resolveCleanAgentThinkingMode(
                        "Mình vẫn hơi phân vân chưa biết mất gốc có theo kịp khóa này không", false, false, false, false, true, false, false, false, true, false, false, List.of()
                )
        );

        // 3. Các lượt phản đối giá hoặc lưỡng lự chi phí -> OBJECTION_REFRAME_THINK
        assertEquals(
                ThinkingMode.OBJECTION_REFRAME_THINK,
                CleanAgentOrchestratorService.resolveCleanAgentThinkingMode(
                        "Đơn giá 380k/m2 hơi cao nhỉ", false, false, false, true, false, false, false, false, true, false, false, List.of()
                )
        );
        assertEquals(
                ThinkingMode.OBJECTION_REFRAME_THINK,
                CleanAgentOrchestratorService.resolveCleanAgentThinkingMode(
                        "Biết thế", false, false, false, true, true, false, false, false, true, false, false, List.of()
                )
        );
    }
}
