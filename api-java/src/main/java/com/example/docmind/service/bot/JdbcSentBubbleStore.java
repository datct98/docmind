package com.example.docmind.service.bot;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lưu bền vững {@link SentBubbleLedger} trong bảng {@code sent_bubble_ledger} (Flyway V19). */
@Component
public class JdbcSentBubbleStore implements SentBubbleLedger.Store {

    private static final Logger log = LoggerFactory.getLogger(JdbcSentBubbleStore.class);

    private final JdbcTemplate jdbc;

    public JdbcSentBubbleStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void install() {
        SentBubbleLedger.useStore(this);
        log.info("[SentBubbleLedger] durable JDBC store installed");
    }

    @Override
    public void append(String key, SentBubbleLedger.TurnRecord rec) {
        try {
            for (SentBubbleLedger.BubbleRecord b : rec.bubbles()) {
                jdbc.update("INSERT INTO sent_bubble_ledger (ledger_key, turn_id, role, bubble_order, bubble_text, delivery_status) "
                                + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT ON CONSTRAINT uq_sent_bubble_ledger_event DO NOTHING",
                        key, rec.turnId(), rec.role(), b.order(), b.text(), b.status().name());
            }
        } catch (Exception e) {
            log.warn("[SentBubbleLedger] persist failed key={} turn={}: {}", key, rec.turnId(), e.getMessage());
        }
    }

    @Override
    public List<SentBubbleLedger.TurnRecord> load(String key) {
        Map<String, List<SentBubbleLedger.BubbleRecord>> byTurn = new LinkedHashMap<>();
        Map<String, String> roles = new LinkedHashMap<>();
        try {
            jdbc.query("SELECT turn_id, role, bubble_order, bubble_text, delivery_status FROM sent_bubble_ledger "
                            + "WHERE ledger_key = ? ORDER BY id",
                    rs -> {
                        String id = rs.getString("turn_id") + "\u0000" + rs.getString("role");
                        roles.putIfAbsent(id, rs.getString("role"));
                        byTurn.computeIfAbsent(id, k -> new ArrayList<>()).add(new SentBubbleLedger.BubbleRecord(
                                rs.getInt("bubble_order"), rs.getString("bubble_text"),
                                SentBubbleLedger.DeliveryStatus.valueOf(rs.getString("delivery_status"))));
                    }, key);
        } catch (Exception e) {
            log.warn("[SentBubbleLedger] load failed key={}: {}", key, e.getMessage());
            return List.of();
        }
        List<SentBubbleLedger.TurnRecord> out = new ArrayList<>();
        byTurn.forEach((id, bubbles) -> out.add(new SentBubbleLedger.TurnRecord(
                id.substring(0, id.indexOf('\u0000')), roles.get(id), List.copyOf(bubbles))));
        return out;
    }

    @Override
    public void reset(String key) {
        try {
            jdbc.update("DELETE FROM sent_bubble_ledger WHERE ledger_key = ?", key);
        } catch (Exception e) {
            log.warn("[SentBubbleLedger] reset failed key={}: {}", key, e.getMessage());
        }
    }

    @Override
    public void resetAll() {
        jdbc.update("DELETE FROM sent_bubble_ledger");
    }
}
