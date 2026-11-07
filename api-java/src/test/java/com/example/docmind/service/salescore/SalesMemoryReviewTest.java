package com.example.docmind.service.salescore;

import com.example.docmind.service.llm.LlmClient;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class SalesMemoryReviewTest {
    @Test void rejectedMemoryDoesNotTriggerUnrepairableReplyLoop() {
        var client = new LlmClient() {
            public String name() { return "separated-review-fixture"; }
            public String complete(String prompt) {
                if (prompt.startsWith("SALES_PLAN")) return """
                    {"latestContribution":"Bổ sung địa điểm","nextDecision":"Ghi nhận","reason":"Khách trả lời","needsBusinessEvidence":false,
                     "memoryUpdates":[{"key":"location","value":"Hà Nội","customerQuote":"Hà Nội","sourceTurn":1,"remove":false}]}
                    """;
                if (prompt.startsWith("SALES_REVIEW")) return """
                    {"replyReview":{"verdict":"PASS","issues":[],"feedback":"","severity":"QUALITY"},
                     "memoryReview":{"approved":false,"issues":["Uncertain extraction; retain source only"]}}
                    """;
                return SalesCoreTest.answer("Em ghi nhận điều mình vừa chia sẻ.", "ANSWER", "[]");
            }
        };
        try (var core = new SalesTurnOrchestrator(client, new IndustryProfileRegistry(), 5000)) {
            core.setPlanningEnabled(true);
            var memory = SalesMemory.advance(SalesMemory.View.empty(), "Hà Nội", false);
            var result = core.run(SalesCoreTest.input("GENERIC", "Hà Nội").withMemory(memory));
            assertThat(result.fallback()).isFalse();
            assertThat(result.attempts()).isEqualTo(3);
            assertThat(result.reviewedMemory()).isEqualTo(memory);
            assertThat(result.violations()).contains("MEMORY_SEMANTIC_REJECTED");
        }
    }
    @Test void memoryApprovalCannotHideBlockingReplyError() {
        var review = SalesSemanticReview.parse("""
            {"replyReview":{"verdict":"REVISE","issues":["Invented availability"],"feedback":"Remove unverified availability","severity":"BLOCKING"},
             "memoryReview":{"approved":true,"issues":[]}}
            """);
        assertThat(review.verdict()).isEqualTo(SalesSemanticReview.Verdict.REVISE);
        assertThat(review.severity()).isEqualTo(SalesSemanticReview.Severity.BLOCKING);
        assertThat(review.memoryApproved()).isTrue();
    }
    @Test void onlyIndependentlyApprovedMemoryIsReturnedForPersistence() {
        for (String approval : List.of("true", "false", "null")) {
            LlmClient client = new LlmClient() {
                public String name() { return "memory-review-fixture"; }
                public String complete(String prompt) {
                    if (prompt.startsWith("SALES_PLAN")) return """
                        {"latestContribution":"Bổ sung chi nhánh","nextDecision":"Dùng địa điểm đã biết","reason":"Khách vừa trả lời","needsBusinessEvidence":false,
                        "memoryUpdates":[{"key":"location","value":"Hà Nội","customerQuote":"Hà Nội","sourceTurn":1,"remove":false}]}
                        """;
                    if (prompt.startsWith("SALES_REVIEW")) {
                        assertThat(prompt).contains("PROPOSED_CUSTOMER_MEMORY_UPDATES_JSON", "Hà Nội");
                        return "{\"verdict\":\"PASS\",\"issues\":[],\"feedback\":\"\",\"memoryApproved\":" + approval + "}";
                    }
                    assertThat(prompt).contains("customerMemory");
                    return SalesCoreTest.answer("Mình muốn ưu tiên điều gì ở buổi này?", "CLARIFY", "[]");
                }
            };
            try (var core = new SalesTurnOrchestrator(client, new IndustryProfileRegistry(), 5000)) {
                core.setPlanningEnabled(true);
                var input = SalesCoreTest.input("BEAUTY_SPA", "Hà Nội").withMemory(SalesMemory.advance(SalesMemory.View.empty(), "Hà Nội", false));
                var result = core.run(input);
                assertThat(result.fallback()).isFalse();
                assertThat(result.reviewedMemory().facts()).hasSize(approval.equals("true") ? 1 : 0);
            }
        }
    }
    @Test void oldRememberedFactsRemainAvailableToWriterValidatorAndContinuation() {
        var view = new SalesMemory.View(50, List.of(new SalesMemory.Fact("service_scope", "cổ vai gáy chân tay", "cổ vai gáy chân tay", 1)), List.of(), List.of(), true);
        var base = SalesCoreTest.input("BEAUTY_SPA", "Mình vẫn cần như vậy");
        var input = new SalesContracts.TurnInput(base.pageId(), base.businessName(), base.industryCode(), base.selfPronoun(), base.customerPronoun(),
                base.voice(), List.of(), base.message(), base.evidence(), base.businessPolicies(), "", true, view);
        var decision = new tools.jackson.databind.ObjectMapper().readValue(SalesCoreTest.answer("Mình cần chăm sóc đủ các vùng đã nêu.", "ANSWER", "[]"), SalesContracts.TurnDecision.class);
        assertThat(new SalesDecisionValidator().validate(decision, input)).isEmpty();
        assertThat(SalesConversationPlanner.conversationOnly(input).memory()).isEqualTo(view);
        assertThat(SalesPromptBuilder.build(input, new IndustryProfileRegistry().load("BEAUTY_SPA"))).contains("cổ vai gáy chân tay");
    }
    @Test void bothIndustriesUseVersionedMinimalContracts() {
        var profiles = new IndustryProfileRegistry();
        for (String code : List.of("BEAUTY_SPA", "MOTORBIKE_RENTAL")) {
            var profile = profiles.load(code);
            assertThat(profile.schemaVersion()).isEqualTo(2);
            assertThat(profile.customerFields()).isNotEmpty();
            assertThat(profile.actionRequirements()).isNotEmpty();
            assertThat(profile.evaluationScenarios()).isNotEmpty();
        }
    }
    @Test void continuationCannotBeManufacturedWhenPlanHasNoQuestionOrPurchaseSignal() {
        var plan = new SalesContracts.InteractionPlan("Trả lời giá", "Biết giá", "NONE", "UNKNOWN", "NONE");
        var input = SalesCoreTest.input("GENERIC", "Giá").withInteractionPlan(plan);
        var decision = new tools.jackson.databind.ObjectMapper().readValue(SalesContinuationTest.QUOTE, SalesContracts.TurnDecision.class);
        assertThat(new SalesDecisionValidator().validate(decision, input)).contains("FOLLOWUP_WITHOUT_DECISION_PURPOSE");
        var continuedInput = SalesConversationPlanner.conversationOnly(input);
        assertThat(continuedInput.interactionPlan()).isEqualTo(plan);
        assertThat(SalesPromptBuilder.build(input, new IndustryProfileRegistry().load("GENERIC"))).contains("FOLLOWUP_PERMITTED=false");
    }
    @Test void legacyPackRemainsReadableButUnknownSchemaVersionFailsVisibly() throws Exception {
        var directory = java.nio.file.Files.createTempDirectory("sales-profile-test-");
        var path = directory.resolve("TEST.json");
        try {
            java.nio.file.Files.writeString(path, "{\"code\":\"TEST\",\"decisionCriteria\":[],\"discoveryHints\":[],\"boundaries\":[]}");
            var registry = new IndustryProfileRegistry(directory.toString());
            assertThat(registry.load("TEST").schemaVersion()).isEqualTo(1);
            java.nio.file.Files.writeString(path, "{\"code\":\"TEST\",\"schemaVersion\":99,\"decisionCriteria\":[],\"discoveryHints\":[],\"boundaries\":[]}");
            assertThatThrownBy(() -> registry.load("TEST")).isInstanceOf(IllegalArgumentException.class);
        } finally {
            java.nio.file.Files.deleteIfExists(path);
            java.nio.file.Files.deleteIfExists(directory);
        }
    }
}
