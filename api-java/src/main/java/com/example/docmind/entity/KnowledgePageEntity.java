package com.example.docmind.entity;

import com.example.docmind.dto.IndustryPersonaTemplates;
import com.example.docmind.dto.PagePersonaConfig;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Thư mục / Page tri thức 1 cấp phẳng.
 * Mỗi Page đại diện cho 1 ngành nghề (industry) và đối tượng khách hàng (target_audience) riêng,
 * kèm cấu hình "Lớp da Nhân cách" (persona_config JSONB).
 */
@Entity
@Table(name = "knowledge_pages")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgePageEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "industry", nullable = false)
    private String industry;

    @Column(name = "target_audience", nullable = false)
    private String targetAudience;

    @Column(name = "description", nullable = false)
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "persona_config", nullable = false)
    private String personaConfigJson;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public PagePersonaConfig getPersonaConfig() {
        PagePersonaConfig parsed = PagePersonaConfig.fromJson(personaConfigJson);
        return IndustryPersonaTemplates.resolveEffectiveConfig(
                parsed,
                name,
                industry,
                targetAudience,
                description
        );
    }

    public void setPersonaConfig(PagePersonaConfig config) {
        this.personaConfigJson = config != null ? config.toJson() : "{}";
    }

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (industry == null) {
            industry = "";
        }
        if (targetAudience == null) {
            targetAudience = "";
        }
        if (description == null) {
            description = "";
        }
        if (personaConfigJson == null || personaConfigJson.isBlank()) {
            personaConfigJson = IndustryPersonaTemplates.resolveEffectiveConfig(
                    null, name, industry, targetAudience, description
            ).toJson();
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        if (personaConfigJson == null || personaConfigJson.isBlank()) {
            personaConfigJson = "{}";
        }
        updatedAt = OffsetDateTime.now();
    }
}
