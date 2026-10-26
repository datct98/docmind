package com.example.docmind.service.bot;

import com.docmind.guardrail.ReplyContractValidator;
import com.example.docmind.dto.bot.FanpageBotRequest.ConversationTurn;
import com.example.docmind.dto.bot.FanpageBotResponse.ReplyBubble;
import com.example.docmind.repository.RetrievedChunk;
import com.example.docmind.service.retrieval.ReciprocalRankFusion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DocmindV340EmotionalRefereeTest {

    private static final UUID PAGE_ID = UUID.fromString("b23cd382-4f7a-4829-8c54-1d410da14bf8");

    private final PackageCatalogService.PackageSummary ns01Package = new PackageCatalogService.PackageSummary(
            "HN_NS01", "NS01", "HN", "Massage cơ bản", "MASSAGE",
            List.of("lưng", "cổ", "vai", "gáy", "chân", "tay"),
            60, "60 phút", 399_000L, "399k",
            "Phòng chung yên tĩnh, sạch thoáng, mỗi giường đều có rèm che riêng kín đáo (không phải phòng VIP riêng)",
            "Massage toàn thân, tập trung chăm sóc các vùng cơ thể đang đau mỏi, buffet thực dưỡng",
            "Khách đau mỏi cơ bản",
            "Ấn huyệt và thả lỏng cơ thể",
            false
    );

    @Test
    @DisplayName("TEST R — PRESERVE AUTHENTIC EMOTION: Câu văn tinh tế, chạm giác quan thật (kể cả dài 46-54 âm tiết) được GIỮ NGUYÊN 100%")
    void testR_PreserveAuthenticEmotionalResponse() {
        List<ReplyBubble> soulfulBubbles = List.of(
                new ReplyBubble("Dạ lần đầu chưa quen chỗ nên mình cân nhắc là rất tự nhiên ạ. Gói 60 phút bên em tập trung ấn đằm và miết dịu dọc vùng thắt lưng đang căng mỏi, làm xong mình còn dùng bữa ăn nhẹ thực dưỡng ấm bụng mà không lo phát sinh tiền Tip đâu ạ 🌿", 0),
                new ReplyBubble("Hôm nay mình muốn thử 1 buổi để cảm nhận xem lực tay kỹ thuật viên có hợp với cơ thể mình không ạ 🥰", 2000)
        );
        assertTrue(ReplyContractValidator.satisfiesRefereeTruthAndAestheticContract(soulfulBubbles, ns01Package));
    }

    @Test
    @DisplayName("TEST S — BLOCK FAKE COMMITMENTS & CLICHES (Vá C.1): Chặn đứng 'hoàn tiền 100%', 'trả góp 0%', '7k/phút', 'tan chảy như bơ'")
    void testS_BlockFakeCommitmentsAndCliches() {
        List<ReplyBubble> fakeCommitment = List.of(
                new ReplyBubble("Nếu mình không hài lòng, bên em cam kết hoàn tiền 100% trong buổi đầu tiên ạ!", 0)
        );
        assertFalse(ReplyContractValidator.satisfiesRefereeTruthAndAestheticContract(fakeCommitment, ns01Package),
                "Phải bắt chính xác cụm 'hoàn tiền 100%' ngay cả khi đứng trước khoảng trắng!");

        List<ReplyBubble> clicheMetaphor = List.of(
                new ReplyBubble("Tính ra chỉ 7k/phút rẻ hơn một ly cà phê, giúp mình tan chảy như bơ ạ", 0)
        );
        assertFalse(ReplyContractValidator.satisfiesRefereeTruthAndAestheticContract(clicheMetaphor, ns01Package));
    }

    @Test
    @DisplayName("TEST T — BLOCK UNGROUNDED SERVICE CLAIMS: Đang tư vấn gói NS01 mà tự bịa 'xông hơi đá nóng' hoặc 'phòng VIP' phải bị chặn")
    void testT_BlockUngroundedPhysicalClaims() {
        List<ReplyBubble> ungroundedProcess = List.of(
                new ReplyBubble("Dạ gói 60 phút này mình sẽ được xông hơi đá nóng và nằm phòng VIP riêng biệt rất thư giãn ạ 🌿", 0)
        );
        assertFalse(ReplyContractValidator.satisfiesRefereeTruthAndAestheticContract(ungroundedProcess, ns01Package));
    }

    @Test
    @DisplayName("TEST U — ALLOW HONEST NEGATION CLARIFICATION (Vá C.2): Giải thích trung thực 'không phải phòng VIP, chưa bao gồm xông hơi' phải được CHO QUA")
    void testU_AllowHonestNegationClarification() {
        List<ReplyBubble> honestClarification = List.of(
                new ReplyBubble("Dạ gói 399k này mình nằm phòng chung có rèm kéo riêng kín đáo chứ không phải phòng VIP riêng, và chưa bao gồm xông hơi ạ 🌿", 0)
        );
        assertTrue(ReplyContractValidator.satisfiesRefereeTruthAndAestheticContract(honestClarification, ns01Package),
                "Câu giải thích phủ định trung thực không được bị đánh nhầm là bịa đặt dịch vụ!");
    }

    @Test
    @DisplayName("TEST V (Gate 4 — Mục 2) — AUTO-SPLIT 3-SENTENCE BUBBLE: Bong bóng 3 câu tự động tách thành 2 bong bóng (2 câu + 1 câu với delay 1500ms), không mất chữ nào")
    void testV_AutoSplitThreeSentenceBubbleIntoTwoBubblesWithoutTruncation() {
        String s1 = "Dạ nằm xuống mà đầu còn váng, cổ vai lại đơ cứng thì trằn trọc khó vào giấc lắm ạ.";
        String s2 = "Bên em có gói Massage trị liệu cơ bản 60 phút giúp ấn miết mở cơ dọc vùng vai gáy rất êm.";
        String s3 = "Mình bị tình trạng này mới dạo gần đây hay đã kéo dài lâu rồi ạ?";
        String combinedThreeSentences = s1 + " " + s2 + " " + s3;

        List<ReplyBubble> input = List.of(new ReplyBubble(combinedThreeSentences, 0));
        List<ReplyBubble> split = ReplyContractValidator.autoSplitBubblesExceedingTwoSentences(input);

        assertEquals(2, split.size(), "Phải tự động tách bong bóng 3 câu thành 2 bong bóng");
        assertEquals(s1 + " " + s2, split.get(0).text(), "Bong bóng A phải giữ nguyên vẹn 2 câu đầu");
        assertEquals(0, split.get(0).typingDelayMs());
        assertEquals(s3, split.get(1).text(), "Bong bóng B phải giữ nguyên vẹn câu thứ 3, cấm cắt cụt");
        assertEquals(1500, split.get(1).typingDelayMs(), "Bong bóng B tách ra phải có typing_delay_ms = 1500ms");

        // Kiểm tra tích hợp qua validateAndEnforce: không báo lỗi TOO_MANY_SENTENCES và giữ đủ cả 2 bong bóng
        ReplyContractValidator.ValidationResult valRes = ReplyContractValidator.validateAndEnforce(
                input,
                null,
                "Toii hơi đau đầu, mất ngủ và mỏi vai",
                PrimaryActionRouter.PrimaryAction.ACKNOWLEDGE_AND_ASK_PREFERENCE,
                false,
                10000
        );
        assertFalse(valRes.errors().contains("TOO_MANY_SENTENCES"), "Sau khi auto-split (tổng <= 3 bong bóng) không được báo lỗi TOO_MANY_SENTENCES");
        assertEquals(2, valRes.validatedBubbles().size());
        assertEquals(1500, valRes.validatedBubbles().get(1).typingDelayMs());
    }

    @Test
    @DisplayName("TEST W (Gate 3, Mục 4 & Mục 6) — COMPASS + SENSORY_PALETTE + SPACE NOTE + OPENER GUARD")
    void testW_CompassSensoryPaletteAndSpaceNoteAndOpenerGuard() {
        // 1. Mục 6: roomType() giữ nguyên cho TEST K, customerFacingSpaceNote() ẩn chữ "phòng chung"
        assertTrue(ns01Package.roomType().toLowerCase().contains("phòng chung"));
        assertFalse(ns01Package.customerFacingSpaceNote().toLowerCase().contains("phòng chung"));
        assertEquals("không gian yên tĩnh, sạch thoáng, mỗi giường đều có rèm che riêng kín đáo", ns01Package.customerFacingSpaceNote());

        // 2. Gate 3 (Mục 7 & 8): buildAuthenticEmotionalCompassBlock chứa 3 Quy tắc Vàng + <SENSORY_PALETTE>
        String compass = FanpageBotPromptBuilder.buildAuthenticEmotionalCompassBlock(
                ThinkingMode.PRICE_ANCHOR_THINK, "mình", ns01Package, "Nàng Sen Spa"
        );
        assertTrue(compass.contains("<AUTHENTIC_EMOTIONAL_COMPASS>"));
        assertTrue(compass.contains("3 QUY TẮC VÀNG CỦA NGƯỜI TƯ VẤN CÓ HỒN"));
        assertTrue(compass.contains("<SENSORY_PALETTE>"));
        assertTrue(compass.contains("Không gian: " + ns01Package.customerFacingSpaceNote()), "Compass phải dùng customerFacingSpaceNote() thay vì roomType()");

        // 3. Mục 4: Chặn văn mẫu mở đầu "Dạ với tình trạng ... xuất hiện của mình"
        ReplyContractValidator.ValidationResult mechOpenerRes = ReplyContractValidator.validateAndEnforce(
                List.of(new ReplyBubble("Dạ với tình trạng đau đầu, mất ngủ và mỏi vai thi thoảng xuất hiện của mình, bên em có gói Massage cơ bản 60 phút giá 399k ạ.", 0)),
                null,
                "Thi thoảng bị",
                PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE,
                false,
                10000
        );
        assertTrue(mechOpenerRes.errors().contains("FORBIDDEN_MECHANICAL_OPENER"));
    }

    @Test
    @DisplayName("TEST V (v3.4.2 Mục 3) — POLITE DEFERRAL EXIT: 'Ukm, để t cân nhắc thêm' sau downsell -> 0 xin lỗi, 0 câu hỏi, đúng badge spa_graceful_exit_after_rejection")
    void testV_PoliteDeferralAfterDownsellNoApology() {
        ConversationState.clearSessionCache();
        String catalogTable = """
                | Mã gói | Tên gói | Giá | Tổng thời gian | Bao gồm | Không gian | Điểm nổi bật | Phù hợp |
                | --- | --- | --- | --- | --- | --- | --- | --- |
                | NS01 | Massage cơ bản | 399.000VNĐ | 60 phút | Massage toàn thân, tập trung chăm sóc các vùng cơ thể đang đau mỏi + Buffet thực dưỡng | Phòng chung (có rèm riêng tư) | Trọn gói không nhận Tip, ăn nhẹ sau liệu trình | Khách đau mỏi lưng, cổ vai, chân tay |
                | DS02 | Gói dưỡng sinh cơ bản | 239.000VNĐ | 60 phút | Gội đầu cơ bản + massage cổ vai gáy + ăn nhẹ | Khu dưỡng sinh có rèm che riêng | Thư giãn nhẹ nhàng, trọn gói không nhận Tip | Khách muốn thư giãn đầu, cổ vai |
                """;
        List<ReciprocalRankFusion.FusedChunk> chunks = List.of(new ReciprocalRankFusion.FusedChunk(
                new RetrievedChunk(UUID.randomUUID(), UUID.randomUUID(), List.of("BẢNG GIÁ DỊCH VỤ", "Cơ sở Hà Nội"), List.of(), 1, new double[]{0, 0, 0, 0}, catalogTable, 0.95),
                0.95
        ));
        List<String> keywords = List.of("massage", "cổ", "vai", "gáy", "dưỡng sinh", "399k", "239k");
        List<ConversationTurn> historyAfterDownsell = List.of(
                new ConversationTurn("user", "Giá"),
                new ConversationTurn("assistant", "Dạ chào mình, mình đang quan tâm nhóm dịch vụ nào ạ?"),
                new ConversationTurn("user", "Tôi bị đau mỏi vai gáy, dễ mất ngủ"),
                new ConversationTurn("assistant", "Dạ tình trạng mỏi vai gáy của mình xuất hiện thường xuyên hay thi thoảng ạ?"),
                new ConversationTurn("user", "Thi thoảng"),
                new ConversationTurn("assistant", "Dạ bên em có gói Massage cơ bản 60 phút giá 399k (trọn gói không nhận Tip) ạ."),
                new ConversationTurn("user", "Mà hơi đắt nhỉ"),
                new ConversationTurn("assistant", "Dạ mình có thể thử trải nghiệm lẻ 1 buổi xem có hợp tay nghề kỹ thuật viên không ạ 🥰"),
                new ConversationTurn("user", "Mà vẫn thấy hơi đắt nhỉ"),
                new ConversationTurn("assistant", "Dạ vậy mình tham khảo Gói dưỡng sinh cơ bản 60 phút giá 239k (trọn gói không nhận Tip) kết hợp gội đầu và massage cổ vai gáy ạ.")
        );

        String userMsg = "Ukm, để t cân nhắc thêm";
        ConversationState state = ConversationState.hydrateForTurn(
                PAGE_ID, "u-defer-v342", "c-defer-v342", false, userMsg, historyAfterDownsell, keywords, chunks
        );
        assertTrue(state.downsellOffered(), "State phải ghi nhận đã downsell 239k");
        assertTrue(SentimentIntentLayer.isPoliteDeferralExit(userMsg), "Phải nhận diện đúng POLITE_DEFERRAL_EXIT");

        SentimentIntentLayer.DetectedIntent detectedIntent = SentimentIntentLayer.classify(userMsg, state);
        assertEquals(SentimentIntentLayer.DetectedIntent.POLITE_DEFERRAL_EXIT, detectedIntent);

        PrimaryActionRouter.RoutingDecision decision = PrimaryActionRouter.route(userMsg, state, chunks, keywords);
        assertEquals("POLITE_DEFERRAL_EXIT", decision.detectedIntent());
        assertEquals("spa_graceful_exit_after_rejection", decision.correspondingMicroSkillCode(),
                "Badge kỹ năng phải là spa_graceful_exit_after_rejection thay vì spa_price_objection_2step");
        assertEquals(1, decision.maxResponseBubblesForLlm());

        // Validator phải chặn đứng nếu LLM lỡ sinh câu xin lỗi hoặc câu hỏi ở POLITE_DEFERRAL_EXIT
        ReplyContractValidator.ValidationResult apologyAttempt = ReplyContractValidator.validateAndEnforce(
                List.of(new ReplyBubble("Dạ em xin lỗi mình vì phần tư vấn vừa rồi chưa làm mình hài lòng. Em xin phép không làm phiền thêm ạ.", 0)),
                state,
                userMsg,
                decision.effectiveResponseAction(),
                false,
                2000
        );
        assertTrue(apologyAttempt.errors().contains("FORBIDDEN_APOLOGY_ON_POLITE_DEFERRAL"),
                "Validator phải chặn đứng từ 'xin lỗi' / 'không làm phiền' khi khách chỉ tạm hoãn lịch sự!");

        // Câu trả lời chuẩn tôn trọng quyết định cân nhắc (0 xin lỗi, 0 dấu '?') phải PASS
        List<ReplyBubble> warmExit = List.of(
                new ReplyBubble("Dạ vâng ạ, mình cứ thong thả cân nhắc thêm nhé, hôm nào vùng cổ vai gáy cần thả lỏng nhẹ nhàng thì nhắn em sắp xếp khung giờ cho mình nha 🌿", 0)
        );
        ReplyContractValidator.ValidationResult validExit = ReplyContractValidator.validateAndEnforce(
                warmExit,
                state,
                userMsg,
                decision.effectiveResponseAction(),
                false,
                2000
        );
        assertTrue(validExit.errors().isEmpty(), "Câu tôn trọng quyết định cân nhắc ấm áp phải hợp lệ: " + validExit.errors());
        assertFalse(validExit.validatedBubbles().get(0).text().toLowerCase().contains("xin lỗi"));
        assertFalse(validExit.validatedBubbles().get(0).text().toLowerCase().contains("không làm phiền"));
        assertFalse(validExit.validatedBubbles().get(0).text().contains("?"));
    }

    @Test
    @DisplayName("TEST W (v3.4.2 Mục 4) — ANTI-APOLOGY PUSHBACK: 'Hâm à mắc j xin loi' -> Ưu tiên trên R01, EMOTIONAL_WARM_THINK, cấm xin lỗi lần 2 & cấm chuyển nhân viên")
    void testW_AntiApologyPushbackNoRepeatApology() {
        ConversationState.clearSessionCache();
        String catalogTable = """
                | Mã gói | Tên gói | Giá | Tổng thời gian | Bao gồm | Không gian | Điểm nổi bật | Phù hợp |
                | --- | --- | --- | --- | --- | --- | --- | --- |
                | NS01 | Massage cơ bản | 399.000VNĐ | 60 phút | Massage toàn thân, tập trung chăm sóc các vùng cơ thể đang đau mỏi + Buffet thực dưỡng | Phòng chung (có rèm riêng tư) | Trọn gói không nhận Tip, ăn nhẹ sau liệu trình | Khách đau mỏi lưng, cổ vai, chân tay |
                | DS02 | Gói dưỡng sinh cơ bản | 239.000VNĐ | 60 phút | Gội đầu cơ bản + massage cổ vai gáy + ăn nhẹ | Khu dưỡng sinh có rèm che riêng | Thư giãn nhẹ nhàng, trọn gói không nhận Tip | Khách muốn thư giãn đầu, cổ vai |
                """;
        List<ReciprocalRankFusion.FusedChunk> chunks = List.of(new ReciprocalRankFusion.FusedChunk(
                new RetrievedChunk(UUID.randomUUID(), UUID.randomUUID(), List.of("BẢNG GIÁ DỊCH VỤ", "Cơ sở Hà Nội"), List.of(), 1, new double[]{0, 0, 0, 0}, catalogTable, 0.95),
                0.95
        ));
        List<String> keywords = List.of("massage", "cổ", "vai", "gáy", "dưỡng sinh", "399k", "239k");
        List<ConversationTurn> historyTurn7 = List.of(
                new ConversationTurn("user", "Mà vẫn thấy hơi đắt nhỉ"),
                new ConversationTurn("assistant", "Dạ vậy mình tham khảo Gói dưỡng sinh cơ bản 60 phút giá 239k ạ."),
                new ConversationTurn("user", "Ukm, để t cân nhắc thêm"),
                new ConversationTurn("assistant", "Dạ vâng ạ, mình cứ thong thả cân nhắc thêm nhé, hôm nào cần thư giãn cổ vai gáy thì nhắn em nha 🌿")
        );

        String userMsg = "Hâm à mắc j xin loi";
        assertTrue(SentimentIntentLayer.isAntiApologyPushback(userMsg),
                "Phải bắt chính xác phản ứng vặn lại câu xin lỗi ('mắc j xin loi')");

        ConversationState state = ConversationState.hydrateForTurn(
                PAGE_ID, "u-pushback-v342", "c-pushback-v342", false, userMsg, historyTurn7, keywords, chunks
        );
        SentimentIntentLayer.DetectedIntent detectedIntent = SentimentIntentLayer.classify(userMsg, state);
        assertEquals(SentimentIntentLayer.DetectedIntent.ANTI_APOLOGY_PUSHBACK, detectedIntent,
                "ANTI_APOLOGY_PUSHBACK phải được ưu tiên cao hơn FRUSTRATION thông thường");

        PrimaryActionRouter.RoutingDecision decision = PrimaryActionRouter.route(userMsg, state, chunks, keywords);
        assertEquals("ANTI_APOLOGY_PUSHBACK", decision.detectedIntent());
        assertNotEquals(PrimaryActionRouter.PrimaryAction.HARD_HANDOFF_COMPLAINT, decision.effectiveResponseAction(),
                "Tuyệt đối cấm kích hoạt HARD_HANDOFF_COMPLAINT chuyển nhân viên khi khách vặn lại câu xin lỗi!");

        ThinkingModePolicy.ThinkingDecision td = ThinkingModePolicy.decide(userMsg, state, decision, null);
        assertEquals(ThinkingMode.EMOTIONAL_WARM_THINK, td.thinkingMode(),
                "ANTI_APOLOGY_PUSHBACK phải dùng EMOTIONAL_WARM_THINK để hóa giải hóm hỉnh");

        // Validator phải chặn đứng nếu LLM lại lặp từ 'xin lỗi' hoặc 'chuyển sang nhân viên'
        ReplyContractValidator.ValidationResult badPushbackReply = ReplyContractValidator.validateAndEnforce(
                List.of(new ReplyBubble("Dạ em xin lỗi vì cách diễn đạt vừa rồi chưa rõ ràng, em xin phép chuyển sang nhân viên hỗ trợ trực tiếp ạ.", 0)),
                state,
                userMsg,
                decision.effectiveResponseAction(),
                false,
                2000
        );
        assertTrue(badPushbackReply.errors().contains("FORBIDDEN_APOLOGY_ON_ANTI_APOLOGY_PUSHBACK"));

        // Câu hóa giải hóm hỉnh, ấm áp (0 xin lỗi, 0 chuyển nhân viên) phải PASS
        List<ReplyBubble> warmLightheartedReply = List.of(
                new ReplyBubble("Dạ tại em sợ mình thấy phiền nên nói hơi khách sáo xíu thôi ạ 😊 Mình cứ thoải mái xem thêm nhé, lúc nào muốn ghé thư giãn cổ vai gáy thì cứ ới em nha 🌿", 0)
        );
        ReplyContractValidator.ValidationResult goodPushbackReply = ReplyContractValidator.validateAndEnforce(
                warmLightheartedReply,
                state,
                userMsg,
                decision.effectiveResponseAction(),
                false,
                2000
        );
        assertTrue(goodPushbackReply.errors().isEmpty(), "Câu hóa giải tự nhiên phải hợp lệ: " + goodPushbackReply.errors());
        assertFalse(goodPushbackReply.validatedBubbles().get(0).text().toLowerCase().contains("xin lỗi"));
        assertFalse(goodPushbackReply.validatedBubbles().get(0).text().toLowerCase().contains("chuyển sang nhân viên"));
    }

    @Test
    @DisplayName("TEST X (v3.5.0-ULTIMATE) — BODY MISMATCH GUARD, MOOD DROP RECOVERY, POLITE PRICE REMINDER, PROMO INQUIRY & 10S POST-PRICE DELAY")
    void testX_V350BodyMismatchMoodDropPolitePriceReminderPromoInquiryAndPostPriceDelay() {
        ConversationState.clearSessionCache();
        String catalogTable = """
                | Mã gói | Tên gói | Giá | Tổng thời gian | Bao gồm | Không gian | Điểm nổi bật | Phù hợp |
                | --- | --- | --- | --- | --- | --- | --- | --- |
                | NS01 | Massage cơ bản | 399.000VNĐ | 60 phút | Massage toàn thân, tập trung chăm sóc các vùng cơ thể đang đau mỏi + Buffet thực dưỡng | Phòng chung (có rèm riêng tư) | Trọn gói không nhận Tip, ăn nhẹ sau liệu trình | Khách đau mỏi lưng, cổ vai, chân tay |
                | DS02 | Gói dưỡng sinh cơ bản | 239.000VNĐ | 60 phút | Gội đầu cơ bản + massage cổ vai gáy + ăn nhẹ | Khu dưỡng sinh có rèm che riêng | Thư giãn nhẹ nhàng, trọn gói không nhận Tip | Khách muốn thư giãn đầu, cổ vai |
                """;
        List<ReciprocalRankFusion.FusedChunk> chunks = List.of(new ReciprocalRankFusion.FusedChunk(
                new RetrievedChunk(UUID.randomUUID(), UUID.randomUUID(), List.of("BẢNG GIÁ DỊCH VỤ", "Cơ sở Hà Nội"), List.of(), 1, new double[]{0, 0, 0, 0}, catalogTable, 0.95),
                0.95
        ));
        List<String> keywords = List.of("massage", "chân", "cổ", "vai", "gáy", "mất ngủ", "399k", "239k");

        // 1. Khách đau nhức chân và mất ngủ -> Tư vấn 399k -> Kiểm tra khóa post-price delay = 10000ms (không trừ thời gian LLM chạy) & cấm chữ 'vai/gáy'
        List<ConversationTurn> historyTurn3 = List.of(
                new ConversationTurn("user", "Giá sao"),
                new ConversationTurn("assistant", "Dạ chào mình, hiện mình đang muốn tìm hiểu massage trị liệu giảm đau mỏi hay gội đầu dưỡng sinh thư giãn ạ?"),
                new ConversationTurn("user", "Tôi đau nhức chân và mất ngủ"),
                new ConversationTurn("assistant", "Dạ vừa đau nhức chân lại thêm mất ngủ chắc hẳn làm mình mệt mỏi lắm ạ. Tình trạng này mình mới bị gần đây hay đã kéo dài lâu rồi ạ?")
        );
        ConversationState stateT3 = ConversationState.hydrateForTurn(
                PAGE_ID, "u-v350", "c-v350", false, "Lâu rồi, giờ bị lại", historyTurn3, keywords, chunks
        );
        assertEquals(List.of("chân"), stateT3.knownBodyAreas(), "knownBodyAreas phải chỉ chứa 'chân'");

        // Nếu LLM ảo giác vùng 'vai/lưng' khi khách chỉ đau chân -> BODY_PART_MISMATCH_HALLUCINATION
        ReplyContractValidator.ValidationResult halluBodyRes = ReplyContractValidator.validateAndEnforce(
                List.of(
                        new ReplyBubble("Dạ mình tham khảo gói Massage cơ bản 60 phút giá 399k giúp thư giãn vùng vai và lưng đang căng mỏi ạ.", 0),
                        new ReplyBubble("Liệu trình kèm bữa ăn thực dưỡng ấm bụng giúp mình dễ ngủ hơn ạ. Mình thấy gói 60 phút này hợp ý không ạ?", 10000)
                ),
                stateT3,
                "Lâu rồi, giờ bị lại",
                PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE,
                false,
                10000,
                ThinkingMode.PRICE_ANCHOR_THINK,
                14500L // Dù LLM chạy mất 14.5s, post-price bubble ở lượt 2 bong bóng vẫn phải giữ nguyên 10000ms!
        );
        assertTrue(halluBodyRes.errors().contains("BODY_PART_MISMATCH_HALLUCINATION"),
                "Phải bắt lỗi BODY_PART_MISMATCH_HALLUCINATION khi khách đau chân nhưng bot nói vùng vai/lưng");

        // Câu đúng vùng chân + kiểm tra giữ nguyên delay 10000ms và không tách emoji mồ côi
        ReplyContractValidator.ValidationResult goodLegQuote = ReplyContractValidator.validateAndEnforce(
                List.of(
                        new ReplyBubble("Dạ tình trạng cũ tái phát lại chắc làm đôi chân mình mỏi nhức và trằn trọc lắm ạ. Mình tham khảo gói Massage cơ bản 60 phút giá 399k trọn gói không nhận Tip, kỹ thuật viên sẽ miết ấm dọc bắp chân và lòng bàn chân giúp mình nhé ạ.", 0),
                        new ReplyBubble("Sau 60 phút thư giãn vùng chân, mình dùng thêm bữa ăn thực dưỡng ấm bụng sẽ dễ đi vào giấc ngủ sâu hơn hẳn ạ. Mình thấy liệu trình 60 phút này có vừa vặn với mình không ạ? 🥰", 10000)
                ),
                stateT3,
                "Lâu rồi, giờ bị lại",
                PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE,
                false,
                10000,
                ThinkingMode.PRICE_ANCHOR_THINK,
                14500L
        );
        assertTrue(goodLegQuote.errors().isEmpty(), "Câu báo giá đúng vùng chân phải PASS: " + goodLegQuote.errors());
        assertEquals(2, goodLegQuote.validatedBubbles().size(), "Không được tách emoji cuối câu thành bong bóng thứ 3 mồ côi");
        assertEquals(10000, goodLegQuote.validatedBubbles().get(1).typingDelayMs(), "Bong bóng 2 sau giá bắt buộc giữ nguyên 10000ms!");

        // 2. Turn 4: "Dở à, đau chân mất ngủ mà đi massage vai làm j" -> ENTITY_MISMATCH_RECONSULT (ưu tiên trên R01), giữ knownBodyAreas=['chân']
        List<ConversationTurn> historyTurn4 = List.of(
                new ConversationTurn("user", "Giá sao"),
                new ConversationTurn("assistant", "Dạ chào mình ạ."),
                new ConversationTurn("user", "Tôi đau nhức chân và mất ngủ"),
                new ConversationTurn("assistant", "Dạ mình bị lâu chưa ạ?"),
                new ConversationTurn("user", "Lâu rồi, giờ bị lại"),
                new ConversationTurn("assistant", "Dạ mình tham khảo gói Massage cơ bản 60 phút giá 399k ạ.")
        );
        String msgT4 = "Dở à, đau chân mất ngủ mà đi massage vai làm j";
        ConversationState stateT4 = ConversationState.hydrateForTurn(
                PAGE_ID, "u-v350", "c-v350", false, msgT4, historyTurn4, keywords, chunks
        );
        assertEquals(List.of("chân"), stateT4.knownBodyAreas(), "Cấm trích xuất chữ 'vai' trong câu mắng 'massage vai làm j' vào knownBodyAreas!");
        PrimaryActionRouter.RoutingDecision decT4 = PrimaryActionRouter.route(msgT4, stateT4, chunks, keywords);
        assertEquals("ENTITY_MISMATCH_RECONSULT", decT4.detectedIntent());
        assertEquals(PrimaryActionRouter.PrimaryAction.RECOMMEND_ONE_PACKAGE, decT4.effectiveResponseAction());

        // 3. Turn 5: "Tôi thấy tụt cảm xúc quá chán chả buồn nói" -> MOOD_DROP_RECOVERY + EMOTIONAL_WARM_THINK
        String msgT5 = "Tôi thấy tụt cảm xúc quá chán chả buồn nói";
        assertTrue(SentimentIntentLayer.isMoodDropRecoveryMessage(msgT5));
        PrimaryActionRouter.RoutingDecision decT5 = PrimaryActionRouter.route(msgT5, stateT4, chunks, keywords);
        assertEquals("MOOD_DROP_RECOVERY", decT5.detectedIntent());
        assertEquals(ThinkingMode.EMOTIONAL_WARM_THINK, ThinkingModePolicy.decide(msgT5, stateT4, decT5, null).thinkingMode());

        // 4. Turn 6: "Nghe ổn hơn đó, giá sao" -> POLITE_PRICE_REMINDER (ANSWER_PRICE_ONCE, nhắc lại 399k, cấm đẩy sang câu 'em đã gửi ở tin nhắn trên')
        String msgT6 = "Nghe ổn hơn đó, giá sao";
        PrimaryActionRouter.RoutingDecision decT6 = PrimaryActionRouter.route(msgT6, stateT4, chunks, keywords);
        assertEquals("POLITE_PRICE_REMINDER", decT6.detectedIntent());
        assertEquals(PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE, decT6.effectiveResponseAction());

        // 5. Turn 7: "Đắt có giảm giá hya Km k" -> PRICE_OBJECTION_WITH_PROMO_INQUIRY (HANDLE_PRICE_OBJECTION_FIRST_TIME, cho phép nêu giảm 10% thẻ 5 buổi)
        String msgT7 = "Đắt có giảm giá hya Km k";
        PrimaryActionRouter.RoutingDecision decT7 = PrimaryActionRouter.route(msgT7, stateT4, chunks, keywords);
        assertEquals("PRICE_OBJECTION_WITH_PROMO_INQUIRY", decT7.detectedIntent());
        assertEquals(PrimaryActionRouter.PrimaryAction.HANDLE_PRICE_OBJECTION_FIRST_TIME, decT7.effectiveResponseAction());
    }
}
