package com.example.docmind.service.metadata;

import com.example.docmind.dto.ChunkMetadata;
import com.example.docmind.entity.ChunkEntity;
import com.example.docmind.repository.ChunkRepository;
import com.example.docmind.service.graph.ObsidianMetadataCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit Test Suite for ChunkMetadataBackfillService (Feature F10):
 * 1. Full 65-chunk enrichment and verification (before == 65, after == 65) for Page "Nàng sen".
 * 2. Invariant violation detection (throws IllegalStateException if row counts diverge).
 * 3. Source provenance survives heuristic enrichment for any Page size.
 * 4. Idempotency (running backfill repeatedly leaves row counts unchanged).
 * 5. Dynamic metadata extraction verification via ChunkMetadataExtractor (zero hardcode).
 * 6. Cache invalidation on ObsidianMetadataCache.
 */
class ChunkMetadataBackfillServiceTest {

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

    private List<ChunkEntity> generate65NangSenChunks() {
        List<ChunkEntity> chunks = new ArrayList<>();
        UUID doc1Price = UUID.randomUUID();
        UUID doc2Detail = UUID.randomUUID();
        UUID doc3Promo = UUID.randomUUID();

        // 20 chunks Bảng giá (PRICE_TABLE)
        for (int i = 1; i <= 20; i++) {
            String branch = (i % 2 == 0) ? "Quận 1 (TP.HCM)" : "Mỹ Đình (Hà Nội)";
            long minP = (i % 2 == 0) ? 570_000L : 399_000L;
            long maxP = (i % 2 == 0) ? 2_150_000L : 1_990_000L;
            String text = String.format("""
                    | Mã gói | Tên gói | Giá | Tổng thời gian |
                    | --- | --- | --- | --- |
                    | NS0%d | Massage cơ bản | %d.000VNĐ | 60 phút |
                    | NS0%d | Massage VIP | %d.000VNĐ | 90 phút |
                    """, i, minP / 1000, i + 1, maxP / 1000);

            chunks.add(ChunkEntity.builder()
                    .id(UUID.randomUUID())
                    .documentId(doc1Price)
                    .pageId(NANG_SEN_PAGE_ID)
                    .headingPath(new String[]{"MASSAGE", "Cơ sở Nàng Sen " + branch})
                    .sourceBlockIds(new UUID[]{UUID.randomUUID()})
                    .page(1)
                    .bbox(new double[]{0, 0, 100, 100})
                    .text(text)
                    .build());
        }

        // 25 chunks FAQ (FAQ)
        for (int i = 1; i <= 25; i++) {
            chunks.add(ChunkEntity.builder()
                    .id(UUID.randomUUID())
                    .documentId(doc2Detail)
                    .pageId(NANG_SEN_PAGE_ID)
                    .headingPath(new String[]{"FAQ", "Khách hỏi thường gặp #" + i})
                    .sourceBlockIds(new UUID[]{UUID.randomUUID()})
                    .page(2)
                    .bbox(new double[]{0, 0, 100, 100})
                    .text("| Câu hỏi | Trả lời |\n| --- | --- |\n| Gói 670k và 780k khác gì nhau? | Gói 780k có phòng riêng tư VIP |")
                    .build());
        }

        // 10 chunks Quy trình (PROCESS)
        for (int i = 1; i <= 10; i++) {
            chunks.add(ChunkEntity.builder()
                    .id(UUID.randomUUID())
                    .documentId(doc2Detail)
                    .pageId(NANG_SEN_PAGE_ID)
                    .headingPath(new String[]{"Quy trình massage", "LƯU_Ý_QUY_TRÌNH bước " + i})
                    .sourceBlockIds(new UUID[]{UUID.randomUUID()})
                    .page(3)
                    .bbox(new double[]{0, 0, 100, 100})
                    .text("| bước | Thao tác | Thời gian |\n| --- | --- | --- |\n| " + i + " | Ngâm chân thảo dược | 15 phút |")
                    .build());
        }

        // 5 chunks Ưu đãi / Khuyến mãi (PROMO)
        for (int i = 1; i <= 5; i++) {
            chunks.add(ChunkEntity.builder()
                    .id(UUID.randomUUID())
                    .documentId(doc3Promo)
                    .pageId(NANG_SEN_PAGE_ID)
                    .headingPath(new String[]{"ƯU ĐÃI & KHUYẾN MÃI", "Chính sách chiết khấu"})
                    .sourceBlockIds(new UUID[]{UUID.randomUUID()})
                    .page(4)
                    .bbox(new double[]{0, 0, 100, 100})
                    .text("| Chiết khấu theo số lượng buổi | Mức ưu đãi |\n| --- | --- |\n| Mua từ 5 buổi | Giảm 10% tổng hóa đơn |")
                    .build());
        }

        // 5 chunks Tổng quát (GENERAL)
        for (int i = 1; i <= 5; i++) {
            chunks.add(ChunkEntity.builder()
                    .id(UUID.randomUUID())
                    .documentId(doc1Price)
                    .pageId(NANG_SEN_PAGE_ID)
                    .headingPath(new String[]{"GIỚI THIỆU CHUNG", "Lịch sử hình thành Nàng Sen"})
                    .sourceBlockIds(new UUID[]{UUID.randomUUID()})
                    .page(5)
                    .bbox(new double[]{0, 0, 100, 100})
                    .text("Nàng Sen Spa là hệ thống chăm sóc sức khỏe và làm đẹp tiêu chuẩn.")
                    .build());
        }

        assertThat(chunks).hasSize(65);
        return chunks;
    }

    @Test
    @DisplayName("F10 Thành công: Làm giàu đầy đủ 65 chunk cho Page Nàng sen (before == 65 && after == 65)")
    void testFull65ChunkEnrichmentAndVerification() {
        List<ChunkEntity> chunks65 = generate65NangSenChunks();
        when(chunkRepository.findByPageId(NANG_SEN_PAGE_ID)).thenReturn(chunks65);

        // Mock pre-flight & post-flight count = 65
        when(jdbc.queryForObject(eq("SELECT count(*) FROM chunks WHERE page_id = ?"), eq(Integer.class), eq(NANG_SEN_PAGE_ID)))
                .thenReturn(65);

        // Mock columnExists for chunk_metadata & tsv_content
        when(jdbc.queryForObject(contains("FROM information_schema.columns"), eq(Integer.class), anyString(), anyString()))
                .thenReturn(1);

        // Mock metadata populated count = 65
        when(jdbc.queryForObject(eq("SELECT count(*) FROM chunks WHERE page_id = ? AND chunk_metadata IS NOT NULL"), eq(Integer.class), eq(NANG_SEN_PAGE_ID)))
                .thenReturn(65);

        // Mock weighted tsv count = 65
        when(jdbc.queryForObject(contains("tsv::text LIKE '%:A%'"), eq(Integer.class), eq(NANG_SEN_PAGE_ID)))
                .thenReturn(65);

        // Mock update count = 1 for each chunk
        when(jdbc.update(anyString(), anyString(), any(UUID.class))).thenReturn(1);

        // Execute backfill
        ChunkMetadataBackfillService.BackfillReport report = service.backfillPage(NANG_SEN_PAGE_ID, false);

        // Assertions
        assertThat(report).isNotNull();
        assertThat(report.verified()).isTrue();
        assertThat(report.status()).isEqualTo("SUCCESS");
        assertThat(report.beforeRowCount()).isEqualTo(65);
        assertThat(report.afterRowCount()).isEqualTo(65);
        assertThat(report.chunksProcessed()).isEqualTo(65);
        assertThat(report.chunksUpdated()).isEqualTo(65);
        assertThat(report.chunksSkipped()).isEqualTo(0);
        assertThat(report.chunksWithMetadata()).isEqualTo(65);
        assertThat(report.chunksWithWeightedTsv()).isEqualTo(65);

        // Verify JDBC update called 65 times
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UUID> idCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(jdbc, times(65)).update(sqlCaptor.capture(), jsonCaptor.capture(), idCaptor.capture());

        // Unit P3.4: Verify SQL update writes canonical tsv once (trg_chunks_sync_tsv() keeps tsv_content in sync)
        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("chunk_metadata = CAST(? AS jsonb)");
        assertThat(sql).contains("tsv = setweight(to_tsvector('simple', coalesce(array_to_string(heading_path, ' '), '')), 'A')");
        assertThat(sql).contains("setweight(to_tsvector('simple', coalesce(text, '')), 'B')");
        assertThat(sql).doesNotContain("tsv_content =");

        // Verify genuine JSON content across calls
        List<String> capturedJsons = jsonCaptor.getAllValues();
        assertThat(capturedJsons).anyMatch(json -> json.contains("\"chunk_role\":\"PRICE_TABLE\""));
        assertThat(capturedJsons).anyMatch(json -> json.contains("\"chunk_role\":\"FAQ\""));
        assertThat(capturedJsons).anyMatch(json -> json.contains("\"chunk_role\":\"PROCESS\""));
        assertThat(capturedJsons).anyMatch(json -> json.contains("\"chunk_role\":\"PROMO\""));

        // Verify cache invalidation & warmup
        verify(obsidianMetadataCache).invalidatePage(NANG_SEN_PAGE_ID);
        verify(obsidianMetadataCache).getGraphForPage(NANG_SEN_PAGE_ID);
    }

    @Test
    @DisplayName("F10 Bất biến: Phát hiện chênh lệch số lượng bản ghi trước và sau (before != after) -> Ném IllegalStateException")
    void testInvariantViolation_ThrowsIllegalStateException_WhenRowCountMismatch() {
        UUID pageId = UUID.randomUUID();
        List<ChunkEntity> chunks = List.of(
                ChunkEntity.builder().id(UUID.randomUUID()).pageId(pageId).text("test").headingPath(new String[0]).build()
        );
        when(chunkRepository.findByPageId(pageId)).thenReturn(chunks);

        // Pre-flight count = 1, Post-flight count = 0 (dropped row)
        when(jdbc.queryForObject(eq("SELECT count(*) FROM chunks WHERE page_id = ?"), eq(Integer.class), eq(pageId)))
                .thenReturn(1, 0);

        when(jdbc.queryForObject(contains("FROM information_schema.columns"), eq(Integer.class), anyString(), anyString()))
                .thenReturn(1);
        when(jdbc.update(anyString(), anyString(), any(UUID.class))).thenReturn(1);

        assertThatThrownBy(() -> service.backfillPage(pageId, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CRITICAL INVARIANT VIOLATION: Row count mismatch! Before=1, After=0");
    }

    @Test
    @DisplayName("Curated source metadata outranks inferred metadata at any row count")
    void curatedSourceScopeSurvivesBackfill() throws Exception {
        var chunk = ChunkEntity.builder().id(UUID.randomUUID()).pageId(NANG_SEN_PAGE_ID)
                .headingPath(new String[]{"Other place", "PRICE"}).text("NS01 500k")
                .chunkMetadataJson("{\"provenance_normalized\":true,\"branch_tags\":[\"verified-branch\"],\"source_sha256\":\"verified-sha\",\"embedding_status\":\"FTS_ONLY\"}").build();
        when(chunkRepository.findByPageId(NANG_SEN_PAGE_ID)).thenReturn(List.of(chunk));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(NANG_SEN_PAGE_ID))).thenReturn(1);
        when(jdbc.queryForObject(contains("FROM information_schema.columns"), eq(Integer.class), anyString(), anyString())).thenReturn(1);
        when(jdbc.update(anyString(), anyString(), any(UUID.class))).thenReturn(1);
        var result = service.backfillPage(NANG_SEN_PAGE_ID, false);
        var captured = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(anyString(), captured.capture(), eq(chunk.getId()));
        var metadata = objectMapper.readTree(captured.getValue());
        assertThat(metadata.get("branch_tags").get(0).asText()).isEqualTo("verified-branch");
        assertThat(metadata.get("source_sha256").asText()).isEqualTo("verified-sha");
        assertThat(metadata.get("embedding_status").asText()).isEqualTo("FTS_ONLY");
        assertThat(result.verified()).isTrue();
    }

    @Test
    @DisplayName("F10 Tính lũy đẳng (Idempotency): Chạy backfill nhiều lần liên tiếp không làm thay đổi số dòng")
    void testIdempotency_RunningRepeatedlyPreservesRowCounts() {
        List<ChunkEntity> chunks65 = generate65NangSenChunks();
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

        // Run 1
        ChunkMetadataBackfillService.BackfillReport report1 = service.backfillPage(NANG_SEN_PAGE_ID, false);
        // Run 2
        ChunkMetadataBackfillService.BackfillReport report2 = service.backfillPage(NANG_SEN_PAGE_ID, false);

        assertThat(report1.afterRowCount()).isEqualTo(65);
        assertThat(report2.afterRowCount()).isEqualTo(65);
        assertThat(report1.chunksUpdated()).isEqualTo(report2.chunksUpdated());
        verify(obsidianMetadataCache, times(2)).invalidatePage(NANG_SEN_PAGE_ID);
    }

    @Test
    @DisplayName("F10 Bỏ qua an toàn khi Page rỗng (0 chunks)")
    void testBackfillPage_EmptyPage_SkipsGracefully() {
        UUID emptyPageId = UUID.randomUUID();
        when(jdbc.queryForObject(eq("SELECT count(*) FROM chunks WHERE page_id = ?"), eq(Integer.class), eq(emptyPageId)))
                .thenReturn(0);

        ChunkMetadataBackfillService.BackfillReport report = service.backfillPage(emptyPageId, false);

        assertThat(report.beforeRowCount()).isEqualTo(0);
        assertThat(report.afterRowCount()).isEqualTo(0);
        assertThat(report.chunksUpdated()).isEqualTo(0);
        assertThat(report.status()).isEqualTo("SKIPPED_EMPTY_PAGE");
        verify(chunkRepository, never()).findByPageId(any());
        verify(jdbc, never()).update(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("F10 Auto-run bị tắt (autoRun=false) -> không thực thi bất kỳ lệnh SQL nào khi ứng dụng khởi động")
    void testAutoRunDisabled_DoesNotExecuteOnStartup() {
        service.setAutoRun(false);

        service.run(null);

        verify(chunkRepository, never()).findByPageId(any());
        verify(jdbc, never()).update(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("F10 Auto-run được bật (autoRun=true) -> tự động gọi backfillPage với target_page_id")
    void testAutoRunEnabled_ExecutesOnStartup() {
        service.setAutoRun(true);
        service.setTargetPageIdStr(NANG_SEN_PAGE_ID.toString());

        List<ChunkEntity> chunks65 = generate65NangSenChunks();
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

        service.run(null);

        verify(chunkRepository).findByPageId(NANG_SEN_PAGE_ID);
        verify(jdbc, times(65)).update(anyString(), anyString(), any(UUID.class));
    }
}
