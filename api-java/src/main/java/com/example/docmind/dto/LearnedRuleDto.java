package com.example.docmind.dto;

import com.example.docmind.entity.LearnedRuleEntity;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * DTO đại diện cho một quy tắc rút kinh nghiệm (AI Playbook rule).
 */
public record LearnedRuleDto(
        UUID id,
        String category,
        @JsonProperty("trigger_condition") String triggerCondition,
        @JsonProperty("root_cause") String rootCause,
        @JsonProperty("generalized_rule") String generalizedRule,
        @JsonProperty("retrieval_keywords") List<String> retrievalKeywords,
        boolean active,
        @JsonProperty("page_id") String pageId,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt
) {
    public LearnedRuleDto(UUID id,
                          String category,
                          String triggerCondition,
                          String rootCause,
                          String generalizedRule,
                          List<String> retrievalKeywords,
                          boolean active,
                          Instant createdAt,
                          Instant updatedAt) {
        this(id, category, triggerCondition, rootCause, generalizedRule, retrievalKeywords, active, null, createdAt, updatedAt);
    }

    public static LearnedRuleDto fromEntity(LearnedRuleEntity entity) {
        if (entity == null) {
            return null;
        }
        List<String> keywords = entity.getRetrievalKeywords() == null
                ? List.of()
                : Arrays.asList(entity.getRetrievalKeywords());
        return new LearnedRuleDto(
                entity.getId(),
                entity.getCategory(),
                entity.getTriggerCondition(),
                entity.getRootCause(),
                entity.getGeneralizedRule(),
                keywords,
                entity.isActive(),
                entity.getPageId() != null ? entity.getPageId().toString() : null,
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
