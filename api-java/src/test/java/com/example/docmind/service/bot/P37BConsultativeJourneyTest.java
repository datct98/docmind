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
                bubbles("Mình nói đúng ạ, em xin lỗi vì đã đưa nhiều thông tin quá sớm khi chưa hỏi kỹ nhu cầu của mình. Em sẽ dừng lại và chỉ tư vấn đúng điều mình cần nhé."),
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
}
