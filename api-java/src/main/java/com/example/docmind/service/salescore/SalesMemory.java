package com.example.docmind.service.salescore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Customer statements only. A generated reply, emotion guess or business promise is never durable fact. */
public final class SalesMemory {
    private SalesMemory() {}
    public record Source(long turn, String text) {}
    public record Fact(String key, String value, String customerQuote, long sourceTurn) {}
    public record Change(String key, String previousValue, String newValue, long sourceTurn) {}
    public record Update(String key, String value, String customerQuote, long sourceTurn, Boolean remove) {
        public Update { remove = Boolean.TRUE.equals(remove); }
    }
    public record View(long revision, List<Fact> facts, List<Change> changes, List<Source> customerSources, boolean truncated, SalesJourney.State journey) {
        public View {
            facts = facts == null ? List.of() : List.copyOf(facts);
            changes = changes == null ? List.of() : List.copyOf(changes);
            customerSources = customerSources == null ? List.of() : List.copyOf(customerSources);
            journey = journey == null ? SalesJourney.State.empty() : journey;
        }
        public View(long revision, List<Fact> facts, List<Change> changes, List<Source> sources, boolean truncated) {
            this(revision, facts, changes, sources, truncated, SalesJourney.State.empty());
        }
        public View withJourney(SalesJourney.State value) { return new View(revision, facts, changes, customerSources, truncated, value); }
        public static View empty() { return new View(0, List.of(), List.of(), List.of(), false); }
    }
    public record Scope(UUID pageId, String senderId, String conversationId) {
        public Scope {
            Objects.requireNonNull(pageId);
            if (senderId == null || senderId.isBlank()) throw new IllegalArgumentException("Memory requires sender");
            conversationId = conversationId == null || conversationId.isBlank() ? "default" : conversationId;
        }
        public String key() {
            try {
                // Length prefixes avoid aliases when identifiers contain delimiters.
                String raw = senderId.length() + ":" + senderId + conversationId.length() + ":" + conversationId;
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
            } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        }
    }
    public record Lease(Scope scope, View view) {}
    public interface Store {
        Lease begin(Scope scope, String customerMessage, boolean reset);
        boolean commit(Lease lease, View reviewedMemory);
    }
    static View advance(View previous, String message, boolean reset) {
        if (message == null || message.isBlank()) throw new IllegalArgumentException("Empty customer event");
        long revision = previous.revision() + 1;
        var sources = new ArrayList<>(reset ? List.<Source>of() : previous.customerSources());
        // Preserve whole messages. A truncated quote is not a reliable source.
        boolean truncated = !reset && previous.truncated();
        if (message.length() <= 12000) sources.add(new Source(revision, message));
        else truncated = true;
        while (sources.size() > 12 || sources.stream().mapToInt(s -> s.text().length()).sum() > 12000) {
            sources.removeFirst(); truncated = true;
        }
        return new View(revision, reset ? List.of() : previous.facts(), reset ? List.of() : previous.changes(), sources, truncated,
                reset ? SalesJourney.State.empty() : previous.journey());
    }
    /** Syntactic provenance checks; semantic entailment is additionally checked by the independent reviewer. */
    public static View apply(View base, List<Update> updates) {
        if (updates == null || updates.isEmpty()) return base;
        if (updates.size() > 12) throw new IllegalArgumentException("Too many memory updates");
        var facts = new LinkedHashMap<String, Fact>();
        base.facts().forEach(f -> facts.put(f.key(), f));
        var changes = new ArrayList<>(base.changes());
        var touched = new HashSet<String>();
        for (Update u : updates) {
            if (u == null || u.key() == null || !u.key().matches("[a-z][a-z0-9_.]{0,63}") || !touched.add(u.key())
                    || u.customerQuote() == null || u.customerQuote().isBlank() || u.customerQuote().length() > 800
                    || (!u.remove() && (u.value() == null || u.value().isBlank() || u.value().length() > 600)))
                throw new IllegalArgumentException("Invalid memory update");
            Source source = base.customerSources().stream().filter(s -> s.turn() == u.sourceTurn()).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown customer source"));
            if (!normalize(source.text()).contains(normalize(u.customerQuote())))
                throw new IllegalArgumentException("Unsupported memory quote");
            Fact before = facts.get(u.key());
            if (before != null && u.sourceTurn() < before.sourceTurn())
                throw new IllegalArgumentException("Older source cannot overwrite newer fact");
            if (u.remove()) facts.remove(u.key());
            else facts.put(u.key(), new Fact(u.key(), u.value(), u.customerQuote(), u.sourceTurn()));
            if (before != null && (u.remove() || !before.value().equals(u.value())))
                changes.add(new Change(u.key(), before.value(), u.remove() ? null : u.value(), u.sourceTurn()));
        }
        if (facts.size() > 48) throw new IllegalArgumentException("Memory capacity exceeded; preserve existing facts");
        while (changes.size() > 12) changes.removeFirst();
        return new View(base.revision(), List.copyOf(facts.values()), changes, base.customerSources(), base.truncated(), base.journey());
    }
    private static String normalize(String text) { return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC).replaceAll("\\s+", " ").strip(); }

    /** Test/embedded adapter; production uses JDBC, not a process-local conversation cache. */
    public static final class InMemoryStore implements Store {
        private final Map<Scope, View> rows = new HashMap<>();
        public synchronized Lease begin(Scope scope, String message, boolean reset) {
            View next = advance(rows.getOrDefault(scope, View.empty()), message, reset);
            rows.put(scope, next); return new Lease(scope, next);
        }
        public synchronized boolean commit(Lease lease, View memory) {
            if (rows.get(lease.scope()).revision() != lease.view().revision() || memory.revision() != lease.view().revision()) return false;
            rows.put(lease.scope(), memory); return true;
        }
    }
}
