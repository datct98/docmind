package com.example.docmind.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity lưu trữ Kỹ năng Agent (Agent Skill Registry) thuộc 3 trụ cột:
 * PSYCHOLOGY (Tâm lý học khách hàng), SALES_STRATEGY (Chiến thuật Tư vấn & Chốt Sale),
 * COMMUNICATION (Nghệ thuật Giao tiếp & Ngôn từ).
 */
@Entity
@Table(name = "agent_skills")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentSkillEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "code", nullable = false, length = 80)
    private String code;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "category", nullable = false, length = 40)
    private String category;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "trigger_signals", nullable = false, columnDefinition = "TEXT")
    private String triggerSignals;

    @Column(name = "tactical_instructions", nullable = false, columnDefinition = "TEXT")
    private String tacticalInstructions;

    @Column(name = "good_example", nullable = false, columnDefinition = "TEXT")
    private String goodExample;

    @Column(name = "anti_pattern", nullable = false, columnDefinition = "TEXT")
    private String antiPattern;

    @Column(name = "priority", nullable = false)
    private int priority;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "page_id")
    private UUID pageId;

    @Builder.Default
    @Column(name = "applied_count", nullable = false)
    private int appliedCount = 0;

    @Builder.Default
    @Column(name = "conversion_count", nullable = false)
    private int conversionCount = 0;

    @Builder.Default
    @Column(name = "confidence_score", nullable = false)
    private double confidenceScore = 0.90;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
