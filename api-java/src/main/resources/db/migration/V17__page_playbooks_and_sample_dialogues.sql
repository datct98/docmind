-- =========================================================================
-- V17: Page-Scoped Playbook Metadata & Sample Dialogues (Few-Shot Library)
-- Supports YAML, Markdown, and JSON Playbook Upload with Immediate Activation
-- =========================================================================

ALTER TABLE learned_rules
    ADD COLUMN IF NOT EXISTS rule_code VARCHAR(100),
    ADD COLUMN IF NOT EXISTS title VARCHAR(255),
    ADD COLUMN IF NOT EXISTS intent VARCHAR(100),
    ADD COLUMN IF NOT EXISTS stage VARCHAR(100),
    ADD COLUMN IF NOT EXISTS priority INTEGER DEFAULT 80,
    ADD COLUMN IF NOT EXISTS do_actions TEXT,
    ADD COLUMN IF NOT EXISTS dont_actions TEXT,
    ADD COLUMN IF NOT EXISTS grounding_policy VARCHAR(100) DEFAULT 'must_use_kb_document_context',
    ADD COLUMN IF NOT EXISTS handoff_policy VARCHAR(100) DEFAULT 'none',
    ADD COLUMN IF NOT EXISTS source_format VARCHAR(32) DEFAULT 'MANUAL';

CREATE INDEX IF NOT EXISTS idx_learned_rules_page_intent
    ON learned_rules (page_id, active, intent, priority DESC);

CREATE INDEX IF NOT EXISTS idx_learned_rules_rule_code
    ON learned_rules (rule_code, page_id);

CREATE TABLE IF NOT EXISTS sample_dialogues (
    id UUID PRIMARY KEY,
    dialogue_code VARCHAR(100) NOT NULL,
    page_id UUID,
    domain VARCHAR(100) DEFAULT 'spa_wellness',
    intent VARCHAR(100),
    stage VARCHAR(100),
    customer_type VARCHAR(128),
    tone VARCHAR(100),
    turns_json TEXT NOT NULL,
    user_summary TEXT NOT NULL,
    expected_bot_answer TEXT NOT NULL,
    why_good TEXT,
    do_not_copy_literally BOOLEAN NOT NULL DEFAULT TRUE,
    tags VARCHAR(512),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    review_status VARCHAR(32) NOT NULL DEFAULT 'APPROVED',
    quarantined_reason TEXT,
    security_audit_notes TEXT,
    source_format VARCHAR(32) NOT NULL DEFAULT 'YAML',
    embedding vector(384),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_sample_dialogues_page_active
    ON sample_dialogues (page_id, active, review_status);

CREATE INDEX IF NOT EXISTS idx_sample_dialogues_code_page
    ON sample_dialogues (dialogue_code, page_id);
