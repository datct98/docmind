package com.example.docmind.controller;

import com.example.docmind.dto.ChatRequest;
import com.example.docmind.dto.ChatResponse;
import com.example.docmind.dto.Citation;
import com.example.docmind.dto.LearnedRuleDto;
import com.example.docmind.service.ChatService;
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

/** Controller slice test cho /api/chat. */
@WebMvcTest(ChatController.class)
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ChatService chatService;

    @Test
    void chatReturnsAnswerAndCitations() throws Exception {
        Citation citation = new Citation(
                "chunk-1", "doc-1", List.of("block-1", "block-2"),
                1, List.of(72.0, 90.0, 480.0, 118.0),
                List.of("Derivatives"), "The derivative measures the rate of change.");
        when(chatService.chat(any()))
                .thenReturn(new ChatResponse("[stub-llm] answer", List.of(citation)));

        ChatRequest req = new ChatRequest(List.of("doc-1"), "What is a derivative?", 5);

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("[stub-llm] answer"))
                .andExpect(jsonPath("$.citations[0].chunk_id").value("chunk-1"))
                .andExpect(jsonPath("$.citations[0].page").value(1))
                .andExpect(jsonPath("$.citations[0].heading_path[0]").value("Derivatives"))
                .andExpect(jsonPath("$.citations[0].block_ids[0]").value("block-1"));
    }

    @Test
    void chatReturnsAppliedRulesAndNewlyLearnedRuleWhenPresent() throws Exception {
        LearnedRuleDto appliedRule = new LearnedRuleDto(
                UUID.randomUUID(),
                "PRICING_CALCULATION",
                "Khi tính chiết khấu",
                "Bỏ sót ngưỡng",
                "Kiểm tra kỹ điều kiện số lượng.",
                List.of("chiết khấu"),
                true,
                Instant.now(),
                Instant.now()
        );
        LearnedRuleDto newlyLearned = new LearnedRuleDto(
                UUID.randomUUID(),
                "TABLE_LOOKUP",
                "Khi tra cứu bảng",
                "Đọc nhầm cột",
                "Đối soát tiêu đề cột.",
                List.of("bảng"),
                true,
                Instant.now(),
                Instant.now()
        );

        when(chatService.chat(any()))
                .thenReturn(new ChatResponse("Câu trả lời sửa lại", List.of(), List.of(appliedRule), newlyLearned));

        ChatRequest req = new ChatRequest(
                List.of("doc-1"),
                "Sai rồi, phải là 15%",
                5,
                "Chiết khấu bao nhiêu?",
                "5%",
                "conv-1"
        );

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Câu trả lời sửa lại"))
                .andExpect(jsonPath("$.applied_rules[0].category").value("PRICING_CALCULATION"))
                .andExpect(jsonPath("$.newly_learned_rule.category").value("TABLE_LOOKUP"));
    }

    @Test
    void chatRejectsBlankQuestionWith400() throws Exception {
        String body = """
                {"document_ids": ["doc-1"], "question": "  "}
                """;
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void chatRejectsEmptyDocumentIdsWith400() throws Exception {
        String body = """
                {"document_ids": [], "question": "hello"}
                """;
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
