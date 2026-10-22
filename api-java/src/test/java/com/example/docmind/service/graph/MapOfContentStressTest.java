package com.example.docmind.service.graph;

import com.example.docmind.entity.ChunkEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("Adversarial Stress Test: MapOfContentService Robustness and Boundary Conditions")
class MapOfContentStressTest {

    private final MapOfContentService service = new MapOfContentService(null, null);

    @Test
    @DisplayName("Corrupted and boundary chunks: null texts, empty tables, malformed prices")
    void testCorruptedAndBoundaryChunks() {
        List<ChunkEntity> chunks = new ArrayList<>();

        // Null chunk
        chunks.add(null);

        // Chunk with null text
        chunks.add(ChunkEntity.builder().id(UUID.randomUUID()).headingPath(new String[]{"MASSAGE"}).text(null).build());

        // Chunk with blank text
        chunks.add(ChunkEntity.builder().id(UUID.randomUUID()).headingPath(new String[]{"MASSAGE"}).text("   ").build());

        // Chunk with null heading path
        chunks.add(ChunkEntity.builder().id(UUID.randomUUID()).headingPath(null).text("| Gói 1 | 200.000 VNĐ |").build());

        // Chunk with empty heading path
        chunks.add(ChunkEntity.builder().id(UUID.randomUUID()).headingPath(new String[0]).text("| Gói 2 | 300.000 VNĐ |").build());

        assertThatCode(() -> {
            String moc = service.buildMocFromChunks(chunks);
            assertThat(moc).isNotNull();
            System.out.println("EMPIRICAL CHECK: Corrupted chunks MOC output length = " + moc.length());
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Huge catalog: 100 chunks across 10 categories and 5 branches")
    void testHugeCatalogPerformance() {
        List<ChunkEntity> chunks = new ArrayList<>();
        String[] categories = {"MASSAGE TRỊ LIỆU", "GỘI ĐẦU DƯỠNG SINH", "CHĂM SÓC DA", "TRIỆT LÔNG", "TẮM TRẮNG",
                "GIẢM BÉO", "NHA KHOA THẨM MỸ", "TRỊ MỤN CHUYÊN SÂU", "PHUN XĂM", "SPA THƯ GIÃN"};
        String[] branches = {"Hà Nội (Mỹ Đình)", "Hà Nội (Bích Câu)", "Hà Nội (Long Biên)", "TP.HCM (Quận 1)", "Đà Nẵng (Hải Châu)"};

        for (int i = 0; i < 100; i++) {
            String cat = categories[i % categories.length];
            String branch = branches[i % branches.length];
            long price = 100_000L + (i * 15_000L);
            chunks.add(ChunkEntity.builder()
                    .id(UUID.randomUUID())
                    .headingPath(new String[]{cat, branch})
                    .text(String.format("| PK%03d | Gói số %d | %,d VNĐ | 60 phút |", i, i, price))
                    .build());
        }

        long startNs = System.nanoTime();
        String moc = service.buildMocFromChunks(chunks);
        long durationMs = (System.nanoTime() - startNs) / 1_000_000L;

        System.out.println("EMPIRICAL CHECK: 100-chunk MOC generation time = " + durationMs + "ms");
        assertThat(moc).contains(MapOfContentService.MOC_HEADER);
        assertThat(moc).contains(MapOfContentService.MOC_FOOTER);
        assertThat(durationMs).as("MOC generation for 100 chunks must be fast (< 200ms)").isLessThan(200L);
    }

    @Test
    @DisplayName("Concurrency: Concurrent MOC generation from multiple threads")
    void testConcurrentMocGeneration() throws InterruptedException {
        ChunkEntity chunk = ChunkEntity.builder()
                .id(UUID.randomUUID())
                .headingPath(new String[]{"MASSAGE", "Cơ sở Mỹ Đình (Hà Nội)"})
                .text("| NS01 | Massage | 399.000 VNĐ |")
                .build();
        List<ChunkEntity> chunks = List.of(chunk);

        int threads = 10;
        int runs = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < runs; j++) {
                        String moc = service.buildMocFromChunks(chunks);
                        if (moc != null && !moc.isBlank()) {
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
        assertThat(successCount.get()).isEqualTo(threads * runs);
    }
}
