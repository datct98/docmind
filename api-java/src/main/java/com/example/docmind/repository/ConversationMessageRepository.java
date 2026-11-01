package com.example.docmind.repository;

import com.example.docmind.entity.ConversationMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ConversationMessageRepository extends JpaRepository<ConversationMessageEntity, UUID> {

    List<ConversationMessageEntity> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    void deleteBySessionId(UUID sessionId);
}
