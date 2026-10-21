package com.example.docmind.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.List;
import java.util.Map;

/**
 * Spring Boot 4.0.0 Declarative HTTP Interface (@HttpExchange) kết nối sang
 * tầng FastAPI + LangGraph Engine (doc-service-py) qua mạng nội bộ Docker
 * kèm xác thực HMAC X-Internal-Token.
 */
@HttpExchange
public interface FastApiAgentClient {

    @PostExchange("/internal/v1/consult")
    ConsultResponse consultCustomer(
            @RequestHeader("X-Internal-Token") String internalToken,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestBody ConsultRequest payload
    );

    @PostExchange("/internal/v1/nightly-extract")
    NightlyExtractResponse extractGoldenSample(
            @RequestHeader("X-Internal-Token") String internalToken,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestBody NightlyExtractRequest logPayload
    );

    record ConsultRequest(
            @JsonProperty("customer_psid") String customerPsid,
            @JsonProperty("page_id") String pageId,
            @JsonProperty("message") String message,
            @JsonProperty("history") List<Map<String, String>> history,
            @JsonProperty("candidate_chunks") List<Map<String, Object>> candidateChunks,
            @JsonProperty("candidate_rules") List<Map<String, Object>> candidateRules,
            @JsonProperty("honorific") String honorific
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ConsultResponse(
            @JsonProperty("engine") String engine,
            @JsonProperty("customer_psid") String customerPsid,
            @JsonProperty("thread_id") String threadId,
            @JsonProperty("honorific") String honorific,
            @JsonProperty("active_topic") String activeTopic,
            @JsonProperty("topic_family") String topicFamily,
            @JsonProperty("topic_keywords") List<String> topicKeywords,
            @JsonProperty("is_profile_update_turn") boolean isProfileUpdateTurn,
            @JsonProperty("is_broad_price_turn") boolean isBroadPriceTurn,
            @JsonProperty("turn_index") int turnIndex,
            @JsonProperty("funnel_stage") String funnelStage,
            @JsonProperty("rewritten_query") String rewrittenQuery,
            @JsonProperty("filtered_chunks") List<Map<String, Object>> filteredChunks,
            @JsonProperty("selected_few_shots") List<Map<String, String>> selectedFewShots,
            @JsonProperty("stage_directive") String stageDirective,
            @JsonProperty("safe_tool_scope") Map<String, Object> safeToolScope
    ) {}

    record NightlyExtractRequest(
            @JsonProperty("page_id") String pageId,
            @JsonProperty("conversations") List<Map<String, Object>> conversations
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record NightlyExtractResponse(
            @JsonProperty("engine") String engine,
            @JsonProperty("page_id") String pageId,
            @JsonProperty("passed_count") int passedCount,
            @JsonProperty("rejected_count") int rejectedCount,
            @JsonProperty("samples") List<ExtractedSampleDto> samples
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ExtractedSampleDto(
            @JsonProperty("funnel_stage") String funnelStage,
            @JsonProperty("dimension") String dimension,
            @JsonProperty("category") String category,
            @JsonProperty("trigger_condition") String triggerCondition,
            @JsonProperty("cue_signal") String cueSignal,
            @JsonProperty("root_cause") String rootCause,
            @JsonProperty("generalized_rule") String generalizedRule,
            @JsonProperty("contrastive_evidence") String contrastiveEvidence,
            @JsonProperty("retrieval_keywords") List<String> retrievalKeywords,
            @JsonProperty("review_status") String reviewStatus,
            @JsonProperty("security_passed") boolean securityPassed,
            @JsonProperty("security_risk_level") String securityRiskLevel,
            @JsonProperty("security_violations") List<String> securityViolations,
            @JsonProperty("security_audit_notes") String securityAuditNotes
    ) {}
}
