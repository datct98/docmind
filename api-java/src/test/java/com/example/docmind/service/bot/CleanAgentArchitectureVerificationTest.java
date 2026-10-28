package com.example.docmind.service.bot;

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

        FanpageBotService.TieredCatalogResult result = FanpageBotService.rankAndTierCatalogForCleanAgent(
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

        FanpageBotService.TieredCatalogResult result = FanpageBotService.rankAndTierCatalogForCleanAgent(
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
        String promptStage1 = FanpageBotService.buildCleanAgentPrompt(
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
        String promptStage2 = FanpageBotService.buildCleanAgentPrompt(
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
        String promptStage3 = FanpageBotService.buildCleanAgentPrompt(
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
        String promptStage5 = FanpageBotService.buildCleanAgentPrompt(
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
        FanpageBotService.TieredCatalogResult tiered = FanpageBotService.rankAndTierCatalogForCleanAgent(
                allCatalog,
                List.of("đau mỏi cổ"),
                List.of("cổ", "vai", "gáy"),
                Set.of(),
                false
        );

        // 1. CHO PHÉP HOÀN TOÀN ngôn từ cảm xúc dịch vụ: "giảm đau mỏi hiệu quả", "mở từng điểm cơ căng cứng", "đào thải độc tố", "nhẹ nhõm từ bên trong"
        List<String> emotionalOk = FanpageBotService.checkCleanCatalogGrounding(
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
        List<String> clinicalViolations = FanpageBotService.checkCleanCatalogGrounding(
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
        List<String> pronounViolations1 = FanpageBotService.checkCleanCatalogGrounding(
                List.of(new FanpageBotResponse.ReplyBubble("Dạ mình đã hiểu rồi ạ, để em kiểm tra cho chị nhé.", 0)),
                allCatalog,
                tiered.allAllowedPackages(),
                tiered.excludedCatalog()
        );
        assertTrue(pronounViolations1.contains("FORBIDDEN_WRONG_PRONOUN_OR_SUBJECTLESS_OPENER"));

        assertEquals(
                "Dạ em hiểu ạ, tình trạng này chắc làm chị mỏi lắm.",
                FanpageBotService.sanitizeMinorCleanAgentWordingInPlace("Dạ mình đã hiểu rồi ạ, tình trạng này chắc làm chị mỏi lắm.", "em")
        );
        assertEquals(
                "Dạ em hiểu ạ.",
                FanpageBotService.sanitizeMinorCleanAgentWordingInPlace("Dạ hiểu rồi ạ.", "em")
        );

        // 4. Chặn tự ý gạ gói rẻ hơn ở lần chê đắt đầu tiên (PREMATURE_DOWNSELL_ON_FIRST_OBJECTION) và chặn tự mâu thuẫn (vừa gạ gói rẻ vừa hỏi giờ đặt lịch)
        List<String> prematureDownsellViolations = FanpageBotService.checkCleanCatalogGrounding(
                List.of(new FanpageBotResponse.ReplyBubble("Dạ hay để em gợi ý gói tiết kiệm hơn cho mình trải nghiệm thử nhé?", 0)),
                allCatalog,
                tiered.primaryRecommendations(),
                tiered.excludedCatalog(),
                1,
                false
        );
        assertTrue(prematureDownsellViolations.contains("PREMATURE_DOWNSELL_ON_FIRST_OBJECTION"),
                "Phải chặn tự gạ gói tiết kiệm hơn ở lần chê đắt đầu tiên: " + prematureDownsellViolations);

        List<String> contradictoryViolations = FanpageBotService.checkCleanCatalogGrounding(
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
        List<String> selfConfessionViolations = FanpageBotService.checkCleanCatalogGrounding(
                List.of(new FanpageBotResponse.ReplyBubble("Dạ tại em lỡ miệng nhắc lại thông tin cũ nên mong chị thông cảm ạ.", 0)),
                allCatalog,
                tiered.allAllowedPackages(),
                tiered.excludedCatalog()
        );
        assertTrue(selfConfessionViolations.contains("FORBIDDEN_INTERNAL_REPAIR_LEAK_SELF_CONFESSION"));

        String repairPrompt = FanpageBotService.buildCleanRepairPrompt("PROMPT_GỐC", List.of("CROSS_TURN_REPETITION"), "em", "chị");
        assertTrue(repairPrompt.contains("CHỈ DẪN KỸ THUẬT NỘI BỘ: Khách hàng KHÔNG phàn nàn về việc lặp lời. Tuyệt đối CẤM viết câu xin lỗi kiểu 'tại em lỡ miệng nhắc lại thông tin cũ'."));

        // 6. Kiểm tra phân loại tín hiệu: "Đắt nhỉ", "Hơi cao", "Để xem đã", "Để tính đã", "Chưa biết" KHÔNG bị gán vào PACKAGE_ACCEPTANCE_OR_BOOKING_PROGRESS
        for (String msg : List.of("Đắt nhỉ", "Hơi cao", "Để xem đã", "Để tính đã", "Chưa biết")) {
            List<String> sigs = FanpageBotService.classifyCleanCurrentTurnSignals(
                    msg, false, false, false, false,
                    FanpageBotService.isCleanPriceObjectionMessage(msg),
                    1, false, false, List.of("đau gáy"), List.of("gáy"), "mới bị"
            );
            assertFalse(sigs.contains("PACKAGE_ACCEPTANCE_OR_BOOKING_PROGRESS"),
                    "Tin nhắn '" + msg + "' tuyệt đối không được gán vào PACKAGE_ACCEPTANCE_OR_BOOKING_PROGRESS: " + sigs);
        }

        // "Ừ, nghe ổn", "Nghe ổn hơn đó" -> POSITIVE_INTEREST_READY_FOR_TRIAL_INVITE
        for (String posMsg : List.of("Ừ, nghe ổn", "Nghe ổn hơn đó")) {
            List<String> sigs = FanpageBotService.classifyCleanCurrentTurnSignals(
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

        boolean repeated = FanpageBotService.detectCleanCrossTurnRepetition(
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
            String dentalPrompt = FanpageBotService.buildCleanAgentPrompt(
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

        String promptStandard = FanpageBotService.buildCleanAgentPrompt(
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
        List<String> privateRoomOn399k = FanpageBotService.checkCleanCatalogGrounding(
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

        String sanitized399k = FanpageBotService.sanitizeMinorCleanAgentWordingInPlace(
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
        List<String> mismatchOn239k = FanpageBotService.checkCleanCatalogGrounding(
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

        String sanitized239k = FanpageBotService.sanitizeMinorCleanAgentWordingInPlace(
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
        String promptTurn2 = FanpageBotService.buildCleanAgentPrompt(
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
        List<String> violations = FanpageBotService.checkCleanCatalogGrounding(
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
        String rewritten = FanpageBotService.sanitizeMinorCleanAgentWordingInPlace(
                "Dạ mình đang quan tâm đến gói Massage cơ bản 60 phút (399k) ạ.",
                "em"
        );
        assertEquals(
                "Dạ em gợi ý mình tham khảo gói Massage cơ bản 60 phút (399k) ạ.",
                rewritten
        );

        // 4. Khi khách đã hạ bậc sang gói Nhóm 2 (priceObjectionCount == 2) rồi đồng thuận ("Ừ, nghe ổn hơn đó"):
        // Turn 5 phải vào STAGE_4_POSITIVE_TRIAL_OR_BOOKING và khóa chặt vào gói Nhóm 2 (DS02 - 239k)
        String promptTurn5AfterDownsell = FanpageBotService.buildCleanAgentPrompt(
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
}
