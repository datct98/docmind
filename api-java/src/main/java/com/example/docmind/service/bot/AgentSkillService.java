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
        if (!hasName && !hasRaw) {
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
                .priority(request.priority() != null ? Math.max(1, Math.min(100, request.priority())) : (parsed != null ? parsed.priority() : 75))
                .active(request.active() != null ? request.active() : true)
                .pageId(parsePageId(request.pageId()))
                .createdAt(now)
                .updatedAt(now)
                .build();

        AgentSkillEntity saved = agentSkillRepository.save(entity);
        log.info("stage=agent_skill action=create id={} code={} category={} page_id={}",
                saved.getId(), saved.getCode(), saved.getCategory(), saved.getPageId());
        return AgentSkillDto.fromEntity(saved);
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
            return catPrefix + "Xử Lý Từ Chối Về Giá & Neo Giá Trị Y Khoa";
        }
        if (lower.contains("sợ đau") || lower.contains("sợ sưng") || lower.contains("nghỉ dưỡng") || lower.contains("biến chứng")) {
            return catPrefix + "Trấn An Tâm Lý Sợ Đau & Cam Kết An Toàn Y Khoa";
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
            signals.add("để chị suy nghĩ");
            signals.add("tham khảo thêm");
        }
        if (lower.contains("lịch") || lower.contains("hẹn") || lower.contains("soi da") || lower.contains("qua khám")) {
            signals.add("đặt lịch");
            signals.add("soi da");
            signals.add("qua khám");
            signals.add("lịch hẹn");
        }
        if (lower.contains("trả góp")) signals.add("trả góp");
        if (lower.contains("bảo hành")) signals.add("bảo hành");
        if (lower.contains("mụn")) signals.add("trị mụn");
        if (lower.contains("nám") || lower.contains("tàn nhang")) signals.add("trị nám");
        if (lower.contains("căng bóng") || lower.contains("meso")) signals.add("căng bóng");
        if (lower.contains("sợ đau") || lower.contains("sưng")) {
            signals.add("có đau không");
            signals.add("có sưng không");
            signals.add("nghỉ dưỡng");
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
            default -> "Chiến thuật dẫn dắt & chốt lịch hẹn thông minh";
        };
        String snippet = cleanRaw.length() > 140 ? cleanRaw.substring(0, 140).strip() + "..." : cleanRaw;
        return catLabel + ": " + snippet;
    }

    private static String inferTacticalInstructionsFromText(String cleanRaw, String lower) {
        StringBuilder sb = new StringBuilder();
        sb.append("CHỈ DẪN THỰC THI TRỌNG TÂM:\n");
        sb.append("- ").append(cleanRaw).append("\n");
        if (!lower.contains("1 tin nhắn") && !lower.contains("câu hỏi khống chế") && !lower.contains("a/b")) {
            sb.append("- Nguyên tắc nhịp độ: Mỗi lượt phản hồi chỉ đưa 1 lợi ích cốt lõi đi kèm 1 câu hỏi khống chế lựa chọn A/B ngắn gọn để giữ quyền dẫn dắt cuộc trò chuyện.\n");
        }
        if (!lower.contains("xả sạch") && (lower.contains("giá") || lower.contains("chi phí"))) {
            sb.append("- Chống xả sạch thông tin: Giữ lại quyền lợi khám/soi da trực tiếp làm móc câu chuyển đổi sang lịch hẹn thay vì liệt kê toàn bộ bảng giá dài dòng.");
        }
        return sb.toString().strip();
    }

    private static String inferGoodExampleFromText(String lower) {
        if (lower.contains("giá") || lower.contains("xả sạch") || lower.contains("3 nấc")) {
            return "Dạ các phác đồ chăm sóc và điều trị da chuẩn Y khoa bên em hiện có mức chi phí rất linh hoạt chỉ từ 650.000đ/buổi, đã bao gồm Bác sĩ soi da AI 3D miễn phí 100% và cam kết trọn gói không phát sinh phụ phí ạ. Hiện tại Chị đang muốn ưu tiên xử lý dứt điểm mụn ẩn/mụn viêm, làm mờ sạm nám hay dưỡng căng bóng trẻ hóa để em báo đúng gói phù hợp nhất cho mình ạ?";
        }
        if (lower.contains("đắt") || lower.contains("suy nghĩ")) {
            return "Dạ em rất hiểu tâm lý muốn cân nhắc kỹ trước khi làm đẹp cho da mặt mình ạ. Bên em đang tặng 100% suất Bác sĩ Chuyên khoa I soi da AI 3D chuyên sâu trước 10 phút để Chị xem rõ phác đồ rồi mới quyết định, Chị tiện ghé tầm Sáng hay Chiều hơn ạ?";
        }
        return "Dạ bên em hiện đang tặng suất Bác sĩ Chuyên khoa I trực tiếp soi da AI 3D và lên phác đồ cá nhân hóa hoàn toàn miễn phí (0đ) trong 10 phút ạ. Để em giữ trước suất ưu tiên không phải chờ đợi cho mình, Chị tiện ghé vào khung giờ Sáng hay Chiều mai hơn ạ?";
    }

    private static String inferAntiPatternFromText(String lower) {
        if (lower.contains("giá") || lower.contains("xả sạch") || lower.contains("3 nấc")) {
            return "Liệt kê một lèo tất cả 4 dịch vụ + Combo giảm 10%-15% ngay khi khách mới nhắn 'giá', khiến khách ngợp thông tin và im lặng rời đi.";
        }
        return "Trả lời dài dòng như văn mẫu, tung hết mọi thông tin trong 1 tin nhắn và kết thúc bằng câu hỏi đóng cụt lủn 'Chị thấy sao ạ?' hoặc 'Khi nào rảnh chị nhắn em nhé'.";
    }
}

