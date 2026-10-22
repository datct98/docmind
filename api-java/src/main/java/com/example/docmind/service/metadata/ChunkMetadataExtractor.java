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
            "(?<!\\w)([A-Z]{2,4}[-_]?\\d{1,3})(?!\\w)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern DYNAMIC_BRANCH_PATTERN = Pattern.compile(
            "(?iU)(?:cơ\\s+sở|chi\\s+nhánh|khu\\s+vực)\\s+([^—\\(\n\\|,\\[\\]]+)"
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

        return new ChunkMetadata(chunkRole, minPrice, maxPrice, branchTags, serviceCodes);
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
            if (!tLow.contains("| mã gói |") && !tLow.contains("| đơn giá |") && !tLow.contains("| giá |")) {
                return ChunkMetadata.ROLE_PROMO;
            }
        }

        // 4. PRICE_TABLE check
        boolean hasPriceKeywords = hLow.contains("bảng giá") || hLow.contains("danh mục")
                || hLow.contains("báo giá") || tLow.contains("đơn giá")
                || tLow.contains("| giá |") || tLow.contains("| mã gói |")
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
     * Bóc tách các nhãn chi nhánh / cơ sở từ heading và text.
     */
    public static List<String> extractBranchTags(String headings, String text) {
        String combined = (headings + " " + (text != null ? text : "")).toLowerCase(Locale.ROOT);
        Set<String> tags = new LinkedHashSet<>();

        // Standard geographical / branch markers
        if (combined.contains("quận 1") || combined.contains("q.1") || combined.contains("nguyễn công trứ") || combined.contains("tp.hcm") || combined.contains("sài gòn")) {
            tags.add("quan_1");
        }
        if (combined.contains("mỹ đình") || combined.contains("my dinh")) {
            tags.add("my_dinh");
        }
        if (combined.contains("bích câu") || combined.contains("bich cau")) {
            tags.add("bich_cau");
        }
        if (combined.contains("long biên") || combined.contains("long bien")) {
            tags.add("long_bien");
        }
        if (combined.contains("cầu giấy") || combined.contains("cau giay")) {
            tags.add("cau_giay");
        }
        if (combined.contains("đống đa") || combined.contains("dong da")) {
            tags.add("dong_da");
        }
        if (combined.contains("hà đông") || combined.contains("ha dong")) {
            tags.add("ha_dong");
        }
        if (combined.contains("tây hồ") || combined.contains("tay ho")) {
            tags.add("tay_ho");
        }
        if (combined.contains("hoàn kiếm") || combined.contains("hoan kiem")) {
            tags.add("hoan_kiem");
        }
        if (combined.contains("thủ đức") || combined.contains("thu duc")) {
            tags.add("thu_duc");
        }
        if (combined.contains("bình thạnh") || combined.contains("binh thanh")) {
            tags.add("binh_thanh");
        }
        if (combined.contains("tân bình") || combined.contains("tan binh")) {
            tags.add("tan_binh");
        }
        if (combined.contains("phú nhuận") || combined.contains("phu nhuan")) {
            tags.add("phu_nhuan");
        }

        // Dynamic regex pattern: "Cơ sở <Name>" hoặc "Chi nhánh <Name>"
        Matcher m = DYNAMIC_BRANCH_PATTERN.matcher(headings);
        while (m.find()) {
            String branchName = m.group(1).trim();
            String slug = slugify(branchName);
            if (!slug.isBlank() && slug.length() >= 3 && !tags.contains(slug)) {
                tags.add(slug);
            }
        }

        return new ArrayList<>(tags);
    }

    /**
     * Bóc tách các mã dịch vụ / sản phẩm từ bảng Markdown hoặc văn bản.
     */
    public static List<String> extractServiceCodes(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Set<String> codes = new LinkedHashSet<>();

        // 1. Phân tích cột trong bảng Markdown
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
                    if (header.matches("mã\\s*(?:gói|dv|sp|dịch\\s*vụ|liệu\\s*trình)?|code|sku")) {
                        codeColIdx = i;
                        break;
                    }
                }
            } else if (!trimmed.contains("---")) {
                if (codeColIdx < cells.length) {
                    String val = cells[codeColIdx].trim();
                    if (val.matches("^[A-Za-z0-9\\-_]{2,10}$") && !val.equalsIgnoreCase("null")) {
                        codes.add(val.toUpperCase(Locale.ROOT));
                    }
                }
            }
        }

        // 2. Quét regex phụ trợ
        Matcher matcher = SERVICE_CODE_PATTERN.matcher(text);
        while (matcher.find()) {
            codes.add(matcher.group(1).toUpperCase(Locale.ROOT));
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
