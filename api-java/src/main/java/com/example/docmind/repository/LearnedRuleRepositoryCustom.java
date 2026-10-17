package com.example.docmind.repository;

import com.example.docmind.entity.LearnedRuleEntity;

import java.util.List;
import java.util.UUID;

/**
 * Thao tác native cho pgvector và mảng trong bảng learned_rules.
 */
public interface LearnedRuleRepositoryCustom {

    default void insertRule(UUID id,
                            String category,
                            String triggerCondition,
                            String rootCause,
                            String generalizedRule,
                            String[] retrievalKeywords,
                            boolean active,
                            float[] embedding) {
        insertRule(id, category, triggerCondition, rootCause, generalizedRule, retrievalKeywords, active, embedding, null);
    }

    void insertRule(UUID id,
                    String category,
                    String triggerCondition,
                    String rootCause,
                    String generalizedRule,
                    String[] retrievalKeywords,
                    boolean active,
                    float[] embedding,
                    UUID pageId);

    default void updateRuleWithEmbedding(UUID id,
                                         String category,
                                         String triggerCondition,
                                         String rootCause,
                                         String generalizedRule,
                                         String[] retrievalKeywords,
                                         boolean active,
                                         float[] embedding) {
        updateRuleWithEmbedding(id, category, triggerCondition, rootCause, generalizedRule, retrievalKeywords, active, embedding, null);
    }

    void updateRuleWithEmbedding(UUID id,
                                 String category,
                                 String triggerCondition,
                                 String rootCause,
                                 String generalizedRule,
                                 String[] retrievalKeywords,
                                 boolean active,
                                 float[] embedding,
                                 UUID pageId);

    default List<LearnedRuleEntity> searchSimilarActiveRules(float[] queryEmbedding, double minScore, int limit) {
        return searchSimilarActiveRules(queryEmbedding, minScore, limit, null);
    }

    List<LearnedRuleEntity> searchSimilarActiveRules(float[] queryEmbedding, double minScore, int limit, UUID pageId);
}
