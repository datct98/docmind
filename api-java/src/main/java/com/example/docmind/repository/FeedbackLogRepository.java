package com.example.docmind.repository;

import com.example.docmind.entity.FeedbackLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FeedbackLogRepository extends JpaRepository<FeedbackLogEntity, UUID> {

    List<FeedbackLogEntity> findByExtractedRuleId(UUID extractedRuleId);

    List<FeedbackLogEntity> findByConversationId(String conversationId);
}
