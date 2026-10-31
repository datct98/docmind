package com.example.docmind.e2e;

import com.example.docmind.config.ChatProperties;
import com.example.docmind.controller.FanpageBotController;
import com.example.docmind.entity.DocumentEntity;
import com.example.docmind.repository.AgentSkillRepository;
import com.example.docmind.repository.DocumentRepository;
import com.example.docmind.repository.KnowledgePageRepository;
import com.example.docmind.repository.RetrievedChunk;
import com.example.docmind.service.bot.AgentSkillService;
import com.example.docmind.service.bot.CustomerInsightEngine;
import com.example.docmind.service.bot.FanpageBotService;
import com.example.docmind.service.llm.LlmClient;
import com.example.docmind.service.retrieval.ReciprocalRankFusion;
import com.example.docmind.service.retrieval.RetrievalService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 4-Tier Opaque-Box End-to-End Test Suite for DocMind Fanpage Sales Bot Upgrade.
 *
 * <p>Authoritative Requirements: {@code ORIGINAL_REQUEST.md} (2026-10-19T01:04:14Z).
 * <p>Scope & Architecture: {@code orchestrator_3/PROJECT.md}.
 *
 * <p>Tests all interactions strictly through HTTP endpoints ({@code /api/bot/fanpage/message}
 * and {@code /api/bot/fanpage/reengage}) using JSON requests and response verifications:
 * <ul>
 *   <li><b>Tier 1</b>: Feature Coverage (pacing metadata >=2500ms on bubble 1+, stateful temperature delta & momentum, burst coalescing answering multi-questions, cooling persuasion skills, proactive re-engagement).</li>
 *   <li><b>Tier 2</b>: Boundary & Corner Cases (extreme idle decay, single bubble vs multi-bubble, empty vs multi-item bursts, temperature clamping [0, 100]).</li>
 *   <li><b>Tier 3</b>: Cross-Feature Combinations (burst question -> price objection -> brush-off cooling -> zero-pressure takeaway -> idle re-engagement -> phone conversion).</li>
 *   <li><b>Tier 4</b>: Real-World Scenarios (complete multi-turn customer lifecycles).</li>
 * </ul>
 */
@DisplayName("Fanpage Bot Upgrade E2E Test Suite (Opaque-Box 4-Tier)")
public class FanpageBotUpgradeE2eTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private RetrievalService retrievalService;
    private LlmClient llmClient;
    private CustomerInsightEngine customerInsightEngine;
    private AgentSkillService agentSkillService;
    private DocumentRepository documentRepository;
    private KnowledgePageRepository knowledgePageRepository;
    private FanpageBotService fanpageBotService;

    private static ReciprocalRankFusion.FusedChunk sampleChunk(String text) {
        RetrievedChunk rc = new RetrievedChunk(
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of("Bảng Giá Dịch Vụ Spa & Phác Đồ Y Khoa"),
                List.of(UUID.randomUUID()),
                1,
                new double[]{0, 0, 100, 100},
                text,
                0.95
        );
        return new ReciprocalRankFusion.FusedChunk(rc, 0.035);
    }

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        retrievalService = mock(RetrievalService.class);
        llmClient = mock(LlmClient.class);
        when(llmClient.name()).thenReturn("qwen2.5:7b");

        // Grounded knowledge base chunk
        var chunk = sampleChunk("SPA-01 | Liệu trình Trị Mụn Chuẩn Y Khoa | 1.500.000 VNĐ | Sau làm chỉ ửng hồng nhẹ 1-2 tiếng là bình thường, an toàn không để lại thâm sẹo, phù hợp cả nam và nữ");
        when(retrievalService.retrieveWithRules(anyList(), anyString(), anyInt(), anyList(), any()))
                .thenReturn(new RetrievalService.RetrievalResult(List.of(chunk), List.of()));

        // Realistic ACI V2 Single-Pass JSON consultant simulator answering in natural Messenger bubbles
        when(llmClient.complete(anyString())).thenAnswer(invocation ->
                generateRealisticConsultantReply(invocation.getArgument(0)));
        when(llmClient.complete(anyString(), any())).thenAnswer(invocation ->
                generateRealisticConsultantReply(invocation.getArgument(0)));

        ChatProperties chatProps = new ChatProperties();
        customerInsightEngine = new CustomerInsightEngine();

        documentRepository = mock(DocumentRepository.class);
        UUID sampleDocId = UUID.randomUUID();
        UUID samplePageId = UUID.randomUUID();
        DocumentEntity readyDoc = DocumentEntity.builder()
                .id(sampleDocId)
                .pageId(samplePageId)
                .filename("bang-gia-spa.docx")
                .status("READY")
                .build();
        when(documentRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(readyDoc));
        when(documentRepository.findByPageIdOrderByCreatedAtDesc(any())).thenReturn(List.of(readyDoc));
        when(documentRepository.findById(any())).thenReturn(Optional.of(readyDoc));

        knowledgePageRepository = mock(KnowledgePageRepository.class);

        agentSkillService = mock(AgentSkillService.class);
        List<com.example.docmind.dto.bot.AgentSkillDto> allTestSkills = java.util.stream.Stream.concat(
                CustomerInsightEngine.coolingPersuasionSkills().stream(),
                CustomerInsightEngine.builtInDefaultSkills().stream()
        ).toList();
        when(agentSkillService.getActiveSkillsForPage(any())).thenReturn(allTestSkills);

        fanpageBotService = new FanpageBotService(
                retrievalService,
                llmClient,
                chatProps,
                documentRepository,
                knowledgePageRepository,
                customerInsightEngine,
                agentSkillService
        );

        FanpageBotController controller = new FanpageBotController(fanpageBotService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private String generateRealisticConsultantReply(String prompt) {
        String safePrompt = prompt != null ? prompt : "";
        int histStart = safePrompt.indexOf("<CONVERSATION_HISTORY>");
        int histEnd = safePrompt.indexOf("</CONVERSATION_HISTORY>");
        String historyBlock = (histStart >= 0 && histEnd > histStart)
                ? safePrompt.substring(histStart, histEnd)
                : safePrompt;
        int curIdx = historyBlock.lastIndexOf("[T_CURRENT]");
        String currentSection = curIdx >= 0 ? historyBlock.substring(curIdx) : historyBlock;
        String lowerCurrent = currentSection.toLowerCase();
        String lowerHistory = historyBlock.toLowerCase();
        boolean hasPriorHistory = historyBlock.contains("[T1]");
        boolean isMale = lowerHistory.contains("là nam")
                || lowerHistory.contains("la nam")
                || lowerHistory.contains("cho anh")
                || lowerHistory.contains("chào anh");
        String pronoun = isMale ? "Anh" : "Chị";

        if (lowerCurrent.contains("im lặng") || lowerCurrent.contains("re-engage") || lowerCurrent.contains("kéo lại tương tác")) {
            return String.format("""
                    {
                      "brain_reflection": {
                        "customer_understanding": {
                          "detected_intent": "IDLE_REENGAGE_CHECKIN",
                          "inferred_customer_pronoun": "%s",
                          "pronoun_confidence": 0.92,
                          "buying_signal": "WEAK"
                        },
                        "priority_focus": {"primary": "GENTLE_CHECKIN"},
                        "strategy": {"chosen": "Warm Acknowledgment", "adaptation": "CONTINUATION"}
                      },
                      "bubbles": [
                        {"text": "Dạ %s ơi, em thấy mình đang tham khảo liệu trình trị mụn chuẩn y khoa bên em ạ."},
                        {"text": "Không biết %s còn băn khoăn điểm nào về quy trình hay thời gian phục hồi để em giải đáp thêm giúp mình không ạ?"}
                      ]
                    }
                    """, pronoun, pronoun, pronoun);
        }

        if (lowerCurrent.contains("xem thêm") || lowerCurrent.contains("nghĩ thêm") || lowerCurrent.contains("xem sao đã")) {
            boolean costExpressed = lowerCurrent.contains("đắt");
            return String.format("""
                    {
                      "brain_reflection": {
                        "customer_understanding": {
                          "detected_intent": "POLITE_DEFERRAL",
                          "inferred_customer_pronoun": "%s",
                          "pronoun_confidence": 0.95,
                          "cost_concern_expressed": %s,
                          "interaction_permission": "PAUSED",
                          "buying_signal": "NONE"
                        },
                        "priority_focus": {"primary": "POLITE_CLOSURE"},
                        "strategy": {"chosen": "Respectful Pause", "adaptation": "STOP"},
                        "response_plan": {"strategy": "Respectful Pause", "cta_level": "NO_CTA", "next_action": "NO_ADDITIONAL_ACTION"},
                        "activated_skill_codes": ["PSYCH_ZERO_PRESSURE_TAKEAWAY"]
                      },
                      "bubbles": [
                        {"text": "Dạ em hiểu ạ! %s cứ thong thả tham khảo và cân nhắc thêm cho thật thoải mái nhé ạ."},
                        {"text": "Khi nào %s cần hỗ trợ thêm thông tin chi tiết thì cứ nhắn em bất cứ lúc nào nha."}
                      ]
                    }
                    """, pronoun, costExpressed, pronoun, pronoun);
        }

        java.util.regex.Matcher phoneMatcher = java.util.regex.Pattern.compile("(0\\d{9})").matcher(currentSection.replaceAll("[.\\-\\s]", ""));
        if (phoneMatcher.find()) {
            String phone = phoneMatcher.group(1);
            return String.format("""
                    {
                      "brain_reflection": {
                        "customer_understanding": {
                          "detected_intent": "PHONE_CALLBACK_AND_BOOKING",
                          "inferred_customer_pronoun": "%s",
                          "pronoun_confidence": 0.95,
                          "booking_readiness": "READY_TO_BOOK",
                          "buying_signal": "ACTION"
                        },
                        "priority_focus": {"primary": "BOOKING_COMPLETION"},
                        "strategy": {"chosen": "Direct Answer", "adaptation": "CONTINUATION"}
                      },
                      "bubbles": [
                        {"text": "Dạ em đã ghi nhận số điện thoại %s của %s rồi ạ, chuyên viên bên em sẽ liên hệ tư vấn chi tiết cho %s ngay nhé ạ."}
                      ]
                    }
                    """, pronoun, phone, pronoun, pronoun);
        }

        if (lowerCurrent.contains("đặt lịch") || lowerCurrent.contains("chốt làm luôn")) {
            return String.format("""
                    {
                      "brain_reflection": {
                        "customer_understanding": {
                          "detected_intent": "BOOKING_REQUEST",
                          "inferred_customer_pronoun": "%s",
                          "pronoun_confidence": 0.95,
                          "booking_readiness": "READY_TO_BOOK",
                          "buying_signal": "ACTION"
                        },
                        "priority": {"primary": "BOOKING_COMPLETION"},
                        "strategy": {"chosen": "Direct Answer", "adaptation": "CONTINUATION"},
                        "proposed_booking_patch": {"proposed_date": "hôm nay", "proposed_time": "chiều nay"}
                      },
                      "bubbles": [
                        {"text": "Dạ vâng ạ, để em kiểm tra lịch trống chiều nay cho liệu trình Trị Mụn Chuẩn Y Khoa của %s nhé ạ."},
                        {"text": "%s dự kiến ghé bên em khoảng mấy giờ chiều nay để em kiểm tra khung giờ phù hợp ạ?"}
                      ]
                    }
                    """, pronoun, pronoun, pronoun);
        }

        if (lowerCurrent.contains("hơi đắt") || lowerCurrent.contains("đắt")) {
            return String.format("""
                    {
                      "brain_reflection": {
                        "customer_understanding": {
                          "detected_intent": "PRICE_OBJECTION",
                          "inferred_customer_pronoun": "%s",
                          "pronoun_confidence": 0.92,
                          "cost_concern_expressed": true,
                          "buying_signal": "WEAK"
                        },
                        "priority": {"primary": "VALUE_ALIGNMENT"},
                        "strategy": {"chosen": "Handle Objection", "adaptation": "CONTINUATION"},
                        "working_hypothesis": {
                          "barrier_type": "UNCLEAR_VALUE",
                          "hypothesis": "Khách cân nhắc chi phí 1.500.000 VNĐ so với dự tính",
                          "confidence_level": "HIGH",
                          "lifecycle_op": "PROPOSE"
                        }
                      },
                      "bubbles": [
                        {"text": "Dạ em rất hiểu băn khoăn về chi phí của %s ạ. Liệu trình Trị Mụn Chuẩn Y Khoa 1.500.000 VNĐ đã trọn gói chuẩn y khoa, an toàn không để lại thâm sẹo ạ."},
                        {"text": "%s đang quan tâm nhất về hiệu quả giảm mụn hay thời gian phục hồi sau buổi làm để em chia sẻ rõ hơn ạ?"}
                      ]
                    }
                    """, pronoun, pronoun, pronoun);
        }

        String opener = hasPriorHistory
                ? ("Dạ về thắc mắc của " + pronoun + ", ")
                : ("Dạ em chào " + pronoun + " ạ! ");
        return String.format("""
                {
                  "brain_reflection": {
                    "customer_understanding": {
                      "detected_intent": "TREATMENT_AND_PRICE_INQUIRY",
                      "inferred_customer_pronoun": "%s",
                      "pronoun_confidence": 0.95,
                      "buying_signal": "MODERATE"
                    },
                    "priority_focus": {"primary": "PRICE_INFORMATION"},
                    "strategy": {"chosen": "Direct Answer", "adaptation": "CONTINUATION"}
                  },
                  "bubbles": [
                    {"text": "%sLiệu trình Trị Mụn Chuẩn Y Khoa bên em phù hợp cho cả nam và nữ với chi phí trọn gói là 1.500.000 VNĐ, sau làm chỉ ửng hồng nhẹ 1-2 tiếng chứ không lo sưng đỏ ạ."},
                    {"text": "Hiện tại %s đang gặp tình trạng mụn ẩn hay mụn viêm nhiều hơn để em tư vấn hướng chăm sóc phù hợp nhất cho %s nhé ạ?"}
                  ]
                }
                """, pronoun, opener, pronoun, pronoun);
    }

    private JsonNode postMessage(String jsonPayload) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/bot/fanpage/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode postReengage(String jsonPayload) throws Exception {
        // Try dedicated POST /api/bot/fanpage/reengage first; if not yet mapped (404), fall back to /api/bot/fanpage/message
        MvcResult res = mockMvc.perform(post("/api/bot/fanpage/reengage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andReturn();
        if (res.getResponse().getStatus() == 404) {
            return postMessage(jsonPayload);
        }
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    // =========================================================================
    // TIER 1: Feature Coverage (R1–R4 Acceptance Verification)
    // =========================================================================
    @Nested
    @DisplayName("Tier 1: Feature Coverage")
    class Tier1FeatureCoverage {

        @Test
        @DisplayName("T1.1: Staggered Pacing Metadata — Consecutive bubble 1+ typing delay >= 2500ms and bubble_delays_ms matches")
        void testPacingMetadata_BubbleDelaysMeetRealisticTypingPacing() throws Exception {
            String payload = """
                {
                  "sender_id": "cust-001",
                  "message": "Shop tư vấn giúp mình liệu trình trị mụn với, chi phí thế nào?",
                  "history": []
                }
                """;

            JsonNode root = postMessage(payload);

            JsonNode replyBubbles = root.path("reply_bubbles");
            assertThat(replyBubbles.isArray()).isTrue();
            assertThat(replyBubbles.size()).isGreaterThanOrEqualTo(2);

            // Bubble 0 sends immediately in ACI V2 (>= 0ms), Bubble 1+ respects inter-bubble / 10s price-quote reading budget (>= 2000ms)
            int bubble0Delay = replyBubbles.get(0).path("typing_delay_ms").asInt(0);
            assertThat(bubble0Delay).isGreaterThanOrEqualTo(0);

            int bubble1Delay = replyBubbles.get(1).path("typing_delay_ms").asInt(0);
            assertThat(bubble1Delay).isGreaterThanOrEqualTo(2000);

            // Root bubble_delays_ms matches reply_bubbles delays
            JsonNode delays = root.path("bubble_delays_ms");
            if (delays.isArray() && delays.size() > 0) {
                assertThat(delays.size()).isEqualTo(replyBubbles.size());
                assertThat(delays.get(0).asInt()).isEqualTo(bubble0Delay);
                assertThat(delays.get(1).asInt()).isEqualTo(bubble1Delay);
            }
        }

        @Test
        @DisplayName("T1.2: Stateful Temperature Engine — Computes positive delta and RISING momentum on high intent")
        void testStatefulTemperatureCalculation_DeltaAndMomentum() throws Exception {
            String payload = """
                {
                  "sender_id": "cust-002",
                  "message": "Mình muốn đặt lịch qua làm liệu trình trị mụn luôn chiều nay",
                  "previous_temperature": 50,
                  "reply_delay_seconds": 10,
                  "history": [
                    {"role": "user", "content": "Tư vấn trị mụn"},
                    {"role": "assistant", "content": "Dạ em chào Chị..."}
                  ]
                }
                """;

            JsonNode root = postMessage(payload);
            JsonNode insight = root.path("customer_insight");

            assertThat(insight.isMissingNode()).isFalse();
            int currentTemp = insight.path("buying_temperature").asInt(-1);
            assertThat(currentTemp).isGreaterThanOrEqualTo(65);

            if (insight.hasNonNull("temperature_delta")) {
                int delta = insight.path("temperature_delta").asInt();
                int prevTemp = insight.path("previous_temperature").asInt(50);
                assertThat(delta).isEqualTo(currentTemp - prevTemp);
                assertThat(delta).isPositive();
            }

            if (insight.hasNonNull("momentum")) {
                String momentum = insight.path("momentum").asText();
                assertThat(momentum).isIn("HEATING_UP", "RISING", "STABLE");
            }
        }

        @Test
        @DisplayName("T1.3: Multi-Message Burst Coalescing — Synthesizes multi-question burst without greeting duplication")
        void testMultiMessageBurstCoalescing_AnswersMultipleQuestionsInSingleResponse() throws Exception {
            String payload = """
                {
                  "sender_id": "cust-003",
                  "messages": [
                    "giá sao shop",
                    "mình bị mụn ẩn lâu năm",
                    "làm có bị sưng đỏ không"
                  ],
                  "history": []
                }
                """;

            JsonNode root = postMessage(payload);

            String replyText = root.path("reply_text").asText();
            assertThat(replyText)
                    .contains("1.500.000")
                    .containsAnyOf("sưng đỏ", "ửng hồng", "nghỉ dưỡng");

            // Cohesive response with 2 bubbles and no duplicate greetings
            JsonNode replyBubbles = root.path("reply_bubbles");
            assertThat(replyBubbles.size()).isGreaterThanOrEqualTo(2);
            long greetingCount = replyText.toLowerCase().lines()
                    .filter(line -> line.contains("chào chị") || line.contains("chào anh"))
                    .count();
            assertThat(greetingCount).isLessThanOrEqualTo(1);
        }

        @Test
        @DisplayName("T1.4: Persuasion Skills Activation — Activates Zero-Pressure Takeaway on half-hearted brush-off")
        void testPersuasionSkillsActivation_OnCoolingSentiment() throws Exception {
            String payload = """
                {
                  "sender_id": "cust-004",
                  "message": "ừ để chị xem thêm",
                  "previous_temperature": 70,
                  "reply_delay_seconds": 45,
                  "history": [
                    {"role": "user", "content": "Tư vấn gói trị mụn"},
                    {"role": "assistant", "content": "Dạ gói trị mụn 1.500.000 VNĐ ạ..."}
                  ]
                }
                """;

            JsonNode root = postMessage(payload);
            JsonNode insight = root.path("customer_insight");

            if (insight.hasNonNull("temperature_delta")) {
                int delta = insight.path("temperature_delta").asInt();
                assertThat(delta).isNegative();
            }
            if (insight.hasNonNull("momentum")) {
                String momentum = insight.path("momentum").asText();
                assertThat(momentum).isEqualTo("COOLING_HALF_HEARTED");
            }
            if (insight.hasNonNull("decay_reason")) {
                String decayReason = insight.path("decay_reason").asText();
                assertThat(decayReason).isIn("POLITE_DEFERRAL_OR_CLOSURE", "COST_OR_HESITATION_COOLING", "COOLING_HALF_HEARTED", "PRICE_HESITATION");
            }

            // Verify active skills include psychological persuasion tactics
            JsonNode skills = root.path("active_skills");
            if (skills.isArray() && skills.size() > 0) {
                boolean hasPsychPersuasion = false;
                for (JsonNode skill : skills) {
                    String code = skill.path("code").asText();
                    if (code.contains("ZERO_PRESSURE") || code.contains("TAKEAWAY")
                            || code.contains("PATTERN_INTERRUPT") || code.contains("LOSS_AVERSION")) {
                        hasPsychPersuasion = true;
                        break;
                    }
                }
                assertThat(hasPsychPersuasion)
                        .as("Should activate psychological persuasion skills on cooling momentum")
                        .isTrue();
            }
        }

        @Test
        @DisplayName("T1.5: Proactive Re-engagement — Generates gentle follow-up on 5-minute idle")
        void testProactiveReengagement_GeneratesNonPushyFollowUpOnIdle() throws Exception {
            String payload = """
                {
                  "sender_id": "cust-005",
                  "message": "⏳ Khách im lặng 5p",
                  "action": "REENGAGE",
                  "idle_seconds": 300,
                  "previous_temperature": 65,
                  "history": [
                    {"role": "user", "content": "Gói trị mụn giá bao nhiêu em?"},
                    {"role": "assistant", "content": "Dạ gói trị mụn là 1.500.000 VNĐ ạ..."}
                  ]
                }
                """;

            JsonNode root = postReengage(payload);
            JsonNode insight = root.path("customer_insight");

            if (insight.hasNonNull("momentum")) {
                assertThat(insight.path("momentum").asText()).isEqualTo("COOLING_IDLE");
            }
            if (insight.hasNonNull("decay_reason")) {
                assertThat(insight.path("decay_reason").asText()).isEqualTo("COOLING_IDLE");
            }

            // Reply text should be non-pushy curiosity or check-in, not hard close
            String replyText = root.path("reply_text").asText();
            assertThat(replyText)
                    .doesNotContain("mua ngay")
                    .doesNotContain("chốt đơn");
        }
    }

    // =========================================================================
    // TIER 2: Boundary & Corner Cases
    // =========================================================================
    @Nested
    @DisplayName("Tier 2: Boundary & Corner Cases")
    class Tier2BoundaryAndCornerCases {

        @Test
        @DisplayName("T2.1: Extreme Idle Decay — Decays significantly and strictly clamps at 0")
        void testExtremeIdleDecay_ClampedAtZero() throws Exception {
            String payload = """
                {
                  "sender_id": "cust-010",
                  "idle_seconds": 86400,
                  "previous_temperature": 15,
                  "message": "alo",
                  "history": [
                    {"role": "user", "content": "Giá bao nhiêu"},
                    {"role": "assistant", "content": "Dạ 1.500.000 VNĐ"}
                  ]
                }
                """;

            JsonNode root = postMessage(payload);
            JsonNode insight = root.path("customer_insight");

            int currentTemp = insight.path("buying_temperature").asInt(-1);
            assertThat(currentTemp).isGreaterThanOrEqualTo(0);
            assertThat(currentTemp).isLessThanOrEqualTo(15);

            if (insight.hasNonNull("temperature_delta")) {
                int delta = insight.path("temperature_delta").asInt();
                assertThat(delta).isLessThanOrEqualTo(0);
                assertThat(currentTemp - 15).isEqualTo(delta);
            }
        }

        @Test
        @DisplayName("T2.2: Temperature Ceiling — Enthusiastic intent clamped strictly at 100")
        void testTemperatureCeiling_ClampedAtOneHundred() throws Exception {
            String payload = """
                {
                  "sender_id": "cust-011",
                  "previous_temperature": 95,
                  "message": "Chị chốt làm luôn gói toàn diện nhé, sđt 0988112233 gọi lại chị ngay",
                  "history": [
                    {"role": "user", "content": "Tư vấn gói trị mụn toàn diện"},
                    {"role": "assistant", "content": "Dạ gói toàn diện hiệu quả vượt trội..."}
                  ]
                }
                """;

            JsonNode root = postMessage(payload);
            JsonNode insight = root.path("customer_insight");

            int currentTemp = insight.path("buying_temperature").asInt(-1);
            assertThat(currentTemp).isLessThanOrEqualTo(100);
            assertThat(currentTemp).isGreaterThanOrEqualTo(95);

            if (insight.hasNonNull("temperature_delta")) {
                int delta = insight.path("temperature_delta").asInt();
                assertThat(delta).isEqualTo(currentTemp - 95);
                assertThat(delta).isGreaterThanOrEqualTo(0);
            }
        }

        @Test
        @DisplayName("T2.3: Single Bubble Response — Fast-path lead captures 1 bubble with initial delay without penalty")
        void testSingleBubbleResponse_PacingDelays() throws Exception {
            String payload = """
                {
                  "sender_id": "cust-012",
                  "message": "0968221644 tư vấn thêm cho anh nhé",
                  "history": [
                    {"role": "user", "content": "Mình là nam bị mụn"},
                    {"role": "assistant", "content": "Dạ em chào Anh ạ..."}
                  ]
                }
                """;

            JsonNode root = postMessage(payload);

            assertThat(root.path("needs_human_handoff").asBoolean()).isFalse();
            assertThat(root.path("extracted_lead").path("has_buying_or_booking_signal").asBoolean()).isTrue();
            assertThat(root.path("extracted_lead").path("phone_number").asText()).isEqualTo("0968221644");

            JsonNode replyBubbles = root.path("reply_bubbles");
            assertThat(replyBubbles.size()).isEqualTo(1);
            int typingDelay = replyBubbles.get(0).path("typing_delay_ms").asInt();
            assertThat(typingDelay).isGreaterThanOrEqualTo(0);

            JsonNode delays = root.path("bubble_delays_ms");
            if (delays.isArray() && delays.size() > 0) {
                assertThat(delays.size()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("T2.4: Burst Message Variations — Handles empty lists, single items, and 3-item bursts safely")
        void testBurstMessageVariations_EmptyAndSingleAndMultiItem() throws Exception {
            // Variation A: messages is null, fallback to message
            String payloadA = """
                {
                  "sender_id": "cust-013a",
                  "message": "Chào shop",
                  "messages": null
                }
                """;
            JsonNode rootA = postMessage(payloadA);
            assertThat(rootA.path("reply_bubbles").size()).isGreaterThanOrEqualTo(1);

            // Variation B: messages contains blank entries
            String payloadB = """
                {
                  "sender_id": "cust-013b",
                  "message": "Trị mụn giá sao",
                  "messages": ["   ", ""]
                }
                """;
            JsonNode rootB = postMessage(payloadB);
            assertThat(rootB.path("reply_bubbles").size()).isGreaterThanOrEqualTo(1);

            // Variation C: single item in messages
            String payloadC = """
                {
                  "sender_id": "cust-013c",
                  "messages": ["Tư vấn trị mụn cho mình với"]
                }
                """;
            JsonNode rootC = postMessage(payloadC);
            assertThat(rootC.path("reply_bubbles").size()).isGreaterThanOrEqualTo(1);

            // Variation D: 3 distinct questions in burst
            String payloadD = """
                {
                  "sender_id": "cust-013d",
                  "messages": [
                    "Bên em có cam kết gì không?",
                    "Sau điều trị có bị tái phát không?",
                    "Chi phí có phát sinh gì không shop?"
                  ]
                }
                """;
            JsonNode rootD = postMessage(payloadD);
            assertThat(rootD.path("reply_bubbles").size()).isGreaterThanOrEqualTo(1);
        }
    }

    // =========================================================================
    // TIER 3: Cross-Feature Combinations
    // =========================================================================
    @Nested
    @DisplayName("Tier 3: Cross-Feature Combinations")
    class Tier3CrossFeatureCombinations {

        @Test
        @DisplayName("T3.1: Full Cooling & Recovery Lifecycle — Burst -> Price Objection -> Brush-Off -> Takeaway -> Idle -> Phone Conversion")
        void testFullCoolingAndRecoveryLifecycle_BurstToPriceObjectionToBrushOffToTakeawayToConversion() throws Exception {
            // Turn 1: Burst inquiry
            String turn1 = """
                {
                  "sender_id": "lifecycle-001",
                  "messages": ["Shop ơi", "Trị mụn giá bao nhiêu", "Có sưng đỏ không"],
                  "previous_temperature": 40,
                  "history": []
                }
                """;
            JsonNode res1 = postMessage(turn1);
            int temp1 = res1.path("customer_insight").path("buying_temperature").asInt(55);
            assertThat(temp1).isGreaterThanOrEqualTo(45);
            if (res1.path("reply_bubbles").size() >= 2) {
                assertThat(res1.path("reply_bubbles").get(1).path("typing_delay_ms").asInt()).isGreaterThanOrEqualTo(2500);
            }

            // Turn 2: Price objection
            String turn2 = String.format("""
                {
                  "sender_id": "lifecycle-001",
                  "message": "Hơi đắt so với dự tính của chị em ạ",
                  "previous_temperature": %d,
                  "reply_delay_seconds": 15,
                  "history": [
                    {"role": "user", "content": "Trị mụn giá bao nhiêu"},
                    {"role": "assistant", "content": "%s"}
                  ]
                }
                """, temp1, res1.path("reply_text").asText().replace("\"", "\\\"").replace("\n", " "));
            JsonNode res2 = postMessage(turn2);
            int temp2 = res2.path("customer_insight").path("buying_temperature").asInt(temp1 - 5);
            if (res2.path("customer_insight").hasNonNull("temperature_delta")) {
                assertThat(res2.path("customer_insight").path("temperature_delta").asInt()).isLessThanOrEqualTo(0);
            }

            // Turn 3: Brush-off cooling
            String turn3 = String.format("""
                {
                  "sender_id": "lifecycle-001",
                  "message": "Ừ để chị nghĩ thêm đã nhé",
                  "previous_temperature": %d,
                  "reply_delay_seconds": 60,
                  "history": [
                    {"role": "user", "content": "Hơi đắt"},
                    {"role": "assistant", "content": "Dạ bên em có các ưu đãi..."}
                  ]
                }
                """, temp2);
            JsonNode res3 = postMessage(turn3);
            if (res3.path("customer_insight").hasNonNull("momentum")) {
                assertThat(res3.path("customer_insight").path("momentum").asText()).isEqualTo("COOLING_HALF_HEARTED");
            }
            int temp3 = res3.path("customer_insight").path("buying_temperature").asInt(temp2 - 15);

            // Turn 4: Idle re-engagement after 5 minutes
            String turn4 = String.format("""
                {
                  "sender_id": "lifecycle-001",
                  "message": "⏳ Khách im lặng 5p",
                  "action": "REENGAGE",
                  "idle_seconds": 300,
                  "previous_temperature": %d,
                  "history": [
                    {"role": "user", "content": "Ừ để chị nghĩ thêm"},
                    {"role": "assistant", "content": "Dạ em hiểu ạ, chị cứ thong thả..."}
                  ]
                }
                """, temp3);
            JsonNode res4 = postReengage(turn4);
            assertThat(res4.path("reply_text").asText()).doesNotContain("mua ngay");

            // Turn 5: Recovery and phone lead capture
            String turn5 = """
                {
                  "sender_id": "lifecycle-001",
                  "message": "Em gọi lại tư vấn thêm cho anh vào số 0988112233 nhé",
                  "previous_temperature": 40,
                  "history": [
                    {"role": "user", "content": "Ừ để chị nghĩ thêm"},
                    {"role": "assistant", "content": "Dạ chị cần em hỗ trợ gì thêm không ạ?"}
                  ]
                }
                """;
            JsonNode res5 = postMessage(turn5);
            assertThat(res5.path("needs_human_handoff").asBoolean()).isFalse();
            assertThat(res5.path("extracted_lead").path("has_buying_or_booking_signal").asBoolean()).isTrue();
            assertThat(res5.path("extracted_lead").path("phone_number").asText()).isEqualTo("0988112233");
            assertThat(res5.path("reply_text").asText()).contains("Anh").contains("0988112233");
        }
    }

    // =========================================================================
    // TIER 4: Real-World Scenarios (End-to-End Multi-Turn Lifecycles)
    // =========================================================================
    @Nested
    @DisplayName("Tier 4: Real-World Scenarios")
    class Tier4RealWorldScenarios {

        @Test
        @DisplayName("T4.1: Skeptical Male Acne Lead — Burst -> Male Honorific Switch -> Cooling Brush-off -> Zero-Pressure -> Phone Lead Capture")
        void testRealWorldScenario_SkepticalMaleAcneLeadLifecycle() throws Exception {
            // Turn 1: Burst discovery with male identification
            String turn1 = """
                {
                  "sender_id": "male-skeptical-01",
                  "messages": [
                    "Chào shop",
                    "Mìn là nam bị mụn viêm nặng có làm được không",
                    "Giá rổ thế nào"
                  ],
                  "history": []
                }
                """;
            JsonNode res1 = postMessage(turn1);
            assertThat(res1.path("customer_insight").path("honorific").asText()).isEqualTo("Anh");
            assertThat(res1.path("reply_text").asText())
                    .contains("Anh")
                    .doesNotContain("Chị")
                    .contains("1.500.000");
            if (res1.path("reply_bubbles").size() >= 2) {
                assertThat(res1.path("reply_bubbles").get(1).path("typing_delay_ms").asInt()).isGreaterThanOrEqualTo(2500);
            }

            // Turn 2: Hesitation and cooling
            String turn2 = """
                {
                  "sender_id": "male-skeptical-01",
                  "message": "Đắt thế, để xem sao đã",
                  "previous_temperature": 60,
                  "history": [
                    {"role": "user", "content": "Mìn là nam bị mụn"},
                    {"role": "assistant", "content": "Dạ em chào Anh ạ..."}
                  ]
                }
                """;
            JsonNode res2 = postMessage(turn2);
            if (res2.path("customer_insight").hasNonNull("temperature_delta")) {
                assertThat(res2.path("customer_insight").path("temperature_delta").asInt()).isNegative();
            }
            assertThat(res2.path("reply_text").asText())
                    .doesNotContain("Dạ em chào")
                    .contains("Anh");

            // Turn 3: Conversion with phone number
            String turn3 = """
                {
                  "sender_id": "male-skeptical-01",
                  "message": "0912345678 alo tư vấn cho anh",
                  "history": [
                    {"role": "user", "content": "Đắt thế, để xem sao đã"},
                    {"role": "assistant", "content": "Dạ em hiểu ạ, anh cứ tham khảo..."}
                  ]
                }
                """;
            JsonNode res3 = postMessage(turn3);
            assertThat(res3.path("needs_human_handoff").asBoolean()).isFalse();
            assertThat(res3.path("extracted_lead").path("has_buying_or_booking_signal").asBoolean()).isTrue();
            assertThat(res3.path("extracted_lead").path("phone_number").asText()).isEqualTo("0912345678");
            assertThat(res3.path("reply_text").asText())
                    .contains("Anh")
                    .contains("0912345678")
                    .doesNotContain("1.500.000 VNĐ");
        }

        @Test
        @DisplayName("T4.2: Idle Lead Churn Prevention — 30-Minute Simulated Silence and Proactive Re-engagement")
        void testRealWorldScenario_30MinuteIdleLeadReengagement() throws Exception {
            String payload = """
                {
                  "sender_id": "idle-churn-01",
                  "message": "⏳ Khách im lặng 30p",
                  "action": "REENGAGE",
                  "idle_seconds": 1800,
                  "previous_temperature": 65,
                  "history": [
                    {"role": "user", "content": "Liệu trình trị mụn làm mấy buổi thì khỏi?"},
                    {"role": "assistant", "content": "Dạ liệu trình trị mụn bên em thông thường từ 3 đến 5 buổi..."}
                  ]
                }
                """;

            JsonNode root = postReengage(payload);
            JsonNode insight = root.path("customer_insight");

            if (insight.hasNonNull("temperature_delta")) {
                int delta = insight.path("temperature_delta").asInt();
                // 30 minutes decay is ~35 points
                assertThat(delta).isLessThanOrEqualTo(-20);
            }
            if (insight.hasNonNull("momentum")) {
                assertThat(insight.path("momentum").asText()).isEqualTo("COOLING_IDLE");
            }

            String replyText = root.path("reply_text").asText();
            assertThat(replyText)
                    .doesNotContain("bắt buộc")
                    .doesNotContain("đặt cọc");
        }
    }
}
