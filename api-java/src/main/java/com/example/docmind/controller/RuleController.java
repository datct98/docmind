package com.example.docmind.controller;

import com.example.docmind.dto.LearnedRuleDto;
import com.example.docmind.dto.RuleRequest;
import com.example.docmind.entity.LearnedRuleEntity;
import com.example.docmind.exception.ResourceNotFoundException;
import com.example.docmind.repository.LearnedRuleRepository;
import com.example.docmind.service.embedding.EmbeddingClient;
import jakarta.validation.Valid;
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

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import com.example.docmind.service.security.PromptSecurityGuard;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST Controller quản lý CRUD cho Bộ quy tắc rút kinh nghiệm (AI Playbook - F13).
 */
@RestController
@RequestMapping("/api/rules")
public class RuleController {

    private final LearnedRuleRepository learnedRuleRepository;
    private final EmbeddingClient embeddingClient;
    private final com.example.docmind.service.MetaLearningService metaLearningService;
    private final com.example.docmind.service.bot.FastApiAgentService fastApiAgentService;
    private final com.example.docmind.service.playbook.PlaybookUploadService playbookUploadService;

    @org.springframework.beans.factory.annotation.Autowired
    public RuleController(LearnedRuleRepository learnedRuleRepository,
                          EmbeddingClient embeddingClient,
                          @org.springframework.beans.factory.annotation.Autowired(required = false)
                          com.example.docmind.service.MetaLearningService metaLearningService,
                          @org.springframework.beans.factory.annotation.Autowired(required = false)
                          com.example.docmind.service.bot.FastApiAgentService fastApiAgentService,
                          @org.springframework.beans.factory.annotation.Autowired(required = false)
                          com.example.docmind.service.playbook.PlaybookUploadService playbookUploadService,
                          @org.springframework.beans.factory.annotation.Autowired(required = false)
                          com.example.docmind.repository.SampleDialogueRepository sampleDialogueRepository) {
        this.learnedRuleRepository = learnedRuleRepository;
        this.embeddingClient = embeddingClient;
        this.metaLearningService = metaLearningService;
        this.fastApiAgentService = fastApiAgentService;
        this.playbookUploadService = playbookUploadService != null
                ? playbookUploadService
                : new com.example.docmind.service.playbook.PlaybookUploadService(
                        learnedRuleRepository, sampleDialogueRepository, embeddingClient, null);
    }

    public RuleController(LearnedRuleRepository learnedRuleRepository,
                          EmbeddingClient embeddingClient,
                          com.example.docmind.service.MetaLearningService metaLearningService,
                          com.example.docmind.service.bot.FastApiAgentService fastApiAgentService,
                          com.example.docmind.service.playbook.PlaybookUploadService playbookUploadService) {
        this(learnedRuleRepository, embeddingClient, metaLearningService, fastApiAgentService, playbookUploadService, null);
    }

    public RuleController(LearnedRuleRepository learnedRuleRepository,
                          EmbeddingClient embeddingClient,
                          com.example.docmind.service.MetaLearningService metaLearningService,
                          com.example.docmind.service.bot.FastApiAgentService fastApiAgentService) {
        this(learnedRuleRepository, embeddingClient, metaLearningService, fastApiAgentService, null);
    }

    public RuleController(LearnedRuleRepository learnedRuleRepository,
                          EmbeddingClient embeddingClient,
                          com.example.docmind.service.MetaLearningService metaLearningService) {
        this(learnedRuleRepository, embeddingClient, metaLearningService, null, null);
    }

    public RuleController(LearnedRuleRepository learnedRuleRepository, EmbeddingClient embeddingClient) {
        this(learnedRuleRepository, embeddingClient, null, null, null);
    }

    @GetMapping
    public ResponseEntity<List<LearnedRuleDto>> getRules(
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String search,
            @RequestParam(name = "page_id", required = false) String pageIdRaw,
            @RequestParam(name = "include_global", required = false, defaultValue = "true") boolean includeGlobal,
            @RequestParam(name = "review_status", required = false) String reviewStatus
    ) {
        String safeCategory = (category == null) ? "" : category.strip();
        String safeSearch = (search == null) ? "" : search.strip();

        List<LearnedRuleEntity> entities;
        if (pageIdRaw != null && !pageIdRaw.isBlank()) {
            if ("__global__".equalsIgnoreCase(pageIdRaw.strip())) {
                entities = learnedRuleRepository.searchRulesByPage(active, safeCategory, safeSearch, null, true, true);
            } else {
                UUID pageId = parsePageId(pageIdRaw);
                if (pageId != null) {
                    entities = learnedRuleRepository.searchRulesByPage(active, safeCategory, safeSearch, pageId, includeGlobal, false);
                } else {
                    // Invalid pageId format: return only global rules, never leak all tenants' rules
                    entities = learnedRuleRepository.searchRulesByPage(active, safeCategory, safeSearch, null, true, true);
                }
            }
        } else if (pageIdRaw != null) {
            entities = learnedRuleRepository.searchRulesByPage(active, safeCategory, safeSearch, null, true, true);
        } else {
            entities = learnedRuleRepository.searchRules(active, safeCategory, safeSearch);
        }
        List<LearnedRuleDto> dtos = entities.stream()
                .filter(e -> reviewStatus == null || reviewStatus.isBlank()
                        || "ALL".equalsIgnoreCase(reviewStatus)
                        || reviewStatus.equalsIgnoreCase(e.getReviewStatus() != null ? e.getReviewStatus() : "APPROVED"))
                .map(LearnedRuleDto::fromEntity)
                .toList();
        return ResponseEntity.ok(dtos);
    }

    @PostMapping
    public ResponseEntity<LearnedRuleDto> createRule(@Valid @RequestBody RuleRequest request) {
        if (PromptSecurityGuard.isPoisonedRuleAttempt(request.generalizedRule())
                || PromptSecurityGuard.isPoisonedRuleAttempt(request.triggerCondition())
                || PromptSecurityGuard.isPoisonedRuleAttempt(request.rootCause())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Quy tắc bị từ chối do vi phạm tiêu chuẩn an toàn / cố ý đầu độc quy tắc."
            );
        }

        UUID id = UUID.randomUUID();
        UUID pageId = parsePageId(request.pageId());
        String category = (request.category() != null && !request.category().isBlank())
                ? request.category().strip()
                : "GENERAL";
        String genRule = request.generalizedRule().strip();
        String trigger = (request.triggerCondition() != null && !request.triggerCondition().isBlank())
                ? request.triggerCondition().strip()
                : "Khi xử lý tình huống liên quan đến: " + (genRule.length() > 80 ? genRule.substring(0, 80).strip() + "..." : genRule);
        String rootCause = request.rootCause() != null ? request.rootCause().strip() : "";

        List<String> kwList;
        if (request.retrievalKeywords() != null && !request.retrievalKeywords().isEmpty()) {
            kwList = request.retrievalKeywords().stream()
                    .filter(k -> k != null && !k.isBlank())
                    .map(String::strip)
                    .toList();
        } else {
            String keywordSource = (request.triggerCondition() != null && !request.triggerCondition().isBlank())
                    ? request.triggerCondition().strip() + " " + genRule
                    : genRule;
            kwList = com.example.docmind.service.MetaLearningService.extractKeywords(keywordSource);
        }
        String[] kwArray = kwList.toArray(new String[0]);
        boolean active = request.active() != null ? request.active() : true;
        String textToEmbed = trigger + " " + genRule;
        float[] embedding = embeddingClient.embed(textToEmbed);

        try {
            if (pageId != null) {
                learnedRuleRepository.insertRule(
                        id,
                        category,
                        trigger,
                        rootCause,
                        genRule,
                        kwArray,
                        active,
                        embedding,
                        pageId
                );
            } else {
                learnedRuleRepository.insertRule(
                        id,
                        category,
                        trigger,
                        rootCause,
                        genRule,
                        kwArray,
                        active,
                        embedding
                );
            }
        } catch (Exception e) {
            // Fallback sang JPA save cho test slice / mock
            LearnedRuleEntity entity = LearnedRuleEntity.builder()
                    .id(id)
                    .category(category)
                    .triggerCondition(trigger)
                    .rootCause(rootCause)
                    .generalizedRule(genRule)
                    .retrievalKeywords(kwArray)
                    .active(active)
                    .pageId(pageId)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
            learnedRuleRepository.save(entity);
        }

        LearnedRuleDto dto = new LearnedRuleDto(
                id,
                category,
                trigger,
                rootCause,
                genRule,
                Arrays.asList(kwArray),
                active,
                pageId != null ? pageId.toString() : null,
                Instant.now(),
                Instant.now()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @PutMapping("/{id}")
    public ResponseEntity<LearnedRuleDto> updateRule(@PathVariable UUID id, @RequestBody RuleRequest request) {
        if (request != null && (
                PromptSecurityGuard.isPoisonedRuleAttempt(request.generalizedRule())
                || PromptSecurityGuard.isPoisonedRuleAttempt(request.triggerCondition())
                || PromptSecurityGuard.isPoisonedRuleAttempt(request.rootCause()))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Quy tắc bị từ chối do vi phạm tiêu chuẩn an toàn / cố ý đầu độc quy tắc."
            );
        }

        LearnedRuleEntity entity = learnedRuleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy quy tắc với id: " + id));

        String category = (request.category() != null && !request.category().isBlank())
                ? request.category().strip()
                : entity.getCategory();
        String genRule = (request.generalizedRule() != null && !request.generalizedRule().isBlank())
                ? request.generalizedRule().strip()
                : entity.getGeneralizedRule();
        String trigger = (request.triggerCondition() != null && !request.triggerCondition().isBlank())
                ? request.triggerCondition().strip()
                : (entity.getTriggerCondition() != null && !entity.getTriggerCondition().isBlank()
                        ? entity.getTriggerCondition()
                        : "Khi xử lý tình huống liên quan đến: " + (genRule.length() > 80 ? genRule.substring(0, 80).strip() + "..." : genRule));
        String rootCause = request.rootCause() != null ? request.rootCause() : entity.getRootCause();

        String[] kw;
        if (request.retrievalKeywords() != null && !request.retrievalKeywords().isEmpty()) {
            kw = request.retrievalKeywords().stream()
                    .filter(k -> k != null && !k.isBlank())
                    .map(String::strip)
                    .toArray(String[]::new);
        } else if (entity.getRetrievalKeywords() != null && entity.getRetrievalKeywords().length > 0) {
            kw = entity.getRetrievalKeywords();
        } else {
            String keywordSource = (request.triggerCondition() != null && !request.triggerCondition().isBlank())
                    ? request.triggerCondition().strip() + " " + genRule
                    : genRule;
            kw = com.example.docmind.service.MetaLearningService.extractKeywords(keywordSource).toArray(new String[0]);
        }

        boolean active = request.active() != null ? request.active() : entity.isActive();
        UUID pageId = request.pageId() != null ? parsePageId(request.pageId()) : entity.getPageId();

        float[] embedding = null;
        if (!trigger.equals(entity.getTriggerCondition()) || !genRule.equals(entity.getGeneralizedRule())) {
            embedding = embeddingClient.embed(trigger + " " + genRule);
        }

        boolean ruleTextChanged = entity.getGeneralizedRule() != null && !genRule.equals(entity.getGeneralizedRule());
        String previousRuleText = ruleTextChanged ? entity.getGeneralizedRule() : entity.getPreviousRuleText();
        int nextVersion = ruleTextChanged ? Math.max(1, entity.getVersion()) + 1 : Math.max(1, entity.getVersion());
        double nextConfidence = ruleTextChanged
                ? 0.90
                : ((active && !entity.isActive())
                        ? Math.max(0.85, entity.getConfidenceScore())
                        : (entity.getConfidenceScore() <= 0.0 ? 0.85 : entity.getConfidenceScore()));
        int nextFailCount = (ruleTextChanged || (active && !entity.isActive())) ? 0 : entity.getFailCount();
        String nextQuarantineReason = active ? null : entity.getQuarantinedReason();

        try {
            if (ruleTextChanged || (active && !entity.isActive())) {
                learnedRuleRepository.updateRuleEvolutionMetadata(
                        id, nextVersion, previousRuleText, nextConfidence, nextQuarantineReason
                );
            }
            if (request.pageId() != null || entity.getPageId() != null) {
                learnedRuleRepository.updateRuleWithEmbedding(
                        id, category, trigger, rootCause, genRule, kw, active, embedding, pageId
                );
            } else {
                learnedRuleRepository.updateRuleWithEmbedding(
                        id, category, trigger, rootCause, genRule, kw, active, embedding
                );
            }
        } catch (Exception e) {
            entity.setCategory(category);
            entity.setTriggerCondition(trigger);
            entity.setRootCause(rootCause);
            entity.setGeneralizedRule(genRule);
            entity.setRetrievalKeywords(kw);
            entity.setActive(active);
            entity.setPageId(pageId);
            entity.setVersion(nextVersion);
            entity.setPreviousRuleText(previousRuleText);
            entity.setConfidenceScore(nextConfidence);
            entity.setFailCount(nextFailCount);
            entity.setQuarantinedReason(nextQuarantineReason);
            entity.setUpdatedAt(Instant.now());
            learnedRuleRepository.save(entity);
        }

        LearnedRuleDto dto = new LearnedRuleDto(
                id,
                category,
                trigger,
                rootCause,
                genRule,
                Arrays.asList(kw),
                active,
                pageId != null ? pageId.toString() : null,
                nextConfidence,
                entity.getAppliedCount(),
                entity.getSuccessCount(),
                nextFailCount,
                nextVersion,
                previousRuleText,
                nextQuarantineReason,
                entity.getLastAppliedAt(),
                entity.getCreatedAt(),
                Instant.now()
        );
        return ResponseEntity.ok(dto);
    }

    @PostMapping("/{id}/rollback")
    public ResponseEntity<LearnedRuleDto> rollbackRule(@PathVariable UUID id) {
        if (metaLearningService != null) {
            return ResponseEntity.ok(metaLearningService.rollbackRuleVersion(id));
        }

        LearnedRuleEntity entity = learnedRuleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy quy tắc với id: " + id));
        if (entity.getPreviousRuleText() == null || entity.getPreviousRuleText().isBlank()) {
            throw new IllegalArgumentException("Quy tắc này chưa có phiên bản trước để khôi phục.");
        }

        String restoredRule = entity.getPreviousRuleText().strip();
        int currentVersion = entity.getVersion() <= 0 ? 1 : entity.getVersion();
        int newVersion = Math.max(1, currentVersion - 1);
        double restoredConfidence = 0.85;
        String trigger = entity.getTriggerCondition() != null ? entity.getTriggerCondition() : "";
        float[] embedding = embeddingClient.embed(trigger + " " + restoredRule);

        entity.setGeneralizedRule(restoredRule);
        entity.setPreviousRuleText(null);
        entity.setVersion(newVersion);
        entity.setActive(true);
        entity.setConfidenceScore(restoredConfidence);
        entity.setFailCount(0);
        entity.setQuarantinedReason(null);
        entity.setUpdatedAt(Instant.now());

        try {
            if (entity.getPageId() != null) {
                learnedRuleRepository.updateRuleWithEmbedding(
                        entity.getId(), entity.getCategory(), trigger, entity.getRootCause(),
                        restoredRule, entity.getRetrievalKeywords(), true, embedding, entity.getPageId()
                );
            } else {
                learnedRuleRepository.updateRuleWithEmbedding(
                        entity.getId(), entity.getCategory(), trigger, entity.getRootCause(),
                        restoredRule, entity.getRetrievalKeywords(), true, embedding
                );
            }
            learnedRuleRepository.updateRuleEvolutionMetadata(entity.getId(), newVersion, null, restoredConfidence, null);
            learnedRuleRepository.recordRuleNegativeFeedback(entity.getId(), 0, restoredConfidence, true, null);
            learnedRuleRepository.save(entity);
        } catch (Exception e) {
            learnedRuleRepository.save(entity);
        }

        return ResponseEntity.ok(LearnedRuleDto.fromEntity(entity));
    }

    @PostMapping("/nightly-extract")
    public ResponseEntity<java.util.Map<String, Object>> extractNightlyRules(
            @RequestBody com.example.docmind.client.FastApiAgentClient.NightlyExtractRequest request
    ) {
        com.example.docmind.service.bot.FastApiAgentService svc = fastApiAgentService != null
                ? fastApiAgentService
                : new com.example.docmind.service.bot.FastApiAgentService((com.example.docmind.config.DocServiceProperties) null);

        var response = svc.extractNightlySamples(request);
        UUID pageId = parsePageId(request.pageId());
        java.util.List<LearnedRuleDto> savedPendingRules = new java.util.ArrayList<>();
        java.util.List<com.example.docmind.client.FastApiAgentClient.ExtractedSampleDto> rejectedSamples = new java.util.ArrayList<>();

        if (response != null && response.samples() != null) {
            for (var sample : response.samples()) {
                if (!sample.securityPassed() || "REJECTED".equalsIgnoreCase(sample.reviewStatus())) {
                    rejectedSamples.add(sample);
                    continue;
                }
                UUID id = UUID.randomUUID();
                String[] kws = sample.retrievalKeywords() != null
                        ? sample.retrievalKeywords().toArray(new String[0])
                        : new String[]{"tư vấn", "chốt lịch"};
                float[] embedding = embeddingClient.embed(
                        (sample.triggerCondition() != null ? sample.triggerCondition() : "")
                        + " " + (sample.generalizedRule() != null ? sample.generalizedRule() : "")
                );

                LearnedRuleEntity entity = LearnedRuleEntity.builder()
                        .id(id)
                        .category(sample.category() != null ? sample.category() : "PSYCHOLOGICAL_PERSUASION")
                        .triggerCondition(sample.triggerCondition() != null ? sample.triggerCondition() : "")
                        .rootCause(sample.rootCause() != null ? sample.rootCause() : "")
                        .generalizedRule(sample.generalizedRule() != null ? sample.generalizedRule() : "")
                        .retrievalKeywords(kws)
                        .active(false) // Chờ Admin bấm 1-Click Approve trong Staging Queue
                        .pageId(pageId)
                        .confidenceScore(0.90)
                        .dimension(com.example.docmind.entity.HarnessDimension.fromString(sample.dimension()))
                        .cueSignal(sample.cueSignal())
                        .contrastiveEvidence(sample.contrastiveEvidence())
                        .reviewStatus("PENDING_REVIEW")
                        .securityAuditNotes(sample.securityAuditNotes())
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();

                try {
                    if (pageId != null) {
                        learnedRuleRepository.insertRule(
                                id, entity.getCategory(), entity.getTriggerCondition(), entity.getRootCause(),
                                entity.getGeneralizedRule(), kws, false, embedding, pageId
                        );
                    } else {
                        learnedRuleRepository.insertRule(
                                id, entity.getCategory(), entity.getTriggerCondition(), entity.getRootCause(),
                                entity.getGeneralizedRule(), kws, false, embedding
                        );
                    }
                } catch (Exception ignored) {
                }
                learnedRuleRepository.save(entity);
                savedPendingRules.add(LearnedRuleDto.fromEntity(entity));
            }
        }

        return ResponseEntity.ok(java.util.Map.of(
                "engine", response != null && response.engine() != null ? response.engine() : "langgraph-nightly-auditor",
                "passed_count", savedPendingRules.size(),
                "rejected_count", rejectedSamples.size(),
                "pending_rules", savedPendingRules,
                "rejected_samples", rejectedSamples
        ));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<LearnedRuleDto> approveStagingRule(@PathVariable UUID id) {
        LearnedRuleEntity entity = learnedRuleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy quy tắc với id: " + id));
        entity.setReviewStatus("APPROVED");
        entity.setActive(true);
        entity.setQuarantinedReason(null);
        entity.setUpdatedAt(Instant.now());

        float[] embedding = embeddingClient.embed(
                (entity.getTriggerCondition() != null ? entity.getTriggerCondition() : "")
                + " " + (entity.getGeneralizedRule() != null ? entity.getGeneralizedRule() : "")
        );
        try {
            if (entity.getPageId() != null) {
                learnedRuleRepository.updateRuleWithEmbedding(
                        entity.getId(), entity.getCategory(), entity.getTriggerCondition(), entity.getRootCause(),
                        entity.getGeneralizedRule(), entity.getRetrievalKeywords(), true, embedding, entity.getPageId()
                );
            } else {
                learnedRuleRepository.updateRuleWithEmbedding(
                        entity.getId(), entity.getCategory(), entity.getTriggerCondition(), entity.getRootCause(),
                        entity.getGeneralizedRule(), entity.getRetrievalKeywords(), true, embedding
                );
            }
        } catch (Exception ignored) {
        }
        learnedRuleRepository.save(entity);
        return ResponseEntity.ok(LearnedRuleDto.fromEntity(entity));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<LearnedRuleDto> rejectStagingRule(@PathVariable UUID id) {
        LearnedRuleEntity entity = learnedRuleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy quy tắc với id: " + id));
        entity.setReviewStatus("REJECTED");
        entity.setActive(false);
        entity.setUpdatedAt(Instant.now());
        learnedRuleRepository.save(entity);
        return ResponseEntity.ok(LearnedRuleDto.fromEntity(entity));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRule(@PathVariable UUID id) {
        if (!learnedRuleRepository.existsById(id)) {
            throw new ResourceNotFoundException("Không tìm thấy quy tắc với id: " + id);
        }
        learnedRuleRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    public record PlaybookRawUploadRequest(
            String content,
            String format,
            @com.fasterxml.jackson.annotation.JsonProperty("page_id") String pageId,
            @com.fasterxml.jackson.annotation.JsonProperty("replace_existing") Boolean replaceExisting
    ) {}

    @PostMapping("/upload-playbook")
    public ResponseEntity<com.example.docmind.service.playbook.PlaybookUploadService.PlaybookUploadResult> uploadPlaybookRaw(
            @RequestBody PlaybookRawUploadRequest request
    ) {
        if (request == null || request.content() == null || request.content().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nội dung Playbook tải lên không được để trống.");
        }
        try {
            var res = playbookUploadService.uploadPlaybookContent(
                    request.content(),
                    request.format(),
                    request.pageId(),
                    Boolean.TRUE.equals(request.replaceExisting())
            );
            return ResponseEntity.ok(res);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @PostMapping(value = "/upload-playbook-file", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<com.example.docmind.service.playbook.PlaybookUploadService.PlaybookUploadResult> uploadPlaybookFile(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @RequestParam(name = "page_id", required = false) String pageId,
            @RequestParam(name = "format", required = false) String format,
            @RequestParam(name = "replace_existing", required = false, defaultValue = "false") boolean replaceExisting
    ) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File Playbook tải lên không được để trống.");
        }
        try {
            String content = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
            String hint = (format != null && !format.isBlank()) ? format : file.getOriginalFilename();
            var res = playbookUploadService.uploadPlaybookContent(content, hint, pageId, replaceExisting);
            return ResponseEntity.ok(res);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không thể đọc file Playbook: " + e.getMessage(), e);
        }
    }

    @GetMapping("/sample-dialogues")
    public ResponseEntity<List<com.example.docmind.dto.SampleDialogueDto>> getSampleDialogues(
            @RequestParam(name = "page_id", required = false) String pageId,
            @RequestParam(name = "active", required = false) Boolean active,
            @RequestParam(name = "include_global", required = false, defaultValue = "true") boolean includeGlobal,
            @RequestParam(name = "review_status", required = false) String reviewStatus
    ) {
        return ResponseEntity.ok(playbookUploadService.getSampleDialogues(pageId, active, includeGlobal, reviewStatus));
    }

    private static UUID parsePageId(String pageIdRaw) {
        if (pageIdRaw == null || pageIdRaw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(pageIdRaw.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
