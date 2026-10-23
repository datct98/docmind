package com.example.docmind.dto;

import com.example.docmind.entity.SampleDialogueEntity;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO đại diện cho một hội thoại mẫu (Few-Shot Sample Dialogue: FD-*) gắn theo Fanpage.
 */
public record SampleDialogueDto(
        UUID id,
        @JsonProperty("dialogue_code") String dialogueCode,
        @JsonProperty("page_id") String pageId,
        String domain,
        String intent,
        String stage,
        @JsonProperty("customer_type") String customerType,
        String tone,
        @JsonProperty("turns_json") String turnsJson,
        @JsonProperty("user_summary") String userSummary,
        @JsonProperty("expected_bot_answer") String expectedBotAnswer,
        @JsonProperty("why_good") String whyGood,
        @JsonProperty("do_not_copy_literally") boolean doNotCopyLiterally,
        String tags,
        boolean active,
        @JsonProperty("review_status") String reviewStatus,
        @JsonProperty("quarantined_reason") String quarantinedReason,
        @JsonProperty("security_audit_notes") String securityAuditNotes,
        @JsonProperty("source_format") String sourceFormat,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt
) {
    public static SampleDialogueDto fromEntity(SampleDialogueEntity entity) {
        if (entity == null) {
            return null;
        }
        return new SampleDialogueDto(
                entity.getId(),
                entity.getDialogueCode(),
                entity.getPageId() != null ? entity.getPageId().toString() : null,
                entity.getDomain() != null ? entity.getDomain() : "spa_wellness",
                entity.getIntent(),
                entity.getStage(),
                entity.getCustomerType(),
                entity.getTone(),
                entity.getTurnsJson(),
                entity.getUserSummary(),
                entity.getExpectedBotAnswer(),
                entity.getWhyGood(),
                entity.isDoNotCopyLiterally(),
                entity.getTags(),
                entity.isActive(),
                entity.getReviewStatus() != null ? entity.getReviewStatus() : "APPROVED",
                entity.getQuarantinedReason(),
                entity.getSecurityAuditNotes(),
                entity.getSourceFormat() != null ? entity.getSourceFormat() : "YAML",
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
