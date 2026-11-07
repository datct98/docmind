package com.example.docmind.service.salescore;

import com.example.docmind.dto.bot.*;
import com.example.docmind.service.llm.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.example.docmind.service.salescore.SalesContracts.*;

class SalesContinuationTest {
    final UUID page = UUID.randomUUID();
    final TurnInput input = SalesCoreTest.input("GENERIC", "Giá");
    static final String QUOTE = SalesCoreTest.answer("Toàn thân 399k / 60 phút.", "ANSWER",
            "[{\"sourceId\":\"e1\",\"quote\":\"Toàn thân: 399k / 60 phút.\"}]")
            .replace("\"handoffSummary\":\"\"", "\"handoffSummary\":\"\",\"followUp\":{\"aim\":\"Làm rõ điều khách coi trọng\"}");
    FanpageBotRequest request(String action, Long seq) {
        return new FanpageBotRequest(page.toString(), "sender", "", "Giá", List.of(), List.of(), List.of(), 5,
                null, null, null, action, true, seq, Long.MAX_VALUE, "conversation", false);
    }
    @Test void newModelCallStartsOnlyAfterPriceAcknowledgementAndUsesThinkingWindow() {
        AtomicInteger generations = new AtomicInteger();
        AtomicBoolean reasoning = new AtomicBoolean();
        LlmClient llm = new LlmClient() {
            public String name() { return "test"; }
            public String complete(String p) { throw new AssertionError("Budget required"); }
            public String complete(String p, ReasoningBudget budget) {
                if (p.startsWith("SALES_REVIEW")) return "{\"verdict\":\"PASS\",\"issues\":[],\"feedback\":\"\"}";
                if (generations.incrementAndGet() == 1) return QUOTE;
                reasoning.set(budget.thinkEnabled());
                assertThat(p).contains("ALREADY_SENT_JSON", "PHASE=CONTINUATION");
                return "{\"strategy\":{\"action\":\"CLARIFY\",\"reason\":\"Làm rõ ưu tiên\",\"advance\":\"Chọn hướng phù hợp\"},\"bubble\":{\"text\":\"Mình muốn ưu tiên điều gì cho lần trải nghiệm này?\",\"evidence\":[]}}";
            }
        };
        try (var core = new SalesTurnOrchestrator(llm, new IndustryProfileRegistry(), 5000)) {
            var source = mock(SalesKnowledgeSource.class);
            when(source.load(any(), any(), any(), any(), any())).thenReturn(input);
            var gateway = new SalesCoreGateway(core, source, true);
            var first = gateway.respond(page, request(null, null), "Giá", null);
            assertThat(first.pendingSecondBubble()).isTrue();
            assertThat(first.replyBubbles()).hasSize(1);
            assertThat(generations).hasValue(1);
            var second = gateway.continueAfterQuote(page, request("GENERATE_PRICE_ANCHOR_BUBBLE", first.pendingTurnSequence()), null);
            assertThat(generations).hasValue(2);
            assertThat(reasoning).isTrue();
            assertThat(second.replyText()).doesNotContain("399k");
            assertThat(second.replyBubbles().getFirst().typingDelayMs()).isBetween(8000, 10000);
            assertThat(second.triggeredHooks()).contains("SALES_CORE_CONTINUATION");
            assertThat(gateway.continueAfterQuote(page, request("GENERATE_PRICE_ANCHOR_BUBBLE", first.pendingTurnSequence()), null).replyBubbles()).isEmpty();
            assertThat(generations).hasValue(2);
        }
    }
    @Test void newerCustomerCancelsContinuationEvenWhenModelIsInFlight() throws Exception {
        var core = mock(SalesTurnOrchestrator.class);
        var source = mock(SalesKnowledgeSource.class);
        var decision = new tools.jackson.databind.ObjectMapper().readValue(QUOTE, TurnDecision.class);
        var result = new Result(decision, List.of(decision.bubbles().getFirst().text()), false, false, 1, List.of(), 1, 1, "GENERIC", 0);
        when(source.load(any(), any(), any(), any(), any())).thenReturn(input);
        when(core.runPaced(any())).thenReturn(result);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(core.continueAfterQuote(any(), any())).thenAnswer(inv -> { started.countDown(); release.await(3, TimeUnit.SECONDS); return result; });
        var gateway = new SalesCoreGateway(core, source, true);
        var first = gateway.respond(page, request(null, null), "Giá", null);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = pool.submit(() -> gateway.continueAfterQuote(page, request("GENERATE_PRICE_ANCHOR_BUBBLE", first.pendingTurnSequence()), null));
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            gateway.cancelPending(page, "sender");
            release.countDown();
            assertThat(future.get(2, TimeUnit.SECONDS).replyBubbles()).isEmpty();
        }
    }
    @Test void ticketsAreBoundedExpiringSingleUseAndScopedToPageSenderConversation() {
        AtomicLong now = new AtomicLong();
        var store = new SalesContinuationStore(now::get, 2, 100);
        var t = store.begin(page, "a", "c");
        assertThat(store.stage(t, input, null)).isTrue();
        assertThat(store.claim(UUID.randomUUID(), "a", "c", t.sequence())).isNull();
        assertThat(store.claim(page, "b", "c", t.sequence())).isNull();
        assertThat(store.claim(page, "a", "wrong", t.sequence())).isNull();
        assertThat(store.claim(page, "a", "c", t.sequence())).isNotNull();
        assertThat(store.claim(page, "a", "c", t.sequence())).isNull();
        store.begin(page, "b", "c"); store.begin(page, "d", "c");
        assertThat(store.current(t)).isFalse();
        var newer = store.begin(page, "a", "new");
        now.set(101);
        assertThat(store.current(newer)).isFalse();
        assertThat(store.stage(t, input, null)).isFalse();
    }
    @Test void thinkingCountsTowardPauseAndLongerThinkingDoesNotAddAnotherTenSeconds() {
        assertThat(SalesCoreGateway.remainingPauseMs(10000, 3000)).isEqualTo(7000);
        assertThat(SalesCoreGateway.remainingPauseMs(10000, 14000)).isZero();
    }
    @Test void priceCanEndNaturallyWithoutManufacturingAQuestion() {
        var json = new tools.jackson.databind.ObjectMapper();
        var quote = json.readValue(QUOTE, TurnDecision.class);
        var complete = new TurnDecision(quote.understanding(), quote.strategy(), quote.bubbles(), "", quote.calculations(), null);
        assertThat(new SalesDecisionValidator().validateDelivery(complete, true, false, false)).isEmpty();
    }
    @Test void vietnameseCurrencyWithAccentedFinalLetterStillTriggersPriceDeliveryAndRequiresBusinessSource() {
        var json = new tools.jackson.databind.ObjectMapper();
        var validator = new SalesDecisionValidator();
        for (String price : List.of("399.000VNĐ", "399.000 VNĐ", "399000vnđ", "399k", "399.000đ")) {
            var quoted = json.readValue(QUOTE.replace("399k", price), TurnDecision.class);
            assertThat(validator.validateDelivery(quoted, true, false, false)).as(price).isEmpty();
            assertThat(validator.validateDelivery(quoted, true, true, false)).as(price).isNotEmpty();
            var invented = json.readValue(SalesCoreTest.answer("Giá " + price, "ANSWER", "[]"), TurnDecision.class);
            var budget = new TurnInput("p", "shop", "GENERIC", "em", "mình", "", List.of(), "399000", List.of(), List.of(), "", false);
            assertThat(validator.validate(invented, budget)).contains("UNSUPPORTED_NUMBER");
        }
    }
    @Test void unsupportedPriceCanRecoverWithCheckedContextualClarificationInsteadOfHandoff() {
        AtomicInteger calls = new AtomicInteger();
        try (var core = new SalesTurnOrchestrator(SalesCoreTest.fake(() -> calls.incrementAndGet() < 3
                ? SalesCoreTest.answer("Gói này 580k nhé.", "ANSWER", "[]")
                : SalesCoreTest.answer("Mình muốn giữ đủ phần chân tay hay ưu tiên vùng mỏi nhiều hơn?", "CLARIFY", "[]")),
                new IndustryProfileRegistry(), 5000)) {
            var result = core.run(input);
            assertThat(result.handoff()).isFalse();
            assertThat(result.fallback()).isFalse();
            assertThat(result.violations()).contains("GROUNDED_CLARIFICATION_RECOVERY");
            assertThat(result.texts().getFirst()).doesNotContain("580");
            assertThat(result.attempts()).isEqualTo(4);
        }
    }
    @Test void oneRepairReceivesBothDeliveryAndSemanticDefects() {
        AtomicInteger calls = new AtomicInteger();
        LlmClient llm = new LlmClient() {
            public String name() { return "combined-review-fixture"; }
            public String complete(String p) {
                return switch (calls.incrementAndGet()) {
                    case 1 -> QUOTE.replace("Toàn thân 399k / 60 phút.", "Toàn thân 399k / 60 phút. Mình chọn chứ?");
                    case 2 -> "{\"verdict\":\"REVISE\",\"issues\":[\"SCOPE_TOO_BROAD\"],\"feedback\":\"Giới hạn đúng phạm vi nguồn\",\"severity\":\"BLOCKING\"}";
                    case 3 -> {
                        assertThat(p).contains("PRICE_BUBBLE_QUESTION_BELONGS_IN_CONTINUATION", "SCOPE_TOO_BROAD");
                        yield QUOTE;
                    }
                    case 4 -> "{\"verdict\":\"PASS\",\"issues\":[],\"feedback\":\"\"}";
                    default -> throw new AssertionError("Unnecessary generation");
                };
            }
        };
        try (var core = new SalesTurnOrchestrator(llm, new IndustryProfileRegistry(), 5000)) {
            var result = core.runPaced(input);
            assertThat(result.fallback()).isFalse();
            assertThat(result.repairCount()).isEqualTo(1);
            assertThat(result.attempts()).isEqualTo(4);
            assertThat(result.texts()).containsExactly("Toàn thân 399k / 60 phút.");
        }
    }
}
