package com.example.docmind.service.bot;

import com.example.docmind.dto.bot.FanpageBotRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sổ cái (ledger) Backend & Transactional Outbox lưu ĐỦ từng bong bóng outbound/inbound theo thứ tự hiển thị
 * và vòng đời trạng thái giao nhận thực tế (P3.1 — Outbound Delivery Reliability).
 *
 * <p>Nguồn lưu chuẩn là {@link Store} bền vững (PostgreSQL qua {@link JdbcSentBubbleStore}); {@code CACHE} chỉ là
 * bộ nhớ đệm đọc-qua (read-through) nên restart tiến trình KHÔNG làm mất lịch sử hoặc trạng thái lease/attempt.
 * Khi chưa cài store bền vững (unit test thuần), dùng {@link InMemoryStore}.
 */
public final class SentBubbleLedger {

    /**
     * Vòng đời trạng thái gửi tin P3.1:
     * <ul>
     *   <li>{@link #PENDING}: Bản ghi outbound đã được lưu bền vững trước khi gọi kênh gửi.</li>
     *   <li>{@link #IN_FLIGHT}: Đang thực hiện một lần gửi, có {@code attemptCount} và {@code leaseUntil} phục vụ crash recovery.</li>
     *   <li>{@link #ACCEPTED_BY_CHANNEL}: Facebook Graph API đã chấp nhận request gửi với HTTP 2xx hợp lệ (chưa phải DELIVERED).</li>
     *   <li>{@link #DELIVERED}: Chỉ ghi khi thực sự nhận được delivery receipt ({@code delivery.mids} / {@code watermark}) từ kênh gửi.</li>
     *   <li>{@link #FAILED}: Có bằng chứng rõ ràng lần gửi đã thất bại (HTTP 4xx/5xx hoặc từ chối rõ ràng).</li>
     *   <li>{@link #UNKNOWN}: Không xác định chắc chắn Facebook đã chấp nhận hay chưa (timeout khi đang gửi hoặc hết hạn lease IN_FLIGHT sau khi crash). Không tự động gửi lặp.</li>
     *   <li>{@link #SUPPRESSED_BY_FENCE}: Bị chặn trước khi gửi bởi Outbound Fence (HUMAN_TAKEOVER, OPT_OUT, hoặc có inbound mới hơn làm mất hiệu lực).</li>
     *   <li>{@link #SENT}, {@link #CANCELED}: Giá trị tương thích ngược cho các bài test lịch sử/cắt ngang cũ.</li>
     * </ul>
     */
    public enum DeliveryStatus {
        PENDING,
        IN_FLIGHT,
        ACCEPTED_BY_CHANNEL,
        DELIVERED,
        FAILED,
        UNKNOWN,
        SUPPRESSED_BY_FENCE,
        SENT,
        CANCELED;

        /**
         * Kiểm tra chuyển trạng thái đơn điệu (monotonic state guard):
         * <ul>
         *   <li>{@link #DELIVERED} là trạng thái xác nhận cuối cùng cao nhất — không bao giờ bị hạ cấp bởi phản hồi HTTP đến muộn.</li>
         *   <li>{@link #ACCEPTED_BY_CHANNEL} chỉ có thể tiến lên {@link #DELIVERED}, không bao giờ lùi về PENDING / IN_FLIGHT / FAILED / UNKNOWN / SUPPRESSED_BY_FENCE.</li>
         *   <li>{@link #UNKNOWN} không bao giờ tự lùi về PENDING / IN_FLIGHT để gửi lặp; chỉ nâng lên ACCEPTED_BY_CHANNEL hoặc DELIVERED khi có bằng chứng.</li>
         *   <li>{@link #FAILED}, {@link #SUPPRESSED_BY_FENCE}, {@link #CANCELED} không bao giờ lùi về PENDING / IN_FLIGHT.</li>
         * </ul>
         */
        public static boolean canTransitionMonotonically(DeliveryStatus current, DeliveryStatus target) {
            if (target == null) {
                return false;
            }
            if (current == null || current == target) {
                return true;
            }
            return switch (current) {
                case DELIVERED -> false;
                case ACCEPTED_BY_CHANNEL, SENT -> target == DELIVERED;
                case UNKNOWN -> target == ACCEPTED_BY_CHANNEL || target == DELIVERED;
                case FAILED, SUPPRESSED_BY_FENCE, CANCELED -> target == DELIVERED;
                case IN_FLIGHT -> target == ACCEPTED_BY_CHANNEL
                        || target == DELIVERED
                        || target == FAILED
                        || target == UNKNOWN;
                case PENDING -> true;
            };
        }
    }

    public record BubbleRecord(
            int order,
            String text,
            DeliveryStatus status,
            String externalMid,
            int attemptCount,
            Instant leaseUntil,
            String failureReason
    ) {
        public BubbleRecord(int order, String text, DeliveryStatus status) {
            this(order, text, status, null, 0, null, null);
        }

        public boolean visibleToCustomer() {
            return status == DeliveryStatus.ACCEPTED_BY_CHANNEL
                    || status == DeliveryStatus.DELIVERED
                    || status == DeliveryStatus.SENT;
        }

        /**
         * Phân biệt bản ghi thực sự đã qua kênh gửi / nhận receipt với dữ liệu dựng sẵn (attemptCount=0, externalMid=null)
         * trong các unit test cũ.
         */
        public boolean isUnattemptedPreSendRecord() {
            return attemptCount == 0
                    && (externalMid == null || externalMid.isBlank())
                    && failureReason == null
                    && (status == DeliveryStatus.PENDING
                        || status == DeliveryStatus.SENT
                        || status == DeliveryStatus.DELIVERED);
        }
    }

    public record TurnRecord(String turnId, String role, List<BubbleRecord> bubbles) {
        /** Chỉ nối các bong bóng khách ĐÃ thấy (ACCEPTED_BY_CHANNEL / DELIVERED / SENT). */
        public String joinedText() {
            StringBuilder sb = new StringBuilder();
            for (BubbleRecord b : bubbles) {
                if (!b.visibleToCustomer() || b.text() == null || b.text().isBlank()) {
                    continue;
                }
                if (!sb.isEmpty()) {
                    sb.append("\n");
                }
                sb.append(b.text().strip());
            }
            return sb.toString();
        }

        /** Bong bóng cuối cùng khách đã thấy (dùng suy ra pendingQuestionSlot). */
        public String lastVisibleText() {
            String last = null;
            for (BubbleRecord b : bubbles) {
                if (b.visibleToCustomer() && b.text() != null && !b.text().isBlank()) {
                    last = b.text().strip();
                }
            }
            return last;
        }
    }

    public record DispatchTurnHandle(String ledgerKey, String turnId) {
    }

    /** Kho lưu trữ bền vững; {@code append} phải idempotent theo (key, turnId, role, order). */
    public interface Store {
        void append(String key, TurnRecord rec);

        List<TurnRecord> load(String key);

        void reset(String key);

        default void resetAll() {
        }

        default void markAssistantBubblesCanceledFrom(String key, String turnId, int cancelFromOrder) {
        }

        default boolean tryAcquireDispatchLease(
                String key,
                String turnId,
                int bubbleOrder,
                Instant leaseUntil,
                Instant now
        ) {
            return true;
        }

        default void updateBubbleState(
                String key,
                String turnId,
                int bubbleOrder,
                DeliveryStatus status,
                String externalMid,
                int attemptCount,
                Instant leaseUntil,
                String failureReason
        ) {
        }

        default int recoverExpiredInFlightLeases(String key, Instant now) {
            return 0;
        }

        default int recoverAllExpiredInFlightLeases(Instant now) {
            return 0;
        }

        default int markDeliveredByExternalMid(String pageId, String senderPsid, String externalMid) {
            return 0;
        }

        default int markDeliveredUpToWatermark(String pageId, String senderPsid, long watermarkEpochMs) {
            return 0;
        }
    }

    public static final class InMemoryStore implements Store {
        private final Map<String, List<TurnRecord>> data = new ConcurrentHashMap<>();

        @Override
        public synchronized void append(String key, TurnRecord rec) {
            List<TurnRecord> l = data.computeIfAbsent(key, k -> new ArrayList<>());
            for (int idx = 0; idx < l.size(); idx++) {
                TurnRecord existing = l.get(idx);
                if (sameEvent(existing, rec)) {
                    if (key != null && key.startsWith("fb_mid|")
                            && !existing.bubbles().isEmpty()
                            && !rec.bubbles().isEmpty()
                            && (existing.bubbles().get(0).text() == null || existing.bubbles().get(0).text().isBlank())
                            && rec.bubbles().get(0).text() != null
                            && !rec.bubbles().get(0).text().isBlank()) {
                        BubbleRecord eb = existing.bubbles().get(0);
                        BubbleRecord rb = rec.bubbles().get(0);
                        DeliveryStatus mergedStatus = eb.status() == DeliveryStatus.DELIVERED
                                ? DeliveryStatus.DELIVERED
                                : rb.status();
                        l.set(idx, new TurnRecord(
                                existing.turnId(),
                                existing.role(),
                                List.of(new BubbleRecord(
                                        0,
                                        rb.text(),
                                        mergedStatus,
                                        eb.externalMid() != null ? eb.externalMid() : rb.externalMid(),
                                        Math.max(eb.attemptCount(), rb.attemptCount()),
                                        null,
                                        eb.failureReason() != null ? eb.failureReason() : rb.failureReason()
                                ))
                        ));
                    }
                    return;
                }
            }
            l.add(rec);
        }

        @Override
        public synchronized List<TurnRecord> load(String key) {
            List<TurnRecord> l = data.get(key);
            return l == null ? List.of() : List.copyOf(l);
        }

        @Override
        public synchronized void reset(String key) {
            data.remove(key);
        }

        @Override
        public synchronized void resetAll() {
            data.clear();
        }

        @Override
        public synchronized void markAssistantBubblesCanceledFrom(String key, String turnId, int cancelFromOrder) {
            List<TurnRecord> l = data.get(key);
            if (l == null || turnId == null) {
                return;
            }
            for (int idx = l.size() - 1; idx >= 0; idx--) {
                TurnRecord t = l.get(idx);
                if ("assistant".equals(t.role()) && turnId.equals(t.turnId())) {
                    List<BubbleRecord> updated = new ArrayList<>(t.bubbles().size());
                    for (BubbleRecord b : t.bubbles()) {
                        updated.add(b.order() >= cancelFromOrder
                                ? new BubbleRecord(b.order(), b.text(), DeliveryStatus.CANCELED,
                                        b.externalMid(), b.attemptCount(), null, b.failureReason())
                                : b);
                    }
                    l.set(idx, new TurnRecord(t.turnId(), t.role(), List.copyOf(updated)));
                    break;
                }
            }
        }

        @Override
        public synchronized boolean tryAcquireDispatchLease(
                String key,
                String turnId,
                int bubbleOrder,
                Instant leaseUntil,
                Instant now
        ) {
            List<TurnRecord> l = data.get(key);
            if (l == null || turnId == null) {
                return false;
            }
            for (int idx = l.size() - 1; idx >= 0; idx--) {
                TurnRecord t = l.get(idx);
                if ("assistant".equals(t.role()) && turnId.equals(t.turnId())) {
                    List<BubbleRecord> updated = new ArrayList<>(t.bubbles().size());
                    boolean acquired = false;
                    for (BubbleRecord b : t.bubbles()) {
                        if (b.order() == bubbleOrder) {
                            if (b.isUnattemptedPreSendRecord()) {
                                acquired = true;
                                updated.add(new BubbleRecord(
                                        b.order(),
                                        b.text(),
                                        DeliveryStatus.IN_FLIGHT,
                                        b.externalMid(),
                                        b.attemptCount() + 1,
                                        leaseUntil,
                                        null
                                ));
                            } else {
                                updated.add(b);
                            }
                        } else {
                            updated.add(b);
                        }
                    }
                    if (acquired) {
                        l.set(idx, new TurnRecord(t.turnId(), t.role(), List.copyOf(updated)));
                    }
                    return acquired;
                }
            }
            return false;
        }

        @Override
        public synchronized void updateBubbleState(
                String key,
                String turnId,
                int bubbleOrder,
                DeliveryStatus status,
                String externalMid,
                int attemptCount,
                Instant leaseUntil,
                String failureReason
        ) {
            List<TurnRecord> l = data.get(key);
            if (l == null || turnId == null) {
                return;
            }
            for (int idx = l.size() - 1; idx >= 0; idx--) {
                TurnRecord t = l.get(idx);
                if ("assistant".equals(t.role()) && turnId.equals(t.turnId())) {
                    List<BubbleRecord> updated = new ArrayList<>(t.bubbles().size());
                    for (BubbleRecord b : t.bubbles()) {
                        if (b.order() == bubbleOrder) {
                            updated.add(new BubbleRecord(
                                    b.order(),
                                    b.text(),
                                    status,
                                    externalMid,
                                    attemptCount,
                                    leaseUntil,
                                    failureReason
                            ));
                        } else {
                            updated.add(b);
                        }
                    }
                    l.set(idx, new TurnRecord(t.turnId(), t.role(), List.copyOf(updated)));
                    break;
                }
            }
        }

        @Override
        public synchronized int recoverExpiredInFlightLeases(String key, Instant now) {
            List<TurnRecord> l = data.get(key);
            if (l == null) {
                return 0;
            }
            return recoverExpiredInList(l, now);
        }

        @Override
        public synchronized int recoverAllExpiredInFlightLeases(Instant now) {
            int total = 0;
            for (List<TurnRecord> l : data.values()) {
                total += recoverExpiredInList(l, now);
            }
            return total;
        }

        private int recoverExpiredInList(List<TurnRecord> l, Instant now) {
            int recovered = 0;
            Instant effectiveNow = now != null ? now : Instant.now();
            for (int idx = 0; idx < l.size(); idx++) {
                TurnRecord t = l.get(idx);
                if (!"assistant".equals(t.role())) {
                    continue;
                }
                boolean changed = false;
                List<BubbleRecord> updated = new ArrayList<>(t.bubbles().size());
                for (BubbleRecord b : t.bubbles()) {
                    if (b.status() == DeliveryStatus.IN_FLIGHT
                            && b.attemptCount() >= 1
                            && (b.leaseUntil() == null || !b.leaseUntil().isAfter(effectiveNow))) {
                        updated.add(new BubbleRecord(
                                b.order(),
                                b.text(),
                                DeliveryStatus.UNKNOWN,
                                b.externalMid(),
                                b.attemptCount(),
                                null,
                                b.failureReason() != null ? b.failureReason() : "IN_FLIGHT_LEASE_EXPIRED_CRASH_RECOVERY"
                        ));
                        changed = true;
                        recovered++;
                    } else {
                        updated.add(b);
                    }
                }
                if (changed) {
                    l.set(idx, new TurnRecord(t.turnId(), t.role(), List.copyOf(updated)));
                }
            }
            return recovered;
        }

        @Override
        public synchronized int markDeliveredByExternalMid(String pageId, String senderPsid, String externalMid) {
            if (externalMid == null || externalMid.isBlank()) {
                return 0;
            }
            String cleanMid = externalMid.strip();
            String normPage = (pageId != null && !pageId.isBlank()) ? pageId.strip() : "-";
            String normSender = (senderPsid != null && !senderPsid.isBlank()) ? senderPsid.strip() : "-";
            String prefix = normPage + "|" + normSender + "|";
            int updatedCount = 0;
            for (Map.Entry<String, List<TurnRecord>> entry : data.entrySet()) {
                if (!entry.getKey().startsWith(prefix)) {
                    continue;
                }
                List<TurnRecord> l = entry.getValue();
                for (int idx = 0; idx < l.size(); idx++) {
                    TurnRecord t = l.get(idx);
                    if (!"assistant".equals(t.role())) {
                        continue;
                    }
                    boolean changed = false;
                    List<BubbleRecord> updated = new ArrayList<>(t.bubbles().size());
                    for (BubbleRecord b : t.bubbles()) {
                        if (cleanMid.equals(b.externalMid())
                                && DeliveryStatus.canTransitionMonotonically(b.status(), DeliveryStatus.DELIVERED)) {
                            updated.add(new BubbleRecord(
                                    b.order(),
                                    b.text(),
                                    DeliveryStatus.DELIVERED,
                                    cleanMid,
                                    Math.max(1, b.attemptCount()),
                                    null,
                                    "DELIVERY_RECEIPT"
                            ));
                            changed = true;
                            if (b.status() != DeliveryStatus.DELIVERED) {
                                updatedCount++;
                            }
                        } else {
                            updated.add(b);
                        }
                    }
                    if (changed) {
                        l.set(idx, new TurnRecord(t.turnId(), t.role(), List.copyOf(updated)));
                    }
                }
            }
            // Option 2 (P3.1.1): Hold early delivery receipt keyed by cleanMid under midLedgerKey;
            // NEVER guess an IN_FLIGHT bubble whose externalMid is still null.
            String midKey = midLedgerKey(normPage, normSender);
            List<TurnRecord> midList = data.computeIfAbsent(midKey, k -> new ArrayList<>());
            boolean foundMidRecord = false;
            for (int idx = 0; idx < midList.size(); idx++) {
                TurnRecord t = midList.get(idx);
                if (cleanMid.equals(t.turnId()) && "assistant".equals(t.role()) && !t.bubbles().isEmpty()) {
                    foundMidRecord = true;
                    BubbleRecord b = t.bubbles().get(0);
                    if (b.status() != DeliveryStatus.DELIVERED) {
                        midList.set(idx, new TurnRecord(
                                t.turnId(),
                                t.role(),
                                List.of(new BubbleRecord(
                                        0,
                                        b.text(),
                                        DeliveryStatus.DELIVERED,
                                        cleanMid,
                                        Math.max(1, b.attemptCount()),
                                        null,
                                        "DELIVERY_RECEIPT"
                                ))
                        ));
                    }
                    break;
                }
            }
            if (!foundMidRecord) {
                midList.add(new TurnRecord(
                        cleanMid,
                        "assistant",
                        List.of(new BubbleRecord(0, "", DeliveryStatus.DELIVERED, cleanMid, 1, null, "DELIVERY_RECEIPT"))
                ));
            }
            return updatedCount;
        }

        @Override
        public synchronized int markDeliveredUpToWatermark(String pageId, String senderPsid, long watermarkEpochMs) {
            String prefix = (pageId != null ? pageId.strip() : "-") + "|" + (senderPsid != null ? senderPsid.strip() : "-") + "|";
            int updatedCount = 0;
            for (Map.Entry<String, List<TurnRecord>> entry : data.entrySet()) {
                if (!entry.getKey().startsWith(prefix)) {
                    continue;
                }
                List<TurnRecord> l = entry.getValue();
                for (int idx = 0; idx < l.size(); idx++) {
                    TurnRecord t = l.get(idx);
                    if (!"assistant".equals(t.role())) {
                        continue;
                    }
                    boolean changed = false;
                    List<BubbleRecord> updated = new ArrayList<>(t.bubbles().size());
                    for (BubbleRecord b : t.bubbles()) {
                        if (b.status() == DeliveryStatus.ACCEPTED_BY_CHANNEL) {
                            updated.add(new BubbleRecord(
                                    b.order(),
                                    b.text(),
                                    DeliveryStatus.DELIVERED,
                                    b.externalMid(),
                                    Math.max(1, b.attemptCount()),
                                    null,
                                    "DELIVERY_WATERMARK:" + watermarkEpochMs
                            ));
                            changed = true;
                            updatedCount++;
                        } else {
                            updated.add(b);
                        }
                    }
                    if (changed) {
                        l.set(idx, new TurnRecord(t.turnId(), t.role(), List.copyOf(updated)));
                    }
                }
            }
            return updatedCount;
        }
    }

    private static final int MAX_TURNS_PER_SESSION = 60;
    private static final int MAX_RECONCILED_TURNS = 24;
    private static final int MAX_MID_CACHE_SIZE = 2000;
    private static final long MID_CACHE_TTL_MS = 24L * 60L * 60L * 1000L;
    public static final Duration DEFAULT_IN_FLIGHT_LEASE_DURATION = Duration.ofSeconds(30);

    private record CachedMidEntry(String text, String role, long expiresAtMs) {
    }

    private static final Map<String, List<TurnRecord>> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, CachedMidEntry> MID_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> EARLY_DELIVERY_RECEIPT_MIDS = ConcurrentHashMap.newKeySet();
    private static volatile Store store = new InMemoryStore();

    private SentBubbleLedger() {
    }

    /** Cài kho bền vững (gọi từ {@link JdbcSentBubbleStore} khi khởi động). */
    public static void useStore(Store s) {
        store = s != null ? s : new InMemoryStore();
        CACHE.clear();
        MID_CACHE.clear();
        EARLY_DELIVERY_RECEIPT_MIDS.clear();
    }

    public static String key(String pageId, String senderId, String conversationId) {
        return (pageId != null ? pageId : "-") + "|" + (senderId != null ? senderId : "-") + "|"
                + (conversationId != null ? conversationId : "-");
    }

    public static String midLedgerKey(String pageId, String senderPsid) {
        String normPage = (pageId != null && !pageId.isBlank()) ? pageId.strip() : "-";
        String normSender = (senderPsid != null && !senderPsid.isBlank()) ? senderPsid.strip() : "-";
        return "fb_mid|" + normPage + "|" + normSender;
    }

    private static String midCacheKey(String pageId, String senderPsid, String messageId) {
        return midLedgerKey(pageId, senderPsid) + "|" + (messageId != null ? messageId.strip() : "");
    }

    private static String earlyReceiptKey(String pageId, String senderPsid, String externalMid) {
        String normPage = (pageId != null && !pageId.isBlank()) ? pageId.strip() : "-";
        String normSender = (senderPsid != null && !senderPsid.isBlank()) ? senderPsid.strip() : "-";
        String normMid = (externalMid != null && !externalMid.isBlank()) ? externalMid.strip() : "-";
        return normPage + "|" + normSender + "|" + normMid;
    }

    /**
     * Kiểm tra xem một {@code externalMid} đã có Delivery Receipt đến sớm (Option 2 — P3.1.1)
     * trong bộ nhớ hoặc trong kho bền vững {@code fb_mid|pageId|senderPsid} hay chưa.
     */
    public static boolean hasHeldEarlyDeliveryReceipt(String pageId, String senderPsid, String externalMid) {
        if (externalMid == null || externalMid.isBlank()) {
            return false;
        }
        String cleanMid = externalMid.strip();
        String eKey = earlyReceiptKey(pageId, senderPsid, cleanMid);
        if (EARLY_DELIVERY_RECEIPT_MIDS.contains(eKey)) {
            return true;
        }
        try {
            List<TurnRecord> persisted = store.load(midLedgerKey(pageId, senderPsid));
            for (TurnRecord rec : persisted) {
                if (rec != null && cleanMid.equals(rec.turnId()) && !rec.bubbles().isEmpty()) {
                    BubbleRecord b = rec.bubbles().get(0);
                    if (b.status() == DeliveryStatus.DELIVERED) {
                        EARLY_DELIVERY_RECEIPT_MIDS.add(eKey);
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
            // Non-blocking fallback
        }
        return false;
    }

    /**
     * Lưu ánh xạ bền vững {@code (pageId, senderPsid, messageId) -> messageText} vào Bounded Cache có TTL
     * và xuống {@link Store} bền vững (PostgreSQL {@code sent_bubble_ledger} / {@link InMemoryStore}).
     *
     * <p>P3.1: Tin nhắn của {@code assistant} khi mới nhận HTTP 2xx từ Graph API chỉ được ghi nhận ở trạng thái
     * {@link DeliveryStatus#ACCEPTED_BY_CHANNEL} (trừ khi đã có delivery receipt đến sớm cho {@code messageId} đó).
     */
    public static void recordMessageId(String pageId, String senderPsid, String messageId, String role, String messageText) {
        if (messageId == null || messageId.isBlank() || messageText == null || messageText.isBlank()) {
            return;
        }
        String cleanMid = messageId.strip();
        String cleanText = messageText.strip();
        String cleanRole = (role != null && !role.isBlank()) ? role.strip() : "assistant";
        long now = System.currentTimeMillis();

        if (MID_CACHE.size() >= MAX_MID_CACHE_SIZE) {
            MID_CACHE.entrySet().removeIf(e -> e.getValue() == null || e.getValue().expiresAtMs() <= now);
            if (MID_CACHE.size() >= MAX_MID_CACHE_SIZE) {
                String firstKey = MID_CACHE.keySet().iterator().next();
                if (firstKey != null) {
                    MID_CACHE.remove(firstKey);
                }
            }
        }

        MID_CACHE.put(midCacheKey(pageId, senderPsid, cleanMid),
                new CachedMidEntry(cleanText, cleanRole, now + MID_CACHE_TTL_MS));
        DeliveryStatus midStatus;
        if ("user".equalsIgnoreCase(cleanRole)) {
            midStatus = DeliveryStatus.DELIVERED;
        } else if (hasHeldEarlyDeliveryReceipt(pageId, senderPsid, cleanMid)) {
            midStatus = DeliveryStatus.DELIVERED;
        } else {
            midStatus = DeliveryStatus.ACCEPTED_BY_CHANNEL;
        }
        try {
            store.append(midLedgerKey(pageId, senderPsid),
                    new TurnRecord(cleanMid, cleanRole,
                            List.of(new BubbleRecord(0, cleanText, midStatus, cleanMid, 1, null, null))));
        } catch (Exception ignored) {
            // Non-blocking fallback if DB store is temporarily unavailable
        }
    }

    /**
     * Tra cứu nội dung bong bóng gốc theo {@code (pageId, senderPsid, messageId)}.
     * Ưu tiên đọc từ Bounded Cache (có kiểm tra TTL); nếu cache miss (ví dụ sau khi container restart
     * hoặc gọi {@link #dropCacheOnly()}), tự động khôi phục từ {@link Store} bền vững.
     */
    public static String lookupMessageByMid(String pageId, String senderPsid, String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return null;
        }
        String cleanMid = messageId.strip();
        String cKey = midCacheKey(pageId, senderPsid, cleanMid);
        long now = System.currentTimeMillis();

        CachedMidEntry cachedEntry = MID_CACHE.get(cKey);
        if (cachedEntry != null) {
            if (cachedEntry.expiresAtMs() > now && cachedEntry.text() != null && !cachedEntry.text().isBlank()) {
                return cachedEntry.text();
            }
            MID_CACHE.remove(cKey);
        }

        try {
            List<TurnRecord> persisted = store.load(midLedgerKey(pageId, senderPsid));
            String matchedText = null;
            for (TurnRecord rec : persisted) {
                if (rec == null || rec.turnId() == null || rec.turnId().isBlank()) {
                    continue;
                }
                String txt = rec.joinedText();
                if (txt == null || txt.isBlank()) {
                    continue;
                }
                MID_CACHE.put(midCacheKey(pageId, senderPsid, rec.turnId().strip()),
                        new CachedMidEntry(txt, rec.role() != null ? rec.role() : "assistant", now + MID_CACHE_TTL_MS));
                if (cleanMid.equals(rec.turnId().strip())) {
                    matchedText = txt;
                }
            }
            return matchedText;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Xoá toàn bộ (cache + store) — chỉ dùng trong test. */
    public static void clear() {
        CACHE.clear();
        MID_CACHE.clear();
        EARLY_DELIVERY_RECEIPT_MIDS.clear();
        store.resetAll();
    }

    /** Mô phỏng restart tiến trình: mất cache nhưng dữ liệu bền vững còn nguyên. */
    public static void dropCacheOnly() {
        CACHE.clear();
        MID_CACHE.clear();
    }

    /** resetSession: vô hiệu hoá cả kho bền vững lẫn cache. */
    public static void reset(String key) {
        CACHE.remove(key);
        store.reset(key);
    }

    /** turnId ổn định theo (key, thứ tự lượt khách, nội dung) để webhook retry không ghi trùng. */
    public static String stableUserTurnId(String key, String text) {
        int userOrdinal = 0;
        for (TurnRecord t : snapshot(key)) {
            if ("user".equals(t.role())) {
                userOrdinal++;
            }
        }
        return Integer.toHexString((key + "#" + userOrdinal + "#" + (text == null ? "" : text.strip())).hashCode())
                + "-" + userOrdinal;
    }

    public static void recordCustomerTurn(String key, String turnId, String text) {
        if (key == null || text == null || text.isBlank()) {
            return;
        }
        append(key, new TurnRecord(turnId, "user",
                List.of(new BubbleRecord(0, text.strip(), DeliveryStatus.DELIVERED))));
    }

    public static void recordAssistantTurn(String key, String turnId, List<String> bubbleTexts, DeliveryStatus status) {
        recordAssistantTurn(key, turnId, bubbleTexts, status, List.of());
    }

    /** {@code canceledTexts}: bong bóng bị huỷ (concurrent turn) — lưu để audit nhưng khách KHÔNG thấy. */
    public static void recordAssistantTurn(String key, String turnId, List<String> bubbleTexts,
                                           DeliveryStatus status, List<String> canceledTexts) {
        if (key == null) {
            return;
        }
        List<BubbleRecord> recs = new ArrayList<>();
        int i = 0;
        if (bubbleTexts != null) {
            for (String t : bubbleTexts) {
                if (t != null && !t.isBlank()) {
                    recs.add(new BubbleRecord(i++, t.strip(), status));
                }
            }
        }
        if (canceledTexts != null) {
            for (String t : canceledTexts) {
                if (t != null && !t.isBlank()) {
                    recs.add(new BubbleRecord(i++, t.strip(), DeliveryStatus.CANCELED));
                }
            }
        }
        if (!recs.isEmpty()) {
            append(key, new TurnRecord(turnId, "assistant", List.copyOf(recs)));
        }
    }

    /**
     * Đảm bảo lượt outbound tồn tại trong {@link SentBubbleLedger} trước khi Dispatcher thực hiện gửi tuần tự.
     * Nếu {@code FanpageBotService} đã ghi bản ghi {@link DeliveryStatus#PENDING} ở cuối lượt sinh câu trả lời,
     * phương thức này trả về đúng {@code (ledgerKey, turnId)} đó; nếu test gọi thẳng {@code sendSequentialBubblesToFacebook},
     * phương thức sẽ khởi tạo bản ghi {@link DeliveryStatus#PENDING} tương ứng.
     */
    public static DispatchTurnHandle ensureOrResolveTurnForDispatch(
            String pageId,
            String senderPsid,
            String conversationId,
            List<String> bubbleTexts
    ) {
        String normPage = (pageId != null && !pageId.isBlank()) ? pageId.strip() : "-";
        String normSender = (senderPsid != null && !senderPsid.isBlank()) ? senderPsid.strip() : "-";
        List<String> candidateKeys = new ArrayList<>();
        if (conversationId != null && !conversationId.isBlank()) {
            candidateKeys.add(key(normPage, normSender, conversationId.strip()));
        }
        String webhookConvKey = key(normPage, normSender, "fb_webhook_" + normSender);
        if (!candidateKeys.contains(webhookConvKey)) {
            candidateKeys.add(webhookConvKey);
        }
        String nullConvKey = key(normPage, normSender, null);
        if (!candidateKeys.contains(nullConvKey)) {
            candidateKeys.add(nullConvKey);
        }

        for (String candKey : candidateKeys) {
            List<TurnRecord> turns = snapshot(candKey);
            for (int idx = turns.size() - 1; idx >= 0; idx--) {
                TurnRecord t = turns.get(idx);
                if ("assistant".equals(t.role()) && matchesBubbleTexts(t.bubbles(), bubbleTexts)) {
                    return new DispatchTurnHandle(candKey, t.turnId());
                }
            }
        }
        for (String candKey : candidateKeys) {
            List<TurnRecord> turns = snapshot(candKey);
            for (int idx = turns.size() - 1; idx >= 0; idx--) {
                TurnRecord t = turns.get(idx);
                if ("assistant".equals(t.role())) {
                    return new DispatchTurnHandle(candKey, t.turnId());
                }
            }
        }

        String primaryKey = candidateKeys.get(0);
        String turnId = "out-" + Integer.toHexString(Objects.hash(primaryKey, bubbleTexts, System.nanoTime()));
        recordAssistantTurn(primaryKey, turnId, bubbleTexts, DeliveryStatus.PENDING, List.of());
        return new DispatchTurnHandle(primaryKey, turnId);
    }

    private static boolean matchesBubbleTexts(List<BubbleRecord> bubbles, List<String> expectedTexts) {
        if (bubbles == null || expectedTexts == null || bubbles.size() != expectedTexts.size()) {
            return false;
        }
        for (int i = 0; i < bubbles.size(); i++) {
            String a = bubbles.get(i).text() != null ? bubbles.get(i).text().strip() : "";
            String b = expectedTexts.get(i) != null ? expectedTexts.get(i).strip() : "";
            if (!a.equals(b)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Kiểm tra xem có lượt tin nhắn khách hàng ({@code role == "user"}) nào mới hơn {@code assistantTurnId}
     * đã được ghi nhận vào cùng {@code ledgerKey} hay chưa.
     */
    public static boolean hasNewerCustomerTurnAfter(String key, String assistantTurnId) {
        if (key == null || assistantTurnId == null) {
            return false;
        }
        List<TurnRecord> turns = snapshot(key);
        boolean seenTargetAssistantTurn = false;
        for (TurnRecord t : turns) {
            if ("assistant".equals(t.role()) && assistantTurnId.equals(t.turnId())) {
                seenTargetAssistantTurn = true;
            } else if (seenTargetAssistantTurn && "user".equals(t.role())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Cấp phát lease gửi tin ({@code PENDING -> IN_FLIGHT}, tăng {@code attemptCount}, gán {@code leaseUntil}).
     * Trả về {@code false} nếu bong bóng đã từng bắt đầu gửi ({@code attemptCount >= 1}), đang ở {@code IN_FLIGHT},
     * {@code UNKNOWN}, {@code ACCEPTED_BY_CHANNEL}, {@code DELIVERED}, {@code FAILED} hoặc {@code SUPPRESSED_BY_FENCE}.
     */
    public static boolean tryAcquireBubbleDispatchLease(
            String key,
            String turnId,
            int bubbleOrder,
            Duration leaseDuration
    ) {
        return tryAcquireBubbleDispatchLease(key, turnId, bubbleOrder, leaseDuration, Instant.now());
    }

    public static boolean tryAcquireBubbleDispatchLease(
            String key,
            String turnId,
            int bubbleOrder,
            Duration leaseDuration,
            Instant now
    ) {
        if (key == null || turnId == null || bubbleOrder < 0) {
            return false;
        }
        Instant effectiveNow = now != null ? now : Instant.now();
        Duration effectiveLease = (leaseDuration != null && !leaseDuration.isNegative() && !leaseDuration.isZero())
                ? leaseDuration
                : DEFAULT_IN_FLIGHT_LEASE_DURATION;
        Instant leaseUntil = effectiveNow.plus(effectiveLease);

        List<TurnRecord> l = cached(key);
        synchronized (l) {
            int targetIdx = -1;
            TurnRecord targetTurn = null;
            for (int idx = l.size() - 1; idx >= 0; idx--) {
                TurnRecord t = l.get(idx);
                if ("assistant".equals(t.role()) && turnId.equals(t.turnId())) {
                    targetIdx = idx;
                    targetTurn = t;
                    break;
                }
            }
            if (targetTurn == null) {
                return false;
            }
            BubbleRecord targetBubble = null;
            for (BubbleRecord b : targetTurn.bubbles()) {
                if (b.order() == bubbleOrder) {
                    targetBubble = b;
                    break;
                }
            }
            if (targetBubble == null || !targetBubble.isUnattemptedPreSendRecord()) {
                return false;
            }
            boolean dbAcquired = store.tryAcquireDispatchLease(key, turnId, bubbleOrder, leaseUntil, effectiveNow);
            if (!dbAcquired) {
                return false;
            }
            List<BubbleRecord> updated = new ArrayList<>(targetTurn.bubbles().size());
            for (BubbleRecord b : targetTurn.bubbles()) {
                if (b.order() == bubbleOrder) {
                    updated.add(new BubbleRecord(
                            b.order(),
                            b.text(),
                            DeliveryStatus.IN_FLIGHT,
                            b.externalMid(),
                            b.attemptCount() + 1,
                            leaseUntil,
                            null
                    ));
                } else {
                    updated.add(b);
                }
            }
            l.set(targetIdx, new TurnRecord(targetTurn.turnId(), targetTurn.role(), List.copyOf(updated)));
            return true;
        }
    }

    /**
     * Chuyển trạng thái bong bóng theo quy tắc đơn điệu (monotonic state guard).
     * Không bao giờ cho phép trạng thái xác nhận cao hơn ({@link DeliveryStatus#DELIVERED}) bị ghi đè ngược về
     * {@link DeliveryStatus#ACCEPTED_BY_CHANNEL} khi phản hồi HTTP về muộn hơn webhook delivery receipt.
     */
    public static BubbleRecord transitionBubbleStatusMonotonically(
            String key,
            String turnId,
            int bubbleOrder,
            DeliveryStatus targetStatus,
            String externalMid,
            String failureReason
    ) {
        if (key == null || turnId == null || targetStatus == null) {
            return null;
        }
        String cleanMid = (externalMid != null && !externalMid.isBlank()) ? externalMid.strip() : null;
        String[] keyParts = key.split("\\|", -1);
        String pagePart = keyParts.length > 0 ? keyParts[0] : "-";
        String senderPart = keyParts.length > 1 ? keyParts[1] : "-";

        List<TurnRecord> l = cached(key);
        synchronized (l) {
            for (int idx = l.size() - 1; idx >= 0; idx--) {
                TurnRecord t = l.get(idx);
                if (!"assistant".equals(t.role()) || !turnId.equals(t.turnId())) {
                    continue;
                }
                List<BubbleRecord> updated = new ArrayList<>(t.bubbles().size());
                BubbleRecord resultRecord = null;
                for (BubbleRecord b : t.bubbles()) {
                    if (b.order() != bubbleOrder) {
                        updated.add(b);
                        continue;
                    }
                    DeliveryStatus effectiveTarget = targetStatus;
                    String effectiveReason = failureReason;
                    if (cleanMid != null && hasHeldEarlyDeliveryReceipt(pagePart, senderPart, cleanMid)) {
                        effectiveTarget = DeliveryStatus.DELIVERED;
                        effectiveReason = "DELIVERY_RECEIPT";
                    }
                    boolean hasPriorReceipt = b.status() == DeliveryStatus.DELIVERED
                            && ((b.externalMid() != null && !b.externalMid().isBlank())
                                || (b.failureReason() != null && b.failureReason().startsWith("DELIVERY_")));
                    DeliveryStatus finalStatus;
                    if (hasPriorReceipt) {
                        finalStatus = DeliveryStatus.DELIVERED;
                    } else if (DeliveryStatus.canTransitionMonotonically(b.status(), effectiveTarget)) {
                        finalStatus = effectiveTarget;
                    } else {
                        finalStatus = b.status();
                    }
                    String finalMid = cleanMid != null ? cleanMid : b.externalMid();
                    int finalAttempts = Math.max(b.attemptCount(),
                            (finalStatus == DeliveryStatus.IN_FLIGHT
                                    || finalStatus == DeliveryStatus.ACCEPTED_BY_CHANNEL
                                    || finalStatus == DeliveryStatus.DELIVERED
                                    || finalStatus == DeliveryStatus.FAILED
                                    || finalStatus == DeliveryStatus.UNKNOWN) ? 1 : 0);
                    Instant finalLease = finalStatus == DeliveryStatus.IN_FLIGHT ? b.leaseUntil() : null;
                    String finalReason = effectiveReason != null ? effectiveReason : b.failureReason();
                    resultRecord = new BubbleRecord(
                            b.order(),
                            b.text(),
                            finalStatus,
                            finalMid,
                            finalAttempts,
                            finalLease,
                            finalReason
                    );
                    updated.add(resultRecord);
                    store.updateBubbleState(
                            key,
                            turnId,
                            bubbleOrder,
                            finalStatus,
                            finalMid,
                            finalAttempts,
                            finalLease,
                            finalReason
                    );
                }
                if (resultRecord != null) {
                    l.set(idx, new TurnRecord(t.turnId(), t.role(), List.copyOf(updated)));
                    return resultRecord;
                }
            }
        }
        return null;
    }

    /**
     * Đánh dấu các bong bóng chưa gửi ({@code order >= fromOrder}) ở lượt {@code turnId} (hoặc lượt assistant mới nhất)
     * thành {@link DeliveryStatus#SUPPRESSED_BY_FENCE} khi bị chặn bởi Outbound Fence ({@code HUMAN_TAKEOVER},
     * {@code OPT_OUT}, hoặc có inbound mới hơn làm mất hiệu lực).
     */
    public static void markAssistantBubblesSuppressedByFenceFrom(
            String key,
            String turnId,
            int fromOrder,
            String fenceReason
    ) {
        if (key == null || fromOrder < 0) {
            return;
        }
        String effectiveReason = (fenceReason != null && !fenceReason.isBlank())
                ? fenceReason.strip()
                : "SUPPRESSED_BY_OUTBOUND_FENCE";
        List<TurnRecord> l = cached(key);
        synchronized (l) {
            for (int idx = l.size() - 1; idx >= 0; idx--) {
                TurnRecord t = l.get(idx);
                if (!"assistant".equals(t.role())) {
                    continue;
                }
                if (turnId != null && !turnId.equals(t.turnId())) {
                    continue;
                }
                List<BubbleRecord> updated = new ArrayList<>(t.bubbles().size());
                for (BubbleRecord b : t.bubbles()) {
                    if (b.order() >= fromOrder && b.isUnattemptedPreSendRecord()) {
                        BubbleRecord suppressed = new BubbleRecord(
                                b.order(),
                                b.text(),
                                DeliveryStatus.SUPPRESSED_BY_FENCE,
                                b.externalMid(),
                                b.attemptCount(),
                                null,
                                effectiveReason
                        );
                        updated.add(suppressed);
                        store.updateBubbleState(
                                key,
                                t.turnId(),
                                b.order(),
                                DeliveryStatus.SUPPRESSED_BY_FENCE,
                                b.externalMid(),
                                b.attemptCount(),
                                null,
                                effectiveReason
                        );
                    } else {
                        updated.add(b);
                    }
                }
                l.set(idx, new TurnRecord(t.turnId(), t.role(), List.copyOf(updated)));
                break;
            }
        }
    }

    public static void markLatestAssistantTurnSuppressedByFence(String key, String fenceReason) {
        markAssistantBubblesSuppressedByFenceFrom(key, null, 0, fenceReason);
    }

    /**
     * Crash Recovery (P3.1 Mục 3.3): Các bản ghi {@link DeliveryStatus#IN_FLIGHT} đã từng thực hiện attempt gửi
     * ({@code attemptCount >= 1}) nhưng hết hạn lease khi tiến trình dừng đột ngột phải chuyển sang
     * {@link DeliveryStatus#UNKNOWN}, tuyệt đối KHÔNG tự động gửi lại.
     */
    public static int recoverExpiredInFlightLeases(String key, Instant now) {
        if (key == null) {
            return 0;
        }
        Instant effectiveNow = now != null ? now : Instant.now();
        int dbRecovered = store.recoverExpiredInFlightLeases(key, effectiveNow);
        CACHE.remove(key);
        List<TurnRecord> reloaded = cached(key);
        int memRecovered = 0;
        synchronized (reloaded) {
            for (int idx = 0; idx < reloaded.size(); idx++) {
                TurnRecord t = reloaded.get(idx);
                if (!"assistant".equals(t.role())) {
                    continue;
                }
                boolean changed = false;
                List<BubbleRecord> updated = new ArrayList<>(t.bubbles().size());
                for (BubbleRecord b : t.bubbles()) {
                    if (b.status() == DeliveryStatus.IN_FLIGHT
                            && b.attemptCount() >= 1
                            && (b.leaseUntil() == null || !b.leaseUntil().isAfter(effectiveNow))) {
                        BubbleRecord unk = new BubbleRecord(
                                b.order(),
                                b.text(),
                                DeliveryStatus.UNKNOWN,
                                b.externalMid(),
                                b.attemptCount(),
                                null,
                                b.failureReason() != null ? b.failureReason() : "IN_FLIGHT_LEASE_EXPIRED_CRASH_RECOVERY"
                        );
                        updated.add(unk);
                        changed = true;
                        memRecovered++;
                        store.updateBubbleState(
                                key,
                                t.turnId(),
                                b.order(),
                                DeliveryStatus.UNKNOWN,
                                b.externalMid(),
                                b.attemptCount(),
                                null,
                                unk.failureReason()
                        );
                    } else {
                        updated.add(b);
                    }
                }
                if (changed) {
                    reloaded.set(idx, new TurnRecord(t.turnId(), t.role(), List.copyOf(updated)));
                }
            }
        }
        return Math.max(dbRecovered, memRecovered);
    }

    public static int recoverAllExpiredInFlightLeases(Instant now) {
        Instant effectiveNow = now != null ? now : Instant.now();
        int count = store.recoverAllExpiredInFlightLeases(effectiveNow);
        CACHE.clear();
        return count;
    }

    /**
     * Ghi nhận Delivery Receipt thực tế từ Meta Webhook ({@code delivery.mids}) theo hướng đơn điệu
     * và idempotent (Option 2 — P3.1.1).
     *
     * <p>Nếu receipt đến trước khi luồng gửi HTTP kịp trả về {@code message_id},
     * lưu giữ receipt trong {@code EARLY_DELIVERY_RECEIPT_MIDS} và trong kho bền vững {@code fb_mid|pageId|senderPsid};
     * tuyệt đối KHÔNG đoán mò gán cho bong bóng {@code IN_FLIGHT} chưa có {@code externalMid}.
     * Ngay khi phản hồi HTTP hoặc {@code message_echo} cung cấp {@code externalMid} khớp, bong bóng đó sẽ được nâng lên {@code DELIVERED}.
     */
    public static int markBubbleDeliveredByExternalMid(String pageId, String senderPsid, String externalMid) {
        if (externalMid == null || externalMid.isBlank()) {
            return 0;
        }
        String cleanMid = externalMid.strip();
        String normPage = (pageId != null && !pageId.isBlank()) ? pageId.strip() : "-";
        String normSender = (senderPsid != null && !senderPsid.isBlank()) ? senderPsid.strip() : "-";
        EARLY_DELIVERY_RECEIPT_MIDS.add(earlyReceiptKey(normPage, normSender, cleanMid));

        int storeUpdated = store.markDeliveredByExternalMid(normPage, normSender, cleanMid);
        cached(key(normPage, normSender, null));
        cached(key(normPage, normSender, "fb_webhook_" + normSender));

        String prefix = normPage + "|" + normSender + "|";
        int cacheUpdated = 0;
        for (Map.Entry<String, List<TurnRecord>> entry : CACHE.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) {
                continue;
            }
            List<TurnRecord> l = entry.getValue();
            synchronized (l) {
                for (int idx = 0; idx < l.size(); idx++) {
                    TurnRecord t = l.get(idx);
                    if (!"assistant".equals(t.role())) {
                        continue;
                    }
                    boolean changed = false;
                    List<BubbleRecord> updated = new ArrayList<>(t.bubbles().size());
                    for (BubbleRecord b : t.bubbles()) {
                        if (cleanMid.equals(b.externalMid())
                                && DeliveryStatus.canTransitionMonotonically(b.status(), DeliveryStatus.DELIVERED)) {
                            updated.add(new BubbleRecord(
                                    b.order(),
                                    b.text(),
                                    DeliveryStatus.DELIVERED,
                                    cleanMid,
                                    Math.max(1, b.attemptCount()),
                                    null,
                                    "DELIVERY_RECEIPT"
                            ));
                            changed = true;
                            if (b.status() != DeliveryStatus.DELIVERED) {
                                cacheUpdated++;
                            }
                        } else {
                            updated.add(b);
                        }
                    }
                    if (changed) {
                        l.set(idx, new TurnRecord(t.turnId(), t.role(), List.copyOf(updated)));
                    }
                }
            }
        }
        return Math.max(storeUpdated, cacheUpdated);
    }

    /**
     * Đối soát sự kiện {@code message_echoes} từ Meta Webhook (P3.1.1 Mục 2.2.B & 2.2.C):
     * <ul>
     *   <li>Tuyệt đối KHÔNG coi {@code message_echoes} là {@link DeliveryStatus#DELIVERED} (trừ khi đã có
     *       delivery receipt thực tế đến sớm cho đúng {@code echoMid} đó).</li>
     *   <li>{@code message_echoes} là bằng chứng Page đã gửi/phản chiếu tin nhắn trên kênh, nên chỉ nâng
     *       bong bóng đang ở {@link DeliveryStatus#UNKNOWN} hoặc {@link DeliveryStatus#IN_FLIGHT} lên
     *       {@link DeliveryStatus#ACCEPTED_BY_CHANNEL} (hoặc {@link DeliveryStatus#DELIVERED} nếu đã giữ receipt của {@code echoMid}).</li>
     *   <li>Chỉ ghép theo nội dung {@code echoText} khi có ĐÚNG 1 bong bóng {@code UNKNOWN}/{@code IN_FLIGHT} chưa có {@code externalMid}
     *       khớp nội dung trong phạm vi {@code (pageId, senderPsid)} (không bao giờ đoán mò nếu có &ge; 2 ứng viên trùng nội dung).</li>
     * </ul>
     */
    public static int reconcileOutboundEchoFromWebhook(
            String pageId,
            String senderPsid,
            String echoMid,
            String echoText
    ) {
        if (echoMid == null || echoMid.isBlank()) {
            return 0;
        }
        String cleanMid = echoMid.strip();
        String cleanText = (echoText != null && !echoText.isBlank()) ? echoText.strip() : null;
        String normPage = (pageId != null && !pageId.isBlank()) ? pageId.strip() : "-";
        String normSender = (senderPsid != null && !senderPsid.isBlank()) ? senderPsid.strip() : "-";

        if (cleanText != null) {
            recordMessageId(normPage, normSender, cleanMid, "assistant", cleanText);
        }

        cached(key(normPage, normSender, null));
        cached(key(normPage, normSender, "fb_webhook_" + normSender));

        boolean heldReceipt = hasHeldEarlyDeliveryReceipt(normPage, normSender, cleanMid);
        DeliveryStatus targetStatus = heldReceipt
                ? DeliveryStatus.DELIVERED
                : DeliveryStatus.ACCEPTED_BY_CHANNEL;
        String targetReason = heldReceipt
                ? "DELIVERY_RECEIPT"
                : "RECONCILED_BY_MESSAGE_ECHO";

        String prefix = normPage + "|" + normSender + "|";
        int reconciledCount = 0;

        // Bước 1: Khớp chính xác theo externalMid đã biết
        for (Map.Entry<String, List<TurnRecord>> entry : CACHE.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) {
                continue;
            }
            List<TurnRecord> l = entry.getValue();
            synchronized (l) {
                for (TurnRecord t : l) {
                    if (!"assistant".equals(t.role())) {
                        continue;
                    }
                    for (BubbleRecord b : t.bubbles()) {
                        if (cleanMid.equals(b.externalMid())
                                && (b.status() == DeliveryStatus.UNKNOWN
                                    || b.status() == DeliveryStatus.IN_FLIGHT
                                    || (heldReceipt && b.status() == DeliveryStatus.ACCEPTED_BY_CHANNEL))) {
                            BubbleRecord updated = transitionBubbleStatusMonotonically(
                                    entry.getKey(),
                                    t.turnId(),
                                    b.order(),
                                    targetStatus,
                                    cleanMid,
                                    targetReason
                            );
                            if (updated != null && updated.status() == targetStatus) {
                                reconciledCount++;
                            }
                        }
                    }
                }
            }
        }
        if (reconciledCount > 0 || cleanText == null) {
            return reconciledCount;
        }

        // Bước 2: Khớp đơn trị (unambiguous) theo văn bản đối với bong bóng UNKNOWN/IN_FLIGHT chưa có externalMid
        String normEchoText = norm(cleanText);
        java.util.Set<String> candidateTurnOrders = new java.util.LinkedHashSet<>();
        java.util.List<String[]> candidateLocations = new ArrayList<>();
        for (Map.Entry<String, List<TurnRecord>> entry : CACHE.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) {
                continue;
            }
            List<TurnRecord> l = entry.getValue();
            synchronized (l) {
                for (TurnRecord t : l) {
                    if (!"assistant".equals(t.role())) {
                        continue;
                    }
                    for (BubbleRecord b : t.bubbles()) {
                        if ((b.status() == DeliveryStatus.UNKNOWN || b.status() == DeliveryStatus.IN_FLIGHT)
                                && (b.externalMid() == null || b.externalMid().isBlank())
                                && normEchoText.equals(norm(b.text()))) {
                            String logicalId = t.turnId() + ":" + b.order();
                            candidateTurnOrders.add(logicalId);
                            candidateLocations.add(new String[]{entry.getKey(), t.turnId(), String.valueOf(b.order())});
                        }
                    }
                }
            }
        }

        // Chỉ đối soát khi có ĐÚNG 1 bong bóng logic khớp văn bản; nếu >= 2 ứng viên thì giữ nguyên UNKNOWN để an toàn
        if (candidateTurnOrders.size() == 1) {
            for (String[] loc : candidateLocations) {
                BubbleRecord updated = transitionBubbleStatusMonotonically(
                        loc[0],
                        loc[1],
                        Integer.parseInt(loc[2]),
                        targetStatus,
                        cleanMid,
                        targetReason
                );
                if (updated != null && updated.status() == targetStatus) {
                    reconciledCount++;
                }
            }
        }
        return reconciledCount;
    }

    public static int markBubblesDeliveredUpToWatermark(String pageId, String senderPsid, long watermarkEpochMs) {
        if (watermarkEpochMs <= 0L) {
            return 0;
        }
        String normPage = (pageId != null && !pageId.isBlank()) ? pageId.strip() : "-";
        String normSender = (senderPsid != null && !senderPsid.isBlank()) ? senderPsid.strip() : "-";
        int storeUpdated = store.markDeliveredUpToWatermark(normPage, normSender, watermarkEpochMs);
        String prefix = normPage + "|" + normSender + "|";
        int cacheUpdated = 0;
        for (Map.Entry<String, List<TurnRecord>> entry : CACHE.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) {
                continue;
            }
            List<TurnRecord> l = entry.getValue();
            synchronized (l) {
                for (int idx = 0; idx < l.size(); idx++) {
                    TurnRecord t = l.get(idx);
                    if (!"assistant".equals(t.role())) {
                        continue;
                    }
                    boolean changed = false;
                    List<BubbleRecord> updated = new ArrayList<>(t.bubbles().size());
                    for (BubbleRecord b : t.bubbles()) {
                        if (b.status() == DeliveryStatus.ACCEPTED_BY_CHANNEL) {
                            updated.add(new BubbleRecord(
                                    b.order(),
                                    b.text(),
                                    DeliveryStatus.DELIVERED,
                                    b.externalMid(),
                                    Math.max(1, b.attemptCount()),
                                    null,
                                    "DELIVERY_WATERMARK:" + watermarkEpochMs
                            ));
                            changed = true;
                            cacheUpdated++;
                        } else {
                            updated.add(b);
                        }
                    }
                    if (changed) {
                        l.set(idx, new TurnRecord(t.turnId(), t.role(), List.copyOf(updated)));
                    }
                }
            }
        }
        return Math.max(storeUpdated, cacheUpdated);
    }

    /**
     * Đánh dấu các bong bóng chưa kịp gửi ra Messenger (có {@code order >= cancelFromOrder}) ở lượt assistant mới nhất
     * thành {@link DeliveryStatus#CANCELED} khi khách nhắn chen ngang giữa khoảng nghỉ {@code bubbleDelayMs}.
     */
    public static void markLatestAssistantBubblesCanceledFrom(String key, int cancelFromOrder) {
        if (key == null || cancelFromOrder < 0) {
            return;
        }
        String targetTurnId = null;
        List<TurnRecord> l = cached(key);
        synchronized (l) {
            for (int idx = l.size() - 1; idx >= 0; idx--) {
                TurnRecord t = l.get(idx);
                if ("assistant".equals(t.role())) {
                    targetTurnId = t.turnId();
                    List<BubbleRecord> updated = new ArrayList<>(t.bubbles().size());
                    for (BubbleRecord b : t.bubbles()) {
                        updated.add(b.order() >= cancelFromOrder
                                ? new BubbleRecord(b.order(), b.text(), DeliveryStatus.CANCELED,
                                        b.externalMid(), b.attemptCount(), null, b.failureReason())
                                : b);
                    }
                    l.set(idx, new TurnRecord(t.turnId(), t.role(), List.copyOf(updated)));
                    break;
                }
            }
        }
        if (targetTurnId != null) {
            try {
                store.markAssistantBubblesCanceledFrom(key, targetTurnId, cancelFromOrder);
            } catch (Exception ignored) {
                // Non-blocking fallback
            }
        }
    }

    public static List<TurnRecord> snapshot(String key) {
        List<TurnRecord> l = cached(key);
        synchronized (l) {
            return List.copyOf(l);
        }
    }

    private static List<TurnRecord> cached(String key) {
        return CACHE.computeIfAbsent(key, k -> new ArrayList<>(store.load(k)));
    }

    private static boolean sameEvent(TurnRecord a, TurnRecord b) {
        return a.turnId() != null && a.turnId().equals(b.turnId()) && a.role().equals(b.role());
    }

    private static void append(String key, TurnRecord rec) {
        List<TurnRecord> l = cached(key);
        synchronized (l) {
            if (l.stream().anyMatch(t -> sameEvent(t, rec))) {
                return; // idempotent: webhook retry / cùng một lượt đến nhiều lần
            }
            l.add(rec);
            while (l.size() > MAX_TURNS_PER_SESSION) {
                l.remove(0);
            }
        }
        store.append(key, rec);
    }

    private static String norm(String s) {
        return s == null ? "" : s.replace("…", "").replaceAll("\\s+", " ").strip();
    }

    /** Transcript đầy đủ từ ledger: user + assistant (chỉ bong bóng khách đã thấy), đúng thứ tự thực tế. */
    public static List<FanpageBotRequest.ConversationTurn> transcript(String key) {
        List<FanpageBotRequest.ConversationTurn> out = new ArrayList<>();
        for (TurnRecord t : snapshot(key)) {
            String text = t.joinedText();
            if (!text.isBlank()) {
                out.add(new FanpageBotRequest.ConversationTurn(t.role(), text));
            }
        }
        return out;
    }

    /**
     * Hợp nhất lịch sử client với ledger Backend (nguồn chuẩn):
     * <ul>
     *   <li>client rỗng: khôi phục đủ user + assistant theo đúng thứ tự;</li>
     *   <li>client không rỗng: nếu mọi lượt client khớp (khớp tiền tố cho assistant bị cắt/thiếu bong bóng) với một
     *       dãy con có thứ tự của ledger thì dùng bản ledger đầy đủ; lượt lạ (không có trong ledger) giữ nguyên.
     *       Không bao giờ dùng bản cắt cụt của client ghi đè bản đầy đủ và không bịa nội dung.</li>
     * </ul>
     */
    public static List<FanpageBotRequest.ConversationTurn> reconcile(String key, List<FanpageBotRequest.ConversationTurn> clientHistory) {
        List<FanpageBotRequest.ConversationTurn> full = transcript(key);
        if (full.isEmpty()) {
            return clientHistory;
        }
        if (clientHistory == null || clientHistory.isEmpty()) {
            return tail(full);
        }
        // (1) Không bao giờ bỏ/đổi thứ tự lượt của client; chỉ vá assistant bị cắt/thiếu bong bóng bằng bản đầy đủ đã lưu
        //     (bản client phải là TIỀN TỐ của bản ledger). Lượt lạ giữ nguyên, không bịa nội dung.
        List<FanpageBotRequest.ConversationTurn> out = new ArrayList<>(clientHistory);
        List<Integer> ledgerAssistantIdx = new ArrayList<>();
        for (int k = 0; k < full.size(); k++) {
            if ("assistant".equalsIgnoreCase(full.get(k).role())) {
                ledgerAssistantIdx.add(k);
            }
        }
        int cursor = ledgerAssistantIdx.size() - 1;
        int lastAlignedLedgerIdx = -1;
        int lastAlignedClientIdx = -1;
        for (int ci = out.size() - 1; ci >= 0 && cursor >= 0; ci--) {
            FanpageBotRequest.ConversationTurn ct = out.get(ci);
            if (ct == null || !"assistant".equalsIgnoreCase(ct.role())) {
                continue;
            }
            String cn = norm(ct.content());
            for (int a = cursor; a >= 0; a--) {
                int lk = ledgerAssistantIdx.get(a);
                String ln = norm(full.get(lk).content());
                if (!cn.isEmpty() && ln.startsWith(cn)) {
                    if (ln.length() > cn.length()) {
                        out.set(ci, new FanpageBotRequest.ConversationTurn(ct.role(), full.get(lk).content()));
                    }
                    if (lastAlignedLedgerIdx < 0) {
                        lastAlignedLedgerIdx = lk;
                        lastAlignedClientIdx = ci;
                    }
                    cursor = a - 1;
                    break;
                }
            }
        }
        // (2) Client thiếu các lượt MỚI NHẤT mà ledger đã có: bổ sung phần đuôi ledger (đúng thứ tự, không bịa).
        if (lastAlignedLedgerIdx >= 0 && lastAlignedClientIdx == out.size() - 1 && lastAlignedLedgerIdx < full.size() - 1) {
            out.addAll(full.subList(lastAlignedLedgerIdx + 1, full.size()));
        }
        return out;
    }

    private static List<FanpageBotRequest.ConversationTurn> tail(List<FanpageBotRequest.ConversationTurn> full) {
        return full.size() <= MAX_RECONCILED_TURNS
                ? full
                : new ArrayList<>(full.subList(full.size() - MAX_RECONCILED_TURNS, full.size()));
    }
}
