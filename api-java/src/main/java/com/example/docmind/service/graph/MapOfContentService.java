package com.example.docmind.service.graph;

import com.example.docmind.dto.ChunkMetadata;
import com.example.docmind.entity.ChunkEntity;
import com.example.docmind.entity.DocumentEntity;
import com.example.docmind.repository.ChunkRepository;
import com.example.docmind.repository.DocumentRepository;
import com.example.docmind.service.metadata.ChunkMetadataExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dịch vụ tổng hợp bản đồ nội dung động (Dynamic Map of Content - MOC) theo chuẩn Obsidian.
 *
 * <p>Mục đích:
 * Khi khách hàng hỏi các câu hỏi chung chung (e.g. "giá", "bên mình có dịch vụ gì", "cơ sở Quận 1 có gói nào"),
 * các chunk RAG lấy từ vector/BM25 có thể bị phân mảnh, thiếu góc nhìn toàn cục về danh mục và các chi nhánh.
 * MapOfContentService tổng hợp động toàn bộ ChunkMetadata của một Page thành một bản tóm tắt MOC cấp Page,
 * hiển thị đầy đủ các nhóm dịch vụ (Category), các cơ sở/chi nhánh và biên độ giá min - max chính xác.
 *
 * <p>Đầu ra mẫu:
 * <pre>
 * === MAP OF CONTENT (MOC) ===
 * - DỊCH VỤ MASSAGE:
 *   * Chi nhánh Hà Nội (Mỹ Đình, Bích Câu, Long Biên): 399.000 VNĐ - 1.990.000 VNĐ (NS01 - NS07)
 *   * Chi nhánh TP.HCM (Quận 1): 570.000 VNĐ - 2.150.000 VNĐ (NS01 - NS06)
 * - GỘI ĐẦU DƯỠNG SINH:
 *   * Chi nhánh Hà Nội: 129.000 VNĐ - 990.000 VNĐ (DS01 - DS06)
 *   * Chi nhánh TP.HCM (Quận 1): 129.000 VNĐ - 1.100.000 VNĐ (DS01 - DS06)
 * - CHÍNH SÁCH ƯU ĐÃI:
 *   * Giảm 10% khi mua liệu trình từ 5 buổi hoặc từ 5 voucher.
 * ============================
 * </pre>
 *
 * <p>Hiệu năng: MOC được tính toán động từ metadata của các chunks và được cache (memoized)
 * theo pageId/documentIds với TTL 30 phút, đảm bảo tốc độ phản hồi < 0.01ms ở các lượt chat sau.
 */
@Service
public class MapOfContentService {

    private static final Logger log = LoggerFactory.getLogger(MapOfContentService.class);

    public static final String MOC_HEADER = "=== MAP OF CONTENT (MOC) ===";
    public static final String MOC_FOOTER = "============================";
    private static final long DEFAULT_TTL_MS = 30 * 60 * 1000L; // 30 phút

    private final ChunkRepository chunkRepository;
    private final DocumentRepository documentRepository;
    private final ConcurrentMap<String, CachedMoc> mocCache = new ConcurrentHashMap<>();

    private record CachedMoc(String mocSummary, long createdAtMs) {
        boolean isExpired(long now, long ttlMs) {
            return now - createdAtMs > ttlMs;
        }
    }

    public MapOfContentService() {
        this(null, null);
    }

    @Autowired
    public MapOfContentService(@Autowired(required = false) ChunkRepository chunkRepository,
                               @Autowired(required = false) DocumentRepository documentRepository) {
        this.chunkRepository = chunkRepository;
        this.documentRepository = documentRepository;
    }

    /**
     * Lấy MOC đã cache hoặc xây dựng mới cho pageId / danh sách documentIds.
     */
    public String getOrBuildMapOfContent(UUID pageId, List<UUID> documentIds) {
        String cacheKey = buildCacheKey(pageId, documentIds);
        long now = System.currentTimeMillis();

        CachedMoc cached = mocCache.get(cacheKey);
        if (cached != null && !cached.isExpired(now, DEFAULT_TTL_MS)) {
            return cached.mocSummary();
        }

        List<ChunkEntity> chunks = fetchChunks(pageId, documentIds);
        String moc = buildMocFromChunks(chunks);

        mocCache.put(cacheKey, new CachedMoc(moc, now));
        return moc;
    }

    /**
     * Tiện ích lấy MOC trực tiếp theo pageId.
     */
    public String getMocForPage(UUID pageId) {
        return getOrBuildMapOfContent(pageId, null);
    }

    /**
     * Xóa cache cho một pageId cụ thể khi tài liệu được tải lên hoặc cập nhật.
     */
    public void invalidateCache(UUID pageId) {
        if (pageId != null) {
            mocCache.remove("page:" + pageId);
        }
    }

    /**
     * Xóa toàn bộ cache MOC.
     */
    public void invalidateAll() {
        mocCache.clear();
    }

    public int getCacheSize() {
        return mocCache.size();
    }

    /**
     * Dựng chuỗi MOC từ danh sách ChunkEntity (hỗ trợ kiểm thử độc lập không phụ thuộc DB).
     */
    public String buildMocFromChunks(List<ChunkEntity> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }

        Map<String, CategoryAggregator> categoryMap = new LinkedHashMap<>();
        List<String> promoPolicies = new ArrayList<>();

        for (ChunkEntity chunk : chunks) {
            if (chunk == null || chunk.getText() == null || chunk.getText().isBlank()) {
                continue;
            }

            String[] headingPath = chunk.getHeadingPath();
            String text = chunk.getText();
            ChunkMetadata meta = ChunkMetadataExtractor.extract(headingPath, text);

            // 1. Phân loại PROMO
            if (meta.isPromo()) {
                List<String> extractedPromos = extractPromoPolicies(text);
                for (String p : extractedPromos) {
                    if (!promoPolicies.contains(p)) {
                        promoPolicies.add(p);
                    }
                }
                continue;
            }

            // Bỏ qua FAQ và PROCESS trong việc tính biên độ giá MOC
            if (meta.isFaq() || meta.isProcess()) {
                continue;
            }

            // 2. Phân loại PRICE_TABLE hoặc khối có mức giá
            if (meta.isPriceTable() || (meta.minPrice() != null && meta.minPrice() > 0)) {
                String category = resolveCategoryName(headingPath);
                BranchInfo branch = resolveBranchInfo(headingPath, text, meta.branchTags());

                CategoryAggregator catAgg = categoryMap.computeIfAbsent(category, k -> new CategoryAggregator(category));
                catAgg.addChunk(branch, meta);
            }
        }

        if (categoryMap.isEmpty() && promoPolicies.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(MOC_HEADER).append('\n');

        // Xuất các danh mục giá theo nhóm dịch vụ
        for (CategoryAggregator cat : categoryMap.values()) {
            String catFormatted = cat.formatCategory();
            if (!catFormatted.isBlank()) {
                sb.append(catFormatted).append('\n');
            }
        }

        // Xuất chính sách ưu đãi nếu có
        if (!promoPolicies.isEmpty()) {
            sb.append("- CHÍNH SÁCH ƯU ĐÃI:\n");
            int count = 0;
            for (String promo : promoPolicies) {
                sb.append("  * ").append(promo).append('\n');
                count++;
                if (count >= 2) break; // Giữ tối đa 2 chính sách cốt lõi để chống phình token
            }
        }

        sb.append(MOC_FOOTER);
        return sb.toString();
    }

    // =========================================================================
    // NỘI HÀM XỬ LÝ AGGREGATION & FORMATTING
    // =========================================================================

    private String buildCacheKey(UUID pageId, List<UUID> documentIds) {
        if (pageId != null) {
            return "page:" + pageId;
        }
        if (documentIds != null && !documentIds.isEmpty()) {
            List<String> sorted = new ArrayList<>(documentIds.stream().map(UUID::toString).toList());
            Collections.sort(sorted);
            return "docs:" + String.join(",", sorted);
        }
        return "empty";
    }

    private List<ChunkEntity> fetchChunks(UUID pageId, List<UUID> documentIds) {
        if (chunkRepository == null) {
            return List.of();
        }

        List<ChunkEntity> chunks = new ArrayList<>();
        if (pageId != null) {
            chunks.addAll(chunkRepository.findByPageId(pageId));
            if (chunks.isEmpty() && documentRepository != null) {
                List<DocumentEntity> docs = documentRepository.findByPageIdOrderByCreatedAtDesc(pageId);
                List<UUID> docIds = docs.stream().map(DocumentEntity::getId).toList();
                if (!docIds.isEmpty()) {
                    chunks.addAll(chunkRepository.findByDocumentIdIn(docIds));
                }
            }
        } else if (documentIds != null && !documentIds.isEmpty()) {
            chunks.addAll(chunkRepository.findByDocumentIdIn(documentIds));
        }

        return chunks;
    }

    /**
     * Nhận diện nhóm danh mục (Category) từ heading_path.
     */
    public static String resolveCategoryName(String[] headingPath) {
        if (headingPath == null || headingPath.length == 0) {
            return "DỊCH VỤ CHUNG";
        }

        for (String h : headingPath) {
            if (h == null || h.isBlank()) continue;
            String upper = h.strip().toUpperCase(Locale.ROOT);
            if (isBranchLocationHeader(upper)) {
                continue;
            }
            if (upper.contains("MASSAGE")) {
                return "DỊCH VỤ MASSAGE";
            }
            if (upper.contains("GỘI ĐẦU") || upper.contains("DƯỠNG SINH")) {
                return "GỘI ĐẦU DƯỠNG SINH";
            }
            if (upper.contains("BẢNG GIÁ") || upper.contains("SHEET") || upper.contains("DANH MỤC") || upper.contains("MENU")) {
                continue;
            }
            return upper;
        }

        return headingPath[0].strip().toUpperCase(Locale.ROOT);
    }

    private static boolean isBranchLocationHeader(String textUpper) {
        return textUpper.contains("CƠ SỞ")
                || textUpper.contains("CHI NHÁNH")
                || textUpper.contains("ĐỊA CHỈ")
                || textUpper.contains("QUẬN 1")
                || textUpper.contains("HÀ NỘI")
                || textUpper.contains("TP.HCM")
                || textUpper.contains("SÀI GÒN")
                || textUpper.contains("MỸ ĐÌNH")
                || textUpper.contains("BÍCH CÂU")
                || textUpper.contains("LONG BIÊN");
    }

    public record BranchInfo(String region, String subLocation) {}

    /**
     * Phân giải vùng địa lý / chi nhánh và cơ sở cụ thể.
     */
    public static BranchInfo resolveBranchInfo(String[] headingPath, String text, List<String> branchTags) {
        String hJoined = headingPath != null ? String.join(" ", headingPath) : "";
        String combined = (hJoined + " " + (text != null ? text : "")).toLowerCase(Locale.ROOT);
        List<String> tags = branchTags != null ? branchTags : List.of();

        boolean isHanoi = combined.contains("hà nội") || combined.contains("ha noi")
                || tags.contains("my_dinh") || tags.contains("bich_cau") || tags.contains("long_bien");
        boolean isHcm = combined.contains("tp.hcm") || combined.contains("tphcm") || combined.contains("sài gòn") || combined.contains("sai gon") || combined.contains("hồ chí minh")
                || tags.contains("quan_1");

        String region = "Toàn hệ thống";
        if (isHanoi && !isHcm) {
            region = "Chi nhánh Hà Nội";
        } else if (isHcm && !isHanoi) {
            region = "Chi nhánh TP.HCM";
        } else if (isHanoi && isHcm) {
            String hLow = hJoined.toLowerCase(Locale.ROOT);
            if (hLow.contains("tp.hcm") || hLow.contains("quận 1") || hLow.contains("sài gòn")) {
                region = "Chi nhánh TP.HCM";
            } else {
                region = "Chi nhánh Hà Nội";
            }
        } else {
            Matcher m = Pattern.compile("(?iU)(?:chi\\s+nhánh|cơ\\s+sở)\\s+([A-Za-zÀ-ỹ\\s]+)").matcher(combined);
            if (m.find()) {
                String candidate = m.group(1).trim();
                if (candidate.length() >= 3 && candidate.length() <= 30) {
                    region = "Chi nhánh " + candidate;
                }
            }
        }

        String subLocation = null;
        if (combined.contains("mỹ đình") || tags.contains("my_dinh")) {
            subLocation = "Mỹ Đình";
        } else if (combined.contains("bích câu") || tags.contains("bich_cau")) {
            subLocation = "Bích Câu";
        } else if (combined.contains("long biên") || tags.contains("long_bien")) {
            subLocation = "Long Biên";
        } else if (combined.contains("quận 1") || tags.contains("quan_1")) {
            subLocation = "Quận 1";
        }

        return new BranchInfo(region, subLocation);
    }

    /**
     * Bóc tách các chính sách ưu đãi từ khối văn bản/bảng PROMO.
     */
    public static List<String> extractPromoPolicies(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> promos = new ArrayList<>();
        String[] lines = text.split("\\r?\\n");

        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("|") || trimmed.contains("---")) continue;
            String[] cells = trimmed.split("\\|");
            if (cells.length < 3) continue;

            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (lower.contains("giảm") || lower.contains("chiết khấu") || lower.contains("ưu đãi") || lower.contains("%")) {
                String c1 = cells[1].trim();
                String c2 = cells[2].trim();
                if (c1.toLowerCase(Locale.ROOT).matches(".*(mua|buổi|voucher|liệu trình).*") && c2.toLowerCase(Locale.ROOT).matches(".*(giảm|\\d+%).*")) {
                    String discount = c2.replaceFirst("(?i)tổng hóa đơn", "").strip();
                    promos.add(discount + " khi " + c1.toLowerCase(Locale.ROOT) + ".");
                } else if (c2.toLowerCase(Locale.ROOT).matches(".*(mua|buổi|voucher|liệu trình).*") && c1.toLowerCase(Locale.ROOT).matches(".*(giảm|\\d+%).*")) {
                    String discount = c1.replaceFirst("(?i)tổng hóa đơn", "").strip();
                    promos.add(discount + " khi " + c2.toLowerCase(Locale.ROOT) + ".");
                }
            }
        }

        if (promos.isEmpty()) {
            for (String line : lines) {
                String cleaned = line.trim().replaceAll("^[\\-*#\\s]+", "");
                String lower = cleaned.toLowerCase(Locale.ROOT);
                if ((lower.contains("giảm") || lower.contains("ưu đãi")) && lower.contains("%")) {
                    promos.add(cleaned.endsWith(".") ? cleaned : cleaned + ".");
                }
            }
        }

        return promos;
    }

    /**
     * Định dạng khoảng mã gói (e.g. NS01 - NS07).
     */
    public static String formatCodeSpan(Set<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return "";
        }
        List<String> sorted = new ArrayList<>(codes);
        Collections.sort(sorted);
        if (sorted.size() == 1) {
            return "(" + sorted.get(0) + ")";
        }
        String first = sorted.get(0);
        String last = sorted.get(sorted.size() - 1);
        String prefixFirst = first.replaceAll("\\d+$", "");
        String prefixLast = last.replaceAll("\\d+$", "");
        if (!prefixFirst.isEmpty() && prefixFirst.equalsIgnoreCase(prefixLast)) {
            return "(" + first + " - " + last + ")";
        }
        if (sorted.size() <= 3) {
            return "(" + String.join(", ", sorted) + ")";
        }
        return "(" + first + " - " + last + ")";
    }

    public static String formatVnd(Long price) {
        if (price == null) {
            return "Liên hệ";
        }
        return String.format(Locale.GERMANY, "%,d", price) + " VNĐ";
    }

    // =========================================================================
    // BỘ THU GOM TỔNG HỢP THEO NHÓM DỊCH VỤ & KHU VỰC
    // =========================================================================

    private static class CategoryAggregator {
        private final String categoryName;
        private final Map<String, RegionAggregator> regionMap = new LinkedHashMap<>();

        CategoryAggregator(String categoryName) {
            this.categoryName = categoryName;
        }

        void addChunk(BranchInfo branch, ChunkMetadata meta) {
            RegionAggregator reg = regionMap.computeIfAbsent(branch.region(), k -> new RegionAggregator(branch.region()));
            reg.add(branch.subLocation(), meta);
        }

        String formatCategory() {
            if (regionMap.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("- ").append(categoryName).append(":\n");
            for (RegionAggregator reg : regionMap.values()) {
                sb.append("  ").append(reg.formatRegionLine()).append('\n');
            }
            return sb.toString().stripTrailing();
        }
    }

    private static class RegionAggregator {
        private final String regionName;
        private final Set<String> subLocations = new LinkedHashSet<>();
        private final List<Long> minPrices = new ArrayList<>();
        private final List<Long> maxPrices = new ArrayList<>();
        private final Set<String> serviceCodes = new LinkedHashSet<>();

        RegionAggregator(String regionName) {
            this.regionName = regionName;
        }

        void add(String subLocation, ChunkMetadata meta) {
            if (subLocation != null && !subLocation.isBlank()) {
                subLocations.add(subLocation);
            }
            if (meta.minPrice() != null) {
                minPrices.add(meta.minPrice());
            }
            if (meta.maxPrice() != null) {
                maxPrices.add(meta.maxPrice());
            }
            if (meta.serviceCodes() != null) {
                serviceCodes.addAll(meta.serviceCodes());
            }
        }

        String formatRegionLine() {
            Long min = minPrices.isEmpty() ? null : Collections.min(minPrices);
            Long max = maxPrices.isEmpty() ? null : Collections.max(maxPrices);

            StringBuilder sb = new StringBuilder();
            sb.append("* ").append(regionName);
            if (!subLocations.isEmpty()) {
                sb.append(" (").append(String.join(", ", subLocations)).append(")");
            }
            sb.append(": ");

            if (min != null && max != null) {
                if (min.equals(max)) {
                    sb.append(formatVnd(min));
                } else {
                    sb.append(formatVnd(min)).append(" - ").append(formatVnd(max));
                }
            } else if (min != null) {
                sb.append("Từ ").append(formatVnd(min));
            } else {
                sb.append("Liên hệ");
            }

            String codeSpan = formatCodeSpan(serviceCodes);
            if (!codeSpan.isBlank()) {
                sb.append(' ').append(codeSpan);
            }

            return sb.toString();
        }
    }
}
