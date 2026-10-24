package com.example.docmind.service.metadata;

import com.example.docmind.dto.ChunkMetadata;
import com.example.docmind.entity.ChunkEntity;
import com.example.docmind.repository.ChunkRepository;
import com.example.docmind.service.graph.ObsidianMetadataCache;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Service thực thi làm giàu dữ liệu động (non-destructive backfill) cho các chunk theo chuẩn Obsidian.
 * Feature F10: Bổ sung chunk_metadata JSONB và weighted tsv_content cho toàn bộ 65 chunk của Page mặc định,
 * cam kết 100% bảo tồn số lượng bản ghi (trước = 65, sau = 65), tuyệt đối không xóa dữ liệu.
 */
@Service
public class ChunkMetadataBackfillService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ChunkMetadataBackfillService.class);

    public static final UUID NANG_SEN_PAGE_ID = UUID.fromString("2850a8ef-ca4a-40eb-87fa-4f808640efee");
    public static final int EXPECTED_NANG_SEN_CHUNK_COUNT = 65;

    private final JdbcTemplate jdbc;
    private final ChunkRepository chunkRepository;
    private final ObjectMapper objectMapper;
    private final ObsidianMetadataCache obsidianMetadataCache;

    @Value("${docmind.backfill.auto-run:true}")
    private boolean autoRun;

    @Value("${docmind.backfill.target-page-id:2850a8ef-ca4a-40eb-87fa-4f808640efee}")
    private String targetPageIdStr;

    public ChunkMetadataBackfillService(JdbcTemplate jdbc,
                                        ChunkRepository chunkRepository,
                                        @Autowired(required = false) ObjectMapper objectMapper,
                                        @Autowired(required = false) ObsidianMetadataCache obsidianMetadataCache) {
        this.jdbc = jdbc;
        this.chunkRepository = chunkRepository;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.obsidianMetadataCache = obsidianMetadataCache;
    }

    public void setAutoRun(boolean autoRun) {
        this.autoRun = autoRun;
    }

    public void setTargetPageIdStr(String targetPageIdStr) {
        this.targetPageIdStr = targetPageIdStr;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!autoRun) {
            log.info("stage=chunk-metadata-backfill status=disabled (docmind.backfill.auto-run=false)");
            return;
        }

        try {
            UUID targetPageId = UUID.fromString(targetPageIdStr);
            log.info("stage=chunk-metadata-backfill status=starting target_page_id={}", targetPageId);
            BackfillReport report = backfillPage(targetPageId, false);
            if ("SKIPPED_EMPTY_PAGE".equals(report.status())) {
                report = backfillAll(false);
            }
            log.info("stage=chunk-metadata-backfill status=completed report={}", report);
        } catch (Exception e) {
            log.error("stage=chunk-metadata-backfill status=error message={}", e.getMessage(), e);
        }
    }

    /**
     * Làm giàu dữ liệu cho một Page cụ thể.
     */
    @Transactional
    public BackfillReport backfillPage(UUID pageId, boolean force) {
        long t0 = System.currentTimeMillis();
        if (pageId == null) {
            throw new IllegalArgumentException("pageId không được null");
        }

        // 1. Kiểm tra pre-flight số lượng bản ghi
        int beforeCount = countChunksByPageId(pageId);
        log.info("stage=backfill-preflight page_id={} before_row_count={}", pageId, beforeCount);

        if (beforeCount == 0) {
            return new BackfillReport(pageId, 0, 0, 0, 0, 0, 0, 0, true, 0, "SKIPPED_EMPTY_PAGE");
        }

        // Kiểm tra cứng pre-flight cho Page mặc định
        if (NANG_SEN_PAGE_ID.equals(pageId) && beforeCount != EXPECTED_NANG_SEN_CHUNK_COUNT) {
            String errMsg = String.format("NANG_SEN_PAGE_ID preflight chunk count mismatch! Expected=%d, Actual=%d",
                    EXPECTED_NANG_SEN_CHUNK_COUNT, beforeCount);
            log.error(errMsg);
            throw new IllegalStateException(errMsg);
        }

        // 2. Kiểm tra các cột trong DB
        boolean hasMetadataCol = columnExists("chunks", "chunk_metadata");
        boolean hasTsvContentCol = columnExists("chunks", "tsv_content");

        if (!hasMetadataCol) {
            log.warn("stage=backfill-schema column 'chunk_metadata' missing; applying idempotent ALTER TABLE");
            jdbc.execute("ALTER TABLE chunks ADD COLUMN IF NOT EXISTS chunk_metadata JSONB NOT NULL DEFAULT '{}'::jsonb;");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chunks_chunk_metadata_gin ON chunks USING GIN (chunk_metadata);");
        }
        if (!hasTsvContentCol) {
            log.warn("stage=backfill-schema column 'tsv_content' missing; applying idempotent ALTER TABLE");
            jdbc.execute("ALTER TABLE chunks ADD COLUMN IF NOT EXISTS tsv_content TSVECTOR;");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chunks_tsv_content ON chunks USING GIN (tsv_content);");
            hasTsvContentCol = true;
        }

        // 3. Đọc toàn bộ ChunkEntity của Page
        List<ChunkEntity> chunks = chunkRepository.findByPageId(pageId);
        if (chunks.size() != beforeCount) {
            log.warn("stage=backfill-warning findByPageId returned {} chunks, expected {}", chunks.size(), beforeCount);
        }

        int processed = 0;
        int updated = 0;
        int skipped = 0;

        String updateSqlWithTsvContent = """
                UPDATE chunks
                SET chunk_metadata = CAST(? AS jsonb),
                    tsv = setweight(to_tsvector('simple', coalesce(array_to_string(heading_path, ' '), '')), 'A')
                          || setweight(to_tsvector('simple', coalesce(text, '')), 'B'),
                    tsv_content = setweight(to_tsvector('simple', coalesce(array_to_string(heading_path, ' '), '')), 'A')
                                  || setweight(to_tsvector('simple', coalesce(text, '')), 'B')
                WHERE id = ?
                """;

        String updateSqlFallback = """
                UPDATE chunks
                SET chunk_metadata = CAST(? AS jsonb),
                    tsv = setweight(to_tsvector('simple', coalesce(array_to_string(heading_path, ' '), '')), 'A')
                          || setweight(to_tsvector('simple', coalesce(text, '')), 'B')
                WHERE id = ?
                """;

        String chosenSql = hasTsvContentCol ? updateSqlWithTsvContent : updateSqlFallback;

        for (ChunkEntity entity : chunks) {
            processed++;
            List<String> hp = entity.getHeadingPath() != null ? Arrays.asList(entity.getHeadingPath()) : List.of();
            String text = entity.getText() != null ? entity.getText() : "";

            // Trích xuất metadata động
            ChunkMetadata meta = ChunkMetadataExtractor.extract(hp, text);
            String json;
            try {
                json = objectMapper.writeValueAsString(meta);
            } catch (JsonProcessingException e) {
                log.error("stage=backfill-json-error chunk_id={} error={}", entity.getId(), e.getMessage());
                json = "{}";
            }

            int rowsAffected = jdbc.update(chosenSql, json, entity.getId());
            if (rowsAffected > 0) {
                updated++;
            } else {
                skipped++;
            }
        }

        // 4. Kiểm tra post-flight & khẳng định bất biến (Invariants)
        int afterCount = countChunksByPageId(pageId);
        int metadataPopulatedCount = countPopulatedMetadata(pageId);
        int weightedTsvCount = countWeightedTsv(pageId);

        boolean rowCountPreserved = (beforeCount == afterCount);
        if (!rowCountPreserved) {
            String errMsg = String.format("CRITICAL INVARIANT VIOLATION: Row count mismatch! Before=%d, After=%d",
                    beforeCount, afterCount);
            log.error(errMsg);
            throw new IllegalStateException(errMsg);
        }

        // Đối với Page mặc định, kiểm tra cứng 65 chunk
        if (NANG_SEN_PAGE_ID.equals(pageId) && afterCount != EXPECTED_NANG_SEN_CHUNK_COUNT) {
            String errMsg = String.format("NANG_SEN_PAGE_ID chunk count mismatch! Expected=%d, Actual=%d",
                    EXPECTED_NANG_SEN_CHUNK_COUNT, afterCount);
            log.error(errMsg);
            throw new IllegalStateException(errMsg);
        }

        // 5. Làm mới in-memory ObsidianMetadataCache
        if (obsidianMetadataCache != null) {
            obsidianMetadataCache.invalidatePage(pageId);
            try {
                // Kích hoạt nạp ấm lại đồ thị
                obsidianMetadataCache.getGraphForPage(pageId);
            } catch (Exception e) {
                log.warn("stage=backfill-cache-warmup-skip error={}", e.getMessage());
            }
            log.info("stage=backfill-cache-refreshed page_id={}", pageId);
        }

        long elapsed = System.currentTimeMillis() - t0;
        return new BackfillReport(
                pageId,
                beforeCount,
                afterCount,
                processed,
                updated,
                skipped,
                metadataPopulatedCount,
                weightedTsvCount,
                true,
                elapsed,
                "SUCCESS"
        );
    }

    /**
     * Làm giàu dữ liệu toàn bộ cơ sở dữ liệu (tất cả các Page).
     */
    @Transactional
    public BackfillReport backfillAll(boolean force) {
        List<UUID> pageIds = jdbc.query(
                "SELECT DISTINCT page_id FROM chunks WHERE page_id IS NOT NULL",
                (rs, rowNum) -> rs.getObject("page_id", UUID.class)
        );

        int totalBefore = countAllChunks();
        int totalUpdated = 0;
        long t0 = System.currentTimeMillis();

        for (UUID pId : pageIds) {
            BackfillReport subReport = backfillPage(pId, force);
            totalUpdated += subReport.chunksUpdated();
        }

        int totalAfter = countAllChunks();
        if (totalBefore != totalAfter) {
            throw new IllegalStateException(String.format("CRITICAL INVARIANT VIOLATION in backfillAll: Before=%d, After=%d",
                    totalBefore, totalAfter));
        }

        if (obsidianMetadataCache != null) {
            obsidianMetadataCache.invalidateAll();
        }

        return new BackfillReport(
                null,
                totalBefore,
                totalAfter,
                totalBefore,
                totalUpdated,
                0,
                countAllPopulatedMetadata(),
                countAllWeightedTsv(),
                totalBefore == totalAfter,
                System.currentTimeMillis() - t0,
                "SUCCESS"
        );
    }

    public int countChunksByPageId(UUID pageId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM chunks WHERE page_id = ?",
                Integer.class,
                pageId
        );
        return count != null ? count : 0;
    }

    public int countPopulatedMetadata(UUID pageId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM chunks WHERE page_id = ? AND chunk_metadata IS NOT NULL",
                Integer.class,
                pageId
        );
        return count != null ? count : 0;
    }

    public int countWeightedTsv(UUID pageId) {
        String sql = columnExists("chunks", "tsv_content")
                ? "SELECT count(*) FROM chunks WHERE page_id = ? AND (tsv::text ~ ':[0-9]+[AB]' OR tsv_content::text ~ ':[0-9]+[AB]' OR tsv::text LIKE '%:A%' OR tsv_content::text LIKE '%:A%')"
                : "SELECT count(*) FROM chunks WHERE page_id = ? AND (tsv::text ~ ':[0-9]+[AB]' OR tsv::text LIKE '%:A%')";
        Integer count = jdbc.queryForObject(sql, Integer.class, pageId);
        return count != null ? count : 0;
    }

    public int countAllChunks() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM chunks", Integer.class);
        return count != null ? count : 0;
    }

    public int countAllPopulatedMetadata() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM chunks WHERE chunk_metadata IS NOT NULL", Integer.class);
        return count != null ? count : 0;
    }

    public int countAllWeightedTsv() {
        String sql = columnExists("chunks", "tsv_content")
                ? "SELECT count(*) FROM chunks WHERE (tsv::text ~ ':[0-9]+[AB]' OR tsv_content::text ~ ':[0-9]+[AB]' OR tsv::text LIKE '%:A%' OR tsv_content::text LIKE '%:A%')"
                : "SELECT count(*) FROM chunks WHERE (tsv::text ~ ':[0-9]+[AB]' OR tsv::text LIKE '%:A%')";
        Integer count = jdbc.queryForObject(sql, Integer.class);
        return count != null ? count : 0;
    }

    public boolean columnExists(String tableName, String columnName) {
        try {
            Integer count = jdbc.queryForObject(
                    """
                    SELECT count(*)
                    FROM information_schema.columns
                    WHERE table_name = ? AND column_name = ?
                    """,
                    Integer.class,
                    tableName.toLowerCase(Locale.ROOT),
                    columnName.toLowerCase(Locale.ROOT)
            );
            return count != null && count > 0;
        } catch (Exception e) {
            log.warn("columnExists check failed for table={} column={}: {}", tableName, columnName, e.getMessage());
            return false;
        }
    }

    /**
     * DTO kết quả backfill.
     */
    public record BackfillReport(
            UUID pageId,
            int beforeRowCount,
            int afterRowCount,
            int chunksProcessed,
            int chunksUpdated,
            int chunksSkipped,
            int chunksWithMetadata,
            int chunksWithWeightedTsv,
            boolean verified,
            long durationMs,
            String status
    ) {}
}
