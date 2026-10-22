package com.example.docmind.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Empirical Database Verification for ChunkRepositoryCustomImpl against live PostgreSQL container (port 5433).
 * Verifies to_tsvector heading_path Weight A vs text Weight B under real PostgreSQL ts_rank_cd,
 * null/empty/deeply nested headings, and adversarial queries.
 */
public class ChunkRepositoryCustomPostgresEmpiricalTest {

    private JdbcTemplate jdbc;
    private ChunkRepositoryCustomImpl repository;

    private UUID testDocId;
    private final UUID chunkQ1Heading = UUID.randomUUID();
    private final UUID chunkQ1Body = UUID.randomUUID();
    private final UUID chunkBichCau = UUID.randomUUID();
    private final UUID chunkDeepNested = UUID.randomUUID();
    private final UUID chunkNullHeading = UUID.randomUUID();
    private final UUID chunkEmptyHeading = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl("jdbc:postgresql://localhost:5433/docmind");
        ds.setUsername("docmind");
        ds.setPassword("docmind");

        this.jdbc = new JdbcTemplate(ds);
        this.repository = new ChunkRepositoryCustomImpl(jdbc, 384);

        // Always create a dedicated isolated document for empirical test run
        testDocId = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, filename, sha256, mime, pages, status, created_at, updated_at) " +
                "VALUES (?, 'test_doc_empirical.md', ?, 'text/markdown', 1, 'READY', NOW(), NOW())",
                testDocId, "sha256_emp_" + testDocId);
    }

    @AfterEach
    void tearDown() {
        if (jdbc != null) {
            jdbc.update("DELETE FROM chunks WHERE id IN (?, ?, ?, ?, ?, ?)",
                    chunkQ1Heading, chunkQ1Body, chunkBichCau, chunkDeepNested, chunkNullHeading, chunkEmptyHeading);
            if (testDocId != null) {
                jdbc.update("DELETE FROM documents WHERE id = ?", testDocId);
            }
        }
    }

    @Test
    @DisplayName("Empirical Proof: Insert with null and empty heading paths succeeds without error")
    void testInsertNullAndEmptyHeadingPaths() {
        // Chunk with null headingPath
        assertThatCode(() -> repository.insertChunk(
                chunkNullHeading, testDocId, null, null, null, 1, new double[]{0, 0, 0, 0},
                "Văn bản với headingPath null", null
        )).doesNotThrowAnyException();

        // Chunk with empty headingPath array
        assertThatCode(() -> repository.insertChunk(
                chunkEmptyHeading, testDocId, null, new String[0], null, 1, new double[]{0, 0, 0, 0},
                "Văn bản với headingPath rỗng", null
        )).doesNotThrowAnyException();

        // Verify TSV was generated properly in PostgreSQL
        String tsvNull = jdbc.queryForObject("SELECT tsv::text FROM chunks WHERE id = ?", String.class, chunkNullHeading);
        assertThat(tsvNull).contains("'null':5B");

        String tsvEmpty = jdbc.queryForObject("SELECT tsv::text FROM chunks WHERE id = ?", String.class, chunkEmptyHeading);
        assertThat(tsvEmpty).contains("'rỗng':5B");
    }

    @Test
    @DisplayName("Empirical Proof: Deeply nested (15 levels) heading path indexes all 15 levels with Weight A")
    void testInsertDeeplyNestedHeadingPath() {
        String[] deepHeading = new String[]{
                "Cấp 1", "Cấp 2", "Cấp 3", "Cấp 4", "Cấp 5",
                "Cấp 6", "Cấp 7", "Cấp 8", "Cấp 9", "Cấp 10",
                "Cấp 11", "Cấp 12", "Cấp 13", "Cấp 14", "Cấp 15"
        };

        repository.insertChunk(
                chunkDeepNested, testDocId, null, deepHeading, null, 1, new double[]{0, 0, 0, 0},
                "Nội dung thân bài ở cấp sâu", null
        );

        String tsv = jdbc.queryForObject("SELECT tsv::text FROM chunks WHERE id = ?", String.class, chunkDeepNested);
        // Verify Cấp 15 token is tagged with weight A
        assertThat(tsv).contains("'15':30A");
        assertThat(tsv).contains("'cấp':1A");
        // Verify body tokens have weight B
        assertThat(tsv).contains("'thân':33B");
    }

    @Test
    @DisplayName("Empirical Proof: Heading Path (Weight A) ranks higher than Body Text (Weight B) for branch query")
    void testWeightARanksHigherThanWeightBUnderTsRankCd() {
        // Chunk A: "Quận 1 Sài Gòn" in heading_path (Weight A)
        repository.insertChunk(
                chunkQ1Heading, testDocId, null,
                new String[]{"MASSAGE", "Cơ sở Nàng Sen Quận 1 (TP.HCM)"}, null,
                1, new double[]{0, 0, 0, 0},
                "| NS01 | Massage cơ bản | 570.000VNĐ | 70 phút |", null
        );

        // Chunk B: "Quận 1 Sài Gòn" only in body text (Weight B)
        repository.insertChunk(
                chunkQ1Body, testDocId, null,
                new String[]{"MASSAGE", "Bảng giá chung"}, null,
                1, new double[]{0, 0, 0, 0},
                "Dịch vụ massage có mặt tại Quận 1 Sài Gòn với giá 570.000VNĐ", null
        );

        // Chunk C: Other branch (Hà Nội - Bích Câu)
        repository.insertChunk(
                chunkBichCau, testDocId, null,
                new String[]{"MASSAGE", "Cơ sở Nàng Sen Bích Câu (Hà Nội)"}, null,
                1, new double[]{0, 0, 0, 0},
                "| NS01 | Massage cơ bản | 399.000VNĐ | 70 phút |", null
        );

        // Execute fulltext search for branch query: "cơ sở Quận 1 Sài Gòn có gói nào?"
        List<RetrievedChunk> results = repository.searchByFullText(
                List.of(testDocId), "cơ sở Quận 1 Sài Gòn có gói nào?", 10
        );

        assertThat(results).isNotEmpty();

        // Find scores for chunkQ1Heading and chunkQ1Body
        double scoreHeading = results.stream()
                .filter(r -> r.chunkId().equals(chunkQ1Heading))
                .mapToDouble(RetrievedChunk::score)
                .findFirst()
                .orElse(-1.0);

        double scoreBody = results.stream()
                .filter(r -> r.chunkId().equals(chunkQ1Body))
                .mapToDouble(RetrievedChunk::score)
                .findFirst()
                .orElse(-1.0);

        double scoreBichCau = results.stream()
                .filter(r -> r.chunkId().equals(chunkBichCau))
                .mapToDouble(RetrievedChunk::score)
                .findFirst()
                .orElse(-1.0);

        // Weight A in heading path must rank highest
        assertThat(scoreHeading).isGreaterThan(scoreBody);
        assertThat(scoreHeading).isGreaterThan(scoreBichCau);

        // Chunk Q1 Heading must be first in result list
        assertThat(results.get(0).chunkId()).isEqualTo(chunkQ1Heading);
    }

    @Test
    @DisplayName("Empirical Proof: Adversarial queries (SQL injection, quotes, syntax traps) execute safely")
    void testAdversarialQueriesExecuteSafely() {
        // Safe handling of SQL injection attempt
        assertThatCode(() -> repository.searchByFullText(
                List.of(testDocId), "'; DROP TABLE chunks; --", 10
        )).doesNotThrowAnyException();

        // Safe handling of single quotes and syntax symbols
        assertThatCode(() -> repository.searchByFullText(
                List.of(testDocId), "quán bà Năm's & \"VIP\" [massage] (hotline)", 10
        )).doesNotThrowAnyException();

        // Safe handling of pure whitespace / empty
        assertThat(repository.searchByFullText(List.of(testDocId), "", 10)).isEmpty();
        assertThat(repository.searchByFullText(List.of(testDocId), "   \t\n  ", 10)).isEmpty();
        assertThat(repository.searchByFullText(List.of(testDocId), null, 10)).isEmpty();
    }
}
