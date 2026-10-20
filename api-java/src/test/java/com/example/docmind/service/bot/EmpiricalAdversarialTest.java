package com.example.docmind.service.bot;

import com.example.docmind.config.ChatProperties;
import com.example.docmind.dto.bot.CustomerInsightDto;
import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.dto.bot.FanpageBotResponse;
import com.example.docmind.entity.DocumentEntity;
import com.example.docmind.repository.AgentSkillRepository;
import com.example.docmind.repository.DocumentRepository;
import com.example.docmind.repository.KnowledgePageRepository;
import com.example.docmind.repository.RetrievedChunk;
import com.example.docmind.service.llm.LlmClient;
import com.example.docmind.service.retrieval.ReciprocalRankFusion;
import com.example.docmind.service.retrieval.RetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Empirical Adversarial Stress Test Suite for DocMind Fanpage Bot Upgrade.
 * Executed by Challenger 1 to independently verify boundary conditions, edge cases,
 * mathematical decay invariants, and potential vulnerabilities.
 */
public class EmpiricalAdversarialTest {

    private CustomerInsightEngine insightEngine;
    private FanpageBotService fanpageBotService;
    private RetrievalService retrievalService;
    private LlmClient llmClient;
    private DocumentRepository documentRepository;
    private AgentSkillService agentSkillService;

    @BeforeEach
    void setUp() {
        insightEngine = new CustomerInsightEngine();
        retrievalService = mock(RetrievalService.class);
        llmClient = mock(LlmClient.class);
        when(llmClient.name()).thenReturn("mock-challenger-llm");

        RetrievedChunk rc = new RetrievedChunk(
                UUID.randomUUID(), UUID.randomUUID(),
                List.of("Bảng Giá"), List.of(UUID.randomUUID()),
                1, new double[]{0, 0, 100, 100},
                "Liệu trình Trị Mụn Chuẩn Y Khoa 1.500.000 VNĐ, không sưng đỏ", 0.95
        );
        when(retrievalService.retrieveWithRules(anyList(), anyString(), anyInt(), anyList(), any()))
                .thenReturn(new RetrievalService.RetrievalResult(List.of(new ReciprocalRankFusion.FusedChunk(rc, 0.05)), List.of()));

        when(llmClient.complete(anyString())).thenReturn("Dạ em chào Chị ạ! Gói mụn 1.500.000 VNĐ ạ.\n\nChị đang bị mụn ở trán hay má ạ?");

        documentRepository = mock(DocumentRepository.class);
        UUID docId = UUID.randomUUID();
        UUID pageId = UUID.randomUUID();
        DocumentEntity readyDoc = DocumentEntity.builder().id(docId).pageId(pageId).filename("spa.docx").status("READY").build();
        when(documentRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(readyDoc));
        when(documentRepository.findByPageIdOrderByCreatedAtDesc(any())).thenReturn(List.of(readyDoc));
        when(documentRepository.findById(any())).thenReturn(Optional.of(readyDoc));

        AgentSkillRepository agentSkillRepository = mock(AgentSkillRepository.class);
        when(agentSkillRepository.findAllOrdered()).thenReturn(List.of());
        agentSkillService = new AgentSkillService(agentSkillRepository);

        fanpageBotService = new FanpageBotService(
                retrievalService,
                llmClient,
                new ChatProperties(),
                documentRepository,
                mock(KnowledgePageRepository.class),
                insightEngine,
                agentSkillService
        );
    }

    // =========================================================================
    // 1. TYPO AND SLANG TESTS ON HALF-HEARTED REPLIES
    // =========================================================================
    @Test
    @DisplayName("EXP-01: Typo & Slang Detection in Half-Hearted Replies")
    void testTypoAndSlangResilience() {
        List<String> validSlangs = List.of(
                "uk",
                "ừ",
                "ok em",
                "để chị xem thêm",
                "thôi để khi khác"
        );

        List<String> challengingSlangs = List.of(
                "để xem thế nào đã",
                "thôi chát sau",
                "thôi chat sau"
        );

        System.out.println("=== Testing Valid Slangs ===");
        for (String msg : validSlangs) {
            CustomerInsightDto dto = insightEngine.analyzeInsight(
                    msg,
                    List.of(new FanpageBotRequest.ConversationTurn("user", "Giá sao em")),
                    "Chị",
                    FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION,
                    null,
                    70,
                    15,
                    0
            );
            System.out.println("Msg: [" + msg + "] -> momentum=" + dto.momentum() + ", delta=" + dto.temperatureDelta());
            assertThat(dto.temperatureDelta()).isNegative();
            assertThat(dto.momentum()).isIn("COOLING_HALF_HEARTED", "PRICE_HESITATION");
        }

        System.out.println("=== Testing Challenging Slangs ===");
        for (String msg : challengingSlangs) {
            CustomerInsightDto dto = insightEngine.analyzeInsight(
                    msg,
                    List.of(new FanpageBotRequest.ConversationTurn("user", "Giá sao em")),
                    "Chị",
                    FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION,
                    null,
                    70,
                    15,
                    0
            );
            System.out.println("Challenging Msg: [" + msg + "] -> momentum=" + dto.momentum() + ", delta=" + dto.temperatureDelta());
            if (dto.temperatureDelta() >= 0) {
                System.err.println("SLANG MISSED: [" + msg + "] did not trigger cooling decay! delta=" + dto.temperatureDelta() + ", momentum=" + dto.momentum());
            }
        }
    }

    // =========================================================================
    // 2. MULTIPLE SEQUENTIAL BRUSH-OFFS & THE <40 TEMPERATURE INVERSION
    // =========================================================================
    @Test
    @DisplayName("EXP-02: Multiple Sequential Brush-offs & Inversion Bug")
    void testSequentialBrushOffs() {
        int temp = 70;
        List<FanpageBotRequest.ConversationTurn> history = new ArrayList<>();
        history.add(new FanpageBotRequest.ConversationTurn("user", "Gói trị mụn bao nhiêu tiền"));
        history.add(new FanpageBotRequest.ConversationTurn("assistant", "Dạ gói 1.500.000 VNĐ ạ"));

        // Turn 1: First brush-off
        CustomerInsightDto turn1 = insightEngine.analyzeInsight(
                "ừ để chị xem thêm", history, "Chị",
                FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION, null, temp, 20, 0
        );
        System.out.println("Turn 1 (70 -> ?): temp=" + turn1.buyingTemperature() + ", delta=" + turn1.temperatureDelta() + ", momentum=" + turn1.momentum());
        assertThat(turn1.temperatureDelta()).isNegative();
        int tempAfterTurn1 = turn1.buyingTemperature();

        history.add(new FanpageBotRequest.ConversationTurn("user", "ừ để chị xem thêm"));
        history.add(new FanpageBotRequest.ConversationTurn("assistant", "Dạ chị cứ thong thả ạ"));

        // Turn 2: Second brush-off
        CustomerInsightDto turn2 = insightEngine.analyzeInsight(
                "để chị nghĩ đã", history, "Chị",
                FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION, null, tempAfterTurn1, 30, 0
        );
        System.out.println("Turn 2 (" + tempAfterTurn1 + " -> ?): temp=" + turn2.buyingTemperature() + ", delta=" + turn2.temperatureDelta() + ", momentum=" + turn2.momentum());
        int tempAfterTurn2 = turn2.buyingTemperature();

        history.add(new FanpageBotRequest.ConversationTurn("user", "để chị nghĩ đã"));
        history.add(new FanpageBotRequest.ConversationTurn("assistant", "Dạ vâng ạ"));

        // Turn 3: Third brush-off when temperature is low (< 40)
        CustomerInsightDto turn3 = insightEngine.analyzeInsight(
                "thôi để khi khác", history, "Chị",
                FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION, null, tempAfterTurn2, 40, 0
        );
        assertThat(turn3.temperatureDelta()).isNegative();
        assertThat(turn3.buyingTemperature()).isEqualTo(10);
        assertThat(turn3.momentum()).isEqualTo("COOLING_HALF_HEARTED");
    }

    // =========================================================================
    // 3. EXTREME DELAY & FLOOR CLAMPING (7200s, negative delays, zero delays)
    // =========================================================================
    @Test
    @DisplayName("EXP-03: Extreme Delays, Negative Delays, and Floor Clamping")
    void testExtremeAndNegativeDelays() {
        // Test 7200s delay on warm customer (75)
        CustomerInsightDto decay7200 = insightEngine.analyzeInsight(
                "alo", List.of(), "Chị",
                FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION, null, 75, 7200, 7200
        );
        System.out.println("7200s delay on 75: temp=" + decay7200.buyingTemperature() + ", delta=" + decay7200.temperatureDelta());
        assertThat(decay7200.buyingTemperature()).isEqualTo(40); // 75 - 35 = 40

        // Test 7200s delay on already cold customer (15)
        CustomerInsightDto decayOnCold = insightEngine.analyzeInsight(
                "alo", List.of(), "Chị",
                FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION, null, 15, 7200, 7200
        );
        System.out.println("7200s delay on 15: temp=" + decayOnCold.buyingTemperature() + ", delta=" + decayOnCold.temperatureDelta());
        assertThat(decayOnCold.buyingTemperature()).isEqualTo(0);
        assertThat(decayOnCold.temperatureDelta()).isEqualTo(-15);
        assertThat(decayOnCold.momentum()).isEqualTo("COOLING_IDLE");

        // Test negative delay (-60s)
        CustomerInsightDto negDelay = insightEngine.analyzeInsight(
                "alo", List.of(), "Chị",
                FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION, null, 50, -60, -60
        );
        System.out.println("Negative delay (-60): temp=" + negDelay.buyingTemperature() + ", momentum=" + negDelay.momentum());
        assertThat(negDelay.buyingTemperature()).isBetween(0, 100);

        // Test zero delay (0s)
        CustomerInsightDto zeroDelay = insightEngine.analyzeInsight(
                "alo", List.of(), "Chị",
                FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION, null, 50, 0, 0
        );
        System.out.println("Zero delay (0): temp=" + zeroDelay.buyingTemperature() + ", momentum=" + zeroDelay.momentum());
        assertThat(zeroDelay.buyingTemperature()).isBetween(0, 100);
    }

    // =========================================================================
    // 4. MOMENTUM RECOVERY TRANSITIONS
    // =========================================================================
    @Test
    @DisplayName("EXP-04: Momentum Transition COOLING_HALF_HEARTED -> RECOVERING")
    void testMomentumRecoveryTransition() {
        // Customer was cooling at temp 45, now sends phone number
        FanpageBotResponse.ExtractedLead lead = new FanpageBotResponse.ExtractedLead(true, "0988112233", null, null);
        CustomerInsightDto recovery = insightEngine.analyzeInsight(
                "0988112233 gọi lại cho anh",
                List.of(
                        new FanpageBotRequest.ConversationTurn("user", "để chị xem thêm"),
                        new FanpageBotRequest.ConversationTurn("assistant", "Dạ chị cứ thong thả ạ")
                ),
                "Anh",
                FanpageBotPromptBuilder.SalesStage.SOFT_BOOKING_CLOSE,
                lead,
                45,
                10,
                0
        );
        System.out.println("Recovery from 45: temp=" + recovery.buyingTemperature() + ", delta=" + recovery.temperatureDelta() + ", momentum=" + recovery.momentum());
        assertThat(recovery.buyingTemperature()).isGreaterThanOrEqualTo(90);
        assertThat(recovery.momentum()).isIn("RECOVERING", "RISING");
    }

    // =========================================================================
    // 5. BURST COALESCING WITH 4-5 MESSAGES & BLANKS
    // =========================================================================
    @Test
    @DisplayName("EXP-05: Multi-Message Burst with 5 rapid messages and blank items")
    void testBurstCoalescingWithFiveMessagesAndBlanks() {
        FanpageBotRequest burstReq = new FanpageBotRequest(
                UUID.randomUUID().toString(),
                "burst-tester-01",
                "Chị Mai",
                "tư vấn thêm nhé",
                List.of(
                        "Shop ơi",
                        "  ",
                        "mụn ẩn trị thế nào",
                        "",
                        "chi phí hết bao nhiêu",
                        "có bị sưng đỏ không"
                ),
                List.of(),
                List.of(UUID.randomUUID()),
                5
        );

        FanpageBotResponse response = fanpageBotService.handleCustomerMessage(burstReq);
        System.out.println("Burst coalescing text: " + response.replyText());
        System.out.println("Reply bubbles count: " + response.replyBubbles().size());
        assertThat(response.replyBubbles()).isNotEmpty();
        assertThat(response.replyText()).contains("1.500.000");
    }

    // =========================================================================
    // 6. BUBBLE PACING INTERVAL STRICTLY [2500, 3000ms] ON BUBBLE 1+
    // =========================================================================
    @Test
    @DisplayName("EXP-06: Consecutive Bubble Pacing strictly in [2500, 3000ms] across lengths")
    void testBubblePacingStrictBounds() {
        // Test normal 2 bubbles (non-greeting paragraph 1 and question paragraph 2)
        String twoBubbles = "Liệu trình Trị Mụn Chuẩn Y Khoa bên em có chi phí 1.500.000 VNĐ trọn gói ạ.\n\nHiện tại da Chị đang bị mụn ẩn hay mụn viêm sưng để em tư vấn phác đồ ạ?";
        List<FanpageBotResponse.ReplyBubble> normalBubbles = FanpageBotService.splitIntoBubbles(twoBubbles);
        assertThat(normalBubbles).hasSize(2);
        System.out.println("Normal bubble 0 delay: " + normalBubbles.get(0).typingDelayMs());
        System.out.println("Normal bubble 1 delay: " + normalBubbles.get(1).typingDelayMs());
        assertThat(normalBubbles.get(0).typingDelayMs()).isGreaterThanOrEqualTo(800).isLessThanOrEqualTo(1200);
        assertThat(normalBubbles.get(1).typingDelayMs()).isGreaterThanOrEqualTo(2500).isLessThanOrEqualTo(3000);

        // Test long second bubble (2000 chars)
        String longText = "A".repeat(2000);
        String twoLongBubbles = "Đoạn thông tin thứ nhất về phác đồ điều trị da chuyên khoa tại phòng khám.\n\n" + longText;
        List<FanpageBotResponse.ReplyBubble> longBubbles = FanpageBotService.splitIntoBubbles(twoLongBubbles);
        assertThat(longBubbles).hasSize(2);
        System.out.println("Long bubble 1 delay: " + longBubbles.get(1).typingDelayMs());
        assertThat(longBubbles.get(1).typingDelayMs()).isGreaterThanOrEqualTo(2500).isLessThanOrEqualTo(3000);

        // Test 3 bubbles
        String threeBubbles = "Bong bóng 1\n\nBong bóng 2\n\nBong bóng 3";
        List<FanpageBotResponse.ReplyBubble> triBubbles = FanpageBotService.splitIntoBubbles(threeBubbles);
        for (int i = 1; i < triBubbles.size(); i++) {
            System.out.println("Tri bubble " + i + " delay: " + triBubbles.get(i).typingDelayMs());
            assertThat(triBubbles.get(i).typingDelayMs()).isGreaterThanOrEqualTo(2500).isLessThanOrEqualTo(3000);
        }
    }

    // =========================================================================
    // 7. NULL PAGE_ID TELEMETRY CLOBBERING TEST IN FANPAGEBOTSERVICE
    // =========================================================================
    @Test
    @DisplayName("EXP-07: Null pageId clobbering bug in FanpageBotService")
    void testNullPageIdTelemetryRetention() {
        // When client sends request without page_id, previous_temperature must NOT be discarded
        FanpageBotRequest req = new FanpageBotRequest(
                null, // null pageId
                "user-test-null-page",
                "Khách hàng",
                "ừ để chị xem thêm",
                null,
                List.of(
                        new FanpageBotRequest.ConversationTurn("user", "Giá sao em"),
                        new FanpageBotRequest.ConversationTurn("assistant", "Dạ 1.500.000 VNĐ ạ")
                ),
                null, // null documentIds
                5,
                70,   // previous_temperature
                15,   // reply_delay_seconds
                0,    // idle_seconds
                "MESSAGE"
        );

        FanpageBotResponse resp = fanpageBotService.handleCustomerMessage(req);
        CustomerInsightDto insight = resp.customerInsight();
        System.out.println("EXP-07 Result: buying_temp=" + insight.buyingTemperature()
                + ", prev_temp=" + insight.previousTemperature()
                + ", delta=" + insight.temperatureDelta()
                + ", momentum=" + insight.momentum());

        assertThat(insight.previousTemperature()).isEqualTo(70);
        assertThat(insight.temperatureDelta()).isEqualTo(-20);
        assertThat(insight.buyingTemperature()).isEqualTo(50);
        assertThat(insight.momentum()).isEqualTo("COOLING_HALF_HEARTED");
    }

    // =========================================================================
    // 8. DEEP MULTI-TURN SEQUENTIAL COOLING MONOTONICITY & ZERO FLOOR
    // =========================================================================
    @Test
    @DisplayName("EXP-08: 6-Turn Sequential Cooling strictly monotonic non-increasing and clamped at 0")
    void testDeepSequentialCoolingMonotonicity() {
        int currentTemp = 75;
        List<FanpageBotRequest.ConversationTurn> history = new ArrayList<>();
        history.add(new FanpageBotRequest.ConversationTurn("user", "Giá trị mụn bao nhiêu shop?"));
        history.add(new FanpageBotRequest.ConversationTurn("assistant", "Dạ 1.500.000 VNĐ ạ."));

        List<String> sequentialBrushOffs = List.of(
                "ừ để chị xem thêm",
                "để chị nghĩ đã",
                "thôi để khi khác",
                "ok",
                "ừ",
                "hơi đắt"
        );

        for (int turn = 0; turn < sequentialBrushOffs.size(); turn++) {
            String msg = sequentialBrushOffs.get(turn);
            CustomerInsightDto insight = insightEngine.analyzeInsight(
                    msg, history, "Chị",
                    FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION, null,
                    currentTemp, 20, 0
            );

            System.out.println("Sequential Turn " + (turn + 1) + " [" + msg + "]: "
                    + currentTemp + " -> " + insight.buyingTemperature()
                    + " (delta=" + insight.temperatureDelta() + ", momentum=" + insight.momentum() + ")");

            assertThat(insight.buyingTemperature())
                    .as("Turn " + (turn + 1) + " temperature must be <= previous temperature")
                    .isLessThanOrEqualTo(currentTemp);

            assertThat(insight.temperatureDelta())
                    .as("Turn " + (turn + 1) + " delta must be <= 0")
                    .isLessThanOrEqualTo(0);

            assertThat(insight.buyingTemperature())
                    .as("Temperature must never drop below 0")
                    .isGreaterThanOrEqualTo(0);

            assertThat(insight.buyingTemperature())
                    .as("Temperature must never exceed 100")
                    .isLessThanOrEqualTo(100);

            assertThat(insight.momentum())
                    .as("Momentum must remain cooling")
                    .isIn("COOLING_HALF_HEARTED", "PRICE_HESITATION");

            history.add(new FanpageBotRequest.ConversationTurn("user", msg));
            history.add(new FanpageBotRequest.ConversationTurn("assistant", "Dạ em hiểu ạ."));
            currentTemp = insight.buyingTemperature();
        }

        // At end of 6 turns of brush-offs, customer must be at 0 temperature
        assertThat(currentTemp).isEqualTo(0);
    }

    // =========================================================================
    // 9. EXTREME IDLE DECAY MATRIX ACROSS ENTIRE TEMPERATURE SPECTRUM
    // =========================================================================
    @Test
    @DisplayName("EXP-09: Extreme Idle Decay across temperature spectrum [0, 10, 15, 35, 75, 100]")
    void testExtremeIdleDecayMatrix() {
        int[] initialTemps = {0, 10, 15, 35, 75, 100};
        int[] idleTimes = {300, 1800, 86400}; // 5 min, 30 min, 24 hr

        for (int temp : initialTemps) {
            for (int idle : idleTimes) {
                CustomerInsightDto insight = insightEngine.analyzeInsight(
                        "alo", List.of(), "Chị",
                        FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION, null,
                        temp, idle, idle
                );

                int expectedDecay = (idle >= 1800) ? 35 : 18;
                int expectedTemp = Math.max(0, temp - expectedDecay);
                int expectedDelta = expectedTemp - temp;

                System.out.println("Idle Test: temp=" + temp + ", idle=" + idle + "s -> result=" + insight.buyingTemperature() + " (delta=" + insight.temperatureDelta() + ")");

                assertThat(insight.buyingTemperature())
                        .as("Decayed temp must match expected value for temp=" + temp + ", idle=" + idle)
                        .isEqualTo(expectedTemp);

                assertThat(insight.temperatureDelta())
                        .as("Delta must match expected value for temp=" + temp + ", idle=" + idle)
                        .isEqualTo(expectedDelta);

                assertThat(insight.momentum()).isEqualTo("COOLING_IDLE");
                assertThat(insight.decayReason()).isEqualTo("COOLING_IDLE");
            }
        }
    }

    // =========================================================================
    // 10. REENGAGEMENT PROMPT BUILDER ISOLATION TEST
    // =========================================================================
    @Test
    @DisplayName("EXP-10: Reengagement Prompt instructions generated on idle/reengage, absent on normal inquiries")
    void testReengagementPromptIsolation() {
        // Normal inquiry 1
        String normalPrompt1 = FanpageBotPromptBuilder.build(
                "Liệu trình trị mụn bên em giá bao nhiêu?",
                "Chị Mai",
                List.of(),
                List.of(),
                List.of(),
                null,
                FanpageBotPromptBuilder.SalesStage.DISCOVERY,
                "Chị"
        );
        assertThat(normalPrompt1)
                .as("Normal inquiry must not include reengagement directive")
                .doesNotContain("[RE-ENGAGEMENT & KHÁCH HẠ NHIỆT]")
                .doesNotContain("khiến khách đọc ngợp rồi im lặng");

        // Normal inquiry 2
        String normalPrompt2 = FanpageBotPromptBuilder.build(
                "Làm có bị sưng đỏ không em?",
                "Anh Tuấn",
                List.of(
                        new FanpageBotRequest.ConversationTurn("user", "Chào shop"),
                        new FanpageBotRequest.ConversationTurn("assistant", "Dạ em chào Anh ạ!")
                ),
                List.of(),
                List.of(),
                null,
                FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION,
                "Anh"
        );
        assertThat(normalPrompt2)
                .as("Normal inquiry must not include reengagement directive")
                .doesNotContain("[RE-ENGAGEMENT & KHÁCH HẠ NHIỆT]");

        // Explicit reengage action / idle message
        String reengagePrompt = FanpageBotPromptBuilder.build(
                "⏳ Khách im lặng 5p",
                "Chị Mai",
                List.of(
                        new FanpageBotRequest.ConversationTurn("user", "Tư vấn trị mụn"),
                        new FanpageBotRequest.ConversationTurn("assistant", "Dạ 1.500.000 VNĐ ạ")
                ),
                List.of(),
                List.of(),
                null,
                FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION,
                "Chị"
        );
        assertThat(reengagePrompt)
                .as("Reengage prompt must contain reengagement guidance")
                .contains("[RE-ENGAGEMENT & KHÁCH HẠ NHIỆT]");
    }

    // =========================================================================
    // 11. ADVERSARIAL SLANGS & REJECTION PHRASES (EXPLORING BOUNDARIES)
    // =========================================================================
    @Test
    @DisplayName("EXP-11: Exploration of Rejection Slangs and Phrases at Temperature 50 and 30")
    void testAdversarialSlangBoundaries() {
        List<String> rejectionPhrases = List.of(
                "thôi",
                "thôi ạ",
                "thôi em",
                "thôi shop",
                "thôi nhé",
                "thôi để hôm khác",
                "để hôm khác",
                "chưa cần đâu"
        );

        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Giá gói trị mụn bao nhiêu em"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ 1.500.000 VNĐ trọn gói ạ")
        );

        System.out.println("=== Testing Rejection Phrases Boundary ===");
        for (String phrase : rejectionPhrases) {
            CustomerInsightDto dto50 = insightEngine.analyzeInsight(
                    phrase, history, "Chị",
                    FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION, null,
                    50, 20, 0
            );
            System.out.println("Phrase [" + phrase + "] at temp 50 -> temp=" + dto50.buyingTemperature()
                    + ", delta=" + dto50.temperatureDelta()
                    + ", momentum=" + dto50.momentum());

            CustomerInsightDto dto30 = insightEngine.analyzeInsight(
                    phrase, history, "Chị",
                    FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION, null,
                    30, 20, 0
            );
            System.out.println("Phrase [" + phrase + "] at temp 30 -> temp=" + dto30.buyingTemperature()
                    + ", delta=" + dto30.temperatureDelta()
                    + ", momentum=" + dto30.momentum());
        }
    }
}
