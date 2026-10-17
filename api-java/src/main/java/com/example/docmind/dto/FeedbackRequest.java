package com.example.docmind.dto;

import com.example.docmind.dto.bot.FanpageBotRequest;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Request payload cho POST /api/feedback.
 */
public record FeedbackRequest(
        @NotBlank(message = "question không được rỗng")
        String question,

        @JsonProperty("faulty_answer")
        @NotBlank(message = "faulty_answer không được rỗng")
        String faultyAnswer,

        @JsonProperty("user_correction")
        @NotBlank(message = "user_correction không được rỗng")
        String userCorrection,

        @JsonProperty("document_ids")
        List<String> documentIds,

        @JsonProperty("conversation_id")
        String conversationId,

        @JsonProperty("retrieved_context")
        String retrievedContext,

        @JsonProperty("page_id")
        String pageId,

        @JsonProperty("source")
        String source,

        @JsonProperty("history")
        List<FanpageBotRequest.ConversationTurn> history
) {
    public FeedbackRequest(String question,
                           String faultyAnswer,
                           String userCorrection,
                           List<String> documentIds,
                           String conversationId,
                           String retrievedContext) {
        this(question, faultyAnswer, userCorrection, documentIds, conversationId, retrievedContext, null, null, null);
    }

    public FeedbackRequest(String question,
                           String faultyAnswer,
                           String userCorrection,
                           List<String> documentIds,
                           String conversationId,
                           String retrievedContext,
                           String pageId) {
        this(question, faultyAnswer, userCorrection, documentIds, conversationId, retrievedContext, pageId, null, null);
    }

    public FeedbackRequest(String question,
                           String faultyAnswer,
                           String userCorrection,
                           List<String> documentIds,
                           String conversationId,
                           String retrievedContext,
                           String pageId,
                           String source) {
        this(question, faultyAnswer, userCorrection, documentIds, conversationId, retrievedContext, pageId, source, null);
    }
}
