package com.example.docmind.dto.bot;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Hồ sơ Đọc vị Tâm lý & Insight Khách hàng theo thời gian thực (Customer Insight Radar).
 */
public record CustomerInsightDto(
        @JsonProperty("buyer_archetype")
        String buyerArchetype,

        @JsonProperty("archetype_label")
        String archetypeLabel,

        @JsonProperty("buying_temperature")
        int buyingTemperature,

        @JsonProperty("temperature_label")
        String temperatureLabel,

        @JsonProperty("honorific")
        String honorific,

        @JsonProperty("extracted_pains")
        List<String> extractedPains,

        @JsonProperty("hidden_concerns")
        List<String> hiddenConcerns,

        @JsonProperty("recommended_strategy")
        String recommendedStrategy
) {
    public CustomerInsightDto {
        if (extractedPains == null) {
            extractedPains = List.of();
        }
        if (hiddenConcerns == null) {
            hiddenConcerns = List.of();
        }
    }
}
