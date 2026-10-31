package com.example.docmind.service.bot;

import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.service.bot.InboundEventIdResolver.InboundChannel;
import com.example.docmind.service.bot.InboundEventIdResolver.ResolvedInboundEvent;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Isolated {@code REQUIRES_NEW} database persistence &amp; session state gate for inbound turns (Unit P0.1).
 * <ul>
 *   <li>Serializes first-time session creation per {@code (pageId, psid)} via {@code pg_advisory_xact_lock}
 *       so concurrent retries for brand-new customers always converge on a single {@code session_id}.</li>
 *   <li>Persists {@code CUSTOMER} rows atomically with
 *       {@code INSERT INTO conversation_messages ... ON CONFLICT (session_id, external_mid) WHERE external_mid IS NOT NULL DO NOTHING}.</li>
 *   <li>Enforces {@code HUMAN_TAKEOVER} entry block ({@code 0} LLM calls, {@code 0} outbound bubbles).</li>
 *   <li>Enforces channel-aware {@code Fail-Closed} behavior on PostgreSQL outage:
 *       {@code HTTP 503 Service Unavailable} on synchronous REST/Simulator channels vs
 *       {@link DurableInboundWalRecoveryStore} {@code fsync} + {@code STATE_GUARD_FAIL_CLOSED} on Webhook.</li>
 * </ul>
 */
@Service
public class InboundSessionPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(InboundSessionPersistenceService.class);

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate requiresNewTx;
    private final DurableInboundWalRecoveryStore walRecoveryStore;

    public enum EntryAction {
        PROCEED_TO_BOT,
        BLOCK_HUMAN_TAKEOVER,
        SUPPRESS_DUPLICATE_INBOUND,
        BLOCK_FAIL_CLOSED_WEBHOOK
    }

    public record EntryEvaluation(
            EntryAction action,
            UUID sessionId,
            String controlMode,
            ResolvedInboundEvent resolvedEvent,
            boolean insertedCustomerRow
    ) {
    }

    @Autowired
    public InboundSessionPersistenceService(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            DurableInboundWalRecoveryStore walRecoveryStore
    ) {
        this.jdbcTemplate = jdbcTemplate;
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNewTx = tx;
        this.walRecoveryStore = walRecoveryStore;
    }

    @PostConstruct
    void bindWalReplayer() {
        if (this.walRecoveryStore != null) {
            this.walRecoveryStore.setDbReplayer(this::replayWalRecordToDatabase);
        }
    }

    /**
     * Entry gate executed at the start of {@code FanpageBotService.handleCustomerMessageInternal}.
     */
    public EntryEvaluation evaluateEntryAndPersistCustomer(
            FanpageBotRequest request,
            String verbatimCustomerText
    ) {
        ResolvedInboundEvent resolved = InboundEventIdResolver.resolveForCurrentContext(request, verbatimCustomerText);
        boolean prePersistedInCaller = InboundEventIdResolver.isCustomerAlreadyPersistedInCurrentCall();
        UUID requestedPageId = parseUuidOrNull(request != null ? request.pageId() : null);
        String psid = normalizePsid(request != null ? request.senderId() : null);
        String customerName = request != null ? request.customerName() : null;
        String safeText = verbatimCustomerText != null ? verbatimCustomerText : "";

        try {
            EntryEvaluation eval = requiresNewTx.execute(status -> {
                SessionSnapshot session = findOrCreateSessionInTx(requestedPageId, psid, customerName);
                boolean inserted = false;
                if (!prePersistedInCaller && !safeText.isBlank()) {
                    Instant now = Instant.now();
                    inserted = insertCustomerMessageOnConflictDoNothingInTx(
                            session.id(),
                            safeText,
                            resolved.externalMid(),
                            now
                    );
                    if (!inserted && resolved.stableCrossRequestId()) {
                        return new EntryEvaluation(
                                EntryAction.SUPPRESS_DUPLICATE_INBOUND,
                                session.id(),
                                session.controlMode(),
                                resolved,
                                false
                        );
                    }
                    if (inserted && "HUMAN_TAKEOVER".equalsIgnoreCase(session.controlMode())) {
                        updateSessionCustomerPreviewInTx(session.id(), safeText, now);
                    }
                }

                if ("HUMAN_TAKEOVER".equalsIgnoreCase(session.controlMode())) {
                    return new EntryEvaluation(
                            EntryAction.BLOCK_HUMAN_TAKEOVER,
                            session.id(),
                            session.controlMode(),
                            resolved,
                            inserted
                    );
                }

                return new EntryEvaluation(
                        EntryAction.PROCEED_TO_BOT,
                        session.id(),
                        session.controlMode(),
                        resolved,
                        inserted
                );
            });

            // Opportunistically drain any pending WAL records once PostgreSQL is healthy
            drainPendingWalQuietly();
            return eval;
        } catch (Exception dbEx) {
            return handleDbFailureFailClosed(request, safeText, resolved, psid, customerName, dbEx);
        }
    }

    /**
     * Checks whether proactive {@code REENGAGE} is permitted for {@code (pageId, senderId)}.
     * Blocks proactive outreach when session is in {@code HUMAN_TAKEOVER} or DB is unreachable (Fail-Closed).
     */
    public boolean isProactiveReengageAllowed(UUID requestedPageId, String rawSenderId) {
        String psid = normalizePsid(rawSenderId);
        try {
            Boolean allowed = requiresNewTx.execute(status -> {
                UUID effectiveDbPageId = resolveExistingPageIdOrNullInTx(requestedPageId);
                List<String> modes = (effectiveDbPageId != null)
                        ? jdbcTemplate.query(
                                "SELECT control_mode FROM conversation_sessions "
                                        + "WHERE page_id = ? AND customer_psid = ? "
                                        + "ORDER BY updated_at DESC LIMIT 1",
                                (rs, rowNum) -> rs.getString("control_mode"),
                                effectiveDbPageId,
                                psid
                        )
                        : jdbcTemplate.query(
                                "SELECT control_mode FROM conversation_sessions "
                                        + "WHERE customer_psid = ? "
                                        + "ORDER BY updated_at DESC LIMIT 1",
                                (rs, rowNum) -> rs.getString("control_mode"),
                                psid
                        );
                if (modes.isEmpty()) {
                    return true;
                }
                return !"HUMAN_TAKEOVER".equalsIgnoreCase(modes.get(0));
            });
            return Boolean.TRUE.equals(allowed);
        } catch (Exception dbEx) {
            log.error("[REENGAGE_STATE_GUARD_FAIL_CLOSED] pageId={} senderId={} reason={}",
                    requestedPageId, psid, dbEx.getMessage());
            return false;
        }
    }

    /**
     * Used by {@code ConversationInboxService.sendCustomerTurnInSession} and {@code recordCustomerAndBotTurn}
     * to atomically persist the {@code CUSTOMER} row without duplicates.
     */
    public boolean persistCustomerMessageForExistingSession(
            UUID sessionId,
            String content,
            String externalMid,
            Instant createdAt
    ) {
        if (sessionId == null || content == null || content.isBlank()) {
            return false;
        }
        try {
            Boolean inserted = requiresNewTx.execute(status ->
                    insertCustomerMessageOnConflictDoNothingInTx(
                            sessionId,
                            content,
                            externalMid,
                            createdAt != null ? createdAt : Instant.now()
                    )
            );
            return Boolean.TRUE.equals(inserted);
        } catch (Exception dbEx) {
            throw new InboundPersistenceUnavailableException(
                    "Unable to persist inbound customer message to PostgreSQL: " + dbEx.getMessage(),
                    dbEx
            );
        }
    }

    /**
     * Used by {@code DurableInboundWalRecoveryStore} during startup or post-outage replay.
     * Guarantees FK safety by calling {@link #findOrCreateSessionInTx} before inserting into
     * {@code conversation_messages} (Spec Item I.1 Condition 2 / Crash-Point e).
     */
    public void replayWalRecordToDatabase(DurableInboundWalRecoveryStore.WalRecord record) {
        UUID requestedPageId = parseUuidOrNull(record.pageId());
        String psid = normalizePsid(record.senderId());
        Instant receivedAt = record.receivedAtEpochMs() > 0
                ? Instant.ofEpochMilli(record.receivedAtEpochMs())
                : Instant.now();

        requiresNewTx.executeWithoutResult(status -> {
            SessionSnapshot session = findOrCreateSessionInTx(requestedPageId, psid, record.customerName());
            boolean inserted = insertCustomerMessageOnConflictDoNothingInTx(
                    session.id(),
                    record.verbatimText(),
                    record.inboundExternalMid(),
                    receivedAt
            );
            if (inserted) {
                updateSessionCustomerPreviewIfNewerInTx(session.id(), record.verbatimText(), receivedAt);
            }
        });
    }

    private EntryEvaluation handleDbFailureFailClosed(
            FanpageBotRequest request,
            String safeText,
            ResolvedInboundEvent resolved,
            String psid,
            String customerName,
            Exception dbEx
    ) {
        if (resolved.channel() == InboundChannel.FB_WEBHOOK) {
            if (!safeText.isBlank() && walRecoveryStore != null) {
                DurableInboundWalRecoveryStore.WalAppendResult walRes = walRecoveryStore.appendAndFsync(
                        request != null ? request.pageId() : null,
                        psid,
                        customerName,
                        request != null ? request.conversationId() : null,
                        resolved.externalMid(),
                        safeText,
                        System.currentTimeMillis()
                );
                log.error("[STATE_GUARD_FAIL_CLOSED_WEBHOOK] pageId={} senderId={} mid={} walDurable={} dbReason={}",
                        request != null ? request.pageId() : null,
                        psid,
                        resolved.externalMid(),
                        walRes.durableOnDisk(),
                        dbEx.getMessage());
            } else {
                log.error("[STATE_GUARD_FAIL_CLOSED_WEBHOOK] pageId={} senderId={} mid={} dbReason={}",
                        request != null ? request.pageId() : null,
                        psid,
                        resolved.externalMid(),
                        dbEx.getMessage());
            }
            return new EntryEvaluation(
                    EntryAction.BLOCK_FAIL_CLOSED_WEBHOOK,
                    null,
                    "FAIL_CLOSED",
                    resolved,
                    false
            );
        }

        log.error("[STATE_GUARD_FAIL_CLOSED_HTTP_503] channel={} pageId={} senderId={} mid={} dbReason={}",
                resolved.channel().name(),
                request != null ? request.pageId() : null,
                psid,
                resolved.externalMid(),
                dbEx.getMessage());
        throw new InboundPersistenceUnavailableException(
                "Inbound session persistence unavailable (Fail-Closed): " + dbEx.getMessage(),
                dbEx
        );
    }

    private SessionSnapshot findOrCreateSessionInTx(UUID requestedPageId, String psid, String customerName) {
        String lockKey = "inbound_sess:" + (requestedPageId != null ? requestedPageId : "null") + ":" + psid;
        jdbcTemplate.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", Object.class, lockKey);

        UUID effectiveDbPageId = resolveExistingPageIdOrNullInTx(requestedPageId);

        List<SessionSnapshot> existing;
        if (effectiveDbPageId != null) {
            existing = jdbcTemplate.query(
                    "SELECT id, control_mode FROM conversation_sessions "
                            + "WHERE page_id = ? AND customer_psid = ? "
                            + "ORDER BY updated_at DESC LIMIT 1",
                    (rs, rowNum) -> new SessionSnapshot(
                            UUID.fromString(rs.getString("id")),
                            rs.getString("control_mode")
                    ),
                    effectiveDbPageId,
                    psid
            );
        } else {
            existing = jdbcTemplate.query(
                    "SELECT id, control_mode FROM conversation_sessions "
                            + "WHERE customer_psid = ? "
                            + "ORDER BY updated_at DESC LIMIT 1",
                    (rs, rowNum) -> new SessionSnapshot(
                            UUID.fromString(rs.getString("id")),
                            rs.getString("control_mode")
                    ),
                    psid
            );
        }

        if (!existing.isEmpty()) {
            SessionSnapshot found = existing.get(0);
            if (customerName != null && !customerName.isBlank()
                    && !"Khách hàng".equalsIgnoreCase(customerName.strip())) {
                jdbcTemplate.update(
                        "UPDATE conversation_sessions SET customer_name = ? WHERE id = ?",
                        customerName.strip(),
                        found.id()
                );
            }
            return found;
        }

        UUID newSessionId = UUID.randomUUID();
        String displayName = (customerName != null && !customerName.isBlank())
                ? customerName.strip()
                : "Khách Messenger (" + psid + ")";
        Timestamp nowTs = Timestamp.from(Instant.now());

        jdbcTemplate.update(
                "INSERT INTO conversation_sessions ("
                        + "id, page_id, customer_psid, customer_name, honorific, active_topic, topic_family, "
                        + "control_mode, buying_temperature, temperature_delta, temperature_label, momentum, "
                        + "buyer_archetype, archetype_label, sales_stage, has_human_sale_reply, learned_in_cold_path, "
                        + "last_message_preview, last_sender_role, last_message_at, created_at, updated_at"
                        + ") VALUES (?, ?, ?, ?, 'mình', 'Tư vấn dịch vụ', 'GENERAL', "
                        + "'BOT_ACTIVE', 45, 0, 'WARM', 'STABLE', "
                        + "'EXPLORING_CONSULTATION', 'Đang tìm hiểu giải pháp', 'DISCOVERY', FALSE, FALSE, "
                        + "'', 'CUSTOMER', ?, ?, ?)",
                newSessionId,
                effectiveDbPageId,
                psid,
                displayName,
                nowTs,
                nowTs,
                nowTs
        );
        return new SessionSnapshot(newSessionId, "BOT_ACTIVE");
    }

    private UUID resolveExistingPageIdOrNullInTx(UUID requestedPageId) {
        if (requestedPageId == null) {
            return null;
        }
        List<Integer> found = jdbcTemplate.query(
                "SELECT 1 FROM knowledge_pages WHERE id = ? LIMIT 1",
                (rs, rowNum) -> rs.getInt(1),
                requestedPageId
        );
        return found.isEmpty() ? null : requestedPageId;
    }

    private boolean insertCustomerMessageOnConflictDoNothingInTx(
            UUID sessionId,
            String content,
            String externalMid,
            Instant createdAt
    ) {
        int rows = jdbcTemplate.update(
                "INSERT INTO conversation_messages (id, session_id, sender_role, content, external_mid, created_at) "
                        + "VALUES (?, ?, 'CUSTOMER', ?, ?, ?) "
                        + "ON CONFLICT (session_id, external_mid) WHERE external_mid IS NOT NULL DO NOTHING",
                UUID.randomUUID(),
                sessionId,
                content,
                externalMid,
                Timestamp.from(createdAt != null ? createdAt : Instant.now())
        );
        return rows > 0;
    }

    private void updateSessionCustomerPreviewInTx(UUID sessionId, String previewText, Instant now) {
        Timestamp ts = Timestamp.from(now);
        jdbcTemplate.update(
                "UPDATE conversation_sessions "
                        + "SET last_message_preview = ?, last_sender_role = 'CUSTOMER', "
                        + "    last_message_at = ?, updated_at = ? "
                        + "WHERE id = ?",
                previewText,
                ts,
                ts,
                sessionId
        );
    }

    private void updateSessionCustomerPreviewIfNewerInTx(UUID sessionId, String previewText, Instant receivedAt) {
        Timestamp ts = Timestamp.from(receivedAt);
        jdbcTemplate.update(
                "UPDATE conversation_sessions "
                        + "SET last_message_preview = ?, last_sender_role = 'CUSTOMER', "
                        + "    last_message_at = GREATEST(last_message_at, ?), "
                        + "    updated_at = GREATEST(updated_at, ?) "
                        + "WHERE id = ? AND last_message_at <= ?",
                previewText,
                ts,
                ts,
                sessionId,
                ts
        );
    }

    private void drainPendingWalQuietly() {
        if (walRecoveryStore != null && walRecoveryStore.hasPendingWalRecords()) {
            try {
                walRecoveryStore.replayPendingRecords();
            } catch (Exception ignored) {
            }
        }
    }

    private static UUID parseUuidOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.strip());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String normalizePsid(String rawSenderId) {
        return (rawSenderId != null && !rawSenderId.isBlank())
                ? rawSenderId.strip()
                : "psid-sim-default";
    }

    private record SessionSnapshot(UUID id, String controlMode) {
    }
}
