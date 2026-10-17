package com.example.docmind.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Request của POST /api/chat.
 * Hỗ trợ 1 hoặc nhiều document_id, kèm theo ngữ cảnh hội thoại đa lượt
 * để nhận diện phản hồi sửa sai (conversational correction).
 */
public record ChatRequest(
        @JsonProperty("document_ids")
        @NotEmpty(message = "document_ids không được rỗng")
        List<@NotBlank String> documentIds,

        @NotBlank(message = "question không được rỗng")
        String question,

        @JsonProperty("top_k")
        @Min(value = 1, message = "top_k tối thiểu 1")
        @Max(value = 50, message = "top_k tối đa 50")
        Integer topK,

        @JsonProperty("previous_question")
        String previousQuestion,

        @JsonProperty("previous_answer")
        String previousAnswer,

        @JsonProperty("conversation_id")
        String conversationId,

        @JsonProperty("page_id")
        String pageId
) {
    public ChatRequest(List<String> documentIds, String question, Integer topK) {
        this(documentIds, question, topK, null, null, null, null);
    }

    public ChatRequest(List<String> documentIds,
                       String question,
                       Integer topK,
                       String previousQuestion,
                       String previousAnswer,
                       String conversationId) {
        this(documentIds, question, topK, previousQuestion, previousAnswer, conversationId, null);
    }
}
