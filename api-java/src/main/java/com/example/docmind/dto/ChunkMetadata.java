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
        List<String> serviceCodes // e.g. ["NS01", "DS01", "DS02"]
) {
    public static final String ROLE_PRICE_TABLE = "PRICE_TABLE";
    public static final String ROLE_PROMO = "PROMO";
    public static final String ROLE_FAQ = "FAQ";
    public static final String ROLE_PROCESS = "PROCESS";
    public static final String ROLE_GENERAL = "GENERAL";

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
