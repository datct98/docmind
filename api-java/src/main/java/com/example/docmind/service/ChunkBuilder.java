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
