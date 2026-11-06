package com.example.docmind.service.salescore;

import java.math.BigDecimal;
import java.util.*;
import static com.example.docmind.service.salescore.SalesContracts.*;

/** Domain-neutral arithmetic with provenance for every operand; never evaluates model-generated code. */
public final class GroundedArithmetic {
    private GroundedArithmetic() {}
    public static Set<String> verify(List<Calculation> calculations, Map<String, Evidence> sources, List<String> customers) {
        if (calculations.size() > 12) throw new IllegalArgumentException("Too many calculations");
        Set<String> derived = new HashSet<>();
        List<BigDecimal> verifiedResults = new ArrayList<>();
        for (var calculation : calculations) {
            if (calculation == null || calculation.operands() == null || calculation.operands().size() < 2
                    || calculation.operands().size() > 8 || calculation.result() == null) throw new IllegalArgumentException("Invalid calculation");
            List<BigDecimal> values = new ArrayList<>();
            for (var operand : calculation.operands()) {
                if (operand == null || operand.value() == null || operand.sourceId() == null)
                    throw new IllegalArgumentException("Missing operand evidence");
                if (operand.sourceId().startsWith("CALCULATION:")) {
                    int index = Integer.parseInt(operand.sourceId().substring("CALCULATION:".length()));
                    if (index < 0 || index >= verifiedResults.size() || operand.value().compareTo(verifiedResults.get(index)) != 0)
                        throw new IllegalArgumentException("Unproven derived operand");
                } else {
                    if (operand.quote() == null || operand.quote().isBlank()) throw new IllegalArgumentException("Missing operand quote");
                    List<String> texts = "CUSTOMER".equals(operand.sourceId()) ? customers
                            : sources.containsKey(operand.sourceId()) ? List.of(SalesDecisionValidator.sourceText(sources.get(operand.sourceId()))) : List.of();
                    if (texts.stream().filter(Objects::nonNull).noneMatch(t -> t.contains(operand.quote()))
                            || !SalesDecisionValidator.numbers(operand.quote()).contains(operand.value().stripTrailingZeros().toPlainString()))
                        throw new IllegalArgumentException("Unproven operand");
                }
                if (operand.value().abs().compareTo(new BigDecimal("1000000000000")) > 0) throw new IllegalArgumentException("Operand too large");
                values.add(operand.value());
            }
            BigDecimal value = values.getFirst();
            for (int i = 1; i < values.size(); i++) {
                value = switch (calculation.operation()) {
                    case "ADD" -> value.add(values.get(i));
                    case "SUBTRACT" -> value.subtract(values.get(i));
                    case "MULTIPLY" -> value.multiply(values.get(i));
                    case "DIVIDE" -> value.divide(values.get(i));
                    default -> throw new IllegalArgumentException("Unknown operation");
                };
            }
            if (value.compareTo(calculation.result()) != 0) throw new IllegalArgumentException("Wrong result");
            derived.add(value.stripTrailingZeros().toPlainString());
            verifiedResults.add(value);
        }
        return derived;
    }
}
