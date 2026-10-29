package com.example.docmind.service.bot;

import com.example.docmind.service.retrieval.ReciprocalRankFusion;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dịch vụ trích xuất Danh mục Sản phẩm / Dịch vụ Đa ngành (Multi-Industry Catalog Service — Checkpoint C2-A)
 * trực tiếp từ các đoạn tài liệu Active ({@link ReciprocalRankFusion.FusedChunk}) của từng Page/Tenant.
 *
 * <p>Tách bạch 4 lớp trách nhiệm trong hợp đồng dữ liệu:
 * <ol>
 *   <li><b>Source ({@link CatalogSourceData})</b>: tên gốc, mã SKU gốc (nếu có), chuỗi giá gốc, đơn vị gốc, quy cách gốc.</li>
 *   <li><b>Normalized ({@link CatalogNormalizedData})</b>: {@code productId}, {@code canonicalName}, {@code priceAmountVnd},
 *       {@link PriceType}, {@code billingUnit}, danh sách {@link SpecificationAttr}, {@link ParseStatus}.</li>
 *   <li><b>Inferred ({@link CatalogInferredData})</b>: {@link CommercialRole} (chỉ gán {@code ECONOMICAL_OPTION} khi có căn cứ cấu trúc),
 *       {@code scopeTags}, {@link RegionScope} ({@code UNSPECIFIED} | {@code GLOBAL_CONFIRMED} | {@code BRANCH_SPECIFIC}).</li>
 *   <li><b>Display ({@link CatalogDisplayData})</b>: nhãn giá động ({@code Đơn giá} / {@code Giá từ} / {@code Giá niêm yết} / {@code Giá trọn gói}),
 *       chuỗi giá kèm đơn vị tính và các getter adapter tương thích ngược cho {@link PackageSummary}.</li>
 * </ol>
 */
public final class PackageCatalogService {

    /** Hỗ trợ SKU đa ngành: DS02, NS01, ADD01, GACH-8080, IELTS-24B, TILE_80X80, ENG_01... */
    private static final Pattern SKU_CODE_PATTERN = Pattern.compile(
            "^(?:[A-Z]{2,10}(?:[-_][A-Z0-9]{1,14})+|[A-Z]{1,8}\\d{1,6}[A-Z0-9]{0,6})$"
    );

    /** Quy ước phân tầng mã truyền thống khi cùng xuất hiện trong 1 danh mục Spa (NS* chủ lực vs DS* dưỡng sinh tiết kiệm). */
    private static final Pattern SPA_DS_TIER_CODE_PATTERN = Pattern.compile("^DS\\d{1,3}$");
    private static final Pattern SPA_NS_TIER_CODE_PATTERN = Pattern.compile("^NS\\d{1,3}$");

    private static final Pattern STARTING_FROM_PREFIX_PATTERN = Pattern.compile(
            "(?iU)(?:^|\\b)(?:giá\\s+)?(?:chỉ\\s+)?(?:từ|khởi\\s+điểm(?:\\s+từ)?)\\s+\\d"
    );

    private static final Pattern CONTACT_PRICE_PATTERN = Pattern.compile(
            "(?iU)^\\s*(?:giá\\s*:?\\s*)?(?:liên\\s+hệ|thỏa\\s+thuận|thoả\\s+thuận|báo\\s+giá\\s+riêng|inbox|tùy\\s+diện\\s+tích)\\s*$"
    );

    /**
     * Thứ tự ưu tiên tất định khi đọc biểu thức số tiền tiếng Việt:
     * 1. Dạng tách hậu tố triệu: "3tr5", "3 triệu 5", "1tr250"
     * 2. Số có hậu tố "triệu" / "tr": "3,5 triệu", "3.5tr", "12 triệu"
     * 3. Số có hậu tố "k" / "nghìn" / "ngàn": "3.500k" (dấu chấm phân cách hàng nghìn -> 3.500.000),
     *    "450k", "450,5k", "3500k"
     * 4. Số tiền đồng đầy đủ (có phân nhóm hàng nghìn . hoặc , hoặc đi kèm đ/vnđ/vnd/đồng): "3.500.000đ", "399.000VNĐ"
     */
    private static final Pattern SPLIT_MILLION_PATTERN = Pattern.compile(
            "(?iU)(?<![\\p{L}\\p{N}.,])(\\d{1,4})\\s*(?:tr|triệu)\\s*(\\d{1,3})(?![\\p{L}\\p{N}])"
    );
    private static final Pattern SUFFIX_MILLION_PATTERN = Pattern.compile(
            "(?iU)(?<![\\p{L}\\p{N}.,])(\\d{1,3}(?:\\.\\d{3})+(?:,\\d{1,3})?|\\d{1,4}(?:[.,]\\d{1,3})?)\\s*(?:triệu|tr\\b)\\s*(?:vnđ|vnd|đ|đồng)?"
    );
    private static final Pattern SUFFIX_THOUSAND_PATTERN = Pattern.compile(
            "(?iU)(?<![\\p{L}\\p{N}.,])(\\d{1,3}(?:\\.\\d{3})+(?:,\\d{1,2})?|\\d{1,6}(?:,\\d{1,2})?|\\d{1,3}\\.\\d{1,2})\\s*(?:k\\b|nghìn\\b|ngàn\\b)\\s*(?:vnđ|vnd|đ|đồng)?"
    );
    private static final Pattern FULL_VND_AMOUNT_PATTERN = Pattern.compile(
            "(?iU)(?<![\\p{L}\\p{N}.,])(\\d{1,3}(?:\\.\\d{3}){1,3}|\\d{1,3}(?:,\\d{3}){1,3}|\\d{5,10}\\s*(?:vnđ|vnd|đ|đồng))\\s*(?:vnđ|vnd|đ|đồng)?(?![\\s]*(?:k\\b|nghìn\\b|ngàn\\b|tr\\b|triệu\\b))"
    );
    private static final Pattern MALFORMED_DOT_THOUSAND_BEFORE_K_PATTERN = Pattern.compile(
            "(?iU)(?<![\\p{L}\\p{N}])\\d{1,3}\\.\\d{4,}\\s*(?:k|tr|triệu|đ|vnđ|vnd)\\b"
    );

    /** Bóc tách đơn vị tính đi ngay sau biểu thức giá: /m², /m2, /khóa, /buổi, /tháng, /hộp, /bộ... */
    private static final Pattern BILLING_UNIT_AFTER_PRICE_PATTERN = Pattern.compile(
            "(?iU)^\\s*(?:/|trên\\s+|mỗi\\s+)\\s*(m²|m2|mét\\s*vuông|m3|m³|mét\\s*khối|mét|m\\b|khóa|khoá|buổi|tháng|năm|tuần|ngày|giờ|ca|suất|vé|người|học\\s*viên|hộp|thùng|viên|tấm|bộ|cái|chiếc|cặp|lần|gói|liệu\\s*trình)"
    );

    private static final Pattern DURATION_MIN_PATTERN = Pattern.compile(
            "(?iU)(?<![\\p{L}\\p{N}])(~?\\s*\\d{1,3})\\s*(?:phút|p\\b)"
    );
    private static final Pattern DIMENSION_SPEC_PATTERN = Pattern.compile(
            "(?iU)(?<![\\p{L}\\p{N}])(\\d{1,4}\\s*[x×]\\s*\\d{1,4}(?:\\s*[x×]\\s*\\d{1,4})?)\\s*(mm|cm|m)\\b"
    );
    private static final Pattern GENERIC_QUANTITY_UNIT_SPEC_PATTERN = Pattern.compile(
            "(?iU)(?<![\\p{L}\\p{N}/])(~?\\s*\\d{1,4}(?:[.,]\\d{1,2})?)\\s*(phút|p\\b|giờ|tiếng|buổi|tuần|tháng|năm|m²|m2|viên/thùng|kg|lít|ml)\\b"
    );

    private static final Pattern SKU_HEADER_PATTERN = Pattern.compile(
            "(?iU)^(?:mã(?:\\s*(?:gói|dịch\\s*vụ|dv|sp|sản\\s*phẩm|khóa(?:\\s*học)?|hạng\\s*mục|mẫu))?|sku|code|product\\s*id)$"
    );
    private static final Pattern INDEX_HEADER_PATTERN = Pattern.compile(
            "(?iU)^(?:stt|#|no\\.?|số\\s*thứ\\s*tự)$"
    );
    private static final Pattern NAME_HEADER_PATTERN = Pattern.compile(
            "(?iU).*(?:tên|sản\\s*phẩm|dịch\\s*vụ|khóa\\s*học|khoá\\s*học|hạng\\s*mục|mẫu|gói|liệu\\s*trình|product|service|course|item).*"
    );
    private static final Pattern PRICE_HEADER_PATTERN = Pattern.compile(
            "(?iU).*(?:đơn\\s*giá|mức\\s*giá|báo\\s*giá|giá\\s*bán|giá|học\\s*phí|chi\\s*phí|price|fee).*"
    );
    private static final Pattern NON_CATALOG_TABLE_HEADER_PATTERN = Pattern.compile(
            "(?iU).*(?:câu\\s*hỏi|trả\\s*lời|giải\\s*đáp|mức\\s*ưu\\s*đãi|chiết\\s*khấu\\s*theo|điều\\s*kiện\\s*áp\\s*dụng|thực\\s*đơn\\s*buffet|món\\s*ăn\\s*nhẹ).*"
    );
    private static final Pattern BULLET_LINE_PATTERN = Pattern.compile(
            "^\\s*(?:[-*•+]|\\d{1,2}[.)])\\s+(.+)$"
    );
    private static final Pattern LEADING_BRACKET_OR_COLON_SKU_PATTERN = Pattern.compile(
            "^\\s*(?:\\[([A-Za-z0-9][A-Za-z0-9_-]{1,18})]\\s*|([A-Z]{2,10}(?:[-_][A-Z0-9]{1,12})+|(?:[A-Z]{2,6}\\d{1,5}))\\s*[:\\-–—]\\s*)(.+)$"
    );

    private static final List<String> ANATOMICAL_SCOPE_TOKENS = List.of(
            "toàn thân", "da đầu", "da mặt", "thắt lưng", "bắp chân",
            "đầu", "cổ", "vai", "gáy", "lưng", "eo", "chân", "gối", "tay", "tóc", "tai", "body", "răng", "nướu"
    );

    private PackageCatalogService() {
    }

    public enum PriceType {
        FIXED,
        PER_UNIT,
        STARTING_FROM,
        CONTACT
    }

    public enum ParseStatus {
        VERIFIED,
        NEEDS_VERIFICATION,
        FAILED
    }

    public enum CommercialRole {
        PRIMARY,
        ECONOMICAL_OPTION,
        STANDARD,
        PREMIUM_OPTION,
        ADDON,
        UNKNOWN
    }

    public enum RegionScope {
        UNSPECIFIED,
        GLOBAL_CONFIRMED,
        BRANCH_SPECIFIC
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SpecificationAttr(
            @JsonProperty("key") String key,
            @JsonProperty("label") String label,
            @JsonProperty("raw_value") String rawValue,
            @JsonProperty("numeric_value") Double numericValue,
            @JsonProperty("unit") String unit
    ) {
    }

    /** Lớp 1 — Dữ liệu nguồn nguyên bản (Source Layer). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CatalogSourceData(
            @JsonProperty("raw_sku_code") String rawSkuCode,
            @JsonProperty("raw_name") String rawName,
            @JsonProperty("raw_price_text") String rawPriceText,
            @JsonProperty("raw_unit_text") String rawUnitText,
            @JsonProperty("raw_spec_text") String rawSpecText,
            @JsonProperty("raw_format_or_space_text") String rawFormatOrSpaceText,
            @JsonProperty("raw_heading_path") List<String> rawHeadingPath
    ) {
    }

    /** Lớp 2 — Dữ liệu chuẩn hóa (Normalized Layer). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CatalogNormalizedData(
            @JsonProperty("product_id") String productId,
            @JsonProperty("canonical_name") String canonicalName,
            @JsonProperty("price_amount_vnd") long priceAmountVnd,
            @JsonProperty("price_type") PriceType priceType,
            @JsonProperty("billing_unit") String billingUnit,
            @JsonProperty("specifications") List<SpecificationAttr> specifications,
            @JsonProperty("parse_status") ParseStatus parseStatus,
            @JsonProperty("parse_note") String parseNote
    ) {
    }

    /** Lớp 3 — Thuộc tính suy luận có kiểm soát (Inferred Layer). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CatalogInferredData(
            @JsonProperty("commercial_role") CommercialRole commercialRole,
            @JsonProperty("scope_tags") List<String> scopeTags,
            @JsonProperty("region_scope") RegionScope regionScope,
            @JsonProperty("region_or_branch_code") String regionOrBranchCode
    ) {
    }

    /** Lớp 4 — Dữ liệu hiển thị theo ngôn ngữ Page (Display Layer). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CatalogDisplayData(
            @JsonProperty("price_label") String priceLabel,
            @JsonProperty("formatted_price_with_unit") String formattedPriceWithUnit,
            @JsonProperty("formatted_specs_summary") String formattedSpecsSummary,
            @JsonProperty("scope_label") String scopeLabel,
            @JsonProperty("format_or_space_label") String formatOrSpaceLabel,
            @JsonProperty("customer_facing_format_note") String customerFacingFormatNote,
            @JsonProperty("unit_noun") String unitNoun
    ) {
        public CatalogDisplayData(
                String priceLabel,
                String formattedPriceWithUnit,
                String formattedSpecsSummary,
                String scopeLabel,
                String formatOrSpaceLabel,
                String customerFacingFormatNote
        ) {
            this(
                    priceLabel,
                    formattedPriceWithUnit,
                    formattedSpecsSummary,
                    scopeLabel,
                    formatOrSpaceLabel,
                    customerFacingFormatNote,
                    "Vùng chăm sóc".equals(scopeLabel) ? "gói" : "sản phẩm"
            );
        }
    }

    public record ParsedPrice(
            long priceAmountVnd,
            PriceType priceType,
            String billingUnit,
            ParseStatus parseStatus,
            String diagnosticNote
    ) {
        public String formattedCompactWithUnit() {
            if (priceType == PriceType.CONTACT || priceAmountVnd <= 0L) {
                return "Liên hệ";
            }
            String base = formatCompactPrice(priceAmountVnd);
            String unitSuffix = (billingUnit != null && !billingUnit.isBlank() && !"trọn gói".equalsIgnoreCase(billingUnit.strip()))
                    ? billingUnit.strip()
                    : "";
            if (priceType == PriceType.STARTING_FROM) {
                return "Từ " + base + unitSuffix;
            }
            return base + unitSuffix;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PackageSummary(
            @JsonProperty("package_uid") String packageUid,
            @JsonProperty("code") String code,
            @JsonProperty("region") String region,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("service_family") String serviceFamily,
            @JsonProperty("body_areas") List<String> bodyAreas,
            @JsonProperty("duration_minutes") Integer durationMinutes,
            @JsonProperty("duration_label") String durationLabel,
            @JsonProperty("price_vnd") long priceVnd,
            @JsonProperty("price_display") String priceDisplay,
            @JsonProperty("room_type") String roomType,
            @JsonProperty("includes_summary") String includesSummary,
            @JsonProperty("suitable_for") String suitableFor,
            @JsonProperty("process_summary") String processSummary,
            @JsonProperty("is_addon") boolean isAddon,
            @JsonProperty("source") CatalogSourceData source,
            @JsonProperty("normalized") CatalogNormalizedData normalized,
            @JsonProperty("inferred") CatalogInferredData inferred,
            @JsonProperty("display") CatalogDisplayData display
    ) {
        /**
         * Constructor tương thích ngược 15 tham số cho toàn bộ bộ test fixture hiện có,
         * tự động điền đầy đủ 4 lớp dữ liệu đa ngành ({@code source}, {@code normalized}, {@code inferred}, {@code display}).
         */
        public PackageSummary(
                String packageUid,
                String code,
                String region,
                String displayName,
                String serviceFamily,
                List<String> bodyAreas,
                Integer durationMinutes,
                String durationLabel,
                long priceVnd,
                String priceDisplay,
                String roomType,
                String includesSummary,
                String suitableFor,
                String processSummary,
                boolean isAddon
        ) {
            this(
                    packageUid,
                    code,
                    region,
                    displayName,
                    serviceFamily,
                    bodyAreas != null ? List.copyOf(bodyAreas) : List.of(),
                    durationMinutes,
                    durationLabel,
                    priceVnd,
                    priceDisplay,
                    roomType,
                    includesSummary,
                    suitableFor,
                    processSummary,
                    isAddon,
                    buildCompatSource(code, displayName, priceDisplay, durationLabel, roomType),
                    buildCompatNormalized(packageUid, code, displayName, priceVnd, priceDisplay, durationMinutes, durationLabel),
                    buildCompatInferred(code, region, serviceFamily, bodyAreas, isAddon),
                    buildCompatDisplay(displayName, priceVnd, priceDisplay, durationMinutes, durationLabel, roomType, bodyAreas)
            );
        }

        public String productId() {
            if (normalized != null && normalized.productId() != null && !normalized.productId().isBlank()) {
                return normalized.productId();
            }
            return packageUid != null ? packageUid : (code != null ? code : "");
        }

        public String canonicalName() {
            if (normalized != null && normalized.canonicalName() != null && !normalized.canonicalName().isBlank()) {
                return normalized.canonicalName();
            }
            return displayName != null ? displayName.strip() : "";
        }

        public long priceAmountVnd() {
            return normalized != null ? normalized.priceAmountVnd() : priceVnd;
        }

        public PriceType priceType() {
            return normalized != null && normalized.priceType() != null ? normalized.priceType() : PriceType.FIXED;
        }

        public String billingUnit() {
            return normalized != null && normalized.billingUnit() != null ? normalized.billingUnit() : "";
        }

        public List<SpecificationAttr> specifications() {
            return normalized != null && normalized.specifications() != null ? normalized.specifications() : List.of();
        }

        public ParseStatus parseStatus() {
            return normalized != null && normalized.parseStatus() != null ? normalized.parseStatus() : ParseStatus.VERIFIED;
        }

        public CommercialRole commercialRole() {
            if (inferred != null && inferred.commercialRole() != null) {
                return inferred.commercialRole();
            }
            return isAddon ? CommercialRole.ADDON : CommercialRole.UNKNOWN;
        }

        public List<String> scopeTags() {
            if (inferred != null && inferred.scopeTags() != null) {
                return inferred.scopeTags();
            }
            return bodyAreas != null ? bodyAreas : List.of();
        }

        public RegionScope regionScope() {
            if (inferred != null && inferred.regionScope() != null) {
                return inferred.regionScope();
            }
            return inferRegionScopeFromCode(region);
        }

        public String formattedPriceWithUnit() {
            if (display != null && display.formattedPriceWithUnit() != null && !display.formattedPriceWithUnit().isBlank()) {
                return display.formattedPriceWithUnit();
            }
            return priceDisplay != null ? priceDisplay.strip() : "";
        }

        public String priceLabel() {
            if (display != null && display.priceLabel() != null && !display.priceLabel().isBlank()) {
                return display.priceLabel();
            }
            return "Giá niêm yết";
        }

        public String formattedSpecsSummary() {
            if (display != null && display.formattedSpecsSummary() != null) {
                return display.formattedSpecsSummary();
            }
            return durationLabel != null ? durationLabel.strip() : "";
        }

        public String unitNoun() {
            if (display != null && display.unitNoun() != null && !display.unitNoun().isBlank()) {
                return display.unitNoun().strip();
            }
            return "sản phẩm";
        }

        public String displayWithUnitNoun(String overrideUnitNoun) {
            String effectiveUnit = (overrideUnitNoun != null && !overrideUnitNoun.isBlank())
                    ? overrideUnitNoun.strip()
                    : unitNoun();
            String label = canonicalLabel();
            if (label.isEmpty()) {
                return effectiveUnit;
            }
            String lowLabel = label.toLowerCase(Locale.ROOT);
            String lowUnit = effectiveUnit.toLowerCase(Locale.ROOT);
            if (lowLabel.startsWith(lowUnit + " ") || lowLabel.equals(lowUnit)) {
                return label;
            }
            return effectiveUnit + " " + label;
        }

        public String canonicalLabel() {
            String base = canonicalName();
            String spec = durationLabel != null ? durationLabel.strip() : "";
            if (!spec.isBlank()) {
                String baseLow = base.toLowerCase(Locale.ROOT);
                String specNormLow = spec.replaceFirst("^~\\s*", "").strip().toLowerCase(Locale.ROOT);
                if (!specNormLow.isEmpty() && !baseLow.contains(specNormLow)) {
                    return base + " " + spec;
                }
            }
            return base;
        }

        /**
         * Ghi chú hình thức / không gian hướng khách hàng:
         * - Với ngành Spa có cột Phòng = "Phòng chung", chuyển sang mô tả lịch sự trung thực có rèm che riêng.
         * - Với mọi ngành khác, giữ nguyên văn mô tả hình thức/quy cách giao nhận/lớp học gốc.
         */
        public String customerFacingSpaceNote() {
            if (display != null && display.customerFacingFormatNote() != null && !display.customerFacingFormatNote().isBlank()) {
                return display.customerFacingFormatNote();
            }
            if (roomType == null || roomType.isBlank()) {
                return "";
            }
            String lower = roomType.strip().toLowerCase(Locale.ROOT);
            if (lower.contains("phòng chung")) {
                return "không gian yên tĩnh, sạch thoáng, mỗi giường đều có rèm che riêng kín đáo";
            }
            return roomType.strip();
        }
    }

    private static CatalogSourceData buildCompatSource(
            String code,
            String displayName,
            String priceDisplay,
            String durationLabel,
            String roomType
    ) {
        return new CatalogSourceData(
                code != null ? code : "",
                displayName != null ? displayName : "",
                priceDisplay != null ? priceDisplay : "",
                "",
                durationLabel != null ? durationLabel : "",
                roomType != null ? roomType : "",
                List.of()
        );
    }

    private static CatalogNormalizedData buildCompatNormalized(
            String packageUid,
            String code,
            String displayName,
            long priceVnd,
            String priceDisplay,
            Integer durationMinutes,
            String durationLabel
    ) {
        ParsedPrice parsedFromDisplay = (priceDisplay != null && !priceDisplay.isBlank())
                ? parseStructuredPrice(priceDisplay)
                : null;
        PriceType pType = (parsedFromDisplay != null && parsedFromDisplay.parseStatus() == ParseStatus.VERIFIED)
                ? parsedFromDisplay.priceType()
                : (priceVnd > 0L ? PriceType.FIXED : PriceType.CONTACT);
        String bUnit = (parsedFromDisplay != null && parsedFromDisplay.billingUnit() != null)
                ? parsedFromDisplay.billingUnit()
                : "";
        List<SpecificationAttr> specs = extractSpecifications(
                displayName != null ? displayName : "",
                "Thời lượng",
                durationLabel != null ? durationLabel : (durationMinutes != null && durationMinutes > 0 ? durationMinutes + " phút" : ""),
                Map.of()
        );
        String prodId = (packageUid != null && !packageUid.isBlank())
                ? packageUid.strip()
                : ((code != null && !code.isBlank()) ? code.strip() : buildDeterministicProductId("UNSPECIFIED", "", displayName, durationLabel));
        ParseStatus status = priceVnd > 0L ? ParseStatus.VERIFIED : ParseStatus.NEEDS_VERIFICATION;
        return new CatalogNormalizedData(
                prodId,
                displayName != null ? displayName.strip() : "",
                priceVnd,
                pType,
                bUnit,
                specs,
                status,
                ""
        );
    }

    private static CatalogInferredData buildCompatInferred(
            String code,
            String region,
            String serviceFamily,
            List<String> bodyAreas,
            boolean isAddon
    ) {
        RegionScope rScope = inferRegionScopeFromCode(region);
        String rCode = rScope == RegionScope.UNSPECIFIED ? "" : (region != null ? region.strip() : "");
        CommercialRole role = inferCommercialRole(
                code,
                "",
                serviceFamily,
                isAddon,
                code != null && (SPA_DS_TIER_CODE_PATTERN.matcher(code.strip().toUpperCase(Locale.ROOT)).matches()
                        || SPA_NS_TIER_CODE_PATTERN.matcher(code.strip().toUpperCase(Locale.ROOT)).matches())
        );
        return new CatalogInferredData(
                role,
                bodyAreas != null ? List.copyOf(bodyAreas) : List.of(),
                rScope,
                rCode
        );
    }

    private static CatalogDisplayData buildCompatDisplay(
            String displayName,
            long priceVnd,
            String priceDisplay,
            Integer durationMinutes,
            String durationLabel,
            String roomType,
            List<String> bodyAreas
    ) {
        ParsedPrice parsed = (priceDisplay != null && !priceDisplay.isBlank())
                ? parseStructuredPrice(priceDisplay)
                : new ParsedPrice(priceVnd, PriceType.FIXED, "", priceVnd > 0 ? ParseStatus.VERIFIED : ParseStatus.NEEDS_VERIFICATION, "");
        PriceType pt = parsed.parseStatus() == ParseStatus.VERIFIED ? parsed.priceType() : PriceType.FIXED;
        String bUnit = parsed.billingUnit() != null ? parsed.billingUnit() : "";
        String pLabel = resolvePriceLabel(pt, bUnit, priceDisplay, durationMinutes != null && durationMinutes > 0);
        String formattedPrice = (priceDisplay != null && !priceDisplay.isBlank())
                ? priceDisplay.strip()
                : formatPriceWithUnit(priceVnd, pt, bUnit);
        String specSummary = (durationLabel != null && durationLabel.strip().length() > 0)
                ? ((durationMinutes != null && durationMinutes > 0 ? "Thời lượng: " : "Quy cách: ") + durationLabel.strip())
                : "";
        boolean hasAnatomical = bodyAreas != null && bodyAreas.stream()
                .filter(Objects::nonNull)
                .map(s -> s.strip().toLowerCase(Locale.ROOT))
                .anyMatch(ANATOMICAL_SCOPE_TOKENS::contains);
        String sLabel = hasAnatomical ? "Vùng chăm sóc" : "Phạm vi phù hợp";
        String fNote = "";
        if (roomType != null && !roomType.isBlank()) {
            String low = roomType.strip().toLowerCase(Locale.ROOT);
            fNote = low.contains("phòng chung")
                    ? "không gian yên tĩnh, sạch thoáng, mỗi giường đều có rèm che riêng kín đáo"
                    : roomType.strip();
        }
        String fLabel = (roomType != null && roomType.toLowerCase(Locale.ROOT).contains("phòng"))
                ? "Không gian"
                : "Hình thức";
        String uNoun = inferUnitNoun(displayName, bUnit, durationLabel, durationMinutes, List.of(), hasAnatomical);
        return new CatalogDisplayData(pLabel, formattedPrice, specSummary, sLabel, fLabel, fNote, uNoun);
    }

    private static String inferUnitNoun(
            String rawName,
            String billingUnit,
            String specText,
            Integer durationMin,
            List<String> headingPath,
            boolean hasAnatomicalScope
    ) {
        String nameLow = rawName != null ? rawName.strip().toLowerCase(Locale.ROOT) : "";
        String unitLow = billingUnit != null ? billingUnit.strip().toLowerCase(Locale.ROOT) : "";
        String specLow = specText != null ? specText.strip().toLowerCase(Locale.ROOT) : "";
        String headLow = headingPath != null ? String.join(" ", headingPath).toLowerCase(Locale.ROOT) : "";

        if (nameLow.startsWith("khóa ") || nameLow.startsWith("khoá ") || nameLow.startsWith("lớp ")
                || unitLow.contains("khóa") || unitLow.contains("khoá") || unitLow.contains("học viên")
                || headLow.contains("khóa học") || headLow.contains("khoá học") || headLow.contains("lộ trình học")
                || nameLow.contains("ielts") || nameLow.contains("toeic") || nameLow.contains("giao tiếp")
                || (specLow.contains("buổi") && !hasAnatomicalScope && (durationMin == null || durationMin <= 0))) {
            return "khóa";
        }
        if (nameLow.startsWith("mẫu ") || nameLow.startsWith("gạch ")
                || unitLow.contains("m²") || unitLow.contains("m2") || unitLow.contains("thùng")
                || unitLow.contains("viên") || unitLow.contains("hộp")
                || headLow.contains("gạch") || headLow.contains("ốp lát") || headLow.contains("vật liệu")
                || specLow.matches(".*\\d+\\s*[x×]\\s*\\d+\\s*(?:mm|cm|m).*")) {
            return "mẫu";
        }
        if (nameLow.startsWith("gói ") || hasAnatomicalScope || (durationMin != null && durationMin > 0)
                || unitLow.contains("gói") || unitLow.contains("trọn gói")
                || headLow.contains("spa") || headLow.contains("dịch vụ")) {
            return "gói";
        }
        return "sản phẩm";
    }

    private static RegionScope inferRegionScopeFromCode(String region) {
        if (region == null || region.isBlank() || "UNSPECIFIED".equalsIgnoreCase(region.strip())) {
            return RegionScope.UNSPECIFIED;
        }
        String up = region.strip().toUpperCase(Locale.ROOT);
        if ("GLOBAL".equals(up) || "GLOBAL_CONFIRMED".equals(up) || "ALL".equals(up)) {
            return RegionScope.GLOBAL_CONFIRMED;
        }
        return RegionScope.BRANCH_SPECIFIC;
    }

    /**
     * Trích xuất danh sách sản phẩm/dịch vụ đã được kiểm chứng giá ({@code ParseStatus.VERIFIED}) để sử dụng an toàn ở runtime.
     */
    public static List<PackageSummary> extractCatalog(
            List<ReciprocalRankFusion.FusedChunk> chunks,
            List<String> domainKeywords
    ) {
        return extractCatalogWithDiagnostics(chunks, domainKeywords).stream()
                .filter(p -> p != null && p.parseStatus() == ParseStatus.VERIFIED && p.priceAmountVnd() > 0L)
                .toList();
    }

    /**
     * Trích xuất toàn bộ danh mục kèm chẩn đoán {@link ParseStatus} ({@code VERIFIED}, {@code NEEDS_VERIFICATION}, {@code FAILED})
     * để kiểm toán chất lượng bảng giá mà không âm thầm biến dòng lỗi thành con số sai.
     */
    public static List<PackageSummary> extractCatalogWithDiagnostics(
            List<ReciprocalRankFusion.FusedChunk> chunks,
            List<String> domainKeywords
    ) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<RawParsedEntry> rawEntries = new ArrayList<>();
        for (ReciprocalRankFusion.FusedChunk fc : chunks) {
            if (fc == null || fc.chunk() == null || fc.chunk().text() == null) {
                continue;
            }
            List<String> headingPath = fc.chunk().headingPath() != null ? fc.chunk().headingPath() : List.of();
            String text = fc.chunk().text();
            int beforeCount = rawEntries.size();
            parseMarkdownTablePackages(text, headingPath, domainKeywords, rawEntries);
            if (rawEntries.size() == beforeCount) {
                parseStructuredBulletPackages(text, headingPath, domainKeywords, rawEntries);
            }
        }
        if (rawEntries.isEmpty()) {
            return List.of();
        }

        boolean catalogHasSpaDsAndNsTiering = rawEntries.stream()
                .anyMatch(e -> e.skuCode != null && SPA_DS_TIER_CODE_PATTERN.matcher(e.skuCode).matches())
                && rawEntries.stream()
                .anyMatch(e -> e.skuCode != null && SPA_NS_TIER_CODE_PATTERN.matcher(e.skuCode).matches());

        Map<String, PackageSummary> byUid = new LinkedHashMap<>();
        for (RawParsedEntry entry : rawEntries) {
            PackageSummary summary = finalizeEntry(entry, catalogHasSpaDsAndNsTiering);
            PackageSummary existing = byUid.get(summary.packageUid());
            if (existing == null || scoreRichness(summary) > scoreRichness(existing)) {
                byUid.put(summary.packageUid(), summary);
            }
        }
        return new ArrayList<>(byUid.values());
    }

    public static List<PackageSummary> filterByRegion(List<PackageSummary> catalog, String preferredRegion) {
        return filterForRegion(catalog, preferredRegion);
    }

    /**
     * Lọc danh mục theo chi nhánh/khu vực mà KHÔNG làm rơi các sản phẩm {@link RegionScope#UNSPECIFIED}
     * hoặc {@link RegionScope#GLOBAL_CONFIRMED}.
     */
    public static List<PackageSummary> filterForRegion(List<PackageSummary> catalog, String preferredRegion) {
        if (catalog == null || catalog.isEmpty()) {
            return List.of();
        }
        List<PackageSummary> nonAddons = catalog.stream()
                .filter(p -> p != null && !p.isAddon())
                .toList();
        if (nonAddons.isEmpty()) {
            return List.of();
        }
        if (preferredRegion == null || preferredRegion.isBlank() || "UNSPECIFIED".equalsIgnoreCase(preferredRegion.strip())) {
            return nonAddons;
        }
        String reg = preferredRegion.strip().toUpperCase(Locale.ROOT);
        List<PackageSummary> filtered = nonAddons.stream()
                .filter(p -> {
                    RegionScope scope = p.regionScope();
                    if (scope == RegionScope.UNSPECIFIED || scope == RegionScope.GLOBAL_CONFIRMED) {
                        return true;
                    }
                    return reg.equalsIgnoreCase(p.region());
                })
                .toList();
        if (!filtered.isEmpty()) {
            return filtered;
        }
        return nonAddons;
    }

    public static List<PackageSummary> findAlternativePackages(
            List<PackageSummary> catalog,
            String preferredRegion,
            String serviceFamily,
            Set<String> mentionedOrRejectedNames,
            Set<String> rejectedDurations
    ) {
        List<PackageSummary> regional = filterForRegion(catalog, preferredRegion);
        if (regional.isEmpty()) {
            return List.of();
        }
        Set<String> excludedLower = new LinkedHashSet<>();
        if (mentionedOrRejectedNames != null) {
            for (String m : mentionedOrRejectedNames) {
                if (m != null && !m.isBlank()) {
                    excludedLower.add(m.strip().toLowerCase(Locale.ROOT));
                }
            }
        }
        Set<String> rejDurLower = new LinkedHashSet<>();
        if (rejectedDurations != null) {
            for (String d : rejectedDurations) {
                if (d != null && !d.isBlank()) {
                    rejDurLower.add(d.strip().toLowerCase(Locale.ROOT));
                }
            }
        }

        List<PackageSummary> candidates = new ArrayList<>();
        for (PackageSummary p : regional) {
            String codeLow = p.code() != null ? p.code().toLowerCase(Locale.ROOT) : "";
            String uidLow = p.packageUid() != null ? p.packageUid().toLowerCase(Locale.ROOT) : "";
            String nameLow = p.displayName() != null ? p.displayName().toLowerCase(Locale.ROOT) : "";
            String canonLow = p.canonicalLabel().toLowerCase(Locale.ROOT);
            String durLow = p.durationLabel() != null ? p.durationLabel().toLowerCase(Locale.ROOT) : "";

            boolean alreadyUsed = excludedLower.stream().anyMatch(ex ->
                    (!ex.isEmpty() && (ex.equals(codeLow) || ex.equals(uidLow)
                            || (!nameLow.isEmpty() && (ex.contains(nameLow) || nameLow.contains(ex)))
                            || (!canonLow.isEmpty() && (ex.contains(canonLow) || canonLow.contains(ex))))));
            boolean durRejected = !durLow.isEmpty() && rejDurLower.contains(durLow);
            if (!alreadyUsed && !durRejected) {
                candidates.add(p);
            }
        }

        if (serviceFamily != null && !serviceFamily.isBlank()) {
            String sfLow = serviceFamily.strip().toLowerCase(Locale.ROOT);
            List<PackageSummary> sameFamily = candidates.stream()
                    .filter(p -> p.serviceFamily() != null && (
                            p.serviceFamily().toLowerCase(Locale.ROOT).contains(sfLow)
                            || sfLow.contains(p.serviceFamily().toLowerCase(Locale.ROOT))
                            || (p.displayName() != null && p.displayName().toLowerCase(Locale.ROOT).contains(sfLow))))
                    .sorted(Comparator.comparingLong(PackageSummary::priceVnd))
                    .toList();
            if (!sameFamily.isEmpty()) {
                return sameFamily;
            }
        }
        candidates.sort(Comparator.comparingLong(PackageSummary::priceVnd));
        return candidates;
    }

    public static String formatCompactPrice(long priceVnd) {
        if (priceVnd <= 0) {
            return "";
        }
        if (priceVnd % 1_000L == 0L) {
            long kVal = priceVnd / 1_000L;
            if (kVal >= 1_000L) {
                return String.format(Locale.ROOT, "%,dk", kVal).replace(',', '.');
            }
            return kVal + "k";
        }
        return String.format(Locale.ROOT, "%,dđ", priceVnd).replace(',', '.');
    }

    public static String formatPriceWithUnit(long priceVnd, PriceType priceType, String billingUnit) {
        if (priceType == PriceType.CONTACT || priceVnd <= 0L) {
            return "Liên hệ";
        }
        String compact = formatCompactPrice(priceVnd);
        String unit = (billingUnit != null && !billingUnit.isBlank() && !"trọn gói".equalsIgnoreCase(billingUnit.strip()))
                ? billingUnit.strip()
                : "";
        if (priceType == PriceType.STARTING_FROM) {
            return "Từ " + compact + unit;
        }
        return compact + unit;
    }

    /**
     * Bộ đọc giá đa ngành theo thứ tự ưu tiên tất định:
     * - Phân biệt dấu phân cách hàng nghìn {@code .} ("3.500k" -> 3.500.000, "3.500.000đ" -> 3.500.000)
     *   với dấu thập phân {@code ,} hoặc {@code .} đi trước hậu tố ("3,5 triệu" -> 3.500.000, "3tr5" -> 3.500.000).
     * - Tách rõ {@link PriceType} ({@code FIXED}, {@code PER_UNIT}, {@code STARTING_FROM}, {@code CONTACT})
     *   và {@code billingUnit} ({@code "/m²"}, {@code "/khóa"}, {@code "trọn gói"}...).
     * - Không âm thầm biến chuỗi giá lỗi/mơ hồ thành con số sai.
     */
    public static ParsedPrice parseStructuredPrice(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ParsedPrice(0L, PriceType.FIXED, "", ParseStatus.FAILED, "EMPTY_PRICE_TEXT");
        }
        String text = raw.strip();
        if (CONTACT_PRICE_PATTERN.matcher(text).matches()) {
            return new ParsedPrice(0L, PriceType.CONTACT, "", ParseStatus.NEEDS_VERIFICATION, "CONTACT_FOR_PRICE");
        }
        if (MALFORMED_DOT_THOUSAND_BEFORE_K_PATTERN.matcher(text).find()) {
            return new ParsedPrice(0L, PriceType.FIXED, "", ParseStatus.FAILED, "MALFORMED_THOUSAND_SEPARATOR");
        }

        boolean isStartingFrom = STARTING_FROM_PREFIX_PATTERN.matcher(text).find()
                || text.strip().startsWith("~");

        long amountVnd = 0L;
        int matchEnd = -1;

        Matcher splitMillionMatcher = SPLIT_MILLION_PATTERN.matcher(text);
        if (splitMillionMatcher.find()) {
            long wholeMillions = Long.parseLong(splitMillionMatcher.group(1));
            String fracStr = splitMillionMatcher.group(2);
            long scale = fracStr.length() == 1 ? 100_000L : (fracStr.length() == 2 ? 10_000L : 1_000L);
            amountVnd = wholeMillions * 1_000_000L + Long.parseLong(fracStr) * scale;
            matchEnd = splitMillionMatcher.end();
        }

        if (amountVnd <= 0L) {
            Matcher millionMatcher = SUFFIX_MILLION_PATTERN.matcher(text);
            if (millionMatcher.find()) {
                BigDecimal bd = parseVietnameseLocalizedDecimal(millionMatcher.group(1));
                if (bd == null || bd.compareTo(BigDecimal.ZERO) <= 0) {
                    return new ParsedPrice(0L, PriceType.FIXED, "", ParseStatus.FAILED, "INVALID_MILLION_NUMBER");
                }
                amountVnd = bd.multiply(BigDecimal.valueOf(1_000_000L))
                        .setScale(0, RoundingMode.HALF_UP)
                        .longValueExact();
                matchEnd = millionMatcher.end();
            }
        }

        if (amountVnd <= 0L) {
            Matcher thousandMatcher = SUFFIX_THOUSAND_PATTERN.matcher(text);
            if (thousandMatcher.find()) {
                String numToken = thousandMatcher.group(1);
                if (numToken.matches("\\d{1,3}\\.\\d{1,2}") && !numToken.matches("\\d{1,3}\\.\\d{3}")) {
                    // Ví dụ "3.50k" là định dạng mơ hồ giữa dấu chấm hàng nghìn bị thiếu số và số thập phân kiểu Anh
                    if (numToken.endsWith("0")) {
                        return new ParsedPrice(0L, PriceType.FIXED, "", ParseStatus.NEEDS_VERIFICATION, "AMBIGUOUS_DOT_BEFORE_K");
                    }
                }
                BigDecimal bd = parseVietnameseLocalizedDecimal(numToken);
                if (bd == null || bd.compareTo(BigDecimal.ZERO) <= 0) {
                    return new ParsedPrice(0L, PriceType.FIXED, "", ParseStatus.FAILED, "INVALID_THOUSAND_NUMBER");
                }
                amountVnd = bd.multiply(BigDecimal.valueOf(1_000L))
                        .setScale(0, RoundingMode.HALF_UP)
                        .longValueExact();
                matchEnd = thousandMatcher.end();
            }
        }

        if (amountVnd <= 0L) {
            Matcher fullVndMatcher = FULL_VND_AMOUNT_PATTERN.matcher(text);
            if (fullVndMatcher.find()) {
                String digitsOnly = fullVndMatcher.group(1).replaceAll("[^0-9]", "");
                try {
                    long candidate = Long.parseLong(digitsOnly);
                    boolean hasExplicitCurrency = text.toLowerCase(Locale.ROOT).matches(".*(?:vnđ|vnd|đ\\b|đ/|đ\\s|đ$|đồng).*");
                    if (candidate < 10_000L && !hasExplicitCurrency) {
                        return new ParsedPrice(0L, PriceType.FIXED, "", ParseStatus.NEEDS_VERIFICATION, "SUSPICIOUSLY_LOW_UNLABELLED_VND");
                    }
                    amountVnd = candidate;
                    matchEnd = fullVndMatcher.end();
                } catch (NumberFormatException e) {
                    return new ParsedPrice(0L, PriceType.FIXED, "", ParseStatus.FAILED, "VND_OVERFLOW");
                }
            }
        }

        if (amountVnd <= 0L) {
            return new ParsedPrice(0L, PriceType.FIXED, "", ParseStatus.FAILED, "UNPARSEABLE_PRICE");
        }

        // Kiểm tra xem có phải khoảng giá "450k - 600k" hay không
        String tail = matchEnd >= 0 && matchEnd < text.length() ? text.substring(matchEnd) : "";
        if (tail.stripLeading().matches("^(?:[-–~]|đến|tới)\\s*\\d+.*")) {
            isStartingFrom = true;
        }

        String billingUnit = extractBillingUnit(text, tail);
        PriceType resolvedType;
        if (isStartingFrom) {
            resolvedType = PriceType.STARTING_FROM;
        } else if (!billingUnit.isEmpty() && !"trọn gói".equalsIgnoreCase(billingUnit)) {
            resolvedType = PriceType.PER_UNIT;
        } else {
            resolvedType = PriceType.FIXED;
        }

        return new ParsedPrice(amountVnd, resolvedType, billingUnit, ParseStatus.VERIFIED, "");
    }

    private static BigDecimal parseVietnameseLocalizedDecimal(String rawNum) {
        if (rawNum == null || rawNum.isBlank()) {
            return null;
        }
        String s = rawNum.strip().replaceAll("\\s+", "");
        try {
            if (s.contains(".") && s.contains(",")) {
                // Chuẩn Việt Nam: 1.250,5 -> bỏ dấu chấm hàng nghìn, đổi dấu phẩy thập phân thành chấm
                String normalized = s.replace(".", "").replace(',', '.');
                return new BigDecimal(normalized);
            }
            if (s.contains(",")) {
                // Nếu đúng định dạng hàng nghìn kiểu Anh "3,500,000" (nhiều nhóm 3 chữ số)
                if (s.matches("^\\d{1,3}(?:,\\d{3}){2,}$")) {
                    return new BigDecimal(s.replace(",", ""));
                }
                return new BigDecimal(s.replace(',', '.'));
            }
            if (s.contains(".")) {
                // Nếu dấu chấm theo sau bởi đúng các nhóm 3 chữ số ("3.500", "1.250.000") -> dấu phân cách hàng nghìn!
                if (s.matches("^\\d{1,3}(?:\\.\\d{3})+$")) {
                    return new BigDecimal(s.replace(".", ""));
                }
                // Ngược lại (ví dụ "3.5" triệu) -> dấu thập phân
                return new BigDecimal(s);
            }
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String extractBillingUnit(String fullText, String tailAfterAmount) {
        if (tailAfterAmount != null && !tailAfterAmount.isBlank()) {
            Matcher m = BILLING_UNIT_AFTER_PRICE_PATTERN.matcher(tailAfterAmount);
            if (m.find()) {
                return "/" + normalizeUnitToken(m.group(1));
            }
            // Trường hợp khoảng giá "450k - 600k/m²" -> quét phần sau số thứ hai
            Matcher slashAnywhereInTail = Pattern.compile(
                    "(?iU)(?:/|trên\\s+)\\s*(m²|m2|mét\\s*vuông|m3|m³|khóa|khoá|buổi|tháng|năm|tuần|ngày|giờ|ca|suất|vé|người|học\\s*viên|hộp|thùng|viên|tấm|bộ|cái|chiếc|lần)"
            ).matcher(tailAfterAmount);
            if (slashAnywhereInTail.find()) {
                return "/" + normalizeUnitToken(slashAnywhereInTail.group(1));
            }
        }
        if (fullText != null && fullText.toLowerCase(Locale.ROOT).contains("trọn gói")) {
            return "trọn gói";
        }
        return "";
    }

    private static String normalizeUnitToken(String rawUnit) {
        if (rawUnit == null) return "";
        String u = rawUnit.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if ("m2".equals(u) || "mét vuông".equals(u)) {
            return "m²";
        }
        if ("m3".equals(u) || "mét khối".equals(u)) {
            return "m³";
        }
        if ("khoá".equals(u)) {
            return "khóa";
        }
        return u;
    }

    public static long parsePriceVnd(String raw) {
        ParsedPrice parsed = parseStructuredPrice(raw);
        return parsed.parseStatus() == ParseStatus.VERIFIED ? parsed.priceAmountVnd() : 0L;
    }

    public static Integer parseDurationMinutes(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher m = DURATION_MIN_PATTERN.matcher(raw.strip());
        if (m.find()) {
            String digits = m.group(1).replaceAll("[^0-9]", "");
            try {
                return Integer.parseInt(digits);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private record RegionResolution(RegionScope scope, String regionCode) {
    }

    private record RawParsedEntry(
            String skuCode,
            String rawName,
            String rawPriceText,
            String rawUnitColumnText,
            String primarySpecHeader,
            String primarySpecText,
            Map<String, String> extraSpecColumns,
            String includesText,
            String formatOrSpaceText,
            String highlightText,
            String suitableForText,
            String detailsOrProcessText,
            String explicitRoleText,
            List<String> headingPath,
            RegionResolution regionInfo,
            String categoryOrFamily,
            List<String> domainKeywords
    ) {
    }

    private static void parseMarkdownTablePackages(
            String text,
            List<String> headingPath,
            List<String> domainKeywords,
            List<RawParsedEntry> outEntries
    ) {
        String[] lines = text.split("\\r?\\n");
        List<String> headers = null;
        String headingJoined = String.join(" > ", headingPath);
        String headingLower = headingJoined.toLowerCase(Locale.ROOT);
        if (headingLower.contains("quy trình chi tiết") && !headingLower.contains("cơ sở") && !headingLower.contains("khu vực")) {
            return;
        }
        RegionResolution regionInfo = inferRegionInfo(headingLower);
        String defaultFamily = inferServiceFamily(headingPath);

        int idxCol = -1;
        int skuCol = -1;
        int nameCol = -1;
        int priceCol = -1;
        int unitCol = -1;
        int roleCol = -1;

        for (String rawLine : lines) {
            String line = rawLine.strip();
            if (!line.startsWith("|") || !line.endsWith("|")) {
                headers = null;
                continue;
            }
            if (line.replaceAll("[|\\-:\\s]", "").isEmpty()) {
                continue;
            }
            List<String> cells = splitMarkdownCells(line);
            if (cells.size() < 2) {
                continue;
            }
            String firstCell = cells.get(0).strip();

            if (headers == null) {
                String joinedRowLower = String.join(" | ", cells).toLowerCase(Locale.ROOT);
                boolean firstLooksLikeSku = isLikelySkuCode(firstCell);
                boolean hasHeaderSignals = !firstLooksLikeSku && (
                        SKU_HEADER_PATTERN.matcher(firstCell).matches()
                        || INDEX_HEADER_PATTERN.matcher(firstCell).matches()
                        || cells.stream().anyMatch(c -> PRICE_HEADER_PATTERN.matcher(c.strip()).matches())
                );
                if (hasHeaderSignals) {
                    if (NON_CATALOG_TABLE_HEADER_PATTERN.matcher(joinedRowLower).matches()) {
                        return;
                    }
                    headers = cells;
                    idxCol = findColumnIndex(headers, INDEX_HEADER_PATTERN);
                    skuCol = findColumnIndex(headers, SKU_HEADER_PATTERN);
                    priceCol = findColumnIndex(headers, PRICE_HEADER_PATTERN);
                    unitCol = findColumnIndexByKeywords(headers, List.of("đơn vị tính", "đơn vị", "đvt", "unit"));
                    roleCol = findColumnIndexByKeywords(headers, List.of("vai trò", "phân tầng", "phân khúc", "tier", "commercial role"));
                    nameCol = findNameColumnIndex(headers, idxCol, skuCol, priceCol);
                    continue;
                }
            }

            String skuCode = "";
            String rawName;
            String rawPriceCell;

            if (headers != null) {
                if (skuCol >= 0 && skuCol < cells.size()) {
                    String candSku = cells.get(skuCol).strip().toUpperCase(Locale.ROOT);
                    if (isLikelySkuCode(candSku)) {
                        skuCode = candSku;
                    } else if (!candSku.isEmpty() && nameCol != skuCol) {
                        // Cột Mã SP có dòng tiêu đề phụ không phải mã hợp lệ
                        continue;
                    }
                } else if (idxCol == -1 && isLikelySkuCode(firstCell.toUpperCase(Locale.ROOT)) && nameCol != 0) {
                    skuCode = firstCell.toUpperCase(Locale.ROOT);
                }

                // Nếu bảng không có cột SKU VÀ cũng không có cột Giá hợp lệ -> không phải bảng danh mục sản phẩm
                if (skuCol < 0 && priceCol < 0) {
                    continue;
                }
                int resolvedNameIdx = nameCol >= 0 ? nameCol : (skuCol == 0 ? 1 : (idxCol == 0 ? 1 : 0));
                if (resolvedNameIdx >= cells.size()) {
                    continue;
                }
                rawName = cells.get(resolvedNameIdx).strip();
                int resolvedPriceIdx = priceCol >= 0 ? priceCol : (resolvedNameIdx + 1 < cells.size() ? resolvedNameIdx + 1 : -1);
                rawPriceCell = (resolvedPriceIdx >= 0 && resolvedPriceIdx < cells.size()) ? cells.get(resolvedPriceIdx).strip() : "";
            } else {
                // Bảng không có dòng tiêu đề nhưng cột 0 là SKU chuẩn
                if (cells.size() < 3 || !isLikelySkuCode(firstCell.toUpperCase(Locale.ROOT))) {
                    continue;
                }
                skuCode = firstCell.toUpperCase(Locale.ROOT);
                rawName = cells.get(1).strip();
                rawPriceCell = cells.get(2).strip();
            }

            if (rawName.isBlank() || rawName.endsWith("?")) {
                continue;
            }

            String rawUnitText = (headers != null && unitCol >= 0 && unitCol < cells.size()) ? cells.get(unitCol).strip() : "";
            String explicitRoleText = (headers != null && roleCol >= 0 && roleCol < cells.size()) ? cells.get(roleCol).strip() : "";

            // Thu thập cột quy cách / thông số động (Thời gian, Thời lượng, Kích thước, Quy cách, Số buổi, Diện tích...)
            String primarySpecHeader = "";
            String primarySpecText = "";
            Map<String, String> extraSpecs = new LinkedHashMap<>();
            if (headers != null) {
                for (int i = 0; i < headers.size() && i < cells.size(); i++) {
                    if (i == idxCol || i == skuCol || i == nameCol || i == priceCol || i == unitCol || i == roleCol) {
                        continue;
                    }
                    String hRaw = headers.get(i).strip();
                    String hLow = hRaw.toLowerCase(Locale.ROOT);
                    String val = cells.get(i).strip();
                    if (val.isBlank()) continue;
                    if (isSpecificationHeader(hLow)) {
                        if (primarySpecText.isEmpty()) {
                            primarySpecHeader = hRaw;
                            primarySpecText = val;
                        } else {
                            extraSpecs.put(hRaw, val);
                        }
                    }
                }
            } else if (cells.size() > 3) {
                primarySpecText = cells.get(3).strip();
            }

            String includesCell = findCellByHeaderKeywords(cells, headers, List.of("bao gồm", "nội dung", "quyền lợi", "đi kèm"), headers == null && cells.size() > 4 ? 4 : -1);
            String spaceOrFormatCell = findCellByHeaderKeywords(cells, headers, List.of("không gian", "phòng", "hình thức"), -1);
            String highlightCell = findCellByHeaderKeywords(cells, headers, List.of("nổi bật", "ưu điểm", "đặc điểm"), -1);
            String suitableCell = findCellByHeaderKeywords(cells, headers, List.of("phù hợp", "ứng dụng", "khu vực ốp", "khu vực", "vùng chăm sóc", "vùng tác động", "phạm vi", "đối tượng", "mục tiêu"), -1);
            String processOrDetailCell = findCellByHeaderKeywords(cells, headers, List.of("quy trình", "chi tiết", "mô tả", "thông số"), -1);

            outEntries.add(new RawParsedEntry(
                    skuCode,
                    rawName,
                    rawPriceCell,
                    rawUnitText,
                    primarySpecHeader,
                    primarySpecText,
                    extraSpecs,
                    includesCell,
                    spaceOrFormatCell,
                    highlightCell,
                    suitableCell,
                    processOrDetailCell,
                    explicitRoleText,
                    headingPath,
                    regionInfo,
                    defaultFamily,
                    domainKeywords
            ));
        }
    }

    private static void parseStructuredBulletPackages(
            String text,
            List<String> headingPath,
            List<String> domainKeywords,
            List<RawParsedEntry> outEntries
    ) {
        String headingJoined = String.join(" > ", headingPath);
        String headingLower = headingJoined.toLowerCase(Locale.ROOT);
        if (headingLower.contains("faq")
                || headingLower.contains("câu hỏi thường gặp")
                || headingLower.contains("khuyến mãi")
                || headingLower.contains("ưu đãi")
                || headingLower.contains("quy trình chi tiết")) {
            return;
        }
        RegionResolution regionInfo = inferRegionInfo(headingLower);
        String defaultFamily = inferServiceFamily(headingPath);

        String[] lines = text.split("\\r?\\n");
        for (String rawLine : lines) {
            Matcher bm = BULLET_LINE_PATTERN.matcher(rawLine);
            if (!bm.matches()) {
                continue;
            }
            String body = bm.group(1).strip();
            if (body.isEmpty() || body.endsWith("?")) {
                continue;
            }
            String bodyLow = body.toLowerCase(Locale.ROOT);
            if (bodyLow.startsWith("mua từ ")
                    || bodyLow.startsWith("giảm ")
                    || bodyLow.startsWith("lưu ý")
                    || bodyLow.startsWith("chính sách")
                    || bodyLow.startsWith("bước ")) {
                continue;
            }

            String[] segments = body.split("\\s*(?:—|–|\\||(?<=\\s)-(?=\\s))\\s*");
            if (segments.length < 2) {
                int colonIdx = body.lastIndexOf(':');
                if (colonIdx > 3 && colonIdx < body.length() - 2) {
                    segments = new String[]{body.substring(0, colonIdx).strip(), body.substring(colonIdx + 1).strip()};
                } else {
                    continue;
                }
            }

            String firstSeg = segments[0].strip();
            String skuCode = "";
            String rawName = firstSeg;
            Matcher skuMatcher = LEADING_BRACKET_OR_COLON_SKU_PATTERN.matcher(firstSeg);
            if (skuMatcher.matches()) {
                String bracketSku = skuMatcher.group(1);
                String prefixSku = skuMatcher.group(2);
                String candidateSku = (bracketSku != null ? bracketSku : prefixSku).strip().toUpperCase(Locale.ROOT);
                if (isLikelySkuCode(candidateSku)) {
                    skuCode = candidateSku;
                    rawName = skuMatcher.group(3).strip();
                }
            } else {
                Matcher trailingParenSku = Pattern.compile("^(.+?)\\s*\\(([A-Za-z]{2,10}(?:[-_][A-Za-z0-9]{1,12})+|(?:[A-Za-z]{2,6}\\d{1,5}))\\)\\s*$")
                        .matcher(firstSeg);
                if (trailingParenSku.matches()) {
                    String candSku = trailingParenSku.group(2).strip().toUpperCase(Locale.ROOT);
                    if (isLikelySkuCode(candSku)) {
                        rawName = trailingParenSku.group(1).strip();
                        skuCode = candSku;
                    }
                }
            }

            String rawPriceSeg = "";
            String primarySpecText = "";
            String includesText = "";
            String suitableText = "";
            String formatText = "";
            String explicitRoleText = "";
            List<String> extraDetails = new ArrayList<>();

            for (int i = 1; i < segments.length; i++) {
                String seg = segments[i].strip();
                if (seg.isEmpty()) continue;
                String segLow = seg.toLowerCase(Locale.ROOT);
                String strippedPriceCandidate = seg.replaceFirst("(?iU)^(?:đơn\\s*giá|mức\\s*giá|giá\\s*bán|giá|học\\s*phí|chi\\s*phí)\\s*:\\s*", "").strip();

                if (rawPriceSeg.isEmpty()) {
                    ParsedPrice tryPrice = parseStructuredPrice(strippedPriceCandidate);
                    if (tryPrice.parseStatus() == ParseStatus.VERIFIED
                            || tryPrice.priceType() == PriceType.CONTACT
                            || segLow.matches("^(?:đơn\\s*giá|giá|học\\s*phí)\\s*:.*")) {
                        rawPriceSeg = strippedPriceCandidate;
                        continue;
                    }
                }
                if (segLow.startsWith("quy cách:") || segLow.startsWith("kích thước:") || segLow.startsWith("thời lượng:") || segLow.startsWith("số buổi:")) {
                    primarySpecText = seg.substring(seg.indexOf(':') + 1).strip();
                } else if (segLow.startsWith("bao gồm:") || segLow.startsWith("quyền lợi:")) {
                    includesText = seg.substring(seg.indexOf(':') + 1).strip();
                } else if (segLow.startsWith("phù hợp:") || segLow.startsWith("ứng dụng:") || segLow.startsWith("mục tiêu:") || segLow.startsWith("vùng chăm sóc:") || segLow.startsWith("phạm vi:") || segLow.startsWith("khu vực:")) {
                    suitableText = seg.substring(seg.indexOf(':') + 1).strip();
                } else if (segLow.startsWith("hình thức:") || segLow.startsWith("không gian:")) {
                    formatText = seg.substring(seg.indexOf(':') + 1).strip();
                } else if (segLow.startsWith("vai trò:") || segLow.startsWith("phân tầng:")) {
                    explicitRoleText = seg.substring(seg.indexOf(':') + 1).strip();
                } else if (primarySpecText.isEmpty() && (DIMENSION_SPEC_PATTERN.matcher(seg).find() || GENERIC_QUANTITY_UNIT_SPEC_PATTERN.matcher(seg).find())) {
                    primarySpecText = seg;
                } else {
                    extraDetails.add(seg);
                }
            }

            if (rawName.isBlank() || rawPriceSeg.isBlank()) {
                continue;
            }

            outEntries.add(new RawParsedEntry(
                    skuCode,
                    rawName,
                    rawPriceSeg,
                    "",
                    "",
                    primarySpecText,
                    Map.of(),
                    includesText,
                    formatText,
                    "",
                    suitableText,
                    String.join(" | ", extraDetails),
                    explicitRoleText,
                    headingPath,
                    regionInfo,
                    defaultFamily,
                    domainKeywords
            ));
        }
    }

    private static PackageSummary finalizeEntry(RawParsedEntry entry, boolean catalogHasSpaDsAndNsTiering) {
        String combinedPriceInput = entry.rawPriceText;
        if (entry.rawUnitColumnText != null && !entry.rawUnitColumnText.isBlank()
                && !combinedPriceInput.contains("/")) {
            String unitClean = entry.rawUnitColumnText.strip();
            if (!unitClean.equalsIgnoreCase("vnđ") && !unitClean.equalsIgnoreCase("vnd") && !unitClean.equalsIgnoreCase("đ")) {
                combinedPriceInput = combinedPriceInput + "/" + unitClean.replaceFirst("^/+", "");
            }
        }

        ParsedPrice parsedPrice = parseStructuredPrice(combinedPriceInput);
        long priceVnd = parsedPrice.priceAmountVnd();

        List<SpecificationAttr> specs = extractSpecifications(
                entry.rawName,
                entry.primarySpecHeader,
                entry.primarySpecText,
                entry.extraSpecColumns
        );

        Integer durationMin = parseDurationMinutes(entry.primarySpecText);
        if (durationMin == null) {
            durationMin = parseDurationMinutes(entry.rawName);
        }

        String primarySpecLabel;
        if (durationMin != null && durationMin > 0) {
            boolean hasApprox = entry.primarySpecText != null && entry.primarySpecText.strip().startsWith("~");
            primarySpecLabel = (hasApprox ? "~" : "") + durationMin + " phút";
        } else if (entry.primarySpecText != null && !entry.primarySpecText.isBlank()) {
            primarySpecLabel = entry.primarySpecText.strip();
        } else if (!specs.isEmpty()) {
            primarySpecLabel = specs.get(0).rawValue();
        } else {
            primarySpecLabel = "";
        }

        String effectiveCode = (entry.skuCode != null && !entry.skuCode.isBlank())
                ? entry.skuCode
                : buildAutoCodeFromName(entry.rawName, primarySpecLabel);
        String regionLegacyField = entry.regionInfo.scope() == RegionScope.BRANCH_SPECIFIC
                ? entry.regionInfo.regionCode()
                : (entry.regionInfo.scope() == RegionScope.GLOBAL_CONFIRMED ? "GLOBAL" : "UNSPECIFIED");
        String productId = buildDeterministicProductId(regionLegacyField, entry.skuCode, entry.rawName, primarySpecLabel);

        boolean isAddon = (entry.skuCode != null && entry.skuCode.startsWith("ADD"))
                || primarySpecLabel.toLowerCase(Locale.ROOT).contains("theo liệu trình")
                || (entry.explicitRoleText != null && entry.explicitRoleText.toLowerCase(Locale.ROOT).contains("addon"));

        String combinedRowText = (entry.rawName + " " + primarySpecLabel + " " + entry.includesText + " "
                + entry.highlightText + " " + entry.suitableForText + " " + entry.detailsOrProcessText)
                .toLowerCase(Locale.ROOT);
        List<String> scopeTags = extractScopeTagsFromRow(combinedRowText, entry.suitableForText, entry.domainKeywords);

        // Xử lý không gian / hình thức: CHỈ khử mâu thuẫn "Phòng chung" vs "Phòng VIP" khi bảng thực sự có cột Phòng của Spa
        String normalizedSpaceOrFormat = entry.formatOrSpaceText != null ? entry.formatOrSpaceText.strip() : "";
        String roomLower = normalizedSpaceOrFormat.toLowerCase(Locale.ROOT);
        String rawIncludesCell = entry.includesText != null ? entry.includesText.strip() : "";
        String rawIncLower = rawIncludesCell.toLowerCase(Locale.ROOT);
        boolean hasExplicitSpaRoomColumn = !roomLower.isEmpty() && (
                roomLower.contains("phòng") || roomLower.contains("khu dưỡng sinh") || roomLower.contains("khu gội") || roomLower.contains("rèm") || roomLower.contains("vip")
        );
        boolean hasBuffetInIncludes = rawIncLower.contains("buffet");
        boolean isVipRoom = hasExplicitSpaRoomColumn && roomLower.contains("vip") && !roomLower.contains("phòng chung");
        boolean isExplicitSharedSpaRoom = hasExplicitSpaRoomColumn && !isVipRoom && (
                roomLower.contains("phòng chung")
                || roomLower.contains("phòng cộng đồng")
                || roomLower.contains("khu dưỡng sinh")
                || roomLower.contains("khu gội chung")
                || roomLower.contains("rèm")
        );

        if (roomLower.contains("phòng chung")) {
            normalizedSpaceOrFormat = "Phòng chung yên tĩnh, sạch thoáng, mỗi giường đều có rèm che riêng kín đáo";
        }
        String safeHighlightCell = entry.highlightText != null ? entry.highlightText.strip() : "";
        if (isExplicitSharedSpaRoom && !safeHighlightCell.isBlank()) {
            safeHighlightCell = safeHighlightCell.replaceAll("(?iU)phòng\\s+vip(?:\\s+riêng(?:\\s+tư)?)?",
                    "không gian yên tĩnh, rèm che kín đáo");
            if (!hasBuffetInIncludes) {
                safeHighlightCell = safeHighlightCell.replaceAll("(?iU)(?:thưởng\\s+thức\\s+)?buffet\\s+thực\\s+dưỡng",
                        "ăn nhẹ thực dưỡng");
            }
        }

        String enrichedIncludes = rawIncludesCell;
        if (hasExplicitSpaRoomColumn) {
            String hlLower = safeHighlightCell.toLowerCase(Locale.ROOT);
            String incLower = enrichedIncludes.toLowerCase(Locale.ROOT);
            if (hlLower.contains("tắm xông") && !incLower.contains("tắm xông") && !incLower.contains("xông hơi")) {
                enrichedIncludes = enrichedIncludes.isEmpty() ? "Tắm xông thảo dược" : (enrichedIncludes + ", tắm xông thảo dược");
            }
            if (hlLower.contains("buffet thực dưỡng") && !incLower.contains("buffet") && isVipRoom) {
                enrichedIncludes = enrichedIncludes.isEmpty() ? "Buffet thực dưỡng" : (enrichedIncludes + ", buffet thực dưỡng");
            } else if ((hlLower.contains("ăn nhẹ thực dưỡng") || hlLower.contains("thực dưỡng"))
                    && !incLower.contains("thực dưỡng") && !incLower.contains("buffet")) {
                enrichedIncludes = enrichedIncludes.isEmpty() ? "Ăn nhẹ thực dưỡng" : (enrichedIncludes + ", ăn nhẹ thực dưỡng");
            } else if (hlLower.contains("ăn nhẹ") && !incLower.contains("ăn nhẹ") && !incLower.contains("buffet")) {
                enrichedIncludes = enrichedIncludes.isEmpty() ? "Ăn nhẹ sau liệu trình" : (enrichedIncludes + ", ăn nhẹ sau liệu trình");
            }
        }

        String safeProcessCell = entry.detailsOrProcessText != null ? entry.detailsOrProcessText.strip() : "";
        if (isExplicitSharedSpaRoom && !safeProcessCell.isBlank()) {
            safeProcessCell = safeProcessCell.replaceAll("(?iU)phòng\\s+vip(?:\\s+riêng(?:\\s+tư)?)?",
                    "không gian yên tĩnh, rèm che kín đáo");
            if (!hasBuffetInIncludes) {
                safeProcessCell = safeProcessCell.replaceAll("(?iU)(?:thưởng\\s+thức\\s+)?buffet\\s+thực\\s+dưỡng",
                        "ăn nhẹ thực dưỡng");
            }
        }
        String enrichedDetails = !safeProcessCell.isBlank()
                ? (!safeHighlightCell.isBlank() ? safeHighlightCell + " | " + safeProcessCell : safeProcessCell)
                : safeHighlightCell;

        CommercialRole commercialRole = inferCommercialRole(
                entry.skuCode,
                entry.explicitRoleText + " " + String.join(" ", entry.headingPath),
                entry.categoryOrFamily,
                isAddon,
                catalogHasSpaDsAndNsTiering
        );

        CatalogSourceData sourceLayer = new CatalogSourceData(
                entry.skuCode != null ? entry.skuCode : "",
                entry.rawName,
                entry.rawPriceText,
                entry.rawUnitColumnText != null ? entry.rawUnitColumnText : "",
                entry.primarySpecText != null ? entry.primarySpecText : "",
                entry.formatOrSpaceText != null ? entry.formatOrSpaceText : "",
                entry.headingPath != null ? List.copyOf(entry.headingPath) : List.of()
        );

        CatalogNormalizedData normalizedLayer = new CatalogNormalizedData(
                productId,
                entry.rawName.strip(),
                priceVnd,
                parsedPrice.priceType(),
                parsedPrice.billingUnit(),
                specs,
                parsedPrice.parseStatus(),
                parsedPrice.diagnosticNote()
        );

        CatalogInferredData inferredLayer = new CatalogInferredData(
                commercialRole,
                scopeTags,
                entry.regionInfo.scope(),
                entry.regionInfo.regionCode()
        );

        String formattedPriceWithUnit = parsedPrice.formattedCompactWithUnit();
        String priceDisplayLegacy = formatPriceWithUnit(priceVnd, parsedPrice.priceType(), parsedPrice.billingUnit());
        String priceLabel = resolvePriceLabel(
                parsedPrice.priceType(),
                parsedPrice.billingUnit(),
                combinedPriceInput,
                durationMin != null && durationMin > 0
        );
        String specsSummary = formatSpecificationsSummary(specs);
        boolean hasAnatomicalScope = hasExplicitSpaRoomColumn || scopeTags.stream().anyMatch(ANATOMICAL_SCOPE_TOKENS::contains);
        String scopeLabel = hasAnatomicalScope ? "Vùng chăm sóc" : "Phạm vi phù hợp";
        String formatLabel = hasExplicitSpaRoomColumn ? "Không gian" : "Hình thức";
        String customerFacingFormatNote = roomLower.contains("phòng chung")
                ? "không gian yên tĩnh, sạch thoáng, mỗi giường đều có rèm che riêng kín đáo"
                : normalizedSpaceOrFormat;
        String unitNoun = inferUnitNoun(
                entry.rawName,
                parsedPrice.billingUnit(),
                primarySpecLabel,
                durationMin,
                entry.headingPath,
                hasAnatomicalScope
        );

        CatalogDisplayData displayLayer = new CatalogDisplayData(
                priceLabel,
                formattedPriceWithUnit,
                specsSummary,
                scopeLabel,
                formatLabel,
                customerFacingFormatNote,
                unitNoun
        );

        return new PackageSummary(
                productId,
                effectiveCode,
                regionLegacyField,
                entry.rawName.strip(),
                entry.categoryOrFamily,
                scopeTags,
                durationMin,
                primarySpecLabel,
                priceVnd,
                priceDisplayLegacy,
                normalizedSpaceOrFormat,
                enrichedIncludes,
                entry.suitableForText != null ? entry.suitableForText.strip() : "",
                enrichedDetails,
                isAddon,
                sourceLayer,
                normalizedLayer,
                inferredLayer,
                displayLayer
        );
    }

    /**
     * Chỉ gán {@link CommercialRole#ECONOMICAL_OPTION} khi có căn cứ cấu trúc rõ ràng:
     * 1. Nhãn/cột/heading khai báo tường minh "tiết kiệm", "downsell", "economical", "hạ bậc".
     * 2. Hoặc quy ước phân tầng mã dịch vụ của Page (DS* vs NS* cùng tồn tại trong danh mục).
     * Tuyệt đối KHÔNG tự suy diễn từ sản phẩm rẻ nhất đối với sản phẩm ngang hàng ở các ngành khác.
     */
    private static CommercialRole inferCommercialRole(
            String skuCode,
            String explicitRoleAndHeadingText,
            String serviceFamily,
            boolean isAddon,
            boolean catalogHasSpaDsAndNsTiering
    ) {
        if (isAddon) {
            return CommercialRole.ADDON;
        }
        String roleContext = (explicitRoleAndHeadingText != null ? explicitRoleAndHeadingText : "").toLowerCase(Locale.ROOT);
        if (roleContext.contains("economical")
                || roleContext.contains("downsell")
                || roleContext.contains("hạ bậc")
                || roleContext.contains("phương án tiết kiệm")
                || roleContext.contains("gói tiết kiệm")) {
            return CommercialRole.ECONOMICAL_OPTION;
        }
        if (roleContext.contains("chủ lực") || roleContext.contains("primary")) {
            return CommercialRole.PRIMARY;
        }
        if (roleContext.contains("premium") || roleContext.contains("cao cấp")) {
            return CommercialRole.PREMIUM_OPTION;
        }
        String codeUp = skuCode != null ? skuCode.strip().toUpperCase(Locale.ROOT) : "";
        String famLow = serviceFamily != null ? serviceFamily.strip().toLowerCase(Locale.ROOT) : "";
        if (catalogHasSpaDsAndNsTiering) {
            if (SPA_DS_TIER_CODE_PATTERN.matcher(codeUp).matches() || "hair_neck_shoulder".equals(famLow)) {
                return CommercialRole.ECONOMICAL_OPTION;
            }
            if (SPA_NS_TIER_CODE_PATTERN.matcher(codeUp).matches() || "body_therapy".equals(famLow)) {
                return CommercialRole.PRIMARY;
            }
        }
        return CommercialRole.STANDARD;
    }

    private static String resolvePriceLabel(
            PriceType priceType,
            String billingUnit,
            String rawPriceInput,
            boolean hasSessionDuration
    ) {
        if (priceType == PriceType.CONTACT) {
            return "Giá liên hệ";
        }
        if (priceType == PriceType.STARTING_FROM) {
            return "Giá tham khảo";
        }
        if (priceType == PriceType.PER_UNIT) {
            return "Đơn giá";
        }
        String rawLow = rawPriceInput != null ? rawPriceInput.toLowerCase(Locale.ROOT) : "";
        if ("trọn gói".equalsIgnoreCase(billingUnit) || rawLow.contains("trọn gói") || hasSessionDuration) {
            return "Giá trọn gói";
        }
        return "Giá niêm yết";
    }

    private static List<SpecificationAttr> extractSpecifications(
            String rawName,
            String primarySpecHeader,
            String primarySpecText,
            Map<String, String> extraSpecColumns
    ) {
        Map<String, SpecificationAttr> specsByKey = new LinkedHashMap<>();
        if (primarySpecText != null && !primarySpecText.isBlank()) {
            SpecificationAttr primaryAttr = parseSingleSpecification(primarySpecHeader, primarySpecText);
            specsByKey.put(primaryAttr.key(), primaryAttr);
        }
        if (extraSpecColumns != null) {
            for (Map.Entry<String, String> e : extraSpecColumns.entrySet()) {
                if (e.getValue() != null && !e.getValue().isBlank()) {
                    SpecificationAttr attr = parseSingleSpecification(e.getKey(), e.getValue());
                    specsByKey.putIfAbsent(attr.key(), attr);
                }
            }
        }
        if (rawName != null && !rawName.isBlank()) {
            Matcher dimMatcher = DIMENSION_SPEC_PATTERN.matcher(rawName);
            if (dimMatcher.find()) {
                String val = (dimMatcher.group(1).replaceAll("\\s+", "") + dimMatcher.group(2).toLowerCase(Locale.ROOT));
                specsByKey.putIfAbsent("dimensions", new SpecificationAttr("dimensions", "Kích thước", val, null, dimMatcher.group(2).toLowerCase(Locale.ROOT)));
            }
            Matcher qtyMatcher = GENERIC_QUANTITY_UNIT_SPEC_PATTERN.matcher(rawName);
            while (qtyMatcher.find()) {
                String numRaw = qtyMatcher.group(1).replace("~", "").strip().replace(',', '.');
                String unitRaw = qtyMatcher.group(2).strip().toLowerCase(Locale.ROOT);
                String normUnit = "p".equals(unitRaw) ? "phút" : ("m2".equals(unitRaw) ? "m²" : unitRaw);
                Double numVal = null;
                try {
                    numVal = Double.parseDouble(numRaw);
                } catch (NumberFormatException ignored) {
                }
                String key = ("phút".equals(normUnit) || "giờ".equals(normUnit) || "tiếng".equals(normUnit))
                        ? "duration"
                        : ("buổi".equals(normUnit) ? "sessions" : "spec_" + slugifyAscii(normUnit));
                String label = "buổi".equals(normUnit) ? "Số buổi" : ("duration".equals(key) ? "Thời lượng" : "Quy cách");
                specsByKey.putIfAbsent(key, new SpecificationAttr(key, label, qtyMatcher.group().strip(), numVal, normUnit));
            }
        }
        return List.copyOf(specsByKey.values());
    }

    private static SpecificationAttr parseSingleSpecification(String headerLabel, String rawVal) {
        String cleanVal = rawVal.strip();
        String label = (headerLabel != null && !headerLabel.isBlank()) ? headerLabel.strip() : "";
        Matcher dimMatcher = DIMENSION_SPEC_PATTERN.matcher(cleanVal);
        if (dimMatcher.find()) {
            String effectiveLabel = label.isEmpty() ? "Kích thước" : label;
            return new SpecificationAttr("dimensions", effectiveLabel, cleanVal, null, dimMatcher.group(2).toLowerCase(Locale.ROOT));
        }
        Matcher qtyMatcher = GENERIC_QUANTITY_UNIT_SPEC_PATTERN.matcher(cleanVal);
        if (qtyMatcher.find()) {
            String numStr = qtyMatcher.group(1).replace("~", "").strip().replace(',', '.');
            String unitRaw = qtyMatcher.group(2).strip().toLowerCase(Locale.ROOT);
            String normUnit = "p".equals(unitRaw) ? "phút" : ("m2".equals(unitRaw) ? "m²" : unitRaw);
            Double numVal = null;
            try {
                numVal = Double.parseDouble(numStr);
            } catch (NumberFormatException ignored) {
            }
            String key = ("phút".equals(normUnit) || "giờ".equals(normUnit) || "tiếng".equals(normUnit))
                    ? "duration"
                    : ("buổi".equals(normUnit) ? "sessions" : slugifyAscii(label.isEmpty() ? normUnit : label));
            String effectiveLabel = !label.isEmpty()
                    ? label
                    : ("buổi".equals(normUnit) ? "Số buổi" : ("phút".equals(normUnit) ? "Thời lượng" : "Quy cách"));
            return new SpecificationAttr(key, effectiveLabel, cleanVal, numVal, normUnit);
        }
        String effectiveLabel = label.isEmpty() ? "Quy cách" : label;
        String key = slugifyAscii(effectiveLabel);
        return new SpecificationAttr(key.isEmpty() ? "specification" : key, effectiveLabel, cleanVal, null, "");
    }

    private static String formatSpecificationsSummary(List<SpecificationAttr> specs) {
        if (specs == null || specs.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>(specs.size());
        for (SpecificationAttr s : specs) {
            if (s == null || s.rawValue() == null || s.rawValue().isBlank()) continue;
            String lbl = (s.label() != null && !s.label().isBlank()) ? s.label().strip() : "Quy cách";
            parts.add(lbl + ": " + s.rawValue().strip());
        }
        return String.join(" | ", parts);
    }

    private static List<String> extractScopeTagsFromRow(
            String lowerRowText,
            String suitableForText,
            List<String> domainKeywords
    ) {
        Set<String> tags = new LinkedHashSet<>();

        // 1. Khớp từ khóa miền đa ngành (không còn bị chặn bởi anatomicalCandidates!)
        if (domainKeywords != null) {
            for (String kw : domainKeywords) {
                if (kw == null || kw.isBlank()) continue;
                String kLow = kw.strip().toLowerCase(Locale.ROOT);
                if (containsWholeVietnamesePhrase(lowerRowText, kLow)) {
                    tags.add(kLow);
                }
            }
        }

        // 2. Bóc tách các thẻ ứng dụng / phạm vi từ cột "Phù hợp / Ứng dụng / Mục tiêu"
        if (suitableForText != null && !suitableForText.isBlank()) {
            String[] rawParts = suitableForText.split("[,;/•|\\n]+");
            for (String part : rawParts) {
                String cleaned = part.strip().toLowerCase(Locale.ROOT)
                        .replaceFirst("^(?:dành\\s+cho|phù\\s+hợp(?:\\s+với|\\s+cho)?|khách\\s+hàng|khách|ứng\\s+dụng(?:\\s+cho)?)\\s+", "")
                        .strip();
                if (cleaned.length() >= 2 && cleaned.length() <= 45) {
                    tags.add(cleaned);
                }
            }
        }

        // 3. Khớp từ khóa giải phẫu theo ranh giới âm tiết chuẩn (tránh "video"/"keo"/"theo" dính nhầm chữ "eo")
        for (String token : ANATOMICAL_SCOPE_TOKENS) {
            if (containsWholeVietnamesePhrase(lowerRowText, token)) {
                tags.add(token);
            }
        }
        if (containsWholeVietnamesePhrase(lowerRowText, "toàn thân") || containsWholeVietnamesePhrase(lowerRowText, "full body")) {
            tags.addAll(List.of("toàn thân", "body", "cổ", "vai", "gáy", "lưng", "chân", "tay"));
        }
        if (tags.contains("cổ") && tags.contains("vai")) {
            tags.add("gáy");
        }

        return List.copyOf(tags);
    }

    private static boolean containsWholeVietnamesePhrase(String haystackLower, String phraseLower) {
        if (haystackLower == null || phraseLower == null || phraseLower.isBlank()) {
            return false;
        }
        Pattern pat = Pattern.compile("(?iU)(?<![\\p{L}\\p{N}])" + Pattern.quote(phraseLower) + "(?![\\p{L}\\p{N}])");
        return pat.matcher(haystackLower).find();
    }

    private static RegionResolution inferRegionInfo(String headingLower) {
        if (headingLower == null || headingLower.isBlank()) {
            return new RegionResolution(RegionScope.UNSPECIFIED, "");
        }
        if (headingLower.contains("toàn quốc")
                || headingLower.contains("toàn hệ thống")
                || headingLower.contains("tất cả cơ sở")
                || headingLower.contains("tất cả chi nhánh")) {
            return new RegionResolution(RegionScope.GLOBAL_CONFIRMED, "GLOBAL");
        }
        if (headingLower.contains("quận 1")
                || headingLower.contains("tp.hcm")
                || headingLower.contains("tp hồ chí minh")
                || headingLower.contains("sài gòn")
                || headingLower.contains("hồ chí minh")) {
            return new RegionResolution(RegionScope.BRANCH_SPECIFIC, "SG");
        }
        if (headingLower.contains("hà nội")
                || headingLower.contains("bích câu")
                || headingLower.contains("mỹ đình")
                || headingLower.contains("long biên")
                || headingLower.contains("cầu giấy")
                || headingLower.contains("đống đa")
                || headingLower.contains("hoàn kiếm")
                || headingLower.contains("tây hồ")
                || headingLower.contains("hà đông")) {
            return new RegionResolution(RegionScope.BRANCH_SPECIFIC, "HN");
        }
        Matcher dynamicBranch = Pattern.compile("(?iU)(?:cơ\\s+sở|chi\\s+nhánh|showroom|campus|khu\\s+vực)\\s+([^—\\(\\n|,\\[\\]>]+)")
                .matcher(headingLower);
        if (dynamicBranch.find()) {
            String slug = slugifyAscii(dynamicBranch.group(1)).toUpperCase(Locale.ROOT);
            if (!slug.isBlank()) {
                return new RegionResolution(RegionScope.BRANCH_SPECIFIC, slug);
            }
        }
        return new RegionResolution(RegionScope.UNSPECIFIED, "");
    }

    private static String inferServiceFamily(List<String> headingPath) {
        if (headingPath == null || headingPath.isEmpty()) {
            return "GENERAL";
        }
        String top = headingPath.get(0).strip();
        return top.isEmpty() ? "GENERAL" : top;
    }

    private static boolean isLikelySkuCode(String rawCell) {
        if (rawCell == null || rawCell.isBlank()) {
            return false;
        }
        String candidate = rawCell.strip().toUpperCase(Locale.ROOT);
        if (candidate.length() < 3 || candidate.length() > 24 || candidate.contains(" ")) {
            return false;
        }
        if (!SKU_CODE_PATTERN.matcher(candidate).matches()) {
            return false;
        }
        // Tránh nhận nhầm đơn vị/tiền tệ hoặc kích thước kiểu "80X80CM" làm mã SKU
        if (candidate.matches("^\\d+[X×]\\d+.*") || candidate.endsWith("VND") || candidate.endsWith("VNĐ")) {
            return false;
        }
        return candidate.chars().anyMatch(Character::isLetter)
                && (candidate.chars().anyMatch(Character::isDigit) || candidate.contains("-") || candidate.contains("_"));
    }

    private static boolean isSpecificationHeader(String headerLower) {
        return headerLower.contains("thời gian")
                || headerLower.contains("thời lượng")
                || headerLower.contains("số buổi")
                || headerLower.contains("lộ trình")
                || headerLower.contains("kích thước")
                || headerLower.contains("quy cách")
                || headerLower.contains("khổ")
                || headerLower.contains("diện tích")
                || headerLower.contains("chất liệu")
                || headerLower.contains("bề mặt")
                || headerLower.contains("trình độ")
                || headerLower.contains("sĩ số")
                || headerLower.contains("bảo hành")
                || headerLower.contains("dung tích")
                || headerLower.contains("công suất");
    }

    private static int findColumnIndex(List<String> headers, Pattern headerPat) {
        if (headers == null) return -1;
        for (int i = 0; i < headers.size(); i++) {
            String h = headers.get(i).strip();
            if (headerPat.matcher(h).matches()) {
                return i;
            }
        }
        return -1;
    }

    private static int findColumnIndexByKeywords(List<String> headers, List<String> keywords) {
        if (headers == null) return -1;
        for (int i = 0; i < headers.size(); i++) {
            String hLow = headers.get(i).strip().toLowerCase(Locale.ROOT);
            for (String kw : keywords) {
                if (hLow.equals(kw) || hLow.contains(kw)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int findNameColumnIndex(List<String> headers, int idxCol, int skuCol, int priceCol) {
        if (headers == null) return -1;
        for (int i = 0; i < headers.size(); i++) {
            if (i == idxCol || i == skuCol || i == priceCol) continue;
            if (NAME_HEADER_PATTERN.matcher(headers.get(i).strip()).matches()) {
                return i;
            }
        }
        for (int i = 0; i < headers.size(); i++) {
            if (i != idxCol && i != skuCol && i != priceCol) {
                return i;
            }
        }
        return -1;
    }

    private static String findCellByHeaderKeywords(
            List<String> cells,
            List<String> headers,
            List<String> keywords,
            int defaultIdx
    ) {
        if (headers != null) {
            for (int i = 0; i < headers.size() && i < cells.size(); i++) {
                String hLow = headers.get(i).toLowerCase(Locale.ROOT);
                for (String kw : keywords) {
                    if (hLow.contains(kw)) {
                        return cells.get(i);
                    }
                }
            }
        }
        return defaultIdx >= 0 && defaultIdx < cells.size() ? cells.get(defaultIdx) : "";
    }

    private static List<String> splitMarkdownCells(String line) {
        String inner = line.substring(1, line.length() - 1);
        String[] parts = inner.split("\\|", -1);
        List<String> res = new ArrayList<>(parts.length);
        for (String p : parts) {
            res.add(p.strip());
        }
        return res;
    }

    private static String buildAutoCodeFromName(String rawName, String primarySpec) {
        String slug = slugifyAscii((rawName != null ? rawName : "") + " " + (primarySpec != null ? primarySpec : ""))
                .toUpperCase(Locale.ROOT);
        if (slug.isEmpty()) {
            return "ITEM_UNKNOWN";
        }
        if (slug.length() > 28) {
            int hash = Math.abs(slug.hashCode() % 10_000);
            slug = slug.substring(0, 22).replaceAll("_+$", "") + "_" + hash;
        }
        return "ITEM_" + slug;
    }

    private static String buildDeterministicProductId(
            String regionField,
            String skuCode,
            String rawName,
            String primarySpec
    ) {
        String baseCode = (skuCode != null && !skuCode.isBlank())
                ? skuCode.strip().toUpperCase(Locale.ROOT)
                : buildAutoCodeFromName(rawName, primarySpec);
        if (regionField != null && !regionField.isBlank()
                && !"UNSPECIFIED".equalsIgnoreCase(regionField)
                && !"GLOBAL".equalsIgnoreCase(regionField)) {
            return regionField.strip().toUpperCase(Locale.ROOT) + "_" + baseCode;
        }
        return baseCode;
    }

    private static String slugifyAscii(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        String normalized = Normalizer.normalize(input.strip(), Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replace('đ', 'd')
                .replace('Đ', 'D')
                .replace("²", "2")
                .replace("³", "3");
        return normalized.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private static int scoreRichness(PackageSummary p) {
        if (p == null) return 0;
        int score = 0;
        if (p.parseStatus() == ParseStatus.VERIFIED) score += 500;
        if (p.displayName() != null) score += p.displayName().length();
        if (p.processSummary() != null && !p.processSummary().isBlank()) score += 100;
        if (p.includesSummary() != null) score += p.includesSummary().length();
        if (p.scopeTags() != null) score += p.scopeTags().size() * 15;
        if (p.specifications() != null) score += p.specifications().size() * 20;
        return score;
    }
}
