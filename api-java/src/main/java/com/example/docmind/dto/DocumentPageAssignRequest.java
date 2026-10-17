package com.example.docmind.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request gán hoặc di chuyển tài liệu sang một Page / Thư mục khác (null = chưa phân loại).
 */
public record DocumentPageAssignRequest(
        @JsonProperty("page_id")
        String pageId
) {
}
