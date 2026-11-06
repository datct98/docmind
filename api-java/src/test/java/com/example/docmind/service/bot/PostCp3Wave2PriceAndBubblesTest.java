package com.example.docmind.service.bot;

import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.dto.bot.FanpageBotResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PostCp3Wave2PriceAndBubblesTest {

    private static List<PackageCatalogService.PackageSummary> nangSenCatalog() {
        return List.of(
                new PackageCatalogService.PackageSummary(
                        "NS00", "NS00", "ALL", "Lấy Ráy Tai Thư Giãn", "EAR",
                        List.of("tai"), 30, "30 phút", 129_000L, "129k",
                        "Phòng chung có rèm riêng", "lấy ráy tai thư giãn", "", "ALL", false),
                new PackageCatalogService.PackageSummary(
                        "NS01", "NS01", "ALL", "Gói Gội Đầu Dưỡng Sinh Cơ Bản", "HAIR",
                        List.of("đầu", "cổ", "vai", "gáy"), 60, "60 phút", 239_000L, "239k",
                        "Phòng chung có rèm riêng", "gội đầu thảo dược, massage cổ vai gáy", "", "ALL", false),
                new PackageCatalogService.PackageSummary(
                        "NS02", "NS02", "ALL", "Gói Chăm Sóc Chuyên Sâu Cổ Vai Gáy", "MASSAGE",
                        List.of("cổ", "vai", "gáy"), 75, "75 phút", 399_000L, "399k",
                        "Phòng chung có rèm riêng", "massage cổ vai gáy chuyên sâu", "", "ALL", false),
                new PackageCatalogService.PackageSummary(
                        "NS03", "NS03", "ALL", "Gói Trị Liệu Toàn Thân Chuyên Sâu", "MASSAGE",
                        List.of("toàn thân", "cổ", "vai", "gáy", "lưng", "chân"), 90, "90 phút", 670_000L, "670k",
                        "Phòng chung có rèm riêng", "massage toàn thân chuyên sâu", "", "ALL", false)
        );
    }

    @Test
    @DisplayName("Wave 2 (2.1): Khi khách chỉ hỏi 'Giá' (GENERAL_PRICE_INQUIRY), cung cấp giá khởi điểm thực tế từ Catalog, gói đại diện và hỏi nhẹ nhu cầu thư giãn/vùng chăm sóc (không ép chọn gói)")
    void testGeneralPriceInquiryProvidesStartingPriceAndAsksNeedInsteadOfPackageSelection() {
        List<PackageCatalogService.PackageSummary> catalog = nangSenCatalog();

        // 1. Dynamic starting package anchor resolves lowest core full-duration package (239k) without hardcoding
        PackageCatalogService.PackageSummary minCore =
                CleanAgentCatalogAndPromptBuilder.resolveLowestCoreStartingPackage(catalog, List.of());
        assertThat(minCore).isNotNull();
        assertThat(minCore.priceDisplay()).isEqualTo("239k");
        String anchorText = CleanAgentCatalogAndPromptBuilder.resolveLowestCoreStartingPackageAnchor(catalog, List.of());
        assertThat(anchorText).contains("239k").contains("Gói Gội Đầu Dưỡng Sinh Cơ Bản");

        // 2. Planner directive for "Giá" instructs starting price + representative options + need/scope question (forbids asking customer to pick a specific package)
        AdaptiveConversationIntelligence.LightweightCustomerUnderstandingPlan plan =
                AdaptiveConversationIntelligence.buildLightweightCustomerUnderstandingPlan(
                        "Giá", List.of(), null, ConversationState.StructuredBookingState.none());
        assertThat(plan.actionExecutionDirective())
                .contains("PRICE_INTENT_A (GENERAL_PRICE_INQUIRY)")
                .contains("starting_package_anchor")
                .contains("lựa chọn tiêu biểu")
                .contains("TUYỆT ĐỐI KHÔNG yêu cầu khách chọn gói cụ thể khi chưa hiểu nhu cầu");

        // 3. Stage 2 Architecture Guardrail: enforceP37B2TurnActionAndBubbleIdeaDisciplineInPlace preserves LLM's own bubble structure and wording without prepending starting-price templates or overwriting questions
        List<FanpageBotResponse.ReplyBubble> rawSingleBubble = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Dạ bên em hiện có các gói từ 239k (Gói Gội Đầu Dưỡng Sinh Cơ Bản 60 phút), 399k đến 670k ạ. Hiện mình đang muốn chăm sóc vùng nào nhất ạ?",
                        0,
                        "FACT_OR_PRICE_QUOTE"
                )
        );
        List<FanpageBotResponse.ReplyBubble> finalBubbles =
                CleanAgentOutputRefereeAndSanitizer.enforceP37B2TurnActionAndBubbleIdeaDisciplineInPlace(
                        rawSingleBubble,
                        "Giá",
                        List.of(),
                        ConversationState.StructuredBookingState.none(),
                        catalog,
                        "em",
                        "mình",
                        "kỹ thuật viên",
                        plan
                );

        assertThat(finalBubbles).hasSize(1);
        assertThat(finalBubbles.get(0).purpose()).isEqualTo("FACT_OR_PRICE_QUOTE");
        assertThat(finalBubbles.get(0).text())
                .isEqualTo("Dạ bên em hiện có các gói từ 239k (Gói Gội Đầu Dưỡng Sinh Cơ Bản 60 phút), 399k đến 670k ạ. Hiện mình đang muốn chăm sóc vùng nào nhất ạ?");
    }

    @Test
    @DisplayName("Wave 2 (2.2): Chia reply_bubbles theo đơn vị ý nghĩa — đồng bộ Prompt, Schema, Parser & Semantic Organizer (không cắt câu, không lặp chào/đồng cảm, không ép 1 ý ngắn thành 2 bubble)")
    void testIdeaBasedBubblesSemanticOrganization() {
        // 1. Prompt & JSON Schema synchronization check
        String promptTemplate = CleanAgentCatalogAndPromptBuilder.loadValidatedAciSystemPromptTemplate();
        assertThat(promptTemplate)
                .contains("Chia theo ĐƠN VỊ Ý NGHĨA: 1 ý hoàn chỉnh = 1 bubble; 2 ý độc lập")
                .contains("\"bubbles\":[\"Ý hoàn chỉnh 1\",\"Ý hoàn chỉnh 2 (nếu có câu hỏi/ý độc lập thứ 2)\"]")
                .doesNotContain("0 khoảng giá min–max")
                .doesNotContain("1 câu hỏi nhẹ khách ưu tiên gói nào");

        // 2. Two independent semantic ideas in a medium-length reply (42 words, < old 75-word threshold) are cleanly split into 2 bubbles at sentence boundary
        List<FanpageBotResponse.ReplyBubble> twoIdeasInOneBubble = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Dạ các liệu trình bên em tập trung chăm sóc thư giãn cơ thể bằng thảo dược, không thay thế thăm khám hay điều trị y khoa ạ. Hiện mình đang muốn ưu tiên chăm sóc thư giãn vùng cơ thể nào nhất ạ?",
                        0
                )
        );
        List<FanpageBotResponse.ReplyBubble> splitResult =
                CleanAgentOutputRefereeAndSanitizer.organizeBubblesBySemanticIdeaUnits(twoIdeasInOneBubble, false);
        assertThat(splitResult).hasSize(2);
        assertThat(splitResult.get(0).text())
                .isEqualTo("Dạ các liệu trình bên em tập trung chăm sóc thư giãn cơ thể bằng thảo dược, không thay thế thăm khám hay điều trị y khoa ạ.");
        assertThat(splitResult.get(1).text())
                .isEqualTo("Hiện mình đang muốn ưu tiên chăm sóc thư giãn vùng cơ thể nào nhất ạ?");

        // 3. Single short idea (pause acknowledgement / simple apology) stays 1 bubble (not artificially split)
        List<FanpageBotResponse.ReplyBubble> singleIdea = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Dạ vâng ạ, mình cứ thong thả tham khảo thêm nhé, khi nào cần hỗ trợ em luôn sẵn sàng ạ.",
                        0
                )
        );
        assertThat(CleanAgentOutputRefereeAndSanitizer.organizeBubblesBySemanticIdeaUnits(singleIdea, false)).hasSize(1);
        assertThat(CleanAgentOutputRefereeAndSanitizer.organizeBubblesBySemanticIdeaUnits(twoIdeasInOneBubble, true)).hasSize(1);

        // 4. Duplicate greeting / empathy prefix in Bubble 2 is stripped cleanly
        List<FanpageBotResponse.ReplyBubble> duplicateGreetingBubbles = List.of(
                new FanpageBotResponse.ReplyBubble(
                        "Dạ chào mình, bên em có các gói gội đầu dưỡng sinh khởi điểm từ 239k và massage chuyên sâu 399k ạ.",
                        0
                ),
                new FanpageBotResponse.ReplyBubble(
                        "Dạ chào mình, hiện mình đang muốn thư giãn vùng cơ thể nào để em tư vấn kỹ hơn ạ?",
                        2000
                )
        );
        List<FanpageBotResponse.ReplyBubble> deduped =
                CleanAgentOutputRefereeAndSanitizer.organizeBubblesBySemanticIdeaUnits(duplicateGreetingBubbles, false);
        assertThat(deduped).hasSize(2);
        assertThat(deduped.get(1).text())
                .startsWith("Hiện mình đang muốn thư giãn vùng cơ thể nào")
                .doesNotContainIgnoringCase("chào mình");
    }
}
