package com.example.docmind.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.List;
import java.util.Map;

/**
 * Spring Boot 4.0 Declarative HTTP Interface (@HttpExchange) kết nối trực tiếp
 * tới microservice độc lập {@code agent-brain} (cổng 8095) theo kiến trúc
 * {@code Retain -> Recall -> Reflect}.
 */
@HttpExchange
public interface AgentBrainClient {

    @GetExchange("/v1/brain/health")
    Map<String, Object> health();

    @PostExchange("/v1/brain/recall-and-advise")
    RecallAndAdviseResponse recallAndAdvise(@RequestBody RecallAndAdviseRequest payload);

    @PostExchange("/v1/brain/retain")
    RetainTurnResponse retainTurn(@RequestBody RetainTurnRequest payload);

    @PostExchange("/v1/brain/reflect")
    ReflectResponse reflect(@RequestBody ReflectRequest payload);

    record RecallAndAdviseRequest(
            @JsonProperty("tenant_id") String tenantId,
            @JsonProperty("customer_id") String customerId,
            @JsonProperty("conversation_id") String conversationId,
            @JsonProperty("message") String message,
            @JsonProperty("customer_name") String customerName,
            @JsonProperty("retrieval_quality") double retrievalQuality,
            @JsonProperty("groundedness") double groundedness,
            @JsonProperty("domain_Metadata") Map<String, Object> domainMetadata
    ) {
        public RecallAndAdviseRequest(
                String tenantId,
                String customerId,
                String conversationId,
                String message,
                String customerName,
                double retrievalQuality,
                double groundedness) {
            this(tenantId, customerId, conversationId, message, customerName, retrievalQuality, groundedness, Map.of());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PsychologicalSkillDirectiveDto(
            @JsonProperty("skill_code") String skillCode,
            @JsonProperty("skill_name") String skillName,
            @JsonProperty("academic_origin") String academicOrigin,
            @JsonProperty("trigger_reason") String triggerReason,
            @JsonProperty("bubble_plan") List<String> bubblePlan,
            @JsonProperty("subtle_flex_hint") String subtleFlexHint,
            @JsonProperty("scarcity_or_close_hint") String scarcityOrCloseHint,
            @JsonProperty("prompt_directive") String promptDirective,
            @JsonProperty("audience_voice_cadence") String audienceVoiceCadence,
            @JsonProperty("price_disclosure_gate") String priceDisclosureGate
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MemoryFactDto(
            @JsonProperty("id") Long id,
            @JsonProperty("fact_key") String factKey,
            @JsonProperty("fact_value") String factValue,
            @JsonProperty("source_type") String sourceType,
            @JsonProperty("confidence") double confidence,
            @JsonProperty("is_active") boolean isActive,
            @JsonProperty("superseded_by") Long supersededBy
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ObjectionItemDto(
            @JsonProperty("type") String type,
            @JsonProperty("quote") String quote,
            @JsonProperty("count") int count,
            @JsonProperty("confidence") double confidence
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RecallAndAdviseResponse(
            @JsonProperty("tenant_id") String tenantId,
            @JsonProperty("customer_id") String customerId,
            @JsonProperty("conversation_id") String conversationId,
            @JsonProperty("normalized_message") String normalizedMessage,
            @JsonProperty("masked_message") String maskedMessage,
            @JsonProperty("honorific") String honorific,
            @JsonProperty("purchase_stage") String purchaseStage,
            @JsonProperty("sentiment") String sentiment,
            @JsonProperty("intent") String intent,
            @JsonProperty("buyer_archetype") String buyerArchetype,
            @JsonProperty("buying_temperature") int buyingTemperature,
            @JsonProperty("final_confidence") double finalConfidence,
            @JsonProperty("next_best_action") String nextBestAction,
            @JsonProperty("requires_human") boolean requiresHuman,
            @JsonProperty("handoff_reason") String handoffReason,
            @JsonProperty("active_skill") PsychologicalSkillDirectiveDto activeSkill,
            @JsonProperty("customer_memory_block") String customerMemoryBlock,
            @JsonProperty("active_facts") List<MemoryFactDto> activeFacts,
            @JsonProperty("objections") List<ObjectionItemDto> objections,
            @JsonProperty("latency_ms") double latencyMs
    ) {}

    record RetainTurnRequest(
            @JsonProperty("tenant_id") String tenantId,
            @JsonProperty("customer_id") String customerId,
            @JsonProperty("conversation_id") String conversationId,
            @JsonProperty("user_message") String userMessage,
            @JsonProperty("assistant_reply") String assistantReply,
            @JsonProperty("customer_name") String customerName,
            @JsonProperty("summarize") boolean summarize
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RetainTurnResponse(
            @JsonProperty("status") String status,
            @JsonProperty("extracted_facts") List<MemoryFactDto> extractedFacts,
            @JsonProperty("superseded_fact_ids") List<Long> supersededFactIds,
            @JsonProperty("objections") List<ObjectionItemDto> objections,
            @JsonProperty("purchase_stage") String purchaseStage
    ) {}

    record ReflectRequest(
            @JsonProperty("tenant_id") String tenantId,
            @JsonProperty("customer_id") String customerId,
            @JsonProperty("conversation_id") String conversationId
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ReflectResponse(
            @JsonProperty("tenant_id") String tenantId,
            @JsonProperty("customer_id") String customerId,
            @JsonProperty("interested_items") List<String> interestedItems,
            @JsonProperty("primary_barriers") List<String> primaryBarriers,
            @JsonProperty("purchase_intent_level") String purchaseIntentLevel,
            @JsonProperty("bot_assessment") String botAssessment,
            @JsonProperty("recommended_human_action") String recommendedHumanAction,
            @JsonProperty("should_call_customer") boolean shouldCallCustomer,
            @JsonProperty("compliance_or_complaint_risk") String complianceOrComplaintRisk,
            @JsonProperty("summary_markdown") String summaryMarkdown
    ) {}
}
