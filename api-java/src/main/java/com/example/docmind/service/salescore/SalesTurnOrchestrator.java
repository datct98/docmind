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
    public Result continueAfterQuote(TurnInput input, TurnDecision first) {
        if (first == null || first.followUp() == null) throw new IllegalArgumentException("Missing continuation purpose");
        return run(input, true, first);
    }
    private Result run(TurnInput input, boolean paced, TurnDecision first) {
        TurnClock clock = new TurnClock();
        clock.continuation = first != null;
        Set<String> violations = new LinkedHashSet<>();
        int promptChars = 0;
        String profileCode = "UNKNOWN";
        TurnInput completeInput = input;
        try {
            var profile = profiles.load(input.industryCode());
            profileCode = profile.code();
            SalesConversationPlanner.Brief brief = null;
            if (planningEnabled && first == null) {
                try {
                    brief = SalesConversationPlanner.parse(call(SalesConversationPlanner.prompt(input, profile), 1800, 0.15, clock, alternateWriter));
                    input = input.withInteractionPlan(brief.contract());
                    completeInput = input;
                    if (!brief.needsBusinessEvidence()) input = SalesConversationPlanner.conversationOnly(input);
                } catch (CallFailure e) { throw e;
                } catch (RuntimeException e) { violations.add("PLAN_INVALID_USING_FULL_CONTEXT"); }
            }
            if (first != null) input = reviewInput(input, first);
            String originalPrompt = first == null && input != completeInput
                    ? SalesPromptBuilder.conversation(input, profile, brief, false)
                    : SalesPromptBuilder.build(input, profile, paced, first, false);
            if (brief != null) originalPrompt += "\nCONVERSATION_BRIEF_JSON (hypothesis, not new customer facts): " + json.writeValueAsString(brief)
                    + "\nBám quyết định tiếp theo trong brief; không nhắc catalog cũ chỉ để lấp câu. Dữ kiện khách vẫn phải lấy từ lời khách.";
            promptChars = originalPrompt.length();
            String prompt = originalPrompt;
            for (int generation = 0; generation < 3; generation++) {
                clock.repairCount = generation;
                boolean recovery = generation == 2;
                TurnInput activeInput = recovery ? new TurnInput(input.pageId(), input.businessName(), input.industryCode(),
                        input.selfPronoun(), input.customerPronoun(), input.voice(), input.history(), input.message(),
                        List.of(), List.of(), input.quotedMessage(), input.contextTruncated(), input.memory(), input.interactionPlan()) : input;
                if (recovery) {
                    // Narrow the task instead of letting an evidence error masquerade as a customer handoff.
                    prompt = (first == null ? SalesPromptBuilder.conversation(activeInput, profile, brief, true)
                            : SalesPromptBuilder.build(activeInput, profile, paced, first, true))
                            + "\nREPAIR_ERRORS_JSON:" + json.writeValueAsString(violations);
                }
                List<String> currentErrors;
                String feedback = "";
                try {
                    TurnDecision decision = parse(call(prompt, 1800, 0.35, clock, alternateWriter || generation > 0), first);
                    currentErrors = new ArrayList<>(validator.validate(decision, activeInput));
                    boolean validContract = currentErrors.isEmpty();
                    currentErrors.addAll(validator.validateDelivery(decision, paced, first != null, recovery));
                    // Review a structurally valid draft even if its delivery layout is wrong, so one repair
                    // fixes both factual/semantic defects and layout rather than spending separate retries.
                    if (validContract) {
                        // Memory proposals are untrusted until both source validation and this semantic review pass.
                        SalesMemory.View reviewedMemory = activeInput.memory();
                        List<SalesMemory.Update> updates = brief == null ? List.of() : brief.memoryUpdates();
                        boolean memoryValid = true;
                        try { reviewedMemory = SalesMemory.apply(activeInput.memory(), updates); }
                        catch (RuntimeException invalidMemory) { memoryValid = false; violations.add("MEMORY_UPDATE_REJECTED"); }
                        var review = SalesSemanticReview.parse(call(SalesSemanticReview.prompt(reviewInput(activeInput, decision), profile,
                                decision, paced, first, recovery) + "\nPROPOSED_CUSTOMER_MEMORY_UPDATES_JSON: "
                                + json.writeValueAsString(memoryValid ? updates : List.of()), 650, 0.0, clock, true));
                        if (review.memoryApproved() == Boolean.FALSE) {
                            reviewedMemory = activeInput.memory(); violations.add("MEMORY_SEMANTIC_REJECTED");
                        }
                        // Older review contracts did not assess memory. Never silently treat absence as approval.
                        if (!updates.isEmpty() && review.memoryApproved() != Boolean.TRUE) reviewedMemory = activeInput.memory();
                        boolean qualityOnly = review.verdict() == SalesSemanticReview.Verdict.REVISE
                                && review.severity() == SalesSemanticReview.Severity.QUALITY;
                        if (qualityOnly && generation > 0 && currentErrors.isEmpty()) {
                            violations.add("QUALITY_REVIEW_WARNING");
                            violations.addAll(review.issues());
                        }
                        // A grounded answer with a style warning is preferable to a false operational handoff.
                        // Factual, scope and authority errors always remain blocking.
                        if (recovery && currentErrors.isEmpty() && (review.verdict() == SalesSemanticReview.Verdict.PASS || qualityOnly)) violations.add("GROUNDED_CLARIFICATION_RECOVERY");
                        if (currentErrors.isEmpty() && (review.verdict() == SalesSemanticReview.Verdict.PASS || (qualityOnly && generation > 0))) return new Result(decision,
                                decision.bubbles().stream().map(Bubble::text).toList(), decision.strategy().action() == Action.HANDOFF,
                                false, clock.calls, List.copyOf(violations), clock.elapsed(), promptChars, profileCode, clock.repairCount,
                                reviewedMemory, activeInput.interactionPlan());
                        if (review.verdict() != SalesSemanticReview.Verdict.PASS) currentErrors.addAll(review.issues());
                        feedback = review.feedback();
                    }
                } catch (CallFailure e) { violations.add(e.code); break;
                } catch (RuntimeException e) { currentErrors = List.of("INVALID_JSON_CONTRACT"); }
                violations.addAll(currentErrors);
                if (first == null && input != completeInput && !recovery) {
                    // The planner's context choice is provisional. A failed compact draft can consult the
                    // original evidence during repair; otherwise it could never fix a missing-source error.
                    input = completeInput;
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
        return new Result(null, List.of("Em cần nhờ nhân viên kiểm tra thêm để tư vấn chính xác cho mình."),
                true, true, clock.calls, List.copyOf(violations), clock.elapsed(), promptChars, profileCode, clock.repairCount);
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
        long remaining = deadlineMs - clock.elapsed();
        if (remaining <= 0) throw new CallFailure("TURN_DEADLINE");
        if (!capacity.tryAcquire()) throw new CallFailure("PROVIDER_BUSY");
        // Use the already configured alternate for review/repair, not a vendor or industry hardcoded here.
        LlmClient selected = preferAlternate && llm instanceof com.example.docmind.service.llm.ResilientFallbackLlmClient resilient
                && resilient.getFallback() != null ? resilient.getFallback() : llm;
        log.info("[SALES-CORE-MODEL] purpose={} model={}", prompt.startsWith("SALES_PLAN") ? "PLAN" : prompt.startsWith("SALES_REVIEW") ? "REVIEW" : clock.repairCount > 0 ? "REPAIR" : clock.continuation ? "CONTINUE" : "GENERATE", selected.name());
        Future<String> future;
        try {
            future = executor.submit(() -> {
                try {
                    boolean think = (writerReasoning || clock.continuation)
                            && !prompt.startsWith("SALES_REVIEW");
                    return selected.complete(prompt, new ReasoningBudget(QueryComplexity.SIMPLE, think ? "low" : "none",
                            (int)Math.max(1, remaining / 1000), 0, 8000, tokens, temperature, 0.9, null,
                            think ? (clock.continuation ? ThinkingMode.PRICE_ANCHOR_THINK : ThinkingMode.EMOTIONAL_WARM_THINK)
                                    : ThinkingMode.FAST_NO_THINK));
                } finally { capacity.release(); }
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
        if (raw == null || raw.length() > 24000) throw new IllegalArgumentException("Invalid response size");
        String text = raw.strip();
        if (text.startsWith("```json\n") && text.endsWith("```")) text = text.substring(8, text.length() - 3).strip();
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
        long elapsed() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }
    }
    private static final class CallFailure extends RuntimeException {
        final String code;
        CallFailure(String code) { super(code); this.code = code; }
    }
    @Override @PreDestroy public void close() { executor.shutdownNow(); }
}
