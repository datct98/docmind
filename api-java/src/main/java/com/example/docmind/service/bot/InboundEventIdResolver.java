package com.example.docmind.service.bot;

import com.example.docmind.dto.bot.FanpageBotRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves deterministic protocol-aligned {@code external_mid} values (<= 128 chars)
 * across frozen {@code FanpageBotController} and {@code FanpageBotRequest} according
 * to the 4-Row Idempotency Matrix (Unit P0.1 / Spec Item I.2):
 * <ul>
 *   <li><b>Tier 1 (Facebook {@code message.mid} present):</b> Derived strictly and exclusively
 *       from {@code mid} (single {@code "fbmid:" + mid} if {@code <= 110} chars, else
 *       {@code "fbmid_sha256:" + sha256Hex(mid)}; sorted {@code |}-joined burst {@code mid}s use
 *       {@code "fbmid_sha256:" + sha256Hex(sortedMidsJoined)}). {@code mergedText} is NEVER mixed
 *       into Tier-1 keys.</li>
 *   <li><b>Tier 2 (Webhook without {@code mid}, numeric {@code eventTimestamp} present):</b>
 *       {@code "fb_nomid_sha256:" + sha256Hex(pageScope + "|" + senderId + "|" + eventTimestamp + "|" + mergedText)}.</li>
 *   <li><b>Tier 3a (REST / Simulator with {@code X-Idempotency-Key} header):</b>
 *       {@code "idem:" + key} ({@code <= 110} chars) or {@code "idem_sha256:" + sha256Hex(key)}.</li>
 *   <li><b>Tier 3b (No protocol identifier):</b> Intra-request correlation ID {@code "turn:" + UUIDv4}
 *       + increments {@code docmind_inbound_missing_protocol_id_total} and logs
 *       {@code [INBOUND_NO_STABLE_EVENT_ID]}.</li>
 * </ul>
 */
public final class InboundEventIdResolver {

    private static final Logger log = LoggerFactory.getLogger(InboundEventIdResolver.class);

    public static final int MAX_DIRECT_ID_PAYLOAD_CHARS = 110;
    public static final String MDC_FB_MESSAGE_ID = "fb_message_id";
    public static final String HEADER_IDEMPOTENCY_KEY = "X-Idempotency-Key";

    /** Matches FanpageBotController:356 fallback when mid is absent: fbNumericPageId + ":" + eventTimestamp + ":" + text */
    private static final Pattern WEBHOOK_NO_MID_WITH_TS_PATTERN =
            Pattern.compile("^([^:]+):(\\d{9,19}):([\\s\\S]*)$");
    private static final Pattern WEBHOOK_NO_MID_EMPTY_TS_PATTERN =
            Pattern.compile("^([^:]+)::([\\s\\S]*)$");

    private static final AtomicLong MISSING_PROTOCOL_ID_TOTAL = new AtomicLong(0L);
    private static final ConcurrentHashMap<String, AtomicLong> MISSING_PROTOCOL_ID_BY_CHANNEL =
            new ConcurrentHashMap<>();

    private static final ThreadLocal<PrePersistedContext> PRE_PERSISTED_CONTEXT = new ThreadLocal<>();

    public enum InboundChannel {
        FB_WEBHOOK,
        REST_FANPAGE_MESSAGE,
        INBOX_SIMULATOR,
        DIRECT_SERVICE
    }

    public enum IdempotencyTier {
        TIER_1_FB_MID,
        TIER_2_FB_TIMESTAMP_HASH,
        TIER_3A_CLIENT_IDEMPOTENCY_KEY,
        TIER_3B_INTRA_REQUEST_TURN_UUID
    }

    public record ResolvedInboundEvent(
            String externalMid,
            IdempotencyTier tier,
            InboundChannel channel,
            boolean stableCrossRequestId
    ) {
    }

    private record PrePersistedContext(
            ResolvedInboundEvent event,
            boolean alreadyPersistedCustomerRow
    ) {
    }

    private InboundEventIdResolver() {
    }

    public static <T> T runWithPrePersistedEvent(ResolvedInboundEvent event, Supplier<T> action) {
        PrePersistedContext previous = PRE_PERSISTED_CONTEXT.get();
        PRE_PERSISTED_CONTEXT.set(new PrePersistedContext(event, true));
        try {
            return action.get();
        } finally {
            if (previous != null) {
                PRE_PERSISTED_CONTEXT.set(previous);
            } else {
                PRE_PERSISTED_CONTEXT.remove();
            }
        }
    }

    public static boolean isCustomerAlreadyPersistedInCurrentCall() {
        PrePersistedContext ctx = PRE_PERSISTED_CONTEXT.get();
        return ctx != null && ctx.alreadyPersistedCustomerRow();
    }

    public static ResolvedInboundEvent resolveForCurrentContext(FanpageBotRequest request, String mergedText) {
        PrePersistedContext pre = PRE_PERSISTED_CONTEXT.get();
        if (pre != null && pre.event() != null) {
            return pre.event();
        }

        HttpServletRequest httpReq = currentHttpServletRequest();
        String mdcFbMessageId = MDC.get(MDC_FB_MESSAGE_ID);
        InboundChannel channel = detectChannel(request, mdcFbMessageId, httpReq);

        String pageScope = (request != null && request.pageId() != null && !request.pageId().isBlank())
                ? request.pageId().strip()
                : "default";
        String senderScope = (request != null && request.senderId() != null && !request.senderId().isBlank())
                ? request.senderId().strip()
                : "psid-sim-default";

        if (channel == InboundChannel.FB_WEBHOOK && mdcFbMessageId != null && !mdcFbMessageId.isBlank()) {
            String trimmedMdc = mdcFbMessageId.strip();
            // Check SentBubbleLedger first: FanpageBotController:389 records real mid before invoking service.
            String ledgerRec =
                    SentBubbleLedger.lookupMessageByMid(
                            request != null ? request.pageId() : null,
                            senderScope,
                            trimmedMdc
                    );
            if (ledgerRec != null) {
                return new ResolvedInboundEvent(
                        deriveTier1FromSingleMid(trimmedMdc),
                        IdempotencyTier.TIER_1_FB_MID,
                        channel,
                        true
                );
            }

            Matcher tsMatcher = WEBHOOK_NO_MID_WITH_TS_PATTERN.matcher(trimmedMdc);
            if (tsMatcher.matches()) {
                String eventTs = tsMatcher.group(2);
                return new ResolvedInboundEvent(
                        deriveTier2FromWebhookTimestamp(pageScope, senderScope, eventTs, mergedText),
                        IdempotencyTier.TIER_2_FB_TIMESTAMP_HASH,
                        channel,
                        true
                );
            }

            Matcher emptyTsMatcher = WEBHOOK_NO_MID_EMPTY_TS_PATTERN.matcher(trimmedMdc);
            if (emptyTsMatcher.matches()) {
                return allocateTier3bTurnUuid(channel, pageScope, senderScope);
            }

            // Direct mid placed in MDC without SentBubbleLedger registration
            return new ResolvedInboundEvent(
                    deriveTier1FromSingleMid(trimmedMdc),
                    IdempotencyTier.TIER_1_FB_MID,
                    channel,
                    true
            );
        }

        String headerIdemKey = httpReq != null ? httpReq.getHeader(HEADER_IDEMPOTENCY_KEY) : null;
        if (headerIdemKey != null && !headerIdemKey.isBlank()) {
            return new ResolvedInboundEvent(
                    deriveTier3aFromIdempotencyHeader(headerIdemKey),
                    IdempotencyTier.TIER_3A_CLIENT_IDEMPOTENCY_KEY,
                    channel,
                    true
            );
        }

        return allocateTier3bTurnUuid(channel, pageScope, senderScope);
    }

    /**
     * Tier 1 (Single mid): Derived EXCLUSIVELY from {@code mid}. {@code mergedText} is NEVER included.
     */
    public static String deriveTier1FromSingleMid(String rawMid) {
        if (rawMid == null || rawMid.isBlank()) {
            throw new IllegalArgumentException("Tier-1 mid must not be null or blank");
        }
        String mid = rawMid.strip();
        if (mid.length() <= MAX_DIRECT_ID_PAYLOAD_CHARS) {
            return "fbmid:" + mid;
        }
        return "fbmid_sha256:" + sha256Hex(mid);
    }

    /**
     * Tier 1 (Burst mids): Derived EXCLUSIVELY from sorted {@code |}-joined {@code mid}s.
     * {@code mergedText} is NEVER included.
     */
    public static String deriveTier1FromBurstMids(List<String> rawMids) {
        if (rawMids == null || rawMids.isEmpty()) {
            throw new IllegalArgumentException("Tier-1 burst mids must not be empty");
        }
        List<String> cleaned = new ArrayList<>();
        for (String m : rawMids) {
            if (m != null && !m.isBlank()) {
                cleaned.add(m.strip());
            }
        }
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("Tier-1 burst mids must contain at least one non-blank mid");
        }
        if (cleaned.size() == 1) {
            return deriveTier1FromSingleMid(cleaned.get(0));
        }
        Collections.sort(cleaned);
        String joinedMids = String.join("|", cleaned);
        return "fbmid_sha256:" + sha256Hex(joinedMids);
    }

    /**
     * Tier 2: Webhook fallback when Facebook payload omits {@code mid} but provides {@code timestamp}.
     */
    public static String deriveTier2FromWebhookTimestamp(
            String pageScope,
            String senderId,
            String eventTimestamp,
            String mergedText
    ) {
        String material = nullToEmpty(pageScope) + "|"
                + nullToEmpty(senderId) + "|"
                + nullToEmpty(eventTimestamp) + "|"
                + nullToEmpty(mergedText);
        return "fb_nomid_sha256:" + sha256Hex(material);
    }

    /**
     * Tier 3a: Client-supplied {@code X-Idempotency-Key} HTTP header.
     */
    public static String deriveTier3aFromIdempotencyHeader(String rawHeaderValue) {
        if (rawHeaderValue == null || rawHeaderValue.isBlank()) {
            throw new IllegalArgumentException("X-Idempotency-Key must not be blank");
        }
        String key = rawHeaderValue.strip();
        if (key.length() <= MAX_DIRECT_ID_PAYLOAD_CHARS) {
            return "idem:" + key;
        }
        return "idem_sha256:" + sha256Hex(key);
    }

    private static ResolvedInboundEvent allocateTier3bTurnUuid(
            InboundChannel channel,
            String pageScope,
            String senderScope
    ) {
        String turnKey = "turn:" + UUID.randomUUID();
        long total = MISSING_PROTOCOL_ID_TOTAL.incrementAndGet();
        long channelTotal = MISSING_PROTOCOL_ID_BY_CHANNEL
                .computeIfAbsent(channel.name(), k -> new AtomicLong(0L))
                .incrementAndGet();
        log.warn("[INBOUND_NO_STABLE_EVENT_ID] channel={} pageId={} senderId={} assignedIntraTurnId={} "
                        + "docmind_inbound_missing_protocol_id_total={} channelCount={}",
                channel.name(), pageScope, senderScope, turnKey, total, channelTotal);
        return new ResolvedInboundEvent(
                turnKey,
                IdempotencyTier.TIER_3B_INTRA_REQUEST_TURN_UUID,
                channel,
                false
        );
    }

    public static InboundChannel detectChannel(
            FanpageBotRequest request,
            String mdcFbMessageId,
            HttpServletRequest httpReq
    ) {
        if (mdcFbMessageId != null && !mdcFbMessageId.isBlank()) {
            return InboundChannel.FB_WEBHOOK;
        }
        String uri = httpReq != null ? httpReq.getRequestURI() : null;
        if (uri != null) {
            if (uri.contains("/api/bot/fanpage/webhook")) {
                return InboundChannel.FB_WEBHOOK;
            }
            if (uri.contains("/api/conversations")) {
                return InboundChannel.INBOX_SIMULATOR;
            }
            if (uri.contains("/api/bot/fanpage/message")) {
                return InboundChannel.REST_FANPAGE_MESSAGE;
            }
        }
        if (request != null && request.conversationId() != null
                && request.conversationId().startsWith("fb_webhook_")
                && uri == null) {
            return InboundChannel.FB_WEBHOOK;
        }
        return InboundChannel.DIRECT_SERVICE;
    }

    public static long getMissingProtocolIdTotal() {
        return MISSING_PROTOCOL_ID_TOTAL.get();
    }

    public static long getMissingProtocolIdCountForChannel(InboundChannel channel) {
        AtomicLong counter = MISSING_PROTOCOL_ID_BY_CHANNEL.get(channel.name());
        return counter != null ? counter.get() : 0L;
    }

    public static Map<String, Long> snapshotMissingProtocolIdByChannel() {
        ConcurrentHashMap<String, Long> snap = new ConcurrentHashMap<>();
        MISSING_PROTOCOL_ID_BY_CHANNEL.forEach((k, v) -> snap.put(k, v.get()));
        return snap;
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    private static HttpServletRequest currentHttpServletRequest() {
        try {
            RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes servletAttrs) {
                return servletAttrs.getRequest();
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
