package com.example.docmind.dto.bot;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.UUID;

/**
 * Request gửi tới API Bot trực tin nhắn Fanpage Facebook.
 * Hỗ trợ cả tin nhắn đơn lẻ (message) hoặc chùm tin nhắn liên tiếp (messages)
 * do khách hàng gõ ngắt dòng trên Messenger (cơ chế Debounce của AstrBot).
 */
public record FanpageBotRequest(
        @JsonProperty("page_id")
        String pageId,

        @JsonProperty("sender_id")
        String senderId,

        @JsonProperty("customer_name")
        String customerName,

        @JsonProperty("message")
        String message,

        @JsonProperty("messages")
        List<String> messages,

        @JsonProperty("history")
        List<ConversationTurn> history,

        @JsonProperty("document_ids")
        List<UUID> documentIds,

        @JsonProperty("top_k")
        Integer topK
) {
    public record ConversationTurn(
            @JsonProperty("role")
            String role,
            @JsonProperty("content")
            String content
    ) {
    }
}
