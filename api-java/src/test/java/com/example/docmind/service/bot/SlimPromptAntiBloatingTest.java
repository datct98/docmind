package com.example.docmind.service.bot;

import com.docmind.guardrail.GuardrailPipeline;
import com.example.docmind.dto.LearnedRuleDto;
import com.example.docmind.dto.bot.AgentSkillDto;
import com.example.docmind.dto.bot.CustomerInsightDto;
import com.example.docmind.dto.bot.FanpageBotRequest.ConversationTurn;
import com.example.docmind.dto.harness.HarnessOptimizeRequest;
import com.example.docmind.dto.harness.HarnessOptimizeResponse;
import com.example.docmind.entity.HarnessDimension;
import com.example.docmind.entity.HarnessRunEntity;
import com.example.docmind.entity.LearnedRuleEntity;
import com.example.docmind.repository.HarnessRunRepository;
import com.example.docmind.repository.LearnedRuleRepository;
import com.example.docmind.repository.RetrievedChunk;
import com.example.docmind.service.embedding.EmbeddingClient;
import com.example.docmind.service.harness.HarnessOptimizerService;
import com.example.docmind.service.retrieval.PromptBuilder;
import com.example.docmind.service.retrieval.ReciprocalRankFusion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SlimPromptAntiBloatingTest {

    private static ReciprocalRankFusion.FusedChunk sampleChunk(List<String> headingPath, String text) {
        RetrievedChunk rc = new RetrievedChunk(
                UUID.randomUUID(),
                UUID.randomUUID(),
                headingPath,
                List.of(UUID.randomUUID()),
                1,
                new double[]{0, 0, 100, 100},
                text,
                0.92
        );
        return new ReciprocalRankFusion.FusedChunk(rc, 0.032);
    }

    @Test
    @DisplayName("1. Prompt length is slim (< 3,200 chars total) even with full Insight, 3 verbose Skills, Rules, Chunks, and History")
    void promptLengthRemainsSlimUnderFullLoad() {
        PromptBuilder.PagePersona persona = new PromptBuilder.PagePersona(
                "Thẩm mỹ viện Nàng Sen",
                "Spa & Thẩm mỹ Y khoa",
                "Khách hàng quan tâm chăm sóc da",
                "Tư vấn nhẹ nhàng, ấm áp, chuyên nghiệp, gọi khách là Chị, xưng em."
        );

        CustomerInsightDto insight = new CustomerInsightDto(
                "ANALYTICAL_SKEPTIC",
                "Khách kỹ tính & Sợ rủi ro",
                65,
                "WARM",
                "RISING",
                List.of("Mụn viêm 2 bên má", "Sợ đau và sợ bị ép mua gói"),
                List.of("Ngại chi phí phát sinh"),
                "Đồng cảm sâu sắc với nỗi lo của khách, đưa bảo chứng kiểm tra thực tế miễn phí 0đ ràng buộc trước khi quyết định. ".repeat(3)
        );

        String longInstruction = "Khi khách hàng hỏi giá hoặc lo lắng về hiệu quả, tuyệt đối không xả sạch bảng giá 4-5 dịch vụ cùng lúc. "
                + "Hãy neo giá trị bằng quy trình chuẩn và chỉ nêu đúng 1 lợi ích cốt lõi rồi đặt câu hỏi lựa chọn A/B ngắn gọn để giữ nhịp hội thoại tự nhiên.";
        List<AgentSkillDto> activeSkills = List.of(
                new AgentSkillDto(UUID.randomUUID(), "SKILL_1", "Chống xả sạch thông tin", "SALES_STRATEGY",
                        "Mô tả 1", "giá", longInstruction, "Ví dụ tốt", "Cấm kỵ", 100, true, null, 10, 5, 0.95, Instant.now(), Instant.now()),
                new AgentSkillDto(UUID.randomUUID(), "SKILL_2", "Xóa bỏ rào cản rủi ro", "PSYCHOLOGY",
                        "Mô tả 2", "sợ đau", longInstruction, "Ví dụ tốt", "Cấm kỵ", 95, true, null, 8, 4, 0.92, Instant.now(), Instant.now()),
                new AgentSkillDto(UUID.randomUUID(), "SKILL_3", "Chốt mềm câu hỏi A/B", "COMMUNICATION",
                        "Mô tả 3", "đặt lịch", longInstruction, "Ví dụ tốt", "Cấm kỵ", 90, true, null, 12, 6, 0.94, Instant.now(), Instant.now())
        );

        List<LearnedRuleDto> rules = List.of(
                new LearnedRuleDto(
                        UUID.randomUUID(),
                        "PSYCHOLOGICAL_PERSUASION",
                        "Khi khách hỏi làm có đau không",
                        "Thiếu cam kết êm dịu",
                        "Khẳng định quy trình êm dịu không nghỉ dưỡng và 0đ ràng buộc.",
                        List.of("đau", "ràng buộc"),
                        true,
                        Instant.now(),
                        Instant.now()
                )
        );

        List<ReciprocalRankFusion.FusedChunk> chunks = List.of(
                sampleChunk(List.of("Bảng giá dịch vụ"),
                        "Liệu trình Trị Mụn Chuẩn Y Khoa: 1.500.000đ/buổi (60 phút), tặng tuýp chấm mụn kháng viêm, miễn phí Bác sĩ soi da AI 3D.")
        );

        List<ConversationTurn> history = List.of(
                new ConversationTurn("user", "Mình muốn trị mụn viêm"),
                new ConversationTurn("assistant", "Dạ liệu trình Trị Mụn Chuẩn Y Khoa bên em là 1.500.000đ/buổi ạ.")
        );

        String prompt = FanpageBotPromptBuilder.build(
                "Tư vấn thêm, tôi là nam, làm có đau hay ràng buộc gì không?",
                "Khách hàng",
                history,
                chunks,
                rules,
                persona,
                FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION,
                "Anh",
                insight,
                activeSkills
        );

        // Before anti-bloating refactor, this prompt was ~6,950 chars. Now it must be < 3,200 chars!
        assertThat(prompt.length()).isLessThan(3200);

        // Verify ACTIVE_SKILLS_MAX_CHARS budget is strictly respected
        int skillHeaderIdx = prompt.indexOf("# BỘ KỸ NĂNG AGENT ĐANG KÍCH HOẠT\n");
        int nextSectionIdx = prompt.indexOf("# LƯU Ý NGHIỆP VỤ QUAN TRỌNG", skillHeaderIdx);
        assertThat(skillHeaderIdx).isGreaterThanOrEqualTo(0);
        assertThat(nextSectionIdx).isGreaterThan(skillHeaderIdx);
        String skillsSectionBody = prompt.substring(
                skillHeaderIdx + "# BỘ KỸ NĂNG AGENT ĐANG KÍCH HOẠT\n".length(),
                nextSectionIdx
        ).strip();
        assertThat(skillsSectionBody.length()).isLessThanOrEqualTo(FanpageBotPromptBuilder.ACTIVE_SKILLS_MAX_CHARS);
    }

    @Test
    @DisplayName("2. Prompt builder is domain-agnostic and contains zero hardcoded Spa prices/terms for non-Spa pages")
    void promptBuilderContainsZeroHardcodedSpaTermsForNonSpaPage() {
        PromptBuilder.PagePersona autoShowroomPersona = new PromptBuilder.PagePersona(
                "Showroom Ô Tô Minh Phát",
                "Ô tô & Xe máy",
                "Khách mua xe trả góp",
                "Chuyên nghiệp, nhiệt tình, hỗ trợ duyệt hồ sơ nhanh."
        );

        String prompt = FanpageBotPromptBuilder.build(
                "giá sao shop",
                "Anh Nam",
                List.of(),
                List.of(sampleChunk(List.of("Bảng giá xe"),
                        "Honda Vision bản Cao cấp giá khởi điểm từ 32.000.000đ, miễn phí 100% kiểm tra điểm tín dụng và lái thử tại Showroom.")),
                List.of(),
                autoShowroomPersona,
                FanpageBotPromptBuilder.SalesStage.DISCOVERY,
                "Anh",
                null,
                List.of()
        );

        assertThat(prompt)
                .doesNotContain("650.000đ")
                .doesNotContain("soi da AI 3D")
                .doesNotContain("nặn mụn 200k")
                .doesNotContain("Bác sĩ Da liễu")
                .doesNotContain("Trị Mụn Chuẩn Y Khoa");
    }

    @Test
    @DisplayName("3. HarnessOptimizerService deduplicates and compacts overlapping rules instead of inserting duplicates")
    void harnessOptimizerCompactsOverlappingRulesInsteadOfBloatingDatabase() {
        HarnessRunRepository runRepository = mock(HarnessRunRepository.class);
        LearnedRuleRepository ruleRepository = mock(LearnedRuleRepository.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.embed(any())).thenReturn(new float[]{0.1f, 0.2f, 0.3f});

        List<LearnedRuleEntity> dbRules = new ArrayList<>();
        when(ruleRepository.findByActiveTrue()).thenAnswer(inv -> new ArrayList<>(dbRules));
        when(ruleRepository.save(any(LearnedRuleEntity.class))).thenAnswer(inv -> {
            LearnedRuleEntity saved = inv.getArgument(0);
            dbRules.removeIf(r -> r.getId().equals(saved.getId()));
            dbRules.add(saved);
            return saved;
        });
        when(runRepository.save(any(HarnessRunEntity.class))).thenAnswer(inv -> {
            HarnessRunEntity run = inv.getArgument(0);
            if (run.getId() == null) {
                run.setId(UUID.randomUUID().toString());
            }
            return run;
        });

        HarnessOptimizerService optimizer = new HarnessOptimizerService(
                runRepository,
                ruleRepository,
                new GuardrailPipeline(),
                embeddingClient,
                null
        );

        List<String> history = List.of(
                "user: 12h30 có đươc k",
                "assistant: Dạ 12h30 trưa được ạ.",
                "user: 09836563213",
                "assistant: Dạ em đã lưu số 09836563213.",
                "user: 12h30 mai nhé",
                "assistant: Dạ bên em giữ lịch 12h30 mai."
        );

        HarnessOptimizeRequest req1 = new HarnessOptimizeRequest(
                null,
                "12h30 là buổi trưa rồi còn hỏi sáng hay chiều, không lẽ đêm à",
                "Dạ em xin lỗi Anh ạ.\n\nAnh cho em xin khung giờ tiện nhất (Sáng hay Chiều) để bên em giữ lịch ưu tiên trước cho mình ạ?",
                null,
                history,
                -15,
                List.of(),
                "MANUAL_1CLICK",
                null,
                42L
        );

        HarnessOptimizeResponse resp1 = optimizer.optimize(req1);
        assertThat(resp1.verdict()).isEqualTo("ADOPTED");
        assertThat(dbRules).hasSize(1);

        // Second optimization on the same scenario must update/compact the existing rule, NOT add a 2nd row
        HarnessOptimizeResponse resp2 = optimizer.optimize(req1);
        assertThat(resp2.verdict()).isEqualTo("ADOPTED");
        assertThat(dbRules).hasSize(1);
    }
}
