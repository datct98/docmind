package com.example.docmind.service.salescore;

import com.example.docmind.entity.*;
import com.example.docmind.repository.*;
import com.example.docmind.service.retrieval.RetrievalService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

/** Replays a SELECT-only snapshot through the production context builder; no DB connection. */
@EnabledIfSystemProperty(named = "sales.pageSnapshot", matches = "true")
class SalesKnowledgeSnapshotTest {
    @Test void realPageSnapshotFitsBudgetAndRetainsPricingWithoutEmbeddings() throws Exception {
        var json = new ObjectMapper();
        var snapshot = json.readTree(Files.readString(Path.of("../artifacts/sales-core-live/page-knowledge.json")));
        var page = snapshot.get("page");
        UUID pageId = UUID.fromString(page.get("id").asText());
        UUID document = UUID.randomUUID(); // snapshot contains only effective documents; preserve one test parent, no DB write.
        var pages = mock(KnowledgePageRepository.class);
        var docs = mock(DocumentRepository.class);
        var chunks = mock(ChunkRepository.class);
        var retrieval = mock(RetrievalService.class);
        when(pages.findById(pageId)).thenReturn(Optional.of(KnowledgePageEntity.builder().id(pageId)
                .name(page.get("name").asText()).personaConfigJson(page.get("persona").toString()).build()));
        when(docs.findByPageIdOrderByCreatedAtDesc(pageId)).thenReturn(List.of(DocumentEntity.builder()
                .id(document).pageId(pageId).status("READY").active(true).build()));
        List<ChunkEntity> rows = new ArrayList<>();
        for (var chunk : snapshot.get("chunks")) rows.add(ChunkEntity.builder().id(UUID.fromString(chunk.get("id").asText()))
                .documentId(document).pageId(pageId).headingPath(json.treeToValue(chunk.get("title"), String[].class))
                .text(chunk.get("text").asText()).build());
        rows.sort(Comparator.comparing(ChunkEntity::getId));
        when(chunks.findByDocumentIdIn(anyList(), any(org.springframework.data.domain.Pageable.class))).thenReturn(rows);
        var source = new SalesKnowledgeSource(pages, docs, chunks, retrieval, "../config/sales/pages");
        var input = source.load(pageId, null, List.of(), "Giá", "");
        assertThat(input.evidence()).isNotEmpty();
        assertThat(input.evidence().stream().mapToInt(e -> e.text().length()).sum()).isLessThanOrEqualTo(18000);
        assertThat(input.evidence()).anyMatch(e -> e.text().contains("399"));
        assertThat(input.industryCode()).isEqualTo("BEAUTY_SPA");
        Files.writeString(Path.of("../artifacts/sales-core-live/page-input.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(input));
        System.out.println("PAGE_SNAPSHOT chunks=" + rows.size() + " selected=" + input.evidence().size() + " truncated=" + input.contextTruncated());
    }
}
