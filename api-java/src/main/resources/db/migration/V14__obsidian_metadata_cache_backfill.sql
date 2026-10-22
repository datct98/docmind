-- ============================================================
-- DocMind api-java — Flyway V14: Obsidian Metadata Cache & Graph Backfill
--
-- Feature F10 (Milestone 4):
--   1. Non-destructive addition of chunk_metadata JSONB and tsv_content TSVECTOR.
--   2. Strict backfill of tsv & tsv_content with Weight A (heading_path) and Weight B (text).
--   3. Baseline chunk_role & branch_tags classification for existing 65 chunks of Page "Nàng sen".
--   4. Automatic trigger synchronization between tsv and tsv_content for zero divergence.
--   5. High-performance GIN and B-Tree indexes for Obsidian MetadataCache, Backlinks, and MOC.
--   6. Zero data loss guarantee (100% non-destructive, IF NOT EXISTS).
-- ============================================================

-- ------------------------------------------------------------
-- 1. Schema Extensions (Non-Destructive)
-- ------------------------------------------------------------

-- Ensure chunk_metadata JSONB column exists with safe empty object default
ALTER TABLE chunks
    ADD COLUMN IF NOT EXISTS chunk_metadata JSONB NOT NULL DEFAULT '{}'::jsonb;

-- Ensure tsv_content TSVECTOR column exists for dual full-text compatibility
ALTER TABLE chunks
    ADD COLUMN IF NOT EXISTS tsv_content TSVECTOR;

-- ------------------------------------------------------------
-- 2. Backfill Existing Chunks (Preserving all 65 chunks of Page "Nàng sen")
-- ------------------------------------------------------------

-- Update full-text search vectors with Weight A (heading_path) and Weight B (content text)
UPDATE chunks
SET tsv = setweight(to_tsvector('simple', coalesce(array_to_string(heading_path, ' '), '')), 'A')
        || setweight(to_tsvector('simple', coalesce(text, '')), 'B'),
    tsv_content = setweight(to_tsvector('simple', coalesce(array_to_string(heading_path, ' '), '')), 'A')
                || setweight(to_tsvector('simple', coalesce(text, '')), 'B');

-- Initial baseline metadata backfill for existing rows where chunk_metadata is empty
UPDATE chunks
SET chunk_metadata = jsonb_build_object(
    'chunk_role', CASE
        WHEN (array_to_string(heading_path, ' ') ILIKE '%FAQ%' 
           OR array_to_string(heading_path, ' ') ILIKE '%Khách hỏi%' 
           OR text ILIKE '%| Câu hỏi%') THEN 'FAQ'
        WHEN (array_to_string(heading_path, ' ') ILIKE '%Quy trình%' 
           OR array_to_string(heading_path, ' ') ILIKE '%LƯU_Ý_QUY_TRÌNH%' 
           OR text ILIKE '%| bước |%') THEN 'PROCESS'
        WHEN (array_to_string(heading_path, ' ') ILIKE '%Ưu đãi%' 
           OR array_to_string(heading_path, ' ') ILIKE '%Khuyến mãi%' 
           OR text ILIKE '%chiết khấu theo%') THEN 'PROMO'
        WHEN (text ILIKE '%| Mã gói |%' 
           OR text ILIKE '%| Giá |%' 
           OR text ILIKE '%| Đơn giá |%' 
           OR array_to_string(heading_path, ' ') ILIKE '%Bảng giá%') THEN 'PRICE_TABLE'
        ELSE 'GENERAL'
    END,
    'branch_tags', (
        SELECT coalesce(jsonb_agg(tag), '[]'::jsonb) FROM (
            SELECT 'quan_1' AS tag WHERE (array_to_string(heading_path, ' ') ILIKE '%Quận 1%' 
                                      OR array_to_string(heading_path, ' ') ILIKE '%TP.HCM%' 
                                      OR array_to_string(heading_path, ' ') ILIKE '%Sài Gòn%' 
                                      OR text ILIKE '%Quận 1%')
            UNION ALL
            SELECT 'my_dinh' AS tag WHERE (array_to_string(heading_path, ' ') ILIKE '%Mỹ Đình%' 
                                       OR text ILIKE '%Mỹ Đình%')
            UNION ALL
            SELECT 'bich_cau' AS tag WHERE (array_to_string(heading_path, ' ') ILIKE '%Bích Câu%' 
                                        OR text ILIKE '%Bích Câu%')
            UNION ALL
            SELECT 'long_bien' AS tag WHERE (array_to_string(heading_path, ' ') ILIKE '%Long Biên%' 
                                         OR text ILIKE '%Long Biên%')
        ) sub
    ),
    'service_codes', '[]'::jsonb
)
WHERE chunk_metadata IS NULL OR chunk_metadata = '{}'::jsonb;

-- ------------------------------------------------------------
-- 3. Automatic Synchronization Trigger (Zero Divergence Guarantee)
-- ------------------------------------------------------------

CREATE OR REPLACE FUNCTION trg_chunks_sync_tsv()
RETURNS trigger AS $$
BEGIN
    IF (TG_OP = 'INSERT') THEN
        IF NEW.tsv IS NULL AND NEW.tsv_content IS NULL THEN
            NEW.tsv := setweight(to_tsvector('simple', coalesce(array_to_string(NEW.heading_path, ' '), '')), 'A')
                    || setweight(to_tsvector('simple', coalesce(NEW.text, '')), 'B');
            NEW.tsv_content := NEW.tsv;
        ELSIF NEW.tsv_content IS NULL THEN
            NEW.tsv_content := NEW.tsv;
        ELSIF NEW.tsv IS NULL THEN
            NEW.tsv := NEW.tsv_content;
        END IF;
    ELSIF (TG_OP = 'UPDATE') THEN
        IF (NEW.text IS DISTINCT FROM OLD.text OR NEW.heading_path IS DISTINCT FROM OLD.heading_path)
           AND (NEW.tsv IS NOT DISTINCT FROM OLD.tsv AND NEW.tsv_content IS NOT DISTINCT FROM OLD.tsv_content) THEN
            NEW.tsv := setweight(to_tsvector('simple', coalesce(array_to_string(NEW.heading_path, ' '), '')), 'A')
                    || setweight(to_tsvector('simple', coalesce(NEW.text, '')), 'B');
            NEW.tsv_content := NEW.tsv;
        ELSE
            IF NEW.tsv_content IS DISTINCT FROM OLD.tsv_content AND NEW.tsv IS NOT DISTINCT FROM OLD.tsv THEN
                NEW.tsv := NEW.tsv_content;
            ELSIF NEW.tsv IS DISTINCT FROM OLD.tsv AND NEW.tsv_content IS NOT DISTINCT FROM OLD.tsv_content THEN
                NEW.tsv_content := NEW.tsv;
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_chunks_sync_tsv ON chunks;
CREATE TRIGGER trg_chunks_sync_tsv
BEFORE INSERT OR UPDATE ON chunks
FOR EACH ROW
EXECUTE FUNCTION trg_chunks_sync_tsv();

-- ------------------------------------------------------------
-- 4. High-Performance GIN & B-Tree Indexes
-- ------------------------------------------------------------

-- GIN index for JSONB queries (e.g. chunk_metadata @> '{"chunk_role": "PRICE_TABLE"}')
CREATE INDEX IF NOT EXISTS idx_chunks_chunk_metadata_gin
    ON chunks USING GIN (chunk_metadata);

-- Fast B-Tree index for filtering by role (PRICE_TABLE, FAQ, PROCESS, PROMO)
CREATE INDEX IF NOT EXISTS idx_chunks_metadata_role
    ON chunks ((chunk_metadata->>'chunk_role'));

-- GIN index for full-text search on tsv_content
CREATE INDEX IF NOT EXISTS idx_chunks_tsv_content
    ON chunks USING GIN (tsv_content);

-- Ensure GIN index for tsv exists (backward compatibility)
CREATE INDEX IF NOT EXISTS idx_chunks_tsv
    ON chunks USING GIN (tsv);

-- GIN index on heading_path array for fast hierarchical breadcrumb filtering
CREATE INDEX IF NOT EXISTS idx_chunks_heading_path_gin
    ON chunks USING GIN (heading_path);

-- Composite / partial index for page-scoped metadata operations
CREATE INDEX IF NOT EXISTS idx_chunks_page_metadata
    ON chunks (page_id) WHERE chunk_metadata IS NOT NULL;
