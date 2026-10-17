package com.example.docmind.repository;

import com.example.docmind.entity.LearnedRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface LearnedRuleRepository extends JpaRepository<LearnedRuleEntity, UUID>, LearnedRuleRepositoryCustom {

    List<LearnedRuleEntity> findByActiveTrue();

    List<LearnedRuleEntity> findByActive(boolean active);

    List<LearnedRuleEntity> findByCategory(String category);

    List<LearnedRuleEntity> findByCategoryAndActive(String category, boolean active);

    long countByPageId(UUID pageId);

    @Query("SELECT r FROM LearnedRuleEntity r WHERE r.active = true AND (r.pageId IS NULL OR r.pageId = :pageId)")
    List<LearnedRuleEntity> findActiveForPage(@Param("pageId") UUID pageId);

    @Query("SELECT r FROM LearnedRuleEntity r WHERE " +
            "(:active IS NULL OR r.active = :active) AND " +
            "(:category IS NULL OR :category = '' OR LOWER(r.category) = LOWER(:category)) AND " +
            "(:search IS NULL OR :search = '' OR LOWER(r.generalizedRule) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
            " LOWER(r.triggerCondition) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
            " LOWER(r.rootCause) LIKE LOWER(CONCAT('%', :search, '%')))")
    List<LearnedRuleEntity> searchRules(@Param("active") Boolean active,
                                        @Param("category") String category,
                                        @Param("search") String search);

    @Query("SELECT r FROM LearnedRuleEntity r WHERE " +
            "(:active IS NULL OR r.active = :active) AND " +
            "(:category IS NULL OR :category = '' OR LOWER(r.category) = LOWER(:category)) AND " +
            "(:pageId IS NULL OR r.pageId = :pageId OR (:includeGlobal = true AND r.pageId IS NULL)) AND " +
            "(:globalOnly = false OR r.pageId IS NULL) AND " +
            "(:search IS NULL OR :search = '' OR LOWER(r.generalizedRule) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
            " LOWER(r.triggerCondition) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
            " LOWER(r.rootCause) LIKE LOWER(CONCAT('%', :search, '%')))")
    List<LearnedRuleEntity> searchRulesByPage(@Param("active") Boolean active,
                                              @Param("category") String category,
                                              @Param("search") String search,
                                              @Param("pageId") UUID pageId,
                                              @Param("includeGlobal") boolean includeGlobal,
                                              @Param("globalOnly") boolean globalOnly);
}
