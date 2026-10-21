package com.example.docmind.service;

import com.example.docmind.dto.LearnedRuleDto;
import com.example.docmind.entity.LearnedRuleEntity;
import com.example.docmind.repository.FeedbackLogRepository;
import com.example.docmind.repository.LearnedRuleRepository;
import com.example.docmind.service.embedding.EmbeddingClient;
import com.example.docmind.service.llm.LlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test cho MetaLearningService:
 * - Trích xuất quy tắc (LLM và Heuristic fallback).
 * - Loại bỏ giá tiền và số liệu biến động (strip volatile facts).
 * - Nhận diện phản hồi sửa sai trong hội thoại (conversational correction detection).
 * - Khử trùng lặp và gộp quy tắc (deduplication & merge).
 */
class MetaLearningServiceTest {

    private LearnedRuleRepository ruleRepo;
    private FeedbackLogRepository feedbackRepo;
    private EmbeddingClient embeddingClient;
    private LlmClient llmClient;
    private ObjectMapper objectMapper;
    private MetaLearningService service;

    @BeforeEach
    void setUp() {
        ruleRepo = mock(LearnedRuleRepository.class);
        feedbackRepo = mock(FeedbackLogRepository.class);
        embeddingClient = mock(EmbeddingClient.class);
        llmClient = mock(LlmClient.class);
        objectMapper = new ObjectMapper();

        when(llmClient.name()).thenReturn("stub-llm");
        when(embeddingClient.embed(anyString())).thenReturn(new float[384]);

        service = new MetaLearningService(ruleRepo, feedbackRepo, embeddingClient, llmClient, objectMapper);
    }

    @Test
    void heuristicFallbackExtractsPricingRuleAndStripsVolatilePriceWhilePreservingPercentages() {
        String question = "Đơn hàng 500 cái được chiết khấu bao nhiêu %?";
        String faultyAnswer = "Được chiết khấu 5% theo chính sách chung.";
        String userCorrection = "Sai rồi, đơn hàng trên 200 cái được chiết khấu 15% và giảm thêm 500.000đ!";

        MetaLearningService.ExtractedRule extracted =
                service.extractRuleHeuristic(question, faultyAnswer, userCorrection, null);

        assertThat(extracted.category()).isEqualTo("PRICING_CALCULATION");
        assertThat(extracted.triggerCondition()).contains("chiết khấu");
        // Kiểm tra generalized rule loại bỏ giá tiền biến động (500.000) nhưng BẢO TOÀN phần trăm (15%) và ngưỡng số lượng (200 cái)
        assertThat(extracted.generalizedRule()).doesNotContain("500.000");
        assertThat(extracted.generalizedRule()).contains("15%");
        assertThat(extracted.generalizedRule()).contains("200 cái");
        assertThat(extracted.retrievalKeywords()).contains("chiết");
    }

    @Test
    void heuristicFallbackWhenCorrectionBlankUsesCategoryTemplate() {
        String question = "Đơn hàng được chiết khấu bao nhiêu %?";
        String faultyAnswer = "5%";

        MetaLearningService.ExtractedRule extracted =
                service.extractRuleHeuristic(question, faultyAnswer, "", null);

        assertThat(extracted.category()).isEqualTo("PRICING_CALCULATION");
        assertThat(extracted.generalizedRule()).contains("bậc thang");
    }

    @Test
    void heuristicFallbackExtractsTableLookupRuleFaithfully() {
        String question = "Xem cột số lượng trong bảng báo giá giúp tôi";
        String faultyAnswer = "Không thấy cột này.";
        String userCorrection = "Cột này nằm ở dòng thứ 3 của bảng!";

        MetaLearningService.ExtractedRule extracted =
                service.extractRuleHeuristic(question, faultyAnswer, userCorrection, null);

        assertThat(extracted.category()).isEqualTo("TABLE_LOOKUP");
        assertThat(extracted.generalizedRule()).contains("dòng thứ 3");
    }

    @Test
    void faithfulHeuristicExtractionPreservesPronounHonorificDirectives() {
        String question = "Mìn là nam, không biết có thể dùng dịch vụ không?";
        String faultyAnswer = "Dạ em chào Chị ạ! Bên em chuyên phục vụ khách hàng nữ.";
        String userCorrection = "Không được xưng Chị khi khách là nam, 100% phải xưng Anh và khẳng định nam giới dùng được!";

        MetaLearningService.ExtractedRule extracted =
                service.extractRuleHeuristic(question, faultyAnswer, userCorrection, null);

        assertThat(extracted.category()).isEqualTo("POLICY");
        assertThat(extracted.generalizedRule())
                .contains("Không được xưng Chị khi khách là nam")
                .contains("100% phải xưng Anh")
                .contains("nam giới dùng được");
    }

    @Test
    void faithfulHeuristicExtractionPreservesComboAndPercentagesWithoutDigitStripping() {
        String question = "Bên mình có combo gì giảm giá không?";
        String faultyAnswer = "Combo 3-4 buổi là Combo Toàn Diện giảm 15%.";
        String userCorrection = "Nhầm rồi: Combo Tiết Kiệm (3 - 4 buổi) giảm 10%, Combo Toàn Diện (5 - 9 buổi) giảm 15%!";

        MetaLearningService.ExtractedRule extracted =
                service.extractRuleHeuristic(question, faultyAnswer, userCorrection, null);

        assertThat(extracted.category()).isEqualTo("PRICING_CALCULATION");
        // Xác minh TUYỆT ĐỐI không bị xóa sạch chữ số (\d+) như lỗi cũ
        assertThat(extracted.generalizedRule())
                .contains("Combo Tiết Kiệm")
                .contains("3 - 4 buổi")
                .contains("10%")
                .contains("Combo Toàn Diện")
                .contains("5 - 9 buổi")
                .contains("15%");
    }

    @Test
    void faithfulHeuristicExtractionPreservesMedicalBoundaries() {
        String question = "Gói trị mụn có chữa được sẹo rỗ không em?";
        String faultyAnswer = "Dạ có ạ, gói trị mụn y khoa chữa dứt điểm sẹo rỗ!";
        String userCorrection = "Sai rồi: Gói Trị Mụn Chuẩn Y Khoa không điều trị sẹo rỗ, tình trạng sẹo rỗ cần Bác sĩ soi da riêng!";

        MetaLearningService.ExtractedRule extracted =
                service.extractRuleHeuristic(question, faultyAnswer, userCorrection, null);

        assertThat(extracted.generalizedRule())
                .contains("Gói Trị Mụn Chuẩn Y Khoa không điều trị sẹo rỗ")
                .contains("Bác sĩ soi da riêng");
    }

    @Test
    void llmExtractionParsesValidJsonResponse() {
        when(llmClient.name()).thenReturn("gemini-2.5-flash");
        String jsonLlmResponse = """
                ```json
                {
                  "category": "TABLE_LOOKUP",
                  "trigger_condition": "Khi tra cứu bảng dữ liệu bán hàng",
                  "root_cause": "Đọc nhầm tiêu đề cột điều kiện",
                  "generalized_rule": "Luôn kiểm tra toàn bộ tiêu đề cột trước khi trích xuất số liệu 100",
                  "retrieval_keywords": ["bảng giá", "tiêu đề cột"]
                }
                ```
                """;
        when(llmClient.complete(anyString())).thenReturn(jsonLlmResponse);

        MetaLearningService.ExtractedRule extracted =
                service.extractRule("Câu hỏi?", "Sai", "Đính chính", null);

        assertThat(extracted.category()).isEqualTo("TABLE_LOOKUP");
        assertThat(extracted.triggerCondition()).isEqualTo("Khi tra cứu bảng dữ liệu bán hàng");
        assertThat(extracted.rootCause()).isEqualTo("Đọc nhầm tiêu đề cột điều kiện");
        // Con số 100 phải bị strip khỏi generalized_rule
        assertThat(extracted.generalizedRule()).doesNotContain("100");
        assertThat(extracted.retrievalKeywords()).containsExactly("bảng giá", "tiêu đề cột");
    }

    @Test
    void stripVolatileFactsRemovesPricesAndPercentages() {
        String raw = "Giá bán niêm yết là 1.250.000đ hoặc $50, được giảm 15% từ ngày 01/01/2026.";
        String stripped = MetaLearningService.stripVolatileFacts(raw);
        assertThat(stripped).doesNotContain("1.250.000");
        assertThat(stripped).doesNotContain("$50");
        assertThat(stripped).doesNotContain("15%");
        assertThat(stripped).doesNotContain("đ", "$", "%", "--", "//");
        assertThat(stripped).doesNotMatch(".*\\d.*");

        // Chuỗi kiểm thử tổng hợp: tiền tố tiền tệ, số nhiều nhóm, %, ngày ISO và ký hiệu treo
        String raw2 = "Giá niêm yết là 250.000đ hoặc $1,500.00, giảm 15% từ 2026-10-14";
        String stripped2 = MetaLearningService.stripVolatileFacts(raw2);
        assertThat(stripped2).doesNotContain("đ", "$", "%", "--", "//");
        assertThat(stripped2).doesNotMatch(".*\\d.*");
        assertThat(stripped2).isEqualTo("Giá niêm yết là hoặc giảm từ");

        // Kiểm thử số tiền lớn, tiền tệ VND/USD và dấu gạch nối/gạch chéo treo
        String raw3 = "Hạn dùng 14/10/2026 và áp dụng từ 2026-10-14 -- kết thúc // Giá: 250.000.000đ";
        String stripped3 = MetaLearningService.stripVolatileFacts(raw3);
        assertThat(stripped3).doesNotContain("đ", "$", "%", "--", "//");
        assertThat(stripped3).doesNotMatch(".*\\d.*");
        assertThat(stripped3).isEqualTo("Hạn dùng và áp dụng từ kết thúc Giá:");
    }

    @Test
    void detectsConversationalCorrectionCorrectly() {
        String prevAnswer = "Đơn giá là 10.000đ.";

        assertThat(service.isCorrectionMessage("Sai rồi bạn ơi, giá phải rẻ hơn", prevAnswer)).isTrue();
        assertThat(service.isCorrectionMessage("Không đúng, xem lại bảng đi", prevAnswer)).isTrue();
        assertThat(service.isCorrectionMessage("Bạn trả lời sai rồi", prevAnswer)).isTrue();
        assertThat(service.isCorrectionMessage("That is incorrect, please check again", prevAnswer)).isTrue();
        assertThat(service.isCorrectionMessage("Actually, it should be different", prevAnswer)).isTrue();

        // Vietnamese conversational corrections from dispatch & challenger
        assertThat(service.isCorrectionMessage("Bạn tính nhầm...", prevAnswer)).isTrue();
        assertThat(service.isCorrectionMessage("Bạn tính nhầm giá sản phẩm rồi", prevAnswer)).isTrue();
        assertThat(service.isCorrectionMessage("Sai giá rồi", prevAnswer)).isTrue();
        assertThat(service.isCorrectionMessage("Bạn tính sai", prevAnswer)).isTrue();
        assertThat(service.isCorrectionMessage("Tính nhầm rồi", prevAnswer)).isTrue();
        assertThat(service.isCorrectionMessage("Nhầm giá rồi", prevAnswer)).isTrue();
        assertThat(service.isCorrectionMessage("Đâu phải như vậy", prevAnswer)).isTrue();
        assertThat(service.isCorrectionMessage("Số liệu chưa chuẩn, kiểm tra lại", prevAnswer)).isTrue();
        assertThat(service.isCorrectionMessage("Xem lại bảng báo giá", prevAnswer)).isTrue();

        // English conversational corrections
        assertThat(service.isCorrectionMessage("You miscalculated", prevAnswer)).isTrue();
        assertThat(service.isCorrectionMessage("Please check again", prevAnswer)).isTrue();

        // Câu hỏi thông thường không phải correction
        assertThat(service.isCorrectionMessage("Cho tôi hỏi thêm về chính sách vận chuyển?", prevAnswer)).isFalse();
        assertThat(service.isCorrectionMessage("Laptop này có màu gì?", prevAnswer)).isFalse();
        assertThat(service.isCorrectionMessage("Bảng giá này áp dụng khi nào?", prevAnswer)).isFalse();

        // Khi không có prevAnswer
        assertThat(service.isCorrectionMessage("Sai rồi", null)).isFalse();
        assertThat(service.isCorrectionMessage("Sai rồi", "")).isFalse();
    }

    @Test
    void deduplicatesAndMergesWhenRuleIsSimilar() {
        UUID existingId = UUID.randomUUID();
        LearnedRuleEntity existing = LearnedRuleEntity.builder()
                .id(existingId)
                .category("PRICING_CALCULATION")
                .triggerCondition("Khi tính chiết khấu")
                .rootCause("Nhầm số")
                .generalizedRule("Quy tắc cũ")
                .retrievalKeywords(new String[]{"chiết khấu", "giá"})
                .active(true)
                .createdAt(Instant.now().minusSeconds(3600))
                .updatedAt(Instant.now().minusSeconds(3600))
                .build();

        when(ruleRepo.searchSimilarActiveRules(any(), anyDouble(), anyInt()))
                .thenReturn(List.of(existing));

        LearnedRuleDto result = service.learnFromFeedback(
                "Đơn hàng 500 cái chiết khấu bao nhiêu?",
                "5%",
                "Sai rồi, trên 500 cái phải là 20% giảm giá đơn hàng!",
                "conv-1",
                null
        );

        assertThat(result.id()).isEqualTo(existingId);
        // Kiểm tra keywords được merge
        assertThat(result.retrievalKeywords()).contains("chiết khấu", "giá");
        verify(ruleRepo).updateRuleWithEmbedding(any(), any(), any(), any(), any(), any(), any(Boolean.class), any());
        verify(feedbackRepo).save(any());
    }

    @Test
    void preservesVietnameseLetterDAndQuotedResponseTemplateWhenTeachingPhoneRule() {
        when(llmClient.name()).thenReturn("qwen2.5:7b");
        String llmJson = """
                {
                  "category": "DATA_INTERPRETATION",
                  "trigger_condition": "AI cung cấp thông tin không cần thiết khi khách hàng đã cung cấp số điện thoại cần tư vấn.",
                  "root_cause": "AI không nhận diện được rằng khách hàng đã cung cấp số điện thoại và chỉ cần thông tin liên quan đến việc tư vấn tiếp theo.",
                  "generalized_rule": "Khi khách hàng cung cấp số điện thoại để được tư vấn, chỉ xác nhận đã nhận thông tin và thông báo sẽ liên hệ để tư vấn thêm, tránh gửi thông tin chi tiết về các gói dịch vụ hoặc chiết khấu không cần thiết.",
                  "retrieval_keywords": ["liên hệ tư vấn", "thông tin không cần thiết", "gói dịch vụ", "chiết khấu combo"]
                }
                """;
        when(llmClient.complete(anyString())).thenReturn(llmJson);

        String question = "0982663255 hãy liên hệ tư vấn tôi";
        String faultyAnswer = "Dạ em chào Chị 0982663255 ạ! Bên em hiện có gói dịch vụ và chiết khấu combo giảm 10%...";
        String userCorrection = "Khách đưa số điện thoại cần tư vấn mà lại gửi một đống thông tin không cần thiết. Thay vào đó trả lời lại khách ngay 'Dạ vâng em nhận thông tin của mình ạ, em sẽ liên hệ để tư vấn mình thêm nha anh/chị' và gửi đến nhân viên tư vấn";

        MetaLearningService.ExtractedRule extracted =
                service.extractRule(question, faultyAnswer, userCorrection, null);

        assertThat(extracted.triggerCondition()).contains("Khi khách hàng để lại số điện thoại");
        assertThat(extracted.generalizedRule())
                .contains("điện thoại")
                .contains("được")
                .contains("đã")
                .contains("Dạ vâng em nhận thông tin của mình ạ, em sẽ liên hệ để tư vấn mình thêm nha anh/chị")
                .doesNotContain(" ược")
                .doesNotContain(" ã nhận");
        assertThat(extracted.retrievalKeywords())
                .contains("số điện thoại", "liên hệ tư vấn")
                .doesNotContain("thông tin không cần thiết", "gói dịch vụ", "chiết khấu combo");
    }

    @Test
    void smartRuleEvolutionUpdatesGeneralizedRuleSavesPreviousTextIncrementsVersionAndCapsKeywordsAt8() {
        UUID existingId = UUID.randomUUID();
        UUID pageId = UUID.randomUUID();
        LearnedRuleEntity existing = LearnedRuleEntity.builder()
                .id(existingId)
                .category("PRICING_CALCULATION")
                .triggerCondition("Khi khách hỏi chiết khấu combo")
                .rootCause("Nhầm mức giảm")
                .generalizedRule("Quy tắc cũ: Combo 5 buổi giảm 10%.")
                .retrievalKeywords(new String[]{"kw1", "kw2", "kw3", "kw4", "kw5", "kw6", "kw7"})
                .pageId(pageId)
                .active(false)
                .confidenceScore(0.40)
                .version(1)
                .quarantinedReason("Tự động tạm ngắt")
                .createdAt(Instant.now().minusSeconds(3600))
                .updatedAt(Instant.now().minusSeconds(3600))
                .build();

        when(ruleRepo.searchSimilarActiveRules(any(), anyDouble(), anyInt(), any()))
                .thenReturn(List.of(existing));

        LearnedRuleDto evolved = service.learnFromFeedback(
                "Combo 5 buổi giảm bao nhiêu %?",
                "Dạ giảm 10% ạ.",
                "Sai rồi: Combo Toàn Diện (5 - 9 buổi) phải được giảm 15% trên tổng hóa đơn trước VAT!",
                "conv-evolve",
                null,
                pageId.toString()
        );

        assertThat(evolved.id()).isEqualTo(existingId);
        assertThat(evolved.version()).isEqualTo(2);
        assertThat(evolved.previousRuleText()).isEqualTo("Quy tắc cũ: Combo 5 buổi giảm 10%.");
        assertThat(evolved.generalizedRule()).contains("15%");
        assertThat(evolved.confidenceScore()).isEqualTo(0.90);
        assertThat(evolved.active()).isTrue();
        assertThat(evolved.quarantinedReason()).isNull();
        assertThat(evolved.retrievalKeywords().size()).isLessThanOrEqualTo(8);
    }

    @Test
    void selectiveBlameAttributionOnlyPenalizesMatchingRuleAndAutoQuarantinesRepeatedlyFailingRule() {
        UUID innocentRuleId = UUID.randomUUID();
        UUID faultyRuleId = UUID.randomUUID();

        LearnedRuleEntity innocentRule = LearnedRuleEntity.builder()
                .id(innocentRuleId)
                .category("POLICY")
                .triggerCondition("Khi xưng hô với khách nam")
                .generalizedRule("Luôn xưng Anh với khách nam.")
                .retrievalKeywords(new String[]{"khách nam", "xưng anh"})
                .active(true)
                .confidenceScore(0.90)
                .appliedCount(5)
                .successCount(2)
                .failCount(0)
                .version(1)
                .build();

        LearnedRuleEntity faultyRule = LearnedRuleEntity.builder()
                .id(faultyRuleId)
                .category("PRICING_CALCULATION")
                .triggerCondition("Khi tính chiết khấu combo")
                .generalizedRule("Áp dụng cộng dồn chiết khấu combo và voucher sinh nhật.")
                .retrievalKeywords(new String[]{"chiết khấu", "combo", "voucher sinh nhật"})
                .active(true)
                .confidenceScore(0.60)
                .appliedCount(3)
                .successCount(0)
                .failCount(1)
                .version(1)
                .build();

        when(ruleRepo.findById(innocentRuleId)).thenReturn(java.util.Optional.of(innocentRule));
        when(ruleRepo.findById(faultyRuleId)).thenReturn(java.util.Optional.of(faultyRule));

        service.recordNegativeFeedbackAndAutoQuarantine(
                List.of(innocentRuleId, faultyRuleId),
                "Combo 5 buổi có cộng dồn voucher sinh nhật không?",
                "Dạ được cộng dồn chiết khấu combo và voucher sinh nhật ạ.",
                "Sai rồi, chiết khấu combo không được cộng dồn với voucher sinh nhật!"
        );

        // Innocent rule must NOT be penalized
        assertThat(innocentRule.getFailCount()).isEqualTo(0);
        assertThat(innocentRule.getConfidenceScore()).isEqualTo(0.90);
        assertThat(innocentRule.isActive()).isTrue();

        // Faulty rule overlaps with feedback -> penalized (0.60 - 0.25 = 0.35 < 0.45, failCount = 2 > successCount = 0) -> auto-quarantined!
        assertThat(faultyRule.getFailCount()).isEqualTo(2);
        assertThat(faultyRule.getConfidenceScore()).isEqualTo(0.35);
        assertThat(faultyRule.isActive()).isFalse();
        assertThat(faultyRule.getQuarantinedReason()).contains("Tự động tạm ngắt");
    }

    @Test
    void rollbackRuleVersionRestoresPreviousRuleTextDecrementsVersionAndReEnablesQuarantinedRule() {
        UUID ruleId = UUID.randomUUID();
        LearnedRuleEntity entity = LearnedRuleEntity.builder()
                .id(ruleId)
                .category("POLICY")
                .triggerCondition("Khi báo giá")
                .rootCause("Báo sai")
                .generalizedRule("Quy tắc phiên bản 2 bị lỗi")
                .previousRuleText("Quy tắc phiên bản 1 chuẩn xác")
                .retrievalKeywords(new String[]{"bảng giá"})
                .active(false)
                .confidenceScore(0.30)
                .failCount(2)
                .version(2)
                .quarantinedReason("Tự động tạm ngắt do bị báo sai lặp lại")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(ruleRepo.findById(ruleId)).thenReturn(java.util.Optional.of(entity));

        LearnedRuleDto rolledBack = service.rollbackRuleVersion(ruleId);

        assertThat(rolledBack.generalizedRule()).isEqualTo("Quy tắc phiên bản 1 chuẩn xác");
        assertThat(rolledBack.previousRuleText()).isNull();
        assertThat(rolledBack.version()).isEqualTo(1);
        assertThat(rolledBack.active()).isTrue();
        assertThat(rolledBack.confidenceScore()).isEqualTo(0.85);
        assertThat(rolledBack.failCount()).isEqualTo(0);
        assertThat(rolledBack.quarantinedReason()).isNull();
    }

    @Test
    void smartRuleEvolutionFindsQuarantinedRuleViaTriggerFallbackWhenActiveVectorSearchReturnsEmpty() {
        UUID existingId = UUID.randomUUID();
        UUID pageId = UUID.randomUUID();
        LearnedRuleEntity quarantined = LearnedRuleEntity.builder()
                .id(existingId)
                .category("PRICING_CALCULATION")
                .triggerCondition("Khi khách hỏi chiết khấu đơn hàng trên 500 cái")
                .rootCause("Nhầm mức giảm")
                .generalizedRule("Đơn hàng trên 500 cái được chiết khấu 10%.")
                .retrievalKeywords(new String[]{"chiết khấu", "500 cái"})
                .pageId(pageId)
                .active(false)
                .confidenceScore(0.35)
                .failCount(2)
                .version(1)
                .quarantinedReason(MetaLearningService.AUTO_QUARANTINE_REASON)
                .createdAt(Instant.now().minusSeconds(3600))
                .updatedAt(Instant.now().minusSeconds(3600))
                .build();

        // Simulate real PostgreSQL WHERE active = true returning empty for quarantined rule
        when(ruleRepo.searchSimilarActiveRules(any(), anyDouble(), anyInt(), any()))
                .thenReturn(List.of());
        when(ruleRepo.searchSimilarRulesForEvolution(any(), anyDouble(), anyInt(), any()))
                .thenReturn(List.of());
        when(ruleRepo.searchRulesByPage(any(), any(), any(), any(), any(Boolean.class), any(Boolean.class)))
                .thenReturn(List.of(quarantined));

        LearnedRuleDto evolved = service.learnFromFeedback(
                "Chính sách chiết khấu đơn hàng trên 500 cái là bao nhiêu?",
                "Đơn hàng trên 500 cái được chiết khấu 10%.",
                "Đơn hàng trên 500 cái phải được chiết khấu 20% theo bảng giá mới!",
                "conv-quarantined-evolve",
                null,
                pageId.toString()
        );

        assertThat(evolved.id()).isEqualTo(existingId);
        assertThat(evolved.version()).isEqualTo(2);
        assertThat(evolved.previousRuleText()).isEqualTo("Đơn hàng trên 500 cái được chiết khấu 10%.");
        assertThat(evolved.generalizedRule()).contains("20%");
        assertThat(evolved.confidenceScore()).isEqualTo(0.90);
        assertThat(evolved.failCount()).isEqualTo(0);
        assertThat(evolved.active()).isTrue();
        assertThat(evolved.quarantinedReason()).isNull();
    }

    @Test
    void repeatedBlameQuarantinesRuleOnSecondFailureAndSubsequentReteachEvolvesAndReactivatesRule() {
        UUID ruleId = UUID.randomUUID();
        UUID pageId = UUID.randomUUID();
        LearnedRuleEntity rule = LearnedRuleEntity.builder()
                .id(ruleId)
                .category("PRICING_CALCULATION")
                .triggerCondition("Khi khách hỏi chiết khấu đơn hàng trên 500 cái")
                .rootCause("Nhầm mức giảm")
                .generalizedRule("Đơn hàng trên 500 cái được chiết khấu 10%.")
                .retrievalKeywords(new String[]{"chiết khấu", "500 cái"})
                .pageId(pageId)
                .active(true)
                .confidenceScore(0.60)
                .failCount(1)
                .successCount(0)
                .version(1)
                .createdAt(Instant.now().minusSeconds(3600))
                .updatedAt(Instant.now().minusSeconds(3600))
                .build();

        when(ruleRepo.findById(ruleId)).thenReturn(java.util.Optional.of(rule));
        when(ruleRepo.searchSimilarRulesForEvolution(any(), anyDouble(), anyInt(), any()))
                .thenReturn(List.of(rule));

        // 2nd failure with appliedRuleIds=[ruleId] -> triggers Auto-Quarantine Circuit Breaker
        service.recordNegativeFeedbackAndAutoQuarantine(
                List.of(ruleId),
                "Chính sách chiết khấu đơn hàng trên 500 cái là bao nhiêu?",
                "Đơn hàng trên 500 cái được chiết khấu 10%.",
                "Sai rồi, đơn hàng trên 500 cái phải được chiết khấu 15%!"
        );
        LearnedRuleDto afterSecondFail = service.learnFromFeedback(
                "Chính sách chiết khấu đơn hàng trên 500 cái là bao nhiêu?",
                "Đơn hàng trên 500 cái được chiết khấu 10%.",
                "Sai rồi, đơn hàng trên 500 cái phải được chiết khấu 15%!",
                "conv-q2",
                null,
                pageId.toString()
        );

        assertThat(afterSecondFail.id()).isEqualTo(ruleId);
        assertThat(afterSecondFail.active()).isFalse();
        assertThat(afterSecondFail.failCount()).isEqualTo(2);
        assertThat(afterSecondFail.confidenceScore()).isEqualTo(0.35);
        assertThat(afterSecondFail.quarantinedReason()).isEqualTo(MetaLearningService.AUTO_QUARANTINE_REASON);

        // Subsequent re-teach on the quarantined rule -> evolves to v2 and reactivates with confidence 0.90
        LearnedRuleDto afterReteach = service.learnFromFeedback(
                "Chính sách chiết khấu đơn hàng trên 500 cái là bao nhiêu?",
                "Đơn hàng trên 500 cái được chiết khấu 10%.",
                "Đơn hàng trên 500 cái được chiết khấu 20% theo quy định mới!",
                "conv-q3",
                null,
                pageId.toString()
        );

        assertThat(afterReteach.id()).isEqualTo(ruleId);
        assertThat(afterReteach.version()).isEqualTo(2);
        assertThat(afterReteach.previousRuleText()).isEqualTo("Đơn hàng trên 500 cái được chiết khấu 10%.");
        assertThat(afterReteach.active()).isTrue();
        assertThat(afterReteach.confidenceScore()).isEqualTo(0.90);
        assertThat(afterReteach.failCount()).isEqualTo(0);
        assertThat(afterReteach.quarantinedReason()).isNull();
    }

    @Test
    void ruleEvolutionStripsVolatileFactsAndPIIBeforeUpdating() {
        UUID pageId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        LearnedRuleEntity rule = LearnedRuleEntity.builder()
                .id(ruleId)
                .category("SALES_STRATEGY")
                .triggerCondition("Khách hỏi đặt lịch")
                .rootCause("Chưa lưu số điện thoại")
                .generalizedRule("Liên hệ khách hàng để tư vấn.")
                .retrievalKeywords(new String[]{"đặt lịch"})
                .pageId(pageId)
                .active(true)
                .version(1)
                .createdAt(Instant.now().minusSeconds(3600))
                .updatedAt(Instant.now().minusSeconds(3600))
                .build();

        when(ruleRepo.searchSimilarActiveRules(any(), anyDouble(), anyInt(), any()))
                .thenReturn(List.of(rule));

        service.learnFromFeedback(
                "Tư vấn cho tôi",
                "Chào bạn",
                "Khách để lại số 0968221644 và giá 1.500.000đ yêu cầu gọi lại",
                "conv-evo",
                null,
                pageId.toString()
        );

        ArgumentCaptor<String> genRuleCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> rootCauseCaptor = ArgumentCaptor.forClass(String.class);
        verify(ruleRepo).updateRuleWithEmbedding(
                eq(ruleId),
                anyString(),
                anyString(),
                rootCauseCaptor.capture(),
                genRuleCaptor.capture(),
                any(),
                eq(true),
                any(),
                eq(pageId)
        );

        assertThat(genRuleCaptor.getValue()).doesNotContain("0968221644");
        assertThat(genRuleCaptor.getValue()).doesNotContain("1.500.000");
        assertThat(rootCauseCaptor.getValue()).doesNotContain("0968221644");
        assertThat(rootCauseCaptor.getValue()).doesNotContain("1.500.000");
    }

    @Test
    void stripVolatileFactsPreservingVietnameseStripsPricesAndPhonesWhilePreservingPercentages() {
        String input = "Khách liên hệ 0968221644 hỏi giá 1.500.000 VNĐ, combo 5 buổi được giảm 15% hoặc 20% vào ngày 2026-10-20.";
        String stripped = MetaLearningService.stripVolatileFactsPreservingVietnamese(input);

        // Phone stripped
        assertThat(stripped).doesNotContain("0968221644");
        // Volatile price stripped
        assertThat(stripped).doesNotContain("1.500.000");
        // Date stripped
        assertThat(stripped).doesNotContain("2026-10-20");
        // Business percentages preserved
        assertThat(stripped).contains("15%");
        assertThat(stripped).contains("20%");
        // Quantity units preserved
        assertThat(stripped).contains("5 buổi");
    }

    @Test
    void ruleEvolutionStripsVolatileFactsAndPIIWhilePreservingPercentages() {
        UUID pageId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        LearnedRuleEntity rule = LearnedRuleEntity.builder()
                .id(ruleId)
                .category("PRICING_CALCULATION")
                .triggerCondition("Khách hỏi về giá dịch vụ chăm sóc")
                .rootCause("Nhầm giá")
                .generalizedRule("Kiểm tra chính sách giá hiện hành.")
                .retrievalKeywords(new String[]{"giá", "dịch vụ"})
                .pageId(pageId)
                .active(true)
                .version(1)
                .createdAt(Instant.now().minusSeconds(3600))
                .updatedAt(Instant.now().minusSeconds(3600))
                .build();

        when(ruleRepo.searchSimilarActiveRules(any(), anyDouble(), anyInt(), any()))
                .thenReturn(List.of(rule));

        service.learnFromFeedback(
                "Giá dịch vụ bao nhiêu?",
                "Giá là 1.500.000đ",
                "Sai rồi, gọi số 0912345678, giá cũ 1.500.000đ nhưng combo 5 buổi được giảm 20%!",
                "conv-evo-pct",
                null,
                pageId.toString()
        );

        ArgumentCaptor<String> genRuleCaptor = ArgumentCaptor.forClass(String.class);
        verify(ruleRepo).updateRuleWithEmbedding(
                eq(ruleId),
                anyString(),
                anyString(),
                anyString(),
                genRuleCaptor.capture(),
                any(),
                eq(true),
                any(),
                eq(pageId)
        );

        String evolvedGenRule = genRuleCaptor.getValue();
        assertThat(evolvedGenRule).doesNotContain("0912345678");
        assertThat(evolvedGenRule).doesNotContain("1.500.000");
        assertThat(evolvedGenRule).contains("20%");
        assertThat(evolvedGenRule).contains("5 buổi");
    }
}

