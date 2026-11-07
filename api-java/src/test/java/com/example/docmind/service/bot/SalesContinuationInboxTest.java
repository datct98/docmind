package com.example.docmind.service.bot;

import com.example.docmind.dto.bot.*;
import com.example.docmind.entity.*;
import com.example.docmind.repository.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;

class SalesContinuationInboxTest {
    @Test void callbackOnlyPersistsAssistantMessageAndNeverRefreshesCustomerState() {
        var sessions = mock(ConversationSessionRepository.class);
        var messages = mock(ConversationMessageRepository.class);
        var inbound = mock(InboundSessionPersistenceService.class);
        UUID page = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        when(sessions.findFirstByPageIdAndCustomerPsidOrderByUpdatedAtDesc(page, "sender")).thenReturn(Optional.of(
                ConversationSessionEntity.builder().id(session).pageId(page).customerPsid("sender").controlMode("BOT_ACTIVE").build()));
        var inbox = new ConversationInboxService(sessions, messages, null, null, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(inbox, "inboundSessionPersistenceService", inbound);
        var request = new FanpageBotRequest(page.toString(), "sender", "", "Giá", List.of(), List.of(), List.of(), 5,
                null, null, null, "GENERATE_PRICE_ANCHOR_BUBBLE", true, 123L, null, "conversation", false);
        var response = new FanpageBotResponse("Mình ưu tiên điều gì?", List.of(new FanpageBotResponse.ReplyBubble("Mình ưu tiên điều gì?", 0)),
                "CLARIFY", 0, false, null, null, null);
        inbox.recordCustomerAndBotTurn(request, response);
        verify(messages).save(argThat(m -> "AI_BOT".equals(m.getSenderRole()) && session.equals(m.getSessionId())));
        verify(messages, never()).save(argThat(m -> "CUSTOMER".equals(m.getSenderRole())));
        verify(inbound, never()).evaluateEntryAndPersistCustomer(any(), anyString());
        verify(inbound, never()).persistCustomerMessageForExistingSession(any(), anyString(), any(), any());
    }
}
