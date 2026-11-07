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
    private static String normalize(String s) { return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC).toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\s]+", " ").strip(); }
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
        boolean recommendation = decision.strategy().action() == Action.RECOMMEND || decision.strategy().action() == Action.COMPARE;
        // Stage never forbids grounded answers. Personal recommendations still require customer context.
        if (recommendation && p.stage() != ConsultationStage.LOCATION && !missingContext(input).isEmpty())
            errors.add("OFFER_BEFORE_REQUIRED_CUSTOMER_CONTEXT");
        if (p.stage() == ConsultationStage.COMMITMENT && !customerSupports(input, p.readinessEvidence()))
            errors.add("COMMITMENT_WITHOUT_CUSTOMER_SIGNAL");
        if (p.stage() == ConsultationStage.CLOSE && (decision.strategy().action() != Action.PAUSE
                || decision.followUp() != null || decision.bubbles().stream().anyMatch(b -> b.text().contains("?") || !b.evidence().isEmpty())))
            errors.add("CLOSE_MUST_RESPECT_CUSTOMER_STOP");
        return errors;
    }
}
