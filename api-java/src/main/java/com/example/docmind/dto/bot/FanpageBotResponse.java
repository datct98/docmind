package com.example.docmind.dto.bot;

import com.example.docmind.dto.LearnedRuleDto;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Response trả về từ API Bot trực tin nhắn Fanpage Facebook.
 * Không bao giờ chứa trích dẫn kỹ thuật [1] hay dữ liệu nội bộ;
 * cung cấp sẵn các bong bóng chat ngắn (reply_bubbles), cờ chuyển nhân viên thật (needs_human_handoff),
 * thông tin khách hàng tiềm năng / đặt lịch (extracted_lead), danh sách quy tắc AI Playbook (applied_rules),
 * hồ sơ đọc vị tâm lý khách hàng thời gian thực (customer_insight) và bộ Kỹ năng Agent đã kích hoạt (active_skills).
 */
public record FanpageBotResponse(
        @JsonProperty("reply_text")
        String replyText,

        @JsonProperty("reply_bubbles")
        List<ReplyBubble> replyBubbles,

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
        List<AgentSkillDto> activeSkills
) {
    public FanpageBotResponse {
        if (appliedRules == null) {
            appliedRules = List.of();
        }
        if (activeSkills == null) {
            activeSkills = List.of();
        }
    }

    public FanpageBotResponse(
            String replyText,
            List<ReplyBubble> replyBubbles,
            String salesStage,
            double confidenceScore,
            boolean needsHumanHandoff,
            String handoffCategory,
            String handoffReason,
            ExtractedLead extractedLead,
            List<LearnedRuleDto> appliedRules
    ) {
        this(
                replyText,
                replyBubbles,
                salesStage,
                confidenceScore,
                needsHumanHandoff,
                handoffCategory,
                handoffReason,
                extractedLead,
                appliedRules,
                null,
                List.of()
        );
    }

    public FanpageBotResponse(
            String replyText,
            List<ReplyBubble> replyBubbles,
            String salesStage,
            double confidenceScore,
            boolean needsHumanHandoff,
            String handoffCategory,
            String handoffReason,
            ExtractedLead extractedLead
    ) {
        this(
                replyText,
                replyBubbles,
                salesStage,
                confidenceScore,
                needsHumanHandoff,
                handoffCategory,
                handoffReason,
                extractedLead,
                List.of(),
                null,
                List.of()
        );
    }

    public record ReplyBubble(
            @JsonProperty("text")
            String text,

            @JsonProperty("typing_delay_ms")
            int typingDelayMs
    ) {
    }

    public record ExtractedLead(
            @JsonProperty("has_buying_or_booking_signal")
            boolean hasBuyingOrBookingSignal,

            @JsonProperty("phone_number")
            String phoneNumber,

            @JsonProperty("preferred_time")
            String preferredTime,

            @JsonProperty("summary_note")
            String summaryNote
    ) {
    }
}
