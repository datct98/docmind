package com.example.docmind.service;

import com.example.docmind.client.model.CanonicalBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test dựng chunk:
 * - nhóm theo (page, heading_path), union bbox, kế thừa block ids.
 * - giới hạn MAX_CHUNK_CHARS = 1200 và chia nhỏ nhóm / block vượt kích thước.
 * - bảo tồn cú pháp bảng Markdown (| ... |) qua b.markdown().
 */
class ChunkBuilderTest {

    private static CanonicalBlock block(String id, int page, int order, List<String> hp,
                                        double x0, double y0, double x1, double y1, String text) {
        return new CanonicalBlock(id, page, order, "paragraph", text, text, "<p>" + text + "</p>",
                null, List.of(x0, y0, x1, y1), 0.9, hp, "pymupdf", "pymupdf-1.24.0");
    }

    private static CanonicalBlock tableBlock(String id, int page, int order, List<String> hp,
                                             double x0, double y0, double x1, double y1,
                                             String text, String markdown) {
        return new CanonicalBlock(id, page, order, "table", text, markdown, "<table>...</table>",
                null, List.of(x0, y0, x1, y1), 0.95, hp, "office", "office-1.0.0");
    }

    @Test
    void groupsSamePageAndHeadingIntoOneChunkWithUnionBbox() {
        UUID docId = UUID.randomUUID();
        String b1 = UUID.randomUUID().toString();
        String b2 = UUID.randomUUID().toString();
        List<CanonicalBlock> blocks = List.of(
                block(b1, 1, 0, List.of("Intro"), 10, 10, 100, 20, "Hello"),
                block(b2, 1, 1, List.of("Intro"), 10, 30, 120, 40, "World")
        );

        List<ChunkBuilder.BuiltChunk> chunks = ChunkBuilder.build(docId, blocks);

        assertThat(chunks).hasSize(1);
        ChunkBuilder.BuiltChunk c = chunks.get(0);
        assertThat(c.documentId()).isEqualTo(docId);
        assertThat(c.page()).isEqualTo(1);
        assertThat(c.headingPath()).containsExactly("Intro");
        assertThat(c.sourceBlockIds()).containsExactly(UUID.fromString(b1), UUID.fromString(b2));
        assertThat(c.bbox()).containsExactly(10.0, 10.0, 120.0, 40.0);
        assertThat(c.text()).isEqualTo("Hello\nWorld");
    }

    @Test
    void splitsOnPageOrHeadingChange() {
        UUID docId = UUID.randomUUID();
        List<CanonicalBlock> blocks = List.of(
                block(UUID.randomUUID().toString(), 1, 0, List.of("A"), 0, 0, 1, 1, "a"),
                block(UUID.randomUUID().toString(), 1, 1, List.of("B"), 0, 0, 1, 1, "b"), // heading đổi
                block(UUID.randomUUID().toString(), 2, 2, List.of("B"), 0, 0, 1, 1, "c")  // page đổi
        );

        List<ChunkBuilder.BuiltChunk> chunks = ChunkBuilder.build(docId, blocks);
        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).headingPath()).containsExactly("A");
        assertThat(chunks.get(1).headingPath()).containsExactly("B");
        assertThat(chunks.get(1).page()).isEqualTo(1);
        assertThat(chunks.get(2).page()).isEqualTo(2);
    }

    @Test
    void emptyBlocksProduceNoChunks() {
        assertThat(ChunkBuilder.build(UUID.randomUUID(), List.of())).isEmpty();
    }

    @Test
    void preservesMarkdownTableSyntaxOverPlainText() {
        UUID docId = UUID.randomUUID();
        String bId = UUID.randomUUID().toString();
        String plain = "Mã SP Tên SP Đơn giá";
        String mdTable = "| Mã SP | Tên SP | Đơn giá |\n|---|---|---|\n| SP01 | Laptop | 20.000.000 |";

        CanonicalBlock tb = tableBlock(bId, 1, 0, List.of("Bảng Giá"), 0, 0, 200, 100, plain, mdTable);

        List<ChunkBuilder.BuiltChunk> chunks = ChunkBuilder.build(docId, List.of(tb));
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).contains("| Mã SP | Tên SP | Đơn giá |");
        assertThat(chunks.get(0).text()).contains("| SP01 | Laptop | 20.000.000 |");
    }

    @Test
    void splitsOversizedGroupWhenExceedingMaxChars() {
        UUID docId = UUID.randomUUID();
        String textPart = "A".repeat(500); // 3 blocks * 500 = 1500 > 1200
        String b1 = UUID.randomUUID().toString();
        String b2 = UUID.randomUUID().toString();
        String b3 = UUID.randomUUID().toString();

        List<CanonicalBlock> blocks = List.of(
                block(b1, 1, 0, List.of("Chính sách"), 0, 0, 10, 10, textPart),
                block(b2, 1, 1, List.of("Chính sách"), 0, 10, 10, 20, textPart),
                block(b3, 1, 2, List.of("Chính sách"), 0, 20, 10, 30, textPart)
        );

        List<ChunkBuilder.BuiltChunk> chunks = ChunkBuilder.build(docId, blocks);
        assertThat(chunks.size()).isGreaterThanOrEqualTo(2);
        for (ChunkBuilder.BuiltChunk c : chunks) {
            assertThat(c.text().length()).isLessThanOrEqualTo(ChunkBuilder.MAX_CHUNK_CHARS);
            assertThat(c.headingPath()).containsExactly("Chính sách");
            assertThat(c.page()).isEqualTo(1);
        }
    }

    @Test
    void splitsSingleOversizedTextBlockPreservingHeadingAndSourceBlockIds() {
        UUID docId = UUID.randomUUID();
        String singleBigText = ("Đoạn văn này chứa nội dung chính sách rất dài cần phân tách.\n\n").repeat(35);
        assertThat(singleBigText.length()).isGreaterThan(ChunkBuilder.MAX_CHUNK_CHARS);

        String bId = UUID.randomUUID().toString();
        CanonicalBlock bigBlock = block(bId, 2, 0, List.of("Quy định"), 10, 20, 300, 400, singleBigText);

        List<ChunkBuilder.BuiltChunk> chunks = ChunkBuilder.build(docId, List.of(bigBlock));
        assertThat(chunks.size()).isGreaterThanOrEqualTo(2);

        for (ChunkBuilder.BuiltChunk c : chunks) {
            assertThat(c.text().length()).isLessThanOrEqualTo(ChunkBuilder.MAX_CHUNK_CHARS);
            assertThat(c.headingPath()).containsExactly("Quy định");
            assertThat(c.page()).isEqualTo(2);
            assertThat(c.sourceBlockIds()).containsExactly(UUID.fromString(bId));
        }
    }

    @Test
    void splitsSingleOversizedTableBlockRetainingHeadersOnEachSplit() {
        UUID docId = UUID.randomUUID();
        StringBuilder md = new StringBuilder("| Cột 1 | Cột 2 | Cột 3 |\n|---|---|---|\n");
        for (int i = 1; i <= 60; i++) {
            md.append("| Dòng ").append(i).append(" dữ liệu chi tiết | Giá trị phân loại ").append(i).append(" | Ghi chú ").append(i).append(" |\n");
        }
        String tableText = md.toString();
        assertThat(tableText.length()).isGreaterThan(ChunkBuilder.MAX_CHUNK_CHARS);

        String bId = UUID.randomUUID().toString();
        CanonicalBlock bigTable = tableBlock(bId, 1, 0, List.of("Danh mục"), 0, 0, 500, 500, "text", tableText);

        List<ChunkBuilder.BuiltChunk> chunks = ChunkBuilder.build(docId, List.of(bigTable));
        assertThat(chunks.size()).isGreaterThanOrEqualTo(2);

        for (ChunkBuilder.BuiltChunk c : chunks) {
            assertThat(c.text().length()).isLessThanOrEqualTo(ChunkBuilder.MAX_CHUNK_CHARS);
            assertThat(c.text()).startsWith("| Cột 1 | Cột 2 | Cột 3 |\n|---|---|---|");
            assertThat(c.sourceBlockIds()).containsExactly(UUID.fromString(bId));
        }
    }

    @Test
    void splitTableTextWithLeadingEmptyLinesPreservesHeaders() {
        StringBuilder md = new StringBuilder("\n\n| Mã SP | Tên Sản Phẩm | Giá Niêm Yết |\n|---|---|---|\n");
        for (int i = 1; i <= 60; i++) {
            md.append("| SP").append(String.format("%03d", i)).append(" | Gói dịch vụ cao cấp số ").append(i)
              .append(" | ").append(500000 + i * 10000).append(" VNĐ |\n");
        }
        String tableText = md.toString();
        List<String> parts = ChunkBuilder.splitTableText(tableText, ChunkBuilder.MAX_CHUNK_CHARS);

        assertThat(parts.size()).isGreaterThanOrEqualTo(2);
        for (String part : parts) {
            assertThat(part).startsWith("| Mã SP | Tên Sản Phẩm | Giá Niêm Yết |\n|---|---|---|");
            assertThat(part.length()).isLessThanOrEqualTo(ChunkBuilder.MAX_CHUNK_CHARS);
        }
    }

    @Test
    void splitTableTextWithAlignmentDelimitersPreservesHeaders() {
        StringBuilder md = new StringBuilder("| Mã SP | Tên Sản Phẩm | Giá Niêm Yết |\n| :--- | :---: | ---: |\n");
        for (int i = 1; i <= 60; i++) {
            md.append("| SP").append(String.format("%03d", i)).append(" | Gói dịch vụ căn bản số ").append(i)
              .append(" | ").append(300000 + i * 5000).append(" VNĐ |\n");
        }
        String tableText = md.toString();
        List<String> parts = ChunkBuilder.splitTableText(tableText, ChunkBuilder.MAX_CHUNK_CHARS);

        assertThat(parts.size()).isGreaterThanOrEqualTo(2);
        for (String part : parts) {
            assertThat(part).startsWith("| Mã SP | Tên Sản Phẩm | Giá Niêm Yết |\n| :--- | :---: | ---: |");
            assertThat(part.length()).isLessThanOrEqualTo(ChunkBuilder.MAX_CHUNK_CHARS);
        }
    }

    @Test
    void isMarkdownTableHeaderAndDelimiterRejectsTextWithHyphens() {
        // Line 1 is data containing a hyphen, NOT a delimiter
        boolean result = ChunkBuilder.isMarkdownTableHeaderAndDelimiter(
                "| Mã Gói | Tên Dịch Vụ |",
                "| NS-01 | Liệu trình Non-destructive |"
        );
        assertThat(result).isFalse();

        // Valid markdown delimiter
        boolean valid = ChunkBuilder.isMarkdownTableHeaderAndDelimiter(
                "| Mã Gói | Tên Dịch Vụ |",
                "|---|---|"
        );
        assertThat(valid).isTrue();
    }

    @Test
    void splitsSingleOversizedParagraphContainingMarkdownTableWithHeaders() {
        UUID docId = UUID.randomUUID();
        StringBuilder md = new StringBuilder("| Danh mục | Tên gói | Giá |\n|---|---|---|\n");
        for (int i = 1; i <= 60; i++) {
            md.append("| Massage | Gói massage body toàn thân ").append(i)
              .append(" | ").append(500000 + i * 10000).append(" VNĐ |\n");
        }
        String text = md.toString();
        String bId = UUID.randomUUID().toString();
        // Block is marked as "paragraph" but content is a Markdown table
        CanonicalBlock pBlock = block(bId, 1, 0, List.of("Bảng Giá"), 0, 0, 100, 100, text);

        List<ChunkBuilder.BuiltChunk> chunks = ChunkBuilder.build(docId, List.of(pBlock));
        assertThat(chunks.size()).isGreaterThanOrEqualTo(2);

        for (ChunkBuilder.BuiltChunk c : chunks) {
            assertThat(c.text()).startsWith("| Danh mục | Tên gói | Giá |\n|---|---|---|");
            assertThat(c.text().length()).isLessThanOrEqualTo(ChunkBuilder.MAX_CHUNK_CHARS);
        }
    }

    @Test
    void decomposesMultiRowFaqAndPricingTableIntoIsolatedAtomicRowNodes() {
        UUID docId = UUID.randomUUID();
        String bId = UUID.randomUUID().toString();
        String faqTable = "| Câu hỏi khách hàng | Câu trả lời chuẩn |\n"
                + "| --- | --- |\n"
                + "| Em hay đau đầu | Nếu mình thường xuyên đau đầu do áp lực công việc thì liệu trình dưỡng sinh rất phù hợp ạ. |\n"
                + "| Em nặng đầu | Tình trạng nặng đầu thường gặp ở khách căng thẳng, thiếu ngủ. |\n"
                + "| Tôi bị mỏi cổ vai gáy | Kỹ thuật viên sẽ ấn huyệt giải cơ và chườm nóng ngải cứu giúp nhẹ vai rõ rệt. |";

        CanonicalBlock tb = tableBlock(bId, 1, 0, List.of("FAQ Nàng Sen", "Triệu chứng"), 0, 0, 200, 100, faqTable, faqTable);

        List<ChunkBuilder.BuiltChunk> chunks = ChunkBuilder.build(docId, List.of(tb));

        // 3 atomic FAQ row nodes so "Em hay đau đầu" never contaminates "Tôi bị mỏi cổ vai gáy"
        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).text()).contains("Em hay đau đầu").doesNotContain("mỏi cổ vai gáy");
        assertThat(chunks.get(1).text()).contains("Em nặng đầu").doesNotContain("Em hay đau đầu");
        assertThat(chunks.get(2).text()).contains("Tôi bị mỏi cổ vai gáy").doesNotContain("Em hay đau đầu");

        String formattedForLlm = ChunkBuilder.formatChunkAsLlamaIndexNode(chunks.get(2).text(), chunks.get(2).headingPath());
        assertThat(formattedForLlm)
                .contains("[FAQ Nàng Sen > Triệu chứng]")
                .contains("Câu hỏi khách hàng: Tôi bị mỏi cổ vai gáy")
                .contains("Câu trả lời chuẩn: Kỹ thuật viên sẽ ấn huyệt giải cơ")
                .doesNotContain("| --- |");
    }

    @Test
    void filtersOutFakeHeaderRowInHeaderlessDocxFaqTable() {
        String headerlessDocxChunk = "| Em hay đau đầu | Nếu mình thường xuyên đau đầu hoặc cảm giác đầu nặng do áp lực công việc thì các liệu trình dưỡng sinh và trị liệu đầu - cổ - vai - gáy bên em sẽ khá phù hợp ạ 🌿 |\n"
                + "| --- | --- |\n"
                + "| Em nặng đầu | Dạ tình trạng nặng đầu thường gặp ở khách stress, thiếu ngủ hoặc làm việc nhiều với máy tính ạ. |\n"
                + "| Em đau cổ vai gáy | Nếu mình đang căng cứng vùng cổ vai gáy thì bên Nàng Sen thường sẽ phù hợp với các gói phục hồi trị liệu chuyên sâu hơn ạ. |\n"
                + "| Em mất ngủ | Dạ nếu mình thường xuyên khó ngủ hoặc ngủ không sâu thì các liệu trình đầu - cổ - vai - gáy kết hợp dưỡng sinh bên em sẽ khá phù hợp ạ. |";

        String formatted = ChunkBuilder.formatChunkAsLlamaIndexNode(
                headerlessDocxChunk,
                new String[]{"FAQ"},
                "Thi thoảng bị | Tôi bị mỏi vai và thi thoảng hơi đau nhức"
        );

        assertThat(formatted)
                .contains("Tình huống: Em đau cổ vai gáy")
                .doesNotContain("Em hay đau đầu")
                .doesNotContain("Em nặng đầu")
                .doesNotContain("| --- |");
    }
}
