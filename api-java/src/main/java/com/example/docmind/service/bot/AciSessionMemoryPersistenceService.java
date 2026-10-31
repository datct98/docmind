package com.example.docmind.service.bot;

import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.entity.ConversationSessionEntity;
import com.example.docmind.repository.ConversationMessageRepository;
import com.example.docmind.repository.ConversationSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Unit P2.4 (V35): PostgreSQL {@code JSONB} persistence, {@code Lazy Hydration}, and
 * {@code @Version} {@code Monotonic Merge} gateway for {@link AgentBrainService.CustomerBrainMemory}.
 *
 * <p>Controlled by {@code docmind.aci.db-persistence.enabled} (default {@code true}).
 * All writes to {@code conversation_sessions} go exclusively through
 * {@link ConversationSessionOptimisticMergeCoordinator} and JPA {@code @Version}.
 */
@Service
public class AciSessionMemoryPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(AciSessionMemoryPersistenceService.class);

    public record HydratedSessionRecord(
            UUID sessionId,
            Long dbVersion,
            String controlMode,
            boolean optedOut,
            AciCustomerBrainMemorySnapshot snapshot
    ) {
    }

    public record PersistedSessionOutcome(
            UUID sessionId,
            Long dbVersion,
            String controlMode,
            boolean optedOut,
            AciCustomerBrainMemorySnapshot mergedSnapshot,
            int optimisticRetries
    ) {
    }

    private final ConversationSessionRepository sessionRepository;
    private final ConversationMessageRepository messageRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ConversationSessionOptimisticMergeCoordinator mergeCoordinator;

    @Value("${docmind.aci.db-persistence.enabled:true}")
    private volatile boolean dbPersistenceEnabled = true;

    @Autowired
    public AciSessionMemoryPersistenceService(
            ConversationSessionRepository sessionRepository,
            @Autowired(required = false) ConversationMessageRepository messageRepository,
            JdbcTemplate jdbcTemplate,
            ConversationSessionOptimisticMergeCoordinator mergeCoordinator
    ) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.mergeCoordinator = mergeCoordinator;
    }

    public static AciSessionMemoryPersistenceService forStandaloneDataSource(DataSource dataSource) {
        ConversationSessionOptimisticMergeCoordinator coord =
                ConversationSessionOptimisticMergeCoordinator.forStandaloneDataSource(dataSource);
        return new AciSessionMemoryPersistenceService(
                coord.repository(),
                null,
                new JdbcTemplate(dataSource),
                coord
        );
    }

    public boolean isDbPersistenceEnabled() {
        return dbPersistenceEnabled;
    }

    public void setDbPersistenceEnabled(boolean dbPersistenceEnabled) {
        this.dbPersistenceEnabled = dbPersistenceEnabled;
    }

    public ConversationSessionOptimisticMergeCoordinator mergeCoordinator() {
        return mergeCoordinator;
    }

    /**
     * Lazy Hydration entry point: reads the latest {@link ConversationSessionEntity} for {@code (pageId, senderId)}
     * and deserializes {@code aci_memory_state} JSONB into {@link AciCustomerBrainMemorySnapshot}.
     */
    public HydratedSessionRecord loadLatestSessionMemory(UUID pageId, String rawSenderId) {
        if (!dbPersistenceEnabled || rawSenderId == null || rawSenderId.isBlank()) {
            return null;
        }
        String psid = rawSenderId.strip();
        try {
            return mergeCoordinator.executeInRequiresNew(() -> {
                Optional<ConversationSessionEntity> opt = findLatestSessionInTx(pageId, psid);
                if (opt.isEmpty()) {
                    return null;
                }
                ConversationSessionEntity s = opt.get();
                AciCustomerBrainMemorySnapshot parsed =
                        AciCustomerBrainMemorySnapshot.fromJson(s.getAciMemoryStateJson());
                return new HydratedSessionRecord(
                        s.getId(),
                        s.getVersion(),
                        s.getControlMode(),
                        s.isOptedOut(),
                        parsed
                );
            });
        } catch (Exception ex) {
            log.debug("[ACI-P24-HYDRATE] Non-fatal lazy hydration skip for pageId={} senderId={}: {}",
                    pageId, psid, ex.getMessage());
            return null;
        }
    }

    /**
     * Persists {@code candidateSnapshot} into {@code conversation_sessions.aci_memory_state} via JPA {@code @Version}.
     * When {@code expectedBaseVersion} differs from the current committed DB version, triggers JPA's
     * {@code ObjectOptimisticLockingFailureException} and resolves conflicts via {@code Monotonic Merge}.
     */
    public PersistedSessionOutcome persistMemorySnapshot(
            UUID pageId,
            String rawSenderId,
            String customerName,
            UUID knownSessionId,
            Long expectedBaseVersion,
            AciCustomerBrainMemorySnapshot candidateSnapshot
    ) {
        if (!dbPersistenceEnabled || rawSenderId == null || rawSenderId.isBlank() || candidateSnapshot == null) {
            return null;
        }
        String psid = rawSenderId.strip();
        try {
            ConversationSessionEntity baseEntity = mergeCoordinator.executeInRequiresNew(() -> {
                if (knownSessionId != null) {
                    Optional<ConversationSessionEntity> byId = sessionRepository.findById(knownSessionId);
                    if (byId.isPresent()) {
                        return byId.get().copySnapshot();
                    }
                }
                Optional<ConversationSessionEntity> latest = findLatestSessionInTx(pageId, psid);
                if (latest.isPresent()) {
                    return latest.get().copySnapshot();
                }
                return findOrCreateSessionWithAdvisoryLockInTx(pageId, psid, customerName).copySnapshot();
            });

            ConversationSessionEntity candidateEntity = baseEntity.copySnapshot();
            if (expectedBaseVersion != null && !Objects.equals(baseEntity.getVersion(), expectedBaseVersion)) {
                // Preserve the turn-start @Version token so JPA saveAndFlush genuinely detects the concurrent
                // modification via ObjectOptimisticLockingFailureException and executes Monotonic Merge!
                candidateEntity.setVersion(expectedBaseVersion);
            }
            if (candidateSnapshot.inferredCustomerPronoun() != null
                    && !candidateSnapshot.inferredCustomerPronoun().isBlank()
                    && candidateSnapshot.inferredPronounConfidence() >= 0.70) {
                candidateEntity.setHonorific(candidateSnapshot.inferredCustomerPronoun().strip());
            }
            Instant existingResetFloor = AciCustomerBrainMemorySnapshot.extractMemoryResetFloor(baseEntity.getAciMemoryStateJson());
            AciCustomerBrainMemorySnapshot mergedAgainstBase =
                    (expectedBaseVersion == null || Objects.equals(baseEntity.getVersion(), expectedBaseVersion))
                            ? AciCustomerBrainMemorySnapshot.monotonicMerge(
                                    AciCustomerBrainMemorySnapshot.fromJson(baseEntity.getAciMemoryStateJson()),
                                    candidateSnapshot)
                            : candidateSnapshot;
            candidateEntity.setAciMemoryStateJson(
                    (mergedAgainstBase != null ? mergedAgainstBase : candidateSnapshot)
                            .withMinMemoryResetAt(existingResetFloor)
                            .withAlignedRowOptOut(candidateEntity.isOptedOut())
                            .toJson()
            );
            candidateEntity.setUpdatedAt(Instant.now());

            ConversationSessionOptimisticMergeCoordinator.MergeWriteResult writeRes =
                    mergeCoordinator.saveCandidateWithMonotonicMerge(
                            candidateEntity,
                            ConversationSessionOptimisticMergeCoordinator.WriteOptions.MEMORY_ONLY_MONOTONIC
                    );
            ConversationSessionEntity saved = writeRes.entity();
            AciCustomerBrainMemorySnapshot finalSnap =
                    AciCustomerBrainMemorySnapshot.fromJson(saved.getAciMemoryStateJson());
            return new PersistedSessionOutcome(
                    saved.getId(),
                    saved.getVersion(),
                    saved.getControlMode(),
                    saved.isOptedOut(),
                    finalSnap != null ? finalSnap : candidateSnapshot,
                    writeRes.optimisticRetries()
            );
        } catch (Exception ex) {
            log.warn("[ACI-P24-PERSIST] Unable to persist aci_memory_state for pageId={} senderId={}: {}",
                    pageId, psid, ex.getMessage());
            return null;
        }
    }

    /**
     * Resets {@code aci_memory_state} via JPA {@code @Version} when {@code resetSession == true},
     * recording {@code memory_reset_at} so prior pre-reset {@code conversation_messages} are not
     * re-hydrated on subsequent stateless turns.
     */
    public void resetPersistedMemory(UUID pageId, String rawSenderId) {
        resetPersistedMemory(pageId, rawSenderId, FanpageBotService.getCurrentInboundExternalMid());
    }

    public void resetPersistedMemory(UUID pageId, String rawSenderId, String currentInboundExternalMid) {
        if (!dbPersistenceEnabled || rawSenderId == null || rawSenderId.isBlank()) {
            return;
        }
        String psid = rawSenderId.strip();
        try {
            UUID sessionId = mergeCoordinator.executeInRequiresNew(() ->
                    findLatestSessionInTx(pageId, psid).map(ConversationSessionEntity::getId).orElse(null)
            );
            if (sessionId != null) {
                Instant resetFloor = resolveResetFloorInstant(sessionId, currentInboundExternalMid);
                String resetJson = "{\"memory_reset_at\":\"" + resetFloor.toString() + "\"}";
                mergeCoordinator.mutateByIdWithMonotonicMerge(
                        sessionId,
                        s -> s.setAciMemoryStateJson(resetJson),
                        ConversationSessionOptimisticMergeCoordinator.WriteOptions.EXPLICIT_MEMORY_RESET
                );
            }
        } catch (Exception ex) {
            log.debug("[ACI-P24-RESET] Non-fatal memory reset skip for pageId={} senderId={}: {}",
                    pageId, psid, ex.getMessage());
        }
    }

    private Instant resolveResetFloorInstant(UUID sessionId, String currentInboundExternalMid) {
        if (jdbcTemplate != null && sessionId != null
                && currentInboundExternalMid != null && !currentInboundExternalMid.isBlank()) {
            try {
                List<Instant> found = jdbcTemplate.query(
                        "SELECT created_at FROM conversation_messages WHERE session_id = ? AND external_mid = ? LIMIT 1",
                        (rs, idx) -> {
                            java.sql.Timestamp ts = rs.getTimestamp("created_at");
                            return ts != null ? ts.toInstant() : null;
                        },
                        sessionId,
                        currentInboundExternalMid.strip()
                );
                if (!found.isEmpty() && found.get(0) != null) {
                    return found.get(0);
                }
            } catch (Exception ignored) {
            }
        }
        return Instant.now();
    }

    /**
     * Lazily hydrates prior {@code user}/{@code assistant} turns from {@code conversation_messages}
     * when {@code request.history()} is empty after a JVM / container restart.
     */
    public List<FanpageBotRequest.ConversationTurn> loadPriorConversationTurns(
            UUID pageId,
            String rawSenderId,
            String excludeCurrentExternalMid
    ) {
        if (!dbPersistenceEnabled || rawSenderId == null || rawSenderId.isBlank() || jdbcTemplate == null) {
            return List.of();
        }
        String psid = rawSenderId.strip();
        try {
            return mergeCoordinator.executeInRequiresNew(() -> {
                Optional<ConversationSessionEntity> opt = findLatestSessionInTx(pageId, psid);
                if (opt.isEmpty()) {
                    return List.of();
                }
                ConversationSessionEntity session = opt.get();
                UUID sessionId = session.getId();
                Instant resetFloor = AciCustomerBrainMemorySnapshot.extractMemoryResetFloor(session.getAciMemoryStateJson());
                List<RowTurn> rows = resetFloor != null
                        ? jdbcTemplate.query(
                                "SELECT sender_role, content, external_mid FROM conversation_messages "
                                        + "WHERE session_id = ? AND created_at >= ? ORDER BY created_at ASC",
                                (rs, idx) -> new RowTurn(
                                        rs.getString("sender_role"),
                                        rs.getString("content"),
                                        rs.getString("external_mid")
                                ),
                                sessionId,
                                java.sql.Timestamp.from(resetFloor)
                        )
                        : jdbcTemplate.query(
                                "SELECT sender_role, content, external_mid FROM conversation_messages "
                                        + "WHERE session_id = ? ORDER BY created_at ASC",
                                (rs, idx) -> new RowTurn(
                                        rs.getString("sender_role"),
                                        rs.getString("content"),
                                        rs.getString("external_mid")
                                ),
                                sessionId
                        );
                if (rows.isEmpty()) {
                    return List.of();
                }
                List<FanpageBotRequest.ConversationTurn> turns = new ArrayList<>();
                for (RowTurn row : rows) {
                    if (row.content() == null || row.content().isBlank()) {
                        continue;
                    }
                    if (excludeCurrentExternalMid != null && !excludeCurrentExternalMid.isBlank()
                            && excludeCurrentExternalMid.equals(row.externalMid())) {
                        continue;
                    }
                    String role = "CUSTOMER".equalsIgnoreCase(row.senderRole()) ? "user" : "assistant";
                    turns.add(new FanpageBotRequest.ConversationTurn(role, row.content()));
                }
                return List.copyOf(turns);
            });
        } catch (Exception ex) {
            log.debug("[ACI-P24-HISTORY] Non-fatal history hydration skip for pageId={} senderId={}: {}",
                    pageId, psid, ex.getMessage());
            return List.of();
        }
    }

    private Optional<ConversationSessionEntity> findLatestSessionInTx(UUID pageId, String psid) {
        UUID effectivePageId = resolveExistingPageIdOrNull(pageId);
        if (effectivePageId != null) {
            Optional<ConversationSessionEntity> scoped =
                    sessionRepository.findFirstByPageIdAndCustomerPsidOrderByUpdatedAtDesc(effectivePageId, psid);
            if (scoped.isPresent()) {
                return scoped;
            }
        }
        return sessionRepository.findFirstByCustomerPsidOrderByUpdatedAtDesc(psid);
    }

    private ConversationSessionEntity findOrCreateSessionWithAdvisoryLockInTx(
            UUID requestedPageId,
            String psid,
            String customerName
    ) {
        UUID effectivePageId = resolveExistingPageIdOrNull(requestedPageId);
        if (jdbcTemplate != null) {
            String lockKey = "inbound_sess:" + (effectivePageId != null ? effectivePageId : "null") + ":" + psid;
            jdbcTemplate.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", Object.class, lockKey);
        }
        Optional<ConversationSessionEntity> existing = effectivePageId != null
                ? sessionRepository.findFirstByPageIdAndCustomerPsidOrderByUpdatedAtDesc(effectivePageId, psid)
                : sessionRepository.findFirstByCustomerPsidOrderByUpdatedAtDesc(psid);
        if (existing.isPresent()) {
            return existing.get();
        }
        Instant now = Instant.now();
        String displayName = (customerName != null && !customerName.isBlank())
                ? customerName.strip()
                : "Khách Messenger (" + psid + ")";
        ConversationSessionEntity created = ConversationSessionEntity.builder()
                .id(UUID.randomUUID())
                .pageId(effectivePageId)
                .customerPsid(psid)
                .customerName(displayName)
                .honorific("mình")
                .activeTopic("Tư vấn dịch vụ")
                .topicFamily("GENERAL")
                .controlMode("BOT_ACTIVE")
                .buyingTemperature(45)
                .temperatureDelta(0)
                .temperatureLabel("WARM")
                .momentum("STABLE")
                .buyerArchetype("EXPLORING_CONSULTATION")
                .archetypeLabel("Đang tìm hiểu giải pháp")
                .salesStage("DISCOVERY")
                .hasHumanSaleReply(false)
                .learnedInColdPath(false)
                .optedOut(false)
                .lastMessagePreview("")
                .lastSenderRole("CUSTOMER")
                .lastMessageAt(now)
                .createdAt(now)
                .updatedAt(now)
                .aciMemoryStateJson("{}")
                .build();
        return sessionRepository.saveAndFlush(created);
    }

    private UUID resolveExistingPageIdOrNull(UUID requestedPageId) {
        if (requestedPageId == null) {
            return null;
        }
        if (jdbcTemplate == null) {
            return requestedPageId;
        }
        List<Integer> found = jdbcTemplate.query(
                "SELECT 1 FROM knowledge_pages WHERE id = ? LIMIT 1",
                (rs, rowNum) -> rs.getInt(1),
                requestedPageId
        );
        return found.isEmpty() ? null : requestedPageId;
    }

    private record RowTurn(String senderRole, String content, String externalMid) {
    }
}
