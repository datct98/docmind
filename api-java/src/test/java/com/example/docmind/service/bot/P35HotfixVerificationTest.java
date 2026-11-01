package com.example.docmind.service.bot;

import com.example.docmind.config.ChatProperties;
import com.example.docmind.controller.HealthController;
import com.example.docmind.dto.PagePersonaConfig;
import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.dto.bot.FanpageBotResponse;
import com.example.docmind.entity.ConversationMessageEntity;
import com.example.docmind.entity.ConversationSessionEntity;
import com.example.docmind.entity.KnowledgePageEntity;
import com.example.docmind.repository.ConversationMessageRepository;
import com.example.docmind.repository.ConversationSessionRepository;
import com.example.docmind.repository.KnowledgePageRepository;
import com.example.docmind.repository.RetrievedChunk;
import com.example.docmind.service.llm.LlmClient;
import com.example.docmind.service.llm.ReasoningBudget;
import com.example.docmind.service.retrieval.ReciprocalRankFusion;
import com.example.docmind.service.retrieval.RetrievalService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class P35HotfixVerificationTest {

    private static final UUID PAGE_ID = UUID.fromString("b23cd382-4f7a-4829-8c54-1d410da14bf8");
    private static final UUID DOC_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @BeforeEach
    void setUp() {
        SentBubbleLedger.useStore(new SentBubbleLedger.InMemoryStore());
        SentBubbleLedger.clear();
        SenderEpochRegistry.clearAll();
        ConversationState.clearSessionCache();
    }

    @AfterEach
    void tearDown() {
        SentBubbleLedger.clear();
        SenderEpochRegistry.clearAll();
        ConversationState.clearSessionCache();
    }

    @Test
    @DisplayName("P3.5 Req 6: HealthController phục vụ cả /api/health và alias /actuator/health (0 NoResourceFoundException WARN)")
    void healthControllerServesBothApiHealthAndActuatorHealthAlias() {
        HealthController controller = new HealthController();
        assertThat(controller.health())
                .containsEntry("status", "ok")
                .containsEntry("service", "docmind-api");
        assertThat(controller.actuatorHealthAlias())
                .containsEntry("status", "UP")
                .containsEntry("service", "docmind-api");
    }

    @Test
    @DisplayName("P3.5 Req 3 & 4: [BOT-TURN-SUMMARY] ghi nhận đủ 7 trường và bảo vệ tuyệt đối SentBubbleLedger/State dưới HUMAN_TAKEOVER & OPT_OUT")
    void botTurnSummaryRecordsAllSevenLifecycleFieldsAndProtectsHumanTakeoverFromReset() {
        List<ReciprocalRankFusion.FusedChunk> chunks = buildSampleNangSenChunks();
        RetrievalService retrievalService = Mockito.mock(RetrievalService.class);
        Mockito.when(retrievalService.retrieveWithRules(
                        Mockito.any(), Mockito.anyString(), Mockito.anyInt(), Mockito.anyList(), Mockito.any()))
                .thenReturn(new RetrievalService.RetrievalResult(chunks, List.of()));

        KnowledgePageRepository pageRepo = Mockito.mock(KnowledgePageRepository.class);
        KnowledgePageEntity pageEntity = new KnowledgePageEntity();
        pageEntity.setId(PAGE_ID);
        pageEntity.setName("Nàng Sen");
        pageEntity.setIndustry("BEAUTY_SPA");
        pageEntity.setPersonaConfig(PagePersonaConfig.empty());
        Mockito.when(pageRepo.findById(PAGE_ID)).thenReturn(Optional.of(pageEntity));

        LlmClient llmClient = Mockito.mock(LlmClient.class);
        Mockito.when(llmClient.name()).thenReturn("mock-aci-v2");
        Mockito.when(llmClient.complete(Mockito.anyString(), Mockito.any(ReasoningBudget.class)))
                .thenReturn("""
                        {
                          "brain_reflection": {
                            "conversational_context": {"current_topic": "Tư vấn chung", "unresolved_requests": []},
                            "customer_understanding": {"turn_intent": "GENERAL_GREETING", "conversation_goal": "EXPLORE", "interaction_permission": "OPEN", "buying_signal": "WEAK", "cost_concern_expressed": false, "booking_readiness": "EXPLORING"},
                            "priority": "CLARIFICATION",
                            "working_hypothesis": {"hypothesis": "Khách mở lời", "barrier_type": "NONE", "confidence": 0.8, "lifecycle_op": "MAINTAIN"},
                            "response_plan": {"strategy": "Warm Acknowledgment", "tone": "LEVEL_1_NATURAL", "depth": "CONCISE", "cta_level": "SOFT_CTA", "next_action": "ASK_ONE_CLARIFYING_QUESTION", "adaptation": "CONTINUATION", "previous_outcome": "NOT_APPLICABLE"},
                            "activated_skill_codes": [],
                            "handoff_request": {"requested": false, "reason": null},
                            "proposed_booking_patch": {"selected_product_id": null, "proposed_date": null, "proposed_time": null}
                          },
                          "bubbles": ["Dạ chào mình, hôm nay mình đang muốn tìm hiểu dịch vụ gội dưỡng sinh hay massage thư giãn ạ?"]
                        }
                        """);

        InboundSessionPersistenceService inboundGuard = Mockito.mock(InboundSessionPersistenceService.class);
        Mockito.when(inboundGuard.verifyOutboundStillPermittedFresh(Mockito.any(), Mockito.anyString())).thenReturn(true);

        FanpageBotService botService = new FanpageBotService(
                retrievalService, llmClient, new ChatProperties(), null, pageRepo,
                null, null, null, null, null
        );
        botService.setInboundSessionPersistenceService(inboundGuard);

        // 1. HUMAN_TAKEOVER + resetSession=true: phải bị chặn ngay ở Entry Guard, KHÔNG xóa SentBubbleLedger, retrievalStarted=false, llmStarted=false
        String takeoverSender = "fb-customer-demo";
        String takeoverLedgerKey = SentBubbleLedger.key(PAGE_ID.toString(), takeoverSender, "conv-takeover");
        SentBubbleLedger.recordCustomerTurn(takeoverLedgerKey, "t1", "Tin nhắn cũ trước khi nhân viên tiếp quản");
        InboundEventIdResolver.ResolvedInboundEvent resolvedEv =
                InboundEventIdResolver.resolveForCurrentContext(null, "Tư vấn");
        Mockito.when(inboundGuard.evaluateEntryAndPersistCustomer(Mockito.any(), Mockito.anyString()))
                .thenReturn(new InboundSessionPersistenceService.EntryEvaluation(
                        InboundSessionPersistenceService.EntryAction.BLOCK_HUMAN_TAKEOVER,
                        UUID.randomUUID(),
                        "HUMAN_TAKEOVER",
                        false,
                        resolvedEv,
                        true
                ));

        FanpageBotRequest takeoverReqWithReset = new FanpageBotRequest(
                PAGE_ID.toString(), takeoverSender, null, "Tư vấn", null, List.of(), List.of(DOC_ID), 5,
                null, null, null, null, false, null, null, "conv-takeover", true, null, null
        );
        FanpageBotResponse suppressedResp = botService.handleCustomerMessage(takeoverReqWithReset);
        assertThat(suppressedResp.salesStage()).isEqualTo("SUPPRESSED");
        assertThat(suppressedResp.replyBubbles()).isEmpty();
        assertThat(SentBubbleLedger.snapshot(takeoverLedgerKey))
                .as("SentBubbleLedger của phiên HUMAN_TAKEOVER không được phép bị xóa dù client gửi resetSession=true")
                .hasSize(1);

        FanpageBotService.TurnSummaryRecord takeoverSummary = botService.getLastTurnSummary();
        assertThat(takeoverSummary).isNotNull();
        assertThat(takeoverSummary.requestReceived()).isTrue();
        assertThat(takeoverSummary.guardDecision()).isEqualTo("BLOCK");
        assertThat(takeoverSummary.guardReason()).isEqualTo("HUMAN_TAKEOVER_SILENT_HOLD");
        assertThat(takeoverSummary.retrievalStarted()).isFalse();
        assertThat(takeoverSummary.llmStarted()).isFalse();
        assertThat(takeoverSummary.responseBubbleCount()).isZero();
        assertThat(takeoverSummary.responseStatus()).isEqualTo("SUPPRESSED");

        // 2. RECONSENT_ALLOW trên phiên mới độc lập: retrievalStarted=true, llmStarted=true, responseStatus=DELIVERED
        String newSimSender = "fb-customer-demo-sim001";
        Mockito.when(inboundGuard.evaluateEntryAndPersistCustomer(Mockito.any(), Mockito.anyString()))
                .thenReturn(new InboundSessionPersistenceService.EntryEvaluation(
                        InboundSessionPersistenceService.EntryAction.RECONSENTED_PROCEED_TO_BOT,
                        UUID.randomUUID(),
                        "BOT_ACTIVE",
                        false,
                        InboundEventIdResolver.resolveForCurrentContext(null, "Giá gói dưỡng sinh bao nhiêu?"),
                        true
                ));
        FanpageBotRequest newConvReq = new FanpageBotRequest(
                PAGE_ID.toString(), newSimSender, null, "Giá gói dưỡng sinh bao nhiêu?", null, List.of(), List.of(DOC_ID), 5
        );
        FanpageBotResponse deliveredResp = botService.handleCustomerMessage(newConvReq);
        assertThat(deliveredResp.replyBubbles()).hasSize(1);

        FanpageBotService.TurnSummaryRecord deliveredSummary = botService.getLastTurnSummary();
        assertThat(deliveredSummary.requestReceived()).isTrue();
        assertThat(deliveredSummary.guardDecision()).isEqualTo("RECONSENT_ALLOW");
        assertThat(deliveredSummary.guardReason()).isEqualTo("RECONSENTED_PROCEED_TO_BOT");
        assertThat(deliveredSummary.retrievalStarted()).isTrue();
        assertThat(deliveredSummary.llmStarted()).isTrue();
        assertThat(deliveredSummary.responseBubbleCount()).isEqualTo(1);
        assertThat(deliveredSummary.responseStatus()).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("P3.5 Req 2 & 3: RESUME_SESSION read-only lookup & HARD_RESET kiểm tra tiền tố an toàn + chặn xóa HUMAN_TAKEOVER / OPT_OUT")
    void resumeLookupAndHardResetEnforcePrefixAndHumanTakeoverOptOutGuards() {
        ConversationSessionRepository sessionRepo = Mockito.mock(ConversationSessionRepository.class);
        ConversationMessageRepository msgRepo = Mockito.mock(ConversationMessageRepository.class);
        FanpageBotService botService = Mockito.mock(FanpageBotService.class);
        ConversationInboxService inboxService = new ConversationInboxService(
                sessionRepo, msgRepo, botService, null, null, null
        );

        // 1. RESUME_SESSION read-only lookup khi chưa có phiên -> found=false, 0 lần save
        Mockito.when(sessionRepo.findFirstByPageIdAndCustomerPsidOrderByUpdatedAtDesc(PAGE_ID, "fb-customer-demo-absent"))
                .thenReturn(Optional.empty());
        Map<String, Object> absentLookup = inboxService.findExistingSessionDetailBySender(PAGE_ID, "fb-customer-demo-absent");
        assertThat(absentLookup).containsEntry("found", false);
        Mockito.verify(sessionRepo, Mockito.never()).save(Mockito.any());

        // 2. HARD_RESET từ chối thiếu confirmHardReset (400), từ chối bare 'fb-customer-demo' (403) và PSID khách thật (403)
        assertThatThrownBy(() -> inboxService.hardResetSimulatorSession(PAGE_ID, "fb-customer-demo-abc123", false))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThatThrownBy(() -> inboxService.hardResetSimulatorSession(PAGE_ID, "fb-customer-demo", true))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThatThrownBy(() -> inboxService.hardResetSimulatorSession(PAGE_ID, "884920193847561", true))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        // 3. HARD_RESET từ chối phiên đang ở HUMAN_TAKEOVER (409 CONFLICT) và phiên đang opted_out (409 CONFLICT)
        ConversationSessionEntity takeoverSession = ConversationSessionEntity.builder()
                .id(UUID.randomUUID())
                .pageId(PAGE_ID)
                .customerPsid("fb-customer-demo-takeover1")
                .controlMode("HUMAN_TAKEOVER")
                .optedOut(false)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        Mockito.when(sessionRepo.findByPageIdAndCustomerPsid(PAGE_ID, "fb-customer-demo-takeover1"))
                .thenReturn(List.of(takeoverSession));

        assertThatThrownBy(() -> inboxService.hardResetSimulatorSession(PAGE_ID, "fb-customer-demo-takeover1", true))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        Mockito.verify(sessionRepo, Mockito.never()).deleteById(takeoverSession.getId());

        ConversationSessionEntity optedOutSession = ConversationSessionEntity.builder()
                .id(UUID.randomUUID())
                .pageId(PAGE_ID)
                .customerPsid("fb-customer-demo-optout1")
                .controlMode("BOT_ACTIVE")
                .optedOut(true)
                .optedOutAt(Instant.now())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        Mockito.when(sessionRepo.findByPageIdAndCustomerPsid(PAGE_ID, "fb-customer-demo-optout1"))
                .thenReturn(List.of(optedOutSession));

        assertThatThrownBy(() -> inboxService.hardResetSimulatorSession(PAGE_ID, "fb-customer-demo-optout1", true))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        // 4. HARD_RESET hợp lệ trên phiên Simulator BOT_ACTIVE độc lập
        UUID cleanSimSessionId = UUID.randomUUID();
        ConversationSessionEntity cleanSimSession = ConversationSessionEntity.builder()
                .id(cleanSimSessionId)
                .pageId(PAGE_ID)
                .customerPsid("fb-customer-demo-clean99")
                .controlMode("BOT_ACTIVE")
                .optedOut(false)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        Mockito.when(sessionRepo.findByPageIdAndCustomerPsid(PAGE_ID, "fb-customer-demo-clean99"))
                .thenReturn(List.of(cleanSimSession));
        Mockito.when(msgRepo.findBySessionIdOrderByCreatedAtAsc(cleanSimSessionId))
                .thenReturn(List.of(ConversationMessageEntity.builder()
                        .id(UUID.randomUUID())
                        .sessionId(cleanSimSessionId)
                        .senderRole("CUSTOMER")
                        .content("Tư vấn")
                        .createdAt(Instant.now())
                        .build()));

        Map<String, Object> resetOut = inboxService.hardResetSimulatorSession(PAGE_ID, "fb-customer-demo-clean99", true);
        assertThat(resetOut)
                .containsEntry("status", "HARD_RESET_COMPLETED")
                .containsEntry("deleted_sessions", 1);
        Mockito.verify(msgRepo).deleteBySessionId(cleanSimSessionId);
        Mockito.verify(sessionRepo).deleteById(cleanSimSessionId);
    }

    @Test
    @DisplayName("P3.5 Req 5A (Tier B STEP_6): 'Nhưng đắt nhỉ' cho phép gợi ý gói trải nghiệm 129k có thực trong Catalog (0 UNPRESENTED_GHOST_PRODUCT_ON_CLOSING), nhưng chặn nghiêm ngặt gói bịa 99k")
    void tierBStep6AllowsVerified129kCatalogTrialOnPriceObjectionWhileBlockingFabricated99k() {
        List<ReciprocalRankFusion.FusedChunk> chunks = buildSampleNangSenChunks();
        List<PackageCatalogService.PackageSummary> allCatalog = PackageCatalogService.extractCatalog(chunks, List.of());
        List<PackageCatalogService.PackageSummary> presentedSoFar = allCatalog.stream()
                .filter(p -> "DS02".equals(p.code()))
                .toList();
        assertThat(allCatalog).extracting(PackageCatalogService.PackageSummary::code).contains("DS01", "DS02");

        // Giả lập trường hợp LLM lỡ gắn priority = POLITE_CLOSURE khi khách nhắn "Nhưng đắt nhỉ" nhưng đề xuất gói 129k có thực trong Catalog
        String rawReflectionWithPoliteClosure = """
                {
                  "brain_reflection": {
                    "conversational_context": {"current_topic": "Băn khoăn giá 239k", "unresolved_requests": []},
                    "customer_understanding": {"turn_intent": "PRICE_OBJECTION", "conversation_goal": "RELAXATION", "interaction_permission": "OPEN", "buying_signal": "WEAK", "cost_concern_expressed": true, "booking_readiness": "EXPLORING"},
                    "priority": "POLITE_CLOSURE",
                    "working_hypothesis": {"hypothesis": "Khách ngại chi phí buổi đầu", "barrier_type": "BUDGET_LIMIT", "confidence": 0.85, "lifecycle_op": "ADJUST"},
                    "response_plan": {"strategy": "Soft Recommendation", "tone": "LEVEL_1_NATURAL", "depth": "CONCISE", "cta_level": "NO_CTA", "next_action": "NO_ADDITIONAL_ACTION", "adaptation": "STOP", "previous_outcome": "REJECTED"},
                    "activated_skill_codes": [],
                    "handoff_request": {"requested": false, "reason": null},
                    "proposed_booking_patch": {"selected_product_id": null, "proposed_date": null, "proposed_time": null}
                  },
                  "bubbles": ["Dạ nếu mình chưa muốn dành trọn 60 phút thì có thể thử bước nhỏ nhẹ nhàng trước với gói Lấy ráy tai cơ bản 30 phút giá 129k kèm ăn nhẹ để cảm nhận không gian bên em xem có hợp ý không nhé ạ."]
                }
                """;
        CleanAgentOutputRefereeAndSanitizer.StructuredLlmOutput parsed =
                CleanAgentOutputRefereeAndSanitizer.parseStructuredLlmJsonOutput(rawReflectionWithPoliteClosure, 2000);
        AdaptiveConversationIntelligence.AdaptiveCognitiveReflection reflection = parsed.aciReflection();

        List<String> valid129kViolations = CleanAgentOutputRefereeAndSanitizer.checkAciV2TruthAuthorityAndSafety(
                parsed.bubbles(),
                allCatalog,
                allCatalog,
                List.of(),
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                ConversationState.StructuredBookingState.none(),
                presentedSoFar,
                PagePersonaConfig.empty(),
                "Nhưng đắt nhỉ",
                false,
                null,
                null,
                reflection,
                chunks,
                "Nàng Sen",
                "Dạ mức 50k bên ngoài thường là gội làm sạch nhanh...",
                List.of()
        );
        assertThat(valid129kViolations)
                .as("Gói 129k có thực trong Catalog không được bị chặn nhầm bởi UNPRESENTED_GHOST_PRODUCT_ON_CLOSING khi khách nói 'Nhưng đắt nhỉ'")
                .isEmpty();

        // Ngược lại: Nếu LLM tự bịa gói 99k không có trong Catalog -> phải bị chặn nghiêm ngặt!
        List<FanpageBotResponse.ReplyBubble> fabricated99kBubbles = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Dạ nếu mình muốn thử nhẹ trước thì bên em có gói gội mini 20 phút giá 99k để mình trải nghiệm thử ạ.",
                        0
                )
        );
        List<String> fabricated99kViolations = CleanAgentOutputRefereeAndSanitizer.checkAciV2TruthAuthorityAndSafety(
                fabricated99kBubbles,
                allCatalog,
                allCatalog,
                List.of(),
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                ConversationState.StructuredBookingState.none(),
                presentedSoFar,
                PagePersonaConfig.empty(),
                "Nhưng đắt nhỉ",
                false,
                null,
                null,
                reflection,
                chunks,
                "Nàng Sen",
                "Dạ mức 50k bên ngoài thường là gội làm sạch nhanh...",
                List.of()
        );
        assertThat(fabricated99kViolations)
                .anyMatch(v -> v.startsWith("UNGROUNDED_PRICE_MENTIONED:99k") || v.startsWith("UNGROUNDED_OR_EXCLUDED_PRICE:99k"));

        // Và nếu khách thực sự xin kết thúc/tạm dừng ("Để chị suy nghĩ thêm đã"), việc chèo kéo thêm gói 129k chưa từng xuất hiện vẫn bị chặn chuẩn xác!
        List<String> explicitPauseViolations = CleanAgentOutputRefereeAndSanitizer.checkAciV2TruthAuthorityAndSafety(
                parsed.bubbles(),
                allCatalog,
                allCatalog,
                List.of(),
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                ConversationState.StructuredBookingState.none(),
                presentedSoFar,
                PagePersonaConfig.empty(),
                "Để chị suy nghĩ thêm đã",
                false,
                null,
                null,
                reflection,
                chunks,
                "Nàng Sen",
                "Dạ mức 239k trọn gói 60 phút ạ.",
                List.of()
        );
        assertThat(explicitPauseViolations)
                .anyMatch(v -> v.startsWith("UNPRESENTED_GHOST_PRODUCT_ON_CLOSING:"));
    }

    @Test
    @DisplayName("P3.5 Req 5B (Tier B STEP_7): Cấm tuyệt đối bot tự nhận 'em là người thật' (FALSE_HUMAN_IDENTITY_CLAIM) và cho phép phản hồi trung thực danh tính trợ lý AI")
    void tierBStep7BlocksFalseHumanIdentityClaimAndAllowsHonestAiAssistantReply() {
        List<ReciprocalRankFusion.FusedChunk> chunks = buildSampleNangSenChunks();
        List<PackageCatalogService.PackageSummary> allCatalog = PackageCatalogService.extractCatalog(chunks, List.of());

        String rawMetaRemarkJson = """
                {
                  "brain_reflection": {
                    "conversational_context": {"current_topic": "Nhận xét cách trả lời", "unresolved_requests": []},
                    "customer_understanding": {"turn_intent": "META_COMMUNICATION_REMARK", "conversation_goal": "RELAXATION", "interaction_permission": "OPEN", "buying_signal": "NONE", "cost_concern_expressed": false, "booking_readiness": "EXPLORING"},
                    "priority": "TRUST_ASSURANCE",
                    "working_hypothesis": {"hypothesis": "Khách thấy câu trả lời giống kịch bản", "barrier_type": "NONE", "confidence": 0.9, "lifecycle_op": "ADJUST"},
                    "response_plan": {"strategy": "Reassure Honestly", "tone": "LEVEL_1_NATURAL", "depth": "CONCISE", "cta_level": "NO_CTA", "next_action": "NO_ADDITIONAL_ACTION", "adaptation": "PIVOT", "previous_outcome": "REJECTED"},
                    "activated_skill_codes": [],
                    "handoff_request": {"requested": false, "reason": null},
                    "proposed_booking_patch": {"selected_product_id": null, "proposed_date": null, "proposed_time": null}
                  },
                  "bubbles": ["Dạ em là người thật đang chat với chị ạ. Em ghi nhận góp ý của chị để nói chuyện tự nhiên hơn ạ."]
                }
                """;
        AdaptiveConversationIntelligence.AdaptiveCognitiveReflection reflection =
                CleanAgentOutputRefereeAndSanitizer.parseStructuredLlmJsonOutput(rawMetaRemarkJson, 2000).aciReflection();

        List<FanpageBotResponse.ReplyBubble> falseHumanBubbles = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Dạ em là người thật đang chat với chị ạ. Em ghi nhận góp ý của chị để nói chuyện tự nhiên hơn ạ.",
                        0
                )
        );
        List<String> falseHumanViolations = CleanAgentOutputRefereeAndSanitizer.checkAciV2TruthAuthorityAndSafety(
                falseHumanBubbles,
                allCatalog,
                allCatalog,
                List.of(),
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                ConversationState.StructuredBookingState.none(),
                allCatalog,
                PagePersonaConfig.empty(),
                "Mỗi khi khách chê đắt, bạn được dạy phải reply vậy à",
                false,
                null,
                null,
                reflection,
                chunks,
                "Nàng Sen",
                "Dạ gói Lấy ráy tai cơ bản 30 phút giá 129k ạ.",
                List.of()
        );
        assertThat(falseHumanViolations).contains("FALSE_HUMAN_IDENTITY_CLAIM");
        assertThat(CleanAgentOutputRefereeAndSanitizer.detectFalseHumanIdentityClaim("Dạ em không phải bot đâu chị ơi.")).isTrue();

        List<FanpageBotResponse.ReplyBubble> honestAiBubbles = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Dạ em là trợ lý AI hỗ trợ tư vấn trên Fanpage của Nàng Sen ạ. Em cảm ơn chị đã nhắc thẳng thắn, câu vừa rồi đúng là hơi giống văn mẫu thật, em xin ghi nhận để trao đổi với chị gọn và tự nhiên hơn ạ.",
                        0
                )
        );
        List<String> honestAiViolations = CleanAgentOutputRefereeAndSanitizer.checkAciV2TruthAuthorityAndSafety(
                honestAiBubbles,
                allCatalog,
                allCatalog,
                List.of(),
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                ConversationState.StructuredBookingState.none(),
                allCatalog,
                PagePersonaConfig.empty(),
                "Mỗi khi khách chê đắt, bạn được dạy phải reply vậy à",
                false,
                null,
                null,
                reflection,
                chunks,
                "Nàng Sen",
                "Dạ gói Lấy ráy tai cơ bản 30 phút giá 129k ạ.",
                List.of()
        );
        assertThat(honestAiViolations).isEmpty();
    }

    private static List<ReciprocalRankFusion.FusedChunk> buildSampleNangSenChunks() {
        String hairAndEarTable = """
                | Mã gói | Tên gói | Giá | Tổng thời gian | Bao gồm | Không gian | KTV | Điểm nổi bật | Phù hợp |
                | --- | --- | --- | --- | --- | --- | --- | --- | --- |
                | DS01 | Lấy ráy tai cơ bản | 129.000VNĐ | 30 phút | Lấy ráy tai thư giãn + vệ sinh tai ngoài + chườm ấm + ăn nhẹ | Giường có rèm che riêng kín đáo | KTV dưỡng sinh | Nhẹ nhàng êm tai, tiết kiệm thời gian | Khách muốn trải nghiệm thử nhẹ nhàng |
                | DS02 | Gói dưỡng sinh cơ bản | 239.000VNĐ | 60 phút | Rửa mặt + massage mặt + gội thảo dược + chườm thảo dược cổ vai + ăn nhẹ | Giường có rèm che riêng kín đáo | KTV dưỡng sinh | Thư giãn đầu cổ vai nhẹ nhàng | Khách cần thư giãn nhanh, tiết kiệm |
                | NS01 | Massage cơ bản | 399.000VNĐ | 60 phút | Full body massage trị liệu | Giường có rèm che riêng kín đáo | KTV tiêu chuẩn | Massage 60 phút + buffet thực dưỡng | Khách thư giãn toàn thân |
                """;
        return List.of(
                new ReciprocalRankFusion.FusedChunk(
                        new RetrievedChunk(
                                UUID.fromString("c9481efb-0002-4613-a2e0-fe50e9b99b39"),
                                DOC_ID,
                                List.of("GỘI ĐẦU DƯỠNG SINH", "Khu vực Hà Nội"),
                                List.of(),
                                1,
                                new double[]{0, 0, 10, 10},
                                hairAndEarTable,
                                0.95
                        ),
                        0.95
                )
        );
    }
}
