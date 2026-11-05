package com.example.docmind.service.bot;

import com.example.docmind.dto.bot.AgentSkillDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SBR-01 Wave 1 — CP3.3: {@code good_example} Neutralization & Prompt Injection Toggle Verification.
 */
class Sbr01Cp3GoodExampleToggleTest {

    private static final String NANG_SEN_PAGE_ID = "2850a8ef-ca4a-40eb-87fa-4f808640efee";

    @AfterEach
    void tearDown() {
        CleanAgentCatalogAndPromptBuilder.setCp3SkillGoodExampleEnabled(null);
    }

    @Test
    @DisplayName("CP3.3: Config A (enabled=true) đưa good_example đã trung hòa vào prompt; Config B (enabled=false) loại bỏ hoàn toàn good_example khỏi prompt")
    void togglesGoodExampleInjectionBetweenConfigAAndConfigB() {
        Instant now = Instant.now();
        String neutralizedGoodExample = "Dạ cơ thể đang căng mỏi, uể oải kéo dài chắc mình khó chịu lắm ạ. Để em gợi ý đúng liệu trình thư giãn vừa vặn nhất, hiện mình đang muốn tập trung chăm sóc vùng nào nhất hay muốn thả lỏng toàn thân ạ?";
        AgentSkillDto discoverySkill = new AgentSkillDto(
                UUID.fromString("24000001-0000-4000-8000-000000000001"),
                "discovery_empathy_and_scope",
                "Đồng cảm giác quan & Khám phá vùng mỏi ưu tiên (Nàng Sen)",
                "PSYCHOLOGY",
                "Lắng nghe, vỗ về cảm giác căng mỏi cơ thể hoặc khó ngủ.",
                "SHARING_CONDITION_OR_CONTEXT",
                "[Tình huống tâm lý khách] Khách vừa chia sẻ cơ thể căng mỏi -> [Nghệ thuật dẫn dắt & Ranh giới sự thật] Ghi nhận cảm giác mỏi mệt bằng ngôn từ dịu dàng -> [Các hướng diễn đạt gợi cảm xúc theo ngữ cảnh] \"Cơ thể đang căng mỏi, uể oải kéo dài chắc mình khó chịu lắm ạ\"",
                neutralizedGoodExample,
                "Xổ ngay bảng giá khô khan khi chưa biết vùng mỏi ưu tiên của khách.",
                98,
                true,
                NANG_SEN_PAGE_ID,
                now,
                now
        );

        assertThat(discoverySkill.goodExample()).doesNotContain("đi tập nặng về");
        assertThat(discoverySkill.tacticalInstructions()).doesNotContain("tập nặng");

        var threeStream = new AdaptiveConversationIntelligence.ThreeStreamTurnInput(
                "Dạo này mình hay mệt mỏi uể oải quá",
                List.of("Dạo này mình hay mệt mỏi uể oải quá"),
                "dạo này mình hay mệt mỏi uể oải quá",
                List.of()
        );
        var draft2 = AdaptiveConversationIntelligence.CustomerUnderstandingDraft2Snapshot.initial();
        var snap = AdaptiveConversationIntelligence.buildTurnContextSnapshot(
                "T1", "Dạo này mình hay mệt mỏi uể oải quá", "mình", false, 0,
                List.of(), draft2, ConversationState.StructuredBookingState.none()
        );

        // Config A: good_example enabled (default true)
        CleanAgentCatalogAndPromptBuilder.setCp3SkillGoodExampleEnabled(true);
        String promptConfigA = CleanAgentCatalogAndPromptBuilder.buildAciSinglePassPrompt(
                null, "mình", null, "mình", threeStream,
                List.of(), List.of(), CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                List.of(), null,
                List.of(), ConversationState.StructuredBookingState.none(),
                null,
                null,
                List.of(), null, false, List.of(), List.of(),
                List.of(discoverySkill), List.of(), draft2, snap.plan()
        );
        assertThat(promptConfigA)
                .contains("discovery_empathy_and_scope")
                .contains("Mẫu tham khảo:")
                .contains("Dạ cơ thể đang căng mỏi, uể oải kéo dài")
                .doesNotContain("đi tập nặng về");

        // Config B: good_example disabled (false)
        CleanAgentCatalogAndPromptBuilder.setCp3SkillGoodExampleEnabled(false);
        String promptConfigB = CleanAgentCatalogAndPromptBuilder.buildAciSinglePassPrompt(
                null, "mình", null, "mình", threeStream,
                List.of(), List.of(), CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                List.of(), null,
                List.of(), ConversationState.StructuredBookingState.none(),
                null,
                null,
                List.of(), null, false, List.of(), List.of(),
                List.of(discoverySkill), List.of(), draft2, snap.plan()
        );
        assertThat(promptConfigB)
                .contains("discovery_empathy_and_scope")
                .doesNotContain("Mẫu tham khảo:")
                .doesNotContain("Dạ cơ thể đang căng mỏi, uể oải kéo dài")
                .doesNotContain("đi tập nặng về");
    }
}
