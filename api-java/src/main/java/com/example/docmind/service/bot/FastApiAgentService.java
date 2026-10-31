package com.example.docmind.service.bot;

import com.example.docmind.client.FastApiAgentClient;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Orchestrates Cold-Path Nightly Training & Security Audit communication between
 * Spring Boot 4.0.0 Gateway and `doc-service-py` (`/internal/v1/nightly-extract`),
 * with a deterministic in-JVM security auditor fallback when running offline.
 */
@Service
public class FastApiAgentService {

    private static final Logger log = LoggerFactory.getLogger(FastApiAgentService.class);

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

    private static String resolveCorrelationId() {
        String cid = CorrelationIds.currentCorrelationId();
        return (cid != null && !cid.isBlank()) ? cid : CorrelationIds.newId();
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
