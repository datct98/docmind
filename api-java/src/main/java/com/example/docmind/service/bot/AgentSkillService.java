package com.example.docmind.service.bot;

import com.example.docmind.dto.bot.AgentSkillDto;
import com.example.docmind.dto.bot.AgentSkillRequest;
import com.example.docmind.entity.AgentSkillEntity;
import com.example.docmind.exception.ResourceNotFoundException;
import com.example.docmind.repository.AgentSkillRepository;
import com.example.docmind.service.security.PromptSecurityGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Service quản lý Kho Kỹ năng Agent (Agent Skill Registry: PSYCHOLOGY, SALES_STRATEGY, COMMUNICATION)
 * và tự động đồng bộ kỹ năng mới khi Quản trị viên dạy AI về tâm lý/chiến thuật chốt sale.
 */
@Service
public class AgentSkillService {

    private static final Logger log = LoggerFactory.getLogger(AgentSkillService.class);
    private static final Set<String> VALID_CATEGORIES = Set.of("PSYCHOLOGY", "SALES_STRATEGY", "COMMUNICATION");

    private final AgentSkillRepository agentSkillRepository;
    private final TransactionTemplate transactionTemplate;

    public AgentSkillService(AgentSkillRepository agentSkillRepository) {
        this(agentSkillRepository, (PlatformTransactionManager) null);
    }

    @Autowired
    public AgentSkillService(AgentSkillRepository agentSkillRepository,
                             @Nullable PlatformTransactionManager transactionManager) {
        this.agentSkillRepository = agentSkillRepository;
        if (transactionManager != null) {
            this.transactionTemplate = new TransactionTemplate(transactionManager);
            this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        } else {
            this.transactionTemplate = null;
        }
    }

    public List<AgentSkillDto> getSkills(String category, Boolean active, String search, String pageIdRaw, boolean includeGlobal) {
        List<AgentSkillEntity> all = agentSkillRepository.findAllOrdered();
        if (all.isEmpty()) {
            return CustomerInsightEngine.builtInDefaultSkills();
        }
        String normCat = category != null ? category.strip().toUpperCase(Locale.ROOT) : "";
        String normSearch = search != null ? search.strip().toLowerCase(Locale.ROOT) : "";
        boolean globalOnly = pageIdRaw != null && "__global__".equalsIgnoreCase(pageIdRaw.strip());
        UUID targetPageId = (!globalOnly) ? parsePageId(pageIdRaw) : null;

        return all.stream()
                .filter(s -> active == null || s.isActive() == active)
                .filter(s -> normCat.isEmpty() || normCat.equalsIgnoreCase(s.getCategory()))
                .filter(s -> {
                    if (globalOnly) {
                        return s.getPageId() == null;
                    }
                    if (targetPageId != null) {
                        return includeGlobal
                                ? (s.getPageId() == null || targetPageId.equals(s.getPageId()))
                                : targetPageId.equals(s.getPageId());
                    }
                    return s.getPageId() == null;
                })
                .filter(s -> {
                    if (normSearch.isEmpty()) {
                        return true;
                    }
                    String hay = ((s.getName() != null ? s.getName() : "") + " "
                            + (s.getCode() != null ? s.getCode() : "") + " "
                            + (s.getDescription() != null ? s.getDescription() : "") + " "
                            + (s.getTriggerSignals() != null ? s.getTriggerSignals() : "") + " "
                            + (s.getTacticalInstructions() != null ? s.getTacticalInstructions() : ""))
                            .toLowerCase(Locale.ROOT);
                    return hay.contains(normSearch);
                })
                .map(AgentSkillDto::fromEntity)
                .toList();
    }

    public List<AgentSkillDto> getActiveSkillsForPage(UUID pageId) {
        try {
            List<AgentSkillEntity> activeEntities = agentSkillRepository.findActiveForPage(pageId);
            if (activeEntities != null && !activeEntities.isEmpty()) {
                return activeEntities.stream().map(AgentSkillDto::fromEntity).toList();
            }
        } catch (Exception e) {
            log.warn("Không thể truy vấn agent_skills từ DB ({}), sử dụng bộ kỹ năng mặc định.", e.getMessage());
        }
        return CustomerInsightEngine.builtInDefaultSkills();
    }

    /**
     * Phân tích mô tả kỹ năng bằng ngôn ngữ tự nhiên (1-Step Smart Skill) thành đầy đủ cấu trúc
     * AgentSkillDto chuẩn (code, name, category, description, trigger_signals, tactical_instructions,
     * good_example, anti_pattern, priority) để người dùng không cần tự điền từng ô kỹ thuật.
     */
    public AgentSkillDto smartParseSkill(AgentSkillRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dữ liệu yêu cầu không được để trống.");
        }
        String raw = firstNonBlank(request.rawInstruction(), request.tacticalInstructions(), request.description(), request.name());
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vui lòng nhập mô tả kỹ năng hoặc tình huống muốn dạy AI.");
        }
        if (PromptSecurityGuard.isPoisonedRuleAttempt(raw)
                || (request.name() != null && PromptSecurityGuard.isPoisonedRuleAttempt(request.name()))
                || (request.tacticalInstructions() != null && PromptSecurityGuard.isPoisonedRuleAttempt(request.tacticalInstructions()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chỉ dẫn chiến thuật chứa nội dung vi phạm an toàn bảo mật.");
        }

        String cleanRaw = PromptSecurityGuard.sanitizeUntrustedContent(raw.strip());
        String lower = cleanRaw.toLowerCase(Locale.ROOT);

        String category = hasExplicitCategory(request.category())
                ? normalizeCategory(request.category())
                : inferCategoryFromText(lower);

        String name = (request.name() != null && !request.name().isBlank())
                ? PromptSecurityGuard.sanitizeUntrustedContent(request.name().strip())
                : inferSkillNameFromText(cleanRaw, lower, category);

        String code = (request.code() != null && !request.code().isBlank())
                ? sanitizeCode(request.code())
                : generateCode(category, name);

        String triggerSignals = (request.triggerSignals() != null && !request.triggerSignals().isBlank())
                ? safeText(request.triggerSignals())
                : inferTriggerSignalsFromText(cleanRaw, lower);

        String description = (request.description() != null && !request.description().isBlank())
                ? safeText(request.description())
                : inferDescriptionFromText(name, cleanRaw, category);

        String tacticalInstructions = (request.tacticalInstructions() != null && !request.tacticalInstructions().isBlank())
                ? safeText(request.tacticalInstructions())
                : inferTacticalInstructionsFromText(cleanRaw, lower);

        String goodExample = (request.goodExample() != null && !request.goodExample().isBlank())
                ? safeText(request.goodExample())
                : inferGoodExampleFromText(lower);

        String antiPattern = (request.antiPattern() != null && !request.antiPattern().isBlank())
                ? safeText(request.antiPattern())
                : inferAntiPatternFromText(lower);

        int priority = request.priority() != null ? Math.max(1, Math.min(100, request.priority())) : 90;
        boolean active = request.active() != null ? request.active() : true;
        UUID pageId = parsePageId(request.pageId());

        Instant now = Instant.now();
        return new AgentSkillDto(
                null,
                code,
                name,
                category,
                description,
                triggerSignals,
                tacticalInstructions,
                goodExample,
                antiPattern,
                priority,
                active,
                pageId != null ? pageId.toString() : null,
                0,
                0,
                0.95,
                now,
                now
        );
    }

    @Transactional
    public AgentSkillDto createSkill(AgentSkillRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tên kỹ năng (name) không được để trống.");
        }
        boolean hasName = request.name() != null && !request.name().isBlank();
        boolean hasRaw = request.rawInstruction() != null && !request.rawInstruction().isBlank();
        boolean hasTactical = request.tacticalInstructions() != null && !request.tacticalInstructions().isBlank();
        if (!hasName && !hasRaw && !hasTactical) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tên kỹ năng (name) không được để trống.");
        }
        if ((request.tacticalInstructions() != null && PromptSecurityGuard.isPoisonedRuleAttempt(request.tacticalInstructions()))
                || (request.rawInstruction() != null && PromptSecurityGuard.isPoisonedRuleAttempt(request.rawInstruction()))
                || (request.name() != null && PromptSecurityGuard.isPoisonedRuleAttempt(request.name()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chỉ dẫn chiến thuật chứa nội dung vi phạm an toàn bảo mật.");
        }

        AgentSkillDto parsed = (hasRaw || !hasName || request.tacticalInstructions() == null || request.tacticalInstructions().isBlank())
                ? smartParseSkill(request)
                : null;

        String category = hasExplicitCategory(request.category())
                ? normalizeCategory(request.category())
                : (parsed != null ? parsed.category() : "SALES_STRATEGY");
        String name = hasName
                ? PromptSecurityGuard.sanitizeUntrustedContent(request.name().strip())
                : parsed.name();
        String code = (request.code() != null && !request.code().isBlank())
                ? sanitizeCode(request.code())
                : generateCode(category, name);

        String description = (request.description() != null && !request.description().isBlank())
                ? safeText(request.description())
                : (parsed != null ? parsed.description() : "");
        String triggerSignals = (request.triggerSignals() != null && !request.triggerSignals().isBlank())
                ? safeText(request.triggerSignals())
                : (parsed != null ? parsed.triggerSignals() : "");
        String tacticalInstructions = (request.tacticalInstructions() != null && !request.tacticalInstructions().isBlank())
                ? safeText(request.tacticalInstructions())
                : (parsed != null ? parsed.tacticalInstructions() : "");
        String goodExample = (request.goodExample() != null && !request.goodExample().isBlank())
                ? safeText(request.goodExample())
                : (parsed != null ? parsed.goodExample() : "");
        String antiPattern = (request.antiPattern() != null && !request.antiPattern().isBlank())
                ? safeText(request.antiPattern())
                : (parsed != null ? parsed.antiPattern() : "");

        Instant now = Instant.now();
        UUID targetPageId = parsePageId(request.pageId());
        int resolvedPriority = request.priority() != null
                ? Math.max(1, Math.min(100, request.priority()))
                : (parsed != null ? parsed.priority() : 90);
        boolean resolvedActive = request.active() != null ? request.active() : true;

        AgentSkillDto candidateDto = new AgentSkillDto(
                null,
                code,
                name,
                category,
                description,
                triggerSignals,
                tacticalInstructions,
                goodExample,
                antiPattern,
                resolvedPriority,
                resolvedActive,
                targetPageId != null ? targetPageId.toString() : null,
                0,
                0,
                0.95,
                now,
                now
        );

        String strategy = request.resolutionStrategy() != null && !request.resolutionStrategy().isBlank()
                ? request.resolutionStrategy().strip().toUpperCase(Locale.ROOT)
                : "AUTO";

        List<AgentSkillEntity> existingSkills = agentSkillRepository.findActiveForPage(targetPageId);
        if (existingSkills == null) {
            existingSkills = List.of();
        }

        // 1. Nếu Quản trị viên đã xác nhận GHI ĐÈ (OVERRIDE) từ Modal cảnh báo xung đột trên UI
        if ("OVERRIDE".equals(strategy)) {
            AgentSkillEntity targetConflict = resolveConflictTarget(existingSkills, request.conflictSkillId(), candidateDto);
            if (targetConflict != null && java.util.Objects.equals(targetConflict.getPageId(), targetPageId)) {
                String oldName = targetConflict.getName();
                targetConflict.setCode(code);
                targetConflict.setName(name);
                targetConflict.setCategory(category);
                targetConflict.setDescription(description);
                targetConflict.setTriggerSignals(triggerSignals);
                targetConflict.setTacticalInstructions(tacticalInstructions);
                targetConflict.setGoodExample(goodExample);
                targetConflict.setAntiPattern(antiPattern);
                targetConflict.setPriority(Math.max(resolvedPriority, targetConflict.getPriority()));
                targetConflict.setActive(true);
                targetConflict.setUpdatedAt(now);
                AgentSkillEntity saved = agentSkillRepository.save(targetConflict);
                log.info("stage=agent_skill_governance action=override_in_place id={} old_name='{}' new_name='{}' page_id={}",
                        saved.getId(), oldName, saved.getName(), saved.getPageId());
                return AgentSkillDto.fromEntity(saved).withGovernance(
                        "OVERRIDDEN",
                        "Đã ghi đè kỹ năng '" + oldName + "' bằng kỹ năng mới '" + saved.getName() + "'."
                );
            }
            if (targetConflict != null && targetConflict.getPageId() == null && targetPageId != null) {
                int overridePriority = Math.min(100, Math.max(98, Math.max(resolvedPriority, targetConflict.getPriority() + 4)));
                AgentSkillEntity pageOverrideEntity = AgentSkillEntity.builder()
                        .id(UUID.randomUUID())
                        .code(code)
                        .name(name)
                        .category(category)
                        .description(description)
                        .triggerSignals(triggerSignals)
                        .tacticalInstructions(tacticalInstructions)
                        .goodExample(goodExample)
                        .antiPattern(antiPattern)
                        .priority(overridePriority)
                        .active(true)
                        .pageId(targetPageId)
                        .confidenceScore(0.95)
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
                AgentSkillEntity saved = agentSkillRepository.save(pageOverrideEntity);
                log.info("stage=agent_skill_governance action=override_global_for_page id={} global_code={} page_id={} priority={}",
                        saved.getId(), targetConflict.getCode(), targetPageId, overridePriority);
                return AgentSkillDto.fromEntity(saved).withGovernance(
                        "OVERRIDDEN",
                        "Đã áp dụng kỹ năng riêng cho Page (Ưu tiên " + overridePriority + ") ghi đè lên kỹ năng mặc định '" + targetConflict.getName() + "'."
                );
            }
        }

        // 2. Nếu ở chế độ AUTO: Kiểm tra Xung đột Chiến thuật (Conflict) trước tiên
        if ("AUTO".equals(strategy) && !existingSkills.isEmpty()) {
            for (AgentSkillEntity existing : existingSkills) {
                if (existing == null || !existing.isActive()) {
                    continue;
                }
                ConflictAnalysis conflict = analyzeTacticalConflict(existing, candidateDto);
                if (conflict != null) {
                    log.info("stage=agent_skill_governance action=conflict_detected existing_code={} candidate_name='{}' page_id={}",
                            existing.getCode(), candidateDto.name(), targetPageId);
                    return AgentSkillDto.conflictDetected(
                            candidateDto,
                            AgentSkillDto.fromEntity(existing),
                            conflict.reason(),
                            conflict.recommendedAction()
                    );
                }
            }

            // 3. Nếu KHÔNG xung đột: Kiểm tra Trùng lặp / Bổ trợ (Duplicate / Complementary) để TỰ ĐỘNG GỘP (Auto-Merge)
            AgentSkillEntity sameScopeDup = findDuplicateOrComplementary(existingSkills, candidateDto, targetPageId, true);
            if (sameScopeDup != null) {
                AgentSkillEntity merged = mergeSkillIntoExisting(sameScopeDup, candidateDto, now);
                AgentSkillEntity saved = agentSkillRepository.save(merged);
                log.info("stage=agent_skill_governance action=auto_merge_same_scope id={} code={} page_id={}",
                        saved.getId(), saved.getCode(), saved.getPageId());
                return AgentSkillDto.fromEntity(saved).withGovernance(
                        "AUTO_MERGED",
                        "Phát hiện kỹ năng tương đồng — Hệ thống đã tự động gộp tín hiệu kích hoạt & chỉ dẫn bổ sung vào kỹ năng: " + saved.getName()
                );
            }

            if (targetPageId != null) {
                AgentSkillEntity globalDup = findDuplicateOrComplementary(existingSkills, candidateDto, targetPageId, false);
                if (globalDup != null) {
                    String mergedTriggers = mergeCommaSeparatedSignals(globalDup.getTriggerSignals(), candidateDto.triggerSignals());
                    String mergedTactics = mergeTacticalInstructions(globalDup.getTacticalInstructions(), candidateDto.tacticalInstructions());
                    int mergedPriority = Math.min(100, Math.max(96, Math.max(resolvedPriority, globalDup.getPriority() + 2)));
                    AgentSkillEntity pageMerged = AgentSkillEntity.builder()
                            .id(UUID.randomUUID())
                            .code(code)
                            .name(name)
                            .category(category)
                            .description(description.isBlank() ? globalDup.getDescription() : description)
                            .triggerSignals(mergedTriggers)
                            .tacticalInstructions(mergedTactics)
                            .goodExample(goodExample.isBlank() ? globalDup.getGoodExample() : goodExample)
                            .antiPattern(antiPattern.isBlank() ? globalDup.getAntiPattern() : antiPattern)
                            .priority(mergedPriority)
                            .active(true)
                            .pageId(targetPageId)
                            .confidenceScore(0.95)
                            .createdAt(now)
                            .updatedAt(now)
                            .build();
                    AgentSkillEntity saved = agentSkillRepository.save(pageMerged);
                    log.info("stage=agent_skill_governance action=auto_merge_from_global id={} global_code={} page_id={}",
                            saved.getId(), globalDup.getCode(), targetPageId);
                    return AgentSkillDto.fromEntity(saved).withGovernance(
                            "AUTO_MERGED",
                            "Đã tự động kế thừa & gộp chỉ dẫn bổ sung với kỹ năng chuẩn '" + globalDup.getName() + "' thành kỹ năng chuyên biệt cho Page."
                    );
                }
            }
        }

        AgentSkillEntity entity = AgentSkillEntity.builder()
                .id(UUID.randomUUID())
                .code(code)
                .name(name)
                .category(category)
                .description(description)
                .triggerSignals(triggerSignals)
                .tacticalInstructions(tacticalInstructions)
                .goodExample(goodExample)
                .antiPattern(antiPattern)
                .priority(resolvedPriority)
                .active(resolvedActive)
                .pageId(targetPageId)
                .confidenceScore(0.95)
                .createdAt(now)
                .updatedAt(now)
                .build();

        AgentSkillEntity saved = agentSkillRepository.save(entity);
        log.info("stage=agent_skill action=create id={} code={} category={} page_id={}",
                saved.getId(), saved.getCode(), saved.getCategory(), saved.getPageId());
        return AgentSkillDto.fromEntity(saved).withGovernance("CREATED", "Đã tạo mới kỹ năng thành công.");
    }

    @Transactional
    public AgentSkillDto updateSkill(UUID id, AgentSkillRequest request) {
        AgentSkillEntity entity = agentSkillRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy Kỹ năng Agent với id: " + id));

        if ((request.tacticalInstructions() != null && PromptSecurityGuard.isPoisonedRuleAttempt(request.tacticalInstructions()))
                || (request.rawInstruction() != null && PromptSecurityGuard.isPoisonedRuleAttempt(request.rawInstruction()))
                || (request.name() != null && PromptSecurityGuard.isPoisonedRuleAttempt(request.name()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chỉ dẫn chiến thuật chứa nội dung vi phạm an toàn bảo mật.");
        }

        if (request.name() != null && !request.name().isBlank()) {
            entity.setName(PromptSecurityGuard.sanitizeUntrustedContent(request.name().strip()));
        }
        if (request.code() != null && !request.code().isBlank()) {
            entity.setCode(sanitizeCode(request.code()));
        }
        if (hasExplicitCategory(request.category())) {
            entity.setCategory(normalizeCategory(request.category()));
        }
        if (request.description() != null) {
            entity.setDescription(safeText(request.description()));
        }
        if (request.triggerSignals() != null) {
            entity.setTriggerSignals(safeText(request.triggerSignals()));
        }
        if (request.tacticalInstructions() != null && !request.tacticalInstructions().isBlank()) {
            entity.setTacticalInstructions(safeText(request.tacticalInstructions()));
        } else if (request.rawInstruction() != null && !request.rawInstruction().isBlank()) {
            String cleanRaw = safeText(request.rawInstruction());
            entity.setTacticalInstructions(inferTacticalInstructionsFromText(cleanRaw, cleanRaw.toLowerCase(Locale.ROOT)));
        } else if (request.tacticalInstructions() != null) {
            entity.setTacticalInstructions("");
        }
        if (request.goodExample() != null) {
            entity.setGoodExample(safeText(request.goodExample()));
        }
        if (request.antiPattern() != null) {
            entity.setAntiPattern(safeText(request.antiPattern()));
        }
        if (request.priority() != null) {
            entity.setPriority(Math.max(1, Math.min(100, request.priority())));
        }
        if (request.active() != null) {
            entity.setActive(request.active());
        }
        if (request.pageId() != null || request.name() != null) {
            entity.setPageId(parsePageId(request.pageId()));
        }
        entity.setUpdatedAt(Instant.now());

        AgentSkillEntity saved = agentSkillRepository.save(entity);
        log.info("stage=agent_skill action=update id={} code={} active={} page_id={}",
                saved.getId(), saved.getCode(), saved.isActive(), saved.getPageId());
        return AgentSkillDto.fromEntity(saved);
    }

    @Transactional
    public void deleteSkill(UUID id) {
        if (!agentSkillRepository.existsById(id)) {
            throw new ResourceNotFoundException("Không tìm thấy Kỹ năng Agent với id: " + id);
        }
        agentSkillRepository.deleteById(id);
        log.info("stage=agent_skill action=delete id={}", id);
    }

    /**
     * Đồng bộ hóa từ vòng lặp "Báo sai & Dạy AI" (Self-Evolving Insight & Skill Loop):
     * Nếu người dạy hướng dẫn AI về tâm lý khách hàng, cách chốt sale hoặc giao tiếp khéo léo,
     * tự động tạo/cập nhật thêm Kỹ năng tương ứng trong Agent Skill Registry.
     */
    @Transactional
    public void syncSkillFromFeedbackIfApplicable(
            String question,
            String userCorrection,
            String generalizedRule,
            List<String> keywords,
            UUID pageId) {
        if (userCorrection == null || userCorrection.isBlank() || generalizedRule == null || generalizedRule.isBlank()) {
            return;
        }
        if (PromptSecurityGuard.isPoisonedRuleAttempt(userCorrection) || PromptSecurityGuard.isPoisonedRuleAttempt(generalizedRule)) {
            return;
        }
        String lower = (question + " " + userCorrection + " " + generalizedRule).toLowerCase(Locale.ROOT);
        boolean isPsych = lower.contains("tâm lý") || lower.contains("đồng cảm") || lower.contains("thấu hiểu")
                || lower.contains("trấn an") || lower.contains("lo lắng") || lower.contains("ngại")
                || lower.contains("lâu năm") || lower.contains("sợ");
        boolean isSales = lower.contains("chốt") || lower.contains("đặt lịch") || lower.contains("số điện thoại")
                || lower.contains("sđt") || lower.contains("chê đắt") || lower.contains("combo") || lower.contains("tư vấn");
        boolean isComm = lower.contains("xưng hô") || lower.contains("giọng văn") || lower.contains("ngắn gọn")
                || lower.contains("chào") || lower.contains("lịch sự") || lower.contains("khéo léo");

        if (!isPsych && !isSales && !isComm) {
            return;
        }

        String category = isPsych ? "PSYCHOLOGY" : (isSales ? "SALES_STRATEGY" : "COMMUNICATION");
        String prefixLabel = isPsych ? "[Tâm lý học từ Thực tế]" : (isSales ? "[Chiến thuật Sale từ Thực tế]" : "[Giao tiếp từ Thực tế]");
        String shortTopic = question != null && !question.isBlank()
                ? (question.strip().length() > 48 ? question.strip().substring(0, 48) + "..." : question.strip())
                : "Xử lý tình huống khách hàng";
        String name = prefixLabel + " " + shortTopic;
        String triggers = (keywords != null && !keywords.isEmpty())
                ? String.join(", ", keywords)
                : shortTopic;
        String code = generateCode(category, shortTopic);

        try {
            Instant now = Instant.now();
            AgentSkillEntity existing = agentSkillRepository.findFirstByCodeIgnoreCase(code).orElse(null);
            if (existing != null && java.util.Objects.equals(existing.getPageId(), pageId)) {
                existing.setName(PromptSecurityGuard.sanitizeUntrustedContent(name));
                existing.setCategory(category);
                existing.setTriggerSignals(PromptSecurityGuard.sanitizeUntrustedContent(triggers));
                existing.setTacticalInstructions(PromptSecurityGuard.sanitizeUntrustedContent(generalizedRule));
                existing.setActive(true);
                existing.setConfidenceScore(Math.max(0.90, existing.getConfidenceScore()));
                existing.setUpdatedAt(now);
                agentSkillRepository.save(existing);
                log.info("stage=agent_skill_auto_evolve action=update category={} code={} page_id={}", category, code, pageId);
                return;
            }

            AgentSkillEntity skill = AgentSkillEntity.builder()
                    .id(UUID.randomUUID())
                    .code(code)
                    .name(PromptSecurityGuard.sanitizeUntrustedContent(name))
                    .category(category)
                    .description("Kỹ năng tự động đúc kết từ phản hồi 'Báo sai & Dạy AI' của Quản trị viên.")
                    .triggerSignals(PromptSecurityGuard.sanitizeUntrustedContent(triggers))
                    .tacticalInstructions(PromptSecurityGuard.sanitizeUntrustedContent(generalizedRule))
                    .goodExample("")
                    .antiPattern("")
                    .priority(85)
                    .active(true)
                    .pageId(pageId)
                    .confidenceScore(0.90)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            agentSkillRepository.save(skill);
            log.info("stage=agent_skill_auto_evolve action=create category={} name={} page_id={}", category, name, pageId);
        } catch (Exception e) {
            log.debug("Bỏ qua auto-sync AgentSkill từ feedback: {}", e.getMessage());
        }
    }

    public void recordSkillsApplied(List<AgentSkillDto> activeSkills) {
        if (activeSkills == null || activeSkills.isEmpty()) {
            return;
        }
        try {
            List<UUID> sortedIds = activeSkills.stream()
                    .map(AgentSkillDto::id)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .sorted()
                    .toList();
            if (sortedIds.isEmpty()) {
                return;
            }
            if (transactionTemplate != null) {
                transactionTemplate.executeWithoutResult(status -> {
                    for (UUID id : sortedIds) {
                        agentSkillRepository.incrementAppliedCount(id);
                    }
                });
            } else {
                for (UUID id : sortedIds) {
                    agentSkillRepository.incrementAppliedCount(id);
                }
            }
        } catch (Exception e) {
            log.warn("Non-fatal: Bỏ qua lỗi cập nhật telemetry agent_skills (applied_count): {}", e.getMessage());
        }
    }

    public void recordConversionSuccess(UUID pageId, String conversationText) {
        if (conversationText == null || conversationText.isBlank()) {
            return;
        }
        try {
            String lowerConvo = conversationText.toLowerCase(Locale.ROOT);
            List<AgentSkillEntity> activeSkills = agentSkillRepository.findActiveForPage(pageId);
            if (activeSkills == null || activeSkills.isEmpty()) {
                return;
            }
            List<UUID> matchedIds = new java.util.ArrayList<>();
            for (AgentSkillEntity skill : activeSkills) {
                if (skill == null || !skill.isActive() || skill.getTriggerSignals() == null) {
                    continue;
                }
                boolean matched = false;
                for (String rawSig : skill.getTriggerSignals().split("[,;]+")) {
                    String sig = rawSig.strip().toLowerCase(Locale.ROOT);
                    if (sig.length() >= 2 && lowerConvo.contains(sig)) {
                        matched = true;
                        break;
                    }
                }
                if (matched && skill.getId() != null) {
                    matchedIds.add(skill.getId());
                }
            }
            if (matchedIds.isEmpty()) {
                return;
            }
            List<UUID> sortedMatchedIds = matchedIds.stream().distinct().sorted().toList();
            if (transactionTemplate != null) {
                transactionTemplate.executeWithoutResult(status -> {
                    for (UUID id : sortedMatchedIds) {
                        agentSkillRepository.incrementConversionSuccess(id);
                    }
                });
            } else {
                for (UUID id : sortedMatchedIds) {
                    agentSkillRepository.incrementConversionSuccess(id);
                }
            }
        } catch (Exception e) {
            log.warn("Non-fatal: Bỏ qua lỗi cập nhật conversion_count cho agent_skills: {}", e.getMessage());
        }
    }

    private static boolean hasExplicitCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String trimmed = raw.strip();
        return !"AUTO".equalsIgnoreCase(trimmed) && !"DEFAULT".equalsIgnoreCase(trimmed);
    }

    private static String normalizeCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            return "SALES_STRATEGY";
        }
        String upper = raw.strip().toUpperCase(Locale.ROOT);
        if (VALID_CATEGORIES.contains(upper)) {
            return upper;
        }
        if (upper.contains("PSYCH") || upper.contains("TÂM LÝ")) {
            return "PSYCHOLOGY";
        }
        if (upper.contains("COMM") || upper.contains("GIAO TIẾP") || upper.contains("TONE")) {
            return "COMMUNICATION";
        }
        return "SALES_STRATEGY";
    }

    private static String sanitizeCode(String raw) {
        String normalized = Normalizer.normalize(raw.strip(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('đ', 'd').replace('Đ', 'D')
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9_]+", "_")
                .replaceAll("^_+|_+$", "");
        if (normalized.length() > 70) {
            normalized = normalized.substring(0, 70);
        }
        return normalized.isBlank() ? "CUSTOM_SKILL_" + System.currentTimeMillis() % 10000 : normalized;
    }

    private static String generateCode(String category, String name) {
        String prefix = switch (category) {
            case "PSYCHOLOGY" -> "PSYCH_";
            case "COMMUNICATION" -> "COMM_";
            default -> "SALES_";
        };
        return sanitizeCode(prefix + name);
    }

    private static String safeText(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        return PromptSecurityGuard.sanitizeUntrustedContent(input.strip());
    }

    private static UUID parsePageId(String pageIdRaw) {
        if (pageIdRaw == null || pageIdRaw.isBlank() || "__global__".equalsIgnoreCase(pageIdRaw.strip())) {
            return null;
        }
        try {
            return UUID.fromString(pageIdRaw.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.strip();
            }
        }
        return null;
    }

    private static String inferCategoryFromText(String lower) {
        if (lower.contains("tâm lý") || lower.contains("đồng cảm") || lower.contains("lo lắng")
                || lower.contains("sợ đau") || lower.contains("biến chứng") || lower.contains("ngại")
                || lower.contains("hoài nghi") || lower.contains("niềm tin") || lower.contains("trấn an")) {
            return "PSYCHOLOGY";
        }
        if (lower.contains("xưng hô") || lower.contains("giọng văn") || lower.contains("tách bong bóng")
                || lower.contains("ngắn gọn") || lower.contains("lịch sự") || lower.contains("cách nói")) {
            return "COMMUNICATION";
        }
        return "SALES_STRATEGY";
    }

    private static String inferSkillNameFromText(String cleanRaw, String lower, String category) {
        String catPrefix = switch (category) {
            case "PSYCHOLOGY" -> "[Tâm lý] ";
            case "COMMUNICATION" -> "[Giao tiếp] ";
            default -> "[Chốt Sale] ";
        };
        if (lower.contains("xả sạch") || lower.contains("3 nấc")
                || (lower.contains("hỏi giá") && (lower.contains("không báo hết") || lower.contains("đừng liệt kê")
                || lower.contains("không liệt kê") || lower.contains("chung chung") || lower.contains("khởi điểm")))) {
            return catPrefix + "Chống Xả Sạch Thông Tin & Phân Bổ 3 Nấc Báo Giá";
        }
        if (lower.contains("bán lịch hẹn") || lower.contains("móc câu") || (lower.contains("lịch hẹn") && lower.contains("sáng hay chiều"))) {
            return catPrefix + "Móc Câu Chốt Lịch Hẹn & Câu Hỏi Khống Chế A/B";
        }
        if (lower.contains("chê đắt") || lower.contains("đắt quá") || lower.contains("suy nghĩ thêm") || lower.contains("hỏi ý kiến")) {
            return catPrefix + "Xử Lý Từ Chối Về Giá & Neo Giá Trị Cốt Lõi";
        }
        if (lower.contains("sợ đau") || lower.contains("sợ sưng") || lower.contains("nghỉ dưỡng") || lower.contains("biến chứng") || lower.contains("rủi ro")) {
            return catPrefix + "Trấn An Tâm Lý Lo Ngại & Cam Kết An Toàn";
        }
        String firstLine = cleanRaw.split("[\\r\\n.!?;]+")[0].strip();
        firstLine = firstLine.replaceFirst("^(?i)(khi khách hàng|khi khách|nếu khách hàng|nếu khách|quy tắc|kỹ năng|hướng dẫn)\\s+", "");
        if (firstLine.isBlank()) {
            firstLine = "Kỹ năng tư vấn chuyên sâu";
        }
        String capitalized = Character.toUpperCase(firstLine.charAt(0)) + firstLine.substring(1);
        if (capitalized.length() > 62) {
            capitalized = capitalized.substring(0, 62).strip() + "...";
        }
        return catPrefix + capitalized;
    }

    private static String inferTriggerSignalsFromText(String cleanRaw, String lower) {
        java.util.LinkedHashSet<String> signals = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher quoted = java.util.regex.Pattern.compile("[\"“”'‘’]([^\"“”'‘’]{2,40})[\"“”'‘’]").matcher(cleanRaw);
        while (quoted.find()) {
            String token = quoted.group(1).strip();
            if (!token.isBlank()) {
                signals.add(token);
            }
        }
        java.util.regex.Matcher naturalClause = java.util.regex.Pattern.compile(
                "(?iU)(?:khi|nếu|lúc)\\s+khách(?:\\s+hàng)?\\s+(?:chỉ\\s+)?(?:nhắn|hỏi|nói|bảo|than|băn\\s+khoăn)\\s+(?:về\\s+|là\\s+)?([^,.;:\\n]{3,42}?)(?=\\s+(?:thì|hãy|cần|tuyệt\\s+đối|đừng|không|nên)\\b|[,.;:\\n]|$)"
        ).matcher(cleanRaw);
        while (naturalClause.find()) {
            String phrase = naturalClause.group(1).strip()
                    .replaceAll("(?iU)\\s+(?:không|chưa|sao|thế\\s+nào|như\\s+thế\\s+nào)\\s*$", "")
                    .strip();
            if (phrase.length() >= 2 && phrase.length() <= 36) {
                signals.add(phrase);
            }
        }
        if (lower.contains("giá") || lower.contains("chi phí") || lower.contains("bao nhiêu")) {
            signals.add("giá");
            signals.add("báo giá");
            signals.add("chi phí");
            signals.add("bao nhiêu tiền");
        }
        if (lower.contains("đắt") || lower.contains("cao quá") || lower.contains("suy nghĩ")) {
            signals.add("đắt quá");
            signals.add("giá cao");
            signals.add("suy nghĩ thêm");
            signals.add("tham khảo thêm");
        }
        if (lower.contains("lịch") || lower.contains("hẹn") || lower.contains("đặt chỗ") || lower.contains("ghé")) {
            signals.add("đặt lịch");
            signals.add("lịch hẹn");
            signals.add("qua cơ sở");
        }
        if (lower.contains("trả góp")) signals.add("trả góp");
        if (lower.contains("bảo hành")) signals.add("bảo hành");
        if (lower.contains("khuyến mãi") || lower.contains("ưu đãi") || lower.contains("voucher") || lower.contains("giảm giá")) {
            signals.add("khuyến mãi");
            signals.add("ưu đãi");
            signals.add("giảm giá");
        }
        if (lower.contains("sợ đau") || lower.contains("an toàn") || lower.contains("tác dụng phụ")) {
            signals.add("có đau không");
            signals.add("an toàn không");
        }
        if (signals.isEmpty()) {
            signals.add("tư vấn");
            signals.add("dịch vụ");
            signals.add("hỏi thăm");
        }
        return String.join(", ", signals);
    }

    private static String inferDescriptionFromText(String name, String cleanRaw, String category) {
        String catLabel = switch (category) {
            case "PSYCHOLOGY" -> "Thấu cảm tâm lý khách hàng";
            case "COMMUNICATION" -> "Tinh chỉnh nhịp điệu & ngôn từ giao tiếp";
            default -> "Chiến thuật dẫn dắt & chuyển đổi thông minh";
        };
        String snippet = cleanRaw.length() > 140 ? cleanRaw.substring(0, 140).strip() + "..." : cleanRaw;
        return catLabel + ": " + snippet;
    }

    private static String inferTacticalInstructionsFromText(String cleanRaw, String lower) {
        StringBuilder sb = new StringBuilder();
        sb.append("CHỈ DẪN THỰC THI TRỌNG TÂM:\n");
        sb.append("- ").append(cleanRaw);
        return sb.toString().strip();
    }

    private static String inferGoodExampleFromText(String lower) {
        if (lower.contains("giá") || lower.contains("xả sạch") || lower.contains("3 nấc")) {
            return "Dạ bên em hiện có các gói dịch vụ linh hoạt theo đúng nhu cầu thực tế kèm cam kết minh bạch trọn gói không phụ phí ạ. Hiện tại Anh/Chị đang quan tâm nhu cầu cơ bản hay chuyên sâu để em báo đúng gói phù hợp nhất cho mình ạ?";
        }
        if (lower.contains("đắt") || lower.contains("suy nghĩ")) {
            return "Dạ em rất hiểu tâm lý muốn cân nhắc kỹ trước khi quyết định ạ. Anh/Chị có thể trải nghiệm kiểm tra/tư vấn bước đầu hoàn toàn thoải mái không ràng buộc trước khi chọn gói, Anh/Chị tiện vào khung Sáng hay Chiều hơn ạ?";
        }
        return "Dạ bên em sẵn sàng thiết kế giải pháp phù hợp nhất với nhu cầu riêng của Anh/Chị ạ. Để em chuẩn bị hỗ trợ chu đáo nhất không phải chờ đợi, Anh/Chị tiện vào khung giờ Sáng hay Chiều ạ?";
    }

    private static String inferAntiPatternFromText(String lower) {
        if (lower.contains("giá") || lower.contains("xả sạch") || lower.contains("3 nấc")) {
            return "Liệt kê một lèo toàn bộ bảng giá dài dòng ngay khi khách mới hỏi thăm khiến khách ngợp thông tin và rời đi.";
        }
        return "Trả lời dài dòng như văn mẫu, tung hết mọi thông tin trong 1 tin nhắn và kết thúc bằng câu hỏi đóng cụt lủn.";
    }

    // =========================================================================
    // SKILL GOVERNANCE ENGINE: DUPLICATE AUTO-MERGE & TACTICAL CONFLICT DETECTOR
    // =========================================================================

    public record ConflictAnalysis(
            String dimensionName,
            String reason,
            String recommendedAction
    ) {}

    public static ConflictAnalysis analyzeTacticalConflict(AgentSkillEntity existing, AgentSkillDto candidate) {
        if (existing == null || candidate == null) {
            return null;
        }
        Set<String> existingSignals = extractSignalSet(existing.getTriggerSignals());
        Set<String> candidateSignals = extractSignalSet(candidate.triggerSignals());
        Set<String> sharedSignals = new java.util.LinkedHashSet<>(existingSignals);
        sharedSignals.retainAll(candidateSignals);

        String existingText = ((existing.getName() != null ? existing.getName() : "") + " "
                + (existing.getTacticalInstructions() != null ? existing.getTacticalInstructions() : "") + " "
                + (existing.getAntiPattern() != null ? " cấm: " + existing.getAntiPattern() : ""))
                .toLowerCase(Locale.ROOT);
        String candidateText = ((candidate.name() != null ? candidate.name() : "") + " "
                + (candidate.tacticalInstructions() != null ? candidate.tacticalInstructions() : ""))
                .toLowerCase(Locale.ROOT);

        boolean sameTopic = !sharedSignals.isEmpty() || sharesTacticalTopic(existingText, candidateText);
        if (!sameTopic) {
            return null;
        }

        String sharedLabel = sharedSignals.isEmpty()
                ? "cùng tình huống tư vấn"
                : String.join(", ", sharedSignals.stream().limit(4).toList());

        // Chiều 1: Chiến thuật Báo giá (Chống xả bảng giá vs. Xả/liệt kê toàn bộ bảng giá)
        boolean existHoldPrice = holdsBackFullPriceList(existingText);
        boolean candHoldPrice = holdsBackFullPriceList(candidateText);
        boolean existDumpPrice = mandatesFullPriceDump(existingText);
        boolean candDumpPrice = mandatesFullPriceDump(candidateText);

        if ((existHoldPrice && candDumpPrice) || (existDumpPrice && candHoldPrice)) {
            String reason = String.format(
                    "Xung đột chiến thuật Báo giá trên tín hiệu [%s]: Kỹ năng mới yêu cầu '%s', đối lập trực tiếp với kỹ năng hiện có '%s' (%s).",
                    sharedLabel,
                    candDumpPrice ? "liệt kê/xả đầy đủ toàn bộ bảng giá ngay lập tức" : "chỉ báo 1 mức giá khởi điểm, chống xả bảng giá",
                    existing.getName(),
                    existHoldPrice ? "yêu cầu chống xả sạch thông tin, chỉ neo 1 mức giá khởi điểm kèm câu hỏi chẩn đoán" : "yêu cầu liệt kê toàn bộ bảng giá"
            );
            String recommendation = existHoldPrice
                    ? "Khuyên dùng kỹ năng hiện có '" + existing.getName() + "' (hoặc chỉnh sửa kỹ năng mới): Giữ nhịp 1 mức giá khởi điểm + 1 câu hỏi A/B giúp tăng tỷ lệ khách phản hồi cao hơn gấp 3 lần so với xả toàn bộ bảng giá."
                    : "Khuyên Ghi đè bằng kỹ năng mới để áp dụng chiến thuật Phân bổ thông tin 3 nấc & Neo giá trị.";
            return new ConflictAnalysis("PRICE_DISCLOSURE", reason, recommendation);
        }

        // Chiều 2: Chính sách Giảm giá / Chiết khấu khi khách chê đắt (Giữ nguyên giá vs. Giảm giá ngay)
        boolean existHoldDiscount = forbidsImmediateDiscount(existingText);
        boolean candHoldDiscount = forbidsImmediateDiscount(candidateText);
        boolean existGiveDiscount = mandatesImmediateDiscount(existingText);
        boolean candGiveDiscount = mandatesImmediateDiscount(candidateText);

        if ((existHoldDiscount && candGiveDiscount) || (existGiveDiscount && candHoldDiscount)) {
            String reason = String.format(
                    "Xung đột chính sách Đàm phán & Chiết khấu trên tín hiệu [%s]: Kỹ năng mới yêu cầu '%s', mâu thuẫn với kỹ năng hiện có '%s' (%s).",
                    sharedLabel,
                    candGiveDiscount ? "giảm giá / tặng chiết khấu ngay khi khách chê đắt" : "giữ nguyên giá, tuyệt đối không tự ý hạ giá",
                    existing.getName(),
                    existHoldDiscount ? "cấm tự ý hạ giá, dùng bảo chứng chất lượng & trải nghiệm rủi ro 0đ" : "cho phép giảm giá ngay"
            );
            String recommendation = "Nếu Page đang chạy chiến dịch khuyến mãi đặc biệt, bạn có thể chọn 'Ghi đè'. Ngược lại, khuyên giữ nguyên tắc bảo vệ biên lợi nhuận của '" + existing.getName() + "'.";
            return new ConflictAnalysis("DISCOUNT_POLICY", reason, recommendation);
        }

        // Chiều 3: Nhịp độ Xin SĐT & Chốt Sale (Không ép chốt sớm vs. Bắt buộc xin SĐT ngay câu đầu)
        boolean existSoftClose = forbidsEarlyHardClose(existingText);
        boolean candSoftClose = forbidsEarlyHardClose(candidateText);
        boolean existHardClose = mandatesEarlyHardClose(existingText);
        boolean candHardClose = mandatesEarlyHardClose(candidateText);

        if ((existSoftClose && candHardClose) || (existHardClose && candSoftClose)) {
            String reason = String.format(
                    "Xung đột nhịp độ Chốt Sale & Xin SĐT trên tín hiệu [%s]: Kỹ năng mới yêu cầu '%s', trái ngược với kỹ năng hiện có '%s' (%s).",
                    sharedLabel,
                    candHardClose ? "ép chốt / xin SĐT ngay từ đầu" : "không ép chốt sớm, dùng cam kết vi mô Sáng/Chiều trước",
                    existing.getName(),
                    existSoftClose ? "hạ áp lực bán hàng, chỉ xin SĐT sau khi khách đã chọn khung giờ" : "ép xin SĐT ngay lập tức"
            );
            String recommendation = "Khuyên dùng chiến thuật Cam kết vi mô (hỏi Sáng/Chiều trước khi xin SĐT) để tránh làm khách hàng phòng vệ và rời bỏ cuộc trò chuyện.";
            return new ConflictAnalysis("CLOSING_PRESSURE", reason, recommendation);
        }

        // Chiều 4: Độ dài & Nhịp thở Tin nhắn (Ngắn gọn <40 từ vs. Giải thích dài dòng/chi tiết từ A-Z)
        boolean existConcise = requiresConcisePacing(existingText);
        boolean candConcise = requiresConcisePacing(candidateText);
        boolean existVerbose = mandatesLongVerboseExplanation(existingText);
        boolean candVerbose = mandatesLongVerboseExplanation(candidateText);

        if ((existConcise && candVerbose) || (existVerbose && candConcise)) {
            String reason = String.format(
                    "Xung đột quy tắc Độ dài & Nhịp điệu phản hồi: Kỹ năng mới yêu cầu '%s', mâu thuẫn với kỹ năng '%s' (%s).",
                    candVerbose ? "trình bày giải thích dài và liệt kê chi tiết toàn bộ quy trình" : "cô đọng ngắn gọn dưới 40 từ",
                    existing.getName(),
                    existConcise ? "giới hạn nhịp thở ngắn gọn <= 40 từ mỗi bong bóng chat" : "trình bày dài chi tiết"
            );
            String recommendation = "Trên kênh chat (Messenger/Zalo), phản hồi ngắn gọn dưới 40 từ giúp khách dễ đọc trên điện thoại hơn. Khuyên chọn 'Chỉnh sửa' hoặc 'Bỏ qua' nếu không thực sự cần gửi bài giải thích dài.";
            return new ConflictAnalysis("MESSAGE_PACING", reason, recommendation);
        }

        return null;
    }

    public static boolean areTacticallyConflicting(AgentSkillDto a, AgentSkillDto b) {
        if (a == null || b == null) {
            return false;
        }
        AgentSkillEntity entityA = AgentSkillEntity.builder()
                .id(a.id())
                .code(a.code())
                .name(a.name())
                .category(a.category())
                .triggerSignals(a.triggerSignals())
                .tacticalInstructions(a.tacticalInstructions())
                .antiPattern(a.antiPattern())
                .priority(a.priority())
                .active(a.active())
                .build();
        return analyzeTacticalConflict(entityA, b) != null;
    }

    private static AgentSkillEntity findDuplicateOrComplementary(
            List<AgentSkillEntity> existingSkills,
            AgentSkillDto candidate,
            UUID targetPageId,
            boolean sameScopeOnly
    ) {
        Set<String> candSignals = extractSignalSet(candidate.triggerSignals());
        String candNormName = normalizeNameForComparison(candidate.name());

        for (AgentSkillEntity existing : existingSkills) {
            if (existing == null || !existing.isActive()) {
                continue;
            }
            boolean isSameScope = java.util.Objects.equals(existing.getPageId(), targetPageId);
            if (sameScopeOnly && !isSameScope) {
                continue;
            }
            if (!sameScopeOnly && existing.getPageId() != null) {
                continue;
            }

            if (existing.getCode() != null && existing.getCode().equalsIgnoreCase(candidate.code())) {
                return existing;
            }
            String existNormName = normalizeNameForComparison(existing.getName());
            if (!candNormName.isEmpty() && candNormName.equals(existNormName)) {
                return existing;
            }

            Set<String> existSignals = extractSignalSet(existing.getTriggerSignals());
            if (!candSignals.isEmpty() && !existSignals.isEmpty()) {
                Set<String> intersection = new java.util.LinkedHashSet<>(candSignals);
                intersection.retainAll(existSignals);
                int minSize = Math.min(candSignals.size(), existSignals.size());
                boolean highSignalOverlap = (minSize > 0 && (double) intersection.size() / minSize >= 0.60)
                        || intersection.size() >= 3;
                boolean sameCategory = existing.getCategory() != null
                        && existing.getCategory().equalsIgnoreCase(candidate.category());
                if (highSignalOverlap && sameCategory) {
                    return existing;
                }
            }
        }
        return null;
    }

    private static AgentSkillEntity resolveConflictTarget(
            List<AgentSkillEntity> existingSkills,
            String conflictSkillIdRaw,
            AgentSkillDto candidate
    ) {
        if (conflictSkillIdRaw != null && !conflictSkillIdRaw.isBlank()) {
            try {
                UUID cid = UUID.fromString(conflictSkillIdRaw.strip());
                for (AgentSkillEntity e : existingSkills) {
                    if (e != null && cid.equals(e.getId())) {
                        return e;
                    }
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        for (AgentSkillEntity e : existingSkills) {
            if (e != null && analyzeTacticalConflict(e, candidate) != null) {
                return e;
            }
        }
        return null;
    }

    private static AgentSkillEntity mergeSkillIntoExisting(
            AgentSkillEntity existing,
            AgentSkillDto candidate,
            Instant now
    ) {
        existing.setTriggerSignals(mergeCommaSeparatedSignals(existing.getTriggerSignals(), candidate.triggerSignals()));
        existing.setTacticalInstructions(mergeTacticalInstructions(existing.getTacticalInstructions(), candidate.tacticalInstructions()));
        if ((existing.getGoodExample() == null || existing.getGoodExample().isBlank())
                && candidate.goodExample() != null && !candidate.goodExample().isBlank()) {
            existing.setGoodExample(candidate.goodExample());
        }
        if ((existing.getAntiPattern() == null || existing.getAntiPattern().isBlank())
                && candidate.antiPattern() != null && !candidate.antiPattern().isBlank()) {
            existing.setAntiPattern(candidate.antiPattern());
        }
        existing.setPriority(Math.max(existing.getPriority(), candidate.priority()));
        existing.setActive(true);
        existing.setUpdatedAt(now);
        return existing;
    }

    public static String mergeCommaSeparatedSignals(String sigA, String sigB) {
        java.util.LinkedHashMap<String, String> merged = new java.util.LinkedHashMap<>();
        for (String raw : new String[]{sigA, sigB}) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            for (String token : raw.split("[,;]+")) {
                String clean = token.strip();
                if (!clean.isEmpty()) {
                    merged.putIfAbsent(clean.toLowerCase(Locale.ROOT), clean);
                }
            }
        }
        return String.join(", ", merged.values());
    }

    public static String mergeTacticalInstructions(String baseInstructions, String additionalInstructions) {
        String base = baseInstructions != null ? baseInstructions.strip() : "";
        String add = additionalInstructions != null ? additionalInstructions.strip() : "";
        if (base.isEmpty()) return add;
        if (add.isEmpty()) return base;

        String cleanedAdd = add.replaceFirst("(?i)^CHỈ DẪN THỰC THI TRỌNG TÂM:\\s*", "").strip();
        String baseLower = base.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(base);
        for (String line : cleanedAdd.split("\\r?\\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;
            String normalizedLine = trimmed.replaceFirst("^[-•*]\\s*", "").strip();
            if (normalizedLine.length() >= 8 && !baseLower.contains(normalizedLine.toLowerCase(Locale.ROOT))) {
                sb.append("\n- [Bổ sung]: ").append(normalizedLine);
            }
        }
        return sb.toString().strip();
    }

    private static Set<String> extractSignalSet(String rawSignals) {
        Set<String> set = new java.util.LinkedHashSet<>();
        if (rawSignals == null || rawSignals.isBlank()) {
            return set;
        }
        for (String s : rawSignals.split("[,;]+")) {
            String clean = s.strip().toLowerCase(Locale.ROOT);
            if (clean.length() >= 2) {
                set.add(clean);
            }
        }
        return set;
    }

    private static String normalizeNameForComparison(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return "";
        }
        return rawName.toLowerCase(Locale.ROOT)
                .replaceAll("\\[[^\\]]*\\]", "")
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .strip();
    }

    private static boolean sharesTacticalTopic(String textA, String textB) {
        boolean priceA = textA.contains("bảng giá") || textA.contains("hỏi giá") || textA.contains("báo giá");
        boolean priceB = textB.contains("bảng giá") || textB.contains("hỏi giá") || textB.contains("báo giá");
        if (priceA && priceB) return true;

        boolean discountA = textA.contains("chê đắt") || textA.contains("giảm giá") || textA.contains("hạ giá");
        boolean discountB = textB.contains("chê đắt") || textB.contains("giảm giá") || textB.contains("hạ giá");
        if (discountA && discountB) return true;

        boolean closeA = textA.contains("xin sđt") || textA.contains("số điện thoại") || textA.contains("chốt lịch");
        boolean closeB = textB.contains("xin sđt") || textB.contains("số điện thoại") || textB.contains("chốt lịch");
        if (closeA && closeB) return true;

        boolean lengthA = textA.contains("40 từ") || textA.contains("ngắn gọn") || textA.contains("dài dòng");
        boolean lengthB = textB.contains("40 từ") || textB.contains("ngắn gọn") || textB.contains("dài dòng");
        return lengthA && lengthB;
    }

    private static boolean holdsBackFullPriceList(String text) {
        return text.contains("không xả")
                || text.contains("đừng xả")
                || text.contains("chống xả")
                || text.contains("không liệt kê toàn bộ")
                || text.contains("đừng liệt kê")
                || text.contains("không liệt kê hết")
                || text.contains("1 mức giá khởi điểm")
                || text.contains("chỉ nêu 1 mức giá")
                || text.contains("chỉ báo 1 mức giá")
                || text.contains("3 nấc");
    }

    private static boolean mandatesFullPriceDump(String text) {
        if (holdsBackFullPriceList(text)) {
            return false;
        }
        return text.contains("liệt kê đầy đủ")
                || text.contains("liệt kê toàn bộ bảng giá")
                || text.contains("báo hết bảng giá")
                || text.contains("báo đầy đủ bảng giá")
                || text.contains("gửi toàn bộ bảng giá")
                || text.contains("xả hết bảng giá")
                || text.contains("báo tất cả các gói")
                || text.contains("liệt kê chi tiết tất cả các gói")
                || text.contains("gửi hết các mức giá");
    }

    private static boolean forbidsImmediateDiscount(String text) {
        return text.contains("không giảm giá")
                || text.contains("tuyệt đối không hạ giá")
                || text.contains("không tự ý hạ giá")
                || text.contains("không tự ý giảm giá")
                || text.contains("giữ nguyên giá")
                || text.contains("đừng giảm giá");
    }

    private static boolean mandatesImmediateDiscount(String text) {
        if (forbidsImmediateDiscount(text)) {
            return false;
        }
        return text.contains("giảm giá ngay")
                || text.contains("hạ giá ngay")
                || text.contains("tặng ngay voucher giảm")
                || text.contains("chiết khấu thêm ngay")
                || text.contains("tự động giảm giá")
                || text.contains("giảm ngay 10%")
                || text.contains("giảm ngay 15%")
                || text.contains("giảm ngay 20%");
    }

    private static boolean forbidsEarlyHardClose(String text) {
        return text.contains("không xin sđt sớm")
                || text.contains("chưa xin sđt")
                || text.contains("không ép chốt")
                || text.contains("tuyệt đối không chèo kéo")
                || text.contains("hạ hoàn toàn áp lực bán hàng")
                || text.contains("không đòi sđt");
    }

    private static boolean mandatesEarlyHardClose(String text) {
        if (forbidsEarlyHardClose(text)) {
            return false;
        }
        return text.contains("xin sđt ngay lập tức")
                || text.contains("bắt buộc xin số điện thoại ngay")
                || text.contains("xin số điện thoại ngay câu đầu")
                || text.contains("ép chốt ngay")
                || text.contains("phải lấy sđt ngay");
    }

    private static boolean requiresConcisePacing(String text) {
        return text.contains("40 từ")
                || text.contains("tối đa 2 câu")
                || text.contains("không giải thích dài dòng");
    }

    private static boolean mandatesLongVerboseExplanation(String text) {
        if (requiresConcisePacing(text)) {
            return false;
        }
        return text.contains("giải thích thật dài")
                || text.contains("trình bày chi tiết từ a-z")
                || text.contains("mô tả đầy đủ mọi bước quy trình")
                || text.contains("viết càng chi tiết càng dài càng tốt");
    }
}

