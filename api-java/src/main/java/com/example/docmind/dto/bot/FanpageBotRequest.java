package com.example.docmind.dto.bot;

import com.fasterxml.jackson.annotation.JsonAlias;
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
        @JsonAlias({"pageId"})
        String pageId,

        @JsonProperty("sender_id")
        @JsonAlias({"senderId"})
        String senderId,

        @JsonProperty("customer_name")
        @JsonAlias({"customerName"})
        String customerName,

        @JsonProperty("message")
        String message,

        @JsonProperty("messages")
        List<String> messages,

        @JsonProperty("history")
        @JsonAlias({"conversation_history", "conversationHistory"})
        List<ConversationTurn> history,

        @JsonProperty("document_ids")
        List<UUID> documentIds,

        @JsonProperty("top_k")
        Integer topK,

        @JsonProperty("previous_temperature")
        Integer previousTemperature,

        @JsonProperty("reply_delay_seconds")
        Integer replyDelaySeconds,

        @JsonProperty("idle_seconds")
        Integer idleSeconds,

        @JsonProperty("action")
        String action
) {
    /**
     * Constructor tương thích ngược cho các bài test và caller cũ (8 tham số).
     */
    public FanpageBotRequest(
            String pageId,
            String senderId,
            String customerName,
            String message,
            List<String> messages,
            List<ConversationTurn> history,
            List<UUID> documentIds,
            Integer topK
    ) {
        this(pageId, senderId, customerName, message, messages, history, documentIds, topK, null, null, null, null);
    }

    public record ConversationTurn(
            @JsonProperty("role")
            String role,
            @JsonProperty("content")
            String content
    ) {
    }
}
