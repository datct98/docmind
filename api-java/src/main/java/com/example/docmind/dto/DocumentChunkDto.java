package com.example.docmind.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * DTO hiển thị chi tiết từng Chunk đã trích xuất của tài liệu trên giao diện quản trị "Tài liệu Page".
 */
public record DocumentChunkDto(
        @JsonProperty("chunk_id")
        String chunkId,
        @JsonProperty("document_id")
        String documentId,
        @JsonProperty("chunk_index")
        int chunkIndex,
        int page,
        @JsonProperty("heading_path")
        List<String> headingPath,
        String text,
        @JsonProperty("has_table")
        boolean hasTable,
        @JsonProperty("has_pricing")
        boolean hasPricing,
        Map<String, Object> metadata
) {
}
