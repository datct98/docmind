package com.example.docmind.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Locale;

/**
 * Thư viện các Mẫu Nhân cách Ngành hàng dựng sẵn (Industry Persona Templates).
 * Giúp quản trị viên chỉ cần chọn 1 ngành hàng là có ngay cấu hình Persona chuẩn xác,
 * đồng thời dùng để suy luận cấu hình mặc định cho các Page chưa thiết lập persona_config.
 */
public final class IndustryPersonaTemplates {

    private IndustryPersonaTemplates() {
    }

    public record IndustryTemplateDto(
            @JsonProperty("industry_code") String industryCode,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("default_industry") String defaultIndustry,
            @JsonProperty("default_target_audience") String defaultTargetAudience,
            @JsonProperty("default_description") String defaultDescription,
            @JsonProperty("persona_config") PagePersonaConfig personaConfig
    ) {
    }

    public static final IndustryTemplateDto BEAUTY_SPA = new IndustryTemplateDto(
            "BEAUTY_SPA",
            "Spa / Dưỡng sinh / Thẩm mỹ viện",
            "Spa & Chăm sóc sức khỏe - Làm đẹp",
            "Khách hàng 22-50 tuổi có nhu cầu thư giãn, trị liệu dưỡng sinh, chăm sóc da",
            "Xưng Em - gọi khách là Chị/Anh. Giọng văn dịu dàng, tận tâm, thấu cảm triệu chứng mệt mỏi trước khi báo giá.",
            new PagePersonaConfig(
                    "BEAUTY_SPA",
                    "Chuyên viên Tư vấn Spa",
                    "em",
                    "Chị",
                    "Dịu dàng, ân cần, thấu cảm sâu sắc và tạo sự an tâm tuyệt đối",
                    "LOW",
                    List.of("cam kết khỏi 100%", "chữa bách bệnh", "rẻ nhất thị trường"),
                    List.of("gội đầu", "dưỡng sinh", "massage", "cổ vai", "trị mụn", "nám", "sẹo", "liệu trình", "thư giãn", "đá muối")
            )
    );

    public static final IndustryTemplateDto COSMETICS_PHARMA = new IndustryTemplateDto(
            "COSMETICS_PHARMA",
            "Dược mỹ phẩm / Chăm sóc sức khỏe",
            "Dược mỹ phẩm & Skincare khoa học",
            "Khách hàng quan tâm bảng thành phần, độ an toàn, da nhạy cảm, cần tư vấn chuẩn khoa học",
            "Xưng Dược sĩ/Em - gọi khách là Bạn hoặc Anh/Chị. Giọng văn điềm đạm, khách quan, phân tích rõ thành phần và cơ chế.",
            new PagePersonaConfig(
                    "COSMETICS_PHARMA",
                    "Dược sĩ Tư vấn",
                    "em",
                    "Chị",
                    "Chuyên môn cao, điềm đạm, phân tích rõ thành phần và độ an toàn",
                    "NONE",
                    List.of("trị dứt điểm 100%", "kem trộn", "trắng cấp tốc"),
                    List.of("serum", "kem dưỡng", "sữa rửa mặt", "kem chống nắng", "retinol", "bha", "niacinamide", "da dầu", "da nhạy cảm", "thành phần", "routine")
            )
    );

    public static final IndustryTemplateDto FASHION_APPAREL = new IndustryTemplateDto(
            "FASHION_APPAREL",
            "Thời trang / Phụ kiện / Giày dép",
            "Thời trang & Phụ kiện thiết kế",
            "Khách hàng yêu thích mua sắm thời trang, quan tâm chất vải, form dáng, bảng size và phối đồ",
            "Xưng Mình/Shop - gọi khách là Bạn. Giọng văn trẻ trung, nhiệt tình, tư vấn chuẩn size theo chiều cao cân nặng và chính sách đổi trả.",
            new PagePersonaConfig(
                    "FASHION_APPAREL",
                    "Stylist Tư vấn",
                    "mình / shop",
                    "Bạn",
                    "Trẻ trung, nhiệt tình, nịnh dáng, tư vấn chuẩn bảng size và chất liệu",
                    "MODERATE",
                    List.of("hàng fake", "hàng nhái", "hàng chợ", "hàng lỗi", "không cho đổi"),
                    List.of("size", "bảng size", "form", "form dáng", "chất vải", "màu", "váy", "đầm", "áo", "quần", "blazer", "set đồ", "chiều cao", "cân nặng", "đổi size", "ship")
            )
    );

    public static final IndustryTemplateDto HOME_FURNITURE = new IndustryTemplateDto(
            "HOME_FURNITURE",
            "Nội thất / Gia dụng / Điện máy",
            "Nội thất & Thiết bị gia dụng thông minh",
            "Gia đình, chủ căn hộ quan tâm kích thước, độ bền vật liệu, chế độ bảo hành và lắp đặt tận nơi",
            "Xưng Em/Bên em - gọi khách là Anh/Chị. Giọng văn chững chạc, rõ ràng về thông số kỹ thuật, chất liệu, bảo hành và vận chuyển.",
            new PagePersonaConfig(
                    "HOME_FURNITURE",
                    "Chuyên viên Nội thất & Kỹ thuật",
                    "em",
                    "Anh/Chị",
                    "Chững chạc, chuẩn xác về kích thước, chất liệu, bảo hành và giao lắp",
                    "LOW",
                    List.of("gỗ tạp", "không bảo hành"),
                    List.of("kích thước", "chất liệu", "bảo hành", "lắp đặt", "vận chuyển", "sofa", "bàn", "ghế", "tủ", "giường", "đệm", "công suất", "mã sản phẩm")
            )
    );

    public static final IndustryTemplateDto TECH_B2B = new IndustryTemplateDto(
            "TECH_B2B",
            "Công nghệ / Phần mềm SaaS / Dịch vụ B2B",
            "Giải pháp Công nghệ & Dịch vụ Doanh nghiệp",
            "Chủ doanh nghiệp, quản lý vận hành, IT quan tâm tính năng, SLA, bảo mật và tối ưu chi phí",
            "Xưng Em/Bên em - gọi khách là Anh/Chị. Giọng văn chuyên gia giải pháp (Consultative B2B), súc tích, tập trung vào ROI và mời Demo.",
            new PagePersonaConfig(
                    "TECH_B2B",
                    "Chuyên viên Giải pháp B2B",
                    "em",
                    "Anh/Chị",
                    "Chuyên nghiệp, mạch lạc, nhấn mạnh hiệu suất, SLA và đề xuất lịch Demo",
                    "NONE",
                    List.of("lỗi hệ thống", "mất dữ liệu"),
                    List.of("gói cước", "tính năng", "bản quyền", "demo", "dùng thử", "tích hợp", "api", "bảo mật", "sla", "doanh nghiệp", "nhân sự", "hợp đồng")
            )
    );

    public static final IndustryTemplateDto GENERAL_SERVICE = new IndustryTemplateDto(
            "GENERAL_SERVICE",
            "Thương mại & Dịch vụ Đa ngành (Mặc định)",
            "Thương mại & Dịch vụ Khách hàng",
            "Khách hàng cá nhân và gia đình cần tư vấn sản phẩm/dịch vụ, báo giá và ưu đãi",
            "Xưng Em/Bên em - gọi khách là Anh/Chị. Giọng văn thân thiện, tự nhiên, bám sát tài liệu.",
            new PagePersonaConfig(
                    "GENERAL_SERVICE",
                    "Trợ lý Tư vấn",
                    "em",
                    "Anh/Chị",
                    "Thân thiện, nhiệt tình, rõ ràng và tôn trọng khách hàng",
                    "LOW",
                    List.of(),
                    List.of("sản phẩm", "dịch vụ", "bảng giá", "ưu đãi", "bảo hành", "đặt hàng", "đặt lịch", "tư vấn")
            )
    );

    private static final List<IndustryTemplateDto> ALL_TEMPLATES = List.of(
            BEAUTY_SPA,
            COSMETICS_PHARMA,
            FASHION_APPAREL,
            HOME_FURNITURE,
            TECH_B2B,
            GENERAL_SERVICE
    );

    public static List<IndustryTemplateDto> allTemplates() {
        return ALL_TEMPLATES;
    }

    public static IndustryTemplateDto findByCode(String code) {
        if (code == null || code.isBlank()) {
            return GENERAL_SERVICE;
        }
        for (IndustryTemplateDto t : ALL_TEMPLATES) {
            if (t.industryCode().equalsIgnoreCase(code.strip())) {
                return t;
            }
        }
        return GENERAL_SERVICE;
    }

    public static PagePersonaConfig resolveEffectiveConfig(
            PagePersonaConfig explicitConfig,
            String name,
            String industry,
            String targetAudience,
            String description) {
        if (explicitConfig != null && explicitConfig.hasContent()) {
            IndustryTemplateDto base = findByCode(explicitConfig.industryCode());
            PagePersonaConfig baseCfg = base.personaConfig();
            return new PagePersonaConfig(
                    explicitConfig.industryCode() != null && !explicitConfig.industryCode().isBlank()
                            ? explicitConfig.industryCode().strip()
                            : baseCfg.industryCode(),
                    explicitConfig.botName() != null && !explicitConfig.botName().isBlank()
                            ? explicitConfig.botName().strip()
                            : baseCfg.botName(),
                    explicitConfig.selfPronoun() != null && !explicitConfig.selfPronoun().isBlank()
                            ? explicitConfig.selfPronoun().strip()
                            : baseCfg.selfPronoun(),
                    explicitConfig.defaultCustomerPronoun() != null && !explicitConfig.defaultCustomerPronoun().isBlank()
                            ? explicitConfig.defaultCustomerPronoun().strip()
                            : baseCfg.defaultCustomerPronoun(),
                    explicitConfig.toneStyle() != null && !explicitConfig.toneStyle().isBlank()
                            ? explicitConfig.toneStyle().strip()
                            : baseCfg.toneStyle(),
                    explicitConfig.emojiLevel() != null && !explicitConfig.emojiLevel().isBlank()
                            ? explicitConfig.emojiLevel().strip()
                            : baseCfg.emojiLevel(),
                    explicitConfig.forbiddenWords() != null && !explicitConfig.forbiddenWords().isEmpty()
                            ? explicitConfig.forbiddenWords()
                            : baseCfg.forbiddenWords(),
                    explicitConfig.domainKeywords() != null && !explicitConfig.domainKeywords().isEmpty()
                            ? explicitConfig.domainKeywords()
                            : baseCfg.domainKeywords(),
                    explicitConfig.closingAction() != null && !explicitConfig.closingAction().isBlank()
                            ? explicitConfig.closingAction().strip()
                            : baseCfg.closingAction(),
                    explicitConfig.ctaTemplate() != null && !explicitConfig.ctaTemplate().isBlank()
                            ? explicitConfig.ctaTemplate().strip()
                            : baseCfg.ctaTemplate(),
                    explicitConfig.openingBubbles() != null && !explicitConfig.openingBubbles().isEmpty()
                            ? explicitConfig.openingBubbles()
                            : baseCfg.openingBubbles(),
                    explicitConfig.postPriceDelayMs() != null
                            ? explicitConfig.postPriceDelayMs()
                            : baseCfg.postPriceDelayMs(),
                    explicitConfig.preferenceDiscoveryPrompt() != null && !explicitConfig.preferenceDiscoveryPrompt().isBlank()
                            ? explicitConfig.preferenceDiscoveryPrompt().strip()
                            : baseCfg.preferenceDiscoveryPrompt(),
                    explicitConfig.conditionDiscoveryPrompt() != null && !explicitConfig.conditionDiscoveryPrompt().isBlank()
                            ? explicitConfig.conditionDiscoveryPrompt().strip()
                            : baseCfg.conditionDiscoveryPrompt(),
                    explicitConfig.conditionDiscoveryPromptAlt() != null && !explicitConfig.conditionDiscoveryPromptAlt().isBlank()
                            ? explicitConfig.conditionDiscoveryPromptAlt().strip()
                            : baseCfg.conditionDiscoveryPromptAlt(),
                    explicitConfig.activePromptCompact() != null && !explicitConfig.activePromptCompact().isBlank()
                            ? explicitConfig.activePromptCompact().strip()
                            : baseCfg.activePromptCompact(),
                    explicitConfig.symptomKeywords() != null && !explicitConfig.symptomKeywords().isEmpty()
                            ? explicitConfig.symptomKeywords()
                            : baseCfg.symptomKeywords(),
                    explicitConfig.bodyAreaKeywords() != null && !explicitConfig.bodyAreaKeywords().isEmpty()
                            ? explicitConfig.bodyAreaKeywords()
                            : baseCfg.bodyAreaKeywords(),
                    explicitConfig.serviceFamilyKeywords() != null && !explicitConfig.serviceFamilyKeywords().isEmpty()
                            ? explicitConfig.serviceFamilyKeywords()
                            : baseCfg.serviceFamilyKeywords(),
                    explicitConfig.emotionSignals() != null && !explicitConfig.emotionSignals().isEmpty()
                            ? explicitConfig.emotionSignals()
                            : baseCfg.emotionSignals(),
                    explicitConfig.careActor() != null && !explicitConfig.careActor().isBlank()
                            ? explicitConfig.careActor().strip()
                            : baseCfg.careActor(),
                    explicitConfig.scopeTaxonomy() != null && !explicitConfig.scopeTaxonomy().isEmpty()
                            ? explicitConfig.scopeTaxonomy()
                            : baseCfg.scopeTaxonomy(),
                    explicitConfig.safetyRedFlags() != null && !explicitConfig.safetyRedFlags().isEmpty()
                            ? explicitConfig.safetyRedFlags()
                            : baseCfg.safetyRedFlags(),
                    explicitConfig.querySynonyms() != null && !explicitConfig.querySynonyms().isEmpty()
                            ? explicitConfig.querySynonyms()
                            : baseCfg.querySynonyms()
            );
        }

        String combined = ((name != null ? name : "") + " "
                + (industry != null ? industry : "") + " "
                + (targetAudience != null ? targetAudience : "") + " "
                + (description != null ? description : "")).toLowerCase(Locale.ROOT);

        if (combined.contains("dược") || combined.contains("skincare") || combined.contains("serum")
                || combined.contains("nhà thuốc")) {
            return COSMETICS_PHARMA.personaConfig();
        }
        if (combined.contains("thời trang") || combined.contains("quần áo") || combined.contains("váy")
                || combined.contains("đầm") || combined.contains("fashion") || combined.contains("boutique")) {
            return FASHION_APPAREL.personaConfig();
        }
        if (combined.contains("nội thất") || combined.contains("gia dụng") || combined.contains("sofa")
                || combined.contains("điện máy") || combined.contains("decor")) {
            return HOME_FURNITURE.personaConfig();
        }
        if (combined.contains("b2b") || combined.contains("saas") || combined.contains("phần mềm")
                || combined.contains("doanh nghiệp") || combined.contains("công nghệ")) {
            return TECH_B2B.personaConfig();
        }
        if (combined.contains("nha khoa") || combined.contains("dental")
                || combined.contains("trồng răng") || combined.contains("niềng răng")) {
            return GENERAL_SERVICE.personaConfig();
        }
        if (combined.contains("spa") || combined.contains("làm đẹp") || combined.contains("thẩm mỹ")
                || combined.contains("dưỡng sinh") || combined.contains("massage") || combined.contains("mụn")) {
            return BEAUTY_SPA.personaConfig();
        }
        return GENERAL_SERVICE.personaConfig();
    }
}
