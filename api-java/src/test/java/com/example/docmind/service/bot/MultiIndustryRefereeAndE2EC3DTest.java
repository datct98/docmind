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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Bộ test nghiệm thu hành vi bắt buộc Mốc C3-D:
 * 1. Nhóm 1 — Chốt chặn sự thật thương mại & trạng thái đa ngành:
 *    - PRICE_ENTITY_MISMATCH kiểm chứng bộ 4 (canonicalName, normalized.specifications, priceAmountVnd, billingUnit)
 *    - UNGROUNDED_PRODUCT_SPECIFICATION (chấp nhận thông số thành phần cộng hợp lệ, chặn thông số bịa)
 *    - CROSS_TIER_EXCLUSIVE_ATTRIBUTE_MISMATCH dẫn xuất từ dữ liệu Catalog đa ngành
 *    - UNPRESENTED_GHOST_PRODUCT_ON_CLOSING ở STAGE_6
 *    - FALSE_PRODUCT_SELECTION_CLAIM & UNVERIFIED_BOOKING_CONFIRMATION_CLAIM
 *    - TIER_2_SINGLE_CANDIDATE linh hoạt theo unitNoun từng ngành
 * 2. Nhóm 3 — Bảng chứng minh các luật Referee tinh giản (bắt lỗi thật, triệt tiêu False Positive, sửa đại từ 0ms không gọi lại LLM).
 * 3. Kiểm thử Đầu-Cuối (E2E) trên 3 ngành: Nàng Sen Spa, Vật liệu xây dựng (Gạch ốp lát), Giáo dục (Trung tâm ngoại ngữ) + Fallback khi LLM lỗi.
 */
class MultiIndustryRefereeAndE2EC3DTest {

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

    private static final String SPA_KB_TABLE = """
            | Mã gói | Tên gói | Thời lượng | Giá | Quyền lợi đi kèm | Không gian | Vùng chăm sóc | Vai trò thương mại |
            | --- | --- | --- | --- | --- | --- | --- | --- |
            | NS01 | Massage cơ bản | 60 phút | 399k | Ấn huyệt chuyên sâu + ăn nhẹ thực dưỡng | Phòng chung có rèm riêng | đầu, cổ, vai, gáy, lưng | gói chính |
            | NS02 | Massage chuyên sâu | 80 phút | 530k | 10 phút ngâm chân thảo dược + 70 phút trị liệu + ăn nhẹ thực dưỡng | Phòng chung có rèm riêng | đầu, cổ, vai, gáy, lưng, chân | gói chính |
            | DS02 | Gói dưỡng sinh cơ bản | 60 phút | 239k | Trà thảo mộc ấm + ấn huyệt cổ vai gáy | Khu rèm riêng tiêu chuẩn | đầu, cổ, vai, gáy | gói tiết kiệm |
            | VIP99 | Liệu trình Hoàng Gia Đá Nóng | 120 phút | 799k | Ngâm bồn thảo dược + đá nóng + buffet thực dưỡng | Phòng VIP riêng | đầu, cổ, vai, gáy, lưng, toàn thân | cao cấp |
            """;

    private static final String TILE_KB_TABLE = """
            | Mã SP | Tên mẫu | Kích thước | Đơn giá | Quy cách & Chất liệu | Hình thức giao | Khu vực sử dụng | Phân khúc |
            | --- | --- | --- | --- | --- | --- | --- | --- |
            | GACH-8080 | Gạch Porcelain Vân Đá Trắng Khói | 80x80cm | 380k/m² | Xương đá Porcelain chống thấm, bề mặt nhám R11 chống trơn, hộp 3 viên | Giao tận công trình | phòng khách, phòng bếp | sản phẩm chính |
            | GACH-6060 | Gạch Ceramic Vân Mây Tiêu Chuẩn | 60x60cm | 245k/m² | Xương đất nung Ceramic men mờ, hộp 4 viên | Giao hàng tiêu chuẩn | phòng khách, phòng ngủ | tiết kiệm |
            """;

    private static final String EDU_KB_TABLE = """
            | Mã khóa | Tên khóa học | Lộ trình | Học phí | Quyền lợi khóa học | Hình thức học | Đối tượng | Phân khúc |
            | --- | --- | --- | --- | --- | --- | --- | --- |
            | IELTS-65 | Khóa Luyện Thi IELTS 6.5 Cam Kết | 36 buổi | 6.500k/khóa | Chấm chữa Writing 1-1 cùng giảng viên 8.5 + thi thử phòng máy | Lớp sĩ số nhỏ 6 học viên | mất gốc, người đi làm, luyện thi ielts | gói chính |
            | ENG-GT01 | Khóa Tiếng Anh Giao Tiếp Cơ Bản | 24 buổi | 3.900k/khóa | Giáo trình phản xạ giao tiếp + câu lạc bộ cuối tuần | Lớp tiêu chuẩn 12 học viên | mất gốc, người đi làm | tiết kiệm |
            """;

    private static PromptBuilder.PagePersona buildSpaPersona() {
        return new PromptBuilder.PagePersona(
                "Nàng Sen Spa",
                "BEAUTY_SPA",
                "Dân văn phòng",
                "Thư giãn chuyên sâu",
                PagePersonaConfig.fromJson("""
                        {
                          "industry_code": "BEAUTY_SPA",
                          "bot_name": "Lễ tân Nàng Sen",
                          "self_pronoun": "em",
                          "default_customer_pronoun": "mình",
                          "unit_noun": "gói",
                          "scope_taxonomy": ["đầu", "cổ", "vai", "gáy", "lưng", "chân", "toàn thân"]
                        }
                        """)
        );
    }

    private static PromptBuilder.PagePersona buildTilePersona() {
        return new PromptBuilder.PagePersona(
                "Tổng Kho Gạch Ốp Lát Minh Phát",
                "CONSTRUCTION_MATERIALS",
                "Chủ nhà & thầu xây dựng",
                "Tư vấn kỹ thuật chuẩn xác",
                PagePersonaConfig.fromJson("""
                        {
                          "industry_code": "CONSTRUCTION_MATERIALS",
                          "bot_name": "Chuyên viên Gạch Minh Phát",
                          "self_pronoun": "em",
                          "default_customer_pronoun": "anh/chị",
                          "unit_noun": "mẫu",
                          "scope_taxonomy": ["phòng khách", "phòng bếp", "phòng ngủ", "nhà tắm", "sân vườn"]
                        }
                        """)
        );
    }

    private static PromptBuilder.PagePersona buildEduPersona() {
        return new PromptBuilder.PagePersona(
                "Anh Ngữ Quốc Tế Horizon",
                "EDUCATION_TRAINING",
                "Người đi làm & sinh viên",
                "Tận tâm, rõ lộ trình",
                PagePersonaConfig.fromJson("""
                        {
                          "industry_code": "EDUCATION_TRAINING",
                          "bot_name": "Cố vấn Học tập Horizon",
                          "self_pronoun": "em",
                          "default_customer_pronoun": "mình",
                          "unit_noun": "khóa",
                          "scope_taxonomy": ["mất gốc", "người đi làm", "luyện thi ielts", "giao tiếp"]
                        }
                        """)
        );
    }

    private static FanpageBotResponse executeSingleTurn(
            CleanAgentOrchestratorService orchestrator,
            UUID pageId,
            String senderId,
            String convId,
            boolean resetSession,
            String userMsg,
            String replyToMid,
            String replyToText,
            String customerPronoun,
            List<FanpageBotRequest.ConversationTurn> history,
            PromptBuilder.PagePersona persona,
            List<ReciprocalRankFusion.FusedChunk> chunks
    ) {
        List<String> taxonomy = persona.resolvedConfig().resolvedScopeTaxonomy();
        ConversationState preState = ConversationState.hydrateForTurn(
                pageId, senderId, convId, resetSession, userMsg, history, taxonomy, List.of()
        );
        ConversationState convState = preState.enrichWithCurrentMessage(userMsg, taxonomy)
                .withRetrievedChunks(chunks, taxonomy, history);
        FanpageBotRequest req = new FanpageBotRequest(
                pageId.toString(),
                senderId,
                "Khách Nghiệm Thu C3D",
                userMsg,
                null,
                history,
                List.of(),
                5,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                convId,
                resetSession,
                replyToMid,
                replyToText
        );
        return orchestrator.executeCleanAgentTurn(
                pageId,
                req,
                userMsg,
                customerPronoun,
                persona,
                preState,
                convState,
                chunks,
                List.of(),
                null,
                null,
                List.of(),
                List.of(),
                FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION,
                history.size(),
                System.nanoTime(),
                8L
        );
    }

    // =========================================================================
    // TEST 1: NHÓM 1 — PRICE_ENTITY_MISMATCH BỘ 4 THÀNH PHẦN TRÊN CẢ 3 NGÀNH
    // =========================================================================
    @Test
    void testGroup1_PriceEntityMismatch_FourTupleAcrossThreeIndustries() {
        List<PackageCatalogService.PackageSummary> spaCatalog = PackageCatalogService.extractCatalog(
                List.of(buildChunk("Bảng giá Spa", SPA_KB_TABLE)),
                buildSpaPersona().resolvedConfig().resolvedScopeTaxonomy()
        );
        List<PackageCatalogService.PackageSummary> tileCatalog = PackageCatalogService.extractCatalog(
                List.of(buildChunk("Bảng giá Gạch", TILE_KB_TABLE)),
                buildTilePersona().resolvedConfig().resolvedScopeTaxonomy()
        );
        List<PackageCatalogService.PackageSummary> eduCatalog = PackageCatalogService.extractCatalog(
                List.of(buildChunk("Bảng học phí", EDU_KB_TABLE)),
                buildEduPersona().resolvedConfig().resolvedScopeTaxonomy()
        );

        // 1. Ngành Spa: Sai thời lượng (Massage cơ bản 80 phút) & sai giá (Massage cơ bản 60 phút giá 239k)
        assertTrue(CleanAgentOutputRefereeAndSanitizer.detectPriceEntityMismatchesInBubble(
                "Dạ em gợi ý mình gói Massage cơ bản 80 phút (399k trọn gói) ạ.", spaCatalog
        ).contains("PRICE_ENTITY_MISMATCH"));
        assertTrue(CleanAgentOutputRefereeAndSanitizer.detectPriceEntityMismatchesInBubble(
                "Dạ gói Massage cơ bản 60 phút hiện có mức giá 239k trọn gói ạ.", spaCatalog
        ).contains("PRICE_ENTITY_MISMATCH"));
        assertTrue(CleanAgentOutputRefereeAndSanitizer.detectPriceEntityMismatchesInBubble(
                "Dạ gói Massage cơ bản 60 phút bên em có giá 399k trọn gói ạ.", spaCatalog
        ).isEmpty());

        // 2. Ngành Gạch ốp lát: Sai kích thước, sai đơn giá, hoặc sai đơn vị tính (/thùng thay vì /m²)
        assertTrue(CleanAgentOutputRefereeAndSanitizer.detectPriceEntityMismatchesInBubble(
                "Dạ mẫu Gạch Porcelain Vân Đá Trắng Khói kích thước 60x60cm có giá 380k/m² ạ.", tileCatalog
        ).contains("PRICE_ENTITY_MISMATCH"), "Ghép sai thông số 60x60cm vào mẫu GACH-8080 phải bắt PRICE_ENTITY_MISMATCH");
        assertTrue(CleanAgentOutputRefereeAndSanitizer.detectPriceEntityMismatchesInBubble(
                "Dạ mẫu Gạch Porcelain Vân Đá Trắng Khói 80x80cm hiện có đơn giá 245k/m² ạ.", tileCatalog
        ).contains("PRICE_ENTITY_MISMATCH"), "Ghép nhầm giá 245k/m² của Ceramic vào Porcelain 80x80 phải bắt PRICE_ENTITY_MISMATCH");
        assertTrue(CleanAgentOutputRefereeAndSanitizer.detectPriceEntityMismatchesInBubble(
                "Dạ mẫu Gạch Porcelain Vân Đá Trắng Khói 80x80cm bên em bán 380k/thùng ạ.", tileCatalog
        ).contains("PRICE_ENTITY_MISMATCH"), "Sai billingUnit (/thùng thay vì /m²) phải bắt PRICE_ENTITY_MISMATCH");
        assertTrue(CleanAgentOutputRefereeAndSanitizer.detectPriceEntityMismatchesInBubble(
                "Dạ mẫu Gạch Porcelain Vân Đá Trắng Khói 80x80cm có đơn giá niêm yết 380k/m² ạ.", tileCatalog
        ).isEmpty(), "Khớp chuẩn bộ 4 (tên, 80x80cm, 380k, /m²) không được báo lỗi");

        // 3. Ngành Giáo dục: Sai số buổi (36 buổi gắn vào khóa 24 buổi) hoặc sai billingUnit (/tháng thay vì /khóa)
        assertTrue(CleanAgentOutputRefereeAndSanitizer.detectPriceEntityMismatchesInBubble(
                "Dạ Khóa Tiếng Anh Giao Tiếp Cơ Bản lộ trình 36 buổi có học phí 3.900k/khóa ạ.", eduCatalog
        ).contains("PRICE_ENTITY_MISMATCH"), "Ghép sai 36 buổi vào khóa Giao Tiếp 24 buổi phải bắt PRICE_ENTITY_MISMATCH");
        assertTrue(CleanAgentOutputRefereeAndSanitizer.detectPriceEntityMismatchesInBubble(
                "Dạ Khóa Tiếng Anh Giao Tiếp Cơ Bản 24 buổi có học phí 3.900k/tháng ạ.", eduCatalog
        ).contains("PRICE_ENTITY_MISMATCH"), "Sai billingUnit (/tháng thay vì /khóa) phải bắt PRICE_ENTITY_MISMATCH");
        assertTrue(CleanAgentOutputRefereeAndSanitizer.detectPriceEntityMismatchesInBubble(
                "Dạ Khóa Tiếng Anh Giao Tiếp Cơ Bản 24 buổi hiện có học phí 3.900k/khóa ạ.", eduCatalog
        ).isEmpty(), "Khớp chuẩn bộ 4 (tên, 24 buổi, 3.900k, /khóa) không được báo lỗi");
    }

    // =========================================================================
    // TEST 2: NHÓM 1 — UNGROUNDED_PRODUCT_SPECIFICATION & THÔNG SỐ THÀNH PHẦN CỘNG HỢP LỆ
    // =========================================================================
    @Test
    void testGroup1_UngroundedProductSpecification_AllowsAdditiveComponents_BlocksFabricatedSpecs() {
        List<PackageCatalogService.PackageSummary> spaCatalog = PackageCatalogService.extractCatalog(
                List.of(buildChunk("Bảng giá Spa", SPA_KB_TABLE)),
                buildSpaPersona().resolvedConfig().resolvedScopeTaxonomy()
        );
        List<PackageCatalogService.PackageSummary> tileCatalog = PackageCatalogService.extractCatalog(
                List.of(buildChunk("Bảng giá Gạch", TILE_KB_TABLE)),
                buildTilePersona().resolvedConfig().resolvedScopeTaxonomy()
        );
        List<PackageCatalogService.PackageSummary> eduCatalog = PackageCatalogService.extractCatalog(
                List.of(buildChunk("Bảng học phí", EDU_KB_TABLE)),
                buildEduPersona().resolvedConfig().resolvedScopeTaxonomy()
        );

        // 1. Spa: Chấp nhận thành phần cộng hợp lệ 10p + 70p = 80p; chặn thông số bịa 45p
        List<String> validSpaAdditive = CleanAgentOutputRefereeAndSanitizer.checkCleanCatalogGrounding(
                List.of(new FanpageBotResponse.ReplyBubble(
                        "Dạ gói Massage chuyên sâu 80 phút (530k) gồm 10 phút ngâm chân thảo dược kết hợp 70 phút ấn huyệt trị liệu sâu ạ.", 0)),
                spaCatalog, spaCatalog, List.of(), 0, false, spaCatalog.get(1), false,
                "NONE", "Gói 80p gồm những gì?", false, false, false,
                "STAGE_1_INITIAL_CONSULTATION_AND_QUOTE", List.of(), ConversationState.StructuredBookingState.none(),
                buildSpaPersona().resolvedConfig()
        );
        assertTrue(validSpaAdditive.isEmpty(), "Thành phần 10p + 70p của gói 80p phải hợp lệ 100%: " + validSpaAdditive);

        List<String> invalidSpaSpec = CleanAgentOutputRefereeAndSanitizer.checkCleanCatalogGrounding(
                List.of(new FanpageBotResponse.ReplyBubble(
                        "Dạ gói Massage cơ bản 45 phút bên em có giá 399k ạ.", 0)),
                spaCatalog, spaCatalog, List.of(), 0, false, spaCatalog.get(0), false,
                "NONE", "Tư vấn gói massage", false, false, false,
                "STAGE_1_INITIAL_CONSULTATION_AND_QUOTE", List.of(), ConversationState.StructuredBookingState.none(),
                buildSpaPersona().resolvedConfig()
        );
        assertTrue(invalidSpaSpec.stream().anyMatch(v -> v.startsWith("UNGROUNDED_PRODUCT_SPECIFICATION:45p")),
                "Thông số thời lượng bịa 45p phải bị chặn: " + invalidSpaSpec);

        // 2. Gạch ốp lát: Chấp nhận 80x80cm hộp 3 viên; chặn kích thước bịa 90x90cm và quy cách hộp 8 viên
        List<String> validTileSpec = CleanAgentOutputRefereeAndSanitizer.checkCleanCatalogGrounding(
                List.of(new FanpageBotResponse.ReplyBubble(
                        "Dạ mẫu Gạch Porcelain Vân Đá Trắng Khói 80x80cm (380k/m²) đóng quy cách hộp 3 viên rất tiện thi công phòng khách ạ.", 0)),
                tileCatalog, tileCatalog, List.of(), 0, false, tileCatalog.get(0), false,
                "NONE", "Tư vấn gạch phòng khách", false, false, false,
                "STAGE_1_INITIAL_CONSULTATION_AND_QUOTE", List.of(), ConversationState.StructuredBookingState.none(),
                buildTilePersona().resolvedConfig()
        );
        assertTrue(validTileSpec.isEmpty(), "Thông số 80x80cm và hộp 3 viên khớp Catalog phải hợp lệ: " + validTileSpec);

        List<String> invalidTileDimAndPack = CleanAgentOutputRefereeAndSanitizer.checkCleanCatalogGrounding(
                List.of(new FanpageBotResponse.ReplyBubble(
                        "Dạ mẫu gạch khổ lớn 90x90cm đóng hộp 8 viên bên em có đơn giá 380k/m² ạ.", 0)),
                tileCatalog, tileCatalog, List.of(), 0, false, tileCatalog.get(0), false,
                "NONE", "Tư vấn gạch phòng khách", false, false, false,
                "STAGE_1_INITIAL_CONSULTATION_AND_QUOTE", List.of(), ConversationState.StructuredBookingState.none(),
                buildTilePersona().resolvedConfig()
        );
        assertTrue(invalidTileDimAndPack.stream().anyMatch(v -> v.equals("UNGROUNDED_PRODUCT_SPECIFICATION:90x90cm")),
                "Kích thước gạch bịa 90x90cm phải bị chặn: " + invalidTileDimAndPack);
        assertTrue(invalidTileDimAndPack.stream().anyMatch(v -> v.equals("UNGROUNDED_PRODUCT_SPECIFICATION:hộp 8 viên")),
                "Quy cách đóng gói bịa hộp 8 viên phải bị chặn: " + invalidTileDimAndPack);

        // 3. Giáo dục: Chấp nhận thành phần cộng hợp lệ 20 buổi + 4 buổi = 24 buổi; chặn 48 buổi bịa đặt
        List<String> validEduAdditive = CleanAgentOutputRefereeAndSanitizer.checkCleanCatalogGrounding(
                List.of(new FanpageBotResponse.ReplyBubble(
                        "Dạ Khóa Tiếng Anh Giao Tiếp Cơ Bản 24 buổi (3.900k/khóa) gồm 20 buổi thực hành phản xạ + 4 buổi đánh giá chuẩn hóa phát âm ạ.", 0)),
                eduCatalog, eduCatalog, List.of(), 0, true, eduCatalog.get(1), false,
                "NONE", "Khóa giao tiếp học mấy buổi?", false, false, true,
                "STAGE_1_INITIAL_CONSULTATION_AND_QUOTE", List.of(), ConversationState.StructuredBookingState.none(),
                buildEduPersona().resolvedConfig()
        );
        assertTrue(validEduAdditive.isEmpty(), "Thành phần cộng 20 buổi + 4 buổi = 24 buổi phải hợp lệ: " + validEduAdditive);

        List<String> invalidEduSessions = CleanAgentOutputRefereeAndSanitizer.checkCleanCatalogGrounding(
                List.of(new FanpageBotResponse.ReplyBubble(
                        "Dạ khóa học lộ trình 48 buổi bên em có mức học phí 3.900k/khóa ạ.", 0)),
                eduCatalog, eduCatalog, List.of(), 0, true, eduCatalog.get(1), false,
                "NONE", "Báo học phí", false, false, false,
                "STAGE_1_INITIAL_CONSULTATION_AND_QUOTE", List.of(), ConversationState.StructuredBookingState.none(),
                buildEduPersona().resolvedConfig()
        );
        assertTrue(invalidEduSessions.stream().anyMatch(v -> v.equals("UNGROUNDED_PRODUCT_SPECIFICATION:48 buổi")),
                "Số buổi học bịa 48 buổi ngoài Catalog phải bị chặn: " + invalidEduSessions);
    }

    // =========================================================================
    // TEST 3: NHÓM 1 — CROSS_TIER_EXCLUSIVE_ATTRIBUTE_MISMATCH DẪN XUẤT TỪ CATALOG ĐA NGÀNH
    // =========================================================================
    @Test
    void testGroup1_CrossTierExclusiveAttributeMismatch_DataDrivenFromCatalog() {
        List<PackageCatalogService.PackageSummary> eduCatalog = PackageCatalogService.extractCatalog(
                List.of(buildChunk("Bảng học phí", EDU_KB_TABLE)),
                buildEduPersona().resolvedConfig().resolvedScopeTaxonomy()
        );
        List<PackageCatalogService.PackageSummary> tileCatalog = PackageCatalogService.extractCatalog(
                List.of(buildChunk("Bảng giá Gạch", TILE_KB_TABLE)),
                buildTilePersona().resolvedConfig().resolvedScopeTaxonomy()
        );
        List<PackageCatalogService.PackageSummary> spaCatalog = PackageCatalogService.extractCatalog(
                List.of(buildChunk("Bảng giá Spa", SPA_KB_TABLE)),
                buildSpaPersona().resolvedConfig().resolvedScopeTaxonomy()
        );

        // 1. Giáo dục: Khóa tiết kiệm ENG-GT01 (3.900k) lấy nhầm quyền lợi độc quyền "chấm chữa Writing 1-1" của IELTS-65 (6.500k)
        PackageCatalogService.PackageSummary gt01 = eduCatalog.stream()
                .filter(p -> "ENG-GT01".equalsIgnoreCase(p.code())).findFirst().orElseThrow();
        List<String> eduMismatch = CleanAgentOutputRefereeAndSanitizer.checkCleanCatalogGrounding(
                List.of(new FanpageBotResponse.ReplyBubble(
                        "Dạ Khóa Tiếng Anh Giao Tiếp Cơ Bản 24 buổi (3.900k/khóa) có kèm chấm chữa Writing 1-1 cùng giảng viên 8.5 và lớp sĩ số nhỏ 6 học viên ạ.", 0)),
                eduCatalog, List.of(gt01), List.of(), 2, true, gt01, false,
                "NONE", "Có khóa nào mềm hơn không?", true, false, false,
                "STAGE_3_DOWNSELL_OR_REPEAT_OBJECTION", List.of(), ConversationState.StructuredBookingState.none(),
                buildEduPersona().resolvedConfig()
        );
        assertTrue(eduMismatch.stream().anyMatch(v -> v.startsWith("CROSS_TIER_EXCLUSIVE_ATTRIBUTE_MISMATCH:ENG-GT01:")),
                "Khóa giao tiếp cơ bản tự nhận quyền lợi độc quyền của khóa IELTS 6.5 phải bị bắt: " + eduMismatch);

        // 2. Gạch ốp lát: Gạch Ceramic 60x60 (245k/m²) lấy nhầm thuộc tính độc quyền "xương đá Porcelain chống thấm" của GACH-8080 (380k/m²)
        PackageCatalogService.PackageSummary g6060 = tileCatalog.stream()
                .filter(p -> "GACH-6060".equalsIgnoreCase(p.code())).findFirst().orElseThrow();
        List<String> tileMismatch = CleanAgentOutputRefereeAndSanitizer.checkCleanCatalogGrounding(
                List.of(new FanpageBotResponse.ReplyBubble(
                        "Dạ mẫu Gạch Ceramic Vân Mây Tiêu Chuẩn 60x60cm (245k/m²) có kết cấu xương đá Porcelain chống thấm cực bền ạ.", 0)),
                tileCatalog, List.of(g6060), List.of(), 2, true, g6060, false,
                "NONE", "Có mẫu gạch nào rẻ hơn không?", true, false, false,
                "STAGE_3_DOWNSELL_OR_REPEAT_OBJECTION", List.of(), ConversationState.StructuredBookingState.none(),
                buildTilePersona().resolvedConfig()
        );
        assertTrue(tileMismatch.stream().anyMatch(v -> v.startsWith("CROSS_TIER_EXCLUSIVE_ATTRIBUTE_MISMATCH:GACH-6060:")),
                "Gạch Ceramic tự nhận xương đá Porcelain chống thấm của phân khúc cao hơn phải bị bắt: " + tileMismatch);

        // 3. Spa: Gói tiết kiệm DS02 (239k) lấy nhầm "ăn nhẹ thực dưỡng" hoặc "ngâm bồn thảo dược" của gói cao hơn
        PackageCatalogService.PackageSummary ds02 = spaCatalog.stream()
                .filter(p -> "DS02".equalsIgnoreCase(p.code())).findFirst().orElseThrow();
        List<String> spaMismatch = CleanAgentOutputRefereeAndSanitizer.checkCleanCatalogGrounding(
                List.of(new FanpageBotResponse.ReplyBubble(
                        "Dạ Gói dưỡng sinh cơ bản 60 phút (239k) bên em có ngâm bồn thảo dược và ăn nhẹ thực dưỡng ạ.", 0)),
                spaCatalog, List.of(ds02), List.of(), 2, true, ds02, false,
                "NONE", "Có gói tiết kiệm hơn không?", true, false, false,
                "STAGE_3_DOWNSELL_OR_REPEAT_OBJECTION", List.of(), ConversationState.StructuredBookingState.none(),
                buildSpaPersona().resolvedConfig()
        );
        assertTrue(spaMismatch.stream().anyMatch(v -> v.startsWith("CROSS_TIER_EXCLUSIVE_ATTRIBUTE_MISMATCH:DS02:")),
                "Gói DS02 tự nhận ngâm bồn thảo dược / ăn nhẹ thực dưỡng phải bị bắt: " + spaMismatch);
    }

    // =========================================================================
    // TEST 4: NHÓM 1 — UNPRESENTED_GHOST_PRODUCT_ON_CLOSING, BOOKING TRUTH & TIER_2 UNIT_NOUN
    // =========================================================================
    @Test
    void testGroup1_ClosingGhostProduct_BookingStateTruth_AndFlexibleTier2UnitNoun() {
        List<PackageCatalogService.PackageSummary> spaCatalog = PackageCatalogService.extractCatalog(
                List.of(buildChunk("Bảng giá Spa", SPA_KB_TABLE)),
                buildSpaPersona().resolvedConfig().resolvedScopeTaxonomy()
        );
        PackageCatalogService.PackageSummary ns01 = spaCatalog.stream().filter(p -> "NS01".equals(p.code())).findFirst().orElseThrow();
        PackageCatalogService.PackageSummary ds02 = spaCatalog.stream().filter(p -> "DS02".equals(p.code())).findFirst().orElseThrow();

        // 1. UNPRESENTED_GHOST_PRODUCT_ON_CLOSING ở STAGE_6 (lịch sử mới chỉ giới thiệu NS01 399k & DS02 239k, cấm bơm VIP99 799k)
        List<String> ghostViolations = CleanAgentOutputRefereeAndSanitizer.checkCleanCatalogGrounding(
                List.of(new FanpageBotResponse.ReplyBubble(
                        "Dạ mình cứ cân nhắc thêm nhé, ngoài ra bên em còn Liệu trình Hoàng Gia Đá Nóng 799k rất thích hợp để thư giãn sâu ạ.", 0)),
                spaCatalog, List.of(ns01, ds02), List.of(), 1, false, null, false,
                "NONE", "Ok để cân nhắc thêm", false, true, true,
                "STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE", List.of(ns01, ds02),
                ConversationState.StructuredBookingState.none(), buildSpaPersona().resolvedConfig()
        );
        assertTrue(ghostViolations.stream().anyMatch(v -> v.startsWith("UNPRESENTED_GHOST_PRODUCT_ON_CLOSING:")),
                "Nhắc gói bóng ma VIP99 (799k) ở STAGE_6 khi khách xin tạm dừng phải bị chặn: " + ghostViolations);

        // 2. FALSE_PRODUCT_SELECTION_CLAIM khi selectedProductId == null (AWAITING_PRODUCT_SELECTION)
        ConversationState.StructuredBookingState awaitingSelection = new ConversationState.StructuredBookingState(
                null, null, "tối nay", "2026-10-29", null, "Asia/Ho_Chi_Minh",
                ConversationState.BookingStatus.AWAITING_PRODUCT_SELECTION, List.of("NS01", "DS02")
        );
        List<String> falseSelectionFlags = CleanAgentOutputRefereeAndSanitizer.checkCleanCatalogGrounding(
                List.of(new FanpageBotResponse.ReplyBubble(
                        "Dạ em đã ghi nhận đặt gói Massage cơ bản 60 phút (399k) cho mình vào tối nay, mình ghé lúc mấy giờ ạ?", 0)),
                spaCatalog, List.of(ns01, ds02), List.of(), 1, false, null, false,
                "AWAITING_PRODUCT_SELECTION", "Đặt cho mình tối nay", false, true, false,
                "STAGE_4_POSITIVE_INTEREST_BOOKING_CLOSE", List.of(ns01, ds02),
                awaitingSelection, buildSpaPersona().resolvedConfig()
        );
        assertTrue(falseSelectionFlags.contains("FALSE_PRODUCT_SELECTION_CLAIM"),
                "Tự nhận khách đã chọn gói 399k khi selectedProductId == null phải bị chặn: " + falseSelectionFlags);

        // 3. UNVERIFIED_BOOKING_CONFIRMATION_CLAIM khi bookingStatus == AWAITING_AVAILABILITY_CHECK (!= CONFIRMED)
        ConversationState.StructuredBookingState awaitingAvailability = new ConversationState.StructuredBookingState(
                "DS02", "Gói dưỡng sinh cơ bản (239k)", "tối nay", "2026-10-29", "19:00", "Asia/Ho_Chi_Minh",
                ConversationState.BookingStatus.AWAITING_AVAILABILITY_CHECK, List.of("NS01", "DS02")
        );
        List<String> falseConfirmFlags = CleanAgentOutputRefereeAndSanitizer.checkCleanCatalogGrounding(
                List.of(new FanpageBotResponse.ReplyBubble(
                        "Dạ bên em đã đặt lịch thành công Gói dưỡng sinh cơ bản 60 phút (239k) lúc 19h tối nay cho mình rồi ạ.", 0)),
                spaCatalog, List.of(ds02), List.of(), 1, false, ds02, false,
                "AWAITING_AVAILABILITY_CHECK", "Đặt gói 239k tối nay lúc 7 giờ", false, true, false,
                "STAGE_4_POSITIVE_INTEREST_BOOKING_CLOSE", List.of(ns01, ds02),
                awaitingAvailability, buildSpaPersona().resolvedConfig()
        );
        assertTrue(falseConfirmFlags.contains("UNVERIFIED_BOOKING_CONFIRMATION_CLAIM"),
                "Tuyên bố đã đặt lịch thành công khi bookingStatus != CONFIRMED phải bị chặn: " + falseConfirmFlags);

        List<String> honestAvailabilityAckFlags = CleanAgentOutputRefereeAndSanitizer.checkCleanCatalogGrounding(
                List.of(new FanpageBotResponse.ReplyBubble(
                        "Dạ em đã ghi nhận yêu cầu đặt Gói dưỡng sinh cơ bản 60 phút (239k) lúc 19h tối nay và xin phép kiểm tra lịch trống thực tế trước khi gửi xác nhận chính thức cho mình ạ.", 0)),
                spaCatalog, List.of(ds02), List.of(), 1, false, ds02, false,
                "AWAITING_AVAILABILITY_CHECK", "Đặt gói 239k tối nay lúc 7 giờ", false, true, false,
                "STAGE_4_POSITIVE_INTEREST_BOOKING_CLOSE", List.of(ns01, ds02),
                awaitingAvailability, buildSpaPersona().resolvedConfig()
        );
        assertFalse(honestAvailabilityAckFlags.contains("UNVERIFIED_BOOKING_CONFIRMATION_CLAIM"),
                "Câu ghi nhận yêu cầu và hẹn kiểm tra lịch trống không được bị phạt nhầm: " + honestAvailabilityAckFlags);

        // 4. Linh hoạt hóa TIER_2_SINGLE_CANDIDATE theo unitNoun từng ngành ("mẫu", "khóa", "gói")
        List<PackageCatalogService.PackageSummary> tileCatalog = PackageCatalogService.extractCatalog(
                List.of(buildChunk("Bảng giá Gạch", TILE_KB_TABLE)),
                buildTilePersona().resolvedConfig().resolvedScopeTaxonomy()
        );
        List<PackageCatalogService.PackageSummary> eduCatalog = PackageCatalogService.extractCatalog(
                List.of(buildChunk("Bảng học phí", EDU_KB_TABLE)),
                buildEduPersona().resolvedConfig().resolvedScopeTaxonomy()
        );
        CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution tileTier2 =
                new CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution(
                        "mid_old_tile", null, true, false, null, List.of(), List.of(tileCatalog.get(0))
                );
        assertTrue(CleanAgentOutputRefereeAndSanitizer.checkReplyResolutionGuard(
                List.of(new FanpageBotResponse.ReplyBubble(
                        "Dạ anh/chị đang hỏi ưu đãi cho mẫu Gạch Porcelain Vân Đá Trắng Khói (380k/m²) hay một mẫu khác để em kiểm tra chuẩn xác ạ?", 0)),
                tileTier2, tileCatalog, "Mẫu này có chiết khấu không?", buildTilePersona().resolvedConfig()
        ).isEmpty(), "TIER_2_SINGLE_CANDIDATE phải chấp nhận câu hỏi làm rõ theo unitNoun 'mẫu'");

        CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution eduTier2 =
                new CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution(
                        "mid_old_edu", null, true, false, null, List.of(), List.of(eduCatalog.get(0))
                );
        assertTrue(CleanAgentOutputRefereeAndSanitizer.checkReplyResolutionGuard(
                List.of(new FanpageBotResponse.ReplyBubble(
                        "Dạ mình đang hỏi lịch khai giảng của Khóa Luyện Thi IELTS 6.5 Cam Kết (6.500k/khóa) hay khóa khác của trung tâm ạ?", 0)),
                eduTier2, eduCatalog, "Khóa này khi nào khai giảng?", buildEduPersona().resolvedConfig()
        ).isEmpty(), "TIER_2_SINGLE_CANDIDATE phải chấp nhận câu hỏi làm rõ theo unitNoun 'khóa'");

        assertTrue(CleanAgentOutputRefereeAndSanitizer.checkReplyResolutionGuard(
                List.of(new FanpageBotResponse.ReplyBubble(
                        "Dạ Khóa Luyện Thi IELTS 6.5 Cam Kết (6.500k/khóa) khai giảng vào thứ Hai tuần tới ạ.", 0)),
                eduTier2, eduCatalog, "Khóa này khi nào khai giảng?", buildEduPersona().resolvedConfig()
        ).contains("UNRESOLVED_OR_AMBIGUOUS_REPLY_GUARD_VIOLATION:TIER_2_SINGLE_CANDIDATE"),
                "Tự ý đoán 1 khóa mà không hỏi xác nhận khi reply_to.mid không khớp vẫn phải bị bắt lỗi thật!");
    }

    // =========================================================================
    // TEST 5: NHÓM 3 — TINH GIẢN CROSS_TURN_REPETITION (BỎ UNIGRAM 6 TỪ / 3 LƯỢT, CHẶN LẶP >= 90% LIỀN KỀ)
    // =========================================================================
    @Test
    void testGroup3_StreamlinedCrossTurnRepetition_EliminatesFalsePositive_Catches90PercentAdjacentVerbatim() {
        String turn1Quote = "Dạ với diện tích phòng khách rộng, em gợi ý anh/chị tham khảo mẫu Gạch Porcelain Vân Đá Trắng Khói kích thước 80x80cm đơn giá niêm yết 380k/m² chống trơn trượt rất tốt ạ.";

        // 1. Triệt tiêu False Positive: Nhắc lại cụm tên sản phẩm dài + thông số + đơn giá (> 6 từ trùng) để giải thích chi tiết đóng gói ở lượt kế tiếp
        List<FanpageBotResponse.ReplyBubble> detailFollowUp = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Dạ đúng rồi ạ, mẫu Gạch Porcelain Vân Đá Trắng Khói kích thước 80x80cm đơn giá niêm yết 380k/m² được đóng quy cách hộp 3 viên tương đương 1,92m² mỗi hộp để anh/chị dễ tính số lượng đặt hàng ạ.", 0)
        );
        assertFalse(CleanAgentOutputRefereeAndSanitizer.detectCleanCrossTurnRepetition(
                detailFollowUp, turn1Quote, List.of(new FanpageBotRequest.ConversationTurn("assistant", turn1Quote)), false
        ), "Nhắc lại tên mẫu gạch dài + kích thước + đơn giá ở lượt sau KHÔNG được báo False Positive CROSS_TURN_REPETITION");

        // 2. Triệt tiêu False Positive quét 3 lượt cũ: Lượt t-2 có câu tương tự nhưng lượt liền kề t-1 là trả lời giờ mở cửa
        String turnMinus1Hours = "Dạ showroom bên em mở cửa đón khách xem mẫu thực tế từ 8h sáng đến 20h tối tất cả các ngày trong tuần ạ.";
        List<FanpageBotRequest.ConversationTurn> threeTurnHistory = List.of(
                new FanpageBotRequest.ConversationTurn("assistant", turn1Quote),
                new FanpageBotRequest.ConversationTurn("user", "Showroom mấy giờ đóng cửa?"),
                new FanpageBotRequest.ConversationTurn("assistant", turnMinus1Hours),
                new FanpageBotRequest.ConversationTurn("user", "Nhắc lại giúp anh mẫu gạch vừa nãy")
        );
        assertFalse(CleanAgentOutputRefereeAndSanitizer.detectCleanCrossTurnRepetition(
                List.of(new FanpageBotResponse.ReplyBubble(turn1Quote, 0)),
                turnMinus1Hours,
                threeTurnHistory,
                false
        ), "Không quét ngược 3 lượt lịch sử gây phạt oan khi lượt liền kề t-1 hoàn toàn khác");

        // 3. Lỗi thật vẫn bị bắt: Lặp nguyên văn toàn bộ bong bóng >= 90% so với ngay lượt liền kề t-1
        List<FanpageBotResponse.ReplyBubble> verbatimAdjacentRepeat = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Dạ với diện tích phòng khách rộng, em gợi ý anh/chị tham khảo mẫu Gạch Porcelain Vân Đá Trắng Khói kích thước 80x80cm đơn giá niêm yết 380k/m² chống trơn trượt rất tốt nhé ạ.", 0)
        );
        assertTrue(CleanAgentOutputRefereeAndSanitizer.detectCleanCrossTurnRepetition(
                verbatimAdjacentRepeat, turn1Quote, List.of(new FanpageBotRequest.ConversationTurn("assistant", turn1Quote)), false
        ), "Lặp nguyên văn >= 90% bong bóng của lượt liền kề ngay trước đó bắt buộc phải bị bắt CROSS_TURN_REPETITION!");
    }

    // =========================================================================
    // TEST 6: NHÓM 3 — ĐẠI TỪ SAI VAI / THIẾU CHỦ NGỮ SỬA TẠI CHỖ 0ms, KHÔNG GỌI LẠI LLM LẦN 2
    // =========================================================================
    @Test
    void testGroup3_WrongPronounOpener_ZeroMillisecondInPlaceRepair_ZeroLlmRegeneration() {
        LlmClient mockLlm = mock(LlmClient.class);
        when(mockLlm.name()).thenReturn("ollama:qwen3.8-flash");
        AtomicInteger llmCallCount = new AtomicInteger(0);
        when(mockLlm.complete(anyString(), any(ReasoningBudget.class))).thenAnswer(inv -> {
            llmCallCount.incrementAndGet();
            return """
                    {
                      "reply_bubbles": [
                        {"text": "Dạ mình đã hiểu rồi ạ, mẫu Gạch Porcelain Vân Đá Trắng Khói 80x80cm bên em có đơn giá 380k/m² với bề mặt nhám R11 chống trơn rất an toàn cho gia đình ạ.", "typing_delay_ms": 0}
                      ],
                      "brain_reflection": {
                        "observed_fact": "slot_target_scope=phòng khách",
                        "care_note_delta": "",
                        "working_hypothesis": "Khách cần gạch phòng khách chống trơn",
                        "intended_speech_act": "GENERAL_CONSULTATION"
                      },
                      "should_handoff": false
                    }
                    """;
        });

        AgentBrainService brainService = new AgentBrainService();
        CleanAgentOrchestratorService orchestrator = new CleanAgentOrchestratorService(mockLlm, brainService, null);
        PromptBuilder.PagePersona tilePersona = buildTilePersona();
        List<ReciprocalRankFusion.FusedChunk> tileChunks = List.of(buildChunk("Bảng giá Gạch", TILE_KB_TABLE));

        FanpageBotResponse response = executeSingleTurn(
                orchestrator, UUID.randomUUID(), "sender-c3d-pronoun", "conv-c3d-pronoun", true,
                "Anh cần tìm gạch lát phòng khách chống trơn", null, null, "anh/chị",
                List.of(), tilePersona, tileChunks
        );

        assertEquals(1, llmCallCount.get(),
                "Lỗi mở đầu 'Dạ mình đã hiểu rồi ạ' phải được sửa tại chỗ 0ms, TUYỆT ĐỐI KHÔNG gọi LLM lần thứ 2!");
        assertFalse(response.replyText().toLowerCase().contains("dạ mình đã hiểu"),
                "Đầu ra cuối cùng tuyệt đối không còn đại từ sai vai 'Dạ mình đã hiểu': " + response.replyText());
        assertTrue(response.replyText().startsWith("Dạ em hiểu"),
                "Đầu ra cuối cùng phải được chuẩn hóa thành 'Dạ em hiểu...': " + response.replyText());
    }

    // =========================================================================
    // TEST 7: NHÓM 3 — XÓA KHỐI REGEX CỨNG < 350_000L SPA, ĐỐI CHIẾU THEO CommercialRole.ECONOMICAL_OPTION
    // =========================================================================
    @Test
    void testGroup3_Deleted350kHardcodedSpaRegex_PreservesLowPriceTileAnchor_RepairsHighPriceEconomicalCourse() {
        List<PackageCatalogService.PackageSummary> tileCatalog = PackageCatalogService.extractCatalog(
                List.of(buildChunk("Bảng giá Gạch", TILE_KB_TABLE)),
                buildTilePersona().resolvedConfig().resolvedScopeTaxonomy()
        );
        // Tạo mẫu gạch chủ lực giá 220k/m² (< 350_000L) mang vai trò PRIMARY_ANCHOR
        String primaryLowPriceTileKb = """
                | Mã SP | Tên mẫu | Kích thước | Đơn giá | Khu vực sử dụng | Phân khúc |
                | --- | --- | --- | --- | --- | --- |
                | GACH-STD | Gạch Men Mờ Chống Trơn Sân Vườn | 50x50cm | 220k/m² | sân vườn | sản phẩm chính |
                """;
        PackageCatalogService.PackageSummary primaryLowPriceTile = PackageCatalogService.extractCatalog(
                List.of(buildChunk("Gạch sân vườn", primaryLowPriceTileKb)),
                List.of("sân vườn")
        ).get(0);
        assertNotEquals(PackageCatalogService.CommercialRole.ECONOMICAL_OPTION, primaryLowPriceTile.commercialRole());
        assertTrue(primaryLowPriceTile.priceAmountVnd() < 350_000L);

        // 1. Triệt tiêu False Positive: Sản phẩm chủ lực ngành Gạch có giá < 350k KHÔNG bị regex cũ ghi đè thành câu "chăm sóc vùng đầu – cổ – vai – gáy"
        String tileSentence = "Dạ 220k/m² là mức giá thấp nhất bên em cho dòng Gạch Men Mờ Chống Trơn Sân Vườn chính hãng ạ.";
        String sanitizedTile = CleanAgentOutputRefereeAndSanitizer.sanitizeMinorCleanAgentWordingInPlace(
                tileSentence, "em", "anh/chị", primaryLowPriceTile, false, "NONE"
        );
        assertEquals(tileSentence, sanitizedTile,
                "Sản phẩm PRIMARY_ANCHOR dưới 350k của ngành Gạch tuyệt đối không được bị biến dạng!");
        assertFalse(sanitizedTile.contains("vai – gáy") || sanitizedTile.contains("trọn gói"));

        // 2. Lỗi thật vẫn bị bắt ở mọi thang giá (> 350_000L): Khóa học tiết kiệm ENG-GT01 (3.900k/khóa, ECONOMICAL_OPTION)
        List<PackageCatalogService.PackageSummary> eduCatalog = PackageCatalogService.extractCatalog(
                List.of(buildChunk("Bảng học phí", EDU_KB_TABLE)),
                buildEduPersona().resolvedConfig().resolvedScopeTaxonomy()
        );
        PackageCatalogService.PackageSummary economicalCourse = eduCatalog.stream()
                .filter(p -> "ENG-GT01".equalsIgnoreCase(p.code())).findFirst().orElseThrow();
        assertEquals(PackageCatalogService.CommercialRole.ECONOMICAL_OPTION, economicalCourse.commercialRole());
        assertTrue(economicalCourse.priceAmountVnd() > 350_000L);

        String falseLowestEdu = "Dạ 6.500k/khóa là mức giá thấp nhất bên em hiện tại cho người mới bắt đầu rồi ạ.";
        String sanitizedEdu = CleanAgentOutputRefereeAndSanitizer.sanitizeMinorCleanAgentWordingInPlace(
                falseLowestEdu, "em", "mình", economicalCourse, false, "NONE"
        );
        assertFalse(sanitizedEdu.contains("mức giá thấp nhất"),
                "Khẳng định sai 'mức giá thấp nhất' khi đang cầm ECONOMICAL_OPTION 3.900k phải được sửa chuẩn xác");
        assertTrue(sanitizedEdu.contains("Khóa Tiếng Anh Giao Tiếp Cơ Bản") && sanitizedEdu.contains("3.900k/khóa"),
                "Câu thay thế phải dựng từ dữ liệu cấu trúc 4 lớp của khóa học 3.900k/khóa: " + sanitizedEdu);
        assertFalse(sanitizedEdu.contains("vai – gáy"),
                "Tuyệt đối không còn bất kỳ từ ngữ Spa nào trong câu sửa của ngành Giáo dục: " + sanitizedEdu);
        assertEquals(2, tileCatalog.size());
    }

    // =========================================================================
    // TEST 8: KIỂM THỬ ĐẦU-CUỐI (E2E) TRÊN CẢ 3 NGÀNH + GIẢ LẬP LLM LỖI KÍCH HOẠT STRUCTURAL FALLBACK
    // =========================================================================
    @Test
    void testEndToEnd_ThreeIndustries_Spa_BuildingMaterials_Education_AndSimulatedLlmFailureFallback() {
        AgentBrainService brainService = new AgentBrainService();

        // ---------------------------------------------------------------------
        // NGÀNH 1: NÀNG SEN SPA (Khám phá -> Báo giá 399k -> Chê đắt -> Hỏi giờ -> "Biết thế" -> "Ok để cân nhắc thêm")
        // ---------------------------------------------------------------------
        LlmClient spaLlm = mock(LlmClient.class);
        when(spaLlm.name()).thenReturn("ollama:qwen3.8-flash");
        CleanAgentOrchestratorService spaOrchestrator = new CleanAgentOrchestratorService(spaLlm, brainService, null);
        UUID spaPageId = UUID.randomUUID();
        String spaSender = "e2e-spa-user";
        String spaConv = "e2e-spa-conv";
        PromptBuilder.PagePersona spaPersona = buildSpaPersona();
        List<ReciprocalRankFusion.FusedChunk> spaChunks = List.of(buildChunk("Bảng giá Spa", SPA_KB_TABLE));
        List<FanpageBotRequest.ConversationTurn> spaHistory = new ArrayList<>();

        // Lượt 1: Báo giá gói 399k
        when(spaLlm.complete(anyString(), any(ReasoningBudget.class))).thenReturn("""
                {"reply_bubbles":[{"text":"Dạ với tình trạng mỏi cổ vai gáy do ngồi máy tính cả tuần, em gợi ý mình gói Massage cơ bản 60 phút (399k trọn gói, bao gồm ấn huyệt chuyên sâu và ăn nhẹ thực dưỡng) ạ.","typing_delay_ms":0}],
                 "brain_reflection":{"observed_fact":"slot_target_scope=Khách mỏi cổ vai gáy cả tuần","care_note_delta":"Mỏi cổ vai gáy do ngồi máy tính","working_hypothesis":"Khách cần giải tỏa mỏi cổ vai gáy","intended_speech_act":"OFFERED_PACKAGE_DETAILS"},"should_handoff":false}
                """);
        FanpageBotResponse spaT1 = executeSingleTurn(spaOrchestrator, spaPageId, spaSender, spaConv, true,
                "Mình bị mỏi cổ vai gáy do ngồi máy tính cả tuần rồi, tư vấn giá giúp mình", null, null, "mình", spaHistory, spaPersona, spaChunks);
        assertTrue(spaT1.replyText().contains("399k"));
        spaHistory.add(new FanpageBotRequest.ConversationTurn("user", "Mình bị mỏi cổ vai gáy do ngồi máy tính cả tuần rồi, tư vấn giá giúp mình"));
        spaHistory.add(new FanpageBotRequest.ConversationTurn("assistant", spaT1.replyText()));

        // Lượt 2: Chê đắt lần 1 -> STAGE_2 giữ giá trị 399k
        when(spaLlm.complete(anyString(), any(ReasoningBudget.class))).thenReturn("""
                {"reply_bubbles":[{"text":"Dạ mức 399k là trọn gói 60 phút kỹ thuật viên ấn miết sâu vùng cổ vai gáy kèm bữa ăn nhẹ thực dưỡng, mình thử trải nghiệm 1 buổi trước để cảm nhận nhé ạ?","typing_delay_ms":0}],
                 "brain_reflection":{"observed_fact":"slot_budget_sensitivity=Khách băn khoăn mức 399k","care_note_delta":"","working_hypothesis":"Khách còn đắn đo chi phí 399k","intended_speech_act":"INVITED_TRIAL_OR_ASKED_TIME"},"should_handoff":false}
                """);
        FanpageBotResponse spaT2 = executeSingleTurn(spaOrchestrator, spaPageId, spaSender, spaConv, false,
                "399k hơi cao nhỉ", null, null, "mình", spaHistory, spaPersona, spaChunks);
        assertFalse(spaT2.replyText().contains("239k"), "Chê đắt lần 1 không được tự ý hạ bậc xuống 239k");
        spaHistory.add(new FanpageBotRequest.ConversationTurn("user", "399k hơi cao nhỉ"));
        spaHistory.add(new FanpageBotRequest.ConversationTurn("assistant", spaT2.replyText()));

        // Lượt 3: "Biết thế" sau khi bot bảo vệ giá -> AgentBrain giải mã ngữ cảnh chuyển sang STAGE_3B gợi ý nhẹ DS02 239k
        when(spaLlm.complete(anyString(), any(ReasoningBudget.class))).thenReturn("""
                {"reply_bubbles":[{"text":"Dạ nếu mình muốn tham khảo phương án nhẹ nhàng tiết kiệm hơn tập trung riêng vùng cổ vai gáy thì bên em có Gói dưỡng sinh cơ bản 60 phút (239k trọn gói) nhé ạ.","typing_delay_ms":0}],
                 "brain_reflection":{"observed_fact":"","care_note_delta":"","working_hypothesis":"Khách lưỡng lự chi phí sau khi nghe giải thích gói 399k","intended_speech_act":"OFFERED_PACKAGE_DETAILS"},"should_handoff":false}
                """);
        FanpageBotResponse spaT3 = executeSingleTurn(spaOrchestrator, spaPageId, spaSender, spaConv, false,
                "Biết thế", null, null, "mình", spaHistory, spaPersona, spaChunks);
        assertTrue(spaT3.replyText().contains("239k"));
        spaHistory.add(new FanpageBotRequest.ConversationTurn("user", "Biết thế"));
        spaHistory.add(new FanpageBotRequest.ConversationTurn("assistant", spaT3.replyText()));

        // Lượt 4: "Ok để cân nhắc thêm" — giả lập LLM cố tình nhắc gói bóng ma VIP99 799k cả 2 lần -> Referee C3-D chặn & dựng câu chào ấm áp STAGE_6 không có bóng ma!
        when(spaLlm.complete(anyString(), any(ReasoningBudget.class))).thenReturn("""
                {"reply_bubbles":[{"text":"Dạ mình cứ cân nhắc nhé, ngoài ra bên em còn Liệu trình Hoàng Gia Đá Nóng 799k rất sướng ạ.","typing_delay_ms":0}],
                 "brain_reflection":{"observed_fact":"","care_note_delta":"","working_hypothesis":"Khách tạm dừng suy nghĩ thêm","intended_speech_act":"RESPECTED_PAUSE_WITH_SOFT_OPTION"},"should_handoff":false}
                """);
        FanpageBotResponse spaT4 = executeSingleTurn(spaOrchestrator, spaPageId, spaSender, spaConv, false,
                "Ok để cân nhắc thêm", null, null, "mình", spaHistory, spaPersona, spaChunks);
        assertFalse(spaT4.replyText().contains("799k") || spaT4.replyText().contains("Hoàng Gia"),
                "STAGE_6 bắt buộc chặn sạch sản phẩm bóng ma 799k chưa từng xuất hiện trong lịch sử: " + spaT4.replyText());
        assertFalse(spaT4.needsHumanHandoff(), "Khách chào tạm dừng phải nhận lời chào ấm áp, không bị ném sang SAFE_HANDOFF");

        // ---------------------------------------------------------------------
        // NGÀNH 2: VẬT LIỆU XÂY DỰNG — GẠCH ỐP LÁT (ADD bộ nhớ phòng khách + phòng bếp -> Đặt hàng AWAITING_AVAILABILITY_CHECK)
        // ---------------------------------------------------------------------
        LlmClient tileLlm = mock(LlmClient.class);
        when(tileLlm.name()).thenReturn("ollama:qwen3.8-flash");
        CleanAgentOrchestratorService tileOrchestrator = new CleanAgentOrchestratorService(tileLlm, brainService, null);
        UUID tilePageId = UUID.randomUUID();
        String tileSender = "e2e-tile-user";
        String tileConv = "e2e-tile-conv";
        PromptBuilder.PagePersona tilePersona = buildTilePersona();
        List<ReciprocalRankFusion.FusedChunk> tileChunks = List.of(buildChunk("Bảng giá Gạch", TILE_KB_TABLE));
        List<FanpageBotRequest.ConversationTurn> tileHistory = new ArrayList<>();

        when(tileLlm.complete(anyString(), any(ReasoningBudget.class))).thenReturn("""
                {"reply_bubbles":[{"text":"Dạ với gia đình có bà bầu cần lát nền phòng khách chống trơn, em gợi ý anh/chị mẫu Gạch Porcelain Vân Đá Trắng Khói 80x80cm (380k/m², bề mặt nhám R11 chống trơn) rất an toàn ạ.","typing_delay_ms":0}],
                 "brain_reflection":{"observed_fact":"slot_target_scope=Lát gạch phòng khách chống trơn","care_note_delta":"Vợ đang bầu cần gạch chống trơn phòng khách","working_hypothesis":"Khách ưu tiên gạch chống trơn an toàn cho phòng khách","intended_speech_act":"OFFERED_PACKAGE_DETAILS"},"should_handoff":false}
                """);
        FanpageBotResponse tileT1 = executeSingleTurn(tileOrchestrator, tilePageId, tileSender, tileConv, true,
                "Vợ anh đang bầu nên cần tìm gạch lát phòng khách chống trơn, báo giá giúp anh", null, null, "anh/chị",
                tileHistory, tilePersona, tileChunks);
        assertTrue(tileT1.replyText().contains("380k/m²"));
        assertFalse(tileT1.needsHumanHandoff(), "Câu 'vợ anh đang bầu tìm gạch chống trơn' không bị chặn nhầm bởi cổng y khoa Spa");
        tileHistory.add(new FanpageBotRequest.ConversationTurn("user", "Vợ anh đang bầu nên cần tìm gạch lát phòng khách chống trơn, báo giá giúp anh"));
        tileHistory.add(new FanpageBotRequest.ConversationTurn("assistant", tileT1.replyText()));

        // Lượt 2: Bổ sung thêm phòng bếp (Memory ADD) và chốt đơn giao sáng mai 9h -> AWAITING_AVAILABILITY_CHECK
        when(tileLlm.complete(anyString(), any(ReasoningBudget.class))).thenReturn("""
                {"reply_bubbles":[{"text":"Dạ em đã ghi nhận yêu cầu đặt mẫu Gạch Porcelain Vân Đá Trắng Khói 80x80cm (380k/m²) cho cả phòng khách và phòng bếp, giao vào 9h sáng mai của anh/chị ạ. Em xin phép kiểm tra tồn kho và lịch xe giao thực tế rồi báo xác nhận chính thức ngay với anh/chị nhé ạ.","typing_delay_ms":0}],
                 "brain_reflection":{"observed_fact":"slot_target_scope=Ốp lát thêm khu vực phòng bếp","care_note_delta":"Lát cả phòng khách và phòng bếp","working_hypothesis":"Khách chốt mẫu 80x80cm giao 9h sáng mai","intended_speech_act":"INVITED_TRIAL_OR_ASKED_TIME"},"should_handoff":false}
                """);
        FanpageBotResponse tileT2 = executeSingleTurn(tileOrchestrator, tilePageId, tileSender, tileConv, false,
                "Anh muốn lát thêm cả phòng bếp nữa, chốt mẫu 80x80 giao 9h sáng mai nhé", null, null, "anh/chị",
                tileHistory, tilePersona, tileChunks);
        ConversationState.StructuredBookingState tileBooking = tileOrchestrator.getLastTurnBookingState();
        assertEquals("GACH-8080", tileBooking.selectedProductId());
        assertEquals("sáng mai", tileBooking.bookingDate());
        assertEquals("09:00", tileBooking.bookingTime());
        assertEquals(ConversationState.BookingStatus.AWAITING_AVAILABILITY_CHECK, tileBooking.status());
        List<String> tileFacts = brainService.getRecalledVerifiedFacts(tilePageId, tileSender);
        assertTrue(tileFacts.stream().anyMatch(f -> f.toLowerCase().contains("phòng khách"))
                        && tileFacts.stream().anyMatch(f -> f.toLowerCase().contains("phòng bếp")),
                "Bộ nhớ ADD phải giữ đồng thời cả phòng khách lẫn phòng bếp: " + tileFacts);

        // ---------------------------------------------------------------------
        // NGÀNH 3: GIÁO DỤC — TRUNG TÂM NGOẠI NGỮ + GIẢ LẬP LLM TIMEOUT KHI QUOTE-REPLY (reply_to.mid)
        // ---------------------------------------------------------------------
        LlmClient eduLlm = mock(LlmClient.class);
        when(eduLlm.name()).thenReturn("ollama:qwen3.8-flash");
        CleanAgentOrchestratorService eduOrchestrator = new CleanAgentOrchestratorService(eduLlm, brainService, null);
        UUID eduPageId = UUID.randomUUID();
        String eduSender = "e2e-edu-user";
        String eduConv = "e2e-edu-conv";
        PromptBuilder.PagePersona eduPersona = buildEduPersona();
        List<ReciprocalRankFusion.FusedChunk> eduChunks = List.of(buildChunk("Bảng học phí", EDU_KB_TABLE));
        List<FanpageBotRequest.ConversationTurn> eduHistory = new ArrayList<>();

        // Lượt 1: Tư vấn khóa IELTS 6.5 (6.500k/khóa, 36 buổi)
        when(eduLlm.complete(anyString(), any(ReasoningBudget.class))).thenReturn("""
                {"reply_bubbles":[{"text":"Dạ với mục tiêu người đi làm cần đạt IELTS 6.5, em gợi ý mình Khóa Luyện Thi IELTS 6.5 Cam Kết lộ trình 36 buổi (6.500k/khóa, lớp 6 học viên kèm chấm chữa Writing 1-1) ạ.","typing_delay_ms":0}],
                 "brain_reflection":{"observed_fact":"slot_target_scope=Người đi làm mục tiêu luyện thi IELTS","care_note_delta":"Người đi làm mục tiêu IELTS 6.5","working_hypothesis":"Khách quan tâm lộ trình IELTS 6.5","intended_speech_act":"OFFERED_PACKAGE_DETAILS"},"should_handoff":false}
                """);
        FanpageBotResponse eduT1 = executeSingleTurn(eduOrchestrator, eduPageId, eduSender, eduConv, true,
                "Mình là người đi làm muốn luyện thi IELTS lên 6.5, tư vấn học phí giúp mình", null, null, "mình",
                eduHistory, eduPersona, eduChunks);
        assertTrue(eduT1.replyText().contains("6.500k/khóa"));

        eduHistory.add(new FanpageBotRequest.ConversationTurn("user", "Mình là người đi làm muốn luyện thi IELTS lên 6.5, tư vấn học phí giúp mình"));
        eduHistory.add(new FanpageBotRequest.ConversationTurn("assistant", eduT1.replyText()));

        // Lượt 2: Giả lập LLM hoàn toàn timeout / ném RuntimeException khi khách quote-reply vào mid_edu_ielts_65 hỏi "Khóa này có giảm giá không?"
        when(eduLlm.complete(anyString(), any(ReasoningBudget.class))).thenThrow(new RuntimeException("Simulated Ollama HTTP 504 Gateway Timeout"));
        when(eduLlm.complete(anyString())).thenThrow(new RuntimeException("Simulated Ollama HTTP 504 Gateway Timeout"));

        FanpageBotResponse eduFallbackResp = executeSingleTurn(eduOrchestrator, eduPageId, eduSender, eduConv, false,
                "Khóa này đang có chương trình giảm giá không bạn?", "mid_edu_ielts_65", eduT1.replyText(), "mình",
                eduHistory, eduPersona, eduChunks);

        assertNotNull(eduFallbackResp);
        assertFalse(eduFallbackResp.replyBubbles().isEmpty(), "Khi LLM lỗi, tầng Fallback Cấu Trúc Đa Ngành An Toàn phải trả về câu trả lời hợp lệ");
        assertTrue(eduFallbackResp.replyText().contains("Khóa Luyện Thi IELTS 6.5 Cam Kết"),
                "Fallback khi LLM lỗi vẫn phải giữ đúng khoá học được quote từ reply_to.mid: " + eduFallbackResp.replyText());
        assertTrue(eduFallbackResp.replyText().contains("6.500k/khóa"),
                "Fallback phải báo đúng học phí niêm yết 6.500k/khóa từ PackageSummary.display(): " + eduFallbackResp.replyText());
        assertFalse(eduFallbackResp.replyText().toLowerCase().contains("liệu trình")
                        || eduFallbackResp.replyText().toLowerCase().contains("nhận tip")
                        || eduFallbackResp.replyText().toLowerCase().contains("chăm sóc vùng"),
                "Fallback ngành Giáo dục tuyệt đối không chứa bất kỳ từ ngữ Spa nào: " + eduFallbackResp.replyText());
    }
}
