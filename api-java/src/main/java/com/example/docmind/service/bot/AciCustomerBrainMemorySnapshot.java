package com.example.docmind.service.bot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Unit P2.4 (V35): Canonical JSONB snapshot for {@code conversation_sessions.aci_memory_state}
 * with deterministic Jackson round-trip serialization, Lazy Hydration support, and explicit
 * {@code Monotonic Merge} semantics for time-ordered collections ({@code confirmed_facts},
 * {@code self_reported_symptoms}, {@code unresolved_requests}) and priority booking/commercial state.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AciCustomerBrainMemorySnapshot(
        @JsonProperty("schema_version") int schemaVersion,
        @JsonProperty("last_updated_turn_id") String lastUpdatedTurnId,
        @JsonProperty("last_updated_turn_seq") int lastUpdatedTurnSeq,
        @JsonProperty("last_updated_at") String lastUpdatedAt,
        // === Draft 2 Layer 1: Conversational Context ===
        @JsonProperty("current_topic") String currentTopic,
        @JsonProperty("topic_history") List<String> topicHistory,
        @JsonProperty("unresolved_requests") List<PersistedUnresolvedRequest> unresolvedRequests,
        @JsonProperty("pending_references") List<String> pendingReferences,
        @JsonProperty("last_bot_action") String lastBotAction,
        // === Draft 2 Layer 2: Consultative Profile & Time-Ordered Versioned Facts ===
        @JsonProperty("last_turn_intent") String lastTurnIntent,
        @JsonProperty("conversation_goal") String conversationGoal,
        @JsonProperty("confirmed_facts") List<PersistedVersionedFact> confirmedFacts,
        @JsonProperty("self_reported_symptoms") List<PersistedVersionedFact> selfReportedSymptoms,
        @JsonProperty("barrier_hypothesis") PersistedBarrierHypothesis barrierHypothesis,
        @JsonProperty("emotion") PersistedEmotion emotion,
        @JsonProperty("communication_style_preference") String communicationStylePreference,
        @JsonProperty("effective_turn_depth") String effectiveTurnDepth,
        @JsonProperty("interaction_permission") String interactionPermission,
        // === Draft 2 Layer 3 & Response Plan ===
        @JsonProperty("interested_package_id") String interestedPackageId,
        @JsonProperty("last_response_plan") PersistedResponsePlan lastResponsePlan,
        // === Commercial, Booking & Pronoun Memory ===
        @JsonProperty("price_objection_count") int priceObjectionCount,
        @JsonProperty("in_price_objection_context") boolean inPriceObjectionContext,
        @JsonProperty("has_presented_downsell") boolean hasPresentedDownsell,
        @JsonProperty("structured_booking_state") PersistedBookingState structuredBookingState,
        @JsonProperty("inferred_customer_pronoun") String inferredCustomerPronoun,
        @JsonProperty("inferred_pronoun_confidence") double inferredPronounConfidence,
        @JsonProperty("inferred_pronoun_evidence_turn_id") String inferredPronounEvidenceTurnId,
        @JsonProperty("verified_symptom_duration") String verifiedSymptomDuration,
        // === 5-Tier ACI Memory & Commitments ===
        @JsonProperty("customer_statements") List<PersistedCustomerStatement> customerStatements,
        @JsonProperty("aci_working_hypothesis") PersistedWorkingHypothesis aciWorkingHypothesis,
        @JsonProperty("authoritative_business_facts") List<PersistedBusinessFact> authoritativeBusinessFacts,
        @JsonProperty("operational_state") PersistedOperationalState operationalState,
        @JsonProperty("previous_strategy_record") PersistedPreviousStrategy previousStrategyRecord,
        @JsonProperty("session_commitments") List<PersistedCommitment> sessionCommitments,
        // === Auxiliary Continuity Fields ===
        @JsonProperty("verified_facts") List<String> verifiedFacts,
        @JsonProperty("superseded_or_retracted_facts") List<String> supersededOrRetractedFacts,
        @JsonProperty("uncertain_explorations") List<String> uncertainExplorations,
        @JsonProperty("care_notes") List<String> careNotes,
        @JsonProperty("working_hypothesis") String workingHypothesis,
        @JsonProperty("last_actual_sent_speech_act") String lastActualSentSpeechAct,
        @JsonProperty("memory_reset_at") String memoryResetAt,
        @JsonProperty("secondary_intents") List<String> secondaryIntents,
        @JsonProperty("conversation_state") String conversationState
) {

    public AciCustomerBrainMemorySnapshot(
            int schemaVersion,
            String lastUpdatedTurnId,
            int lastUpdatedTurnSeq,
            String lastUpdatedAt,
            String currentTopic,
            List<String> topicHistory,
            List<PersistedUnresolvedRequest> unresolvedRequests,
            List<String> pendingReferences,
            String lastBotAction,
            String lastTurnIntent,
            String conversationGoal,
            List<PersistedVersionedFact> confirmedFacts,
            List<PersistedVersionedFact> selfReportedSymptoms,
            PersistedBarrierHypothesis barrierHypothesis,
            PersistedEmotion emotion,
            String communicationStylePreference,
            String effectiveTurnDepth,
            String interactionPermission,
            String interestedPackageId,
            PersistedResponsePlan lastResponsePlan,
            int priceObjectionCount,
            boolean inPriceObjectionContext,
            boolean hasPresentedDownsell,
            PersistedBookingState structuredBookingState,
            String inferredCustomerPronoun,
            double inferredPronounConfidence,
            String inferredPronounEvidenceTurnId,
            String verifiedSymptomDuration,
            List<PersistedCustomerStatement> customerStatements,
            PersistedWorkingHypothesis aciWorkingHypothesis,
            List<PersistedBusinessFact> authoritativeBusinessFacts,
            PersistedOperationalState operationalState,
            PersistedPreviousStrategy previousStrategyRecord,
            List<PersistedCommitment> sessionCommitments,
            List<String> verifiedFacts,
            List<String> supersededOrRetractedFacts,
            List<String> uncertainExplorations,
            List<String> careNotes,
            String workingHypothesis,
            String lastActualSentSpeechAct,
            String memoryResetAt
    ) {
        this(
                schemaVersion, lastUpdatedTurnId, lastUpdatedTurnSeq, lastUpdatedAt,
                currentTopic, topicHistory, unresolvedRequests, pendingReferences, lastBotAction,
                lastTurnIntent, conversationGoal, confirmedFacts, selfReportedSymptoms,
                barrierHypothesis, emotion, communicationStylePreference, effectiveTurnDepth,
                interactionPermission, interestedPackageId, lastResponsePlan,
                priceObjectionCount, inPriceObjectionContext, hasPresentedDownsell,
                structuredBookingState, inferredCustomerPronoun, inferredPronounConfidence,
                inferredPronounEvidenceTurnId, verifiedSymptomDuration,
                customerStatements, aciWorkingHypothesis, authoritativeBusinessFacts,
                operationalState, previousStrategyRecord, sessionCommitments,
                verifiedFacts, supersededOrRetractedFacts, uncertainExplorations, careNotes,
                workingHypothesis, lastActualSentSpeechAct, memoryResetAt,
                null, null
        );
    }

    public static final int CURRENT_SCHEMA_VERSION = 1;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern TURN_SEQ_PATTERN = Pattern.compile("(?i)(?:^|\\b)(?:t|turn[_-]?)(\\d+)(?:\\b|$)");

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PersistedVersionedFact(
            @JsonProperty("key") String key,
            @JsonProperty("value") String value,
            @JsonProperty("status") String status,
            @JsonProperty("source") String source,
            @JsonProperty("source_turn_id") String sourceTurnId,
            @JsonProperty("updated_turn_id") String updatedTurnId,
            @JsonProperty("observed_at") String observedAt,
            @JsonProperty("validity") String validity,
            @JsonProperty("confidence") String confidence
    ) {
        public static PersistedVersionedFact fromDomain(AdaptiveConversationIntelligence.VersionedCustomerFact f) {
            if (f == null) {
                return null;
            }
            return new PersistedVersionedFact(
                    f.key(),
                    f.value(),
                    f.status(),
                    f.source(),
                    f.sourceTurnId(),
                    f.sourceTurnId(),
                    f.observedAt(),
                    f.validity() != null ? f.validity().name() : AdaptiveConversationIntelligence.FactValidity.CURRENT.name(),
                    f.confidence()
            );
        }

        public AdaptiveConversationIntelligence.VersionedCustomerFact toDomain() {
            String effectiveTurn = nonBlankOrDefault(updatedTurnId, nonBlankOrDefault(sourceTurnId, "T_CURRENT"));
            AdaptiveConversationIntelligence.FactValidity val =
                    AdaptiveConversationIntelligence.FactValidity.fromString(validity);
            return new AdaptiveConversationIntelligence.VersionedCustomerFact(
                    key,
                    value,
                    status,
                    source,
                    effectiveTurn,
                    observedAt,
                    val,
                    confidence
            );
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PersistedUnresolvedRequest(
            @JsonProperty("item") String item,
            @JsonProperty("status") String status,
            @JsonProperty("source_turn_id") String sourceTurnId,
            @JsonProperty("updated_turn_id") String updatedTurnId,
            @JsonProperty("observed_at") String observedAt
    ) {
        public static PersistedUnresolvedRequest fromDomain(
                AdaptiveConversationIntelligence.UnresolvedRequestItem r,
                String defaultObservedAt
        ) {
            if (r == null) {
                return null;
            }
            return new PersistedUnresolvedRequest(
                    r.item(),
                    r.status() != null ? r.status().name() : AdaptiveConversationIntelligence.UnresolvedRequestStatus.OPEN.name(),
                    r.sourceTurnId(),
                    r.updatedTurnId(),
                    nonBlankOrDefault(defaultObservedAt, Instant.now().toString())
            );
        }

        public AdaptiveConversationIntelligence.UnresolvedRequestItem toDomain() {
            return new AdaptiveConversationIntelligence.UnresolvedRequestItem(
                    item,
                    AdaptiveConversationIntelligence.UnresolvedRequestStatus.fromString(status),
                    sourceTurnId,
                    updatedTurnId
            );
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PersistedBarrierHypothesis(
            @JsonProperty("barrier_type") String barrierType,
            @JsonProperty("hypothesis") String hypothesis,
            @JsonProperty("confidence_level") String confidenceLevel,
            @JsonProperty("confidence_score") double confidenceScore,
            @JsonProperty("evidence_turn_ids") List<String> evidenceTurnIds,
            @JsonProperty("lifecycle_op") String lifecycleOp
    ) {
        public static PersistedBarrierHypothesis fromDomain(AdaptiveConversationIntelligence.BarrierHypothesis b) {
            if (b == null) {
                return null;
            }
            return new PersistedBarrierHypothesis(
                    b.barrierType(),
                    b.hypothesis(),
                    b.confidenceLevel(),
                    b.confidenceScore(),
                    b.evidenceTurnIds(),
                    b.lifecycleOp() != null ? b.lifecycleOp().name() : AdaptiveConversationIntelligence.HypothesisLifecycleOp.MAINTAIN.name()
            );
        }

        public AdaptiveConversationIntelligence.BarrierHypothesis toDomain() {
            AdaptiveConversationIntelligence.HypothesisLifecycleOp op =
                    parseEnumOrDefault(AdaptiveConversationIntelligence.HypothesisLifecycleOp.class,
                            lifecycleOp, AdaptiveConversationIntelligence.HypothesisLifecycleOp.MAINTAIN);
            return new AdaptiveConversationIntelligence.BarrierHypothesis(
                    barrierType,
                    hypothesis,
                    confidenceLevel,
                    confidenceScore,
                    evidenceTurnIds != null ? evidenceTurnIds : List.of(),
                    op
            );
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PersistedEmotion(
            @JsonProperty("primary") String primary,
            @JsonProperty("secondary") List<String> secondary,
            @JsonProperty("confidence") double confidence
    ) {
        public static PersistedEmotion fromDomain(AdaptiveConversationIntelligence.EmotionalState e) {
            if (e == null) {
                return null;
            }
            return new PersistedEmotion(e.primary(), e.secondary(), e.confidence());
        }

        public AdaptiveConversationIntelligence.EmotionalState toDomain() {
            return new AdaptiveConversationIntelligence.EmotionalState(
                    nonBlankOrDefault(primary, "CALM"),
                    secondary != null ? secondary : List.of(),
                    confidence
            );
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PersistedResponsePlan(
            @JsonProperty("objective") String objective,
            @JsonProperty("strategy") String strategy,
            @JsonProperty("tone") String tone,
            @JsonProperty("depth") String depth,
            @JsonProperty("next_action") String nextAction,
            @JsonProperty("cta_level") String ctaLevel,
            @JsonProperty("adaptation") String adaptation,
            @JsonProperty("previous_outcome") String previousOutcome
    ) {
        public static PersistedResponsePlan fromDomain(AdaptiveConversationIntelligence.AdaptiveResponsePlan p) {
            if (p == null) {
                return null;
            }
            return new PersistedResponsePlan(
                    p.objective(),
                    p.strategy(),
                    p.tone(),
                    p.depth(),
                    p.nextAction(),
                    p.ctaLevel() != null ? p.ctaLevel().name() : null,
                    p.adaptation() != null ? p.adaptation().name() : null,
                    p.previousOutcome() != null ? p.previousOutcome().name() : null
            );
        }

        public AdaptiveConversationIntelligence.AdaptiveResponsePlan toDomain() {
            AdaptiveConversationIntelligence.CtaLevel cta =
                    AdaptiveConversationIntelligence.CtaLevel.fromString(ctaLevel, strategy, nextAction);
            AdaptiveConversationIntelligence.StrategyAdaptationMode adapt =
                    parseEnumOrDefault(AdaptiveConversationIntelligence.StrategyAdaptationMode.class,
                            adaptation, AdaptiveConversationIntelligence.StrategyAdaptationMode.CONTINUATION);
            AdaptiveConversationIntelligence.StrategyOutcome prevOut =
                    parseEnumOrDefault(AdaptiveConversationIntelligence.StrategyOutcome.class,
                            previousOutcome, AdaptiveConversationIntelligence.StrategyOutcome.NOT_APPLICABLE);
            return new AdaptiveConversationIntelligence.AdaptiveResponsePlan(
                    objective, strategy, tone, depth, nextAction, cta, adapt, prevOut
            );
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PersistedBookingState(
            @JsonProperty("selected_product_id") String selectedProductId,
            @JsonProperty("selected_product_label") String selectedProductLabel,
            @JsonProperty("booking_date") String bookingDate,
            @JsonProperty("resolved_iso_date") String resolvedIsoDate,
            @JsonProperty("booking_time") String bookingTime,
            @JsonProperty("timezone_id") String timezoneId,
            @JsonProperty("status") String status,
            @JsonProperty("candidate_product_ids") List<String> candidateProductIds
    ) {
        public static PersistedBookingState fromDomain(ConversationState.StructuredBookingState b) {
            if (b == null) {
                return null;
            }
            return new PersistedBookingState(
                    b.selectedProductId(),
                    b.selectedProductLabel(),
                    b.bookingDate(),
                    b.resolvedIsoDate(),
                    b.bookingTime(),
                    b.timezoneId(),
                    b.status() != null ? b.status().name() : ConversationState.BookingStatus.NONE.name(),
                    b.candidateProductIds()
            );
        }

        public ConversationState.StructuredBookingState toDomain() {
            ConversationState.BookingStatus st = parseEnumOrDefault(
                    ConversationState.BookingStatus.class,
                    status,
                    ConversationState.BookingStatus.NONE
            );
            return new ConversationState.StructuredBookingState(
                    selectedProductId,
                    selectedProductLabel,
                    bookingDate,
                    resolvedIsoDate,
                    bookingTime,
                    nonBlankOrDefault(timezoneId, "Asia/Ho_Chi_Minh"),
                    st,
                    candidateProductIds != null ? List.copyOf(candidateProductIds) : List.of()
            );
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PersistedCustomerStatement(
            @JsonProperty("statement_id") String statementId,
            @JsonProperty("turn_id") String turnId,
            @JsonProperty("category") String category,
            @JsonProperty("content") String content,
            @JsonProperty("recorded_at") String recordedAt
    ) {
        public static PersistedCustomerStatement fromDomain(AdaptiveConversationIntelligence.CustomerStatementRecord r) {
            if (r == null) {
                return null;
            }
            return new PersistedCustomerStatement(
                    r.statementId(),
                    r.turnId(),
                    r.category(),
                    r.content(),
                    r.recordedAt() != null ? r.recordedAt().toString() : Instant.now().toString()
            );
        }

        public AdaptiveConversationIntelligence.CustomerStatementRecord toDomain() {
            return new AdaptiveConversationIntelligence.CustomerStatementRecord(
                    statementId,
                    turnId,
                    category,
                    content,
                    false,
                    parseInstantOrDefault(recordedAt, Instant.now())
            );
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PersistedWorkingHypothesis(
            @JsonProperty("content") String content,
            @JsonProperty("last_lifecycle_op") String lastLifecycleOp,
            @JsonProperty("confidence") double confidence,
            @JsonProperty("evidence_turn_ids") List<String> evidenceTurnIds,
            @JsonProperty("updated_at") String updatedAt
    ) {
        public static PersistedWorkingHypothesis fromDomain(AdaptiveConversationIntelligence.WorkingHypothesisRecord w) {
            if (w == null) {
                return null;
            }
            return new PersistedWorkingHypothesis(
                    w.content(),
                    w.lastLifecycleOp() != null ? w.lastLifecycleOp().name() : AdaptiveConversationIntelligence.HypothesisLifecycleOp.MAINTAIN.name(),
                    w.confidence(),
                    w.evidenceTurnIds(),
                    w.updatedAt() != null ? w.updatedAt().toString() : Instant.now().toString()
            );
        }

        public AdaptiveConversationIntelligence.WorkingHypothesisRecord toDomain() {
            AdaptiveConversationIntelligence.HypothesisLifecycleOp op =
                    parseEnumOrDefault(AdaptiveConversationIntelligence.HypothesisLifecycleOp.class,
                            lastLifecycleOp, AdaptiveConversationIntelligence.HypothesisLifecycleOp.MAINTAIN);
            return new AdaptiveConversationIntelligence.WorkingHypothesisRecord(
                    content,
                    op,
                    confidence,
                    evidenceTurnIds != null ? evidenceTurnIds : List.of(),
                    parseInstantOrDefault(updatedAt, Instant.now())
            );
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PersistedBusinessFact(
            @JsonProperty("fact_id") String factId,
            @JsonProperty("category") String category,
            @JsonProperty("fact_key") String factKey,
            @JsonProperty("fact_value") String factValue,
            @JsonProperty("authoritative_source") String authoritativeSource,
            @JsonProperty("verified_at") String verifiedAt
    ) {
        public static PersistedBusinessFact fromDomain(AdaptiveConversationIntelligence.VerifiedBusinessFact f) {
            if (f == null) {
                return null;
            }
            return new PersistedBusinessFact(
                    f.factId(),
                    f.category(),
                    f.factKey(),
                    f.factValue(),
                    f.authoritativeSource(),
                    f.verifiedAt() != null ? f.verifiedAt().toString() : Instant.now().toString()
            );
        }

        public AdaptiveConversationIntelligence.VerifiedBusinessFact toDomain() {
            return new AdaptiveConversationIntelligence.VerifiedBusinessFact(
                    factId,
                    category,
                    factKey,
                    factValue,
                    authoritativeSource,
                    parseInstantOrDefault(verifiedAt, Instant.now())
            );
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PersistedOperationalState(
            @JsonProperty("booking_status") String bookingStatus,
            @JsonProperty("handoff_status") String handoffStatus,
            @JsonProperty("opted_out") boolean optedOut,
            @JsonProperty("active_handoff_ticket_id") String activeHandoffTicketId,
            @JsonProperty("last_handoff_error") String lastHandoffError,
            @JsonProperty("communication_style_preference") String communicationStylePreference
    ) {
        public static PersistedOperationalState fromDomain(AdaptiveConversationIntelligence.OperationalStateSnapshot o) {
            if (o == null) {
                return null;
            }
            return new PersistedOperationalState(
                    o.bookingStatus(),
                    o.handoffStatus() != null ? o.handoffStatus().name() : AdaptiveConversationIntelligence.HandoffLifecycleStatus.NONE.name(),
                    o.optedOut(),
                    o.activeHandoffTicketId(),
                    o.lastHandoffError(),
                    o.communicationStylePreference()
            );
        }

        public AdaptiveConversationIntelligence.OperationalStateSnapshot toDomain() {
            AdaptiveConversationIntelligence.HandoffLifecycleStatus hs =
                    parseEnumOrDefault(AdaptiveConversationIntelligence.HandoffLifecycleStatus.class,
                            handoffStatus, AdaptiveConversationIntelligence.HandoffLifecycleStatus.NONE);
            return new AdaptiveConversationIntelligence.OperationalStateSnapshot(
                    nonBlankOrDefault(bookingStatus, "NONE"),
                    hs,
                    optedOut,
                    activeHandoffTicketId,
                    lastHandoffError,
                    nonBlankOrDefault(communicationStylePreference, "STANDARD")
            );
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PersistedPreviousStrategy(
            @JsonProperty("last_primary_priority") String lastPrimaryPriority,
            @JsonProperty("last_chosen_strategy") String lastChosenStrategy,
            @JsonProperty("strategy_outcome") String strategyOutcome,
            @JsonProperty("outcome_evidence") String outcomeEvidence,
            @JsonProperty("turn_id") String turnId,
            @JsonProperty("recorded_at") String recordedAt
    ) {
        public static PersistedPreviousStrategy fromDomain(AdaptiveConversationIntelligence.PreviousStrategyRecord p) {
            if (p == null) {
                return null;
            }
            return new PersistedPreviousStrategy(
                    p.lastPrimaryPriority(),
                    p.lastChosenStrategy(),
                    p.strategyOutcome() != null ? p.strategyOutcome().name() : AdaptiveConversationIntelligence.StrategyOutcome.NOT_APPLICABLE.name(),
                    p.outcomeEvidence(),
                    p.turnId(),
                    p.recordedAt() != null ? p.recordedAt().toString() : Instant.now().toString()
            );
        }

        public AdaptiveConversationIntelligence.PreviousStrategyRecord toDomain() {
            AdaptiveConversationIntelligence.StrategyOutcome out =
                    parseEnumOrDefault(AdaptiveConversationIntelligence.StrategyOutcome.class,
                            strategyOutcome, AdaptiveConversationIntelligence.StrategyOutcome.NOT_APPLICABLE);
            return new AdaptiveConversationIntelligence.PreviousStrategyRecord(
                    nonBlankOrDefault(lastPrimaryPriority, "NONE"),
                    nonBlankOrDefault(lastChosenStrategy, "NONE"),
                    out,
                    nonBlankOrDefault(outcomeEvidence, ""),
                    nonBlankOrDefault(turnId, "NONE"),
                    parseInstantOrDefault(recordedAt, Instant.now())
            );
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PersistedCommitment(
            @JsonProperty("commitment_id") String commitmentId,
            @JsonProperty("created_turn_id") String createdTurnId,
            @JsonProperty("updated_turn_id") String updatedTurnId,
            @JsonProperty("content") String content,
            @JsonProperty("owner") String owner,
            @JsonProperty("verified_timeframe") String verifiedTimeframe,
            @JsonProperty("commitment_status") String commitmentStatus,
            @JsonProperty("execution_status") String executionStatus,
            @JsonProperty("task_id") String taskId,
            @JsonProperty("verification_evidence") String verificationEvidence,
            @JsonProperty("updated_at") String updatedAt
    ) {
        public static PersistedCommitment fromDomain(AdaptiveConversationIntelligence.CommitmentRecord c) {
            if (c == null) {
                return null;
            }
            return new PersistedCommitment(
                    c.commitmentId(),
                    c.createdTurnId(),
                    c.updatedTurnId(),
                    c.content(),
                    c.owner() != null ? c.owner().name() : AdaptiveConversationIntelligence.CommitmentOwner.STAFF.name(),
                    c.verifiedTimeframe(),
                    c.commitmentStatus() != null ? c.commitmentStatus().name() : AdaptiveConversationIntelligence.CommitmentStatus.pending.name(),
                    c.executionStatus() != null ? c.executionStatus().name() : AdaptiveConversationIntelligence.TaskExecutionStatus.not_created.name(),
                    c.taskId(),
                    c.verificationEvidence(),
                    c.updatedAt() != null ? c.updatedAt().toString() : Instant.now().toString()
            );
        }

        public AdaptiveConversationIntelligence.CommitmentRecord toDomain() {
            AdaptiveConversationIntelligence.CommitmentOwner own =
                    parseEnumOrDefault(AdaptiveConversationIntelligence.CommitmentOwner.class,
                            owner, AdaptiveConversationIntelligence.CommitmentOwner.STAFF);
            AdaptiveConversationIntelligence.CommitmentStatus cs =
                    parseEnumOrDefault(AdaptiveConversationIntelligence.CommitmentStatus.class,
                            commitmentStatus, AdaptiveConversationIntelligence.CommitmentStatus.pending);
            AdaptiveConversationIntelligence.TaskExecutionStatus es =
                    parseEnumOrDefault(AdaptiveConversationIntelligence.TaskExecutionStatus.class,
                            executionStatus, AdaptiveConversationIntelligence.TaskExecutionStatus.not_created);
            return new AdaptiveConversationIntelligence.CommitmentRecord(
                    commitmentId,
                    createdTurnId,
                    updatedTurnId,
                    content,
                    own,
                    verifiedTimeframe,
                    cs,
                    es,
                    taskId,
                    verificationEvidence,
                    parseInstantOrDefault(updatedAt, Instant.now())
            );
        }
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isMeaningfullyPopulated() {
        return schemaVersion > 0 && (
                lastUpdatedTurnSeq > 0
                        || (confirmedFacts != null && !confirmedFacts.isEmpty())
                        || (selfReportedSymptoms != null && !selfReportedSymptoms.isEmpty())
                        || (unresolvedRequests != null && !unresolvedRequests.isEmpty())
                        || (customerStatements != null && !customerStatements.isEmpty())
                        || (verifiedFacts != null && !verifiedFacts.isEmpty())
                        || priceObjectionCount > 0
                        || (inferredCustomerPronoun != null && !inferredCustomerPronoun.isBlank())
                        || (lastBotAction != null && !lastBotAction.isBlank() && !"NONE".equalsIgnoreCase(lastBotAction))
                        || (structuredBookingState != null && structuredBookingState.status() != null
                            && !"NONE".equalsIgnoreCase(structuredBookingState.status()))
        );
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception ex) {
            return "{}";
        }
    }

    public static AciCustomerBrainMemorySnapshot fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        String trimmed = json.strip();
        if ("{}".equals(trimmed) || "null".equalsIgnoreCase(trimmed)) {
            return null;
        }
        try {
            AciCustomerBrainMemorySnapshot parsed = MAPPER.readValue(trimmed, AciCustomerBrainMemorySnapshot.class);
            return (parsed != null && parsed.isMeaningfullyPopulated()) ? parsed : null;
        } catch (Exception ex) {
            return null;
        }
    }

    public static Instant extractMemoryResetFloor(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        String trimmed = json.strip();
        if ("{}".equals(trimmed) || "null".equalsIgnoreCase(trimmed)) {
            return null;
        }
        try {
            AciCustomerBrainMemorySnapshot parsed = MAPPER.readValue(trimmed, AciCustomerBrainMemorySnapshot.class);
            return parsed != null ? parseInstantOrNull(parsed.memoryResetAt()) : null;
        } catch (Exception ex) {
            return null;
        }
    }

    // =========================================================================
    // EXPLICIT MONOTONIC MERGE FOR CustomerBrainMemory JSONB (Unit P2.4)
    // =========================================================================

    public static AciCustomerBrainMemorySnapshot monotonicMerge(
            AciCustomerBrainMemorySnapshot freshDb,
            AciCustomerBrainMemorySnapshot candidate
    ) {
        if (freshDb == null || !freshDb.isMeaningfullyPopulated()) {
            return candidate;
        }
        if (candidate == null || !candidate.isMeaningfullyPopulated()) {
            return freshDb;
        }

        boolean candidateIsNewerOrEqualTurn = compareSnapshotChronology(candidate, freshDb) >= 0;
        AciCustomerBrainMemorySnapshot primary = candidateIsNewerOrEqualTurn ? candidate : freshDb;
        AciCustomerBrainMemorySnapshot secondary = candidateIsNewerOrEqualTurn ? freshDb : candidate;

        // 1. Time-ordered collections merged by canonical key with strict NEGATED/SUPERSEDED non-regression:
        List<PersistedVersionedFact> mergedConfirmedFacts =
                mergeVersionedFactsByKeyMonotonic(freshDb.confirmedFacts(), candidate.confirmedFacts());
        List<PersistedVersionedFact> mergedSymptoms =
                mergeVersionedFactsByKeyMonotonic(freshDb.selfReportedSymptoms(), candidate.selfReportedSymptoms());
        List<PersistedUnresolvedRequest> mergedUnresolved =
                mergeUnresolvedRequestsByKeyMonotonic(freshDb.unresolvedRequests(), candidate.unresolvedRequests());

        // 2. Pronoun: keep higher confidence or newer turn evidence; never erase confirmed pronoun to null
        String mergedPronoun = primary.inferredCustomerPronoun();
        double mergedPronounConf = primary.inferredPronounConfidence();
        String mergedPronounTurn = primary.inferredPronounEvidenceTurnId();
        if ((mergedPronoun == null || mergedPronoun.isBlank())
                || secondary.inferredPronounConfidence() > mergedPronounConf) {
            if (secondary.inferredCustomerPronoun() != null && !secondary.inferredCustomerPronoun().isBlank()) {
                mergedPronoun = secondary.inferredCustomerPronoun();
                mergedPronounConf = secondary.inferredPronounConfidence();
                mergedPronounTurn = secondary.inferredPronounEvidenceTurnId();
            }
        }

        // 3. Monotonic booking state: never erase confirmed product/date/time back to null by an older turn
        boolean candidateExplicitlyCancelledBooking = candidateIsNewerOrEqualTurn
                && candidate.structuredBookingState() != null
                && "NONE".equalsIgnoreCase(candidate.structuredBookingState().status())
                && candidate.supersededOrRetractedFacts() != null
                && candidate.supersededOrRetractedFacts().contains("BOOKING_CANCELLED");
        boolean candidateInvalidatedUnavailableSlot = candidateIsNewerOrEqualTurn
                && candidate.structuredBookingState() != null
                && "AWAITING_DATE_OR_TIME".equalsIgnoreCase(candidate.structuredBookingState().status())
                && (candidate.structuredBookingState().bookingTime() == null
                    || candidate.structuredBookingState().bookingTime().isBlank())
                && candidate.supersededOrRetractedFacts() != null
                && candidate.supersededOrRetractedFacts().contains("BOOKING_SLOT_UNAVAILABLE");
        PersistedBookingState mergedBooking = mergeBookingStateMonotonic(
                freshDb.structuredBookingState(),
                candidate.structuredBookingState(),
                candidateIsNewerOrEqualTurn,
                candidateExplicitlyCancelledBooking,
                candidateInvalidatedUnavailableSlot
        );

        // 4. Operational state: optedOut monotonic OR (overridden by row-level V33/V35 opted_out timestamp truth)
        PersistedOperationalState mergedOpState = mergeOperationalStateMonotonic(
                freshDb.operationalState(),
                candidate.operationalState(),
                mergedBooking,
                candidateIsNewerOrEqualTurn
        );

        // 5. Commitments, statements, authoritative business facts, and auxiliary lists
        List<PersistedCommitment> mergedCommitments =
                mergeCommitmentsMonotonic(freshDb.sessionCommitments(), candidate.sessionCommitments());
        List<PersistedCustomerStatement> mergedStatements =
                mergeStatementsDedup(freshDb.customerStatements(), candidate.customerStatements());
        List<PersistedBusinessFact> mergedBusinessFacts =
                mergeBusinessFactsByKey(freshDb.authoritativeBusinessFacts(), candidate.authoritativeBusinessFacts());

        List<String> mergedSupersededLegacy = new ArrayList<>(unionStringsBounded(
                freshDb.supersededOrRetractedFacts(), candidate.supersededOrRetractedFacts(), 24));
        if (mergedBooking != null && !"NONE".equalsIgnoreCase(mergedBooking.status())) {
            mergedSupersededLegacy.removeIf("BOOKING_CANCELLED"::equalsIgnoreCase);
        }
        if (mergedBooking != null && mergedBooking.bookingTime() != null && !mergedBooking.bookingTime().isBlank()) {
            mergedSupersededLegacy.removeIf("BOOKING_SLOT_UNAVAILABLE"::equalsIgnoreCase);
        }
        mergedSupersededLegacy = List.copyOf(mergedSupersededLegacy);
        Set<String> retractedLower = new LinkedHashSet<>();
        for (String r : mergedSupersededLegacy) {
            retractedLower.add(r.toLowerCase(Locale.ROOT));
        }
        List<String> mergedVerifiedLegacy = new ArrayList<>();
        for (String v : unionStringsBounded(freshDb.verifiedFacts(), candidate.verifiedFacts(), 24)) {
            if (!retractedLower.contains(v.toLowerCase(Locale.ROOT))) {
                mergedVerifiedLegacy.add(v);
            }
        }

        int mergedTurnSeq = Math.max(freshDb.lastUpdatedTurnSeq(), candidate.lastUpdatedTurnSeq());
        String mergedTurnId = primary.lastUpdatedTurnId() != null && !primary.lastUpdatedTurnId().isBlank()
                ? primary.lastUpdatedTurnId()
                : secondary.lastUpdatedTurnId();
        Instant maxTs = maxInstant(
                parseInstantOrNull(freshDb.lastUpdatedAt()),
                parseInstantOrNull(candidate.lastUpdatedAt())
        );

        Instant maxResetTs = maxInstant(
                parseInstantOrNull(freshDb.memoryResetAt()),
                parseInstantOrNull(candidate.memoryResetAt())
        );

        return new AciCustomerBrainMemorySnapshot(
                CURRENT_SCHEMA_VERSION,
                mergedTurnId,
                mergedTurnSeq,
                (maxTs != null ? maxTs : Instant.now()).toString(),
                firstMeaningful(primary.currentTopic(), secondary.currentTopic(), "INITIAL"),
                unionStringsBounded(freshDb.topicHistory(), candidate.topicHistory(), 16),
                mergedUnresolved,
                unionStringsBounded(primary.pendingReferences(), secondary.pendingReferences(), 12),
                firstMeaningful(primary.lastBotAction(), secondary.lastBotAction(), "NONE"),
                nonBlankOrDefault(primary.lastTurnIntent(), secondary.lastTurnIntent()),
                firstMeaningful(primary.conversationGoal(), secondary.conversationGoal(), "EXPLORE_SERVICES"),
                mergedConfirmedFacts,
                mergedSymptoms,
                primary.barrierHypothesis() != null ? primary.barrierHypothesis() : secondary.barrierHypothesis(),
                primary.emotion() != null ? primary.emotion() : secondary.emotion(),
                firstMeaningful(primary.communicationStylePreference(), secondary.communicationStylePreference(), "STANDARD"),
                firstMeaningful(primary.effectiveTurnDepth(), secondary.effectiveTurnDepth(), "STANDARD"),
                firstMeaningful(primary.interactionPermission(), secondary.interactionPermission(), "OPEN"),
                nonBlankOrDefault(primary.interestedPackageId(), secondary.interestedPackageId()),
                primary.lastResponsePlan() != null ? primary.lastResponsePlan() : secondary.lastResponsePlan(),
                Math.max(freshDb.priceObjectionCount(), candidate.priceObjectionCount()),
                primary.inPriceObjectionContext(),
                freshDb.hasPresentedDownsell() || candidate.hasPresentedDownsell(),
                mergedBooking,
                mergedPronoun,
                mergedPronounConf,
                mergedPronounTurn,
                nonBlankOrDefault(primary.verifiedSymptomDuration(), secondary.verifiedSymptomDuration()),
                mergedStatements,
                primary.aciWorkingHypothesis() != null ? primary.aciWorkingHypothesis() : secondary.aciWorkingHypothesis(),
                mergedBusinessFacts,
                mergedOpState,
                primary.previousStrategyRecord() != null ? primary.previousStrategyRecord() : secondary.previousStrategyRecord(),
                mergedCommitments,
                mergedVerifiedLegacy,
                mergedSupersededLegacy,
                unionStringsBounded(freshDb.uncertainExplorations(), candidate.uncertainExplorations(), 16),
                unionStringsBounded(freshDb.careNotes(), candidate.careNotes(), 16),
                nonBlankOrDefault(primary.workingHypothesis(), secondary.workingHypothesis()),
                nonBlankOrDefault(primary.lastActualSentSpeechAct(), secondary.lastActualSentSpeechAct()),
                maxResetTs != null ? maxResetTs.toString() : null,
                primary.secondaryIntents() != null ? List.copyOf(primary.secondaryIntents())
                        : (secondary.secondaryIntents() != null ? List.copyOf(secondary.secondaryIntents()) : List.of()),
                nonBlankOrDefault(primary.conversationState(), secondary.conversationState())
        );
    }

    public AciCustomerBrainMemorySnapshot withAlignedRowOptOut(boolean rowOptedOut) {
        PersistedOperationalState base = this.operationalState != null
                ? this.operationalState
                : new PersistedOperationalState("NONE", "NONE", rowOptedOut, null, null,
                        nonBlankOrDefault(this.communicationStylePreference, "STANDARD"));
        if (base.optedOut() == rowOptedOut) {
            return this;
        }
        PersistedOperationalState updatedOp = new PersistedOperationalState(
                base.bookingStatus(),
                base.handoffStatus(),
                rowOptedOut,
                base.activeHandoffTicketId(),
                base.lastHandoffError(),
                base.communicationStylePreference()
        );
        return new AciCustomerBrainMemorySnapshot(
                schemaVersion, lastUpdatedTurnId, lastUpdatedTurnSeq, lastUpdatedAt,
                currentTopic, topicHistory, unresolvedRequests, pendingReferences, lastBotAction,
                lastTurnIntent, conversationGoal, confirmedFacts, selfReportedSymptoms,
                barrierHypothesis, emotion, communicationStylePreference, effectiveTurnDepth,
                interactionPermission, interestedPackageId, lastResponsePlan,
                priceObjectionCount, inPriceObjectionContext, hasPresentedDownsell,
                structuredBookingState, inferredCustomerPronoun, inferredPronounConfidence,
                inferredPronounEvidenceTurnId, verifiedSymptomDuration,
                customerStatements, aciWorkingHypothesis, authoritativeBusinessFacts,
                updatedOp, previousStrategyRecord, sessionCommitments,
                verifiedFacts, supersededOrRetractedFacts, uncertainExplorations, careNotes,
                workingHypothesis, lastActualSentSpeechAct, memoryResetAt,
                secondaryIntents, conversationState
        );
    }

    public AciCustomerBrainMemorySnapshot withMinMemoryResetAt(Instant floor) {
        if (floor == null) {
            return this;
        }
        Instant existing = parseInstantOrNull(this.memoryResetAt);
        Instant merged = maxInstant(existing, floor);
        if (existing != null && existing.equals(merged)) {
            return this;
        }
        return new AciCustomerBrainMemorySnapshot(
                schemaVersion, lastUpdatedTurnId, lastUpdatedTurnSeq, lastUpdatedAt,
                currentTopic, topicHistory, unresolvedRequests, pendingReferences, lastBotAction,
                lastTurnIntent, conversationGoal, confirmedFacts, selfReportedSymptoms,
                barrierHypothesis, emotion, communicationStylePreference, effectiveTurnDepth,
                interactionPermission, interestedPackageId, lastResponsePlan,
                priceObjectionCount, inPriceObjectionContext, hasPresentedDownsell,
                structuredBookingState, inferredCustomerPronoun, inferredPronounConfidence,
                inferredPronounEvidenceTurnId, verifiedSymptomDuration,
                customerStatements, aciWorkingHypothesis, authoritativeBusinessFacts,
                operationalState, previousStrategyRecord, sessionCommitments,
                verifiedFacts, supersededOrRetractedFacts, uncertainExplorations, careNotes,
                workingHypothesis, lastActualSentSpeechAct, merged.toString(),
                secondaryIntents, conversationState
        );
    }

    /**
     * Merges {@code confirmed_facts} / {@code self_reported_symptoms} by canonical {@code key}
     * using latest {@code updatedTurnId} / {@code sourceTurnId} / {@code observedAt}.
     *
     * <p>Invariant (Unit P2.4): A fact marked {@code NEGATED} or {@code SUPERSEDED} (or {@code RESOLVED})
     * in a newer turn is NEVER restored back to {@code CURRENT} by an older turn finishing late.
     */
    public static List<PersistedVersionedFact> mergeVersionedFactsByKeyMonotonic(
            List<PersistedVersionedFact> left,
            List<PersistedVersionedFact> right
    ) {
        Map<String, PersistedVersionedFact> merged = new LinkedHashMap<>();
        if (left != null) {
            for (PersistedVersionedFact f : left) {
                upsertVersionedFactMonotonic(merged, f);
            }
        }
        if (right != null) {
            for (PersistedVersionedFact f : right) {
                upsertVersionedFactMonotonic(merged, f);
            }
        }
        return List.copyOf(merged.values());
    }

    private static void upsertVersionedFactMonotonic(
            Map<String, PersistedVersionedFact> map,
            PersistedVersionedFact incoming
    ) {
        if (incoming == null) {
            return;
        }
        String canonicalKey = canonicalFactKey(incoming.key(), incoming.value());
        PersistedVersionedFact existing = map.get(canonicalKey);
        if (existing == null) {
            map.put(canonicalKey, incoming);
            return;
        }
        if (compareVersionedFactWinner(incoming, existing) > 0) {
            map.put(canonicalKey, incoming);
        }
    }

    /**
     * Returns {@code > 0} if {@code candidate} beats {@code incumbent}, {@code < 0} if {@code incumbent} wins.
     */
    public static int compareVersionedFactWinner(PersistedVersionedFact candidate, PersistedVersionedFact incumbent) {
        OptionalInt candTurn = extractBestTurnSeq(candidate.updatedTurnId(), candidate.sourceTurnId());
        OptionalInt incTurn = extractBestTurnSeq(incumbent.updatedTurnId(), incumbent.sourceTurnId());
        if (candTurn.isPresent() && incTurn.isPresent() && candTurn.getAsInt() != incTurn.getAsInt()) {
            return Integer.compare(candTurn.getAsInt(), incTurn.getAsInt());
        }

        Instant candTs = parseInstantOrNull(candidate.observedAt());
        Instant incTs = parseInstantOrNull(incumbent.observedAt());
        if (candTs != null && incTs != null && !candTs.equals(incTs)) {
            return candTs.compareTo(incTs);
        }

        if (candTurn.isPresent() != incTurn.isPresent()) {
            return candTurn.isPresent() ? 1 : -1;
        }

        // Tie-breaker on identical turn & timestamp: NEGATED / SUPERSEDED / RESOLVED strictly beat CURRENT
        int candRank = factValidityFinalityRank(candidate.validity(), candidate.status());
        int incRank = factValidityFinalityRank(incumbent.validity(), incumbent.status());
        return Integer.compare(candRank, incRank);
    }

    private static int factValidityFinalityRank(String validityRaw, String statusRaw) {
        AdaptiveConversationIntelligence.FactValidity v =
                AdaptiveConversationIntelligence.FactValidity.fromString(
                        nonBlankOrDefault(validityRaw, statusRaw));
        return switch (v) {
            case NEGATED, SUPERSEDED -> 4;
            case RESOLVED -> 3;
            case PAST -> 2;
            case CURRENT -> 1;
        };
    }

    /**
     * Merges {@code unresolved_requests} by canonical {@code item} key using latest
     * {@code updatedTurnId} / {@code sourceTurnId} / {@code observedAt}.
     * An item already {@code ANSWERED}, {@code CANCELLED}, or {@code SUPERSEDED} in a newer turn
     * never reverts to {@code OPEN} from a stale turn.
     */
    public static List<PersistedUnresolvedRequest> mergeUnresolvedRequestsByKeyMonotonic(
            List<PersistedUnresolvedRequest> left,
            List<PersistedUnresolvedRequest> right
    ) {
        Map<String, PersistedUnresolvedRequest> merged = new LinkedHashMap<>();
        if (left != null) {
            for (PersistedUnresolvedRequest r : left) {
                upsertUnresolvedRequestMonotonic(merged, r);
            }
        }
        if (right != null) {
            for (PersistedUnresolvedRequest r : right) {
                upsertUnresolvedRequestMonotonic(merged, r);
            }
        }
        return List.copyOf(merged.values());
    }

    private static void upsertUnresolvedRequestMonotonic(
            Map<String, PersistedUnresolvedRequest> map,
            PersistedUnresolvedRequest incoming
    ) {
        if (incoming == null || incoming.item() == null || incoming.item().isBlank()) {
            return;
        }
        String key = incoming.item().strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        PersistedUnresolvedRequest existing = map.get(key);
        if (existing == null) {
            map.put(key, incoming);
            return;
        }
        if (compareUnresolvedRequestWinner(incoming, existing) > 0) {
            map.put(key, incoming);
        }
    }

    public static int compareUnresolvedRequestWinner(
            PersistedUnresolvedRequest candidate,
            PersistedUnresolvedRequest incumbent
    ) {
        OptionalInt candTurn = extractBestTurnSeq(candidate.updatedTurnId(), candidate.sourceTurnId());
        OptionalInt incTurn = extractBestTurnSeq(incumbent.updatedTurnId(), incumbent.sourceTurnId());
        if (candTurn.isPresent() && incTurn.isPresent() && candTurn.getAsInt() != incTurn.getAsInt()) {
            return Integer.compare(candTurn.getAsInt(), incTurn.getAsInt());
        }
        Instant candTs = parseInstantOrNull(candidate.observedAt());
        Instant incTs = parseInstantOrNull(incumbent.observedAt());
        if (candTs != null && incTs != null && !candTs.equals(incTs)) {
            return candTs.compareTo(incTs);
        }
        if (candTurn.isPresent() != incTurn.isPresent()) {
            return candTurn.isPresent() ? 1 : -1;
        }
        return Integer.compare(unresolvedStatusRank(candidate.status()), unresolvedStatusRank(incumbent.status()));
    }

    private static int unresolvedStatusRank(String raw) {
        AdaptiveConversationIntelligence.UnresolvedRequestStatus st =
                AdaptiveConversationIntelligence.UnresolvedRequestStatus.fromString(raw);
        return switch (st) {
            case SUPERSEDED, CANCELLED -> 4;
            case ANSWERED -> 3;
            case OPEN -> 1;
        };
    }

    private static PersistedBookingState mergeBookingStateMonotonic(
            PersistedBookingState freshBooking,
            PersistedBookingState candBooking,
            boolean candidateIsNewerOrEqualTurn,
            boolean candidateExplicitlyCancelledBooking,
            boolean candidateInvalidatedUnavailableSlot
    ) {
        if (freshBooking == null) {
            return candBooking;
        }
        if (candBooking == null) {
            return freshBooking;
        }
        if (candidateExplicitlyCancelledBooking || candidateInvalidatedUnavailableSlot) {
            return candBooking;
        }
        PersistedBookingState newer = candidateIsNewerOrEqualTurn ? candBooking : freshBooking;
        PersistedBookingState older = candidateIsNewerOrEqualTurn ? freshBooking : candBooking;

        boolean newerMutatedActiveSlot = candidateIsNewerOrEqualTurn && (
                (newer.selectedProductId() != null && !newer.selectedProductId().isBlank()
                        && older.selectedProductId() != null && !older.selectedProductId().isBlank()
                        && !newer.selectedProductId().equals(older.selectedProductId()))
                || (newer.bookingDate() != null && !newer.bookingDate().isBlank()
                        && older.bookingDate() != null && !older.bookingDate().isBlank()
                        && !newer.bookingDate().equals(older.bookingDate()))
                || (newer.bookingTime() != null && !newer.bookingTime().isBlank()
                        && older.bookingTime() != null && !older.bookingTime().isBlank()
                        && !newer.bookingTime().equals(older.bookingTime()))
        );

        int newerRank = bookingStatusRank(newer.status());
        int olderRank = bookingStatusRank(older.status());
        String winningStatus = (newerMutatedActiveSlot || newerRank >= olderRank) ? newer.status() : older.status();

        return new PersistedBookingState(
                nonBlankOrDefault(newer.selectedProductId(), older.selectedProductId()),
                nonBlankOrDefault(newer.selectedProductLabel(), older.selectedProductLabel()),
                nonBlankOrDefault(newer.bookingDate(), older.bookingDate()),
                nonBlankOrDefault(newer.resolvedIsoDate(), older.resolvedIsoDate()),
                nonBlankOrDefault(newer.bookingTime(), older.bookingTime()),
                firstMeaningful(newer.timezoneId(), older.timezoneId(), "Asia/Ho_Chi_Minh"),
                nonBlankOrDefault(winningStatus, "NONE"),
                unionStringsBounded(older.candidateProductIds(), newer.candidateProductIds(), 12)
        );
    }

    private static int bookingStatusRank(String rawStatus) {
        ConversationState.BookingStatus st = parseEnumOrDefault(
                ConversationState.BookingStatus.class, rawStatus, ConversationState.BookingStatus.NONE);
        return switch (st) {
            case CONFIRMED -> 6;
            case AWAITING_CUSTOMER_CONFIRMATION -> 5;
            case AWAITING_AVAILABILITY_CHECK -> 4;
            case AWAITING_DATE_OR_TIME -> 3;
            case AWAITING_PRODUCT_SELECTION -> 2;
            case NONE -> 1;
        };
    }

    private static PersistedOperationalState mergeOperationalStateMonotonic(
            PersistedOperationalState freshOp,
            PersistedOperationalState candOp,
            PersistedBookingState mergedBooking,
            boolean candidateIsNewerOrEqualTurn
    ) {
        if (freshOp == null && candOp == null) {
            return null;
        }
        if (freshOp == null) {
            return candOp;
        }
        if (candOp == null) {
            return freshOp;
        }
        PersistedOperationalState newer = candidateIsNewerOrEqualTurn ? candOp : freshOp;
        PersistedOperationalState older = candidateIsNewerOrEqualTurn ? freshOp : candOp;
        String handoff = handoffStatusRank(newer.handoffStatus()) >= handoffStatusRank(older.handoffStatus())
                ? newer.handoffStatus()
                : older.handoffStatus();
        String booking = mergedBooking != null && mergedBooking.status() != null
                ? mergedBooking.status()
                : nonBlankOrDefault(newer.bookingStatus(), older.bookingStatus());
        return new PersistedOperationalState(
                nonBlankOrDefault(booking, "NONE"),
                nonBlankOrDefault(handoff, "NONE"),
                freshOp.optedOut() || candOp.optedOut(),
                nonBlankOrDefault(newer.activeHandoffTicketId(), older.activeHandoffTicketId()),
                nonBlankOrDefault(newer.lastHandoffError(), older.lastHandoffError()),
                firstMeaningful(newer.communicationStylePreference(), older.communicationStylePreference(), "STANDARD")
        );
    }

    private static int handoffStatusRank(String raw) {
        AdaptiveConversationIntelligence.HandoffLifecycleStatus st =
                parseEnumOrDefault(AdaptiveConversationIntelligence.HandoffLifecycleStatus.class,
                        raw, AdaptiveConversationIntelligence.HandoffLifecycleStatus.NONE);
        return switch (st) {
            case HANDOFF_RESOLVED -> 6;
            case HANDOFF_ACKNOWLEDGED -> 5;
            case HANDOFF_ASSIGNED -> 4;
            case HANDOFF_CREATED -> 3;
            case HANDOFF_REQUESTED, HANDOFF_FAILED -> 2;
            case NONE -> 1;
        };
    }

    private static List<PersistedCommitment> mergeCommitmentsMonotonic(
            List<PersistedCommitment> left,
            List<PersistedCommitment> right
    ) {
        Map<String, PersistedCommitment> map = new LinkedHashMap<>();
        if (left != null) {
            for (PersistedCommitment c : left) {
                upsertCommitment(map, c);
            }
        }
        if (right != null) {
            for (PersistedCommitment c : right) {
                upsertCommitment(map, c);
            }
        }
        return List.copyOf(map.values());
    }

    private static void upsertCommitment(Map<String, PersistedCommitment> map, PersistedCommitment incoming) {
        if (incoming == null) {
            return;
        }
        String key = nonBlankOrDefault(incoming.commitmentId(), incoming.content()).toLowerCase(Locale.ROOT);
        PersistedCommitment existing = map.get(key);
        if (existing == null) {
            map.put(key, incoming);
            return;
        }
        boolean incomingCompleted = "completed".equalsIgnoreCase(incoming.commitmentStatus());
        boolean existingCompleted = "completed".equalsIgnoreCase(existing.commitmentStatus());
        if (existingCompleted && !incomingCompleted) {
            return;
        }
        if (incomingCompleted && !existingCompleted) {
            map.put(key, incoming);
            return;
        }
        OptionalInt inTurn = extractBestTurnSeq(incoming.updatedTurnId(), incoming.createdTurnId());
        OptionalInt exTurn = extractBestTurnSeq(existing.updatedTurnId(), existing.createdTurnId());
        if (inTurn.isPresent() && exTurn.isPresent() && inTurn.getAsInt() != exTurn.getAsInt()) {
            if (inTurn.getAsInt() > exTurn.getAsInt()) {
                map.put(key, incoming);
            }
            return;
        }
        Instant inTs = parseInstantOrNull(incoming.updatedAt());
        Instant exTs = parseInstantOrNull(existing.updatedAt());
        if (inTs != null && (exTs == null || inTs.isAfter(exTs))) {
            map.put(key, incoming);
        }
    }

    private static List<PersistedCustomerStatement> mergeStatementsDedup(
            List<PersistedCustomerStatement> left,
            List<PersistedCustomerStatement> right
    ) {
        Map<String, PersistedCustomerStatement> dedup = new LinkedHashMap<>();
        if (left != null) {
            for (PersistedCustomerStatement s : left) {
                if (s != null && s.content() != null && !s.content().isBlank()) {
                    dedup.putIfAbsent(s.content().strip().toLowerCase(Locale.ROOT), s);
                }
            }
        }
        if (right != null) {
            for (PersistedCustomerStatement s : right) {
                if (s != null && s.content() != null && !s.content().isBlank()) {
                    dedup.putIfAbsent(s.content().strip().toLowerCase(Locale.ROOT), s);
                }
            }
        }
        List<PersistedCustomerStatement> all = new ArrayList<>(dedup.values());
        if (all.size() > 16) {
            return List.copyOf(all.subList(all.size() - 16, all.size()));
        }
        return List.copyOf(all);
    }

    private static List<PersistedBusinessFact> mergeBusinessFactsByKey(
            List<PersistedBusinessFact> left,
            List<PersistedBusinessFact> right
    ) {
        Map<String, PersistedBusinessFact> map = new LinkedHashMap<>();
        if (left != null) {
            for (PersistedBusinessFact f : left) {
                if (f != null && f.factKey() != null && !f.factKey().isBlank()) {
                    map.put(f.factKey().strip().toLowerCase(Locale.ROOT), f);
                }
            }
        }
        if (right != null) {
            for (PersistedBusinessFact f : right) {
                if (f != null && f.factKey() != null && !f.factKey().isBlank()) {
                    String k = f.factKey().strip().toLowerCase(Locale.ROOT);
                    PersistedBusinessFact ex = map.get(k);
                    Instant inTs = parseInstantOrNull(f.verifiedAt());
                    Instant exTs = ex != null ? parseInstantOrNull(ex.verifiedAt()) : null;
                    if (ex == null || (inTs != null && (exTs == null || !inTs.isBefore(exTs)))) {
                        map.put(k, f);
                    }
                }
            }
        }
        return List.copyOf(map.values());
    }

    private static int compareSnapshotChronology(
            AciCustomerBrainMemorySnapshot a,
            AciCustomerBrainMemorySnapshot b
    ) {
        if (a.lastUpdatedTurnSeq() != b.lastUpdatedTurnSeq()) {
            return Integer.compare(a.lastUpdatedTurnSeq(), b.lastUpdatedTurnSeq());
        }
        OptionalInt turnA = extractTurnOrder(a.lastUpdatedTurnId());
        OptionalInt turnB = extractTurnOrder(b.lastUpdatedTurnId());
        if (turnA.isPresent() && turnB.isPresent() && turnA.getAsInt() != turnB.getAsInt()) {
            return Integer.compare(turnA.getAsInt(), turnB.getAsInt());
        }
        Instant tsA = parseInstantOrNull(a.lastUpdatedAt());
        Instant tsB = parseInstantOrNull(b.lastUpdatedAt());
        if (tsA != null && tsB != null) {
            return tsA.compareTo(tsB);
        }
        return 0;
    }

    public static OptionalInt extractBestTurnSeq(String primaryTurnId, String fallbackTurnId) {
        OptionalInt p = extractTurnOrder(primaryTurnId);
        return p.isPresent() ? p : extractTurnOrder(fallbackTurnId);
    }

    public static OptionalInt extractTurnOrder(String rawTurnId) {
        if (rawTurnId == null || rawTurnId.isBlank()) {
            return OptionalInt.empty();
        }
        Matcher m = TURN_SEQ_PATTERN.matcher(rawTurnId.strip());
        if (m.find()) {
            try {
                return OptionalInt.of(Integer.parseInt(m.group(1)));
            } catch (NumberFormatException ignored) {
            }
        }
        return OptionalInt.empty();
    }

    private static String canonicalFactKey(String key, String value) {
        String norm = AdaptiveConversationIntelligence.normalizeFactCategoryKey(key, value);
        String baseKey = norm != null ? norm.strip().toLowerCase(Locale.ROOT) : "";
        if ("self_reported_symptom".equals(baseKey)) {
            String symCanon = AdaptiveConversationIntelligence.canonicalSymptomKey(value);
            return symCanon.isBlank() ? baseKey : (baseKey + "::" + symCanon);
        }
        if ("constraint".equals(baseKey) && value != null && !value.isBlank()) {
            String valCanon = java.text.Normalizer.normalize(value.strip(), java.text.Normalizer.Form.NFC)
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("\\s+", " ");
            return baseKey + "::" + valCanon;
        }
        return baseKey;
    }

    private static List<String> unionStringsBounded(List<String> first, List<String> second, int maxItems) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (first != null) {
            for (String s : first) {
                if (s != null && !s.isBlank()) {
                    set.add(s.strip());
                }
            }
        }
        if (second != null) {
            for (String s : second) {
                if (s != null && !s.isBlank()) {
                    set.add(s.strip());
                }
            }
        }
        List<String> list = new ArrayList<>(set);
        if (list.size() > maxItems) {
            return List.copyOf(list.subList(list.size() - maxItems, list.size()));
        }
        return List.copyOf(list);
    }

    private static String firstMeaningful(String preferred, String fallback, String defaultSentinel) {
        if (preferred != null && !preferred.isBlank() && !defaultSentinel.equalsIgnoreCase(preferred.strip())) {
            return preferred.strip();
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback.strip();
        }
        return defaultSentinel;
    }

    private static String nonBlankOrDefault(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.strip();
        }
        return fallback;
    }

    private static Instant parseInstantOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw.strip());
        } catch (Exception ex) {
            return null;
        }
    }

    private static Instant parseInstantOrDefault(String raw, Instant fallback) {
        Instant parsed = parseInstantOrNull(raw);
        return parsed != null ? parsed : fallback;
    }

    private static Instant maxInstant(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }

    private static <E extends Enum<E>> E parseEnumOrDefault(Class<E> enumType, String raw, E fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String trimmed = raw.strip();
        for (E constant : enumType.getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(trimmed)) {
                return constant;
            }
        }
        return fallback;
    }
}
