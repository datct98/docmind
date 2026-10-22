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
        String description,
        @JsonProperty("persona_config")
        PagePersonaConfig personaConfig
) {
    public KnowledgePageRequest(
            String name,
            String industry,
            String targetAudience,
            String description
    ) {
        this(name, industry, targetAudience, description, null);
    }
}
