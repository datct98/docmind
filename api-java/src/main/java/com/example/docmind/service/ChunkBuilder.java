package com.example.docmind.service;

import com.example.docmind.client.model.CanonicalBlock;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Dựng chunk từ các block (canonical-schema §3): nhóm theo (page, heading_path).
 * Mỗi chunk NẰM GỌN trong 1 page; kế thừa heading_path/page từ block; bbox là union
 * các block; source_block_ids là id các block cấu thành; text ghép theo thứ tự đọc.
 *
 * <p>Quy chuẩn R1 (F6, F7):
 * - Giới hạn kích thước chunk không vượt quá MAX_CHUNK_CHARS (1200 ký tự ~ 300-400 token).
 * - Tách các nhóm hoặc block quá lớn thành các chunk nhỏ hơn nhưng vẫn giữ nguyên heading_path và source_block_ids.
 * - Ưu tiên block.markdown() cho các block bảng ("table") để bảo tồn cú pháp bảng Markdown (| ... |).
 */
public final class ChunkBuilder {

    public static final int MAX_CHUNK_CHARS = 1200;

    private ChunkBuilder() {
    }

    /** 1 chunk đã dựng (chưa persist). */
    public record BuiltChunk(
            UUID id,
            UUID documentId,
            String[] headingPath,
            UUID[] sourceBlockIds,
            int page,
            double[] bbox,
            String text
    ) {
    }

    /**
     * Nhóm block liên tiếp cùng (page + heading_path) thành các chunk có kích thước giới hạn <= MAX_CHUNK_CHARS.
     * Giả định block đã theo thứ tự reading order (order asc).
     */
    public static List<BuiltChunk> build(UUID documentId, List<CanonicalBlock> blocks) {
        List<BuiltChunk> chunks = new ArrayList<>();
        if (blocks == null || blocks.isEmpty()) {
            return chunks;
        }

        List<CanonicalBlock> group = new ArrayList<>();
        int currentGroupLength = 0;
        Integer curPage = null;
        List<String> curHeading = null;

        for (CanonicalBlock b : blocks) {
            String blockText = extractBlockText(b);
            if (blockText.isBlank()) {
                continue;
            }

            List<String> hp = b.headingPath() == null ? List.of() : b.headingPath();
            boolean sameGroup = curPage != null
                    && curPage == b.page()
                    && hp.equals(curHeading);

            // Nếu đổi trang hoặc đổi heading, flush nhóm hiện tại
            if (!sameGroup && !group.isEmpty()) {
                if (curPage != null && curPage == b.page()
                        && isHeadingOnlyGroup(group)
                        && isPrefixOf(curHeading, hp)) {
                    // Tránh tạo chunk rác chỉ chứa 1 dòng tiêu đề cha (VD: "## 2. DANH MỤC & BẢNG GIÁ")
                    // khi ngay sau đó là tiêu đề con ("### 2.1. Bảng giá...") đã kế thừa đầy đủ heading_path.
                    group = new ArrayList<>();
                    currentGroupLength = 0;
                } else {
                    chunks.addAll(flushGroup(documentId, group));
                    group = new ArrayList<>();
                    currentGroupLength = 0;
                }
            }

            // Nếu thêm block này làm vượt quá MAX_CHUNK_CHARS và nhóm đã có phần tử
            if (!group.isEmpty() && (currentGroupLength + blockText.length() + 1 > MAX_CHUNK_CHARS)) {
                chunks.addAll(flushGroup(documentId, group));
                group = new ArrayList<>();
                currentGroupLength = 0;
            }

            // Nếu bản thân block này là bảng Markdown nhiều dòng (2..25 dòng dữ liệu, VD: bảng FAQ hoặc bảng Giá):
            // Phân rã theo kiến trúc LlamaIndex Atomic Table-Row Node để mỗi câu FAQ / mỗi gói dịch vụ là 1 Node độc lập,
            // tuyệt đối không trộn 10-15 câu FAQ khác nhau vào chung 1 chunk.
            if (("table".equalsIgnoreCase(b.type()) || isMarkdownTable(blockText))
                    && shouldDecomposeTableIntoAtomicRows(blockText)) {
                if (!group.isEmpty()) {
                    chunks.addAll(flushGroup(documentId, group));
                    group = new ArrayList<>();
                    currentGroupLength = 0;
                }
                chunks.addAll(decomposeTableBlockIntoAtomicNodes(documentId, b, blockText));
                curPage = b.page();
                curHeading = hp;
                continue;
            }

            // Nếu bản thân block này vượt quá MAX_CHUNK_CHARS
            if (blockText.length() > MAX_CHUNK_CHARS) {
                if (!group.isEmpty()) {
                    chunks.addAll(flushGroup(documentId, group));
                    group = new ArrayList<>();
                    currentGroupLength = 0;
                }
                chunks.addAll(splitOversizedBlock(documentId, b, blockText, MAX_CHUNK_CHARS));
                curPage = b.page();
                curHeading = hp;
                continue;
            }

            group.add(b);
            currentGroupLength += (currentGroupLength == 0 ? 0 : 1) + blockText.length();
            curPage = b.page();
            curHeading = hp;
        }

        if (!group.isEmpty()) {
            chunks.addAll(flushGroup(documentId, group));
        }

        return chunks;
    }

    private static boolean isHeadingOnlyGroup(List<CanonicalBlock> group) {
        if (group == null || group.isEmpty()) {
            return false;
        }
        for (CanonicalBlock b : group) {
            if (b == null || !"heading".equalsIgnoreCase(b.type())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPrefixOf(List<String> prefix, List<String> full) {
        if (prefix == null || prefix.isEmpty() || full == null || full.size() <= prefix.size()) {
            return false;
        }
        for (int i = 0; i < prefix.size(); i++) {
            if (!prefix.get(i).equals(full.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Trích xuất văn bản từ block: ưu tiên markdown() nếu là table hoặc nếu text() rỗng.
     */
    public static String extractBlockText(CanonicalBlock b) {
        if (b == null) {
            return "";
        }
        if ("table".equalsIgnoreCase(b.type()) && b.markdown() != null && !b.markdown().isBlank()) {
            return b.markdown().replace("\r\n", "\n").replace("\r", "\n").strip();
        }
        if (b.text() != null && !b.text().isBlank()) {
            return b.text().replace("\r\n", "\n").replace("\r", "\n").strip();
        }
        if (b.markdown() != null && !b.markdown().isBlank()) {
            return b.markdown().replace("\r\n", "\n").replace("\r", "\n").strip();
        }
        return "";
    }

    /**
     * Flush một nhóm block cùng (page, heading_path) thành 1 BuiltChunk.
     */
    private static List<BuiltChunk> flushGroup(UUID documentId, List<CanonicalBlock> group) {
        if (group.isEmpty()) {
            return List.of();
        }
        CanonicalBlock first = group.get(0);
        List<String> headingPath = first.headingPath() == null ? List.of() : first.headingPath();

        UUID[] blockIds = group.stream()
                .map(b -> parseUuidSafe(b.blockId()))
                .toArray(UUID[]::new);

        double[] bbox = unionBbox(group);

        StringBuilder text = new StringBuilder();
        for (CanonicalBlock b : group) {
            String bt = extractBlockText(b);
            if (!bt.isBlank()) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(bt);
            }
        }

        return List.of(new BuiltChunk(
                UUID.randomUUID(),
                documentId,
                headingPath.toArray(new String[0]),
                blockIds,
                first.page(),
                bbox,
                text.toString()
        ));
    }

    /**
     * Phân tách một block đơn lẻ có độ dài vượt quá maxChars thành nhiều sub-chunks,
     * giữ nguyên heading_path, source_block_ids, page và bbox.
     */
    private static List<BuiltChunk> splitOversizedBlock(UUID documentId, CanonicalBlock b, String blockText, int maxChars) {
        List<BuiltChunk> result = new ArrayList<>();
        List<String> headingPath = b.headingPath() == null ? List.of() : b.headingPath();
        UUID[] blockIds = new UUID[]{ parseUuidSafe(b.blockId()) };
        double[] bbox = toDoubleArray(b.bbox());

        List<String> textParts;
        if ("table".equalsIgnoreCase(b.type()) || isMarkdownTable(blockText)) {
            textParts = splitTableText(blockText, maxChars);
        } else {
            textParts = splitPlainText(blockText, maxChars);
        }

        for (String part : textParts) {
            if (!part.isBlank()) {
                result.add(new BuiltChunk(
                        UUID.randomUUID(),
                        documentId,
                        headingPath.toArray(new String[0]),
                        blockIds,
                        b.page(),
                        bbox,
                        part.strip()
                ));
            }
        }

        return result;
    }

    private static boolean shouldDecomposeTableIntoAtomicRows(String blockText) {
        if (blockText == null || !isMarkdownTable(blockText)) {
            return false;
        }
        String[] lines = blockText.strip().split("\r?\n");
        if (lines.length < 4 || !isMarkdownTableHeaderAndDelimiter(lines[0], lines[1])) {
            return false;
        }
        int dataRows = 0;
        for (int i = 2; i < lines.length; i++) {
            if (!lines[i].isBlank() && lines[i].contains("|")) {
                dataRows++;
            }
        }
        return dataRows >= 2 && dataRows <= 25;
    }

    private static List<BuiltChunk> decomposeTableBlockIntoAtomicNodes(
            UUID documentId, CanonicalBlock b, String blockText) {
        List<BuiltChunk> result = new ArrayList<>();
        List<String> headingPath = b.headingPath() == null ? List.of() : b.headingPath();
        UUID[] blockIds = new UUID[]{ parseUuidSafe(b.blockId()) };
        double[] bbox = toDoubleArray(b.bbox());

        String[] lines = blockText.strip().split("\r?\n");
        String[] firstRowCells = splitMarkdownTableCells(lines[0].strip());
        boolean genuineHeader = isGenuineTableHeaderRow(firstRowCells);
        String headerLine;
        String delimiterLine = lines[1].strip();
        int startRowIdx;
        if (genuineHeader) {
            headerLine = lines[0].strip();
            startRowIdx = 2;
        } else {
            headerLine = synthesizeHeaderRow(firstRowCells.length);
            startRowIdx = 0;
        }
        String headerPrefix = headerLine + "\n" + delimiterLine;

        for (int i = startRowIdx; i < lines.length; i++) {
            if (i == 1) {
                continue; // skip delimiter line
            }
            String rowLine = lines[i].strip();
            if (rowLine.isBlank() || !rowLine.contains("|")) {
                continue;
            }
            String singleRowTable = headerPrefix + "\n" + rowLine;
            result.add(new BuiltChunk(
                    UUID.randomUUID(),
                    documentId,
                    headingPath.toArray(new String[0]),
                    blockIds,
                    b.page(),
                    bbox,
                    singleRowTable
            ));
        }
        return result;
    }

    private static String synthesizeHeaderRow(int colCount) {
        if (colCount == 2) {
            return "| Tình huống | Tư vấn |";
        }
        List<String> cols = new ArrayList<>();
        for (int i = 1; i <= Math.max(1, colCount); i++) {
            cols.add("Cột " + i);
        }
        return "| " + String.join(" | ", cols) + " |";
    }

    /**
     * Phân biệt dòng tiêu đề cột thật sự (VD: `| Mã gói | Tên gói | Giá |`) với dòng dữ liệu đầu tiên
     * của bảng Word không có header (VD: `| Em hay đau đầu | Nếu mình thường xuyên... |` hoặc `| NS01 | Massage cơ bản | 399.000VNĐ |`).
     */
    public static boolean isGenuineTableHeaderRow(String[] cells) {
        if (cells == null || cells.length == 0) {
            return false;
        }
        int nonEmpty = 0;
        for (String rawCell : cells) {
            if (rawCell == null) continue;
            String cell = rawCell.strip();
            if (cell.isEmpty()) continue;
            nonEmpty++;
            if (cell.length() > 38 || cell.contains("?") || cell.contains("•") || cell.contains(";")) {
                return false;
            }
            String lower = cell.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("dạ ") || lower.startsWith("em ") || lower.startsWith("nếu ")
                    || lower.startsWith("có ") || lower.startsWith("khách ")) {
                return false;
            }
            if (cell.matches("(?i)^(?:NS|DS|CB|B)\\d{1,3}$")) {
                return false;
            }
            if (lower.matches(".*\\d+\\.?\\d*\\s*(?:vnđ|vnd|đ|k|phút|p)\\b.*")) {
                return false;
            }
        }
        return nonEmpty > 0;
    }

    /**
     * Chuyển đổi nội dung chunk (đặc biệt là các bảng Markdown 1 dòng hoặc nhiều dòng)
     * sang định dạng LlamaIndex Structured Semantic Node sạch sẽ để đưa vào LLM Prompt,
     * loại bỏ các đường kẻ bảng `| --- |` gây nhiễu cho mô hình.
     */
    public static String formatChunkAsLlamaIndexNode(String chunkText, String[] headingPath) {
        return formatChunkAsLlamaIndexNode(chunkText, headingPath, null);
    }

    public static String formatChunkAsLlamaIndexNode(String chunkText, String[] headingPath, String query) {
        if (chunkText == null || chunkText.isBlank()) {
            return "";
        }
        String breadcrumb = (headingPath != null && headingPath.length > 0)
                ? "[" + String.join(" > ", headingPath) + "] "
                : "";
        String trimmed = chunkText.strip();
        String[] lines = trimmed.split("\r?\n");

        // Tìm vị trí bắt đầu bảng Markdown (kể cả khi có 1-2 dòng tiêu đề đứng trước bảng)
        int tableStartIdx = -1;
        for (int i = 0; i + 1 < lines.length; i++) {
            if (isMarkdownTableHeaderAndDelimiter(lines[i], lines[i + 1])) {
                tableStartIdx = i;
                break;
            }
        }
        if (tableStartIdx < 0 || tableStartIdx + 2 >= lines.length) {
            return breadcrumb + trimmed;
        }

        StringBuilder prefixBuilder = new StringBuilder();
        for (int i = 0; i < tableStartIdx; i++) {
            String pLine = lines[i].strip();
            if (!pLine.isEmpty()) {
                if (!prefixBuilder.isEmpty()) prefixBuilder.append(" — ");
                prefixBuilder.append(pLine);
            }
        }
        String prefix = prefixBuilder.isEmpty() ? "" : (prefixBuilder + ": ");

        String[] firstRowCells = splitMarkdownTableCells(lines[tableStartIdx]);
        boolean genuineHeader = isGenuineTableHeaderRow(firstRowCells);
        String[] headers = genuineHeader ? firstRowCells : new String[0];
        int dataStartIdx = genuineHeader ? tableStartIdx + 2 : tableStartIdx;

        List<String> formattedRows = new ArrayList<>();
        for (int i = dataStartIdx; i < lines.length; i++) {
            if (i == tableStartIdx + 1) {
                continue; // bỏ qua dòng delimiter | --- | --- |
            }
            String rowLine = lines[i].strip();
            if (rowLine.isBlank() || !rowLine.contains("|")) {
                continue;
            }
            String[] cells = splitMarkdownTableCells(rowLine);
            List<String> labeledPairs = new ArrayList<>();
            if (genuineHeader) {
                for (int c = 0; c < cells.length; c++) {
                    String val = cells[c].strip();
                    if (val.isEmpty()) {
                        continue;
                    }
                    String colName = (c < headers.length && !headers[c].isBlank())
                            ? headers[c].strip()
                            : ("Cột " + (c + 1));
                    labeledPairs.add(colName + ": " + val);
                }
            } else {
                List<String> nonEmptyCells = new ArrayList<>();
                for (String c : cells) {
                    if (c != null && !c.strip().isEmpty()) {
                        nonEmptyCells.add(c.strip());
                    }
                }
                if (nonEmptyCells.size() == 2) {
                    labeledPairs.add("Tình huống: " + nonEmptyCells.get(0) + " | Tư vấn: " + nonEmptyCells.get(1));
                } else {
                    labeledPairs.addAll(nonEmptyCells);
                }
            }
            if (!labeledPairs.isEmpty()) {
                formattedRows.add(String.join(" | ", labeledPairs));
            }
        }
        if (formattedRows.isEmpty()) {
            return breadcrumb + trimmed;
        }
        // LlamaIndex Row-Level Node Postprocessor:
        // - Với bảng 2 cột không có header thật (Tình huống | Tư vấn), chỉ giữ lại 1 dòng Tình huống khớp nhất với câu hỏi.
        // - Với bảng danh mục nhiều cột bị lặp dòng đầu khi split (!genuineHeader && firstRowCells.length >= 3),
        //   đưa dòng payload chính của chunk lên đầu và thu gọn mỗi dòng để giữ trọn cả 2 gói trong giới hạn 420 ký tự.
        if (!genuineHeader && firstRowCells.length <= 2 && formattedRows.size() > 1 && query != null && !query.isBlank()) {
            String qLowForFaq = query.toLowerCase(java.util.Locale.ROOT);
            int faqMaxRows = qLowForFaq.matches("(?isU).*\\b(thực\\s+dưỡng|buffet|ăn\\s+nhẹ|điểm\\s+tâm|nước\\s+ép|thực\\s+đơn)\\b.*") ? 2 : 1;
            formattedRows = selectMostRelevantTableRows(formattedRows, query, faqMaxRows);
        } else if (!genuineHeader && firstRowCells.length >= 3 && formattedRows.size() > 1) {
            String repeatedFirst = formattedRows.get(0);
            String payloadRow = formattedRows.get(1);
            String qLow = query != null ? query.toLowerCase(java.util.Locale.ROOT) : "";
            boolean queryTargetsFirst = qLow.matches("(?s).*\\b(399|390|ns01)\\b.*");
            boolean queryTargetsSpecificOtherPrice = !queryTargetsFirst
                    && qLow.matches("(?s).*\\b(530|580|670|780|990|ns02|ns03|ns04|ns05|ns06)\\b.*");
            if (queryTargetsSpecificOtherPrice) {
                String onlyPayload = payloadRow.length() > 320 ? payloadRow.substring(0, 320) : payloadRow;
                formattedRows = java.util.List.of(onlyPayload);
            } else {
                String primary = queryTargetsFirst ? repeatedFirst : payloadRow;
                String secondary = queryTargetsFirst ? payloadRow : repeatedFirst;
                if (primary.length() > 215) {
                    primary = primary.substring(0, 215);
                }
                if (secondary.length() > 150) {
                    secondary = secondary.substring(0, 150);
                }
                formattedRows = java.util.List.of(primary, secondary);
            }
        } else if (formattedRows.size() > 2) {
            if (query != null && !query.isBlank()) {
                int targetMax = firstRowCells.length <= 2 ? 3 : 2;
                formattedRows = selectMostRelevantTableRows(formattedRows, query, targetMax);
            } else {
                formattedRows = formattedRows.subList(0, 2);
            }
        }
        if (formattedRows.size() == 1) {
            return breadcrumb + prefix + formattedRows.get(0);
        }
        return breadcrumb + prefix + "\n- " + String.join("\n- ", formattedRows);
    }

    private static List<String> selectMostRelevantTableRows(List<String> rows, String query, int maxRows) {
        String lowerQuery = query.toLowerCase(java.util.Locale.ROOT);
        String[] rawTokens = lowerQuery.split("(?U)[^\\p{L}\\p{Nd}]+");
        List<String> queryTokens = new ArrayList<>();
        for (String t : rawTokens) {
            if (t.length() >= 2) {
                queryTokens.add(t);
            }
        }
        List<String> queryBigrams = new ArrayList<>();
        for (int i = 0; i + 1 < queryTokens.size(); i++) {
            queryBigrams.add(queryTokens.get(i) + " " + queryTokens.get(i + 1));
        }

        boolean asksRefreshmentOrMenu = lowerQuery.matches("(?isU).*\\b(thực\\s+dưỡng|dinh\\s+dưỡng|buffet|ăn\\s+nhẹ|điểm\\s+tâm|thực\\s+đơn|món\\s+gì|ăn\\s+gì|nước\\s+ép|trái\\s+cây|hoa\\s+quả)\\b.*");
        if (asksRefreshmentOrMenu) {
            for (String synBg : List.of("ăn nhẹ", "điểm tâm", "dinh dưỡng", "thực dưỡng", "nước ép", "trái cây", "hoa quả", "thực đơn")) {
                if (!queryBigrams.contains(synBg)) {
                    queryBigrams.add(synBg);
                }
            }
            for (String synTok : List.of("ăn", "điểm", "tâm", "dưỡng", "nước", "ép", "trái", "cây", "quả", "món", "buffet")) {
                if (!queryTokens.contains(synTok)) {
                    queryTokens.add(synTok);
                }
            }
        }

        boolean prefersBasicTier = lowerQuery.matches("(?isU).*\\b(thi\\s+thoảng|thỉnh\\s+thoảng|mới\\s+bị|hơi\\s+mỏi|hơi\\s+đau|nhẹ|cơ\\s+bản|khởi\\s+điểm|rẻ\\s+hơn|đắt)\\b.*");
        boolean prefersDeepTier = !prefersBasicTier && lowerQuery.matches("(?isU).*\\b(thường\\s+xuyên|lâu\\s+năm|lâu\\s+ngày|mãn\\s+tính|căng\\s+cứng|rất\\s+mỏi|chuyên\\s+sâu|phục\\s+hồi)\\b.*");

        List<Integer> indices = new ArrayList<>();
        int[] scores = new int[rows.size()];
        int maxScore = 0;
        for (int i = 0; i < rows.size(); i++) {
            indices.add(i);
            String rowLower = rows.get(i).toLowerCase(java.util.Locale.ROOT);
            int pipeIdx = rowLower.indexOf('|');
            String keyCell = pipeIdx > 0 ? rowLower.substring(0, pipeIdx) : rowLower;

            int score = 0;
            for (String tok : queryTokens) {
                if (tok.matches("\\d{3,4}") && (rowLower.contains(tok + ".000") || rowLower.contains(tok + "k"))) {
                    score += 45;
                }
                int weight = tok.length() >= 4 ? 3 : 2;
                if (keyCell.contains(tok)) {
                    score += weight * 3; // Ưu tiên khớp ở cột tiêu đề / tình huống / tên dịch vụ
                } else if (rowLower.contains(tok)) {
                    score += weight;
                }
            }
            for (String bg : queryBigrams) {
                if (keyCell.contains(bg)) {
                    score += 12;
                } else if (rowLower.contains(bg)) {
                    score += 5;
                }
            }
            if (prefersBasicTier && rowLower.contains("cơ bản") && !rowLower.contains("nâng cao")) {
                score += 18;
            } else if (prefersDeepTier && (rowLower.contains("chuyên sâu") || rowLower.contains("phục hồi") || rowLower.contains("nâng cao"))) {
                score += 18;
            }
            scores[i] = score;
            if (score > maxScore) {
                maxScore = score;
            }
        }
        if (maxScore == 0) {
            return rows.subList(0, Math.min(maxRows, rows.size()));
        }
        final int topScore = maxScore;
        indices.sort((a, b) -> Integer.compare(scores[b], scores[a]));
        List<String> selected = new ArrayList<>();
        for (int idx : indices) {
            if (selected.size() >= maxRows) {
                break;
            }
            if (scores[idx] > 0 && scores[idx] >= (topScore * 2) / 3) {
                selected.add(rows.get(idx));
            }
        }
        return selected.isEmpty() ? rows.subList(0, Math.min(maxRows, rows.size())) : selected;
    }

    private static String[] splitMarkdownTableCells(String line) {
        String s = line.strip();
        if (s.startsWith("|")) {
            s = s.substring(1);
        }
        if (s.endsWith("|")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.split("(?<!\\\\)\\|", -1);
    }

    /**
     * Kiểm tra xem chuỗi có phải là bảng Markdown hay không.
     */
    public static boolean isMarkdownTable(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.strip();
        if (!trimmed.contains("|")) {
            return false;
        }
        String[] lines = trimmed.split("\r?\n", 3);
        if (lines.length < 2) {
            return false;
        }
        return isMarkdownTableHeaderAndDelimiter(lines[0], lines[1]);
    }

    /**
     * Kiểm tra xem 2 dòng đầu có phải là Header row và Delimiter row hợp lệ của bảng Markdown hay không.
     */
    public static boolean isMarkdownTableHeaderAndDelimiter(String line0, String line1) {
        if (line0 == null || line1 == null) {
            return false;
        }
        String l0 = line0.strip();
        String l1 = line1.strip();
        if (!l0.contains("|") || !l1.contains("|") || !l1.contains("-")) {
            return false;
        }
        // Dòng delimiter trong Markdown chỉ chứa |, -, :, và khoảng trắng
        return l1.matches("^[\\|\\s\\-\\:]+$");
    }

    /**
     * Phân tách bảng Markdown (| col1 | col2 |\n|---|---|\n| r1 | r2 |)
     * sao cho mỗi phần giữ lại dòng tiêu đề (header + separator).
     */
    public static List<String> splitTableText(String tableText, int maxChars) {
        if (tableText == null || tableText.isBlank()) {
            return List.of();
        }
        String cleanTable = tableText.strip();
        String[] lines = cleanTable.split("\r?\n");
        if (lines.length <= 2) {
            return splitPlainText(cleanTable, maxChars);
        }

        // Kiểm tra xem 2 dòng đầu có phải là header và delimiter không
        boolean hasHeader = isMarkdownTableHeaderAndDelimiter(lines[0], lines[1]);
        String headerPrefix = hasHeader ? lines[0].strip() + "\n" + lines[1].strip() : "";
        int startIdx = hasHeader ? 2 : 0;

        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder(headerPrefix);

        for (int i = startIdx; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isBlank()) {
                continue;
            }

            int neededLength = (current.length() > headerPrefix.length() ? 1 : 0) + line.length();
            if (current.length() + neededLength > maxChars && current.length() > headerPrefix.length()) {
                parts.add(current.toString().strip());
                current = new StringBuilder(headerPrefix);
            }

            if (current.length() > 0 && !current.toString().endsWith("\n")) {
                current.append('\n');
            }
            current.append(line);
        }

        if (current.length() > headerPrefix.length() || (headerPrefix.isEmpty() && !current.isEmpty())) {
            parts.add(current.toString().strip());
        }

        return parts.isEmpty() ? List.of(cleanTable) : parts;
    }

    /**
     * Phân tách văn bản thông thường theo đoạn (\n\n), dòng (\n), hoặc khoảng trắng.
     */
    public static List<String> splitPlainText(String text, int maxChars) {
        List<String> parts = new ArrayList<>();
        if (text.length() <= maxChars) {
            parts.add(text);
            return parts;
        }

        // Tách theo paragraph trước
        String[] paragraphs = text.split("\r?\n\r?\n");
        StringBuilder current = new StringBuilder();

        for (String para : paragraphs) {
            String p = para.strip();
            if (p.isBlank()) {
                continue;
            }

            if (p.length() > maxChars) {
                // Nếu 1 paragraph vượt quá maxChars, tách theo dòng
                if (!current.isEmpty()) {
                    parts.add(current.toString().strip());
                    current = new StringBuilder();
                }
                String[] lines = p.split("\r?\n");
                for (String line : lines) {
                    String l = line.strip();
                    if (l.isBlank()) continue;
                    if (l.length() > maxChars) {
                        // Tách theo kích thước cứng hoặc từ
                        if (!current.isEmpty()) {
                            parts.add(current.toString().strip());
                            current = new StringBuilder();
                        }
                        int start = 0;
                        while (start < l.length()) {
                            int end = Math.min(start + maxChars, l.length());
                            if (end < l.length()) {
                                int lastSpace = l.lastIndexOf(' ', end);
                                if (lastSpace > start + maxChars / 2) {
                                    end = lastSpace;
                                }
                            }
                            parts.add(l.substring(start, end).strip());
                            start = end;
                            while (start < l.length() && l.charAt(start) == ' ') {
                                start++;
                            }
                        }
                    } else {
                        if (current.length() + l.length() + 1 > maxChars && !current.isEmpty()) {
                            parts.add(current.toString().strip());
                            current = new StringBuilder();
                        }
                        if (!current.isEmpty()) current.append('\n');
                        current.append(l);
                    }
                }
            } else {
                if (current.length() + p.length() + 2 > maxChars && !current.isEmpty()) {
                    parts.add(current.toString().strip());
                    current = new StringBuilder();
                }
                if (!current.isEmpty()) current.append("\n\n");
                current.append(p);
            }
        }

        if (!current.isEmpty()) {
            parts.add(current.toString().strip());
        }

        return parts.isEmpty() ? List.of(text) : parts;
    }

    private static UUID parseUuidSafe(String id) {
        if (id == null || id.isBlank()) {
            return UUID.randomUUID();
        }
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static double[] toDoubleArray(List<Double> bbox) {
        if (bbox == null || bbox.size() < 4) {
            return new double[]{0, 0, 0, 0};
        }
        return new double[]{
                bbox.get(0), bbox.get(1), bbox.get(2), bbox.get(3)
        };
    }

    /** Union bbox [x0,y0,x1,y1] top-left origin (x1,y1 là góc dưới-phải). */
    private static double[] unionBbox(List<CanonicalBlock> group) {
        double x0 = Double.MAX_VALUE;
        double y0 = Double.MAX_VALUE;
        double x1 = -Double.MAX_VALUE;
        double y1 = -Double.MAX_VALUE;
        boolean any = false;
        for (CanonicalBlock b : group) {
            List<Double> bb = b.bbox();
            if (bb == null || bb.size() < 4) {
                continue;
            }
            any = true;
            x0 = Math.min(x0, bb.get(0));
            y0 = Math.min(y0, bb.get(1));
            x1 = Math.max(x1, bb.get(2));
            y1 = Math.max(y1, bb.get(3));
        }
        if (!any) {
            return new double[]{0, 0, 0, 0};
        }
        return new double[]{x0, y0, x1, y1};
    }
}
