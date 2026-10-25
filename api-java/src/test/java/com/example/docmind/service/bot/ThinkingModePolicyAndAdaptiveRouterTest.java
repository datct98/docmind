package com.example.docmind.service.bot;

import com.docmind.guardrail.ReplyContractValidator;
import com.example.docmind.config.LlmProperties;
import com.example.docmind.config.OllamaProperties;
import com.example.docmind.dto.PagePersonaConfig;
import com.example.docmind.dto.bot.AgentSkillDto;
import com.example.docmind.dto.bot.CustomerInsightDto;
import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.dto.bot.FanpageBotResponse;
import com.example.docmind.repository.RetrievedChunk;
import com.example.docmind.service.llm.OllamaLlmClient;
import com.example.docmind.service.llm.OpenAiCompatibleLlmClient;
import com.example.docmind.service.llm.ReasoningBudget;
import com.example.docmind.service.retrieval.PromptBuilder;
import com.example.docmind.service.retrieval.ReciprocalRankFusion;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ThinkingModePolicyAndAdaptiveRouterTest {

    private static final UUID PAGE_ID = UUID.fromString("b23cd382-4f7a-4829-8c54-1d410da14bf8");
    private static final UUID DOC_ID = UUID.fromString("c9481efb-f3a9-4613-a2e0-fe50e9b99b39");

    private static List<ReciprocalRankFusion.FusedChunk> buildTestChunks() {
        String massageTable = """
                | Mã gói | Tên gói | Giá | Tổng thời gian | Bao gồm | Không gian | KTV | Điểm nổi bật | Phù hợp |
                | --- | --- | --- | --- | --- | --- | --- | --- | --- |
                | NS01 | Massage cơ bản | 399.000VNĐ | 60 phút | Full body massage trị liệu cơ bản | Phòng cộng đồng có rèm riêng | KTV tiêu chuẩn | Massage trị liệu toàn thân 60 phút ấn huyệt giảm đau mỏi cổ vai gáy lưng, buffet thực dưỡng miễn phí | Khách thư giãn nhanh, dân văn phòng |
                | NS02 | Massage ngâm thảo dược | 530.000VNĐ | 90 phút | 10p ngâm bồn thảo dược + 5p xông hơi + 75p massage | Phòng cộng đồng có rèm | KTV tiêu chuẩn | Ngâm bồn thảo dược ấm giúp giãn cơ, giảm đau mỏi cổ vai gáy lưng | Khách mỏi cơ, lạnh người |
                | DS02 | Dưỡng sinh cơ bản | 239.000VNĐ | 60 phút | Rửa mặt + massage mặt + gội thảo dược + chườm thảo dược cổ vai + ăn nhẹ | Khu dưỡng sinh chung | KTV dưỡng sinh | Thư giãn đầu cổ vai nhẹ nhàng | Khách cần thư giãn nhanh |
                """;
        String faqChunk = """
                [FAQ > Thực đơn Buffet thực dưỡng & Điểm tâm ăn nhẹ sau liệu trình]
                Sau khi làm dịch vụ có được ăn nhẹ không?: Có. Tất cả khách hàng sau liệu trình đều được phục vụ đồ ăn nhẹ và trà thảo mộc miễn phí.
                Có phát sinh chi phí hoặc tiền tip bắt buộc không?: Không. Giá dịch vụ đã trọn gói, spa không thu phụ phí và không bắt buộc tiền tip.
                | STT | Món ăn nhẹ / Thực đơn Buffet thực dưỡng sau liệu trình |
                | --- | --- |
                | 1 | Bánh mì bò sốt vang |
                | 2 | Trứng ngải cứu |
                | 3 | Chè đậu đen |
                """;
        return List.of(
                new ReciprocalRankFusion.FusedChunk(
                        new RetrievedChunk(
                                UUID.fromString("c9481efb-0001-4613-a2e0-fe50e9b99b39"),
                                DOC_ID,
                                List.of("MASSAGE", "Cơ sở Hà Nội"),
                                List.of(),
                                1,
                                new double[]{0, 0, 10, 10},
                                massageTable,
                                0.96
                        ),
                        0.96
                ),
                new ReciprocalRankFusion.FusedChunk(
                        new RetrievedChunk(
                                UUID.fromString("c9481efb-0002-4613-a2e0-fe50e9b99b39"),
                                DOC_ID,
                                List.of("FAQ", "Thực đơn ăn nhẹ"),
                                List.of(),
                                2,
                                new double[]{0, 0, 10, 10},
                                faqChunk,
                                0.92
                        ),
                        0.92
                )
        );
    }

    private static PromptBuilder.PagePersona buildTestPersona() {
        PagePersonaConfig cfg = new PagePersonaConfig(
                "BEAUTY_SPA",
                "nhân viên chăm sóc",
                "em",
                "mình",
                "Dịu dàng, ấm áp, tinh tế",
                "NONE",
                List.of("chốt đơn", "cam kết khỏi hẳn"),
                List.of("massage", "cổ vai gáy", "vai gáy", "mỏi", "đau", "nhức", "thảo dược", "dưỡng sinh", "lưng"),
                "spa-llm-native-guarded-v3.2.1-final",
                "BẠN LÀ nhân viên chăm sóc khách hàng.",
                List.of("Dạ chào mình ạ.", "Bên em chuyên massage trị liệu và dưỡng sinh ạ."),
                10000,
                "Mình thích lực nhẹ hay vừa ạ?",
                "Mình bị lâu chưa hay mới gần đây ạ?",
                "Dạ mình gặp tình trạng này mới gần đây hay đã kéo dài ạ?",
                null,
                List.of("đau", "mỏi", "nhức", "căng"),
                List.of("cổ", "vai", "gáy", "cổ vai gáy", "vai gáy", "lưng", "chân"),
                Map.of(),
                Map.of()
        );
        return new PromptBuilder.PagePersona("Nàng Sen Spa", "BEAUTY_SPA", "Khách hàng spa", "Ấm áp", cfg);
    }

    @Test
    @DisplayName("1. ThinkingMode enum defines provider-agnostic token, sentence, and word budgets")
    void thinkingModeEnumDefinesProviderAgnosticBudgets() {
        assertThat(ThinkingMode.FAST_NO_THINK.thinkEnabled()).isFalse();
        assertThat(ThinkingMode.FAST_NO_THINK.maxOutputTokens()).isEqualTo(400);
        assertThat(ThinkingMode.FAST_NO_THINK.maxSentencesPerBubble()).isEqualTo(2);
        assertThat(ThinkingMode.FAST_NO_THINK.maxWordsPerBubble()).isEqualTo(65);

        assertThat(ThinkingMode.PRICE_ANCHOR_THINK.thinkEnabled()).isTrue();
        assertThat(ThinkingMode.PRICE_ANCHOR_THINK.maxOutputTokens()).isEqualTo(500);
        assertThat(ThinkingMode.PRICE_ANCHOR_THINK.maxSentencesPerBubble()).isEqualTo(3);
        assertThat(ThinkingMode.PRICE_ANCHOR_THINK.maxWordsPerBubble()).isEqualTo(65);

        assertThat(ThinkingMode.OBJECTION_REFRAME_THINK.thinkEnabled()).isTrue();
        assertThat(ThinkingMode.OBJECTION_REFRAME_THINK.maxOutputTokens()).isEqualTo(550);
        assertThat(ThinkingMode.OBJECTION_REFRAME_THINK.maxSentencesPerBubble()).isEqualTo(3);
        assertThat(ThinkingMode.OBJECTION_REFRAME_THINK.maxWordsPerBubble()).isEqualTo(65);

        assertThat(ThinkingMode.REPAIR_FIRST.thinkEnabled()).isTrue();
        assertThat(ThinkingMode.REPAIR_FIRST.maxOutputTokens()).isEqualTo(500);
        assertThat(ThinkingMode.REPAIR_FIRST.maxSentencesPerBubble()).isEqualTo(3);
        assertThat(ThinkingMode.REPAIR_FIRST.maxWordsPerBubble()).isEqualTo(65);
    }

    @Test
    @DisplayName("2. ThinkingModePolicy routes all 10 scenarios and A/B/C benchmark modes accurately")
    void thinkingModePolicyRoutesAllScenariosAndBenchmarkModes() {
        List<ReciprocalRankFusion.FusedChunk> chunks = buildTestChunks();
        List<String> keywords = List.of("cổ", "vai", "gáy", "lưng", "chân", "thảo dược", "massage", "dưỡng sinh", "239k", "399k");

        // Scenario 1: Greeting / Broad Price Inquiry -> FAST_NO_THINK
        ConversationState s1 = ConversationState.hydrate(PAGE_ID, "u-s1", List.of(), keywords, chunks);
        PrimaryActionRouter.RoutingDecision d1 = PrimaryActionRouter.route("Giá sao shop", s1, chunks, keywords);
        ThinkingModePolicy.ThinkingDecision td1 = ThinkingModePolicy.decide("Giá sao shop", s1, d1, null);
        assertThat(td1.thinkingMode()).isEqualTo(ThinkingMode.FAST_NO_THINK);
        assertThat(td1.thinkEnabled()).isFalse();

        // Scenario 2: Discovery Clarify ("Đau mỏi cổ vai gáy") -> FAST_NO_THINK
        List<FanpageBotRequest.ConversationTurn> h2 = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Giá sao shop"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ chào mình! Mình đang quan tâm chăm sóc vùng nào ạ?")
        );
        ConversationState s2 = ConversationState.hydrate(PAGE_ID, "u-s2", h2, keywords, chunks);
        PrimaryActionRouter.RoutingDecision d2 = PrimaryActionRouter.route("Mình bị đau mỏi cổ vai gáy", s2, chunks, keywords);
        ThinkingModePolicy.ThinkingDecision td2 = ThinkingModePolicy.decide("Mình bị đau mỏi cổ vai gáy", s2, d2, null);
        assertThat(td2.thinkingMode()).isEqualTo(ThinkingMode.FAST_NO_THINK);

        // Scenario 3: First Price Quote after clarify ("Thi thoảng mới đau thôi") -> PRICE_ANCHOR_THINK
        List<FanpageBotRequest.ConversationTurn> h3 = new ArrayList<>(h2);
        h3.add(new FanpageBotRequest.ConversationTurn("user", "Mình bị đau mỏi cổ vai gáy"));
        h3.add(new FanpageBotRequest.ConversationTurn("assistant", "Dạ tình trạng mỏi cổ vai gáy của mình bị lâu chưa hay mới gần đây ạ?"));
        ConversationState s3 = ConversationState.hydrate(PAGE_ID, "u-s3", h3, keywords, chunks);
        PrimaryActionRouter.RoutingDecision d3 = PrimaryActionRouter.route("Thi thoảng mới bị thôi", s3, chunks, keywords);
        ThinkingModePolicy.ThinkingDecision td3 = ThinkingModePolicy.decide("Thi thoảng mới bị thôi", s3, d3, null);
        assertThat(td3.thinkingMode()).isEqualTo(ThinkingMode.PRICE_ANCHOR_THINK);
        assertThat(td3.thinkEnabled()).isTrue();
        assertThat(td3.maxOutputTokens()).isEqualTo(500);

        // Scenario 4: First Price Objection ("Đắt nhỉ") -> OBJECTION_REFRAME_THINK
        List<FanpageBotRequest.ConversationTurn> h4 = new ArrayList<>(h3);
        h4.add(new FanpageBotRequest.ConversationTurn("user", "Thi thoảng mới bị thôi"));
        h4.add(new FanpageBotRequest.ConversationTurn("assistant", "Dạ với mỏi cổ vai gáy, mình tham khảo gói Massage cơ bản 60 phút giá 399k (đã bao gồm tắm xông, ngâm chân thảo dược, ăn nhẹ và cam kết không nhận Tip) ạ."));
        ConversationState s4 = ConversationState.hydrate(PAGE_ID, "u-s4", h4, keywords, chunks);
        PrimaryActionRouter.RoutingDecision d4 = PrimaryActionRouter.route("Đắt nhỉ", s4, chunks, keywords);
        ThinkingModePolicy.ThinkingDecision td4 = ThinkingModePolicy.decide("Đắt nhỉ", s4, d4, null);
        assertThat(td4.thinkingMode()).isEqualTo(ThinkingMode.OBJECTION_REFRAME_THINK);
        assertThat(td4.objectionRootCause()).isEqualTo(ThinkingModePolicy.ObjectionRootCause.GENERAL_PRICE_HESITATION);
        assertThat(td4.previouslyUsedArguments()).contains("full_service_inclusion", "no_tip_policy");

        // Scenario 5: Second Price Objection / Budget Constraint ("Vẫn hơi cao, có gói nào mềm hơn không") -> OBJECTION_REFRAME_THINK (BUDGET_CONSTRAINT)
        List<FanpageBotRequest.ConversationTurn> h5 = new ArrayList<>(h4);
        h5.add(new FanpageBotRequest.ConversationTurn("user", "Đắt nhỉ"));
        h5.add(new FanpageBotRequest.ConversationTurn("assistant", "Dạ mức 399k là trọn gói 60 phút chăm sóc chuyên sâu, mình có thể trải nghiệm thử 1 buổi xem hợp tay nghề kỹ thuật viên không ạ 🥰"));
        ConversationState s5 = ConversationState.hydrate(PAGE_ID, "u-s5", h5, keywords, chunks);
        PrimaryActionRouter.RoutingDecision d5 = PrimaryActionRouter.route("Vẫn hơi cao, sinh viên chưa đủ ngân sách, có gói nào rẻ hơn không", s5, chunks, keywords);
        ThinkingModePolicy.ThinkingDecision td5 = ThinkingModePolicy.decide("Vẫn hơi cao, sinh viên chưa đủ ngân sách, có gói nào rẻ hơn không", s5, d5, null);
        assertThat(td5.thinkingMode()).isEqualTo(ThinkingMode.OBJECTION_REFRAME_THINK);
        assertThat(td5.objectionRootCause()).isEqualTo(ThinkingModePolicy.ObjectionRootCause.BUDGET_CONSTRAINT);
        assertThat(td5.previouslyUsedArguments()).contains("single_session_trial");

        // Scenario 6: Competitor / External Comparison ("Chỗ khác có 200k thôi") -> OBJECTION_REFRAME_THINK (EXTERNAL_COMPARISON)
        ThinkingModePolicy.ThinkingDecision td6 = ThinkingModePolicy.decide("Bên tiệm gần nhà chị có 200k thôi sao bên em cao thế", s4, d4, null);
        assertThat(td6.thinkingMode()).isEqualTo(ThinkingMode.OBJECTION_REFRAME_THINK);
        assertThat(td6.objectionRootCause()).isEqualTo(ThinkingModePolicy.ObjectionRootCause.EXTERNAL_COMPARISON);

        // Scenario 7: Value / Efficacy Doubt ("Làm 1 buổi có đỡ thật không hay chỉ quảng cáo") -> OBJECTION_REFRAME_THINK (VALUE_DOUBT)
        ThinkingModePolicy.ThinkingDecision td7 = ThinkingModePolicy.decide("Làm 1 buổi có đỡ thật không hay chỉ đỡ lúc đó thôi", s4, d4, null);
        assertThat(td7.thinkingMode()).isEqualTo(ThinkingMode.OBJECTION_REFRAME_THINK);
        assertThat(td7.objectionRootCause()).isEqualTo(ThinkingModePolicy.ObjectionRootCause.VALUE_DOUBT);

        // Scenario 8: Alternative Package Inquiry ("Có gói khác không") -> PRICE_ANCHOR_THINK
        List<FanpageBotRequest.ConversationTurn> h8 = new ArrayList<>(h5);
        h8.add(new FanpageBotRequest.ConversationTurn("user", "Mà hơi đắt"));
        h8.add(new FanpageBotRequest.ConversationTurn("assistant", "Dạ vậy mình tham khảo Gói dưỡng sinh cơ bản 60 phút giá 239k ạ."));
        ConversationState s8 = ConversationState.hydrate(PAGE_ID, "u-s8", h8, keywords, chunks);
        PrimaryActionRouter.RoutingDecision d8 = PrimaryActionRouter.route("Có gói khác k", s8, chunks, keywords);
        ThinkingModePolicy.ThinkingDecision td8 = ThinkingModePolicy.decide("Có gói khác k", s8, d8, null);
        assertThat(td8.thinkingMode()).isEqualTo(ThinkingMode.PRICE_ANCHOR_THINK);

        // Scenario 9: Customer Frustration / Comprehension Repair ("Sao hỏi ăn nhẹ gồm món gì mà cứ nói vòng vo thế") -> REPAIR_FIRST
        PrimaryActionRouter.RoutingDecision d9 = PrimaryActionRouter.route("Sao hỏi ăn nhẹ gồm món gì mà cứ nói vòng vo thế, dở à", s8, chunks, keywords);
        CustomerInsightDto frustratedInsight = new CustomerInsightDto(
                "SKEPTICAL", "Khách hoài nghi", 35, 50, -15, "DECAYING", "Lạnh",
                "Frustrated customer", "mình", List.of(), List.of("frustrated"), "Apologize and answer directly"
        );
        ThinkingModePolicy.ThinkingDecision td9 = ThinkingModePolicy.decide(
                "Sao hỏi ăn nhẹ gồm món gì mà cứ nói vòng vo thế, dở à", s8, d9, frustratedInsight);
        assertThat(td9.thinkingMode()).isEqualTo(ThinkingMode.REPAIR_FIRST);
        assertThat(td9.thinkEnabled()).isTrue();
        assertThat(td9.maxOutputTokens()).isEqualTo(500);

        // Clarification contrast detail question ("K tôi thấy có buffet thực dưỡng, nó là j") -> REPAIR_FIRST
        PrimaryActionRouter.RoutingDecision dClarify = PrimaryActionRouter.route("K tôi thấy có buffet thực dưỡng, nó là j", s4, chunks, keywords);
        ThinkingModePolicy.ThinkingDecision tdClarify = ThinkingModePolicy.decide("K tôi thấy có buffet thực dưỡng, nó là j", s4, dClarify, null);
        assertThat(tdClarify.thinkingMode()).isEqualTo(ThinkingMode.REPAIR_FIRST);

        // Explicit Opt-Out ("Đừng nhắn nữa") and SAFE_REFUSAL_OR_HANDOFF_IF_REPEATED -> FAST_NO_THINK
        PrimaryActionRouter.RoutingDecision dOptOut = PrimaryActionRouter.route("Đừng nhắn nữa, phiền quá", s4, chunks, keywords);
        ThinkingModePolicy.ThinkingDecision tdOptOut = ThinkingModePolicy.decide("Đừng nhắn nữa, phiền quá", s4, dOptOut, null);
        assertThat(tdOptOut.thinkingMode()).isEqualTo(ThinkingMode.FAST_NO_THINK);
        assertThat(tdOptOut.thinkEnabled()).isFalse();

        // Scenario 10: Service Detail FAQ ("Ăn nhẹ có những món gì em") -> FAST_NO_THINK + Benchmark A/B/C override verification
        PrimaryActionRouter.RoutingDecision d10 = PrimaryActionRouter.route("Ăn nhẹ có những món gì em", s4, chunks, keywords);
        ThinkingModePolicy.ThinkingDecision td10Adaptive = ThinkingModePolicy.decide(
                "Ăn nhẹ có những món gì em", s4, d10, null, ThinkingModePolicy.BenchmarkConfigMode.ADAPTIVE_C);
        assertThat(td10Adaptive.thinkingMode()).isEqualTo(ThinkingMode.FAST_NO_THINK);

        ThinkingModePolicy.ThinkingDecision td4ConfigA = ThinkingModePolicy.decide(
                "Đắt nhỉ", s4, d4, null, ThinkingModePolicy.BenchmarkConfigMode.FORCE_NO_THINK_A);
        assertThat(td4ConfigA.thinkingMode()).isEqualTo(ThinkingMode.FAST_NO_THINK);
        assertThat(td4ConfigA.thinkEnabled()).isFalse();

        ThinkingModePolicy.ThinkingDecision td1ConfigB = ThinkingModePolicy.decide(
                "Giá sao shop", s1, d1, null, ThinkingModePolicy.BenchmarkConfigMode.FORCE_ALWAYS_THINK_B);
        assertThat(td1ConfigB.thinkingMode()).isNotEqualTo(ThinkingMode.FAST_NO_THINK);
        assertThat(td1ConfigB.thinkEnabled()).isTrue();
    }

    @Test
    @DisplayName("3. Dynamic Post-Price Delay subtracts elapsed generation time and cancels stale Bubble 2 on concurrent turn")
    void dynamicPostPriceDelaySubtractsElapsedMsAndCancelsStaleBubble2() {
        long t0 = 1_000_000_000L;
        // Case A: 3,200ms elapsed since Bubble 1 -> remaining delay = 10,000 - 3,200 = 6,800ms
        long tAfter3200Ms = t0 + 3_200L * 1_000_000L;
        ThinkingModePolicy.PostPriceDelayMetrics m1 = ThinkingModePolicy.computeDynamicPostPriceDelay(10_000, t0, tAfter3200Ms);
        assertThat(m1.configuredDelayMs()).isEqualTo(10_000);
        assertThat(m1.elapsedSincePriceBubbleMs()).isEqualTo(3_200L);
        assertThat(m1.remainingDelayMs()).isEqualTo(6_800);

        // Case B: 11,500ms elapsed since Bubble 1 -> remaining delay = max(0, 10,000 - 11,500) = 0ms
        long tAfter11500Ms = t0 + 11_500L * 1_000_000L;
        ThinkingModePolicy.PostPriceDelayMetrics m2 = ThinkingModePolicy.computeDynamicPostPriceDelay(10_000, t0, tAfter11500Ms);
        assertThat(m2.elapsedSincePriceBubbleMs()).isEqualTo(11_500L);
        assertThat(m2.remainingDelayMs()).isEqualTo(0);

        // Verify ReplyContractValidator 8-arg overload applies dynamic remaining delay and 3-sentence/65-word budget
        List<FanpageBotResponse.ReplyBubble> priceAndFollowup = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Dạ với tình trạng căng cứng vùng cổ vai gáy khi ngồi máy tính lâu, mình tham khảo gói Massage cơ bản 60 phút giá 399k ạ. Liệu trình tập trung ấn huyệt làm mềm cơ sâu và giải tỏa chèn ép vùng vai gáy. Giá đã trọn gói ngâm chân thảo dược, ăn nhẹ và không nhận Tip ạ.",
                        0
                ),
                new FanpageBotResponse.ReplyBubble(
                        "Sau 60 phút trị liệu, vùng cổ vai sẽ nhẹ hẳn và dễ ngủ sâu giấc hơn vào buổi tối. Mình muốn đặt lịch ghé trải nghiệm thử 1 buổi vào chiều nay hay tối mai để bên em giữ phòng riêng yên tĩnh cho mình ạ?",
                        10_000
                )
        );
        ReplyContractValidator.ValidationResult valRes = ReplyContractValidator.validateAndEnforce(
                priceAndFollowup,
                null,
                "Thi thoảng mới đau thôi",
                PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE,
                false,
                10_000,
                ThinkingMode.PRICE_ANCHOR_THINK,
                3_200L
        );
        assertThat(valRes.validatedBubbles()).hasSize(2);
        assertThat(valRes.validatedBubbles().get(1).typingDelayMs()).isEqualTo(6_800);
        // 3 sentences in Bubble 1 must NOT be truncated in PRICE_ANCHOR_THINK mode
        assertThat(valRes.validatedBubbles().get(0).text()).contains("không nhận Tip ạ.");

        // Case C: Concurrent customer turn arrives before Bubble 2 is dispatched -> stale Bubble 2 is cancelled
        String senderId = "sender-concurrent-test";
        long seq1 = ThinkingModePolicy.registerIncomingCustomerTurn(PAGE_ID, senderId);
        ThinkingModePolicy.markPriceBubble1ShownAndBubble2Pending(PAGE_ID, senderId, seq1, t0);
        assertThat(ThinkingModePolicy.isPendingSecondBubbleSuperseded(PAGE_ID, senderId, seq1)).isFalse();

        // Customer sends a new message while Bubble 2 of seq1 is still pending
        long seq2 = ThinkingModePolicy.registerIncomingCustomerTurn(PAGE_ID, senderId);
        assertThat(seq2).isGreaterThan(seq1);
        assertThat(ThinkingModePolicy.isPendingSecondBubbleSuperseded(PAGE_ID, senderId, seq1)).isTrue();

        List<FanpageBotResponse.ReplyBubble> alignedAfterCancellation = FanpageBotService.alignBubbleDelaysWithContract(
                valRes.validatedBubbles(),
                valRes.validatedBubbles(),
                m1,
                true
        );
        assertThat(alignedAfterCancellation).hasSize(1);
        assertThat(alignedAfterCancellation.get(0).text()).contains("399k");
    }

    @Test
    @DisplayName("4. Adapter isolation: OllamaLlmClient uses /api/chat think+num_predict while OpenAiCompatibleLlmClient never sends think and strips reasoning_brief")
    void adapterIsolationBetweenOllamaAndOpenAiCompatibleClients() throws Exception {
        AtomicReference<String> capturedOllamaPath = new AtomicReference<>();
        AtomicReference<String> capturedOllamaBody = new AtomicReference<>();

        HttpServer ollamaServer = HttpServer.create(new InetSocketAddress(0), 0);
        ollamaServer.createContext("/api/chat", exchange -> {
            capturedOllamaPath.set(exchange.getRequestURI().getPath());
            capturedOllamaBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String responseJson = """
                    {
                      "model": "qwen3:14b",
                      "message": {
                        "role": "assistant",
                        "thinking": "Khách đang chê đắt lần 1, cần đồng cảm và neo giá trị thực tế, không lặp lại lý lẽ cũ.",
                        "content": "{\\"reply_bubbles\\":[{\\"text\\":\\"Dạ em rất hiểu tâm lý muốn cân nhắc kỹ trước khi chọn gói chăm sóc sức khỏe ạ.\\",\\"typing_delay_ms\\":0}]}"
                      },
                      "done": true
                    }
                    """;
            byte[] bytes = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        ollamaServer.start();

        try {
            int port = ollamaServer.getAddress().getPort();
            OllamaProperties ollamaProps = new OllamaProperties();
            ollamaProps.setBaseUrl("http://localhost:" + port);
            ollamaProps.setModel("qwen3:14b");
            ollamaProps.setReadTimeoutMs(5000);
            OllamaLlmClient ollamaClient = new OllamaLlmClient(ollamaProps);

            ReasoningBudget objectionBudget = ReasoningBudget.forThinkingMode(ThinkingMode.OBJECTION_REFRAME_THINK);
            String ollamaReply = ollamaClient.complete("Prompt test", objectionBudget);

            assertThat(capturedOllamaPath.get()).isEqualTo("/api/chat");
            assertThat(capturedOllamaBody.get())
                    .contains("\"think\":true")
                    .contains("\"num_predict\":550");
            // Internal thinking trace must NEVER leak into returned content
            assertThat(ollamaReply)
                    .doesNotContain("Khách đang chê đắt lần 1")
                    .contains("Dạ em rất hiểu tâm lý");
            assertThat(ollamaClient.getLastCallTelemetry()).isNotNull();
            assertThat(ollamaClient.getLastCallTelemetry().thinkingChars()).isGreaterThan(20);
            assertThat(ollamaClient.getLastCallTelemetry().thinkRequested()).isTrue();
            assertThat(ollamaClient.getLastCallTelemetry().thinkingLatencyMs()).isGreaterThanOrEqualTo(1L);

            // Stop server and verify a subsequent failed/timeout call resets telemetry rather than retaining Turn N-1 metrics
            ollamaServer.stop(0);
            String offlineReply = ollamaClient.complete("Prompt offline", ReasoningBudget.forThinkingMode(ThinkingMode.FAST_NO_THINK));
            assertThat(offlineReply).startsWith("[ollama-offline]");
            assertThat(ollamaClient.getLastCallTelemetry().thinkingChars()).isZero();
            assertThat(ollamaClient.getLastCallTelemetry().thinkingLatencyMs()).isZero();
            assertThat(ollamaClient.getLastCallTelemetry().thinkRequested()).isFalse();
        } finally {
            ollamaServer.stop(0);
        }

        // OpenAI-compatible client test
        AtomicReference<String> capturedOpenAiBody = new AtomicReference<>();
        HttpServer openAiServer = HttpServer.create(new InetSocketAddress(0), 0);
        openAiServer.createContext("/v1/chat/completions", exchange -> {
            capturedOpenAiBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String responseJson = """
                    {
                      "choices": [
                        {
                          "message": {
                            "role": "assistant",
                            "content": "{\\"reasoning_brief\\":\\"Neo cảm giác nhẹ vai gáy trước khi báo giá\\",\\"reply_bubbles\\":[{\\"text\\":\\"Dạ gói 60 phút giúp vùng vai gáy nhẹ hẳn ngay sau buổi đầu ạ.\\",\\"typing_delay_ms\\":0}],\\"should_handoff\\":false}"
                          }
                        }
                      ]
                    }
                    """;
            byte[] bytes = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        openAiServer.start();

        try {
            int port = openAiServer.getAddress().getPort();
            LlmProperties llmProps = new LlmProperties();
            llmProps.setProvider("custom");
            llmProps.setBaseUrl("http://localhost:" + port + "/v1");
            llmProps.setModel("gpt-4o-mini");
            llmProps.setApiKey("test-key");
            llmProps.setTimeoutSeconds(5);
            OpenAiCompatibleLlmClient openAiClient = new OpenAiCompatibleLlmClient(llmProps, new tools.jackson.databind.ObjectMapper());

            ReasoningBudget priceBudget = ReasoningBudget.forThinkingMode(ThinkingMode.PRICE_ANCHOR_THINK);
            String openAiReply = openAiClient.complete("Prompt test", priceBudget);

            // OpenAI-compatible payload must NEVER contain Ollama-specific "think" or "num_predict"
            assertThat(capturedOpenAiBody.get())
                    .doesNotContain("\"think\"")
                    .doesNotContain("\"num_predict\"")
                    .contains("\"max_tokens\":500");
            // reasoning_brief must be stripped from returned JSON and recorded only in telemetry
            assertThat(openAiReply)
                    .doesNotContain("reasoning_brief")
                    .doesNotContain("Neo cảm giác nhẹ vai gáy")
                    .contains("reply_bubbles");
            assertThat(openAiClient.getLastCallTelemetry()).isNotNull();
            assertThat(openAiClient.getLastCallTelemetry().thinkingChars()).isGreaterThan(10);
        } finally {
            openAiServer.stop(0);
        }
    }

    @Test
    @DisplayName("5. PromptBuilder injects Emotional Value Anchoring, Objection Reframing without repeating arguments, and Repair First instructions")
    void promptBuilderInjectsThinkingModeStrategies() {
        List<ReciprocalRankFusion.FusedChunk> chunks = buildTestChunks();
        PromptBuilder.PagePersona persona = buildTestPersona();
        ConversationState state = ConversationState.hydrate(PAGE_ID, "u-prompt", List.of(), List.of("vai gáy"), chunks);
        PrimaryActionRouter.RoutingDecision decision = PrimaryActionRouter.route("Đắt nhỉ", state, chunks, List.of("vai gáy"));

        ThinkingModePolicy.ThinkingDecision objectionDecision = new ThinkingModePolicy.ThinkingDecision(
                ThinkingMode.OBJECTION_REFRAME_THINK,
                "FIRST_TIME_PRICE_OBJECTION_REFRAME",
                ThinkingModePolicy.ObjectionRootCause.EXTERNAL_COMPARISON,
                List.of("full_service_inclusion", "no_tip_policy")
        );

        String ollamaPrompt = FanpageBotPromptBuilder.buildDynamicRuntimePrompt(
                "Sao bên mình cao hơn tiệm gần nhà chị thế",
                "Chị Lan",
                List.of(),
                chunks,
                List.of(),
                persona,
                FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION,
                "chị",
                null,
                List.<AgentSkillDto>of(),
                "",
                null,
                List.of(),
                state,
                decision,
                List.of(),
                objectionDecision,
                false
        );

        assertThat(ollamaPrompt)
                .contains("<THINKING_MODE_STRATEGY>")
                .contains("thinking_mode: OBJECTION_REFRAME_THINK")
                .contains("objection_root_cause: EXTERNAL_COMPARISON")
                .contains("full_service_inclusion")
                .contains("no_tip_policy")
                .doesNotContain("reasoning_brief");

        String openAiPrompt = FanpageBotPromptBuilder.buildDynamicRuntimePrompt(
                "Sao bên mình cao hơn tiệm gần nhà chị thế",
                "Chị Lan",
                List.of(),
                chunks,
                List.of(),
                persona,
                FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION,
                "chị",
                null,
                List.<AgentSkillDto>of(),
                "",
                null,
                List.of(),
                state,
                decision,
                List.of(),
                objectionDecision,
                true
        );

        assertThat(openAiPrompt).contains("reasoning_brief");
    }

    @Test
    @DisplayName("6. V3.2.4 STAGE_3_GRACEFUL_EXIT routes to FAST_NO_THINK, TurnTelemetry tracks full latency breakdown, and split-bubble response contract works")
    void v324Stage3GracefulExitTelemetryAndSplitBubbleContract() {
        List<ReciprocalRankFusion.FusedChunk> chunks = buildTestChunks();
        List<String> keywords = List.of("massage", "vai gáy", "dưỡng sinh");
        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Giá gói massage 60 phút"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ gói Massage cơ bản 60 phút bên em có giá 399k ạ."),
                new FanpageBotRequest.ConversationTurn("user", "Đắt nhỉ"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ mình trải nghiệm thử 1 buổi nhé 🥰"),
                new FanpageBotRequest.ConversationTurn("user", "Để tôi cân nhắc thêm"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ em hoàn toàn tôn trọng quyết định của mình ạ. Mình tham khảo gói dưỡng sinh cơ bản 60 phút giá 239k nhé ạ.")
        );
        ConversationState state = ConversationState.hydrate(PAGE_ID, "u-stage3", history, keywords, chunks);
        assertThat(state.downsellOffered()).isTrue();
        assertThat(state.retentionAttemptCount()).isEqualTo(1);

        PrimaryActionRouter.RoutingDecision stage3Dec = PrimaryActionRouter.route("Thôi để lúc khác vậy em", state, chunks, keywords);
        assertThat(stage3Dec.detectedIntent()).isEqualTo("STAGE_3_GRACEFUL_EXIT");

        ThinkingModePolicy.ThinkingDecision stage3Think = ThinkingModePolicy.decide("Thôi để lúc khác vậy em", state, stage3Dec, null);
        assertThat(stage3Think.thinkingMode()).isEqualTo(ThinkingMode.FAST_NO_THINK);
        assertThat(stage3Think.thinkEnabled()).isFalse();

        ThinkingModePolicy.TurnTelemetry telemetry = new ThinkingModePolicy.TurnTelemetry(
                PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE,
                ThinkingMode.PRICE_ANCHOR_THINK,
                "EXPLICIT_PRICE_INQUIRY",
                List.of(),
                List.of("doc-1"),
                true,
                "NONE",
                3850L,
                1000L,
                11000L,
                36L,
                6150L,
                "EXPLICIT_PRICE_ANCHOR",
                true,
                500,
                320,
                3850L,
                3850L,
                10000,
                3850L,
                6150,
                false,
                14L,
                22L,
                8L,
                1,
                10000L
        );
        assertThat(telemetry.promptBuildMs()).isEqualTo(14L);
        assertThat(telemetry.retrievalMs()).isEqualTo(22L);
        assertThat(telemetry.llmElapsedMs()).isEqualTo(3850L);
        assertThat(telemetry.validationMs()).isEqualTo(8L);
        assertThat(telemetry.retryCount()).isEqualTo(1);
        assertThat(telemetry.firstBubbleSendMs()).isEqualTo(36L);
        assertThat(telemetry.interBubbleGapMs()).isEqualTo(6150L);
        assertThat(telemetry.totalTurnMs()).isEqualTo(10000L);
    }

    @Test
    @DisplayName("7. End-to-End FanpageBotService splitPriceBubbles + GENERATE_PRICE_ANCHOR_BUBBLE + 3-Stage Retention + Concurrent Cancellation + SAFE-NEURO-001")
    void v324EndToEndSplitBubblePriceQuoteEmotionalAnchorRetentionAndMidSaleSafetyFlow() {
        List<ReciprocalRankFusion.FusedChunk> chunks = buildTestChunks();
        PromptBuilder.PagePersona persona = buildTestPersona();

        com.example.docmind.service.retrieval.RetrievalService retrievalService =
                org.mockito.Mockito.mock(com.example.docmind.service.retrieval.RetrievalService.class);
        org.mockito.Mockito.when(retrievalService.retrieveWithRules(
                org.mockito.Mockito.anyList(),
                org.mockito.Mockito.anyString(),
                org.mockito.Mockito.anyInt(),
                org.mockito.Mockito.anyList(),
                org.mockito.Mockito.any()
        )).thenReturn(new com.example.docmind.service.retrieval.RetrievalService.RetrievalResult(chunks, List.of()));

        com.example.docmind.repository.KnowledgePageRepository pageRepo =
                org.mockito.Mockito.mock(com.example.docmind.repository.KnowledgePageRepository.class);
        com.example.docmind.entity.KnowledgePageEntity pageEntity = new com.example.docmind.entity.KnowledgePageEntity();
        pageEntity.setId(PAGE_ID);
        pageEntity.setName(persona.name());
        pageEntity.setIndustry(persona.industry());
        pageEntity.setTargetAudience(persona.targetAudience());
        pageEntity.setDescription(persona.description());
        pageEntity.setPersonaConfig(persona.resolvedConfig());
        org.mockito.Mockito.when(pageRepo.findById(PAGE_ID)).thenReturn(java.util.Optional.of(pageEntity));

        com.example.docmind.service.llm.LlmClient llmClient =
                org.mockito.Mockito.mock(com.example.docmind.service.llm.LlmClient.class);
        org.mockito.Mockito.when(llmClient.name()).thenReturn("qwen3:14b");
        // Simulate LLM returning valid JSON for each turn; for standalone Bubble 2, return a 1-bubble emotional anchor without repeating price
        org.mockito.Mockito.when(llmClient.complete(org.mockito.Mockito.anyString(), org.mockito.Mockito.any(ReasoningBudget.class)))
                .thenAnswer(inv -> {
                    String prompt = inv.getArgument(0, String.class);
                    if (prompt.contains("Bong bóng 1 báo giá đã được gửi cho khách")) {
                        return "{\"reply_bubbles\":[{\"text\":\"Dạ sau 60 phút ấn huyệt giải cơ sâu kết hợp dùng buffet thực dưỡng miễn phí, vùng cổ vai gáy của mình sẽ nhẹ hẳn và dễ ngủ sâu giấc hơn ạ. Mình đang thiên về thư giãn nhẹ nhàng hay ấn huyệt sâu vùng cổ vai gáy ạ?\",\"typing_delay_ms\":10000}],\"should_handoff\":false}";
                    }
                    return "[ollama-offline]";
                });

        FanpageBotService botService = new FanpageBotService(
                retrievalService,
                llmClient,
                new com.example.docmind.config.ChatProperties(),
                null,
                pageRepo,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        String senderId = "u-e2e-split-v324";
        List<FanpageBotRequest.ConversationTurn> history = new ArrayList<>();

        // Part A: Non-price RECOMMEND_ONE_PACKAGE turn with splitPriceBubbles=true MUST NOT set pendingSecondBubble=true
        List<FanpageBotRequest.ConversationTurn> symHistory = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Mình bị mỏi cổ vai gáy"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ mình bị mỏi cổ vai gáy lâu chưa ạ?")
        );
        FanpageBotRequest nonPriceRecReq = new FanpageBotRequest(
                PAGE_ID.toString(), senderId + "-nonprice", "Chị Lan",
                "Mình bị mỏi 2 tuần rồi do ngồi máy tính nhiều",
                null, symHistory, List.of(DOC_ID), null, null, null, 5, null, true, null, null
        );
        FanpageBotResponse nonPriceRecResp = botService.handleCustomerMessage(nonPriceRecReq);
        assertThat(nonPriceRecResp.pendingSecondBubble()).isFalse();

        // Part B: Split-bubble Price Quote (Turn 1: Early broad price -> Turn 2: Explicit price quote with splitPriceBubbles=true)
        FanpageBotRequest turn1Req = new FanpageBotRequest(
                PAGE_ID.toString(), senderId, "Chị Lan",
                "Giá sao shop?",
                null, history, List.of(DOC_ID), null, null, null, 5, null, true, null, null
        );
        FanpageBotResponse turn1Resp = botService.handleCustomerMessage(turn1Req);
        assertThat(turn1Resp.pendingSecondBubble()).isFalse();
        assertThat(botService.getLastTurnTelemetry().thinkingMode()).isEqualTo(ThinkingMode.FAST_NO_THINK);
        history.add(new FanpageBotRequest.ConversationTurn("user", "Giá sao shop?"));
        history.add(new FanpageBotRequest.ConversationTurn("assistant", turn1Resp.replyText()));

        // Turn 2 Request 1: Customer answers clarifying question -> Bubble 1 price quote returned immediately with pendingSecondBubble=true
        String turn2Msg = "Mình bị đau mỏi vai gáy 2 tuần rồi, muốn làm gói 60 phút";
        List<FanpageBotRequest.ConversationTurn> historyBeforeTurn2 = new ArrayList<>(history);
        FanpageBotRequest turn2Bubble1Req = new FanpageBotRequest(
                PAGE_ID.toString(), senderId, "Chị Lan",
                turn2Msg,
                null, historyBeforeTurn2, List.of(DOC_ID), null, null, null, 5, null, true, null, null
        );
        FanpageBotResponse turn2Bubble1Resp = botService.handleCustomerMessage(turn2Bubble1Req);
        assertThat(turn2Bubble1Resp.pendingSecondBubble()).isTrue();
        assertThat(turn2Bubble1Resp.pendingTurnSequence()).isNotNull().isGreaterThan(0L);
        assertThat(turn2Bubble1Resp.priceBubbleSentAtNs()).isNotNull().isGreaterThan(0L);
        assertThat(turn2Bubble1Resp.replyBubbles()).hasSize(1);
        assertThat(turn2Bubble1Resp.replyBubbles().get(0).text()).contains("399k").contains("60 phút");
        assertThat(botService.getLastTurnTelemetry().thinkingMode()).isEqualTo(ThinkingMode.FAST_NO_THINK);
        assertThat(botService.getLastTurnTelemetry().thinkEnabled()).isFalse();

        // Turn 2 Request 2: Frontend requests GENERATE_PRICE_ANCHOR_BUBBLE -> returns Bubble 2 with PRICE_ANCHOR_THINK, dynamic delay, 0 price repeat, and preserves 399k in ConversationState
        long simulatedSentAtNs = System.nanoTime() - 2_500L * 1_000_000L; // 2.5s elapsed
        FanpageBotRequest turn2Bubble2Req = new FanpageBotRequest(
                PAGE_ID.toString(), senderId, "Chị Lan",
                turn2Msg,
                null, historyBeforeTurn2, List.of(DOC_ID), null, null, null, 5,
                "GENERATE_PRICE_ANCHOR_BUBBLE", false,
                turn2Bubble1Resp.pendingTurnSequence(), simulatedSentAtNs
        );
        FanpageBotResponse turn2Bubble2Resp = botService.handleCustomerMessage(turn2Bubble2Req);
        assertThat(turn2Bubble2Resp.pendingSecondBubble()).isFalse();
        assertThat(turn2Bubble2Resp.replyBubbles()).hasSize(1);
        String anchorText = turn2Bubble2Resp.replyBubbles().get(0).text();
        int anchorDelay = turn2Bubble2Resp.replyBubbles().get(0).typingDelayMs();
        assertThat(anchorText)
                .doesNotContain("399k")
                .doesNotContain("399.000")
                .doesNotContainIgnoringCase("hôm nay hay ngày mai")
                .doesNotContainIgnoringCase("giữ lịch");
        assertThat(anchorDelay).isBetween(1, 8_000);
        assertThat(botService.getLastTurnTelemetry().thinkingMode()).isEqualTo(ThinkingMode.PRICE_ANCHOR_THINK);
        assertThat(botService.getLastTurnTelemetry().thinkEnabled()).isTrue();
        assertThat(botService.getLastTurnTelemetry().remainingDelayMs()).isEqualTo(anchorDelay);

        history.add(new FanpageBotRequest.ConversationTurn("user", turn2Msg));
        history.add(new FanpageBotRequest.ConversationTurn("assistant",
                turn2Bubble1Resp.replyText() + "\n\n" + anchorText));

        // Turn 3 (Stage 1 / Nhịp 1): "399k hơi cao nhỉ" -> must recognize 399k was already quoted (not wiped by Bubble 2!), route to HANDLE_PRICE_OBJECTION_FIRST_TIME, hold 399k, invite 1-session trial with 🥰
        String turn3Msg = "399k hơi cao nhỉ";
        FanpageBotRequest turn3Req = new FanpageBotRequest(
                PAGE_ID.toString(), senderId, "Chị Lan",
                turn3Msg,
                null, new ArrayList<>(history), List.of(DOC_ID), null, null, null, 5, null, true, null, null
        );
        FanpageBotResponse turn3Resp = botService.handleCustomerMessage(turn3Req);
        assertThat(turn3Resp.pendingSecondBubble()).isFalse();
        assertThat(botService.getLastTurnTelemetry().primaryAction())
                .isEqualTo(PrimaryActionRouter.PrimaryAction.HANDLE_PRICE_OBJECTION_FIRST_TIME);
        assertThat(botService.getLastTurnTelemetry().thinkingMode())
                .isEqualTo(ThinkingMode.OBJECTION_REFRAME_THINK);
        assertThat(turn3Resp.replyText())
                .contains("🥰")
                .doesNotContain("239k");
        history.add(new FanpageBotRequest.ConversationTurn("user", turn3Msg));
        history.add(new FanpageBotRequest.ConversationTurn("assistant", turn3Resp.replyText()));

        // Turn 4 (Stage 2 / Nhịp 2): "Để chị cân nhắc thêm" -> HANDLE_PRICE_OBJECTION_REPEAT, 2 bubbles (respect + clarify question in Bubble 1; 239k Dưỡng sinh cơ bản 60 phút in Bubble 2)
        String turn4Msg = "Để chị cân nhắc thêm";
        FanpageBotRequest turn4Req = new FanpageBotRequest(
                PAGE_ID.toString(), senderId, "Chị Lan",
                turn4Msg,
                null, new ArrayList<>(history), List.of(DOC_ID), null, null, null, 5, null, true, null, null
        );
        FanpageBotResponse turn4Resp = botService.handleCustomerMessage(turn4Req);
        assertThat(botService.getLastTurnTelemetry().primaryAction())
                .isEqualTo(PrimaryActionRouter.PrimaryAction.HANDLE_PRICE_OBJECTION_REPEAT);
        assertThat(turn4Resp.replyBubbles()).hasSize(2);
        assertThat(turn4Resp.replyBubbles().get(0).text()).contains("?");
        assertThat(turn4Resp.replyBubbles().get(1).text().toLowerCase( java.util.Locale.ROOT))
                .contains("239k")
                .contains("dưỡng sinh cơ bản")
                .contains("60 phút");
        history.add(new FanpageBotRequest.ConversationTurn("user", turn4Msg));
        history.add(new FanpageBotRequest.ConversationTurn("assistant", turn4Resp.replyText()));

        // Turn 5 (Stage 3 / Nhịp 3): "Thôi để lúc khác vậy em" -> STAGE_3_GRACEFUL_EXIT, FAST_NO_THINK, 0 questions, 0 package pitch
        String turn5Msg = "Thôi để lúc khác vậy em";
        FanpageBotRequest turn5Req = new FanpageBotRequest(
                PAGE_ID.toString(), senderId, "Chị Lan",
                turn5Msg,
                null, new ArrayList<>(history), List.of(DOC_ID), null, null, null, 5, null, true, null, null
        );
        FanpageBotResponse turn5Resp = botService.handleCustomerMessage(turn5Req);
        assertThat(botService.getLastTurnTelemetry().detectedIntent()).isEqualTo("STAGE_3_GRACEFUL_EXIT");
        assertThat(botService.getLastTurnTelemetry().thinkingMode()).isEqualTo(ThinkingMode.FAST_NO_THINK);
        assertThat(turn5Resp.replyText())
                .doesNotContain("?")
                .doesNotContain("239k")
                .doesNotContain("399k");

        // Part C: Concurrent customer message arriving while Bubble 2 is pending cancels GENERATE_PRICE_ANCHOR_BUBBLE
        String cancelSender = "u-e2e-cancel-v324";
        List<FanpageBotRequest.ConversationTurn> cancelHist = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Giá massage vai gáy bao nhiêu?"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ mình bị mỏi vai gáy lâu chưa ạ?")
        );
        FanpageBotResponse b1CancelResp = botService.handleCustomerMessage(new FanpageBotRequest(
                PAGE_ID.toString(), cancelSender, "Anh Nam",
                "Anh bị mỏi 1 tuần, báo giá gói 60 phút",
                null, cancelHist, List.of(DOC_ID), null, null, null, 5, null, true, null, null
        ));
        assertThat(b1CancelResp.pendingSecondBubble()).isTrue();
        // Customer sends a new message BEFORE Bubble 2 is generated
        botService.handleCustomerMessage(new FanpageBotRequest(
                PAGE_ID.toString(), cancelSender, "Anh Nam",
                "À bên mình mở cửa tới mấy giờ?",
                null, cancelHist, List.of(DOC_ID), null, null, null, 5, null, true, null, null
        ));
        // Now the stale GENERATE_PRICE_ANCHOR_BUBBLE request arrives -> must be cancelled!
        FanpageBotResponse staleAnchorResp = botService.handleCustomerMessage(new FanpageBotRequest(
                PAGE_ID.toString(), cancelSender, "Anh Nam",
                "Anh bị mỏi 1 tuần, báo giá gói 60 phút",
                null, cancelHist, List.of(DOC_ID), null, null, null, 5,
                "GENERATE_PRICE_ANCHOR_BUBBLE", false,
                b1CancelResp.pendingTurnSequence(), b1CancelResp.priceBubbleSentAtNs()
        ));
        assertThat(staleAnchorResp.replyBubbles()).isEmpty();
        assertThat(staleAnchorResp.triggeredHooks()).contains("CANCELLED_SECOND_BUBBLE");
        assertThat(botService.getLastTurnTelemetry().cancelledSecondBubble()).isTrue();

        // Part D: Mid-sale SAFE-NEURO-001 ("tê chân" & "chèn ép rễ thần kinh") preempts sales/objection and hands off immediately
        FanpageBotResponse neuroResp = botService.handleCustomerMessage(new FanpageBotRequest(
                PAGE_ID.toString(), senderId, "Chị Lan",
                "Nhưng hiện tại chị còn bị tê chân và bác sĩ bảo nghi chèn ép rễ thần kinh thì có làm gói 239k được không?",
                null, new ArrayList<>(history), List.of(DOC_ID), null, null, null, 5, null, true, null, null
        ));
        assertThat(neuroResp.needsHumanHandoff()).isTrue();
        assertThat(neuroResp.handoffCategory()).isEqualTo("MEDICAL_SAFETY_HANDOFF");
        assertThat(neuroResp.replyText())
                .containsAnyOf("bác sĩ", "y tế")
                .doesNotContain("239k")
                .doesNotContain("399k")
                .doesNotContain("?");
        assertThat(botService.getLastTurnTelemetry().primaryAction())
                .isEqualTo(PrimaryActionRouter.PrimaryAction.HARD_HANDOFF_SAFETY);
        assertThat(botService.getLastTurnTelemetry().thinkingMode())
                .isEqualTo(ThinkingMode.FAST_NO_THINK);
    }

    @Test
    @DisplayName("8. Live Container Verification: Re-seeds nang_sen_spa_playbook.yaml and benchmarks Scenarios A/B/C/D on live docmind-api + qwen3:14b when reachable")
    void v324LiveContainerPlaybookSeedAndMultiTurnBenchmarkIfRunning() throws Exception {
        java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(2))
                .build();
        String baseUrl = null;
        for (String candidate : List.of("http://api-java:8090", "http://localhost:8090")) {
            try {
                java.net.http.HttpRequest ping = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(candidate + "/api/pages"))
                        .timeout(java.time.Duration.ofSeconds(2))
                        .GET()
                        .build();
                java.net.http.HttpResponse<String> r = http.send(ping, java.net.http.HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    baseUrl = candidate;
                    break;
                }
            } catch (Exception ignored) {
            }
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(baseUrl != null, "Live docmind-api container not reachable on api-java:8090 or localhost:8090; skipping live container benchmark");

        tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();

        // 1. Re-seed nang_sen_spa_playbook.yaml with replace_existing=true
        java.nio.file.Path playbookPath = java.nio.file.Paths.get("../docs/playbooks/nang_sen_spa_playbook.yaml");
        if (!java.nio.file.Files.exists(playbookPath)) {
            playbookPath = java.nio.file.Paths.get("docs/playbooks/nang_sen_spa_playbook.yaml");
        }
        String yamlContent = java.nio.file.Files.readString(playbookPath, StandardCharsets.UTF_8);
        String uploadBody = mapper.writeValueAsString(Map.of(
                "content", yamlContent,
                "format", "yaml",
                "page_id", PAGE_ID.toString(),
                "replace_existing", true
        ));
        java.net.http.HttpResponse<String> uploadResp = http.send(
                java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(baseUrl + "/api/rules/upload-playbook"))
                        .header("Content-Type", "application/json")
                        .timeout(java.time.Duration.ofSeconds(30))
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(uploadBody, StandardCharsets.UTF_8))
                        .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString()
        );
        assertThat(uploadResp.statusCode()).isEqualTo(200);
        System.out.println("[LIVE-BENCHMARK] Playbook upload response: " + uploadResp.body());

        // Helper for calling /api/bot/fanpage/message
        String apiBase = baseUrl;
        String liveSenderA = "live-v324-bench-a-" + System.currentTimeMillis();
        List<Map<String, String>> histA = new ArrayList<>();

        // Scenario A Turn 1: Early broad price inquiry ("Giá gội đầu dưỡng sinh bao nhiêu?")
        long t1Start = System.currentTimeMillis();
        Map<String, Object> req1 = Map.of(
                "page_id", PAGE_ID.toString(),
                "sender_id", liveSenderA,
                "message", "Giá gội đầu dưỡng sinh bao nhiêu?",
                "history", histA,
                "split_price_bubbles", true
        );
        java.net.http.HttpResponse<String> resp1 = http.send(
                java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(apiBase + "/api/bot/fanpage/message"))
                        .header("Content-Type", "application/json")
                        .timeout(java.time.Duration.ofSeconds(60))
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(req1), StandardCharsets.UTF_8))
                        .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString()
        );
        long t1Ms = System.currentTimeMillis() - t1Start;
        assertThat(resp1.statusCode()).isEqualTo(200);
        tools.jackson.databind.JsonNode j1 = mapper.readTree(resp1.body());
        System.out.println("[LIVE-BENCHMARK] Scenario A Turn 1 (" + t1Ms + "ms): " + j1.path("reply_text").asText());
        assertThat(j1.path("pending_second_bubble").asBoolean(false)).isFalse();
        histA.add(Map.of("role", "user", "content", "Giá gội đầu dưỡng sinh bao nhiêu?"));
        histA.add(Map.of("role", "assistant", "content", j1.path("reply_text").asText()));

        // Scenario A Turn 2 Request 1: "Tôi hay đau đầu căng thẳng, muốn gói 80 phút" -> Bubble 1 price quote immediately
        List<Map<String, String>> histBeforeT2 = new ArrayList<>(histA);
        long t2b1Start = System.currentTimeMillis();
        Map<String, Object> req2b1 = Map.of(
                "page_id", PAGE_ID.toString(),
                "sender_id", liveSenderA,
                "message", "Tôi hay đau đầu căng thẳng, muốn gói 80 phút",
                "history", histBeforeT2,
                "split_price_bubbles", true
        );
        java.net.http.HttpResponse<String> resp2b1 = http.send(
                java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(apiBase + "/api/bot/fanpage/message"))
                        .header("Content-Type", "application/json")
                        .timeout(java.time.Duration.ofSeconds(60))
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(req2b1), StandardCharsets.UTF_8))
                        .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString()
        );
        long t2b1Ms = System.currentTimeMillis() - t2b1Start;
        assertThat(resp2b1.statusCode()).isEqualTo(200);
        tools.jackson.databind.JsonNode j2b1 = mapper.readTree(resp2b1.body());
        System.out.println("[LIVE-BENCHMARK] Scenario A Turn 2 Bubble 1 (" + t2b1Ms + "ms): " + j2b1.path("reply_text").asText());
        assertThat(j2b1.path("pending_second_bubble").asBoolean(false)).isTrue();
        assertThat(j2b1.path("reply_text").asText()).contains("339k").contains("80 phút");

        // Scenario A Turn 2 Request 2: GENERATE_PRICE_ANCHOR_BUBBLE -> Bubble 2 emotional anchor via PRICE_ANCHOR_THINK
        long t2b2Start = System.currentTimeMillis();
        Map<String, Object> req2b2 = Map.of(
                "page_id", PAGE_ID.toString(),
                "sender_id", liveSenderA,
                "message", "Tôi hay đau đầu căng thẳng, muốn gói 80 phút",
                "history", histBeforeT2,
                "action", "GENERATE_PRICE_ANCHOR_BUBBLE",
                "pending_turn_sequence", j2b1.path("pending_turn_sequence").asLong(),
                "price_bubble_sent_at_ns", j2b1.path("price_bubble_sent_at_ns").asLong()
        );
        java.net.http.HttpResponse<String> resp2b2 = http.send(
                java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(apiBase + "/api/bot/fanpage/message"))
                        .header("Content-Type", "application/json")
                        .timeout(java.time.Duration.ofSeconds(90))
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(req2b2), StandardCharsets.UTF_8))
                        .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString()
        );
        long t2b2Ms = System.currentTimeMillis() - t2b2Start;
        assertThat(resp2b2.statusCode()).isEqualTo(200);
        tools.jackson.databind.JsonNode j2b2 = mapper.readTree(resp2b2.body());
        String b2Text = j2b2.path("reply_text").asText();
        int b2Delay = j2b2.path("reply_bubbles").get(0).path("typing_delay_ms").asInt();
        System.out.println("[LIVE-BENCHMARK] Scenario A Turn 2 Bubble 2 (" + t2b2Ms + "ms, remainingDelayMs=" + b2Delay + "): " + b2Text);
        assertThat(b2Text)
                .doesNotContain("339k")
                .doesNotContainIgnoringCase("hôm nay hay ngày mai")
                .doesNotContainIgnoringCase("giữ lịch");

        histA.add(Map.of("role", "user", "content", "Tôi hay đau đầu căng thẳng, muốn gói 80 phút"));
        histA.add(Map.of("role", "assistant", "content", j2b1.path("reply_text").asText() + "\n\n" + b2Text));

        // Scenario A Turn 3 (Stage 1 / Nhịp 1): "339k hơi cao nhỉ"
        long t3Start = System.currentTimeMillis();
        Map<String, Object> req3 = Map.of(
                "page_id", PAGE_ID.toString(),
                "sender_id", liveSenderA,
                "message", "339k hơi cao nhỉ",
                "history", new ArrayList<>(histA),
                "split_price_bubbles", true
        );
        java.net.http.HttpResponse<String> resp3 = http.send(
                java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(apiBase + "/api/bot/fanpage/message"))
                        .header("Content-Type", "application/json")
                        .timeout(java.time.Duration.ofSeconds(90))
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(req3), StandardCharsets.UTF_8))
                        .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString()
        );
        long t3Ms = System.currentTimeMillis() - t3Start;
        assertThat(resp3.statusCode()).isEqualTo(200);
        tools.jackson.databind.JsonNode j3 = mapper.readTree(resp3.body());
        System.out.println("[LIVE-BENCHMARK] Scenario A Turn 3 Stage 1 (" + t3Ms + "ms): " + j3.path("reply_text").asText());
        assertThat(j3.path("reply_text").asText())
                .doesNotContain("239k");
        histA.add(Map.of("role", "user", "content", "339k hơi cao nhỉ"));
        histA.add(Map.of("role", "assistant", "content", j3.path("reply_text").asText()));

        // Scenario A Turn 4 (Stage 2 / Nhịp 2): "Để tôi cân nhắc thêm"
        long t4Start = System.currentTimeMillis();
        Map<String, Object> req4 = Map.of(
                "page_id", PAGE_ID.toString(),
                "sender_id", liveSenderA,
                "message", "Để tôi cân nhắc thêm",
                "history", new ArrayList<>(histA),
                "split_price_bubbles", true
        );
        java.net.http.HttpResponse<String> resp4 = http.send(
                java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(apiBase + "/api/bot/fanpage/message"))
                        .header("Content-Type", "application/json")
                        .timeout(java.time.Duration.ofSeconds(90))
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(req4), StandardCharsets.UTF_8))
                        .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString()
        );
        long t4Ms = System.currentTimeMillis() - t4Start;
        assertThat(resp4.statusCode()).isEqualTo(200);
        tools.jackson.databind.JsonNode j4 = mapper.readTree(resp4.body());
        System.out.println("[LIVE-BENCHMARK] Scenario A Turn 4 Stage 2 (" + t4Ms + "ms): " + j4.path("reply_text").asText());
        assertThat(j4.path("reply_bubbles").size()).isEqualTo(2);
        assertThat(j4.path("reply_bubbles").get(1).path("text").asText().toLowerCase(java.util.Locale.ROOT))
                .contains("239k")
                .contains("60 phút");
        histA.add(Map.of("role", "user", "content", "Để tôi cân nhắc thêm"));
        histA.add(Map.of("role", "assistant", "content", j4.path("reply_text").asText()));

        // Scenario A Turn 5 (Stage 3 / Nhịp 3): "Thôi để lúc khác vậy"
        long t5Start = System.currentTimeMillis();
        Map<String, Object> req5 = Map.of(
                "page_id", PAGE_ID.toString(),
                "sender_id", liveSenderA,
                "message", "Thôi để lúc khác vậy",
                "history", new ArrayList<>(histA),
                "split_price_bubbles", true
        );
        java.net.http.HttpResponse<String> resp5 = http.send(
                java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(apiBase + "/api/bot/fanpage/message"))
                        .header("Content-Type", "application/json")
                        .timeout(java.time.Duration.ofSeconds(60))
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(req5), StandardCharsets.UTF_8))
                        .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString()
        );
        long t5Ms = System.currentTimeMillis() - t5Start;
        assertThat(resp5.statusCode()).isEqualTo(200);
        tools.jackson.databind.JsonNode j5 = mapper.readTree(resp5.body());
        System.out.println("[LIVE-BENCHMARK] Scenario A Turn 5 Stage 3 (" + t5Ms + "ms): " + j5.path("reply_text").asText());
        assertThat(j5.path("reply_text").asText())
                .doesNotContain("?")
                .doesNotContain("239k")
                .doesNotContain("339k");

        // Scenario B (External Price Comparison): "Sao bên mình cao hơn tiệm gội đầu gần nhà chị thế?"
        List<Map<String, String>> histB = List.of(
                Map.of("role", "user", "content", "Giá gội đầu dưỡng sinh 80 phút bao nhiêu?"),
                Map.of("role", "assistant", "content", "Dạ gói Dưỡng sinh chuyên sâu 80 phút bên em có giá 339k ạ.")
        );
        long tbStart = System.currentTimeMillis();
        Map<String, Object> reqB = Map.of(
                "page_id", PAGE_ID.toString(),
                "sender_id", liveSenderA + "-b",
                "message", "Sao bên mình cao hơn tiệm gội đầu gần nhà chị thế?",
                "history", histB,
                "split_price_bubbles", true
        );
        java.net.http.HttpResponse<String> respB = http.send(
                java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(apiBase + "/api/bot/fanpage/message"))
                        .header("Content-Type", "application/json")
                        .timeout(java.time.Duration.ofSeconds(90))
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(reqB), StandardCharsets.UTF_8))
                        .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString()
        );
        long tbMs = System.currentTimeMillis() - tbStart;
        assertThat(respB.statusCode()).isEqualTo(200);
        tools.jackson.databind.JsonNode jB = mapper.readTree(respB.body());
        System.out.println("[LIVE-BENCHMARK] Scenario B External Comparison (" + tbMs + "ms): " + jB.path("reply_text").asText());
        assertThat(jB.path("reply_text").asText())
                .contains("🥰")
                .doesNotContain("239k");

        // Scenario C (Complaint / Unresolved Question): "Tôi đang hỏi gói 339k gồm những bước gì cơ mà"
        long tcStart = System.currentTimeMillis();
        Map<String, Object> reqC = Map.of(
                "page_id", PAGE_ID.toString(),
                "sender_id", liveSenderA + "-c",
                "message", "Tôi đang hỏi gói 339k gồm những bước gì cơ mà",
                "history", histB,
                "split_price_bubbles", true
        );
        java.net.http.HttpResponse<String> respC = http.send(
                java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(apiBase + "/api/bot/fanpage/message"))
                        .header("Content-Type", "application/json")
                        .timeout(java.time.Duration.ofSeconds(90))
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(reqC), StandardCharsets.UTF_8))
                        .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString()
        );
        long tcMs = System.currentTimeMillis() - tcStart;
        assertThat(respC.statusCode()).isEqualTo(200);
        tools.jackson.databind.JsonNode jC = mapper.readTree(respC.body());
        System.out.println("[LIVE-BENCHMARK] Scenario C Repair First (" + tcMs + "ms): " + jC.path("reply_text").asText());
        assertThat(jC.path("reply_text").asText())
                .doesNotContainIgnoringCase("hôm nay hay ngày mai")
                .doesNotContainIgnoringCase("giữ lịch");

        // Scenario D (SAFE-NEURO-001): Mid-sale neurological red flag ("tê chân" + "chèn ép rễ thần kinh")
        long tdStart = System.currentTimeMillis();
        Map<String, Object> reqD = Map.of(
                "page_id", PAGE_ID.toString(),
                "sender_id", liveSenderA,
                "message", "Tôi bị đau cổ vai gáy kèm tê chân và bác sĩ nghi chèn ép rễ thần kinh thì có làm được không?",
                "history", new ArrayList<>(histA),
                "split_price_bubbles", true
        );
        java.net.http.HttpResponse<String> respD = http.send(
                java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(apiBase + "/api/bot/fanpage/message"))
                        .header("Content-Type", "application/json")
                        .timeout(java.time.Duration.ofSeconds(30))
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(reqD), StandardCharsets.UTF_8))
                        .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString()
        );
        long tdMs = System.currentTimeMillis() - tdStart;
        assertThat(respD.statusCode()).isEqualTo(200);
        tools.jackson.databind.JsonNode jD = mapper.readTree(respD.body());
        System.out.println("[LIVE-BENCHMARK] Scenario D SAFE-NEURO-001 (" + tdMs + "ms): " + jD.path("reply_text").asText());
        assertThat(jD.path("needs_human_handoff").asBoolean(false)).isTrue();
        assertThat(jD.path("handoff_category").asText()).isEqualTo("MEDICAL_SAFETY_HANDOFF");
        assertThat(jD.path("reply_text").asText())
                .doesNotContain("?")
                .doesNotContain("239k")
                .doesNotContain("339k");
    }

    @Test
    @DisplayName("16. v3.2.4-MASTER: Progressive Value Reveal (deliveredValueAngles), <580k room disambiguation, and Fast Targeted Repair telemetry")
    void v324MasterProgressiveValueRevealRoomDisambiguationAndFastTargetedRepair() throws Exception {
        List<ReciprocalRankFusion.FusedChunk> chunks = buildTestChunks();
        List<String> keywords = List.of("cổ", "vai", "gáy", "lưng", "chân", "thảo dược", "massage", "dưỡng sinh", "239k", "399k");

        // 1. Verify PackageCatalogService disambiguates conflicting room / highlight text for <580k HN packages
        String conflictingTable = """
                | Mã gói | Tên gói | Giá | Tổng thời gian | Bao gồm | Phòng | KTV | Điểm nổi bật | Phù hợp |
                | --- | --- | --- | --- | --- | --- | --- | --- | --- |
                | NS01 | Massage cơ bản | 399.000VNĐ | 60 phút | Full body massage trị liệu | Phòng chung | KTV tiêu chuẩn | Phòng Vip, tắm xông thảo dược, buffet thực dưỡng | Khách văn phòng |
                | NS02 | Massage ngâm thảo dược | 530.000VNĐ | 90 phút | Ngâm bồn thảo dược + massage | Phòng chung | KTV tiêu chuẩn | Phòng VIP riêng tư, ngâm thảo dược | Khách mỏi cơ |
                | NS03 | Massage VIP 1 | 580.000VNĐ | 80 phút | Xông hơi + ngâm chân + massage | Phòng VIP | KTV VIP | Phòng VIP riêng tư, chườm ngải cứu | Khách cần riêng tư |
                | DS02 | Gói dưỡng sinh cơ bản | 239.000VNĐ | 60 phút | Gội đầu cơ bản + massage cổ vai gáy + ăn nhẹ, trái cây theo mùa | Khu dưỡng sinh | KTV dưỡng sinh | Thư giãn nhẹ nhàng | Khách thư giãn nhanh |
                """;
        ReciprocalRankFusion.FusedChunk tableChunk = new ReciprocalRankFusion.FusedChunk(
                new RetrievedChunk(
                        UUID.randomUUID(),
                        DOC_ID,
                        List.of("MASSAGE", "Cơ sở Hà Nội"),
                        List.of(),
                        1,
                        new double[]{0, 0, 10, 10},
                        conflictingTable,
                        0.98
                ),
                0.98
        );
        List<PackageCatalogService.PackageSummary> parsedCatalog = PackageCatalogService.extractCatalog(
                List.of(tableChunk),
                keywords
        );
        PackageCatalogService.PackageSummary ns01 = parsedCatalog.stream()
                .filter(p -> "NS01".equals(p.code())).findFirst().orElseThrow();
        PackageCatalogService.PackageSummary ns02 = parsedCatalog.stream()
                .filter(p -> "NS02".equals(p.code())).findFirst().orElseThrow();
        PackageCatalogService.PackageSummary ns03 = parsedCatalog.stream()
                .filter(p -> "NS03".equals(p.code())).findFirst().orElseThrow();
        assertThat(ns01.roomType()).contains("rèm che riêng kín đáo").doesNotContainIgnoringCase("Phòng Vip");
        assertThat(ns01.processSummary()).contains("rèm che riêng kín đáo").doesNotContainIgnoringCase("Phòng Vip");
        assertThat(ns02.roomType()).contains("rèm che riêng kín đáo").doesNotContainIgnoringCase("Phòng VIP");
        assertThat(ns02.processSummary()).contains("rèm che riêng kín đáo").doesNotContainIgnoringCase("Phòng VIP");
        assertThat(ns03.roomType()).containsIgnoringCase("Phòng VIP");

        // 2. Verify deliveredValueAngles() is NOT marked when Bubble 1 only is sent (splitPriceBubbles=true) or when Bubble 2 is superseded
        List<FanpageBotRequest.ConversationTurn> hBeforeQuote = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Mình bị đau mỏi cổ vai gáy"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ tình trạng mỏi cổ vai gáy của mình mới gần đây hay đã kéo dài ạ?")
        );
        ConversationState stateBeforeQuote = ConversationState.hydrate(PAGE_ID, "u-val-angle", hBeforeQuote, keywords, List.of(tableChunk));
        PrimaryActionRouter.RoutingDecision quoteDecision = PrimaryActionRouter.route(
                "Mới bị mấy hôm nay thôi, báo giá giúp mình", stateBeforeQuote, List.of(tableChunk), keywords
        );
        List<FanpageBotResponse.ReplyBubble> bubble1Only = List.of(
                new FanpageBotResponse.ReplyBubble("Dạ mình tham khảo gói Massage cơ bản 60 phút giá 399k ạ.", 0)
        );
        ConversationState afterBubble1Only = ConversationState.updateAfterSend(
                PAGE_ID, "u-val-angle", stateBeforeQuote, "Mới bị mấy hôm nay thôi, báo giá giúp mình", quoteDecision, bubble1Only, false
        );
        assertThat(afterBubble1Only.deliveredValueAngles()).doesNotContain("VALUE_ANGLE_1");

        // When Bubble 2 is superseded/cancelled (empty finalBubbles), VALUE_ANGLE_1 must still NOT be marked delivered
        PrimaryActionRouter.RoutingDecision anchorDecision = new PrimaryActionRouter.RoutingDecision(
                PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE,
                PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE,
                false,
                1,
                "GENERATE_PRICE_ANCHOR_BUBBLE",
                "spa_price_quote"
        );
        ConversationState afterCancelledBubble2 = ConversationState.updateAfterSend(
                PAGE_ID, "u-val-angle", afterBubble1Only, "__GENERATE_PRICE_ANCHOR_BUBBLE__", anchorDecision, List.of(), false
        );
        assertThat(afterCancelledBubble2.deliveredValueAngles()).doesNotContain("VALUE_ANGLE_1");

        // When Bubble 2 is actually delivered, VALUE_ANGLE_1 IS marked delivered
        List<FanpageBotResponse.ReplyBubble> deliveredBubble2 = List.of(
                new FanpageBotResponse.ReplyBubble("Liệu trình kết hợp chăm sóc êm ái vùng cổ vai gáy, kỹ thuật viên điều chỉnh lực theo cảm nhận của mình ạ.", 7200)
        );
        ConversationState afterDeliveredBubble2 = ConversationState.updateAfterSend(
                PAGE_ID, "u-val-angle", afterBubble1Only, "__GENERATE_PRICE_ANCHOR_BUBBLE__", anchorDecision, deliveredBubble2, false
        );
        assertThat(afterDeliveredBubble2.deliveredValueAngles()).contains("VALUE_ANGLE_1");
        assertThat(ThinkingModePolicy.extractPreviouslyUsedArgumentsFromState(afterDeliveredBubble2))
                .contains("VALUE_ANGLE_1");

        // Stage 1 objection marks VALUE_ANGLE_2 as delivered
        PrimaryActionRouter.RoutingDecision stage1ObjDecision = PrimaryActionRouter.route(
                "Giá 399k hơi cao nhỉ", afterDeliveredBubble2, List.of(tableChunk), keywords
        );
        List<FanpageBotResponse.ReplyBubble> stage1Bubbles = List.of(
                new FanpageBotResponse.ReplyBubble("Dạ em rất hiểu băn khoăn của mình về chi phí ạ.", 0),
                new FanpageBotResponse.ReplyBubble("Mình có thể trải nghiệm thử 1 buổi trước để cảm nhận trực tiếp nha 🥰", 2000)
        );
        ConversationState afterStage1Obj = ConversationState.updateAfterSend(
                PAGE_ID, "u-val-angle", afterDeliveredBubble2, "Giá 399k hơi cao nhỉ", stage1ObjDecision, stage1Bubbles, false
        );
        assertThat(afterStage1Obj.deliveredValueAngles()).contains("VALUE_ANGLE_1", "VALUE_ANGLE_2");

        // 3. Verify Fast Targeted Repair (max_regenerations = 1 with FAST_NO_THINK) repairs false VIP claim on 399k package
        java.util.concurrent.atomic.AtomicInteger callCounter = new java.util.concurrent.atomic.AtomicInteger(0);
        AtomicReference<String> secondCallBody = new AtomicReference<>("");
        HttpServer repairServer = HttpServer.create(new InetSocketAddress(0), 0);
        repairServer.createContext("/api/chat", exchange -> {
            int attempt = callCounter.incrementAndGet();
            String reqBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (attempt == 2) {
                secondCallBody.set(reqBody);
            }
            String jsonContent;
            if (attempt == 1) {
                // Attempt 1 violates room disambiguation by falsely claiming Phòng VIP riêng for 399k
                jsonContent = """
                        {"reply_bubbles":[{"text":"Dạ gói Massage cơ bản 60 phút giá 399k có Phòng VIP riêng sang trọng ạ.","typing_delay_ms":0},{"text":"Liệu trình chăm sóc êm ái vùng cổ vai gáy, kỹ thuật viên điều chỉnh lực theo cảm nhận của mình ạ.","typing_delay_ms":8000}],"should_handoff":false}
                        """.strip();
            } else {
                // Attempt 2 (Fast Targeted Repair) fixes the violation
                jsonContent = """
                        {"reply_bubbles":[{"text":"Dạ với tình trạng mỏi cổ vai gáy mới xuất hiện, mình tham khảo gói Massage cơ bản 60 phút giá 399k trọn gói không nhận tip ạ.","typing_delay_ms":0},{"text":"Không gian yên tĩnh, mỗi giường đều có rèm che riêng kín đáo và kỹ thuật viên điều chỉnh lực theo cảm nhận của mình ạ.","typing_delay_ms":8000}],"should_handoff":false}
                        """.strip();
            }
            String escaped = jsonContent.replace("\\", "\\\\").replace("\"", "\\\"");
            String resp = "{\"message\":{\"role\":\"assistant\",\"content\":\"" + escaped + "\"}}";
            byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        repairServer.start();
        try {
            int port = repairServer.getAddress().getPort();
            OllamaProperties ollamaProps = new OllamaProperties();
            ollamaProps.setBaseUrl("http://localhost:" + port);
            ollamaProps.setModel("qwen3:14b");
            ollamaProps.setReadTimeoutMs(5000);
            OllamaLlmClient ollamaClient = new OllamaLlmClient(ollamaProps);

            PromptBuilder.PagePersona persona = buildTestPersona();
            com.example.docmind.service.retrieval.RetrievalService retrievalService =
                    org.mockito.Mockito.mock(com.example.docmind.service.retrieval.RetrievalService.class);
            org.mockito.Mockito.when(retrievalService.retrieveWithRules(
                    org.mockito.Mockito.anyList(),
                    org.mockito.Mockito.anyString(),
                    org.mockito.Mockito.anyInt(),
                    org.mockito.Mockito.anyList(),
                    org.mockito.Mockito.any()
            )).thenReturn(new com.example.docmind.service.retrieval.RetrievalService.RetrievalResult(List.of(tableChunk), List.of()));

            com.example.docmind.repository.KnowledgePageRepository pageRepo =
                    org.mockito.Mockito.mock(com.example.docmind.repository.KnowledgePageRepository.class);
            com.example.docmind.entity.KnowledgePageEntity pageEntity = new com.example.docmind.entity.KnowledgePageEntity();
            pageEntity.setId(PAGE_ID);
            pageEntity.setName(persona.name());
            pageEntity.setIndustry(persona.industry());
            pageEntity.setTargetAudience(persona.targetAudience());
            pageEntity.setDescription(persona.description());
            pageEntity.setPersonaConfig(persona.resolvedConfig());
            org.mockito.Mockito.when(pageRepo.findById(PAGE_ID)).thenReturn(java.util.Optional.of(pageEntity));

            FanpageBotService service = new FanpageBotService(
                    retrievalService,
                    ollamaClient,
                    new com.example.docmind.config.ChatProperties(),
                    null,
                    pageRepo,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );
            FanpageBotRequest req = new FanpageBotRequest(
                    PAGE_ID.toString(),
                    "u-repair-test",
                    "Khách hàng",
                    "Mới bị mấy hôm nay thôi, giá gói 60 phút bao nhiêu shop?",
                    null,
                    hBeforeQuote,
                    List.of(DOC_ID),
                    null,
                    null,
                    null,
                    5,
                    null,
                    false,
                    null,
                    null
            );
            FanpageBotResponse resp = service.handleCustomerMessage(req);
            assertThat(callCounter.get()).isEqualTo(2);
            assertThat(secondCallBody.get())
                    .contains("\"think\":false")
                    .contains("\"num_predict\":400")
                    .contains("/no_think")
                    .contains("FAST_TARGETED_REPAIR_HINT");
            assertThat(resp.replyText())
                    .contains("399k")
                    .contains("rèm che riêng kín đáo")
                    .doesNotContainIgnoringCase("Phòng VIP");
            ThinkingModePolicy.TurnTelemetry telemetry = service.getLastTurnTelemetry();
            assertThat(telemetry).isNotNull();
            assertThat(telemetry.retryCount()).isEqualTo(1);
            assertThat(telemetry.repairElapsedMs()).isGreaterThanOrEqualTo(0L);
            assertThat(telemetry.responseValidated()).isTrue();
            assertThat(telemetry.usedTemplateFallback()).isFalse();
            assertThat(telemetry.generationSource()).isEqualTo("LLM_REPAIRED");
            assertThat(telemetry.repairApplied()).isTrue();
            assertThat(telemetry.repairAttemptCount()).isEqualTo(1);
            assertThat(telemetry.validationOutcome()).isEqualTo("REPAIRED_PASS");
        } finally {
            repairServer.stop(0);
        }
    }

    @Test
    @DisplayName("17. v3.2.4-FINAL: Preserves natural LLM_LIVE text across 7-turn scenario without template overwrite, plus negative guardrail tests")
    void v324FinalSevenTurnScenarioPreservesLlmLiveAndEnforcesNegativeGuardrails() {
        List<ReciprocalRankFusion.FusedChunk> chunks = buildTestChunks();
        List<String> keywords = List.of("cổ", "vai", "gáy", "đầu", "lưng", "chân", "thảo dược", "massage", "dưỡng sinh", "239k", "399k");
        PromptBuilder.PagePersona persona = buildTestPersona();

        // Verify VietnameseQueryNormalizer contextual "ns" normalization preserves NS/NS01 codes
        assertThat(com.example.docmind.service.text.VietnameseQueryNormalizer.normalizeForClassification("NS linh ta linh tỉnh, vớ vânr"))
                .contains("nói linh ta linh tinh")
                .contains("vớ vẩn");
        assertThat(com.example.docmind.service.text.VietnameseQueryNormalizer.normalizeForClassification("Cho mình xem gói NS01 bên NS"))
                .contains("ns01")
                .doesNotContain("nói01");

        com.example.docmind.service.retrieval.RetrievalService retrievalService =
                org.mockito.Mockito.mock(com.example.docmind.service.retrieval.RetrievalService.class);
        org.mockito.Mockito.when(retrievalService.retrieveWithRules(
                org.mockito.Mockito.anyList(),
                org.mockito.Mockito.anyString(),
                org.mockito.Mockito.anyInt(),
                org.mockito.Mockito.anyList(),
                org.mockito.Mockito.any()
        )).thenReturn(new com.example.docmind.service.retrieval.RetrievalService.RetrievalResult(chunks, List.of()));

        com.example.docmind.repository.KnowledgePageRepository pageRepo =
                org.mockito.Mockito.mock(com.example.docmind.repository.KnowledgePageRepository.class);
        com.example.docmind.entity.KnowledgePageEntity pageEntity = new com.example.docmind.entity.KnowledgePageEntity();
        pageEntity.setId(PAGE_ID);
        pageEntity.setName(persona.name());
        pageEntity.setIndustry(persona.industry());
        pageEntity.setTargetAudience(persona.targetAudience());
        pageEntity.setDescription(persona.description());
        pageEntity.setPersonaConfig(persona.resolvedConfig());
        org.mockito.Mockito.when(pageRepo.findById(PAGE_ID)).thenReturn(java.util.Optional.of(pageEntity));

        AtomicReference<String> simulatedLlmJson = new AtomicReference<>();
        com.example.docmind.service.llm.LlmClient llmClient =
                org.mockito.Mockito.mock(com.example.docmind.service.llm.LlmClient.class);
        org.mockito.Mockito.when(llmClient.name()).thenReturn("ollama:qwen3:14b");
        org.mockito.Mockito.when(llmClient.complete(org.mockito.Mockito.anyString(), org.mockito.Mockito.any(ReasoningBudget.class)))
                .thenAnswer(inv -> simulatedLlmJson.get());

        FanpageBotService service = new FanpageBotService(
                retrievalService,
                llmClient,
                new com.example.docmind.config.ChatProperties(),
                null,
                pageRepo,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        String senderId = "u-v324-final-7turns";
        List<FanpageBotRequest.ConversationTurn> history = new ArrayList<>();

        // Turn 1: "giá" -> v3.2.5 Warm 3-Bubble Brand Opening: 2 CONFIGURED_OPENING bubbles (0ms, 1000ms) + 1 LLM_LIVE bubble (2000ms)
        String t1LlmText = "Để em báo đúng gói phù hợp nhất, hiện mình đang muốn tìm hiểu massage trị liệu giảm đau mỏi hay gội đầu dưỡng sinh thư giãn ạ?";
        simulatedLlmJson.set("{\"reply_bubbles\":[{\"text\":\"" + t1LlmText + "\",\"typing_delay_ms\":2000}],\"should_handoff\":false}");
        FanpageBotResponse r1 = service.handleCustomerMessage(new FanpageBotRequest(
                PAGE_ID.toString(), senderId, "Khách", "giá", null, new ArrayList<>(history), List.of(DOC_ID), 5
        ));
        assertThat(r1.replyBubbles()).hasSize(3);
        assertThat(r1.replyBubbles().get(0).typingDelayMs()).isZero();
        assertThat(r1.replyBubbles().get(1).typingDelayMs()).isEqualTo(1000);
        assertThat(r1.replyBubbles().get(2).text()).isEqualTo(t1LlmText);
        assertThat(r1.replyBubbles().get(2).typingDelayMs()).isEqualTo(2000);
        assertThat(service.getLastTurnTelemetry().generationSource()).isEqualTo("LLM_LIVE");
        assertThat(service.getLastTurnTelemetry().bubbleSources())
                .containsExactly("CONFIGURED_OPENING", "CONFIGURED_OPENING", "LLM_LIVE");
        assertThat(service.getLastTurnTelemetry().usedTemplateFallback()).isFalse();
        history.add(new FanpageBotRequest.ConversationTurn("user", "giá"));
        history.add(new FanpageBotRequest.ConversationTurn("assistant", r1.replyText()));

        // Turn 2: "tôi bị đau đầu, mất ngủ, nhức vai gáy" -> Preserves all 3 symptoms, no "căng" assumption, no medical diagnosis
        String t2LlmText = "Dạ vừa đau đầu, mất ngủ lại thêm nhức vai gáy cùng lúc chắc hẳn làm mình mệt mỏi và khó sâu giấc lắm ạ. Tình trạng này mình mới gặp vài hôm nay hay đã kéo dài lâu rồi để em chọn liệu trình sát nhất cho mình ạ?";
        simulatedLlmJson.set("{\"reply_bubbles\":[{\"text\":\"" + t2LlmText + "\",\"typing_delay_ms\":0}],\"should_handoff\":false}");
        FanpageBotResponse r2 = service.handleCustomerMessage(new FanpageBotRequest(
                PAGE_ID.toString(), senderId, "Khách", "tôi bị đau đầu, mất ngủ, nhức vai gáy", null, new ArrayList<>(history), List.of(DOC_ID), 5
        ));
        assertThat(r2.replyBubbles()).hasSize(1);
        assertThat(r2.replyBubbles().get(0).text()).isEqualTo(t2LlmText);
        assertThat(service.getLastTurnTelemetry().generationSource()).isEqualTo("LLM_LIVE");
        ConversationState sAfterT2 = ConversationState.getSessionState(PAGE_ID, senderId);
        assertThat(sAfterT2.knownSymptoms()).contains("đau đầu", "mất ngủ", "nhức vai gáy").doesNotContain("căng");
        history.add(new FanpageBotRequest.ConversationTurn("user", "tôi bị đau đầu, mất ngủ, nhức vai gáy"));
        history.add(new FanpageBotRequest.ConversationTurn("assistant", r2.replyText()));

        // Turn 3: "Căng gì, tôi bị căng đâu" -> Pushback on assumption, REPAIR_FIRST, does NOT add "căng" to knownSymptoms, no premature price quote
        String t3LlmText = "Dạ em xin lỗi mình vì em diễn đạt chưa chuẩn làm mình hiểu nhầm ạ. Em đã ghi nhận đúng tình trạng mình chia sẻ là đau đầu, mất ngủ và nhức vai gáy rồi ạ, mình muốn em gửi luôn thông tin và mức giá gói trị liệu phù hợp cho mình tham khảo nhé ạ?";
        simulatedLlmJson.set("{\"reply_bubbles\":[{\"text\":\"" + t3LlmText + "\",\"typing_delay_ms\":0}],\"should_handoff\":false}");
        FanpageBotResponse r3 = service.handleCustomerMessage(new FanpageBotRequest(
                PAGE_ID.toString(), senderId, "Khách", "Căng gì, tôi bị căng đâu", null, new ArrayList<>(history), List.of(DOC_ID), 5
        ));
        assertThat(r3.replyBubbles()).hasSize(1);
        assertThat(r3.replyBubbles().get(0).text()).isEqualTo(t3LlmText);
        assertThat(service.getLastTurnTelemetry().thinkingMode()).isEqualTo(ThinkingMode.REPAIR_FIRST);
        assertThat(service.getLastTurnTelemetry().detectedIntent()).isEqualTo("PUSHBACK_ON_ASSUMPTION_REPAIR");
        assertThat(service.getLastTurnTelemetry().generationSource()).isEqualTo("LLM_LIVE");
        ConversationState sAfterT3 = ConversationState.getSessionState(PAGE_ID, senderId);
        assertThat(sAfterT3.knownSymptoms()).doesNotContain("căng");
        history.add(new FanpageBotRequest.ConversationTurn("user", "Căng gì, tôi bị căng đâu"));
        history.add(new FanpageBotRequest.ConversationTurn("assistant", r3.replyText()));

        // Turn 4: "NS linh ta linh tỉnh, vớ vânr" -> Frustration + unresolved price from Turn 1 -> apologizes briefly & quotes NS01 60 phút 399k
        String t4b1 = "Dạ em thật sự xin lỗi mình vì hỏi hơi kỹ làm mình sốt ruột ạ! Với tình trạng đau đầu, mất ngủ và nhức vai gáy của mình, gói phù hợp nhất là Massage cơ bản 60 phút giá 399k trọn gói ạ.";
        String t4b2 = "Liệu trình 60 phút tập trung ấn huyệt giải cơ sâu vùng cổ vai gáy và đầu, kết hợp dùng buffet thực dưỡng miễn phí sau buổi làm giúp mình dịu cơn nhức và dễ ngủ hơn. Mình thấy gói 60 phút này hợp với thời gian của mình không ạ?";
        simulatedLlmJson.set("{\"reply_bubbles\":[{\"text\":\"" + t4b1 + "\",\"typing_delay_ms\":0},{\"text\":\"" + t4b2 + "\",\"typing_delay_ms\":10000}],\"should_handoff\":false}");
        FanpageBotResponse r4 = service.handleCustomerMessage(new FanpageBotRequest(
                PAGE_ID.toString(), senderId, "Khách", "NS linh ta linh tỉnh, vớ vânr", null, new ArrayList<>(history), List.of(DOC_ID), 5
        ));
        assertThat(r4.replyBubbles()).hasSize(2);
        assertThat(r4.replyBubbles().get(0).text()).isEqualTo(t4b1);
        assertThat(r4.replyBubbles().get(0).typingDelayMs()).isZero();
        assertThat(r4.replyBubbles().get(1).text()).isEqualTo(t4b2);
        assertThat(r4.replyBubbles().get(1).typingDelayMs()).isEqualTo(10000);
        assertThat(service.getLastTurnTelemetry().primaryAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE);
        assertThat(service.getLastTurnTelemetry().detectedIntent()).isEqualTo("FRUSTRATION_RECOVERY_QUOTE_PENDING_PRICE");
        assertThat(service.getLastTurnTelemetry().thinkingMode()).isEqualTo(ThinkingMode.REPAIR_FIRST);
        assertThat(service.getLastTurnTelemetry().generationSource()).isEqualTo("LLM_LIVE");
        ConversationState sAfterT4 = ConversationState.getSessionState(PAGE_ID, senderId);
        assertThat(sAfterT4.lastOfferedPackageId()).isEqualTo("massage_co_ban_60");
        assertThat(sAfterT4.lastOfferedDuration()).isEqualTo("60");
        assertThat(sAfterT4.lastOfferedPrice()).isEqualTo("399k");
        history.add(new FanpageBotRequest.ConversationTurn("user", "NS linh ta linh tỉnh, vớ vânr"));
        history.add(new FanpageBotRequest.ConversationTurn("assistant", r4.replyText()));

        // Turn 5: "Đắt" -> Stage 1 objection, holds 399k NS01, natural LLM text preserved WITHOUT appending " 🥰" and WITHOUT higher-tier sauna claims
        String t5LlmText = "Dạ em rất hiểu mình cân nhắc về chi phí ạ. Mức 399k cho 60 phút bên em đã bao gồm trọn gói massage trị liệu toàn thân ấn huyệt chuyên sâu vùng đầu vai gáy, dùng bữa nhẹ thực dưỡng miễn phí sau buổi làm và hoàn toàn không nhận tiền Tip, nên mình có thể yên tâm trải nghiệm thử 1 buổi xem cơ thể nhẹ nhõm thế nào nhé ạ.";
        simulatedLlmJson.set("{\"reply_bubbles\":[{\"text\":\"" + t5LlmText + "\",\"typing_delay_ms\":0}],\"should_handoff\":false}");
        FanpageBotResponse r5 = service.handleCustomerMessage(new FanpageBotRequest(
                PAGE_ID.toString(), senderId, "Khách", "Đắt", null, new ArrayList<>(history), List.of(DOC_ID), 5
        ));
        assertThat(r5.replyBubbles()).hasSize(1);
        // Must preserve exact LLM text without appending " 🥰"
        assertThat(r5.replyBubbles().get(0).text()).isEqualTo(t5LlmText);
        assertThat(service.getLastTurnTelemetry().primaryAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.HANDLE_PRICE_OBJECTION_FIRST_TIME);
        assertThat(service.getLastTurnTelemetry().thinkingMode()).isEqualTo(ThinkingMode.OBJECTION_REFRAME_THINK);
        assertThat(service.getLastTurnTelemetry().generationSource()).isEqualTo("LLM_LIVE");
        history.add(new FanpageBotRequest.ConversationTurn("user", "Đắt"));
        history.add(new FanpageBotRequest.ConversationTurn("assistant", r5.replyText()));

        // Turn 6: "để tôi cân nhắc thêm" -> Stage 2 downsell to DS02 Gói dưỡng sinh cơ bản 60 phút 239k
        String t6b1 = "Dạ vâng, em hoàn toàn tôn trọng việc mình cân nhắc thêm ạ. Không biết hiện mình đang lăn tăn nhiều hơn về mức chi phí buổi đầu hay thời gian 60 phút của liệu trình ạ?";
        String t6b2 = "Nếu mình muốn thử một lựa chọn nhẹ nhàng và tiết kiệm hơn trước, bên em có Gói dưỡng sinh cơ bản 60 phút giá 239k (gồm gội thảo dược, massage mặt và chườm thảo dược cổ vai kèm ăn nhẹ) cũng giúp dịu vùng đầu và dễ ngủ lắm ạ.";
        simulatedLlmJson.set("{\"reply_bubbles\":[{\"text\":\"" + t6b1 + "\",\"typing_delay_ms\":0},{\"text\":\"" + t6b2 + "\",\"typing_delay_ms\":10000}],\"should_handoff\":false}");
        FanpageBotResponse r6 = service.handleCustomerMessage(new FanpageBotRequest(
                PAGE_ID.toString(), senderId, "Khách", "để tôi cân nhắc thêm", null, new ArrayList<>(history), List.of(DOC_ID), 5
        ));
        assertThat(r6.replyBubbles()).hasSize(2);
        assertThat(r6.replyBubbles().get(0).text()).isEqualTo(t6b1);
        assertThat(r6.replyBubbles().get(1).text()).isEqualTo(t6b2);
        assertThat(service.getLastTurnTelemetry().primaryAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.HANDLE_PRICE_OBJECTION_REPEAT);
        assertThat(service.getLastTurnTelemetry().generationSource()).isEqualTo("LLM_LIVE");
        ConversationState sAfterT6 = ConversationState.getSessionState(PAGE_ID, senderId);
        assertThat(sAfterT6.downsellOffered()).isTrue();
        assertThat(sAfterT6.lastOfferedPackageId()).isEqualTo("duong_sinh_co_ban_60");
        assertThat(sAfterT6.lastOfferedDuration()).isEqualTo("60");
        assertThat(sAfterT6.lastOfferedPrice()).isEqualTo("239k");
        history.add(new FanpageBotRequest.ConversationTurn("user", "để tôi cân nhắc thêm"));
        history.add(new FanpageBotRequest.ConversationTurn("assistant", r6.replyText()));

        // Turn 7: "thôi cũng được, thử xem" -> Confirm booking on DS02 60 phút 239k (must NOT repeat rejected 399k!)
        String t7LlmText = "Dạ vâng ạ, vậy em xin phép ghi nhận mình chọn Gói dưỡng sinh cơ bản 60 phút giá 239k nhé ạ. Mình dự kiến ghé cơ sở bên em vào khung giờ nào và cho em xin Số điện thoại để lễ tân giữ chỗ chu đáo cho mình nha?";
        simulatedLlmJson.set("{\"reply_bubbles\":[{\"text\":\"" + t7LlmText + "\",\"typing_delay_ms\":0}],\"should_handoff\":false}");
        FanpageBotResponse r7 = service.handleCustomerMessage(new FanpageBotRequest(
                PAGE_ID.toString(), senderId, "Khách", "thôi cũng được, thử xem", null, new ArrayList<>(history), List.of(DOC_ID), 5
        ));
        assertThat(r7.replyBubbles()).hasSize(1);
        assertThat(r7.replyBubbles().get(0).text()).isEqualTo(t7LlmText);
        assertThat(r7.replyText()).contains("239k").doesNotContain("399k");
        assertThat(service.getLastTurnTelemetry().primaryAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.CONFIRM_BOOKING_DETAILS);
        assertThat(service.getLastTurnTelemetry().generationSource()).isEqualTo("LLM_LIVE");

        // Negative Guardrail Checks:
        // (a) Repeating rejected 399k on Turn 7 after 239k downsell is flagged by ReplyContractValidator
        ReplyContractValidator.ValidationResult rejectedRepeatRes = ReplyContractValidator.validateAndEnforce(
                List.of(new FanpageBotResponse.ReplyBubble("Dạ em chốt cho mình gói Massage cơ bản 60 phút giá 399k nhé ạ.", 0)),
                sAfterT6,
                "thôi cũng được, thử xem",
                PrimaryActionRouter.PrimaryAction.CONFIRM_BOOKING_DETAILS,
                false,
                10000,
                ThinkingMode.FAST_NO_THINK,
                0L
        );
        assertThat(rejectedRepeatRes.errors()).contains("VR-004A_NO_REJECTED_PACKAGE_REPEAT");

        // (b) Medical diagnosis ("khí huyết lên vùng đầu kém lưu thông") or unstated "căng" assumption is flagged by ReplyContractValidator
        ReplyContractValidator.ValidationResult medAndCangRes = ReplyContractValidator.validateAndEnforce(
                List.of(new FanpageBotResponse.ReplyBubble("Dạ đau đầu mất ngủ là do khí huyết lên vùng đầu kém lưu thông, mình thường căng sau ngồi lâu hay vận động nhiều ạ?", 0)),
                sAfterT2,
                "tôi bị đau đầu, mất ngủ, nhức vai gáy",
                PrimaryActionRouter.PrimaryAction.ACKNOWLEDGE_AND_ASK_PREFERENCE,
                false,
                10000,
                ThinkingMode.FAST_NO_THINK,
                0L
        );
        assertThat(medAndCangRes.errors()).contains("MEDICAL_CLAIM", "UNSTATED_CANG_ASSUMPTION");

        // (c) False higher-tier benefit claim ("tắm xông thảo dược" / "xông hơi") on NS01 399k is flagged as UNGROUNDED_PACKAGE_BENEFIT
        ReplyContractValidator.ValidationResult ungroundedPerkRes = ReplyContractValidator.validateAndEnforce(
                List.of(new FanpageBotResponse.ReplyBubble("Dạ gói Massage cơ bản 60 phút giá 399k đã bao gồm tắm xông thảo dược và ngâm bồn thảo dược ấm ạ.", 0)),
                sAfterT4,
                "Đắt",
                PrimaryActionRouter.PrimaryAction.HANDLE_PRICE_OBJECTION_FIRST_TIME,
                false,
                10000,
                ThinkingMode.OBJECTION_REFRAME_THINK,
                0L
        );
        assertThat(ungroundedPerkRes.errors()).contains("UNGROUNDED_PACKAGE_BENEFIT");

        // (d) Complex negation ("Tôi không bị căng vai đâu mà chỉ bị tê rần hai bàn chân thôi") strips negated "căng"/"vai" and preserves positive "tê rần"/"chân"
        ConversationState complexNegState = ConversationState.hydrate(
                PAGE_ID,
                "u-complex-negation",
                List.of(new FanpageBotRequest.ConversationTurn("user", "Tôi không bị căng vai đâu mà chỉ bị tê rần hai bàn chân thôi")),
                keywords,
                chunks
        );
        assertThat(complexNegState.knownSymptoms())
                .anyMatch(s -> s.contains("tê rần"))
                .noneMatch(s -> s.contains("căng") || s.contains("vai"));
        assertThat(complexNegState.knownBodyAreas())
                .contains("chân")
                .doesNotContain("vai");

        // (e) Inverted customer pronoun ("Em đang quan tâm dịch vụ nào ạ?") is flagged as INVERTED_CUSTOMER_PRONOUN
        ReplyContractValidator.ValidationResult invertedPronounRes = ReplyContractValidator.validateAndEnforce(
                List.of(new FanpageBotResponse.ReplyBubble("Dạ chào mình! Em đang quan tâm đến dịch vụ nào của Nàng Sen Spa để em báo giá ạ?", 0)),
                sAfterT2,
                "giá",
                PrimaryActionRouter.PrimaryAction.GENERIC_SHORT_DISCOVERY,
                true,
                10000,
                ThinkingMode.FAST_NO_THINK,
                0L
        );
        assertThat(invertedPronounRes.errors()).contains("INVERTED_CUSTOMER_PRONOUN");

        // (f) sanitizeRawMessage preserves raw input while normalizeForClassification preserves NS01 and SP-01
        assertThat(com.example.docmind.service.text.VietnameseQueryNormalizer.sanitizeRawMessage("NS linh ta linh tỉnh, vớ vânr"))
                .isEqualTo("NS linh ta linh tỉnh, vớ vânr");
        assertThat(com.example.docmind.service.text.VietnameseQueryNormalizer.normalizeForClassification("Cho xem gói SP-01 và NS01"))
                .contains("sp-01")
                .contains("ns01");
    }

    @Test
    @DisplayName("v3.2.5: 5 Session Management Scenarios + Turn 1 3-Bubble Warm Opening + DS02 239k vs NS01 399k Benefit Validation")
    void v325SessionIsolationAndTurn1WarmOpeningAndPackageAttributeValidation() {
        List<String> keywords = List.of("đau đầu", "mất ngủ", "nhức vai gáy", "mỏi vai", "mỏi cổ");
        List<ReciprocalRankFusion.FusedChunk> chunks = buildTestChunks();
        String senderId = "u-v325-session-test";

        // 1. Scenario 1: New conversation ("conv-session-1") -> greetingSent is false, openingRequired is true (3 bubbles: 0ms, 1000ms, 2000ms)
        ConversationState s1Turn1 = ConversationState.hydrateForTurn(
                PAGE_ID, senderId, "conv-session-1", false, "Giá", List.of(), keywords, chunks);
        assertThat(s1Turn1.greetingSent()).isFalse();
        PrimaryActionRouter.RoutingDecision d1Turn1 = PrimaryActionRouter.route("Giá", s1Turn1, chunks, keywords);
        assertThat(d1Turn1.openingRequired()).isTrue();

        PromptBuilder.PagePersona persona = new PromptBuilder.PagePersona(
                "Nàng Sen Spa",
                "Spa & Chăm sóc sức khỏe",
                "Khách văn phòng",
                "Thư giãn trị liệu",
                new com.example.docmind.dto.PagePersonaConfig(
                        "spa_wellness",
                        "An Nhiên",
                        "em",
                        "mình",
                        "ấm áp, tinh tế",
                        "MODERATE",
                        List.of(),
                        keywords,
                        null,
                        null,
                        List.of(
                                "Dạ chào mình, em là An Nhiên — chuyên viên chăm sóc khách hàng của Nàng Sen Spa ạ.",
                                "Bên em chuyên massage trị liệu giãn cơ, gội đầu dưỡng sinh thảo dược và chăm sóc thư giãn nhẹ nhàng, chỉn chu ạ."
                        ),
                        10000,
                        null
                )
        );
        List<FanpageBotResponse.ReplyBubble> turn1RefBubbles = PrimaryActionRouter.executeActionBubbles(
                d1Turn1, "Giá", s1Turn1, "mình", persona, chunks, null);
        assertThat(turn1RefBubbles).hasSize(3);
        assertThat(turn1RefBubbles.get(0).typingDelayMs()).isEqualTo(0);
        assertThat(turn1RefBubbles.get(0).text()).contains("An Nhiên");
        assertThat(turn1RefBubbles.get(1).typingDelayMs()).isEqualTo(1000);
        assertThat(turn1RefBubbles.get(1).text()).contains("massage trị liệu giãn cơ");
        assertThat(turn1RefBubbles.get(2).typingDelayMs()).isEqualTo(2000);

        ConversationState.updateAfterSend(
                PAGE_ID, senderId, "conv-session-1", s1Turn1, "Giá", d1Turn1, turn1RefBubbles, false, 0);

        // 2. Scenario 2: Subsequent request on SAME conversationId ("conv-session-1") WITHOUT history (webhook mode)
        // Must NOT reset state and must NOT re-send greeting!
        ConversationState s1Turn2NoHistory = ConversationState.hydrateForTurn(
                PAGE_ID, senderId, "conv-session-1", false, "tôi bị đau đầu, mất ngủ, nhức vai gáy", List.of(), keywords, chunks);
        assertThat(s1Turn2NoHistory.greetingSent()).isTrue();
        assertThat(s1Turn2NoHistory.priceRequestedInSession()).isTrue();
        PrimaryActionRouter.RoutingDecision d1Turn2 = PrimaryActionRouter.route(
                "tôi bị đau đầu, mất ngủ, nhức vai gáy", s1Turn2NoHistory, chunks, keywords);
        assertThat(d1Turn2.openingRequired()).isFalse();

        // 3. Scenario 5: Retry request on "conv-session-1" -> greetingSent remains true, never duplicates opening bubbles
        ConversationState s1Retry = ConversationState.hydrateForTurn(
                PAGE_ID, senderId, "conv-session-1", false, "Giá", List.of(), keywords, chunks);
        assertThat(s1Retry.greetingSent()).isTrue();
        PrimaryActionRouter.RoutingDecision d1Retry = PrimaryActionRouter.route("Giá", s1Retry, chunks, keywords);
        assertThat(d1Retry.openingRequired()).isFalse();

        // 4. Scenario 4: Two concurrent sessions of the SAME senderId ("conv-session-1" vs "conv-session-2") -> isolated states!
        ConversationState s2Turn1 = ConversationState.hydrateForTurn(
                PAGE_ID, senderId, "conv-session-2", false, "Xin chào", List.of(), keywords, chunks);
        assertThat(s2Turn1.greetingSent()).isFalse();
        assertThat(s2Turn1.priceRequestedInSession()).isFalse();
        assertThat(ConversationState.getSessionState(PAGE_ID, senderId, "conv-session-1").priceRequestedInSession()).isTrue();

        // 5. Scenario 3: UI Reset (resetSession = true) on "conv-session-1" -> resets session and triggers 3-bubble opening again
        ConversationState s1AfterReset = ConversationState.hydrateForTurn(
                PAGE_ID, senderId, "conv-session-1", true, "Giá", List.of(), keywords, chunks);
        assertThat(s1AfterReset.greetingSent()).isFalse();
        PrimaryActionRouter.RoutingDecision d1AfterReset = PrimaryActionRouter.route("Giá", s1AfterReset, chunks, keywords);
        assertThat(d1AfterReset.openingRequired()).isTrue();

        // 6. Section 3.3: DS02 (239k) must NOT claim "massage toàn thân" / "full body" or "buffet thực dưỡng"
        ReplyContractValidator.ValidationResult ds02FalseFullBody = ReplyContractValidator.validateAndEnforce(
                List.of(new FanpageBotResponse.ReplyBubble("Dạ bên em có Gói dưỡng sinh cơ bản 60 phút giá 239k bao gồm massage toàn thân và buffet thực dưỡng miễn phí ạ.", 0)),
                s1Turn1,
                "để tôi cân nhắc thêm",
                PrimaryActionRouter.PrimaryAction.HANDLE_PRICE_OBJECTION_REPEAT,
                false,
                10000,
                ThinkingMode.OBJECTION_REFRAME_THINK,
                0L
        );
        assertThat(ds02FalseFullBody.errors()).contains("UNGROUNDED_PACKAGE_BENEFIT");

        // 7. Section 4.2: Robotic phrasing or mixed "Chị" + "mình" is flagged by ReplyContractValidator
        ReplyContractValidator.ValidationResult mixedPronounRes = ReplyContractValidator.validateAndEnforce(
                List.of(new FanpageBotResponse.ReplyBubble("Chị đang gặp phải tình trạng đau đầu khiến mình mệt mỏi hẳn luôn ạ. Liệu Chị có muốn đặt lịch không ạ?", 0)),
                s1Turn1,
                "tôi bị đau đầu",
                PrimaryActionRouter.PrimaryAction.ACKNOWLEDGE_AND_ASK_PREFERENCE,
                false,
                10000,
                ThinkingMode.FAST_NO_THINK,
                0L
        );
        assertThat(mixedPronounRes.errors()).contains("ROBOTIC_OR_MIXED_PRONOUN_VOICE");
    }
}
