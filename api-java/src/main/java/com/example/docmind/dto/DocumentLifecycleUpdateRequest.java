package com.example.docmind.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/**
 * Request cập nhật thời hạn hiệu lực (effective_from, effective_to) và trạng thái kích hoạt (is_active) của tài liệu.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocumentLifecycleUpdateRequest(
        @JsonProperty("effective_from")
        OffsetDateTime effectiveFrom,
        @JsonProperty("effective_to")
        OffsetDateTime effectiveTo,
        @JsonProperty("is_active")
        Boolean isActive
) {
}
