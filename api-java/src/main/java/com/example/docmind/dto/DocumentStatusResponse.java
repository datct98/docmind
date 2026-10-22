package com.example.docmind.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.Map;

/** Response của GET /api/documents/{id}. */
public record DocumentStatusResponse(
        @JsonProperty("document_id") String documentId,
        String filename,
        String status,
        int pages,
        @JsonProperty("engine_breakdown") Map<String, Integer> engineBreakdown,
        @JsonProperty("page_id") String pageId,
        @JsonProperty("effective_from") OffsetDateTime effectiveFrom,
        @JsonProperty("effective_to") OffsetDateTime effectiveTo,
        @JsonProperty("is_active") boolean isActive,
        @JsonProperty("is_currently_effective") boolean isCurrentlyEffective
) {
    public DocumentStatusResponse(String documentId,
                                  String filename,
                                  String status,
                                  int pages,
                                  Map<String, Integer> engineBreakdown,
                                  String pageId) {
        this(documentId, filename, status, pages, engineBreakdown, pageId, null, null, true, true);
    }

    public DocumentStatusResponse(String documentId,
                                  String filename,
                                  String status,
                                  int pages,
                                  Map<String, Integer> engineBreakdown) {
        this(documentId, filename, status, pages, engineBreakdown, null, null, null, true, true);
    }

    @JsonProperty("is_expired")
    public boolean isExpired() {
        return isActive && !isCurrentlyEffective;
    }
}
