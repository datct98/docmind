package com.example.docmind.service.metadata;

import com.example.docmind.dto.ChunkMetadata;
import com.example.docmind.entity.ChunkEntity;
import com.example.docmind.repository.ChunkRepository;
import com.example.docmind.service.graph.ObsidianMetadataCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Adversarial Stress & Invariant Test Suite for ChunkMetadataBackfillService.
 * Validates:
 * 1. Strict row count divergence triggering IllegalStateException (64, 66, post-flight mismatch).
 * 2. Edge cases: null pageId, non-existent page UUIDs, empty pages.
 * 3. Extreme inputs: malformed prices, SQL injection strings, null fields, huge tables.
 * 4. Idempotency across repeated backfills and concurrent executions.
 * 5. Self-healing schema alterations when columns are absent.
 */
class ChunkMetadataBackfillServiceAdversarialTest {

    private JdbcTemplate jdbc;
    private ChunkRepository chunkRepository;
    private ObjectMapper objectMapper;
    private ObsidianMetadataCache obsidianMetadataCache;
    private ChunkMetadataBackfillService service;

    private static final UUID NANG_SEN_PAGE_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        chunkRepository = mock(ChunkRepository.class);
        objectMapper = new ObjectMapper();
        obsidianMetadataCache = mock(ObsidianMetadataCache.class);

        service = new ChunkMetadataBackfillService(jdbc, chunkRepository, objectMapper, obsidianMetadataCache);
    }

    private List<ChunkEntity> generate65Chunks() {
        List<ChunkEntity> chunks = new ArrayList<>();
        UUID docId = UUID.randomUUID();
        for (int i = 0; i < 65; i++) {
            chunks.add(ChunkEntity.builder()
                    .id(UUID.randomUUID())
                    .documentId(docId)
                    .pageId(NANG_SEN_PAGE_ID)
                    .headingPath(new String[]{"MASSAGE", "Cơ sở " + (i % 2 == 0 ? "Quận 1" : "Mỹ Đình")})
                    .text("| Mã | Tên | Giá |\n| --- | --- | --- |\n| NS01 | Trị liệu | 399.000 VNĐ |")
                    .build());
        }
        return chunks;
    }

    @ParameterizedTest(name = "Preserve Page snapshot of {0} chunks, without a fixed business count")
    @ValueSource(ints = {1, 63, 64, 66, 67, 100, 114})
    void arbitraryPageSnapshotCountIsPreserved(int actualCount) {
        var chunks = new ArrayList<ChunkEntity>();
        for (int i = 0; i < actualCount; i++) chunks.add(ChunkEntity.builder().id(UUID.randomUUID())
                .pageId(NANG_SEN_PAGE_ID).text("price source").headingPath(new String[]{"Catalog"}).build());
        when(chunkRepository.findByPageId(NANG_SEN_PAGE_ID)).thenReturn(chunks);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(NANG_SEN_PAGE_ID))).thenReturn(actualCount);
        when(jdbc.queryForObject(contains("FROM information_schema.columns"), eq(Integer.class), anyString(), anyString())).thenReturn(1);
        when(jdbc.update(anyString(), anyString(), any(UUID.class))).thenReturn(1);
        var report = service.backfillPage(NANG_SEN_PAGE_ID, false);
        assertThat(report.verified()).isTrue();
        assertThat(report.beforeRowCount()).isEqualTo(actualCount);
        assertThat(report.afterRowCount()).isEqualTo(actualCount);
        assertThat(report.chunksUpdated()).isEqualTo(actualCount);
        verify(jdbc, times(actualCount)).update(anyString(), anyString(), any(UUID.class));
    }

    @Test
    @DisplayName("ADV-2: Post-flight row count divergence (before=65, after=66) ném IllegalStateException")
    void testPostflightRowCountDivergence_65to66_ThrowsIllegalStateException() {
        List<ChunkEntity> chunks65 = generate65Chunks();
        when(chunkRepository.findByPageId(NANG_SEN_PAGE_ID)).thenReturn(chunks65);

        // Pre-flight returns 65, post-flight returns 66
        when(jdbc.queryForObject(eq("SELECT count(*) FROM chunks WHERE page_id = ?"), eq(Integer.class), eq(NANG_SEN_PAGE_ID)))
                .thenReturn(65, 66);
        when(jdbc.queryForObject(contains("FROM information_schema.columns"), eq(Integer.class), anyString(), anyString()))
                .thenReturn(1);
        when(jdbc.update(anyString(), anyString(), any(UUID.class))).thenReturn(1);

        assertThatThrownBy(() -> service.backfillPage(NANG_SEN_PAGE_ID, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CRITICAL INVARIANT VIOLATION: Row count mismatch! Before=65, After=66");
    }

    @Test
    @DisplayName("ADV-3: Post-flight row count divergence (before=65, after=64) ném IllegalStateException")
    void testPostflightRowCountDivergence_65to64_ThrowsIllegalStateException() {
        List<ChunkEntity> chunks65 = generate65Chunks();
        when(chunkRepository.findByPageId(NANG_SEN_PAGE_ID)).thenReturn(chunks65);

        // Pre-flight returns 65, post-flight returns 64
        when(jdbc.queryForObject(eq("SELECT count(*) FROM chunks WHERE page_id = ?"), eq(Integer.class), eq(NANG_SEN_PAGE_ID)))
                .thenReturn(65, 64);
        when(jdbc.queryForObject(contains("FROM information_schema.columns"), eq(Integer.class), anyString(), anyString()))
                .thenReturn(1);
        when(jdbc.update(anyString(), anyString(), any(UUID.class))).thenReturn(1);

        assertThatThrownBy(() -> service.backfillPage(NANG_SEN_PAGE_ID, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CRITICAL INVARIANT VIOLATION: Row count mismatch! Before=65, After=64");
    }

    @Test
    @DisplayName("ADV-4: Generic Page post-flight divergence (before=10, after=9) ném IllegalStateException")
    void testGenericPagePostflightDivergence_ThrowsIllegalStateException() {
        UUID pageId = UUID.randomUUID();
        when(chunkRepository.findByPageId(pageId)).thenReturn(List.of(
                ChunkEntity.builder().id(UUID.randomUUID()).pageId(pageId).text("test").headingPath(new String[0]).build()
        ));

        when(jdbc.queryForObject(eq("SELECT count(*) FROM chunks WHERE page_id = ?"), eq(Integer.class), eq(pageId)))
                .thenReturn(10, 9);
        when(jdbc.queryForObject(contains("FROM information_schema.columns"), eq(Integer.class), anyString(), anyString()))
                .thenReturn(1);
        when(jdbc.update(anyString(), anyString(), any(UUID.class))).thenReturn(1);

        assertThatThrownBy(() -> service.backfillPage(pageId, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CRITICAL INVARIANT VIOLATION: Row count mismatch! Before=10, After=9");
    }

    @Test
    @DisplayName("ADV-5: backfillAll divergence (totalBefore=100, totalAfter=99) ném IllegalStateException")
    void testBackfillAllDivergence_ThrowsIllegalStateException() {
        UUID pageId1 = UUID.randomUUID();
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of(pageId1));

        when(chunkRepository.findByPageId(pageId1)).thenReturn(List.of(
                ChunkEntity.builder().id(UUID.randomUUID()).pageId(pageId1).text("test").headingPath(new String[0]).build()
        ));

        // countChunksByPageId returns 1 before and 1 after for the page
        when(jdbc.queryForObject(eq("SELECT count(*) FROM chunks WHERE page_id = ?"), eq(Integer.class), eq(pageId1)))
                .thenReturn(1, 1);
        when(jdbc.queryForObject(contains("FROM information_schema.columns"), eq(Integer.class), anyString(), anyString()))
                .thenReturn(1);
        when(jdbc.update(anyString(), anyString(), any(UUID.class))).thenReturn(1);

        // countAllChunks returns 100 at start, but 99 at end (external mutation)
        when(jdbc.queryForObject(eq("SELECT count(*) FROM chunks"), eq(Integer.class)))
                .thenReturn(100, 99);

        assertThatThrownBy(() -> service.backfillAll(false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CRITICAL INVARIANT VIOLATION in backfillAll: Before=100, After=99");
    }

    @Test
    @DisplayName("ADV-6: pageId là null ném IllegalArgumentException")
    void testNullPageId_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> service.backfillPage(null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pageId không được null");
    }

    @Test
    @DisplayName("ADV-7: Non-existent page UUID (0 rows in DB) hoàn thành an toàn với status SKIPPED_EMPTY_PAGE")
    void testNonExistentPageUuid_SkipsGracefullyWithoutError() {
        UUID nonExistentPageId = UUID.randomUUID();
        when(jdbc.queryForObject(eq("SELECT count(*) FROM chunks WHERE page_id = ?"), eq(Integer.class), eq(nonExistentPageId)))
                .thenReturn(0);

        ChunkMetadataBackfillService.BackfillReport report = service.backfillPage(nonExistentPageId, false);

        assertThat(report.status()).isEqualTo("SKIPPED_EMPTY_PAGE");
        assertThat(report.beforeRowCount()).isEqualTo(0);
        assertThat(report.afterRowCount()).isEqualTo(0);
        assertThat(report.chunksProcessed()).isEqualTo(0);
        assertThat(report.chunksUpdated()).isEqualTo(0);
        assertThat(report.verified()).isTrue();
        verify(chunkRepository, never()).findByPageId(any());
        verify(jdbc, never()).update(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("ADV-8: Chunks chứa dữ liệu cực đoan: Malformed prices, SQL Injection, null fields, huge tables")
    void testAdversarialChunkData_ProcessedResiliently() {
        UUID pageId = UUID.randomUUID();

        // 1. Chunk with null text & null headingPath
        ChunkEntity chunk1 = ChunkEntity.builder()
                .id(UUID.randomUUID())
                .pageId(pageId)
                .headingPath(null)
                .text(null)
                .build();

        // 2. Chunk with malformed prices (> 50 billion, negative, overflowing strings)
        ChunkEntity chunk2 = ChunkEntity.builder()
                .id(UUID.randomUUID())
                .pageId(pageId)
                .headingPath(new String[]{"FAQ"})
                .text("Giá gói là 9999999999999999999999999999999999999 VNĐ và -500k và 99.999.999.999 VNĐ")
                .build();

        // 3. Chunk with SQL Injection payload
        ChunkEntity chunk3 = ChunkEntity.builder()
                .id(UUID.randomUUID())
                .pageId(pageId)
                .headingPath(new String[]{"'); DROP TABLE chunks;--"})
                .text("Test SQL injection '); DELETE FROM chunks WHERE '1'='1';--")
                .build();

        // 4. Chunk with huge markdown table (300 rows)
        StringBuilder hugeTable = new StringBuilder("| Cột 1 | Cột 2 | Cột 3 |\n| --- | --- | --- |\n");
        for (int r = 0; r < 300; r++) {
            hugeTable.append("| Dòng ").append(r).append(" | Dữ liệu ").append(r).append(" | 129.000 VNĐ |\n");
        }
        ChunkEntity chunk4 = ChunkEntity.builder()
                .id(UUID.randomUUID())
                .pageId(pageId)
                .headingPath(new String[]{"BẢNG GIÁ LỚN", "Chi nhánh Mỹ Đình"})
                .text(hugeTable.toString())
                .build();

        List<ChunkEntity> chunks = List.of(chunk1, chunk2, chunk3, chunk4);
        when(chunkRepository.findByPageId(pageId)).thenReturn(chunks);

        when(jdbc.queryForObject(eq("SELECT count(*) FROM chunks WHERE page_id = ?"), eq(Integer.class), eq(pageId)))
                .thenReturn(4);
        when(jdbc.queryForObject(contains("FROM information_schema.columns"), eq(Integer.class), anyString(), anyString()))
                .thenReturn(1);
        when(jdbc.queryForObject(eq("SELECT count(*) FROM chunks WHERE page_id = ? AND chunk_metadata IS NOT NULL"), eq(Integer.class), eq(pageId)))
                .thenReturn(4);
        when(jdbc.queryForObject(contains("tsv::text LIKE '%:A%'"), eq(Integer.class), eq(pageId)))
                .thenReturn(4);
        when(jdbc.update(anyString(), anyString(), any(UUID.class))).thenReturn(1);

        ChunkMetadataBackfillService.BackfillReport report = service.backfillPage(pageId, false);

        assertThat(report.status()).isEqualTo("SUCCESS");
        assertThat(report.chunksProcessed()).isEqualTo(4);
        assertThat(report.chunksUpdated()).isEqualTo(4);
        assertThat(report.beforeRowCount()).isEqualTo(4);
        assertThat(report.afterRowCount()).isEqualTo(4);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(4)).update(anyString(), jsonCaptor.capture(), any(UUID.class));

        List<String> captured = jsonCaptor.getAllValues();
        // chunk1: GENERAL
        assertThat(captured.get(0)).contains("\"chunk_role\":\"GENERAL\"");
        // chunk2: FAQ
        assertThat(captured.get(1)).contains("\"chunk_role\":\"FAQ\"");
        // chunk3: SQL injection string safely handled, producing valid clean JSON without corruption
        assertThat(captured.get(2)).contains("\"chunk_role\":\"GENERAL\"");
        assertThat(captured.get(2)).contains("\"branch_tags\":[]");
        assertThat(captured.get(2)).contains("\"service_codes\":[]");
        // chunk4: PRICE_TABLE with minPrice=129000 and branch_tags=["my_dinh"]
        assertThat(captured.get(3)).contains("\"chunk_role\":\"PRICE_TABLE\"");
        assertThat(captured.get(3)).contains("\"min_price\":129000");
        assertThat(captured.get(3)).contains("\"branch_tags\":[\"my_dinh\"]");
    }

    @Test
    @DisplayName("ADV-9: Tự động phục hồi schema khi các cột chunk_metadata và tsv_content chưa tồn tại")
    void testSelfHealingSchema_WhenColumnsMissing() {
        List<ChunkEntity> chunks65 = generate65Chunks();
        when(chunkRepository.findByPageId(NANG_SEN_PAGE_ID)).thenReturn(chunks65);

        when(jdbc.queryForObject(eq("SELECT count(*) FROM chunks WHERE page_id = ?"), eq(Integer.class), eq(NANG_SEN_PAGE_ID)))
                .thenReturn(65);

        // Column checks return 0 (columns do not exist initially)
        when(jdbc.queryForObject(contains("FROM information_schema.columns"), eq(Integer.class), eq("chunks"), eq("chunk_metadata")))
                .thenReturn(0);
        when(jdbc.queryForObject(contains("FROM information_schema.columns"), eq(Integer.class), eq("chunks"), eq("tsv_content")))
                .thenReturn(0);

        when(jdbc.queryForObject(eq("SELECT count(*) FROM chunks WHERE page_id = ? AND chunk_metadata IS NOT NULL"), eq(Integer.class), eq(NANG_SEN_PAGE_ID)))
                .thenReturn(65);
        when(jdbc.queryForObject(contains("tsv::text LIKE '%:A%'"), eq(Integer.class), eq(NANG_SEN_PAGE_ID)))
                .thenReturn(65);
        when(jdbc.update(anyString(), anyString(), any(UUID.class))).thenReturn(1);

        ChunkMetadataBackfillService.BackfillReport report = service.backfillPage(NANG_SEN_PAGE_ID, false);

        assertThat(report.status()).isEqualTo("SUCCESS");
        // Verify ALTER TABLE was executed for both columns
        verify(jdbc).execute(contains("ALTER TABLE chunks ADD COLUMN IF NOT EXISTS chunk_metadata JSONB"));
        verify(jdbc).execute(contains("ALTER TABLE chunks ADD COLUMN IF NOT EXISTS tsv_content TSVECTOR"));
        verify(jdbc).execute(contains("CREATE INDEX IF NOT EXISTS idx_chunks_chunk_metadata_gin"));
        verify(jdbc).execute(contains("CREATE INDEX IF NOT EXISTS idx_chunks_tsv_content"));
    }

    @Test
    @DisplayName("ADV-10: Khả năng đồng thời (Concurrency) — Nhiều luồng gọi backfill đồng thời duy trì bất biến")
    void testConcurrentBackfillExecutions_PreserveInvariants() throws InterruptedException, ExecutionException {
        List<ChunkEntity> chunks65 = generate65Chunks();
        when(chunkRepository.findByPageId(NANG_SEN_PAGE_ID)).thenReturn(chunks65);

        when(jdbc.queryForObject(eq("SELECT count(*) FROM chunks WHERE page_id = ?"), eq(Integer.class), eq(NANG_SEN_PAGE_ID)))
                .thenReturn(65);
        when(jdbc.queryForObject(contains("FROM information_schema.columns"), eq(Integer.class), anyString(), anyString()))
                .thenReturn(1);
        when(jdbc.queryForObject(eq("SELECT count(*) FROM chunks WHERE page_id = ? AND chunk_metadata IS NOT NULL"), eq(Integer.class), eq(NANG_SEN_PAGE_ID)))
                .thenReturn(65);
        when(jdbc.queryForObject(contains("tsv::text LIKE '%:A%'"), eq(Integer.class), eq(NANG_SEN_PAGE_ID)))
                .thenReturn(65);
        when(jdbc.update(anyString(), anyString(), any(UUID.class))).thenReturn(1);

        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Callable<ChunkMetadataBackfillService.BackfillReport>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> service.backfillPage(NANG_SEN_PAGE_ID, false));
        }

        List<Future<ChunkMetadataBackfillService.BackfillReport>> futures = executor.invokeAll(tasks);
        executor.shutdown();

        for (Future<ChunkMetadataBackfillService.BackfillReport> future : futures) {
            ChunkMetadataBackfillService.BackfillReport report = future.get();
            assertThat(report.verified()).isTrue();
            assertThat(report.status()).isEqualTo("SUCCESS");
            assertThat(report.beforeRowCount()).isEqualTo(65);
            assertThat(report.afterRowCount()).isEqualTo(65);
        }
    }
}
