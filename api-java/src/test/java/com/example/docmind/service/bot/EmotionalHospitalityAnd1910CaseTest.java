package com.example.docmind.service.bot;

import com.example.docmind.dto.PagePersonaConfig;
import com.example.docmind.dto.bot.AgentSkillDto;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Kiểm chứng toàn diện:
 * 1. Truy nguyên & sửa triệt để ca thực tế 19:10–19:22 trên Nàng Sen Spa
 *    (Khám phá phạm vi ưu tiên -> Báo giá -> Chê đắt + Conversational Delta -> Ngập ngừng -> So sánh 2 gói -> Gật đầu "uk" tiến tới AWAITING_DATE_OR_TIME, không rơi về STAGE_6).
 * 2. Ba ngữ cảnh câu trả lời ngắn ("uk", "ừ", "ok", "cũng được"):
 *    - Ngữ cảnh A: Sau lời mời trải nghiệm / gợi ý 1 sản phẩm khớp nhu cầu -> STAGE_4 + khóa sản phẩm + AWAITING_DATE_OR_TIME.
 *    - Ngữ cảnh B: Sau thông tin / báo giá chung -> STAGE_1 cầu nối ấm áp (không ép chốt lịch STAGE_4, không ngắt ngang STAGE_6).
 *    - Ngữ cảnh C: Sau đề xuất khung giờ cụ thể -> STAGE_4 + kế thừa khung giờ + AWAITING_AVAILABILITY_CHECK.
 * 3. Kiểm chứng song song đa ngành trên Nha Khoa Tỏa Sáng (DENTAL_CLINIC):
 *    Đúng 1 focal skill giàu EQ mỗi lượt, 0% từ vựng Spa rò rỉ sang Prompt Nha khoa, chặn cam kết cực đoan sai sự thật nhưng cho phép ngôn từ giàu cảm xúc.
 */
class EmotionalHospitalityAnd1910CaseTest {

    private static final UUID NANG_SEN_PAGE_ID = UUID.fromString("b23cd382-4f7a-4829-8c54-1d410da14bf8");
    private static final UUID DENTAL_PAGE_ID = UUID.fromString("d3000000-0000-0000-0000-000000000001");

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

    private static final String NANG_SEN_STANDARDIZED_KB = """
            | Mã gói | Tên gói | Thời lượng | Giá | Bao gồm | Không gian | Vùng chăm sóc | Vai trò thương mại |
            | --- | --- | --- | --- | --- | --- | --- | --- |
            | NS01 | Massage cơ bản | 60 phút | 399k | Massage trị liệu toàn thân + buffet thực dưỡng | Không gian tĩnh lặng, mỗi giường đều có rèm che riêng kín đáo | đầu, cổ, vai, gáy, lưng, toàn thân | gói chính |
            | DS02 | Gói dưỡng sinh cơ bản | 60 phút | 239k | Gội đầu dưỡng sinh + massage cổ vai gáy + ăn nhẹ thực dưỡng | Không gian tĩnh lặng, mỗi giường đều có rèm che riêng kín đáo | đầu, cổ, vai, gáy | gói tiết kiệm |
            """;

    private static final String DENTAL_KB = """
            | Mã dịch vụ | Tên dịch vụ | Quy cách | Đơn giá | Quyền lợi đi kèm | Hình thức thực hiện | Phạm vi phù hợp | Vai trò thương mại |
            | --- | --- | --- | --- | --- | --- | --- | --- |
            | NK-SU-ZIR | Răng toàn sứ thẩm mỹ Zirconia chính hãng Đức | 2 buổi hẹn | 3.500k/răng | Chụp phim CT ConeBeam + thiết kế dáng răng + thẻ bảo hành chính hãng | Ghế nha riêng vô trùng, bác sĩ phục hình trực tiếp thực hiện êm ái | răng cửa, răng hàm, toàn hàm | gói chính |
            | NK-SU-TITAN | Răng sứ kim loại Titan tiết kiệm | 2 buổi hẹn | 1.500k/răng | Thăm khám khớp cắn + phục hình sứ khung Titan chịu lực ăn nhai tốt | Ghế nha vô trùng tiêu chuẩn, bác sĩ kiểm soát tê nhẹ nhàng | răng hàm | gói tiết kiệm |
            """;

    private static PromptBuilder.PagePersona buildNangSenPersona() {
        return new PromptBuilder.PagePersona(
                "Nàng Sen Spa",
                "BEAUTY_SPA",
                "Khách văn phòng, người tập thể thao cần phục hồi cơ",
                "Ấm áp, tinh tế, nâng niu cảm xúc thư thái",
                PagePersonaConfig.fromJson("""
                        {
                          "industry_code": "BEAUTY_SPA",
                          "bot_name": "Lễ tân Nàng Sen",
                          "self_pronoun": "em",
                          "default_customer_pronoun": "mình",
                          "care_actor": "kỹ thuật viên",
                          "unit_noun": "gói",
                          "scope_taxonomy": ["đầu", "cổ", "vai", "gáy", "lưng", "toàn thân"]
                        }
                        """)
        );
    }

    private static PromptBuilder.PagePersona buildDentalPersona() {
        return new PromptBuilder.PagePersona(
                "Nha Khoa Tỏa Sáng",
                "DENTAL_CLINIC",
                "Khách hàng cần thẩm mỹ nụ cười và phục hình răng êm ái",
                "Trấn an nhẹ nhàng, tận tâm, chuyên môn rõ ràng",
                PagePersonaConfig.fromJson("""
                        {
                          "industry_code": "DENTAL_CLINIC",
                          "bot_name": "Minh Anh",
                          "self_pronoun": "em",
                          "default_customer_pronoun": "mình",
                          "care_actor": "bác sĩ",
                          "unit_noun": "dòng dịch vụ",
                          "scope_taxonomy": ["răng cửa", "răng hàm", "toàn hàm"]
                        }
                        """)
        );
    }

    private static List<AgentSkillDto> buildSixCanonicalSkills(UUID pageId, boolean dental) {
        String p = pageId.toString();
        java.time.Instant now = java.time.Instant.now();
        return List.of(
                new AgentSkillDto(UUID.randomUUID(), "discovery_empathy_and_scope", "Đồng cảm & Khám phá phạm vi", "PSYCHOLOGY", "",
                        "SHARING_CONDITION_OR_CONTEXT,ACKNOWLEDGED_GENERAL_INFO,LUKEWARM_POST_QUOTE_CHECKIN",
                        dental
                                ? "[Tình huống tâm lý khách] Khách lo ê buốt sợ đau -> [Nghệ thuật dẫn dắt & Ranh giới sự thật] Trấn an thao tác bác sĩ êm ái và hỏi nhẹ vùng răng cửa hay răng hàm cần ưu tiên -> [Các hướng diễn đạt gợi cảm xúc theo ngữ cảnh] Bác sĩ kiểm soát tê rất êm, hiện mình cần ưu tiên vùng răng nào ạ?"
                                : "[Tình huống tâm lý khách] Khách căng mỏi cơ sau tập nặng -> [Nghệ thuật dẫn dắt & Ranh giới sự thật] Vỗ về cảm giác căng cơ và hỏi nhẹ vùng mỏi ưu tiên -> [Các hướng diễn đạt gợi cảm xúc theo ngữ cảnh] Tập nặng về chắc thớ cơ căng lắm, hiện mình mỏi nhất vùng nào ạ?",
                        "", "", 98, true, p, 0, 0, 0.96, now, now),
                new AgentSkillDto(UUID.randomUUID(), "price_objection_sensory_value", "Thấu cảm giá & Giá trị trải nghiệm", "PSYCHOLOGY", "",
                        "PRICE_OBJECTION_FIRST_TIME,PRICE_OBJECTION",
                        dental
                                ? "[Tình huống tâm lý khách] Khách thấy chi phí cao -> [Nghệ thuật dẫn dắt & Ranh giới sự thật] Không lặp lại thông số phụ, khắc họa độ trong men sứ và khớp cắn êm ái -> [Các hướng diễn đạt gợi cảm xúc theo ngữ cảnh] Đường viền sứ ôm êm nướu giúp ăn nhai nhẹ tênh."
                                : "[Tình huống tâm lý khách] Khách thấy giá hơi cao -> [Nghệ thuật dẫn dắt & Ranh giới sự thật] Không nhai lại tiện ích phụ, khắc họa kỹ thuật viên ấn mở từng thớ cơ căng mỏi -> [Các hướng diễn đạt gợi cảm xúc theo ngữ cảnh] Thả lỏng sâu giúp tối về dễ ngủ êm hơn.",
                        "", "", 97, true, p, 0, 0, 0.96, now, now),
                new AgentSkillDto(UUID.randomUUID(), "soft_economical_bridge_and_compare", "Nhịp cầu lựa chọn vừa vặn & So sánh tinh tế", "SALES_STRATEGY", "",
                        "PRICE_HESITATION_AFTER_VALUE_DEFENSE,PRICE_OBJECTION_REPEAT_OR_DOWNSELL_REQUEST,SERVICE_OR_UNIT_DETAIL_QUESTION",
                        dental
                                ? "[Tình huống tâm lý khách] Khách ngập ngừng hoặc hỏi so sánh -> [Nghệ thuật dẫn dắt & Ranh giới sự thật] Bắc nhịp cầu sang dòng vật liệu vừa ngân sách cho răng hàm, không dìm hàng -> [Các hướng diễn đạt gợi cảm xúc theo ngữ cảnh] Cả hai dòng đều được bác sĩ căn chỉnh khớp cắn êm ái."
                                : "[Tình huống tâm lý khách] Khách ngập ngừng hoặc hỏi so sánh -> [Nghệ thuật dẫn dắt & Ranh giới sự thật] Bắc nhịp cầu sang lựa chọn nhẹ nhàng tập trung cổ vai gáy trong không gian tĩnh lặng có rèm che riêng kín đáo -> [Các hướng diễn đạt gợi cảm xúc theo ngữ cảnh] Không dìm hàng gói nhỏ.",
                        "", "", 96, true, p, 0, 0, 0.95, now, now),
                new AgentSkillDto(UUID.randomUUID(), "warm_nod_to_gentle_scheduling", "Đón nhận cái gật đầu ấm áp & Mời chọn giờ", "COMMUNICATION", "",
                        "POSITIVE_CONFIRMATION_SIGNAL,POSITIVE_INTEREST_READY_FOR_TRIAL_INVITE,PACKAGE_ACCEPTANCE_OR_BOOKING_PROGRESS",
                        "[Tình huống tâm lý khách] Khách gật đầu uk đồng ý -> [Nghệ thuật dẫn dắt & Ranh giới sự thật] Đón nhận ấm áp và hỏi khung giờ thuận tiện hôm nay hoặc ngày mai -> [Các hướng diễn đạt gợi cảm xúc theo ngữ cảnh] Không chúc ngủ ngon bỏ rơi khách.",
                        "", "", 95, true, p, 0, 0, 0.97, now, now),
                new AgentSkillDto(UUID.randomUUID(), "booking_careful_preparation", "Chuẩn bị chu đáo & Kiểm tra lịch trống", "SALES_STRATEGY", "",
                        "ACCEPTED_PROPOSED_TIME_SLOT,POST_BOOKING_CLOSING_ACK,PACKAGE_ACCEPTANCE_OR_BOOKING_PROGRESS",
                        "[Tình huống tâm lý khách] Khách chốt khung giờ hẹn -> [Nghệ thuật dẫn dắt & Ranh giới sự thật] Ghi nhận chu đáo và hẹn kiểm tra lịch trống thực tế -> [Các hướng diễn đạt gợi cảm xúc theo ngữ cảnh] Phản hồi xác nhận ngay cho khách.",
                        "", "", 94, true, p, 0, 0, 0.96, now, now),
                new AgentSkillDto(UUID.randomUUID(), "graceful_pause_keep_warmth", "Tôn trọng khoảng nghỉ & Giữ hơi ấm", "COMMUNICATION", "",
                        "POLITE_CLOSING_ACKNOWLEDGMENT,HESITATION_OR_DEFERRAL",
                        "[Tình huống tâm lý khách] Khách muốn suy nghĩ thêm -> [Nghệ thuật dẫn dắt & Ranh giới sự thật] Thoải mái không áp lực, chỉ lưu phương án đã bàn -> [Các hướng diễn đạt gợi cảm xúc theo ngữ cảnh] Chúc khách ấm áp để luôn muốn quay lại.",
                        "", "", 93, true, p, 0, 0, 0.96, now, now)
        );
    }

    private static FanpageBotResponse executeTurn(
            CleanAgentOrchestratorService orchestrator,
            LlmClient mockLlm,
            UUID pageId,
            String senderId,
            String convId,
            String userMessage,
            List<FanpageBotRequest.ConversationTurn> history,
            PromptBuilder.PagePersona persona,
            List<ReciprocalRankFusion.FusedChunk> fusedChunks,
            List<AgentSkillDto> candidateSkills,
            String mockLlmJson,
            AtomicReference<String> capturedPromptOut
    ) {
        List<String> taxonomy = persona.resolvedConfig().resolvedScopeTaxonomy();
        ConversationState preState = ConversationState.hydrateForTurn(
                pageId, senderId, convId, false, userMessage, history, taxonomy, List.of()
        );
        ConversationState convState = preState.enrichWithCurrentMessage(userMessage, taxonomy)
                .withRetrievedChunks(fusedChunks, taxonomy, history);

        when(mockLlm.complete(anyString(), any(ReasoningBudget.class))).thenAnswer(inv -> {
            if (capturedPromptOut != null) {
                capturedPromptOut.set(inv.getArgument(0, String.class));
            }
            return mockLlmJson;
        });
        when(mockLlm.complete(anyString())).thenAnswer(inv -> {
            if (capturedPromptOut != null) {
                capturedPromptOut.set(inv.getArgument(0, String.class));
            }
            return mockLlmJson;
        });

        FanpageBotRequest req = new FanpageBotRequest(
                pageId.toString(),
                senderId,
                "fb-customer-demo",
                userMessage,
                null,
                history,
                List.of(),
                5,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                convId,
                false
        );
        return orchestrator.executeCleanAgentTurn(
                pageId,
                req,
                userMessage,
                "mình",
                persona,
                preState,
                convState,
                fusedChunks,
                List.of(),
                null,
                null,
                candidateSkills,
                List.of(),
                FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION,
                history.size(),
                System.nanoTime(),
                8L
        );
    }

    @Test
    void testCase1910To1922_FullNangSenTrajectory_DiscoveryDeltaCompareAndWarmNodUk() {
        LlmClient mockLlm = mock(LlmClient.class);
        when(mockLlm.name()).thenReturn("ollama:qwen3.8-flash");
        AgentBrainService brainService = new AgentBrainService();
        CleanAgentOrchestratorService orchestrator = new CleanAgentOrchestratorService(mockLlm, brainService, null);

        String senderId = "fb-customer-demo-1910";
        String convId = "bd37304c-cb05-4f40-9560-401544430ef6";
        PromptBuilder.PagePersona persona = buildNangSenPersona();
        List<ReciprocalRankFusion.FusedChunk> chunks = List.of(buildChunk("Bảng giá Nàng Sen Spa", NANG_SEN_STANDARDIZED_KB));
        List<AgentSkillDto> skills = buildSixCanonicalSkills(NANG_SEN_PAGE_ID, false);
        List<FanpageBotRequest.ConversationTurn> history = new ArrayList<>();
        history.add(new FanpageBotRequest.ConversationTurn("user", "tư vấn"));
        history.add(new FanpageBotRequest.ConversationTurn("assistant", "Dạ chào mình, em là lễ tân tại Nàng Sen Spa ạ. Hôm nay cơ thể mình đang đau mỏi vùng nào để em hỗ trợ thông tin phù hợp nhất ạ?"));

        // 1. Lượt 19:11 — "tôi đi tập nặng về dãn cơ đau mỏi khớp" (chưa nêu vùng ưu tiên, chưa đòi giá ngay)
        AtomicReference<String> prompt1911 = new AtomicReference<>();
        FanpageBotResponse resp1911 = executeTurn(
                orchestrator, mockLlm, NANG_SEN_PAGE_ID, senderId, convId,
                "tôi đi tập nặng về dãn cơ đau mỏi khớp",
                history, persona, chunks, skills,
                """
                {"bubbles":[
                  "Dạ đi tập nặng về mà cơ khớp còn đang căng mỏi chắc người mình oải lắm ạ.",
                  "Để em chọn đúng liệu trình thả lỏng vừa vặn nhất, hiện tại mình đang nhức mỏi nhiều ở vùng cổ vai gáy, lưng hay muốn giãn cơ toàn thân luôn ạ?"
                ]}
                """,
                prompt1911
        );
        assertNotNull(prompt1911.get());
        assertTrue(prompt1911.get().contains("discovery_completed: false"),
                "19:11 khi chưa rõ vùng mỏi ưu tiên và chưa hỏi giá thì discovery_completed phải là false");
        assertTrue(prompt1911.get().contains("TẠM ẨN danh sách báo giá ở lượt này"),
                "19:11 phải vỗ về và hỏi vùng mỏi ưu tiên trước thay vì xổ bảng giá ngay");
        assertEquals(1, resp1911.activeSkills().size(), "Mỗi lượt chỉ nạp duy nhất 1 focal skill");
        assertEquals("discovery_empathy_and_scope", resp1911.activeSkills().get(0).code());

        history.add(new FanpageBotRequest.ConversationTurn("user", "tôi đi tập nặng về dãn cơ đau mỏi khớp"));
        history.add(new FanpageBotRequest.ConversationTurn("assistant",
                "Dạ đi tập nặng về mà cơ khớp còn đang căng mỏi chắc người mình oải lắm ạ. Hiện tại mình đang nhức mỏi nhiều ở vùng cổ vai gáy, lưng hay muốn giãn cơ toàn thân luôn ạ?"));

        // Khách trả lời muốn làm toàn thân -> Báo giá gói chủ lực 399k với mô tả không gian ấm cúng
        AtomicReference<String> promptQuote = new AtomicReference<>();
        FanpageBotResponse respQuote = executeTurn(
                orchestrator, mockLlm, NANG_SEN_PAGE_ID, senderId, convId,
                "mình mỏi cả người muốn làm toàn thân",
                history, persona, chunks, skills,
                """
                {"bubbles":[
                  "Dạ mỏi cả người sau tập nặng thì gói Massage cơ bản 60 phút (399k) giúp thả lỏng toàn thân rất hợp với mình ạ.",
                  "Gói được thực hiện trong không gian tĩnh lặng, mỗi giường đều có rèm che riêng kín đáo và có kèm buffet thực dưỡng nhẹ bụng sau buổi làm. Mình thấy hướng chăm sóc toàn thân này thế nào ạ?"
                ]}
                """,
                promptQuote
        );
        assertTrue(promptQuote.get().contains("discovery_completed: true"));
        assertTrue(promptQuote.get().contains("Không gian tĩnh lặng, mỗi giường đều có rèm che riêng kín đáo"));
        assertFalse(promptQuote.get().toLowerCase().contains("phòng chung"));
        assertEquals(1, respQuote.activeSkills().size());

        history.add(new FanpageBotRequest.ConversationTurn("user", "mình mỏi cả người muốn làm toàn thân"));
        history.add(new FanpageBotRequest.ConversationTurn("assistant",
                "Dạ mỏi cả người sau tập nặng thì gói Massage cơ bản 60 phút (399k) giúp thả lỏng toàn thân rất hợp với mình ạ. Gói được thực hiện trong không gian tĩnh lặng, mỗi giường đều có rèm che riêng kín đáo và có kèm buffet thực dưỡng nhẹ bụng sau buổi làm. Mình thấy hướng chăm sóc toàn thân này thế nào ạ?"));

        // 2. Lượt 19:12 — "uk hơi đắt" -> STAGE_2 + Conversational Delta + price_objection_sensory_value
        AtomicReference<String> prompt1912 = new AtomicReference<>();
        FanpageBotResponse resp1912 = executeTurn(
                orchestrator, mockLlm, NANG_SEN_PAGE_ID, senderId, convId,
                "uk hơi đắt",
                history, persona, chunks, skills,
                """
                {"bubbles":[
                  "Dạ em hiểu cảm giác mình cân nhắc ạ. Khác với việc chỉ xoa bóp lướt qua bên ngoài, kỹ thuật viên bên em dành trọn 60 phút miết ấm và ấn mở tỉ mỉ từng thớ cơ đang căng cứng sau buổi tập nặng để cơ thể thực sự được nghỉ ngơi sâu.",
                  "Với tình trạng căng cơ hôm nay, mình đang mong cảm giác giãn sâu vùng cơ mỏi hay thư thái nhẹ đầu để tối dễ ngủ hơn ạ?"
                ]}
                """,
                prompt1912
        );
        assertTrue(prompt1912.get().contains("STAGE_2_FIRST_PRICE_OBJECTION_HOLD_VALUE"));
        assertTrue(prompt1912.get().contains("6. Emotional Hospitality & Conversational Delta:"));
        assertTrue(prompt1912.get().contains(CleanAgentCatalogAndPromptBuilder.EMOTIONAL_HOSPITALITY_CONTRACT_DIRECTIVE));
        assertTrue(prompt1912.get().contains("CONVERSATIONAL_DELTA_ACTIVE"));
        assertEquals(1, resp1912.activeSkills().size());
        assertEquals("price_objection_sensory_value", resp1912.activeSkills().get(0).code());

        history.add(new FanpageBotRequest.ConversationTurn("user", "uk hơi đắt"));
        history.add(new FanpageBotRequest.ConversationTurn("assistant",
                "Dạ em hiểu cảm giác mình cân nhắc ạ. Kỹ thuật viên bên em dành trọn 60 phút ấn mở tỉ mỉ từng thớ cơ đang căng cứng sau buổi tập nặng để cơ thể thực sự được nghỉ ngơi sâu ạ."));

        // 3. Lượt ngập ngừng "ok xem đã" -> STAGE_3B mở nhịp cầu mềm sang gói 239k
        AtomicReference<String> promptHesitate = new AtomicReference<>();
        FanpageBotResponse respHesitate = executeTurn(
                orchestrator, mockLlm, NANG_SEN_PAGE_ID, senderId, convId,
                "ok xem đã",
                history, persona, chunks, skills,
                """
                {"bubbles":[
                  "Dạ không sao đâu ạ, mình cứ thong thả cân nhắc thêm, chưa cần vội chốt ngay đâu ạ.",
                  "Nếu mình muốn bắt đầu bằng một lựa chọn nhẹ nhàng hơn tập trung vùng đầu – cổ vai gáy trước thì bên em cũng có Gói dưỡng sinh cơ bản 60 phút (239k), mình xem thử có vừa ý hơn không nhé ạ."
                ]}
                """,
                promptHesitate
        );
        assertTrue(promptHesitate.get().contains("STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL"));
        assertEquals(1, respHesitate.activeSkills().size());
        assertEquals("soft_economical_bridge_and_compare", respHesitate.activeSkills().get(0).code());

        history.add(new FanpageBotRequest.ConversationTurn("user", "ok xem đã"));
        history.add(new FanpageBotRequest.ConversationTurn("assistant",
                "Dạ không sao đâu ạ, mình cứ thong thả cân nhắc thêm ạ. Nếu mình muốn bắt đầu bằng một lựa chọn nhẹ nhàng hơn tập trung vùng đầu – cổ vai gáy trước thì bên em cũng có Gói dưỡng sinh cơ bản 60 phút (239k) nhé ạ."));

        // 4. Lượt so sánh "nó khác j so với gói trước" -> Cả NS01 và DS02 cùng có trong <SCOPE_FILTERED_CATALOG>, 0 "phòng chung"
        AtomicReference<String> promptCompare = new AtomicReference<>();
        FanpageBotResponse respCompare = executeTurn(
                orchestrator, mockLlm, NANG_SEN_PAGE_ID, senderId, convId,
                "nó khác j so với gói trước",
                history, persona, chunks, skills,
                """
                {"bubbles":[
                  "Dạ hai gói khác nhau chính ở phạm vi vùng cơ chăm sóc: gói Massage cơ bản 60 phút (399k) thả lỏng trọn vẹn toàn thân, còn Gói dưỡng sinh cơ bản 60 phút (239k) tập trung gội dưỡng sinh kết hợp giải tỏa căng mỏi vùng đầu – cổ vai gáy ạ.",
                  "Cả hai gói đều nằm trong không gian tĩnh lặng, mỗi giường đều có rèm che riêng kín đáo và được chăm chút rất kỹ. Hiện mình đang mỏi tập trung vùng trên hay cả lưng chân hơn ạ?"
                ]}
                """,
                promptCompare
        );
        assertTrue(promptCompare.get().contains("id=NS01") && promptCompare.get().contains("id=DS02"),
                "Lượt hỏi so sánh phải cung cấp đủ cả 2 gói NS01 và DS02 trong allowed_4layer_products");
        assertEquals(1, respCompare.activeSkills().size());
        assertEquals("soft_economical_bridge_and_compare", respCompare.activeSkills().get(0).code());

        history.add(new FanpageBotRequest.ConversationTurn("user", "nó khác j so với gói trước"));
        history.add(new FanpageBotRequest.ConversationTurn("assistant",
                "Dạ hai gói khác nhau ở phạm vi vùng cơ chăm sóc: gói 399k làm toàn thân còn gói 239k tập trung vùng đầu – cổ vai gáy trong không gian tĩnh lặng có rèm che riêng kín đáo ạ. Hiện mình đang mỏi vùng nào hơn ạ?"));

        // 5. Lượt 19:21 — "chủ yếu vùng vai gáy với đầu" -> Bot mời thử 1 buổi gói 239k
        AtomicReference<String> prompt1921 = new AtomicReference<>();
        FanpageBotResponse resp1921 = executeTurn(
                orchestrator, mockLlm, NANG_SEN_PAGE_ID, senderId, convId,
                "chủ yếu vùng vai gáy với đầu",
                history, persona, chunks, skills,
                """
                {"bubbles":[
                  "Dạ tập trung vùng vai gáy với đầu thì Gói dưỡng sinh cơ bản 60 phút (239k) đúng ngay vùng mình đang cần giải tỏa nhất rồi ạ.",
                  "Mình thử một buổi xem cơ vai gáy có nhẹ đi không nhé?"
                ]}
                """,
                prompt1921
        );
        assertNotNull(resp1921);
        assertTrue(CleanAgentOrchestratorService.isInviteTrialOrAskTimeBotReply(
                "Dạ tập trung vùng vai gáy với đầu thì Gói dưỡng sinh cơ bản 60 phút (239k) đúng ngay vùng mình đang cần giải tỏa nhất rồi ạ. Mình thử một buổi xem cơ vai gáy có nhẹ đi không nhé?"
        ), "Câu mời 'Mình thử một buổi xem cơ vai gáy có nhẹ đi không nhé?' bắt buộc phải được nhận diện là SPEECH_ACT_INVITED_TRIAL_OR_TIME");

        history.add(new FanpageBotRequest.ConversationTurn("user", "chủ yếu vùng vai gáy với đầu"));
        history.add(new FanpageBotRequest.ConversationTurn("assistant",
                "Dạ tập trung vùng vai gáy với đầu thì Gói dưỡng sinh cơ bản 60 phút (239k) đúng ngay vùng mình đang cần giải tỏa nhất rồi ạ. Mình thử một buổi xem cơ vai gáy có nhẹ đi không nhé?"));

        // 6. Lượt 19:22 — Khách gật đầu "uk" (Ngữ cảnh A) -> TUYỆT ĐỐI KHÔNG RƠI VỀ STAGE_6!
        // Phải tiến tới STAGE_4_POSITIVE_TRIAL_OR_BOOKING + selected_product_id="DS02" + AWAITING_DATE_OR_TIME + warm_nod_to_gentle_scheduling
        AtomicReference<String> prompt1922 = new AtomicReference<>();
        FanpageBotResponse resp1922 = executeTurn(
                orchestrator, mockLlm, NANG_SEN_PAGE_ID, senderId, convId,
                "uk",
                history, persona, chunks, skills,
                """
                {"bubbles":[
                  "Dạ vâng ạ, vậy để em chuẩn bị Gói dưỡng sinh cơ bản 60 phút (239k) với góc giường tĩnh lặng có rèm riêng ấm cúng đón mình nhé.",
                  "Hôm nay hay ngày mai mình tiện ghé khoảng mấy giờ để bên em sắp xếp kỹ thuật viên chu đáo nhất cho mình ạ?"
                ]}
                """,
                prompt1922
        );
        assertNotNull(prompt1922.get());
        assertNotEquals("STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE", resp1922.salesStage(),
                "Lượt 19:22 ('uk' sau lời mời thử gói 239k) TUYỆT ĐỐI KHÔNG được rơi về STAGE_6 bỏ rơi khách!");
        assertFalse(prompt1922.get().contains("stage: \"STAGE_6_POLITE_DEFERRAL_RESPECT_PAUSE\""),
                "Lượt 19:22 ('uk' sau lời mời thử gói 239k) TUYỆT ĐỐI KHÔNG được rơi về STAGE_6 bỏ rơi khách!");
        assertTrue(prompt1922.get().contains("stage: \"STAGE_4_POSITIVE_TRIAL_OR_BOOKING\""),
                "Lượt 19:22 ('uk') phải tiến tới STAGE_4_POSITIVE_TRIAL_OR_BOOKING: " + prompt1922.get());
        assertTrue(prompt1922.get().contains("selected_product_id=\"DS02\""),
                "Lượt 19:22 ('uk') phải khóa đúng gói DS02 (239k) vừa mời ở lượt 19:21: " + prompt1922.get());
        assertTrue(prompt1922.get().contains("status=AWAITING_DATE_OR_TIME"),
                "Lượt 19:22 ('uk') phải chuyển sang trạng thái AWAITING_DATE_OR_TIME để hỏi khung giờ thuận tiện: " + prompt1922.get());
        assertEquals(1, resp1922.activeSkills().size(), "Mỗi lượt chỉ nạp đúng 1 focal skill");
        assertEquals("warm_nod_to_gentle_scheduling", resp1922.activeSkills().get(0).code());
        String reply1922Combined = resp1922.replyText().toLowerCase();
        assertFalse(reply1922Combined.contains("chúc mình ngủ ngon") || reply1922Combined.contains("khi nào tiện cứ nhắn"),
                "Tuyệt đối không chúc ngủ ngon rồi đẩy trách nhiệm nhắn lại cho khách khi khách vừa gật đầu 'uk'");
    }

    @Test
    void testShortAffirmativeContextB_WarmGeneralBridge_AndContextC_ProposedSlotAvailabilityCheck() {
        LlmClient mockLlm = mock(LlmClient.class);
        when(mockLlm.name()).thenReturn("ollama:qwen3.8-flash");
        AgentBrainService brainService = new AgentBrainService();
        CleanAgentOrchestratorService orchestrator = new CleanAgentOrchestratorService(mockLlm, brainService, null);
        PromptBuilder.PagePersona persona = buildNangSenPersona();
        List<ReciprocalRankFusion.FusedChunk> chunks = List.of(buildChunk("Bảng giá Nàng Sen Spa", NANG_SEN_STANDARDIZED_KB));
        List<AgentSkillDto> skills = buildSixCanonicalSkills(NANG_SEN_PAGE_ID, false);

        // Ngữ cảnh B: Bot vừa báo thông tin/giá chung (không mời thử gói / không đề xuất giờ), khách đáp "uk"
        List<FanpageBotRequest.ConversationTurn> historyB = List.of(
                new FanpageBotRequest.ConversationTurn("user", "cho mình xem giá gói toàn thân"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ gói Massage cơ bản toàn thân 60 phút bên em hiện có mức giá niêm yết là 399k trọn gói, bao gồm buffet thực dưỡng nhẹ bụng trong không gian tĩnh lặng có rèm che riêng kín đáo ạ.")
        );
        AtomicReference<String> promptB = new AtomicReference<>();
        FanpageBotResponse respB = executeTurn(
                orchestrator, mockLlm, NANG_SEN_PAGE_ID, "sender-ctx-b", "conv-ctx-b",
                "uk",
                historyB, persona, chunks, skills,
                """
                {"bubbles":[
                  "Dạ nghe qua liệu trình Massage cơ bản 60 phút (399k) này thì mình thấy có hợp với nhu cầu thư giãn hiện tại không, hay mình còn đang băn khoăn điểm nào để em chia sẻ kỹ hơn cho mình ạ?"
                ]}
                """,
                promptB
        );
        assertTrue(promptB.get().contains("stage: \"STAGE_1_DISCOVERY_AND_FIRST_QUOTE\""),
                "Ngữ cảnh B ('uk' tiếp nhận thông tin chung) phải giữ nhịp ở STAGE_1, không ép chốt STAGE_4 và không cắt ngang STAGE_6");
        assertTrue(promptB.get().contains("KEEP_WARM_CONVERSATIONAL_BRIDGE_WITHOUT_PRESSURE"));
        assertEquals("discovery_empathy_and_scope", respB.activeSkills().get(0).code());

        // Ngữ cảnh C: Bot vừa đề xuất khung giờ cụ thể, khách đáp "uk" -> AWAITING_AVAILABILITY_CHECK
        List<FanpageBotRequest.ConversationTurn> historyC = List.of(
                new FanpageBotRequest.ConversationTurn("user", "chiều nay mình muốn đặt Gói dưỡng sinh cơ bản 239k"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ chiều nay khung 16h30 bên em khá yên tĩnh cho Gói dưỡng sinh cơ bản 60 phút (239k), mình ghé lúc 16h30 chiều nay nhé?")
        );
        AtomicReference<String> promptC = new AtomicReference<>();
        FanpageBotResponse respC = executeTurn(
                orchestrator, mockLlm, NANG_SEN_PAGE_ID, "sender-ctx-c", "conv-ctx-c",
                "uk",
                historyC, persona, chunks, skills,
                """
                {"bubbles":[
                  "Dạ em xin ghi nhận yêu cầu đặt Gói dưỡng sinh cơ bản 60 phút (239k) vào hôm nay lúc 16:30 của mình ạ.",
                  "Để em báo lễ tân kiểm tra chỗ trống thực tế khung giờ này rồi xác nhận chính thức ngay cho mình nhé ạ."
                ]}
                """,
                promptC
        );
        assertTrue(promptC.get().contains("stage: \"STAGE_4_POSITIVE_TRIAL_OR_BOOKING\""));
        assertTrue(promptC.get().contains("status=AWAITING_AVAILABILITY_CHECK"),
                "Ngữ cảnh C ('uk' sau đề xuất giờ cụ thể) phải chuyển sang AWAITING_AVAILABILITY_CHECK: " + promptC.get());
        assertTrue(promptC.get().contains("selected_product_id=\"DS02\""));
        assertTrue(promptC.get().contains("booking_time=\"16:30\""));
        assertEquals("booking_careful_preparation", respC.activeSkills().get(0).code());
    }

    @Test
    void testParallelMultiIndustryTrajectory_NhaKhoaToaSang_ZeroSpaVocabularyLeakageAndMedicalSafetyBounds() {
        LlmClient mockLlm = mock(LlmClient.class);
        when(mockLlm.name()).thenReturn("ollama:qwen3.8-flash");
        AgentBrainService brainService = new AgentBrainService();
        CleanAgentOrchestratorService orchestrator = new CleanAgentOrchestratorService(mockLlm, brainService, null);

        PromptBuilder.PagePersona dentalPersona = buildDentalPersona();
        List<ReciprocalRankFusion.FusedChunk> dentalChunks = List.of(buildChunk("Bảng giá Nha Khoa Tỏa Sáng", DENTAL_KB));
        List<AgentSkillDto> dentalSkills = buildSixCanonicalSkills(DENTAL_PAGE_ID, true);
        String senderId = "dental-demo-customer";
        String convId = "dental-conv-parallel";
        List<String> forbiddenSpaTerms = List.of("thớ cơ", "thảo dược", "vai gáy", "ngủ sâu giấc", "rèm che", "399k", "239k");

        List<FanpageBotRequest.ConversationTurn> history = new ArrayList<>();
        history.add(new FanpageBotRequest.ConversationTurn("user", "tư vấn"));
        history.add(new FanpageBotRequest.ConversationTurn("assistant",
                "Dạ chào mình, em là Minh Anh — trợ lý bác sĩ tại Nha Khoa Tỏa Sáng ạ. Hôm nay răng mình đang gặp tình trạng ê buốt, sứt mẻ hay muốn cải thiện thẩm mỹ ở vùng răng nào ạ?"));

        // 1. Chia sẻ vấn đề chưa rõ phạm vi ("dạo này ăn đồ lạnh hay bị ê buốt mà mình sợ đau lắm")
        AtomicReference<String> p1 = new AtomicReference<>();
        FanpageBotResponse r1 = executeTurn(
                orchestrator, mockLlm, DENTAL_PAGE_ID, senderId, convId,
                "dạo này ăn đồ lạnh hay bị ê buốt mà mình sợ đau lắm",
                history, dentalPersona, dentalChunks, dentalSkills,
                """
                {"bubbles":[
                  "Dạ răng nhạy cảm mỗi lần chạm đồ lạnh bị ê nhói kèm tâm lý ngại đau là điều rất dễ hiểu, mình yên tâm vì bác sĩ bên em kiểm soát tê rất êm ái ạ.",
                  "Hiện tại mình đang bị ê buốt hay muốn phục hình ở vùng răng cửa hay răng hàm bên trong để em tư vấn đúng giải pháp nhất ạ?"
                ]}
                """,
                p1
        );
        assertTrue(p1.get().contains("discovery_completed: false"));
        assertEquals(1, r1.activeSkills().size());
        assertEquals("discovery_empathy_and_scope", r1.activeSkills().get(0).code());
        for (String spaTerm : forbiddenSpaTerms) {
            assertFalse(p1.get().toLowerCase().contains(spaTerm), "Prompt Nha khoa tuyệt đối không được rò rỉ từ vựng Spa: " + spaTerm);
        }

        history.add(new FanpageBotRequest.ConversationTurn("user", "dạo này ăn đồ lạnh hay bị ê buốt mà mình sợ đau lắm"));
        history.add(new FanpageBotRequest.ConversationTurn("assistant",
                "Dạ bác sĩ bên em thao tác rất êm ái ạ. Hiện tại mình đang muốn phục hình ở vùng răng cửa hay răng hàm bên trong ạ?"));

        // 2. Khách nêu rõ vùng răng hàm -> Báo giá dòng chủ lực Zirconia 3.500k/răng
        AtomicReference<String> p2 = new AtomicReference<>();
        executeTurn(
                orchestrator, mockLlm, DENTAL_PAGE_ID, senderId, convId,
                "mình muốn bọc sứ vùng răng hàm bên trong",
                history, dentalPersona, dentalChunks, dentalSkills,
                """
                {"bubbles":[
                  "Dạ với vùng răng hàm cần ăn nhai êm và khít sát viền nướu, em gợi ý mình dòng Răng toàn sứ thẩm mỹ Zirconia chính hãng Đức (3.500k/răng) đã bao gồm chụp phim CT ConeBeam và thẻ bảo hành chính hãng ạ.",
                  "Mình thấy dòng toàn sứ Đức này có hợp với mong muốn của mình không ạ?"
                ]}
                """,
                p2
        );
        assertTrue(p2.get().contains("discovery_completed: true"));
        assertTrue(p2.get().contains("id=NK-SU-ZIR"));
        for (String spaTerm : forbiddenSpaTerms) {
            assertFalse(p2.get().toLowerCase().contains(spaTerm), "Prompt Nha khoa tuyệt đối không được rò rỉ từ vựng Spa: " + spaTerm);
        }

        history.add(new FanpageBotRequest.ConversationTurn("user", "mình muốn bọc sứ vùng răng hàm bên trong"));
        history.add(new FanpageBotRequest.ConversationTurn("assistant",
                "Dạ với vùng răng hàm, em gợi ý mình dòng Răng toàn sứ thẩm mỹ Zirconia chính hãng Đức (3.500k/răng) đã bao gồm chụp phim CT ConeBeam ạ. Mình thấy dòng toàn sứ Đức này thế nào ạ?"));

        // 3. Chê đắt -> Conversational Delta + price_objection_sensory_value
        AtomicReference<String> p3 = new AtomicReference<>();
        FanpageBotResponse r3 = executeTurn(
                orchestrator, mockLlm, DENTAL_PAGE_ID, senderId, convId,
                "giá hơi cao nhỉ",
                history, dentalPersona, dentalChunks, dentalSkills,
                """
                {"bubbles":[
                  "Dạ em rất hiểu sự cân nhắc của mình ạ. Giá trị của dòng toàn sứ Zirconia (3.500k/răng) nằm ở đường viền sứ ôm khít êm dịu vào mô nướu và bác sĩ căn chỉnh tỉ mỉ từng điểm chạm khớp cắn giúp ăn nhai nhẹ tênh như răng thật ạ.",
                  "Với chiếc răng hàm này, điều mình ưu tiên nhất là cảm giác ăn nhai chắc khỏe lâu dài hay độ sáng tự nhiên đồng màu răng thật ạ?"
                ]}
                """,
                p3
        );
        assertTrue(p3.get().contains("CONVERSATIONAL_DELTA_ACTIVE"));
        assertEquals("price_objection_sensory_value", r3.activeSkills().get(0).code());
        for (String spaTerm : forbiddenSpaTerms) {
            assertFalse(p3.get().toLowerCase().contains(spaTerm), "Prompt Nha khoa tuyệt đối không được rò rỉ từ vựng Spa: " + spaTerm);
        }

        history.add(new FanpageBotRequest.ConversationTurn("user", "giá hơi cao nhỉ"));
        history.add(new FanpageBotRequest.ConversationTurn("assistant",
                "Dạ giá trị của dòng toàn sứ Zirconia (3.500k/răng) nằm ở đường viền sứ ôm êm mô nướu và khớp cắn êm ái như răng thật ạ."));

        // 4. Ngập ngừng "ok để xem đã" -> Nhịp cầu mềm sang Răng sứ Titan 1.500k/răng
        AtomicReference<String> p4 = new AtomicReference<>();
        FanpageBotResponse r4 = executeTurn(
                orchestrator, mockLlm, DENTAL_PAGE_ID, senderId, convId,
                "ok để xem đã",
                history, dentalPersona, dentalChunks, dentalSkills,
                """
                {"bubbles":[
                  "Dạ mình cứ thong thả suy nghĩ thêm, làm răng quan trọng nhất là mình thấy thật sự an tâm ạ.",
                  "Nếu mình muốn một lựa chọn nhẹ nhàng hơn về chi phí cho răng hàm bên trong thì bên em cũng có dòng Răng sứ kim loại Titan tiết kiệm (1.500k/răng) chịu lực ăn nhai rất tốt ạ."
                ]}
                """,
                p4
        );
        assertTrue(p4.get().contains("STAGE_3B_PRICE_HESITATION_SOFT_DOWNSELL"));
        assertEquals("soft_economical_bridge_and_compare", r4.activeSkills().get(0).code());

        history.add(new FanpageBotRequest.ConversationTurn("user", "ok để xem đã"));
        history.add(new FanpageBotRequest.ConversationTurn("assistant",
                "Dạ mình cứ thong thả suy nghĩ thêm ạ. Với răng hàm bên trong bên em cũng có dòng Răng sứ kim loại Titan tiết kiệm (1.500k/răng) chịu lực ăn nhai rất tốt ạ."));

        // 5. So sánh 2 phương án -> Cả NK-SU-ZIR và NK-SU-TITAN đều có trong allowed_4layer_products
        AtomicReference<String> p5 = new AtomicReference<>();
        executeTurn(
                orchestrator, mockLlm, DENTAL_PAGE_ID, senderId, convId,
                "2 dòng sứ này khác gì nhau vậy em",
                history, dentalPersona, dentalChunks, dentalSkills,
                """
                {"bubbles":[
                  "Dạ hai dòng khác nhau chính ở độ thấu quang thẩm mỹ: Răng toàn sứ thẩm mỹ Zirconia chính hãng Đức (3.500k/răng) trong sáng tự nhiên tối ưu cho cả răng cửa lẫn răng hàm, còn Răng sứ kim loại Titan tiết kiệm (1.500k/răng) có khung Titan cứng chắc rất kinh tế cho vùng răng hàm ăn nhai bên trong ạ.",
                  "Mình ghé thăm khám nhẹ nhàng để bác sĩ kiểm tra trực tiếp chiếc răng hàm và tư vấn dòng Răng sứ kim loại Titan tiết kiệm (1.500k/răng) cho mình nhé?"
                ]}
                """,
                p5
        );
        assertTrue(p5.get().contains("id=NK-SU-ZIR") && p5.get().contains("id=NK-SU-TITAN"));

        history.add(new FanpageBotRequest.ConversationTurn("user", "2 dòng sứ này khác gì nhau vậy em"));
        history.add(new FanpageBotRequest.ConversationTurn("assistant",
                "Dạ dòng Zirconia (3.500k/răng) có độ trong tự nhiên tối ưu, còn dòng Răng sứ kim loại Titan tiết kiệm (1.500k/răng) chịu lực ăn nhai bền chắc cho răng hàm bên trong ạ. Mình ghé thăm khám thử dòng Răng sứ kim loại Titan tiết kiệm (1.500k/răng) nhé?"));

        // 6. Khách gật đầu "uk" -> STAGE_4 + selected_product_id="NK-SU-TITAN" + AWAITING_DATE_OR_TIME + warm_nod_to_gentle_scheduling
        AtomicReference<String> p6 = new AtomicReference<>();
        FanpageBotResponse r6 = executeTurn(
                orchestrator, mockLlm, DENTAL_PAGE_ID, senderId, convId,
                "uk",
                history, dentalPersona, dentalChunks, dentalSkills,
                """
                {"bubbles":[
                  "Dạ vâng ạ, vậy để em ghi nhận dòng Răng sứ kim loại Titan tiết kiệm (1.500k/răng) và chuẩn bị ghế nha vô trùng êm ái đón mình nhé.",
                  "Hôm nay hay ngày mai mình tiện ghé phòng khám khoảng mấy giờ để em sắp xếp bác sĩ chu đáo nhất cho mình ạ?"
                ]}
                """,
                p6
        );
        assertTrue(p6.get().contains("stage: \"STAGE_4_POSITIVE_TRIAL_OR_BOOKING\""));
        assertTrue(p6.get().contains("selected_product_id=\"NK-SU-TITAN\""));
        assertTrue(p6.get().contains("status=AWAITING_DATE_OR_TIME"));
        assertEquals(1, r6.activeSkills().size());
        assertEquals("warm_nod_to_gentle_scheduling", r6.activeSkills().get(0).code());
        for (String spaTerm : forbiddenSpaTerms) {
            assertFalse(p6.get().toLowerCase().contains(spaTerm), "Prompt Nha khoa tuyệt đối không được rò rỉ từ vựng Spa: " + spaTerm);
        }

        // Kiểm chứng Referee: cho phép ngôn từ giàu cảm xúc, chỉ chặn cam kết y khoa/độ bền cực đoan sai sự thật
        List<PackageCatalogService.PackageSummary> dentalCat = PackageCatalogService.extractCatalog(dentalChunks, List.of("răng cửa", "răng hàm"));
        CleanAgentCatalogAndPromptBuilder.TieredCatalogResult dentalTiered =
                CleanAgentCatalogAndPromptBuilder.rankAndTierCatalogForCleanAgent(dentalCat, List.of(), List.of("răng hàm"), Set.of(), false);

        List<String> validSensoryViolations = CleanAgentOutputRefereeAndSanitizer.checkCleanCatalogGrounding(
                List.of(new FanpageBotResponse.ReplyBubble(
                        "Dạ bác sĩ bên em căn chỉnh khớp cắn thật êm ái giúp mình ăn nhai nhẹ tênh và tự tin với dòng Răng toàn sứ thẩm mỹ Zirconia chính hãng Đức (3.500k/răng) ạ.",
                        0
                )),
                dentalCat,
                dentalTiered.primaryRecommendations(),
                List.of(),
                0,
                false,
                dentalCat.get(0),
                false,
                "NONE",
                "mình muốn bọc sứ vùng răng hàm bên trong",
                false,
                false,
                false
        );
        assertTrue(validSensoryViolations.stream().noneMatch(v -> v.startsWith("FORBIDDEN_MEDICAL_CLAIM")),
                "Ngôn từ giàu cảm xúc thực tế phải được phép 100%: " + validSensoryViolations);

        List<String> extremeClaimViolations = CleanAgentOutputRefereeAndSanitizer.checkCleanCatalogGrounding(
                List.of(new FanpageBotResponse.ReplyBubble(
                        "Dạ dòng sứ này bền vĩnh viễn suốt đời không hỏng và cam kết chữa khỏi hẳn bệnh 100% ạ.",
                        0
                )),
                dentalCat,
                dentalTiered.primaryRecommendations(),
                List.of(),
                0,
                false,
                dentalCat.get(0),
                false,
                "NONE",
                "mình muốn bọc sứ vùng răng hàm bên trong",
                false,
                false,
                false
        );
        assertTrue(extremeClaimViolations.stream().anyMatch(v -> v.startsWith("FORBIDDEN_MEDICAL_CLAIM")),
                "Cam kết cực đoan sai sự thật phải bị chặn: " + extremeClaimViolations);
    }
}
