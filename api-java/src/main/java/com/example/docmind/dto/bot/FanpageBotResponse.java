package com.example.docmind.dto.bot;

import com.example.docmind.dto.LearnedRuleDto;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Response trả về từ API Bot trực tin nhắn Fanpage Facebook.
 * Không bao giờ chứa trích dẫn kỹ thuật [1] hay dữ liệu nội bộ;
 * cung cấp sẵn các bong bóng chat ngắn (reply_bubbles), cờ chuyển nhân viên thật (needs_human_handoff),
 * thông tin khách hàng tiềm năng / đặt lịch (extracted_lead), danh sách quy tắc AI Playbook (applied_rules),
 * hồ sơ đọc vị tâm lý khách hàng thời gian thực (customer_insight), bộ Kỹ năng Agent đã kích hoạt (active_skills),
 * và danh sách các deterministic guardrail hooks đã kích hoạt (triggered_hooks).
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
        if (appliedRules == null) {
            appliedRules = List.of();
        }
        if (activeSkills == null) {
            activeSkills = List.of();
        }
        if (triggeredHooks == null) {
            triggeredHooks = List.of();
        }
        if (bubbleDelaysMs == null) {
            bubbleDelaysMs = (replyBubbles != null)
                    ? replyBubbles.stream().map(ReplyBubble::typingDelayMs).toList()
                    : List.of();
        }
    }

    public FanpageBotResponse(
            String replyText,
            List<ReplyBubble> replyBubbles,
            List<Integer> bubbleDelaysMs,
            String salesStage,
            double confidenceScore,
            boolean needsHumanHandoff,
            String handoffCategory,
            String handoffReason,
            ExtractedLead extractedLead,
            List<LearnedRuleDto> appliedRules,
            CustomerInsightDto customerInsight,
            List<AgentSkillDto> activeSkills
    ) {
        this(
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
            ExtractedLead extractedLead,
            List<LearnedRuleDto> appliedRules,
            CustomerInsightDto customerInsight,
            List<AgentSkillDto> activeSkills
    ) {
        this(
                replyText,
                replyBubbles,
                (replyBubbles != null) ? replyBubbles.stream().map(ReplyBubble::typingDelayMs).toList() : List.of(),
                salesStage,
                confidenceScore,
                needsHumanHandoff,
                handoffCategory,
                handoffReason,
                extractedLead,
                appliedRules,
                customerInsight,
                activeSkills,
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
                List.of()
        );
    }

    public List<String> getTriggeredHooks() {
        return triggeredHooks;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .replyText(this.replyText)
                .replyBubbles(this.replyBubbles)
                .bubbleDelaysMs(this.bubbleDelaysMs)
                .salesStage(this.salesStage)
                .confidenceScore(this.confidenceScore)
                .needsHumanHandoff(this.needsHumanHandoff)
                .handoffCategory(this.handoffCategory)
                .handoffReason(this.handoffReason)
                .extractedLead(this.extractedLead)
                .appliedRules(this.appliedRules)
                .customerInsight(this.customerInsight)
                .activeSkills(this.activeSkills)
                .triggeredHooks(this.triggeredHooks);
    }

    public static class Builder {
        private String replyText;
        private List<ReplyBubble> replyBubbles = new ArrayList<>();
        private List<Integer> bubbleDelaysMs = new ArrayList<>();
        private String salesStage = "DISCOVERY";
        private double confidenceScore = 1.0;
        private boolean needsHumanHandoff = false;
        private String handoffCategory;
        private String handoffReason;
        private ExtractedLead extractedLead;
        private List<LearnedRuleDto> appliedRules = new ArrayList<>();
        private CustomerInsightDto customerInsight;
        private List<AgentSkillDto> activeSkills = new ArrayList<>();
        private List<String> triggeredHooks = new ArrayList<>();

        public Builder replyText(String replyText) {
            this.replyText = replyText;
            return this;
        }

        public Builder replyBubbles(List<ReplyBubble> replyBubbles) {
            this.replyBubbles = (replyBubbles != null) ? new ArrayList<>(replyBubbles) : new ArrayList<>();
            return this;
        }

        public Builder bubbleDelaysMs(List<Integer> bubbleDelaysMs) {
            this.bubbleDelaysMs = (bubbleDelaysMs != null) ? new ArrayList<>(bubbleDelaysMs) : new ArrayList<>();
            return this;
        }

        public Builder salesStage(String salesStage) {
            this.salesStage = salesStage;
            return this;
        }

        public Builder confidenceScore(double confidenceScore) {
            this.confidenceScore = confidenceScore;
            return this;
        }

        public Builder needsHumanHandoff(boolean needsHumanHandoff) {
            this.needsHumanHandoff = needsHumanHandoff;
            return this;
        }

        public Builder handoffCategory(String handoffCategory) {
            this.handoffCategory = handoffCategory;
            return this;
        }

        public Builder handoffReason(String handoffReason) {
            this.handoffReason = handoffReason;
            return this;
        }

        public Builder extractedLead(ExtractedLead extractedLead) {
            this.extractedLead = extractedLead;
            return this;
        }

        public Builder appliedRules(List<LearnedRuleDto> appliedRules) {
            this.appliedRules = (appliedRules != null) ? new ArrayList<>(appliedRules) : new ArrayList<>();
            return this;
        }

        public Builder customerInsight(CustomerInsightDto customerInsight) {
            this.customerInsight = customerInsight;
            return this;
        }

        public Builder activeSkills(List<AgentSkillDto> activeSkills) {
            this.activeSkills = (activeSkills != null) ? new ArrayList<>(activeSkills) : new ArrayList<>();
            return this;
        }

        public Builder triggeredHooks(List<String> triggeredHooks) {
            this.triggeredHooks = (triggeredHooks != null) ? new ArrayList<>(triggeredHooks) : new ArrayList<>();
            return this;
        }

        public Builder addTriggeredHook(String hookCode) {
            if (hookCode != null && !hookCode.isBlank() && !this.triggeredHooks.contains(hookCode)) {
                this.triggeredHooks.add(hookCode);
            }
            return this;
        }

        public FanpageBotResponse build() {
            return new FanpageBotResponse(
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
        public boolean hasAnyLead() {
            return (phoneNumber != null && !phoneNumber.isBlank()) || hasBuyingOrBookingSignal;
        }
    }
}
