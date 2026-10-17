package com.example.docmind.service.bot;

import com.example.docmind.dto.bot.AgentSkillDto;
import com.example.docmind.dto.bot.AgentSkillRequest;
import com.example.docmind.entity.AgentSkillEntity;
import com.example.docmind.exception.ResourceNotFoundException;
import com.example.docmind.repository.AgentSkillRepository;
import com.example.docmind.service.security.PromptSecurityGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    public AgentSkillService(AgentSkillRepository agentSkillRepository) {
        this.agentSkillRepository = agentSkillRepository;
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
                    return true;
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

    @Transactional
    public AgentSkillDto createSkill(AgentSkillRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tên kỹ năng (name) không được để trống.");
        }
        if (request.tacticalInstructions() != null && PromptSecurityGuard.isPoisonedRuleAttempt(request.tacticalInstructions())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chỉ dẫn chiến thuật chứa nội dung vi phạm an toàn bảo mật.");
        }

        String category = normalizeCategory(request.category());
        String name = PromptSecurityGuard.sanitizeUntrustedContent(request.name().strip());
        String code = (request.code() != null && !request.code().isBlank())
                ? sanitizeCode(request.code())
                : generateCode(category, name);

        Instant now = Instant.now();
        AgentSkillEntity entity = AgentSkillEntity.builder()
                .id(UUID.randomUUID())
                .code(code)
                .name(name)
                .category(category)
                .description(safeText(request.description()))
                .triggerSignals(safeText(request.triggerSignals()))
                .tacticalInstructions(safeText(request.tacticalInstructions()))
                .goodExample(safeText(request.goodExample()))
                .antiPattern(safeText(request.antiPattern()))
                .priority(request.priority() != null ? Math.max(1, Math.min(100, request.priority())) : 75)
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

        if (request.tacticalInstructions() != null && PromptSecurityGuard.isPoisonedRuleAttempt(request.tacticalInstructions())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chỉ dẫn chiến thuật chứa nội dung vi phạm an toàn bảo mật.");
        }

        if (request.name() != null && !request.name().isBlank()) {
            entity.setName(PromptSecurityGuard.sanitizeUntrustedContent(request.name().strip()));
        }
        if (request.code() != null && !request.code().isBlank()) {
            entity.setCode(sanitizeCode(request.code()));
        }
        if (request.category() != null && !request.category().isBlank()) {
            entity.setCategory(normalizeCategory(request.category()));
        }
        if (request.description() != null) {
            entity.setDescription(safeText(request.description()));
        }
        if (request.triggerSignals() != null) {
            entity.setTriggerSignals(safeText(request.triggerSignals()));
        }
        if (request.tacticalInstructions() != null) {
            entity.setTacticalInstructions(safeText(request.tacticalInstructions()));
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
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            agentSkillRepository.save(skill);
            log.info("stage=agent_skill_auto_evolve action=create category={} name={} page_id={}", category, name, pageId);
        } catch (Exception e) {
            log.debug("Bỏ qua auto-sync AgentSkill từ feedback: {}", e.getMessage());
        }
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
}
