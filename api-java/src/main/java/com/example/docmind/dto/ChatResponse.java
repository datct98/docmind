package com.example.docmind.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Response của POST /api/chat: câu trả lời, danh sách citation,
 * các quy tắc đã áp dụng (applied_rules) và quy tắc mới học được (newly_learned_rule).
 */
public record ChatResponse(
        String answer,
        List<Citation> citations,
        @JsonProperty("applied_rules")
        List<LearnedRuleDto> appliedRules,
        @JsonProperty("newly_learned_rule")
        LearnedRuleDto newlyLearnedRule
) {
    public ChatResponse(String answer, List<Citation> citations) {
        this(answer, citations, List.of(), null);
    }
}
