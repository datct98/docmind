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
                            boolean contextTruncated) {
        public TurnInput {
            history = history == null ? List.of() : List.copyOf(history);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            businessPolicies = businessPolicies == null ? List.of() : List.copyOf(businessPolicies);
        }
    }
    public record IndustryProfile(String code, List<String> decisionCriteria, List<String> discoveryHints,
                                  List<String> boundaries) {}
    public enum Action { ANSWER, CLARIFY, RECOMMEND, COMPARE, RESOLVE_CONCERN, INVITE_NEXT_STEP, HANDOFF, PAUSE }
    public record CustomerFact(String value, String customerQuote) {}
    public record Understanding(String customerGoal, List<CustomerFact> knownFacts, List<String> uncertainties,
                                String obstacleHypothesis) {}
    public record Strategy(Action action, String reason, String advance) {}
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
                         String profileCode, int repairCount) {}
}
