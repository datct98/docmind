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
import com.example.docmind.repository.KnowledgePageRepository;
import com.example.docmind.repository.LearnedRuleRepository;
import com.example.docmind.service.MetaLearningService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.regex.Pattern;

@Service
public class ConversationInboxService {

    private static final Logger log = LoggerFactory.getLogger(ConversationInboxService.class);

    /**
     * Tiền tố an toàn bắt buộc cho HARD_RESET trong phạm vi Simulator/Test:
     * từ chối tuyệt đối cả 'fb-customer-demo' không hậu tố lẫn mọi PSID khách thật trên Live Inbox.
     */
    private static final Pattern SAFE_SIMULATOR_OR_TEST_SENDER_PATTERN = Pattern.compile(
            "^(?:fb-customer-demo-[A-Za-z0-9_-]{3,}|sim[_-][A-Za-z0-9_-]{2,}|test[_-][A-Za-z0-9_-]{2,}|relgate_[A-Za-z0-9_-]{2,}|p35[_-][A-Za-z0-9_-]{2,})$"
    );

    private final ConversationSessionRepository sessionRepository;
    private final ConversationMessageRepository messageRepository;
    private final FanpageBotService fanpageBotService;
    private final FastApiAgentService fastApiAgentService;
    private final MetaLearningService metaLearningService;
    private final LearnedRuleRepository learnedRuleRepository;
    private KnowledgePageRepository knowledgePageRepository;
    @Autowired(required = false)
    private InboundSessionPersistenceService inboundSessionPersistenceService;
    @Autowired(required = false)
    private ConversationSessionOptimisticMergeCoordinator sessionMergeCoordinator;
    @Autowired(required = false)
    private AgentBrainService agentBrainService;
    @Autowired(required = false)
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    @Value("${docmind.security.atomic-state-guard.enabled:true}")
    private volatile boolean atomicStateGuardEnabled = true;
    @Value("${docmind.fanpage.default-page-id:}")
    private volatile String defaultFanpageId = "";
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    public ConversationInboxService(
            ConversationSessionRepository sessionRepository,
            ConversationMessageRepository messageRepository,
            FanpageBotService fanpageBotService,
            @Autowired(required = false) FastApiAgentService fastApiAgentService,
            @Autowired(required = false) MetaLearningService metaLearningService,
            @Autowired(required = false) LearnedRuleRepository learnedRuleRepository,
            @Autowired(required = false) KnowledgePageRepository knowledgePageRepository) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.fanpageBotService = fanpageBotService;
        this.fastApiAgentService = fastApiAgentService;
        this.metaLearningService = metaLearningService;
        this.learnedRuleRepository = learnedRuleRepository;
        this.knowledgePageRepository = knowledgePageRepository;
    }

    public ConversationInboxService(
            ConversationSessionRepository sessionRepository,
            ConversationMessageRepository messageRepository,
            FanpageBotService fanpageBotService,
            FastApiAgentService fastApiAgentService,
            MetaLearningService metaLearningService,
            LearnedRuleRepository learnedRuleRepository) {
        this(sessionRepository, messageRepository, fanpageBotService,
                fastApiAgentService, metaLearningService, learnedRuleRepository, null);
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

    /**
     * RESUME_SESSION (Read-Only): Tra cứu phiên hiện có theo (pageId, senderId) mà KHÔNG tạo bản ghi mới nếu chưa tồn tại.
     */
    public Map<String, Object> findExistingSessionDetailBySender(UUID pageId, String senderId) {
        if (senderId == null || senderId.isBlank()) {
            return Map.of("found", false);
        }
        String psid = senderId.strip();
        Optional<ConversationSessionEntity> existing = pageId != null
                ? sessionRepository.findFirstByPageIdAndCustomerPsidOrderByUpdatedAtDesc(pageId, psid)
                : sessionRepository.findFirstByCustomerPsidOrderByUpdatedAtDesc(psid);
        if (existing.isEmpty()) {
            Map<String, Object> notFound = new LinkedHashMap<>();
            notFound.put("found", false);
            notFound.put("page_id", pageId);
            notFound.put("sender_id", psid);
            return notFound;
        }
        ConversationSessionEntity session = existing.get().copySnapshot();
        syncFreshGuardFieldsFromDb(session);
        List<ConversationMessageEntity> allMsgs = messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
        Instant resetBoundary = extractMemoryResetBoundary(session.getAciMemoryStateJson());
        List<ConversationMessageEntity> activeMsgs = (resetBoundary == null)
                ? allMsgs
                : allMsgs.stream()
                        .filter(m -> m.getCreatedAt() != null && !m.getCreatedAt().isBefore(resetBoundary))
                        .toList();
        Map<String, Object> detail = toSessionDetailMap(session, activeMsgs);
        detail.put("found", true);
        return detail;
    }

    private Instant extractMemoryResetBoundary(String aciMemoryStateJson) {
        if (aciMemoryStateJson == null || aciMemoryStateJson.isBlank()) {
            return null;
        }
        try {
            var root = objectMapper.readTree(aciMemoryStateJson);
            var node = root != null ? root.get("memory_reset_at") : null;
            if (node != null && !node.isNull() && !node.asText().isBlank()) {
                return Instant.parse(node.asText().strip());
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static boolean isSafeSimulatorOrTestSenderId(String senderId) {
        if (senderId == null || senderId.isBlank()) {
            return false;
        }
        return SAFE_SIMULATOR_OR_TEST_SENDER_PATTERN.matcher(senderId.strip()).matches();
    }

    @Transactional
    public Map<String, Object> hardResetSimulatorSession(UUID pageId, String senderId, boolean confirmHardReset) {
        return hardResetSimulatorSession(pageId, senderId, confirmHardReset, false);
    }

    /**
     * HARD_RESET có kiểm tra tiền tố an toàn:
     * - Chỉ cho phép khi confirmHardReset == true và senderId khớp tiền tố test/simulator độc lập.
     * - Từ chối 403 đối với 'fb-customer-demo' không hậu tố và mọi PSID khách thật trên Live Inbox.
     * - Từ chối 409 đối với bất kỳ phiên nào đang ở trạng thái HUMAN_TAKEOVER hoặc OPT_OUT (trừ khi test cleanup tường minh).
     */
    @Transactional
    public Map<String, Object> hardResetSimulatorSession(
            UUID pageId,
            String senderId,
            boolean confirmHardReset,
            boolean allowOptOutTestCleanup
    ) {
        if (!confirmHardReset) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "HARD_RESET yêu cầu xác nhận tường minh confirm_hard_reset=true.");
        }
        String psid = senderId != null ? senderId.strip() : "";
        if (!isSafeSimulatorOrTestSenderId(psid)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Từ chối HARD_RESET: sender_id không thuộc tiền tố phiên test/simulator an toàn: " + psid
            );
        }

        List<ConversationSessionEntity> matching = pageId != null
                ? sessionRepository.findByPageIdAndCustomerPsid(pageId, psid)
                : sessionRepository.findByCustomerPsid(psid);

        for (ConversationSessionEntity s : matching) {
            ConversationSessionEntity fresh = s.copySnapshot();
            syncFreshGuardFieldsFromDb(fresh);
            if ("HUMAN_TAKEOVER".equalsIgnoreCase(fresh.getControlMode())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Từ chối HARD_RESET: phiên đang ở trạng thái HUMAN_TAKEOVER (" + fresh.getId() + ")."
                );
            }
            if (!allowOptOutTestCleanup && (fresh.isOptedOut() || fresh.getReOptInAt() != null)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Từ chối HARD_RESET: không được xóa trạng thái opted_out / re-consent của phiên (" + fresh.getId() + ")."
                );
            }
        }

        int deletedSessions = 0;
        for (ConversationSessionEntity s : matching) {
            UUID effPageId = s.getPageId() != null ? s.getPageId() : pageId;
            ConversationState.resetSessionState(effPageId, psid, null);
            SentBubbleLedger.reset(SentBubbleLedger.key(effPageId != null ? effPageId.toString() : null, psid, null));
            if (fanpageBotService != null && fanpageBotService.getAgentBrainService() != null) {
                fanpageBotService.getAgentBrainService().evictInMemoryCacheOnly(effPageId, psid);
            }
            messageRepository.deleteBySessionId(s.getId());
            ConversationSessionOptimisticMergeCoordinator effMerge = sessionMergeCoordinator != null
                    ? sessionMergeCoordinator
                    : ConversationSessionOptimisticMergeCoordinator.forMockOrPassthroughRepository(sessionRepository);
            effMerge.deleteSimulatorSessionById(s.getId());
            deletedSessions++;
        }

        ConversationState.resetSessionState(pageId, psid, null);
        SentBubbleLedger.reset(SentBubbleLedger.key(pageId != null ? pageId.toString() : null, psid, null));
        if (fanpageBotService != null && fanpageBotService.getAgentBrainService() != null) {
            fanpageBotService.getAgentBrainService().evictInMemoryCacheOnly(pageId, psid);
        }
        if (jdbcTemplate != null) {
            jdbcTemplate.update("DELETE FROM sent_bubble_ledger WHERE ledger_key LIKE ?", "%" + psid + "%");
        }

        log.info("[SIMULATOR-HARD-RESET] page_id={} sender_id={} deleted_sessions={}", pageId, psid, deletedSessions);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "HARD_RESET_COMPLETED");
        out.put("page_id", pageId);
        out.put("sender_id", psid);
        out.put("deleted_sessions", deletedSessions);
        return out;
    }

    public static boolean isSilentSuppressedBotResponse(FanpageBotResponse botResponse) {
        if (botResponse == null) {
            return true;
        }
        boolean emptyBubbles = botResponse.replyBubbles() == null || botResponse.replyBubbles().isEmpty();
        boolean emptyText = botResponse.replyText() == null || botResponse.replyText().isBlank();
        return emptyBubbles && emptyText;
    }

    public void setInboundSessionPersistenceService(InboundSessionPersistenceService inboundSessionPersistenceService) {
        this.inboundSessionPersistenceService = inboundSessionPersistenceService;
    }

    public void setSessionMergeCoordinator(ConversationSessionOptimisticMergeCoordinator sessionMergeCoordinator) {
        this.sessionMergeCoordinator = sessionMergeCoordinator;
    }

    private ConversationSessionOptimisticMergeCoordinator resolveMergeCoordinator() {
        ConversationSessionOptimisticMergeCoordinator existing = this.sessionMergeCoordinator;
        if (existing != null) {
            return existing;
        }
        return ConversationSessionOptimisticMergeCoordinator.forMockOrPassthroughRepository(this.sessionRepository);
    }

    private ConversationSessionEntity saveSessionMonotonically(
            ConversationSessionEntity detachedCandidate,
            ConversationSessionOptimisticMergeCoordinator.WriteOptions options
    ) {
        return resolveMergeCoordinator()
                .saveCandidateWithMonotonicMerge(detachedCandidate, options)
                .entity();
    }

    public void setAtomicStateGuardEnabled(boolean atomicStateGuardEnabled) {
        this.atomicStateGuardEnabled = atomicStateGuardEnabled;
    }

    public void setDefaultFanpageId(String defaultFanpageId) {
        this.defaultFanpageId = defaultFanpageId != null ? defaultFanpageId : "";
    }

    private void syncFreshGuardFieldsFromDb(ConversationSessionEntity session) {
        if (session == null || session.getId() == null || inboundSessionPersistenceService == null) {
            return;
        }
        InboundSessionPersistenceService.FreshSessionGuardState fresh =
                inboundSessionPersistenceService.readFreshSessionGuardState(session.getId());
        if (fresh == null) {
            return;
        }
        if (fresh.controlMode() != null && !fresh.controlMode().isBlank()) {
            session.setControlMode(fresh.controlMode());
        }
        session.setOptedOut(fresh.optedOut());
        session.setOptedOutAt(fresh.optedOutAt());
        session.setReOptInAt(fresh.reOptInAt());
        session.setReOptInSource(fresh.reOptInSource());
        session.setReOptInEvidence(fresh.reOptInEvidence());
    }

    @Transactional
    public void recordCustomerAndBotTurn(FanpageBotRequest request, FanpageBotResponse botResponse) {
        if (request == null || botResponse == null) {
            return;
        }
        if ("GENERATE_PRICE_ANCHOR_BUBBLE".equalsIgnoreCase(request.action())) {
            recordBotContinuation(request, botResponse);
            return;
        }
        if (botResponse.triggeredHooks() != null
                && botResponse.triggeredHooks().contains("DUPLICATE_INBOUND_EVENT_SUPPRESSED")) {
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

            String inboundExtMid = (botResponse.inboundExternalMid() != null && !botResponse.inboundExternalMid().isBlank())
                    ? botResponse.inboundExternalMid()
                    : InboundEventIdResolver.resolveForCurrentContext(request, custMsg).externalMid();

            ConversationSessionEntity session = findOrCreateSession(pageId, psid, request.customerName()).copySnapshot();
            syncFreshGuardFieldsFromDb(session);
            Instant now = Instant.now();

            if (isSilentSuppressedBotResponse(botResponse)) {
                session.setLastMessagePreview(custMsg);
                session.setLastSenderRole("CUSTOMER");
                session.setLastMessageAt(now);
                session.setUpdatedAt(now);
                session = saveSessionMonotonically(
                        session,
                        ConversationSessionOptimisticMergeCoordinator.WriteOptions.STANDARD_MONOTONIC
                );
                if (inboundSessionPersistenceService != null) {
                    inboundSessionPersistenceService.persistCustomerMessageForExistingSession(
                            session.getId(), custMsg, inboundExtMid, now
                    );
                } else {
                    ConversationMessageEntity userMsgEntity = ConversationMessageEntity.builder()
                            .id(UUID.randomUUID())
                            .sessionId(session.getId())
                            .senderRole("CUSTOMER")
                            .content(custMsg)
                            .externalMid(inboundExtMid)
                            .createdAt(now)
                            .build();
                    messageRepository.save(userMsgEntity);
                }
                return;
            }

            applyBotResponseToSession(session, custMsg, botResponse);
            session = saveSessionMonotonically(
                    session,
                    ConversationSessionOptimisticMergeCoordinator.WriteOptions.STANDARD_MONOTONIC
            );

            if (inboundSessionPersistenceService != null) {
                inboundSessionPersistenceService.persistCustomerMessageForExistingSession(
                        session.getId(), custMsg, inboundExtMid, now
                );
            } else {
                ConversationMessageEntity userMsgEntity = ConversationMessageEntity.builder()
                        .id(UUID.randomUUID())
                        .sessionId(session.getId())
                        .senderRole("CUSTOMER")
                        .content(custMsg)
                        .externalMid(inboundExtMid)
                        .createdAt(now)
                        .build();
                messageRepository.save(userMsgEntity);
            }

            ConversationMessageEntity botMsgEntity = ConversationMessageEntity.builder()
                    .id(UUID.randomUUID())
                    .sessionId(session.getId())
                    .senderRole("AI_BOT")
                    .content(botResponse.replyText() != null ? botResponse.replyText() : "")
                    .metadataJson(serializeBotMetadata(session, botResponse))
                    .createdAt(now.plusMillis(100))
                    .build();
            messageRepository.save(botMsgEntity);
        } catch (Exception ex) {
            log.warn("Không thể đồng bộ lượt chat vào ConversationInbox: {}", ex.getMessage());
        }
    }

    /** A continuation is not another customer turn and must not refresh inbound/opt-in state. */
    private void recordBotContinuation(FanpageBotRequest request, FanpageBotResponse response) {
        if (response.replyText() == null || response.replyText().isBlank()) return;
        UUID pageId = parseUuidOrNull(request.pageId());
        String psid = request.senderId();
        if (pageId == null || psid == null || psid.isBlank()) return;
        ConversationSessionEntity session = sessionRepository.findFirstByPageIdAndCustomerPsidOrderByUpdatedAtDesc(pageId, psid)
                .map(ConversationSessionEntity::copySnapshot).orElse(null);
        if (session == null) return;
        syncFreshGuardFieldsFromDb(session);
        applyBotResponseToSession(session, "", response);
        session = saveSessionMonotonically(session, ConversationSessionOptimisticMergeCoordinator.WriteOptions.STANDARD_MONOTONIC);
        messageRepository.save(ConversationMessageEntity.builder().id(UUID.randomUUID()).sessionId(session.getId())
                .senderRole("AI_BOT").content(response.replyText()).metadataJson(serializeBotMetadata(session, response))
                .createdAt(Instant.now()).build());
    }

    public Map<String, Object> sendCustomerTurnInSession(UUID sessionId, String customerMessage) {
        if (customerMessage == null || customerMessage.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tin nhắn không được để trống.");
        }
        ConversationSessionEntity session;
        List<ConversationMessageEntity> existingMsgs;
        try {
            session = sessionRepository.findById(sessionId)
                    .map(ConversationSessionEntity::copySnapshot)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy phiên hội thoại."));
            existingMsgs = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        } catch (ResponseStatusException rse) {
            throw rse;
        } catch (Exception dbEx) {
            throw new InboundPersistenceUnavailableException(
                    "Không thể truy cập phiên hội thoại do lỗi kết nối cơ sở dữ liệu: " + dbEx.getMessage(),
                    dbEx
            );
        }

        List<FanpageBotRequest.ConversationTurn> history = new ArrayList<>();
        for (ConversationMessageEntity m : existingMsgs) {
            String role = "CUSTOMER".equalsIgnoreCase(m.getSenderRole()) ? "user" : "assistant";
            history.add(new FanpageBotRequest.ConversationTurn(role, m.getContent()));
        }

        String trimmedCustomerMessage = customerMessage.strip();
        InboundEventIdResolver.ResolvedInboundEvent resolvedEvent =
                InboundEventIdResolver.resolveForCurrentContext(null, trimmedCustomerMessage);
        Instant now = Instant.now();

        if (inboundSessionPersistenceService != null) {
            boolean inserted = inboundSessionPersistenceService.persistCustomerMessageForExistingSession(
                    sessionId,
                    trimmedCustomerMessage,
                    resolvedEvent.externalMid(),
                    now
            );
            if (!inserted && resolvedEvent.stableCrossRequestId()) {
                return toSessionDetailMap(session, existingMsgs);
            }
        } else {
            try {
                ConversationMessageEntity custEntity = ConversationMessageEntity.builder()
                        .id(UUID.randomUUID())
                        .sessionId(sessionId)
                        .senderRole("CUSTOMER")
                        .content(trimmedCustomerMessage)
                        .externalMid(resolvedEvent.externalMid())
                        .createdAt(now)
                        .build();
                messageRepository.save(custEntity);
            } catch (Exception dbEx) {
                throw new InboundPersistenceUnavailableException(
                        "Không thể lưu tin nhắn khách hàng do lỗi kết nối cơ sở dữ liệu: " + dbEx.getMessage(),
                        dbEx
                );
            }
        }

        // Nếu đang ở chế độ HUMAN_TAKEOVER (Sale đang trực tiếp tiếp quản và đã tắt AI):
        // Chỉ lưu tin nhắn của khách hàng để Sale trả lời, AI không nhảy vào cướp lời Sale!
        // Scenario 6 (Unit P0.4): nếu khách gửi lệnh dừng nhận tin rõ ràng dưới HUMAN_TAKEOVER, vẫn cập nhật opted_out = true + opted_out_at.
        if ("HUMAN_TAKEOVER".equalsIgnoreCase(session.getControlMode())) {
            if (atomicStateGuardEnabled && InboundSessionPersistenceService.isExplicitCustomerOptOutCommand(trimmedCustomerMessage)) {
                session.setOptedOut(true);
                session.setOptedOutAt(now);
                ConversationState.syncOptedOutInSession(session.getPageId(), session.getCustomerPsid(), null, true);
                if (fanpageBotService != null && fanpageBotService.getAgentBrainService() != null) {
                    fanpageBotService.getAgentBrainService().syncOptedOutState(session.getPageId(), session.getCustomerPsid(), true);
                }
                InboundSessionPersistenceService.supersedeEpochForSender(session.getPageId(), defaultFanpageId, session.getCustomerPsid());
            }
            session.setLastMessagePreview(trimmedCustomerMessage);
            session.setLastSenderRole("CUSTOMER");
            session.setLastMessageAt(now);
            session.setUpdatedAt(now);
            session = saveSessionMonotonically(
                    session,
                    ConversationSessionOptimisticMergeCoordinator.WriteOptions.STANDARD_MONOTONIC
            );
            List<ConversationMessageEntity> updatedMsgs = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
            Map<String, Object> detail = toSessionDetailMap(session, updatedMsgs);
            detail.put("latest_bot_message_id", null);
            detail.put("reply_bubbles", List.of());
            detail.put("bubble_delays_ms", List.of());
            return detail;
        }

        FanpageBotRequest botReq = new FanpageBotRequest(
                session.getPageId() != null ? session.getPageId().toString() : null,
                session.getCustomerPsid(),
                session.getCustomerName(),
                trimmedCustomerMessage,
                null,
                history,
                null,
                5,
                session.getBuyingTemperature(),
                null,
                null,
                null
        );

        FanpageBotResponse botResp = InboundEventIdResolver.runWithPrePersistedEvent(
                resolvedEvent,
                () -> fanpageBotService.handleCustomerMessage(botReq)
        );
        syncFreshGuardFieldsFromDb(session);
        if (isSilentSuppressedBotResponse(botResp)) {
            session.setLastMessagePreview(trimmedCustomerMessage);
            session.setLastSenderRole("CUSTOMER");
            session.setLastMessageAt(now);
            session.setUpdatedAt(now);
            session = saveSessionMonotonically(
                    session,
                    ConversationSessionOptimisticMergeCoordinator.WriteOptions.STANDARD_MONOTONIC
            );
            List<ConversationMessageEntity> updatedMsgs = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
            Map<String, Object> detail = toSessionDetailMap(session, updatedMsgs);
            detail.put("latest_bot_message_id", null);
            detail.put("reply_bubbles", List.of());
            detail.put("bubble_delays_ms", List.of());
            return detail;
        }

        ConversationMessageEntity botEntity = ConversationMessageEntity.builder()
                .id(UUID.randomUUID())
                .sessionId(sessionId)
                .senderRole("AI_BOT")
                .content(botResp.replyText() != null ? botResp.replyText() : "")
                .metadataJson(serializeBotMetadata(session, botResp))
                .createdAt(Instant.now())
                .build();
        messageRepository.save(botEntity);

        applyBotResponseToSession(session, trimmedCustomerMessage, botResp);
        session = saveSessionMonotonically(
                session,
                ConversationSessionOptimisticMergeCoordinator.WriteOptions.STANDARD_MONOTONIC
        );

        List<ConversationMessageEntity> updatedMsgs = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        Map<String, Object> detail = toSessionDetailMap(session, updatedMsgs);
        List<Map<String, Object>> latestBubbles = serializeReplyBubbleList(botResp);
        detail.put("latest_bot_message_id", botEntity.getId());
        detail.put("reply_bubbles", latestBubbles);
        detail.put("bubble_delays_ms", botResp.bubbleDelaysMs() != null
                ? botResp.bubbleDelaysMs()
                : latestBubbles.stream().map(b -> (Integer) b.get("typing_delay_ms")).toList());
        return detail;
    }

    @Transactional
    public Map<String, Object> sendHumanSaleReply(UUID sessionId, String saleReply, boolean markConverted) {
        if (saleReply == null || saleReply.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nội dung phản hồi của Sale không được để trống.");
        }
        ConversationSessionEntity session = sessionRepository.findById(sessionId)
                .map(ConversationSessionEntity::copySnapshot)
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
        session = saveSessionMonotonically(
                session,
                ConversationSessionOptimisticMergeCoordinator.WriteOptions.STANDARD_MONOTONIC
        );
        if (atomicStateGuardEnabled) {
            InboundSessionPersistenceService.supersedeEpochForSender(
                    session.getPageId(),
                    defaultFanpageId,
                    session.getCustomerPsid()
            );
        }

        List<ConversationMessageEntity> msgs = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        return toSessionDetailMap(session, msgs);
    }

    @Transactional
    public Map<String, Object> updateControlMode(UUID sessionId, String controlMode) {
        ConversationSessionEntity session = sessionRepository.findById(sessionId)
                .map(ConversationSessionEntity::copySnapshot)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy phiên hội thoại."));
        String normalized = controlMode != null ? controlMode.trim().toUpperCase(Locale.ROOT) : "BOT_ACTIVE";
        if (!List.of("BOT_ACTIVE", "NEEDS_HANDOFF", "HUMAN_TAKEOVER", "CONVERTED").contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "control_mode không hợp lệ: " + controlMode);
        }
        session.setControlMode(normalized);
        session.setUpdatedAt(Instant.now());
        session = saveSessionMonotonically(
                session,
                ConversationSessionOptimisticMergeCoordinator.WriteOptions.EXPLICIT_OPERATOR_CONTROL_MODE
        );
        if (atomicStateGuardEnabled
                && ("HUMAN_TAKEOVER".equalsIgnoreCase(normalized) || "CONVERTED".equalsIgnoreCase(normalized))) {
            InboundSessionPersistenceService.supersedeEpochForSender(
                    session.getPageId(),
                    defaultFanpageId,
                    session.getCustomerPsid()
            );
        }
        List<ConversationMessageEntity> msgs = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        return toSessionDetailMap(session, msgs);
    }

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
                .honorific(resolvePageDefaultCustomerPronoun(pageId))
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
        session = saveSessionMonotonically(
                session,
                ConversationSessionOptimisticMergeCoordinator.WriteOptions.STANDARD_MONOTONIC
        );

        if (initialMessage != null && !initialMessage.isBlank()) {
            return sendCustomerTurnInSession(session.getId(), initialMessage.strip());
        }
        return toSessionDetailMap(session, List.of());
    }

    @Transactional
    public Map<String, Object> extractRuleFromSession(UUID sessionId) {
        ConversationSessionEntity session = sessionRepository.findById(sessionId)
                .map(ConversationSessionEntity::copySnapshot)
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
        saveSessionMonotonically(
                session,
                ConversationSessionOptimisticMergeCoordinator.WriteOptions.STANDARD_MONOTONIC
        );

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
                .honorific(resolvePageDefaultCustomerPronoun(pageId))
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

    private String resolvePageDefaultCustomerPronoun(UUID pageId) {
        if (pageId != null && knowledgePageRepository != null) {
            try {
                return knowledgePageRepository.findById(pageId)
                        .map(p -> p.getPersonaConfig() != null
                                ? p.getPersonaConfig().resolvedDefaultCustomerPronoun()
                                : "mình")
                        .orElse("mình");
            } catch (Exception ignored) {
            }
        }
        return "mình";
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
            if (insight.honorific() != null && !insight.honorific().isBlank()) {
                session.setHonorific(insight.honorific().strip());
            }
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

    private List<Map<String, Object>> serializeReplyBubbleList(FanpageBotResponse resp) {
        if (resp == null || resp.replyBubbles() == null) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (FanpageBotResponse.ReplyBubble b : resp.replyBubbles()) {
            if (b == null || b.text() == null || b.text().isBlank()) {
                continue;
            }
            Map<String, Object> bm = new LinkedHashMap<>();
            bm.put("text", b.text());
            bm.put("typing_delay_ms", Math.max(0, b.typingDelayMs()));
            out.add(bm);
        }
        return out;
    }

    private List<Map<String, Object>> serializeSkillSummaryList(List<com.example.docmind.dto.bot.AgentSkillDto> skills) {
        if (skills == null || skills.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (com.example.docmind.dto.bot.AgentSkillDto s : skills) {
            if (s == null) continue;
            Map<String, Object> sm = new LinkedHashMap<>();
            if (s.id() != null) sm.put("id", s.id().toString());
            sm.put("code", s.code() != null ? s.code() : "");
            sm.put("name", s.name() != null ? s.name() : (s.code() != null ? s.code() : ""));
            sm.put("category", s.category() != null ? s.category() : "");
            out.add(sm);
        }
        return out;
    }

    public void setAgentBrainService(AgentBrainService agentBrainService) {
        this.agentBrainService = agentBrainService;
    }

    private record SessionBookingMetadata(
            String bookingStatus,
            String bookingId,
            String bookingSource,
            Map<String, Object> structuredBookingMap
    ) {
    }

    private SessionBookingMetadata resolveSessionBookingMetadata(ConversationSessionEntity s) {
        if (s == null) {
            return new SessionBookingMetadata("NONE", null, "NONE", Map.of("status", "NONE"));
        }
        UUID pageId = s.getPageId();
        String psid = s.getCustomerPsid();
        ConversationState.StructuredBookingState bookingState = null;
        String bookingId = null;
        String bookingSource = null;

        if (agentBrainService != null && psid != null && !psid.isBlank()) {
            bookingState = agentBrainService.recallBookingState(pageId, psid);
            bookingId = agentBrainService.recallBookingId(pageId, psid);
            bookingSource = agentBrainService.recallBookingSource(pageId, psid);
        }
        if ((bookingState == null || bookingState.status() == ConversationState.BookingStatus.NONE)
                && s.getAciMemoryStateJson() != null && !s.getAciMemoryStateJson().isBlank()) {
            AciCustomerBrainMemorySnapshot snap = AciCustomerBrainMemorySnapshot.fromJson(s.getAciMemoryStateJson());
            if (snap != null) {
                if (snap.structuredBookingState() != null) {
                    bookingState = snap.structuredBookingState().toDomain();
                }
                if (snap.authoritativeBusinessFacts() != null) {
                    for (int i = snap.authoritativeBusinessFacts().size() - 1; i >= 0; i--) {
                        AciCustomerBrainMemorySnapshot.PersistedBusinessFact f = snap.authoritativeBusinessFacts().get(i);
                        if (f == null || f.factKey() == null) continue;
                        if (bookingId == null && "BOOKING_ID".equalsIgnoreCase(f.factKey())) {
                            String v = f.factValue() != null ? f.factValue().strip() : "";
                            if (!v.isEmpty() && !"NONE".equalsIgnoreCase(v)) {
                                bookingId = v;
                            }
                        } else if (bookingSource == null && "BOOKING_SOURCE".equalsIgnoreCase(f.factKey())) {
                            String v = f.factValue() != null ? f.factValue().strip() : "";
                            if (!v.isEmpty() && !"NONE".equalsIgnoreCase(v)) {
                                bookingSource = v;
                            }
                        }
                    }
                }
            }
        }
        ConversationState.StructuredBookingState safeState = bookingState != null
                ? bookingState
                : ConversationState.StructuredBookingState.none();
        String statusStr = safeState.status() != null ? safeState.status().name() : "NONE";
        String effBookingId = "CONFIRMED".equalsIgnoreCase(statusStr) ? bookingId : null;
        String effSource = (bookingSource != null && !bookingSource.isBlank())
                ? bookingSource
                : (effBookingId != null && effBookingId.startsWith("MOCK-") ? "MOCK" : "NONE");

        Map<String, Object> stateMap = new LinkedHashMap<>();
        stateMap.put("status", statusStr);
        stateMap.put("selected_product_id", safeState.selectedProductId());
        stateMap.put("selected_product_label", safeState.selectedProductLabel());
        stateMap.put("booking_date", safeState.bookingDate());
        stateMap.put("resolved_iso_date", safeState.resolvedIsoDate());
        stateMap.put("booking_time", safeState.bookingTime());
        stateMap.put("booking_id", effBookingId);
        stateMap.put("booking_source", effSource);
        return new SessionBookingMetadata(statusStr, effBookingId, effSource, stateMap);
    }

    private String serializeBotMetadata(FanpageBotResponse resp) {
        return serializeBotMetadata(null, resp);
    }

    private String serializeBotMetadata(ConversationSessionEntity session, FanpageBotResponse resp) {
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("sales_stage", resp.salesStage());
            meta.put("confidence_score", resp.confidenceScore());
            SessionBookingMetadata bookingMeta = resolveSessionBookingMetadata(session);
            meta.put("booking_status", bookingMeta.bookingStatus());
            meta.put("booking_id", bookingMeta.bookingId());
            meta.put("booking_source", bookingMeta.bookingSource());
            meta.put("triggered_hooks", resp.triggeredHooks() != null ? resp.triggeredHooks() : List.of());
            List<com.example.docmind.dto.bot.AgentSkillDto> effActivated =
                    resp.activatedSkills() != null ? resp.activatedSkills()
                            : (resp.activeSkills() != null ? resp.activeSkills() : List.of());
            meta.put("available_skills", resp.availableSkills() != null
                    ? resp.availableSkills().stream()
                            .filter(s -> s != null)
                            .map(s -> s.code() != null ? s.code() : s.name())
                            .filter(s -> s != null && !s.isBlank())
                            .toList()
                    : List.of());
            meta.put("selected_skills", resp.selectedSkills() != null
                    ? resp.selectedSkills().stream()
                            .filter(s -> s != null)
                            .map(s -> s.code() != null ? s.code() : s.name())
                            .filter(s -> s != null && !s.isBlank())
                            .toList()
                    : List.of());
            meta.put("activated_skills", serializeSkillSummaryList(effActivated));
            meta.put("active_skills", effActivated.stream()
                    .filter(s -> s != null)
                    .map(s -> s.code() != null ? s.code() : s.name())
                    .filter(s -> s != null && !s.isBlank())
                    .toList());
            if (resp.turnProfiling() != null) {
                Map<String, Object> prof = new LinkedHashMap<>();
                prof.put("input_tokens", resp.turnProfiling().inputTokens());
                prof.put("output_tokens", resp.turnProfiling().outputTokens());
                prof.put("reflection_tokens", resp.turnProfiling().reflectionTokens());
                prof.put("model_latency_ms", resp.turnProfiling().modelLatencyMs());
                prof.put("retrieval_latency_ms", resp.turnProfiling().retrievalLatencyMs());
                prof.put("retry_count", resp.turnProfiling().retryCount());
                prof.put("total_turn_ms", resp.turnProfiling().totalTurnMs());
                meta.put("turn_profiling", prof);
            }
            meta.put("applied_rules", resp.appliedRules() != null
                    ? resp.appliedRules().stream()
                            .filter(r -> r != null && r.id() != null)
                            .map(r -> r.id().toString())
                            .toList()
                    : List.of());
            meta.put("needs_human_handoff", resp.needsHumanHandoff());
            meta.put("handoff_reason", resp.handoffReason());
            List<Map<String, Object>> serializedBubbles = serializeReplyBubbleList(resp);
            meta.put("reply_bubbles", serializedBubbles);
            meta.put("bubble_delays_ms", resp.bubbleDelaysMs() != null
                    ? resp.bubbleDelaysMs()
                    : serializedBubbles.stream().map(b -> (Integer) b.get("typing_delay_ms")).toList());
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
        SessionBookingMetadata bookingMeta = resolveSessionBookingMetadata(s);
        m.put("booking_status", bookingMeta.bookingStatus());
        m.put("booking_id", bookingMeta.bookingId());
        m.put("booking_source", bookingMeta.bookingSource());
        m.put("structured_booking_state", bookingMeta.structuredBookingMap());
        m.put("has_human_sale_reply", s.isHasHumanSaleReply());
        m.put("learned_in_cold_path", s.isLearnedInColdPath());
        m.put("opted_out", s.isOptedOut());
        m.put("opted_out_at", s.getOptedOutAt());
        m.put("re_opt_in_at", s.getReOptInAt());
        m.put("re_opt_in_source", s.getReOptInSource());
        m.put("re_opt_in_evidence", s.getReOptInEvidence());
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
                    if (meta.get("activated_skills") != null) {
                        item.put("activated_skills", meta.get("activated_skills"));
                    }
                    if (meta.get("available_skills") != null) {
                        item.put("available_skills", meta.get("available_skills"));
                    }
                    if (meta.get("selected_skills") != null) {
                        item.put("selected_skills", meta.get("selected_skills"));
                    }
                    if (meta.get("active_skills") != null) {
                        item.put("active_skills", meta.get("active_skills"));
                    }
                    if (meta.get("turn_profiling") != null) {
                        item.put("turn_profiling", meta.get("turn_profiling"));
                    }
                    if (meta.get("applied_rules") != null) {
                        item.put("applied_rules", meta.get("applied_rules"));
                    }
                    if (meta.get("reply_bubbles") != null) {
                        item.put("reply_bubbles", meta.get("reply_bubbles"));
                    }
                    if (meta.get("bubble_delays_ms") != null) {
                        item.put("bubble_delays_ms", meta.get("bubble_delays_ms"));
                    }
                    if (meta.get("booking_status") != null) {
                        item.put("booking_status", meta.get("booking_status"));
                    }
                    if (meta.containsKey("booking_id")) {
                        item.put("booking_id", meta.get("booking_id"));
                    }
                    if (meta.get("booking_source") != null) {
                        item.put("booking_source", meta.get("booking_source"));
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
