package com.example.docmind.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Điều kiện kích hoạt Gemini: CHỈ true khi gemini.api.keys có ít nhất 1 giá trị
 * không rỗng. @ConditionalOnProperty không phân biệt "trống" vs "vắng mặt" (keys=
 * vẫn coi là present), nên cần condition riêng đọc & trim giá trị.
 */
public class GeminiEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String provider = context.getEnvironment().getProperty("llm.provider");
        if (provider != null && !provider.isBlank()) {
            return false;
        }
        return hasKey(context);
    }

    static boolean hasKey(ConditionContext context) {
        String raw = context.getEnvironment().getProperty("gemini.api.keys");
        if (raw == null || raw.isBlank()) {
            return false;
        }
        // CSV "k1,k2" — có ít nhất 1 phần tử không rỗng?
        for (String k : raw.split(",")) {
            if (k != null && !k.isBlank()) {
                return true;
            }
        }
        return false;
    }

    /** Negation: active khi KHÔNG có Gemini key và KHÔNG bật Ollama -> StubLlmClient dùng cái này. */
    public static class Disabled implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String provider = context.getEnvironment().getProperty("llm.provider");
            if (provider != null && !provider.isBlank()) {
                return false;
            }
            return !hasKey(context) && !OllamaEnabledCondition.isOllamaEnabled(context);
        }
    }
}
