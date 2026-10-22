package com.example.docmind.repository;

import com.example.docmind.entity.AgentSkillEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository truy vấn kho Kỹ năng Agent (Agent Skills) theo phạm vi Toàn hệ thống (Global) và từng Page.
 */
@Repository
public interface AgentSkillRepository extends JpaRepository<AgentSkillEntity, UUID> {

    @Query("""
            SELECT s FROM AgentSkillEntity s
            WHERE s.active = true
              AND (:pageId IS NULL AND s.pageId IS NULL
                   OR :pageId IS NOT NULL AND (s.pageId IS NULL OR s.pageId = :pageId))
            ORDER BY s.priority DESC, s.createdAt ASC
            """)
    List<AgentSkillEntity> findActiveForPage(@Param("pageId") UUID pageId);

    @Query("""
            SELECT s FROM AgentSkillEntity s
            ORDER BY s.priority DESC, s.createdAt ASC
            """)
    List<AgentSkillEntity> findAllOrdered();

    Optional<AgentSkillEntity> findFirstByCodeIgnoreCase(String code);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE agent_skills
            SET applied_count = applied_count + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
            """, nativeQuery = true)
    int incrementAppliedCount(@Param("id") UUID id);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE agent_skills
            SET conversion_count = conversion_count + 1,
                confidence_score = LEAST(0.99, ROUND((CASE WHEN confidence_score <= 0.0 THEN 0.90 ELSE confidence_score END + 0.02)::numeric, 2)),
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
            """, nativeQuery = true)
    int incrementConversionSuccess(@Param("id") UUID id);
}
