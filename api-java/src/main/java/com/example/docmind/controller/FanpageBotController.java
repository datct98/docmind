package com.example.docmind.controller;

import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.dto.bot.FanpageBotResponse;
import com.example.docmind.service.bot.ConversationInboxService;
import com.example.docmind.service.bot.FanpageBotService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * REST API chuyên biệt cho BOT TRỰC TIN NHẮN FANPAGE FACEBOOK (Customer-Facing Conversational Sales Bot).
 *
 * <p>Tách biệt hoàn toàn với {@code POST /api/chat} (vốn phục vụ nhân viên nội bộ tra cứu Q&A & dạy AI):
 * <ul>
 *   <li>Không xuất trích dẫn nguồn {@code [1]}, không hiện tiêu đề kỹ thuật.</li>
 *   <li>Không kích hoạt ghi mới vào {@code AI Playbook} từ tin nhắn của khách hàng.</li>
 *   <li>Tự động điều tiết nhịp tư vấn 3 giai đoạn (Không vội vã), tách bong bóng tin nhắn ({@code reply_bubbles}),
 *       và bật cờ chuyển nhân viên thật ({@code needs_human_handoff}) khi gặp câu hỏi khó hoặc không chắc chắn.</li>
 *   <li>Hỗ trợ sẵn Webhook chuẩn Meta Messenger ({@code GET/POST /api/bot/fanpage/webhook}) kèm kiểm tra chữ ký
 *       {@code X-Hub-Signature-256} (HmacSHA256) và lọc bỏ sự kiện {@code is_echo} / {@code delivery} / {@code read}.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/bot/fanpage")
public class FanpageBotController {

    private static final Logger log = LoggerFactory.getLogger(FanpageBotController.class);

    public static final String DEFAULT_NANG_SEN_PAGE_ID = "b23cd382-4f7a-4829-8c54-1d410da14bf8";
    public static final String FB_GRAPH_MESSAGES_API_URL = "https://graph.facebook.com/v21.0/me/messages";
    public static final String FB_GRAPH_MESSAGE_LOOKUP_BASE_URL = "https://graph.facebook.com/v21.0/";
    public static final long GRAPH_API_LOOKUP_TIMEOUT_MS = 1200L;
    public static final long MID_DEDUP_TTL_MS = 10 * 60 * 1000L; // 10 phút

    @FunctionalInterface
    public interface FacebookGraphApiSender {
        HttpResponseResult postJson(String url, String jsonBody) throws Exception;
    }

    @FunctionalInterface
    public interface FacebookGraphApiGetter {
        HttpResponseResult getJson(String url, long timeoutMs) throws Exception;
    }

    public record HttpResponseResult(int statusCode, String body) {}

    private static final class SenderBatchCoordinator {
        final Object lock = new Object();
        final List<String> pendingTexts = new ArrayList<>();
        final List<String> pendingDedupKeys = new ArrayList<>();
        String latestReplyToMid;
        String latestQuotedMessageText;
        long firstMessageArrivedAtMs;
        long debounceDeadlineMs;
        boolean debounceLeaderActive;
        boolean batchCurrentlyExecuting;
        boolean runningLlmCanSupersede;
        boolean sendingBubblesCanInterrupt;
        List<String> inFlightTexts = new ArrayList<>();
        String inFlightReplyToMid;
        String inFlightQuotedMessageText;
        FanpageBotResponse lastCompletedResponse;
    }

    private final java.util.concurrent.ConcurrentHashMap<String, SenderBatchCoordinator> senderCoordinators =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final FanpageBotService fanpageBotService;
    private final ConversationInboxService conversationInboxService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private FacebookGraphApiSender graphApiSender = this::defaultPostJsonToGraphApi;
    private FacebookGraphApiGetter graphApiGetter = this::defaultGetJsonFromGraphApi;

    @Value("${docmind.fanpage.bubble-delay-ms:2500}")
    private long interBubbleDelayMs = 0L;

    @Value("${docmind.fanpage.burst-debounce-ms:1800}")
    private long burstDebounceMs = 0L;

    @Value("${docmind.fanpage.max-burst-wait-ms:3000}")
    private long maxBurstWaitMs = 3000L;

    @Value("${docmind.fanpage.verify-token:docmind-fb-verify-token}")
    private String verifyToken = "docmind-fb-verify-token";

    @Value("${docmind.fanpage.app-secret:}")
    private String appSecret = "";

    @Value("${docmind.fanpage.page-access-token:}")
    private String pageAccessToken = "";

    @Value("${docmind.fanpage.default-page-id:b23cd382-4f7a-4829-8c54-1d410da14bf8}")
    private String defaultPageId = DEFAULT_NANG_SEN_PAGE_ID;

    private Duration inFlightLeaseDuration =
            com.example.docmind.service.bot.SentBubbleLedger.DEFAULT_IN_FLIGHT_LEASE_DURATION;

    public FanpageBotController(FanpageBotService fanpageBotService) {
        this(fanpageBotService, null);
    }

    @Autowired
    public FanpageBotController(FanpageBotService fanpageBotService, ConversationInboxService conversationInboxService) {
        this.fanpageBotService = fanpageBotService;
        this.conversationInboxService = conversationInboxService;
    }

    public void setPageAccessToken(String pageAccessToken) {
        this.pageAccessToken = pageAccessToken != null ? pageAccessToken : "";
    }

    public String getPageAccessToken() {
        return this.pageAccessToken;
    }

    public void setDefaultPageId(String defaultPageId) {
        this.defaultPageId = (defaultPageId != null && !defaultPageId.isBlank())
                ? defaultPageId.strip()
                : DEFAULT_NANG_SEN_PAGE_ID;
    }

    public String getDefaultPageId() {
        return this.defaultPageId;
    }

    public void setGraphApiSender(FacebookGraphApiSender graphApiSender) {
        this.graphApiSender = graphApiSender != null ? graphApiSender : this::defaultPostJsonToGraphApi;
    }

    public void setGraphApiGetter(FacebookGraphApiGetter graphApiGetter) {
        this.graphApiGetter = graphApiGetter != null ? graphApiGetter : this::defaultGetJsonFromGraphApi;
    }

    public void setInterBubbleDelayMs(long interBubbleDelayMs) {
        this.interBubbleDelayMs = Math.max(0L, interBubbleDelayMs);
    }

    public long getInterBubbleDelayMs() {
        return this.interBubbleDelayMs;
    }

    public void setBubbleDelayMs(long bubbleDelayMs) {
        this.interBubbleDelayMs = Math.max(0L, bubbleDelayMs);
    }

    public long getBubbleDelayMs() {
        return this.interBubbleDelayMs;
    }

    public void setBurstDebounceMs(long burstDebounceMs) {
        this.burstDebounceMs = Math.max(0L, burstDebounceMs);
    }

    public long getBurstDebounceMs() {
        return this.burstDebounceMs;
    }

    public void setMaxBurstWaitMs(long maxBurstWaitMs) {
        this.maxBurstWaitMs = Math.max(200L, maxBurstWaitMs);
    }

    public void setInFlightLeaseDuration(Duration inFlightLeaseDuration) {
        this.inFlightLeaseDuration = (inFlightLeaseDuration != null
                && !inFlightLeaseDuration.isNegative()
                && !inFlightLeaseDuration.isZero())
                ? inFlightLeaseDuration
                : com.example.docmind.service.bot.SentBubbleLedger.DEFAULT_IN_FLIGHT_LEASE_DURATION;
    }

    public Duration getInFlightLeaseDuration() {
        return this.inFlightLeaseDuration;
    }

    /**
     * Tự động ánh xạ {@code page_id} từ URL hoặc {@code entry[].id} dạng số của Facebook
     * sang UUID nội bộ của Nàng Sen Spa ({@code b23cd382-4f7a-4829-8c54-1d410da14bf8}).
     */
    public String resolveEffectivePageId(String queryPageId, String fbEntryPageId) {
        java.util.UUID parsedQueryUuid = parseSafeUuid(queryPageId);
        if (parsedQueryUuid != null) {
            return parsedQueryUuid.toString();
        }
        java.util.UUID parsedEntryUuid = parseSafeUuid(fbEntryPageId);
        if (parsedEntryUuid != null) {
            return parsedEntryUuid.toString();
        }
        java.util.UUID parsedDefaultUuid = parseSafeUuid(defaultPageId);
        if (parsedDefaultUuid != null) {
            return parsedDefaultUuid.toString();
        }
        return DEFAULT_NANG_SEN_PAGE_ID;
    }

    @PostMapping("/message")
    public ResponseEntity<FanpageBotResponse> handleMessage(@RequestBody FanpageBotRequest request) {
        if (request != null && "REENGAGE".equalsIgnoreCase(request.action())) {
            return ResponseEntity.ok(fanpageBotService.handleReengage(request));
        }
        java.util.UUID parsedPageId = parseSafeUuid(request != null ? request.pageId() : null);
        String msgKey = request != null
                ? com.example.docmind.service.bot.ConversationState.buildWebhookEventKey(
                        parsedPageId,
                        request.senderId(),
                        request.conversationId(),
                        request.isResetSessionRequested(),
                        request.message(),
                        request.history(),
                        null
                )
                : null;
        boolean duplicateEvent = msgKey != null
                && com.example.docmind.service.bot.ConversationState.getCachedWebhookResponse(msgKey) != null;
        FanpageBotResponse response = fanpageBotService.handleCustomerMessage(request);
        if (msgKey != null && response != null) {
            com.example.docmind.service.bot.ConversationState.cacheWebhookResponse(msgKey, response);
        }
        if (!duplicateEvent && conversationInboxService != null && request != null) {
            try {
                conversationInboxService.recordCustomerAndBotTurn(request, response);
            } catch (Exception ignored) {
                // Non-blocking sync to inbox
            }
        }
        return ResponseEntity.ok(response);
    }

    /**
     * Endpoint chủ động kích hoạt tin nhắn giữ nhiệt / kéo lại tương tác (Proactive Re-engagement)
     * khi khách hàng im lặng (ví dụ: nút "Giả lập khách im lặng 5p/30p" trên Simulator).
     */
    @PostMapping("/reengage")
    public ResponseEntity<FanpageBotResponse> handleReengage(@RequestBody(required = false) FanpageBotRequest request) {
        return ResponseEntity.ok(fanpageBotService.handleReengage(request));
    }

    /**
     * Endpoint xác thực Webhook của Meta Facebook Messenger (GET /api/bot/fanpage/webhook).
     */
    @GetMapping(value = "/webhook", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> verifyMetaWebhook(
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String token,
            @RequestParam(name = "hub.challenge", required = false) String challenge) {
        if ("subscribe".equalsIgnoreCase(mode) && verifyToken != null && verifyToken.equals(token)) {
            return ResponseEntity.ok(challenge != null ? challenge : "");
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Forbidden: Invalid verify_token");
    }

    /**
     * Endpoint Chính sách quyền riêng tư & Hướng dẫn xóa dữ liệu chuẩn Meta (GET/HEAD /api/bot/fanpage/privacy-policy).
     */
    @RequestMapping(
            value = "/privacy-policy",
            method = {RequestMethod.GET, RequestMethod.HEAD},
            produces = "text/html;charset=UTF-8"
    )
    public ResponseEntity<String> getPrivacyPolicyHtml() {
        return ResponseEntity.ok()
                .header("Content-Type", "text/html;charset=UTF-8")
                .body(NANG_SEN_PRIVACY_POLICY_HTML);
    }

    /**
     * Endpoint nhận sự kiện trực tiếp từ Meta Messenger Platform (POST /api/bot/fanpage/webhook).
     * Học hỏi từ PR #1: Kiểm tra chữ ký HmacSHA256 constant-time và lọc bỏ sự kiện delivery, read, is_echo.
     */
    @PostMapping("/webhook")
    public ResponseEntity<Map<String, Object>> receiveMetaWebhook(
            @RequestParam(name = "page_id", required = false) String queryPageId,
            @RequestHeader(name = "X-Hub-Signature-256", required = false) String signatureHeader,
            @RequestBody String rawBody) {
        if (appSecret != null && !appSecret.isBlank()) {
            if (!verifyHmacSha256Signature(appSecret, signatureHeader, rawBody)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("status", "forbidden", "reason", "invalid_x_hub_signature_256"));
            }
        }
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            if (!"page".equalsIgnoreCase(root.path("object").asText(""))) {
                return ResponseEntity.ok(Map.of("status", "ignored", "reason", "not_page_object"));
            }
            JsonNode entries = root.path("entry");
            if (!entries.isArray()) {
                return ResponseEntity.ok(Map.of("status", "ignored", "reason", "empty_entry"));
            }

            FanpageBotResponse lastBotResponse = null;
            String lastResolvedPageId = null;
            int processedCount = 0;
            int deduplicatedCount = 0;
            for (JsonNode entry : entries) {
                String fbNumericPageId = entry.path("id").asText("").strip();
                String effectivePageId = resolveEffectivePageId(queryPageId, fbNumericPageId);
                java.util.UUID effectivePageUuid = parseSafeUuid(effectivePageId);
                lastResolvedPageId = effectivePageId;

                JsonNode messagingArr = entry.path("messaging");
                if (!messagingArr.isArray()) continue;

                for (JsonNode event : messagingArr) {
                    if (event.hasNonNull("delivery")) {
                        handleDeliveryReceiptWebhookEvent(effectivePageId, fbNumericPageId, event);
                        continue;
                    }
                    // Lọc bỏ read receipt và tin nhắn phản chiếu của chính Page (is_echo=true)
                    if (event.hasNonNull("read")
                            || event.path("message").path("is_echo").asBoolean(false)) {
                        continue;
                    }
                    String senderId = event.path("sender").path("id").asText(null);
                    if (senderId == null || senderId.isBlank()) {
                        continue;
                    }
                    String normalizedSenderId = senderId.strip();
                    // Đối chiếu chống lặp is_echo theo PAGE_ID số của Facebook (nếu sender.id == entry[].id thì bỏ qua tuyệt đối)
                    if (!fbNumericPageId.isEmpty() && fbNumericPageId.equals(normalizedSenderId)) {
                        continue;
                    }

                    String text = event.path("message").path("text").asText(null);
                    if (text == null || text.isBlank()) {
                        text = event.path("postback").path("payload").asText(null);
                    }
                    if (text == null || text.isBlank()) {
                        text = event.path("postback").path("title").asText(null);
                    }
                    if (text == null || text.isBlank()) {
                        continue;
                    }

                    String mid = event.path("message").path("mid").asText(null);
                    String rawReplyToMid = event.path("message").path("reply_to").path("mid").asText("");
                    String replyToMid = (rawReplyToMid != null && !rawReplyToMid.isBlank()) ? rawReplyToMid.strip() : null;
                    String eventTimestamp = event.path("timestamp").asText("");
                    String explicitEventId = (mid != null && !mid.isBlank())
                            ? mid.strip()
                            : (fbNumericPageId + ":" + eventTimestamp + ":" + text.strip());
                    String stableWebhookConvId = "fb_webhook_" + normalizedSenderId;
                    String dedupKey = com.example.docmind.service.bot.ConversationState.buildWebhookEventKey(
                            effectivePageUuid,
                            normalizedSenderId,
                            stableWebhookConvId,
                            false,
                            text,
                            List.of(),
                            explicitEventId
                    );

                    // Chống trùng lặp Webhook Retry theo mid (TTL 10 phút) & cache phản hồi
                    if (mid != null && !mid.isBlank()
                            && !com.example.docmind.service.bot.ConversationState.tryAcquireWebhookMid(mid, MID_DEDUP_TTL_MS)) {
                        FanpageBotResponse cachedWebhookResp = com.example.docmind.service.bot.ConversationState.getCachedWebhookResponse(dedupKey);
                        if (cachedWebhookResp != null) {
                            lastBotResponse = cachedWebhookResp;
                        }
                        deduplicatedCount++;
                        log.info("[FB_WEBHOOK] Ignored duplicate message mid={} sender={}", mid.strip(), normalizedSenderId);
                        continue;
                    }

                    FanpageBotResponse cachedWebhookResp = com.example.docmind.service.bot.ConversationState.getCachedWebhookResponse(dedupKey);
                    if (cachedWebhookResp != null) {
                        lastBotResponse = cachedWebhookResp;
                        deduplicatedCount++;
                        continue;
                    }

                    // Lưu ánh xạ mid của tin nhắn khách gửi đến vào kho bền vững + bounded cache
                    if (mid != null && !mid.isBlank()) {
                        com.example.docmind.service.bot.SentBubbleLedger.recordMessageId(
                                effectivePageId, normalizedSenderId, mid.strip(), "user", text);
                    }

                    String prevMdcCorrId = org.slf4j.MDC.get("correlation_id");
                    boolean generatedCorrId = (prevMdcCorrId == null || prevMdcCorrId.isBlank());
                    if (generatedCorrId) {
                        org.slf4j.MDC.put("correlation_id", java.util.UUID.randomUUID().toString());
                    }
                    org.slf4j.MDC.put("fb_message_id", explicitEventId);
                    try {
                        // Tra cứu nội dung bong bóng gốc nếu khách dùng tính năng Quote-Reply (message.reply_to.mid)
                        String quotedMessageText = null;
                        if (replyToMid != null) {
                            quotedMessageText = resolveQuotedMessageTextForWebhook(
                                    effectivePageId, normalizedSenderId, replyToMid);
                        }

                        // 1. Bật hiệu ứng đang gõ (typing_on) ngay khi nhận tin từ khách (nếu đã cấu hình page-access-token)
                        sendFacebookSenderAction(normalizedSenderId, "typing_on");

                        // 2. Điều phối hàng đợi theo Sender (Debounce + Lifecycle Epoch + Sequential Send)
                        lastBotResponse = processCoordinatedSenderEvent(
                                effectivePageId,
                                normalizedSenderId,
                                stableWebhookConvId,
                                text,
                                replyToMid,
                                quotedMessageText,
                                dedupKey
                        );
                    } finally {
                        org.slf4j.MDC.remove("fb_message_id");
                        if (generatedCorrId) {
                            org.slf4j.MDC.remove("correlation_id");
                        }
                    }

                    processedCount++;
                }
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "ok");
            out.put("processed_events", processedCount);
            if (deduplicatedCount > 0) {
                out.put("deduplicated_events", deduplicatedCount);
            }
            if (lastResolvedPageId != null) {
                out.put("resolved_page_id", lastResolvedPageId);
            }
            if (lastBotResponse != null) {
                out.put("bot_response", lastBotResponse);
            }
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", e.getMessage() != null ? e.getMessage() : "Invalid payload"));
        }
    }

    /**
     * Tra cứu nội dung tin nhắn gốc của {@code reply_to.mid} theo 2 tầng:
     * <ol>
     *   <li>Tầng 1: {@link com.example.docmind.service.bot.SentBubbleLedger#lookupMessageByMid} (Cache + PostgreSQL).</li>
     *   <li>Tầng 2: Nếu {@code found=false} và có {@code pageAccessToken}, gọi fallback có kiểm soát tới
     *       Facebook Graph API ({@code timeout <= 1200ms}, không retry).</li>
     * </ol>
     */
    public String resolveQuotedMessageTextForWebhook(
            String effectivePageId,
            String normalizedSenderId,
            String replyToMid
    ) {
        if (replyToMid == null || replyToMid.isBlank()) {
            return null;
        }
        String cleanMid = replyToMid.strip();
        String quotedMessageText = com.example.docmind.service.bot.SentBubbleLedger.lookupMessageByMid(
                effectivePageId, normalizedSenderId, cleanMid);
        String source = quotedMessageText != null ? "ledger" : "none";
        if (quotedMessageText == null && pageAccessToken != null && !pageAccessToken.isBlank()) {
            String fetched = fetchMessageTextFromGraphApi(cleanMid);
            if (fetched != null && !fetched.isBlank()) {
                quotedMessageText = fetched.strip();
                source = "graph_api";
                com.example.docmind.service.bot.SentBubbleLedger.recordMessageId(
                        effectivePageId, normalizedSenderId, cleanMid, "assistant", quotedMessageText);
            }
        }
        log.info("[FB_WEBHOOK] Resolved reply_to.mid={} sender={} found={} source={}",
                cleanMid, normalizedSenderId, quotedMessageText != null, source);
        return quotedMessageText;
    }

    public String fetchMessageTextFromGraphApi(String mid) {
        if (mid == null || mid.isBlank() || pageAccessToken == null || pageAccessToken.isBlank()) {
            return null;
        }
        try {
            String url = FB_GRAPH_MESSAGE_LOOKUP_BASE_URL
                    + URLEncoder.encode(mid.strip(), StandardCharsets.UTF_8)
                    + "?fields=message&access_token="
                    + URLEncoder.encode(pageAccessToken.strip(), StandardCharsets.UTF_8);
            HttpResponseResult resp = graphApiGetter.getJson(url, GRAPH_API_LOOKUP_TIMEOUT_MS);
            if (resp != null && resp.statusCode() >= 200 && resp.statusCode() < 300
                    && resp.body() != null && !resp.body().isBlank()) {
                JsonNode node = objectMapper.readTree(resp.body());
                String msg = node.path("message").asText("");
                if (msg != null && !msg.isBlank()) {
                    return msg.strip();
                }
            }
        } catch (Exception e) {
            log.debug("[FB_WEBHOOK] Graph API reply_to.mid fallback missed for mid={}: {}", mid, e.getMessage());
        }
        return null;
    }

    /**
     * Điều phối hàng đợi theo từng {@code (pageId, senderPsid)} theo chính sách nhắn chen thống nhất (Checkpoint E):
     * <ul>
     *   <li>Trường hợp 1 (Tin nhắn B đến trước khi bong bóng 1 của lượt A được gửi — trong debounce hoặc lúc LLM đang chạy):
     *       tăng {@code generationEpoch}, hủy lượt A ({@code supersede}), gộp {@code A + B} thành lượt mới.</li>
     *   <li>Trường hợp 2 (Tin nhắn B đến giữa khoảng nghỉ {@code bubbleDelayMs} sau khi bong bóng {@code 1..i} của lượt A đã gửi,
     *       trước khi bong bóng {@code i+1} được gửi): tăng {@code generationEpoch} để đánh thức ngay luồng đang chờ {@code bubbleDelayMs},
     *       dừng gửi ngay toàn bộ các bong bóng còn lại ({@code i+1..N}) của lượt A (trừ cảnh báo khẩn cấp an toàn y tế),
     *       giữ nguyên đúng các bong bóng {@code 1..i} đã gửi trong {@link com.example.docmind.service.bot.SentBubbleLedger},
     *       và chuyển sang xử lý ngay tin nhắn B.</li>
     * </ul>
     */
    private FanpageBotResponse processCoordinatedSenderEvent(
            String effectivePageId,
            String normalizedSenderId,
            String stableWebhookConvId,
            String text,
            String replyToMid,
            String quotedMessageText,
            String dedupKey
    ) {
        String coordKey = (effectivePageId != null ? effectivePageId.strip() : "")
                + "|"
                + (normalizedSenderId != null ? normalizedSenderId.strip() : "");
        SenderBatchCoordinator coord = senderCoordinators.computeIfAbsent(coordKey, k -> new SenderBatchCoordinator());
        boolean isLeader = false;

        synchronized (coord.lock) {
            long now = System.currentTimeMillis();
            if (coord.batchCurrentlyExecuting && coord.runningLlmCanSupersede) {
                // Trường hợp 1b: Batch A đang chạy LLM nhưng CHƯA gửi bong bóng đầu tiên ra Facebook -> Supersede & gộp A + B
                long newEpoch = com.example.docmind.service.bot.SenderEpochRegistry.supersedeCurrentEpoch(
                        effectivePageId, normalizedSenderId);
                if (!coord.inFlightTexts.isEmpty()) {
                    List<String> combined = new ArrayList<>(coord.inFlightTexts);
                    combined.addAll(coord.pendingTexts);
                    coord.pendingTexts.clear();
                    coord.pendingTexts.addAll(combined);
                    coord.inFlightTexts.clear();
                }
                if (coord.latestReplyToMid == null && coord.inFlightReplyToMid != null) {
                    coord.latestReplyToMid = coord.inFlightReplyToMid;
                    coord.latestQuotedMessageText = coord.inFlightQuotedMessageText;
                } else if ((coord.latestQuotedMessageText == null || coord.latestQuotedMessageText.isBlank())
                        && coord.inFlightQuotedMessageText != null
                        && java.util.Objects.equals(coord.latestReplyToMid, coord.inFlightReplyToMid)) {
                    coord.latestQuotedMessageText = coord.inFlightQuotedMessageText;
                }
                log.info("[FB_WEBHOOK_EPOCH] Superseded in-flight LLM batch for sender={} -> newEpoch={}",
                        normalizedSenderId, newEpoch);
            } else if (coord.batchCurrentlyExecuting && coord.sendingBubblesCanInterrupt) {
                // Trường hợp 2: Batch A đang trong khoảng nghỉ bubbleDelayMs giữa các bong bóng -> đánh thức ngay để hủy bong bóng i+1..N
                long newEpoch = com.example.docmind.service.bot.SenderEpochRegistry.supersedeCurrentEpoch(
                        effectivePageId, normalizedSenderId);
                coord.sendingBubblesCanInterrupt = false;
                log.info("[FB_WEBHOOK_EPOCH] Interrupted mid-send bubble delay for sender={} -> newEpoch={}",
                        normalizedSenderId, newEpoch);
            }
            coord.pendingTexts.add(text);
            if (dedupKey != null) {
                coord.pendingDedupKeys.add(dedupKey);
            }
            if (replyToMid != null) {
                String prevLatestMid = coord.latestReplyToMid;
                coord.latestReplyToMid = replyToMid;
                if (quotedMessageText != null && !quotedMessageText.isBlank()) {
                    coord.latestQuotedMessageText = quotedMessageText;
                } else if (!replyToMid.equals(prevLatestMid) && !replyToMid.equals(coord.inFlightReplyToMid)) {
                    coord.latestQuotedMessageText = null;
                }
            }

            if (!coord.debounceLeaderActive && !coord.batchCurrentlyExecuting) {
                isLeader = true;
                coord.debounceLeaderActive = true;
                coord.firstMessageArrivedAtMs = now;
                coord.debounceDeadlineMs = now + Math.max(0L, burstDebounceMs);
                if (burstDebounceMs > 0) {
                    com.example.docmind.service.bot.SenderEpochRegistry.markDebouncing(
                            effectivePageId, normalizedSenderId);
                }
            } else if (coord.debounceLeaderActive && !coord.batchCurrentlyExecuting && burstDebounceMs > 0) {
                // Trường hợp 1a: Tin nhắn B đến khi Tin nhắn A vẫn đang nằm trong cửa sổ gom
                long extended = now + Math.min(burstDebounceMs, 400L);
                long hardCap = coord.firstMessageArrivedAtMs + maxBurstWaitMs;
                coord.debounceDeadlineMs = Math.min(hardCap, Math.max(coord.debounceDeadlineMs, extended));
                coord.lock.notifyAll();
            }

            if (!isLeader) {
                // Luồng phụ chờ luồng điều phối hoàn tất xử lý cả cụm tin nhắn để trả kết quả đồng bộ
                while (coord.debounceLeaderActive || coord.batchCurrentlyExecuting || !coord.pendingTexts.isEmpty()) {
                    if (!coord.debounceLeaderActive && !coord.batchCurrentlyExecuting && !coord.pendingTexts.isEmpty()) {
                        isLeader = true;
                        coord.debounceLeaderActive = true;
                        long tNow = System.currentTimeMillis();
                        coord.firstMessageArrivedAtMs = tNow;
                        coord.debounceDeadlineMs = tNow;
                        break;
                    }
                    try {
                        coord.lock.wait(2000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return coord.lastCompletedResponse;
                    }
                }
                if (!isLeader) {
                    return coord.lastCompletedResponse;
                }
            }
        }

        // Luồng Leader thực thi vòng lặp xử lý từng Batch tuần tự cho tới khi hàng đợi của Sender rỗng
        FanpageBotResponse finalResp = null;
        while (true) {
            List<String> batchTexts;
            List<String> batchDedupKeys;
            String batchReplyToMid;
            String batchQuotedText;
            com.example.docmind.service.bot.SenderEpochRegistry.EpochTurnContext epochCtx;

            synchronized (coord.lock) {
                if (burstDebounceMs > 0) {
                    while (true) {
                        long remaining = coord.debounceDeadlineMs - System.currentTimeMillis();
                        if (remaining <= 0) {
                            break;
                        }
                        try {
                            coord.lock.wait(remaining);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                if (coord.pendingTexts.isEmpty()) {
                    coord.debounceLeaderActive = false;
                    coord.batchCurrentlyExecuting = false;
                    coord.runningLlmCanSupersede = false;
                    coord.sendingBubblesCanInterrupt = false;
                    coord.lock.notifyAll();
                    break;
                }
                batchTexts = new ArrayList<>(coord.pendingTexts);
                batchDedupKeys = new ArrayList<>(coord.pendingDedupKeys);
                batchReplyToMid = coord.latestReplyToMid;
                batchQuotedText = coord.latestQuotedMessageText;

                coord.pendingTexts.clear();
                coord.pendingDedupKeys.clear();
                coord.latestReplyToMid = null;
                coord.latestQuotedMessageText = null;

                coord.inFlightTexts = new ArrayList<>(batchTexts);
                coord.inFlightReplyToMid = batchReplyToMid;
                coord.inFlightQuotedMessageText = batchQuotedText;
                coord.debounceLeaderActive = false;
                coord.batchCurrentlyExecuting = true;
                coord.runningLlmCanSupersede = true;
                coord.sendingBubblesCanInterrupt = false;

                epochCtx = com.example.docmind.service.bot.SenderEpochRegistry.startNewBatchEpoch(
                        effectivePageId, normalizedSenderId);
            }

            String mergedText = String.join("\n", batchTexts);
            FanpageBotRequest webhookReq = new FanpageBotRequest(
                    effectivePageId,
                    normalizedSenderId,
                    null,
                    mergedText,
                    List.of(),
                    List.of(),
                    List.of(),
                    5,
                    null,
                    null,
                    null,
                    null,
                    false,
                    null,
                    null,
                    stableWebhookConvId,
                    false,
                    batchReplyToMid,
                    batchQuotedText
            );

            FanpageBotResponse batchResp = null;
            boolean supersededBeforeSend = false;
            try {
                batchResp = fanpageBotService.handleCustomerMessage(webhookReq);
                boolean isUninterruptibleMedicalSafetyHandoff = isEmergencyMedicalSafetyHandoff(batchResp);

                synchronized (coord.lock) {
                    boolean stillCurrent = com.example.docmind.service.bot.SenderEpochRegistry.isEpochCurrent(
                            effectivePageId, normalizedSenderId, epochCtx.epoch())
                            && (batchResp == null
                                || batchResp.triggeredHooks() == null
                                || !batchResp.triggeredHooks().contains("SUPERSEDED_BY_NEWER_EPOCH"));
                    if (!stillCurrent) {
                        supersededBeforeSend = true;
                        com.example.docmind.service.bot.SentBubbleLedger.markLatestAssistantTurnSuppressedByFence(
                                com.example.docmind.service.bot.SentBubbleLedger.key(
                                        effectivePageId, normalizedSenderId, stableWebhookConvId),
                                "SUPERSEDED_BY_NEWER_EPOCH");
                        log.info("[FB_WEBHOOK_EPOCH] Discarding superseded batchId={} epoch={} for sender={}",
                                epochCtx.batchId(), epochCtx.epoch(), normalizedSenderId);
                    } else {
                        coord.runningLlmCanSupersede = false;
                        coord.sendingBubblesCanInterrupt = !isUninterruptibleMedicalSafetyHandoff;
                        coord.inFlightTexts.clear();
                        coord.inFlightReplyToMid = null;
                        coord.inFlightQuotedMessageText = null;
                        com.example.docmind.service.bot.SenderEpochRegistry.transitionToSendingBubbles(
                                effectivePageId, normalizedSenderId, epochCtx.epoch());
                    }
                }

                if (!supersededBeforeSend && batchResp != null) {
                    for (String dKey : batchDedupKeys) {
                        if (dKey != null) {
                            com.example.docmind.service.bot.ConversationState.cacheWebhookResponse(dKey, batchResp);
                        }
                    }
                    int sentBubbles = sendSequentialBubblesToFacebook(
                            effectivePageId, normalizedSenderId, stableWebhookConvId, batchResp);
                    if (conversationInboxService != null) {
                        try {
                            boolean hadOutboundBubbles = (batchResp.replyBubbles() != null && !batchResp.replyBubbles().isEmpty())
                                    || (batchResp.replyText() != null && !batchResp.replyText().isBlank());
                            boolean tokenConfigured = pageAccessToken != null && !pageAccessToken.isBlank();
                            FanpageBotResponse inboxResp = (tokenConfigured && hadOutboundBubbles && sentBubbles == 0)
                                    ? new FanpageBotResponse(
                                            "",
                                            List.of(),
                                            List.of(),
                                            batchResp.salesStage(),
                                            batchResp.confidenceScore(),
                                            batchResp.needsHumanHandoff(),
                                            batchResp.handoffCategory(),
                                            batchResp.handoffReason(),
                                            batchResp.extractedLead(),
                                            batchResp.appliedRules(),
                                            batchResp.customerInsight(),
                                            batchResp.activeSkills(),
                                            List.of("SUPPRESSED_BY_FENCE"),
                                            false,
                                            batchResp.pendingTurnSequence(),
                                            batchResp.priceBubbleSentAtNs()
                                    ).withInboundExternalMid(batchResp.inboundExternalMid())
                                    : batchResp;
                            conversationInboxService.recordCustomerAndBotTurn(webhookReq, inboxResp);
                        } catch (Exception ignored) {
                            // Non-blocking sync to inbox
                        }
                    }
                    finalResp = batchResp;
                }
            } finally {
                com.example.docmind.service.bot.SenderEpochRegistry.completeTurn(
                        effectivePageId, normalizedSenderId, epochCtx.epoch());
                synchronized (coord.lock) {
                    if (finalResp != null) {
                        coord.lastCompletedResponse = finalResp;
                    }
                    coord.runningLlmCanSupersede = false;
                    coord.sendingBubblesCanInterrupt = false;
                    if (coord.pendingTexts.isEmpty()) {
                        coord.batchCurrentlyExecuting = false;
                        coord.debounceLeaderActive = false;
                        coord.lock.notifyAll();
                        break;
                    } else {
                        // Còn tin nhắn mới đến trong lúc Batch A đang chạy -> tiếp tục vòng lặp ngay cho Batch kế tiếp
                        coord.debounceDeadlineMs = System.currentTimeMillis();
                    }
                }
            }
        }
        return finalResp;
    }

    private static boolean isEmergencyMedicalSafetyHandoff(FanpageBotResponse resp) {
        if (resp == null || !resp.needsHumanHandoff()) {
            return false;
        }
        String hType = resp.handoffCategory() != null ? resp.handoffCategory().strip().toUpperCase(Locale.ROOT) : "";
        if ("MEDICAL_SAFETY_HANDOFF".equals(hType)
                || "SAFETY_RISK".equals(hType)
                || "NEUROLOGICAL_RISK".equals(hType)) {
            return true;
        }
        return resp.triggeredHooks() != null && resp.triggeredHooks().stream()
                .anyMatch(h -> h != null && (h.contains("SAFETY_RISK") || h.contains("NEUROLOGICAL") || h.contains("MEDICAL_SAFETY")));
    }

    /**
     * Gửi hành động trạng thái (ví dụ {@code typing_on}) tới khách hàng qua Facebook Graph API {@code me/messages}.
     * Nếu {@code page-access-token} đang để trống (như trong Unit Test), bỏ qua hoàn toàn.
     */
    public boolean sendFacebookSenderAction(String recipientPsid, String senderAction) {
        if (pageAccessToken == null || pageAccessToken.isBlank()
                || recipientPsid == null || recipientPsid.isBlank()
                || senderAction == null || senderAction.isBlank()) {
            return false;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("recipient", Map.of("id", recipientPsid.strip()));
            payload.put("sender_action", senderAction.strip());
            String jsonBody = objectMapper.writeValueAsString(payload);
            String url = buildFacebookMessagesUrl(pageAccessToken.strip());
            HttpResponseResult resp = graphApiSender.postJson(url, jsonBody);
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                log.warn("[FB_SEND_API] sender_action={} to psid={} returned status={} body={}",
                        senderAction, recipientPsid, resp.statusCode(), resp.body());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("[FB_SEND_API] Error sending sender_action={} to psid={}: {}",
                    senderAction, recipientPsid, e.getMessage());
            return false;
        }
    }

    /**
     * Duyệt tuần tự qua danh sách {@code replyBubbles} (hoặc {@code reply} nếu {@code replyBubbles} rỗng)
     * và bắn tuần tự từng bong bóng {@code 1 -> 2 -> 3} qua Facebook Graph API {@code me/messages}.
     * Giữa các bong bóng có bật {@code sender_action: "typing_on"} và nghỉ có thể đánh thức ({@code awaitBubbleDelayOrSupersede})
     * để nếu khách nhắn chen ngang giữa chừng thì dừng gửi ngay lập tức các bong bóng còn lại.
     */
    public int sendSequentialBubblesToFacebook(String recipientPsid, FanpageBotResponse botResp) {
        return sendSequentialBubblesToFacebook(resolveEffectivePageId(defaultPageId, null), recipientPsid, null, botResp);
    }

    public int sendSequentialBubblesToFacebook(String effectivePageId, String recipientPsid, FanpageBotResponse botResp) {
        return sendSequentialBubblesToFacebook(effectivePageId, recipientPsid, null, botResp);
    }

    public int sendSequentialBubblesToFacebook(
            String effectivePageId,
            String recipientPsid,
            String conversationId,
            FanpageBotResponse botResp
    ) {
        if (pageAccessToken == null || pageAccessToken.isBlank()
                || recipientPsid == null || recipientPsid.isBlank()
                || botResp == null) {
            return 0;
        }
        String resolvedPageId = (effectivePageId != null && !effectivePageId.isBlank())
                ? effectivePageId.strip()
                : resolveEffectivePageId(defaultPageId, null);
        String cleanPsid = recipientPsid.strip();
        List<String> bubbleTexts = new ArrayList<>();
        if (botResp.replyBubbles() != null && !botResp.replyBubbles().isEmpty()) {
            for (FanpageBotResponse.ReplyBubble b : botResp.replyBubbles()) {
                if (b != null && b.text() != null && !b.text().isBlank()) {
                    bubbleTexts.add(b.text().strip());
                }
            }
        }
        if (bubbleTexts.isEmpty() && botResp.replyText() != null && !botResp.replyText().isBlank()) {
            bubbleTexts.add(botResp.replyText().strip());
        }
        if (bubbleTexts.isEmpty()) {
            return 0;
        }

        boolean uninterruptibleSafety = isEmergencyMedicalSafetyHandoff(botResp);
        com.example.docmind.service.bot.SenderEpochRegistry.EpochTurnContext turnCtx =
                com.example.docmind.service.bot.SenderEpochRegistry.getCurrentThreadTurn();
        long expectedEpoch = (turnCtx != null && !uninterruptibleSafety) ? turnCtx.epoch() : 0L;

        com.example.docmind.service.bot.SentBubbleLedger.DispatchTurnHandle turnHandle =
                com.example.docmind.service.bot.SentBubbleLedger.ensureOrResolveTurnForDispatch(
                        resolvedPageId, cleanPsid, conversationId, bubbleTexts);
        java.time.Instant recoveryNow = java.time.Instant.now();
        com.example.docmind.service.bot.SentBubbleLedger.recoverExpiredInFlightLeases(
                turnHandle.ledgerKey(), recoveryNow);
        if (conversationId == null || conversationId.isBlank()) {
            com.example.docmind.service.bot.SentBubbleLedger.recoverExpiredInFlightLeases(
                    com.example.docmind.service.bot.SentBubbleLedger.key(resolvedPageId, cleanPsid, null), recoveryNow);
            com.example.docmind.service.bot.SentBubbleLedger.recoverExpiredInFlightLeases(
                    com.example.docmind.service.bot.SentBubbleLedger.key(resolvedPageId, cleanPsid, "fb_webhook_" + cleanPsid), recoveryNow);
        }

        String url = buildFacebookMessagesUrl(pageAccessToken.strip());
        int sentCount = 0;
        int total = bubbleTexts.size();
        boolean suppressedMidSequence = false;
        String suppressionReason = null;
        int suppressedFromIndex = total;

        for (int i = 0; i < total; i++) {
            // 1. Pre-Send Outbound Fence (Section 3.4): verify epoch, newer inbound turn, and fresh DB state (HUMAN_TAKEOVER / OPT_OUT / newer inbound)
            String fenceBlockReason = evaluatePreSendOutboundFence(
                    resolvedPageId,
                    cleanPsid,
                    turnHandle,
                    uninterruptibleSafety,
                    expectedEpoch,
                    botResp.inboundExternalMid()
            );
            if (fenceBlockReason != null) {
                suppressedMidSequence = true;
                suppressionReason = fenceBlockReason;
                suppressedFromIndex = i;
                com.example.docmind.service.bot.SentBubbleLedger.markAssistantBubblesSuppressedByFenceFrom(
                        turnHandle.ledgerKey(), turnHandle.turnId(), i, fenceBlockReason);
                log.info("[FB_SEND_API] Suppressed bubble {}/{} to psid={} by outbound fence reason={}",
                        i + 1, total, cleanPsid, fenceBlockReason);
                break;
            }

            // 2. Lease Acquisition: PENDING (attemptCount == 0) -> IN_FLIGHT (attemptCount = 1, leaseUntil = now + leaseDuration)
            boolean leaseAcquired = com.example.docmind.service.bot.SentBubbleLedger.tryAcquireBubbleDispatchLease(
                    turnHandle.ledgerKey(), turnHandle.turnId(), i, inFlightLeaseDuration);
            if (!leaseAcquired) {
                com.example.docmind.service.bot.SentBubbleLedger.DeliveryStatus existingStatus =
                        resolveBubbleStatusInTurn(turnHandle.ledgerKey(), turnHandle.turnId(), i);
                if (existingStatus == com.example.docmind.service.bot.SentBubbleLedger.DeliveryStatus.ACCEPTED_BY_CHANNEL
                        || existingStatus == com.example.docmind.service.bot.SentBubbleLedger.DeliveryStatus.DELIVERED
                        || existingStatus == com.example.docmind.service.bot.SentBubbleLedger.DeliveryStatus.SENT) {
                    continue;
                }
                log.warn("[FB_SEND_API] Aborting dispatch at bubble {}/{} to psid={} because bubble is in non-dispatchable status={}",
                        i + 1, total, cleanPsid, existingStatus);
                break;
            }

            String bubbleText = bubbleTexts.get(i);
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("recipient", Map.of("id", cleanPsid));
                payload.put("messaging_type", "RESPONSE");
                payload.put("message", Map.of("text", bubbleText));
                String jsonBody = objectMapper.writeValueAsString(payload);

                HttpResponseResult resp = graphApiSender.postJson(url, jsonBody);
                if (resp != null && resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    sentCount++;
                    String outMid = "";
                    // Trích xuất message_id từ response của POST /v21.0/me/messages để lưu ánh xạ Quote-Reply bền vững
                    if (resp.body() != null && !resp.body().isBlank()) {
                        try {
                            JsonNode respNode = objectMapper.readTree(resp.body());
                            outMid = respNode.path("message_id").asText("");
                            if (outMid == null || outMid.isBlank()) {
                                outMid = respNode.path("mid").asText("");
                            }
                        } catch (Exception ignored) {
                            // Ignore non-JSON mock response bodies if any
                        }
                    }
                    String cleanOutMid = (outMid != null && !outMid.isBlank()) ? outMid.strip() : null;
                    com.example.docmind.service.bot.SentBubbleLedger.transitionBubbleStatusMonotonically(
                            turnHandle.ledgerKey(),
                            turnHandle.turnId(),
                            i,
                            com.example.docmind.service.bot.SentBubbleLedger.DeliveryStatus.ACCEPTED_BY_CHANNEL,
                            cleanOutMid,
                            null
                    );
                    if (cleanOutMid != null) {
                        com.example.docmind.service.bot.SentBubbleLedger.recordMessageId(
                                resolvedPageId, cleanPsid, cleanOutMid, "assistant", bubbleText);
                    }
                    log.info("[FB_SEND_API] Accepted bubble {}/{} by channel to psid={} status={} mid={}",
                            i + 1, total, cleanPsid, resp.statusCode(),
                            cleanOutMid != null ? cleanOutMid : "-");
                } else {
                    int code = resp != null ? resp.statusCode() : 0;
                    com.example.docmind.service.bot.SentBubbleLedger.transitionBubbleStatusMonotonically(
                            turnHandle.ledgerKey(),
                            turnHandle.turnId(),
                            i,
                            com.example.docmind.service.bot.SentBubbleLedger.DeliveryStatus.FAILED,
                            null,
                            "HTTP_" + code
                    );
                    log.error("[FB_SEND_API] Failed bubble {}/{} to psid={} status={} body={}",
                            i + 1, total, cleanPsid, code, resp != null ? resp.body() : "null");
                    break;
                }
            } catch (Throwable e) {
                if (isSimulatedJvmCrashAfterInFlight(e)) {
                    if (e instanceof Error err) {
                        throw err;
                    }
                    if (e instanceof RuntimeException rex) {
                        throw rex;
                    }
                    throw new RuntimeException(e);
                }
                com.example.docmind.service.bot.SentBubbleLedger.DeliveryStatus errorStatus = classifySendExceptionStatus(e);
                String reason = e.getClass().getSimpleName() + (e.getMessage() != null ? ":" + e.getMessage() : "");
                com.example.docmind.service.bot.SentBubbleLedger.transitionBubbleStatusMonotonically(
                        turnHandle.ledgerKey(),
                        turnHandle.turnId(),
                        i,
                        errorStatus,
                        null,
                        reason
                );
                log.error("[FB_SEND_API] Exception sending bubble {}/{} to psid={} classifiedStatus={}: {}",
                        i + 1, total, cleanPsid, errorStatus, e.getMessage());
                break;
            }

            if (i + 1 < total) {
                if (!uninterruptibleSafety && expectedEpoch > 0L
                        && !com.example.docmind.service.bot.SenderEpochRegistry.isEpochCurrent(
                                resolvedPageId, cleanPsid, expectedEpoch)) {
                    suppressedMidSequence = true;
                    suppressionReason = "SUPERSEDED_BY_NEWER_EPOCH";
                    suppressedFromIndex = i + 1;
                    com.example.docmind.service.bot.SentBubbleLedger.markAssistantBubblesSuppressedByFenceFrom(
                            turnHandle.ledgerKey(), turnHandle.turnId(), i + 1, suppressionReason);
                    log.info("[FB_SEND_API] Aborted remaining bubbles after {}/{} to psid={} because newer message arrived",
                            i + 1, total, cleanPsid);
                    break;
                }
                sendFacebookSenderAction(cleanPsid, "typing_on");
                if (interBubbleDelayMs > 0) {
                    boolean stillValid = com.example.docmind.service.bot.SenderEpochRegistry.awaitBubbleDelayOrSupersede(
                            resolvedPageId, cleanPsid, expectedEpoch, interBubbleDelayMs);
                    if (!stillValid) {
                        suppressedMidSequence = true;
                        suppressionReason = "SUPERSEDED_BY_NEWER_INBOUND_DURING_DELAY";
                        suppressedFromIndex = i + 1;
                        com.example.docmind.service.bot.SentBubbleLedger.markAssistantBubblesSuppressedByFenceFrom(
                                turnHandle.ledgerKey(), turnHandle.turnId(), i + 1, suppressionReason);
                        log.info("[FB_SEND_API] Woke up early and suppressed remaining bubbles {}-{} to psid={} due to customer interruption",
                                i + 2, total, cleanPsid);
                        break;
                    }
                }
            }
        }
        if (suppressedMidSequence && suppressedFromIndex < total) {
            String reasonToRecord = suppressionReason != null ? suppressionReason : "SUPPRESSED_BY_FENCE";
            if (conversationId == null || conversationId.isBlank()) {
                com.example.docmind.service.bot.SentBubbleLedger.markAssistantBubblesSuppressedByFenceFrom(
                        com.example.docmind.service.bot.SentBubbleLedger.key(resolvedPageId, cleanPsid, null),
                        turnHandle.turnId(),
                        suppressedFromIndex,
                        reasonToRecord
                );
                com.example.docmind.service.bot.SentBubbleLedger.markAssistantBubblesSuppressedByFenceFrom(
                        com.example.docmind.service.bot.SentBubbleLedger.key(resolvedPageId, cleanPsid, "fb_webhook_" + cleanPsid),
                        turnHandle.turnId(),
                        suppressedFromIndex,
                        reasonToRecord
                );
            }
        }
        return sentCount;
    }

    private void handleDeliveryReceiptWebhookEvent(String effectivePageId, String fbNumericPageId, JsonNode event) {
        if (event == null || !event.hasNonNull("delivery")) {
            return;
        }
        String senderId = event.path("sender").path("id").asText("").strip();
        String recipientId = event.path("recipient").path("id").asText("").strip();
        String customerPsid;
        if (!senderId.isEmpty() && !senderId.equals(fbNumericPageId)) {
            customerPsid = senderId;
        } else if (!recipientId.isEmpty() && !recipientId.equals(fbNumericPageId)) {
            customerPsid = recipientId;
        } else {
            customerPsid = senderId;
        }

        JsonNode deliveryNode = event.path("delivery");
        JsonNode midsNode = deliveryNode.path("mids");
        long watermark = deliveryNode.path("watermark").asLong(0L);
        boolean hasExplicitMids = midsNode.isArray() && !midsNode.isEmpty();
        if (hasExplicitMids) {
            for (JsonNode midNode : midsNode) {
                String deliveredMid = midNode.asText("").strip();
                if (!deliveredMid.isEmpty()) {
                    com.example.docmind.service.bot.SentBubbleLedger.markBubbleDeliveredByExternalMid(
                            effectivePageId, customerPsid, deliveredMid);
                }
            }
        } else if (watermark > 0L && !customerPsid.isEmpty()) {
            com.example.docmind.service.bot.SentBubbleLedger.markBubblesDeliveredUpToWatermark(
                    effectivePageId, customerPsid, watermark);
        }
    }

    private String evaluatePreSendOutboundFence(
            String resolvedPageId,
            String cleanPsid,
            com.example.docmind.service.bot.SentBubbleLedger.DispatchTurnHandle turnHandle,
            boolean uninterruptibleSafety,
            long expectedEpoch,
            String sourceInboundExternalMid
    ) {
        if (!uninterruptibleSafety) {
            if (!com.example.docmind.service.bot.SenderEpochRegistry.checkThreadEpochStillCurrent(
                    resolvedPageId,
                    cleanPsid,
                    com.example.docmind.service.bot.SenderEpochRegistry.Checkpoint.BEFORE_OUTBOUND_BUBBLE_SEND)) {
                return "SUPERSEDED_BY_NEWER_EPOCH";
            }
            if (expectedEpoch > 0L
                    && !com.example.docmind.service.bot.SenderEpochRegistry.isEpochCurrent(
                            resolvedPageId, cleanPsid, expectedEpoch)) {
                return "SUPERSEDED_BY_NEWER_EPOCH";
            }
            if (turnHandle != null
                    && com.example.docmind.service.bot.SentBubbleLedger.hasNewerCustomerTurnAfter(
                            turnHandle.ledgerKey(), turnHandle.turnId())) {
                return "SUPERSEDED_BY_NEWER_INBOUND_TURN";
            }
        }
        if (fanpageBotService != null) {
            com.example.docmind.service.bot.InboundSessionPersistenceService.OutboundFenceDecision decision =
                    fanpageBotService.evaluateOutboundFenceBeforeBubbleSend(
                            resolvedPageId, cleanPsid, sourceInboundExternalMid);
            if (decision != null && !decision.permitted()) {
                return decision.blockReason() != null ? decision.blockReason() : "SUPPRESSED_BY_OUTBOUND_FENCE";
            }
        }
        return null;
    }

    private static com.example.docmind.service.bot.SentBubbleLedger.DeliveryStatus resolveBubbleStatusInTurn(
            String ledgerKey,
            String turnId,
            int bubbleOrder
    ) {
        List<com.example.docmind.service.bot.SentBubbleLedger.TurnRecord> snapshot =
                com.example.docmind.service.bot.SentBubbleLedger.snapshot(ledgerKey);
        for (int t = snapshot.size() - 1; t >= 0; t--) {
            com.example.docmind.service.bot.SentBubbleLedger.TurnRecord tr = snapshot.get(t);
            if (tr != null && "assistant".equals(tr.role())
                    && (turnId == null || turnId.equals(tr.turnId()))
                    && tr.bubbles() != null) {
                for (com.example.docmind.service.bot.SentBubbleLedger.BubbleRecord br : tr.bubbles()) {
                    if (br != null && br.order() == bubbleOrder) {
                        return br.status();
                    }
                }
            }
        }
        return null;
    }

    private static boolean isSimulatedJvmCrashAfterInFlight(Throwable e) {
        if (e == null) {
            return false;
        }
        if (e instanceof VirtualMachineError) {
            return true;
        }
        String cls = e.getClass().getSimpleName().toUpperCase(Locale.ROOT);
        String msg = e.getMessage() != null ? e.getMessage().toUpperCase(Locale.ROOT) : "";
        return cls.contains("CRASH") || msg.contains("SIMULATED_JVM_CRASH");
    }

    private static com.example.docmind.service.bot.SentBubbleLedger.DeliveryStatus classifySendExceptionStatus(Throwable e) {
        if (e == null) {
            return com.example.docmind.service.bot.SentBubbleLedger.DeliveryStatus.UNKNOWN;
        }
        Throwable curr = e;
        while (curr != null) {
            if (curr instanceof java.net.SocketTimeoutException
                    || curr instanceof java.net.http.HttpTimeoutException
                    || curr instanceof java.io.InterruptedIOException
                    || curr instanceof java.util.concurrent.TimeoutException) {
                return com.example.docmind.service.bot.SentBubbleLedger.DeliveryStatus.UNKNOWN;
            }
            if (curr instanceof java.net.ConnectException) {
                return com.example.docmind.service.bot.SentBubbleLedger.DeliveryStatus.FAILED;
            }
            String msg = curr.getMessage() != null ? curr.getMessage().toLowerCase(Locale.ROOT) : "";
            if (msg.contains("timeout") || msg.contains("timed out") || msg.contains("reset") || msg.contains("eof")) {
                return com.example.docmind.service.bot.SentBubbleLedger.DeliveryStatus.UNKNOWN;
            }
            if (msg.contains("refused") || msg.contains("rejected")) {
                return com.example.docmind.service.bot.SentBubbleLedger.DeliveryStatus.FAILED;
            }
            curr = curr.getCause();
        }
        return com.example.docmind.service.bot.SentBubbleLedger.DeliveryStatus.UNKNOWN;
    }

    private static String buildFacebookMessagesUrl(String token) {
        return FB_GRAPH_MESSAGES_API_URL + "?access_token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    private HttpResponseResult defaultPostJsonToGraphApi(String url, String jsonBody) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new HttpResponseResult(resp.statusCode(), resp.body());
    }

    private HttpResponseResult defaultGetJsonFromGraphApi(String url, long timeoutMs) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(Math.max(200L, Math.min(timeoutMs, GRAPH_API_LOOKUP_TIMEOUT_MS))))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new HttpResponseResult(resp.statusCode(), resp.body());
    }

    private static java.util.UUID parseSafeUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return java.util.UUID.fromString(raw.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static boolean verifyHmacSha256Signature(String secret, String signatureHeader, String rawPayload) {
        if (secret == null || secret.isBlank()) {
            return true;
        }
        if (signatureHeader == null || !signatureHeader.toLowerCase(Locale.ROOT).startsWith("sha256=") || rawPayload == null) {
            return false;
        }
        String providedHex = signatureHeader.substring("sha256=".length()).strip().toLowerCase(Locale.ROOT);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] computed = mac.doFinal(rawPayload.getBytes(StandardCharsets.UTF_8));
            String computedHex = HexFormat.of().formatHex(computed);
            return MessageDigest.isEqual(
                    computedHex.getBytes(StandardCharsets.UTF_8),
                    providedHex.getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            return false;
        }
    }

    @RestController
    public static class RootPrivacyPolicyController {
        @RequestMapping(
                value = {"/privacy-policy", "/privacy-policy.html"},
                method = {RequestMethod.GET, RequestMethod.HEAD},
                produces = "text/html;charset=UTF-8"
        )
        public ResponseEntity<String> getRootPrivacyPolicyHtml() {
            return ResponseEntity.ok()
                    .header("Content-Type", "text/html;charset=UTF-8")
                    .body(NANG_SEN_PRIVACY_POLICY_HTML);
        }
    }

    public static final String NANG_SEN_PRIVACY_POLICY_HTML = """
            <!DOCTYPE html>
            <html lang="vi">
            <head>
              <meta charset="UTF-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1.0" />
              <title>Chính Sách Quyền Riêng Tư &amp; Xóa Dữ Liệu — Nàng Sen Spa (Trợ lý CSKH An Nhiên)</title>
              <meta name="description" content="Chính sách quyền riêng tư, bảo mật thông tin và hướng dẫn yêu cầu xóa dữ liệu người dùng (Privacy Policy &amp; User Data Deletion Instructions) của Nàng Sen Spa — Trợ lý CSKH An Nhiên trên Facebook Messenger." />
              <meta property="og:title" content="Chính Sách Quyền Riêng Tư &amp; Xóa Dữ Liệu — Nàng Sen Spa (Trợ lý CSKH An Nhiên)" />
              <meta property="og:description" content="Chính sách bảo mật thông tin khách hàng và hướng dẫn xóa dữ liệu người dùng (Privacy Policy &amp; User Data Deletion Instructions) khi tương tác qua Facebook Messenger cùng Nàng Sen Spa." />
              <meta property="og:type" content="website" />
              <meta property="og:url" content="https://assistant.goc-an-nhien.site/privacy-policy.html" />
              <meta property="og:image" content="https://assistant.goc-an-nhien.site/app-icon-1024.png" />
            </head>
            <body>
              <h1>CHÍNH SÁCH QUYỀN RIÊNG TƯ &amp; XÓA DỮ LIỆU NGƯỜI DÙNG — NÀNG SEN SPA (TRỢ LÝ CSKH AN NHIÊN)</h1>
              <h2>1. Dữ liệu thu thập (Information We Collect)</h2>
              <p>Mã định danh người dùng trên Trang (Facebook Page-Scoped User ID - PSID), nội dung tin nhắn tư vấn, và họ tên/số điện thoại/khung giờ đặt lịch khi khách hàng chủ động cung cấp.</p>
              <h2>2. Mục đích sử dụng (How We Use Information)</h2>
              <p>Tư vấn dịch vụ spa dưỡng sinh, báo giá minh bạch theo bảng giá niêm yết và hỗ trợ đặt lịch hẹn tại Nàng Sen Spa qua Facebook Messenger.</p>
              <h2>3. Bảo mật &amp; Không chia sẻ bên thứ ba (Data Protection)</h2>
              <p>Dữ liệu được bảo mật nội bộ và tuyệt đối không bán hoặc chia sẻ cho bất kỳ bên thứ ba nào.</p>
              <h2>4. Hướng dẫn yêu cầu xóa dữ liệu người dùng (User Data Deletion Instructions)</h2>
              <p>Khách hàng có thể nhắn trực tiếp từ khóa "Xóa dữ liệu" hoặc "Hủy đăng ký" vào hộp thư Fanpage Nàng Sen Spa hoặc gửi email tới privacy@goc-an-nhien.site để xóa toàn bộ lịch sử hội thoại và thông tin liên hệ trong vòng 24 giờ.</p>
            </body>
            </html>
            """;
}
