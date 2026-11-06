package com.example.docmind.service.bot;

import com.example.docmind.dto.PagePersonaConfig;
import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.dto.bot.FanpageBotResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class Stage2ResponseCompositionAndNoHardcodeTest {

    @Test
    @DisplayName("Section 1, 2 & 4: LLM Decision Ownership & Structured Bubble Schema — preserve purpose, 1..3 bubble count, and wording without post-LLM content overrides")
    void llmDecisionOwnershipAndStructuredBubblePurposePreservedEndToEnd() {
        String rawJson = """
                {
                  "brain_reflection": {
                    "customer_understanding": {
                      "turn_intent": "PRICE_INQUIRY",
                      "emotional_state": {"primary": "NEUTRAL", "secondary": [], "confidence": 0.9}
                    },
                    "priority": {"primary": "CLARIFICATION", "secondary": []},
                    "response_plan": {
                      "strategy": "Direct Answer",
                      "tone": "LEVEL_1_NATURAL",
                      "depth": "STANDARD",
                      "cta_level": "SOFT_CTA",
                      "next_action": "ASK_ONE_DISCOVERY_QUESTION"
                    }
                  },
                  "bubbles": [
                    {"text": "Dạ em chào chị ạ, bên em có các liệu trình chăm sóc da chuyên sâu và trẻ hóa công nghệ cao.", "purpose": "GREETING_AND_OVERVIEW"},
                    {"text": "Mức chi phí liệu trình trẻ hóa da cơ bản hiện là 850k cho 60 phút chăm sóc trực tiếp cùng bác sĩ da liễu ạ.", "purpose": "PRICE_QUOTE"},
                    {"text": "Hiện chị đang quan tâm cải thiện sắc tố da hay nâng cơ trẻ hóa để em tư vấn đúng liệu trình hơn ạ?", "purpose": "DISCOVERY_FOLLOWUP"}
                  ]
                }
                """;

        CleanAgentOutputRefereeAndSanitizer.StructuredLlmOutput parsed =
                CleanAgentOutputRefereeAndSanitizer.parseStructuredLlmJsonOutput(rawJson, 0);
        assertThat(parsed.parsedFromJson()).isTrue();
        assertThat(parsed.bubbles()).hasSize(3);
        assertThat(parsed.bubbles().get(0).purpose()).isEqualTo("GREETING_AND_OVERVIEW");
        assertThat(parsed.bubbles().get(1).purpose()).isEqualTo("PRICE_QUOTE");
        assertThat(parsed.bubbles().get(2).purpose()).isEqualTo("DISCOVERY_FOLLOWUP");

        // Verify mergeTrailingColonAndTurn1PriceIntroBubbles does NOT collapse 3 non-colon bubbles into 2
        List<FanpageBotResponse.ReplyBubble> afterColonMerge =
                CleanAgentOutputRefereeAndSanitizer.mergeTrailingColonAndTurn1PriceIntroBubbles(
                        parsed.bubbles(), "Giá bên mình sao ạ?", true);
        assertThat(afterColonMerge).hasSize(3);
        assertThat(afterColonMerge.get(0).purpose()).isEqualTo("GREETING_AND_OVERVIEW");
        assertThat(afterColonMerge.get(1).purpose()).isEqualTo("PRICE_QUOTE");
        assertThat(afterColonMerge.get(2).purpose()).isEqualTo("DISCOVERY_FOLLOWUP");

        // Verify enforceP37B2TurnActionAndBubbleIdeaDisciplineInPlace does NOT prepend starting price or rewrite LLM question
        PackageCatalogService.PackageSummary aestheticPkg = new PackageCatalogService.PackageSummary(
                "TMV01", "TMV01", "ALL", "Trẻ Hóa Da Cơ Bản", "SKIN",
                List.of("da mặt"), 60, "60 phút", 850_000L, "850k",
                "Phòng điều trị riêng", "Soi da, làm sạch sâu, điện di tinh chất", "", "ALL", false
        );
        AdaptiveConversationIntelligence.LightweightCustomerUnderstandingPlan plan =
                AdaptiveConversationIntelligence.buildLightweightCustomerUnderstandingPlan(
                        "Giá", List.of(), null, ConversationState.StructuredBookingState.none());
        List<FanpageBotResponse.ReplyBubble> afterDiscipline =
                CleanAgentOutputRefereeAndSanitizer.enforceP37B2TurnActionAndBubbleIdeaDisciplineInPlace(
                        afterColonMerge,
                        "Giá",
                        List.of(),
                        ConversationState.StructuredBookingState.none(),
                        List.of(aestheticPkg),
                        "em",
                        "chị",
                        "bác sĩ",
                        plan
                );
        assertThat(afterDiscipline).hasSize(3);
        assertThat(afterDiscipline.get(0).text()).isEqualTo(parsed.bubbles().get(0).text());
        assertThat(afterDiscipline.get(1).text()).isEqualTo(parsed.bubbles().get(1).text());
        assertThat(afterDiscipline.get(2).text()).isEqualTo(parsed.bubbles().get(2).text());
        assertThat(afterDiscipline.get(2).purpose()).isEqualTo("DISCOVERY_FOLLOWUP");
    }

    @Test
    @DisplayName("Section 3: Strict Multi-Tenant No-Hardcode — no hardcoded package code sets in Sanitizer; data-driven PackageSummary inspection works for arbitrary tenants")
    void strictMultiTenantNoHardcodeInJavaCore() {
        List<String> fieldNames = Arrays.stream(CleanAgentOutputRefereeAndSanitizer.class.getDeclaredFields())
                .map(Field::getName)
                .toList();
        assertThat(fieldNames)
                .doesNotContain("NON_VIP_COMMON_ROOM_PACKAGE_CODES", "NANG_SEN_BUFFET_CONFIRMED_PACKAGE_CODES");

        PackageCatalogService.PackageSummary commonRoomPkg = new PackageCatalogService.PackageSummary(
                "TENANT_A_01", "TENANT_A_01", "ALL", "Gói Chăm Sóc Cơ Bản", "CARE",
                List.of("toàn thân"), 60, "60 phút", 300_000L, "300k",
                "Phòng chung có rèm riêng", "Trà thảo mộc, gội thảo dược", "", "ALL", false
        );
        PackageCatalogService.PackageSummary vipBuffetPkg = new PackageCatalogService.PackageSummary(
                "TENANT_B_VIP", "TENANT_B_VIP", "ALL", "Gói Chăm Sóc Chuyên Sâu VIP", "CARE",
                List.of("toàn thân"), 90, "90 phút", 650_000L, "650k",
                "Phòng VIP riêng", "Ngâm bồn thảo dược, xông hơi, buffet thực dưỡng", "", "ALL", false
        );

        assertThat(CleanAgentOutputRefereeAndSanitizer.packageConfirmedAllowsPrivateOrVipRoom(commonRoomPkg)).isFalse();
        assertThat(CleanAgentOutputRefereeAndSanitizer.packageConfirmedAllowsPrivateOrVipRoom(vipBuffetPkg)).isTrue();

        assertThat(CleanAgentOutputRefereeAndSanitizer.packageConfirmedAllowsBuffet(commonRoomPkg)).isFalse();
        assertThat(CleanAgentOutputRefereeAndSanitizer.packageConfirmedAllowsBuffet(vipBuffetPkg)).isTrue();

        assertThat(CleanAgentOutputRefereeAndSanitizer.packageConfirmedAllowsThucDuong(commonRoomPkg)).isFalse();
        assertThat(CleanAgentOutputRefereeAndSanitizer.packageConfirmedAllowsThucDuong(vipBuffetPkg)).isTrue();

        assertThat(CleanAgentOutputRefereeAndSanitizer.packageConfirmedAllowsHerbalBath(commonRoomPkg)).isFalse();
        assertThat(CleanAgentOutputRefereeAndSanitizer.packageConfirmedAllowsHerbalBath(vipBuffetPkg)).isTrue();

        assertThat(CleanAgentOutputRefereeAndSanitizer.packageConfirmedAllowsSteam(commonRoomPkg)).isFalse();
        assertThat(CleanAgentOutputRefereeAndSanitizer.packageConfirmedAllowsSteam(vipBuffetPkg)).isTrue();
    }

    @Test
    @DisplayName("Section 5: Referee & Price Provenance on T15 ('loạn ngôn à, nãy vừa 339 giờ lại mọc đâu ra gói 370') — distinguishes asked, negated, and cross-branch prices from ungrounded prices")
    void refereeAndPriceProvenanceHandlesT15DiscrepancyClarificationAccurately() {
        String t15CustomerMsg = "loạn ngôn à, nãy vừa 339 giờ lại mọc đâu ra gói 370";
        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Giá gói gội dưỡng sinh chuyên sâu bao nhiêu?"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ Gói Dưỡng Sinh Chuyên Sâu 75 phút tại cơ sở Hà Nội có giá 339k ạ.")
        );

        // 1. Price provenance extracts both 339_000 and 370_000 from shorthand 3-digit utterance
        CleanAgentOutputRefereeAndSanitizer.CustomerSessionPriceProvenance prov =
                CleanAgentOutputRefereeAndSanitizer.extractCustomerSessionPriceProvenance(t15CustomerMsg, history);
        assertThat(prov.userAskedPrices()).contains(339_000L, 370_000L);

        // 2. Semantic role classification distinguishes negated/corrected price and cross-branch clarification price
        String negatedBubble = "Dạ em xin lỗi làm mình hiểu nhầm ạ, tại cơ sở Hà Nội bên em không có gói 370k mà chỉ có Gói Dưỡng Sinh Chuyên Sâu 75 phút giá 339k thôi ạ.";
        int idx370Neg = negatedBubble.indexOf("370k");
        var roleNeg = CleanAgentOutputRefereeAndSanitizer.classifyPriceMentionSemanticRole(
                negatedBubble, idx370Neg, idx370Neg + 4, 370_000L, Set.of(239_000L, 339_000L), Set.of(), prov);
        assertThat(roleNeg).isEqualTo(CleanAgentOutputRefereeAndSanitizer.PriceMentionSemanticRole.BOT_NEGATED_OR_CORRECTED_PRICE);

        String crossBranchBubble = "Dạ mức 339k là giá niêm yết tại Hà Nội, còn mức 370k là tại cơ sở TP.HCM nên em xin phép đính chính lại cho rõ ạ.";
        int idx370Branch = crossBranchBubble.indexOf("370k");
        var roleBranch = CleanAgentOutputRefereeAndSanitizer.classifyPriceMentionSemanticRole(
                crossBranchBubble, idx370Branch, idx370Branch + 4, 370_000L, Set.of(239_000L, 339_000L), Set.of(), prov);
        assertThat(roleBranch).isEqualTo(CleanAgentOutputRefereeAndSanitizer.PriceMentionSemanticRole.CROSS_BRANCH_CLARIFICATION_PRICE);

        // 3. Referee checkAciV2TruthAuthorityAndSafety passes valid T15 clarification without false-positive UNGROUNDED_PRICE_MENTIONED
        PackageCatalogService.PackageSummary ds03Hn = new PackageCatalogService.PackageSummary(
                "DS03", "DS03", "HN", "Gói Dưỡng Sinh Chuyên Sâu", "HAIR",
                List.of("đầu", "cổ", "vai", "gáy"), 75, "75 phút", 339_000L, "339k",
                "Phòng chung có rèm riêng", "gội đầu dưỡng sinh chuyên sâu", "", "HN", false
        );
        List<String> validT15Violations = CleanAgentOutputRefereeAndSanitizer.checkAciV2TruthAuthorityAndSafety(
                List.of(new FanpageBotResponse.ReplyBubble(negatedBubble, 0, "CLARIFICATION")),
                List.of(ds03Hn),
                List.of(ds03Hn),
                List.of(),
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                ConversationState.StructuredBookingState.none(),
                List.of(ds03Hn),
                PagePersonaConfig.defaults(),
                t15CustomerMsg,
                false,
                null,
                null,
                AdaptiveConversationIntelligence.AdaptiveCognitiveReflection.empty(),
                List.of(),
                "Nàng Sen",
                history.get(1).content(),
                history
        );
        assertThat(validT15Violations).isEmpty();

        // 4. Post-LLM complaint sanitizers preserve the 339k / 370k clarification on T15
        List<FanpageBotResponse.ReplyBubble> afterNegStrip =
                CleanAgentOutputRefereeAndSanitizer.stripUnsolicitedPackagePitchOnNegativeReactionInPlace(
                        List.of(new FanpageBotResponse.ReplyBubble(negatedBubble, 0, "CLARIFICATION")),
                        t15CustomerMsg, List.of(), List.of(), "em", "mình");
        assertThat(afterNegStrip).hasSize(1);
        assertThat(afterNegStrip.get(0).text()).contains("339k").contains("370k");
        assertThat(afterNegStrip.get(0).purpose()).isEqualTo("CLARIFICATION");

        // 5. Truly ungrounded price (e.g., 415k not in catalog and not asked by customer) is still blocked by Referee
        List<String> ungroundedViolations = CleanAgentOutputRefereeAndSanitizer.checkAciV2TruthAuthorityAndSafety(
                List.of(new FanpageBotResponse.ReplyBubble("Dạ bên em còn có gói đặc biệt giá 415k nữa ạ.", 0)),
                List.of(ds03Hn),
                List.of(ds03Hn),
                List.of(),
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                ConversationState.StructuredBookingState.none(),
                List.of(ds03Hn),
                PagePersonaConfig.defaults(),
                t15CustomerMsg,
                false,
                null,
                null,
                AdaptiveConversationIntelligence.AdaptiveCognitiveReflection.empty(),
                List.of(),
                "Nàng Sen",
                history.get(1).content(),
                history
        );
        assertThat(ungroundedViolations).anyMatch(v -> v.startsWith("UNGROUNDED_PRICE_MENTIONED:"));
    }

    @Test
    @DisplayName("Section 6: Bubble Delay & Message Lifecycle for 1, 2, and 3 Bubbles")
    void bubbleDelayAndMessageLifecycleForOneTwoAndThreeBubbles() {
        List<FanpageBotResponse.ReplyBubble> threeBubbles = List.of(
                new FanpageBotResponse.ReplyBubble("Dạ em chào chị ạ, bên em có các liệu trình trẻ hóa da công nghệ cao.", 0, "GREETING"),
                new FanpageBotResponse.ReplyBubble("Gói Trẻ Hóa Da Cơ Bản 60 phút có giá 850k ạ.", 0, "PRICE_QUOTE"),
                new FanpageBotResponse.ReplyBubble("Hiện chị muốn cải thiện vùng da nào nhất ạ?", 0, "FOLLOWUP_QUESTION")
        );

        List<FanpageBotResponse.ReplyBubble> delayed = PriceQuoteReadingBudgetScheduler.applyAdaptiveReadingBudgetTypingDelays(
                threeBubbles, 1, 10_000, 1_500);
        assertThat(delayed).hasSize(3);
        assertThat(delayed.get(0).typingDelayMs()).isZero();
        assertThat(delayed.get(1).typingDelayMs()).isEqualTo(1_500);
        // Concise single-price Bubble 2 (12 words <= 35 words) + short follow-up Bubble 3 (<= 22 words) scales reading budget down to 3200ms (instead of rigid 10_000ms)
        assertThat(delayed.get(2).typingDelayMs()).isEqualTo(3_200);
        assertThat(delayed.get(2).purpose()).isEqualTo("FOLLOWUP_QUESTION");

        // Verify explicit BubbleDeliveryLifecycleState trail on SentBubbleExecutionLog
        PriceQuoteReadingBudgetScheduler.SentBubbleExecutionLog sentLog =
                new PriceQuoteReadingBudgetScheduler.SentBubbleExecutionLog(
                        2, "Hiện chị muốn cải thiện vùng da nào nhất ạ?", true, "mid.123", 1_000L, 6_000L, 7_005L, false, false);
        assertThat(sentLog.terminalLifecycleState())
                .isEqualTo(PriceQuoteReadingBudgetScheduler.BubbleDeliveryLifecycleState.PROVIDER_ACCEPTED);
        assertThat(sentLog.lifecycleTrail(7_120L)).containsExactly(
                PriceQuoteReadingBudgetScheduler.BubbleDeliveryLifecycleState.SCHEDULED_TYPING_DELAY,
                PriceQuoteReadingBudgetScheduler.BubbleDeliveryLifecycleState.SEND_REQUESTED,
                PriceQuoteReadingBudgetScheduler.BubbleDeliveryLifecycleState.PROVIDER_ACCEPTED,
                PriceQuoteReadingBudgetScheduler.BubbleDeliveryLifecycleState.DELIVERY_CONFIRMED
        );

        PriceQuoteReadingBudgetScheduler.SentBubbleExecutionLog cancelledLog =
                new PriceQuoteReadingBudgetScheduler.SentBubbleExecutionLog(
                        2, "Hiện chị muốn cải thiện vùng da nào nhất ạ?", false, null, 1_000L, 6_000L, 3_200L, true, false);
        assertThat(cancelledLog.terminalLifecycleState())
                .isEqualTo(PriceQuoteReadingBudgetScheduler.BubbleDeliveryLifecycleState.MESSAGE_SUPPRESSED_OR_CANCELLED);
    }

    @Test
    @DisplayName("Section 7: Multi-Page Non-Spa Validation — Thẩm mỹ viện page never receives Spa vocabulary from Java")
    void multiPageNonSpaValidationNeverInjectsSpaVocabulary() {
        String complaintMsg = "Chưa tìm hiểu gì về khách đã xả thông tin rồi";
        List<FanpageBotResponse.ReplyBubble> complaintReply =
                CleanAgentOutputRefereeAndSanitizer.enforceAciV2StyleComplaintNoCtaAndAntiClicheInPlace(
                        List.of(new FanpageBotResponse.ReplyBubble(
                                "Dạ em xin lỗi chị vì chưa hỏi kỹ nhu cầu trước khi chia sẻ thông tin ạ. Hiện chị đang quan tâm cải thiện tình trạng da nào để em hỗ trợ đúng ý chị ạ?",
                                0,
                                "APOLOGY_AND_DISCOVERY"
                        )),
                        complaintMsg,
                        null,
                        "em",
                        "chị"
                );
        assertThat(complaintReply).hasSize(1);
        assertThat(complaintReply.get(0).text())
                .contains("cải thiện tình trạng da nào")
                .doesNotContain("mỏi")
                .doesNotContain("gội đầu")
                .doesNotContain("thảo dược")
                .doesNotContain("cổ vai gáy");

        List<FanpageBotResponse.ReplyBubble> genderReply =
                CleanAgentOutputRefereeAndSanitizer.enforceP37B2TurnActionAndBubbleIdeaDisciplineInPlace(
                        List.of(new FanpageBotResponse.ReplyBubble("Anh muốn hỏi thêm gì ạ?", 0, "GENDER_ACK")),
                        "t là nam",
                        List.of(),
                        ConversationState.StructuredBookingState.none(),
                        List.of(),
                        "em",
                        "Anh",
                        "bác sĩ",
                        AdaptiveConversationIntelligence.buildLightweightCustomerUnderstandingPlan(
                                "t là nam", List.of(), null, ConversationState.StructuredBookingState.none())
                );
        assertThat(genderReply).hasSize(1);
        assertThat(genderReply.get(0).text())
                .doesNotContain("cổ vai gáy")
                .doesNotContain("giảm đau đầu")
                .doesNotContain("Spa");
    }
}
