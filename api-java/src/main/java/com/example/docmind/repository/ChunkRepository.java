package com.example.docmind.repository;

import com.example.docmind.entity.ChunkEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface ChunkRepository extends JpaRepository<ChunkEntity, UUID>, ChunkRepositoryCustom {

    @Modifying
    @Query("DELETE FROM ChunkEntity c WHERE c.documentId = :documentId")
    void deleteByDocumentId(@Param("documentId") UUID documentId);
}
