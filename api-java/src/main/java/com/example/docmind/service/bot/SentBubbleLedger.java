package com.example.docmind.service.bot;

import com.example.docmind.dto.bot.FanpageBotRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sổ cái (ledger) phía Backend lưu ĐỦ từng bong bóng thực sự đã gửi cho khách, theo thứ tự hiển thị và trạng thái gửi.
 * Đây là nguồn chuẩn cho {@code <CONVERSATION_HISTORY>}: khi payload {@code history} từ UI/script bị thiếu bong bóng 2, 3
 * hoặc bị cắt ngắn (kết thúc bằng '…'), {@link #reconcile} hợp nhất lại bằng nội dung đầy đủ đã lưu thay vì
 * xoá dấu vết cắt cụt trên chuỗi đã mất chữ.
 */
public final class SentBubbleLedger {

    public enum DeliveryStatus { DELIVERED, FAILED }

    public record BubbleRecord(int order, String text, DeliveryStatus status) {
    }

    public record TurnRecord(String turnId, String role, List<BubbleRecord> bubbles) {
        public String joinedText() {
            StringBuilder sb = new StringBuilder();
            for (BubbleRecord b : bubbles) {
                if (b.status() == DeliveryStatus.FAILED || b.text() == null || b.text().isBlank()) {
                    continue;
                }
                if (!sb.isEmpty()) {
                    sb.append("\n");
                }
                sb.append(b.text().strip());
            }
            return sb.toString();
        }
    }

    private static final int MAX_TURNS_PER_SESSION = 60;
    private static final Map<String, List<TurnRecord>> STORE = new ConcurrentHashMap<>();

    private SentBubbleLedger() {
    }

    public static String key(String pageId, String senderId, String conversationId) {
        return (pageId != null ? pageId : "-") + "|" + (senderId != null ? senderId : "-") + "|"
                + (conversationId != null ? conversationId : "-");
    }

    public static void clear() {
        STORE.clear();
    }

    public static void reset(String key) {
        STORE.remove(key);
    }

    public static void recordCustomerTurn(String key, String turnId, String text) {
        if (key == null || text == null || text.isBlank()) {
            return;
        }
        append(key, new TurnRecord(turnId, "user", List.of(new BubbleRecord(0, text.strip(), DeliveryStatus.DELIVERED))));
    }

    public static void recordAssistantTurn(String key, String turnId, List<String> bubbleTexts, DeliveryStatus status) {
        if (key == null || bubbleTexts == null || bubbleTexts.isEmpty()) {
            return;
        }
        List<BubbleRecord> recs = new ArrayList<>();
        int i = 0;
        for (String t : bubbleTexts) {
            if (t != null && !t.isBlank()) {
                recs.add(new BubbleRecord(i++, t.strip(), status));
            }
        }
        if (!recs.isEmpty()) {
            append(key, new TurnRecord(turnId, "assistant", List.copyOf(recs)));
        }
    }

    public static List<TurnRecord> snapshot(String key) {
        List<TurnRecord> l = STORE.get(key);
        if (l == null) {
            return List.of();
        }
        synchronized (l) {
            return List.copyOf(l);
        }
    }

    private static void append(String key, TurnRecord rec) {
        List<TurnRecord> l = STORE.computeIfAbsent(key, k -> new ArrayList<>());
        synchronized (l) {
            l.add(rec);
            while (l.size() > MAX_TURNS_PER_SESSION) {
                l.remove(0);
            }
        }
    }

    private static String norm(String s) {
        return s == null ? "" : s.replace("…", "").replaceAll("\\s+", " ").strip();
    }

    /**
     * Hợp nhất lịch sử từ client với sổ cái Backend: lượt assistant của client nào bị cắt ngắn / thiếu bong bóng so với
     * bản đã gửi thật sẽ được thay bằng bản đầy đủ. Lượt không khớp giữ nguyên (không bịa nội dung).
     */
    public static List<FanpageBotRequest.ConversationTurn> reconcile(String key, List<FanpageBotRequest.ConversationTurn> clientHistory) {
        if (clientHistory == null || clientHistory.isEmpty()) {
            return clientHistory;
        }
        List<TurnRecord> ledgerAssistant = new ArrayList<>();
        for (TurnRecord t : snapshot(key)) {
            if ("assistant".equals(t.role()) && !t.joinedText().isBlank()) {
                ledgerAssistant.add(t);
            }
        }
        if (ledgerAssistant.isEmpty()) {
            return clientHistory;
        }
        List<FanpageBotRequest.ConversationTurn> out = new ArrayList<>(clientHistory);
        int li = ledgerAssistant.size() - 1;
        for (int ci = out.size() - 1; ci >= 0 && li >= 0; ci--) {
            FanpageBotRequest.ConversationTurn ct = out.get(ci);
            if (ct == null || !"assistant".equalsIgnoreCase(ct.role())) {
                continue;
            }
            String clientNorm = norm(ct.content());
            // Tìm bản ghi ledger khớp (client là tiền tố/cắt ngắn của bản đầy đủ) bắt đầu từ li đi lùi
            for (int k = li; k >= 0; k--) {
                String full = ledgerAssistant.get(k).joinedText();
                String fullNorm = norm(full);
                if (!clientNorm.isEmpty() && fullNorm.startsWith(clientNorm)) {
                    if (fullNorm.length() > clientNorm.length()) {
                        out.set(ci, new FanpageBotRequest.ConversationTurn(ct.role(), full));
                    }
                    li = k - 1;
                    break;
                }
            }
        }
        return out;
    }
}
