package com.example.docmind.service.retrieval;

import com.example.docmind.config.ChatProperties;
import com.example.docmind.dto.ChunkMetadata;
import com.example.docmind.dto.PagePersonaConfig;
import com.example.docmind.entity.KnowledgePageEntity;
import com.example.docmind.repository.ChunkRepository;
import com.example.docmind.repository.KnowledgePageRepository;
import com.example.docmind.repository.LearnedRuleRepository;
import com.example.docmind.repository.RetrievedChunk;
import com.example.docmind.service.embedding.EmbeddingClient;
import com.example.docmind.service.metadata.ChunkMetadataExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MultiIndustryRetrievalAndMetadataC2BTest {

    private ChunkRepository chunkRepository;
    private EmbeddingClient embeddingClient;
    private ChatProperties chatProps;
    private LearnedRuleRepository learnedRuleRepository;
    private KnowledgePageRepository knowledgePageRepository;
    private RetrievalService retrievalService;

    @BeforeEach
    void setUp() {
        chunkRepository = mock(ChunkRepository.class);
        embeddingClient = mock(EmbeddingClient.class);
        chatProps = new ChatProperties();
        learnedRuleRepository = mock(LearnedRuleRepository.class);
        knowledgePageRepository = mock(KnowledgePageRepository.class);

        when(embeddingClient.embed(anyString())).thenReturn(new float[384]);
        when(learnedRuleRepository.searchSimilarActiveRules(any(), anyDouble(), anyInt(), any())).thenReturn(List.of());
        when(learnedRuleRepository.findActiveForPage(any())).thenReturn(List.of());

        retrievalService = new RetrievalService(
                chunkRepository,
                embeddingClient,
                chatProps,
                learnedRuleRepository,
                null,
                knowledgePageRepository
        );
    }

    @Test
    @DisplayName("C2-B Gate 1: Câu hỏi 'ghế văn phòng' và 'tin học văn phòng' KHÔNG bị tiêm 'bấm huyệt', 'chườm ngải cứu', 'gội đầu', 'massage'")
    void doesNotInjectSpaKeywordsIntoOfficeFurnitureOrOfficeCourseQueries() {
        UUID pageId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        RetrievedChunk chunk = new RetrievedChunk(
                UUID.randomUUID(), docId, List.of("Bảng giá nội thất"), List.of(UUID.randomUUID()),
                1, new double[]{0, 0, 10, 10}, "| Mã SP | Tên SP | Đơn giá |\n| --- | --- | --- |\n| CHAIR-01 | Ghế văn phòng công thái học | 2.450.000đ |", 0.92
        );
        when(chunkRepository.searchByVector(any(), any(), anyInt(), eq(pageId))).thenReturn(List.of(chunk));
        when(chunkRepository.searchByFullText(any(), anyString(), anyInt(), eq(pageId))).thenReturn(List.of(chunk));
        when(knowledgePageRepository.findById(pageId)).thenReturn(Optional.empty());

        List<String> nonSpaQueries = List.of(
                "ghế văn phòng",
                "tin học văn phòng",
                "ngồi máy tính học tiếng Anh lâu nên chọn bàn nào",
                "quy trình thi công ốp lát gồm những gì"
        );

        for (String q : nonSpaQueries) {
            List<String> expanded = RetrievalService.buildExpandedKeywords(q, List.of(), Map.of());
            assertThat(expanded)
                    .as("expanded_keywords cho câu hỏi '%s'", q)
                    .doesNotContain("bấm huyệt", "chườm ngải cứu", "tắm xông", "gội đầu", "massage", "trị liệu", "căng cơ");

            retrievalService.retrieveWithRules(List.of(docId), q, 5, List.of(), pageId);
        }

        ArgumentCaptor<String> fullTextCaptor = ArgumentCaptor.forClass(String.class);
        verify(chunkRepository, atLeastOnce()).searchByFullText(eq(List.of(docId)), fullTextCaptor.capture(), anyInt(), eq(pageId));

        for (String executedQuery : fullTextCaptor.getAllValues()) {
            assertThat(executedQuery.toLowerCase())
                    .doesNotContain("bấm huyệt", "chườm ngải cứu", "tắm xông", "gội đầu", "massage", "trị liệu");
        }
    }

    @Test
    @DisplayName("C2-B Gate 2: Chỉ mở rộng từ đồng nghĩa khi Page có cấu hình query_synonyms hợp lệ")
    void expandsSynonymsOnlyFromConfiguredPagePersona() {
        UUID tilePageId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        RetrievedChunk chunk = new RetrievedChunk(
                UUID.randomUUID(), docId, List.of("Bảng giá gạch"), List.of(UUID.randomUUID()),
                1, new double[]{0, 0, 10, 10}, "| SKU | Tên mẫu | Đơn giá |\n| --- | --- | --- |\n| GACH-8080 | Gạch Porcelain 80x80 | 450k/m² |", 0.95
        );
        when(chunkRepository.searchByVector(any(), any(), anyInt(), eq(tilePageId))).thenReturn(List.of(chunk));
        when(chunkRepository.searchByFullText(any(), anyString(), anyInt(), eq(tilePageId))).thenReturn(List.of(chunk));

        PagePersonaConfig tileConfig = new PagePersonaConfig(
                "HOME_FURNITURE",
                "Minh Khôi",
                "em",
                "Anh/Chị",
                "Chuyên nghiệp, kỹ thuật",
                "NONE",
                List.of(),
                List.of("gạch ốp lát", "porcelain"),
                null, null, List.of(), 10000, null, null, null, null,
                List.of(), List.of(), Map.of(), Map.of(),
                "kỹ thuật viên kho vận",
                List.of("phòng khách", "phòng tắm"),
                List.of(),
                Map.of("gạch bóng kiếng", List.of("porcelain", "xương đá chống thấm"))
        );
        KnowledgePageEntity tilePage = KnowledgePageEntity.builder()
                .id(tilePageId)
                .name("Tổng kho Gạch Hoàng Gia")
                .industry("Vật liệu xây dựng")
                .targetAudience("Chủ nhà, kiến trúc sư")
                .description("Tổng kho gạch ốp lát cao cấp")
                .personaConfigJson(tileConfig.toJson())
                .build();
        when(knowledgePageRepository.findById(tilePageId)).thenReturn(Optional.of(tilePage));

        retrievalService.retrieveWithRules(List.of(docId), "Báo giá gạch bóng kiếng 450k", 5, List.of(), tilePageId);

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(chunkRepository).searchByFullText(eq(List.of(docId)), queryCaptor.capture(), anyInt(), eq(tilePageId));
        String expandedQuery = queryCaptor.getValue();

        assertThat(expandedQuery).contains("porcelain", "xương đá chống thấm", "450.000");
        assertThat(expandedQuery).doesNotContain("bấm huyệt", "chườm ngải cứu");
    }

    @Test
    @DisplayName("C2-B Gate 3: ChunkMetadataExtractor trích xuất SKU đa ngành và nhãn chi nhánh/showroom động (zero hardcode)")
    void extractsMultiIndustrySkusAndDynamicBranches() {
        List<String> tileHeadings = List.of(
                "DANH MỤC GẠCH ỐP LÁT",
                "Showroom Gạch Hoàng Gia Hải Phòng — Địa chỉ: 120 Lê Hồng Phong, Quận Ngô Quyền"
        );
        String tileTable = """
                | SKU | Tên sản phẩm | Kích thước | Đơn giá |
                | --- | --- | --- | --- |
                | GACH-8080 | Gạch Porcelain bóng kiếng | 80x80cm | 450.000đ |
                | TILE_60X60 | Gạch Granite chống trơn | 60x60cm | 320.000đ |
                """;

        ChunkMetadata tileMeta = ChunkMetadataExtractor.extract(tileHeadings, tileTable);
        assertThat(tileMeta.chunkRole()).isEqualTo(ChunkMetadata.ROLE_PRICE_TABLE);
        assertThat(tileMeta.serviceCodes()).contains("GACH-8080", "TILE_60X60");
        assertThat(tileMeta.serviceCodes()).doesNotContain("80X80CM", "60X60CM");
        assertThat(tileMeta.branchTags()).contains("gach_hoang_gia_hai_phong", "hai_phong", "ngo_quyen");

        List<String> courseHeadings = List.of(
                "KHÓA HỌC TIẾNG ANH",
                "Trung tâm Anh Ngữ ZIM Cầu Giấy (Hà Nội)"
        );
        String courseText = "Tư vấn lộ trình khóa ielts-24b hoặc toeic_16b cam kết đầu ra.";
        ChunkMetadata courseMeta = ChunkMetadataExtractor.extract(courseHeadings, courseText);
        assertThat(courseMeta.serviceCodes()).contains("IELTS-24B", "TOEIC_16B");
        assertThat(courseMeta.branchTags()).contains("anh_ngu_zim_cau_giay", "cau_giay");
    }
}
