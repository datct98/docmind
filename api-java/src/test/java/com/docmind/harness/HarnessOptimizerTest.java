package com.docmind.harness;

import com.docmind.guardrail.GuardrailPipeline;
import com.example.docmind.controller.HarnessController;
import com.example.docmind.dto.LearnedRuleDto;
import com.example.docmind.dto.bot.CustomerInsightDto;
import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.dto.bot.FanpageBotResponse;
import com.example.docmind.dto.harness.HarnessOptimizeRequest;
import com.example.docmind.dto.harness.HarnessOptimizeResponse;
import com.example.docmind.dto.harness.HarnessRunDto;
import com.example.docmind.entity.HarnessDimension;
import com.example.docmind.entity.HarnessRunEntity;
import com.example.docmind.entity.LearnedRuleEntity;
import com.example.docmind.repository.HarnessRunRepository;
import com.example.docmind.repository.LearnedRuleRepository;
import com.example.docmind.service.bot.CustomerInsightEngine;
import com.example.docmind.service.bot.FanpageBotPromptBuilder;
import com.example.docmind.service.bot.FanpageBotPromptBuilder.BudgetAllocation;
import com.example.docmind.service.bot.FanpageBotService;
import com.example.docmind.service.harness.HarnessOptimizerService;
import com.example.docmind.service.llm.LlmClient;
import com.example.docmind.service.retrieval.RetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Comprehensive Unit and Integration Test Suite for Milestone M3 (Requirement R3):
 * Task-Level Contrastive Propose–Probe–Anonymized Select Recursive Optimizer.
 */
@DisplayName("Harness² Recursive Optimizer Test Suite (Milestone M3)")
class HarnessOptimizerTest {

    private HarnessRunRepository runRepository;
    private LearnedRuleRepository ruleRepository;
    private GuardrailPipeline guardrailPipeline;
    private HarnessOptimizerService optimizerService;
    private HarnessController harnessController;

    private static final String PAGE_ID = "00000000-0000-0000-0000-000000000001";
    private static final String SUBOPTIMAL_BASE_REPLY =
            "Dạ gói Trị Mụn bên em 1.500.000đ, gói Trị Nám 4.500.000đ. Chị Lan Anh có muốn chốt lịch luôn chiều nay không ạ?";
    private static final String OPTIMAL_BASE_REPLY =
            "Dạ chi phí liệu trình bên em rất linh hoạt chỉ từ 650.000đ/buổi tùy theo tình trạng da của Anh, đã bao gồm Bác sĩ soi da 3D miễn phí ạ.\n\n"
            + "Hiện tại Anh đang muốn ưu tiên xử lý mụn viêm hay mụn ẩn trước để em tư vấn phác đồ chuẩn nhất ạ?";

    @BeforeEach
    void setUp() {
        runRepository = mock(HarnessRunRepository.class);
        ruleRepository = mock(LearnedRuleRepository.class);
        guardrailPipeline = new GuardrailPipeline();

        when(runRepository.save(any(HarnessRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(ruleRepository.save(any(LearnedRuleEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        optimizerService = new HarnessOptimizerService(
                runRepository,
                ruleRepository,
                guardrailPipeline,
                null,
                null,
                0.15
        );

        harnessController = new HarnessController(optimizerService);
    }

    // =========================================================================
    // 1. SEEDED RANDOM ANONYMIZED SHUFFLE SELECTION TESTS
    // =========================================================================
    @Nested
    @DisplayName("1. Seeded Random Anonymized Shuffle Selection (Bias Elimination)")
    class SeededShuffleSelectionTests {

        @Test
        @DisplayName("Deterministic seeded shuffle assigns Candidate A and B based on seed without position bias")
        void testSeededShuffleDeterminism() {
            HarnessOptimizeRequest req1 = new HarnessOptimizeRequest(
                    PAGE_ID, "ừ để xem thêm đã", SUBOPTIMAL_BASE_REPLY, "conv-1",
                    List.of(), -20, List.of(), "TEMP", "task-1", 777L
            );
            HarnessOptimizeRequest req2 = new HarnessOptimizeRequest(
                    PAGE_ID, "ừ để xem thêm đã", SUBOPTIMAL_BASE_REPLY, "conv-1",
                    List.of(), -20, List.of(), "TEMP", "task-1", 777L
            );

            HarnessOptimizeResponse resp1 = optimizerService.optimize(req1);
            HarnessOptimizeResponse resp2 = optimizerService.optimize(req2);

            assertThat(resp1.candidateALabel()).isEqualTo(resp2.candidateALabel());
            assertThat(resp1.candidateBLabel()).isEqualTo(resp2.candidateBLabel());
            assertThat(resp1.candidateA()).isEqualTo(resp2.candidateA());
            assertThat(resp1.candidateB()).isEqualTo(resp2.candidateB());
        }

        @Test
        @DisplayName("Inverting seed flips candidate slots between Slot A and Slot B")
        void testInvertingSeedFlipsSlots() {
            // Seed 0L yields true on Random.nextBoolean() (CANDIDATE in A), seed 4096L yields false (BASE in A)
            HarnessOptimizeRequest reqA = new HarnessOptimizeRequest(
                    PAGE_ID, "ừ để xem thêm", SUBOPTIMAL_BASE_REPLY, "conv-1",
                    List.of(), -20, List.of(), "TEMP", "task-1", 0L
            );
            HarnessOptimizeRequest reqB = new HarnessOptimizeRequest(
                    PAGE_ID, "ừ để xem thêm", SUBOPTIMAL_BASE_REPLY, "conv-1",
                    List.of(), -20, List.of(), "TEMP", "task-1", 4096L
            );

            HarnessOptimizeResponse respA = optimizerService.optimize(reqA);
            HarnessOptimizeResponse respB = optimizerService.optimize(reqB);

            assertThat(respA.candidateALabel()).isNotEqualTo(respB.candidateALabel());
            assertThat(respA.candidateBLabel()).isNotEqualTo(respB.candidateBLabel());
            // Both runs agree on the verdict (ADOPTED) regardless of position
            assertThat(respA.verdict()).isEqualTo("ADOPTED");
            assertThat(respB.verdict()).isEqualTo("ADOPTED");
        }

        @Test
        @DisplayName("Unseeded runs across 50 iterations produce balanced 50/50 position distribution")
        void testUnseededBalancedDistribution() {
            int aAsCandidate = 0;
            int bAsCandidate = 0;

            for (int i = 0; i < 50; i++) {
                HarnessOptimizeRequest req = new HarnessOptimizeRequest(
                        PAGE_ID, "nghĩ thêm " + i, SUBOPTIMAL_BASE_REPLY, "conv-" + i,
                        List.of(), -20, List.of(), "TEMP", "task-" + i, null
                );
                HarnessOptimizeResponse resp = optimizerService.optimize(req);
                if ("CANDIDATE".equals(resp.candidateALabel())) {
                    aAsCandidate++;
                } else {
                    bAsCandidate++;
                }
            }

            // Statistical significance: each slot occurs at least 12 times in 50 trials
            assertThat(aAsCandidate).isGreaterThanOrEqualTo(12);
            assertThat(bAsCandidate).isGreaterThanOrEqualTo(12);
        }

        @Test
        @DisplayName("Labels are strictly anonymized without revealing experimental origins")
        void testStrictAnonymization() {
            HarnessOptimizeRequest req = new HarnessOptimizeRequest(
                    PAGE_ID, "ừ để xem", SUBOPTIMAL_BASE_REPLY, -18
            );
            HarnessOptimizeResponse resp = optimizerService.optimize(req);

            assertThat(resp.candidateALabel()).isIn("BASE", "CANDIDATE");
            assertThat(resp.candidateBLabel()).isIn("BASE", "CANDIDATE");
            assertThat(resp.candidateALabel()).isNotEqualTo(resp.candidateBLabel());
            assertThat(resp.winnerCandidate()).isIn("Candidate A", "Candidate B", "KEEP_BASE");
        }
    }

    // =========================================================================
    // 2. KEEP_BASE FALLBACK & ZERO LIBRARY POLLUTION TESTS
    // =========================================================================
    @Nested
    @DisplayName("2. KEEP_BASE Fallback & Zero Library Pollution")
    class KeepBaseFallbackTests {

        @Test
        @DisplayName("When base response is already optimal, returns KEEP_BASE and does not save any rule")
        void testKeepBaseOnOptimalResponse() {
            HarnessOptimizeRequest req = new HarnessOptimizeRequest(
                    PAGE_ID, "giá mụn thế nào em", OPTIMAL_BASE_REPLY, 0
            );

            HarnessOptimizeResponse resp = optimizerService.optimize(req);

            assertThat(resp.verdict()).isEqualTo("KEEP_BASE");
            assertThat(resp.winnerCandidate()).isEqualTo("KEEP_BASE");
            assertThat(resp.winningReply()).isEqualTo(OPTIMAL_BASE_REPLY);
            assertThat(resp.newExperienceRule()).isNull();
            assertThat(resp.deltaScore()).isLessThan(0.15);
            assertThat(resp.rationale()).contains("KEEP_BASE");

            // Zero library pollution in learned_rules
            verify(ruleRepository, never()).save(any(LearnedRuleEntity.class));

            // Audit record IS saved to harness_optimization_runs
            verify(runRepository, times(1)).save(any(HarnessRunEntity.class));
        }

        @Test
        @DisplayName("KEEP_BASE preserves baseline reply text and splits it into bubbles properly")
        void testKeepBasePreservesBaselineText() {
            HarnessOptimizeRequest req = new HarnessOptimizeRequest(
                    PAGE_ID, "tư vấn giúp", OPTIMAL_BASE_REPLY, 0
            );

            HarnessOptimizeResponse resp = optimizerService.optimize(req);

            assertThat(resp.verdict()).isEqualTo("KEEP_BASE");
            assertThat(resp.winningReply()).isEqualTo(OPTIMAL_BASE_REPLY);
            assertThat(resp.winningBubbles()).isNotEmpty();
        }

        @Test
        @DisplayName("Audit entity for KEEP_BASE records correct verdict, delta score and rationale")
        void testKeepBaseAuditEntityRecorded() {
            HarnessOptimizeRequest req = new HarnessOptimizeRequest(
                    PAGE_ID, "tư vấn gói", OPTIMAL_BASE_REPLY, 0
            );

            optimizerService.optimize(req);

            ArgumentCaptor<HarnessRunEntity> captor = ArgumentCaptor.forClass(HarnessRunEntity.class);
            verify(runRepository).save(captor.capture());
            HarnessRunEntity saved = captor.getValue();

            assertThat(saved.getVerdict()).isEqualTo("KEEP_BASE");
            assertThat(saved.getWinnerCandidate()).isEqualTo("KEEP_BASE");
            assertThat(saved.getDeltaScore()).isLessThan(0.15);
            assertThat(saved.getRationale()).contains("KEEP_BASE");
        }
    }

    // =========================================================================
    // 3. ADOPTED RULE PERSISTENCE & 3-PART EXPERIENCE LIBRARY TESTS
    // =========================================================================
    @Nested
    @DisplayName("3. ADOPTED Rule Persistence & 3-Part Experience Schema")
    class AdoptedRulePersistenceTests {

        @Test
        @DisplayName("Suboptimal response with cooling lead yields ADOPTED verdict and persists 3-part rule")
        void testAdoptedRulePersistenceOnCoolingLead() {
            HarnessOptimizeRequest req = new HarnessOptimizeRequest(
                    PAGE_ID,
                    "ừ để chị xem thêm đã",
                    SUBOPTIMAL_BASE_REPLY,
                    "conv-cooling-1",
                    List.of("User: giá sao", "Assistant: 1.500.000đ"),
                    -20,
                    List.of(),
                    "TEMPERATURE_DROP_BURST",
                    "task-1",
                    123L
            );

            HarnessOptimizeResponse resp = optimizerService.optimize(req);

            assertThat(resp.verdict()).isEqualTo("ADOPTED");
            assertThat(resp.deltaScore()).isGreaterThanOrEqualTo(0.15);
            assertThat(resp.newExperienceRule()).isNotNull();

            LearnedRuleDto rule = resp.newExperienceRule();
            assertThat(rule.dimension()).isEqualTo("INV");
            assertThat(rule.alwaysOn()).isFalse(); // CRITICAL: strictly false to protect 1200 char budget!
            assertThat(rule.confidenceScore()).isEqualTo(0.92);
            assertThat(rule.cueSignal()).contains("hạ nhiệt");
            assertThat(rule.generalizedRule()).contains("áp lực chốt bán hàng");
            assertThat(rule.contrastiveEvidence()).contains("❌ Before").contains("✅ After");

            // Verify persistence via repository
            ArgumentCaptor<LearnedRuleEntity> captor = ArgumentCaptor.forClass(LearnedRuleEntity.class);
            verify(ruleRepository, times(1)).save(captor.capture());
            LearnedRuleEntity entity = captor.getValue();

            assertThat(entity.getDimension()).isEqualTo(HarnessDimension.INV);
            assertThat(entity.isAlwaysOn()).isFalse();
            assertThat(entity.getPageId()).isNull(); // Global scope for Invariants
            assertThat(entity.getConfidenceScore()).isEqualTo(0.92);
        }

        @Test
        @DisplayName("Domain fact query (sẹo rỗ) proposes DOM dimension and preserves pageId")
        void testAdoptedDomainRulePersistence() {
            HarnessOptimizeRequest req = new HarnessOptimizeRequest(
                    PAGE_ID,
                    "trị mụn có chữa được sẹo rỗ không em",
                    "Dạ liệu trình trị mụn 1.500.000đ bên em chữa được cả sẹo rỗ nhé Chị Lan Anh có làm không?",
                    "conv-dom-1",
                    List.of(),
                    0,
                    List.of(),
                    "MANUAL_1CLICK",
                    "task-dom",
                    42L
            );

            HarnessOptimizeResponse resp = optimizerService.optimize(req);

            assertThat(resp.verdict()).isEqualTo("ADOPTED");
            assertThat(resp.proposedDimension()).isEqualTo("DOM");

            ArgumentCaptor<LearnedRuleEntity> captor = ArgumentCaptor.forClass(LearnedRuleEntity.class);
            verify(ruleRepository).save(captor.capture());
            LearnedRuleEntity entity = captor.getValue();

            assertThat(entity.getDimension()).isEqualTo(HarnessDimension.DOM);
            assertThat(entity.getPageId()).isEqualTo(UUID.fromString(PAGE_ID));
        }

        @Test
        @DisplayName("Audit entity for ADOPTED run stores both candidates, winner, and delta score")
        void testAdoptedAuditEntityStored() {
            HarnessOptimizeRequest req = new HarnessOptimizeRequest(
                    PAGE_ID, "ừ để xem", SUBOPTIMAL_BASE_REPLY, -18
            );

            optimizerService.optimize(req);

            ArgumentCaptor<HarnessRunEntity> captor = ArgumentCaptor.forClass(HarnessRunEntity.class);
            verify(runRepository).save(captor.capture());
            HarnessRunEntity saved = captor.getValue();

            assertThat(saved.getVerdict()).isEqualTo("ADOPTED");
            assertThat(saved.getWinnerCandidate()).isIn("Candidate A", "Candidate B");
            assertThat(saved.getDeltaScore()).isGreaterThanOrEqualTo(0.15);
            assertThat(saved.getCandidateA()).isNotBlank();
            assertThat(saved.getCandidateB()).isNotBlank();
        }
    }

    // =========================================================================
    // 4. PROMPT ASSEMBLY INTEGRATION & TOKEN BUDGET PROTECTION TESTS
    // =========================================================================
    @Nested
    @DisplayName("4. Prompt Assembly Integration & Token Budget Protection")
    class PromptAssemblyIntegrationTests {

        @Test
        @DisplayName("Adopted rule with always_on = false is routed to onDemandRules, consuming 0 chars of budget")
        void testAdoptedRuleSafeguardsAlwaysOnBudget() {
            HarnessOptimizeRequest req = new HarnessOptimizeRequest(
                    PAGE_ID, "ừ để xem thêm đã", SUBOPTIMAL_BASE_REPLY, -20
            );

            HarnessOptimizeResponse resp = optimizerService.optimize(req);
            assertThat(resp.verdict()).isEqualTo("ADOPTED");
            LearnedRuleDto adoptedRule = resp.newExperienceRule();

            // In production, the system contains always-on invariant rules.
            // Verify that the newly adopted rule (with always_on = false) is demoted to onDemandRules
            // and does not consume any character budget from the Always-On pool.
            LearnedRuleDto systemAlwaysOnRule = new LearnedRuleDto(
                    UUID.randomUUID(), "INV", "Cue system", "Cause", "Lesson system invariant",
                    List.of(), true, null, 0.95, 0, 0, 0, 1, null, null, null,
                    Instant.now(), Instant.now(), "INV", "Cue system", "Evidence", true
            );

            BudgetAllocation alloc = FanpageBotPromptBuilder.packAlwaysOnRules(List.of(systemAlwaysOnRule, adoptedRule));

            assertThat(alloc.alwaysOnRules()).containsExactly(systemAlwaysOnRule);
            assertThat(alloc.onDemandRules()).contains(adoptedRule);
            assertThat(alloc.totalAlwaysOnChars()).isEqualTo(FanpageBotPromptBuilder.ruleCharacterCount(systemAlwaysOnRule));
            assertThat(alloc.capExceeded()).isFalse();
        }

        @Test
        @DisplayName("FanpageBotPromptBuilder formats experience card with authentic 3-part XML schema")
        void testFormatExperienceCardIntegration() {
            HarnessOptimizeRequest req = new HarnessOptimizeRequest(
                    PAGE_ID, "ừ để xem thêm đã", SUBOPTIMAL_BASE_REPLY, -20
            );

            HarnessOptimizeResponse resp = optimizerService.optimize(req);
            LearnedRuleDto rule = resp.newExperienceRule();

            String card = FanpageBotPromptBuilder.formatExperienceCard(rule);

            assertThat(card).contains("<experience dimension=\"INV\">");
            assertThat(card).contains("<cue_signal>");
            assertThat(card).contains("<rule_text>");
            assertThat(card).contains("<contrastive_evidence>");
            assertThat(card).contains("❌ Before:");
            assertThat(card).contains("✅ After:");
        }
    }

    // =========================================================================
    // 5. BACKGROUND COOLING AUTO-TRIGGER & RESILIENCE TESTS
    // =========================================================================
    @Nested
    @DisplayName("5. Background Cooling Auto-Trigger (temperature_delta <= -15)")
    class BackgroundCoolingAutoTriggerTests {

        @Test
        @DisplayName("temperature_delta <= -15 asynchronously triggers optimizer")
        void testTriggerAsyncOptimizationWhenDeltaLeqMinus15() {
            CompletableFuture<HarnessOptimizeResponse> future = optimizerService.triggerAsyncOptimization(
                    PAGE_ID, "sender-1", "ừ để xem", SUBOPTIMAL_BASE_REPLY, List.of(), -20, "TEMPERATURE_DROP_BURST"
            );

            assertThat(future).isNotNull();
            HarnessOptimizeResponse resp = future.join();
            assertThat(resp).isNotNull();
            assertThat(resp.verdict()).isEqualTo("ADOPTED");
        }

        @Test
        @DisplayName("temperature_delta > -15 does NOT trigger optimizer")
        void testDoesNotTriggerWhenDeltaGreaterThanMinus15() {
            CompletableFuture<HarnessOptimizeResponse> future = optimizerService.triggerAsyncOptimization(
                    PAGE_ID, "sender-2", "tư vấn giúp", OPTIMAL_BASE_REPLY, List.of(), -10, "NORMAL"
            );

            assertThat(future).isNotNull();
            HarnessOptimizeResponse resp = future.join();
            assertThat(resp).isNull();
            verify(runRepository, never()).save(any(HarnessRunEntity.class));
        }

        @Test
        @DisplayName("Sliding debounce suppresses duplicate triggers for the same conversation within 5 minutes")
        void testSlidingDebounceSuppressesDuplicates() {
            CompletableFuture<HarnessOptimizeResponse> first = optimizerService.triggerAsyncOptimization(
                    PAGE_ID, "sender-debounce", "ừ để xem", SUBOPTIMAL_BASE_REPLY, List.of(), -20, "TEMP"
            );
            first.join();

            // Immediate second call for same page and sender
            CompletableFuture<HarnessOptimizeResponse> second = optimizerService.triggerAsyncOptimization(
                    PAGE_ID, "sender-debounce", "ừ", SUBOPTIMAL_BASE_REPLY, List.of(), -20, "TEMP"
            );

            assertThat(second.join()).isNull();
        }

        @Test
        @DisplayName("FanpageBotService triggers optimizer asynchronously on cooling signal and isolates errors")
        void testFanpageBotServiceIntegrationAndFaultIsolation() {
            RetrievalService retrievalService = mock(RetrievalService.class);
            LlmClient llmClient = mock(LlmClient.class);
            HarnessOptimizerService mockOptimizer = mock(HarnessOptimizerService.class);
            com.example.docmind.config.ChatProperties chatProps = new com.example.docmind.config.ChatProperties();

            var rc = new com.example.docmind.repository.RetrievedChunk(
                    UUID.randomUUID(), UUID.randomUUID(), List.of("Bảng Giá Dịch Vụ"), List.of(), 1, new double[]{0, 0, 100, 100},
                    "Liệu trình Trị Mụn Chuẩn Y Khoa 650.000đ", 0.92
            );
            var fused = new com.example.docmind.service.retrieval.ReciprocalRankFusion.FusedChunk(rc, 0.032);

            when(retrievalService.retrieveWithRules(any(), any(), anyInt(), any(), any()))
                    .thenReturn(new RetrievalService.RetrievalResult(List.of(fused), List.of()));
            when(llmClient.name()).thenReturn("test-llm");
            when(llmClient.complete(any())).thenReturn("Dạ em chào Anh ạ! Gói mụn 650.000đ.");

            // Fault injection: optimizer throws unexpected exception
            doThrow(new RuntimeException("Database connection timeout"))
                    .when(mockOptimizer).triggerAsyncOptimization(any(), any(), any(), any(), any(), anyInt(), any());

            FanpageBotService botService = new FanpageBotService(
                    retrievalService, llmClient, chatProps, null, null,
                    new CustomerInsightEngine(), null, null, guardrailPipeline, mockOptimizer
            );

            // Customer replies with cooling phrase: prevTemp = 60, message = "ừ để chị xem" -> delta = -20
            FanpageBotRequest botReq = new FanpageBotRequest(
                    PAGE_ID, "sender-test", "Anh Nam", "ừ để chị xem thêm", null,
                    List.of(), List.of(UUID.randomUUID()), 5, 60, null, null, "CHAT"
            );

            // Must NOT throw exception to caller despite background optimizer failure
            FanpageBotResponse botResp = botService.handleCustomerMessage(botReq);

            assertThat(botResp).isNotNull();
            assertThat(botResp.replyText()).isNotBlank();
            verify(mockOptimizer, times(1)).triggerAsyncOptimization(any(), any(), any(), any(), any(), eq(-20), any());
        }
    }

    // =========================================================================
    // 6. REST CONTROLLER ENDPOINT TESTS
    // =========================================================================
    @Nested
    @DisplayName("6. REST Controller Endpoints (/api/harness/*)")
    class RestControllerTests {

        @Test
        @DisplayName("POST /api/harness/optimize returns 200 OK and response payload")
        void testOptimizeEndpoint() {
            HarnessOptimizeRequest req = new HarnessOptimizeRequest(
                    PAGE_ID, "ừ để xem thêm đã", SUBOPTIMAL_BASE_REPLY, -20
            );

            ResponseEntity<HarnessOptimizeResponse> response = harnessController.optimize(req);

            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().verdict()).isEqualTo("ADOPTED");
            assertThat(response.getBody().runId()).isNotBlank();
        }

        @Test
        @DisplayName("GET /api/harness/runs queries repository and returns DTO list")
        void testGetRunsEndpoint() {
            HarnessRunEntity runEntity = HarnessRunEntity.builder()
                    .id(UUID.randomUUID().toString())
                    .pageId(PAGE_ID)
                    .customerMessage("ừ để xem")
                    .baseResponse(SUBOPTIMAL_BASE_REPLY)
                    .winnerCandidate("Candidate A")
                    .verdict("ADOPTED")
                    .deltaScore(0.35)
                    .rationale("Candidate won")
                    .createdAt(Instant.now())
                    .build();

            when(runRepository.findRunsWithFilter(eq(PAGE_ID), eq("ADOPTED"), any()))
                    .thenReturn(List.of(runEntity));

            ResponseEntity<List<HarnessRunDto>> response = harnessController.getRuns(PAGE_ID, "ADOPTED", 10);

            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(response.getBody()).hasSize(1);
            assertThat(response.getBody().get(0).verdict()).isEqualTo("ADOPTED");
            assertThat(response.getBody().get(0).pageId()).isEqualTo(PAGE_ID);
        }

        @Test
        @DisplayName("Listing runs without page_id queries only global runs (pageId IS NULL)")
        void getRunsWithoutPageIdQueriesGlobalRunsOnly() {
            HarnessRunEntity globalRun = HarnessRunEntity.builder()
                    .id("run-global-1")
                    .pageId(null)
                    .customerMessage("Khách hỏi chung")
                    .baseResponse("Trả lời chung")
                    .candidateA("A")
                    .candidateB("B")
                    .winnerCandidate("Candidate A")
                    .verdict("ADOPTED")
                    .deltaScore(0.35)
                    .rationale("Candidate won")
                    .createdAt(Instant.now())
                    .build();

            when(runRepository.findRunsWithFilter(isNull(), isNull(), any()))
                    .thenReturn(List.of(globalRun));

            ResponseEntity<List<HarnessRunDto>> response = harnessController.getRuns(null, null, 10);

            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(response.getBody()).hasSize(1);
            assertThat(response.getBody().get(0).pageId()).isNull();
            verify(runRepository).findRunsWithFilter(isNull(), isNull(), any());
        }
    }

    // =========================================================================
    // 7. CONTEXT-AWARE PAS SYMPTOM & MULTI-TURN HONORIFIC PERSISTENCE TESTS
    // =========================================================================
    @Nested
    @DisplayName("7. Situational PAS Symptom Framing & Multi-Turn Honorific Persistence")
    class SituationalPasAndHonorificTests {

        @Test
        @DisplayName("Shorthand 'a sợ không hiệu quả' resolves to 'Anh' and persists across subsequent turns")
        void testShorthandMaleHonorificAndHistoryPersistence() {
            String h1 = FanpageBotPromptBuilder.resolveCustomerHonorific(
                    "a sợ không hiệu quả", List.of(), "Khách hàng", null
            );
            assertThat(h1).isEqualTo("Anh");

            // When history contains "a sợ không hiệu quả" or an Assistant message addressing "Anh",
            // a subsequent turn with "tôi bị mụn viêm..." MUST keep "Anh" and never flip back to "Chị".
            List<FanpageBotRequest.ConversationTurn> historyWithAssistantAnh = List.of(
                    new FanpageBotRequest.ConversationTurn("user", "tôi muốn tư vấn gói trị mụn"),
                    new FanpageBotRequest.ConversationTurn("assistant",
                            "Dạ chi phí liệu trình bên em rất linh hoạt chỉ từ 650.000đ/buổi tùy theo tình trạng da của Anh, đã bao gồm Bác sĩ soi da 3D miễn phí ạ.")
            );
            String h2 = FanpageBotPromptBuilder.resolveCustomerHonorific(
                    "tôi bị mụn viêm dưới cằm và lưng, bị thâm mụn lâu nữa", historyWithAssistantAnh, "Khách hàng", null
            );
            assertThat(h2).isEqualTo("Anh");
        }

        @Test
        @DisplayName("Complex/multi-zone symptom turn generates PAS clinical risk & diagnostic appointment hook and beats weak 'tò mò' reply")
        void testComplexSymptomPasOptimizationBeatsWeakCuriosityReply() {
            String weakBaseReply =
                    "Dạ em hoàn toàn hiểu Chị muốn tìm hiểu kỹ trước khi quyết định, việc cân nhắc kỹ như vậy rất đúng đắn ạ. "
                    + "Liệu trình Trị Mụn Chuẩn Y Khoa bên em hỗ trợ tốt cho mụn viêm và nền da dầu, kèm quà tuýp chấm mụn kháng viêm.\n\n"
                    + "Chị đang tò mò hơn về cách phục hồi thâm sau mụn hay muốn giữ suất soi da AI 3D miễn phí 0đ không ràng buộc trước ạ?";

            HarnessOptimizeRequest req = new HarnessOptimizeRequest(
                    PAGE_ID,
                    "tôi bị mụn viêm dưới cằm và lưng, bị thâm mụn lâu nữa",
                    weakBaseReply,
                    "conv-pas-1",
                    List.of(
                            "user: a sợ không hiệu quả",
                            "assistant: Dạ chi phí liệu trình bên em rất linh hoạt chỉ từ 650.000đ/buổi tùy theo tình trạng da của Anh"
                    ),
                    0,
                    List.of(),
                    "MANUAL_1CLICK",
                    "task-pas",
                    42L
            );

            HarnessOptimizeResponse resp = optimizerService.optimize(req);

            assertThat(resp.verdict()).isEqualTo("ADOPTED");
            assertThat(resp.winningReply())
                    .contains("Anh")
                    .doesNotContain("Chị")
                    .doesNotContain("tò mò hơn về")
                    .containsIgnoringCase("sẹo")
                    .containsIgnoringCase("soi da AI 3D");
            assertThat(resp.newExperienceRule()).isNotNull();
            assertThat(resp.newExperienceRule().generalizedRule()).contains("PAS");
        }

        @Test
        @DisplayName("Harness² generates distinct, situation-specific suggestions across different customer turns")
        void testDistinctSituationalCandidatesAcrossScenarios() {
            HarnessOptimizeResponse rFear = optimizerService.optimize(new HarnessOptimizeRequest(
                    PAGE_ID, "a sợ không hiệu quả", SUBOPTIMAL_BASE_REPLY, "c1", List.of(), 0, List.of(), "MANUAL_1CLICK", "t1", 1L
            ));
            HarnessOptimizeResponse rPain = optimizerService.optimize(new HarnessOptimizeRequest(
                    PAGE_ID, "soi da có đau không có rằng buộc j k", SUBOPTIMAL_BASE_REPLY, "c2", List.of(), 0, List.of(), "MANUAL_1CLICK", "t2", 1L
            ));
            HarnessOptimizeResponse rBranch = optimizerService.optimize(new HarnessOptimizeRequest(
                    PAGE_ID, "có chi nhánh ở HN k", SUBOPTIMAL_BASE_REPLY, "c3", List.of(), 0, List.of(), "MANUAL_1CLICK", "t3", 1L
            ));
            HarnessOptimizeResponse rSymptom = optimizerService.optimize(new HarnessOptimizeRequest(
                    PAGE_ID, "tôi bị mụn viêm dưới cằm và lưng, bị thâm mụn lâu nữa", SUBOPTIMAL_BASE_REPLY, "c4", List.of(), 0, List.of(), "MANUAL_1CLICK", "t4", 1L
            ));

            Set<String> uniqueWinningReplies = Set.of(
                    rFear.winningReply(),
                    rPain.winningReply(),
                    rBranch.winningReply(),
                    rSymptom.winningReply()
            );
            assertThat(uniqueWinningReplies).hasSize(4);
        }
    }
}

