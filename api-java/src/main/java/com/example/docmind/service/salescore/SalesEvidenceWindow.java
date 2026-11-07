package com.example.docmind.service.salescore;

import java.text.Normalizer;
import java.util.*;
import static com.example.docmind.service.salescore.SalesContracts.*;

/** Domain-neutral lexical window over ALREADY tenant-scoped evidence. Whole rows preserve their conditions. */
final class SalesEvidenceWindow {
    private SalesEvidenceWindow() {}
    record Selection(List<Evidence> evidence, boolean truncated) {}
    static Selection select(TurnInput input, int maxChars) {
        // Recent customer corrections dominate; assistant wording never becomes a retrieval constraint.
        var query = new LinkedHashMap<String,Double>();
        input.memory().facts().forEach(f -> add(query, f.value(), 1));
        input.history().stream().filter(m -> "user".equalsIgnoreCase(m.role()) || "customer".equalsIgnoreCase(m.role()))
                .skip(Math.max(0, input.history().stream().filter(m -> "user".equalsIgnoreCase(m.role()) || "customer".equalsIgnoreCase(m.role())).count() - 2))
                .forEach(m -> add(query, m.content(), 1));
        add(query, input.message(), 4);
        var frequencies = new HashMap<String,Integer>();
        for (var e : input.evidence()) tokens(e.title() + " " + e.text()).forEach(t -> frequencies.merge(t, 1, Integer::sum));
        var ranked = new ArrayList<>(input.evidence());
        ranked.sort(Comparator.comparingDouble((Evidence e) -> score(e, query, frequencies, input.evidence().size())).reversed());
        var selected = new ArrayList<Evidence>();
        int chars = 0;
        for (var e : ranked) {
            int size = (e.title() == null ? 0 : e.title().length()) + (e.text() == null ? 0 : e.text().length());
            if (size == 0 || size > maxChars - chars) continue;
            selected.add(e); chars += size;
        }
        return new Selection(List.copyOf(selected), input.contextTruncated() || selected.size() != input.evidence().size());
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
