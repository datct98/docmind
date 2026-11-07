package com.example.docmind.service.salescore;

import java.util.*;
import static com.example.docmind.service.salescore.SalesContracts.*;

/** Generic permission boundary. Context requirements belong to industry data, never utterance keyword routes. */
final class SalesConsultationPolicy {
    private SalesConsultationPolicy() {}
    static boolean customerSupports(TurnInput input, String quote) {
        if (quote == null || quote.isBlank() || Set.of("NONE", "UNKNOWN").contains(quote.toUpperCase(Locale.ROOT))) return false;
        var sources = new ArrayList<String>();
        sources.add(input.message());
        input.history().stream().filter(m -> "user".equalsIgnoreCase(m.role()) || "customer".equalsIgnoreCase(m.role()))
                .map(Message::content).forEach(sources::add);
        input.memory().customerSources().stream().map(SalesMemory.Source::text).forEach(sources::add);
        input.memory().facts().stream().map(SalesMemory.Fact::customerQuote).forEach(sources::add);
        return sources.stream().filter(Objects::nonNull).anyMatch(s -> normalize(s).contains(normalize(quote)));
    }
    private static String normalize(String s) { return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC).replaceAll("\\s+", " ").strip(); }
    static List<String> missingContext(TurnInput input) {
        var p = input.interactionPlan();
        if (p == null) return List.of("VALID_PLAN");
        return p.requiredContext().stream().map(ContextRequirement::key)
                .filter(key -> !customerSupports(input, p.contextEvidence().get(key))).toList();
    }
    static boolean conversationOnly(TurnInput input) {
        var p = input.interactionPlan();
        return p != null && (p.stage() == ConsultationStage.DISCOVERY || p.stage() == ConsultationStage.TRUST_REPAIR || p.stage() == ConsultationStage.CLOSE);
    }
    static List<String> validate(TurnDecision decision, TurnInput input) {
        var errors = new ArrayList<String>();
        var p = input.interactionPlan();
        if (decision.strategy().action() == Action.HANDOFF && (p == null || p.stage() != ConsultationStage.ESCALATION
                || p.handoffReason() == HandoffReason.NONE || !customerSupports(input, p.handoffQuote())))
            errors.add("HANDOFF_REQUIRES_CUSTOMER_SERVICE_REASON");
        if (p == null || p.stage() == null) return errors; // Legacy callers still get source/authority checks; follow-up remains fail-closed.
        boolean quote = decision.bubbles().stream().anyMatch(b -> b != null && b.text() != null && !SalesDecisionValidator.monetaryNumbers(b.text()).isEmpty());
        boolean recommendation = decision.strategy().action() == Action.RECOMMEND || decision.strategy().action() == Action.COMPARE;
        // LOCATION may recommend a branch. Its factual scope is reviewed semantically; a generic
        // RECOMMEND action is not proof of a product pitch. Money still cannot enter this stage.
        if (quote && p.stage() == ConsultationStage.LOCATION)
            errors.add("LOCATION_TURN_ANSWER_BRANCH_ONLY_NO_PRODUCT_PRICE");
        if ((quote || recommendation) && conversationOnly(input)
                || recommendation && p.stage() != ConsultationStage.LOCATION && !missingContext(input).isEmpty())
            errors.add("OFFER_BEFORE_REQUIRED_CUSTOMER_CONTEXT");
        if (p.stage() == ConsultationStage.COMMITMENT && !customerSupports(input, p.readinessEvidence()))
            errors.add("COMMITMENT_WITHOUT_CUSTOMER_SIGNAL");
        if (conversationOnly(input) && (decision.bubbles().stream().anyMatch(b -> !b.evidence().isEmpty()) || !decision.calculations().isEmpty()))
            errors.add("BUSINESS_CLAIMS_IN_CONVERSATION_ONLY_TURN");
        return errors;
    }
}
