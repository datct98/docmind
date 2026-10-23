package com.example.docmind.dto;

import com.example.docmind.entity.HarnessDimension;
import com.example.docmind.entity.LearnedRule;
import com.example.docmind.entity.LearnedRuleEntity;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * DTO đại diện cho một quy tắc rút kinh nghiệm (AI Playbook rule).
 * Cập nhật cấu trúc Experience Library theo Google Research Harness²:
 * dimension (INV | DOM), cue_signal, contrastive_evidence, always_on.
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
        @JsonProperty("confidence_score") double confidenceScore,
        @JsonProperty("applied_count") int appliedCount,
        @JsonProperty("success_count") int successCount,
        @JsonProperty("fail_count") int failCount,
        @JsonProperty("version") int version,
        @JsonProperty("previous_rule_text") String previousRuleText,
        @JsonProperty("quarantined_reason") String quarantinedReason,
        @JsonProperty("last_applied_at") Instant lastAppliedAt,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("dimension") String dimension,
        @JsonProperty("cue_signal") String cueSignal,
        @JsonProperty("contrastive_evidence") String contrastiveEvidence,
        @JsonProperty("always_on") boolean alwaysOn,
        @JsonProperty("review_status") String reviewStatus,
        @JsonProperty("security_audit_notes") String securityAuditNotes,
        @JsonProperty("rule_code") String ruleCode,
        @JsonProperty("title") String title,
        @JsonProperty("intent") String intent,
        @JsonProperty("stage") String stage,
        @JsonProperty("priority") Integer priority,
        @JsonProperty("do_actions") String doActions,
        @JsonProperty("dont_actions") String dontActions,
        @JsonProperty("grounding_policy") String groundingPolicy,
        @JsonProperty("handoff_policy") String handoffPolicy,
        @JsonProperty("source_format") String sourceFormat
) {
    public LearnedRuleDto {
        if (confidenceScore <= 0.0) {
            confidenceScore = 0.85;
        }
        if (version <= 0) {
            version = 1;
        }
        if (dimension == null || dimension.isBlank()) {
            dimension = "DOM";
        }
        if (reviewStatus == null || reviewStatus.isBlank()) {
            reviewStatus = "APPROVED";
        }
        if (priority == null || priority <= 0) {
            priority = 80;
        }
        if (groundingPolicy == null || groundingPolicy.isBlank()) {
            groundingPolicy = "must_use_kb_document_context";
        }
        if (handoffPolicy == null || handoffPolicy.isBlank()) {
            handoffPolicy = "none";
        }
        if (sourceFormat == null || sourceFormat.isBlank()) {
            sourceFormat = "MANUAL";
        }
    }

    public LearnedRuleDto(UUID id,
                          String category,
                          String triggerCondition,
                          String rootCause,
                          String generalizedRule,
                          List<String> retrievalKeywords,
                          boolean active,
                          String pageId,
                          double confidenceScore,
                          int appliedCount,
                          int successCount,
                          int failCount,
                          int version,
                          String previousRuleText,
                          String quarantinedReason,
                          Instant lastAppliedAt,
                          Instant createdAt,
                          Instant updatedAt,
                          String dimension,
                          String cueSignal,
                          String contrastiveEvidence,
                          boolean alwaysOn,
                          String reviewStatus,
                          String securityAuditNotes) {
        this(id, category, triggerCondition, rootCause, generalizedRule, retrievalKeywords, active, pageId,
                confidenceScore, appliedCount, successCount, failCount, version, previousRuleText,
                quarantinedReason, lastAppliedAt, createdAt, updatedAt, dimension, cueSignal,
                contrastiveEvidence, alwaysOn, reviewStatus, securityAuditNotes,
                null, null, null, null, 80, null, null, "must_use_kb_document_context", "none", "MANUAL");
    }

    public LearnedRuleDto(UUID id,
                          String category,
                          String triggerCondition,
                          String rootCause,
                          String generalizedRule,
                          List<String> retrievalKeywords,
                          boolean active,
                          String pageId,
                          double confidenceScore,
                          int appliedCount,
                          int successCount,
                          int failCount,
                          int version,
                          String previousRuleText,
                          String quarantinedReason,
                          Instant lastAppliedAt,
                          Instant createdAt,
                          Instant updatedAt,
                          String dimension,
                          String cueSignal,
                          String contrastiveEvidence,
                          boolean alwaysOn) {
        this(id, category, triggerCondition, rootCause, generalizedRule, retrievalKeywords, active, pageId,
                confidenceScore, appliedCount, successCount, failCount, version, previousRuleText,
                quarantinedReason, lastAppliedAt, createdAt, updatedAt, dimension, cueSignal,
                contrastiveEvidence, alwaysOn, "APPROVED", null);
    }

    public LearnedRuleDto(UUID id,
                          String category,
                          String triggerCondition,
                          String rootCause,
                          String generalizedRule,
                          List<String> retrievalKeywords,
                          boolean active,
                          Instant createdAt,
                          Instant updatedAt) {
        this(id, category, triggerCondition, rootCause, generalizedRule, retrievalKeywords, active, null,
                0.85, 0, 0, 0, 1, null, null, null, createdAt, updatedAt, "DOM", "", null, false);
    }

    public LearnedRuleDto(UUID id,
                          String category,
                          String triggerCondition,
                          String rootCause,
                          String generalizedRule,
                          List<String> retrievalKeywords,
                          boolean active,
                          String pageId,
                          Instant createdAt,
                          Instant updatedAt) {
        this(id, category, triggerCondition, rootCause, generalizedRule, retrievalKeywords, active, pageId,
                0.85, 0, 0, 0, 1, null, null, null, createdAt, updatedAt, "DOM", "", null, false);
    }

    public LearnedRuleDto(UUID id,
                          String category,
                          String triggerCondition,
                          String rootCause,
                          String generalizedRule,
                          List<String> retrievalKeywords,
                          boolean active,
                          String pageId,
                          double confidenceScore,
                          int appliedCount,
                          int successCount,
                          int failCount,
                          int version,
                          String previousRuleText,
                          String quarantinedReason,
                          Instant lastAppliedAt,
                          Instant createdAt,
                          Instant updatedAt) {
        this(id, category, triggerCondition, rootCause, generalizedRule, retrievalKeywords, active, pageId,
                confidenceScore, appliedCount, successCount, failCount, version, previousRuleText,
                quarantinedReason, lastAppliedAt, createdAt, updatedAt, "DOM", "", null, false);
    }

    public boolean hasExperience() {
        return cueSignal != null && !cueSignal.isBlank()
                && generalizedRule != null && !generalizedRule.isBlank()
                && contrastiveEvidence != null && !contrastiveEvidence.isBlank();
    }

    public int characterCount() {
        int count = 0;
        if (cueSignal != null) count += cueSignal.length();
        if (generalizedRule != null) count += generalizedRule.length();
        if (contrastiveEvidence != null) count += contrastiveEvidence.length();
        if ((cueSignal == null || cueSignal.isBlank()) && triggerCondition != null) {
            count += triggerCondition.length();
        }
        return count;
    }

    public String toPromptBlock() {
        return String.format(
                "[%s] Cue: %s | Lesson: %s | Evidence: %s",
                dimension != null ? dimension : "DOM",
                cueSignal != null && !cueSignal.isBlank() ? cueSignal : (triggerCondition != null ? triggerCondition : ""),
                generalizedRule != null ? generalizedRule : "",
                contrastiveEvidence != null && !contrastiveEvidence.isBlank()
                        ? contrastiveEvidence.replace("\n", " ")
                        : "N/A"
        );
    }

    public static LearnedRuleDto fromEntity(LearnedRuleEntity entity) {
        if (entity == null) {
            return null;
        }
        List<String> keywords = entity.getRetrievalKeywords() == null
                ? List.of()
                : Arrays.asList(entity.getRetrievalKeywords());
        double conf = entity.getConfidenceScore() <= 0.0 ? 0.85 : entity.getConfidenceScore();
        int ver = entity.getVersion() <= 0 ? 1 : entity.getVersion();
        String dim = entity.getDimension() != null ? entity.getDimension().name() : "DOM";
        return new LearnedRuleDto(
                entity.getId(),
                entity.getCategory(),
                entity.getTriggerCondition(),
                entity.getRootCause(),
                entity.getGeneralizedRule(),
                keywords,
                entity.isActive(),
                entity.getPageId() != null ? entity.getPageId().toString() : null,
                conf,
                entity.getAppliedCount(),
                entity.getSuccessCount(),
                entity.getFailCount(),
                ver,
                entity.getPreviousRuleText(),
                entity.getQuarantinedReason(),
                entity.getLastAppliedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                dim,
                entity.getCueSignal(),
                entity.getContrastiveEvidence(),
                entity.isAlwaysOn(),
                entity.getReviewStatus() != null ? entity.getReviewStatus() : "APPROVED",
                entity.getSecurityAuditNotes(),
                entity.getRuleCode(),
                entity.getTitle(),
                entity.getIntent(),
                entity.getStage(),
                entity.getPriority() != null ? entity.getPriority() : 80,
                entity.getDoActions(),
                entity.getDontActions(),
                entity.getGroundingPolicy(),
                entity.getHandoffPolicy(),
                entity.getSourceFormat()
        );
    }

    public static LearnedRuleDto fromLearnedRule(LearnedRule rule) {
        if (rule == null) {
            return null;
        }
        return rule.toDto();
    }

    public LearnedRuleEntity toEntity() {
        UUID pid = null;
        if (pageId != null && !pageId.isBlank()) {
            try {
                pid = UUID.fromString(pageId);
            } catch (IllegalArgumentException ignored) {}
        }
        return LearnedRuleEntity.builder()
                .id(id != null ? id : UUID.randomUUID())
                .category(category != null ? category : "GENERAL")
                .triggerCondition(triggerCondition != null ? triggerCondition : "")
                .rootCause(rootCause != null ? rootCause : "")
                .generalizedRule(generalizedRule != null ? generalizedRule : "")
                .retrievalKeywords(retrievalKeywords != null ? retrievalKeywords.toArray(new String[0]) : new String[0])
                .active(active)
                .pageId(pid)
                .confidenceScore(confidenceScore > 0 ? confidenceScore : 0.85)
                .appliedCount(appliedCount)
                .successCount(successCount)
                .failCount(failCount)
                .version(version > 0 ? version : 1)
                .previousRuleText(previousRuleText)
                .quarantinedReason(quarantinedReason)
                .lastAppliedAt(lastAppliedAt)
                .createdAt(createdAt != null ? createdAt : Instant.now())
                .updatedAt(updatedAt != null ? updatedAt : Instant.now())
                .dimension(HarnessDimension.fromString(dimension))
                .cueSignal(cueSignal)
                .contrastiveEvidence(contrastiveEvidence)
                .alwaysOn(alwaysOn)
                .reviewStatus(reviewStatus != null && !reviewStatus.isBlank() ? reviewStatus : "APPROVED")
                .securityAuditNotes(securityAuditNotes)
                .ruleCode(ruleCode)
                .title(title)
                .intent(intent)
                .stage(stage)
                .priority(priority != null ? priority : 80)
                .doActions(doActions)
                .dontActions(dontActions)
                .groundingPolicy(groundingPolicy != null ? groundingPolicy : "must_use_kb_document_context")
                .handoffPolicy(handoffPolicy != null ? handoffPolicy : "none")
                .sourceFormat(sourceFormat != null ? sourceFormat : "MANUAL")
                .build();
    }

    public LearnedRule toLearnedRule() {
        return LearnedRule.fromDto(this);
    }
}
