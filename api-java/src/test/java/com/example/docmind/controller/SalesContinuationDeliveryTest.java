package com.example.docmind.controller;

import com.example.docmind.dto.bot.*;
import com.example.docmind.service.bot.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SalesContinuationDeliveryTest {
    @Test void facebookOnlyContinuesAfterAcceptedPriceAndRecordsAssistantContinuation() {
        var service = mock(FanpageBotService.class);
        var inbox = mock(ConversationInboxService.class);
        var controller = new FanpageBotController(service, inbox);
        controller.setPageAccessToken("test-not-a-real-token");
        AtomicInteger sent = new AtomicInteger();
        controller.setGraphApiSender((url, body) -> { if (body.contains("\"message\"")) sent.incrementAndGet(); return new FanpageBotController.HttpResponseResult(200, "{\"message_id\":\"mock-mid\"}"); });
        String page = UUID.randomUUID().toString();
        String sender = UUID.randomUUID().toString();
        var req = new FanpageBotRequest(page, sender, "", "giá", List.of(), List.of(), List.of(), 5);
        var first = new FanpageBotResponse("Giá", List.of(new FanpageBotResponse.ReplyBubble("Giá", 0)), List.of(0),
                "ANSWER", 0, false, null, null, null, List.of(), null, List.of(), List.of("SALES_CORE_V1"))
                .withPendingSecondBubble(true, 123L, null);
        var second = new FanpageBotResponse("Tiếp nối", List.of(new FanpageBotResponse.ReplyBubble("Tiếp nối", 30)), List.of(30),
                "CLARIFY", 0, false, null, null, null, List.of(), null, List.of(), List.of("SALES_CORE_V1", "SALES_CORE_CONTINUATION"));
        when(service.handleCustomerMessage(any())).thenReturn(second);
        controller.completePendingSalesBubble(req, first, 0);
        verifyNoInteractions(service, inbox);
        controller.completePendingSalesBubble(req, first, 1);
        verify(service).handleCustomerMessage(argThat(r -> "GENERATE_PRICE_ANCHOR_BUBBLE".equals(r.action()) && r.pendingTurnSequence() == 123L));
        verify(inbox).recordCustomerAndBotTurn(argThat(r -> "GENERATE_PRICE_ANCHOR_BUBBLE".equals(r.action())), eq(second));
        assertThat(sent).hasValue(1);
    }
}
