package com.example.docmind.dto.bot;

import com.example.docmind.entity.AgentSkillEntity;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO biểu diễn một Kỹ năng Agent (Tâm lý học / Chiến thuật Sale / Giao tiếp)
 * kèm thông tin Quản trị Kỹ năng động (Tự động gộp trùng lặp & Cảnh báo xung đột chiến thuật).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentSkillDto(
        @JsonProperty("id")
        UUID id,

        @JsonProperty("code")
        String code,

        @JsonProperty("name")
        String name,

        @JsonProperty("category")
        String category,

        @JsonProperty("description")
        String description,

        @JsonProperty("trigger_signals")
        String triggerSignals,

        @JsonProperty("tactical_instructions")
        String tacticalInstructions,

        @JsonProperty("good_example")
        String goodExample,

        @JsonProperty("anti_pattern")
        String antiPattern,

        @JsonProperty("priority")
        int priority,

        @JsonProperty("active")
        boolean active,

        @JsonProperty("page_id")
        String pageId,

        @JsonProperty("applied_count")
        int appliedCount,

        @JsonProperty("conversion_count")
        int conversionCount,

        @JsonProperty("confidence_score")
        double confidenceScore,

        @JsonProperty("created_at")
        Instant createdAt,

        @JsonProperty("updated_at")
        Instant updatedAt,

        @JsonProperty("governance_action")
        String governanceAction,

        @JsonProperty("governance_message")
        String governanceMessage,

        @JsonProperty("conflict_detected")
        Boolean conflictDetected,

        @JsonProperty("conflicting_skill")
        AgentSkillDto conflictingSkill,

        @JsonProperty("conflict_reason")
        String conflictReason,

        @JsonProperty("recommended_action")
        String recommendedAction
) {
    public AgentSkillDto {
        if (confidenceScore <= 0.0) {
            confidenceScore = 0.90;
        }
    }

    public AgentSkillDto(
            UUID id,
            String code,
            String name,
            String category,
            String description,
            String triggerSignals,
            String tacticalInstructions,
            String goodExample,
            String antiPattern,
            int priority,
            boolean active,
            String pageId,
            int appliedCount,
            int conversionCount,
            double confidenceScore,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(id, code, name, category, description, triggerSignals, tacticalInstructions,
                goodExample, antiPattern, priority, active, pageId, appliedCount, conversionCount,
                confidenceScore, createdAt, updatedAt, null, null, null, null, null, null);
    }

    public AgentSkillDto(
            UUID id,
            String code,
            String name,
            String category,
            String description,
            String triggerSignals,
            String tacticalInstructions,
            String goodExample,
            String antiPattern,
            int priority,
            boolean active,
            String pageId,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(id, code, name, category, description, triggerSignals, tacticalInstructions,
                goodExample, antiPattern, priority, active, pageId, 0, 0, 0.90, createdAt, updatedAt);
    }

    public AgentSkillDto withGovernance(String action, String message) {
        return new AgentSkillDto(
                id, code, name, category, description, triggerSignals, tacticalInstructions,
                goodExample, antiPattern, priority, active, pageId, appliedCount, conversionCount,
                confidenceScore, createdAt, updatedAt, action, message, false, null, null, null
        );
    }

    public static AgentSkillDto conflictDetected(
            AgentSkillDto candidateSkill,
            AgentSkillDto existingConflictingSkill,
            String conflictReason,
            String recommendedAction
    ) {
        return new AgentSkillDto(
                candidateSkill.id(),
                candidateSkill.code(),
                candidateSkill.name(),
                candidateSkill.category(),
                candidateSkill.description(),
                candidateSkill.triggerSignals(),
                candidateSkill.tacticalInstructions(),
                candidateSkill.goodExample(),
                candidateSkill.antiPattern(),
                candidateSkill.priority(),
                candidateSkill.active(),
                candidateSkill.pageId(),
                candidateSkill.appliedCount(),
                candidateSkill.conversionCount(),
                candidateSkill.confidenceScore(),
                candidateSkill.createdAt(),
                candidateSkill.updatedAt(),
                "CONFLICT_DETECTED",
                "Phát hiện xung đột chiến thuật với kỹ năng hiện có: " + (existingConflictingSkill != null ? existingConflictingSkill.name() : ""),
                true,
                existingConflictingSkill,
                conflictReason,
                recommendedAction
        );
    }

    public static AgentSkillDto fromEntity(AgentSkillEntity e) {
        if (e == null) {
            return null;
        }
        double conf = e.getConfidenceScore() <= 0.0 ? 0.90 : e.getConfidenceScore();
        return new AgentSkillDto(
                e.getId(),
                e.getCode(),
                e.getName(),
                e.getCategory(),
                e.getDescription(),
                e.getTriggerSignals(),
                e.getTacticalInstructions(),
                e.getGoodExample(),
                e.getAntiPattern(),
                e.getPriority(),
                e.isActive(),
                e.getPageId() != null ? e.getPageId().toString() : null,
                e.getAppliedCount(),
                e.getConversionCount(),
                conf,
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }
}
