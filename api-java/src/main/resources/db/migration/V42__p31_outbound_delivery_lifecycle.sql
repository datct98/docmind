-- ============================================================
-- DocMind api-java — Flyway V42: P3.1 Outbound Delivery Lifecycle & Lease Recovery
--
-- Mở rộng bảng sent_bubble_ledger để quản lý trung thực vòng đời gửi tin:
-- PENDING -> IN_FLIGHT -> ACCEPTED_BY_CHANNEL -> DELIVERED
-- cùng các trạng thái FAILED, UNKNOWN, SUPPRESSED_BY_FENCE và cơ chế lease/attempt
-- phục vụ crash recovery chống gửi trùng.
-- ============================================================

ALTER TABLE sent_bubble_ledger
    ALTER COLUMN delivery_status TYPE VARCHAR(32);

ALTER TABLE sent_bubble_ledger
    ADD COLUMN IF NOT EXISTS external_mid TEXT,
    ADD COLUMN IF NOT EXISTS attempt_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS lease_until TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS failure_reason TEXT,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

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
END $$;

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
    ));

CREATE INDEX IF NOT EXISTS idx_sent_bubble_ledger_external_mid
    ON sent_bubble_ledger (external_mid)
    WHERE external_mid IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_sent_bubble_ledger_in_flight_lease
    ON sent_bubble_ledger (delivery_status, lease_until)
    WHERE delivery_status = 'IN_FLIGHT';
