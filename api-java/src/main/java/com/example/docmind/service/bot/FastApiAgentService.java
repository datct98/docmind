package com.example.docmind.service.bot;

import com.example.docmind.client.FastApiAgentClient;
import com.example.docmind.client.FastApiAgentClient.ConsultRequest;
import com.example.docmind.client.FastApiAgentClient.ConsultResponse;
import com.example.docmind.client.FastApiAgentClient.ExtractedSampleDto;
import com.example.docmind.client.FastApiAgentClient.NightlyExtractRequest;
import com.example.docmind.client.FastApiAgentClient.NightlyExtractResponse;
import com.example.docmind.config.DocServiceProperties;
import com.example.docmind.observability.CorrelationIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Orchestrates communication between Spring Boot 4.0.0 Gateway and the
 * FastAPI + LangGraph Engine (`doc-service-py`) via `@HttpExchange`, with
 * an ultra-fast deterministic in-JVM State Graph fallback when running
 * offline or in unit tests.
 */
@Service
public class FastApiAgentService {

    private static final Logger log = LoggerFactory.getLogger(FastApiAgentService.class);

    private static final Pattern MALE_PATTERN = Pattern.compile(
            "(?iU)(?:\\b(?:tôi|mình|mìn|minh|mk|t|em|anh)\\s+(?:là|la|làm)\\s+(?:nam|con\\s+trai|đàn\\s+ông)\\b|"
            + "\\bnhưng\\s+(?:tôi|mình|mìn|mk|t)\\s+là\\s+nam\\b|"
            + "\\bnam\\s+giới\\b|\\bcho\\s+nam\\b|\\bkhách\\s+nam\\b|\\banh\\s+muốn\\b|\\banh\\s+bị\\b)"
    );

    private static final Pattern PROFILE_UPDATE_ONLY_PATTERN = Pattern.compile(
            "(?iU)^[\\s,.\\-!]*(?:nhưng\\s+|mà\\s+|à\\s+)?(?:tôi|mình|mìn|minh|mk|t|anh|chị)?\\s*"
            + "(?:là\\s+nam(?:\\s+giới)?|là\\s+con\\s+trai|là\\s+đàn\\s+ông|nam\\s+nhé|nam\\s+ạ|"
            + "nam\\s+có\\s+làm\\s+được\\s+không|nam\\s+dùng\\s+được\\s+không|"
            + "da\\s+(?:nhạy\\s+cảm|dầu|khô|mỏng)|(?:\\d{2})\\s+tuổi)[\\s?.!]*$"
    );

    private static final Pattern ACNE_THAM_PATTERN = Pattern.compile(
            "(?iU)\\b(?:trị\\s+mụn\\s+lưng|trị\\s+mụn\\s+ẩn|trị\\s+mụn\\s+viêm|mụn\\s+lưng|mụn\\s+ẩn|"
            + "mụn\\s+viêm|mụn\\s+cằm|mụn\\s+đầu\\s+đen|mờ\\s+thâm|trị\\s+thâm|vết\\s+thâm|thâm\\s+mụn|"
            + "nặn\\s+mụn|kháng\\s+viêm|trị\\s+mụn|mụn|thâm)\\b"
    );

    private static final Pattern MELASMA_PICO_PATTERN = Pattern.compile(
            "(?iU)\\b(?:nám|tàn\\s+nhang|đồi\\s+mồi|sạm\\s+da|sắc\\s+tố|laser\\s+pico|pico)\\b"
    );

    private static final Pattern MESO_REJUV_PATTERN = Pattern.compile(
            "(?iU)\\b(?:căng\\s+bóng|trẻ\\s+hóa|meso|collagen|lão\\s+hóa|nếp\\s+nhăn|chảy\\s+xệ)\\b"
    );

    private static final Pattern ACNE_EXCLUDE_LINE_PATTERN = Pattern.compile(
            "(?iU)(?:Trị\\s+Nám\\s+Laser\\s+Pico|Căng\\s+bóng\\s+Meso\\s+Collagen|4\\.500\\.000|3\\.200\\.000|Laser\\s+Pico|Meso\\s+Collagen)"
    );

    private static final Pattern INJECTION_AUDIT_PATTERN = Pattern.compile(
            "(?iU)(?:ignore\\s+(?:all\\s+)?(?:previous|prior|above)\\s+instructions|"
            + "disregard\\s+system\\s+prompt|reveal\\s+(?:your\\s+)?(?:system\\s+prompt|api\\s*key)|"
            + "bỏ\\s+qua\\s+(?:mọi|tất\\s+cả|toàn\\s+bộ)\\s+(?:quy\\s+tắc|hướng\\s+dẫn|lệnh)|"
            + "quên\\s+(?:hết|tất\\s+cả)\\s+(?:quy\\s+tắc|hướng\\s+dẫn)|"
            + "jailbreak|dan\\s+mode)"
    );

    private static final Pattern UNAUTHORIZED_DISCOUNT_PATTERN = Pattern.compile(
            "(?iU)(?:giảm\\s+(?:giá\\s+)?(?:[3-9]\\d|100)\\s*%|"
            + "chiết\\s+khấu\\s+(?:[3-9]\\d|100)\\s*%|"
            + "miễn\\s+phí\\s+(?:100%\\s+)?(?:toàn\\s+bộ|tất\\s+cả|mọi)\\s+(?:liệu\\s+trình|dịch\\s+vụ|gói)|"
            + "(?:báo\\s+giá|bán|đồng\\s+giá)\\s+0\\s*(?:đ|vnđ|vnd|đồng)\\s+cho\\s+(?:mọi|tất\\s+cả|toàn\\s+bộ))"
    );

    private static final Pattern VN_PHONE_MASK_PATTERN = Pattern.compile(
            "(?<!\\d)(?:\\+84|84|0)(?:3[2-9]|5[25689]|7[06-9]|8[1-9]|9[0-46-9])(?:[\\s.\\-]?\\d){7}(?!\\d)"
    );

    private final FastApiAgentClient client;
    private final String internalToken;

    @Autowired
    public FastApiAgentService(@Autowired(required = false) DocServiceProperties props) {
        String baseUrl = (props != null && props.getBaseUrl() != null && !props.getBaseUrl().isBlank())
                ? props.getBaseUrl()
                : "http://localhost:8091";
        this.internalToken = (props != null && props.getInternalToken() != null && !props.getInternalToken().isBlank())
                ? props.getInternalToken()
                : "docmind-internal-secret-token-2026";

        HttpClient jdkHttp = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(1500))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(jdkHttp);
        requestFactory.setReadTimeout(Duration.ofMillis(3500));

        RestClient restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();

        HttpServiceProxyFactory factory = HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build();
        this.client = factory.createClient(FastApiAgentClient.class);
    }

    public FastApiAgentService(FastApiAgentClient customClient, String internalToken) {
        this.client = customClient;
        this.internalToken = internalToken != null ? internalToken : "docmind-internal-secret-token-2026";
    }

    public FastApiAgentService(FastApiAgentClient customClient, DocServiceProperties props) {
        this(customClient, props != null && props.getInternalToken() != null ? props.getInternalToken() : "docmind-internal-secret-token-2026");
    }

    public ConsultResponse consult(
            String customerPsid,
            String pageId,
            String message,
            List<Map<String, String>> history,
            List<Map<String, Object>> candidateChunks,
            String honorific) {
        return consult(new ConsultRequest(
                customerPsid != null && !customerPsid.isBlank() ? customerPsid : "anonymous_customer",
                pageId,
                message,
                history != null ? history : List.of(),
                candidateChunks != null ? candidateChunks : List.of(),
                List.of(),
                honorific != null && !honorific.isBlank() ? honorific : "Chị"
        ));
    }

    private static String resolveCorrelationId() {
        String cid = CorrelationIds.currentCorrelationId();
        return (cid != null && !cid.isBlank()) ? cid : CorrelationIds.newId();
    }

    /**
     * Execute Hot-Path LangGraph state-driven consultation & topic-anchor contextualization.
     */
    public ConsultResponse consult(ConsultRequest request) {
        if (client != null) {
            try {
                ConsultResponse remote = client.consultCustomer(
                        internalToken,
                        resolveCorrelationId(),
                        request
                );
                if (remote != null && remote.rewrittenQuery() != null) {
                    return remote;
                }
            } catch (Exception ex) {
                log.debug("FastAPI LangGraph consult unreachable ({}), using local state-graph fallback", ex.getMessage());
            }
        }
        return runLocalStateGraphFallback(request);
    }

    /**
     * Execute Cold-Path Nightly Training & Security Audit Graph.
     */
    public NightlyExtractResponse extractNightlySamples(NightlyExtractRequest request) {
        if (client != null) {
            try {
                NightlyExtractResponse remote = client.extractGoldenSample(
                        internalToken,
                        resolveCorrelationId(),
                        request
                );
                if (remote != null && remote.samples() != null) {
                    return remote;
                }
            } catch (Exception ex) {
                log.debug("FastAPI LangGraph nightly-extract unreachable ({}), using local auditor fallback", ex.getMessage());
            }
        }
        return runLocalNightlyFallback(request);
    }

    public ConsultResponse runLocalStateGraphFallback(ConsultRequest req) {
        String rawMsg = req.message() != null ? req.message().trim() : "";
        List<Map<String, String>> history = req.history() != null ? req.history() : List.of();

        List<String> userTurns = new ArrayList<>();
        int assistantTurns = 0;
        for (Map<String, String> turn : history) {
            String role = turn.getOrDefault("role", "").toLowerCase(Locale.ROOT);
            String content = turn.getOrDefault("content", "").trim();
            if ("user".equals(role) && !content.isEmpty()) {
                userTurns.add(content);
            } else if ("assistant".equals(role) && !content.isEmpty()) {
                assistantTurns++;
            }
        }
        int turnIndex = assistantTurns + 1;

        String combinedUserText = String.join(" . ", userTurns) + " . " + rawMsg;
        String honorific = req.honorific() != null && !req.honorific().isBlank() ? req.honorific() : "Chị";
        if (MALE_PATTERN.matcher(rawMsg).find() || MALE_PATTERN.matcher(combinedUserText).find()) {
            honorific = "Anh";
        }

        TopicDetection topic = detectTopic(rawMsg);
        boolean currentHasNewTopic = topic != null;
        if (topic == null) {
            for (int i = userTurns.size() - 1; i >= 0; i--) {
                topic = detectTopic(userTurns.get(i));
                if (topic != null) {
                    break;
                }
            }
        }

        String topicFamily = topic != null ? topic.family : "GENERAL";
        String activeTopic = topic != null ? topic.label : "Tư vấn chăm sóc & điều trị da chuẩn Y khoa";
        List<String> topicKeywords = topic != null ? topic.keywords : List.of();

        boolean isProfileUpdate = turnIndex > 1
                && !currentHasNewTopic
                && (MALE_PATTERN.matcher(rawMsg).find() || PROFILE_UPDATE_ONLY_PATTERN.matcher(rawMsg).find());

        String rewrittenQuery;
        if (isProfileUpdate && !"GENERAL".equals(topicFamily)) {
            rewrittenQuery = activeTopic + " " + String.join(" ", topicKeywords)
                    + " phù hợp cho khách hàng " + honorific + " (Nam giới) — " + rawMsg;
        } else if (turnIndex > 1 && !currentHasNewTopic && !"GENERAL".equals(topicFamily) && rawMsg.length() <= 65) {
            rewrittenQuery = activeTopic + " (" + String.join(" ", topicKeywords) + "): " + rawMsg;
        } else {
            rewrittenQuery = rawMsg;
        }

        String funnelStage = classifyFunnelStage(rawMsg);

        List<Map<String, Object>> filteredChunks = new ArrayList<>();
        if (req.candidateChunks() != null) {
            for (Map<String, Object> ch : req.candidateChunks()) {
                String text = String.valueOf(ch.getOrDefault("text", ""));
                if ("ACNE_THAM".equals(topicFamily) && !text.isBlank()) {
                    List<String> kept = new ArrayList<>();
                    for (String line : text.split("\\r?\\n")) {
                        if (!ACNE_EXCLUDE_LINE_PATTERN.matcher(line).find()) {
                            kept.add(line);
                        }
                    }
                    String cleaned = String.join("\n", kept).trim();
                    if (!cleaned.isEmpty()) {
                        filteredChunks.add(Map.of(
                                "chunk_id", ch.getOrDefault("chunk_id", ""),
                                "text", cleaned
                        ));
                    }
                } else {
                    filteredChunks.add(ch);
                }
            }
        }

        StringBuilder directive = new StringBuilder();
        directive.append("[LANGGRAPH STATE ENGINE — BƯỚC PHỄU: ").append(funnelStage)
                .append(" | LƯỢT CHAT: #").append(turnIndex).append("]\n");
        directive.append("- DANH XƯNG BẮT BUỘC 100%: Gọi khách là \"").append(honorific)
                .append("\" (TUYỆT ĐỐI KHÔNG dùng danh xưng khác).\n");
        directive.append("- CHỦ ĐỀ ĐANG NEO (ACTIVE TOPIC ANCHOR): ").append(activeTopic)
                .append(" (Mã nhóm: ").append(topicFamily).append(").\n");
        if (turnIndex >= 2) {
            directive.append("- QUY TẮC ĐA LƯỢT: Đây là lượt chat tiếp nối (Turn >= 2). CẤM TUYỆT ĐỐI chào lại ")
                    .append("(\"Dạ em chào...\") và CẤM giới thiệu lại danh mục dịch vụ tổng quan từ đầu!\n");
        }
        if (isProfileUpdate) {
            directive.append("- CHỈ THỊ ĐẶC BIỆT (CẬP NHẬT HỒ SƠ KHÁCH HÀNG): Khách vừa bổ sung thông tin cá nhân (\"")
                    .append(rawMsg).append("\") trong khi đang tìm hiểu về [").append(activeTopic).append("]. ")
                    .append("Hãy xác nhận ngay rằng phác đồ [").append(activeTopic)
                    .append("] hoàn toàn phù hợp & hiệu quả cao với ").append(honorific)
                    .append(" (có phòng trị liệu riêng tư kín đáo), giữ nguyên mạch tư vấn [")
                    .append(activeTopic).append("] và CẤM nhắc tới các dịch vụ ngoài lề (như Nám Laser Pico hay Meso Collagen)!");
        }

        String psid = req.customerPsid() != null ? req.customerPsid() : "anonymous_customer";
        return new ConsultResponse(
                "local-state-graph",
                psid,
                (req.pageId() != null ? req.pageId() : "global") + ":" + psid,
                honorific,
                activeTopic,
                topicFamily,
                topicKeywords,
                isProfileUpdate,
                turnIndex == 1 && !currentHasNewTopic && rawMsg.length() <= 15,
                turnIndex,
                funnelStage,
                rewrittenQuery,
                filteredChunks,
                List.of(),
                directive.toString(),
                Map.of("customer_psid", psid, "psid_locked", true, "funnel_stage", funnelStage)
        );
    }

    private NightlyExtractResponse runLocalNightlyFallback(NightlyExtractRequest req) {
        List<Map<String, Object>> convs = req.conversations() != null ? req.conversations() : List.of();
        List<ExtractedSampleDto> samples = new ArrayList<>();
        int passed = 0;
        int rejected = 0;

        for (Map<String, Object> conv : convs) {
            String custRaw = String.valueOf(conv.getOrDefault("customer_message", conv.getOrDefault("question", ""))).trim();
            String saleRaw = String.valueOf(conv.getOrDefault("sale_reply", conv.getOrDefault("correction", ""))).trim();
            if (custRaw.isEmpty() && saleRaw.isEmpty() && conv.get("turns") instanceof List<?> turnsList) {
                List<String> uParts = new ArrayList<>();
                List<String> aParts = new ArrayList<>();
                for (Object item : turnsList) {
                    if (item instanceof Map<?, ?> turnMap) {
                        String role = String.valueOf(turnMap.get("role")).toLowerCase(Locale.ROOT);
                        String content = turnMap.get("content") != null ? String.valueOf(turnMap.get("content")).trim() : "";
                        if ("user".equals(role) && !content.isEmpty()) {
                            uParts.add(content);
                        } else if ("assistant".equals(role) && !content.isEmpty()) {
                            aParts.add(content);
                        }
                    }
                }
                custRaw = String.join(" | ", uParts);
                saleRaw = String.join(" ", aParts);
            }
            String custMasked = VN_PHONE_MASK_PATTERN.matcher(custRaw).replaceAll("[PHONE_REDACTED]");
            String saleMasked = VN_PHONE_MASK_PATTERN.matcher(saleRaw).replaceAll("[PHONE_REDACTED]");

            String combined = custMasked + "\n" + saleMasked;
            List<String> violations = new ArrayList<>();
            if (INJECTION_AUDIT_PATTERN.matcher(combined).find()) {
                violations.add("INDIRECT_PROMPT_INJECTION");
            }
            if (UNAUTHORIZED_DISCOUNT_PATTERN.matcher(combined).find()) {
                violations.add("UNAUTHORIZED_DISCOUNT_OR_PRICE_PROMISE");
            }

            boolean ok = violations.isEmpty();
            if (ok) passed++;
            else rejected++;

            String stage = classifyFunnelStage(custMasked);
            samples.add(new ExtractedSampleDto(
                    stage,
                    "INV",
                    "PSYCHOLOGICAL_PERSUASION",
                    custMasked,
                    "Khi khách ở giai đoạn " + stage + " nhắn: \"" + custMasked + "\"",
                    "Nhân viên Sale thực chiến giữ đúng mạch chủ đề và đặt câu hỏi chốt lịch hẹn.",
                    "[" + stage + "] Khi gặp tình huống tương tự \"" + custMasked + "\", áp dụng phản hồi: " + saleMasked,
                    "❌ Trước: Trả lời chung chung hoặc lệch chủ đề.\n✅ Sau (Chuẩn Sale): " + saleMasked,
                    List.of("tư vấn", "chốt lịch"),
                    ok ? "PENDING_REVIEW" : "REJECTED",
                    ok,
                    ok ? "LOW" : "HIGH",
                    violations,
                    ok
                            ? "AI Security Auditor: Đạt chuẩn an toàn (0 Prompt Injection, 0 sai lệch chính sách giá, 0 lộ PII)."
                            : "AI Security Auditor CHẶN quy tắc do phát hiện rủi ro bảo mật/nghiệp vụ: " + String.join(", ", violations)
            ));
        }

        return new NightlyExtractResponse(
                "local-nightly-auditor",
                req.pageId(),
                passed,
                rejected,
                samples
        );
    }

    private record TopicDetection(String family, String label, List<String> keywords) {}

    private TopicDetection detectTopic(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher mAcne = ACNE_THAM_PATTERN.matcher(text);
        if (mAcne.find()) {
            Set<String> matched = new LinkedHashSet<>();
            matched.add(mAcne.group(0).toLowerCase(Locale.ROOT));
            while (mAcne.find()) {
                matched.add(mAcne.group(0).toLowerCase(Locale.ROOT));
            }
            List<String> kws = new ArrayList<>(List.of("Trị Mụn Chuẩn Y Khoa", "mụn", "mờ thâm"));
            kws.addAll(matched);
            return new TopicDetection(
                    "ACNE_THAM",
                    "Trị mụn & Mờ thâm chuyên sâu (" + String.join(", ", matched) + ")",
                    kws
            );
        }
        if (MELASMA_PICO_PATTERN.matcher(text).find()) {
            return new TopicDetection(
                    "MELASMA_PICO",
                    "Trị nám, tàn nhang & sắc tố Laser Pico",
                    List.of("Trị Nám Laser Pico", "nám", "tàn nhang")
            );
        }
        if (MESO_REJUV_PATTERN.matcher(text).find()) {
            return new TopicDetection(
                    "MESO_REJUV",
                    "Căng bóng & Trẻ hóa Meso Collagen",
                    List.of("Căng bóng Meso Collagen", "căng bóng", "trẻ hóa")
            );
        }
        return null;
    }

    private String classifyFunnelStage(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (lower.contains("đặt lịch") || lower.contains("giữ suất") || lower.contains("sáng mai")
                || lower.contains("chiều mai") || VN_PHONE_MASK_PATTERN.matcher(lower).find()) {
            return "CLOSING";
        }
        if (lower.contains("đắt") || lower.contains("cao quá") || lower.contains("giảm giá")
                || lower.contains("combo") || lower.contains("suy nghĩ thêm") || lower.contains("xem thêm")) {
            return "OBJECTION_PRICE";
        }
        if (lower.contains("bảo hành") || lower.contains("địa chỉ") || lower.contains("sưng đỏ")) {
            return "SUPPORT";
        }
        return "CONSULTING";
    }
}
