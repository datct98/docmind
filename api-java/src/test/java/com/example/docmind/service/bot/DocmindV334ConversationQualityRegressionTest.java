package com.example.docmind.service.bot;

import com.example.docmind.dto.LearnedRuleDto;
import com.example.docmind.dto.bot.FanpageBotRequest.ConversationTurn;
import com.example.docmind.repository.RetrievedChunk;
import com.example.docmind.service.bot.PrimaryActionRouter.PrimaryAction;
import com.example.docmind.service.retrieval.ReciprocalRankFusion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DocMind v3.3.4 Conversation Quality & Unified Wiring Regression Suite.
 */
class DocmindV334ConversationQualityRegressionTest {

    private static final UUID PAGE_ID = UUID.fromString("b23cd382-4f7a-4829-8c54-1d410da14bf8");
    private List<ReciprocalRankFusion.FusedChunk> activeCatalogChunks;

    private final PackageCatalogService.PackageSummary ns01 = new PackageCatalogService.PackageSummary(
            "HN_NS01", "NS01", "HN", "Massage cơ bản", "MASSAGE",
            List.of("lưng", "cổ", "vai", "gáy", "chân", "tay"),
            60, "60 phút", 399_000L, "399k",
            "Phòng chung có rèm riêng",
            "Massage toàn thân, tập trung chăm sóc các vùng cơ thể đang đau mỏi, buffet thực dưỡng",
            "Khách đau mỏi cơ bản",
            "Ấn huyệt và thả lỏng cơ thể",
            false
    );

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
    }

    @Test
    @DisplayName("STEP 2.1 — AUTHENTIC EMOTIONAL COMPASS: Chỉ bật khi ThinkingMode != FAST_NO_THINK")
    void testAuthenticEmotionalCompassInjectedOnlyOnDeepThinkModes() {
        assertEquals("", FanpageBotPromptBuilder.buildAuthenticEmotionalCompassBlock(
                ThinkingMode.FAST_NO_THINK, "mình", ns01
        ));

        for (ThinkingMode mode : List.of(
                ThinkingMode.PRICE_ANCHOR_THINK,
                ThinkingMode.OBJECTION_REFRAME_THINK,
                ThinkingMode.REPAIR_FIRST,
                ThinkingMode.EMOTIONAL_WARM_THINK
        )) {
            String block = FanpageBotPromptBuilder.buildAuthenticEmotionalCompassBlock(mode, "chị", ns01, "Nàng Sen Spa");
            assertTrue(block.contains("<AUTHENTIC_EMOTIONAL_COMPASS>"), "Must include compass tag for " + mode);
            assertTrue(block.contains("Massage cơ bản (60 phút - 399k"), "Must ground active package in compass");
            assertTrue(block.contains("gọi khách là \"chị\""), "Must use resolved honorific");
        }
    }

    @Test
    @DisplayName("STEP 3.1 — LUKEWARM VS OBJECTION REPEAT: 'Để xem đã' khi objectionCount==0 là LUKEWARM; khi objectionCount>=1 & !downsellOffered đi vào HANDLE_PRICE_OBJECTION_REPEAT")
    void testLukewarmVsPriceObjectionRepeatRouting() {
        List<ConversationTurn> noObjectionHistory = List.of(
                new ConversationTurn("user", "Tôi bị đau lưng, mỏi vai, xin giá"),
                new ConversationTurn("assistant", "Dạ với tình trạng của mình, bên em có gói massage cơ bản 60 phút (chi phí 399k) giúp làm dịu vùng lưng và cổ vai rất tốt ạ.")
        );
        ConversationState noObjectionState = ConversationState.hydrateForTurn(
                PAGE_ID, "u-luke-0", "c-luke-0", false, "Để xem đã", noObjectionHistory,
                List.of("massage", "đau lưng"), activeCatalogChunks
        );
        assertEquals(0, noObjectionState.objectionCount());
        assertTrue(SentimentIntentLayer.isMidFunnelLukewarmReply("Để xem đã", noObjectionState),
                "Khi objectionCount == 0, 'Để xem đã' giữa phễu phải được nhận diện là Lukewarm (TEST Q)");

        List<ConversationTurn> afterFirstObjectionHistory = List.of(
                new ConversationTurn("user", "Tôi bị đau lưng, mỏi vai, xin giá"),
                new ConversationTurn("assistant", "Dạ với tình trạng của mình, bên em có gói massage cơ bản 60 phút (chi phí 399k) giúp làm dịu vùng lưng và cổ vai rất tốt ạ."),
                new ConversationTurn("user", "Đắt thế"),
                new ConversationTurn("assistant", "Dạ em rất hiểu mình cân nhắc chi phí cho buổi đầu ạ. Hôm nay mình thử trải nghiệm lẻ 1 buổi xem sao ha 🥰")
        );
        ConversationState afterFirstObjectionState = ConversationState.hydrateForTurn(
                PAGE_ID, "u-luke-1", "c-luke-1", false, "Để xem đã", afterFirstObjectionHistory,
                List.of("massage", "đau lưng"), activeCatalogChunks
        );
        assertTrue(afterFirstObjectionState.objectionCount() >= 1);
        assertFalse(afterFirstObjectionState.downsellOffered());
        assertFalse(SentimentIntentLayer.isMidFunnelLukewarmReply("Để xem đã", afterFirstObjectionState),
                "Khi objectionCount >= 1 && !downsellOffered, 'Để xem đã' phải trả về false để đi vào HANDLE_PRICE_OBJECTION_REPEAT");

        PrimaryActionRouter.RoutingDecision decision = PrimaryActionRouter.route(
                "Để xem đã",
                afterFirstObjectionState,
                activeCatalogChunks,
                List.of("massage", "đau lưng")
        );
        assertEquals(PrimaryAction.HANDLE_PRICE_OBJECTION_REPEAT, decision.effectiveResponseAction(),
                "Sau khi đã chê đắt lần 1, 'Để xem đã' phải kích hoạt Nhịp 2 Downsell (HANDLE_PRICE_OBJECTION_REPEAT)");
    }

    @Test
    @DisplayName("STEP 3.2 — PROMPT BUDGET GUARD SCOPING: Khớp chính xác PB-SPA-EMOTIONAL-REPAIR-001, PB-SPA-MID-FUNNEL-LUKEWARM-001, PB-SPA-PRICE-ANCHOR-001")
    void testPromptBudgetGuardNewRuleScoping() {
        List<LearnedRuleDto> rules = List.of(
                rule("PB-SPA-PRICE-ANCHOR-001", "PRICING", "Bong bóng 1 báo giá gọn, Bong bóng 2 vẽ trải nghiệm thực tế", 96, false, "none"),
                rule("PB-SPA-EMOTIONAL-REPAIR-001", "EMPATHY", "Hạ cái tôi, nhận lời góp ý thật duyên dáng và quay lại đúng nhu cầu cơ học", 99, false, "none"),
                rule("PB-SPA-MID-FUNNEL-LUKEWARM-001", "REENGAGE", "Khơi gợi 1 điểm chạm trải nghiệm thực tế và hỏi mở nhẹ nhàng", 94, false, "none")
        );

        LearnedRuleDto repairMatched = PromptBudgetGuard.selectScopedContextualRule(
                rules, "WORDING_FEEDBACK_REPAIR", "đau lưng chứ đau lòng đâu mà nhẹ nhõm"
        );
        assertNotNull(repairMatched);
        assertEquals("PB-SPA-EMOTIONAL-REPAIR-001", repairMatched.ruleCode());

        LearnedRuleDto lukewarmMatched = PromptBudgetGuard.selectScopedContextualRule(
                rules, "MID_FUNNEL_LUKEWARM_REENGAGE", "Ừ để xem đã"
        );
        assertNotNull(lukewarmMatched);
        assertEquals("PB-SPA-MID-FUNNEL-LUKEWARM-001", lukewarmMatched.ruleCode());

        LearnedRuleDto anchorMatched = PromptBudgetGuard.selectScopedContextualRule(
                rules, "EXPLICIT_PRICE_INQUIRY", "chi phí gói 60 phút"
        );
        assertNotNull(anchorMatched);
        assertEquals("PB-SPA-PRICE-ANCHOR-001", anchorMatched.ruleCode());
    }

    private static LearnedRuleDto rule(String ruleCode, String category, String text, int priority,
                                       boolean alwaysOn, String handoffPolicy) {
        return new LearnedRuleDto(
                UUID.randomUUID(), category, null, null, text, List.of(), true, PAGE_ID.toString(),
                0.85, 0, 0, 0, 1, null, null, null, null, null, "DOM", "", null, alwaysOn,
                "APPROVED", null, ruleCode, null, null, null, priority, null, null,
                "must_use_kb_document_context", handoffPolicy, "MANUAL"
        );
    }
}
