package com.example.docmind.service.bot;

import com.example.docmind.dto.bot.AgentSkillDto;
import com.example.docmind.dto.bot.CustomerInsightDto;
import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.dto.bot.FanpageBotResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerInsightEngineTest {

    private CustomerInsightEngine engine;

    @BeforeEach
    void setUp() {
        engine = new CustomerInsightEngine();
    }

    @Test
    void detectsSkepticalChronicArchetypeAndExtractsPainsAndHiddenConcerns() {
        CustomerInsightDto insight = engine.analyzeInsight(
                "Chị bị nám mãn tính 5 năm rồi, chữa nhiều nơi không khỏi, sợ tái phát lắm em ơi",
                List.of(),
                "Chị",
                FanpageBotPromptBuilder.SalesStage.DISCOVERY,
                null
        );

        assertThat(insight.buyerArchetype()).isEqualTo("SKEPTICAL_CHRONIC");
        assertThat(insight.archetypeLabel()).contains("Đa nghi");
        assertThat(insight.honorific()).isEqualTo("Chị");
        assertThat(insight.extractedPains())
                .contains(
                        "Tình trạng kéo dài lâu năm (mụn/nám mãn tính cần đánh giá chuyên sâu)",
                        "Quan tâm xử lý sắc tố nám / tàn nhang"
                );
        assertThat(insight.hiddenConcerns())
                .contains(
                        "Tâm lý đa nghi do bị lâu năm; sợ quảng cáo quá đà hoặc mua gói mà không cải thiện thực sự",
                        "Lo lắng rủi ro đau rát, thời gian nghỉ dưỡng hoặc nguy cơ tái phát sau khi làm"
                );
        assertThat(insight.recommendedStrategy()).contains("Đồng cảm sâu sắc");
    }

    @Test
    void detectsDiscreetDecisiveArchetypeForMaleVipCustomer() {
        CustomerInsightDto insight = engine.analyzeInsight(
                "Anh cần phòng VIP riêng tư kín đáo, làm nhanh không cần nghỉ dưỡng nhé",
                List.of(),
                "Anh",
                FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION,
                null
        );

        assertThat(insight.buyerArchetype()).isEqualTo("DISCREET_DECISIVE");
        assertThat(insight.honorific()).isEqualTo("Anh");
        assertThat(insight.extractedPains()).contains("Khách hàng Nam giới (cần tư vấn rõ tính phù hợp & sự kín đáo)");
        assertThat(insight.hiddenConcerns())
                .contains("E ngại môi trường Spa đông khách nữ, cần sự tôn trọng, nhanh gọn và phòng riêng kín đáo");
    }

    @Test
    void detectsPriceConsciousArchetypeWhenCustomerFocusesOnCost() {
        CustomerInsightDto insight = engine.analyzeInsight(
                "Giá bao nhiêu tiền vậy shop, có phát sinh phụ phí ẩn không?",
                List.of(),
                "Chị",
                FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION,
                null
        );

        assertThat(insight.buyerArchetype()).isEqualTo("PRICE_CONSCIOUS");
        assertThat(insight.hiddenConcerns()).contains("Sợ giá cao hơn mặt bằng chung, lo phát sinh chi phí ẩn hoặc văn hóa vòi tiền Tip");
        assertThat(insight.recommendedStrategy()).contains("Value Stacking");
    }

    @Test
    void detectsHotBuyingTemperatureAndReadyToBookArchetype() {
        FanpageBotResponse.ExtractedLead lead = new FanpageBotResponse.ExtractedLead(
                true,
                "0912345678",
                "Chiều mai 15h",
                "Khách đặt lịch"
        );

        CustomerInsightDto insight = engine.analyzeInsight(
                "Ok chốt cho chị đặt lịch chiều mai lúc 15h nhé, số của chị 0912345678",
                List.of(
                        new FanpageBotRequest.ConversationTurn("user", "Bên em có gói trị liệu cổ vai gáy không?"),
                        new FanpageBotRequest.ConversationTurn("assistant", "Dạ bên em có liệu trình Trị liệu Cổ Vai Gáy chuyên sâu ạ.")
                ),
                "Chị",
                FanpageBotPromptBuilder.SalesStage.SOFT_BOOKING_CLOSE,
                lead
        );

        assertThat(insight.buyerArchetype()).isEqualTo("READY_TO_BOOK");
        assertThat(insight.buyingTemperature()).isGreaterThanOrEqualTo(75);
        assertThat(insight.temperatureLabel()).isEqualTo("HOT");
        assertThat(insight.extractedPains())
                .contains(
                        "Nhu cầu thư giãn dưỡng sinh & giảm đau mỏi cổ vai gáy",
                        "Đã gửi SĐT liên hệ (0912345678) cần chuyên viên tư vấn thêm"
                );
    }

    @Test
    void accumulatesExtractedPainsAcrossConversationHistoryWithoutLosingEarlierSymptoms() {
        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Em ơi chị bị đau mỏi cổ vai gáy quá"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ em rất hiểu cảm giác mệt mỏi của Chị ạ."),
                new FanpageBotRequest.ConversationTurn("user", "Da chị dạo này còn bị sạm nám và sẹo rỗ nữa")
        );

        CustomerInsightDto insight = engine.analyzeInsight(
                "Giá gói kết hợp bên em sao nhỉ?",
                history,
                "Chị",
                FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION,
                null
        );

        assertThat(insight.extractedPains())
                .contains(
                        "Nhu cầu thư giãn dưỡng sinh & giảm đau mỏi cổ vai gáy",
                        "Quan tâm xử lý sắc tố nám / tàn nhang",
                        "Kèm sẹo rỗ tổn thương cấu trúc da (cần Bác sĩ soi da AI 3D trực tiếp)",
                        "Quan tâm bảng giá niêm yết, quyền lợi đi kèm & mức giảm Combo"
                );
    }

    @Test
    void selectsTopRelevantSkillsFromPool() {
        CustomerInsightDto insight = engine.analyzeInsight(
                "Chị trị nám lâu năm chữa nhiều nơi không khỏi, sợ tái phát lắm",
                List.of(),
                "Chị",
                FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION,
                null
        );

        List<AgentSkillDto> builtinSkills = CustomerInsightEngine.builtInDefaultSkills();
        List<AgentSkillDto> topSkills = engine.selectActiveSkills(
                insight,
                "Chị trị nám lâu năm chữa nhiều nơi không khỏi, sợ tái phát lắm",
                List.of(),
                FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION,
                builtinSkills
        );

        assertThat(topSkills).hasSize(3);
        assertThat(topSkills)
                .extracting(AgentSkillDto::code)
                .contains("PSYCH_CHRONIC_EMPATHY", "PSYCH_ZERO_RISK_REVERSAL");

        String pageId = UUID.randomUUID().toString();
        AgentSkillDto customPageSkill = new AgentSkillDto(
                UUID.randomUUID(),
                "CUSTOM_PAGE_VIP_CARE",
                "Chăm sóc đặc quyền Page",
                "PSYCHOLOGY",
                "Dành riêng cho khách lo tái phát tại Page",
                "tái phát, chữa nhiều nơi",
                "Luôn tặng kèm buổi soi da miễn phí",
                "Ví dụ tốt",
                "Lỗi cần tránh",
                100,
                true,
                pageId,
                Instant.now(),
                Instant.now()
        );

        List<AgentSkillDto> withCustom = new ArrayList<>(builtinSkills);
        withCustom.add(customPageSkill);

        List<AgentSkillDto> topWithCustom = engine.selectActiveSkills(
                insight,
                "Chị trị nám lâu năm chữa nhiều nơi không khỏi, sợ tái phát",
                List.of(),
                FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION,
                withCustom
        );

        assertThat(topWithCustom)
                .extracting(AgentSkillDto::code)
                .contains("CUSTOM_PAGE_VIP_CARE", "PSYCH_CHRONIC_EMPATHY");
        long psychCount = topWithCustom.stream()
                .filter(s -> "PSYCHOLOGY".equalsIgnoreCase(s.category()))
                .count();
        assertThat(psychCount).isLessThanOrEqualTo(2);
    }

    @Test
    void loadDotenvOverridesStalePropertiesAndSupportsSameFilePresetOverrideAndDockerLocalhost() throws Exception {
        java.nio.file.Path closerEnv = java.nio.file.Files.createTempFile("docmind-closer-", ".env");
        java.nio.file.Path parentEnv = java.nio.file.Files.createTempFile("docmind-parent-", ".env");
        try {
            java.nio.file.Files.writeString(closerEnv, """
                    # Default block at top of .env
                    LLM_PROVIDER=ollama
                    LLM_API_KEY="sk-initial-key"
                    OLLAMA_BASE_URL=http://localhost:11434
                    SingleQuoteEdge="

                    # Uncommented Preset block at bottom of same .env must override earlier lines in same file
                    export LLM_PROVIDER=openai-compatible # inline comment
                    LLM_API_KEY="sk-new-key-999"
                    LLM_BASE_URL=http://127.0.0.1:8000/v1
                    """);
            java.nio.file.Files.writeString(parentEnv, """
                    LLM_PROVIDER=gemini
                    LLM_API_KEY=sk-parent-should-not-override-closer-file
                    DOCMIND_FANPAGE_VERIFY_TOKEN=verify-from-parent
                    """);
            System.setProperty("LLM_API_KEY", "sk-old-stale-key");
            System.setProperty("llm.api-key", "sk-old-stale-key");

            com.example.docmind.DocmindApiApplication.loadDotenv(true, closerEnv, parentEnv);

            assertThat(System.getProperty("LLM_PROVIDER")).isEqualTo("openai-compatible");
            assertThat(System.getProperty("llm.provider")).isEqualTo("openai-compatible");
            assertThat(System.getProperty("LLM_API_KEY")).isEqualTo("sk-new-key-999");
            assertThat(System.getProperty("llm.api-key")).isEqualTo("sk-new-key-999");
            assertThat(System.getProperty("OLLAMA_BASE_URL")).isEqualTo("http://host.docker.internal:11434");
            assertThat(System.getProperty("ollama.base-url")).isEqualTo("http://host.docker.internal:11434");
            assertThat(System.getProperty("LLM_BASE_URL")).isEqualTo("http://host.docker.internal:8000/v1");
            assertThat(System.getProperty("llm.base-url")).isEqualTo("http://host.docker.internal:8000/v1");
            assertThat(System.getProperty("DOCMIND_FANPAGE_VERIFY_TOKEN")).isEqualTo("verify-from-parent");
            assertThat(System.getProperty("docmind.fanpage.verify-token")).isEqualTo("verify-from-parent");
        } finally {
            System.clearProperty("LLM_PROVIDER");
            System.clearProperty("llm.provider");
            System.clearProperty("LLM_API_KEY");
            System.clearProperty("llm.api-key");
            System.clearProperty("OLLAMA_BASE_URL");
            System.clearProperty("ollama.base-url");
            System.clearProperty("LLM_BASE_URL");
            System.clearProperty("llm.base-url");
            System.clearProperty("DOCMIND_FANPAGE_VERIFY_TOKEN");
            System.clearProperty("docmind.fanpage.verify-token");
            System.clearProperty("SingleQuoteEdge");
            java.nio.file.Files.deleteIfExists(closerEnv);
            java.nio.file.Files.deleteIfExists(parentEnv);
        }
    }
}
