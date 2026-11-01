package com.example.docmind.service.bot;

import com.example.docmind.config.LlmProperties;
import com.example.docmind.dto.PagePersonaConfig;
import com.example.docmind.dto.bot.AgentSkillDto;
import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.dto.bot.FanpageBotResponse;
import com.example.docmind.service.retrieval.PromptBuilder;
import com.example.docmind.service.llm.OpenAiCompatibleLlmClient;
import com.example.docmind.service.llm.ReasoningBudget;
import com.example.docmind.service.reasoning.QueryComplexity;
import com.example.docmind.service.security.PromptSecurityGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class P36ConversationIntelligenceVerificationTest {

    private static final UUID PAGE_ID = UUID.fromString("b23cd382-4f7a-4829-8c54-1d410da14bf8");
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final tools.jackson.databind.ObjectMapper toolsObjectMapper = new tools.jackson.databind.ObjectMapper();

    @BeforeEach
    void setUp() {
        SentBubbleLedger.useStore(new SentBubbleLedger.InMemoryStore());
        SentBubbleLedger.clear();
        SenderEpochRegistry.clearAll();
        ConversationState.clearSessionCache();
    }

    @Test
    @DisplayName("P0: OpenAiCompatibleLlmClient gửi reasoning_effort='none' cho FAST_NO_THINK trên Qwen gateway (api.x-apikey.com) nhưng vẫn ẩn trên DashScope chính thức")
    void openAiCompatibleClientControlsReasoningEffortOnQwenGatewayWithoutBreakingOfficialDashScope() {
        LlmProperties gatewayProps = new LlmProperties();
        gatewayProps.setProvider("openai_compatible");
        gatewayProps.setBaseUrl("https://api.x-apikey.com/v1");
        gatewayProps.setModel("qwen3.8-flash");
        gatewayProps.setApiKey("sk-test-gateway");

        RestClient.Builder gwBuilder = RestClient.builder();
        MockRestServiceServer gwServer = MockRestServiceServer.bindTo(gwBuilder).build();
        OpenAiCompatibleLlmClient gwClient = new OpenAiCompatibleLlmClient(gatewayProps, toolsObjectMapper, gwBuilder.build());

        ReasoningBudget fastBudget = new ReasoningBudget(
                QueryComplexity.SIMPLE,
                "none",
                45,
                300,
                5000,
                2500,
                0.2,
                0.9,
                1.08,
                ThinkingMode.FAST_NO_THINK
        );

        gwServer.expect(requestTo("https://api.x-apikey.com/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.enable_thinking").value(false))
                .andExpect(jsonPath("$.reasoning_effort").value("none"))
                .andExpect(jsonPath("$.max_tokens").value(2500))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"bubbles\\\":[\\\"Dạ chào anh ạ.\\\"]}\"}}],\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":220,\"completion_tokens_details\":{\"reasoning_tokens\":140}}}",
                        MediaType.APPLICATION_JSON
                ));

        String gwFastReply = gwClient.complete("Giá", fastBudget);
        assertThat(gwFastReply).contains("Dạ chào anh ạ.");
        assertThat(gwClient.getLastCallTelemetry().reasoningTokens()).isEqualTo(140);
        gwServer.verify();
        gwServer.reset();

        // OBJECTION_REFRAME_THINK on Qwen non-DashScope gateway also caps reasoning_effort='none'
        ReasoningBudget objectionBudget = new ReasoningBudget(
                QueryComplexity.DEEP_REASONING,
                "low",
                60,
                500,
                13000,
                3200,
                0.2,
                0.9,
                1.08,
                ThinkingMode.OBJECTION_REFRAME_THINK
        );
        gwServer.expect(requestTo("https://api.x-apikey.com/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.enable_thinking").value(false))
                .andExpect(jsonPath("$.reasoning_effort").value("none"))
                .andExpect(jsonPath("$.max_tokens").value(3200))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"bubbles\\\":[\\\"Dạ gói 399k làm trọn 60 phút toàn thân ạ.\\\"]}\"}}],\"usage\":{\"prompt_tokens\":1200,\"completion_tokens\":380,\"completion_tokens_details\":{\"reasoning_tokens\":260}}}",
                        MediaType.APPLICATION_JSON
                ));
        assertThat(gwClient.complete("đắt nhỉ", objectionBudget)).contains("399k");
        gwServer.verify();

        // Official DashScope endpoint continues omitting reasoning_effort
        LlmProperties dashScopeProps = new LlmProperties();
        dashScopeProps.setProvider("qwen");
        dashScopeProps.setBaseUrl("https://dashscope-intl.aliyuncs.com/compatible-mode/v1");
        dashScopeProps.setModel("qwen3.8-flash");
        dashScopeProps.setApiKey("sk-dashscope");

        RestClient.Builder dsBuilder = RestClient.builder();
        MockRestServiceServer dsServer = MockRestServiceServer.bindTo(dsBuilder).build();
        OpenAiCompatibleLlmClient dsClient = new OpenAiCompatibleLlmClient(dashScopeProps, toolsObjectMapper, dsBuilder.build());

        dsServer.expect(requestTo("https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.enable_thinking").value(false))
                .andExpect(jsonPath("$.reasoning_effort").doesNotExist())
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"bubbles\\\":[\\\"Dạ chào mình ạ.\\\"]}\"}}]}",
                        MediaType.APPLICATION_JSON
                ));

        assertThat(dsClient.complete("Giá", fastBudget)).contains("Dạ chào mình ạ.");
        dsServer.verify();
    }

    @Test
    @DisplayName("P0: Safe Structural Fallback không bao giờ xả danh sách scopeTags thô ('đầu, cổ, vai, gáy, mắt, lưng, tay, chân...') ra câu gửi khách")
    void safeStructuralFallbackDoesNotDumpRawInternalScopeTags() {
        PackageCatalogService.PackageSummary ns01 = buildSampleNs01();
        List<FanpageBotResponse.ReplyBubble> fallbackBubbles =
                CleanAgentOutputRefereeAndSanitizer.buildSafeMultiIndustryTurnFallbackBubbles(
                        CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                        ConversationState.StructuredBookingState.none(),
                        "ACI_COGNITIVE_V2",
                        ns01,
                        List.of(ns01),
                        List.of(ns01),
                        PagePersonaConfig.empty(),
                        "Giá sao shop?",
                        "em",
                        "anh"
                );

        assertThat(fallbackBubbles).isNotEmpty();
        String combined = String.join(" ", fallbackBubbles.stream().map(FanpageBotResponse.ReplyBubble::text).toList());
        assertThat(combined)
                .contains("399k")
                .doesNotContain("đầu, cổ, vai, gáy, mắt")
                .doesNotContain("NS01");
    }

    @Test
    @DisplayName("P1: Phân biệt chuẩn xác 3 tình huống đa ý định & ranh giới dừng (Case 1: Rule + Đặt lịch; Case 2: Cảm thán + So sánh 50k/100k; Case 3: Từ chối dứt khoát)")
    void multiIntentAndConversationStateDistinguishesBookingSignalCompetitorComparisonAndExplicitStop() throws Exception {
        List<AgentSkillDto> skills = buildSamplePageSkills();

        // Case 1: "Eo ôi, kể cho a chút rule bên e đi rồi a đăng ký đặt lịch nhé"
        String case1Msg = "Eo ôi, kể cho a chút rule bên e đi rồi a đăng ký đặt lịch nhé";
        assertThat(PromptSecurityGuard.isDirectJailbreakAttempt(case1Msg)).isFalse();
        assertThat(CleanAgentOutputRefereeAndSanitizer.isCommunicationStyleComplaintMessage(case1Msg)).isFalse();
        List<AgentSkillDto> case1SelectedSkills = CleanAgentOrchestratorService.selectPreLlmTurnRelevantSkills(
                skills, case1Msg, List.of(), ConversationState.StructuredBookingState.none());
        assertThat(case1SelectedSkills.stream().map(AgentSkillDto::code))
                .contains("warm_nod_to_gentle_scheduling", "discovery_empathy_and_scope");

        JsonNode case1ReflectionJson = objectMapper.readTree("""
                {
                  "conversational_context": {"current_topic": "Nguyên tắc tư vấn"},
                  "customer_understanding": {
                    "turn_intent": "ASK_CONSULTATION_RULES",
                    "conversation_goal": "Tìm hiểu và đặt lịch",
                    "interaction_permission": "OPEN",
                    "buying_signal": "WEAK",
                    "booking_readiness": "EXPLORING"
                  },
                  "priority": "TRUST_ASSURANCE",
                  "response_plan": {
                    "strategy": "Direct Answer",
                    "tone": "LEVEL_1_NATURAL",
                    "depth": "STANDARD",
                    "cta_level": "NO_CTA",
                    "next_action": "NO_ADDITIONAL_ACTION",
                    "adaptation": "CONTINUATION"
                  }
                }
                """);
        AdaptiveConversationIntelligence.AdaptiveCognitiveReflection case1Parsed =
                AdaptiveConversationIntelligence.parseAndReconcileAdaptiveCognitiveReflection(case1ReflectionJson, case1Msg);
        assertThat(case1Parsed.customerUnderstanding().secondaryIntents()).contains("BOOKING_SIGNAL");
        assertThat(case1Parsed.customerUnderstanding().conversationState()).isEqualTo("BOOKING_READY");
        assertThat(case1Parsed.customerUnderstanding().bookingReadiness())
                .isEqualTo(AdaptiveConversationIntelligence.BookingReadiness.READY_TO_BOOK);

        // Case 2: "thôi chịu đấy, ở ngoài kia gội đầu có 50k thêm massage cũng 100k chứ mấy"
        String case2Msg = "thôi chịu đấy, ở ngoài kia gội đầu có 50k thêm massage cũng 100k chứ mấy";
        assertThat(CleanAgentOrchestratorService.isExplicitConversationRefusalStopMessage(case2Msg)).isFalse();
        assertThat(CleanAgentOrchestratorService.isConfirmedCustomerPriceObjection(case2Msg, true)).isTrue();
        List<AgentSkillDto> case2SelectedSkills = CleanAgentOrchestratorService.selectPreLlmTurnRelevantSkills(
                skills, case2Msg, List.of(), ConversationState.StructuredBookingState.none());
        assertThat(case2SelectedSkills.stream().map(AgentSkillDto::code))
                .contains("price_objection_sensory_value")
                .doesNotContain("graceful_pause_keep_warmth");

        JsonNode case2PrematureCloseJson = objectMapper.readTree("""
                {
                  "conversational_context": {"current_topic": "So sánh giá ngoài"},
                  "customer_understanding": {
                    "turn_intent": "POLITE_DEFERRAL",
                    "conversation_goal": "Massage toàn thân",
                    "interaction_permission": "PAUSED",
                    "buying_signal": "NONE",
                    "cost_concern_expressed": false,
                    "booking_readiness": "EXPLORING"
                  },
                  "priority": "POLITE_CLOSURE",
                  "response_plan": {
                    "strategy": "Respectful Pause",
                    "tone": "LEVEL_1_NATURAL",
                    "depth": "CONCISE",
                    "cta_level": "NO_CTA",
                    "next_action": "NO_ADDITIONAL_ACTION",
                    "adaptation": "STOP"
                  }
                }
                """);
        AdaptiveConversationIntelligence.AdaptiveCognitiveReflection case2Parsed =
                AdaptiveConversationIntelligence.parseAndReconcileAdaptiveCognitiveReflection(case2PrematureCloseJson, case2Msg);
        assertThat(case2Parsed.customerUnderstanding().conversationState()).isEqualTo("OBJECTION_NEGOTIATION");
        assertThat(case2Parsed.customerUnderstanding().secondaryIntents()).contains("COMPETITOR_COMPARISON");
        assertThat(case2Parsed.customerUnderstanding().costConcernExpressed()).isTrue();
        assertThat(case2Parsed.priority().primary()).isEqualTo("VALUE_ALIGNMENT");
        assertThat(case2Parsed.responsePlan().strategy()).isEqualTo("Explain Value");

        // Case 3: "Thôi anh không làm đâu, đừng tư vấn thêm nữa" (Explicit stop refusal, NOT system opt-out)
        String case3Msg = "Thôi anh không làm đâu, đừng tư vấn thêm nữa";
        assertThat(PrimaryActionRouter.isStrictSystemOptOutMessage(case3Msg)).isFalse();
        assertThat(CleanAgentOrchestratorService.isExplicitConversationRefusalStopMessage(case3Msg)).isTrue();
        assertThat(CleanAgentOrchestratorService.isConfirmedCustomerPriceObjection(case3Msg, true)).isFalse();
        List<AgentSkillDto> case3SelectedSkills = CleanAgentOrchestratorService.selectPreLlmTurnRelevantSkills(
                skills, case3Msg, List.of(), ConversationState.StructuredBookingState.none());
        assertThat(case3SelectedSkills.stream().map(AgentSkillDto::code))
                .containsExactly("graceful_pause_keep_warmth");

        JsonNode case3OverEagerJson = objectMapper.readTree("""
                {
                  "conversational_context": {"current_topic": "Từ chối dịch vụ"},
                  "customer_understanding": {
                    "turn_intent": "PRICE_OBJECTION",
                    "conversation_goal": "Massage toàn thân",
                    "interaction_permission": "OPEN",
                    "buying_signal": "WEAK"
                  },
                  "priority": "BUDGET_FIT",
                  "response_plan": {
                    "strategy": "Soft Recommendation",
                    "tone": "LEVEL_1_NATURAL",
                    "depth": "STANDARD",
                    "cta_level": "SOFT_CTA",
                    "next_action": "ASK_ONE_CLARIFYING_QUESTION",
                    "adaptation": "PIVOT"
                  }
                }
                """);
        AdaptiveConversationIntelligence.AdaptiveCognitiveReflection case3Parsed =
                AdaptiveConversationIntelligence.parseAndReconcileAdaptiveCognitiveReflection(case3OverEagerJson, case3Msg);
        assertThat(case3Parsed.customerUnderstanding().detectedIntent()).isEqualTo("EXPLICIT_REFUSAL");
        assertThat(case3Parsed.customerUnderstanding().conversationState()).isEqualTo("PAUSED_BY_CUSTOMER");
        assertThat(case3Parsed.priority().primary()).isEqualTo("POLITE_CLOSURE");
        assertThat(case3Parsed.responsePlan().strategy()).isEqualTo("Respectful Pause");
        assertThat(case3Parsed.responsePlan().ctaLevel()).isEqualTo(AdaptiveConversationIntelligence.CtaLevel.NO_CTA);
        assertThat(case3Parsed.strategy().adaptation()).isEqualTo(AdaptiveConversationIntelligence.StrategyAdaptationMode.STOP);

        // Guard against false positive when customer declines VIP package to pick standard package
        assertThat(CleanAgentOrchestratorService.isExplicitConversationRefusalStopMessage(
                "Thôi anh không làm gói 580k đâu, đặt gói 399k cho anh nhé")).isFalse();
    }

    @Test
    @DisplayName("P2: Giữ trọn vẹn đa triệu chứng đồng thời ('đau mỏi chân tay' + 'buốt vai'), đóng unresolved_request mở lời chung và bắt ngay đại từ 'Anh' cùng lượt")
    void contextContinuityRetainsAllCoordinatedSymptomsClosesGenericUnresolvedAndCapturesSameTurnPronoun() throws Exception {
        assertThat(AdaptiveConversationIntelligence.inferExplicitCustomerSelfPronounFromUtterance(
                "Nếu anh tức giận e sẽ nói j")).isEqualTo("Anh");
        assertThat(AdaptiveConversationIntelligence.inferExplicitCustomerSelfPronounFromUtterance(
                "Anh trai tôi bị đau lưng")).isNull();

        AgentBrainService brain = new AgentBrainService();
        String senderId = "fb-customer-demo-p36-unit";

        // Turn 1: "tư vấn" -> records generic unresolved request "tư vấn chung"
        JsonNode t1Json = objectMapper.readTree("""
                {
                  "conversational_context": {
                    "current_topic": "Tư vấn dịch vụ",
                    "unresolved_requests": [{"request": "tư vấn chung", "status": "OPEN"}]
                  },
                  "customer_understanding": {
                    "turn_intent": "GENERAL_CONSULTATION",
                    "conversation_goal": "Tìm hiểu dịch vụ",
                    "interaction_permission": "OPEN",
                    "buying_signal": "WEAK"
                  },
                  "priority": "CLARIFICATION",
                  "response_plan": {
                    "strategy": "Clarify Gently",
                    "tone": "LEVEL_1_NATURAL",
                    "depth": "CONCISE",
                    "cta_level": "SOFT_CTA",
                    "next_action": "ASK_ONE_CLARIFYING_QUESTION",
                    "adaptation": "CONTINUATION"
                  }
                }
                """);
        brain.applyAciTurnMemoryUpdates(
                PAGE_ID,
                senderId,
                "T1",
                "tư vấn",
                List.of(),
                AdaptiveConversationIntelligence.parseAndReconcileAdaptiveCognitiveReflection(t1Json, "tư vấn"),
                ConversationState.StructuredBookingState.none(),
                null,
                false,
                null,
                "GENERAL_CONSULTATION"
        );

        // Turn 3: "Tôi bị đau mỏi chân tay và buốt vai" where LLM delta only emitted the first symptom
        String t3Msg = "Tôi bị đau mỏi chân tay và buốt vai";
        AdaptiveConversationIntelligence.ThreeStreamTurnInput t3Stream =
                AdaptiveConversationIntelligence.buildThreeStreamInput(
                        new FanpageBotRequest(senderId, "Khách", t3Msg, null, List.of(), List.of(), List.of(), 8),
                        List.of(buildSampleNs01()),
                        PagePersonaConfig.empty(),
                        java.time.ZoneId.of("Asia/Ho_Chi_Minh")
                );
        JsonNode t3Json = objectMapper.readTree("""
                {
                  "conversational_context": {
                    "current_topic": "Đau mỏi vai tay chân"
                  },
                  "customer_understanding": {
                    "turn_intent": "SHARE_SYMPTOM",
                    "conversation_goal": "Giảm đau mỏi cơ thể",
                    "interaction_permission": "OPEN",
                    "buying_signal": "MODERATE",
                    "confirmed_facts_delta": [
                      {"key": "self_reported_symptom", "value": "đau mỏi chân tay", "op": "ADD", "status": "CURRENT"}
                    ]
                  },
                  "priority": "CLARIFICATION",
                  "response_plan": {
                    "strategy": "Clarify Gently",
                    "tone": "LEVEL_2_REFLECTIVE",
                    "depth": "STANDARD",
                    "cta_level": "SOFT_CTA",
                    "next_action": "ASK_ONE_CLARIFYING_QUESTION",
                    "adaptation": "REFINEMENT"
                  }
                }
                """);
        brain.applyAciTurnMemoryUpdates(
                PAGE_ID,
                senderId,
                "T3",
                t3Msg,
                t3Stream.unverifiedParserCandidates(),
                AdaptiveConversationIntelligence.parseAndReconcileAdaptiveCognitiveReflection(t3Json, t3Msg),
                ConversationState.StructuredBookingState.none(),
                null,
                false,
                null,
                "GENERAL_CONSULTATION"
        );

        AdaptiveConversationIntelligence.CustomerUnderstandingDraft2Snapshot snap =
                brain.getCustomerUnderstandingDraft2Snapshot(PAGE_ID, senderId);
        List<String> symptomValues = snap.consultativeProfile().selfReportedSymptoms().stream()
                .map(f -> f.value().toLowerCase())
                .toList();
        assertThat(symptomValues)
                .anyMatch(v -> v.contains("chân tay"))
                .anyMatch(v -> v.contains("buốt vai"));
        assertThat(snap.conversationalContext().unresolvedRequests())
                .as("Stale T1 generic 'tư vấn chung' placeholder must be closed (ANSWERED) once specific symptoms are shared")
                .noneMatch(r -> r.status() == AdaptiveConversationIntelligence.UnresolvedRequestStatus.OPEN)
                .anyMatch(r -> r.status() == AdaptiveConversationIntelligence.UnresolvedRequestStatus.ANSWERED);
    }

    @Test
    @DisplayName("P3-P5: Prompt gắn nhãn service_group + ZERO_SCOPE_DRIFT, chuẩn hóa DS01 Lấy ráy tai, và Referee PASS 0 lỗi với tư vấn riêng tư 3 bước")
    void catalogPromptEnforcesServiceGroupZeroScopeDriftAndRefereeAllowsThreeStepPrivacyReply() {
        PackageCatalogService.PackageSummary ns01 = buildSampleNs01();
        PackageCatalogService.PackageSummary ns03 = buildSampleNs03();
        PackageCatalogService.PackageSummary ds02 = buildSampleDs02();
        PackageCatalogService.PackageSummary ds01 = buildSampleDs01();

        PromptBuilder.PagePersona persona = new PromptBuilder.PagePersona(
                "Nàng Sen",
                "BEAUTY_SPA",
                "Spa dưỡng sinh trị liệu",
                null,
                PagePersonaConfig.empty()
        );
        AdaptiveConversationIntelligence.ThreeStreamTurnInput input =
                AdaptiveConversationIntelligence.buildThreeStreamInput(
                        new FanpageBotRequest("fb-test", "Khách", "đắt nhỉ", null, List.of(), List.of(), List.of(), 8),
                        List.of(ns01, ns03, ds02, ds01),
                        PagePersonaConfig.empty(),
                        java.time.ZoneId.of("Asia/Ho_Chi_Minh")
                );

        String prompt = CleanAgentCatalogAndPromptBuilder.buildAciSinglePassPrompt(
                persona,
                "mình",
                "Anh",
                "Anh",
                input,
                List.of(ns01, ns03),
                List.of(ds02, ds01),
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                List.of(),
                AdaptiveConversationIntelligence.WorkingHypothesisRecord.initialEmpty(),
                List.of(),
                ConversationState.StructuredBookingState.none(),
                AdaptiveConversationIntelligence.OperationalStateSnapshot.initial(),
                AdaptiveConversationIntelligence.PreviousStrategyRecord.initialNone(),
                List.of(),
                null,
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                AdaptiveConversationIntelligence.CustomerUnderstandingDraft2Snapshot.initial()
        );

        assertThat(prompt)
                .contains("ZERO_SCOPE_DRIFT")
                .contains("service_group=\"MASSAGE_BODY_TOAN_THAN\"")
                .contains("service_group=\"GOI_DAU_DUONG_SINH\"")
                .contains("service_group=\"LAY_RAY_TAI_CO_BAN\"")
                .contains("Lấy ráy tai cơ bản 30p")
                .doesNotContain("Gội dưỡng sinh nhanh 30p");

        // Verify 3-step privacy response passes Referee with 0 violations
        List<FanpageBotResponse.ReplyBubble> privacyBubbles = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Dạ ở gói Massage cơ bản 399k tuy là phòng tiêu chuẩn nhưng không gian rất yên tĩnh và mỗi giường đều có rèm che riêng kín đáo từ đầu đến cuối buổi nên không sợ bất tiện đâu anh ạ.",
                        0
                ),
                new FanpageBotResponse.ReplyBubble(
                        "Không biết mức độ riêng tư có rèm che riêng từng giường như vậy đã đủ thoải mái với anh chưa ạ? Còn trường hợp anh thực sự muốn phòng đóng cửa khép kín hoàn toàn thì bên em có gói Massage phục hồi 100 phút giá 580k có Phòng VIP riêng để anh tham khảo thêm nhé ạ.",
                        2000
                )
        );
        List<String> violations = CleanAgentOutputRefereeAndSanitizer.checkAciV2TruthAuthorityAndSafety(
                privacyBubbles,
                List.of(ns01, ns03, ds02, ds01),
                List.of(ns01, ns03, ds02, ds01),
                List.of(),
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                ConversationState.StructuredBookingState.none(),
                List.of(ns01),
                PagePersonaConfig.empty(),
                "khiếp chung phòng á ngại chết",
                false,
                null,
                null,
                AdaptiveConversationIntelligence.AdaptiveCognitiveReflection.empty(),
                List.of(),
                "Nàng Sen",
                "",
                List.of()
        );
        assertThat(violations).isEmpty();
    }

    private static PackageCatalogService.PackageSummary buildSampleNs01() {
        return new PackageCatalogService.PackageSummary(
                "HN:NS01:399000",
                "NS01",
                "HN",
                "Massage cơ bản",
                "MASSAGE",
                List.of("đầu", "cổ", "vai", "gáy", "mắt", "lưng", "tay", "chân", "toàn thân"),
                60,
                "60 phút",
                399_000L,
                "399k",
                "Phòng chung",
                "Full body massage trị liệu cơ bản + Thưởng thức buffet thực dưỡng sau liệu trình",
                "Đau mỏi cơ bản",
                "Massage trị liệu full body cơ bản 60 phút",
                false
        );
    }

    private static PackageCatalogService.PackageSummary buildSampleNs03() {
        return new PackageCatalogService.PackageSummary(
                "HN:NS03:580000",
                "NS03",
                "HN",
                "Massage phục hồi",
                "MASSAGE",
                List.of("vai", "gáy", "lưng", "toàn thân"),
                100,
                "100 phút",
                580_000L,
                "580k",
                "Phòng VIP riêng",
                "10p ngâm bồn thảo dược + 5p xông hơi + 85p massage phục hồi + Buffet thực dưỡng cao cấp",
                "Đau mỏi vai gáy, stress nặng",
                "Ngâm bồn thảo dược 10 phút, xông hơi 5 phút, massage phục hồi 85 phút",
                false
        );
    }

    private static PackageCatalogService.PackageSummary buildSampleNs04() {
        return new PackageCatalogService.PackageSummary(
                "HN:NS04:670000",
                "NS04",
                "HN",
                "Massage phục hồi + dưỡng sinh",
                "MASSAGE",
                List.of("đầu", "cổ", "vai", "gáy", "lưng", "toàn thân"),
                120,
                "120 phút",
                670_000L,
                "670k",
                "Phòng VIP riêng khép kín",
                "10p ngâm bồn + 5p xông hơi + 75p massage + 30p gội đầu dưỡng sinh + Buffet thực dưỡng",
                "Mất ngủ nhẹ, điều trị chuyên sâu",
                "Ngâm bồn 10 phút, xông hơi 5 phút, massage 75 phút, gội đầu dưỡng sinh 30 phút",
                false
        );
    }

    @Test
    @DisplayName("P2/P1 Persistence Round-Trip: AciCustomerBrainMemorySnapshot.monotonicMerge giữ nguyên đa triệu chứng nguyên tử + secondary_intents + conversation_state")
    void snapshotMonotonicMergePreservesCoReportedAtomicSymptomsAndSecondaryIntents() throws Exception {
        AgentBrainService brain = new AgentBrainService();
        String senderId = "fb-customer-demo-p36-merge";
        String msg = "Tôi bị đau mỏi chân tay và buốt vai";
        JsonNode json = objectMapper.readTree("""
                {
                  "conversational_context": {"current_topic": "Đau mỏi chân tay và buốt vai"},
                  "customer_understanding": {
                    "turn_intent": "SHARE_SYMPTOM",
                    "secondary_intents": ["BOOKING_SIGNAL"],
                    "conversation_state": "BOOKING_READY",
                    "conversation_goal": "Chăm sóc cơ thể",
                    "interaction_permission": "OPEN",
                    "buying_signal": "STRONG",
                    "booking_readiness": "READY_TO_BOOK",
                    "confirmed_facts_delta": [
                      {"key": "self_reported_symptom", "value": "đau mỏi chân tay", "op": "ADD", "status": "CURRENT"}
                    ]
                  },
                  "priority": "CLARIFICATION",
                  "response_plan": {
                    "strategy": "Active Listening",
                    "tone": "LEVEL_2_REFLECTIVE",
                    "depth": "STANDARD",
                    "cta_level": "SOFT_CTA",
                    "next_action": "ASK_ONE_CLARIFYING_QUESTION",
                    "adaptation": "CONTINUATION"
                  }
                }
                """);
        brain.applyAciTurnMemoryUpdates(
                PAGE_ID,
                senderId,
                "T3",
                msg,
                List.of(),
                AdaptiveConversationIntelligence.parseAndReconcileAdaptiveCognitiveReflection(json, msg),
                ConversationState.StructuredBookingState.none(),
                null,
                false,
                null,
                "Active Listening"
        );

        // Simulate DB JSON round-trip + monotonicMerge against an empty T1 snapshot
        AgentBrainService.CustomerBrainMemory emptyT1 = new AgentBrainService.CustomerBrainMemory();
        AciCustomerBrainMemorySnapshot t1Snap = emptyT1.toPersistedSnapshot();
        AdaptiveConversationIntelligence.CustomerUnderstandingDraft2Snapshot inMem =
                brain.getCustomerUnderstandingDraft2Snapshot(PAGE_ID, senderId);
        assertThat(inMem.consultativeProfile().selfReportedSymptoms()).hasSize(2);

        List<AciCustomerBrainMemorySnapshot.PersistedVersionedFact> persistedSymptoms =
                inMem.consultativeProfile().selfReportedSymptoms().stream()
                        .map(AciCustomerBrainMemorySnapshot.PersistedVersionedFact::fromDomain)
                        .toList();
        List<AciCustomerBrainMemorySnapshot.PersistedVersionedFact> mergedSymptoms =
                AciCustomerBrainMemorySnapshot.mergeVersionedFactsByKeyMonotonic(
                        t1Snap.selfReportedSymptoms(),
                        persistedSymptoms
                );
        assertThat(mergedSymptoms)
                .extracting(AciCustomerBrainMemorySnapshot.PersistedVersionedFact::value)
                .anyMatch(v -> v.contains("chân tay"))
                .anyMatch(v -> v.contains("buốt vai"));

        // Updating "buốt vai" to RESOLVED on T5 must NOT overwrite "đau mỏi chân tay"
        List<AciCustomerBrainMemorySnapshot.PersistedVersionedFact> t5Resolved = List.of(
                new AciCustomerBrainMemorySnapshot.PersistedVersionedFact(
                        "self_reported_symptom", "buốt vai", "RESOLVED", "CUSTOMER_MESSAGE",
                        "T3", "T5", Instant.now().toString(), "RESOLVED", "HIGH"
                )
        );
        List<AciCustomerBrainMemorySnapshot.PersistedVersionedFact> afterT5 =
                AciCustomerBrainMemorySnapshot.mergeVersionedFactsByKeyMonotonic(mergedSymptoms, t5Resolved);
        assertThat(afterT5).hasSize(2);
        assertThat(afterT5).anySatisfy(f -> {
            assertThat(f.value()).contains("chân tay");
            assertThat(f.validity()).isEqualTo("CURRENT");
        });
        assertThat(afterT5).anySatisfy(f -> {
            assertThat(f.value()).contains("buốt vai");
            assertThat(f.validity()).isEqualTo("RESOLVED");
        });
    }

    @Test
    @DisplayName("P5 Referee: Phản hồi riêng tư 3 bước đối chiếu rèm riêng gói 399k với gói 580k Phòng VIP riêng (kể cả 'khép kín') không bị phạt nhầm, nhưng chặn gán VIP cho gói 399k")
    void threeStepPrivacyConsultationAllowsVipRoomReferenceOnNs03WhileBlockingVipClaimOnNs01() {
        PackageCatalogService.PackageSummary ns01 = buildSampleNs01();
        PackageCatalogService.PackageSummary ns03 = buildSampleNs03();
        PackageCatalogService.PackageSummary ns04 = buildSampleNs04();
        List<PackageCatalogService.PackageSummary> catalog = List.of(ns01, ns03, ns04);

        List<FanpageBotResponse.ReplyBubble> validThreeStepPrivacyBubbles = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Dạ ở gói Massage cơ bản 60 phút giá 399k, phòng tiêu chuẩn bên em thiết kế trầm yên tĩnh, kiểm soát lượng khách và mỗi giường đều có rèm che riêng kín đáo ạ.",
                        0
                ),
                new FanpageBotResponse.ReplyBubble(
                        "Mình thấy không gian có rèm che riêng từng giường như vậy đã đủ thoải mái chưa ạ? Nếu mình thực sự muốn phòng đóng cửa riêng thì bên em có gói Massage phục hồi 100 phút giá 580k có Phòng VIP riêng khép kín để mình tham khảo thêm nhé.",
                        2000
                )
        );
        List<String> validViolations = CleanAgentOutputRefereeAndSanitizer.checkAciV2TruthAuthorityAndSafety(
                validThreeStepPrivacyBubbles,
                catalog,
                catalog,
                List.of(),
                null,
                ConversationState.StructuredBookingState.none(),
                List.of(ns01),
                PagePersonaConfig.empty(),
                "khiếp chung phòng á ngại chết",
                false,
                null,
                null,
                AdaptiveConversationIntelligence.AdaptiveCognitiveReflection.empty()
        );
        assertThat(validViolations)
                .as("Valid 3-step privacy response mentioning 399k curtains + optional 580k VIP room must NOT trigger CROSS_TIER_EXCLUSIVE_ATTRIBUTE_MISMATCH")
                .isEmpty();

        List<FanpageBotResponse.ReplyBubble> falseVipOn399kBubbles = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Dạ mình yên tâm nhé, gói Massage cơ bản 60 phút giá 399k bên em được làm trong Phòng VIP riêng khép kín hoàn toàn ạ.",
                        0
                )
        );
        List<String> falseVipViolations = CleanAgentOutputRefereeAndSanitizer.checkAciV2TruthAuthorityAndSafety(
                falseVipOn399kBubbles,
                catalog,
                catalog,
                List.of(),
                null,
                ConversationState.StructuredBookingState.none(),
                List.of(ns01),
                PagePersonaConfig.empty(),
                "khiếp chung phòng á ngại chết",
                false,
                null,
                null,
                AdaptiveConversationIntelligence.AdaptiveCognitiveReflection.empty()
        );
        assertThat(falseVipViolations)
                .anyMatch(v -> v.startsWith("UNGROUNDED_PRIVATE_ROOM:NS01")
                        || v.startsWith("CROSS_TIER_EXCLUSIVE_ATTRIBUTE_MISMATCH:NS01"));
    }

    @Test
    @DisplayName("P5 Truth Grounding: Referee chặn bịa 'giá đã gồm tip' hoặc 'không nhận tip' trái với 04_chính_sách ('Tiền tip chưa bao gồm trong giá dịch vụ và hoàn toàn tùy tâm')")
    void refereeBlocksUngroundedTipInclusionOrNoTipClaimsWhileAllowingGroundedPolicyStatement() {
        PackageCatalogService.PackageSummary ns01 = buildSampleNs01();
        List<PackageCatalogService.PackageSummary> catalog = List.of(ns01);
        com.example.docmind.service.retrieval.ReciprocalRankFusion.FusedChunk tipPolicyChunk =
                new com.example.docmind.service.retrieval.ReciprocalRankFusion.FusedChunk(
                        new com.example.docmind.repository.RetrievedChunk(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                List.of("CHÍNH SÁCH"),
                                List.of(),
                                1,
                                new double[]{0, 0, 10, 10},
                                "| Quy định về tip | Tiền tip chưa bao gồm trong giá dịch vụ và hoàn toàn tùy tâm |",
                                0.95
                        ),
                        0.95
                );

        // 1. Hallucinated "giá đã gồm tip" without tip chunk retrieved -> BLOCKED
        List<String> hallucinatedIncludedNoChunk = CleanAgentOutputRefereeAndSanitizer.checkAciV2TruthAuthorityAndSafety(
                List.of(new FanpageBotResponse.ReplyBubble(
                        "Dạ gói Massage cơ bản 60 phút giá 399k đã gồm tip, không phát sinh phụ phí ạ.", 0)),
                catalog, catalog, List.of(),
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                ConversationState.StructuredBookingState.none(),
                List.of(ns01), PagePersonaConfig.empty(),
                "đắt nhỉ", false, null, null,
                AdaptiveConversationIntelligence.AdaptiveCognitiveReflection.empty(),
                List.of(), "Nàng Sen", "", List.of()
        );
        assertThat(hallucinatedIncludedNoChunk).contains("UNGROUNDED_TIP_POLICY_CLAIM");

        // 2. Hallucinated "giá đã gồm tip" even when 04_chính_sách chunk IS retrieved -> BLOCKED
        List<String> hallucinatedIncludedWithChunk = CleanAgentOutputRefereeAndSanitizer.checkAciV2TruthAuthorityAndSafety(
                List.of(new FanpageBotResponse.ReplyBubble(
                        "Dạ gói Massage cơ bản 60 phút giá 399k đã bao gồm tiền tip rồi ạ.", 0)),
                catalog, catalog, List.of(),
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                ConversationState.StructuredBookingState.none(),
                List.of(ns01), PagePersonaConfig.empty(),
                "giá đã gồm tip chưa", false, null, null,
                AdaptiveConversationIntelligence.AdaptiveCognitiveReflection.empty(),
                List.of(tipPolicyChunk), "Nàng Sen", "", List.of()
        );
        assertThat(hallucinatedIncludedWithChunk).contains("UNGROUNDED_TIP_POLICY_CLAIM");

        // 3. Hallucinated "bên em không nhận tip" when 04_chính_sách says optional tip ("tùy tâm") -> BLOCKED
        List<String> hallucinatedNoTip = CleanAgentOutputRefereeAndSanitizer.checkAciV2TruthAuthorityAndSafety(
                List.of(new FanpageBotResponse.ReplyBubble(
                        "Dạ tại Nàng Sen bên em không nhận tip dưới mọi hình thức ạ.", 0)),
                catalog, catalog, List.of(),
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                ConversationState.StructuredBookingState.none(),
                List.of(ns01), PagePersonaConfig.empty(),
                "có cần tip không", false, null, null,
                AdaptiveConversationIntelligence.AdaptiveCognitiveReflection.empty(),
                List.of(tipPolicyChunk), "Nàng Sen", "", List.of()
        );
        assertThat(hallucinatedNoTip).contains("UNGROUNDED_TIP_POLICY_CLAIM");

        // 4. Accurate grounded policy answer ("tiền tip chưa bao gồm trong giá dịch vụ và hoàn toàn tùy tâm") -> PASS 0 violations
        List<String> accuratePolicyReply = CleanAgentOutputRefereeAndSanitizer.checkAciV2TruthAuthorityAndSafety(
                List.of(new FanpageBotResponse.ReplyBubble(
                        "Dạ ở gói Massage cơ bản 60 phút giá 399k, tiền tip chưa bao gồm trong giá dịch vụ và hoàn toàn tùy tâm theo sự hài lòng của mình ạ.", 0)),
                catalog, catalog, List.of(),
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                ConversationState.StructuredBookingState.none(),
                List.of(ns01), PagePersonaConfig.empty(),
                "có cần tip không", false, null, null,
                AdaptiveConversationIntelligence.AdaptiveCognitiveReflection.empty(),
                List.of(tipPolicyChunk), "Nàng Sen", "", List.of()
        );
        assertThat(accuratePolicyReply).doesNotContain("UNGROUNDED_TIP_POLICY_CLAIM").isEmpty();
    }

    private static PackageCatalogService.PackageSummary buildSampleDs02() {
        return new PackageCatalogService.PackageSummary(
                "HN:DS02:239000",
                "DS02",
                "HN",
                "Gói dưỡng sinh cơ bản",
                "GỘI ĐẦU DƯỠNG SINH",
                List.of("đầu", "cổ", "vai", "gáy"),
                60,
                "60 phút",
                239_000L,
                "239k",
                "Phòng chung",
                "Gội đầu cơ bản + massage cổ vai gáy + ăn nhẹ & trái cây theo mùa",
                "Đau mỏi nhẹ",
                "Gội đầu thảo dược + massage cổ vai gáy",
                false
        );
    }

    private static PackageCatalogService.PackageSummary buildSampleDs01() {
        return new PackageCatalogService.PackageSummary(
                "HN:DS01:129000",
                "DS01",
                "HN",
                "Lấy ráy tai cơ bản",
                "GỘI ĐẦU DƯỠNG SINH",
                List.of("tai"),
                30,
                "30 phút",
                129_000L,
                "129k",
                "Phòng chung",
                "Lấy ráy tai cơ bản + ăn nhẹ & trái cây theo mùa",
                "Vệ sinh tai cơ bản",
                "Lấy ráy tai cơ bản 30 phút",
                false
        );
    }

    private static List<AgentSkillDto> buildSamplePageSkills() {
        Instant now = Instant.now();
        String pageIdStr = PAGE_ID.toString();
        return List.of(
                new AgentSkillDto(UUID.randomUUID(), "discovery_empathy_and_scope", "Discovery", "PSYCHOLOGY", "Desc", "Trig", "Tac", "Good", "Anti", 98, true, pageIdStr, now, now),
                new AgentSkillDto(UUID.randomUUID(), "price_objection_sensory_value", "Objection", "PSYCHOLOGY", "Desc", "Trig", "Tac", "Good", "Anti", 97, true, pageIdStr, now, now),
                new AgentSkillDto(UUID.randomUUID(), "soft_economical_bridge_and_compare", "Compare", "PSYCHOLOGY", "Desc", "Trig", "Tac", "Good", "Anti", 96, true, pageIdStr, now, now),
                new AgentSkillDto(UUID.randomUUID(), "warm_nod_to_gentle_scheduling", "Booking", "CLOSING", "Desc", "Trig", "Tac", "Good", "Anti", 95, true, pageIdStr, now, now),
                new AgentSkillDto(UUID.randomUUID(), "graceful_pause_keep_warmth", "Pause", "PSYCHOLOGY", "Desc", "Trig", "Tac", "Good", "Anti", 93, true, pageIdStr, now, now)
        );
    }
}
