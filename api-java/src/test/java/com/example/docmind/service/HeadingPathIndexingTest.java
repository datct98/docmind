package com.example.docmind.service;

import com.example.docmind.client.model.CanonicalBlock;
import com.example.docmind.client.model.CanonicalDocument;
import com.example.docmind.client.model.CanonicalSource;
import com.example.docmind.entity.DocumentEntity;
import com.example.docmind.repository.BlockRepository;
import com.example.docmind.repository.ChunkRepository;
import com.example.docmind.repository.DocumentRepository;
import com.example.docmind.service.embedding.EmbeddingClient;
import com.example.docmind.service.embedding.StubEmbeddingClient;
import com.example.docmind.config.EmbeddingProperties;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HeadingPathIndexingTest {

    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private BlockRepository blockRepository;
    @Mock
    private ChunkRepository chunkRepository;
    @Mock
    private EmbeddingClient embeddingClient;

    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        documentService = new DocumentService(
                documentRepository,
                blockRepository,
                chunkRepository,
                null,
                embeddingClient,
                null,
                new ObjectMapper()
        );
    }

    @Test
    @DisplayName("formatHeadingPrefix: formats multi-level hierarchical breadcrumbs with ' > '")
    void testFormatHeadingPrefixMultiLevel() {
        String[] path = new String[]{"MASSAGE", "Cơ sở Nàng Sen Quận 1 (TP.HCM)"};
        String prefix = DocumentService.formatHeadingPrefix(path);
        assertThat(prefix).isEqualTo("[MASSAGE > Cơ sở Nàng Sen Quận 1 (TP.HCM)]");
    }

    @Test
    @DisplayName("formatHeadingPrefix: formats single-level heading")
    void testFormatHeadingPrefixSingleLevel() {
        String[] path = new String[]{"Bảng Giá Dịch Vụ 2026"};
        String prefix = DocumentService.formatHeadingPrefix(path);
        assertThat(prefix).isEqualTo("[Bảng Giá Dịch Vụ 2026]");
    }

    @Test
    @DisplayName("formatHeadingPrefix: returns empty string for null, empty array, or whitespace strings")
    void testFormatHeadingPrefixEmptyOrNull() {
        assertThat(DocumentService.formatHeadingPrefix(null)).isEmpty();
        assertThat(DocumentService.formatHeadingPrefix(new String[0])).isEmpty();
        assertThat(DocumentService.formatHeadingPrefix(new String[]{"", "   ", null})).isEmpty();
    }

    @Test
    @DisplayName("formatHeadingPrefix: trims elements and ignores empty intermediate tokens")
    void testFormatHeadingPrefixTrimming() {
        String[] path = new String[]{"  Dịch Vụ  ", "", "  Chăm Sóc Da  "};
        String prefix = DocumentService.formatHeadingPrefix(path);
        assertThat(prefix).isEqualTo("[Dịch Vụ > Chăm Sóc Da]");
    }

    @Test
    @DisplayName("DocumentService.persistResult: prefixes heading breadcrumbs to text before embedding")
    void testPersistResultEnrichesTextWithHeadingPrefix() {
        UUID docId = UUID.randomUUID();
        UUID pageId = UUID.randomUUID();
        DocumentEntity doc = DocumentEntity.builder()
                .id(docId)
                .pageId(pageId)
                .status("PARSING")
                .build();

        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));
        when(embeddingClient.embed(any())).thenReturn(new float[384]);

        CanonicalBlock tableBlock = new CanonicalBlock(
                UUID.randomUUID().toString(),
                1,
                0,
                "table",
                "| NS01 | Massage cơ bản | 570.000VNĐ |",
                "| NS01 | Massage cơ bản | 570.000VNĐ |",
                "<table>...</table>",
                null,
                List.of(0.0, 0.0, 100.0, 50.0),
                0.95,
                List.of("MASSAGE", "Cơ sở Nàng Sen Quận 1 (TP.HCM)"),
                "office",
                "1.0.0"
        );

        CanonicalDocument canonicalDoc = new CanonicalDocument(
                "doc-01",
                new CanonicalSource("01_giá.html", "hash123", "text/html", 1),
                List.of(tableBlock),
                null
        );

        documentService.persistResult(docId, canonicalDoc);

        // Capture text passed to embeddingClient.embed
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(embeddingClient, atLeastOnce()).embed(textCaptor.capture());

        String embeddedText = textCaptor.getValue();
        assertThat(embeddedText).startsWith("[MASSAGE > Cơ sở Nàng Sen Quận 1 (TP.HCM)]\n");
        assertThat(embeddedText).contains("| NS01 | Massage cơ bản | 570.000VNĐ |");

        // Verify chunkRepository.insertChunk is called with correct arguments
        verify(chunkRepository, atLeastOnce()).insertChunk(
                any(UUID.class),
                eq(docId),
                eq(pageId),
                any(String[].class),
                any(UUID[].class),
                eq(1),
                any(double[].class),
                eq("| NS01 | Massage cơ bản | 570.000VNĐ |"),
                any(float[].class)
        );
    }

    @Test
    @DisplayName("Empirical Proof: Dense vector embedding with heading prefix increases cosine similarity for heading query")
    void testCosineSimilarityEmpiricalImprovement() {
        EmbeddingProperties props = new EmbeddingProperties();
        props.setDim(384);
        StubEmbeddingClient stub = new StubEmbeddingClient(props);

        String rawBody = "| NS01 | Massage cơ bản | 570.000VNĐ | 70 phút | Full body |";
        String[] headingPath = new String[]{"MASSAGE", "Cơ sở Nàng Sen Quận 1 (TP.HCM)"};
        String prefix = DocumentService.formatHeadingPrefix(headingPath);
        String enrichedBody = prefix + "\n" + rawBody;

        float[] rawEmbedding = stub.embed(rawBody);
        float[] enrichedEmbedding = stub.embed(enrichedBody);

        String query = "cơ sở Quận 1 Sài Gòn có gói nào?";
        float[] queryEmbedding = stub.embed(query);

        double simRaw = dot(queryEmbedding, rawEmbedding);
        double simEnriched = dot(queryEmbedding, enrichedEmbedding);

        assertThat(simEnriched).isGreaterThan(simRaw);
        assertThat(simEnriched - simRaw).isGreaterThan(0.05);
    }

    private static double dot(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }
}
