-- ============================================================
-- DocMind api-java — Flyway V19: Durable SentBubbleLedger
--
-- Lưu bền vững từng bong bóng khách/bot THỰC SỰ đã gửi để khôi phục <CONVERSATION_HISTORY>
-- đầy đủ sau restart hoặc khi payload history của client rỗng/cắt cụt.
-- Idempotent theo (ledger_key, turn_id, role, bubble_order) để chống ghi trùng khi webhook retry.
-- ============================================================
CREATE TABLE IF NOT EXISTS sent_bubble_ledger (
    id              BIGSERIAL PRIMARY KEY,
    ledger_key      TEXT        NOT NULL,
    turn_id         TEXT        NOT NULL,
    role            VARCHAR(16) NOT NULL CHECK (role IN ('user', 'assistant')),
    bubble_order    INT         NOT NULL,
    bubble_text     TEXT        NOT NULL,
    delivery_status VARCHAR(16) NOT NULL CHECK (delivery_status IN ('SENT', 'DELIVERED', 'CANCELED', 'FAILED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_sent_bubble_ledger_event UNIQUE (ledger_key, turn_id, role, bubble_order)
);

CREATE INDEX IF NOT EXISTS idx_sent_bubble_ledger_key_id
    ON sent_bubble_ledger (ledger_key, id);
