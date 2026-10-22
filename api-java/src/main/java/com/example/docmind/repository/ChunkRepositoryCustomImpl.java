package com.example.docmind.repository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Impl native cho pgvector/tsvector (JdbcTemplate).
 *
 * <p>Vector được truyền dưới dạng literal '[a,b,c]'::vector — đây là format text
 * chính thức của pgvector. tsvector dựng bằng to_tsvector('simple', text) để trung
 * lập ngôn ngữ (tài liệu có thể tiếng Việt). plainto_tsquery('simple', ?) cho query.
 */
@Repository
public class ChunkRepositoryCustomImpl implements ChunkRepositoryCustom {

    private final JdbcTemplate jdbc;
    private final int embeddingDim;

    public ChunkRepositoryCustomImpl(JdbcTemplate jdbc,
                                     @Value("${docmind.embedding.dim:384}") int embeddingDim) {
        this.jdbc = jdbc;
        this.embeddingDim = embeddingDim;
    }

    @Override
    public void insertChunk(UUID id,
                            UUID documentId,
                            String[] headingPath,
                            UUID[] sourceBlockIds,
                            int page,
                            double[] bbox,
                            String text,
                            float[] embedding) {
        insertChunk(id, documentId, null, headingPath, sourceBlockIds, page, bbox, text, embedding);
    }

    @Override
    public void insertChunk(UUID id,
                            UUID documentId,
                            UUID pageId,
                            String[] headingPath,
                            UUID[] sourceBlockIds,
                            int page,
                            double[] bbox,
                            String text,
                            float[] embedding) {

        String vectorLiteral = embedding == null ? null : toVectorLiteral(embedding);

        jdbc.update(connection -> {
            String sql = """
                    INSERT INTO chunks
                        (id, document_id, page_id, heading_path, source_block_ids, page, bbox, text, embedding, tsv)
                    VALUES
                        (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS vector),
                         setweight(to_tsvector('simple', coalesce(array_to_string(?, ' '), '')), 'A') || setweight(to_tsvector('simple', ?), 'B'))
                    """;
            var ps = connection.prepareStatement(sql);
            ps.setObject(1, id);
            ps.setObject(2, documentId);
            ps.setObject(3, pageId);
            String[] safeHeadingPath = headingPath != null ? headingPath : new String[0];
            Array headingArray = connection.createArrayOf("text", safeHeadingPath);
            Array blockIdsArray = connection.createArrayOf("uuid", sourceBlockIds != null ? sourceBlockIds : new UUID[0]);
            Array bboxArray = connection.createArrayOf("float8", toObjectArray(bbox));
            ps.setArray(4, headingArray);
            ps.setArray(5, blockIdsArray);
            ps.setInt(6, page);
            ps.setArray(7, bboxArray);
            ps.setString(8, text != null ? text : "");
            ps.setString(9, vectorLiteral); // null => NULL::vector
            ps.setArray(10, headingArray);
            ps.setString(11, text != null ? text : "");
            return ps;
        });
    }

    @Override
    public int updatePageIdByDocumentId(UUID documentId, UUID pageId) {
        if (documentId == null) {
            return 0;
        }
        return jdbc.update(
                "UPDATE chunks SET page_id = ? WHERE document_id = ?",
                pageId,
                documentId
        );
    }

    @Override
    public List<RetrievedChunk> searchByVector(List<UUID> documentIds, float[] queryEmbedding, int limit) {
        return searchByVector(documentIds, queryEmbedding, limit, null);
    }

    @Override
    public List<RetrievedChunk> searchByVector(List<UUID> documentIds, float[] queryEmbedding, int limit, UUID pageId) {
        if (documentIds == null || documentIds.isEmpty() || queryEmbedding == null) {
            return List.of();
        }
        String vectorLiteral = toVectorLiteral(queryEmbedding);
        String inClause = inPlaceholders(documentIds.size());
        String pageFilter = pageId != null ? "AND page_id = ?" : "";
        // score = 1 - cosine_distance; ORDER BY distance (toán tử <=> = cosine distance).
        String sql = """
                SELECT id, document_id, heading_path, source_block_ids, page, bbox, text,
                       (1 - (embedding <=> CAST(? AS vector))) AS score
                FROM chunks
                WHERE embedding IS NOT NULL
                  AND document_id IN (%s)
                  %s
                ORDER BY embedding <=> CAST(? AS vector)
                LIMIT ?
                """.formatted(inClause, pageFilter);

        Object[] args = new Object[1 + documentIds.size() + (pageId != null ? 1 : 0) + 2];
        int i = 0;
        args[i++] = vectorLiteral;
        for (UUID d : documentIds) {
            args[i++] = d;
        }
        if (pageId != null) {
            args[i++] = pageId;
        }
        args[i++] = vectorLiteral;
        args[i] = limit;

        return jdbc.query(sql, this::mapRow, args);
    }

    private static final java.util.Set<String> VI_STOP_WORDS = java.util.Set.of(
            "và", "hoặc", "hay", "là", "thì", "mà", "ở", "tại", "trong", "ngoài", "của", "cho",
            "với", "từ", "đến", "để", "do", "bởi", "vì", "nên", "nếu", "có", "không", "được",
            "bị", "các", "những", "một", "cái", "con", "người", "này", "đó", "kia", "nào",
            "gì", "sao", "đâu", "ai", "mấy", "bao", "nhiêu", "thuộc", "hãy", "xem", "biết",
            "giúp", "tôi", "em", "anh", "chị", "xin", "vui", "lòng", "như", "thế",
            "bên", "bạn", "mình", "shop", "ad", "admin", "vậy", "nhé", "nha", "ạ", "ơi", "hỏi", "chút"
    );

    static String buildTsQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String lowerQuery = query.toLowerCase();
        String[] rawTokens = lowerQuery.split("(?U)[^\\p{L}\\p{Nd}]+");
        List<String> validTokens = new ArrayList<>();
        List<String> contentTokens = new ArrayList<>();
        for (String t : rawTokens) {
            if (!t.isBlank()) {
                validTokens.add(t);
                if (!VI_STOP_WORDS.contains(t) && t.length() > 1) {
                    contentTokens.add(t);
                }
            }
        }
        List<String> tokensToUse = contentTokens.isEmpty() ? validTokens : contentTokens;
        if (tokensToUse.isEmpty()) {
            return null;
        }

        List<String> parts = new ArrayList<>();
        // Cặp từ liền kề (phrase matching: 'dịch' <-> 'vụ', 'chi' <-> 'phí')
        for (int i = 0; i < tokensToUse.size() - 1; i++) {
            String w1 = tokensToUse.get(i).replace("'", "''");
            String w2 = tokensToUse.get(i + 1).replace("'", "''");
            parts.add("('" + w1 + "' <-> '" + w2 + "')");
        }
        // Từng từ đơn lẻ
        for (String t : tokensToUse) {
            parts.add("'" + t.replace("'", "''") + "'");
        }
        // Bảo toàn số tiền có dấu chấm ngăn cách hàng nghìn (VD: '580.000', '670.000') vì to_tsvector('simple') giữ nguyên dấu chấm trong số
        java.util.regex.Matcher dottedNumMatcher = java.util.regex.Pattern
                .compile("\\b\\d{1,3}(?:\\.\\d{3})+\\b")
                .matcher(lowerQuery);
        while (dottedNumMatcher.find()) {
            parts.add("'" + dottedNumMatcher.group() + "'");
        }

        // Mở rộng từ đồng nghĩa bán hàng phổ biến (VD: khách hỏi "chi phí bao nhiêu" -> khớp "bảng giá", "niêm yết")
        if (lowerQuery.contains("chi phí") || lowerQuery.contains("bao nhiêu") || lowerQuery.contains("giá")
                || lowerQuery.contains("dịch vụ") || lowerQuery.contains("gói") || lowerQuery.contains("liệu trình")) {
            parts.add("('bảng' <-> 'giá')");
            parts.add("('đơn' <-> 'giá')");
            parts.add("('niêm' <-> 'yết')");
            parts.add("('tên' <-> 'gói')");
            parts.add("('liệu' <-> 'trình')");
            parts.add("'giá'");
        }

        return String.join(" | ", parts);
    }

    @Override
    public List<RetrievedChunk> searchByFullText(List<UUID> documentIds, String query, int limit) {
        return searchByFullText(documentIds, query, limit, null);
    }

    @Override
    public List<RetrievedChunk> searchByFullText(List<UUID> documentIds, String query, int limit, UUID pageId) {
        if (documentIds == null || documentIds.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }
        String inClause = inPlaceholders(documentIds.size());
        String pageFilter = pageId != null ? "AND page_id = ?" : "";
        String tsQueryStr = buildTsQuery(query);

        if (tsQueryStr == null) {
            return List.of();
        }

        try {
            String sql = """
                    SELECT id, document_id, heading_path, source_block_ids, page, bbox, text,
                           (ts_rank_cd(tsv, to_tsquery('simple', ?)) +
                            CASE WHEN tsv @@ plainto_tsquery('simple', ?) THEN 10.0 ELSE 0.0 END) AS score
                    FROM chunks
                    WHERE (tsv @@ to_tsquery('simple', ?) OR tsv @@ plainto_tsquery('simple', ?))
                      AND document_id IN (%s)
                      %s
                    ORDER BY score DESC
                    LIMIT ?
                    """.formatted(inClause, pageFilter);

            Object[] args = new Object[4 + documentIds.size() + (pageId != null ? 1 : 0) + 1];
            int i = 0;
            args[i++] = tsQueryStr;
            args[i++] = query;
            args[i++] = tsQueryStr;
            args[i++] = query;
            for (UUID d : documentIds) {
                args[i++] = d;
            }
            if (pageId != null) {
                args[i++] = pageId;
            }
            args[i] = limit;

            return jdbc.query(sql, this::mapRow, args);
        } catch (Exception e) {
            // Fallback an toàn về plainto_tsquery nếu to_tsquery gặp cú pháp lạ
            String fallbackSql = """
                    SELECT id, document_id, heading_path, source_block_ids, page, bbox, text,
                           ts_rank(tsv, plainto_tsquery('simple', ?)) AS score
                    FROM chunks
                    WHERE tsv @@ plainto_tsquery('simple', ?)
                      AND document_id IN (%s)
                      %s
                    ORDER BY score DESC
                    LIMIT ?
                    """.formatted(inClause, pageFilter);

            Object[] args = new Object[2 + documentIds.size() + (pageId != null ? 1 : 0) + 1];
            int i = 0;
            args[i++] = query;
            args[i++] = query;
            for (UUID d : documentIds) {
                args[i++] = d;
            }
            if (pageId != null) {
                args[i++] = pageId;
            }
            args[i] = limit;

            return jdbc.query(fallbackSql, this::mapRow, args);
        }
    }

    // --- helpers ---

    private RetrievedChunk mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        UUID chunkId = rs.getObject("id", UUID.class);
        UUID documentId = rs.getObject("document_id", UUID.class);
        List<String> headingPath = toStringList(rs.getArray("heading_path"));
        List<UUID> blockIds = toUuidList(rs.getArray("source_block_ids"));
        int page = rs.getInt("page");
        double[] bbox = toDoubleArray(rs.getArray("bbox"));
        String text = rs.getString("text");
        double score = rs.getDouble("score");
        return new RetrievedChunk(chunkId, documentId, headingPath, blockIds, page, bbox, text, score);
    }

    private String toVectorLiteral(float[] v) {
        if (v.length != embeddingDim) {
            throw new IllegalArgumentException(
                    "Embedding dim không khớp: expected " + embeddingDim + " nhưng nhận " + v.length);
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    private static Double[] toObjectArray(double[] arr) {
        Double[] out = new Double[arr.length];
        for (int i = 0; i < arr.length; i++) {
            out[i] = arr[i];
        }
        return out;
    }

    private static String inPlaceholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    private static List<String> toStringList(Array sqlArray) throws java.sql.SQLException {
        if (sqlArray == null) {
            return List.of();
        }
        Object[] raw = (Object[]) sqlArray.getArray();
        List<String> out = new ArrayList<>(raw.length);
        for (Object o : raw) {
            out.add(o == null ? null : o.toString());
        }
        return out;
    }

    private static List<UUID> toUuidList(Array sqlArray) throws java.sql.SQLException {
        if (sqlArray == null) {
            return List.of();
        }
        Object[] raw = (Object[]) sqlArray.getArray();
        List<UUID> out = new ArrayList<>(raw.length);
        for (Object o : raw) {
            if (o instanceof UUID u) {
                out.add(u);
            } else if (o != null) {
                out.add(UUID.fromString(o.toString()));
            }
        }
        return out;
    }

    private static double[] toDoubleArray(Array sqlArray) throws java.sql.SQLException {
        if (sqlArray == null) {
            return new double[0];
        }
        Object[] raw = (Object[]) sqlArray.getArray();
        double[] out = new double[raw.length];
        for (int i = 0; i < raw.length; i++) {
            out[i] = raw[i] == null ? 0.0 : ((Number) raw[i]).doubleValue();
        }
        return out;
    }
}
