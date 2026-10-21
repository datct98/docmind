package com.example.docmind.repository;

import com.example.docmind.entity.ConversationSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationSessionRepository extends JpaRepository<ConversationSessionEntity, UUID> {

    List<ConversationSessionEntity> findAllByOrderByLastMessageAtDesc();

    List<ConversationSessionEntity> findByPageIdOrderByLastMessageAtDesc(UUID pageId);

    Optional<ConversationSessionEntity> findFirstByPageIdAndCustomerPsidOrderByUpdatedAtDesc(UUID pageId, String customerPsid);

    Optional<ConversationSessionEntity> findFirstByCustomerPsidOrderByUpdatedAtDesc(String customerPsid);
}
