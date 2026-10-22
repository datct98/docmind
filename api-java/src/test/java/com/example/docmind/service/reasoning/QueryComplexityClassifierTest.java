package com.example.docmind.service.reasoning;

import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.service.sales.PriceLadderTracker.PriceLadderResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueryComplexityClassifierTest {

    private QueryComplexityClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new QueryComplexityClassifier();
    }

    // =========================================================================
    // 1. SIMPLE QUERIES
    // =========================================================================

    @ParameterizedTest
    @ValueSource(strings = {
            "Chào shop",
            "hello em",
            "dạ chào bạn",
            "alo ad ơi",
            "hi shop"
    })
    @DisplayName("Greetings classify as SIMPLE (4-8s target)")
    void testGreetingsClassifyAsSimple(String message) {
        assertThat(classifier.classify(message)).isEqualTo(QueryComplexity.SIMPLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Mấy giờ mở cửa ạ",
            "giờ mở cửa của shop",
            "bên mình làm việc đến mấy giờ",
            "có làm trưa không shop",
            "cuối tuần có mở cửa không"
    })
    @DisplayName("Operating hours classify as SIMPLE (4-8s target)")
    void testOperatingHoursClassifyAsSimple(String message) {
        assertThat(classifier.classify(message)).isEqualTo(QueryComplexity.SIMPLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Địa chỉ cơ sở ở đâu ạ",
            "chi nhánh bên mình ở đâu",
            "cho xin số hotline",
            "cơ sở Quận 1 Sài Gòn có gói nào?"
    })
    @DisplayName("Branch location and direct branch catalog classify as SIMPLE (4-8s target)")
    void testLocationAndBranchClassifyAsSimple(String message) {
        assertThat(classifier.classify(message)).isEqualTo(QueryComplexity.SIMPLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "giá",
            "bảng giá sao shop",
            "giá bn ạ",
            "chi phí massage thế nào"
    })
    @DisplayName("Broad price anchor inquiries without objections classify as SIMPLE (4-8s target)")
    void testBroadPriceClassifyAsSimple(String message) {
        assertThat(classifier.classify(message)).isEqualTo(QueryComplexity.SIMPLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "tôi muosn thử trải nghiệm trước",
            "muốn thử xem sao",
            "đặt lịch 9h sáng mai",
            "0968221644 tư vấn mình thêm"
    })
    @DisplayName("Trial requests and phone bookings without objections classify as SIMPLE (4-8s target)")
    void testTrialAndBookingClassifyAsSimple(String message) {
        assertThat(classifier.classify(message)).isEqualTo(QueryComplexity.SIMPLE);
    }

    // =========================================================================
    // 2. DEEP REASONING QUERIES
    // =========================================================================

    @ParameterizedTest
    @ValueSource(strings = {
            "gói 670k và 780k khác gì nhau?",
            "so sánh gói NS01 và NS02",
            "sao hai gói này chênh giá vậy shop",
            "nên chọn gói 399k hay 530k",
            "gói nào tốt hơn vậy em"
    })
    @DisplayName("Package comparisons classify as DEEP_REASONING (15-30s target)")
    void testPackageComparisonsClassifyAsDeepReasoning(String message) {
        assertThat(classifier.classify(message)).isEqualTo(QueryComplexity.DEEP_REASONING);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "cao thế",
            "nhưng hơi đắt",
            "có gói nào rẻ hơn không",
            "sao đắt vậy",
            "giá hơi chát shop ơi",
            "bớt được không shop",
            "tiết kiệm hơn được không",
            "vượt ngân sách của mình rồi"
    })
    @DisplayName("Down-sell price objections classify as DEEP_REASONING (15-30s target)")
    void testPriceObjectionsClassifyAsDeepReasoning(String message) {
        assertThat(classifier.classify(message)).isEqualTo(QueryComplexity.DEEP_REASONING);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "mình vừa mụn vừa thâm",
            "mụn ẩn kèm sẹo rỗ lâu năm",
            "bị đau vai gáy và mất ngủ kéo dài",
            "vừa muốn massage body vừa muốn gội đầu dưỡng sinh",
            "da dầu mụn viêm kèm tàn nhang"
    })
    @DisplayName("Multi-symptom consultations classify as DEEP_REASONING (15-30s target)")
    void testMultiSymptomClassifyAsDeepReasoning(String message) {
        assertThat(classifier.classify(message)).isEqualTo(QueryComplexity.DEEP_REASONING);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "mua 5 buổi có được giảm 10% không em",
            "combo 5 voucher thì tổng hết bao nhiêu và chiết khấu thế nào?"
    })
    @DisplayName("Conditional promo calculations classify as DEEP_REASONING (15-30s target)")
    void testConditionalPromoClassifyAsDeepReasoning(String message) {
        assertThat(classifier.classify(message)).isEqualTo(QueryComplexity.DEEP_REASONING);
    }

    // =========================================================================
    // 3. WITH PRICELADDER RESULT
    // =========================================================================

    @Test
    @DisplayName("Active PriceLadder price objection overrides to DEEP_REASONING")
    void testPriceLadderObjectionOverridesToDeepReasoning() {
        PriceLadderResult ladderResult = new PriceLadderResult(
                true, false, 399000L, 239000L, "Dưỡng sinh cơ bản", null, false, "Objection handled"
        );
        QueryComplexity complexity = classifier.classify("xem sao", List.of(), ladderResult);
        assertThat(complexity).isEqualTo(QueryComplexity.DEEP_REASONING);
    }

    @Test
    @DisplayName("Active PriceLadder catalog floor overrides to DEEP_REASONING")
    void testPriceLadderCatalogFloorOverridesToDeepReasoning() {
        PriceLadderResult ladderResult = new PriceLadderResult(
                false, false, 129000L, 129000L, "Lấy ráy tai cơ bản", "Giảm 10%", true, "Catalog floor reached"
        );
        QueryComplexity complexity = classifier.classify("còn gói nào nữa không", List.of(), ladderResult);
        assertThat(complexity).isEqualTo(QueryComplexity.DEEP_REASONING);
    }

    // =========================================================================
    // 4. BOUNDARY & CORNER CASES
    // =========================================================================

    @Test
    @DisplayName("Null or empty message safely defaults to SIMPLE")
    void testNullOrEmptyDefaultsToSimple() {
        assertThat(classifier.classify(null)).isEqualTo(QueryComplexity.SIMPLE);
        assertThat(classifier.classify("   ")).isEqualTo(QueryComplexity.SIMPLE);
    }

    @Test
    @DisplayName("ReasoningBudget returns matching parameters for complexity tiers")
    void testReasoningBudgetParameters() {
        com.example.docmind.service.llm.ReasoningBudget simple = com.example.docmind.service.llm.ReasoningBudget.simple();
        assertThat(simple.complexity()).isEqualTo(QueryComplexity.SIMPLE);
        assertThat(simple.reasoningEffort()).isEqualTo("low");
        assertThat(simple.targetMinLatencyMs()).isEqualTo(4000);
        assertThat(simple.targetMaxLatencyMs()).isEqualTo(8000);
        assertThat(simple.maxTokens()).isEqualTo(600);
        assertThat(simple.temperature()).isEqualTo(0.1);

        com.example.docmind.service.llm.ReasoningBudget deep = com.example.docmind.service.llm.ReasoningBudget.deepReasoning();
        assertThat(deep.complexity()).isEqualTo(QueryComplexity.DEEP_REASONING);
        assertThat(deep.reasoningEffort()).isEqualTo("medium");
        assertThat(deep.targetMinLatencyMs()).isEqualTo(15000);
        assertThat(deep.targetMaxLatencyMs()).isEqualTo(30000);
        assertThat(deep.maxTokens()).isEqualTo(2000);
        assertThat(deep.temperature()).isEqualTo(0.2);
    }
}
