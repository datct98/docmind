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

@Entity
@Table(name = "conversation_sessions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationSessionEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "page_id")
    private UUID pageId;

    @Column(name = "customer_psid", nullable = false, length = 128)
    private String customerPsid;

    @Builder.Default
    @Column(name = "customer_name", nullable = false, length = 255)
    private String customerName = "Khách hàng Messenger";

    @Builder.Default
    @Column(name = "honorific", nullable = false, length = 32)
    private String honorific = "Chị";

    @Builder.Default
    @Column(name = "active_topic", nullable = false, columnDefinition = "TEXT")
    private String activeTopic = "Tư vấn dịch vụ chuẩn Y khoa";

    @Builder.Default
    @Column(name = "topic_family", nullable = false, length = 64)
    private String topicFamily = "GENERAL";

    /** BOT_ACTIVE | NEEDS_HANDOFF | HUMAN_TAKEOVER | CONVERTED */
    @Builder.Default
    @Column(name = "control_mode", nullable = false, length = 32)
    private String controlMode = "BOT_ACTIVE";

    @Column(name = "handoff_category", length = 64)
    private String handoffCategory;

    @Column(name = "handoff_reason", columnDefinition = "TEXT")
    private String handoffReason;

    @Column(name = "captured_phone", length = 32)
    private String capturedPhone;

    @Column(name = "preferred_time", length = 128)
    private String preferredTime;

    @Builder.Default
    @Column(name = "buying_temperature", nullable = false)
    private int buyingTemperature = 42;

    @Column(name = "previous_temperature")
    private Integer previousTemperature;

    @Builder.Default
    @Column(name = "temperature_delta", nullable = false)
    private int temperatureDelta = 0;

    @Builder.Default
    @Column(name = "temperature_label", nullable = false, length = 64)
    private String temperatureLabel = "WARM";

    @Builder.Default
    @Column(name = "momentum", nullable = false, length = 64)
    private String momentum = "STABLE";

    @Builder.Default
    @Column(name = "buyer_archetype", nullable = false, length = 64)
    private String buyerArchetype = "EXPLORING_CONSULTATION";

    @Builder.Default
    @Column(name = "archetype_label", nullable = false, length = 128)
    private String archetypeLabel = "Đang tìm hiểu giải pháp";

    @Column(name = "extracted_pains", columnDefinition = "TEXT")
    private String extractedPains;

    @Column(name = "hidden_concerns", columnDefinition = "TEXT")
    private String hiddenConcerns;

    @Column(name = "recommended_strategy", columnDefinition = "TEXT")
    private String recommendedStrategy;

    @Builder.Default
    @Column(name = "sales_stage", nullable = false, length = 64)
    private String salesStage = "DISCOVERY";

    @Builder.Default
    @Column(name = "has_human_sale_reply", nullable = false)
    private boolean hasHumanSaleReply = false;

    @Builder.Default
    @Column(name = "learned_in_cold_path", nullable = false)
    private boolean learnedInColdPath = false;

    @Builder.Default
    @Column(name = "last_message_preview", nullable = false, columnDefinition = "TEXT")
    private String lastMessagePreview = "";

    @Builder.Default
    @Column(name = "last_sender_role", nullable = false, length = 32)
    private String lastSenderRole = "CUSTOMER";

    @Column(name = "last_message_at", nullable = false)
    private Instant lastMessageAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
