package com.example.docmind.service.bot;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bộ trích xuất tín hiệu phạm vi &amp; phủ định tiếng Việt (Deterministic Pre-Guard &lt; 1ms).
 *
 * <p>Phân tách rõ ranh giới trách nhiệm giữa Regex Pre-Guard và LLM:
 * <ul>
 *   <li><b>Không bao giờ bắt từ đơn {@code "gối"} đứng độc lập làm {@code "đầu gối"}</b> (chỉ bắt {@code "đầu gối"}, {@code "khớp gối"}, {@code "đau gối"}).</li>
 *   <li><b>Nhóm D (Cấu trúc phủ định/đính chính tường minh):</b> Trích xuất tất định {@code confirmedScopes += A}, {@code forbiddenScopes += B}, xóa {@code B} khỏi {@code confirmedScopes}, và khóa {@code forcedAction = "REPAIR_AND_RECONFIRM"}.</li>
 *   <li><b>Nhóm H (Câu khẩu ngữ, đảo ngữ, phương ngữ lắt léo):</b> Nhận diện qua 3 mẫu cấu trúc ngôn ngữ học ({@code possibleCorrection = true}), chặn tự động nạp từ khóa vào {@code confirmedScopes}, không bật {@code conditionClarified = true}, và cấm nhảy sang {@code ANSWER_PRICE_ONCE} / {@code RECOMMEND_ONE_PACKAGE}.</li>
 *   <li><b>Quyền sở hữu State Bất đối xứng (Asymmetric State Ownership):</b> {@code grounding_check} của LLM không bao giờ được tự ý THÊM ({@code ADD}) phạm vi mới vào {@code confirmedScopes}, nhưng được phép tách phạm vi bị khách bác bỏ ({@code B}) ra khỏi {@code confirmedScopes} và đưa vào {@code forbiddenScopes}.</li>
 * </ul>
 */
public final class VietnameseScopeSignalExtractor {

    private VietnameseScopeSignalExtractor() {
    }

    public record ScopeSignalResult(
            Set<String> confirmedScopes,
            Set<String> forbiddenScopes,
            boolean explicitCorrectionGroupD,
            boolean possibleCorrection,
            String forcedAction,
            String errorId
    ) {
        public boolean blocksPriceAndPackageQuote() {
            return explicitCorrectionGroupD || possibleCorrection || "REPAIR_AND_RECONFIRM".equals(forcedAction);
        }

        public Set<String> confirmedScopesToAdd() {
            return confirmedScopes != null ? confirmedScopes : Set.of();
        }

        public Set<String> forbiddenScopesToAdd() {
            return forbiddenScopes != null ? forbiddenScopes : Set.of();
        }
    }

    public static ScopeSignalResult extract(String rawMessage) {
        return analyze(rawMessage, List.of());
    }

    public static ScopeSignalResult extract(String rawMessage, List<String> tenantScopeTaxonomy) {
        return analyze(rawMessage, tenantScopeTaxonomy);
    }

    // Nhóm D - Mẫu 1: "<A> chứ (có phải | không phải | đâu phải)? <B> (đâu | gì)?"
    private static final Pattern GROUP_D_A_CHU_B_PATTERN = Pattern.compile(
            "(?isU)^\\s*(?<clauseA>.+?)\\s+chứ\\s+(?:(?:có\\s+phải|không\\s+phải|đâu\\s+phải|phải)\\s+(?:là\\s+)?)?(?<clauseB>.+?)(?:\\s+(?:đâu|gì|bao\\s+giờ|nào))?\\s*[.!?]*\\s*$"
    );

    // Nhóm D - Mẫu 2: "(Đâu phải | Không phải | Có phải ... đâu) <B> [, (mà | là | tôi ...)? <A>]"
    private static final Pattern GROUP_D_DAU_PHAI_B_THEN_A_PATTERN = Pattern.compile(
            "(?isU)^\\s*(?:đâu\\s+phải|không\\s+phải|chứ\\s+không\\s+phải|có\\s+phải)\\s+(?:là\\s+|bị\\s+|đau\\s+|mỏi\\s+)?(?<clauseB>[^,;.!?]+?)(?:\\s+(?:đâu|rồi))?(?:\\s*(?:,|;|mà\\s+là|mà\\s+|là\\s+)\\s*(?<clauseA>.+?))?\\s*[.!?]*\\s*$"
    );

    // Nhóm D - Mẫu 3: "Làm gì có <B> [, <A>]"
    private static final Pattern GROUP_D_LAM_GI_CO_B_THEN_A_PATTERN = Pattern.compile(
            "(?isU)^\\s*(?:làm\\s+gì\\s+có|đâu\\s+có|chẳng\\s+có)\\s+(?:bị\\s+|đau\\s+|mỏi\\s+)?(?<clauseB>[^,;.!?]+?)(?:\\s+đâu)?(?:\\s*[,;]\\s*(?<clauseA>.+?))?\\s*[.!?]*\\s*$"
    );

    // Nhóm D - Mẫu 4: "(Tư vấn)? nhầm sang <B> (rồi)? [, <A>]"
    private static final Pattern GROUP_D_NHAM_SANG_B_THEN_A_PATTERN = Pattern.compile(
            "(?isU)^\\s*(?:.*\\b)?nhầm\\s+(?:sang\\s+|qua\\s+|vào\\s+|thành\\s+)(?<clauseB>[^,;.!?]+?)(?:\\s+(?:rồi|đó|ạ|nhé))?(?:\\s*[,;]\\s*(?<clauseA>.+?))?\\s*[.!?]*\\s*$"
    );

    // Nhóm D - Mẫu 5: "<A> mà (đi|lại|bạn|em|mày|shop) (massage|tư vấn|làm|gợi ý) <B> (làm gì|làm j|là sao)?"
    private static final Pattern GROUP_D_A_MA_DI_TU_VAN_B_PATTERN = Pattern.compile(
            "(?isU)^\\s*(?<clauseA>.+?)\\s+mà\\s+(?:(?:đi|lại|bạn|em|mày|shop|sao\\s+lại)\\s+)*(?:massage|mát\\s+xa|tư\\s+vấn|gợi\\s+ý|báo|làm)\\s+(?<clauseB>.+?)(?:\\s+(?:làm\\s+gì|làm\\s+j|là\\s+sao|vậy|thế|hả|à))?\\s*[.!?]*\\s*$"
    );

    // Nhóm H - 3 mẫu cấu trúc ngôn ngữ học tổng quát cho câu phản bác khẩu ngữ / phương ngữ:
    // (H1) Lặp từ bác bỏ: "<X> <trợ từ 1-4 ký tự> mà <X>" (VD: "gối mô mà gối", "gối gì mà gối", "lưng nào mà lưng")
    private static final Pattern GROUP_H_REDUPLICATIVE_REJECTION_PATTERN = Pattern.compile(
            "(?isU)\\b(\\p{L}{2,10})\\s+\\p{L}{1,5}\\s+mà\\s+\\1\\b"
    );

    // (H2) Tương phản hành vi ngôn từ: "(nói|bảo|kêu) ... (nghe|hiểu|tưởng) ... (à|hả|sao|vậy)"
    // (VD: "tao nói đầu, mày nghe gối à", "mình bảo cổ mà em nghe chân à")
    private static final Pattern GROUP_H_SPEECH_PERCEPTION_CONTRAST_PATTERN = Pattern.compile(
            "(?isU)\\b(?:nói|bảo|kêu|nhắn)\\b[^.!?]{1,40}\\b(?:nghe|hiểu|tưởng|đọc)\\b"
    );

    // (H3) Đính chính khẩu ngữ ngắn có tiểu từ cảm thán hoặc đảo vế ngắn:
    // (VD: "đầu chứ lị", "cổ chứ bộ", "gối đâu, đầu á", "gối đâu mà gối")
    private static final Pattern GROUP_H_COLLOQUIAL_PARTICLE_CORRECTION_PATTERN = Pattern.compile(
            "(?isU)(?:\\bchứ\\s+(?:lị|bộ|ai|sao)\\b|^\\s*\\p{L}+(?:\\s+\\p{L}+){0,2}\\s+đâu\\s*,\\s*\\p{L}+(?:\\s+(?:á|đó|cơ|nè|nhé|nha|mà))?\\s*[.!?]*\\s*$)"
    );

    private static final Pattern EXPLICIT_KNEE_COMPOUND_PATTERN = Pattern.compile(
            "(?iU)\\b(?:đầu\\s+gối|khớp\\s+gối|(?:đau|mỏi|nhức|cứng|tê|sưng)\\s+gối)\\b"
    );

    private static final Pattern EXPLICIT_CALF_OR_FOOT_COMPOUND_PATTERN = Pattern.compile(
            "(?iU)\\b(bắp\\s+chân|bàn\\s+chân|cổ\\s+chân|gót\\s+chân)\\b"
    );

    private static final Pattern ATOMIC_BODY_OR_GENERAL_SCOPE_PATTERN = Pattern.compile(
            "(?iU)(?<![\\p{L}\\d])(thắt\\s+lưng|bả\\s+vai|cánh\\s+tay|bàn\\s+tay|cổ\\s+tay|toàn\\s+thân|body|cổ|vai|gáy|lưng|eo|hông|đầu|chân|tay|mắt)(?![\\p{L}\\d])"
    );

    private static final Pattern CLAUSE_NOISE_PREFIX_SUFFIX_PATTERN = Pattern.compile(
            "(?iU)^(?:dạ|em\\s+ơi|chị\\s+ơi|anh\\s+ơi|bạn\\s+ơi|shop\\s+ơi|tôi|mình|chị|anh|em|tớ|tao|con|bị|đang\\s+bị|đang|chỉ\\s+bị|chỉ|muốn|cần|tìm|hỏi|làm|chọn|là|mà\\s+là|mà|đau|mỏi|nhức|căng|tê|sưng|tư\\s+vấn|nhầm\\s+sang|nhầm\\s+qua|nhầm)+\\s+|\\s+(?:thôi|rồi|đâu|gì|nhé|nha|ạ|á|đó|cơ|luôn|nữa)+$"
    );

    public static ScopeSignalResult analyze(String rawMessage) {
        return analyze(rawMessage, List.of());
    }

    public static ScopeSignalResult analyze(String rawMessage, List<String> tenantScopeTaxonomy) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return new ScopeSignalResult(Set.of(), Set.of(), false, false, null, null);
        }
        String normalized = Normalizer.normalize(rawMessage.strip(), Normalizer.Form.NFC);
        String lower = normalized.toLowerCase(Locale.ROOT);

        if (SentimentIntentLayer.isWordingOrExpressionFeedback(normalized)
                || SentimentIntentLayer.isConfirmPreviousInfoMessage(normalized)
                || SentimentIntentLayer.isMetaConversationOnBotBehavior(normalized)
                || SentimentIntentLayer.isAntiApologyPushback(normalized)
                || (SentimentIntentLayer.hasContrastOrClarificationPrefix(normalized)
                    && SentimentIntentLayer.isDetailOrFaqQuestion(normalized, (ConversationState) null))) {
            Set<String> confirmed = extractScopesFromClause(normalized, tenantScopeTaxonomy, false);
            return new ScopeSignalResult(
                    Collections.unmodifiableSet(confirmed),
                    Set.of(),
                    false,
                    false,
                    null,
                    null
            );
        }

        // 1. Kiểm tra Nhóm H (Câu khẩu ngữ / phương ngữ lắt léo) TRƯỚC để không bị GROUP_D_A_CHU_B_PATTERN bắt nhầm "đầu chứ lị"
        if (isGroupHColloquialPushback(lower)) {
            return new ScopeSignalResult(
                    Set.of(),
                    Set.of(),
                    false,
                    true,
                    "REPAIR_AND_RECONFIRM",
                    "WRONG_SCOPE_COLLOQUIAL"
            );
        }

        // 2. Kiểm tra Nhóm D (Cấu trúc phủ định / đính chính tường minh < 1ms)
        Matcher mD5 = GROUP_D_A_MA_DI_TU_VAN_B_PATTERN.matcher(normalized);
        if (mD5.find()) {
            ScopeSignalResult r = buildGroupDResult(mD5.group("clauseA"), mD5.group("clauseB"), tenantScopeTaxonomy);
            if (r.explicitCorrectionGroupD()) return r;
        }

        Matcher mD4 = GROUP_D_NHAM_SANG_B_THEN_A_PATTERN.matcher(normalized);
        if (mD4.find()) {
            ScopeSignalResult r = buildGroupDResult(mD4.group("clauseA"), mD4.group("clauseB"), tenantScopeTaxonomy);
            if (r.explicitCorrectionGroupD()) return r;
        }

        Matcher mD2 = GROUP_D_DAU_PHAI_B_THEN_A_PATTERN.matcher(normalized);
        if (mD2.find()) {
            ScopeSignalResult r = buildGroupDResult(mD2.group("clauseA"), mD2.group("clauseB"), tenantScopeTaxonomy);
            if (r.explicitCorrectionGroupD()) return r;
        }

        Matcher mD3 = GROUP_D_LAM_GI_CO_B_THEN_A_PATTERN.matcher(normalized);
        if (mD3.find()) {
            ScopeSignalResult r = buildGroupDResult(mD3.group("clauseA"), mD3.group("clauseB"), tenantScopeTaxonomy);
            if (r.explicitCorrectionGroupD()) return r;
        }

        Matcher mD1 = GROUP_D_A_CHU_B_PATTERN.matcher(normalized);
        if (mD1.find()) {
            String clauseA = mD1.group("clauseA");
            String clauseB = mD1.group("clauseB");
            // Đảm bảo vế B của "A chứ B..." thực sự là mệnh đề phủ định ("... đâu", "... gì", "không phải ...", "có phải ... đâu")
            boolean hasExplicitNegationCueInB = lower.matches("(?isU).*\\bchứ\\s+(?:có\\s+phải|không\\s+phải|đâu\\s+phải|phải\\s+là).*")
                    || lower.matches("(?isU).*\\bchứ\\b.*\\b(?:đâu|gì)\\s*[.!?]*\\s*$");
            if (hasExplicitNegationCueInB) {
                ScopeSignalResult r = buildGroupDResult(clauseA, clauseB, tenantScopeTaxonomy);
                if (r.explicitCorrectionGroupD()) return r;
            }
        }

        // 3. Tin nhắn khẳng định thông thường (Không phải Nhóm D hay Nhóm H)
        Set<String> confirmed = extractScopesFromClause(normalized, tenantScopeTaxonomy, false);
        return new ScopeSignalResult(
                Collections.unmodifiableSet(confirmed),
                Set.of(),
                false,
                false,
                null,
                null
        );
    }

    public static boolean isGroupHColloquialPushback(String rawOrLowerMessage) {
        if (rawOrLowerMessage == null || rawOrLowerMessage.isBlank()) {
            return false;
        }
        String lower = Normalizer.normalize(rawOrLowerMessage.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        if (GROUP_H_REDUPLICATIVE_REJECTION_PATTERN.matcher(lower).find()) {
            return true;
        }
        if (GROUP_H_SPEECH_PERCEPTION_CONTRAST_PATTERN.matcher(lower).find()) {
            return true;
        }
        return GROUP_H_COLLOQUIAL_PARTICLE_CORRECTION_PATTERN.matcher(lower).find();
    }

    private static ScopeSignalResult buildGroupDResult(
            String clauseA,
            String clauseB,
            List<String> tenantScopeTaxonomy
    ) {
        Set<String> confirmed = clauseA != null && !clauseA.isBlank()
                ? new LinkedHashSet<>(extractScopesFromClause(clauseA, tenantScopeTaxonomy, true))
                : new LinkedHashSet<>();
        Set<String> forbidden = clauseB != null && !clauseB.isBlank()
                ? new LinkedHashSet<>(extractScopesFromClause(clauseB, tenantScopeTaxonomy, true))
                : new LinkedHashSet<>();
        if (clauseB != null) {
            String normB = Normalizer.normalize(clauseB.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
            if (normB.matches("(?isU).*(?:\\bhiểu\\s+(?:lầm|nhầm)\\b|\\b\\d{2,3}\\s*(?:phút|p)\\b|\\bđau\\s+lòng\\b).*")) {
                return new ScopeSignalResult(Set.of(), Set.of(), false, false, null, null);
            }
            if (normB.matches("(?isU)^(?:vùng\\s+|phần\\s+|ở\\s+|bị\\s+|đau\\s+|mỏi\\s+)?gối(?:\\s+(?:đâu|gì|rồi|đó|ạ|nhé))?$")) {
                forbidden.add("đầu gối");
                forbidden.add("gối");
            }
        }
        if (forbidden.isEmpty()) {
            return new ScopeSignalResult(
                    Collections.unmodifiableSet(confirmed),
                    Set.of(),
                    false,
                    false,
                    null,
                    null
            );
        }
        confirmed.removeAll(forbidden);
        if (forbidden.contains("đầu gối")) {
            confirmed.remove("gối");
        }
        if (forbidden.contains("bắp chân")) {
            confirmed.remove("chân");
        }
        String primaryForbidden = forbidden.iterator().next();
        String errorId = "WRONG_SCOPE:" + primaryForbidden;
        return new ScopeSignalResult(
                Collections.unmodifiableSet(confirmed),
                Collections.unmodifiableSet(forbidden),
                true,
                false,
                "REPAIR_AND_RECONFIRM",
                errorId
        );
    }

    /**
     * Trích xuất phạm vi từ một mệnh đề đơn lẻ.
     * Tuyệt đối KHÔNG bắt từ đơn {@code "gối"} đứng độc lập làm {@code "đầu gối"}.
     */
    public static Set<String> extractScopesFromClause(
            String clause,
            List<String> tenantScopeTaxonomy,
            boolean allowCleanedPhraseFallback
    ) {
        if (clause == null || clause.isBlank()) {
            return Set.of();
        }
        String norm = Normalizer.normalize(clause.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        Set<String> scopes = new LinkedHashSet<>();

        // 1. Khớp từ vựng phạm vi của Tenant (scope_taxonomy) theo thứ tự cụm dài nhất trước
        String working = norm;
        if (tenantScopeTaxonomy != null && !tenantScopeTaxonomy.isEmpty()) {
            List<String> sortedTax = new ArrayList<>();
            for (String item : tenantScopeTaxonomy) {
                if (item != null && !item.isBlank()) {
                    String t = Normalizer.normalize(item.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
                    if (!"gối".equals(t)) {
                        sortedTax.add(t);
                    }
                }
            }
            sortedTax.sort(Comparator.comparingInt(String::length).reversed());
            for (String taxTerm : sortedTax) {
                if (taxTerm.contains(" ") && working.contains(taxTerm)) {
                    if (taxTerm.startsWith("cổ ") && taxTerm.length() <= 12) {
                        for (String part : taxTerm.split("\\s+")) {
                            scopes.add(part);
                        }
                    } else {
                        scopes.add(taxTerm);
                    }
                    working = working.replace(taxTerm, " ");
                }
            }
        }

        // 2. Bắt cụm tường minh "đầu gối" / "khớp gối" / "đau gối" -> chuẩn hóa thành "đầu gối"
        // (Tuyệt đối không bắt từ đơn "gối" đứng độc lập)
        Matcher kneeMatcher = EXPLICIT_KNEE_COMPOUND_PATTERN.matcher(working);
        if (kneeMatcher.find()) {
            scopes.add("đầu gối");
            working = kneeMatcher.replaceAll(" ");
        }

        // 3. Bắt cụm chi dưới cụ thể ("bắp chân", "bàn chân") và giấc ngủ ("mất ngủ", "khó ngủ")
        if (working.matches("(?isU).*\\b(mất\\s+ngủ|khó\\s+ngủ|giấc\\s+ngủ)\\b.*")) {
            scopes.add("giấc ngủ");
        }
        Matcher calfMatcher = EXPLICIT_CALF_OR_FOOT_COMPOUND_PATTERN.matcher(working);
        while (calfMatcher.find()) {
            scopes.add(calfMatcher.group(1).replaceAll("\\s+", " ").strip());
        }
        working = calfMatcher.replaceAll(" ");

        // 4. Bắt các phạm vi đa ngành phổ biến (Nha khoa, Nội thất, BĐS) nếu có trong câu
        Matcher multiDomainMatcher = Pattern.compile(
                "(?iU)\\b(tẩy\\s+trắng\\s+răng|nhổ\\s+răng(?:\\s+khôn)?|bọc\\s+răng\\s+sứ|niềng\\s+răng|trồng\\s+răng(?:\\s+implant)?|cạo\\s+vôi\\s+răng|lấy\\s+tủy(?:\\s+răng)?|hàn\\s+răng|trám\\s+răng|lát\\s+nền|ốp\\s+tường|sơn\\s+tường|sàn\\s+gỗ|căn\\s+\\d+\\s*pn|căn\\s+hộ\\s+\\d+\\s*pn|studio|penthouse|biệt\\s+thự|shophouse)\\b"
        ).matcher(working);
        while (multiDomainMatcher.find()) {
            scopes.add(multiDomainMatcher.group(1).replaceAll("\\s+", " ").strip());
        }
        working = multiDomainMatcher.replaceAll(" ");

        // 5. Bắt các phạm vi đơn/cụm cơ bản (tránh bắt nhầm chữ "đầu" trong "gội đầu", "lần đầu", "ban đầu", "bắt đầu")
        String maskedWorking = working
                .replaceAll("(?iU)\\b(?:gội|lần|ban|bắt)\\s+đầu\\b", " ")
                .replaceAll("(?iU)\\bchân\\s+răng\\b", "răng")
                .replaceAll("(?iU)\\bchân\\s+thành\\b", " ");
        Matcher atomicMatcher = ATOMIC_BODY_OR_GENERAL_SCOPE_PATTERN.matcher(maskedWorking);
        while (atomicMatcher.find()) {
            String raw = atomicMatcher.group(1).replaceAll("\\s+", " ").strip();
            if ("thắt lưng".equals(raw)) {
                scopes.add("lưng");
            } else if ("bả vai".equals(raw)) {
                scopes.add("vai");
            } else {
                scopes.add(raw);
            }
        }

        // 6. Nếu là mệnh đề trong cấu trúc tương phản Nhóm D ("A chứ B đâu") của ngành bất kỳ mà chưa khớp danh sách trên,
        // lọc bỏ đại từ/hư từ để lấy danh ngữ cốt lõi
        if (scopes.isEmpty() && allowCleanedPhraseFallback) {
            String cleaned = norm.replaceFirst("(?isU)^(?:bị\\s+điên\\s+à|bị\\s+đuên\\s+à|điên\\s+à|lú\\s+à|ngáo\\s+à|dở\\s+à|ủa|ơ|nhầm\\s+rồi|sai\\s+rồi)\\s*,\\s*", "").strip();
            for (int i = 0; i < 3; i++) {
                String next = CLAUSE_NOISE_PREFIX_SUFFIX_PATTERN.matcher(cleaned).replaceAll("").strip();
                if (next.equals(cleaned)) break;
                cleaned = next;
            }
            cleaned = cleaned.replaceAll("\\s+", " ").strip();
            if (!cleaned.isEmpty() && !cleaned.equals("gối") && cleaned.length() <= 35) {
                scopes.add(cleaned);
            }
        }

        return scopes;
    }

    /**
     * Quyền sở hữu State Bất đối xứng (Asymmetric State Ownership):
     * <ul>
     *   <li>{@code grounding_check} của LLM KHÔNG ĐƯỢC PHÉP tự ý THÊM ({@code ADD}) phạm vi mới vào {@code confirmedScopes} nếu Pre-Guard chưa xác tín.</li>
     *   <li>NHƯNG nếu {@code grounding_check} phát hiện khách đang PHỦ ĐỊNH / BÁC BỎ một phạm vi {@code B} (và {@code B} hoặc từ gốc của {@code B} có xuất hiện trong tin nhắn của khách), Kernel được phép tách {@code B} khỏi {@code confirmedScopes} và đưa vào {@code forbiddenScopes}.</li>
     * </ul>
     */
    public static AsymmetricGroundingDelta applyGroundingCheckNegationOnly(
            Set<String> currentConfirmedScopes,
            Set<String> currentForbiddenScopes,
            String groundingCheck,
            String rawCustomerMessage,
            List<String> tenantScopeTaxonomy
    ) {
        Set<String> nextConfirmed = new LinkedHashSet<>(currentConfirmedScopes != null ? currentConfirmedScopes : Set.of());
        Set<String> nextForbidden = new LinkedHashSet<>(currentForbiddenScopes != null ? currentForbiddenScopes : Set.of());

        if (groundingCheck == null || groundingCheck.isBlank() || rawCustomerMessage == null || rawCustomerMessage.isBlank()) {
            return new AsymmetricGroundingDelta(
                    Collections.unmodifiableSet(nextConfirmed),
                    Collections.unmodifiableSet(nextForbidden)
            );
        }

        String gcLower = Normalizer.normalize(groundingCheck, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        String msgLower = Normalizer.normalize(rawCustomerMessage, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);

        // Kiểm tra các mệnh đề chỉ sự phủ định/bác bỏ trong grounding_check
        Matcher negPattern = Pattern.compile(
                "(?isU)(?:phủ\\s+định|bác\\s+bỏ|không\\s+phải|nhầm(?:\\s+sang)?|không\\s+bị|loại\\s+bỏ|forbidden|negated|reject(?:ed)?)\\s*[:=\\-]?\\s*([^,.;|\\n]+)"
        ).matcher(gcLower);

        while (negPattern.find()) {
            String negSegment = negPattern.group(1).strip();
            Set<String> candidateNegScopes = new LinkedHashSet<>(extractScopesFromClause(negSegment, tenantScopeTaxonomy, true));
            if ((negSegment.contains("đầu gối") || negSegment.contains("gối")) && msgLower.contains("gối")) {
                candidateNegScopes.add("đầu gối");
            }
            for (String negScope : candidateNegScopes) {
                boolean presentInCustomerMsg = msgLower.contains(negScope)
                        || ("đầu gối".equals(negScope) && msgLower.contains("gối"));
                if (presentInCustomerMsg) {
                    nextConfirmed.remove(negScope);
                    if ("đầu gối".equals(negScope)) {
                        nextConfirmed.remove("gối");
                    }
                    nextForbidden.add(negScope);
                }
            }
        }

        return new AsymmetricGroundingDelta(
                Collections.unmodifiableSet(nextConfirmed),
                Collections.unmodifiableSet(nextForbidden)
        );
    }

    public record AsymmetricGroundingDelta(
            Set<String> confirmedScopes,
            Set<String> forbiddenScopes
    ) {
    }
}
