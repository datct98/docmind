-- Unit P0.4: Additive session opt-out & audited re-consent columns on conversation_sessions
-- Safe Additive Rollback compatible (DEFAULT FALSE / NULLABLE so R1 code revert requires zero DB rollback)
ALTER TABLE conversation_sessions
    ADD COLUMN IF NOT EXISTS opted_out BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS opted_out_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS re_opt_in_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS re_opt_in_source VARCHAR(64),
    ADD COLUMN IF NOT EXISTS re_opt_in_evidence TEXT;
