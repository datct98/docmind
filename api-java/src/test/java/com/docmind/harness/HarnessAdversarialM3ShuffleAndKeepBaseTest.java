package com.docmind.harness;

import com.docmind.guardrail.GuardrailPipeline;
import com.example.docmind.dto.LearnedRuleDto;
import com.example.docmind.dto.harness.HarnessOptimizeRequest;
import com.example.docmind.dto.harness.HarnessOptimizeResponse;
import com.example.docmind.entity.HarnessRunEntity;
import com.example.docmind.entity.LearnedRuleEntity;
import com.example.docmind.repository.HarnessRunRepository;
import com.example.docmind.repository.LearnedRuleRepository;
import com.example.docmind.service.harness.HarnessOptimizerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Adversarial Empirical Verification Test Suite for Milestone M3 (Requirement R3):
 * Seeded Shuffle Anonymity, Slot-Swap Invariance, and KEEP_BASE Fallback Boundary.
 */
@DisplayName("Adversarial M3 Suite: Seeded Shuffle Anonymity & KEEP_BASE Boundary")
public class HarnessAdversarialM3ShuffleAndKeepBaseTest {

    private HarnessRunRepository runRepository;
    private LearnedRuleRepository ruleRepository;
    private GuardrailPipeline guardrailPipeline;
    private HarnessOptimizerService defaultOptimizer;

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

        defaultOptimizer = new HarnessOptimizerService(
                runRepository,
                ruleRepository,
                guardrailPipeline,
                null,
                null,
                0.15
        );
    }

    // =========================================================================
    // 1. ANONYMIZED SELECTION INVARIANCE & SEEDED SHUFFLE
    // =========================================================================
    @Nested
    @DisplayName("1. Anonymized Selection Invariance & Seeded Shuffle")
    class AnonymizedSelectionInvarianceTests {

        @Test
        @DisplayName("1A. Determinism: 500 repeated trials with identical seed produce byte-identical candidate slot assignment")
        void testDeterministicSeedAssignmentAcross500Trials() {
            long testSeed = 9876543210L;
            HarnessOptimizeRequest req = new HarnessOptimizeRequest(
                    PAGE_ID, "ừ để xem thêm đã", SUBOPTIMAL_BASE_REPLY, "conv-det",
                    List.of(), -20, List.of(), "TEMP", "task-det", testSeed
            );

            HarnessOptimizeResponse initial = defaultOptimizer.optimize(req);
            String expectedSlotA = initial.candidateA();
            String expectedSlotB = initial.candidateB();
            String expectedLabelA = initial.candidateALabel();
            String expectedLabelB = initial.candidateBLabel();
            String expectedWinner = initial.winnerCandidate();

            for (int i = 0; i < 500; i++) {
                HarnessOptimizeResponse repeat = defaultOptimizer.optimize(req);
                assertThat(repeat.candidateA()).isEqualTo(expectedSlotA);
                assertThat(repeat.candidateB()).isEqualTo(expectedSlotB);
                assertThat(repeat.candidateALabel()).isEqualTo(expectedLabelA);
                assertThat(repeat.candidateBLabel()).isEqualTo(expectedLabelB);
                assertThat(repeat.winnerCandidate()).isEqualTo(expectedWinner);
            }
        }

        @Test
        @DisplayName("1B. Slot-Swap Invariance: Candidate placed in Slot A vs Slot B yields identical quality scores, delta, and winning reply")
        void testSlotSwapInvarianceProducesIdenticalObjectiveScoring() {
            // Seed 0L -> Random.nextBoolean() is true -> swap=true -> Slot A = CANDIDATE, Slot B = BASE
            // Seed 4096L -> Random.nextBoolean() is false -> swap=false -> Slot A = BASE, Slot B = CANDIDATE
            HarnessOptimizeRequest reqSlotAIsCandidate = new HarnessOptimizeRequest(
                    PAGE_ID, "ừ để xem thêm đã", SUBOPTIMAL_BASE_REPLY, "conv-swap",
                    List.of(), -20, List.of(), "TEMP", "task-swap", 0L
            );
            HarnessOptimizeRequest reqSlotBIsCandidate = new HarnessOptimizeRequest(
                    PAGE_ID, "ừ để xem thêm đã", SUBOPTIMAL_BASE_REPLY, "conv-swap",
                    List.of(), -20, List.of(), "TEMP", "task-swap", 4096L
            );

            HarnessOptimizeResponse respA = defaultOptimizer.optimize(reqSlotAIsCandidate);
            HarnessOptimizeResponse respB = defaultOptimizer.optimize(reqSlotBIsCandidate);

            // Confirm physical slots are inverted
            assertThat(respA.candidateALabel()).isEqualTo("CANDIDATE");
            assertThat(respA.candidateBLabel()).isEqualTo("BASE");
            assertThat(respB.candidateALabel()).isEqualTo("BASE");
            assertThat(respB.candidateBLabel()).isEqualTo("CANDIDATE");

            // Invariance proofs:
            // 1. Base scores are identical
            assertThat(respA.scoreBefore()).isEqualTo(respB.scoreBefore());
            // 2. Candidate scores are identical
            assertThat(respA.scoreAfter()).isEqualTo(respB.scoreAfter());
            // 3. Delta score is identical
            assertThat(respA.deltaScore()).isEqualTo(respB.deltaScore());
            // 4. Verdict is identical
            assertThat(respA.verdict()).isEqualTo(respB.verdict()).isEqualTo("ADOPTED");
            // 5. Winning reply text is identical (independent of which slot held it)
            assertThat(respA.winningReply()).isEqualTo(respB.winningReply());
            // 6. Winner label appropriately points to the correct slot
            assertThat(respA.winnerCandidate()).isEqualTo("Candidate A");
            assertThat(respB.winnerCandidate()).isEqualTo("Candidate B");
        }

        @Test
        @DisplayName("1C. Statistical Uniformity: 2,000 random seeds show ~50/50 unbiased distribution (within 46%–54%)")
        void testStatisticalUniformityOver2000RandomSeeds() {
            int slotAIsCandidateCount = 0;
            int totalTrials = 2000;
            Random seedGenerator = new Random(1337);

            for (int i = 0; i < totalTrials; i++) {
                long randomSeed = seedGenerator.nextLong();
                HarnessOptimizeRequest req = new HarnessOptimizeRequest(
                        PAGE_ID, "ừ để xem " + i, SUBOPTIMAL_BASE_REPLY, "conv-" + i,
                        List.of(), -20, List.of(), "TEMP", "task-" + i, randomSeed
                );
                HarnessOptimizeResponse resp = defaultOptimizer.optimize(req);
                if ("CANDIDATE".equals(resp.candidateALabel())) {
                    slotAIsCandidateCount++;
                }
            }

            double slotARatio = (double) slotAIsCandidateCount / totalTrials;
            // 3-sigma tolerance: for N=2000, 3*sigma ~= 0.0335, so 46.6% - 53.4%
            assertThat(slotARatio).isCloseTo(0.50, within(0.04));
            assertThat(slotAIsCandidateCount).isBetween(920, 1080);
        }

        @Test
        @DisplayName("1D. computeSha1Seed: Cryptographic diffusion across 1,000 unique customer messages produces balanced parity")
        void testSha1SeedDiffusionOver1000DistinctMessages() {
            int trueCount = 0;
            int total = 1000;

            for (int i = 0; i < total; i++) {
                String message = "Khách hàng gửi tin nhắn số #" + i + " với nội dung tư vấn dịch vụ";
                long seed = HarnessOptimizerService.computeSha1Seed("task-" + i, PAGE_ID, "conv-" + i, message, -15 - (i % 10));
                Random rng = new Random(seed);
                if (rng.nextBoolean()) {
                    trueCount++;
                }
            }

            double ratio = (double) trueCount / total;
            assertThat(ratio).isCloseTo(0.50, within(0.05));
            assertThat(trueCount).isBetween(450, 550);
        }
    }

    // =========================================================================
    // 2. STRICT KEEP_BASE FALLBACK & ZERO POLLUTION
    // =========================================================================
    @Nested
    @DisplayName("2. Strict KEEP_BASE Fallback & Zero Pollution")
    class StrictKeepBaseFallbackTests {

        @Test
        @DisplayName("2A. Identical Base & Candidate (delta = 0): Strictly returns KEEP_BASE and zero rules written to learned_rules")
        void testIdenticalBaseAndCandidateYieldsZeroDeltaAndKeepBase() {
            // When temperatureDelta <= -15 and customer is "Anh", the probe draft generated is:
            String expectedProbeText =
                    "Dạ em hiểu ạ! Chăm sóc da là quyết định cần sự an tâm tuyệt đối nên Anh cứ thong thả cân nhắc kỹ nhé ạ.\n\n"
                    + "Cho em hỏi nhỏ để hỗ trợ Anh tốt nhất, hiện tại điều làm Anh băn khoăn nhất là về chi phí hay chưa chắc liệu trình có hợp với da mình ạ?";

            // Set baseResponse to EXACTLY match the probe response (identical)
            HarnessOptimizeRequest req = new HarnessOptimizeRequest(
                    PAGE_ID, "ừ để xem thêm đã", expectedProbeText, "conv-identical",
                    List.of(), -20, List.of(), "TEMP", "task-ident", 123L
            );

            HarnessOptimizeResponse resp = defaultOptimizer.optimize(req);

            // Assertions for exact delta = 0
            assertThat(resp.scoreBefore()).isEqualTo(resp.scoreAfter());
            assertThat(resp.deltaScore()).isEqualTo(0.0);
            assertThat(resp.verdict()).isEqualTo("KEEP_BASE");
            assertThat(resp.winnerCandidate()).isEqualTo("KEEP_BASE");
            assertThat(resp.winningReply()).isEqualTo(expectedProbeText);
            assertThat(resp.newExperienceRule()).isNull();
            assertThat(resp.rationale()).contains("KEEP_BASE");

            // CRITICAL: Zero library pollution in learned_rules
            verify(ruleRepository, never()).save(any(LearnedRuleEntity.class));

            // Audit record IS saved to harness_optimization_runs
            ArgumentCaptor<HarnessRunEntity> captor = ArgumentCaptor.forClass(HarnessRunEntity.class);
            verify(runRepository, times(1)).save(captor.capture());
            HarnessRunEntity audit = captor.getValue();
            assertThat(audit.getVerdict()).isEqualTo("KEEP_BASE");
            assertThat(audit.getDeltaScore()).isEqualTo(0.0);
            assertThat(audit.getWinnerCandidate()).isEqualTo("KEEP_BASE");
        }

        @Test
        @DisplayName("2B. Inferior Candidate (delta < 0): Strictly returns KEEP_BASE and retains superior baseline response")
        void testInferiorCandidateNegativeDeltaYieldsKeepBase() {
            // High-scoring base response (empathy + single price + valid closing question + correct honorific + conciseness)
            String superiorBaseReply =
                    "Dạ em hiểu ạ! Chăm sóc da là quyết định cần sự an tâm tuyệt đối nên Anh cứ thong thả cân nhắc kỹ nhé ạ. Chi phí bên em từ 650.000đ.\n\n"
                    + "Hiện tại Anh đang muốn ưu tiên xử lý mụn viêm hay mụn ẩn trước để em tư vấn phác đồ chuẩn nhất ạ?";

            // Customer message without cooling (temperatureDelta = 0) produces standard probe draft without empathy (+0.20 missing)
            HarnessOptimizeRequest req = new HarnessOptimizeRequest(
                    PAGE_ID, "giá mụn thế nào", superiorBaseReply, "conv-inferior",
                    List.of(), 0, List.of(), "MANUAL", "task-inf", 456L
            );

            HarnessOptimizeResponse resp = defaultOptimizer.optimize(req);

            // Assertions for delta < 0
            assertThat(resp.scoreBefore()).isGreaterThan(resp.scoreAfter());
            assertThat(resp.deltaScore()).isLessThan(0.0);
            assertThat(resp.verdict()).isEqualTo("KEEP_BASE");
            assertThat(resp.winnerCandidate()).isEqualTo("KEEP_BASE");
            assertThat(resp.winningReply()).isEqualTo(superiorBaseReply);
            assertThat(resp.newExperienceRule()).isNull();

            // Zero pollution verification
            verify(ruleRepository, never()).save(any(LearnedRuleEntity.class));
        }

        @Test
        @DisplayName("2C. Exact Borderline Delta (delta = 0.10 vs threshold 0.15): Delta below threshold strictly yields KEEP_BASE")
        void testExactBorderlineDeltaThresholdBoundary() {
            // Probe score for DISCOVERY "giá mụn" is 0.90
            // Construct a base reply scoring exactly 0.80:
            // - baseline: 0.50
            // - single price: +0.15
            // - ends with ?: +0.10
            // - neutral honorific (bạn): +0.00
            // - length <= 500: +0.05
            // Total baseScore = 0.80
            // candidateScore = 0.90 -> deltaScore = 0.90 - 0.80 = 0.10
            String baseReply80 = "Dạ em chào bạn ạ! Liệu trình bên mình từ 650.000đ. Bạn đang muốn làm mụn hay nám ạ?";
            double computedBaseScore = defaultOptimizer.evaluateReplyQuality(baseReply80, null, "Anh");
            assertThat(computedBaseScore).isEqualTo(0.80);

            HarnessOptimizeRequest reqBelowThreshold = new HarnessOptimizeRequest(
                    PAGE_ID, "giá mụn", baseReply80, 0
            );
            HarnessOptimizeResponse respBelow = defaultOptimizer.optimize(reqBelowThreshold);

            // delta = 0.10 < 0.15 -> MUST be KEEP_BASE
            assertThat(respBelow.deltaScore()).isEqualTo(0.10);
            assertThat(respBelow.verdict()).isEqualTo("KEEP_BASE");
            assertThat(respBelow.winnerCandidate()).isEqualTo("KEEP_BASE");
            assertThat(respBelow.newExperienceRule()).isNull();

            // When threshold is set to 0.10: delta 0.10 >= 0.10 -> ADOPTED
            HarnessOptimizerService threshold10 = new HarnessOptimizerService(
                    runRepository, ruleRepository, guardrailPipeline, null, null, 0.10
            );
            HarnessOptimizeResponse respAtThreshold = threshold10.optimize(reqBelowThreshold);
            assertThat(respAtThreshold.deltaScore()).isEqualTo(0.10);
            assertThat(respAtThreshold.verdict()).isEqualTo("ADOPTED");
            assertThat(respAtThreshold.newExperienceRule()).isNotNull();
        }

        @Test
        @DisplayName("2D. Boundary Precision: delta 0.10 vs threshold (0.099 vs 0.100 vs 0.101)")
        void testThresholdBoundaryDeltaComparison() {
            String baseReply80 = "Dạ em chào bạn ạ! Liệu trình bên mình từ 650.000đ. Bạn đang muốn làm mụn hay nám ạ?";
            HarnessOptimizeRequest req = new HarnessOptimizeRequest(PAGE_ID, "giá mụn", baseReply80, 0);

            // deltaScore is exactly 0.10
            // 1. Threshold 0.099: 0.10 >= 0.099 -> ADOPTED
            HarnessOptimizerService threshold099 = new HarnessOptimizerService(
                    runRepository, ruleRepository, guardrailPipeline, null, null, 0.099
            );
            HarnessOptimizeResponse resp099 = threshold099.optimize(req);
            assertThat(resp099.deltaScore()).isEqualTo(0.10);
            assertThat(resp099.verdict()).isEqualTo("ADOPTED");
            assertThat(resp099.newExperienceRule()).isNotNull();

            // 2. Threshold 0.100: 0.10 >= 0.100 -> ADOPTED
            HarnessOptimizerService threshold100 = new HarnessOptimizerService(
                    runRepository, ruleRepository, guardrailPipeline, null, null, 0.100
            );
            HarnessOptimizeResponse resp100 = threshold100.optimize(req);
            assertThat(resp100.deltaScore()).isEqualTo(0.10);
            assertThat(resp100.verdict()).isEqualTo("ADOPTED");
            assertThat(resp100.newExperienceRule()).isNotNull();

            // 3. Threshold 0.101: 0.10 < 0.101 -> KEEP_BASE
            HarnessOptimizerService threshold101 = new HarnessOptimizerService(
                    runRepository, ruleRepository, guardrailPipeline, null, null, 0.101
            );
            HarnessOptimizeResponse resp101 = threshold101.optimize(req);
            assertThat(resp101.deltaScore()).isEqualTo(0.10);
            assertThat(resp101.verdict()).isEqualTo("KEEP_BASE");
            assertThat(resp101.newExperienceRule()).isNull();
        }

        @Test
        @DisplayName("2D-Bis. Exact Delta = 0.15 tested against 0.149, 0.150, and 0.151 thresholds")
        void testExactDelta15AgainstBorderlineThresholds() {
            // Construct base reply scoring exactly 0.75:
            // - baseline: 0.50
            // - single price: +0.15 (0.65)
            // - ends with ?: +0.10 (0.75)
            // - neutral honorific (bạn): 0.00
            // - length 520 chars (between 501 and 650 chars): 0.00
            // Total = 0.75
            String padding = " ".repeat(480);
            String baseReply75 = "Dạ chi phí bên mình từ 650.000đ." + padding + "Bạn muốn xử lý mụn viêm hay mụn ẩn trước ạ?";
            assertThat(baseReply75.length()).isBetween(501, 650);
            double baseScore = defaultOptimizer.evaluateReplyQuality(baseReply75, null, "Anh");
            assertThat(baseScore).isEqualTo(0.75);

            HarnessOptimizeRequest req = new HarnessOptimizeRequest(PAGE_ID, "giá mụn", baseReply75, 0);

            // 1. Default threshold (0.150): delta 0.15 >= 0.150 -> ADOPTED
            HarnessOptimizeResponse respDefault = defaultOptimizer.optimize(req);
            assertThat(respDefault.deltaScore()).isEqualTo(0.15);
            assertThat(respDefault.verdict()).isEqualTo("ADOPTED");
            assertThat(respDefault.newExperienceRule()).isNotNull();

            // 2. Threshold 0.149: delta 0.15 >= 0.149 -> ADOPTED
            HarnessOptimizerService thresh149 = new HarnessOptimizerService(
                    runRepository, ruleRepository, guardrailPipeline, null, null, 0.149
            );
            HarnessOptimizeResponse resp149 = thresh149.optimize(req);
            assertThat(resp149.deltaScore()).isEqualTo(0.15);
            assertThat(resp149.verdict()).isEqualTo("ADOPTED");

            // 3. Threshold 0.150: delta 0.15 >= 0.150 -> ADOPTED
            HarnessOptimizerService thresh150 = new HarnessOptimizerService(
                    runRepository, ruleRepository, guardrailPipeline, null, null, 0.150
            );
            HarnessOptimizeResponse resp150 = thresh150.optimize(req);
            assertThat(resp150.deltaScore()).isEqualTo(0.15);
            assertThat(resp150.verdict()).isEqualTo("ADOPTED");

            // 4. Threshold 0.151: delta 0.15 < 0.151 -> KEEP_BASE
            HarnessOptimizerService thresh151 = new HarnessOptimizerService(
                    runRepository, ruleRepository, guardrailPipeline, null, null, 0.151
            );
            HarnessOptimizeResponse resp151 = thresh151.optimize(req);
            assertThat(resp151.deltaScore()).isEqualTo(0.15);
            assertThat(resp151.verdict()).isEqualTo("KEEP_BASE");
            assertThat(resp151.newExperienceRule()).isNull();
        }

        @Test
        @DisplayName("2E. IEEE 754 Floating Point Rounding Safety: Math.round ensures 0.70 - 0.55 = 0.15 without precision loss")
        void testFloatingPointArithmeticPrecisionRounding() {
            // In IEEE 754 binary floating point:
            double rawDifference = 0.70 - 0.55; // 0.1499999999999999
            assertThat(rawDifference).isNotEqualTo(0.15); // Demonstrating the raw IEEE 754 artifact
            assertThat(rawDifference).isLessThan(0.15);

            // HarnessOptimizerService applies Math.round(diff * 100.0) / 100.0
            double roundedDelta = Math.round(rawDifference * 100.0) / 100.0;
            assertThat(roundedDelta).isEqualTo(0.15);
            assertThat(roundedDelta >= 0.15).isTrue(); // Safely passes adoption threshold
        }
    }

    // =========================================================================
    // 3. ZERO POLLUTION & PERSISTENCE SAFETY
    // =========================================================================
    @Nested
    @DisplayName("3. Zero Pollution & Persistence Safety")
    class ZeroPollutionSafetyTests {

        @Test
        @DisplayName("Multiple consecutive KEEP_BASE runs leave learned_rules completely untouched (0 saves)")
        void testMultipleKeepBaseRunsLeaveLearnedRulesUntouched() {
            String optimalReply =
                    "Dạ chi phí liệu trình bên em rất linh hoạt chỉ từ 650.000đ/buổi tùy theo tình trạng da của Anh, đã bao gồm Bác sĩ soi da 3D miễn phí ạ.\n\n"
                    + "Hiện tại Anh đang muốn ưu tiên xử lý mụn viêm hay mụn ẩn trước để em tư vấn phác đồ chuẩn nhất ạ?";

            for (int i = 0; i < 20; i++) {
                HarnessOptimizeRequest req = new HarnessOptimizeRequest(
                        PAGE_ID, "tư vấn giúp em " + i, optimalReply, 0
                );
                HarnessOptimizeResponse resp = defaultOptimizer.optimize(req);
                assertThat(resp.verdict()).isEqualTo("KEEP_BASE");
            }

            // Exactly 0 calls to save on ruleRepository
            verify(ruleRepository, never()).save(any(LearnedRuleEntity.class));
            // Exactly 20 audit runs saved to runRepository
            verify(runRepository, times(20)).save(any(HarnessRunEntity.class));
        }

        @Test
        @DisplayName("ADOPTED rule has always_on = false and correct 3-part schema")
        void testAdoptedRuleHasAlwaysOnFalse() {
            HarnessOptimizeRequest req = new HarnessOptimizeRequest(
                    PAGE_ID, "ừ để xem thêm đã", SUBOPTIMAL_BASE_REPLY, -20
            );

            HarnessOptimizeResponse resp = defaultOptimizer.optimize(req);
            assertThat(resp.verdict()).isEqualTo("ADOPTED");

            LearnedRuleDto rule = resp.newExperienceRule();
            assertThat(rule).isNotNull();
            assertThat(rule.alwaysOn()).isFalse();
            assertThat(rule.cueSignal()).isNotBlank();
            assertThat(rule.generalizedRule()).isNotBlank();
            assertThat(rule.contrastiveEvidence()).contains("❌ Before:").contains("✅ After:");
        }
    }
}
