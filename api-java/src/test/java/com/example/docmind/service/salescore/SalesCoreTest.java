package com.example.docmind.service.salescore;

import com.example.docmind.service.llm.LlmClient;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static com.example.docmind.service.salescore.SalesContracts.*;

class SalesCoreTest {
    static TurnInput input(String industry, String message) {
        return new TurnInput("page-a", "Shop", industry, "em", "mình", "ấm áp, ngắn gọn",
                List.of(new Message("user", "cổ vai gáy chân tay"), new Message("assistant", "Gói toàn thân 399k."),
                        new Message("user", "Đắt"), new Message("assistant", "Có gói 239k, chỉ cổ vai gáy.")),
                message, List.of(new Evidence("e1", "Bảng giá", "Toàn thân: 399k / 60 phút. Cổ vai gáy: 239k / 60 phút.")),
                List.of(), "", false);
    }
    static String answer(String text, String action, String citations) {
        return """
            {"understanding":{"customerGoal":"thư giãn nhiều vùng","knownFacts":[{"value":"cổ vai gáy chân tay","customerQuote":"cổ vai gáy chân tay"}],
            "uncertainties":["ngân sách"],"obstacleHypothesis":"chưa rõ là vượt ngân sách hay chưa thấy phù hợp"},
            "strategy":{"action":"%s","reason":"cần hiểu giới hạn chi phí","advance":"làm rõ ngân sách"},
            "bubbles":[{"text":"%s","evidence":%s}],"handoffSummary":""}
            """.formatted(action, text, citations);
    }
    static LlmClient fake(java.util.function.Supplier<String> supplier) {
        return new LlmClient() {
            public String complete(String p) {return p.startsWith("SALES_REVIEW") ? "{\"verdict\":\"PASS\",\"issues\":[],\"feedback\":\"\"}" : supplier.get();}
            public String name(){return "test";}
        };
    }
    @Test void repeatedObjectionPreservesUsefulQuestionAndDoesNotForceExit() {
        var expected = "Mình dự tính khoảng bao nhiêu cho lần này để em đối chiếu đúng nhu cầu toàn thân?";
        try (var core = new SalesTurnOrchestrator(fake(() -> answer(expected, "CLARIFY", "[]")), new IndustryProfileRegistry(), 5000)) {
            var result = core.run(input("BEAUTY_SPA", "Đắt"));
            assertThat(result.texts()).containsExactly(expected);
            assertThat(result.handoff()).isFalse();
            assertThat(result.attempts()).isEqualTo(2);
        }
    }
    @Test void invalidPriceAndInventedEvidenceAreRejectedThenRepaired() {
        var calls = new AtomicInteger();
        var bad = answer("Gói toàn thân 199k nhé.", "RECOMMEND", "[{\"sourceId\":\"e1\",\"quote\":\"Toàn thân: 199k\"}]");
        var good = answer("Gói toàn thân 399k / 60 phút.", "RECOMMEND", "[{\"sourceId\":\"e1\",\"quote\":\"Toàn thân: 399k / 60 phút.\"}]");
        try (var core = new SalesTurnOrchestrator(fake(() -> calls.getAndIncrement() == 0 ? bad : good), new IndustryProfileRegistry(), 5000)) {
            var result = core.run(input("BEAUTY_SPA", "Giá?"));
            assertThat(result.texts()).containsExactly("Gói toàn thân 399k / 60 phút.");
            assertThat(result.attempts()).isEqualTo(3);
            assertThat(result.violations()).contains("INVALID_EVIDENCE_QUOTE");
        }
    }
    @Test void inventedCustomerFactCannotBecomeMemory() {
        var raw = answer("Em kiểm tra thêm nhé.", "CLARIFY", "[]").replace("\"customerQuote\":\"cổ vai gáy chân tay\"", "\"customerQuote\":\"khách đau ba tháng\"");
        try (var core = new SalesTurnOrchestrator(fake(() -> raw), new IndustryProfileRegistry(), 5000)) {
            assertThat(core.run(input("BEAUTY_SPA", "Đắt")).violations()).contains("UNSUPPORTED_CUSTOMER_FACT");
        }
    }
    @Test void malformedOutputAndUnauthorizedBookingFailClosedWithBoundedCalls() {
        for (String raw : List.of("not json", answer("Em đã xác nhận đặt lịch cho mình.", "CONFIRM_BOOKING", "[]"))) {
            var calls = new AtomicInteger();
            try (var core = new SalesTurnOrchestrator(fake(() -> { calls.incrementAndGet(); return raw; }), new IndustryProfileRegistry(), 5000)) {
                var result = core.run(input("BEAUTY_SPA", "Chốt nhé"));
                assertThat(result.handoff()).isFalse();
                assertThat(result.fallback()).isTrue();
                assertThat(calls.get()).isEqualTo(3); // initial, repair, then constrained clarification recovery
                assertThat(String.join(" ", result.texts())).doesNotContain("đã xác nhận");
            }
        }
    }
    @Test void deadlineStopsWaitingAndDoesNotRetryTimedOutProvider() {
        try (var core = new SalesTurnOrchestrator(fake(() -> {try {Thread.sleep(1000);} catch (InterruptedException e) {Thread.currentThread().interrupt();} return "{}";}), new IndustryProfileRegistry(), 50)) {
            long start = System.nanoTime();
            var result = core.run(input("BEAUTY_SPA", "Chào"));
            assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(800);
            assertThat(result.attempts()).isEqualTo(1);
            assertThat(result.violations()).contains("TURN_DEADLINE");
        }
    }
    @Test void samePromptBuilderUsesSeparateIndustryDataAndUnknownIndustryFailsVisibly() {
        var registry = new IndustryProfileRegistry();
        var rental = SalesPromptBuilder.build(input("VEHICLE_RENTAL", "thuê hai ngày"), registry.load("VEHICLE_RENTAL"));
        assertThat(rental).contains("VEHICLE_RENTAL", "giấy tờ").doesNotContain("rèm riêng", "SOFT_FEELING_CHECK", "RESPECTFUL_DEESCALATION");
        assertThatThrownBy(() -> registry.load("UNLISTED_INDUSTRY")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.load("../private")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void priceInCustomerMessageDoesNotAuthorizeInventedOfferPrice() {
        // A stated budget is not a business price source.
        String raw = answer("Gói toàn thân giá 199k.", "RECOMMEND", "[]");
        try (var core = new SalesTurnOrchestrator(fake(() -> raw), new IndustryProfileRegistry(), 5000)) {
            assertThat(core.run(input("BEAUTY_SPA", "Có gói 199k không?")).fallback()).isTrue();
        }
        // Even an unformatted numeric budget cannot be promoted to a business price.
        try (var core = new SalesTurnOrchestrator(fake(() -> answer("Gói toàn thân 199000 VND.", "ANSWER", "[]")), new IndustryProfileRegistry(), 5000)) {
            assertThat(core.run(input("BEAUTY_SPA", "Ngân sách 199000")).fallback()).isTrue();
        }
    }
    @Test void confirmationIsBlockedEvenWhenModelLabelsItAnswer() {
        String raw = answer("Em đã giữ chỗ cho mình.", "ANSWER", "[]");
        try (var core = new SalesTurnOrchestrator(fake(() -> raw), new IndustryProfileRegistry(), 5000)) {
            var result = core.run(input("BEAUTY_SPA", "Đặt giúp"));
            assertThat(result.fallback()).isTrue();
            assertThat(result.violations()).contains("UNAUTHORIZED_TRANSACTION_CLAIM");
        }
    }
    @Test void semanticReviewRequiresRepairOfWrongScopeAndPreservesUsefulRepair() {
        var generations = new AtomicInteger();
        LlmClient provider = new LlmClient() {
            public String name() { return "test"; }
            public String complete(String prompt) {
                if (prompt.startsWith("SALES_REVIEW")) return generations.get() == 1
                        ? "{\"verdict\":\"REVISE\",\"issues\":[\"NEED_MISMATCH\"],\"feedback\":\"Phương án giảm phạm vi nhưng lời tư vấn chưa nêu đánh đổi.\"}"
                        : "{\"verdict\":\"PASS\",\"issues\":[],\"feedback\":\"\"}";
                return generations.incrementAndGet() == 1
                        ? answer("Gói cổ vai gáy sẽ đáp ứng hết nhu cầu của mình.", "RECOMMEND", "[]")
                        : answer("Phương án cổ vai gáy chưa đủ phần chân tay mình cần. Mình dự tính khoảng bao nhiêu cho lần này?", "CLARIFY", "[]");
            }
        };
        try (var core = new SalesTurnOrchestrator(provider, new IndustryProfileRegistry(), 5000)) {
            var result = core.run(input("BEAUTY_SPA", "Đắt"));
            assertThat(result.fallback()).isFalse();
            assertThat(result.texts().getFirst()).contains("chưa đủ phần chân tay", "?");
            assertThat(result.violations()).contains("NEED_MISMATCH");
            assertThat(result.attempts()).isEqualTo(4);
        }
    }
    @Test void verifiedArithmeticAllowsTotalPriceButRejectsWrongResultAndUnprovenOperand() {
        String raw = answer("Tạm tính 798k cho hai lượt.", "ANSWER", "[{\"sourceId\":\"e1\",\"quote\":\"Toàn thân: 399k / 60 phút.\"}]")
                .replace("\"handoffSummary\":\"\"", """
                    "calculations":[{"operation":"MULTIPLY","operands":[
                    {"value":399000,"sourceId":"e1","quote":"Toàn thân: 399k / 60 phút."},
                    {"value":2,"sourceId":"CUSTOMER","quote":"2 lượt"}],"result":798000}],"handoffSummary":""
                    """);
        for (boolean valid : List.of(true, false)) {
            String candidate = valid ? raw : raw.replace("\"result\":798000", "\"result\":799000");
            try (var core = new SalesTurnOrchestrator(fake(() -> candidate), new IndustryProfileRegistry(), 5000)) {
                var result = core.run(input("BEAUTY_SPA", "2 lượt"));
                assertThat(result.fallback()).isEqualTo(!valid);
            }
        }
    }
    @Test void configuredFallbackRepairsSemanticOrContractFailureWithoutIndustryRouting() {
        var fallbackCalls = new AtomicInteger();
        var primary = fake(() -> "malformed");
        var fallback = fake(() -> { fallbackCalls.incrementAndGet(); return answer("Mình dự tính khoảng bao nhiêu ạ?", "CLARIFY", "[]"); });
        var routed = new com.example.docmind.service.llm.ResilientFallbackLlmClient(primary, fallback);
        try (var core = new SalesTurnOrchestrator(routed, new IndustryProfileRegistry(), 5000)) {
            var result = core.run(input("BEAUTY_SPA", "Đắt"));
            assertThat(result.fallback()).isFalse();
            assertThat(fallbackCalls).hasValue(1);
            assertThat(result.repairCount()).isEqualTo(1);
        }
    }
    @Test void onlyStyleWarningsMaySurviveRepairWhileFactualErrorsRemainBlocking() {
        for (String severity : List.of("QUALITY", "BLOCKING")) {
            LlmClient provider = new LlmClient() {
                public String name() { return "test"; }
                public String complete(String prompt) {
                    if (prompt.startsWith("SALES_REVIEW")) return "{\"verdict\":\"REVISE\",\"issues\":[\"REVIEW_FINDING\"],\"feedback\":\"Cần sửa.\",\"severity\":\"" + severity + "\"}";
                    return answer("Mình dự tính khoảng bao nhiêu ạ?", "CLARIFY", "[]");
                }
            };
            try (var core = new SalesTurnOrchestrator(provider, new IndustryProfileRegistry(), 5000)) {
                var result = core.run(input("BEAUTY_SPA", "Đắt"));
                assertThat(result.fallback()).isEqualTo(severity.equals("BLOCKING"));
                assertThat(result.attempts()).isEqualTo(severity.equals("BLOCKING") ? 6 : 4);
                if (severity.equals("QUALITY")) assertThat(result.violations()).contains("QUALITY_REVIEW_WARNING");
            }
        }
    }
    @Test void customerQuantityIsPreservedAcrossRecommendationsButMoneyIsNotBusinessAuthority() {
        String cited = "[{\"sourceId\":\"e1\",\"quote\":\"Toàn thân: 399k / 60 phút.\"}]";
        try (var core = new SalesTurnOrchestrator(fake(() -> answer("Mình đi 2 người, gói toàn thân 399k mỗi người.", "RECOMMEND", cited)), new IndustryProfileRegistry(), 5000)) {
            assertThat(core.run(input("BEAUTY_SPA", "2 người")).fallback()).isFalse();
        }
        for (String action : List.of("ANSWER", "CLARIFY", "RECOMMEND")) {
            try (var core = new SalesTurnOrchestrator(fake(() -> answer("Gói giá 199k nhé.", action, "[]")), new IndustryProfileRegistry(), 5000)) {
                assertThat(core.run(input("BEAUTY_SPA", "Ngân sách 199k")).fallback()).isTrue();
            }
        }
    }
    @Test void derivedArithmeticUsesVerifiedEarlierResultAndRejectsForwardReference() {
        var difference = new Calculation("SUBTRACT", List.of(new Operand(new java.math.BigDecimal("399000"), "e1", "399k"),
                new Operand(new java.math.BigDecimal("239000"), "e1", "239k")), new java.math.BigDecimal("160000"));
        var total = new Calculation("MULTIPLY", List.of(new Operand(new java.math.BigDecimal("160000"), "CALCULATION:0", ""),
                new Operand(new java.math.BigDecimal("2"), "CUSTOMER", "2 người")), new java.math.BigDecimal("320000"));
        var sources = java.util.Map.of("e1", new Evidence("e1", "prices", "399k 239k"));
        assertThat(GroundedArithmetic.verify(List.of(difference, total), sources, List.of("2 người"))).contains("160000", "320000");
        assertThatThrownBy(() -> GroundedArithmetic.verify(List.of(total, difference), sources, List.of("2 người"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void configurableWriterSelectionDoesNotDependOnIndustry() {
        var primaryCalls = new AtomicInteger();
        var primary = fake(() -> { primaryCalls.incrementAndGet(); return "bad"; });
        var alternate = fake(() -> answer("Mình dự tính khoảng bao nhiêu ạ?", "CLARIFY", "[]"));
        var routed = new com.example.docmind.service.llm.ResilientFallbackLlmClient(primary, alternate);
        try (var core = new SalesTurnOrchestrator(routed, new IndustryProfileRegistry(), 5000)) {
            core.setWriterClient("ALTERNATE");
            assertThat(core.run(input("VEHICLE_RENTAL", "Đắt")).fallback()).isFalse();
            assertThat(primaryCalls).hasValue(0);
            assertThatThrownBy(() -> core.setWriterClient("unknown")).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void citationsAreSharedAcrossBubblesButUncitedPricesStillFail() {
        String base = answer("Gói toàn thân 399k.", "ANSWER", "[{\"sourceId\":\"e1\",\"quote\":\"Toàn thân: 399k / 60 phút.\"}]");
        for (String price : List.of("399k", "199k")) {
            String raw = base.replace("}],\"handoffSummary\"", "},{\"text\":\"Mức " + price + " này có phù hợp không?\",\"evidence\":[]}],\"handoffSummary\"");
            try (var core = new SalesTurnOrchestrator(fake(() -> raw), new IndustryProfileRegistry(), 5000)) {
                assertThat(core.run(input("BEAUTY_SPA", "Giá?")).fallback()).isEqualTo(price.equals("199k"));
            }
        }
    }
    @Test void sourceHeadingIsPartOfEvidenceScopeIncludingBranchNumbers() {
        var original = input("BEAUTY_SPA", "Giá?");
        var scoped = new TurnInput(original.pageId(), original.businessName(), original.industryCode(), "em", "mình", "gọn",
                original.history(), original.message(), List.of(new Evidence("e1", "Chi nhánh Quận 1", "Toàn thân: 399k / 60 phút.")), List.of(), "", false);
        String raw = answer("Tại Quận 1, gói toàn thân là 399k.", "ANSWER", "[{\"sourceId\":\"e1\",\"quote\":\"Toàn thân: 399k / 60 phút.\"}]");
        try (var core = new SalesTurnOrchestrator(fake(() -> raw), new IndustryProfileRegistry(), 5000)) {
            assertThat(core.run(scoped).fallback()).isFalse();
        }
    }
}
