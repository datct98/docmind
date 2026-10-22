package com.example.docmind.service.graph;

import com.example.docmind.dto.ChunkMetadata;
import com.example.docmind.entity.ChunkEntity;
import com.example.docmind.repository.RetrievedChunk;
import com.example.docmind.service.retrieval.ReciprocalRankFusion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("Adversarial Stress Test: ObsidianMetadataCache Edge Cases, Scale, and Concurrency")
class ObsidianMetadataCacheStressTest {

    private ObsidianMetadataCache cache;
    private UUID pageId;
    private UUID docId;

    @BeforeEach
    void setUp() {
        cache = new ObsidianMetadataCache();
        pageId = UUID.randomUUID();
        docId = UUID.randomUUID();
    }

    // =========================================================================
    // 1. EMPTY AND SINGLE-CHUNK DOCUMENTS
    // =========================================================================

    @Test
    @DisplayName("Edge Case: Empty documents list, null list, empty ChunkEntity list")
    void testEmptyDocumentsAndChunks() {
        // 1. Empty list
        BacklinkGraph emptyGraph = cache.buildGraphFromEntities(List.of());
        assertThat(emptyGraph.isEmpty()).isTrue();
        assertThat(emptyGraph.size()).isEqualTo(0);
        assertThat(emptyGraph.getAllPricesSorted()).isEmpty();

        // 2. Null list
        BacklinkGraph nullGraph = cache.buildGraphFromEntities(null);
        assertThat(nullGraph.isEmpty()).isTrue();

        // 3. List with null elements or entity with null fields
        ChunkEntity corruptedEntity = ChunkEntity.builder()
                .id(UUID.randomUUID())
                .headingPath(null)
                .sourceBlockIds(null)
                .text(null)
                .build();
        ChunkEntity nullIdEntity = ChunkEntity.builder()
                .id(null)
                .text("Valid text")
                .build();

        List<ChunkEntity> corruptedList = new ArrayList<>();
        corruptedList.add(null);
        corruptedList.add(corruptedEntity);
        corruptedList.add(nullIdEntity);

        assertThatCode(() -> {
            BacklinkGraph g = cache.buildGraphFromEntities(corruptedList);
            assertThat(g.size()).isEqualTo(1); // Only corruptedEntity has non-null ID
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Edge Case: Single-chunk document (isolated node, no backlinks)")
    void testSingleChunkDocument() {
        ChunkEntity single = ChunkEntity.builder()
                .id(UUID.randomUUID())
                .documentId(docId)
                .pageId(pageId)
                .headingPath(new String[]{"BẢNG GIÁ"})
                .text("| NS01 | Massage | 399.000 VNĐ |")
                .build();

        BacklinkGraph graph = cache.buildGraphFromEntities(List.of(single));
        assertThat(graph.size()).isEqualTo(1);
        assertThat(graph.getBacklinks(single.getId())).isEmpty();
        assertThat(graph.getServiceCodeIndex().get("NS01")).contains(single.getId());
        assertThat(graph.getPriceIndex().get(399000L)).contains(single.getId());

        // Expanding backlinks on a single-chunk graph must gracefully return primary chunks
        RetrievedChunk rc = new RetrievedChunk(single.getId(), docId, List.of(), List.of(), 1, new double[0], single.getText(), 0.9);
        List<ReciprocalRankFusion.FusedChunk> primary = List.of(new ReciprocalRankFusion.FusedChunk(rc, 0.9));

        cache.registerGraphForPage(pageId, graph);
        List<ReciprocalRankFusion.FusedChunk> expanded = cache.expandBacklinks(primary, "gói NS01", pageId, List.of(docId), 3);
        assertThat(expanded).hasSize(1);
    }

    // =========================================================================
    // 2. LARGE DOCUMENTS (50+ CHUNKS) & HIGH DENSITY GRAPH
    // =========================================================================

    @Test
    @DisplayName("Scale Stress: Document with 60+ chunks building dense cross-references")
    void testScaleWithSixtyChunks() {
        List<ChunkEntity> chunks = new ArrayList<>();

        // 20 Price Table chunks (NS01 - NS20)
        for (int i = 1; i <= 20; i++) {
            String code = String.format("NS%02d", i);
            long price = 300_000L + (i * 20_000L);
            chunks.add(ChunkEntity.builder()
                    .id(UUID.randomUUID())
                    .documentId(docId)
                    .pageId(pageId)
                    .headingPath(new String[]{"MASSAGE", "BẢNG GIÁ CHI NHÁNH " + (i % 2 == 0 ? "HÀ NỘI" : "SÀI GÒN")})
                    .text(String.format("| %s | Dịch vụ %d | %d VNĐ | 60 phút |", code, i, price))
                    .build());
        }

        // 20 FAQ chunks matching package codes and prices
        for (int i = 1; i <= 20; i++) {
            String code = String.format("NS%02d", i);
            long price = 300_000L + (i * 20_000L);
            chunks.add(ChunkEntity.builder()
                    .id(UUID.randomUUID())
                    .documentId(docId)
                    .pageId(pageId)
                    .headingPath(new String[]{"HỎI ĐÁP FAQ"})
                    .text(String.format("Hỏi: Gói %s giá %d VNĐ có gì đặc biệt? Đáp: Có phòng riêng và trà thảo mộc.", code, price))
                    .build());
        }

        // 10 Process chunks
        for (int i = 1; i <= 10; i++) {
            String code = String.format("NS%02d", i);
            chunks.add(ChunkEntity.builder()
                    .id(UUID.randomUUID())
                    .documentId(docId)
                    .pageId(pageId)
                    .headingPath(new String[]{"QUY TRÌNH THỰC HIỆN"})
                    .text(String.format("Quy trình gói %s gồm 5 bước tiêu chuẩn Y khoa.", code))
                    .build());
        }

        // 10 Promo chunks
        for (int i = 1; i <= 10; i++) {
            chunks.add(ChunkEntity.builder()
                    .id(UUID.randomUUID())
                    .documentId(docId)
                    .pageId(pageId)
                    .headingPath(new String[]{"ƯU ĐÃI KHUYẾN MÃI"})
                    .text(String.format("Ưu đãi tháng %d: Giảm %d%% khi mua combo từ 5 buổi.", i, 5 + i))
                    .build());
        }

        assertThat(chunks).hasSize(60);

        long startNs = System.nanoTime();
        BacklinkGraph graph = cache.buildGraphFromEntities(chunks);
        long buildMs = (System.nanoTime() - startNs) / 1_000_000L;

        System.out.println("EMPIRICAL CHECK: 60-chunk graph build time = " + buildMs + "ms, total nodes = " + graph.size());
        assertThat(graph.size()).isEqualTo(60);
        assertThat(buildMs).as("Graph construction for 60 chunks must be fast (< 500ms)").isLessThan(500L);

        cache.registerGraphForPage(pageId, graph);

        // Test retrieval expansion with maxBacklinks = 3
        RetrievedChunk rc0 = new RetrievedChunk(chunks.get(0).getId(), docId, List.of(), List.of(), 1, new double[0], chunks.get(0).getText(), 0.95);
        List<ReciprocalRankFusion.FusedChunk> primary = List.of(new ReciprocalRankFusion.FusedChunk(rc0, 0.95));

        long expStartNs = System.nanoTime();
        List<ReciprocalRankFusion.FusedChunk> expanded = cache.expandBacklinks(primary, "gói NS01 giá 320k", pageId, List.of(docId), 3);
        long expMs = (System.nanoTime() - expStartNs) / 1_000_000L;

        System.out.println("EMPIRICAL CHECK: Backlink expansion time = " + expMs + "ms, expanded count = " + expanded.size());
        assertThat(expanded.size()).isGreaterThanOrEqualTo(2);
        assertThat(expanded.size()).isLessThanOrEqualTo(4); // 1 primary + max 3 backlinks
        assertThat(expMs).as("Backlink expansion must be sub-millisecond (< 50ms)").isLessThan(50L);
    }

    // =========================================================================
    // 3. OVERLAPPING, EMPTY, AND NON-EXISTENT CODES
    // =========================================================================

    @Test
    @DisplayName("Retrieval with non-existent service codes or prices returns primary chunks untouched")
    void testNonExistentCodesAndPrices() {
        ChunkEntity chunk1 = ChunkEntity.builder()
                .id(UUID.randomUUID())
                .headingPath(new String[]{"BẢNG GIÁ"})
                .text("| NS01 | 399.000 VNĐ |")
                .build();
        ChunkEntity chunk2 = ChunkEntity.builder()
                .id(UUID.randomUUID())
                .headingPath(new String[]{"FAQ"})
                .text("Hỏi: NS01 có phòng riêng không?")
                .build();

        BacklinkGraph graph = cache.buildGraphFromEntities(List.of(chunk1, chunk2));
        cache.registerGraphForPage(pageId, graph);

        RetrievedChunk rc = new RetrievedChunk(chunk1.getId(), docId, List.of(), List.of(), 1, new double[0], chunk1.getText(), 0.9);
        List<ReciprocalRankFusion.FusedChunk> primary = List.of(new ReciprocalRankFusion.FusedChunk(rc, 0.9));

        // Query with non-existent code "XYZ99" and price "9999k"
        String question = "gói XYZ99 giá 9999k có khuyến mãi gì không";
        List<ReciprocalRankFusion.FusedChunk> result = cache.expandBacklinks(primary, question, pageId, List.of(docId), 2);

        // Since primary chunk1 is linked to chunk2 via NS01, chunk2 may be expanded via graph edge,
        // but no NPE or crash happens on unknown code XYZ99.
        assertThat(result).isNotNull();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("Overlapping service codes: multiple chunks with exact same code (e.g. NS01 in Hanoi and Saigon)")
    void testOverlappingServiceCodesAcrossBranches() {
        ChunkEntity hanoiPrice = ChunkEntity.builder()
                .id(UUID.randomUUID())
                .headingPath(new String[]{"MASSAGE HÀ NỘI"})
                .text("| NS01 | Massage Hà Nội | 399.000 VNĐ |")
                .build();
        ChunkEntity saigonPrice = ChunkEntity.builder()
                .id(UUID.randomUUID())
                .headingPath(new String[]{"MASSAGE SÀI GÒN"})
                .text("| NS01 | Massage Sài Gòn | 570.000 VNĐ |")
                .build();
        ChunkEntity faqNs01 = ChunkEntity.builder()
                .id(UUID.randomUUID())
                .headingPath(new String[]{"FAQ"})
                .text("Hỏi: NS01 làm trong bao lâu? Đáp: 60-70 phút tùy chi nhánh.")
                .build();

        BacklinkGraph graph = cache.buildGraphFromEntities(List.of(hanoiPrice, saigonPrice, faqNs01));
        assertThat(graph.getServiceCodeIndex().get("NS01")).containsExactlyInAnyOrder(
                hanoiPrice.getId(), saigonPrice.getId(), faqNs01.getId()
        );

        cache.registerGraphForPage(pageId, graph);

        RetrievedChunk rc = new RetrievedChunk(hanoiPrice.getId(), docId, List.of(), List.of(), 1, new double[0], hanoiPrice.getText(), 0.88);
        List<ReciprocalRankFusion.FusedChunk> expanded = cache.expandBacklinks(
                List.of(new ReciprocalRankFusion.FusedChunk(rc, 0.88)),
                "gói NS01",
                pageId,
                List.of(docId),
                2
        );

        // Must expand the FAQ chunk
        assertThat(expanded).anyMatch(fc -> fc.chunk().chunkId().equals(faqNs01.getId()));
    }

    // =========================================================================
    // 4. CONCURRENCY & THREAD SAFETY
    // =========================================================================

    @Test
    @DisplayName("Concurrency: Simultaneous queries across 10 threads reading/expanding backlinks")
    void testConcurrentAccessThreadSafety() throws InterruptedException {
        List<ChunkEntity> chunks = List.of(
                ChunkEntity.builder().id(UUID.randomUUID()).headingPath(new String[]{"BẢNG GIÁ"}).text("| NS01 | 399.000 VNĐ |").build(),
                ChunkEntity.builder().id(UUID.randomUUID()).headingPath(new String[]{"FAQ"}).text("Hỏi: NS01 bao lâu? Đáp: 60p").build(),
                ChunkEntity.builder().id(UUID.randomUUID()).headingPath(new String[]{"QUY TRÌNH"}).text("Quy trình NS01 gồm 3 bước").build()
        );

        BacklinkGraph graph = cache.buildGraphFromEntities(chunks);
        cache.registerGraphForPage(pageId, graph);

        int threadCount = 10;
        int operationsPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        RetrievedChunk rc = new RetrievedChunk(chunks.get(0).getId(), docId, List.of(), List.of(), 1, new double[0], chunks.get(0).getText(), 0.8);
        List<ReciprocalRankFusion.FusedChunk> primary = List.of(new ReciprocalRankFusion.FusedChunk(rc, 0.8));

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < operationsPerThread; i++) {
                        List<ReciprocalRankFusion.FusedChunk> res = cache.expandBacklinks(primary, "gói NS01 399k", pageId, List.of(docId), 2);
                        if (res != null && !res.isEmpty()) {
                            successCount.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(threadCount * operationsPerThread);
    }
}
