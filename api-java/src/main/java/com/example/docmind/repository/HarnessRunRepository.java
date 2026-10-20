package com.example.docmind.repository;

import com.example.docmind.entity.HarnessRunEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA Repository for {@link HarnessRunEntity}.
 * Provides query methods for audit history of Harness² optimization runs.
 */
@Repository
public interface HarnessRunRepository extends JpaRepository<HarnessRunEntity, String> {

    List<HarnessRunEntity> findByPageIdOrderByCreatedAtDesc(String pageId, Pageable pageable);

    List<HarnessRunEntity> findByPageIdOrderByCreatedAtDesc(String pageId);

    List<HarnessRunEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<HarnessRunEntity> findAllByOrderByCreatedAtDesc();

    List<HarnessRunEntity> findByVerdictOrderByCreatedAtDesc(String verdict, Pageable pageable);

    @Query("SELECT r FROM HarnessRunEntity r WHERE " +
           "(:pageId IS NULL OR :pageId = '' OR r.pageId = :pageId) AND " +
           "(:verdict IS NULL OR :verdict = '' OR r.verdict = :verdict) " +
           "ORDER BY r.createdAt DESC")
    List<HarnessRunEntity> findRunsWithFilter(
            @Param("pageId") String pageId,
            @Param("verdict") String verdict,
            Pageable pageable
    );
}
