package com.example.docmind.service.salescore;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import static com.example.docmind.service.salescore.SalesContracts.*;

/** Checks verifiable contracts; does not claim to prove empathy or semantic entailment. */
public final class SalesDecisionValidator {
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*(?:\\s*(?:k|nghìn|ngàn|triệu|tr)\\b)?", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern MONEY = Pattern.compile("\\d+(?:[.,]\\d+)*\\s*(?:k\\b|nghìn\\b|ngàn\\b|triệu\\b|tr\\b|đồng\\b|vnd\\b|vnđ\\b|đ)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern UNAUTHORIZED = Pattern.compile(
            "(?iu)(?:đã|vừa)\\s+(?:đặt|giữ|chốt|xác nhận|thanh toán)|(?:đặt|giữ|thanh toán)\\s+.{0,35}thành công|(?:booking|payment|reservation)\\s+(?:is\\s+)?confirmed");
    public List<String> validate(TurnDecision d, TurnInput input) {
        Set<String> errors = new LinkedHashSet<>();
        if (d != null && d.followUp() != null && input.interactionPlan() != null && !input.interactionPlan().allowsFollowUp())
            errors.add("FOLLOWUP_WITHOUT_DECISION_PURPOSE");
        if (d == null || d.understanding() == null || d.strategy() == null || d.strategy().action() == null
                || blank(d.strategy().reason()) || blank(d.strategy().advance()) || d.bubbles() == null
                || d.bubbles().isEmpty() || d.bubbles().size() > 4) return List.of("INVALID_CONTRACT");
        var customers = new ArrayList<String>();
        customers.add(input.message());
        input.memory().facts().stream().map(SalesMemory.Fact::customerQuote).forEach(customers::add);
        input.memory().customerSources().stream().map(SalesMemory.Source::text).forEach(customers::add);
        input.history().stream().filter(m -> Set.of("user", "customer").contains(m.role().toLowerCase(Locale.ROOT)))
                .map(Message::content).forEach(customers::add);
        var facts = d.understanding().knownFacts();
        if (facts == null || d.understanding().uncertainties() == null) errors.add("INVALID_UNDERSTANDING");
        else for (CustomerFact fact : facts) {
            if (fact == null || blank(fact.value()) || blank(fact.customerQuote())
                    || customers.stream().filter(Objects::nonNull).noneMatch(s -> normalized(s).contains(normalized(fact.customerQuote()))))
                errors.add("UNSUPPORTED_CUSTOMER_FACT");
        }
        Map<String, Evidence> sources = new HashMap<>();
        input.evidence().forEach(e -> sources.put(e.id(), e));
        Set<String> calculated = Set.of();
        try { calculated = GroundedArithmetic.verify(d.calculations(), sources, customers); }
        catch (RuntimeException e) { errors.add("INVALID_CALCULATION"); }
        String customerText = String.join(" ", customers.stream().filter(Objects::nonNull).toList());
        Set<String> customerNumbers = numbers(customerText);
        // Quantities/time from the customer remain usable in any speech act. A budget never authorizes a selling price.
        var money = MONEY.matcher(customerText);
        while (money.find()) customerNumbers.removeAll(numbers(money.group()));
        Set<String> citedNumbers = new HashSet<>();
        // Bubbles form one answer. Reusing a fact in the next bubble does not require duplicating its citation.
        for (Bubble bubble : d.bubbles()) if (bubble != null && bubble.evidence() != null) {
            for (Citation c : bubble.evidence()) {
                Evidence source = c == null ? null : sources.get(c.sourceId());
                if (source == null || blank(c.quote()) || !normalized(sourceText(source)).contains(normalized(c.quote())))
                    errors.add("INVALID_EVIDENCE_QUOTE");
                else {
                    citedNumbers.addAll(numbers(c.quote()));
                    // Headings carry branch/region identifiers and are part of the cited source's scope.
                    citedNumbers.addAll(numbers(source.title() == null ? "" : source.title()));
                }
            }
        }
        for (Bubble b : d.bubbles()) {
            if (b == null || blank(b.text()) || b.text().length() > 1800 || b.evidence() == null) {
                errors.add("INVALID_BUBBLE"); continue;
            }
            Set<String> allowed = new HashSet<>(customerNumbers);
            allowed.addAll(calculated);
            allowed.addAll(citedNumbers);
            Set<String> missing = new TreeSet<>(numbers(b.text())); missing.removeAll(allowed);
            Set<String> moneyWithoutBusinessSource = monetaryNumbers(b.text());
            moneyWithoutBusinessSource.removeAll(citedNumbers);
            moneyWithoutBusinessSource.removeAll(calculated);
            missing.addAll(moneyWithoutBusinessSource);
            if (!missing.isEmpty()) {
                errors.add("UNSUPPORTED_NUMBER");
                // Give the repair enough information to change the actual violation, not blindly regenerate.
                errors.add("UNSUPPORTED_NUMBER_VALUES:" + String.join(",", missing));
            }
            if (UNAUTHORIZED.matcher(b.text()).find()) errors.add("UNAUTHORIZED_TRANSACTION_CLAIM");
            if (b.text().contains("{{") || b.text().contains("sourceId") || b.text().contains("TURN_DATA_JSON")) errors.add("INTERNAL_DATA_LEAK");
        }
        if (d.strategy().action() == Action.HANDOFF && blank(d.handoffSummary())) errors.add("MISSING_HANDOFF_SUMMARY");
        return List.copyOf(errors);
    }
    public List<String> validateDelivery(TurnDecision d, boolean paced, boolean continuation, boolean recovery) {
        if (d == null || d.bubbles() == null) return List.of("INVALID_DELIVERY");
        boolean price = d.bubbles().stream().filter(Objects::nonNull).map(Bubble::text)
                .filter(Objects::nonNull).anyMatch(t -> MONEY.matcher(t).find());
        if (continuation || recovery) {
            if (price || d.bubbles().size() > (continuation ? 1 : 2) || d.followUp() != null) return List.of("INVALID_CONTINUATION_DELIVERY");
        } else if (paced && price && d.strategy().action() != Action.HANDOFF && d.strategy().action() != Action.PAUSE) {
            if (d.bubbles().size() != 1 || (d.followUp() != null && blank(d.followUp().aim())))
                return List.of("PRICE_REQUIRES_SINGLE_BUBBLE_AND_VALID_OPTIONAL_FOLLOWUP");
            if (d.bubbles().getFirst().text().contains("?")) return List.of("PRICE_BUBBLE_QUESTION_BELONGS_IN_CONTINUATION");
        } else if (d.followUp() != null) return List.of("UNEXPECTED_FOLLOWUP");
        if (d.followUp() != null && d.followUp().aim().length() > 500) return List.of("FOLLOWUP_AIM_TOO_LONG");
        return List.of();
    }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    static String sourceText(Evidence source) { return (source.title() == null ? "" : source.title()) + "\n" + (source.text() == null ? "" : source.text()); }
    private static String normalized(String s) { return Normalizer.normalize(s, Normalizer.Form.NFC).replaceAll("\\s+", " ").strip(); }
    private static Set<String> monetaryNumbers(String text) {
        Set<String> values = new HashSet<>();
        var matches = MONEY.matcher(text);
        while (matches.find()) values.addAll(numbers(matches.group()));
        return values;
    }
    static Set<String> numbers(String s) {
        Set<String> result = new HashSet<>();
        var matcher = NUMBER.matcher(s);
        while (matcher.find()) {
            String token = matcher.group().toLowerCase(Locale.ROOT).replaceAll("\\s", "");
            long multiplier = token.matches(".*(?:triệu|tr)$") ? 1_000_000 : token.matches(".*(?:k|nghìn|ngàn)$") ? 1000 : 1;
            token = token.replaceAll("(?:triệu|nghìn|ngàn|tr|k)$", "");
            if (token.matches("\\d{1,3}(?:[.,]\\d{3})+")) token = token.replaceAll("[.,]", "");
            else token = token.replace(',', '.');
            try { result.add(new java.math.BigDecimal(token).multiply(java.math.BigDecimal.valueOf(multiplier)).stripTrailingZeros().toPlainString()); }
            catch (NumberFormatException e) { result.add(token); }
        }
        return result;
    }
}
