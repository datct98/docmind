package com.example.docmind.dto.harness;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Request payload for POST /api/harness/optimize and service-level optimization invocations.
 */
public record HarnessOptimizeRequest(
        @JsonProperty("page_id")
        @JsonAlias({"pageId"})
        String pageId,

        @JsonProperty("customer_message")
        @JsonAlias({"customerMessage", "message"})
        @NotBlank(message = "customer_message must not be blank")
        String customerMessage,

        @JsonProperty("base_response")
        @JsonAlias({"baseResponse", "base_reply", "baseReply"})
        @NotBlank(message = "base_response must not be blank")
        String baseResponse,

        @JsonProperty("conversation_id")
        @JsonAlias({"conversationId"})
        String conversationId,

        @JsonProperty("history")
        List<String> history,

        @JsonProperty("temperature_delta")
        @JsonAlias({"temperatureDelta"})
        Integer temperatureDelta,

        @JsonProperty("triggered_hooks")
        @JsonAlias({"triggeredHooks"})
        List<String> triggeredHooks,

        @JsonProperty("trigger_reason")
        @JsonAlias({"triggerReason"})
        String triggerReason,

        @JsonProperty("task_id")
        @JsonAlias({"taskId"})
        String taskId,

        @JsonProperty("seed")
        Long seed
) {
    public HarnessOptimizeRequest {
        if (temperatureDelta == null) {
            temperatureDelta = 0;
        }
        if (triggerReason == null || triggerReason.isBlank()) {
            triggerReason = "MANUAL_1CLICK";
        }
        if (history == null) {
            history = List.of();
        }
        if (triggeredHooks == null) {
            triggeredHooks = List.of();
        }
    }

    public HarnessOptimizeRequest(String pageId, String customerMessage, String baseResponse, Integer temperatureDelta) {
        this(pageId, customerMessage, baseResponse, null, List.of(), temperatureDelta, List.of(), "MANUAL_1CLICK", null, null);
    }

    public HarnessOptimizeRequest(String pageId, String customerMessage, String baseResponse, Integer temperatureDelta, String taskId, Long seed) {
        this(pageId, customerMessage, baseResponse, null, List.of(), temperatureDelta, List.of(), "MANUAL_1CLICK", taskId, seed);
    }

    /**
     * Alias for baseResponse to ensure cross-contract compatibility.
     */
    public String baseReply() {
        return baseResponse;
    }
}
