package com.example.docmind.service.bot;

import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.entity.ConversationSessionEntity;
import com.example.docmind.repository.ConversationSessionRepository;
import com.example.docmind.service.bot.InboundEventIdResolver.InboundChannel;
import com.example.docmind.service.bot.InboundEventIdResolver.ResolvedInboundEvent;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

/**
 * Isolated {@code REQUIRES_NEW} database persistence, 6-Scenario {@code Opt-Out / Re-Consent} state machine,
 * and 2-Layer Outbound Fencing gate for inbound turns (Units P0.1 &amp; P0.4).
 */
@Service
public class InboundSessionPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(InboundSessionPersistenceService.class);

    /**
     * Explicit customer stop/opt-out commands (Scenario 5 &amp; Scenario 6).
     * Strictly excludes polite conversational deferral S06 ("Thôi, không cần nữa").
     */
    private static final Pattern EXPLICIT_CUSTOMER_OPT_OUT_COMMAND_PATTERN = Pattern.compile(
            "(?isU)^\\s*(?:stop|unsubscribe|hủy\\s+đăng\\s+ký|huỷ\\s+đăng\\s+ký|ngừng\\s+đăng\\s+ký|hủy\\s+nhận\\s+tin|huỷ\\s+nhận\\s+tin)[.!?~\\s]*$"
            + "|(?isU).*\\b(?:đừng\\s+nhắn(?:\\s+tin)?(?:\\s+nữa)?|thôi\\s+đừng\\s+nhắn(?:\\s+nữa)?|"
            + "đừng\\s+gửi\\s+tin(?:\\s+nhắn)?(?:\\s+nữa)?|ngừng\\s+nhắn(?:\\s+tin)?|dừng\\s+nhắn(?:\\s+tin)?|"
            + "ngừng\\s+gửi\\s+tin|ngừng\\s+liên\\s+hệ|đừng\\s+liên\\s+hệ(?:\\s+nữa)?|"
            + "đừng\\s+làm\\s+phiền(?:\\s+(?:chị|anh|em|mình|tôi))?(?:\\s+nữa)?|"
            + "đừng\\s+quấy\\s+rầy(?:\\s+nữa)?|làm\\s+ơn\\s+đừng\\s+nhắn|xin\\s+đừng\\s+nhắn)\\b.*"
    );

    /** Negation clauses that disqualify an utterance from being treated as active re-consent. */
    private static final Pattern RECONSENT_NEGATION_CLAUSE_PATTERN = Pattern.compile(
            "(?isU).*\\b(?:không\\s+(?:muốn|cần)\\s+(?:tư\\s+vấn|nhận\\s+tin|hỏi|đặt)|"
            + "chưa\\s+(?:muốn|cần)\\s+(?:tư\\s+vấn|đặt)|không\\s+hỏi\\s+giá|không\\s+đặt\\s+lịch)\\b.*"
    );

    /**
     * Explicit marketing / proactive notification re-subscribe command:
     * ONLY this explicit command restores proactive {@code /api/bot/fanpage/reengage} permission
     * ({@code re_opt_in_source = "EXPLICIT_MARKETING_REOPT_IN"}) after a customer has opted out.
     */
    public static final String RE_OPT_IN_SOURCE_EXPLICIT_MARKETING = "EXPLICIT_MARKETING_REOPT_IN";

    /**
     * Active customer-initiated service, price, or booking inquiry after opt-out:
     * opens reply permission ONLY for the customer's active service inquiries
     * ({@code re_opt_in_source = "SERVICE_INQUIRY_REPLY_ONLY"}), while proactive marketing
     * {@code /api/bot/fanpage/reengage} remains 100% blocked!
     */
    public static final String RE_OPT_IN_SOURCE_SERVICE_INQUIRY_REPLY_ONLY = "SERVICE_INQUIRY_REPLY_ONLY";

    private static final Pattern EXPLICIT_MARKETING_REOPT_IN_PATTERN = Pattern.compile(
            "(?isU)^\\s*(?:start|subscribe|đăng\\s+ký\\s+lại|đăng\\s+ký\\s+nhận\\s+tin(?:\\s+lại)?|"
            + "nhận\\s+tin\\s+(?:tiếp\\s+thị\\s+|khuyến\\s+mãi\\s+|tư\\s+vấn\\s+)?lại|"
            + "đồng\\s+ý\\s+nhận\\s+(?:lại\\s+)?tin(?:\\s+nhắn|\\s+tiếp\\s+thị|\\s+khuyến\\s+mãi|\\s+tư\\s+vấn)?)[.!?~\\s]*$"
    );

    /**
     * Concrete customer-initiated re-consent patterns (Scenarios 1, 2, 3):
     * (1) Concrete price inquiry, (2) Concrete booking / schedule availability request,
     * (3) Concrete service / package / operational inquiry or explicit re-subscribe request.
     * Short phatic/ambiguous messages ("ok", "ừ", "cảm ơn", "?", emoji) NEVER match (Scenario 4).
     */
    private static final Pattern EXPLICIT_RECONSENT_SERVICE_PRICE_BOOKING_PATTERN = Pattern.compile(
            "(?isU)^\\s*(?:start|subscribe|đăng\\s+ký\\s+lại)[.!?~\\s]*$"
            + "|(?isU).*\\b(?:"
            // Explicit re-consent request
            + "tư\\s+vấn\\s+lại|hỗ\\s+trợ\\s+tư\\s+vấn\\s+lại|nhận\\s+tin\\s+(?:tư\\s+vấn\\s+)?lại"
            // Scenario 1: Concrete price inquiry
            + "|bảng\\s+giá|bao\\s+nhiêu\\s+tiền|mấy\\s+tiền|nhiêu\\s+tiền|chi\\s+phí\\s+(?:gói|dịch\\s+vụ|làm|khám|cạo|tẩy|bọc|massage|gội)"
            + "|(?:giá|chi\\s+phí)\\s+.*?(?:bao\\s+nhiêu|thế\\s+nào|sao\\s+(?:em|ạ|shop))"
            + "|(?:gói|dịch\\s+vụ|liệu\\s+trình|\\d{2,3}\\s*(?:p|phút))\\s+.*?(?:giá|bao\\s+nhiêu|mấy\\s+tiền)"
            // Scenario 2: Concrete booking / schedule request
            + "|đặt\\s+lịch|đặt\\s+cho\\s+(?:chị|anh|mình|tôi|cô|chú)|giữ\\s+lịch|giữ\\s+chỗ|book\\s+lịch|hẹn\\s+lịch"
            + "|còn\\s+lịch(?:\\s+trống)?|lịch\\s+trống|còn\\s+giường|qua\\s+làm\\s+lúc|ghé\\s+lúc"
            + "|(?:đặt|hẹn|qua|ghé|book)\\s+.*?\\b\\d{1,2}\\s*(?:h\\d{0,2}|giờ)\\b"
            // Scenario 3: Concrete service / package / procedure / operational inquiry
            + "|(?:gói|dịch\\s+vụ|liệu\\s+trình|quy\\s+trình)\\s+.*?(?:gồm\\s+(?:những|mấy|các)|như\\s+thế\\s+nào|thế\\s+nào|bước\\s+gì|gì\\s+(?:em|vậy|ạ))"
            + "|quy\\s+trình\\s+(?:gội|massage|dưỡng\\s+sinh|chăm\\s+sóc|cạo\\s+vôi|lấy\\s+cao\\s+răng|tẩy\\s+trắng|bọc\\s+răng|khám)"
            + "|bên\\s+(?:mình|em|shop|phòng\\s+khám)\\s+có\\s+(?:gói|dịch\\s+vụ|làm)\\s+\\p{L}+"
            + "|(?:địa\\s+chỉ|cơ\\s+sở|phòng\\s+khám)\\s+.*?(?:ở\\s+đâu|chỗ\\s+nào|mở\\s+cửa|mấy\\s+giờ)"
            + "|mở\\s+cửa\\s+(?:tới|đến)\\s+mấy\\s+giờ"
            + ")\\b.*"
    );

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate jdbcRequiresNewTx;
    private final DurableInboundWalRecoveryStore walRecoveryStore;
    private volatile ConversationSessionOptimisticMergeCoordinator sessionMergeCoordinator;

    @Value("${docmind.security.atomic-state-guard.enabled:true}")
    private volatile boolean atomicStateGuardEnabled = true;

    @Value("${docmind.fanpage.default-page-id:}")
    private volatile String defaultFanpageId = "";

    public enum EntryAction {
        PROCEED_TO_BOT,
        RECONSENTED_PROCEED_TO_BOT,
        BLOCK_HUMAN_TAKEOVER,
        BLOCK_HUMAN_TAKEOVER_AND_OPT_OUT_RECORDED,
        BLOCK_OPTED_OUT,
        SUPPRESS_DUPLICATE_INBOUND,
        BLOCK_FAIL_CLOSED_WEBHOOK
    }

    public record EntryEvaluation(
            EntryAction action,
            UUID sessionId,
            String controlMode,
            boolean optedOut,
            ResolvedInboundEvent resolvedEvent,
            boolean insertedCustomerRow
    ) {
        public EntryEvaluation(
                EntryAction action,
                UUID sessionId,
                String controlMode,
                ResolvedInboundEvent resolvedEvent,
                boolean insertedCustomerRow
        ) {
            this(action, sessionId, controlMode, false, resolvedEvent, insertedCustomerRow);
        }
    }

    public record FreshSessionGuardState(
            UUID sessionId,
            String controlMode,
            boolean optedOut,
            Instant optedOutAt,
            Instant reOptInAt,
            String reOptInSource,
            String reOptInEvidence
    ) {
    }

    @Autowired
    public InboundSessionPersistenceService(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            DurableInboundWalRecoveryStore walRecoveryStore,
            ConversationSessionOptimisticMergeCoordinator sessionMergeCoordinator
    ) {
        this.jdbcTemplate = jdbcTemplate;
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.jdbcRequiresNewTx = tx;
        this.walRecoveryStore = walRecoveryStore;
        this.sessionMergeCoordinator = sessionMergeCoordinator;
    }

    public InboundSessionPersistenceService(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            DurableInboundWalRecoveryStore walRecoveryStore
    ) {
        this(jdbcTemplate, transactionManager, walRecoveryStore, null);
    }

    private ConversationSessionOptimisticMergeCoordinator resolveMergeCoordinator() {
        ConversationSessionOptimisticMergeCoordinator existing = this.sessionMergeCoordinator;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (this.sessionMergeCoordinator == null) {
                this.sessionMergeCoordinator = ConversationSessionOptimisticMergeCoordinator
                        .forStandaloneDataSource(this.jdbcTemplate.getDataSource());
            }
            return this.sessionMergeCoordinator;
        }
    }

    private <T> T executeInJpaRequiresNew(Callable<T> action) {
        return resolveMergeCoordinator().executeInRequiresNew(action);
    }

    @PostConstruct
    void bindWalReplayer() {
        if (this.walRecoveryStore != null) {
            this.walRecoveryStore.setDbReplayer(this::replayWalRecordToDatabase);
        }
    }

    public boolean isAtomicStateGuardEnabled() {
        return atomicStateGuardEnabled;
    }

    public void setAtomicStateGuardEnabled(boolean atomicStateGuardEnabled) {
        this.atomicStateGuardEnabled = atomicStateGuardEnabled;
    }

    public void setDefaultFanpageId(String defaultFanpageId) {
        this.defaultFanpageId = defaultFanpageId != null ? defaultFanpageId : "";
    }

    /**
     * Layer-1 Outbound Fencing helper: supersedes the active {@link SenderEpochRegistry} slot immediately
     * so any thread sleeping in {@code awaitBubbleDelayOrSupersede} or about to dispatch subsequent bubbles
     * wakes up and aborts remaining bubbles immediately (Tiers C1/C2/C3).
     */
    public static void supersedeEpochForSender(UUID pageId, String fallbackDefaultPageId, String rawPsid) {
        String psid = normalizePsid(rawPsid);
        if (pageId != null) {
            SenderEpochRegistry.supersedeCurrentEpoch(pageId.toString(), psid);
        }
        if (fallbackDefaultPageId != null && !fallbackDefaultPageId.isBlank()) {
            String trimmedDefault = fallbackDefaultPageId.strip();
            if (pageId == null || !trimmedDefault.equalsIgnoreCase(pageId.toString())) {
                SenderEpochRegistry.supersedeCurrentEpoch(trimmedDefault, psid);
            }
        }
        SenderEpochRegistry.supersedeCurrentEpoch(null, psid);
    }

    public static boolean isExplicitCustomerOptOutCommand(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String norm = Normalizer.normalize(message.strip(), Normalizer.Form.NFC);
        return EXPLICIT_CUSTOMER_OPT_OUT_COMMAND_PATTERN.matcher(norm).matches()
                || PrimaryActionRouter.isStrictSystemOptOutMessage(norm);
    }

    public static boolean isExplicitMarketingReOptInCommand(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String norm = Normalizer.normalize(message.strip(), Normalizer.Form.NFC);
        if (isExplicitCustomerOptOutCommand(norm)) {
            return false;
        }
        if (RECONSENT_NEGATION_CLAUSE_PATTERN.matcher(norm).matches()) {
            return false;
        }
        return EXPLICIT_MARKETING_REOPT_IN_PATTERN.matcher(norm).matches();
    }

    public static boolean isExplicitServicePriceOrBookingReconsent(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String norm = Normalizer.normalize(message.strip(), Normalizer.Form.NFC);
        if (isExplicitCustomerOptOutCommand(norm)) {
            return false;
        }
        if (RECONSENT_NEGATION_CLAUSE_PATTERN.matcher(norm).matches()) {
            return false;
        }
        return EXPLICIT_MARKETING_REOPT_IN_PATTERN.matcher(norm).matches()
                || EXPLICIT_RECONSENT_SERVICE_PRICE_BOOKING_PATTERN.matcher(norm).matches();
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
        boolean guardEnabled = this.atomicStateGuardEnabled;

        try {
            EntryEvaluation eval = executeWithOptimisticRetry(() -> {
                SessionSnapshot session = findOrCreateSessionInTx(requestedPageId, psid, customerName);
                Instant now = Instant.now();
                boolean inserted = false;
                if (!prePersistedInCaller && !safeText.isBlank()) {
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
                                session.optedOut(),
                                resolved,
                                false
                        );
                    }
                }

                boolean humanTakeover = "HUMAN_TAKEOVER".equalsIgnoreCase(session.controlMode());
                boolean explicitOptOutCmd = guardEnabled && isExplicitCustomerOptOutCommand(safeText);

                // Scenario 6: HUMAN_TAKEOVER combined with all (1)-(5)
                if (humanTakeover) {
                    if (explicitOptOutCmd) {
                        recordSessionOptOutInTx(session.id(), safeText, now);
                        return new EntryEvaluation(
                                EntryAction.BLOCK_HUMAN_TAKEOVER_AND_OPT_OUT_RECORDED,
                                session.id(),
                                session.controlMode(),
                                true,
                                resolved,
                                inserted
                        );
                    }
                    if (inserted) {
                        updateSessionCustomerPreviewInTx(session.id(), safeText, now);
                    }
                    return new EntryEvaluation(
                            EntryAction.BLOCK_HUMAN_TAKEOVER,
                            session.id(),
                            session.controlMode(),
                            session.optedOut(),
                            resolved,
                            inserted
                    );
                }

                // Scenario 5: Explicit customer opt-out command -> latest refusal ALWAYS wins immediately
                if (explicitOptOutCmd) {
                    recordSessionOptOutInTx(session.id(), safeText, now);
                    return new EntryEvaluation(
                            EntryAction.BLOCK_OPTED_OUT,
                            session.id(),
                            session.controlMode(),
                            true,
                            resolved,
                            inserted
                    );
                }

                // Scenarios 1-4: Session currently opted_out == true (while control_mode != HUMAN_TAKEOVER)
                if (guardEnabled && session.optedOut()) {
                    boolean explicitMarketingReOptIn = isExplicitMarketingReOptInCommand(safeText);
                    if (explicitMarketingReOptIn || isExplicitServicePriceOrBookingReconsent(safeText)) {
                        // Unit P1.1 (P0.4 Permission Split):
                        // - Concrete price/service/booking inquiry -> SERVICE_INQUIRY_REPLY_ONLY (allows active reply ONLY, keeps /reengage 100% blocked!)
                        // - Explicit marketing re-opt-in command -> EXPLICIT_MARKETING_REOPT_IN (restores proactive /reengage permission)
                        String channelTag = resolved.channel() != null ? resolved.channel().name() : "UNKNOWN";
                        String source = explicitMarketingReOptIn
                                ? RE_OPT_IN_SOURCE_EXPLICIT_MARKETING
                                : RE_OPT_IN_SOURCE_SERVICE_INQUIRY_REPLY_ONLY;
                        String evidence = buildReconsentEvidence(channelTag, resolved.externalMid(), safeText);
                        recordSessionReconsentInTx(session.id(), source, evidence, now);
                        log.info("[OPT_OUT_RECONSENT_AUDITED] sessionId={} pageId={} psid={} source={} channel={} mid={}",
                                session.id(), requestedPageId, psid, source, channelTag, resolved.externalMid());
                        return new EntryEvaluation(
                                EntryAction.RECONSENTED_PROCEED_TO_BOT,
                                session.id(),
                                session.controlMode(),
                                false,
                                resolved,
                                inserted
                        );
                    }
                    // Scenario 4: Phatic / short / ambiguous message -> keep opted_out = true, 0 LLM, 0 outbound
                    if (inserted) {
                        updateSessionCustomerPreviewInTx(session.id(), safeText, now);
                    }
                    return new EntryEvaluation(
                            EntryAction.BLOCK_OPTED_OUT,
                            session.id(),
                            session.controlMode(),
                            true,
                            resolved,
                            inserted
                    );
                }

                return new EntryEvaluation(
                        EntryAction.PROCEED_TO_BOT,
                        session.id(),
                        session.controlMode(),
                        session.optedOut(),
                        resolved,
                        inserted
                );
            });

            if (eval != null && (eval.action() == EntryAction.BLOCK_OPTED_OUT
                    || eval.action() == EntryAction.BLOCK_HUMAN_TAKEOVER_AND_OPT_OUT_RECORDED)) {
                supersedeEpochForSender(requestedPageId, this.defaultFanpageId, psid);
            }

            // Opportunistically drain any pending WAL records once PostgreSQL is healthy
            drainPendingWalQuietly();
            return eval;
        } catch (Exception dbEx) {
            return handleDbFailureFailClosed(request, safeText, resolved, psid, customerName, dbEx);
        }
    }

    /**
     * Layer-2 Outbound Fencing (Unit P0.4 — Tier C1):
     * Performs a fresh {@code REQUIRES_NEW} read of {@code (control_mode, opted_out)} from PostgreSQL
     * right after LLM generation completes and BEFORE committing {@code SentBubbleLedger} or dispatching
     * outbound bubbles. Fails closed ({@code false}) if PostgreSQL becomes unreachable mid-turn.
     */
    public boolean verifyOutboundStillPermittedFresh(UUID requestedPageId, String rawSenderId) {
        if (!this.atomicStateGuardEnabled) {
            return true;
        }
        String psid = normalizePsid(rawSenderId);
        try {
            Boolean permitted = executeInJpaRequiresNew(() -> {
                UUID effectiveDbPageId = resolveExistingPageIdOrNullInTx(requestedPageId);
                List<SessionSnapshot> rows = queryLatestSessionSnapshotInTx(effectiveDbPageId, psid);
                if (rows.isEmpty()) {
                    return true;
                }
                SessionSnapshot fresh = rows.get(0);
                if ("HUMAN_TAKEOVER".equalsIgnoreCase(fresh.controlMode())) {
                    return false;
                }
                return !fresh.optedOut();
            });
            return Boolean.TRUE.equals(permitted);
        } catch (Exception dbEx) {
            log.error("[LAYER2_OUTBOUND_FENCING_FAIL_CLOSED] pageId={} senderId={} reason={}",
                    requestedPageId, psid, dbEx.getMessage());
            return false;
        }
    }

    /**
     * Checks whether proactive {@code REENGAGE} is permitted for {@code (pageId, senderId)}.
     * Blocks proactive outreach 100% when session is in {@code HUMAN_TAKEOVER}, {@code opted_out == true},
     * OR has previously opted out ({@code opted_out_at IS NOT NULL}) without an explicit marketing
     * re-opt-in command ({@code re_opt_in_source != "EXPLICIT_MARKETING_REOPT_IN"}, e.g. when
     * {@code re_opt_in_source == "SERVICE_INQUIRY_REPLY_ONLY"}), or when DB is unreachable (Fail-Closed).
     */
    public boolean isProactiveReengageAllowed(UUID requestedPageId, String rawSenderId) {
        String psid = normalizePsid(rawSenderId);
        boolean guardEnabled = this.atomicStateGuardEnabled;
        try {
            Boolean allowed = executeInJpaRequiresNew(() -> {
                UUID effectiveDbPageId = resolveExistingPageIdOrNullInTx(requestedPageId);
                List<SessionSnapshot> rows = queryLatestSessionSnapshotInTx(effectiveDbPageId, psid);
                if (rows.isEmpty()) {
                    return true;
                }
                SessionSnapshot latest = rows.get(0);
                if ("HUMAN_TAKEOVER".equalsIgnoreCase(latest.controlMode())) {
                    return false;
                }
                if (guardEnabled) {
                    if (latest.optedOut()) {
                        return false;
                    }
                    if (latest.optedOutAt() != null
                            && !RE_OPT_IN_SOURCE_EXPLICIT_MARKETING.equalsIgnoreCase(latest.reOptInSource())) {
                        log.info("[REENGAGE_BLOCKED_MARKETING_OPT_OUT_ACTIVE] sessionId={} pageId={} psid={} optedOutAt={} reOptInSource={}",
                                latest.id(), requestedPageId, psid, latest.optedOutAt(), latest.reOptInSource());
                        return false;
                    }
                }
                return true;
            });
            return Boolean.TRUE.equals(allowed);
        } catch (Exception dbEx) {
            log.error("[REENGAGE_STATE_GUARD_FAIL_CLOSED] pageId={} senderId={} reason={}",
                    requestedPageId, psid, dbEx.getMessage());
            return false;
        }
    }

    /**
     * Reads fresh {@code REQUIRES_NEW} guard &amp; opt-out audit columns by {@code sessionId}
     * via JPA {@link ConversationSessionRepository} so callers never clobber concurrent updates.
     */
    public FreshSessionGuardState readFreshSessionGuardState(UUID sessionId) {
        if (sessionId == null) {
            return null;
        }
        try {
            return executeInJpaRequiresNew(() ->
                    resolveMergeCoordinator().repository().findById(sessionId)
                            .map(s -> new FreshSessionGuardState(
                                    s.getId(),
                                    s.getControlMode(),
                                    s.isOptedOut(),
                                    s.getOptedOutAt(),
                                    s.getReOptInAt(),
                                    s.getReOptInSource(),
                                    s.getReOptInEvidence()
                            ))
                            .orElse(null)
            );
        } catch (Exception ex) {
            log.debug("Non-fatal: unable to read V33/V35 fresh session guard state for {}: {}", sessionId, ex.getMessage());
            return null;
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
            Boolean inserted = jdbcRequiresNewTx.execute(status ->
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

        executeWithOptimisticRetry(() -> {
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
            if (this.atomicStateGuardEnabled && isExplicitCustomerOptOutCommand(record.verbatimText())) {
                recordSessionOptOutInTx(session.id(), record.verbatimText(), receivedAt);
            }
            return null;
        });
    }

    private <T> T executeWithOptimisticRetry(Callable<T> action) {
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                return executeInJpaRequiresNew(action);
            } catch (RuntimeException ex) {
                if (!ConversationSessionOptimisticMergeCoordinator.isOptimisticLockConflict(ex) || attempt >= 5) {
                    throw ex;
                }
                log.info("[ACI-P24-INBOUND-RETRY] attempt={} conflict={} -> retrying atomic inbound transaction",
                        attempt, ex.getClass().getSimpleName());
            }
        }
        throw new IllegalStateException("Exhausted inbound JPA optimistic retries");
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
                    false,
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

    private List<SessionSnapshot> queryLatestSessionSnapshotInTx(UUID effectiveDbPageId, String psid) {
        ConversationSessionRepository repo = resolveMergeCoordinator().repository();
        Optional<ConversationSessionEntity> found = effectiveDbPageId != null
                ? repo.findFirstByPageIdAndCustomerPsidOrderByUpdatedAtDesc(effectiveDbPageId, psid)
                : repo.findFirstByCustomerPsidOrderByUpdatedAtDesc(psid);
        return found.map(s -> List.of(new SessionSnapshot(
                        s.getId(),
                        s.getControlMode(),
                        s.isOptedOut(),
                        s.getOptedOutAt(),
                        s.getReOptInSource()
                )))
                .orElseGet(List::of);
    }

    private SessionSnapshot findOrCreateSessionInTx(UUID requestedPageId, String psid, String customerName) {
        String lockKey = "inbound_sess:" + (requestedPageId != null ? requestedPageId : "null") + ":" + psid;
        jdbcTemplate.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", Object.class, lockKey);

        UUID effectiveDbPageId = resolveExistingPageIdOrNullInTx(requestedPageId);
        ConversationSessionRepository repo = resolveMergeCoordinator().repository();
        Optional<ConversationSessionEntity> existing = effectiveDbPageId != null
                ? repo.findFirstByPageIdAndCustomerPsidOrderByUpdatedAtDesc(effectiveDbPageId, psid)
                : repo.findFirstByCustomerPsidOrderByUpdatedAtDesc(psid);

        if (existing.isPresent()) {
            ConversationSessionEntity found = existing.get();
            if (customerName != null && !customerName.isBlank()
                    && !"Khách hàng".equalsIgnoreCase(customerName.strip())) {
                found.setCustomerName(customerName.strip());
                found = repo.saveAndFlush(found);
            }
            return new SessionSnapshot(
                    found.getId(),
                    found.getControlMode(),
                    found.isOptedOut(),
                    found.getOptedOutAt(),
                    found.getReOptInSource()
            );
        }

        UUID newSessionId = UUID.randomUUID();
        String displayName = (customerName != null && !customerName.isBlank())
                ? customerName.strip()
                : "Khách Messenger (" + psid + ")";
        Instant now = Instant.now();

        ConversationSessionEntity created = ConversationSessionEntity.builder()
                .id(newSessionId)
                .pageId(effectiveDbPageId)
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
        ConversationSessionEntity saved = repo.saveAndFlush(created);
        return new SessionSnapshot(saved.getId(), saved.getControlMode(), saved.isOptedOut(), null, null);
    }

    private void recordSessionOptOutInTx(UUID sessionId, String previewText, Instant now) {
        ConversationSessionRepository repo = resolveMergeCoordinator().repository();
        ConversationSessionEntity entity = repo.findById(sessionId)
                .orElseThrow(() -> new IllegalStateException("Session not found: " + sessionId));
        entity.setOptedOut(true);
        entity.setOptedOutAt(now);
        entity.setReOptInAt(null);
        entity.setReOptInSource(null);
        entity.setReOptInEvidence(null);
        entity.setLastMessagePreview(previewText != null ? previewText : "");
        entity.setLastSenderRole("CUSTOMER");
        if (entity.getLastMessageAt() == null || now.isAfter(entity.getLastMessageAt())) {
            entity.setLastMessageAt(now);
        }
        entity.setUpdatedAt(now);
        AciCustomerBrainMemorySnapshot snap = AciCustomerBrainMemorySnapshot.fromJson(entity.getAciMemoryStateJson());
        if (snap != null) {
            entity.setAciMemoryStateJson(snap.withAlignedRowOptOut(true).toJson());
        }
        repo.saveAndFlush(entity);
    }

    private void recordSessionReconsentInTx(UUID sessionId, String source, String evidence, Instant now) {
        ConversationSessionRepository repo = resolveMergeCoordinator().repository();
        ConversationSessionEntity entity = repo.findById(sessionId)
                .orElseThrow(() -> new IllegalStateException("Session not found: " + sessionId));
        entity.setOptedOut(false);
        entity.setReOptInAt(now);
        entity.setReOptInSource(source);
        entity.setReOptInEvidence(evidence);
        entity.setUpdatedAt(now);
        AciCustomerBrainMemorySnapshot snap = AciCustomerBrainMemorySnapshot.fromJson(entity.getAciMemoryStateJson());
        if (snap != null) {
            entity.setAciMemoryStateJson(snap.withAlignedRowOptOut(false).toJson());
        }
        repo.saveAndFlush(entity);
    }

    private static String buildReconsentEvidence(String channelTag, String externalMid, String text) {
        String safeChannel = (channelTag != null && !channelTag.isBlank()) ? channelTag.strip() : "UNKNOWN";
        String safeMid = (externalMid != null && !externalMid.isBlank()) ? externalMid : "no-mid";
        String excerpt = text != null ? text.strip() : "";
        if (excerpt.length() > 260) {
            excerpt = excerpt.substring(0, 260);
        }
        return safeChannel + ":" + safeMid + " | " + excerpt;
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
        ConversationSessionRepository repo = resolveMergeCoordinator().repository();
        ConversationSessionEntity entity = repo.findById(sessionId)
                .orElseThrow(() -> new IllegalStateException("Session not found: " + sessionId));
        entity.setLastMessagePreview(previewText != null ? previewText : "");
        entity.setLastSenderRole("CUSTOMER");
        entity.setLastMessageAt(now);
        entity.setUpdatedAt(now);
        repo.saveAndFlush(entity);
    }

    private void updateSessionCustomerPreviewIfNewerInTx(UUID sessionId, String previewText, Instant receivedAt) {
        ConversationSessionRepository repo = resolveMergeCoordinator().repository();
        ConversationSessionEntity entity = repo.findById(sessionId)
                .orElseThrow(() -> new IllegalStateException("Session not found: " + sessionId));
        if (entity.getLastMessageAt() == null || !entity.getLastMessageAt().isAfter(receivedAt)) {
            entity.setLastMessagePreview(previewText != null ? previewText : "");
            entity.setLastSenderRole("CUSTOMER");
            if (entity.getLastMessageAt() == null || receivedAt.isAfter(entity.getLastMessageAt())) {
                entity.setLastMessageAt(receivedAt);
            }
            if (entity.getUpdatedAt() == null || receivedAt.isAfter(entity.getUpdatedAt())) {
                entity.setUpdatedAt(receivedAt);
            }
            repo.saveAndFlush(entity);
        }
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

    private record SessionSnapshot(
            UUID id,
            String controlMode,
            boolean optedOut,
            Instant optedOutAt,
            String reOptInSource
    ) {
    }
}
