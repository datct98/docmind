-- ============================================================
-- DocMind api-java — Flyway V2: Meta-Learning & Feedback Tables
--
-- Lưu trữ bộ quy tắc rút kinh nghiệm (AI Playbook) và log phản hồi
-- từ người dùng để tự cải thiện năng lực suy luận RAG.
-- ============================================================

CREATE TABLE IF NOT EXISTS learned_rules (
    id                  UUID PRIMARY KEY,
    category            VARCHAR(64) NOT NULL,               -- PRICING_CALCULATION | TABLE_LOOKUP | DATA_INTERPRETATION | TEMPORAL_VALIDITY | POLICY
    trigger_condition   TEXT NOT NULL,                      -- Điều kiện/ngữ cảnh kích hoạt quy tắc
    root_cause          TEXT NOT NULL,                      -- Nguyên nhân gốc rễ khiến AI trả lời sai
    generalized_rule    TEXT NOT NULL,                      -- Quy tắc tổng quát hóa (đã loại bỏ số/giá cụ thể)
    retrieval_keywords  TEXT[] NOT NULL DEFAULT '{}',       -- Từ khóa mở rộng truy vấn tài liệu
    embedding           vector(384),                        -- Vector embedding (MiniLM-L12-v2 dim 384)
    active              BOOLEAN NOT NULL DEFAULT true,       -- Trạng thái kích hoạt quy tắc
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_learned_rules_active ON learned_rules (active);
CREATE INDEX IF NOT EXISTS idx_learned_rules_category ON learned_rules (category);
CREATE INDEX IF NOT EXISTS idx_learned_rules_embedding ON learned_rules USING ivfflat (embedding vector_cosine_ops) WITH (lists = 50);

CREATE TABLE IF NOT EXISTS feedback_logs (
    id                  UUID PRIMARY KEY,
    conversation_id     TEXT,
    question            TEXT NOT NULL,
    retrieved_context   TEXT,
    faulty_answer       TEXT NOT NULL,
    user_correction     TEXT NOT NULL,
    extracted_rule_id   UUID REFERENCES learned_rules (id) ON DELETE SET NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_feedback_logs_created_at ON feedback_logs (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_feedback_logs_rule_id ON feedback_logs (extracted_rule_id);
CREATE INDEX IF NOT EXISTS idx_feedback_logs_conversation_id ON feedback_logs (conversation_id);
