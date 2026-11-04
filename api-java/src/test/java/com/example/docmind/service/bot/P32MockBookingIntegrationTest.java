package com.example.docmind.service.bot;

import com.example.docmind.config.ChatProperties;
import com.example.docmind.dto.PagePersonaConfig;
import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.dto.bot.FanpageBotResponse;
import com.example.docmind.entity.ConversationMessageEntity;
import com.example.docmind.entity.ConversationSessionEntity;
import com.example.docmind.entity.DocumentEntity;
import com.example.docmind.entity.KnowledgePageEntity;
import com.example.docmind.repository.ConversationMessageRepository;
import com.example.docmind.repository.ConversationSessionRepository;
import com.example.docmind.repository.DocumentRepository;
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

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bộ kiểm thử nghiệm thu toàn diện cho Unit P3.2 — Mock Booking Integration (Scenarios A–I + Step 5 Create Failure).
 */
class P32MockBookingIntegrationTest {

    private static final UUID PAGE_ID = UUID.fromString("b23cd382-4f7a-4829-8c54-1d410da14bf8");
    private static final UUID DOC_ID = UUID.fromString("22222222-3333-4444-5555-666666666666");

    private static final String SPA_CATALOG_TABLE = """
            | Mã gói | Tên gói | Thời lượng | Giá | Vùng chăm sóc | Vai trò thương mại |
            | --- | --- | --- | --- | --- | --- |
            | GT399 | Gói Giảm Căng Thẳng Toàn Thân | 60 phút | 399k | đầu, cổ, vai, gáy, lưng, toàn thân | gói chính |
            | CVG299 | Gói Cổ Vai Gáy Chuyên Sâu | 60 phút | 299k | cổ, vai, gáy | gói chuyên biệt |
            """;

    private MockBookingGateway mockGateway;
    private AgentBrainService agentBrainService;
    private CleanAgentOrchestratorService orchestrator;
    private FanpageBotService botService;
    private LlmClient llmClient;
    private Map<UUID, ConversationSessionEntity> inMemorySessions;
    private Map<UUID, List<ConversationMessageEntity>> inMemoryMessages;
    private ConversationInboxService inboxService;

    @BeforeEach
    void setUp() {
        SentBubbleLedger.useStore(new SentBubbleLedger.InMemoryStore());
        SentBubbleLedger.clear();
        SenderEpochRegistry.clearAll();
        ConversationState.clearSessionCache();

        mockGateway = new MockBookingGateway();
        agentBrainService = new AgentBrainService();

        List<ReciprocalRankFusion.FusedChunk> chunks = List.of(
                new ReciprocalRankFusion.FusedChunk(
                        new RetrievedChunk(
                                UUID.randomUUID(),
                                DOC_ID,
                                List.of("Bảng giá dịch vụ Nàng Sen Spa"),
                                List.of(UUID.randomUUID()),
                                1,
                                new double[]{0, 0, 100, 100},
                                SPA_CATALOG_TABLE,
                                0.98
                        ),
                        0.98
                )
        );
        RetrievalService retrievalService = Mockito.mock(RetrievalService.class);
        Mockito.when(retrievalService.retrieveWithRules(
                        Mockito.any(), Mockito.anyString(), Mockito.anyInt(), Mockito.anyList(), Mockito.any()))
                .thenReturn(new RetrievalService.RetrievalResult(chunks, List.of()));

        KnowledgePageRepository pageRepo = Mockito.mock(KnowledgePageRepository.class);
        KnowledgePageEntity pageEntity = new KnowledgePageEntity();
        pageEntity.setId(PAGE_ID);
        pageEntity.setName("Nàng Sen Spa");
        pageEntity.setIndustry("BEAUTY_SPA");
        pageEntity.setPersonaConfig(PagePersonaConfig.fromJson("""
                {
                  "industry_code": "BEAUTY_SPA",
                  "bot_name": "Lễ tân Nàng Sen",
                  "self_pronoun": "em",
                  "default_customer_pronoun": "mình",
                  "scope_taxonomy": ["đầu", "cổ", "vai", "gáy", "lưng", "toàn thân"]
                }
                """));
        Mockito.when(pageRepo.findById(PAGE_ID)).thenReturn(Optional.of(pageEntity));

        llmClient = Mockito.mock(LlmClient.class);
        Mockito.when(llmClient.name()).thenReturn("mock-aci-v2-p32");

        DocumentRepository docRepo = Mockito.mock(DocumentRepository.class);
        DocumentEntity readyDoc = new DocumentEntity();
        readyDoc.setId(DOC_ID);
        readyDoc.setPageId(PAGE_ID);
        readyDoc.setStatus("READY");
        readyDoc.setActive(true);
        Mockito.when(docRepo.findByPageIdOrderByCreatedAtDesc(PAGE_ID)).thenReturn(List.of(readyDoc));

        botService = new FanpageBotService(
                retrievalService,
                llmClient,
                new ChatProperties(),
                docRepo,
                pageRepo,
                null,
                null,
                null,
                null,
                null
        );
        botService.setAgentBrainService(agentBrainService);
        orchestrator = botService.getCleanAgentOrchestratorService();
        orchestrator.setBookingGateway(mockGateway);
        orchestrator.setBookingGatewayMode(BookingGateway.Mode.DISABLED);

        inMemorySessions = new LinkedHashMap<>();
        inMemoryMessages = new LinkedHashMap<>();

        ConversationSessionRepository sessionRepo = Mockito.mock(ConversationSessionRepository.class);
        Mockito.when(sessionRepo.findById(Mockito.any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            return Optional.ofNullable(inMemorySessions.get(id));
        });
        Mockito.when(sessionRepo.findFirstByPageIdAndCustomerPsidOrderByUpdatedAtDesc(Mockito.any(), Mockito.anyString()))
                .thenAnswer(inv -> {
                    UUID pid = inv.getArgument(0);
                    String psid = inv.getArgument(1);
                    return inMemorySessions.values().stream()
                            .filter(s -> pid.equals(s.getPageId()) && psid.equals(s.getCustomerPsid()))
                            .findFirst();
                });
        Mockito.when(sessionRepo.save(Mockito.any())).thenAnswer(inv -> {
            ConversationSessionEntity entity = inv.getArgument(0);
            inMemorySessions.put(entity.getId(), entity);
            return entity;
        });

        ConversationMessageRepository msgRepo = Mockito.mock(ConversationMessageRepository.class);
        Mockito.when(msgRepo.findBySessionIdOrderByCreatedAtAsc(Mockito.any())).thenAnswer(inv -> {
            UUID sid = inv.getArgument(0);
            return new ArrayList<>(inMemoryMessages.getOrDefault(sid, List.of()));
        });
        Mockito.when(msgRepo.save(Mockito.any())).thenAnswer(inv -> {
            ConversationMessageEntity msg = inv.getArgument(0);
            inMemoryMessages.computeIfAbsent(msg.getSessionId(), k -> new ArrayList<>()).add(msg);
            return msg;
        });

        inboxService = new ConversationInboxService(
                sessionRepo,
                msgRepo,
                botService,
                null,
                null,
                null,
                pageRepo
        );
        inboxService.setAgentBrainService(agentBrainService);
    }

    @AfterEach
    void tearDown() {
        mockGateway.reset();
        SentBubbleLedger.clear();
        SenderEpochRegistry.clearAll();
        ConversationState.clearSessionCache();
    }

    private void stubDefaultAciV2LlmReply(String bubbleText, String primaryPriority, String bookingReadiness) {
        String json = """
                {
                  "brain_reflection": {
                    "conversational_context": {"current_topic": "Tư vấn đặt lịch", "unresolved_requests": []},
                    "customer_understanding": {
                      "turn_intent": "BOOKING_FLOW",
                      "conversation_goal": "BOOK_APPOINTMENT",
                      "interaction_permission": "OPEN",
                      "buying_signal": "STRONG",
                      "cost_concern_expressed": false,
                      "booking_readiness": "%s"
                    },
                    "priority": "%s",
                    "working_hypothesis": {"hypothesis": "Khách đang trao đổi lịch hẹn", "barrier_type": "NONE", "confidence": 0.9, "lifecycle_op": "MAINTAIN"},
                    "response_plan": {"strategy": "Direct Answer", "tone": "LEVEL_1_NATURAL", "depth": "CONCISE", "cta_level": "SOFT_CTA", "next_action": "ANSWER_DIRECTLY", "adaptation": "CONTINUATION", "previous_outcome": "NOT_APPLICABLE"},
                    "activated_skill_codes": [],
                    "handoff_request": {"requested": false, "reason": null},
                    "proposed_booking_patch": {"selected_product_id": null, "proposed_date": null, "proposed_time": null}
                  },
                  "bubbles": ["%s"]
                }
                """.formatted(bookingReadiness, primaryPriority, bubbleText.replace("\"", "\\\""));
        Mockito.when(llmClient.complete(Mockito.anyString(), Mockito.any(ReasoningBudget.class))).thenReturn(json);
        Mockito.when(llmClient.complete(Mockito.anyString())).thenReturn(json);
    }

    private FanpageBotResponse sendTurn(
            String senderId,
            String message,
            List<FanpageBotRequest.ConversationTurn> history,
            String stubbedBubble
    ) {
        stubDefaultAciV2LlmReply(stubbedBubble, "BOOKING_COMPLETION", "READY_TO_BOOK");
        FanpageBotRequest req = new FanpageBotRequest(
                PAGE_ID.toString(),
                senderId,
                "Khách Test P3.2",
                message,
                null,
                history,
                List.of(DOC_ID),
                3,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                "conv-" + senderId,
                false,
                null,
                null
        );
        FanpageBotResponse resp = botService.handleCustomerMessage(req);
        history.add(new FanpageBotRequest.ConversationTurn("user", message));
        history.add(new FanpageBotRequest.ConversationTurn("assistant", resp.replyText()));
        return resp;
    }

    @Test
    @DisplayName("Section 3.4: application.properties mặc định booking.gateway.mode=DISABLED (cô lập tuyệt đối Production)")
    void applicationPropertiesDefaultsBookingGatewayModeToDisabled() throws Exception {
        Properties props = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            assertThat(in).isNotNull();
            props.load(in);
        }
        String rawProp = props.getProperty("booking.gateway.mode");
        assertThat(rawProp).isEqualTo("${BOOKING_GATEWAY_MODE:DISABLED}");
        assertThat(BookingGateway.Mode.fromProperty("DISABLED")).isEqualTo(BookingGateway.Mode.DISABLED);
        assertThat(BookingGateway.Mode.fromProperty(null)).isEqualTo(BookingGateway.Mode.DISABLED);
    }

    @Test
    @DisplayName("Scenario A — Mock Happy Path Booking: Tư vấn 1 gói -> Đặt 19h tối nay (checkAvailability -> AWAITING_CUSTOMER_CONFIRMATION) -> Khách xác nhận (createBooking -> CONFIRMED + MOCK-BK-*)")
    void scenarioA_mockHappyPathBooking() {
        orchestrator.setBookingGatewayMode(BookingGateway.Mode.MOCK);
        String senderId = "sim_p32_scenario_a";
        List<FanpageBotRequest.ConversationTurn> history = new ArrayList<>();

        // Turn 1: Tư vấn duy nhất Gói Giảm Căng Thẳng Toàn Thân (399k)
        FanpageBotResponse t1 = sendTurn(
                senderId,
                "Mình muốn hỏi Gói Giảm Căng Thẳng Toàn Thân bên mình giá bao nhiêu?",
                history,
                "Dạ Gói Giảm Căng Thẳng Toàn Thân 60 phút bên em có giá 399k trọn gói ạ. Mình muốn đặt lịch khung giờ nào để em kiểm tra lịch trống giúp mình nhé ạ?"
        );
        assertThat(t1.replyText()).contains("Gói Giảm Căng Thẳng Toàn Thân");

        // Turn 2: Khách chọn đặt tối nay lúc 19h -> checkAvailability thành công -> AWAITING_CUSTOMER_CONFIRMATION (chưa tạo booking)
        FanpageBotResponse t2 = sendTurn(
                senderId,
                "Cho mình đặt gói này tối nay lúc 19h nhé",
                history,
                "Dạ để em kiểm tra lịch lúc 19:00 tối nay ạ."
        );
        ConversationState.StructuredBookingState stateAfterT2 = agentBrainService.recallBookingState(PAGE_ID, senderId);
        assertThat(stateAfterT2.status()).isEqualTo(ConversationState.BookingStatus.AWAITING_CUSTOMER_CONFIRMATION);
        assertThat(stateAfterT2.bookingTime()).isEqualTo("19:00");
        assertThat(mockGateway.getCheckAvailabilityCallCount()).isEqualTo(1);
        assertThat(mockGateway.getCreateBookingCallCount()).isZero();
        assertThat(agentBrainService.recallBookingId(PAGE_ID, senderId)).isNull();
        assertThat(t2.replyText())
                .contains("19:00")
                .contains("xác nhận")
                .doesNotContain("đã đặt lịch thành công")
                .doesNotContain("MOCK-BK-");

        // Turn 3: Khách xác nhận -> createBooking thành công -> CONFIRMED + mã MOCK-BK-*
        FanpageBotResponse t3 = sendTurn(
                senderId,
                "Ok chốt giúp mình nhé",
                history,
                "Dạ em xác nhận lịch hẹn cho mình ạ."
        );
        ConversationState.StructuredBookingState stateAfterT3 = agentBrainService.recallBookingState(PAGE_ID, senderId);
        String bookingId = agentBrainService.recallBookingId(PAGE_ID, senderId);
        String bookingSource = agentBrainService.recallBookingSource(PAGE_ID, senderId);

        assertThat(stateAfterT3.status()).isEqualTo(ConversationState.BookingStatus.CONFIRMED);
        assertThat(mockGateway.getCreateBookingCallCount()).isEqualTo(1);
        assertThat(bookingId).isNotNull().startsWith("MOCK-BK-");
        assertThat(bookingSource).isEqualTo("MOCK");
        assertThat(t3.replyText())
                .contains("đã đặt lịch thành công")
                .contains(bookingId)
                .contains("MOCK");
    }

    @Test
    @DisplayName("Scenario B — Production Default (DISABLED): Không gọi MockBookingGateway, dừng ở AWAITING_AVAILABILITY_CHECK, không tạo booking_id, không tuyên bố đã đặt lịch thành công")
    void scenarioB_productionDefaultDisabledNeverConfirmsOrCallsMockGateway() {
        orchestrator.setBookingGatewayMode(BookingGateway.Mode.DISABLED);
        String senderId = "sim_p32_scenario_b";
        List<FanpageBotRequest.ConversationTurn> history = new ArrayList<>();

        sendTurn(
                senderId,
                "Tư vấn cho mình Gói Giảm Căng Thẳng Toàn Thân 399k",
                history,
                "Dạ Gói Giảm Căng Thẳng Toàn Thân 60 phút bên em giá 399k trọn gói ạ."
        );

        FanpageBotResponse t2 = sendTurn(
                senderId,
                "Cho mình đặt gói này tối nay lúc 19h nhé",
                history,
                "Dạ em đã ghi nhận thông tin 19:00 tối nay và sẽ kiểm tra lịch trống thực tế giúp mình ạ."
        );
        assertThat(agentBrainService.recallBookingState(PAGE_ID, senderId).status())
                .isEqualTo(ConversationState.BookingStatus.AWAITING_AVAILABILITY_CHECK);
        assertThat(mockGateway.getCheckAvailabilityCallCount()).isZero();
        assertThat(mockGateway.getCreateBookingCallCount()).isZero();

        FanpageBotResponse t3 = sendTurn(
                senderId,
                "Ok chốt giúp mình nhé",
                history,
                "Dạ em đã đặt lịch thành công cho mình rồi ạ."
        );
        assertThat(agentBrainService.recallBookingState(PAGE_ID, senderId).status())
                .isEqualTo(ConversationState.BookingStatus.AWAITING_AVAILABILITY_CHECK);
        assertThat(agentBrainService.recallBookingId(PAGE_ID, senderId)).isNull();
        assertThat(mockGateway.getCheckAvailabilityCallCount()).isZero();
        assertThat(mockGateway.getCreateBookingCallCount()).isZero();
        assertThat(t3.replyText())
                .doesNotContain("đã đặt lịch thành công")
                .doesNotContain("MOCK-BK-")
                .contains("kiểm tra lịch trống thực tế");
    }

    @Test
    @DisplayName("Scenario C — Slot Không Còn Chỗ: 19:00 kín lịch -> chuyển về AWAITING_DATE_OR_TIME -> khách đổi 20:00 -> checkAvailability 20:00 -> xác nhận -> CONFIRMED")
    void scenarioC_unavailableSlotRevertsToAwaitingDateOrTimeThenConfirmsNewSlot() {
        orchestrator.setBookingGatewayMode(BookingGateway.Mode.MOCK);
        mockGateway.markTimeUnavailable("19:00");
        String senderId = "sim_p32_scenario_c";
        List<FanpageBotRequest.ConversationTurn> history = new ArrayList<>();

        sendTurn(
                senderId,
                "Mình hỏi Gói Giảm Căng Thẳng Toàn Thân 399k",
                history,
                "Dạ Gói Giảm Căng Thẳng Toàn Thân 60 phút giá 399k trọn gói ạ."
        );

        // Turn 2: Khách chọn 19h tối nay (đã đánh dấu hết chỗ)
        FanpageBotResponse t2 = sendTurn(
                senderId,
                "Đặt cho mình gói này tối nay lúc 19h nhé",
                history,
                "Dạ để em kiểm tra lịch 19:00 tối nay ạ."
        );
        ConversationState.StructuredBookingState stateAfterT2 = agentBrainService.recallBookingState(PAGE_ID, senderId);
        assertThat(stateAfterT2.status()).isEqualTo(ConversationState.BookingStatus.AWAITING_DATE_OR_TIME);
        assertThat(stateAfterT2.bookingTime()).isNull();
        assertThat(mockGateway.getCreateBookingCallCount()).isZero();
        assertThat(t2.replyText())
                .contains("kín lịch")
                .doesNotContain("đã đặt lịch thành công");

        // Turn 3: Khách đổi sang 20h
        FanpageBotResponse t3 = sendTurn(
                senderId,
                "Thế đổi sang 20h tối nay giúp mình nhé",
                history,
                "Dạ để em kiểm tra khung 20:00 tối nay ạ."
        );
        ConversationState.StructuredBookingState stateAfterT3 = agentBrainService.recallBookingState(PAGE_ID, senderId);
        assertThat(stateAfterT3.status()).isEqualTo(ConversationState.BookingStatus.AWAITING_CUSTOMER_CONFIRMATION);
        assertThat(stateAfterT3.bookingTime()).isEqualTo("20:00");
        assertThat(mockGateway.getCreateBookingCallCount()).isZero();
        assertThat(t3.replyText()).contains("20:00").contains("xác nhận");

        // Turn 4: Khách xác nhận khung 20:00
        FanpageBotResponse t4 = sendTurn(
                senderId,
                "Ok chốt giúp mình nhé",
                history,
                "Dạ em xác nhận lịch hẹn 20:00 ạ."
        );
        ConversationState.StructuredBookingState stateAfterT4 = agentBrainService.recallBookingState(PAGE_ID, senderId);
        assertThat(stateAfterT4.status()).isEqualTo(ConversationState.BookingStatus.CONFIRMED);
        assertThat(stateAfterT4.bookingTime()).isEqualTo("20:00");
        assertThat(mockGateway.getCreateBookingCallCount()).isEqualTo(1);
        assertThat(t4.replyText()).contains("20:00").contains("MOCK-BK-");
    }

    @Test
    @DisplayName("Scenario D — Khách Từ Chối hoặc Chưa Quyết Định: Lưỡng lự giữ AWAITING_CUSTOMER_CONFIRMATION; Từ chối đặt hủy về NONE, createBooking = 0")
    void scenarioD_customerHesitationAndExplicitRefusalDoNotCreateBooking() {
        orchestrator.setBookingGatewayMode(BookingGateway.Mode.MOCK);
        String senderId = "sim_p32_scenario_d";
        List<FanpageBotRequest.ConversationTurn> history = new ArrayList<>();

        sendTurn(
                senderId,
                "Mình muốn xem Gói Giảm Căng Thẳng Toàn Thân 399k",
                history,
                "Dạ Gói Giảm Căng Thẳng Toàn Thân 60 phút giá 399k trọn gói ạ."
        );
        sendTurn(
                senderId,
                "Cho mình đặt gói này tối nay lúc 19h",
                history,
                "Dạ để em kiểm tra lịch 19:00 ạ."
        );
        assertThat(agentBrainService.recallBookingState(PAGE_ID, senderId).status())
                .isEqualTo(ConversationState.BookingStatus.AWAITING_CUSTOMER_CONFIRMATION);

        // Turn 3a: Khách lưỡng lự "Để tôi kiểm tra lịch của mình đã" -> giữ AWAITING_CUSTOMER_CONFIRMATION, không gọi createBooking
        FanpageBotResponse t3Hesitate = sendTurn(
                senderId,
                "Để tôi kiểm tra lịch của mình đã",
                history,
                "Dạ mình cứ kiểm tra lịch thoải mái nhé ạ."
        );
        assertThat(agentBrainService.recallBookingState(PAGE_ID, senderId).status())
                .isEqualTo(ConversationState.BookingStatus.AWAITING_CUSTOMER_CONFIRMATION);
        assertThat(mockGateway.getCreateBookingCallCount()).isZero();
        assertThat(agentBrainService.recallBookingId(PAGE_ID, senderId)).isNull();
        assertThat(t3Hesitate.replyText()).doesNotContain("MOCK-BK-").doesNotContain("đã đặt lịch thành công");

        // Turn 3b: Khách từ chối đặt "Thôi mình không đặt đâu" -> reset về NONE, không gọi createBooking
        FanpageBotResponse t4Refuse = sendTurn(
                senderId,
                "Thôi mình không đặt đâu",
                history,
                "Dạ vâng ạ, khi nào mình cần đặt lịch cứ nhắn em nhé ạ."
        );
        assertThat(agentBrainService.recallBookingState(PAGE_ID, senderId).status())
                .isEqualTo(ConversationState.BookingStatus.NONE);
        assertThat(mockGateway.getCreateBookingCallCount()).isZero();
        assertThat(agentBrainService.recallBookingId(PAGE_ID, senderId)).isNull();
        assertThat(t4Refuse.replyText()).doesNotContain("MOCK-BK-").doesNotContain("đã đặt lịch thành công");
    }

    @Test
    @DisplayName("Scenario E — Khách Đổi Giờ Trước Khi Xác Nhận: Đang chờ xác nhận 19:00 -> đổi sang 20:00 -> phải checkAvailability lại cho 20:00, không giữ xác nhận cũ")
    void scenarioE_customerChangesTimeBeforeConfirmationReChecksAvailability() {
        orchestrator.setBookingGatewayMode(BookingGateway.Mode.MOCK);
        String senderId = "sim_p32_scenario_e";
        List<FanpageBotRequest.ConversationTurn> history = new ArrayList<>();

        sendTurn(
                senderId,
                "Cho mình hỏi Gói Giảm Căng Thẳng Toàn Thân 399k",
                history,
                "Dạ Gói Giảm Căng Thẳng Toàn Thân 60 phút giá 399k ạ."
        );
        sendTurn(
                senderId,
                "Đặt cho mình gói này tối nay lúc 19h",
                history,
                "Dạ khung 19:00 còn trống ạ."
        );
        assertThat(agentBrainService.recallBookingState(PAGE_ID, senderId).status())
                .isEqualTo(ConversationState.BookingStatus.AWAITING_CUSTOMER_CONFIRMATION);
        assertThat(mockGateway.getCheckAvailabilityCallCount()).isEqualTo(1);

        // Khách đổi giờ sang 20h trước khi xác nhận
        FanpageBotResponse t3 = sendTurn(
                senderId,
                "À đổi sang 20h giúp mình",
                history,
                "Dạ để em kiểm tra lại khung 20:00 ạ."
        );
        ConversationState.StructuredBookingState stateAfterChange = agentBrainService.recallBookingState(PAGE_ID, senderId);
        assertThat(stateAfterChange.status()).isEqualTo(ConversationState.BookingStatus.AWAITING_CUSTOMER_CONFIRMATION);
        assertThat(stateAfterChange.bookingTime()).isEqualTo("20:00");
        assertThat(mockGateway.getCheckAvailabilityCallCount()).isEqualTo(2);
        assertThat(mockGateway.getCreateBookingCallCount()).isZero();
        assertThat(t3.replyText())
                .contains("20:00")
                .contains("xác nhận")
                .doesNotContain("đã đặt lịch thành công");

        // Khách xác nhận khung 20h mới
        FanpageBotResponse t4 = sendTurn(
                senderId,
                "Ok chốt giúp mình nhé",
                history,
                "Dạ em chốt lịch 20:00 cho mình ạ."
        );
        assertThat(agentBrainService.recallBookingState(PAGE_ID, senderId).status())
                .isEqualTo(ConversationState.BookingStatus.CONFIRMED);
        assertThat(agentBrainService.recallBookingState(PAGE_ID, senderId).bookingTime()).isEqualTo("20:00");
        assertThat(mockGateway.getCreateBookingCallCount()).isEqualTo(1);
        assertThat(t4.replyText()).contains("20:00").contains("MOCK-BK-");
    }

    @Test
    @DisplayName("Scenario F — Khách Đổi Gói Dịch Vụ Trong Quá Trình Đặt Lịch: Đổi từ Gói Giảm Căng Thẳng sang Gói Cổ Vai Gáy Chuyên Sâu -> cập nhật selectedProductId & checkAvailability lại")
    void scenarioF_customerChangesServicePackageDuringBookingFlow() {
        orchestrator.setBookingGatewayMode(BookingGateway.Mode.MOCK);
        String senderId = "sim_p32_scenario_f";
        List<FanpageBotRequest.ConversationTurn> history = new ArrayList<>();

        sendTurn(
                senderId,
                "Tư vấn giúp mình Gói Giảm Căng Thẳng Toàn Thân 399k",
                history,
                "Dạ Gói Giảm Căng Thẳng Toàn Thân 60 phút giá 399k trọn gói ạ."
        );
        sendTurn(
                senderId,
                "Đặt cho mình gói này tối nay lúc 19h",
                history,
                "Dạ khung 19:00 còn chỗ trống ạ."
        );
        ConversationState.StructuredBookingState beforeSwitch = agentBrainService.recallBookingState(PAGE_ID, senderId);
        assertThat(beforeSwitch.status()).isEqualTo(ConversationState.BookingStatus.AWAITING_CUSTOMER_CONFIRMATION);
        assertThat(beforeSwitch.selectedProductLabel()).containsIgnoringCase("Giảm Căng Thẳng Toàn Thân");

        // Khách đổi sang Gói Cổ Vai Gáy Chuyên Sâu
        FanpageBotResponse t3 = sendTurn(
                senderId,
                "Mình đổi sang Gói Cổ Vai Gáy Chuyên Sâu nhé",
                history,
                "Dạ để em kiểm tra lại lịch cho Gói Cổ Vai Gáy Chuyên Sâu ạ."
        );
        ConversationState.StructuredBookingState afterSwitch = agentBrainService.recallBookingState(PAGE_ID, senderId);
        assertThat(afterSwitch.selectedProductId()).isNotEqualTo(beforeSwitch.selectedProductId());
        assertThat(afterSwitch.selectedProductLabel()).containsIgnoringCase("Cổ Vai Gáy Chuyên Sâu");
        assertThat(afterSwitch.status()).isEqualTo(ConversationState.BookingStatus.AWAITING_CUSTOMER_CONFIRMATION);
        assertThat(mockGateway.getCheckAvailabilityCallCount()).isEqualTo(2);
        assertThat(mockGateway.getCreateBookingCallCount()).isZero();
        assertThat(t3.replyText())
                .containsIgnoringCase("Cổ Vai Gáy Chuyên Sâu")
                .contains("xác nhận")
                .doesNotContain("đã đặt lịch thành công");

        // Khách xác nhận gói mới
        FanpageBotResponse t4 = sendTurn(
                senderId,
                "Ok chốt giúp mình nhé",
                history,
                "Dạ em xác nhận lịch hẹn Gói Cổ Vai Gáy Chuyên Sâu ạ."
        );
        assertThat(agentBrainService.recallBookingState(PAGE_ID, senderId).status())
                .isEqualTo(ConversationState.BookingStatus.CONFIRMED);
        assertThat(mockGateway.getCreateBookingCallCount()).isEqualTo(1);
        assertThat(t4.replyText()).containsIgnoringCase("Cổ Vai Gáy Chuyên Sâu").contains("MOCK-BK-");
    }

    @Test
    @DisplayName("Scenario G — Duplicate Confirmation Event (Idempotency): Gửi lặp sự kiện xác nhận chỉ tạo 1 booking duy nhất, giữ nguyên MOCK-BK-*")
    void scenarioG_duplicateConfirmationEventCreatesOnlyOneBooking() {
        orchestrator.setBookingGatewayMode(BookingGateway.Mode.MOCK);
        String senderId = "sim_p32_scenario_g";
        List<FanpageBotRequest.ConversationTurn> history = new ArrayList<>();

        sendTurn(
                senderId,
                "Cho mình hỏi Gói Giảm Căng Thẳng Toàn Thân 399k",
                history,
                "Dạ Gói Giảm Căng Thẳng Toàn Thân 60 phút giá 399k ạ."
        );
        sendTurn(
                senderId,
                "Đặt gói này tối nay lúc 19h",
                history,
                "Dạ khung 19:00 còn trống ạ."
        );

        FanpageBotResponse firstConfirm = sendTurn(
                senderId,
                "Ok chốt giúp mình nhé",
                history,
                "Dạ em đã đặt lịch thành công ạ."
        );
        String firstBookingId = agentBrainService.recallBookingId(PAGE_ID, senderId);
        assertThat(firstBookingId).isNotNull().startsWith("MOCK-BK-");
        assertThat(mockGateway.getActiveBookingCount()).isEqualTo(1);

        // Gửi lặp lại câu xác nhận
        FanpageBotResponse secondConfirm = sendTurn(
                senderId,
                "Ok chốt giúp mình nhé",
                history,
                "Dạ em đã đặt lịch thành công ạ."
        );
        String secondBookingId = agentBrainService.recallBookingId(PAGE_ID, senderId);
        assertThat(secondBookingId).isEqualTo(firstBookingId);
        assertThat(mockGateway.getActiveBookingCount()).isEqualTo(1);
        assertThat(secondConfirm.replyText()).contains(firstBookingId);
    }

    @Test
    @DisplayName("Scenario H — Multi-Product Ambiguity Guard: Đã giới thiệu >= 2 gói mà khách đặt lịch chưa chọn gói -> giữ AWAITING_PRODUCT_SELECTION, không gọi checkAvailability/createBooking")
    void scenarioH_multiProductAmbiguityGuardRequiresExplicitProductSelection() {
        orchestrator.setBookingGatewayMode(BookingGateway.Mode.MOCK);
        String senderId = "sim_p32_scenario_h";
        List<FanpageBotRequest.ConversationTurn> history = new ArrayList<>();

        // Turn 1: Bot giới thiệu cả 2 gói trong bảng giá
        sendTurn(
                senderId,
                "Bên mình có những gói massage nào cho cổ vai gáy và toàn thân?",
                history,
                "Dạ bên em có Gói Cổ Vai Gáy Chuyên Sâu 60 phút (299k) và Gói Giảm Căng Thẳng Toàn Thân 60 phút (399k) ạ."
        );

        // Turn 2: Khách nói "Thế đặt lịch cho mình tối nay lúc 19h nhé" mà chưa chỉ rõ gói nào
        FanpageBotResponse t2 = sendTurn(
                senderId,
                "Thế đặt lịch cho mình tối nay lúc 19h nhé",
                history,
                "Dạ mình muốn chọn gói nào ạ?"
        );
        ConversationState.StructuredBookingState stateAfterT2 = agentBrainService.recallBookingState(PAGE_ID, senderId);
        assertThat(stateAfterT2.status()).isEqualTo(ConversationState.BookingStatus.AWAITING_PRODUCT_SELECTION);
        assertThat(stateAfterT2.selectedProductId()).isNull();
        assertThat(mockGateway.getCheckAvailabilityCallCount()).isZero();
        assertThat(mockGateway.getCreateBookingCallCount()).isZero();
        assertThat(t2.replyText())
                .containsIgnoringCase("Gói Cổ Vai Gáy Chuyên Sâu")
                .containsIgnoringCase("Gói Giảm Căng Thẳng Toàn Thân")
                .doesNotContain("MOCK-BK-");
    }

    @Test
    @DisplayName("Step 5 — CreateBooking Failure Handling: Khi MockBookingGateway.createBooking trả FAILURE -> giữ AWAITING_CUSTOMER_CONFIRMATION, không tạo mã giả, báo lỗi trung thực")
    void step5_createBookingFailureKeepsAwaitingConfirmationAndNeverInventsBookingId() {
        orchestrator.setBookingGatewayMode(BookingGateway.Mode.MOCK);
        String senderId = "sim_p32_step5_fail";
        List<FanpageBotRequest.ConversationTurn> history = new ArrayList<>();

        sendTurn(
                senderId,
                "Cho mình xem Gói Giảm Căng Thẳng Toàn Thân 399k",
                history,
                "Dạ Gói Giảm Căng Thẳng Toàn Thân 60 phút giá 399k ạ."
        );
        sendTurn(
                senderId,
                "Đặt gói này tối nay lúc 19h",
                history,
                "Dạ khung 19:00 còn trống ạ."
        );
        assertThat(agentBrainService.recallBookingState(PAGE_ID, senderId).status())
                .isEqualTo(ConversationState.BookingStatus.AWAITING_CUSTOMER_CONFIRMATION);

        // Giả lập lỗi hệ thống ở bước createBooking
        mockGateway.setSimulateCreateFailureReason("MOCK_BOOKING_UPSTREAM_TIMEOUT");
        FanpageBotResponse t3Fail = sendTurn(
                senderId,
                "Ok chốt giúp mình nhé",
                history,
                "Dạ em xác nhận lịch ạ."
        );
        ConversationState.StructuredBookingState stateAfterFail = agentBrainService.recallBookingState(PAGE_ID, senderId);
        assertThat(stateAfterFail.status()).isEqualTo(ConversationState.BookingStatus.AWAITING_CUSTOMER_CONFIRMATION);
        assertThat(agentBrainService.recallBookingId(PAGE_ID, senderId)).isNull();
        assertThat(t3Fail.replyText())
                .doesNotContain("đã đặt lịch thành công")
                .doesNotContain("MOCK-BK-")
                .contains("MOCK_BOOKING_UPSTREAM_TIMEOUT");
    }

    @Test
    @DisplayName("Scenario I — UI Simulator End-to-End: Đi trọn chu trình qua ConversationInboxService.sendCustomerTurnInSession và hiển thị rõ booking_status, booking_id, booking_source=MOCK")
    void scenarioI_uiSimulatorEndToEndExposesMockBookingMetadataClearly() {
        orchestrator.setBookingGatewayMode(BookingGateway.Mode.MOCK);
        String senderId = "sim_p32_scenario_i";
        UUID sessionId = UUID.randomUUID();
        inMemorySessions.put(sessionId, ConversationSessionEntity.builder()
                .id(sessionId)
                .pageId(PAGE_ID)
                .customerPsid(senderId)
                .customerName("Khách Simulator P3.2")
                .controlMode("BOT_ACTIVE")
                .createdAt(java.time.Instant.now())
                .updatedAt(java.time.Instant.now())
                .build());

        // Turn 1 qua Simulator
        stubDefaultAciV2LlmReply(
                "Dạ Gói Giảm Căng Thẳng Toàn Thân 60 phút bên em có giá 399k trọn gói ạ.",
                "BOOKING_COMPLETION",
                "READY_TO_BOOK"
        );
        Map<String, Object> simT1 = inboxService.sendCustomerTurnInSession(
                sessionId,
                "Mình muốn hỏi Gói Giảm Căng Thẳng Toàn Thân 399k"
        );
        assertThat(simT1.get("booking_status")).isEqualTo("AWAITING_DATE_OR_TIME");
        assertThat(simT1.get("booking_id")).isNull();

        // Turn 2 qua Simulator: Đặt tối nay lúc 19h -> AWAITING_CUSTOMER_CONFIRMATION
        stubDefaultAciV2LlmReply(
                "Dạ khung 19:00 tối nay còn trống ạ.",
                "BOOKING_COMPLETION",
                "READY_TO_BOOK"
        );
        Map<String, Object> simT2 = inboxService.sendCustomerTurnInSession(
                sessionId,
                "Cho mình đặt gói này tối nay lúc 19h nhé"
        );
        assertThat(simT2.get("booking_status")).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
        assertThat(simT2.get("booking_id")).isNull();
        assertThat(simT2.get("booking_source")).isEqualTo("MOCK");

        // Turn 3 qua Simulator: Khách xác nhận -> CONFIRMED + MOCK-BK-* + booking_source=MOCK
        stubDefaultAciV2LlmReply(
                "Dạ em xác nhận lịch hẹn cho mình ạ.",
                "BOOKING_COMPLETION",
                "READY_TO_BOOK"
        );
        Map<String, Object> simT3 = inboxService.sendCustomerTurnInSession(
                sessionId,
                "Ok chốt giúp mình nhé"
        );
        assertThat(simT3.get("booking_status")).isEqualTo("CONFIRMED");
        String simBookingId = (String) simT3.get("booking_id");
        assertThat(simBookingId).isNotNull().startsWith("MOCK-BK-");
        assertThat(simT3.get("booking_source")).isEqualTo("MOCK");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) simT3.get("messages");
        Map<String, Object> lastBotMsg = messages.get(messages.size() - 1);
        assertThat(lastBotMsg.get("sender_role")).isEqualTo("AI_BOT");
        assertThat(lastBotMsg.get("booking_status")).isEqualTo("CONFIRMED");
        assertThat(lastBotMsg.get("booking_id")).isEqualTo(simBookingId);
        assertThat(lastBotMsg.get("booking_source")).isEqualTo("MOCK");
        assertThat((String) lastBotMsg.get("content"))
                .contains(simBookingId)
                .contains("MOCK");
    }
}
