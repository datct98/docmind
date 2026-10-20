package com.example.docmind.entity;

import com.example.docmind.dto.LearnedRuleDto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Domain entity representing a learned operational rule / Experience Library entry.
 * Based on Google Research Harness² (arXiv:2610.14224) 3-part schema:
 * {@code Cue – Lesson – Contrastive Evidence}.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LearnedRule {

    private UUID id;
    private String category;
    private String triggerCondition;
    private String rootCause;
    private String generalizedRule;
    private String[] retrievalKeywords;
    private boolean active;
    private UUID pageId;

    @Builder.Default
    private double confidenceScore = 0.85;

    @Builder.Default
    private int appliedCount = 0;

    @Builder.Default
    private int successCount = 0;

    @Builder.Default
    private int failCount = 0;

    @Builder.Default
    private int version = 1;

    private String previousRuleText;
    private String quarantinedReason;
    private Instant lastAppliedAt;
    private Instant createdAt;
    private Instant updatedAt;

    // Harness² Experience Library enhancements (Requirement R2)
    @Builder.Default
    private HarnessDimension dimension = HarnessDimension.DOM;

    private String cueSignal;
    private String contrastiveEvidence;

    @Builder.Default
    private boolean alwaysOn = false;

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
                dimension != null ? dimension : HarnessDimension.DOM,
                cueSignal != null && !cueSignal.isBlank() ? cueSignal : (triggerCondition != null ? triggerCondition : ""),
                generalizedRule != null ? generalizedRule : "",
                contrastiveEvidence != null && !contrastiveEvidence.isBlank()
                        ? contrastiveEvidence.replace("\n", " ")
                        : "N/A"
        );
    }

    public static LearnedRule fromEntity(LearnedRuleEntity entity) {
        if (entity == null) return null;
        return LearnedRule.builder()
                .id(entity.getId())
                .category(entity.getCategory())
                .triggerCondition(entity.getTriggerCondition())
                .rootCause(entity.getRootCause())
                .generalizedRule(entity.getGeneralizedRule())
                .retrievalKeywords(entity.getRetrievalKeywords())
                .active(entity.isActive())
                .pageId(entity.getPageId())
                .confidenceScore(entity.getConfidenceScore())
                .appliedCount(entity.getAppliedCount())
                .successCount(entity.getSuccessCount())
                .failCount(entity.getFailCount())
                .version(entity.getVersion())
                .previousRuleText(entity.getPreviousRuleText())
                .quarantinedReason(entity.getQuarantinedReason())
                .lastAppliedAt(entity.getLastAppliedAt())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .dimension(entity.getDimension() != null ? entity.getDimension() : HarnessDimension.DOM)
                .cueSignal(entity.getCueSignal())
                .contrastiveEvidence(entity.getContrastiveEvidence())
                .alwaysOn(entity.isAlwaysOn())
                .build();
    }

    public LearnedRuleEntity toEntity() {
        return LearnedRuleEntity.builder()
                .id(id != null ? id : UUID.randomUUID())
                .category(category != null ? category : "GENERAL")
                .triggerCondition(triggerCondition != null ? triggerCondition : "")
                .rootCause(rootCause != null ? rootCause : "")
                .generalizedRule(generalizedRule != null ? generalizedRule : "")
                .retrievalKeywords(retrievalKeywords != null ? retrievalKeywords : new String[0])
                .active(active)
                .pageId(pageId)
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
                .dimension(dimension != null ? dimension : HarnessDimension.DOM)
                .cueSignal(cueSignal)
                .contrastiveEvidence(contrastiveEvidence)
                .alwaysOn(alwaysOn)
                .build();
    }

    public static LearnedRule fromDto(LearnedRuleDto dto) {
        if (dto == null) return null;
        UUID parsedPageId = null;
        if (dto.pageId() != null && !dto.pageId().isBlank()) {
            try {
                parsedPageId = UUID.fromString(dto.pageId());
            } catch (IllegalArgumentException ignored) {}
        }
        String[] keywords = dto.retrievalKeywords() != null
                ? dto.retrievalKeywords().toArray(new String[0])
                : new String[0];

        return LearnedRule.builder()
                .id(dto.id() != null ? dto.id() : UUID.randomUUID())
                .category(dto.category() != null ? dto.category() : "GENERAL")
                .triggerCondition(dto.triggerCondition() != null ? dto.triggerCondition() : "")
                .rootCause(dto.rootCause() != null ? dto.rootCause() : "")
                .generalizedRule(dto.generalizedRule() != null ? dto.generalizedRule() : "")
                .retrievalKeywords(keywords)
                .active(dto.active())
                .pageId(parsedPageId)
                .confidenceScore(dto.confidenceScore() > 0 ? dto.confidenceScore() : 0.85)
                .appliedCount(dto.appliedCount())
                .successCount(dto.successCount())
                .failCount(dto.failCount())
                .version(dto.version() > 0 ? dto.version() : 1)
                .previousRuleText(dto.previousRuleText())
                .quarantinedReason(dto.quarantinedReason())
                .lastAppliedAt(dto.lastAppliedAt())
                .createdAt(dto.createdAt() != null ? dto.createdAt() : Instant.now())
                .updatedAt(dto.updatedAt() != null ? dto.updatedAt() : Instant.now())
                .dimension(HarnessDimension.fromString(dto.dimension()))
                .cueSignal(dto.cueSignal())
                .contrastiveEvidence(dto.contrastiveEvidence())
                .alwaysOn(dto.alwaysOn())
                .build();
    }

    public LearnedRuleDto toDto() {
        List<String> keywords = retrievalKeywords != null ? Arrays.asList(retrievalKeywords) : List.of();
        return new LearnedRuleDto(
                id,
                category,
                triggerCondition,
                rootCause,
                generalizedRule,
                keywords,
                active,
                pageId != null ? pageId.toString() : null,
                confidenceScore > 0 ? confidenceScore : 0.85,
                appliedCount,
                successCount,
                failCount,
                version > 0 ? version : 1,
                previousRuleText,
                quarantinedReason,
                lastAppliedAt,
                createdAt,
                updatedAt,
                dimension != null ? dimension.name() : "DOM",
                cueSignal,
                contrastiveEvidence,
                alwaysOn
        );
    }
}
