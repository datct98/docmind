package com.example.docmind.dto.harness;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Request payload for Whole-Conversation Harness² Audit & Multi-Turn Optimization
 * ({@code POST /api/harness/audit-conversation}).
 */
public record HarnessConversationAuditRequest(
        @JsonProperty("page_id") String pageId,
        @JsonProperty("conversation_id") String conversationId,
        @JsonProperty("customer_name") String customerName,
        @JsonProperty("turns") List<AuditTurnInput> turns,
        @JsonProperty("auto_apply_rules") Boolean autoApplyRules
) {
    public record AuditTurnInput(
            @JsonProperty("turn_index") Integer turnIndex,
            @JsonProperty("role") String role,
            @JsonProperty("content") String content,
            @JsonProperty("message_id") String messageId
    ) {
    }
}
