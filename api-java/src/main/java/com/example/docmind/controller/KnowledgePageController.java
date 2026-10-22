package com.example.docmind.controller;

import com.example.docmind.dto.IndustryPersonaTemplates;
import com.example.docmind.dto.KnowledgePageDto;
import com.example.docmind.dto.KnowledgePageRequest;
import com.example.docmind.dto.PagePersonaConfig;
import com.example.docmind.entity.DocumentEntity;
import com.example.docmind.entity.KnowledgePageEntity;
import com.example.docmind.exception.ResourceNotFoundException;
import com.example.docmind.repository.DocumentRepository;
import com.example.docmind.repository.KnowledgePageRepository;
import com.example.docmind.repository.LearnedRuleRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * REST Controller quản lý Thư mục / Page tri thức 1 cấp phẳng (/api/pages).
 * Mỗi Page đại diện cho 1 ngành nghề và tệp đối tượng khách hàng độc lập.
 */
@RestController
@RequestMapping("/api/pages")
public class KnowledgePageController {

    private final KnowledgePageRepository pageRepository;
    private final DocumentRepository documentRepository;
    private final LearnedRuleRepository learnedRuleRepository;

    public KnowledgePageController(KnowledgePageRepository pageRepository,
                                   DocumentRepository documentRepository,
                                   LearnedRuleRepository learnedRuleRepository) {
        this.pageRepository = pageRepository;
        this.documentRepository = documentRepository;
        this.learnedRuleRepository = learnedRuleRepository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<List<KnowledgePageDto>> listPages() {
        List<KnowledgePageEntity> entities = pageRepository.findAllByOrderByCreatedAtDesc();
        List<KnowledgePageDto> dtos = entities.stream().map(entity -> {
            long docCount = documentRepository.countByPageId(entity.getId());
            long ruleCount = learnedRuleRepository.countByPageId(entity.getId());
            return KnowledgePageDto.fromEntity(entity, docCount, ruleCount);
        }).toList();
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/industry-templates")
    public ResponseEntity<List<IndustryPersonaTemplates.IndustryTemplateDto>> listIndustryTemplates() {
        return ResponseEntity.ok(IndustryPersonaTemplates.allTemplates());
    }

    @PostMapping
    @Transactional
    public ResponseEntity<KnowledgePageDto> createPage(@Valid @RequestBody KnowledgePageRequest request) {
        String name = request.name().strip();
        String industry = request.industry() != null ? request.industry().strip() : "";
        String targetAudience = request.targetAudience() != null ? request.targetAudience().strip() : "";
        String description = request.description() != null ? request.description().strip() : "";
        PagePersonaConfig effectiveConfig = IndustryPersonaTemplates.resolveEffectiveConfig(
                request.personaConfig(), name, industry, targetAudience, description
        );
        KnowledgePageEntity entity = KnowledgePageEntity.builder()
                .id(UUID.randomUUID())
                .name(name)
                .industry(industry)
                .targetAudience(targetAudience)
                .description(description)
                .personaConfigJson(effectiveConfig.toJson())
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
        KnowledgePageEntity saved = pageRepository.save(entity);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(KnowledgePageDto.fromEntity(saved, 0L, 0L));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<KnowledgePageDto> updatePage(@PathVariable UUID id,
                                                       @Valid @RequestBody KnowledgePageRequest request) {
        KnowledgePageEntity entity = pageRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy Page với id: " + id));
        entity.setName(request.name().strip());
        if (request.industry() != null) {
            entity.setIndustry(request.industry().strip());
        }
        if (request.targetAudience() != null) {
            entity.setTargetAudience(request.targetAudience().strip());
        }
        if (request.description() != null) {
            entity.setDescription(request.description().strip());
        }
        if (request.personaConfig() != null) {
            PagePersonaConfig effectiveConfig = IndustryPersonaTemplates.resolveEffectiveConfig(
                    request.personaConfig(),
                    entity.getName(),
                    entity.getIndustry(),
                    entity.getTargetAudience(),
                    entity.getDescription()
            );
            entity.setPersonaConfig(effectiveConfig);
        }
        entity.setUpdatedAt(OffsetDateTime.now());
        KnowledgePageEntity saved = pageRepository.save(entity);
        long docCount = documentRepository.countByPageId(id);
        long ruleCount = learnedRuleRepository.countByPageId(id);
        return ResponseEntity.ok(KnowledgePageDto.fromEntity(saved, docCount, ruleCount));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> deletePage(@PathVariable UUID id) {
        KnowledgePageEntity entity = pageRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy Page với id: " + id));
        // Đưa các tài liệu thuộc Page này về trạng thái Chưa phân loại (page_id = null)
        List<DocumentEntity> docs = documentRepository.findByPageIdOrderByCreatedAtDesc(id);
        for (DocumentEntity doc : docs) {
            doc.setPageId(null);
        }
        documentRepository.saveAll(docs);
        pageRepository.delete(entity);
        return ResponseEntity.noContent().build();
    }
}
