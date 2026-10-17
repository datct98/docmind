package com.example.docmind.dto.bot;

import com.example.docmind.entity.AgentSkillEntity;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO biểu diễn một Kỹ năng Agent (Tâm lý học / Chiến thuật Sale / Giao tiếp).
 */
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

        @JsonProperty("created_at")
        Instant createdAt,

        @JsonProperty("updated_at")
        Instant updatedAt
) {
    public static AgentSkillDto fromEntity(AgentSkillEntity e) {
        if (e == null) {
            return null;
        }
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
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }
}
