package com.example.docmind.dto;

import com.example.docmind.dto.bot.AgentSkillDto;
import com.example.docmind.dto.bot.CustomerInsightDto;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Response payload cho POST /api/feedback.
 */
public record FeedbackResponse(
        @JsonProperty("learned_rule")
        LearnedRuleDto learnedRule,

        @JsonProperty("regenerated_answer")
        String regeneratedAnswer,

        @JsonProperty("applied_rules")
        List<LearnedRuleDto> appliedRules,

        List<Citation> citations,

        @JsonProperty("customer_insight")
        CustomerInsightDto customerInsight,

        @JsonProperty("active_skills")
        List<AgentSkillDto> activeSkills
) {
    public FeedbackResponse(LearnedRuleDto learnedRule,
                            String regeneratedAnswer,
                            List<LearnedRuleDto> appliedRules,
                            List<Citation> citations) {
        this(learnedRule, regeneratedAnswer, appliedRules, citations, null, List.of());
    }

    public FeedbackResponse(LearnedRuleDto learnedRule, String regeneratedAnswer, List<Citation> citations) {
        this(learnedRule, regeneratedAnswer, learnedRule != null ? List.of(learnedRule) : List.of(), citations, null, List.of());
    }
}
