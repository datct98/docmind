package com.example.docmind.service.bot;

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

    static List<ReciprocalRankFusion.FusedChunk> buildTestChunks() {
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

    static PromptBuilder.PagePersona buildTestPersona() {
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

    private static PrimaryActionRouter.RoutingDecision routeEnriched(
            String msg,
            ConversationState state,
            List<ReciprocalRankFusion.FusedChunk> chunks,
            List<String> keywords) {
        return PrimaryActionRouter.route(msg, state.enrichWithCurrentMessage(msg, keywords), chunks, keywords);
    }

    @Test
    @DisplayName("2. ThinkingModePolicy routes all 10 scenarios and A/B/C benchmark modes accurately")
    void thinkingModePolicyRoutesAllScenariosAndBenchmarkModes() {
        List<ReciprocalRankFusion.FusedChunk> chunks = buildTestChunks();
        List<String> keywords = List.of("cổ", "vai", "gáy", "lưng", "chân", "thảo dược", "massage", "dưỡng sinh", "239k", "399k");

        // Scenario 1: Greeting / Broad Price Inquiry -> FAST_NO_THINK
        ConversationState s1 = ConversationState.hydrate(PAGE_ID, "u-s1", List.of(), keywords, chunks);
        PrimaryActionRouter.RoutingDecision d1 = routeEnriched("Giá sao shop", s1, chunks, keywords);
        ThinkingModePolicy.ThinkingDecision td1 = ThinkingModePolicy.decide("Giá sao shop", s1, d1, null);
        assertThat(td1.thinkingMode()).isEqualTo(ThinkingMode.FAST_NO_THINK);
        assertThat(td1.thinkEnabled()).isFalse();

        // Scenario 2: Discovery Clarify ("Đau mỏi cổ vai gáy") -> FAST_NO_THINK
        List<FanpageBotRequest.ConversationTurn> h2 = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Giá sao shop"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ chào mình! Mình đang quan tâm chăm sóc vùng nào ạ?")
        );
        ConversationState s2 = ConversationState.hydrate(PAGE_ID, "u-s2", h2, keywords, chunks);
        PrimaryActionRouter.RoutingDecision d2 = routeEnriched("Mình bị đau mỏi cổ vai gáy", s2, chunks, keywords);
        ThinkingModePolicy.ThinkingDecision td2 = ThinkingModePolicy.decide("Mình bị đau mỏi cổ vai gáy", s2, d2, null);
        assertThat(td2.thinkingMode()).isEqualTo(ThinkingMode.FAST_NO_THINK);

        // Scenario 3: First Price Quote after clarify ("Thi thoảng mới đau thôi") -> PRICE_ANCHOR_THINK
        List<FanpageBotRequest.ConversationTurn> h3 = new ArrayList<>(h2);
        h3.add(new FanpageBotRequest.ConversationTurn("user", "Mình bị đau mỏi cổ vai gáy"));
        h3.add(new FanpageBotRequest.ConversationTurn("assistant", "Dạ tình trạng mỏi cổ vai gáy của mình bị lâu chưa hay mới gần đây ạ?"));
        ConversationState s3 = ConversationState.hydrate(PAGE_ID, "u-s3", h3, keywords, chunks);
        PrimaryActionRouter.RoutingDecision d3 = routeEnriched("Thi thoảng mới bị thôi", s3, chunks, keywords);
        ThinkingModePolicy.ThinkingDecision td3 = ThinkingModePolicy.decide("Thi thoảng mới bị thôi", s3, d3, null);
        assertThat(td3.thinkingMode()).isEqualTo(ThinkingMode.PRICE_ANCHOR_THINK);
        assertThat(td3.thinkEnabled()).isTrue();
        assertThat(td3.maxOutputTokens()).isEqualTo(500);

        // Scenario 4: First Price Objection ("Đắt nhỉ") -> OBJECTION_REFRAME_THINK
        List<FanpageBotRequest.ConversationTurn> h4 = new ArrayList<>(h3);
        h4.add(new FanpageBotRequest.ConversationTurn("user", "Thi thoảng mới bị thôi"));
        h4.add(new FanpageBotRequest.ConversationTurn("assistant", "Dạ với mỏi cổ vai gáy, mình tham khảo gói Massage cơ bản 60 phút giá 399k (đã bao gồm tắm xông, ngâm chân thảo dược, ăn nhẹ và cam kết không nhận Tip) ạ."));
        ConversationState s4 = ConversationState.hydrate(PAGE_ID, "u-s4", h4, keywords, chunks);
        PrimaryActionRouter.RoutingDecision d4 = routeEnriched("Đắt nhỉ", s4, chunks, keywords);
        ThinkingModePolicy.ThinkingDecision td4 = ThinkingModePolicy.decide("Đắt nhỉ", s4, d4, null);
        assertThat(td4.thinkingMode()).isEqualTo(ThinkingMode.OBJECTION_REFRAME_THINK);
        assertThat(td4.objectionRootCause()).isEqualTo(ThinkingModePolicy.ObjectionRootCause.GENERAL_PRICE_HESITATION);
        assertThat(td4.previouslyUsedArguments()).contains("full_service_inclusion", "no_tip_policy");

        // Scenario 5: Second Price Objection / Budget Constraint ("Vẫn hơi cao, có gói nào mềm hơn không") -> OBJECTION_REFRAME_THINK (BUDGET_CONSTRAINT)
        List<FanpageBotRequest.ConversationTurn> h5 = new ArrayList<>(h4);
        h5.add(new FanpageBotRequest.ConversationTurn("user", "Đắt nhỉ"));
        h5.add(new FanpageBotRequest.ConversationTurn("assistant", "Dạ mức 399k là trọn gói 60 phút chăm sóc chuyên sâu, mình có thể trải nghiệm thử 1 buổi xem hợp tay nghề kỹ thuật viên không ạ 🥰"));
        ConversationState s5 = ConversationState.hydrate(PAGE_ID, "u-s5", h5, keywords, chunks);
        PrimaryActionRouter.RoutingDecision d5 = routeEnriched("Vẫn hơi cao, sinh viên chưa đủ ngân sách, có gói nào rẻ hơn không", s5, chunks, keywords);
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
        PrimaryActionRouter.RoutingDecision d8 = routeEnriched("Có gói khác k", s8, chunks, keywords);
        ThinkingModePolicy.ThinkingDecision td8 = ThinkingModePolicy.decide("Có gói khác k", s8, d8, null);
        assertThat(td8.thinkingMode()).isEqualTo(ThinkingMode.PRICE_ANCHOR_THINK);

        // Scenario 9: Customer Frustration / Comprehension Repair ("Sao hỏi ăn nhẹ gồm món gì mà cứ nói vòng vo thế") -> REPAIR_FIRST
        PrimaryActionRouter.RoutingDecision d9 = routeEnriched("Sao hỏi ăn nhẹ gồm món gì mà cứ nói vòng vo thế, dở à", s8, chunks, keywords);
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
        PrimaryActionRouter.RoutingDecision dClarify = routeEnriched("K tôi thấy có buffet thực dưỡng, nó là j", s4, chunks, keywords);
        ThinkingModePolicy.ThinkingDecision tdClarify = ThinkingModePolicy.decide("K tôi thấy có buffet thực dưỡng, nó là j", s4, dClarify, null);
        assertThat(tdClarify.thinkingMode()).isEqualTo(ThinkingMode.REPAIR_FIRST);

        // Explicit Opt-Out ("Đừng nhắn nữa") and SAFE_REFUSAL_OR_HANDOFF_IF_REPEATED -> FAST_NO_THINK
        PrimaryActionRouter.RoutingDecision dOptOut = routeEnriched("Đừng nhắn nữa, phiền quá", s4, chunks, keywords);
        ThinkingModePolicy.ThinkingDecision tdOptOut = ThinkingModePolicy.decide("Đừng nhắn nữa, phiền quá", s4, dOptOut, null);
        assertThat(tdOptOut.thinkingMode()).isEqualTo(ThinkingMode.FAST_NO_THINK);
        assertThat(tdOptOut.thinkEnabled()).isFalse();

        // Scenario 10: Service Detail FAQ ("Ăn nhẹ có những món gì em") -> FAST_NO_THINK + Benchmark A/B/C override verification
        PrimaryActionRouter.RoutingDecision d10 = routeEnriched("Ăn nhẹ có những món gì em", s4, chunks, keywords);
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

        // Verify ACI V2 PriceQuoteReadingBudgetScheduler applies dynamic remaining delay and preserves 3-sentence Bubble 1 verbatim
        List<FanpageBotResponse.ReplyBubble> priceAndFollowup = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Dạ với tình trạng căng cứng vùng cổ vai gáy khi ngồi máy tính lâu, mình tham khảo gói Massage cơ bản 60 phút giá 399k ạ. Liệu trình tập trung ấn huyệt làm mềm cơ sâu và giải tỏa chèn ép vùng vai gáy. Giá đã trọn gói ngâm chân thảo dược, ăn nhẹ và không nhận Tip ạ.",
                        0
                ),
                new FanpageBotResponse.ReplyBubble(
                        "Sau 60 phút trị liệu, vùng cổ vai sẽ nhẹ hẳn và dễ ngủ sâu giấc hơn vào buổi tối. Mình muốn đặt lịch ghé trải nghiệm thử 1 buổi vào chiều nay hay tối mai để bên em giữ chỗ yên tĩnh cho mình ạ?",
                        10_000
                )
        );
        List<FanpageBotResponse.ReplyBubble> scheduled = PriceQuoteReadingBudgetScheduler.applyReadingBudgetTypingDelays(
                priceAndFollowup,
                0,
                m1.remainingDelayMs(),
                2_000
        );
        assertThat(scheduled).hasSize(2);
        assertThat(scheduled.get(1).typingDelayMs()).isEqualTo(6_800);
        // 3 sentences in Bubble 1 must NOT be truncated
        assertThat(scheduled.get(0).text()).contains("không nhận Tip ạ.");

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
                scheduled,
                scheduled,
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
        ConversationState state = ConversationState.hydrate(PAGE_ID, "u-prompt", List.of(), List.of("vai gáy"), chunks)
                .enrichWithCurrentMessage("Đắt nhỉ", List.of("vai gáy"));
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

        PrimaryActionRouter.RoutingDecision stage3Dec = routeEnriched("Thôi để lúc khác vậy em", state, chunks, keywords);
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
        AtomicReference<String> nextV2Json = new AtomicReference<>(
                "{\"reply_bubbles\":[{\"text\":\"Dạ chào chị Lan ạ, chị đang muốn tìm hiểu massage trị liệu giảm đau mỏi hay gội đầu dưỡng sinh thư giãn để em gửi gói vừa vặn nhất ạ?\",\"typing_delay_ms\":0}],\"should_handoff\":false}"
        );
        org.mockito.Mockito.when(llmClient.complete(org.mockito.Mockito.anyString(), org.mockito.Mockito.any(ReasoningBudget.class)))
                .thenAnswer(inv -> nextV2Json.get());

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
                null
        );

        String senderId = "u-e2e-split-v324";
        List<FanpageBotRequest.ConversationTurn> history = new ArrayList<>();

        // Part A: Non-price discovery turn MUST NOT set pendingSecondBubble=true
        List<FanpageBotRequest.ConversationTurn> symHistory = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Mình bị mỏi cổ vai gáy"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ mình bị mỏi cổ vai gáy lâu chưa ạ?")
        );
        nextV2Json.set("{\"reply_bubbles\":[{\"text\":\"Dạ ngồi máy tính lâu suốt 2 tuần liền thì vùng cơ cổ vai gáy dễ mỏi nhừ lắm ạ. Mình thích ấn huyệt đằm tay hay thư giãn nhẹ nhàng ạ?\",\"typing_delay_ms\":0}],\"should_handoff\":false}");
        FanpageBotRequest nonPriceRecReq = new FanpageBotRequest(
                PAGE_ID.toString(), senderId + "-nonprice", "Chị Lan",
                "Mình bị mỏi 2 tuần rồi do ngồi máy tính nhiều",
                null, symHistory, List.of(DOC_ID), null, null, null, 5, null, true, null, null
        );
        FanpageBotResponse nonPriceRecResp = botService.handleCustomerMessage(nonPriceRecReq);
        assertThat(nonPriceRecResp.pendingSecondBubble()).isFalse();

        // Part B: ACI V2 Price Quote + 10s Reading Budget + 3-Stage Retention
        nextV2Json.set("{\"reply_bubbles\":[{\"text\":\"Dạ chào chị Lan ạ, chị đang muốn tìm hiểu massage trị liệu giảm đau mỏi hay gội đầu dưỡng sinh thư giãn để em gửi bảng giá sát nhất ạ?\",\"typing_delay_ms\":0}],\"should_handoff\":false}");
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

        // Turn 2: Customer requests 60-minute package -> Bubble 1 quotes 399k (60 phút), Bubble 2 anchors emotion with 10,000ms reading budget & 0 price repeat
        String turn2Msg = "Mình bị đau mỏi vai gáy 2 tuần rồi, muốn làm gói 60 phút";
        nextV2Json.set("{\"reply_bubbles\":[{\"text\":\"Dạ với tình trạng đau mỏi vai gáy 2 tuần nay, chị tham khảo gói Massage cơ bản 60 phút giá 399k trọn gói không nhận Tip ạ.\",\"typing_delay_ms\":0},{\"text\":\"Sau 60 phút ấn huyệt giải cơ sâu kết hợp dùng buffet thực dưỡng miễn phí, vùng cổ vai gáy sẽ nhẹ nhõm và dễ ngủ hơn hẳn ạ. Chị thấy liệu trình 60 phút này hợp với thời gian của mình không ạ?\",\"typing_delay_ms\":10000}],\"should_handoff\":false}");
        FanpageBotResponse turn2Resp = botService.handleCustomerMessage(new FanpageBotRequest(
                PAGE_ID.toString(), senderId, "Chị Lan",
                turn2Msg,
                null, new ArrayList<>(history), List.of(DOC_ID), null, null, null, 5, null, false, null, null
        ));
        assertThat(turn2Resp.replyBubbles()).hasSize(2);
        assertThat(turn2Resp.replyBubbles().get(0).text()).contains("399k").contains("60 phút");
        assertThat(turn2Resp.replyBubbles().get(1).text()).doesNotContain("399k").doesNotContain("399.000");
        assertThat(turn2Resp.replyBubbles().get(1).typingDelayMs()).isGreaterThanOrEqualTo(10_000);
        history.add(new FanpageBotRequest.ConversationTurn("user", turn2Msg));
        history.add(new FanpageBotRequest.ConversationTurn("assistant", turn2Resp.replyText()));

        // Turn 3 (Stage 1 / Nhịp 1): "399k hơi cao nhỉ" -> holds 399k value, invites 1-session trial with 🥰, no premature 239k
        String turn3Msg = "399k hơi cao nhỉ";
        nextV2Json.set("{\"reply_bubbles\":[{\"text\":\"Dạ em hiểu mình cân nhắc chi phí buổi đầu ạ. Mức 399k đã trọn gói 60 phút massage toàn thân kèm buffet thực dưỡng miễn phí và không nhận Tip, chị có thể trải nghiệm thử 1 buổi xem lực tay kỹ thuật viên có hợp ý không nha 🥰\",\"typing_delay_ms\":0}],\"should_handoff\":false}");
        FanpageBotResponse turn3Resp = botService.handleCustomerMessage(new FanpageBotRequest(
                PAGE_ID.toString(), senderId, "Chị Lan",
                turn3Msg,
                null, new ArrayList<>(history), List.of(DOC_ID), null, null, null, 5, null, false, null, null
        ));
        assertThat(turn3Resp.replyText())
                .contains("🥰")
                .doesNotContain("239k");
        history.add(new FanpageBotRequest.ConversationTurn("user", turn3Msg));
        history.add(new FanpageBotRequest.ConversationTurn("assistant", turn3Resp.replyText()));

        // Turn 4 (Stage 2 / Nhịp 2): "Để chị cân nhắc thêm, có gói nào nhẹ chi phí hơn không" -> offers 239k Dưỡng sinh cơ bản 60 phút
        String turn4Msg = "Để chị cân nhắc thêm, có gói nào nhẹ chi phí hơn không";
        nextV2Json.set("{\"reply_bubbles\":[{\"text\":\"Dạ vâng chị, không biết hiện chị đang ưu tiên một buổi thư giãn gọn nhẹ hơn trước đúng không ạ?\",\"typing_delay_ms\":0},{\"text\":\"Nếu muốn nhẹ chi phí hơn, chị tham khảo gói Dưỡng sinh cơ bản 60 phút giá 239k gồm gội thảo dược, rửa mặt và chườm thảo dược cổ vai kèm ăn nhẹ nhé ạ.\",\"typing_delay_ms\":10000}],\"should_handoff\":false}");
        FanpageBotResponse turn4Resp = botService.handleCustomerMessage(new FanpageBotRequest(
                PAGE_ID.toString(), senderId, "Chị Lan",
                turn4Msg,
                null, new ArrayList<>(history), List.of(DOC_ID), null, null, null, 5, null, false, null, null
        ));
        // P3.7-B Global Question Invariant: câu hỏi ở Bubble 1 (khi Bubble 2 còn thông tin) được dời về cuối bubble cuối;
        // Bubble 1 rỗng sau khi dời câu hỏi bị loại bỏ -> 1 bubble, đúng 1 dấu "?" ở cuối.
        assertThat(turn4Resp.replyBubbles()).hasSize(1);
        assertThat(turn4Resp.replyBubbles().get(0).text().chars().filter(c -> c == '?').count()).isEqualTo(1L);
        assertThat(turn4Resp.replyBubbles().get(0).text().strip()).endsWith("?");
        assertThat(turn4Resp.replyBubbles().get(0).text().toLowerCase(java.util.Locale.ROOT))
                .contains("239k")
                .contains("dưỡng sinh cơ bản")
                .contains("60 phút");
        history.add(new FanpageBotRequest.ConversationTurn("user", turn4Msg));
        history.add(new FanpageBotRequest.ConversationTurn("assistant", turn4Resp.replyText()));

        // Turn 5 (Stage 3 / Nhịp 3): "Thôi để lúc khác vậy em" -> graceful exit, 0 questions, 0 package pitch
        String turn5Msg = "Thôi để lúc khác vậy em";
        nextV2Json.set("{\"reply_bubbles\":[{\"text\":\"Dạ vâng chị Lan, chị cứ thong thả sắp xếp thời gian nhé, hôm nào cần thư giãn nhẹ người thì nhắn em hỗ trợ chị nha 🌿\",\"typing_delay_ms\":0}],\"should_handoff\":false}");
        FanpageBotResponse turn5Resp = botService.handleCustomerMessage(new FanpageBotRequest(
                PAGE_ID.toString(), senderId, "Chị Lan",
                turn5Msg,
                null, new ArrayList<>(history), List.of(DOC_ID), null, null, null, 5, null, false, null, null
        ));
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
        long pendingSeq = ThinkingModePolicy.registerIncomingCustomerTurn(PAGE_ID, cancelSender);
        long sentAtNs = System.nanoTime();
        ThinkingModePolicy.markPriceBubble1ShownAndBubble2Pending(PAGE_ID, cancelSender, pendingSeq, sentAtNs);
        // Customer sends a new message BEFORE Bubble 2 is generated
        nextV2Json.set("{\"reply_bubbles\":[{\"text\":\"Dạ bên em mở cửa đón khách hằng ngày, anh ghé khung giờ nào cũng tiện ạ.\",\"typing_delay_ms\":0}],\"should_handoff\":false}");
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
                pendingSeq, sentAtNs
        ));
        assertThat(staleAnchorResp.replyBubbles()).isEmpty();
        assertThat(staleAnchorResp.triggeredHooks()).contains("CANCELLED_SECOND_BUBBLE");
        assertThat(botService.getLastTurnTelemetry().cancelledSecondBubble()).isTrue();

        // Part D: Mid-sale SAFE-NEURO-001 ("tê chân" & "chèn ép rễ thần kinh") preempts sales/objection and hands off safely
        nextV2Json.set("{\"brain_reflection\":{\"priority\":{\"primary\":\"SAFETY\",\"secondary\":[]},\"handoff_request\":{\"requested\":true,\"category\":\"MEDICAL_SAFETY_HANDOFF\",\"reason\":\"Nghi chèn ép rễ thần kinh\"}},\"bubbles\":[\"Dạ với tình trạng tê chân kèm nghi chèn ép rễ thần kinh, để đảm bảo an toàn sức khỏe chị nên thăm khám bác sĩ chuyên khoa y tế trước, đồng thời em xin phép kết nối chuyên viên hỗ trợ trực tiếp cho chị ạ.\"]}");
        FanpageBotResponse neuroResp = botService.handleCustomerMessage(new FanpageBotRequest(
                PAGE_ID.toString(), senderId, "Chị Lan",
                "Nhưng hiện tại chị còn bị tê chân và bác sĩ bảo nghi chèn ép rễ thần kinh thì có làm gói 239k được không?",
                null, new ArrayList<>(history), List.of(DOC_ID), null, null, null, 5, null, true, null, null
        ));
        assertThat(neuroResp.needsHumanHandoff()).isTrue();
        assertThat(neuroResp.replyText())
                .containsAnyOf("bác sĩ", "y tế")
                .doesNotContain("239k")
                .doesNotContain("399k")
                .doesNotContain("?");
    }

    @Test
    @DisplayName("8. Live Container Verification: Re-seeds nang_sen_spa_playbook.yaml and benchmarks Scenarios A/B/C/D on live docmind-api + qwen3:14b when reachable")
    void v324LiveContainerPlaybookSeedAndMultiTurnBenchmarkIfRunning() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                "true".equalsIgnoreCase(System.getenv("DOCMIND_RUN_LIVE_V1_PLAYBOOK_RESEED")),
                "Skipping destructive V1 live playbook reseed during unit tests unless DOCMIND_RUN_LIVE_V1_PLAYBOOK_RESEED=true"
        );
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
        assertThat(ns01.processSummary()).contains("rèm che kín đáo").doesNotContainIgnoringCase("Phòng Vip");
        assertThat(ns02.roomType()).contains("rèm che riêng kín đáo").doesNotContainIgnoringCase("Phòng VIP");
        assertThat(ns02.processSummary()).contains("rèm che kín đáo").doesNotContainIgnoringCase("Phòng VIP");
        assertThat(ns03.roomType()).containsIgnoringCase("Phòng VIP");

        // 2. Verify deliveredValueAngles() is NOT marked when Bubble 1 only is sent (splitPriceBubbles=true) or when Bubble 2 is superseded
        List<FanpageBotRequest.ConversationTurn> hBeforeQuote = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Mình bị đau mỏi cổ vai gáy"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ tình trạng mỏi cổ vai gáy của mình mới gần đây hay đã kéo dài ạ?")
        );
        ConversationState stateBeforeQuote = ConversationState.hydrate(PAGE_ID, "u-val-angle", hBeforeQuote, keywords, List.of(tableChunk))
                .enrichWithCurrentMessage("Mới bị mấy hôm nay thôi, báo giá giúp mình", keywords);
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
        ConversationState stateForStage1Obj = afterDeliveredBubble2.enrichWithCurrentMessage("Giá 399k hơi cao nhỉ", keywords);
        PrimaryActionRouter.RoutingDecision stage1ObjDecision = PrimaryActionRouter.route(
                "Giá 399k hơi cao nhỉ", stateForStage1Obj, List.of(tableChunk), keywords
        );
        List<FanpageBotResponse.ReplyBubble> stage1Bubbles = List.of(
                new FanpageBotResponse.ReplyBubble("Dạ em rất hiểu băn khoăn của mình về chi phí ạ.", 0),
                new FanpageBotResponse.ReplyBubble("Mình có thể trải nghiệm thử 1 buổi trước để cảm nhận trực tiếp nha 🥰", 2000)
        );
        ConversationState afterStage1Obj = ConversationState.updateAfterSend(
                PAGE_ID, "u-val-angle", stateForStage1Obj, "Giá 399k hơi cao nhỉ", stage1ObjDecision, stage1Bubbles, false
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
                // Attempt 1 violates room disambiguation on LLM_COGNITIVE_V2 by falsely claiming Phòng VIP riêng for 399k
                jsonContent = """
                        {"brain_reflection":{"customer_understanding":{"turn_intent":"ASK_PRICE","cost_concern_expressed":false},"priority":{"primary":"PRICE_INFORMATION","secondary":[]},"response_plan":{"strategy":"Direct Answer","tone":"LEVEL_1_NATURAL","depth":"STANDARD","cta_level":"SOFT_CTA","next_action":"SOFT_FEELING_CHECK"}},"bubbles":["Dạ gói Massage cơ bản 60 phút giá 399k có Phòng VIP riêng sang trọng ạ.","Liệu trình chăm sóc êm ái vùng cổ vai gáy, kỹ thuật viên điều chỉnh lực theo cảm nhận của mình ạ."]}
                        """.strip();
            } else {
                // Attempt 2 (ACI V2 Targeted Repair with FAST_NO_THINK) fixes the violation
                jsonContent = """
                        {"brain_reflection":{"customer_understanding":{"turn_intent":"ASK_PRICE","cost_concern_expressed":false},"priority":{"primary":"PRICE_INFORMATION","secondary":[]},"response_plan":{"strategy":"Direct Answer","tone":"LEVEL_1_NATURAL","depth":"STANDARD","cta_level":"SOFT_CTA","next_action":"SOFT_FEELING_CHECK"}},"bubbles":["Dạ với tình trạng mỏi cổ vai gáy mới xuất hiện, mình tham khảo gói Massage cơ bản 60 phút giá 399k trọn gói không nhận tip ạ.","Không gian yên tĩnh, mỗi giường đều có rèm che riêng kín đáo và kỹ thuật viên điều chỉnh lực theo cảm nhận của mình ạ."]}
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
                    null
            );
            service.setAgentBrainService(new AgentBrainService("LLM_COGNITIVE_V2"));
            service.setCognitiveMode(AdaptiveConversationIntelligence.CognitiveMode.LLM_COGNITIVE_V2);
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
                    .contains("/no_think")
                    .contains("<REPAIR_INSTRUCTION>")
                    .contains("FAST_TARGETED_REPAIR_HINT:")
                    .contains("UNGROUNDED_PRIVATE_ROOM:NS01");
            assertThat(resp.replyText())
                    .contains("399k")
                    .contains("rèm che riêng kín đáo")
                    .doesNotContainIgnoringCase("Phòng VIP");
            ThinkingModePolicy.AciTurnTelemetry aciTelemetry = ThinkingModePolicy.getLastAciTurnTelemetry();
            assertThat(aciTelemetry).isNotNull();
            assertThat(aciTelemetry.cognitiveMode()).isEqualTo(AdaptiveConversationIntelligence.CognitiveMode.LLM_COGNITIVE_V2);
            assertThat(aciTelemetry.refereeViolations()).contains("UNGROUNDED_PRIVATE_ROOM:NS01");
            assertThat(resp.turnProfiling()).isNotNull();
            assertThat(resp.turnProfiling().retryCount()).isEqualTo(1);
        } finally {
            repairServer.stop(0);
        }
    }

    @Test
    @DisplayName("17. v3.2.4-FINAL (ACI V2): Preserves natural LLM_LIVE text across 7-turn scenario without template overwrite, plus negative ACI V2 Referee tests")
    void v324FinalSevenTurnScenarioPreservesLlmLiveAndEnforcesNegativeGuardrails() {
        List<ReciprocalRankFusion.FusedChunk> chunks = buildTestChunks();
        List<String> keywords = List.of("cổ", "vai", "gáy", "đầu", "lưng", "chân", "thảo dược", "massage", "dưỡng sinh", "239k", "399k");
        PromptBuilder.PagePersona persona = buildTestPersona();
        List<PackageCatalogService.PackageSummary> catalog = PackageCatalogService.extractCatalog(chunks, keywords);

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
                null
        );
        service.setAgentBrainService(new AgentBrainService("LLM_COGNITIVE_V2"));

        String senderId = "u-v324-final-7turns";
        List<FanpageBotRequest.ConversationTurn> history = new ArrayList<>();

        // Turn 1: "giá" -> ACI V2 natural greeting + clarifying question without hardcoded V1 template prepend
        String t1LlmText = "Dạ chào mình, để em báo đúng gói phù hợp nhất, hiện mình đang muốn tìm hiểu massage trị liệu giảm đau mỏi hay gội đầu dưỡng sinh thư giãn ạ?";
        simulatedLlmJson.set("""
                {"brain_reflection":{"customer_understanding":{"turn_intent":"ASK_PRICE","interaction_permission":"OPEN"},"priority":{"primary":"DISCOVERY","secondary":[]},"response_plan":{"strategy":"Clarify Scope","cta_level":"SOFT_CTA"}},"bubbles":["%s"]}
                """.formatted(t1LlmText).strip());
        FanpageBotResponse r1 = service.handleCustomerMessage(new FanpageBotRequest(
                PAGE_ID.toString(), senderId, "Khách", "giá", null, new ArrayList<>(history), List.of(DOC_ID), 5
        ));
        assertThat(r1.replyBubbles()).hasSize(1);
        assertThat(r1.replyBubbles().get(0).text()).isEqualTo(t1LlmText);
        assertThat(service.getLastTurnTelemetry().generationSource()).isEqualTo("ACI_COGNITIVE_V2_LLM");
        assertThat(service.getLastTurnTelemetry().usedTemplateFallback()).isFalse();
        history.add(new FanpageBotRequest.ConversationTurn("user", "giá"));
        history.add(new FanpageBotRequest.ConversationTurn("assistant", r1.replyText()));

        // Turn 2: "tôi bị đau đầu, mất ngủ, nhức vai gáy" -> Preserves all 3 symptoms, no "căng" assumption, no medical diagnosis
        String t2LlmText = "Dạ vừa đau đầu, mất ngủ lại thêm nhức vai gáy cùng lúc chắc hẳn làm mình mệt mỏi và khó sâu giấc lắm ạ. Tình trạng này mình mới gặp vài hôm nay hay đã kéo dài lâu rồi để em chọn liệu trình sát nhất cho mình ạ?";
        simulatedLlmJson.set("""
                {"brain_reflection":{"customer_understanding":{"turn_intent":"SHARE_SYMPTOM","interaction_permission":"OPEN"},"priority":{"primary":"EMPATHY_AND_DISCOVERY","secondary":[]},"response_plan":{"strategy":"Empathy + Clarify","cta_level":"SOFT_CTA"}},"bubbles":["%s"]}
                """.formatted(t2LlmText).strip());
        FanpageBotResponse r2 = service.handleCustomerMessage(new FanpageBotRequest(
                PAGE_ID.toString(), senderId, "Khách", "tôi bị đau đầu, mất ngủ, nhức vai gáy", null, new ArrayList<>(history), List.of(DOC_ID), 5
        ));
        assertThat(r2.replyBubbles()).hasSize(1);
        assertThat(r2.replyBubbles().get(0).text()).isEqualTo(t2LlmText);
        assertThat(service.getLastTurnTelemetry().generationSource()).isEqualTo("ACI_COGNITIVE_V2_LLM");
        history.add(new FanpageBotRequest.ConversationTurn("user", "tôi bị đau đầu, mất ngủ, nhức vai gáy"));
        history.add(new FanpageBotRequest.ConversationTurn("assistant", r2.replyText()));

        // Turn 3: "Căng gì, tôi bị căng đâu" -> Pushback on assumption, natural repair without premature price quote
        String t3LlmText = "Dạ em xin lỗi mình vì em diễn đạt chưa chuẩn làm mình hiểu nhầm ạ. Em đã ghi nhận đúng tình trạng mình chia sẻ là đau đầu, mất ngủ và nhức vai gáy rồi ạ, mình muốn em gửi luôn thông tin và mức giá gói trị liệu phù hợp cho mình tham khảo nhé ạ?";
        simulatedLlmJson.set("""
                {"brain_reflection":{"customer_understanding":{"turn_intent":"CORRECTION_PUSHBACK","interaction_permission":"GUARDED"},"priority":{"primary":"REPAIR_TRUST","secondary":[]},"response_plan":{"strategy":"Apologize + Align","cta_level":"SOFT_CTA"}},"bubbles":["%s"]}
                """.formatted(t3LlmText).strip());
        FanpageBotResponse r3 = service.handleCustomerMessage(new FanpageBotRequest(
                PAGE_ID.toString(), senderId, "Khách", "Căng gì, tôi bị căng đâu", null, new ArrayList<>(history), List.of(DOC_ID), 5
        ));
        assertThat(r3.replyBubbles()).hasSize(1);
        assertThat(r3.replyBubbles().get(0).text()).isEqualTo(t3LlmText);
        assertThat(service.getLastTurnTelemetry().generationSource()).isEqualTo("ACI_COGNITIVE_V2_LLM");
        history.add(new FanpageBotRequest.ConversationTurn("user", "Căng gì, tôi bị căng đâu"));
        history.add(new FanpageBotRequest.ConversationTurn("assistant", r3.replyText()));

        // Turn 4: "NS linh ta linh tỉnh, vớ vânr" -> Frustration + unresolved price -> apologizes briefly & quotes NS01 60 phút 399k
        String t4b1 = "Dạ em thật sự xin lỗi mình vì hỏi hơi kỹ làm mình sốt ruột ạ! Với tình trạng đau đầu, mất ngủ và nhức vai gáy của mình, gói phù hợp nhất là Massage cơ bản 60 phút giá 399k trọn gói ạ.";
        String t4b2 = "Liệu trình 60 phút tập trung ấn huyệt giải cơ sâu vùng cổ vai gáy và đầu, kết hợp dùng buffet thực dưỡng miễn phí sau buổi làm giúp mình thư giãn nhẹ nhõm hơn. Mình thấy gói 60 phút này hợp với thời gian của mình không ạ?";
        simulatedLlmJson.set("""
                {"brain_reflection":{"customer_understanding":{"turn_intent":"FRUSTRATION_PRICE_DEMAND","interaction_permission":"GUARDED"},"priority":{"primary":"PRICE_INFORMATION","secondary":[]},"response_plan":{"strategy":"Direct Quote","cta_level":"SOFT_CTA"}},"reply_bubbles":[{"text":"%s","typing_delay_ms":0},{"text":"%s","typing_delay_ms":10000}]}
                """.formatted(t4b1, t4b2).strip());
        FanpageBotResponse r4 = service.handleCustomerMessage(new FanpageBotRequest(
                PAGE_ID.toString(), senderId, "Khách", "NS linh ta linh tỉnh, vớ vânr", null, new ArrayList<>(history), List.of(DOC_ID), 5
        ));
        assertThat(r4.replyBubbles()).hasSize(2);
        assertThat(r4.replyBubbles().get(0).text()).isEqualTo(t4b1);
        assertThat(r4.replyBubbles().get(1).text()).isEqualTo(t4b2);
        assertThat(service.getLastTurnTelemetry().generationSource()).isEqualTo("ACI_COGNITIVE_V2_LLM");
        history.add(new FanpageBotRequest.ConversationTurn("user", "NS linh ta linh tỉnh, vớ vânr"));
        history.add(new FanpageBotRequest.ConversationTurn("assistant", r4.replyText()));

        // Turn 5: "Đắt" -> Stage 1 objection, holds 399k NS01, natural LLM text preserved WITHOUT appending " 🥰" and WITHOUT higher-tier sauna claims
        String t5LlmText = "Dạ em rất hiểu mình cân nhắc về chi phí ạ. Mức 399k cho 60 phút bên em đã bao gồm trọn gói massage trị liệu toàn thân ấn huyệt chuyên sâu vùng đầu vai gáy, dùng bữa nhẹ thực dưỡng miễn phí sau buổi làm và hoàn toàn không nhận tiền Tip, nên mình có thể yên tâm trải nghiệm thử 1 buổi xem cơ thể nhẹ nhõm thế nào nhé ạ.";
        simulatedLlmJson.set("""
                {"brain_reflection":{"customer_understanding":{"turn_intent":"PRICE_OBJECTION","cost_concern_expressed":true},"priority":{"primary":"VALUE_REFRAME","secondary":[]},"response_plan":{"strategy":"Reframe Value","cta_level":"SOFT_CTA"}},"bubbles":["%s"]}
                """.formatted(t5LlmText).strip());
        FanpageBotResponse r5 = service.handleCustomerMessage(new FanpageBotRequest(
                PAGE_ID.toString(), senderId, "Khách", "Đắt", null, new ArrayList<>(history), List.of(DOC_ID), 5
        ));
        assertThat(r5.replyBubbles()).hasSize(1);
        assertThat(r5.replyBubbles().get(0).text()).isEqualTo(t5LlmText);
        assertThat(service.getLastTurnTelemetry().generationSource()).isEqualTo("ACI_COGNITIVE_V2_LLM");
        history.add(new FanpageBotRequest.ConversationTurn("user", "Đắt"));
        history.add(new FanpageBotRequest.ConversationTurn("assistant", r5.replyText()));

        // Turn 6: "để tôi cân nhắc thêm" -> Downsell option to DS02 Dưỡng sinh cơ bản 60 phút 239k
        String t6b1 = "Dạ vâng, em hoàn toàn tôn trọng việc mình cân nhắc thêm ạ.";
        String t6b2 = "Nếu mình muốn thử một lựa chọn nhẹ nhàng và tiết kiệm hơn trước, bên em có gói Dưỡng sinh cơ bản 60 phút giá 239k (gồm gội thảo dược, massage mặt và chườm thảo dược cổ vai kèm ăn nhẹ) cũng giúp thư giãn vùng đầu dễ chịu lắm ạ.";
        simulatedLlmJson.set("""
                {"brain_reflection":{"customer_understanding":{"turn_intent":"HESITATION_DEFER","interaction_permission":"LOW"},"priority":{"primary":"RESPECT_AUTONOMY","secondary":[]},"response_plan":{"strategy":"Step Back + Soft Option","cta_level":"NO_CTA"}},"reply_bubbles":[{"text":"%s","typing_delay_ms":0},{"text":"%s","typing_delay_ms":3000}]}
                """.formatted(t6b1, t6b2).strip());
        FanpageBotResponse r6 = service.handleCustomerMessage(new FanpageBotRequest(
                PAGE_ID.toString(), senderId, "Khách", "để tôi cân nhắc thêm", null, new ArrayList<>(history), List.of(DOC_ID), 5
        ));
        assertThat(r6.replyBubbles()).hasSize(2);
        assertThat(r6.replyBubbles().get(0).text()).isEqualTo(t6b1);
        assertThat(r6.replyBubbles().get(1).text()).isEqualTo(t6b2);
        assertThat(service.getLastTurnTelemetry().generationSource()).isEqualTo("ACI_COGNITIVE_V2_LLM");
        history.add(new FanpageBotRequest.ConversationTurn("user", "để tôi cân nhắc thêm"));
        history.add(new FanpageBotRequest.ConversationTurn("assistant", r6.replyText()));

        // Turn 7: "thôi cũng được, thử gói 239k xem" -> Confirm booking on DS02 60 phút 239k (must NOT repeat rejected 399k!)
        String t7LlmText = "Dạ vâng ạ, vậy em xin phép ghi nhận mình chọn gói Dưỡng sinh cơ bản 60 phút giá 239k nhé ạ. Mình dự kiến ghé cơ sở bên em vào khung giờ nào và cho em xin Số điện thoại để lễ tân giữ chỗ chu đáo cho mình nha?";
        simulatedLlmJson.set("""
                {"brain_reflection":{"customer_understanding":{"turn_intent":"BOOKING_INTENT","interaction_permission":"OPEN"},"priority":{"primary":"BOOKING_COLLECTION","secondary":[]},"response_plan":{"strategy":"Confirm Selected Package","cta_level":"BOOKING_CTA"}},"bubbles":["%s"]}
                """.formatted(t7LlmText).strip());
        FanpageBotResponse r7 = service.handleCustomerMessage(new FanpageBotRequest(
                PAGE_ID.toString(), senderId, "Khách", "thôi cũng được, thử gói 239k xem", null, new ArrayList<>(history), List.of(DOC_ID), 5
        ));
        assertThat(r7.replyBubbles()).hasSize(1);
        assertThat(r7.replyBubbles().get(0).text()).isEqualTo(t7LlmText);
        assertThat(r7.replyText()).contains("239k").doesNotContain("399k");
        assertThat(service.getLastTurnTelemetry().generationSource()).isEqualTo("ACI_COGNITIVE_V2_LLM");

        // Negative ACI V2 Referee Checks:
        PackageCatalogService.PackageSummary ns01Pkg = catalog.stream()
                .filter(p -> "NS01".equals(p.code())).findFirst().orElseThrow();
        PackageCatalogService.PackageSummary ds02Pkg = catalog.stream()
                .filter(p -> "DS02".equals(p.code())).findFirst().orElseThrow();

        // (a) Repeating excluded/rejected 399k after narrowing to DS02 239k is flagged by V2 Referee
        List<String> rejectedRepeatViolations = CleanAgentOutputRefereeAndSanitizer.checkAciV2TruthAuthorityAndSafety(
                List.of(new FanpageBotResponse.ReplyBubble("Dạ em chốt cho mình gói Massage cơ bản 60 phút giá 399k nhé ạ.", 0)),
                List.of(ds02Pkg),
                List.of(ds02Pkg),
                List.of(ns01Pkg),
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                ConversationState.StructuredBookingState.none(),
                List.of(),
                persona.resolvedConfig(),
                "thôi cũng được, thử gói 239k xem",
                false,
                null,
                null,
                AdaptiveConversationIntelligence.AdaptiveCognitiveReflection.empty(),
                chunks,
                persona.name(),
                null,
                history
        );
        assertThat(rejectedRepeatViolations).contains("UNGROUNDED_OR_EXCLUDED_PRICE:399k");

        // (b) Extreme medical cure claim ("cam kết khỏi hẳn đau đầu mất ngủ ngay buổi đầu") is flagged by V2 Referee
        assertThat(CleanAgentOutputRefereeAndSanitizer.detectExtremeMedicalCureClaim(
                "Dạ bên em cam kết khỏi hẳn đau đầu mất ngủ dứt điểm ngay sau 1 buổi ạ."))
                .isTrue();

        // (c) False higher-tier benefit claim ("xông hơi" / "ngâm bồn thảo dược") on NS01 399k is flagged by V2 Referee
        List<String> ungroundedPerkViolations = CleanAgentOutputRefereeAndSanitizer.checkAciV2TruthAuthorityAndSafety(
                List.of(new FanpageBotResponse.ReplyBubble("Dạ gói Massage cơ bản 60 phút giá 399k đã bao gồm xông hơi và ngâm bồn thảo dược ấm ạ.", 0)),
                catalog,
                catalog,
                List.of(),
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                ConversationState.StructuredBookingState.none(),
                List.of(),
                persona.resolvedConfig(),
                "Đắt",
                false,
                null,
                null,
                AdaptiveConversationIntelligence.AdaptiveCognitiveReflection.empty(),
                chunks,
                persona.name(),
                null,
                history
        );
        assertThat(ungroundedPerkViolations)
                .anyMatch(v -> v.startsWith("UNGROUNDED_HERBAL_BATH_AMENITY:NS01") || v.startsWith("UNGROUNDED_STEAM_AMENITY:NS01"));

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

        // (e) Wrong opener pronoun ("Em đang quan tâm dịch vụ nào ạ?") is flagged & repaired to configured customerPronoun ("mình")
        String repairedPronounText = CleanAgentOutputRefereeAndSanitizer.repairWrongPronounOpenerInPlace(
                "Em đang quan tâm đến dịch vụ nào của Nàng Sen Spa để em báo giá ạ?",
                "em",
                "mình"
        );
        assertThat(repairedPronounText)
                .doesNotStartWith("Em đang quan tâm")
                .containsIgnoringCase("mình");

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
                PAGE_ID, senderId, "conv-session-1", false, "Giá", List.of(), keywords, chunks)
                .enrichWithCurrentMessage("Giá", keywords);
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
                PAGE_ID, senderId, "conv-session-1", false, "tôi bị đau đầu, mất ngủ, nhức vai gáy", List.of(), keywords, chunks)
                .enrichWithCurrentMessage("tôi bị đau đầu, mất ngủ, nhức vai gáy", keywords);
        assertThat(s1Turn2NoHistory.greetingSent()).isTrue();
        assertThat(s1Turn2NoHistory.priceRequestedInSession()).isTrue();
        PrimaryActionRouter.RoutingDecision d1Turn2 = PrimaryActionRouter.route(
                "tôi bị đau đầu, mất ngủ, nhức vai gáy", s1Turn2NoHistory, chunks, keywords);
        assertThat(d1Turn2.openingRequired()).isFalse();

        // 3. Scenario 5: Retry request on "conv-session-1" -> greetingSent remains true, never duplicates opening bubbles
        ConversationState s1Retry = ConversationState.hydrateForTurn(
                PAGE_ID, senderId, "conv-session-1", false, "Giá", List.of(), keywords, chunks)
                .enrichWithCurrentMessage("Giá", keywords);
        assertThat(s1Retry.greetingSent()).isTrue();
        PrimaryActionRouter.RoutingDecision d1Retry = PrimaryActionRouter.route("Giá", s1Retry, chunks, keywords);
        assertThat(d1Retry.openingRequired()).isFalse();

        // 4. Scenario 4: Two concurrent sessions of the SAME senderId ("conv-session-1" vs "conv-session-2") -> isolated states!
        ConversationState s2Turn1 = ConversationState.hydrateForTurn(
                PAGE_ID, senderId, "conv-session-2", false, "Xin chào", List.of(), keywords, chunks)
                .enrichWithCurrentMessage("Xin chào", keywords);
        assertThat(s2Turn1.greetingSent()).isFalse();
        assertThat(s2Turn1.priceRequestedInSession()).isFalse();
        assertThat(ConversationState.getSessionState(PAGE_ID, senderId, "conv-session-1").priceRequestedInSession()).isTrue();

        // 5. Scenario 3: UI Reset (resetSession = true) on "conv-session-1" -> resets session and triggers 3-bubble opening again
        ConversationState s1AfterReset = ConversationState.hydrateForTurn(
                PAGE_ID, senderId, "conv-session-1", true, "Giá", List.of(), keywords, chunks)
                .enrichWithCurrentMessage("Giá", keywords);
        assertThat(s1AfterReset.greetingSent()).isFalse();
        PrimaryActionRouter.RoutingDecision d1AfterReset = PrimaryActionRouter.route("Giá", s1AfterReset, chunks, keywords);
        assertThat(d1AfterReset.openingRequired()).isTrue();

        // 6. Section 3.3: DS02 (239k) must NOT claim "buffet thực dưỡng" (V2 Referee UNGROUNDED_BUFFET_AMENITY:DS02)
        List<PackageCatalogService.PackageSummary> catalog = PackageCatalogService.extractCatalog(chunks, keywords);
        List<String> ds02FalseBuffetViolations = CleanAgentOutputRefereeAndSanitizer.checkAciV2TruthAuthorityAndSafety(
                List.of(new FanpageBotResponse.ReplyBubble("Dạ bên em có gói Dưỡng sinh cơ bản 60 phút giá 239k bao gồm buffet thực dưỡng miễn phí ạ.", 0)),
                catalog,
                catalog,
                List.of(),
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                ConversationState.StructuredBookingState.none(),
                List.of(),
                persona.resolvedConfig(),
                "để tôi cân nhắc thêm",
                false,
                null,
                null,
                AdaptiveConversationIntelligence.AdaptiveCognitiveReflection.empty(),
                chunks,
                persona.name(),
                null,
                List.of()
        );
        assertThat(ds02FalseBuffetViolations).contains("UNGROUNDED_BUFFET_AMENITY:DS02");

        // 7. Section 4.2: Wrong pronoun opener ("Chị đang gặp phải...") when configured customerPronoun is "mình" is repaired in-place
        String normalizedPronounText = CleanAgentOutputRefereeAndSanitizer.repairWrongPronounOpenerInPlace(
                "Chị đang gặp phải tình trạng đau đầu khiến mệt mỏi hẳn ạ.",
                "em",
                "mình"
        );
        assertThat(normalizedPronounText)
                .doesNotStartWith("Chị ")
                .containsIgnoringCase("mình");
    }
}
