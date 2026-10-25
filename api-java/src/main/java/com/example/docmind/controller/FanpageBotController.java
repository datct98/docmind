package com.example.docmind.controller;

import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.dto.bot.FanpageBotResponse;
import com.example.docmind.service.bot.ConversationInboxService;
import com.example.docmind.service.bot.FanpageBotService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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

    private final FanpageBotService fanpageBotService;
    private final ConversationInboxService conversationInboxService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${docmind.fanpage.verify-token:docmind-fb-verify-token}")
    private String verifyToken = "docmind-fb-verify-token";

    @Value("${docmind.fanpage.app-secret:}")
    private String appSecret = "";

    public FanpageBotController(FanpageBotService fanpageBotService) {
        this(fanpageBotService, null);
    }

    @Autowired
    public FanpageBotController(FanpageBotService fanpageBotService, ConversationInboxService conversationInboxService) {
        this.fanpageBotService = fanpageBotService;
        this.conversationInboxService = conversationInboxService;
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
            int processedCount = 0;
            int deduplicatedCount = 0;
            java.util.UUID parsedQueryPageId = parseSafeUuid(queryPageId);
            for (JsonNode entry : entries) {
                JsonNode messagingArr = entry.path("messaging");
                if (!messagingArr.isArray()) continue;

                for (JsonNode event : messagingArr) {
                    // Lọc bỏ delivery, read receipt và tin nhắn phản chiếu của chính Page (is_echo=true)
                    if (event.hasNonNull("delivery") || event.hasNonNull("read")
                            || event.path("message").path("is_echo").asBoolean(false)) {
                        continue;
                    }
                    String senderId = event.path("sender").path("id").asText(null);
                    String text = event.path("message").path("text").asText(null);
                    if (text == null || text.isBlank()) {
                        text = event.path("postback").path("payload").asText(null);
                    }
                    if (senderId == null || text == null || text.isBlank()) {
                        continue;
                    }
                    String mid = event.path("message").path("mid").asText(null);
                    String eventTimestamp = event.path("timestamp").asText("");
                    String explicitEventId = (mid != null && !mid.isBlank())
                            ? mid.strip()
                            : (entry.path("id").asText("") + ":" + eventTimestamp + ":" + text.strip());
                    String stableWebhookConvId = "fb_webhook_" + senderId.strip();
                    String dedupKey = com.example.docmind.service.bot.ConversationState.buildWebhookEventKey(
                            parsedQueryPageId,
                            senderId,
                            stableWebhookConvId,
                            false,
                            text,
                            List.of(),
                            explicitEventId
                    );
                    FanpageBotResponse cachedWebhookResp = com.example.docmind.service.bot.ConversationState.getCachedWebhookResponse(dedupKey);
                    if (cachedWebhookResp != null) {
                        lastBotResponse = cachedWebhookResp;
                        deduplicatedCount++;
                        continue;
                    }
                    FanpageBotRequest webhookReq = new FanpageBotRequest(
                            queryPageId,
                            senderId,
                            null,
                            text,
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
                            false
                    );
                    lastBotResponse = fanpageBotService.handleCustomerMessage(webhookReq);
                    if (dedupKey != null && lastBotResponse != null) {
                        com.example.docmind.service.bot.ConversationState.cacheWebhookResponse(dedupKey, lastBotResponse);
                    }
                    if (conversationInboxService != null) {
                        try {
                            conversationInboxService.recordCustomerAndBotTurn(webhookReq, lastBotResponse);
                        } catch (Exception ignored) {
                            // Non-blocking sync to inbox
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
            if (lastBotResponse != null) {
                out.put("bot_response", lastBotResponse);
            }
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", e.getMessage() != null ? e.getMessage() : "Invalid payload"));
        }
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
}
