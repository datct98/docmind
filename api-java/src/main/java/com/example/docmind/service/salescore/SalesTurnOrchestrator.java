package com.example.docmind.service.salescore;

import com.example.docmind.service.llm.LlmClient;
import com.example.docmind.service.llm.ReasoningBudget;
import com.example.docmind.service.reasoning.QueryComplexity;
import com.example.docmind.service.bot.ThinkingMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import static com.example.docmind.service.salescore.SalesContracts.*;

@Service
public class SalesTurnOrchestrator implements AutoCloseable {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SalesTurnOrchestrator.class);
    private final LlmClient llm;
    private final IndustryProfileRegistry profiles;
    private final long deadlineMs;
    private final ObjectMapper json = new ObjectMapper();
    private final SalesDecisionValidator validator = new SalesDecisionValidator();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore capacity = new Semaphore(32);
    private boolean alternateWriter;
    private boolean writerReasoning;
    private boolean planningEnabled;
    private boolean goalFirstEnabled;
    private boolean strategiesEnabled;
    @Autowired
    public void setStrategiesEnabled(@Value("${sales.core.strategies-enabled:false}") boolean value) { strategiesEnabled = value; }
    @Autowired
    public void setGoalFirstEnabled(@Value("${sales.core.goal-first-enabled:false}") boolean value) { goalFirstEnabled = value; }
    @Autowired
    public void setPlanningEnabled(@Value("${sales.core.planning-enabled:true}") boolean value) { planningEnabled = value; }
    @Autowired
    public void setWriterReasoning(@Value("${sales.core.writer-reasoning:false}") boolean value) { writerReasoning = value; }
    @Autowired
    public void setWriterClient(@Value("${sales.core.writer-client:PRIMARY}") String selection) {
        if (!Set.of("PRIMARY", "ALTERNATE").contains(selection)) throw new IllegalArgumentException("Unknown sales writer client");
        alternateWriter = "ALTERNATE".equals(selection);
    }
    @Autowired
    public SalesTurnOrchestrator(LlmClient llm, IndustryProfileRegistry profiles,
            @Value("${sales.core.deadline-ms:45000}") long deadlineMs) {
        this.llm = llm; this.profiles = profiles; this.deadlineMs = Math.max(1, deadlineMs);
    }
    public Result run(TurnInput input) {
        return run(input, false, null);
    }
    public Result runPaced(TurnInput input) { return run(input, true, null); }
    public Result runPaced(TurnInput input, java.util.function.Supplier<TurnInput> evidence) { return run(input, true, null, evidence); }
    public Result run(TurnInput input, java.util.function.Supplier<TurnInput> evidence) { return run(input, false, null, evidence); }
    public Result continueAfterQuote(TurnInput input, TurnDecision first) {
        if (first == null || first.followUp() == null) throw new IllegalArgumentException("Missing continuation purpose");
        return run(input, true, first);
    }
    private Result run(TurnInput input, boolean paced, TurnDecision first) {
        return run(input, paced, first, null);
    }
    private Result run(TurnInput input, boolean paced, TurnDecision first, java.util.function.Supplier<TurnInput> evidenceLoader) {
        TurnClock clock = new TurnClock();
        clock.continuation = first != null;
        Set<String> violations = new LinkedHashSet<>();
        int promptChars = 0;
        String profileCode = "UNKNOWN";
        TurnInput completeInput = input;
        Result reviewedAlternative = null;
        try {
            var profile = profiles.load(input.industryCode());
            profileCode = profile.code();
            SalesConversationPlanner.Brief brief = null;
            TurnDecision preparedDraft = null;
            if ((!planningEnabled || goalFirstEnabled) && first == null && evidenceLoader != null) {
                input = evidenceLoader.get();
                completeInput = input;
            }
            if (planningEnabled && !goalFirstEnabled && first == null) {
                try {
                    brief = SalesConversationPlanner.parse(call(SalesConversationPlanner.prompt(input, profile), 2200, 0.15, clock, alternateWriter));
                } catch (CallFailure e) { throw e;
                } catch (RuntimeException e) {
                    log.warn("[SALES-PLAN-REJECTED] type={}", e.getClass().getSimpleName());
                    violations.add("PLAN_INVALID_EVIDENCE_PRESERVED");
                    if (evidenceLoader != null) input = evidenceLoader.get();
                    input = input.withInteractionPlan(new InteractionPlan(
                            "Giúp mục tiêu còn mở từ lịch sử và nguồn", "Giảm công sức khách", "NONE", "UNKNOWN", "UNKNOWN",
                            ConsultationStage.CONSULTATION, Map.of(), profile.recommendationRequirements(), HandoffReason.NONE, ""));
                    completeInput = input;
                }
                if (brief != null) {
                    if (Boolean.TRUE.equals(brief.memoryProposalRejected())) violations.add("MEMORY_PROPOSAL_PARSE_REJECTED");
                    input = input.withInteractionPlan(brief.contract(profile));
                    completeInput = input;
                    boolean needsEvidence = Boolean.TRUE.equals(brief.needsBusinessEvidence())
                            || !SalesConsultationPolicy.conversationOnly(input);
                    if (input.interactionPlan().stage() == ConsultationStage.CLOSE) needsEvidence = false;
                    if (needsEvidence && evidenceLoader != null) {
                        input = evidenceLoader.get().withInteractionPlan(input.interactionPlan());
                        completeInput = input;
                    }
                    if (!needsEvidence && !brief.replyBubbles().isEmpty()) {
                        input = SalesConversationPlanner.conversationOnly(input);
                        Action action = input.interactionPlan().stage() == ConsultationStage.CLOSE ? Action.PAUSE
                                : input.interactionPlan().stage() == ConsultationStage.TRUST_REPAIR ? Action.RESOLVE_CONCERN : Action.CLARIFY;
                        preparedDraft = new TurnDecision(new Understanding(brief.latestContribution(), List.of(), List.of(), ""),
                                new Strategy(action, brief.reason(), brief.nextDecision()), brief.replyBubbles().stream().map(t -> new Bubble(t, List.of())).toList(), "", List.of(), null);
                        // Reuse the planner's actual draft, but subject it to the SAME semantic/memory review as all replies.
                    }
                }
            }
            if (first != null) input = reviewInput(input, first);
            String originalPrompt = goalFirstEnabled && first == null ? SalesConsultationDraft.prompt(input, profile, false, strategiesEnabled)
                    : SalesPromptBuilder.build(input, profile, paced, first, false);
            if (brief != null) originalPrompt += "\nCONVERSATION_BRIEF_JSON (hypothesis, not new customer facts): " + json.writeValueAsString(brief)
                    + "\nBám quyết định tiếp theo trong brief; không nhắc catalog cũ chỉ để lấp câu. Dữ kiện khách vẫn phải lấy từ lời khách.";
            promptChars = originalPrompt.length();
            String prompt = originalPrompt;
            for (int generation = 0; generation < 3; generation++) {
                clock.repairCount = generation;
                boolean recovery = generation == 2;
                TurnInput activeInput = input;
                if (recovery) {
                    // Retain evidence. A failed draft is not a reason to forget the customer's goal or quote unsupported facts.
                    prompt = (goalFirstEnabled && first == null ? SalesConsultationDraft.prompt(activeInput, profile, true, strategiesEnabled)
                            : SalesPromptBuilder.build(activeInput, profile, paced, first, true))
                            + "\nREPAIR_ERRORS_JSON:" + json.writeValueAsString(violations);
                }
                List<String> currentErrors;
                String feedback = "";
                try {
                    TurnDecision decision;
                    if (goalFirstEnabled && first == null) {
                        var draft = SalesConsultationDraft.parse(call(prompt, 2200, 0.35, clock, alternateWriter || generation > 0), activeInput);
                        decision = draft.decision(); brief = draft.brief();
                        activeInput = input.withInteractionPlan(brief.contract(profile));
                        input = activeInput;
                        if (Boolean.TRUE.equals(brief.memoryProposalRejected())) violations.add("MEMORY_PROPOSAL_PARSE_REJECTED");
                    } else decision = generation == 0 && preparedDraft != null ? preparedDraft
                            : parse(call(prompt, 1800, 0.35, clock, alternateWriter || generation > 0), first);
                    if (!decision.strategy().techniques().isEmpty()) {
                        var cited = new HashSet<String>();
                        decision.bubbles().forEach(b -> b.evidence().forEach(c -> cited.add(c.sourceId())));
                        var assessed = SalesStrategyRegistry.assess(decision.strategy(), activeInput, cited);
                        violations.addAll(assessed.rejected());
                        var strategy = new Strategy(decision.strategy().action(), decision.strategy().reason(), decision.strategy().advance(),
                                strategiesEnabled ? assessed.accepted() : List.of());
                        decision = new TurnDecision(decision.understanding(), strategy, decision.bubbles(), decision.handoffSummary(), decision.calculations(), decision.followUp());
                    }
                    currentErrors = new ArrayList<>(validator.validate(decision, activeInput));
                    boolean validContract = currentErrors.isEmpty();
                    currentErrors.addAll(validator.validateDelivery(decision, paced, first != null, recovery,
                            activeInput.interactionPlan() == null ? ReplyMode.DIRECT : activeInput.interactionPlan().replyMode()));
                    // Review a structurally valid draft even if its delivery layout is wrong, so one repair
                    // fixes both factual/semantic defects and layout rather than spending separate retries.
                    if (validContract) {
                        // Memory proposals are untrusted until both source validation and this semantic review pass.
                        SalesMemory.View reviewedMemory = activeInput.memory();
                        List<SalesMemory.Update> updates = brief == null ? List.of() : brief.memoryUpdates();
                        boolean memoryValid = true;
                        try {
                            reviewedMemory = SalesMemory.apply(activeInput.memory(), updates);
                            if (brief != null) reviewedMemory = reviewedMemory.withJourney(SalesJourney.validate(brief.journey(), activeInput.memory()));
                        }
                        catch (RuntimeException invalidMemory) { memoryValid = false; reviewedMemory = activeInput.memory(); violations.add("MEMORY_UPDATE_REJECTED"); }
                        var review = SalesSemanticReview.parse(call(SalesSemanticReview.prompt(reviewInput(activeInput, decision), profile,
                                decision, paced, first, recovery) + "\nPROPOSED_CUSTOMER_MEMORY_UPDATES_JSON: "
                                + json.writeValueAsString(memoryValid ? updates : List.of())
                                + "\nPROPOSED_JOURNEY_JSON: " + json.writeValueAsString(memoryValid && brief != null ? brief.journey() : null)
                                + "\nSELECTED_STRATEGY_CARDS_JSON: " + json.writeValueAsString(SalesStrategyRegistry.selectedCards(decision.strategy()))
                                + "\nCheck selected strategy against actual customer intent, contraindications and reply. Labels are hypotheses, never proof. Reject pressure/false readiness; distinguish imagery from promised rights/outcomes.", 650, 0.0, clock, true));
                        if (review.memoryApproved() == Boolean.FALSE) {
                            reviewedMemory = activeInput.memory(); violations.add("MEMORY_SEMANTIC_REJECTED");
                        }
                        // Older review contracts did not assess memory. Never silently treat absence as approval.
                        if ((!updates.isEmpty() || (brief != null && brief.journey() != null)) && review.memoryApproved() != Boolean.TRUE) reviewedMemory = activeInput.memory();
                        boolean qualityOnly = review.verdict() == SalesSemanticReview.Verdict.REVISE
                                && review.severity() == SalesSemanticReview.Severity.QUALITY;
                        if (qualityOnly && currentErrors.isEmpty()) {
                            violations.add("QUALITY_REVIEW_WARNING");
                            violations.addAll(review.issues());
                            reviewedAlternative = new Result(decision, decision.bubbles().stream().map(Bubble::text).toList(),
                                    decision.strategy().action() == Action.HANDOFF, false, clock.calls, List.copyOf(violations),
                                    clock.elapsed(), promptChars, profileCode, clock.repairCount, reviewedMemory, activeInput.interactionPlan());
                            // One bounded improvement, not an unbounded style loop. A failed rewrite cannot erase
                            // a response which has already passed the source, authority and delivery checks.
                            long remaining = deadlineMs - clock.elapsed();
                            if (generation > 0 || remaining < 1000) return reviewedAlternative;
                            clock.qualityDeadline = clock.elapsed() + Math.min(16000, remaining);
                            violations.add("QUALITY_REPAIR_ATTEMPTED");
                        }
                        // A grounded answer with a style warning is preferable to a false operational handoff.
                        // Factual, scope and authority errors always remain blocking.
                        if (recovery && currentErrors.isEmpty() && (review.verdict() == SalesSemanticReview.Verdict.PASS || qualityOnly)) violations.add("GROUNDED_CLARIFICATION_RECOVERY");
                        if (currentErrors.isEmpty() && review.verdict() == SalesSemanticReview.Verdict.PASS) return new Result(decision,
                                decision.bubbles().stream().map(Bubble::text).toList(), decision.strategy().action() == Action.HANDOFF,
                                false, clock.calls, List.copyOf(violations), clock.elapsed(), promptChars, profileCode, clock.repairCount,
                                reviewedMemory, activeInput.interactionPlan());
                        if (review.verdict() != SalesSemanticReview.Verdict.PASS) currentErrors.addAll(review.issues());
                        feedback = review.feedback();
                    }
                } catch (CallFailure e) { violations.add(e.code); break;
                } catch (RuntimeException e) { currentErrors = List.of("INVALID_JSON_CONTRACT"); }
                violations.addAll(currentErrors);
                if (reviewedAlternative != null && generation > 0) break;
                if (!goalFirstEnabled && first == null && input != completeInput && !recovery && !SalesConsultationPolicy.conversationOnly(input)) {
                    // The planner's context choice is provisional. A failed compact draft can consult the
                    // original evidence during repair; otherwise it could never fix a missing-source error.
                    input = evidenceLoader == null ? completeInput : evidenceLoader.get().withInteractionPlan(input.interactionPlan());
                    violations.add("PLAN_CONTEXT_EXPANDED");
                    originalPrompt = SalesPromptBuilder.build(input, profile, paced, null, false)
                            + "\nThe compact conversation-only draft failed validation. Full original business evidence is now available."
                            + " Use it only for the customer's current decision; do not repeat a catalog already sent.";
                    if (brief != null) originalPrompt += "\nPREVIOUS_PROVISIONAL_BRIEF_JSON: " + json.writeValueAsString(brief)
                            + "\nKeep the current conversation objective; revise the provisional choice only when evidence warrants it.";
                    promptChars = Math.max(promptChars, originalPrompt.length());
                }
                // Invalid output never becomes authority. Review feedback remains subordinate to the original contract.
                prompt = originalPrompt + "\nREVIEW_DATA_JSON: " + json.writeValueAsString(Map.of("errors", currentErrors, "feedback", feedback))
                        + "\nGenerate a fresh complete JSON response fixing these errors from the original evidence. Do not invent facts or transactions.";
            }
        } catch (CallFailure e) { violations.add(e.code);
        } catch (RuntimeException e) { violations.add("CONTEXT_OR_PROFILE_ERROR"); }
        if (reviewedAlternative != null) {
            violations.add("CHECKED_REPLY_RETAINED_AFTER_QUALITY_REPAIR");
            return new Result(reviewedAlternative.decision(), reviewedAlternative.texts(), reviewedAlternative.handoff(), false,
                    clock.calls, List.copyOf(violations), clock.elapsed(), promptChars, profileCode, clock.repairCount,
                    reviewedAlternative.reviewedMemory(), reviewedAlternative.interactionPlan());
        }
        if (input.interactionPlan() != null && input.interactionPlan().stage() == ConsultationStage.CLOSE) {
            var close = new TurnDecision(new Understanding("Tôn trọng yêu cầu dừng", List.of(), List.of(), ""),
                    new Strategy(Action.PAUSE, "CONSENT_SAFE_RECOVERY", "Dừng tư vấn"), List.of(new Bubble("Dạ, em dừng tư vấn tại đây.", List.of())), "", List.of());
            return new Result(close, List.of(close.bubbles().getFirst().text()), false, true, clock.calls, List.copyOf(violations),
                    clock.elapsed(), promptChars, profileCode, clock.repairCount, input.memory(), input.interactionPlan());
        }
        return new Result(null, List.of("Xin lỗi mình, lượt trả lời này đang gặp trục trặc. Mình gửi lại tin nhắn giúp em nhé."),
                false, true, clock.calls, List.copyOf(violations), clock.elapsed(), promptChars, profileCode, clock.repairCount);
    }
    private TurnInput reviewInput(TurnInput input, TurnDecision decision) {
        Set<String> ids = new HashSet<>();
        decision.bubbles().forEach(b -> b.evidence().forEach(c -> ids.add(c.sourceId())));
        decision.calculations().forEach(c -> c.operands().forEach(o -> ids.add(o.sourceId())));
        // Full cited sources preserve conditions and scope. Unused catalog entries cannot justify a candidate's claims.
        return new TurnInput(input.pageId(), input.businessName(), input.industryCode(), input.selfPronoun(),
                input.customerPronoun(), input.voice(), input.history(), input.message(),
                input.evidence().stream().filter(e -> ids.contains(e.id())).toList(), input.businessPolicies(),
                input.quotedMessage(), input.contextTruncated(), input.memory(), input.interactionPlan());
    }
    private String call(String prompt, int tokens, double temperature, TurnClock clock, boolean preferAlternate) {
        long remainingTurn = Math.min(deadlineMs, clock.qualityDeadline) - clock.elapsed();
        long phaseBudget = prompt.startsWith("SALES_PLAN") ? 24000 : prompt.startsWith("SALES_REVIEW") ? 14000 : 24000;
        long remaining = Math.min(remainingTurn, phaseBudget);
        if (remaining <= 0) throw new CallFailure("TURN_DEADLINE");
        if (!capacity.tryAcquire()) throw new CallFailure("PROVIDER_BUSY");
        // Use the already configured alternate for review/repair, not a vendor or industry hardcoded here.
        LlmClient selected = preferAlternate && llm instanceof com.example.docmind.service.llm.ResilientFallbackLlmClient resilient
                && resilient.getFallback() != null ? new com.example.docmind.service.llm.ResilientFallbackLlmClient(
                        resilient.getFallback(), resilient.getPrimary()) : llm;
        log.info("[SALES-CORE-MODEL] purpose={} model={}", prompt.startsWith("SALES_PLAN") ? "PLAN" : prompt.startsWith("SALES_REVIEW") ? "REVIEW" : clock.repairCount > 0 ? "REPAIR" : clock.continuation ? "CONTINUE" : "GENERATE", selected.name());
        Future<String> future;
        var requestContext = org.slf4j.MDC.getCopyOfContextMap();
        try {
            future = executor.submit(() -> {
                try {
                    if (requestContext != null) org.slf4j.MDC.setContextMap(requestContext);
                    boolean think = (writerReasoning || clock.continuation)
                            && !prompt.startsWith("SALES_REVIEW");
                    return selected.complete(prompt, new ReasoningBudget(QueryComplexity.SIMPLE, think ? "low" : "none",
                            (int)Math.max(1, remaining / 1000), 0, 8000, tokens, temperature, 0.9, null,
                            think ? (clock.continuation ? ThinkingMode.PRICE_ANCHOR_THINK : ThinkingMode.EMOTIONAL_WARM_THINK)
                                    : ThinkingMode.FAST_NO_THINK));
                } finally { org.slf4j.MDC.clear(); capacity.release(); }
            });
            clock.calls++;
        } catch (RejectedExecutionException e) { capacity.release(); throw new CallFailure("PROVIDER_BUSY"); }
        try { return future.get(remaining, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) { future.cancel(true); throw new CallFailure("TURN_DEADLINE");
        } catch (InterruptedException e) { future.cancel(true); Thread.currentThread().interrupt(); throw new CallFailure("INTERRUPTED");
        } catch (ExecutionException e) { throw new CallFailure("PROVIDER_ERROR"); }
    }
    private record ContinuationDraft(Strategy strategy, Bubble bubble) {}
    private TurnDecision parse(String raw, TurnDecision first) {
        String text = SalesJson.objectText(raw, 24000);
        if (first != null) {
            var draft = json.readValue(text, ContinuationDraft.class);
            // There has been no new customer input since the first checked decision. Do not let a second
            // writer re-infer customer facts or transaction state just to compose one continuation.
            return new TurnDecision(first.understanding(), draft.strategy(), List.of(draft.bubble()), "", List.of(), null);
        }
        return json.readValue(text, TurnDecision.class);
    }
    private static final class TurnClock {
        final long start = System.nanoTime(); int calls; int repairCount; boolean continuation;
        long qualityDeadline = Long.MAX_VALUE;
        long elapsed() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }
    }
    private static final class CallFailure extends RuntimeException {
        final String code;
        CallFailure(String code) { super(code); this.code = code; }
    }
    @Override @PreDestroy public void close() { executor.shutdownNow(); }
}
