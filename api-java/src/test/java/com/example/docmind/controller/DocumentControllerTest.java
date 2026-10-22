package com.example.docmind.controller;

import com.example.docmind.dto.DocumentStatusResponse;
import com.example.docmind.dto.DocumentUploadResponse;
import com.example.docmind.service.DocumentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Controller slice test cho /api/documents (DocumentService mock). */
@WebMvcTest(DocumentController.class)
class DocumentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocumentService documentService;

    @Test
    void uploadReturns201WithDocumentAndJobId() throws Exception {
        when(documentService.upload(any()))
                .thenReturn(new DocumentUploadResponse("doc-123", "job-456", "PARSING"));

        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.pdf", MediaType.APPLICATION_PDF_VALUE,
                "%PDF-1.4 sample".getBytes());

        mockMvc.perform(multipart("/api/documents").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.document_id").value("doc-123"))
                .andExpect(jsonPath("$.job_id").value("job-456"))
                .andExpect(jsonPath("$.status").value("PARSING"))
                // Correlation id phải được sinh và echo ra response header (MDC chain).
                .andExpect(header().exists("X-Correlation-Id"));
    }

    @Test
    void getStatusReturnsDocumentStatus() throws Exception {
        when(documentService.getStatus("doc-123"))
                .thenReturn(new DocumentStatusResponse("doc-123", "notes.pdf", "READY", 3,
                        Map.of("pymupdf", 2, "docling", 1)));

        mockMvc.perform(get("/api/documents/doc-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.pages").value(3))
                .andExpect(jsonPath("$.engine_breakdown.pymupdf").value(2));
    }

    @Test
    void listReturnsEmptyListWithoutPageId() throws Exception {
        mockMvc.perform(get("/api/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void listWithPageIdReturnsScopedDocuments() throws Exception {
        when(documentService.listAll("page-123"))
                .thenReturn(java.util.List.of(new DocumentStatusResponse("doc-123", "notes.pdf", "READY", 3,
                        Map.of("pymupdf", 2, "docling", 1))));

        mockMvc.perform(get("/api/documents").param("page_id", "page-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].document_id").value("doc-123"))
                .andExpect(jsonPath("$[0].filename").value("notes.pdf"))
                .andExpect(jsonPath("$[0].status").value("READY"));
    }

    @Test
    void listWithInvalidPageIdReturnsEmptyList() throws Exception {
        when(documentService.listAll("invalid"))
                .thenReturn(java.util.List.of());

        mockMvc.perform(get("/api/documents").param("page_id", "invalid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void deleteReturns204NoContent() throws Exception {
        mockMvc.perform(delete("/api/documents/doc-123"))
                .andExpect(status().isNoContent());

        verify(documentService).delete("doc-123");
    }

    @Test
    void updateLifecycleReturnsUpdatedStatus() throws Exception {
        java.time.OffsetDateTime from = java.time.OffsetDateTime.parse("2026-10-01T00:00:00Z");
        java.time.OffsetDateTime to = java.time.OffsetDateTime.parse("2026-10-31T23:59:59Z");
        when(documentService.updateLifecycle(any(), any()))
                .thenReturn(new DocumentStatusResponse(
                        "doc-123",
                        "promo.md",
                        "READY",
                        1,
                        Map.of(),
                        null,
                        from,
                        to,
                        true,
                        true
                ));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/documents/doc-123/lifecycle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"effective_from\":\"2026-10-01T00:00:00Z\",\"effective_to\":\"2026-10-31T23:59:59Z\",\"is_active\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.document_id").value("doc-123"))
                .andExpect(jsonPath("$.is_active").value(true))
                .andExpect(jsonPath("$.is_expired").value(false));
    }
}
