package com.example.docmind.service.bot;

import com.docmind.guardrail.HonorificConsistencyHook;
import com.example.docmind.dto.LearnedRuleDto;
import com.example.docmind.dto.PagePersonaConfig;
import com.example.docmind.dto.bot.FanpageBotRequest.ConversationTurn;
import com.example.docmind.dto.bot.FanpageBotResponse.ReplyBubble;
import com.example.docmind.repository.RetrievedChunk;
import com.example.docmind.service.retrieval.PromptBuilder;
import com.example.docmind.service.retrieval.ReciprocalRankFusion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DocMind v3.3.4 — Architectural regression suite (Tests A..Q) from the v3.3.4 master directive.
 *
 * <p>Assertions are kept exactly as specified. Only API names that do not exist in the real source
 * at commit {@code 80e386a} were mapped to their real equivalents (public signatures are frozen by
 * the directive, so the test adapts to the source, not the other way around):
 * <ul>
 *   <li>{@code RoutingDecision.detectedCustomerIntent()} -> {@code RoutingDecision.detectedIntent()}</li>
 *   <li>{@code ThinkingMode.NO_THINK_GROUNDED} -> {@code ThinkingMode.FAST_NO_THINK} (existing grounded no-think mode)</li>
 *   <li>{@code ThinkingMode.EMOTIONAL_WARM_THINK} -> newly added enum constant</li>
 *   <li>{@code ThinkingMode} / {@code PromptBudgetGuard} live in {@code com.example.docmind.service.bot}</li>
 *   <li>{@code LearnedRuleDto} has no 10-arg constructor; rules are built via the canonical constructor
 *       with the same rule_code / category / text / always_on / handoff_policy values</li>
 * </ul>
 */
class DocmindV330ArchitecturalRegressionTest {

    private static final UUID PAGE_ID = UUID.fromString("b23cd382-4f7a-4829-8c54-1d410da14bf8");
    private List<ReciprocalRankFusion.FusedChunk> activeCatalogChunks;
    private PromptBuilder.PagePersona nangSenPersona;

    @BeforeEach
    void setUp() {
        ConversationState.clearSessionCache();
        String catalogTable = """
                | Mã gói | Tên gói | Giá | Tổng thời gian | Bao gồm | Không gian | Điểm nổi bật | Phù hợp |
                | --- | --- | --- | --- | --- | --- | --- | --- |
                | NS01 | Massage cơ bản | 399.000VNĐ | 60 phút | Massage toàn thân, tập trung chăm sóc các vùng cơ thể đang đau mỏi + Buffet thực dưỡng | Phòng chung (có rèm riêng tư) | Trọn gói không nhận Tip, ăn nhẹ sau liệu trình | Khách đau mỏi lưng, cổ vai, chân tay |
                | DS02 | Dưỡng sinh cơ bản | 239.000VNĐ | 60 phút | Gội đầu thảo dược + massage cổ vai | Khu dưỡng sinh có rèm che riêng | Thư giãn nhẹ nhàng, trọn gói không nhận Tip | Khách muốn thư giãn đầu, cổ vai |
                """;
        RetrievedChunk rc = new RetrievedChunk(
                UUID.randomUUID(), UUID.randomUUID(),
                List.of("BẢNG GIÁ DỊCH VỤ", "Cơ sở Hà Nội"),
                List.of(), 1, new double[]{0, 0, 0, 0}, catalogTable, 0.95
        );
        activeCatalogChunks = List.of(new ReciprocalRankFusion.FusedChunk(rc, 0.95));
        PagePersonaConfig cfg = new PagePersonaConfig(
                "spa_wellness", "Linh", "em", "mình",
                "Ấm áp, tự nhiên, lắng nghe", "LOW",
                List.of("cam kết khỏi 100%", "chữa khỏi"),
                List.of("massage", "đau lưng", "mỏi gối", "tê tay", "gội đầu"),
                null, null,
                List.of(
                        "Dạ chào mình, em là Linh của Nàng Sen Spa ạ.",
                        "Bên em chuyên massage thư giãn và chăm sóc thảo dược nhẹ nhàng, chỉn chu ạ."
                ),
                10000, null
        );
        nangSenPersona = new PromptBuilder.PagePersona("Nàng Sen Spa", "Spa", "Khách thư giãn", "", cfg);
    }

    /** Canonical-constructor helper mirroring the directive's 10-arg rule shape. */
    private static LearnedRuleDto rule(String ruleCode, String category, String text, int priority,
                                       boolean alwaysOn, String handoffPolicy) {
        return new LearnedRuleDto(
                UUID.randomUUID(), category, null, null, text, List.of(), true, PAGE_ID.toString(),
                0.85, 0, 0, 0, 1, null, null, null, null, null, "DOM", "", null, alwaysOn,
                "APPROVED", null, ruleCode, null, null, null, priority, null, null,
                "must_use_kb_document_context", handoffPolicy, "MANUAL"
        );
    }

    @Test
    @DisplayName("TEST A — OPENING & GRAMMAR SAFETY: Cắt câu chào lặp ở đầu câu nhưng KHÔNG làm hỏng câu không có lời chào")
    void testA_OpeningSingleGreetingAndGrammarSafety() {
        String stripped = FanpageBotService.stripRedundantGreetingPrefixPreservingGrammar(
                "Em chào mình nha, hiện bên em có nhiều mức giá theo từng gói dịch vụ và thời lượng ạ. Mình đang quan tâm massage hay gội đầu dưỡng sinh ạ?",
                "mình"
        );
        assertFalse(stripped.toLowerCase().contains("chào mình nha"));
        assertTrue(stripped.toLowerCase().contains("mức giá"));

        String normalSentence = "Dạ gói 60 phút bên em rất phù hợp với tình trạng của mình ạ.";
        String preserved = FanpageBotService.stripRedundantGreetingPrefixPreservingGrammar(normalSentence, "mình");
        assertEquals(normalSentence, preserved);
    }

    @Test
    @DisplayName("TEST B & C — CONDITION FOLLOW-UP & 2 BUBBLES (0ms + 10000ms): 'Thi thoảng bị'")
    void testBAndC_ConditionFollowUpAndNoDuplicateQuote() {
        List<ConversationTurn> history = List.of(
                new ConversationTurn("user", "Giá"),
                new ConversationTurn("assistant", "Dạ chào mình, em là Linh của Nàng Sen Spa ạ.\n\nDạ mình đang quan tâm massage hay gội đầu dưỡng sinh để em gửi đúng mức giá ạ?"),
                new ConversationTurn("user", "Tôi bị đau lưng, mỏi gối tê tay"),
                new ConversationTurn("assistant", "Dạ vừa bị đau lưng, mỏi gối và tê tay cùng lúc chắc làm mình mệt mỏi lắm ạ. Tình trạng này mình mới gặp gần đây hay đã kéo dài bao lâu rồi ạ?")
        );
        ConversationState state = ConversationState.hydrateForTurn(
                PAGE_ID, "user-b", "conv-b", false, "Thi thoảng bị", history,
                nangSenPersona.resolvedConfig().safeDomainKeywords(), activeCatalogChunks
        );
        PrimaryActionRouter.RoutingDecision decision = PrimaryActionRouter.route(
                "Thi thoảng bị", state, activeCatalogChunks, nangSenPersona.resolvedConfig().safeDomainKeywords()
        );
        assertEquals(PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE, decision.effectiveResponseAction());
        List<ReplyBubble> bubbles = PrimaryActionRouter.executeActionBubbles(
                decision, "Thi thoảng bị", state, "mình", nangSenPersona, activeCatalogChunks, null
        );
        assertEquals(2, bubbles.size());
        assertFalse(bubbles.get(0).text().toLowerCase().contains("theo bảng giá hiện tại ạ"));
        assertFalse(bubbles.get(1).text().toLowerCase().startsWith("dạ em hiểu rồi ạ"));
        assertFalse(bubbles.get(1).text().toLowerCase().contains("399k"));
        assertEquals(10000, bubbles.get(1).typingDelayMs());
    }

    @Test
    @DisplayName("TEST D — CORRECTION/JOKE: Nhận diện cả câu gốc lẫn biến thể trêu câu chữ")
    void testD_WordingCorrectionOrJokeAndVariants() {
        assertTrue(SentimentIntentLayer.isWordingOrExpressionFeedback("Đau lưng chứ đau lòng đâu mà nhẹ nhõm"));
        assertTrue(SentimentIntentLayer.isWordingOrExpressionFeedback("Đau lưng mỏi gối chứ có phải đau lòng đâu mà nhẹ nhõm"));

        List<ConversationTurn> history = List.of(
                new ConversationTurn("user", "Tôi bị đau lưng, mỏi gối tê tay, xin giá"),
                new ConversationTurn("assistant", "Dạ với tình trạng của mình, bên em có gói massage cơ bản 60 phút (chi phí 399k) giúp mình cảm thấy nhẹ nhõm hơn ạ.")
        );
        ConversationState state = ConversationState.hydrateForTurn(
                PAGE_ID, "user-d", "conv-d", false, "Đau lưng chứ đau lòng đâu mà nhẹ nhõm", history,
                nangSenPersona.resolvedConfig().safeDomainKeywords(), activeCatalogChunks
        );
        PrimaryActionRouter.RoutingDecision decision = PrimaryActionRouter.route(
                "Đau lưng chứ đau lòng đâu mà nhẹ nhõm", state, activeCatalogChunks, nangSenPersona.resolvedConfig().safeDomainKeywords()
        );
        assertNotEquals("AMBIGUOUS_CONFIRM_ONCE", decision.detectedIntent());
        assertEquals("WORDING_FEEDBACK_REPAIR", decision.detectedIntent());
    }

    @Test
    @DisplayName("TEST E — PRICE OBJECTION 1: 'Đắt thế' sinh 2 bong bóng (0ms và 2000ms), Bubble 1 không lặp giá 399k")
    void testE_PriceObjectionFirstTime() {
        List<ConversationTurn> history = List.of(
                new ConversationTurn("user", "Tôi bị đau lưng, mỏi gối tê tay, xin giá"),
                new ConversationTurn("assistant", "Dạ với tình trạng của mình, bên em có gói massage cơ bản 60 phút (chi phí 399k) ạ.")
        );
        ConversationState state = ConversationState.hydrateForTurn(
                PAGE_ID, "user-e", "conv-e", false, "Đắt thế", history,
                nangSenPersona.resolvedConfig().safeDomainKeywords(), activeCatalogChunks
        );
        PrimaryActionRouter.RoutingDecision decision = PrimaryActionRouter.route(
                "Đắt thế", state, activeCatalogChunks, nangSenPersona.resolvedConfig().safeDomainKeywords()
        );
        assertEquals(PrimaryActionRouter.PrimaryAction.HANDLE_PRICE_OBJECTION_FIRST_TIME, decision.effectiveResponseAction());
        List<ReplyBubble> bubbles = PrimaryActionRouter.executeActionBubbles(
                decision, "Đắt thế", state, "mình", nangSenPersona, activeCatalogChunks, null
        );
        assertEquals(2, bubbles.size());
        assertFalse(bubbles.get(0).text().toLowerCase().contains("399"));
        assertEquals(0, bubbles.get(0).typingDelayMs());
        assertEquals(2000, bubbles.get(1).typingDelayMs());
    }

    @Test
    @DisplayName("TEST F & G — DURATION & BENEFITS: Giữ nguyên gói 60p từ state catalog ngay cả khi fusedChunks lượt FAQ rỗng")
    void testFAndG_DurationAndBenefitsFromActiveCatalog() {
        List<ConversationTurn> history = List.of(
                new ConversationTurn("user", "Tôi bị đau lưng, mỏi gối tê tay, xin giá"),
                new ConversationTurn("assistant", "Dạ với tình trạng của mình, bên em có gói massage cơ bản 60 phút (chi phí 399k) ạ.")
        );
        ConversationState state = ConversationState.hydrateForTurn(
                PAGE_ID, "user-fg", "conv-fg", false, "Thời lượng j nữa nhỉ tưởng 60p", history,
                nangSenPersona.resolvedConfig().safeDomainKeywords(), activeCatalogChunks
        );
        PrimaryActionRouter.RoutingDecision durDecision = PrimaryActionRouter.route(
                "Thời lượng j nữa nhỉ tưởng 60p", state, List.of(), nangSenPersona.resolvedConfig().safeDomainKeywords()
        );
        assertEquals(PrimaryActionRouter.PrimaryAction.SERVICE_DETAIL_AND_FAQ_QA, durDecision.effectiveResponseAction());
        List<ReplyBubble> durBubbles = PrimaryActionRouter.executeActionBubbles(
                durDecision, "Thời lượng j nữa nhỉ tưởng 60p", state, "mình", nangSenPersona, List.of(), null
        );
        assertTrue(durBubbles.get(0).text().contains("60 phút"));
    }

    @Test
    @DisplayName("TEST I — STRONG REJECTION: 'Tụt hứng tghooi rẹp' dừng bán hàng bằng 1 bubble, 0 câu hỏi, TẮT thinking")
    void testI_StrongRejectionGracefulExit() {
        assertTrue(SentimentIntentLayer.isStrongRejection("Tụt hứng tghooi rẹp"));
        assertFalse(SentimentIntentLayer.isNegativeOrFrustratedMood("Tụt hứng tghooi rẹp"),
                "Từ chối dứt khoát không được nhầm sang luồng xoa dịu tiếp tục bán hàng");
        ConversationState state = ConversationState.initial();
        PrimaryActionRouter.RoutingDecision decision = PrimaryActionRouter.route(
                "Tụt hứng tghooi rẹp", state, activeCatalogChunks, List.of()
        );
        assertEquals(ThinkingMode.FAST_NO_THINK,
                FanpageBotService.resolveSelectiveThinkingMode(decision, state, "Tụt hứng tghooi rẹp"));
        List<ReplyBubble> bubbles = PrimaryActionRouter.executeActionBubbles(
                decision, "Tụt hứng tghooi rẹp", state, "mình", nangSenPersona, activeCatalogChunks, null
        );
        assertEquals(1, bubbles.size());
        assertFalse(bubbles.get(0).text().contains("?"));
    }

    @Test
    @DisplayName("TEST J — SAFETY GATE: Triệu chứng thần kinh cấp tính phải chặn luồng bán hàng và handoff")
    void testJ_SafetyGateBlocksSalesFlow() {
        String highRiskInput = "Tôi bị đau lưng kèm tê tay lan xuống bàn tay và mất cảm giác";
        assertTrue(SentimentIntentLayer.hasNeurologicalRisk(highRiskInput, ConversationState.initial()));
        PrimaryActionRouter.RoutingDecision decision = PrimaryActionRouter.route(
                highRiskInput, ConversationState.initial(), activeCatalogChunks, List.of()
        );
        assertEquals(PrimaryActionRouter.PrimaryAction.HARD_HANDOFF_SAFETY, decision.effectiveResponseAction());
    }

    @Test
    @DisplayName("TEST K — ROOM SPACE GROUNDING: Gói NS01 phải giữ rõ chữ 'Phòng chung', không biến thành phòng VIP")
    void testK_SharedRoomLabelPreserved() {
        List<PackageCatalogService.PackageSummary> pkgs = PackageCatalogService.extractCatalog(activeCatalogChunks, List.of());
        PackageCatalogService.PackageSummary ns01 = pkgs.stream()
                .filter(p -> "NS01".equalsIgnoreCase(p.code()))
                .findFirst()
                .orElseThrow();
        assertTrue(ns01.roomType().toLowerCase().contains("phòng chung"));
    }

    @Test
    @DisplayName("TEST L — PROMPT BUDGET ENFORCEMENT: Tự động hạ alwaysOn=false cho rule PB-SPA-*, tổng alwaysOn <= 1200 ký tự")
    void testL_PromptBudgetEnforcedImmutably() {
        LearnedRuleDto safeRule = rule(
                "SAFE-NEURO-001", "SAFETY_MEDICAL",
                "Khi khách có dấu hiệu chèn ép rễ thần kinh cấp tính, dừng báo giá và chuyển nhân viên.",
                100, true, "mandatory_human_handoff"
        );
        LearnedRuleDto bloatedPlaybookRule = rule(
                "PB-SPA-PRICE-002", "PRICING",
                "Quy tắc báo giá chi tiết...".repeat(40),
                80, true, "none"
        );
        List<LearnedRuleDto> enforced = PromptBudgetGuard.enforceAlwaysOnBudget(List.of(safeRule, bloatedPlaybookRule));
        long alwaysOnCount = enforced.stream().filter(LearnedRuleDto::alwaysOn).count();
        assertEquals(1L, alwaysOnCount);
    }

    @Test
    @DisplayName("TEST M — HONORIFIC HOOK: Bảo toàn câu đúng vai ('Em đang...', 'Mình có thể...')")
    void testM_HonorificHookProtectedPhrases() {
        String validBotEm = "Em đang kiểm tra giúp mình ạ";
        String validCustomerMinh = "Mình có thể tham khảo gói 60 phút ạ";
        assertEquals(validBotEm, HonorificConsistencyHook.repairIfReversed(validBotEm, "mình"));
        assertEquals(validCustomerMinh, HonorificConsistencyHook.repairIfReversed(validCustomerMinh, "mình"));
    }

    @Test
    @DisplayName("TEST R — SPLIT BUBBLE 2 (phát hiện khi chạy live): Anchor bubble riêng không được mở đầu 'Dạ em hiểu rồi ạ' hay gọi lại tên gói đã báo")
    void testR_SplitAnchorBubbleCrossBubbleRedundancy() {
        List<ConversationTurn> history = List.of(
                new ConversationTurn("user", "Tôi bị đau lưng, mỏi vai, xin giá"),
                new ConversationTurn("assistant", "Dạ với tình trạng đau lưng và mỏi vai của mình, bên em có gói massage cơ bản 60 phút (chi phí 399k) rất phù hợp ạ.")
        );
        ConversationState state = ConversationState.hydrateForTurn(
                PAGE_ID, "user-r", "conv-r", false, "thi thoảng bị", history,
                nangSenPersona.resolvedConfig().safeDomainKeywords(), activeCatalogChunks
        );
        assertNotNull(state.lastOfferedPackageName(), "State phải nhớ tên gói vừa báo ở Bubble 1");

        List<ReplyBubble> badAnchor = List.of(new ReplyBubble(
                "Dạ em hiểu rồi ạ. Mình thấy gói " + state.lastOfferedPackageName() + " 60 phút đã hợp ý mình chưa ạ?", 10000));
        com.docmind.guardrail.ReplyContractValidator.ValidationResult bad = com.docmind.guardrail.ReplyContractValidator.validateAndEnforce(
                badAnchor, state, "thi thoảng bị", PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE,
                false, 10000, ThinkingMode.PRICE_ANCHOR_THINK, 4000L);
        assertTrue(bad.errors().contains("CROSS_BUBBLE_REDUNDANCY_OR_PRICE_REPEAT"));

        List<ReplyBubble> goodAnchor = List.of(new ReplyBubble(
                "Sau buổi làm, vùng lưng vai được thả lỏng dần và người nhẹ hẳn ạ. Mình thấy gói 60 phút này đã hợp ý chưa ạ?", 10000));
        com.docmind.guardrail.ReplyContractValidator.ValidationResult good = com.docmind.guardrail.ReplyContractValidator.validateAndEnforce(
                goodAnchor, state, "thi thoảng bị", PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE,
                false, 10000, ThinkingMode.PRICE_ANCHOR_THINK, 4000L);
        assertFalse(good.errors().contains("CROSS_BUBBLE_REDUNDANCY_OR_PRICE_REPEAT"));
    }

    @Test
    @DisplayName("TEST P — SELECTIVE THINKING: Bật Thinking khi Khách Tiêu Cực/Tức Giận để xoa dịu cảm xúc")
    void testP_NegativeOrAngryCustomerTriggersThinking() {
        List<ConversationTurn> history = List.of(
                new ConversationTurn("user", "Tôi bị đau lưng, mỏi gối tê tay"),
                new ConversationTurn("assistant", "Dạ với tình trạng của mình, bên em có gói massage cơ bản 60 phút (chi phí 399k) ạ.")
        );
        ConversationState state = ConversationState.hydrateForTurn(
                PAGE_ID, "u-angry", "c-angry", false, "Trả lời linh tinh cái quái gì đấy", history,
                List.of("massage", "đau lưng"), activeCatalogChunks
        );
        assertTrue(SentimentIntentLayer.isNegativeOrFrustratedMood("Trả lời linh tinh cái quái gì đấy"));
        assertTrue(SentimentIntentLayer.isNegativeOrFrustratedMood("Sao em hỏi nhiều và vòng vo thế nhỉ"));

        PrimaryActionRouter.RoutingDecision dAngry = PrimaryActionRouter.route(
                "Trả lời linh tinh cái quái gì đấy", state, activeCatalogChunks, List.of("massage", "đau lưng")
        );
        assertEquals(ThinkingMode.EMOTIONAL_WARM_THINK,
                FanpageBotService.resolveSelectiveThinkingMode(dAngry, state, "Trả lời linh tinh cái quái gì đấy"),
                "Khi khách tức giận/tiêu cực, bắt buộc bật EMOTIONAL_WARM_THINK để xoa dịu cảm xúc");
    }

    @Test
    @DisplayName("TEST Q — SELECTIVE THINKING: Khách trả lời hời hợt giữa phễu ('Ừm', 'Để xem đã') bật Thinking kéo mood, nhưng ở Turn 1 thì TẮT")
    void testQ_MidFunnelLukewarmReplyTriggersThinkingButNotTurn1() {
        // 1. Ở giai đoạn đầu (Turn 1 - chưa chào/chưa tư vấn): KHÔNG coi là tụt mood hời hợt
        ConversationState turn1State = ConversationState.initial();
        assertFalse(SentimentIntentLayer.isMidFunnelLukewarmReply("Giá", turn1State));
        assertFalse(SentimentIntentLayer.isMidFunnelLukewarmReply("Ừm", turn1State));
        PrimaryActionRouter.RoutingDecision dTurn1 = PrimaryActionRouter.route("Giá", turn1State, activeCatalogChunks, List.of());
        assertEquals(ThinkingMode.FAST_NO_THINK,
                FanpageBotService.resolveSelectiveThinkingMode(dTurn1, turn1State, "Giá"));

        // 2. Ở giữa phễu (sau khi đã báo giá/tư vấn gói 60p): Khách nhắn hời hợt 'Ừm để xem đã' hoặc 'Vậy à' -> BẬT EMOTIONAL_WARM_THINK
        List<ConversationTurn> midFunnelHistory = List.of(
                new ConversationTurn("user", "Tôi bị đau lưng, mỏi gối tê tay, xin giá"),
                new ConversationTurn("assistant", "Dạ với tình trạng của mình, bên em có gói massage cơ bản 60 phút (chi phí 399k) giúp làm dịu vùng lưng và cổ vai rất tốt ạ.")
        );
        ConversationState midFunnelState = ConversationState.hydrateForTurn(
                PAGE_ID, "u-luke", "c-luke", false, "Ừm để xem đã", midFunnelHistory,
                List.of("massage", "đau lưng"), activeCatalogChunks
        );
        assertTrue(SentimentIntentLayer.isMidFunnelLukewarmReply("Ừm để xem đã", midFunnelState));
        assertTrue(SentimentIntentLayer.isMidFunnelLukewarmReply("Vậy à", midFunnelState));
        assertTrue(SentimentIntentLayer.isMidFunnelLukewarmReply("Ok em", midFunnelState));

        PrimaryActionRouter.RoutingDecision dLukewarm = PrimaryActionRouter.route(
                "Ừm để xem đã", midFunnelState, activeCatalogChunks, List.of("massage", "đau lưng")
        );
        assertEquals("MID_FUNNEL_LUKEWARM_REENGAGE", dLukewarm.detectedIntent());
        assertEquals(ThinkingMode.EMOTIONAL_WARM_THINK,
                FanpageBotService.resolveSelectiveThinkingMode(dLukewarm, midFunnelState, "Ừm để xem đã"),
                "Khi khách trả lời hời hợt ở giữa phễu (tụt mood), bắt buộc bật EMOTIONAL_WARM_THINK để tăng nhiệt độ mua hàng");
    }
}
