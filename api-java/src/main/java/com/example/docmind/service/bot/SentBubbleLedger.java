package com.example.docmind.service.bot;

import com.example.docmind.dto.bot.FanpageBotRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sổ cái (ledger) Backend lưu ĐỦ từng bong bóng thực sự đã gửi cho khách, theo thứ tự hiển thị và trạng thái giao nhận.
 *
 * <p>v3.8: nguồn lưu chuẩn là {@link Store} bền vững (PostgreSQL qua {@link JdbcSentBubbleStore}); {@code CACHE} chỉ là
 * bộ nhớ đệm đọc-qua (read-through) nên restart tiến trình KHÔNG làm mất lịch sử. Khi chưa cài store bền vững
 * (unit test thuần), dùng {@link InMemoryStore}.
 */
public final class SentBubbleLedger {

    /** SENT/DELIVERED: khách đã thấy. CANCELED (bị lượt đồng thời huỷ) / FAILED: khách KHÔNG thấy bong bóng này. */
    public enum DeliveryStatus { SENT, DELIVERED, CANCELED, FAILED }

    public record BubbleRecord(int order, String text, DeliveryStatus status) {
        public boolean visibleToCustomer() {
            return status == DeliveryStatus.SENT || status == DeliveryStatus.DELIVERED;
        }
    }

    public record TurnRecord(String turnId, String role, List<BubbleRecord> bubbles) {
        /** Chỉ nối các bong bóng khách ĐÃ thấy. */
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

    /** Kho lưu trữ bền vững; {@code append} phải idempotent theo (key, turnId, role, order). */
    public interface Store {
        void append(String key, TurnRecord rec);

        List<TurnRecord> load(String key);

        void reset(String key);

        default void resetAll() {
        }

        default void markAssistantBubblesCanceledFrom(String key, String turnId, int cancelFromOrder) {
        }
    }

    public static final class InMemoryStore implements Store {
        private final Map<String, List<TurnRecord>> data = new ConcurrentHashMap<>();

        @Override
        public synchronized void append(String key, TurnRecord rec) {
            List<TurnRecord> l = data.computeIfAbsent(key, k -> new ArrayList<>());
            if (l.stream().noneMatch(t -> sameEvent(t, rec))) {
                l.add(rec);
            }
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
                                ? new BubbleRecord(b.order(), b.text(), DeliveryStatus.CANCELED)
                                : b);
                    }
                    l.set(idx, new TurnRecord(t.turnId(), t.role(), List.copyOf(updated)));
                    break;
                }
            }
        }
    }

    private static final int MAX_TURNS_PER_SESSION = 60;
    private static final int MAX_RECONCILED_TURNS = 24;
    private static final int MAX_MID_CACHE_SIZE = 2000;
    private static final long MID_CACHE_TTL_MS = 24L * 60L * 60L * 1000L;

    private record CachedMidEntry(String text, String role, long expiresAtMs) {
    }

    private static final Map<String, List<TurnRecord>> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, CachedMidEntry> MID_CACHE = new ConcurrentHashMap<>();
    private static volatile Store store = new InMemoryStore();

    private SentBubbleLedger() {
    }

    /** Cài kho bền vững (gọi từ {@link JdbcSentBubbleStore} khi khởi động). */
    public static void useStore(Store s) {
        store = s != null ? s : new InMemoryStore();
        CACHE.clear();
        MID_CACHE.clear();
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

    /**
     * Lưu ánh xạ bền vững {@code (pageId, senderPsid, messageId) -> messageText} vào Bounded Cache có TTL
     * và xuống {@link Store} bền vững (PostgreSQL {@code sent_bubble_ledger} / {@link InMemoryStore}).
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
        try {
            store.append(midLedgerKey(pageId, senderPsid),
                    new TurnRecord(cleanMid, cleanRole,
                            List.of(new BubbleRecord(0, cleanText, DeliveryStatus.DELIVERED))));
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
                                ? new BubbleRecord(b.order(), b.text(), DeliveryStatus.CANCELED)
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
