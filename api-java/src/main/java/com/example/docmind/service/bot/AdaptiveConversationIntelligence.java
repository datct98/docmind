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
            "(?isU).*\\b(gặp\\s+(?:nhân\\s+viên\\s+thật|người\\s+thật|quản\\s+lý|chủ\\s+spa|bác\\s+sĩ)|chuyển\\s+(?:cho\\s+)?(?:quản\\s+lý|người\\s+thật|nhân\\s+viên\\s+trực))\\b.*"
    );

    private static final Pattern EXPLICIT_BOOKING_CANCEL_PATTERN = Pattern.compile(
            "(?isU).*\\b(hủy\\s+lịch|huỷ\\s+lịch|không\\s+đặt\\s+lịch\\s+nữa|bỏ\\s+lịch\\s+hẹn)\\b.*"
    );

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

    private static final Pattern FIRST_PERSON_ANH_SELF_REF_PATTERN = Pattern.compile(
            "(?iu)(?:^|[\\s,;.!?])(?:"
            + "nếu\\s+(?:anh|a)\\b|(?:kể|tư\\s+vấn|báo|gửi|đặt|giữ|xem|chốt|lấy)\\s+cho\\s+(?:anh|a)\\b|"
            + "cho\\s+(?:anh|a)\\s+(?:hỏi|xin|đặt|đăng\\s+ký|chốt|xem|biết|thử|làm)|"
            + "rồi\\s+(?:anh|a)\\s+(?:đăng\\s+ký|đặt|qua|ghé|chốt|làm)|"
            + "(?:thôi\\s+|vậy\\s+|thế\\s+)?anh\\s+(?:không|ko|chưa|đang|muốn|cần|định|sẽ|vừa|mới|bị|thấy|ngại|đăng\\s+ký|đặt)\\b|"
            + "để\\s+anh\\s+(?:xem|nghĩ|suy\\s+nghĩ|tính|hỏi|cân\\s+nhắc|qua|đặt)\\b|"
            + "(?:của|với|giúp)\\s+anh\\b"
            + ")"
    );

    private static final Pattern FIRST_PERSON_CHI_SELF_REF_PATTERN = Pattern.compile(
            "(?iu)(?:^|[\\s,;.!?])(?:"
            + "nếu\\s+chị\\b|(?:kể|tư\\s+vấn|báo|gửi|đặt|giữ|xem|chốt|lấy)\\s+cho\\s+chị\\b|"
            + "cho\\s+chị\\s+(?:hỏi|xin|đặt|đăng\\s+ký|chốt|xem|biết|thử|làm)|"
            + "rồi\\s+chị\\s+(?:đăng\\s+ký|đặt|qua|ghé|chốt|làm)|"
            + "(?:thôi\\s+|vậy\\s+|thế\\s+)?chị\\s+(?:không|ko|chưa|đang|muốn|cần|định|sẽ|vừa|mới|bị|thấy|ngại|đăng\\s+ký|đặt)\\b|"
            + "để\\s+chị\\s+(?:xem|nghĩ|suy\\s+nghĩ|tính|hỏi|cân\\s+nhắc|qua|đặt)\\b|"
            + "(?:của|với|giúp)\\s+chị\\b"
            + ")"
    );

    /**
     * Phát hiện đại từ khách tự xưng rõ ràng ngay trong lượt hiện tại ("nếu anh...", "kể cho a...", "thôi anh không...")
     * khi phiên chưa có {@code priorInferredCustomerPronoun}, giúp Single-Pass LLM xưng hô đúng ngay trong cùng lượt.
     */
    public static String inferExplicitCustomerSelfPronounFromUtterance(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return null;
        }
        String norm = Normalizer.normalize(rawMessage.strip(), Normalizer.Form.NFC);
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
            if (u.contains("READY") || u.contains("BOOK") || u.contains("COMMIT")) {
                return READY_TO_BOOK;
            }
            if (u.contains("CONSIDER") || u.contains("COMPARE") || u.contains("WEIGH")) {
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

    /** Tầng 3 — Business &amp; Commitment State (Tách biệt interested_package vs selected_package). */
    public record BusinessCommitmentLayer(
            String interestedPackageId,
            String selectedPackageId,
            String bookingStatus,
            HandoffLifecycleStatus handoffStatus,
            int pendingCommitmentCount
    ) {
        public BusinessCommitmentLayer {
            interestedPackageId = cleanNullable(interestedPackageId);
            selectedPackageId = cleanNullable(selectedPackageId);
            bookingStatus = (bookingStatus != null && !bookingStatus.isBlank()) ? bookingStatus.strip() : "NONE";
            handoffStatus = handoffStatus != null ? handoffStatus : HandoffLifecycleStatus.NONE;
            pendingCommitmentCount = Math.max(0, pendingCommitmentCount);
        }

        public static BusinessCommitmentLayer initial() {
            return new BusinessCommitmentLayer(null, null, "NONE", HandoffLifecycleStatus.NONE, 0);
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
            String canon = canonicalSymptomKey(cleaned);
            if (!canon.isBlank()) {
                dedupByCanonical.putIfAbsent(canon, cleaned);
            }
        }
        return List.copyOf(dedupByCanonical.values());
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
        return s;
    }

    public static String canonicalSymptomKey(String rawSymptom) {
        String cleaned = cleanAtomicSymptomValue(rawSymptom);
        return cleaned.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    public static String normalizeFactCategoryKey(String rawKey, String rawVal) {
        String k = rawKey != null ? rawKey.strip().toLowerCase(Locale.ROOT) : "";
        if (!k.isEmpty()) {
            if (k.contains("symptom") || k.contains("health_condition") || k.contains("health_issue")) {
                return "self_reported_symptom";
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
        if (v.contains("đắt") || v.contains("giá cao")) {
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
        if (h.contains("chưa rõ giá trị") || h.contains("trọn gói") || h.contains("239k") || h.contains("399k") && h.contains("giá")) {
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

    public static String normalizePlannerNextAction(String raw) {
        if (raw == null || raw.isBlank()) {
            return "NO_ADDITIONAL_ACTION";
        }
        String u = raw.strip().toUpperCase(Locale.ROOT);
        if (u.contains("NO_ADDITIONAL") || "NONE".equals(u) || "STOP".equals(u) || "NO_QUESTION".equals(u)) {
            return "NO_ADDITIONAL_ACTION";
        }
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

        public AdaptiveCognitiveReflection withForcedNoCta() {
            AdaptiveResponsePlan noCtaPlan = new AdaptiveResponsePlan(
                    responsePlan.objective(),
                    responsePlan.strategy(),
                    responsePlan.tone(),
                    responsePlan.depth(),
                    "NO_ADDITIONAL_ACTION",
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
            if (rawUserMessage == null || rawUserMessage.isBlank()
                    || PromptSecurityGuard.isDirectJailbreakAttempt(rawUserMessage)) {
                return this;
            }
            String normLower = Normalizer.normalize(rawUserMessage.strip(), Normalizer.Form.NFC)
                    .toLowerCase(Locale.ROOT);
            boolean explicitStop = CleanAgentOrchestratorService.isExplicitConversationRefusalStopMessage(rawUserMessage);
            boolean directBotIdentityQuestion = CleanAgentOutputRefereeAndSanitizer
                    .isDirectBotIdentityOrTrainingQuestion(rawUserMessage);
            boolean processFeedbackComplaint = !explicitStop
                    && !directBotIdentityQuestion
                    && (CleanAgentOutputRefereeAndSanitizer.isProcessOrConsultationFeedbackComplaint(rawUserMessage)
                        || (customerUnderstanding.secondaryIntents() != null
                            && customerUnderstanding.secondaryIntents().stream().anyMatch(s ->
                                s != null && (s.toUpperCase(Locale.ROOT).contains("PROCESS_FEEDBACK")
                                        || s.toUpperCase(Locale.ROOT).contains("CONSULTATION_FEEDBACK")
                                        || s.toUpperCase(Locale.ROOT).contains("STYLE_COMPLAINT")))));
            boolean pureBudgetBoundedInquiry = !explicitStop
                    && !processFeedbackComplaint
                    && CleanAgentOrchestratorService.isPureBudgetBoundedServiceInquiry(rawUserMessage);
            boolean competitorComparison = !explicitStop
                    && !processFeedbackComplaint
                    && !pureBudgetBoundedInquiry
                    && CleanAgentOrchestratorService.hasCompetitorOrExternalPriceComparisonCue(rawUserMessage);
            boolean negatedBooking = normLower.matches(
                    "(?isU).*\\b(?:không|ko|chưa|khỏi|đừng|chẳng)\\s+(?:muốn\\s+|cần\\s+|vội\\s+|định\\s+)?(?:đăng\\s+ký\\s+)?(?:đặt|book|chốt)\\s+lịch\\b.*"
            );
            boolean affirmativeBookingCue = !explicitStop && !processFeedbackComplaint && !negatedBooking && normLower.matches(
                    "(?isU).*\\b(đăng\\s+ký\\s+đặt\\s+lịch|(?:rồi\\s+)?(?:anh|chị|mình|tôi|em|a)\\s+(?:đăng\\s+ký\\s+)?(?:đặt|book|chốt)\\s+lịch|đặt\\s+lịch\\s+(?:nhé|nha|giúp|cho)|chốt\\s+lịch\\s+(?:nhé|nha|luôn))\\b.*"
            );
            if (!explicitStop
                    && !processFeedbackComplaint
                    && !pureBudgetBoundedInquiry
                    && !competitorComparison
                    && !affirmativeBookingCue) {
                return this;
            }

            CustomerUnderstanding cu = customerUnderstanding;
            String detIntent = cu.detectedIntent();
            EmotionalState emoState = cu.emotionalState();
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
                plan = new AdaptiveResponsePlan(
                        "Acknowledge consultation process feedback and adjust communication pace without defending AI identity",
                        "Active Listening",
                        "Sincere & Attentive",
                        "CONCISE",
                        "NO_ADDITIONAL_ACTION",
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
                            || "self_reported_symptom".equalsIgnoreCase(d.key())));
                reconciledBookingPatch = ProposedBookingPatch.empty();
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
                String reconciledStrat = ("Explain Value".equalsIgnoreCase(plan.strategy())
                        || "Respectful Pause".equalsIgnoreCase(plan.strategy())
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
                if ("POLITE_DEFERRAL".equalsIgnoreCase(detIntent)
                        || "HESITATION".equalsIgnoreCase(detIntent)
                        || isExplicitStopOrRefusalIntentLabel(detIntent)) {
                    if (secIntents.stream().noneMatch(s -> s.toUpperCase(Locale.ROOT).contains("EXASPERATION"))) {
                        secIntents.add("EXASPERATION_REMARK");
                    }
                    detIntent = "COMPETITOR_PRICE_OBJECTION";
                }
                costConcern = Boolean.TRUE;
                convState = "OBJECTION_NEGOTIATION";
                effBarrier = "COMPETITOR_COMPARISON";
                if (perm == InteractionPermission.PAUSED) {
                    perm = InteractionPermission.OPEN;
                }
                if ("POLITE_CLOSURE".equalsIgnoreCase(prio.primary())) {
                    prio = new PriorityFocus("VALUE_ALIGNMENT", prio.secondary());
                }
                String reconciledStrat = ("Respectful Pause".equalsIgnoreCase(plan.strategy()) || plan.strategy().isBlank())
                        ? "Explain Value"
                        : plan.strategy();
                StrategyAdaptationMode reconciledAdapt = plan.adaptation() == StrategyAdaptationMode.STOP
                        ? StrategyAdaptationMode.REFINEMENT
                        : plan.adaptation();
                CtaLevel reconciledCta = plan.ctaLevel() == CtaLevel.NO_CTA ? CtaLevel.SOFT_CTA : plan.ctaLevel();
                String reconciledNext = "NO_ADDITIONAL_ACTION".equalsIgnoreCase(plan.nextAction())
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
            }

            CustomerUnderstanding reconciledCu = new CustomerUnderstanding(
                    detIntent,
                    emoState,
                    cu.inferredCustomerPronoun(),
                    cu.pronounConfidence(),
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
                    handoffRequest,
                    reconciledBookingPatch,
                    blockedUnauthorizedCompletionCount,
                    parsedFromV2Schema,
                    reconciledSkills,
                    explicitActivatedSkillCodes,
                    currentTopic,
                    unresolvedRequestsDelta,
                    pendingReferences,
                    conversationGoal,
                    needRevisionDetected,
                    reconciledFactsDelta,
                    effBarrier,
                    hypothesisConfidenceLevel,
                    interestedPackageId,
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
            // Tương thích ngược cho test cũ vẫn truyền mảng self_reported_symptoms rời
            JsonNode symNode = cuNode.get("self_reported_symptoms");
            if (symNode != null) {
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
            String rawLifeOp = readTextOrDefault(whNode, "lifecycle_op", readTextOrDefault(whNode, "operation", "MAINTAIN"));
            String hypText = readTextOrDefault(whNode, "hypothesis", readTextOrDefault(whNode, "content", ""));
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
                    String rawOp = readTextOrDefault(el, "op", null);
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
                String keptContent = !safePrior.content().isEmpty() ? safePrior.content() : newContent;
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

        String category = null;
        String effectiveReason = llmReason;
        boolean mandatoryVerificationOrHandoff = false;

        if (safetyContraindication) {
            mandatoryVerificationOrHandoff = true;
            category = "MEDICAL_SAFETY_HANDOFF";
            if (effectiveReason == null || effectiveReason.isBlank()) {
                effectiveReason = "Khách hàng có dấu hiệu thai kỳ, triệu chứng thần kinh cấp tính hoặc cảnh báo an toàn chuyên ngành — chuyển chuyên viên hỗ trợ trực tiếp.";
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

        if (originalCustomerMessage != null && EXPLICIT_BOOKING_CANCEL_PATTERN.matcher(originalCustomerMessage).matches()) {
            return ConversationState.StructuredBookingState.none();
        }

        // Chặn lỗi tự hiểu nhầm "Ok" / "Ừ" / "Để xem đã" thành xác nhận đặt lịch:
        // Khi chưa có bookingState mở (status == NONE), nếu khách chỉ nhắn bare ack ("Ok", "Ừ", "Vâng") hoặc câu lưỡng lự/xin suy nghĩ thêm,
        // BẮT BUỘC giữ nguyên BookingStatus.NONE bất kể lượt bot liền trước có câu mời nhẹ hay không!
        boolean isBareAck = CleanAgentOrchestratorService.isBareAcknowledgmentMessage(originalCustomerMessage);
        boolean isDeferOrHesitation = CleanAgentOrchestratorService.isHesitationOrDeferralMessage(originalCustomerMessage);
        if ((isBareAck || isDeferOrHesitation) && safePrior.status() == ConversationState.BookingStatus.NONE) {
            return safePrior;
        }

        boolean hasPatch = patch != null && patch.hasAnyProposal();
        if (!hasPatch) {
            return safePrior;
        }

        // Chặn nhảy cóc sang AWAITING_DATE_OR_TIME khi khách mới EXPLORING / CONSIDERING (ví dụ: "Mình muốn hỏi gói dưỡng sinh 239k"):
        // Khi chưa có lịch mở (status == NONE) và khách chưa nêu bất kỳ ngày/giờ hẹn nào, chỉ cho phép mở AWAITING_DATE_OR_TIME
        // khi khách thực sự ở trạng thái READY_TO_BOOK (hoặc ưu tiên chính là BOOKING_COMPLETION).
        boolean hasDateOrTimeProposal = patch.proposedDate() != null || patch.proposedTime() != null;
        if (safePrior.status() == ConversationState.BookingStatus.NONE && !hasDateOrTimeProposal) {
            if (bookingReadiness != null && bookingReadiness != BookingReadiness.READY_TO_BOOK) {
                return safePrior;
            }
            if (bookingReadiness == null
                    && primaryPriority != null
                    && !primaryPriority.isBlank()
                    && !"BOOKING_COMPLETION".equalsIgnoreCase(primaryPriority.strip())) {
                return safePrior;
            }
        }

        // Nếu lịch đã CONFIRMED thực sự từ nguồn có thẩm quyền và khách không đổi sản phẩm/ngày/giờ -> giữ nguyên CONFIRMED
        String nextProductId = safePrior.selectedProductId();
        String nextProductLabel = safePrior.selectedProductLabel();
        if (patch.selectedProductId() != null) {
            PackageCatalogService.PackageSummary resolvedPkg = resolveCatalogProductByFlexibleIdentifier(
                    patch.selectedProductId(), presentedPackagesInHistory, allCatalog);
            if (resolvedPkg != null) {
                nextProductId = resolvedPkg.productId();
                nextProductLabel = resolvedPkg.canonicalLabel();
            }
        } else if ((nextProductId == null || nextProductId.isBlank())
                && (patch.proposedDate() != null || patch.proposedTime() != null)) {
            // Nếu khách chỉ định rõ gói ngay trong câu đặt lịch hoặc lịch sử chỉ đang trao đổi đúng 1 gói duy nhất
            // (không mơ hồ giữa >= 2 sản phẩm), kế thừa gói đó một cách xác định.
            PackageCatalogService.PackageSummary explicitInMsg = resolveCatalogProductByFlexibleIdentifier(
                    originalCustomerMessage, presentedPackagesInHistory, allCatalog);
            if (explicitInMsg != null) {
                nextProductId = explicitInMsg.productId();
                nextProductLabel = explicitInMsg.canonicalLabel();
            } else if (presentedPackagesInHistory != null && presentedPackagesInHistory.size() == 1
                    && presentedPackagesInHistory.get(0) != null) {
                PackageCatalogService.PackageSummary solePresented = presentedPackagesInHistory.get(0);
                nextProductId = solePresented.productId();
                nextProductLabel = solePresented.canonicalLabel();
            }
        }

        String nextDate = safePrior.bookingDate();
        String nextIsoDate = safePrior.resolvedIsoDate();
        String nextTime = safePrior.bookingTime();

        if (patch.proposedDate() != null || patch.proposedTime() != null) {
            String combinedTimeInput = ((patch.proposedDate() != null ? patch.proposedDate() : "")
                    + " " + (patch.proposedTime() != null ? patch.proposedTime() : "")).strip();
            ConversationState.ParsedBookingDateTime parsedFromPatch = ConversationState.parseBookingDateTime(
                    combinedTimeInput,
                    nextDate,
                    nextTime,
                    safeZone,
                    LocalDate.now(safeZone)
            );
            // Cũng đối chiếu với tin nhắn gốc để lấy mốc ngày tương đối chính xác (VD: "tối nay lúc 19h" -> bookingDate="tối nay", bookingTime="19:00")
            ConversationState.ParsedBookingDateTime parsedFromMsg = ConversationState.parseBookingDateTime(
                    originalCustomerMessage,
                    null,
                    null,
                    safeZone,
                    LocalDate.now(safeZone)
            );

            if (patch.proposedDate() != null) {
                nextDate = parsedFromMsg.bookingDate() != null ? parsedFromMsg.bookingDate()
                        : (parsedFromPatch.bookingDate() != null ? parsedFromPatch.bookingDate() : patch.proposedDate());
                nextIsoDate = parsedFromMsg.resolvedIsoDate() != null ? parsedFromMsg.resolvedIsoDate()
                        : parsedFromPatch.resolvedIsoDate();
            } else if (nextDate == null && parsedFromMsg.bookingDate() != null) {
                nextDate = parsedFromMsg.bookingDate();
                nextIsoDate = parsedFromMsg.resolvedIsoDate();
            }

            if (patch.proposedTime() != null) {
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
            }
        }

        boolean changedAnySlot = !Objects.equals(nextProductId, safePrior.selectedProductId())
                || !Objects.equals(nextDate, safePrior.bookingDate())
                || !Objects.equals(nextTime, safePrior.bookingTime());
        if (!changedAnySlot && safePrior.status() != ConversationState.BookingStatus.NONE) {
            return safePrior;
        }

        List<String> candidateIds = new ArrayList<>();
        if (presentedPackagesInHistory != null) {
            for (PackageCatalogService.PackageSummary p : presentedPackagesInHistory) {
                if (p != null && p.productId() != null && !candidateIds.contains(p.productId())) {
                    candidateIds.add(p.productId());
                }
            }
        }

        ConversationState.BookingStatus nextStatus;
        if (nextProductId == null || nextProductId.isBlank()) {
            if (nextDate != null || nextTime != null) {
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
}
