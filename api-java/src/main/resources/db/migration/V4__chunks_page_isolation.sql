-- ============================================================
-- DocMind api-java — Flyway V4: Strict Page Isolation on Chunks
--
-- Đồng bộ page_id trực tiếp xuống bảng chunks (embedding + tsvector)
-- để đảm bảo khi chuyển đổi Page cho tài liệu, toàn bộ vector embeddings
-- và chỉ mục full-text được cô lập tuyệt đối ở cấp Database (Zero Cross-Page Leakage).
-- ============================================================

ALTER TABLE chunks
    ADD COLUMN IF NOT EXISTS page_id UUID REFERENCES knowledge_pages (id) ON DELETE SET NULL;

-- Backfill dữ liệu page_id hiện có từ bảng documents sang chunks
UPDATE chunks c
SET page_id = d.page_id
FROM documents d
WHERE c.document_id = d.id
  AND c.page_id IS DISTINCT FROM d.page_id;

-- Composite index phục vụ truy vấn hybrid search theo page_id + document_id cực nhanh
CREATE INDEX IF NOT EXISTS idx_chunks_page_id ON chunks (page_id);
CREATE INDEX IF NOT EXISTS idx_chunks_page_doc ON chunks (page_id, document_id);
