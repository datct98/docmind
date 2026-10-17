-- ============================================================
-- DocMind api-java — Flyway V3: Knowledge Pages / Folders
--
-- Phân loại tài liệu tri thức và quy tắc AI Playbook theo từng Page
-- (mỗi Page đại diện cho 1 ngành nghề và đối tượng khách hàng riêng).
-- ============================================================

CREATE TABLE IF NOT EXISTS knowledge_pages (
    id              UUID PRIMARY KEY,
    name            TEXT NOT NULL,
    industry        TEXT NOT NULL DEFAULT '',
    target_audience TEXT NOT NULL DEFAULT '',
    description     TEXT NOT NULL DEFAULT '',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_knowledge_pages_created_at ON knowledge_pages (created_at DESC);

-- Gắn tài liệu vào Page/Thư mục (NULL = chưa phân loại / dùng chung)
ALTER TABLE documents
    ADD COLUMN IF NOT EXISTS page_id UUID REFERENCES knowledge_pages (id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_documents_page_id ON documents (page_id);

-- Gắn quy tắc AI Playbook vào Page/Thư mục (NULL = Quy tắc chung toàn hệ thống)
ALTER TABLE learned_rules
    ADD COLUMN IF NOT EXISTS page_id UUID REFERENCES knowledge_pages (id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS idx_learned_rules_page_id ON learned_rules (page_id);
