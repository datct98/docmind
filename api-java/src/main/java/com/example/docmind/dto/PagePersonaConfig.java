package com.example.docmind.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
        String ctaTemplate,
        @JsonProperty("opening_bubbles")
        List<String> openingBubbles,
        @JsonProperty("post_price_delay_ms")
        Integer postPriceDelayMs,
        @JsonProperty("preference_discovery_prompt")
        String preferenceDiscoveryPrompt,
        @JsonProperty("condition_discovery_prompt")
        String conditionDiscoveryPrompt,
        @JsonProperty("condition_discovery_prompt_alt")
        String conditionDiscoveryPromptAlt,
        @JsonProperty("active_prompt_compact")
        String activePromptCompact,
        @JsonProperty("symptom_keywords")
        List<String> symptomKeywords,
        @JsonProperty("body_area_keywords")
        List<String> bodyAreaKeywords,
        @JsonProperty("service_family_keywords")
        Map<String, List<String>> serviceFamilyKeywords,
        @JsonProperty("emotion_signals")
        Map<String, Object> emotionSignals,
        @JsonProperty("care_actor")
        String careActor,
        @JsonProperty("scope_taxonomy")
        List<String> scopeTaxonomy,
        @JsonProperty("safety_red_flags")
        List<String> safetyRedFlags
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
            List<String> domainKeywords,
            String closingAction,
            String ctaTemplate,
            List<String> openingBubbles,
            Integer postPriceDelayMs,
            String preferenceDiscoveryPrompt,
            String conditionDiscoveryPrompt,
            String conditionDiscoveryPromptAlt,
            String activePromptCompact,
            List<String> symptomKeywords,
            List<String> bodyAreaKeywords,
            Map<String, List<String>> serviceFamilyKeywords,
            Map<String, Object> emotionSignals
    ) {
        this(industryCode, botName, selfPronoun, defaultCustomerPronoun,
                toneStyle, emojiLevel, forbiddenWords, domainKeywords, closingAction, ctaTemplate,
                openingBubbles, postPriceDelayMs, preferenceDiscoveryPrompt,
                conditionDiscoveryPrompt, conditionDiscoveryPromptAlt, activePromptCompact,
                symptomKeywords, bodyAreaKeywords, serviceFamilyKeywords, emotionSignals,
                null, List.of(), List.of());
    }

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
                toneStyle, emojiLevel, forbiddenWords, domainKeywords, null, null, List.of(), null, null,
                null, null, null, List.of(), List.of(), Map.of(), Map.of(), null, List.of(), List.of());
    }

    public PagePersonaConfig(
            String industryCode,
            String botName,
            String selfPronoun,
            String defaultCustomerPronoun,
            String toneStyle,
            String emojiLevel,
            List<String> forbiddenWords,
            List<String> domainKeywords,
            String closingAction,
            String ctaTemplate
    ) {
        this(industryCode, botName, selfPronoun, defaultCustomerPronoun,
                toneStyle, emojiLevel, forbiddenWords, domainKeywords, closingAction, ctaTemplate, List.of(), null, null,
                null, null, null, List.of(), List.of(), Map.of(), Map.of(), null, List.of(), List.of());
    }

    public PagePersonaConfig(
            String industryCode,
            String botName,
            String selfPronoun,
            String defaultCustomerPronoun,
            String toneStyle,
            String emojiLevel,
            List<String> forbiddenWords,
            List<String> domainKeywords,
            String closingAction,
            String ctaTemplate,
            List<String> openingBubbles,
            Integer postPriceDelayMs,
            String preferenceDiscoveryPrompt
    ) {
        this(industryCode, botName, selfPronoun, defaultCustomerPronoun,
                toneStyle, emojiLevel, forbiddenWords, domainKeywords, closingAction, ctaTemplate,
                openingBubbles, postPriceDelayMs, preferenceDiscoveryPrompt,
                null, null, null, List.of(), List.of(), Map.of(), Map.of(), null, List.of(), List.of());
    }

    public static PagePersonaConfig defaults() {
        return empty();
    }

    public static PagePersonaConfig empty() {
        return new PagePersonaConfig(
                null, null, null, null, null, null, List.of(), List.of(), null, null, List.of(), null, null,
                null, null, null, List.of(), List.of(), Map.of(), Map.of(), null, List.of(), List.of()
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
                || (ctaTemplate != null && !ctaTemplate.isBlank())
                || (openingBubbles != null && !openingBubbles.isEmpty())
                || postPriceDelayMs != null
                || (preferenceDiscoveryPrompt != null && !preferenceDiscoveryPrompt.isBlank())
                || (conditionDiscoveryPrompt != null && !conditionDiscoveryPrompt.isBlank())
                || (activePromptCompact != null && !activePromptCompact.isBlank());
    }

    public int resolvedPostPriceDelayMs() {
        if (postPriceDelayMs != null && postPriceDelayMs >= 1000) {
            return postPriceDelayMs;
        }
        return 10_000;
    }

    public List<String> resolvedOpeningBubbles(String pageName, String pageDescription, String customerHonorific) {
        String h = (customerHonorific != null && !customerHonorific.isBlank())
                ? customerHonorific.strip()
                : resolvedDefaultCustomerPronoun();
        String selfP = resolvedSelfPronoun();
        String bName = (botName != null && !botName.isBlank()) ? botName.strip() : "Trợ lý Tư vấn";
        String sName = (pageName != null && !pageName.isBlank()) ? pageName.strip() : "Shop";

        if (openingBubbles != null && openingBubbles.size() >= 3) {
            return List.of(
                    interpolateOpeningPlaceholders(openingBubbles.get(0), h, selfP, bName, sName),
                    interpolateOpeningPlaceholders(openingBubbles.get(1), h, selfP, bName, sName),
                    interpolateOpeningPlaceholders(openingBubbles.get(2), h, selfP, bName, sName)
            );
        }
        if (openingBubbles != null && openingBubbles.size() >= 2) {
            return List.of(
                    interpolateOpeningPlaceholders(openingBubbles.get(0), h, selfP, bName, sName),
                    interpolateOpeningPlaceholders(openingBubbles.get(1), h, selfP, bName, sName)
            );
        }
        String bubble1 = "Dạ chào " + h + ", " + selfP + " là " + bName + " của " + sName + " ạ.";
        String bubble2;
        if (openingBubbles != null && openingBubbles.size() == 1 && !openingBubbles.get(0).isBlank()) {
            bubble2 = interpolateOpeningPlaceholders(openingBubbles.get(0), h, selfP, bName, sName);
        } else if (pageDescription != null && !pageDescription.isBlank()) {
            String cleanDesc = pageDescription.strip();
            if (!cleanDesc.endsWith(".")) {
                cleanDesc = cleanDesc + ".";
            }
            bubble2 = cleanDesc;
        } else {
            bubble2 = "Bên " + selfP + " chuyên cung cấp sản phẩm và dịch vụ tư vấn tận tâm, chuẩn xác theo nhu cầu của " + h + ".";
        }
        return List.of(bubble1, bubble2);
    }

    private static String interpolateOpeningPlaceholders(
            String template, String honorific, String selfPronoun, String botName, String shopName) {
        if (template == null) {
            return "";
        }
        String capH = (honorific != null && !honorific.isBlank())
                ? Character.toUpperCase(honorific.charAt(0)) + honorific.substring(1)
                : "Mình";
        return template
                .replace("{customer_pronoun}", honorific)
                .replace("{honorific}", honorific)
                .replace("{CapHonorific}", capH)
                .replace("{self_pronoun}", selfPronoun)
                .replace("{bot_name}", botName)
                .replace("{shop_name}", shopName)
                .replace("{page_name}", shopName)
                .strip();
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

    public boolean isNonSpaIndustry() {
        return industryCode != null
                && !industryCode.isBlank()
                && !"spa_wellness".equalsIgnoreCase(industryCode)
                && !"BEAUTY_SPA".equalsIgnoreCase(industryCode);
    }

    public String resolvedConditionDiscoveryPrompt(String customerHonorific) {
        String h = (customerHonorific != null && !customerHonorific.isBlank())
                ? customerHonorific.strip()
                : resolvedDefaultCustomerPronoun();
        String capH = Character.toUpperCase(h.charAt(0)) + h.substring(1);
        if (conditionDiscoveryPrompt != null && !conditionDiscoveryPrompt.isBlank()) {
            String custom = conditionDiscoveryPrompt.strip()
                    .replace("{customer_pronoun}", h)
                    .replace("{honorific}", h)
                    .replace("{CapHonorific}", capH);
            if (!custom.toLowerCase(java.util.Locale.ROOT).contains("thường căng sau")) {
                return custom;
            }
        }
        return capH + " gặp tình trạng này lâu chưa hay mới gần đây để " + resolvedSelfPronoun() + " tư vấn gói phù hợp nhất cho mình ạ?";
    }

    public String resolvedPreferenceDiscoveryPrompt(String customerHonorific) {
        String h = (customerHonorific != null && !customerHonorific.isBlank())
                ? customerHonorific.strip()
                : resolvedDefaultCustomerPronoun();
        String capH = Character.toUpperCase(h.charAt(0)) + h.substring(1);
        if (preferenceDiscoveryPrompt != null && !preferenceDiscoveryPrompt.isBlank()) {
            return preferenceDiscoveryPrompt.strip()
                    .replace("{customer_pronoun}", h)
                    .replace("{honorific}", h)
                    .replace("{CapHonorific}", capH);
        }
        if (isNonSpaIndustry()) {
            return capH + " ưu tiên phương án cơ bản tiết kiệm hay chuyên sâu để " + resolvedSelfPronoun() + " báo chi tiết ạ?";
        }
        return capH + " thích lực nhẹ thư giãn hay lực vừa để thả lỏng vùng này cho dễ chịu ạ?";
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
        java.util.Set<String> merged = new java.util.LinkedHashSet<>();
        if (domainKeywords != null) {
            for (String kw : domainKeywords) {
                if (kw != null && !kw.isBlank()) merged.add(kw.strip());
            }
        }
        if (scopeTaxonomy != null) {
            for (String kw : scopeTaxonomy) {
                if (kw != null && !kw.isBlank()) merged.add(kw.strip());
            }
        }
        if (symptomKeywords != null) {
            for (String kw : symptomKeywords) {
                if (kw != null && !kw.isBlank()) merged.add(kw.strip());
            }
        }
        if (bodyAreaKeywords != null) {
            for (String kw : bodyAreaKeywords) {
                if (kw != null && !kw.isBlank()) merged.add(kw.strip());
            }
        }
        if (serviceFamilyKeywords != null) {
            for (Map.Entry<String, List<String>> e : serviceFamilyKeywords.entrySet()) {
                if (e.getKey() != null && !e.getKey().isBlank()) merged.add(e.getKey().strip());
                if (e.getValue() != null) {
                    for (String v : e.getValue()) {
                        if (v != null && !v.isBlank()) merged.add(v.strip());
                    }
                }
            }
        }
        return merged.isEmpty() ? Collections.emptyList() : List.copyOf(merged);
    }

    public String resolvedCareActor() {
        if (careActor != null && !careActor.isBlank()) {
            return careActor.strip();
        }
        String ind = industryCode != null ? industryCode.toLowerCase(Locale.ROOT) : "";
        List<String> kws = safeDomainKeywords();
        String joinedKws = String.join(" ", kws).toLowerCase(Locale.ROOT);
        if (ind.contains("dental") || ind.contains("nha_khoa") || ind.contains("clinic") || ind.contains("medical")
                || joinedKws.contains("nha khoa") || joinedKws.contains("tẩy trắng răng") || joinedKws.contains("bọc răng")
                || joinedKws.contains("niềng răng") || joinedKws.contains("bác sĩ")) {
            return "bác sĩ";
        }
        if (ind.contains("spa") || ind.contains("wellness") || joinedKws.contains("massage") || joinedKws.contains("dưỡng sinh")) {
            return "kỹ thuật viên";
        }
        return "chuyên viên";
    }

    public List<String> resolvedScopeTaxonomy() {
        java.util.Set<String> merged = new java.util.LinkedHashSet<>();
        if (scopeTaxonomy != null) {
            for (String s : scopeTaxonomy) {
                if (s != null && !s.isBlank()) merged.add(s.strip());
            }
        }
        if (bodyAreaKeywords != null) {
            for (String s : bodyAreaKeywords) {
                if (s != null && !s.isBlank()) merged.add(s.strip());
            }
        }
        if (domainKeywords != null) {
            for (String s : domainKeywords) {
                if (s != null && !s.isBlank()) merged.add(s.strip());
            }
        }
        return merged.isEmpty() ? Collections.emptyList() : List.copyOf(merged);
    }

    public List<String> resolvedSafetyRedFlags() {
        if (safetyRedFlags != null && !safetyRedFlags.isEmpty()) {
            return List.copyOf(safetyRedFlags);
        }
        return Collections.emptyList();
    }

    public boolean matchesDomainKeyword(String text) {
        List<String> allKw = safeDomainKeywords();
        if (text == null || text.isBlank() || allKw.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String kw : allKw) {
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
            tools.jackson.databind.JsonNode node = MAPPER.readTree(json);
            if (node != null && node.isObject()) {
                tools.jackson.databind.node.ObjectNode obj = (tools.jackson.databind.node.ObjectNode) node;
                tools.jackson.databind.JsonNode sfk = obj.get("service_family_keywords");
                if (sfk != null && sfk.isArray()) {
                    tools.jackson.databind.node.ObjectNode sfkMap = MAPPER.createObjectNode();
                    for (tools.jackson.databind.JsonNode item : sfk) {
                        if (item != null && item.isTextual()) {
                            tools.jackson.databind.node.ArrayNode arr = MAPPER.createArrayNode();
                            arr.add(item.asText());
                            sfkMap.set(item.asText(), arr);
                        }
                    }
                    obj.set("service_family_keywords", sfkMap);
                }
                tools.jackson.databind.JsonNode emo = obj.get("emotion_signals");
                if (emo != null && !emo.isObject()) {
                    obj.remove("emotion_signals");
                }
                PagePersonaConfig parsed = MAPPER.treeToValue(obj, PagePersonaConfig.class);
                return parsed != null ? parsed : empty();
            }
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
