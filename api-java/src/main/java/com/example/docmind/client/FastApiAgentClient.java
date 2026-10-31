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

    @PostExchange("/internal/v1/nightly-extract")
    NightlyExtractResponse extractGoldenSample(
            @RequestHeader("X-Internal-Token") String internalToken,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestBody NightlyExtractRequest logPayload
    );

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
