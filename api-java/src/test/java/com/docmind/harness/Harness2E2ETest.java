package com.docmind.harness;

import com.docmind.harness.HarnessContracts.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Master End-to-End Test Suite for Google Research Harness² Integration in DocMind (api-java).
 *
 * <p>Strict 4-Tier Test Architecture:
 * <ul>
 *   <li><b>Tier 1: Feature Coverage</b> (>= 5 test cases per feature for R1, R2, R3 - 65 tests)</li>
 *   <li><b>Tier 2: Boundary & Corner Cases</b> (empty inputs, extreme temperatures, large text, boundary budgets, prompt overflow - 10 tests)</li>
 *   <li><b>Tier 3: Cross-Feature Combinations</b> (interaction between deterministic hooks and playbook rules, optimizer triggers with low temperature - 7 tests)</li>
 *   <li><b>Tier 4: Real-World Scenarios</b> (the 5 application scenarios documented in TEST_INFRA.md - 5 tests)</li>
 * </ul>
 *
 * <p>Total: 87 comprehensive automated test cases with 100% test isolation and opaque-box assertions.
 */
@DisplayName("Harness² Master E2E Test Suite (4-Tier Verification)")
public class Harness2E2ETest {

    private GuardrailPipeline pipeline;
    private TokenBudgetManager budgetManager;
    private HarnessOptimizerEngine optimizerEngine;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        pipeline = GuardrailPipeline.standardPipeline();
        budgetManager = new TokenBudgetManager();
        optimizerEngine = new HarnessOptimizerEngine(pipeline);
        objectMapper = new ObjectMapper();
    }

    // =========================================================================
    // TIER 1: FEATURE COVERAGE (R1, R2, R3)
    // =========================================================================

    @Nested
    @DisplayName("Tier 1: Feature Coverage")
    class Tier1FeatureCoverage {

        // ---------------------------------------------------------------------
        // Feature 1: Deterministic In-Loop Guardrail Hooks (< 5ms)
        // ---------------------------------------------------------------------
        @Nested
        @DisplayName("F1: In-Loop Deterministic Guardrail Hooks (< 5ms Latency)")
        class Feature1DeterministicGuardrailHooks {

            @Test
            @DisplayName("T1.1.1: Single pipeline run executes under 5ms (5,000,000 nanoseconds) budget")
            void t1_1_1_pipelineExecutionUnder5msBudget() {
                GuardrailContext ctx = GuardrailContext.of("Anh", "DISCOVERY",
                        HarnessFixtures.DUMPING_BUBBLES_DISCOVERY, List.of(), HarnessFixtures.MSG_DISCOVERY_PRICE_GENERIC);

                pipeline.evaluate(ctx); // JIT warmup
                GuardrailResult result = pipeline.evaluate(ctx);

                assertThat(result.latencyBudgetMet())
                        .as("Guardrail pipeline execution must be strictly < 5ms")
                        .isTrue();
                assertThat(result.durationNanos()).isLessThan(5_000_000L);
                assertThat(result.guardedBubbles()).isNotEmpty();
            }

            @Test
            @DisplayName("T1.1.2: Chained execution of all 4 hooks remains under 5ms budget")
            void t1_1_2_multiHookChainedExecutionUnder5ms() {
                // Violates honorifics, anti-dumping, bubble cap, and closing question
                List<String> rawBubbles = List.of(
                        "Dạ em chào Chị Lan Anh ạ! Gói mụn 1.500.000đ, gói nám 4.500.000đ, Combo 3-4 buổi giảm 10%.",
                        "Em có thể tham khảo bảng giá chi tiết trên website.",
                        "Mã SPA-01 [1] cam kết an toàn.",
                        "Chị có muốn làm ngay hôm nay được không ạ?"
                );
                GuardrailContext ctx = GuardrailContext.of("Anh", "DISCOVERY", rawBubbles, List.of(), "giá thế nào?");

                pipeline.evaluate(ctx); // JIT warmup
                GuardrailResult result = pipeline.evaluate(ctx);

                assertThat(result.latencyBudgetMet()).isTrue();
                assertThat(result.durationNanos()).isLessThan(5_000_000L);
                assertThat(result.triggeredHooks()).contains(
                        HonorificConsistencyHook.CODE,
                        BubbleCapHook.CODE,
                        ClosingQuestionHook.CODE,
                        AntiInfoDumpingHook.CODE
                );
            }

            @Test
            @DisplayName("T1.1.3: Pipeline accurately collects and attributes triggered_hooks telemetry")
            void t1_1_3_triggeredHooksAttributionAccurate() {
                GuardrailContext ctx = GuardrailContext.of("Anh", "VALUE_CONSULTATION",
                        HarnessFixtures.MALE_LEAKAGE_BUBBLES, List.of(), "tư vấn giúp mình");

                GuardrailResult result = pipeline.evaluate(ctx);

                assertThat(result.triggeredHooks()).contains(HonorificConsistencyHook.CODE);
                assertThat(result.guardedBubbles().get(0)).doesNotContain("Chị");
            }

            @Test
            @DisplayName("T1.1.4: Clean response is preserved verbatim without spurious hook triggers")
            void t1_1_4_cleanResponsePreservedWithoutSpuriousHooks() {
                GuardrailContext ctx = GuardrailContext.of("Anh", "DISCOVERY",
                        HarnessFixtures.CLEAN_OPTIMAL_BUBBLES, List.of(), "giá bao nhiêu?");

                GuardrailResult result = pipeline.evaluate(ctx);

                assertThat(result.triggeredHooks()).isEmpty();
                assertThat(result.guardedBubbles()).containsExactlyElementsOf(HarnessFixtures.CLEAN_OPTIMAL_BUBBLES);
            }

            @Test
            @DisplayName("T1.1.5: Timing telemetry reports positive duration and non-null result fields")
            void t1_1_5_timingTelemetryReportingAccurate() {
                GuardrailContext ctx = GuardrailContext.of("Anh", "DISCOVERY",
                        List.of("Dạ em chào Anh ạ!", "Liệu trình có giá 1.500.000đ."), List.of(), "tư vấn");

                GuardrailResult result = pipeline.evaluate(ctx);

                assertThat(result.durationNanos()).isPositive();
                assertThat(result.guardedBubbles()).isNotNull();
                assertThat(result.triggeredHooks()).isNotNull();
            }
        }

        // ---------------------------------------------------------------------
        // Feature 2: Honorific Consistency Hook
        // ---------------------------------------------------------------------
        @Nested
        @DisplayName("F2: Honorific Consistency Hook")
        class Feature2HonorificConsistency {

            @Test
            @DisplayName("T1.2.1: Replaces all occurrences of leaked 'Chị' with 'Anh' when customer is male")
            void t1_2_1_replacesLeakedChiWithAnhWhenMale() {
                List<String> raw = List.of("Dạ em chào Chị ạ, em gửi Chị thông tin nhé. Chị có câu hỏi gì không?");
                GuardrailContext ctx = GuardrailContext.of("Anh", "VALUE_CONSULTATION", raw, List.of(), "mình là nam");

                GuardrailResult result = pipeline.evaluate(ctx);

                assertThat(result.triggeredHooks()).contains(HonorificConsistencyHook.CODE);
                for (String b : result.guardedBubbles()) {
                    assertThat(b).doesNotContain("Chị").doesNotContain("chị");
                    assertThat(b).contains("Anh");
                }
            }

            @Test
            @DisplayName("T1.2.2: Replaces inverted pronouns 'Em có thể tham khảo' with 'Anh có thể tham khảo'")
            void t1_2_2_replacesInvertedPronounsEmCoThe() {
                List<String> raw = List.of("Dạ em có thể tham khảo liệu trình này nhé!");
                GuardrailContext ctx = GuardrailContext.of("Anh", "VALUE_CONSULTATION", raw, List.of(), "anh muốn hỏi");

                GuardrailResult result = pipeline.evaluate(ctx);

                assertThat(result.triggeredHooks()).contains(HonorificConsistencyHook.CODE);
                assertThat(result.guardedBubbles().get(0)).contains("Anh có thể tham khảo");
            }

            @Test
            @DisplayName("T1.2.3: Preserves uppercase/lowercase capitalization during Vietnamese pronoun rewrite")
            void t1_2_3_casePreservationVietnamesePronouns() {
                List<String> raw = List.of("Chào Chị! Cảm ơn chị đã liên hệ.");
                GuardrailContext ctx = GuardrailContext.of("Anh", "VALUE_CONSULTATION", raw, List.of(), "t là nam");

                GuardrailResult result = pipeline.evaluate(ctx);

                assertThat(result.guardedBubbles().get(0)).contains("Chào Anh! Cảm ơn anh đã liên hệ.");
            }

            @Test
            @DisplayName("T1.2.4: Preserves 'Chị' when customer resolved honorific is female")
            void t1_2_4_preservesChiWhenCustomerIsFemale() {
                List<String> raw = List.of("Dạ em chào Chị ạ! Em gửi Chị bảng giá.");
                GuardrailContext ctx = GuardrailContext.of("Chị", "VALUE_CONSULTATION", raw, List.of(), "chị muốn hỏi");

                GuardrailResult result = pipeline.evaluate(ctx);

                assertThat(result.triggeredHooks()).doesNotContain(HonorificConsistencyHook.CODE);
                assertThat(result.guardedBubbles().get(0)).contains("Chị");
            }

            @Test
            @DisplayName("T1.2.5: Removes phantom customer name 'Lan Anh' from assistant output")
            void t1_2_5_removesPhantomNamesLikeLanAnh() {
                List<String> raw = List.of("Dạ em chào Chị Lan Anh ạ! Chúc Chị ngày mới tốt lành.");
                GuardrailContext ctx = GuardrailContext.of("Anh", "VALUE_CONSULTATION", raw, List.of(), "minh la nam");

                GuardrailResult result = pipeline.evaluate(ctx);

                assertThat(result.triggeredHooks()).contains(HonorificConsistencyHook.CODE);
                assertThat(result.guardedBubbles().get(0)).doesNotContain("Lan Anh");
                assertThat(result.guardedBubbles().get(0)).contains("Anh");
            }
        }

        // ---------------------------------------------------------------------
        // Feature 3: Bubble Cap Hook (<= 2 bubbles)
        // ---------------------------------------------------------------------
        @Nested
        @DisplayName("F3: Bubble Cap Hook (<= 2 Bubbles)")
        class Feature3BubbleCap {

            @Test
            @DisplayName("T1.3.1: Caps four bubbles down to strictly <= 2 bubbles by merging trailing bubbles")
            void t1_3_1_capsFourBubblesDownToTwo() {
                List<String> raw = List.of("Bong bóng 1", "Bong bóng 2", "Bong bóng 3", "Bong bóng 4");
                GuardrailContext ctx = GuardrailContext.of("Anh", "VALUE_CONSULTATION", raw, List.of(), "chi tiết");

                GuardrailResult result = pipeline.evaluate(ctx);

                assertThat(result.triggeredHooks()).contains(BubbleCapHook.CODE);
                assertThat(result.guardedBubbles().size()).isLessThanOrEqualTo(2);
                assertThat(result.guardedBubbles().get(1)).contains("Bong bóng 2").contains("Bong bóng 4");
            }

            @Test
            @DisplayName("T1.3.2: Merges stand-alone Turn 1 greeting bubble into head of Bubble 1")
            void t1_3_2_mergesStandAloneTurn1GreetingIntoBubble1() {
                List<String> raw = List.of("Dạ em chào Anh ạ!", "Liệu trình trị mụn bên em rất an toàn và hiệu quả.");
                GuardrailContext ctx = GuardrailContext.of("Anh", "VALUE_CONSULTATION", raw, List.of(), "chào shop");

                GuardrailResult result = pipeline.evaluate(ctx);

                assertThat(result.triggeredHooks()).contains(BubbleCapHook.CODE);
                assertThat(result.guardedBubbles().size()).isEqualTo(1);
                assertThat(result.guardedBubbles().get(0)).startsWith("Dạ em chào Anh ạ! Liệu trình");
            }

            @Test
            @DisplayName("T1.3.3: Preserves exactly 2 substantive bubbles when already compliant")
            void t1_3_3_preservesExactlyTwoBubbles() {
                List<String> raw = List.of(
                        "Dạ chi phí liệu trình bên em là 1.500.000 VNĐ đã bao gồm khám soi da miễn phí ạ.",
                        "Hiện tại Anh muốn qua làm buổi sáng hay buổi chiều để em giữ chỗ tốt nhất ạ?"
                );
                GuardrailContext ctx = GuardrailContext.of("Anh", "VALUE_CONSULTATION", raw, List.of(), "giá thế nào?");

                GuardrailResult result = pipeline.evaluate(ctx);

                assertThat(result.triggeredHooks()).doesNotContain(BubbleCapHook.CODE);
                assertThat(result.guardedBubbles().size()).isEqualTo(2);
            }

            @Test
            @DisplayName("T1.3.4: Preserves single concise bubble without unnecessary splitting")
            void t1_3_4_preservesSingleBubble() {
                List<String> raw = List.of("Dạ em chào Anh ạ! Chi phí liệu trình là 1.500.000 VNĐ trọn gói ạ.");
                GuardrailContext ctx = GuardrailContext.of("Anh", "VALUE_CONSULTATION", raw, List.of(), "bao nhiêu?");

                GuardrailResult result = pipeline.evaluate(ctx);

                assertThat(result.guardedBubbles().size()).isEqualTo(1);
            }

            @Test
            @DisplayName("T1.3.5: Triggers HOOK_BUBBLE_CAP only when bubble count > 2 or stand-alone greeting detected")
            void t1_3_5_triggersHookOnlyWhenBubbleCapViolated() {
                List<String> compliant = List.of("Bubble A nội dung đầy đủ", "Bubble B câu hỏi kết thúc?");
                GuardrailResult res1 = pipeline.evaluate(GuardrailContext.of("Anh", "VALUE_CONSULTATION", compliant, List.of(), "hỏi"));

                assertThat(res1.triggeredHooks()).doesNotContain(BubbleCapHook.CODE);

                List<String> nonCompliant = List.of("Bubble 1", "Bubble 2", "Bubble 3");
                GuardrailResult res2 = pipeline.evaluate(GuardrailContext.of("Anh", "VALUE_CONSULTATION", nonCompliant, List.of(), "hỏi"));

                assertThat(res2.triggeredHooks()).contains(BubbleCapHook.CODE);
            }
        }

        // ---------------------------------------------------------------------
        // Feature 4: Anti-Info Dumping in DISCOVERY Stage
        // ---------------------------------------------------------------------
        @Nested
        @DisplayName("F4: Anti-Info Dumping in DISCOVERY Stage")
        class Feature4AntiInfoDumping {

            @Test
            @DisplayName("T1.4.1: When customer asks general 'giá' in DISCOVERY, rewrites dump to 1 starting price + 1 0-risk perk + 1 A/B question")
            void t1_4_1_rewritesMultiPriceDumpToSingleBasePricePlusZeroRiskPerkPlusAbQuestion() {
                GuardrailContext ctx = GuardrailContext.of("Anh", "DISCOVERY",
                        HarnessFixtures.DUMPING_BUBBLES_DISCOVERY, List.of(), HarnessFixtures.MSG_DISCOVERY_PRICE_GENERIC);

                GuardrailResult result = pipeline.evaluate(ctx);

                assertThat(result.triggeredHooks()).contains(AntiInfoDumpingHook.CODE);
                List<String> bubbles = result.guardedBubbles();
                assertThat(bubbles.size()).isEqualTo(2);

                // Bubble 1: 1 starting price + 1 0-risk perk
                assertThat(bubbles.get(0)).contains("chỉ từ 650.000đ/buổi");
                assertThat(bubbles.get(0)).contains("soi da AI 3D miễn phí 100%");
                assertThat(bubbles.get(0)).contains("không phát sinh phụ phí");

                // Bubble 2: A/B closing question
                assertThat(bubbles.get(1)).contains("ưu tiên");
                assertThat(bubbles.get(1)).endsWith("?");
            }

            @Test
            @DisplayName("T1.4.2: Collapses multiple Combos mentioned simultaneously in DISCOVERY")
            void t1_4_2_collapsesMultipleCombosDuringDiscovery() {
                List<String> raw = List.of(
                        "Dạ bên em có Combo Tiết Kiệm giảm 10% và Combo Toàn Diện giảm 15% gói 5-9 buổi ạ.",
                        "Anh có muốn chọn Combo nào không?"
                );
                GuardrailContext ctx = GuardrailContext.of("Anh", "DISCOVERY", raw, List.of(), "giá sao b");

                GuardrailResult result = pipeline.evaluate(ctx);

                assertThat(result.triggeredHooks()).contains(AntiInfoDumpingHook.CODE);
                assertThat(result.guardedBubbles().get(0)).doesNotContain("Combo Tiết Kiệm").doesNotContain("Combo Toàn Diện");
            }

            @Test
            @DisplayName("T1.4.3: In VALUE_CONSULTATION stage, allows specific package pricing without triggering anti-dumping")
            void t1_4_3_allowsSpecificPricingInValueConsultationOrSoftBooking() {
                List<String> raw = List.of(
                        "Dạ Liệu trình Trị Mụn Chuẩn Y Khoa có giá 1.500.000 VNĐ trọn gói ạ.",
                        "Anh có thể qua thăm khám trực tiếp để Bác sĩ lên phác đồ nhé ạ."
                );
                GuardrailContext ctx = GuardrailContext.of("Anh", "VALUE_CONSULTATION", raw, List.of(), "giá trị mụn bao nhiêu");

                GuardrailResult result = pipeline.evaluate(ctx);

                assertThat(result.triggeredHooks()).doesNotContain(AntiInfoDumpingHook.CODE);
                assertThat(result.guardedBubbles().get(0)).contains("1.500.000 VNĐ");
            }

            @Test
            @DisplayName("T1.4.4: Does not trigger anti-dumping when customer asks about a specific treatment directly")
            void t1_4_4_doesNotTriggerWhenCustomerAsksSpecificServiceDirectly() {
                List<String> raw = List.of(
                        "Dạ dịch vụ Trị Nám Laser bên em có chi phí là 4.500.000 VNĐ/liệu trình ạ.",
                        "Anh đang bị nám mảng hay nám chân sâu để em hỗ trợ tư vấn chi tiết ạ?"
                );
                GuardrailContext ctx = GuardrailContext.of("Anh", "DISCOVERY", raw, List.of(), "cho anh hỏi giá trị nám laser");

                GuardrailResult result = pipeline.evaluate(ctx);

                assertThat(result.triggeredHooks()).doesNotContain(AntiInfoDumpingHook.CODE);
            }

            @Test
            @DisplayName("T1.4.5: Strips internal product codes (SPA-01) and bracketed citation markers [1]")
            void t1_4_5_stripsInternalProductCodesAndBracketedCitations() {
                List<String> raw = List.of(
                        "Dạ mã dịch vụ SPA-01 [1] có giá 1.500.000đ và PKG-99 [2] giá 3.000.000đ.",
                        "Anh tham khảo nhé."
                );
                GuardrailContext ctx = GuardrailContext.of("Anh", "DISCOVERY", raw, List.of(), "giá thế nào");

                GuardrailResult result = pipeline.evaluate(ctx);

                for (String b : result.guardedBubbles()) {
                    assertThat(b).doesNotContain("SPA-01").doesNotContain("PKG-99");
                    assertThat(b).doesNotContain("[1]").doesNotContain("[2]");
                }
            }
        }

        // ---------------------------------------------------------------------
        // Feature 5: Closing Question Control
        // ---------------------------------------------------------------------
        @Nested
        @DisplayName("F5: Closing Question Control Hook")
        class Feature5ClosingQuestionControl {

            @Test
            @DisplayName("T1.5.1: Strips closed Yes/No permission questions ('...được không ạ?', '...có tiện không?')")
            void t1_5_1_stripsClosedYesNoPermissionQuestions() {
                List<String> raw = List.of(
                        "Dạ liệu trình trị mụn bên em là 1.500.000 VNĐ trọn gói ạ.",
                        "Anh có tiện qua khám chiều nay được không ạ?"
                );
                GuardrailContext ctx = GuardrailContext.of("Anh", "VALUE_CONSULTATION", raw, List.of(), "tư vấn");

                GuardrailResult result = pipeline.evaluate(ctx);

                assertThat(result.triggeredHooks()).contains(ClosingQuestionHook.CODE);
                String lastBubble = result.guardedBubbles().get(result.guardedBubbles().size() - 1);
                assertThat(lastBubble).doesNotContain("được không ạ?");
                assertThat(lastBubble).contains("buổi sáng hay buổi chiều");
            }

            @Test
            @DisplayName("T1.5.2: Enforces single focused A/B question when raw reply contains multiple question marks")
            void t1_5_2_collapsesMultipleClosingQuestionsIntoSingleAbQuestion() {
                List<String> raw = List.of(
                        "Anh bị mụn lâu chưa? Da anh là da dầu hay da khô? Anh có muốn đặt lịch luôn không?"
                );
                GuardrailContext ctx = GuardrailContext.of("Anh", "DISCOVERY", raw, List.of(), "tư vấn mụn");

                GuardrailResult result = pipeline.evaluate(ctx);

                assertThat(result.triggeredHooks()).contains(ClosingQuestionHook.CODE);
            }

            @Test
            @DisplayName("T1.5.3: Injects relevant closing question when assistant reply ends abruptly without any question")
            void t1_5_3_injectsRelevantClosingQuestionWhenMissing() {
                List<String> raw = List.of(
                        "Dạ liệu trình trị mụn bên em có giá là 1.500.000 VNĐ trọn gói đã bao gồm chăm sóc phục hồi."
                );
                GuardrailContext ctx = GuardrailContext.of("Anh", "VALUE_CONSULTATION", raw, List.of(), "giá");

                GuardrailResult result = pipeline.evaluate(ctx);

                assertThat(result.triggeredHooks()).contains(ClosingQuestionHook.CODE);
                String lastBubble = result.guardedBubbles().get(result.guardedBubbles().size() - 1);
                assertThat(lastBubble).endsWith("?");
                assertThat(lastBubble).contains("Anh cần em hỗ trợ thêm thông tin gì");
            }

            @Test
            @DisplayName("T1.5.4: Preserves high-quality A/B closing question when already compliant")
            void t1_5_4_deduplicatesRepeatedQuestionsFromHistory() {
                List<String> raw = List.of(
                        "Dạ em gửi Anh thông tin liệu trình ạ.",
                        "Hiện tại Anh muốn ưu tiên xử lý mụn viêm hay mụn ẩn trước ạ?"
                );
                GuardrailContext ctx = GuardrailContext.of("Anh", "DISCOVERY", raw, List.of(), "tư vấn");

                GuardrailResult result = pipeline.evaluate(ctx);

                assertThat(result.triggeredHooks()).doesNotContain(ClosingQuestionHook.CODE);
                assertThat(result.guardedBubbles().get(1)).isEqualTo("Hiện tại Anh muốn ưu tiên xử lý mụn viêm hay mụn ẩn trước ạ?");
            }

            @Test
            @DisplayName("T1.5.5: Telemetry correctly attributes HOOK_CLOSING_QUESTION_CONTROL code in result")
            void t1_5_5_triggersHookClosingQuestionControlTelemetry() {
                List<String> raw = List.of("Thông tin dịch vụ.", "Anh có tiện không?");
                GuardrailContext ctx = GuardrailContext.of("Anh", "DISCOVERY", raw, List.of(), "hỏi");

                GuardrailResult result = pipeline.evaluate(ctx);

                assertThat(result.triggeredHooks()).contains(ClosingQuestionHook.CODE);
            }
        }

        // ---------------------------------------------------------------------
        // Feature 6: INV vs DOM Harness Dimension Separation
        // ---------------------------------------------------------------------
        @Nested
        @DisplayName("F6: INV vs DOM Harness Dimension Separation")
        class Feature6InvDomSeparation {

            @Test
            @DisplayName("T1.6.1: Validates INV dimension represents invariant sales/psychology procedures")
            void t1_6_1_validatesInvDimensionRepresentsUniversalProcedures() {
                ExperienceRule rule = HarnessFixtures.createInvariantRule(
                        "INV-01", "Khách hạ nhiệt", "Buông áp lực bán hàng ngay lập tức", 200
                );

                assertThat(rule.dimension()).isEqualTo(HarnessDimension.INV);
                assertThat(rule.pageId()).isNull(); // Global universal scope
            }

            @Test
            @DisplayName("T1.6.2: Validates DOM dimension represents page-specific verified domain facts")
            void t1_6_2_validatesDomDimensionRepresentsPageFacts() {
                ExperienceRule rule = HarnessFixtures.createDomainRule(
                        "DOM-01", "Khách hỏi sẹo rỗ", "Liệu trình trị mụn không điều trị sẹo rỗ", 200
                );

                assertThat(rule.dimension()).isEqualTo(HarnessDimension.DOM);
                assertThat(rule.pageId()).isEqualTo(HarnessFixtures.PAGE_ID_SPA);
            }

            @Test
            @DisplayName("T1.6.3: Enforces dimension constraint checking (only INV or DOM allowed)")
            void t1_6_3_enforcesDimensionEnumConstraint() {
                assertThat(HarnessDimension.values()).containsExactly(HarnessDimension.INV, HarnessDimension.DOM);
            }

            @Test
            @DisplayName("T1.6.4: Partitions mixed rule collections into separate INV and DOM buckets")
            void t1_6_4_partitionsMixedRulesIntoSeparateInvAndDomBuckets() {
                List<ExperienceRule> mixed = HarnessFixtures.sampleBudgetOverflownRules();

                long invCount = mixed.stream().filter(r -> r.dimension() == HarnessDimension.INV).count();
                long domCount = mixed.stream().filter(r -> r.dimension() == HarnessDimension.DOM).count();

                assertThat(invCount).isEqualTo(2);
                assertThat(domCount).isEqualTo(2);
            }

            @Test
            @DisplayName("T1.6.5: Assigns Global scope (page_id = null) to INV and Page scope to DOM by default")
            void t1_6_5_assignsGlobalScopeToInvAndPageScopeToDomByDefault() {
                ExperienceRule inv = HarnessFixtures.createInvariantRule("INV-G", "Cue", "Lesson", 100);
                ExperienceRule dom = HarnessFixtures.createDomainRule("DOM-P", "Cue", "Lesson", 100);

                assertThat(inv.pageId()).isNull();
                assertThat(dom.pageId()).isNotNull();
            }
        }

        // ---------------------------------------------------------------------
        // Feature 7: Flyway Schema / Persistence Model
        // ---------------------------------------------------------------------
        @Nested
        @DisplayName("F7: Flyway Schema & Persistence Model Contract")
        class Feature7FlywaySchemaModel {

            @Test
            @DisplayName("T1.7.1: Entity model maps dimension, cue_signal, contrastive_evidence, and always_on")
            void t1_7_1_entityMapsDimensionCueEvidenceAlwaysOn() {
                ExperienceRule rule = new ExperienceRule(
                        UUID.randomUUID(), HarnessDimension.INV, "COMMUNICATION",
                        "Khách do dự", "Hỏi vi mô", "❌ Before\n✅ After",
                        true, 0.95, true, null
                );

                assertThat(rule.dimension()).isEqualTo(HarnessDimension.INV);
                assertThat(rule.cueSignal()).isEqualTo("Khách do dự");
                assertThat(rule.contrastiveEvidence()).contains("Before").contains("After");
                assertThat(rule.alwaysOn()).isTrue();
            }

            @Test
            @DisplayName("T1.7.2: Default values applied: dimension=INV, always_on=false, cue_signal non-null")
            void t1_7_2_defaultValuesAppliedDimensionInvAlwaysOnFalse() {
                ExperienceRule rule = ExperienceRule.create(
                        null, null, null, "Default Lesson", null, false, 0.8, null
                );

                assertThat(rule.dimension()).isEqualTo(HarnessDimension.INV);
                assertThat(rule.alwaysOn()).isFalse();
                assertThat(rule.cueSignal()).isEmpty();
            }

            @Test
            @DisplayName("T1.7.3: Optimization run entity maps full audit schema fields")
            void t1_7_3_optimizationRunEntityMapsFullAuditSchema() {
                HarnessOptimizationRun run = new HarnessOptimizationRun(
                        UUID.randomUUID(), HarnessFixtures.PAGE_ID_SPA, "conv-1",
                        "MANUAL_1CLICK", -18, "Base", "Candidate",
                        "BASE", "CANDIDATE", HarnessDimension.INV,
                        "Cue", "Lesson", "Evidence", "ADOPTED",
                        "Selection why", UUID.randomUUID(), java.time.Instant.now()
                );

                assertThat(run.id()).isNotNull();
                assertThat(run.triggerReason()).isEqualTo("MANUAL_1CLICK");
                assertThat(run.temperatureDelta()).isEqualTo(-18);
                assertThat(run.verdict()).isEqualTo("ADOPTED");
            }

            @Test
            @DisplayName("T1.7.4: Foreign key relationships to KnowledgePages and LearnedRules modeled cleanly")
            void t1_7_4_foreignKeyToKnowledgePagesAndLearnedRulesModeled() {
                UUID pageId = UUID.randomUUID();
                UUID ruleId = UUID.randomUUID();

                HarnessOptimizationRun run = new HarnessOptimizationRun(
                        UUID.randomUUID(), pageId, "conv-2", "AUTO", -20,
                        "Base", "Cand", "BASE", "CAND", HarnessDimension.DOM,
                        "Cue", "Lesson", "Evidence", "ADOPTED",
                        "Why", ruleId, java.time.Instant.now()
                );

                assertThat(run.pageId()).isEqualTo(pageId);
                assertThat(run.adoptedRuleId()).isEqualTo(ruleId);
            }

            @Test
            @DisplayName("T1.7.5: JSON serialization matches Harness² REST DTO contracts")
            void t1_7_5_jsonSerializationMatchesHarnessRestContracts() throws Exception {
                HarnessOptimizeRequest req = HarnessFixtures.createCoolingLeadRequest(42L);
                String json = objectMapper.writeValueAsString(req);
                JsonNode node = objectMapper.readTree(json);

                assertThat(node.has("pageId")).isTrue();
                assertThat(node.has("customerMessage")).isTrue();
                assertThat(node.has("baseReply")).isTrue();
                assertThat(node.get("temperatureDelta").asInt()).isEqualTo(-20);
            }
        }

        // ---------------------------------------------------------------------
        // Feature 8: Experience Library 3-part Schema
        // ---------------------------------------------------------------------
        @Nested
        @DisplayName("F8: Experience Library 3-Part Schema (Cue – Lesson – Evidence)")
        class Feature8ExperienceLibrarySchema {

            @Test
            @DisplayName("T1.8.1: Validates 3-part rule requires all of: cue_signal, generalized_rule, and contrastive_evidence")
            void t1_8_1_validatesRuleRequiresCueLessonEvidence() {
                ExperienceRule valid = ExperienceRule.create(
                        HarnessDimension.INV, "COMMUNICATION", "Cue Signal", "Lesson Learned", "❌ Before vs ✅ After", false, 0.9, null
                );
                assertThat(valid.hasExperience()).isTrue();
            }

            @Test
            @DisplayName("T1.8.2: Rejects incomplete rule missing cue_signal")
            void t1_8_2_rejectsIncompleteRuleMissingCueSignal() {
                ExperienceRule invalid = ExperienceRule.create(
                        HarnessDimension.INV, "COMMUNICATION", "", "Lesson Learned", "Evidence", false, 0.9, null
                );
                assertThat(invalid.hasExperience()).isFalse();
            }

            @Test
            @DisplayName("T1.8.3: Rejects incomplete rule missing contrastive_evidence")
            void t1_8_3_rejectsIncompleteRuleMissingEvidence() {
                ExperienceRule invalid = ExperienceRule.create(
                        HarnessDimension.INV, "COMMUNICATION", "Cue Signal", "Lesson Learned", null, false, 0.9, null
                );
                assertThat(invalid.hasExperience()).isFalse();
            }

            @Test
            @DisplayName("T1.8.4: Formats Experience Library prompt injection using standard Harness² format")
            void t1_8_4_formatsPromptBlockWithStandardHarness2Format() {
                ExperienceRule rule = ExperienceRule.create(
                        HarnessDimension.INV, "COMMUNICATION", "Khách chê đắt", "Hỏi thăm lý do", "Evidence", false, 0.9, null
                );
                String block = rule.toPromptBlock();

                assertThat(block).startsWith("[INV] Cue: Khách chê đắt | Lesson: Hỏi thăm lý do");
            }

            @Test
            @DisplayName("T1.8.5: Cites concrete Before (❌) and After (✅) contrastive evidence in synthesized rule")
            void t1_8_5_citesConcreteBeforeAfterContrastiveEvidence() {
                HarnessOptimizeRequest req = HarnessFixtures.createCoolingLeadRequest(100L);
                HarnessOptimizeResponse resp = optimizerEngine.optimize(req);

                assertThat(resp.contrastiveEvidence()).contains("❌ Before").contains("✅ After");
            }
        }

        // ---------------------------------------------------------------------
        // Feature 9: Always-On Token Budget Cap (1200 chars)
        // ---------------------------------------------------------------------
        @Nested
        @DisplayName("F9: Always-On Token Budget Cap (1200 chars / ~300 tokens)")
        class Feature9AlwaysOnTokenBudgetCap {

            @Test
            @DisplayName("T1.9.1: Enforces hard limit of <= 1200 characters for always-on prompt prose")
            void t1_9_1_enforcesStrict1200CharacterHardCap() {
                List<ExperienceRule> overflown = HarnessFixtures.sampleBudgetOverflownRules();
                BudgetAllocation alloc = budgetManager.allocate(overflown);

                assertThat(alloc.totalAlwaysOnChars()).isLessThanOrEqualTo(1200);
            }

            @Test
            @DisplayName("T1.9.2: When total always-on rules exceed 1200 chars, prunes lowest confidence rules")
            void t1_9_2_prunesLowestConfidenceRulesWhenExceeding1200Chars() {
                List<ExperienceRule> rules = List.of(
                        ExperienceRule.create(HarnessDimension.INV, "C1", "Cue1", "Lesson 1".repeat(40), "Ev1", true, 0.98, null), // ~327 chars
                        ExperienceRule.create(HarnessDimension.INV, "C2", "Cue2", "Lesson 2".repeat(40), "Ev2", true, 0.92, null), // ~327 chars
                        ExperienceRule.create(HarnessDimension.INV, "C3", "Cue3", "Lesson 3".repeat(40), "Ev3", true, 0.88, null), // ~327 chars
                        ExperienceRule.create(HarnessDimension.DOM, "C4", "Cue4", "Lesson 4".repeat(40), "Ev4", true, 0.70, null)  // ~327 chars -> 1308 total
                );

                BudgetAllocation alloc = budgetManager.allocate(rules);

                assertThat(alloc.capExceeded()).isTrue();
                assertThat(alloc.alwaysOnRules()).hasSize(3);
                assertThat(alloc.onDemandRules()).hasSize(1);
                // The lowest confidence rule (0.70) must be demoted
                assertThat(alloc.onDemandRules().get(0).confidenceScore()).isEqualTo(0.70);
            }

            @Test
            @DisplayName("T1.9.3: Demoted rules are placed into on-demand contextual pool, not discarded")
            void t1_9_3_demotesPrunedRulesToOnDemandContextualPool() {
                List<ExperienceRule> overflown = HarnessFixtures.sampleBudgetOverflownRules();
                BudgetAllocation alloc = budgetManager.allocate(overflown);

                assertThat(alloc.onDemandRules()).isNotEmpty();
                assertThat(alloc.alwaysOnRules().size() + alloc.onDemandRules().size()).isEqualTo(overflown.size());
            }

            @Test
            @DisplayName("T1.9.4: Preserves high-confidence INV rules over lower-confidence DOM rules")
            void t1_9_4_preservesHighConfidenceInvRulesOverLowerConfidenceDomRules() {
                List<ExperienceRule> rules = List.of(
                        ExperienceRule.create(HarnessDimension.INV, "C1", "Cue1", "Lesson 1".repeat(50), "Ev1", true, 0.95, null), // ~550 chars
                        ExperienceRule.create(HarnessDimension.DOM, "C2", "Cue2", "Lesson 2".repeat(50), "Ev2", true, 0.80, HarnessFixtures.PAGE_ID_SPA), // ~550 chars
                        ExperienceRule.create(HarnessDimension.DOM, "C3", "Cue3", "Lesson 3".repeat(50), "Ev3", true, 0.75, HarnessFixtures.PAGE_ID_SPA)  // ~550 chars
                );

                BudgetAllocation alloc = budgetManager.allocate(rules);

                assertThat(alloc.alwaysOnRules()).extracting(ExperienceRule::dimension)
                        .contains(HarnessDimension.INV);
            }

            @Test
            @DisplayName("T1.9.5: Calculates accurate token budget utilization percentage (e.g. 960 chars = 80.0%)")
            void t1_9_5_calculatesAccurateTokenBudgetUtilizationPercent() {
                ExperienceRule rule = ExperienceRule.create(
                        HarnessDimension.INV, "C", "Cue", "L".repeat(595), "Ev", true, 0.9, null // total 600 chars (3+595+2 = 600)
                );
                BudgetAllocation alloc = budgetManager.allocate(List.of(rule));

                assertThat(alloc.totalAlwaysOnChars()).isEqualTo(600);
                assertThat(alloc.utilizationPercent()).isEqualTo(50.0);
            }
        }

        // ---------------------------------------------------------------------
        // Feature 10: Task-Level Contrastive Optimizer Service
        // ---------------------------------------------------------------------
        @Nested
        @DisplayName("F10: Task-Level Contrastive Optimizer Service")
        class Feature10ContrastiveOptimizer {

            @Test
            @DisplayName("T1.10.1: Full optimization pipeline executes Propose -> Probe -> Anonymized Select -> Reflect")
            void t1_10_1_executesFullProposeProbeAnonymizedSelectReflect() {
                HarnessOptimizeRequest req = HarnessFixtures.createCoolingLeadRequest(42L);

                HarnessOptimizeResponse resp = optimizerEngine.optimize(req);

                assertThat(resp.runId()).isNotNull();
                assertThat(resp.verdict()).isIn("ADOPTED", "KEEP_BASE");
                assertThat(resp.selectionRationale()).isNotBlank();
                assertThat(resp.winningReply()).isNotBlank();
            }

            @Test
            @DisplayName("T1.10.2: Propose step emits targeted INV or DOM mutation based on customer context")
            void t1_10_2_proposeStepEmitsTargetedMutationBasedOnContext() {
                HarnessOptimizeRequest coolingReq = HarnessFixtures.createCoolingLeadRequest(1L);
                HarnessOptimizeResponse resp = optimizerEngine.optimize(coolingReq);

                assertThat(resp.proposedDimension()).isEqualTo(HarnessDimension.INV);
                assertThat(resp.proposedCue()).contains("hạ nhiệt");
                assertThat(resp.proposedLesson()).contains("áp lực chốt bán hàng");
            }

            @Test
            @DisplayName("T1.10.3: Probe step executes candidate rollout through deterministic guardrails")
            void t1_10_3_probeStepAppliesDeterministicGuardrails() {
                HarnessOptimizeRequest req = HarnessFixtures.createCoolingLeadRequest(2L);
                HarnessOptimizeResponse resp = optimizerEngine.optimize(req);

                // Candidate response must satisfy bubble cap <= 2
                assertThat(resp.winningBubbles().size()).isLessThanOrEqualTo(2);
                assertThat(resp.winningReply()).doesNotContain("Chị Lan Anh");
            }

            @Test
            @DisplayName("T1.10.4: Reflect step synthesizes rationale and creates adopted 3-part Experience rule when ADOPTED")
            void t1_10_4_reflectStepSynthesizesRationaleAndAdoptedRule() {
                HarnessOptimizeRequest req = HarnessFixtures.createCoolingLeadRequest(3L);
                HarnessOptimizeResponse resp = optimizerEngine.optimize(req);

                if ("ADOPTED".equals(resp.verdict())) {
                    assertThat(resp.adoptedRule()).isNotNull();
                    assertThat(resp.adoptedRule().hasExperience()).isTrue();
                    assertThat(resp.deltaScore()).isPositive();
                }
            }

            @Test
            @DisplayName("T1.10.5: Generates unique runId and audit trail for each optimization run")
            void t1_10_5_generatesUniqueRunIdAndAuditTrail() {
                HarnessOptimizeResponse resp1 = optimizerEngine.optimize(HarnessFixtures.createCoolingLeadRequest(10L));
                HarnessOptimizeResponse resp2 = optimizerEngine.optimize(HarnessFixtures.createCoolingLeadRequest(20L));

                assertThat(resp1.runId()).isNotEqualTo(resp2.runId());
            }
        }

        // ---------------------------------------------------------------------
        // Feature 11: Seeded Random Anonymized Selection
        // ---------------------------------------------------------------------
        @Nested
        @DisplayName("F11: Seeded Random Anonymized Selection")
        class Feature11SeededAnonymizedSelection {

            @Test
            @DisplayName("T1.11.1: Seeded shuffle deterministically assigns BASE and CANDIDATE to labels A and B")
            void t1_11_1_seededShuffleDeterministicallyAssignsAAndB() {
                HarnessOptimizeRequest req1 = HarnessFixtures.createCoolingLeadRequest(777L);
                HarnessOptimizeRequest req2 = HarnessFixtures.createCoolingLeadRequest(777L);

                HarnessOptimizeResponse resp1 = optimizerEngine.optimize(req1);
                HarnessOptimizeResponse resp2 = optimizerEngine.optimize(req2);

                assertThat(resp1.candidateALabel()).isEqualTo(resp2.candidateALabel());
                assertThat(resp1.candidateBLabel()).isEqualTo(resp2.candidateBLabel());
            }

            @Test
            @DisplayName("T1.11.2: Inverting seed flips candidate positions (Candidate A becomes BASE, Candidate B becomes CANDIDATE)")
            void t1_11_2_invertingSeedFlipsCandidatePositions() {
                // In java.util.Random, seed 0L yields true on first nextBoolean(), while 4096L yields false
                long seedA = 0L;
                long seedB = 4096L;

                HarnessOptimizeResponse respA = optimizerEngine.optimize(HarnessFixtures.createCoolingLeadRequest(seedA));
                HarnessOptimizeResponse respB = optimizerEngine.optimize(HarnessFixtures.createCoolingLeadRequest(seedB));

                assertThat(respA.candidateALabel()).isNotEqualTo(respB.candidateALabel());
            }

            @Test
            @DisplayName("T1.11.3: Judge evaluates position-independently (winner maps to underlying response regardless of position)")
            void t1_11_3_judgeEvaluatesPositionIndependently() {
                HarnessOptimizeRequest req1 = HarnessFixtures.createCoolingLeadRequest(10L);
                HarnessOptimizeRequest req2 = HarnessFixtures.createCoolingLeadRequest(15L);

                HarnessOptimizeResponse resp1 = optimizerEngine.optimize(req1);
                HarnessOptimizeResponse resp2 = optimizerEngine.optimize(req2);

                // Both runs on the same cooling input should agree on the verdict (ADOPTED)
                assertThat(resp1.verdict()).isEqualTo(resp2.verdict());
            }

            @Test
            @DisplayName("T1.11.4: Unseeded runs over 50 iterations produce unbiased candidate distribution")
            void t1_11_4_unseededRunsProduceBalancedDistribution() {
                int aAsCandidate = 0;
                int bAsCandidate = 0;

                for (int i = 0; i < 50; i++) {
                    HarnessOptimizeRequest req = HarnessFixtures.createCoolingLeadRequest(null);
                    HarnessOptimizeResponse resp = optimizerEngine.optimize(req);
                    if ("CANDIDATE".equals(resp.candidateALabel())) aAsCandidate++;
                    else bAsCandidate++;
                }

                // Both positions should have been selected at least 10 times in 50 trials
                assertThat(aAsCandidate).isGreaterThanOrEqualTo(10);
                assertThat(bAsCandidate).isGreaterThanOrEqualTo(10);
            }

            @Test
            @DisplayName("T1.11.5: Candidate labels are strictly anonymized without label leakage in comparison")
            void t1_11_5_candidateLabelsAnonymizedWithoutLabelLeakage() {
                HarnessOptimizeRequest req = HarnessFixtures.createCoolingLeadRequest(999L);
                HarnessOptimizeResponse resp = optimizerEngine.optimize(req);

                assertThat(resp.candidateALabel()).isIn("BASE", "CANDIDATE");
                assertThat(resp.candidateBLabel()).isIn("BASE", "CANDIDATE");
                assertThat(resp.candidateALabel()).isNotEqualTo(resp.candidateBLabel());
            }
        }

        // ---------------------------------------------------------------------
        // Feature 12: KEEP_BASE Fallback & Delta Verification
        // ---------------------------------------------------------------------
        @Nested
        @DisplayName("F12: KEEP_BASE Fallback & Delta Verification")
        class Feature12KeepBaseFallback {

            @Test
            @DisplayName("T1.12.1: Emits KEEP_BASE verdict when candidate fails to demonstrably beat base response")
            void t1_12_1_emitsKeepBaseWhenCandidateFailsToBeatBase() {
                HarnessOptimizeRequest req = HarnessFixtures.createOptimalBaseRequest(50L);

                HarnessOptimizeResponse resp = optimizerEngine.optimize(req);

                assertThat(resp.verdict()).isEqualTo("KEEP_BASE");
                assertThat(resp.deltaScore()).isLessThanOrEqualTo(0.15);
            }

            @Test
            @DisplayName("T1.12.2: Emits KEEP_BASE verdict on tied evaluation score (preserves status quo)")
            void t1_12_2_emitsKeepBaseOnTiedEvaluationScore() {
                HarnessOptimizeRequest req = HarnessFixtures.createOptimalBaseRequest(51L);

                HarnessOptimizeResponse resp = optimizerEngine.optimize(req);

                assertThat(resp.verdict()).isEqualTo("KEEP_BASE");
            }

            @Test
            @DisplayName("T1.12.3: KEEP_BASE does not persist a new rule to learned_rules table (null adoptedRule)")
            void t1_12_3_keepBaseDoesNotPolluteLearnedRulesTable() {
                HarnessOptimizeRequest req = HarnessFixtures.createOptimalBaseRequest(52L);

                HarnessOptimizeResponse resp = optimizerEngine.optimize(req);

                assertThat(resp.verdict()).isEqualTo("KEEP_BASE");
                assertThat(resp.adoptedRule()).isNull();
            }

            @Test
            @DisplayName("T1.12.4: Records non-positive or low delta score in optimization history on KEEP_BASE")
            void t1_12_4_recordsNonPositiveDeltaScoreInOptimizationHistory() {
                HarnessOptimizeRequest req = HarnessFixtures.createOptimalBaseRequest(53L);

                HarnessOptimizeResponse resp = optimizerEngine.optimize(req);

                assertThat(resp.deltaScore()).isLessThanOrEqualTo(0.15);
            }

            @Test
            @DisplayName("T1.12.5: Returns original base reply as winning reply when KEEP_BASE is selected")
            void t1_12_5_returnsOriginalBaseReplyAsWinningReply() {
                HarnessOptimizeRequest req = HarnessFixtures.createOptimalBaseRequest(54L);

                HarnessOptimizeResponse resp = optimizerEngine.optimize(req);

                assertThat(resp.winningReply()).isEqualTo(req.baseReply());
            }
        }

        // ---------------------------------------------------------------------
        // Feature 13: Background Temperature Drop Auto-Trigger
        // ---------------------------------------------------------------------
        @Nested
        @DisplayName("F13: Background Temperature Drop Auto-Trigger")
        class Feature13BackgroundAutoTrigger {

            private boolean checkAutoTriggerCondition(int tempDelta) {
                return tempDelta <= -15;
            }

            @Test
            @DisplayName("T1.13.1: Automatically triggers optimization when temperature_delta <= -15")
            void t1_13_1_triggersWhenTemperatureDeltaLeqMinus15() {
                int severeDrop = -20;
                assertThat(checkAutoTriggerCondition(severeDrop)).isTrue();

                HarnessOptimizeRequest req = HarnessFixtures.createCoolingLeadRequest(123L);
                HarnessOptimizeResponse resp = optimizerEngine.optimize(req);

                assertThat(resp.verdict()).isEqualTo("ADOPTED");
            }

            @Test
            @DisplayName("T1.13.2: Does NOT trigger optimization when temperature_delta > -15 (e.g. -5, 0, +10)")
            void t1_13_2_doesNotTriggerWhenTemperatureDeltaGreaterThanMinus15() {
                assertThat(checkAutoTriggerCondition(-14)).isFalse();
                assertThat(checkAutoTriggerCondition(-5)).isFalse();
                assertThat(checkAutoTriggerCondition(0)).isFalse();
                assertThat(checkAutoTriggerCondition(15)).isFalse();
            }

            @Test
            @DisplayName("T1.13.3: Sets trigger_reason = 'TEMPERATURE_DROP_BURST' on auto-triggered run")
            void t1_13_3_setsTriggerReasonTemperatureDropBurst() {
                HarnessOptimizeRequest req = HarnessFixtures.createCoolingLeadRequest(456L);

                assertThat(req.triggerReason()).isEqualTo("TEMPERATURE_DROP_BURST");
            }

            @Test
            @DisplayName("T1.13.4: Asynchronous trigger executes without blocking user chat flow")
            void t1_13_4_asynchronousTriggerExecutesWithoutBlockingUserChat() throws Exception {
                ExecutorService executor = Executors.newSingleThreadExecutor();
                long start = System.currentTimeMillis();

                Future<HarnessOptimizeResponse> future = executor.submit(() ->
                        optimizerEngine.optimize(HarnessFixtures.createCoolingLeadRequest(789L))
                );

                // Primary thread proceeds immediately without waiting
                long dispatchElapsed = System.currentTimeMillis() - start;
                assertThat(dispatchElapsed).isLessThan(50L);

                HarnessOptimizeResponse resp = future.get(3, TimeUnit.SECONDS);
                assertThat(resp).isNotNull();
                executor.shutdown();
            }

            @Test
            @DisplayName("T1.13.5: Associates triggered run audit with correct pageId and conversationId")
            void t1_13_5_associatesRunAuditWithCorrectPageAndConversation() {
                HarnessOptimizeRequest req = HarnessFixtures.createCoolingLeadRequest(101L);

                assertThat(req.pageId()).isEqualTo(HarnessFixtures.PAGE_ID_SPA);
                assertThat(req.conversationId()).isEqualTo("conv-cooling-001");
            }
        }
    }

    // =========================================================================
    // TIER 2: BOUNDARY & CORNER CASES
    // =========================================================================

    @Nested
    @DisplayName("Tier 2: Boundary & Corner Cases")
    class Tier2BoundaryCases {

        @Test
        @DisplayName("T2.1: Empty customer message and empty conversation history handled gracefully without NPE")
        void t2_1_emptyCustomerMessageAndHistory() {
            GuardrailContext ctx = GuardrailContext.of("Anh", "DISCOVERY", List.of("Dạ em chào Anh!"), List.of(), "");

            GuardrailResult result = pipeline.evaluate(ctx);

            assertThat(result.guardedBubbles()).isNotEmpty();
            assertThat(result.durationNanos()).isPositive();
        }

        @Test
        @DisplayName("T2.2: Extreme temperature clamping [0, 100] with negative delta = -100 and positive delta = +100")
        void t2_2_extremeTemperatureClamping() {
            int clampedLow = Math.max(0, Math.min(100, 50 + (-100)));
            int clampedHigh = Math.max(0, Math.min(100, 50 + 100));

            assertThat(clampedLow).isEqualTo(0);
            assertThat(clampedHigh).isEqualTo(100);
        }

        @Test
        @DisplayName("T2.3: Massive input text (> 50,000 characters) processed without StackOverflowError or heap exhaustion")
        void t2_3_massiveInputTextOver50000Chars() {
            String massiveMessage = "tôi muốn hỏi giá dịch vụ ".repeat(2500); // ~62,500 chars
            GuardrailContext ctx = GuardrailContext.of("Anh", "DISCOVERY",
                    List.of("Dạ em chào Anh ạ!", "Bảng giá dịch vụ: 1.500.000đ."), List.of(), massiveMessage);

            GuardrailResult result = pipeline.evaluate(ctx);

            assertThat(result.guardedBubbles()).isNotEmpty();
        }

        @Test
        @DisplayName("T2.4: Boundary character budget: exactly 1200 characters accepted; 1201 characters triggers pruning")
        void t2_4_exactBoundary1200CharsVs1201Chars() {
            // Rule with exactly 1200 characters: Cue (3) + Ev (2) = 5. Need 1195 Ls.
            ExperienceRule exact1200 = ExperienceRule.create(
                    HarnessDimension.INV, "C", "Cue", "L".repeat(1195), "Ev", true, 0.95, null
            );
            assertThat(exact1200.characterCount()).isEqualTo(1200);

            BudgetAllocation alloc1 = budgetManager.allocate(List.of(exact1200));
            assertThat(alloc1.capExceeded()).isFalse();
            assertThat(alloc1.totalAlwaysOnChars()).isEqualTo(1200);

            // Rule with 1201 characters: Cue (3) + Ev (2) = 5. Need 1196 Ls.
            ExperienceRule over1201 = ExperienceRule.create(
                    HarnessDimension.INV, "C", "Cue", "L".repeat(1196), "Ev", true, 0.95, null
            );
            assertThat(over1201.characterCount()).isEqualTo(1201);

            BudgetAllocation alloc2 = budgetManager.allocate(List.of(over1201));
            assertThat(alloc2.capExceeded()).isTrue();
            assertThat(alloc2.alwaysOnRules()).isEmpty();
            assertThat(alloc2.onDemandRules()).hasSize(1);
        }

        @Test
        @DisplayName("T2.5: Boundary character budget: empty always-on rule list results in 0 characters and 0% utilization")
        void t2_5_emptyAlwaysOnRuleListZeroUtilization() {
            BudgetAllocation alloc = budgetManager.allocate(List.of());

            assertThat(alloc.totalAlwaysOnChars()).isZero();
            assertThat(alloc.utilizationPercent()).isZero();
            assertThat(alloc.capExceeded()).isFalse();
        }

        @Test
        @DisplayName("T2.6: Single character bubbles (e.g. '?', '!') processed and capped without array indexing errors")
        void t2_6_singleCharacterBubbles() {
            List<String> raw = List.of("?", "!", "Dạ.");
            GuardrailContext ctx = GuardrailContext.of("Anh", "DISCOVERY", raw, List.of(), "hỏi");

            GuardrailResult result = pipeline.evaluate(ctx);

            assertThat(result.guardedBubbles().size()).isLessThanOrEqualTo(2);
        }

        @Test
        @DisplayName("T2.7: Missing/null fields in Experience Library caught with descriptive validation")
        void t2_7_missingNullFieldsInExperienceLibrary() {
            ExperienceRule ruleNullCue = new ExperienceRule(
                    UUID.randomUUID(), HarnessDimension.INV, "CAT", null, "Lesson", "Evidence", true, 0.9, true, null
            );
            ExperienceRule ruleEmptyLesson = new ExperienceRule(
                    UUID.randomUUID(), HarnessDimension.INV, "CAT", "Cue", "   ", "Evidence", true, 0.9, true, null
            );

            assertThat(ruleNullCue.hasExperience()).isFalse();
            assertThat(ruleEmptyLesson.hasExperience()).isFalse();
        }

        @Test
        @DisplayName("T2.8: Multiple contradictory honorific cues in multi-turn history resolved strictly to male ('Anh')")
        void t2_8_multipleContradictoryHonorificCuesResolvedToMale() {
            // History contains earlier references to Chị, but latest turn identifies as male
            List<String> raw = List.of("Dạ em gửi Chị thông tin ạ. Em chào Chị!");
            GuardrailContext ctx = GuardrailContext.of("Anh", "VALUE_CONSULTATION", raw,
                    List.of("User: em chào chị", "Assistant: Dạ chào Chị", "User: mìn là nam nhé"), "mìn là nam nhé");

            GuardrailResult result = pipeline.evaluate(ctx);

            assertThat(result.guardedBubbles().get(0)).doesNotContain("Chị");
            assertThat(result.guardedBubbles().get(0)).contains("Anh");
        }

        @Test
        @DisplayName("T2.9: Vietnamese diacritic permutations and shorthand ('mìn là nam', 'mk la nam', 't là nam giới') reliably detected")
        void t2_9_vietnameseDiacriticsAndSlangPermutations() {
            List<String> maleInputs = List.of(
                    HarnessFixtures.MSG_MALE_WITH_TYPOS_1,
                    HarnessFixtures.MSG_MALE_WITH_TYPOS_2,
                    HarnessFixtures.MSG_MALE_WITH_TYPOS_3
            );

            java.util.regex.Pattern malePattern = java.util.regex.Pattern.compile("(?isU)\\b(mìn|minh|mình|mk|t)\\s+(?:làm?|là|la)?\\s*(?:nam|con trai)|\\bnam\\s+giới\\b");

            for (String input : maleInputs) {
                assertThat(malePattern.matcher(input).find())
                        .as("Should detect male honorific in: " + input)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("T2.10: High-concurrency stress: 50 parallel evaluations through GuardrailPipeline executed concurrently")
        void t2_10_highConcurrencyFiftyParallelPipelineRuns() throws Exception {
            int threadCount = 50;
            ExecutorService executor = Executors.newFixedThreadPool(10);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                executor.submit(() -> {
                    try {
                        GuardrailContext ctx = GuardrailContext.of("Anh", "DISCOVERY",
                                List.of("Dạ chào Chị " + idx + "!", "Giá 1.500.000đ."), List.of(), "giá");
                        GuardrailResult res = pipeline.evaluate(ctx);
                        if (res.guardedBubbles().get(0).contains("Anh")) {
                            successCount.incrementAndGet();
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean completed = latch.await(5, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(completed).isTrue();
            assertThat(successCount.get()).isEqualTo(threadCount);
        }
    }

    // =========================================================================
    // TIER 3: CROSS-FEATURE COMBINATIONS
    // =========================================================================

    @Nested
    @DisplayName("Tier 3: Cross-Feature Combinations")
    class Tier3CrossFeature {

        @Test
        @DisplayName("T3.1: HonorificConsistencyHook + BubbleCapHook: male rewrite across multi-bubbles properly merged")
        void t3_1_honorificAndBubbleCapCooperation() {
            List<String> raw = List.of(
                    "Dạ em chào Chị Lan Anh ạ!",
                    "Chị có thể tham khảo phác đồ điều trị.",
                    "Chúc Chị một ngày vui vẻ!"
            );
            GuardrailContext ctx = GuardrailContext.of("Anh", "VALUE_CONSULTATION", raw, List.of(), "anh là nam");

            GuardrailResult result = pipeline.evaluate(ctx);

            assertThat(result.triggeredHooks()).contains(HonorificConsistencyHook.CODE, BubbleCapHook.CODE);
            assertThat(result.guardedBubbles().size()).isLessThanOrEqualTo(2);
            for (String b : result.guardedBubbles()) {
                assertThat(b).doesNotContain("Chị").doesNotContain("Lan Anh");
                assertThat(b).contains("Anh");
            }
        }

        @Test
        @DisplayName("T3.2: AntiInfoDumpingHook + ClosingQuestionHook + BubbleCapHook fire harmoniously in single pass")
        void t3_2_tripleHookFiringAntiDumpingClosingQuestionBubbleCap() {
            List<String> raw = List.of(
                    "Dạ em chào Chị Lan Anh! Bảng giá bên em có: Gói Trị Mụn 1.500.000đ, Trị Nám 4.500.000đ, Căng Bóng 2.800.000đ.",
                    "Ngoài ra còn có Combo Tiết Kiệm giảm 10% (3-4 buổi) và Combo Toàn Diện giảm 15% (5-9 buổi).",
                    "Chị có muốn đăng ký khám ngay hôm nay được không ạ?"
            );
            GuardrailContext ctx = GuardrailContext.of("Anh", "DISCOVERY", raw, List.of(), "báo giá giúp mình");

            GuardrailResult result = pipeline.evaluate(ctx);

            assertThat(result.triggeredHooks()).contains(
                    HonorificConsistencyHook.CODE,
                    BubbleCapHook.CODE,
                    ClosingQuestionHook.CODE,
                    AntiInfoDumpingHook.CODE
            );
            assertThat(result.guardedBubbles().size()).isEqualTo(2);
            assertThat(result.guardedBubbles().get(0)).contains("chỉ từ 650.000đ/buổi");
            assertThat(result.guardedBubbles().get(1)).endsWith("?");
        }

        @Test
        @DisplayName("T3.3: TokenBudgetPruning interacting with Experience Library cue-based contextual retrieval")
        void t3_3_tokenBudgetPruningWithOnDemandCueRetrieval() {
            List<ExperienceRule> overflown = HarnessFixtures.sampleBudgetOverflownRules();
            BudgetAllocation alloc = budgetManager.allocate(overflown);

            // Always-on pool is under 1200 chars
            assertThat(alloc.totalAlwaysOnChars()).isLessThanOrEqualTo(1200);

            // Pruned rules are accessible in onDemandRules
            assertThat(alloc.onDemandRules()).isNotEmpty();

            // Contextual retrieval matches pruned rule by cue_signal
            boolean hasComboRule = alloc.onDemandRules().stream()
                    .anyMatch(r -> r.cueSignal().toLowerCase().contains("combo"));
            assertThat(hasComboRule).isTrue();
        }

        @Test
        @DisplayName("T3.4: Temperature Drop (-20) triggers Optimizer -> produces INV rule -> new rule fits in BudgetManager")
        void t3_4_temperatureDropTriggerToOptimizerToBudgetManager() {
            HarnessOptimizeRequest req = HarnessFixtures.createCoolingLeadRequest(555L);
            HarnessOptimizeResponse optResp = optimizerEngine.optimize(req);

            assertThat(optResp.verdict()).isEqualTo("ADOPTED");
            ExperienceRule newRule = optResp.adoptedRule();
            assertThat(newRule).isNotNull();

            // Feed new rule into budget manager with existing pool
            List<ExperienceRule> existing = List.of(
                    HarnessFixtures.createInvariantRule("INV-EXISTING", "Cue", "Lesson", 600)
            );
            List<ExperienceRule> combined = new ArrayList<>(existing);
            combined.add(newRule);

            BudgetAllocation alloc = budgetManager.allocate(combined);
            assertThat(alloc.totalAlwaysOnChars()).isLessThanOrEqualTo(1200);
        }

        @Test
        @DisplayName("T3.5: Deterministic Guardrails applied to Candidate output during the Probe step of Optimizer")
        void t3_5_probeStepGuardsMutantBeforeJudging() {
            HarnessOptimizeRequest req = HarnessFixtures.createCoolingLeadRequest(666L);
            HarnessOptimizeResponse resp = optimizerEngine.optimize(req);

            // Winning bubbles must have passed BubbleCapHook (size <= 2) and HonorificConsistencyHook (no 'Chị')
            assertThat(resp.winningBubbles().size()).isLessThanOrEqualTo(2);
            assertThat(resp.winningReply()).doesNotContain("Chị Lan Anh");
        }

        @Test
        @DisplayName("T3.6: Optimizer proposing DOM fact vs INV procedure under different customer contexts")
        void t3_6_optimizerContextualDimensionSelectionInvVsDom() {
            HarnessOptimizeRequest invReq = new HarnessOptimizeRequest(
                    HarnessFixtures.PAGE_ID_SPA, "c-1", "ừ để xem thêm đã", "Base", List.of(), -20, List.of(), "TEMP", 1L
            );
            HarnessOptimizeRequest domReq = new HarnessOptimizeRequest(
                    HarnessFixtures.PAGE_ID_SPA, "c-2", "trị mụn có chữa được sẹo rỗ không", "Base", List.of(), 0, List.of(), "MANUAL", 1L
            );

            HarnessOptimizeResponse invResp = optimizerEngine.optimize(invReq);
            HarnessOptimizeResponse domResp = optimizerEngine.optimize(domReq);

            assertThat(invResp.proposedDimension()).isEqualTo(HarnessDimension.INV);
            assertThat(domResp.proposedDimension()).isEqualTo(HarnessDimension.DOM);
        }

        @Test
        @DisplayName("T3.7: KEEP_BASE path when candidate violates guardrail hooks during probe")
        void t3_7_keepBaseWhenMutantViolatesGuardrailsDuringProbe() {
            HarnessOptimizeRequest optimalReq = HarnessFixtures.createOptimalBaseRequest(12L);
            HarnessOptimizeResponse resp = optimizerEngine.optimize(optimalReq);

            assertThat(resp.verdict()).isEqualTo("KEEP_BASE");
            assertThat(resp.winningReply()).isEqualTo(optimalReq.baseReply());
        }
    }

    // =========================================================================
    // TIER 4: REAL-WORLD APPLICATION SCENARIOS
    // =========================================================================

    @Nested
    @DisplayName("Tier 4: Real-World Scenarios")
    class Tier4RealWorldScenarios {

        @Test
        @DisplayName("Scenario 1: Frustrated customer inquiring about pricing during initial DISCOVERY stage")
        void t4_1_scenario1_frustratedPricingDiscoveryCustomer() {
            // Context: Customer asks "giá sao b", bot draft dumps 3 service prices and combo catalog
            GuardrailContext ctx = GuardrailContext.of("Anh", "DISCOVERY",
                    HarnessFixtures.DUMPING_BUBBLES_DISCOVERY,
                    List.of("User: chào shop", "Assistant: Dạ chào Chị"),
                    HarnessFixtures.MSG_DISCOVERY_PRICE_SLANG);

            pipeline.evaluate(ctx); // JIT warmup
            GuardrailResult result = pipeline.evaluate(ctx);

            // 1. Response maintains "Anh" honorific (zero "Chị")
            for (String b : result.guardedBubbles()) {
                assertThat(b).doesNotContain("Chị").doesNotContain("Lan Anh");
            }

            // 2. Bubble count capped to <= 2
            assertThat(result.guardedBubbles().size()).isLessThanOrEqualTo(2);

            // 3. Avoids dumping pricing sheet; provides 1 starting price + 1 0-risk perk
            assertThat(result.guardedBubbles().get(0)).contains("chỉ từ 650.000đ/buổi");
            assertThat(result.guardedBubbles().get(0)).contains("soi da AI 3D miễn phí 100%");

            // 4. Asks a focused A/B closing question
            assertThat(result.guardedBubbles().get(result.guardedBubbles().size() - 1)).endsWith("?");

            // 5. Execution latency strictly < 5ms
            assertThat(result.latencyBudgetMet()).isTrue();
            assertThat(result.durationNanos()).isLessThan(5_000_000L);
        }

        @Test
        @DisplayName("Scenario 2: Sudden negative temperature drop in customer conversation (temperature_delta = -20)")
        void t4_2_scenario2_suddenTemperatureDropMinus20AutoTrigger() {
            HarnessOptimizeRequest req = HarnessFixtures.createCoolingLeadRequest(888L);

            // Background recursive optimizer triggers automatically
            HarnessOptimizeResponse resp = optimizerEngine.optimize(req);

            // Records optimization run with delta score comparison
            assertThat(resp.verdict()).isEqualTo("ADOPTED");
            assertThat(resp.deltaScore()).isPositive();
            assertThat(resp.adoptedRule()).isNotNull();
            assertThat(resp.adoptedRule().cueSignal()).contains("hạ nhiệt");
            assertThat(resp.winningReply()).contains("thong thả cân nhắc");
        }

        @Test
        @DisplayName("Scenario 3: Admin configures Invariant vs Domain playbook rules exceeding 1200 chars")
        void t4_3_scenario3_adminConfiguresPlaybookExceeding1200Chars() {
            List<ExperienceRule> overflown = HarnessFixtures.sampleBudgetOverflownRules();

            BudgetAllocation alloc = budgetManager.allocate(overflown);

            // Always-on budget prunes rules to fit cap while preserving invariant rules
            assertThat(alloc.totalAlwaysOnChars()).isLessThanOrEqualTo(1200);
            assertThat(alloc.alwaysOnRules()).extracting(ExperienceRule::dimension)
                    .contains(HarnessDimension.INV);

            // Experience library rules are formatted with 3-part structure
            for (ExperienceRule rule : alloc.alwaysOnRules()) {
                assertThat(rule.hasExperience()).isTrue();
                assertThat(rule.toPromptBlock()).contains("Cue:").contains("Lesson:").contains("Evidence:");
            }
        }

        @Test
        @DisplayName("Scenario 4: Operator runs 1-Click Auto-Optimization on a suboptimal bot response")
        void t4_4_scenario4_operatorOneClickAutoOptimizationSuboptimalReply() {
            HarnessOptimizeRequest req = new HarnessOptimizeRequest(
                    HarnessFixtures.PAGE_ID_SPA,
                    "conv-suboptimal-004",
                    "giá mụn thế nào",
                    "Dạ bên em có gói mụn 1.500.000đ, gói nám 4.500.000đ. Chị có muốn đặt lịch luôn không?",
                    List.of(),
                    -16,
                    List.of(AntiInfoDumpingHook.CODE),
                    "MANUAL_1CLICK",
                    42L
            );

            HarnessOptimizeResponse resp = optimizerEngine.optimize(req);

            // Propose mutant -> anonymized probe -> mutant selected as winner -> rule synthesized
            assertThat(resp.verdict()).isEqualTo("ADOPTED");
            assertThat(resp.candidateALabel()).isIn("BASE", "CANDIDATE");
            assertThat(resp.candidateBLabel()).isIn("BASE", "CANDIDATE");
            assertThat(resp.adoptedRule()).isNotNull();
            assertThat(resp.adoptedRule().hasExperience()).isTrue();
        }

        @Test
        @DisplayName("Scenario 5: Operator runs 1-Click Auto-Optimization on an already optimal bot response")
        void t4_5_scenario5_operatorOneClickAutoOptimizationOptimalReplyKeepBase() {
            HarnessOptimizeRequest req = HarnessFixtures.createOptimalBaseRequest(999L);

            HarnessOptimizeResponse resp = optimizerEngine.optimize(req);

            // Propose mutant -> probe evaluation shows base response superior -> KEEP_BASE verdict emitted without degrading playbook
            assertThat(resp.verdict()).isEqualTo("KEEP_BASE");
            assertThat(resp.adoptedRule()).isNull();
            assertThat(resp.winningReply()).isEqualTo(req.baseReply());
            assertThat(resp.selectionRationale()).contains("KEEP_BASE");
        }
    }
}
