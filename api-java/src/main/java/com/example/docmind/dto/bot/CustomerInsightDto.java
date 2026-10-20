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

        @JsonProperty("previous_temperature")
        Integer previousTemperature,

        @JsonProperty("temperature_delta")
        Integer temperatureDelta,

        @JsonProperty("momentum")
        String momentum,

        @JsonProperty("temperature_label")
        String temperatureLabel,

        @JsonProperty("decay_reason")
        String decayReason,

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
        if (momentum == null || momentum.isBlank()) {
            momentum = "STABLE";
        }
    }

    /**
     * Constructor tương thích ngược cho các bài test và caller cũ.
     */
    public CustomerInsightDto(
            String buyerArchetype,
            String archetypeLabel,
            int buyingTemperature,
            String temperatureLabel,
            String honorific,
            List<String> extractedPains,
            List<String> hiddenConcerns,
            String recommendedStrategy
    ) {
        this(
                buyerArchetype,
                archetypeLabel,
                buyingTemperature,
                null,
                0,
                "STABLE",
                temperatureLabel,
                null,
                honorific,
                extractedPains,
                hiddenConcerns,
                recommendedStrategy
        );
    }
}
