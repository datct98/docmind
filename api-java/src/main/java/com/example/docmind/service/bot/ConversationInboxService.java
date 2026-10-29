package com.example.docmind.service.bot;

import com.example.docmind.client.FastApiAgentClient.ExtractedSampleDto;
import com.example.docmind.client.FastApiAgentClient.NightlyExtractRequest;
import com.example.docmind.client.FastApiAgentClient.NightlyExtractResponse;
import com.example.docmind.dto.LearnedRuleDto;
import com.example.docmind.dto.bot.CustomerInsightDto;
import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.dto.bot.FanpageBotResponse;
import com.example.docmind.entity.ConversationMessageEntity;
import com.example.docmind.entity.ConversationSessionEntity;
import com.example.docmind.entity.HarnessDimension;
import com.example.docmind.entity.LearnedRuleEntity;
import com.example.docmind.repository.ConversationMessageRepository;
import com.example.docmind.repository.ConversationSessionRepository;
import com.example.docmind.repository.LearnedRuleRepository;
import com.example.docmind.service.MetaLearningService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ConversationInboxService {

    private static final Logger log = LoggerFactory.getLogger(ConversationInboxService.class);

    private final ConversationSessionRepository sessionRepository;
    private final ConversationMessageRepository messageRepository;
    private final FanpageBotService fanpageBotService;
    private final FastApiAgentService fastApiAgentService;
    private final MetaLearningService metaLearningService;
    private final LearnedRuleRepository learnedRuleRepository;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    public ConversationInboxService(
            ConversationSessionRepository sessionRepository,
            ConversationMessageRepository messageRepository,
            FanpageBotService fanpageBotService,
            @Autowired(required = false) FastApiAgentService fastApiAgentService,
            @Autowired(required = false) MetaLearningService metaLearningService,
            @Autowired(required = false) LearnedRuleRepository learnedRuleRepository) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.fanpageBotService = fanpageBotService;
        this.fastApiAgentService = fastApiAgentService;
        this.metaLearningService = metaLearningService;
        this.learnedRuleRepository = learnedRuleRepository;
    }

    public Map<String, Object> listSessions(UUID pageId, String controlMode, String search) {
        if (pageId == null) {
            Map<String, Object> emptyResult = new LinkedHashMap<>();
            emptyResult.put("total_count", 0);
            emptyResult.put("needs_handoff_count", 0);
            emptyResult.put("converted_count", 0);
            emptyResult.put("bot_active_count", 0);
            emptyResult.put("human_takeover_count", 0);
            emptyResult.put("has_phone_count", 0);
            emptyResult.put("sessions", List.of());
            return emptyResult;
        }

        List<ConversationSessionEntity> scoped = sessionRepository.findByPageIdOrderByLastMessageAtDesc(pageId);

        int needsHandoffCount = 0;
        int convertedCount = 0;
        int botActiveCount = 0;
        int humanTakeoverCount = 0;
        int hasPhoneCount = 0;

        for (ConversationSessionEntity s : scoped) {
            String mode = s.getControlMode() != null ? s.getControlMode().toUpperCase(Locale.ROOT) : "BOT_ACTIVE";
            switch (mode) {
                case "NEEDS_HANDOFF" -> needsHandoffCount++;
                case "CONVERTED" -> convertedCount++;
                case "HUMAN_TAKEOVER" -> humanTakeoverCount++;
                default -> botActiveCount++;
            }
            if (s.getCapturedPhone() != null && !s.getCapturedPhone().isBlank()) {
                hasPhoneCount++;
            }
        }

        List<Map<String, Object>> filtered = new ArrayList<>();
        String normFilter = controlMode != null ? controlMode.trim().toUpperCase(Locale.ROOT) : "";
        String normSearch = search != null ? search.trim().toLowerCase(Locale.ROOT) : "";

        for (ConversationSessionEntity s : scoped) {
            if (!normFilter.isEmpty() && !"ALL".equals(normFilter)) {
                if ("HAS_PHONE".equals(normFilter)) {
                    if (s.getCapturedPhone() == null || s.getCapturedPhone().isBlank()) {
                        continue;
                    }
                } else if (!normFilter.equalsIgnoreCase(s.getControlMode())) {
                    continue;
                }
            }
            if (!normSearch.isEmpty()) {
                String haystack = ((s.getCustomerName() != null ? s.getCustomerName() : "") + " "
                        + (s.getCustomerPsid() != null ? s.getCustomerPsid() : "") + " "
                        + (s.getCapturedPhone() != null ? s.getCapturedPhone() : "") + " "
                        + (s.getActiveTopic() != null ? s.getActiveTopic() : "") + " "
                        + (s.getLastMessagePreview() != null ? s.getLastMessagePreview() : "")).toLowerCase(Locale.ROOT);
                if (!haystack.contains(normSearch)) {
                    continue;
                }
            }
            filtered.add(toSessionSummaryMap(s));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total_count", scoped.size());
        result.put("needs_handoff_count", needsHandoffCount);
        result.put("converted_count", convertedCount);
        result.put("bot_active_count", botActiveCount);
        result.put("human_takeover_count", humanTakeoverCount);
        result.put("has_phone_count", hasPhoneCount);
        result.put("sessions", filtered);
        return result;
    }

    public Map<String, Object> getSessionDetail(UUID sessionId) {
        ConversationSessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy phiên hội thoại: " + sessionId));
        List<ConversationMessageEntity> msgs = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        return toSessionDetailMap(session, msgs);
    }

    @Transactional
    public void recordCustomerAndBotTurn(FanpageBotRequest request, FanpageBotResponse botResponse) {
        if (request == null || botResponse == null) {
            return;
        }
        try {
            UUID pageId = parseUuidOrNull(request.pageId());
            String psid = (request.senderId() != null && !request.senderId().isBlank())
                    ? request.senderId().strip()
                    : "psid-sim-default";
            String custMsg = FanpageBotService.mergeBurstMessages(request);
            if (custMsg.isBlank()) {
                return;
            }

            ConversationSessionEntity session = findOrCreateSession(pageId, psid, request.customerName());
            applyBotResponseToSession(session, custMsg, botResponse);
            session = sessionRepository.saveAndFlush(session);
            Instant now = Instant.now();

            ConversationMessageEntity userMsgEntity = ConversationMessageEntity.builder()
                    .id(UUID.randomUUID())
                    .sessionId(session.getId())
                    .senderRole("CUSTOMER")
                    .content(custMsg)
                    .createdAt(now)
                    .build();
            messageRepository.save(userMsgEntity);

            ConversationMessageEntity botMsgEntity = ConversationMessageEntity.builder()
                    .id(UUID.randomUUID())
                    .sessionId(session.getId())
                    .senderRole("AI_BOT")
                    .content(botResponse.replyText() != null ? botResponse.replyText() : "")
                    .metadataJson(serializeBotMetadata(botResponse))
                    .createdAt(now.plusMillis(100))
                    .build();
            messageRepository.save(botMsgEntity);
        } catch (Exception ex) {
            log.warn("Không thể đồng bộ lượt chat vào ConversationInbox: {}", ex.getMessage());
        }
    }

    @Transactional
    public Map<String, Object> sendCustomerTurnInSession(UUID sessionId, String customerMessage) {
        if (customerMessage == null || customerMessage.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tin nhắn không được để trống.");
        }
        ConversationSessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy phiên hội thoại."));

        List<ConversationMessageEntity> existingMsgs = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        List<FanpageBotRequest.ConversationTurn> history = new ArrayList<>();
        for (ConversationMessageEntity m : existingMsgs) {
            String role = "CUSTOMER".equalsIgnoreCase(m.getSenderRole()) ? "user" : "assistant";
            history.add(new FanpageBotRequest.ConversationTurn(role, m.getContent()));
        }

        Instant now = Instant.now();
        ConversationMessageEntity custEntity = ConversationMessageEntity.builder()
                .id(UUID.randomUUID())
                .sessionId(sessionId)
                .senderRole("CUSTOMER")
                .content(customerMessage.strip())
                .createdAt(now)
                .build();
        messageRepository.save(custEntity);

        // Nếu đang ở chế độ HUMAN_TAKEOVER (Sale đang trực tiếp tiếp quản và đã tắt AI):
        // Chỉ lưu tin nhắn của khách hàng để Sale trả lời, AI không nhảy vào cướp lời Sale!
        if ("HUMAN_TAKEOVER".equalsIgnoreCase(session.getControlMode())) {
            session.setLastMessagePreview(customerMessage.strip());
            session.setLastSenderRole("CUSTOMER");
            session.setLastMessageAt(now);
            session.setUpdatedAt(now);
            sessionRepository.save(session);
            List<ConversationMessageEntity> updatedMsgs = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
            return toSessionDetailMap(session, updatedMsgs);
        }

        FanpageBotRequest botReq = new FanpageBotRequest(
                session.getPageId() != null ? session.getPageId().toString() : null,
                session.getCustomerPsid(),
                session.getCustomerName(),
                customerMessage.strip(),
                null,
                history,
                null,
                5,
                session.getBuyingTemperature(),
                null,
                null,
                null
        );

        FanpageBotResponse botResp = fanpageBotService.handleCustomerMessage(botReq);
        ConversationMessageEntity botEntity = ConversationMessageEntity.builder()
                .id(UUID.randomUUID())
                .sessionId(sessionId)
                .senderRole("AI_BOT")
                .content(botResp.replyText() != null ? botResp.replyText() : "")
                .metadataJson(serializeBotMetadata(botResp))
                .createdAt(Instant.now())
                .build();
        messageRepository.save(botEntity);

        applyBotResponseToSession(session, customerMessage.strip(), botResp);
        sessionRepository.save(session);

        List<ConversationMessageEntity> updatedMsgs = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        return toSessionDetailMap(session, updatedMsgs);
    }

    @Transactional
    public Map<String, Object> sendHumanSaleReply(UUID sessionId, String saleReply, boolean markConverted) {
        if (saleReply == null || saleReply.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nội dung phản hồi của Sale không được để trống.");
        }
        ConversationSessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy phiên hội thoại."));

        Instant now = Instant.now();
        ConversationMessageEntity saleMsg = ConversationMessageEntity.builder()
                .id(UUID.randomUUID())
                .sessionId(sessionId)
                .senderRole("HUMAN_SALE")
                .content(saleReply.strip())
                .createdAt(now)
                .build();
        messageRepository.save(saleMsg);

        session.setHasHumanSaleReply(true);
        session.setLastMessagePreview(saleReply.strip());
        session.setLastSenderRole("HUMAN_SALE");
        session.setLastMessageAt(now);
        session.setUpdatedAt(now);
        if (markConverted) {
            session.setControlMode("CONVERTED");
            session.setBuyingTemperature(Math.max(session.getBuyingTemperature(), 90));
            session.setTemperatureLabel("HOT");
        } else if (!"CONVERTED".equalsIgnoreCase(session.getControlMode())) {
            session.setControlMode("HUMAN_TAKEOVER");
        }
        sessionRepository.save(session);

        List<ConversationMessageEntity> msgs = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        return toSessionDetailMap(session, msgs);
    }

    @Transactional
    public Map<String, Object> updateControlMode(UUID sessionId, String controlMode) {
        ConversationSessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy phiên hội thoại."));
        String normalized = controlMode != null ? controlMode.trim().toUpperCase(Locale.ROOT) : "BOT_ACTIVE";
        if (!List.of("BOT_ACTIVE", "NEEDS_HANDOFF", "HUMAN_TAKEOVER", "CONVERTED").contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "control_mode không hợp lệ: " + controlMode);
        }
        session.setControlMode(normalized);
        session.setUpdatedAt(Instant.now());
        sessionRepository.save(session);
        List<ConversationMessageEntity> msgs = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        return toSessionDetailMap(session, msgs);
    }

    @Transactional
    public Map<String, Object> createNewCustomerSession(UUID pageId, String customerName, String initialMessage) {
        String name = (customerName != null && !customerName.isBlank())
                ? customerName.strip()
                : "Khách hàng #" + (1000 + (int) (Math.random() * 8999));
        String psid = "psid-fb-" + UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();

        ConversationSessionEntity session = ConversationSessionEntity.builder()
                .id(UUID.randomUUID())
                .pageId(pageId)
                .customerPsid(psid)
                .customerName(name)
                .honorific(name.toLowerCase(Locale.ROOT).startsWith("anh ") ? "Anh" : "Chị")
                .activeTopic("Tư vấn dịch vụ chuẩn Y khoa")
                .topicFamily("GENERAL")
                .controlMode("BOT_ACTIVE")
                .buyingTemperature(45)
                .temperatureLabel("WARM")
                .momentum("STABLE")
                .buyerArchetype("EXPLORING_CONSULTATION")
                .archetypeLabel("Đang tìm hiểu giải pháp")
                .salesStage("DISCOVERY")
                .lastMessagePreview(initialMessage != null ? initialMessage.strip() : "Phiên hội thoại mới")
                .lastSenderRole("CUSTOMER")
                .lastMessageAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
        session = sessionRepository.saveAndFlush(session);

        if (initialMessage != null && !initialMessage.isBlank()) {
            return sendCustomerTurnInSession(session.getId(), initialMessage.strip());
        }
        return toSessionDetailMap(session, List.of());
    }

    @Transactional
    public Map<String, Object> extractRuleFromSession(UUID sessionId) {
        ConversationSessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy phiên hội thoại."));
        List<ConversationMessageEntity> msgs = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);

        String lastCustomerQuestion = "";
        String bestSaleReply = "";
        for (ConversationMessageEntity m : msgs) {
            if ("CUSTOMER".equalsIgnoreCase(m.getSenderRole())) {
                if (lastCustomerQuestion.isEmpty()) {
                    lastCustomerQuestion = m.getContent();
                } else if (bestSaleReply.isEmpty()) {
                    lastCustomerQuestion = m.getContent();
                }
            } else if ("HUMAN_SALE".equalsIgnoreCase(m.getSenderRole())) {
                if (bestSaleReply.isEmpty() || m.getContent().length() > bestSaleReply.length()) {
                    bestSaleReply = m.getContent();
                }
            }
        }
        if (bestSaleReply.isEmpty()) {
            for (ConversationMessageEntity m : msgs) {
                if ("AI_BOT".equalsIgnoreCase(m.getSenderRole()) && m.getContent().length() > bestSaleReply.length()) {
                    bestSaleReply = m.getContent();
                }
            }
        }
        if (lastCustomerQuestion.isEmpty()) {
            lastCustomerQuestion = session.getActiveTopic();
        }

        NightlyExtractRequest extractReq = new NightlyExtractRequest(
                session.getPageId() != null ? session.getPageId().toString() : null,
                List.of(Map.of(
                        "customer_message", lastCustomerQuestion,
                        "sale_reply", bestSaleReply
                ))
        );

        NightlyExtractResponse auditResp = fastApiAgentService != null
                ? fastApiAgentService.extractNightlySamples(extractReq)
                : new FastApiAgentService(null, new com.example.docmind.config.DocServiceProperties()).extractNightlySamples(extractReq);

        List<LearnedRuleDto> stagedRules = new ArrayList<>();
        if (auditResp.samples() != null) {
            for (ExtractedSampleDto s : auditResp.samples()) {
                if (s.securityPassed() && "PENDING_REVIEW".equalsIgnoreCase(s.reviewStatus())) {
                    HarnessDimension dim = "DOM".equalsIgnoreCase(s.dimension()) ? HarnessDimension.DOM : HarnessDimension.INV;
                    String[] kws = s.retrievalKeywords() != null
                            ? s.retrievalKeywords().toArray(new String[0])
                            : new String[]{"tư vấn", "chốt lịch"};
                    LearnedRuleEntity saved = LearnedRuleEntity.builder()
                            .id(UUID.randomUUID())
                            .category(s.category() != null ? s.category() : "PSYCHOLOGICAL_PERSUASION")
                            .triggerCondition(s.triggerCondition() != null ? s.triggerCondition() : "")
                            .rootCause(s.rootCause() != null ? s.rootCause() : "")
                            .generalizedRule(s.generalizedRule() != null ? s.generalizedRule() : "")
                            .retrievalKeywords(kws)
                            .active(false)
                            .pageId(session.getPageId())
                            .confidenceScore(0.90)
                            .dimension(dim)
                            .cueSignal(s.cueSignal())
                            .contrastiveEvidence(s.contrastiveEvidence())
                            .reviewStatus("PENDING_REVIEW")
                            .securityAuditNotes(s.securityAuditNotes())
                            .createdAt(Instant.now())
                            .updatedAt(Instant.now())
                            .build();
                    if (learnedRuleRepository != null) {
                        learnedRuleRepository.save(saved);
                    }
                    stagedRules.add(LearnedRuleDto.fromEntity(saved));
                }
            }
        }

        session.setLearnedInColdPath(true);
        session.setUpdatedAt(Instant.now());
        sessionRepository.save(session);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session_id", sessionId);
        out.put("staged_count", stagedRules.size());
        out.put("passed_count", auditResp.passedCount());
        out.put("rejected_count", auditResp.rejectedCount());
        out.put("staged_rules", stagedRules);
        return out;
    }

    public List<Map<String, Object>> collectUnlearnedConvertedSaleConversations(UUID pageId) {
        if (pageId == null) {
            return List.of();
        }
        List<ConversationSessionEntity> sessions = sessionRepository.findByPageIdOrderByLastMessageAtDesc(pageId);

        List<Map<String, Object>> convs = new ArrayList<>();
        for (ConversationSessionEntity s : sessions) {
            if (!s.isHasHumanSaleReply()) {
                continue;
            }
            List<ConversationMessageEntity> msgs = messageRepository.findBySessionIdOrderByCreatedAtAsc(s.getId());
            String custMsg = "";
            String saleMsg = "";
            for (ConversationMessageEntity m : msgs) {
                if ("CUSTOMER".equalsIgnoreCase(m.getSenderRole()) && saleMsg.isEmpty()) {
                    custMsg = m.getContent();
                } else if ("HUMAN_SALE".equalsIgnoreCase(m.getSenderRole()) && m.getContent().length() > saleMsg.length()) {
                    saleMsg = m.getContent();
                }
            }
            if (!custMsg.isBlank() && !saleMsg.isBlank()) {
                convs.add(Map.of(
                        "session_id", s.getId().toString(),
                        "customer_message", custMsg,
                        "sale_reply", saleMsg
                ));
            }
        }
        return convs;
    }

    private ConversationSessionEntity findOrCreateSession(UUID pageId, String psid, String customerName) {
        Optional<ConversationSessionEntity> existing = pageId != null
                ? sessionRepository.findFirstByPageIdAndCustomerPsidOrderByUpdatedAtDesc(pageId, psid)
                : sessionRepository.findFirstByCustomerPsidOrderByUpdatedAtDesc(psid);
        if (existing.isPresent()) {
            ConversationSessionEntity found = existing.get();
            if (customerName != null && !customerName.isBlank() && !"Khách hàng".equalsIgnoreCase(customerName.strip())) {
                found.setCustomerName(customerName.strip());
            }
            return found;
        }
        Instant now = Instant.now();
        String displayName = (customerName != null && !customerName.isBlank())
                ? customerName.strip()
                : "Khách Messenger (" + psid + ")";
        return ConversationSessionEntity.builder()
                .id(UUID.randomUUID())
                .pageId(pageId)
                .customerPsid(psid)
                .customerName(displayName)
                .honorific("Chị")
                .activeTopic("Tư vấn dịch vụ chuẩn Y khoa")
                .topicFamily("GENERAL")
                .controlMode("BOT_ACTIVE")
                .buyingTemperature(45)
                .temperatureLabel("WARM")
                .momentum("STABLE")
                .buyerArchetype("EXPLORING_CONSULTATION")
                .archetypeLabel("Đang tìm hiểu giải pháp")
                .salesStage("DISCOVERY")
                .lastMessagePreview("")
                .lastSenderRole("CUSTOMER")
                .lastMessageAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private void applyBotResponseToSession(
            ConversationSessionEntity session,
            String customerMessage,
            FanpageBotResponse botResponse) {
        Instant now = Instant.now();
        session.setLastMessagePreview(botResponse.replyText() != null ? botResponse.replyText() : customerMessage);
        session.setLastSenderRole("AI_BOT");
        session.setLastMessageAt(now);
        session.setUpdatedAt(now);

        if (botResponse.salesStage() != null && !botResponse.salesStage().isBlank()) {
            session.setSalesStage(botResponse.salesStage());
        }
        if (botResponse.replyText() != null && botResponse.replyText().contains("Anh")) {
            session.setHonorific("Anh");
        }
        if (botResponse.extractedLead() != null) {
            if (botResponse.extractedLead().phoneNumber() != null && !botResponse.extractedLead().phoneNumber().isBlank()) {
                session.setCapturedPhone(botResponse.extractedLead().phoneNumber());
            }
            if (botResponse.extractedLead().preferredTime() != null && !botResponse.extractedLead().preferredTime().isBlank()) {
                session.setPreferredTime(botResponse.extractedLead().preferredTime());
            }
        }
        CustomerInsightDto insight = botResponse.customerInsight();
        if (insight != null) {
            session.setBuyingTemperature(insight.buyingTemperature());
            session.setPreviousTemperature(insight.previousTemperature());
            session.setTemperatureDelta(insight.temperatureDelta() != null ? insight.temperatureDelta() : 0);
            if (insight.temperatureLabel() != null) session.setTemperatureLabel(insight.temperatureLabel());
            if (insight.momentum() != null) session.setMomentum(insight.momentum());
            if (insight.buyerArchetype() != null) session.setBuyerArchetype(insight.buyerArchetype());
            if (insight.archetypeLabel() != null) session.setArchetypeLabel(insight.archetypeLabel());
            if (insight.extractedPains() != null && !insight.extractedPains().isEmpty()) {
                session.setExtractedPains(String.join("; ", insight.extractedPains()));
            }
            if (insight.hiddenConcerns() != null && !insight.hiddenConcerns().isEmpty()) {
                session.setHiddenConcerns(String.join("; ", insight.hiddenConcerns()));
            }
            if (insight.recommendedStrategy() != null) {
                session.setRecommendedStrategy(insight.recommendedStrategy());
            }
        }
        if (botResponse.needsHumanHandoff()) {
            session.setHandoffCategory(botResponse.handoffCategory());
            session.setHandoffReason(botResponse.handoffReason());
            if (!"HUMAN_TAKEOVER".equalsIgnoreCase(session.getControlMode())
                    && !"CONVERTED".equalsIgnoreCase(session.getControlMode())) {
                session.setControlMode("NEEDS_HANDOFF");
            }
        }
    }

    private String serializeBotMetadata(FanpageBotResponse resp) {
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("sales_stage", resp.salesStage());
            meta.put("confidence_score", resp.confidenceScore());
            meta.put("triggered_hooks", resp.triggeredHooks() != null ? resp.triggeredHooks() : List.of());
            meta.put("active_skills", resp.activeSkills() != null
                    ? resp.activeSkills().stream()
                            .filter(s -> s != null)
                            .map(s -> s.code() != null ? s.code() : s.name())
                            .filter(s -> s != null && !s.isBlank())
                            .toList()
                    : List.of());
            meta.put("applied_rules", resp.appliedRules() != null
                    ? resp.appliedRules().stream()
                            .filter(r -> r != null && r.id() != null)
                            .map(r -> r.id().toString())
                            .toList()
                    : List.of());
            meta.put("needs_human_handoff", resp.needsHumanHandoff());
            meta.put("handoff_reason", resp.handoffReason());
            meta.put("reply_bubbles", resp.replyBubbles() != null
                    ? resp.replyBubbles().stream()
                            .filter(b -> b != null && b.text() != null && !b.text().isBlank())
                            .map(FanpageBotResponse.ReplyBubble::text)
                            .toList()
                    : List.of());
            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            log.warn("Không thể tuần tự hóa metadata tin nhắn AI_BOT: {}", e.getMessage());
            return "{}";
        }
    }

    private Map<String, Object> toSessionSummaryMap(ConversationSessionEntity s) {
        Map<String, Object> m = new LinkedHashMap<>();
        String mode = s.getControlMode() != null ? s.getControlMode() : "BOT_ACTIVE";
        boolean needsHandoff = "NEEDS_HANDOFF".equalsIgnoreCase(mode)
                || (s.getHandoffReason() != null && !s.getHandoffReason().isBlank()
                && !"HUMAN_TAKEOVER".equalsIgnoreCase(mode) && !"CONVERTED".equalsIgnoreCase(mode));
        List<String> pains = splitSemicolonList(s.getExtractedPains());
        List<String> concerns = splitSemicolonList(s.getHiddenConcerns());

        Map<String, Object> insight = new LinkedHashMap<>();
        insight.put("buyer_archetype", s.getBuyerArchetype());
        insight.put("archetype_label", s.getArchetypeLabel());
        insight.put("buying_temperature", s.getBuyingTemperature());
        insight.put("previous_temperature", s.getPreviousTemperature());
        insight.put("temperature_delta", s.getTemperatureDelta());
        insight.put("temperature_label", s.getTemperatureLabel());
        insight.put("momentum", s.getMomentum());
        insight.put("honorific", s.getHonorific());
        insight.put("active_topic", s.getActiveTopic());
        insight.put("extracted_pains", pains);
        insight.put("hidden_concerns", concerns);
        insight.put("recommended_strategy", s.getRecommendedStrategy());

        m.put("id", s.getId());
        m.put("page_id", s.getPageId());
        m.put("customer_psid", s.getCustomerPsid());
        m.put("sender_psid", s.getCustomerPsid());
        m.put("customer_name", s.getCustomerName());
        m.put("channel", "MESSENGER");
        m.put("honorific", s.getHonorific());
        m.put("active_topic", s.getActiveTopic());
        m.put("topic_family", s.getTopicFamily());
        m.put("control_mode", "HUMAN_TAKEOVER".equalsIgnoreCase(mode) ? "HUMAN_TAKEOVER" : "BOT_ACTIVE");
        m.put("status", mode);
        m.put("needs_human_handoff", needsHandoff);
        m.put("handoff_category", s.getHandoffCategory());
        m.put("handoff_reason", s.getHandoffReason());
        m.put("captured_phone", s.getCapturedPhone());
        m.put("preferred_time", s.getPreferredTime());
        m.put("buying_temperature", s.getBuyingTemperature());
        m.put("previous_temperature", s.getPreviousTemperature());
        m.put("temperature_delta", s.getTemperatureDelta());
        m.put("temperature_label", s.getTemperatureLabel());
        m.put("momentum", s.getMomentum());
        m.put("buyer_archetype", s.getArchetypeLabel() != null ? s.getArchetypeLabel() : s.getBuyerArchetype());
        m.put("archetype_label", s.getArchetypeLabel());
        m.put("extracted_pains", pains);
        m.put("hidden_concerns", concerns);
        m.put("recommended_strategy", s.getRecommendedStrategy());
        m.put("customer_insight", insight);
        m.put("sales_stage", s.getSalesStage());
        m.put("has_human_sale_reply", s.isHasHumanSaleReply());
        m.put("learned_in_cold_path", s.isLearnedInColdPath());
        m.put("last_message_preview", s.getLastMessagePreview());
        m.put("last_sender_role", s.getLastSenderRole());
        m.put("last_message_at", s.getLastMessageAt());
        m.put("last_activity_at", s.getLastMessageAt());
        m.put("created_at", s.getCreatedAt());
        m.put("updated_at", s.getUpdatedAt());
        return m;
    }

    private Map<String, Object> toSessionDetailMap(ConversationSessionEntity s, List<ConversationMessageEntity> msgs) {
        Map<String, Object> summary = toSessionSummaryMap(s);
        Map<String, Object> detail = new LinkedHashMap<>(summary);
        List<Map<String, Object>> msgList = new ArrayList<>(msgs.size());
        for (ConversationMessageEntity msg : msgs) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", msg.getId());
            item.put("session_id", msg.getSessionId());
            item.put("sender_role", msg.getSenderRole());
            item.put("sender_name", "CUSTOMER".equalsIgnoreCase(msg.getSenderRole())
                    ? s.getCustomerName()
                    : ("HUMAN_SALE".equalsIgnoreCase(msg.getSenderRole()) ? "Nhân viên Sale" : "AI Trợ Lý Fanpage"));
            item.put("content", msg.getContent());
            item.put("sales_stage", s.getSalesStage());
            item.put("created_at", msg.getCreatedAt());
            if (msg.getMetadataJson() != null && !msg.getMetadataJson().isBlank()) {
                try {
                    Map<?, ?> meta = objectMapper.readValue(msg.getMetadataJson(), Map.class);
                    item.put("metadata", meta);
                    if (meta.get("sales_stage") != null) {
                        item.put("sales_stage", meta.get("sales_stage"));
                    }
                    if (meta.get("triggered_hooks") != null) {
                        item.put("triggered_hooks", meta.get("triggered_hooks"));
                    }
                    if (meta.get("reply_bubbles") != null) {
                        item.put("reply_bubbles", meta.get("reply_bubbles"));
                    }
                } catch (Exception ignored) {
                }
            }
            msgList.add(item);
        }
        detail.put("session", summary);
        detail.put("messages", msgList);
        detail.put("bot_paused_by_human_takeover", "HUMAN_TAKEOVER".equalsIgnoreCase(s.getControlMode()));
        return detail;
    }

    private static List<String> splitSemicolonList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(";"))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toList();
    }

    private static UUID parseUuidOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
