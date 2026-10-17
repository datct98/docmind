package com.example.docmind.service;

import com.example.docmind.dto.ChatRequest;
import com.example.docmind.dto.ChatResponse;
import com.example.docmind.dto.Citation;
import com.example.docmind.dto.LearnedRuleDto;
import com.example.docmind.entity.KnowledgePageEntity;
import com.example.docmind.repository.KnowledgePageRepository;
import com.example.docmind.repository.RetrievedChunk;
import com.example.docmind.service.llm.LlmClient;
import com.example.docmind.service.retrieval.PromptBuilder;
import com.example.docmind.service.retrieval.ReciprocalRankFusion;
import com.example.docmind.service.retrieval.RetrievalService;
import com.example.docmind.service.security.PromptSecurityGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Luồng chat/RAG CHỈ-JAVA: hybrid retrieval -> RRF rerank -> build prompt -> LLM
 * -> answer + citations -> citation-validate.
 *
 * <p>M2 & Page Persona:
 * - Nhận diện phản hồi sửa sai trong hội thoại (conversational correction).
 * - Tự động học quy tắc mới và đính kèm newly_learned_rule trong ChatResponse.
 * - Áp dụng các quy tắc liên quan từ AI Playbook và trả về applied_rules.
 * - Tự động nạp Page Persona (Ngành nghề, Đối tượng KH, Giọng văn) theo page_id và định dạng đầu ra Sale-Ready.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final int SNIPPET_MAX = 240;

    private final RetrievalService retrievalService;
    private final LlmClient llmClient;
    private final MetaLearningService metaLearningService;
    private final KnowledgePageRepository knowledgePageRepository;
    private com.example.docmind.repository.DocumentRepository documentRepository;

    @Autowired
    public ChatService(RetrievalService retrievalService,
                       LlmClient llmClient,
                       @Autowired(required = false) MetaLearningService metaLearningService,
                       @Autowired(required = false) KnowledgePageRepository knowledgePageRepository,
                       @Autowired(required = false) com.example.docmind.repository.DocumentRepository documentRepository) {
        this.retrievalService = retrievalService;
        this.llmClient = llmClient;
        this.metaLearningService = metaLearningService;
        this.knowledgePageRepository = knowledgePageRepository;
        this.documentRepository = documentRepository;
    }

    public ChatService(RetrievalService retrievalService,
                       LlmClient llmClient,
                       MetaLearningService metaLearningService,
                       KnowledgePageRepository knowledgePageRepository) {
        this(retrievalService, llmClient, metaLearningService, knowledgePageRepository, null);
    }

    public ChatService(RetrievalService retrievalService,
                       LlmClient llmClient,
                       MetaLearningService metaLearningService) {
        this(retrievalService, llmClient, metaLearningService, null, null);
    }

    public ChatService(RetrievalService retrievalService, LlmClient llmClient) {
        this(retrievalService, llmClient, null, null, null);
    }

    public ChatResponse chat(ChatRequest request) {
        List<UUID> documentIds = request.documentIds().stream()
                .map(UUID::fromString)
                .toList();

        UUID pageId = parsePageId(request.pageId());
        if (pageId == null && documentRepository != null && !documentIds.isEmpty()) {
            for (UUID docId : documentIds) {
                UUID inferred = documentRepository.findById(docId)
                        .map(com.example.docmind.entity.DocumentEntity::getPageId)
                        .orElse(null);
                if (inferred != null) {
                    pageId = inferred;
                    break;
                }
            }
        }

        // Chốt chặn sớm: Nếu câu hỏi là nỗ lực tấn công trực tiếp (Direct Prompt Injection / Jailbreak)
        if (PromptSecurityGuard.isDirectJailbreakAttempt(request.question())) {
            log.warn("stage=security_guard blocked_direct_jailbreak question={}", request.question());
            return new ChatResponse(PromptSecurityGuard.SAFE_JAILBREAK_REFUSAL, List.of(), List.of(), null);
        }

        // 0) Nhận diện phản hồi sửa sai từ hội thoại (conversational correction detection)
        LearnedRuleDto newlyLearnedRule = null;
        if (metaLearningService != null
                && !PromptSecurityGuard.isPoisonedRuleAttempt(request.question())
                && metaLearningService.isCorrectionMessage(request.question(), request.previousAnswer())) {
            try {
                String questionToLearn = request.previousQuestion() != null && !request.previousQuestion().isBlank()
                        ? request.previousQuestion()
                        : request.question();
                if (pageId != null) {
                    newlyLearnedRule = metaLearningService.learnFromFeedback(
                            questionToLearn,
                            request.previousAnswer(),
                            request.question(),
                            request.conversationId(),
                            null,
                            pageId.toString()
                    );
                } else {
                    newlyLearnedRule = metaLearningService.learnFromFeedback(
                            questionToLearn,
                            request.previousAnswer(),
                            request.question(),
                            request.conversationId(),
                            null
                    );
                }
                log.info("stage=conversational_correction learned_rule_id={} category={} page_id={}",
                        newlyLearnedRule.id(), newlyLearnedRule.category(), pageId);
            } catch (Exception e) {
                log.warn("Không thể tự động học quy tắc từ hội thoại: {}", e.getMessage());
            }
        }

        // 1) Hybrid retrieval + RRF rerank + áp dụng quy tắc Playbook.
        List<LearnedRuleDto> extraRules = newlyLearnedRule != null ? List.of(newlyLearnedRule) : List.of();
        RetrievalService.RetrievalResult retrievalResult = null;
        try {
            if (pageId != null) {
                retrievalResult = retrievalService.retrieveWithRules(documentIds, request.question(), request.topK(), extraRules, pageId);
            } else {
                retrievalResult = retrievalService.retrieveWithRules(documentIds, request.question(), request.topK(), extraRules);
            }
        } catch (Exception e) {
            log.warn("retrieveWithRules không hoàn thành, fallback sang retrieve: {}", e.getMessage());
        }

        List<ReciprocalRankFusion.FusedChunk> fused;
        List<LearnedRuleDto> appliedRules;

        if (retrievalResult != null && retrievalResult.chunks() != null) {
            fused = retrievalResult.chunks();
            appliedRules = new ArrayList<>(retrievalResult.appliedRules());
        } else {
            List<ReciprocalRankFusion.FusedChunk> legacy = retrievalService.retrieve(documentIds, request.question(), request.topK());
            fused = legacy != null ? legacy : List.of();
            appliedRules = new ArrayList<>(extraRules);
        }

        if (newlyLearnedRule != null) {
            final LearnedRuleDto finalLearnedRule = newlyLearnedRule;
            boolean alreadyPresent = appliedRules.stream().anyMatch(r -> r.id().equals(finalLearnedRule.id()));
            if (!alreadyPresent) {
                appliedRules.add(0, newlyLearnedRule);
            }
        }

        // 1.5) Truy xuất hồ sơ Page Persona (nếu đang hỏi đáp trong phạm vi 1 Page cụ thể)
        PromptBuilder.PagePersona pagePersona = resolvePagePersona(pageId);
        String honorific = com.example.docmind.service.bot.FanpageBotPromptBuilder
                .resolveCustomerHonorific(request.question(), null, null, pagePersona);

        // 2) Build prompt (có tiêm Page Persona + AI Playbook + Sale-Ready) + gọi LLM + kiểm duyệt đầu ra.
        String prompt = PromptBuilder.build(request.question(), fused, appliedRules, pagePersona);
        long llmStartNs = System.nanoTime();
        log.info("[LLM] ChatService calling engine={} (fused_chunks={}, applied_rules={}, page={})",
                llmClient.name(), fused.size(), appliedRules.size(),
                pagePersona != null ? pagePersona.name() : "global");
        String rawAnswer = llmClient.complete(prompt);
        long llmLatencyMs = (System.nanoTime() - llmStartNs) / 1_000_000L;
        boolean isFallback = rawAnswer != null && rawAnswer.matches("^\\[[a-z0-9\\-]+-(?:error|offline|unavailable)\\].*");
        String answer = cleanCustomerFacingOutput(
                PromptSecurityGuard.sanitizeModelOutput(rawAnswer),
                honorific,
                request.question(),
                appliedRules
        );
        log.info("[LLM] ChatService response from engine={} latencyMs={} status={} fused_chunks={} applied_rules={} page_persona={} honorific={}",
                llmClient.name(), llmLatencyMs, isFallback ? "fallback" : "ok",
                fused.size(), appliedRules.size(),
                pagePersona != null ? pagePersona.name() : "global", honorific);

        // 3) Dựng citations truy ngược block/page/bbox/heading_path.
        List<Citation> citations = new ArrayList<>(fused.size());
        Set<UUID> retrievedIds = new HashSet<>();
        for (ReciprocalRankFusion.FusedChunk fc : fused) {
            RetrievedChunk c = fc.chunk();
            retrievedIds.add(c.chunkId());
            citations.add(new Citation(
                    c.chunkId().toString(),
                    c.documentId().toString(),
                    c.sourceBlockIds().stream().map(UUID::toString).toList(),
                    c.page(),
                    toBboxList(c.bbox()),
                    c.headingPath(),
                    snippet(c.text())
            ));
        }

        // 4) Citation-validate: mọi citation phải trỏ về chunk thực sự được retrieve.
        long invalid = citations.stream()
                .filter(cit -> !retrievedIds.contains(UUID.fromString(cit.chunkId())))
                .count();
        log.info("stage=citation-validate total={} invalid={}", citations.size(), invalid);
        if (invalid > 0) {
            citations.removeIf(cit -> !retrievedIds.contains(UUID.fromString(cit.chunkId())));
        }

        return new ChatResponse(answer, citations, appliedRules, newlyLearnedRule);
    }

    private static List<Double> toBboxList(double[] bbox) {
        if (bbox == null) {
            return List.of();
        }
        return Arrays.stream(bbox).boxed().toList();
    }

    private static String snippet(String text) {
        if (text == null) {
            return "";
        }
        String t = text.strip();
        return t.length() <= SNIPPET_MAX ? t : t.substring(0, SNIPPET_MAX) + "…";
    }

    private PromptBuilder.PagePersona resolvePagePersona(UUID pageId) {
        if (pageId == null || knowledgePageRepository == null) {
            return null;
        }
        try {
            return knowledgePageRepository.findById(pageId)
                    .map(p -> new PromptBuilder.PagePersona(
                            p.getName(),
                            p.getIndustry(),
                            p.getTargetAudience(),
                            p.getDescription()
                    ))
                    .orElse(null);
        } catch (Exception e) {
            log.warn("Không thể tải PagePersona cho page_id={}: {}", pageId, e.getMessage());
            return null;
        }
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

    /**
     * Làm sạch các câu lỗi văn phong thường gặp ở model 7B:
     * - Không bảo khách hàng "tham khảo bảng giá niêm yết trong tài liệu"
     * - Không đọc lại mô tả nội bộ "Đối tượng khách hàng chủ yếu là..." hoặc "chuyên phục vụ khách hàng nữ giới 22-45 tuổi"
     * - Sửa lỗi đảo ngược đại từ chủ ngữ ("Em có thể tham khảo gói..." -> "Chị/Anh có thể tham khảo gói...")
     * - Đồng nhất danh xưng Anh / Chị và gọt câu hỏi kết thúc dồn dập ở phần 💬 [TIN NHẮN MẪU GỬI KHÁCH]
     * - Nếu khách để lại SĐT yêu cầu liên hệ tư vấn, đảm bảo không chào nhầm số điện thoại và tuân thủ mẫu câu trả lời trong AI Playbook.
     */
    private static String cleanCustomerFacingOutput(
            String raw,
            String honorific,
            String question,
            List<LearnedRuleDto> appliedRules) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String h = (honorific != null && !honorific.isBlank()) ? honorific.strip() : "Chị";
        String cleaned = raw
                .replaceAll("(?iU)[^.!?\\n]*\\b(tham\\s+khảo|xem\\s+thêm)[^.!?\\n]*\\btrong\\s+tài\\s+liệu[^.!?\\n]*[.!?]?\\s*", "")
                .replaceAll("(?iU)[^.!?\\n]*\\bđối\\s+tượng\\s+khách\\s+hàng\\s+(chủ\\s+yếu|mục\\s+tiêu)[^.!?\\n]*[.!?]?\\s*", "")
                .replaceAll("(?iU)(?:dạ[,\\s]*)?(?:bên\\s+em|chúng\\s+tôi|nàng\\s+sen|spa|page)\\s+chuyên\\s+(?:phục\\s+vụ|dành\\s+cho)\\s+khách\\s+hàng\\s+nữ[^.!?:\\n]*[,;.]?\\s*(?:nhưng\\s+thực\\s+tế[,\\s]*)?", "Dạ ")
                .replaceAll("(?iU)(?:^|(?<=[.!?\\n]\\s*))em\\s+(?:đã\\s+|rất\\s+)?hiểu\\s+(?:câu\\s+hỏi|thắc\\s+mắc)\\s+của\\s+(?:chị|anh|anh/chị|bạn|mình)(?:\\s+ạ)?[.!]?\\s*", "")
                .replaceAll("(?iU)(?<=\\b(?:chào\\s+anh|chào\\s+chị))\\s+0\\d{8,10}\\b", "")
                .replaceAll("(?iU)(^|(?<=[.!?\\n,;:\\-]\\s*))em\\s+(có\\s+thể\\s+(?:tham\\s+khảo|lựa\\s+chọn|chọn|trải\\s+nghiệm|dùng|sử\\s+dụng|đăng\\s+ký|cân\\s+nhắc)|nên\\s+(?:tham\\s+khảo|lựa\\s+chọn|chọn|dùng|trải\\s+nghiệm)|sẽ\\s+(?:nhận\\s+được|được\\s+tặng|được\\s+giảm|được\\s+hưởng))", "$1" + h + " $2");

        boolean isPhoneLeadOnly = question != null
                && question.matches("(?s).*(?:\\+84|84|0)(?:3[2-9]|5[25689]|7[06-9]|8[1-9]|9[0-46-9])(?:[.\\-\\s]?\\d){7}\\b.*")
                && !question.toLowerCase().matches("(?s).*(?:giá|bao nhiêu|chi phí).*");

        int saleIdx = cleaned.indexOf("💬");
        if (saleIdx >= 0) {
            String topPart = cleaned.substring(0, saleIdx);
            String bottomPart = cleaned.substring(saleIdx);
            java.util.regex.Matcher headerMatcher = java.util.regex.Pattern
                    .compile("^(?:\\*\\*)?💬\\s*(?:\\[[^\\n\\]]*\\]|[^:\\n]{3,60})(?:\\*\\*)?\\s*:?\\s*")
                    .matcher(bottomPart);
            String headerLine = "**💬 [TIN NHẮN MẪU GỬI KHÁCH - SALE READY]**: ";
            String body = bottomPart;
            if (headerMatcher.find()) {
                headerLine = headerMatcher.group();
                body = bottomPart.substring(headerMatcher.end());
            }
            String polishedBody = com.example.docmind.service.bot.FanpageBotService
                    .polishConversationalVietnamese(body, h);
            if (isPhoneLeadOnly) {
                String taughtTemplate = extractPhoneTemplateFromRules(appliedRules, h);
                boolean dumpsPriceTable = polishedBody.matches("(?isU).*(?:\\d{1,3}(?:[.,]\\d{3})+\\s*(?:vnđ|đ)|combo\\s+tiết\\s+kiệm|combo\\s+toàn\\s+diện).*");
                if (taughtTemplate != null) {
                    polishedBody = taughtTemplate;
                } else if (dumpsPriceTable) {
                    polishedBody = "Dạ vâng em nhận thông tin của mình ạ, em sẽ liên hệ để tư vấn mình thêm nha " + h.toLowerCase() + "!";
                }
                topPart = "**📋 [TRA CỨU CHUẨN XÁC & LƯU Ý NỘI BỘ]**:\n"
                        + "- Ghi nhận khách hàng để lại số điện thoại yêu cầu liên hệ tư vấn trực tiếp.\n"
                        + "- Chuyển thông tin SĐT của khách hàng đến nhân viên tư vấn để liên hệ hỗ trợ ngay.\n\n";
            }
            cleaned = topPart + headerLine + polishedBody;
        } else if (isPhoneLeadOnly) {
            String taughtTemplate = extractPhoneTemplateFromRules(appliedRules, h);
            String replyMsg = taughtTemplate != null
                    ? taughtTemplate
                    : "Dạ vâng em nhận thông tin của mình ạ, em sẽ liên hệ để tư vấn mình thêm nha " + h.toLowerCase() + "!";
            cleaned = "**📋 [TRA CỨU CHUẨN XÁC & LƯU Ý NỘI BỘ]**:\n"
                    + "- Ghi nhận khách hàng để lại số điện thoại yêu cầu liên hệ tư vấn trực tiếp.\n"
                    + "- Chuyển thông tin SĐT của khách hàng đến nhân viên tư vấn để liên hệ hỗ trợ ngay.\n\n"
                    + "**💬 [TIN NHẮN MẪU GỬI KHÁCH - SALE READY]**: " + replyMsg;
        }
        return cleaned.strip();
    }

    private static String extractPhoneTemplateFromRules(List<LearnedRuleDto> appliedRules, String honorific) {
        if (appliedRules == null) {
            return null;
        }
        java.util.regex.Pattern quotePattern = java.util.regex.Pattern.compile("['\"“”‘’]([^'\"“”‘’]{12,260})['\"“”‘’]");
        for (LearnedRuleDto rule : appliedRules) {
            if (rule != null && rule.generalizedRule() != null) {
                String rText = rule.generalizedRule();
                if (rText.toLowerCase().contains("điện thoại") || rText.toLowerCase().contains("liên hệ")) {
                    var m = quotePattern.matcher(rText);
                    if (m.find()) {
                        return m.group(1).strip();
                    }
                }
            }
        }
        return null;
    }
}
