package com.example.docmind.service;

import com.example.docmind.client.DocServiceClient;
import com.example.docmind.client.model.CanonicalBlock;
import com.example.docmind.client.model.CanonicalDocument;
import com.example.docmind.client.model.JobStatusResponse;
import com.example.docmind.client.model.ParseSubmitResponse;
import com.example.docmind.config.DocServiceProperties;
import com.example.docmind.dto.DocumentLifecycleUpdateRequest;
import com.example.docmind.dto.DocumentStatusResponse;
import com.example.docmind.dto.DocumentUploadResponse;
import com.example.docmind.entity.BlockEntity;
import com.example.docmind.entity.DocumentEntity;
import com.example.docmind.exception.DocServiceException;
import com.example.docmind.exception.ResourceNotFoundException;
import com.example.docmind.observability.CorrelationIds;
import com.example.docmind.repository.BlockRepository;
import com.example.docmind.repository.ChunkRepository;
import com.example.docmind.repository.DocumentRepository;
import com.example.docmind.service.embedding.EmbeddingClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Luồng INGEST. Upload: validate (Tika magic-byte + SHA-256) -> persist documents
 * (PARSING) -> gọi doc-service-py submitParse -> lưu job_id. Poll async: chờ parse
 * xong -> persist blocks + dựng chunks + embed + ghi tsvector.
 *
 * <p>Log các stage: validate / detect-mime / submit-parse / poll-job / normalize /
 * chunk / embed — kèm correlation chain qua MDC.
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final DocumentRepository documentRepository;
    private final BlockRepository blockRepository;
    private final ChunkRepository chunkRepository;
    private final DocServiceClient docServiceClient;
    private final EmbeddingClient embeddingClient;
    private final DocServiceProperties docServiceProps;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.docmind.service.metadata.ChunkMetadataBackfillService chunkMetadataBackfillService;

    public DocumentService(DocumentRepository documentRepository,
                           BlockRepository blockRepository,
                           ChunkRepository chunkRepository,
                           DocServiceClient docServiceClient,
                           EmbeddingClient embeddingClient,
                           DocServiceProperties docServiceProps,
                           ObjectMapper objectMapper) {
        this.documentRepository = documentRepository;
        this.blockRepository = blockRepository;
        this.chunkRepository = chunkRepository;
        this.docServiceClient = docServiceClient;
        this.embeddingClient = embeddingClient;
        this.docServiceProps = docServiceProps;
        this.objectMapper = objectMapper;
    }

    /** Upload: validate + hash + persist + submit parse. Trả về 201 payload. */
    @Transactional
    public DocumentUploadResponse upload(MultipartFile file) {
        return upload(file, null);
    }

    /** Upload kèm phân loại vào Page / Thư mục tri thức (pageId có thể null). */
    @Transactional
    public DocumentUploadResponse upload(MultipartFile file, String pageIdRaw) {
        return upload(file, pageIdRaw, null, null);
    }

    /** Upload kèm phân loại vào Page và thời hạn hiệu lực (effectiveFrom / effectiveTo). */
    @Transactional
    public DocumentUploadResponse upload(MultipartFile file,
                                         String pageIdRaw,
                                         String effectiveFromRaw,
                                         String effectiveToRaw) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File rỗng");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Không đọc được file upload", e);
        }
        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload";
        UUID targetPageId = parseNullableUuid(pageIdRaw);
        java.time.OffsetDateTime effectiveFrom = parseNullableOffsetDateTime(effectiveFromRaw);
        java.time.OffsetDateTime effectiveTo = parseNullableOffsetDateTime(effectiveToRaw);

        // stage=validate + detect-mime (magic-byte, KHÔNG theo đuôi file).
        CorrelationIds.setStage("validate");
        String sha256 = FileValidation.sha256Hex(bytes);
        log.info("validate filename={} size={} sha256={}", originalName, bytes.length, sha256);

        CorrelationIds.setStage("detect-mime");
        String mime = FileValidation.detectMime(bytes, originalName);
        log.info("detect-mime detected={}", mime);
        FileValidation.requireSupported(mime);
        CorrelationIds.clearStage();

        // Dedup theo sha256: nếu đã có, trả lại document hiện tại (không parse lại).
        var existing = documentRepository.findBySha256(sha256);
        if (existing.isPresent()) {
            DocumentEntity doc = existing.get();
            boolean changed = false;
            if (targetPageId != null && !targetPageId.equals(doc.getPageId())) {
                doc.setPageId(targetPageId);
                changed = true;
                int movedChunks = chunkRepository.updatePageIdByDocumentId(doc.getId(), targetPageId);
                log.info("dedup sha256 page reassigned document_id={} page_id={} synced_chunks={}",
                        doc.getId(), targetPageId, movedChunks);
            }
            if (effectiveFrom != null) {
                doc.setEffectiveFrom(effectiveFrom);
                changed = true;
            }
            if (effectiveTo != null) {
                doc.setEffectiveTo(effectiveTo);
                changed = true;
            }
            if (changed) {
                documentRepository.save(doc);
            }
            CorrelationIds.putDocumentId(doc.getId().toString());
            log.info("dedup sha256 hit -> document_id={} status={}", doc.getId(), doc.getStatus());
            return new DocumentUploadResponse(doc.getId().toString(), doc.getJobId(), doc.getStatus());
        }

        String correlationId = CorrelationIds.currentCorrelationId();
        if (correlationId == null) {
            correlationId = CorrelationIds.newId();
            CorrelationIds.putCorrelationId(correlationId);
        }

        // Gọi parser (propagate X-Correlation-Id).
        ParseSubmitResponse submit = docServiceClient.submitParse(bytes, originalName, correlationId);

        // document_id ưu tiên theo id parser trả về (ổn định xuyên hệ thống).
        UUID documentId = safeUuid(submit.documentId());
        CorrelationIds.putDocumentId(documentId.toString());
        CorrelationIds.putJobId(submit.jobId());

        DocumentEntity doc = DocumentEntity.builder()
                .id(documentId)
                .filename(originalName)
                .sha256(sha256)
                .mime(mime)
                .pages(0)
                .status("PARSING")
                .jobId(submit.jobId())
                .pageId(targetPageId)
                .effectiveFrom(effectiveFrom)
                .effectiveTo(effectiveTo)
                .active(Boolean.TRUE)
                .build();
        documentRepository.save(doc);
        log.info("persist documents row document_id={} job_id={} page_id={} status=PARSING",
                documentId, submit.jobId(), targetPageId);

        // Nếu parser báo cached => kết quả sẵn, poll ngay (async) để persist.
        pollAndPersistAsync(documentId, submit.jobId(), correlationId);

        return new DocumentUploadResponse(documentId.toString(), submit.jobId(), "PARSING");
    }

    /**
     * Poll job tới khi succeeded/failed rồi persist blocks + chunks + embeddings.
     * Chạy async để POST /api/documents trả 201 ngay.
     */
    @Async
    public void pollAndPersistAsync(UUID documentId, String jobId, String correlationId) {
        CorrelationIds.putCorrelationId(correlationId);
        CorrelationIds.putDocumentId(documentId.toString());
        CorrelationIds.putJobId(jobId);
        try {
            CanonicalDocument result = pollUntilDone(jobId, correlationId);
            persistResult(documentId, result);
        } catch (Exception e) {
            log.error("Ingest async thất bại document_id={} job_id={}: {}", documentId, jobId, e.getMessage());
            markFailed(documentId);
        }
    }

    private CanonicalDocument pollUntilDone(String jobId, String correlationId) {
        int attempts = docServiceProps.getPollMaxAttempts();
        long interval = docServiceProps.getPollIntervalMs();
        for (int i = 0; i < attempts; i++) {
            JobStatusResponse status = docServiceClient.getJob(jobId, correlationId);
            if (status.isSucceeded()) {
                return docServiceClient.getResult(jobId, correlationId);
            }
            if (status.isFailed()) {
                throw new DocServiceException("Parser báo job failed: " + status.error());
            }
            try {
                Thread.sleep(interval);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new DocServiceException("Poll job bị gián đoạn", ie);
            }
        }
        throw new DocServiceException("Poll job timeout sau " + attempts + " lần: " + jobId);
    }

    /** Persist blocks + dựng/embed/ghi chunks. Tách transaction riêng. */
    @Transactional
    public void persistResult(UUID documentId, CanonicalDocument result) {
        DocumentEntity doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document không tồn tại: " + documentId));

        // QUAN TRỌNG: insertChunk (native SQL) chạy TỨC THÌ và có FK ->documents.
        // Hibernate trì hoãn INSERT documents/blocks tới cuối transaction, nên nếu
        // không flush trước, chunk sẽ vi phạm FK (document_id chưa có trong DB) ->
        // transaction abort (SQLState 25P02) -> rollback toàn bộ. Flush để ép thứ tự
        // ghi: documents + blocks xuống DB TRƯỚC khi insert chunk.
        documentRepository.saveAndFlush(doc);

        List<CanonicalBlock> blocks = result.blocks() == null ? List.of() : result.blocks();

        // stage=normalize: persist blocks nguyên trạng.
        CorrelationIds.setStage("normalize");
        List<BlockEntity> blockEntities = blocks.stream().map(b -> BlockEntity.builder()
                .id(safeUuid(b.blockId()))
                .documentId(documentId)
                .page(b.page())
                .blockOrder(b.order())
                .type(b.type())
                .text(nullToEmpty(b.text()))
                .markdown(nullToEmpty(b.markdown()))
                .html(nullToEmpty(b.html()))
                .latex(b.latex())
                .bbox(toDoubleArray(b.bbox()))
                .confidence((float) b.confidence())
                .headingPath(toStringArray(b.headingPath()))
                .parseEngine(b.parseEngine())
                .parseVersion(b.parseVersion())
                .build()).toList();
        blockRepository.saveAll(blockEntities);
        blockRepository.flush();   // blocks xuống DB trước khi dựng chunk
        log.info("normalize persisted_blocks={}", blockEntities.size());

        // stage=chunk: nhóm block -> chunk.
        CorrelationIds.setStage("chunk");
        List<ChunkBuilder.BuiltChunk> builtChunks = ChunkBuilder.build(documentId, blocks);
        log.info("chunk built_chunks={}", builtChunks.size());

        // stage=embed: embed + insert chunk (native, cast vector/tsvector + gán page_id hiện tại của doc).
        CorrelationIds.setStage("embed");
        UUID currentPageId = doc.getPageId();
        for (ChunkBuilder.BuiltChunk c : builtChunks) {
            String prefix = formatHeadingPrefix(c.headingPath());
            String textToEmbed = prefix.isBlank() ? c.text() : prefix + "\n" + c.text();
            float[] embedding = embeddingClient.embed(textToEmbed);
            chunkRepository.insertChunk(
                    c.id(), c.documentId(), currentPageId, c.headingPath(), c.sourceBlockIds(),
                    c.page(), c.bbox(), c.text(), embedding);
        }
        // Đảm bảo nếu user đã đổi Page trong lúc doc đang PARSING thì đồng bộ lại theo trạng thái mới nhất
        DocumentEntity refreshedDoc = documentRepository.findById(documentId).orElse(doc);
        if ((currentPageId == null && refreshedDoc.getPageId() != null)
                || (currentPageId != null && !currentPageId.equals(refreshedDoc.getPageId()))) {
            chunkRepository.updatePageIdByDocumentId(documentId, refreshedDoc.getPageId());
        }
        log.info("embed embedded_chunks={} dim={} page_id={}", builtChunks.size(), embeddingClient.dim(), refreshedDoc.getPageId());
        CorrelationIds.clearStage();

        // Cập nhật document: pages + stats + READY.
        int pages = result.source() != null ? result.source().pages() : 0;
        doc.setPages(pages);
        doc.setStatus("READY");
        doc.setStatsJson(engineBreakdownJson(result));
        documentRepository.save(doc);
        log.info("ingest done document_id={} pages={} status=READY", documentId, pages);
    }

    @Transactional(readOnly = true)
    public List<DocumentStatusResponse> listAll() {
        return listAll(null);
    }

    @Transactional(readOnly = true)
    public List<DocumentStatusResponse> listAll(String pageIdRaw) {
        List<DocumentEntity> docs;
        if (pageIdRaw == null) {
            docs = documentRepository.findAllByOrderByCreatedAtDesc();
        } else if (pageIdRaw.isBlank()) {
            docs = List.of();
        } else if ("unassigned".equalsIgnoreCase(pageIdRaw.strip())) {
            docs = documentRepository.findByPageIdIsNullOrderByCreatedAtDesc();
        } else {
            UUID pageId = parseNullableUuid(pageIdRaw);
            docs = pageId != null
                    ? documentRepository.findByPageIdOrderByCreatedAtDesc(pageId)
                    : List.of();
        }
        return docs.stream().map(this::toStatusResponse).toList();
    }

    @Transactional(readOnly = true)
    public DocumentStatusResponse getStatus(String documentIdRaw) {
        UUID documentId = safeUuid(documentIdRaw);
        DocumentEntity doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document không tồn tại: " + documentIdRaw));
        return toStatusResponse(doc);
    }

    @Transactional
    public DocumentStatusResponse assignPage(String documentIdRaw, String pageIdRaw) {
        UUID documentId = safeUuid(documentIdRaw);
        DocumentEntity doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document không tồn tại: " + documentIdRaw));
        UUID targetPageId = parseNullableUuid(pageIdRaw);
        doc.setPageId(targetPageId);
        DocumentEntity saved = documentRepository.saveAndFlush(doc);
        int updatedChunks = chunkRepository.updatePageIdByDocumentId(documentId, targetPageId);
        log.info("assign_page document_id={} target_page_id={} synced_chunks={}",
                documentId, targetPageId, updatedChunks);
        if (targetPageId != null && chunkMetadataBackfillService != null) {
            try {
                chunkMetadataBackfillService.backfillPage(targetPageId, false);
            } catch (Exception e) {
                log.warn("assign_page metadata backfill skipped page_id={} reason={}", targetPageId, e.getMessage());
            }
        }
        return toStatusResponse(saved);
    }

    @Transactional
    public DocumentStatusResponse updateLifecycle(String documentIdRaw, DocumentLifecycleUpdateRequest request) {
        UUID documentId = safeUuid(documentIdRaw);
        DocumentEntity doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document không tồn tại: " + documentIdRaw));
        if (request != null) {
            doc.setEffectiveFrom(request.effectiveFrom());
            doc.setEffectiveTo(request.effectiveTo());
            if (request.isActive() != null) {
                doc.setActive(request.isActive());
            }
        }
        DocumentEntity saved = documentRepository.saveAndFlush(doc);
        return toStatusResponse(saved);
    }

    private DocumentStatusResponse toStatusResponse(DocumentEntity doc) {
        Map<String, Integer> breakdown = parseBreakdown(doc.getStatsJson());
        return new DocumentStatusResponse(
                doc.getId().toString(),
                doc.getFilename(),
                doc.getStatus(),
                doc.getPages(),
                breakdown,
                doc.getPageId() != null ? doc.getPageId().toString() : null,
                doc.getEffectiveFrom(),
                doc.getEffectiveTo(),
                doc.isActive(),
                doc.isCurrentlyEffective()
        );
    }

    private static java.time.OffsetDateTime parseNullableOffsetDateTime(String raw) {
        if (raw == null || raw.isBlank() || "null".equalsIgnoreCase(raw.strip()) || "undefined".equalsIgnoreCase(raw.strip())) {
            return null;
        }
        String trimmed = raw.strip();
        try {
            return java.time.OffsetDateTime.parse(trimmed);
        } catch (Exception e) {
            try {
                java.time.LocalDate ld = java.time.LocalDate.parse(trimmed);
                return ld.atStartOfDay().atOffset(java.time.ZoneOffset.ofHours(7));
            } catch (Exception ex) {
                return null;
            }
        }
    }

    private static UUID parseNullableUuid(String raw) {
        if (raw == null || raw.isBlank() || "null".equalsIgnoreCase(raw.strip()) || "undefined".equalsIgnoreCase(raw.strip())) {
            return null;
        }
        try {
            return UUID.fromString(raw.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Xóa document cùng toàn bộ blocks và chunks (embedding pgvector + fulltext tsv). */
    @Transactional
    public void delete(String documentIdRaw) {
        UUID documentId = safeUuid(documentIdRaw);
        DocumentEntity doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document không tồn tại: " + documentIdRaw));
        CorrelationIds.putDocumentId(documentId.toString());
        chunkRepository.deleteByDocumentId(documentId);
        blockRepository.deleteByDocumentId(documentId);
        documentRepository.delete(doc);
        log.info("delete document_id={} and all associated chunks (embeddings) + blocks", documentId);
    }

    // --- helpers ---

    @Transactional
    public void markFailed(UUID documentId) {
        documentRepository.findById(documentId).ifPresent(doc -> {
            doc.setStatus("FAILED");
            documentRepository.save(doc);
        });
    }

    private String engineBreakdownJson(CanonicalDocument result) {
        try {
            Map<String, Integer> breakdown =
                    result.stats() != null ? result.stats().engineBreakdown() : Map.of();
            return objectMapper.writeValueAsString(Map.of("engine_breakdown",
                    breakdown == null ? Map.of() : breakdown));
        } catch (JacksonException e) {
            log.warn("Không serialize được stats_json: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> parseBreakdown(String statsJson) {
        if (statsJson == null || statsJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> root = objectMapper.readValue(statsJson, Map.class);
            Object bd = root.get("engine_breakdown");
            if (bd instanceof Map<?, ?> m) {
                return (Map<String, Integer>) m;
            }
        } catch (JacksonException e) {
            log.warn("Không parse được stats_json: {}", e.getMessage());
        }
        return Map.of();
    }

    private static UUID safeUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return UUID.randomUUID();
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return UUID.randomUUID();
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static double[] toDoubleArray(List<Double> list) {
        if (list == null) {
            return new double[]{0, 0, 0, 0};
        }
        double[] out = new double[list.size()];
        for (int i = 0; i < list.size(); i++) {
            out[i] = list.get(i) == null ? 0.0 : list.get(i);
        }
        return out;
    }

    private static String[] toStringArray(List<String> list) {
        return list == null ? new String[0] : list.toArray(new String[0]);
    }

    /**
     * Dựng breadcrumb chuỗi từ heading_path để gắn vào đầu văn bản trước khi sinh dense vector embedding.
     * Ví dụ: ["MASSAGE", "Cơ sở Nàng Sen Quận 1 (TP.HCM)"] -> "[MASSAGE > Cơ sở Nàng Sen Quận 1 (TP.HCM)]"
     */
    public static String formatHeadingPrefix(String[] headingPath) {
        if (headingPath == null || headingPath.length == 0) {
            return "";
        }
        List<String> valid = new ArrayList<>();
        for (String h : headingPath) {
            if (h != null && !h.isBlank()) {
                valid.add(h.trim());
            }
        }
        if (valid.isEmpty()) {
            return "";
        }
        return "[" + String.join(" > ", valid) + "]";
    }
}
