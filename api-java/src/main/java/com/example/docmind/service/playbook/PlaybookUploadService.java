package com.example.docmind.service.playbook;

import com.example.docmind.dto.LearnedRuleDto;
import com.example.docmind.dto.SampleDialogueDto;
import com.example.docmind.entity.HarnessDimension;
import com.example.docmind.entity.LearnedRuleEntity;
import com.example.docmind.entity.SampleDialogueEntity;
import com.example.docmind.repository.LearnedRuleRepository;
import com.example.docmind.repository.SampleDialogueRepository;
import com.example.docmind.service.MetaLearningService;
import com.example.docmind.service.embedding.EmbeddingClient;
import com.example.docmind.service.security.PromptSecurityGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dịch vụ nạp (Upload), phân tích cú pháp (YAML / Markdown / JSON), kiểm định an toàn (PII / Y khoa / Prompt Injection)
 * và kích hoạt ngay lập tức (Auto-ACTIVE, bỏ bước duyệt thủ công) cho Playbook Rules (PB-*) và Sample Dialogues (FD-*).
 */
@Service
public class PlaybookUploadService {

    private static final Logger log = LoggerFactory.getLogger(PlaybookUploadService.class);

    private static final Pattern VN_PHONE_PII_PATTERN = Pattern.compile(
            "(?:\\+84|84|0)(?:3[2-9]|5[25689]|7[06-9]|8[1-9]|9[0-46-9])(?:[.\\-\\s]?\\d){7,8}\\b"
    );

    private static final Pattern EMAIL_PII_PATTERN = Pattern.compile(
            "(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b"
    );

    private static final Pattern MEDICAL_CURE_CLAIM_PATTERN = Pattern.compile(
            "(?iU)(chữa\\s+khỏi\\s+vĩnh\\s+viễn|trị\\s+dứt\\s+điểm\\s+100%|khỏi\\s+hẳn\\s+100%\\s+chỉ\\s+sau\\s+1\\s+buổi|"
            + "cam\\s+kết\\s+chữa\\s+khỏi\\s+bệnh|không\\s+cần\\s+đi\\s+viện)"
    );

    private static final Pattern COMPROMISED_BOT_REPLY_PATTERN = Pattern.compile(
            "(?iU)(system\\s+prompt\\s+của\\s+tôi\\s+là|đã\\s+bỏ\\s+qua\\s+mọi\\s+hướng\\s+dẫn\\s+trước\\s+đó|"
            + "xác\\s+nhận\\s+đặt[^>]*giá\\s+0\\s*đ|miễn\\s+phí\\s+hoàn\\s+toàn\\s+cho\\s+bạn|<DOCUMENT_CONTEXT>)"
    );

    private static final Pattern MD_SECTION_HEADER = Pattern.compile(
            "^#{2,4}\\s*\\[?([A-Za-z0-9_-]+)\\]?\\s*(.*)$"
    );

    private static final Pattern MD_KEY_VALUE = Pattern.compile(
            "^\\s*[-*]?\\s*(?:\\*\\*)?([A-Za-z0-9_]+)(?:\\*\\*)?\\s*:\\s*(.*)$"
    );

    private static final Pattern MD_VI_DU_HEADER = Pattern.compile(
            "^###\\s+Ví\\s+dụ\\s+(\\d+)\\s*:\\s*(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private final LearnedRuleRepository learnedRuleRepository;
    private final SampleDialogueRepository sampleDialogueRepository;
    private final EmbeddingClient embeddingClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public PlaybookUploadService(LearnedRuleRepository learnedRuleRepository,
                                 @Autowired(required = false) SampleDialogueRepository sampleDialogueRepository,
                                 EmbeddingClient embeddingClient,
                                 @Autowired(required = false) ObjectMapper objectMapper) {
        this.learnedRuleRepository = learnedRuleRepository;
        this.sampleDialogueRepository = sampleDialogueRepository;
        this.embeddingClient = embeddingClient;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public record PlaybookUploadResult(
            String format,
            String pageId,
            int totalPlaybookRulesParsed,
            int activePlaybookRulesSaved,
            int rejectedPlaybookRulesCount,
            int totalSampleDialoguesParsed,
            int activeSampleDialoguesSaved,
            int rejectedSampleDialoguesCount,
            List<LearnedRuleDto> activeRules,
            List<LearnedRuleDto> rejectedRules,
            List<SampleDialogueDto> activeDialogues,
            List<SampleDialogueDto> rejectedDialogues
    ) {}

    public record SecurityAuditVerdict(
            boolean passed,
            String reviewStatus,
            String quarantinedReason,
            String auditNotes
    ) {}

    public PlaybookUploadResult uploadPlaybookContent(String content,
                                                      String filenameOrFormatHint,
                                                      String pageIdRaw) {
        return uploadPlaybookContent(content, filenameOrFormatHint, pageIdRaw, false);
    }

    public PlaybookUploadResult uploadPlaybookContent(String content,
                                                      String filenameOrFormatHint,
                                                      String pageIdRaw,
                                                      boolean replaceExisting) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Nội dung Playbook tải lên không được để trống.");
        }
        String detectedFormat = detectFormat(content, filenameOrFormatHint);
        UUID pageId = parsePageId(pageIdRaw);

        ParsedPlaybookBundle bundle = switch (detectedFormat) {
            case "JSON" -> parseJsonBundle(content);
            case "MARKDOWN" -> parseMarkdownBundle(content);
            default -> parseYamlBundle(content);
        };

        if (replaceExisting && pageId != null) {
            List<LearnedRuleEntity> oldRules = learnedRuleRepository.findAll().stream()
                    .filter(r -> pageId.equals(r.getPageId()))
                    .toList();
            if (!oldRules.isEmpty()) {
                learnedRuleRepository.deleteAll(oldRules);
                log.info("[PLAYBOOK-UPLOAD] Replaced/wiped {} existing learned_rules for pageId={}", oldRules.size(), pageId);
            }
            if (sampleDialogueRepository != null) {
                List<SampleDialogueEntity> oldDialogues = sampleDialogueRepository.findAll().stream()
                        .filter(d -> pageId.equals(d.getPageId()))
                        .toList();
                if (!oldDialogues.isEmpty()) {
                    sampleDialogueRepository.deleteAll(oldDialogues);
                    log.info("[PLAYBOOK-UPLOAD] Replaced/wiped {} existing sample_dialogues for pageId={}", oldDialogues.size(), pageId);
                }
            }
        }

        List<LearnedRuleDto> activeRules = new ArrayList<>();
        List<LearnedRuleDto> rejectedRules = new ArrayList<>();
        List<SampleDialogueDto> activeDialogues = new ArrayList<>();
        List<SampleDialogueDto> rejectedDialogues = new ArrayList<>();

        for (ParsedPlaybookItem item : bundle.playbookItems()) {
            LearnedRuleDto saved = upsertPlaybookRule(item, pageId, detectedFormat);
            if (saved.active() && "APPROVED".equalsIgnoreCase(saved.reviewStatus())) {
                activeRules.add(saved);
            } else {
                rejectedRules.add(saved);
            }
        }

        if (replaceExisting && bundle.safetyPolicyItems() != null) {
            for (ParsedPlaybookItem safetyItem : bundle.safetyPolicyItems()) {
                LearnedRuleDto saved = upsertPlaybookRule(safetyItem, pageId, detectedFormat);
                if (saved.active() && "APPROVED".equalsIgnoreCase(saved.reviewStatus())) {
                    activeRules.add(saved);
                } else {
                    rejectedRules.add(saved);
                }
            }
        }

        for (ParsedSampleDialogue item : bundle.sampleDialogues()) {
            SampleDialogueDto saved = upsertSampleDialogue(item, pageId, detectedFormat);
            if (saved.active() && "APPROVED".equalsIgnoreCase(saved.reviewStatus())) {
                activeDialogues.add(saved);
            } else {
                rejectedDialogues.add(saved);
            }
        }

        log.info("[PLAYBOOK-UPLOAD] format={}, pageId={}, rules(active={}, rejected={}), dialogues(active={}, rejected={})",
                detectedFormat, pageId, activeRules.size(), rejectedRules.size(),
                activeDialogues.size(), rejectedDialogues.size());

        return new PlaybookUploadResult(
                detectedFormat,
                pageId != null ? pageId.toString() : null,
                bundle.playbookItems().size(),
                activeRules.size(),
                rejectedRules.size(),
                bundle.sampleDialogues().size(),
                activeDialogues.size(),
                rejectedDialogues.size(),
                activeRules,
                rejectedRules,
                activeDialogues,
                rejectedDialogues
        );
    }

    public List<SampleDialogueDto> getSampleDialogues(String pageIdRaw,
                                                      Boolean active,
                                                      boolean includeGlobal,
                                                      String reviewStatus) {
        if (sampleDialogueRepository == null) {
            return List.of();
        }
        List<SampleDialogueEntity> entities;
        if (pageIdRaw != null && !pageIdRaw.isBlank()) {
            if ("__global__".equalsIgnoreCase(pageIdRaw.strip())) {
                entities = sampleDialogueRepository.searchDialoguesByPage(active, null, true, true);
            } else {
                UUID pageId = parsePageId(pageIdRaw);
                if (pageId != null) {
                    entities = sampleDialogueRepository.searchDialoguesByPage(active, pageId, includeGlobal, false);
                } else {
                    entities = sampleDialogueRepository.searchDialoguesByPage(active, null, true, true);
                }
            }
        } else {
            entities = sampleDialogueRepository.findAllByOrderByCreatedAtDesc();
            if (active != null) {
                entities = entities.stream().filter(d -> d.isActive() == active).toList();
            }
        }

        return entities.stream()
                .filter(d -> reviewStatus == null || reviewStatus.isBlank()
                        || "ALL".equalsIgnoreCase(reviewStatus)
                        || reviewStatus.equalsIgnoreCase(d.getReviewStatus()))
                .map(SampleDialogueDto::fromEntity)
                .toList();
    }

    public List<SampleDialogueDto> retrieveRelevantDialogues(String query, UUID pageId, int topK) {
        if (sampleDialogueRepository == null || query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        String lowerQ = query.toLowerCase(Locale.ROOT);
        Map<UUID, SampleDialogueDto> candidates = new LinkedHashMap<>();
        Map<UUID, Double> scores = new LinkedHashMap<>();

        try {
            float[] emb = embeddingClient != null ? embeddingClient.embed(query) : null;
            if (emb != null && emb.length > 0) {
                String vecLiteral = toVectorLiteral(emb);
                List<SampleDialogueEntity> vectorHits = sampleDialogueRepository.findSimilarApprovedDialogues(
                        vecLiteral, pageId, Math.max(4, topK * 2));
                int rank = 0;
                for (SampleDialogueEntity d : vectorHits) {
                    rank++;
                    if (d == null || !d.isActive()
                            || !"APPROVED".equalsIgnoreCase(d.getReviewStatus())
                            || (d.getQualityLabel() != null && !d.getQualityLabel().isBlank()
                                && !"APPROVED_GOOD".equalsIgnoreCase(d.getQualityLabel()))) {
                        continue;
                    }
                    candidates.putIfAbsent(d.getId(), SampleDialogueDto.fromEntity(d));
                    double score = Math.max(0.70, 1.15 - (rank - 1) * 0.08);
                    if (pageId != null && pageId.equals(d.getPageId())) {
                        score += 0.15;
                    }
                    scores.merge(d.getId(), score, Math::max);
                }
            }
        } catch (Exception e) {
            log.debug("Fallback to keyword matching for sample_dialogues: {}", e.getMessage());
        }

        try {
            List<SampleDialogueEntity> activeForPage = sampleDialogueRepository.findActiveApprovedForPage(pageId);
            for (SampleDialogueEntity d : activeForPage) {
                if (d == null || !d.isActive()
                        || !"APPROVED".equalsIgnoreCase(d.getReviewStatus())
                        || (d.getQualityLabel() != null && !d.getQualityLabel().isBlank()
                            && !"APPROVED_GOOD".equalsIgnoreCase(d.getQualityLabel()))) {
                    continue;
                }
                double kwScore = scoreDialogueByKeywordsAndIntent(d, lowerQ);
                if (kwScore > 0) {
                    if (pageId != null && pageId.equals(d.getPageId())) {
                        kwScore += 0.15;
                    }
                    candidates.putIfAbsent(d.getId(), SampleDialogueDto.fromEntity(d));
                    scores.merge(d.getId(), kwScore, (a, b) -> Math.max(a, b) + 0.10);
                }
            }
        } catch (Exception e) {
            log.debug("Unable to query active sample_dialogues: {}", e.getMessage());
        }

        return candidates.values().stream()
                .sorted((a, b) -> Double.compare(
                        scores.getOrDefault(b.id(), 0.0),
                        scores.getOrDefault(a.id(), 0.0)))
                .limit(topK)
                .toList();
    }

    private static double scoreDialogueByKeywordsAndIntent(SampleDialogueEntity d, String lowerQ) {
        double score = 0.0;
        String userSum = d.getUserSummary() != null ? d.getUserSummary().toLowerCase(Locale.ROOT) : "";
        String tags = d.getTags() != null ? d.getTags().toLowerCase(Locale.ROOT) : "";
        String intent = d.getIntent() != null ? d.getIntent().toLowerCase(Locale.ROOT) : "";

        if ((lowerQ.contains("bầu") || lowerQ.contains("mang thai") || lowerQ.contains("thai kỳ") || lowerQ.contains("huyết áp"))
                && (intent.contains("safety") || tags.contains("pregnancy") || userSum.contains("bầu"))) {
            score += 1.4;
        }
        if ((lowerQ.contains("cổ vai") || lowerQ.contains("mỏi vai") || lowerQ.contains("đau vai") || lowerQ.contains("máy tính"))
                && (tags.contains("neck_shoulder") || userSum.contains("cổ vai"))) {
            score += 1.25;
        }
        if ((lowerQ.contains("gội đầu") || lowerQ.contains("thảo mộc") || lowerQ.contains("mất ngủ") || lowerQ.contains("khó ngủ"))
                && (tags.contains("herbal_shampoo") || userSum.contains("gội đầu"))) {
            score += 1.25;
        }
        if ((lowerQ.contains("ngoáy tai") || lowerQ.contains("vệ sinh tai") || lowerQ.contains("đau tai") || lowerQ.contains("ù tai"))
                && (tags.contains("ear_care") || userSum.contains("ngoáy tai"))) {
            score += 1.3;
        }
        if ((lowerQ.contains("cao") || lowerQ.contains("đắt") || lowerQ.contains("giảm giá") || lowerQ.contains("mềm hơn") || lowerQ.contains("rẻ hơn"))
                && (intent.contains("pricing") || tags.contains("objection"))) {
            score += 1.3;
        }
        if ((lowerQ.contains("đặt lịch") || lowerQ.contains("giữ chỗ") || lowerQ.contains("2 suất") || lowerQ.contains("chiều nay"))
                && (intent.contains("booking") || tags.contains("booking"))) {
            score += 1.2;
        }
        if ((lowerQ.contains("combo") || lowerQ.contains("toàn diện") || (lowerQ.contains("cổ vai") && lowerQ.contains("gội đầu")))
                && (intent.contains("upsell") || tags.contains("combo"))) {
            score += 1.35;
        }
        if ((lowerQ.contains("ê ẩm") || lowerQ.contains("đau nhức hơn") || lowerQ.contains("khiếu nại") || lowerQ.contains("ấn hơi mạnh"))
                && (intent.contains("complaint") || tags.contains("complaint"))) {
            score += 1.4;
        }
        if ((lowerQ.contains("dịch vụ gì") || lowerQ.contains("tư vấn giúp") || lowerQ.contains("thư giãn"))
                && (intent.contains("greeting") || tags.contains("opening"))) {
            score += 1.1;
        }
        return score;
    }

    public static String detectFormat(String content, String filenameOrFormatHint) {
        if (filenameOrFormatHint != null && !filenameOrFormatHint.isBlank()) {
            String h = filenameOrFormatHint.strip().toLowerCase(Locale.ROOT);
            if (h.endsWith(".json") || "json".equals(h)) {
                return "JSON";
            }
            if (h.endsWith(".md") || h.endsWith(".markdown") || "md".equals(h) || "markdown".equals(h)) {
                return "MARKDOWN";
            }
            if (h.endsWith(".yaml") || h.endsWith(".yml") || "yaml".equals(h) || "yml".equals(h)) {
                return "YAML";
            }
        }
        String trimmed = content.strip();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return "JSON";
        }
        if (trimmed.startsWith("# ") || trimmed.contains("\n### [PB-") || trimmed.contains("\n### [FD-")) {
            return "MARKDOWN";
        }
        return "YAML";
    }

    public SecurityAuditVerdict auditPlaybookRule(ParsedPlaybookItem item) {
        String combined = (item.title() != null ? item.title() : "") + " "
                + String.join(" ", item.doActions()) + " "
                + String.join(" ", item.dontActions()) + " "
                + String.join(" ", item.recommendedQuestions());

        boolean isGuardrailRule = "prompt_injection_or_out_of_scope".equalsIgnoreCase(item.intent())
                || "guardrail".equalsIgnoreCase(item.stage())
                || (item.id() != null && item.id().toUpperCase(Locale.ROOT).contains("INJECTION"));

        List<String> violations = new ArrayList<>();
        if (VN_PHONE_PII_PATTERN.matcher(combined).find() || EMAIL_PII_PATTERN.matcher(combined).find()) {
            violations.add("Chứa thông tin định danh cá nhân (PII: Số điện thoại hoặc Email thật)");
        }
        String doJoined = String.join(" ", item.doActions()) + " " + String.join(" ", item.recommendedQuestions());
        if (MEDICAL_CURE_CLAIM_PATTERN.matcher(doJoined).find()) {
            violations.add("Chứa cam kết y khoa vượt thẩm quyền Spa (hứa chữa khỏi vĩnh viễn / trị dứt điểm 100%)");
        }
        if (!isGuardrailRule && PromptSecurityGuard.isPoisonedRuleAttempt(doJoined)) {
            violations.add("Phát hiện dấu hiệu Prompt Injection / Playbook Poisoning trong chỉ dẫn hành động");
        }

        if (!violations.isEmpty()) {
            String reason = String.join("; ", violations);
            return new SecurityAuditVerdict(false, "REJECTED", reason, "Auto-quarantined by PlaybookUploadService: " + reason);
        }
        return new SecurityAuditVerdict(
                true,
                "APPROVED",
                null,
                "Auto-approved on Playbook Upload (Schema valid, PII/Medical/Injection checks passed)"
        );
    }

    public SecurityAuditVerdict auditSampleDialogue(ParsedSampleDialogue item) {
        List<String> violations = new ArrayList<>();
        String codeUpper = item.id() != null ? item.id().toUpperCase(Locale.ROOT) : "";
        String tagsLower = item.tags() != null ? String.join(",", item.tags()).toLowerCase(Locale.ROOT) : "";
        String custTypeLower = item.customerType() != null ? item.customerType().toLowerCase(Locale.ROOT) : "";
        String assistantText = item.expectedBotAnswer() != null ? item.expectedBotAnswer() : "";
        String userText = item.userSummary() != null ? item.userSummary() : "";

        if (codeUpper.contains("-BAD-") || tagsLower.contains("negative_sample") || custTypeLower.contains("negative_sample")) {
            violations.add("Mẫu kiểm thử âm tính (negative_sample / BAD dialogue) không được phép nạp vào bộ nhớ ACTIVE");
        }
        if (VN_PHONE_PII_PATTERN.matcher(assistantText).find() || EMAIL_PII_PATTERN.matcher(assistantText).find()) {
            violations.add("Phản hồi mẫu của Assistant làm lộ PII khách hàng thật (SĐT / Email chưa che)");
        }
        if (MEDICAL_CURE_CLAIM_PATTERN.matcher(assistantText).find()) {
            violations.add("Phản hồi mẫu cam kết chữa khỏi bệnh lý y khoa vĩnh viễn / trị dứt điểm 100%");
        }
        if (COMPROMISED_BOT_REPLY_PATTERN.matcher(assistantText).find()
                || PromptSecurityGuard.isPoisonedRuleAttempt(assistantText)
                || PromptSecurityGuard.isDirectJailbreakAttempt(userText)) {
            violations.add("Hội thoại mẫu chứa Prompt Injection, rò rỉ system prompt hoặc xác nhận giá 0đ trái phép");
        }

        if (!violations.isEmpty()) {
            String reason = String.join("; ", violations);
            return new SecurityAuditVerdict(false, "REJECTED", reason, "Auto-quarantined by PlaybookUploadService: " + reason);
        }
        return new SecurityAuditVerdict(
                true,
                "APPROVED",
                null,
                "Auto-approved on Sample Dialogue Upload (PII/Medical/Injection checks passed)"
        );
    }

    private LearnedRuleDto upsertPlaybookRule(ParsedPlaybookItem item, UUID pageId, String format) {
        SecurityAuditVerdict verdict = auditPlaybookRule(item);
        String ruleCode = (item.id() != null && !item.id().isBlank())
                ? item.id().strip()
                : "PB-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        String category = mapIntentToCategory(item.intent(), item.stage());
        String triggerCondition = !item.whenUserSays().isEmpty()
                ? "Khi khách nói/hỏi (" + (item.intent() != null ? item.intent() : "general") + "): "
                  + String.join(" | ", item.whenUserSays())
                : "Khi xử lý tình huống: " + (item.title() != null ? item.title() : ruleCode);

        String doJoined = String.join("; ", item.doActions());
        String dontJoined = String.join("; ", item.dontActions());
        String recQJoined = String.join(" / ", item.recommendedQuestions());

        StringBuilder genRuleBuilder = new StringBuilder();
        if (item.title() != null && !item.title().isBlank()) {
            genRuleBuilder.append("[").append(ruleCode).append(" - ").append(item.title().strip()).append("] ");
        }
        if (!doJoined.isBlank()) {
            genRuleBuilder.append("NÊN LÀM: ").append(doJoined).append(". ");
        }
        if (!dontJoined.isBlank()) {
            genRuleBuilder.append("TRÁNH: ").append(dontJoined).append(". ");
        }
        if (!recQJoined.isBlank()) {
            genRuleBuilder.append("Câu hỏi gợi mở: ").append(recQJoined);
        }
        String generalizedRule = genRuleBuilder.toString().strip();
        String rootCause = "Playbook chuẩn hóa (" + format + ") cho intent="
                + (item.intent() != null ? item.intent() : "general")
                + ", stage=" + (item.stage() != null ? item.stage() : "consulting");

        Set<String> kwSet = new LinkedHashSet<>();
        if (item.tags() != null) {
            for (String t : item.tags()) {
                if (t != null && !t.isBlank()) kwSet.add(t.strip());
            }
        }
        for (String phrase : item.whenUserSays()) {
            if (phrase != null && !phrase.isBlank()) {
                kwSet.addAll(MetaLearningService.extractKeywords(phrase));
            }
        }
        if (kwSet.isEmpty()) {
            kwSet.addAll(MetaLearningService.extractKeywords(triggerCondition + " " + generalizedRule));
        }
        String[] kwArray = kwSet.stream().limit(16).toArray(String[]::new);

        boolean active = verdict.passed();
        float[] embedding = embeddingClient != null
                ? embeddingClient.embed(triggerCondition + " " + generalizedRule)
                : null;

        Optional<LearnedRuleEntity> existingOpt = pageId != null
                ? learnedRuleRepository.findFirstByRuleCodeAndPageId(ruleCode, pageId)
                : learnedRuleRepository.findFirstByRuleCodeAndPageIdIsNull(ruleCode);

        UUID entityId = existingOpt.map(LearnedRuleEntity::getId).orElseGet(UUID::randomUUID);
        int priority = item.priority() != null && item.priority() > 0 ? item.priority() : 85;
        boolean isInvariant = priority >= 98 || "safety_and_contraindication".equalsIgnoreCase(item.intent())
                || "prompt_injection_or_out_of_scope".equalsIgnoreCase(item.intent());

        if (existingOpt.isEmpty()) {
            try {
                if (pageId != null) {
                    learnedRuleRepository.insertRule(entityId, category, triggerCondition, rootCause, generalizedRule, kwArray, active, embedding, pageId);
                } else {
                    learnedRuleRepository.insertRule(entityId, category, triggerCondition, rootCause, generalizedRule, kwArray, active, embedding);
                }
            } catch (Exception ignored) {
            }
        } else {
            try {
                learnedRuleRepository.updateRuleWithEmbedding(entityId, category, triggerCondition, rootCause, generalizedRule, kwArray, active, embedding, pageId);
            } catch (Exception ignored) {
            }
        }

        LearnedRuleEntity entity = existingOpt.orElseGet(() -> LearnedRuleEntity.builder()
                .id(entityId)
                .createdAt(Instant.now())
                .build());
        entity.setCategory(category);
        entity.setTriggerCondition(triggerCondition);
        entity.setRootCause(rootCause);
        entity.setGeneralizedRule(generalizedRule);
        entity.setRetrievalKeywords(kwArray);
        entity.setActive(active);
        entity.setPageId(pageId);
        entity.setConfidenceScore(active ? Math.min(0.98, Math.max(0.85, priority / 100.0)) : 0.10);
        entity.setDimension(isInvariant ? HarnessDimension.INV : HarnessDimension.DOM);
        entity.setCueSignal(!item.whenUserSays().isEmpty() ? String.join(" | ", item.whenUserSays()) : triggerCondition);
        entity.setContrastiveEvidence(!dontJoined.isBlank() ? "Tránh: " + dontJoined : "Tuân thủ sát <DOCUMENT_CONTEXT>");
        entity.setAlwaysOn(com.example.docmind.service.bot.PromptBudgetGuard.isGlobalCriticalRuleCode(ruleCode, category));
        entity.setReviewStatus(verdict.reviewStatus());
        entity.setQuarantinedReason(verdict.quarantinedReason());
        entity.setSecurityAuditNotes(verdict.auditNotes());
        entity.setRuleCode(ruleCode);
        entity.setTitle(item.title() != null ? item.title() : ruleCode);
        entity.setIntent(item.intent());
        entity.setStage(item.stage());
        entity.setPriority(priority);
        entity.setDoActions(doJoined);
        entity.setDontActions(dontJoined);
        entity.setGroundingPolicy(item.groundingPolicy() != null ? item.groundingPolicy() : "must_use_kb_document_context");
        entity.setHandoffPolicy(item.handoffPolicy() != null ? item.handoffPolicy() : "none");
        entity.setSourceFormat(format);
        entity.setUpdatedAt(Instant.now());

        learnedRuleRepository.save(entity);
        return LearnedRuleDto.fromEntity(entity);
    }

    private SampleDialogueDto upsertSampleDialogue(ParsedSampleDialogue item, UUID pageId, String format) {
        SecurityAuditVerdict verdict = auditSampleDialogue(item);
        String code = (item.id() != null && !item.id().isBlank())
                ? item.id().strip()
                : "FD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        boolean active = verdict.passed();

        String turnsJson = "[]";
        try {
            turnsJson = objectMapper.writeValueAsString(item.turns());
        } catch (Exception ignored) {
        }

        Optional<SampleDialogueEntity> existingOpt = Optional.empty();
        if (sampleDialogueRepository != null) {
            existingOpt = pageId != null
                    ? sampleDialogueRepository.findFirstByDialogueCodeAndPageId(code, pageId)
                    : sampleDialogueRepository.findFirstByDialogueCodeAndPageIdIsNull(code);
        }

        UUID entityId = existingOpt.map(SampleDialogueEntity::getId).orElseGet(UUID::randomUUID);
        SampleDialogueEntity entity = existingOpt.orElseGet(() -> SampleDialogueEntity.builder()
                .id(entityId)
                .createdAt(Instant.now())
                .build());

        entity.setDialogueCode(code);
        entity.setPageId(pageId);
        entity.setDomain(item.domain() != null && !item.domain().isBlank() ? item.domain() : "spa_wellness");
        entity.setIntent(item.intent());
        entity.setStage(item.stage());
        entity.setCustomerType(item.customerType());
        entity.setTone(item.tone());
        entity.setTurnsJson(turnsJson);
        entity.setUserSummary(item.userSummary() != null ? item.userSummary() : "");
        entity.setExpectedBotAnswer(item.expectedBotAnswer() != null ? item.expectedBotAnswer() : "");
        entity.setWhyGood(item.whyGood());
        entity.setDoNotCopyLiterally(item.doNotCopyLiterally());
        entity.setTags(item.tags() != null ? String.join(",", item.tags()) : "");
        entity.setActive(active);
        entity.setReviewStatus(verdict.reviewStatus());
        entity.setQualityLabel(active ? "APPROVED_GOOD" : "QUARANTINED_BAD");
        entity.setQuarantinedReason(verdict.quarantinedReason());
        entity.setSecurityAuditNotes(verdict.auditNotes());
        entity.setSourceFormat(format);
        entity.setUpdatedAt(Instant.now());

        if (sampleDialogueRepository != null) {
            sampleDialogueRepository.save(entity);
            if (active && embeddingClient != null) {
                try {
                    String textToEmbed = (entity.getIntent() != null ? entity.getIntent() + " " : "")
                            + entity.getUserSummary() + " " + (entity.getTags() != null ? entity.getTags() : "");
                    float[] emb = embeddingClient.embed(textToEmbed);
                    if (emb != null && emb.length > 0) {
                        sampleDialogueRepository.updateEmbedding(entity.getId(), toVectorLiteral(emb));
                    }
                } catch (Exception e) {
                    log.debug("Non-fatal: failed to update vector embedding for sample_dialogue {}: {}", code, e.getMessage());
                }
            }
        }

        return SampleDialogueDto.fromEntity(entity);
    }

    private static String mapIntentToCategory(String intent, String stage) {
        if (intent == null) return "PLAYBOOK_CONSULTATION";
        String lower = intent.toLowerCase(Locale.ROOT);
        if (lower.contains("price") || lower.contains("pricing") || lower.contains("promo")) {
            return "PRICING_CALCULATION";
        }
        if (lower.contains("safety") || lower.contains("contraindication") || lower.contains("complaint")) {
            return "POLICY";
        }
        if (lower.contains("booking") || lower.contains("upsell")) {
            return "CLOSING_BOOKING";
        }
        if (lower.contains("injection") || lower.contains("guardrail")) {
            return "SECURITY_GUARDRAIL";
        }
        return "PLAYBOOK_CONSULTATION";
    }

    public record ParsedPlaybookItem(
            String id,
            String title,
            String domain,
            String intent,
            String stage,
            Integer priority,
            List<String> whenUserSays,
            List<String> doActions,
            List<String> dontActions,
            List<String> recommendedQuestions,
            String groundingPolicy,
            String handoffPolicy,
            List<String> tags
    ) {}

    public record ParsedSampleDialogue(
            String id,
            String domain,
            String intent,
            String stage,
            String customerType,
            String tone,
            List<Map<String, String>> turns,
            String userSummary,
            String expectedBotAnswer,
            String whyGood,
            boolean doNotCopyLiterally,
            List<String> tags
    ) {}

    public record ParsedPlaybookBundle(
            List<ParsedPlaybookItem> playbookItems,
            List<ParsedSampleDialogue> sampleDialogues,
            List<ParsedPlaybookItem> safetyPolicyItems
    ) {
        public ParsedPlaybookBundle(List<ParsedPlaybookItem> playbookItems, List<ParsedSampleDialogue> sampleDialogues) {
            this(playbookItems, sampleDialogues, List.of());
        }
    }

    @SuppressWarnings("unchecked")
    private ParsedPlaybookBundle parseYamlBundle(String yamlContent) {
        Yaml yaml = new Yaml();
        Object loaded = yaml.load(yamlContent);
        if (!(loaded instanceof Map<?, ?> rootMap)) {
            throw new IllegalArgumentException("File YAML không đúng cấu trúc Map gốc (cần có playbook_items/playbook_rules hoặc sample_dialogues).");
        }
        return extractBundleFromMap((Map<String, Object>) rootMap);
    }

    @SuppressWarnings("unchecked")
    private ParsedPlaybookBundle parseJsonBundle(String jsonContent) {
        try {
            Map<String, Object> rootMap = objectMapper.readValue(jsonContent, Map.class);
            return extractBundleFromMap(rootMap);
        } catch (Exception e) {
            throw new IllegalArgumentException("File JSON không hợp lệ: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private ParsedPlaybookBundle extractBundleFromMap(Map<String, Object> rootMap) {
        List<ParsedPlaybookItem> pbItems = new ArrayList<>();
        List<ParsedSampleDialogue> dialogues = new ArrayList<>();
        List<ParsedPlaybookItem> safetyItems = new ArrayList<>();

        Object safetyPolicyObj = rootMap.get("safety_policy");
        if (safetyPolicyObj instanceof Map<?, ?> safetyMap) {
            Object rawSafetyRules = safetyMap.get("rules");
            if (rawSafetyRules instanceof List<?> sList) {
                for (Object elem : sList) {
                    if (elem instanceof Map<?, ?> sm) {
                        Map<String, Object> map = (Map<String, Object>) sm;
                        safetyItems.add(new ParsedPlaybookItem(
                                asString(map.get("id")),
                                asString(map.get("title")),
                                asStringOrDefault(map.get("domain"), "spa_wellness"),
                                asStringOrDefault(map.get("intent"), "safety_and_contraindication"),
                                asStringOrDefault(map.get("stage"), "HANDOFF"),
                                asInt(map.get("priority"), 100),
                                asStringList(map.get("when_user_says")),
                                asStringList(map.get("do")),
                                asStringList(map.get("dont")),
                                asStringList(map.get("recommended_questions")),
                                asStringOrDefault(map.get("grounding_policy"), "must_use_kb_document_context"),
                                asStringOrDefault(map.get("handoff_policy"), "mandatory_human_handoff"),
                                asStringList(map.get("tags"))
                        ));
                    }
                }
            }
        }

        Object rawItems = rootMap.get("playbook_items");
        if (rawItems == null) rawItems = rootMap.get("playbook_rules");
        if (rawItems == null) rawItems = rootMap.get("rules");
        if (rawItems instanceof List<?> list) {
            for (Object elem : list) {
                if (elem instanceof Map<?, ?> m) {
                    Map<String, Object> map = (Map<String, Object>) m;
                    pbItems.add(new ParsedPlaybookItem(
                            asString(map.get("id")),
                            asString(map.get("title")),
                            asStringOrDefault(map.get("domain"), "spa_wellness"),
                            asString(map.get("intent")),
                            asString(map.get("stage")),
                            asInt(map.get("priority"), 85),
                            asStringList(map.get("when_user_says")),
                            asStringList(map.get("do")),
                            asStringList(map.get("dont")),
                            asStringList(map.get("recommended_questions")),
                            asStringOrDefault(map.get("grounding_policy"), "must_use_kb_document_context"),
                            asStringOrDefault(map.get("handoff_policy"), "none"),
                            asStringList(map.get("tags"))
                    ));
                }
            }
        }

        Object rawDialogues = rootMap.get("sample_dialogues");
        if (rawDialogues == null) rawDialogues = rootMap.get("dialogues");
        if (rawDialogues instanceof List<?> list) {
            for (Object elem : list) {
                if (elem instanceof Map<?, ?> m) {
                    Map<String, Object> map = (Map<String, Object>) m;
                    List<Map<String, String>> turns = new ArrayList<>();
                    StringBuilder userSb = new StringBuilder();
                    StringBuilder botSb = new StringBuilder();
                    Object rawTurns = map.get("turns");
                    if (rawTurns instanceof List<?> turnList) {
                        for (Object tObj : turnList) {
                            if (tObj instanceof Map<?, ?> tm) {
                                // Support both {role: "user", text: "..."} and {user: "...", assistant: ["...", "..."]}
                                if (tm.containsKey("user") || tm.containsKey("assistant")) {
                                    String uText = asString(tm.get("user"));
                                    if (uText != null && !uText.isBlank()) {
                                        turns.add(Map.of("role", "user", "text", uText));
                                        if (!userSb.isEmpty()) userSb.append("\n");
                                        userSb.append(uText);
                                    }
                                    Object aObj = tm.get("assistant");
                                    if (aObj instanceof List<?> aList) {
                                        List<String> bParts = new ArrayList<>();
                                        for (Object bItem : aList) {
                                            if (bItem != null && !bItem.toString().isBlank()) {
                                                String bStr = bItem.toString().strip();
                                                turns.add(Map.of("role", "assistant", "text", bStr));
                                                bParts.add(bStr);
                                            }
                                        }
                                        if (!bParts.isEmpty()) {
                                            if (!botSb.isEmpty()) botSb.append("\n\n");
                                            botSb.append(String.join("\n\n", bParts));
                                        }
                                    } else if (aObj != null) {
                                        String aStr = aObj.toString().strip();
                                        if (!aStr.isBlank()) {
                                            turns.add(Map.of("role", "assistant", "text", aStr));
                                            if (!botSb.isEmpty()) botSb.append("\n");
                                            botSb.append(aStr);
                                        }
                                    }
                                } else {
                                    String role = asStringOrDefault(tm.get("role"), "user");
                                    String text = asString(tm.get("text"));
                                    if (text == null) text = asString(tm.get("content"));
                                    if (text != null) {
                                        turns.add(Map.of("role", role, "text", text));
                                        if ("assistant".equalsIgnoreCase(role) || "bot".equalsIgnoreCase(role)) {
                                            if (!botSb.isEmpty()) botSb.append("\n");
                                            botSb.append(text);
                                        } else {
                                            if (!userSb.isEmpty()) userSb.append("\n");
                                            userSb.append(text);
                                        }
                                    }
                                }
                            }
                        }
                    }
                    String userSum = !userSb.isEmpty() ? userSb.toString() : asStringOrDefault(map.get("user"), "");
                    String botAns = !botSb.isEmpty() ? botSb.toString() : asStringOrDefault(map.get("assistant"), "");
                    if (turns.isEmpty() && (!userSum.isBlank() || !botAns.isBlank())) {
                        turns.add(Map.of("role", "user", "text", userSum));
                        turns.add(Map.of("role", "assistant", "text", botAns));
                    }
                    List<String> parsedTags = new ArrayList<>(asStringList(map.get("tags")));
                    String qualityLabel = asString(map.get("quality_label"));
                    if (qualityLabel != null && qualityLabel.toUpperCase(Locale.ROOT).contains("QUARANTINED")) {
                        if (!parsedTags.contains("negative_sample")) {
                            parsedTags.add("negative_sample");
                        }
                    }
                    dialogues.add(new ParsedSampleDialogue(
                            asString(map.get("id")),
                            asStringOrDefault(map.get("domain"), "spa_wellness"),
                            asString(map.get("intent")),
                            asString(map.get("stage")),
                            asString(map.get("customer_type")),
                            asString(map.get("tone")),
                            turns,
                            userSum,
                            botAns,
                            asStringOrDefault(map.get("why_good"), asString(map.get("why_bad"))),
                            asBoolean(map.get("do_not_copy_literally"), true),
                            parsedTags
                    ));
                }
            }
        }

        return new ParsedPlaybookBundle(pbItems, dialogues, safetyItems);
    }

    private ParsedPlaybookBundle parseMarkdownBundle(String mdContent) {
        List<ParsedPlaybookItem> pbItems = new ArrayList<>();
        List<ParsedSampleDialogue> dialogues = new ArrayList<>();
        List<ParsedPlaybookItem> safetyItems = new ArrayList<>();

        String[] lines = mdContent.split("\\r?\\n");
        String currentId = null;
        String currentTitle = null;
        Map<String, String> kvPairs = new LinkedHashMap<>();
        Map<String, List<String>> listPairs = new LinkedHashMap<>();
        List<Map<String, String>> mdTurns = new ArrayList<>();
        String activeListKey = null;

        for (int i = 0; i <= lines.length; i++) {
            String line = (i < lines.length) ? lines[i] : "### [__EOF__] EOF";
            Matcher headerMatcher = MD_SECTION_HEADER.matcher(line.strip());
            if (headerMatcher.matches()) {
                String candidateId = headerMatcher.group(1).strip();
                String candidateTitle = headerMatcher.group(2).strip();
                if (candidateId.startsWith("PB-") || candidateId.startsWith("SAFE-") || candidateId.startsWith("FD-") || "__EOF__".equals(candidateId)) {
                    if (currentId != null) {
                        if (currentId.startsWith("PB-") || currentId.startsWith("SAFE-")) {
                            ParsedPlaybookItem parsedRule = new ParsedPlaybookItem(
                                    currentId,
                                    currentTitle,
                                    kvPairs.getOrDefault("domain", "spa_wellness"),
                                    kvPairs.get("intent"),
                                    kvPairs.get("stage"),
                                    asInt(kvPairs.get("priority"), 85),
                                    listPairs.getOrDefault("when_user_says", List.of()),
                                    listPairs.getOrDefault("do", List.of()),
                                    listPairs.getOrDefault("dont", List.of()),
                                    listPairs.getOrDefault("recommended_questions", List.of()),
                                    kvPairs.getOrDefault("grounding_policy", "must_use_kb_document_context"),
                                    kvPairs.getOrDefault("handoff_policy", "none"),
                                    parseCommaTags(kvPairs.get("tags"))
                            );
                            if (currentId.startsWith("SAFE-")) {
                                safetyItems.add(parsedRule);
                            } else {
                                pbItems.add(parsedRule);
                            }
                        } else if (currentId.startsWith("FD-")) {
                            String userText = kvPairs.getOrDefault("user", "");
                            String assistantText = kvPairs.getOrDefault("assistant", "");
                            List<Map<String, String>> turns = !mdTurns.isEmpty()
                                    ? new ArrayList<>(mdTurns)
                                    : List.of(
                                            Map.of("role", "user", "text", userText),
                                            Map.of("role", "assistant", "text", assistantText)
                                    );
                            dialogues.add(new ParsedSampleDialogue(
                                    currentId,
                                    kvPairs.getOrDefault("domain", "spa_wellness"),
                                    kvPairs.get("intent"),
                                    kvPairs.get("stage"),
                                    kvPairs.get("customer_type"),
                                    kvPairs.get("tone"),
                                    turns,
                                    userText,
                                    assistantText,
                                    kvPairs.getOrDefault("why_good", kvPairs.get("why_it_works")),
                                    asBoolean(kvPairs.get("do_not_copy_literally"), true),
                                    parseCommaTags(kvPairs.get("tags"))
                            ));
                        }
                    }
                    if ("__EOF__".equals(candidateId)) {
                        break;
                    }
                    currentId = candidateId;
                    currentTitle = candidateTitle;
                    kvPairs.clear();
                    listPairs.clear();
                    mdTurns.clear();
                    activeListKey = null;
                    continue;
                }
            }

            if (currentId == null) {
                continue;
            }

            Matcher kvMatcher = MD_KEY_VALUE.matcher(line);
            if (kvMatcher.matches()) {
                String key = kvMatcher.group(1).strip().toLowerCase(Locale.ROOT);
                String val = stripBackticksAndQuotes(kvMatcher.group(2).strip());
                if (val.isEmpty()) {
                    activeListKey = key;
                    listPairs.putIfAbsent(key, new ArrayList<>());
                } else {
                    boolean isTurnRole = "user".equals(key) || "assistant".equals(key) || key.startsWith("bubble_");
                    if (!"turns".equals(activeListKey) || !isTurnRole) {
                        activeListKey = null;
                    }
                    if ("user".equals(key) || "assistant".equals(key)) {
                        mdTurns.add(Map.of("role", key, "text", val));
                        kvPairs.merge(key, val, (oldVal, newVal) -> oldVal + "\n" + newVal);
                    } else if (key.startsWith("bubble_")) {
                        mdTurns.add(Map.of("role", "assistant", "text", val));
                        kvPairs.merge("assistant", val, (oldVal, newVal) -> oldVal + "\n\n" + newVal);
                    } else {
                        kvPairs.put(key, val);
                    }
                }
            } else if (activeListKey != null && line.strip().startsWith("-")) {
                String bulletVal = stripBackticksAndQuotes(line.strip().substring(1).strip());
                if (!bulletVal.isEmpty()) {
                    listPairs.computeIfAbsent(activeListKey, k -> new ArrayList<>()).add(bulletVal);
                }
            }
        }

        if (pbItems.isEmpty() || dialogues.isEmpty()) {
            parseSpaRuntimePlaybookV2Fallback(mdContent, pbItems, dialogues);
        }

        return new ParsedPlaybookBundle(pbItems, dialogues, safetyItems);
    }

    private void parseSpaRuntimePlaybookV2Fallback(String mdContent,
                                                   List<ParsedPlaybookItem> pbItems,
                                                   List<ParsedSampleDialogue> dialogues) {
        if (mdContent == null || mdContent.isBlank()) {
            return;
        }
        String normalized = mdContent.toLowerCase(Locale.ROOT);
        if (pbItems.isEmpty() && (normalized.contains("spa_runtime_playbook_v2")
                || normalized.contains("mở đầu bắt buộc")
                || normalized.contains("chính sách giá"))) {
            pbItems.add(new ParsedPlaybookItem(
                    "PB-SPA-OPEN-001",
                    "Mở đầu bắt buộc — Chào ấm áp và giới thiệu ngắn không báo giá",
                    "spa_wellness",
                    "greeting",
                    "OPENING",
                    96,
                    List.of("Chào shop", "Hi shop", "Shop ơi tư vấn giúp mình"),
                    List.of(
                            "Bong bóng 1: Dạ chào mình, em là {bot_name} của {shop_name} ạ. Bên em chuyên massage thư giãn, chăm sóc thảo dược và vệ sinh tai ngoài theo hướng nhẹ nhàng, sạch sẽ và chỉn chu.",
                            "Bong bóng 2: Mình cứ nhắn em biết đang muốn thư giãn, giảm mỏi hay chăm sóc riêng vùng nào, em sẽ gợi ý cho phù hợp ạ."
                    ),
                    List.of(
                            "Không liệt kê toàn bộ bảng giá ở tin nhắn mở đầu",
                            "Không hỏi số điện thoại ngay",
                            "Không chốt lịch ngay hoặc báo giá khi khách chưa hỏi"
                    ),
                    List.of("Mình cứ nhắn em biết đang muốn thư giãn, giảm mỏi hay chăm sóc riêng vùng nào, em sẽ gợi ý cho phù hợp ạ?"),
                    "must_use_kb_document_context",
                    "none",
                    List.of("opening", "greeting", "v2")
            ));
            pbItems.add(new ParsedPlaybookItem(
                    "PB-SPA-PRICE-002",
                    "Chính sách báo giá rõ từ tài liệu + khoảng lặng 15 giây neo cảm xúc",
                    "spa_wellness",
                    "price_inquiry",
                    "PRICING",
                    96,
                    List.of("Giá massage body 60 phút bao nhiêu?", "Massage cổ vai 45 phút giá sao shop?", "Ngâm chân thảo dược 20 phút bao nhiêu tiền?"),
                    List.of(
                            "Nếu là lượt đầu tiên: Bong bóng 1 chào ngắn và giới thiệu {bot_name} của {shop_name}.",
                            "Bong bóng giá: Dạ {service_name} {duration} bên em đang có giá {price} ạ.",
                            "Sau bong bóng giá bắt buộc delay 10000ms trước khi gửi bong bóng neo cảm xúc nhẹ nhàng."
                    ),
                    List.of(
                            "Không bịa giá ngoài product knowledge active",
                            "Không chốt đơn gay gắt ngay trong hoặc ngay sau bong bóng giá"
                    ),
                    List.of("Mình cứ chọn gói phù hợp với thời gian và mức mỏi, em chuẩn bị phòng sẵn cho mình nhé."),
                    "must_use_kb_document_context",
                    "none",
                    List.of("pricing", "delay_15s", "v2")
            ));
            pbItems.add(new ParsedPlaybookItem(
                    "PB-SPA-PRICE-CLARIFY-003",
                    "Hỏi làm rõ 1 câu ngắn khi khách hỏi giá chung chung",
                    "spa_wellness",
                    "ambiguous_price_inquiry",
                    "DISCOVERY",
                    94,
                    List.of("Massage bao nhiêu tiền?", "Bên mình giá sao?", "Bảng giá thế nào shop?"),
                    List.of(
                            "Bong bóng 1: Dạ bên em có vài mức giá theo từng dịch vụ và thời lượng ạ.",
                            "Bong bóng 2: Mình đang hỏi massage body, massage cổ vai hay chăm sóc thảo dược ạ?"
                    ),
                    List.of(
                            "Không tự đoán giá khi chưa rõ gói và thời lượng",
                            "Không chuyển người chỉ vì khách hỏi giá chung chung"
                    ),
                    List.of("Dạ mình đang hỏi massage body 60 phút hay 90 phút ạ?", "Mình đang hỏi massage body, massage cổ vai hay chăm sóc thảo dược ạ?"),
                    "must_use_kb_document_context",
                    "none",
                    List.of("pricing", "clarify", "v2")
            ));
            pbItems.add(new ParsedPlaybookItem(
                    "PB-SPA-OBJECTION-004",
                    "Xử lý khách chê giá / phân vân bằng sự đồng cảm và giá trị chăm sóc",
                    "spa_wellness",
                    "price_objection",
                    "OBJECTION",
                    95,
                    List.of("Sao đắt vậy?", "Đắt thế", "Chỗ khác rẻ hơn", "Để chị suy nghĩ thêm"),
                    List.of(
                            "Bong bóng 1: Dạ em hiểu mình đang cân nhắc về chi phí ạ. Bên em tập trung không gian riêng tư, dụng cụ vệ sinh và kỹ thuật viên hỏi cảm giác của mình trong lúc làm.",
                            "Bong bóng 2: Nếu mình muốn gói nhẹ nhàng hơn, em có thể gợi ý thời lượng ngắn phù hợp với nhu cầu thư giãn ạ."
                    ),
                    List.of(
                            "Không tự giảm giá hoặc bịa khuyến mãi",
                            "Không cãi khách hoặc chê đối thủ",
                            "Không chuyển người khi khách chỉ chê giá hoặc phân vân"
                    ),
                    List.of("Nếu mình muốn gói nhẹ nhàng hơn, em có thể gợi ý thời lượng ngắn phù hợp với nhu cầu thư giãn ạ."),
                    "must_use_kb_document_context",
                    "none",
                    List.of("objection", "price_objection", "v2")
            ));
            pbItems.add(new ParsedPlaybookItem(
                    "PB-SPA-RECOMMEND-005",
                    "Khám phá nhu cầu và gợi ý tối đa 1 dịch vụ phù hợp",
                    "spa_wellness",
                    "service_recommendation",
                    "CONSULTATION",
                    92,
                    List.of("Bên mình có massage không?", "Em ngồi máy tính nhiều bị mỏi cổ vai", "T bị đau lưng tê tay"),
                    List.of(
                            "Hỏi tối đa 1 câu ngắn về vùng mỏi hoặc mức lực mong muốn (lực nhẹ thư giãn hay lực vừa).",
                            "Mỗi lượt chỉ gợi ý tối đa 1 dịch vụ phù hợp với vùng mỏi của khách."
                    ),
                    List.of(
                            "Không liệt kê 3-5 dịch vụ cùng lúc",
                            "Không đẩy combo giá cao ngay hoặc chốt lịch khi khách chưa quan tâm rõ"
                    ),
                    List.of("Dạ với mỏi cổ vai do ngồi nhiều, mình thích lực nhẹ thư giãn hay lực vừa để giảm mỏi ạ?"),
                    "must_use_kb_document_context",
                    "none",
                    List.of("discovery", "recommendation", "v2")
            ));
            pbItems.add(new ParsedPlaybookItem(
                    "PB-SPA-BOOKING-006",
                    "Xác nhận ý định đặt lịch an toàn, không hứa chắc chỗ trống",
                    "spa_wellness",
                    "booking_request",
                    "CLOSING",
                    94,
                    List.of("Vậy em đặt massage body 60 phút lúc 7h tối nay", "Cho chị đặt lịch 3h chiều nhé"),
                    List.of(
                            "Bong bóng 1: Dạ em ghi nhận mình muốn {service_name} {duration} lúc {time} ạ.",
                            "Bong bóng 2: Em kiểm tra khung giờ và chuẩn bị phòng phù hợp, rồi phản hồi mình sớm nhất nhé."
                    ),
                    List.of(
                            "Không nói chắc chắn còn chỗ khi chưa có API realtime",
                            "Không đòi số điện thoại hoặc hỏi quá nhiều thông tin nhạy cảm ngay"
                    ),
                    List.of("Em kiểm tra khung giờ và chuẩn bị phòng phù hợp, rồi phản hồi mình sớm nhất nhé."),
                    "must_use_kb_document_context",
                    "none",
                    List.of("booking", "closing", "v2")
            ));
            pbItems.add(new ParsedPlaybookItem(
                    "PB-SPA-SAFETY-007",
                    "An toàn Spa & Hard Handoff (Mang thai, đau/ù tai, vết thương hở, khiếu nại)",
                    "spa_wellness",
                    "safety_or_complaint_handoff",
                    "HANDOFF",
                    100,
                    List.of("Chị đang mang thai massage được không?", "Chị bầu 5 tháng massage được không?", "Tai em đang đau và ù, vệ sinh tai được không?", "Hôm qua massage làm chị đau quá"),
                    List.of(
                            "Chỉ gửi đúng 1 bong bóng dừng tư vấn bán hàng và chuyển chuyên viên/quản lý hoặc khuyên khám Tai Mũi Họng.",
                            "Với mang thai/bệnh nền: Dạ trường hợp mang thai cần được tư vấn trực tiếp để an toàn cho chị ạ. Em chuyển chuyên viên kiểm tra và hướng dẫn phù hợp ngay nhé.",
                            "Với khiếu nại: Dạ em rất tiếc vì trải nghiệm chưa làm chị hài lòng ạ. Em chuyển quản lý phụ trách kiểm tra và phản hồi chị sớm nhất nhé."
                    ),
                    List.of(
                            "Không tư vấn y khoa hoặc cam kết chữa bệnh, hết đau, thải độc 100%, an toàn tuyệt đối",
                            "Không giải thích dài, không đổ lỗi cơ địa, không tự hoàn tiền"
                    ),
                    List.of(),
                    "must_use_kb_document_context",
                    "mandatory_human_handoff",
                    List.of("safety", "handoff", "complaint", "v2")
            ));
        }

        if (dialogues.isEmpty() && mdContent.contains("Ví dụ")) {
            String[] lines = mdContent.split("\\r?\\n");
            int currentNum = -1;
            String currentTitle = null;
            String userText = "";
            List<String> bubbles = new ArrayList<>();
            String mode = null; // "input" or "bubble"

            for (int i = 0; i <= lines.length; i++) {
                String trimmed = (i < lines.length) ? lines[i].strip() : "### Ví dụ 999: EOF";
                Matcher m = MD_VI_DU_HEADER.matcher(trimmed);
                if (m.matches() || (trimmed.startsWith("## ") && currentNum > 0)) {
                    if (currentNum > 0 && !userText.isBlank() && !bubbles.isEmpty()) {
                        String id = String.format("FD-SPA-V2-%03d", currentNum);
                        List<Map<String, String>> turns = new ArrayList<>();
                        turns.add(Map.of("role", "user", "text", userText));
                        for (String b : bubbles) {
                            turns.add(Map.of("role", "assistant", "text", b));
                        }
                        dialogues.add(new ParsedSampleDialogue(
                                id,
                                "spa_wellness",
                                currentTitle,
                                "CONSULTATION",
                                "spa_customer",
                                "warm_calm_professional",
                                turns,
                                userText,
                                String.join("\n\n", bubbles),
                                "Chuẩn SPA_RUNTIME_PLAYBOOK_V2: " + currentTitle,
                                true,
                                List.of("v2", "spa_runtime_playbook_v2")
                        ));
                    }
                    if (m.matches()) {
                        currentNum = asInt(m.group(1), -1);
                        if (currentNum == 999) {
                            break;
                        }
                        currentTitle = m.group(2).strip();
                        userText = "";
                        bubbles.clear();
                        mode = null;
                    } else {
                        currentNum = -1;
                    }
                    continue;
                }
                if (currentNum <= 0) {
                    continue;
                }
                if (trimmed.equalsIgnoreCase("Input:")) {
                    mode = "input";
                    continue;
                }
                if (trimmed.toLowerCase(Locale.ROOT).startsWith("bong bóng")) {
                    mode = "bubble";
                    continue;
                }
                if (trimmed.startsWith("---") || trimmed.startsWith("Điều kiện:") || trimmed.startsWith("Output chuẩn:")) {
                    mode = null;
                    continue;
                }
                if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                    String clean = stripBackticksAndQuotes(trimmed);
                    if ("input".equals(mode) && userText.isBlank()) {
                        userText = clean;
                        mode = null;
                    } else if ("bubble".equals(mode)) {
                        bubbles.add(clean);
                        mode = null;
                    }
                }
            }
        }
    }

    private static List<String> parseCommaTags(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.replace("[", "").replace("]", "").split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static String stripBackticksAndQuotes(String s) {
        if (s == null) return "";
        String t = s.strip();
        if ((t.startsWith("`") && t.endsWith("`")) || (t.startsWith("\"") && t.endsWith("\""))) {
            return t.substring(1, t.length() - 1).strip();
        }
        return t;
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString().strip();
    }

    private static String asStringOrDefault(Object o, String def) {
        String s = asString(o);
        return (s == null || s.isBlank()) ? def : s;
    }

    private static Integer asInt(Object o, int def) {
        if (o == null) return def;
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(o.toString().strip());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean asBoolean(Object o, boolean def) {
        if (o == null) return def;
        if (o instanceof Boolean b) return b;
        String s = o.toString().strip().toLowerCase(Locale.ROOT);
        if ("true".equals(s) || "yes".equals(s) || "1".equals(s)) return true;
        if ("false".equals(s) || "no".equals(s) || "0".equals(s)) return false;
        return def;
    }

    private static List<String> asStringList(Object o) {
        if (o == null) return List.of();
        if (o instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item != null && !item.toString().isBlank()) {
                    out.add(item.toString().strip());
                }
            }
            return out;
        }
        return parseCommaTags(o.toString());
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

    private static String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
