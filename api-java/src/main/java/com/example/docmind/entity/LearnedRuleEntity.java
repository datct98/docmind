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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity lưu trữ quy tắc đúc kết từ phản hồi người dùng (AI Playbook).
 *
 * <p>Cột {@code embedding vector(384)} không ánh xạ qua JPA để tránh xung đột dialect;
 * được ghi và truy vấn vector qua {@code LearnedRuleRepositoryCustomImpl}.
 */
@Entity
@Table(name = "learned_rules")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LearnedRuleEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "category", nullable = false, length = 64)
    private String category;

    @Column(name = "trigger_condition", nullable = false, columnDefinition = "TEXT")
    private String triggerCondition;

    @Column(name = "root_cause", nullable = false, columnDefinition = "TEXT")
    private String rootCause;

    @Column(name = "generalized_rule", nullable = false, columnDefinition = "TEXT")
    private String generalizedRule;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "retrieval_keywords", nullable = false)
    private String[] retrievalKeywords;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "page_id")
    private UUID pageId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
