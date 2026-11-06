package com.example.docmind.dto.bot;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * ACI V3 — Stage P3 (Directive §4.9 / §5.1): Decision Trace chuẩn hóa cho debug/audit observability.
 *
 * <p>Gắn vào metadata trả về của {@code POST /api/bot/fanpage/message} (field {@code decision_trace})
 * cho mỗi lượt ACI Cognitive V2. Mục tiêu: expose đủ quyết định (intent, subtype phản ứng tiêu cực,
 * fact changes, scopes trước/sau, skill/rule được chọn hoặc bị loại, catalog candidates theo branch
 * variant, response plan, sanitizer mutations) để không còn phải suy đoán các biến nội bộ.</p>
 *
 * <p><b>Redaction / PII:</b> nội dung tin nhắn khách KHÔNG được lưu thô — chỉ lưu
 * {@link #customerMessageHash} (SHA-256 rút gọn 16 ký tự hex). Trace không chứa raw prompt,
 * raw LLM output hay dữ liệu sức khỏe dạng câu văn; chỉ chứa nhãn/mã/giá trị đã chuẩn hóa.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DecisionTrace(
        @JsonProperty("turn_id")
        String turnId,

        /** SHA-256 rút gọn (16 hex chars) của tin nhắn khách — bảo mật PII, đủ để đối chiếu replay. */
        @JsonProperty("customer_message_hash")
        String customerMessageHash,

        @JsonProperty("detected_intent")
        String detectedIntent,

        /** NONE | CUSTOMER_CORRECTION | COMMUNICATION_COMPLAINT | OVER_INTRUSION | EXPLICIT_CONCISE_REQUEST */
        @JsonProperty("negative_reaction_subtype")
        String negativeReactionSubtype,

        @JsonProperty("fact_changes")
        FactChanges factChanges,

        @JsonProperty("scopes_before")
        List<String> scopesBefore,

        @JsonProperty("scopes_after")
        List<String> scopesAfter,

        @JsonProperty("selected_skills")
        List<SkillSelection> selectedSkills,

        /** Skill/rule bị loại do correction-turn gate (kèm lý do). Rỗng nếu lượt không phải correction. */
        @JsonProperty("excluded_skills_or_rules_on_correction")
        List<String> excludedSkillsOrRulesOnCorrection,

        @JsonProperty("catalog_candidates")
        List<CatalogCandidate> catalogCandidates,

        @JsonProperty("response_plan")
        ResponsePlanTrace responsePlan,

        @JsonProperty("sanitizer_mutations")
        SanitizerMutations sanitizerMutations,

        @JsonProperty("stage1_trace")
        Stage1ExecutionTrace stage1Trace
) {
    public DecisionTrace {
        scopesBefore = scopesBefore != null ? List.copyOf(scopesBefore) : List.of();
        scopesAfter = scopesAfter != null ? List.copyOf(scopesAfter) : List.of();
        selectedSkills = selectedSkills != null ? List.copyOf(selectedSkills) : List.of();
        excludedSkillsOrRulesOnCorrection = excludedSkillsOrRulesOnCorrection != null
                ? List.copyOf(excludedSkillsOrRulesOnCorrection) : List.of();
        catalogCandidates = catalogCandidates != null ? List.copyOf(catalogCandidates) : List.of();
    }

    public DecisionTrace(
            String turnId,
            String customerMessageHash,
            String detectedIntent,
            String negativeReactionSubtype,
            FactChanges factChanges,
            List<String> scopesBefore,
            List<String> scopesAfter,
            List<SkillSelection> selectedSkills,
            List<String> excludedSkillsOrRulesOnCorrection,
            List<CatalogCandidate> catalogCandidates,
            ResponsePlanTrace responsePlan,
            SanitizerMutations sanitizerMutations
    ) {
        this(
                turnId,
                customerMessageHash,
                detectedIntent,
                negativeReactionSubtype,
                factChanges,
                scopesBefore,
                scopesAfter,
                selectedSkills,
                excludedSkillsOrRulesOnCorrection,
                catalogCandidates,
                responsePlan,
                sanitizerMutations,
                null
        );
    }

    public DecisionTrace withStage1ExecutionTrace(Stage1ExecutionTrace trace) {
        return new DecisionTrace(
                turnId,
                customerMessageHash,
                detectedIntent,
                negativeReactionSubtype,
                factChanges,
                scopesBefore,
                scopesAfter,
                selectedSkills,
                excludedSkillsOrRulesOnCorrection,
                catalogCandidates,
                responsePlan,
                sanitizerMutations,
                trace
        );
    }

    public record FactChanges(
            @JsonProperty("extracted_pains")
            List<String> extractedPains,

            @JsonProperty("known_symptoms")
            List<String> knownSymptoms,

            @JsonProperty("known_body_areas")
            List<String> knownBodyAreas,

            @JsonProperty("retracted_bot_assumptions")
            List<String> retractedBotAssumptions,

            @JsonProperty("preferred_region")
            String preferredRegion
    ) {
        public FactChanges {
            extractedPains = extractedPains != null ? List.copyOf(extractedPains) : List.of();
            knownSymptoms = knownSymptoms != null ? List.copyOf(knownSymptoms) : List.of();
            knownBodyAreas = knownBodyAreas != null ? List.copyOf(knownBodyAreas) : List.of();
            retractedBotAssumptions = retractedBotAssumptions != null ? List.copyOf(retractedBotAssumptions) : List.of();
        }
    }

    public record SkillSelection(
            @JsonProperty("code")
            String code,

            @JsonProperty("reason")
            String reason
    ) {
    }

    public record CatalogCandidate(
            @JsonProperty("product_id")
            String productId,

            @JsonProperty("branch")
            String branch,

            @JsonProperty("price_display")
            String priceDisplay,

            @JsonProperty("duration_label")
            String durationLabel
    ) {
    }

    public record ResponsePlanTrace(
            @JsonProperty("recommended_action")
            String recommendedAction,

            @JsonProperty("communication_style_preference")
            String communicationStylePreference,

            @JsonProperty("turn_depth")
            String turnDepth,

            @JsonProperty("explicit_price_demand")
            boolean explicitPriceDemand
    ) {
    }

    public record SanitizerMutations(
            @JsonProperty("false_apology_rewritten")
            boolean falseApologyRewritten,

            @JsonProperty("cliche_empathy_stripped")
            boolean clicheEmpathyStripped,

            @JsonProperty("price_grounding_applied")
            boolean priceGroundingApplied
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Stage1ExecutionTrace(
            @JsonProperty("correlation_id")
            String correlationId,

            @JsonProperty("turn_id")
            String turnId,

            @JsonProperty("model")
            String model,

            @JsonProperty("provider")
            String provider,

            @JsonProperty("prompt_version")
            String promptVersion,

            @JsonProperty("branch_id")
            String branchId,

            @JsonProperty("catalog_snapshot_id")
            String catalogSnapshotId,

            @JsonProperty("raw_llm_output")
            List<String> rawLlmOutput,

            @JsonProperty("parsed_structured_output")
            String parsedStructuredOutput,

            @JsonProperty("post_reconciliation_output")
            List<String> postReconciliationOutput,

            @JsonProperty("referee_result")
            String refereeResult,

            @JsonProperty("sanitizer_result")
            String sanitizerResult,

            @JsonProperty("final_output")
            List<String> finalOutput,

            @JsonProperty("buying_readiness")
            String buyingReadiness,

            @JsonProperty("booking_state_before")
            String bookingStateBefore,

            @JsonProperty("booking_state_after")
            String bookingStateAfter,

            @JsonProperty("state_transition_reason")
            String stateTransitionReason,

            @JsonProperty("step_timings")
            StepTimings stepTimings,

            @JsonProperty("input_tokens")
            int inputTokens,

            @JsonProperty("output_tokens")
            int outputTokens,

            @JsonProperty("reasoning_tokens")
            int reasoningTokens,

            @JsonProperty("retry_count")
            int retryCount,

            @JsonProperty("retry_reason")
            String retryReason,

            @JsonProperty("fallback_used")
            boolean fallbackUsed,

            @JsonProperty("fallback_reason")
            String fallbackReason,

            @JsonProperty("processing_stage")
            String processingStage,

            @JsonProperty("delivery_stage")
            String deliveryStage,

            @JsonProperty("lifecycle_stages")
            List<String> lifecycleStages,

            @JsonProperty("sanitizer_mutation_audit")
            SanitizerMutationAudit sanitizerMutationAudit
    ) {
        public Stage1ExecutionTrace {
            rawLlmOutput = rawLlmOutput != null ? List.copyOf(rawLlmOutput) : List.of();
            postReconciliationOutput = postReconciliationOutput != null ? List.copyOf(postReconciliationOutput) : List.of();
            finalOutput = finalOutput != null ? List.copyOf(finalOutput) : List.of();
            lifecycleStages = lifecycleStages != null ? List.copyOf(lifecycleStages) : List.of();
        }
    }

    public record StepTimings(
            @JsonProperty("retrieval_ms")
            long retrievalMs,

            @JsonProperty("prompt_build_ms")
            long promptBuildMs,

            @JsonProperty("llm_ms")
            long llmMs,

            @JsonProperty("validation_ms")
            long validationMs,

            @JsonProperty("total_turn_ms")
            long totalTurnMs,

            @JsonProperty("turn_deadline_ms")
            long turnDeadlineMs
    ) {
    }

    public record SanitizerMutationAudit(
            @JsonProperty("mutated")
            boolean mutated,

            @JsonProperty("changed_char_count")
            int changedCharCount,

            @JsonProperty("changed_segment_count")
            int changedSegmentCount,

            @JsonProperty("diff_summary")
            String diffSummary,

            @JsonProperty("mutation_reasons")
            List<String> mutationReasons,

            @JsonProperty("mutating_components")
            List<String> mutatingComponents
    ) {
        public SanitizerMutationAudit {
            mutationReasons = mutationReasons != null ? List.copyOf(mutationReasons) : List.of();
            mutatingComponents = mutatingComponents != null ? List.copyOf(mutatingComponents) : List.of();
        }
    }

    /**
     * SHA-256 rút gọn (16 ký tự hex đầu) của một chuỗi — dùng cho {@code customer_message_hash}.
     * Trả về {@code null} nếu input null/blank. Không bao giờ trả về chuỗi gốc.
     */
    public static String sha256Short(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.strip().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(Character.forDigit((hash[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(hash[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 luôn có sẵn trên JVM chuẩn; nhánh này chỉ để an toàn tuyệt đối.
            return null;
        }
    }
}
