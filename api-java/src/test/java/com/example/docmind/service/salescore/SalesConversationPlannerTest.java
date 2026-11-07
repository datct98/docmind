package com.example.docmind.service.salescore;

import com.example.docmind.service.llm.LlmClient;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class SalesConversationPlannerTest {
    @Test void nextDecisionIsChosenWithoutCatalogAndWriterCanUseConversationOnly() {
        AtomicBoolean planned = new AtomicBoolean();
        LlmClient client = new LlmClient() {
            public String name() { return "planner-fixture"; }
            public String complete(String p) {
                if (p.startsWith("SALES_PLAN")) {
                    assertThat(p).doesNotContain("Bảng giá", "evidence\":");
                    planned.set(true);
                    return "{\"latestContribution\":\"Phản đối chưa rõ nguyên nhân\",\"nextDecision\":\"Làm rõ điều chưa phù hợp\",\"reason\":\"Không muốn lặp lý lẽ giá cũ\",\"needsBusinessEvidence\":false}";
                }
                if (p.startsWith("SALES_REVIEW")) return "{\"verdict\":\"PASS\",\"issues\":[],\"feedback\":\"\"}";
                assertThat(planned).isTrue();
                assertThat(p).contains("CONVERSATION_BRIEF_JSON", "\"evidence\":[]").doesNotContain("\"id\":\"e1\"");
                return SalesCoreTest.answer("Điều gì ở phương án vừa rồi khiến mình chưa thấy phù hợp?", "CLARIFY", "[]");
            }
        };
        try (var core = new SalesTurnOrchestrator(client, new IndustryProfileRegistry(), 5000)) {
            core.setPlanningEnabled(true);
            var result = core.runPaced(SalesCoreTest.input("GENERIC", "Đắt"));
            assertThat(result.fallback()).isFalse();
            assertThat(result.attempts()).isEqualTo(3);
            assertThat(result.texts().getFirst()).doesNotContain("399", "239");
        }
    }
    @Test void malformedBriefDoesNotDiscardEvidenceOrBecomeAuthority() {
        LlmClient client = new LlmClient() {
            public String name() { return "planner-fixture"; }
            public String complete(String p) {
                if (p.startsWith("SALES_PLAN")) return "{\"needsBusinessEvidence\":false}";
                if (p.startsWith("SALES_REVIEW")) return "{\"verdict\":\"PASS\",\"issues\":[],\"feedback\":\"\"}";
                assertThat(p).contains("\"id\":\"e1\"");
                return SalesContinuationTest.QUOTE;
            }
        };
        try (var core = new SalesTurnOrchestrator(client, new IndustryProfileRegistry(), 5000)) {
            core.setPlanningEnabled(true);
            var result = core.runPaced(SalesCoreTest.input("GENERIC", "Giá"));
            assertThat(result.fallback()).isFalse();
            assertThat(result.violations()).contains("PLAN_INVALID_USING_FULL_CONTEXT");
        }
    }
    @Test void missingEvidenceInCompactDraftCanBeRecoveredFromOriginalContext() {
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        LlmClient client = new LlmClient() {
            public String name() { return "context-expansion-fixture"; }
            public String complete(String p) {
                if (p.startsWith("SALES_PLAN")) return "{\"latestContribution\":\"Bổ sung địa điểm\",\"nextDecision\":\"Ghi nhận thông tin\",\"reason\":\"Nối tiếp cuộc trao đổi\",\"needsBusinessEvidence\":false}";
                if (p.startsWith("SALES_REVIEW")) return "{\"verdict\":\"PASS\",\"issues\":[],\"feedback\":\"\"}";
                if (writes.incrementAndGet() == 1) {
                    assertThat(p).doesNotContain("\"id\":\"e1\"");
                    return SalesCoreTest.answer("Toàn thân 399k / 60 phút.", "ANSWER", "[]");
                }
                assertThat(p).contains("\"id\":\"e1\"", "Full original business evidence is now available");
                return SalesContinuationTest.QUOTE;
            }
        };
        try (var core = new SalesTurnOrchestrator(client, new IndustryProfileRegistry(), 5000)) {
            core.setPlanningEnabled(true);
            var result = core.runPaced(SalesCoreTest.input("GENERIC", "Hà Nội"));
            assertThat(result.fallback()).isFalse();
            assertThat(result.violations()).contains("PLAN_CONTEXT_EXPANDED");
            assertThat(result.repairCount()).isEqualTo(1);
            assertThat(result.texts()).containsExactly("Toàn thân 399k / 60 phút.");
        }
    }
}
