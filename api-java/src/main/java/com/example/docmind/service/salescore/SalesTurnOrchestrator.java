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
        TurnClock clock = new TurnClock();
        Set<String> violations = new LinkedHashSet<>();
        int promptChars = 0;
        String profileCode = "UNKNOWN";
        try {
            var profile = profiles.load(input.industryCode());
            profileCode = profile.code();
            String originalPrompt = SalesPromptBuilder.build(input, profile);
            promptChars = originalPrompt.length();
            String prompt = originalPrompt;
            for (int generation = 0; generation < 2; generation++) {
                clock.repairCount = generation;
                List<String> currentErrors;
                String feedback = "";
                try {
                    TurnDecision decision = parse(call(prompt, 1800, 0.35, clock, alternateWriter || generation > 0));
                    currentErrors = validator.validate(decision, input);
                    if (currentErrors.isEmpty()) {
                        var review = SalesSemanticReview.parse(call(SalesSemanticReview.prompt(input, profile, decision), 450, 0.0, clock, true));
                        boolean qualityOnly = review.verdict() == SalesSemanticReview.Verdict.REVISE
                                && review.severity() == SalesSemanticReview.Severity.QUALITY;
                        if (qualityOnly && generation > 0) {
                            violations.add("QUALITY_REVIEW_WARNING");
                            violations.addAll(review.issues());
                        }
                        // A grounded answer with a style warning is preferable to a false operational handoff.
                        // Factual, scope and authority errors always remain blocking.
                        if (review.verdict() == SalesSemanticReview.Verdict.PASS || (qualityOnly && generation > 0)) return new Result(decision,
                                decision.bubbles().stream().map(Bubble::text).toList(), decision.strategy().action() == Action.HANDOFF,
                                false, clock.calls, List.copyOf(violations), clock.elapsed(), promptChars, profileCode, clock.repairCount);
                        currentErrors = review.issues(); feedback = review.feedback();
                    }
                } catch (CallFailure e) { violations.add(e.code); break;
                } catch (RuntimeException e) { currentErrors = List.of("INVALID_JSON_CONTRACT"); }
                violations.addAll(currentErrors);
                // Invalid output never becomes authority. Review feedback remains subordinate to the original contract.
                prompt = originalPrompt + "\nREVIEW_DATA_JSON: " + json.writeValueAsString(Map.of("errors", currentErrors, "feedback", feedback))
                        + "\nGenerate a fresh complete JSON response fixing these errors from the original evidence. Do not invent facts or transactions.";
            }
        } catch (RuntimeException e) { violations.add("CONTEXT_OR_PROFILE_ERROR"); }
        return new Result(null, List.of("Em cần nhờ nhân viên kiểm tra thêm để tư vấn chính xác cho mình."),
                true, true, clock.calls, List.copyOf(violations), clock.elapsed(), promptChars, profileCode, clock.repairCount);
    }
    private String call(String prompt, int tokens, double temperature, TurnClock clock, boolean preferAlternate) {
        long remaining = deadlineMs - clock.elapsed();
        if (remaining <= 0) throw new CallFailure("TURN_DEADLINE");
        if (!capacity.tryAcquire()) throw new CallFailure("PROVIDER_BUSY");
        // Use the already configured alternate for review/repair, not a vendor or industry hardcoded here.
        LlmClient selected = preferAlternate && llm instanceof com.example.docmind.service.llm.ResilientFallbackLlmClient resilient
                && resilient.getFallback() != null ? resilient.getFallback() : llm;
        log.info("[SALES-CORE-MODEL] purpose={} model={}", prompt.startsWith("SALES_REVIEW") ? "REVIEW" : clock.repairCount > 0 ? "REPAIR" : "GENERATE", selected.name());
        Future<String> future;
        try {
            future = executor.submit(() -> {
                try {
                    return selected.complete(prompt, new ReasoningBudget(QueryComplexity.SIMPLE, "none",
                            (int)Math.max(1, remaining / 1000), 0, 8000, tokens, temperature, 0.9, null, ThinkingMode.FAST_NO_THINK));
                } finally { capacity.release(); }
            });
            clock.calls++;
        } catch (RejectedExecutionException e) { capacity.release(); throw new CallFailure("PROVIDER_BUSY"); }
        try { return future.get(remaining, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) { future.cancel(true); throw new CallFailure("TURN_DEADLINE");
        } catch (InterruptedException e) { future.cancel(true); Thread.currentThread().interrupt(); throw new CallFailure("INTERRUPTED");
        } catch (ExecutionException e) { throw new CallFailure("PROVIDER_ERROR"); }
    }
    private TurnDecision parse(String raw) {
        if (raw == null || raw.length() > 24000) throw new IllegalArgumentException("Invalid response size");
        String text = raw.strip();
        if (text.startsWith("```json\n") && text.endsWith("```")) text = text.substring(8, text.length() - 3).strip();
        return json.readValue(text, TurnDecision.class);
    }
    private static final class TurnClock {
        final long start = System.nanoTime(); int calls; int repairCount;
        long elapsed() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }
    }
    private static final class CallFailure extends RuntimeException {
        final String code;
        CallFailure(String code) { super(code); this.code = code; }
    }
    @Override @PreDestroy public void close() { executor.shutdownNow(); }
}
