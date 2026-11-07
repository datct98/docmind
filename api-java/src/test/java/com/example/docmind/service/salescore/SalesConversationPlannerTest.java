package com.example.docmind.service.salescore;

import com.example.docmind.service.llm.LlmClient;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SalesConversationPlannerTest {
    @Test void realModelMalformedOptionalStructuresCannotEraseTheActiveRequest() throws Exception {
        try (var stream = getClass().getResourceAsStream("/salescore/planner-malformed-optional.json")) {
            var brief = SalesConversationPlanner.parse(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            assertThat(brief.needsBusinessEvidence()).isTrue();
            assertThat(brief.memoryProposalRejected()).isTrue();
            assertThat(brief.journey().primaryGoal()).isNull();
            assertThat(brief.journey().observedOutcome()).isNull();
            assertThat(brief.journey().openLoops()).hasSize(2);
            assertThat(brief.decisionCheck().contextEvidence()).isEmpty();
            assertThat(brief.memoryUpdates()).hasSize(1);
        }
    }
    @Test void disablingPlannerStillLoadsBusinessEvidenceAndSourceFailureCannotMasqueradeAsDiscovery() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var input = SalesCoreTest.input("GENERIC", "Giá gói toàn thân?");
        try (var core = new SalesTurnOrchestrator(SalesCoreTest.fake(() -> SalesCoreTest.answer("Toàn thân 399k / 60 phút.", "ANSWER",
                "[{\"sourceId\":\"e1\",\"quote\":\"Toàn thân: 399k / 60 phút.\"}]")), new IndustryProfileRegistry(), 5000)) {
            var result = core.run(SalesConversationPlanner.conversationOnly(input), () -> { calls.incrementAndGet(); return input; });
            assertThat(result.fallback()).isFalse();
            assertThat(calls.get()).isEqualTo(1);
        }
        try (var core = new SalesTurnOrchestrator(SalesCoreTest.fake(() -> SalesConsultationPolicyTest.plan("CONSULTATION", true, null)),
                new IndustryProfileRegistry(), 5000)) {
            core.setPlanningEnabled(true);
            var result = core.run(input, () -> { throw new IllegalStateException("Source offline"); });
            assertThat(result.fallback()).isTrue();
            assertThat(result.handoff()).isFalse();
            assertThat(result.violations()).doesNotContain("PLAN_INVALID_CONVERSATION_ONLY");
            assertThat(result.attempts()).isEqualTo(1);
        }
    }
    @Test void listeningReusesPlannerDraftWithIndependentReviewAndDoesNotFetchCatalog() {
        LlmClient client = new LlmClient() {
            public String name() { return "planner-fixture"; }
            public String complete(String p) {
                if (p.startsWith("SALES_REVIEW")) return "{\"verdict\":\"PASS\",\"issues\":[],\"feedback\":\"\"}";
                assertThat(p).startsWith("SALES_PLAN").doesNotContain("\"evidence\":", "evaluationScenarios");
                return SalesConsultationPolicyTest.plan("DISCOVERY", false, "Em chào mình, mình muốn thư giãn vùng nào ạ?");
            }
        };
        try (var core = new SalesTurnOrchestrator(client, new IndustryProfileRegistry(), 5000)) {
            core.setPlanningEnabled(true);
            var input = SalesCoreTest.input("BEAUTY_SPA", "Giá");
            var result = core.runPaced(input, () -> { throw new AssertionError("Discovery must not fetch business evidence"); });
            assertThat(result.fallback()).isFalse();
            assertThat(result.attempts()).isEqualTo(2);
            assertThat(result.violations()).doesNotContain("CONVERSATION_FAST_PATH");
            assertThat(result.reviewedMemory()).isEqualTo(input.memory());
            assertThat(result.decision().followUp()).isNull();
        }
    }
    @Test void malformedBriefPreservesOriginalVerifiedEvidenceForWriter() {
        LlmClient client = new LlmClient() {
            public String name() { return "planner-fixture"; }
            public String complete(String p) {
                if (p.startsWith("SALES_PLAN")) return "{\"needsBusinessEvidence\":false}";
                if (p.startsWith("SALES_REVIEW")) return "{\"verdict\":\"PASS\",\"issues\":[],\"feedback\":\"\"}";
                assertThat(p).contains("\"id\":\"e1\"");
                return SalesCoreTest.answer("Em chào mình, mình đang cần tư vấn gì ạ?", "CLARIFY", "[]");
            }
        };
        try (var core = new SalesTurnOrchestrator(client, new IndustryProfileRegistry(), 5000)) {
            core.setPlanningEnabled(true);
            var result = core.runPaced(SalesCoreTest.input("GENERIC", "Giá"));
            assertThat(result.fallback()).isFalse();
            assertThat(result.violations()).contains("PLAN_INVALID_EVIDENCE_PRESERVED");
            assertThat(result.decision().followUp()).isNull();
        }
    }
    @Test void typedConsultationKeepsEvidenceEvenWhenLegacyHintDisagrees() {
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        LlmClient client = new LlmClient() {
            public String name() { return "context-expansion-fixture"; }
            public String complete(String p) {
                if (p.startsWith("SALES_PLAN")) return SalesConsultationPolicyTest.plan("CONSULTATION", false, null);
                if (p.startsWith("SALES_REVIEW")) return "{\"verdict\":\"PASS\",\"issues\":[],\"feedback\":\"\"}";
                writes.incrementAndGet();
                assertThat(p).contains("\"id\":\"e1\"");
                return SalesContinuationTest.QUOTE.replace(",\"followUp\":{\"aim\":\"Làm rõ điều khách coi trọng\"}", "");
            }
        };
        try (var core = new SalesTurnOrchestrator(client, new IndustryProfileRegistry(), 5000)) {
            core.setPlanningEnabled(true);
            var result = core.runPaced(SalesCoreTest.input("GENERIC", "Hà Nội"));
            assertThat(result.fallback()).isFalse();
            assertThat(result.violations()).doesNotContain("PLAN_CONTEXT_EXPANDED");
            assertThat(writes.get()).isEqualTo(1);
            assertThat(result.repairCount()).isZero();
        }
    }
}
