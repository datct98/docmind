package com.example.docmind.service.retrieval;

import com.example.docmind.config.ChatProperties;
import com.example.docmind.dto.LearnedRuleDto;
import com.example.docmind.entity.LearnedRuleEntity;
import com.example.docmind.repository.ChunkRepository;
import com.example.docmind.repository.LearnedRuleRepository;
import com.example.docmind.repository.RetrievedChunk;
import com.example.docmind.service.embedding.EmbeddingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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

    public RetrievalService(ChunkRepository chunkRepository,
                            EmbeddingClient embeddingClient,
                            ChatProperties chatProps,
                            LearnedRuleRepository learnedRuleRepository) {
        this.chunkRepository = chunkRepository;
        this.embeddingClient = embeddingClient;
        this.chatProps = chatProps;
        this.learnedRuleRepository = learnedRuleRepository;
    }

    /** Kết quả retrieval kèm danh sách quy tắc playbook đã áp dụng. */
    public record RetrievalResult(
            List<ReciprocalRankFusion.FusedChunk> chunks,
            List<LearnedRuleDto> appliedRules
    ) {
    }

    /**
     * Tương thích ngược: retrieve chỉ trả về danh sách FusedChunk.
     */
    @Transactional(readOnly = true)
    public List<ReciprocalRankFusion.FusedChunk> retrieve(List<UUID> documentIds, String question, Integer topK) {
        return retrieveWithRules(documentIds, question, topK, List.of()).chunks();
    }

    /**
     * Truy xuất tài liệu kèm theo tìm kiếm và áp dụng các quy tắc liên quan từ AI Playbook.
     */
    @Transactional(readOnly = true)
    public RetrievalResult retrieveWithRules(List<UUID> documentIds,
                                             String question,
                                             Integer topK,
                                             List<LearnedRuleDto> extraRules) {
        return retrieveWithRules(documentIds, question, topK, extraRules, null);
    }

    @Transactional(readOnly = true)
    public RetrievalResult retrieveWithRules(List<UUID> documentIds,
                                             String question,
                                             Integer topK,
                                             List<LearnedRuleDto> extraRules,
                                             UUID pageId) {
        int candidateK = chatProps.getCandidateK();
        int finalK = topK != null ? topK : chatProps.getTopK();

        float[] queryEmbedding = embeddingClient.embed(question);

        // 1. Tìm các quy tắc AI Playbook đang kích hoạt phù hợp với câu hỏi
        Map<UUID, LearnedRuleDto> matchedRulesMap = new LinkedHashMap<>();

        // Thêm extraRules trước (nếu có, ví dụ rule vừa trích xuất từ conversational correction)
        if (extraRules != null) {
            for (LearnedRuleDto er : extraRules) {
                if (er != null && er.id() != null) {
                    matchedRulesMap.put(er.id(), er);
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

            // Vector search trên learned_rules (nếu có pageId thì chỉ lấy rule của page đó + global rules)
            if (queryEmbedding != null) {
                List<LearnedRuleEntity> similarRules = pageId != null
                        ? learnedRuleRepository.searchSimilarActiveRules(queryEmbedding, 0.60, 5, pageId)
                        : learnedRuleRepository.searchSimilarActiveRules(queryEmbedding, 0.60, 5);
                for (LearnedRuleEntity r : similarRules) {
                    if (isPhoneLeadSpecificRule(r) && !mentionsPhoneTopicWithoutPrice) {
                        continue;
                    }
                    matchedRulesMap.putIfAbsent(r.getId(), LearnedRuleDto.fromEntity(r));
                }
            }

            // Keyword match trên active rules (scoped theo pageId nếu có)
            String lowerQuestion = rawQuestionLower
                    + ((hasPhoneNumber && !asksExplicitPrice) ? " số điện thoại sđt liên hệ tư vấn" : "");
            List<LearnedRuleEntity> activeRules = pageId != null
                    ? learnedRuleRepository.findActiveForPage(pageId)
                    : learnedRuleRepository.findByActiveTrue();
            for (LearnedRuleEntity r : activeRules) {
                if (isPhoneLeadSpecificRule(r) && !mentionsPhoneTopicWithoutPrice) {
                    continue;
                }
                boolean matched = false;
                if (r.getRetrievalKeywords() != null) {
                    for (String kw : r.getRetrievalKeywords()) {
                        if (kw != null && !kw.isBlank()) {
                            String normKw = kw.toLowerCase().strip();
                            if (isGenericNoiseKeyword(normKw)) {
                                continue;
                            }
                            if (lowerQuestion.contains(normKw)) {
                                matched = true;
                                break;
                            }
                        }
                    }
                }
                if (!matched && hasPhoneNumber && !asksExplicitPrice && isPhoneLeadSpecificRule(r)) {
                    matched = true;
                }
                if (matched) {
                    matchedRulesMap.putIfAbsent(r.getId(), LearnedRuleDto.fromEntity(r));
                }
            }
        } catch (Exception e) {
            log.warn("Không thể tìm kiếm quy tắc Playbook: {}", e.getMessage());
        }

        List<LearnedRuleDto> appliedRules = deduplicateRulesByText(matchedRulesMap.values());

        // 2. Thu thập keywords để mở rộng truy vấn full-text (bỏ qua từ khóa nhiễu chung chung)
        Set<String> keywordsToExpand = new LinkedHashSet<>();
        for (LearnedRuleDto rule : appliedRules) {
            if (rule.retrievalKeywords() != null) {
                for (String kw : rule.retrievalKeywords()) {
                    if (kw != null && !kw.isBlank()) {
                        String trimmedKw = kw.strip();
                        if (!isGenericNoiseKeyword(trimmedKw.toLowerCase())) {
                            keywordsToExpand.add(trimmedKw);
                        }
                    }
                }
            }
        }

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

        // Fuse bằng RRF.
        List<ReciprocalRankFusion.FusedChunk> fused = new ArrayList<>(
                ReciprocalRankFusion.fuse(List.of(vectorHits, textHits), chatProps.getRrfK())
        );

        // Ưu tiên bảng danh mục dịch vụ / bảng giá / cột Đối tượng phù hợp và hạ thấp mục 1 (thông tin nội bộ Page)
        prioritizeCatalogAndPriceTableChunks(fused, question);

        // Đa dạng hóa kết quả theo Độ phủ Từ khóa Câu hỏi (Query-Coverage MMR - học hỏi từ PR #1)
        List<ReciprocalRankFusion.FusedChunk> top = diversifyByQueryCoverage(fused, question, finalK);

        log.info("stage=rerank fused_total={} returned={} rrf_k={}",
                fused.size(), top.size(), chatProps.getRrfK());

        return new RetrievalResult(top, appliedRules);
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

    private static void prioritizeCatalogAndPriceTableChunks(List<ReciprocalRankFusion.FusedChunk> fused, String question) {
        if (fused == null || fused.size() <= 1 || question == null) {
            return;
        }
        String q = question.toLowerCase();
        boolean isCatalogOrSuitabilityQuery = q.contains("dịch vụ") || q.contains("sản phẩm") || q.contains("chi phí")
                || q.contains("giá") || q.contains("bao nhiêu") || q.contains("gói") || q.contains("liệu trình")
                || q.contains("phù hợp") || q.contains("tuổi") || q.contains("trung niên") || q.contains("nam")
                || q.contains("dùng được");
        fused.sort((a, b) -> {
            int priorityA = catalogChunkPriority(a.chunk(), isCatalogOrSuitabilityQuery);
            int priorityB = catalogChunkPriority(b.chunk(), isCatalogOrSuitabilityQuery);
            if (priorityA != priorityB) {
                return Integer.compare(priorityB, priorityA);
            }
            return Double.compare(b.score(), a.score());
        });
    }

    private static int catalogChunkPriority(RetrievedChunk c, boolean boostCatalogTables) {
        if (c == null || c.text() == null) {
            return 0;
        }
        String text = c.text().toLowerCase();
        String headings = c.headingPath() != null ? String.join(" ", c.headingPath()).toLowerCase() : "";
        // Luôn đẩy mục "1. THÔNG TIN CHUNG VỀ PAGE & TỆP KHÁCH HÀNG" xuống cuối để tránh LLM đọc nhầm mô tả nội bộ
        if (headings.contains("tệp khách hàng") || headings.contains("thông tin chung về page")) {
            return -1;
        }
        if (!boostCatalogTables) {
            return 0;
        }
        boolean hasTable = text.contains("|") && text.contains("---");
        if (hasTable && (headings.contains("bảng giá") || headings.contains("danh mục") || text.contains("đơn giá"))) {
            return 2;
        }
        if (hasTable && (headings.contains("chiết khấu") || headings.contains("combo"))) {
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
        if (queryTokens.isEmpty()) {
            return fused.subList(0, finalK);
        }

        List<ReciprocalRankFusion.FusedChunk> remaining = new ArrayList<>(fused);
        List<ReciprocalRankFusion.FusedChunk> selected = new ArrayList<>(finalK);
        Set<String> coveredTokens = new LinkedHashSet<>();

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
                int priorityBonus = catalogChunkPriority(cand.chunk(), true) * 10;
                // Giữ trật tự ưu tiên bảng giá ở đầu và thưởng nhẹ cho các chunk mở rộng độ phủ từ khóa câu hỏi
                double mmrScore = priorityBonus + (cand.score() * 100.0) + (uncoveredHits * 0.45) - (i * 0.05);
                if (mmrScore > bestScore) {
                    bestScore = mmrScore;
                    bestIdx = i;
                }
            }

            ReciprocalRankFusion.FusedChunk chosen = remaining.remove(bestIdx);
            selected.add(chosen);
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

    private static boolean isPhoneLeadSpecificRule(LearnedRuleEntity r) {
        if (r == null) {
            return false;
        }
        String combined = ((r.getTriggerCondition() != null ? r.getTriggerCondition() : "") + " "
                + (r.getGeneralizedRule() != null ? r.getGeneralizedRule() : "")).toLowerCase();
        return combined.contains("số điện thoại") || combined.contains("sđt");
    }

    private static boolean isGenericNoiseKeyword(String normKw) {
        if (normKw == null || normKw.length() <= 2) {
            return true;
        }
        return switch (normKw) {
            case "tư vấn", "liên hệ", "liên hệ tư vấn", "gói", "liệu", "trị", "quy", "tắc", "global", "rule" -> true;
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
