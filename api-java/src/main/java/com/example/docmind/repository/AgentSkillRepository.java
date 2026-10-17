package com.example.docmind.repository;

import com.example.docmind.entity.AgentSkillEntity;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
