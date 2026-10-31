package com.example.docmind.service.bot;

import com.example.docmind.dto.PagePersonaConfig;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
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
 * Bộ đối sánh phạm vi nhu cầu đa ngành ({@code ScopeMatcher} — Checkpoint C3-A):
 * Thay thế chuỗi regex bộ phận cơ thể cố định bằng cơ chế đối sánh động trực tiếp từ
 * {@link PackageCatalogService.PackageSummary#inferred()} ({@code scopeTags}) và
 * {@link PagePersonaConfig#scopeTaxonomy()}, đồng thời hỗ trợ thu hồi ({@code RETRACT})
 * phạm vi khi khách hàng phủ định/rút lại nhu cầu trước đó.
 */
public final class ScopeMatcher {

    /** Mẫu mệnh đề khách chủ động rút lại / hủy phạm vi đã nêu ("không làm vùng lưng nữa", "bỏ phòng bếp", "thôi không ốp sân thượng"). */
    private static final Pattern RETRACTED_SCOPE_CLAUSE_PATTERN = Pattern.compile(
            "(?isU)(?:à\\s+thôi|thôi|tạm\\s+thời)?\\s*(?:mình\\s+|chị\\s+|anh\\s+|em\\s+|tôi\\s+)?"
            + "(?:không\\s+(?:làm|chăm\\s+sóc|massage|ốp|lát|lấy|chọn|học|đăng\\s+ký)|bỏ(?:\\s+qua)?|hủy)\\s+"
            + "(?:vùng\\s+|phần\\s+|khu\\s+vực\\s+|phòng\\s+|mảng\\s+|khóa\\s+)?"
            + "([\\p{L}0-9\\s]{2,28}?)(?:\\s+nữa|\\s+nhé|\\s+nha|\\s+ạ|\\s+đâu|[,.!;]|$)"
    );

    /** Che các cụm từ dễ gây bắt nhầm âm tiết đơn trước khi quét taxonomy. */
    private static final Pattern FALSE_POSITIVE_MASK_PATTERN = Pattern.compile(
            "(?iU)\\b(?:chân\\s+không|chân\\s+thành|chân\\s+thật|chân\\s+dung|tay\\s+nghề|sổ\\s+tay|chia\\s+tay|vai\\s+trò|"
            + "quyền\\s+lợi|lợi\\s+ích|tiện\\s+lợi|thuận\\s+lợi|có\\s+lợi|lợi\\s+nhuận|"
            + "lần\\s+đầu|buổi\\s+đầu|bắt\\s+đầu|ban\\s+đầu|hàng\\s+đầu|từ\\s+đầu|đầu\\s+tiên|đầu\\s+ra|đầu\\s+vào|"
            + "cổ\\s+điển|cổ\\s+phần|cổ\\s+đông|cổ\\s+phiếu)\\b"
    );

    /**
     * Từ điển đồng nghĩa / cụm từ ghép tiếng Việt đa ngành dùng để ánh xạ biểu đạt tự nhiên của khách
     * về nhãn phạm vi chuẩn (ưu tiên nhãn có trong {@code scopeTags} của Catalog hoặc {@code scopeTaxonomy} của Page).
     */
    private static final Map<String, List<String>> MULTI_INDUSTRY_COMPOUND_ALIASES;

    static {
        Map<String, List<String>> m = new LinkedHashMap<>();
        // 1. Vật liệu xây dựng / Nội thất / Kiến trúc
        m.put("phòng khách", List.of("phòng khách", "sảnh khách"));
        m.put("phòng bếp", List.of("phòng bếp", "nhà bếp", "khu bếp", "tường bếp"));
        m.put("phòng ngủ", List.of("phòng ngủ"));
        m.put("phòng tắm", List.of("phòng tắm", "nhà tắm", "nhà vệ sinh", "wc", "toilet"));
        m.put("ban công", List.of("ban công", "lô gia"));
        m.put("sân thượng", List.of("sân thượng"));
        m.put("sân vườn", List.of("sân vườn", "lối đi sân vườn", "ngoài trời"));
        m.put("mặt tiền", List.of("mặt tiền", "ốp mặt tiền"));
        m.put("cầu thang", List.of("cầu thang"));
        m.put("lát nền", List.of("lát nền", "lát sàn", "gạch nền"));
        m.put("ốp tường", List.of("ốp tường", "gạch ốp"));
        m.put("sofa", List.of("sofa"));
        m.put("ghế", List.of("ghế"));
        m.put("đệm", List.of("đệm"));
        m.put("nệm", List.of("nệm"));
        m.put("giường", List.of("giường"));
        m.put("tủ", List.of("tủ"));

        // 2. Giáo dục / Trung tâm ngoại ngữ / Đào tạo
        m.put("ielts", List.of("ielts", "luyện thi ielts"));
        m.put("toeic", List.of("toeic", "luyện thi toeic"));
        m.put("giao tiếp", List.of("giao tiếp", "tiếng anh giao tiếp", "nghe nói", "phản xạ giao tiếp"));
        m.put("người đi làm", List.of("người đi làm", "tiếng anh công sở", "tiếng anh thương mại"));
        m.put("thiếu nhi", List.of("thiếu nhi", "trẻ em", "các bé"));
        m.put("mất gốc", List.of("mất gốc", "người mới bắt đầu", "căn bản"));

        // 3. Chăm sóc sức khỏe / Nha khoa / Thẩm mỹ / Dưỡng sinh (tương thích dữ liệu & unit test hiện có)
        m.put("thắt lưng", List.of("thắt lưng"));
        m.put("lưng", List.of("lưng", "cột sống", "eo", "hông"));
        m.put("đầu", List.of("nửa đầu", "thái dương", "trán", "da đầu", "nhức đầu", "đau đầu", "nặng đầu", "đầu"));
        m.put("cổ", List.of("cổ"));
        m.put("vai", List.of("bả vai", "vai"));
        m.put("gáy", List.of("sau gáy", "gáy"));
        m.put("mông", List.of("mông"));
        m.put("gối", List.of("đầu gối", "khớp gối", "mỏi gối", "đau gối", "nhức gối", "sưng gối", "tê gối", "cứng gối", "gối đau", "gối mỏi", "gối nhức", "gối"));
        m.put("tay", List.of("cánh tay", "bàn tay", "cổ tay", "ngón tay", "tê tay", "mỏi tay", "đau tay", "tay"));
        m.put("chân", List.of("bắp chân", "bàn chân", "cổ chân", "gót chân", "đùi", "chuột rút", "tê chân", "mỏi chân", "đau chân", "chân"));
        m.put("toàn thân", List.of("toàn thân", "cả người", "khắp người", "body"));
        m.put("da mặt", List.of("da mặt", "chăm sóc da mặt"));
        m.put("da", List.of("da"));
        m.put("mụn", List.of("mụn", "trị mụn"));
        m.put("nám", List.of("nám", "tàn nhang"));
        m.put("tóc", List.of("tóc"));
        m.put("tai", List.of("tai", "ù tai"));
        m.put("răng", List.of("chân răng", "răng hàm", "răng khôn", "răng cửa", "tẩy trắng răng", "bọc răng", "niềng răng", "răng"));
        m.put("nướu", List.of("nướu", "viêm nướu", "chảy máu nướu"));
        m.put("lợi", List.of("lợi", "viêm lợi", "chảy máu lợi"));
        m.put("tủy", List.of("tủy", "viêm tủy"));
        m.put("hàm", List.of("hàm", "khớp hàm"));
        MULTI_INDUSTRY_COMPOUND_ALIASES = Map.copyOf(m);
    }

    private ScopeMatcher() {
    }

    /**
     * Hợp nhất phạm vi từ lịch sử + lượt nói hiện tại dựa trên {@code PackageSummary.inferred().scopeTags()}
     * và {@code PagePersonaConfig.scopeTaxonomy()}, đồng thời loại bỏ các phạm vi khách vừa phủ định ({@code RETRACT}).
     */
    public static List<String> mergeScopes(
            String customerMessage,
            Collection<String> priorScopes,
            List<PackageCatalogService.PackageSummary> catalog,
            PagePersonaConfig personaConfig
    ) {
        Set<String> explicitCatalogAndPageTags = collectConfiguredScopeVocabulary(catalog, personaConfig);
        Map<String, List<String>> effectiveLexicon = buildEffectiveLexicon(explicitCatalogAndPageTags, personaConfig);

        Set<String> retracted = extractRetractedScopes(customerMessage, effectiveLexicon, explicitCatalogAndPageTags);
        LinkedHashSet<String> merged = new LinkedHashSet<>();

        if (priorScopes != null) {
            for (String prior : priorScopes) {
                if (prior == null || prior.isBlank()) {
                    continue;
                }
                List<String> normalizedPriors = matchScopesInText(prior, effectiveLexicon, explicitCatalogAndPageTags, false);
                if (!normalizedPriors.isEmpty()) {
                    for (String np : normalizedPriors) {
                        if (!isScopeRetracted(np, retracted)) {
                            merged.add(np);
                        }
                    }
                } else {
                    String cleanPrior = normalizeText(prior);
                    if (!cleanPrior.isEmpty() && !isSymptomOrModalityNonScope(cleanPrior) && !isScopeRetracted(cleanPrior, retracted)) {
                        merged.add(cleanPrior);
                    }
                }
            }
        }

        if (customerMessage != null && !customerMessage.isBlank()) {
            List<String> currentTurnScopes = matchScopesInText(customerMessage, effectiveLexicon, explicitCatalogAndPageTags, true);
            for (String scope : currentTurnScopes) {
                if (!isScopeRetracted(scope, retracted)) {
                    merged.add(scope);
                }
            }
        }

        return new ArrayList<>(merged);
    }

    /**
     * Đối sánh phạm vi từ 1 văn bản đơn lẻ dựa trên {@code catalog} và {@code personaConfig}.
     */
    public static List<String> matchScopes(
            String text,
            List<PackageCatalogService.PackageSummary> catalog,
            PagePersonaConfig personaConfig
    ) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Set<String> explicitTags = collectConfiguredScopeVocabulary(catalog, personaConfig);
        Map<String, List<String>> effectiveLexicon = buildEffectiveLexicon(explicitTags, personaConfig);
        Set<String> retracted = extractRetractedScopes(text, effectiveLexicon, explicitTags);
        List<String> matched = matchScopesInText(text, effectiveLexicon, explicitTags, true);
        if (retracted.isEmpty()) {
            return matched;
        }
        return matched.stream().filter(s -> !isScopeRetracted(s, retracted)).toList();
    }

    /**
     * Adapter hợp nhất phạm vi khi chỉ truyền danh sách {@code taxonomyOrDomainKeywords}.
     */
    public static List<String> mergeScopes(
            String customerMessage,
            Collection<String> priorScopes,
            List<String> taxonomyOrDomainKeywords
    ) {
        Set<String> explicitTags = new LinkedHashSet<>();
        if (taxonomyOrDomainKeywords != null) {
            for (String kw : taxonomyOrDomainKeywords) {
                if (kw != null && !kw.isBlank()) {
                    String norm = canonicalizeConfiguredScopeTag(normalizeText(kw));
                    if (!norm.isEmpty() && !isSymptomOrModalityNonScope(norm)) {
                        explicitTags.add(norm);
                    }
                }
            }
        }
        Map<String, List<String>> effectiveLexicon = buildEffectiveLexicon(explicitTags, null);
        Set<String> retracted = extractRetractedScopes(customerMessage, effectiveLexicon, explicitTags);
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (priorScopes != null) {
            for (String prior : priorScopes) {
                if (prior == null || prior.isBlank()) {
                    continue;
                }
                List<String> normalizedPriors = matchScopesInText(prior, effectiveLexicon, explicitTags, false);
                if (!normalizedPriors.isEmpty()) {
                    for (String np : normalizedPriors) {
                        if (!isScopeRetracted(np, retracted)) {
                            merged.add(np);
                        }
                    }
                } else {
                    String cleanPrior = normalizeText(prior);
                    if (!cleanPrior.isEmpty() && !isSymptomOrModalityNonScope(cleanPrior) && !isScopeRetracted(cleanPrior, retracted)) {
                        merged.add(cleanPrior);
                    }
                }
            }
        }
        if (customerMessage != null && !customerMessage.isBlank()) {
            for (String scope : matchScopesInText(customerMessage, effectiveLexicon, explicitTags, true)) {
                if (!isScopeRetracted(scope, retracted)) {
                    merged.add(scope);
                }
            }
        }
        return new ArrayList<>(merged);
    }

    /**
     * Adapter tương thích cho các hàm chỉ truyền {@code domainKeywords / scopeTaxonomy}.
     */
    public static List<String> extractScopesFromText(String text, List<String> taxonomyOrDomainKeywords) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Set<String> explicitTags = new LinkedHashSet<>();
        if (taxonomyOrDomainKeywords != null) {
            for (String kw : taxonomyOrDomainKeywords) {
                if (kw != null && !kw.isBlank()) {
                    String norm = canonicalizeConfiguredScopeTag(normalizeText(kw));
                    if (!norm.isEmpty() && !isSymptomOrModalityNonScope(norm)) {
                        explicitTags.add(norm);
                    }
                }
            }
        }
        Map<String, List<String>> effectiveLexicon = buildEffectiveLexicon(explicitTags, null);
        Set<String> retracted = extractRetractedScopes(text, effectiveLexicon, explicitTags);
        List<String> matched = matchScopesInText(text, effectiveLexicon, explicitTags, true);
        if (retracted.isEmpty()) {
            return matched;
        }
        return matched.stream().filter(s -> !isScopeRetracted(s, retracted)).toList();
    }

    /**
     * Phát hiện danh sách phạm vi khách chủ động phủ định / rút lại ({@code RETRACT}) trong câu nói.
     */
    public static Set<String> extractRetractedScopes(
            String text,
            List<PackageCatalogService.PackageSummary> catalog,
            PagePersonaConfig personaConfig
    ) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        Set<String> explicitTags = collectConfiguredScopeVocabulary(catalog, personaConfig);
        Map<String, List<String>> effectiveLexicon = buildEffectiveLexicon(explicitTags, personaConfig);
        return extractRetractedScopes(text, effectiveLexicon, explicitTags);
    }

    private static Set<String> extractRetractedScopes(
            String text,
            Map<String, List<String>> effectiveLexicon,
            Set<String> explicitTags
    ) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        String normText = normalizeText(text);
        Set<String> retracted = new LinkedHashSet<>();
        Matcher m = RETRACTED_SCOPE_CLAUSE_PATTERN.matcher(normText);
        while (m.find()) {
            String clauseTarget = m.group(1);
            if (clauseTarget == null || clauseTarget.isBlank()) {
                continue;
            }
            List<String> scopesInRetraction = matchScopesInText(clauseTarget, effectiveLexicon, explicitTags, false);
            if (!scopesInRetraction.isEmpty()) {
                retracted.addAll(scopesInRetraction);
            } else {
                String trimmed = clauseTarget.strip();
                if (!trimmed.isEmpty() && trimmed.split("\\s+").length <= 3) {
                    retracted.add(trimmed);
                }
            }
        }
        return retracted;
    }

    private static boolean isScopeRetracted(String scope, Set<String> retracted) {
        if (retracted == null || retracted.isEmpty() || scope == null) {
            return false;
        }
        String s = normalizeText(scope);
        for (String r : retracted) {
            if (r.equals(s)
                    || ("lưng".equals(r) && "thắt lưng".equals(s))
                    || ("thắt lưng".equals(r) && "lưng".equals(s))) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> collectConfiguredScopeVocabulary(
            List<PackageCatalogService.PackageSummary> catalog,
            PagePersonaConfig personaConfig
    ) {
        Set<String> tags = new LinkedHashSet<>();
        if (catalog != null) {
            for (PackageCatalogService.PackageSummary pkg : catalog) {
                if (pkg == null) {
                    continue;
                }
                List<String> inferredTags = (pkg.inferred() != null && pkg.inferred().scopeTags() != null)
                        ? pkg.inferred().scopeTags()
                        : pkg.scopeTags();
                if (inferredTags != null) {
                    for (String t : inferredTags) {
                        String norm = canonicalizeConfiguredScopeTag(normalizeText(t));
                        if (!norm.isEmpty() && !isSymptomOrModalityNonScope(norm)) {
                            tags.add(norm);
                        }
                    }
                }
            }
        }
        if (personaConfig != null) {
            if (personaConfig.scopeTaxonomy() != null) {
                for (String t : personaConfig.scopeTaxonomy()) {
                    String norm = canonicalizeConfiguredScopeTag(normalizeText(t));
                    if (!norm.isEmpty() && !isSymptomOrModalityNonScope(norm)) {
                        tags.add(norm);
                    }
                }
            }
            if (personaConfig.bodyAreaKeywords() != null) {
                for (String t : personaConfig.bodyAreaKeywords()) {
                    String norm = canonicalizeConfiguredScopeTag(normalizeText(t));
                    if (!norm.isEmpty() && !isSymptomOrModalityNonScope(norm)) {
                        tags.add(norm);
                    }
                }
            }
        }
        return tags;
    }

    private static String canonicalizeConfiguredScopeTag(String norm) {
        if (norm == null || norm.isBlank()) {
            return "";
        }
        return switch (norm) {
            case "bắp chân", "bàn chân" -> "chân";
            case "đầu gối", "khớp gối" -> "gối";
            case "cánh tay", "bàn tay" -> "tay";
            case "eo" -> "lưng";
            case "body" -> "toàn thân";
            default -> norm;
        };
    }

    /**
     * Loại bỏ các tính từ triệu chứng ("đau", "mỏi", "nhức", "tê", "căng"...) và tên loại hình dịch vụ/vật liệu chung
     * ("massage", "dưỡng sinh", "gạch chống trơn", "porcelain"...) thường nằm trong {@code domain_keywords} cũ để không nhận nhầm làm {@code scopeTag}.
     */
    private static boolean isSymptomOrModalityNonScope(String norm) {
        if (norm == null || norm.isBlank()) {
            return true;
        }
        if (Set.of(
                "đau", "mỏi", "nhức", "nhói", "tê", "căng", "căng cứng", "sưng", "viêm", "ê buốt",
                "mất ngủ", "khó ngủ", "stress", "thư giãn", "phục hồi", "trị liệu",
                "gội đầu", "dưỡng sinh", "massage", "mát xa", "xoa bóp", "bấm huyệt",
                "ngâm thảo dược", "ngâm chân", "đá nóng", "ráy tai", "vệ sinh tai",
                "porcelain", "granite", "ceramic", "men matt", "men bóng", "chống trơn",
                "tư vấn", "sản phẩm", "dịch vụ", "liệu trình", "bảng giá", "báo giá",
                "ưu đãi", "khuyến mãi", "bảo hành", "đặt hàng", "đặt lịch", "chi phí", "học phí",
                "kích thước", "chất liệu", "lắp đặt", "vận chuyển", "mã sản phẩm", "bảng size", "đổi size", "ship"
        ).contains(norm)) {
            return true;
        }
        if (norm.startsWith("gạch ") || norm.startsWith("massage ") || norm.startsWith("gội ")) {
            return true;
        }
        return norm.matches("^(?:đau|mỏi|nhức|tê|căng|sưng|viêm)\\s+.+");
    }

    private static Map<String, List<String>> buildEffectiveLexicon(
            Set<String> explicitTags,
            PagePersonaConfig personaConfig
    ) {
        Map<String, List<String>> lexicon = new LinkedHashMap<>();

        // 1. Đưa toàn bộ tag từ PackageSummary.inferred().scopeTags() và PagePersonaConfig.scopeTaxonomy() lên đầu
        for (String tag : explicitTags) {
            if (tag == null || tag.isBlank()) {
                continue;
            }
            // Nếu tag là cụm ghép kiểu "cổ vai gáy" thì tách từng thành phần nguyên tử nếu có
            if (tag.contains(" ") && (tag.equals("cổ vai gáy") || tag.equals("vai gáy") || tag.equals("đầu cổ vai gáy"))) {
                for (String part : tag.split("\\s+")) {
                    if (!part.isBlank()) {
                        List<String> aliases = new ArrayList<>();
                        aliases.add(part);
                        aliases.addAll(MULTI_INDUSTRY_COMPOUND_ALIASES.getOrDefault(part, List.of()));
                        lexicon.put(part, List.copyOf(new LinkedHashSet<>(aliases)));
                    }
                }
                continue;
            }
            List<String> aliases = new ArrayList<>();
            aliases.add(tag);
            if (MULTI_INDUSTRY_COMPOUND_ALIASES.containsKey(tag)) {
                aliases.addAll(MULTI_INDUSTRY_COMPOUND_ALIASES.get(tag));
            }
            lexicon.put(tag, List.copyOf(new LinkedHashSet<>(aliases)));
        }

        // 2. Bổ sung querySynonyms từ PagePersonaConfig (nếu Page có định nghĩa đồng nghĩa riêng)
        if (personaConfig != null && !personaConfig.resolvedQuerySynonyms().isEmpty()) {
            for (Map.Entry<String, List<String>> entry : personaConfig.resolvedQuerySynonyms().entrySet()) {
                String key = normalizeText(entry.getKey());
                if (key.isEmpty()) {
                    continue;
                }
                List<String> mergedAliases = new ArrayList<>(lexicon.getOrDefault(key, List.of(key)));
                for (String syn : entry.getValue()) {
                    String normSyn = normalizeText(syn);
                    if (!normSyn.isEmpty() && !mergedAliases.contains(normSyn)) {
                        mergedAliases.add(normSyn);
                    }
                }
                lexicon.put(key, List.copyOf(mergedAliases));
            }
        }

        // 3. Bổ sung từ điển đồng nghĩa đa ngành chuẩn để không bỏ sót khi khách nói tự nhiên hoặc khi unit test truyền catalog rỗng
        for (Map.Entry<String, List<String>> entry : MULTI_INDUSTRY_COMPOUND_ALIASES.entrySet()) {
            String canonical = entry.getKey();
            if ("thắt lưng".equals(canonical) && !explicitTags.contains("thắt lưng")) {
                // Nếu Catalog/Page không tách riêng tag "thắt lưng" mà dùng tag "lưng", ánh xạ "thắt lưng" vào "lưng"
                List<String> lungAliases = new ArrayList<>(lexicon.getOrDefault("lưng", MULTI_INDUSTRY_COMPOUND_ALIASES.get("lưng")));
                if (!lungAliases.contains("thắt lưng")) {
                    lungAliases.add(0, "thắt lưng");
                }
                lexicon.put("lưng", List.copyOf(new LinkedHashSet<>(lungAliases)));
                continue;
            }
            if (!lexicon.containsKey(canonical)) {
                lexicon.put(canonical, entry.getValue());
            }
        }

        return lexicon;
    }

    private record ScopeHit(String canonicalScope, int firstIndex, int matchedLength) {
    }

    private static List<String> matchScopesInText(
            String rawText,
            Map<String, List<String>> lexicon,
            Set<String> explicitTags,
            boolean stripCritiqueAndRetractionClauses
    ) {
        if (rawText == null || rawText.isBlank()) {
            return List.of();
        }
        String working = stripCritiqueAndRetractionClauses
                ? SentimentIntentLayer.stripMismatchCritiqueClause(rawText)
                : rawText;
        if (stripCritiqueAndRetractionClauses) {
            working = RETRACTED_SCOPE_CLAUSE_PATTERN.matcher(working).replaceAll(" ");
        }
        String normalized = normalizeText(working);
        if (normalized.isEmpty()) {
            return List.of();
        }

        // Xử lý các cặp từ ghép dễ giao thoa trước khi quét ranh giới từ
        String masked = normalized
                .replaceAll("(?iU)\\bchân\\s+răng\\b", "răng")
                .replaceAll("(?iU)\\bcổ\\s+tay\\b", "tay")
                .replaceAll("(?iU)\\bcổ\\s+chân\\b", "chân")
                .replaceAll("(?iU)\\bda\\s+đầu\\b", "đầu");
        if (!explicitTags.contains("gội đầu")) {
            masked = masked.replaceAll("(?iU)\\bgội\\s+đầu\\b", "gội ");
        }
        // Tránh bắt chữ "gối" khi khách nói "kê gối cao", "nằm gối", "gối ôm" (trừ khi nói đau/mỏi/khớp/đầu gối)
        masked = masked.replaceAll("(?iU)\\b(?:nằm|kê|ôm|chiếc|cái)\\s+gối(?:\\s+cao|\\s+thấp|\\s+mềm|\\s+ôm|\\s+đầu)?\\b|\\bgối\\s+(?:ôm|nằm|kê)\\b", " ");
        masked = FALSE_POSITIVE_MASK_PATTERN.matcher(masked).replaceAll(" ");

        List<ScopeHit> hits = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : lexicon.entrySet()) {
            String canonical = entry.getKey();
            int earliestIdx = Integer.MAX_VALUE;
            int bestLen = 0;
            for (String alias : entry.getValue()) {
                if (alias == null || alias.isBlank()) {
                    continue;
                }
                int idx = indexOfWholePhrase(masked, alias);
                if (idx >= 0 && (idx < earliestIdx || (idx == earliestIdx && alias.length() > bestLen))) {
                    earliestIdx = idx;
                    bestLen = alias.length();
                }
            }
            if (earliestIdx < Integer.MAX_VALUE) {
                hits.add(new ScopeHit(canonical, earliestIdx, bestLen));
            }
        }

        hits.sort(Comparator.comparingInt(ScopeHit::firstIndex).thenComparing((a, b) -> Integer.compare(b.matchedLength(), a.matchedLength())));

        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (ScopeHit hit : hits) {
            if ("da".equals(hit.canonicalScope()) && ordered.contains("da mặt")) {
                continue;
            }
            ordered.add(hit.canonicalScope());
        }
        return new ArrayList<>(ordered);
    }

    private static int indexOfWholePhrase(String text, String phrase) {
        int from = 0;
        int len = phrase.length();
        while (from <= text.length() - len) {
            int idx = text.indexOf(phrase, from);
            if (idx < 0) {
                return -1;
            }
            boolean leftBoundary = (idx == 0) || !Character.isLetterOrDigit(text.charAt(idx - 1));
            int end = idx + len;
            boolean rightBoundary = (end == text.length()) || !Character.isLetterOrDigit(text.charAt(end));
            if (leftBoundary && rightBoundary) {
                return idx;
            }
            from = idx + 1;
        }
        return -1;
    }

    private static String normalizeText(String s) {
        if (s == null) {
            return "";
        }
        return Normalizer.normalize(s, Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .strip();
    }
}
