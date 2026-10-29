package com.example.docmind.controller;

import com.example.docmind.dto.KnowledgePageRequest;
import com.example.docmind.dto.PagePersonaConfig;
import com.example.docmind.entity.KnowledgePageEntity;
import com.example.docmind.repository.DocumentRepository;
import com.example.docmind.repository.KnowledgePageRepository;
import com.example.docmind.repository.LearnedRuleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(KnowledgePageController.class)
class KnowledgePageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private KnowledgePageRepository knowledgePageRepository;

    @MockitoBean
    private DocumentRepository documentRepository;

    @MockitoBean
    private LearnedRuleRepository learnedRuleRepository;

    @Test
    void listPagesReturnsPagesWithDocumentAndRuleCounts() throws Exception {
        UUID pageId = UUID.randomUUID();
        KnowledgePageEntity page = KnowledgePageEntity.builder()
                .id(pageId)
                .name("Page Mỹ Phẩm Spa")
                .industry("Làm đẹp")
                .targetAudience("Nữ 25-45 tuổi")
                .description("Chuyên dược mỹ phẩm")
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();

        when(knowledgePageRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(page));
        when(documentRepository.countByPageId(pageId)).thenReturn(4L);
        when(learnedRuleRepository.countByPageId(pageId)).thenReturn(2L);

        mockMvc.perform(get("/api/pages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(pageId.toString()))
                .andExpect(jsonPath("$[0].name").value("Page Mỹ Phẩm Spa"))
                .andExpect(jsonPath("$[0].industry").value("Làm đẹp"))
                .andExpect(jsonPath("$[0].target_audience").value("Nữ 25-45 tuổi"))
                .andExpect(jsonPath("$[0].document_count").value(4))
                .andExpect(jsonPath("$[0].rule_count").value(2));
    }

    @Test
    void createPageReturns201Created() throws Exception {
        KnowledgePageRequest req = new KnowledgePageRequest(
                "Page Bất Động Sản",
                "Bất động sản",
                "Nhà đầu tư VIP",
                "Tài liệu dự án nghỉ dưỡng"
        );
        when(knowledgePageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post("/api/pages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Page Bất Động Sản"))
                .andExpect(jsonPath("$.industry").value("Bất động sản"))
                .andExpect(jsonPath("$.target_audience").value("Nhà đầu tư VIP"))
                .andExpect(jsonPath("$.document_count").value(0));
    }

    @Test
    void updatePageModifiesMetadata() throws Exception {
        UUID pageId = UUID.randomUUID();
        KnowledgePageEntity existing = KnowledgePageEntity.builder()
                .id(pageId)
                .name("Old Name")
                .industry("Old Industry")
                .targetAudience("Old Audience")
                .description("Old Desc")
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();

        when(knowledgePageRepository.findById(pageId)).thenReturn(Optional.of(existing));
        when(knowledgePageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(documentRepository.countByPageId(pageId)).thenReturn(3L);
        when(learnedRuleRepository.countByPageId(pageId)).thenReturn(1L);

        KnowledgePageRequest updateReq = new KnowledgePageRequest(
                "Page SaaS Enterprise",
                "Công nghệ B2B",
                "Giám đốc IT",
                "Giải pháp đám mây"
        );

        mockMvc.perform(put("/api/pages/" + pageId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Page SaaS Enterprise"))
                .andExpect(jsonPath("$.industry").value("Công nghệ B2B"))
                .andExpect(jsonPath("$.target_audience").value("Giám đốc IT"))
                .andExpect(jsonPath("$.document_count").value(3));
    }

    @Test
    void deletePageReturns204() throws Exception {
        UUID pageId = UUID.randomUUID();
        KnowledgePageEntity existing = KnowledgePageEntity.builder()
                .id(pageId)
                .name("Page Test")
                .industry("Tech")
                .targetAudience("B2B")
                .build();
        when(knowledgePageRepository.findById(pageId)).thenReturn(Optional.of(existing));

        mockMvc.perform(delete("/api/pages/" + pageId))
                .andExpect(status().isNoContent());

        verify(knowledgePageRepository).delete(existing);
    }

    @Test
    void listIndustryTemplatesReturnsSixPresets() throws Exception {
        mockMvc.perform(get("/api/pages/industry-templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[0].industry_code").value("BEAUTY_SPA"))
                .andExpect(jsonPath("$[2].industry_code").value("FASHION_APPAREL"))
                .andExpect(jsonPath("$[2].persona_config.default_customer_pronoun").value("Bạn"));
    }

    @Test
    void updatePageUpdatesOpeningBubblesAndPreservesDeepPersonaConfig() throws Exception {
        UUID pageId = UUID.fromString("d3000000-0000-0000-0000-000000000001");
        String existingJson = """
                {
                  "industry_code": "DENTAL_CLINIC",
                  "bot_name": "Minh Anh",
                  "self_pronoun": "em",
                  "default_customer_pronoun": "mình",
                  "care_actor": "bác sĩ",
                  "post_price_delay_ms": 10000,
                  "active_prompt_compact": "BẠN LÀ: Minh Anh, trợ lý tư vấn Nha Khoa Tỏa Sáng.",
                  "symptom_keywords": ["ê buốt", "ố vàng"],
                  "opening_bubbles": ["Câu chào cũ 1", "Câu chào cũ 2"]
                }
                """;
        KnowledgePageEntity existing = KnowledgePageEntity.builder()
                .id(pageId)
                .name("Nha Khoa Tỏa Sáng")
                .industry("DENTAL_CLINIC")
                .targetAudience("Khách hàng chăm sóc & thẩm mỹ răng miệng")
                .description("Ân cần, chuyên nghiệp")
                .personaConfigJson(existingJson)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();

        when(knowledgePageRepository.findById(pageId)).thenReturn(Optional.of(existing));
        when(knowledgePageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(documentRepository.countByPageId(pageId)).thenReturn(1L);
        when(learnedRuleRepository.countByPageId(pageId)).thenReturn(0L);

        PagePersonaConfig incomingCfg = new PagePersonaConfig(
                "DENTAL_CLINIC",
                "Minh Anh",
                "em",
                "mình",
                "Ân cần, chuẩn y khoa",
                "LOW",
                List.of("massage"),
                List.of("nha khoa", "răng sứ"),
                null,
                null,
                List.of(
                        "Dạ chào mình, em là Minh Anh — trợ lý bác sĩ tại Nha Khoa Tỏa Sáng ạ.",
                        "Bên em chuyên sâu các dịch vụ chăm sóc, phục hình răng sứ thẩm mỹ và điều trị răng miệng êm ái, nhẹ nhàng, giúp mình yên tâm không lo đau buốt.",
                        "Hôm nay răng mình đang gặp tình trạng ê buốt, sứt mẻ hay mình muốn cải thiện thẩm mỹ nụ cười ở vùng răng nào để em hỗ trợ thông tin chi tiết nhất ạ?"
                ),
                null,
                null
        );
        KnowledgePageRequest updateReq = new KnowledgePageRequest(
                "Nha Khoa Tỏa Sáng",
                "DENTAL_CLINIC",
                "Khách hàng chăm sóc & thẩm mỹ răng miệng",
                "Ân cần, chuyên nghiệp",
                incomingCfg
        );

        mockMvc.perform(put("/api/pages/" + pageId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.persona_config.opening_bubbles.length()").value(3))
                .andExpect(jsonPath("$.persona_config.opening_bubbles[0]").value("Dạ chào mình, em là Minh Anh — trợ lý bác sĩ tại Nha Khoa Tỏa Sáng ạ."))
                .andExpect(jsonPath("$.persona_config.opening_bubbles[2]").value("Hôm nay răng mình đang gặp tình trạng ê buốt, sứt mẻ hay mình muốn cải thiện thẩm mỹ nụ cười ở vùng răng nào để em hỗ trợ thông tin chi tiết nhất ạ?"))
                .andExpect(jsonPath("$.persona_config.care_actor").value("bác sĩ"))
                .andExpect(jsonPath("$.persona_config.post_price_delay_ms").value(10000))
                .andExpect(jsonPath("$.persona_config.active_prompt_compact").value("BẠN LÀ: Minh Anh, trợ lý tư vấn Nha Khoa Tỏa Sáng."));
    }
}
