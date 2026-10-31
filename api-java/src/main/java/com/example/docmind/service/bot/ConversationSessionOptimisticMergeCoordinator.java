package com.example.docmind.service.bot;

import com.example.docmind.entity.ConversationSessionEntity;
import com.example.docmind.repository.ConversationSessionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import org.hibernate.StaleStateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Unit P2.4 (V35): Single authoritative JPA {@code @Version} optimistic-locking write coordinator
 * and {@code Monotonic Merge} conflict resolver for {@link ConversationSessionEntity}
 * ({@code public.conversation_sessions}).
 *
 * <p>Guarantees that {@code 100%} of writes to {@code conversation_sessions} go through JPA
 * {@code @Version} optimistic locking and resolve {@link OptimisticLockingFailureException} /
 * {@code ObjectOptimisticLockingFailureException} deterministically without rolling back:
 * <ul>
 *   <li>{@code control_mode = HUMAN_TAKEOVER / CONVERTED} always beats {@code BOT_ACTIVE / NEEDS_HANDOFF}.</li>
 *   <li>{@code opted_out = true} (when {@code opted_out_at} is newer) always wins.</li>
 *   <li>Confirmed {@code captured_phone} and {@code preferred_time} are never erased back to {@code null}.</li>
 *   <li>Time-ordered collections ({@code confirmed_facts}, {@code self_reported_symptoms},
 *       {@code unresolved_requests}) merge by canonical {@code key} using latest {@code updatedTurnId} /
 *       {@code observedAt}; {@code NEGATED} or {@code SUPERSEDED} facts never revert to {@code CURRENT}.</li>
 * </ul>
 */
@Service
public class ConversationSessionOptimisticMergeCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ConversationSessionOptimisticMergeCoordinator.class);
    private static final int MAX_OPTIMISTIC_MERGE_ATTEMPTS = 6;

    private static final Map<DataSource, StandaloneJpaHandle> STANDALONE_JPA_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    public record WriteOptions(
            boolean explicitOperatorControlModeOverride,
            boolean memoryOnlyWrite,
            boolean explicitMemoryReset
    ) {
        public static final WriteOptions STANDARD_MONOTONIC = new WriteOptions(false, false, false);
        public static final WriteOptions EXPLICIT_OPERATOR_CONTROL_MODE = new WriteOptions(true, false, false);
        public static final WriteOptions MEMORY_ONLY_MONOTONIC = new WriteOptions(false, true, false);
        public static final WriteOptions EXPLICIT_MEMORY_RESET = new WriteOptions(false, true, true);
    }

    public record MergeWriteResult(
            ConversationSessionEntity entity,
            int optimisticRetries
    ) {
    }

    private record StandaloneJpaHandle(
            EntityManagerFactory emf,
            JpaTransactionManager txManager,
            ConversationSessionRepository repository,
            ConversationSessionOptimisticMergeCoordinator coordinator
    ) {
    }

    private final ConversationSessionRepository sessionRepository;
    private final TransactionTemplate requiresNewTx;
    private final AtomicLong totalOptimisticMergeRetries = new AtomicLong(0);

    @Autowired
    public ConversationSessionOptimisticMergeCoordinator(
            ConversationSessionRepository sessionRepository,
            PlatformTransactionManager transactionManager
    ) {
        this.sessionRepository = sessionRepository;
        if (transactionManager != null) {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.requiresNewTx = tx;
        } else {
            this.requiresNewTx = null;
        }
    }

    public static ConversationSessionOptimisticMergeCoordinator forMockOrPassthroughRepository(
            ConversationSessionRepository repository
    ) {
        return new ConversationSessionOptimisticMergeCoordinator(repository, null);
    }

    /**
     * Builds (and caches per {@link DataSource}) a zero-metadata-probe JPA {@link ConversationSessionRepository}
     * + {@link JpaTransactionManager} so standalone non-Spring integration tests (e.g. Units P0.1, P0.4, P2.4)
     * execute {@code 100%} of {@code conversation_sessions} reads and writes through JPA {@code @Version}.
     */
    public static ConversationSessionOptimisticMergeCoordinator forStandaloneDataSource(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("DataSource must not be null for standalone JPA bootstrap");
        }
        synchronized (STANDALONE_JPA_CACHE) {
            StandaloneJpaHandle cached = STANDALONE_JPA_CACHE.get(dataSource);
            if (cached != null) {
                return cached.coordinator();
            }
            LocalContainerEntityManagerFactoryBean fb = new LocalContainerEntityManagerFactoryBean();
            fb.setDataSource(dataSource);
            fb.setPackagesToScan(ConversationSessionEntity.class.getPackageName());
            HibernateJpaVendorAdapter vendor = new HibernateJpaVendorAdapter();
            vendor.setShowSql(false);
            vendor.setGenerateDdl(false);
            fb.setJpaVendorAdapter(vendor);

            Map<String, Object> props = new HashMap<>();
            props.put("hibernate.hbm2ddl.auto", "none");
            props.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
            props.put("hibernate.boot.allow_jdbc_metadata_access", false);
            props.put("hibernate.temp.use_jdbc_metadata_defaults", false);
            fb.setJpaPropertyMap(props);
            fb.afterPropertiesSet();

            EntityManagerFactory emf = fb.getObject();
            if (emf == null) {
                throw new IllegalStateException("Failed to initialize standalone EntityManagerFactory");
            }
            JpaTransactionManager jpaTx = new JpaTransactionManager(emf);
            jpaTx.afterPropertiesSet();

            EntityManager sharedEm = SharedEntityManagerCreator.createSharedEntityManager(emf);
            ConversationSessionRepository repo = new JpaRepositoryFactory(sharedEm)
                    .getRepository(ConversationSessionRepository.class);
            ConversationSessionOptimisticMergeCoordinator coord =
                    new ConversationSessionOptimisticMergeCoordinator(repo, jpaTx);

            STANDALONE_JPA_CACHE.put(dataSource, new StandaloneJpaHandle(emf, jpaTx, repo, coord));
            return coord;
        }
    }

    public ConversationSessionRepository repository() {
        return sessionRepository;
    }

    public long totalOptimisticMergeRetries() {
        return totalOptimisticMergeRetries.get();
    }

    public <T> T executeInRequiresNew(Callable<T> action) {
        if (requiresNewTx == null) {
            try {
                return action.call();
            } catch (RuntimeException re) {
                throw re;
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }
        return requiresNewTx.execute(status -> {
            try {
                return action.call();
            } catch (RuntimeException re) {
                throw re;
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    /**
     * Persists a newly created or detached {@link ConversationSessionEntity} snapshot using JPA {@code @Version}.
     * On {@link OptimisticLockingFailureException} / {@code ObjectOptimisticLockingFailureException}, reloads
     * the latest committed row in a fresh {@code REQUIRES_NEW} transaction and applies {@code Monotonic Merge}.
     */
    public MergeWriteResult saveCandidateWithMonotonicMerge(
            ConversationSessionEntity candidate,
            WriteOptions options
    ) {
        if (candidate == null) {
            throw new IllegalArgumentException("ConversationSessionEntity candidate must not be null");
        }
        WriteOptions effectiveOptions = options != null ? options : WriteOptions.STANDARD_MONOTONIC;
        ConversationSessionEntity detachedAttempt = candidate.copySnapshot();
        int retries = 0;

        for (int attempt = 1; attempt <= MAX_OPTIMISTIC_MERGE_ATTEMPTS; attempt++) {
            final int currentAttempt = attempt;
            final ConversationSessionEntity snapshotForAttempt = detachedAttempt;
            try {
                ConversationSessionEntity saved = executeInRequiresNew(() -> {
                    if (currentAttempt == 1) {
                        return sessionRepository.saveAndFlush(snapshotForAttempt);
                    }
                    Optional<ConversationSessionEntity> freshOpt = sessionRepository.findById(snapshotForAttempt.getId());
                    if (freshOpt.isEmpty()) {
                        ConversationSessionEntity insertCopy = snapshotForAttempt.copySnapshot();
                        insertCopy.setVersion(null);
                        return sessionRepository.saveAndFlush(insertCopy);
                    }
                    ConversationSessionEntity freshDb = freshOpt.get();
                    applyMonotonicMergeInPlace(freshDb, snapshotForAttempt, effectiveOptions);
                    return sessionRepository.saveAndFlush(freshDb);
                });
                return new MergeWriteResult(saved != null ? saved.copySnapshot() : snapshotForAttempt, retries);
            } catch (RuntimeException ex) {
                if (!isOptimisticLockConflict(ex) || attempt >= MAX_OPTIMISTIC_MERGE_ATTEMPTS) {
                    throw ex;
                }
                retries++;
                totalOptimisticMergeRetries.incrementAndGet();
                log.info("[ACI-P24-OPTIMISTIC-MERGE] sessionId={} attempt={} conflict={} -> reloading & merging monotonically",
                        detachedAttempt.getId(), attempt, ex.getClass().getSimpleName());
            }
        }
        throw new IllegalStateException("Exhausted optimistic merge retries for session " + candidate.getId());
    }

    /**
     * Atomically loads {@code sessionId} in {@code REQUIRES_NEW}, applies {@code mutator} to produce a candidate,
     * saves via JPA {@code @Version}, and automatically retries with {@code Monotonic Merge} on concurrent conflict.
     */
    public MergeWriteResult mutateByIdWithMonotonicMerge(
            UUID sessionId,
            Consumer<ConversationSessionEntity> mutator,
            WriteOptions options
    ) {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId must not be null");
        }
        WriteOptions effectiveOptions = options != null ? options : WriteOptions.STANDARD_MONOTONIC;
        int retries = 0;
        ConversationSessionEntity firstCandidateSnapshot = null;

        for (int attempt = 1; attempt <= MAX_OPTIMISTIC_MERGE_ATTEMPTS; attempt++) {
            final ConversationSessionEntity priorAttemptCandidate = firstCandidateSnapshot;
            try {
                ConversationSessionEntity[] capturedCandidate = new ConversationSessionEntity[1];
                ConversationSessionEntity saved = executeInRequiresNew(() -> {
                    ConversationSessionEntity fresh = sessionRepository.findById(sessionId)
                            .orElseThrow(() -> new IllegalStateException("Session not found: " + sessionId));
                    if (priorAttemptCandidate == null) {
                        ConversationSessionEntity working = fresh.copySnapshot();
                        mutator.accept(working);
                        capturedCandidate[0] = working.copySnapshot();
                        applyMonotonicMergeInPlace(fresh, working, effectiveOptions);
                    } else {
                        capturedCandidate[0] = priorAttemptCandidate;
                        applyMonotonicMergeInPlace(fresh, priorAttemptCandidate, effectiveOptions);
                    }
                    return sessionRepository.saveAndFlush(fresh);
                });
                return new MergeWriteResult(saved != null ? saved.copySnapshot() : capturedCandidate[0], retries);
            } catch (RuntimeException ex) {
                if (!isOptimisticLockConflict(ex) || attempt >= MAX_OPTIMISTIC_MERGE_ATTEMPTS) {
                    throw ex;
                }
                retries++;
                totalOptimisticMergeRetries.incrementAndGet();
                log.info("[ACI-P24-OPTIMISTIC-MERGE] mutateById sessionId={} attempt={} conflict={}",
                        sessionId, attempt, ex.getClass().getSimpleName());
            }
        }
        throw new IllegalStateException("Exhausted optimistic merge retries for session " + sessionId);
    }

    /**
     * Pure, deterministic {@code Monotonic Merge} applying {@code candidate} onto {@code freshDb}.
     */
    public static void applyMonotonicMergeInPlace(
            ConversationSessionEntity freshDb,
            ConversationSessionEntity candidate,
            WriteOptions options
    ) {
        WriteOptions opts = options != null ? options : WriteOptions.STANDARD_MONOTONIC;

        // 1. Priority control_mode invariant:
        //    HUMAN_TAKEOVER / CONVERTED NEVER regress to BOT_ACTIVE / NEEDS_HANDOFF from stale AI turns!
        if (opts.explicitOperatorControlModeOverride()) {
            if (candidate.getControlMode() != null && !candidate.getControlMode().isBlank()) {
                freshDb.setControlMode(candidate.getControlMode().strip().toUpperCase(Locale.ROOT));
            }
        } else {
            String winningMode = resolveMonotonicControlMode(freshDb.getControlMode(), candidate.getControlMode());
            freshDb.setControlMode(winningMode);
        }

        if (!"BOT_ACTIVE".equalsIgnoreCase(freshDb.getControlMode())) {
            String mergedCat = nonBlankOrKeep(candidate.getHandoffCategory(), freshDb.getHandoffCategory());
            String mergedReason = nonBlankOrKeep(candidate.getHandoffReason(), freshDb.getHandoffReason());
            freshDb.setHandoffCategory(mergedCat);
            freshDb.setHandoffReason(mergedReason);
        }

        // 2. Priority opted_out / re_opt_in temporal invariant:
        //    opted_out = true (when opted_out_at is newer than or equal to re_opt_in_at) ALWAYS wins!
        mergeOptOutFieldsMonotonically(freshDb, candidate);

        // 3. Confirmed captured_phone and preferred_time invariant:
        //    Confirmed values are NEVER erased back to null!
        freshDb.setCapturedPhone(nonBlankOrKeep(candidate.getCapturedPhone(), freshDb.getCapturedPhone()));
        freshDb.setPreferredTime(nonBlankOrKeep(candidate.getPreferredTime(), freshDb.getPreferredTime()));

        // 4. Monotonic audit flags
        freshDb.setHasHumanSaleReply(freshDb.isHasHumanSaleReply() || candidate.isHasHumanSaleReply());
        freshDb.setLearnedInColdPath(freshDb.isLearnedInColdPath() || candidate.isLearnedInColdPath());

        // 5. Session summary & preview fields (skip clobbering on memory-only writes)
        if (!opts.memoryOnlyWrite()) {
            if (isMeaningfulCustomerName(candidate.getCustomerName())) {
                freshDb.setCustomerName(candidate.getCustomerName().strip());
            }
            if (candidate.getHonorific() != null && !candidate.getHonorific().isBlank()) {
                freshDb.setHonorific(candidate.getHonorific().strip());
            }
            if (candidate.getActiveTopic() != null && !candidate.getActiveTopic().isBlank()) {
                freshDb.setActiveTopic(candidate.getActiveTopic());
            }
            if (candidate.getTopicFamily() != null && !candidate.getTopicFamily().isBlank()) {
                freshDb.setTopicFamily(candidate.getTopicFamily());
            }
            if (candidate.getSalesStage() != null && !candidate.getSalesStage().isBlank()) {
                freshDb.setSalesStage(candidate.getSalesStage());
            }

            boolean candHasNewerMsg = isCandidatePreviewNewer(freshDb, candidate);
            if (candHasNewerMsg) {
                if (candidate.getLastMessagePreview() != null && !candidate.getLastMessagePreview().isEmpty()) {
                    freshDb.setLastMessagePreview(candidate.getLastMessagePreview());
                }
                if (candidate.getLastSenderRole() != null && !candidate.getLastSenderRole().isBlank()) {
                    freshDb.setLastSenderRole(candidate.getLastSenderRole());
                }
                if (candidate.getLastMessageAt() != null) {
                    freshDb.setLastMessageAt(maxInstant(freshDb.getLastMessageAt(), candidate.getLastMessageAt()));
                }
                freshDb.setBuyingTemperature(candidate.getBuyingTemperature());
                freshDb.setPreviousTemperature(candidate.getPreviousTemperature() != null
                        ? candidate.getPreviousTemperature()
                        : freshDb.getPreviousTemperature());
                freshDb.setTemperatureDelta(candidate.getTemperatureDelta());
                if (candidate.getTemperatureLabel() != null) {
                    freshDb.setTemperatureLabel(candidate.getTemperatureLabel());
                }
                if (candidate.getMomentum() != null) {
                    freshDb.setMomentum(candidate.getMomentum());
                }
                if (candidate.getBuyerArchetype() != null) {
                    freshDb.setBuyerArchetype(candidate.getBuyerArchetype());
                }
                if (candidate.getArchetypeLabel() != null) {
                    freshDb.setArchetypeLabel(candidate.getArchetypeLabel());
                }
                freshDb.setExtractedPains(nonBlankOrKeep(candidate.getExtractedPains(), freshDb.getExtractedPains()));
                freshDb.setHiddenConcerns(nonBlankOrKeep(candidate.getHiddenConcerns(), freshDb.getHiddenConcerns()));
                freshDb.setRecommendedStrategy(
                        nonBlankOrKeep(candidate.getRecommendedStrategy(), freshDb.getRecommendedStrategy()));
            }
        } else if (candidate.getHonorific() != null && !candidate.getHonorific().isBlank()) {
            freshDb.setHonorific(candidate.getHonorific().strip());
        }

        // 6. Deep JSONB Monotonic Merge for aci_memory_state (CustomerBrainMemory)
        if (opts.explicitMemoryReset()) {
            String resetPayload = candidate.getAciMemoryStateJson();
            freshDb.setAciMemoryStateJson((resetPayload != null && !resetPayload.isBlank()) ? resetPayload : "{}");
        } else {
            Instant existingResetFloor = AciCustomerBrainMemorySnapshot.extractMemoryResetFloor(freshDb.getAciMemoryStateJson());
            AciCustomerBrainMemorySnapshot dbMem =
                    AciCustomerBrainMemorySnapshot.fromJson(freshDb.getAciMemoryStateJson());
            AciCustomerBrainMemorySnapshot candMem =
                    AciCustomerBrainMemorySnapshot.fromJson(candidate.getAciMemoryStateJson());
            AciCustomerBrainMemorySnapshot mergedMem =
                    AciCustomerBrainMemorySnapshot.monotonicMerge(dbMem, candMem);
            if (mergedMem != null) {
                freshDb.setAciMemoryStateJson(
                        mergedMem.withMinMemoryResetAt(existingResetFloor)
                                .withAlignedRowOptOut(freshDb.isOptedOut())
                                .toJson()
                );
            } else if (freshDb.getAciMemoryStateJson() == null || freshDb.getAciMemoryStateJson().isBlank()) {
                freshDb.setAciMemoryStateJson("{}");
            }
        }

        freshDb.setUpdatedAt(maxInstant(maxInstant(freshDb.getUpdatedAt(), candidate.getUpdatedAt()), Instant.now()));
    }

    public static String resolveMonotonicControlMode(String freshModeRaw, String candidateModeRaw) {
        String fresh = normalizeControlMode(freshModeRaw);
        String cand = normalizeControlMode(candidateModeRaw);
        return controlModePriorityRank(cand) >= controlModePriorityRank(fresh) ? cand : fresh;
    }

    private static int controlModePriorityRank(String normalizedMode) {
        return switch (normalizedMode) {
            case "CONVERTED" -> 4;
            case "HUMAN_TAKEOVER" -> 3;
            case "NEEDS_HANDOFF" -> 2;
            default -> 1; // BOT_ACTIVE
        };
    }

    private static String normalizeControlMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return "BOT_ACTIVE";
        }
        String u = raw.strip().toUpperCase(Locale.ROOT);
        return switch (u) {
            case "CONVERTED", "HUMAN_TAKEOVER", "NEEDS_HANDOFF", "BOT_ACTIVE" -> u;
            default -> "BOT_ACTIVE";
        };
    }

    private static void mergeOptOutFieldsMonotonically(
            ConversationSessionEntity freshDb,
            ConversationSessionEntity candidate
    ) {
        Instant dbOptOutAt = freshDb.getOptedOutAt();
        Instant candOptOutAt = candidate.getOptedOutAt();
        Instant maxOptOutAt = maxInstant(dbOptOutAt, candOptOutAt);

        Instant dbReOptInAt = freshDb.getReOptInAt();
        Instant candReOptInAt = candidate.getReOptInAt();
        Instant maxReOptInAt = maxInstant(dbReOptInAt, candReOptInAt);

        if (maxOptOutAt != null) {
            if (maxReOptInAt != null && maxReOptInAt.isAfter(maxOptOutAt)) {
                boolean candSuppliedReOptIn = candReOptInAt != null && candReOptInAt.equals(maxReOptInAt);
                freshDb.setOptedOut(false);
                freshDb.setOptedOutAt(maxOptOutAt);
                freshDb.setReOptInAt(maxReOptInAt);
                freshDb.setReOptInSource(candSuppliedReOptIn
                        ? nonBlankOrKeep(candidate.getReOptInSource(), freshDb.getReOptInSource())
                        : nonBlankOrKeep(freshDb.getReOptInSource(), candidate.getReOptInSource()));
                freshDb.setReOptInEvidence(candSuppliedReOptIn
                        ? nonBlankOrKeep(candidate.getReOptInEvidence(), freshDb.getReOptInEvidence())
                        : nonBlankOrKeep(freshDb.getReOptInEvidence(), candidate.getReOptInEvidence()));
            } else {
                freshDb.setOptedOut(true);
                freshDb.setOptedOutAt(maxOptOutAt);
                freshDb.setReOptInAt(null);
                freshDb.setReOptInSource(null);
                freshDb.setReOptInEvidence(null);
            }
            return;
        }

        // Neither side has opted_out_at timestamp: monotonic OR on boolean flag unless explicit re-opt-in exists
        if (maxReOptInAt != null) {
            boolean candSupplied = candReOptInAt != null && candReOptInAt.equals(maxReOptInAt);
            freshDb.setOptedOut(false);
            freshDb.setReOptInAt(maxReOptInAt);
            freshDb.setReOptInSource(candSupplied
                    ? nonBlankOrKeep(candidate.getReOptInSource(), freshDb.getReOptInSource())
                    : nonBlankOrKeep(freshDb.getReOptInSource(), candidate.getReOptInSource()));
            freshDb.setReOptInEvidence(candSupplied
                    ? nonBlankOrKeep(candidate.getReOptInEvidence(), freshDb.getReOptInEvidence())
                    : nonBlankOrKeep(freshDb.getReOptInEvidence(), candidate.getReOptInEvidence()));
        } else {
            freshDb.setOptedOut(freshDb.isOptedOut() || candidate.isOptedOut());
        }
    }

    private static boolean isCandidatePreviewNewer(
            ConversationSessionEntity freshDb,
            ConversationSessionEntity candidate
    ) {
        Instant dbTs = freshDb.getLastMessageAt();
        Instant candTs = candidate.getLastMessageAt();
        if (candTs == null) {
            return false;
        }
        if (dbTs == null) {
            return true;
        }
        if (candTs.isAfter(dbTs)) {
            return true;
        }
        if (candTs.equals(dbTs)) {
            return candidate.getLastMessagePreview() != null && !candidate.getLastMessagePreview().isBlank();
        }
        return false;
    }

    private static boolean isMeaningfulCustomerName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String trimmed = name.strip();
        return !"Khách hàng".equalsIgnoreCase(trimmed)
                && !"Khách hàng Messenger".equalsIgnoreCase(trimmed)
                && !trimmed.startsWith("Khách Messenger (");
    }

    public static boolean isOptimisticLockConflict(Throwable throwable) {
        Throwable cur = throwable;
        int depth = 0;
        while (cur != null && depth < 12) {
            if (cur instanceof OptimisticLockingFailureException
                    || cur instanceof OptimisticLockException
                    || cur instanceof StaleStateException) {
                return true;
            }
            cur = cur.getCause();
            depth++;
        }
        return false;
    }

    private static String nonBlankOrKeep(String candidate, String existing) {
        if (candidate != null && !candidate.isBlank()) {
            return candidate.strip();
        }
        return existing;
    }

    private static Instant maxInstant(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }
}
