package com.example.docmind.repository;

import com.example.docmind.entity.BlockEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface BlockRepository extends JpaRepository<BlockEntity, UUID> {

    List<BlockEntity> findByDocumentIdOrderByBlockOrderAsc(UUID documentId);

    @Modifying
    @Query("DELETE FROM BlockEntity b WHERE b.documentId = :documentId")
    void deleteByDocumentId(@Param("documentId") UUID documentId);
}
