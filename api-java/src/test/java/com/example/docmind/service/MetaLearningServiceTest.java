package com.example.docmind.service;

import com.example.docmind.dto.LearnedRuleDto;
import com.example.docmind.entity.LearnedRuleEntity;
import com.example.docmind.repository.FeedbackLogRepository;
import com.example.docmind.repository.LearnedRuleRepository;
import com.example.docmind.service.embedding.EmbeddingClient;
import com.example.docmind.service.llm.LlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
}
