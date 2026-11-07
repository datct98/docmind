package com.example.docmind.service.salescore;

import java.util.List;

/** Domain-neutral contracts. History is authoritative; an inferred insight is not a customer fact. */
public final class SalesContracts {
    private SalesContracts() {}
    public record Message(String role, String content) {}
    public record Evidence(String id, String title, String text) {}
    public record TurnInput(String pageId, String businessName, String industryCode, String selfPronoun,
                            String customerPronoun, String voice, List<Message> history, String message,
                            List<Evidence> evidence, List<String> businessPolicies, String quotedMessage,
                            boolean contextTruncated, SalesMemory.View memory, InteractionPlan interactionPlan) {
        public TurnInput {
            history = history == null ? List.of() : List.copyOf(history);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            businessPolicies = businessPolicies == null ? List.of() : List.copyOf(businessPolicies);
            memory = memory == null ? SalesMemory.View.empty() : memory;
        }
        public TurnInput(String pageId, String businessName, String industryCode, String selfPronoun,
                         String customerPronoun, String voice, List<Message> history, String message,
                         List<Evidence> evidence, List<String> businessPolicies, String quotedMessage, boolean contextTruncated) {
            this(pageId, businessName, industryCode, selfPronoun, customerPronoun, voice, history, message, evidence,
                    businessPolicies, quotedMessage, contextTruncated, SalesMemory.View.empty(), null);
        }
        public TurnInput(String pageId, String businessName, String industryCode, String selfPronoun,
                         String customerPronoun, String voice, List<Message> history, String message,
                         List<Evidence> evidence, List<String> businessPolicies, String quotedMessage, boolean contextTruncated, SalesMemory.View memory) {
            this(pageId, businessName, industryCode, selfPronoun, customerPronoun, voice, history, message, evidence,
                    businessPolicies, quotedMessage, contextTruncated, memory, null);
        }
        public TurnInput withMemory(SalesMemory.View value) {
            return new TurnInput(pageId, businessName, industryCode, selfPronoun, customerPronoun, voice, history, message,
                    evidence, businessPolicies, quotedMessage, contextTruncated, value, interactionPlan);
        }
        public TurnInput withInteractionPlan(InteractionPlan value) {
            return new TurnInput(pageId, businessName, industryCode, selfPronoun, customerPronoun, voice, history, message,
                    evidence, businessPolicies, quotedMessage, contextTruncated, memory, value);
        }
    }
    /** A provisional decision, not a customer fact. Carried unchanged across writing, review and post-quote continuation. */
    public record InteractionPlan(String nextDecision, String customerBenefit, String questionPurpose,
                                  String readinessEvidence, String unresolvedConcern, ConsultationStage stage,
                                  java.util.Map<String,String> contextEvidence, List<ContextRequirement> requiredContext,
                                  HandoffReason handoffReason, String handoffQuote, ReplyMode replyMode) {
        public InteractionPlan {
            contextEvidence = contextEvidence == null ? java.util.Map.of() : java.util.Map.copyOf(contextEvidence);
            requiredContext = requiredContext == null ? List.of() : List.copyOf(requiredContext);
            handoffReason = handoffReason == null ? HandoffReason.NONE : handoffReason;
            replyMode = replyMode == null ? ReplyMode.DIRECT : replyMode;
        }
        public InteractionPlan(String next, String benefit, String question, String readiness, String concern,
                               ConsultationStage stage, java.util.Map<String,String> context, List<ContextRequirement> required,
                               HandoffReason handoff, String quote) {
            this(next, benefit, question, readiness, concern, stage, context, required, handoff, quote, ReplyMode.DIRECT);
        }
        public InteractionPlan(String next, String benefit, String question, String readiness, String concern) {
            this(next, benefit, question, readiness, concern, null, java.util.Map.of(), List.of(), HandoffReason.NONE, "");
        }
        public boolean allowsFollowUp() {
            return replyMode == ReplyMode.PRICE_MOMENT && stage == ConsultationStage.COMMITMENT
                    && meaningful(questionPurpose) && meaningful(readinessEvidence);
        }
        private static boolean meaningful(String value) { return value != null && !value.isBlank()
                && !"NONE".equalsIgnoreCase(value) && !"UNKNOWN".equalsIgnoreCase(value); }
    }
    public enum ConsultationStage { DISCOVERY, LOCATION, CONSULTATION, TRUST_REPAIR, COMMITMENT, ESCALATION, CLOSE }
    public enum ReplyMode { DIRECT, PRICE_MOMENT }
    public enum HandoffReason { NONE, EXPLICIT_HUMAN_REQUEST, DELIVERED_SERVICE_COMPLAINT, REFUND_OR_RETURN, TRANSACTION_DISPUTE }
    public record ContextRequirement(String key, String description) {}
    public record IndustryProfile(String code, List<String> decisionCriteria, List<String> discoveryHints,
                                  List<String> boundaries, Integer schemaVersion, List<String> customerFields,
                                  List<String> actionRequirements, List<String> evaluationScenarios, List<ContextRequirement> recommendationRequirements) {
        public IndustryProfile {
            schemaVersion = schemaVersion == null ? 1 : schemaVersion;
            customerFields = customerFields == null ? List.of() : List.copyOf(customerFields);
            actionRequirements = actionRequirements == null ? List.of() : List.copyOf(actionRequirements);
            evaluationScenarios = evaluationScenarios == null ? List.of() : List.copyOf(evaluationScenarios);
            recommendationRequirements = recommendationRequirements == null ? List.of() : List.copyOf(recommendationRequirements);
        }
        public IndustryProfile(String code, List<String> criteria, List<String> hints, List<String> boundaries) {
            this(code, criteria, hints, boundaries, 1, List.of(), List.of(), List.of(), List.of());
        }
        public java.util.Map<String,Object> runtimeView() {
            return java.util.Map.of("code", code, "schemaVersion", schemaVersion, "decisionCriteria", decisionCriteria,
                "discoveryHints", discoveryHints, "boundaries", boundaries, "customerFields", customerFields,
                "actionRequirements", actionRequirements, "recommendationRequirements", recommendationRequirements);
        }
    }
    public enum Action { ANSWER, CLARIFY, RECOMMEND, COMPARE, RESOLVE_CONCERN, INVITE_NEXT_STEP, HANDOFF, PAUSE }
    public record CustomerFact(String value, String customerQuote) {}
    public record Understanding(String customerGoal, List<CustomerFact> knownFacts, List<String> uncertainties,
                                String obstacleHypothesis) {}
    public record Strategy(Action action, String reason, String advance, List<SalesStrategyRegistry.Application> techniques) {
        public Strategy { techniques = techniques == null ? List.of() : List.copyOf(techniques); }
        public Strategy(Action action, String reason, String advance) { this(action, reason, advance, List.of()); }
    }
    public record Citation(String sourceId, String quote) {}
    public record Bubble(String text, List<Citation> evidence) {
        public Bubble { evidence = evidence == null ? List.of() : List.copyOf(evidence); }
    }
    public record Operand(java.math.BigDecimal value, String sourceId, String quote) {}
    public record Calculation(String operation, List<Operand> operands, java.math.BigDecimal result) {}
    /** A purpose for a future model call, never pre-written customer-facing copy. */
    public record FollowUp(String aim) {}
    public record TurnDecision(Understanding understanding, Strategy strategy, List<Bubble> bubbles,
                               String handoffSummary, List<Calculation> calculations, FollowUp followUp) {
        public TurnDecision { calculations = calculations == null ? List.of() : List.copyOf(calculations); }
        public TurnDecision(Understanding understanding, Strategy strategy, List<Bubble> bubbles,
                            String handoffSummary, List<Calculation> calculations) {
            this(understanding, strategy, bubbles, handoffSummary, calculations, null);
        }
    }
    public record Result(TurnDecision decision, List<String> texts, boolean handoff, boolean fallback,
                         int attempts, List<String> violations, long elapsedMs, int promptChars,
                         String profileCode, int repairCount, SalesMemory.View reviewedMemory, InteractionPlan interactionPlan) {
        public Result(TurnDecision decision, List<String> texts, boolean handoff, boolean fallback, int attempts,
                      List<String> violations, long elapsedMs, int promptChars, String profileCode, int repairCount) {
            this(decision, texts, handoff, fallback, attempts, violations, elapsedMs, promptChars, profileCode, repairCount, null, null);
        }
    }
}
