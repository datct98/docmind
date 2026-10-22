package com.example.docmind.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Cấu hình "Lớp da Nhân cách" (Configurable Persona Skin) cho từng Page/Tenant.
 * Cho phép tùy biến tên Bot, đại từ tự xưng của Shop, đại từ mặc định gọi khách,
 * giọng văn, mức độ Emoji, từ cấm kỵ (forbidden_words) và từ khóa đặc thù ngành hàng (domain_keywords).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PagePersonaConfig(
        @JsonProperty("industry_code")
        String industryCode,
        @JsonProperty("bot_name")
        String botName,
        @JsonProperty("self_pronoun")
        String selfPronoun,
        @JsonProperty("default_customer_pronoun")
        String defaultCustomerPronoun,
        @JsonProperty("tone_style")
        String toneStyle,
        @JsonProperty("emoji_level")
        String emojiLevel,
        @JsonProperty("forbidden_words")
        List<String> forbiddenWords,
        @JsonProperty("domain_keywords")
        List<String> domainKeywords,
        @JsonProperty("closing_action")
        String closingAction,
        @JsonProperty("cta_template")
        String ctaTemplate
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public PagePersonaConfig(
            String industryCode,
            String botName,
            String selfPronoun,
            String defaultCustomerPronoun,
            String toneStyle,
            String emojiLevel,
            List<String> forbiddenWords,
            List<String> domainKeywords
    ) {
        this(industryCode, botName, selfPronoun, defaultCustomerPronoun,
                toneStyle, emojiLevel, forbiddenWords, domainKeywords, null, null);
    }

    public static PagePersonaConfig empty() {
        return new PagePersonaConfig(
                null, null, null, null, null, null, List.of(), List.of(), null, null
        );
    }

    public boolean hasContent() {
        return (industryCode != null && !industryCode.isBlank())
                || (botName != null && !botName.isBlank())
                || (selfPronoun != null && !selfPronoun.isBlank())
                || (defaultCustomerPronoun != null && !defaultCustomerPronoun.isBlank())
                || (toneStyle != null && !toneStyle.isBlank())
                || (emojiLevel != null && !emojiLevel.isBlank())
                || (forbiddenWords != null && !forbiddenWords.isEmpty())
                || (domainKeywords != null && !domainKeywords.isEmpty())
                || (closingAction != null && !closingAction.isBlank())
                || (ctaTemplate != null && !ctaTemplate.isBlank());
    }

    public String resolvedClosingAction() {
        if (closingAction != null && !closingAction.isBlank()) {
            return closingAction.strip();
        }
        return "tư vấn gói dịch vụ/sản phẩm phù hợp nhất";
    }

    public String resolvedCtaTemplate() {
        if (ctaTemplate != null && !ctaTemplate.isBlank()) {
            return ctaTemplate.strip();
        }
        return "";
    }

    public String resolvedSelfPronoun() {
        if (selfPronoun != null && !selfPronoun.isBlank()) {
            return selfPronoun.strip();
        }
        return "em";
    }

    public String resolvedDefaultCustomerPronoun() {
        if (defaultCustomerPronoun != null && !defaultCustomerPronoun.isBlank()) {
            return defaultCustomerPronoun.strip();
        }
        if ("BEAUTY_SPA".equalsIgnoreCase(industryCode) || "FASHION_APPAREL".equalsIgnoreCase(industryCode)) {
            return "Chị";
        }
        if ("COSMETICS_PHARMA".equalsIgnoreCase(industryCode)) {
            return "Bạn";
        }
        return "Anh/Chị";
    }

    public List<String> safeForbiddenWords() {
        return forbiddenWords != null ? forbiddenWords : Collections.emptyList();
    }

    public List<String> safeDomainKeywords() {
        return domainKeywords != null ? domainKeywords : Collections.emptyList();
    }

    public boolean matchesDomainKeyword(String text) {
        if (text == null || text.isBlank() || domainKeywords == null || domainKeywords.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String kw : domainKeywords) {
            if (kw != null && !kw.isBlank() && lower.contains(kw.strip().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    public static PagePersonaConfig fromJson(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.strip())) {
            return empty();
        }
        try {
            PagePersonaConfig parsed = MAPPER.readValue(json, PagePersonaConfig.class);
            return parsed != null ? parsed : empty();
        } catch (Exception e) {
            return empty();
        }
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            return "{}";
        }
    }
}
