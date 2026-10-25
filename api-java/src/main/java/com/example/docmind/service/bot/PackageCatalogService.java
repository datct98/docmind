package com.example.docmind.service.bot;

import com.example.docmind.service.retrieval.ReciprocalRankFusion;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dịch vụ trích xuất Danh mục Gói dịch vụ động (Dynamic Package Catalog v3.2.1-FINAL)
 * trực tiếp từ các đoạn tài liệu Active ({@link ReciprocalRankFusion.FusedChunk}) của từng Page/Tenant.
 *
 * <p>Hoàn toàn phi đặc thù ngành (Domain-Agnostic): hỗ trợ cả bảng Markdown đa cột (như HTML/Excel bảng giá
 * có cột {@code Mã gói | Tên gói | Giá | Tổng thời gian | Bao gồm | Không gian | Điểm nổi bật | Phù hợp | Quy trình chi tiết})
 * lẫn danh mục dạng gạch đầu dòng văn bản.
 */
public final class PackageCatalogService {

    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z]{2,5}\\d{1,4}$");
    private static final Pattern PRICE_VND_PATTERN = Pattern.compile(
            "(?iU)\\b(\\d{1,3}(?:\\.\\d{3}){1,2}|\\d{2,4}\\s*k)\\s*(?:vnđ|vnd|đ)?"
    );
    private static final Pattern DURATION_MIN_PATTERN = Pattern.compile(
            "(?iU)(~?\\s*\\d{2,3})\\s*(?:phút|p\\b)"
    );

    private PackageCatalogService() {
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
            @JsonProperty("is_addon") boolean isAddon
    ) {
        public String canonicalLabel() {
            String base = displayName != null ? displayName.strip() : "";
            if (durationLabel != null && !durationLabel.isBlank() && !base.toLowerCase(Locale.ROOT).contains("phút")) {
                return base + " " + durationLabel.strip();
            }
            return base;
        }
    }

    public static List<PackageSummary> extractCatalog(
            List<ReciprocalRankFusion.FusedChunk> chunks,
            List<String> domainKeywords
    ) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        Map<String, PackageSummary> byUid = new LinkedHashMap<>();
        for (ReciprocalRankFusion.FusedChunk fc : chunks) {
            if (fc == null || fc.chunk() == null || fc.chunk().text() == null) {
                continue;
            }
            List<String> headingPath = fc.chunk().headingPath() != null ? fc.chunk().headingPath() : List.of();
            String text = fc.chunk().text();
            parseMarkdownTablePackages(text, headingPath, domainKeywords, byUid);
        }
        return new ArrayList<>(byUid.values());
    }

    public static List<PackageSummary> filterByRegion(List<PackageSummary> catalog, String preferredRegion) {
        return filterForRegion(catalog, preferredRegion);
    }

    public static List<PackageSummary> filterForRegion(List<PackageSummary> catalog, String preferredRegion) {
        if (catalog == null || catalog.isEmpty()) {
            return List.of();
        }
        String reg = (preferredRegion != null && !preferredRegion.isBlank())
                ? preferredRegion.strip().toUpperCase(Locale.ROOT)
                : "HN";
        List<PackageSummary> filtered = catalog.stream()
                .filter(p -> p != null && !p.isAddon() && reg.equalsIgnoreCase(p.region()))
                .toList();
        if (!filtered.isEmpty()) {
            return filtered;
        }
        return catalog.stream().filter(p -> p != null && !p.isAddon()).toList();
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
                            || ex.contains(nameLow) || nameLow.contains(ex)
                            || ex.contains(canonLow) || canonLow.contains(ex))));
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
        long kVal = priceVnd / 1000L;
        if (kVal >= 1000L && kVal % 10L == 0) {
            return String.format(Locale.ROOT, "%,dk", kVal).replace(',', '.');
        }
        return kVal + "k";
    }

    private static void parseMarkdownTablePackages(
            String text,
            List<String> headingPath,
            List<String> domainKeywords,
            Map<String, PackageSummary> byUid
    ) {
        String[] lines = text.split("\\r?\\n");
        List<String> headers = null;
        String headingJoined = String.join(" > ", headingPath);
        String headingLower = headingJoined.toLowerCase(Locale.ROOT);
        if (headingLower.contains("quy trình chi tiết") && !headingLower.contains("cơ sở") && !headingLower.contains("khu vực")) {
            return;
        }
        String region = inferRegion(headingLower);
        String defaultFamily = inferServiceFamily(headingPath);

        for (String rawLine : lines) {
            String line = rawLine.strip();
            if (!line.startsWith("|") || !line.endsWith("|")) {
                continue;
            }
            if (line.replaceAll("[|\\-:\\s]", "").isEmpty()) {
                continue;
            }
            List<String> cells = splitMarkdownCells(line);
            if (cells.size() < 3) {
                continue;
            }
            String firstCell = cells.get(0).strip();
            if (headers == null && (firstCell.equalsIgnoreCase("Mã gói")
                    || firstCell.equalsIgnoreCase("Mã dịch vụ")
                    || firstCell.equalsIgnoreCase("Mã SP")
                    || cells.stream().anyMatch(c -> c.toLowerCase(Locale.ROOT).contains("giá")))) {
                if (!CODE_PATTERN.matcher(firstCell.toUpperCase(Locale.ROOT)).matches()) {
                    headers = cells;
                    continue;
                }
            }

            String code = firstCell.toUpperCase(Locale.ROOT);
            if (!CODE_PATTERN.matcher(code).matches()) {
                continue;
            }
            String name = cells.size() > 1 ? cells.get(1).strip() : code;
            String priceCell = findCellByHeader(cells, headers, "giá", 2);
            String durationCell = findCellByHeader(cells, headers, "thời gian", 3);
            String includesCell = findCellByHeader(cells, headers, "bao gồm", -1);
            if (includesCell.isBlank()) {
                includesCell = findCellByHeader(cells, headers, "nội dung", 4);
            }
            String roomCell = findCellByHeader(cells, headers, "không gian", -1);
            if (roomCell.isBlank()) {
                roomCell = findCellByHeader(cells, headers, "phòng", -1);
            }
            String highlightCell = findCellByHeader(cells, headers, "nổi bật", -1);
            String suitableCell = findCellByHeader(cells, headers, "phù hợp", -1);
            String processCell = findCellByHeader(cells, headers, "quy trình", -1);

            long priceVnd = parsePriceVnd(priceCell);
            if (priceVnd <= 0) {
                continue;
            }
            Integer durationMin = parseDurationMinutes(durationCell);
            String durationLabel = (durationMin != null && durationMin > 0)
                    ? durationMin + " phút"
                    : (durationCell != null && !durationCell.isBlank() ? durationCell.strip() : "");
            boolean isAddon = code.startsWith("ADD") || (durationLabel.toLowerCase(Locale.ROOT).contains("theo liệu trình"));

            String combinedRowText = (name + " " + includesCell + " " + highlightCell + " " + suitableCell + " " + processCell)
                    .toLowerCase(Locale.ROOT);
            List<String> bodyAreas = extractBodyAreasFromRow(combinedRowText, domainKeywords);

            String normalizedRoomCell = roomCell != null ? roomCell.strip() : "";
            String roomLower = normalizedRoomCell.toLowerCase(Locale.ROOT);
            boolean isSharedOrCurtainRoom = roomLower.contains("phòng chung")
                    || roomLower.contains("khu dưỡng sinh")
                    || ("HN".equalsIgnoreCase(region) && priceVnd > 0L && priceVnd < 580_000L && !roomLower.contains("vip"));
            if (roomLower.contains("phòng chung")) {
                // v3.3.4 RC-07: GIỮ rõ nhãn "Phòng chung" để LLM không thể diễn giải thành phòng riêng/VIP
                normalizedRoomCell = "Phòng chung yên tĩnh, sạch thoáng, mỗi giường đều có rèm che riêng kín đáo";
            }
            String safeHighlightCell = highlightCell != null ? highlightCell.strip() : "";
            if (isSharedOrCurtainRoom && !safeHighlightCell.isBlank()) {
                safeHighlightCell = safeHighlightCell.replaceAll("(?iU)phòng\\s+vip(?:\\s+riêng(?:\\s+tư)?)?",
                        "Không gian yên tĩnh, sạch thoáng, mỗi giường đều có rèm che riêng kín đáo");
            }

            String enrichedIncludes = includesCell != null ? includesCell.strip() : "";
            String hlLower = safeHighlightCell.toLowerCase(Locale.ROOT);
            String incLower = enrichedIncludes.toLowerCase(Locale.ROOT);
            if (hlLower.contains("tắm xông") && !incLower.contains("tắm xông") && !incLower.contains("xông hơi")) {
                enrichedIncludes = enrichedIncludes.isEmpty() ? "Tắm xông thảo dược" : (enrichedIncludes + ", tắm xông thảo dược");
            }
            if (hlLower.contains("buffet thực dưỡng") && !incLower.contains("buffet")) {
                enrichedIncludes = enrichedIncludes.isEmpty() ? "Buffet thực dưỡng" : (enrichedIncludes + ", buffet thực dưỡng");
            } else if (hlLower.contains("ăn nhẹ") && !incLower.contains("ăn nhẹ") && !incLower.contains("buffet")) {
                enrichedIncludes = enrichedIncludes.isEmpty() ? "Ăn nhẹ sau liệu trình" : (enrichedIncludes + ", ăn nhẹ sau liệu trình");
            }
            String enrichedProcess = (processCell != null && !processCell.isBlank())
                    ? (!safeHighlightCell.isBlank() ? safeHighlightCell + " | " + processCell.strip() : processCell.strip())
                    : safeHighlightCell;

            String uid = region + "_" + code;
            PackageSummary candidate = new PackageSummary(
                    uid,
                    code,
                    region,
                    name,
                    defaultFamily,
                    bodyAreas,
                    durationMin,
                    durationLabel,
                    priceVnd,
                    formatCompactPrice(priceVnd),
                    normalizedRoomCell,
                    enrichedIncludes,
                    suitableCell,
                    enrichedProcess,
                    isAddon
            );

            PackageSummary existing = byUid.get(uid);
            if (existing == null || scoreRichness(candidate) > scoreRichness(existing)) {
                byUid.put(uid, candidate);
            }
        }
    }

    private static int scoreRichness(PackageSummary p) {
        if (p == null) return 0;
        int score = 0;
        if (p.displayName() != null) score += p.displayName().length();
        if (p.processSummary() != null && !p.processSummary().isBlank()) score += 100;
        if (p.includesSummary() != null) score += p.includesSummary().length();
        if (p.bodyAreas() != null) score += p.bodyAreas().size() * 15;
        return score;
    }

    private static String inferRegion(String headingLower) {
        if (headingLower == null) {
            return "HN";
        }
        if (headingLower.contains("quận 1")
                || headingLower.contains("tp.hcm")
                || headingLower.contains("tp hồ chí minh")
                || headingLower.contains("sài gòn")
                || headingLower.contains("hồ chí minh")) {
            return "SG";
        }
        return "HN";
    }

    private static String inferServiceFamily(List<String> headingPath) {
        if (headingPath == null || headingPath.isEmpty()) {
            return "GENERAL";
        }
        String top = headingPath.get(0).strip();
        return top.isEmpty() ? "GENERAL" : top;
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

    private static String findCellByHeader(List<String> cells, List<String> headers, String headerKeyword, int defaultIdx) {
        if (headers != null) {
            for (int i = 0; i < headers.size() && i < cells.size(); i++) {
                if (headers.get(i).toLowerCase(Locale.ROOT).contains(headerKeyword)) {
                    return cells.get(i);
                }
            }
        }
        return defaultIdx >= 0 && defaultIdx < cells.size() ? cells.get(defaultIdx) : "";
    }

    public static long parsePriceVnd(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        Matcher m = PRICE_VND_PATTERN.matcher(raw.strip());
        if (m.find()) {
            String token = m.group(1).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
            try {
                if (token.endsWith("k")) {
                    return Long.parseLong(token.substring(0, token.length() - 1)) * 1000L;
                }
                return Long.parseLong(token.replace(".", ""));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0L;
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

    private static List<String> extractBodyAreasFromRow(String lowerText, List<String> domainKeywords) {
        Set<String> areas = new LinkedHashSet<>();
        List<String> anatomicalCandidates = List.of(
                "cổ", "vai", "gáy", "lưng", "thắt lưng", "eo", "chân", "gối", "tay", "đầu", "da đầu", "tóc", "tai", "da mặt", "toàn thân", "body", "răng", "nướu"
        );
        for (String token : anatomicalCandidates) {
            if (lowerText.contains(token)) {
                areas.add(token);
            }
        }
        if (lowerText.contains("toàn thân") || lowerText.contains("full body")) {
            areas.addAll(List.of("toàn thân", "body", "cổ", "vai", "gáy", "lưng", "chân", "tay"));
        }
        if (domainKeywords != null) {
            for (String kw : domainKeywords) {
                if (kw != null && !kw.isBlank()) {
                    String kLow = kw.strip().toLowerCase(Locale.ROOT);
                    if (lowerText.contains(kLow) && anatomicalCandidates.contains(kLow)) {
                        areas.add(kLow);
                    }
                }
            }
        }
        return List.copyOf(areas);
    }
}
