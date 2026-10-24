package com.example.docmind.config;

import com.example.docmind.service.llm.GeminiLlmClient;
import com.example.docmind.service.llm.LlmClient;
import com.example.docmind.service.llm.OllamaLlmClient;
import com.example.docmind.service.llm.OpenAiCompatibleLlmClient;
import com.example.docmind.service.llm.ResilientFallbackLlmClient;
import com.example.docmind.service.llm.StubLlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

/**
 * Cấu hình khởi tạo LlmClient động theo provider (R4 - Unified Multi-Provider).
 * Tự động chọn client phù hợp (Ollama, DeepSeek, Grok, OpenAI, Gemini, OpenRouter, Custom, Stub)
 * dựa trên giá trị cấu hình {@code llm.provider}.
 * Bảo đảm tương thích ngược với {@code gemini.api.keys} và {@code ollama.enabled} khi {@code llm.provider} chưa thiết lập.
 */
@Configuration
public class LlmConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LlmConfiguration.class);

    private static final Set<String> OPENAI_COMPATIBLE_PROVIDERS = Set.of(
            "deepseek", "grok", "openai", "openai_compatible", "gemini", "anthropic", "qwen", "openrouter", "custom"
    );

    /**
     * Hạ cờ primary trên các bean LLM quét từ ComponentScan (OllamaLlmClient, GeminiLlmClient),
     * để bean {@code llmClient} do LlmConfiguration tạo ra luôn là @Primary duy nhất trong ứng dụng.
     */
    @Bean
    public static BeanDefinitionRegistryPostProcessor llmBeanDemoter() {
        return registry -> {
            if (registry.containsBeanDefinition("ollamaLlmClient")) {
                registry.getBeanDefinition("ollamaLlmClient").setPrimary(false);
            }
            if (registry.containsBeanDefinition("geminiLlmClient")) {
                registry.getBeanDefinition("geminiLlmClient").setPrimary(false);
            }
        };
    }

    @Bean
    @Primary
    public LlmClient llmClient(LlmProperties llmProps,
                               OllamaProperties ollamaProps,
                               GeminiProperties geminiProps,
                               ObjectMapper objectMapper) {
        LlmClient primary = buildSingleClient(llmProps, ollamaProps, geminiProps, objectMapper);
        if (llmProps != null && llmProps.hasFallbackProvider()) {
            LlmProperties fallbackProps = llmProps.toFallbackProperties();
            OllamaProperties fallbackOllamaProps = new OllamaProperties();
            if (ollamaProps != null) {
                fallbackOllamaProps.setEnabled(ollamaProps.isEnabled());
                fallbackOllamaProps.setBaseUrl(ollamaProps.getBaseUrl());
                fallbackOllamaProps.setModel(ollamaProps.getModel());
                fallbackOllamaProps.setFallbackModel(ollamaProps.getFallbackModel());
                fallbackOllamaProps.setTemperature(ollamaProps.getTemperature());
                fallbackOllamaProps.setTopP(ollamaProps.getTopP());
                fallbackOllamaProps.setRepeatPenalty(ollamaProps.getRepeatPenalty());
                fallbackOllamaProps.setNumCtx(ollamaProps.getNumCtx());
                fallbackOllamaProps.setNumPredict(ollamaProps.getNumPredict());
                fallbackOllamaProps.setReadTimeoutMs(ollamaProps.getReadTimeoutMs());
            }
            LlmClient fallback = buildSingleClient(fallbackProps, fallbackOllamaProps, geminiProps, objectMapper);
            log.info("[LLM] Enabled ResilientFallbackLlmClient: primary={} -> fallback={}",
                    primary.name(), fallback.name());
            return new ResilientFallbackLlmClient(primary, fallback);
        }
        return primary;
    }

    private LlmClient buildSingleClient(LlmProperties llmProps,
                                        OllamaProperties ollamaProps,
                                        GeminiProperties geminiProps,
                                        ObjectMapper objectMapper) {
        String rawProvider = llmProps.getProvider();
        log.info("[LLM] Đang khởi tạo LlmClient (configured provider={})", rawProvider);

        // Trường hợp không cấu hình llm.provider (backward-compatible legacy fallback)
        if (rawProvider == null || rawProvider.isBlank()) {
            if (geminiProps != null && geminiProps.hasKey()) {
                log.info("[LLM] Active LlmClient: provider=gemini-legacy, model={}, baseUrl={}",
                        geminiProps.getModel(), geminiProps.getBaseUrl());
                return new GeminiLlmClient(geminiProps);
            }
            if (ollamaProps != null && ollamaProps.isEnabled()) {
                log.info("[LLM] Active LlmClient: provider=ollama, model={}, baseUrl={}",
                        ollamaProps.getModel(), ollamaProps.getBaseUrl());
                return new OllamaLlmClient(ollamaProps);
            }
            log.info("[LLM] Active LlmClient: provider=stub, model=stub-llm, baseUrl=none");
            return new StubLlmClient();
        }

        String provider = llmProps.getNormalizedProvider();

        // 1. Chế độ Stub (dùng trong test hoặc offline)
        if ("stub".equals(provider)) {
            log.info("[LLM] Active LlmClient: provider=stub, model=stub-llm, baseUrl=none");
            return new StubLlmClient();
        }

        // 2. Chế độ OpenAI-compatible (DeepSeek, Grok, OpenAI, OpenAI-Compatible, Gemini, Anthropic, Qwen, OpenRouter, Custom)
        if (OPENAI_COMPATIBLE_PROVIDERS.contains(provider)) {
            String resolvedModel = llmProps.resolveModel();
            String resolvedBaseUrl = llmProps.resolveBaseUrl();
            String keyStatus = maskApiKey(llmProps.getApiKey());
            log.info("[LLM] Active LlmClient: provider={}, model={}, baseUrl={}, apiKey={}",
                    provider, resolvedModel, resolvedBaseUrl, keyStatus);
            return new OpenAiCompatibleLlmClient(llmProps, objectMapper);
        }

        // 3. Chế độ Ollama local
        if ("ollama".equals(provider)) {
            if (llmProps.getBaseUrl() != null && !llmProps.getBaseUrl().isBlank()) {
                ollamaProps.setBaseUrl(llmProps.getBaseUrl().trim());
            }
            if (llmProps.getModel() != null && !llmProps.getModel().isBlank()) {
                ollamaProps.setModel(llmProps.getModel().trim());
            }
            if (llmProps.getTemperature() >= 0) {
                ollamaProps.setTemperature(llmProps.getTemperature());
            }
            if (llmProps.getTopP() != null && llmProps.getTopP() > 0) {
                ollamaProps.setTopP(llmProps.getTopP());
            }
            log.info("[LLM] Active LlmClient: provider=ollama, model={}, fallbackModel={}, baseUrl={}, temp={}, topP={}, repeatPenalty={}, numCtx={}, numPredict={}",
                    ollamaProps.getModel(), ollamaProps.getFallbackModel(), ollamaProps.getBaseUrl(),
                    ollamaProps.getTemperature(), ollamaProps.getTopP(), ollamaProps.getRepeatPenalty(),
                    ollamaProps.getNumCtx(), ollamaProps.getNumPredict());
            return new OllamaLlmClient(ollamaProps);
        }

        // 4. Nếu provider lạ nhưng có cấu hình Base URL, đối xử như Custom OpenAI-compatible
        if (llmProps.getBaseUrl() != null && !llmProps.getBaseUrl().isBlank()) {
            log.info("[LLM] Active LlmClient (custom): provider={}, model={}, baseUrl={}, apiKey={}",
                    provider, llmProps.resolveModel(), llmProps.resolveBaseUrl(), maskApiKey(llmProps.getApiKey()));
            return new OpenAiCompatibleLlmClient(llmProps, objectMapper);
        }

        log.warn("[LLM] Không nhận diện được provider '{}', chuyển sang StubLlmClient an toàn.", provider);
        return new StubLlmClient();
    }

    private static String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return "EMPTY (WARNING: missing API key!)";
        }
        String trimmed = apiKey.trim();
        if (trimmed.length() <= 6) {
            return "***";
        }
        return trimmed.substring(0, 4) + "..." + trimmed.substring(trimmed.length() - 4);
    }
}
