package com.example.docmind.dto.bot;

import com.example.docmind.entity.AgentSkillEntity;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO biểu diễn một Kỹ năng Agent (Tâm lý học / Chiến thuật Sale / Giao tiếp)
 * kèm thông tin Quản trị Kỹ năng động (Tự động gộp trùng lặp & Cảnh báo xung đột chiến thuật).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentSkillDto(
        @JsonProperty("id")
        UUID id,

        @JsonProperty("code")
        String code,

        @JsonProperty("name")
        String name,

        @JsonProperty("category")
        String category,

        @JsonProperty("description")
        String description,

        @JsonProperty("trigger_signals")
        String triggerSignals,

        @JsonProperty("tactical_instructions")
        String tacticalInstructions,

        @JsonProperty("good_example")
        String goodExample,

        @JsonProperty("anti_pattern")
        String antiPattern,

        @JsonProperty("priority")
        int priority,

        @JsonProperty("active")
        boolean active,

        @JsonProperty("page_id")
        String pageId,

        @JsonProperty("applied_count")
        int appliedCount,

        @JsonProperty("conversion_count")
        int conversionCount,

        @JsonProperty("confidence_score")
        double confidenceScore,

        @JsonProperty("created_at")
        Instant createdAt,

        @JsonProperty("updated_at")
        Instant updatedAt,

        @JsonProperty("governance_action")
        String governanceAction,

        @JsonProperty("governance_message")
        String governanceMessage,

        @JsonProperty("conflict_detected")
        Boolean conflictDetected,

        @JsonProperty("conflicting_skill")
        AgentSkillDto conflictingSkill,

        @JsonProperty("conflict_reason")
        String conflictReason,

        @JsonProperty("recommended_action")
        String recommendedAction
) {
    public AgentSkillDto {
        if (confidenceScore <= 0.0) {
            confidenceScore = 0.90;
        }
    }

    public AgentSkillDto(
            UUID id,
            String code,
            String name,
            String category,
            String description,
            String triggerSignals,
            String tacticalInstructions,
            String goodExample,
            String antiPattern,
            int priority,
            boolean active,
            String pageId,
            int appliedCount,
            int conversionCount,
            double confidenceScore,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(id, code, name, category, description, triggerSignals, tacticalInstructions,
                goodExample, antiPattern, priority, active, pageId, appliedCount, conversionCount,
                confidenceScore, createdAt, updatedAt, null, null, null, null, null, null);
    }

    public AgentSkillDto(
            UUID id,
            String code,
            String name,
            String category,
            String description,
            String triggerSignals,
            String tacticalInstructions,
            String goodExample,
            String antiPattern,
            int priority,
            boolean active,
            String pageId,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(id, code, name, category, description, triggerSignals, tacticalInstructions,
                goodExample, antiPattern, priority, active, pageId, 0, 0, 0.90, createdAt, updatedAt);
    }

    public AgentSkillDto withGovernance(String action, String message) {
        return new AgentSkillDto(
                id, code, name, category, description, triggerSignals, tacticalInstructions,
                goodExample, antiPattern, priority, active, pageId, appliedCount, conversionCount,
                confidenceScore, createdAt, updatedAt, action, message, false, null, null, null
        );
    }

    public static AgentSkillDto conflictDetected(
            AgentSkillDto candidateSkill,
            AgentSkillDto existingConflictingSkill,
            String conflictReason,
            String recommendedAction
    ) {
        return new AgentSkillDto(
                candidateSkill.id(),
                candidateSkill.code(),
                candidateSkill.name(),
                candidateSkill.category(),
                candidateSkill.description(),
                candidateSkill.triggerSignals(),
                candidateSkill.tacticalInstructions(),
                candidateSkill.goodExample(),
                candidateSkill.antiPattern(),
                candidateSkill.priority(),
                candidateSkill.active(),
                candidateSkill.pageId(),
                candidateSkill.appliedCount(),
                candidateSkill.conversionCount(),
                candidateSkill.confidenceScore(),
                candidateSkill.createdAt(),
                candidateSkill.updatedAt(),
                "CONFLICT_DETECTED",
                "Phát hiện xung đột chiến thuật với kỹ năng hiện có: " + (existingConflictingSkill != null ? existingConflictingSkill.name() : ""),
                true,
                existingConflictingSkill,
                conflictReason,
                recommendedAction
        );
    }

    public static AgentSkillDto fromEntity(AgentSkillEntity e) {
        if (e == null) {
            return null;
        }
        double conf = e.getConfidenceScore() <= 0.0 ? 0.90 : e.getConfidenceScore();
        return new AgentSkillDto(
                e.getId(),
                e.getCode(),
                e.getName(),
                e.getCategory(),
                e.getDescription(),
                e.getTriggerSignals(),
                e.getTacticalInstructions(),
                e.getGoodExample(),
                e.getAntiPattern(),
                e.getPriority(),
                e.isActive(),
                e.getPageId() != null ? e.getPageId().toString() : null,
                e.getAppliedCount(),
                e.getConversionCount(),
                conf,
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }

    public record SkillOutputContract(
            @JsonProperty("reply_max_bubbles") int replyMaxBubbles,
            @JsonProperty("reply_max_sentences") int replyMaxSentences,
            @JsonProperty("reply_max_words") int replyMaxWords,
            @JsonProperty("max_questions") int maxQuestions,
            @JsonProperty("requires_human") boolean requiresHuman,
            @JsonProperty("contains_price") boolean containsPrice,
            @JsonProperty("min_delay_after_price_ms") int minDelayAfterPriceMs
    ) {
    }

    @JsonProperty("output_contract")
    public SkillOutputContract resolvedOutputContract() {
        String c = code != null ? code.strip().toLowerCase(java.util.Locale.ROOT) : "";
        return switch (c) {
            case "greeting_warm_intro" -> new SkillOutputContract(2, 2, 45, 1, false, false, 0);
            case "discovery_ask_one_need" -> new SkillOutputContract(2, 2, 45, 1, false, false, 0);
            case "price_clarify_service_duration" -> new SkillOutputContract(2, 2, 45, 1, false, false, 0);
            case "price_answer_from_docs" -> new SkillOutputContract(3, 2, 45, 1, false, true, 15000);
            case "price_objection_soft_anchor" -> new SkillOutputContract(2, 2, 45, 1, false, false, 0);
            case "booking_soft_confirm" -> new SkillOutputContract(2, 2, 45, 1, false, false, 0);
            case "upsell_one_addon" -> new SkillOutputContract(2, 2, 45, 1, false, false, 0);
            case "safety_handoff_high_risk", "complaint_immediate_handoff" -> new SkillOutputContract(1, 2, 45, 0, true, false, 0);
            default -> new SkillOutputContract(3, 2, 45, 1, false, false, 15000);
        };
    }

    @JsonProperty("fallback_reply")
    public String defaultFallbackReply() {
        return resolvedFallbackReply("mình");
    }

    public String resolvedFallbackReply(String honorific) {
        String h = (honorific != null && !honorific.isBlank()) ? honorific.strip() : "mình";
        String capH = Character.toUpperCase(h.charAt(0)) + h.substring(1);
        String c = code != null ? code.strip().toLowerCase(java.util.Locale.ROOT) : "";
        return switch (c) {
            case "greeting_warm_intro" ->
                    "Dạ chào " + h + ", rất vui được hỗ trợ " + h + " hôm nay ạ.\n\n"
                    + "Hôm nay " + h + " đang quan tâm nhóm dịch vụ hay sản phẩm nào để em tư vấn chi tiết cho " + h + " nha?";
            case "discovery_ask_one_need" ->
                    "Dạ với tình trạng hiện tại của " + h + ", " + h + " đang ưu tiên phương án nhẹ nhàng hay chuyên sâu để em gợi ý gói phù hợp ạ?";
            case "price_clarify_service_duration" ->
                    "Dạ bên em có vài mức giá theo từng gói dịch vụ và thời lượng ạ.\n\n"
                    + capH + " đang quan tâm gói cơ bản hay gói chuyên sâu ạ?";
            case "price_answer_from_docs" ->
                    "Dạ gói dịch vụ tiêu chuẩn bên em đang áp dụng mức giá niêm yết theo bảng giá hiện tại ạ.\n\n"
                    + "Bên em luôn ưu tiên điều chỉnh theo cảm giác của " + h + " trong suốt quá trình trải nghiệm ạ. "
                    + capH + " muốn chọn gói phù hợp với thời gian nào để em chuẩn bị sẵn cho " + h + " nhé.";
            case "price_objection_soft_anchor" ->
                    "Dạ em hiểu " + h + " đang cân nhắc về chi phí ạ. Bên em tập trung không gian riêng tư, dụng cụ vệ sinh và kỹ thuật viên hỏi cảm giác của " + h + " trong lúc làm.\n\n"
                    + "Nếu " + h + " muốn thử nhẹ nhàng trước, em có thể gợi ý gói ngắn vừa ngân sách hơn cho " + h + " nha?";
            case "booking_soft_confirm" ->
                    "Dạ em ghi nhận nhu cầu đặt lịch của " + h + " ạ.\n\n"
                    + "Em kiểm tra khung giờ và chuẩn bị lịch phù hợp, rồi phản hồi " + h + " sớm nhất nhé.";
            case "upsell_one_addon" ->
                    "Dạ em ghi nhận gói dịch vụ cho " + h + " nha. Nhiều khách thường kết hợp thêm gói chăm sóc bổ trợ 20 phút cho thư giãn hơn.\n\n"
                    + capH + " muốn giữ nguyên gói chính hay kết hợp thêm để em chuẩn bị lịch cho " + h + " ạ?";
            case "safety_handoff_high_risk" ->
                    "Dạ trường hợp này cần chuyên viên kiểm tra trực tiếp để an toàn và chính xác cho " + h + " ạ. Em chuyển người hỗ trợ " + h + " ngay nhé.";
            case "complaint_immediate_handoff" ->
                    "Dạ em rất tiếc vì trải nghiệm chưa làm " + h + " hài lòng ạ. Em chuyển quản lý phụ trách kiểm tra và phản hồi " + h + " sớm nhất nhé.";
            default -> (goodExample != null && !goodExample.isBlank())
                    ? goodExample
                    : "Dạ em ghi nhận thông tin của " + h + " ạ. Em sẽ hỗ trợ " + h + " tiếp nhé.";
        };
    }
}
