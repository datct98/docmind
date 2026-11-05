package com.example.docmind.service.bot;

import com.example.docmind.dto.PagePersonaConfig;
import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.service.security.PromptSecurityGuard;
import com.example.docmind.service.text.VietnameseQueryNormalizer;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ACI – Adaptive Conversation Intelligence (Trí tuệ hội thoại thích ứng).
 *
 * <p>Hợp nhất toàn bộ mô hình miền và cơ chế kiểm soát quyền hạn cho chế độ chuẩn duy nhất {@link CognitiveMode#LLM_COGNITIVE_V2}:
 * <ul>
 *   <li><b>Cognitive Mode ({@link CognitiveMode}):</b> {@code LLM_COGNITIVE_V2} (chế độ vận hành chính tắc duy nhất sau khi nghỉ hưu {@code DETERMINISTIC_V1} ở Unit P3.4).</li>
 *   <li><b>Tách biệt 3 luồng dữ liệu đầu vào ({@link ThreeStreamTurnInput}):</b>
 *       {@code Original Message} (bất biến gửi cho LLM + danh sách từng bong bóng burst gốc),
 *       {@code Normalized Query} (chỉ dùng nội bộ cho RAG/BM25), và
 *       {@code Unverified Parser Candidates} (ứng viên trích xuất có provenance, {@code verified=false}).</li>
 *   <li><b>Single-Pass Cognitive Contract &amp; Adaptive Cognitive Reflection ({@link AdaptiveCognitiveReflection}):</b>
 *       Null-safe, default-tolerant parser cho schema chuẩn hóa duy nhất của vòng tư duy {@code ACI Cognitive Loop}.</li>
 *   <li><b>Phân quyền 5 Tầng Bộ nhớ, Vòng đời {@code Working Hypothesis} 5 thao tác,
 *       Session-Level Commitment Management ({@code commitment_status} vs {@code execution_status}),
 *       và Conditional Handoff Policy ({@code handoff_requested} vs {@code handoff_created}).</b></li>
 * </ul>
 */
public final class AdaptiveConversationIntelligence {

    private static final Logger log = LoggerFactory.getLogger(AdaptiveConversationIntelligence.class);

    private AdaptiveConversationIntelligence() {
    }

    // =========================================================================
    // 1. FEATURE FLAG: COGNITIVE MODE
    // =========================================================================

    public enum CognitiveMode {
        LLM_COGNITIVE_V2;

        public static CognitiveMode fromProperty(String raw) {
            return LLM_COGNITIVE_V2;
        }
    }

    // =========================================================================
    // 2. THREE-STREAM INPUT SEPARATION (MỤC 2.1)
    // =========================================================================

    /**
     * Ứng viên trích xuất tự động từ regex/parser của Java (chưa xác minh — KHÔNG phải sự thật nghiệp vụ).
     */
    public record UnverifiedParserCandidate(
            String candidateType,
            String rawValue,
            String normalizedValue,
            String extractorProvenance,
            boolean verified
    ) {
        public UnverifiedParserCandidate {
            candidateType = candidateType != null ? candidateType.strip() : "UNKNOWN";
            rawValue = rawValue != null ? rawValue.strip() : "";
            normalizedValue = normalizedValue != null ? normalizedValue.strip() : "";
            extractorProvenance = extractorProvenance != null ? extractorProvenance.strip() : "java_regex_parser";
            // Bất biến an toàn nhận thức: ứng viên từ regex/parser không bao giờ tự mang cờ verified=true
            verified = false;
        }

        public static UnverifiedParserCandidate of(
                String candidateType,
                String rawValue,
                String normalizedValue,
                String extractorProvenance
        ) {
            return new UnverifiedParserCandidate(candidateType, rawValue, normalizedValue, extractorProvenance, false);
        }
    }

    /**
     * Ba luồng dữ liệu đầu vào tách biệt cho mỗi lượt hội thoại.
     */
    public record ThreeStreamTurnInput(
            String originalMessage,
            List<String> originalBurstBubbles,
            String normalizedRetrievalQuery,
            List<UnverifiedParserCandidate> unverifiedParserCandidates
    ) {
        public ThreeStreamTurnInput {
            originalMessage = originalMessage != null ? originalMessage : "";
            originalBurstBubbles = originalBurstBubbles != null ? List.copyOf(originalBurstBubbles) : List.of();
            normalizedRetrievalQuery = normalizedRetrievalQuery != null ? normalizedRetrievalQuery : "";
            unverifiedParserCandidates = unverifiedParserCandidates != null ? List.copyOf(unverifiedParserCandidates) : List.of();
        }
    }

    private static final Pattern SHADOW_DURATION_TOKEN_PATTERN = Pattern.compile(
            "(?iU)\\b(\\d+\\s*(?:ngày|tuần|tháng|năm|hôm|tiếng|giờ|phút)|vài\\s+(?:ngày|hôm|tuần|tháng)|lâu\\s+rồi|mới\\s+bị)\\b"
    );

    private static final Pattern SHADOW_MONEY_CANDIDATE_PATTERN = Pattern.compile(
            "(?iU)\\b(\\d{1,4}(?:[.,]\\d{3})+\\s*(?:đ|vnđ|vnd)?|\\d{2,4}\\s*k|\\d+(?:[.,]\\d+)?\\s*(?:tr|triệu))\\b"
    );

    private static final Pattern PAYMENT_CLAIM_STATEMENT_PATTERN = Pattern.compile(
            "(?isU).*\\b("
            + "(?:đã|vừa|mới)\\s+(?:chuyển\\s+khoản|ck|thanh\\s+toán|đặt\\s+cọc|chuyển\\s+tiền)"
            + "|(?:chuyển\\s+khoản|ck|thanh\\s+toán|đặt\\s+cọc|cọc)(?:\\s+[^.!?,;\\n]{0,32})?\\s+(?:rồi|xong)"
            + ")\\b.*"
    );

    private static final Pattern STAFF_PROMISE_CLAIM_PATTERN = Pattern.compile(
            "(?isU).*\\b((?:nhân\\s+viên|chuyên\\s+viên|quản\\s+lý|lễ\\s+tân|bạn\\s+tư\\s+vấn|bên\\s+bạn|bên\\s+em|shop)\\s+(?:của\\s+(?:bạn|shop|bên\\s+mình)\\s+)?(?:đã\\s+|vừa\\s+|hôm\\s+trước\\s+)?(?:hứa|cam\\s+kết|bảo\\s+là|báo\\s+là|hẹn\\s+là)|lời\\s+hứa\\s+của\\s+nhân\\s+viên)\\b.*"
    );

    private static final Pattern CONCRETE_SERVICE_INCIDENT_PATTERN = Pattern.compile(
            "(?isU).*\\b((?:hôm\\s+qua|hôm\\s+trước|lần\\s+trước|bữa\\s+trước|tuần\\s+trước|hôm\\s+nọ|vừa\\s+rồi)\\s+.*(?:trục\\s+trặc|sai\\s+sót|nhầm\\s+lịch|không\\s+có\\s+tên|mất\\s+lịch|quên\\s+lịch|chờ\\s+lâu|thái\\s+độ|làm\\s+ẩu|tệ|kém|đau|bầm|dị\\s+ứng|kích\\s+ứng|bỏng|sưng|chảy\\s+máu)|"
            + "(?:lịch\\s+hẹn|trải\\s+nghiệm|dịch\\s+vụ|kỹ\\s+thuật\\s+viên|ktv)\\s+.*(?:hôm\\s+qua|hôm\\s+trước|lần\\s+trước|hôm\\s+nọ)\\s+.*(?:trục\\s+trặc|lỗi|sai\\s+sót|hủy|huỷ|sai|làm\\s+ẩu|quá\\s+tệ)|"
            + "(?:trục\\s+trặc|sai\\s+sót|nhầm\\s+lịch|mất\\s+lịch)\\s+.*(?:hôm\\s+qua|hôm\\s+trước|lần\\s+trước)|"
            + "đòi\\s+hoàn\\s+tiền|yêu\\s+cầu\\s+hoàn\\s+tiền|bồi\\s+thường|khiếu\\s+nại\\s+dịch\\s+vụ)\\b.*"
    );

    private static final Pattern EXPLICIT_HUMAN_ESCALATION_PATTERN = Pattern.compile(
            "(?isU).*\\b(gặp\\s+(?:nhân\\s+viên(?:\\s+thật|\\s+trực)?|người\\s+thật|quản\\s+lý|chủ\\s+spa|bác\\s+sĩ)|"
                    + "chuyển\\s+(?:cho\\s+)?(?:quản\\s+lý|người\\s+thật|nhân\\s+viên(?:\\s+trực)?)|"
                    + "cho\\s+(?:nhân\\s+viên|quản\\s+lý|người\\s+thật)\\s+(?:hỗ\\s+trợ|tư\\s+vấn|gọi|liên\\s+hệ)|"
                    + "cần\\s+(?:nhân\\s+viên|quản\\s+lý|người\\s+thật)\\s+hỗ\\s+trợ)\\b.*"
    );

    private static final Pattern EXPLICIT_BOOKING_CANCEL_PATTERN = Pattern.compile(
            "(?isU).*\\b(hủy\\s+lịch|huỷ\\s+lịch|không\\s+đặt\\s+lịch\\s+nữa|không\\s+đặt\\s+nữa|bỏ\\s+lịch\\s+hẹn|thôi\\s+mình\\s+không\\s+đặt\\s+đâu|thôi\\s+không\\s+đặt\\s+đâu|không\\s+đặt\\s+đâu)\\b.*"
    );

    public static boolean isExplicitBookingCancelOrRefusalMessage(String message) {
        return message != null && !message.isBlank()
                && EXPLICIT_BOOKING_CANCEL_PATTERN.matcher(Normalizer.normalize(message.strip(), Normalizer.Form.NFC)).matches();
    }

    /**
     * Xây dựng 3 luồng đầu vào tách biệt từ {@link FanpageBotRequest} mà không chạm vào {@code FanpageBotRequest.java}.
     */
    public static ThreeStreamTurnInput buildThreeStreamInput(
            FanpageBotRequest request,
            List<PackageCatalogService.PackageSummary> catalog,
            PagePersonaConfig personaConfig,
            ZoneId pageZoneId
    ) {
        List<String> burstBubbles = extractOriginalBurstBubbles(request);
        String rawJoined = burstBubbles.isEmpty()
                ? (request != null && request.message() != null ? request.message() : "")
                : String.join("\n", burstBubbles);
        String originalVerbatim = VietnameseQueryNormalizer.sanitizeVerbatimOriginalMessage(rawJoined);
        List<String> sanitizedBurst = new ArrayList<>();
        int remainingBurstBudget = VietnameseQueryNormalizer.MAX_VERBATIM_LLM_INPUT_LENGTH;
        for (String b : burstBubbles) {
            if (remainingBurstBudget <= 0) {
                break;
            }
            String cleanB = VietnameseQueryNormalizer.sanitizeVerbatimOriginalMessage(b, remainingBurstBudget);
            if (!cleanB.isEmpty()) {
                sanitizedBurst.add(cleanB);
                remainingBurstBudget -= cleanB.length();
                if (remainingBurstBudget > 0) {
                    remainingBurstBudget--; // account for newline separator between burst bubbles
                }
            }
        }
        if (sanitizedBurst.isEmpty() && !originalVerbatim.isEmpty()) {
            for (String line : originalVerbatim.split("\n")) {
                if (!line.isBlank()) {
                    sanitizedBurst.add(line.strip());
                }
            }
        }

        // Luồng 2: Normalized Query — CHỈ dùng nội bộ cho truy xuất RAG/BM25 (giới hạn 2.000 ký tự của Retrieval)
        String normalizedForRetrieval = VietnameseQueryNormalizer.normalize(originalVerbatim);

        // Luồng 3: Unverified Parser Candidates — Shadow extraction có provenance, verified=false
        List<UnverifiedParserCandidate> candidates = extractUnverifiedParserCandidates(
                originalVerbatim, catalog, personaConfig, pageZoneId);

        return new ThreeStreamTurnInput(
                originalVerbatim,
                sanitizedBurst,
                normalizedForRetrieval,
                candidates
        );
    }

    public static List<String> extractOriginalBurstBubbles(FanpageBotRequest request) {
        if (request == null) {
            return List.of();
        }
        List<String> bubbles = new ArrayList<>();
        if (request.messages() != null && !request.messages().isEmpty()) {
            for (String m : request.messages()) {
                if (m != null && !m.isBlank()) {
                    for (String subLine : m.split("\\r?\\n")) {
                        if (subLine != null && !subLine.isBlank()) {
                            bubbles.add(subLine.strip());
                        }
                    }
                }
            }
        }
        if (request.message() != null && !request.message().isBlank()) {
            List<String> fromSingle = new ArrayList<>();
            for (String subLine : request.message().split("\\r?\\n")) {
                if (subLine != null && !subLine.isBlank()) {
                    fromSingle.add(subLine.strip());
                }
            }
            if (bubbles.isEmpty()) {
                bubbles.addAll(fromSingle);
            } else {
                for (String s : fromSingle) {
                    if (!bubbles.contains(s)) {
                        bubbles.add(s);
                    }
                }
            }
        }
        return List.copyOf(bubbles);
    }

    public static List<UnverifiedParserCandidate> extractUnverifiedParserCandidates(
            String originalMessage,
            List<PackageCatalogService.PackageSummary> catalog,
            PagePersonaConfig personaConfig,
            ZoneId pageZoneId
    ) {
        if (originalMessage == null || originalMessage.isBlank()) {
            return List.of();
        }
        List<UnverifiedParserCandidate> out = new ArrayList<>();
        ZoneId safeZone = pageZoneId != null ? pageZoneId : ZoneId.of("Asia/Ho_Chi_Minh");

        // 1. Date / Time parser candidates
        ConversationState.ParsedBookingDateTime parsedDt =
                ConversationState.parseBookingDateTime(originalMessage, null, null, safeZone, null);
        if (parsedDt.bookingDate() != null && !parsedDt.bookingDate().isBlank()) {
            out.add(UnverifiedParserCandidate.of(
                    "DATE_CANDIDATE",
                    parsedDt.bookingDate(),
                    parsedDt.resolvedIsoDate() != null ? parsedDt.resolvedIsoDate() : parsedDt.bookingDate(),
                    "ConversationState.parseBookingDateTime"
            ));
        }
        if (parsedDt.bookingTime() != null && !parsedDt.bookingTime().isBlank()) {
            out.add(UnverifiedParserCandidate.of(
                    "TIME_CANDIDATE",
                    parsedDt.bookingTime(),
                    parsedDt.bookingTime(),
                    "ConversationState.parseBookingDateTime"
            ));
        }

        // 2. Money / Price mention candidates
        Matcher moneyMatcher = SHADOW_MONEY_CANDIDATE_PATTERN.matcher(originalMessage);
        while (moneyMatcher.find()) {
            String raw = moneyMatcher.group(1).strip();
            Long vnd = CleanAgentOutputRefereeAndSanitizer.parseCleanPriceMentionToVnd(raw);
            out.add(UnverifiedParserCandidate.of(
                    "PRICE_OR_MONEY_CANDIDATE",
                    raw,
                    vnd != null ? (vnd + " VND") : raw,
                    "SHADOW_MONEY_CANDIDATE_PATTERN"
            ));
        }

        // 3. Duration token candidates (ví dụ "1 ngày" trong "1 ngày công r" hay "14 tiếng" — GẮN NHÃN UNVERIFIED!)
        boolean isStyleOrProcessComplaint =
                CleanAgentOutputRefereeAndSanitizer.isCommunicationStyleComplaintMessage(originalMessage);
        if (!isStyleOrProcessComplaint) {
            Matcher durMatcher = SHADOW_DURATION_TOKEN_PATTERN.matcher(originalMessage);
            while (durMatcher.find()) {
                String rawDur = durMatcher.group(1).strip();
                out.add(UnverifiedParserCandidate.of(
                        "DURATION_TOKEN_CANDIDATE",
                        rawDur,
                        rawDur.toLowerCase(Locale.ROOT),
                        "PROBLEM_DURATION_TOKEN_PATTERN_SHADOW"
                ));
            }

            // 4. Scope / Body-area parser candidates (chặn trích xuất từ mệnh đề khách nhắc lại lời tư vấn của bot)
            String messageForScopeShadow = scrubBotCitationClausesBeforeScopeShadow(originalMessage);
            if (!messageForScopeShadow.isBlank()) {
                List<String> candidateScopes = ScopeMatcher.mergeScopes(
                        messageForScopeShadow,
                        List.of(),
                        catalog != null ? catalog : List.of(),
                        personaConfig
                );
                for (String sc : candidateScopes) {
                    if (sc != null && !sc.isBlank()) {
                        out.add(UnverifiedParserCandidate.of(
                                "SCOPE_OR_AREA_CANDIDATE",
                                sc,
                                sc.strip().toLowerCase(Locale.ROOT),
                                "ScopeMatcher.extractScopeCandidateShadow"
                        ));
                    }
                }
            }
        }

        return List.copyOf(out);
    }

    private static final Pattern BOT_RECOMMENDATION_CITATION_CLAUSE_PATTERN = Pattern.compile(
            "(?isU)\\b(?:rồi\\s+lại\\s+|tự\\s+nhiên\\s+|chưa\\s+gì\\s+đã\\s+|lại\\s+đi\\s+|sao\\s+lại\\s+)?"
            + "(?:tư\\s+vấn|gợi\\s+ý|chào\\s+mời|đề\\s+xuất|báo)\\s+(?:gói\\s+|dịch\\s+vụ\\s+|làm\\s+)?"
            + "(?:lấy\\s+ráy\\s+tai|ráy\\s+tai|gội\\s+đầu|massage)[^.,;!?\\n]*"
    );

    private static String scrubBotCitationClausesBeforeScopeShadow(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return "";
        }
        String norm = Normalizer.normalize(rawMessage.strip(), Normalizer.Form.NFC);
        if (!norm.toLowerCase(Locale.ROOT).matches("(?isU).*\\b(rồi\\s+lại\\s+tư\\s+vấn|tự\\s+nhiên.*tư\\s+vấn|sao\\s+lại\\s+tư\\s+vấn|chưa\\s+tìm\\s+hiểu).*")) {
            return norm;
        }
        return BOT_RECOMMENDATION_CITATION_CLAUSE_PATTERN.matcher(norm).replaceAll(" ").strip();
    }

    // =========================================================================
    // 3. UNIFIED SCHEMA: ADAPTIVE COGNITIVE REFLECTION (MỤC 3.3 & 3.4)
    // =========================================================================

    public enum HypothesisLifecycleOp {
        MAINTAIN,
        REINFORCE,
        ADJUST,
        DOWNGRADE,
        REJECT;

        public static HypothesisLifecycleOp fromString(String raw) {
            if (raw == null || raw.isBlank()) {
                return MAINTAIN;
            }
            String u = raw.strip().toUpperCase(Locale.ROOT);
            for (HypothesisLifecycleOp op : values()) {
                if (op.name().equals(u)) {
                    return op;
                }
            }
            if (u.contains("REJECT") || u.contains("REPLACE")) return REJECT;
            if (u.contains("ADJUST") || u.contains("UPDATE")) return ADJUST;
            if (u.contains("REINFORCE") || u.contains("CONFIRM")) return REINFORCE;
            if (u.contains("DOWNGRADE")) return DOWNGRADE;
            return MAINTAIN;
        }
    }

    public enum StrategyAdaptationMode {
        CONTINUATION,
        REFINEMENT,
        PIVOT,
        PRIORITY_SHIFT,
        STOP;

        public static StrategyAdaptationMode fromString(String raw) {
            if (raw == null || raw.isBlank()) {
                return CONTINUATION;
            }
            String u = raw.strip().toUpperCase(Locale.ROOT);
            for (StrategyAdaptationMode m : values()) {
                if (m.name().equals(u)) {
                    return m;
                }
            }
            if (u.contains("PRIORITY")) return PRIORITY_SHIFT;
            if (u.contains("PIVOT")) return PIVOT;
            if (u.contains("REFINE")) return REFINEMENT;
            if (u.contains("STOP") || u.contains("PAUSE") || u.contains("CLOSE")) return STOP;
            return CONTINUATION;
        }
    }

    public enum StrategyOutcome {
        ACCEPTED,
        REJECTED,
        PARTIALLY_ACCEPTED,
        UNCLEAR,
        NOT_APPLICABLE;

        public static StrategyOutcome fromString(String raw) {
            if (raw == null || raw.isBlank()) {
                return NOT_APPLICABLE;
            }
            String u = raw.strip().toUpperCase(Locale.ROOT);
            for (StrategyOutcome o : values()) {
                if (o.name().equals(u)) {
                    return o;
                }
            }
            if (u.contains("PARTIAL")) return PARTIALLY_ACCEPTED;
            if (u.contains("REJECT")) return REJECTED;
            if (u.contains("ACCEPT")) return ACCEPTED;
            if (u.contains("UNCLEAR")) return UNCLEAR;
            return NOT_APPLICABLE;
        }
    }

    /**
     * Các thao tác cam kết LLM được phép đề xuất (TUYỆT ĐỐI CẤM LLM phát hành COMPLETED).
     */
    public enum CommitmentOperation {
        CREATE,
        UPDATE_CONTENT,
        REMIND,
        CANCEL_BY_CUSTOMER;

        public static CommitmentOperation parseAllowedFromLlm(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String u = raw.strip().toUpperCase(Locale.ROOT);
            if ("COMPLETED".equals(u) || "COMPLETE".equals(u) || "MARK_COMPLETED".equals(u) || "DONE".equals(u)) {
                log.warn("[ACI-AuthorityGuard] Blocked unauthorized LLM attempt to mark commitment COMPLETED (op='{}')", raw);
                return null;
            }
            for (CommitmentOperation op : values()) {
                if (op.name().equals(u)) {
                    return op;
                }
            }
            if (u.contains("CANCEL")) return CANCEL_BY_CUSTOMER;
            if (u.contains("REMIND") || u.contains("FOLLOW")) return REMIND;
            if (u.contains("UPDATE")) return UPDATE_CONTENT;
            if (u.contains("CREATE") || u.contains("ADD") || u.contains("NEW")) return CREATE;
            return null;
        }
    }

    public enum CommitmentOwner {
        BOT,
        MANAGER,
        CASHIER,
        STAFF;

        public static CommitmentOwner fromString(String raw) {
            if (raw == null || raw.isBlank()) {
                return STAFF;
            }
            String u = raw.strip().toUpperCase(Locale.ROOT);
            for (CommitmentOwner o : values()) {
                if (o.name().equals(u)) {
                    return o;
                }
            }
            if (u.contains("CASHIER") || u.contains("THU_NGAN") || u.contains("ACCOUNT")) return CASHIER;
            if (u.contains("MANAGER") || u.contains("QUAN_LY")) return MANAGER;
            if (u.contains("BOT")) return BOT;
            return STAFF;
        }
    }

    public record EmotionalState(
            String primary,
            List<String> secondary,
            double confidence
    ) {
        public EmotionalState {
            primary = (primary != null && !primary.isBlank()) ? primary.strip().toUpperCase(Locale.ROOT) : "UNKNOWN";
            secondary = secondary != null ? List.copyOf(secondary) : List.of();
            confidence = Math.max(0.0, Math.min(1.0, confidence));
        }

        public static EmotionalState empty() {
            return new EmotionalState("UNKNOWN", List.of(), 0.0);
        }
    }

    public static final double INFERRED_PRONOUN_HIGH_CONFIDENCE_THRESHOLD = 0.85;

    private static final Pattern THIRD_PERSON_FAMILY_OR_ROLE_EXCLUSION_PATTERN = Pattern.compile(
            "(?iu)\\b(anh\\s+(?:trai|xã|chồng|họ|rể|ruột|ấy|đó|kia)|"
            + "chị\\s+(?:gái|dâu|họ|ruột|ấy|đó|kia)|"
            + "cô\\s+(?:giáo|gái|ấy|đó|kia|nàng)|"
            + "chú\\s+(?:ý|thích|trọng|rể|ấy|đó|kia)|"
            + "bác\\s+(?:sĩ|sỹ|học|ấy|đó|kia))\\b"
    );

    private static final Pattern EXPLICIT_PRONOUN_CORRECTION_TO_ANH_PATTERN = Pattern.compile(
            "(?isU)(?:^|[\\s,;.!?])(?:"
            + "gọi\\s+(?:tôi|mình|anh|em|t|tui|mk|mik)\\s+là\\s+anh|"
            + "(?:tôi|mình|t|tui|mk|mik|em|anh|a)\\s+là\\s+(?:anh|nam|nam\\s+giới|con\\s+trai|đàn\\s+ông)|"
            + "(?:không|ko|k)\\s+phải\\s+chị(?:\\s+đâu)?(?:[,;\\s]+(?:gọi\\s+là\\s+anh|(?:mình|tôi|t|tui|em)\\s+là\\s+(?:anh|nam|con\\s+trai)|anh\\s+nhé|gọi\\s+anh))?|"
            + "đừng\\s+gọi\\s+(?:là\\s+)?chị[,;\\s]+gọi\\s+(?:là\\s+)?anh|"
            + "(?:tôi|mình|t|tui)\\s+là\\s+chồng\\s+(?:của\\s+)?chị"
            + ")(?:$|[\\s,;.!?])"
    );

    private static final Pattern EXPLICIT_PRONOUN_CORRECTION_TO_CHI_PATTERN = Pattern.compile(
            "(?isU)(?:^|[\\s,;.!?])(?:"
            + "gọi\\s+(?:tôi|mình|chị|em|t|tui|mk|mik)\\s+là\\s+chị|"
            + "(?:tôi|mình|t|tui|mk|mik|em|chị)\\s+là\\s+(?:chị|nữ|nữ\\s+giới|con\\s+gái|phụ\\s+nữ)|"
            + "(?:không|ko|k)\\s+phải\\s+anh(?:\\s+đâu)?(?:[,;\\s]+(?:gọi\\s+là\\s+chị|(?:mình|tôi|t|tui|em)\\s+là\\s+(?:chị|nữ|con\\s+gái)|chị\\s+nhé|gọi\\s+chị))?|"
            + "đừng\\s+gọi\\s+(?:là\\s+)?anh[,;\\s]+gọi\\s+(?:là\\s+)?chị|"
            + "(?:tôi|mình|t|tui)\\s+là\\s+vợ\\s+(?:của\\s+)?anh"
            + ")(?:$|[\\s,;.!?])"
    );

    public static boolean isExplicitGenderOrPronounCorrectionMessage(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return false;
        }
        String norm = Normalizer.normalize(rawMessage.strip(), Normalizer.Form.NFC);
        return EXPLICIT_PRONOUN_CORRECTION_TO_ANH_PATTERN.matcher(norm).find()
                || EXPLICIT_PRONOUN_CORRECTION_TO_CHI_PATTERN.matcher(norm).find();
    }

    private static final Pattern FIRST_PERSON_ANH_SELF_REF_PATTERN = Pattern.compile(
            "(?iu)(?:^|[\\s,;.!?])(?:"
            + "nếu\\s+(?:anh|a)\\b|(?:kể|tư\\s+vấn|báo|gửi|đặt|giữ|xem|chốt|lấy|hỗ\\s+trợ)\\s+cho\\s+(?:anh|a)\\b|"
            + "cho\\s+(?:anh|a)\\s+(?:hỏi|xin|đặt|đăng\\s+ký|chốt|xem|biết|thử|làm)|"
            + "rồi\\s+(?:anh|a)\\s+(?:đăng\\s+ký|đặt|qua|ghé|chốt|làm)|"
            + "(?:thôi\\s+|vậy\\s+|thế\\s+|tự\\s+nhiên\\s+)?anh\\s+(?:không|ko|chưa|đang|muốn|cần|định|sẽ|vừa|mới|bị|thấy|tức\\s+ngực|tê|đau|mỏi|ngại|đăng\\s+ký|đặt|qua|ghé|đến|sang|chốt|chọn|thử)\\b|"
            + "để\\s+anh\\s+(?:xem|nghĩ|suy\\s+nghĩ|tính|hỏi|cân\\s+nhắc|qua|đặt)\\b|"
            + "(?:của|với|giúp|hỗ\\s+trợ)\\s+anh\\b"
            + ")"
    );

    private static final Pattern FIRST_PERSON_CHI_SELF_REF_PATTERN = Pattern.compile(
            "(?iu)(?:^|[\\s,;.!?])(?:"
            + "nếu\\s+chị\\b|(?:kể|tư\\s+vấn|báo|gửi|đặt|giữ|xem|chốt|lấy|hỗ\\s+trợ)\\s+cho\\s+chị\\b|"
            + "cho\\s+chị\\s+(?:hỏi|xin|đặt|đăng\\s+ký|chốt|xem|biết|thử|làm)|"
            + "rồi\\s+chị\\s+(?:đăng\\s+ký|đặt|qua|ghé|chốt|làm)|"
            + "(?:thôi\\s+|vậy\\s+|thế\\s+|dạo\\s+này\\s+)?chị\\s+(?:không|ko|chưa|đang|muốn|cần|định|sẽ|vừa|mới|bị|hay|làm\\s+việc|thấy|hơi|ngại|đăng\\s+ký|đặt|qua|ghé|đến|sang|chốt|chọn|thử)\\b|"
            + "để\\s+chị\\s+(?:xem|nghĩ|suy\\s+nghĩ|tính|hỏi|cân\\s+nhắc|tham\\s+khảo|qua|đặt)\\b|"
            + "(?:của|với|giúp|hỗ\\s+trợ)\\s+chị\\b"
            + ")"
    );

    /**
     * Phát hiện đại từ khách tự xưng hoặc chủ động yêu cầu đính chính ngay trong lượt hiện tại
     * ("gọi tôi là anh", "mình là nam", "nếu anh...", "để chị xem thêm"), cho phép cập nhật đại từ an toàn.
     */
    public static String inferExplicitCustomerSelfPronounFromUtterance(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return null;
        }
        String norm = Normalizer.normalize(rawMessage.strip(), Normalizer.Form.NFC);
        if (EXPLICIT_PRONOUN_CORRECTION_TO_ANH_PATTERN.matcher(norm).find()) {
            return "Anh";
        }
        if (EXPLICIT_PRONOUN_CORRECTION_TO_CHI_PATTERN.matcher(norm).find()) {
            return "Chị";
        }
        String scrubbed = THIRD_PERSON_FAMILY_OR_ROLE_EXCLUSION_PATTERN.matcher(norm).replaceAll(" ");
        boolean hasAnh = FIRST_PERSON_ANH_SELF_REF_PATTERN.matcher(scrubbed).find();
        boolean hasChi = FIRST_PERSON_CHI_SELF_REF_PATTERN.matcher(scrubbed).find();
        if (hasAnh && !hasChi) {
            return "Anh";
        }
        if (hasChi && !hasAnh) {
            return "Chị";
        }
        return null;
    }

    public static String normalizeInferredSpeakerPronoun(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String norm = Normalizer.normalize(raw.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        if ("null".equals(norm) || "none".equals(norm) || "unknown".equals(norm)
                || "mình".equals(norm) || "minh".equals(norm)
                || "tôi".equals(norm) || "toi".equals(norm)
                || "bạn".equals(norm) || "ban".equals(norm)
                || "em".equals(norm) || "khách".equals(norm) || "anh/chị".equals(norm)) {
            return null;
        }
        return switch (norm) {
            case "anh" -> "Anh";
            case "chị", "chi" -> "Chị";
            case "cô", "co" -> "Cô";
            case "chú", "chu" -> "Chú";
            case "bác", "bac" -> "Bác";
            default -> null;
        };
    }

    public enum BookingReadiness {
        EXPLORING,
        CONSIDERING,
        READY_TO_BOOK;

        public static BookingReadiness fromString(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String u = raw.strip().toUpperCase(Locale.ROOT);
            for (BookingReadiness r : values()) {
                if (r.name().equals(u)) {
                    return r;
                }
            }
            if (u.contains("NOT_READY") || u.startsWith("NOT_") || u.equals("NONE") || u.equals("LOW")) {
                return EXPLORING;
            }
            if (u.contains("READY") || u.contains("BOOK") || u.contains("COMMIT")) {
                return READY_TO_BOOK;
            }
            if (u.contains("CONSIDER") || u.contains("COMPARE") || u.contains("WEIGH")
                    || u.contains("INTEREST") || u.contains("WARM") || u.contains("PREPAR") || u.contains("TRIAL")) {
                return CONSIDERING;
            }
            if (u.contains("EXPLOR") || u.contains("INQUIR") || u.contains("DISCOVER")) {
                return EXPLORING;
            }
            return null;
        }
    }

    public static String normalizeCommunicationStylePreference(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String u = raw.strip().toUpperCase(Locale.ROOT);
        if ("NULL".equals(u) || "NONE".equals(u) || "KEEP".equals(u)
                || "KEEP_CURRENT".equals(u) || "UNCHANGED".equals(u)) {
            return null;
        }
        if (u.contains("ON_DEMAND")) {
            return "DETAILED_ON_DEMAND";
        }
        if (u.contains("CONCISE") || u.contains("BRIEF") || u.contains("SHORT")) {
            return "CONCISE";
        }
        if (u.contains("DETAIL") || u.contains("EXPAND") || u.contains("THOROUGH")) {
            return "DETAILED";
        }
        if (u.contains("STANDARD") || u.contains("DEFAULT") || u.contains("NORMAL")) {
            return "STANDARD";
        }
        return null;
    }

    // =========================================================================
    // 3B. CUSTOMER UNDERSTANDING MODEL (DRAFT 2 — 3 LAYERS & VERSIONED FACTS)
    //     & ADAPTIVE RESPONSE PLANNER (10 BEHAVIORS + NO_ADDITIONAL_ACTION)
    // =========================================================================

    public enum UnresolvedRequestStatus {
        OPEN,
        ANSWERED,
        CANCELLED,
        SUPERSEDED;

        public static UnresolvedRequestStatus fromString(String raw) {
            if (raw == null || raw.isBlank()) {
                return OPEN;
            }
            String u = raw.strip().toUpperCase(Locale.ROOT);
            for (UnresolvedRequestStatus s : values()) {
                if (s.name().equals(u)) {
                    return s;
                }
            }
            if (u.contains("ANSWER") || u.contains("RESOLV") || u.contains("DONE")) return ANSWERED;
            if (u.contains("CANCEL") || u.contains("DROP")) return CANCELLED;
            if (u.contains("SUPERSED") || u.contains("REPLAC")) return SUPERSEDED;
            return OPEN;
        }
    }

    public record UnresolvedRequestItem(
            String item,
            UnresolvedRequestStatus status,
            String sourceTurnId,
            String updatedTurnId
    ) {
        public UnresolvedRequestItem {
            item = item != null ? item.strip() : "";
            status = status != null ? status : UnresolvedRequestStatus.OPEN;
            sourceTurnId = (sourceTurnId != null && !sourceTurnId.isBlank()) ? sourceTurnId.strip() : "T_CURRENT";
            updatedTurnId = (updatedTurnId != null && !updatedTurnId.isBlank()) ? updatedTurnId.strip() : sourceTurnId;
        }

        public String requestSummary() {
            return item;
        }
    }

    public enum FactValidity {
        CURRENT,
        RESOLVED,
        PAST,
        NEGATED,
        SUPERSEDED;

        public static FactValidity fromString(String raw) {
            if (raw == null || raw.isBlank()) {
                return CURRENT;
            }
            String u = raw.strip().toUpperCase(Locale.ROOT);
            if (u.contains("RESOLV")) {
                return RESOLVED;
            }
            if ("PAST".equals(u) || u.contains("PAST_") || u.contains("FORMER") || u.contains("HISTORY")) {
                return PAST;
            }
            if (u.contains("NEGAT") || u.contains("DENY") || u.contains("DENIED") || "NO".equals(u)) {
                return NEGATED;
            }
            if (u.contains("SUPERSED") || u.contains("REVIS") || u.contains("REPLAC")) {
                return SUPERSEDED;
            }
            return CURRENT;
        }

        public static FactValidity parseExplicit(String raw) {
            if (raw == null || raw.isBlank() || "null".equalsIgnoreCase(raw.strip())) {
                return null;
            }
            String u = raw.strip().toUpperCase(Locale.ROOT);
            if ("CONFIRMED".equals(u) || "ACTIVE".equals(u) || "CURRENT".equals(u)) {
                return CURRENT;
            }
            if (u.contains("RESOLV")) return RESOLVED;
            if ("PAST".equals(u) || u.contains("PAST_") || u.contains("FORMER") || u.contains("HISTORY")) return PAST;
            if (u.contains("NEGAT") || u.contains("DENY") || u.contains("DENIED")) return NEGATED;
            if (u.contains("SUPERSED") || u.contains("REVIS") || u.contains("REPLAC")) return SUPERSEDED;
            return null;
        }
    }

    public enum InteractionPermission {
        OPEN,
        CONCISE_PREFERRED,
        BOUNDED_INFO_ONLY,
        PAUSED;

        public static InteractionPermission parseExplicit(String raw) {
            if (raw == null || raw.isBlank() || "null".equalsIgnoreCase(raw.strip())) {
                return null;
            }
            String u = raw.strip().toUpperCase(Locale.ROOT);
            if (u.contains("PAUS") || u.contains("STOP") || u.contains("DEFER") || u.contains("CLOSE")) {
                return PAUSED;
            }
            if (u.contains("BOUND") || u.contains("INFO_ONLY") || u.contains("FACT_ONLY") || u.contains("RESTRICT")) {
                return BOUNDED_INFO_ONLY;
            }
            if (u.contains("CONCISE") || u.contains("BRIEF") || u.contains("SHORT")) {
                return CONCISE_PREFERRED;
            }
            if (u.contains("OPEN") || u.contains("ACTIVE") || u.contains("CONSULT")) {
                return OPEN;
            }
            return null;
        }

        public static InteractionPermission fromStringOrDefault(String raw, InteractionPermission fallback) {
            InteractionPermission parsed = parseExplicit(raw);
            return parsed != null ? parsed : (fallback != null ? fallback : OPEN);
        }
    }

    public enum BuyingSignal {
        NONE,
        WEAK,
        MODERATE,
        STRONG,
        ACTION;

        public static BuyingSignal fromString(String raw) {
            if (raw == null || raw.isBlank() || "null".equalsIgnoreCase(raw.strip())) {
                return NONE;
            }
            String u = raw.strip().toUpperCase(Locale.ROOT);
            if (u.contains("ACTION") || u.contains("BOOK") || u.contains("COMMIT") || u.contains("READY")) {
                return ACTION;
            }
            if (u.contains("STRONG") || u.contains("HIGH")) {
                return STRONG;
            }
            if (u.contains("MODERATE") || u.contains("MED") || u.contains("CONSIDER")) {
                return MODERATE;
            }
            if (u.contains("WEAK") || u.contains("LOW") || u.contains("EXPLOR")) {
                return WEAK;
            }
            return NONE;
        }
    }

    public enum CtaLevel {
        NO_CTA,
        SOFT_CTA,
        DIRECT_CTA,
        TRANSACTION_HANDOFF;

        public static CtaLevel fromString(String raw, String fallbackStrategy, String fallbackNextAction) {
            if (raw != null && !raw.isBlank() && !"null".equalsIgnoreCase(raw.strip())) {
                String u = raw.strip().toUpperCase(Locale.ROOT);
                if (u.contains("HANDOFF") || u.contains("TRANSACTION")) {
                    return TRANSACTION_HANDOFF;
                }
                if (u.contains("DIRECT") || u.contains("BOOK") || u.contains("SCHEDUL")) {
                    return DIRECT_CTA;
                }
                if (u.contains("NO_CTA") || "NONE".equals(u) || u.contains("STOP") || u.contains("PAUSE")) {
                    return NO_CTA;
                }
                if (u.contains("SOFT") || u.contains("GENTLE") || u.contains("LIGHT")) {
                    return SOFT_CTA;
                }
            }
            if ("Respectful Pause".equalsIgnoreCase(fallbackStrategy)) {
                return NO_CTA;
            }
            if ("Transaction Handoff".equalsIgnoreCase(fallbackStrategy)) {
                return TRANSACTION_HANDOFF;
            }
            if ("GENTLE_SCHEDULE_CHECK".equalsIgnoreCase(fallbackNextAction)) {
                return DIRECT_CTA;
            }
            if ("NO_ADDITIONAL_ACTION".equalsIgnoreCase(fallbackNextAction)) {
                return NO_CTA;
            }
            return SOFT_CTA;
        }
    }

    public enum FactMutationOp {
        ADD,
        REVISE;

        public static FactMutationOp fromString(String raw) {
            if (raw == null || raw.isBlank()) {
                return ADD;
            }
            String u = raw.strip().toUpperCase(Locale.ROOT);
            if ("REVISE".equals(u) || u.contains("REVIS") || u.contains("REPLAC")
                    || u.contains("SUPERSED") || u.contains("UPDATE") || u.contains("SWITCH")) {
                return REVISE;
            }
            return ADD;
        }
    }

    public record FactDeltaProposal(
            String key,
            String value,
            FactMutationOp op,
            String confidence,
            FactValidity explicitValidity
    ) {
        public FactDeltaProposal {
            key = normalizeFactCategoryKey(key, value);
            value = value != null ? value.strip() : "";
            op = op != null ? op : FactMutationOp.ADD;
            confidence = normalizeConfidenceLevel(confidence, 0.90);
        }

        public FactDeltaProposal(String key, String value, FactMutationOp op, String confidence) {
            this(key, value, op, confidence, null);
        }

        public FactValidity effectiveValidity() {
            return explicitValidity != null ? explicitValidity : FactValidity.CURRENT;
        }
    }

    /**
     * P3.3 Mục 2.4: Phân biệt nguồn gốc và mức độ xác nhận của thông tin trong bộ nhớ phiên.
     */
    public enum FactProvenance {
        CUSTOMER_CONFIRMED,
        CUSTOMER_CORRECTED,
        BOT_PROPOSED,
        INFERRED,
        SYSTEM_DERIVED;

        public static FactProvenance fromSourceAndStatus(String rawSource, String rawStatus, FactValidity validity) {
            String src = rawSource != null ? rawSource.strip().toUpperCase(Locale.ROOT) : "";
            String st = rawStatus != null ? rawStatus.strip().toUpperCase(Locale.ROOT) : "";
            if (src.contains("CUSTOMER_CORRECT") || st.contains("CORRECT") || src.contains("REVISE")) {
                return CUSTOMER_CORRECTED;
            }
            if (src.contains("BOT") || st.contains("BOT") || src.contains("ASSISTANT") || src.contains("OFFER")) {
                return BOT_PROPOSED;
            }
            if (src.contains("SYSTEM") || st.contains("SYSTEM") || src.contains("GATEWAY") || src.contains("DERIVED")) {
                return SYSTEM_DERIVED;
            }
            if (src.contains("INFER") || st.contains("INFER") || src.contains("HYPOTHESIS")
                    || src.contains("PARSER") || src.contains("CANDIDATE")) {
                return INFERRED;
            }
            if (src.contains("CUSTOMER") || "CONFIRMED".equals(st)) {
                return CUSTOMER_CONFIRMED;
            }
            return INFERRED;
        }
    }

    /**
     * Phương án B (Versioned Facts): Mỗi dữ kiện trong {@code confirmed_facts} / {@code self_reported_symptoms}
     * lưu đầy đủ nguồn gốc và hiệu lực 5 trạng thái ({@code CURRENT | RESOLVED | PAST | NEGATED | SUPERSEDED}).
     */
    public record VersionedCustomerFact(
            String key,
            String value,
            String status,
            String source,
            String sourceTurnId,
            String observedAt,
            FactValidity validity,
            String confidence
    ) {
        public VersionedCustomerFact {
            key = normalizeFactCategoryKey(key, value);
            value = value != null ? value.strip() : "";
            status = (status != null && !status.isBlank()) ? status.strip().toUpperCase(Locale.ROOT) : "CONFIRMED";
            source = (source != null && !source.isBlank()) ? source.strip().toUpperCase(Locale.ROOT) : "CUSTOMER_MESSAGE";
            sourceTurnId = (sourceTurnId != null && !sourceTurnId.isBlank()) ? sourceTurnId.strip() : "T_CURRENT";
            observedAt = (observedAt != null && !observedAt.isBlank()) ? observedAt.strip() : Instant.now().toString();
            validity = validity != null ? validity : FactValidity.CURRENT;
            confidence = normalizeConfidenceLevel(confidence, 0.90);
        }

        public FactProvenance provenance() {
            return FactProvenance.fromSourceAndStatus(source, status, validity);
        }

        public VersionedCustomerFact withValidity(FactValidity newValidity) {
            FactValidity target = newValidity != null ? newValidity : FactValidity.CURRENT;
            String nextStatus = target == FactValidity.CURRENT ? "CONFIRMED" : target.name();
            return new VersionedCustomerFact(
                    key, value, nextStatus, source, sourceTurnId, observedAt, target, confidence);
        }

        public VersionedCustomerFact markSuperseded() {
            return withValidity(FactValidity.SUPERSEDED);
        }
    }

    public record BarrierHypothesis(
            String barrierType,
            String hypothesis,
            String confidenceLevel,
            double confidenceScore,
            List<String> evidenceTurnIds,
            HypothesisLifecycleOp lifecycleOp
    ) {
        public BarrierHypothesis {
            barrierType = normalizeBarrierType(barrierType, hypothesis);
            hypothesis = hypothesis != null ? hypothesis.strip() : "";
            confidenceScore = Math.max(0.0, Math.min(1.0, confidenceScore));
            confidenceLevel = normalizeConfidenceLevel(confidenceLevel, confidenceScore);
            evidenceTurnIds = evidenceTurnIds != null ? List.copyOf(evidenceTurnIds) : List.of();
            lifecycleOp = lifecycleOp != null ? lifecycleOp : HypothesisLifecycleOp.MAINTAIN;
        }

        public static BarrierHypothesis initialNone() {
            return new BarrierHypothesis("NONE", "", "LOW", 0.0, List.of(), HypothesisLifecycleOp.MAINTAIN);
        }

        public String summary() {
            return hypothesis;
        }
    }

    public static final List<String> ALLOWED_PLANNER_STRATEGIES = List.of(
            "Direct Answer",
            "Warm Acknowledgment",
            "Active Listening",
            "Clarify Gently",
            "Explain Value",
            "Handle Objection",
            "Reassure Honestly",
            "Soft Recommendation",
            "Respectful Pause",
            "Transaction Handoff"
    );

    public record AdaptiveResponsePlan(
            String objective,
            String strategy,
            String tone,
            String depth,
            String nextAction,
            CtaLevel ctaLevel,
            StrategyAdaptationMode adaptation,
            StrategyOutcome previousOutcome
    ) {
        public AdaptiveResponsePlan {
            objective = objective != null ? objective.strip() : "";
            strategy = normalizePlannerStrategy(strategy);
            tone = normalizePlannerTone(tone);
            depth = normalizePlannerDepth(depth);
            nextAction = normalizePlannerNextAction(nextAction);
            ctaLevel = ctaLevel != null ? ctaLevel : CtaLevel.fromString(null, strategy, nextAction);
            adaptation = adaptation != null ? adaptation
                    : ("Respectful Pause".equalsIgnoreCase(strategy)
                        ? StrategyAdaptationMode.STOP
                        : StrategyAdaptationMode.CONTINUATION);
            previousOutcome = previousOutcome != null ? previousOutcome : StrategyOutcome.NOT_APPLICABLE;
        }

        public AdaptiveResponsePlan(
                String objective,
                String strategy,
                String tone,
                String depth,
                String nextAction
        ) {
            this(
                    objective,
                    strategy,
                    tone,
                    depth,
                    nextAction,
                    CtaLevel.fromString(null, strategy, nextAction),
                    "Respectful Pause".equalsIgnoreCase(normalizePlannerStrategy(strategy))
                            ? StrategyAdaptationMode.STOP
                            : StrategyAdaptationMode.CONTINUATION,
                    StrategyOutcome.NOT_APPLICABLE
            );
        }

        public static AdaptiveResponsePlan empty() {
            return new AdaptiveResponsePlan(
                    "",
                    "Direct Answer",
                    "LEVEL_1_NATURAL",
                    "STANDARD",
                    "NO_ADDITIONAL_ACTION",
                    CtaLevel.NO_CTA,
                    StrategyAdaptationMode.CONTINUATION,
                    StrategyOutcome.NOT_APPLICABLE
            );
        }
    }

    /** Tầng 1 — Conversational Context (Ngữ cảnh vận hành ngắn hạn, không lặp current_user_message). */
    public record ConversationalContextLayer(
            String currentTopic,
            List<String> topicHistory,
            List<UnresolvedRequestItem> unresolvedRequests,
            List<String> pendingReferences,
            String lastBotAction
    ) {
        public ConversationalContextLayer {
            currentTopic = (currentTopic != null && !currentTopic.isBlank()) ? currentTopic.strip() : "GENERAL_INQUIRY";
            topicHistory = topicHistory != null ? List.copyOf(topicHistory) : List.of();
            unresolvedRequests = unresolvedRequests != null ? List.copyOf(unresolvedRequests) : List.of();
            pendingReferences = pendingReferences != null ? List.copyOf(pendingReferences) : List.of();
            lastBotAction = lastBotAction != null ? lastBotAction.strip() : "";
        }

        public static ConversationalContextLayer initial() {
            return new ConversationalContextLayer("INITIAL", List.of(), List.of(), List.of(), "NONE");
        }
    }

    /** Tầng 2 — Consultative Profile (Hồ sơ hiểu khách hàng xuyên suốt + Multi-Intent + Conversation State + Interaction Permission). */
    public record ConsultativeProfileLayer(
            String turnIntent,
            List<String> secondaryIntents,
            String conversationState,
            String conversationGoal,
            List<VersionedCustomerFact> confirmedFacts,
            List<VersionedCustomerFact> selfReportedSymptoms,
            BarrierHypothesis barrierAndHypothesis,
            EmotionalState emotion,
            String defaultCommunicationPreference,
            String effectiveTurnDepth,
            InteractionPermission interactionPermission
    ) {
        public ConsultativeProfileLayer {
            turnIntent = turnIntent != null ? turnIntent.strip() : "";
            secondaryIntents = secondaryIntents != null
                    ? secondaryIntents.stream().filter(s -> s != null && !s.isBlank()).map(String::strip).toList()
                    : List.of();
            conversationState = (conversationState != null && !conversationState.isBlank())
                    ? conversationState.strip().toUpperCase(Locale.ROOT)
                    : "ACTIVE_CONSULTATION";
            conversationGoal = (conversationGoal != null && !conversationGoal.isBlank()) ? conversationGoal.strip() : "EXPLORE_SERVICES";
            confirmedFacts = confirmedFacts != null ? List.copyOf(confirmedFacts) : List.of();
            selfReportedSymptoms = selfReportedSymptoms != null ? List.copyOf(selfReportedSymptoms) : List.of();
            barrierAndHypothesis = barrierAndHypothesis != null ? barrierAndHypothesis : BarrierHypothesis.initialNone();
            emotion = emotion != null ? emotion : EmotionalState.empty();
            defaultCommunicationPreference = (defaultCommunicationPreference != null && !defaultCommunicationPreference.isBlank())
                    ? defaultCommunicationPreference.strip()
                    : "STANDARD";
            effectiveTurnDepth = (effectiveTurnDepth != null && !effectiveTurnDepth.isBlank())
                    ? effectiveTurnDepth.strip()
                    : defaultCommunicationPreference;
            interactionPermission = interactionPermission != null
                    ? interactionPermission
                    : ("CONCISE".equalsIgnoreCase(defaultCommunicationPreference)
                        ? InteractionPermission.CONCISE_PREFERRED
                        : InteractionPermission.OPEN);
        }

        public ConsultativeProfileLayer(
                String turnIntent,
                String conversationGoal,
                List<VersionedCustomerFact> confirmedFacts,
                List<VersionedCustomerFact> selfReportedSymptoms,
                BarrierHypothesis barrierAndHypothesis,
                EmotionalState emotion,
                String defaultCommunicationPreference,
                String effectiveTurnDepth,
                InteractionPermission interactionPermission
        ) {
            this(
                    turnIntent,
                    List.of(),
                    interactionPermission == InteractionPermission.PAUSED ? "PAUSED_BY_CUSTOMER" : "ACTIVE_CONSULTATION",
                    conversationGoal,
                    confirmedFacts,
                    selfReportedSymptoms,
                    barrierAndHypothesis,
                    emotion,
                    defaultCommunicationPreference,
                    effectiveTurnDepth,
                    interactionPermission
            );
        }

        public ConsultativeProfileLayer(
                String turnIntent,
                String conversationGoal,
                List<VersionedCustomerFact> confirmedFacts,
                List<VersionedCustomerFact> selfReportedSymptoms,
                BarrierHypothesis barrierAndHypothesis,
                EmotionalState emotion,
                String defaultCommunicationPreference,
                String effectiveTurnDepth
        ) {
            this(
                    turnIntent,
                    List.of(),
                    "ACTIVE_CONSULTATION",
                    conversationGoal,
                    confirmedFacts,
                    selfReportedSymptoms,
                    barrierAndHypothesis,
                    emotion,
                    defaultCommunicationPreference,
                    effectiveTurnDepth,
                    "CONCISE".equalsIgnoreCase(defaultCommunicationPreference)
                            ? InteractionPermission.CONCISE_PREFERRED
                            : InteractionPermission.OPEN
            );
        }

        public static ConsultativeProfileLayer initial() {
            return new ConsultativeProfileLayer(
                    "", List.of(), "ACTIVE_CONSULTATION", "EXPLORE_SERVICES", List.of(), List.of(),
                    BarrierHypothesis.initialNone(), EmotionalState.empty(),
                    "STANDARD", "STANDARD", InteractionPermission.OPEN);
        }

        public String primaryIntent() {
            return turnIntent;
        }

        public BarrierHypothesis barrierHypothesis() {
            return barrierAndHypothesis;
        }

        public String communicationPreference() {
            return defaultCommunicationPreference;
        }

        public List<VersionedCustomerFact> currentConfirmedFacts() {
            return confirmedFacts.stream()
                    .filter(f -> f != null && f.validity() == FactValidity.CURRENT)
                    .toList();
        }

        public List<VersionedCustomerFact> supersededFacts() {
            return confirmedFacts.stream()
                    .filter(f -> f != null && f.validity() == FactValidity.SUPERSEDED)
                    .toList();
        }

        public List<VersionedCustomerFact> symptomsByValidity(FactValidity validity) {
            if (validity == null) {
                return List.of();
            }
            return selfReportedSymptoms.stream()
                    .filter(f -> f != null && f.validity() == validity)
                    .toList();
        }

        public List<VersionedCustomerFact> currentSymptoms() {
            return symptomsByValidity(FactValidity.CURRENT);
        }
    }

    /** ACI V3: Mức độ cam kết mua hàng của khách (tăng dần). */
    public enum CommitmentLevel {
        NONE,
        INTEREST,
        PREFERENCE,
        TRIAL_COMMITMENT,
        BOOKING_INTENT,
        BOOKING_REQUEST,
        CUSTOMER_CONFIRMATION,
        BOOKING_SUCCESS
    }

    /** ACI V3: Phạm vi từ chối của khách (thành phần trong gói / cả gói / cả hướng tư vấn). */
    public enum RejectionScope {
        COMPONENT,
        PACKAGE,
        FOCUS
    }

    /** ACI V3: Một mục sở thích tiêu cực (khách đã từ chối) kèm bằng chứng nguyên văn. */
    public record NegativePreferenceEntry(
            String targetKey,
            RejectionScope scope,
            String evidenceSpan,
            String turnId,
            boolean active
    ) {
        public NegativePreferenceEntry {
            targetKey = targetKey != null ? targetKey.strip() : "";
            scope = scope != null ? scope : RejectionScope.COMPONENT;
            evidenceSpan = evidenceSpan != null ? evidenceSpan.strip() : "";
            turnId = turnId != null ? turnId.strip() : "";
        }
    }

    /** ACI V3: Trạng thái hành vi mua hàng của khách trong hội thoại. */
    public enum BehaviorBuyingState {
        EXPLORATION,
        NEED_DISCLOSURE,
        SOLUTION_EVALUATION,
        TRIAL_PREFERENCE,
        BOOKING_INTENT,
        OBJECTION_HESITATION,
        CUSTOMER_CORRECTION,
        DEFERRAL
    }

    /** Tầng 3 — Business &amp; Commitment State (Tách biệt interested_package vs selected_package). */
    public record BusinessCommitmentLayer(
            String interestedPackageId,
            String selectedPackageId,
            String bookingStatus,
            HandoffLifecycleStatus handoffStatus,
            int pendingCommitmentCount,
            CommitmentLevel commitmentLevel,
            String commitmentEvidence,
            List<String> lastOfferedPackageIds,
            List<NegativePreferenceEntry> negativePreferences
    ) {
        public BusinessCommitmentLayer {
            interestedPackageId = cleanNullable(interestedPackageId);
            selectedPackageId = cleanNullable(selectedPackageId);
            bookingStatus = (bookingStatus != null && !bookingStatus.isBlank()) ? bookingStatus.strip() : "NONE";
            handoffStatus = handoffStatus != null ? handoffStatus : HandoffLifecycleStatus.NONE;
            pendingCommitmentCount = Math.max(0, pendingCommitmentCount);
            commitmentLevel = commitmentLevel != null ? commitmentLevel : CommitmentLevel.NONE;
            commitmentEvidence = cleanNullable(commitmentEvidence);
            lastOfferedPackageIds = lastOfferedPackageIds != null
                    ? lastOfferedPackageIds.stream().filter(Objects::nonNull).toList()
                    : List.of();
            negativePreferences = negativePreferences != null
                    ? negativePreferences.stream().filter(Objects::nonNull).toList()
                    : List.of();
        }

        /** Constructor tương thích ngược 5 tham số cho mọi chỗ gọi hiện hữu. */
        public BusinessCommitmentLayer(
                String interestedPackageId,
                String selectedPackageId,
                String bookingStatus,
                HandoffLifecycleStatus handoffStatus,
                int pendingCommitmentCount
        ) {
            this(interestedPackageId, selectedPackageId, bookingStatus, handoffStatus, pendingCommitmentCount,
                    CommitmentLevel.NONE, null, List.of(), List.of());
        }

        public static BusinessCommitmentLayer initial() {
            return new BusinessCommitmentLayer(null, null, "NONE", HandoffLifecycleStatus.NONE, 0);
        }

        public List<NegativePreferenceEntry> activeNegativePreferences() {
            return negativePreferences.stream().filter(NegativePreferenceEntry::active).toList();
        }

        public String bookingReadiness() {
            if (!"NONE".equalsIgnoreCase(bookingStatus) || selectedPackageId != null) {
                return "READY_TO_BOOK";
            }
            return interestedPackageId != null ? "CONSIDERING" : "EXPLORING";
        }
    }

    public record CustomerUnderstandingDraft2Snapshot(
            ConversationalContextLayer conversationalContext,
            ConsultativeProfileLayer consultativeProfile,
            BusinessCommitmentLayer businessAndCommitmentState,
            AdaptiveResponsePlan responsePlan
    ) {
        public CustomerUnderstandingDraft2Snapshot {
            conversationalContext = conversationalContext != null ? conversationalContext : ConversationalContextLayer.initial();
            consultativeProfile = consultativeProfile != null ? consultativeProfile : ConsultativeProfileLayer.initial();
            businessAndCommitmentState = businessAndCommitmentState != null ? businessAndCommitmentState : BusinessCommitmentLayer.initial();
            responsePlan = responsePlan != null ? responsePlan : AdaptiveResponsePlan.empty();
        }

        public static CustomerUnderstandingDraft2Snapshot initial() {
            return new CustomerUnderstandingDraft2Snapshot(
                    ConversationalContextLayer.initial(),
                    ConsultativeProfileLayer.initial(),
                    BusinessCommitmentLayer.initial(),
                    AdaptiveResponsePlan.empty()
            );
        }

        public BusinessCommitmentLayer businessCommitment() {
            return businessAndCommitmentState;
        }

        public AdaptiveResponsePlan lastResponsePlan() {
            return responsePlan;
        }
    }

    private static final Pattern ATOMIC_SYMPTOM_SPLIT_PATTERN = Pattern.compile(
            "(?iU)\\s*(?:[,;/+]|\\b(?:và|kèm(?:\\s+theo)?|cùng(?:\\s+với)?|với\\s+cả|lẫn)\\b)\\s*"
    );

    private static final Pattern SYMPTOM_LEADING_FILLER_PATTERN = Pattern.compile(
            "(?iU)^(?:(?:tôi|mình|em|anh|chị|khách)\\s+)?"
                    + "(?:(?:trước\\s+đây|hồi\\s+trước|hôm\\s+nay|dạo\\s+này|bây\\s+giờ)\\s+)?"
                    + "(?:(?:cũng|vẫn|đã)\\s+)?"
                    + "(?:từng\\s+bị|hay\\s+bị|đang\\s+bị|hơi\\s+bị|còn\\s+bị|hết\\s+bị|không\\s+còn\\s+bị|không\\s+bị"
                    + "|từng|hay|hết|khỏi|không\\s+còn|bị)\\s+"
    );

    private static final Pattern SYMPTOM_TRAILING_CLAUSE_PATTERN = Pattern.compile(
            "(?iU)(?:\\s+nhưng\\s+(?:đã\\s+|cũng\\s+)?(?:khỏi|hết|đỡ)\\b.*$|\\s+(?:lâu\\s+rồi|rồi|nữa|ạ)$)"
    );

    /**
     * Tách biểu hiện sức khỏe liệt kê/kết hợp ("đau đầu, chóng mặt", "đau đầu kèm buồn nôn")
     * thành danh sách các biểu hiện nguyên tử đã chuẩn hóa và khử trùng lặp.
     */
    /**
     * ACI V3 P1 §3.1: Fact grounding — fact ADD chỉ được nhận khi có bằng chứng từ vựng trong lời khách:
     * (a) value xuất hiện nguyên văn trong câu; hoặc
     * (b) mọi phạm vi/vùng cơ thể cấu thành value đều có bằng chứng alias trong câu ("tê chân" -&gt; "chân").
     * Value không mang token phạm vi nào thì bắt buộc bằng chứng nguyên văn.
     * Tuyệt đối không ghi fact CONFIRMED/HIGH với source=CUSTOMER_MESSAGE khi thiếu bằng chứng này.
     */
    public static boolean hasLexicalEvidenceInUtterance(String factValue, String rawUserMessage) {
        if (factValue == null || factValue.isBlank() || rawUserMessage == null || rawUserMessage.isBlank()) {
            return false;
        }
        String msg = Normalizer.normalize(rawUserMessage.strip(), Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
        String val = Normalizer.normalize(factValue.strip(), Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
        if (val.isEmpty()) {
            return false;
        }
        if (msg.contains(val)) {
            return true;
        }
        List<String> valueScopes = ScopeMatcher.extractScopesFromText(val, List.of());
        if (!valueScopes.isEmpty()) {
            Set<String> evidenceScopes = new LinkedHashSet<>(ScopeMatcher.extractScopesFromText(msg, List.of()));
            return evidenceScopes.containsAll(valueScopes);
        }
        // P3.3: Hỗ trợ đối chiếu tương đương số tiền ngân sách (VD: "250k" <-> "250.000đ" <-> "250 nghìn")
        Set<Integer> valAmountsK = extractNormalizedAmountsInThousands(val);
        if (!valAmountsK.isEmpty()) {
            Set<Integer> msgAmountsK = extractNormalizedAmountsInThousands(msg);
            if (!msgAmountsK.isEmpty() && msgAmountsK.containsAll(valAmountsK)) {
                return true;
            }
        }
        // P3.3: Hỗ trợ đối chiếu viết tắt cơ sở/chi nhánh (VD: "quận 1" <-> "q1", "quận 3" <-> "q3")
        Matcher qMatcher = Pattern.compile("\\bquận\\s*(\\d{1,2})\\b").matcher(val);
        if (qMatcher.find()) {
            String qNum = qMatcher.group(1);
            if (msg.matches("(?isU).*\\b(?:quận\\s*" + qNum + "|q\\.?\\s*" + qNum + ")\\b.*")) {
                return true;
            }
        }
        return false;
    }

    public static Set<Integer> extractNormalizedAmountsInThousands(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        String low = Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        Set<Integer> amounts = new LinkedHashSet<>();
        Matcher kMatch = Pattern.compile("\\b(\\d{2,4})\\s*(?:k|nghìn|ngàn)\\b").matcher(low);
        while (kMatch.find()) {
            try {
                amounts.add(Integer.parseInt(kMatch.group(1)));
            } catch (NumberFormatException ignored) {
            }
        }
        Matcher vndMatch = Pattern.compile("\\b(\\d{2,4})[.,]000(?:\\s*(?:đ|vnđ|vnd|d|đồng))?\\b").matcher(low);
        while (vndMatch.find()) {
            try {
                amounts.add(Integer.parseInt(vndMatch.group(1)));
            } catch (NumberFormatException ignored) {
            }
        }
        Matcher trMatch = Pattern.compile("\\b(\\d{1,2})(?:[.,](\\d{1,2}))?\\s*(?:triệu|tr)\\b").matcher(low);
        while (trMatch.find()) {
            try {
                int millions = Integer.parseInt(trMatch.group(1));
                String dec = trMatch.group(2);
                int subThousands = 0;
                if (dec != null && !dec.isEmpty()) {
                    subThousands = dec.length() == 1
                            ? Integer.parseInt(dec) * 100
                            : Integer.parseInt(dec) * 10;
                }
                amounts.add(millions * 1000 + subThousands);
            } catch (NumberFormatException ignored) {
            }
        }
        return amounts;
    }

    public static boolean isPriceObjectionFactValue(String rawVal) {
        if (rawVal == null || rawVal.isBlank()) {
            return false;
        }
        String low = Normalizer.normalize(rawVal.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        return low.matches("(?isU).*\\b(cao\\s+quá|giá\\s+cao|cao\\s+thế|cao\\s+vậy|đắt|mắc|chát|hơi\\s+cao|hơi\\s+căng|căng\\s+quá|đau\\s+ví|quá\\s+sức|phản\\s+ứng\\s+giá)\\b.*");
    }

    private static final Pattern EXPLICIT_CUSTOMER_TARGET_BUDGET_PATTERN = Pattern.compile(
            "(?isU)\\b(?:"
                    + "(?:ngân\\s+sách|tài\\s+chính|kinh\\s+phí|tầm\\s+giá|mức\\s+giá)(?:\\s+của)?(?:\\s+(?:mình|tôi|em|anh|chị))?(?:\\s+(?:chỉ|đổi\\s+sang|đổi\\s+xuống|hạ\\s+xuống|khoảng|tầm|dưới|tối\\s+đa|trong\\s+khoảng))*\\s*((?:tầm\\s+|khoảng\\s+|dưới\\s+|tối\\s+đa\\s+)?\\d{2,4}\\s*(?:k|nghìn|ngàn)|(?:tầm\\s+|khoảng\\s+|dưới\\s+|tối\\s+đa\\s+)?\\d{2,4}[.,]000\\s*(?:đ|vnđ|vnd|d)?(?:\\s*đổ\\s+lại)?)"
                    + "|(?:gói\\s+|loại\\s+|mức\\s+|tìm\\s+gói\\s+|đổi\\s+sang\\s+tầm\\s+|chuyển\\s+sang\\s+tầm\\s+|chỉ\\s+tầm\\s+|tầm\\s+khoảng\\s+|dưới\\s+|tối\\s+đa\\s+)?((?:tầm\\s+|khoảng\\s+|dưới\\s+|tối\\s+đa\\s+)\\d{2,4}\\s*(?:k|nghìn|ngàn)|(?:tầm\\s+|khoảng\\s+|dưới\\s+|tối\\s+đa\\s+)?\\d{2,4}[.,]000\\s*(?:đ|vnđ|vnd|d)?)(?:\\s*(?:thôi|đổ\\s+lại|trở\\s+xuống|nhé|nha|ạ))?"
                    + ")\\b"
    );

    /**
     * P3.3 Mục 2.4 (TC01, TC06): Chỉ trích xuất ngân sách mục tiêu do khách tự xác nhận
     * (VD: "ngân sách tầm 250k", "tìm gói dưới 300k"); TUYỆT ĐỐI KHÔNG trích mức giá bị khách chê đắt
     * ("Giá 399k cao quá") hoặc giá chỉ đang hỏi thông tin ("Gói 399k gồm những gì?").
     */
    public static String extractExplicitCustomerTargetBudget(String rawUserMessage) {
        if (rawUserMessage == null || rawUserMessage.isBlank()) {
            return null;
        }
        String norm = Normalizer.normalize(rawUserMessage.strip(), Normalizer.Form.NFC);
        Matcher m = EXPLICIT_CUSTOMER_TARGET_BUDGET_PATTERN.matcher(norm);
        while (m.find()) {
            String candidate = m.group(1) != null ? m.group(1).strip() : (m.group(2) != null ? m.group(2).strip() : "");
            if (candidate.isEmpty()) {
                continue;
            }
            int endPos = m.end();
            String trailing = norm.substring(endPos, Math.min(norm.length(), endPos + 24)).toLowerCase(Locale.ROOT);
            if (trailing.matches("(?isU)^\\s*(?:cao|đắt|mắc|chát|hơi\\s+cao|quá\\s+cao|gồm\\s+những\\s+gì|có\\s+gì|làm\\s+trong\\s+bao\\s+lâu).*")) {
                continue;
            }
            return candidate.replaceAll("\\s+", " ").strip();
        }
        return null;
    }

    private static final Pattern EXPLICIT_CUSTOMER_BRANCH_PATTERN = Pattern.compile(
            "(?isU)\\b(?:cơ\\s+sở|chi\\s+nhánh|ở|bên|qua|ghé|sang|tại|khu\\s+vực)\\s+"
                    + "(?:cơ\\s+sở\\s+|chi\\s+nhánh\\s+)?"
                    + "(quận\\s*\\d{1,2}|q\\.?\\s*\\d{1,2}|cầu\\s+giấy|đống\\s+đa|ba\\s+đình|hoàn\\s+kiếm|thanh\\s+xuân|tây\\s+hồ|"
                    + "hai\\s+bà\\s+trưng|hà\\s+đông|long\\s+biên|nam\\s+từ\\s+liêm|bắc\\s+từ\\s+liêm|bình\\s+thạnh|phú\\s+nhuận|"
                    + "tân\\s+bình|thủ\\s+đức|gò\\s+vấp|hà\\s+nội|tp\\.?\\s*hcm|sài\\s+gòn|đà\\s+nẵng)\\b"
    );

    public static String extractExplicitCustomerBranch(String rawUserMessage) {
        if (rawUserMessage == null || rawUserMessage.isBlank()) {
            return null;
        }
        String norm = Normalizer.normalize(rawUserMessage.strip(), Normalizer.Form.NFC);
        Matcher m = EXPLICIT_CUSTOMER_BRANCH_PATTERN.matcher(norm);
        String lastMatch = null;
        while (m.find()) {
            String rawBranch = m.group(1).strip().replaceAll("\\s+", " ");
            String low = rawBranch.toLowerCase(Locale.ROOT);
            if (low.matches("q\\.?\\s*(\\d{1,2})")) {
                String digits = low.replaceAll("\\D+", "");
                lastMatch = "Quận " + digits;
            } else if (low.matches("quận\\s*(\\d{1,2})")) {
                String digits = low.replaceAll("\\D+", "");
                lastMatch = "Quận " + digits;
            } else {
                lastMatch = rawBranch;
            }
        }
        return lastMatch;
    }

    public static boolean isCustomerPreferenceOrNeedRevisionUtterance(String rawUserMessage) {
        if (rawUserMessage == null || rawUserMessage.isBlank()) {
            return false;
        }
        String low = Normalizer.normalize(rawUserMessage.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        return low.matches("(?isU).*\\b(đổi\\s+sang|chuyển\\s+sang|chuyển\\s+qua|đổi\\s+qua|đổi\\s+lại|thay\\s+vì|"
                + "thôi\\s+(?:mình|tôi|em|anh|chị)?\\s*(?:muốn|chọn|đổi|chuyển|lấy|làm)|"
                + "không\\s+(?:làm|lấy|chọn)\\s+[^,.!?]{1,35}\\s+nữa)\\b.*");
    }

    /**
     * P3.3 Mục 2.4 (TC05): Nhận diện khi khách chỉ đang hỏi thông tin về gói (VD: "Ok, gói đó gồm những gì?",
     * "Gói 399k làm trong bao lâu?", "Cho mình hỏi gói 239k") mà CHƯA xác nhận chọn gói hay đặt lịch.
     */
    public static boolean isPurePackageInformationInquiry(String rawUserMessage) {
        if (rawUserMessage == null || rawUserMessage.isBlank()) {
            return false;
        }
        String low = Normalizer.normalize(rawUserMessage.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        boolean hasExplicitSelectionOrBookingVerb = low.matches(
                "(?isU).*\\b(?:chốt\\s+gói|chọn\\s+gói|lấy\\s+gói|đặt\\s+gói|đặt\\s+lịch|book\\s+lịch|chốt\\s+lịch|"
                        + "giữ\\s+chỗ|đăng\\s+ký\\s+gói|cho\\s+(?:mình|tôi|anh|chị|em)\\s+(?:chốt|chọn|lấy|đặt|thử)\\s+gói|"
                        + "thử\\s+gói\\s+\\d|đổi\\s+sang\\s+gói)\\b.*"
        );
        if (hasExplicitSelectionOrBookingVerb) {
            return false;
        }
        return low.contains("?")
                || low.contains("？")
                || low.matches("(?isU).*\\b(gồm\\s+những\\s+gì|gồm\\s+gì|bao\\s+gồm|như\\s+thế\\s+nào|ra\\s+sao|"
                        + "bao\\s+lâu|mấy\\s+phút|khác\\s+gì|hỏi\\s+thêm\\s+về\\s+gói)\\b.*");
    }

    public static List<String> splitAtomicHealthExpressions(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String norm = Normalizer.normalize(raw.strip(), Normalizer.Form.NFC)
                .replaceAll("^[-•*+\\s]+", "")
                .replaceAll("[.!?;,\\s]+$", "");
        if (norm.isBlank()) {
            return List.of();
        }
        String[] parts = ATOMIC_SYMPTOM_SPLIT_PATTERN.split(norm);
        Map<String, String> dedupByCanonical = new LinkedHashMap<>();
        for (String p : parts) {
            if (p == null || p.isBlank()) continue;
            String cleaned = cleanAtomicSymptomValue(p);
            if (cleaned.isBlank()) continue;
            // ACI V3 P1 §3.1: tách biểu hiện ghép nhiều vùng ("đau cổ vai gáy") thành atoms,
            // tránh lưu đồng thời chuỗi tổng hợp và các phần tử con trong extracted_pains
            for (String atom : expandCompoundBodyAreaSymptom(cleaned)) {
                String canon = canonicalSymptomKey(atom);
                if (!canon.isBlank()) {
                    dedupByCanonical.putIfAbsent(canon, atom);
                }
            }
        }
        return List.copyOf(dedupByCanonical.values());
    }

    private static final Set<String> COMPOUND_BODY_AREA_TOKENS = Set.of(
            "cổ", "vai", "gáy", "lưng", "eo", "hông", "chân", "tay", "đầu", "bụng", "mông", "gối"
    );

    private static final Pattern SYMPTOM_VERB_COMPOUND_PREFIX_PATTERN = Pattern.compile(
            "(?iU)^(đau\\s+nhức|đau|mỏi|nhức|căng|tê|sưng|cứng|nóng|nặng)\\s+(.+)$"
    );

    /**
     * ACI V3 P1 §3.1: Tách "{động từ triệu chứng} + chuỗi ≥2 vùng cơ thể" ("đau cổ vai gáy",
     * "mỏi vai gáy") thành các atom từng vùng. Giữ nguyên cụm ghép đặc biệt "đầu gối".
     */
    private static List<String> expandCompoundBodyAreaSymptom(String cleaned) {
        if (cleaned == null || cleaned.isBlank()) {
            return List.of();
        }
        String low = Normalizer.normalize(cleaned.strip(), Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
        Matcher m = SYMPTOM_VERB_COMPOUND_PREFIX_PATTERN.matcher(low);
        if (!m.matches()) {
            return List.of(cleaned);
        }
        String verb = m.group(1).strip();
        String rest = m.group(2).strip()
                .replaceAll("(?iU)\\bđầu\\s+gối\\b", "đầu_gối");
        String[] tokens = rest.split("\\s+");
        if (tokens.length < 2) {
            return List.of(cleaned);
        }
        List<String> areas = new ArrayList<>();
        for (String t : tokens) {
            String tok = t.replace("_", " ");
            if (!COMPOUND_BODY_AREA_TOKENS.contains(tok) && !"đầu gối".equals(tok)) {
                return List.of(cleaned);
            }
            areas.add(tok);
        }
        List<String> expanded = new ArrayList<>(areas.size());
        for (String area : areas) {
            expanded.add(verb + " " + area);
        }
        return expanded;
    }

    public static String cleanAtomicSymptomValue(String rawPart) {
        if (rawPart == null || rawPart.isBlank()) {
            return "";
        }
        String s = Normalizer.normalize(rawPart.strip(), Normalizer.Form.NFC)
                .replaceAll("^[-•*+\\s]+", "")
                .replaceAll("[.!?;,\\s]+$", "");
        s = SYMPTOM_TRAILING_CLAUSE_PATTERN.matcher(s).replaceAll("").strip();
        s = SYMPTOM_LEADING_FILLER_PATTERN.matcher(s).replaceFirst("").strip();
        s = SYMPTOM_TRAILING_CLAUSE_PATTERN.matcher(s).replaceAll("").strip();
        s = s.replaceAll("(?iU)\\s+chết(?:\\s+đi\\s+được|\\s+mất|\\s+luôn|\\s+thật)?$", "").strip();
        String low = s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
        if (low.isEmpty()
                || low.matches("(?isU)^(?:chết|ngại|ngại\\s+quá|ngại\\s+lắm|sợ|sợ\\s+quá|chịu|quá|lắm|luôn|được|thế|vậy|nhỉ|mơ\\s+hồ|đáp\\s+ứng\\s+mơ\\s+hồ|phản\\s+ứng\\s+mơ\\s+hồ|chưa\\s+rõ|không\\s+rõ)$")) {
            return "";
        }
        return s;
    }

    /**
     * Post-CP3 Wave 3 (3.3): Chặn các mảnh cắt cụt ngữ nghĩa khẩu ngữ (như "chết" từ "ngại chết"/"mệt chết"),
     * các từ cảm thán đơn lẻ, và các nhãn suy diễn mơ hồ ("đáp ứng mơ hồ", "chưa rõ") không được ghi vào confirmed_facts.
     */
    public static boolean isInvalidOrTruncatedFactValue(String rawKey, String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return true;
        }
        String norm = Normalizer.normalize(rawValue.strip(), Normalizer.Form.NFC)
                .replaceAll("^[-•*+\\s]+", "")
                .replaceAll("[.!?;,\\s]+$", "")
                .replaceAll("\\s+", " ")
                .strip();
        if (norm.length() < 2) {
            return true;
        }
        String low = norm.toLowerCase(Locale.ROOT);
        if (low.matches("(?isU)^(?:chết|ngại\\s*chết|sợ\\s*chết|chịu\\s*chết|ngại|ngại\\s+quá|ngại\\s+lắm|sợ|sợ\\s+quá|quá|lắm|luôn|được|thế|vậy|nhỉ|à|ừ|ờ|ok|oke|dạ|vâng|không|chưa|rồi|thôi)$")) {
            return true;
        }
        if (low.matches("(?isU).*(?:đáp\\s+ứng\\s+mơ\\s+hồ|phản\\s+ứng\\s+mơ\\s+hồ|thái\\s+độ\\s+mơ\\s+hồ|nhu\\s+cầu\\s+mơ\\s+hồ|ý\\s+định\\s+mơ\\s+hồ|\\bmơ\\s+hồ\\b|chưa\\s+rõ\\s+ràng|không\\s+rõ\\s+ràng|^chưa\\s+rõ$|^không\\s+rõ$|^chưa\\s+xác\\s+định$|^không\\s+xác\\s+định$|^đang\\s+phân\\s+vân$|^còn\\s+băn\\s+khoăn$|^băn\\s+khoăn$|^do\\s+dự$|^nghi\\s+ngờ$).*")) {
            return true;
        }
        String k = rawKey != null ? rawKey.strip().toLowerCase(Locale.ROOT) : "";
        if ("self_reported_symptom".equals(k)) {
            String cleanedSym = cleanAtomicSymptomValue(norm);
            if (cleanedSym.isBlank()) {
                return true;
            }
        }
        if ("price_reaction".equals(k)) {
            boolean hasPriceOrCostSignal = isPriceObjectionFactValue(norm)
                    || low.matches("(?isU).*\\b(giá|đắt|mắc|chát|cao|rẻ|chi\\s+phí|ngân\\s+sách|tiền|\\d+\\s*k)\\b.*");
            if (!hasPriceOrCostSignal) {
                return true;
            }
        }
        return false;
    }

    public static String canonicalSymptomKey(String rawSymptom) {
        String cleaned = cleanAtomicSymptomValue(rawSymptom);
        return cleaned.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    public static String normalizeFactCategoryKey(String rawKey, String rawVal) {
        String k = rawKey != null ? rawKey.strip().toLowerCase(Locale.ROOT) : "";
        if (!k.isEmpty()) {
            if (k.contains("symptom") || k.contains("health_condition") || k.contains("health_issue")) {
                if (isBareBodyAreaScopePhraseWithoutSymptoms(rawVal)) {
                    return "need_scope";
                }
                return "self_reported_symptom";
            }
            if ("budget_note".equals(k)) {
                return isPriceObjectionFactValue(rawVal) ? "price_reaction" : "budget_note";
            }
            if (k.contains("budget") || k.contains("ngan_sach") || k.contains("ngân_sách")) {
                return isPriceObjectionFactValue(rawVal) ? "price_reaction" : "customer_budget";
            }
            if (k.contains("branch") || k.contains("facility") || k.contains("location")
                    || k.contains("chi_nhanh") || k.contains("chi_nhánh")
                    || k.contains("co_so") || k.contains("cơ_sở")) {
                return "preferred_branch";
            }
            if ("preferred_package".equals(k) || "interested_package".equals(k)) {
                return k;
            }
            if (k.contains("need") || k.contains("scope") || k.contains("area") || k.contains("service")) return "need_scope";
            if (k.contains("time") || k.contains("slot") || k.contains("schedule")) return "available_time";
            if (k.contains("price") || k.contains("cost") || k.contains("objection")) return "price_reaction";
            if (k.contains("comm") || k.contains("style")) return "communication_preference";
            if (k.contains("constraint")) return "constraint";
            return k;
        }
        String v = rawVal != null ? Normalizer.normalize(rawVal, Normalizer.Form.NFC).toLowerCase(Locale.ROOT) : "";
        if (v.contains("hoa mắt") || v.contains("chóng mặt") || v.contains("đau đầu")
                || v.contains("khó ngủ") || v.contains("mất ngủ") || v.contains("buồn nôn")
                || v.contains("đau lưng") || v.contains("nặng đầu") || v.contains("tê bì")) {
            return "self_reported_symptom";
        }
        if (v.contains("đắt") || v.contains("giá cao") || isPriceObjectionFactValue(rawVal)) {
            return "price_reaction";
        }
        return "need_scope";
    }

    public static String normalizeConfidenceLevel(String rawLevel, double fallbackScore) {
        if (rawLevel != null && !rawLevel.isBlank()) {
            String u = rawLevel.strip().toUpperCase(Locale.ROOT);
            if (u.contains("HIGH")) return "HIGH";
            if (u.contains("MED")) return "MEDIUM";
            if (u.contains("LOW")) return "LOW";
        }
        if (fallbackScore >= 0.80) return "HIGH";
        if (fallbackScore >= 0.45) return "MEDIUM";
        return "LOW";
    }

    public static String normalizeBarrierType(String rawType, String hypothesisText) {
        if (rawType != null && !rawType.isBlank() && !"null".equalsIgnoreCase(rawType.strip())) {
            String u = rawType.strip().toUpperCase(Locale.ROOT);
            if (u.contains("PERCEPTION_GAP") || "VALUE_PERCEPTION_GAP".equals(u)) return "VALUE_PERCEPTION_GAP";
            if (u.contains("UNCLEAR") || u.contains("VALUE")) return "UNCLEAR_VALUE";
            if (u.contains("COMPETITOR") || u.contains("COMPARE")) return "COMPETITOR_COMPARISON";
            if (u.contains("BUDGET")) return "BUDGET_LIMIT";
            if (u.contains("MEDICAL") || u.contains("HEALTH") || u.contains("SAFETY")) return "MEDICAL_CONCERN";
            if (u.contains("TIME") || u.contains("SCHEDULE")) return "TIME_CONSTRAINT";
            if ("NONE".equals(u)) return "NONE";
            return u;
        }
        String h = hypothesisText != null ? Normalizer.normalize(hypothesisText, Normalizer.Form.NFC).toLowerCase(Locale.ROOT) : "";
        if (h.contains("so sánh") || h.contains("nơi khác")) return "COMPETITOR_COMPARISON";
        if (h.contains("chưa rõ giá trị") || h.contains("trọn gói")
                || (h.contains("giá") && (h.contains("gói") || h.matches(".*\\b\\d{2,4}\\s*k\\b.*")))) {
            return "UNCLEAR_VALUE";
        }
        return "NONE";
    }

    public static String normalizePlannerStrategy(String raw) {
        if (raw == null || raw.isBlank()) {
            return "Direct Answer";
        }
        String s = raw.strip();
        for (String allowed : ALLOWED_PLANNER_STRATEGIES) {
            if (allowed.equalsIgnoreCase(s) || allowed.replace(" ", "_").equalsIgnoreCase(s)) {
                return allowed;
            }
        }
        String u = s.toUpperCase(Locale.ROOT);
        if (u.contains("RESPECTFUL") || u.contains("PAUSE") || u.contains("CLOSURE") || u.contains("DEFER")) return "Respectful Pause";
        if (u.contains("OBJECTION")) return "Handle Objection";
        if (u.contains("REASSURE") || u.contains("HONEST") || u.contains("SAFETY")) return "Reassure Honestly";
        if (u.contains("HANDOFF") || u.contains("TRANSACTION") || u.contains("BOOKING")) return "Transaction Handoff";
        if (u.contains("RECOMMEND")) return "Soft Recommendation";
        if (u.contains("VALUE")) return "Explain Value";
        if (u.contains("CLARIFY")) return "Clarify Gently";
        if (u.contains("LISTEN") || u.contains("REFLECT")) return "Active Listening";
        if (u.contains("WARM") || u.contains("ACK") || u.contains("HOSPITALITY")) return "Warm Acknowledgment";
        if (u.contains("DIRECT") || u.contains("ANSWER")) return "Direct Answer";
        return s;
    }

    public static String normalizePlannerTone(String raw) {
        if (raw == null || raw.isBlank()) {
            return "LEVEL_1_NATURAL";
        }
        String u = raw.strip().toUpperCase(Locale.ROOT);
        if (u.contains("3") || u.contains("SENSORY")) return "LEVEL_3_SENSORY";
        if (u.contains("2") || u.contains("REFLECT") || u.contains("WARM")) return "LEVEL_2_REFLECTIVE";
        return "LEVEL_1_NATURAL";
    }

    public static String normalizePlannerDepth(String raw) {
        if (raw == null || raw.isBlank()) {
            return "STANDARD";
        }
        String u = raw.strip().toUpperCase(Locale.ROOT);
        if (u.contains("CONCISE") || u.contains("SHORT") || u.contains("BRIEF")) return "CONCISE";
        if (u.contains("ON_DEMAND") || u.contains("DETAIL") || u.contains("EXPAND")) return "DETAILED_ON_DEMAND";
        return "STANDARD";
    }

    /**
     * P3.7-B: {@code next_action} dành riêng cho câu hỏi khám phá/lắng nghe nhu cầu ({@code DISCOVERY_QUESTION}).
     * Tách bạch khỏi {@code cta_level} (= {@code SALES_CTA}: mời chọn gói/trải nghiệm/đặt lịch) — một lượt có thể
     * {@code cta_level = NO_CTA} nhưng vẫn được phép đúng 1 câu hỏi khám phá ở cuối phản hồi.
     */
    public static final String DISCOVERY_QUESTION_NEXT_ACTION = "ASK_ONE_DISCOVERY_QUESTION";

    /** P3.7-B: Hai nhánh phục hồi giao tiếp khi khách phàn nàn quy trình tư vấn ({@code PROCESS_FEEDBACK}). */
    public enum FrictionRecoveryMode {
        /** Khách trách chưa được lắng nghe/khám phá nhu cầu -> {@code DISCOVERY_QUESTION = 1}, {@code SALES_CTA = NONE}. */
        MISSED_DISCOVERY,
        /** Khách trách bị hỏi nhiều/nói dài/máy móc/tự khai AI -> {@code DISCOVERY_QUESTION = NONE}, {@code SALES_CTA = NONE}. */
        OVER_INTRUSION
    }

    /**
     * P3.7-B: Hợp giải nhánh {@link FrictionRecoveryMode} — quyết định ngữ nghĩa thuộc về LLM ({@code next_action}
     * trong {@code response_plan}); Java chỉ giữ quyền phủ quyết an toàn theo tín hiệu cấu trúc tổng quát
     * (khách phê phán việc tự khai AI hoặc yêu cầu bớt hỏi/bớt nói) để luôn nghiêng về {@code OVER_INTRUSION} (0 câu hỏi).
     * Mặc định khi LLM không chọn câu hỏi: {@code OVER_INTRUSION} (an toàn, không hỏi thêm).
     */
    public static FrictionRecoveryMode resolveFrictionRecoveryMode(String rawUserMessage, String llmPlannedNextAction) {
        if (CleanAgentOutputRefereeAndSanitizer.isOverIntrusionFeedbackCue(rawUserMessage)) {
            return FrictionRecoveryMode.OVER_INTRUSION;
        }
        String normalized = normalizePlannerNextAction(llmPlannedNextAction);
        if (DISCOVERY_QUESTION_NEXT_ACTION.equals(normalized)
                || "ASK_ONE_CLARIFYING_QUESTION".equals(normalized)) {
            return FrictionRecoveryMode.MISSED_DISCOVERY;
        }
        return FrictionRecoveryMode.OVER_INTRUSION;
    }

    public static String normalizePlannerNextAction(String raw) {
        if (raw == null || raw.isBlank()) {
            return "NO_ADDITIONAL_ACTION";
        }
        String u = raw.strip().toUpperCase(Locale.ROOT);
        if (u.contains("NO_ADDITIONAL") || "NONE".equals(u) || "STOP".equals(u) || "NO_QUESTION".equals(u)) {
            return "NO_ADDITIONAL_ACTION";
        }
        if (u.contains("DISCOVERY")) return DISCOVERY_QUESTION_NEXT_ACTION;
        if (u.contains("CLARIFY") || u.contains("QUESTION")) return "ASK_ONE_CLARIFYING_QUESTION";
        if (u.contains("SCHEDUL") || u.contains("BOOK") || u.contains("AVAILAB")) return "GENTLE_SCHEDULE_CHECK";
        if (u.contains("FEEL") || u.contains("INVITE") || u.contains("SOFT")) return "SOFT_FEELING_CHECK";
        return raw.strip();
    }

    public static boolean isExplicitStopOrRefusalIntentLabel(String rawIntent) {
        if (rawIntent == null || rawIntent.isBlank()) {
            return false;
        }
        String u = rawIntent.strip().toUpperCase(Locale.ROOT);
        return u.contains("EXPLICIT_REFUSAL")
                || u.contains("STOP_CONSULT")
                || u.contains("DECLINE_SERVICE")
                || u.contains("DECLINE_ALL")
                || u.equals("OPT_OUT")
                || u.equals("POLITE_CLOSURE");
    }

    public static boolean isBookingSignalIntentLabel(String rawIntent) {
        if (rawIntent == null || rawIntent.isBlank()) {
            return false;
        }
        String u = rawIntent.strip().toUpperCase(Locale.ROOT);
        return u.contains("BOOKING")
                || u.contains("SCHEDULE")
                || u.contains("REGISTER")
                || u.contains("APPOINTMENT");
    }

    public static boolean isPriceOrCompetitorObjectionIntentLabel(String rawIntent) {
        if (rawIntent == null || rawIntent.isBlank()) {
            return false;
        }
        String u = rawIntent.strip().toUpperCase(Locale.ROOT);
        return u.contains("COMPETITOR")
                || u.contains("PRICE_OBJECTION")
                || u.contains("PRICE_COMPARISON")
                || u.contains("VALUE_OBJECTION")
                || u.contains("COST_OBJECTION")
                || u.contains("PRIVACY_OBJECTION")
                || u.contains("PRIVACY_CONCERN");
    }

    public static String normalizeConversationStateLabel(
            String rawState,
            String primaryIntent,
            List<String> secondaryIntents,
            String effectiveBarrierType,
            BookingReadiness bookingReadiness,
            BuyingSignal buyingSignal,
            InteractionPermission interactionPermission
    ) {
        List<String> sec = secondaryIntents != null ? secondaryIntents : List.of();
        boolean secHasBooking = sec.stream().anyMatch(AdaptiveConversationIntelligence::isBookingSignalIntentLabel);
        boolean secHasObjection = sec.stream().anyMatch(AdaptiveConversationIntelligence::isPriceOrCompetitorObjectionIntentLabel);
        if (isExplicitStopOrRefusalIntentLabel(primaryIntent)) {
            return "PAUSED_BY_CUSTOMER";
        }
        if (secHasBooking || isBookingSignalIntentLabel(primaryIntent)
                || bookingReadiness == BookingReadiness.READY_TO_BOOK
                || buyingSignal == BuyingSignal.STRONG
                || buyingSignal == BuyingSignal.ACTION) {
            return "BOOKING_READY";
        }
        if ("COMPETITOR_COMPARISON".equalsIgnoreCase(effectiveBarrierType)
                || secHasObjection
                || isPriceOrCompetitorObjectionIntentLabel(primaryIntent)) {
            return "OBJECTION_NEGOTIATION";
        }
        if (rawState != null && !rawState.isBlank()) {
            String u = rawState.strip().toUpperCase(Locale.ROOT);
            if (u.contains("PAUSE") || u.contains("STOP") || u.contains("CLOS")) return "PAUSED_BY_CUSTOMER";
            if (u.contains("BOOK")) return "BOOKING_READY";
            if (u.contains("OBJECT") || u.contains("NEGOT")) return "OBJECTION_NEGOTIATION";
            if (u.contains("META") || u.contains("PLAYFUL") || u.contains("BANTER")) return "META_PLAYFUL";
            if (u.contains("ACTIVE") || u.contains("CONSULT") || u.contains("DISCOVER")) return "ACTIVE_CONSULTATION";
        }
        if (interactionPermission == InteractionPermission.PAUSED) {
            return "PAUSED_BY_CUSTOMER";
        }
        String pUpper = primaryIntent != null ? primaryIntent.strip().toUpperCase(Locale.ROOT) : "";
        if (pUpper.contains("META") || pUpper.contains("PLAYFUL") || pUpper.contains("BANTER") || pUpper.contains("HYPOTHETICAL")) {
            return "META_PLAYFUL";
        }
        return "ACTIVE_CONSULTATION";
    }

    public record CustomerUnderstanding(
            String detectedIntent,
            EmotionalState emotionalState,
            String inferredCustomerPronoun,
            double pronounConfidence,
            Boolean costConcernExpressed,
            String verifiedSymptomDuration,
            BookingReadiness bookingReadiness,
            String communicationStylePreference,
            InteractionPermission interactionPermission,
            BuyingSignal buyingSignal,
            List<String> secondaryIntents,
            String conversationState
    ) {
        public CustomerUnderstanding {
            detectedIntent = detectedIntent != null ? detectedIntent.strip() : "";
            emotionalState = emotionalState != null ? emotionalState : EmotionalState.empty();
            inferredCustomerPronoun = normalizeInferredSpeakerPronoun(inferredCustomerPronoun);
            pronounConfidence = Math.max(0.0, Math.min(1.0, pronounConfidence));
            verifiedSymptomDuration = cleanNullable(verifiedSymptomDuration);
            communicationStylePreference = normalizeCommunicationStylePreference(communicationStylePreference);
            buyingSignal = buyingSignal != null ? buyingSignal : BuyingSignal.NONE;
            secondaryIntents = secondaryIntents != null
                    ? secondaryIntents.stream().filter(s -> s != null && !s.isBlank()).map(String::strip).toList()
                    : List.of();
            conversationState = (conversationState != null && !conversationState.isBlank())
                    ? conversationState.strip().toUpperCase(Locale.ROOT)
                    : "ACTIVE_CONSULTATION";
        }

        public CustomerUnderstanding(
                String detectedIntent,
                EmotionalState emotionalState,
                String inferredCustomerPronoun,
                double pronounConfidence,
                Boolean costConcernExpressed,
                String verifiedSymptomDuration,
                BookingReadiness bookingReadiness,
                String communicationStylePreference,
                InteractionPermission interactionPermission,
                BuyingSignal buyingSignal
        ) {
            this(detectedIntent, emotionalState, inferredCustomerPronoun, pronounConfidence,
                    costConcernExpressed, verifiedSymptomDuration, bookingReadiness, communicationStylePreference,
                    interactionPermission, buyingSignal, List.of(),
                    normalizeConversationStateLabel(null, detectedIntent, List.of(), null,
                            bookingReadiness, buyingSignal, interactionPermission));
        }

        public CustomerUnderstanding(
                String detectedIntent,
                EmotionalState emotionalState,
                String inferredCustomerPronoun,
                double pronounConfidence,
                Boolean costConcernExpressed,
                String verifiedSymptomDuration,
                BookingReadiness bookingReadiness,
                String communicationStylePreference
        ) {
            this(detectedIntent, emotionalState, inferredCustomerPronoun, pronounConfidence,
                    costConcernExpressed, verifiedSymptomDuration, bookingReadiness, communicationStylePreference,
                    null, BuyingSignal.NONE, List.of(), "ACTIVE_CONSULTATION");
        }

        public CustomerUnderstanding(
                String detectedIntent,
                EmotionalState emotionalState,
                String inferredCustomerPronoun,
                double pronounConfidence,
                Boolean costConcernExpressed,
                String verifiedSymptomDuration
        ) {
            this(detectedIntent, emotionalState, inferredCustomerPronoun, pronounConfidence,
                    costConcernExpressed, verifiedSymptomDuration, null, null, null, BuyingSignal.NONE,
                    List.of(), "ACTIVE_CONSULTATION");
        }

        public CustomerUnderstanding(String detectedIntent, EmotionalState emotionalState) {
            this(detectedIntent, emotionalState, null, 0.0, null, null, null, null, null, BuyingSignal.NONE,
                    List.of(), "ACTIVE_CONSULTATION");
        }

        public String primaryIntent() {
            return detectedIntent;
        }

        public boolean hasHighConfidenceInferredPronoun() {
            return inferredCustomerPronoun != null
                    && pronounConfidence >= INFERRED_PRONOUN_HIGH_CONFIDENCE_THRESHOLD;
        }

        public static CustomerUnderstanding empty() {
            return new CustomerUnderstanding(
                    "", EmotionalState.empty(), null, 0.0, null, null, null, null, null, BuyingSignal.NONE,
                    List.of(), "ACTIVE_CONSULTATION");
        }
    }

    public record PriorityFocus(
            String primary,
            List<String> secondary
    ) {
        public PriorityFocus {
            primary = (primary != null && !primary.isBlank()) ? primary.strip().toUpperCase(Locale.ROOT) : "CLARIFICATION";
            secondary = secondary != null ? List.copyOf(secondary) : List.of();
        }

        public static PriorityFocus empty() {
            return new PriorityFocus("CLARIFICATION", List.of());
        }
    }

    public record WorkingHypothesisReflection(
            String content,
            HypothesisLifecycleOp lifecycleOp,
            double confidence,
            List<String> evidenceTurnIds
    ) {
        public WorkingHypothesisReflection {
            content = content != null ? content.strip() : "";
            lifecycleOp = lifecycleOp != null ? lifecycleOp : HypothesisLifecycleOp.MAINTAIN;
            confidence = Math.max(0.0, Math.min(1.0, confidence));
            evidenceTurnIds = evidenceTurnIds != null ? List.copyOf(evidenceTurnIds) : List.of();
        }

        public String hypothesis() {
            return content;
        }

        public List<String> evidence() {
            return evidenceTurnIds;
        }

        public static WorkingHypothesisReflection empty() {
            return new WorkingHypothesisReflection("", HypothesisLifecycleOp.MAINTAIN, 0.0, List.of());
        }
    }

    public record StrategyReflection(
            String chosen,
            StrategyAdaptationMode adaptation,
            StrategyOutcome previousOutcome,
            List<String> evidenceTurnIds
    ) {
        public StrategyReflection {
            chosen = chosen != null ? chosen.strip() : "";
            adaptation = adaptation != null ? adaptation : StrategyAdaptationMode.CONTINUATION;
            previousOutcome = previousOutcome != null ? previousOutcome : StrategyOutcome.NOT_APPLICABLE;
            evidenceTurnIds = evidenceTurnIds != null ? List.copyOf(evidenceTurnIds) : List.of();
        }

        public String nextStrategy() {
            return chosen;
        }

        public List<String> evidence() {
            return evidenceTurnIds;
        }

        public static StrategyReflection empty() {
            return new StrategyReflection("", StrategyAdaptationMode.CONTINUATION, StrategyOutcome.NOT_APPLICABLE, List.of());
        }
    }

    public record CommitmentDeltaItem(
            CommitmentOperation operation,
            String content,
            CommitmentOwner owner,
            String verifiedTimeframe
    ) {
        public CommitmentDeltaItem {
            content = content != null ? content.strip() : "";
            owner = owner != null ? owner : CommitmentOwner.STAFF;
            verifiedTimeframe = (verifiedTimeframe != null && !verifiedTimeframe.isBlank()
                    && !"null".equalsIgnoreCase(verifiedTimeframe.strip()))
                    ? verifiedTimeframe.strip()
                    : null;
        }
    }

    public record HandoffRequestReflection(
            boolean requested,
            String reason
    ) {
        public HandoffRequestReflection {
            reason = (reason != null && !reason.isBlank() && !"null".equalsIgnoreCase(reason.strip()))
                    ? reason.strip()
                    : null;
        }

        public static HandoffRequestReflection none() {
            return new HandoffRequestReflection(false, null);
        }
    }

    public record ProposedBookingPatch(
            String selectedProductId,
            String proposedDate,
            String proposedTime
    ) {
        public ProposedBookingPatch {
            selectedProductId = cleanNullable(selectedProductId);
            proposedDate = cleanNullable(proposedDate);
            proposedTime = cleanNullable(proposedTime);
        }

        public static ProposedBookingPatch empty() {
            return new ProposedBookingPatch(null, null, null);
        }

        public boolean hasAnyProposal() {
            return selectedProductId != null || proposedDate != null || proposedTime != null;
        }
    }

    public record AdaptiveCognitiveReflection(
            CustomerUnderstanding customerUnderstanding,
            PriorityFocus priority,
            WorkingHypothesisReflection workingHypothesis,
            StrategyReflection strategy,
            List<String> uncertainties,
            List<String> customerStatements,
            List<CommitmentDeltaItem> commitmentDelta,
            HandoffRequestReflection handoffRequest,
            ProposedBookingPatch proposedBookingPatch,
            int blockedUnauthorizedCompletionCount,
            boolean parsedFromV2Schema,
            List<String> activatedSkillCodes,
            boolean explicitActivatedSkillCodes,
            String currentTopic,
            List<UnresolvedRequestItem> unresolvedRequestsDelta,
            List<String> pendingReferences,
            String conversationGoal,
            boolean needRevisionDetected,
            List<FactDeltaProposal> confirmedFactsDelta,
            String barrierType,
            String hypothesisConfidenceLevel,
            String interestedPackageId,
            AdaptiveResponsePlan responsePlan
    ) {
        public AdaptiveCognitiveReflection {
            customerUnderstanding = customerUnderstanding != null ? customerUnderstanding : CustomerUnderstanding.empty();
            priority = priority != null ? priority : PriorityFocus.empty();
            workingHypothesis = workingHypothesis != null ? workingHypothesis : WorkingHypothesisReflection.empty();
            strategy = strategy != null ? strategy : StrategyReflection.empty();
            uncertainties = uncertainties != null ? List.copyOf(uncertainties) : List.of();
            customerStatements = customerStatements != null ? List.copyOf(customerStatements) : List.of();
            commitmentDelta = commitmentDelta != null ? List.copyOf(commitmentDelta) : List.of();
            handoffRequest = handoffRequest != null ? handoffRequest : HandoffRequestReflection.none();
            proposedBookingPatch = proposedBookingPatch != null ? proposedBookingPatch : ProposedBookingPatch.empty();
            if (activatedSkillCodes != null && !activatedSkillCodes.isEmpty()) {
                Set<String> dedup = new LinkedHashSet<>();
                for (String c : activatedSkillCodes) {
                    if (c != null && !c.isBlank() && !"null".equalsIgnoreCase(c.strip())) {
                        dedup.add(c.strip());
                        if (dedup.size() >= 2) {
                            break;
                        }
                    }
                }
                activatedSkillCodes = List.copyOf(dedup);
            } else {
                activatedSkillCodes = List.of();
            }
            currentTopic = cleanNullable(currentTopic);
            unresolvedRequestsDelta = unresolvedRequestsDelta != null ? List.copyOf(unresolvedRequestsDelta) : List.of();
            pendingReferences = pendingReferences != null ? List.copyOf(pendingReferences) : List.of();
            conversationGoal = cleanGoalField(conversationGoal);
            confirmedFactsDelta = confirmedFactsDelta != null ? List.copyOf(confirmedFactsDelta) : List.of();
            barrierType = normalizeBarrierType(barrierType, workingHypothesis.content());
            hypothesisConfidenceLevel = normalizeConfidenceLevel(hypothesisConfidenceLevel, workingHypothesis.confidence());
            interestedPackageId = cleanNullable(interestedPackageId);
            responsePlan = responsePlan != null ? responsePlan : AdaptiveResponsePlan.empty();
        }

        public AdaptiveCognitiveReflection withConfirmedFactsDelta(List<FactDeltaProposal> newDelta) {
            return new AdaptiveCognitiveReflection(
                    customerUnderstanding,
                    priority,
                    workingHypothesis,
                    strategy,
                    uncertainties,
                    customerStatements,
                    commitmentDelta,
                    handoffRequest,
                    proposedBookingPatch,
                    blockedUnauthorizedCompletionCount,
                    parsedFromV2Schema,
                    activatedSkillCodes,
                    explicitActivatedSkillCodes,
                    currentTopic,
                    unresolvedRequestsDelta,
                    pendingReferences,
                    conversationGoal,
                    needRevisionDetected,
                    newDelta,
                    barrierType,
                    hypothesisConfidenceLevel,
                    interestedPackageId,
                    responsePlan
            );
        }

        public AdaptiveCognitiveReflection withForcedNoCta() {
            // P3.7-B: NO_CTA chỉ khóa SALES_CTA; câu hỏi khám phá (DISCOVERY_QUESTION) đã được hợp giải ở nhánh
            // MISSED_DISCOVERY thì được giữ nguyên (không phải lời mời mua/đặt lịch).
            String keptNextAction = DISCOVERY_QUESTION_NEXT_ACTION.equals(responsePlan.nextAction())
                    ? DISCOVERY_QUESTION_NEXT_ACTION
                    : "NO_ADDITIONAL_ACTION";
            AdaptiveResponsePlan noCtaPlan = new AdaptiveResponsePlan(
                    responsePlan.objective(),
                    responsePlan.strategy(),
                    responsePlan.tone(),
                    responsePlan.depth(),
                    keptNextAction,
                    CtaLevel.NO_CTA,
                    responsePlan.adaptation(),
                    responsePlan.previousOutcome()
            );
            return new AdaptiveCognitiveReflection(
                    customerUnderstanding,
                    priority,
                    workingHypothesis,
                    strategy,
                    uncertainties,
                    customerStatements,
                    commitmentDelta,
                    handoffRequest,
                    ProposedBookingPatch.empty(),
                    blockedUnauthorizedCompletionCount,
                    parsedFromV2Schema,
                    activatedSkillCodes,
                    explicitActivatedSkillCodes,
                    currentTopic,
                    unresolvedRequestsDelta,
                    pendingReferences,
                    conversationGoal,
                    needRevisionDetected,
                    confirmedFactsDelta,
                    barrierType,
                    hypothesisConfidenceLevel,
                    interestedPackageId,
                    noCtaPlan
            );
        }

        /**
         * P3.6 Multi-Intent &amp; Conversation State Reconciliation với phát ngôn hiện tại của khách:
         * đảm bảo không bị mất tín hiệu phụ (đặt lịch trong câu phức hợp, so sánh giá bên ngoài 50k/100k)
         * và tôn trọng tuyệt đối lời từ chối dừng tư vấn rõ ràng của khách.
         */
        public AdaptiveCognitiveReflection reconcileWithCurrentUtterance(String rawUserMessage) {
            return reconcileWithCurrentUtterance(rawUserMessage, null);
        }

        /**
         * ACI V3 P1 §3.1 (hiệu chỉnh): bằng chứng từ vựng của một fact = phát ngôn hiện tại
         * <b>hoặc</b> các phát ngôn khách đã nói trước đó trong cùng phiên (LLM thường mang
         * các fact đã xác nhận ở lượt trước sang confirmed_facts_delta của lượt hiện tại —
         * VD khách nói "đau đầu chóng mặt" ở T1, lượt T3 chỉ nói "thỉnh thoảng bị lại").
         * Fact KHÔNG có bằng chứng ở cả hai nơi (bot tự suy diễn, VD "cổ vai gáy" khi khách
         * chỉ từng nói "tê chân") vẫn bị loại tuyệt đối.
         */
        public AdaptiveCognitiveReflection reconcileWithCurrentUtterance(String rawUserMessage,
                String historicalCustomerEvidence) {
            if (rawUserMessage == null || rawUserMessage.isBlank()
                    || PromptSecurityGuard.isDirectJailbreakAttempt(rawUserMessage)) {
                return this;
            }
            // ACI V3 P1 §3.1: Fact grounding — mọi fact ADD do LLM đề xuất phải có bằng chứng từ vựng
            // trong lời khách (lượt hiện tại hoặc lịch sử phiên); fact không có bằng chứng (bot tự
            // suy diễn, VD "cổ vai gáy" khi khách chỉ nói "tê chân") bị loại khỏi confirmed_facts_delta,
            // không bao giờ được ghi CONFIRMED/HIGH với source=CUSTOMER_MESSAGE.
            String groundingEvidence = historicalCustomerEvidence != null && !historicalCustomerEvidence.isBlank()
                    ? rawUserMessage + "\n" + historicalCustomerEvidence
                    : rawUserMessage;
            List<FactDeltaProposal> groundedDelta = new ArrayList<>();
            boolean droppedUngrounded = false;
            for (FactDeltaProposal f : confirmedFactsDelta) {
                if (f == null || isInvalidOrTruncatedFactValue(f.key(), f.value())) {
                    droppedUngrounded = true;
                    continue;
                }
                if (f.op() == FactMutationOp.ADD
                        && !("price_reaction".equals(f.key())
                            && hasGenuinePriceObjectionCue(rawUserMessage)
                            && isPriceObjectionFactValue(f.value()))
                        && !hasLexicalEvidenceInUtterance(f.value(), groundingEvidence)) {
                    droppedUngrounded = true;
                    continue;
                }
                groundedDelta.add(f);
            }
            if (droppedUngrounded) {
                return withConfirmedFactsDelta(groundedDelta)
                        .reconcileWithCurrentUtterance(rawUserMessage, historicalCustomerEvidence);
            }
            String normLower = Normalizer.normalize(rawUserMessage.strip(), Normalizer.Form.NFC)
                    .toLowerCase(Locale.ROOT);
            boolean explicitFullPriceListRequest = classifyPriceIntentSubMode(rawUserMessage)
                    == PriceIntentSubMode.EXPLICIT_FULL_PRICE_LIST_REQUEST;
            boolean explicitStop = !explicitFullPriceListRequest
                    && CleanAgentOrchestratorService.isExplicitConversationRefusalStopMessage(rawUserMessage);
            boolean politeThankYouClosingAck = !explicitStop
                    && !explicitFullPriceListRequest
                    && CleanAgentOrchestratorService.isPoliteThankYouClosingAckMessage(rawUserMessage);
            boolean directBotIdentityQuestion = CleanAgentOutputRefereeAndSanitizer
                    .isDirectBotIdentityOrTrainingQuestion(rawUserMessage);
            boolean fix5Enabled = CleanAgentOutputRefereeAndSanitizer.isFix5MixedIntentRecoveryEnabled();
            boolean mixedComplaintWithBusinessDemand = fix5Enabled
                    && !explicitStop
                    && !politeThankYouClosingAck
                    && !directBotIdentityQuestion
                    && !explicitFullPriceListRequest
                    && CleanAgentOutputRefereeAndSanitizer.isProcessOrConsultationFeedbackComplaint(rawUserMessage)
                    && (CleanAgentOutputRefereeAndSanitizer.hasExplicitPriceDemandInMessage(rawUserMessage)
                        || CleanAgentOutputRefereeAndSanitizer.hasBusinessOrCatalogClarificationDemandOnComplaintTurn(rawUserMessage));
            boolean processFeedbackComplaint = !explicitStop
                    && !politeThankYouClosingAck
                    && !directBotIdentityQuestion
                    && !explicitFullPriceListRequest
                    && !mixedComplaintWithBusinessDemand
                    && (CleanAgentOutputRefereeAndSanitizer.isProcessOrConsultationFeedbackComplaint(rawUserMessage)
                        || (!fix5Enabled
                            && customerUnderstanding.secondaryIntents() != null
                            && customerUnderstanding.secondaryIntents().stream().anyMatch(s ->
                                s != null && (s.toUpperCase(Locale.ROOT).contains("PROCESS_FEEDBACK")
                                        || s.toUpperCase(Locale.ROOT).contains("CONSULTATION_FEEDBACK")
                                        || s.toUpperCase(Locale.ROOT).contains("STYLE_COMPLAINT")))));
            boolean stickyFrictionOnNonComplaintTurn = fix5Enabled
                    && !explicitStop
                    && !politeThankYouClosingAck
                    && !processFeedbackComplaint
                    && !mixedComplaintWithBusinessDemand
                    && !CleanAgentOutputRefereeAndSanitizer.isProcessOrConsultationFeedbackComplaint(rawUserMessage)
                    && !isCustomerCorrectionOrComponentRejectionMessage(rawUserMessage)
                    && !PrimaryActionRouter.isFrustrationMessage(rawUserMessage)
                    && ("FRICTION_RECOVERY".equalsIgnoreCase(customerUnderstanding.conversationState())
                        || "PROCESS_FEEDBACK".equalsIgnoreCase(customerUnderstanding.detectedIntent())
                        || (priority != null && "SERVICE_RECOVERY".equalsIgnoreCase(priority.primary())));
            boolean pureBudgetBoundedInquiry = !explicitStop
                    && !politeThankYouClosingAck
                    && !processFeedbackComplaint
                    && !explicitFullPriceListRequest
                    && CleanAgentOrchestratorService.isPureBudgetBoundedServiceInquiry(rawUserMessage);
            boolean competitorComparison = !explicitStop
                    && !politeThankYouClosingAck
                    && !processFeedbackComplaint
                    && !pureBudgetBoundedInquiry
                    && !explicitFullPriceListRequest
                    && CleanAgentOrchestratorService.hasCompetitorOrExternalPriceComparisonCue(rawUserMessage);
            boolean negatedBooking = normLower.matches(
                    "(?isU).*\\b(?:không|ko|chưa|khỏi|đừng|chẳng)\\s+(?:muốn\\s+|cần\\s+|vội\\s+|định\\s+)?(?:đăng\\s+ký\\s+)?(?:đặt|book|chốt)\\s+lịch\\b.*"
            );
            boolean affirmativeBookingCue = !explicitStop && !politeThankYouClosingAck && !processFeedbackComplaint && !negatedBooking && normLower.matches(
                    "(?isU).*\\b(đăng\\s+ký\\s+đặt\\s+lịch|(?:rồi\\s+)?(?:anh|chị|mình|tôi|em|a)\\s+(?:đăng\\s+ký\\s+)?(?:đặt|book|chốt)\\s+lịch|đặt\\s+lịch\\s+(?:nhé|nha|giúp|cho)|chốt\\s+lịch\\s+(?:nhé|nha|luôn))\\b.*"
            );
            boolean barePauseOrAck = !explicitStop
                    && !politeThankYouClosingAck
                    && !processFeedbackComplaint
                    && !affirmativeBookingCue
                    && !explicitFullPriceListRequest
                    && isBarePauseOrAcknowledgementMessage(rawUserMessage)
                    && (proposedBookingPatch == null
                        || (proposedBookingPatch.proposedDate() == null
                            && proposedBookingPatch.proposedTime() == null
                            && proposedBookingPatch.selectedProductId() == null));
            boolean customerDeferral = !explicitStop
                    && !politeThankYouClosingAck
                    && !processFeedbackComplaint
                    && !affirmativeBookingCue
                    && !explicitFullPriceListRequest
                    && !competitorComparison
                    && !barePauseOrAck
                    && (isCustomerDeferralMessage(rawUserMessage)
                        || CleanAgentOrchestratorService.isHesitationOrDeferralMessage(rawUserMessage)
                        || CleanAgentOrchestratorService.isPureBusyOrStopDeferralMessage(rawUserMessage))
                    && !hasGenuinePriceObjectionCue(rawUserMessage);
            boolean explicitGenderCorrection = !explicitStop
                    && !politeThankYouClosingAck
                    && !processFeedbackComplaint
                    && !competitorComparison
                    && !affirmativeBookingCue
                    && !explicitFullPriceListRequest
                    && !customerDeferral
                    && isExplicitGenderOrPronounCorrectionMessage(rawUserMessage)
                    && !hasGenuinePriceObjectionCue(rawUserMessage)
                    && classifyPriceIntentSubMode(rawUserMessage) == PriceIntentSubMode.NONE;
            boolean firstGenuinePriceObjection = !explicitStop
                    && !politeThankYouClosingAck
                    && !processFeedbackComplaint
                    && !competitorComparison
                    && !pureBudgetBoundedInquiry
                    && !explicitFullPriceListRequest
                    && hasGenuinePriceObjectionCue(rawUserMessage);
            boolean explicitHumanEscalation = !explicitStop
                    && isExplicitHumanEscalationMessage(rawUserMessage)
                    && !FanpageBotService.isUniversalAcuteMedicalEmergency(rawUserMessage);

            // P3.3 Mục 2.4: Trích xuất & đối soát bằng chứng từ phát ngôn khách (ngân sách mục tiêu vs phản đối giá,
            // cơ sở/chi nhánh, đính chính/thay đổi ý định, và hỏi thông tin gói chưa chọn).
            String explicitTargetBudget = !explicitStop && !politeThankYouClosingAck && !processFeedbackComplaint
                    ? extractExplicitCustomerTargetBudget(rawUserMessage)
                    : null;
            String explicitBranch = !explicitStop && !politeThankYouClosingAck && !processFeedbackComplaint
                    ? extractExplicitCustomerBranch(rawUserMessage)
                    : null;
            boolean utteranceHasRevisionCue = !explicitStop && !politeThankYouClosingAck && !processFeedbackComplaint
                    && isCustomerPreferenceOrNeedRevisionUtterance(rawUserMessage);
            boolean purePkgInfoInquiry = !explicitStop && !politeThankYouClosingAck && !processFeedbackComplaint
                    && !affirmativeBookingCue
                    && isPurePackageInformationInquiry(rawUserMessage);
            boolean bareBodyAreaScopeReply = !explicitStop
                    && !politeThankYouClosingAck
                    && !processFeedbackComplaint
                    && !affirmativeBookingCue
                    && isBareBodyAreaScopePhraseWithoutSymptoms(rawUserMessage);
            boolean hasP33EvidenceOrPatchUpdate = explicitTargetBudget != null
                    || explicitBranch != null
                    || utteranceHasRevisionCue
                    || bareBodyAreaScopeReply
                    || (purePkgInfoInquiry
                        && proposedBookingPatch != null
                        && proposedBookingPatch.selectedProductId() != null
                        && proposedBookingPatch.proposedDate() == null
                        && proposedBookingPatch.proposedTime() == null);

            if (!explicitStop
                    && !politeThankYouClosingAck
                    && !processFeedbackComplaint
                    && !mixedComplaintWithBusinessDemand
                    && !stickyFrictionOnNonComplaintTurn
                    && !pureBudgetBoundedInquiry
                    && !competitorComparison
                    && !affirmativeBookingCue
                    && !barePauseOrAck
                    && !customerDeferral
                    && !explicitFullPriceListRequest
                    && !explicitGenderCorrection
                    && !firstGenuinePriceObjection
                    && !explicitHumanEscalation
                    && !hasP33EvidenceOrPatchUpdate) {
                return this;
            }

            CustomerUnderstanding cu = customerUnderstanding;
            String detIntent = cu.detectedIntent();
            EmotionalState emoState = cu.emotionalState();
            String reconciledInferredPronoun = cu.inferredCustomerPronoun();
            double reconciledPronounConf = cu.pronounConfidence();
            List<String> secIntents = new ArrayList<>(cu.secondaryIntents());
            String convState = cu.conversationState();
            Boolean costConcern = cu.costConcernExpressed();
            BookingReadiness readiness = cu.bookingReadiness();
            InteractionPermission perm = cu.interactionPermission();
            BuyingSignal buySig = cu.buyingSignal();
            PriorityFocus prio = priority;
            String effBarrier = barrierType;
            AdaptiveResponsePlan plan = responsePlan;
            StrategyReflection strat = strategy;
            List<String> reconciledSkills = new ArrayList<>(activatedSkillCodes);
            List<FactDeltaProposal> reconciledFactsDelta = new ArrayList<>(confirmedFactsDelta);
            ProposedBookingPatch reconciledBookingPatch = proposedBookingPatch;
            HandoffRequestReflection reconciledHandoff = handoffRequest;
            boolean reconciledNeedRevision = needRevisionDetected;
            String reconciledInterestedPkgId = interestedPackageId;

            // P3.3 TC05 & Post-CP3 Wave 1 (1.2): Khi khách chỉ hỏi thông tin về gói ("Ok, gói đó gồm những gì?", "Gói 399k gồm những gì?")
            // hoặc chỉ mới nêu vùng cơ thể cần chăm sóc ("Đầu và vai gáy", "Chân và lưng"),
            // không được ghi nhận thành selected_product_id; chỉ giữ ở interestedPackageId.
            if ((purePkgInfoInquiry || bareBodyAreaScopeReply)
                    && reconciledBookingPatch != null
                    && reconciledBookingPatch.selectedProductId() != null
                    && reconciledBookingPatch.proposedDate() == null
                    && reconciledBookingPatch.proposedTime() == null) {
                if (reconciledInterestedPkgId == null || reconciledInterestedPkgId.isBlank()) {
                    reconciledInterestedPkgId = reconciledBookingPatch.selectedProductId();
                }
                reconciledBookingPatch = ProposedBookingPatch.empty();
            }
            if (bareBodyAreaScopeReply) {
                boolean hasNeedScopeDelta = false;
                for (int i = 0; i < reconciledFactsDelta.size(); i++) {
                    FactDeltaProposal d = reconciledFactsDelta.get(i);
                    if (d == null) continue;
                    if ("need_scope".equalsIgnoreCase(d.key())) {
                        hasNeedScopeDelta = true;
                    } else if ("self_reported_symptom".equalsIgnoreCase(d.key())
                            && isBareBodyAreaScopePhraseWithoutSymptoms(d.value())) {
                        reconciledFactsDelta.set(i, new FactDeltaProposal(
                                "need_scope", d.value(), d.op(), d.confidence(), d.explicitValidity()));
                        hasNeedScopeDelta = true;
                    }
                }
                if (!hasNeedScopeDelta) {
                    reconciledFactsDelta.add(new FactDeltaProposal(
                            "need_scope",
                            rawUserMessage.strip(),
                            FactMutationOp.ADD,
                            "HIGH"
                    ));
                }
            }

            // P3.3 TC01 & TC04: Ghi nhận ngân sách mục tiêu tường minh của khách và cơ sở/chi nhánh khách nêu
            if (explicitTargetBudget != null) {
                boolean hasBudgetDelta = reconciledFactsDelta.stream()
                        .anyMatch(d -> d != null && ("customer_budget".equals(d.key()) || "budget_note".equals(d.key())));
                if (!hasBudgetDelta) {
                    reconciledFactsDelta.add(new FactDeltaProposal(
                            "customer_budget",
                            explicitTargetBudget,
                            utteranceHasRevisionCue ? FactMutationOp.REVISE : FactMutationOp.ADD,
                            "HIGH"
                    ));
                }
            }
            if (explicitBranch != null) {
                boolean hasBranchDelta = reconciledFactsDelta.stream()
                        .anyMatch(d -> d != null && "preferred_branch".equals(d.key()));
                if (!hasBranchDelta) {
                    reconciledFactsDelta.add(new FactDeltaProposal(
                            "preferred_branch",
                            explicitBranch,
                            utteranceHasRevisionCue ? FactMutationOp.REVISE : FactMutationOp.ADD,
                            "HIGH"
                    ));
                }
            }
            if (utteranceHasRevisionCue) {
                for (int i = 0; i < reconciledFactsDelta.size(); i++) {
                    FactDeltaProposal d = reconciledFactsDelta.get(i);
                    if (d != null && d.op() == FactMutationOp.ADD
                            && ("need_scope".equals(d.key())
                                || "preferred_branch".equals(d.key())
                                || "customer_budget".equals(d.key())
                                || "budget_note".equals(d.key())
                                || "available_time".equals(d.key())
                                || "preferred_package".equals(d.key()))) {
                        reconciledFactsDelta.set(i, new FactDeltaProposal(
                                d.key(), d.value(), FactMutationOp.REVISE, d.confidence(), d.explicitValidity()));
                        if ("need_scope".equals(d.key())) {
                            reconciledNeedRevision = true;
                        }
                    }
                }
            }

            if (explicitStop) {
                detIntent = "EXPLICIT_REFUSAL";
                convState = "PAUSED_BY_CUSTOMER";
                perm = InteractionPermission.PAUSED;
                prio = new PriorityFocus("POLITE_CLOSURE", prio.secondary());
                plan = new AdaptiveResponsePlan(
                        plan.objective(),
                        "Respectful Pause",
                        plan.tone(),
                        plan.depth(),
                        "NO_ADDITIONAL_ACTION",
                        CtaLevel.NO_CTA,
                        StrategyAdaptationMode.STOP,
                        plan.previousOutcome()
                );
                strat = new StrategyReflection(
                        "Respectful Pause",
                        StrategyAdaptationMode.STOP,
                        strat.previousOutcome(),
                        strat.evidence()
                );
            } else if (barePauseOrAck) {
                detIntent = "CUSTOMER_PAUSE_OR_ACKNOWLEDGEMENT";
                convState = "PAUSED_BY_CUSTOMER";
                costConcern = Boolean.FALSE;
                perm = InteractionPermission.CONCISE_PREFERRED;
                buySig = BuyingSignal.NONE;
                prio = new PriorityFocus("RESPECTFUL_PAUSE", List.of());
                reconciledBookingPatch = ProposedBookingPatch.empty();
                String pauseStrat = "Respectful Pause".equalsIgnoreCase(plan.strategy())
                        ? "Respectful Pause"
                        : "Warm Acknowledgment";
                plan = new AdaptiveResponsePlan(
                        "Warmly acknowledge pause without assuming permanent exit or asking trailing questions",
                        pauseStrat,
                        plan.tone(),
                        "CONCISE",
                        "NO_ADDITIONAL_ACTION",
                        CtaLevel.NO_CTA,
                        StrategyAdaptationMode.STOP,
                        plan.previousOutcome()
                );
                strat = new StrategyReflection(
                        pauseStrat,
                        StrategyAdaptationMode.STOP,
                        strat.previousOutcome(),
                        strat.evidence()
                );
            } else if (customerDeferral) {
                detIntent = "CUSTOMER_DEFERRAL";
                secIntents.removeIf(AdaptiveConversationIntelligence::isPriceOrCompetitorObjectionIntentLabel);
                convState = "PAUSED_BY_CUSTOMER";
                costConcern = Boolean.FALSE;
                perm = InteractionPermission.CONCISE_PREFERRED;
                if (buySig == BuyingSignal.STRONG || buySig == BuyingSignal.ACTION) {
                    buySig = BuyingSignal.WEAK;
                }
                prio = new PriorityFocus("POLITE_CLOSURE", List.of());
                reconciledBookingPatch = ProposedBookingPatch.empty();
                plan = new AdaptiveResponsePlan(
                        "Respect customer deferral warmly without sales push or trailing questions",
                        "Respectful Pause",
                        plan.tone(),
                        "CONCISE",
                        "NO_ADDITIONAL_ACTION",
                        CtaLevel.NO_CTA,
                        StrategyAdaptationMode.STOP,
                        plan.previousOutcome()
                );
                strat = new StrategyReflection(
                        "Respectful Pause",
                        StrategyAdaptationMode.STOP,
                        strat.previousOutcome(),
                        strat.evidence()
                );
            } else if (politeThankYouClosingAck) {
                detIntent = "POLITE_DEFERRAL";
                secIntents.removeIf(s -> s != null && s.toUpperCase(Locale.ROOT).contains("FEEDBACK"));
                convState = "PAUSED_BY_CUSTOMER";
                perm = InteractionPermission.PAUSED;
                buySig = BuyingSignal.NONE;
                prio = new PriorityFocus("POLITE_CLOSURE", List.of());
                reconciledBookingPatch = ProposedBookingPatch.empty();
                plan = new AdaptiveResponsePlan(
                        plan.objective(),
                        "Respectful Pause",
                        plan.tone(),
                        "CONCISE",
                        "NO_ADDITIONAL_ACTION",
                        CtaLevel.NO_CTA,
                        StrategyAdaptationMode.STOP,
                        plan.previousOutcome()
                );
                strat = new StrategyReflection(
                        "Respectful Pause",
                        StrategyAdaptationMode.STOP,
                        strat.previousOutcome(),
                        strat.evidence()
                );
            } else if (processFeedbackComplaint) {
                String priorIntent = detIntent;
                if (!"PROCESS_FEEDBACK".equalsIgnoreCase(detIntent)) {
                    if (priorIntent != null && !priorIntent.isBlank()
                            && !"VERIFY_BOT_IDENTITY".equalsIgnoreCase(priorIntent)
                            && secIntents.stream().noneMatch(s -> s.equalsIgnoreCase(priorIntent))) {
                        secIntents.add(priorIntent);
                    }
                    detIntent = "PROCESS_FEEDBACK";
                }
                secIntents.removeIf(s -> s != null
                        && ("PROCESS_FEEDBACK".equalsIgnoreCase(s)
                            || "VERIFY_BOT_IDENTITY".equalsIgnoreCase(s)));
                String primaryEmo = emoState != null ? emoState.primary() : "";
                if (primaryEmo == null || primaryEmo.isBlank()
                        || "UNKNOWN".equalsIgnoreCase(primaryEmo)
                        || "NEUTRAL".equalsIgnoreCase(primaryEmo)
                        || "CURIOUS".equalsIgnoreCase(primaryEmo)) {
                    emoState = new EmotionalState(
                            "FRUSTRATED",
                            emoState != null ? emoState.secondary() : List.of(),
                            0.95
                    );
                }
                convState = "FRICTION_RECOVERY";
                costConcern = Boolean.FALSE;
                readiness = BookingReadiness.EXPLORING;
                buySig = BuyingSignal.NONE;
                if (perm != InteractionPermission.PAUSED) {
                    perm = InteractionPermission.CONCISE_PREFERRED;
                }
                prio = new PriorityFocus("SERVICE_RECOVERY", List.of("TRUST_BUILDING"));
                effBarrier = "COMMUNICATION_FRICTION";
                // P3.7-B: Phân hóa 2 nhánh phục hồi — SALES_CTA luôn NONE (cta_level = NO_CTA);
                // chỉ nhánh MISSED_DISCOVERY được giữ đúng 1 DISCOVERY_QUESTION ở cuối phản hồi.
                FrictionRecoveryMode recoveryMode = resolveFrictionRecoveryMode(
                        rawUserMessage, responsePlan != null ? responsePlan.nextAction() : null);
                boolean missedDiscovery = recoveryMode == FrictionRecoveryMode.MISSED_DISCOVERY;
                if (missedDiscovery && perm == InteractionPermission.CONCISE_PREFERRED) {
                    perm = InteractionPermission.OPEN;
                }
                plan = new AdaptiveResponsePlan(
                        missedDiscovery
                                ? "Acknowledge missed discovery sincerely and ask one open listening question without any sales pitch"
                                : "Acknowledge consultation process feedback and adjust communication pace without defending AI identity",
                        "Active Listening",
                        "Sincere & Attentive",
                        "CONCISE",
                        missedDiscovery ? DISCOVERY_QUESTION_NEXT_ACTION : "NO_ADDITIONAL_ACTION",
                        CtaLevel.NO_CTA,
                        StrategyAdaptationMode.PIVOT,
                        StrategyOutcome.REJECTED
                );
                strat = new StrategyReflection(
                        "Active Listening",
                        StrategyAdaptationMode.PIVOT,
                        StrategyOutcome.REJECTED,
                        strat.evidence()
                );
                reconciledSkills.removeIf(code -> code != null
                        && code.toLowerCase(Locale.ROOT).contains("bot_identity"));
                reconciledFactsDelta.removeIf(d -> d != null
                        && d.effectiveValidity() == FactValidity.CURRENT
                        && ("need_scope".equalsIgnoreCase(d.key())
                            || "self_reported_symptom".equalsIgnoreCase(d.key())
                            || ("price_reaction".equalsIgnoreCase(d.key()) && !hasGenuinePriceObjectionCue(rawUserMessage))));
                reconciledBookingPatch = ProposedBookingPatch.empty();
            } else if (mixedComplaintWithBusinessDemand) {
                boolean explicitPrice = CleanAgentOutputRefereeAndSanitizer.hasExplicitPriceDemandInMessage(rawUserMessage);
                if (secIntents.stream().noneMatch(s -> "PROCESS_FEEDBACK".equalsIgnoreCase(s))) {
                    secIntents.add("PROCESS_FEEDBACK");
                }
                detIntent = explicitPrice ? "ASK_PRICE" : "SERVICE_OR_OPERATIONAL_INQUIRY";
                String primaryEmo = emoState != null ? emoState.primary() : "";
                if (primaryEmo == null || primaryEmo.isBlank()
                        || "UNKNOWN".equalsIgnoreCase(primaryEmo)
                        || "NEUTRAL".equalsIgnoreCase(primaryEmo)) {
                    emoState = new EmotionalState(
                            "FRUSTRATED",
                            emoState != null ? emoState.secondary() : List.of(),
                            0.90
                    );
                }
                convState = "ACTIVE_CONSULTATION";
                costConcern = Boolean.FALSE;
                if (perm == InteractionPermission.PAUSED) {
                    perm = InteractionPermission.OPEN;
                }
                prio = new PriorityFocus(explicitPrice ? "PRICE_INFORMATION" : "CLARIFICATION", List.of("TRUST_BUILDING"));
                plan = new AdaptiveResponsePlan(
                        "Acknowledge feedback briefly in one clause and directly clarify the service scope or catalog packages",
                        "Direct Answer",
                        "Sincere & Helpful",
                        "MODERATE",
                        "NO_ADDITIONAL_ACTION",
                        CtaLevel.NO_CTA,
                        StrategyAdaptationMode.PIVOT,
                        plan.previousOutcome()
                );
                strat = new StrategyReflection(
                        "Direct Answer",
                        StrategyAdaptationMode.PIVOT,
                        strat.previousOutcome(),
                        strat.evidence()
                );
                reconciledSkills.removeIf(code -> code != null
                        && code.toLowerCase(Locale.ROOT).contains("bot_identity"));
            } else if (stickyFrictionOnNonComplaintTurn) {
                if ("PROCESS_FEEDBACK".equalsIgnoreCase(detIntent)) {
                    detIntent = "SERVICE_OR_OPERATIONAL_INQUIRY";
                }
                if ("FRICTION_RECOVERY".equalsIgnoreCase(convState)) {
                    convState = "ACTIVE_CONSULTATION";
                }
                if (prio != null && "SERVICE_RECOVERY".equalsIgnoreCase(prio.primary())) {
                    prio = new PriorityFocus("CLARIFICATION", List.of("DISCOVERY"));
                }
                if (perm == InteractionPermission.PAUSED || perm == InteractionPermission.CONCISE_PREFERRED) {
                    perm = InteractionPermission.OPEN;
                }
            } else if (pureBudgetBoundedInquiry) {
                if (!"ASK_PRICE".equalsIgnoreCase(detIntent)) {
                    detIntent = "ASK_PRICE";
                }
                secIntents.removeIf(AdaptiveConversationIntelligence::isPriceOrCompetitorObjectionIntentLabel);
                if (secIntents.stream().noneMatch(s -> "BUDGET_FIT".equalsIgnoreCase(s))) {
                    secIntents.add("BUDGET_FIT");
                }
                costConcern = Boolean.FALSE;
                convState = "ACTIVE_CONSULTATION";
                effBarrier = "BUDGET_CONSTRAINT";
                if (perm == InteractionPermission.PAUSED) {
                    perm = InteractionPermission.OPEN;
                }
                prio = new PriorityFocus("BUDGET_FIT", List.of("DISCOVERY"));
                String reconciledStrat = ("Respectful Pause".equalsIgnoreCase(plan.strategy())
                        || plan.strategy().isBlank())
                        ? "Direct Answer"
                        : plan.strategy();
                plan = new AdaptiveResponsePlan(
                        plan.objective(),
                        reconciledStrat,
                        plan.tone(),
                        plan.depth(),
                        plan.nextAction(),
                        plan.ctaLevel(),
                        plan.adaptation() == StrategyAdaptationMode.STOP
                                ? StrategyAdaptationMode.CONTINUATION
                                : plan.adaptation(),
                        plan.previousOutcome()
                );
                strat = new StrategyReflection(
                        reconciledStrat,
                        strat.adaptation() == StrategyAdaptationMode.STOP
                                ? StrategyAdaptationMode.CONTINUATION
                                : strat.adaptation(),
                        strat.previousOutcome(),
                        strat.evidence()
                );
            } else if (competitorComparison) {
                if (secIntents.stream().noneMatch(AdaptiveConversationIntelligence::isPriceOrCompetitorObjectionIntentLabel)) {
                    secIntents.add("COMPETITOR_COMPARISON");
                }
                boolean alsoHasGenuineObjection = hasGenuinePriceObjectionCue(rawUserMessage);
                boolean llmMistookForExitOrPause = "POLITE_DEFERRAL".equalsIgnoreCase(detIntent)
                        || "HESITATION".equalsIgnoreCase(detIntent)
                        || isExplicitStopOrRefusalIntentLabel(detIntent)
                        || "Respectful Pause".equalsIgnoreCase(plan.strategy())
                        || plan.adaptation() == StrategyAdaptationMode.STOP;
                if ("POLITE_DEFERRAL".equalsIgnoreCase(detIntent)
                        || "HESITATION".equalsIgnoreCase(detIntent)
                        || isExplicitStopOrRefusalIntentLabel(detIntent)) {
                    if (alsoHasGenuineObjection && secIntents.stream().noneMatch(s -> s.toUpperCase(Locale.ROOT).contains("EXASPERATION"))) {
                        secIntents.add("EXASPERATION_REMARK");
                    }
                    detIntent = alsoHasGenuineObjection ? "COMPETITOR_PRICE_OBJECTION" : "COMPETITOR_COMPARISON";
                } else if (!alsoHasGenuineObjection && "PRICE_OBJECTION".equalsIgnoreCase(detIntent)) {
                    detIntent = "COMPETITOR_COMPARISON";
                }
                costConcern = alsoHasGenuineObjection ? Boolean.TRUE : Boolean.FALSE;
                convState = "OBJECTION_NEGOTIATION";
                effBarrier = "COMPETITOR_COMPARISON";
                if (perm == InteractionPermission.PAUSED) {
                    perm = InteractionPermission.OPEN;
                }
                if ("POLITE_CLOSURE".equalsIgnoreCase(prio.primary())) {
                    prio = new PriorityFocus("VALUE_ALIGNMENT", prio.secondary());
                }
                if (reconciledBookingPatch != null
                        && reconciledBookingPatch.selectedProductId() != null
                        && reconciledBookingPatch.proposedDate() == null
                        && reconciledBookingPatch.proposedTime() == null) {
                    reconciledBookingPatch = ProposedBookingPatch.empty();
                }
                String reconciledStrat = ("Respectful Pause".equalsIgnoreCase(plan.strategy()) || plan.strategy().isBlank())
                        ? "Explain Value"
                        : plan.strategy();
                StrategyAdaptationMode reconciledAdapt = plan.adaptation() == StrategyAdaptationMode.STOP
                        ? StrategyAdaptationMode.REFINEMENT
                        : plan.adaptation();
                CtaLevel reconciledCta = llmMistookForExitOrPause && plan.ctaLevel() == CtaLevel.NO_CTA
                        ? CtaLevel.SOFT_CTA
                        : (plan.ctaLevel() == CtaLevel.DIRECT_CTA ? CtaLevel.SOFT_CTA : plan.ctaLevel());
                String reconciledNext = llmMistookForExitOrPause && "NO_ADDITIONAL_ACTION".equalsIgnoreCase(plan.nextAction())
                        ? "ASK_ONE_CLARIFYING_QUESTION"
                        : plan.nextAction();
                plan = new AdaptiveResponsePlan(
                        plan.objective(),
                        reconciledStrat,
                        plan.tone(),
                        plan.depth(),
                        reconciledNext,
                        reconciledCta,
                        reconciledAdapt,
                        plan.previousOutcome()
                );
                strat = new StrategyReflection(
                        reconciledStrat,
                        reconciledAdapt,
                        strat.previousOutcome(),
                        strat.evidence()
                );
            } else if (affirmativeBookingCue) {
                if (!isBookingSignalIntentLabel(detIntent)
                        && secIntents.stream().noneMatch(AdaptiveConversationIntelligence::isBookingSignalIntentLabel)) {
                    secIntents.add("BOOKING_SIGNAL");
                }
                convState = "BOOKING_READY";
                readiness = BookingReadiness.READY_TO_BOOK;
                if (perm == InteractionPermission.PAUSED || perm == InteractionPermission.BOUNDED_INFO_ONLY) {
                    perm = InteractionPermission.OPEN;
                }
                if (buySig == BuyingSignal.NONE || buySig == BuyingSignal.WEAK) {
                    buySig = BuyingSignal.MODERATE;
                }
                String reconciledStrat = "Respectful Pause".equalsIgnoreCase(plan.strategy())
                        ? "Direct Answer"
                        : plan.strategy();
                StrategyAdaptationMode reconciledAdapt = plan.adaptation() == StrategyAdaptationMode.STOP
                        ? StrategyAdaptationMode.CONTINUATION
                        : plan.adaptation();
                CtaLevel reconciledCta = plan.ctaLevel() == CtaLevel.NO_CTA ? CtaLevel.SOFT_CTA : plan.ctaLevel();
                String reconciledNext = "NO_ADDITIONAL_ACTION".equalsIgnoreCase(plan.nextAction())
                        ? "GENTLE_SCHEDULE_CHECK"
                        : plan.nextAction();
                plan = new AdaptiveResponsePlan(
                        plan.objective(),
                        reconciledStrat,
                        plan.tone(),
                        plan.depth(),
                        reconciledNext,
                        reconciledCta,
                        reconciledAdapt,
                        plan.previousOutcome()
                );
            } else if (explicitFullPriceListRequest) {
                detIntent = "ASK_PRICE";
                convState = "ACTIVE_CONSULTATION";
                costConcern = Boolean.FALSE;
                perm = InteractionPermission.CONCISE_PREFERRED;
                prio = new PriorityFocus("PRICE_INFORMATION", List.of());
                String fullPriceStrat = ("Respectful Pause".equalsIgnoreCase(plan.strategy()) || plan.strategy().isBlank())
                        ? "Direct Answer"
                        : plan.strategy();
                CtaLevel fullPriceCta = plan.ctaLevel() == CtaLevel.DIRECT_CTA ? CtaLevel.SOFT_CTA : plan.ctaLevel();
                plan = new AdaptiveResponsePlan(
                        plan.objective() != null && !plan.objective().isBlank()
                                ? plan.objective()
                                : "Provide complete grounded catalog price list concisely as requested",
                        fullPriceStrat,
                        plan.tone(),
                        "CONCISE",
                        plan.nextAction() != null && !plan.nextAction().isBlank()
                                ? plan.nextAction()
                                : "ASK_ONE_CLARIFYING_QUESTION",
                        fullPriceCta,
                        plan.adaptation() == StrategyAdaptationMode.STOP
                                ? StrategyAdaptationMode.CONTINUATION
                                : plan.adaptation(),
                        plan.previousOutcome()
                );
                strat = new StrategyReflection(
                        fullPriceStrat,
                        strat.adaptation() == StrategyAdaptationMode.STOP
                                ? StrategyAdaptationMode.CONTINUATION
                                : strat.adaptation(),
                        strat.previousOutcome(),
                        strat.evidence()
                );
            } else if (explicitGenderCorrection) {
                String selfPronounTarget = inferExplicitCustomerSelfPronounFromUtterance(rawUserMessage);
                if (selfPronounTarget != null && !selfPronounTarget.isBlank()) {
                    reconciledInferredPronoun = selfPronounTarget;
                    reconciledPronounConf = 0.99;
                    reconciledFactsDelta.add(new FactDeltaProposal(
                            "customer_gender_pronoun",
                            selfPronounTarget,
                            FactMutationOp.REVISE,
                            "HIGH"
                    ));
                }
                detIntent = "SELF_IDENTIFY_GENDER";
                secIntents.removeIf(AdaptiveConversationIntelligence::isPriceOrCompetitorObjectionIntentLabel);
                convState = "ACTIVE_CONSULTATION";
                costConcern = Boolean.FALSE;
                effBarrier = "NONE";
                if (perm == InteractionPermission.PAUSED) {
                    perm = InteractionPermission.OPEN;
                }
                prio = new PriorityFocus("TRUST_ASSURANCE", List.of());
                reconciledSkills.clear();
                reconciledBookingPatch = ProposedBookingPatch.empty();
                plan = new AdaptiveResponsePlan(
                        "Acknowledge customer gender/pronoun correction warmly and switch pronoun immediately without sales push",
                        "Warm Acknowledgment",
                        plan.tone(),
                        "CONCISE",
                        "NO_ADDITIONAL_ACTION",
                        CtaLevel.NO_CTA,
                        StrategyAdaptationMode.PIVOT,
                        plan.previousOutcome()
                );
                strat = new StrategyReflection(
                        "Warm Acknowledgment",
                        StrategyAdaptationMode.PIVOT,
                        strat.previousOutcome(),
                        strat.evidence()
                );
            } else if (explicitHumanEscalation) {
                detIntent = "HUMAN_HANDOFF";
                convState = "ACTIVE_CONSULTATION";
                costConcern = Boolean.FALSE;
                prio = new PriorityFocus("SERVICE_RECOVERY", List.of("TRUST_ASSURANCE"));
                reconciledSkills.clear();
                reconciledBookingPatch = ProposedBookingPatch.empty();
                reconciledHandoff = new HandoffRequestReflection(
                        true,
                        reconciledHandoff != null && reconciledHandoff.reason() != null && !reconciledHandoff.reason().isBlank()
                                ? reconciledHandoff.reason()
                                : "Khách hàng chủ động yêu cầu gặp nhân viên hỗ trợ trực tiếp."
                );
                plan = new AdaptiveResponsePlan(
                        "Connect customer with human staff politely as requested",
                        "Transaction Handoff",
                        plan.tone(),
                        "CONCISE",
                        "NO_ADDITIONAL_ACTION",
                        CtaLevel.TRANSACTION_HANDOFF,
                        StrategyAdaptationMode.PRIORITY_SHIFT,
                        plan.previousOutcome()
                );
                strat = new StrategyReflection(
                        "Transaction Handoff",
                        StrategyAdaptationMode.PRIORITY_SHIFT,
                        strat.previousOutcome(),
                        strat.evidence()
                );
            } else if (firstGenuinePriceObjection) {
                costConcern = Boolean.TRUE;
                boolean alsoAsksPackageComparison = hasPackageComparisonCue(rawUserMessage);
                if (alsoAsksPackageComparison
                        && secIntents.stream().noneMatch(s -> "PACKAGE_COMPARISON".equalsIgnoreCase(s))) {
                    secIntents.add("PACKAGE_COMPARISON");
                }
                if (detIntent == null || detIntent.isBlank()
                        || "CUSTOMER_PAUSE_OR_ACKNOWLEDGEMENT".equalsIgnoreCase(detIntent)
                        || "POLITE_DEFERRAL".equalsIgnoreCase(detIntent)
                        || "NEED_DISCOVERY".equalsIgnoreCase(detIntent)
                        || "PACKAGE_COMPARISON".equalsIgnoreCase(detIntent)) {
                    detIntent = "PRICE_OBJECTION";
                    convState = "OBJECTION_NEGOTIATION";
                }
                // P3.3 TC06: Khi khách phản đối giá ("Giá 399k cao quá"), TUYỆT ĐỐI KHÔNG cho phép lưu mức giá bị chê
                // thành customer_budget / budget_note hoặc khóa gói thành selected_product_id!
                Set<Integer> targetBudgetAmountsK = extractNormalizedAmountsInThousands(explicitTargetBudget);
                List<FactDeltaProposal> cleanedObjectionFacts = new ArrayList<>();
                boolean hasPriceReactionFact = false;
                for (FactDeltaProposal d : reconciledFactsDelta) {
                    if (d == null) continue;
                    if ("price_reaction".equals(d.key())) {
                        hasPriceReactionFact = true;
                        cleanedObjectionFacts.add(d);
                        continue;
                    }
                    if ("customer_budget".equals(d.key()) || "budget_note".equals(d.key())) {
                        Set<Integer> deltaAmountsK = extractNormalizedAmountsInThousands(d.value());
                        boolean matchesExplicitTargetBudget = explicitTargetBudget != null
                                && !targetBudgetAmountsK.isEmpty()
                                && targetBudgetAmountsK.equals(deltaAmountsK);
                        if (!matchesExplicitTargetBudget) {
                            hasPriceReactionFact = true;
                            cleanedObjectionFacts.add(new FactDeltaProposal(
                                    "price_reaction",
                                    "PRICE_OBJECTION: khách phản ứng giá " + d.value() + " cao",
                                    FactMutationOp.ADD,
                                    "HIGH"
                            ));
                            continue;
                        }
                    }
                    cleanedObjectionFacts.add(d);
                }
                if (!hasPriceReactionFact) {
                    Set<Integer> objectedAmountsK = extractNormalizedAmountsInThousands(rawUserMessage);
                    objectedAmountsK.removeAll(targetBudgetAmountsK);
                    String priceReactionVal = !objectedAmountsK.isEmpty()
                            ? ("PRICE_OBJECTION: khách phản ứng giá " + objectedAmountsK.iterator().next() + "k cao")
                            : "PRICE_OBJECTION: khách phản ứng giá đắt";
                    cleanedObjectionFacts.add(new FactDeltaProposal(
                            "price_reaction",
                            priceReactionVal,
                            FactMutationOp.ADD,
                            "HIGH"
                    ));
                }
                reconciledFactsDelta = cleanedObjectionFacts;
                if (reconciledBookingPatch != null
                        && reconciledBookingPatch.selectedProductId() != null
                        && reconciledBookingPatch.proposedDate() == null
                        && reconciledBookingPatch.proposedTime() == null) {
                    reconciledBookingPatch = ProposedBookingPatch.empty();
                }

        boolean explicitBudgetConstraintCue = normLower.matches(
                "(?isU).*\\b(không\\s+đủ\\s+tiền|chưa\\s+đủ\\s+tiền|vượt\\s+ngân\\s+sách|quá\\s+ngân\\s+sách|quá\\s+sức|đau\\s+ví|ví\\s+mỏng|rẻ\\s+hơn|mềm\\s+hơn|nhẹ\\s+hơn|nhẹ\\s+tiền\\s+hơn|tiết\\s+kiệm\\s+hơn|bằng\\s+cả\\s+ngày|ngày\\s+công)\\b.*");
                if (!explicitBudgetConstraintCue
                        && (effBarrier == null || effBarrier.isBlank()
                            || "NONE".equalsIgnoreCase(effBarrier))) {
                    effBarrier = "VALUE_PERCEPTION_GAP";
                }
                if ("GENTLE_SCHEDULE_CHECK".equalsIgnoreCase(plan.nextAction())
                        || plan.ctaLevel() == CtaLevel.DIRECT_CTA) {
                    String safeObjStrat = (plan.strategy() != null && !plan.strategy().isBlank()
                            && !"Respectful Pause".equalsIgnoreCase(plan.strategy()))
                            ? plan.strategy()
                            : "Handle Objection";
                    String safeObjNext = "GENTLE_SCHEDULE_CHECK".equalsIgnoreCase(plan.nextAction())
                            ? "SOFT_FEELING_CHECK"
                            : plan.nextAction();
                    CtaLevel safeObjCta = plan.ctaLevel() == CtaLevel.DIRECT_CTA
                            ? CtaLevel.SOFT_CTA
                            : plan.ctaLevel();
                    plan = new AdaptiveResponsePlan(
                            plan.objective(),
                            safeObjStrat,
                            plan.tone(),
                            plan.depth(),
                            safeObjNext,
                            safeObjCta,
                            plan.adaptation(),
                            plan.previousOutcome()
                    );
                }
                // Do not auto-truncate verifiedSymptomDuration here; let AgentBrainService sanitize duration tokens if needed
            }

            CustomerUnderstanding reconciledCu = new CustomerUnderstanding(
                    detIntent,
                    emoState,
                    reconciledInferredPronoun,
                    reconciledPronounConf,
                    costConcern,
                    cu.verifiedSymptomDuration(),
                    readiness,
                    cu.communicationStylePreference(),
                    perm,
                    buySig,
                    secIntents,
                    convState
            );
            return new AdaptiveCognitiveReflection(
                    reconciledCu,
                    prio,
                    workingHypothesis,
                    strat,
                    uncertainties,
                    customerStatements,
                    commitmentDelta,
                    reconciledHandoff,
                    reconciledBookingPatch,
                    blockedUnauthorizedCompletionCount,
                    parsedFromV2Schema,
                    reconciledSkills,
                    explicitActivatedSkillCodes,
                    currentTopic,
                    unresolvedRequestsDelta,
                    pendingReferences,
                    conversationGoal,
                    reconciledNeedRevision,
                    reconciledFactsDelta,
                    effBarrier,
                    hypothesisConfidenceLevel,
                    reconciledInterestedPkgId,
                    plan
            );
        }

        public AdaptiveCognitiveReflection(
                CustomerUnderstanding customerUnderstanding,
                PriorityFocus priority,
                WorkingHypothesisReflection workingHypothesis,
                StrategyReflection strategy,
                List<String> uncertainties,
                List<String> customerStatements,
                List<CommitmentDeltaItem> commitmentDelta,
                HandoffRequestReflection handoffRequest,
                ProposedBookingPatch proposedBookingPatch,
                int blockedUnauthorizedCompletionCount,
                boolean parsedFromV2Schema,
                List<String> activatedSkillCodes,
                boolean explicitActivatedSkillCodes
        ) {
            this(
                    customerUnderstanding,
                    priority,
                    workingHypothesis,
                    strategy,
                    uncertainties,
                    customerStatements,
                    commitmentDelta,
                    handoffRequest,
                    proposedBookingPatch,
                    blockedUnauthorizedCompletionCount,
                    parsedFromV2Schema,
                    activatedSkillCodes,
                    explicitActivatedSkillCodes,
                    null,
                    List.of(),
                    List.of(),
                    null,
                    false,
                    List.of(),
                    null,
                    null,
                    null,
                    AdaptiveResponsePlan.empty()
            );
        }

        public AdaptiveCognitiveReflection(
                CustomerUnderstanding customerUnderstanding,
                PriorityFocus priority,
                WorkingHypothesisReflection workingHypothesis,
                StrategyReflection strategy,
                List<String> uncertainties,
                List<String> customerStatements,
                List<CommitmentDeltaItem> commitmentDelta,
                HandoffRequestReflection handoffRequest,
                ProposedBookingPatch proposedBookingPatch,
                int blockedUnauthorizedCompletionCount,
                boolean parsedFromV2Schema
        ) {
            this(
                    customerUnderstanding,
                    priority,
                    workingHypothesis,
                    strategy,
                    uncertainties,
                    customerStatements,
                    commitmentDelta,
                    handoffRequest,
                    proposedBookingPatch,
                    blockedUnauthorizedCompletionCount,
                    parsedFromV2Schema,
                    List.of(),
                    false
            );
        }

        public static AdaptiveCognitiveReflection empty() {
            return new AdaptiveCognitiveReflection(
                    CustomerUnderstanding.empty(),
                    PriorityFocus.empty(),
                    WorkingHypothesisReflection.empty(),
                    StrategyReflection.empty(),
                    List.of(),
                    List.of(),
                    List.of(),
                    HandoffRequestReflection.none(),
                    ProposedBookingPatch.empty(),
                    0,
                    false,
                    List.of(),
                    false
            );
        }
    }

    private static String cleanGoalField(String rawGoal) {
        String c = cleanNullable(rawGoal);
        if (c == null) {
            return null;
        }
        String u = c.toUpperCase(Locale.ROOT);
        if ("KEEP".equals(u) || "KEEP_CURRENT".equals(u) || "UNCHANGED".equals(u) || "SAME".equals(u)) {
            return null;
        }
        return c;
    }

    /**
     * Null-safe &amp; Default-tolerant parser cho {@code brain_reflection} theo Schema Chuẩn Hóa Duy Nhất
     * (hỗ trợ đồng thời schema V2 tiêu chuẩn + {@code Customer Understanding Model (Draft 2)} &amp; {@code Adaptive Response Planner}).
     */
    public static AdaptiveCognitiveReflection parseAndReconcileAdaptiveCognitiveReflection(JsonNode brainNode, String rawUserMessage) {
        return parseAdaptiveCognitiveReflection(brainNode, null)
                .reconcileWithCurrentUtterance(rawUserMessage);
    }

    public static AdaptiveCognitiveReflection parseAdaptiveCognitiveReflection(JsonNode brainNode, JsonNode rootNode) {
        if ((brainNode == null || !brainNode.isObject()) && (rootNode == null || !rootNode.isObject())) {
            return AdaptiveCognitiveReflection.empty();
        }
        JsonNode b = (brainNode != null && brainNode.isObject()) ? brainNode : rootNode;
        boolean hasV2Markers = b.has("customer_understanding")
                || b.has("conversational_context")
                || b.has("response_plan")
                || b.has("priority")
                || b.has("strategy")
                || b.has("commitment_delta")
                || b.has("handoff_request")
                || b.has("proposed_booking_patch")
                || b.has("activated_skill_codes")
                || (b.has("working_hypothesis") && b.get("working_hypothesis").isObject());

        // 0. Tầng 1: conversational_context (current_topic, unresolved_requests, pending_references)
        String currentTopic = null;
        List<UnresolvedRequestItem> unresolvedRequests = new ArrayList<>();
        List<String> pendingReferences = new ArrayList<>();
        JsonNode ccNode = b.get("conversational_context");
        if (ccNode != null && ccNode.isObject()) {
            currentTopic = cleanNullable(readTextOrDefault(ccNode, "current_topic", null));
            pendingReferences.addAll(readStringList(ccNode.get("pending_references")));
            JsonNode urArr = ccNode.get("unresolved_requests");
            if (urArr != null && urArr.isArray()) {
                for (JsonNode urEl : urArr) {
                    if (urEl == null || urEl.isNull()) continue;
                    if (urEl.isObject()) {
                        String item = readTextOrDefault(urEl, "request",
                                readTextOrDefault(urEl, "item", readTextOrDefault(urEl, "content", "")));
                        if (!item.isBlank()) {
                            UnresolvedRequestStatus st = UnresolvedRequestStatus.fromString(
                                    readTextOrDefault(urEl, "status", "OPEN"));
                            unresolvedRequests.add(new UnresolvedRequestItem(item, st, "T_CURRENT", "T_CURRENT"));
                        }
                    } else if (urEl.isTextual() && !urEl.asText().isBlank()) {
                        unresolvedRequests.add(new UnresolvedRequestItem(
                                urEl.asText().strip(), UnresolvedRequestStatus.OPEN, "T_CURRENT", "T_CURRENT"));
                    }
                }
            }
        }
        if (currentTopic == null) {
            currentTopic = cleanNullable(readTextOrDefault(b, "current_topic", null));
        }

        // 1. Tầng 2: customer_understanding / consultative_profile (hỗ trợ primary_intent, secondary_intents, conversation_state)
        JsonNode cuNode = b.has("customer_understanding") ? b.get("customer_understanding") : b.get("consultative_profile");
        if (unresolvedRequests.isEmpty()) {
            JsonNode fallbackUrArr = b.has("unresolved_requests_delta")
                    ? b.get("unresolved_requests_delta")
                    : (b.has("unresolved_requests")
                        ? b.get("unresolved_requests")
                        : (cuNode != null && cuNode.has("unresolved_requests_delta")
                            ? cuNode.get("unresolved_requests_delta")
                            : (cuNode != null ? cuNode.get("unresolved_requests") : null)));
            if (fallbackUrArr != null && fallbackUrArr.isArray()) {
                for (JsonNode urEl : fallbackUrArr) {
                    if (urEl == null || urEl.isNull()) continue;
                    if (urEl.isObject()) {
                        String item = readTextOrDefault(urEl, "request",
                                readTextOrDefault(urEl, "item", readTextOrDefault(urEl, "content", "")));
                        if (!item.isBlank()) {
                            UnresolvedRequestStatus st = UnresolvedRequestStatus.fromString(
                                    readTextOrDefault(urEl, "status", "OPEN"));
                            unresolvedRequests.add(new UnresolvedRequestItem(item, st, "T_CURRENT", "T_CURRENT"));
                        }
                    } else if (urEl.isTextual() && !urEl.asText().isBlank()) {
                        unresolvedRequests.add(new UnresolvedRequestItem(
                                urEl.asText().strip(), UnresolvedRequestStatus.OPEN, "T_CURRENT", "T_CURRENT"));
                    }
                }
            }
        }
        String detectedIntent = "";
        List<String> secondaryIntents = new ArrayList<>();
        String rawConversationState = null;
        String conversationGoal = null;
        boolean needRevisionDetected = false;
        List<FactDeltaProposal> confirmedFactsDelta = new ArrayList<>();
        String interestedPackageId = null;
        EmotionalState emotionalState = EmotionalState.empty();
        String inferredPronoun = null;
        double pronounConfidence = 0.0;
        Boolean costConcernExpressed = null;
        String verifiedSymptomDuration = null;
        BookingReadiness bookingReadiness = null;
        String communicationStylePreference = null;
        InteractionPermission interactionPermission = null;
        BuyingSignal buyingSignal = BuyingSignal.NONE;
        if (cuNode != null && cuNode.isObject()) {
            detectedIntent = readTextOrDefault(cuNode, "primary_intent",
                    readTextOrDefault(cuNode, "turn_intent",
                            readTextOrDefault(cuNode, "detected_intent", "")));
            if (detectedIntent.isBlank()) {
                detectedIntent = readTextOrDefault(cuNode, "turn_intent",
                        readTextOrDefault(cuNode, "detected_intent", ""));
            }
            secondaryIntents.addAll(readStringList(cuNode.has("secondary_intents")
                    ? cuNode.get("secondary_intents")
                    : b.get("secondary_intents")));
            rawConversationState = cleanNullable(readTextOrDefault(cuNode, "conversation_state",
                    readTextOrDefault(b, "conversation_state", null)));
            conversationGoal = cleanGoalField(readTextOrDefault(cuNode, "conversation_goal", null));
            if (cuNode.has("need_revision") && !cuNode.get("need_revision").isNull()) {
                needRevisionDetected = cuNode.get("need_revision").asBoolean(false);
            }
            JsonNode factsNode = cuNode.has("confirmed_facts_delta")
                    ? cuNode.get("confirmed_facts_delta")
                    : (cuNode.has("fact_deltas")
                        ? cuNode.get("fact_deltas")
                        : (cuNode.has("confirmed_facts") ? cuNode.get("confirmed_facts") : b.get("confirmed_facts_delta")));
            parseFactDeltaProposals(factsNode, confirmedFactsDelta, needRevisionDetected);
            // Tương thích ngược cho test cũ vẫn truyền mảng self_reported_symptoms rời (hỗ trợ cả chuỗi lẫn object {"value":..., "validity":...})
            JsonNode symNode = cuNode.get("self_reported_symptoms");
            if (symNode != null && symNode.isArray()) {
                for (JsonNode symEl : symNode) {
                    if (symEl == null || symEl.isNull()) continue;
                    if (symEl.isObject()) {
                        String symVal = readTextOrDefault(symEl, "value",
                                readTextOrDefault(symEl, "symptom",
                                        readTextOrDefault(symEl, "symptom_name", "")));
                        FactValidity symValidity = FactValidity.parseExplicit(
                                readTextOrDefault(symEl, "validity", readTextOrDefault(symEl, "status", null)));
                        String symOpRaw = readTextOrDefault(symEl, "operation", readTextOrDefault(symEl, "op", null));
                        FactMutationOp symOp = symOpRaw != null && !symOpRaw.isBlank()
                                ? FactMutationOp.fromString(symOpRaw)
                                : FactMutationOp.ADD;
                        String symConf = readTextOrDefault(symEl, "confidence", "HIGH");
                        appendFactDeltaWithAtomicSymptomSplit(
                                confirmedFactsDelta, "self_reported_symptom", symVal, symOp, symConf, symValidity);
                    } else if (symEl.isTextual()) {
                        appendFactDeltaWithAtomicSymptomSplit(
                                confirmedFactsDelta, "self_reported_symptom", symEl.asText(), FactMutationOp.ADD, "HIGH", null);
                    }
                }
            } else if (symNode != null) {
                for (String sym : readStringList(symNode)) {
                    appendFactDeltaWithAtomicSymptomSplit(
                            confirmedFactsDelta, "self_reported_symptom", sym, FactMutationOp.ADD, "HIGH", null);
                }
            }
            interestedPackageId = cleanNullable(readTextOrDefault(cuNode, "interested_package",
                    readTextOrDefault(cuNode, "interested_package_id", null)));

            JsonNode emoNode = cuNode.has("emotional_state") ? cuNode.get("emotional_state") : cuNode.get("emotion");
            if (emoNode != null && emoNode.isObject()) {
                String emoPrimary = readTextOrDefault(emoNode, "primary", "UNKNOWN");
                List<String> emoSecondary = readStringList(emoNode.get("secondary"));
                double emoConf = readDoubleOrDefault(emoNode, "confidence", 0.0);
                emotionalState = new EmotionalState(emoPrimary, emoSecondary, emoConf);
            } else if (emoNode != null && emoNode.isTextual()) {
                emotionalState = new EmotionalState(emoNode.asText(), List.of(), 0.75);
            }
            inferredPronoun = readTextOrDefault(cuNode, "inferred_customer_pronoun", null);
            pronounConfidence = readDoubleOrDefault(cuNode, "pronoun_confidence", 0.0);
            if (cuNode.has("cost_concern_expressed") && !cuNode.get("cost_concern_expressed").isNull()) {
                costConcernExpressed = cuNode.get("cost_concern_expressed").asBoolean(false);
            }
            verifiedSymptomDuration = readTextOrDefault(cuNode, "verified_symptom_duration", null);
            bookingReadiness = BookingReadiness.fromString(
                    readTextOrDefault(cuNode, "booking_readiness", readTextOrDefault(b, "booking_readiness", null)));
            communicationStylePreference = normalizeCommunicationStylePreference(
                    readTextOrDefault(cuNode, "communication_preference",
                            readTextOrDefault(cuNode, "communication_style_preference",
                                    readTextOrDefault(b, "communication_style_preference", null))));
            interactionPermission = InteractionPermission.parseExplicit(
                    readTextOrDefault(cuNode, "interaction_permission",
                            readTextOrDefault(b, "interaction_permission", null)));
            buyingSignal = BuyingSignal.fromString(
                    readTextOrDefault(cuNode, "buying_signal",
                            readTextOrDefault(b, "buying_signal", null)));
        } else {
            detectedIntent = readTextOrDefault(b, "primary_intent",
                    readTextOrDefault(b, "turn_intent", readTextOrDefault(b, "detected_customer_intent", "")));
            secondaryIntents.addAll(readStringList(b.get("secondary_intents")));
            rawConversationState = cleanNullable(readTextOrDefault(b, "conversation_state", null));
            bookingReadiness = BookingReadiness.fromString(readTextOrDefault(b, "booking_readiness", null));
            communicationStylePreference = normalizeCommunicationStylePreference(
                    readTextOrDefault(b, "communication_style_preference", null));
            interactionPermission = InteractionPermission.parseExplicit(
                    readTextOrDefault(b, "interaction_permission", null));
            buyingSignal = BuyingSignal.fromString(readTextOrDefault(b, "buying_signal", null));
        }
        if (buyingSignal == BuyingSignal.NONE && bookingReadiness == BookingReadiness.READY_TO_BOOK) {
            buyingSignal = BuyingSignal.STRONG;
        }
        if (conversationGoal == null) {
            conversationGoal = cleanGoalField(readTextOrDefault(b, "conversation_goal", null));
        }

        // 2. priority
        JsonNode prioNode = b.get("priority");
        PriorityFocus priority = PriorityFocus.empty();
        if (prioNode != null && prioNode.isObject()) {
            priority = new PriorityFocus(
                    readTextOrDefault(prioNode, "primary", "CLARIFICATION"),
                    readStringList(prioNode.get("secondary"))
            );
        } else if (prioNode != null && prioNode.isTextual()) {
            priority = new PriorityFocus(prioNode.asText(), List.of());
        }

        // 3. working_hypothesis / barriers_and_hypotheses
        JsonNode whNode = b.has("working_hypothesis") ? b.get("working_hypothesis") : b.get("barriers_and_hypotheses");
        WorkingHypothesisReflection workingHyp = WorkingHypothesisReflection.empty();
        String barrierType = null;
        String hypothesisConfLevel = null;
        if (whNode != null && whNode.isObject()) {
            String hypText = readTextOrDefault(whNode, "hypothesis", readTextOrDefault(whNode, "content", ""));
            String defaultLifeOp = hypText.isBlank() ? "MAINTAIN" : "ADJUST";
            String rawLifeOp = readTextOrDefault(whNode, "lifecycle_op", readTextOrDefault(whNode, "operation", defaultLifeOp));
            barrierType = readTextOrDefault(whNode, "barrier_type", null);
            hypothesisConfLevel = readTextOrDefault(whNode, "confidence_level", null);
            double confNum = readDoubleOrDefault(whNode, "confidence", -1.0);
            if (confNum < 0.0) {
                confNum = "HIGH".equalsIgnoreCase(hypothesisConfLevel) ? 0.85
                        : ("LOW".equalsIgnoreCase(hypothesisConfLevel) ? 0.35 : 0.72);
            }
            List<String> hypEvidence = whNode.has("evidence_turn_ids")
                    ? readStringList(whNode.get("evidence_turn_ids"))
                    : readStringList(whNode.get("evidence"));
            workingHyp = new WorkingHypothesisReflection(
                    hypText,
                    HypothesisLifecycleOp.fromString(rawLifeOp),
                    confNum,
                    hypEvidence
            );
        } else if (whNode != null && whNode.isTextual()) {
            String txt = whNode.asText("").strip();
            workingHyp = new WorkingHypothesisReflection(
                    txt,
                    txt.isEmpty() ? HypothesisLifecycleOp.MAINTAIN : HypothesisLifecycleOp.ADJUST,
                    txt.isEmpty() ? 0.0 : 0.70,
                    List.of()
            );
        }

        // 4. Hợp nhất Adaptive Response Planner (response_plan gộp adaptation, previous_outcome, cta_level; vẫn tương thích ngược với "strategy" rời)
        JsonNode rpNode = b.get("response_plan");
        JsonNode stratNode = b.get("strategy");
        boolean hasResponsePlan = rpNode != null && rpNode.isObject();
        boolean hasSeparateStrategy = stratNode != null && stratNode.isObject();

        String planObjective = hasResponsePlan ? readTextOrDefault(rpNode, "objective", "") : detectedIntent;
        String rawPlanStrategy = hasResponsePlan ? readTextOrDefault(rpNode, "strategy", "") : "";
        if (rawPlanStrategy.isBlank() && hasSeparateStrategy) {
            rawPlanStrategy = readTextOrDefault(stratNode, "next_strategy", readTextOrDefault(stratNode, "chosen", ""));
        }
        if (rawPlanStrategy.isBlank()) {
            rawPlanStrategy = readTextOrDefault(b, "next_strategy", readTextOrDefault(b, "intended_speech_act", ""));
        }
        String normalizedStrategy = normalizePlannerStrategy(rawPlanStrategy.isBlank() ? "Direct Answer" : rawPlanStrategy);
        if (planObjective.isBlank()) {
            planObjective = !detectedIntent.isBlank() ? detectedIntent : normalizedStrategy;
        }
        String planTone = hasResponsePlan ? readTextOrDefault(rpNode, "tone", "LEVEL_1_NATURAL") : "LEVEL_1_NATURAL";
        String defaultDepth = communicationStylePreference != null ? communicationStylePreference : "STANDARD";
        String planDepth = hasResponsePlan ? readTextOrDefault(rpNode, "depth", defaultDepth) : defaultDepth;

        String rawAdaptation = null;
        if (hasResponsePlan && rpNode.has("adaptation") && !rpNode.get("adaptation").isNull()) {
            rawAdaptation = readTextOrDefault(rpNode, "adaptation", null);
        } else if (hasSeparateStrategy && stratNode.has("adaptation") && !stratNode.get("adaptation").isNull()) {
            rawAdaptation = readTextOrDefault(stratNode, "adaptation", null);
        }
        StrategyAdaptationMode planAdaptation;
        if (rawAdaptation != null && !rawAdaptation.isBlank()) {
            planAdaptation = StrategyAdaptationMode.fromString(rawAdaptation);
        } else if ("Respectful Pause".equalsIgnoreCase(normalizedStrategy)) {
            planAdaptation = StrategyAdaptationMode.STOP;
        } else if (needRevisionDetected) {
            planAdaptation = StrategyAdaptationMode.PIVOT;
        } else {
            planAdaptation = StrategyAdaptationMode.CONTINUATION;
        }

        String rawPrevOutcome = null;
        if (hasResponsePlan && rpNode.has("previous_outcome") && !rpNode.get("previous_outcome").isNull()) {
            rawPrevOutcome = readTextOrDefault(rpNode, "previous_outcome", null);
        } else if (hasSeparateStrategy && stratNode.has("previous_outcome") && !stratNode.get("previous_outcome").isNull()) {
            rawPrevOutcome = readTextOrDefault(stratNode, "previous_outcome", null);
        }
        StrategyOutcome planPrevOutcome = StrategyOutcome.fromString(
                rawPrevOutcome != null ? rawPrevOutcome : "NOT_APPLICABLE");

        String defaultNextAction = planAdaptation == StrategyAdaptationMode.STOP
                ? "NO_ADDITIONAL_ACTION"
                : (hasResponsePlan ? "NO_ADDITIONAL_ACTION" : "SOFT_FEELING_CHECK");
        String planNextAction = hasResponsePlan
                ? normalizePlannerNextAction(readTextOrDefault(rpNode, "next_action", defaultNextAction))
                : defaultNextAction;
        String rawCtaLevel = hasResponsePlan ? readTextOrDefault(rpNode, "cta_level", null) : null;
        CtaLevel planCtaLevel = CtaLevel.fromString(rawCtaLevel, normalizedStrategy, planNextAction);

        List<String> stratEvidence = List.of();
        if (hasResponsePlan && rpNode.has("evidence_turn_ids")) {
            stratEvidence = readStringList(rpNode.get("evidence_turn_ids"));
        } else if (hasSeparateStrategy) {
            stratEvidence = stratNode.has("evidence_turn_ids")
                    ? readStringList(stratNode.get("evidence_turn_ids"))
                    : readStringList(stratNode.get("evidence"));
        } else if (hasResponsePlan) {
            stratEvidence = List.of("T_CURRENT");
        }

        // 5. uncertainties & customer_statements
        List<String> uncertainties = readStringList(b.get("uncertainties"));
        List<String> customerStatements = readFlexibleStatementsList(b.get("customer_statements"));

        // 6. commitment_delta (strictly blocks COMPLETED from LLM!)
        List<CommitmentDeltaItem> commitments = new ArrayList<>();
        int blockedCompletions = 0;
        JsonNode cdNode = b.get("commitment_delta");
        if (cdNode != null && cdNode.isArray()) {
            for (JsonNode item : cdNode) {
                if (item == null || !item.isObject()) continue;
                String rawOp = readTextOrDefault(item, "operation", "");
                CommitmentOperation parsedOp = CommitmentOperation.parseAllowedFromLlm(rawOp);
                if (parsedOp == null) {
                    if (!rawOp.isBlank()) {
                        blockedCompletions++;
                    }
                    continue;
                }
                String content = readTextOrDefault(item, "content", "");
                if (content.isBlank() && parsedOp == CommitmentOperation.CREATE) {
                    continue;
                }
                CommitmentOwner owner = CommitmentOwner.fromString(readTextOrDefault(item, "owner", "STAFF"));
                String timeframe = cleanNullable(readTextOrDefault(
                        item, "verified_timeframe", readTextOrDefault(item, "timeframe", null)));
                commitments.add(new CommitmentDeltaItem(parsedOp, content, owner, timeframe));
            }
        }

        // 7. handoff_request (consolidated single object, fallback to root should_handoff if absent)
        HandoffRequestReflection handoffReq = HandoffRequestReflection.none();
        JsonNode hrNode = b.get("handoff_request");
        if (hrNode != null && hrNode.isObject()) {
            boolean req = (hrNode.has("requested") && !hrNode.get("requested").isNull() && hrNode.get("requested").asBoolean(false))
                    || (hrNode.has("needed") && !hrNode.get("needed").isNull() && hrNode.get("needed").asBoolean(false));
            String reason = cleanNullable(readTextOrDefault(hrNode, "reason", null));
            handoffReq = new HandoffRequestReflection(req, reason);
        } else if (rootNode != null && rootNode.has("should_handoff") && rootNode.get("should_handoff").asBoolean(false)) {
            handoffReq = new HandoffRequestReflection(true, cleanNullable(readTextOrDefault(rootNode, "handoff_reason", null)));
        }

        // Multi-Intent & Conversation State Reconciliation (P3.6 — P1):
        // Kiểm tra mối quan hệ giữa primary_intent, secondary_intents, barrier_hypothesis, confirmed_facts_delta và conversation_state:
        // - Case 3 (Từ chối dừng tư vấn rõ ràng): EXPLICIT_REFUSAL / STOP_CONSULTATION / POLITE_CLOSURE -> PAUSED_BY_CUSTOMER + Respectful Pause + NO_CTA + STOP.
        // - Case 2 (So sánh giá bên ngoài / phản đối giá trị khi chưa từ chối dừng tư vấn): COMPETITOR_COMPARISON + cost_concern / price_reaction
        //   -> không chọn nhầm POLITE_DEFERRAL / Respectful Pause / STOP; chuyển sang OBJECTION_NEGOTIATION + Explain Value.
        // - Case 1 (Câu phức hợp vừa hỏi nguyên tắc/trêu đùa vừa có tín hiệu đặt lịch ở secondary_intents):
        //   -> không khóa vào BOUNDED_INFO_ONLY / STOP mà giữ OPEN + đón tín hiệu đặt lịch (BOOKING_READY).
        String effectiveBarrierType = normalizeBarrierType(barrierType, workingHyp.hypothesis());
        boolean secondaryHasBooking = secondaryIntents.stream()
                .anyMatch(AdaptiveConversationIntelligence::isBookingSignalIntentLabel);
        boolean secondaryHasPriceOrCompetitor = secondaryIntents.stream()
                .anyMatch(AdaptiveConversationIntelligence::isPriceOrCompetitorObjectionIntentLabel);
        boolean hasCurrentTurnPriceComparisonDelta = confirmedFactsDelta.stream()
                .anyMatch(d -> d != null && "price_reaction".equals(d.key())
                        && d.effectiveValidity() == FactValidity.CURRENT);
        boolean isExplicitStopRefusal = isExplicitStopOrRefusalIntentLabel(detectedIntent);
        boolean isSecurityRefusalIntent = detectedIntent.toUpperCase(Locale.ROOT).contains("PROMPT_INJECTION")
                || detectedIntent.toUpperCase(Locale.ROOT).contains("JAILBREAK");
        boolean currentTurnRaisesCompetitorOrPriceObjection = !isExplicitStopRefusal && (
                isPriceOrCompetitorObjectionIntentLabel(detectedIntent)
                || secondaryHasPriceOrCompetitor
                || ("COMPETITOR_COMPARISON".equalsIgnoreCase(effectiveBarrierType)
                    && (Boolean.TRUE.equals(costConcernExpressed) || hasCurrentTurnPriceComparisonDelta))
        );

        if (isExplicitStopRefusal) {
            interactionPermission = InteractionPermission.PAUSED;
            normalizedStrategy = "Respectful Pause";
            planAdaptation = StrategyAdaptationMode.STOP;
            planCtaLevel = CtaLevel.NO_CTA;
            planNextAction = "NO_ADDITIONAL_ACTION";
            rawConversationState = "PAUSED_BY_CUSTOMER";
        } else if (currentTurnRaisesCompetitorOrPriceObjection) {
            if ("POLITE_DEFERRAL".equalsIgnoreCase(detectedIntent)
                    || "HESITATION".equalsIgnoreCase(detectedIntent)) {
                if (secondaryIntents.stream().noneMatch(s -> s.toUpperCase(Locale.ROOT).contains("EXASPERATION")
                        || s.toUpperCase(Locale.ROOT).contains("HESITATION"))) {
                    secondaryIntents.add("EXASPERATION_REMARK");
                }
                detectedIntent = "COMPETITOR_COMPARISON".equalsIgnoreCase(effectiveBarrierType)
                        ? "COMPETITOR_PRICE_OBJECTION"
                        : "PRICE_OBJECTION";
            }
            if (interactionPermission == InteractionPermission.PAUSED) {
                interactionPermission = InteractionPermission.OPEN;
            }
            if ("Respectful Pause".equalsIgnoreCase(normalizedStrategy)) {
                normalizedStrategy = "Explain Value";
            }
            if (planAdaptation == StrategyAdaptationMode.STOP) {
                planAdaptation = StrategyAdaptationMode.REFINEMENT;
            }
            rawConversationState = "OBJECTION_NEGOTIATION";
        } else if (!isSecurityRefusalIntent
                && (secondaryHasBooking || isBookingSignalIntentLabel(detectedIntent)
                    || bookingReadiness == BookingReadiness.READY_TO_BOOK)) {
            if (interactionPermission == InteractionPermission.BOUNDED_INFO_ONLY
                    || interactionPermission == InteractionPermission.PAUSED) {
                interactionPermission = InteractionPermission.OPEN;
            }
            if ("Respectful Pause".equalsIgnoreCase(normalizedStrategy)) {
                normalizedStrategy = "Direct Answer";
            }
            if (planAdaptation == StrategyAdaptationMode.STOP) {
                planAdaptation = StrategyAdaptationMode.CONTINUATION;
            }
            if (buyingSignal == BuyingSignal.NONE) {
                buyingSignal = BuyingSignal.MODERATE;
            }
            if (planCtaLevel == CtaLevel.NO_CTA) {
                planCtaLevel = (buyingSignal == BuyingSignal.STRONG || buyingSignal == BuyingSignal.ACTION)
                        ? CtaLevel.DIRECT_CTA
                        : CtaLevel.SOFT_CTA;
            }
            if ("NO_ADDITIONAL_ACTION".equalsIgnoreCase(planNextAction)) {
                planNextAction = "GENTLE_SCHEDULE_CHECK";
            }
            rawConversationState = "BOOKING_READY";
        }

        String normalizedConversationState = normalizeConversationStateLabel(
                rawConversationState,
                detectedIntent,
                secondaryIntents,
                effectiveBarrierType,
                bookingReadiness,
                buyingSignal,
                interactionPermission
        );

        // Deterministic Plan Consistency Guard (Chốt chặn nhất quán quyết định trong Java):
        // (a) Nếu interaction_permission == BOUNDED_INFO_ONLY / PAUSED hoặc strategy == Respectful Pause hoặc adaptation == STOP
        //     -> khóa cta_level = NO_CTA và next_action = NO_ADDITIONAL_ACTION.
        // (b) Nếu buying_signal == STRONG / ACTION (và không ở trạng thái PAUSED / BOUNDED_INFO_ONLY / STOP)
        //     -> đảm bảo cta_level tối thiểu là DIRECT_CTA (hoặc TRANSACTION_HANDOFF khi chuyển giao dịch).
        boolean mustLockNoCta = interactionPermission == InteractionPermission.BOUNDED_INFO_ONLY
                || interactionPermission == InteractionPermission.PAUSED
                || "Respectful Pause".equalsIgnoreCase(normalizedStrategy)
                || planAdaptation == StrategyAdaptationMode.STOP;
        if (mustLockNoCta) {
            planCtaLevel = CtaLevel.NO_CTA;
            planNextAction = "NO_ADDITIONAL_ACTION";
            if ("Respectful Pause".equalsIgnoreCase(normalizedStrategy) || interactionPermission == InteractionPermission.PAUSED) {
                planAdaptation = StrategyAdaptationMode.STOP;
            }
        } else if (buyingSignal == BuyingSignal.STRONG || buyingSignal == BuyingSignal.ACTION) {
            if (planCtaLevel == CtaLevel.NO_CTA || planCtaLevel == CtaLevel.SOFT_CTA) {
                planCtaLevel = ("Transaction Handoff".equalsIgnoreCase(normalizedStrategy) || handoffReq.requested())
                        ? CtaLevel.TRANSACTION_HANDOFF
                        : CtaLevel.DIRECT_CTA;
            }
            if ("NO_ADDITIONAL_ACTION".equalsIgnoreCase(planNextAction)) {
                planNextAction = "GENTLE_SCHEDULE_CHECK";
            }
        }

        CustomerUnderstanding understanding = new CustomerUnderstanding(
                detectedIntent,
                emotionalState,
                inferredPronoun,
                pronounConfidence,
                costConcernExpressed,
                verifiedSymptomDuration,
                bookingReadiness,
                communicationStylePreference,
                interactionPermission,
                buyingSignal,
                secondaryIntents,
                normalizedConversationState
        );

        AdaptiveResponsePlan responsePlan = (hasResponsePlan || hasSeparateStrategy || !rawPlanStrategy.isBlank() || mustLockNoCta
                || currentTurnRaisesCompetitorOrPriceObjection || secondaryHasBooking
                || buyingSignal == BuyingSignal.STRONG || buyingSignal == BuyingSignal.ACTION)
                ? new AdaptiveResponsePlan(
                        planObjective,
                        normalizedStrategy,
                        planTone,
                        planDepth,
                        planNextAction,
                        planCtaLevel,
                        planAdaptation,
                        planPrevOutcome
                )
                : AdaptiveResponsePlan.empty();
        String chosenForStrategyRecord = hasResponsePlan || currentTurnRaisesCompetitorOrPriceObjection || isExplicitStopRefusal
                ? normalizedStrategy
                : (!rawPlanStrategy.isBlank() ? rawPlanStrategy : "");
        StrategyReflection strategy = chosenForStrategyRecord.isBlank()
                ? StrategyReflection.empty()
                : new StrategyReflection(chosenForStrategyRecord, planAdaptation, planPrevOutcome, stratEvidence);

        // 8. Tầng 3: business_state & proposed_booking_patch (tách interested_package vs selected_package)
        JsonNode bsNode = b.get("business_state");
        if (bsNode != null && bsNode.isObject() && interestedPackageId == null) {
            interestedPackageId = cleanNullable(readTextOrDefault(bsNode, "interested_package",
                    readTextOrDefault(bsNode, "interested_package_id", null)));
        }
        ProposedBookingPatch bookingPatch = ProposedBookingPatch.empty();
        JsonNode pbpNode = b.get("proposed_booking_patch");
        if (pbpNode != null && pbpNode.isObject()) {
            if (interestedPackageId == null) {
                interestedPackageId = cleanNullable(readTextOrDefault(pbpNode, "interested_package",
                        readTextOrDefault(pbpNode, "interested_package_id", null)));
            }
            bookingPatch = new ProposedBookingPatch(
                    readTextOrDefault(pbpNode, "selected_product_id", null),
                    readTextOrDefault(pbpNode, "proposed_date", null),
                    readTextOrDefault(pbpNode, "proposed_time", null)
            );
        }
        // Nếu khách mới ở EXPLORING / CONSIDERING mà LLM ghi mã gói vào selected_product_id (không có ngày/giờ),
        // chuyển mã gói đó sang interestedPackageId để bảo toàn Tầng 3 (interested_package != selected_package)!
        if (interestedPackageId == null
                && bookingPatch.selectedProductId() != null
                && bookingPatch.proposedDate() == null
                && bookingPatch.proposedTime() == null
                && bookingReadiness != null
                && bookingReadiness != BookingReadiness.READY_TO_BOOK) {
            interestedPackageId = bookingPatch.selectedProductId();
        }

        // 9. activated_skill_codes (1-2 skills actually selected & activated by Brain in this turn)
        boolean explicitActivatedSkills = b.has("activated_skill_codes")
                || b.has("activated_skills")
                || (rootNode != null && (rootNode.has("activated_skill_codes") || rootNode.has("activated_skills")));
        List<String> activatedSkillCodes = List.of();
        if (b.has("activated_skill_codes")) {
            activatedSkillCodes = readStringList(b.get("activated_skill_codes"));
        } else if (b.has("activated_skills")) {
            activatedSkillCodes = readStringList(b.get("activated_skills"));
        } else if (rootNode != null && rootNode.has("activated_skill_codes")) {
            activatedSkillCodes = readStringList(rootNode.get("activated_skill_codes"));
        } else if (rootNode != null && rootNode.has("activated_skills")) {
            activatedSkillCodes = readStringList(rootNode.get("activated_skills"));
        }

        return new AdaptiveCognitiveReflection(
                understanding,
                priority,
                workingHyp,
                strategy,
                uncertainties,
                customerStatements,
                commitments,
                handoffReq,
                bookingPatch,
                blockedCompletions,
                hasV2Markers,
                activatedSkillCodes,
                explicitActivatedSkills,
                currentTopic,
                unresolvedRequests,
                pendingReferences,
                conversationGoal,
                needRevisionDetected,
                confirmedFactsDelta,
                barrierType,
                hypothesisConfLevel,
                interestedPackageId,
                responsePlan
        );
    }

    private static void parseFactDeltaProposals(
            JsonNode factsNode,
            List<FactDeltaProposal> target,
            boolean defaultReviseOnNeedScope
    ) {
        if (factsNode == null || factsNode.isNull()) {
            return;
        }
        if (factsNode.isArray()) {
            for (JsonNode el : factsNode) {
                if (el == null || el.isNull()) continue;
                if (el.isObject()) {
                    String val = readTextOrDefault(el, "value", readTextOrDefault(el, "fact", ""));
                    if (val.isBlank()) continue;
                    String key = readTextOrDefault(el, "key", readTextOrDefault(el, "category", ""));
                    String rawOp = readTextOrDefault(el, "op", readTextOrDefault(el, "operation", null));
                    FactMutationOp op = rawOp != null && !rawOp.isBlank()
                            ? FactMutationOp.fromString(rawOp)
                            : (defaultReviseOnNeedScope ? FactMutationOp.REVISE : FactMutationOp.ADD);
                    FactValidity explicitValidity = FactValidity.parseExplicit(
                            readTextOrDefault(el, "status", readTextOrDefault(el, "validity", null)));
                    if (explicitValidity == null && rawOp != null) {
                        FactValidity fromOp = FactValidity.parseExplicit(rawOp);
                        if (fromOp != null && fromOp != FactValidity.SUPERSEDED) {
                            explicitValidity = fromOp;
                        }
                    }
                    String conf = readTextOrDefault(el, "confidence", "HIGH");
                    appendFactDeltaWithAtomicSymptomSplit(target, key, val, op, conf, explicitValidity);
                } else if (el.isTextual() && !el.asText().isBlank()) {
                    String val = el.asText().strip();
                    appendFactDeltaWithAtomicSymptomSplit(
                            target,
                            "",
                            val,
                            defaultReviseOnNeedScope ? FactMutationOp.REVISE : FactMutationOp.ADD,
                            "HIGH",
                            null
                    );
                }
            }
        }
    }

    private static void appendFactDeltaWithAtomicSymptomSplit(
            List<FactDeltaProposal> target,
            String rawKey,
            String rawVal,
            FactMutationOp op,
            String conf,
            FactValidity explicitValidity
    ) {
        if (rawVal == null || rawVal.isBlank()) {
            return;
        }
        String normKey = normalizeFactCategoryKey(rawKey, rawVal);
        if ("self_reported_symptom".equals(normKey)) {
            List<String> atoms = splitAtomicHealthExpressions(rawVal);
            if (atoms.isEmpty()) {
                return;
            }
            for (String atom : atoms) {
                String canon = canonicalSymptomKey(atom);
                if (canon.isBlank()) continue;
                int existingIdx = -1;
                for (int i = 0; i < target.size(); i++) {
                    FactDeltaProposal existing = target.get(i);
                    if (existing != null
                            && "self_reported_symptom".equals(existing.key())
                            && canonicalSymptomKey(existing.value()).equals(canon)) {
                        existingIdx = i;
                        break;
                    }
                }
                if (existingIdx >= 0) {
                    FactDeltaProposal existing = target.get(existingIdx);
                    // Ưu tiên trạng thái tường minh khác CURRENT (RESOLVED / PAST / NEGATED / SUPERSEDED) nếu cùng xuất hiện
                    if (explicitValidity != null && explicitValidity != FactValidity.CURRENT
                            && existing.effectiveValidity() == FactValidity.CURRENT) {
                        target.set(existingIdx, new FactDeltaProposal(
                                "self_reported_symptom", atom, op, conf, explicitValidity));
                    }
                } else {
                    target.add(new FactDeltaProposal("self_reported_symptom", atom, op, conf, explicitValidity));
                }
            }
            return;
        }
        target.add(new FactDeltaProposal(normKey, rawVal, op, conf, explicitValidity));
    }

    // =========================================================================
    // 4. FIVE-TIER MEMORY, COMMITMENT VS TASK & CONDITIONAL HANDOFF (MỤC 4)
    // =========================================================================

    /** Tier 1: Lời khách tự khai (lưu kèm turnId, KHÔNG tự biến thành sự thật nghiệp vụ). */
    public record CustomerStatementRecord(
            String statementId,
            String turnId,
            String category,
            String content,
            boolean verifiedBusinessFact,
            Instant recordedAt
    ) {
        public CustomerStatementRecord {
            statementId = statementId != null ? statementId : ("STMT-" + UUID.randomUUID().toString().substring(0, 8));
            turnId = (turnId != null && !turnId.isBlank()) ? turnId.strip() : "T_CURRENT";
            category = (category != null && !category.isBlank()) ? category.strip() : "GENERAL_STATEMENT";
            content = content != null ? content.strip() : "";
            // Lời khách tự khai không bao giờ tự động trở thành sự thật nghiệp vụ đã xác minh
            verifiedBusinessFact = false;
            recordedAt = recordedAt != null ? recordedAt : Instant.now();
        }
    }

    /** Tier 2: Working Hypothesis với vòng đời 5 thao tác dựa trên bằng chứng lượt hội thoại. */
    public record WorkingHypothesisRecord(
            String content,
            HypothesisLifecycleOp lastLifecycleOp,
            double confidence,
            List<String> evidenceTurnIds,
            Instant updatedAt
    ) {
        public WorkingHypothesisRecord {
            content = content != null ? content.strip() : "";
            lastLifecycleOp = lastLifecycleOp != null ? lastLifecycleOp : HypothesisLifecycleOp.MAINTAIN;
            confidence = Math.max(0.0, Math.min(1.0, confidence));
            evidenceTurnIds = evidenceTurnIds != null ? List.copyOf(evidenceTurnIds) : List.of();
            updatedAt = updatedAt != null ? updatedAt : Instant.now();
        }

        public static WorkingHypothesisRecord initialEmpty() {
            return new WorkingHypothesisRecord("", HypothesisLifecycleOp.MAINTAIN, 0.0, List.of(), Instant.now());
        }
    }

    /** Tier 4: Sự thật nghiệp vụ đã xác minh (chỉ Java Core / nguồn nghiệp vụ có thẩm quyền được ghi). */
    public record VerifiedBusinessFact(
            String factId,
            String category,
            String factKey,
            String factValue,
            String authoritativeSource,
            Instant verifiedAt
    ) {
        public VerifiedBusinessFact {
            factId = factId != null ? factId : ("VBF-" + UUID.randomUUID().toString().substring(0, 8));
            category = category != null ? category.strip() : "BUSINESS_TRUTH";
            factKey = factKey != null ? factKey.strip() : "";
            factValue = factValue != null ? factValue.strip() : "";
            authoritativeSource = authoritativeSource != null ? authoritativeSource.strip() : "JAVA_CORE_AUTHORITY";
            verifiedAt = verifiedAt != null ? verifiedAt : Instant.now();
        }
    }

    /** Bản ghi kết quả chiến thuật của lượt trước phục vụ Adaptive Strategy Selection. */
    public record PreviousStrategyRecord(
            String lastPrimaryPriority,
            String lastChosenStrategy,
            StrategyOutcome strategyOutcome,
            String outcomeEvidence,
            String turnId,
            Instant recordedAt
    ) {
        public static PreviousStrategyRecord initialNone() {
            return new PreviousStrategyRecord("NONE", "NONE", StrategyOutcome.NOT_APPLICABLE, "", "NONE", Instant.now());
        }
    }

    public enum CommitmentStatus {
        pending,
        completed,
        failed,
        cancelled
    }

    public enum TaskExecutionStatus {
        not_created,
        created,
        in_progress,
        completed,
        failed
    }

    /**
     * Session-Level Commitment Record tách bạch {@code commitment_status} (lời hứa hội thoại)
     * và {@code execution_status} (công việc thực tế giao cho bộ phận phụ trách).
     */
    public record CommitmentRecord(
            String commitmentId,
            String createdTurnId,
            String updatedTurnId,
            String content,
            CommitmentOwner owner,
            String verifiedTimeframe,
            CommitmentStatus commitmentStatus,
            TaskExecutionStatus executionStatus,
            String taskId,
            String verificationEvidence,
            Instant updatedAt
    ) {
        public CommitmentRecord {
            commitmentId = commitmentId != null ? commitmentId : ("CMT-" + UUID.randomUUID().toString().substring(0, 8));
            createdTurnId = createdTurnId != null ? createdTurnId : "T_CURRENT";
            updatedTurnId = updatedTurnId != null ? updatedTurnId : createdTurnId;
            content = content != null ? content.strip() : "";
            owner = owner != null ? owner : CommitmentOwner.STAFF;
            commitmentStatus = commitmentStatus != null ? commitmentStatus : CommitmentStatus.pending;
            executionStatus = executionStatus != null ? executionStatus : TaskExecutionStatus.not_created;
            updatedAt = updatedAt != null ? updatedAt : Instant.now();
        }
    }

    public enum HandoffLifecycleStatus {
        NONE,
        HANDOFF_REQUESTED,
        HANDOFF_CREATED,
        HANDOFF_ASSIGNED,
        HANDOFF_ACKNOWLEDGED,
        HANDOFF_RESOLVED,
        HANDOFF_FAILED
    }

    /** Tier 5: Operational State do Java Core nắm quyền tuyệt đối. */
    public record OperationalStateSnapshot(
            String bookingStatus,
            HandoffLifecycleStatus handoffStatus,
            boolean optedOut,
            String activeHandoffTicketId,
            String lastHandoffError,
            String communicationStylePreference
    ) {
        public OperationalStateSnapshot {
            String normalizedPref = normalizeCommunicationStylePreference(communicationStylePreference);
            communicationStylePreference = normalizedPref != null ? normalizedPref : "STANDARD";
        }

        public OperationalStateSnapshot(
                String bookingStatus,
                HandoffLifecycleStatus handoffStatus,
                boolean optedOut,
                String activeHandoffTicketId,
                String lastHandoffError
        ) {
            this(bookingStatus, handoffStatus, optedOut, activeHandoffTicketId, lastHandoffError, "STANDARD");
        }

        public static OperationalStateSnapshot initial() {
            return new OperationalStateSnapshot("NONE", HandoffLifecycleStatus.NONE, false, null, null, "STANDARD");
        }
    }

    /**
     * Cập nhật vòng đời {@code Working Hypothesis} theo 5 thao tác ({@code MAINTAIN -> REINFORCE -> ADJUST -> DOWNGRADE -> REJECT})
     * mà không bị xóa trắng bởi bộ đếm trùng từ mặt chữ hay giới hạn {@code < 12} ký tự.
     */
    public static WorkingHypothesisRecord applyWorkingHypothesisLifecycle(
            WorkingHypothesisRecord prior,
            WorkingHypothesisReflection proposal,
            String currentTurnId
    ) {
        WorkingHypothesisRecord safePrior = prior != null ? prior : WorkingHypothesisRecord.initialEmpty();
        if (proposal == null) {
            return safePrior;
        }
        HypothesisLifecycleOp op = proposal.lifecycleOp() != null ? proposal.lifecycleOp() : HypothesisLifecycleOp.MAINTAIN;
        String newContent = proposal.content() != null ? proposal.content().strip() : "";
        String safeTurnId = (currentTurnId != null && !currentTurnId.isBlank()) ? currentTurnId.strip() : "T_CURRENT";

        Set<String> mergedEvidence = new LinkedHashSet<>();
        if (op == HypothesisLifecycleOp.MAINTAIN || op == HypothesisLifecycleOp.REINFORCE || op == HypothesisLifecycleOp.DOWNGRADE) {
            mergedEvidence.addAll(safePrior.evidenceTurnIds());
        }
        if (proposal.evidenceTurnIds() != null && !proposal.evidenceTurnIds().isEmpty()) {
            mergedEvidence.addAll(proposal.evidenceTurnIds());
        } else if (!newContent.isEmpty() || !safePrior.content().isEmpty()) {
            mergedEvidence.add(safeTurnId);
        }

        double priorConf = safePrior.confidence() > 0.0 ? safePrior.confidence() : 0.65;
        double proposedConf = proposal.confidence() > 0.0 ? proposal.confidence() : priorConf;

        return switch (op) {
            case MAINTAIN -> {
                String keptContent = !newContent.isEmpty() ? newContent : safePrior.content();
                double conf = !keptContent.isEmpty() ? Math.max(0.50, proposedConf) : 0.0;
                yield new WorkingHypothesisRecord(keptContent, HypothesisLifecycleOp.MAINTAIN, conf, List.copyOf(mergedEvidence), Instant.now());
            }
            case REINFORCE -> {
                String reinforcedContent = !newContent.isEmpty() ? newContent : safePrior.content();
                double conf = Math.min(0.98, Math.max(proposedConf, priorConf + 0.10));
                yield new WorkingHypothesisRecord(reinforcedContent, HypothesisLifecycleOp.REINFORCE, conf, List.copyOf(mergedEvidence), Instant.now());
            }
            case ADJUST -> {
                String adjustedContent = !newContent.isEmpty() ? newContent : safePrior.content();
                double conf = proposal.confidence() > 0.0 ? proposal.confidence() : 0.78;
                yield new WorkingHypothesisRecord(adjustedContent, HypothesisLifecycleOp.ADJUST, conf, List.copyOf(mergedEvidence), Instant.now());
            }
            case DOWNGRADE -> {
                String downgradedContent = !newContent.isEmpty() ? newContent : safePrior.content();
                double conf = proposal.confidence() > 0.0 && proposal.confidence() < priorConf
                        ? proposal.confidence()
                        : Math.max(0.20, priorConf - 0.25);
                yield new WorkingHypothesisRecord(downgradedContent, HypothesisLifecycleOp.DOWNGRADE, conf, List.copyOf(mergedEvidence), Instant.now());
            }
            case REJECT -> {
                // Khi bác bỏ giả thuyết cũ và có giả thuyết thay thế mới (VD: A04 khách nói tiền không quan trọng, chỉ cần KTV chắc tay),
                // ghi nhận ngay nội dung mới với op=REJECT & evidence_turn_ids mới; nếu không có giả thuyết thay thế thì để rỗng.
                double conf = !newContent.isEmpty()
                        ? (proposal.confidence() > 0.0 ? proposal.confidence() : 0.82)
                        : 0.15;
                List<String> rejectEvidence = (proposal.evidenceTurnIds() != null && !proposal.evidenceTurnIds().isEmpty())
                        ? List.copyOf(proposal.evidenceTurnIds())
                        : List.of(safeTurnId);
                yield new WorkingHypothesisRecord(newContent, HypothesisLifecycleOp.REJECT, conf, rejectEvidence, Instant.now());
            }
        };
    }

    public static String categorizeCustomerStatement(String text) {
        if (text == null || text.isBlank()) {
            return "GENERAL_STATEMENT";
        }
        String norm = Normalizer.normalize(text, Normalizer.Form.NFC);
        if (PAYMENT_CLAIM_STATEMENT_PATTERN.matcher(norm).matches()) {
            return "PAYMENT_CLAIM";
        }
        if (STAFF_PROMISE_CLAIM_PATTERN.matcher(norm).matches()) {
            return "STAFF_PROMISE_CLAIM";
        }
        if (CONCRETE_SERVICE_INCIDENT_PATTERN.matcher(norm).matches()) {
            return "SERVICE_INCIDENT_CLAIM";
        }
        return "CUSTOMER_SELF_REPORTED";
    }

    public static boolean isPaymentClaimMessage(String message) {
        return message != null && PAYMENT_CLAIM_STATEMENT_PATTERN.matcher(
                Normalizer.normalize(message, Normalizer.Form.NFC)).matches();
    }

    public static boolean isStaffPromiseClaimMessage(String message) {
        return message != null && STAFF_PROMISE_CLAIM_PATTERN.matcher(
                Normalizer.normalize(message, Normalizer.Form.NFC)).matches();
    }

    public static boolean isConcreteServiceIncidentMessage(String message) {
        return message != null && CONCRETE_SERVICE_INCIDENT_PATTERN.matcher(
                Normalizer.normalize(message, Normalizer.Form.NFC)).matches();
    }

    public static boolean isExplicitHumanEscalationMessage(String message) {
        return message != null && EXPLICIT_HUMAN_ESCALATION_PATTERN.matcher(
                Normalizer.normalize(message, Normalizer.Form.NFC)).matches();
    }

    // =========================================================================
    // 5. CONDITIONAL HANDOFF POLICY ENGINE (MỤC 4.4)
    // =========================================================================

    public record HandoffDispatchResult(
            boolean created,
            String ticketId,
            String errorReason
    ) {
        public static HandoffDispatchResult success(String ticketId) {
            return new HandoffDispatchResult(true, ticketId, null);
        }

        public static HandoffDispatchResult failed(String errorReason) {
            return new HandoffDispatchResult(false, null, errorReason);
        }
    }

    @FunctionalInterface
    public interface HandoffEventDispatcher {
        HandoffDispatchResult dispatchHandoffEvent(UUID pageId, String senderId, String category, String reason);
    }

    public record ConditionalHandoffDecision(
            boolean handoffRequestedByLlm,
            boolean policyApprovedCreation,
            HandoffLifecycleStatus resultingStatus,
            String handoffCategory,
            String handoffReason,
            String ticketId,
            String failureReason
    ) {
        public boolean isHandoffCreated() {
            return resultingStatus == HandoffLifecycleStatus.HANDOFF_CREATED
                    || resultingStatus == HandoffLifecycleStatus.HANDOFF_ASSIGNED
                    || resultingStatus == HandoffLifecycleStatus.HANDOFF_ACKNOWLEDGED;
        }
    }

    /**
     * Đánh giá chính sách Handoff có điều kiện ({@code handoff_requested -> handoff_created}):
     * <ul>
     *   <li>Không tạo ticket vô điều kiện cho phàn nàn nhẹ chưa rõ nguyên nhân (như {@code S05} {@code "Tôi không hài lòng"} ngay sau gợi ý gói).</li>
     *   <li>Bắt buộc tạo yêu cầu xác minh/handoff cho: báo đã chuyển khoản cần đối soát ({@code S08}), tranh chấp cam kết nhân viên ({@code S09}),
     *       sự cố dịch vụ/lịch hẹn thực tế ({@code A08}), hoặc khách yêu cầu gặp người thật.</li>
     *   <li>Chỉ chuyển sang {@code HANDOFF_CREATED} khi {@code dispatcher} thực sự tạo sự kiện thành công; nếu lỗi -> {@code HANDOFF_FAILED}.</li>
     * </ul>
     */
    public static ConditionalHandoffDecision evaluateAndExecuteConditionalHandoff(
            UUID pageId,
            String senderId,
            String originalCustomerMessage,
            AdaptiveCognitiveReflection reflection,
            HandoffEventDispatcher dispatcher
    ) {
        return evaluateAndExecuteConditionalHandoff(
                pageId,
                senderId,
                originalCustomerMessage,
                reflection,
                null,
                dispatcher
        );
    }

    /** ACI V3: Câu hỏi trực tiếp "tình trạng của tôi có làm dịch vụ/massage được không" (khác với chỉ chia sẻ triệu chứng). */
    private static final Pattern HEALTH_SUITABILITY_QUESTION_CUE_PATTERN = Pattern.compile(
            "(?isU).*(?:"
                    + "(?:có\\s+)?(?:làm|massage|mát\\s*xa|gội|đi\\s+làm|sử\\s+dụng)(?:\\s+(?:massage|mát\\s*xa|dịch\\s+vụ|gói(?:\\s+(?:này|đó|kia))?|liệu\\s+trình))?\\s+(?:được|đc|dc)\\s+(?:không|ko|k|hông|hok|chứ|ạ)"
                    + "|có\\s+ảnh\\s+hưởng\\s+(?:gì\\s+)?(?:không|ko|hông)"
                    + "|có\\s+nên\\s+(?:làm|đi|massage|mát\\s*xa|gội)[^?？]{0,30}(?:không|ko|hông)"
                    + "|có\\s+(?:sao|nguy\\s+hiểm|an\\s+toàn|phù\\s+hợp)\\s+(?:gì\\s+)?(?:không|ko|hông)"
                    + ").*"
    );

    private static final Pattern HEALTH_CONDITION_CONTEXT_PATTERN = Pattern.compile(
            "(?isU).*\\b(?:tình\\s+trạng|bị\\s+(?:như\\s+)?(?:vậy|thế)|như\\s+vậy|như\\s+thế|đau|nhức|chóng\\s+mặt|choáng|hoa\\s+mắt|"
                    + "tê|mỏi|huyết\\s+áp|tiền\\s+đình|bệnh|mất\\s+ngủ|khó\\s+ngủ|mệt|thoát\\s+vị|có\\s+bầu|mang\\s+thai|sức\\s+khỏe|sức\\s+khoẻ)\\b.*"
    );

    private static final Pattern BOOKING_TIME_SLOT_QUESTION_PATTERN = Pattern.compile(
            "(?isU).*\\b(?:\\d{1,2}\\s*(?:h|giờ|g)(?:\\s*\\d{1,2})?|chiều\\s+nay|tối\\s+nay|sáng\\s+mai|ngày\\s+mai|cuối\\s+tuần|khung\\s+giờ|đặt\\s+lịch)\\b.*"
    );

    /**
     * ACI V3: Nhận diện khi khách hỏi TRỰC TIẾP xem tình trạng sức khỏe của mình có làm dịch vụ/massage được không.
     * Chỉ chia sẻ mệt mỏi/đau đầu/mất ngủ mà không hỏi câu phù hợp sức khỏe -> {@code false}.
     */
    public static boolean isHealthSuitabilityInquiryMessage(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return false;
        }
        String low = Normalizer.normalize(rawMessage.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        if (!HEALTH_SUITABILITY_QUESTION_CUE_PATTERN.matcher(low).matches()) {
            return false;
        }
        if (!HEALTH_CONDITION_CONTEXT_PATTERN.matcher(low).matches()) {
            return false;
        }
        // "Chiều nay 17h làm được không" là câu hỏi lịch, không phải câu hỏi phù hợp sức khỏe
        boolean explicitConditionRef = low.matches("(?isU).*\\b(?:tình\\s+trạng|bị\\s+(?:như\\s+)?(?:vậy|thế)|như\\s+vậy|như\\s+thế|sức\\s+kho[ẻẻ])\\b.*");
        if (!explicitConditionRef && BOOKING_TIME_SLOT_QUESTION_PATTERN.matcher(low).matches()) {
            return false;
        }
        return true;
    }

    public static ConditionalHandoffDecision evaluateAndExecuteConditionalHandoff(
            UUID pageId,
            String senderId,
            String originalCustomerMessage,
            AdaptiveCognitiveReflection reflection,
            com.example.docmind.service.retrieval.PromptBuilder.PagePersona pagePersona,
            HandoffEventDispatcher dispatcher
    ) {
        boolean llmRequested = reflection != null
                && reflection.handoffRequest() != null
                && reflection.handoffRequest().requested();
        String llmReason = (reflection != null && reflection.handoffRequest() != null)
                ? reflection.handoffRequest().reason()
                : null;

        boolean llmSafetyEscalation = llmRequested
                && reflection != null
                && reflection.priority() != null
                && "SAFETY".equalsIgnoreCase(reflection.priority().primary());
        boolean safetyContraindication = pagePersona != null
                ? FanpageBotService.shouldTriggerSafetyContraindicationHandoff(originalCustomerMessage, pagePersona)
                : FanpageBotService.isUniversalAcuteMedicalEmergency(originalCustomerMessage);
        boolean paymentClaim = isPaymentClaimMessage(originalCustomerMessage);
        boolean staffPromise = isStaffPromiseClaimMessage(originalCustomerMessage);
        boolean concreteIncident = isConcreteServiceIncidentMessage(originalCustomerMessage);
        boolean explicitHuman = isExplicitHumanEscalationMessage(originalCustomerMessage);
        // ACI V3: Khách hỏi trực tiếp tình trạng sức khỏe có làm dịch vụ được không -> chuyển chuyên viên Spa đánh giá.
        // Chỉ chia sẻ mệt mỏi/đau đầu/mất ngủ thông thường (không hỏi câu phù hợp) -> KHÔNG tự động handoff.
        boolean suitabilityInquiry = !safetyContraindication
                && isHealthSuitabilityInquiryMessage(originalCustomerMessage);

        String category = null;
        String effectiveReason = llmReason;
        boolean mandatoryVerificationOrHandoff = false;

        if (suitabilityInquiry) {
            mandatoryVerificationOrHandoff = true;
            category = "SUITABILITY_INQUIRY_HANDOFF";
            effectiveReason = "Khách hàng hỏi về mức độ phù hợp của dịch vụ với tình trạng sức khỏe cá nhân (nguyên văn: \""
                    + originalCustomerMessage.strip()
                    + "\") — chuyển nhân viên/chuyên viên Spa tư vấn theo quy trình vận hành (DEMO_HANDOFF_PENDING).";
            if (explicitHuman) {
                effectiveReason = effectiveReason + " (Đồng thời khách yêu cầu nhân viên hỗ trợ trực tiếp).";
            }
        } else if (safetyContraindication) {
            mandatoryVerificationOrHandoff = true;
            category = "MEDICAL_SAFETY_HANDOFF";
            if (effectiveReason == null || effectiveReason.isBlank()) {
                effectiveReason = explicitHuman
                        ? "Khách hàng có dấu hiệu cảnh báo an toàn y khoa (Cấp 3/Cấp 4) VÀ chủ động yêu cầu nhân sự hỗ trợ — ưu tiên an toàn y tế đồng thời kết nối chuyên viên/quản lý hỗ trợ trực tiếp."
                        : "Khách hàng có dấu hiệu thai kỳ, triệu chứng thần kinh cấp tính hoặc cảnh báo an toàn chuyên ngành — chuyển chuyên viên hỗ trợ trực tiếp.";
            } else if (explicitHuman && !effectiveReason.toLowerCase(Locale.ROOT).contains("nhân viên") && !effectiveReason.toLowerCase(Locale.ROOT).contains("chuyên viên")) {
                effectiveReason = effectiveReason + " (Đồng thời khách yêu cầu nhân viên hỗ trợ trực tiếp).";
            }
        } else if (paymentClaim) {
            mandatoryVerificationOrHandoff = true;
            category = "PAYMENT_VERIFICATION_HANDOFF";
            if (effectiveReason == null || effectiveReason.isBlank()) {
                effectiveReason = "Khách hàng báo đã chuyển khoản/thanh toán — cần thu ngân đối soát giao dịch thực tế.";
            }
        } else if (staffPromise) {
            mandatoryVerificationOrHandoff = true;
            category = "STAFF_PROMISE_VERIFICATION_HANDOFF";
            if (effectiveReason == null || effectiveReason.isBlank()) {
                effectiveReason = "Khách hàng phản ánh cam kết/lời hứa từ nhân viên — cần quản lý xác minh thông tin.";
            }
        } else if (concreteIncident) {
            mandatoryVerificationOrHandoff = true;
            category = "SERVICE_INCIDENT_HANDOFF";
            if (effectiveReason == null || effectiveReason.isBlank()) {
                effectiveReason = "Khách hàng phản ánh sự cố trải nghiệm/lịch hẹn thực tế — cần quản lý kiểm tra xử lý.";
            }
        } else if (explicitHuman) {
            mandatoryVerificationOrHandoff = true;
            category = "EXPLICIT_HUMAN_REQUEST";
            if (effectiveReason == null || effectiveReason.isBlank()) {
                effectiveReason = "Khách hàng chủ động yêu cầu gặp nhân viên/quản lý hỗ trợ trực tiếp.";
            }
        } else if (llmSafetyEscalation) {
            mandatoryVerificationOrHandoff = true;
            category = "MEDICAL_SAFETY_HANDOFF";
            if (effectiveReason == null || effectiveReason.isBlank()) {
                effectiveReason = "Khách hàng có dấu hiệu cảnh báo an toàn sức khỏe — chuyển chuyên viên hỗ trợ trực tiếp.";
            }
        }

        if (!mandatoryVerificationOrHandoff) {
            // Nếu LLM đề xuất handoff nhưng khách chỉ mới nói câu bất mãn nhẹ/mơ hồ trong hội thoại (như S05 "Tôi không hài lòng")
            // mà chưa có sự cố thực tế hay yêu cầu gặp người thật -> giữ ở hội thoại để bot hỏi làm rõ trước!
            if (llmRequested) {
                log.info("[ACI-ConditionalHandoff] Deferred premature handoff_requested on clarifiable turn (page={}, sender={}, msg='{}', llmReason='{}')",
                        pageId, senderId, originalCustomerMessage, llmReason);
                return new ConditionalHandoffDecision(
                        true,
                        false,
                        HandoffLifecycleStatus.HANDOFF_REQUESTED,
                        null,
                        llmReason,
                        null,
                        null
                );
            }
            return new ConditionalHandoffDecision(
                    false,
                    false,
                    HandoffLifecycleStatus.NONE,
                    null,
                    null,
                    null,
                    null
            );
        }

        HandoffEventDispatcher safeDispatcher = dispatcher != null
                ? dispatcher
                : (p, s, cat, rsn) -> HandoffDispatchResult.success(
                        "HO-" + (cat != null ? cat.substring(0, Math.min(3, cat.length())) : "GEN")
                                + "-" + UUID.randomUUID().toString().substring(0, 8));
        try {
            HandoffDispatchResult result = safeDispatcher.dispatchHandoffEvent(pageId, senderId, category, effectiveReason);
            if (result != null && result.created()) {
                return new ConditionalHandoffDecision(
                        llmRequested || mandatoryVerificationOrHandoff,
                        true,
                        HandoffLifecycleStatus.HANDOFF_CREATED,
                        category,
                        effectiveReason,
                        result.ticketId(),
                        null
                );
            }
            String err = (result != null && result.errorReason() != null) ? result.errorReason() : "HANDOFF_DISPATCH_UNAVAILABLE";
            log.warn("[ACI-ConditionalHandoff] Handoff creation failed (page={}, sender={}, category={}, err={})",
                    pageId, senderId, category, err);
            return new ConditionalHandoffDecision(
                    llmRequested || mandatoryVerificationOrHandoff,
                    true,
                    HandoffLifecycleStatus.HANDOFF_FAILED,
                    category,
                    effectiveReason,
                    null,
                    err
            );
        } catch (Exception ex) {
            log.warn("[ACI-ConditionalHandoff] Exception dispatching handoff event: {}", ex.getMessage());
            return new ConditionalHandoffDecision(
                    llmRequested || mandatoryVerificationOrHandoff,
                    true,
                    HandoffLifecycleStatus.HANDOFF_FAILED,
                    category,
                    effectiveReason,
                    null,
                    ex.getMessage()
            );
        }
    }

    /**
     * Unit P2.1: Thực thi Handoff có thẩm quyền từ Referee ({@code SAFE_HANDOFF} khi vi phạm giá dịch vụ không căn cứ
     * vẫn tồn tại sau bước repair), đảm bảo đồng bộ nhất quán 100% giữa {@code needsHumanHandoff},
     * {@code handoffCategory} và {@code handoffStatus} trên Telemetry, Session Memory và {@code FanpageBotResponse}.
     */
    public static ConditionalHandoffDecision executeAuthoritativeSafeRefereeHandoff(
            UUID pageId,
            String senderId,
            String category,
            String reason,
            HandoffEventDispatcher dispatcher
    ) {
        String effectiveCategory = (category != null && !category.isBlank())
                ? category.strip()
                : "REFEREE_SAFE_HANDOFF_UNGROUNDED_PRICE";
        String effectiveReason = (reason != null && !reason.isBlank())
                ? reason.strip()
                : "Referee chặn mức giá dịch vụ không có căn cứ sau bước sửa lỗi — chuyển nhân viên hỗ trợ trực tiếp.";
        HandoffEventDispatcher safeDispatcher = dispatcher != null
                ? dispatcher
                : (p, s, cat, rsn) -> HandoffDispatchResult.success(
                        "HO-REF-" + UUID.randomUUID().toString().substring(0, 8));
        try {
            HandoffDispatchResult result = safeDispatcher.dispatchHandoffEvent(
                    pageId, senderId, effectiveCategory, effectiveReason);
            if (result != null && result.created()) {
                return new ConditionalHandoffDecision(
                        false,
                        true,
                        HandoffLifecycleStatus.HANDOFF_CREATED,
                        effectiveCategory,
                        effectiveReason,
                        result.ticketId(),
                        null
                );
            }
            String err = (result != null && result.errorReason() != null)
                    ? result.errorReason()
                    : "HANDOFF_DISPATCH_UNAVAILABLE";
            log.warn("[ACI-SafeRefereeHandoff] Handoff creation failed (page={}, sender={}, category={}, err={})",
                    pageId, senderId, effectiveCategory, err);
            return new ConditionalHandoffDecision(
                    false,
                    true,
                    HandoffLifecycleStatus.HANDOFF_FAILED,
                    effectiveCategory,
                    effectiveReason,
                    null,
                    err
            );
        } catch (Exception ex) {
            log.warn("[ACI-SafeRefereeHandoff] Exception dispatching safe handoff event: {}", ex.getMessage());
            return new ConditionalHandoffDecision(
                    false,
                    true,
                    HandoffLifecycleStatus.HANDOFF_FAILED,
                    effectiveCategory,
                    effectiveReason,
                    null,
                    ex.getMessage()
            );
        }
    }

    // =========================================================================
    // 6. AUTHORITATIVE BOOKING PATCH APPLICATION (MỤC 3.1, 3.4, A03, S08, S10B, A08)
    // =========================================================================

    /**
     * Áp dụng {@link ProposedBookingPatch} từ LLM qua bộ kiểm định thẩm quyền của Java Core:
     * <ul>
     *   <li>Không bao giờ tự điền khống {@code selected_product_id} ở câu {@code "Ừ"} sau lời giải thích giá ({@code S10B}).</li>
     *   <li>Không xóa trắng {@code priorBookingState} khi khách hỏi giá, đắn đo hay báo đã chuyển khoản ({@code S08}).</li>
     *   <li>Hỗ trợ khách đổi sản phẩm và đổi giờ hẹn nhiều lần xuyên lượt ({@code A03}) mà không ghi đè mất các slot còn lại.</li>
     *   <li>Trạng thái tối đa khi khách đặt qua chat luôn dừng ở {@code AWAITING_AVAILABILITY_CHECK} (cấm tự lên {@code CONFIRMED}).</li>
     * </ul>
     */
    public static ConversationState.StructuredBookingState applyAuthoritativeBookingPatch(
            ConversationState.StructuredBookingState priorState,
            ProposedBookingPatch patch,
            String originalCustomerMessage,
            String precedingBotText,
            List<PackageCatalogService.PackageSummary> allCatalog,
            List<PackageCatalogService.PackageSummary> presentedPackagesInHistory,
            ZoneId pageZoneId
    ) {
        return applyAuthoritativeBookingPatch(
                priorState,
                patch,
                originalCustomerMessage,
                precedingBotText,
                allCatalog,
                presentedPackagesInHistory,
                pageZoneId,
                null,
                null
        );
    }

    private static ConversationState.StructuredBookingState withCandidateProducts(
            ConversationState.StructuredBookingState state,
            List<String> candidateProductIds,
            ZoneId safeZone
    ) {
        ConversationState.StructuredBookingState safe =
                state != null ? state : ConversationState.StructuredBookingState.none();
        if (candidateProductIds == null || candidateProductIds.isEmpty()) {
            return safe;
        }
        Set<String> merged = new LinkedHashSet<>();
        if (safe.candidateProductIds() != null) {
            merged.addAll(safe.candidateProductIds());
        }
        merged.addAll(candidateProductIds);
        return new ConversationState.StructuredBookingState(
                safe.selectedProductId(),
                safe.selectedProductLabel(),
                safe.bookingDate(),
                safe.resolvedIsoDate(),
                safe.bookingTime(),
                safe.timezoneId() != null ? safe.timezoneId() : safeZone.getId(),
                safe.status(),
                List.copyOf(merged)
        );
    }

    public static ConversationState.StructuredBookingState applyAuthoritativeBookingPatch(
            ConversationState.StructuredBookingState priorState,
            ProposedBookingPatch patch,
            String originalCustomerMessage,
            String precedingBotText,
            List<PackageCatalogService.PackageSummary> allCatalog,
            List<PackageCatalogService.PackageSummary> presentedPackagesInHistory,
            ZoneId pageZoneId,
            BookingReadiness bookingReadiness,
            String primaryPriority
    ) {
        ConversationState.StructuredBookingState safePrior =
                priorState != null ? priorState : ConversationState.StructuredBookingState.none();
        ZoneId safeZone = pageZoneId != null ? pageZoneId : ZoneId.of("Asia/Ho_Chi_Minh");

        if (isExplicitBookingCancelOrRefusalMessage(originalCustomerMessage)) {
            return ConversationState.StructuredBookingState.none();
        }

        // ACI V3 Guardrail 4: Ưu tiên an toàn y tế tuyệt đối (Cấp 3 hậu phẫu/chống chỉ định & Cấp 4 cấp cứu 115) —
        // KHÔNG BAO GIỜ cho phép booking patch ghi đè hoặc đẩy trạng thái đặt lịch khi lượt hiện tại có cảnh báo an toàn y tế!
        if (originalCustomerMessage != null) {
            String msgLowForSafety = Normalizer.normalize(originalCustomerMessage.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
            if ("SAFETY".equalsIgnoreCase(primaryPriority != null ? primaryPriority.strip() : "")
                    || FanpageBotService.isUniversalAcuteMedicalEmergency(originalCustomerMessage)
                    || CleanAgentCatalogAndPromptBuilder.hasHighRiskOrContraindicationCue(msgLowForSafety)) {
                return safePrior;
            }
        }

        List<PackageCatalogService.PackageSummary> distinctPresented = new ArrayList<>();
        List<String> earlyCandidateIds = new ArrayList<>();
        if (presentedPackagesInHistory != null) {
            Set<String> seenIds = new LinkedHashSet<>();
            for (PackageCatalogService.PackageSummary p : presentedPackagesInHistory) {
                if (p != null && p.productId() != null && seenIds.add(p.productId())) {
                    distinctPresented.add(p);
                    earlyCandidateIds.add(p.productId());
                }
            }
        }

        // Chặn lỗi tự hiểu nhầm "Ok" / "Ừ" / "Để xem đã" thành xác nhận đặt lịch:
        // Khi chưa có bookingState mở (status == NONE), nếu khách chỉ nhắn bare ack ("Ok", "Ừ", "Vâng") hoặc câu lưỡng lự/xin suy nghĩ thêm,
        // BẮT BUỘC giữ nguyên BookingStatus.NONE bất kể lượt bot liền trước có câu mời nhẹ hay không!
        boolean isBareAck = CleanAgentOrchestratorService.isBareAcknowledgmentMessage(originalCustomerMessage);
        boolean isDeferOrHesitation = CleanAgentOrchestratorService.isHesitationOrDeferralMessage(originalCustomerMessage)
                || isCustomerDeferralMessage(originalCustomerMessage);
        boolean isObjectionOrComplaintTurn = hasGenuinePriceObjectionCue(originalCustomerMessage)
                || CleanAgentOrchestratorService.hasCompetitorOrExternalPriceComparisonCue(originalCustomerMessage)
                || CleanAgentOutputRefereeAndSanitizer.isCommunicationStyleComplaintMessage(originalCustomerMessage)
                || CleanAgentOutputRefereeAndSanitizer.isProcessOrConsultationFeedbackComplaint(originalCustomerMessage);
        if ((isBareAck || isDeferOrHesitation) && safePrior.status() == ConversationState.BookingStatus.NONE) {
            return withCandidateProducts(safePrior, earlyCandidateIds, safeZone);
        }
        if (isDeferOrHesitation && safePrior.status() == ConversationState.BookingStatus.AWAITING_CUSTOMER_CONFIRMATION) {
            return withCandidateProducts(safePrior, earlyCandidateIds, safeZone);
        }
        // P0.3 Guard: Nếu trạng thái trước đó mới ở mức AWAITING_DATE_OR_TIME (hoặc chưa có ngày/giờ xác nhận)
        // nhưng ở lượt này khách phản đối giá ("Đắt"), so sánh đối thủ, phàn nàn hoặc xin tạm hoãn mà không hẹn ngày/giờ,
        // hạ ngay trạng thái đặt lịch về NONE và xóa selectedProductId chưa xác nhận để tránh rò rỉ sang FALLBACK_BOOKING_HOLD.
        if ((isObjectionOrComplaintTurn || isDeferOrHesitation)
                && !CleanAgentOrchestratorService.hasProactiveBookingOrPackageSelectionInMessage(originalCustomerMessage)
                && safePrior.status() != ConversationState.BookingStatus.CONFIRMED
                && safePrior.bookingDate() == null
                && safePrior.bookingTime() == null) {
            ConversationState.StructuredBookingState clearedUnconfirmed = new ConversationState.StructuredBookingState(
                    null,
                    null,
                    null,
                    null,
                    null,
                    safeZone.getId(),
                    ConversationState.BookingStatus.NONE,
                    List.copyOf(earlyCandidateIds)
            );
            return clearedUnconfirmed;
        }

        // ACI V3: Phân giải lựa chọn tương đối của khách ("vừa đủ để thử", "cái thứ hai", "chỉ muốn massage", "gói đó"...)
        // dựa trên các gói THỰC SỰ vừa được giới thiệu — trước khi kiểm tra hasPatch để không đánh rơi lựa chọn.
        boolean isRelativeOrCorrectionSelection = isTrialOrRelativeOptionSelectionMessage(originalCustomerMessage)
                || isCustomerCorrectionOrComponentRejectionMessage(originalCustomerMessage);
        PackageCatalogService.PackageSummary contextualResolvedPkg = isRelativeOrCorrectionSelection
                ? resolveContextualOptionFromUtterance(originalCustomerMessage, precedingBotText, presentedPackagesInHistory, allCatalog)
                : null;
        if (contextualResolvedPkg == null && isRelativeOrCorrectionSelection
                && safePrior.selectedProductId() != null && !safePrior.selectedProductId().isBlank()
                && originalCustomerMessage != null
                && Normalizer.normalize(originalCustomerMessage.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT)
                        .matches("(?isU).*\\b(?:gói\\s+đó|cái\\s+đó|gói\\s+này|cái\\s+(?:em|bạn|shop)\\s+vừa\\s+nói)\\b.*")) {
            contextualResolvedPkg = resolveCatalogProductByFlexibleIdentifier(
                    safePrior.selectedProductId(), presentedPackagesInHistory, allCatalog);
        }

        PackageCatalogService.PackageSummary explicitPkgInMsg = resolveCatalogProductByFlexibleIdentifier(
                originalCustomerMessage, presentedPackagesInHistory, allCatalog);
        boolean hasExplicitBookingCue = CleanAgentOrchestratorService.hasProactiveBookingOrPackageSelectionInMessage(originalCustomerMessage);
        boolean activeBookingSession = safePrior.status() != ConversationState.BookingStatus.NONE;
        ConversationState.ParsedBookingDateTime parsedDirectFromMsg = (activeBookingSession || hasExplicitBookingCue)
                ? ConversationState.parseBookingDateTime(
                        originalCustomerMessage,
                        null,
                        null,
                        safeZone,
                        LocalDate.now(safeZone))
                : new ConversationState.ParsedBookingDateTime(null, null, null, safeZone.getId());
        boolean hasDirectDateOrTimeInMsg = parsedDirectFromMsg.bookingDate() != null || parsedDirectFromMsg.bookingTime() != null;

        boolean hasPatch = patch != null && patch.hasAnyProposal();
        if (!hasPatch && contextualResolvedPkg == null && explicitPkgInMsg == null && !hasDirectDateOrTimeInMsg && !hasExplicitBookingCue) {
            return withCandidateProducts(safePrior, earlyCandidateIds, safeZone);
        }

        // Gói đã được phân giải ở lượt này (ưu tiên lựa chọn tường minh/ngữ cảnh của khách hơn đề xuất của LLM để tránh upsell hoặc tự đoán khi có >= 2 gói).
        PackageCatalogService.PackageSummary resolvedSelectionPkg = contextualResolvedPkg != null
                ? contextualResolvedPkg
                : explicitPkgInMsg;
        boolean ambiguousMultiProductSelection = (safePrior.selectedProductId() == null || safePrior.selectedProductId().isBlank())
                && distinctPresented.size() >= 2
                && contextualResolvedPkg == null
                && explicitPkgInMsg == null;
        if (resolvedSelectionPkg == null && !ambiguousMultiProductSelection && patch != null && patch.selectedProductId() != null) {
            resolvedSelectionPkg = resolveCatalogProductByFlexibleIdentifier(
                    patch.selectedProductId(), presentedPackagesInHistory, allCatalog);
        }

        // Chặn nhảy cóc sang AWAITING_DATE_OR_TIME khi khách mới EXPLORING / CONSIDERING (ví dụ: "Mình muốn hỏi gói dưỡng sinh 239k"):
        // Khi chưa có lịch mở (status == NONE) và khách chưa nêu bất kỳ ngày/giờ hẹn nào, chỉ cho phép mở AWAITING_DATE_OR_TIME
        // khi khách thực sự ở trạng thái READY_TO_BOOK (hoặc ưu tiên chính là BOOKING_COMPLETION).
        // P3.3 Mục 2.4 (TC05, TC06, TC07): Nếu khách chỉ đang chê giá ("Giá 399k cao quá"), hỏi thông tin gói ("Gói 399k gồm những gì?"),
        // hoặc hoãn quyết định ("Để tôi xem đã") mà chưa có động từ chọn gói/đặt lịch -> KHÔNG khóa vào selectedProductId!
        boolean hasDateOrTimeProposal = (patch != null && (patch.proposedDate() != null || patch.proposedTime() != null))
                || hasDirectDateOrTimeInMsg;
        boolean nonSelectionMention = !hasExplicitBookingCue
                && !isTrialOrRelativeOptionSelectionMessage(originalCustomerMessage)
                && (hasGenuinePriceObjectionCue(originalCustomerMessage)
                    || isDeferOrHesitation
                    || isPurePackageInformationInquiry(originalCustomerMessage)
                    || isBareBodyAreaScopePhraseWithoutSymptoms(originalCustomerMessage));
        if (safePrior.status() == ConversationState.BookingStatus.NONE && !hasDateOrTimeProposal && !hasExplicitBookingCue) {
            if (nonSelectionMention) {
                return withCandidateProducts(safePrior, earlyCandidateIds, safeZone);
            }
            boolean notReadyToBook = (bookingReadiness != null && bookingReadiness != BookingReadiness.READY_TO_BOOK)
                    || (bookingReadiness == null
                        && primaryPriority != null
                        && !primaryPriority.isBlank()
                        && !"BOOKING_COMPLETION".equalsIgnoreCase(primaryPriority.strip()));
            if (notReadyToBook || (!hasPatch && contextualResolvedPkg != null)) {
                if (resolvedSelectionPkg != null) {
                    // TUYỆT ĐỐI không vứt bỏ lựa chọn của khách: lưu selectedProductId nhưng giữ nguyên trạng thái lịch.
                    return new ConversationState.StructuredBookingState(
                            resolvedSelectionPkg.productId(),
                            resolvedSelectionPkg.canonicalLabel(),
                            safePrior.bookingDate(),
                            safePrior.resolvedIsoDate(),
                            safePrior.bookingTime(),
                            safeZone.getId(),
                            safePrior.status(),
                            List.copyOf(earlyCandidateIds)
                    );
                }
                return withCandidateProducts(safePrior, earlyCandidateIds, safeZone);
            }
        }

        // Nếu lịch đã CONFIRMED thực sự từ nguồn có thẩm quyền và khách không đổi sản phẩm/ngày/giờ -> giữ nguyên CONFIRMED
        String nextProductId = safePrior.selectedProductId();
        String nextProductLabel = safePrior.selectedProductLabel();
        if (resolvedSelectionPkg != null) {
            nextProductId = resolvedSelectionPkg.productId();
            nextProductLabel = resolvedSelectionPkg.canonicalLabel();
        } else if ((nextProductId == null || nextProductId.isBlank()) && (hasDateOrTimeProposal || hasExplicitBookingCue)) {
            // Nếu lịch sử chỉ đang trao đổi đúng 1 gói duy nhất (không mơ hồ giữa >= 2 sản phẩm), kế thừa gói đó một cách xác định.
            if (distinctPresented.size() == 1) {
                PackageCatalogService.PackageSummary solePresented = distinctPresented.get(0);
                nextProductId = solePresented.productId();
                nextProductLabel = solePresented.canonicalLabel();
            } else if (distinctPresented.isEmpty() && allCatalog != null && allCatalog.size() == 1 && allCatalog.get(0) != null) {
                PackageCatalogService.PackageSummary soleCatalog = allCatalog.get(0);
                nextProductId = soleCatalog.productId();
                nextProductLabel = soleCatalog.canonicalLabel();
            }
        }

        String nextDate = safePrior.bookingDate();
        String nextIsoDate = safePrior.resolvedIsoDate();
        String nextTime = safePrior.bookingTime();

        if (hasDateOrTimeProposal) {
            String combinedTimeInput = patch != null
                    ? ((patch.proposedDate() != null ? patch.proposedDate() : "")
                            + " " + (patch.proposedTime() != null ? patch.proposedTime() : "")).strip()
                    : "";
            ConversationState.ParsedBookingDateTime parsedFromPatch = !combinedTimeInput.isBlank()
                    ? ConversationState.parseBookingDateTime(
                            combinedTimeInput,
                            nextDate,
                            nextTime,
                            safeZone,
                            LocalDate.now(safeZone))
                    : new ConversationState.ParsedBookingDateTime(null, null, null, safeZone.getId());
            // Cũng đối chiếu với tin nhắn gốc để lấy mốc ngày tương đối chính xác (VD: "tối nay lúc 19h" -> bookingDate="tối nay", bookingTime="19:00")
            ConversationState.ParsedBookingDateTime parsedFromMsg = ConversationState.parseBookingDateTime(
                    originalCustomerMessage,
                    null,
                    null,
                    safeZone,
                    LocalDate.now(safeZone)
            );

            if (patch != null && patch.proposedDate() != null) {
                nextDate = parsedFromMsg.bookingDate() != null ? parsedFromMsg.bookingDate()
                        : (parsedFromPatch.bookingDate() != null ? parsedFromPatch.bookingDate() : patch.proposedDate());
                nextIsoDate = parsedFromMsg.resolvedIsoDate() != null ? parsedFromMsg.resolvedIsoDate()
                        : parsedFromPatch.resolvedIsoDate();
            } else if (parsedFromMsg.bookingDate() != null) {
                nextDate = parsedFromMsg.bookingDate();
                nextIsoDate = parsedFromMsg.resolvedIsoDate();
            }

            if (patch != null && patch.proposedTime() != null) {
                nextTime = parsedFromPatch.bookingTime() != null
                        ? parsedFromPatch.bookingTime()
                        : (parsedFromMsg.bookingTime() != null ? parsedFromMsg.bookingTime() : normalizeClockString(patch.proposedTime()));
                if (nextDate == null) {
                    if (parsedFromMsg.bookingDate() != null) {
                        nextDate = parsedFromMsg.bookingDate();
                        nextIsoDate = parsedFromMsg.resolvedIsoDate();
                    } else {
                        nextDate = "hôm nay";
                        nextIsoDate = LocalDate.now(safeZone).toString();
                    }
                }
            } else if (parsedFromMsg.bookingTime() != null) {
                nextTime = parsedFromMsg.bookingTime();
                if (nextDate == null) {
                    nextDate = "hôm nay";
                    nextIsoDate = LocalDate.now(safeZone).toString();
                }
            }
        }

        boolean changedAnySlot = !Objects.equals(nextProductId, safePrior.selectedProductId())
                || !Objects.equals(nextDate, safePrior.bookingDate())
                || !Objects.equals(nextTime, safePrior.bookingTime());
        if (!changedAnySlot && safePrior.status() != ConversationState.BookingStatus.NONE) {
            return safePrior;
        }

        List<String> candidateIds = new ArrayList<>();
        for (PackageCatalogService.PackageSummary p : distinctPresented) {
            candidateIds.add(p.productId());
        }

        ConversationState.BookingStatus nextStatus;
        if (nextProductId == null || nextProductId.isBlank()) {
            if (nextDate != null || nextTime != null || (hasExplicitBookingCue && distinctPresented.size() >= 2)) {
                nextStatus = ConversationState.BookingStatus.AWAITING_PRODUCT_SELECTION;
            } else {
                nextStatus = ConversationState.BookingStatus.NONE;
            }
        } else if (nextTime != null && !nextTime.isBlank()) {
            if (nextDate == null || nextDate.isBlank()) {
                nextDate = "hôm nay";
                nextIsoDate = LocalDate.now(safeZone).toString();
            }
            // Cấm tuyệt đối tự nâng lên CONFIRMED khi chưa kiểm tra lịch trống thực tế!
            nextStatus = ConversationState.BookingStatus.AWAITING_AVAILABILITY_CHECK;
        } else {
            nextStatus = ConversationState.BookingStatus.AWAITING_DATE_OR_TIME;
        }

        return new ConversationState.StructuredBookingState(
                nextProductId,
                nextProductLabel,
                nextDate,
                nextIsoDate,
                nextTime,
                safeZone.getId(),
                nextStatus,
                List.copyOf(candidateIds)
        );
    }

    private static final Pattern RELATIVE_SECOND_OPTION_PATTERN = Pattern.compile(
            "(?isU).*\\b(?:cái|gói|loại|lựa\\s+chọn|phương\\s+án)\\s+(?:thứ\\s+(?:hai|2)|sau)\\b.*");
    private static final Pattern RELATIVE_FIRST_OPTION_PATTERN = Pattern.compile(
            "(?isU).*\\b(?:cái|gói|loại|lựa\\s+chọn|phương\\s+án)\\s+(?:đầu(?:\\s+tiên)?|thứ\\s+(?:nhất|1)|trước)\\b.*");
    private static final Pattern TRIAL_OR_BASIC_OPTION_PATTERN = Pattern.compile(
            "(?isU).*(?:vừa\\s+đủ\\s+để\\s+thử|(?:gói|cái|buổi)\\s+(?:vừa\\s+đủ|nhẹ\\s+nhàng(?:\\s+hơn)?)|(?:thử|làm|chọn|lấy)\\s+(?:buổi|gói|cái)?\\s*(?:vừa\\s+đủ|nhẹ\\s+nhàng(?:\\s+hơn)?)|gói\\s+cơ\\s+bản|cái\\s+cơ\\s+bản|cơ\\s+bản\\s+để\\s+thử|thử\\s+gói\\s+nhỏ|gói\\s+nhỏ|trải\\s+nghiệm\\s+thử|làm\\s+thử|để\\s+thử).*");
    private static final Pattern HAIR_COMPONENT_REJECTION_PATTERN = Pattern.compile(
            "(?isU).*(?:(?:không|ko|khỏi|bỏ|chẳng)\\s+(?:muốn\\s+|cần\\s+|thích\\s+)?(?:phần\\s+)?(?:chăm\\s+sóc\\s+tóc|phục\\s+hồi\\s+tóc|tóc|gội)"
                    + "|(?:sao|ủa)[^?？]{0,25}phục\\s+hồi\\s+tóc|loại\\s+không\\s+có\\s+phần\\s+đó|chỉ\\s+(?:muốn\\s+)?massage|chủ\\s+yếu\\s+(?:là\\s+)?massage).*");
    private static final Pattern REFERENCE_LAST_MENTIONED_PATTERN = Pattern.compile(
            "(?isU).*\\b(?:cái|gói)\\s+(?:(?:em|bạn|shop)\\s+vừa\\s+nói|đó|này)\\b.*");

    private static String packageSearchTextLower(PackageCatalogService.PackageSummary p) {
        StringBuilder sb = new StringBuilder();
        if (p.displayName() != null) sb.append(p.displayName()).append(' ');
        sb.append(p.canonicalName()).append(' ');
        if (p.serviceFamily() != null) sb.append(p.serviceFamily()).append(' ');
        if (p.includesSummary() != null) sb.append(p.includesSummary()).append(' ');
        return Normalizer.normalize(sb.toString(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private static boolean packageHasHairComponent(PackageCatalogService.PackageSummary p) {
        return packageSearchTextLower(p).matches("(?isU).*\\b(?:tóc|gội)\\b.*");
    }

    private static boolean packageIsEarCare(PackageCatalogService.PackageSummary p) {
        return packageSearchTextLower(p).matches("(?isU).*\\b(?:ráy\\s+tai|lấy\\s+ráy)\\b.*");
    }

    /** Các gói được nhắc trong lượt bot liền trước, theo thứ tự xuất hiện trong văn bản. */
    private static List<PackageCatalogService.PackageSummary> packagesMentionedInBotText(
            String precedingBotText,
            List<PackageCatalogService.PackageSummary> presented,
            List<PackageCatalogService.PackageSummary> allCatalog
    ) {
        if (precedingBotText == null || precedingBotText.isBlank()) {
            return List.of();
        }
        String botLow = Normalizer.normalize(precedingBotText, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        Map<String, Integer> firstIndexById = new HashMap<>();
        Map<String, PackageCatalogService.PackageSummary> byId = new LinkedHashMap<>();
        List<PackageCatalogService.PackageSummary> pool = new ArrayList<>();
        if (presented != null) pool.addAll(presented);
        if (allCatalog != null) pool.addAll(allCatalog);
        for (PackageCatalogService.PackageSummary p : pool) {
            if (p == null || p.productId() == null || p.productId().isBlank() || byId.containsKey(p.productId())) continue;
            int idx = -1;
            String name = p.canonicalName().toLowerCase(Locale.ROOT).strip();
            if (name.length() >= 4) idx = botLow.indexOf(name);
            if (idx < 0 && p.code() != null && p.code().strip().length() >= 3) {
                idx = botLow.indexOf(p.code().strip().toLowerCase(Locale.ROOT));
            }
            if (idx >= 0) {
                byId.put(p.productId(), p);
                firstIndexById.put(p.productId(), idx);
            }
        }
        List<PackageCatalogService.PackageSummary> ordered = new ArrayList<>(byId.values());
        ordered.sort(Comparator.comparingInt(p -> firstIndexById.getOrDefault(p.productId(), Integer.MAX_VALUE)));
        return ordered;
    }

    /**
     * ACI V3: Phân giải câu lựa chọn tương đối của khách ("vừa đủ để thử", "cái thứ hai", "gói đầu",
     * "loại không có phần đó", "chỉ muốn massage", "gói đó") dựa trên các gói THỰC SỰ vừa được giới thiệu.
     * Trả về {@code null} nếu không phân giải được chắc chắn (caller tự giữ {@code safePrior.selectedProductId()}).
     */
    public static PackageCatalogService.PackageSummary resolveContextualOptionFromUtterance(
            String originalCustomerMessage,
            String precedingBotText,
            List<PackageCatalogService.PackageSummary> presentedPackagesInHistory,
            List<PackageCatalogService.PackageSummary> allCatalog
    ) {
        return resolveContextualOptionFromUtterance(
                originalCustomerMessage, precedingBotText, presentedPackagesInHistory, allCatalog, false);
    }

    /**
     * ACI V3: Overload có {@code excludeHairCare} — khi khách đã từ chối chăm sóc/phục hồi tóc (negative_preferences: hair_care),
     * loại bỏ mọi gói có thành phần tóc/gội khỏi phân giải lựa chọn "vừa đủ để thử".
     */
    public static PackageCatalogService.PackageSummary resolveContextualOptionFromUtterance(
            String originalCustomerMessage,
            String precedingBotText,
            List<PackageCatalogService.PackageSummary> presentedPackagesInHistory,
            List<PackageCatalogService.PackageSummary> allCatalog,
            boolean excludeHairCare
    ) {
        if (originalCustomerMessage == null || originalCustomerMessage.isBlank()) {
            return null;
        }
        String msgLow = Normalizer.normalize(originalCustomerMessage.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);

        List<PackageCatalogService.PackageSummary> offered = new ArrayList<>(
                packagesMentionedInBotText(precedingBotText, presentedPackagesInHistory, allCatalog));
        if (offered.isEmpty() && presentedPackagesInHistory != null) {
            for (PackageCatalogService.PackageSummary p : presentedPackagesInHistory) {
                if (p != null && p.productId() != null && !p.productId().isBlank()) offered.add(p);
            }
        }
        List<PackageCatalogService.PackageSummary> safeCatalog = allCatalog != null
                ? allCatalog.stream().filter(Objects::nonNull).toList()
                : List.of();

        // 1) Chọn theo thứ tự trong danh sách vừa giới thiệu
        if (RELATIVE_SECOND_OPTION_PATTERN.matcher(msgLow).matches() && offered.size() >= 2) {
            return offered.get(1);
        }
        if (RELATIVE_FIRST_OPTION_PATTERN.matcher(msgLow).matches() && !offered.isEmpty()) {
            return offered.get(0);
        }

        boolean talksAboutEar = msgLow.matches("(?isU).*\\b(?:tai|ráy)\\b.*");
        boolean rejectsHair = HAIR_COMPONENT_REJECTION_PATTERN.matcher(msgLow).matches();
        boolean wantsTrialOrBasic = TRIAL_OR_BASIC_OPTION_PATTERN.matcher(msgLow).matches();

        // 2) Từ chối thành phần tóc / chỉ muốn massage -> gói thuần massage
        if (rejectsHair) {
            boolean neckFocus = msgLow.matches("(?isU).*\\b(?:cổ|vai|gáy)\\b.*");
            List<PackageCatalogService.PackageSummary> pool = new ArrayList<>();
            for (PackageCatalogService.PackageSummary p : offered) {
                if (!p.isAddon() && !packageHasHairComponent(p) && !packageIsEarCare(p)) pool.add(p);
            }
            if (pool.isEmpty()) {
                for (PackageCatalogService.PackageSummary p : safeCatalog) {
                    if (!p.isAddon() && !packageHasHairComponent(p) && !packageIsEarCare(p)
                            && packageSearchTextLower(p).matches("(?isU).*\\b(?:massage|mát\\s*xa)\\b.*")) {
                        pool.add(p);
                    }
                }
            }
            if (pool.isEmpty()) {
                return null;
            }
            Comparator<PackageCatalogService.PackageSummary> cmp = Comparator
                    .comparingInt((PackageCatalogService.PackageSummary p) -> neckFocus
                            && packageSearchTextLower(p).matches("(?isU).*\\b(?:cổ|vai|gáy)\\b.*") ? 0 : 1)
                    .thenComparingInt(p -> p.durationMinutes() != null && p.durationMinutes() >= 45 ? 0 : 1)
                    .thenComparingLong(PackageCatalogService.PackageSummary::priceAmountVnd);
            return pool.stream().min(cmp).orElse(null);
        }

        // 3) Vừa đủ để thử / nhẹ nhàng hơn / cơ bản -> gói cơ bản vừa đủ trong các gói vừa giới thiệu
        if (wantsTrialOrBasic) {
            List<PackageCatalogService.PackageSummary> pool = new ArrayList<>();
            for (PackageCatalogService.PackageSummary p : offered) {
                if (!p.isAddon() && (talksAboutEar || !packageIsEarCare(p))
                        && (!excludeHairCare || !packageHasHairComponent(p))) {
                    pool.add(p);
                }
            }
            if (pool.isEmpty() && excludeHairCare) {
                boolean neckFocusTrial = msgLow.matches("(?isU).*\\b(?:cổ|vai|gáy)\\b.*");
                for (PackageCatalogService.PackageSummary p : safeCatalog) {
                    if (p.isAddon() || packageHasHairComponent(p) || packageIsEarCare(p)) continue;
                    String searchLow = packageSearchTextLower(p);
                    if (!searchLow.matches("(?isU).*\\b(?:massage|mát\\s*xa)\\b.*")) continue;
                    if (neckFocusTrial && !searchLow.matches("(?isU).*\\b(?:cổ|vai|gáy)\\b.*")) continue;
                    pool.add(p);
                }
                if (pool.isEmpty() && neckFocusTrial) {
                    for (PackageCatalogService.PackageSummary p : safeCatalog) {
                        if (!p.isAddon() && !packageHasHairComponent(p) && !packageIsEarCare(p)
                                && packageSearchTextLower(p).matches("(?isU).*\\b(?:massage|mát\\s*xa)\\b.*")) {
                            pool.add(p);
                        }
                    }
                }
            }
            if (pool.isEmpty()) {
                return null;
            }
            List<PackageCatalogService.PackageSummary> fullSessions = pool.stream()
                    .filter(p -> p.durationMinutes() == null || p.durationMinutes() >= 45)
                    .toList();
            List<PackageCatalogService.PackageSummary> effective = fullSessions.isEmpty() ? pool : fullSessions;
            return effective.stream()
                    .min(Comparator.comparingLong(PackageCatalogService.PackageSummary::priceAmountVnd)
                            .thenComparingInt(p -> p.durationMinutes() != null ? p.durationMinutes() : Integer.MAX_VALUE))
                    .orElse(null);
        }

        // 4) "Gói đó" / "cái em vừa nói" -> gói được nhắc gần nhất trong lượt bot liền trước
        if (REFERENCE_LAST_MENTIONED_PATTERN.matcher(msgLow).matches()) {
            if (offered.size() == 1) {
                return offered.get(0);
            }
            if (precedingBotText != null && offered.size() > 1) {
                String botLow = Normalizer.normalize(precedingBotText, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
                PackageCatalogService.PackageSummary last = null;
                int lastIdx = -1;
                for (PackageCatalogService.PackageSummary p : offered) {
                    int idx = botLow.lastIndexOf(p.canonicalName().toLowerCase(Locale.ROOT).strip());
                    if (idx > lastIdx) {
                        lastIdx = idx;
                        last = p;
                    }
                }
                return last;
            }
        }
        return null;
    }

    public static PackageCatalogService.PackageSummary resolveCatalogProductByFlexibleIdentifier(
            String rawId,
            List<PackageCatalogService.PackageSummary> presentedInHistory,
            List<PackageCatalogService.PackageSummary> allCatalog
    ) {
        if (rawId == null || rawId.isBlank() || "null".equalsIgnoreCase(rawId.strip())) {
            return null;
        }
        String q = rawId.strip();
        String qLower = Normalizer.normalize(q, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);

        List<PackageCatalogService.PackageSummary> pool = new ArrayList<>();
        if (presentedInHistory != null) {
            for (PackageCatalogService.PackageSummary p : presentedInHistory) {
                if (p != null && !p.isAddon() && !pool.contains(p)) {
                    pool.add(p);
                }
            }
        }
        if (allCatalog != null) {
            for (PackageCatalogService.PackageSummary p : allCatalog) {
                if (p != null && !p.isAddon() && !pool.contains(p)) {
                    pool.add(p);
                }
            }
        }
        if (pool.isEmpty()) {
            return null;
        }

        // 1. Exact productId / SKU code / packageUid match
        for (PackageCatalogService.PackageSummary p : pool) {
            if (q.equalsIgnoreCase(p.productId())
                    || (p.code() != null && q.equalsIgnoreCase(p.code().strip()))
                    || (p.packageUid() != null && q.equalsIgnoreCase(p.packageUid().strip()))) {
                return p;
            }
        }

        // 2. Price match (e.g. "399k", "399000", "gói 239k")
        Set<Long> prices = CleanAgentOutputRefereeAndSanitizer.extractPricesInVndFromText(q);
        if (prices.isEmpty() && q.matches("\\d{5,8}")) {
            try {
                prices = Set.of(Long.parseLong(q));
            } catch (NumberFormatException ignored) {
            }
        }
        if (prices.size() == 1) {
            long targetVnd = prices.iterator().next();
            for (PackageCatalogService.PackageSummary p : pool) {
                if (p.priceAmountVnd() == targetVnd) {
                    return p;
                }
            }
        }

        // 3. Canonical / display name containment match
        for (PackageCatalogService.PackageSummary p : pool) {
            String disp = p.displayName() != null ? Normalizer.normalize(p.displayName(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT) : "";
            String canon = p.canonicalName() != null ? Normalizer.normalize(p.canonicalName(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT) : "";
            String codeLow = p.code() != null ? p.code().strip().toLowerCase(Locale.ROOT) : "";
            if ((!codeLow.isEmpty() && qLower.contains(codeLow))
                    || (!disp.isEmpty() && (qLower.contains(disp) || disp.contains(qLower)))
                    || (!canon.isEmpty() && (qLower.contains(canon) || canon.contains(qLower)))) {
                return p;
            }
        }
        return null;
    }

    private static String normalizeClockString(String rawTime) {
        if (rawTime == null || rawTime.isBlank()) {
            return null;
        }
        String t = rawTime.strip().toLowerCase(Locale.ROOT);
        Matcher hm = Pattern.compile("(\\d{1,2})\\s*(?:h|g|giờ|:)\\s*(\\d{0,2})").matcher(t);
        if (hm.find()) {
            int hour = Integer.parseInt(hm.group(1));
            int min = (hm.group(2) != null && !hm.group(2).isEmpty()) ? Integer.parseInt(hm.group(2)) : 0;
            if (t.contains("rưỡi") && min == 0) {
                min = 30;
            }
            if ((t.contains("tối") || t.contains("chiều") || t.contains("pm")) && hour >= 1 && hour <= 11) {
                hour += 12;
            }
            if (hour >= 0 && hour <= 23 && min >= 0 && min <= 59) {
                return String.format(Locale.ROOT, "%02d:%02d", hour, min);
            }
        }
        return rawTime.strip();
    }

    private static String cleanNullable(String s) {
        if (s == null) return null;
        String trimmed = s.strip();
        if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed) || "none".equalsIgnoreCase(trimmed)) {
            return null;
        }
        return trimmed;
    }

    private static String readTextOrDefault(JsonNode node, String field, String defVal) {
        if (node == null || !node.has(field) || node.get(field) == null || node.get(field).isNull()) {
            return defVal;
        }
        JsonNode v = node.get(field);
        return v.isTextual() || v.isNumber() || v.isBoolean() ? v.asText().strip() : defVal;
    }

    private static double readDoubleOrDefault(JsonNode node, String field, double defVal) {
        if (node == null || !node.has(field) || node.get(field) == null || node.get(field).isNull()) {
            return defVal;
        }
        JsonNode v = node.get(field);
        if (v.isNumber()) {
            return v.asDouble();
        }
        if (v.isTextual()) {
            try {
                return Double.parseDouble(v.asText().strip());
            } catch (NumberFormatException ignored) {
            }
        }
        return defVal;
    }

    private static List<String> readStringList(JsonNode arrNode) {
        if (arrNode == null || arrNode.isNull()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        if (arrNode.isArray()) {
            for (JsonNode el : arrNode) {
                if (el != null && !el.isNull()) {
                    String txt = el.isTextual() ? el.asText().strip() : el.toString().strip();
                    if (!txt.isEmpty() && !"null".equalsIgnoreCase(txt)) {
                        out.add(txt);
                    }
                }
            }
        } else if (arrNode.isTextual()) {
            String txt = arrNode.asText().strip();
            if (!txt.isEmpty() && !"null".equalsIgnoreCase(txt)) {
                out.add(txt);
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static List<String> readFlexibleStatementsList(JsonNode arrNode) {
        if (arrNode == null || arrNode.isNull()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        if (arrNode.isArray()) {
            for (JsonNode el : arrNode) {
                if (el == null || el.isNull()) continue;
                if (el.isTextual()) {
                    String s = el.asText().strip();
                    if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) {
                        out.add(s);
                    }
                } else if (el.isObject()) {
                    String s = readTextOrDefault(el, "content", readTextOrDefault(el, "statement", ""));
                    if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) {
                        out.add(s);
                    }
                }
            }
        } else if (arrNode.isTextual()) {
            String s = arrNode.asText().strip();
            if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) {
                out.add(s);
            }
        }
        return Collections.unmodifiableList(out);
    }

    // =========================================================================
    // P3.7-B.2: LIGHTWEIGHT CUSTOMER UNDERSTANDING & NEXT ACTION PLANNER
    // =========================================================================

    /**
     * P3.7-B.2: 12 hành động tư vấn chính tắc của {@code Next Action Planner} ({@code Turn Action Selection}).
     * Mỗi lượt chọn đúng 1 hành động tư vấn chính phù hợp với trạng thái hội thoại.
     */
    public enum TurnAction {
        GREETING_AND_DISCOVERY,
        NEED_DISCOVERY,
        SERVICE_RECOMMENDATION,
        SPECIFIC_PRICE_ANSWER,
        PRICE_OBJECTION_HANDLING,
        VALUE_EXPLANATION,
        ALTERNATIVE_OFFER,
        RESPECTFUL_DEESCALATION,
        CUSTOMER_PAUSE_OR_ACKNOWLEDGEMENT,
        CUSTOMER_DEFERRAL,
        CUSTOMER_EXIT,
        FEEDBACK_RECOVERY,
        BOOKING_DISCUSSION,
        SELF_IDENTIFY_GENDER,
        CUSTOMER_CORRECTION_PIVOT,
        TRIAL_COMMITMENT_LOCK,
        SUITABILITY_INQUIRY_HANDOFF
    }

    /**
     * ACI V3: Các ý định chính tắc của lượt (tách biệt rạch ròi {@code PRICE_OBJECTION},
     * {@code COMPETITOR_COMPARISON}, {@code BUDGET_INQUIRY}, {@code PRIVACY_CONCERN},
     * {@code CUSTOMER_DEFERRAL}, {@code EXPLICIT_REFUSAL}, {@code SELF_IDENTIFY_GENDER} để một câu có thể mang nhiều ý định
     * mà không tự động biến tất cả thành phản đối giá).
     */
    public enum CanonicalTurnIntent {
        PRICE_OBJECTION,
        COMPETITOR_COMPARISON,
        BUDGET_INQUIRY,
        PRIVACY_CONCERN,
        CUSTOMER_DEFERRAL,
        EXPLICIT_REFUSAL,
        CUSTOMER_PAUSE_OR_ACKNOWLEDGEMENT,
        PROCESS_FEEDBACK,
        BOOKING_DISCUSSION,
        SCOPE_CONFIRMATION,
        SYMPTOM_OR_NEED_SHARED,
        SERVICE_OR_OPERATIONAL_INQUIRY,
        GENERAL_CONSULTATION_OR_GREETING,
        SELF_IDENTIFY_GENDER,
        CUSTOMER_CORRECTION,
        TRIAL_COMMITMENT_SELECTION,
        SUITABILITY_INQUIRY,
        PACKAGE_COMPARISON
    }

    /**
     * ACI V3: Snapshot bất biến của lượt hiện tại ({@code TurnContextSnapshot}).
     * Xây dựng đúng 1 lần mỗi lượt trong Orchestrator và chia sẻ đồng nhất giữa Prompt Builder và Guard/Sanitizer.
     */
    public record TurnContextSnapshot(
            String turnId,
            String originalMessage,
            String effectiveCustomerPronoun,
            boolean pronounLockedFromSelfReference,
            CanonicalTurnIntent primaryIntent,
            Set<CanonicalTurnIntent> detectedIntents,
            PriceIntentSubMode priceIntentSubMode,
            int priorPriceObjectionCount,
            int authoritativePriceObjectionCount,
            boolean incrementsPriceObjection,
            LightweightCustomerUnderstandingPlan plan,
            CustomerUnderstandingDraft2Snapshot draft2Snapshot,
            ConversationState.StructuredBookingState priorBookingState
    ) {
        public TurnContextSnapshot {
            turnId = turnId != null ? turnId : "T1";
            originalMessage = originalMessage != null ? originalMessage : "";
            effectiveCustomerPronoun = (effectiveCustomerPronoun != null && !effectiveCustomerPronoun.isBlank())
                    ? effectiveCustomerPronoun.strip()
                    : "mình";
            primaryIntent = primaryIntent != null ? primaryIntent : CanonicalTurnIntent.GENERAL_CONSULTATION_OR_GREETING;
            detectedIntents = detectedIntents != null ? Collections.unmodifiableSet(new LinkedHashSet<>(detectedIntents)) : Set.of(primaryIntent);
            priceIntentSubMode = priceIntentSubMode != null ? priceIntentSubMode : PriceIntentSubMode.NONE;
            priorBookingState = priorBookingState != null ? priorBookingState : ConversationState.StructuredBookingState.none();
        }
    }

    /**
     * P3.7-B.2: 4 phân loại {@code Price Intent} đa ngành (nạp động từ Catalog của Tenant).
     */
    public enum PriceIntentSubMode {
        NONE,
        GENERAL_PRICE_INQUIRY,
        SPECIFIC_PRICE_INQUIRY,
        BUDGET_OR_LOWEST_PRICE_INQUIRY,
        EXPLICIT_FULL_PRICE_LIST_REQUEST
    }

    public record LightweightCustomerState(
            List<String> needs,
            List<String> emotions,
            List<String> concerns,
            List<String> buyingSignals,
            List<String> frictionHistory,
            List<String> communicationSignals
    ) {
        public LightweightCustomerState {
            needs = needs != null ? List.copyOf(needs) : List.of();
            emotions = emotions != null ? List.copyOf(emotions) : List.of();
            concerns = concerns != null ? List.copyOf(concerns) : List.of();
            buyingSignals = buyingSignals != null ? List.copyOf(buyingSignals) : List.of();
            frictionHistory = frictionHistory != null ? List.copyOf(frictionHistory) : List.of();
            communicationSignals = communicationSignals != null ? List.copyOf(communicationSignals) : List.of();
        }
    }

    public record LightweightInferenceItem(
            String hypothesis,
            double confidence,
            List<String> evidence
    ) {
        public LightweightInferenceItem {
            hypothesis = hypothesis != null ? hypothesis.strip() : "NONE";
            confidence = Math.max(0.0, Math.min(1.0, confidence));
            evidence = evidence != null ? List.copyOf(evidence) : List.of();
        }
    }

    public record LightweightCustomerUnderstandingPlan(
            LightweightCustomerState customerState,
            List<LightweightInferenceItem> inferences,
            List<String> unknowns,
            TurnAction recommendedAction,
            PriceIntentSubMode priceIntentSubMode,
            String actionExecutionDirective,
            int consecutivePriceObjectionCount
    ) {
        public LightweightCustomerUnderstandingPlan {
            customerState = customerState != null
                    ? customerState
                    : new LightweightCustomerState(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
            inferences = inferences != null ? List.copyOf(inferences) : List.of();
            unknowns = unknowns != null ? List.copyOf(unknowns) : List.of();
            recommendedAction = recommendedAction != null ? recommendedAction : TurnAction.NEED_DISCOVERY;
            priceIntentSubMode = priceIntentSubMode != null ? priceIntentSubMode : PriceIntentSubMode.NONE;
            actionExecutionDirective = actionExecutionDirective != null ? actionExecutionDirective : "";
        }
    }

    private static final Pattern BARE_PAUSE_OR_ACK_MESSAGE_PATTERN = Pattern.compile(
            "(?iU)^\\s*(?:dạ\\s+|vâng\\s+)?(?:k|kk|ok|oke|oki|okie|okay|ừ|ừm|ờ|uh|uhm|vâng|dạ|rồi|"
                    + "ok\\s+em|ok\\s+shop|ok\\s+bạn|ok\\s+nhé|ok\\s+nha|ok\\s+ạ|vâng\\s+ạ|dạ\\s+vâng|"
                    + "để\\s+(?:mình|chị|anh|tôi|em)\\s+(?:xem|tham\\s+khảo|nghĩ|tính|cân\\s+nhắc)(?:\\s+thêm|\\s+đã|\\s+nhé|\\s+ạ)*|"
                    + "có\\s+gì\\s+(?:mình|chị|anh|tôi|em)\\s+(?:báo|nhắn)(?:\\s+lại|\\s+sau)?(?:\\s+nhé|\\s+ạ)*)\\s*[.!~]*\\s*$"
    );

    private static final Pattern CUSTOMER_DEFERRAL_MESSAGE_PATTERN = Pattern.compile(
            "(?iU)^\\s*(?:dạ\\s+|vâng\\s+|ừ\\s+|ờ\\s+|uh\\s+|ok\\s+|thôi\\s+)?(?:"
                    + "để\\s+(?:mình|chị|anh|tôi|t|tui|em)\\s+(?:xem|tham\\s+khảo|suy\\s+nghĩ|nghĩ|tính|cân\\s+nhắc|hỏi\\s+ý\\s+kiến|sắp\\s+xếp)[^?？]*|"
                    + "(?:để\\s+)?(?:xem|tham\\s+khảo|suy\\s+nghĩ|nghĩ|cân\\s+nhắc)(?:\\s+thêm)?\\s+(?:đã|chút|tí|xíu|sau)(?:\\s+nhé|\\s+nha|\\s+ạ|\\s+em|\\s+shop)*|"
                    + "(?:để\\s+)?tính\\s+sau(?:\\s+nhé|\\s+nha|\\s+ạ)*|"
                    + "có\\s+gì\\s+(?:mình|chị|anh|tôi|t|tui|em)\\s+(?:báo|nhắn|liên\\s+hệ)(?:\\s+lại|\\s+sau)?[^?？]*|"
                    + "khi\\s+nào\\s+(?:rảnh|tiện|sắp\\s+xếp\\s+được|cần)\\s+(?:mình|chị|anh|tôi|t|tui|em)\\s+(?:báo|nhắn|ghé)[^?？]*|"
                    + "(?:mình|chị|anh|tôi|t|tui|em)\\s+(?:xem|tham\\s+khảo|suy\\s+nghĩ|nghĩ|cân\\s+nhắc)(?:\\s+thêm)?(?:\\s+đã|\\s+chút|\\s+nhé|\\s+nha|\\s+ạ)+"
                    + ")\\s*[.!~]*\\s*$"
    );

    private static final Pattern PRIVACY_CONCERN_MESSAGE_PATTERN = Pattern.compile(
            "(?isU).*\\b(ngại\\s+chung\\s+phòng|phòng\\s+chung|giường\\s+chung|rèm\\s+che|vách\\s+ngăn|riêng\\s+tư|kín\\s+đáo|đông\\s+người|ồn\\s+ào|phòng\\s+riêng)\\b.*"
    );

    private static final Pattern GENUINE_PRICE_OBJECTION_CUE_PATTERN = Pattern.compile(
            "(?isU).*\\b(đắt|mắc|chát|cao\\s+quá|hơi\\s+cao|giá\\s+cao|cao\\s+thế|cao\\s+vậy|đắt\\s+thế|mắc\\s+thế|căng\\s+quá|hơi\\s+căng|đau\\s+ví|ví\\s+mỏng|quá\\s+sức|"
                    + "vượt\\s+ngân\\s+sách|quá\\s+ngân\\s+sách|không\\s+đủ\\s+tiền|chưa\\s+đủ\\s+tiền|bằng\\s+cả\\s+ngày\\s+đi\\s+làm|"
                    + "thôi\\s+chịu(?:\\s+đấy|\\s+thôi|\\s+luôn)?|chịu\\s+đấy|chịu\\s+chết|chiu\\s+chet|chịu\\s+luôn|bó\\s+tay|lạy\\s+chúa|"
                    + "rẻ\\s+hơn|mềm\\s+hơn|nhẹ\\s+hơn|nhẹ\\s+tiền\\s+hơn|tiết\\s+kiệm\\s+hơn)\\b.*"
    );

    private static final Pattern EXPLICIT_FULL_PRICE_LIST_REQUEST_PATTERN = Pattern.compile(
            "(?iU).*(?:bảng\\s+giá(?:\\s+đầy\\s+đủ|\\s+chi\\s+tiết)?|danh\\s+sách\\s+(?:giá|gói|dịch\\s+vụ)|"
                    + "tất\\s+cả\\s+các\\s+gói|đừng\\s+tư\\s+vấn\\s+dài[^.!?\\n]{0,30}giá|cho\\s+(?:tôi|mình|chị|anh)\\s+bảng\\s+giá).*"
    );

    private static final Pattern GENERAL_BARE_PRICE_INQUIRY_PATTERN = Pattern.compile(
            "(?iU)^\\s*(?:dạ\\s+|shop\\s+ơi\\s+|em\\s+ơi\\s+|bạn\\s+ơi\\s+)?(?:giá(?:\\s+sao|\\s+thế\\s+nào|\\s+như\\s+nào|\\s+bao\\s+nhiêu|\\s+cả\\s+sao)?|"
                    + "rổ\\s+giá(?:\\s+sao|\\s+thế\\s+nào)?|báo\\s+giá|xin\\s+giá)(?:\\s+(?:dịch\\s+vụ|bên\\s+mình|bên\\s+em|shop|ạ|nhé|với))*\\s*[.?]*\\s*$"
    );

    private static final Pattern BUDGET_OR_LOWEST_PRICE_INQUIRY_PATTERN = Pattern.compile(
            "(?iU).*(?:rẻ\\s+nhất|thấp\\s+nhất|tiết\\s+kiệm\\s+nhất|dưới\\s+\\d+|tầm\\s+\\d+[^.!?\\n]{0,20}(?:đổ\\s+lại|trở\\s+xuống)|"
                    + "có\\s+gói\\s+nào\\s+(?:dưới|tầm|khoảng|chỉ)?\\s*\\d+\\s*(?:k|nghìn|ngàn|tr|triệu)|"
                    + "ngân\\s+sách\\s+(?:dưới|tầm|khoảng)?\\s*\\d+).*"
    );

    private static final Pattern SCOPE_OR_DUAL_SELECTION_CONFIRMATION_PATTERN = Pattern.compile(
            "(?iU)^\\s*(?:dạ\\s+)?(?:cả\\s*(?:2|hai)|làm\\s+cả\\s*(?:2|hai)|cả\\s+hai\\s+luôn|"
                    + "gói\\s+(?:cơ\\s+bản|chuyên\\s+sâu|đó|này|đầu|thứ\\s+hai)|"
                    + "(?:ưu\\s+tiên|tập\\s+trung|chọn|làm|thử)\\s+[^?]{1,45}|"
                    + "(?:(?:buổi\\s+)?vừa\\s+đủ(?:\\s+ạ)?|nhẹ\\s+nhàng(?:\\s+hơn)?|chăm\\s+sóc\\s+kỹ\\s+hơn|nhanh\\s+gọn|chuyên\\s+sâu\\s+hơn))\\s*[.!]*\\s*$"
    );

    private static final Pattern CUSTOMER_CORRECTION_OR_COMPONENT_REJECTION_PATTERN = Pattern.compile(
            "(?isU).*(?:"
                    + "(?:ủa|sao)[^?？]{0,25}(?:lại\\s+)?(?:phục\\s+hồi\\s+tóc|chăm\\s+sóc\\s+tóc|gội|tóc)"
                    + "|kêu\\s+(?:đi\\s+)?làm\\s+(?:phục\\s+hồi\\s+tóc|chăm\\s+sóc\\s+tóc|tóc)"
                    + "|(?:không|ko|chẳng|chả)\\s+(?:muốn|cần|thích)\\s+(?:làm\\s+)?(?:phần\\s+)?(?:chăm\\s+sóc\\s+tóc|phục\\s+hồi\\s+tóc|tóc|gội(?:\\s+đầu)?|gói\\s+(?:đó|này|kia)|cái\\s+(?:đó|này))"
                    + "|(?:khỏi|bỏ)\\s+(?:phần\\s+|cái\\s+)?(?:chăm\\s+sóc\\s+tóc|phục\\s+hồi\\s+tóc|tóc|gội)"
                    + "|chỉ\\s+(?:muốn|cần|thích|làm|chọn)\\s+(?:massage|mát\\s*xa)"
                    + "|chủ\\s+yếu\\s+(?:là\\s+)?(?:massage|mát\\s*xa)"
                    + "|đổi\\s+(?:sang\\s+)?gói\\s+khác"
                    + "|loại\\s+không\\s+có\\s+phần\\s+đó"
                    + ").*"
    );

    private static final Pattern TRIAL_OR_RELATIVE_OPTION_SELECTION_PATTERN = Pattern.compile(
            "(?isU).*(?:"
                    + "vừa\\s+đủ\\s+để\\s+thử"
                    + "|(?:gói|cái|buổi)\\s+(?:vừa\\s+đủ|nhẹ\\s+nhàng(?:\\s+hơn)?)"
                    + "|(?:thử|làm|chọn|lấy)\\s+(?:buổi|gói|cái)?\\s*(?:vừa\\s+đủ|nhẹ\\s+nhàng(?:\\s+hơn)?)"
                    + "|(?:gói|cái)\\s+cơ\\s+bản"
                    + "|thử\\s+gói\\s+nhỏ"
                    + "|trải\\s+nghiệm\\s+thử"
                    + "|(?:cái|gói|loại)\\s+(?:thứ\\s+(?:hai|2|nhất|1)|sau|đầu(?:\\s+tiên)?)"
                    + "|(?:cái|gói)\\s+(?:em|bạn|shop)\\s+vừa\\s+nói"
                    + "|(?:làm|lấy|chọn)\\s+(?:gói|cái)\\s+(?:đó|này)"
                    + ").*"
    );

    /**
     * ACI V3: Khách đính chính hoặc từ chối một thành phần/hướng tư vấn
     * (VD: "ủa sao lại phục hồi tóc", "khỏi phần tóc", "chỉ muốn massage", "đổi sang gói khác").
     */
    public static boolean isCustomerCorrectionOrComponentRejectionMessage(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return false;
        }
        String low = Normalizer.normalize(rawMessage.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        if (low.matches("(?isU).*\\bcó\\b[^?？]+\\b(?:không\\s+hay|hay\\s+chỉ|hay\\s+là)\\b.*")) {
            return false;
        }
        return CUSTOMER_CORRECTION_OR_COMPONENT_REJECTION_PATTERN.matcher(low).matches()
                || CleanAgentOutputRefereeAndSanitizer.hasExplicitFactualDenialCorrection(rawMessage);
    }

    private static final Pattern PACKAGE_COMPARISON_CUE_PATTERN = Pattern.compile(
            "(?isU).*\\b(?:khác\\s+nhau|khác\\s+gì|khác\\s+sao|so\\s+sánh|phân\\s+biệt|"
                    + "(?:2|hai)\\s+gói\\s+(?:này|đó|kia)|"
                    + "gói\\s+[^?]{1,35}\\s+(?:với|và|hay)\\s+gói|"
                    + "\\d{2,4}(?:\\s*k|\\.[0-9]{3})\\s*đ?\\s+(?:với|và|hay)\\s+(?:gói\\s+)?\\d{2,4}(?:\\s*k|\\.[0-9]{3})).*"
    );

    public static boolean hasPackageComparisonCue(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return false;
        }
        String low = Normalizer.normalize(rawMessage.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        return PACKAGE_COMPARISON_CUE_PATTERN.matcher(low).matches();
    }

    private static final Pattern BARE_BODY_AREA_OR_DUAL_SCOPE_TOKEN_PATTERN = Pattern.compile(
            "(?isU).*\\b(?:đầu|cổ|vai|gáy|vau\\s+gáy|lưng|eo|thắt\\s+lưng|chân|bàn\\s+chân|bắp\\s+chân|tay|cánh\\s+tay|toàn\\s+thân|body|da\\s+mặt|mặt|da\\s+đầu|cả\\s*(?:2|hai))\\b.*"
    );

    private static final Pattern SYMPTOM_OR_MEDICAL_DISCOMFORT_VERB_PATTERN = Pattern.compile(
            "(?isU).*\\b(?:đau|mỏi|nhức|căng|tê|buốt|ê\\s+ẩm|hoa\\s+mắt|chóng\\s+mặt|choáng|mất\\s+ngủ|khó\\s+ngủ|ngủ\\s+không\\s+sâu|stress|mệt|uể\\s+oải|nặng\\s+đầu|rụng\\s+tóc|gàu|ngứa|bệnh|huyết\\s+áp|tiền\\s+đình|thoát\\s+vị|bầu|mang\\s+thai|sưng|viêm|sốt|nôn)\\b.*"
    );

    private static final Pattern NON_SCOPE_PRICE_OR_BOOKING_TOKEN_PATTERN = Pattern.compile(
            "(?isU).*\\b(?:giá|bao\\s+nhiêu|mấy\\s+tiền|\\d+\\s*k|đắt|rẻ|khác\\s+nhau|khác\\s+gì|so\\s+sánh|đặt\\s+lịch|book|mấy\\s+giờ|ở\\s+đâu|chi\\s+nhánh|cơ\\s+sở|gói\\s+(?:cơ\\s+bản|chuyên\\s+sâu|đó|này|thứ|đầu)|đầu\\s+tiên|ban\\s+đầu|lần\\s+đầu|bắt\\s+đầu|tư\\s+vấn|mặt\\s+bằng|chỉ\\s+định)\\b.*"
    );

    /**
     * Post-CP3 Wave 1 (1.2): Nhận diện cụm từ ngắn chỉ nêu phạm vi vùng cơ thể hoặc "cả hai"
     * mà KHÔNG chứa động từ triệu chứng/bệnh lý hay từ khóa hỏi giá/đặt lịch (VD: "Đầu và vai gáy", "Chân và lưng", "Cả hai").
     */
    public static boolean isBareBodyAreaScopePhraseWithoutSymptoms(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return false;
        }
        String norm = Normalizer.normalize(rawMessage.strip(), Normalizer.Form.NFC);
        if (norm.contains("?") || norm.contains("？")) {
            return false;
        }
        String[] words = norm.split("\\s+");
        if (words.length < 1 || words.length > 10) {
            return false;
        }
        String low = norm.toLowerCase(Locale.ROOT);
        if (!BARE_BODY_AREA_OR_DUAL_SCOPE_TOKEN_PATTERN.matcher(low).matches()) {
            return false;
        }
        if (SYMPTOM_OR_MEDICAL_DISCOMFORT_VERB_PATTERN.matcher(low).matches()) {
            return false;
        }
        if (NON_SCOPE_PRICE_OR_BOOKING_TOKEN_PATTERN.matcher(low).matches()) {
            return false;
        }
        return !CleanAgentOutputRefereeAndSanitizer.isCommunicationStyleComplaintMessage(norm)
                && !CleanAgentOutputRefereeAndSanitizer.isProcessOrConsultationFeedbackComplaint(norm)
                && !isCustomerCorrectionOrComponentRejectionMessage(norm);
    }

    public static boolean hasPriorBotScopeOrFocusQuestion(List<FanpageBotRequest.ConversationTurn> history) {
        if (history == null || history.isEmpty()) {
            return false;
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            FanpageBotRequest.ConversationTurn t = history.get(i);
            if (t == null || t.content() == null || t.content().isBlank()) continue;
            String role = t.role() != null ? t.role().strip().toLowerCase(Locale.ROOT) : "";
            if ("assistant".equals(role) || "bot".equals(role)) {
                String botNorm = Normalizer.normalize(t.content().strip(), Normalizer.Form.NFC);
                String botLow = botNorm.toLowerCase(Locale.ROOT);
                boolean hasQuestion = botNorm.contains("?") || botNorm.contains("？")
                        || botLow.matches("(?isU).*\\b(?:vùng\\s+nào|phần\\s+nào|chỗ\\s+nào|hướng\\s+nào|hay\\s+là)\\b.*");
                boolean asksScopeOrFocus = botLow.matches(
                        "(?isU).*\\b(?:vùng\\s+nào|vùng\\s+cơ\\s+thể\\s+nào|phần\\s+nào|ưu\\s+tiên|tập\\s+trung|chăm\\s+sóc|thư\\s+giãn|massage|gội\\s+đầu|cổ\\s+vai\\s+gáy|chân\\s+tay|lưng|toàn\\s+thân|vừa\\s+đủ\\s+hay|chuyên\\s+sâu)\\b.*"
                );
                return hasQuestion && asksScopeOrFocus;
            }
        }
        return false;
    }

    public static boolean isContextualShortScopeSelectionReply(
            String rawMessage,
            List<FanpageBotRequest.ConversationTurn> history,
            CustomerUnderstandingDraft2Snapshot draft2Snapshot
    ) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return false;
        }
        String norm = Normalizer.normalize(rawMessage.strip(), Normalizer.Form.NFC);
        boolean priorNeedsEstablished = hasCustomerSharedNeedsOrSymptomsInHistory(history, draft2Snapshot);
        if (priorNeedsEstablished && SCOPE_OR_DUAL_SELECTION_CONFIRMATION_PATTERN.matcher(norm).matches()) {
            return true;
        }
        if (hasPriorBotScopeOrFocusQuestion(history)
                && (isBareBodyAreaScopePhraseWithoutSymptoms(norm)
                    || SCOPE_OR_DUAL_SELECTION_CONFIRMATION_PATTERN.matcher(norm).matches())) {
            return true;
        }
        return false;
    }

    /**
     * ACI V3: Khách chọn phương án thử / lựa chọn tương đối
     * (VD: "tôi muốn vừa đủ để thử", "gói nhẹ nhàng hơn", "cái thứ hai", "cái em vừa nói").
     * Câu hỏi giá hoặc câu hỏi phù hợp sức khỏe KHÔNG được tính là chốt lựa chọn.
     */
    public static boolean isTrialOrRelativeOptionSelectionMessage(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return false;
        }
        String low = Normalizer.normalize(rawMessage.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        if (!TRIAL_OR_RELATIVE_OPTION_SELECTION_PATTERN.matcher(low).matches()) {
            return false;
        }
        if (low.matches("(?isU).*\\b(?:giá|bao\\s+nhiêu|mấy\\s+tiền|chi\\s+phí)\\b.*")) {
            return false;
        }
        return !isHealthSuitabilityInquiryMessage(rawMessage);
    }

    public static boolean isBarePauseOrAcknowledgementMessage(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return false;
        }
        String norm = Normalizer.normalize(rawMessage.strip(), Normalizer.Form.NFC);
        return BARE_PAUSE_OR_ACK_MESSAGE_PATTERN.matcher(norm).matches();
    }

    public static boolean isCustomerDeferralMessage(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return false;
        }
        String norm = Normalizer.normalize(rawMessage.strip(), Normalizer.Form.NFC);
        if (CleanAgentOrchestratorService.isExplicitConversationRefusalStopMessage(norm)) {
            return false;
        }
        return CUSTOMER_DEFERRAL_MESSAGE_PATTERN.matcher(norm).matches()
                || CleanAgentOrchestratorService.isHesitationOrDeferralMessage(norm)
                || CleanAgentOrchestratorService.isPureBusyOrStopDeferralMessage(norm);
    }

    public static boolean isPrivacyConcernMessage(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return false;
        }
        String norm = Normalizer.normalize(rawMessage.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        return PRIVACY_CONCERN_MESSAGE_PATTERN.matcher(norm).matches();
    }

    /**
     * ACI V3 Guardrail 3: Kiểm tra xem phát ngôn có thực sự mang ý phản đối giá / chê đắt / quá sức ngân sách hay không
     * (tách biệt với câu chỉ so sánh giá đối thủ bên ngoài hoặc hỏi gói theo ngân sách).
     */
    public static boolean hasGenuinePriceObjectionCue(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return false;
        }
        String norm = Normalizer.normalize(rawMessage.strip(), Normalizer.Form.NFC);
        if (CleanAgentOutputRefereeAndSanitizer.isCommunicationStyleComplaintMessage(norm)
                || CleanAgentOrchestratorService.isExplicitConversationRefusalStopMessage(norm)
                || CleanAgentOrchestratorService.isPureBudgetBoundedServiceInquiry(norm)
                || isPrivacyConcernMessage(norm)) {
            return false;
        }
        String low = norm.toLowerCase(Locale.ROOT);
        boolean isComp = CleanAgentOrchestratorService.hasCompetitorOrExternalPriceComparisonCue(norm);
        if (isComp) {
            // Khi khách đang so sánh giá đối thủ ("chỗ khác rẻ hơn", "tiệm kia mềm hơn"), chỉ tính là phản đối giá
            // nếu khách đồng thời chê đắt/mắc/chát/quá sức/chịu đấy đối với cơ sở mình.
            return low.matches("(?isU).*\\b(đắt|mắc|chát|cao\\s+quá|giá\\s+cao|cao\\s+thế|cao\\s+vậy|đắt\\s+thế|căng\\s+quá|đau\\s+ví|ví\\s+mỏng|quá\\s+sức|vượt\\s+ngân\\s+sách|quá\\s+ngân\\s+sách|không\\s+đủ\\s+tiền|thôi\\s+chịu|chịu\\s+đấy|chịu\\s+chết|chiu\\s+chet)\\b.*");
        }
        if (GENUINE_PRICE_OBJECTION_CUE_PATTERN.matcher(low).matches()) {
            return true;
        }
        if (isCustomerDeferralMessage(norm) || isBarePauseOrAcknowledgementMessage(norm)) {
            return false;
        }
        return CleanAgentOrchestratorService.isConfirmedCustomerPriceObjection(norm, true);
    }

    /**
     * ACI V3 Guardrail 3: Trích xuất tập hợp các ý định chính tắc từ phát ngôn hiện tại.
     * Một câu có thể mang nhiều ý định (VD: vừa {@code COMPETITOR_COMPARISON} vừa {@code PRICE_OBJECTION},
     * hoặc vừa {@code PRIVACY_CONCERN} vừa {@code SERVICE_OR_OPERATIONAL_INQUIRY}), nhưng KHÔNG tự động biến
     * {@code COMPETITOR_COMPARISON}, {@code BUDGET_INQUIRY}, {@code PRIVACY_CONCERN}, {@code CUSTOMER_DEFERRAL}
     * hay {@code EXPLICIT_REFUSAL} thành {@code PRICE_OBJECTION}.
     */
    public static Set<CanonicalTurnIntent> detectCanonicalTurnIntents(
            String rawUserMessage,
            List<FanpageBotRequest.ConversationTurn> history,
            CustomerUnderstandingDraft2Snapshot draft2Snapshot,
            ConversationState.StructuredBookingState bookingState
    ) {
        String msg = rawUserMessage != null ? Normalizer.normalize(rawUserMessage.strip(), Normalizer.Form.NFC) : "";
        if (msg.isEmpty()) {
            return Set.of(CanonicalTurnIntent.GENERAL_CONSULTATION_OR_GREETING);
        }
        String msgLow = msg.toLowerCase(Locale.ROOT);
        Set<CanonicalTurnIntent> intents = new LinkedHashSet<>();

        // ACI V3: Câu hỏi phù hợp sức khỏe -> ưu tiên tuyệt đối, chuyển chuyên viên Spa đánh giá.
        if (isHealthSuitabilityInquiryMessage(msg)) {
            intents.add(CanonicalTurnIntent.SUITABILITY_INQUIRY);
            return Collections.unmodifiableSet(new LinkedHashSet<>(intents));
        }

        // ACI V3: CUSTOMER_CORRECTION > CUSTOMER_COMMITMENT > BOT_RECOMMENDATION (thêm trước để trở thành primary intent).
        boolean isCustomerCorrection = isCustomerCorrectionOrComponentRejectionMessage(msg);
        boolean isTrialSelection = !isCustomerCorrection && isTrialOrRelativeOptionSelectionMessage(msg);
        if (isCustomerCorrection) {
            intents.add(CanonicalTurnIntent.CUSTOMER_CORRECTION);
        } else if (isTrialSelection) {
            intents.add(CanonicalTurnIntent.TRIAL_COMMITMENT_SELECTION);
        }

        boolean isStyleOrProcessFeedback = !isCustomerCorrection
                && (CleanAgentOutputRefereeAndSanitizer.isCommunicationStyleComplaintMessage(msg)
                    || CleanAgentOutputRefereeAndSanitizer.isProcessOrConsultationFeedbackComplaint(msg));
        if (isStyleOrProcessFeedback) {
            if (CleanAgentOutputRefereeAndSanitizer.isFix5MixedIntentRecoveryEnabled()
                    && (CleanAgentOutputRefereeAndSanitizer.hasExplicitPriceDemandInMessage(msg)
                        || CleanAgentOutputRefereeAndSanitizer.hasBusinessOrCatalogClarificationDemandOnComplaintTurn(msg))) {
                intents.add(CanonicalTurnIntent.SERVICE_OR_OPERATIONAL_INQUIRY);
                intents.add(CanonicalTurnIntent.PROCESS_FEEDBACK);
            } else {
                intents.add(CanonicalTurnIntent.PROCESS_FEEDBACK);
                return Collections.unmodifiableSet(new LinkedHashSet<>(intents));
            }
        }

        boolean explicitFullPriceList = EXPLICIT_FULL_PRICE_LIST_REQUEST_PATTERN.matcher(msgLow).matches();
        boolean isExplicitRefusal = !explicitFullPriceList
                && !isCustomerCorrection
                && !isTrialSelection
                && (CleanAgentOrchestratorService.isExplicitConversationRefusalStopMessage(msg)
                    || CleanAgentOrchestratorService.isPoliteThankYouClosingAckMessage(msg));
        if (isExplicitRefusal) {
            intents.add(CanonicalTurnIntent.EXPLICIT_REFUSAL);
            return Collections.unmodifiableSet(new LinkedHashSet<>(intents));
        }

        boolean hasBookingCue = (bookingState != null && bookingState.status() != ConversationState.BookingStatus.NONE)
                || msgLow.matches("(?isU).*\\b(đặt\\s+lịch|book\\s+lịch|giữ\\s+lịch|khung\\s+giờ|mấy\\s+giờ|chiều\\s+nay|tối\\s+nay|ngày\\s+mai)\\b.*");
        if (hasBookingCue) {
            intents.add(CanonicalTurnIntent.BOOKING_DISCUSSION);
        }

        boolean isCompComparison = CleanAgentOrchestratorService.hasCompetitorOrExternalPriceComparisonCue(msg);
        if (isCompComparison) {
            intents.add(CanonicalTurnIntent.COMPETITOR_COMPARISON);
        }

        boolean isBudgetInquiry = !isCompComparison
                && (CleanAgentOrchestratorService.isPureBudgetBoundedServiceInquiry(msg)
                    || BUDGET_OR_LOWEST_PRICE_INQUIRY_PATTERN.matcher(msgLow).matches());
        if (isBudgetInquiry) {
            intents.add(CanonicalTurnIntent.BUDGET_INQUIRY);
        }

        boolean isPrivacy = isPrivacyConcernMessage(msg);
        if (isPrivacy) {
            intents.add(CanonicalTurnIntent.PRIVACY_CONCERN);
        }

        boolean hasPriceObjection = !isBudgetInquiry && hasGenuinePriceObjectionCue(msg);
        if (hasPriceObjection) {
            intents.add(CanonicalTurnIntent.PRICE_OBJECTION);
        }
        boolean hasPkgComparison = hasPackageComparisonCue(msg);
        if (hasPkgComparison) {
            intents.add(CanonicalTurnIntent.PACKAGE_COMPARISON);
        }

        boolean isDeferral = !hasBookingCue && !isCompComparison && !hasPriceObjection && !hasPkgComparison && isCustomerDeferralMessage(msg);
        boolean isBarePause = !hasBookingCue && !isCompComparison && !hasPriceObjection && !hasPkgComparison && !isDeferral
                && isBarePauseOrAcknowledgementMessage(msg);
        if (isDeferral) {
            intents.add(CanonicalTurnIntent.CUSTOMER_DEFERRAL);
        } else if (isBarePause) {
            intents.add(CanonicalTurnIntent.CUSTOMER_PAUSE_OR_ACKNOWLEDGEMENT);
        }

        boolean isGenderSelfId = !hasBookingCue
                && !isCompComparison
                && !hasPriceObjection
                && !hasPkgComparison
                && !isDeferral
                && !isBarePause
                && classifyPriceIntentSubMode(msg) == PriceIntentSubMode.NONE
                && isExplicitGenderOrPronounCorrectionMessage(msg);
        if (isGenderSelfId) {
            intents.add(CanonicalTurnIntent.SELF_IDENTIFY_GENDER);
        }

        boolean priorNeedsEstablished = hasCustomerSharedNeedsOrSymptomsInHistory(history, draft2Snapshot);
        if (isContextualShortScopeSelectionReply(msg, history, draft2Snapshot)) {
            intents.add(CanonicalTurnIntent.SCOPE_CONFIRMATION);
        }

        if (intents.isEmpty()) {
            if (CleanAgentCatalogAndPromptBuilder.isBareTurnOneGreetingOrConsultationRequest(msg) && !priorNeedsEstablished) {
                intents.add(CanonicalTurnIntent.GENERAL_CONSULTATION_OR_GREETING);
            } else if (CleanAgentOutputRefereeAndSanitizer.isUnconfirmedHealthSymptomWithoutServiceDemand(msg, List.of())
                    || isConfirmedNeedOrSymptomUtteranceForLightweightState(msg)) {
                intents.add(CanonicalTurnIntent.SYMPTOM_OR_NEED_SHARED);
            } else {
                intents.add(CanonicalTurnIntent.SERVICE_OR_OPERATIONAL_INQUIRY);
            }
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(intents));
    }

    public static PriceIntentSubMode classifyPriceIntentSubMode(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return PriceIntentSubMode.NONE;
        }
        String norm = Normalizer.normalize(rawMessage.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        // ACI V3 P2 (§4.2): phàn nàn phong cách CHỈ chặn price sub-mode khi khách KHÔNG đòi giá tường minh.
        if ((CleanAgentOutputRefereeAndSanitizer.isCommunicationStyleComplaintMessage(rawMessage)
                && !CleanAgentOutputRefereeAndSanitizer.hasExplicitPriceDemandInMessage(rawMessage))
                || CleanAgentOrchestratorService.hasCompetitorOrExternalPriceComparisonCue(rawMessage)) {
            return PriceIntentSubMode.NONE;
        }
        if (EXPLICIT_FULL_PRICE_LIST_REQUEST_PATTERN.matcher(norm).matches()) {
            return PriceIntentSubMode.EXPLICIT_FULL_PRICE_LIST_REQUEST;
        }
        if (CleanAgentOrchestratorService.isPureBudgetBoundedServiceInquiry(rawMessage)
                || BUDGET_OR_LOWEST_PRICE_INQUIRY_PATTERN.matcher(norm).matches()) {
            return PriceIntentSubMode.BUDGET_OR_LOWEST_PRICE_INQUIRY;
        }
        if (GENERAL_BARE_PRICE_INQUIRY_PATTERN.matcher(norm).matches()) {
            return PriceIntentSubMode.GENERAL_PRICE_INQUIRY;
        }
        boolean hasPriceCue = norm.matches("(?isU).*\\b(giá|bao\\s+nhiêu|mấy\\s+tiền|chi\\s+phí|báo\\s+giá|hết\\s+bao\\s+nhiêu)\\b.*");
        if (hasPriceCue && !hasGenuinePriceObjectionCue(rawMessage)) {
            return PriceIntentSubMode.SPECIFIC_PRICE_INQUIRY;
        }
        return PriceIntentSubMode.NONE;
    }

    public static int countPriorCustomerPriceObjections(List<FanpageBotRequest.ConversationTurn> history) {
        if (history == null || history.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (FanpageBotRequest.ConversationTurn h : history) {
            if (h == null || h.content() == null || h.content().isBlank()) continue;
            String role = h.role() != null ? h.role().strip().toLowerCase(Locale.ROOT) : "";
            if ("user".equals(role) || "customer".equals(role)) {
                if (hasGenuinePriceObjectionCue(h.content())) {
                    count++;
                }
            }
        }
        return count;
    }

    public static boolean hasCustomerSharedNeedsOrSymptomsInHistory(
            List<FanpageBotRequest.ConversationTurn> history,
            CustomerUnderstandingDraft2Snapshot draft2Snapshot
    ) {
        if (draft2Snapshot != null && draft2Snapshot.consultativeProfile() != null) {
            if (!draft2Snapshot.consultativeProfile().confirmedFacts().isEmpty()
                    || !draft2Snapshot.consultativeProfile().selfReportedSymptoms().isEmpty()) {
                return true;
            }
        }
        if (history == null || history.isEmpty()) {
            return false;
        }
        for (FanpageBotRequest.ConversationTurn h : history) {
            if (h == null || h.content() == null || h.content().isBlank()) continue;
            String role = h.role() != null ? h.role().strip().toLowerCase(Locale.ROOT) : "";
            if (!"user".equals(role) && !"customer".equals(role)) continue;
            String txt = h.content().strip();
            if (!CleanAgentCatalogAndPromptBuilder.isBareTurnOneGreetingOrConsultationRequest(txt)
                    && classifyPriceIntentSubMode(txt) != PriceIntentSubMode.GENERAL_PRICE_INQUIRY
                    && !isBarePauseOrAcknowledgementMessage(txt)
                    && !isCustomerDeferralMessage(txt)
                    && !CleanAgentOrchestratorService.isExplicitConversationRefusalStopMessage(txt)
                    && txt.split("\\s+").length >= 3) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasPriorPriceInquiryInHistory(
            List<FanpageBotRequest.ConversationTurn> history,
            CustomerUnderstandingDraft2Snapshot draft2Snapshot
    ) {
        if (draft2Snapshot != null && draft2Snapshot.conversationalContext() != null
                && draft2Snapshot.conversationalContext().unresolvedRequests() != null) {
            for (UnresolvedRequestItem u : draft2Snapshot.conversationalContext().unresolvedRequests()) {
                if (u != null && u.item() != null) {
                    String rLow = u.item().toLowerCase(Locale.ROOT);
                    if (rLow.contains("price") || rLow.contains("giá") || rLow.contains("chi phí")) {
                        return true;
                    }
                }
            }
        }
        if (history == null || history.isEmpty()) {
            return false;
        }
        for (FanpageBotRequest.ConversationTurn h : history) {
            if (h == null || h.content() == null || h.content().isBlank()) continue;
            String role = h.role() != null ? h.role().strip().toLowerCase(Locale.ROOT) : "";
            if ("user".equals(role) || "customer".equals(role)) {
                if (classifyPriceIntentSubMode(h.content()) != PriceIntentSubMode.NONE) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isConfirmedNeedOrSymptomUtteranceForLightweightState(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return false;
        }
        String norm = Normalizer.normalize(rawText.strip(), Normalizer.Form.NFC);
        if (CleanAgentCatalogAndPromptBuilder.isBareTurnOneGreetingOrConsultationRequest(norm)
                || CleanAgentOutputRefereeAndSanitizer.isCommunicationStyleComplaintMessage(norm)
                || CleanAgentOutputRefereeAndSanitizer.isProcessOrConsultationFeedbackComplaint(norm)
                || CleanAgentOrchestratorService.isExplicitConversationRefusalStopMessage(norm)
                || CleanAgentOrchestratorService.isPoliteThankYouClosingAckMessage(norm)
                || isBarePauseOrAcknowledgementMessage(norm)
                || isCustomerDeferralMessage(norm)
                || isPrivacyConcernMessage(norm)
                || isExplicitGenderOrPronounCorrectionMessage(norm)
                || CleanAgentOrchestratorService.isOperationalOrLogisticsQuestion(norm)
                || CleanAgentOrchestratorService.hasCompetitorOrExternalPriceComparisonCue(norm)
                || hasGenuinePriceObjectionCue(norm)
                || classifyPriceIntentSubMode(norm) != PriceIntentSubMode.NONE) {
            return false;
        }
        if (SCOPE_OR_DUAL_SELECTION_CONFIRMATION_PATTERN.matcher(norm).matches()
                || CleanAgentOutputRefereeAndSanitizer.isUnconfirmedHealthSymptomWithoutServiceDemand(norm, List.of())) {
            return true;
        }
        String low = norm.toLowerCase(Locale.ROOT);
        return low.matches("(?isU).*\\b(đau|mỏi|nhức|căng|tê|mất\\s+ngủ|khó\\s+ngủ|chóng\\s+mặt|hoa\\s+mắt|stress|mệt|"
                + "cổ|vai|vau\\s+gáy|gáy|lưng|đầu|toàn\\s+thân|body|chân|tay|da\\s+mặt|nám|mụn|răng|nướu|"
                + "gội\\s+đầu|dưỡng\\s+sinh|massage|chăm\\s+sóc|thư\\s+giãn|trị\\s+liệu)\\b.*");
    }

    public static boolean isIsolatedNeurologicalSymptomOnly(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return false;
        }
        String low = Normalizer.normalize(rawText.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        boolean hasDizzyOrNeuroVascularOnly = low.matches("(?isU).*\\b(hoa\\s+mắt|chóng\\s+mặt|choáng\\s+váng|mất\\s+thăng\\s+bằng|huyết\\s+áp|tiền\\s+đình|tê\\s+bì|tê\\s+(?:chân|tay|bàn\\s+tay|bàn\\s+chân|tứ\\s+chi)|buồn\\s+nôn|tức\\s+ngực|khó\\s+thở)\\b.*");
        boolean hasMusculoskeletalOrCareContext = low.matches("(?isU).*\\b(đau\\s+đầu|nhức\\s+đầu|mỏi|cổ|vai|vau\\s+gáy|gáy|lưng|ngồi\\s+lâu|máy\\s+tính|văn\\s+phòng|làm\\s+việc|mất\\s+ngủ|khó\\s+ngủ|gói|dịch\\s+vụ|liệu\\s+trình|thư\\s+giãn|dưỡng\\s+sinh|massage)\\b.*");
        return hasDizzyOrNeuroVascularOnly && !hasMusculoskeletalOrCareContext;
    }

    /**
     * ACI V3: Xây dựng {@link TurnContextSnapshot} bất biến một lần duy nhất cho mỗi lượt,
     * dùng chung cho cả Prompt Builder và Guard/Sanitizer.
     */
    public static TurnContextSnapshot buildTurnContextSnapshot(
            String turnId,
            String rawUserMessage,
            String effectiveCustomerPronoun,
            boolean pronounLockedFromSelfReference,
            int memoryPriceObjectionCountBefore,
            List<FanpageBotRequest.ConversationTurn> history,
            CustomerUnderstandingDraft2Snapshot draft2Snapshot,
            ConversationState.StructuredBookingState bookingState
    ) {
        List<FanpageBotRequest.ConversationTurn> safeHist = history != null ? history : List.of();
        String sameTurnSelfPronoun = inferExplicitCustomerSelfPronounFromUtterance(rawUserMessage);
        if (sameTurnSelfPronoun != null && !sameTurnSelfPronoun.isBlank()) {
            effectiveCustomerPronoun = sameTurnSelfPronoun;
            pronounLockedFromSelfReference = true;
        }
        LightweightCustomerUnderstandingPlan plan = buildLightweightCustomerUnderstandingPlan(
                rawUserMessage, safeHist, draft2Snapshot, bookingState);
        Set<CanonicalTurnIntent> intents = detectCanonicalTurnIntents(
                rawUserMessage, safeHist, draft2Snapshot, bookingState);
        CanonicalTurnIntent primary = intents.iterator().next();
        boolean incrementsObjection = intents.contains(CanonicalTurnIntent.PRICE_OBJECTION);
        int historyPriorObjections = countPriorCustomerPriceObjections(safeHist);
        int priorCount = Math.max(memoryPriceObjectionCountBefore, historyPriorObjections);
        int authoritativeCount = priorCount + (incrementsObjection ? 1 : 0);
        if (authoritativeCount != plan.consecutivePriceObjectionCount()) {
            TurnAction adjustedAction = plan.recommendedAction();
            String adjustedDirective = plan.actionExecutionDirective();
            String safeUserMsgForObj = rawUserMessage != null ? rawUserMessage : "";
            String msgLowForObj = safeUserMsgForObj.toLowerCase(Locale.ROOT);
            boolean isAskingWhyOrAlternative = safeUserMsgForObj.contains("?") || safeUserMsgForObj.contains("？")
                    || msgLowForObj.matches("(?isU).*\\b(sao|tại\\s+sao|vì\\s+sao|nhẹ\\s+hơn|nhẹ\\s+tiền\\s+hơn|rẻ\\s+hơn|mềm\\s+hơn|gói\\s+nào|lựa\\s+chọn\\s+nào)\\b.*");
            if (incrementsObjection && priorCount >= 1 && !isAskingWhyOrAlternative
                    && (adjustedAction == TurnAction.ALTERNATIVE_OFFER || adjustedAction == TurnAction.PRICE_OBJECTION_HANDLING)) {
                adjustedAction = TurnAction.RESPECTFUL_DEESCALATION;
                adjustedDirective = "RESPECTFUL_DEESCALATION (LƯỢT PHẢN ĐỐI GIÁ LIÊN TIẾP THỨ " + authoritativeCount
                        + "): Hạ nhiệt tôn trọng cảm nhận ngân sách của khách, tuyệt đối KHÔNG ép chốt, KHÔNG chào mua gói nhiều buổi/combo giảm giá, KHÔNG lặp lại tiện ích phụ đã nói, và kết thúc mở nhẹ nhàng KHÔNG đặt câu hỏi ('?' = 0).";
            }
            plan = new LightweightCustomerUnderstandingPlan(
                    plan.customerState(),
                    plan.inferences(),
                    plan.unknowns(),
                    adjustedAction,
                    plan.priceIntentSubMode(),
                    adjustedDirective,
                    authoritativeCount
            );
        }
        return new TurnContextSnapshot(
                turnId,
                rawUserMessage,
                effectiveCustomerPronoun,
                pronounLockedFromSelfReference,
                primary,
                intents,
                plan.priceIntentSubMode(),
                priorCount,
                authoritativeCount,
                incrementsObjection,
                plan,
                draft2Snapshot,
                bookingState
        );
    }

    /**
     * P3.7-B.2 &amp; ACI V3: Xây dựng {@code LightweightCustomerUnderstandingPlan} (tách bạch rõ {@code Fact} / {@code Inference} / {@code Unknown},
     * phân tách rạch ròi 6 nhóm ý định mà không biến so sánh đối thủ / hỏi ngân sách / riêng tư / tạm hoãn thành phản đối giá,
     * và không nhét phát ngôn không phải nhu cầu vào {@code needs}).
     */
    public static LightweightCustomerUnderstandingPlan buildLightweightCustomerUnderstandingPlan(
            String rawUserMessage,
            List<FanpageBotRequest.ConversationTurn> history,
            CustomerUnderstandingDraft2Snapshot draft2Snapshot,
            ConversationState.StructuredBookingState bookingState
    ) {
        String msg = rawUserMessage != null ? Normalizer.normalize(rawUserMessage.strip(), Normalizer.Form.NFC) : "";
        String msgLow = msg.toLowerCase(Locale.ROOT);
        int wordCount = msg.isBlank() ? 0 : msg.split("\\s+").length;
        boolean hasCustomerHistory = history != null && history.stream().anyMatch(h ->
                h != null && h.role() != null
                        && ("user".equalsIgnoreCase(h.role()) || "customer".equalsIgnoreCase(h.role()))
                        && h.content() != null && !h.content().isBlank());
        boolean priorNeedsEstablished = hasCustomerSharedNeedsOrSymptomsInHistory(history, draft2Snapshot);
        int priorPriceObjections = countPriorCustomerPriceObjections(history);

        List<String> needs = new ArrayList<>();
        List<String> emotions = new ArrayList<>();
        List<String> concerns = new ArrayList<>();
        List<String> buyingSignals = new ArrayList<>();
        List<String> frictionHistory = new ArrayList<>();
        List<String> communicationSignals = new ArrayList<>();
        List<LightweightInferenceItem> inferences = new ArrayList<>();
        List<String> unknowns = new ArrayList<>();
        if (ConversationState.resolveExplicitRegionAcrossTurns(msg, history != null ? history : List.of(), null) == null) {
            unknowns.add("customer_branch_location_if_multi_branch_catalog");
        }

        if (draft2Snapshot != null && draft2Snapshot.consultativeProfile() != null) {
            for (VersionedCustomerFact f : draft2Snapshot.consultativeProfile().confirmedFacts()) {
                if (f != null && f.validity() == FactValidity.CURRENT && f.value() != null && !f.value().isBlank()) {
                    if ("self_reported_symptom".equalsIgnoreCase(f.key()) || "need_scope".equalsIgnoreCase(f.key())) {
                        needs.add(f.value());
                    } else if ("price_reaction".equalsIgnoreCase(f.key())) {
                        concerns.add(f.value());
                    }
                }
            }
            for (VersionedCustomerFact sf : draft2Snapshot.consultativeProfile().selfReportedSymptoms()) {
                if (sf != null && sf.validity() == FactValidity.CURRENT && sf.value() != null && !sf.value().isBlank() && !needs.contains(sf.value())) {
                    needs.add(sf.value());
                }
            }
        }
        if (history != null) {
            for (FanpageBotRequest.ConversationTurn h : history) {
                if (h == null || h.content() == null || h.content().isBlank()) continue;
                String r = h.role() != null ? h.role().strip().toLowerCase(Locale.ROOT) : "";
                if (("user".equals(r) || "customer".equals(r))
                        && isConfirmedNeedOrSymptomUtteranceForLightweightState(h.content())
                        && h.content().strip().split("\\s+").length >= 3) {
                    String hNorm = Normalizer.normalize(h.content().strip(), Normalizer.Form.NFC);
                    if (!needs.contains(hNorm) && needs.size() < 3) {
                        needs.add(hNorm);
                    }
                }
            }
        }

        // Short message signal (1-3 words): only adjusts response length, NEVER infers coldness/disinterest
        if (wordCount >= 1 && wordCount <= 3) {
            communicationSignals.add("SHORT_UTTERANCE_1_TO_3_WORDS_ADJUST_LENGTH_ONLY");
            inferences.add(new LightweightInferenceItem(
                    "SHORT_MESSAGE_IS_CONCISE_PACING_SIGNAL_NOT_DISINTEREST",
                    0.60,
                    List.of(msg)
            ));
        }

        Set<CanonicalTurnIntent> detectedIntents = detectCanonicalTurnIntents(msg, history, draft2Snapshot, bookingState);
        boolean isStyleOrProcessFeedback = detectedIntents.contains(CanonicalTurnIntent.PROCESS_FEEDBACK);
        boolean isExplicitRefusal = detectedIntents.contains(CanonicalTurnIntent.EXPLICIT_REFUSAL);
        boolean isBarePauseAck = detectedIntents.contains(CanonicalTurnIntent.CUSTOMER_PAUSE_OR_ACKNOWLEDGEMENT);
        boolean isCustomerDeferral = detectedIntents.contains(CanonicalTurnIntent.CUSTOMER_DEFERRAL);
        boolean isExternalComparison = detectedIntents.contains(CanonicalTurnIntent.COMPETITOR_COMPARISON);
        boolean isGenuinePriceObjection = detectedIntents.contains(CanonicalTurnIntent.PRICE_OBJECTION);
        boolean isPrivacyConcern = detectedIntents.contains(CanonicalTurnIntent.PRIVACY_CONCERN);
        boolean isGenderSelfId = detectedIntents.contains(CanonicalTurnIntent.SELF_IDENTIFY_GENDER);
        boolean hasPriorPriceAsk = hasPriorPriceInquiryInHistory(history, draft2Snapshot);
        PriceIntentSubMode priceSubMode = classifyPriceIntentSubMode(msg);
        boolean hasBookingCue = detectedIntents.contains(CanonicalTurnIntent.BOOKING_DISCUSSION);
        boolean isScopeConfirmation = detectedIntents.contains(CanonicalTurnIntent.SCOPE_CONFIRMATION);
        boolean isSuitabilityInquiry = detectedIntents.contains(CanonicalTurnIntent.SUITABILITY_INQUIRY);
        boolean isCustomerCorrection = detectedIntents.contains(CanonicalTurnIntent.CUSTOMER_CORRECTION);
        boolean isTrialCommitmentSelection = detectedIntents.contains(CanonicalTurnIntent.TRIAL_COMMITMENT_SELECTION);
        boolean isPackageComparison = detectedIntents.contains(CanonicalTurnIntent.PACKAGE_COMPARISON);

        if (isPrivacyConcern) {
            concerns.add("PRIVACY_OR_SHARED_SPACE_CONCERN");
        }

        TurnAction recommendedAction;
        String directive;
        // Guardrail 3: Only genuine price objection increments totalPriceObjections; pure competitor comparison / budget inquiry / privacy / deferral never do.
        int totalPriceObjections = priorPriceObjections + (isGenuinePriceObjection ? 1 : 0);

        if (isSuitabilityInquiry) {
            concerns.add("HEALTH_SUITABILITY_INQUIRY");
            communicationSignals.add("DEMO_HANDOFF_PENDING");
            recommendedAction = TurnAction.SUITABILITY_INQUIRY_HANDOFF;
            directive = "SUITABILITY_INQUIRY_HANDOFF: Khách hỏi trực tiếp tình trạng sức khỏe có phù hợp làm dịch vụ không ('" + msg + "'). "
                    + "BẮT BUỘC: (1) Ghi nhận nguyên văn tình trạng khách vừa chia sẻ một cách đồng cảm, tự nhiên; "
                    + "(2) Giải thích rõ phần đánh giá mức độ phù hợp với tình trạng sức khỏe sẽ do nhân viên/chuyên viên Spa trực tiếp xem xét và tư vấn theo quy trình của Spa (trạng thái demo: DEMO_HANDOFF_PENDING); "
                    + "(3) TUYỆT ĐỐI KHÔNG tự kết luận 'làm được bình thường', KHÔNG tự phân loại cấp độ y tế, KHÔNG đặt câu hỏi khai thác chỉ số huyết áp/bệnh lý ('?' = 0), KHÔNG báo giá/chốt lịch trong lượt này; 0 xuất mã enum nội bộ.";
        } else if (isCustomerCorrection) {
            frictionHistory.add("CUSTOMER_CORRECTION_OR_COMPONENT_REJECTION");
            communicationSignals.add("PRIORITY: CUSTOMER_CORRECTION > CUSTOMER_COMMITMENT > BOT_RECOMMENDATION");
            recommendedAction = TurnAction.CUSTOMER_CORRECTION_PIVOT;
            directive = "CUSTOMER_CORRECTION_PIVOT (ƯU TIÊN CUSTOMER_CORRECTION > CUSTOMER_COMMITMENT > BOT_RECOMMENDATION): Khách vừa đính chính/từ chối ('" + msg + "'). "
                    + "BẮT BUỘC: (1) Thừa nhận thẳng thắn điều chỉnh của khách ngay câu đầu (không bảo vệ gói cũ, không gọi thành phần bị từ chối là 'tặng kèm'); "
                    + "(2) Loại bỏ hoàn toàn thành phần/gói khách không muốn — nếu khách từ chối chăm sóc/phục hồi tóc mà chỉ muốn massage giúp dễ ngủ thì chuyển thẳng sang đề xuất gói thuần massage trong <VERIFIED_BUSINESS_CATALOG> (nêu đúng tên gói + giá + thời lượng theo Catalog, tuyệt đối không tự bịa mức giá); "
                    + "(3) Bảo toàn các nhu cầu đã biết (đau đầu, khó ngủ, mỏi...) và KHÔNG hỏi lại câu thừa 'Anh/chị có muốn tìm lựa chọn tập trung massage không?'.";
        } else if (isTrialCommitmentSelection) {
            buyingSignals.add("TRIAL_COMMITMENT");
            recommendedAction = TurnAction.TRIAL_COMMITMENT_LOCK;
            directive = "TRIAL_COMMITMENT_LOCK: Khách vừa chọn phương án vừa đủ/trải nghiệm thử ('" + msg + "'). "
                    + "BẮT BUỘC: (1) Khóa ngay lựa chọn này (TRIAL_COMMITMENT) và xác nhận đúng gói đó một cách tự nhiên (tên + giá + thời lượng theo <VERIFIED_BUSINESS_CATALOG>); "
                    + "(2) TUYỆT ĐỐI KHÔNG upsell sang gói đắt hơn, KHÔNG nhắc thành phần không liên quan hoặc đã bị khách từ chối; "
                    + "(3) Có thể hỏi nhẹ 1 câu về thời điểm khách tiện ghé, không ép chốt.";
            boolean priorHairCareRejection = draft2Snapshot != null
                    && draft2Snapshot.businessCommitment() != null
                    && draft2Snapshot.businessCommitment().negativePreferences() != null
                    && draft2Snapshot.businessCommitment().negativePreferences().stream()
                        .anyMatch(e -> e != null && e.targetKey() != null && e.targetKey().equalsIgnoreCase("hair_care"));
            boolean massageFocusAfterHairRejection = priorHairCareRejection
                    && msgLow.matches("(?isU).*(cổ\\s*vai\\s*gáy|massage|mát\\s*xa).*");
            if (priorHairCareRejection || massageFocusAfterHairRejection) {
                directive = directive + " LƯU Ý QUAN TRỌNG: Khách ĐÃ TỪ CHỐI chăm sóc/phục hồi tóc (negative_preferences: hair_care) -> TUYỆT ĐỐI KHÔNG chọn lại gói thuộc nhóm gội đầu dưỡng sinh/chăm sóc tóc; BẮT BUỘC khóa đúng gói thuần Massage vừa trao đổi với đúng tên, giá và thời lượng theo <VERIFIED_BUSINESS_CATALOG>!";
            }
        } else if (isStyleOrProcessFeedback) {
            emotions.add("FRUSTRATED_BY_CONSULTATION_STYLE");
            frictionHistory.add("PROCESS_OR_COMMUNICATION_FEEDBACK");
            recommendedAction = TurnAction.FEEDBACK_RECOVERY;
            // ACI V3 P2 (§4.2 NGOẠI LỆ BẮT BUỘC): khách vừa phàn nàn vừa ĐÒI GIÁ trong cùng lượt
            // ("đừng nói vòng vo nữa, báo giá đi") -> PHẢI trả lời giá grounded, không chặn vì friction.
            boolean explicitPriceDemandThisTurn = priceSubMode != PriceIntentSubMode.NONE
                    || CleanAgentOutputRefereeAndSanitizer.hasExplicitPriceDemandInMessage(msg);
            boolean mixedBusinessOrCatalogClarification = CleanAgentOutputRefereeAndSanitizer.isFix5MixedIntentRecoveryEnabled()
                    && CleanAgentOutputRefereeAndSanitizer.hasBusinessOrCatalogClarificationDemandOnComplaintTurn(msg);
            boolean allowDiscoveryQ = CleanAgentOutputRefereeAndSanitizer.isDiscoveryQuestionAllowedOnProcessFeedback(msg);
            if (explicitPriceDemandThisTurn) {
                directive = "FEEDBACK_RECOVERY (KÈM YÊU CẦU GIÁ TƯỜNG MINH TRONG CÙNG LƯỢT): Khách vừa phàn nàn cách trao đổi NHƯNG đồng thời yêu cầu báo giá ('" + msg + "'). "
                        + "BẮT BUỘC: (1) Đi thẳng NGAY câu đầu tiên vào báo giá cụ thể bằng đúng tên gói + mức giá + thời lượng grounded từ <VERIFIED_BUSINESS_CATALOG> gắn với vùng khách đang mỏi (TUYỆT ĐỐI KHÔNG mở đầu bằng câu xin lỗi dài dòng hay giải thích vòng vo); "
                        + "(2) Giữ đúng 1 bong bóng ngắn gọn súc tích, cta_level = NO_CTA, 0 ép chốt, 0 câu hỏi thăm dò thêm ('?' = 0).";
            } else if (mixedBusinessOrCatalogClarification) {
                directive = "FEEDBACK_RECOVERY (COMPLAINT_WITH_BUSINESS_OR_CATALOG_CLARIFICATION): Khách vừa góp ý/phàn nàn cách diễn đạt NHƯNG đồng thời cần làm rõ phạm vi dịch vụ hoặc danh mục gói trong <VERIFIED_BUSINESS_CATALOG> ('" + msg + "'). "
                        + "BẮT BUỘC: (1) Nhận lỗi chân thành, ngắn gọn trong 1 vế đầu về cách diễn đạt gây hiểu lầm (cấm đổ lỗi khách hiểu nhầm, cấm lặp lại danh sách triệu chứng của khách); "
                        + "(2) Giải thích/làm rõ TRỰC TIẾP ngay trong cùng lượt về phạm vi chăm sóc hoặc nêu rõ thêm 2–3 gói tiêu biểu khác trong <VERIFIED_BUSINESS_CATALOG> kèm thời lượng và mức giá cụ thể để giải tỏa hoàn toàn thắc mắc của khách; "
                        + "(3) Giữ cta_level = NO_CTA, 0 câu hỏi ép chọn ('?' = 0), 0 mời đặt lịch.";
            } else {
                directive = allowDiscoveryQ
                        ? "FEEDBACK_RECOVERY (MISSED_DISCOVERY): Xin lỗi chân thành trực diện (cấm xin lỗi điều kiện 'Em xin lỗi nếu...'), 0 báo giá/gói, và hỏi đúng 1 câu mở ngắn gọn xem khách đang muốn chăm sóc vùng nào."
                        : "FEEDBACK_RECOVERY (OVER_INTRUSION / CONTEXTUAL_RECOVERY): Khách phản ứng tiêu cực về câu trả lời vừa rồi của bot ('" + msg + "'). BẮT BUỘC: Nhận lỗi chân thành, tự nhiên và sát ngữ cảnh cuộc trò chuyện vừa diễn ra (1 bong bóng gọn gàng, cta_level = NO_CTA, 0 câu hỏi '?', 0 tự khai AI khi không bị hỏi trực tiếp, 0 báo giá hay chào bán gói dịch vụ, 0 lặp lại danh sách triệu chứng của khách).";
            }
        } else if (isExplicitRefusal) {
            communicationSignals.add("EXPLICIT_CONVERSATION_CLOSURE");
            recommendedAction = TurnAction.CUSTOMER_EXIT;
            directive = "CUSTOMER_EXIT: Tôn trọng quyết định dừng/tạm nghỉ của khách bằng 1 bong bóng ngắn gọn ấm áp, 0 câu hỏi '?', 0 chèo kéo.";
        } else if (isGenderSelfId) {
            String targetPronoun = inferExplicitCustomerSelfPronounFromUtterance(msg);
            String safeTarget = (targetPronoun != null && !targetPronoun.isBlank()) ? targetPronoun : "Anh";
            communicationSignals.add("EXPLICIT_GENDER_OR_PRONOUN_CORRECTION:" + safeTarget);
            recommendedAction = TurnAction.SELF_IDENTIFY_GENDER;
            directive = "SELF_IDENTIFY_GENDER: Khách vừa xác nhận giới tính hoặc chỉnh đại từ ('" + msg + "' -> gọi khách là '" + safeTarget + "'). "
                    + "BẮT BUỘC: (1) Xin lỗi ngắn gọn 1 câu tự nhiên vì sơ suất xưng hô và đổi ngay sang '" + safeTarget + "' trong toàn bộ câu trả lời; "
                    + "(2) Khẳng định nhẹ nhàng dịch vụ chăm sóc tại cơ sở hoàn toàn phù hợp để '" + safeTarget + "' thư giãn giải tỏa vùng đang mỏi (CẤM câu chụp mũ giới tính kiểu 'các anh thường...'); "
                    + "(3) Giữ đúng 1 bong bóng ngắn gọn ấm áp, cta_level = NO_CTA, next_action = NO_ADDITIONAL_ACTION, 0 câu hỏi ('?' = 0), 0 báo giá lại, 0 kích hoạt skill giá/chốt lịch.";
        } else if (isBarePauseAck && (bookingState == null || bookingState.status() == ConversationState.BookingStatus.NONE)) {
            communicationSignals.add("BARE_PAUSE_OR_ACKNOWLEDGEMENT");
            inferences.add(new LightweightInferenceItem(
                    "CUSTOMER_PAUSE_OR_ACKNOWLEDGEMENT_NOT_PERMANENT_EXIT",
                    0.75,
                    List.of(msg)
            ));
            recommendedAction = TurnAction.CUSTOMER_PAUSE_OR_ACKNOWLEDGEMENT;
            directive = "CUSTOMER_PAUSE_OR_ACKNOWLEDGEMENT: Khách phản hồi ngắn ('" + msg + "') để ghi nhận/tạm dừng nhịp — giữ nguyên giọng ấm áp tự nhiên, 1 bong bóng gọn, 0 câu hỏi '?', 0 chào tạm biệt đóng cửa, 0 nhắc lại gói/giá.";
        } else if (isCustomerDeferral && (bookingState == null || bookingState.status() == ConversationState.BookingStatus.NONE)) {
            communicationSignals.add("CUSTOMER_DEFERRAL_RESPECT_PAUSE");
            inferences.add(new LightweightInferenceItem(
                    "CUSTOMER_DEFERRING_DECISION_NEEDS_SPACE_WITHOUT_PRESSURE",
                    0.80,
                    List.of(msg)
            ));
            recommendedAction = TurnAction.CUSTOMER_DEFERRAL;
            directive = "CUSTOMER_DEFERRAL: Khách xin tạm hoãn để cân nhắc/tham khảo thêm ('" + msg + "') — tôn trọng nhịp của khách bằng 1 bong bóng ấm áp, 0 câu hỏi '?', 0 ép chốt hay chào thêm gói.";
        } else if (hasBookingCue) {
            buyingSignals.add("BOOKING_OR_SCHEDULE_DISCUSSION");
            recommendedAction = TurnAction.BOOKING_DISCUSSION;
            directive = "BOOKING_DISCUSSION: Hỗ trợ thông tin khung giờ/cơ sở hoặc giữ lịch tạm chờ kiểm tra lịch trống thực tế; tuyệt đối không tự tuyên bố đã chốt lịch thành công và không xuất mã enum nội bộ.";
        } else if (isExternalComparison) {
            concerns.add("EXTERNAL_PRICE_COMPARISON");
            if (isGenuinePriceObjection) {
                concerns.add("PRICE_CONCERN_EXPRESSED");
                frictionHistory.add("PRICE_OBJECTION_TURN_" + totalPriceObjections);
            }
            inferences.add(new LightweightInferenceItem(
                    "CUSTOMER_COMPARING_WITH_EXTERNAL_SERVICE_NEEDS_GROUNDED_INTRINSIC_DIFFERENTIATION",
                    0.75,
                    List.of(msg)
            ));
            recommendedAction = TurnAction.VALUE_EXPLANATION;
            directive = "VALUE_EXPLANATION (COMPETITOR_COMPARISON - 4 BƯỚC EMOTIONAL VALUE REFRAMING): "
                    + "(1) Công nhận tự nhiên mức giá tham chiếu bên ngoài khách nêu là hợp lý cho nhu cầu gội nhanh thông thường (CẤM chê bai hay hạ thấp dịch vụ bên ngoài); "
                    + "(2) Làm rõ điềm tĩnh sự khác biệt nội tại đã xác minh của cơ sở trong <VERIFIED_BUSINESS_CATALOG> về thời lượng và phạm vi quy trình kỹ thuật cốt lõi; "
                    + "(3) Kết nối trực tiếp vào triệu chứng/tình trạng khách đã chia sẻ (như đau đầu, mỏi cổ vai gáy, khó ngủ) để khách cảm nhận vì sao buổi chăm sóc kỹ giúp thả lỏng vùng căng mỏi hiệu quả hơn; "
                    + "(4) Gợi ý nhẹ nhàng gói cơ bản dễ tiếp cận nhất trong Catalog và để quyền lựa chọn thoải mái cho khách, không ép mua.";
        } else if (isGenuinePriceObjection) {
            concerns.add("PRICE_CONCERN_EXPRESSED");
            frictionHistory.add("PRICE_OBJECTION_TURN_" + totalPriceObjections);
            boolean isAskingWhyOrAlternative = msg.contains("?") || msg.contains("？")
                    || msgLow.matches("(?isU).*\\b(sao|tại\\s+sao|vì\\s+sao|nhẹ\\s+hơn|nhẹ\\s+tiền\\s+hơn|rẻ\\s+hơn|mềm\\s+hơn|gói\\s+nào|lựa\\s+chọn\\s+nào)\\b.*");
            if (isPackageComparison) {
                buyingSignals.add("PACKAGE_COMPARISON_INQUIRY");
                inferences.add(new LightweightInferenceItem(
                        "CUSTOMER_HAS_PRICE_CONCERN_AND_ASKS_DIRECT_PACKAGE_COMPARISON",
                        0.75,
                        List.of(msg)
                ));
                recommendedAction = TurnAction.ALTERNATIVE_OFFER;
                directive = "PRICE_OBJECTION_WITH_PACKAGE_COMPARISON (LƯỢT PHẢN ĐỐI GIÁ KÈM CÂU HỎI SO SÁNH GÓI CỤ THỂ '" + msg + "'): "
                        + "BẮT BUỘC xử lý đồng thời cả 2 vế: "
                        + "(1) Đồng cảm tự nhiên, ngắn gọn với cảm nhận chi phí của khách ngay vế đầu; "
                        + "(2) So sánh TRỰC TIẾP và CỤ THỂ đúng các gói/mức giá khách vừa hỏi dựa trên <VERIFIED_BUSINESS_CATALOG> (nêu rõ thời lượng, phạm vi vùng chăm sóc và các hạng mục khác biệt cốt lõi giữa các gói đó); "
                        + "(3) TUYỆT ĐỐI KHÔNG né câu hỏi so sánh, KHÔNG chỉ lặp lại giá mà không giải thích điểm khác nhau, và KHÔNG tự ý lái sang một gói thứ ba không liên quan; "
                        + "(4) Có thể hỏi nhẹ 1 câu xem khách nghiêng về trải nghiệm gói nào hơn.";
            } else if (priorPriceObjections >= 1 && !isAskingWhyOrAlternative) {
                emotions.add("HESITANT_UNDER_REPEATED_PRICE_FRICTION");
                inferences.add(new LightweightInferenceItem(
                        "CUSTOMER_MAY_HAVE_PRICE_CONCERN",
                        0.70,
                        List.of(msg)
                ));
                recommendedAction = TurnAction.RESPECTFUL_DEESCALATION;
                directive = "RESPECTFUL_DEESCALATION (LƯỢT PHẢN ĐỐI GIÁ LIÊN TIẾP THỨ " + totalPriceObjections + "): Hạ nhiệt tôn trọng cảm nhận ngân sách của khách, tuyệt đối KHÔNG ép chốt, KHÔNG chào mua gói nhiều buổi/combo giảm giá, KHÔNG lặp lại tiện ích phụ đã nói, và kết thúc mở nhẹ nhàng KHÔNG đặt câu hỏi ('?' = 0).";
            } else {
                inferences.add(new LightweightInferenceItem(
                        "CUSTOMER_MAY_HAVE_PRICE_CONCERN_SINGLE_TURN_DO_NOT_OVERGENERALIZE",
                        0.55,
                        List.of(msg)
                ));
                recommendedAction = TurnAction.ALTERNATIVE_OFFER;
                directive = "ALTERNATIVE_OFFER / PRICE_OBJECTION_HANDLING (LƯỢT PHẢN ĐỐI GIÁ ĐẦU TIÊN - FEEL FIRST, SELL SECOND): "
                        + "(1) Đồng cảm tự nhiên với cảm nhận chi phí của khách (VALUE_PERCEPTION_GAP); "
                        + "(2) Làm rõ giá trị thực tế gắn với triệu chứng khách đang gặp và gợi ý nhẹ nhàng phương án cơ bản dễ tiếp cận nhất trong <VERIFIED_BUSINESS_CATALOG> (0 upsell, 0 combo nhiều buổi, next_action = SOFT_FEELING_CHECK).";
            }
        } else if (isPackageComparison) {
            buyingSignals.add("PACKAGE_COMPARISON_INQUIRY");
            recommendedAction = TurnAction.SPECIFIC_PRICE_ANSWER;
            directive = "PACKAGE_COMPARISON (SO SÁNH GÓI CỤ THỂ '" + msg + "'): So sánh trực tiếp, rõ ràng đúng các gói khách đang hỏi dựa trên <VERIFIED_BUSINESS_CATALOG> (nêu đúng mức giá, thời lượng, phạm vi vùng chăm sóc và các bước khác biệt chính), không trả lời chung chung và không lái sang gói không liên quan.";
        } else if (priceSubMode != PriceIntentSubMode.NONE) {
            switch (priceSubMode) {
                case GENERAL_PRICE_INQUIRY -> {
                    if (priorNeedsEstablished) {
                        buyingSignals.add("CONTEXTUAL_PRICE_INQUIRY_AFTER_NEED_SHARED");
                        recommendedAction = TurnAction.SPECIFIC_PRICE_ANSWER;
                        directive = "PRICE_INTENT_B_CONTEXTUAL (HỎI GIÁ SAU KHI ĐÃ CHIA SẺ TRIỆU CHỨNG): Khách hỏi giá sau khi đã chia sẻ tình trạng mỏi — báo giá trực tiếp, minh bạch 1–2 gói phù hợp nhất với triệu chứng của khách từ <VERIFIED_BUSINESS_CATALOG> (kèm thời lượng và điểm khác biệt cốt lõi), kết thúc bằng 1 câu hỏi nhẹ nhàng xem khách nghiêng về gói nào, KHÔNG hỏi lại câu khám phá triệu chứng từ đầu.";
                    } else {
                        unknowns.add("specific_service_or_symptom_need");
                        recommendedAction = !hasCustomerHistory ? TurnAction.GREETING_AND_DISCOVERY : TurnAction.SPECIFIC_PRICE_ANSWER;
                        directive = "PRICE_INTENT_A (GENERAL_PRICE_INQUIRY): (1) Nêu rõ gói/mức giá khởi điểm thực tế từ starting_package_anchor / core_service_group_starting_prices trong <VERIFIED_BUSINESS_CATALOG> cùng 1–2 gói đại diện ở mức phổ biến/chuyên sâu (kèm giá, thời lượng và hạng mục chính), đồng thời nói rõ đây là các lựa chọn tiêu biểu chứ không phải toàn bộ danh mục của cơ sở; (2) Tách câu hỏi thành Bubble 2 riêng hỏi nhẹ về nhu cầu thư giãn hoặc vùng cơ thể khách đang muốn chăm sóc để gợi ý đúng hơn (TUYỆT ĐỐI KHÔNG yêu cầu khách chọn gói cụ thể khi chưa hiểu nhu cầu, CẤM xả bảng giá dài).";
                    }
                }
                case SPECIFIC_PRICE_INQUIRY -> {
                    buyingSignals.add("SPECIFIC_SERVICE_PRICE_INTEREST");
                    recommendedAction = TurnAction.SPECIFIC_PRICE_ANSWER;
                    directive = "PRICE_INTENT_B (SPECIFIC_PRICE_INQUIRY): Trả lời trực tiếp, chính xác mức giá và thời lượng của đúng dịch vụ khách hỏi từ <VERIFIED_BUSINESS_CATALOG>, không né tránh bằng câu hỏi khám phá vòng vo.";
                }
                case BUDGET_OR_LOWEST_PRICE_INQUIRY -> {
                    concerns.add("BUDGET_BOUNDED_SEARCH");
                    recommendedAction = TurnAction.SPECIFIC_PRICE_ANSWER;
                    directive = "PRICE_INTENT_C (BUDGET_OR_LOWEST_PRICE_INQUIRY): Lọc trung thực từ <VERIFIED_BUSINESS_CATALOG> theo ngưỡng ngân sách khách hỏi; nếu không có gói ở mức đó thì trả lời trung thực rồi nêu gói thấp nhất hiện có và hỏi nhu cầu.";
                }
                case EXPLICIT_FULL_PRICE_LIST_REQUEST -> {
                    communicationSignals.add("EXPLICIT_FULL_PRICE_LIST_REQUESTED");
                    recommendedAction = TurnAction.SPECIFIC_PRICE_ANSWER;
                    directive = "PRICE_INTENT_D (EXPLICIT_FULL_PRICE_LIST_REQUEST): Tôn trọng yêu cầu xem bảng giá đầy đủ của khách — trình bày gọn gàng, rõ ràng các gói chính và mức giá từ <VERIFIED_BUSINESS_CATALOG> mà không hỏi vặn lại vòng vo.";
                }
                default -> {
                    recommendedAction = TurnAction.SPECIFIC_PRICE_ANSWER;
                    directive = "SPECIFIC_PRICE_ANSWER: Báo giá chính xác từ Catalog.";
                }
            }
        } else if (CleanAgentCatalogAndPromptBuilder.isBareTurnOneGreetingOrConsultationRequest(msg) && !priorNeedsEstablished) {
            unknowns.add("customer_need_or_discomfort_area");
            recommendedAction = TurnAction.GREETING_AND_DISCOVERY;
            directive = "GREETING_AND_DISCOVERY: Chào tự nhiên, ấm áp và đặt đúng 1 câu hỏi ngắn gọn để khám phá nhu cầu/tình trạng khách đang muốn chăm sóc (0 báo giá, 0 liệt kê gói).";
        } else if (isScopeConfirmation) {
            buyingSignals.add("CONFIRMED_DESIRED_CARE_SCOPE");
            if (!needs.contains(msg)) {
                needs.add(msg);
            }
            unknowns.add("preferred_session_duration_or_experience_depth");
            recommendedAction = TurnAction.SERVICE_RECOMMENDATION;
            directive = "SERVICE_RECOMMENDATION (SAU KHI KHÁCH XÁC NHẬN PHẠM VI '" + msg + "'): Khách vừa trả lời trực tiếp câu hỏi phạm vi chăm sóc của bot ('" + msg + "'). "
                    + "BẮT BUỘC: (1) Ghi nhận đúng phạm vi '" + msg + "' vào need_scope (TUYỆT ĐỐI KHÔNG biến câu trả lời vùng cơ thể thành triệu chứng bệnh lý mới, KHÔNG đặt mục tiêu điều trị y khoa, KHÔNG tự gán selected_product_id); "
                    + "(2) Đề xuất trực tiếp gói phù hợp từ <VERIFIED_BUSINESS_CATALOG> (nêu tự nhiên giá + thời lượng + hạng mục chính, dùng từ 'chăm sóc chuyên sâu' thay vì 'massage điều trị'); "
                    + "(3) Câu hỏi cuối hỏi về thời lượng/trải nghiệm gói, không hỏi lại khách muốn chăm sóc vùng nào và không quay lại hỏi thời điểm/tần suất xuất hiện triệu chứng.";
        } else if (isIsolatedNeurologicalSymptomOnly(msg)
                || (!priorNeedsEstablished && isConfirmedNeedOrSymptomUtteranceForLightweightState(msg))) {
            if (!needs.contains(msg)) {
                needs.add(msg);
            }
            emotions.add("PHYSICAL_FATIGUE_OR_DISCOMFORT");
            if (hasPriorPriceAsk
                    && !CleanAgentOutputRefereeAndSanitizer.isUnconfirmedHealthSymptomWithoutServiceDemand(msg, history)
                    && !isIsolatedNeurologicalSymptomOnly(msg)) {
                recommendedAction = TurnAction.SERVICE_RECOMMENDATION;
                directive = "SERVICE_RECOMMENDATION (SAU KHI KHÁCH ĐÃ HỎI GIÁ & VỪA CHIA SẺ TRIỆU CHỨNG '" + msg + "'): "
                        + "(1) Đồng cảm tinh tế Cấp độ 3 với đầy đủ triệu chứng khách nêu (kể cả khi khách gõ tắt/sai chính tả như 'vau gáy' -> 'vai gáy'); "
                        + "(2) Giải thích ngắn gọn vì sao nhóm chăm sóc dưỡng sinh/cổ vai gáy phù hợp với tình trạng này; "
                        + "(3) Gợi ý 1–2 gói phù hợp nhất từ <VERIFIED_BUSINESS_CATALOG> kèm thời lượng, điểm khác biệt rõ ràng và mức giá minh bạch để trả lời trọn vẹn câu hỏi giá trước đó; "
                        + "(4) Hỏi 1 câu nhẹ nhàng xem khách nghiêng về buổi thư giãn vừa đủ hay chăm sóc chuyên sâu hơn.";
            } else {
                unknowns.add("preferred_focus_area_or_session_intensity");
                recommendedAction = TurnAction.NEED_DISCOVERY;
                directive = isIsolatedNeurologicalSymptomOnly(msg)
                        ? "NEED_DISCOVERY (ISOLATED_NEUROLOGICAL_OR_VASCULAR_SYMPTOM '" + msg + "'): "
                                + "(1) Đồng cảm ấm áp, tự nhiên với tình trạng khách chia sẻ (1 câu ngắn gọn, không sáo rỗng); "
                                + "(2) Giữ đúng ranh giới an toàn sức khỏe: nêu nhẹ nhàng rằng các liệu trình tại cơ sở hướng tới chăm sóc thư giãn cơ thể, không thay thế thăm khám hay điều trị y khoa đối với các triệu chứng thần kinh/mạch máu (như hoa mắt, chóng mặt, tê chân tay); "
                                + "(3) TUYỆT ĐỐI KHÔNG tự ý chốt/chào bán gói dịch vụ hay báo giá gói cụ thể ở lượt này; "
                                + "(4) Đặt đúng 1 câu hỏi nhẹ nhàng xem hiện khách đang muốn ưu tiên chăm sóc thư giãn vùng cơ thể nào nhất (như vùng đầu, cổ vai gáy hay chân tay) để tư vấn hướng thư giãn phù hợp."
                        : "NEED_DISCOVERY (CHIA SẺ TRIỆU CHỨNG LẦN ĐẦU CHƯA HỎI GÓI/GIÁ): Đồng cảm tinh tế Cấp độ 3 với đầy đủ triệu chứng khách chia sẻ (1-2 câu tự nhiên, không sáo rỗng) và giới thiệu định hướng liệu trình chăm sóc kết hợp thư giãn phù hợp, sau đó hỏi xem khách muốn ưu tiên vùng nào hoặc muốn trải nghiệm buổi vừa đủ hay dành thêm thời gian chăm sóc kỹ hơn (chưa xả bảng giá chi tiết khi khách chưa hỏi giá).";
            }
        } else {
            if (!msg.isBlank() && isConfirmedNeedOrSymptomUtteranceForLightweightState(msg) && !needs.contains(msg)) {
                needs.add(msg);
            }
            recommendedAction = TurnAction.SERVICE_RECOMMENDATION;
            directive = isPrivacyConcern
                    ? "SERVICE_RECOMMENDATION (PRIVACY_CONCERN): Giải đáp trung thực điều kiện không gian/rèm che riêng từng giường của gói đang tư vấn theo đúng <VERIFIED_BUSINESS_CATALOG>, giữ đúng service_group và hỏi nhẹ nhàng xem khách đã yên tâm hơn chưa."
                    : "SERVICE_RECOMMENDATION: Tư vấn gói dịch vụ sát nhu cầu đã xác nhận từ <VERIFIED_BUSINESS_CATALOG>, tách ý tự nhiên 1-2 bong bóng (~20-40 từ/bong bóng), kết thúc bằng tối đa 1 câu hỏi hướng về trải nghiệm/thời lượng.";
        }

        return new LightweightCustomerUnderstandingPlan(
                new LightweightCustomerState(needs, emotions, concerns, buyingSignals, frictionHistory, communicationSignals),
                inferences,
                unknowns,
                recommendedAction,
                priceSubMode,
                directive,
                totalPriceObjections
        );
    }
}
