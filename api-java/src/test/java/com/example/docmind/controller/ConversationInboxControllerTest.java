package com.example.docmind.controller;

import com.example.docmind.service.bot.ConversationInboxService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationInboxControllerTest {

    @Test
    void shouldListAndManageOmnichannelConversations() {
        ConversationInboxService service = mock(ConversationInboxService.class);
        ConversationInboxController controller = new ConversationInboxController(service);

        UUID pageId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID sessionId = UUID.fromString("a1000000-0000-0000-0000-000000000001");
        Map<String, Object> sampleSession = Map.of(
                "id", sessionId.toString(),
                "customer_name", "Anh Hoàng Minh",
                "control_mode", "BOT_ACTIVE",
                "status", "NEEDS_HANDOFF",
                "captured_phone", "0968221644"
        );

        when(service.listSessions(pageId, "ALL", ""))
                .thenReturn(Map.of("sessions", List.of(sampleSession)));
        when(service.updateControlMode(sessionId, "HUMAN_TAKEOVER"))
                .thenReturn(Map.of(
                        "id", sessionId.toString(),
                        "control_mode", "HUMAN_TAKEOVER",
                        "status", "HUMAN_TAKEOVER"
                ));

        ResponseEntity<List<Map<String, Object>>> listRes = controller.listSessions(
                pageId.toString(), "ALL", ""
        );
        assertThat(listRes.getStatusCode().value()).isEqualTo(200);
        assertThat(listRes.getBody()).hasSize(1);
        assertThat(listRes.getBody().get(0).get("customer_name")).isEqualTo("Anh Hoàng Minh");

        ResponseEntity<Map<String, Object>> modeRes = controller.updateControlMode(
                sessionId,
                Map.of("control_mode", "HUMAN_TAKEOVER", "status", "HUMAN_TAKEOVER")
        );
        assertThat(modeRes.getStatusCode().value()).isEqualTo(200);
        assertThat(modeRes.getBody()).containsEntry("control_mode", "HUMAN_TAKEOVER");
        verify(service).updateControlMode(sessionId, "HUMAN_TAKEOVER");
    }
}
