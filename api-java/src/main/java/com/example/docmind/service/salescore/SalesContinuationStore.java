package com.example.docmind.service.salescore;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import static com.example.docmind.service.salescore.SalesContracts.*;

/** Short-lived, single-use continuation tickets. A new inbound invalidates all older work for that page/sender. */
final class SalesContinuationStore {
    record Key(UUID page, String sender) {}
    record Ticket(Key key, String conversation, long sequence) {}
    record Pending(Ticket ticket, TurnInput input, TurnDecision first) {}
    private record Entry(Ticket ticket, long expiresAt, Pending pending, boolean claimed) {}
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>();
    private final LongSupplier nanoTime;
    private final int limit;
    private final long ttlNanos;
    // Tokens fit JS safe integers. They are scoped to page+sender+conversation, never accepted as client evidence.
    private long sequence = ThreadLocalRandom.current().nextLong(1, 1L << 50);

    SalesContinuationStore() { this(System::nanoTime, 512, 120_000_000_000L); }
    SalesContinuationStore(LongSupplier nanoTime, int limit, long ttlNanos) {
        this.nanoTime = nanoTime; this.limit = limit; this.ttlNanos = ttlNanos;
    }
    synchronized Ticket begin(UUID page, String sender, String conversation) {
        expire();
        Key key = new Key(page, Objects.requireNonNullElse(sender, ""));
        entries.remove(key);
        while (entries.size() >= limit) entries.remove(entries.keySet().iterator().next());
        Ticket ticket = new Ticket(key, Objects.requireNonNullElse(conversation, ""), ++sequence);
        entries.put(key, new Entry(ticket, nanoTime.getAsLong() + ttlNanos, null, false));
        return ticket;
    }
    synchronized void cancel(UUID page, String sender) { entries.remove(new Key(page, Objects.requireNonNullElse(sender, ""))); }
    synchronized boolean stage(Ticket ticket, TurnInput input, TurnDecision first) {
        if (!current(ticket)) return false;
        Entry entry = entries.get(ticket.key());
        entries.put(ticket.key(), new Entry(ticket, entry.expiresAt(), new Pending(ticket, input, first), false));
        return true;
    }
    synchronized Pending claim(UUID page, String sender, String conversation, Long sequence) {
        expire();
        Entry entry = entries.get(new Key(page, Objects.requireNonNullElse(sender, "")));
        if (entry == null || sequence == null || entry.claimed() || entry.pending() == null
                || entry.ticket().sequence() != sequence || !entry.ticket().conversation().equals(Objects.requireNonNullElse(conversation, ""))) return null;
        entries.put(entry.ticket().key(), new Entry(entry.ticket(), entry.expiresAt(), entry.pending(), true));
        return entry.pending();
    }
    synchronized boolean current(Ticket ticket) {
        expire();
        Entry entry = entries.get(ticket.key());
        return entry != null && entry.ticket().equals(ticket);
    }
    synchronized void finish(Ticket ticket) { if (current(ticket)) entries.remove(ticket.key()); }
    private void expire() { long now = nanoTime.getAsLong(); entries.values().removeIf(e -> now >= e.expiresAt()); }
}
