package com.docmind.fanpage;

import com.example.docmind.dto.LearnedRuleDto;
import com.example.docmind.dto.bot.AgentSkillDto;
import com.example.docmind.dto.bot.CustomerInsightDto;
import com.example.docmind.dto.bot.FanpageBotResponse.ExtractedLead;
import com.example.docmind.dto.bot.FanpageBotResponse.ReplyBubble;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;

/**
 * Compatibility model for com.docmind.fanpage.FanpageBotResponse.
 * Wraps and converts to/from com.example.docmind.dto.bot.FanpageBotResponse.
 */
public record FanpageBotResponse(
        @JsonProperty("reply_text")
        String replyText,

        @JsonProperty("reply_bubbles")
        List<ReplyBubble> replyBubbles,

        @JsonProperty("bubble_delays_ms")
        List<Integer> bubbleDelaysMs,

        @JsonProperty("sales_stage")
        String salesStage,

        @JsonProperty("confidence_score")
        double confidenceScore,

        @JsonProperty("needs_human_handoff")
        boolean needsHumanHandoff,

        @JsonProperty("handoff_category")
        String handoffCategory,

        @JsonProperty("handoff_reason")
        String handoffReason,

        @JsonProperty("extracted_lead")
        ExtractedLead extractedLead,

        @JsonProperty("applied_rules")
        List<LearnedRuleDto> appliedRules,

        @JsonProperty("customer_insight")
        CustomerInsightDto customerInsight,

        @JsonProperty("active_skills")
        List<AgentSkillDto> activeSkills,

        @JsonProperty("triggered_hooks")
        List<String> triggeredHooks
) {
    public FanpageBotResponse {
        if (appliedRules == null) appliedRules = Collections.emptyList();
        if (activeSkills == null) activeSkills = Collections.emptyList();
        if (triggeredHooks == null) triggeredHooks = Collections.emptyList();
        if (bubbleDelaysMs == null) {
            bubbleDelaysMs = (replyBubbles != null)
                    ? replyBubbles.stream().map(ReplyBubble::typingDelayMs).toList()
                    : Collections.emptyList();
        }
    }

    public static FanpageBotResponse from(com.example.docmind.dto.bot.FanpageBotResponse resp) {
        if (resp == null) return null;
        return new FanpageBotResponse(
                resp.replyText(),
                resp.replyBubbles(),
                resp.bubbleDelaysMs(),
                resp.salesStage(),
                resp.confidenceScore(),
                resp.needsHumanHandoff(),
                resp.handoffCategory(),
                resp.handoffReason(),
                resp.extractedLead(),
                resp.appliedRules(),
                resp.customerInsight(),
                resp.activeSkills(),
                resp.triggeredHooks()
        );
    }

    public com.example.docmind.dto.bot.FanpageBotResponse toDto() {
        return new com.example.docmind.dto.bot.FanpageBotResponse(
                replyText,
                replyBubbles,
                bubbleDelaysMs,
                salesStage,
                confidenceScore,
                needsHumanHandoff,
                handoffCategory,
                handoffReason,
                extractedLead,
                appliedRules,
                customerInsight,
                activeSkills,
                triggeredHooks
        );
    }

    public List<String> getTriggeredHooks() {
        return triggeredHooks;
    }
}
