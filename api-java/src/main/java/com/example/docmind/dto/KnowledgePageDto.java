package com.example.docmind.dto;

import com.example.docmind.entity.KnowledgePageEntity;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/**
 * DTO trả về thông tin của một Thư mục / Page tri thức.
 */
public record KnowledgePageDto(
        String id,
        String name,
        String industry,
        @JsonProperty("target_audience") String targetAudience,
        String description,
        @JsonProperty("persona_config") PagePersonaConfig personaConfig,
        @JsonProperty("document_count") long documentCount,
        @JsonProperty("rule_count") long ruleCount,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("updated_at") OffsetDateTime updatedAt
) {
    public KnowledgePageDto(
            String id,
            String name,
            String industry,
            String targetAudience,
            String description,
            long documentCount,
            long ruleCount,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        this(
                id,
                name,
                industry,
                targetAudience,
                description,
                IndustryPersonaTemplates.resolveEffectiveConfig(null, name, industry, targetAudience, description),
                documentCount,
                ruleCount,
                createdAt,
                updatedAt
        );
    }

    public static KnowledgePageDto fromEntity(KnowledgePageEntity entity, long documentCount, long ruleCount) {
        return new KnowledgePageDto(
                entity.getId().toString(),
                entity.getName(),
                entity.getIndustry(),
                entity.getTargetAudience(),
                entity.getDescription(),
                entity.getPersonaConfig(),
                documentCount,
                ruleCount,
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
