package com.example.docmind.repository;

import com.example.docmind.entity.LearnedRuleEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Triển khai native SQL cho pgvector và mảng text trong learned_rules.
 */
@Repository
public class LearnedRuleRepositoryCustomImpl implements LearnedRuleRepositoryCustom {

    private final JdbcTemplate jdbc;
    private final int embeddingDim;

    public LearnedRuleRepositoryCustomImpl(JdbcTemplate jdbc,
                                           @Value("${docmind.embedding.dim:384}") int embeddingDim) {
        this.jdbc = jdbc;
        this.embeddingDim = embeddingDim;
    }

    @Override
    public void insertRule(UUID id,
                           String category,
                           String triggerCondition,
                           String rootCause,
                           String generalizedRule,
                           String[] retrievalKeywords,
                           boolean active,
                           float[] embedding,
                           UUID pageId) {
        String vectorLiteral = embedding == null ? null : toVectorLiteral(embedding);

        jdbc.update(connection -> {
            String sql;
            if (vectorLiteral != null) {
                sql = """
                        INSERT INTO learned_rules
                            (id, category, trigger_condition, root_cause, generalized_rule,
                             retrieval_keywords, active, page_id, created_at, updated_at, embedding)
                        VALUES
                            (?, ?, ?, ?, ?, ?, ?, ?, now(), now(), CAST(? AS vector))
                        """;
            } else {
                sql = """
                        INSERT INTO learned_rules
                            (id, category, trigger_condition, root_cause, generalized_rule,
                             retrieval_keywords, active, page_id, created_at, updated_at, embedding)
                        VALUES
                            (?, ?, ?, ?, ?, ?, ?, ?, now(), now(), NULL)
                        """;
            }
            var ps = connection.prepareStatement(sql);
            ps.setObject(1, id);
            ps.setString(2, category);
            ps.setString(3, triggerCondition);
            ps.setString(4, rootCause);
            ps.setString(5, generalizedRule);
            Array kwArray = connection.createArrayOf("text", retrievalKeywords == null ? new String[0] : retrievalKeywords);
            ps.setArray(6, kwArray);
            ps.setBoolean(7, active);
            if (pageId == null) {
                ps.setNull(8, java.sql.Types.OTHER);
            } else {
                ps.setObject(8, pageId);
            }
            if (vectorLiteral != null) {
                ps.setString(9, vectorLiteral);
            }
            return ps;
        });
    }

    @Override
    public void updateRuleWithEmbedding(UUID id,
                                        String category,
                                        String triggerCondition,
                                        String rootCause,
                                        String generalizedRule,
                                        String[] retrievalKeywords,
                                        boolean active,
                                        float[] embedding,
                                        UUID pageId) {
        String vectorLiteral = embedding == null ? null : toVectorLiteral(embedding);

        jdbc.update(connection -> {
            String sql;
            if (vectorLiteral != null) {
                sql = """
                        UPDATE learned_rules
                        SET category = ?,
                            trigger_condition = ?,
                            root_cause = ?,
                            generalized_rule = ?,
                            retrieval_keywords = ?,
                            active = ?,
                            page_id = ?,
                            updated_at = now(),
                            embedding = CAST(? AS vector)
                        WHERE id = ?
                        """;
            } else {
                sql = """
                        UPDATE learned_rules
                        SET category = ?,
                            trigger_condition = ?,
                            root_cause = ?,
                            generalized_rule = ?,
                            retrieval_keywords = ?,
                            active = ?,
                            page_id = ?,
                            updated_at = now()
                        WHERE id = ?
                        """;
            }
            var ps = connection.prepareStatement(sql);
            ps.setString(1, category);
            ps.setString(2, triggerCondition);
            ps.setString(3, rootCause);
            ps.setString(4, generalizedRule);
            Array kwArray = connection.createArrayOf("text", retrievalKeywords == null ? new String[0] : retrievalKeywords);
            ps.setArray(5, kwArray);
            ps.setBoolean(6, active);
            if (pageId == null) {
                ps.setNull(7, java.sql.Types.OTHER);
            } else {
                ps.setObject(7, pageId);
            }
            if (vectorLiteral != null) {
                ps.setString(8, vectorLiteral);
                ps.setObject(9, id);
            } else {
                ps.setObject(8, id);
            }
            return ps;
        });
    }

    @Override
    public List<LearnedRuleEntity> searchSimilarActiveRules(float[] queryEmbedding, double minScore, int limit, UUID pageId) {
        if (queryEmbedding == null) {
            return List.of();
        }
        String vectorLiteral = toVectorLiteral(queryEmbedding);
        if (pageId == null) {
            String sql = """
                    SELECT id, category, trigger_condition, root_cause, generalized_rule,
                           retrieval_keywords, active, page_id, created_at, updated_at,
                           (1 - (embedding <=> CAST(? AS vector))) AS score
                    FROM learned_rules
                    WHERE active = true AND embedding IS NOT NULL
                    ORDER BY embedding <=> CAST(? AS vector)
                    LIMIT ?
                    """;

            return jdbc.query(sql, (rs, rowNum) -> {
                double score = rs.getDouble("score");
                if (score < minScore) {
                    return null;
                }
                return mapRow(rs);
            }, vectorLiteral, vectorLiteral, limit)
            .stream()
            .filter(java.util.Objects::nonNull)
            .toList();
        } else {
            String sql = """
                    SELECT id, category, trigger_condition, root_cause, generalized_rule,
                           retrieval_keywords, active, page_id, created_at, updated_at,
                           (1 - (embedding <=> CAST(? AS vector))) AS score
                    FROM learned_rules
                    WHERE active = true AND embedding IS NOT NULL
                      AND (page_id IS NULL OR page_id = ?)
                    ORDER BY embedding <=> CAST(? AS vector)
                    LIMIT ?
                    """;

            return jdbc.query(sql, (rs, rowNum) -> {
                double score = rs.getDouble("score");
                if (score < minScore) {
                    return null;
                }
                return mapRow(rs);
            }, vectorLiteral, pageId, vectorLiteral, limit)
            .stream()
            .filter(java.util.Objects::nonNull)
            .toList();
        }
    }

    private LearnedRuleEntity mapRow(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        String category = rs.getString("category");
        String triggerCondition = rs.getString("trigger_condition");
        String rootCause = rs.getString("root_cause");
        String generalizedRule = rs.getString("generalized_rule");
        String[] keywords = toStringArray(rs.getArray("retrieval_keywords"));
        boolean active = rs.getBoolean("active");
        UUID pageId = null;
        try {
            pageId = rs.getObject("page_id", UUID.class);
        } catch (SQLException ignored) {
        }
        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp updatedAt = rs.getTimestamp("updated_at");

        return LearnedRuleEntity.builder()
                .id(id)
                .category(category)
                .triggerCondition(triggerCondition)
                .rootCause(rootCause)
                .generalizedRule(generalizedRule)
                .retrievalKeywords(keywords)
                .active(active)
                .pageId(pageId)
                .createdAt(createdAt != null ? createdAt.toInstant() : Instant.now())
                .updatedAt(updatedAt != null ? updatedAt.toInstant() : Instant.now())
                .build();
    }

    private String toVectorLiteral(float[] v) {
        if (v == null) {
            return null;
        }
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

    private static String[] toStringArray(Array sqlArray) throws SQLException {
        if (sqlArray == null) {
            return new String[0];
        }
        Object[] raw = (Object[]) sqlArray.getArray();
        List<String> list = new ArrayList<>(raw.length);
        for (Object o : raw) {
            if (o != null) {
                list.add(o.toString());
            }
        }
        return list.toArray(new String[0]);
    }
}
