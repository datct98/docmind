-- ============================================================
-- DocMind api-java — Flyway V11: Harness² Recursive Library & Optimization Runs
--
-- Tích hợp cơ chế Harness² của Google Research:
-- 1. Phân tách dimension (INV vs DOM) trong learned_rules.
-- 2. Cấu trúc Experience Library 3 phần: cue_signal, generalized_rule (lesson), contrastive_evidence.
-- 3. Cờ always_on & hỗ trợ Always-On Token Budget Cap.
-- 4. Bảng harness_optimization_runs lưu vết Propose–Probe–Anonymized Select.
-- ============================================================

-- 1. Bổ sung các cột Harness² vào learned_rules
ALTER TABLE learned_rules
    ADD COLUMN IF NOT EXISTS dimension VARCHAR(10) NOT NULL DEFAULT 'DOM',
    ADD COLUMN IF NOT EXISTS cue_signal TEXT,
    ADD COLUMN IF NOT EXISTS contrastive_evidence TEXT,
    ADD COLUMN IF NOT EXISTS always_on BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_learned_rules_dimension_active
    ON learned_rules (dimension, active);

CREATE INDEX IF NOT EXISTS idx_learned_rules_always_on
    ON learned_rules (always_on, active, confidence_score DESC);

-- 2. Tạo bảng harness_optimization_runs để lưu vết chu trình tự tối ưu tương phản
CREATE TABLE IF NOT EXISTS harness_optimization_runs (
    id                  VARCHAR(36) PRIMARY KEY,
    page_id             VARCHAR(64),
    customer_message    TEXT,
    base_response       TEXT,
    candidate_a         TEXT,
    candidate_b         TEXT,
    winner_candidate    VARCHAR(32),
    verdict             VARCHAR(32),
    score_before        DOUBLE PRECISION,
    score_after         DOUBLE PRECISION,
    delta_score         DOUBLE PRECISION,
    rationale           TEXT,
    created_at          TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_harness_optimization_runs_page_created
    ON harness_optimization_runs (page_id, created_at DESC);
