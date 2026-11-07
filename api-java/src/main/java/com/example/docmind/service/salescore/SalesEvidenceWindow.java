package com.example.docmind.service.salescore;

import java.text.Normalizer;
import java.util.*;
import static com.example.docmind.service.salescore.SalesContracts.*;

/** Domain-neutral lexical window over ALREADY tenant-scoped evidence. Whole rows preserve their conditions. */
final class SalesEvidenceWindow {
    private SalesEvidenceWindow() {}
    record Selection(List<Evidence> evidence, boolean truncated) {}
    static Selection select(TurnInput input, int maxChars) {
        // Customer corrections dominate. Visible assistant wording can resolve a follow-up's subject,
        // but only selects current business evidence; it never becomes factual authority.
        var query = new LinkedHashMap<String,Double>();
        input.memory().facts().forEach(f -> add(query, f.value(), 1));
        input.history().stream().filter(m -> "user".equalsIgnoreCase(m.role()) || "customer".equalsIgnoreCase(m.role()))
                .skip(Math.max(0, input.history().stream().filter(m -> "user".equalsIgnoreCase(m.role()) || "customer".equalsIgnoreCase(m.role())).count() - 2))
                .forEach(m -> add(query, m.content(), 1));
        add(query, input.message(), 4);
        var recentAssistant = input.history().stream().filter(m -> "assistant".equalsIgnoreCase(m.role())).toList();
        recentAssistant.stream().skip(Math.max(0, recentAssistant.size() - 2)).forEach(m -> add(query, m.content(), .25));
        var frequencies = new HashMap<String,Integer>();
        for (var e : input.evidence()) tokens(e.title() + " " + e.text()).forEach(t -> frequencies.merge(t, 1, Integer::sum));
        var ranked = new ArrayList<>(input.evidence());
        ranked.sort(Comparator.comparingDouble((Evidence e) -> score(e, query, frequencies, input.evidence().size())).reversed());
        var selected = new ArrayList<Evidence>();
        int chars = 0;
        // Applicable terms must not be crowded out by many branch-specific prices. Reserve at most
        // one quarter for the highest-ranked policies; unused space remains available to all evidence.
        for (var e : ranked) {
            Object kind = e.metadata().get("record_kind");
            if (!"POLICY".equals(kind) && !"PROMOTION".equals(kind)) continue;
            int size = size(e);
            if (size == 0 || size > maxChars / 4 - chars) continue;
            selected.add(e); chars += size;
        }
        for (var e : ranked) {
            if (selected.contains(e)) continue;
            int size = size(e);
            if (size == 0 || size > maxChars - chars) continue;
            selected.add(e); chars += size;
        }
        return new Selection(List.copyOf(selected), input.contextTruncated() || selected.size() != input.evidence().size());
    }
    private static int size(Evidence e) {
        return (e.title() == null ? 0 : e.title().length()) + (e.text() == null ? 0 : e.text().length())
                + e.metadata().toString().length();
    }
    private static double score(Evidence e, Map<String,Double> query, Map<String,Integer> frequencies, int size) {
        var title = tokens(e.title()); var text = tokens(e.text());
        return query.entrySet().stream().mapToDouble(q -> {
            double idf = Math.log1p((double)(size + 1) / (1 + frequencies.getOrDefault(q.getKey(), 0)));
            double phrase = q.getKey().contains(" ") ? 3 : 1;
            return q.getValue() * idf * phrase * (title.contains(q.getKey()) ? 5 : text.contains(q.getKey()) ? 1 : 0);
        }).sum();
    }
    private static void add(Map<String,Double> into, String text, double weight) {
        tokens(text).forEach(t -> into.merge(t, weight, Math::max));
    }
    private static Set<String> tokens(String text) {
        if (text == null || text.isBlank()) return Set.of();
        String normalized = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replace('đ', 'd');
        var words = Arrays.stream(normalized.split("[^\\p{L}\\p{N}]+")).filter(t -> !t.isBlank()).toList();
        var tokens = new HashSet<String>(words);
        for (int i=1; i<words.size(); i++) tokens.add(words.get(i-1) + " " + words.get(i));
        return tokens;
    }
}
