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

    @Test
    void testBrainReflectionParser_FaultTolerant_WhenMissingOrMalformed() {
        List<PackageCatalogService.PackageSummary> allCatalog = sampleSpaCatalog();
        FanpageBotService.TieredCatalogResult tiered = FanpageBotService.rankAndTierCatalogForCleanAgent(
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
        FanpageBotService.StructuredLlmOutput parsedValid = FanpageBotService.parseStructuredLlmJsonOutput(validJsonWithReflection, 10_000);
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
            FanpageBotService.StructuredLlmOutput out = FanpageBotService.parseStructuredLlmJsonOutput(variant, 10_000);
            assertTrue(out.parsedFromJson(), "Parser phải chịu lỗi với biến thể: " + variant);
            assertEquals(1, out.bubbles().size());
            assertEquals("Dạ em chào chị ạ.", out.bubbles().get(0).text());
            assertNotNull(out.brainReflection());
        }

        // 3. Khi phần "brain_reflection" ở cuối JSON bị cắt cụt do maxTokens -> vẫn cứu được nguyên vẹn "reply_bubbles"!
        String truncatedReflectionJson = "{\"reply_bubbles\": [{\"text\": \"Dạ gói Massage cơ bản 60 phút có giá 399k trọn gói ạ.\", \"typing_delay_ms\": 0}], \"brain_reflection\": {\"observed_fact\": \"Khách hỏi giá";
        FanpageBotService.StructuredLlmOutput salvaged = FanpageBotService.parseStructuredLlmJsonOutput(truncatedReflectionJson, 10_000);
        assertTrue(salvaged.parsedFromJson(), "Khi brain_reflection bị cắt cụt ở cuối, parser phải cứu được reply_bubbles");
        assertEquals(1, salvaged.bubbles().size());
        assertEquals("Dạ gói Massage cơ bản 60 phút có giá 399k trọn gói ạ.", salvaged.bubbles().get(0).text());

        // 4. Kiểm tra xác thực care_note_delta: chặn cam kết y khoa lâm sàng và tên gói bị loại (excludedCatalog)
        assertEquals(
                "",
                FanpageBotService.validateCleanCareNoteDelta("Cam kết chữa khỏi hẳn bệnh thoái hóa đốt sống cổ cho khách", tiered.excludedCatalog()),
                "Phải loại bỏ care_note_delta chứa cam kết chữa khỏi bệnh lý y khoa"
        );
        assertEquals(
                "",
                FanpageBotService.validateCleanCareNoteDelta("Khách hợp với gói Lấy ráy tai cơ bản DS01", tiered.excludedCatalog()),
                "Phải loại bỏ care_note_delta chứa tên/mã gói nằm trong excludedCatalog"
        );
        String validCareNote = FanpageBotService.validateCleanCareNoteDelta(
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
        String bookingPromptWithRecalledMemory = FanpageBotService.buildCleanAgentPrompt(
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
        String promptCaseA = FanpageBotService.buildCleanAgentPrompt(
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
                FanpageBotService.resolveCleanAgentThinkingMode(false, false, false, true, false, true, false, false, List.of("đau cổ")),
                "Trường hợp A (lưỡng lự vì giá) phải dùng OBJECTION_REFRAME_THINK"
        );

        // 2. Trường hợp B1: Khách báo bận / hẹn tối xem lại ("Chị đang bận chút, để tối xem lại nhé") ngay cả khi priceObjectionCount == 1
        String promptCaseBBusy = FanpageBotService.buildCleanAgentPrompt(
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
                FanpageBotService.resolveCleanAgentThinkingMode(false, false, false, false, true, true, false, false, List.of("đau cổ")),
                "Trường hợp B (khách bận / tạm dừng thuần túy) phải dùng FAST_NO_THINK để phản hồi nhanh, nhẹ nhàng"
        );

        // 3. Trường hợp B2: Khách nói "Để xem đã" khi chưa từng chê đắt (priceObjectionCount == 0) -> Tôn trọng nhịp độ của khách (STAGE_6)
        String promptCaseBNoPriceObj = FanpageBotService.buildCleanAgentPrompt(
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
        String normalizedBuffet = FanpageBotService.sanitizeMinorCleanAgentWordingInPlace(
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
        String verifiedSpeechActTurn5 = FanpageBotService.resolveActualSentSpeechAct(
                "STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL",
                turn5Bubbles,
                "NONE",
                "INVITED_TRIAL_OR_ASKED_TIME"
        );
        assertEquals(
                FanpageBotService.SPEECH_ACT_RESPECTED_PAUSE,
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
        assertEquals(FanpageBotService.SPEECH_ACT_RESPECTED_PAUSE, insightForTurn6.lastActualSentSpeechAct());

        List<FanpageBotRequest.ConversationTurn> historyAfterTurn5 = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Ok để nghĩ thêm"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ không sao đâu ạ, mình cứ thoải mái tham khảo và cân nhắc thêm, chưa cần vội đặt lịch ngay đâu ạ. Nếu mình muốn thử một phương án tiết kiệm hơn thì bên em cũng có gói dưỡng sinh cơ bản 60 phút (239k trọn gói), khi nào tiện mình cứ nhắn em nhé.")
        );
        String promptCase1 = FanpageBotService.buildCleanAgentPrompt(
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
                FanpageBotService.SPEECH_ACT_INVITED_TRIAL_OR_TIME
        );
        List<FanpageBotRequest.ConversationTurn> historyAfterTrialInvite = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Giá 399k hơi cao nhỉ"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ gói Massage cơ bản 60 phút (399k) tập trung xử lý sâu vùng cổ vai gáy, mình thử trải nghiệm trước 1 lần để cảm nhận nhé?")
        );
        String promptCase2 = FanpageBotService.buildCleanAgentPrompt(
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
                FanpageBotService.SPEECH_ACT_OFFERED_DETAILS
        );
        List<FanpageBotRequest.ConversationTurn> historyAfterOfferDetails = List.of(
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ bên em có Gói dưỡng sinh cơ bản 60 phút (239k trọn gói), mình có muốn nghe thêm chi tiết về gói này không ạ?")
        );
        String promptCase3 = FanpageBotService.buildCleanAgentPrompt(
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
                FanpageBotService.SPEECH_ACT_BOOKING_CONFIRMED
        );
        String promptCase4 = FanpageBotService.buildCleanAgentPrompt(
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
        String promptCase5Proactive = FanpageBotService.buildCleanAgentPrompt(
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
        List<String> violationsWhenUnconfirmed = FanpageBotService.checkCleanCatalogGrounding(
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
        String sanitizedMidSentence = FanpageBotService.sanitizeMinorCleanAgentWordingInPlace(
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

        String sanitizedStartSentence = FanpageBotService.sanitizeMinorCleanAgentWordingInPlace(
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
        String sanitizedWhenConfirmed = FanpageBotService.sanitizeMinorCleanAgentWordingInPlace(
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
        List<String> violationsOnNs01 = FanpageBotService.checkCleanCatalogGrounding(
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

        String sanitizedBuffetAnNhe = FanpageBotService.sanitizeMinorCleanAgentWordingInPlace(
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
        List<String> violationsOnRealBuffetPkg = FanpageBotService.checkCleanCatalogGrounding(
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

        String preservedBuffet = FanpageBotService.sanitizeMinorCleanAgentWordingInPlace(
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
        FanpageBotService.TieredCatalogResult tiered = FanpageBotService.rankAndTierCatalogForCleanAgent(
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

        String scopeLimitationNote = FanpageBotService.buildPartialScopeLimitationNote(
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
                    FanpageBotService.isHesitationOrDeferralMessage(shortDeferral),
                    "Phải nhận diện '" + shortDeferral + "' là lời lưỡng lự/tạm hoãn"
            );
        }

        List<String> turn5Signals = FanpageBotService.classifyCleanCurrentTurnSignals(
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
                FanpageBotService.SPEECH_ACT_INVITED_TRIAL_OR_TIME,
                "NONE",
                List.of(
                        new FanpageBotRequest.ConversationTurn("user", "Cao thế"),
                        new FanpageBotRequest.ConversationTurn("assistant", "Giá trị của gói Massage cơ bản 60 phút (399k) nằm ở trọn vẹn thời lượng chuyên viên lành nghề chăm sóc sâu. Mình thử trải nghiệm trước 1 lần nhé?")
                )
        );
        assertEquals(List.of("HESITATION_OR_DEFERRAL"), turn5Signals,
                "Turn 5 ('Xem đã') chỉ được mang tín hiệu HESITATION_OR_DEFERRAL, KHÔNG được tự động gán SHARING_CONDITION_OR_CONTEXT");

        String turn5Stage = FanpageBotService.resolveCleanFunnelStage(
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
        List<com.example.docmind.dto.bot.AgentSkillDto> alignedSkillsTurn5 = FanpageBotService.alignCleanAgentActiveSkills(
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

        String promptTurn5 = FanpageBotService.buildCleanAgentPrompt(
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
                FanpageBotService.SPEECH_ACT_RESPECTED_PAUSE
        );
        List<FanpageBotRequest.ConversationTurn> historyAfterTurn5 = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Xem đã"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ không sao đâu ạ, mình cứ thoải mái tham khảo thêm, chưa cần vội đặt lịch ngay đâu ạ. Nếu mình muốn thử phương án tiết kiệm hơn giải tỏa trước vùng cổ vai gáy (chưa bao gồm vùng mông, chân) thì bên em cũng có Gói dưỡng sinh cơ bản 60 phút (239k trọn gói), khi nào tiện mình cứ nhắn em nhé.")
        );
        String promptTurn6 = FanpageBotService.buildCleanAgentPrompt(
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
        List<ReciprocalRankFusion.FusedChunk> filteredForNs01 = FanpageBotService.filterChunksByVisibleCatalogScope(
                List.of(buffetFaqChunk),
                List.of(ns01)
        );
        assertTrue(filteredForNs01.isEmpty(),
                "Chunk giới thiệu Buffet thực dưỡng phải bị loại khỏi <DOCUMENT_CONTEXT> khi chỉ hiển thị gói tiêu chuẩn NS01");

        List<ReciprocalRankFusion.FusedChunk> filteredForNs04 = FanpageBotService.filterChunksByVisibleCatalogScope(
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
        List<FanpageBotResponse.ReplyBubble> splitStage2 = FanpageBotService.splitNaturalTrialInviteBubbleIfApplicable(
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
        assertEquals(1, FanpageBotService.splitNaturalTrialInviteBubbleIfApplicable(
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
        assertEquals(1, FanpageBotService.splitNaturalTrialInviteBubbleIfApplicable(
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
        List<FanpageBotResponse.ReplyBubble> splitComma = FanpageBotService.splitClosingWishBubbleIfApplicable(
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
        List<FanpageBotResponse.ReplyBubble> splitDotAnh = FanpageBotService.splitClosingWishBubbleIfApplicable(
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
        List<FanpageBotResponse.ReplyBubble> splitSemiChi = FanpageBotService.splitClosingWishBubbleIfApplicable(
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
        assertEquals(1, FanpageBotService.splitClosingWishBubbleIfApplicable(
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
        assertEquals(1, FanpageBotService.splitClosingWishBubbleIfApplicable(
                wishNotAtEnd,
                "STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE",
                1200
        ).size(), "Không được tách khi sau lời chúc vẫn còn mệnh đề lưu thông tin/tư vấn");

        // 5. Bảo vệ biên: Không tách lặp khi phản hồi đã có sẵn từ 2 bong bóng trở lên
        assertEquals(2, FanpageBotService.splitClosingWishBubbleIfApplicable(
                splitComma,
                "STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE",
                1200
        ).size(), "Không được tách lặp khi đã có sẵn 2 bong bóng");

        // 6. Bảo vệ biên: Không tách ở các stage khác ngoài STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE
        assertEquals(1, FanpageBotService.splitClosingWishBubbleIfApplicable(
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
        FanpageBotService.TieredCatalogResult tiered = FanpageBotService.rankAndTierCatalogForCleanAgent(
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
        List<String> signalsXemDaNha = FanpageBotService.classifyCleanCurrentTurnSignals(
                "Xem đã nha",
                false, false, false, false,
                false, 1, false, false,
                knownSymptoms, knownBodyAreas, "ngồi nhiều",
                FanpageBotService.SPEECH_ACT_INVITED_TRIAL_OR_TIME,
                "NONE",
                List.of(new FanpageBotRequest.ConversationTurn("user", "Cao thế"))
        );
        String stageXemDaNha = FanpageBotService.resolveCleanFunnelStage(
                signalsXemDaNha, "Xem đã nha", 1, false, tiered, List.of()
        );
        assertEquals("STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL", stageXemDaNha);

        List<com.example.docmind.dto.bot.AgentSkillDto> alignedTurn5 = FanpageBotService.alignCleanAgentActiveSkills(
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
        List<String> signalsBareOk = FanpageBotService.classifyCleanCurrentTurnSignals(
                "Ok",
                false, false, false, false,
                false, 1, false, false,
                knownSymptoms, knownBodyAreas, "ngồi nhiều",
                FanpageBotService.SPEECH_ACT_RESPECTED_PAUSE,
                "NONE",
                historyAfterXemDaNha
        );
        String stageBareOk = FanpageBotService.resolveCleanFunnelStage(
                signalsBareOk, "Ok", 1, false, tiered, historyAfterXemDaNha
        );
        assertEquals("STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE", stageBareOk,
                "'Ok' đơn lẻ sau 'Xem đã nha' phải là STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE");

        List<com.example.docmind.dto.bot.AgentSkillDto> alignedTurn6BareOk = FanpageBotService.alignCleanAgentActiveSkills(
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
        assertFalse(alignedTurn6BareOk.stream().anyMatch(FanpageBotService::isBookingOrClosingSkill));

        // Tình huống 3 (Test đối chứng): "Ok, đặt lịch cho mình lúc 19h" ngay sau khi cân nhắc ("Xem đã nha")
        // -> Nhận diện chính xác ý định đặt lịch (PACKAGE_ACCEPTANCE_OR_BOOKING_PROGRESS -> STAGE_4_POSITIVE_TRIAL_OR_BOOKING) & giữ đầy đủ skill chốt lịch
        String proactiveBookingMsg = "Ok, đặt lịch cho mình lúc 19h";
        assertTrue(FanpageBotService.hasProactiveBookingOrPackageSelectionInMessage(proactiveBookingMsg));
        assertFalse(FanpageBotService.isBareAcknowledgmentMessage(proactiveBookingMsg));

        List<String> signalsProactiveBooking = FanpageBotService.classifyCleanCurrentTurnSignals(
                proactiveBookingMsg,
                false, false, false, false,
                false, 1, true, true,
                knownSymptoms, knownBodyAreas, "ngồi nhiều",
                FanpageBotService.SPEECH_ACT_RESPECTED_PAUSE,
                "COLLECTING_VISIT_TIME",
                historyAfterXemDaNha
        );
        assertTrue(signalsProactiveBooking.contains("PACKAGE_ACCEPTANCE_OR_BOOKING_PROGRESS"),
                "Khách chủ động nhắn 'Ok, đặt lịch cho mình lúc 19h' phải nhận diện là PACKAGE_ACCEPTANCE_OR_BOOKING_PROGRESS");
        assertFalse(signalsProactiveBooking.contains("POLITE_CLOSING_ACKNOWLEDGMENT"));

        String stageProactiveBooking = FanpageBotService.resolveCleanFunnelStage(
                signalsProactiveBooking, proactiveBookingMsg, 1, true, tiered, historyAfterXemDaNha
        );
        assertEquals("STAGE_4_POSITIVE_TRIAL_OR_BOOKING", stageProactiveBooking,
                "Khách chủ động nhắn 'Ok, đặt lịch cho mình lúc 19h' phải chuyển sang STAGE_4_POSITIVE_TRIAL_OR_BOOKING");

        List<com.example.docmind.dto.bot.AgentSkillDto> alignedProactiveBooking = FanpageBotService.alignCleanAgentActiveSkills(
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
}
