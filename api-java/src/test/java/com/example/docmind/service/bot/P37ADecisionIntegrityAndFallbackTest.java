package com.example.docmind.service.bot;

import com.example.docmind.dto.LearnedRuleDto;
import com.example.docmind.dto.PagePersonaConfig;
import com.example.docmind.dto.bot.AgentSkillDto;
import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.dto.bot.FanpageBotResponse;
import com.example.docmind.repository.RetrievedChunk;
import com.example.docmind.service.retrieval.PromptBuilder;
import com.example.docmind.service.retrieval.ReciprocalRankFusion;
import com.example.docmind.service.retrieval.RetrievalService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class P37ADecisionIntegrityAndFallbackTest {

    private static final UUID PAGE_ID = UUID.fromString("b23cd382-4f7a-4829-8c54-1d410da14bf8");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        SentBubbleLedger.useStore(new SentBubbleLedger.InMemoryStore());
        SentBubbleLedger.clear();
        SenderEpochRegistry.clearAll();
        ConversationState.clearSessionCache();
    }

    @Test
    @DisplayName("P3.7-A Req 1.1: T5 ('chưa tìm hiểu j về KH tự nhiên xả cả đống thông tin rồi lại tư vấn lấy ráy tai') ưu tiên PROCESS_FEEDBACK + FRUSTRATED, chặn tự khai danh tính AI")
    void processFeedbackDominatesMetaRemarkAndStripsUnsolicitedAiIdentityWhilePreservingDirectIdentityAnswers() throws Exception {
        String t5Msg = "chưa tìm hiểu j về KH tự nhiên xả cả đống thông tin rồi lại tư vấn lấy ráy tai";
        String t7Msg = "Dell hiểu sao tự khia mình là AI, KH chạy mất dép. Đã theé nch k có tý cảm xúc, k có chút chiến lược hay nghệ thuật bán hàng nào hết";

        assertThat(CleanAgentOutputRefereeAndSanitizer.isCommunicationStyleComplaintMessage(t5Msg)).isTrue();
        assertThat(CleanAgentOutputRefereeAndSanitizer.isProcessOrConsultationFeedbackComplaint(t5Msg)).isTrue();
        assertThat(CleanAgentOutputRefereeAndSanitizer.isDirectBotIdentityOrTrainingQuestion(t5Msg)).isFalse();

        assertThat(CleanAgentOutputRefereeAndSanitizer.isCommunicationStyleComplaintMessage(t7Msg)).isTrue();
        assertThat(CleanAgentOutputRefereeAndSanitizer.isProcessOrConsultationFeedbackComplaint(t7Msg)).isTrue();
        assertThat(CleanAgentOutputRefereeAndSanitizer.isDirectBotIdentityOrTrainingQuestion(t7Msg)).isFalse();

        // Direct bot identity / training question must still be recognized as direct identity question
        assertThat(CleanAgentOutputRefereeAndSanitizer.isDirectBotIdentityOrTrainingQuestion("Thế em đc training nói vậy à")).isTrue();
        assertThat(CleanAgentOutputRefereeAndSanitizer.isDirectBotIdentityOrTrainingQuestion("Em là bot hay người thật vậy?")).isTrue();

        // Simulate the exact T5 LLM reflection from production log where turn_intent was META_COMMUNICATION_REMARK and secondary_intents had PROCESS_FEEDBACK
        JsonNode t5RawReflection = objectMapper.readTree("""
                {
                  "conversational_context": {"current_topic": "Phàn nàn cách tư vấn"},
                  "customer_understanding": {
                    "turn_intent": "META_COMMUNICATION_REMARK",
                    "secondary_intents": ["PROCESS_FEEDBACK"],
                    "conversation_state": "META_PLAYFUL",
                    "conversation_goal": "EXPLORE",
                    "interaction_permission": "CONCISE_PREFERRED",
                    "buying_signal": "NONE",
                    "emotion": "FRUSTRATED",
                    "confirmed_facts_delta": [
                      {"key": "need_scope", "value": "tai", "op": "ADD", "status": "CURRENT"}
                    ]
                  },
                  "priority": "CONVERSATION_RECOVERY",
                  "response_plan": {
                    "strategy": "Reassure Honestly",
                    "tone": "LEVEL_1_NATURAL",
                    "depth": "CONCISE",
                    "cta_level": "NO_CTA",
                    "next_action": "NO_ADDITIONAL_ACTION",
                    "adaptation": "PIVOT"
                  },
                  "activated_skill_codes": ["bot_identity_and_meta_remark_handling"]
                }
                """);

        AdaptiveConversationIntelligence.AdaptiveCognitiveReflection reconciledT5 =
                AdaptiveConversationIntelligence.parseAndReconcileAdaptiveCognitiveReflection(t5RawReflection, t5Msg);

        assertThat(reconciledT5.customerUnderstanding().detectedIntent()).isEqualTo("PROCESS_FEEDBACK");
        assertThat(reconciledT5.customerUnderstanding().emotionalState().primary()).isEqualTo("FRUSTRATED");
        assertThat(reconciledT5.customerUnderstanding().conversationState()).isEqualTo("FRICTION_RECOVERY");
        assertThat(reconciledT5.priority().primary()).isEqualTo("SERVICE_RECOVERY");
        assertThat(reconciledT5.responsePlan().strategy()).isEqualTo("Active Listening");
        assertThat(reconciledT5.responsePlan().ctaLevel()).isEqualTo(AdaptiveConversationIntelligence.CtaLevel.NO_CTA);
        assertThat(reconciledT5.responsePlan().nextAction()).isEqualTo("NO_ADDITIONAL_ACTION");
        assertThat(reconciledT5.strategy().adaptation()).isEqualTo(AdaptiveConversationIntelligence.StrategyAdaptationMode.PIVOT);
        assertThat(reconciledT5.activatedSkillCodes()).doesNotContain("bot_identity_and_meta_remark_handling");
        assertThat(reconciledT5.confirmedFactsDelta()).isEmpty();

        // Sanitizer strips unsolicited "Dạ em là trợ lý AI..." on T5 process feedback complaint
        List<FanpageBotResponse.ReplyBubble> t5LeakedAiBubbles = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Dạ em là trợ lý AI của Nàng Sen, em xin ghi nhận góp ý của chị ạ. Do em vội đưa mức giá gần 100k mà chưa hỏi kỹ nhu cầu của mình trước, em xin rút kinh nghiệm để lắng nghe chị tốt hơn ạ.",
                        0
                )
        );
        List<FanpageBotResponse.ReplyBubble> sanitizedT5 =
                CleanAgentOutputRefereeAndSanitizer.enforceAciV2StyleComplaintNoCtaAndAntiClicheInPlace(
                        t5LeakedAiBubbles,
                        t5Msg,
                        "Dạ hiện tại Nàng Sen chưa có gói dịch vụ nào ở mức 100k ạ.",
                        "em",
                        "chị"
                );
        assertThat(sanitizedT5).hasSize(1);
        assertThat(sanitizedT5.get(0).text())
                .doesNotContainIgnoringCase("trợ lý AI")
                .containsIgnoringCase("xin lỗi chị");

        // Sanitizer preserves AI identity admission when customer directly asks about training/identity
        List<FanpageBotResponse.ReplyBubble> directTrainingBubbles = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Dạ em là trợ lý AI hỗ trợ thông tin dịch vụ trên trang Nàng Sen, em xin tiếp thu góp ý của anh để điều chỉnh cách trò chuyện tự nhiên hơn ạ.",
                        0
                )
        );
        List<FanpageBotResponse.ReplyBubble> sanitizedDirect =
                CleanAgentOutputRefereeAndSanitizer.enforceAciV2StyleComplaintNoCtaAndAntiClicheInPlace(
                        directTrainingBubbles,
                        "Thế em đc training nói vậy à",
                        "Dạ gói 399k ạ.",
                        "em",
                        "anh"
                );
        assertThat(sanitizedDirect.get(0).text()).containsIgnoringCase("trợ lý AI");
    }

    @Test
    @DisplayName("P3.7-A Req 1.1 & 2: T3 ('có gói nào 100k k') nhận diện đúng tìm hiểu dịch vụ theo ngân sách (ASK_PRICE + BUDGET_FIT), chạy FAST_NO_THINK và không nạp nhầm PB-SPA-EAR-001")
    void budgetBoundedServiceInquiryUsesFastNoThinkAndAvoidsBlindEarPackageRuleInjection() throws Exception {
        String t3Msg = "có gói nào 100k k";

        assertThat(CleanAgentOrchestratorService.isPureBudgetBoundedServiceInquiry(t3Msg)).isTrue();
        assertThat(CleanAgentOrchestratorService.isConfirmedCustomerPriceObjection(t3Msg, true)).isFalse();

        FanpageBotRequest req = new FanpageBotRequest(
                PAGE_ID.toString(), "fb-test", "Customer", t3Msg, List.of(), List.of(), List.of(), 6, null, null, null, null
        );
        AdaptiveConversationIntelligence.ThreeStreamTurnInput threeStream =
                AdaptiveConversationIntelligence.buildThreeStreamInput(
                        req, buildSampleCatalog(), PagePersonaConfig.empty(), ZoneId.of("Asia/Ho_Chi_Minh")
                );
        ThinkingMode mode = CleanAgentOrchestratorService.selectAciAdaptiveThinkingMode(t3Msg, threeStream, List.of());
        assertThat(mode).isEqualTo(ThinkingMode.FAST_NO_THINK);

        // Actual price objection with "rẻ hơn" or "đắt" must still trigger price objection
        assertThat(CleanAgentOrchestratorService.isPureBudgetBoundedServiceInquiry("Có gói nào rẻ hơn tầm 200k không em?")).isFalse();
        assertThat(CleanAgentOrchestratorService.isConfirmedCustomerPriceObjection("Có gói nào rẻ hơn tầm 200k không em?", true)).isTrue();

        JsonNode t3RawJson = objectMapper.readTree("""
                {
                  "conversational_context": {"current_topic": "Tìm gói 100k"},
                  "customer_understanding": {
                    "turn_intent": "PRICE_OBJECTION",
                    "conversation_goal": "EXPLORE",
                    "interaction_permission": "OPEN",
                    "buying_signal": "WEAK",
                    "cost_concern_expressed": true
                  },
                  "priority": "VALUE_ALIGNMENT",
                  "response_plan": {
                    "strategy": "Explain Value",
                    "tone": "LEVEL_1_NATURAL",
                    "depth": "CONCISE",
                    "cta_level": "SOFT_CTA",
                    "next_action": "ASK_ONE_CLARIFYING_QUESTION",
                    "adaptation": "CONTINUATION"
                  }
                }
                """);
        AdaptiveConversationIntelligence.AdaptiveCognitiveReflection reconciledT3 =
                AdaptiveConversationIntelligence.parseAndReconcileAdaptiveCognitiveReflection(t3RawJson, t3Msg);
        assertThat(reconciledT3.customerUnderstanding().detectedIntent()).isEqualTo("ASK_PRICE");
        assertThat(reconciledT3.customerUnderstanding().secondaryIntents()).contains("BUDGET_FIT");
        assertThat(reconciledT3.customerUnderstanding().costConcernExpressed()).isFalse();
        assertThat(reconciledT3.priority().primary()).isEqualTo("BUDGET_FIT");
        assertThat(reconciledT3.responsePlan().strategy()).isEqualTo("Direct Answer");

        // Verify learned rules selection on "giá" and "có gói nào 100k k" does NOT inject SAFE-NEURO-001, PB-SPA-PRICE-OBJECTION-001/002, or PB-SPA-EAR-001
        List<LearnedRuleDto> sampleRules = List.of(
                buildSampleRule("SAFE-NEURO-001", "SAFETY", true, List.of("safety", "neurological", "đau", "tai", "chảy", "dịch")),
                buildSampleRule("PB-SPA-PRICE-OBJECTION-001", "PRICE_OBJECTION", false, List.of("giá", "gói", "đắt", "cao")),
                buildSampleRule("PB-SPA-EAR-001", "SERVICE_SCOPE", false, List.of("giá", "gói", "lấy ráy tai", "129k"))
        );
        assertThat(CleanAgentCatalogAndPromptBuilder.selectTurnRelevantCompactRules(sampleRules, "giá", true, 5)).isEmpty();
        assertThat(CleanAgentCatalogAndPromptBuilder.selectTurnRelevantCompactRules(sampleRules, t3Msg, false, 5)).isEmpty();
    }

    @Test
    @DisplayName("P3.7-A Req 1.2: T5 ('...rồi lại tư vấn lấy ráy tai') cô lập tuyệt đối candidate verified=false — 0 trích xuất 'tai' từ lời trích dẫn bot, 0 mở rộng từ khóa, 0 ghi vào confirmed_facts")
    void unverifiedCandidateFromComplaintQuotingBotReplyIsCompletelyBlockedAcrossPipeline() throws Exception {
        String t5Msg = "chưa tìm hiểu j về KH tự nhiên xả cả đống thông tin rồi lại tư vấn lấy ráy tai";
        PagePersonaConfig cfg = objectMapper.readValue("""
                {
                  "botName": "Nàng Sen",
                  "selfPronoun": "em",
                  "customerPronoun": "chị",
                  "scopeTaxonomy": ["đầu", "cổ", "vai", "gáy", "lưng", "toàn thân", "tai"]
                }
                """, PagePersonaConfig.class);
        FanpageBotRequest req = new FanpageBotRequest(
                PAGE_ID.toString(), "fb-test", "Customer", t5Msg, List.of(), List.of(), List.of(), 6, null, null, null, null
        );
        AdaptiveConversationIntelligence.ThreeStreamTurnInput threeStream =
                AdaptiveConversationIntelligence.buildThreeStreamInput(
                        req, buildSampleCatalog(), cfg, ZoneId.of("Asia/Ho_Chi_Minh")
                );

        // 1. Parser must NOT extract SCOPE_OR_AREA_CANDIDATE(rawValue=tai, verified=false) from T5 complaint quoting bot's ear consultation
        assertThat(threeStream.unverifiedParserCandidates())
                .as("No unverified parser candidate 'tai' may be extracted from a complaint quoting the bot's reply")
                .isEmpty();

        // 2. RetrievalService.buildExpandedKeywords must return empty list on communication/process complaint
        LearnedRuleDto neuroRule = buildSampleRule("SAFE-NEURO-001", "SAFETY", true, List.of("safety", "neurological", "đau", "tai", "chảy", "dịch"));
        assertThat(RetrievalService.buildExpandedKeywords(t5Msg, List.of(neuroRule), Map.of("tai", List.of("ráy tai"))))
                .isEmpty();

        // 3. AgentBrainService must never promote unverified candidates or hallucinated complaint scope into confirmed_facts or self_reported_symptoms
        AgentBrainService brain = new AgentBrainService();
        String senderId = "fb-customer-demo-p37a-candidate";
        JsonNode t5Json = objectMapper.readTree("""
                {
                  "conversational_context": {"current_topic": "Góp ý tư vấn"},
                  "customer_understanding": {
                    "turn_intent": "META_COMMUNICATION_REMARK",
                    "secondary_intents": ["PROCESS_FEEDBACK"],
                    "emotion": "FRUSTRATED",
                    "confirmed_facts_delta": [
                      {"key": "need_scope", "value": "tai", "op": "ADD", "status": "CURRENT"}
                    ]
                  },
                  "priority": "SERVICE_RECOVERY",
                  "response_plan": {
                    "strategy": "Active Listening",
                    "tone": "LEVEL_1_NATURAL",
                    "depth": "CONCISE",
                    "cta_level": "NO_CTA",
                    "next_action": "NO_ADDITIONAL_ACTION",
                    "adaptation": "PIVOT"
                  }
                }
                """);
        AdaptiveConversationIntelligence.AdaptiveCognitiveReflection reconciled =
                AdaptiveConversationIntelligence.parseAndReconcileAdaptiveCognitiveReflection(t5Json, t5Msg);
        brain.applyAciTurnMemoryUpdates(
                PAGE_ID,
                senderId,
                "T5",
                t5Msg,
                List.of(new AdaptiveConversationIntelligence.UnverifiedParserCandidate(
                        "SCOPE_OR_AREA_CANDIDATE", "tai", "ScopeMatcher", "Must not auto-promote", false)),
                reconciled,
                ConversationState.StructuredBookingState.none(),
                null,
                false,
                null,
                "Active Listening"
        );
        AdaptiveConversationIntelligence.CustomerUnderstandingDraft2Snapshot snap =
                brain.getCustomerUnderstandingDraft2Snapshot(PAGE_ID, senderId);
        assertThat(snap.consultativeProfile().confirmedFacts()).isEmpty();
        assertThat(snap.consultativeProfile().selfReportedSymptoms()).isEmpty();
    }

    @Test
    @DisplayName("P3.7-A Req 1.3 & 1.4: Kiểm thử đủ 7 tình huống LLM_OUTPUT_UNAVAILABLE trên Context-Safe Fallback + bảo toàn trạng thái đặt lịch & ý định phiên")
    void contextSafeFallbackHandlesAllSevenTimeoutScenariosWithoutBlindSalesActionAndPreservesSessionState() throws Exception {
        List<PackageCatalogService.PackageSummary> catalog = buildSampleCatalog();
        PackageCatalogService.PackageSummary ns01 = catalog.get(0);
        PagePersonaConfig cfg = objectMapper.readValue("""
                {
                  "botName": "Nàng Sen",
                  "selfPronoun": "em",
                  "customerPronoun": "chị",
                  "openingBubbles": ["Dạ chào {{customer_pronoun}}, cảm ơn {{customer_pronoun}} đã nhắn tin cho Nàng Sen ạ."]
                }
                """, PagePersonaConfig.class);
        AdaptiveConversationIntelligence.CustomerUnderstandingDraft2Snapshot activeDraft2 =
                AdaptiveConversationIntelligence.CustomerUnderstandingDraft2Snapshot.initial();

        // 1. Lượt đầu mở lời chung ("Tư vấn giúp chị")
        List<FanpageBotResponse.ReplyBubble> b1Opening = CleanAgentOutputRefereeAndSanitizer.buildContextSafeAciV2Fallback(
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                ConversationState.StructuredBookingState.none(),
                "ACI_COGNITIVE_V2", ns01, catalog, catalog, cfg,
                "Tư vấn giúp chị", "em", "chị", activeDraft2, List.of()
        );
        assertThat(b1Opening).hasSize(1);
        assertThat(b1Opening.get(0).text()).doesNotContain("399k").doesNotContain("129k");
        assertThat(CleanAgentOutputRefereeAndSanitizer.resolveFallbackActualSentSpeechAct(
                "Tư vấn giúp chị", ConversationState.StructuredBookingState.none(),
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(), activeDraft2
        )).isEqualTo("FALLBACK_INTERRUPTION_APOLOGY");

        // 2. Giữa phiên chia sẻ triệu chứng/nhu cầu chưa hỏi giá ("Chị bị đau mỏi vai gáy mấy hôm nay")
        List<FanpageBotResponse.ReplyBubble> b2MidSession = CleanAgentOutputRefereeAndSanitizer.buildContextSafeAciV2Fallback(
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                ConversationState.StructuredBookingState.none(),
                "ACI_COGNITIVE_V2", ns01, catalog, catalog, cfg,
                "Chị bị đau mỏi vai gáy mấy hôm nay", "em", "chị", activeDraft2, List.of()
        );
        assertThat(b2MidSession).hasSize(1);
        assertThat(b2MidSession.get(0).text())
                .containsIgnoringCase("xin lỗi chị")
                .containsIgnoringCase("gián đoạn")
                .doesNotContain("399k")
                .doesNotContain("239k");

        // 3. Khách hỏi giá ("giá" / "có gói nào 100k k")
        List<FanpageBotResponse.ReplyBubble> b3Price = CleanAgentOutputRefereeAndSanitizer.buildContextSafeAciV2Fallback(
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                ConversationState.StructuredBookingState.none(),
                "ACI_COGNITIVE_V2", ns01, catalog, catalog, cfg,
                "giá", "em", "chị", activeDraft2, List.of()
        );
        assertThat(b3Price).hasSize(1);
        assertThat(b3Price.get(0).text()).contains("239k").contains("399k");
        assertThat(CleanAgentOutputRefereeAndSanitizer.resolveFallbackActualSentSpeechAct(
                "giá", ConversationState.StructuredBookingState.none(),
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(), activeDraft2
        )).isEqualTo("FALLBACK_PRICE_INFO");

        // 4. Khách hỏi địa chỉ / vận hành ("Địa chỉ cơ sở Bích Câu ở đâu em?")
        List<FanpageBotResponse.ReplyBubble> b4Address = CleanAgentOutputRefereeAndSanitizer.buildContextSafeAciV2Fallback(
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                ConversationState.StructuredBookingState.none(),
                "ACI_COGNITIVE_V2", ns01, catalog, catalog, cfg,
                "Địa chỉ cơ sở Bích Câu ở đâu em?", "em", "chị", activeDraft2, List.of()
        );
        assertThat(b4Address).hasSize(1);
        assertThat(b4Address.get(0).text())
                .containsIgnoringCase("địa chỉ")
                .doesNotContain("399k")
                .doesNotContain("239k");
        assertThat(CleanAgentOutputRefereeAndSanitizer.resolveFallbackActualSentSpeechAct(
                "Địa chỉ cơ sở Bích Câu ở đâu em?", ConversationState.StructuredBookingState.none(),
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(), activeDraft2
        )).isEqualTo("FALLBACK_OPERATIONAL_INFO");

        // 5. Khách phàn nàn gay gắt (T7: "Dell hiểu sao tự khia mình là AI, KH chạy mất dép. Đã theé nch k có tý cảm xúc, k có chút chiến lược hay nghệ thuật bán hàng nào hết")
        String t7Complaint = "Dell hiểu sao tự khia mình là AI, KH chạy mất dép. Đã theé nch k có tý cảm xúc, k có chút chiến lược hay nghệ thuật bán hàng nào hết";
        List<FanpageBotResponse.ReplyBubble> b5Complaint = CleanAgentOutputRefereeAndSanitizer.buildContextSafeAciV2Fallback(
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                ConversationState.StructuredBookingState.none(),
                "ACI_COGNITIVE_V2", ns01, catalog, catalog, cfg,
                t7Complaint, "em", "chị", activeDraft2, List.of()
        );
        assertThat(b5Complaint).hasSize(1);
        assertThat(b5Complaint.get(0).text())
                .containsIgnoringCase("xin lỗi chị")
                .doesNotContain("399k")
                .doesNotContain("239k")
                .doesNotContain("Massage cơ bản")
                .doesNotContain("?");
        assertThat(CleanAgentOutputRefereeAndSanitizer.resolveFallbackActualSentSpeechAct(
                t7Complaint, ConversationState.StructuredBookingState.none(),
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(), activeDraft2
        )).isEqualTo("FALLBACK_SERVICE_RECOVERY");

        // 6. Khách từ chối / tạm dừng ("Thôi chị không làm đâu, đừng tư vấn thêm nữa")
        String refusalMsg = "Thôi chị không làm đâu, đừng tư vấn thêm nữa";
        List<FanpageBotResponse.ReplyBubble> b6Refusal = CleanAgentOutputRefereeAndSanitizer.buildContextSafeAciV2Fallback(
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                ConversationState.StructuredBookingState.none(),
                "ACI_COGNITIVE_V2", ns01, catalog, catalog, cfg,
                refusalMsg, "em", "chị", activeDraft2, List.of()
        );
        assertThat(b6Refusal).hasSize(1);
        assertThat(b6Refusal.get(0).text())
                .containsIgnoringCase("tôn trọng quyết định của chị")
                .doesNotContain("399k")
                .doesNotContain("?");
        assertThat(CleanAgentOutputRefereeAndSanitizer.resolveFallbackActualSentSpeechAct(
                refusalMsg, ConversationState.StructuredBookingState.none(),
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(), activeDraft2
        )).isEqualTo("FALLBACK_RESPECTED_PAUSE");

        // 7. Khách đặt lịch ("Đặt lịch cho chị gói 399k lúc 18h tối nay nhé")
        ConversationState.StructuredBookingState bookingState = new ConversationState.StructuredBookingState(
                "HN:NS01:399000", "Massage cơ bản", "hôm nay", "2026-11-02", "18:00",
                "Asia/Ho_Chi_Minh", ConversationState.BookingStatus.AWAITING_AVAILABILITY_CHECK, List.of("HN:NS01:399000")
        );
        List<FanpageBotResponse.ReplyBubble> b7Booking = CleanAgentOutputRefereeAndSanitizer.buildContextSafeAciV2Fallback(
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                bookingState,
                "ACI_COGNITIVE_V2", ns01, catalog, catalog, cfg,
                "Đặt lịch cho chị gói 399k lúc 18h tối nay nhé", "em", "chị", activeDraft2, List.of()
        );
        assertThat(b7Booking).hasSize(1);
        assertThat(b7Booking.get(0).text())
                .contains("Massage cơ bản")
                .contains("18:00")
                .containsIgnoringCase("kiểm tra lịch trống");
        assertThat(CleanAgentOutputRefereeAndSanitizer.resolveFallbackActualSentSpeechAct(
                "Đặt lịch cho chị gói 399k lúc 18h tối nay nhé", bookingState,
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(), activeDraft2
        )).isEqualTo("FALLBACK_BOOKING_HOLD");

        // Verify AgentBrainService preserves prior session state on LLM_OUTPUT_UNAVAILABLE
        AgentBrainService brain = new AgentBrainService();
        String senderId = "fb-customer-demo-p37a-preserve";
        JsonNode priorTurnJson = objectMapper.readTree("""
                {
                  "conversational_context": {"current_topic": "Tư vấn Massage cơ bản"},
                  "customer_understanding": {
                    "turn_intent": "SELECT_SCOPE",
                    "conversation_state": "ACTIVE_CONSULTATION",
                    "conversation_goal": "Giảm mỏi toàn thân",
                    "interested_package_id": "HN:NS01:399000",
                    "booking_readiness": "CONSIDERING"
                  },
                  "priority": "VALUE_ALIGNMENT",
                  "response_plan": {
                    "strategy": "Soft Recommendation",
                    "tone": "LEVEL_2_REFLECTIVE",
                    "depth": "STANDARD",
                    "cta_level": "SOFT_CTA",
                    "next_action": "ASK_ONE_CLARIFYING_QUESTION",
                    "adaptation": "CONTINUATION"
                  }
                }
                """);
        brain.applyAciTurnMemoryUpdates(
                PAGE_ID, senderId, "T3", "Chị muốn làm toàn thân", List.of(),
                AdaptiveConversationIntelligence.parseAndReconcileAdaptiveCognitiveReflection(priorTurnJson, "Chị muốn làm toàn thân"),
                ConversationState.StructuredBookingState.none(), null, false, null, "Soft Recommendation"
        );

        // Now simulate T5 LLM timeout (AdaptiveCognitiveReflection.empty(), parsedFromV2Schema=false)
        brain.applyAciTurnMemoryUpdates(
                PAGE_ID, senderId, "T5", t7Complaint, List.of(),
                AdaptiveConversationIntelligence.AdaptiveCognitiveReflection.empty(),
                ConversationState.StructuredBookingState.none(), null, false, null, "FALLBACK_SERVICE_RECOVERY"
        );
        AdaptiveConversationIntelligence.CustomerUnderstandingDraft2Snapshot afterTimeout =
                brain.getCustomerUnderstandingDraft2Snapshot(PAGE_ID, senderId);
        assertThat(afterTimeout.consultativeProfile().turnIntent())
                .as("Timeout fallback must NOT overwrite prior turnIntent with GENERAL_INQUIRY")
                .isEqualTo("SELECT_SCOPE");
        assertThat(afterTimeout.consultativeProfile().conversationGoal())
                .isEqualTo("Giảm mỏi toàn thân");
        assertThat(afterTimeout.businessCommitment().interestedPackageId())
                .isEqualTo("HN:NS01:399000");
        assertThat(afterTimeout.conversationalContext().lastBotAction())
                .isEqualTo("FALLBACK_SERVICE_RECOVERY");
        assertThat(brain.getPreviousStrategyRecord(PAGE_ID, senderId).lastChosenStrategy())
                .as("Timeout must NOT overwrite previousStrategyRecord with a fake model-selected strategy")
                .isEqualTo("Soft Recommendation");
    }

    @Test
    void measureExactSixComponentsOfPromptOn9bsi21Session() throws Exception {
        Path dumpPath = Path.of("/Users/chutiendat/.gemini/antigravity/brain/157eac0f-7e83-4cc8-a4f8-41255390ef53/scratch/p37a_nang_sen_db_dump.json");
        Path chunksPath = Path.of("/Users/chutiendat/.gemini/antigravity/brain/157eac0f-7e83-4cc8-a4f8-41255390ef53/scratch/p37a_nang_sen_chunks.json");
        if (!Files.exists(dumpPath) || !Files.exists(chunksPath)) {
            return;
        }
        JsonNode dbDump = objectMapper.readTree(Files.readString(dumpPath));
        JsonNode chunksArr = objectMapper.readTree(Files.readString(chunksPath));

        JsonNode pageNode = dbDump.get("page");
        PagePersonaConfig personaCfg = objectMapper.treeToValue(pageNode.get("persona_config"), PagePersonaConfig.class);
        PromptBuilder.PagePersona persona = new PromptBuilder.PagePersona(
                pageNode.get("name").asText(),
                pageNode.get("industry").asText(),
                pageNode.get("target_audience").asText(),
                pageNode.get("description").asText(),
                personaCfg
        );

        List<AgentSkillDto> skills = new ArrayList<>();
        for (JsonNode s : dbDump.get("skills")) {
            skills.add(new AgentSkillDto(
                    UUID.fromString(s.get("id").asText()),
                    s.get("code").asText(),
                    s.get("name").asText(),
                    s.get("category").asText(""),
                    s.get("description").asText(""),
                    s.get("trigger_signals").asText(""),
                    s.get("tactical_instructions").asText(""),
                    "",
                    s.get("anti_pattern").asText(""),
                    s.get("priority").asInt(80),
                    s.get("active").asBoolean(true),
                    s.hasNonNull("page_id") ? s.get("page_id").asText() : null,
                    Instant.now(),
                    Instant.now()
            ));
        }

        List<LearnedRuleDto> rules = new ArrayList<>();
        for (JsonNode r : dbDump.get("rules")) {
            List<String> kws = new ArrayList<>();
            if (r.hasNonNull("retrieval_keywords") && r.get("retrieval_keywords").isArray()) {
                for (JsonNode k : r.get("retrieval_keywords")) kws.add(k.asText());
            }
            rules.add(new LearnedRuleDto(
                    UUID.fromString(r.get("id").asText()),
                    r.get("category").asText(""),
                    r.hasNonNull("trigger_condition") ? r.get("trigger_condition").asText() : null,
                    null,
                    r.hasNonNull("generalized_rule") ? r.get("generalized_rule").asText() : null,
                    kws,
                    r.hasNonNull("active") && r.get("active").asBoolean(true),
                    r.hasNonNull("page_id") ? r.get("page_id").asText() : null,
                    r.hasNonNull("confidence_score") ? r.get("confidence_score").asDouble(0.9) : 0.9,
                    0, 0, 0, 1, null, null, null, Instant.now(), Instant.now(),
                    "DOM", null, null,
                    r.hasNonNull("always_on") && r.get("always_on").asBoolean(),
                    "APPROVED", null,
                    r.hasNonNull("rule_code") ? r.get("rule_code").asText() : null,
                    r.hasNonNull("title") ? r.get("title").asText() : null,
                    null,
                    r.hasNonNull("stage") ? r.get("stage").asText() : null,
                    r.hasNonNull("priority") ? r.get("priority").asInt(80) : 80,
                    r.hasNonNull("do_actions") ? r.get("do_actions").asText() : null,
                    r.hasNonNull("dont_actions") ? r.get("dont_actions").asText() : null,
                    "must_use_kb_document_context", "none", "MANUAL"
            ));
        }

        List<ReciprocalRankFusion.FusedChunk> allFused = new ArrayList<>();
        for (JsonNode c : chunksArr) {
            List<String> hp = new ArrayList<>();
            if (c.hasNonNull("heading_path") && c.get("heading_path").isArray()) {
                for (JsonNode h : c.get("heading_path")) hp.add(h.asText());
            }
            RetrievedChunk rc = new RetrievedChunk(
                    UUID.fromString(c.get("id").asText()),
                    UUID.randomUUID(),
                    hp,
                    List.of(),
                    1,
                    new double[0],
                    c.get("text").asText(),
                    0.9
            );
            allFused.add(new ReciprocalRankFusion.FusedChunk(rc, 0.9));
        }

        List<PackageCatalogService.PackageSummary> extracted = PackageCatalogService.filterByRegion(
                PackageCatalogService.extractCatalog(allFused, personaCfg.resolvedScopeTaxonomy()),
                "HN"
        );
        CleanAgentCatalogAndPromptBuilder.TieredCatalogResult tiered =
                CleanAgentCatalogAndPromptBuilder.rankAndTierCatalogForCleanAgent(
                        extracted, List.of(), List.of(), Set.of(), false
                );
        List<PackageCatalogService.PackageSummary> unblindedPrimaries = new ArrayList<>(tiered.primaryRecommendations());
        List<PackageCatalogService.PackageSummary> unblindedEconomicals = new ArrayList<>(tiered.economicalAlternatives());
        Set<String> includedIds = new LinkedHashSet<>();
        for (var p : unblindedPrimaries) includedIds.add(p.productId());
        for (var p : unblindedEconomicals) includedIds.add(p.productId());
        for (var p : extracted) {
            if (!p.isAddon() && includedIds.add(p.productId())) {
                if (p.priceAmountVnd() > 0 && p.priceAmountVnd() < 559500L && !unblindedPrimaries.isEmpty()) {
                    unblindedEconomicals.add(p);
                } else {
                    unblindedPrimaries.add(p);
                }
            }
        }
        List<PackageCatalogService.PackageSummary> promptPrimaries =
                CleanAgentCatalogAndPromptBuilder.deduplicateCatalogByCanonicalSignature(unblindedPrimaries);
        List<PackageCatalogService.PackageSummary> promptEconomicals =
                CleanAgentCatalogAndPromptBuilder.deduplicateCatalogByCanonicalSignature(unblindedEconomicals);

        FanpageBotRequest reqT1 = new FanpageBotRequest(PAGE_ID.toString(), "test", "Customer", "giá", List.of(), List.of(), List.of(), 6, null, null, null, null);
        AdaptiveConversationIntelligence.ThreeStreamTurnInput threeStreamT1 =
                AdaptiveConversationIntelligence.buildThreeStreamInput(reqT1, extracted, personaCfg, ZoneId.of("Asia/Ho_Chi_Minh"));
        List<AgentSkillDto> preSelectedSkillsT1 = CleanAgentOrchestratorService.selectPreLlmTurnRelevantSkills(
                skills, "giá", List.of(), ConversationState.StructuredBookingState.none()
        );
        List<LearnedRuleDto> rulesT1 = CleanAgentCatalogAndPromptBuilder.selectTurnRelevantCompactRules(rules, "giá", true, 5);
        String promptT1After = CleanAgentCatalogAndPromptBuilder.buildAciSinglePassPrompt(
                persona, "chị", null, "chị", threeStreamT1,
                promptPrimaries, promptEconomicals,
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                List.of(), AdaptiveConversationIntelligence.WorkingHypothesisRecord.initialEmpty(),
                List.of(), ConversationState.StructuredBookingState.none(),
                AdaptiveConversationIntelligence.OperationalStateSnapshot.initial(),
                AdaptiveConversationIntelligence.PreviousStrategyRecord.initialNone(),
                List.of(), null, false,
                allFused.subList(0, Math.min(7, allFused.size())),
                List.of(), preSelectedSkillsT1, rulesT1,
                AdaptiveConversationIntelligence.CustomerUnderstandingDraft2Snapshot.initial()
        );
        Files.writeString(Path.of("/Users/chutiendat/.gemini/antigravity/brain/157eac0f-7e83-4cc8-a4f8-41255390ef53/scratch/p37a_reconstructed_prompt_t1_after.txt"), promptT1After);
        assertThat(promptT1After)
                .contains("brand_welcome_line")
                .doesNotContain("SAFE-NEURO-001")
                .doesNotContain("PB-SPA-PRICE-OBJECTION-001")
                .doesNotContain("PB-SPA-EAR-001");
    }

    private static List<PackageCatalogService.PackageSummary> buildSampleCatalog() {
        return List.of(
                new PackageCatalogService.PackageSummary(
                        "HN:NS01:399000", "NS01", "HN", "Massage cơ bản", "MASSAGE",
                        List.of("đầu", "cổ", "vai", "gáy", "lưng", "tay", "chân", "toàn thân"),
                        60, "60 phút", 399_000L, "399k", "Phòng chung",
                        "Full body massage trị liệu cơ bản + Thưởng thức buffet thực dưỡng sau liệu trình",
                        "Đau mỏi cơ bản", "Massage trị liệu full body cơ bản 60 phút", false
                ),
                new PackageCatalogService.PackageSummary(
                        "HN:DS02:239000", "DS02", "HN", "Gói dưỡng sinh cơ bản", "GỘI ĐẦU DƯỠNG SINH",
                        List.of("đầu", "cổ", "vai", "gáy"),
                        60, "60 phút", 239_000L, "239k", "Phòng chung",
                        "Gội đầu cơ bản + massage cổ vai gáy + ăn nhẹ & trái cây theo mùa",
                        "Đau mỏi nhẹ", "Gội đầu thảo dược + massage cổ vai gáy", false
                ),
                new PackageCatalogService.PackageSummary(
                        "HN:DS01:129000", "DS01", "HN", "Lấy ráy tai cơ bản", "GỘI ĐẦU DƯỠNG SINH",
                        List.of("tai"),
                        30, "30 phút", 129_000L, "129k", "Phòng chung",
                        "Lấy ráy tai cơ bản + ăn nhẹ & trái cây theo mùa",
                        "Vệ sinh tai cơ bản", "Lấy ráy tai cơ bản 30 phút", false
                )
        );
    }

    private static LearnedRuleDto buildSampleRule(String code, String category, boolean alwaysOn, List<String> keywords) {
        return new LearnedRuleDto(
                UUID.randomUUID(), category, "Trigger " + code, null,
                "Rule body " + code, keywords, true, PAGE_ID.toString(),
                0.95, 0, 0, 0, 1, null, null, null, Instant.now(), Instant.now(),
                "DOM", null, null, alwaysOn, "APPROVED", null, code, "Title " + code,
                null, "CONSULTATION", 90, "Do " + code, "Dont " + code,
                "must_use_kb_document_context", "none", "MANUAL"
        );
    }
}
