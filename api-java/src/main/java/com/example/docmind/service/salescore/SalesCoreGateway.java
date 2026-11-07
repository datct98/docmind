package com.example.docmind.service.salescore;

import com.example.docmind.dto.bot.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import static com.example.docmind.service.salescore.SalesContracts.*;

/** Adapter into existing inbox/delivery contracts. The core never writes booking/control state. */
@Service
public class SalesCoreGateway {
    private static final Logger log = LoggerFactory.getLogger(SalesCoreGateway.class);
    private final SalesTurnOrchestrator core;
    private final SalesKnowledgeSource knowledge;
    private final boolean enabled;
    private final SalesContinuationStore continuations = new SalesContinuationStore();
    private int quotePauseMs = 10000;
    @org.springframework.beans.factory.annotation.Autowired
    public void setQuotePauseMs(@Value("${sales.core.quote-pause-ms:10000}") int value) {
        if (value < 0 || value > 30000) throw new IllegalArgumentException("Invalid quote pause");
        quotePauseMs = value;
    }
    private Set<UUID> pilotPages = Set.of();
    @org.springframework.beans.factory.annotation.Autowired
    public void setPilotPages(@Value("${sales.core.page-allowlist:}") String value) {
        pilotPages = value == null || value.isBlank() ? Set.of() : Arrays.stream(value.split(","))
                .map(String::strip).map(UUID::fromString).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    public SalesCoreGateway(SalesTurnOrchestrator core, SalesKnowledgeSource knowledge,
                            @Value("${sales.core.enabled:false}") boolean enabled) {
        this.core = core; this.knowledge = knowledge; this.enabled = enabled;
    }
    public boolean enabled() { return enabled; }
    public boolean enabledFor(UUID pageId) { return enabled && pageId != null && (pilotPages.isEmpty() || pilotPages.contains(pageId)); }
    public void cancelPending(UUID pageId, String sender) { continuations.cancel(pageId, sender); }
    public FanpageBotResponse respond(UUID pageId, FanpageBotRequest request, String message, FanpageBotResponse.ExtractedLead lead) {
        long started = System.nanoTime();
        var ticket = continuations.begin(pageId, request.senderId(), request.conversationId());
        Result result;
        TurnInput input = null;
        long sourceMs = 0;
        try {
            List<Message> history = request.isResetSessionRequested() || request.history() == null ? List.of()
                    : request.history().stream().map(t -> new Message(t.role(), t.content())).toList();
            input = knowledge.load(pageId, request.documentIds(), history, message, request.quotedMessageText());
            sourceMs = (System.nanoTime() - started) / 1_000_000;
            result = Boolean.TRUE.equals(request.splitPriceBubbles()) ? core.runPaced(input) : core.run(input);
        } catch (RuntimeException e) {
            log.warn("[SALES-CORE] context unavailable page={} error_type={}", pageId, e.getClass().getSimpleName());
            result = new Result(null, List.of("Em cần nhờ nhân viên kiểm tra thông tin trước khi tư vấn tiếp cho mình."),
                    true, true, 0, List.of("CONTEXT_UNAVAILABLE"), 0, 0, "UNKNOWN", 0);
        }
        if (!continuations.current(ticket)) return suppressed("SUPERSEDED_SALES_CORE_TURN");
        var response = response(pageId, result, message, lead, sourceMs, started, 0, false);
        if (!result.fallback() && !result.handoff() && result.decision() != null && result.decision().followUp() != null
                && continuations.stage(ticket, input, result.decision()))
            return response.withPendingSecondBubble(true, ticket.sequence(), null);
        continuations.finish(ticket);
        return response;
    }
    public FanpageBotResponse continueAfterQuote(UUID pageId, FanpageBotRequest request, FanpageBotResponse.ExtractedLead lead) {
        // Invoked only after the transport has displayed/accepted the price bubble. Ignore client timestamps and context.
        long started = System.nanoTime();
        var pending = continuations.claim(pageId, request.senderId(), request.conversationId(), request.pendingTurnSequence());
        if (pending == null) return suppressed("SALES_CONTINUATION_EXPIRED_OR_SUPERSEDED");
        try {
            Result result = core.continueAfterQuote(pending.input(), pending.first());
            if (!continuations.current(pending.ticket())) return suppressed("SUPERSEDED_SALES_CORE_CONTINUATION");
            // Already delivered a checked quote. A failed optional continuation must not replace it with a false handoff.
            if (result.fallback()) {
                log.warn("[SALES-CONTINUATION] suppressed page={} violations={}", pageId, result.violations());
                return suppressed("SALES_CONTINUATION_VALIDATION_FAILED");
            }
            int remaining = remainingPauseMs(quotePauseMs, (System.nanoTime() - started) / 1_000_000);
            return response(pageId, result, pending.input().message(), lead, 0, started, remaining, true);
        } finally { continuations.finish(pending.ticket()); }
    }
    static int remainingPauseMs(int pause, long thinkingMs) { return (int)Math.max(0, pause - thinkingMs); }
    private FanpageBotResponse suppressed(String reason) {
        return new FanpageBotResponse("", List.of(), List.of(), "PAUSE", 0, false, null, null, null,
                List.of(), null, List.of(), List.of("SALES_CORE_V1", reason));
    }
    private FanpageBotResponse response(UUID pageId, Result result, String message, FanpageBotResponse.ExtractedLead lead,
                                        long sourceMs, long started, int delayMs, boolean continuation) {
        var bubbles = result.texts().stream().map(t -> new FanpageBotResponse.ReplyBubble(t, delayMs)).toList();
        List<String> hooks = new ArrayList<>(List.of("SALES_CORE_V1", "SALES_CORE_PACED_V2", "EVIDENCE_CONTRACT_CHECKED", "TOKEN_USAGE_UNAVAILABLE"));
        if (!result.fallback() && !result.violations().contains("QUALITY_REVIEW_WARNING")) hooks.add("SEMANTIC_REVIEW_PASS");
        if (result.fallback()) hooks.add("SALES_CORE_SAFE_HANDOFF");
        if (continuation) hooks.add("SALES_CORE_CONTINUATION");
        hooks.addAll(result.violations());
        String action = result.decision() == null ? "HANDOFF" : result.decision().strategy().action().name();
        String handoffReason = result.handoff() ? result.decision() == null
                ? "Không xác minh được dữ kiện hoặc phản hồi trong thời hạn." : result.decision().handoffSummary() : null;
        long total = (System.nanoTime() - started) / 1_000_000;
        var response = new FanpageBotResponse(String.join("\n\n", result.texts()), bubbles, bubbles.stream().map(FanpageBotResponse.ReplyBubble::typingDelayMs).toList(), action, 0,
                result.handoff(), result.handoff() ? "SALES_ASSISTANCE" : null, handoffReason, lead, List.of(), null, List.of(), hooks)
                .withSkillTiersAndProfiling(List.of(), List.of(), List.of(),
                        new FanpageBotResponse.TurnProfiling(0, 0, 0, result.elapsedMs(), sourceMs, result.repairCount(), total));
        response = response.withDecisionTrace(new DecisionTrace(UUID.randomUUID().toString(), DecisionTrace.sha256Short(message),
                action, "NONE", null, List.of(), List.of(), List.of(), List.of(), List.of(),
                new DecisionTrace.ResponsePlanTrace(action, "ADAPTIVE", "CONTEXTUAL", false),
                new DecisionTrace.SanitizerMutations(false, false, false)));
        log.info("[SALES-CORE] page={} profile={} prompt_version={} prompt_chars={} action={} attempts={} violations={} fallback={} total_ms={}",
                pageId, result.profileCode(), SalesPromptBuilder.VERSION, result.promptChars(), action, result.attempts(),
                result.violations(), result.fallback(), total);
        return response;
    }
}
