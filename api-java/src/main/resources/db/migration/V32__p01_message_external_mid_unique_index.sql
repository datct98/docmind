-- Unit P0.1: Enforce DB-level atomic idempotency for conversation messages carrying a protocol event ID
CREATE UNIQUE INDEX IF NOT EXISTS uq_conv_messages_session_ext_mid
    ON conversation_messages (session_id, external_mid)
    WHERE external_mid IS NOT NULL;
