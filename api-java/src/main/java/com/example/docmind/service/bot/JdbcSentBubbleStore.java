package com.example.docmind.service.bot;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lưu bền vững {@link SentBubbleLedger} trong bảng {@code sent_bubble_ledger} (Flyway V19 + V42 P3.1). */
@Component
public class JdbcSentBubbleStore implements SentBubbleLedger.Store {

    private static final Logger log = LoggerFactory.getLogger(JdbcSentBubbleStore.class);

    private final JdbcTemplate jdbc;

    public JdbcSentBubbleStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void install() {
        ensureSchemaUpgraded();
        SentBubbleLedger.useStore(this);
        int recovered = recoverAllExpiredInFlightLeases(Instant.now());
        log.info("[SentBubbleLedger] durable JDBC store installed (recoveredExpiredInFlight={})", recovered);
    }

    /**
     * Quét định kỳ các bản ghi {@code IN_FLIGHT} đã quá hạn lease do tiến trình crash/timeout
     * ngay cả khi khách không nhắn thêm tin mới trên cùng {@code ledger_key}.
     */
    @Scheduled(fixedDelayString = "${docmind.bot.in-flight-recovery-interval-ms:30000}")
    public void sweepExpiredInFlightLeases() {
        try {
            int recovered = SentBubbleLedger.recoverAllExpiredInFlightLeases(Instant.now());
            if (recovered > 0) {
                log.warn("[SentBubbleLedger] Swept {} expired IN_FLIGHT bubble(s) to UNKNOWN", recovered);
            }
        } catch (Exception e) {
            log.debug("[SentBubbleLedger] Periodic IN_FLIGHT lease sweep skipped: {}", e.getMessage());
        }
    }

    /**
     * Đảm bảo bảng {@code sent_bubble_ledger} có đầy đủ cột và CHECK constraint của P3.1 (V42)
     * ngay cả trong các bài test chỉ nạp {@code V19__sent_bubble_ledger.sql}.
     */
    public void ensureSchemaUpgraded() {
        try {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS sent_bubble_ledger (
                        id              BIGSERIAL PRIMARY KEY,
                        ledger_key      TEXT        NOT NULL,
                        turn_id         TEXT        NOT NULL,
                        role            VARCHAR(16) NOT NULL CHECK (role IN ('user', 'assistant')),
                        bubble_order    INT         NOT NULL,
                        bubble_text     TEXT        NOT NULL,
                        delivery_status VARCHAR(32) NOT NULL,
                        external_mid    TEXT,
                        attempt_count   INT         NOT NULL DEFAULT 0,
                        lease_until     TIMESTAMPTZ,
                        failure_reason  TEXT,
                        created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
                        updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
                        CONSTRAINT uq_sent_bubble_ledger_event UNIQUE (ledger_key, turn_id, role, bubble_order)
                    )
                    """);
            jdbc.execute("ALTER TABLE sent_bubble_ledger ALTER COLUMN delivery_status TYPE VARCHAR(32)");
            jdbc.execute("""
                    ALTER TABLE sent_bubble_ledger
                        ADD COLUMN IF NOT EXISTS external_mid TEXT,
                        ADD COLUMN IF NOT EXISTS attempt_count INT NOT NULL DEFAULT 0,
                        ADD COLUMN IF NOT EXISTS lease_until TIMESTAMPTZ,
                        ADD COLUMN IF NOT EXISTS failure_reason TEXT,
                        ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    """);
            jdbc.execute("""
                    DO $$
                    DECLARE
                        r RECORD;
                    BEGIN
                        FOR r IN
                            SELECT c.conname
                            FROM pg_constraint c
                            JOIN pg_class t ON c.conrelid = t.oid
                            WHERE t.relname = 'sent_bubble_ledger'
                              AND c.contype = 'c'
                              AND pg_get_constraintdef(c.oid) ILIKE '%delivery_status%'
                        LOOP
                            EXECUTE format('ALTER TABLE sent_bubble_ledger DROP CONSTRAINT IF EXISTS %I', r.conname);
                        END LOOP;
                    END $$
                    """);
            jdbc.execute("""
                    ALTER TABLE sent_bubble_ledger
                        ADD CONSTRAINT chk_sent_bubble_ledger_delivery_status
                        CHECK (delivery_status IN (
                            'PENDING',
                            'IN_FLIGHT',
                            'ACCEPTED_BY_CHANNEL',
                            'DELIVERED',
                            'FAILED',
                            'UNKNOWN',
                            'SUPPRESSED_BY_FENCE',
                            'SENT',
                            'CANCELED'
                        ))
                    """);
        } catch (Exception e) {
            log.debug("[SentBubbleLedger] schema upgrade skipped or already applied: {}", e.getMessage());
        }
    }

    @Override
    public void append(String key, SentBubbleLedger.TurnRecord rec) {
        try {
            for (SentBubbleLedger.BubbleRecord b : rec.bubbles()) {
                Timestamp leaseTs = b.leaseUntil() != null ? Timestamp.from(b.leaseUntil()) : null;
                jdbc.update("INSERT INTO sent_bubble_ledger "
                                + "(ledger_key, turn_id, role, bubble_order, bubble_text, delivery_status, external_mid, attempt_count, lease_until, failure_reason, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now()) "
                                + "ON CONFLICT ON CONSTRAINT uq_sent_bubble_ledger_event DO NOTHING",
                        key, rec.turnId(), rec.role(), b.order(), b.text(), b.status().name(),
                        b.externalMid(), b.attemptCount(), leaseTs, b.failureReason());
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
            jdbc.query("SELECT turn_id, role, bubble_order, bubble_text, delivery_status, "
                            + "external_mid, attempt_count, lease_until, failure_reason "
                            + "FROM sent_bubble_ledger WHERE ledger_key = ? ORDER BY id",
                    rs -> {
                        String id = rs.getString("turn_id") + "\u0000" + rs.getString("role");
                        roles.putIfAbsent(id, rs.getString("role"));
                        Timestamp leaseTs = rs.getTimestamp("lease_until");
                        byTurn.computeIfAbsent(id, k -> new ArrayList<>()).add(new SentBubbleLedger.BubbleRecord(
                                rs.getInt("bubble_order"),
                                rs.getString("bubble_text"),
                                SentBubbleLedger.DeliveryStatus.valueOf(rs.getString("delivery_status")),
                                rs.getString("external_mid"),
                                rs.getInt("attempt_count"),
                                leaseTs != null ? leaseTs.toInstant() : null,
                                rs.getString("failure_reason")
                        ));
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
        try {
            jdbc.update("DELETE FROM sent_bubble_ledger");
        } catch (Exception e) {
            log.warn("[SentBubbleLedger] resetAll failed: {}", e.getMessage());
        }
    }

    @Override
    public void markAssistantBubblesCanceledFrom(String key, String turnId, int cancelFromOrder) {
        try {
            jdbc.update("UPDATE sent_bubble_ledger SET delivery_status = ?, lease_until = NULL, updated_at = now() "
                            + "WHERE ledger_key = ? AND turn_id = ? AND role = 'assistant' AND bubble_order >= ?",
                    SentBubbleLedger.DeliveryStatus.CANCELED.name(), key, turnId, cancelFromOrder);
        } catch (Exception e) {
            log.warn("[SentBubbleLedger] markAssistantBubblesCanceledFrom failed key={} turn={}: {}", key, turnId, e.getMessage());
        }
    }

    @Override
    public boolean tryAcquireDispatchLease(
            String key,
            String turnId,
            int bubbleOrder,
            Instant leaseUntil,
            Instant now
    ) {
        try {
            Timestamp leaseTs = leaseUntil != null ? Timestamp.from(leaseUntil) : null;
            int rows = jdbc.update("UPDATE sent_bubble_ledger "
                            + "SET delivery_status = 'IN_FLIGHT', "
                            + "    attempt_count = attempt_count + 1, "
                            + "    lease_until = ?, "
                            + "    updated_at = now() "
                            + "WHERE ledger_key = ? AND turn_id = ? AND role = 'assistant' AND bubble_order = ? "
                            + "  AND attempt_count = 0 "
                            + "  AND (external_mid IS NULL OR external_mid = '') "
                            + "  AND failure_reason IS NULL "
                            + "  AND delivery_status IN ('PENDING', 'SENT', 'DELIVERED')",
                    leaseTs, key, turnId, bubbleOrder);
            return rows > 0;
        } catch (Exception e) {
            log.warn("[SentBubbleLedger] tryAcquireDispatchLease failed key={} turn={} order={}: {}",
                    key, turnId, bubbleOrder, e.getMessage());
            return false;
        }
    }

    @Override
    public void updateBubbleState(
            String key,
            String turnId,
            int bubbleOrder,
            SentBubbleLedger.DeliveryStatus status,
            String externalMid,
            int attemptCount,
            Instant leaseUntil,
            String failureReason
    ) {
        try {
            Timestamp leaseTs = leaseUntil != null ? Timestamp.from(leaseUntil) : null;
            String targetName = status.name();
            jdbc.update("UPDATE sent_bubble_ledger "
                            + "SET delivery_status = CASE "
                            + "        WHEN delivery_status = 'DELIVERED' AND (external_mid IS NOT NULL OR failure_reason LIKE 'DELIVERY_%') THEN 'DELIVERED' "
                            + "        WHEN delivery_status = 'ACCEPTED_BY_CHANNEL' AND ? NOT IN ('DELIVERED') THEN 'ACCEPTED_BY_CHANNEL' "
                            + "        ELSE ? "
                            + "    END, "
                            + "    external_mid = COALESCE(NULLIF(?, ''), external_mid), "
                            + "    attempt_count = GREATEST(attempt_count, ?), "
                            + "    lease_until = ?, "
                            + "    failure_reason = COALESCE(?, failure_reason), "
                            + "    updated_at = now() "
                            + "WHERE ledger_key = ? AND turn_id = ? AND role = 'assistant' AND bubble_order = ?",
                    targetName, targetName, externalMid, attemptCount, leaseTs, failureReason,
                    key, turnId, bubbleOrder);
        } catch (Exception e) {
            log.warn("[SentBubbleLedger] updateBubbleState failed key={} turn={} order={}: {}",
                    key, turnId, bubbleOrder, e.getMessage());
        }
    }

    @Override
    public int recoverExpiredInFlightLeases(String key, Instant now) {
        try {
            Timestamp nowTs = Timestamp.from(now != null ? now : Instant.now());
            return jdbc.update("UPDATE sent_bubble_ledger "
                            + "SET delivery_status = 'UNKNOWN', "
                            + "    lease_until = NULL, "
                            + "    failure_reason = COALESCE(failure_reason, 'IN_FLIGHT_LEASE_EXPIRED_CRASH_RECOVERY'), "
                            + "    updated_at = now() "
                            + "WHERE ledger_key = ? AND role = 'assistant' AND delivery_status = 'IN_FLIGHT' "
                            + "  AND attempt_count >= 1 "
                            + "  AND (lease_until IS NULL OR lease_until <= ?)",
                    key, nowTs);
        } catch (Exception e) {
            log.warn("[SentBubbleLedger] recoverExpiredInFlightLeases failed key={}: {}", key, e.getMessage());
            return 0;
        }
    }

    @Override
    public int recoverAllExpiredInFlightLeases(Instant now) {
        try {
            Timestamp nowTs = Timestamp.from(now != null ? now : Instant.now());
            return jdbc.update("UPDATE sent_bubble_ledger "
                            + "SET delivery_status = 'UNKNOWN', "
                            + "    lease_until = NULL, "
                            + "    failure_reason = COALESCE(failure_reason, 'IN_FLIGHT_LEASE_EXPIRED_CRASH_RECOVERY'), "
                            + "    updated_at = now() "
                            + "WHERE role = 'assistant' AND delivery_status = 'IN_FLIGHT' "
                            + "  AND attempt_count >= 1 "
                            + "  AND (lease_until IS NULL OR lease_until <= ?)",
                    nowTs);
        } catch (Exception e) {
            log.warn("[SentBubbleLedger] recoverAllExpiredInFlightLeases failed: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public int markDeliveredByExternalMid(String pageId, String senderPsid, String externalMid) {
        if (externalMid == null || externalMid.isBlank()) {
            return 0;
        }
        String cleanMid = externalMid.strip();
        String likePrefix = (pageId != null ? pageId.strip() : "-") + "|"
                + (senderPsid != null ? senderPsid.strip() : "-") + "|%";
        try {
            int updated = jdbc.update("UPDATE sent_bubble_ledger "
                            + "SET delivery_status = 'DELIVERED', "
                            + "    lease_until = NULL, "
                            + "    failure_reason = 'DELIVERY_RECEIPT', "
                            + "    updated_at = now() "
                            + "WHERE ledger_key LIKE ? AND role = 'assistant' AND external_mid = ? "
                            + "  AND delivery_status <> 'DELIVERED'",
                    likePrefix, cleanMid);
            if (updated == 0) {
                // Scenario 12: Delivery receipt arrived while IN_FLIGHT before HTTP response returned outMid
                updated = jdbc.update("UPDATE sent_bubble_ledger "
                                + "SET delivery_status = 'DELIVERED', "
                                + "    external_mid = ?, "
                                + "    lease_until = NULL, "
                                + "    failure_reason = 'DELIVERY_RECEIPT', "
                                + "    updated_at = now() "
                                + "WHERE id = ("
                                + "    SELECT id FROM sent_bubble_ledger "
                                + "    WHERE ledger_key LIKE ? AND role = 'assistant' "
                                + "      AND delivery_status = 'IN_FLIGHT' AND (external_mid IS NULL OR external_mid = '') "
                                + "    ORDER BY id DESC LIMIT 1"
                                + ")",
                        cleanMid, likePrefix);
            }
            return updated;
        } catch (Exception e) {
            log.warn("[SentBubbleLedger] markDeliveredByExternalMid failed mid={}: {}", cleanMid, e.getMessage());
            return 0;
        }
    }

    @Override
    public int markDeliveredUpToWatermark(String pageId, String senderPsid, long watermarkEpochMs) {
        String likePrefix = (pageId != null ? pageId.strip() : "-") + "|"
                + (senderPsid != null ? senderPsid.strip() : "-") + "|%";
        try {
            return jdbc.update("UPDATE sent_bubble_ledger "
                            + "SET delivery_status = 'DELIVERED', "
                            + "    lease_until = NULL, "
                            + "    failure_reason = ?, "
                            + "    updated_at = now() "
                            + "WHERE ledger_key LIKE ? AND role = 'assistant' "
                            + "  AND delivery_status = 'ACCEPTED_BY_CHANNEL'",
                    "DELIVERY_WATERMARK:" + watermarkEpochMs, likePrefix);
        } catch (Exception e) {
            log.warn("[SentBubbleLedger] markDeliveredUpToWatermark failed: {}", e.getMessage());
            return 0;
        }
    }
}
