package com.example.docmind.service.reasoning;

import com.example.docmind.config.LlmProperties;
import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.service.llm.OpenAiCompatibleLlmClient;
import com.example.docmind.service.llm.ReasoningBudget;
import com.example.docmind.service.sales.PriceLadderTracker.PriceLadderResult;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Empirical Adversarial Challenger Suite (M3-R2-1) for Feature F8:
 * Adaptive Deep Reasoning Engine, QueryComplexityClassifier, and ReasoningBudget.
 *
 * Stress-tests:
 * 1. Routine token "gội đầu dưỡng sinh" isolation vs multi-service/symptom combinations.
 * 2. 10k-20k+ character ReDoS & catastrophic backtracking resistance (< 200ms).
 * 3. High-concurrency throughput under multi-threaded load (> 5,000 ops/sec).
 * 4. ReasoningBudget parameter integrity (2000 tokens, 0.1/0.2 temperatures) and LLM fallback.
 */
@DisplayName("Challenger M3-R2-1: Empirical Adversarial Stress Test Suite (Feature F8)")
class QueryComplexityClassifierAdversarialChallengerTest {

    private QueryComplexityClassifier classifier;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        classifier = new QueryComplexityClassifier();
        objectMapper = new ObjectMapper();
    }

    // =========================================================================
    // 1. ROUTINE TOKEN "gội đầu dưỡng sinh" ADVERSARIAL DISCRIMINATION
    // =========================================================================
    @Nested
    @DisplayName("1. Routine Service 'gội đầu dưỡng sinh' Isolation vs Escalation Matrix")
    class GoiDauDuongSinhTests {

        @ParameterizedTest
        @ValueSource(strings = {
                "gội đầu dưỡng sinh",
                "mình muốn gội đầu dưỡng sinh",
                "gội đầu dưỡng sinh bên mình giá bn",
                "cho em xin giá gói gội đầu dưỡng sinh",
                "gội đầu dưỡng sinh làm trong bao lâu ạ",
                "bên mình có dịch vụ gội đầu dưỡng sinh không",
                "em muốn đặt lịch gội đầu dưỡng sinh chiều nay",
                "gội đầu dưỡng sinh thư giãn nha shop",
                "gói gội đầu dưỡng sinh có những bước nào",
                "goi dau duong sinh",
                "GỘI ĐẦU DƯỠNG SINH",
                "Gội Đầu Dưỡng Sinh",
                "gội   đầu    dưỡng     sinh",
                "gội đầu dưỡng sinh!",
                "Dạ em muốn hỏi về gói gội đầu dưỡng sinh bên Nàng Sen ạ"
        })
        @DisplayName("Isolated routine 'gội đầu dưỡng sinh' across diacritics/casing must stay SIMPLE")
        void testIsolatedGoiDauDuongSinhMustStaySimple(String query) {
            QueryComplexity complexity = classifier.classify(query);
            assertThat(complexity)
                    .as("Routine query '%s' must classify as SIMPLE (4-8s target), not falsely escalate", query)
                    .isEqualTo(QueryComplexity.SIMPLE);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "gội đầu dưỡng sinh và massage body",
                "gội đầu dưỡng sinh kèm massage cổ vai gáy",
                "gội đầu dưỡng sinh với trị mụn",
                "gội đầu dưỡng sinh và trị nám",
                "mình bị mất ngủ muốn gội đầu dưỡng sinh",
                "mình bị stress căng thẳng muốn gội đầu dưỡng sinh thư giãn",
                "bị đau vai gáy muốn đi gội đầu dưỡng sinh",
                "gội đầu dưỡng sinh có trị rụng tóc không",
                "gội đầu dưỡng sinh kèm trị gàu",
                "gội đầu dưỡng sinh và đau lưng",
                "gội đầu dưỡng sinh kết hợp chăm sóc da dầu",
                "vừa mụn vừa muốn gội đầu dưỡng sinh",
                "da nhạy cảm có gội đầu dưỡng sinh được không và có giảm đau đầu không",
                "gội đầu dưỡng sinh gói 129k và gói 239k khác gì nhau",
                "gội đầu dưỡng sinh mà 239k chát thế shop bớt không"
        })
        @DisplayName("Combined queries with distinct secondary symptoms/services must escalate to DEEP_REASONING")
        void testCombinedGoiDauDuongSinhMustEscalateToDeepReasoning(String query) {
            QueryComplexity complexity = classifier.classify(query);
            assertThat(complexity)
                    .as("Combined consultative query '%s' must escalate to DEEP_REASONING", query)
                    .isEqualTo(QueryComplexity.DEEP_REASONING);
        }
    }

    // =========================================================================
    // 2. 10,000–25,000 CHARACTER REDOS & BACKTRACKING STRESS TESTS
    // =========================================================================
    @Nested
    @DisplayName("2. 10k-25k Character Extreme Inputs & ReDoS Protection (< 200ms)")
    class ReDoSStressTests {

        @Test
        @DisplayName("Stress 2.1: 20,000-character repetitive package comparison evaluates in < 200ms")
        void testTwentyThousandCharRepetitivePackageComparison() {
            String repetitive = "gói 670k và 780k khác gì nhau sao chênh nhiều thế ".repeat(400); // 20,400 chars
            assertThat(repetitive.length()).isGreaterThanOrEqualTo(20_000);

            assertTimeoutPreemptively(Duration.ofMillis(200), () -> {
                long startNs = System.nanoTime();
                QueryComplexity complexity = classifier.classify(repetitive);
                long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

                assertThat(complexity).isEqualTo(QueryComplexity.DEEP_REASONING);
                assertThat(elapsedMs).as("Classification must complete in < 200ms (actual: %d ms)", elapsedMs).isLessThan(200);
            });
        }

        @Test
        @DisplayName("Stress 2.2: 21,000-character repetitive price objection evaluates in < 200ms")
        void testTwentyOneThousandCharRepetitivePriceObjection() {
            String repetitive = "sao đắt thế bớt được không shop ơi giá hơi chát ".repeat(450); // 21,600 chars
            assertThat(repetitive.length()).isGreaterThanOrEqualTo(20_000);

            assertTimeoutPreemptively(Duration.ofMillis(200), () -> {
                long startNs = System.nanoTime();
                QueryComplexity complexity = classifier.classify(repetitive);
                long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

                assertThat(complexity).isEqualTo(QueryComplexity.DEEP_REASONING);
                assertThat(elapsedMs).as("Classification must complete in < 200ms (actual: %d ms)", elapsedMs).isLessThan(200);
            });
        }

        @Test
        @DisplayName("Stress 2.3: 20,000-character adversarial connective trap payload evaluates in < 200ms")
        void testAdversarialConnectiveBacktrackingTrap() {
            // Pathological pattern targeting `vừa.*?vừa` with 10k non-matching chars between
            String trap = "vừa " + "a".repeat(10_000) + " vừa " + "b".repeat(10_000);
            assertThat(trap.length()).isGreaterThanOrEqualTo(20_000);

            assertTimeoutPreemptively(Duration.ofMillis(200), () -> {
                long startNs = System.nanoTime();
                QueryComplexity complexity = classifier.classify(trap);
                long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

                assertThat(elapsedMs).as("Trap evaluation must complete in < 200ms (actual: %d ms)", elapsedMs).isLessThan(200);
            });
        }

        @Test
        @DisplayName("Stress 2.4: 20,000-character non-matching alternating characters evaluate in < 200ms")
        void testTwentyThousandCharAlternationMismatch() {
            // Non-matching noise causing regex engine to scan all pattern alternations without match
            String noise = "x y z abc def ghi jkl mno pqr stu vwx yza bcd ".repeat(450); // 20,700 chars
            assertThat(noise.length()).isGreaterThanOrEqualTo(20_000);

            assertTimeoutPreemptively(Duration.ofMillis(200), () -> {
                long startNs = System.nanoTime();
                QueryComplexity complexity = classifier.classify(noise);
                long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

                assertThat(complexity).isEqualTo(QueryComplexity.SIMPLE);
                assertThat(elapsedMs).as("Mismatch scan must complete in < 200ms (actual: %d ms)", elapsedMs).isLessThan(200);
            });
        }

        @Test
        @DisplayName("Stress 2.5: 25,000-character payload with repetitive symptoms evaluates in < 200ms")
        void testTwentyFiveThousandCharRepetitiveSymptoms() {
            String repetitiveSymptoms = "mụn ẩn thâm sẹo đau vai gáy mất ngủ gội đầu dưỡng sinh massage ".repeat(400); // 25,200 chars
            assertThat(repetitiveSymptoms.length()).isGreaterThanOrEqualTo(25_000);

            assertTimeoutPreemptively(Duration.ofMillis(200), () -> {
                long startNs = System.nanoTime();
                QueryComplexity complexity = classifier.classify(repetitiveSymptoms);
                long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

                assertThat(complexity).isEqualTo(QueryComplexity.DEEP_REASONING);
                assertThat(elapsedMs).as("Must complete in < 200ms (actual: %d ms)", elapsedMs).isLessThan(200);
            });
        }
    }

    // =========================================================================
    // 3. HIGH-CONCURRENCY THROUGHPUT BENCHMARK (> 5,000 OPS/SEC)
    // =========================================================================
    @Nested
    @DisplayName("3. High-Concurrency Multi-Threaded Stress (> 5,000 ops/sec)")
    class ConcurrencyThroughputTests {

        @Test
        @DisplayName("Benchmark 3.1: 64 concurrent threads executing 20,000 classifications exceed 5,000 ops/sec")
        void testHighConcurrencyThroughputExceedsFiveThousandOps() throws InterruptedException, ExecutionException {
            int numThreads = 64;
            int totalIterations = 20_000;
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch readyLatch = new CountDownLatch(numThreads);
            CountDownLatch startLatch = new CountDownLatch(1);
            AtomicInteger completedCount = new AtomicInteger(0);

            List<String> simplePool = List.of(
                    "Chào shop",
                    "mấy giờ mở cửa ạ",
                    "địa chỉ cơ sở ở đâu ạ",
                    "giá bn ạ",
                    "0968221644 tư vấn giúp mình",
                    "gội đầu dưỡng sinh",
                    "mình muốn gội đầu dưỡng sinh",
                    "gói NS01 làm trong bao lâu"
            );

            List<String> deepPool = List.of(
                    "sao dat the co giam dc nua ko",
                    "gói 670k và 780k khác gì nhau sao chênh nhiều thế",
                    "bị đau cổ vai gáy kèm mất ngủ và đau lưng đã 3 năm",
                    "gia cao qua co bot ko shop",
                    "mua 5 buổi có được giảm 10% không em",
                    "gội đầu dưỡng sinh và massage body",
                    "gội đầu dưỡng sinh kèm đau vai gáy",
                    "mặt vừa mụn vừa thâm"
            );

            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < totalIterations; i++) {
                final int idx = i;
                tasks.add(() -> {
                    readyLatch.countDown();
                    startLatch.await();

                    if (idx % 2 == 0) {
                        String query = simplePool.get((idx / 2) % simplePool.size());
                        QueryComplexity result = classifier.classify(query);
                        if (result != QueryComplexity.SIMPLE) {
                            throw new IllegalStateException("Expected SIMPLE for: " + query + " but got: " + result);
                        }
                    } else {
                        String query = deepPool.get((idx / 2) % deepPool.size());
                        QueryComplexity result = classifier.classify(query);
                        if (result != QueryComplexity.DEEP_REASONING) {
                            throw new IllegalStateException("Expected DEEP_REASONING for: " + query + " but got: " + result);
                        }
                    }
                    completedCount.incrementAndGet();
                    return true;
                });
            }

            List<Future<Boolean>> futures = new ArrayList<>();
            for (var task : tasks) {
                futures.add(executor.submit(task));
            }

            // Ensure all worker threads are created and waiting
            boolean allReady = readyLatch.await(5, TimeUnit.SECONDS);
            assertThat(allReady).isTrue();

            long startNs = System.nanoTime();
            // Fire starting gun
            startLatch.countDown();

            for (Future<Boolean> f : futures) {
                assertThat(f.get()).isTrue();
            }

            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

            assertThat(completedCount.get()).isEqualTo(totalIterations);
            double opsPerSec = (totalIterations / (double) durationMs) * 1000.0;
            System.out.printf("[ChallengerStress] Completed %d classifications across %d threads in %d ms (%.1f ops/sec)%n",
                    totalIterations, numThreads, durationMs, opsPerSec);

            assertThat(opsPerSec)
                    .as("Throughput must be strictly greater than 5,000 ops/sec")
                    .isGreaterThan(5000.0);
        }
    }

    // =========================================================================
    // 4. REASONING BUDGET & LLM CLIENT INTEGRATION VERIFICATION
    // =========================================================================
    @Nested
    @DisplayName("4. ReasoningBudget Parameters & LLM Client Fallback Verification")
    class ReasoningBudgetAndLlmTests {

        @Test
        @DisplayName("Budget 4.1: SIMPLE vs DEEP_REASONING budget parameters conform strictly to F8 spec")
        void testReasoningBudgetParametersStrictConformity() {
            ReasoningBudget simple = ReasoningBudget.simple();
            assertThat(simple.complexity()).isEqualTo(QueryComplexity.SIMPLE);
            assertThat(simple.reasoningEffort()).isEqualTo("low");
            assertThat(simple.targetMinLatencyMs()).isEqualTo(4000);
            assertThat(simple.targetMaxLatencyMs()).isEqualTo(8000);
            assertThat(simple.timeoutSeconds()).isEqualTo(15);
            assertThat(simple.maxTokens()).isEqualTo(600);
            assertThat(simple.temperature()).isEqualTo(0.1);

            ReasoningBudget deep = ReasoningBudget.deepReasoning();
            assertThat(deep.complexity()).isEqualTo(QueryComplexity.DEEP_REASONING);
            assertThat(deep.reasoningEffort()).isEqualTo("medium");
            assertThat(deep.targetMinLatencyMs()).isEqualTo(15000);
            assertThat(deep.targetMaxLatencyMs()).isEqualTo(30000);
            assertThat(deep.timeoutSeconds()).isEqualTo(45);
            assertThat(deep.maxTokens()).isEqualTo(2000);
            assertThat(deep.temperature()).isEqualTo(0.2);
        }

        @Test
        @DisplayName("Budget 4.2: OpenAiCompatibleLlmClient serializes exact reasoning parameters for simple and deep")
        void testLlmClientSerializesExactBudgetParameters() {
            LlmProperties props = new LlmProperties();
            props.setProvider("openai");
            props.setApiKey("sk-challenger-test");

            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(props, objectMapper, builder.build());

            // 1. SIMPLE budget
            server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer sk-challenger-test"))
                    .andExpect(jsonPath("$.temperature").value(0.1))
                    .andExpect(jsonPath("$.reasoning_effort").value("low"))
                    .andExpect(jsonPath("$.max_tokens").value(600))
                    .andRespond(withSuccess("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Chào bạn\"}}]}",
                            MediaType.APPLICATION_JSON));

            String resSimple = client.complete("Chào shop", ReasoningBudget.simple());
            assertThat(resSimple).isEqualTo("Chào bạn");
            server.verify();

            // 2. DEEP budget
            server.reset();
            server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer sk-challenger-test"))
                    .andExpect(jsonPath("$.temperature").value(0.2))
                    .andExpect(jsonPath("$.reasoning_effort").value("medium"))
                    .andExpect(jsonPath("$.max_tokens").value(2000))
                    .andRespond(withSuccess("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Tư vấn sâu\"}}]}",
                            MediaType.APPLICATION_JSON));

            String resDeep = client.complete("So sánh gói", ReasoningBudget.deepReasoning());
            assertThat(resDeep).isEqualTo("Tư vấn sâu");
            server.verify();
        }

        @Test
        @DisplayName("Budget 4.3: HTTP 400 Bad Request triggers fallback without reasoning parameters but preserves temperature")
        void testLlmClient400FallbackRetriesWithoutReasoningParameters() {
            LlmProperties props = new LlmProperties();
            props.setProvider("deepseek");
            props.setModel("deepseek-chat");
            props.setApiKey("sk-challenger-deepseek");

            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(props, objectMapper, builder.build());

            ReasoningBudget deep = ReasoningBudget.deepReasoning();

            // First attempt: rejects reasoning_effort and max_tokens with 400
            server.expect(requestTo("https://api.deepseek.com/v1/chat/completions"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(jsonPath("$.reasoning_effort").value("medium"))
                    .andExpect(jsonPath("$.max_tokens").value(2000))
                    .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{\"error\":{\"message\":\"reasoning_effort not supported\"}}"));

            // Second attempt: succeeds without reasoning_effort and max_tokens, retaining temperature 0.2
            server.expect(requestTo("https://api.deepseek.com/v1/chat/completions"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(jsonPath("$.reasoning_effort").doesNotExist())
                    .andExpect(jsonPath("$.max_tokens").doesNotExist())
                    .andExpect(jsonPath("$.temperature").value(0.2))
                    .andRespond(withSuccess("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Phân tích thành công\"}}]}",
                            MediaType.APPLICATION_JSON));

            String result = client.complete("So sánh gói phức tạp", deep);
            assertThat(result).isEqualTo("Phân tích thành công");
            server.verify();
        }
    }
}
