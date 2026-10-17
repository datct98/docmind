package com.example.docmind.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Điều kiện kích hoạt {@code OllamaLlmClient}:
 * Kích hoạt khi KHÔNG có Gemini API key VÀ {@code ollama.enabled=true}.
 */
public class OllamaEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String provider = context.getEnvironment().getProperty("llm.provider");
        if (provider != null && !provider.isBlank()) {
            return false;
        }
        return !GeminiEnabledCondition.hasKey(context) && isOllamaEnabled(context);
    }

    public static boolean isOllamaEnabled(ConditionContext context) {
        String raw = context.getEnvironment().getProperty("ollama.enabled");
        return raw != null && "true".equalsIgnoreCase(raw.trim());
    }
}
