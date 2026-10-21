package com.example.docmind.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    @Builder.Default
    @Column(name = "confidence_score", nullable = false)
    private double confidenceScore = 0.85;

    @Builder.Default
    @Column(name = "applied_count", nullable = false)
    private int appliedCount = 0;

    @Builder.Default
    @Column(name = "success_count", nullable = false)
    private int successCount = 0;

    @Builder.Default
    @Column(name = "fail_count", nullable = false)
    private int failCount = 0;

    @Builder.Default
    @Column(name = "version", nullable = false)
    private int version = 1;

    @Column(name = "previous_rule_text", columnDefinition = "TEXT")
    private String previousRuleText;

    @Column(name = "quarantined_reason", columnDefinition = "TEXT")
    private String quarantinedReason;

    @Column(name = "last_applied_at")
    private Instant lastAppliedAt;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "dimension", nullable = false, length = 10)
    private HarnessDimension dimension = HarnessDimension.DOM;

    @Column(name = "cue_signal", columnDefinition = "TEXT")
    private String cueSignal;

    @Column(name = "contrastive_evidence", columnDefinition = "TEXT")
    private String contrastiveEvidence;

    @Builder.Default
    @Column(name = "always_on", nullable = false)
    private boolean alwaysOn = false;

    @Builder.Default
    @Column(name = "review_status", nullable = false, length = 32)
    private String reviewStatus = "APPROVED";

    @Column(name = "security_audit_notes", columnDefinition = "TEXT")
    private String securityAuditNotes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
