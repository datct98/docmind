-- ============================================================================
-- Unit P2.4 (V35): CustomerBrainMemory JSONB Persistence (`aci_memory_state`)
-- and JPA `@Version` Optimistic Locking (`version`) on `conversation_sessions`.
-- ============================================================================

ALTER TABLE public.conversation_sessions
    ADD COLUMN IF NOT EXISTS aci_memory_state JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
