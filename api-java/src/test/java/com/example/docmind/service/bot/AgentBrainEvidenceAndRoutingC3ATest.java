package com.example.docmind.service.bot;

import com.example.docmind.dto.PagePersonaConfig;
import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.dto.bot.FanpageBotResponse;
import com.example.docmind.repository.RetrievedChunk;
import com.example.docmind.service.llm.LlmClient;
import com.example.docmind.service.llm.ReasoningBudget;
import com.example.docmind.service.retrieval.PromptBuilder;
import com.example.docmind.service.retrieval.ReciprocalRankFusion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Bộ test nghiệm thu hành vi Mốc C3-A:
 * 1. Minh bạch hóa nguồn gốc suy luận (BrainReasoningProvenance) & Living Hypothesis dựa trên bằng chứng khách hàng.
 * 2. Phân biệt 5 thao tác bộ nhớ (ADD, UPDATE, SUPERSEDE, RETRACT, UNCERTAIN) trên 5 nhóm slot đa ngành.
 * 3. ScopeMatcher đa ngành từ PackageSummary.inferred().scopeTags() & PagePersonaConfig.scopeTaxonomy().
 * 4. Giải mã câu ngắn / lấp lửng ("Cũng được", "Biết thế", "Để xem đã", "Ừ") theo 3 ngữ cảnh + luồng Turn 1 -> Turn 6.
 */
class AgentBrainEvidenceAndRoutingC3ATest {

    private static ReciprocalRankFusion.FusedChunk buildChunk(String heading, String text) {
        RetrievedChunk rc = new RetrievedChunk(
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(heading),
                List.of(UUID.randomUUID()),
                1,
                new double[]{0, 0, 10, 10},
                text,
                0.95
        );
        return new ReciprocalRankFusion.FusedChunk(rc, 0.95);
    }

    private static final String SPA_KB_TABLE = """
            | Mã gói | Tên gói | Thời lượng | Giá | Vùng chăm sóc | Vai trò thương mại |
            | --- | --- | --- | --- | --- | --- |
            | NS01 | Massage cơ bản | 60 phút | 399k | đầu, cổ, vai, gáy, lưng | gói chính |
            | DS02 | Gói dưỡng sinh cơ bản | 60 phút | 239k | đầu, cổ, vai, gáy | gói tiết kiệm |
            | VIP99 | Liệu trình Hoàng Gia Đá Nóng | 120 phút | 799k | đầu, cổ, vai, gáy, lưng, toàn thân | cao cấp |
            """;

    @Test
    void testTripleContextShortUtterance_CungDuoc_RoutesDistinctlyAcrossThreeContexts() {
        AgentBrainService brainService = new AgentBrainService((com.example.docmind.client.AgentBrainClient) null);
        UUID pageId = UUID.randomUUID();
        List<PackageCatalogService.PackageSummary> spaCatalog = PackageCatalogService.extractCatalog(
                List.of(buildChunk("Bảng giá Spa", SPA_KB_TABLE)),
                List.of("đầu", "cổ", "vai", "gáy", "lưng")
        );
        CleanAgentCatalogAndPromptBuilder.TieredCatalogResult tiered =
                CleanAgentCatalogAndPromptBuilder.rankAndTierCatalogForCleanAgent(
                        spaCatalog, List.of("mỏi cổ vai gáy"), List.of("cổ", "vai", "gáy"), java.util.Set.of(), false
                );

        // =========================================================================
        // Ngữ cảnh 1: Ngay sau khi bot vừa bảo vệ giá ở STAGE_2 (khách vẫn lấn cấn chi phí)
        // -> Có gói ECONOMICAL_OPTION chưa giới thiệu => STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL
        // =========================================================================
        String senderCtx1 = "sender-ctx1-price";
        brainService.recordCommercialFunnelState(pageId, senderCtx1, 1, true, false);
        List<FanpageBotRequest.ConversationTurn> historyCtx1 = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Mình bị mỏi cổ vai gáy"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ em gợi ý mình gói Massage cơ bản 60 phút giá 399k trọn gói ạ."),
                new FanpageBotRequest.ConversationTurn("user", "399k hơi cao nhỉ"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ mức giá 399k đã bao gồm trọn gói 60 phút ấn huyệt chuyên sâu và trà thảo mộc, không phát sinh phụ phí ạ. Mình thử trải nghiệm trước 1 lần để cảm nhận nhé?")
        );

        AgentBrainService.ShortUtteranceResolution resCtx1 = brainService.decodeContextualShortUtterance(
                "Cũng được",
                historyCtx1.get(3).content(),
                CleanAgentOrchestratorService.SPEECH_ACT_INVITED_TRIAL_OR_TIME,
                historyCtx1,
                1,
                true,
                false,
                true,
                "NONE"
        );
        assertTrue(resCtx1.isContextualShortUtterance());
        assertEquals("PRICE_HESITATION_AFTER_VALUE_DEFENSE", resCtx1.decodedMeaning());
        assertEquals("SOFT_SUGGEST_ECONOMICAL_OPTION", resCtx1.proposedAction());
        assertEquals("STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL", resCtx1.recommendedStage());

        // Kiểm chứng nhánh Ngữ cảnh 1 khi Page thuộc ngành không có gói ECONOMICAL_OPTION (ví dụ Gạch ốp lát)
        AgentBrainService.ShortUtteranceResolution resCtx1NoDownsell = brainService.decodeContextualShortUtterance(
                "Cũng được",
                "Dạ mẫu Gạch Porcelain 80x80cm giá 450k/m² có lớp men chống xước cao cấp ạ.",
                "",
                List.of(
                        new FanpageBotRequest.ConversationTurn("user", "450k/m2 hơi căng nhỉ"),
                        new FanpageBotRequest.ConversationTurn("assistant", "Dạ mẫu Gạch Porcelain 80x80cm giá 450k/m² có lớp men chống xước cao cấp ạ.")
                ),
                1,
                true,
                false,
                false,
                "NONE"
        );
        assertEquals("RESPECT_PACE_OR_BRIEF_CHECKIN", resCtx1NoDownsell.proposedAction());
        assertEquals("STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE", resCtx1NoDownsell.recommendedStage());

        // =========================================================================
        // Ngữ cảnh 2: Ngay sau khi bot vừa trả lời câu hỏi vận hành (giờ mở cửa / chỗ đỗ xe)
        // -> ACKNOWLEDGED_OPERATIONAL_INFO -> tiếp tục tư vấn tự nhiên ở STAGE_1, KHÔNG ép downsell!
        // =========================================================================
        String senderCtx2 = "sender-ctx2-ops";
        brainService.recordCommercialFunnelState(pageId, senderCtx2, 1, true, false);
        List<FanpageBotRequest.ConversationTurn> historyCtx2 = List.of(
                new FanpageBotRequest.ConversationTurn("user", "399k hơi cao nhỉ"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ mức giá 399k đã bao gồm trọn gói 60 phút chăm sóc chuyên sâu ạ."),
                new FanpageBotRequest.ConversationTurn("user", "Tối mấy giờ bên mình đóng cửa và có chỗ đỗ ô tô không?"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ bên em mở cửa từ 9h sáng đến 21h tối hàng ngày và có chỗ đỗ ô tô miễn phí ngay trước sảnh ạ.")
        );

        AgentBrainService.ShortUtteranceResolution resCtx2 = brainService.decodeContextualShortUtterance(
                "Cũng được",
                historyCtx2.get(3).content(),
                CleanAgentOrchestratorService.SPEECH_ACT_ANSWERED_OPERATIONAL_INFO,
                historyCtx2,
                1,
                true,
                false,
                true,
                "NONE"
        );
        assertTrue(resCtx2.isContextualShortUtterance());
        assertEquals("ACKNOWLEDGED_OPERATIONAL_INFO", resCtx2.decodedMeaning());
        assertEquals("CONTINUE_NATURAL_CONSULTATION_NO_DOWNSELL", resCtx2.proposedAction());
        assertEquals("STAGE_1_DISCOVERY_AND_FIRST_QUOTE", resCtx2.recommendedStage());

        List<String> sigsCtx2 = CleanAgentOrchestratorService.classifyCleanCurrentTurnSignals(
                "Cũng được", false, false, false, false, false, 1, false, false,
                List.of("mỏi cổ vai gáy"), List.of("cổ", "vai", "gáy"), "ngồi nhiều",
                null, "NONE", historyCtx2
        );
        assertTrue(sigsCtx2.contains("ACKNOWLEDGED_OPERATIONAL_INFO"));
        String stageCtx2 = CleanAgentOrchestratorService.resolveCleanFunnelStage(
                sigsCtx2, "Cũng được", 1, true, false, false, tiered, historyCtx2
        );
        assertEquals("STAGE_1_DISCOVERY_AND_FIRST_QUOTE", stageCtx2,
                "Ngữ cảnh 2: 'Cũng được' sau câu trả lời giờ đóng cửa/chỗ đỗ xe tuyệt đối không được ép nhảy sang STAGE_3B downsell");

        // =========================================================================
        // Ngữ cảnh 3: Ngay sau khi bot đề xuất khung giờ hẹn / gói đặt lịch
        // -> POSITIVE_CONFIRMATION_SIGNAL -> STAGE_4_POSITIVE_TRIAL_OR_BOOKING
        // =========================================================================
        List<FanpageBotRequest.ConversationTurn> historyCtx3 = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Mình muốn đặt gói 239k tối nay"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ tối nay bên em còn khung 19h00 khá thoáng, chị ghé lúc 19h để em giữ lịch gói dưỡng sinh cơ bản 239k cho mình nhé?")
        );

        AgentBrainService.ShortUtteranceResolution resCtx3 = brainService.decodeContextualShortUtterance(
                "Cũng được",
                historyCtx3.get(1).content(),
                CleanAgentOrchestratorService.SPEECH_ACT_INVITED_TRIAL_OR_TIME,
                historyCtx3,
                1,
                false,
                true,
                true,
                "COLLECTING_VISIT_TIME"
        );
        assertTrue(resCtx3.isContextualShortUtterance());
        assertEquals("POSITIVE_CONFIRMATION_SIGNAL", resCtx3.decodedMeaning());
        assertEquals("PROCEED_BOOKING_CONFIRMATION_OR_SLOT_COLLECTION", resCtx3.proposedAction());
        assertEquals("STAGE_4_POSITIVE_TRIAL_OR_BOOKING", resCtx3.recommendedStage());

        List<String> sigsCtx3 = CleanAgentOrchestratorService.classifyCleanCurrentTurnSignals(
                "Cũng được", false, false, false, false, false, 1, false, false,
                List.of("mỏi cổ vai gáy"), List.of("cổ", "vai", "gáy"), "ngồi nhiều",
                null, "COLLECTING_VISIT_TIME", historyCtx3
        );
        assertTrue(sigsCtx3.contains("POSITIVE_CONFIRMATION_SIGNAL"));
        String stageCtx3 = CleanAgentOrchestratorService.resolveCleanFunnelStage(
                sigsCtx3, "Cũng được", 1, false, true, false, tiered, historyCtx3
        );
        assertEquals("STAGE_4_POSITIVE_TRIAL_OR_BOOKING", stageCtx3);

        // Đảm bảo cả 3 quyết định điều hướng của cùng 1 câu "Cũng được" hoàn toàn phân biệt nhau!
        assertNotEquals(resCtx1.recommendedStage(), resCtx2.recommendedStage());
        assertNotEquals(resCtx2.recommendedStage(), resCtx3.recommendedStage());
        assertNotEquals(resCtx1.recommendedStage(), resCtx3.recommendedStage());
    }

    @Test
    void testTurn1ToTurn6Flow_BietTheSoftDownsell_ThenOkDeCanNhacThemWarmGoodbyeZeroGhostProduct() {
        LlmClient mockLlm = mock(LlmClient.class);
        when(mockLlm.name()).thenReturn("ollama:qwen3.8-flash");
        AgentBrainService brainService = new AgentBrainService((com.example.docmind.client.AgentBrainClient) null);
        CleanAgentOrchestratorService orchestrator = new CleanAgentOrchestratorService(mockLlm, brainService, null);

        UUID pageId = UUID.randomUUID();
        String senderId = "user-c3a-turn1-to-6";
        PromptBuilder.PagePersona spaPersona = new PromptBuilder.PagePersona(
                "Nàng Sen Spa",
                "BEAUTY_SPA",
                "Dân văn phòng",
                "Thư giãn chuyên sâu",
                PagePersonaConfig.fromJson("""
                        {
                          "industry_code": "BEAUTY_SPA",
                          "bot_name": "Lễ tân Nàng Sen",
                          "self_pronoun": "em",
                          "default_customer_pronoun": "mình",
                          "scope_taxonomy": ["đầu", "cổ", "vai", "gáy", "lưng", "toàn thân"]
                        }
                        """)
        );

        List<ReciprocalRankFusion.FusedChunk> fusedChunks = List.of(
                buildChunk("Bảng giá Nàng Sen Spa", SPA_KB_TABLE)
        );
        List<String> scopeTaxonomy = spaPersona.resolvedConfig().scopeTaxonomy();

        // Giả lập lịch sử Turn 1 -> Turn 4 (đã báo 399k và bảo vệ giá ở STAGE_2; 239k và 799k CHƯA xuất hiện trong lịch sử)
        brainService.recordCommercialFunnelState(pageId, senderId, 1, true, false);
        List<FanpageBotRequest.ConversationTurn> historyBeforeTurn5 = new ArrayList<>(List.of(
                new FanpageBotRequest.ConversationTurn("user", "Mình bị đau mỏi cổ vai gáy mấy hôm nay do ngồi máy tính nhiều"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ ngồi máy tính lâu dễ căng cứng cơ vùng cổ vai gáy lắm ạ. Em gợi ý mình tham khảo gói Massage cơ bản 60 phút (399k trọn gói) để giải tỏa sâu vùng cổ vai gáy nhé ạ."),
                new FanpageBotRequest.ConversationTurn("user", "399k hơi cao so với mình nhỉ"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ mức giá 399k đã bao gồm trọn gói 60 phút ấn huyệt chuyên sâu và trà thảo mộc, không phát sinh phụ phí hay nhận tip ạ. Mình thử trải nghiệm trước 1 lần để cảm nhận nhé?")
        ));

        ConversationState preTurn5 = ConversationState.hydrateForTurn(
                pageId, senderId, null, true, "Biết thế", historyBeforeTurn5, scopeTaxonomy, List.of()
        );
        ConversationState convTurn5 = preTurn5.enrichWithCurrentMessage("Biết thế", scopeTaxonomy)
                .withRetrievedChunks(fusedChunks, scopeTaxonomy, historyBeforeTurn5);

        AtomicReference<String> capturedTurn5Prompt = new AtomicReference<>("");
        String llmTurn5Json = """
                {
                  "reply_bubbles": [
                    {"text": "Dạ em hiểu cảm giác cân nhắc chi phí của mình ạ, mình chưa cần vội chốt ngay đâu nhé.", "typing_delay_ms": 0},
                    {"text": "Nếu mình muốn thử một phương án nhẹ nhàng tiết kiệm hơn thì bên em có Gói dưỡng sinh cơ bản 60 phút (239k trọn gói) tập trung vùng đầu cổ vai gáy, khi nào tiện mình cứ nhắn em nhé ạ.", "typing_delay_ms": 1500}
                  ],
                  "brain_reflection": {
                    "observed_fact": "slot_budget_sensitivity=Khách cân nhắc chi phí gói 399k",
                    "care_note_delta": "",
                    "working_hypothesis": "Khách công nhận giá trị gói 399k nhưng vẫn lấn cấn ngân sách",
                    "intended_speech_act": "RESPECTED_PAUSE_WITH_SOFT_OPTION"
                  },
                  "should_handoff": false
                }
                """;
        when(mockLlm.complete(anyString(), any(ReasoningBudget.class))).thenAnswer(inv -> {
            capturedTurn5Prompt.set(inv.getArgument(0, String.class));
            return llmTurn5Json;
        });
        when(mockLlm.complete(anyString())).thenAnswer(inv -> {
            capturedTurn5Prompt.set(inv.getArgument(0, String.class));
            return llmTurn5Json;
        });

        // =========================================================================
        // TURN 5: Khách đáp "Biết thế" -> Phải vào STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL & gợi ý nhẹ gói tiết kiệm 239k
        // =========================================================================
        FanpageBotRequest reqTurn5 = new FanpageBotRequest(
                pageId.toString(), senderId, "Chị Lan", "Biết thế", null, historyBeforeTurn5, List.of(), 5
        );
        FanpageBotResponse respTurn5 = orchestrator.executeCleanAgentTurn(
                pageId,
                reqTurn5,
                "Biết thế",
                "mình",
                spaPersona,
                preTurn5,
                convTurn5,
                fusedChunks,
                List.of(),
                null,
                null,
                List.of(),
                List.of(),
                FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION,
                historyBeforeTurn5.size(),
                System.nanoTime(),
                10L
        );

        assertTrue(capturedTurn5Prompt.get().contains("STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL"),
                "Turn 5 ('Biết thế' sau bảo vệ giá) phải được điều hướng vào STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL");
        assertTrue(respTurn5.replyText().contains("239k"),
                "Turn 5 phải gợi ý nhẹ nhàng phương án ECONOMICAL_OPTION (239k): " + respTurn5.replyText());
        assertFalse(respTurn5.replyText().contains("799k") || respTurn5.replyText().toLowerCase().contains("hoàng gia"),
                "Turn 5 không được nhắc tới gói VIP99 (799k)");
        assertTrue(brainService.hasRecalledPresentedDownsell(pageId, senderId),
                "Sau Turn 5 (STAGE_3B), AgentBrain phải ghi nhận hasPresentedDownsell=true");

        // =========================================================================
        // TURN 6: Khách nhắn "Ok để cân nhắc thêm" -> Phải vào STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE,
        // chào ấm áp & TUYỆT ĐỐI KHÔNG nhắc bất kỳ sản phẩm ma nào chưa có trong presentedPackagesInHistory (799k)!
        // =========================================================================
        List<FanpageBotRequest.ConversationTurn> historyBeforeTurn6 = new ArrayList<>(historyBeforeTurn5);
        historyBeforeTurn6.add(new FanpageBotRequest.ConversationTurn("user", "Biết thế"));
        historyBeforeTurn6.add(new FanpageBotRequest.ConversationTurn("assistant", respTurn5.replyText()));

        ConversationState preTurn6 = ConversationState.hydrateForTurn(
                pageId, senderId, null, true, "Ok để cân nhắc thêm", historyBeforeTurn6, scopeTaxonomy, List.of()
        );
        ConversationState convTurn6 = preTurn6.enrichWithCurrentMessage("Ok để cân nhắc thêm", scopeTaxonomy)
                .withRetrievedChunks(fusedChunks, scopeTaxonomy, historyBeforeTurn6);

        AtomicReference<String> capturedTurn6Prompt = new AtomicReference<>("");
        String llmTurn6Json = """
                {
                  "reply_bubbles": [
                    {"text": "Dạ vâng ạ, em đã lưu lại thông tin tư vấn cho mình rồi, mình cứ thoải mái cân nhắc thêm nhé ạ.", "typing_delay_ms": 0},
                    {"text": "Chúc mình một buổi tối thật thư thái, khi nào tiện cần hỗ trợ cứ nhắn em bất cứ lúc nào nhé ạ!", "typing_delay_ms": 1200}
                  ],
                  "brain_reflection": {
                    "observed_fact": "",
                    "care_note_delta": "",
                    "working_hypothesis": "Khách tạm dừng để tự cân nhắc giữa hai phương án đã tư vấn",
                    "intended_speech_act": "RESPECTED_PAUSE_WITH_SOFT_OPTION"
                  },
                  "should_handoff": false
                }
                """;
        when(mockLlm.complete(anyString(), any(ReasoningBudget.class))).thenAnswer(inv -> {
            capturedTurn6Prompt.set(inv.getArgument(0, String.class));
            return llmTurn6Json;
        });
        when(mockLlm.complete(anyString())).thenAnswer(inv -> {
            capturedTurn6Prompt.set(inv.getArgument(0, String.class));
            return llmTurn6Json;
        });

        FanpageBotRequest reqTurn6 = new FanpageBotRequest(
                pageId.toString(), senderId, "Chị Lan", "Ok để cân nhắc thêm", null, historyBeforeTurn6, List.of(), 5
        );
        FanpageBotResponse respTurn6 = orchestrator.executeCleanAgentTurn(
                pageId,
                reqTurn6,
                "Ok để cân nhắc thêm",
                "mình",
                spaPersona,
                preTurn6,
                convTurn6,
                fusedChunks,
                List.of(),
                null,
                null,
                List.of(),
                List.of(),
                FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION,
                historyBeforeTurn6.size(),
                System.nanoTime(),
                10L
        );

        String userPartTurn6 = capturedTurn6Prompt.get().substring(capturedTurn6Prompt.get().indexOf("<!-- SYSTEM_USER_SPLIT -->"));
        assertTrue(userPartTurn6.contains("STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE"),
                "Turn 6 ('Ok để cân nhắc thêm') phải đi thẳng vào STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE");
        assertFalse(userPartTurn6.contains("Hoàng Gia Đá Nóng") || userPartTurn6.contains("799k"),
                "Prompt Turn 6 ở STAGE_6 tuyệt đối không được để lộ gói ma VIP99 (799k) chưa từng xuất hiện trong lịch sử");
        assertEquals(2, respTurn6.replyBubbles().size(), "Turn 6 phải trả về 2 bong bóng chào tạm biệt ấm áp");
        assertFalse(respTurn6.replyText().contains("799k") || respTurn6.replyText().toLowerCase().contains("hoàng gia"),
                "Turn 6 tuyệt đối không nhắc tới bất kỳ sản phẩm ma nào");
        assertFalse(respTurn6.replyText().contains("239k") || respTurn6.replyText().contains("399k"),
                "Turn 6 chào tạm biệt ấm áp, không chào mời lặp lại giá gói");
    }

    @Test
    void testFiveMemoryFactOperations_AddUpdateSupersedeRetractUncertain_AcrossMultiIndustrySlots() {
        AgentBrainService brainService = new AgentBrainService((com.example.docmind.client.AgentBrainClient) null);
        UUID tilePageId = UUID.randomUUID();
        String tileSender = "tile-customer-c3a";

        // 1. ADD (Bổ sung không mâu thuẫn cùng nhóm slot_target_scope: phòng khách + phòng bếp)
        AgentBrainService.FactMutationRecord op1 = brainService.applyStructuredFactObservation(
                tilePageId,
                tileSender,
                "slot_target_scope=Khách cần lát gạch phòng khách",
                "Mình đang cần tìm gạch lát nền cho phòng khách"
        );
        assertNotNull(op1);
        assertEquals(AgentBrainService.FactMemoryOperation.ADD, op1.operation());
        assertEquals(AgentBrainService.FactSlotGroup.SLOT_TARGET_SCOPE.code(), op1.slotGroup());

        AgentBrainService.FactMutationRecord op2 = brainService.applyStructuredFactObservation(
                tilePageId,
                tileSender,
                "slot_target_scope=Khách muốn ốp thêm cả phòng bếp",
                "À mình muốn ốp thêm cả phòng bếp nữa nhé"
        );
        assertNotNull(op2);
        assertEquals(AgentBrainService.FactMemoryOperation.ADD, op2.operation(),
                "Mở rộng nhu cầu từ phòng khách sang thêm phòng bếp phải là ADD, giữ cả hai trong verifiedFacts");
        List<String> factsAfterAdd = brainService.getRecalledVerifiedFacts(tilePageId, tileSender);
        assertEquals(2, factsAfterAdd.size());
        assertTrue(factsAfterAdd.stream().anyMatch(f -> f.contains("phòng khách")));
        assertTrue(factsAfterAdd.stream().anyMatch(f -> f.contains("phòng bếp")));

        // 2. UPDATE (Làm rõ chi tiết trên cùng thực thể đã biết mà không thay đổi lựa chọn)
        AgentBrainService.FactMutationRecord opUpdate = brainService.applyStructuredFactObservation(
                tilePageId,
                tileSender,
                "slot_target_scope=Khách cần lát gạch phòng khách diện tích 40m2 tông sáng",
                "Phòng khách nhà mình cần lát gạch phòng khách diện tích 40m2 tông sáng nhé"
        );
        assertNotNull(opUpdate);
        assertEquals(AgentBrainService.FactMemoryOperation.UPDATE, opUpdate.operation());
        List<String> factsAfterUpdate = brainService.getRecalledVerifiedFacts(tilePageId, tileSender);
        assertEquals(2, factsAfterUpdate.size());
        assertTrue(factsAfterUpdate.stream().anyMatch(f -> f.contains("40m2")));
        assertTrue(factsAfterUpdate.stream().anyMatch(f -> f.contains("phòng bếp")));

        // 3. UNCERTAIN (Khách mới hỏi dò chưa xác nhận -> KHÔNG lưu thành verifiedFact!)
        AgentBrainService.FactMutationRecord opUncertain = brainService.applyStructuredFactObservation(
                tilePageId,
                tileSender,
                "slot_preference_spec=Gạch kích thước 100x100cm",
                "Không biết bên mình có mẫu gạch 100x100cm không?"
        );
        assertNotNull(opUncertain);
        assertEquals(AgentBrainService.FactMemoryOperation.UNCERTAIN, opUncertain.operation());
        List<String> factsAfterUncertain = brainService.getRecalledVerifiedFacts(tilePageId, tileSender);
        assertEquals(2, factsAfterUncertain.size(), "Câu hỏi dò UNCERTAIN không được ghi vào verifiedFacts");
        assertFalse(factsAfterUncertain.stream().anyMatch(f -> f.contains("100x100cm")));

        // 4. RETRACT (Khách phủ định / rút lại thông tin trước đó: "À thôi mình không ốp phòng bếp nữa")
        AgentBrainService.FactMutationRecord opRetract = brainService.applyStructuredFactObservation(
                tilePageId,
                tileSender,
                "",
                "À thôi mình không ốp phòng bếp nữa nhé"
        );
        assertNotNull(opRetract);
        assertEquals(AgentBrainService.FactMemoryOperation.RETRACT, opRetract.operation());
        List<String> factsAfterRetract = brainService.getRecalledVerifiedFacts(tilePageId, tileSender);
        assertEquals(1, factsAfterRetract.size(), "Sau RETRACT phòng bếp, chỉ còn lại phòng khách trong verifiedFacts");
        assertFalse(factsAfterRetract.stream().anyMatch(f -> f.contains("phòng bếp")));
        assertTrue(factsAfterRetract.stream().anyMatch(f -> f.contains("phòng khách")));

        // 5. SUPERSEDE (Thay thế khi mâu thuẫn / đổi lựa chọn: gói 530k -> 239k & lịch tối nay -> chiều mai)
        UUID spaPageId = UUID.randomUUID();
        String spaSender = "spa-customer-c3a";
        brainService.applyStructuredFactObservation(
                spaPageId, spaSender, "slot_target_scope=Đau mỏi vai gáy", "Mình bị đau mỏi vai gáy"
        );
        brainService.applyStructuredFactObservation(
                spaPageId, spaSender, "slot_target_scope=Mỏi thêm vùng thắt lưng", "Nay mỏi thêm cả thắt lưng nữa"
        );
        assertEquals(2, brainService.getRecalledVerifiedFacts(spaPageId, spaSender).size(),
                "Đau vai gáy nói thêm mỏi thắt lưng phải giữ cả hai (ADD)");

        brainService.applyStructuredFactObservation(
                spaPageId, spaSender, "slot_product_focus=Chọn gói chuyên sâu 530k", "Cho mình chọn gói 530k"
        );
        AgentBrainService.FactMutationRecord supersedePkg = brainService.applyStructuredFactObservation(
                spaPageId, spaSender, "slot_product_focus=Đổi sang gói cơ bản 239k", "Thôi cho mình đổi từ gói 530k sang gói 239k nhé"
        );
        assertEquals(AgentBrainService.FactMemoryOperation.SUPERSEDE, supersedePkg.operation());

        brainService.applyStructuredFactObservation(
                spaPageId, spaSender, "slot_logistics_or_care=Lịch hẹn tối nay", "Mình đặt lịch tối nay nhé"
        );
        AgentBrainService.FactMutationRecord supersedeTime = brainService.applyStructuredFactObservation(
                spaPageId, spaSender, "slot_logistics_or_care=Đổi lịch hẹn sang chiều mai", "Cho mình đổi lịch từ tối nay sang chiều mai nha"
        );
        assertEquals(AgentBrainService.FactMemoryOperation.SUPERSEDE, supersedeTime.operation());

        // RETRACT vùng lưng
        AgentBrainService.FactMutationRecord retractLung = brainService.applyStructuredFactObservation(
                spaPageId, spaSender, "", "À thôi mình không làm vùng lưng nữa"
        );
        assertEquals(AgentBrainService.FactMemoryOperation.RETRACT, retractLung.operation());

        List<String> finalSpaFacts = brainService.getRecalledVerifiedFacts(spaPageId, spaSender);
        assertTrue(finalSpaFacts.stream().anyMatch(f -> f.contains("239k")));
        assertFalse(finalSpaFacts.stream().anyMatch(f -> f.contains("530k")), "Gói 530k đã bị SUPERSEDE bởi 239k");
        assertTrue(finalSpaFacts.stream().anyMatch(f -> f.contains("chiều mai")));
        assertFalse(finalSpaFacts.stream().anyMatch(f -> f.contains("tối nay")), "Lịch tối nay đã bị SUPERSEDE bởi chiều mai");
        assertFalse(finalSpaFacts.stream().anyMatch(f -> f.contains("lưng")), "Vùng lưng đã bị RETRACT");
        assertTrue(finalSpaFacts.stream().anyMatch(f -> f.contains("vai gáy")), "Vùng vai gáy vẫn còn hiệu lực");
    }

    @Test
    void testOperationalPivotAfterPriceObjection_LivingHypothesisRules_AndAntiBotPromotionalFluffGuard() {
        LlmClient mockLlm = mock(LlmClient.class);
        when(mockLlm.name()).thenReturn("ollama:qwen3.8-flash");
        AgentBrainService brainService = new AgentBrainService((com.example.docmind.client.AgentBrainClient) null);
        CleanAgentOrchestratorService orchestrator = new CleanAgentOrchestratorService(mockLlm, brainService, null);

        UUID pageId = UUID.randomUUID();
        String senderId = "sender-ops-pivot-c3a";
        PromptBuilder.PagePersona spaPersona = new PromptBuilder.PagePersona(
                "Nàng Sen Spa", "BEAUTY_SPA", "Văn phòng", "Chăm sóc", PagePersonaConfig.empty()
        );

        // 1. Câu quá ngắn khi chưa có ngữ cảnh ("Alo") -> INSUFFICIENT_EVIDENCE & archetype = UNKNOWN
        brainService.recallAndAdvise(pageId, senderId, "Chị Mai", "Alo", 0.5, 0.5, true, spaPersona);
        AgentBrainService.BrainReasoningProvenance firstProv = brainService.getLastReasoningProvenance(pageId, senderId);
        assertNotNull(firstProv);
        assertEquals("UNKNOWN", firstProv.buyerArchetype());
        assertEquals(AgentBrainService.HypothesisStatus.INSUFFICIENT_EVIDENCE, firstProv.hypothesisStatus());

        // 2. Khách chê đắt -> LivingHypothesis kích hoạt dựa trên bằng chứng lời khách
        brainService.recordCommercialFunnelState(pageId, senderId, 1, true, false);
        brainService.retainTurnWithReflection(
                pageId,
                senderId,
                "Chị Mai",
                "Giá 399k hơi cao nhỉ",
                "Dạ gói 399k làm trọn gói 60 phút vùng cổ vai gáy lưng và không nhận tip ạ.",
                List.of(),
                "slot_budget_sensitivity=Khách thấy giá 399k hơi cao",
                "",
                "Khách đang lấn cấn mức giá 399k ở lần báo giá đầu",
                CleanAgentOrchestratorService.SPEECH_ACT_INVITED_TRIAL_OR_TIME,
                List.of("cổ", "vai", "gáy"),
                "chuyên viên"
        );
        AgentBrainService.LivingHypothesisState hypAfterPriceObj = brainService.getLivingHypothesisState(pageId, senderId);
        assertEquals(AgentBrainService.HypothesisStatus.ACTIVE_NEW_EVIDENCE, hypAfterPriceObj.status());
        assertTrue(hypAfterPriceObj.confidence() >= 0.78);

        // 3. CẤM TUYỆT ĐỐI lấy câu quảng cáo của bot làm bằng chứng rằng khách có nhu cầu/cảm xúc đó!
        brainService.retainTurnWithReflection(
                pageId,
                senderId,
                "Chị Mai",
                "Gói đó gồm mấy bước?",
                "Dạ gói này sử dụng thảo dược quý hiếm độc quyền trong không gian thiền định hoàng gia sang trọng bậc nhất ạ.",
                List.of(),
                "",
                "",
                "Khách đam mê thảo dược quý hiếm độc quyền và không gian thiền định hoàng gia sang trọng",
                "",
                List.of("cổ", "vai", "gáy"),
                "chuyên viên"
        );
        AgentBrainService.LivingHypothesisState hypAfterFluffAttempt = brainService.getLivingHypothesisState(pageId, senderId);
        assertEquals(AgentBrainService.HypothesisStatus.REJECTED_BOT_PROMOTIONAL_FLUFF, hypAfterFluffAttempt.status(),
                "Phải chặn đứng giả thuyết bịa từ câu quảng cáo hoa mỹ của chính bot");
        assertFalse(hypAfterFluffAttempt.hypothesisText().contains("thảo dược quý hiếm"),
                "Không được lưu nội dung quảng cáo của bot vào workingHypothesis của khách");

        // 4. Khách hỏi câu vận hành "Tối mấy h đóng cửa" ngay sau khi chê đắt:
        //    - Hạ độ tin cậy hypothesis (DOWNGRADED_OPERATIONAL_PIVOT)
        //    - Bot trả lời đúng câu hỏi vận hành, KHÔNG bị ép nhồi downsell 239k!
        String kbText = "GIỜ MỞ CỬA: Mở cửa từ 09:00 sáng đến 21:30 tối tất cả các ngày trong tuần.\n\n" + SPA_KB_TABLE;
        List<ReciprocalRankFusion.FusedChunk> opsChunks = List.of(buildChunk("Thông tin vận hành & Bảng giá", kbText));

        AtomicReference<String> capturedOpsPrompt = new AtomicReference<>("");
        String opsReplyJson = """
                {
                  "reply_bubbles": [
                    {"text": "Dạ bên em mở cửa từ 9h00 sáng đến 21h30 tối tất cả các ngày trong tuần ạ.", "typing_delay_ms": 0}
                  ],
                  "brain_reflection": {
                    "observed_fact": "",
                    "care_note_delta": "",
                    "working_hypothesis": "Khách chuyển sang hỏi giờ đóng cửa buổi tối",
                    "intended_speech_act": "ANSWERED_OPERATIONAL_INFO"
                  },
                  "should_handoff": false
                }
                """;
        when(mockLlm.complete(anyString(), any(ReasoningBudget.class))).thenAnswer(inv -> {
            capturedOpsPrompt.set(inv.getArgument(0, String.class));
            return opsReplyJson;
        });
        when(mockLlm.complete(anyString())).thenAnswer(inv -> {
            capturedOpsPrompt.set(inv.getArgument(0, String.class));
            return opsReplyJson;
        });

        List<FanpageBotRequest.ConversationTurn> historyBeforeOps = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Mình bị mỏi cổ vai gáy"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ em gợi ý mình gói Massage cơ bản 60 phút giá 399k trọn gói ạ."),
                new FanpageBotRequest.ConversationTurn("user", "Giá 399k hơi cao nhỉ"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ gói 399k làm trọn gói 60 phút vùng cổ vai gáy lưng ạ.")
        );
        ConversationState preOps = ConversationState.hydrateForTurn(
                pageId, senderId, null, true, "Tối mấy h đóng cửa", historyBeforeOps, List.of("cổ", "vai", "gáy"), List.of()
        );
        ConversationState convOps = preOps.enrichWithCurrentMessage("Tối mấy h đóng cửa", List.of("cổ", "vai", "gáy"))
                .withRetrievedChunks(opsChunks, List.of("cổ", "vai", "gáy"), historyBeforeOps);

        FanpageBotResponse opsResp = orchestrator.executeCleanAgentTurn(
                pageId,
                new FanpageBotRequest(pageId.toString(), senderId, "Chị Mai", "Tối mấy h đóng cửa", null, historyBeforeOps, List.of(), 5),
                "Tối mấy h đóng cửa",
                "mình",
                spaPersona,
                preOps,
                convOps,
                opsChunks,
                List.of(),
                null,
                null,
                List.of(),
                List.of(),
                FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION,
                historyBeforeOps.size(),
                System.nanoTime(),
                8L
        );

        String opsUserPrompt = capturedOpsPrompt.get().substring(capturedOpsPrompt.get().indexOf("<!-- SYSTEM_USER_SPLIT -->"));
        assertFalse(opsUserPrompt.contains("STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL")
                        || opsUserPrompt.contains("STAGE_3_DOWNSELL_OR_REPEAT_OBJECTION"),
                "Câu hỏi vận hành 'Tối mấy h đóng cửa' sau khi chê đắt không được định tuyến vào stage downsell");
        assertFalse(opsResp.replyText().contains("239k"),
                "Bot trả lời giờ đóng cửa, không bị ép gạ gói tiết kiệm 239k");
        AgentBrainService.LivingHypothesisState hypAfterOps = brainService.getLivingHypothesisState(pageId, senderId);
        assertEquals(AgentBrainService.HypothesisStatus.DOWNGRADED_OPERATIONAL_PIVOT, hypAfterOps.status());
        assertTrue(hypAfterOps.confidence() < hypAfterPriceObj.confidence(),
                "Độ tin cậy của giả thuyết giá cũ phải được hạ xuống khi khách bẻ lái sang câu hỏi vận hành");

        // 5. Khách bác bỏ trực tiếp giả thuyết cũ -> xóa/đổi ngay lập tức
        brainService.retainTurnWithReflection(
                pageId,
                senderId,
                "Chị Mai",
                "Ai bảo em chị chê đắt, chị hỏi giờ đóng cửa để tối đi làm về ghé thôi!",
                "Dạ em xin lỗi chị vì đã hiểu chưa đúng ý chị ạ, buổi tối bên em mở đến 21h30 nên chị tan làm ghé rất thoải mái ạ.",
                List.of(),
                "",
                "",
                "",
                "",
                List.of("cổ", "vai", "gáy"),
                "chuyên viên"
        );
        AgentBrainService.LivingHypothesisState hypAfterRejection = brainService.getLivingHypothesisState(pageId, senderId);
        assertEquals(AgentBrainService.HypothesisStatus.REJECTED_BY_CUSTOMER, hypAfterRejection.status());
        assertEquals("", hypAfterRejection.hypothesisText());
    }

    @Test
    void testScopeMatcher_MultiIndustryDynamicMatchingAndRetraction_FromCatalogAndPersonaTaxonomy() {
        // 1. Ngành Vật liệu xây dựng / Gạch ốp lát
        String tileTableMd = """
                | Mã SP | Tên sản phẩm | Kích thước | Đơn giá | Khu vực phù hợp |
                | --- | --- | --- | --- | --- |
                | GL80 | Gạch Porcelain Vân Đá Marble | 80x80cm | 450k/m² | phòng khách, sảnh thương mại |
                | GO3060 | Gạch Ốp Tường Men Bóng | 30x60cm | 280k/m² | phòng bếp, phòng tắm |
                """;
        List<PackageCatalogService.PackageSummary> tileCatalog = PackageCatalogService.extractCatalog(
                List.of(buildChunk("Bảng giá Gạch ốp lát", tileTableMd)),
                List.of("phòng khách", "sảnh thương mại", "phòng bếp", "phòng tắm")
        );
        PagePersonaConfig tileConfig = PagePersonaConfig.fromJson("""
                {
                  "industry_code": "HOME_FURNITURE",
                  "bot_name": "Minh Phát Ceramics",
                  "self_pronoun": "em",
                  "default_customer_pronoun": "anh",
                  "care_actor": "kỹ thuật viên",
                  "scope_taxonomy": ["phòng khách", "phòng bếp", "phòng tắm", "hồ bơi kính"],
                  "query_synonyms": {
                    "hồ bơi kính": ["bể bơi vô cực", "hồ bơi"]
                  }
                }
                """);

        List<String> matchedTileScopes = ScopeMatcher.mergeScopes(
                "Anh cần tìm gạch cho phòng khách và bể bơi vô cực nhé",
                List.of(),
                tileCatalog,
                tileConfig
        );
        assertEquals(List.of("phòng khách", "hồ bơi kính"), matchedTileScopes,
                "ScopeMatcher phải đọc trực tiếp từ scopeTags + scopeTaxonomy + querySynonyms của Page gạch ốp lát");

        // Khi khách mở rộng thêm nhà bếp (ADD) rồi sau đó rút lại hồ bơi kính (RETRACT)
        List<String> expandedTileScopes = ScopeMatcher.mergeScopes(
                "Cho anh xem thêm gạch nhà bếp, à thôi bỏ hồ bơi kính nhé",
                matchedTileScopes,
                tileCatalog,
                tileConfig
        );
        assertEquals(List.of("phòng khách", "phòng bếp"), expandedTileScopes,
                "ScopeMatcher phải giữ phòng khách + thêm phòng bếp (từ 'nhà bếp') và thu hồi 'hồ bơi kính' khi khách RETRACT");

        // 2. Ngành Giáo dục ngoại ngữ (custom taxonomy từ PagePersonaConfig)
        PagePersonaConfig eduConfig = PagePersonaConfig.fromJson("""
                {
                  "industry_code": "EDUCATION",
                  "bot_name": "Apex English",
                  "self_pronoun": "mình",
                  "default_customer_pronoun": "bạn",
                  "care_actor": "giảng viên",
                  "scope_taxonomy": ["ielts academic", "tiếng anh thương mại", "phát âm chuẩn ipa"],
                  "query_synonyms": {
                    "tiếng anh thương mại": ["viết email công việc", "đàm phán đối tác"]
                  }
                }
                """);
        List<String> matchedEduScopes = ScopeMatcher.mergeScopes(
                "Mình muốn cải thiện phát âm chuẩn IPA và kỹ năng viết email công việc",
                List.of(),
                List.of(),
                eduConfig
        );
        assertEquals(List.of("phát âm chuẩn ipa", "tiếng anh thương mại"), matchedEduScopes);
    }
}
