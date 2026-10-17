package com.example.docmind.repository;

import com.example.docmind.entity.KnowledgePageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface KnowledgePageRepository extends JpaRepository<KnowledgePageEntity, UUID> {

    List<KnowledgePageEntity> findAllByOrderByCreatedAtDesc();
}
