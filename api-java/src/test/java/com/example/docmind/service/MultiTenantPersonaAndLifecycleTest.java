package com.example.docmind.service;

import com.example.docmind.config.LlmProperties;
import com.example.docmind.dto.ChunkMetadata;
import com.example.docmind.dto.IndustryPersonaTemplates;
import com.example.docmind.dto.PagePersonaConfig;
import com.example.docmind.entity.DocumentEntity;
import com.example.docmind.repository.RetrievedChunk;
import com.example.docmind.service.bot.FanpageBotPromptBuilder;
import com.example.docmind.service.bot.FanpageBotService;
import com.example.docmind.service.llm.LlmClient;
import com.example.docmind.service.llm.ResilientFallbackLlmClient;
import com.example.docmind.service.metadata.ChunkMetadataExtractor;
import com.example.docmind.service.retrieval.PromptBuilder;
import com.example.docmind.service.retrieval.ReciprocalRankFusion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MultiTenantPersonaAndLifecycleTest {

    @Test
    @DisplayName("Phase 1.1: IndustryPersonaTemplates provides all 6 industry presets with domain keywords and forbidden words")
    void industryTemplates_ProvidesSixCompletePresets() {
        List<IndustryPersonaTemplates.IndustryTemplateDto> presets = IndustryPersonaTemplates.allTemplates();
        assertThat(presets).hasSize(6);
        assertThat(presets).extracting(IndustryPersonaTemplates.IndustryTemplateDto::industryCode)
                .containsExactly(
                        "BEAUTY_SPA",
                        "COSMETICS_PHARMA",
                        "FASHION_APPAREL",
                        "HOME_FURNITURE",
                        "TECH_B2B",
                        "GENERAL_SERVICE"
                );

        PagePersonaConfig fashionCfg = IndustryPersonaTemplates.findByCode("FASHION_APPAREL").personaConfig();
        assertThat(fashionCfg.selfPronoun()).isEqualTo("mình / shop");
        assertThat(fashionCfg.defaultCustomerPronoun()).isEqualTo("Bạn");
        assertThat(fashionCfg.domainKeywords()).contains("size", "form dáng");
        assertThat(fashionCfg.forbiddenWords()).contains("hàng fake");
    }

    @Test
    @DisplayName("Phase 1.2: Multi-Tenant PagePersona injects custom selfPronoun, defaultCustomerPronoun, domainKeywords, and forbiddenWords")
    void multiTenantPersona_FashionAndTechDomainAdaptation() {
        PagePersonaConfig fashionConfig = IndustryPersonaTemplates.findByCode("FASHION_APPAREL").personaConfig();
        PromptBuilder.PagePersona fashionPersona = new PromptBuilder.PagePersona(
                "Atelier Chic",
                "Thời trang thiết kế",
                "Giới trẻ 20-32 tuổi",
                "Tư vấn phối đồ trẻ trung, năng động",
                fashionConfig
        );

        String honorific = FanpageBotPromptBuilder.resolveCustomerHonorific(
                "mẫu đầm lụa này còn size M không shop",
                List.of(),
                null,
                fashionPersona
        );
        assertThat(honorific).isEqualTo("Bạn");

        // Fashion product + price inquiry should NOT be treated as broad unspecified price inquiry
        boolean isBroad = FanpageBotPromptBuilder.isBroadUnspecifiedPriceInquiry(
                "áo blazer form dáng rộng size M giá bao nhiêu",
                List.of(),
                fashionPersona
        );
        assertThat(isBroad).isFalse();

        RetrievedChunk rc = new RetrievedChunk(
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of("Bộ sưu tập Thu Đông"),
                List.of(UUID.randomUUID()),
                1,
                new double[]{0, 0, 100, 100},
                "Áo Blazer lụa cao cấp đủ size S/M/L: 690.000 VNĐ.",
                0.95
        );
        String prompt = FanpageBotPromptBuilder.build(
                "áo blazer form dáng rộng size M giá bao nhiêu",
                null,
                List.of(),
                List.of(new ReciprocalRankFusion.FusedChunk(rc, 0.035)),
                List.of(),
                fashionPersona,
                FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION,
                honorific
        );

        assertThat(prompt).contains("Xưng 'mình / shop', gọi 'Bạn' 100%");
        assertThat(prompt).contains("- Từ cấm kỵ:");
        assertThat(prompt).contains("hàng fake");
    }

    @Test
    @DisplayName("Phase 1.3: FanpageBotService.sanitizeForbiddenWords replaces or removes forbidden claims")
    void sanitizeForbiddenWords_ReplacesMedicalAndCounterfeitClaims() {
        PagePersonaConfig pharmaConfig = IndustryPersonaTemplates.findByCode("COSMETICS_PHARMA").personaConfig();
        PromptBuilder.PagePersona pharmaPersona = new PromptBuilder.PagePersona(
                "Dr. Derma Lab",
                "Dược mỹ phẩm",
                "Khách hàng chăm sóc da",
                "Tư vấn khoa học",
                pharmaConfig
        );

        String raw = "Dạ serum B5 bên em giúp trị dứt điểm 100% cho Chị ạ!";
        String sanitized = FanpageBotService.sanitizeForbiddenWords(raw, pharmaPersona);

        assertThat(sanitized).doesNotContain("trị dứt điểm 100%");
        assertThat(sanitized).contains("hỗ trợ cải thiện hiệu quả");
    }

    @Test
    @DisplayName("Phase 2.1: DocumentEntity.isCurrentlyEffective enforces active flag and effectiveFrom/effectiveTo window")
    void documentLifecycle_IsCurrentlyEffective_EnforcesTimeWindowAndActiveToggle() {
        OffsetDateTime now = OffsetDateTime.now();

        DocumentEntity defaultDoc = DocumentEntity.builder()
                .id(UUID.randomUUID())
                .status("READY")
                .build();
        assertThat(defaultDoc.isCurrentlyEffective()).isTrue();

        DocumentEntity pausedDoc = DocumentEntity.builder()
                .id(UUID.randomUUID())
                .status("READY")
                .active(false)
                .build();
        assertThat(pausedDoc.isCurrentlyEffective()).isFalse();

        DocumentEntity expiredPromoDoc = DocumentEntity.builder()
                .id(UUID.randomUUID())
                .status("READY")
                .active(true)
                .effectiveFrom(now.minusDays(10))
                .effectiveTo(now.minusMinutes(5))
                .build();
        assertThat(expiredPromoDoc.isCurrentlyEffective()).isFalse();

        DocumentEntity futurePromoDoc = DocumentEntity.builder()
                .id(UUID.randomUUID())
                .status("READY")
                .active(true)
                .effectiveFrom(now.plusDays(1))
                .effectiveTo(now.plusDays(7))
                .build();
        assertThat(futurePromoDoc.isCurrentlyEffective()).isFalse();

        DocumentEntity activeWindowDoc = DocumentEntity.builder()
                .id(UUID.randomUUID())
                .status("READY")
                .active(true)
                .effectiveFrom(now.minusDays(1))
                .effectiveTo(now.plusDays(5))
                .build();
        assertThat(activeWindowDoc.isCurrentlyEffective()).isTrue();
    }

    @Test
    @DisplayName("Phase 2.2: ChunkMetadataExtractor extracts semanticTags (warranty_return, social_proof, spec_ingredients, scarcity_promo)")
    void chunkMetadataExtractor_ExtractsSemanticTags() {
        ChunkMetadata meta = ChunkMetadataExtractor.extract(
                List.of("Chính sách bảo hành & Ưu đãi tháng 10"),
                "Cam kết đổi trả miễn phí trong 7 ngày, hoàn tiền 100% nếu lỗi. "
                        + "Thành phần chứa Niacinamide 10%, đạt chứng nhận FDA và hơn 10.000 khách hàng đánh giá 5 sao. "
                        + "Flash sale giới hạn 20 suất duy nhất hôm nay."
        );

        assertThat(meta.semanticTags()).containsExactlyInAnyOrder(
                "warranty_return",
                "social_proof",
                "spec_ingredients",
                "scarcity_promo"
        );
    }

    @Test
    @DisplayName("Phase 3: ResilientFallbackLlmClient automatically switches to fallback when primary returns offline/error marker or throws")
    void resilientFallbackLlmClient_SwitchesToFallbackOnPrimaryFailure() {
        LlmClient healthyPrimary = new LlmClient() {
            @Override
            public String complete(String prompt) {
                return "Phản hồi từ Primary LLM";
            }

            @Override
            public String name() {
                return "ollama:qwen2.5:7b";
            }
        };

        LlmClient offlinePrimary = new LlmClient() {
            @Override
            public String complete(String prompt) {
                return "[ollama-offline] Ollama concurrency load shedding";
            }

            @Override
            public String name() {
                return "ollama:qwen2.5:7b";
            }
        };

        LlmClient throwingPrimary = new LlmClient() {
            @Override
            public String complete(String prompt) {
                throw new RuntimeException("Connection refused");
            }

            @Override
            public String name() {
                return "ollama:qwen2.5:7b";
            }
        };

        LlmClient cloudFallback = new LlmClient() {
            @Override
            public String complete(String prompt) {
                return "Dạ em chào Chị ạ! Đây là phản hồi dự phòng từ Cloud LLM.";
            }

            @Override
            public String name() {
                return "gemini:gemini-2.5-flash";
            }
        };

        ResilientFallbackLlmClient clientWhenPrimaryHealthy = new ResilientFallbackLlmClient(healthyPrimary, cloudFallback);
        assertThat(clientWhenPrimaryHealthy.complete("test")).isEqualTo("Phản hồi từ Primary LLM");
        assertThat(clientWhenPrimaryHealthy.name()).isEqualTo("ollama:qwen2.5:7b+fallback(gemini:gemini-2.5-flash)");

        ResilientFallbackLlmClient clientWhenPrimaryOffline = new ResilientFallbackLlmClient(offlinePrimary, cloudFallback);
        assertThat(clientWhenPrimaryOffline.complete("test"))
                .isEqualTo("Dạ em chào Chị ạ! Đây là phản hồi dự phòng từ Cloud LLM.");

        ResilientFallbackLlmClient clientWhenPrimaryThrows = new ResilientFallbackLlmClient(throwingPrimary, cloudFallback);
        assertThat(clientWhenPrimaryThrows.complete("test"))
                .isEqualTo("Dạ em chào Chị ạ! Đây là phản hồi dự phòng từ Cloud LLM.");

        LlmProperties props = new LlmProperties();
        props.setProvider("ollama");
        props.setFallbackProvider("gemini");
        props.setFallbackApiKey("test-key-123456");
        assertThat(props.hasFallbackProvider()).isTrue();
        LlmProperties fbProps = props.toFallbackProperties();
        assertThat(fbProps.getNormalizedProvider()).isEqualTo("gemini");
        assertThat(fbProps.resolveModel()).isEqualTo("gemini-2.5-flash");
    }
}
