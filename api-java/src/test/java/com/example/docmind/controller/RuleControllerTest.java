package com.example.docmind.controller;

import com.example.docmind.dto.RuleRequest;
import com.example.docmind.entity.LearnedRuleEntity;
import com.example.docmind.repository.LearnedRuleRepository;
import com.example.docmind.service.embedding.EmbeddingClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RuleController.class)
class RuleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private LearnedRuleRepository learnedRuleRepository;

    @MockitoBean
    private EmbeddingClient embeddingClient;

    @Test
    void getRulesReturnsListOfRules() throws Exception {
        UUID id = UUID.randomUUID();
        LearnedRuleEntity entity = LearnedRuleEntity.builder()
                .id(id)
                .category("PRICING_CALCULATION")
                .triggerCondition("Khi tính giá")
                .rootCause("Nhầm số")
                .generalizedRule("Kiểm tra điều kiện")
                .retrievalKeywords(new String[]{"giá"})
                .active(true)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(learnedRuleRepository.searchRules(any(), any(), any()))
                .thenReturn(List.of(entity));

        mockMvc.perform(get("/api/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].category").value("PRICING_CALCULATION"))
                .andExpect(jsonPath("$[0].active").value(true));
    }

    @Test
    void createRuleReturnsCreatedStatus() throws Exception {
        when(embeddingClient.embed(anyString())).thenReturn(new float[384]);

        RuleRequest req = new RuleRequest(
                "TABLE_LOOKUP",
                "Khi tra cứu bảng",
                "Đọc nhầm cột",
                "Đối chiếu toàn bộ tiêu đề cột trước khi lấy dữ liệu.",
                List.of("bảng", "cột"),
                true
        );

        mockMvc.perform(post("/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.category").value("TABLE_LOOKUP"))
                .andExpect(jsonPath("$.generalized_rule").value("Đối chiếu toàn bộ tiêu đề cột trước khi lấy dữ liệu."))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void createRuleWithBlankTriggerConditionAutoPopulatesTriggerAndKeywords() throws Exception {
        when(embeddingClient.embed(anyString())).thenReturn(new float[384]);

        RuleRequest req = new RuleRequest(
                "POLICY",
                "",
                "",
                "Khi khách hàng để lại số điện thoại cần tư vấn thì xác nhận ngay và chuyển nhân viên.",
                List.of(),
                true
        );

        mockMvc.perform(post("/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.category").value("POLICY"))
                .andExpect(jsonPath("$.trigger_condition").isNotEmpty())
                .andExpect(jsonPath("$.retrieval_keywords").isNotEmpty())
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void createPageScopedRuleReturnsCreatedStatusWithPageId() throws Exception {
        when(embeddingClient.embed(anyString())).thenReturn(new float[384]);
        UUID pageId = UUID.randomUUID();

        RuleRequest req = new RuleRequest(
                "POLICY",
                "Khi tư vấn dịch vụ trị mụn",
                "Chào nhầm tên khách",
                "Không bao giờ xưng Lan Anh, xưng Anh với khách nam.",
                List.of("nam", "xưng hô"),
                true,
                pageId.toString()
        );

        mockMvc.perform(post("/api/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.category").value("POLICY"))
                .andExpect(jsonPath("$.page_id").value(pageId.toString()))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void getRulesWithPageIdFiltersByPage() throws Exception {
        UUID pageId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        LearnedRuleEntity entity = LearnedRuleEntity.builder()
                .id(id)
                .category("POLICY")
                .triggerCondition("Trigger")
                .rootCause("Cause")
                .generalizedRule("Page scoped rule")
                .retrievalKeywords(new String[]{"policy"})
                .pageId(pageId)
                .active(true)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(learnedRuleRepository.searchRulesByPage(any(), any(), any(), any(), any(Boolean.class), any(Boolean.class)))
                .thenReturn(List.of(entity));

        mockMvc.perform(get("/api/rules")
                        .param("page_id", pageId.toString())
                        .param("include_global", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].page_id").value(pageId.toString()));
    }

    @Test
    void updateRuleModifiesExistingRule() throws Exception {
        UUID id = UUID.randomUUID();
        LearnedRuleEntity entity = LearnedRuleEntity.builder()
                .id(id)
                .category("POLICY")
                .triggerCondition("Trigger")
                .rootCause("Cause")
                .generalizedRule("Old rule")
                .retrievalKeywords(new String[]{"policy"})
                .active(true)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(learnedRuleRepository.findById(id)).thenReturn(Optional.of(entity));

        RuleRequest req = new RuleRequest(
                "POLICY",
                "Trigger",
                "Cause",
                "Updated new rule",
                List.of("policy", "rule"),
                false
        );

        mockMvc.perform(put("/api/rules/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.generalized_rule").value("Updated new rule"))
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void deleteRuleRemovesRule() throws Exception {
        UUID id = UUID.randomUUID();
        when(learnedRuleRepository.existsById(id)).thenReturn(true);

        mockMvc.perform(delete("/api/rules/" + id))
                .andExpect(status().isNoContent());

        verify(learnedRuleRepository).deleteById(id);
    }

    @Test
    void deleteRuleReturnsNotFoundWhenMissing() throws Exception {
        UUID id = UUID.randomUUID();
        when(learnedRuleRepository.existsById(id)).thenReturn(false);

        mockMvc.perform(delete("/api/rules/" + id))
                .andExpect(status().isNotFound());
    }
}
