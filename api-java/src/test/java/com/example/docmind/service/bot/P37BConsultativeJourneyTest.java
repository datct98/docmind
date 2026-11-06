package com.example.docmind.service.bot;

import com.example.docmind.dto.bot.FanpageBotResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3.7-B: Consultative Journey — tách bạch DISCOVERY_QUESTION / SALES_CTA, bất biến 1 câu hỏi ở cuối bubble cuối,
 * sửa nhãn phạm vi "toàn thân" & cắt chữ catalog, giữ 2 nhịp mở đầu, rào chắn lâm sàng và ngân sách prompt.
 * Chỉ dùng câu thử nghiệm tổng quát (không dùng bộ Unseen giữ kín).
 */
class P37BConsultativeJourneyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static List<FanpageBotResponse.ReplyBubble> bubbles(String... texts) {
        return java.util.Arrays.stream(texts).map(t -> new FanpageBotResponse.ReplyBubble(t, 0)).toList();
    }

    private static int countQ(String s) {
        return (int) s.chars().filter(c -> c == '?' || c == '？').count();
    }

    private static void assertGlobalQuestionInvariant(List<FanpageBotResponse.ReplyBubble> out) {
        int total = out.stream().mapToInt(b -> countQ(b.text())).sum();
        assertThat(total).isLessThanOrEqualTo(1);
        for (int i = 0; i < out.size() - 1; i++) {
            assertThat(countQ(out.get(i).text())).as("bubble %d must have 0 '?'", i).isZero();
        }
        if (total == 1) {
            assertThat(out.get(out.size() - 1).text().strip()).endsWith("?");
        }
    }

    @Test
    @DisplayName("Bất biến toàn cục: câu hỏi ở Bubble 1 + thông tin ở Bubble 2 -> chỉ còn 1 câu hỏi ở cuối bubble cuối")
    void globalInvariantMovesPrematureBubbleOneQuestionToEnd() {
        List<FanpageBotResponse.ReplyBubble> out = CleanAgentOutputRefereeAndSanitizer.enforceGlobalSingleTrailingQuestionAcrossBubbles(
                bubbles("Dạ không gian yên tĩnh và có vách ngăn riêng ạ. Mình thấy như vậy đã yên tâm chưa ạ?",
                        "Bên em cũng có lựa chọn phòng riêng biệt trong cùng nhóm dịch vụ ạ."));
        assertGlobalQuestionInvariant(out);
        assertThat(out).hasSize(2);
        assertThat(out.get(0).text()).doesNotContain("?");
        assertThat(out.get(1).text()).endsWith("yên tâm chưa ạ?");
    }

    @Test
    @DisplayName("Bất biến toàn cục: 2 câu hỏi liền kề ở cuối được gộp thành 1 câu hỏi duy nhất; câu hỏi rời ở Bubble 1 bị lược")
    void globalInvariantMergesAdjacentTrailingQuestionsAndDropsDetachedOnes() {
        List<FanpageBotResponse.ReplyBubble> out = CleanAgentOutputRefereeAndSanitizer.enforceGlobalSingleTrailingQuestionAcrossBubbles(
                bubbles("Dạ em ghi nhận rồi ạ. Mình cần gì thêm không ạ?",
                        "Em kiểm tra lịch trống ngay ạ. Mình muốn ghé cơ sở nào? Khung giờ nào tiện cho mình ạ?"));
        assertGlobalQuestionInvariant(out);
        assertThat(out.get(out.size() - 1).text()).contains("cơ sở nào, khung giờ nào");
        assertThat(out.get(0).text()).doesNotContain("cần gì thêm");
    }

    @Test
    @DisplayName("Bất biến toàn cục: phản hồi đã hợp lệ được giữ nguyên; bubble rỗng sau khi lược câu hỏi bị loại bỏ")
    void globalInvariantKeepsCompliantAndDropsEmptyBubbles() {
        List<FanpageBotResponse.ReplyBubble> ok = bubbles("Dạ chào mình ạ.", "Bên em có hai dòng chính. Mình quan tâm dòng nào ạ?");
        assertThat(CleanAgentOutputRefereeAndSanitizer.enforceGlobalSingleTrailingQuestionAcrossBubbles(ok)).isEqualTo(ok);

        List<FanpageBotResponse.ReplyBubble> out = CleanAgentOutputRefereeAndSanitizer.enforceGlobalSingleTrailingQuestionAcrossBubbles(
                bubbles("Mình đang quan tâm điều gì ạ?", "Dạ bên em hỗ trợ ngay ạ.", "Mình tiện ghé hôm nào ạ?"));
        assertGlobalQuestionInvariant(out);
        assertThat(out).hasSize(2);
    }

    @Test
    @DisplayName("Lượt 1 hỏi giá: lời chào hoàn chỉnh được giữ riêng Bubble 1 (không gộp lặp 'Dạ chào... Dạ bên em...')")
    void openingTurnKeepsCompleteGreetingAsSeparateBubble() {
        List<FanpageBotResponse.ReplyBubble> out = CleanAgentOutputRefereeAndSanitizer.mergeTrailingColonAndTurn1PriceIntroBubbles(
                bubbles("Dạ chào mình, em là trợ lý tư vấn của cơ sở mình ạ.",
                        "Dạ bên em có dòng A từ 200k và dòng B từ 400k. Mình đang muốn chăm sóc điều gì ạ?"),
                "giá", true);
        assertThat(out).hasSize(2);
        assertThat(out.get(0).text()).doesNotContain("200k");
        assertGlobalQuestionInvariant(out);

        List<FanpageBotResponse.ReplyBubble> shortLead = CleanAgentOutputRefereeAndSanitizer.mergeTrailingColonAndTurn1PriceIntroBubbles(
                bubbles("Dạ chào mình ạ.", "Dạ bên em có dòng A từ 200k. Mình cần gì ạ?"),
                "giá", true);
        assertThat(shortLead).hasSize(1);
    }

    @Test
    @DisplayName("PROCESS_FEEDBACK nhánh MISSED_DISCOVERY: giữ 1 DISCOVERY_QUESTION thuần, lược SALES_CTA & báo giá, 0 tự khai AI")
    void missedDiscoveryKeepsSinglePureDiscoveryQuestionAndStripsSalesCta() {
        String complaint = "chưa tìm hiểu j về KH tự nhiên xả cả đống thông tin";
        assertThat(CleanAgentOutputRefereeAndSanitizer.isProcessOrConsultationFeedbackComplaint(complaint)).isTrue();
        assertThat(CleanAgentOutputRefereeAndSanitizer.isDiscoveryQuestionAllowedOnProcessFeedback(complaint)).isTrue();

        List<FanpageBotResponse.ReplyBubble> out = CleanAgentOutputRefereeAndSanitizer.enforceAciV2StyleComplaintNoCtaAndAntiClicheInPlace(
                bubbles("Dạ em là trợ lý AI, em xin lỗi vì đã vội đưa thông tin khi chưa hỏi kỹ ạ. Gói cơ bản chỉ 200k thôi ạ.",
                        "Mình muốn đặt lịch khung giờ nào ạ? Hiện mình đang cần được chăm sóc điều gì nhất ạ?"),
                complaint, "Dạ bên em có dòng A từ 200k ạ.", "em", "mình");
        assertThat(out).hasSize(1);
        String txt = out.get(0).text();
        assertThat(txt).doesNotContainIgnoringCase("trợ lý AI").doesNotContain("200k").doesNotContain("đặt lịch");
        assertThat(countQ(txt)).isEqualTo(1);
        assertThat(txt).endsWith("cần được chăm sóc điều gì nhất ạ?");

        assertThat(CleanAgentOutputRefereeAndSanitizer.hasTrailingSalesOrBookingCtaOnStyleComplaint(
                "Dạ em xin lỗi ạ. Hiện mình đang cần được chăm sóc điều gì nhất ạ?", complaint)).isFalse();
        assertThat(CleanAgentOutputRefereeAndSanitizer.hasTrailingSalesOrBookingCtaOnStyleComplaint(
                "Dạ em xin lỗi ạ. Mình muốn thử gói nào ạ?", complaint)).isTrue();
    }

    @Test
    @DisplayName("PROCESS_FEEDBACK nhánh OVER_INTRUSION (phê phán tự khai AI / hỏi nhiều): 0 câu hỏi")
    void overIntrusionRemovesAllQuestions() {
        String aiCriticism = "sao tự khai mình là AI, nói chuyện chẳng có chút cảm xúc nào";
        String overAsking = "hỏi nhiều quá, nói ngắn thôi";
        assertThat(CleanAgentOutputRefereeAndSanitizer.isOverIntrusionFeedbackCue(aiCriticism)).isTrue();
        assertThat(CleanAgentOutputRefereeAndSanitizer.isOverIntrusionFeedbackCue(overAsking)).isTrue();
        assertThat(CleanAgentOutputRefereeAndSanitizer.isOverIntrusionFeedbackCue("chưa tìm hiểu j về KH đã tư vấn")).isFalse();

        List<FanpageBotResponse.ReplyBubble> out = CleanAgentOutputRefereeAndSanitizer.enforceAciV2StyleComplaintNoCtaAndAntiClicheInPlace(
                bubbles("Dạ em tiếp thu góp ý của mình ạ. Mình đang cần chăm sóc điều gì ạ?"),
                aiCriticism, null, "em", "mình");
        assertThat(out).hasSize(1);
        assertThat(countQ(out.get(0).text())).isZero();
    }

    @Test
    @DisplayName("Reconcile: MISSED_DISCOVERY giữ ASK_ONE_DISCOVERY_QUESTION + NO_CTA; OVER_INTRUSION phủ quyết về NO_ADDITIONAL_ACTION")
    void reconcileResolvesFrictionRecoveryModes() throws Exception {
        String reflectionTpl = """
                {
                  "customer_understanding": {"turn_intent": "PROCESS_FEEDBACK", "secondary_intents": [], "emotion": "FRUSTRATED",
                    "interaction_permission": "OPEN", "buying_signal": "NONE"},
                  "priority": "SERVICE_RECOVERY",
                  "response_plan": {"strategy": "Active Listening", "tone": "LEVEL_1_NATURAL", "depth": "CONCISE",
                    "cta_level": "SOFT_CTA", "next_action": "ASK_ONE_DISCOVERY_QUESTION", "adaptation": "PIVOT"},
                  "activated_skill_codes": []
                }
                """;
        JsonNode raw = objectMapper.readTree(reflectionTpl);

        var missed = AdaptiveConversationIntelligence.parseAndReconcileAdaptiveCognitiveReflection(
                raw, "chưa tìm hiểu j về KH tự nhiên xả cả đống thông tin");
        assertThat(missed.responsePlan().nextAction()).isEqualTo(AdaptiveConversationIntelligence.DISCOVERY_QUESTION_NEXT_ACTION);
        assertThat(missed.responsePlan().ctaLevel()).isEqualTo(AdaptiveConversationIntelligence.CtaLevel.NO_CTA);
        assertThat(missed.withForcedNoCta().responsePlan().nextAction())
                .isEqualTo(AdaptiveConversationIntelligence.DISCOVERY_QUESTION_NEXT_ACTION);

        var over = AdaptiveConversationIntelligence.parseAndReconcileAdaptiveCognitiveReflection(
                raw, "Dell hiểu sao tự khia mình là AI, KH chạy mất dép");
        assertThat(over.responsePlan().nextAction()).isEqualTo("NO_ADDITIONAL_ACTION");
        assertThat(over.responsePlan().ctaLevel()).isEqualTo(AdaptiveConversationIntelligence.CtaLevel.NO_CTA);
    }

    @Test
    @DisplayName("Catalog: chỉ ghi 'Vùng: toàn thân' khi nguồn thực sự có 'toàn thân'; không còn size() >= 8")
    void scopeSummaryNoLongerMislabelsWholeBody() {
        String nonWholeBody = CleanAgentCatalogAndPromptBuilder.summarizeVerifiedScopeTags(
                List.of("gội đầu", "dưỡng sinh", "massage", "cổ", "vai", "gáy", "đầu", "thư giãn", "ăn nhẹ"));
        assertThat(nonWholeBody).doesNotContain("toàn thân").contains("cổ").contains("vai");

        String wholeBody = CleanAgentCatalogAndPromptBuilder.summarizeVerifiedScopeTags(List.of("toàn thân", "body", "cổ"));
        assertThat(wholeBody).isEqualTo("toàn thân");

        String dental = CleanAgentCatalogAndPromptBuilder.summarizeVerifiedScopeTags(List.of("răng", "nướu"));
        assertThat(dental).isEqualTo("răng, nướu");
    }

    @Test
    @DisplayName("Catalog: mô tả không gian giữ trọn mệnh đề (không cắt giữa từ kiểu 'đều có r…')")
    void roomDescriptionKeepsCompleteClause() {
        String room = "Không gian tĩnh lặng, mỗi giường đều có rèm che riêng";
        assertThat(CleanAgentCatalogAndPromptBuilder.compactCompleteClause(room, 72)).isEqualTo(room);
    }

    @Test
    @DisplayName("Rào chắn lâm sàng: chặn cam kết 'không đau'/'1 lần là hết', cho phép câu trung thực phủ định cam kết")
    void absoluteClinicalGuaranteeDetection() {
        assertThat(CleanAgentOutputRefereeAndSanitizer.detectAbsoluteClinicalOutcomeGuarantee(
                "Dạ bên em cam kết không đau ạ.")).isTrue();
        assertThat(CleanAgentOutputRefereeAndSanitizer.detectAbsoluteClinicalOutcomeGuarantee(
                "Làm hoàn toàn không đau chút nào đâu ạ.")).isTrue();
        assertThat(CleanAgentOutputRefereeAndSanitizer.detectAbsoluteClinicalOutcomeGuarantee(
                "Chỉ 1 lần hẹn là xử lý gọn ạ.")).isTrue();
        assertThat(CleanAgentOutputRefereeAndSanitizer.detectAbsoluteClinicalOutcomeGuarantee(
                "Dạ bên em không dám cam kết không đau, nhưng sẽ hỗ trợ giảm khó chịu tối đa ạ.")).isFalse();
        assertThat(CleanAgentOutputRefereeAndSanitizer.detectAbsoluteClinicalOutcomeGuarantee(
                "Bác sĩ sẽ kiểm tra trực tiếp rồi mới tư vấn phương án phù hợp ạ.")).isFalse();
    }

    @Test
    @DisplayName("Prompt: đồng bộ file/in-code, chỉ dẫn hệ thống <= 9.800 ký tự, 0 giá/tên riêng tenant, có bất biến câu hỏi & DISCOVERY_QUESTION")
    void promptBudgetZeroHardcodeAndInvariants() throws Exception {
        Path md = Path.of("../config/aci_v2_system_prompt.md");
        if (!Files.isRegularFile(md)) {
            md = Path.of("config/aci_v2_system_prompt.md");
        }
        String raw = Files.readString(md);
        String body = CleanAgentCatalogAndPromptBuilder.stripPromptVersionComment(raw).strip();
        assertThat(body).isEqualTo(CleanAgentCatalogAndPromptBuilder.getInCodeAciPromptTemplate().strip());
        int instructionsLen = body.indexOf("V. ĐẦU RA BẮT BUỘC");
        assertThat(instructionsLen).isPositive().isLessThanOrEqualTo(9_800);
        assertThat(body).contains("ASK_ONE_DISCOVERY_QUESTION").contains("TỐI ĐA 1 dấu `?`");
        assertThat(Pattern.compile("\\b\\d{2,4}k\\b").matcher(body).find()).isFalse();
        assertThat(body.toLowerCase()).doesNotContain("nàng sen").doesNotContain("massage").doesNotContain("rèm")
                .doesNotContain("composite").doesNotContain("ráy tai");
    }

    @Test
    @DisplayName("P3.7-B.1: Contextual Symptom Understanding (Cấp 2 đời thường không bị chặn là triệu chứng y khoa độc lập; Cấp 3/4 phân tách rõ)")
    void p37b1ContextualSymptomLevelsAndRuleGates() {
        String level2Work = "Dạo này chị làm việc căng thẳng, mất ngủ, đau mỏi cổ vai gáy và đứng dậy hay hoa mắt chóng mặt, bên em có gói nào phù hợp không?";
        String level2Postural = "Nhưng chị hay bị đau đầu với thi thoảng hoa mắt chóng mặt vì ngồi máy tính lâu thì làm gói này có hợp không em?";
        assertThat(CleanAgentOutputRefereeAndSanitizer.isUnconfirmedHealthSymptomWithoutServiceDemand(level2Work, List.of())).isFalse();
        assertThat(CleanAgentOutputRefereeAndSanitizer.isUnconfirmedHealthSymptomWithoutServiceDemand(level2Postural, List.of())).isFalse();
        assertThat(CleanAgentCatalogAndPromptBuilder.hasHighRiskOrContraindicationCue(level2Work.toLowerCase())).isFalse();
        assertThat(CleanAgentCatalogAndPromptBuilder.hasHighRiskOrContraindicationCue(level2Postural.toLowerCase())).isFalse();
        assertThat(FanpageBotService.isUniversalAcuteMedicalEmergency(level2Work)).isFalse();

        String level3PostSurgery = "Anh mới phẫu thuật khớp vai được 2 tuần, vết mổ vẫn hơi sưng nhưng mỏi lưng quá muốn qua làm nhẹ có được không?";
        assertThat(CleanAgentCatalogAndPromptBuilder.hasHighRiskOrContraindicationCue(level3PostSurgery.toLowerCase())).isTrue();
        assertThat(FanpageBotService.isUniversalAcuteMedicalEmergency(level3PostSurgery)).isFalse();

        String level4Acute = "Tự nhiên anh thấy tức ngực khó thở, tê nửa người bên trái và chóng mặt dữ dội";
        assertThat(FanpageBotService.isUniversalAcuteMedicalEmergency(level4Acute)).isTrue();
    }

    @Test
    @DisplayName("P3.7-B.1: Live UI 0A — Lượt 1 'Tư vấn' không xả giá; Lượt 2 'Chưa j đã xả hết thông tin rồi, chưa cả tìm hiểu j cả' bắt buộc có 1 câu hỏi mở và 0 câu tự khóa")
    void p37b1Ui0ATurn1ConsultAndTurn2MissedDiscoveryRecovery() throws Exception {
        assertThat(CleanAgentCatalogAndPromptBuilder.isBareTurnOneGreetingOrConsultationRequest("Tư vấn")).isTrue();
        assertThat(CleanAgentCatalogAndPromptBuilder.isBareTurnOneGreetingOrConsultationRequest("Giá")).isFalse();

        List<FanpageBotResponse.ReplyBubble> t1Out = CleanAgentOutputRefereeAndSanitizer.mergeTrailingColonAndTurn1PriceIntroBubbles(
                bubbles("Dạ chào mình ạ. Bên em có gói cơ bản 239k và gói chuyên sâu 399k ạ.",
                        "Mình đang muốn chăm sóc vùng nào để em hỗ trợ ạ?"),
                "Tư vấn", true);
        assertGlobalQuestionInvariant(t1Out);
        String t1Combined = String.join(" ", t1Out.stream().map(FanpageBotResponse.ReplyBubble::text).toList());
        assertThat(t1Combined).doesNotContain("239k").doesNotContain("399k").endsWith("?");

        String ui0aT2 = "Chưa j đã xả hết thông tin rồi, chưa cả tìm hiểu j cả";
        assertThat(CleanAgentOutputRefereeAndSanitizer.isMissedDiscoveryFeedbackCue(ui0aT2)).isTrue();
        assertThat(AdaptiveConversationIntelligence.resolveFrictionRecoveryMode(ui0aT2, "ASK_ONE_DISCOVERY_QUESTION"))
                .isEqualTo(AdaptiveConversationIntelligence.FrictionRecoveryMode.MISSED_DISCOVERY);

        List<FanpageBotResponse.ReplyBubble> t2Out = CleanAgentOutputRefereeAndSanitizer.enforceAciV2StyleComplaintNoCtaAndAntiClicheInPlace(
                bubbles("Mình nói đúng ạ, em xin lỗi vì đã đưa nhiều thông tin quá sớm khi chưa hỏi kỹ nhu cầu của mình. Em sẽ dừng lại và chỉ tư vấn đúng điều mình cần nhé. Hiện mình đang muốn tìm hiểu điều gì trước để em hỗ trợ đúng ý mình ạ?"),
                ui0aT2, null, "em", "mình");
        assertThat(t2Out).hasSize(1);
        String t2Text = CleanAgentOutputRefereeAndSanitizer.repairWrongPronounOpenerInPlace(t2Out.get(0).text(), "em", "mình");
        assertThat(t2Text)
                .doesNotStartWith("Mình nói đúng")
                .doesNotContain("chỉ tư vấn đúng điều mình cần nhé")
                .endsWith("?");
        assertThat(countQ(t2Text)).isEqualTo(1);
    }

    @Test
    @DisplayName("P3.7-B.1: Ngữ nghĩa giá đời thường ('hơn trăm', 'trăm rưỡi', '100-150k'), độ chênh toán học (239k - 90k = 149k) và đóng phiên lịch sự 'ok cảm ơn e nhé'")
    void p37b1ColloquialPriceProvenanceExactDeltaAndPoliteClosingAck() {
        var prov = CleanAgentOutputRefereeAndSanitizer.extractCustomerSessionPriceProvenance(
                "Tầm hơn trăm hoặc trăm rưỡi có gói nào không shop?", List.of());
        assertThat(prov.userAskedPrices()).contains(100_000L, 150_000L, 200_000L);

        var deltaProv = CleanAgentOutputRefereeAndSanitizer.extractCustomerSessionPriceProvenance(
                "Chỗ khác gội có 90k thôi", List.of());
        String deltaBubble = "Dạ mức 239k bên em chênh khoảng 149k so với tiệm 90k bên ngoài vì bao gồm 60 phút chăm sóc chuyên sâu ạ.";
        int idx149 = deltaBubble.indexOf("149k");
        var role149 = CleanAgentOutputRefereeAndSanitizer.classifyPriceMentionSemanticRole(
                deltaBubble, idx149, idx149 + 4, 149_000L, java.util.Set.of(239_000L, 399_000L), java.util.Set.of(), deltaProv);
        assertThat(role149).isEqualTo(CleanAgentOutputRefereeAndSanitizer.PriceMentionSemanticRole.USER_PROVIDED_COMPARISON_PRICE);

        String closingMsg = "ok cảm ơn e nhé";
        assertThat(CleanAgentOrchestratorService.isPoliteThankYouClosingAckMessage(closingMsg)).isTrue();
        List<FanpageBotResponse.ReplyBubble> closingOut = CleanAgentOutputRefereeAndSanitizer.enforcePoliteClosingPauseSingleBubbleNoQuestionInPlace(
                bubbles("Dạ em cảm ơn chị nhiều ạ. Khi nào chị sắp xếp được thời gian ghé cơ sở thì cứ nhắn em nhé ạ.",
                        "Vậy chị muốn dời sang mấy giờ ngày mai để em giữ lịch luôn ạ?"),
                "em", "chị");
        assertThat(closingOut).hasSize(1);
        assertThat(countQ(closingOut.get(0).text())).isZero();
    }

    @Test
    @DisplayName("ACI V3 — Guardrail 1: Sanitizer bảo toàn câu trả lời tự nhiên của LLM ở Pause/Deferral/Level 3 hợp lệ và không ép nối bubble mẫu ở Respectful Deescalation")
    void aciV3Guardrail1SanitizerPreservesNaturalProseAndDoesNotInjectTemplates() {
        // 1. CUSTOMER_PAUSE_OR_ACKNOWLEDGEMENT ("Ok"): giữ nguyên câu tự nhiên của LLM (chỉ bỏ câu hỏi ở bubble phụ nếu có)
        String naturalAckBubble = "Dạ vâng ạ, chị cứ thong thả xem qua các liệu trình bên em nhé, khi nào cần tư vấn thêm em luôn sẵn sàng hỗ trợ chị ạ.";
        List<FanpageBotResponse.ReplyBubble> pauseOut = CleanAgentOutputRefereeAndSanitizer.enforceP37B2TurnActionAndBubbleIdeaDisciplineInPlace(
                bubbles(naturalAckBubble, "Chị có muốn em giữ lịch trước không ạ?"),
                "Ok",
                List.of(),
                null,
                null,
                "em",
                "chị",
                "chuyên viên"
        );
        assertThat(pauseOut).hasSize(1);
        assertThat(pauseOut.get(0).text()).isEqualTo(naturalAckBubble);
        assertThat(countQ(pauseOut.get(0).text())).isZero();

        // 2. CUSTOMER_DEFERRAL ("Để chị xem thêm nhé"): giữ nguyên câu tự nhiên của LLM, không ghi đè văn mẫu
        String naturalDeferralBubble = "Dạ chị cứ thoải mái cân nhắc thêm nhé ạ, lúc nào chị thu xếp được thời gian ghé thư giãn thì nhắn em hỗ trợ chị nha.";
        List<FanpageBotResponse.ReplyBubble> deferralOut = CleanAgentOutputRefereeAndSanitizer.enforceP37B2TurnActionAndBubbleIdeaDisciplineInPlace(
                bubbles(naturalDeferralBubble),
                "Để chị xem thêm nhé",
                List.of(),
                null,
                null,
                "em",
                "chị",
                "chuyên viên"
        );
        assertThat(deferralOut).hasSize(1);
        assertThat(deferralOut.get(0).text()).isEqualTo(naturalDeferralBubble);

        // 3. Level 3 Post-Surgery hợp lệ (đã có kết nối chuyên viên/quản lý và không hứa thao tác bừa): giữ nguyên văn phong tự nhiên của LLM
        String naturalLevel3Bubble = "Dạ vì anh mới phẫu thuật khớp vai được 2 tuần và vết mổ còn hơi sưng, để an toàn nhất thì em xin phép kết nối chuyên viên phụ trách kiểm tra trực tiếp tình trạng của anh trước khi tư vấn thao tác phù hợp nhé ạ.";
        List<FanpageBotResponse.ReplyBubble> level3Out = CleanAgentOutputRefereeAndSanitizer.enforceP37B2TurnActionAndBubbleIdeaDisciplineInPlace(
                bubbles(naturalLevel3Bubble),
                "Anh mới phẫu thuật khớp vai được 2 tuần, vết mổ vẫn hơi sưng nhưng mỏi lưng quá muốn qua làm nhẹ có được không?",
                List.of(),
                null,
                null,
                "em",
                "anh",
                "chuyên viên"
        );
        assertThat(level3Out).hasSize(1);
        assertThat(level3Out.get(0).text()).isEqualTo(naturalLevel3Bubble);

        // 4. RESPECTFUL_DEESCALATION (phản đối giá liên tiếp lần 2): không tự động nhét thêm Bubble 2 mẫu ("Dạ mình cứ thong thả cân nhắc thêm theo ngân sách...")
        List<com.example.docmind.dto.bot.FanpageBotRequest.ConversationTurn> histWithPriorObjection = List.of(
                new com.example.docmind.dto.bot.FanpageBotRequest.ConversationTurn("user", "Giá 399k đắt thế shop"),
                new com.example.docmind.dto.bot.FanpageBotRequest.ConversationTurn("assistant", "Dạ bên em có gói cơ bản 239k nhẹ nhàng hơn ạ.")
        );
        String naturalDeescalation = "Dạ em rất hiểu cảm nhận của chị về mức chi phí ạ, bên em luôn tôn trọng ngân sách của chị để mình thoải mái cân nhắc khi thật sự phù hợp nhé ạ.";
        List<FanpageBotResponse.ReplyBubble> deescalateOut = CleanAgentOutputRefereeAndSanitizer.enforceP37B2TurnActionAndBubbleIdeaDisciplineInPlace(
                bubbles(naturalDeescalation),
                "Vẫn thấy đắt quá em ơi",
                histWithPriorObjection,
                null,
                null,
                "em",
                "chị",
                "chuyên viên"
        );
        assertThat(deescalateOut).hasSize(1);
        assertThat(deescalateOut.get(0).text()).isEqualTo(naturalDeescalation);
    }

    @Test
    @DisplayName("ACI V3 — Guardrail 2 & 3: TurnContextSnapshot bất biến và phân tách rạch ròi 6 nhóm ý định (PRICE_OBJECTION, COMPETITOR_COMPARISON, BUDGET_INQUIRY, PRIVACY_CONCERN, CUSTOMER_DEFERRAL, EXPLICIT_REFUSAL)")
    void aciV3Guardrail2And3TurnContextSnapshotAndSemanticIntentSeparation() {
        // 1. Pure COMPETITOR_COMPARISON: KHÔNG tự biến thành PRICE_OBJECTION, KHÔNG tăng priceObjectionCount
        var compSnapshot = AdaptiveConversationIntelligence.buildTurnContextSnapshot(
                "T2",
                "Chỗ khác gội có 90k thôi",
                "chị",
                true,
                0,
                List.of(),
                null,
                ConversationState.StructuredBookingState.none()
        );
        assertThat(compSnapshot.detectedIntents())
                .contains(AdaptiveConversationIntelligence.CanonicalTurnIntent.COMPETITOR_COMPARISON)
                .doesNotContain(AdaptiveConversationIntelligence.CanonicalTurnIntent.PRICE_OBJECTION);
        assertThat(compSnapshot.incrementsPriceObjection()).isFalse();
        assertThat(compSnapshot.authoritativePriceObjectionCount()).isZero();
        assertThat(compSnapshot.plan().recommendedAction())
                .isEqualTo(AdaptiveConversationIntelligence.TurnAction.VALUE_EXPLANATION);
        assertThat(compSnapshot.plan().customerState().needs()).isEmpty();

        // 2. Multi-intent COMPETITOR_COMPARISON + PRICE_OBJECTION: mang cả 2 ý định và chỉ tăng bộ đếm khi thực sự chê đắt/quá sức
        var multiIntentSnapshot = AdaptiveConversationIntelligence.buildTurnContextSnapshot(
                "T3",
                "Thôi chịu đấy, ở ngoài kia gội đầu có 50k mà bên em đắt quá",
                "chị",
                true,
                0,
                List.of(),
                null,
                ConversationState.StructuredBookingState.none()
        );
        assertThat(multiIntentSnapshot.detectedIntents())
                .contains(
                        AdaptiveConversationIntelligence.CanonicalTurnIntent.COMPETITOR_COMPARISON,
                        AdaptiveConversationIntelligence.CanonicalTurnIntent.PRICE_OBJECTION
                );
        assertThat(multiIntentSnapshot.incrementsPriceObjection()).isTrue();
        assertThat(multiIntentSnapshot.authoritativePriceObjectionCount()).isEqualTo(1);

        // 3. BUDGET_INQUIRY: hỏi gói theo ngân sách KHÔNG tăng priceObjectionCount
        var budgetSnapshot = AdaptiveConversationIntelligence.buildTurnContextSnapshot(
                "T2",
                "Bên mình có gói nào tầm 100k đổ lại không shop?",
                "chị",
                false,
                0,
                List.of(),
                null,
                ConversationState.StructuredBookingState.none()
        );
        assertThat(budgetSnapshot.detectedIntents())
                .contains(AdaptiveConversationIntelligence.CanonicalTurnIntent.BUDGET_INQUIRY)
                .doesNotContain(AdaptiveConversationIntelligence.CanonicalTurnIntent.PRICE_OBJECTION);
        assertThat(budgetSnapshot.incrementsPriceObjection()).isFalse();
        assertThat(budgetSnapshot.authoritativePriceObjectionCount()).isZero();

        // 4. PRIVACY_CONCERN: hỏi sự riêng tư/ngại chung phòng KHÔNG tăng priceObjectionCount và KHÔNG làm bẩn needs
        var privacySnapshot = AdaptiveConversationIntelligence.buildTurnContextSnapshot(
                "T2",
                "Chị hơi ngại chung phòng đông người, bên em có rèm che riêng tư không?",
                "chị",
                true,
                0,
                List.of(),
                null,
                ConversationState.StructuredBookingState.none()
        );
        assertThat(privacySnapshot.detectedIntents())
                .contains(AdaptiveConversationIntelligence.CanonicalTurnIntent.PRIVACY_CONCERN)
                .doesNotContain(AdaptiveConversationIntelligence.CanonicalTurnIntent.PRICE_OBJECTION);
        assertThat(privacySnapshot.incrementsPriceObjection()).isFalse();
        assertThat(privacySnapshot.authoritativePriceObjectionCount()).isZero();
        assertThat(privacySnapshot.plan().customerState().needs()).isEmpty();

        // 5. CUSTOMER_DEFERRAL: khách hẹn xem thêm KHÔNG tăng priceObjectionCount
        var deferralSnapshot = AdaptiveConversationIntelligence.buildTurnContextSnapshot(
                "T3",
                "Để chị tham khảo thêm rồi có gì chị nhắn lại sau nhé",
                "chị",
                true,
                1,
                List.of(),
                null,
                ConversationState.StructuredBookingState.none()
        );
        assertThat(deferralSnapshot.detectedIntents())
                .contains(AdaptiveConversationIntelligence.CanonicalTurnIntent.CUSTOMER_DEFERRAL)
                .doesNotContain(AdaptiveConversationIntelligence.CanonicalTurnIntent.PRICE_OBJECTION);
        assertThat(deferralSnapshot.incrementsPriceObjection()).isFalse();
        assertThat(deferralSnapshot.authoritativePriceObjectionCount()).isEqualTo(1);
        assertThat(deferralSnapshot.plan().recommendedAction())
                .isEqualTo(AdaptiveConversationIntelligence.TurnAction.CUSTOMER_DEFERRAL);

        // 6. EXPLICIT_REFUSAL: khách từ chối dứt khoát -> EXPLICIT_REFUSAL + CUSTOMER_EXIT, KHÔNG tăng priceObjectionCount
        var refusalSnapshot = AdaptiveConversationIntelligence.buildTurnContextSnapshot(
                "T4",
                "Thôi chị không làm nữa đâu, cảm ơn em",
                "chị",
                true,
                1,
                List.of(),
                null,
                ConversationState.StructuredBookingState.none()
        );
        assertThat(refusalSnapshot.detectedIntents())
                .contains(AdaptiveConversationIntelligence.CanonicalTurnIntent.EXPLICIT_REFUSAL)
                .doesNotContain(AdaptiveConversationIntelligence.CanonicalTurnIntent.PRICE_OBJECTION);
        assertThat(refusalSnapshot.incrementsPriceObjection()).isFalse();
        assertThat(refusalSnapshot.authoritativePriceObjectionCount()).isEqualTo(1);
        assertThat(refusalSnapshot.plan().recommendedAction())
                .isEqualTo(AdaptiveConversationIntelligence.TurnAction.CUSTOMER_EXIT);
    }

    @Test
    @DisplayName("ACI V3 — Guardrail 4: Bảo toàn an toàn y tế Cấp 4 (115) VÀ hỗ trợ đồng thời yêu cầu chuyển nhân viên xử lý")
    void aciV3Guardrail4ConcurrentAcuteMedicalEmergencyAndHumanHandoff() throws Exception {
        String dualEmergencyAndStaffMsg = "Tự nhiên anh tức ngực khó thở và tê nửa người bên trái quá, cho nhân viên hỗ trợ anh gấp với";
        assertThat(FanpageBotService.isUniversalAcuteMedicalEmergency(dualEmergencyAndStaffMsg)).isTrue();
        assertThat(AdaptiveConversationIntelligence.isExplicitHumanEscalationMessage(dualEmergencyAndStaffMsg)).isTrue();

        String reflectionJson = "{"
                + "\"customer_understanding\":{\"turn_intent\":\"SAFETY_PRECAUTION\",\"secondary_intents\":[\"HUMAN_HANDOFF\"],\"emotion\":\"IMPATIENT\","
                + "\"interaction_permission\":\"OPEN\",\"buying_signal\":\"NONE\"},"
                + "\"priority\":\"SAFETY\","
                + "\"response_plan\":{\"strategy\":\"Transaction Handoff\",\"tone\":\"LEVEL_1_NATURAL\",\"depth\":\"CONCISE\","
                + "\"cta_level\":\"TRANSACTION_HANDOFF\",\"next_action\":\"NO_ADDITIONAL_ACTION\",\"adaptation\":\"PRIORITY_SHIFT\"},"
                + "\"handoff_request\":{\"requested\":true,\"reason\":\"Khách tức ngực khó thở tê nửa người cần cấp cứu 115\"}"
                + "}";
        var reflection = AdaptiveConversationIntelligence.parseAndReconcileAdaptiveCognitiveReflection(
                objectMapper.readTree(reflectionJson), dualEmergencyAndStaffMsg);

        var handoffDecision = AdaptiveConversationIntelligence.evaluateAndExecuteConditionalHandoff(
                java.util.UUID.randomUUID(),
                "sender-emergency-1",
                dualEmergencyAndStaffMsg,
                reflection,
                (pageId, senderId, category, reason) -> AdaptiveConversationIntelligence.HandoffDispatchResult.success("TICKET-115-STAFF")
        );
        assertThat(handoffDecision.isHandoffCreated()).isTrue();
        assertThat(handoffDecision.handoffCategory()).isEqualTo("MEDICAL_SAFETY_HANDOFF");
        assertThat(handoffDecision.handoffReason()).contains("nhân viên");
    }

    @Test
    @DisplayName("ACI V3 — Post-Audit Verification: Đính chính đại từ, Khóa an toàn BookingPatch và Đo lường SanitizerMutationRate")
    void aciV3PostAuditPronounCorrectionSafetyBookingLockAndTelemetry() {
        assertThat(AdaptiveConversationIntelligence.inferExplicitCustomerSelfPronounFromUtterance("Gọi tôi là anh nhé"))
                .isEqualTo("Anh");
        assertThat(AdaptiveConversationIntelligence.inferExplicitCustomerSelfPronounFromUtterance("Mình là nam bạn ơi"))
                .isEqualTo("Anh");
        assertThat(AdaptiveConversationIntelligence.inferExplicitCustomerSelfPronounFromUtterance("Không phải chị đâu, gọi là anh"))
                .isEqualTo("Anh");
        assertThat(AdaptiveConversationIntelligence.inferExplicitCustomerSelfPronounFromUtterance("Gọi mình là chị nhé"))
                .isEqualTo("Chị");

        assertThat(AdaptiveConversationIntelligence.hasGenuinePriceObjectionCue("Sao bên em 480k mà chỗ khác rẻ hơn có 300k vậy?"))
                .isFalse();
        assertThat(AdaptiveConversationIntelligence.hasGenuinePriceObjectionCue("Sao bên em đắt thế, chỗ khác rẻ hơn có 300k thôi"))
                .isTrue();

        var proposedPatch = new AdaptiveConversationIntelligence.ProposedBookingPatch(
                "pkg-neck-60",
                "hôm nay",
                "19:00"
        );
        var bookingAfterLevel3 = AdaptiveConversationIntelligence.applyAuthoritativeBookingPatch(
                ConversationState.StructuredBookingState.none(),
                proposedPatch,
                "Chị mới mổ nội soi vùng bụng tuần trước, đặt cho chị lúc 19h hôm nay nhé",
                "Dạ chị muốn đặt khung giờ nào ạ?",
                List.of(),
                List.of(),
                java.time.ZoneId.of("Asia/Ho_Chi_Minh"),
                AdaptiveConversationIntelligence.BookingReadiness.READY_TO_BOOK,
                "BOOKING_PROGRESSION"
        );
        assertThat(bookingAfterLevel3.status()).isEqualTo(ConversationState.BookingStatus.NONE);

        List<String> rawClean = List.of("Dạ gói Cổ Vai Gáy 60 phút bên em có giá 480.000đ ạ.");
        assertThat(CleanAgentOrchestratorService.computeSanitizerMutationRate(rawClean, rawClean)).isEqualTo(0.0);
        List<String> mutated = List.of("Dạ gói Cổ Vai Gáy 60 phút bên em có giá 480.000đ, hiện có ưu đãi 380.000đ ạ.");
        assertThat(CleanAgentOrchestratorService.computeSanitizerMutationRate(rawClean, mutated)).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("ACI V3 — Relationship Intelligence (FEEL FIRST. SELL SECOND.): SELF_IDENTIFY_GENDER, CUSTOMER_DEFERRAL, Contextual Price+Symptom, VALUE_PERCEPTION_GAP & Enum Leak Guard")
    void aciV3RelationshipIntelligenceFeelFirstSellSecondVerification() throws Exception {
        assertThat(AdaptiveConversationIntelligence.inferExplicitCustomerSelfPronounFromUtterance("t là nam")).isEqualTo("Anh");
        var genderSnapshot = AdaptiveConversationIntelligence.buildTurnContextSnapshot(
                "T4",
                "t là nam",
                "chị",
                true,
                1,
                List.of(
                        new com.example.docmind.dto.bot.FanpageBotRequest.ConversationTurn("user", "đắt"),
                        new com.example.docmind.dto.bot.FanpageBotRequest.ConversationTurn("assistant", "Dạ chị tham khảo gói dưỡng sinh cơ bản 239k ạ.")
                ),
                null,
                ConversationState.StructuredBookingState.none()
        );
        assertThat(genderSnapshot.effectiveCustomerPronoun()).isEqualTo("Anh");
        assertThat(genderSnapshot.detectedIntents())
                .containsExactly(AdaptiveConversationIntelligence.CanonicalTurnIntent.SELF_IDENTIFY_GENDER);
        assertThat(genderSnapshot.incrementsPriceObjection()).isFalse();
        assertThat(genderSnapshot.authoritativePriceObjectionCount()).isEqualTo(1);
        assertThat(genderSnapshot.plan().recommendedAction())
                .isEqualTo(AdaptiveConversationIntelligence.TurnAction.SELF_IDENTIFY_GENDER);

        String genderJson = "{"
                + "\"customer_understanding\":{\"turn_intent\":\"PRICE_OBJECTION\",\"conversation_state\":\"OBJECTION_NEGOTIATION\","
                + "\"inferred_customer_pronoun\":\"Chị\",\"pronoun_confidence\":0.60,\"cost_concern_expressed\":true},"
                + "\"priority\":\"BUDGET_FIT\","
                + "\"response_plan\":{\"strategy\":\"Handle Objection\",\"cta_level\":\"SOFT_CTA\",\"next_action\":\"GENTLE_SCHEDULE_CHECK\"},"
                + "\"activated_skill_codes\":[\"price_objection_sensory_value\"]"
                + "}";
        var reconciledGender = AdaptiveConversationIntelligence.parseAndReconcileAdaptiveCognitiveReflection(
                objectMapper.readTree(genderJson), "t là nam");
        assertThat(reconciledGender.customerUnderstanding().detectedIntent()).isEqualTo("SELF_IDENTIFY_GENDER");
        assertThat(reconciledGender.customerUnderstanding().inferredCustomerPronoun()).isEqualTo("Anh");
        assertThat(reconciledGender.customerUnderstanding().pronounConfidence()).isGreaterThanOrEqualTo(0.99);
        assertThat(reconciledGender.customerUnderstanding().costConcernExpressed()).isFalse();
        assertThat(reconciledGender.responsePlan().ctaLevel()).isEqualTo(AdaptiveConversationIntelligence.CtaLevel.NO_CTA);
        assertThat(reconciledGender.responsePlan().nextAction()).isEqualTo("NO_ADDITIONAL_ACTION");
        assertThat(reconciledGender.activatedSkillCodes()).isEmpty();

        List<FanpageBotResponse.ReplyBubble> genderBubbles = CleanAgentOutputRefereeAndSanitizer.enforceP37B2TurnActionAndBubbleIdeaDisciplineInPlace(
                bubbles("Dạ em xin lỗi Chị vì sơ suất xưng hô lúc nãy ạ, vì các anh thường mỏi vai gáy nhiều hơn. Anh có muốn đặt lịch tối nay không ạ?"),
                "t là nam",
                List.of(),
                ConversationState.StructuredBookingState.none(),
                List.of(),
                "em",
                "Anh",
                "kỹ thuật viên",
                genderSnapshot.plan()
        );
        assertThat(genderBubbles).hasSize(1);
        assertThat(genderBubbles.get(0).text())
                .contains("Anh")
                .doesNotContain("Chị")
                .doesNotContain("các anh thường")
                .doesNotContain("?");

        List<com.example.docmind.dto.bot.FanpageBotRequest.ConversationTurn> histAfterPriceAsk = List.of(
                new com.example.docmind.dto.bot.FanpageBotRequest.ConversationTurn("user", "giá"),
                new com.example.docmind.dto.bot.FanpageBotRequest.ConversationTurn("assistant", "Dạ bên em có các gói từ 129k - 399k ạ. Mình đang mỏi vùng nào ạ?")
        );
        var symptomAfterPricePlan = AdaptiveConversationIntelligence.buildLightweightCustomerUnderstandingPlan(
                "tôi đau đầu mỏi vau gáy",
                histAfterPriceAsk,
                null,
                ConversationState.StructuredBookingState.none()
        );
        assertThat(symptomAfterPricePlan.recommendedAction())
                .isEqualTo(AdaptiveConversationIntelligence.TurnAction.SERVICE_RECOMMENDATION);
        assertThat(symptomAfterPricePlan.customerState().needs())
                .anyMatch(n -> n.contains("vai gáy") || n.contains("đầu"));

        String firstObjJson = "{"
                + "\"customer_understanding\":{\"turn_intent\":\"PRICE_OBJECTION\",\"conversation_state\":\"OBJECTION_NEGOTIATION\",\"cost_concern_expressed\":true},"
                + "\"priority\":\"VALUE_ALIGNMENT\","
                + "\"working_hypothesis\":{\"hypothesis\":\"Khách đang cân nhắc tương quan giá trị\",\"barrier_type\":\"BUDGET_LIMIT\",\"confidence\":0.80,\"lifecycle_op\":\"ADJUST\"},"
                + "\"response_plan\":{\"strategy\":\"Handle Objection\",\"cta_level\":\"SOFT_CTA\",\"next_action\":\"GENTLE_SCHEDULE_CHECK\"}"
                + "}";
        var reconciledObj = AdaptiveConversationIntelligence.parseAndReconcileAdaptiveCognitiveReflection(
                objectMapper.readTree(firstObjJson), "đắt");
        assertThat(reconciledObj.barrierType()).isEqualTo("BUDGET_LIMIT");
        assertThat(reconciledObj.responsePlan().nextAction()).isEqualTo("SOFT_FEELING_CHECK");

        String deferralJson = "{"
                + "\"customer_understanding\":{\"turn_intent\":\"PRICE_OBJECTION\",\"conversation_state\":\"OBJECTION_NEGOTIATION\",\"interaction_permission\":\"OPEN\"},"
                + "\"priority\":\"BUDGET_FIT\","
                + "\"response_plan\":{\"strategy\":\"Handle Objection\",\"cta_level\":\"SOFT_CTA\",\"next_action\":\"ASK_ONE_CLARIFYING_QUESTION\"}"
                + "}";
        var reconciledDeferral = AdaptiveConversationIntelligence.parseAndReconcileAdaptiveCognitiveReflection(
                objectMapper.readTree(deferralJson), "xem đã");
        assertThat(reconciledDeferral.customerUnderstanding().detectedIntent()).isEqualTo("CUSTOMER_DEFERRAL");
        assertThat(reconciledDeferral.customerUnderstanding().conversationState()).isEqualTo("PAUSED_BY_CUSTOMER");
        assertThat(reconciledDeferral.customerUnderstanding().interactionPermission())
                .isEqualTo(AdaptiveConversationIntelligence.InteractionPermission.CONCISE_PREFERRED);
        assertThat(reconciledDeferral.responsePlan().ctaLevel()).isEqualTo(AdaptiveConversationIntelligence.CtaLevel.NO_CTA);
        assertThat(reconciledDeferral.responsePlan().nextAction()).isEqualTo("NO_ADDITIONAL_ACTION");

        List<FanpageBotResponse.ReplyBubble> strippedEnum = CleanAgentOutputRefereeAndSanitizer.stripInternalCatalogSkuCodesInPlace(
                bubbles("Dạ em xin ghi nhận lịch tạm lúc 19h tối nay tại cơ sở Bích Câu để kiểm tra lịch trống thực tế (AWAITING_AVAILABILITY_CHECK) cho Anh nhé ạ."),
                List.of()
        );
        assertThat(strippedEnum).hasSize(1);
        assertThat(strippedEnum.get(0).text()).doesNotContain("AWAITING_AVAILABILITY_CHECK");
        assertThat(CleanAgentOutputRefereeAndSanitizer.repairWrongPronounOpenerInPlace(
                "Với đau đầu mỏi vai gáy thì phần massage cổ vai gáy đủ lâu mới giúp chị dễ chịu thật sự.", "em", "Anh"
        )).contains("giúp Anh dễ chịu").doesNotContain("dễ Anhu");
        String journeyBTurn1 = "Mấy hôm nay tôi đau đầu, hoa mắt, ù tai, người cứ mệt mỏi.";
        assertThat(CleanAgentCatalogAndPromptBuilder.hasHighRiskOrContraindicationCue(journeyBTurn1.toLowerCase())).isFalse();
        assertThat(FanpageBotService.shouldTriggerSafetyContraindicationHandoff(journeyBTurn1, null)).isFalse();
        assertThat(AdaptiveConversationIntelligence.inferExplicitCustomerSelfPronounFromUtterance(
                "Chiều tối nay tầm 6h anh qua được không?"
        )).isEqualTo("Anh");
    }
}
