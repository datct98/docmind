package com.example.docmind.service.bot;

import com.example.docmind.dto.PagePersonaConfig;
import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.dto.bot.FanpageBotResponse;
import com.example.docmind.repository.RetrievedChunk;
import com.example.docmind.service.llm.LlmClient;
import com.example.docmind.service.llm.ReasoningBudget;
import com.example.docmind.service.retrieval.PromptBuilder;
import com.example.docmind.service.retrieval.ReciprocalRankFusion;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Bộ test nghiệm thu hành vi bắt buộc Mốc C3-B:
 * Máy trạng thái Đặt lịch / Đặt hàng đa ngành 6 trạng thái tường minh
 * (NONE, AWAITING_PRODUCT_SELECTION, AWAITING_DATE_OR_TIME, AWAITING_AVAILABILITY_CHECK,
 *  AWAITING_CUSTOMER_CONFIRMATION, CONFIRMED) & 4 kịch bản cổng kiểm thử Test A (+ Turn 7), Test B, Test C, Test D.
 */
class MultiIndustryBookingStateMachineC3BTest {

    private static ReciprocalRankFusion.FusedChunk buildChunk(String heading, String text) {
        RetrievedChunk rc = new RetrievedChunk(
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(heading),
                List.of(UUID.randomUUID()),
                1,
                new double[]{0, 0, 10, 10},
                text,
                0.95
        );
        return new ReciprocalRankFusion.FusedChunk(rc, 0.95);
    }

    private static final String SPA_KB_TABLE = """
            | Mã gói | Tên gói | Thời lượng | Giá | Vùng chăm sóc | Vai trò thương mại |
            | --- | --- | --- | --- | --- | --- |
            | NS01 | Massage cơ bản | 60 phút | 399k | đầu, cổ, vai, gáy, lưng | gói chính |
            | DS02 | Gói dưỡng sinh cơ bản | 60 phút | 239k | đầu, cổ, vai, gáy | gói tiết kiệm |
            | VIP99 | Liệu trình Hoàng Gia Đá Nóng | 120 phút | 799k | đầu, cổ, vai, gáy, lưng, toàn thân | cao cấp |
            """;

    private static PromptBuilder.PagePersona buildSpaPersona() {
        return new PromptBuilder.PagePersona(
                "Nàng Sen Spa",
                "BEAUTY_SPA",
                "Dân văn phòng",
                "Thư giãn chuyên sâu",
                PagePersonaConfig.fromJson("""
                        {
                          "industry_code": "BEAUTY_SPA",
                          "bot_name": "Lễ tân Nàng Sen",
                          "self_pronoun": "em",
                          "default_customer_pronoun": "mình",
                          "scope_taxonomy": ["đầu", "cổ", "vai", "gáy", "lưng", "toàn thân"]
                        }
                        """)
        );
    }

    /**
     * Lịch sử nền 5 lượt (Turn 1 -> Turn 5):
     * Cả 2 sản phẩm NS01 (399k) và DS02 (239k) đều đã được giới thiệu trong lịch sử,
     * và lượt 5 bot vừa gợi ý DS02 (khiến legacy ConversationState.heldPackage = DS02).
     */
    private static List<FanpageBotRequest.ConversationTurn> buildFiveBaselineTurnsHistory() {
        return new ArrayList<>(List.of(
                new FanpageBotRequest.ConversationTurn("user", "Mình bị đau mỏi cổ vai gáy mấy hôm nay"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ tình trạng mỏi cổ vai gáy của mình mới xuất hiện mấy hôm nay do ngồi máy tính lâu hay vận động nhiều ạ?"),
                new FanpageBotRequest.ConversationTurn("user", "Do ngồi máy tính nhiều cả tuần rồi, giá bên mình sao?"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ em gợi ý mình tham khảo gói Massage cơ bản 60 phút (399k trọn gói) để giải tỏa sâu vùng cổ vai gáy nhé ạ."),
                new FanpageBotRequest.ConversationTurn("user", "399k hơi cao so với mình nhỉ"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ mức giá 399k đã bao gồm trọn gói 60 phút ấn huyệt chuyên sâu và trà thảo mộc, không phát sinh phụ phí ạ. Mình thử trải nghiệm trước 1 lần để cảm nhận nhé?"),
                new FanpageBotRequest.ConversationTurn("user", "Tối mấy giờ bên mình đóng cửa?"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ bên em mở cửa từ 9h sáng đến 21h tối tất cả các ngày trong tuần ạ."),
                new FanpageBotRequest.ConversationTurn("user", "Biết thế"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ em hiểu cảm giác cân nhắc chi phí của mình ạ. Nếu mình muốn thử một phương án nhẹ nhàng tiết kiệm hơn thì bên em có Gói dưỡng sinh cơ bản 60 phút (239k trọn gói) tập trung vùng đầu cổ vai gáy, khi nào tiện mình cứ nhắn em nhé ạ.")
        ));
    }

    private static FanpageBotResponse runCleanTurn(
            CleanAgentOrchestratorService orchestrator,
            LlmClient mockLlm,
            UUID pageId,
            String senderId,
            String convId,
            boolean resetSession,
            String userMessage,
            List<FanpageBotRequest.ConversationTurn> history,
            PromptBuilder.PagePersona persona,
            List<ReciprocalRankFusion.FusedChunk> fusedChunks,
            String mockLlmJson,
            AtomicReference<String> capturedPromptOut
    ) {
        List<String> taxonomy = persona.resolvedConfig().resolvedScopeTaxonomy();
        ConversationState preState = ConversationState.hydrateForTurn(
                pageId, senderId, convId, resetSession, userMessage, history, taxonomy, List.of()
        );
        ConversationState convState = preState.enrichWithCurrentMessage(userMessage, taxonomy)
                .withRetrievedChunks(fusedChunks, taxonomy, history);

        when(mockLlm.complete(anyString(), any(ReasoningBudget.class))).thenAnswer(inv -> {
            if (capturedPromptOut != null) {
                capturedPromptOut.set(inv.getArgument(0, String.class));
            }
            return mockLlmJson;
        });
        when(mockLlm.complete(anyString())).thenAnswer(inv -> {
            if (capturedPromptOut != null) {
                capturedPromptOut.set(inv.getArgument(0, String.class));
            }
            return mockLlmJson;
        });

        FanpageBotRequest req = new FanpageBotRequest(
                pageId.toString(),
                senderId,
                "Khách Test C3B",
                userMessage,
                null,
                history,
                List.of(),
                5,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                convId,
                resetSession
        );
        return orchestrator.executeCleanAgentTurn(
                pageId,
                req,
                userMessage,
                "mình",
                persona,
                preState,
                convState,
                fusedChunks,
                List.of(),
                null,
                null,
                List.of(),
                List.of(),
                FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION,
                history.size(),
                System.nanoTime(),
                8L
        );
    }

    @Test
    void testGateA_OkDatLichLuon_AwaitingProductSelection_ThenTurn7_Goi239kNhe_AwaitingDateOrTime() {
        LlmClient mockLlm = mock(LlmClient.class);
        when(mockLlm.name()).thenReturn("ollama:qwen3.8-flash");
        AgentBrainService brainService = new AgentBrainService((com.example.docmind.client.AgentBrainClient) null);
        CleanAgentOrchestratorService orchestrator = new CleanAgentOrchestratorService(mockLlm, brainService, null);

        UUID pageId = UUID.randomUUID();
        String senderId = "sender-c3b-test-a";
        String convId = "conv-c3b-test-a";
        PromptBuilder.PagePersona spaPersona = buildSpaPersona();
        List<ReciprocalRankFusion.FusedChunk> fusedChunks = List.of(buildChunk("Bảng giá Spa", SPA_KB_TABLE));
        List<FanpageBotRequest.ConversationTurn> historyAfterTurn5 = buildFiveBaselineTurnsHistory();
        brainService.recordCommercialFunnelState(pageId, senderId, 1, true, true);

        // =========================================================================
        // TEST A (Turn 6): "Ok, đặt lịch luôn"
        // Lịch sử đã có >= 2 sản phẩm (399k & 239k) -> selectedProductId PHẢI == null (cấm lấy từ heldPackage),
        // bookingStatus PHẢI == AWAITING_PRODUCT_SELECTION, bot hỏi muốn đặt gói 399k hay 239k.
        // =========================================================================
        AtomicReference<String> turn6Prompt = new AtomicReference<>("");
        String turn6LlmJson = """
                {
                  "reply_bubbles": [
                    {"text": "Dạ vâng ạ, mình muốn đặt lịch gói Massage cơ bản 60 phút (399k) hay Gói dưỡng sinh cơ bản 60 phút (239k) để em giữ khung giờ phù hợp cho mình ạ?", "typing_delay_ms": 0}
                  ],
                  "brain_reflection": {
                    "observed_fact": "",
                    "care_note_delta": "",
                    "working_hypothesis": "Khách đã sẵn sàng đặt lịch trải nghiệm và đang cần chọn rõ giữa gói 399k và 239k",
                    "intended_speech_act": "INVITED_TRIAL_OR_ASKED_TIME"
                  },
                  "should_handoff": false
                }
                """;
        FanpageBotResponse respTurn6 = runCleanTurn(
                orchestrator, mockLlm, pageId, senderId, convId, true,
                "Ok, đặt lịch luôn", historyAfterTurn5, spaPersona, fusedChunks, turn6LlmJson, turn6Prompt
        );

        ConversationState.StructuredBookingState stateAfterTurn6 = orchestrator.getLastTurnBookingState();
        ConversationState sessionAfterTurn6 = ConversationState.getSessionState(pageId, senderId, convId);
        assertNotNull(stateAfterTurn6);
        assertNull(stateAfterTurn6.selectedProductId(),
                "Test A: Khi lịch sử có >= 2 gói (399k & 239k), selectedProductId bắt buộc == null, tuyệt đối không lấy từ heldPackage");
        assertEquals(ConversationState.BookingStatus.AWAITING_PRODUCT_SELECTION, stateAfterTurn6.status());
        assertNotNull(sessionAfterTurn6);
        assertNull(sessionAfterTurn6.selectedProductId());
        assertEquals(ConversationState.BookingStatus.AWAITING_PRODUCT_SELECTION, sessionAfterTurn6.bookingStatusEnum());
        assertEquals("AWAITING_PRODUCT_SELECTION", sessionAfterTurn6.bookingStatus());

        assertTrue(turn6Prompt.get().contains("selected_product_id: \"null\""),
                "Prompt Test A phải ghi rõ selected_product_id: \"null\"");
        assertTrue(turn6Prompt.get().contains("booking_funnel_status: \"AWAITING_PRODUCT_SELECTION\""),
                "Prompt Test A phải ghi rõ booking_funnel_status: \"AWAITING_PRODUCT_SELECTION\"");
        assertTrue(respTurn6.replyText().contains("399k") && respTurn6.replyText().contains("239k"),
                "Bot ở Test A phải hỏi khách muốn đặt gói 399k hay 239k: " + respTurn6.replyText());

        // =========================================================================
        // TURN 7 (Tiếp nối Test A): Khách đáp "Gói 239k nhé"
        // -> selectedProductId == "DS02", bookingStatus == AWAITING_DATE_OR_TIME, hỏi ngày/giờ.
        // =========================================================================
        List<FanpageBotRequest.ConversationTurn> historyBeforeTurn7 = new ArrayList<>(historyAfterTurn5);
        historyBeforeTurn7.add(new FanpageBotRequest.ConversationTurn("user", "Ok, đặt lịch luôn"));
        historyBeforeTurn7.add(new FanpageBotRequest.ConversationTurn("assistant", respTurn6.replyText()));

        AtomicReference<String> turn7Prompt = new AtomicReference<>("");
        String turn7LlmJson = """
                {
                  "reply_bubbles": [
                    {"text": "Dạ em ghi nhận mình chọn Gói dưỡng sinh cơ bản 60 phút (239k) ạ. Mình dự kiến ghé bên em vào ngày nào và khung giờ mấy giờ để em kiểm tra lịch trống nhé ạ?", "typing_delay_ms": 0}
                  ],
                  "brain_reflection": {
                    "observed_fact": "slot_product_focus=Khách chốt đặt Gói dưỡng sinh cơ bản 239k",
                    "care_note_delta": "",
                    "working_hypothesis": "Khách đã chọn gói 239k và đang cần cung cấp ngày giờ cụ thể",
                    "intended_speech_act": "INVITED_TRIAL_OR_ASKED_TIME"
                  },
                  "should_handoff": false
                }
                """;
        FanpageBotResponse respTurn7 = runCleanTurn(
                orchestrator, mockLlm, pageId, senderId, convId, false,
                "Gói 239k nhé", historyBeforeTurn7, spaPersona, fusedChunks, turn7LlmJson, turn7Prompt
        );

        ConversationState.StructuredBookingState stateAfterTurn7 = orchestrator.getLastTurnBookingState();
        ConversationState sessionAfterTurn7 = ConversationState.getSessionState(pageId, senderId, convId);
        assertEquals("DS02", stateAfterTurn7.selectedProductId(),
                "Turn 7: Khách đáp 'Gói 239k nhé' phải cập nhật selectedProductId = DS02");
        assertEquals(ConversationState.BookingStatus.AWAITING_DATE_OR_TIME, stateAfterTurn7.status(),
                "Turn 7: Đã chọn rõ gói nhưng chưa có ngày/giờ -> phải chuyển sang AWAITING_DATE_OR_TIME");
        assertEquals("DS02", sessionAfterTurn7.selectedProductId());
        assertEquals(ConversationState.BookingStatus.AWAITING_DATE_OR_TIME, sessionAfterTurn7.bookingStatusEnum());
        assertTrue(turn7Prompt.get().contains("selected_product_id: \"DS02\""));
        assertTrue(turn7Prompt.get().contains("booking_funnel_status: \"AWAITING_DATE_OR_TIME\""));
        assertTrue(respTurn7.replyText().contains("239k") && !respTurn7.replyText().contains("399k"),
                "Turn 7 phải xác nhận gói 239k và hỏi ngày/giờ, không nhắc lại gói 399k: " + respTurn7.replyText());
    }

    @Test
    void testGateB_DatGoi239kChoMinh_LocksDS02_AwaitingDateOrTime_NeverRePitches399k() {
        LlmClient mockLlm = mock(LlmClient.class);
        when(mockLlm.name()).thenReturn("ollama:qwen3.8-flash");
        AgentBrainService brainService = new AgentBrainService((com.example.docmind.client.AgentBrainClient) null);
        CleanAgentOrchestratorService orchestrator = new CleanAgentOrchestratorService(mockLlm, brainService, null);

        UUID pageId = UUID.randomUUID();
        String senderId = "sender-c3b-test-b";
        String convId = "conv-c3b-test-b";
        PromptBuilder.PagePersona spaPersona = buildSpaPersona();
        List<ReciprocalRankFusion.FusedChunk> fusedChunks = List.of(buildChunk("Bảng giá Spa", SPA_KB_TABLE));
        List<FanpageBotRequest.ConversationTurn> historyAfterTurn5 = buildFiveBaselineTurnsHistory();
        brainService.recordCommercialFunnelState(pageId, senderId, 1, true, true);

        // =========================================================================
        // TEST B (Turn 6): "Đặt gói 239k cho mình"
        // -> selectedProductId = DS02, bookingStatus == AWAITING_DATE_OR_TIME.
        // Bot xác nhận hỗ trợ đặt gói 239k và hỏi ngày/giờ, không quay lại gạ gói 399k.
        // =========================================================================
        AtomicReference<String> turn6Prompt = new AtomicReference<>("");
        String turn6LlmJson = """
                {
                  "reply_bubbles": [
                    {"text": "Dạ vâng ạ, em hỗ trợ mình đặt Gói dưỡng sinh cơ bản 60 phút (239k trọn gói) nhé. Mình dự kiến qua bên em ngày nào và lúc mấy giờ để em kiểm tra lịch trống cho mình ạ?", "typing_delay_ms": 0}
                  ],
                  "brain_reflection": {
                    "observed_fact": "slot_product_focus=Khách chọn đặt Gói dưỡng sinh cơ bản 239k",
                    "care_note_delta": "",
                    "working_hypothesis": "Khách chủ động chốt gói 239k và sẵn sàng hẹn ngày giờ",
                    "intended_speech_act": "INVITED_TRIAL_OR_ASKED_TIME"
                  },
                  "should_handoff": false
                }
                """;
        FanpageBotResponse respTurn6 = runCleanTurn(
                orchestrator, mockLlm, pageId, senderId, convId, true,
                "Đặt gói 239k cho mình", historyAfterTurn5, spaPersona, fusedChunks, turn6LlmJson, turn6Prompt
        );

        ConversationState.StructuredBookingState state = orchestrator.getLastTurnBookingState();
        ConversationState sessionState = ConversationState.getSessionState(pageId, senderId, convId);
        assertEquals("DS02", state.selectedProductId());
        assertNull(state.bookingDate());
        assertNull(state.bookingTime());
        assertEquals(ConversationState.BookingStatus.AWAITING_DATE_OR_TIME, state.status());
        assertEquals("DS02", sessionState.selectedProductId());
        assertEquals(ConversationState.BookingStatus.AWAITING_DATE_OR_TIME, sessionState.bookingStatusEnum());

        String userPart = turn6Prompt.get().substring(turn6Prompt.get().indexOf("<!-- SYSTEM_USER_SPLIT -->"));
        String catalogBlock = userPart.substring(
                userPart.indexOf("<SCOPE_FILTERED_CATALOG>"),
                userPart.indexOf("</SCOPE_FILTERED_CATALOG>")
        );
        assertTrue(catalogBlock.contains("239k"), "Catalog trong prompt Test B phải chứa gói 239k (DS02)");
        assertFalse(catalogBlock.contains("399k"), "Catalog trong prompt Test B không được đưa gói 399k quay lại chào bán");
        assertTrue(respTurn6.replyText().contains("239k"));
        assertFalse(respTurn6.replyText().contains("399k"),
                "Test B tuyệt đối không được quay lại gạ gói 399k: " + respTurn6.replyText());
    }

    @Test
    void testGateC_DatChoMinhToiNay_RetainsToiNay_NullSelectedProductId_AwaitingProductSelection() {
        LlmClient mockLlm = mock(LlmClient.class);
        when(mockLlm.name()).thenReturn("ollama:qwen3.8-flash");
        AgentBrainService brainService = new AgentBrainService((com.example.docmind.client.AgentBrainClient) null);
        CleanAgentOrchestratorService orchestrator = new CleanAgentOrchestratorService(mockLlm, brainService, null);

        UUID pageId = UUID.randomUUID();
        String senderId = "sender-c3b-test-c";
        String convId = "conv-c3b-test-c";
        PromptBuilder.PagePersona spaPersona = buildSpaPersona();
        List<ReciprocalRankFusion.FusedChunk> fusedChunks = List.of(buildChunk("Bảng giá Spa", SPA_KB_TABLE));
        List<FanpageBotRequest.ConversationTurn> historyAfterTurn5 = buildFiveBaselineTurnsHistory();
        brainService.recordCommercialFunnelState(pageId, senderId, 1, true, true);

        // =========================================================================
        // TEST C (Turn 6): "Đặt cho mình tối nay"
        // -> bookingDate = "tối nay", selectedProductId == null, bookingStatus == AWAITING_PRODUCT_SELECTION.
        // Giữ nguyên mốc "tối nay" (không hỏi lại ngày), hỏi tối nay khách muốn đặt gói nào và mấy giờ.
        // =========================================================================
        AtomicReference<String> turn6Prompt = new AtomicReference<>("");
        String turn6LlmJson = """
                {
                  "reply_bubbles": [
                    {"text": "Dạ tối nay mình muốn đặt gói Massage cơ bản 60 phút (399k) hay Gói dưỡng sinh cơ bản 60 phút (239k), và mình tiện ghé vào khoảng mấy giờ tối nay để em kiểm tra lịch trống ạ?", "typing_delay_ms": 0}
                  ],
                  "brain_reflection": {
                    "observed_fact": "slot_logistics_or_care=Khách muốn đặt lịch tối nay",
                    "care_note_delta": "",
                    "working_hypothesis": "Khách muốn ghé tối nay nhưng chưa chọn gói cụ thể và giờ cụ thể",
                    "intended_speech_act": "INVITED_TRIAL_OR_ASKED_TIME"
                  },
                  "should_handoff": false
                }
                """;
        FanpageBotResponse respTurn6 = runCleanTurn(
                orchestrator, mockLlm, pageId, senderId, convId, true,
                "Đặt cho mình tối nay", historyAfterTurn5, spaPersona, fusedChunks, turn6LlmJson, turn6Prompt
        );

        ConversationState.StructuredBookingState state = orchestrator.getLastTurnBookingState();
        ConversationState sessionState = ConversationState.getSessionState(pageId, senderId, convId);
        assertEquals("tối nay", state.bookingDate(), "Test C phải lưu đúng bookingDate = 'tối nay'");
        assertEquals(LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).toString(), state.resolvedIsoDate(),
                "Test C phải chuẩn hóa ngày tương đối 'tối nay' theo múi giờ Asia/Ho_Chi_Minh");
        assertNull(state.selectedProductId(), "Test C: selectedProductId bắt buộc == null");
        assertNull(state.bookingTime(), "Test C: khách chưa nêu mấy giờ nên bookingTime == null");
        assertEquals(ConversationState.BookingStatus.AWAITING_PRODUCT_SELECTION, state.status());
        assertEquals("tối nay", sessionState.bookingDate());
        assertNull(sessionState.selectedProductId());
        assertEquals(ConversationState.BookingStatus.AWAITING_PRODUCT_SELECTION, sessionState.bookingStatusEnum());

        assertTrue(turn6Prompt.get().contains("booking_date: \"tối nay\""));
        assertTrue(turn6Prompt.get().contains("selected_product_id: \"null\""));
        assertTrue(turn6Prompt.get().contains("booking_funnel_status: \"AWAITING_PRODUCT_SELECTION\""));
        assertTrue(turn6Prompt.get().contains("GIỮ NGUYÊN, CẤM HỎI LẠI NGÀY"),
                "Prompt Test C phải dặn giữ nguyên mốc 'tối nay', không hỏi lại ngày");
        assertTrue(respTurn6.replyText().toLowerCase().contains("tối nay"));
        assertFalse(respTurn6.replyText().toLowerCase().contains("ngày nào"),
                "Test C đã có 'tối nay' nên bot không được hỏi lại 'ngày nào': " + respTurn6.replyText());
    }

    @Test
    void testGateD_DatGoi239kToiNayLuc7Gio_Parses1900_AwaitingAvailabilityCheck_NeverClaimsConfirmed() {
        LlmClient mockLlm = mock(LlmClient.class);
        when(mockLlm.name()).thenReturn("ollama:qwen3.8-flash");
        AgentBrainService brainService = new AgentBrainService((com.example.docmind.client.AgentBrainClient) null);
        CleanAgentOrchestratorService orchestrator = new CleanAgentOrchestratorService(mockLlm, brainService, null);

        UUID pageId = UUID.randomUUID();
        String senderId = "sender-c3b-test-d";
        String convId = "conv-c3b-test-d";
        PromptBuilder.PagePersona spaPersona = buildSpaPersona();
        List<ReciprocalRankFusion.FusedChunk> fusedChunks = List.of(buildChunk("Bảng giá Spa", SPA_KB_TABLE));
        List<FanpageBotRequest.ConversationTurn> historyAfterTurn5 = buildFiveBaselineTurnsHistory();
        brainService.recordCommercialFunnelState(pageId, senderId, 1, true, true);

        // =========================================================================
        // TEST D (Turn 6): "Đặt gói 239k tối nay lúc 7 giờ"
        // -> selectedProductId = DS02, bookingDate = "tối nay", bookingTime = "19:00",
        //    bookingStatus == AWAITING_AVAILABILITY_CHECK (tuyệt đối KHÔNG tự chuyển sang CONFIRMED).
        // =========================================================================
        AtomicReference<String> turn6Prompt = new AtomicReference<>("");
        String turn6LlmJson = """
                {
                  "reply_bubbles": [
                    {"text": "Dạ em đã ghi nhận yêu cầu đặt Gói dưỡng sinh cơ bản 60 phút (239k) vào 19h tối nay của mình ạ. Em xin phép kiểm tra lịch trống thực tế của kỹ thuật viên và sẽ báo xác nhận chính thức lại ngay với mình nhé ạ.", "typing_delay_ms": 0}
                  ],
                  "brain_reflection": {
                    "observed_fact": "slot_product_focus=Khách yêu cầu đặt gói DS02 239k lúc 19:00 tối nay",
                    "care_note_delta": "",
                    "working_hypothesis": "Khách đã chốt đủ thông tin gói 239k và khung giờ 19:00 tối nay, đang chờ kiểm tra lịch trống",
                    "intended_speech_act": "BOOKING_CONFIRMED_ACK"
                  },
                  "should_handoff": false
                }
                """;
        FanpageBotResponse respTurn6 = runCleanTurn(
                orchestrator, mockLlm, pageId, senderId, convId, true,
                "Đặt gói 239k tối nay lúc 7 giờ", historyAfterTurn5, spaPersona, fusedChunks, turn6LlmJson, turn6Prompt
        );

        ConversationState.StructuredBookingState state = orchestrator.getLastTurnBookingState();
        ConversationState sessionState = ConversationState.getSessionState(pageId, senderId, convId);
        assertEquals("DS02", state.selectedProductId());
        assertEquals("tối nay", state.bookingDate());
        assertEquals("19:00", state.bookingTime(),
                "Test D phải chuẩn hóa 'tối nay lúc 7 giờ' thành bookingTime = '19:00'");
        assertEquals(ConversationState.BookingStatus.AWAITING_AVAILABILITY_CHECK, state.status(),
                "Test D: Khi chưa có công cụ kiểm tra lịch thực tế, bookingStatus bắt buộc là AWAITING_AVAILABILITY_CHECK, cấm tự lên CONFIRMED");
        assertNotEquals(ConversationState.BookingStatus.CONFIRMED, state.status());
        assertEquals("AWAITING_AVAILABILITY_CHECK", sessionState.bookingStatus());
        assertFalse(sessionState.bookingIntentConfirmed(),
                "bookingIntentConfirmed chỉ được true khi BookingStatus == CONFIRMED");

        // Ngay cả khi LLM điền nhầm intended_speech_act = BOOKING_CONFIRMED_ACK trong reflection,
        // hệ thống cũng phải chặn, không được lưu actualSentSpeechAct = BOOKING_CONFIRMED_ACK!
        assertNotEquals(CleanAgentOrchestratorService.SPEECH_ACT_BOOKING_CONFIRMED,
                brainService.getRecalledActualSentSpeechAct(pageId, senderId),
                "Khi bookingStatus != CONFIRMED, tuyệt đối không được phát SPEECH_ACT_BOOKING_CONFIRMED");

        assertTrue(turn6Prompt.get().contains("selected_product_id: \"DS02\""));
        assertTrue(turn6Prompt.get().contains("booking_date: \"tối nay\""));
        assertTrue(turn6Prompt.get().contains("booking_time: \"19:00\""));
        assertTrue(turn6Prompt.get().contains("booking_funnel_status: \"AWAITING_AVAILABILITY_CHECK\""));
        assertTrue(respTurn6.replyText().contains("239k") && respTurn6.replyText().contains("19h tối nay"));
        assertTrue(respTurn6.replyText().toLowerCase().contains("kiểm tra lịch trống"));
    }

    @Test
    void testSixStateLifecycle_AvailabilityCheck_ToCustomerConfirmation_ToOfficialConfirmed() {
        UUID pageId = UUID.randomUUID();
        String senderId = "sender-c3b-lifecycle";
        String convId = "conv-c3b-lifecycle";

        // Khởi tạo phiên từ AWAITING_AVAILABILITY_CHECK
        ConversationState.updateStructuredBookingStateInSession(
                pageId, senderId, convId,
                new ConversationState.StructuredBookingState(
                        "DS02", "Gói dưỡng sinh cơ bản (239k)", "tối nay",
                        LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).toString(),
                        "19:00", "Asia/Ho_Chi_Minh",
                        ConversationState.BookingStatus.AWAITING_AVAILABILITY_CHECK,
                        List.of("DS02")
                )
        );
        assertEquals(ConversationState.BookingStatus.AWAITING_AVAILABILITY_CHECK,
                ConversationState.getSessionState(pageId, senderId, convId).bookingStatusEnum());

        // Bước 5: Công cụ/nhân sự kiểm tra còn chỗ trống và chờ khách xác nhận chốt cuối cùng
        ConversationState afterSlotCheck = ConversationState.markSlotAvailableAwaitingCustomerConfirmation(
                pageId, senderId, convId, "DS02", "tối nay", "19:00"
        );
        assertEquals(ConversationState.BookingStatus.AWAITING_CUSTOMER_CONFIRMATION, afterSlotCheck.bookingStatusEnum());
        assertFalse(afterSlotCheck.bookingIntentConfirmed());

        // Bước 6: Công cụ đặt lịch / nhân sự phụ trách xác nhận chính thức -> CONFIRMED
        ConversationState afterOfficialConfirm = ConversationState.confirmBookingOfficiallyFromToolOrStaff(
                pageId, senderId, convId, "DS02", "tối nay", "19:00"
        );
        assertEquals(ConversationState.BookingStatus.CONFIRMED, afterOfficialConfirm.bookingStatusEnum());
        assertEquals("CONFIRMED", afterOfficialConfirm.bookingStatus());
        assertTrue(afterOfficialConfirm.bookingIntentConfirmed());
    }

    @Test
    void testMultiIndustryBookingState_TilesAndEducation_RespectSixStates() {
        String tileKb = """
                | Mã SP | Tên mẫu | Kích thước | Đơn giá | Khu vực sử dụng | Phân khúc |
                | --- | --- | --- | --- | --- | --- |
                | G8080 | Gạch Porcelain Vân Đá Trắng | 80x80cm | 245k/m² | phòng khách, phòng bếp | sản phẩm chính |
                | G6060 | Gạch Ceramic Vân Mây | 60x60cm | 165k/m² | phòng khách, phòng ngủ | tiết kiệm |
                """;
        List<PackageCatalogService.PackageSummary> tileCatalog = PackageCatalogService.extractCatalog(
                List.of(buildChunk("Bảng giá Gạch ốp lát", tileKb)),
                List.of("phòng khách", "phòng bếp", "phòng ngủ")
        );
        assertEquals(2, tileCatalog.size());

        CleanAgentCatalogAndPromptBuilder.TieredCatalogResult tileTiered =
                CleanAgentCatalogAndPromptBuilder.rankAndTierCatalogForCleanAgent(
                        tileCatalog, List.of(), List.of("phòng khách"), java.util.Set.of(), false
                );

        // Khi cả 2 mẫu gạch G8080 và G6060 đã giới thiệu, khách nói "Chốt đơn giao sáng mai giúp mình" -> AWAITING_PRODUCT_SELECTION
        ConversationState.StructuredBookingState tileAmbiguousOrder =
                CleanAgentOrchestratorService.resolveMultiIndustryBookingState(
                        "Chốt đơn giao sáng mai giúp mình",
                        ConversationState.StructuredBookingState.none(),
                        true,
                        false,
                        false,
                        tileCatalog,
                        tileCatalog,
                        tileTiered,
                        CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                        ZoneId.of("Asia/Ho_Chi_Minh"),
                        LocalDate.of(2026, 10, 29)
                );
        assertNull(tileAmbiguousOrder.selectedProductId());
        assertEquals("sáng mai", tileAmbiguousOrder.bookingDate());
        assertEquals("2026-10-30", tileAmbiguousOrder.resolvedIsoDate());
        assertEquals(ConversationState.BookingStatus.AWAITING_PRODUCT_SELECTION, tileAmbiguousOrder.status());

        // Khách chỉ định rõ "Lấy mẫu 80x80 nhé, giao lúc 9h sáng" -> G8080 + sáng mai + 09:00 -> AWAITING_AVAILABILITY_CHECK
        ConversationState.StructuredBookingState tileResolvedOrder =
                CleanAgentOrchestratorService.resolveMultiIndustryBookingState(
                        "Lấy mẫu 80x80 nhé, giao lúc 9h sáng",
                        tileAmbiguousOrder,
                        true,
                        false,
                        false,
                        tileCatalog,
                        tileCatalog,
                        tileTiered,
                        CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                        ZoneId.of("Asia/Ho_Chi_Minh"),
                        LocalDate.of(2026, 10, 29)
                );
        assertEquals("G8080", tileResolvedOrder.selectedProductId());
        assertEquals("sáng mai", tileResolvedOrder.bookingDate());
        assertEquals("09:00", tileResolvedOrder.bookingTime());
        assertEquals(ConversationState.BookingStatus.AWAITING_AVAILABILITY_CHECK, tileResolvedOrder.status());

        // Ngành Giáo dục: Đăng ký khóa IELTS 24 buổi
        String eduKb = """
                | Mã khóa | Tên khóa học | Thời lượng | Học phí | Đối tượng | Phân khúc |
                | --- | --- | --- | --- | --- | --- |
                | IELTS24 | Luyện thi IELTS Intensive | 24 buổi | 4.800.000đ/khóa | mất gốc, người đi làm | gói chính |
                | GT12 | Tiếng Anh Giao Tiếp Cơ Bản | 12 buổi | 2.400.000đ/khóa | người đi làm | tiết kiệm |
                """;
        List<PackageCatalogService.PackageSummary> eduCatalog = PackageCatalogService.extractCatalog(
                List.of(buildChunk("Bảng học phí", eduKb)),
                List.of("mất gốc", "người đi làm")
        );
        CleanAgentCatalogAndPromptBuilder.TieredCatalogResult eduTiered =
                CleanAgentCatalogAndPromptBuilder.rankAndTierCatalogForCleanAgent(
                        eduCatalog, List.of(), List.of("người đi làm"), java.util.Set.of(), false
                );
        ConversationState.StructuredBookingState eduBooking =
                CleanAgentOrchestratorService.resolveMultiIndustryBookingState(
                        "Đăng ký khóa 24 buổi cho mình",
                        ConversationState.StructuredBookingState.none(),
                        true,
                        false,
                        false,
                        eduCatalog,
                        eduCatalog,
                        eduTiered,
                        CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                        ZoneId.of("Asia/Ho_Chi_Minh"),
                        LocalDate.of(2026, 10, 29)
                );
        assertEquals("IELTS24", eduBooking.selectedProductId());
        assertEquals(ConversationState.BookingStatus.AWAITING_DATE_OR_TIME, eduBooking.status());
    }
}
