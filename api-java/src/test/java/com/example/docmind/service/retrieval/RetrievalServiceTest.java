package com.example.docmind.service.retrieval;

import com.example.docmind.config.ChatProperties;
import com.example.docmind.dto.LearnedRuleDto;
import com.example.docmind.entity.LearnedRuleEntity;
import com.example.docmind.repository.ChunkRepository;
import com.example.docmind.repository.LearnedRuleRepository;
import com.example.docmind.repository.RetrievedChunk;
import com.example.docmind.service.embedding.EmbeddingClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

class RetrievalServiceTest {

    private ChunkRepository chunkRepository;
    private EmbeddingClient embeddingClient;
    private ChatProperties chatProps;
    private LearnedRuleRepository learnedRuleRepository;
    private RetrievalService retrievalService;

    @BeforeEach
    void setUp() {
        chunkRepository = mock(ChunkRepository.class);
        embeddingClient = mock(EmbeddingClient.class);
        chatProps = new ChatProperties();
        learnedRuleRepository = mock(LearnedRuleRepository.class);

        when(embeddingClient.embed(anyString())).thenReturn(new float[384]);

        retrievalService = new RetrievalService(chunkRepository, embeddingClient, chatProps, learnedRuleRepository);
    }

    @Test
    void retrieveWithRulesAugmentsFullTextQueryWithRuleKeywords() {
        UUID docId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        RetrievedChunk rc = new RetrievedChunk(
                chunkId, docId, List.of("Chính sách"), List.of(UUID.randomUUID()),
                1, new double[]{0, 0, 10, 10}, "Nội dung bảng chiết khấu theo số lượng", 0.95
        );

        when(chunkRepository.searchByVector(any(), any(), anyInt()))
                .thenReturn(List.of(rc));
        when(chunkRepository.searchByFullText(any(), anyString(), anyInt()))
                .thenReturn(List.of(rc));

        LearnedRuleEntity ruleEntity = LearnedRuleEntity.builder()
                .id(UUID.randomUUID())
                .category("PRICING_CALCULATION")
                .triggerCondition("Khi tính chiết khấu")
                .rootCause("Nhầm số")
                .generalizedRule("Đối soát bậc thang")
                .retrievalKeywords(new String[]{"chiết khấu", "bậc thang"})
                .active(true)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(learnedRuleRepository.searchSimilarActiveRules(any(), anyDouble(), anyInt()))
                .thenReturn(List.of(ruleEntity));
        when(learnedRuleRepository.findByActiveTrue())
                .thenReturn(List.of(ruleEntity));

        RetrievalService.RetrievalResult result =
                retrievalService.retrieveWithRules(List.of(docId), "Báo giá đơn hàng", 5, List.of());

        assertThat(result.chunks()).isNotEmpty();
        assertThat(result.appliedRules()).hasSize(1);
        assertThat(result.appliedRules().get(0).category()).isEqualTo("PRICING_CALCULATION");

        // Verify fulltext search nhận query đã mở rộng với keywords
        verify(chunkRepository).searchByFullText(
                eq(List.of(docId)),
                org.mockito.ArgumentMatchers.contains("chiết khấu"),
                anyInt()
        );
    }

    @Test
    void doesNotMatchPhoneSpecificRuleWhenQuestionHasNoPhoneNumber() {
        UUID docId = UUID.randomUUID();
        RetrievedChunk rc = new RetrievedChunk(
                UUID.randomUUID(), docId, List.of("Bảng giá"), List.of(UUID.randomUUID()),
                1, new double[]{0, 0, 10, 10}, "Liệu trình Trị Mụn 1.500.000 VNĐ", 0.95
        );
        when(chunkRepository.searchByVector(any(), any(), anyInt())).thenReturn(List.of(rc));
        when(chunkRepository.searchByFullText(any(), anyString(), anyInt())).thenReturn(List.of(rc));

        LearnedRuleEntity phoneRule = LearnedRuleEntity.builder()
                .id(UUID.randomUUID())
                .category("DATA_INTERPRETATION")
                .triggerCondition("Khi khách hàng gửi số điện thoại cần tư vấn")
                .rootCause("AI gửi bảng giá khi khách đã để lại SĐT")
                .generalizedRule("Khi khách gửi số điện thoại cần tư vấn, không liệt kê lại toàn bộ bảng giá dài dòng.")
                .retrievalKeywords(new String[]{"số điện thoại", "liên hệ", "tư vấn", "liên hệ tư vấn"})
                .active(true)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(learnedRuleRepository.searchSimilarActiveRules(any(), anyDouble(), anyInt()))
                .thenReturn(List.of(phoneRule));
        when(learnedRuleRepository.findByActiveTrue())
                .thenReturn(List.of(phoneRule));

        // 1. Khi khách chỉ hỏi "Tư vấn giúp dịch vụ và giá bên shop" (không có SĐT) -> KHÔNG được khớp nhầm phoneRule
        RetrievalService.RetrievalResult resNoPhone =
                retrievalService.retrieveWithRules(List.of(docId), "Tư vấn giúp dịch vụ và giá bên shop", 5, List.of());
        assertThat(resNoPhone.appliedRules()).isEmpty();

        // 2. Khi khách gửi SĐT thật "0982663255 tư vấn mình thêm" (không hỏi giá) -> BẮT BUỘC khớp phoneRule
        RetrievalService.RetrievalResult resWithPhone =
                retrievalService.retrieveWithRules(List.of(docId), "0982663255 tư vấn mình thêm", 5, List.of());
        assertThat(resWithPhone.appliedRules()).hasSize(1);

        // 3. Khi khách vừa gửi SĐT vừa hỏi giá trực tiếp -> KHÔNG được áp phoneRule cấm báo giá
        RetrievalService.RetrievalResult resPhoneAndPrice =
                retrievalService.retrieveWithRules(List.of(docId), "SĐT chị là 0912345678, báo giá giúp chị gói trị mụn nhé", 5, List.of());
        assertThat(resPhoneAndPrice.appliedRules()).isEmpty();
    }

    @Test
    void filtersOutLowConfidenceRulesRanksByEffectiveScoreAndCapsAtTop3WhileIncrementingAppliedCount() {
        UUID docId = UUID.randomUUID();
        RetrievedChunk rc = new RetrievedChunk(
                UUID.randomUUID(), docId, List.of("Bảng giá"), List.of(UUID.randomUUID()),
                1, new double[]{0, 0, 10, 10}, "Nội dung dịch vụ trị nám", 0.95
        );
        when(chunkRepository.searchByVector(any(), any(), anyInt())).thenReturn(List.of(rc));
        when(chunkRepository.searchByFullText(any(), anyString(), anyInt())).thenReturn(List.of(rc));

        LearnedRuleEntity quarantinedLowConf = LearnedRuleEntity.builder()
                .id(UUID.randomUUID())
                .category("PRICING")
                .triggerCondition("Khi báo giá trị nám")
                .generalizedRule("Quy tắc độ tin cậy thấp dưới ngưỡng 0.45")
                .retrievalKeywords(new String[]{"trị nám", "báo giá"})
                .active(true)
                .confidenceScore(0.35)
                .build();

        LearnedRuleEntity ruleHighConf = LearnedRuleEntity.builder()
                .id(UUID.randomUUID())
                .category("PRICING")
                .triggerCondition("Khi báo giá trị nám")
                .generalizedRule("Quy tắc 1 độ tin cậy rất cao 0.98")
                .retrievalKeywords(new String[]{"trị nám", "báo giá"})
                .active(true)
                .confidenceScore(0.98)
                .appliedCount(4)
                .build();

        LearnedRuleEntity ruleMedConf1 = LearnedRuleEntity.builder()
                .id(UUID.randomUUID())
                .category("POLICY")
                .triggerCondition("Khi báo giá trị nám")
                .generalizedRule("Quy tắc 2 độ tin cậy 0.88")
                .retrievalKeywords(new String[]{"trị nám"})
                .active(true)
                .confidenceScore(0.88)
                .build();

        LearnedRuleEntity ruleMedConf2 = LearnedRuleEntity.builder()
                .id(UUID.randomUUID())
                .category("DISCOUNT")
                .triggerCondition("Khi báo giá trị nám")
                .generalizedRule("Quy tắc 3 độ tin cậy 0.78")
                .retrievalKeywords(new String[]{"trị nám"})
                .active(true)
                .confidenceScore(0.78)
                .build();

        LearnedRuleEntity ruleLowerConf4th = LearnedRuleEntity.builder()
                .id(UUID.randomUUID())
                .category("GENERAL")
                .triggerCondition("Khi báo giá trị nám")
                .generalizedRule("Quy tắc 4 độ tin cậy 0.52 bị cắt bởi Top-3 budget")
                .retrievalKeywords(new String[]{"trị nám"})
                .active(true)
                .confidenceScore(0.52)
                .build();

        List<LearnedRuleEntity> pool = List.of(
                quarantinedLowConf, ruleLowerConf4th, ruleMedConf2, ruleHighConf, ruleMedConf1
        );
        when(learnedRuleRepository.searchSimilarActiveRules(any(), anyDouble(), anyInt()))
                .thenReturn(pool);
        when(learnedRuleRepository.findByActiveTrue())
                .thenReturn(pool);

        RetrievalService.RetrievalResult result =
                retrievalService.retrieveWithRules(List.of(docId), "Báo giá trị nám bên mình", 5, List.of());

        assertThat(result.appliedRules()).hasSize(3);
        assertThat(result.appliedRules())
                .extracting(LearnedRuleDto::id)
                .containsExactly(ruleHighConf.getId(), ruleMedConf1.getId(), ruleMedConf2.getId())
                .doesNotContain(quarantinedLowConf.getId(), ruleLowerConf4th.getId());

        verify(learnedRuleRepository).recordRulesApplied(
                List.of(ruleHighConf.getId(), ruleMedConf1.getId(), ruleMedConf2.getId())
        );
    }
}
