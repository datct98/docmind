package com.example.docmind.service.bot;

import com.example.docmind.dto.bot.CustomerInsightDto;
import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.dto.bot.FanpageBotResponse;
import com.example.docmind.repository.RetrievedChunk;
import com.example.docmind.service.retrieval.PromptBuilder;
import com.example.docmind.service.retrieval.ReciprocalRankFusion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CleanAgentArchitectureVerificationTest {

    private static List<PackageCatalogService.PackageSummary> sampleSpaCatalog() {
        return List.of(
                new PackageCatalogService.PackageSummary(
                        "HN_DS01",
                        "DS01",
                        "HN",
                        "Lấy ráy tai cơ bản",
                        "ear_care",
                        List.of("tai"),
                        30,
                        "30 phút",
                        129_000L,
                        "129k",
                        "Phòng chung",
                        "Lấy ráy tai nhẹ nhàng + ăn nhẹ & trái cây theo mùa",
                        "Khách muốn thư giãn tai",
                        "Vệ sinh tai ngoài thư giãn",
                        false
                ),
                new PackageCatalogService.PackageSummary(
                        "HN_DS02",
                        "DS02",
                        "HN",
                        "Gói dưỡng sinh cơ bản",
                        "hair_neck_shoulder",
                        List.of("đầu", "cổ", "vai", "gáy", "tóc", "da đầu"),
                        60,
                        "60 phút",
                        239_000L,
                        "239k",
                        "Khu gội chung",
                        "Gội đầu cơ bản + massage cổ vai gáy + ăn nhẹ & trái cây theo mùa",
                        "Dân văn phòng đau mỏi nhẹ",
                        "Gội đầu dưỡng sinh kết hợp massage cổ vai gáy",
                        false
                ),
                new PackageCatalogService.PackageSummary(
                        "HN_NS01",
                        "NS01",
                        "HN",
                        "Massage cơ bản",
                        "body_therapy",
                        List.of("toàn thân", "body", "cổ", "vai", "gáy", "đầu", "lưng", "eo", "chân", "gối", "bắp chân", "tay"),
                        60,
                        "60 phút",
                        399_000L,
                        "399k",
                        "Phòng chung (có rèm riêng)",
                        "Full body massage trị liệu cơ bản + ăn nhẹ thực dưỡng",
                        "Đau mỏi cổ vai gáy, lưng, chân tay",
                        "Ấn huyệt làm mềm cơ toàn thân, tập trung kỹ vùng đau mỏi",
                        false
                ),
                new PackageCatalogService.PackageSummary(
                        "HN_NS02",
                        "NS02",
                        "HN",
                        "Massage cơ bản nâng cao",
                        "body_therapy",
                        List.of("toàn thân", "body", "cổ", "vai", "gáy", "đầu", "lưng", "eo", "chân", "gối", "bắp chân", "tay"),
                        80,
                        "80 phút",
                        530_000L,
                        "530k",
                        "Phòng chung (có rèm riêng)",
                        "10p ngâm bồn thảo dược + tắm tráng + 70p massage + ăn nhẹ",
                        "Đau mỏi nhiều cần ngâm thảo dược",
                        "Ngâm bồn thảo dược ấm và massage trị liệu chuyên sâu",
                        false
                ),
                new PackageCatalogService.PackageSummary(
                        "HN_NS03",
                        "NS03",
                        "HN",
                        "Massage phục hồi",
                        "body_therapy",
                        List.of("toàn thân", "body", "cổ", "vai", "gáy", "đầu", "lưng", "eo", "chân", "gối", "bắp chân", "tay"),
                        100,
                        "100 phút",
                        580_000L,
                        "580k",
                        "Phòng VIP riêng",
                        "10p ngâm bồn thảo dược + 5p xông hơi + 85p massage phục hồi + ăn nhẹ",
                        "Khách cần phòng riêng và phục hồi chuyên sâu",
                        "Ngâm thảo dược, xông hơi và massage phục hồi toàn diện",
                        false
                )
        );
    }

    @Test
    void testTieredCatalogRanking_NeckPain_Primary399kFirst_Downsell239kSecond_EarExcluded() {
        List<PackageCatalogService.PackageSummary> allCatalog = sampleSpaCatalog();

        CleanAgentCatalogAndPromptBuilder.TieredCatalogResult result = CleanAgentCatalogAndPromptBuilder.rankAndTierCatalogForCleanAgent(
                allCatalog,
                List.of("đau mỏi cổ", "nhức vai", "nhức đầu"),
                List.of("cổ", "vai", "gáy", "đầu"),
                Set.of(),
                false
        );

        assertNotNull(result);
        assertFalse(result.noMatchingPackageForRequestedScope());

        // 1. Gói ráy tai 129k (DS01) phải bị loại vào excludedCatalog
        assertTrue(
                result.excludedCatalog().stream().anyMatch(p -> "DS01".equals(p.code())),
                "Gói ráy tai DS01 (129k) phải nằm trong excludedCatalog khi khách đau cổ vai gáy"
        );
        assertFalse(
                result.allAllowedPackages().stream().anyMatch(p -> "DS01".equals(p.code())),
                "Gói ráy tai DS01 (129k) tuyệt đối không được lọt vào danh sách cho phép"
        );

        // 2. Nhóm 1 (Primary Recommendations) phải xếp gói tiêu chuẩn 60 phút NS01 (399k) lên đầu tiên
        assertFalse(result.primaryRecommendations().isEmpty());
        assertEquals("NS01", result.primaryRecommendations().get(0).code());
        assertEquals(399_000L, result.primaryRecommendations().get(0).priceVnd());

        // 3. Nhóm 2 (Economical Downsell) phải chứa gói DS02 (239k) dành cho bước hạ bậc khi khách chê đắt lần 2
        assertFalse(result.economicalAlternatives().isEmpty());
        assertEquals("DS02", result.economicalAlternatives().get(0).code());
        assertEquals(239_000L, result.economicalAlternatives().get(0).priceVnd());
    }

    @Test
    void testTieredCatalogRanking_LimbCramps_OnlyBodyPackages_NoEarOrHeadOnlyDownsell() {
        List<PackageCatalogService.PackageSummary> allCatalog = sampleSpaCatalog();

        // Khách báo "hay bị chuột rút và nhức đầu" -> ConversationState.extractTargetScopes trích xuất ["chân", "đầu"]
        List<String> extractedScopes = ConversationState.extractTargetScopes("Tôi hay bị chuột rút và nhức đầu", List.of());
        assertTrue(extractedScopes.contains("chân"), "Triệu chứng chuột rút phải ánh xạ ra phạm vi 'chân'");
        assertTrue(extractedScopes.contains("đầu"), "Triệu chứng nhức đầu phải ánh xạ ra phạm vi 'đầu'");

        CleanAgentCatalogAndPromptBuilder.TieredCatalogResult result = CleanAgentCatalogAndPromptBuilder.rankAndTierCatalogForCleanAgent(
                allCatalog,
                List.of("chuột rút", "nhức đầu"),
                extractedScopes,
                Set.of(),
                false
        );

        // Cả DS01 (129k - chỉ làm tai) và DS02 (239k - chỉ gội đầu & cổ vai, không có vùng chân) đều phải bị loại!
        assertTrue(
                result.excludedCatalog().stream().anyMatch(p -> "DS01".equals(p.code())),
                "DS01 (129k) phải bị loại"
        );
        assertTrue(
                result.excludedCatalog().stream().anyMatch(p -> "DS02".equals(p.code())),
                "DS02 (239k - không chăm sóc vùng chân bị chuột rút) phải bị loại để tránh hạ bậc sai vùng"
        );

        // Nhóm 1 chỉ giữ các gói Full Body chăm sóc đủ cả chân và đầu/cổ vai (NS01 399k đứng đầu)
        assertEquals(List.of("NS01", "NS02", "NS03"),
                result.primaryRecommendations().stream().map(PackageCatalogService.PackageSummary::code).toList());
        // Nhóm 2 (Economical Downsell) phải rỗng vì không có gói nào rẻ hơn 399k mà chăm sóc được vùng chân bị chuột rút
        assertTrue(result.economicalAlternatives().isEmpty(),
                "Nhóm 2 phải rỗng khi khách bị chuột rút chân (tránh hạ bậc nhầm sang gói gội đầu 239k)");
    }

    @Test
    void testStageGatedContext_5Stages_AndAgentBrainInsight_AndCompactDocContext() {
        PromptBuilder.PagePersona spaPersona = new PromptBuilder.PagePersona(
                "Nàng Sen Spa",
                "Spa dưỡng sinh trị liệu cổ truyền",
                "Dân văn phòng và khách đau mỏi cơ xương khớp",
                "Tinh tế, giàu cảm xúc, chu đáo"
        );
        List<PackageCatalogService.PackageSummary> allCatalog = sampleSpaCatalog();

        // 1. Tình huống 1 (priceObjectionCount == 0): Nhóm 1 ưu tiên 60p 399k đứng số 1, ẩn Nhóm 2
        String promptStage1 = CleanAgentCatalogAndPromptBuilder.buildCleanAgentPrompt(
                spaPersona,
                "chị",
                List.of("đau mỏi cổ", "nhức gáy"),
                List.of("cổ", "vai", "gáy"),
                "mới bị vài hôm",
                true,
                "Massage cơ bản 60 phút",
                "399k",
                0,
                false,
                "NONE",
                allCatalog,
                List.of(),
                List.of(),
                "Tư vấn gói phù hợp giúp chị"
        );
        assertTrue(promptStage1.contains("<!-- SYSTEM_USER_SPLIT -->"));
        assertTrue(promptStage1.contains("<AGENT_BRAIN_INSIGHT>"));
        assertTrue(promptStage1.contains("1. verified_customer_facts:"));
        assertTrue(promptStage1.contains("2. current_stage_and_buying_state:"));
        assertTrue(promptStage1.contains("3. turn_tactical_focus:"));
        assertTrue(promptStage1.contains("4. contrastive_reference:"));
        assertTrue(promptStage1.contains("1. Massage cơ bản — Thời lượng: 60 phút — Giá trọn gói: 399k"),
                "Tình huống 1 phải xếp gói tiêu chuẩn 60p 399k ở vị trí số 1");
        assertTrue(promptStage1.contains("2. Massage cơ bản nâng cao — Thời lượng: 80 phút — Giá trọn gói: 530k"),
                "Tình huống 1 phải xếp gói 80p 530k ở vị trí số 2");
        String userPartStage1 = promptStage1.substring(promptStage1.indexOf("<!-- SYSTEM_USER_SPLIT -->"));
        assertFalse(userPartStage1.contains("NHÓM 2"),
                "Tình huống 1 (báo giá lần đầu) phải ẩn Nhóm 2 khỏi <SCOPE_FILTERED_CATALOG> để tránh tự gạ hạ gói sớm");
        assertFalse(userPartStage1.contains("239k"),
                "Tình huống 1 (báo giá lần đầu) không được hiển thị gói 239k của Nhóm 2");

        // 2. Tình huống 2 (priceObjectionCount == 1 và khách chưa xin gói rẻ hơn): Chỉ truyền heldPackage, ẨN HOÀN TOÀN Nhóm 2
        String promptStage2 = CleanAgentCatalogAndPromptBuilder.buildCleanAgentPrompt(
                spaPersona,
                "chị",
                List.of("đau mỏi cổ", "nhức gáy"),
                List.of("cổ", "vai", "gáy"),
                "mới bị vài hôm",
                true,
                "Massage cơ bản 60 phút",
                "399k",
                1,
                false,
                "NONE",
                allCatalog,
                List.of(),
                List.of(),
                "Giá 399k hơi cao nhỉ"
        );
        String userPartStage2 = promptStage2.substring(promptStage2.indexOf("<!-- SYSTEM_USER_SPLIT -->"));
        assertTrue(userPartStage2.contains("[GÓI ĐANG CÂN NHẮC — GIỮ NGUYÊN GIÁ TRỊ Ở LẦN BĂN KHOĂN GIÁ ĐẦU TIÊN"));
        assertTrue(userPartStage2.contains("Massage cơ bản — Thời lượng: 60 phút — Giá trọn gói: 399k"));
        assertFalse(userPartStage2.contains("NHÓM 2"),
                "Tình huống 2 (chê đắt lần 1) phải ẩn hoàn toàn Nhóm 2 khỏi <SCOPE_FILTERED_CATALOG>");
        assertFalse(userPartStage2.contains("239k"),
                "Tình huống 2 (chê đắt lần 1) không được lộ giá gói hạ bậc 239k trong Prompt");

        // 3. Tình huống 3 (khách chủ động xin gói rẻ hơn HOẶC priceObjectionCount >= 2): Mở khóa Nhóm 2 (DS02 - 239k)
        String promptStage3 = CleanAgentCatalogAndPromptBuilder.buildCleanAgentPrompt(
                spaPersona,
                "chị",
                List.of("đau mỏi cổ", "nhức gáy"),
                List.of("cổ", "vai", "gáy"),
                "mới bị vài hôm",
                true,
                "Massage cơ bản 60 phút",
                "399k",
                2,
                false,
                "NONE",
                allCatalog,
                List.of(),
                List.of(),
                "Có gói nào rẻ hơn tầm 200k không em?"
        );
        assertTrue(promptStage3.contains("[NHÓM 2 — PHƯƠNG ÁN TIẾT KIỆM / HẠ BẬC"));
        assertTrue(promptStage3.contains("Gói dưỡng sinh cơ bản — Thời lượng: 60 phút — Giá trọn gói: 239k"),
                "Tình huống 3 phải mở khóa Nhóm 2 (DS02 - 239k)");

        // 4. Tình huống 5 (khách hỏi vặn suy đoán của bot): Tạm ẩn danh sách chào bán gói
        String promptStage5 = CleanAgentCatalogAndPromptBuilder.buildCleanAgentPrompt(
                spaPersona,
                "chị",
                List.of("đau mỏi cổ"),
                List.of("cổ", "gáy"),
                "chưa có",
                false,
                "null",
                "null",
                0,
                false,
                "NONE",
                allCatalog,
                List.of(),
                List.of(),
                "Sao biết hay vậy, đoán mò à?"
        );
        assertTrue(promptStage5.contains("Tạm ẩn danh sách chào bán gói ở lượt này"),
                "Tình huống 5 (khách hỏi vặn) phải tạm ẩn danh sách chào bán gói");
    }

    @Test
    void testEmotionalLanguageAllowed_ClinicalClaimsBlocked_PronounsAndRepairIsolated() {
        List<PackageCatalogService.PackageSummary> allCatalog = sampleSpaCatalog();
        CleanAgentCatalogAndPromptBuilder.TieredCatalogResult tiered = CleanAgentCatalogAndPromptBuilder.rankAndTierCatalogForCleanAgent(
                allCatalog,
                List.of("đau mỏi cổ"),
                List.of("cổ", "vai", "gáy"),
                Set.of(),
                false
        );

        // 1. CHO PHÉP HOÀN TOÀN ngôn từ cảm xúc dịch vụ: "giảm đau mỏi hiệu quả", "mở từng điểm cơ căng cứng", "đào thải độc tố", "nhẹ nhõm từ bên trong"
        List<String> emotionalOk = CleanAgentOutputRefereeAndSanitizer.checkCleanCatalogGrounding(
                List.of(new FanpageBotResponse.ReplyBubble(
                        "Dạ gói Massage cơ bản 60 phút (399k) giúp kỹ thuật viên ấn miết mở từng điểm cơ căng cứng, đào thải độc tố và giảm đau mỏi hiệu quả để cơ thể nhẹ nhõm từ bên trong ạ.",
                        0
                )),
                allCatalog,
                tiered.allAllowedPackages(),
                tiered.excludedCatalog()
        );
        assertTrue(
                emotionalOk.isEmpty(),
                "Ngôn từ cảm xúc dịch vụ ('giảm đau mỏi hiệu quả', 'mở từng điểm cơ căng cứng') phải được cho phép hoàn toàn, nhưng lại bị chặn: " + emotionalOk
        );

        // 2. CHỈ CHẶN khi cam kết chữa khỏi bệnh lý y khoa lâm sàng ("chữa khỏi hẳn bệnh", "điều trị dứt điểm bệnh")
        List<String> clinicalViolations = CleanAgentOutputRefereeAndSanitizer.checkCleanCatalogGrounding(
                List.of(new FanpageBotResponse.ReplyBubble("Dạ liệu trình bên em cam kết chữa khỏi hẳn bệnh thoái hóa đốt sống cổ ạ.", 0)),
                allCatalog,
                tiered.allAllowedPackages(),
                tiered.excludedCatalog()
        );
        assertTrue(
                clinicalViolations.stream().anyMatch(v -> v.startsWith("FORBIDDEN_MEDICAL_CLAIM:")),
                "Phải chặn cam kết chữa khỏi bệnh lý y khoa lâm sàng: " + clinicalViolations
        );

        // 3. Bắt mọi biến thể đại từ sai vai ("Dạ mình đã hiểu rồi ạ", "Dạ mình hiểu ạ", "Dạ hiểu rồi ạ") và chuẩn hóa thành "Dạ em hiểu ạ"
        List<String> pronounViolations1 = CleanAgentOutputRefereeAndSanitizer.checkCleanCatalogGrounding(
                List.of(new FanpageBotResponse.ReplyBubble("Dạ mình đã hiểu rồi ạ, để em kiểm tra cho chị nhé.", 0)),
                allCatalog,
                tiered.allAllowedPackages(),
                tiered.excludedCatalog()
        );
        assertTrue(pronounViolations1.contains("FORBIDDEN_WRONG_PRONOUN_OR_SUBJECTLESS_OPENER"));

        assertEquals(
                "Dạ em hiểu ạ, tình trạng này chắc làm chị mỏi lắm.",
                CleanAgentOutputRefereeAndSanitizer.sanitizeMinorCleanAgentWordingInPlace("Dạ mình đã hiểu rồi ạ, tình trạng này chắc làm chị mỏi lắm.", "em")
        );
        assertEquals(
                "Dạ em hiểu ạ.",
                CleanAgentOutputRefereeAndSanitizer.sanitizeMinorCleanAgentWordingInPlace("Dạ hiểu rồi ạ.", "em")
        );

        // 4. Chặn tự ý gạ gói rẻ hơn ở lần chê đắt đầu tiên (PREMATURE_DOWNSELL_ON_FIRST_OBJECTION) và chặn tự mâu thuẫn (vừa gạ gói rẻ vừa hỏi giờ đặt lịch)
        List<String> prematureDownsellViolations = CleanAgentOutputRefereeAndSanitizer.checkCleanCatalogGrounding(
                List.of(new FanpageBotResponse.ReplyBubble("Dạ hay để em gợi ý gói tiết kiệm hơn cho mình trải nghiệm thử nhé?", 0)),
                allCatalog,
                tiered.primaryRecommendations(),
                tiered.excludedCatalog(),
                1,
                false
        );
        assertTrue(prematureDownsellViolations.contains("PREMATURE_DOWNSELL_ON_FIRST_OBJECTION"),
                "Phải chặn tự gạ gói tiết kiệm hơn ở lần chê đắt đầu tiên: " + prematureDownsellViolations);

        List<String> contradictoryViolations = CleanAgentOutputRefereeAndSanitizer.checkCleanCatalogGrounding(
                List.of(
                        new FanpageBotResponse.ReplyBubble("Dạ để em gợi ý gói tiết kiệm hơn cho mình nhé.", 0),
                        new FanpageBotResponse.ReplyBubble("Hôm nay chị tiện ghé vào khung giờ nào ạ?", 2000)
                ),
                allCatalog,
                tiered.allAllowedPackages(),
                tiered.excludedCatalog()
        );
        assertTrue(contradictoryViolations.contains("CONTRADICTORY_DOWNSELL_AND_BOOKING_CLOSE_IN_SAME_TURN"),
                "Phải chặn lỗi tự mâu thuẫn vừa gạ gói tiết kiệm hơn vừa hỏi khung giờ đặt lịch: " + contradictoryViolations);

        // 5. Chặn câu tự thú lặp lời ("tại em lỡ miệng nhắc lại thông tin cũ") và kiểm tra <REPAIR_INSTRUCTION>
        List<String> selfConfessionViolations = CleanAgentOutputRefereeAndSanitizer.checkCleanCatalogGrounding(
                List.of(new FanpageBotResponse.ReplyBubble("Dạ tại em lỡ miệng nhắc lại thông tin cũ nên mong chị thông cảm ạ.", 0)),
                allCatalog,
                tiered.allAllowedPackages(),
                tiered.excludedCatalog()
        );
        assertTrue(selfConfessionViolations.contains("FORBIDDEN_INTERNAL_REPAIR_LEAK_SELF_CONFESSION"));

        String repairPrompt = CleanAgentOutputRefereeAndSanitizer.buildCleanRepairPrompt("PROMPT_GỐC", List.of("CROSS_TURN_REPETITION"), "em", "chị");
        assertTrue(repairPrompt.contains("CHỈ DẪN KỸ THUẬT NỘI BỘ: Khách hàng KHÔNG phàn nàn về việc lặp lời. Tuyệt đối CẤM viết câu xin lỗi kiểu 'tại em lỡ miệng nhắc lại thông tin cũ'."));

        // 6. Kiểm tra phân loại tín hiệu: "Đắt nhỉ", "Hơi cao", "Để xem đã", "Để tính đã", "Chưa biết" KHÔNG bị gán vào PACKAGE_ACCEPTANCE_OR_BOOKING_PROGRESS
        for (String msg : List.of("Đắt nhỉ", "Hơi cao", "Để xem đã", "Để tính đã", "Chưa biết")) {
            List<String> sigs = CleanAgentOrchestratorService.classifyCleanCurrentTurnSignals(
                    msg, false, false, false, false,
                    CleanAgentOrchestratorService.isCleanPriceObjectionMessage(msg),
                    1, false, false, List.of("đau gáy"), List.of("gáy"), "mới bị"
            );
            assertFalse(sigs.contains("PACKAGE_ACCEPTANCE_OR_BOOKING_PROGRESS"),
                    "Tin nhắn '" + msg + "' tuyệt đối không được gán vào PACKAGE_ACCEPTANCE_OR_BOOKING_PROGRESS: " + sigs);
        }

        // "Ừ, nghe ổn", "Nghe ổn hơn đó" -> POSITIVE_INTEREST_READY_FOR_TRIAL_INVITE
        for (String posMsg : List.of("Ừ, nghe ổn", "Nghe ổn hơn đó")) {
            List<String> sigs = CleanAgentOrchestratorService.classifyCleanCurrentTurnSignals(
                    posMsg, false, false, false, false,
                    false, 0, true, true, List.of("đau gáy"), List.of("gáy"), "mới bị"
            );
            assertTrue(sigs.contains("POSITIVE_INTEREST_READY_FOR_TRIAL_INVITE"),
                    "Tin nhắn '" + posMsg + "' phải được phân loại là POSITIVE_INTEREST_READY_FOR_TRIAL_INVITE: " + sigs);
        }
    }

    @Test
    void testSingleBubbleRepetitionDetected() {
        String prevBotTurn = "Gói Massage cơ bản 60 phút bên em tập trung ấn huyệt làm mềm cơ vùng cổ vai gáy giúp mình nhẹ đầu hơn, đi kèm phần ăn nhẹ ấm bụng ạ.";
        List<FanpageBotResponse.ReplyBubble> singleBubbleCandidate = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Dạ gói Massage cơ bản 60 phút bên em tập trung ấn huyệt làm mềm cơ vùng cổ vai gáy giúp mình nhẹ đầu hơn, đi kèm phần ăn nhẹ ấm bụng nhé ạ.",
                        0
                )
        );

        boolean repeated = CleanAgentOutputRefereeAndSanitizer.detectCleanCrossTurnRepetition(
                singleBubbleCandidate,
                prevBotTurn,
                List.of(new FanpageBotRequest.ConversationTurn("assistant", prevBotTurn))
        );
        assertTrue(repeated, "Phải phát hiện lặp câu giữa các lượt ngay cả khi LLM chỉ sinh 1 bong bóng duy nhất");
    }

    @Test
    void testPromptHasZeroHardcodedSpaPolicyOnDentalPage() {
        PromptBuilder.PagePersona dentalPersona = new PromptBuilder.PagePersona(
                "Nha Khoa Tỏa Sáng",
                "Nha khoa thẩm mỹ & trồng răng Implant",
                "Khách hàng niềng răng, bọc răng sứ, tẩy trắng răng",
                "Chuyên nghiệp, tận tâm, tư vấn rõ ràng"
        );
        List<PackageCatalogService.PackageSummary> dentalCatalog = List.of(
                new PackageCatalogService.PackageSummary(
                        "DN_TW01",
                        "TW01",
                        "HN",
                        "Tẩy trắng răng Laser Whitening",
                        "dental_care",
                        List.of("răng", "men răng"),
                        45,
                        "45 phút",
                        1_500_000L,
                        "1500k",
                        "Ghế nha khoa tiêu chuẩn vô trùng",
                        "Lấy cao răng siêu âm + chiếu đèn Laser + bộ kít duy trì tại nhà",
                        "Răng ố vàng, xỉn màu do cà phê",
                        "Chiếu đèn Laser hoạt hóa gel tẩy trắng an toàn cho men răng",
                        false
                )
        );

        for (int objectionCount : List.of(0, 1)) {
            String dentalPrompt = CleanAgentCatalogAndPromptBuilder.buildCleanAgentPrompt(
                    dentalPersona,
                    "anh",
                    List.of("ê buốt răng", "ố vàng"),
                    List.of("răng"),
                    "mới bị vài tuần",
                    true,
                    objectionCount == 0 ? "null" : "Tẩy trắng răng Laser Whitening",
                    objectionCount == 0 ? "null" : "1500k",
                    objectionCount,
                    false,
                    "NONE",
                    dentalCatalog,
                    List.of(),
                    List.of(),
                    objectionCount == 0 ? "Tư vấn gói tẩy trắng răng giúp anh" : "Giá 1500k hơi cao nhỉ"
            );
            String lower = dentalPrompt.toLowerCase();
            assertTrue(dentalPrompt.contains("Nha khoa thẩm mỹ & trồng răng Implant"),
                    "Phải tiêm động persona_style_context của Page Nha khoa");
            for (String forbiddenSpaKeyword : List.of(
                    "massage", "dưỡng sinh", "cổ vai gáy", "thực dưỡng",
                    "rèm riêng", "phòng riêng kín đáo", "ráy tai", "ngâm bồn", "thảo dược"
            )) {
                assertFalse(
                        lower.contains(forbiddenSpaKeyword),
                        "Prompt trên Page Nha khoa tuyệt đối không được chứa từ khóa Spa '" + forbiddenSpaKeyword + "' (objectionCount=" + objectionCount + ")"
                );
            }
        }
    }

    @Test
    void testScopeMatchedChunkFiltering_ExcludesVipChunksWhenConsultingStandardPackage() {
        PromptBuilder.PagePersona spaPersona = new PromptBuilder.PagePersona(
                "Nàng Sen Spa",
                "Spa dưỡng sinh trị liệu cổ truyền",
                "Khách đau mỏi cổ vai gáy",
                "Tinh tế, giàu cảm xúc"
        );
        List<PackageCatalogService.PackageSummary> allCatalog = sampleSpaCatalog();

        ReciprocalRankFusion.FusedChunk vipFaqChunk = new ReciprocalRankFusion.FusedChunk(
                new RetrievedChunk(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        List.of("FAQ", "Hỏi đáp gói VIP"),
                        List.of(),
                        1,
                        null,
                        "Câu hỏi FAQ (match_prices=[580000, 670000, 780000], match_codes=[NS03, NS04]): Gói NS03 (580k) và NS04 (670k) được bố trí Phòng VIP riêng tư kín đáo và xông hơi đá muối.",
                        0.92
                ),
                0.92
        );
        ReciprocalRankFusion.FusedChunk standardProcessChunk = new ReciprocalRankFusion.FusedChunk(
                new RetrievedChunk(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        List.of("Quy trình", "Gói tiêu chuẩn"),
                        List.of(),
                        1,
                        null,
                        "Quy trình gói NS01 (399k) và DS02 (239k): Kỹ thuật viên ấn huyệt chăm sóc kỹ vùng cổ vai gáy, cam kết trọn gói không nhận Tip.",
                        0.88
                ),
                0.88
        );

        String promptStandard = CleanAgentCatalogAndPromptBuilder.buildCleanAgentPrompt(
                spaPersona,
                "chị",
                List.of("đau mỏi cổ"),
                List.of("cổ", "vai", "gáy"),
                "mới bị vài hôm",
                true,
                "Massage cơ bản 60 phút",
                "399k",
                1,
                false,
                "NONE",
                allCatalog,
                List.of(vipFaqChunk, standardProcessChunk),
                List.of(),
                "Giá 399k hơi cao nhỉ"
        );

        assertFalse(
                promptStandard.contains("Phòng VIP riêng tư"),
                "Chunk VIP (NS03/NS04 - 580k/670k/780k) phải bị loại khỏi <DOCUMENT_CONTEXT> khi đang tư vấn gói cơ bản 399k"
        );
        assertTrue(
                promptStandard.contains("cam kết trọn gói không nhận Tip"),
                "Chunk khớp với gói đang tư vấn (NS01 - 399k) phải được giữ lại trong <DOCUMENT_CONTEXT>"
        );
    }

    @Test
    void testCrossTierAmenityGuard_BlocksPrivateRoomAndMacrobioticFoodOnBasicPackages() {
        List<PackageCatalogService.PackageSummary> allCatalog = sampleSpaCatalog();
        PackageCatalogService.PackageSummary ds02Basic239k = allCatalog.stream()
                .filter(p -> "DS02".equals(p.code()))
                .findFirst()
                .orElseThrow();
        PackageCatalogService.PackageSummary ns01Standard399k = allCatalog.stream()
                .filter(p -> "NS01".equals(p.code()))
                .findFirst()
                .orElseThrow();

        // 1. Chặn bịa "phòng riêng" trên gói 399k (vốn là Phòng chung (có rèm riêng)) và gói 239k (Khu gội chung)
        List<String> privateRoomOn399k = CleanAgentOutputRefereeAndSanitizer.checkCleanCatalogGrounding(
                List.of(new FanpageBotResponse.ReplyBubble(
                        "Dạ gói Massage cơ bản 60 phút (399k) bên em sẽ chuẩn bị phòng riêng kín đáo cho chị ạ.",
                        0
                )),
                allCatalog,
                List.of(ns01Standard399k),
                List.of(),
                1,
                false,
                ns01Standard399k,
                false
        );
        assertTrue(
                privateRoomOn399k.stream().anyMatch(v -> v.startsWith("UNGROUNDED_PRIVATE_ROOM")),
                "Phải bắt lỗi UNGROUNDED_PRIVATE_ROOM khi bịa 'phòng riêng' cho gói 399k: " + privateRoomOn399k
        );

        String sanitized399k = CleanAgentOutputRefereeAndSanitizer.sanitizeMinorCleanAgentWordingInPlace(
                "Em sẽ chuẩn bị phòng riêng kín đáo cho mình ạ.",
                "em",
                "chị",
                ns01Standard399k,
                false
        );
        assertFalse(sanitized399k.toLowerCase().contains("phòng riêng"),
                "Hàm chuẩn hóa phải thay thế 'phòng riêng kín đáo' bằng đúng customerFacingSpaceNote của gói 399k: " + sanitized399k);
        assertTrue(sanitized399k.contains(ns01Standard399k.customerFacingSpaceNote()));

        // 2. Chặn bịa "thực dưỡng" và "phòng riêng" trên gói 239k (DS02 chỉ có ăn nhẹ & trái cây theo mùa, Khu gội chung)
        List<String> mismatchOn239k = CleanAgentOutputRefereeAndSanitizer.checkCleanCatalogGrounding(
                List.of(new FanpageBotResponse.ReplyBubble(
                        "Dạ Gói dưỡng sinh cơ bản 60 phút (239k) có phòng riêng kín đáo và bữa ăn nhẹ thực dưỡng sau liệu trình ạ.",
                        0
                )),
                allCatalog,
                List.of(ds02Basic239k),
                List.of(),
                2,
                true,
                ds02Basic239k,
                false
        );
        assertTrue(
                mismatchOn239k.stream().anyMatch(v -> v.startsWith("UNGROUNDED_PRIVATE_ROOM")),
                "Phải bắt lỗi UNGROUNDED_PRIVATE_ROOM trên gói 239k: " + mismatchOn239k
        );
        assertTrue(
                mismatchOn239k.stream().anyMatch(v -> v.startsWith("CROSS_TIER_AMENITY_MISMATCH")),
                "Phải bắt lỗi CROSS_TIER_AMENITY_MISMATCH khi gán 'thực dưỡng' vào gói 239k: " + mismatchOn239k
        );

        String sanitized239k = CleanAgentOutputRefereeAndSanitizer.sanitizeMinorCleanAgentWordingInPlace(
                "Em sẽ chuẩn bị phòng riêng kín đáo và bữa ăn nhẹ thực dưỡng cho mình ạ.",
                "em",
                "chị",
                ds02Basic239k,
                false
        );
        assertFalse(sanitized239k.toLowerCase().contains("phòng riêng"));
        assertFalse(sanitized239k.toLowerCase().contains("thực dưỡng"));
        assertTrue(sanitized239k.contains(ds02Basic239k.customerFacingSpaceNote()));
        assertTrue(sanitized239k.contains(ds02Basic239k.includesSummary()));
    }

    @Test
    void testProactiveRecommendation_BlocksMinhDangQuanTamWhenHeldPkgIsNull() {
        PromptBuilder.PagePersona spaPersona = new PromptBuilder.PagePersona(
                "Nàng Sen Spa",
                "Spa dưỡng sinh trị liệu cổ truyền",
                "Khách đau mỏi cổ vai gáy",
                "Tinh tế, giàu cảm xúc"
        );
        List<PackageCatalogService.PackageSummary> allCatalog = sampleSpaCatalog();

        // 1. Khi heldPkg == null ("null"), <STRUCTURED_CONVERSATION_STATE> phải ghi rõ chỉ thị chủ động đề xuất gói số 1
        String promptTurn2 = CleanAgentCatalogAndPromptBuilder.buildCleanAgentPrompt(
                spaPersona,
                "chị",
                List.of("đau nhức gáy"),
                List.of("cổ", "gáy"),
                "mới vài hôm nay",
                true,
                "null",
                "null",
                0,
                false,
                "NONE",
                allCatalog,
                List.of(),
                List.of(),
                "Mới bị vài hôm nay thôi em"
        );
        assertTrue(
                promptTurn2.contains("KHÁCH MỚI CHIA SẺ NHU CẦU, CHƯA BIẾT TÊN GÓI — BẠN PHẢI CHỦ ĐỘNG ĐỀ XUẤT GÓI SỐ 1"),
                "Khi heldPkg == null phải ghi rõ chỉ thị chủ động đề xuất gói số 1 trong <STRUCTURED_CONVERSATION_STATE>"
        );

        // 2. Trọng tài checkCleanCatalogGrounding bắt lỗi FALSE_PRIOR_PACKAGE_INTEREST_ASSUMPTION khi heldPkg == null
        List<String> violations = CleanAgentOutputRefereeAndSanitizer.checkCleanCatalogGrounding(
                List.of(new FanpageBotResponse.ReplyBubble(
                        "Dạ mình đang quan tâm đến gói Massage cơ bản 60 phút (399k) đúng không ạ?",
                        0
                )),
                allCatalog,
                allCatalog,
                List.of()
        );
        assertTrue(
                violations.contains("FALSE_PRIOR_PACKAGE_INTEREST_ASSUMPTION"),
                "Phải bắt lỗi FALSE_PRIOR_PACKAGE_INTEREST_ASSUMPTION khi heldPkg == null mà viết 'Mình đang quan tâm đến gói...'"
        );

        // 3. sanitizeMinorCleanAgentWordingInPlace tự động đổi thành "em gợi ý mình tham khảo gói"
        String rewritten = CleanAgentOutputRefereeAndSanitizer.sanitizeMinorCleanAgentWordingInPlace(
                "Dạ mình đang quan tâm đến gói Massage cơ bản 60 phút (399k) ạ.",
                "em"
        );
        assertEquals(
                "Dạ em gợi ý mình tham khảo gói Massage cơ bản 60 phút (399k) ạ.",
                rewritten
        );

        // 4. Khi khách đã hạ bậc sang gói Nhóm 2 (priceObjectionCount == 2) rồi đồng thuận ("Ừ, nghe ổn hơn đó"):
        // Turn 5 phải vào STAGE_4_POSITIVE_TRIAL_OR_BOOKING và khóa chặt vào gói Nhóm 2 (DS02 - 239k)
        String promptTurn5AfterDownsell = CleanAgentCatalogAndPromptBuilder.buildCleanAgentPrompt(
                spaPersona,
                "chị",
                List.of("đau nhức gáy"),
                List.of("cổ", "vai", "gáy"),
                "mới vài hôm nay",
                true,
                "Massage cơ bản 60 phút",
                "399k",
                2,
                true,
                "COLLECTING_VISIT_TIME",
                allCatalog,
                List.of(),
                List.of(),
                "Ừ, nghe ổn hơn đó"
        );
        assertTrue(promptTurn5AfterDownsell.contains("STAGE_4_POSITIVE_TRIAL_OR_BOOKING"));
        assertTrue(promptTurn5AfterDownsell.contains("[GÓI KHÁCH ĐANG QUAN TÂM / ĐẶT LỊCH TRẢI NGHIỆM]:"));
        assertTrue(promptTurn5AfterDownsell.contains("Gói dưỡng sinh cơ bản — Thời lượng: 60 phút — Giá trọn gói: 239k"),
                "Turn 5 sau khi hạ bậc phải khóa chặt vào gói Nhóm 2 (239k) để hỏi giờ đặt lịch, không bắt khách chọn lại");
    }

    @Test
    void testBrainReflectionParser_FaultTolerant_WhenMissingOrMalformed() {
        List<PackageCatalogService.PackageSummary> allCatalog = sampleSpaCatalog();
        CleanAgentCatalogAndPromptBuilder.TieredCatalogResult tiered = CleanAgentCatalogAndPromptBuilder.rankAndTierCatalogForCleanAgent(
                allCatalog,
                List.of("đau mỏi cổ"),
                List.of("cổ", "vai", "gáy"),
                Set.of(),
                false
        );

        // 1. JSON hợp lệ có đầy đủ "reply_bubbles" + "brain_reflection"
        String validJsonWithReflection = """
                {
                  "reply_bubbles": [
                    {"text": "Dạ gặp tình trạng đau mỏi cổ vai gáy như vậy chắc hẳn gây nhiều bất tiện cho chị lắm ạ.", "typing_delay_ms": 0},
                    {"text": "Tình trạng này của chị mới xuất hiện mấy hôm nay hay đã kéo dài lâu rồi ạ?", "typing_delay_ms": 2000}
                  ],
                  "brain_reflection": {
                    "observed_fact": "Khách làm văn phòng, đau mỏi cổ vai gáy",
                    "care_note_delta": "Khách ngồi máy tính nhiều, đau mỏi cổ vai gáy — ưu tiên thao tác ấn miết nhẹ nhàng",
                    "working_hypothesis": "Khách cần liệu trình thư giãn giảm căng cứng vùng cổ vai gáy"
                  },
                  "should_handoff": false
                }
                """;
        CleanAgentOutputRefereeAndSanitizer.StructuredLlmOutput parsedValid = CleanAgentOutputRefereeAndSanitizer.parseStructuredLlmJsonOutput(validJsonWithReflection, 10_000);
        assertTrue(parsedValid.parsedFromJson());
        assertEquals(2, parsedValid.bubbles().size());
        assertNotNull(parsedValid.brainReflection());
        assertEquals("Khách làm văn phòng, đau mỏi cổ vai gáy", parsedValid.brainReflection().observedFact());
        assertEquals("Khách ngồi máy tính nhiều, đau mỏi cổ vai gáy — ưu tiên thao tác ấn miết nhẹ nhàng", parsedValid.brainReflection().careNoteDelta());
        assertEquals("Khách cần liệu trình thư giãn giảm căng cứng vùng cổ vai gáy", parsedValid.brainReflection().workingHypothesis());

        // 2. Khi "brain_reflection" bị thiếu, null, sai kiểu dữ liệu (mảng, chuỗi, số) -> vẫn giữ nguyên "reply_bubbles"
        for (String variant : List.of(
                "{\"reply_bubbles\": [{\"text\": \"Dạ em chào chị ạ.\", \"typing_delay_ms\": 0}], \"should_handoff\": false}",
                "{\"reply_bubbles\": [{\"text\": \"Dạ em chào chị ạ.\", \"typing_delay_ms\": 0}], \"brain_reflection\": null, \"should_handoff\": false}",
                "{\"reply_bubbles\": [{\"text\": \"Dạ em chào chị ạ.\", \"typing_delay_ms\": 0}], \"brain_reflection\": \"sai_kieu_chuoi\", \"should_handoff\": false}",
                "{\"reply_bubbles\": [{\"text\": \"Dạ em chào chị ạ.\", \"typing_delay_ms\": 0}], \"brain_reflection\": [1, 2, 3], \"should_handoff\": false}",
                "{\"reply_bubbles\": [{\"text\": \"Dạ em chào chị ạ.\", \"typing_delay_ms\": 0}], \"brain_reflection\": {\"observed_fact\": [\"mang_loi\"], \"care_note_delta\": 12345}}"
        )) {
            CleanAgentOutputRefereeAndSanitizer.StructuredLlmOutput out = CleanAgentOutputRefereeAndSanitizer.parseStructuredLlmJsonOutput(variant, 10_000);
            assertTrue(out.parsedFromJson(), "Parser phải chịu lỗi với biến thể: " + variant);
            assertEquals(1, out.bubbles().size());
            assertEquals("Dạ em chào chị ạ.", out.bubbles().get(0).text());
            assertNotNull(out.brainReflection());
        }

        // 3. Khi phần "brain_reflection" ở cuối JSON bị cắt cụt do maxTokens -> vẫn cứu được nguyên vẹn "reply_bubbles"!
        String truncatedReflectionJson = "{\"reply_bubbles\": [{\"text\": \"Dạ gói Massage cơ bản 60 phút có giá 399k trọn gói ạ.\", \"typing_delay_ms\": 0}], \"brain_reflection\": {\"observed_fact\": \"Khách hỏi giá";
        CleanAgentOutputRefereeAndSanitizer.StructuredLlmOutput salvaged = CleanAgentOutputRefereeAndSanitizer.parseStructuredLlmJsonOutput(truncatedReflectionJson, 10_000);
        assertTrue(salvaged.parsedFromJson(), "Khi brain_reflection bị cắt cụt ở cuối, parser phải cứu được reply_bubbles");
        assertEquals(1, salvaged.bubbles().size());
        assertEquals("Dạ gói Massage cơ bản 60 phút có giá 399k trọn gói ạ.", salvaged.bubbles().get(0).text());

        // 4. Kiểm tra xác thực care_note_delta: chặn cam kết y khoa lâm sàng và tên gói bị loại (excludedCatalog)
        assertEquals(
                "",
                CleanAgentOutputRefereeAndSanitizer.validateCleanCareNoteDelta("Cam kết chữa khỏi hẳn bệnh thoái hóa đốt sống cổ cho khách", tiered.excludedCatalog()),
                "Phải loại bỏ care_note_delta chứa cam kết chữa khỏi bệnh lý y khoa"
        );
        assertEquals(
                "",
                CleanAgentOutputRefereeAndSanitizer.validateCleanCareNoteDelta("Khách hợp với gói Lấy ráy tai cơ bản DS01", tiered.excludedCatalog()),
                "Phải loại bỏ care_note_delta chứa tên/mã gói nằm trong excludedCatalog"
        );
        String validCareNote = CleanAgentOutputRefereeAndSanitizer.validateCleanCareNoteDelta(
                "Khách ngồi máy tính nhiều, khó ngủ — ưu tiên không gian yên tĩnh, thao tác nhẹ nhàng",
                tiered.excludedCatalog()
        );
        assertEquals("Khách ngồi máy tính nhiều, khó ngủ — ưu tiên không gian yên tĩnh, thao tác nhẹ nhàng", validCareNote);
    }

    @Test
    void testMultiTurnCareNotesAccumulation_DeduplicatedAndBounded_AndReusedInNextPrompt() {
        AgentBrainService brainService = new AgentBrainService((com.example.docmind.client.AgentBrainClient) null);
        UUID pageId = UUID.randomUUID();
        String senderId = "cust_memory_test_01";
        brainService.clearCustomerMemory(pageId, senderId);

        // Turn 1: Lưu care_note_delta và observed_fact đầu tiên -> careNotes không bao giờ bị 0
        brainService.retainTurnWithReflection(
                pageId,
                senderId,
                "Chị Lan",
                "Chị hay đau mỏi cổ vai gáy và mất ngủ do ngồi máy tính nhiều",
                "Dạ em chia sẻ cùng chị ạ...",
                List.of(),
                "slot_symptom=Đau mỏi cổ vai gáy, mất ngủ do ngồi máy tính",
                "Khách ngồi máy tính nhiều, khó ngủ — ưu tiên không gian yên tĩnh, thao tác nhẹ nhàng vùng cổ vai gáy",
                "Khách quan tâm chất lượng trị liệu giúp dễ ngủ hơn",
                List.of("cổ", "vai", "gáy")
        );
        assertEquals(1, brainService.getLastRetainedCareNotes().size(), "careNotes sau Turn 1 phải > 0");
        assertEquals(1, brainService.getRecalledCareNotes(pageId, senderId).size());
        assertEquals(1, brainService.getRecalledVerifiedFacts(pageId, senderId).size());

        // Turn 2: Thử lưu lại câu trùng lặp > 70% từ vựng -> phải bị chống trùng lặp (không tăng số lượng)
        brainService.retainTurnWithReflection(
                pageId,
                senderId,
                "Chị Lan",
                "Mới bị khoảng 1 tuần nay thôi em",
                "Dạ với tình trạng này em gợi ý chị...",
                List.of(),
                "slot_symptom=Đau mỏi cổ vai gáy, mất ngủ do ngồi máy tính nhiều",
                "Khách ngồi máy tính nhiều, khó ngủ — ưu tiên không gian yên tĩnh và thao tác nhẹ nhàng vùng cổ vai gáy",
                "Khách sẵn sàng tham khảo gói tiêu chuẩn 60 phút",
                List.of("cổ", "vai", "gáy")
        );
        assertEquals(1, brainService.getRecalledCareNotes(pageId, senderId).size(),
                "Ghi chú trùng lặp > 70% từ vựng không được phép nhân bản trong bộ nhớ");
        assertEquals(1, brainService.getRecalledVerifiedFacts(pageId, senderId).size(),
                "Sự thật cùng slot trùng lặp không được nhân bản");

        // Turn 3: Giải quyết xung đột (Conflict Resolution) khi khách đổi khung giờ / đổi thông tin cùng slot
        brainService.retainTurnWithReflection(
                pageId,
                senderId,
                "Chị Lan",
                "Chiều nay tầm 17h chị ghé nhé",
                "Dạ vâng ạ",
                List.of(),
                "slot_visit_time=Khung giờ dự kiến 17h chiều nay",
                "Khách thích uống trà thảo mộc ấm không đường sau buổi làm",
                "Khách chuẩn bị đặt lịch chiều nay",
                List.of("cổ", "vai", "gáy")
        );
        assertEquals(2, brainService.getRecalledVerifiedFacts(pageId, senderId).size());
        brainService.retainTurnWithReflection(
                pageId,
                senderId,
                "Chị Lan",
                "À thôi đổi sang khung giờ 10h sáng mai giúp chị nhé",
                "Dạ em đã cập nhật lại lịch 10h sáng mai cho chị ạ",
                List.of(),
                "slot_visit_time=Đổi sang khung giờ 10h sáng mai",
                "",
                "Khách đã chốt lịch 10h sáng mai",
                List.of("cổ", "vai", "gáy")
        );
        List<String> factsAfterConflictUpdate = brainService.getRecalledVerifiedFacts(pageId, senderId);
        assertEquals(2, factsAfterConflictUpdate.size(), "Cập nhật cùng slot_visit_time phải ghi đè mục cũ thay vì cộng dồn");
        assertTrue(factsAfterConflictUpdate.stream().anyMatch(f -> f.contains("10h sáng mai")));
        assertFalse(factsAfterConflictUpdate.stream().anyMatch(f -> f.contains("17h chiều nay")),
                "Khung giờ cũ 17h chiều nay phải được thay thế bởi thông tin mới nhất 10h sáng mai");

        // Turn 4..8: Kiểm tra giới hạn dung lượng (Bounded Memory: tối đa 4 careNotes và 5 verifiedFacts)
        List<String> distinctFacts = List.of(
                "Khách bị dị ứng tinh dầu bạc hà nồng độ cao",
                "Khách thường xuyên đi công tác xa bằng máy bay",
                "Khách ưu tiên thanh toán chuyển khoản ngân hàng",
                "Khách đi cùng bạn đồng nghiệp nữ vào cuối tuần",
                "Khách từng phẫu thuật dây chằng đầu gối trái năm ngoái"
        );
        List<String> distinctCareNotes = List.of(
                "Tránh dùng tinh dầu bạc hà, chỉ dùng dầu tràm hoặc sả chanh dịu nhẹ",
                "Chuẩn bị thêm gối kê cổ mềm vì khách mỏi gáy sau chuyến bay dài",
                "Bố trí hai giường cạnh nhau nếu khách đi cùng đồng nghiệp",
                "Tránh ấn lực mạnh trực tiếp lên vùng khớp gối trái đã từng phẫu thuật",
                "Ưu tiên kỹ thuật viên nữ tay nghề đằm và hạn chế trò chuyện ồn ào"
        );
        for (int i = 0; i < 5; i++) {
            brainService.retainTurnWithReflection(
                    pageId,
                    senderId,
                    "Chị Lan",
                    "Tin nhắn bổ sung thứ " + (i + 1),
                    "Phản hồi thứ " + (i + 1),
                    List.of(),
                    distinctFacts.get(i),
                    distinctCareNotes.get(i),
                    "Giả thuyết cập nhật ở lượt thứ " + (i + 1),
                    List.of("cổ", "vai", "gáy")
            );
        }
        List<String> boundedCareNotes = brainService.getRecalledCareNotes(pageId, senderId);
        List<String> boundedFacts = brainService.getRecalledVerifiedFacts(pageId, senderId);
        assertEquals(AgentBrainService.MAX_CARE_NOTES_PER_CUSTOMER, boundedCareNotes.size(),
                "Bộ nhớ careNotes phải được giới hạn tối đa đúng 4 mục");
        assertEquals(AgentBrainService.MAX_VERIFIED_FACTS_PER_CUSTOMER, boundedFacts.size(),
                "Bộ nhớ verifiedFacts phải được giới hạn tối đa đúng 5 mục");
        assertTrue(boundedCareNotes.get(boundedCareNotes.size() - 1).contains("Ưu tiên kỹ thuật viên nữ"),
                "Phải giữ lại các lưu ý chăm sóc mới nhất");

        // Bước 3: Tái sử dụng ở lượt kế tiếp thông qua enrichCustomerInsight -> buildCleanAgentPrompt (<AGENT_BRAIN_INSIGHT>)
        CustomerInsightDto enrichedInsight = brainService.enrichCustomerInsight(pageId, senderId, null, null);
        assertNotNull(enrichedInsight);
        assertEquals(4, enrichedInsight.careNotes().size());
        assertEquals(5, enrichedInsight.extractedPains().size());
        assertEquals("Giả thuyết cập nhật ở lượt thứ 5", enrichedInsight.workingHypothesis());

        PromptBuilder.PagePersona spaPersona = new PromptBuilder.PagePersona(
                "Nàng Sen Spa",
                "Spa dưỡng sinh trị liệu cổ truyền",
                "Khách đau mỏi cổ vai gáy",
                "Tinh tế, chu đáo"
        );
        String bookingPromptWithRecalledMemory = CleanAgentCatalogAndPromptBuilder.buildCleanAgentPrompt(
                spaPersona,
                "chị",
                List.of("đau mỏi cổ vai gáy"),
                List.of("cổ", "vai", "gáy"),
                "1 tuần nay",
                true,
                "Massage cơ bản 60 phút",
                "399k",
                0,
                true,
                "COLLECTING_VISIT_TIME",
                sampleSpaCatalog(),
                List.of(),
                List.of(),
                "Ừ, nghe ổn đó, đặt lịch cho chị nhé",
                enrichedInsight
        );
        assertTrue(bookingPromptWithRecalledMemory.contains("care_notes="),
                "<AGENT_BRAIN_INSIGHT> ở lượt kế tiếp phải chứa care_notes đã lưu từ các lượt trước");
        assertTrue(bookingPromptWithRecalledMemory.contains("working_hypothesis=\"Giả thuyết cập nhật ở lượt thứ 5\""),
                "<AGENT_BRAIN_INSIGHT> phải chứa working_hypothesis từ lượt phản tư trước");
        assertTrue(bookingPromptWithRecalledMemory.contains("đồng thời khéo léo nhắc lại lưu ý chăm sóc đã ghi nhận"),
                "Ở lượt khách đồng thuận đặt lịch (STAGE_4), turn_tactical_focus phải hướng dẫn nhắc lại lưu ý chăm sóc cho chuyên viên");
    }

    @Test
    void testConditionalHesitation_PriceContextVsPureDeferral() {
        PromptBuilder.PagePersona spaPersona = new PromptBuilder.PagePersona(
                "Nàng Sen Spa",
                "Spa dưỡng sinh trị liệu cổ truyền",
                "Khách đau mỏi cổ vai gáy",
                "Tinh tế, giàu cảm xúc"
        );
        List<PackageCatalogService.PackageSummary> allCatalog = sampleSpaCatalog();

        // 1. Trường hợp A: Khách lưỡng lự trong ngữ cảnh ĐÃ băn khoăn về giá (priceObjectionCount == 1) và Nhóm 2 có gói 239k chưa được giới thiệu
        String promptCaseA = CleanAgentCatalogAndPromptBuilder.buildCleanAgentPrompt(
                spaPersona,
                "chị",
                List.of("đau mỏi cổ vai gáy"),
                List.of("cổ", "vai", "gáy"),
                "mới vài hôm",
                true,
                "Massage cơ bản 60 phút",
                "399k",
                1,
                false,
                "NONE",
                allCatalog,
                List.of(),
                List.of(),
                "Để chị suy nghĩ thêm đã nhé"
        );
        assertTrue(promptCaseA.contains("STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL"),
                "Trường hợp A (lưỡng lự trong ngữ cảnh giá) phải kích hoạt STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL");
        assertTrue(promptCaseA.contains("[NHÓM 2 — PHƯƠNG ÁN TIẾT KIỆM / HẠ BẬC"),
                "Trường hợp A phải mở khóa nhẹ nhàng gói Nhóm 2 trong <SCOPE_FILTERED_CATALOG>");
        assertTrue(promptCaseA.contains("Gói dưỡng sinh cơ bản — Thời lượng: 60 phút — Giá trọn gói: 239k"),
                "Trường hợp A phải hiển thị gói DS02 (239k) để gợi ý mềm không áp lực");
        assertEquals(
                ThinkingMode.OBJECTION_REFRAME_THINK,
                CleanAgentOrchestratorService.resolveCleanAgentThinkingMode(false, false, false, true, false, true, false, false, List.of("đau cổ")),
                "Trường hợp A (lưỡng lự vì giá) phải dùng OBJECTION_REFRAME_THINK"
        );

        // 2. Trường hợp B1: Khách báo bận / hẹn tối xem lại ("Chị đang bận chút, để tối xem lại nhé") ngay cả khi priceObjectionCount == 1
        String promptCaseBBusy = CleanAgentCatalogAndPromptBuilder.buildCleanAgentPrompt(
                spaPersona,
                "chị",
                List.of("đau mỏi cổ vai gáy"),
                List.of("cổ", "vai", "gáy"),
                "mới vài hôm",
                true,
                "Massage cơ bản 60 phút",
                "399k",
                1,
                false,
                "NONE",
                allCatalog,
                List.of(),
                List.of(),
                "Chị đang bận chút, để tối xem lại nhé"
        );
        String userPartBusy = promptCaseBBusy.substring(promptCaseBBusy.indexOf("<!-- SYSTEM_USER_SPLIT -->"));
        assertTrue(userPartBusy.contains("STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE"),
                "Trường hợp B (khách báo bận/hẹn tối xem lại) phải kích hoạt STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE");
        assertTrue(userPartBusy.contains("Tạm ẩn danh sách chào bán gói ở lượt này — Khách đang bận hoặc xin tạm dừng để tự cân nhắc"),
                "Trường hợp B phải tạm ẩn danh sách chào bán gói và không cố nhồi thêm gói mới");
        assertFalse(userPartBusy.contains("239k"),
                "Trường hợp B tuyệt đối không được tung gói hạ bậc 239k khi khách đã báo bận");
        assertEquals(
                ThinkingMode.FAST_NO_THINK,
                CleanAgentOrchestratorService.resolveCleanAgentThinkingMode(false, false, false, false, true, true, false, false, List.of("đau cổ")),
                "Trường hợp B (khách bận / tạm dừng thuần túy) phải dùng FAST_NO_THINK để phản hồi nhanh, nhẹ nhàng"
        );

        // 3. Trường hợp B2: Khách nói "Để xem đã" khi chưa từng chê đắt (priceObjectionCount == 0) -> Tôn trọng nhịp độ của khách (STAGE_6)
        String promptCaseBNoPriceObj = CleanAgentCatalogAndPromptBuilder.buildCleanAgentPrompt(
                spaPersona,
                "chị",
                List.of("đau mỏi cổ vai gáy"),
                List.of("cổ", "vai", "gáy"),
                "mới vài hôm",
                true,
                "Massage cơ bản 60 phút",
                "399k",
                0,
                false,
                "NONE",
                allCatalog,
                List.of(),
                List.of(),
                "Để xem đã nhé"
        );
        assertTrue(promptCaseBNoPriceObj.contains("STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE"),
                "Khi priceObjectionCount == 0 mà khách nhắn 'Để xem đã nhé', phải tôn trọng nhịp độ của khách (STAGE_6)");

        // 4. Kiểm tra chuẩn hóa "buffet thực dưỡng" -> "bữa ăn nhẹ thực dưỡng" theo đúng hồ sơ gói NS01 (399k)
        PackageCatalogService.PackageSummary ns01Standard399k = allCatalog.stream()
                .filter(p -> "NS01".equals(p.code()))
                .findFirst()
                .orElseThrow();
        String normalizedBuffet = CleanAgentOutputRefereeAndSanitizer.sanitizeMinorCleanAgentWordingInPlace(
                "Gói Massage cơ bản 60 phút (399k) đã bao gồm buffet thực dưỡng ấm bụng sau buổi làm ạ.",
                "em",
                "chị",
                ns01Standard399k,
                false
        );
        assertFalse(normalizedBuffet.toLowerCase().contains("buffet thực dưỡng"),
                "Phải chuẩn hóa 'buffet thực dưỡng' về đúng 'bữa ăn nhẹ thực dưỡng' của gói NS01");
        assertTrue(normalizedBuffet.contains("bữa ăn nhẹ thực dưỡng"));
    }

    @Test
    void testMultiTurnContextualOkMatrix_FiveScenarios() {
        PromptBuilder.PagePersona spaPersona = new PromptBuilder.PagePersona(
                "Nàng Sen Spa",
                "Spa dưỡng sinh trị liệu cổ truyền",
                "Khách đau mỏi cổ vai gáy",
                "Tinh tế, giàu cảm xúc"
        );
        List<PackageCatalogService.PackageSummary> allCatalog = sampleSpaCatalog();
        AgentBrainService brainService = new AgentBrainService((com.example.docmind.client.AgentBrainClient) null);
        UUID pageId = UUID.randomUUID();
        String senderId = "cust_ok_matrix_01";
        brainService.clearCustomerMemory(pageId, senderId);

        // =====================================================================
        // Case 1: "Ok" ngay sau "Ok để nghĩ thêm" (actualSentSpeechAct == RESPECTED_PAUSE_WITH_SOFT_OPTION)
        // =====================================================================
        // 1a. Kiểm tra ở Turn 5 ("Ok để nghĩ thêm"): UI skill KHÔNG được kích hoạt "spa_trial_acceptance_booking"
        CustomerInsightEngine insightEngine = new CustomerInsightEngine();
        List<com.example.docmind.dto.bot.AgentSkillDto> candidateSkills = List.of(
                new com.example.docmind.dto.bot.AgentSkillDto(
                        UUID.randomUUID(),
                        "spa_trial_acceptance_booking",
                        "Tiến lên chốt khung giờ đặt lịch sau khi khách đồng ý",
                        "closing",
                        "Chốt lịch hẹn",
                        "ok, oke, đồng ý, thử 1 buổi",
                        "Hỏi khung giờ khách tiện ghé",
                        "Mình tiện ghé khung giờ nào ạ?",
                        "",
                        100,
                        true,
                        null,
                        null,
                        null
                ),
                new com.example.docmind.dto.bot.AgentSkillDto(
                        UUID.randomUUID(),
                        "spa_graceful_exit_after_rejection",
                        "Tôn trọng nhịp độ khi khách lưỡng lự / hẹn suy nghĩ thêm",
                        "objection",
                        "Tôn trọng khách",
                        "để nghĩ thêm, suy nghĩ thêm, cân nhắc thêm",
                        "Không thúc ép đặt lịch khi khách cần suy nghĩ thêm",
                        "Dạ mình cứ thoải mái tham khảo thêm nhé ạ.",
                        "",
                        90,
                        true,
                        null,
                        null,
                        null
                )
        );
        CustomerInsightDto dummyInsight = new CustomerInsightDto(
                "HESITANT_ANALYTICAL", "Cân nhắc kỹ", 55, "WARM", "Anh",
                List.of("đau mỏi cổ"), List.of("giá"), "Tôn trọng nhịp độ"
        );
        List<com.example.docmind.dto.bot.AgentSkillDto> selectedForTurn5 = insightEngine.selectActiveSkills(
                dummyInsight,
                "Ok để nghĩ thêm",
                List.of(),
                FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION,
                candidateSkills
        );
        assertFalse(
                selectedForTurn5.stream().anyMatch(s -> "spa_trial_acceptance_booking".equals(s.code())),
                "Khi khách nhắn 'Ok để nghĩ thêm', tuyệt đối KHÔNG được chọn skill 'spa_trial_acceptance_booking'"
        );

        // 1b. Trọng tài hậu kiểm resolveActualSentSpeechAct ở cuối Turn 5 phải ghi nhận RESPECTED_PAUSE_WITH_SOFT_OPTION
        // bất chấp LLM tự khai báo sai là INVITED_TRIAL_OR_ASKED_TIME
        List<FanpageBotResponse.ReplyBubble> turn5Bubbles = List.of(
                new FanpageBotResponse.ReplyBubble("Dạ không sao đâu ạ, mình cứ thoải mái tham khảo và cân nhắc thêm, chưa cần vội đặt lịch ngay đâu ạ.", 0),
                new FanpageBotResponse.ReplyBubble("Nếu mình muốn thử một phương án tiết kiệm hơn thì bên em cũng có gói dưỡng sinh cơ bản 60 phút (239k trọn gói), khi nào tiện mình cứ nhắn em nhé.", 2000)
        );
        String verifiedSpeechActTurn5 = CleanAgentOutputRefereeAndSanitizer.resolveActualSentSpeechAct(
                "STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL",
                turn5Bubbles,
                "NONE",
                "INVITED_TRIAL_OR_ASKED_TIME"
        );
        assertEquals(
                CleanAgentOutputRefereeAndSanitizer.SPEECH_ACT_RESPECTED_PAUSE,
                verifiedSpeechActTurn5,
                "Java Post-Send Verification phải ghi đè speech act thành RESPECTED_PAUSE_WITH_SOFT_OPTION"
        );

        brainService.retainTurnWithReflection(
                pageId,
                senderId,
                "Anh Minh",
                "Ok để nghĩ thêm",
                "Dạ không sao đâu ạ... khi nào tiện mình cứ nhắn em nhé.",
                List.of(),
                "Khách làm việc máy tính nhiều, đau mỏi cổ vai gáy",
                "Khách ngồi máy tính nhiều — lưu ý thao tác ấn miết kỹ vùng cổ vai gáy",
                "Khách đang tạm dừng để cân nhắc ngân sách giữa gói 399k và 239k",
                verifiedSpeechActTurn5,
                List.of("cổ", "vai", "gáy")
        );
        CustomerInsightDto insightForTurn6 = brainService.enrichCustomerInsight(pageId, senderId, dummyInsight, null);
        assertEquals(CleanAgentOutputRefereeAndSanitizer.SPEECH_ACT_RESPECTED_PAUSE, insightForTurn6.lastActualSentSpeechAct());

        List<FanpageBotRequest.ConversationTurn> historyAfterTurn5 = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Ok để nghĩ thêm"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ không sao đâu ạ, mình cứ thoải mái tham khảo và cân nhắc thêm, chưa cần vội đặt lịch ngay đâu ạ. Nếu mình muốn thử một phương án tiết kiệm hơn thì bên em cũng có gói dưỡng sinh cơ bản 60 phút (239k trọn gói), khi nào tiện mình cứ nhắn em nhé.")
        );
        String promptCase1 = CleanAgentCatalogAndPromptBuilder.buildCleanAgentPrompt(
                spaPersona,
                "mình",
                List.of("đau mỏi cổ vai gáy"),
                List.of("cổ", "vai", "gáy"),
                "ngồi máy tính nhiều",
                true,
                "Massage cơ bản 60 phút",
                "399k",
                1,
                false,
                "NONE",
                allCatalog,
                List.of(),
                historyAfterTurn5,
                "Ok",
                insightForTurn6
        );
        String userPartCase1 = promptCase1.substring(promptCase1.indexOf("<!-- SYSTEM_USER_SPLIT -->"));
        assertTrue(userPartCase1.contains("POLITE_CLOSING_ACKNOWLEDGMENT"),
                "Case 1: 'Ok' sau khi hẹn nghĩ thêm phải phân loại là POLITE_CLOSING_ACKNOWLEDGMENT");
        assertFalse(userPartCase1.contains("PACKAGE_ACCEPTANCE_OR_BOOKING_PROGRESS"),
                "Case 1: Tuyệt đối KHÔNG được phân loại 'Ok' sau tạm dừng thành PACKAGE_ACCEPTANCE_OR_BOOKING_PROGRESS");
        assertTrue(userPartCase1.contains("STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE"),
                "Case 1: Phải định tuyến vào STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE");
        assertFalse(userPartCase1.contains("STAGE_4_POSITIVE_TRIAL_OR_BOOKING"),
                "Case 1: Tuyệt đối KHÔNG được vào STAGE_4_POSITIVE_TRIAL_OR_BOOKING");
        assertTrue(userPartCase1.contains("active_package_in_focus: \"Massage cơ bản 60 phút (399k)\""),
                "Case 1: Không được tự ý khóa gói 239k thành gói đặt lịch khi khách chỉ nhắn 'Ok' khép lại");
        assertFalse(userPartCase1.contains("hôm nay hay ngày mai"),
                "Case 1: Trong prompt của STAGE_6 không được có lệnh ép bot hỏi hôm nay hay ngày mai");
        assertFalse(userPartCase1.contains("hôm nay hoặc ngày mai"),
                "Case 1: Trong prompt của STAGE_6 không được có lệnh ép bot hỏi hôm nay hoặc ngày mai");

        // =====================================================================
        // Case 2: "Ok" ngay sau INVITED_TRIAL_OR_ASKED_TIME (Bot vừa mời thử 1 buổi)
        // =====================================================================
        CustomerInsightDto insightAfterTrialInvite = new CustomerInsightDto(
                "HESITANT_ANALYTICAL", "Cân nhắc kỹ", 55, null, 0, "STABLE", "WARM", null, "Anh",
                List.of("đau mỏi cổ vai gáy"), List.of("giá"), "Tôn trọng nhịp độ",
                List.of("Khách ngồi máy tính nhiều"),
                "Khách quan tâm gói 399k",
                CleanAgentOutputRefereeAndSanitizer.SPEECH_ACT_INVITED_TRIAL_OR_TIME
        );
        List<FanpageBotRequest.ConversationTurn> historyAfterTrialInvite = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Giá 399k hơi cao nhỉ"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ gói Massage cơ bản 60 phút (399k) tập trung xử lý sâu vùng cổ vai gáy, mình thử trải nghiệm trước 1 lần để cảm nhận nhé?")
        );
        String promptCase2 = CleanAgentCatalogAndPromptBuilder.buildCleanAgentPrompt(
                spaPersona,
                "mình",
                List.of("đau mỏi cổ vai gáy"),
                List.of("cổ", "vai", "gáy"),
                "ngồi máy tính nhiều",
                true,
                "Massage cơ bản 60 phút",
                "399k",
                1,
                false,
                "NONE",
                allCatalog,
                List.of(),
                historyAfterTrialInvite,
                "Ok",
                insightAfterTrialInvite
        );
        String userPartCase2 = promptCase2.substring(promptCase2.indexOf("<!-- SYSTEM_USER_SPLIT -->"));
        assertTrue(userPartCase2.contains("STAGE_4_POSITIVE_TRIAL_OR_BOOKING"),
                "Case 2: 'Ok' ngay sau lời mời thử trải nghiệm phải vào STAGE_4_POSITIVE_TRIAL_OR_BOOKING để hỏi khung giờ");

        // =====================================================================
        // Case 3: "Ok" ngay sau OFFERED_PACKAGE_DETAILS (Bot vừa hỏi có muốn nghe chi tiết gói không)
        // =====================================================================
        CustomerInsightDto insightAfterOfferDetails = new CustomerInsightDto(
                "HESITANT_ANALYTICAL", "Cân nhắc kỹ", 55, null, 0, "STABLE", "WARM", null, "Anh",
                List.of("đau mỏi cổ vai gáy"), List.of("giá"), "Tôn trọng nhịp độ",
                List.of("Khách ngồi máy tính nhiều"),
                "Khách muốn tìm hiểu chi tiết gói 239k",
                CleanAgentOutputRefereeAndSanitizer.SPEECH_ACT_OFFERED_DETAILS
        );
        List<FanpageBotRequest.ConversationTurn> historyAfterOfferDetails = List.of(
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ bên em có Gói dưỡng sinh cơ bản 60 phút (239k trọn gói), mình có muốn nghe thêm chi tiết về gói này không ạ?")
        );
        String promptCase3 = CleanAgentCatalogAndPromptBuilder.buildCleanAgentPrompt(
                spaPersona,
                "mình",
                List.of("đau mỏi cổ vai gáy"),
                List.of("cổ", "vai", "gáy"),
                "ngồi máy tính nhiều",
                true,
                "Massage cơ bản 60 phút",
                "399k",
                1,
                false,
                "NONE",
                allCatalog,
                List.of(),
                historyAfterOfferDetails,
                "Ok",
                insightAfterOfferDetails
        );
        String userPartCase3 = promptCase3.substring(promptCase3.indexOf("<!-- SYSTEM_USER_SPLIT -->"));
        assertTrue(userPartCase3.contains("SERVICE_OR_UNIT_DETAIL_QUESTION"),
                "Case 3: 'Ok' sau khi bot hỏi muốn nghe chi tiết gói phải phân loại là SERVICE_OR_UNIT_DETAIL_QUESTION");
        assertFalse(userPartCase3.contains("STAGE_4_POSITIVE_TRIAL_OR_BOOKING"),
                "Case 3: Chỉ gửi chi tiết gói, chưa nhảy sang STAGE_4 hỏi ép giờ đặt lịch");

        // =====================================================================
        // Case 4: "Ok" khi lịch đã CONFIRMED (BOOKING_CONFIRMED_ACK)
        // =====================================================================
        CustomerInsightDto insightAfterBookingConfirmed = new CustomerInsightDto(
                "READY_TO_BOOK", "Sẵn sàng đặt lịch", 90, null, 0, "RISING", "HOT", null, "Anh",
                List.of("đau mỏi cổ vai gáy"), List.of(), "Xác nhận lịch hẹn",
                List.of("Khách ngồi máy tính nhiều"),
                "Khách đã đặt lịch 17h chiều nay",
                CleanAgentOutputRefereeAndSanitizer.SPEECH_ACT_BOOKING_CONFIRMED
        );
        String promptCase4 = CleanAgentCatalogAndPromptBuilder.buildCleanAgentPrompt(
                spaPersona,
                "mình",
                List.of("đau mỏi cổ vai gáy"),
                List.of("cổ", "vai", "gáy"),
                "ngồi máy tính nhiều",
                true,
                "Massage cơ bản 60 phút",
                "399k",
                0,
                true,
                "CONFIRMED",
                allCatalog,
                List.of(),
                List.of(new FanpageBotRequest.ConversationTurn("assistant", "Dạ em đã xác nhận lịch hẹn 17h chiều nay cho mình rồi ạ.")),
                "Ok",
                insightAfterBookingConfirmed
        );
        String userPartCase4 = promptCase4.substring(promptCase4.indexOf("<!-- SYSTEM_USER_SPLIT -->"));
        assertTrue(userPartCase4.contains("POST_BOOKING_CLOSING_ACK"),
                "Case 4: 'Ok' sau khi đã chốt lịch phải phân loại là POST_BOOKING_CLOSING_ACK");
        assertTrue(userPartCase4.contains("STAGE_4B_POST_BOOKING_CONFIRMED_CLOSING"),
                "Case 4: Phải vào STAGE_4B_POST_BOOKING_CONFIRMED_CLOSING để cảm ơn khép lại, không hỏi lại giờ");

        // =====================================================================
        // Case 5 (Phản chứng): "Ok, chiều nay mình qua" hoặc "Ok cho mình thử gói 239k nhé" ngay sau khi hẹn nghĩ thêm
        // =====================================================================
        String promptCase5Proactive = CleanAgentCatalogAndPromptBuilder.buildCleanAgentPrompt(
                spaPersona,
                "mình",
                List.of("đau mỏi cổ vai gáy"),
                List.of("cổ", "vai", "gáy"),
                "ngồi máy tính nhiều",
                true,
                "Massage cơ bản 60 phút",
                "399k",
                1,
                false,
                "NONE",
                allCatalog,
                List.of(),
                historyAfterTurn5,
                "Ok cho mình thử gói 239k chiều nay nhé",
                insightForTurn6
        );
        String userPartCase5 = promptCase5Proactive.substring(promptCase5Proactive.indexOf("<!-- SYSTEM_USER_SPLIT -->"));
        assertTrue(userPartCase5.contains("PACKAGE_ACCEPTANCE_OR_BOOKING_PROGRESS"),
                "Case 5: Khi khách nhắn 'Ok' kèm thông tin chủ động ('cho mình thử gói 239k chiều nay nhé'), phải phân loại là PACKAGE_ACCEPTANCE_OR_BOOKING_PROGRESS");
        assertTrue(userPartCase5.contains("STAGE_4_POSITIVE_TRIAL_OR_BOOKING"),
                "Case 5: Phải định tuyến vào STAGE_4_POSITIVE_TRIAL_OR_BOOKING");
        assertTrue(userPartCase5.contains("active_package_in_focus: \"Gói dưỡng sinh cơ bản 60 phút (239k)\""),
                "Case 5: Khi khách chủ động chọn gói 239k, phải khóa sang Gói dưỡng sinh cơ bản 60 phút (239k)");
    }

    @Test
    void testUnconfirmedBooking_BlocksPastTenseAlreadyInstructedStaff() {
        List<PackageCatalogService.PackageSummary> allCatalog = sampleSpaCatalog();
        PackageCatalogService.PackageSummary ns01Standard399k = allCatalog.stream()
                .filter(p -> "NS01".equals(p.code()))
                .findFirst()
                .orElseThrow();

        // 1. Khi bookingStatus != CONFIRMED ("NONE" hoặc "COLLECTING_VISIT_TIME"):
        // checkCleanCatalogGrounding phải bắt lỗi UNCONFIRMED_BOOKING_PAST_TENSE_STAFF_INSTRUCTION
        List<String> violationsWhenUnconfirmed = CleanAgentOutputRefereeAndSanitizer.checkCleanCatalogGrounding(
                List.of(new FanpageBotResponse.ReplyBubble(
                        "Dạ vâng ạ, em đã dặn chuyên viên chuẩn bị lực ấn vừa phải vùng cổ vai gáy cho mình rồi ạ. Mình tiện ghé khung giờ nào ạ?",
                        0
                )),
                allCatalog,
                List.of(ns01Standard399k),
                List.of(),
                0,
                false,
                ns01Standard399k,
                false,
                "COLLECTING_VISIT_TIME"
        );
        assertTrue(
                violationsWhenUnconfirmed.contains("UNCONFIRMED_BOOKING_PAST_TENSE_STAFF_INSTRUCTION"),
                "Khi bookingStatus != CONFIRMED, phải bắt lỗi nói quá khứ 'em đã dặn chuyên viên': " + violationsWhenUnconfirmed
        );

        // 2. sanitizeMinorCleanAgentWordingInPlace tự động chuẩn hóa về "em lưu lại để khi mình ghé sẽ dặn chuyên viên"
        String sanitizedMidSentence = CleanAgentOutputRefereeAndSanitizer.sanitizeMinorCleanAgentWordingInPlace(
                "Dạ vâng ạ, em đã dặn chuyên viên lưu ý vùng cổ vai gáy cho mình rồi ạ.",
                "em",
                "mình",
                ns01Standard399k,
                false,
                "COLLECTING_VISIT_TIME"
        );
        assertTrue(
                sanitizedMidSentence.contains("em lưu lại để khi mình ghé sẽ dặn chuyên viên"),
                "Phải chuẩn hóa 'em đã dặn chuyên viên' thành 'em lưu lại để khi mình ghé sẽ dặn chuyên viên': " + sanitizedMidSentence
        );
        assertFalse(sanitizedMidSentence.toLowerCase().contains("em đã dặn chuyên viên"));

        String sanitizedStartSentence = CleanAgentOutputRefereeAndSanitizer.sanitizeMinorCleanAgentWordingInPlace(
                "Em đã báo kỹ thuật viên chuẩn bị chu đáo cho chị ạ.",
                "em",
                "chị",
                ns01Standard399k,
                false,
                "NONE"
        );
        assertTrue(
                sanitizedStartSentence.contains("Em lưu lại để khi mình ghé sẽ dặn chuyên viên"),
                "Phải bảo toàn chữ hoa đầu câu khi chuẩn hóa 'Em đã báo kỹ thuật viên': " + sanitizedStartSentence
        );

        // 3. Khi bookingStatus == "CONFIRMED": Cho phép giữ nguyên vì lịch hẹn đã thực sự được chốt
        String sanitizedWhenConfirmed = CleanAgentOutputRefereeAndSanitizer.sanitizeMinorCleanAgentWordingInPlace(
                "Dạ em đã dặn chuyên viên chuẩn bị phòng đón mình lúc 17h chiều nay rồi ạ.",
                "em",
                "mình",
                ns01Standard399k,
                false,
                "CONFIRMED"
        );
        assertTrue(sanitizedWhenConfirmed.contains("em đã dặn chuyên viên"));
    }

    @Test
    void testDynamicIncludesSummary_ReplacesUngroundedBuffetWithCatalogWording() {
        List<PackageCatalogService.PackageSummary> allCatalog = sampleSpaCatalog();
        PackageCatalogService.PackageSummary ns01Standard399k = allCatalog.stream()
                .filter(p -> "NS01".equals(p.code()))
                .findFirst()
                .orElseThrow();

        // 1. Gói NS01 có includesSummary = "Trà thảo mộc, ăn nhẹ thực dưỡng" (không chứa từ "buffet")
        List<String> violationsOnNs01 = CleanAgentOutputRefereeAndSanitizer.checkCleanCatalogGrounding(
                List.of(new FanpageBotResponse.ReplyBubble(
                        "Gói Massage cơ bản 60 phút (399k) đã bao gồm trà thảo mộc và buffet ăn nhẹ sau liệu trình ạ.",
                        0
                )),
                allCatalog,
                List.of(ns01Standard399k),
                List.of(),
                1,
                false,
                ns01Standard399k,
                false
        );
        assertTrue(
                violationsOnNs01.stream().anyMatch(v -> v.startsWith("UNGROUNDED_BUFFET_AMENITY")),
                "Khi includesSummary không có từ 'buffet', phải bắt lỗi UNGROUNDED_BUFFET_AMENITY: " + violationsOnNs01
        );

        String sanitizedBuffetAnNhe = CleanAgentOutputRefereeAndSanitizer.sanitizeMinorCleanAgentWordingInPlace(
                "Gói 399k đã bao gồm trà thảo mộc và buffet ăn nhẹ ấm bụng ạ.",
                "em",
                "chị",
                ns01Standard399k,
                false
        );
        assertFalse(sanitizedBuffetAnNhe.toLowerCase().contains("buffet"));
        assertTrue(sanitizedBuffetAnNhe.contains("bữa ăn nhẹ thực dưỡng"));

        // 2. Đối chứng: Với một gói có includesSummary thực sự chứa chữ "buffet", hệ thống giữ nguyên không sửa
        PackageCatalogService.PackageSummary realBuffetPkg = new PackageCatalogService.PackageSummary(
                "HN_VIP99",
                "VIP99",
                "HN",
                "Gói Hoàng Gia Toàn Diện",
                "body_therapy",
                List.of("toàn thân", "cổ", "vai", "gáy"),
                120,
                "120 phút",
                990_000L,
                "990k",
                "Phòng VIP riêng",
                "Trà thảo mộc, buffet thực dưỡng 15 món",
                "Khách VIP",
                "Trị liệu chuyên sâu toàn thân",
                false
        );
        List<String> violationsOnRealBuffetPkg = CleanAgentOutputRefereeAndSanitizer.checkCleanCatalogGrounding(
                List.of(new FanpageBotResponse.ReplyBubble(
                        "Dạ Gói Hoàng Gia Toàn Diện 120 phút (990k) có phòng VIP riêng và buffet thực dưỡng 15 món ạ.",
                        0
                )),
                List.of(realBuffetPkg),
                List.of(realBuffetPkg),
                List.of(),
                0,
                false,
                realBuffetPkg,
                false
        );
        assertTrue(violationsOnRealBuffetPkg.isEmpty(), "Khi includesSummary thực sự có 'buffet', không được báo lỗi: " + violationsOnRealBuffetPkg);

        String preservedBuffet = CleanAgentOutputRefereeAndSanitizer.sanitizeMinorCleanAgentWordingInPlace(
                "Dạ gói này có buffet thực dưỡng 15 món ạ.",
                "em",
                "chị",
                realBuffetPkg,
                false
        );
        assertTrue(preservedBuffet.contains("buffet thực dưỡng 15 món"));
    }

    @Test
    void testTurn4To6_CaoThe_XemDa_Ok_PartialScopeDownsell239k_AndNoLukewarmSkill() {
        PromptBuilder.PagePersona spaPersona = new PromptBuilder.PagePersona(
                "Nàng Sen Spa",
                "Spa dưỡng sinh trị liệu cổ truyền",
                "Khách đau mỏi vai gáy, mông, chân",
                "Tinh tế, giàu cảm xúc"
        );
        List<PackageCatalogService.PackageSummary> allCatalog = sampleSpaCatalog();
        List<String> knownSymptoms = List.of("đau mỏi vai gáy", "nhức mông", "mỏi chân");
        List<String> knownBodyAreas = List.of("vai gáy", "mông", "chân");

        // 1. Kiểm tra phân tầng Catalog với khách đau ["vai gáy", "mông", "chân"]:
        // Nhóm 1 là NS01 (399k), Nhóm 2 mở khóa DS02 (239k) với ghi chú giới hạn phạm vi rõ ràng
        CleanAgentCatalogAndPromptBuilder.TieredCatalogResult tiered = CleanAgentCatalogAndPromptBuilder.rankAndTierCatalogForCleanAgent(
                allCatalog,
                knownSymptoms,
                knownBodyAreas,
                Set.of(),
                false
        );
        assertFalse(tiered.primaryRecommendations().isEmpty());
        assertEquals("NS01", tiered.primaryRecommendations().get(0).code());
        assertFalse(tiered.economicalAlternatives().isEmpty(),
                "Nhóm 2 phải giữ lại gói DS02 (239k) làm phương án tiết kiệm một phần phạm vi");
        assertEquals("DS02", tiered.economicalAlternatives().get(0).code());
        assertTrue(tiered.excludedCatalog().stream().anyMatch(p -> "DS01".equals(p.code())),
                "Gói ráy tai DS01 (129k, hits==0) vẫn phải bị loại vào outExcluded");

        String scopeLimitationNote = CleanAgentCatalogAndPromptBuilder.buildPartialScopeLimitationNote(
                tiered.economicalAlternatives().get(0),
                knownBodyAreas,
                knownSymptoms
        );
        assertEquals(
                "(Phạm vi hẹp hơn: chỉ tập trung chăm sóc vùng [đầu, cổ, vai, gáy], KHÔNG bao gồm vùng [mông, chân])",
                scopeLimitationNote
        );

        // 2. Turn 5: Khách nhắn "Xem đã" sau khi đã chê đắt ở Turn 4 ("Cao thế", priceObjectionCount == 1)
        for (String shortDeferral : List.of("Xem đã", "Nghĩ đã", "Tính đã", "Cân nhắc đã", "Xem thêm đã", "Nghĩ thêm đã", "Tham khảo thêm", "Chưa quyết", "Từ từ đã")) {
            assertTrue(
                    CleanAgentOrchestratorService.isHesitationOrDeferralMessage(shortDeferral),
                    "Phải nhận diện '" + shortDeferral + "' là lời lưỡng lự/tạm hoãn"
            );
        }

        List<String> turn5Signals = CleanAgentOrchestratorService.classifyCleanCurrentTurnSignals(
                "Xem đã",
                false,
                false,
                false,
                false,
                false,
                1,
                false,
                false,
                knownSymptoms,
                knownBodyAreas,
                "ngồi nhiều",
                CleanAgentOutputRefereeAndSanitizer.SPEECH_ACT_INVITED_TRIAL_OR_TIME,
                "NONE",
                List.of(
                        new FanpageBotRequest.ConversationTurn("user", "Cao thế"),
                        new FanpageBotRequest.ConversationTurn("assistant", "Giá trị của gói Massage cơ bản 60 phút (399k) nằm ở trọn vẹn thời lượng chuyên viên lành nghề chăm sóc sâu. Mình thử trải nghiệm trước 1 lần nhé?")
                )
        );
        assertEquals(List.of("HESITATION_OR_DEFERRAL"), turn5Signals,
                "Turn 5 ('Xem đã') chỉ được mang tín hiệu HESITATION_OR_DEFERRAL, KHÔNG được tự động gán SHARING_CONDITION_OR_CONTEXT");

        String turn5Stage = CleanAgentOrchestratorService.resolveCleanFunnelStage(
                turn5Signals,
                "Xem đã",
                1,
                false,
                tiered,
                List.of()
        );
        assertEquals("STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL", turn5Stage,
                "Turn 5 ('Xem đã' sau khi chê đắt) phải định tuyến vào STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL, không bị kẹt ở STAGE_2");

        // Không chọn và không ghi nhận skill spa_lukewarm_mood_boost khi khách nhắn "Xem đã"
        CustomerInsightEngine insightEngine = new CustomerInsightEngine();
        List<com.example.docmind.dto.bot.AgentSkillDto> candidateSkills = List.of(
                new com.example.docmind.dto.bot.AgentSkillDto(
                        UUID.randomUUID(),
                        "spa_lukewarm_mood_boost",
                        "Hâm nóng cảm xúc khi khách trả lời ngắn gọn",
                        "discovery",
                        "Hâm nóng cảm xúc",
                        "ngắn gọn, nhát gừng",
                        "Khơi gợi lại nhu cầu",
                        "Chắc dạo này mình bận rộn lắm nhỉ?",
                        "",
                        85,
                        true,
                        null,
                        null,
                        null
                ),
                new com.example.docmind.dto.bot.AgentSkillDto(
                        UUID.randomUUID(),
                        "spa_graceful_exit_after_rejection",
                        "Tôn trọng nhịp độ khi khách lưỡng lự",
                        "objection",
                        "Tôn trọng khách",
                        "xem đã, để nghĩ thêm",
                        "Tôn trọng nhịp độ của khách",
                        "Dạ mình cứ thoải mái tham khảo thêm nhé ạ.",
                        "",
                        90,
                        true,
                        null,
                        null,
                        null
                )
        );
        CustomerInsightDto lukewarmInsight = new CustomerInsightDto(
                "LUKEWARM_PASSIVE", "Cần hâm nóng", 45, "LUKEWARM", "Anh",
                knownSymptoms, List.of("giá"), "Tôn trọng nhịp độ"
        );
        List<com.example.docmind.dto.bot.AgentSkillDto> selectedSkillsTurn5 = insightEngine.selectActiveSkills(
                lukewarmInsight,
                "Xem đã",
                List.of(),
                FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION,
                candidateSkills
        );
        assertFalse(
                selectedSkillsTurn5.stream().anyMatch(s -> "spa_lukewarm_mood_boost".equalsIgnoreCase(s.code())),
                "CustomerInsightEngine tuyệt đối không được chọn skill 'spa_lukewarm_mood_boost' khi khách nhắn 'Xem đã'"
        );
        List<com.example.docmind.dto.bot.AgentSkillDto> alignedSkillsTurn5 = CleanAgentOrchestratorService.alignCleanAgentActiveSkills(
                candidateSkills,
                candidateSkills,
                true,
                false,
                false
        );
        assertFalse(
                alignedSkillsTurn5.stream().anyMatch(s -> "spa_lukewarm_mood_boost".equalsIgnoreCase(s.code())),
                "alignCleanAgentActiveSkills phải loại bỏ hoàn toàn 'spa_lukewarm_mood_boost' ở lượt lưỡng lự"
        );

        String promptTurn5 = CleanAgentCatalogAndPromptBuilder.buildCleanAgentPrompt(
                spaPersona,
                "mình",
                knownSymptoms,
                knownBodyAreas,
                "ngồi nhiều",
                true,
                "Massage cơ bản 60 phút",
                "399k",
                1,
                false,
                "NONE",
                allCatalog,
                List.of(),
                List.of(
                        new FanpageBotRequest.ConversationTurn("user", "Cao thế"),
                        new FanpageBotRequest.ConversationTurn("assistant", "Giá trị của gói Massage cơ bản 60 phút (399k) nằm ở trọn vẹn thời lượng chuyên viên lành nghề chăm sóc sâu. Mình thử trải nghiệm trước 1 lần nhé?")
                ),
                "Xem đã",
                lukewarmInsight
        );
        String userPartTurn5 = promptTurn5.substring(promptTurn5.indexOf("<!-- SYSTEM_USER_SPLIT -->"));
        assertTrue(userPartTurn5.contains("STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL"));
        assertTrue(userPartTurn5.contains("Gói dưỡng sinh cơ bản — Thời lượng: 60 phút — Giá trọn gói: 239k"));
        assertTrue(userPartTurn5.contains("(Phạm vi hẹp hơn: chỉ tập trung chăm sóc vùng [đầu, cổ, vai, gáy], KHÔNG bao gồm vùng [mông, chân])"),
                "Prompt Turn 5 phải hiển thị rõ ghi chú giới hạn phạm vi của gói 239k");

        // 3. Turn 6: Khách nhắn "Ok" sau khi bot đã tôn trọng tạm dừng và gợi ý mềm gói 239k ở Turn 5
        CustomerInsightDto insightTurn6 = new CustomerInsightDto(
                "HESITANT_ANALYTICAL", "Cân nhắc kỹ", 50, null, 0, "STABLE", "WARM", null, "Anh",
                knownSymptoms, List.of("giá"), "Tôn trọng nhịp độ",
                List.of("Khách ngồi nhiều"),
                "Khách tạm dừng cân nhắc giữa gói 399k và gói 239k",
                CleanAgentOutputRefereeAndSanitizer.SPEECH_ACT_RESPECTED_PAUSE
        );
        List<FanpageBotRequest.ConversationTurn> historyAfterTurn5 = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Xem đã"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ không sao đâu ạ, mình cứ thoải mái tham khảo thêm, chưa cần vội đặt lịch ngay đâu ạ. Nếu mình muốn thử phương án tiết kiệm hơn giải tỏa trước vùng cổ vai gáy (chưa bao gồm vùng mông, chân) thì bên em cũng có Gói dưỡng sinh cơ bản 60 phút (239k trọn gói), khi nào tiện mình cứ nhắn em nhé.")
        );
        String promptTurn6 = CleanAgentCatalogAndPromptBuilder.buildCleanAgentPrompt(
                spaPersona,
                "mình",
                knownSymptoms,
                knownBodyAreas,
                "ngồi nhiều",
                true,
                "Massage cơ bản 60 phút",
                "399k",
                1,
                false,
                "NONE",
                allCatalog,
                List.of(),
                historyAfterTurn5,
                "Ok",
                insightTurn6
        );
        String userPartTurn6 = promptTurn6.substring(promptTurn6.indexOf("<!-- SYSTEM_USER_SPLIT -->"));
        assertTrue(userPartTurn6.contains("POLITE_CLOSING_ACKNOWLEDGMENT"),
                "Turn 6 ('Ok' sau 'Xem đã') phải là POLITE_CLOSING_ACKNOWLEDGMENT");
        assertTrue(userPartTurn6.contains("STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE"),
                "Turn 6 ('Ok' sau 'Xem đã') phải định tuyến vào STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE");
        assertFalse(userPartTurn6.contains("STAGE_4_POSITIVE_TRIAL_OR_BOOKING"),
                "Turn 6 tuyệt đối không được nhảy sang STAGE_4 hỏi ép giờ đặt lịch");
    }

    @Test
    void testPackageCatalogExtract_Ns01NoBuffetOrVip_WhileVipPreservesBuffet_AndChunkFilterDropsBuffet() {
        String markdownTable = """
                | Mã DV | Tên dịch vụ | Thời gian | Giá dịch vụ | Bao gồm | Không gian | Phù hợp với | Điểm nổi bật |
                | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
                | NS01 | Massage cơ bản | 60 phút | 399.000đ | Trà thảo mộc, ăn nhẹ thực dưỡng | Phòng chung (có rèm riêng) | Khách đau mỏi cổ vai gáy, lưng, chân | Phòng Vip. Kỹ thuật viên lành nghề. Thưởng thức buffet thực dưỡng sau liệu trình |
                | NS04 | Massage VIP Hoàng Gia | 120 phút | 670.000đ | Trà thảo mộc, buffet thực dưỡng, xông hơi | Phòng VIP riêng | Khách cần phòng VIP riêng tư | Phòng Vip. Thưởng thức buffet thực dưỡng không giới hạn |
                """;
        RetrievedChunk tableChunk = new RetrievedChunk(
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of("Bảng giá dịch vụ"),
                List.of(),
                1,
                null,
                markdownTable,
                0.95
        );
        List<PackageCatalogService.PackageSummary> extracted = PackageCatalogService.extractCatalog(
                List.of(new ReciprocalRankFusion.FusedChunk(tableChunk, 0.95)),
                List.of()
        );
        PackageCatalogService.PackageSummary ns01 = extracted.stream()
                .filter(p -> "NS01".equals(p.code()))
                .findFirst()
                .orElseThrow();
        PackageCatalogService.PackageSummary ns04 = extracted.stream()
                .filter(p -> "NS04".equals(p.code()))
                .findFirst()
                .orElseThrow();

        // 1. Gói tiêu chuẩn NS01 (399k) tuyệt đối không chứa "buffet thực dưỡng" hay "Phòng Vip" trong includesSummary / processSummary
        assertFalse(ns01.includesSummary().toLowerCase().contains("buffet"),
                "includesSummary của NS01 (399k) không được tự động nối 'buffet thực dưỡng': " + ns01.includesSummary());
        assertTrue(ns01.includesSummary().toLowerCase().contains("ăn nhẹ thực dưỡng"));
        assertFalse(ns01.processSummary().toLowerCase().contains("buffet"),
                "processSummary của NS01 (399k) phải chuẩn hóa 'buffet thực dưỡng' -> 'ăn nhẹ thực dưỡng': " + ns01.processSummary());
        assertFalse(ns01.processSummary().toLowerCase().contains("phòng vip"),
                "processSummary của NS01 (399k) phải chuẩn hóa 'Phòng Vip' -> 'không gian yên tĩnh, rèm che kín đáo': " + ns01.processSummary());

        // 2. Gói VIP NS04 (670k) thực sự có quyền lợi buffet và Phòng VIP vẫn giữ nguyên 100%
        assertTrue(ns04.includesSummary().toLowerCase().contains("buffet"),
                "Gói VIP NS04 có quyền lợi buffet thực sự phải được giữ nguyên: " + ns04.includesSummary());
        assertTrue(ns04.processSummary().toLowerCase().contains("buffet"));

        // 3. filterChunksByVisibleCatalogScope loại bỏ chunk giới thiệu "Buffet thực dưỡng" khi gói hiển thị là NS01 (không có buffet)
        ReciprocalRankFusion.FusedChunk buffetFaqChunk = new ReciprocalRankFusion.FusedChunk(
                new RetrievedChunk(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        List.of("FAQ", "Buffet thực dưỡng là gì?"),
                        List.of(),
                        2,
                        null,
                        "Sau liệu trình, quý khách được thưởng thức quầy Buffet thực dưỡng gồm cháo dưỡng sinh, chè hạt sen và súp ấm bụng.",
                        0.89
                ),
                0.89
        );
        List<ReciprocalRankFusion.FusedChunk> filteredForNs01 = CleanAgentCatalogAndPromptBuilder.filterChunksByVisibleCatalogScope(
                List.of(buffetFaqChunk),
                List.of(ns01)
        );
        assertTrue(filteredForNs01.isEmpty(),
                "Chunk giới thiệu Buffet thực dưỡng phải bị loại khỏi <DOCUMENT_CONTEXT> khi chỉ hiển thị gói tiêu chuẩn NS01");

        List<ReciprocalRankFusion.FusedChunk> filteredForNs04 = CleanAgentCatalogAndPromptBuilder.filterChunksByVisibleCatalogScope(
                List.of(buffetFaqChunk),
                List.of(ns04)
        );
        assertEquals(1, filteredForNs04.size(),
                "Chunk giới thiệu Buffet thực dưỡng vẫn được giữ lại khi hiển thị gói VIP NS04 có quyền lợi buffet");
    }

    @Test
    void testNaturalTrialInviteBubbleSplit_Stage1And2_UpTo3Bubbles_NoMechanicalSplitOnShortOrConditional() {
        // 1. Ở STAGE_2 (hoặc STAGE_1), khi bong bóng cuối chứa phần mô tả quyền lợi gói kết thúc bằng '.' kèm câu hỏi mời trải nghiệm độc lập '?' -> tách thành 3 bong bóng tự nhiên
        List<FanpageBotResponse.ReplyBubble> twoBubblesStage2 = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Giá trị của gói Massage cơ bản 60 phút nằm ở trọn vẹn thời lượng chuyên viên lành nghề tập trung xử lý sâu từng điểm đau mỏi cổ vai gáy để mang lại cảm nhận cải thiện rõ rệt ngay từ lần đầu ạ.",
                        0
                ),
                new FanpageBotResponse.ReplyBubble(
                        "Mức giá 399k đã bao gồm trọn gói (Trà thảo mộc, ăn nhẹ thực dưỡng | Không gian: Phòng chung (có rèm riêng)). Mình thử trải nghiệm trước 1 lần để tự mình kiểm chứng chất lượng nhé?",
                        2000
                )
        );
        List<FanpageBotResponse.ReplyBubble> splitStage2 = CleanAgentOutputRefereeAndSanitizer.splitNaturalTrialInviteBubbleIfApplicable(
                twoBubblesStage2,
                "STAGE_2_FIRST_PRICE_OBJECTION_HOLD_VALUE",
                1200
        );
        assertEquals(3, splitStage2.size(),
                "STAGE_2 phải tách phần câu hỏi mời trải nghiệm độc lập ở cuối thành bong bóng thứ 3 tự nhiên");
        assertEquals(
                "Mức giá 399k đã bao gồm trọn gói (Trà thảo mộc, ăn nhẹ thực dưỡng | Không gian: Phòng chung (có rèm riêng)).",
                splitStage2.get(1).text()
        );
        assertEquals(
                "Mình thử trải nghiệm trước 1 lần để tự mình kiểm chứng chất lượng nhé?",
                splitStage2.get(2).text()
        );

        // 2. Không tách nếu bong bóng vốn đã ngắn (< 16 từ)
        List<FanpageBotResponse.ReplyBubble> shortBubble = List.of(
                new FanpageBotResponse.ReplyBubble("Gói này 399k trọn gói ạ. Mình thử 1 buổi nhé?", 0)
        );
        assertEquals(1, CleanAgentOutputRefereeAndSanitizer.splitNaturalTrialInviteBubbleIfApplicable(
                shortBubble,
                "STAGE_1_DISCOVERY_AND_FIRST_QUOTE",
                1200
        ).size(), "Không được cắt cơ học khi bong bóng ngắn < 16 từ");

        // 3. Không tách nếu câu hỏi ở cuối có cấu trúc phụ thuộc ("Nếu... thì...?" hoặc "Không biết... hay...?")
        List<FanpageBotResponse.ReplyBubble> conditionalBubble = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Dạ gặp tình trạng đau mỏi cổ vai gáy như vậy chắc hẳn gây nhiều bất tiện trong sinh hoạt hàng ngày của chị lắm ạ. Không biết tình trạng này của chị mới xuất hiện mấy hôm nay hay đã kéo dài lâu rồi ạ?",
                        0
                )
        );
        assertEquals(1, CleanAgentOutputRefereeAndSanitizer.splitNaturalTrialInviteBubbleIfApplicable(
                conditionalBubble,
                "STAGE_1_DISCOVERY_AND_FIRST_QUOTE",
                1200
        ).size(), "Không được tách câu hỏi khám phá có cấu trúc 'Không biết... hay...?'");
    }

    @Test
    void testSplitClosingWishBubbleIfApplicable_Stage6ControlledSplit_AndBoundaryProtection() {
        // 1. Dấu phẩy + đại từ "mình" ở STAGE_6 -> tách thành 2 bong bóng, bong bóng 1 kết thúc bằng '.', bong bóng 2 viết hoa "Chúc..."
        List<FanpageBotResponse.ReplyBubble> commaWishMình = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Dạ vâng ạ, em đã lưu lại thông tin gói cùng lưu ý chăm sóc vai gáy của mình rồi ạ. Bất cứ khi nào thu xếp được thời gian cần hỗ trợ thì cứ nhắn em ngay nhé, chúc mình một ngày thật nhiều niềm vui và thư thái ạ.",
                        0
                )
        );
        List<FanpageBotResponse.ReplyBubble> splitComma = CleanAgentOutputRefereeAndSanitizer.splitClosingWishBubbleIfApplicable(
                commaWishMình,
                "STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE",
                1200
        );
        assertEquals(2, splitComma.size(), "Phải tách mệnh đề lời chúc cuối ở STAGE_6 thành bong bóng thứ 2");
        assertEquals(
                "Dạ vâng ạ, em đã lưu lại thông tin gói cùng lưu ý chăm sóc vai gáy của mình rồi ạ. Bất cứ khi nào thu xếp được thời gian cần hỗ trợ thì cứ nhắn em ngay nhé.",
                splitComma.get(0).text()
        );
        assertEquals(
                "Chúc mình một ngày thật nhiều niềm vui và thư thái ạ.",
                splitComma.get(1).text()
        );

        // 2. Dấu chấm + đại từ "anh" / "chị" / "bạn"
        List<FanpageBotResponse.ReplyBubble> dotWishAnh = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Dạ vâng ạ, em đã lưu lại thông tin gói cho anh rồi ạ. Khi nào tiện anh cứ nhắn em ngay nhé. Chúc anh buổi tối thật nhiều niềm vui và thư thái ạ!",
                        0
                )
        );
        List<FanpageBotResponse.ReplyBubble> splitDotAnh = CleanAgentOutputRefereeAndSanitizer.splitClosingWishBubbleIfApplicable(
                dotWishAnh,
                "STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE",
                1200
        );
        assertEquals(2, splitDotAnh.size());
        assertTrue(splitDotAnh.get(0).text().endsWith("."));
        assertEquals("Chúc anh buổi tối thật nhiều niềm vui và thư thái ạ!", splitDotAnh.get(1).text());

        List<FanpageBotResponse.ReplyBubble> semicolonWishChi = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Dạ em đã lưu sẵn lưu ý chăm sóc cổ vai gáy của chị rồi ạ; chúc chị cuối tuần thật nhiều niềm vui và nhẹ nhàng ạ",
                        0
                )
        );
        List<FanpageBotResponse.ReplyBubble> splitSemiChi = CleanAgentOutputRefereeAndSanitizer.splitClosingWishBubbleIfApplicable(
                semicolonWishChi,
                "STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE",
                1200
        );
        assertEquals(2, splitSemiChi.size());
        assertEquals("Dạ em đã lưu sẵn lưu ý chăm sóc cổ vai gáy của chị rồi ạ.", splitSemiChi.get(0).text());
        assertEquals("Chúc chị cuối tuần thật nhiều niềm vui và nhẹ nhàng ạ.", splitSemiChi.get(1).text());

        // 3. Bảo vệ biên: Không tách câu có từ "chúc" nằm trong câu tư vấn ("Chúc mình thử liệu trình này sẽ thấy dễ chịu hơn")
        List<FanpageBotResponse.ReplyBubble> midConsultWish = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Dạ gói này giúp thư giãn cơ sâu lắm ạ, chúc mình thử liệu trình này sẽ thấy dễ chịu hơn nhiều ạ.",
                        0
                )
        );
        assertEquals(1, CleanAgentOutputRefereeAndSanitizer.splitClosingWishBubbleIfApplicable(
                midConsultWish,
                "STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE",
                1200
        ).size(), "Không được tách câu có từ 'chúc' mang nội dung tư vấn liệu trình");

        // 4. Bảo vệ biên: Không tách nếu lời chúc nằm ở đầu/giữa câu chứ không phải mệnh đề kết thúc
        List<FanpageBotResponse.ReplyBubble> wishNotAtEnd = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Dạ vâng ạ, chúc mình một ngày thật nhiều niềm vui ạ, em đã lưu lại thông tin gói cho mình rồi nhé.",
                        0
                )
        );
        assertEquals(1, CleanAgentOutputRefereeAndSanitizer.splitClosingWishBubbleIfApplicable(
                wishNotAtEnd,
                "STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE",
                1200
        ).size(), "Không được tách khi sau lời chúc vẫn còn mệnh đề lưu thông tin/tư vấn");

        // 5. Bảo vệ biên: Không tách lặp khi phản hồi đã có sẵn từ 2 bong bóng trở lên
        assertEquals(2, CleanAgentOutputRefereeAndSanitizer.splitClosingWishBubbleIfApplicable(
                splitComma,
                "STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE",
                1200
        ).size(), "Không được tách lặp khi đã có sẵn 2 bong bóng");

        // 6. Bảo vệ biên: Không tách ở các stage khác ngoài STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE
        assertEquals(1, CleanAgentOutputRefereeAndSanitizer.splitClosingWishBubbleIfApplicable(
                commaWishMình,
                "STAGE_1_DISCOVERY_AND_FIRST_QUOTE",
                1200
        ).size(), "Chỉ áp dụng tách lời chúc tạm biệt khi finalStage là STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE");
    }

    @Test
    void testSkillEmojiSanitization_AndContrastiveSkillAlignmentByFinalStage() {
        List<PackageCatalogService.PackageSummary> allCatalog = sampleSpaCatalog();
        List<String> knownSymptoms = List.of("mỏi vai gáy", "mỏi chân");
        List<String> knownBodyAreas = List.of("vai", "gáy", "mông", "chân");
        CleanAgentCatalogAndPromptBuilder.TieredCatalogResult tiered = CleanAgentCatalogAndPromptBuilder.rankAndTierCatalogForCleanAgent(
                allCatalog, knownSymptoms, knownBodyAreas, java.util.Set.of(), false
        );

        com.example.docmind.dto.bot.AgentSkillDto gracefulExitWithDupEmoji = new com.example.docmind.dto.bot.AgentSkillDto(
                UUID.randomUUID(),
                "spa_graceful_exit_after_rejection",
                "🎯 Tôn trọng quyết định cân nhắc & Giữ thiện cảm (Graceful Exit)",
                "OBJECTION",
                "Tôn trọng quyết định cân nhắc",
                "để cân nhắc thêm, để suy nghĩ thêm, để xem đã, có gì nhắn sau",
                "Tôn trọng nhịp độ của khách",
                "Dạ vâng ạ, em đã lưu lại thông tin gói cho mình rồi ạ.",
                "",
                95,
                true,
                null,
                null,
                null
        );
        com.example.docmind.dto.bot.AgentSkillDto bookingAdvanceSkill = new com.example.docmind.dto.bot.AgentSkillDto(
                UUID.randomUUID(),
                "spa_booking_advance_after_ack",
                "Tiến lên chốt khung giờ đặt lịch sau khi khách đồng ý",
                "SALES_STRATEGY",
                "Chốt khung giờ đặt lịch",
                "ok, ừ, được, chốt, massage đi, đặt lịch",
                "Mời khách chọn khung giờ",
                "Mình tiện ghé khung giờ nào ạ?",
                "",
                90,
                true,
                null,
                null,
                null
        );
        com.example.docmind.dto.bot.AgentSkillDto closingCategorySkill = new com.example.docmind.dto.bot.AgentSkillDto(
                UUID.randomUUID(),
                "spa_trial_acceptance_booking",
                "Hỗ trợ bước chọn khung giờ ghé khi khách đồng ý thử gói (Trial Acceptance)",
                "CLOSING",
                "Hỗ trợ chọn khung giờ",
                "thử 1 buổi, ok thử",
                "Hỏi giờ ghé",
                "Mình ghé lúc mấy giờ ạ?",
                "",
                88,
                true,
                null,
                null,
                null
        );
        List<com.example.docmind.dto.bot.AgentSkillDto> allCandidateSkills = List.of(
                gracefulExitWithDupEmoji,
                bookingAdvanceSkill,
                closingCategorySkill
        );

        // Tình huống 1: "Xem đã nha" (sau khi chê đắt) -> STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL
        List<String> signalsXemDaNha = CleanAgentOrchestratorService.classifyCleanCurrentTurnSignals(
                "Xem đã nha",
                false, false, false, false,
                false, 1, false, false,
                knownSymptoms, knownBodyAreas, "ngồi nhiều",
                CleanAgentOutputRefereeAndSanitizer.SPEECH_ACT_INVITED_TRIAL_OR_TIME,
                "NONE",
                List.of(new FanpageBotRequest.ConversationTurn("user", "Cao thế"))
        );
        String stageXemDaNha = CleanAgentOrchestratorService.resolveCleanFunnelStage(
                signalsXemDaNha, "Xem đã nha", 1, false, tiered, List.of()
        );
        assertEquals("STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL", stageXemDaNha);

        List<com.example.docmind.dto.bot.AgentSkillDto> alignedTurn5 = CleanAgentOrchestratorService.alignCleanAgentActiveSkills(
                List.of(bookingAdvanceSkill, closingCategorySkill),
                allCandidateSkills,
                stageXemDaNha,
                true,
                false,
                false
        );
        assertEquals(1, alignedTurn5.size(), "Ở STAGE_3B chỉ hiển thị đúng kỹ năng Graceful Exit, lọc sạch skill chốt lịch");
        assertEquals("spa_graceful_exit_after_rejection", alignedTurn5.get(0).code());
        assertFalse(alignedTurn5.get(0).name().startsWith("🎯"),
                "Tên skill phải được bỏ tiền tố emoji '🎯' thừa để UI không bị lặp '🎯 🎯': " + alignedTurn5.get(0).name());
        assertEquals("Tôn trọng quyết định cân nhắc & Giữ thiện cảm (Graceful Exit)", alignedTurn5.get(0).name());

        // Tình huống 2: "Ok" đơn lẻ ngay sau khi cân nhắc ("Xem đã nha") -> STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE
        List<FanpageBotRequest.ConversationTurn> historyAfterXemDaNha = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Xem đã nha"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ không sao đâu ạ, mình cứ thoải mái tham khảo thêm, chưa cần vội đặt lịch ngay đâu ạ. Nếu mình muốn thử phương án tiết kiệm hơn thì bên em cũng có Gói dưỡng sinh cơ bản 60 phút (239k trọn gói), khi nào tiện mình cứ nhắn em nhé.")
        );
        List<String> signalsBareOk = CleanAgentOrchestratorService.classifyCleanCurrentTurnSignals(
                "Ok",
                false, false, false, false,
                false, 1, false, false,
                knownSymptoms, knownBodyAreas, "ngồi nhiều",
                CleanAgentOutputRefereeAndSanitizer.SPEECH_ACT_RESPECTED_PAUSE,
                "NONE",
                historyAfterXemDaNha
        );
        String stageBareOk = CleanAgentOrchestratorService.resolveCleanFunnelStage(
                signalsBareOk, "Ok", 1, false, tiered, historyAfterXemDaNha
        );
        assertEquals("STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE", stageBareOk,
                "'Ok' đơn lẻ sau 'Xem đã nha' phải là STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE");

        List<com.example.docmind.dto.bot.AgentSkillDto> alignedTurn6BareOk = CleanAgentOrchestratorService.alignCleanAgentActiveSkills(
                List.of(bookingAdvanceSkill, closingCategorySkill),
                allCandidateSkills,
                stageBareOk,
                true,
                false,
                false
        );
        assertEquals(1, alignedTurn6BareOk.size(),
                "Ở Turn 6 ('Ok' sau 'Xem đã nha'), phải lọc bỏ hoàn toàn 'spa_booking_advance_after_ack' và chỉ giữ Graceful Exit");
        assertEquals("spa_graceful_exit_after_rejection", alignedTurn6BareOk.get(0).code());
        assertFalse(alignedTurn6BareOk.stream().anyMatch(CleanAgentOrchestratorService::isBookingOrClosingSkill));

        // Tình huống 3 (Test đối chứng): "Ok, đặt lịch cho mình lúc 19h" ngay sau khi cân nhắc ("Xem đã nha")
        // -> Nhận diện chính xác ý định đặt lịch (PACKAGE_ACCEPTANCE_OR_BOOKING_PROGRESS -> STAGE_4_POSITIVE_TRIAL_OR_BOOKING) & giữ đầy đủ skill chốt lịch
        String proactiveBookingMsg = "Ok, đặt lịch cho mình lúc 19h";
        assertTrue(CleanAgentOrchestratorService.hasProactiveBookingOrPackageSelectionInMessage(proactiveBookingMsg));
        assertFalse(CleanAgentOrchestratorService.isBareAcknowledgmentMessage(proactiveBookingMsg));

        List<String> signalsProactiveBooking = CleanAgentOrchestratorService.classifyCleanCurrentTurnSignals(
                proactiveBookingMsg,
                false, false, false, false,
                false, 1, true, true,
                knownSymptoms, knownBodyAreas, "ngồi nhiều",
                CleanAgentOutputRefereeAndSanitizer.SPEECH_ACT_RESPECTED_PAUSE,
                "COLLECTING_VISIT_TIME",
                historyAfterXemDaNha
        );
        assertTrue(signalsProactiveBooking.contains("PACKAGE_ACCEPTANCE_OR_BOOKING_PROGRESS"),
                "Khách chủ động nhắn 'Ok, đặt lịch cho mình lúc 19h' phải nhận diện là PACKAGE_ACCEPTANCE_OR_BOOKING_PROGRESS");
        assertFalse(signalsProactiveBooking.contains("POLITE_CLOSING_ACKNOWLEDGMENT"));

        String stageProactiveBooking = CleanAgentOrchestratorService.resolveCleanFunnelStage(
                signalsProactiveBooking, proactiveBookingMsg, 1, true, tiered, historyAfterXemDaNha
        );
        assertEquals("STAGE_4_POSITIVE_TRIAL_OR_BOOKING", stageProactiveBooking,
                "Khách chủ động nhắn 'Ok, đặt lịch cho mình lúc 19h' phải chuyển sang STAGE_4_POSITIVE_TRIAL_OR_BOOKING");

        List<com.example.docmind.dto.bot.AgentSkillDto> alignedProactiveBooking = CleanAgentOrchestratorService.alignCleanAgentActiveSkills(
                List.of(gracefulExitWithDupEmoji, bookingAdvanceSkill),
                allCandidateSkills,
                stageProactiveBooking,
                false,
                false,
                false
        );
        assertTrue(alignedProactiveBooking.stream().anyMatch(s -> "spa_booking_advance_after_ack".equals(s.code())),
                "Khi finalStage == STAGE_4_POSITIVE_TRIAL_OR_BOOKING, phải giữ đầy đủ kỹ năng chốt lịch phù hợp");
        assertFalse(alignedProactiveBooking.stream().anyMatch(s -> "spa_graceful_exit_after_rejection".equals(s.code())),
                "Khi khách đã chuyển sang đặt lịch (STAGE_4), không giữ lại skill Graceful Exit của lượt trước");
    }

    @Test
    void testBrainToStateSynchronization_AndPartialScopeDownsell_FourTurnFlow() {
        PromptBuilder.PagePersona spaPersona = new PromptBuilder.PagePersona(
                "Nàng Sen Spa",
                "Spa dưỡng sinh trị liệu cổ truyền",
                "Khách đau mỏi cổ vai gáy và toàn thân",
                "Tinh tế, giàu cảm xúc"
        );
        List<PackageCatalogService.PackageSummary> allCatalog = sampleSpaCatalog();
        AgentBrainService brainService = new AgentBrainService((com.example.docmind.client.AgentBrainClient) null);
        UUID pageId = UUID.randomUUID();
        String senderId = "cust_brain_to_state_flow_01";
        brainService.clearCustomerMemory(pageId, senderId);

        // =====================================================================
        // 1. Turn 2 ("Đau đầu, mỏi gối tê tay"):
        //    - Chuẩn hóa vùng cơ thể chính xác thành ["đầu", "gối", "tay"]
        //    - [NHÓM 1] = NS01 (399k), [NHÓM 2] = DS02 (239k) kèm ghi chú giới hạn phạm vi
        // =====================================================================
        String turn2Msg = "Đau đầu, mỏi gối tê tay";
        List<String> turn2Areas = CleanAgentOrchestratorService.extractCanonicalBodyAreas(turn2Msg, List.of(), List.of());
        assertEquals(List.of("đầu", "gối", "tay"), turn2Areas,
                "Phải trích xuất chính xác ['đầu', 'gối', 'tay'], không gộp sai gối/tay thành vai gáy và không làm rơi 'đầu'");

        List<String> turn2Symptoms = List.of("đau đầu", "mỏi gối", "tê tay");
        CleanAgentCatalogAndPromptBuilder.TieredCatalogResult tieredTurn2 = CleanAgentCatalogAndPromptBuilder.rankAndTierCatalogForCleanAgent(
                allCatalog, turn2Symptoms, turn2Areas, Set.of(), false
        );
        assertFalse(tieredTurn2.primaryRecommendations().isEmpty());
        assertEquals("NS01", tieredTurn2.primaryRecommendations().get(0).code(),
                "[NHÓM 1] phải chọn gói toàn thân NS01 (399k) bao phủ cả đầu, gối, tay");
        assertEquals(1, tieredTurn2.economicalAlternatives().size(),
                "[NHÓM 2] phải giữ lại DS02 (239k) vì giao thoa 1 phần ở vùng 'đầu'");
        PackageCatalogService.PackageSummary ds02 = tieredTurn2.economicalAlternatives().get(0);
        assertEquals("DS02", ds02.code());
        String partialScopeNote = CleanAgentCatalogAndPromptBuilder.buildPartialScopeLimitationNote(ds02, turn2Areas, turn2Symptoms);
        assertEquals(
                "(Phạm vi hẹp hơn: chỉ tập trung chăm sóc vùng [đầu, cổ, vai, gáy], KHÔNG bao gồm vùng [gối, tay])",
                partialScopeNote
        );

        // =====================================================================
        // 2. Turn 4 ("Cao nhỉ" + câu cảm thán ẩn dụ qua detected_customer_intent):
        //    - Tầng 1 Pre-LLM: "Cao nhỉ" -> confirmedPriceObjectionThisTurn = true, priceObjectionCount = 1,
        //      inPriceObjectionContext = true, STAGE_2_FIRST_PRICE_OBJECTION_HOLD_VALUE,
        //      alignedActiveSkills = price_objection_soft_anchor ("Đồng cảm chi phí"), loại bỏ discovery_ask_one_need
        // =====================================================================
        String turn4Msg = "Cao nhỉ";
        assertTrue(CleanAgentOrchestratorService.isConfirmedCustomerPriceObjection(turn4Msg, true),
                "'Cao nhỉ' sau khi đã báo giá phải được nhận diện là phản đối giá");
        for (String naturalExclamation : List.of(
                "Cao nhỉ", "Cao thế", "Cao quá", "Cao vậy", "Cao ghê", "Cao dữ", "Cao phết",
                "Hơi cao", "Giá cao", "Giá căng", "Căng nhỉ", "Căng thế", "Chát nhỉ", "Chát thế",
                "Hơi chát", "Mắc nhỉ", "Mắc thế", "Hơi mắc", "Đắt nhỉ", "Hơi đắt", "Đắt thế",
                "Đắt quá", "Đau ví quá", "Quá sức với mình", "Nhiều tiền quá"
        )) {
            assertTrue(CleanAgentOrchestratorService.isConfirmedCustomerPriceObjection(naturalExclamation, true),
                    "Phải nhận diện câu cảm thán giá tự nhiên: " + naturalExclamation);
        }

        List<FanpageBotRequest.ConversationTurn> historyBeforeTurn4 = List.of(
                new FanpageBotRequest.ConversationTurn("user", turn2Msg),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ với tình trạng đau đầu, mỏi gối tê tay, em gợi ý mình tham khảo gói Massage cơ bản 60 phút (399k trọn gói) ạ.")
        );
        List<String> signalsTurn4 = CleanAgentOrchestratorService.classifyCleanCurrentTurnSignals(
                turn4Msg,
                false, false, false, false,
                true, 1, false, false,
                turn2Symptoms, turn2Areas, "mấy hôm nay",
                CleanAgentOutputRefereeAndSanitizer.SPEECH_ACT_GENERAL_CONSULTATION,
                "NONE",
                historyBeforeTurn4
        );
        assertTrue(signalsTurn4.contains("PRICE_OBJECTION_FIRST_TIME"));
        String stageTurn4 = CleanAgentOrchestratorService.resolveCleanFunnelStage(
                signalsTurn4, turn4Msg, 1, true, false, false, tieredTurn2, historyBeforeTurn4
        );
        assertEquals("STAGE_2_FIRST_PRICE_OBJECTION_HOLD_VALUE", stageTurn4);

        com.example.docmind.dto.bot.AgentSkillDto discoverySkill = new com.example.docmind.dto.bot.AgentSkillDto(
                UUID.randomUUID(),
                "discovery_ask_one_need",
                "Khám phá nhu cầu 1 câu hỏi",
                "DISCOVERY",
                "Hỏi khám phá nhu cầu",
                "đau mỏi",
                "Hỏi 1 câu",
                "Mình đau lâu chưa ạ?",
                "",
                80,
                true,
                null,
                null,
                null
        );
        List<com.example.docmind.dto.bot.AgentSkillDto> alignedTurn4Skills = CleanAgentOrchestratorService.alignCleanAgentActiveSkills(
                List.of(discoverySkill),
                List.of(discoverySkill),
                stageTurn4,
                false,
                false,
                false
        );
        assertFalse(alignedTurn4Skills.isEmpty());
        assertEquals("price_objection_soft_anchor", alignedTurn4Skills.get(0).code(),
                "Ở STAGE_2_FIRST_PRICE_OBJECTION_HOLD_VALUE phải đồng bộ sang skill 'price_objection_soft_anchor'");
        assertTrue(alignedTurn4Skills.get(0).name().contains("Đồng cảm chi phí"));
        assertFalse(alignedTurn4Skills.stream().anyMatch(s -> "discovery_ask_one_need".equals(s.code())),
                "Không được giữ lại skill 'discovery_ask_one_need' ở lượt khách chê đắt");

        // Kiểm tra Tầng 2 (Post-LLM Brain-to-State Reconciliation) cho câu cảm thán ẩn dụ ngoài Regex
        String metaphoricalPriceMsg = "Ui gần bằng ngày công của mình rồi";
        boolean preLlmMatched = CleanAgentOrchestratorService.isConfirmedCustomerPriceObjection(metaphoricalPriceMsg, true);
        assertFalse(preLlmMatched, "Câu ẩn dụ ngoài Regex ban đầu chưa khớp Pre-LLM");

        String llmJsonWithPriceObjectionIntent = """
                {
                  "reply_bubbles": [
                    {"text": "Dạ mức giá 399k đã bao gồm trọn gói 60 phút chăm sóc toàn thân và ăn nhẹ thực dưỡng ạ.", "typing_delay_ms": 0},
                    {"text": "Mình thử trải nghiệm trước 1 buổi để cảm nhận sự nhẹ nhõm nhé?", "typing_delay_ms": 1200}
                  ],
                  "brain_reflection": {
                    "observed_fact": "Khách so sánh giá gói 399k với ngày công",
                    "care_note_delta": "Khách cân nhắc kỹ chi phí, đau đầu mỏi gối tê tay",
                    "working_hypothesis": "Khách thấy mức giá 399k hơi cao so với ngân sách",
                    "intended_speech_act": "INVITED_TRIAL_OR_ASKED_TIME",
                    "detected_customer_intent": "PRICE_OBJECTION"
                  },
                  "should_handoff": false
                }
                """;
        CleanAgentOutputRefereeAndSanitizer.StructuredLlmOutput parsedMeta = CleanAgentOutputRefereeAndSanitizer.parseStructuredLlmJsonOutput(llmJsonWithPriceObjectionIntent, 10_000);
        assertEquals("PRICE_OBJECTION", parsedMeta.brainReflection().detectedCustomerIntent());
        assertTrue(
                CleanAgentOrchestratorService.shouldReconcilePostLlmPriceObjection(
                        preLlmMatched,
                        parsedMeta.brainReflection().detectedCustomerIntent(),
                        true,
                        metaphoricalPriceMsg
                ),
                "Post-LLM Brain-to-State Reconciliation phải xác nhận phản đối giá khi detected_customer_intent == PRICE_OBJECTION và đã có báo giá"
        );

        // Lưu trạng thái sau Turn 4 vào AgentBrainService
        brainService.recordCommercialFunnelState(pageId, senderId, 1, true, false);
        assertEquals(1, brainService.getRecalledPriceObjectionCount(pageId, senderId));
        assertTrue(brainService.isRecalledInPriceObjectionContext(pageId, senderId));
        assertFalse(brainService.hasRecalledPresentedDownsell(pageId, senderId));

        // =====================================================================
        // 3. Turn 5 ("Ukm xem đã nha"):
        //    - Không để từ đệm "Ukm" ở đầu câu làm mất tín hiệu "xem đã nha"
        //    - Vì inPriceObjectionContext == true && hasPresentedDownsell == false -> STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL
        //    - Prompt gợi ý mềm DS02 (239k) kèm đúng giới hạn phạm vi [đầu, cổ, vai, gáy], KHÔNG bao gồm [gối, tay]
        //    - Sau khi gửi xong, cập nhật hasPresentedDownsell = true
        // =====================================================================
        String turn5Msg = "Ukm xem đã nha";
        assertTrue(CleanAgentOrchestratorService.isHesitationOrDeferralMessage(turn5Msg),
                "Phải nhận diện 'Ukm xem đã nha' là HESITATION_OR_DEFERRAL, không bị từ đệm 'Ukm' ghi đè");
        assertFalse(CleanAgentOrchestratorService.isBareAcknowledgmentMessage(turn5Msg));

        List<FanpageBotRequest.ConversationTurn> historyBeforeTurn5 = List.of(
                new FanpageBotRequest.ConversationTurn("user", turn2Msg),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ với tình trạng đau đầu, mỏi gối tê tay, em gợi ý mình tham khảo gói Massage cơ bản 60 phút (399k trọn gói) ạ."),
                new FanpageBotRequest.ConversationTurn("user", turn4Msg),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ mức giá 399k đã bao gồm trọn gói 60 phút chăm sóc toàn thân và ăn nhẹ thực dưỡng ạ. Mình thử trải nghiệm trước 1 buổi nhé?")
        );
        List<String> signalsTurn5 = CleanAgentOrchestratorService.classifyCleanCurrentTurnSignals(
                turn5Msg,
                false, false, false, false,
                false,
                brainService.getRecalledPriceObjectionCount(pageId, senderId),
                false, false,
                turn2Symptoms, turn2Areas, "mấy hôm nay",
                CleanAgentOutputRefereeAndSanitizer.SPEECH_ACT_INVITED_TRIAL_OR_TIME,
                "NONE",
                historyBeforeTurn5
        );
        assertTrue(signalsTurn5.contains("HESITATION_OR_DEFERRAL"));
        String stageTurn5 = CleanAgentOrchestratorService.resolveCleanFunnelStage(
                signalsTurn5,
                turn5Msg,
                brainService.getRecalledPriceObjectionCount(pageId, senderId),
                brainService.isRecalledInPriceObjectionContext(pageId, senderId),
                brainService.hasRecalledPresentedDownsell(pageId, senderId),
                false,
                tieredTurn2,
                historyBeforeTurn5
        );
        assertEquals("STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL", stageTurn5,
                "Ở Turn 5 ('Ukm xem đã nha' sau khi chê đắt ở Turn 4), phải vào STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL");

        String promptTurn5 = CleanAgentCatalogAndPromptBuilder.buildCleanAgentPrompt(
                spaPersona,
                "mình",
                turn2Symptoms,
                turn2Areas,
                "mấy hôm nay",
                true,
                "Massage cơ bản 60 phút",
                "399k",
                1,
                false,
                "NONE",
                allCatalog,
                List.of(),
                historyBeforeTurn5,
                turn5Msg
        );
        assertTrue(promptTurn5.contains("STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL"));
        assertTrue(promptTurn5.contains("Gói dưỡng sinh cơ bản — Thời lượng: 60 phút — Giá trọn gói: 239k"));
        assertTrue(promptTurn5.contains("(Phạm vi hẹp hơn: chỉ tập trung chăm sóc vùng [đầu, cổ, vai, gáy], KHÔNG bao gồm vùng [gối, tay])"),
                "Prompt Turn 5 phải đính kèm ghi chú giới hạn phạm vi chính xác của DS02 (239k)");

        // Sau khi gửi Turn 5 (STAGE_3B), cập nhật hasPresentedDownsell = true
        brainService.recordCommercialFunnelState(pageId, senderId, 1, true, true);
        assertTrue(brainService.hasRecalledPresentedDownsell(pageId, senderId));

        // =====================================================================
        // 4. Turn 6 ("Ok"):
        //    - Phân tuyến vào STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE (không lặp lại gói 239k)
        //    - Tách lời chúc thành bong bóng thứ 2
        //    - Chỉ hiển thị kỹ năng Graceful Exit
        // =====================================================================
        String turn6Msg = "Ok";
        List<FanpageBotRequest.ConversationTurn> historyBeforeTurn6 = List.of(
                new FanpageBotRequest.ConversationTurn("user", turn5Msg),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ không sao đâu ạ, mình cứ thoải mái tham khảo thêm, chưa cần vội đặt lịch ngay đâu ạ. Nếu mình muốn thử phương án tiết kiệm hơn tập trung chăm sóc vùng đầu, cổ, vai, gáy trước thì bên em cũng có Gói dưỡng sinh cơ bản 60 phút (239k trọn gói), khi nào tiện mình cứ nhắn em nhé.")
        );
        List<String> signalsTurn6 = CleanAgentOrchestratorService.classifyCleanCurrentTurnSignals(
                turn6Msg,
                false, false, false, false,
                false,
                brainService.getRecalledPriceObjectionCount(pageId, senderId),
                false, false,
                turn2Symptoms, turn2Areas, "mấy hôm nay",
                CleanAgentOutputRefereeAndSanitizer.SPEECH_ACT_RESPECTED_PAUSE,
                "NONE",
                historyBeforeTurn6
        );
        assertTrue(signalsTurn6.contains("POLITE_CLOSING_ACKNOWLEDGMENT"));
        String stageTurn6 = CleanAgentOrchestratorService.resolveCleanFunnelStage(
                signalsTurn6,
                turn6Msg,
                brainService.getRecalledPriceObjectionCount(pageId, senderId),
                brainService.isRecalledInPriceObjectionContext(pageId, senderId),
                brainService.hasRecalledPresentedDownsell(pageId, senderId),
                false,
                tieredTurn2,
                historyBeforeTurn6
        );
        assertEquals("STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE", stageTurn6);

        List<FanpageBotResponse.ReplyBubble> turn6SplitBubbles = CleanAgentOutputRefereeAndSanitizer.splitClosingWishBubbleIfApplicable(
                List.of(new FanpageBotResponse.ReplyBubble(
                        "Dạ vâng ạ, em đã lưu lại thông tin gói cùng lưu ý chăm sóc của mình rồi ạ. Bất cứ khi nào thu xếp được thời gian cần hỗ trợ thì cứ nhắn em ngay nhé, chúc mình một ngày thật nhiều niềm vui và thư thái ạ.",
                        0
                )),
                stageTurn6,
                1200
        );
        assertEquals(2, turn6SplitBubbles.size(), "Turn 6 ở STAGE_6 phải tách lời chúc thành bong bóng thứ 2");
        assertTrue(turn6SplitBubbles.get(1).text().startsWith("Chúc mình"));

        List<com.example.docmind.dto.bot.AgentSkillDto> alignedTurn6Skills = CleanAgentOrchestratorService.alignCleanAgentActiveSkills(
                List.of(discoverySkill),
                List.of(discoverySkill),
                stageTurn6,
                true,
                false,
                false
        );
        assertEquals(1, alignedTurn6Skills.size());
        assertEquals("spa_graceful_exit_after_rejection", alignedTurn6Skills.get(0).code());
    }

    @Test
    void testContrastiveGuardrails_NoFalsePositive_NoPrematureDownsell_NoRepeatDownsell_AndBookingTransition() {
        List<PackageCatalogService.PackageSummary> allCatalog = sampleSpaCatalog();
        List<String> symptoms = List.of("đau đầu", "mỏi gối", "tê tay");
        List<String> areas = List.of("đầu", "gối", "tay");
        CleanAgentCatalogAndPromptBuilder.TieredCatalogResult tiered = CleanAgentCatalogAndPromptBuilder.rankAndTierCatalogForCleanAgent(
                allCatalog, symptoms, areas, Set.of(), false
        );

        // =====================================================================
        // Đối chứng 1 (Câu hỏi giá thông thường — Không dương tính giả):
        // Khách hỏi "Giá sao", "Gói 399k là mấy phút?", "399k là 1 buổi à" hoặc nói "Mình bị huyết áp cao"
        // -> priceObjectionCount == 0, inPriceObjectionContext == false
        // =====================================================================
        for (String normalInquiry : List.of(
                "Giá sao",
                "Giá thế nào shop",
                "Bao nhiêu tiền ạ",
                "Gói 399k là mấy phút?",
                "399k là 1 buổi à",
                "Gói 399k gồm những gì vậy em?"
        )) {
            assertTrue(CleanAgentOrchestratorService.isNormalPriceOrDetailInquiryMessage(normalInquiry),
                    "Phải nhận diện là câu hỏi giá/thủ tục thông thường: " + normalInquiry);
            assertFalse(CleanAgentOrchestratorService.isConfirmedCustomerPriceObjection(normalInquiry, true),
                    "Câu hỏi giá thông thường TUYỆT ĐỐI KHÔNG được tính là phản đối giá: " + normalInquiry);
            assertFalse(CleanAgentOrchestratorService.shouldReconcilePostLlmPriceObjection(
                    false, "PRICE_OBJECTION", true, normalInquiry
            ), "Post-LLM reconciliation phải chặn dương tính giả trên câu hỏi giá thông thường: " + normalInquiry);
        }
        assertFalse(CleanAgentOrchestratorService.isConfirmedCustomerPriceObjection("Mình bị huyết áp cao và đau mỏi cổ", true),
                "Câu mô tả bệnh lý 'huyết áp cao' không được tính là chê giá cao");

        // =====================================================================
        // Đối chứng 2 (Xin tạm dừng khi chưa từng chê đắt):
        // priceObjectionCount == 0, inPriceObjectionContext == false -> khách nhắn "Ukm để mình xem đã"
        // -> Phải vào STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE (không tự động hạ giá 239k)
        // =====================================================================
        String pauseWithoutPriceObjMsg = "Ukm để mình xem đã";
        List<String> signalsNoPriceObj = CleanAgentOrchestratorService.classifyCleanCurrentTurnSignals(
                pauseWithoutPriceObjMsg,
                false, false, false, false,
                false, 0, false, false,
                symptoms, areas, "mấy hôm nay",
                CleanAgentOutputRefereeAndSanitizer.SPEECH_ACT_GENERAL_CONSULTATION,
                "NONE",
                List.of(new FanpageBotRequest.ConversationTurn("assistant", "Dạ em gợi ý mình gói Massage cơ bản 60 phút (399k) ạ."))
        );
        String stageNoPriceObj = CleanAgentOrchestratorService.resolveCleanFunnelStage(
                signalsNoPriceObj,
                pauseWithoutPriceObjMsg,
                0,
                false,
                false,
                false,
                tiered,
                List.of()
        );
        assertEquals("STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE", stageNoPriceObj,
                "Khi khách chưa từng chê đắt (inPriceObjectionContext == false), 'Ukm để mình xem đã' phải vào STAGE_6, không tự động gạ gói 239k");

        // =====================================================================
        // Đối chứng 3 (Không lặp lại downsell ở lượt tạm dừng thứ 2):
        // Khách đã được gợi ý gói 239k ở lượt trước (hasPresentedDownsell == true), nhắn tiếp "Để xem thêm đã"
        // -> Phải vào STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE (không lặp lại lời chào gói 239k)
        // =====================================================================
        String secondPauseMsg = "Để xem thêm đã";
        List<String> signalsSecondPause = CleanAgentOrchestratorService.classifyCleanCurrentTurnSignals(
                secondPauseMsg,
                false, false, false, false,
                false, 1, false, false,
                symptoms, areas, "mấy hôm nay",
                CleanAgentOutputRefereeAndSanitizer.SPEECH_ACT_RESPECTED_PAUSE,
                "NONE",
                List.of()
        );
        String stageSecondPause = CleanAgentOrchestratorService.resolveCleanFunnelStage(
                signalsSecondPause,
                secondPauseMsg,
                1,
                true,
                true,
                false,
                tiered,
                List.of()
        );
        assertEquals("STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE", stageSecondPause,
                "Khi hasPresentedDownsell == true, khách nhắn 'Để xem thêm đã' phải vào STAGE_6, không lặp lại gói 239k");

        // =====================================================================
        // Đối chứng 4 (Chuyển sang đặt lịch sau khi cân nhắc):
        // Sau "Ukm xem đã nha", khách nhắn "Ok, đặt lịch cho mình lúc 19h"
        // -> Phải vào STAGE_4_POSITIVE_TRIAL_OR_BOOKING, inPriceObjectionContext chuyển về false
        // =====================================================================
        String bookingAfterPauseMsg = "Ok, đặt lịch cho mình lúc 19h";
        List<String> signalsBookingAfterPause = CleanAgentOrchestratorService.classifyCleanCurrentTurnSignals(
                bookingAfterPauseMsg,
                false, false, false, false,
                false, 1, true, true,
                symptoms, areas, "mấy hôm nay",
                CleanAgentOutputRefereeAndSanitizer.SPEECH_ACT_RESPECTED_PAUSE,
                "COLLECTING_VISIT_TIME",
                List.of()
        );
        String stageBookingAfterPause = CleanAgentOrchestratorService.resolveCleanFunnelStage(
                signalsBookingAfterPause,
                bookingAfterPauseMsg,
                1,
                false,
                true,
                true,
                tiered,
                List.of()
        );
        assertEquals("STAGE_4_POSITIVE_TRIAL_OR_BOOKING", stageBookingAfterPause,
                "Sau khi cân nhắc, khách nhắn 'Ok, đặt lịch cho mình lúc 19h' phải vào STAGE_4_POSITIVE_TRIAL_OR_BOOKING");

        // Kiểm tra thêm: Khi khách CHỈ bị "mỏi gối tê tay" (không có vùng đầu/cổ/vai/gáy), DS02 (239k) có hits == 0 nên phải bị loại (outExcluded)
        List<String> limbOnlyAreas = CleanAgentOrchestratorService.extractCanonicalBodyAreas("Mỏi gối tê tay", List.of(), List.of());
        assertEquals(List.of("gối", "tay"), limbOnlyAreas);
        CleanAgentCatalogAndPromptBuilder.TieredCatalogResult limbOnlyTiered = CleanAgentCatalogAndPromptBuilder.rankAndTierCatalogForCleanAgent(
                allCatalog, List.of("mỏi gối", "tê tay"), limbOnlyAreas, Set.of(), false
        );
        assertTrue(limbOnlyTiered.economicalAlternatives().isEmpty(),
                "Khi khách chỉ mỏi gối tê tay (hits == 0 với [đầu, cổ, vai, gáy]), không được xếp DS02 vào [NHÓM 2]");
        assertTrue(limbOnlyTiered.excludedCatalog().stream().anyMatch(p -> "DS02".equals(p.code())),
                "DS02 phải nằm trong outExcluded khi khách chỉ mỏi gối tê tay");
    }

    @Test
    void testCheckpointA_SixTurnSplitBrainLock_ChatThe_Dat_Ukm_BiLoanA_AndContrastiveSpeechActs() {
        PromptBuilder.PagePersona spaPersona = new PromptBuilder.PagePersona(
                "Nàng Sen Spa",
                "Spa dưỡng sinh trị liệu cổ truyền",
                "Khách đau mỏi vai gáy",
                "Tinh tế, giàu cảm xúc"
        );
        List<PackageCatalogService.PackageSummary> allCatalog = sampleSpaCatalog();
        List<String> symptoms = List.of("đau mỏi vai gáy");
        List<String> areas = List.of("vai", "gáy");
        CleanAgentCatalogAndPromptBuilder.TieredCatalogResult tiered = CleanAgentCatalogAndPromptBuilder.rankAndTierCatalogForCleanAgent(
                allCatalog, symptoms, areas, Set.of(), false
        );
        assertFalse(tiered.economicalAlternatives().isEmpty(),
                "Với khách đau mỏi vai gáy, [NHÓM 2] phải có sẵn DS02 (239k)");
        PackageCatalogService.PackageSummary ds02 = tiered.economicalAlternatives().get(0);
        assertEquals("DS02", ds02.code());

        // =====================================================================
        // 1. Kiểm thử chuỗi 6 lượt: Đau mỏi vai gáy -> Gần đây -> Chát thế -> Đắt -> Ukm -> Bị loạn à
        // =====================================================================
        // Turn 3 ("Chát thế"): Phản đối giá lần 1 -> STAGE_2_FIRST_PRICE_OBJECTION_HOLD_VALUE
        assertTrue(CleanAgentOrchestratorService.isConfirmedCustomerPriceObjection("Chát thế", true));
        // Turn 4 ("Đắt"): Phản đối giá lần 2 -> Phải nhận diện "Đắt" đơn từ là phản đối giá xác thực
        assertTrue(CleanAgentOrchestratorService.isConfirmedCustomerPriceObjection("Đắt", true),
                "Từ đơn 'Đắt' sau khi đã báo giá phải được nhận diện là phản đối giá");
        List<String> turn4Signals = CleanAgentOrchestratorService.classifyCleanCurrentTurnSignals(
                "Đắt",
                false, false, false, false,
                true, 2, false, false,
                symptoms, areas, "gần đây",
                CleanAgentOutputRefereeAndSanitizer.SPEECH_ACT_INVITED_TRIAL_OR_TIME,
                "NONE",
                List.of(
                        new FanpageBotRequest.ConversationTurn("user", "Chát thế"),
                        new FanpageBotRequest.ConversationTurn("assistant", "Dạ gói Massage cơ bản 60 phút (399k) tập trung ấn huyệt sâu vùng vai gáy ạ. Mình thử trải nghiệm 1 buổi nhé?")
                )
        );
        String turn4Stage = CleanAgentOrchestratorService.resolveCleanFunnelStage(
                turn4Signals, "Đắt", 2, true, false, false, tiered, List.of()
        );
        assertEquals("STAGE_3_DOWNSELL_OR_REPEAT_OBJECTION", turn4Stage,
                "Turn 4 ('Đắt' - phản đối giá lần 2) phải vào STAGE_3_DOWNSELL_OR_REPEAT_OBJECTION");

        // Kiểm tra Prompt & Trọng tài ở Turn 4 ("Đắt"):
        // - Phải mở khóa DS02 (239k) trong [NHÓM 2]
        // - Phải bắt lỗi nếu câu trả lời nói "399k là mức thấp nhất bên em có" hoặc "dưới mức này chỉ làm được thư giãn hời hợt"
        // - Phải bắt lỗi nếu câu trả lời ở STAGE_3 không giới thiệu gói tiết kiệm DS02 (239k)
        String turn4Prompt = CleanAgentCatalogAndPromptBuilder.buildCleanAgentPrompt(
                spaPersona, "mình", symptoms, areas, "gần đây", true,
                "Massage cơ bản 60 phút", "399k", 2, false, "NONE",
                turn4Signals, "Mình thử trải nghiệm 1 buổi nhé?", 1,
                tiered, List.of(),
                List.of(
                        new FanpageBotRequest.ConversationTurn("user", "Chát thế"),
                        new FanpageBotRequest.ConversationTurn("assistant", "Dạ gói Massage cơ bản 60 phút (399k) tập trung ấn huyệt sâu vùng vai gáy ạ.")
                ),
                "Đắt"
        );
        assertTrue(turn4Prompt.contains("Gói dưỡng sinh cơ bản") && turn4Prompt.contains("239k"),
                "Prompt ở STAGE_3 ('Đắt') bắt buộc phải hiển thị gói DS02 (239k) trong [NHÓM 2]");
        assertTrue(turn4Prompt.contains("TUYỆT ĐỐI CẤM nói gói Nhóm 1 là 'mức thấp nhất bên em có'"),
                "Prompt ở STAGE_3 phải cấm khẳng định gói Nhóm 1 là mức giá thấp nhất khi [NHÓM 2] có sẵn DS02 (239k)");

        List<String> turn4ContradictionViolations = CleanAgentOutputRefereeAndSanitizer.checkCleanCatalogGrounding(
                List.of(new FanpageBotResponse.ReplyBubble(
                        "Dạ 399k là mức thấp nhất bên em có cho trị liệu đau mỏi vai gáy rồi ạ, vì dưới mức này chỉ làm được thư giãn hời hợt không giải quyết được điểm đau của mình.",
                        0
                )),
                allCatalog,
                tiered.allAllowedPackages(),
                tiered.excludedCatalog(),
                2,
                true,
                ds02,
                false,
                "NONE"
        );
        assertTrue(
                turn4ContradictionViolations.contains("CONTRADICTORY_LOWEST_PRICE_CLAIM_WHEN_DOWNSELL_EXISTS"),
                "Trọng tài phải bắt lỗi khi nói '399k là mức thấp nhất' / 'dưới mức này chỉ làm thư giãn hời hợt' trong khi có gói 239k: " + turn4ContradictionViolations
        );
        assertTrue(
                turn4ContradictionViolations.stream().anyMatch(v -> v.startsWith("MISSING_ECONOMICAL_PACKAGE_IN_STAGE_3_DOWNSELL")),
                "Trọng tài phải bắt lỗi khi ở STAGE_3 (priceObjectionCount >= 2) mà bot bỏ quên không giới thiệu gói tiết kiệm 239k: " + turn4ContradictionViolations
        );

        String sanitizedFalseLowest = CleanAgentOutputRefereeAndSanitizer.sanitizeMinorCleanAgentWordingInPlace(
                "Dạ 399k là mức thấp nhất bên em có cho trị liệu đau mỏi vai gáy rồi ạ, vì dưới mức này chỉ làm được thư giãn hời hợt. Mình cứ cân nhắc thêm nhé ạ.",
                "em",
                "mình",
                ds02,
                false,
                "NONE"
        );
        assertFalse(sanitizedFalseLowest.toLowerCase().contains("mức thấp nhất"),
                "Hậu xử lý phải loại bỏ câu khẳng định sai '399k là mức thấp nhất': " + sanitizedFalseLowest);
        assertTrue(sanitizedFalseLowest.contains("Gói dưỡng sinh cơ bản") && sanitizedFalseLowest.contains("239k"),
                "Hậu xử lý phải thay thế bằng câu giới thiệu trung thực gói DS02 (239k): " + sanitizedFalseLowest);

        // Kiểm tra Grounding trạng thái gói (Fix #1):
        // Nếu ở Turn 4 bot CHƯA nhắc tới 239k trong finalBubbles -> presentedDownsellThisTurn phải là false!
        String turn4BotReplyWithout239k = "Dạ gói 399k là trị liệu chuyên sâu toàn thân ạ. Mình cứ cân nhắc thêm, khi nào sẵn sàng thì nhắn em để mình đặt lịch nhé ạ.";
        assertFalse(
                CleanAgentCatalogAndPromptBuilder.isAnyPackagePresentedInBotText(turn4BotReplyWithout239k, tiered.economicalAlternatives()),
                "Khi finalBubbles không nhắc tới tên/mã/giá gói 239k, gói 239k tuyệt đối chưa được coi là đã giới thiệu"
        );

        // =====================================================================
        // 2. Kiểm thử Đối chứng 3 trường hợp Speech Act (Fix #3): (a), (b), (c)
        // =====================================================================
        // Trường hợp (a): Bot nói "Mình cứ cân nhắc thêm, khi nào sẵn sàng thì nhắn em để mình đặt lịch nhé ạ" -> Khách đáp "Ukm"
        // -> Phải phân loại speech act của bot là RESPECTED_PAUSE_WITH_SOFT_OPTION và định tuyến vào STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE
        String botPauseWithBookingTail = "Mình cứ cân nhắc thêm, khi nào sẵn sàng thì nhắn em để mình đặt lịch nhé ạ.";
        assertTrue(CleanAgentOrchestratorService.isPauseOrDeferralBotReply(botPauseWithBookingTail),
                "Câu bot chủ đạo khuyên 'cứ cân nhắc thêm, khi nào sẵn sàng thì nhắn em để mình đặt lịch' phải được nhận diện là câu tôn trọng tạm dừng");
        String resolvedActCaseA = CleanAgentOutputRefereeAndSanitizer.resolveActualSentSpeechAct(
                "STAGE_3_DOWNSELL_OR_REPEAT_OBJECTION",
                List.of(new FanpageBotResponse.ReplyBubble(botPauseWithBookingTail, 0)),
                "NONE",
                CleanAgentOutputRefereeAndSanitizer.SPEECH_ACT_INVITED_TRIAL_OR_TIME // kể cả khi LLM gán nhầm INVITED_TRIAL_OR_ASKED_TIME
        );
        assertEquals(CleanAgentOutputRefereeAndSanitizer.SPEECH_ACT_RESPECTED_PAUSE, resolvedActCaseA,
                "Post-send verification phải ghi đè về RESPECTED_PAUSE_WITH_SOFT_OPTION khi câu bot khuyên khách cân nhắc thêm");

        List<FanpageBotRequest.ConversationTurn> historyCaseA = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Đắt"),
                new FanpageBotRequest.ConversationTurn("assistant", botPauseWithBookingTail)
        );
        String inferredActCaseA = CleanAgentOrchestratorService.inferPrecedingBotSpeechAct(
                null, botPauseWithBookingTail, historyCaseA, "NONE", CleanAgentOutputRefereeAndSanitizer.SPEECH_ACT_INVITED_TRIAL_OR_TIME
        );
        assertEquals(CleanAgentOutputRefereeAndSanitizer.SPEECH_ACT_RESPECTED_PAUSE, inferredActCaseA);
        List<String> signalsCaseA = CleanAgentOrchestratorService.classifyCleanCurrentTurnSignals(
                "Ukm",
                false, false, false, false,
                false, 2, false, false,
                symptoms, areas, "gần đây",
                inferredActCaseA, "NONE", historyCaseA
        );
        assertTrue(signalsCaseA.contains("POLITE_CLOSING_ACKNOWLEDGMENT"),
                "Trường hợp (a): Khách đáp 'Ukm' sau câu bot khuyên cân nhắc thêm phải mang tín hiệu POLITE_CLOSING_ACKNOWLEDGMENT: " + signalsCaseA);
        String stageCaseA = CleanAgentOrchestratorService.resolveCleanFunnelStage(
                signalsCaseA, "Ukm", 2, true, false, false, tiered, historyCaseA
        );
        assertEquals("STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE", stageCaseA,
                "Trường hợp (a): Khách đáp 'Ukm' sau câu bot khuyên cân nhắc thêm phải vào STAGE_6, tuyệt đối không tự chốt gói 239k hay hỏi giờ đặt lịch");

        // Trường hợp (b): Bot nói "Em mời mình trải nghiệm thử 1 buổi trước nhé, hôm nay hay ngày mai mình tiện ghé khung giờ nào ạ?" -> Khách đáp "Ukm"
        // -> Phải phân loại speech act của bot là INVITED_TRIAL_OR_ASKED_TIME và định tuyến vào STAGE_4_POSITIVE_TRIAL_OR_BOOKING
        String botDirectTrialInvite = "Em mời mình trải nghiệm thử 1 buổi trước nhé, hôm nay hay ngày mai mình tiện ghé khung giờ nào ạ?";
        assertFalse(CleanAgentOrchestratorService.isPauseOrDeferralBotReply(botDirectTrialInvite));
        String inferredActCaseB = CleanAgentOrchestratorService.inferPrecedingBotSpeechAct(
                null, botDirectTrialInvite,
                List.of(new FanpageBotRequest.ConversationTurn("assistant", botDirectTrialInvite)),
                "NONE", null
        );
        assertEquals(CleanAgentOutputRefereeAndSanitizer.SPEECH_ACT_INVITED_TRIAL_OR_TIME, inferredActCaseB);
        List<String> signalsCaseB = CleanAgentOrchestratorService.classifyCleanCurrentTurnSignals(
                "Ukm",
                false, false, false, false,
                false, 1, true, true,
                symptoms, areas, "gần đây",
                inferredActCaseB, "COLLECTING_VISIT_TIME",
                List.of(new FanpageBotRequest.ConversationTurn("assistant", botDirectTrialInvite))
        );
        String stageCaseB = CleanAgentOrchestratorService.resolveCleanFunnelStage(
                signalsCaseB, "Ukm", 1, false, false, true, tiered,
                List.of(new FanpageBotRequest.ConversationTurn("assistant", botDirectTrialInvite))
        );
        assertEquals("STAGE_4_POSITIVE_TRIAL_OR_BOOKING", stageCaseB,
                "Trường hợp (b): Khách đáp 'Ukm' sau câu mời thử/hỏi giờ trực tiếp phải vào STAGE_4_POSITIVE_TRIAL_OR_BOOKING");

        // Trường hợp (c): Khách chủ động nói "Khi nào tiện thì đặt giúp mình lúc 19h nhé"
        // -> Phải phân loại là yêu cầu đặt lịch (PACKAGE_ACCEPTANCE_OR_BOOKING_PROGRESS -> STAGE_4_POSITIVE_TRIAL_OR_BOOKING)
        String customerProactiveBookingMsg = "Khi nào tiện thì đặt giúp mình lúc 19h nhé";
        assertFalse(CleanAgentOrchestratorService.isHesitationOrDeferralMessage(customerProactiveBookingMsg),
                "Câu khách chủ động nhờ đặt lịch lúc 19h không được nhận nhầm là lưỡng lự/tạm dừng");
        assertTrue(CleanAgentOrchestratorService.hasProactiveBookingOrPackageSelectionInMessage(customerProactiveBookingMsg),
                "Câu khách 'Khi nào tiện thì đặt giúp mình lúc 19h nhé' phải được nhận diện là yêu cầu đặt lịch chủ động");
        List<String> signalsCaseC = CleanAgentOrchestratorService.classifyCleanCurrentTurnSignals(
                customerProactiveBookingMsg,
                false, false, false, false,
                false, 1, true, true,
                symptoms, areas, "gần đây",
                CleanAgentOutputRefereeAndSanitizer.SPEECH_ACT_RESPECTED_PAUSE, "COLLECTING_VISIT_TIME", historyCaseA
        );
        assertTrue(signalsCaseC.contains("PACKAGE_ACCEPTANCE_OR_BOOKING_PROGRESS"),
                "Trường hợp (c): Khách chủ động đặt lịch lúc 19h phải có tín hiệu PACKAGE_ACCEPTANCE_OR_BOOKING_PROGRESS: " + signalsCaseC);
        String stageCaseC = CleanAgentOrchestratorService.resolveCleanFunnelStage(
                signalsCaseC, customerProactiveBookingMsg, 1, false, false, true, tiered, historyCaseA
        );
        assertEquals("STAGE_4_POSITIVE_TRIAL_OR_BOOKING", stageCaseC,
                "Trường hợp (c): Khách chủ động đặt lịch lúc 19h phải định tuyến vào STAGE_4_POSITIVE_TRIAL_OR_BOOKING");

        // Kiểm tra thêm ở STAGE_4 khi gói 239k CHƯA TỪNG được giới thiệu trong lịch sử (downsellAlreadyOfferedInHistory == false):
        // Prompt ở STAGE_4 vẫn phải giữ gói 399k (NS01), tuyệt đối không tự nhảy sang gói 239k (DS02)!
        String stage4PromptWithoutPrior239k = CleanAgentCatalogAndPromptBuilder.buildCleanAgentPrompt(
                spaPersona, "mình", symptoms, areas, "gần đây", true,
                "Massage cơ bản 60 phút", "399k", 2, true, "COLLECTING_VISIT_TIME",
                signalsCaseC, botPauseWithBookingTail, 1,
                tiered, List.of(), historyCaseA, customerProactiveBookingMsg
        );
        assertTrue(stage4PromptWithoutPrior239k.contains("Massage cơ bản") && stage4PromptWithoutPrior239k.contains("399k"),
                "Khi gói 239k chưa từng được giới thiệu trong lịch sử, STAGE_4 phải giữ nguyên gói 399k đã báo cho khách");
        assertFalse(stage4PromptWithoutPrior239k.contains("239k"),
                "Khi gói 239k chưa từng được giới thiệu và khách không tự chọn 239k, STAGE_4 tuyệt đối không được tự gán gói 239k");

        // =====================================================================
        // 3. Kiểm soát nguồn gốc careNotes & Active Skills khi Handoff ("Bị loạn à") (Fix #4)
        // =====================================================================
        // 3a. careNotes chỉ giữ thông tin triệu chứng/thời gian từ phía khách, cắt bỏ lời khuyên/quảng cáo của bot ("xử lý sớm sẽ hiệu quả nhanh")
        String mixedCareNote = "Đau mỏi vai gáy mới xuất hiện gần đây, xử lý sớm sẽ hiệu quả nhanh";
        String cleanedCareNote = CleanAgentOutputRefereeAndSanitizer.validateCleanCareNoteDelta(
                mixedCareNote,
                tiered.excludedCatalog(),
                tiered.primaryRecommendations().get(0),
                "Gần đây",
                "Tình trạng mới xuất hiện gần đây nếu xử lý sớm sẽ hiệu quả nhanh lắm ạ."
        );
        assertEquals("Đau mỏi vai gáy mới xuất hiện gần đây", cleanedCareNote,
                "Phải giữ thông tin của khách ('Đau mỏi vai gáy mới xuất hiện gần đây') và cắt bỏ mệnh đề nhận định của bot ('xử lý sớm sẽ hiệu quả nhanh')");

        String pureBotPromoNote = "Chuyên viên lành nghề ấn miết sâu, không làm qua loa bề mặt, xử lý sớm sẽ hiệu quả nhanh";
        assertEquals("", CleanAgentOutputRefereeAndSanitizer.validateCleanCareNoteDelta(pureBotPromoNote, "Gần đây", pureBotPromoNote),
                "Câu thuần quảng cáo/nhận định của bot phải bị loại bỏ hoàn toàn khỏi careNotes");

        // 3b. Ở Turn 6 ("Bị loạn à" -> HUMAN_HANDOFF / STAGE_5_META_COMMUNICATION_REPAIR):
        // Tuyệt đối không hiển thị các skill chốt đơn/khám phá lệch ngữ cảnh như "Trial Acceptance" hay "Condition-First"
        List<com.example.docmind.dto.bot.AgentSkillDto> rawSkillsBeforeHandoff = List.of(
                new com.example.docmind.dto.bot.AgentSkillDto(
                        null, "spa_trial_acceptance_booking", "🎯 Chốt Khung Giờ Khi Khách Đồng Thuận Thử (Trial Acceptance)",
                        "CLOSING", "", "", "", "", "", 95, true, null, null, null
                ),
                new com.example.docmind.dto.bot.AgentSkillDto(
                        null, "spa_condition_first_discovery", "🎯 Khám Phá Tình Trạng Trước Khi Báo Giá (Condition-First)",
                        "DISCOVERY", "", "", "", "", "", 90, true, null, null, null
                )
        );
        List<com.example.docmind.dto.bot.AgentSkillDto> handoffAlignedSkills = CleanAgentOrchestratorService.alignCleanAgentActiveSkills(
                rawSkillsBeforeHandoff,
                rawSkillsBeforeHandoff,
                "HUMAN_HANDOFF",
                false,
                false,
                false
        );
        assertTrue(handoffAlignedSkills.isEmpty(),
                "Khi chuyển sang HUMAN_HANDOFF / COMPLAINT_OR_DISTRESS ('Bị loạn à'), phải lọc sạch các skill Trial Acceptance và Condition-First: " + handoffAlignedSkills);

        List<com.example.docmind.dto.bot.AgentSkillDto> stage5AlignedSkills = CleanAgentOrchestratorService.alignCleanAgentActiveSkills(
                rawSkillsBeforeHandoff,
                rawSkillsBeforeHandoff,
                "STAGE_5_META_COMMUNICATION_REPAIR",
                false,
                false,
                false
        );
        assertTrue(stage5AlignedSkills.isEmpty(),
                "Khi ở STAGE_5_META_COMMUNICATION_REPAIR, phải lọc sạch các skill Trial Acceptance và Condition-First: " + stage5AlignedSkills);
    }

    @Test
    void testSemanticPriceGrounding_SevenMandatoryRegressionCases() {
        List<PackageCatalogService.PackageSummary> allCatalog = sampleSpaCatalog();
        CleanAgentCatalogAndPromptBuilder.TieredCatalogResult neckShoulderTiered =
                CleanAgentCatalogAndPromptBuilder.rankAndTierCatalogForCleanAgent(
                        allCatalog,
                        List.of("đau mỏi cổ vai gáy"),
                        List.of("cổ", "vai", "gáy"),
                        Set.of(),
                        false
                );
        PackageCatalogService.PackageSummary ds02 = neckShoulderTiered.economicalAlternatives().get(0);
        assertEquals("DS02", ds02.code());
        assertEquals(239_000L, ds02.priceVnd());

        // =====================================================================
        // Case 1: Khách hỏi 100k (kể cả biến thể 100.000đ, 100 nghìn);
        //         bot phủ định không có gói 100k và giới thiệu gói phù hợp 239k có trong Catalog -> PASS
        // =====================================================================
        List<String> case1Violations = CleanAgentOutputRefereeAndSanitizer.checkCleanCatalogGrounding(
                List.of(
                        new FanpageBotResponse.ReplyBubble(
                                "Dạ hiện bên em chưa có gói chăm sóc cổ vai gáy ở mức 100k ạ. Trong nhóm dịch vụ chăm sóc đầu – cổ – vai – gáy phù hợp với tình trạng của mình, lựa chọn tiết kiệm nhất hiện tại là Gói dưỡng sinh cơ bản 60 phút (239k trọn gói) tại khu gội chung ạ.",
                                0
                        ),
                        new FanpageBotResponse.ReplyBubble(
                                "Mình cứ thoải mái tham khảo thêm, khi nào tiện nhắn em hỗ trợ nhé ạ.",
                                1200
                        )
                ),
                allCatalog,
                neckShoulderTiered.allAllowedPackages(),
                neckShoulderTiered.excludedCatalog(),
                2,
                true,
                ds02,
                false,
                "NONE",
                "Có gói 100k không shop?"
        );
        assertTrue(case1Violations.isEmpty(),
                "Case 1 (Khách hỏi 100k, bot phủ định 100k và đối chiếu gói 239k hợp lệ) phải PASS grounding: " + case1Violations);

        List<String> case1NghinViolations = CleanAgentOutputRefereeAndSanitizer.checkCleanCatalogGrounding(
                List.of(new FanpageBotResponse.ReplyBubble(
                        "Dạ bên em không có gói 100 nghìn ạ, lựa chọn tiết kiệm nhất trong nhóm chăm sóc đầu – cổ – vai – gáy là Gói dưỡng sinh cơ bản 60 phút (239k trọn gói) ạ.",
                        0
                )),
                allCatalog,
                neckShoulderTiered.allAllowedPackages(),
                neckShoulderTiered.excludedCatalog(),
                2,
                true,
                ds02,
                false,
                "NONE",
                "Bên mình có gói 100 nghìn không?"
        );
        assertTrue(case1NghinViolations.isEmpty(),
                "Case 1b (Biến thể '100 nghìn' được phủ định rõ và đối chiếu gói 239k) phải PASS grounding: " + case1NghinViolations);

        // =====================================================================
        // Case 2: Khách hỏi 150k / 150.000đ; bot phủ định mức 150k và nêu giá Catalog đã xác minh -> PASS
        // =====================================================================
        List<String> case2Violations = CleanAgentOutputRefereeAndSanitizer.checkCleanCatalogGrounding(
                List.of(new FanpageBotResponse.ReplyBubble(
                        "Dạ ở mức 150.000đ hiện bên em chưa có gói chăm sóc cổ vai gáy ạ. Trong nhóm dịch vụ chăm sóc đầu – cổ – vai – gáy phù hợp với mình, gói tiết kiệm nhất là Gói dưỡng sinh cơ bản 60 phút (239k trọn gói) ạ.",
                        0
                )),
                allCatalog,
                neckShoulderTiered.allAllowedPackages(),
                neckShoulderTiered.excludedCatalog(),
                2,
                true,
                ds02,
                false,
                "NONE",
                "Tầm 150k hay 150.000đ có gói nào không em?"
        );
        assertTrue(case2Violations.isEmpty(),
                "Case 2 (Khách hỏi 150k / 150.000đ, bot phủ định 150.000đ và nêu gói 239k) phải PASS grounding: " + case2Violations);

        // =====================================================================
        // Case 3: Bot khẳng định tồn tại gói 100k không có trong Catalog -> FAIL grounding
        // =====================================================================
        List<String> case3Violations = CleanAgentOutputRefereeAndSanitizer.checkCleanCatalogGrounding(
                List.of(new FanpageBotResponse.ReplyBubble(
                        "Dạ bên em có gói 100k chăm sóc cổ vai gáy nhẹ nhàng cho mình ạ.",
                        0
                )),
                allCatalog,
                neckShoulderTiered.allAllowedPackages(),
                neckShoulderTiered.excludedCatalog(),
                1,
                true,
                ds02,
                false,
                "NONE",
                "Có gói 100k không shop?"
        );
        assertTrue(
                case3Violations.stream().anyMatch(v -> v.startsWith("UNGROUNDED_OR_EXCLUDED_PRICE:")),
                "Case 3 (Bot khẳng định tồn tại gói 100k không có trong Catalog) bắt buộc phải FAIL grounding: " + case3Violations
        );

        // =====================================================================
        // Case 4: Bot bịa thời lượng hoặc quyền lợi của gói 100k (VD: "Gói 100k có thời lượng 60 phút") -> FAIL grounding
        // =====================================================================
        List<String> case4Violations = CleanAgentOutputRefereeAndSanitizer.checkCleanCatalogGrounding(
                List.of(new FanpageBotResponse.ReplyBubble(
                        "Dạ Gói 100k có thời lượng 60 phút hiện bên em chưa áp dụng, mình tham khảo Gói dưỡng sinh cơ bản 239k (60 phút) nhé ạ.",
                        0
                )),
                allCatalog,
                neckShoulderTiered.allAllowedPackages(),
                neckShoulderTiered.excludedCatalog(),
                2,
                true,
                ds02,
                false,
                "NONE",
                "Có gói 100k không shop?"
        );
        assertTrue(
                case4Violations.stream().anyMatch(v -> v.startsWith("UNGROUNDED_OR_EXCLUDED_PRICE:")),
                "Case 4 (Bot bịa thời lượng/quyền lợi cho gói 100k) bắt buộc phải FAIL grounding: " + case4Violations
        );

        // =====================================================================
        // Case 5: Bot nhắc 100k trong ngữ cảnh phủ định nhưng đưa ra một mức giá đề xuất khác không có trong Catalog -> FAIL grounding
        // =====================================================================
        List<String> case5Violations = CleanAgentOutputRefereeAndSanitizer.checkCleanCatalogGrounding(
                List.of(new FanpageBotResponse.ReplyBubble(
                        "Dạ hiện bên em chưa có gói ở mức 100k ạ, nhưng mình có thể tham khảo gói chăm sóc cổ vai gáy 180k bên em nhé.",
                        0
                )),
                allCatalog,
                neckShoulderTiered.allAllowedPackages(),
                neckShoulderTiered.excludedCatalog(),
                2,
                true,
                ds02,
                false,
                "NONE",
                "Có gói 100k không shop?"
        );
        assertTrue(
                case5Violations.stream().anyMatch(v -> v.equals("UNGROUNDED_OR_EXCLUDED_PRICE:180k")),
                "Case 5 (Bot phủ định 100k nhưng đề xuất giá 180k ngoài Catalog) bắt buộc phải FAIL grounding ở mức 180k: " + case5Violations
        );

        // =====================================================================
        // Case 6: Bot nhắc giá 100k trong câu khẳng định/mơ hồ không rõ nghĩa phủ định -> FAIL grounding
        // =====================================================================
        List<String> case6Violations = CleanAgentOutputRefereeAndSanitizer.checkCleanCatalogGrounding(
                List.of(new FanpageBotResponse.ReplyBubble(
                        "Dạ với mức 100k mình đang quan tâm thì bên em có Gói dưỡng sinh cơ bản 239k (60 phút) chăm sóc cổ vai gáy rất tốt ạ.",
                        0
                )),
                allCatalog,
                neckShoulderTiered.allAllowedPackages(),
                neckShoulderTiered.excludedCatalog(),
                2,
                true,
                ds02,
                false,
                "NONE",
                "Có gói 100k không shop?"
        );
        assertTrue(
                case6Violations.stream().anyMatch(v -> v.equals("UNGROUNDED_OR_EXCLUDED_PRICE:100k")),
                "Case 6 (Bot nhắc 100k trong câu mơ hồ không rõ nghĩa phủ định) bắt buộc phải FAIL grounding: " + case6Violations
        );

        // =====================================================================
        // Case 7: Khách đau cổ vai gáy, Catalog có gói 129k (Lấy ráy tai) không khớp vùng đau -> Không đề xuất gói 129k
        // =====================================================================
        assertFalse(
                neckShoulderTiered.allAllowedPackages().stream().anyMatch(p -> p.priceVnd() == 129_000L || "DS01".equals(p.code())),
                "Case 7a: Gói 129k (Lấy ráy tai) phải bị loại khỏi danh sách gói hợp lệ khi khách đau cổ vai gáy"
        );
        List<String> case7Violations = CleanAgentOutputRefereeAndSanitizer.checkCleanCatalogGrounding(
                List.of(new FanpageBotResponse.ReplyBubble(
                        "Dạ hiện bên em chưa có gói 100k ạ, nếu mình muốn gói rẻ nhất thì có gói Lấy ráy tai cơ bản 129k (30 phút) ạ.",
                        0
                )),
                allCatalog,
                neckShoulderTiered.allAllowedPackages(),
                neckShoulderTiered.excludedCatalog(),
                2,
                true,
                ds02,
                false,
                "NONE",
                "Có gói 100k không shop?"
        );
        assertTrue(
                case7Violations.stream().anyMatch(v -> v.equals("UNGROUNDED_OR_EXCLUDED_PRICE:129k")),
                "Case 7b (Đề xuất gói 129k Lấy ráy tai cho khách đau cổ vai gáy) bắt buộc phải FAIL grounding: " + case7Violations
        );
        assertTrue(
                case7Violations.stream().anyMatch(v -> v.startsWith("EXCLUDED_PACKAGE_MENTIONED:")),
                "Case 7c (Nhắc tên gói Lấy ráy tai cơ bản đã bị loại theo vùng đau) bắt buộc phải FAIL grounding: " + case7Violations
        );
    }

    @Test
    void testSkillLabelCleanup_AndMetaphoricalWageComparisonObjection_WithNegativeControlsAndTwoTurnFlow() {
        List<PackageCatalogService.PackageSummary> allCatalog = sampleSpaCatalog();
        List<String> symptoms = List.of("đau mỏi cổ vai gáy");
        List<String> areas = List.of("cổ", "vai", "gáy");
        CleanAgentCatalogAndPromptBuilder.TieredCatalogResult tiered =
                CleanAgentCatalogAndPromptBuilder.rankAndTierCatalogForCleanAgent(
                        allCatalog, symptoms, areas, Set.of(), false
                );

        // =====================================================================
        // Phần II.1: Làm sạch Skill Label & Loại bỏ "Khám phá tình trạng đau mỏi (Condition-First)" ở STAGE_2
        // =====================================================================
        com.example.docmind.dto.bot.AgentSkillDto dbFirstPriceObjectionSkill = new com.example.docmind.dto.bot.AgentSkillDto(
                UUID.randomUUID(),
                "spa_objection_value_trial",
                "🎯 Xử lý phản đối giá lần 1 bám sát Catalog đã xác minh (Tối đa 2 bong bóng)",
                "OBJECTION",
                "Xử lý phản đối giá lần 1",
                "đắt, cao nhỉ",
                "Giữ nguyên gói đang tư vấn",
                "Dạ mức giá 399k đã bao gồm trọn gói 60 phút ạ.",
                "Tự động hạ giá ngay lần đầu",
                95,
                true,
                null,
                null,
                null
        );
        com.example.docmind.dto.bot.AgentSkillDto leftoverConditionFirstSkill = new com.example.docmind.dto.bot.AgentSkillDto(
                UUID.randomUUID(),
                "spa_condition_first_discovery",
                "Khám phá tình trạng đau mỏi (Condition-First)",
                "DISCOVERY",
                "Khám phá tình trạng đau mỏi trước khi báo giá",
                "đau cổ vai gáy",
                "Hỏi thời gian đau mỏi",
                "Mình bị đau mỏi lâu chưa ạ?",
                "",
                90,
                true,
                null,
                null,
                null
        );

        com.example.docmind.dto.bot.AgentSkillDto sanitizedSkill =
                CleanAgentOrchestratorService.sanitizeSkillDisplayNameForUi(dbFirstPriceObjectionSkill);
        assertEquals(
                "Đồng cảm chi phí & bảo vệ giá trị liệu trình (Value Hold)",
                sanitizedSkill.name(),
                "Phải đổi nhãn hiển thị của skill phản đối giá lần 1 thành 'Đồng cảm chi phí & bảo vệ giá trị liệu trình (Value Hold)'"
        );

        List<com.example.docmind.dto.bot.AgentSkillDto> alignedStage2Skills =
                CleanAgentOrchestratorService.alignCleanAgentActiveSkills(
                        List.of(leftoverConditionFirstSkill, dbFirstPriceObjectionSkill),
                        List.of(leftoverConditionFirstSkill, dbFirstPriceObjectionSkill),
                        "STAGE_2_FIRST_PRICE_OBJECTION_HOLD_VALUE",
                        false,
                        false,
                        false
                );
        assertEquals(1, alignedStage2Skills.size(),
                "Ở STAGE_2_FIRST_PRICE_OBJECTION_HOLD_VALUE, phải loại bỏ 'Khám phá tình trạng đau mỏi (Condition-First)' còn sót từ lượt trước");
        assertEquals(
                "Đồng cảm chi phí & bảo vệ giá trị liệu trình (Value Hold)",
                alignedStage2Skills.get(0).name()
        );
        assertFalse(
                alignedStage2Skills.stream().anyMatch(s -> s.name().contains("Condition-First") || "spa_condition_first_discovery".equals(s.code())),
                "Tuyệt đối không còn nhãn 'Khám phá tình trạng đau mỏi (Condition-First)' trong danh sách hiển thị của STAGE_2"
        );

        // =====================================================================
        // Phần II.2a: Nhận diện phản đối giá ẩn dụ ("ngày công", "lương") sau khi đã báo giá
        // =====================================================================
        for (String metaphoricalPositive : List.of(
                "Ui gần 1 ngày công của t",
                "Gần một ngày công của em rồi",
                "Gần một ngày lương",
                "Bằng cả tháng lương"
        )) {
            assertTrue(
                    CleanAgentOrchestratorService.isConfirmedCustomerPriceObjection(metaphoricalPositive, true),
                    "Phải nhận diện câu phản đối giá ẩn dụ sau khi đã báo giá: " + metaphoricalPositive
            );
        }

        // =====================================================================
        // Phần II.2b: Chặn dương tính giả (Negative cases)
        // =====================================================================
        assertFalse(
                CleanAgentOrchestratorService.isConfirmedCustomerPriceObjection("Em đi làm cả ngày, tối mới rảnh", true),
                "Negative case 1: 'Em đi làm cả ngày, tối mới rảnh' TUYỆT ĐỐI KHÔNG phải phản đối giá"
        );
        assertFalse(
                CleanAgentOrchestratorService.isConfirmedCustomerPriceObjection("Em đi làm cả ngày, tối mới rảnh", false),
                "Negative case 1b: 'Em đi làm cả ngày, tối mới rảnh' khi chưa báo giá cũng KHÔNG phải phản đối giá"
        );
        assertFalse(
                CleanAgentOrchestratorService.isConfirmedCustomerPriceObjection("Tháng lương này em chưa nhận", false),
                "Negative case 2a: 'Tháng lương này em chưa nhận' khi chưa báo giá KHÔNG mặc định là phản đối giá"
        );
        assertFalse(
                CleanAgentOrchestratorService.isConfirmedCustomerPriceObjection("Tháng lương này em chưa nhận", true),
                "Negative case 2b: 'Tháng lương này em chưa nhận' (chia sẻ bối cảnh chưa nhận lương, không phải cấu trúc so sánh đắt/cao) KHÔNG mặc định là phản đối giá"
        );

        // =====================================================================
        // Phần II.2c: Giữ mạch ngữ cảnh qua 2 lượt ("Ui gần 1 ngày công của t" -> "Ok để tối về t xem nhé")
        // =====================================================================
        String turn1MetaphorMsg = "Ui gần 1 ngày công của t";
        List<FanpageBotRequest.ConversationTurn> historyBeforeMetaphor = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Mình bị đau mỏi cổ vai gáy mấy hôm nay"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ với tình trạng đau mỏi cổ vai gáy mới xuất hiện mấy hôm nay, em gợi ý mình tham khảo gói Massage cơ bản 60 phút (399k trọn gói) ạ.")
        );
        boolean confirmedObjTurn1 = CleanAgentOrchestratorService.isConfirmedCustomerPriceObjection(turn1MetaphorMsg, true);
        assertTrue(confirmedObjTurn1);

        List<String> signalsTurn1Metaphor = CleanAgentOrchestratorService.classifyCleanCurrentTurnSignals(
                turn1MetaphorMsg,
                false, false, false, false,
                confirmedObjTurn1, 1, false, false,
                symptoms, areas, "mấy hôm nay",
                CleanAgentOutputRefereeAndSanitizer.SPEECH_ACT_INVITED_TRIAL_OR_TIME,
                "NONE",
                historyBeforeMetaphor
        );
        assertTrue(signalsTurn1Metaphor.contains("PRICE_OBJECTION_FIRST_TIME"));
        String stageTurn1Metaphor = CleanAgentOrchestratorService.resolveCleanFunnelStage(
                signalsTurn1Metaphor, turn1MetaphorMsg, 1, true, false, false, tiered, historyBeforeMetaphor
        );
        assertEquals("STAGE_2_FIRST_PRICE_OBJECTION_HOLD_VALUE", stageTurn1Metaphor);

        // Lượt kế tiếp: Khách nhắn "Ok để tối về t xem nhé"
        String turn2HesitationMsg = "Ok để tối về t xem nhé";
        List<FanpageBotRequest.ConversationTurn> historyAfterMetaphor = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Mình bị đau mỏi cổ vai gáy mấy hôm nay"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ với tình trạng đau mỏi cổ vai gáy mới xuất hiện mấy hôm nay, em gợi ý mình tham khảo gói Massage cơ bản 60 phút (399k trọn gói) ạ."),
                new FanpageBotRequest.ConversationTurn("user", turn1MetaphorMsg),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ giá trị của gói Massage cơ bản 60 phút (399k) nằm ở trọn vẹn thời lượng chuyên viên tập trung làm mềm sâu vùng cơ cổ vai gáy và đã bao gồm bữa ăn nhẹ thực dưỡng ạ. Mình thử trải nghiệm trước 1 buổi nhé?")
        );

        int historyPriceObjCount = CleanAgentOrchestratorService.countConfirmedCustomerPriceObjectionsInHistory(historyAfterMetaphor);
        assertEquals(1, historyPriceObjCount,
                "Lịch sử hội thoại phải bảo toàn tín hiệu phản đối giá của lượt 'Ui gần 1 ngày công của t' (count == 1)");
        assertTrue(CleanAgentOrchestratorService.isHesitationOrDeferralMessage(turn2HesitationMsg),
                "'Ok để tối về t xem nhé' phải được nhận diện là lượt lưỡng lự / hẹn xem lại");
        assertFalse(CleanAgentOrchestratorService.isPureBusyOrStopDeferralMessage(turn2HesitationMsg),
                "'Ok để tối về t xem nhé' sau phản đối giá là tâm lý lưỡng lự vì giá, KHÔNG phải báo bận việc cá nhân thuần túy");

        List<String> signalsTurn2Hesitation = CleanAgentOrchestratorService.classifyCleanCurrentTurnSignals(
                turn2HesitationMsg,
                false, false, false, false,
                false, historyPriceObjCount, false, false,
                symptoms, areas, "mấy hôm nay",
                CleanAgentOutputRefereeAndSanitizer.SPEECH_ACT_INVITED_TRIAL_OR_TIME,
                "NONE",
                historyAfterMetaphor
        );
        assertTrue(signalsTurn2Hesitation.contains("HESITATION_OR_DEFERRAL"));
        String stageTurn2Hesitation = CleanAgentOrchestratorService.resolveCleanFunnelStage(
                signalsTurn2Hesitation,
                turn2HesitationMsg,
                historyPriceObjCount,
                true,
                false,
                false,
                tiered,
                historyAfterMetaphor
        );
        assertEquals(
                "STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL",
                stageTurn2Hesitation,
                "Lượt 'Ok để tối về t xem nhé' ngay sau 'Ui gần 1 ngày công của t' phải kích hoạt STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL"
        );

        // Đối chứng: Khi khách báo bận việc cá nhân thuần túy ("Em đang bận việc chút, để tối nhắn lại nhé") -> STAGE_6
        String pureBusyMsg = "Em đang bận việc chút, để tối nhắn lại nhé";
        assertTrue(CleanAgentOrchestratorService.isPureBusyOrStopDeferralMessage(pureBusyMsg));
        List<String> signalsPureBusy = CleanAgentOrchestratorService.classifyCleanCurrentTurnSignals(
                pureBusyMsg,
                false, false, false, false,
                false, historyPriceObjCount, false, false,
                symptoms, areas, "mấy hôm nay",
                CleanAgentOutputRefereeAndSanitizer.SPEECH_ACT_INVITED_TRIAL_OR_TIME,
                "NONE",
                historyAfterMetaphor
        );
        String stagePureBusy = CleanAgentOrchestratorService.resolveCleanFunnelStage(
                signalsPureBusy, pureBusyMsg, historyPriceObjCount, true, false, false, tiered, historyAfterMetaphor
        );
        assertEquals(
                "STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE",
                stagePureBusy,
                "Khi khách báo bận việc cá nhân thuần túy ('Em đang bận việc chút...'), phải tôn trọng tạm dừng ở STAGE_6"
        );
    }
}
