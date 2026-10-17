package com.example.docmind.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Request payload cho tạo mới và cập nhật rule qua /api/rules.
 */
public record RuleRequest(
        String category,

        @JsonProperty("trigger_condition")
        String triggerCondition,

        @JsonProperty("root_cause")
        String rootCause,

        @JsonProperty("generalized_rule")
        @NotBlank(message = "generalized_rule không được rỗng")
        String generalizedRule,

        @JsonProperty("retrieval_keywords")
        List<String> retrievalKeywords,

        Boolean active,

        @JsonProperty("page_id")
        String pageId
) {
    public RuleRequest(String category,
                       String triggerCondition,
                       String rootCause,
                       String generalizedRule,
                       List<String> retrievalKeywords,
                       Boolean active) {
        this(category, triggerCondition, rootCause, generalizedRule, retrievalKeywords, active, null);
    }
}
