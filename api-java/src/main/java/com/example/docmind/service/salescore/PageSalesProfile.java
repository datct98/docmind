package com.example.docmind.service.salescore;

import java.time.LocalDate;
import java.util.List;

/** Optional, operator-maintained data for a single Page; no executable rules. */
public record PageSalesProfile(String industryCode, String voice, List<String> businessPolicies, List<Offer> offers) {
    public PageSalesProfile {
        businessPolicies = businessPolicies == null ? List.of() : List.copyOf(businessPolicies);
        offers = offers == null ? List.of() : List.copyOf(offers);
        if (offers.size() > 100 || businessPolicies.size() > 30) throw new IllegalArgumentException("Page profile too large");
    }
    public record Offer(String id, String name, String price, String scope, String details,
                        String validFrom, String validUntil) {
        public Offer {
            if (id == null || !id.matches("[A-Za-z0-9_.-]{1,80}") || name == null || name.isBlank() || price == null || price.isBlank())
                throw new IllegalArgumentException("Offer requires id, name and price with unit/currency");
        }
        boolean effective(LocalDate date) {
            return (validFrom == null || !LocalDate.parse(validFrom).isAfter(date))
                    && (validUntil == null || !LocalDate.parse(validUntil).isBefore(date));
        }
        String asEvidence() {
            return name + " | Giá: " + price + " | Phạm vi: " + (scope == null ? "chưa rõ" : scope)
                    + " | Chi tiết và điều kiện: " + (details == null ? "chưa rõ" : details);
        }
    }
}
