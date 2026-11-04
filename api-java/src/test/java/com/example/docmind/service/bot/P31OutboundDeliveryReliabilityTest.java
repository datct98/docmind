package com.example.docmind.service.bot;

import com.example.docmind.config.ChatProperties;
import com.example.docmind.controller.FanpageBotController;
import com.example.docmind.dto.PagePersonaConfig;
import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.dto.bot.FanpageBotResponse;
import com.example.docmind.entity.KnowledgePageEntity;
import com.example.docmind.repository.KnowledgePageRepository;
import com.example.docmind.repository.RetrievedChunk;
import com.example.docmind.service.llm.LlmClient;
import com.example.docmind.service.llm.ReasoningBudget;
import com.example.docmind.service.retrieval.ReciprocalRankFusion;
import com.example.docmind.service.retrieval.RetrievalService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P3.1 — Outbound Delivery Reliability verification suite covering all 12 mandatory scenarios:
 * <ol>
 *   <li>Outbound creation -> initial status {@code PENDING} (never {@code DELIVERED} before channel call).</li>
 *   <li>Facebook API 2xx acceptance -> {@code ACCEPTED_BY_CHANNEL} (never equated to {@code DELIVERED}).</li>
 *   <li>Delivery receipt webhook -> {@code DELIVERED}.</li>
 *   <li>Explicit Facebook rejection (4xx/5xx) -> {@code FAILED}.</li>
 *   <li>Request timeout -> {@code UNKNOWN} (and never auto-retried).</li>
 *   <li>JVM crash after starting send ({@code IN_FLIGHT}) -> recovery marks {@code UNKNOWN} and does not duplicate-send.</li>
 *   <li>Expired {@code IN_FLIGHT} lease -> cautious recovery to {@code UNKNOWN} without re-sending.</li>
 *   <li>{@code HUMAN_TAKEOVER} -> {@code SUPPRESSED_BY_FENCE}, 0 Facebook API calls.</li>
 *   <li>{@code OPT_OUT} -> {@code SUPPRESSED_BY_FENCE}, 0 Facebook API calls.</li>
 *   <li>Newer inbound invalidates older outbound -> {@code SUPPRESSED_BY_FENCE}.</li>
 *   <li>Duplicate webhook delivery -> idempotent, no duplicate state or error.</li>
 *   <li>Delivery receipt arrives before HTTP response processing completes -> monotonic guard preserves {@code DELIVERED}.</li>
 * </ol>
 */
class P31OutboundDeliveryReliabilityTest {

    private static final UUID PAGE_ID = UUID.fromString("b23cd382-4f7a-4829-8c54-1d410da14bf8");
    private static final UUID DOC_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @BeforeEach
    void setUp() {
        SentBubbleLedger.useStore(new SentBubbleLedger.InMemoryStore());
        SentBubbleLedger.clear();
        ConversationState.clearSessionCache();
        SenderEpochRegistry.clearAll();
    }

    @AfterEach
    void tearDown() {
        SentBubbleLedger.useStore(new SentBubbleLedger.InMemoryStore());
        SentBubbleLedger.clear();
        ConversationState.clearSessionCache();
        SenderEpochRegistry.clearAll();
    }

    private static FanpageBotRequest buildBotRequest(String senderId, String customerName, String message, String convId) {
        return new FanpageBotRequest(
                PAGE_ID.toString(),
                senderId,
                customerName,
                message,
                null,
                List.of(),
                List.of(DOC_ID),
                5,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                convId,
                null
        );
    }

    private static FanpageBotResponse buildBotResponse(List<String> bubbleTexts) {
        String joined = String.join("\n", bubbleTexts);
        List<FanpageBotResponse.ReplyBubble> bubbles = bubbleTexts.stream()
                .map(t -> new FanpageBotResponse.ReplyBubble(t, 0))
                .toList();
        return new FanpageBotResponse(
                joined,
                bubbles,
                null,
                "STAGE_1",
                0.9,
                false,
                null,
                null,
                null,
                List.of(),
                null,
                List.of(),
                List.of(),
                false,
                null,
                null
        );
    }

    private static FanpageBotService buildBotServiceWithBubbles(List<String> bubbles) {
        String hairAndEarTable = """
                | Mã gói | Tên gói | Giá | Tổng thời gian | Bao gồm | Không gian | KTV | Điểm nổi bật | Phù hợp |
                | --- | --- | --- | --- | --- | --- | --- | --- | --- |
                | DS01 | Lấy ráy tai cơ bản | 129.000VNĐ | 30 phút | Lấy ráy tai thư giãn + vệ sinh tai ngoài + chườm ấm + ăn nhẹ | Giường có rèm che riêng kín đáo | KTV dưỡng sinh | Nhẹ nhàng êm tai, tiết kiệm thời gian | Khách muốn trải nghiệm thử nhẹ nhàng |
                | DS02 | Gói dưỡng sinh cơ bản | 239.000VNĐ | 60 phút | Rửa mặt + massage mặt + gội thảo dược + chườm thảo dược cổ vai + ăn nhẹ | Giường có rèm che riêng kín đáo | KTV dưỡng sinh | Thư giãn đầu cổ vai nhẹ nhàng | Khách cần thư giãn nhanh, tiết kiệm |
                | NS01 | Massage cơ bản | 399.000VNĐ | 60 phút | Full body massage trị liệu | Giường có rèm che riêng kín đáo | KTV tiêu chuẩn | Massage 60 phút + buffet thực dưỡng | Khách thư giãn toàn thân |
                """;
        List<ReciprocalRankFusion.FusedChunk> sampleChunks = List.of(
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
        RetrievalService retrievalService = Mockito.mock(RetrievalService.class);
        Mockito.when(retrievalService.retrieveWithRules(
                        Mockito.any(), Mockito.anyString(), Mockito.anyInt(), Mockito.anyList(), Mockito.any()))
                .thenReturn(new RetrievalService.RetrievalResult(sampleChunks, List.of()));

        KnowledgePageRepository pageRepo = Mockito.mock(KnowledgePageRepository.class);
        KnowledgePageEntity pageEntity = new KnowledgePageEntity();
        pageEntity.setId(PAGE_ID);
        pageEntity.setName("Nàng Sen");
        pageEntity.setIndustry("BEAUTY_SPA");
        pageEntity.setPersonaConfig(PagePersonaConfig.empty());
        Mockito.when(pageRepo.findById(PAGE_ID)).thenReturn(Optional.of(pageEntity));

        String bubblesJson = bubbles.stream()
                .map(b -> "\"" + b.replace("\"", "\\\"") + "\"")
                .reduce((a, b) -> a + ", " + b)
                .orElse("\"Dạ chào chị ạ\"");

        LlmClient llmClient = Mockito.mock(LlmClient.class);
        Mockito.when(llmClient.name()).thenReturn("mock-p31-aci-v2");
        Mockito.when(llmClient.complete(Mockito.anyString(), Mockito.any(ReasoningBudget.class)))
                .thenReturn("""
                        {
                          "brain_reflection": {
                            "conversational_context": {"current_topic": "Tư vấn", "unresolved_requests": []},
                            "customer_understanding": {"turn_intent": "GENERAL_GREETING", "conversation_goal": "EXPLORE", "interaction_permission": "OPEN", "buying_signal": "WEAK", "cost_concern_expressed": false, "booking_readiness": "EXPLORING"},
                            "priority": "CLARIFICATION",
                            "working_hypothesis": {"hypothesis": "Khách hỏi thông tin", "barrier_type": "NONE", "confidence": 0.85, "lifecycle_op": "MAINTAIN"},
                            "response_plan": {"strategy": "Helpful Reply", "tone": "LEVEL_1_NATURAL", "depth": "CONCISE", "cta_level": "SOFT_CTA", "next_action": "ASK_ONE_CLARIFYING_QUESTION", "adaptation": "CONTINUATION", "previous_outcome": "NOT_APPLICABLE"},
                            "activated_skill_codes": [],
                            "handoff_request": {"requested": false, "reason": null},
                            "proposed_booking_patch": {"selected_product_id": null, "proposed_date": null, "proposed_time": null}
                          },
                          "bubbles": [%s]
                        }
                        """.formatted(bubblesJson));

        return new FanpageBotService(
                retrievalService, llmClient, new ChatProperties(), null, pageRepo,
                null, null, null, null, null
        );
    }

    @Test
    @DisplayName("Scenario 1: Tạo outbound mới -> trạng thái ban đầu là PENDING (không bao giờ ghi DELIVERED trước khi gọi Facebook API)")
    void scenario01_outboundCreationRecordsPendingAndNeverDeliveredBeforeChannelCall() {
        FanpageBotService botService = buildBotServiceWithBubbles(
                List.of("Dạ gói Massage cơ bản 60 phút bên em giá 399k ạ.", "Chị muốn đặt khung giờ nào ạ?")
        );
        String senderId = "psid_p31_sc01";
        String convId = "conv_p31_sc01";
        FanpageBotRequest req = buildBotRequest(senderId, "Chị Lan", "Cho chị hỏi giá gói massage cơ bản", convId);

        FanpageBotResponse resp = botService.handleCustomerMessage(req);
        assertThat(resp.replyBubbles()).hasSize(2);
        assertThat(botService.getLastTurnSummary().responseStatus()).isEqualTo("PENDING");

        String ledgerKey = SentBubbleLedger.key(PAGE_ID.toString(), senderId, convId);
        List<SentBubbleLedger.TurnRecord> snapshot = SentBubbleLedger.snapshot(ledgerKey);
        assertThat(snapshot).hasSize(2);
        SentBubbleLedger.TurnRecord assistantTurn = snapshot.get(1);
        assertThat(assistantTurn.role()).isEqualTo("assistant");
        assertThat(assistantTurn.bubbles()).hasSize(2);
        assertThat(assistantTurn.bubbles())
                .extracting(SentBubbleLedger.BubbleRecord::status)
                .containsExactly(SentBubbleLedger.DeliveryStatus.PENDING, SentBubbleLedger.DeliveryStatus.PENDING);
        assertThat(assistantTurn.bubbles())
                .extracting(SentBubbleLedger.BubbleRecord::attemptCount)
                .containsExactly(0, 0);

        // Chưa gửi qua kênh -> reconcile không được coi các bong bóng PENDING là đã hiển thị cho khách
        List<FanpageBotRequest.ConversationTurn> reconciledBeforeSend = SentBubbleLedger.reconcile(ledgerKey, List.of());
        assertThat(reconciledBeforeSend)
                .extracting(FanpageBotRequest.ConversationTurn::role)
                .containsExactly("user");
    }

    @Test
    @DisplayName("Scenario 2 & 3: Facebook API 2xx -> ACCEPTED_BY_CHANNEL (không phải DELIVERED); nhận Delivery Receipt -> DELIVERED")
    void scenario02_and_03_facebook2xxMarksAcceptedByChannelAndReceiptMarksDelivered() throws Exception {
        FanpageBotService botService = buildBotServiceWithBubbles(
                List.of("Dạ bên em mở cửa từ 9h sáng đến 21h tối ạ.", "Mình ghé tầm mấy giờ để em giữ lịch ạ?")
        );
        FanpageBotController controller = new FanpageBotController(botService);
        controller.setPageAccessToken("EAAB_P31_TEST_TOKEN");
        controller.setInterBubbleDelayMs(0L);

        AtomicInteger callSeq = new AtomicInteger(0);
        controller.setGraphApiSender((url, jsonBody) -> {
            if (jsonBody.contains("sender_action")) {
                return new FanpageBotController.HttpResponseResult(200, "{\"recipient_id\":\"psid_p31_sc02\"}");
            }
            int n = callSeq.incrementAndGet();
            return new FanpageBotController.HttpResponseResult(
                    200,
                    "{\"recipient_id\":\"psid_p31_sc02\",\"message_id\":\"m_out_sc02_" + n + "\"}"
            );
        });

        String senderId = "psid_p31_sc02";
        String convId = "fb_webhook_" + senderId;
        FanpageBotRequest req = buildBotRequest(senderId, "Chị Mai", "Mấy giờ đóng cửa?", convId);
        FanpageBotResponse resp = botService.handleCustomerMessage(req);
        int sentCount = controller.sendSequentialBubblesToFacebook(PAGE_ID.toString(), senderId, convId, resp);
        assertThat(sentCount).isEqualTo(2);

        String ledgerKey = SentBubbleLedger.key(PAGE_ID.toString(), senderId, convId);
        List<SentBubbleLedger.TurnRecord> after2xx = SentBubbleLedger.snapshot(ledgerKey);
        SentBubbleLedger.TurnRecord assistantTurnAfter2xx = after2xx.get(1);
        assertThat(assistantTurnAfter2xx.bubbles())
                .extracting(SentBubbleLedger.BubbleRecord::status)
                .as("HTTP 2xx chỉ được ghi ACCEPTED_BY_CHANNEL, tuyệt đối chưa phải DELIVERED")
                .containsExactly(
                        SentBubbleLedger.DeliveryStatus.ACCEPTED_BY_CHANNEL,
                        SentBubbleLedger.DeliveryStatus.ACCEPTED_BY_CHANNEL
                );
        assertThat(assistantTurnAfter2xx.bubbles())
                .extracting(SentBubbleLedger.BubbleRecord::externalMid)
                .containsExactly("m_out_sc02_1", "m_out_sc02_2");

        String midKey = SentBubbleLedger.midLedgerKey(PAGE_ID.toString(), senderId);
        List<SentBubbleLedger.TurnRecord> midRecordsBeforeReceipt = SentBubbleLedger.snapshot(midKey);
        assertThat(midRecordsBeforeReceipt).isNotEmpty();
        assertThat(midRecordsBeforeReceipt.get(0).bubbles().get(0).status())
                .isEqualTo(SentBubbleLedger.DeliveryStatus.ACCEPTED_BY_CHANNEL);

        // Scenario 3: Gửi webhook delivery receipt thực tế cho m_out_sc02_1
        String deliveryWebhookPayload1 = """
                {
                  "object": "page",
                  "entry": [{
                    "id": "12218945612345",
                    "time": 1730000005000,
                    "messaging": [{
                      "sender": { "id": "psid_p31_sc02" },
                      "recipient": { "id": "12218945612345" },
                      "timestamp": 1730000005100,
                      "delivery": {
                        "mids": ["m_out_sc02_1"],
                        "watermark": 1730000005100
                      }
                    }]
                  }]
                }
                """;
        controller.receiveMetaWebhook(null, null, deliveryWebhookPayload1);

        List<SentBubbleLedger.BubbleRecord> afterFirstReceipt = SentBubbleLedger.snapshot(ledgerKey).get(1).bubbles();
        assertThat(afterFirstReceipt.get(0).status()).isEqualTo(SentBubbleLedger.DeliveryStatus.DELIVERED);
        assertThat(afterFirstReceipt.get(1).status()).isEqualTo(SentBubbleLedger.DeliveryStatus.ACCEPTED_BY_CHANNEL);

        // Gửi delivery receipt theo watermark (không có mảng mids) -> nâng nốt bong bóng 2 lên DELIVERED
        String deliveryWebhookWatermarkOnly = """
                {
                  "object": "page",
                  "entry": [{
                    "id": "12218945612345",
                    "time": 1730000006000,
                    "messaging": [{
                      "sender": { "id": "psid_p31_sc02" },
                      "recipient": { "id": "12218945612345" },
                      "timestamp": 1730000006100,
                      "delivery": {
                        "watermark": 4102444800000
                      }
                    }]
                  }]
                }
                """;
        controller.receiveMetaWebhook(null, null, deliveryWebhookWatermarkOnly);

        List<SentBubbleLedger.BubbleRecord> afterSecondReceipt = SentBubbleLedger.snapshot(ledgerKey).get(1).bubbles();
        assertThat(afterSecondReceipt)
                .extracting(SentBubbleLedger.BubbleRecord::status)
                .containsExactly(SentBubbleLedger.DeliveryStatus.DELIVERED, SentBubbleLedger.DeliveryStatus.DELIVERED);
    }

    @Test
    @DisplayName("Scenario 4: Facebook từ chối rõ ràng (HTTP 4xx/5xx) -> FAILED và dừng chuỗi bong bóng")
    void scenario04_explicitFacebookRejection4xx5xxTransitionsToFailed() {
        FanpageBotController controller = new FanpageBotController(Mockito.mock(FanpageBotService.class));
        controller.setPageAccessToken("EAAB_P31_TEST_TOKEN");
        controller.setInterBubbleDelayMs(0L);

        AtomicInteger sendAttempts = new AtomicInteger(0);
        controller.setGraphApiSender((url, jsonBody) -> {
            sendAttempts.incrementAndGet();
            return new FanpageBotController.HttpResponseResult(400, "{\"error\":{\"message\":\"Invalid OAuth access token.\"}}");
        });

        String senderId = "psid_p31_sc04";
        String convId = "conv_p31_sc04";
        String ledgerKey = SentBubbleLedger.key(PAGE_ID.toString(), senderId, convId);
        SentBubbleLedger.recordCustomerTurn(ledgerKey, "t_sc04", "Chào shop");
        SentBubbleLedger.recordAssistantTurn(
                ledgerKey, "t_sc04:a", List.of("Bong bóng 1", "Bong bóng 2"), SentBubbleLedger.DeliveryStatus.PENDING
        );

        FanpageBotResponse botResp = buildBotResponse(List.of("Bong bóng 1", "Bong bóng 2"));

        int sent = controller.sendSequentialBubblesToFacebook(PAGE_ID.toString(), senderId, convId, botResp);
        assertThat(sent).isZero();
        assertThat(sendAttempts.get()).isEqualTo(1);

        List<SentBubbleLedger.BubbleRecord> bubbles = SentBubbleLedger.snapshot(ledgerKey).get(1).bubbles();
        assertThat(bubbles.get(0).status()).isEqualTo(SentBubbleLedger.DeliveryStatus.FAILED);
        assertThat(bubbles.get(0).failureReason()).isEqualTo("HTTP_400_REJECTED");
        assertThat(bubbles.get(0).attemptCount()).isEqualTo(1);
        assertThat(bubbles.get(1).status()).isEqualTo(SentBubbleLedger.DeliveryStatus.SUPPRESSED_BY_FENCE);
        assertThat(bubbles.get(1).failureReason()).isEqualTo("ABORTED_AFTER_PRIOR_BUBBLE_FAILED");

        // Bong bóng FAILED không được hiển thị như đã gửi trong lịch sử hội thoại
        assertThat(SentBubbleLedger.reconcile(ledgerKey, List.of()))
                .extracting(FanpageBotRequest.ConversationTurn::role)
                .containsExactly("user");
    }

    @Test
    @DisplayName("Scenario 5: Request timeout -> UNKNOWN và tuyệt đối KHÔNG tự động retry")
    void scenario05_requestTimeoutTransitionsToUnknownAndNeverAutoRetries() {
        FanpageBotController controller = new FanpageBotController(Mockito.mock(FanpageBotService.class));
        controller.setPageAccessToken("EAAB_P31_TEST_TOKEN");
        controller.setInterBubbleDelayMs(0L);

        AtomicInteger sendAttempts = new AtomicInteger(0);
        controller.setGraphApiSender((url, jsonBody) -> {
            sendAttempts.incrementAndGet();
            throw new SocketTimeoutException("Read timed out waiting for Facebook Graph API");
        });

        String senderId = "psid_p31_sc05";
        String convId = "conv_p31_sc05";
        String ledgerKey = SentBubbleLedger.key(PAGE_ID.toString(), senderId, convId);
        SentBubbleLedger.recordCustomerTurn(ledgerKey, "t_sc05", "Tư vấn giúp chị");
        SentBubbleLedger.recordAssistantTurn(
                ledgerKey, "t_sc05:a", List.of("Dạ em chào chị ạ"), SentBubbleLedger.DeliveryStatus.PENDING
        );

        FanpageBotResponse botResp = buildBotResponse(List.of("Dạ em chào chị ạ"));

        int firstCallSent = controller.sendSequentialBubblesToFacebook(PAGE_ID.toString(), senderId, convId, botResp);
        assertThat(firstCallSent).isZero();
        assertThat(sendAttempts.get()).isEqualTo(1);

        SentBubbleLedger.BubbleRecord bubbleAfterTimeout = SentBubbleLedger.snapshot(ledgerKey).get(1).bubbles().get(0);
        assertThat(bubbleAfterTimeout.status()).isEqualTo(SentBubbleLedger.DeliveryStatus.UNKNOWN);
        assertThat(bubbleAfterTimeout.attemptCount()).isEqualTo(1);
        assertThat(bubbleAfterTimeout.failureReason()).contains("SocketTimeoutException");

        // Gọi lại lần 2 -> không được phép auto-retry bản ghi đang ở trạng thái UNKNOWN
        int secondCallSent = controller.sendSequentialBubblesToFacebook(PAGE_ID.toString(), senderId, convId, botResp);
        assertThat(secondCallSent).isZero();
        assertThat(sendAttempts.get())
                .as("Tuyệt đối không tự động retry outbound ở trạng thái UNKNOWN")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Scenario 6 & 7: JVM crash sau khi bắt đầu gửi (IN_FLIGHT) & hết hạn lease -> chuyển sang UNKNOWN và chống gửi trùng")
    void scenario06_and_07_jvmCrashDuringInFlightAndExpiredLeaseRecoversToUnknownWithoutDuplicateSend() {
        FanpageBotController controller = new FanpageBotController(Mockito.mock(FanpageBotService.class));
        controller.setPageAccessToken("EAAB_P31_TEST_TOKEN");
        controller.setInterBubbleDelayMs(0L);
        controller.setInFlightLeaseDuration(Duration.ofMillis(60));

        String senderId = "psid_p31_sc06";
        String convId = "conv_p31_sc06";
        String ledgerKey = SentBubbleLedger.key(PAGE_ID.toString(), senderId, convId);
        SentBubbleLedger.recordCustomerTurn(ledgerKey, "t_sc06", "Đặt lịch chiều nay");
        SentBubbleLedger.recordAssistantTurn(
                ledgerKey, "t_sc06:a", List.of("Dạ chị cho em xin SĐT để giữ lịch ạ"), SentBubbleLedger.DeliveryStatus.PENDING
        );

        AtomicInteger channelCalls = new AtomicInteger(0);
        controller.setGraphApiSender((url, jsonBody) -> {
            channelCalls.incrementAndGet();
            // Tại thời điểm đang gọi Facebook API, trạng thái trong Ledger bắt buộc phải là IN_FLIGHT với attemptCount = 1
            SentBubbleLedger.BubbleRecord inFlightRecord = SentBubbleLedger.snapshot(ledgerKey).get(1).bubbles().get(0);
            assertThat(inFlightRecord.status()).isEqualTo(SentBubbleLedger.DeliveryStatus.IN_FLIGHT);
            assertThat(inFlightRecord.attemptCount()).isEqualTo(1);
            assertThat(inFlightRecord.leaseUntil()).isNotNull();
            throw new RuntimeException("SIMULATED_JVM_CRASH_AFTER_IN_FLIGHT_HTTP_SEND");
        });

        FanpageBotResponse botResp = buildBotResponse(List.of("Dạ chị cho em xin SĐT để giữ lịch ạ"));

        assertThatThrownBy(() -> controller.sendSequentialBubblesToFacebook(PAGE_ID.toString(), senderId, convId, botResp))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("SIMULATED_JVM_CRASH");
        assertThat(channelCalls.get()).isEqualTo(1);

        // Ngay sau crash (khi lease vẫn chưa hết), bản ghi đang nằm ở trạng thái IN_FLIGHT
        SentBubbleLedger.BubbleRecord rightAfterCrash = SentBubbleLedger.snapshot(ledgerKey).get(1).bubbles().get(0);
        assertThat(rightAfterCrash.status()).isEqualTo(SentBubbleLedger.DeliveryStatus.IN_FLIGHT);
        assertThat(rightAfterCrash.attemptCount()).isEqualTo(1);

        // Scenario 7: Chờ lease IN_FLIGHT hết hạn -> cơ chế recovery chuyển sang UNKNOWN và không gửi lại
        int recoveredCount = SentBubbleLedger.recoverAllExpiredInFlightLeases(Instant.now().plusSeconds(5));
        assertThat(recoveredCount).isEqualTo(1);

        SentBubbleLedger.BubbleRecord afterLeaseRecovery = SentBubbleLedger.snapshot(ledgerKey).get(1).bubbles().get(0);
        assertThat(afterLeaseRecovery.status()).isEqualTo(SentBubbleLedger.DeliveryStatus.UNKNOWN);
        assertThat(afterLeaseRecovery.failureReason()).isEqualTo("IN_FLIGHT_LEASE_EXPIRED_CRASH_RECOVERY");

        // Thử chạy lại dispatcher sau khi hệ thống khởi động lại -> KHÔNG gửi lại bản ghi UNKNOWN
        controller.setGraphApiSender((url, jsonBody) -> {
            channelCalls.incrementAndGet();
            return new FanpageBotController.HttpResponseResult(200, "{\"message_id\":\"m_should_not_send\"}");
        });
        int postRecoverySent = controller.sendSequentialBubblesToFacebook(PAGE_ID.toString(), senderId, convId, botResp);
        assertThat(postRecoverySent).isZero();
        assertThat(channelCalls.get())
                .as("Recovery sau crash tuyệt đối không được gửi trùng bản ghi đã từng bắt đầu gửi (attemptCount >= 1)")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Scenario 8: Phiên chuyển sang HUMAN_TAKEOVER trước khi gửi -> SUPPRESSED_BY_FENCE và không gọi Facebook API")
    void scenario08_humanTakeoverBeforeSendMarksSuppressedByFenceAndSkipsChannel() {
        InboundSessionPersistenceService mockPersistence = Mockito.mock(InboundSessionPersistenceService.class);
        Mockito.when(mockPersistence.evaluateOutboundFenceFresh(Mockito.eq(PAGE_ID), Mockito.eq("psid_p31_sc08"), Mockito.any()))
                .thenReturn(InboundSessionPersistenceService.OutboundFenceDecision.block("SUPPRESSED_BY_HUMAN_TAKEOVER"));

        FanpageBotService botService = buildBotServiceWithBubbles(List.of("Dạ bên em có gói 399k ạ."));
        botService.setInboundSessionPersistenceService(mockPersistence);

        FanpageBotController controller = new FanpageBotController(botService);
        controller.setPageAccessToken("EAAB_P31_TEST_TOKEN");
        controller.setInterBubbleDelayMs(0L);

        AtomicInteger channelCalls = new AtomicInteger(0);
        controller.setGraphApiSender((url, jsonBody) -> {
            channelCalls.incrementAndGet();
            return new FanpageBotController.HttpResponseResult(200, "{\"message_id\":\"m_never\"}");
        });

        String senderId = "psid_p31_sc08";
        String convId = "conv_p31_sc08";
        String ledgerKey = SentBubbleLedger.key(PAGE_ID.toString(), senderId, convId);
        SentBubbleLedger.recordCustomerTurn(ledgerKey, "t_sc08", "Tư vấn giá");
        SentBubbleLedger.recordAssistantTurn(
                ledgerKey, "t_sc08:a", List.of("Dạ bên em có gói 399k ạ.", "Mình muốn ghé giờ nào ạ?"), SentBubbleLedger.DeliveryStatus.PENDING
        );

        FanpageBotResponse botResp = buildBotResponse(List.of("Dạ bên em có gói 399k ạ.", "Mình muốn ghé giờ nào ạ?"));

        int sent = controller.sendSequentialBubblesToFacebook(PAGE_ID.toString(), senderId, convId, botResp);
        assertThat(sent).isZero();
        assertThat(channelCalls.get()).as("Không được gọi Facebook API khi HUMAN_TAKEOVER").isZero();

        List<SentBubbleLedger.BubbleRecord> bubbles = SentBubbleLedger.snapshot(ledgerKey).get(1).bubbles();
        assertThat(bubbles)
                .extracting(SentBubbleLedger.BubbleRecord::status)
                .containsExactly(
                        SentBubbleLedger.DeliveryStatus.SUPPRESSED_BY_FENCE,
                        SentBubbleLedger.DeliveryStatus.SUPPRESSED_BY_FENCE
                );
        assertThat(bubbles.get(0).failureReason()).isEqualTo("SUPPRESSED_BY_HUMAN_TAKEOVER");
    }

    @Test
    @DisplayName("Scenario 9: Khách OPT_OUT trước khi gửi -> SUPPRESSED_BY_FENCE và không gọi Facebook API")
    void scenario09_optOutBeforeSendMarksSuppressedByFenceAndSkipsChannel() {
        InboundSessionPersistenceService mockPersistence = Mockito.mock(InboundSessionPersistenceService.class);
        Mockito.when(mockPersistence.evaluateOutboundFenceFresh(Mockito.eq(PAGE_ID), Mockito.eq("psid_p31_sc09"), Mockito.any()))
                .thenReturn(InboundSessionPersistenceService.OutboundFenceDecision.block("SUPPRESSED_BY_OPT_OUT"));

        FanpageBotService botService = buildBotServiceWithBubbles(List.of("Dạ bên em có ưu đãi ạ."));
        botService.setInboundSessionPersistenceService(mockPersistence);

        FanpageBotController controller = new FanpageBotController(botService);
        controller.setPageAccessToken("EAAB_P31_TEST_TOKEN");
        controller.setInterBubbleDelayMs(0L);

        AtomicInteger channelCalls = new AtomicInteger(0);
        controller.setGraphApiSender((url, jsonBody) -> {
            channelCalls.incrementAndGet();
            return new FanpageBotController.HttpResponseResult(200, "{\"message_id\":\"m_never\"}");
        });

        String senderId = "psid_p31_sc09";
        String convId = "conv_p31_sc09";
        String ledgerKey = SentBubbleLedger.key(PAGE_ID.toString(), senderId, convId);
        SentBubbleLedger.recordCustomerTurn(ledgerKey, "t_sc09", "Đừng nhắn nữa");
        SentBubbleLedger.recordAssistantTurn(
                ledgerKey, "t_sc09:a", List.of("Dạ bên em có ưu đãi ạ."), SentBubbleLedger.DeliveryStatus.PENDING
        );

        FanpageBotResponse botResp = buildBotResponse(List.of("Dạ bên em có ưu đãi ạ."));

        int sent = controller.sendSequentialBubblesToFacebook(PAGE_ID.toString(), senderId, convId, botResp);
        assertThat(sent).isZero();
        assertThat(channelCalls.get()).as("Không được gọi Facebook API khi khách đã OPT_OUT").isZero();

        SentBubbleLedger.BubbleRecord bubble = SentBubbleLedger.snapshot(ledgerKey).get(1).bubbles().get(0);
        assertThat(bubble.status()).isEqualTo(SentBubbleLedger.DeliveryStatus.SUPPRESSED_BY_FENCE);
        assertThat(bubble.failureReason()).isEqualTo("SUPPRESSED_BY_OPT_OUT");
    }

    @Test
    @DisplayName("Scenario 10: Tin nhắn mới của khách đến làm mất hiệu lực phản hồi cũ chưa gửi -> SUPPRESSED_BY_FENCE")
    void scenario10_newerInboundInvalidatesOlderPendingOutboundAndMarksSuppressedByFence() {
        String senderId = "psid_p31_sc10";
        String convId = "conv_p31_sc10";
        String ledgerKey = SentBubbleLedger.key(PAGE_ID.toString(), senderId, convId);

        // Turn 1 đã tạo phản hồi PENDING nhưng chưa kịp gửi ra kênh thì Turn 2 của khách đã tới
        SentBubbleLedger.recordCustomerTurn(ledgerKey, "t_sc10_1", "Hỏi giá gội đầu");
        SentBubbleLedger.recordAssistantTurn(
                ledgerKey, "t_sc10_1:a", List.of("Gội đầu dưỡng sinh giá 199k ạ"), SentBubbleLedger.DeliveryStatus.PENDING
        );
        SentBubbleLedger.recordCustomerTurn(ledgerKey, "t_sc10_2", "À thôi cho chị hỏi gói trị liệu cổ vai gáy đi");

        InboundSessionPersistenceService mockPersistence = Mockito.mock(InboundSessionPersistenceService.class);
        Mockito.when(mockPersistence.evaluateOutboundFenceFresh(Mockito.eq(PAGE_ID), Mockito.eq(senderId), Mockito.eq("mid_in_1")))
                .thenReturn(InboundSessionPersistenceService.OutboundFenceDecision.block("SUPERSEDED_BY_NEWER_INBOUND"));

        FanpageBotService botService = buildBotServiceWithBubbles(List.of("Gội đầu dưỡng sinh giá 199k ạ"));
        botService.setInboundSessionPersistenceService(mockPersistence);

        FanpageBotController controller = new FanpageBotController(botService);
        controller.setPageAccessToken("EAAB_P31_TEST_TOKEN");
        controller.setInterBubbleDelayMs(0L);

        AtomicInteger channelCalls = new AtomicInteger(0);
        controller.setGraphApiSender((url, jsonBody) -> {
            channelCalls.incrementAndGet();
            return new FanpageBotController.HttpResponseResult(200, "{\"message_id\":\"m_stale\"}");
        });

        FanpageBotResponse staleBotResp = buildBotResponse(List.of("Gội đầu dưỡng sinh giá 199k ạ"))
                .withInboundExternalMid("mid_in_1");

        int sent = controller.sendSequentialBubblesToFacebook(PAGE_ID.toString(), senderId, convId, staleBotResp);
        assertThat(sent).isZero();
        assertThat(channelCalls.get()).as("Không được gửi phản hồi cũ đã bị thay thế bởi tin nhắn mới hơn").isZero();

        SentBubbleLedger.BubbleRecord suppressedBubble = SentBubbleLedger.snapshot(ledgerKey).get(1).bubbles().get(0);
        assertThat(suppressedBubble.status()).isEqualTo(SentBubbleLedger.DeliveryStatus.SUPPRESSED_BY_FENCE);
    }

    @Test
    @DisplayName("Scenario 11: Webhook bị gửi trùng (cả inbound message và delivery receipt) -> xử lý idempotent, không nhân đôi trạng thái")
    void scenario11_duplicateWebhookDeliveryIsIdempotent() throws Exception {
        FanpageBotService botService = buildBotServiceWithBubbles(
                List.of("Dạ bên em ở 123 Nguyễn Huệ, Quận 1 ạ.")
        );
        FanpageBotController controller = new FanpageBotController(botService);
        controller.setPageAccessToken("EAAB_P31_TEST_TOKEN");
        controller.setInterBubbleDelayMs(0L);

        AtomicInteger messageSendCount = new AtomicInteger(0);
        controller.setGraphApiSender((url, jsonBody) -> {
            if (jsonBody.contains("sender_action")) {
                return new FanpageBotController.HttpResponseResult(200, "{\"recipient_id\":\"psid_p31_sc11\"}");
            }
            int c = messageSendCount.incrementAndGet();
            return new FanpageBotController.HttpResponseResult(200, "{\"recipient_id\":\"psid_p31_sc11\",\"message_id\":\"m_out_sc11_" + c + "\"}");
        });

        String inboundWebhook = """
                {
                  "object": "page",
                  "entry": [{
                    "id": "12218945612345",
                    "time": 1730000011000,
                    "messaging": [{
                      "sender": { "id": "psid_p31_sc11" },
                      "recipient": { "id": "12218945612345" },
                      "timestamp": 1730000011100,
                      "message": {
                        "mid": "m_in_sc11_01",
                        "text": "Địa chỉ ở đâu?"
                      }
                    }]
                  }]
                }
                """;
        var resp1 = controller.receiveMetaWebhook(null, null, inboundWebhook);
        var resp2 = controller.receiveMetaWebhook(null, null, inboundWebhook);
        assertThat(resp1.getStatusCode().value()).isEqualTo(200);
        assertThat(resp2.getStatusCode().value()).isEqualTo(200);
        assertThat(resp2.getBody().get("deduplicated_events")).isEqualTo(1);
        assertThat(messageSendCount.get()).as("Inbound webhook trùng chỉ được gửi outbound 1 lần").isEqualTo(1);

        String deliveryWebhook = """
                {
                  "object": "page",
                  "entry": [{
                    "id": "12218945612345",
                    "time": 1730000012000,
                    "messaging": [{
                      "sender": { "id": "psid_p31_sc11" },
                      "recipient": { "id": "12218945612345" },
                      "timestamp": 1730000012100,
                      "delivery": {
                        "mids": ["m_out_sc11_1"],
                        "watermark": 1730000012100
                      }
                    }]
                  }]
                }
                """;
        var delResp1 = controller.receiveMetaWebhook(null, null, deliveryWebhook);
        var delResp2 = controller.receiveMetaWebhook(null, null, deliveryWebhook);
        assertThat(delResp1.getStatusCode().value()).isEqualTo(200);
        assertThat(delResp2.getStatusCode().value()).isEqualTo(200);

        String ledgerKey = SentBubbleLedger.key(PAGE_ID.toString(), "psid_p31_sc11", "fb_webhook_psid_p31_sc11");
        List<SentBubbleLedger.TurnRecord> turns = SentBubbleLedger.snapshot(ledgerKey);
        assertThat(turns).hasSize(2);
        assertThat(turns.get(1).bubbles()).hasSize(1);
        assertThat(turns.get(1).bubbles().get(0).status()).isEqualTo(SentBubbleLedger.DeliveryStatus.DELIVERED);
    }

    @Test
    @DisplayName("Scenario 12: Delivery receipt đến trước khi xử lý xong HTTP response -> bảo vệ monotonic không lùi DELIVERED về ACCEPTED_BY_CHANNEL")
    void scenario12_deliveryReceiptArrivingBeforeHttpResponseCompletesPreservesDeliveredMonotonically() {
        FanpageBotController controller = new FanpageBotController(Mockito.mock(FanpageBotService.class));
        controller.setPageAccessToken("EAAB_P31_TEST_TOKEN");
        controller.setInterBubbleDelayMs(0L);

        String senderId = "psid_p31_sc12";
        String convId = "fb_webhook_" + senderId;
        String ledgerKey = SentBubbleLedger.key(PAGE_ID.toString(), senderId, convId);
        SentBubbleLedger.recordCustomerTurn(ledgerKey, "t_sc12", "Em muốn đặt lịch");
        SentBubbleLedger.recordAssistantTurn(
                ledgerKey, "t_sc12:a", List.of("Dạ chị cho em xin khung giờ nha"), SentBubbleLedger.DeliveryStatus.PENDING
        );

        controller.setGraphApiSender((url, jsonBody) -> {
            // Ngay trong lúc cuộc gọi HTTP đang chạy (trạng thái đang là IN_FLIGHT, chưa kịp xử lý kết quả trả về của postJson),
            // Facebook bắn webhook delivery receipt của m_race_sc12_01 đến trước!
            String earlyDeliveryReceiptPayload = """
                    {
                      "object": "page",
                      "entry": [{
                        "id": "12218945612345",
                        "time": 1730000015000,
                        "messaging": [{
                          "sender": { "id": "psid_p31_sc12" },
                          "recipient": { "id": "12218945612345" },
                          "timestamp": 1730000015100,
                          "delivery": {
                            "mids": ["m_race_sc12_01"],
                            "watermark": 1730000015100
                          }
                        }]
                      }]
                    }
                    """;
            controller.receiveMetaWebhook(null, null, earlyDeliveryReceiptPayload);

            // Sau đó luồng gửi HTTP mới nhận kết quả 200 OK kèm message_id = m_race_sc12_01
            return new FanpageBotController.HttpResponseResult(
                    200,
                    "{\"recipient_id\":\"psid_p31_sc12\",\"message_id\":\"m_race_sc12_01\"}"
            );
        });

        FanpageBotResponse botResp = buildBotResponse(List.of("Dạ chị cho em xin khung giờ nha"));

        int sent = controller.sendSequentialBubblesToFacebook(PAGE_ID.toString(), senderId, convId, botResp);
        assertThat(sent).isEqualTo(1);

        SentBubbleLedger.BubbleRecord finalBubble = SentBubbleLedger.snapshot(ledgerKey).get(1).bubbles().get(0);
        assertThat(finalBubble.status())
                .as("Monotonic state guard không bao giờ cho phép lùi từ DELIVERED về ACCEPTED_BY_CHANNEL")
                .isEqualTo(SentBubbleLedger.DeliveryStatus.DELIVERED);
        assertThat(finalBubble.externalMid()).isEqualTo("m_race_sc12_01");

        String midKey = SentBubbleLedger.midLedgerKey(PAGE_ID.toString(), senderId);
        List<SentBubbleLedger.TurnRecord> midTurns = SentBubbleLedger.snapshot(midKey);
        assertThat(midTurns).isNotEmpty();
        assertThat(midTurns.get(0).bubbles().get(0).status()).isEqualTo(SentBubbleLedger.DeliveryStatus.DELIVERED);
    }

    @Test
    @DisplayName("P3.1.1 — HTTP 429 Rate Limit -> ghi nhận HTTP_429_RATE_LIMITED, dừng chuỗi bong bóng và không retry mù")
    void p311_http429RateLimit_recordsRateLimitedAndNeverBlindRetries() {
        FanpageBotController controller = new FanpageBotController(Mockito.mock(FanpageBotService.class));
        controller.setPageAccessToken("EAAB_P311_TOKEN");
        controller.setInterBubbleDelayMs(0L);

        AtomicInteger sendAttempts = new AtomicInteger(0);
        controller.setGraphApiSender((url, jsonBody) -> {
            sendAttempts.incrementAndGet();
            return new FanpageBotController.HttpResponseResult(429, "{\"error\":{\"message\":\"(#613) Calls to this api have exceeded the rate limit.\"}}");
        });

        String senderId = "psid_p311_429";
        String convId = "conv_p311_429";
        String ledgerKey = SentBubbleLedger.key(PAGE_ID.toString(), senderId, convId);
        SentBubbleLedger.recordCustomerTurn(ledgerKey, "t_429", "Cho hỏi giá");
        SentBubbleLedger.recordAssistantTurn(
                ledgerKey, "t_429:a", List.of("Bong bóng 1", "Bong bóng 2"), SentBubbleLedger.DeliveryStatus.PENDING
        );

        FanpageBotResponse botResp = buildBotResponse(List.of("Bong bóng 1", "Bong bóng 2"));
        assertThat(controller.sendSequentialBubblesToFacebook(PAGE_ID.toString(), senderId, convId, botResp)).isZero();
        assertThat(sendAttempts.get()).isEqualTo(1);

        List<SentBubbleLedger.BubbleRecord> bubbles = SentBubbleLedger.snapshot(ledgerKey).get(1).bubbles();
        assertThat(bubbles.get(0).status()).isEqualTo(SentBubbleLedger.DeliveryStatus.FAILED);
        assertThat(bubbles.get(0).failureReason()).isEqualTo("HTTP_429_RATE_LIMITED");
        assertThat(bubbles.get(0).attemptCount()).isEqualTo(1);
        assertThat(bubbles.get(1).status()).isEqualTo(SentBubbleLedger.DeliveryStatus.SUPPRESSED_BY_FENCE);
        assertThat(bubbles.get(1).failureReason()).isEqualTo("ABORTED_AFTER_PRIOR_BUBBLE_FAILED");

        // Gọi lại lần 2 -> không retry mù
        assertThat(controller.sendSequentialBubblesToFacebook(PAGE_ID.toString(), senderId, convId, botResp)).isZero();
        assertThat(sendAttempts.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("P3.1.1 — HTTP 5xx, HTTP 408 và không nhận được response -> chuyển sang UNKNOWN (không phải FAILED) và không gửi lặp")
    void p311_http5xxAnd408AndNullResponse_transitionToUnknownAndNeverBlindRetry() {
        FanpageBotController controller = new FanpageBotController(Mockito.mock(FanpageBotService.class));
        controller.setPageAccessToken("EAAB_P311_TOKEN");
        controller.setInterBubbleDelayMs(0L);

        // 1. HTTP 503 Server Error -> UNKNOWN
        AtomicInteger attempts503 = new AtomicInteger(0);
        controller.setGraphApiSender((url, jsonBody) -> {
            attempts503.incrementAndGet();
            return new FanpageBotController.HttpResponseResult(503, "{\"error\":\"Service Unavailable\"}");
        });
        String sender503 = "psid_p311_503";
        String conv503 = "conv_p311_503";
        String key503 = SentBubbleLedger.key(PAGE_ID.toString(), sender503, conv503);
        SentBubbleLedger.recordCustomerTurn(key503, "t_503", "Tư vấn gói");
        SentBubbleLedger.recordAssistantTurn(
                key503, "t_503:a", List.of("Gói A 239k", "Chị muốn đặt giờ nào ạ?"), SentBubbleLedger.DeliveryStatus.PENDING
        );
        FanpageBotResponse resp503 = buildBotResponse(List.of("Gói A 239k", "Chị muốn đặt giờ nào ạ?"));
        assertThat(controller.sendSequentialBubblesToFacebook(PAGE_ID.toString(), sender503, conv503, resp503)).isZero();
        assertThat(attempts503.get()).isEqualTo(1);

        List<SentBubbleLedger.BubbleRecord> bubbles503 = SentBubbleLedger.snapshot(key503).get(1).bubbles();
        assertThat(bubbles503.get(0).status()).isEqualTo(SentBubbleLedger.DeliveryStatus.UNKNOWN);
        assertThat(bubbles503.get(0).failureReason()).isEqualTo("HTTP_503_SERVER_ERROR_UNCERTAIN");
        assertThat(bubbles503.get(1).status()).isEqualTo(SentBubbleLedger.DeliveryStatus.SUPPRESSED_BY_FENCE);
        assertThat(bubbles503.get(1).failureReason()).isEqualTo("ABORTED_AFTER_PRIOR_BUBBLE_UNKNOWN");

        // Không tự động retry UNKNOWN
        assertThat(controller.sendSequentialBubblesToFacebook(PAGE_ID.toString(), sender503, conv503, resp503)).isZero();
        assertThat(attempts503.get()).isEqualTo(1);

        // 2. HTTP 408 Request Timeout -> UNKNOWN
        controller.setGraphApiSender((url, jsonBody) ->
                new FanpageBotController.HttpResponseResult(408, "Request Timeout")
        );
        String sender408 = "psid_p311_408";
        String conv408 = "conv_p311_408";
        String key408 = SentBubbleLedger.key(PAGE_ID.toString(), sender408, conv408);
        SentBubbleLedger.recordCustomerTurn(key408, "t_408", "Hỏi lịch");
        SentBubbleLedger.recordAssistantTurn(
                key408, "t_408:a", List.of("Dạ bên em còn lịch ạ"), SentBubbleLedger.DeliveryStatus.PENDING
        );
        assertThat(controller.sendSequentialBubblesToFacebook(
                PAGE_ID.toString(), sender408, conv408, buildBotResponse(List.of("Dạ bên em còn lịch ạ")))).isZero();
        SentBubbleLedger.BubbleRecord bubble408 = SentBubbleLedger.snapshot(key408).get(1).bubbles().get(0);
        assertThat(bubble408.status()).isEqualTo(SentBubbleLedger.DeliveryStatus.UNKNOWN);
        assertThat(bubble408.failureReason()).isEqualTo("HTTP_408_REQUEST_TIMEOUT_UNCERTAIN");

        // 3. Không nhận được response (null) -> UNKNOWN
        controller.setGraphApiSender((url, jsonBody) -> null);
        String senderNull = "psid_p311_null";
        String convNull = "conv_p311_null";
        String keyNull = SentBubbleLedger.key(PAGE_ID.toString(), senderNull, convNull);
        SentBubbleLedger.recordCustomerTurn(keyNull, "t_null", "Hỏi địa chỉ");
        SentBubbleLedger.recordAssistantTurn(
                keyNull, "t_null:a", List.of("Dạ 123 Nguyễn Huệ ạ"), SentBubbleLedger.DeliveryStatus.PENDING
        );
        assertThat(controller.sendSequentialBubblesToFacebook(
                PAGE_ID.toString(), senderNull, convNull, buildBotResponse(List.of("Dạ 123 Nguyễn Huệ ạ")))).isZero();
        SentBubbleLedger.BubbleRecord bubbleNull = SentBubbleLedger.snapshot(keyNull).get(1).bubbles().get(0);
        assertThat(bubbleNull.status()).isEqualTo(SentBubbleLedger.DeliveryStatus.UNKNOWN);
        assertThat(bubbleNull.failureReason()).isEqualTo("NO_HTTP_RESPONSE_UNCERTAIN");
    }

    @Test
    @DisplayName("P3.1.1 — ConnectException trước khi thiết lập kết nối -> FAILED và dừng chuỗi bong bóng")
    void p311_connectExceptionBeforeSend_transitionsToFailedAndStopsSequence() {
        FanpageBotController controller = new FanpageBotController(Mockito.mock(FanpageBotService.class));
        controller.setPageAccessToken("EAAB_P311_TOKEN");
        controller.setInterBubbleDelayMs(0L);

        AtomicInteger attempts = new AtomicInteger(0);
        controller.setGraphApiSender((url, jsonBody) -> {
            attempts.incrementAndGet();
            throw new java.net.ConnectException("Connection refused: connect");
        });

        String senderId = "psid_p311_conn_refused";
        String convId = "conv_p311_conn_refused";
        String ledgerKey = SentBubbleLedger.key(PAGE_ID.toString(), senderId, convId);
        SentBubbleLedger.recordCustomerTurn(ledgerKey, "t_conn", "Tư vấn");
        SentBubbleLedger.recordAssistantTurn(
                ledgerKey, "t_conn:a", List.of("Bong bóng 1", "Bong bóng 2"), SentBubbleLedger.DeliveryStatus.PENDING
        );

        FanpageBotResponse botResp = buildBotResponse(List.of("Bong bóng 1", "Bong bóng 2"));
        assertThat(controller.sendSequentialBubblesToFacebook(PAGE_ID.toString(), senderId, convId, botResp)).isZero();
        assertThat(attempts.get()).isEqualTo(1);

        List<SentBubbleLedger.BubbleRecord> bubbles = SentBubbleLedger.snapshot(ledgerKey).get(1).bubbles();
        assertThat(bubbles.get(0).status()).isEqualTo(SentBubbleLedger.DeliveryStatus.FAILED);
        assertThat(bubbles.get(0).failureReason()).contains("ConnectException");
        assertThat(bubbles.get(1).status()).isEqualTo(SentBubbleLedger.DeliveryStatus.SUPPRESSED_BY_FENCE);
        assertThat(bubbles.get(1).failureReason()).isEqualTo("ABORTED_AFTER_PRIOR_BUBBLE_FAILED");
    }

    @Test
    @DisplayName("P3.1.1 — Phân biệt Message Echo và Delivery Receipt: Echo chỉ đối soát UNKNOWN -> ACCEPTED_BY_CHANNEL (không bao giờ DELIVERED); Receipt mới nâng lên DELIVERED")
    void p311_messageEchoReconcilesUnknownToAcceptedNeverDelivered_andDeliveryReceiptElevatesToDelivered() throws Exception {
        FanpageBotController controller = new FanpageBotController(Mockito.mock(FanpageBotService.class));
        controller.setPageAccessToken("EAAB_P311_TOKEN");
        controller.setInterBubbleDelayMs(0L);

        String senderId = "psid_p311_echo";
        String convId = "fb_webhook_" + senderId;
        String ledgerKey = SentBubbleLedger.key(PAGE_ID.toString(), senderId, convId);
        SentBubbleLedger.recordCustomerTurn(ledgerKey, "t_echo_1", "Gói cơ bản bao nhiêu?");
        SentBubbleLedger.recordAssistantTurn(
                ledgerKey, "t_echo_1:a", List.of("Dạ gói dưỡng sinh cơ bản 239k ạ."), SentBubbleLedger.DeliveryStatus.PENDING
        );

        // Lần gửi gặp HTTP 502 -> trạng thái là UNKNOWN (chưa có externalMid)
        controller.setGraphApiSender((url, jsonBody) ->
                new FanpageBotController.HttpResponseResult(502, "Bad Gateway")
        );
        controller.sendSequentialBubblesToFacebook(
                PAGE_ID.toString(), senderId, convId, buildBotResponse(List.of("Dạ gói dưỡng sinh cơ bản 239k ạ."))
        );
        assertThat(SentBubbleLedger.snapshot(ledgerKey).get(1).bubbles().get(0).status())
                .isEqualTo(SentBubbleLedger.DeliveryStatus.UNKNOWN);

        // Meta gửi message_echoes (is_echo = true) chứng minh Page thực sự đã gửi tin này với mid = m_echo_239k
        String echoWebhook = """
                {
                  "object": "page",
                  "entry": [{
                    "id": "12218945612345",
                    "time": 1730000020000,
                    "messaging": [{
                      "sender": { "id": "12218945612345" },
                      "recipient": { "id": "psid_p311_echo" },
                      "timestamp": 1730000020100,
                      "message": {
                        "is_echo": true,
                        "mid": "m_echo_239k",
                        "text": "Dạ gói dưỡng sinh cơ bản 239k ạ."
                      }
                    }]
                  }]
                }
                """;
        var echoResp = controller.receiveMetaWebhook(PAGE_ID.toString(), null, echoWebhook);
        assertThat(echoResp.getStatusCode().value()).isEqualTo(200);
        assertThat(echoResp.getBody().get("processed_events")).isEqualTo(0);

        SentBubbleLedger.BubbleRecord afterEcho = SentBubbleLedger.snapshot(ledgerKey).get(1).bubbles().get(0);
        assertThat(afterEcho.status())
                .as("message_echoes chỉ được nâng UNKNOWN lên ACCEPTED_BY_CHANNEL, tuyệt đối KHÔNG phải DELIVERED")
                .isEqualTo(SentBubbleLedger.DeliveryStatus.ACCEPTED_BY_CHANNEL);
        assertThat(afterEcho.externalMid()).isEqualTo("m_echo_239k");
        assertThat(afterEcho.failureReason()).isEqualTo("RECONCILED_BY_MESSAGE_ECHO");

        // Sau đó Meta gửi message_deliveries thực tế cho m_echo_239k -> lúc này mới chuyển sang DELIVERED
        String deliveryWebhook = """
                {
                  "object": "page",
                  "entry": [{
                    "id": "12218945612345",
                    "time": 1730000021000,
                    "messaging": [{
                      "sender": { "id": "psid_p311_echo" },
                      "recipient": { "id": "12218945612345" },
                      "timestamp": 1730000021100,
                      "delivery": {
                        "mids": ["m_echo_239k"],
                        "watermark": 1730000021100
                      }
                    }]
                  }]
                }
                """;
        controller.receiveMetaWebhook(PAGE_ID.toString(), null, deliveryWebhook);

        SentBubbleLedger.BubbleRecord afterDelivery = SentBubbleLedger.snapshot(ledgerKey).get(1).bubbles().get(0);
        assertThat(afterDelivery.status()).isEqualTo(SentBubbleLedger.DeliveryStatus.DELIVERED);
    }

    @Test
    @DisplayName("P3.1.1 — Early Delivery Receipt (Option 2): Receipt không bao giờ bị gán nhầm giữa nhiều lượt IN_FLIGHT cùng sender")
    void p311_earlyReceiptNeverMisattributedAcrossMultipleInFlightTurnsForSameSender() {
        String senderId = "psid_p311_multi_inflight";
        String convId = "fb_webhook_" + senderId;
        String ledgerKey = SentBubbleLedger.key(PAGE_ID.toString(), senderId, convId);

        // Giả sử có 2 lượt assistant cùng của một sender đang ở trạng thái IN_FLIGHT và chưa có externalMid
        SentBubbleLedger.recordCustomerTurn(ledgerKey, "t_multi_1", "Câu hỏi 1");
        SentBubbleLedger.recordAssistantTurn(
                ledgerKey, "t_multi_1:a", List.of("Trả lời lượt 1"), SentBubbleLedger.DeliveryStatus.PENDING
        );
        SentBubbleLedger.recordCustomerTurn(ledgerKey, "t_multi_2", "Câu hỏi 2");
        SentBubbleLedger.recordAssistantTurn(
                ledgerKey, "t_multi_2:a", List.of("Trả lời lượt 2"), SentBubbleLedger.DeliveryStatus.PENDING
        );

        assertThat(SentBubbleLedger.tryAcquireBubbleDispatchLease(ledgerKey, "t_multi_1:a", 0, Duration.ofSeconds(30))).isTrue();
        assertThat(SentBubbleLedger.tryAcquireBubbleDispatchLease(ledgerKey, "t_multi_2:a", 0, Duration.ofSeconds(30))).isTrue();

        // Receipt của lượt 2 (mid_turn_2) đến sớm khi cả lượt 1 và lượt 2 đều chưa nhận HTTP response
        SentBubbleLedger.markBubbleDeliveredByExternalMid(PAGE_ID.toString(), senderId, "mid_turn_2");

        // Trước khi có HTTP response cung cấp MID: cả 2 lượt vẫn phải giữ nguyên IN_FLIGHT (không được đoán mò gán cho lượt 1 hay lượt 2)
        List<SentBubbleLedger.TurnRecord> whileInFlight = SentBubbleLedger.snapshot(ledgerKey);
        assertThat(whileInFlight.get(1).bubbles().get(0).status()).isEqualTo(SentBubbleLedger.DeliveryStatus.IN_FLIGHT);
        assertThat(whileInFlight.get(1).bubbles().get(0).externalMid()).isNull();
        assertThat(whileInFlight.get(3).bubbles().get(0).status()).isEqualTo(SentBubbleLedger.DeliveryStatus.IN_FLIGHT);
        assertThat(whileInFlight.get(3).bubbles().get(0).externalMid()).isNull();

        // Khi HTTP response của lượt 1 trả về mid_turn_1 -> lượt 1 chỉ lên ACCEPTED_BY_CHANNEL (không bị gán nhầm receipt của lượt 2)
        SentBubbleLedger.transitionBubbleStatusMonotonically(
                ledgerKey, "t_multi_1:a", 0, SentBubbleLedger.DeliveryStatus.ACCEPTED_BY_CHANNEL, "mid_turn_1", null
        );
        // Khi HTTP response của lượt 2 trả về mid_turn_2 -> khớp đúng receipt đã giữ nên nâng thẳng lên DELIVERED
        SentBubbleLedger.transitionBubbleStatusMonotonically(
                ledgerKey, "t_multi_2:a", 0, SentBubbleLedger.DeliveryStatus.ACCEPTED_BY_CHANNEL, "mid_turn_2", null
        );

        List<SentBubbleLedger.TurnRecord> afterResponses = SentBubbleLedger.snapshot(ledgerKey);
        assertThat(afterResponses.get(1).bubbles().get(0).status())
                .as("Lượt 1 (mid_turn_1) không có receipt nên phải là ACCEPTED_BY_CHANNEL")
                .isEqualTo(SentBubbleLedger.DeliveryStatus.ACCEPTED_BY_CHANNEL);
        assertThat(afterResponses.get(1).bubbles().get(0).externalMid()).isEqualTo("mid_turn_1");

        assertThat(afterResponses.get(3).bubbles().get(0).status())
                .as("Lượt 2 (mid_turn_2) khớp đúng early receipt nên phải được nâng lên DELIVERED")
                .isEqualTo(SentBubbleLedger.DeliveryStatus.DELIVERED);
        assertThat(afterResponses.get(3).bubbles().get(0).externalMid()).isEqualTo("mid_turn_2");
    }

    private static String reachablePostgresUrl() {
        String fromEnv = System.getenv("SENT_LEDGER_TEST_JDBC_URL");
        List<String> candidates = fromEnv != null ? List.of(fromEnv) : List.of(
                "jdbc:postgresql://localhost:5433/docmind",
                "jdbc:postgresql://host.docker.internal:5433/docmind"
        );
        for (String url : candidates) {
            try (Connection c = DriverManager.getConnection(url, "docmind", "docmind")) {
                return url;
            } catch (Exception ignored) {
                // try next
            }
        }
        return null;
    }

    @Test
    @DisplayName("PostgreSQL Durability: Kiểm chứng vòng đời PENDING -> IN_FLIGHT -> ACCEPTED_BY_CHANNEL -> DELIVERED, monotonic guard & crash recovery trên PostgreSQL thật")
    void postgresDurability_p31FullLifecycleAndCrashRecoveryInRealPostgres() throws Exception {
        String url = reachablePostgresUrl();
        Assumptions.assumeTrue(url != null, "SKIPPED: không kết nối được PostgreSQL thật (:5433)");

        DriverManagerDataSource ds = new DriverManagerDataSource(url, "docmind", "docmind");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/V19__sent_bubble_ledger.sql")));
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/V42__p31_outbound_delivery_lifecycle.sql")));

        String sender1 = "pg_sender_" + UUID.randomUUID();
        String conv1 = "fb_webhook_" + sender1;
        String key1 = SentBubbleLedger.key(PAGE_ID.toString(), sender1, conv1);

        String senderCrash = "pg_crash_" + UUID.randomUUID();
        String convCrash = "fb_webhook_" + senderCrash;
        String keyCrash = SentBubbleLedger.key(PAGE_ID.toString(), senderCrash, convCrash);

        String senderFence = "pg_fence_" + UUID.randomUUID();
        String convFence = "fb_webhook_" + senderFence;
        String keyFence = SentBubbleLedger.key(PAGE_ID.toString(), senderFence, convFence);

        try {
            JdbcSentBubbleStore store1 = new JdbcSentBubbleStore(jdbc);
            store1.install();

            // 1. Ghi PENDING vào PostgreSQL thật
            SentBubbleLedger.recordCustomerTurn(key1, "t_pg_1", "Giá massage cổ vai gáy?");
            SentBubbleLedger.recordAssistantTurn(
                    key1, "t_pg_1:a", List.of("Gói 60 phút giá 399k ạ"), SentBubbleLedger.DeliveryStatus.PENDING
            );

            SentBubbleLedger.dropCacheOnly();
            assertThat(SentBubbleLedger.snapshot(key1).get(1).bubbles().get(0).status())
                    .isEqualTo(SentBubbleLedger.DeliveryStatus.PENDING);

            // 2. Chạy qua FanpageBotController: PENDING -> IN_FLIGHT -> ACCEPTED_BY_CHANNEL
            FanpageBotController controller = new FanpageBotController(Mockito.mock(FanpageBotService.class));
            controller.setPageAccessToken("EAAB_PG_TEST");
            controller.setInterBubbleDelayMs(0L);
            String outMidPg = "m_pg_out_" + UUID.randomUUID();
            controller.setGraphApiSender((u, body) ->
                    new FanpageBotController.HttpResponseResult(200, "{\"message_id\":\"" + outMidPg + "\"}")
            );

            FanpageBotResponse resp1 = buildBotResponse(List.of("Gói 60 phút giá 399k ạ"));
            assertThat(controller.sendSequentialBubblesToFacebook(PAGE_ID.toString(), sender1, conv1, resp1)).isEqualTo(1);

            SentBubbleLedger.dropCacheOnly();
            SentBubbleLedger.BubbleRecord pgAccepted = SentBubbleLedger.snapshot(key1).get(1).bubbles().get(0);
            assertThat(pgAccepted.status()).isEqualTo(SentBubbleLedger.DeliveryStatus.ACCEPTED_BY_CHANNEL);
            assertThat(pgAccepted.externalMid()).isEqualTo(outMidPg);
            assertThat(pgAccepted.attemptCount()).isEqualTo(1);

            // 3. Nhận Delivery Receipt -> nâng lên DELIVERED trong PostgreSQL và chống ghi lùi về ACCEPTED_BY_CHANNEL
            SentBubbleLedger.markBubbleDeliveredByExternalMid(PAGE_ID.toString(), sender1, outMidPg);
            SentBubbleLedger.transitionBubbleStatusMonotonically(
                    key1, "t_pg_1:a", 0, SentBubbleLedger.DeliveryStatus.ACCEPTED_BY_CHANNEL, outMidPg, null
            );

            SentBubbleLedger.dropCacheOnly();
            SentBubbleLedger.BubbleRecord pgDelivered = SentBubbleLedger.snapshot(key1).get(1).bubbles().get(0);
            assertThat(pgDelivered.status()).isEqualTo(SentBubbleLedger.DeliveryStatus.DELIVERED);

            // 4. Mô phỏng JVM crash khi đang IN_FLIGHT trong PostgreSQL thật -> sau restart chuyển sang UNKNOWN và không gửi lại
            SentBubbleLedger.recordCustomerTurn(keyCrash, "t_pg_crash", "Đặt lịch tối nay");
            SentBubbleLedger.recordAssistantTurn(
                    keyCrash, "t_pg_crash:a", List.of("Dạ chị cho em xin SĐT ạ"), SentBubbleLedger.DeliveryStatus.PENDING
            );
            assertThat(SentBubbleLedger.tryAcquireBubbleDispatchLease(keyCrash, "t_pg_crash:a", 0, Duration.ofMillis(20))).isTrue();

            // Giả lập JVM restart hoàn toàn: xoá cache + tạo store mới trên PostgreSQL (install() tự quét lease quá hạn)
            SentBubbleLedger.dropCacheOnly();
            JdbcSentBubbleStore storeAfterRestart = new JdbcSentBubbleStore(new JdbcTemplate(ds));
            storeAfterRestart.install();
            SentBubbleLedger.recoverAllExpiredInFlightLeases(Instant.now().plusSeconds(5));

            SentBubbleLedger.dropCacheOnly();
            SentBubbleLedger.BubbleRecord pgCrashRecovered = SentBubbleLedger.snapshot(keyCrash).get(1).bubbles().get(0);
            assertThat(pgCrashRecovered.status()).isEqualTo(SentBubbleLedger.DeliveryStatus.UNKNOWN);
            assertThat(pgCrashRecovered.failureReason()).isEqualTo("IN_FLIGHT_LEASE_EXPIRED_CRASH_RECOVERY");

            AtomicInteger retryCalls = new AtomicInteger(0);
            controller.setGraphApiSender((u, body) -> {
                retryCalls.incrementAndGet();
                return new FanpageBotController.HttpResponseResult(200, "{\"message_id\":\"m_dup\"}");
            });
            FanpageBotResponse crashResp = buildBotResponse(List.of("Dạ chị cho em xin SĐT ạ"));
            assertThat(controller.sendSequentialBubblesToFacebook(PAGE_ID.toString(), senderCrash, convCrash, crashResp)).isZero();
            assertThat(retryCalls.get()).isZero();

            // 5. Kiểm chứng Outbound Fence (HUMAN_TAKEOVER / OPT_OUT / newer inbound) bền vững trực tiếp trên PostgreSQL thật
            SentBubbleLedger.recordCustomerTurn(keyFence, "t_pg_fence_1", "Tư vấn giá");
            SentBubbleLedger.recordAssistantTurn(
                    keyFence, "t_pg_fence_1:a", List.of("Dạ gói 399k ạ"), SentBubbleLedger.DeliveryStatus.PENDING
            );
            SentBubbleLedger.recordCustomerTurn(keyFence, "t_pg_fence_2", "À thôi cho chị hỏi gói khác");

            FanpageBotResponse fenceResp = buildBotResponse(List.of("Dạ gói 399k ạ"));
            assertThat(controller.sendSequentialBubblesToFacebook(PAGE_ID.toString(), senderFence, convFence, fenceResp)).isZero();
            assertThat(retryCalls.get()).isZero();

            SentBubbleLedger.dropCacheOnly();
            SentBubbleLedger.BubbleRecord pgFenceBubble = SentBubbleLedger.snapshot(keyFence).get(1).bubbles().get(0);
            assertThat(pgFenceBubble.status()).isEqualTo(SentBubbleLedger.DeliveryStatus.SUPPRESSED_BY_FENCE);
            assertThat(pgFenceBubble.failureReason()).isEqualTo("SUPERSEDED_BY_NEWER_INBOUND_TURN");

            // 6. P3.1.1 Option 2 trên PostgreSQL thật: Early delivery receipt đến trước HTTP response & qua dropCacheOnly()
            //    không đoán mò IN_FLIGHT có external_mid = NULL, chỉ nâng đúng bubble khi external_mid xuất hiện
            String earlyMidPg = "m_pg_out_early_" + UUID.randomUUID();
            SentBubbleLedger.recordCustomerTurn(key1, "t_pg_early_1", "Hỏi gói đá nóng");
            SentBubbleLedger.recordAssistantTurn(
                    key1, "t_pg_early_1:a", List.of("Dạ gói đá nóng 799k ạ"), SentBubbleLedger.DeliveryStatus.PENDING
            );
            assertThat(SentBubbleLedger.tryAcquireBubbleDispatchLease(key1, "t_pg_early_1:a", 0, Duration.ofSeconds(30))).isTrue();

            // Early receipt tới trước khi HTTP 200 trả về
            SentBubbleLedger.markBubbleDeliveredByExternalMid(PAGE_ID.toString(), sender1, earlyMidPg);
            SentBubbleLedger.dropCacheOnly();

            // Khi external_mid còn NULL trong PostgreSQL, bubble đang IN_FLIGHT tuyệt đối KHÔNG bị đoán mò thành DELIVERED
            SentBubbleLedger.BubbleRecord pgStillInFlight = SentBubbleLedger.snapshot(key1).stream()
                    .filter(t -> "t_pg_early_1:a".equals(t.turnId()))
                    .findFirst().orElseThrow().bubbles().get(0);
            assertThat(pgStillInFlight.status()).isEqualTo(SentBubbleLedger.DeliveryStatus.IN_FLIGHT);
            assertThat(pgStillInFlight.externalMid()).isNull();

            // Khi HTTP response mang earlyMidPg về (dù cache in-memory đã drop, đối soát từ midKey trong PostgreSQL) -> nâng lên DELIVERED
            SentBubbleLedger.transitionBubbleStatusMonotonically(
                    key1, "t_pg_early_1:a", 0, SentBubbleLedger.DeliveryStatus.ACCEPTED_BY_CHANNEL, earlyMidPg, null
            );
            SentBubbleLedger.dropCacheOnly();
            SentBubbleLedger.BubbleRecord pgCorrelatedDelivered = SentBubbleLedger.snapshot(key1).stream()
                    .filter(t -> "t_pg_early_1:a".equals(t.turnId()))
                    .findFirst().orElseThrow().bubbles().get(0);
            assertThat(pgCorrelatedDelivered.status()).isEqualTo(SentBubbleLedger.DeliveryStatus.DELIVERED);
            assertThat(pgCorrelatedDelivered.externalMid()).isEqualTo(earlyMidPg);
        } finally {
            jdbc.update("DELETE FROM sent_bubble_ledger WHERE ledger_key IN (?, ?, ?, ?)",
                    key1, keyCrash, keyFence, SentBubbleLedger.midLedgerKey(PAGE_ID.toString(), sender1));
            jdbc.update("DELETE FROM sent_bubble_ledger WHERE turn_id LIKE ?", "m_pg_out_%");
            SentBubbleLedger.useStore(new SentBubbleLedger.InMemoryStore());
        }
    }
}
