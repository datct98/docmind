package com.example.docmind.repository;

import com.example.docmind.entity.SampleDialogueEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SampleDialogueRepository extends JpaRepository<SampleDialogueEntity, UUID> {

    List<SampleDialogueEntity> findAllByOrderByCreatedAtDesc();

    @Query("SELECT d FROM SampleDialogueEntity d WHERE " +
            "(:active IS NULL OR d.active = :active) AND " +
            "(:pageId IS NULL OR d.pageId = :pageId OR (:includeGlobal = true AND d.pageId IS NULL)) AND " +
            "(:globalOnly = false OR d.pageId IS NULL) " +
            "ORDER BY d.createdAt DESC")
    List<SampleDialogueEntity> searchDialoguesByPage(@Param("active") Boolean active,
                                                     @Param("pageId") UUID pageId,
                                                     @Param("includeGlobal") boolean includeGlobal,
                                                     @Param("globalOnly") boolean globalOnly);

    @Query("SELECT d FROM SampleDialogueEntity d WHERE d.active = true AND d.reviewStatus = 'APPROVED' AND (d.pageId IS NULL OR d.pageId = :pageId) ORDER BY d.createdAt DESC")
    List<SampleDialogueEntity> findActiveApprovedForPage(@Param("pageId") UUID pageId);

    Optional<SampleDialogueEntity> findFirstByDialogueCodeAndPageId(String dialogueCode, UUID pageId);

    Optional<SampleDialogueEntity> findFirstByDialogueCodeAndPageIdIsNull(String dialogueCode);

    @Modifying
    @Transactional
    @Query(value = "UPDATE sample_dialogues SET embedding = CAST(:vectorLiteral AS vector) WHERE id = :id", nativeQuery = true)
    void updateEmbedding(@Param("id") UUID id, @Param("vectorLiteral") String vectorLiteral);

    @Query(value = """
            SELECT * FROM sample_dialogues
            WHERE active = true
              AND review_status = 'APPROVED'
              AND embedding IS NOT NULL
              AND (:pageId IS NULL OR page_id IS NULL OR page_id = :pageId)
            ORDER BY embedding <=> CAST(:queryVector AS vector)
            LIMIT :topK
            """, nativeQuery = true)
    List<SampleDialogueEntity> findSimilarApprovedDialogues(@Param("queryVector") String queryVector,
                                                            @Param("pageId") UUID pageId,
                                                            @Param("topK") int topK);
}
