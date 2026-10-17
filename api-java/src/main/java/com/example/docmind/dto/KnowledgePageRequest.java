package com.example.docmind.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * Request tạo mới / cập nhật Thư mục / Page tri thức.
 */
public record KnowledgePageRequest(
        @NotBlank(message = "Tên Page không được rỗng")
        String name,
        String industry,
        @JsonProperty("target_audience")
        String targetAudience,
        String description
) {
}
