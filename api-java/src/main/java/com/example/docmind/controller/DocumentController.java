package com.example.docmind.controller;

import com.example.docmind.dto.DocumentChunkDto;
import com.example.docmind.dto.DocumentLifecycleUpdateRequest;
import com.example.docmind.dto.DocumentPageAssignRequest;
import com.example.docmind.dto.DocumentStatusResponse;
import com.example.docmind.dto.DocumentUploadResponse;
import com.example.docmind.service.DocumentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** Ingest API. Controller mỏng, delegate sang DocumentService. */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping
    public ResponseEntity<DocumentUploadResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "page_id", required = false) String pageId,
            @RequestParam(value = "effective_from", required = false) String effectiveFrom,
            @RequestParam(value = "effective_to", required = false) String effectiveTo
    ) {
        if ((effectiveFrom == null || effectiveFrom.isBlank())
                && (effectiveTo == null || effectiveTo.isBlank())) {
            if (pageId == null || pageId.isBlank()) {
                return ResponseEntity.status(HttpStatus.CREATED).body(documentService.upload(file));
            }
            return ResponseEntity.status(HttpStatus.CREATED).body(documentService.upload(file, pageId));
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(documentService.upload(file, pageId, effectiveFrom, effectiveTo));
    }

    @GetMapping
    public ResponseEntity<List<DocumentStatusResponse>> list(
            @RequestParam(value = "page_id", required = false) String pageId
    ) {
        if (pageId == null) {
            return ResponseEntity.ok(documentService.listAll());
        }
        if (pageId.isBlank()) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(documentService.listAll(pageId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<DocumentStatusResponse> status(@PathVariable("id") String id) {
        return ResponseEntity.ok(documentService.getStatus(id));
    }

    @GetMapping("/{id}/chunks")
    public ResponseEntity<List<DocumentChunkDto>> chunks(@PathVariable("id") String id) {
        return ResponseEntity.ok(documentService.getDocumentChunks(id));
    }

    @PutMapping("/{id}/page")
    public ResponseEntity<DocumentStatusResponse> assignPage(
            @PathVariable("id") String id,
            @RequestBody(required = false) DocumentPageAssignRequest request
    ) {
        String pageId = request != null ? request.pageId() : null;
        return ResponseEntity.ok(documentService.assignPage(id, pageId));
    }

    @PutMapping("/{id}/lifecycle")
    public ResponseEntity<DocumentStatusResponse> updateLifecycle(
            @PathVariable("id") String id,
            @RequestBody DocumentLifecycleUpdateRequest request
    ) {
        return ResponseEntity.ok(documentService.updateLifecycle(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id) {
        documentService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
