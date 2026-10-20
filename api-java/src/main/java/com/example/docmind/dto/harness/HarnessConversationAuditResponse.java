package com.example.docmind.dto.harness;

import com.example.docmind.dto.LearnedRuleDto;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Response payload for Whole-Conversation Harness² Audit & Multi-Turn Optimization
 * ({@code POST /api/harness/audit-conversation}).
 */
public record HarnessConversationAuditResponse(
        @JsonProperty("audit_id") String auditId,
        @JsonProperty("total_exchanges") int totalExchanges,
        @JsonProperty("issues_found_count") int issuesFoundCount,
        @JsonProperty("overall_score_before") double overallScoreBefore,
        @JsonProperty("overall_score_after") double overallScoreAfter,
        @JsonProperty("score_delta") double scoreDelta,
        @JsonProperty("executive_summary") String executiveSummary,
        @JsonProperty("state_summary") ConversationStateSummary stateSummary,
        @JsonProperty("turn_findings") List<TurnAuditFinding> turnFindings,
        @JsonProperty("learned_rules") List<LearnedRuleDto> learnedRules
) {
    public record ConversationStateSummary(
            @JsonProperty("resolved_honorific") String resolvedHonorific,
            @JsonProperty("confirmed_time_slot") String confirmedTimeSlot,
            @JsonProperty("confirmed_date") String confirmedDate,
            @JsonProperty("captured_phone") String capturedPhone,
            @JsonProperty("self_arrange_mode") boolean selfArrangeMode
    ) {
    }

    public record TurnAuditFinding(
            @JsonProperty("exchange_index") int exchangeIndex,
            @JsonProperty("assistant_message_id") String assistantMessageId,
            @JsonProperty("customer_message") String customerMessage,
            @JsonProperty("original_bot_reply") String originalBotReply,
            @JsonProperty("status") String status,
            @JsonProperty("issue_type") String issueType,
            @JsonProperty("issue_title") String issueTitle,
            @JsonProperty("what_went_wrong") String whatWentWrong,
            @JsonProperty("how_to_fix") String howToFix,
            @JsonProperty("rewritten_bot_reply") String rewrittenBotReply,
            @JsonProperty("rewritten_bubbles") List<String> rewrittenBubbles,
            @JsonProperty("score_before") double scoreBefore,
            @JsonProperty("score_after") double scoreAfter
    ) {
    }
}
