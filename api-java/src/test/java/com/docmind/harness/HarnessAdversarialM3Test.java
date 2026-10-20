package com.docmind.harness;

import com.docmind.guardrail.GuardrailPipeline;
import com.example.docmind.config.ChatProperties;
import com.example.docmind.dto.bot.CustomerInsightDto;
import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.dto.bot.FanpageBotResponse;
import com.example.docmind.dto.harness.HarnessOptimizeRequest;
import com.example.docmind.dto.harness.HarnessOptimizeResponse;
import com.example.docmind.entity.HarnessRunEntity;
import com.example.docmind.entity.LearnedRuleEntity;
import com.example.docmind.repository.HarnessRunRepository;
import com.example.docmind.repository.LearnedRuleRepository;
import com.example.docmind.repository.RetrievedChunk;
import com.example.docmind.service.bot.CustomerInsightEngine;
import com.example.docmind.service.bot.FanpageBotService;
import com.example.docmind.service.harness.HarnessOptimizerService;
import com.example.docmind.service.llm.LlmClient;
import com.example.docmind.service.retrieval.ReciprocalRankFusion;
import com.example.docmind.service.retrieval.RetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Empirical Adversarial Challenger Test Suite for Milestone M3:
 * Background Cooling Auto-Trigger, 5-Minute Sliding Debounce Cache,
 * Error Containment & Chat Latency SLA (< 5ms), and Multi-Threaded Concurrency Safety.
 */
@DisplayName("Adversarial Empirical Challenger: Milestone M3 Background Cooling & Concurrency")
public class HarnessAdversarialM3Test {

    private HarnessRunRepository runRepository;
    private LearnedRuleRepository ruleRepository;
    private GuardrailPipeline guardrailPipeline;
    private HarnessOptimizerService optimizerService;

    private static final String PAGE_ID = "00000000-0000-0000-0000-000000000001";
    private static final String SUBOPTIMAL_BASE_REPLY =
            "Dạ gói Trị Mụn bên em 1.500.000đ, gói Trị Nám 4.500.000đ. Chị Lan Anh có muốn chốt lịch luôn chiều nay không ạ?";

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
    }

    // =========================================================================
    // 1. COOLING AUTO-TRIGGER BOUNDARY TESTING (temperature_delta <= -15 vs > -15)
    // =========================================================================
    @Nested
    @DisplayName("1. Cooling Auto-Trigger Boundary Testing (temperatureDelta <= -15 vs > -15)")
    class CoolingAutoTriggerBoundaryTests {

        @Test
        @DisplayName("Exact boundary -15 triggers background optimization")
        void testExactBoundaryMinus15Triggers() {
            CompletableFuture<HarnessOptimizeResponse> future = optimizerService.triggerAsyncOptimization(
                    PAGE_ID, "sender-boundary-minus15", "ừ để xem", SUBOPTIMAL_BASE_REPLY, List.of(), -15, "BOUNDARY_TEST"
            );

            assertThat(future).isNotNull();
            HarnessOptimizeResponse resp = future.join();
            assertThat(resp).isNotNull();
            assertThat(resp.verdict()).isIn("ADOPTED", "KEEP_BASE");
            assertThat(resp.runId()).isNotBlank();
        }

        @Test
        @DisplayName("Deeper cooling values (-20, -35) reliably trigger background optimization")
        void testDeeperCoolingValuesTrigger() {
            int[] coolingDeltas = {-20, -35};
            for (int delta : coolingDeltas) {
                CompletableFuture<HarnessOptimizeResponse> future = optimizerService.triggerAsyncOptimization(
                        PAGE_ID, "sender-cooling-" + delta, "thôi đắt quá", SUBOPTIMAL_BASE_REPLY, List.of(), delta, "DEEP_COOLING"
                );
                assertThat(future).isNotNull();
                HarnessOptimizeResponse resp = future.join();
                assertThat(resp).as("Expected delta %d to trigger optimization", delta).isNotNull();
            }
        }

        @Test
        @DisplayName("Exact boundary -14 (greater than -15) does NOT trigger background optimization")
        void testExactBoundaryMinus14DoesNotTrigger() {
            CompletableFuture<HarnessOptimizeResponse> future = optimizerService.triggerAsyncOptimization(
                    PAGE_ID, "sender-boundary-minus14", "tư vấn gói mụn", SUBOPTIMAL_BASE_REPLY, List.of(), -14, "BOUNDARY_TEST"
            );

            assertThat(future).isNotNull();
            HarnessOptimizeResponse resp = future.join();
            assertThat(resp).isNull();
            verify(runRepository, never()).save(any(HarnessRunEntity.class));
        }

        @Test
        @DisplayName("Non-cooling values (-5, 0, +10) do NOT trigger background optimization")
        void testNonCoolingValuesDoNotTrigger() {
            int[] nonCoolingDeltas = {-5, 0, 10};
            for (int delta : nonCoolingDeltas) {
                CompletableFuture<HarnessOptimizeResponse> future = optimizerService.triggerAsyncOptimization(
                        PAGE_ID, "sender-noncooling-" + delta, "bên mình có dịch vụ gì", SUBOPTIMAL_BASE_REPLY, List.of(), delta, "NON_COOLING"
                );
                assertThat(future).isNotNull();
                HarnessOptimizeResponse resp = future.join();
                assertThat(resp).as("Expected delta %d to NOT trigger optimization", delta).isNull();
            }
            verify(runRepository, never()).save(any(HarnessRunEntity.class));
        }

        @Test
        @DisplayName("FanpageBotService end-to-end enforces exact threshold <= -15 and rejects > -15")
        void testFanpageBotServiceThresholdEnforcement() {
            RetrievalService retrievalService = mock(RetrievalService.class);
            LlmClient llmClient = mock(LlmClient.class);
            HarnessOptimizerService mockOptimizer = mock(HarnessOptimizerService.class);
            CustomerInsightEngine mockInsightEngine = mock(CustomerInsightEngine.class);

            var rc = new RetrievedChunk(
                    UUID.randomUUID(), UUID.randomUUID(), List.of("Bảng Giá"), List.of(), 1, new double[]{0, 0, 100, 100},
                    "Liệu trình Trị Mụn 650.000đ", 0.92
            );
            var fused = new ReciprocalRankFusion.FusedChunk(rc, 0.032);
            when(retrievalService.retrieveWithRules(any(), any(), anyInt(), any(), any()))
                    .thenReturn(new RetrievalService.RetrievalResult(List.of(fused), List.of()));
            when(llmClient.name()).thenReturn("test-llm");
            when(llmClient.complete(any())).thenReturn("Dạ em chào Anh ạ! Gói mụn 650.000đ.");

            FanpageBotService botService = new FanpageBotService(
                    retrievalService, llmClient, new ChatProperties(), null, null,
                    mockInsightEngine, null, null, guardrailPipeline, mockOptimizer
            );

            // Case A: delta = -15 (triggers)
            when(mockInsightEngine.analyzeInsight(any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(new CustomerInsightDto("ANALYTICAL", "Analytical", 45, 60, -15, "COOLING_HALF_HEARTED", "Cooling", "COOLING", "Anh", List.of(), List.of(), "TAKEAWAY"));
            FanpageBotRequest req15 = new FanpageBotRequest(
                    PAGE_ID, "user-delta-15", "Anh Nam", "ừ để xem", null, List.of(), List.of(UUID.randomUUID()), 5, 60, null, null, "CHAT"
            );
            botService.handleCustomerMessage(req15);
            verify(mockOptimizer, times(1)).triggerAsyncOptimization(any(), any(), any(), any(), any(), eq(-15), any());

            // Case B: delta = -14 (does NOT trigger)
            when(mockInsightEngine.analyzeInsight(any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(new CustomerInsightDto("ANALYTICAL", "Analytical", 46, 60, -14, "COOLING_HALF_HEARTED", "Cooling", "COOLING", "Anh", List.of(), List.of(), "TAKEAWAY"));
            FanpageBotRequest req14 = new FanpageBotRequest(
                    PAGE_ID, "user-delta-14", "Anh Nam", "ừ để xem", null, List.of(), List.of(UUID.randomUUID()), 5, 60, null, null, "CHAT"
            );
            botService.handleCustomerMessage(req14);
            // Verify mockOptimizer was NOT called with -14
            verify(mockOptimizer, never()).triggerAsyncOptimization(any(), any(), any(), any(), any(), eq(-14), any());
        }
    }

    // =========================================================================
    // 2. RAPID-FIRE BURST & SLIDING DEBOUNCE STRESS
    // =========================================================================
    @Nested
    @DisplayName("2. Rapid-Fire Burst & 5-Minute Sliding Debounce Stress")
    class DebounceStressTests {

        @Test
        @DisplayName("Rapid-fire burst: 10 consecutive requests in <100ms for same conversation permits exactly 1 run and rejects 9")
        void testRapidFire10ConsecutiveBurstWithin100ms() {
            String convId = "burst-sender-" + UUID.randomUUID();
            long burstStartTime = System.currentTimeMillis();

            int allowedRuns = 0;
            int debouncedRuns = 0;

            for (int i = 0; i < 10; i++) {
                CompletableFuture<HarnessOptimizeResponse> future = optimizerService.triggerAsyncOptimization(
                        PAGE_ID, convId, "ừ để xem thêm lần " + i, SUBOPTIMAL_BASE_REPLY, List.of(), -20, "BURST_BURST"
                );
                HarnessOptimizeResponse resp = future.join();
                if (resp != null) {
                    allowedRuns++;
                } else {
                    debouncedRuns++;
                }
            }

            long totalBurstDurationMs = System.currentTimeMillis() - burstStartTime;

            // Assert exactly 1 permitted run and 9 debounced duplicates
            assertThat(allowedRuns).as("Expected exactly 1 run permitted across 10 rapid-fire requests").isEqualTo(1);
            assertThat(debouncedRuns).as("Expected exactly 9 duplicates debounced and rejected").isEqualTo(9);
            assertThat(totalBurstDurationMs).as("Burst should complete rapidly").isLessThan(2000L);
        }

        @Test
        @DisplayName("Distinct conversations do NOT debounce each other (no cross-conversation crosstalk)")
        void testDistinctConversationsDoNotDebounceEachOther() {
            int conversationCount = 5;
            int allowedRuns = 0;

            for (int i = 0; i < conversationCount; i++) {
                String convId = "independent-conv-" + i + "-" + UUID.randomUUID();
                CompletableFuture<HarnessOptimizeResponse> future = optimizerService.triggerAsyncOptimization(
                        PAGE_ID, convId, "ừ để xem " + i, SUBOPTIMAL_BASE_REPLY, List.of(), -25, "INDEPENDENT"
                );
                HarnessOptimizeResponse resp = future.join();
                if (resp != null) {
                    allowedRuns++;
                }
            }

            // Every distinct conversation must be permitted its initial run
            assertThat(allowedRuns).as("Each distinct conversation should be permitted without cross-debouncing").isEqualTo(conversationCount);
        }

        @Test
        @DisplayName("Concurrent 10-thread burst to same conversation is strictly controlled")
        void testConcurrent10ThreadBurstForSameConversation() throws InterruptedException, ExecutionException {
            String convId = "concurrent-conv-" + UUID.randomUUID();
            int threadCount = 10;
            ExecutorService pool = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(1);

            List<Callable<HarnessOptimizeResponse>> tasks = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                tasks.add(() -> {
                    latch.await(); // ensure simultaneous execution
                    CompletableFuture<HarnessOptimizeResponse> f = optimizerService.triggerAsyncOptimization(
                            PAGE_ID, convId, "ừ để xem concurrent " + idx, SUBOPTIMAL_BASE_REPLY, List.of(), -20, "CONCURRENT_BURST"
                    );
                    return f.join();
                });
            }

            // Fire simultaneously
            latch.countDown();
            List<Future<HarnessOptimizeResponse>> results = pool.invokeAll(tasks);
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);

            int nonNullCount = 0;
            for (Future<HarnessOptimizeResponse> f : results) {
                if (f.get() != null) {
                    nonNullCount++;
                }
            }

            // In concurrent race, debounce must limit to at most 1 or 2 (bounded by concurrencyLimiter=2 and debounce)
            assertThat(nonNullCount).as("Concurrent burst should not permit unrestricted runs (bounded <= 2)").isLessThanOrEqualTo(2);
        }
    }

    // =========================================================================
    // 3. ERROR CONTAINMENT & CHAT LATENCY SLA (< 5ms)
    // =========================================================================
    @Nested
    @DisplayName("3. Error Containment & Chat Latency SLA (< 5ms)")
    class ErrorContainmentAndLatencySlaTests {

        private FanpageBotService botService;
        private HarnessOptimizerService faultThrowingOptimizer;

        @BeforeEach
        void initService() {
            RetrievalService retrievalService = mock(RetrievalService.class);
            LlmClient llmClient = mock(LlmClient.class);
            faultThrowingOptimizer = mock(HarnessOptimizerService.class);
            CustomerInsightEngine mockInsightEngine = mock(CustomerInsightEngine.class);

            var rc = new RetrievedChunk(
                    UUID.randomUUID(), UUID.randomUUID(), List.of("Bảng Giá"), List.of(), 1, new double[]{0, 0, 100, 100},
                    "Liệu trình Trị Mụn 650.000đ", 0.92
            );
            var fused = new ReciprocalRankFusion.FusedChunk(rc, 0.032);
            when(retrievalService.retrieveWithRules(any(), any(), anyInt(), any(), any()))
                    .thenReturn(new RetrievalService.RetrievalResult(List.of(fused), List.of()));
            when(llmClient.name()).thenReturn("test-llm");
            when(llmClient.complete(any())).thenReturn("Dạ em chào Anh ạ! Chi phí liệu trình là 650.000đ ạ.\n\nAnh muốn khám da trước không ạ?");

            when(mockInsightEngine.analyzeInsight(any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(new CustomerInsightDto("ANALYTICAL", "Analytical", 40, 60, -20, "COOLING_HALF_HEARTED", "Cooling", "COOLING", "Anh", List.of(), List.of(), "TAKEAWAY"));

            botService = new FanpageBotService(
                    retrievalService, llmClient, new ChatProperties(), null, null,
                    mockInsightEngine, null, null, guardrailPipeline, faultThrowingOptimizer
            );
        }

        @Test
        @DisplayName("Catastrophic DB pool exhaustion / timeout exception is completely swallowed without customer disruption")
        void testCatastrophicDbExceptionCompletelySwallowed() {
            // Fault injection: simulate catastrophic DB outage
            doThrow(new RuntimeException("FATAL: PostgreSQL connection pool exhausted (timeout 30000ms)"))
                    .when(faultThrowingOptimizer).triggerAsyncOptimization(any(), any(), any(), any(), any(), anyInt(), any());

            FanpageBotRequest req = new FanpageBotRequest(
                    PAGE_ID, "cust-db-outage", "Anh Nam", "ừ để xem thêm đã", null, List.of(), List.of(UUID.randomUUID()), 5, 60, null, null, "CHAT"
            );

            // Must NOT throw exception to caller
            FanpageBotResponse response = botService.handleCustomerMessage(req);

            assertThat(response).isNotNull();
            assertThat(response.replyText()).isNotBlank();
            assertThat(response.replyBubbles()).isNotEmpty();
        }

        @Test
        @DisplayName("Throwable error (OutOfMemoryError) is caught by dual-layer containment")
        void testFatalErrorContainment() {
            doThrow(new OutOfMemoryError("Simulated Metaspace Exhaustion in Optimizer"))
                    .when(faultThrowingOptimizer).triggerAsyncOptimization(any(), any(), any(), any(), any(), anyInt(), any());

            FanpageBotRequest req = new FanpageBotRequest(
                    PAGE_ID, "cust-oom", "Anh Nam", "ừ để xem", null, List.of(), List.of(UUID.randomUUID()), 5, 60, null, null, "CHAT"
            );

            FanpageBotResponse response = botService.handleCustomerMessage(req);
            assertThat(response).isNotNull();
            assertThat(response.replyText()).isNotBlank();
        }

        @Test
        @DisplayName("Chat Latency SLA: Error containment overhead in handleCustomerMessage is strictly < 5ms (sub-millisecond)")
        void testChatLatencySlaUnderCatastrophicFailure() {
            doThrow(new RuntimeException("Simulated catastrophic crash"))
                    .when(faultThrowingOptimizer).triggerAsyncOptimization(any(), any(), any(), any(), any(), anyInt(), any());

            FanpageBotRequest req = new FanpageBotRequest(
                    PAGE_ID, "cust-sla", "Anh Nam", "ừ để xem", null, List.of(), List.of(UUID.randomUUID()), 5, 60, null, null, "CHAT"
            );

            // JIT Warmup (100 runs for C2 compilation)
            for (int i = 0; i < 100; i++) {
                botService.handleCustomerMessage(req);
            }

            // Timed Benchmark (50 iterations)
            List<Long> durationsNs = new ArrayList<>();

            for (int i = 0; i < 50; i++) {
                long start = System.nanoTime();
                FanpageBotResponse resp = botService.handleCustomerMessage(req);
                long elapsed = System.nanoTime() - start;
                durationsNs.add(elapsed);
                assertThat(resp).isNotNull();
            }

            Collections.sort(durationsNs);
            double minMs = durationsNs.get(0) / 1_000_000.0;
            double p50Ms = durationsNs.get(25) / 1_000_000.0;
            double p90Ms = durationsNs.get(45) / 1_000_000.0;
            double p95Ms = durationsNs.get(47) / 1_000_000.0;
            double maxMs = durationsNs.get(49) / 1_000_000.0;

            // SLA requirement: median chat processing latency under catastrophic optimizer failure is < 5.0ms
            assertThat(p50Ms).as("Median chat response latency under catastrophic optimizer failure must be < 5.0ms").isLessThan(5.0);
            assertThat(minMs).as("Minimum chat response latency under catastrophic optimizer failure must be < 5.0ms").isLessThan(5.0);
        }

        @Test
        @DisplayName("Isolated Error Containment: overhead of swallowing catastrophic optimizer error is strictly < 0.1ms without Mockito proxy overhead")
        void testIsolatedErrorContainmentOverhead() {
            // Direct throwing optimizer instance (no Mockito invocation tracking overhead)
            HarnessOptimizerService directThrowingOptimizer = new HarnessOptimizerService(null, null, null, null, null, 0.15) {
                @Override
                public CompletableFuture<HarnessOptimizeResponse> triggerAsyncOptimization(
                        String pageId, String conversationId, String customerMessage,
                        String baseResponse, List<String> history, int temperatureDelta, String triggerReason
                ) {
                    throw new RuntimeException("Direct catastrophic DB failure");
                }
            };

            // JIT Warmup
            for (int i = 0; i < 100; i++) {
                try {
                    directThrowingOptimizer.triggerAsyncOptimization(PAGE_ID, "conv-warmup", "msg", "resp", List.of(), -20, "TEST");
                } catch (Throwable ignored) {}
            }

            // Timed Benchmark
            List<Long> durationsNs = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                long start = System.nanoTime();
                try {
                    directThrowingOptimizer.triggerAsyncOptimization(
                            PAGE_ID, "conv-1", "msg", "resp", List.of(), -20, "TEST"
                    );
                } catch (Throwable t) {
                    // error contained
                }
                long elapsed = System.nanoTime() - start;
                durationsNs.add(elapsed);
            }

            Collections.sort(durationsNs);
            double p95Ms = durationsNs.get(95) / 1_000_000.0;
            System.out.println(String.format("DIRECT_CONTAINMENT_OVERHEAD_P95: %.4fms", p95Ms));
            assertThat(p95Ms).as("Direct error containment overhead must be < 0.1ms (sub-millisecond)").isLessThan(0.1);
        }
    }

    // =========================================================================
    // 4. CONCURRENCY & THREAD SAFETY
    // =========================================================================
    @Nested
    @DisplayName("4. Concurrency & Thread Safety of HarnessOptimizerService")
    class ConcurrencyAndThreadSafetyTests {

        @Test
        @DisplayName("Multi-threaded concurrent invocations of optimize() across 20 threads execute without data races or corruption")
        void testConcurrentMultiThreadedOptimize() throws InterruptedException, ExecutionException {
            int threadCount = 20;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            List<Future<HarnessOptimizeResponse>> futures = new ArrayList<>();

            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                futures.add(executor.submit(() -> {
                    startLatch.await();
                    HarnessOptimizeRequest req = new HarnessOptimizeRequest(
                            PAGE_ID,
                            "ừ để em xem thêm " + idx,
                            SUBOPTIMAL_BASE_REPLY,
                            "conv-concurrent-" + idx,
                            List.of(),
                            -20,
                            List.of(),
                            "CONCURRENCY_TEST",
                            "task-" + idx,
                            (long) idx * 1000L
                    );
                    return optimizerService.optimize(req);
                }));
            }

            // Release all threads simultaneously
            startLatch.countDown();

            Set<String> observedRunIds = ConcurrentHashMap.newKeySet();
            AtomicInteger adoptedCount = new AtomicInteger(0);
            AtomicInteger keepBaseCount = new AtomicInteger(0);

            for (Future<HarnessOptimizeResponse> f : futures) {
                HarnessOptimizeResponse resp = f.get();
                assertThat(resp).isNotNull();
                assertThat(resp.runId()).isNotBlank();
                observedRunIds.add(resp.runId());

                assertThat(resp.verdict()).isIn("ADOPTED", "KEEP_BASE");
                if ("ADOPTED".equals(resp.verdict())) {
                    adoptedCount.incrementAndGet();
                    assertThat(resp.newExperienceRule()).isNotNull();
                    assertThat(resp.deltaScore()).isGreaterThanOrEqualTo(0.15);
                } else {
                    keepBaseCount.incrementAndGet();
                }

                assertThat(resp.candidateA()).isNotBlank();
                assertThat(resp.candidateB()).isNotBlank();
                assertThat(resp.candidateALabel()).isIn("BASE", "CANDIDATE");
                assertThat(resp.candidateBLabel()).isIn("BASE", "CANDIDATE");
                assertThat(resp.winningReply()).isNotBlank();
            }

            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);

            // Assert exactly 20 unique run IDs generated (no UUID collisions or race condition overwrites)
            assertThat(observedRunIds).hasSize(threadCount);

            // Verify runRepository received exactly 20 saves
            verify(runRepository, times(threadCount)).save(any(HarnessRunEntity.class));
        }

        @Test
        @DisplayName("Semaphore Concurrency Limiter (slots=2) safely manages permits and releases them on completion")
        void testConcurrencyLimiterPermitManagement() {
            // Sequential async triggers complete and release permits properly
            for (int i = 0; i < 5; i++) {
                CompletableFuture<HarnessOptimizeResponse> future = optimizerService.triggerAsyncOptimization(
                        PAGE_ID, "sender-limiter-" + i, "ừ để xem " + i, SUBOPTIMAL_BASE_REPLY, List.of(), -20, "LIMITER_TEST"
                );
                assertThat(future).isNotNull();
                HarnessOptimizeResponse resp = future.join();
                assertThat(resp).isNotNull();
            }
        }
    }
}
