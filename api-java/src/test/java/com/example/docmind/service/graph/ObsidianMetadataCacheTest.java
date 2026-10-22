package com.example.docmind.service.graph;

import com.example.docmind.dto.ChunkMetadata;
import com.example.docmind.entity.ChunkEntity;
import com.example.docmind.repository.RetrievedChunk;
import com.example.docmind.service.retrieval.ReciprocalRankFusion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ObsidianMetadataCacheTest {

    private ObsidianMetadataCache metadataCache;
    private UUID pageId;
    private UUID docId;

    private ChunkEntity chunk1PriceTable;
    private ChunkEntity chunk2Faq;
    private ChunkEntity chunk3Process;
    private ChunkEntity chunk4Promo;

    @BeforeEach
    void setUp() {
        metadataCache = new ObsidianMetadataCache();
        pageId = UUID.randomUUID();
        docId = UUID.randomUUID();

        // Chunk 1: Bảng giá Massage tại Mỹ Đình (NS01 399k, NS02 530k, NS03 670k, NS04 780k)
        chunk1PriceTable = ChunkEntity.builder()
                .id(UUID.randomUUID())
                .documentId(docId)
                .pageId(pageId)
                .headingPath(new String[]{"NÀNG SEN SPA", "BẢNG GIÁ DỊCH VỤ MASSAGE", "CHI NHÁNH HÀ NỘI - CƠ SỞ MỸ ĐÌNH"})
                .sourceBlockIds(new UUID[]{UUID.randomUUID()})
                .page(1)
                .bbox(new double[]{0, 0, 100, 100})
                .text("""
                        | Mã gói | Tên dịch vụ | Thời lượng | Giá vé (VNĐ) |
                        | NS01 | Massage body đá nóng tiêu chuẩn | 60 phút | 399.000 |
                        | NS02 | Massage body chuyên sâu | 75 phút | 530.000 |
                        | NS03 | Massage đả thông kinh lạc | 90 phút | 670.000 |
                        | NS04 | Massage Hoàng Gia VIP | 90 phút | 780.000 |
                        """)
                .build();

        // Chunk 2: FAQ so sánh gói 670k vs 780k
        chunk2Faq = ChunkEntity.builder()
                .id(UUID.randomUUID())
                .documentId(docId)
                .pageId(pageId)
                .headingPath(new String[]{"NÀNG SEN SPA", "HỎI ĐÁP THƯỜNG GẶP (FAQ)"})
                .sourceBlockIds(new UUID[]{UUID.randomUUID()})
                .page(2)
                .bbox(new double[]{0, 0, 100, 100})
                .text("""
                        Hỏi: Gói 670k và 780k khác gì nhau mà chênh giá?
                        Đáp: Gói 780k có phòng riêng tư VIP khép kín cho gội đầu dưỡng sinh tại phòng không cần di chuyển,
                        trong khi gói 670k làm tại phòng trị liệu chung cao cấp.
                        """)
                .build();

        // Chunk 3: Quy trình của gói NS01
        chunk3Process = ChunkEntity.builder()
                .id(UUID.randomUUID())
                .documentId(docId)
                .pageId(pageId)
                .headingPath(new String[]{"NÀNG SEN SPA", "QUY TRÌNH MASSAGE CƠ BẢN (NS01)"})
                .sourceBlockIds(new UUID[]{UUID.randomUUID()})
                .page(3)
                .bbox(new double[]{0, 0, 100, 100})
                .text("""
                        Quy trình các bước gói NS01:
                        Bước 1: Ngâm chân thảo dược và khai thông huyệt đạo (10 phút).
                        Bước 2: Massage lưng và vai gáy với tinh dầu ấm (30 phút).
                        Bước 3: Chườm đá nóng núi lửa và thư giãn toàn thân (20 phút).
                        """)
                .build();

        // Chunk 4: Chính sách ưu đãi combo
        chunk4Promo = ChunkEntity.builder()
                .id(UUID.randomUUID())
                .documentId(docId)
                .pageId(pageId)
                .headingPath(new String[]{"NÀNG SEN SPA", "CHÍNH SÁCH ƯU ĐÃI VÀ VOUCHER"})
                .sourceBlockIds(new UUID[]{UUID.randomUUID()})
                .page(4)
                .bbox(new double[]{0, 0, 100, 100})
                .text("""
                        Chính sách ưu đãi:
                        Giảm 10% khi mua liệu trình từ 5 buổi hoặc từ 5 voucher.
                        Miễn phí dịch vụ ngâm chân thảo dược cho khách đi theo nhóm từ 2 người.
                        """)
                .build();
    }

    @Test
    @DisplayName("Xây dựng đồ thị và chỉ mục đảo (inverted indices) từ các chunks")
    void testBuildGraphAndInvertedIndices() {
        BacklinkGraph graph = metadataCache.buildGraphFromEntities(
                List.of(chunk1PriceTable, chunk2Faq, chunk3Process, chunk4Promo)
        );

        assertThat(graph.size()).isEqualTo(4);

        // Kiểm tra serviceCodeIndex
        assertThat(graph.getServiceCodeIndex().get("NS01"))
                .contains(chunk1PriceTable.getId(), chunk3Process.getId());

        // Kiểm tra priceIndex
        assertThat(graph.getPriceIndex().get(670000L))
                .contains(chunk1PriceTable.getId(), chunk2Faq.getId());
        assertThat(graph.getPriceIndex().get(780000L))
                .contains(chunk1PriceTable.getId(), chunk2Faq.getId());
        assertThat(graph.getPriceIndex().get(399000L))
                .contains(chunk1PriceTable.getId());

        // Kiểm tra roleIndex
        assertThat(graph.getRoleIndex().get(ChunkMetadata.ROLE_PRICE_TABLE))
                .contains(chunk1PriceTable.getId());
        assertThat(graph.getRoleIndex().get(ChunkMetadata.ROLE_FAQ))
                .contains(chunk2Faq.getId());
        assertThat(graph.getRoleIndex().get(ChunkMetadata.ROLE_PROCESS))
                .contains(chunk3Process.getId());
        assertThat(graph.getRoleIndex().get(ChunkMetadata.ROLE_PROMO))
                .contains(chunk4Promo.getId());
    }

    @Test
    @DisplayName("Tự động dựng cạnh liên kết hai chiều (bidirectional edges)")
    void testBidirectionalBacklinkEdgeFormation() {
        BacklinkGraph graph = metadataCache.buildGraphFromEntities(
                List.of(chunk1PriceTable, chunk2Faq, chunk3Process, chunk4Promo)
        );

        Set<BacklinkEdge> edgesFromPriceTable = graph.getBacklinks(chunk1PriceTable.getId());

        // Chunk 1 phải có liên kết tới Chunk 3 (SERVICE_CODE: NS01)
        assertThat(edgesFromPriceTable).anyMatch(e ->
                e.targetChunkId().equals(chunk3Process.getId())
                && e.linkType() == BacklinkEdge.LinkType.SERVICE_CODE
                && "NS01".equalsIgnoreCase(e.matchKey())
        );

        // Chunk 1 phải có liên kết tới Chunk 2 (PRICE_POINT: 670000 hoặc 780000)
        assertThat(edgesFromPriceTable).anyMatch(e ->
                e.targetChunkId().equals(chunk2Faq.getId())
                && e.linkType() == BacklinkEdge.LinkType.PRICE_POINT
        );

        // Chunk 1 phải có liên kết tới Chunk 4 (PROMO_POLICY)
        assertThat(edgesFromPriceTable).anyMatch(e ->
                e.targetChunkId().equals(chunk4Promo.getId())
                && e.linkType() == BacklinkEdge.LinkType.PROMO_POLICY
        );

        // Chiều ngược lại: Chunk 3 liên kết về Chunk 1
        Set<BacklinkEdge> edgesFromProcess = graph.getBacklinks(chunk3Process.getId());
        assertThat(edgesFromProcess).anyMatch(e ->
                e.targetChunkId().equals(chunk1PriceTable.getId())
                && e.linkType() == BacklinkEdge.LinkType.SERVICE_CODE
        );

        // Chiều ngược lại: Chunk 2 liên kết về Chunk 1
        Set<BacklinkEdge> edgesFromFaq = graph.getBacklinks(chunk2Faq.getId());
        assertThat(edgesFromFaq).anyMatch(e ->
                e.targetChunkId().equals(chunk1PriceTable.getId())
                && e.linkType() == BacklinkEdge.LinkType.PRICE_POINT
        );
    }

    @Test
    @DisplayName("Mở rộng retrieval bằng backlink FAQ khi khách hỏi so sánh giá 670k vs 780k")
    void testBacklinkExpansionFaqForComparisonQuery() {
        BacklinkGraph graph = metadataCache.buildGraphFromEntities(
                List.of(chunk1PriceTable, chunk2Faq, chunk3Process, chunk4Promo)
        );
        metadataCache.registerGraphForPage(pageId, graph);

        // Giả lập top-1 retrieval chỉ lấy được bảng giá Chunk 1
        RetrievedChunk rc1 = new RetrievedChunk(
                chunk1PriceTable.getId(),
                docId,
                List.of(chunk1PriceTable.getHeadingPath()),
                List.of(),
                1,
                new double[0],
                chunk1PriceTable.getText(),
                0.85
        );
        ReciprocalRankFusion.FusedChunk fc1 = new ReciprocalRankFusion.FusedChunk(rc1, 0.85);

        String question = "gói 670k và 780k khác gì nhau mà chênh giá vậy shop";

        List<ReciprocalRankFusion.FusedChunk> expanded = metadataCache.expandBacklinks(
                List.of(fc1),
                question,
                pageId,
                List.of(docId),
                1
        );

        // Danh sách mở rộng phải chứa Chunk 2 (FAQ) ở vị trí số 2
        assertThat(expanded).hasSize(2);
        assertThat(expanded.get(0).chunk().chunkId()).isEqualTo(chunk1PriceTable.getId());
        assertThat(expanded.get(1).chunk().chunkId()).isEqualTo(chunk2Faq.getId());
        assertThat(expanded.get(1).chunk().text()).contains("VIP");
        assertThat(expanded.get(1).score()).isLessThan(expanded.get(0).score());
    }

    @Test
    @DisplayName("Mở rộng retrieval bằng backlink PROCESS khi khách hỏi quy trình mã gói NS01")
    void testBacklinkExpansionProcessForServiceCodeQuery() {
        BacklinkGraph graph = metadataCache.buildGraphFromEntities(
                List.of(chunk1PriceTable, chunk2Faq, chunk3Process, chunk4Promo)
        );
        metadataCache.registerGraphForPage(pageId, graph);

        RetrievedChunk rc1 = new RetrievedChunk(
                chunk1PriceTable.getId(),
                docId,
                List.of(chunk1PriceTable.getHeadingPath()),
                List.of(),
                1,
                new double[0],
                chunk1PriceTable.getText(),
                0.80
        );
        ReciprocalRankFusion.FusedChunk fc1 = new ReciprocalRankFusion.FusedChunk(rc1, 0.80);

        String question = "quy trình gói NS01 làm những gì";

        List<ReciprocalRankFusion.FusedChunk> expanded = metadataCache.expandBacklinks(
                List.of(fc1),
                question,
                pageId,
                List.of(docId),
                1
        );

        assertThat(expanded).hasSize(2);
        assertThat(expanded.get(1).chunk().chunkId()).isEqualTo(chunk3Process.getId());
        assertThat(expanded.get(1).chunk().text()).contains("Ngâm chân thảo dược");
    }

    @Test
    @DisplayName("Khử trùng lặp khi chunk liên kết đã nằm trong top-K ban đầu")
    void testDeduplicationWhenTargetAlreadyInTopK() {
        BacklinkGraph graph = metadataCache.buildGraphFromEntities(
                List.of(chunk1PriceTable, chunk2Faq, chunk3Process, chunk4Promo)
        );
        metadataCache.registerGraphForPage(pageId, graph);

        RetrievedChunk rc1 = new RetrievedChunk(chunk1PriceTable.getId(), docId, List.of(), List.of(), 1, new double[0], chunk1PriceTable.getText(), 0.90);
        RetrievedChunk rc2 = new RetrievedChunk(chunk2Faq.getId(), docId, List.of(), List.of(), 2, new double[0], chunk2Faq.getText(), 0.70);

        List<ReciprocalRankFusion.FusedChunk> primary = List.of(
                new ReciprocalRankFusion.FusedChunk(rc1, 0.90),
                new ReciprocalRankFusion.FusedChunk(rc2, 0.70)
        );

        List<ReciprocalRankFusion.FusedChunk> result = metadataCache.expandBacklinks(
                primary,
                "gói 670k và 780k khác gì nhau",
                pageId,
                List.of(docId),
                2
        );

        // Chunk 2 đã có sẵn nên không bị nhân bản
        long chunk2Count = result.stream().filter(c -> c.chunk().chunkId().equals(chunk2Faq.getId())).count();
        assertThat(chunk2Count).isEqualTo(1);
    }

    @Test
    @DisplayName("Xử lý an toàn khi đầu vào null hoặc rỗng")
    void testGracefulHandlingOnEmptyOrNull() {
        assertThat(metadataCache.expandBacklinks(null, null, null, null, 2)).isEmpty();
        assertThat(metadataCache.expandBacklinks(List.of(), "test", pageId, null, 2)).isEmpty();
        assertThat(metadataCache.getGraphForPage(null)).isNotNull();
        assertThat(metadataCache.getGraphForDocuments(null)).isNotNull();
        assertThat(metadataCache.buildGraphFromEntities(null).isEmpty()).isTrue();
    }
}
