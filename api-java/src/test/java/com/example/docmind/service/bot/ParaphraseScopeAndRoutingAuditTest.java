package com.example.docmind.service.bot;

import com.example.docmind.dto.PagePersonaConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate 2 Mandatory Paraphrase &amp; Multi-Domain Routing Audit Suite (v3.6.7.1-MASTER):
 * Kiểm định 25 biến thể câu nói tự nhiên (12 câu Nhóm D đa ngành Spa/Nha khoa/Nội thất/BĐS,
 * 4 câu Nhóm H phương ngữ/đảo ngữ, 9 câu Frustration &amp; Hỏi giá/Khuyến mãi).
 */
class ParaphraseScopeAndRoutingAuditTest {

    private static final List<String> MULTI_DOMAIN_TAXONOMY = List.of(
            // Spa
            "đầu", "vai", "cổ", "gáy", "lưng", "chân", "bắp chân", "bàn chân", "đầu gối", "giấc ngủ",
            // Nha khoa
            "tẩy trắng răng", "nhổ răng", "bọc răng sứ", "cạo vôi răng", "lấy tủy răng", "niềng răng",
            // Nội thất / Xây dựng
            "lát nền", "ốp tường", "sàn gỗ", "sơn tường",
            // Bất động sản
            "căn 2pn", "biệt thự"
    );

    static Stream<Arguments> groupDExplicitCorrectionCases() {
        return Stream.of(
                // 1..6: Spa
                Arguments.of("Đau đầu chứ đầu gối đâu", "đầu", "đầu gối"),
                Arguments.of("Mỏi vai chứ có phải bắp chân đâu", "vai", "bắp chân"),
                Arguments.of("Đau chân mất ngủ mà đi massage vai làm gì", "chân", "vai"),
                Arguments.of("Không phải đau lưng, tôi bị nhức đầu và mỏi cổ", "đầu", "lưng"),
                Arguments.of("Làm gì có mỏi gối, chỉ đau cổ và vai thôi", "cổ", "đầu gối"),
                Arguments.of("Tư vấn nhầm sang đầu gối rồi, mình đau đầu cơ", "đầu", "đầu gối"),
                // 7..9: Nha khoa
                Arguments.of("Tẩy trắng răng chứ nhổ răng đâu", "tẩy trắng răng", "nhổ răng"),
                Arguments.of("Bọc răng sứ chứ có phải cạo vôi răng đâu", "bọc răng sứ", "cạo vôi răng"),
                Arguments.of("Không phải lấy tủy răng, tôi muốn niềng răng", "niềng răng", "lấy tủy răng"),
                // 10..11: Nội thất
                Arguments.of("Lát nền chứ ốp tường đâu", "lát nền", "ốp tường"),
                Arguments.of("Sàn gỗ chứ không phải sơn tường", "sàn gỗ", "sơn tường"),
                // 12: Bất động sản
                Arguments.of("Căn 2PN chứ biệt thự đâu", "căn 2pn", "biệt thự")
        );
    }

    @ParameterizedTest(name = "Group D [{index}]: \"{0}\" -> +{1}, -{2}, action=REPAIR_AND_RECONFIRM")
    @MethodSource("groupDExplicitCorrectionCases")
    @DisplayName("Gate 2A (12 biến thể Nhóm D Đa Ngành): Trích xuất đúng confirmed/forbidden & khóa REPAIR_AND_RECONFIRM")
    void testGroupDExplicitNegationAcrossDomains(String message, String expectedConfirmed, String expectedForbidden) {
        VietnameseScopeSignalExtractor.ScopeSignalResult sig =
                VietnameseScopeSignalExtractor.extract(message, MULTI_DOMAIN_TAXONOMY);

        assertTrue(sig.confirmedScopesToAdd().contains(expectedConfirmed),
                "confirmedScopesToAdd phải chứa '" + expectedConfirmed + "' cho câu: " + message + " (actual=" + sig.confirmedScopesToAdd() + ")");
        assertTrue(sig.forbiddenScopesToAdd().contains(expectedForbidden),
                "forbiddenScopesToAdd phải chứa '" + expectedForbidden + "' cho câu: " + message + " (actual=" + sig.forbiddenScopesToAdd() + ")");
        assertFalse(sig.confirmedScopesToAdd().contains(expectedForbidden),
                "confirmedScopesToAdd tuyệt đối không được chứa phạm vi bị phủ định '" + expectedForbidden + "'");
        assertEquals("REPAIR_AND_RECONFIRM", sig.forcedAction());

        ConversationState state = ConversationState.empty()
                .withTenantCareActor("chuyên viên", MULTI_DOMAIN_TAXONOMY)
                .enrichWithCurrentMessage(message, MULTI_DOMAIN_TAXONOMY);
        PrimaryActionRouter.RoutingDecision decision = PrimaryActionRouter.decide(
                state,
                message,
                false,
                List.of(),
                PagePersonaConfig.defaults()
        );
        assertEquals(PrimaryActionRouter.PrimaryAction.REPAIR_AND_RECONFIRM, decision.effectiveResponseAction(),
                "Router bắt buộc chọn REPAIR_AND_RECONFIRM cho câu đính chính Nhóm D: " + message);
    }

    static Stream<String> groupHDialectCases() {
        return Stream.of(
                "đầu chứ lị",
                "gối mô mà gối",
                "tao nói đầu, mày nghe gối à",
                "gối đâu, đầu á"
        );
    }

    @ParameterizedTest(name = "Group H [{index}]: \"{0}\" -> possibleCorrection=true, no auto-add to confirmedScopes, action=REPAIR_AND_RECONFIRM")
    @MethodSource("groupHDialectCases")
    @DisplayName("Gate 2B (4 biến thể Nhóm H Phương ngữ/Đảo ngữ): Bật possibleCorrection=true, chặn nạp nhầm từ khóa và cấm nhảy sang báo giá")
    void testGroupHColloquialAndDialectPushback(String message) {
        VietnameseScopeSignalExtractor.ScopeSignalResult sig =
                VietnameseScopeSignalExtractor.extract(message, MULTI_DOMAIN_TAXONOMY);

        assertTrue(sig.possibleCorrection(), "Nhóm H phải bật possibleCorrection = true cho câu: " + message);
        assertTrue(sig.confirmedScopesToAdd().isEmpty(),
                "Nhóm H nghiêm cấm Pre-Guard tự động nạp từ khóa vào confirmedScopes: " + message);

        ConversationState priorState = ConversationState.empty()
                .withTenantCareActor("kỹ thuật viên", MULTI_DOMAIN_TAXONOMY)
                .enrichWithCurrentMessage("Tôi bị đau đầu và mỏi vai", MULTI_DOMAIN_TAXONOMY);
        ConversationState enriched = priorState.enrichWithCurrentMessage(message, MULTI_DOMAIN_TAXONOMY);
        assertFalse(enriched.confirmedScopes().contains("đầu gối"),
                "Tuyệt đối không được nạp 'đầu gối' vào confirmedScopes ở câu Nhóm H: " + message);

        PrimaryActionRouter.RoutingDecision decision = PrimaryActionRouter.decide(
                enriched,
                message,
                false,
                List.of(),
                PagePersonaConfig.defaults()
        );
        assertEquals(PrimaryActionRouter.PrimaryAction.REPAIR_AND_RECONFIRM, decision.effectiveResponseAction(),
                "Câu Nhóm H phải định tuyến về REPAIR_AND_RECONFIRM, cấm nhảy sang ANSWER_PRICE_ONCE hay RECOMMEND_ONE_PACKAGE");
    }

    @Test
    @DisplayName("Gate 2C (9 biến thể Frustration, Giải pháp cụ thể, Báo giá & Khuyến mãi): Định tuyến chính xác theo 13 mức ưu tiên")
    void testNineFrustrationAndPricePromoVariants() {
        List<PackageCatalogService.PackageSummary> catalog = List.of(
                new PackageCatalogService.PackageSummary(
                        "HN_NS01", "NS01", "HN", "Massage trị liệu cơ bản", "neck_shoulder",
                        List.of("đầu", "cổ", "vai", "gáy", "lưng", "chân", "giấc ngủ"), 60, "60 phút", 399_000L, "399k",
                        "Phòng tiêu chuẩn có rèm riêng", "Massage trị liệu toàn thân và tập trung vùng đau mỏi + buffet thực dưỡng",
                        "Đau mỏi đầu, cổ vai gáy, chân và khó ngủ", "Quy trình 60 phút", false
                ),
                new PackageCatalogService.PackageSummary(
                        "HN_DS02", "DS02", "HN", "Gói dưỡng sinh cơ bản", "duong_sinh",
                        List.of("đầu", "cổ", "vai", "gáy"), 60, "60 phút", 239_000L, "239k",
                        "Phòng tiêu chuẩn", "Gội đầu dưỡng sinh + massage cổ vai gáy + ăn nhẹ",
                        "Mỏi nhẹ vùng đầu cổ vai", "Quy trình 60 phút", false
                )
        );

        ConversationState baseState = ConversationState.empty()
                .withDynamicContext(catalog, true, true)
                .withTenantCareActor("kỹ thuật viên", MULTI_DOMAIN_TAXONOMY)
                .enrichWithCurrentMessage("Giá sao", MULTI_DOMAIN_TAXONOMY)
                .enrichWithCurrentMessage("Tôi bị đau đầu khó ngủ mỏi vai", MULTI_DOMAIN_TAXONOMY)
                .enrichWithCurrentMessage("Đau đầu chứ đầu gối đâu", MULTI_DOMAIN_TAXONOMY);

        // Giả lập bot đã xin lỗi lỗi WRONG_SCOPE:đầu gối ở Turn 3
        PrimaryActionRouter.RoutingDecision t3Decision = new PrimaryActionRouter.RoutingDecision(
                PrimaryActionRouter.PrimaryAction.REPAIR_AND_RECONFIRM,
                PrimaryActionRouter.PrimaryAction.REPAIR_AND_RECONFIRM,
                false, 2, "REPAIR_AND_RECONFIRM", "spa_frustration_empathy_rescue"
        );
        ConversationState stateAfterT3 = ConversationState.updateAfterSend(
                null, "sender-25", "conv-25", baseState, "Đau đầu chứ đầu gối đâu", t3Decision,
                List.of(new com.example.docmind.dto.bot.FanpageBotResponse.ReplyBubble("Dạ em xin lỗi vì nhầm sang đầu gối ạ.", 0)),
                false, 3
        );

        // Biến thể 17..19: 3 câu Frustration / Đòi giải pháp sau khi đã sửa lỗi vùng -> DIRECT_CONCRETE_SOLUTION (Mức ưu tiên 7)
        String[] frustrationVariants = {
                "Dở vừa, ns linh ta linh tinh",
                "Rồi định chăm sóc cái j cho t, t phát bực r",
                "Tư vấn chán thế, chẳng hiểu ý khách gì cả"
        };
        for (String msg : frustrationVariants) {
            PrimaryActionRouter.RoutingDecision dec = PrimaryActionRouter.decide(
                    stateAfterT3, msg, false, List.of(), PagePersonaConfig.defaults()
            );
            assertEquals(PrimaryActionRouter.PrimaryAction.DIRECT_CONCRETE_SOLUTION, dec.effectiveResponseAction(),
                    "Câu bức xúc '" + msg + "' sau khi đã xin lỗi phải vào DIRECT_CONCRETE_SOLUTION");
            assertNotEquals("spa_ambiguous_confirm_once", dec.correspondingMicroSkillCode());
        }

        // Biến thể 20: Câu tụt cảm xúc -> MOOD_DROP_RECOVERY (Mức ưu tiên 8: 1 bubble, 0 dấu ?, không vào ambiguous_confirm_once)
        PrimaryActionRouter.RoutingDecision moodDropDec = PrimaryActionRouter.decide(
                stateAfterT3, "Tôi thấy tụt cảm xúc quá chán chả buồn nói", false, List.of(), PagePersonaConfig.defaults()
        );
        assertEquals("MOOD_DROP_RECOVERY", moodDropDec.detectedIntent());
        assertEquals(1, moodDropDec.maxResponseBubblesForLlm());
        assertNotEquals("spa_ambiguous_confirm_once", moodDropDec.correspondingMicroSkillCode());

        // Biến thể 21..22: 2 câu hỏi giá khi đã có confirmedScopes -> ANSWER_PRICE_ONCE
        String[] priceInquiryVariants = {
                "Nghe ổn hơn đó, giá sao",
                "Vậy gói này bao nhiêu tiền em?"
        };
        for (String msg : priceInquiryVariants) {
            PrimaryActionRouter.RoutingDecision dec = PrimaryActionRouter.decide(
                    stateAfterT3, msg, false, List.of(), PagePersonaConfig.defaults()
            );
            assertEquals(PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE, dec.effectiveResponseAction(),
                    "Câu hỏi giá '" + msg + "' khi đã rõ phạm vi phải vào ANSWER_PRICE_ONCE");
        }

        // Cập nhật state đã báo giá 399k để kiểm tra Biến thể 23..25 (Chê đắt & Hỏi khuyến mãi)
        PrimaryActionRouter.RoutingDecision quoteDecision = new PrimaryActionRouter.RoutingDecision(
                PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE,
                PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE,
                false, 2, "PRICE_QUOTE", "spa_price_quote_once"
        );
        ConversationState stateAfterPriceQuoted = ConversationState.updateAfterSend(
                null, "sender-25", "conv-25", stateAfterT3, "Nghe ổn hơn đó, giá sao", quoteDecision,
                List.of(new com.example.docmind.dto.bot.FanpageBotResponse.ReplyBubble("Dạ gói Massage trị liệu cơ bản 60 phút có giá 399k ạ.", 0)),
                false, 4
        );

        // Biến thể 23..24: Chê đắt + hỏi giảm giá/khuyến mãi lần 1 -> HANDLE_PRICE_OBJECTION_FIRST_TIME
        String[] promoObjectionVariants = {
                "Đắt có giảm giá hya Km k",
                "Có chương trình ưu đãi hay voucher gì đỡ đắt không?"
        };
        for (String msg : promoObjectionVariants) {
            PrimaryActionRouter.RoutingDecision dec = PrimaryActionRouter.decide(
                    stateAfterPriceQuoted, msg, false, List.of(), PagePersonaConfig.defaults()
            );
            assertEquals(PrimaryActionRouter.PrimaryAction.HANDLE_PRICE_OBJECTION_FIRST_TIME, dec.effectiveResponseAction(),
                    "Câu chê đắt/hỏi KM lần 1 '" + msg + "' phải vào HANDLE_PRICE_OBJECTION_FIRST_TIME");
        }

        // Biến thể 25: Chê đắt lần 2 / xin gói mềm hơn -> HANDLE_PRICE_OBJECTION_REPEAT
        PrimaryActionRouter.RoutingDecision firstObjDec = new PrimaryActionRouter.RoutingDecision(
                PrimaryActionRouter.PrimaryAction.HANDLE_PRICE_OBJECTION_FIRST_TIME,
                PrimaryActionRouter.PrimaryAction.HANDLE_PRICE_OBJECTION_FIRST_TIME,
                false, 2, "PRICE_OBJECTION_WITH_PROMO_INQUIRY", "spa_objection_value_trial"
        );
        ConversationState stateAfterFirstObjection = ConversationState.updateAfterSend(
                null, "sender-25", "conv-25", stateAfterPriceQuoted, "Đắt có giảm giá hya Km k", firstObjDec,
                List.of(new com.example.docmind.dto.bot.FanpageBotResponse.ReplyBubble("Dạ bên em ưu đãi 10% thẻ 5 buổi, mình thử lẻ 1 buổi 399k trước nha 🥰", 0)),
                false, 5
        );
        PrimaryActionRouter.RoutingDecision downsellDec = PrimaryActionRouter.decide(
                stateAfterFirstObjection, "Vẫn hơi cao, có gói nào mềm hơn không em?", false, List.of(), PagePersonaConfig.defaults()
        );
        assertEquals(PrimaryActionRouter.PrimaryAction.HANDLE_PRICE_OBJECTION_REPEAT, downsellDec.effectiveResponseAction(),
                "Câu xin gói mềm hơn ở lần chê thứ 2 phải vào HANDLE_PRICE_OBJECTION_REPEAT");
    }
}
