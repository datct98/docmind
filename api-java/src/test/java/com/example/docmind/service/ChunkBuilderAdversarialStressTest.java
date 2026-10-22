package com.example.docmind.service;

import com.example.docmind.client.model.CanonicalBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkBuilderAdversarialStressTest {

    private static CanonicalBlock tableBlock(String id, List<String> hp, String markdown) {
        return new CanonicalBlock(
                id, 1, 0, "table", markdown, markdown, "<table></table>",
                null, List.of(0.0, 0.0, 100.0, 200.0), 1.0, hp, "office", "1.0.0"
        );
    }

    private static CanonicalBlock paragraphBlock(String id, List<String> hp, String text) {
        return new CanonicalBlock(
                id, 1, 0, "paragraph", text, text, "<p>" + text + "</p>",
                null, List.of(0.0, 0.0, 100.0, 200.0), 1.0, hp, "office", "1.0.0"
        );
    }

    @Test
    @DisplayName("Massive table splitting: 80 rows across multiple chunks; 100% chunks retain header")
    void massiveTableSplittingRetainsHeaderInAllSubChunks() {
        UUID docId = UUID.randomUUID();
        String headerLine = "| STT | Tên Dịch Vụ | Thời Lượng | Đơn Giá | Ghi Chú |";
        String delimLine = "| :--- | :--- | :---: | ---: | :--- |";
        String expectedHeader = headerLine + "\n" + delimLine;

        StringBuilder md = new StringBuilder();
        md.append(expectedHeader).append("\n");
        for (int i = 1; i <= 80; i++) {
            md.append("| ").append(i)
              .append(" | Dịch vụ chăm sóc phục hồi chuyên sâu số ").append(i)
              .append(" | ").append(30 + (i % 4) * 15).append(" phút")
              .append(" | ").append(200000 + i * 10000).append(" VNĐ")
              .append(" | Ưu đãi đặc biệt áp dụng toàn hệ thống ").append(i)
              .append(" |\n");
        }
        String tableText = md.toString();

        CanonicalBlock tb = tableBlock(UUID.randomUUID().toString(), List.of("Bảng Giá Nàng Sen"), tableText);

        List<ChunkBuilder.BuiltChunk> chunks = ChunkBuilder.build(docId, List.of(tb));

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(5);

        int totalDataRows = 0;
        for (int idx = 0; idx < chunks.size(); idx++) {
            ChunkBuilder.BuiltChunk c = chunks.get(idx);

            // 1. Every chunk must retain exact markdown header
            assertThat(c.text()).as("Chunk " + idx + " must start with table header")
                    .startsWith(expectedHeader);

            // 2. Every chunk must obey MAX_CHUNK_CHARS limit
            assertThat(c.text().length()).as("Chunk " + idx + " exceeds MAX_CHUNK_CHARS")
                    .isLessThanOrEqualTo(ChunkBuilder.MAX_CHUNK_CHARS);

            // 3. Count data rows (excluding 2 header lines)
            String[] lines = c.text().split("\n");
            int dataRowsInChunk = lines.length - 2;
            assertThat(dataRowsInChunk).isGreaterThan(0);
            totalDataRows += dataRowsInChunk;

            // 4. Metadata inheritance
            assertThat(c.headingPath()).containsExactly("Bảng Giá Nàng Sen");
            assertThat(c.page()).isEqualTo(1);
        }

        // All 80 rows must be preserved across the chunks without data loss
        assertThat(totalDataRows).isEqualTo(80);
    }

    @Test
    @DisplayName("Table with ragged rows (inconsistent pipe column count)")
    void tableWithRaggedRowsPreservesHeaderAndChunks() {
        UUID docId = UUID.randomUUID();
        String headerLine = "| Cột 1 | Cột 2 | Cột 3 |";
        String delimLine = "|---|---|---|";
        String expectedHeader = headerLine + "\n" + delimLine;

        StringBuilder md = new StringBuilder();
        md.append(expectedHeader).append("\n");
        for (int i = 1; i <= 60; i++) {
            if (i % 3 == 0) {
                // Ragged row with 1 extra cell
                md.append("| Extra ").append(i).append(" | Row ").append(i).append(" | Val ").append(i).append(" | Overflow |\n");
            } else if (i % 3 == 1) {
                // Ragged row with 1 missing cell
                md.append("| Row ").append(i).append(" | Val ").append(i).append(" |\n");
            } else {
                md.append("| Row ").append(i).append(" | Normal ").append(i).append(" | Val ").append(i).append(" |\n");
            }
        }

        CanonicalBlock tb = tableBlock(UUID.randomUUID().toString(), List.of("Ragged"), md.toString());
        List<ChunkBuilder.BuiltChunk> chunks = ChunkBuilder.build(docId, List.of(tb));

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
        for (ChunkBuilder.BuiltChunk c : chunks) {
            assertThat(c.text()).startsWith(expectedHeader);
            assertThat(c.text().length()).isLessThanOrEqualTo(ChunkBuilder.MAX_CHUNK_CHARS);
        }
    }

    @Test
    @DisplayName("Oversized paragraph containing table with leading and trailing blank lines")
    void tableWithLeadingAndTrailingBlankLines() {
        UUID docId = UUID.randomUUID();
        String headerLine = "| Mã DV | Tên Dịch Vụ | Giá Niêm Yết |";
        String delimLine = "| --- | :---: | ---: |";
        String expectedHeader = headerLine + "\n" + delimLine;

        StringBuilder md = new StringBuilder("\n\n   \n");
        md.append(expectedHeader).append("\n");
        for (int i = 1; i <= 55; i++) {
            md.append("| DV").append(i).append(" | Gói trị liệu thảo dược ").append(i)
              .append(" | ").append(300000 + i * 15000).append(" VNĐ |\n");
        }
        md.append("\n   \n\n");

        CanonicalBlock pBlock = paragraphBlock(UUID.randomUUID().toString(), List.of("Catalog"), md.toString());
        List<ChunkBuilder.BuiltChunk> chunks = ChunkBuilder.build(docId, List.of(pBlock));

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
        for (ChunkBuilder.BuiltChunk c : chunks) {
            assertThat(c.text()).startsWith(expectedHeader);
            assertThat(c.text().length()).isLessThanOrEqualTo(ChunkBuilder.MAX_CHUNK_CHARS);
        }
    }

    @Test
    @DisplayName("Single row longer than MAX_CHUNK_CHARS in table does not crash or lose headers")
    void tableWithSingleRowExceedingMaxCharsDoesNotCrash() {
        UUID docId = UUID.randomUUID();
        String header = "| Cột 1 | Cột 2 |\n|---|---|";
        String massiveRow = "| Dữ liệu cực dài " + "X".repeat(1500) + " | Kết thúc |";
        String normalRow = "| Row bình thường | Giá trị |";

        String tableText = header + "\n" + massiveRow + "\n" + normalRow;

        CanonicalBlock tb = tableBlock(UUID.randomUUID().toString(), List.of("BigRow"), tableText);
        List<ChunkBuilder.BuiltChunk> chunks = ChunkBuilder.build(docId, List.of(tb));

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
        assertThat(chunks.get(0).text()).startsWith(header);
        assertThat(chunks.get(1).text()).startsWith(header);
    }
}
