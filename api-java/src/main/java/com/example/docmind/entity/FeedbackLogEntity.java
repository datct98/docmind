package com.example.docmind.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity lưu log phản hồi sửa sai từ người dùng.
 */
@Entity
@Table(name = "feedback_logs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedbackLogEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "conversation_id")
    private String conversationId;

    @Column(name = "question", nullable = false, columnDefinition = "TEXT")
    private String question;

    @Column(name = "retrieved_context", columnDefinition = "TEXT")
    private String retrievedContext;

    @Column(name = "faulty_answer", nullable = false, columnDefinition = "TEXT")
    private String faultyAnswer;

    @Column(name = "user_correction", nullable = false, columnDefinition = "TEXT")
    private String userCorrection;

    @Column(name = "extracted_rule_id")
    private UUID extractedRuleId;

    @Column(name = "page_id")
    private UUID pageId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
