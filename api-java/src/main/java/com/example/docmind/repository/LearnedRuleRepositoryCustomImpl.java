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

    private static final int DEFAULT_LEARNED_RULES_IVFFLAT_PROBES = 50;

    @Override
    public List<LearnedRuleEntity> searchSimilarActiveRules(float[] queryEmbedding, double minScore, int limit, UUID pageId) {
        if (queryEmbedding == null) {
            return List.of();
        }
        String vectorLiteral = toVectorLiteral(queryEmbedding);
        if (pageId == null) {
            String sql = """
                    SELECT id, category, trigger_condition, root_cause, generalized_rule,
                           retrieval_keywords, active, page_id,
                           confidence_score, applied_count, success_count, fail_count,
                           version, previous_rule_text, quarantined_reason, last_applied_at,
                           dimension, cue_signal, contrastive_evidence, always_on,
                           review_status, security_audit_notes,
                           rule_code, title, intent, stage, priority,
                           do_actions, dont_actions, grounding_policy, handoff_policy, source_format,
                           created_at, updated_at,
                           (1 - (embedding <=> CAST(? AS vector))) AS score
                    FROM learned_rules
                    WHERE active = true AND page_id IS NULL AND embedding IS NOT NULL
                    ORDER BY embedding <=> CAST(? AS vector)
                    LIMIT ?
                    """;

            return queryRulesWithIvfflatProbes(sql, minScore, ps -> {
                ps.setString(1, vectorLiteral);
                ps.setString(2, vectorLiteral);
                ps.setInt(3, limit);
            });
        } else {
            String sql = """
                    SELECT id, category, trigger_condition, root_cause, generalized_rule,
                           retrieval_keywords, active, page_id,
                           confidence_score, applied_count, success_count, fail_count,
                           version, previous_rule_text, quarantined_reason, last_applied_at,
                           dimension, cue_signal, contrastive_evidence, always_on,
                           review_status, security_audit_notes,
                           rule_code, title, intent, stage, priority,
                           do_actions, dont_actions, grounding_policy, handoff_policy, source_format,
                           created_at, updated_at,
                           (1 - (embedding <=> CAST(? AS vector))) AS score
                    FROM learned_rules
                    WHERE active = true AND embedding IS NOT NULL
                      AND (page_id IS NULL OR page_id = ?)
                    ORDER BY embedding <=> CAST(? AS vector)
                    LIMIT ?
                    """;

            return queryRulesWithIvfflatProbes(sql, minScore, ps -> {
                ps.setString(1, vectorLiteral);
                ps.setObject(2, pageId);
                ps.setString(3, vectorLiteral);
                ps.setInt(4, limit);
            });
        }
    }

    @Override
    public List<LearnedRuleEntity> searchSimilarRulesForEvolution(float[] queryEmbedding, double minScore, int limit, UUID pageId) {
        if (queryEmbedding == null) {
            return List.of();
        }
        String vectorLiteral = toVectorLiteral(queryEmbedding);
        if (pageId == null) {
            String sql = """
                    SELECT id, category, trigger_condition, root_cause, generalized_rule,
                           retrieval_keywords, active, page_id,
                           confidence_score, applied_count, success_count, fail_count,
                           version, previous_rule_text, quarantined_reason, last_applied_at,
                           dimension, cue_signal, contrastive_evidence, always_on,
                           review_status, security_audit_notes,
                           rule_code, title, intent, stage, priority,
                           do_actions, dont_actions, grounding_policy, handoff_policy, source_format,
                           created_at, updated_at,
                           (1 - (embedding <=> CAST(? AS vector))) AS score
                    FROM learned_rules
                    WHERE (active = true OR quarantined_reason IS NOT NULL)
                      AND page_id IS NULL
                      AND embedding IS NOT NULL
                    ORDER BY embedding <=> CAST(? AS vector)
                    LIMIT ?
                    """;

            return queryRulesWithIvfflatProbes(sql, minScore, ps -> {
                ps.setString(1, vectorLiteral);
                ps.setString(2, vectorLiteral);
                ps.setInt(3, limit);
            });
        } else {
            String sql = """
                    SELECT id, category, trigger_condition, root_cause, generalized_rule,
                           retrieval_keywords, active, page_id,
                           confidence_score, applied_count, success_count, fail_count,
                           version, previous_rule_text, quarantined_reason, last_applied_at,
                           dimension, cue_signal, contrastive_evidence, always_on,
                           review_status, security_audit_notes,
                           rule_code, title, intent, stage, priority,
                           do_actions, dont_actions, grounding_policy, handoff_policy, source_format,
                           created_at, updated_at,
                           (1 - (embedding <=> CAST(? AS vector))) AS score
                    FROM learned_rules
                    WHERE (active = true OR quarantined_reason IS NOT NULL)
                      AND page_id = ?
                      AND embedding IS NOT NULL
                    ORDER BY embedding <=> CAST(? AS vector)
                    LIMIT ?
                    """;

            return queryRulesWithIvfflatProbes(sql, minScore, ps -> {
                ps.setString(1, vectorLiteral);
                ps.setObject(2, pageId);
                ps.setString(3, vectorLiteral);
                ps.setInt(4, limit);
            });
        }
    }

    @FunctionalInterface
    private interface SqlParamBinder {
        void bind(java.sql.PreparedStatement ps) throws SQLException;
    }

    private List<LearnedRuleEntity> queryRulesWithIvfflatProbes(String sql, double minScore, SqlParamBinder binder) {
        return jdbc.execute((java.sql.Connection connection) -> {
            ChunkRepositoryCustomImpl.ensureIvfflatProbes(connection, DEFAULT_LEARNED_RULES_IVFFLAT_PROBES);
            try (var ps = connection.prepareStatement(sql)) {
                binder.bind(ps);
                try (ResultSet rs = ps.executeQuery()) {
                    List<LearnedRuleEntity> out = new ArrayList<>();
                    while (rs.next()) {
                        double score = rs.getDouble("score");
                        if (score >= minScore) {
                            out.add(mapRow(rs));
                        }
                    }
                    return out;
                }
            }
        });
    }

    @Override
    public void updateRuleEvolutionMetadata(UUID id,
                                            int version,
                                            String previousRuleText,
                                            double confidenceScore,
                                            String quarantinedReason) {
        if (id == null) {
            return;
        }
        String sql = """
                UPDATE learned_rules
                SET version = ?,
                    previous_rule_text = ?,
                    confidence_score = ?,
                    quarantined_reason = ?,
                    fail_count = 0,
                    active = true,
                    updated_at = now()
                WHERE id = ?
                """;
        jdbc.update(sql, version, previousRuleText, confidenceScore, quarantinedReason, id);
    }

    @Override
    public void recordRulesApplied(List<UUID> ruleIds) {
        if (ruleIds == null || ruleIds.isEmpty()) {
            return;
        }
        String sql = """
                UPDATE learned_rules
                SET applied_count = applied_count + 1,
                    last_applied_at = now()
                WHERE id = ?
                """;
        for (UUID ruleId : ruleIds) {
            if (ruleId != null) {
                jdbc.update(sql, ruleId);
            }
        }
    }

    @Override
    public void recordRuleConversionSuccess(UUID id, double confidenceBoost) {
        if (id == null) {
            return;
        }
        String sql = """
                UPDATE learned_rules
                SET success_count = success_count + 1,
                    confidence_score = LEAST(0.99, confidence_score + ?),
                    updated_at = now()
                WHERE id = ?
                """;
        jdbc.update(sql, confidenceBoost, id);
    }

    @Override
    public void recordRuleNegativeFeedback(UUID id,
                                           int failCount,
                                           double confidenceScore,
                                           boolean active,
                                           String quarantinedReason) {
        if (id == null) {
            return;
        }
        String sql = """
                UPDATE learned_rules
                SET fail_count = ?,
                    confidence_score = ?,
                    active = ?,
                    quarantined_reason = ?,
                    updated_at = now()
                WHERE id = ?
                """;
        jdbc.update(sql, failCount, confidenceScore, active, quarantinedReason, id);
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
        double confidenceScore = 0.85;
        try {
            double rawConf = rs.getDouble("confidence_score");
            if (!rs.wasNull() && rawConf > 0.0) {
                confidenceScore = rawConf;
            }
        } catch (SQLException ignored) {
        }
        int appliedCount = 0;
        try {
            appliedCount = rs.getInt("applied_count");
        } catch (SQLException ignored) {
        }
        int successCount = 0;
        try {
            successCount = rs.getInt("success_count");
        } catch (SQLException ignored) {
        }
        int failCount = 0;
        try {
            failCount = rs.getInt("fail_count");
        } catch (SQLException ignored) {
        }
        int version = 1;
        try {
            int rawVer = rs.getInt("version");
            if (!rs.wasNull() && rawVer > 0) {
                version = rawVer;
            }
        } catch (SQLException ignored) {
        }
        String previousRuleText = null;
        try {
            previousRuleText = rs.getString("previous_rule_text");
        } catch (SQLException ignored) {
        }
        String quarantinedReason = null;
        try {
            quarantinedReason = rs.getString("quarantined_reason");
        } catch (SQLException ignored) {
        }
        Timestamp lastAppliedAt = null;
        try {
            lastAppliedAt = rs.getTimestamp("last_applied_at");
        } catch (SQLException ignored) {
        }
        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        String ruleCode = getOptionalString(rs, "rule_code");
        String title = getOptionalString(rs, "title");
        String intent = getOptionalString(rs, "intent");
        String stage = getOptionalString(rs, "stage");
        Integer priority = getOptionalInt(rs, "priority", 80);
        String doActions = getOptionalString(rs, "do_actions");
        String dontActions = getOptionalString(rs, "dont_actions");
        String groundingPolicy = getOptionalString(rs, "grounding_policy");
        String handoffPolicy = getOptionalString(rs, "handoff_policy");
        String sourceFormat = getOptionalString(rs, "source_format");
        String dimStr = getOptionalString(rs, "dimension");
        com.example.docmind.entity.HarnessDimension dimension = "INV".equalsIgnoreCase(dimStr)
                ? com.example.docmind.entity.HarnessDimension.INV
                : com.example.docmind.entity.HarnessDimension.DOM;
        String cueSignal = getOptionalString(rs, "cue_signal");
        String contrastiveEvidence = getOptionalString(rs, "contrastive_evidence");
        boolean alwaysOn = getOptionalBoolean(rs, "always_on", false);
        String reviewStatus = getOptionalString(rs, "review_status");
        String securityAuditNotes = getOptionalString(rs, "security_audit_notes");

        return LearnedRuleEntity.builder()
                .id(id)
                .category(category)
                .triggerCondition(triggerCondition)
                .rootCause(rootCause)
                .generalizedRule(generalizedRule)
                .retrievalKeywords(keywords)
                .active(active)
                .pageId(pageId)
                .confidenceScore(confidenceScore)
                .appliedCount(appliedCount)
                .successCount(successCount)
                .failCount(failCount)
                .version(version)
                .previousRuleText(previousRuleText)
                .quarantinedReason(quarantinedReason)
                .lastAppliedAt(lastAppliedAt != null ? lastAppliedAt.toInstant() : null)
                .dimension(dimension)
                .cueSignal(cueSignal)
                .contrastiveEvidence(contrastiveEvidence)
                .alwaysOn(alwaysOn)
                .reviewStatus(reviewStatus != null ? reviewStatus : "APPROVED")
                .securityAuditNotes(securityAuditNotes)
                .ruleCode(ruleCode)
                .title(title)
                .intent(intent)
                .stage(stage)
                .priority(priority)
                .doActions(doActions)
                .dontActions(dontActions)
                .groundingPolicy(groundingPolicy != null ? groundingPolicy : "must_use_kb_document_context")
                .handoffPolicy(handoffPolicy != null ? handoffPolicy : "none")
                .sourceFormat(sourceFormat != null ? sourceFormat : "MANUAL")
                .createdAt(createdAt != null ? createdAt.toInstant() : Instant.now())
                .updatedAt(updatedAt != null ? updatedAt.toInstant() : Instant.now())
                .build();
    }

    private static String getOptionalString(ResultSet rs, String col) {
        try {
            return rs.getString(col);
        } catch (SQLException ignored) {
            return null;
        }
    }

    private static Integer getOptionalInt(ResultSet rs, String col, int defaultVal) {
        try {
            int val = rs.getInt(col);
            return rs.wasNull() ? defaultVal : val;
        } catch (SQLException ignored) {
            return defaultVal;
        }
    }

    private static boolean getOptionalBoolean(ResultSet rs, String col, boolean defaultVal) {
        try {
            boolean val = rs.getBoolean(col);
            return rs.wasNull() ? defaultVal : val;
        } catch (SQLException ignored) {
            return defaultVal;
        }
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
