package com.example.docmind.repository;

import com.example.docmind.entity.DocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<DocumentEntity, UUID> {

    Optional<DocumentEntity> findBySha256(String sha256);

    List<DocumentEntity> findAllByOrderByCreatedAtDesc();

    List<DocumentEntity> findByPageIdOrderByCreatedAtDesc(UUID pageId);

    List<DocumentEntity> findByPageIdIsNullOrderByCreatedAtDesc();

    long countByPageId(UUID pageId);
}
