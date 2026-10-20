package com.example.docmind.controller;

import com.example.docmind.dto.Citation;
import com.example.docmind.dto.FeedbackRequest;
import com.example.docmind.dto.LearnedRuleDto;
import com.example.docmind.dto.ChatResponse;
import com.example.docmind.service.ChatService;
import com.example.docmind.service.MetaLearningService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FeedbackController.class)
class FeedbackControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private MetaLearningService metaLearningService;

    @MockitoBean
    private ChatService chatService;

    @MockitoBean
    private com.example.docmind.service.bot.FanpageBotService fanpageBotService;

    @Test
    void submitFeedbackReturnsLearnedRuleAndRegeneratedAnswer() throws Exception {
        UUID ruleId = UUID.randomUUID();
        LearnedRuleDto learnedRule = new LearnedRuleDto(
                ruleId,
                "PRICING_CALCULATION",
                "Khi tính chiết khấu",
                "Đọc sai mức ngưỡng",
                "Luôn kiểm tra toàn bộ điều kiện bậc thang số lượng trước khi tính chiết khấu.",
                List.of("chiết khấu", "số lượng"),
                true,
                Instant.now(),
                Instant.now()
        );

        when(metaLearningService.learnFromFeedback(any(), any(), any(), any(), any()))
                .thenReturn(learnedRule);

        when(chatService.chat(any()))
                .thenReturn(new ChatResponse("Câu trả lời đã được tái sinh chính xác.", List.of(), List.of(learnedRule), null));

        FeedbackRequest req = new FeedbackRequest(
                "Đơn 500 cái chiết khấu bao nhiêu?",
                "5%",
                "Phải là 15% theo bảng chiết khấu bậc thang",
                List.of("doc-uuid"),
                "conv-1",
                "context"
        );

        mockMvc.perform(post("/api/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.learned_rule.id").value(ruleId.toString()))
                .andExpect(jsonPath("$.learned_rule.category").value("PRICING_CALCULATION"))
                .andExpect(jsonPath("$.regenerated_answer").value("Câu trả lời đã được tái sinh chính xác."))
                .andExpect(jsonPath("$.applied_rules[0].id").value(ruleId.toString()));
    }

    @Test
    void submitFeedbackFromFanpageBotRoutesToFanpageBotService() throws Exception {
        UUID ruleId = UUID.randomUUID();
        LearnedRuleDto learnedRule = new LearnedRuleDto(
                ruleId,
                "POLICY",
                "Khi xưng hô với khách nam",
                "Chào nhầm Chị",
                "Không xưng Chị khi khách là nam, phải gọi là Anh.",
                List.of("nam", "xưng hô"),
                true,
                Instant.now(),
                Instant.now()
        );

        when(metaLearningService.learnFromFeedback(any(), any(), any(), any(), any(), any()))
                .thenReturn(learnedRule);

        com.example.docmind.dto.bot.CustomerInsightDto insight = new com.example.docmind.dto.bot.CustomerInsightDto(
                "DISCREET_DECISIVE",
                "Khách Nam / VIP",
                68,
                "WARM",
                "Anh",
                List.of("Khách hàng Nam giới (cần tư vấn rõ tính phù hợp & sự kín đáo)"),
                List.of("E ngại môi trường Spa đông khách nữ"),
                "Giữ giọng văn điềm đạm"
        );
        com.example.docmind.dto.bot.AgentSkillDto skill = new com.example.docmind.dto.bot.AgentSkillDto(
                UUID.randomUUID(),
                "PSYCH_DISCREET_VIP_CARE",
                "Tâm lý Tinh tế cho Khách Nam",
                "PSYCHOLOGY",
                "Mô tả",
                "nam giới",
                "Chỉ dẫn",
                "Ví dụ",
                "Anti",
                90,
                true,
                null,
                Instant.now(),
                Instant.now()
        );
        com.example.docmind.dto.bot.FanpageBotResponse botResponse = new com.example.docmind.dto.bot.FanpageBotResponse(
                "Dạ em chào Anh ạ! Gói dịch vụ phù hợp với Anh.",
                List.of(),
                "VALUE_CONSULTATION",
                0.95,
                false,
                null,
                null,
                null,
                List.of(learnedRule),
                insight,
                List.of(skill)
        );

        org.mockito.ArgumentCaptor<com.example.docmind.dto.bot.FanpageBotRequest> botReqCaptor =
                org.mockito.ArgumentCaptor.forClass(com.example.docmind.dto.bot.FanpageBotRequest.class);
        when(fanpageBotService.handleCustomerMessage(botReqCaptor.capture()))
                .thenReturn(botResponse);

        List<com.example.docmind.dto.bot.FanpageBotRequest.ConversationTurn> history = List.of(
                new com.example.docmind.dto.bot.FanpageBotRequest.ConversationTurn("user", "Mình là nam bị mụn 10 năm"),
                new com.example.docmind.dto.bot.FanpageBotRequest.ConversationTurn("assistant", "Dạ em chào Anh ạ!")
        );

        FeedbackRequest req = new FeedbackRequest(
                "Giá gói điều trị bên em sao?",
                "Dạ chào Chị ạ...",
                "Khách là nam ở lượt trước phải giữ xưng hô Anh",
                List.of("f7a63412-2127-4632-95f2-9577717424ad"),
                "conv-2",
                "context",
                "page-uuid",
                "FANPAGE_BOT",
                history
        );

        mockMvc.perform(post("/api/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.learned_rule.id").value(ruleId.toString()))
                .andExpect(jsonPath("$.regenerated_answer").value("Dạ em chào Anh ạ! Gói dịch vụ phù hợp với Anh."))
                .andExpect(jsonPath("$.applied_rules[0].id").value(ruleId.toString()))
                .andExpect(jsonPath("$.customer_insight.honorific").value("Anh"))
                .andExpect(jsonPath("$.active_skills[0].code").value("PSYCH_DISCREET_VIP_CARE"));

        org.assertj.core.api.Assertions.assertThat(botReqCaptor.getValue().history())
                .hasSize(2)
                .extracting(com.example.docmind.dto.bot.FanpageBotRequest.ConversationTurn::content)
                .containsExactly("Mình là nam bị mụn 10 năm", "Dạ em chào Anh ạ!");
    }

    @Test
    void submitFeedbackRejectsBlankFieldsWith400() throws Exception {
        String invalidBody = """
                {
                  "question": "",
                  "faulty_answer": "Sai",
                  "user_correction": "Đính chính"
                }
                """;
        mockMvc.perform(post("/api/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submitFeedbackWithAppliedRuleIdsTriggersSelectiveBlameAttribution() throws Exception {
        UUID ruleId = UUID.randomUUID();
        UUID appliedRuleId = UUID.randomUUID();
        LearnedRuleDto learnedRule = new LearnedRuleDto(
                ruleId,
                "PRICING_CALCULATION",
                "Khi tính chiết khấu",
                "Đọc sai mức ngưỡng",
                "Luôn kiểm tra bậc thang số lượng.",
                List.of("chiết khấu"),
                true,
                Instant.now(),
                Instant.now()
        );

        when(metaLearningService.learnFromFeedback(any(), any(), any(), any(), any()))
                .thenReturn(learnedRule);
        when(chatService.chat(any()))
                .thenReturn(new ChatResponse("Câu trả lời đúng.", List.of(), List.of(learnedRule), null));

        FeedbackRequest req = new FeedbackRequest(
                "Câu hỏi giá?",
                "Trả lời sai",
                "Chỉ dẫn sửa sai chuẩn",
                List.of("doc-uuid"),
                "conv-1",
                "context",
                null,
                null,
                List.of(),
                List.of(appliedRuleId)
        );

        mockMvc.perform(post("/api/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.learned_rule.id").value(ruleId.toString()));

        org.mockito.Mockito.verify(metaLearningService).recordNegativeFeedbackAndAutoQuarantine(
                org.mockito.ArgumentMatchers.eq(List.of(appliedRuleId)),
                org.mockito.ArgumentMatchers.eq("Câu hỏi giá?"),
                org.mockito.ArgumentMatchers.eq("Trả lời sai"),
                org.mockito.ArgumentMatchers.eq("Chỉ dẫn sửa sai chuẩn")
        );
    }
}
