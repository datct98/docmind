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
 * Entity lưu trữ các hội thoại mẫu (Few-Shot Sample Dialogues: FD-*) gắn theo từng Fanpage (page_id).
 * Cột {@code embedding vector(384)} được cập nhật và truy vấn qua native SQL / JdbcTemplate.
 */
@Entity
@Table(name = "sample_dialogues")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SampleDialogueEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "dialogue_code", nullable = false, length = 100)
    private String dialogueCode;

    @Column(name = "page_id")
    private UUID pageId;

    @Builder.Default
    @Column(name = "domain", length = 100)
    private String domain = "spa_wellness";

    @Column(name = "intent", length = 100)
    private String intent;

    @Column(name = "stage", length = 100)
    private String stage;

    @Column(name = "customer_type", length = 128)
    private String customerType;

    @Column(name = "tone", length = 100)
    private String tone;

    @Column(name = "turns_json", nullable = false, columnDefinition = "TEXT")
    private String turnsJson;

    @Column(name = "user_summary", nullable = false, columnDefinition = "TEXT")
    private String userSummary;

    @Column(name = "expected_bot_answer", nullable = false, columnDefinition = "TEXT")
    private String expectedBotAnswer;

    @Column(name = "why_good", columnDefinition = "TEXT")
    private String whyGood;

    @Builder.Default
    @Column(name = "do_not_copy_literally", nullable = false)
    private boolean doNotCopyLiterally = true;

    @Column(name = "tags", length = 512)
    private String tags;

    @Builder.Default
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Builder.Default
    @Column(name = "review_status", nullable = false, length = 32)
    private String reviewStatus = "APPROVED";

    @Builder.Default
    @Column(name = "quality_label", length = 64)
    private String qualityLabel = "APPROVED_GOOD";

    @Column(name = "quarantined_reason", columnDefinition = "TEXT")
    private String quarantinedReason;

    @Column(name = "security_audit_notes", columnDefinition = "TEXT")
    private String securityAuditNotes;

    @Builder.Default
    @Column(name = "source_format", nullable = false, length = 32)
    private String sourceFormat = "YAML";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
