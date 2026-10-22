package com.example.docmind.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Metadata động trích xuất từ khối văn bản/bảng của ChunkEntity theo chuẩn Obsidian MetadataCache.
 */
public record ChunkMetadata(
        @JsonProperty("chunk_role")
        String chunkRole,       // PRICE_TABLE | PROMO | FAQ | PROCESS | GENERAL
        @JsonProperty("min_price")
        Long minPrice,          // e.g. 129000L
        @JsonProperty("max_price")
        Long maxPrice,          // e.g. 1990000L
        @JsonProperty("branch_tags")
        List<String> branchTags,// e.g. ["my_dinh", "quan_1", "bich_cau", "long_bien"]
        @JsonProperty("service_codes")
        List<String> serviceCodes, // e.g. ["NS01", "DS01", "DS02"]
        @JsonProperty("semantic_tags")
        List<String> semanticTags  // e.g. ["warranty_return", "social_proof", "spec_ingredients", "scarcity_promo"]
) {
    public static final String ROLE_PRICE_TABLE = "PRICE_TABLE";
    public static final String ROLE_PROMO = "PROMO";
    public static final String ROLE_FAQ = "FAQ";
    public static final String ROLE_PROCESS = "PROCESS";
    public static final String ROLE_GENERAL = "GENERAL";

    public static final String TAG_WARRANTY_RETURN = "warranty_return";
    public static final String TAG_SOCIAL_PROOF = "social_proof";
    public static final String TAG_SPEC_INGREDIENTS = "spec_ingredients";
    public static final String TAG_SCARCITY_PROMO = "scarcity_promo";

    public ChunkMetadata(
            String chunkRole,
            Long minPrice,
            Long maxPrice,
            List<String> branchTags,
            List<String> serviceCodes
    ) {
        this(chunkRole, minPrice, maxPrice, branchTags, serviceCodes, List.of());
    }

    public boolean hasSemanticTag(String tag) {
        if (tag == null || semanticTags == null) {
            return false;
        }
        for (String t : semanticTags) {
            if (tag.equalsIgnoreCase(t)) {
                return true;
            }
        }
        return false;
    }

    public boolean isPriceTable() {
        return ROLE_PRICE_TABLE.equalsIgnoreCase(chunkRole);
    }

    public boolean isPromo() {
        return ROLE_PROMO.equalsIgnoreCase(chunkRole);
    }

    public boolean isFaq() {
        return ROLE_FAQ.equalsIgnoreCase(chunkRole);
    }

    public boolean isProcess() {
        return ROLE_PROCESS.equalsIgnoreCase(chunkRole);
    }

    public boolean isEntryLevel() {
        if (!isPriceTable()) {
            return false;
        }
        if (serviceCodes != null) {
            for (String code : serviceCodes) {
                if (code != null) {
                    String norm = code.toUpperCase(java.util.Locale.ROOT);
                    if (norm.endsWith("01") || norm.endsWith("-01") || norm.endsWith("_01") || norm.endsWith("1")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
