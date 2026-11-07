package com.example.docmind.service.salescore;

import java.util.*;

/** Reviewed working context, separate from customer facts and transaction/delivery state. */
public final class SalesJourney {
    private SalesJourney() {}
    public enum Engagement { ACTIVE, PAUSED, STOPPED }
    public enum Confidence { LOW, MEDIUM }
    public record Claim(String value, String customerQuote, long sourceTurn) {}
    public record OpenLoop(String id, Claim request) {}
    public record Hypothesis(String interpretation, String customerQuote, long sourceTurn, Confidence confidence) {}
    public record State(Claim primaryGoal, List<OpenLoop> openLoops, Claim currentConcern,
                        List<Hypothesis> hypotheses, Engagement engagement, Claim engagementSignal,
                        String previousApproach, Claim observedOutcome, String nextUsefulAction) {
        public State {
            openLoops = openLoops == null ? List.of() : List.copyOf(openLoops);
            hypotheses = hypotheses == null ? List.of() : List.copyOf(hypotheses);
            engagement = engagement == null ? Engagement.ACTIVE : engagement;
        }
        public static State empty() { return new State(null, List.of(), null, List.of(), Engagement.ACTIVE, null, "NONE", null, ""); }
    }

    /** Quote identity is checked here; meaning/goal closure/reactivation must also pass semantic review. */
    public static State validate(State proposed, SalesMemory.View memory) {
        if (proposed == null) return memory.journey();
        if (proposed.openLoops().size() > 8 || proposed.hypotheses().size() > 3
                || tooLong(proposed.previousApproach(), 600) || tooLong(proposed.nextUsefulAction(), 600))
            throw new IllegalArgumentException("Journey capacity exceeded");
        claim(proposed.primaryGoal(), memory);
        claim(proposed.currentConcern(), memory);
        claim(proposed.observedOutcome(), memory);
        claim(proposed.engagementSignal(), memory);
        Set<String> ids = new HashSet<>();
        for (var loop : proposed.openLoops()) {
            if (loop == null || loop.id() == null || !loop.id().matches("[a-zA-Z0-9_-]{1,64}")
                    || !ids.add(loop.id()) || loop.request() == null) throw new IllegalArgumentException("Invalid open loop");
            claim(loop.request(), memory);
        }
        for (var hypothesis : proposed.hypotheses()) {
            if (hypothesis == null || hypothesis.confidence() == null)
                throw new IllegalArgumentException("Hypothesis needs uncertainty");
            claim(new Claim(hypothesis.interpretation(), hypothesis.customerQuote(), hypothesis.sourceTurn()), memory);
        }
        if (proposed.engagement() != memory.journey().engagement() || proposed.engagement() != Engagement.ACTIVE) {
            if (proposed.engagementSignal() == null) throw new IllegalArgumentException("Engagement requires evidence");
            if (proposed.engagement() != memory.journey().engagement()
                    && proposed.engagementSignal().sourceTurn() != memory.revision())
                throw new IllegalArgumentException("Engagement transition requires current customer input");
        }
        return proposed;
    }
    private static void claim(Claim c, SalesMemory.View memory) {
        if (c == null) return;
        if (c.value() == null || c.value().isBlank() || tooLong(c.value(), 600)
                || c.customerQuote() == null || c.customerQuote().isBlank() || tooLong(c.customerQuote(), 800))
            throw new IllegalArgumentException("Invalid journey claim");
        boolean raw = memory.customerSources().stream().anyMatch(s -> s.turn() == c.sourceTurn()
                && normalize(s.text()).contains(normalize(c.customerQuote())));
        boolean fact = memory.facts().stream().anyMatch(f -> f.sourceTurn() == c.sourceTurn()
                && normalize(f.customerQuote()).contains(normalize(c.customerQuote())));
        // Carry a previously reviewed goal beyond the raw-source window without inventing a new provenance.
        boolean retained = priorClaims(memory.journey()).stream().anyMatch(p -> p.equals(c));
        if (!raw && !fact && !retained) throw new IllegalArgumentException("Unsupported journey claim");
    }
    private static List<Claim> priorClaims(State state) {
        var claims = new ArrayList<Claim>();
        Collections.addAll(claims, state.primaryGoal(), state.currentConcern(), state.engagementSignal(), state.observedOutcome());
        state.openLoops().forEach(l -> claims.add(l.request()));
        state.hypotheses().forEach(h -> claims.add(new Claim(h.interpretation(), h.customerQuote(), h.sourceTurn())));
        return claims.stream().filter(Objects::nonNull).toList();
    }
    private static boolean tooLong(String s, int max) { return s != null && s.length() > max; }
    private static String normalize(String s) { return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC).replaceAll("\\s+", " ").strip(); }
}
