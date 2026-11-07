package com.example.docmind.service.salescore;

import com.example.docmind.service.llm.LlmClient;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.example.docmind.service.salescore.SalesContracts.*;
import static org.assertj.core.api.Assertions.*;

class SalesUnifiedConsultationTest {
    private static final String DRAFT = """
        {"decision":{"understanding":{"customerGoal":"giá","knownFacts":[],"uncertainties":[],"obstacleHypothesis":"chưa rõ"},
        "strategy":{"action":"ANSWER","reason":"Đáp giá được hỏi","advance":"Có giá để cân nhắc"},
        "bubbles":[{"text":"Chào mình, giá tham khảo tại khu A là 240k, chưa thuế.","evidence":[{"sourceId":"current","quote":"Khu A: 240k, chưa thuế."}]}],
        "handoffSummary":"","calculations":[],"followUp":null},"contextEvidence":{},"journey":"invalid optional shape"}
        """;
    private TurnInput input() { return new TurnInput("page-a", "Example", "GENERIC", "em", "mình", "ấm và gọn",
            List.of(), "Giá", List.of(new Evidence("current", "Current scoped source", "Khu A: 240k, chưa thuế.")), List.of(), "", false); }

    @Test void decisionAndWordingUseOneCallButStillRequireIndependentReview() {
        var calls = new AtomicInteger(); var prompts = new ArrayList<String>();
        LlmClient client = new LlmClient() {
            public String name() { return "fixture"; }
            public String complete(String prompt) {
                prompts.add(prompt);
                return calls.incrementAndGet() == 1 ? DRAFT : """
                    {"replyReview":{"verdict":"PASS","issues":[],"feedback":"","severity":"QUALITY"},"memoryReview":{"approved":true,"issues":[]}}
                    """;
            }
        };
        try (var core = new SalesTurnOrchestrator(client, new IndustryProfileRegistry(), 5000)) {
            core.setGoalFirstEnabled(true); core.setPlanningEnabled(true);
            var result = core.run(input());
            assertThat(result.fallback()).isFalse();
            assertThat(result.attempts()).isEqualTo(2);
            assertThat(prompts.getFirst()).startsWith("SALES_CONSULT");
            assertThat(prompts.getLast()).startsWith("SALES_REVIEW");
            assertThat(result.violations()).contains("MEMORY_PROPOSAL_PARSE_REJECTED");
        }
    }
    @Test void reviewerMustBlockAClaimEvenWhenTheDraftCopiesAValidCitation() {
        LlmClient client = new LlmClient() {
            public String name() { return "fixture"; }
            public String complete(String prompt) { return prompt.startsWith("SALES_REVIEW") ? """
            {"replyReview":{"verdict":"REVISE","issues":["Wrong branch interpretation"],"feedback":"Correct scope","severity":"BLOCKING"},"memoryReview":{"approved":true,"issues":[]}}
            """ : DRAFT; }
        };
        try (var core = new SalesTurnOrchestrator(client, new IndustryProfileRegistry(), 5000)) {
            core.setGoalFirstEnabled(true);
            var result = core.run(input());
            assertThat(result.fallback()).isTrue();
            assertThat(result.texts()).noneMatch(t -> t.contains("240"));
            assertThat(result.violations()).contains("Wrong branch interpretation");
        }
    }
    @Test void failedQualityRewriteRetainsOnlyThePreviouslyCheckedReply() {
        var calls = new AtomicInteger();
        LlmClient client = new LlmClient() {
            public String name() { return "quality-repair-fixture"; }
            public String complete(String prompt) {
                return switch (calls.incrementAndGet()) {
                    case 1 -> DRAFT;
                    case 2 -> """
                        {"replyReview":{"verdict":"REVISE","issues":["Too verbose"],"feedback":"Be concise","severity":"QUALITY"},"memoryReview":{"approved":true,"issues":[]}}
                        """;
                    default -> throw new IllegalStateException("rewrite provider unavailable");
                };
            }
        };
        try (var core = new SalesTurnOrchestrator(client, new IndustryProfileRegistry(), 45000)) {
            core.setGoalFirstEnabled(true);
            var result = core.run(input());
            assertThat(result.fallback()).isFalse();
            assertThat(result.handoff()).isFalse();
            assertThat(result.attempts()).isEqualTo(3);
            assertThat(result.texts()).containsExactly("Chào mình, giá tham khảo tại khu A là 240k, chưa thuế.");
            assertThat(result.violations()).contains("QUALITY_REPAIR_ATTEMPTED", "CHECKED_REPLY_RETAINED_AFTER_QUALITY_REPAIR");
        }
    }
    @Test void qualityFeedbackActuallyChangesTheDeliveredReplyAfterIndependentRecheck() {
        var generation = new AtomicInteger();
        LlmClient client = new LlmClient() {
            public String name() { return "quality-improvement-fixture"; }
            public String complete(String prompt) {
                if (prompt.startsWith("SALES_REVIEW")) return generation.get() == 1 ? """
                    {"replyReview":{"verdict":"REVISE","issues":["Wordy"],"feedback":"Keep relevant price only","severity":"QUALITY"},"memoryReview":{"approved":true,"issues":[]}}
                    """ : """
                    {"replyReview":{"verdict":"PASS","issues":[],"feedback":"","severity":"QUALITY"},"memoryReview":{"approved":true,"issues":[]}}
                    """;
                return generation.incrementAndGet() == 1 ? DRAFT : DRAFT.replace("Chào mình, giá tham khảo tại khu A là 240k, chưa thuế.", "Khu A: 240k, chưa thuế.");
            }
        };
        try (var core = new SalesTurnOrchestrator(client, new IndustryProfileRegistry(), 45000)) {
            core.setGoalFirstEnabled(true);
            var result = core.run(input());
            assertThat(result.fallback()).isFalse();
            assertThat(result.attempts()).isEqualTo(4);
            assertThat(result.texts()).containsExactly("Khu A: 240k, chưa thuế.");
        }
    }
    @Test void malformedPlanCannotTurnAPauseIntoAQuestionOrHandoff() {
        var pause = DRAFT.replace("\"ANSWER\"", "\"PAUSE\"");
        var parsed = SalesConsultationDraft.parse(pause);
        var input = input().withInteractionPlan(parsed.brief().contract(new IndustryProfileRegistry().load("GENERIC")));
        assertThat(new SalesDecisionValidator().validate(parsed.decision(), input)).contains("CLOSE_MUST_RESPECT_CUSTOMER_STOP");
    }
    @Test void citationsBindToCurrentScopedSourceAndCannotInventAnId() {
        var changed = new TurnInput("page-a", "Example", "GENERIC", "em", "mình", "gọn", List.of(), "Giá",
                List.of(new Evidence("current", "Khu B", "Khu B: 730k, đã thuế.")), List.of(), "", false);
        var draft = SalesConsultationDraft.parse(DRAFT, changed);
        assertThat(draft.decision().bubbles().getFirst().evidence().getFirst().quote()).contains("730k");
        assertThat(new SalesDecisionValidator().validate(draft.decision(), changed)).contains("UNSUPPORTED_NUMBER");
        assertThatThrownBy(() -> SalesConsultationDraft.parse(DRAFT.replace("\"current\"", "\"foreign-page\""), changed))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown scoped source");
    }
    @Test void customerCorrectionSelectsWholeEvidenceWithoutAssistantAnchoring() {
        var corrected = new TurnInput("p", "Shop", "GENERIC", "em", "mình", "gọn",
                List.of(new Message("user", "Bích Câu"), new Message("assistant", "Bích Câu Bích Câu")),
                "Sửa lại Long Biên", List.of(new Evidence("old", "Bích Câu", "Giá 200k chưa thuế"),
                new Evidence("new", "Long Biên", "Giá 700k đã thuế")), List.of(), "", false);
        var selection = SalesEvidenceWindow.select(corrected, 30);
        assertThat(selection.evidence()).extracting(Evidence::id).containsExactly("new");
        assertThat(selection.evidence().getFirst().text()).isEqualTo("Giá 700k đã thuế");
        assertThat(selection.truncated()).isTrue();
    }
}
