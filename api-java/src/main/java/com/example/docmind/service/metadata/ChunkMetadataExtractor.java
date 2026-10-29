package com.example.docmind.service.metadata;

import com.example.docmind.dto.ChunkMetadata;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Trích xuất động ChunkMetadata (role, min/max price, branch_tags, service_codes) từ chunk văn bản/bảng.
 * Tuyệt đối không hardcode mã gói (NS01, DS01...) hay mức giá cụ thể nào.
 */
public final class ChunkMetadataExtractor {

    private ChunkMetadataExtractor() {}

    private static final Pattern DOT_VND_PATTERN = Pattern.compile(
            "(?<!\\d)(?:(?<!\\d\\.)\\b)(\\d{1,3}(?:\\.\\d{3})+)\\s*(?:vnđ|vnd|đ|đồng)?(?![\\p{L}\\d])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS
    );

    private static final Pattern COMMA_VND_PATTERN = Pattern.compile(
            "(?<!\\d)(?:(?<!\\d,)\\b)(\\d{1,3}(?:,\\d{3})+)\\s*(?:vnđ|vnd|đ|đồng)?(?![\\p{L}\\d])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS
    );

    private static final Pattern K_PRICE_PATTERN = Pattern.compile(
            "(?<!\\w)(\\d{2,4})\\s*k(?![\\p{L}\\d])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS
    );

    private static final Pattern SERVICE_CODE_PATTERN = Pattern.compile(
            "(?<![\\p{L}\\p{N}_-])([A-Za-z]{2,10}(?:[-_][A-Za-z0-9]{1,14}){1,2}|[A-Za-z]{2,6}\\d{1,6}[A-Za-z0-9]{0,3})(?![\\p{L}\\p{N}_-])"
    );

    private static final Set<String> NON_SKU_ABBREVIATIONS = Set.of(
            "VND", "MP3", "MP4", "CO2", "H2O", "UTF8", "UTF-8", "TP-HCM", "TP_HCM"
    );

    private static final Pattern ADMIN_NUMBERED_DISTRICT_PATTERN = Pattern.compile(
            "(?iU)(?<![\\p{L}\\p{N}])(?:quận|q\\.)\\s*(\\d{1,2})(?![\\p{L}\\p{N}])"
    );

    private static final Pattern ADMIN_NAMED_DISTRICT_PATTERN = Pattern.compile(
            "(?U)(?<![\\p{L}\\p{N}])(?:[Qq]uận)\\s+([\\p{Lu}][\\p{Ll}]+\\s+[\\p{Lu}][\\p{Ll}]+)(?![\\p{L}\\p{N}])"
    );

    private static final Pattern DYNAMIC_BRANCH_PATTERN = Pattern.compile(
            "(?U)(?:[Cc]ơ\\s+sở|[Cc]hi\\s+nhánh|[Ss]howroom|[Tt]rung\\s+tâm|[Cc]ampus|[Đđ]iểm\\s+bán|[Cc]ửa\\s+hàng|[Kk]hu\\s+vực)\\s*(?:\\d+\\s*[-–:]\\s*)?:?\\s*([^—\\(\n\\|,\\[\\]>.;:]+)"
    );

    /**
     * Trích xuất toàn diện ChunkMetadata cho một chunk với headingPath là String[].
     */
    public static ChunkMetadata extract(String[] headingPath, String text) {
        return extract(headingPath == null ? null : Arrays.asList(headingPath), text);
    }

    /**
     * Trích xuất toàn diện ChunkMetadata cho một chunk với headingPath là List<String>.
     */
    public static ChunkMetadata extract(List<String> headingPath, String text) {
        String safeText = text != null ? text : "";
        String headingsJoined = (headingPath != null && !headingPath.isEmpty())
                ? String.join(" > ", headingPath)
                : "";

        List<Long> prices = extractAllPrices(safeText);
        Long minPrice = prices.isEmpty() ? null : Collections.min(prices);
        Long maxPrice = prices.isEmpty() ? null : Collections.max(prices);

        List<String> branchTags = extractBranchTags(headingsJoined, safeText);
        List<String> serviceCodes = extractServiceCodes(safeText);
        String chunkRole = determineChunkRole(headingsJoined, safeText, prices, serviceCodes);
        List<String> semanticTags = extractSemanticTags(headingsJoined, safeText);

        return new ChunkMetadata(chunkRole, minPrice, maxPrice, branchTags, serviceCodes, semanticTags);
    }

    /**
     * Bóc tách nhãn ngữ nghĩa (semantic_tags) đa ngành phục vụ Intent-Aware Tag Boosting:
     * - warranty_return: bảo hành, đổi trả, cam kết, hoàn tiền, 0 phụ phí
     * - social_proof: feedback, đánh giá thực tế, ca lâm sàng, chứng nhận, khách hàng tin chọn
     * - spec_ingredients: thành phần, chất liệu, kích thước, bảng size, thông số, bao gồm
     * - scarcity_promo: ưu đãi, khuyến mãi, giảm %, giờ vàng, voucher, combo
     */
    public static List<String> extractSemanticTags(String headings, String text) {
        String combined = ((headings != null ? headings : "") + " " + (text != null ? text : "")).toLowerCase(Locale.ROOT);
        Set<String> tags = new LinkedHashSet<>();

        if (combined.contains("bảo hành") || combined.contains("đổi trả") || combined.contains("cam kết")
                || combined.contains("hoàn tiền") || combined.contains("0 phụ phí")
                || combined.contains("không phát sinh") || combined.contains("đổi size")) {
            tags.add(ChunkMetadata.TAG_WARRANTY_RETURN);
        }
        if ((combined.contains("khách hàng") && (combined.contains("đánh giá") || combined.contains("hài lòng")
                || combined.contains("tin chọn") || combined.contains("feedback") || combined.contains("review")))
                || combined.contains("chứng nhận") || combined.contains("ca lâm sàng") || combined.contains("kiểm định")) {
            tags.add(ChunkMetadata.TAG_SOCIAL_PROOF);
        }
        if (combined.contains("thành phần") || combined.contains("quy cách") || combined.contains("chất liệu")
                || combined.contains("chất vải") || combined.contains("kích thước") || combined.contains("bảng size")
                || combined.contains("thông số") || combined.contains("bao gồm") || combined.contains("cấu hình")) {
            tags.add(ChunkMetadata.TAG_SPEC_INGREDIENTS);
        }
        if (combined.contains("ưu đãi") || combined.contains("khuyến mãi") || combined.contains("chiết khấu")
                || combined.contains("giờ vàng") || combined.contains("voucher") || combined.contains("combo")
                || combined.matches("(?s).*giảm\\s+\\d+%.*")) {
            tags.add(ChunkMetadata.TAG_SCARCITY_PROMO);
        }
        return new ArrayList<>(tags);
    }

    /**
     * Nhận diện vai trò khối (PRICE_TABLE | PROMO | FAQ | PROCESS | GENERAL).
     */
    public static String determineChunkRole(String headings, String text, List<Long> prices, List<String> serviceCodes) {
        String hLow = headings.toLowerCase(Locale.ROOT);
        String tLow = text.toLowerCase(Locale.ROOT);
        boolean hasTable = text.contains("|") && text.contains("---");

        // 1. FAQ check
        if (hLow.contains("faq") || hLow.contains("câu hỏi") || hLow.contains("hỏi đáp")
                || hLow.contains("thắc mắc") || hLow.contains("khách hỏi")
                || (hasTable && (tLow.contains("câu hỏi") || tLow.contains("trả lời") || tLow.contains("khác gì nhau")))) {
            return ChunkMetadata.ROLE_FAQ;
        }

        // 2. PROCESS check
        if (hLow.contains("quy trình") || hLow.contains("các bước") || hLow.contains("thao tác")
                || hLow.contains("lưu ý") || (hasTable && (tLow.contains("| bước |") || tLow.contains("mục đích / lợi ích")))) {
            return ChunkMetadata.ROLE_PROCESS;
        }

        // 3. PROMO check (Chính sách ưu đãi, chiết khấu, combo không có bảng giá gốc)
        boolean hasPromoKeywords = hLow.contains("ưu đãi") || hLow.contains("khuyến mãi")
                || hLow.contains("voucher") || hLow.contains("chiết khấu")
                || hLow.contains("combo") || tLow.contains("mức ưu đãi")
                || tLow.contains("chiết khấu theo");
        if (hasPromoKeywords && (prices.isEmpty() || tLow.contains("giảm 10%") || tLow.contains("giảm 15%") || tLow.contains("giảm 20%"))) {
            if (!tLow.contains("| mã gói |") && !tLow.contains("| mã sp |") && !tLow.contains("| sku |")
                    && !tLow.contains("| mã sản phẩm |") && !tLow.contains("| mã khóa |")
                    && !tLow.contains("| đơn giá |") && !tLow.contains("| giá |")) {
                return ChunkMetadata.ROLE_PROMO;
            }
        }

        // 4. PRICE_TABLE check
        boolean hasPriceKeywords = hLow.contains("bảng giá") || hLow.contains("danh mục")
                || hLow.contains("báo giá") || tLow.contains("đơn giá")
                || tLow.contains("| giá |") || tLow.contains("| mã gói |")
                || tLow.contains("| mã sp |") || tLow.contains("| sku |")
                || tLow.contains("| mã sản phẩm |") || tLow.contains("| mã khóa |")
                || tLow.contains("giá niêm yết") || tLow.contains("bảng giá");
        if (hasTable && (hasPriceKeywords || !prices.isEmpty() || !serviceCodes.isEmpty())) {
            return ChunkMetadata.ROLE_PRICE_TABLE;
        }

        if (!prices.isEmpty() && (hasPriceKeywords || tLow.contains("bảng giá"))) {
            return ChunkMetadata.ROLE_PRICE_TABLE;
        }

        if (hasPromoKeywords) {
            return ChunkMetadata.ROLE_PROMO;
        }

        return ChunkMetadata.ROLE_GENERAL;
    }

    /**
     * Bóc tách tất cả các mức giá VND từ văn bản.
     */
    public static List<Long> extractAllPrices(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Set<Long> prices = new LinkedHashSet<>();

        // Match dạng '570.000', '1.990.000'
        Matcher dotMatcher = DOT_VND_PATTERN.matcher(text);
        while (dotMatcher.find()) {
            try {
                String clean = dotMatcher.group(1).replace(".", "");
                long val = Long.parseLong(clean);
                if (isPlausiblePrice(val, text, dotMatcher.start())) {
                    prices.add(val);
                }
            } catch (NumberFormatException ignored) {}
        }

        // Match dạng '500,000,000'
        Matcher commaMatcher = COMMA_VND_PATTERN.matcher(text);
        while (commaMatcher.find()) {
            try {
                String clean = commaMatcher.group(1).replace(",", "");
                long val = Long.parseLong(clean);
                if (isPlausiblePrice(val, text, commaMatcher.start())) {
                    prices.add(val);
                }
            } catch (NumberFormatException ignored) {}
        }

        // Match dạng '670k', '129k'
        Matcher kMatcher = K_PRICE_PATTERN.matcher(text);
        while (kMatcher.find()) {
            try {
                long val = Long.parseLong(kMatcher.group(1)) * 1000L;
                if (isPlausiblePrice(val, text, kMatcher.start())) {
                    prices.add(val);
                }
            } catch (NumberFormatException ignored) {}
        }

        List<Long> result = new ArrayList<>(prices);
        Collections.sort(result);
        return result;
    }

    private static boolean isPlausiblePrice(long val, String fullText, int matchStart) {
        if (val < 10_000L || val > 50_000_000_000L) {
            return false;
        }
        // Loại bỏ năm e.g. 2024..2030 nếu xuất hiện sau 'năm'
        if (val >= 2020L && val <= 2035L) {
            int lookback = Math.max(0, matchStart - 10);
            String snippet = fullText.substring(lookback, matchStart).toLowerCase(Locale.ROOT);
            if (snippet.contains("năm") || snippet.contains("year")) {
                return false;
            }
        }
        // Loại bỏ số điện thoại
        if (val >= 30_000_000L && val <= 99_999_999L) {
            int lookback = Math.max(0, matchStart - 5);
            String snippet = fullText.substring(lookback, matchStart).toLowerCase(Locale.ROOT);
            if (snippet.contains("sđt") || snippet.contains("sdt") || snippet.contains("hotline") || snippet.contains("0")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Bóc tách động các nhãn chi nhánh / cơ sở / showroom / quận từ heading và text (đa ngành, zero hardcode tên đường/thương hiệu).
     */
    public static List<String> extractBranchTags(String headings, String text) {
        String safeHeadings = headings != null ? headings : "";
        String safeText = text != null ? text : "";
        String combinedOriginal = safeHeadings + " " + safeText;
        Set<String> tags = new LinkedHashSet<>();

        // 1. Nhận diện động đơn vị hành chính dạng số (Quận 1, Q.1, Quận 7...)
        Matcher numDistrictMatcher = ADMIN_NUMBERED_DISTRICT_PATTERN.matcher(combinedOriginal);
        while (numDistrictMatcher.find()) {
            tags.add("quan_" + numDistrictMatcher.group(1).strip());
        }

        // 2. Nhận diện động đơn vị hành chính dạng danh từ riêng viết hoa (Quận Cầu Giấy, Quận Bình Thạnh...)
        Matcher namedDistrictMatcher = ADMIN_NAMED_DISTRICT_PATTERN.matcher(combinedOriginal);
        while (namedDistrictMatcher.find()) {
            String distSlug = slugify(namedDistrictMatcher.group(1).strip());
            if (!distSlug.isBlank() && distSlug.length() >= 4) {
                tags.add(distSlug);
            }
        }

        // 3. Nhận diện động nhãn cơ sở/chi nhánh/showroom/trung tâm/campus từ heading và text
        extractDynamicBranchSlugs(safeHeadings, false, tags);
        extractDynamicBranchSlugs(safeText, true, tags);

        return new ArrayList<>(tags);
    }

    private static void extractDynamicBranchSlugs(String source, boolean requireProperNoun, Set<String> tags) {
        if (source == null || source.isBlank()) {
            return;
        }
        Matcher m = DYNAMIC_BRANCH_PATTERN.matcher(source);
        while (m.find()) {
            String branchName = m.group(1).trim();
            if (branchName.isEmpty()) {
                continue;
            }
            if (requireProperNoun && branchName.codePoints().noneMatch(Character::isUpperCase)) {
                continue;
            }
            String[] rawWords = branchName.split("\\s+");
            if (rawWords.length > 8) {
                branchName = String.join(" ", Arrays.copyOf(rawWords, 6));
            }
            String slug = slugify(branchName);
            if (!slug.isBlank() && slug.length() >= 3) {
                tags.add(slug);
                String[] parts = slug.split("_");
                if (parts.length >= 3) {
                    String secondLast = parts[parts.length - 2];
                    String last = parts[parts.length - 1];
                    if ("quan".equals(secondLast) && last.matches("\\d{1,2}")) {
                        tags.add("quan_" + last);
                    } else if (!secondLast.matches("\\d+") && !last.matches("\\d+")) {
                        String locationTail = secondLast + "_" + last;
                        if (locationTail.length() >= 4) {
                            tags.add(locationTail);
                        }
                    }
                }
            }
        }
    }

    /**
     * Bóc tách các mã dịch vụ / sản phẩm / SKU đa ngành từ bảng Markdown hoặc văn bản.
     */
    public static List<String> extractServiceCodes(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Set<String> codes = new LinkedHashSet<>();

        // 1. Phân tích cột Mã / Code / SKU trong bảng Markdown
        String[] lines = text.split("\\r?\\n");
        int codeColIdx = -1;
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("|")) continue;

            String[] cells = trimmed.split("\\|");
            if (cells.length < 2) continue;

            if (codeColIdx == -1) {
                for (int i = 1; i < cells.length; i++) {
                    String header = cells[i].trim().toLowerCase(Locale.ROOT);
                    if (header.matches("mã\\s*(?:gói|dv|sp|sản\\s*phẩm|hàng|mẫu|khóa|khóa\\s*học|dịch\\s*vụ|liệu\\s*trình)?|code|sku|item\\s*code")) {
                        codeColIdx = i;
                        break;
                    }
                }
            } else if (!trimmed.contains("---")) {
                if (codeColIdx < cells.length) {
                    String val = cells[codeColIdx].trim();
                    if (val.matches("^[A-Za-z0-9][A-Za-z0-9\\-_]{1,23}$")
                            && !val.equalsIgnoreCase("null")
                            && val.chars().anyMatch(Character::isLetter)) {
                        codes.add(val.toUpperCase(Locale.ROOT));
                    }
                }
            }
        }

        // 2. Quét regex phụ trợ đa ngành (VD: GACH-8080, IELTS-24B, TILE_80X80, DS-03, SP01, NS02)
        Matcher matcher = SERVICE_CODE_PATTERN.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group(1).toUpperCase(Locale.ROOT);
            if (candidate.chars().anyMatch(Character::isDigit) && !NON_SKU_ABBREVIATIONS.contains(candidate)) {
                codes.add(candidate);
            }
        }

        return new ArrayList<>(codes);
    }

    /**
     * Kiểm tra động xem một chunk có phải là bảng giá khởi điểm / cơ bản / entry-level hay không (String[]).
     */
    public static boolean isEntryLevelPriceChunk(String[] headingPath, String text) {
        return isEntryLevelPriceChunk(headingPath == null ? null : Arrays.asList(headingPath), text);
    }

    /**
     * Kiểm tra động xem một chunk có phải là bảng giá khởi điểm / cơ bản / entry-level hay không (List<String>).
     * Hoàn toàn không phụ thuộc mã gói hay mức giá hardcoded.
     */
    public static boolean isEntryLevelPriceChunk(List<String> headingPath, String text) {
        ChunkMetadata meta = extract(headingPath, text);
        if (!meta.isPriceTable()) {
            return false;
        }
        if (meta.isEntryLevel()) {
            return true;
        }
        String combined = ((headingPath != null ? String.join(" ", headingPath) : "")
                + " " + (text != null ? text : "")).toLowerCase(Locale.ROOT);
        return combined.contains("cơ bản")
                || combined.contains("khởi điểm")
                || combined.contains("trải nghiệm")
                || combined.contains("dùng thử")
                || combined.contains("gói 1")
                || combined.contains("tiết kiệm");
    }

    private static String slugify(String input) {
        if (input == null) return "";
        String norm = java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFD);
        String noDiacritics = norm.replaceAll("\\p{InCombiningDiacriticalMarks}+", "").replace("đ", "d").replace("Đ", "D");
        return noDiacritics.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }
}
