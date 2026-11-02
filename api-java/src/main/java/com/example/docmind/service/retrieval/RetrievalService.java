package com.example.docmind.service.retrieval;

import com.example.docmind.config.ChatProperties;
import com.example.docmind.dto.LearnedRuleDto;
import com.example.docmind.dto.PagePersonaConfig;
import com.example.docmind.entity.KnowledgePageEntity;
import com.example.docmind.entity.LearnedRuleEntity;
import com.example.docmind.repository.ChunkRepository;
import com.example.docmind.repository.KnowledgePageRepository;
import com.example.docmind.repository.LearnedRuleRepository;
import com.example.docmind.repository.RetrievedChunk;
import com.example.docmind.service.bot.FanpageBotPromptBuilder;
import com.example.docmind.service.embedding.EmbeddingClient;
import com.example.docmind.service.metadata.ChunkMetadataExtractor;
import com.example.docmind.service.text.VietnameseQueryNormalizer;
import com.example.docmind.service.graph.ObsidianMetadataCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Hybrid retrieval (Java-only): kết hợp vector search (pgvector cosine) + full-text
 * (tsvector) rồi fuse bằng RRF.
 *
 * <p>M2: Mở rộng truy vấn (query expansion) bằng retrieval_keywords từ các rule đang kích hoạt
 * trong AI Playbook, đồng thời trả về danh sách applied_rules cho PromptBuilder và ChatResponse.
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private final ChunkRepository chunkRepository;
    private final EmbeddingClient embeddingClient;
    private final ChatProperties chatProps;
    private final LearnedRuleRepository learnedRuleRepository;
    private final ObsidianMetadataCache metadataCache;
    private final KnowledgePageRepository knowledgePageRepository;

    public RetrievalService(ChunkRepository chunkRepository,
                            EmbeddingClient embeddingClient,
                            ChatProperties chatProps,
                            LearnedRuleRepository learnedRuleRepository) {
        this(chunkRepository, embeddingClient, chatProps, learnedRuleRepository, null, null);
    }

    public RetrievalService(ChunkRepository chunkRepository,
                            EmbeddingClient embeddingClient,
                            ChatProperties chatProps,
                            LearnedRuleRepository learnedRuleRepository,
                            ObsidianMetadataCache metadataCache) {
        this(chunkRepository, embeddingClient, chatProps, learnedRuleRepository, metadataCache, null);
    }

    @Autowired
    public RetrievalService(ChunkRepository chunkRepository,
                            EmbeddingClient embeddingClient,
                            ChatProperties chatProps,
                            LearnedRuleRepository learnedRuleRepository,
                            @Autowired(required = false) ObsidianMetadataCache metadataCache,
                            @Autowired(required = false) KnowledgePageRepository knowledgePageRepository) {
        this.chunkRepository = chunkRepository;
        this.embeddingClient = embeddingClient;
        this.chatProps = chatProps;
        this.learnedRuleRepository = learnedRuleRepository;
        this.metadataCache = metadataCache != null ? metadataCache : new ObsidianMetadataCache(chunkRepository);
        this.knowledgePageRepository = knowledgePageRepository;
    }

    public ObsidianMetadataCache getMetadataCache() {
        return metadataCache;
    }

    private static final double MIN_RULE_CONFIDENCE = 0.45;
    private static final int MAX_APPLIED_RULES = 3;

    /** Kết quả retrieval kèm danh sách quy tắc playbook đã áp dụng. */
    public record RetrievalResult(
            List<ReciprocalRankFusion.FusedChunk> chunks,
            List<LearnedRuleDto> appliedRules
    ) {
    }

    /**
     * Tương thích ngược: retrieve chỉ trả về danh sách FusedChunk.
     */
    @Transactional
    public List<ReciprocalRankFusion.FusedChunk> retrieve(List<UUID> documentIds, String question, Integer topK) {
        return retrieveWithRules(documentIds, question, topK, List.of()).chunks();
    }

    /**
     * Truy xuất tài liệu kèm theo tìm kiếm và áp dụng các quy tắc liên quan từ AI Playbook.
     */
    @Transactional
    public RetrievalResult retrieveWithRules(List<UUID> documentIds,
                                             String question,
                                             Integer topK,
                                             List<LearnedRuleDto> extraRules) {
        return retrieveWithRules(documentIds, question, topK, extraRules, null);
    }

    @Transactional
    public RetrievalResult retrieveWithRules(List<UUID> documentIds,
                                             String question,
                                             Integer topK,
                                             List<LearnedRuleDto> extraRules,
                                             UUID pageId) {
        PagePersonaConfig pageConfig = resolvePagePersonaConfig(pageId);
        Map<String, List<String>> pageSynonyms = pageConfig != null ? pageConfig.resolvedQuerySynonyms() : Collections.emptyMap();
        List<String> pageScopeKeywords = pageConfig != null ? pageConfig.resolvedScopeTaxonomy() : Collections.emptyList();
        return retrieveWithRulesInternal(documentIds, question, topK, extraRules, pageId, pageSynonyms, pageScopeKeywords);
    }

    @Transactional
    public RetrievalResult retrieveWithRules(List<UUID> documentIds,
                                             String question,
                                             Integer topK,
                                             List<LearnedRuleDto> extraRules,
                                             UUID pageId,
                                             Map<String, List<String>> pageQuerySynonyms) {
        PagePersonaConfig pageConfig = resolvePagePersonaConfig(pageId);
        Map<String, List<String>> effectiveSynonyms = (pageQuerySynonyms != null && !pageQuerySynonyms.isEmpty())
                ? pageQuerySynonyms
                : (pageConfig != null ? pageConfig.resolvedQuerySynonyms() : Collections.emptyMap());
        List<String> pageScopeKeywords = pageConfig != null ? pageConfig.resolvedScopeTaxonomy() : Collections.emptyList();
        return retrieveWithRulesInternal(documentIds, question, topK, extraRules, pageId, effectiveSynonyms, pageScopeKeywords);
    }

    /**
     * Nạp động toàn bộ {@code learned_rules} đang {@code active = true} (đã duyệt, không bị từ chối/staging)
     * của Page cho kiến trúc {@code LLM_COGNITIVE_V2} mà KHÔNG lọc ẩn ngầm bằng Regex tiền-LLM.
     */
    @Transactional(readOnly = true)
    public List<LearnedRuleDto> getAllActiveApprovedRulesForPage(UUID pageId) {
        if (learnedRuleRepository == null) {
            return List.of();
        }
        try {
            List<LearnedRuleEntity> entities = pageId != null
                    ? learnedRuleRepository.findActiveForPage(pageId)
                    : learnedRuleRepository.findByActiveTrue();
            if (entities == null || entities.isEmpty()) {
                return List.of();
            }
            List<LearnedRuleDto> activeApproved = entities.stream()
                    .filter(r -> r != null
                            && r.isActive()
                            && isRuleConfidenceEligible(r)
                            && (r.getReviewStatus() == null
                                || r.getReviewStatus().isBlank()
                                || "APPROVED".equalsIgnoreCase(r.getReviewStatus().strip())))
                    .sorted((a, b) -> {
                        int alwaysCmp = Boolean.compare(b.isAlwaysOn(), a.isAlwaysOn());
                        if (alwaysCmp != 0) {
                            return alwaysCmp;
                        }
                        boolean aPageSpecific = pageId != null && pageId.equals(a.getPageId());
                        boolean bPageSpecific = pageId != null && pageId.equals(b.getPageId());
                        int pageCmp = Boolean.compare(bPageSpecific, aPageSpecific);
                        if (pageCmp != 0) {
                            return pageCmp;
                        }
                        return Double.compare(
                                b.getConfidenceScore() > 0.0 ? b.getConfidenceScore() : 0.85,
                                a.getConfidenceScore() > 0.0 ? a.getConfidenceScore() : 0.85
                        );
                    })
                    .map(LearnedRuleDto::fromEntity)
                    .toList();
            return deduplicateRulesByText(activeApproved);
        } catch (Exception e) {
            log.warn("Không thể tải toàn bộ learned_rules active cho pageId={}: {}", pageId, e.getMessage());
            return List.of();
        }
    }

    private PagePersonaConfig resolvePagePersonaConfig(UUID pageId) {
        if (pageId == null || knowledgePageRepository == null) {
            return null;
        }
        try {
            return knowledgePageRepository.findById(pageId)
                    .map(KnowledgePageEntity::getPersonaConfig)
                    .orElse(null);
        } catch (Exception e) {
            log.debug("Bỏ qua tải cấu hình PagePersonaConfig khi retrieval cho pageId={}: {}", pageId, e.getMessage());
            return null;
        }
    }

    private RetrievalResult retrieveWithRulesInternal(List<UUID> documentIds,
                                                      String question,
                                                      Integer topK,
                                                      List<LearnedRuleDto> extraRules,
                                                      UUID pageId,
                                                      Map<String, List<String>> pageQuerySynonyms,
                                                      List<String> pageScopeKeywords) {
        question = VietnameseQueryNormalizer.normalize(question);
        int candidateK = pageId != null ? Math.max(chatProps.getCandidateK(), 80) : chatProps.getCandidateK();
        int finalK = topK != null ? topK : chatProps.getTopK();

        float[] queryEmbedding = embeddingClient.embed(question);

        // 1. Tìm các quy tắc AI Playbook đang kích hoạt phù hợp với câu hỏi (lọc confidence >= 0.45)
        Map<UUID, LearnedRuleDto> matchedRulesMap = new LinkedHashMap<>();
        Map<UUID, Double> ruleMatchScores = new LinkedHashMap<>();

        // Thêm extraRules trước (nếu có, ví dụ rule vừa trích xuất từ conversational correction)
        if (extraRules != null) {
            for (LearnedRuleDto er : extraRules) {
                if (er != null && er.id() != null && er.active() && er.confidenceScore() >= MIN_RULE_CONFIDENCE) {
                    matchedRulesMap.put(er.id(), er);
                    ruleMatchScores.put(er.id(), 2.0);
                }
            }
        }

        try {
            boolean hasPhoneNumber = question != null && question.matches(
                    "(?s).*(?:\\+84|84|0)(?:3[2-9]|5[25689]|7[06-9]|8[1-9]|9[0-46-9])(?:[.\\-\\s]?\\d){7}\\b.*");
            String rawQuestionLower = question != null ? question.toLowerCase() : "";
            boolean asksExplicitPrice = rawQuestionLower.contains("giá")
                    || rawQuestionLower.contains("bao nhiêu")
                    || rawQuestionLower.contains("chi phí");
            boolean mentionsPhoneTopicWithoutPrice = (hasPhoneNumber
                    || rawQuestionLower.contains("số điện thoại")
                    || rawQuestionLower.contains("sđt")) && !asksExplicitPrice;

            boolean isCoolingMsg = isCoolingOrHesitationQuery(rawQuestionLower);
            boolean isPriceObjOrDownsellMsg = isPriceObjectionOrDownsellQuery(rawQuestionLower);
            boolean isTrialOrCorrectionMsg = isTrialOrCorrectionQuery(rawQuestionLower);
            boolean isStyleOrProcessComplaintMsg = com.example.docmind.service.bot.CleanAgentOutputRefereeAndSanitizer
                    .isCommunicationStyleComplaintMessage(question);
            boolean hasSafetyCue = !isStyleOrProcessComplaintMsg && hasSafetyContraindicationQueryCue(rawQuestionLower);

            // Vector search trên learned_rules (nếu có pageId thì chỉ lấy rule của page đó + global rules)
            if (queryEmbedding != null && !isStyleOrProcessComplaintMsg) {
                List<LearnedRuleEntity> similarRules = pageId != null
                        ? learnedRuleRepository.searchSimilarActiveRules(queryEmbedding, 0.60, 5, pageId)
                        : learnedRuleRepository.searchSimilarActiveRules(queryEmbedding, 0.60, 5);
                int rankIdx = 0;
                for (LearnedRuleEntity r : similarRules) {
                    rankIdx++;
                    if (r == null || !r.isActive() || !isRuleConfidenceEligible(r)) {
                        continue;
                    }
                    if (isSafetyContraindicationRule(r) && !hasSafetyCue) {
                        continue;
                    }
                    if (isPhoneLeadSpecificRule(r) && !mentionsPhoneTopicWithoutPrice) {
                        continue;
                    }
                    if (isCoolingSpecificRule(r) && !isCoolingMsg) {
                        continue;
                    }
                    if (isPriceObjectionSpecificRule(r) && !isPriceObjOrDownsellMsg) {
                        continue;
                    }
                    if (isTrialOrCorrectionSpecificRule(r) && !isTrialOrCorrectionMsg) {
                        continue;
                    }
                    LearnedRuleDto dto = LearnedRuleDto.fromEntity(r);
                    matchedRulesMap.putIfAbsent(r.getId(), dto);
                    double baseVectorScore = Math.max(0.75, 1.20 - (rankIdx - 1) * 0.08);
                    if (pageId != null && pageId.equals(r.getPageId())) {
                        baseVectorScore += 0.12;
                    }
                    ruleMatchScores.merge(r.getId(), baseVectorScore, Math::max);
                }
            }

            // Keyword match trên active rules (scoped theo pageId nếu có)
            String lowerQuestion = rawQuestionLower
                    + ((hasPhoneNumber && !asksExplicitPrice) ? " số điện thoại sđt liên hệ tư vấn" : "");
            List<LearnedRuleEntity> activeRules = isStyleOrProcessComplaintMsg
                    ? List.of()
                    : (pageId != null
                        ? learnedRuleRepository.findActiveForPage(pageId)
                        : learnedRuleRepository.findByActiveTrue());
            for (LearnedRuleEntity r : activeRules) {
                if (r == null || !r.isActive() || !isRuleConfidenceEligible(r)) {
                    continue;
                }
                if (isSafetyContraindicationRule(r) && !hasSafetyCue) {
                    continue;
                }
                if (isPhoneLeadSpecificRule(r) && !mentionsPhoneTopicWithoutPrice) {
                    continue;
                }
                if (isCoolingSpecificRule(r) && !isCoolingMsg) {
                    continue;
                }
                if (isPriceObjectionSpecificRule(r) && !isPriceObjOrDownsellMsg) {
                    continue;
                }
                if (isTrialOrCorrectionSpecificRule(r) && !isTrialOrCorrectionMsg) {
                    continue;
                }
                int matchedKwCount = 0;
                if (r.getRetrievalKeywords() != null) {
                    for (String kw : r.getRetrievalKeywords()) {
                        if (kw != null && !kw.isBlank()) {
                            String normKw = kw.toLowerCase().strip();
                            if (isGenericNoiseKeyword(normKw)) {
                                continue;
                            }
                            if (lowerQuestion.contains(normKw)) {
                                matchedKwCount++;
                            }
                        }
                    }
                }
                if (matchedKwCount == 0 && hasPhoneNumber && !asksExplicitPrice && isPhoneLeadSpecificRule(r)) {
                    matchedKwCount = 1;
                }
                if (matchedKwCount > 0) {
                    matchedRulesMap.putIfAbsent(r.getId(), LearnedRuleDto.fromEntity(r));
                    double kwScore = 1.0 + Math.min(0.25, matchedKwCount * 0.06);
                    if (pageId != null && pageId.equals(r.getPageId())) {
                        kwScore += 0.12;
                    }
                    ruleMatchScores.merge(r.getId(), kwScore, (oldVal, newVal) -> Math.max(oldVal, newVal) + 0.10);
                }
            }
        } catch (Exception e) {
            log.warn("Không thể tìm kiếm quy tắc Playbook: {}", e.getMessage());
        }

        // Xếp hạng theo EffectiveScore = MatchScore * ConfidenceScore và giới hạn tối đa Top-3 quy tắc (MAX_APPLIED_RULES = 3)
        List<LearnedRuleDto> sortedRules = matchedRulesMap.values().stream()
                .sorted((a, b) -> {
                    double effA = computeEffectiveRuleScore(a, ruleMatchScores.getOrDefault(a.id(), 1.0));
                    double effB = computeEffectiveRuleScore(b, ruleMatchScores.getOrDefault(b.id(), 1.0));
                    return Double.compare(effB, effA);
                })
                .toList();

        List<LearnedRuleDto> deduplicatedRules = deduplicateRulesByText(sortedRules);
        List<LearnedRuleDto> appliedRules = deduplicatedRules.size() > MAX_APPLIED_RULES
                ? deduplicatedRules.subList(0, MAX_APPLIED_RULES)
                : deduplicatedRules;

        if (!appliedRules.isEmpty()) {
            try {
                List<UUID> appliedIds = appliedRules.stream()
                        .map(LearnedRuleDto::id)
                        .filter(java.util.Objects::nonNull)
                        .toList();
                learnedRuleRepository.recordRulesApplied(appliedIds);
            } catch (Exception e) {
                log.debug("Bỏ qua cập nhật applied_count cho learned_rules: {}", e.getMessage());
            }
        }

        // 2. Thu thập keywords để mở rộng truy vấn full-text:
        // Chỉ mở rộng viết tắt giá (580k -> 580.000), từ đồng nghĩa được cấu hình hợp lệ của Page, và retrieval_keywords từ learned_rules của Page.
        // Tuyệt đối KHÔNG tự động tiêm từ khóa ngành Spa ("bấm huyệt", "chườm ngải cứu", "gội đầu", "massage"...) cho các từ phổ thông ("văn phòng", "máy tính", "ngồi").
        List<String> keywordsToExpand = buildExpandedKeywords(question, appliedRules, pageQuerySynonyms);

        String fulltextQuery = question;
        if (!keywordsToExpand.isEmpty()) {
            fulltextQuery = question + " " + String.join(" ", keywordsToExpand);
            log.info("stage=retrieve expanded_keywords={}", keywordsToExpand);
        }

        // Arm 1: vector search (nếu có pageId thì khóa cứng theo page_id ở tầng SQL).
        List<RetrievedChunk> rawVectorHits = pageId != null
                ? chunkRepository.searchByVector(documentIds, queryEmbedding, candidateK, pageId)
                : chunkRepository.searchByVector(documentIds, queryEmbedding, candidateK);

        // Arm 2: full-text search với query đã mở rộng (khóa cứng theo page_id nếu có).
        List<RetrievedChunk> rawTextHits = pageId != null
                ? chunkRepository.searchByFullText(documentIds, fulltextQuery, candidateK, pageId)
                : chunkRepository.searchByFullText(documentIds, fulltextQuery, candidateK);

        List<RetrievedChunk> vectorHits = filterBareHeadingChunks(rawVectorHits);
        List<RetrievedChunk> textHits = filterBareHeadingChunks(rawTextHits);

        log.info("stage=retrieve vector_candidates={} fulltext_candidates={} candidate_k={} applied_rules={} page_id={}",
                vectorHits.size(), textHits.size(), candidateK, appliedRules.size(), pageId);

        // Fuse bằng RRF và gộp các chunk có nội dung bảng trùng lặp 100% giữa các cơ sở/chi nhánh.
        List<ReciprocalRankFusion.FusedChunk> fused = deduplicateIdenticalBodyChunks(
                ReciprocalRankFusion.fuse(List.of(vectorHits, textHits), chatProps.getRrfK())
        );

        // Ưu tiên bảng danh mục dịch vụ / bảng giá / cột Đối tượng phù hợp và hạ thấp mục 1 (thông tin nội bộ Page)
        prioritizeCatalogAndPriceTableChunks(fused, question, pageScopeKeywords);

        // Đa dạng hóa kết quả theo Độ phủ Từ khóa Câu hỏi (Query-Coverage MMR - học hỏi từ PR #1)
        List<ReciprocalRankFusion.FusedChunk> top = diversifyByQueryCoverage(fused, question, finalK, pageScopeKeywords);

        // F5: Mở rộng Backlinks hai chiều qua ObsidianMetadataCache (FAQ, PROCESS, PROMO)
        List<ReciprocalRankFusion.FusedChunk> expanded = metadataCache != null
                ? metadataCache.expandBacklinks(
                        top,
                        question,
                        pageId,
                        documentIds,
                        chatProps.getMaxBacklinks()
                )
                : top;

        log.info("stage=rerank fused_total={} returned={} rrf_k={}",
                fused.size(), expanded.size(), chatProps.getRrfK());

        return new RetrievalResult(expanded, appliedRules);
    }

    /**
     * Loại bỏ các chunk rỗng chỉ chứa đúng 1 dòng tiêu đề mục cha mà không có nội dung thân bài
     * (tránh việc chunk tiêu đề chiếm mất vị trí topK của bảng giá thực tế).
     */
    private static List<RetrievedChunk> filterBareHeadingChunks(List<RetrievedChunk> hits) {
        if (hits == null || hits.size() <= 1) {
            return hits == null ? List.of() : hits;
        }
        List<RetrievedChunk> filtered = hits.stream()
                .filter(c -> !isBareHeadingChunk(c))
                .toList();
        return filtered.isEmpty() ? hits : filtered;
    }

    private static boolean isBareHeadingChunk(RetrievedChunk c) {
        if (c == null || c.text() == null) {
            return true;
        }
        String t = c.text().strip();
        if (t.isEmpty()) {
            return true;
        }
        if (t.contains("\n") || t.length() > 95) {
            return false;
        }
        List<String> hp = c.headingPath();
        if (hp != null && !hp.isEmpty()) {
            String lastHeading = hp.get(hp.size() - 1);
            if (lastHeading != null && t.equalsIgnoreCase(lastHeading.strip())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Dựng danh sách từ khóa mở rộng đa ngành có kiểm soát:
     * 1. Mở rộng định dạng số tiền (VD: 580k -> 580.000).
     * 2. Mở rộng từ đồng nghĩa chỉ khi được cấu hình tường minh trên Page (pageQuerySynonyms).
     * 3. Mở rộng retrieval_keywords từ các learned_rules đang kích hoạt của Page.
     */
    public static List<String> buildExpandedKeywords(
            String question,
            List<LearnedRuleDto> appliedRules,
            Map<String, List<String>> pageQuerySynonyms
    ) {
        if (question == null || question.isBlank()
                || com.example.docmind.service.bot.CleanAgentOutputRefereeAndSanitizer
                        .isCommunicationStyleComplaintMessage(question)) {
            return List.of();
        }
        Set<String> keywordsToExpand = new LinkedHashSet<>(extractExpandedPriceTokens(question));
        String lowerQuestion = question.toLowerCase(Locale.ROOT);

        if (pageQuerySynonyms != null && !pageQuerySynonyms.isEmpty()) {
            for (Map.Entry<String, List<String>> entry : pageQuerySynonyms.entrySet()) {
                String trigger = entry.getKey();
                List<String> synonyms = entry.getValue();
                if (trigger == null || trigger.isBlank() || synonyms == null || synonyms.isEmpty()) {
                    continue;
                }
                if (containsWholePhrase(lowerQuestion, trigger.strip().toLowerCase(Locale.ROOT))) {
                    for (String syn : synonyms) {
                        if (syn != null && !syn.isBlank()) {
                            String trimmedSyn = syn.strip();
                            if (!isGenericNoiseKeyword(trimmedSyn.toLowerCase(Locale.ROOT))) {
                                keywordsToExpand.add(trimmedSyn);
                            }
                        }
                    }
                }
            }
        }

        if (appliedRules != null) {
            for (LearnedRuleDto rule : appliedRules) {
                if (rule != null && rule.retrievalKeywords() != null) {
                    for (String kw : rule.retrievalKeywords()) {
                        if (kw != null && !kw.isBlank()) {
                            String trimmedKw = kw.strip();
                            if (!isGenericNoiseKeyword(trimmedKw.toLowerCase(Locale.ROOT))) {
                                keywordsToExpand.add(trimmedKw);
                            }
                        }
                    }
                }
            }
        }
        return new ArrayList<>(keywordsToExpand);
    }

    private static boolean containsWholePhrase(String lowerText, String lowerPhrase) {
        if (lowerText == null || lowerPhrase == null || lowerPhrase.isBlank()) {
            return false;
        }
        Pattern p = Pattern.compile("(?U)(?<![\\p{L}\\p{N}])" + Pattern.quote(lowerPhrase) + "(?![\\p{L}\\p{N}])");
        return p.matcher(lowerText).find();
    }

    private static List<String> extractConfiguredQueryScopes(String lowerQuestion, List<String> pageScopeKeywords) {
        if (lowerQuestion == null || lowerQuestion.isBlank() || pageScopeKeywords == null || pageScopeKeywords.isEmpty()) {
            return List.of();
        }
        List<String> matched = new ArrayList<>();
        for (String kw : pageScopeKeywords) {
            if (kw == null || kw.isBlank()) {
                continue;
            }
            String norm = kw.strip().toLowerCase(Locale.ROOT);
            if (norm.length() >= 2 && containsWholePhrase(lowerQuestion, norm) && !matched.contains(norm)) {
                matched.add(norm);
            }
        }
        return matched;
    }

    private static List<String> extractExpandedPriceTokens(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        Set<String> expanded = new LinkedHashSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)\\b(\\d{2,4})\\s*k\\b")
                .matcher(question);
        while (m.find()) {
            String digits = m.group(1);
            String formatted = digits.length() == 4
                    ? digits.charAt(0) + "." + digits.substring(1) + ".000"
                    : digits + ".000";
            expanded.add(formatted);
            expanded.add(formatted + "vnđ");
            expanded.add(digits);
        }
        return new ArrayList<>(expanded);
    }

    private static List<ReciprocalRankFusion.FusedChunk> deduplicateIdenticalBodyChunks(
            List<ReciprocalRankFusion.FusedChunk> rawFused) {
        if (rawFused == null || rawFused.size() <= 1) {
            return rawFused == null ? new ArrayList<>() : new ArrayList<>(rawFused);
        }
        Map<String, ReciprocalRankFusion.FusedChunk> uniqueByBody = new LinkedHashMap<>();
        Map<String, Set<String>> mergedHeadingsByBody = new LinkedHashMap<>();
        for (ReciprocalRankFusion.FusedChunk fc : rawFused) {
            if (fc == null || fc.chunk() == null || fc.chunk().text() == null) {
                continue;
            }
            String sig = fc.chunk().text().replaceAll("\\s+", " ").strip();
            String hpJoined = fc.chunk().headingPath() != null ? String.join(" > ", fc.chunk().headingPath()).strip() : "";
            if (!uniqueByBody.containsKey(sig)) {
                uniqueByBody.put(sig, fc);
                Set<String> hs = new LinkedHashSet<>();
                if (!hpJoined.isBlank()) {
                    hs.add(hpJoined);
                }
                mergedHeadingsByBody.put(sig, hs);
            } else if (!hpJoined.isBlank()) {
                mergedHeadingsByBody.get(sig).add(hpJoined);
            }
        }
        List<ReciprocalRankFusion.FusedChunk> result = new ArrayList<>(uniqueByBody.size());
        for (Map.Entry<String, ReciprocalRankFusion.FusedChunk> entry : uniqueByBody.entrySet()) {
            String sig = entry.getKey();
            ReciprocalRankFusion.FusedChunk best = entry.getValue();
            Set<String> hs = mergedHeadingsByBody.get(sig);
            if (hs != null && hs.size() > 1) {
                RetrievedChunk orig = best.chunk();
                RetrievedChunk mergedChunk = new RetrievedChunk(
                        orig.chunkId(),
                        orig.documentId(),
                        List.of(String.join(" ; ", hs)),
                        orig.sourceBlockIds(),
                        orig.page(),
                        orig.bbox(),
                        orig.text(),
                        orig.score()
                );
                result.add(new ReciprocalRankFusion.FusedChunk(mergedChunk, best.score()));
            } else {
                result.add(best);
            }
        }
        return result;
    }

    private static void prioritizeCatalogAndPriceTableChunks(
            List<ReciprocalRankFusion.FusedChunk> fused,
            String question,
            List<String> pageScopeKeywords) {
        if (fused == null || fused.size() <= 1 || question == null) {
            return;
        }
        boolean hasSpecificPriceQuery = !extractExpandedPriceTokens(question).isEmpty();
        fused.sort((a, b) -> {
            int priorityA = catalogChunkPriority(a.chunk(), question, pageScopeKeywords);
            int priorityB = catalogChunkPriority(b.chunk(), question, pageScopeKeywords);
            if (priorityA != priorityB) {
                return Integer.compare(priorityB, priorityA);
            }
            if (!hasSpecificPriceQuery && priorityA >= 2) {
                boolean entryA = isEntryLevelPriceChunk(a.chunk());
                boolean entryB = isEntryLevelPriceChunk(b.chunk());
                if (entryA != entryB) {
                    return entryA ? -1 : 1;
                }
            }
            return Double.compare(b.score(), a.score());
        });
    }

    private static boolean isEntryLevelPriceChunk(RetrievedChunk c) {
        if (c == null || c.text() == null) {
            return false;
        }
        return ChunkMetadataExtractor.isEntryLevelPriceChunk(c.headingPath(), c.text());
    }

    private static boolean isPriceCatalogTable(RetrievedChunk c) {
        if (c == null || c.text() == null) {
            return false;
        }
        String text = c.text().toLowerCase(Locale.ROOT);
        String headings = c.headingPath() != null ? String.join(" ", c.headingPath()).toLowerCase(Locale.ROOT) : "";
        boolean hasTable = text.contains("|") && text.contains("---");
        return hasTable && (headings.contains("bảng giá") || headings.contains("danh mục") || headings.contains("báo giá")
                || text.contains("đơn giá") || text.contains("| mã gói |") || text.contains("| mã sp |")
                || text.contains("| sku |") || text.contains("| mã sản phẩm |") || text.contains("| mã khóa |")
                || text.contains("| giá |") || text.contains("vnđ |"));
    }

    private static int catalogChunkPriority(RetrievedChunk c, String question) {
        return catalogChunkPriority(c, question, Collections.emptyList());
    }

    private static int catalogChunkPriority(RetrievedChunk c, String question, List<String> pageScopeKeywords) {
        if (c == null || c.text() == null) {
            return 0;
        }
        String text = c.text().toLowerCase(Locale.ROOT);
        String headings = c.headingPath() != null ? String.join(" ", c.headingPath()).toLowerCase(Locale.ROOT) : "";
        // Luôn đẩy mục "1. THÔNG TIN CHUNG VỀ PAGE & TỆP KHÁCH HÀNG" xuống cuối để tránh LLM đọc nhầm mô tả nội bộ
        if (headings.contains("tệp khách hàng") || headings.contains("thông tin chung về page")) {
            return -1;
        }
        String q = question != null ? question.toLowerCase(Locale.ROOT) : "";
        boolean isPromoQuery = q.contains("ưu đãi") || q.contains("khuyến mãi") || q.contains("voucher")
                || q.contains("chiết khấu") || q.contains("giảm giá") || q.contains("combo") || q.contains("sale");
        boolean isProcessQuery = FanpageBotPromptBuilder.isServiceExplanationOrProcessInquiry(q);
        boolean isWarrantyQuery = q.contains("bảo hành") || q.contains("đổi trả") || q.contains("hoàn tiền")
                || q.contains("đổi hàng") || q.contains("bảo trì") || q.contains("cam kết");
        boolean isSocialProofQuery = q.contains("review") || q.contains("đánh giá") || q.contains("uy tín")
                || q.contains("chứng nhận") || q.contains("phản hồi") || q.contains("feedback");
        boolean isSpecQuery = q.contains("thành phần") || q.contains("chất liệu") || q.contains("kích thước")
                || q.contains("thông số") || q.contains("cấu hình") || q.contains("size") || q.contains("xuất xứ")
                || q.contains("quy cách");
        boolean isEntryOrDownsellQuery = q.contains("thử") || q.contains("trải nghiệm") || q.contains("cơ bản")
                || q.contains("khởi điểm") || q.contains("rẻ") || q.contains("mềm") || q.contains("thấp")
                || q.contains("tiết kiệm") || q.contains("nhỏ") || q.contains("cao thế") || q.contains("cao quá")
                || q.contains("cao vậy") || q.contains("hơi cao") || q.contains("giá cao") || q.contains("đắt")
                || q.contains("mắc") || q.contains("chát") || q.contains("gói khác") || q.contains("gói nào nữa")
                || q.contains("còn gói") || q.contains("lựa chọn khác") || q.contains("mẫu khác");
        boolean isHighTierSpecificQuery = q.contains("vip") || q.contains("cao cấp") || q.contains("premium")
                || q.contains("tinh hoa") || q.contains("chuyên sâu") || q.contains("nâng cao");
        boolean isCatalogOrSuitabilityQuery = isEntryOrDownsellQuery
                || q.contains("dịch vụ") || q.contains("sản phẩm") || q.contains("chi phí")
                || q.contains("giá") || q.contains("bao nhiêu") || q.contains("bảng giá") || q.contains("báo giá")
                || q.contains("gói") || q.contains("mẫu") || q.contains("khóa") || q.contains("liệu trình")
                || q.contains("phù hợp") || q.contains("tuổi") || q.contains("trung niên") || q.contains("nam")
                || q.contains("dùng được") || q.contains("tư vấn");

        List<String> queryScopes = extractConfiguredQueryScopes(q, pageScopeKeywords);
        int scopeBoost = 0;
        if (!queryScopes.isEmpty()) {
            boolean headingMatchesScope = queryScopes.stream().anyMatch(headings::contains);
            boolean textMatchesScope = queryScopes.stream().anyMatch(text::contains);
            if (headingMatchesScope) {
                scopeBoost = 2;
            } else if (textMatchesScope) {
                scopeBoost = 1;
            }
        }

        List<String> semanticTags = ChunkMetadataExtractor.extractSemanticTags(headings, text);
        boolean hasTable = text.contains("|") && text.contains("---");
        boolean isPromoChunk = (hasTable && (headings.contains("chiết khấu") || headings.contains("combo")
                || headings.contains("ưu đãi") || text.contains("tên ưu đãi") || text.contains("mức ưu đãi")
                || text.contains("voucher"))) || semanticTags.contains("scarcity_promo");
        boolean isProcessChunk = hasTable && !text.contains("| mã gói |") && !text.contains("| mã sp |") && !text.contains("| sku |")
                && (headings.contains("quy trình") || headings.contains("lưu_ý") || headings.contains("chi tiết")
                || text.contains("| bước |") || text.contains("mục đích / lợi ích") || text.contains("quy trình chi tiết")
                || text.contains("gồm những gì") || text.contains("tập trung vào gì"));
        boolean isFaqChunk = hasTable && (headings.contains("khách hỏi") || headings.contains("faq")
                || headings.contains("hỏi đáp") || headings.contains("câu hỏi")
                || headings.contains("lưu_ý_quy_trình") || text.contains("| câu hỏi |")
                || text.contains("| nội dung | chi tiết |") || text.contains("gồm những gì?")
                || text.contains("tập trung vào gì?"));
        boolean isWarrantyChunk = semanticTags.contains("warranty_return");
        boolean isSocialProofChunk = semanticTags.contains("social_proof");
        boolean isSpecChunk = semanticTags.contains("spec_ingredients");

        if (isPromoQuery && isPromoChunk) {
            return 3 + scopeBoost;
        }
        if (isProcessQuery && (isProcessChunk || isFaqChunk)) {
            return ((!q.contains("giá") && !q.contains("bao nhiêu")) ? 4 : 3) + scopeBoost;
        }
        if (isWarrantyQuery && isWarrantyChunk) {
            return 3 + scopeBoost;
        }
        if (isSocialProofQuery && isSocialProofChunk) {
            return 3 + scopeBoost;
        }
        if (isSpecQuery && isSpecChunk) {
            return 3 + scopeBoost;
        }
        boolean isMultiPackageCatalogTable = (text.contains("| mã gói |") || text.contains("| mã dịch vụ |")
                || text.contains("| mã sp |") || text.contains("| sku |") || text.contains("| mã sản phẩm |")
                || text.contains("| mã khóa |"))
                && text.split("\\r?\\n").length >= 5;
        boolean isAuthoritativePriceChunk = (headings.contains("bảng giá")
                && (text.contains("có giá") || text.contains("mức thời lượng") || text.contains("bảng giá chính thức")
                || text.contains("đơn giá")))
                || isMultiPackageCatalogTable;
        if (isAuthoritativePriceChunk && (isCatalogOrSuitabilityQuery || !queryScopes.isEmpty())) {
            return 5 + scopeBoost;
        }
        if (!isCatalogOrSuitabilityQuery && !isPromoQuery && !isProcessQuery
                && !isWarrantyQuery && !isSocialProofQuery && !isSpecQuery) {
            return isAuthoritativePriceChunk ? (4 + scopeBoost) : scopeBoost;
        }
        if (isPriceCatalogTable(c)) {
            boolean hasSpecificPriceTokens = !extractExpandedPriceTokens(q).isEmpty();
            if (isEntryLevelPriceChunk(c) && (isEntryOrDownsellQuery || (!hasSpecificPriceTokens && !isHighTierSpecificQuery))) {
                return 3 + scopeBoost;
            }
            return 2 + scopeBoost;
        }
        if (isPromoChunk && isEntryOrDownsellQuery) {
            return 2;
        }
        if (isPromoChunk || isProcessChunk || isFaqChunk || isWarrantyChunk || isSocialProofChunk || isSpecChunk) {
            return 1;
        }
        return 0;
    }

    /**
     * Đa dạng hóa top-K kết quả theo Độ phủ Từ khóa Câu hỏi (Query-Coverage MMR):
     * Giữ nguyên các bảng giá chủ lực ở đầu, đồng thời cộng điểm thưởng cho các chunk tiếp theo
     * bao phủ được các từ khóa mới trong câu hỏi (giúp câu hỏi đa ý định lấy đủ cả bảng giá lẫn chính sách).
     */
    static List<ReciprocalRankFusion.FusedChunk> diversifyByQueryCoverage(
            List<ReciprocalRankFusion.FusedChunk> fused,
            String question,
            int finalK) {
        return diversifyByQueryCoverage(fused, question, finalK, Collections.emptyList());
    }

    static List<ReciprocalRankFusion.FusedChunk> diversifyByQueryCoverage(
            List<ReciprocalRankFusion.FusedChunk> fused,
            String question,
            int finalK,
            List<String> pageScopeKeywords) {
        if (fused == null || fused.size() <= finalK || question == null || question.isBlank()) {
            return fused == null ? List.of() : (fused.size() > finalK ? fused.subList(0, finalK) : fused);
        }
        String[] rawTokens = question.toLowerCase().replaceAll("[^\\p{L}\\p{N}\\s]", " ").trim().split("\\s+");
        List<String> queryTokens = new ArrayList<>();
        for (String t : rawTokens) {
            if (t.length() >= 2) {
                queryTokens.add(t);
            }
        }
        List<String> expandedPriceTokens = extractExpandedPriceTokens(question).stream()
                .filter(s -> s.contains("."))
                .map(String::toLowerCase)
                .toList();
        for (String exp : extractExpandedPriceTokens(question)) {
            String lowExp = exp.toLowerCase();
            if (!queryTokens.contains(lowExp)) {
                queryTokens.add(lowExp);
            }
        }
        if (queryTokens.isEmpty()) {
            return fused.subList(0, finalK);
        }

        List<ReciprocalRankFusion.FusedChunk> remaining = new ArrayList<>(fused);
        List<ReciprocalRankFusion.FusedChunk> selected = new ArrayList<>(finalK);
        Set<String> coveredTokens = new LinkedHashSet<>();
        int selectedPriceTables = 0;
        int selectedPriority3Chunks = 0;

        while (selected.size() < finalK && !remaining.isEmpty()) {
            int bestIdx = 0;
            double bestScore = Double.NEGATIVE_INFINITY;

            for (int i = 0; i < remaining.size(); i++) {
                ReciprocalRankFusion.FusedChunk cand = remaining.get(i);
                String candText = ((cand.chunk().headingPath() != null ? String.join(" ", cand.chunk().headingPath()) : "")
                        + " " + (cand.chunk().text() != null ? cand.chunk().text() : "")).toLowerCase();

                int uncoveredHits = 0;
                for (String qt : queryTokens) {
                    if (!coveredTokens.contains(qt) && candText.contains(qt)) {
                        uncoveredHits++;
                    }
                }
                int exactPriceHits = 0;
                for (String pt : expandedPriceTokens) {
                    if (!coveredTokens.contains(pt) && candText.contains(pt)) {
                        exactPriceHits++;
                    }
                }
                int rawPriority = catalogChunkPriority(cand.chunk(), question, pageScopeKeywords);
                if (rawPriority >= 3 && selectedPriority3Chunks >= 4) {
                    rawPriority = 1;
                }
                if (rawPriority == 2 && selectedPriceTables >= 4 && isPriceCatalogTable(cand.chunk()) && uncoveredHits == 0) {
                    // Sau khi đã lấy 4 bảng giá chủ lực và không có từ khóa mới, nhường slot cho bảng ưu đãi/quy trình/FAQ
                    rawPriority = 0;
                }
                int priorityBonus = rawPriority * 10;
                double mmrScore = priorityBonus + (cand.score() * 100.0) + (uncoveredHits * 2.5) + (exactPriceHits * 18.0) - (i * 0.05);
                if (mmrScore > bestScore) {
                    bestScore = mmrScore;
                    bestIdx = i;
                }
            }

            ReciprocalRankFusion.FusedChunk chosen = remaining.remove(bestIdx);
            selected.add(chosen);
            if (isPriceCatalogTable(chosen.chunk())) {
                selectedPriceTables++;
            }
            if (catalogChunkPriority(chosen.chunk(), question, pageScopeKeywords) >= 3) {
                selectedPriority3Chunks++;
            }
            String chosenText = ((chosen.chunk().headingPath() != null ? String.join(" ", chosen.chunk().headingPath()) : "")
                    + " " + (chosen.chunk().text() != null ? chosen.chunk().text() : "")).toLowerCase();
            for (String qt : queryTokens) {
                if (chosenText.contains(qt)) {
                    coveredTokens.add(qt);
                }
            }
        }
        return selected;
    }

    private static boolean isRuleConfidenceEligible(LearnedRuleEntity r) {
        if (r == null) {
            return false;
        }
        String status = r.getReviewStatus();
        if (status != null && !status.isBlank() && !"APPROVED".equalsIgnoreCase(status)) {
            return false;
        }
        double conf = r.getConfidenceScore() <= 0.0 ? 0.85 : r.getConfidenceScore();
        return conf >= MIN_RULE_CONFIDENCE;
    }

    private static double computeEffectiveRuleScore(LearnedRuleDto rule, double matchScore) {
        if (rule == null) {
            return 0.0;
        }
        double conf = rule.confidenceScore() <= 0.0 ? 0.85 : rule.confidenceScore();
        double conversionBonus = Math.min(0.10, Math.max(0, rule.successCount()) * 0.01);
        return (matchScore * conf) + conversionBonus;
    }

    private static boolean isPhoneLeadSpecificRule(LearnedRuleEntity r) {
        if (r == null) {
            return false;
        }
        String combined = ((r.getTriggerCondition() != null ? r.getTriggerCondition() : "") + " "
                + (r.getGeneralizedRule() != null ? r.getGeneralizedRule() : "")).toLowerCase();
        return combined.contains("số điện thoại") || combined.contains("sđt");
    }

    private static boolean isCoolingSpecificRule(LearnedRuleEntity r) {
        if (r == null) {
            return false;
        }
        String combined = ((r.getTriggerCondition() != null ? r.getTriggerCondition() : "") + " "
                + (r.getCueSignal() != null ? r.getCueSignal() : "") + " "
                + (r.getGeneralizedRule() != null ? r.getGeneralizedRule() : "")).toLowerCase(java.util.Locale.ROOT);
        return combined.contains("hạ nhiệt")
                || combined.contains("temperature_delta")
                || combined.contains("do dự")
                || combined.contains("ngập ngừng")
                || combined.contains("áp lực chốt bán hàng")
                || combined.contains("công nhận quyền cân nhắc")
                || combined.contains("phản hồi ngắn đối phó");
    }

    private static boolean isPriceObjectionSpecificRule(LearnedRuleEntity r) {
        if (r == null) {
            return false;
        }
        String code = r.getRuleCode() != null ? r.getRuleCode().toUpperCase(java.util.Locale.ROOT) : "";
        String cat = r.getCategory() != null ? r.getCategory().toUpperCase(java.util.Locale.ROOT) : "";
        if (code.contains("PRICE-OBJECTION") || code.equals("PB-001") || code.equals("PB-002")
                || cat.equals("PRICE_OBJECTION") || cat.equals("EXPLICIT_DOWNSELL")) {
            return true;
        }
        String combined = ((r.getTriggerCondition() != null ? r.getTriggerCondition() : "") + " "
                + (r.getCueSignal() != null ? r.getCueSignal() : "")).toLowerCase(java.util.Locale.ROOT);
        return combined.contains("price_objection")
                || combined.contains("chê giá")
                || combined.contains("chê đắt")
                || combined.contains("hơi đắt")
                || combined.contains("hơi cao")
                || combined.contains("gói nào mềm hơn")
                || combined.contains("gói nào rẻ hơn");
    }

    private static boolean isPriceObjectionOrDownsellQuery(String lowerQuestion) {
        if (lowerQuestion == null || lowerQuestion.isBlank()) {
            return false;
        }
        return com.example.docmind.service.bot.SentimentIntentLayer.isPriceObjection(lowerQuestion)
                || com.example.docmind.service.bot.SentimentIntentLayer.isExplicitDownsellRequest(lowerQuestion)
                || com.example.docmind.service.bot.SentimentIntentLayer.isPackageRejectionMessage(lowerQuestion)
                || com.example.docmind.service.bot.SentimentIntentLayer.isAlternativePackageRequest(lowerQuestion);
    }

    private static boolean isTrialOrCorrectionSpecificRule(LearnedRuleEntity r) {
        if (r == null) {
            return false;
        }
        String code = r.getRuleCode() != null ? r.getRuleCode().toUpperCase(java.util.Locale.ROOT) : "";
        String cat = r.getCategory() != null ? r.getCategory().toUpperCase(java.util.Locale.ROOT) : "";
        return code.contains("TRIAL-ACCEPT") || code.contains("CORRECTION")
                || cat.equals("TRIAL_ACCEPT") || cat.equals("CORRECTION");
    }

    private static boolean isTrialOrCorrectionQuery(String lowerQuestion) {
        if (lowerQuestion == null || lowerQuestion.isBlank()) {
            return false;
        }
        return com.example.docmind.service.bot.SentimentIntentLayer.isTrialAcceptanceMessage(lowerQuestion)
                || com.example.docmind.service.bot.SentimentIntentLayer.isEntityMismatchCorrection(lowerQuestion)
                || com.example.docmind.service.bot.PrimaryActionRouter.isFrustrationMessage(lowerQuestion);
    }

    private static boolean isCoolingOrHesitationQuery(String lowerQuestion) {
        if (lowerQuestion == null || lowerQuestion.isBlank()) {
            return false;
        }
        return lowerQuestion.matches("(?isU)^\\s*(?:ừ|uk|uh|uhm|ok|oke|vâng|dạ|thôi)\\s*[.!,~?]*$")
                || lowerQuestion.contains("để xem")
                || lowerQuestion.contains("nghĩ thêm")
                || lowerQuestion.contains("suy nghĩ")
                || lowerQuestion.contains("chưa cần")
                || lowerQuestion.contains("khi khác")
                || lowerQuestion.contains("im lặng")
                || lowerQuestion.contains("re-engage")
                || lowerQuestion.contains("reengage");
    }

    private static boolean isSafetyContraindicationRule(LearnedRuleEntity r) {
        if (r == null) {
            return false;
        }
        String code = r.getRuleCode() != null ? r.getRuleCode().toUpperCase(java.util.Locale.ROOT) : "";
        String cat = r.getCategory() != null ? r.getCategory().toUpperCase(java.util.Locale.ROOT) : "";
        return code.startsWith("SAFE-") || cat.equals("SAFETY") || cat.contains("CONTRAINDICATION");
    }

    private static boolean hasSafetyContraindicationQueryCue(String lowerQuestion) {
        if (lowerQuestion == null || lowerQuestion.isBlank()) {
            return false;
        }
        return lowerQuestion.matches(
                "(?isU).*\\b(đau\\s+tai|chảy\\s+dịch|viêm\\s+tai|thủng\\s+màng\\s+nhĩ|hoa\\s+mắt|chóng\\s+mặt|nôn\\s+mửa|buồn\\s+nôn|sốt\\s+cao|huyết\\s+áp|tim\\s+mạch|bầu|mang\\s+thai|thai\\s+kỳ|dị\\s+ứng|vết\\s+thương\\s+hở|chống\\s+chỉ\\s+định|an\\s+toàn\\s+không)\\b.*"
        );
    }

    private static boolean isGenericNoiseKeyword(String normKw) {
        if (normKw == null || normKw.length() <= 2) {
            return true;
        }
        return switch (normKw) {
            case "tư vấn", "liên hệ", "liên hệ tư vấn", "gói", "liệu", "trị", "quy", "tắc", "global", "rule",
                 "giá", "shop", "bên", "mình", "của", "với", "bằng", "ngắn", "câu", "hỏi", "khách", "hàng",
                 "thật", "cho", "các", "khi", "cần", "như", "này", "được", "không", "trong", "hoặc", "nhưng",
                 "phải", "luôn", "trước", "sau", "đang", "rất", "hơn", "nhất", "tốt", "mức", "báo", "đặt",
                 "tìm", "quyền", "cân", "nhắc", "hoàn", "toàn", "tuyệt", "đối", "chốt", "bán", "lực", "phản",
                 "hồi", "dấu", "hiệu", "giai", "đoạn", "discovery", "temperature_delta", "harness", "chuẩn",
                 "mực", "đồng", "duy", "trì", "tối", "bong", "bóng", "kết", "thúc", "xưng", "gọn", "chẩn",
                 "đoán", "rào", "cản", "chưa", "tự", "nhiên", "lại", "rồi", "thông", "tin", "nói", "chuyện",
                 "hiểu", "xem", "thử", "nào", "mấy", "vậy", "thế", "sao", "tai" -> true;
            default -> false;
        };
    }

    private static List<LearnedRuleDto> deduplicateRulesByText(java.util.Collection<LearnedRuleDto> rules) {
        if (rules == null || rules.isEmpty()) {
            return List.of();
        }
        List<LearnedRuleDto> out = new ArrayList<>();
        Set<String> seenRuleText = new LinkedHashSet<>();
        for (LearnedRuleDto r : rules) {
            if (r == null || r.generalizedRule() == null || r.generalizedRule().isBlank()) {
                continue;
            }
            String norm = r.generalizedRule().strip().toLowerCase();
            if (norm.equals("global rule 1") || norm.equals("quy tắc giảm giá đã cập nhật")) {
                continue;
            }
            if (seenRuleText.add(norm)) {
                out.add(r);
            }
        }
        return out;
    }
}
