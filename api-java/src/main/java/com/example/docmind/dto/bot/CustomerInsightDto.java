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
        String recommendedStrategy,

        @JsonProperty("care_notes")
        List<String> careNotes,

        @JsonProperty("working_hypothesis")
        String workingHypothesis,

        @JsonProperty("last_actual_sent_speech_act")
        String lastActualSentSpeechAct,

        @JsonProperty("price_objection_count")
        int priceObjectionCount,

        @JsonProperty("in_price_objection_context")
        boolean inPriceObjectionContext,

        @JsonProperty("has_presented_downsell")
        boolean hasPresentedDownsell
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
        if (careNotes == null) {
            careNotes = List.of();
        }
        if (workingHypothesis == null) {
            workingHypothesis = "";
        }
        if (lastActualSentSpeechAct == null) {
            lastActualSentSpeechAct = "";
        }
        if (priceObjectionCount < 0) {
            priceObjectionCount = 0;
        }
    }

    /**
     * Constructor 15 tham số tương thích ngược.
     */
    public CustomerInsightDto(
            String buyerArchetype,
            String archetypeLabel,
            int buyingTemperature,
            Integer previousTemperature,
            Integer temperatureDelta,
            String momentum,
            String temperatureLabel,
            String decayReason,
            String honorific,
            List<String> extractedPains,
            List<String> hiddenConcerns,
            String recommendedStrategy,
            List<String> careNotes,
            String workingHypothesis,
            String lastActualSentSpeechAct
    ) {
        this(
                buyerArchetype,
                archetypeLabel,
                buyingTemperature,
                previousTemperature,
                temperatureDelta,
                momentum,
                temperatureLabel,
                decayReason,
                honorific,
                extractedPains,
                hiddenConcerns,
                recommendedStrategy,
                careNotes,
                workingHypothesis,
                lastActualSentSpeechAct,
                0,
                false,
                false
        );
    }

    /**
     * Constructor 14 tham số tương thích ngược.
     */
    public CustomerInsightDto(
            String buyerArchetype,
            String archetypeLabel,
            int buyingTemperature,
            Integer previousTemperature,
            Integer temperatureDelta,
            String momentum,
            String temperatureLabel,
            String decayReason,
            String honorific,
            List<String> extractedPains,
            List<String> hiddenConcerns,
            String recommendedStrategy,
            List<String> careNotes,
            String workingHypothesis
    ) {
        this(
                buyerArchetype,
                archetypeLabel,
                buyingTemperature,
                previousTemperature,
                temperatureDelta,
                momentum,
                temperatureLabel,
                decayReason,
                honorific,
                extractedPains,
                hiddenConcerns,
                recommendedStrategy,
                careNotes,
                workingHypothesis,
                "",
                0,
                false,
                false
        );
    }

    /**
     * Constructor 12 tham số tương thích ngược cho các caller hiện hữu.
     */
    public CustomerInsightDto(
            String buyerArchetype,
            String archetypeLabel,
            int buyingTemperature,
            Integer previousTemperature,
            Integer temperatureDelta,
            String momentum,
            String temperatureLabel,
            String decayReason,
            String honorific,
            List<String> extractedPains,
            List<String> hiddenConcerns,
            String recommendedStrategy
    ) {
        this(
                buyerArchetype,
                archetypeLabel,
                buyingTemperature,
                previousTemperature,
                temperatureDelta,
                momentum,
                temperatureLabel,
                decayReason,
                honorific,
                extractedPains,
                hiddenConcerns,
                recommendedStrategy,
                List.of(),
                "",
                "",
                0,
                false,
                false
        );
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
                recommendedStrategy,
                List.of(),
                "",
                "",
                0,
                false,
                false
        );
    }
}
