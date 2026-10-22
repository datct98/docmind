package com.example.docmind.dto.bot;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request payload để Tạo mới hoặc Cập nhật Kỹ năng Agent (POST/PUT /api/skills).
 */
public record AgentSkillRequest(
        @JsonProperty("code")
        String code,

        @JsonProperty("name")
        String name,

        @JsonProperty("category")
        String category,

        @JsonProperty("description")
        String description,

        @JsonProperty("trigger_signals")
        @JsonAlias("triggerSignals")
        String triggerSignals,

        @JsonProperty("tactical_instructions")
        @JsonAlias("tacticalInstructions")
        String tacticalInstructions,

        @JsonProperty("good_example")
        @JsonAlias("goodExample")
        String goodExample,

        @JsonProperty("anti_pattern")
        @JsonAlias("antiPattern")
        String antiPattern,

        @JsonProperty("priority")
        Integer priority,

        @JsonProperty("active")
        Boolean active,

        @JsonProperty("page_id")
        @JsonAlias("pageId")
        String pageId,

        @JsonProperty("raw_instruction")
        @JsonAlias({"rawInstruction", "natural_input", "naturalInput"})
        String rawInstruction,

        @JsonProperty("resolution_strategy")
        @JsonAlias("resolutionStrategy")
        String resolutionStrategy,

        @JsonProperty("conflict_skill_id")
        @JsonAlias("conflictSkillId")
        String conflictSkillId
) {
    public AgentSkillRequest(
            String code,
            String name,
            String category,
            String description,
            String triggerSignals,
            String tacticalInstructions,
            String goodExample,
            String antiPattern,
            Integer priority,
            Boolean active,
            String pageId
    ) {
        this(code, name, category, description, triggerSignals, tacticalInstructions, goodExample, antiPattern, priority, active, pageId, null, null, null);
    }

    public AgentSkillRequest(
            String code,
            String name,
            String category,
            String description,
            String triggerSignals,
            String tacticalInstructions,
            String goodExample,
            String antiPattern,
            Integer priority,
            Boolean active,
            String pageId,
            String rawInstruction
    ) {
        this(code, name, category, description, triggerSignals, tacticalInstructions, goodExample, antiPattern, priority, active, pageId, rawInstruction, null, null);
    }
}

