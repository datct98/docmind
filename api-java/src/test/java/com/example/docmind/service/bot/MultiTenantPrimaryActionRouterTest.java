package com.example.docmind.service.bot;

import com.docmind.guardrail.ReplyContractValidator;
import com.example.docmind.dto.PagePersonaConfig;
import com.example.docmind.dto.bot.FanpageBotRequest.ConversationTurn;
import com.example.docmind.dto.bot.FanpageBotResponse.ReplyBubble;
import com.example.docmind.repository.RetrievedChunk;
import com.example.docmind.service.retrieval.PromptBuilder.PagePersona;
import com.example.docmind.service.retrieval.ReciprocalRankFusion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MultiTenantPrimaryActionRouterTest {

    private static ReciprocalRankFusion.FusedChunk chunk(String heading, String text) {
        RetrievedChunk rc = new RetrievedChunk(
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(heading),
                List.of(UUID.randomUUID()),
                1,
                new double[]{0, 0, 100, 100},
                text,
                0.95
        );
        return new ReciprocalRankFusion.FusedChunk(rc, 0.05);
    }

    private static PagePersona spaPersona() {
        PagePersonaConfig cfg = new PagePersonaConfig(
                "spa_wellness",
                "An Nhiên",
                "em",
                "mình",
                "Ấm áp, nhẹ nhàng",
                "THẤP",
                List.of(),
                List.of("massage", "thảo dược", "gội đầu", "chăm sóc", "trị liệu", "cổ", "vai", "gáy", "lưng", "chân", "gối", "tay", "đầu", "body", "toàn thân"),
                null,
                null,
                List.of(
                        "Dạ chào {honorific}, em là An Nhiên của Nàng Sen Spa ạ. Bên em chuyên massage thư giãn, ngâm thảo dược và vệ sinh tai ngoài theo hướng nhẹ nhàng, sạch sẽ và chỉn chu.",
                        "{CapHonorific} cứ nhắn em biết đang muốn thư giãn, giảm mỏi hay chăm sóc riêng vùng nào, em sẽ gợi ý cho phù hợp ạ."
                ),
                15_000,
                "Dạ mình thích lực nhẹ thư giãn hay lực vừa ấn sâu hơn chút để em báo kỹ thuật viên chuẩn bị ạ?",
                "Dạ tình trạng của {honorific} mới xuất hiện gần đây hay đã kéo dài một thời gian rồi ạ?",
                "Dạ tình trạng này của {honorific} bị thường xuyên mỗi ngày hay thi thoảng mới mỏi nhiều hơn ạ?",
                null,
                List.of("đau", "mỏi", "nhức", "căng", "tê", "tê bì"),
                List.of("cổ", "vai", "gáy", "lưng", "chân", "gối", "tay", "đầu", "body", "toàn thân"),
                java.util.Map.of(),
                java.util.Map.of()
        );
        return new PagePersona("Nàng Sen Spa", "spa_wellness", "Khách hàng spa", "Ấm áp", cfg);
    }

    private static List<ReciprocalRankFusion.FusedChunk> spaCatalogChunks() {
        return List.of(
                chunk("Bảng giá dịch vụ Active",
                        "- Massage body thư giãn: có 2 mức thời lượng là 60 phút giá 350k (350.000đ) và 90 phút giá 480k (480.000đ).\n"
                        + "- Massage cổ vai gáy: thời lượng 45 phút có giá 280k (280.000đ).\n"
                        + "- Ngâm chân thảo dược ấm: thời lượng 20 phút có giá 150k (150.000đ).\n"
                        + "- Vệ sinh tai ngoài / lấy ráy tai thư giãn: thời lượng 30 phút có giá 180k (180.000đ).\n"
                        + "- Gội đầu thảo dược thư giãn: thời lượng 45 phút có giá 220k (220.000đ).")
        );
    }

    private static PagePersona dentalPersona() {
        PagePersonaConfig cfg = new PagePersonaConfig(
                "dental_clinic",
                "Minh Anh",
                "em",
                "mình",
                "Chuyên nghiệp, tận tâm",
                "THẤP",
                List.of(),
                List.of("răng", "nướu", "lợi", "tẩy trắng", "cạo vôi", "nhổ răng", "trám răng", "ê buốt", "nha khoa", "hàm"),
                null,
                null,
                List.of(
                        "Dạ chào {honorific}, em là Minh Anh của Nha Khoa Minh Châu ạ. Bên em chuyên chăm sóc răng miệng, cạo vôi răng siêu âm và thẩm mỹ nụ cười chuẩn Y khoa.",
                        "{CapHonorific} đang muốn kiểm tra tình trạng răng nướu hay tìm hiểu dịch vụ nào để em hỗ trợ tư vấn chi tiết ạ?"
                ),
                15_000,
                "Dạ mình ưu tiên làm nhẹ nhàng giảm ê buốt tối đa hay làm nhanh gọn trong buổi hôm nay ạ?",
                "Dạ tình trạng răng của {honorific} mới ê buốt gần đây hay đã kéo dài nhiều tuần rồi ạ?",
                "Dạ {honorific} thấy ê buốt khi uống nước lạnh hay đau nhức cả lúc bình thường ạ?",
                null,
                List.of("đau", "ê buốt", "nhức", "sâu", "chảy máu"),
                List.of("răng", "nướu", "lợi", "hàm"),
                java.util.Map.of(),
                java.util.Map.of()
        );
        return new PagePersona("Nha Khoa Minh Châu", "dental_clinic", "Khách hàng nha khoa", "Tận tâm", cfg);
    }

    private static List<ReciprocalRankFusion.FusedChunk> dentalCatalogChunks() {
        return List.of(
                chunk("Bảng giá dịch vụ Nha Khoa Minh Châu",
                        "- Cạo vôi răng siêu âm: thời lượng 30 phút có giá 300k (300.000đ), giúp làm sạch mảng bám chân răng và giảm ê buốt răng nướu.\n"
                        + "- Hàn trám răng thẩm mỹ Composite: thời lượng 30 phút có giá 450k (450.000đ), phục hồi răng sâu hoặc mẻ nhẹ.\n"
                        + "- Tẩy trắng răng Laser Whitening: thời lượng 60 phút có giá 1500k (1.500.000đ).\n"
                        + "- Nhổ răng khôn hàm dưới bằng máy Piezotome: thời lượng 45 phút có giá 2000k (2.000.000đ).")
        );
    }

    @Test
    @DisplayName("RT-SPA-018: 4-turn multi-entity leg/knee flow + Scope-Grounded Selection + ENTITY_MISMATCH_CORRECTION anti-keyword-hijacking")
    void rtSpa018_fourTurnMultiEntityAndMismatchCorrectionFlow() {
        UUID pageId = UUID.randomUUID();
        PagePersona persona = spaPersona();
        List<ReciprocalRankFusion.FusedChunk> chunks = spaCatalogChunks();
        List<String> keywords = persona.resolvedConfig().safeDomainKeywords();
        List<ConversationTurn> history = new ArrayList<>();

        // Turn 1: "Giá"
        String u1 = "Giá";
        ConversationState s1 = ConversationState.hydrate(pageId, "u-018", history, keywords, chunks);
        PrimaryActionRouter.RoutingDecision d1 = PrimaryActionRouter.route(u1, s1, chunks, keywords);
        assertThat(d1.effectiveResponseAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.CLARIFY_SERVICE_DURATION);
        List<ReplyBubble> b1 = ReplyContractValidator.validateAndEnforce(
                PrimaryActionRouter.executeActionBubbles(d1, u1, s1, "mình", persona, chunks, null),
                s1, u1, d1.effectiveResponseAction(), d1.openingRequired(), 15_000
        ).validatedBubbles();
        assertThat(b1).hasSizeGreaterThanOrEqualTo(2);
        assertThat(b1.get(0).text()).contains("An Nhiên").contains("Nàng Sen Spa");
        history.add(new ConversationTurn("user", u1));
        history.add(new ConversationTurn("assistant", joinBubbles(b1)));

        // Turn 2: "Tôi bị chân đau, mỏi gối tê bì" (inverted + multi-entity)
        String u2 = "Tôi bị chân đau, mỏi gối tê bì";
        ConversationState s2 = ConversationState.hydrate(pageId, "u-018", history, keywords, chunks);
        PrimaryActionRouter.RoutingDecision d2 = PrimaryActionRouter.route(u2, s2, chunks, keywords);
        assertThat(d2.effectiveResponseAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.ACKNOWLEDGE_AND_ASK_PREFERENCE);

        // Verify multi-entity extraction and inverted Vietnamese phrase ("chân đau" -> "đau chân")
        List<String> extractedNeeds = ConversationState.extractAllNeeds(u2, keywords);
        List<String> targetScopes = ConversationState.extractTargetScopes(u2, keywords);
        assertThat(extractedNeeds).contains("đau chân", "mỏi gối", "tê bì");
        assertThat(targetScopes).contains("chân", "gối");
        assertThat(targetScopes).doesNotContain("cổ", "vai", "gáy");

        List<ReplyBubble> b2 = ReplyContractValidator.validateAndEnforce(
                PrimaryActionRouter.executeActionBubbles(d2, u2, s2, "mình", persona, chunks, null),
                s2, u2, d2.effectiveResponseAction(), d2.openingRequired(), 15_000
        ).validatedBubbles();
        assertThat(b2).hasSize(2);
        assertThat(b2.get(0).text().toLowerCase(Locale.ROOT))
                .contains("đau chân")
                .contains("mỏi gối")
                .contains("tê bì")
                .doesNotContain("cổ vai");
        history.add(new ConversationTurn("user", u2));
        history.add(new ConversationTurn("assistant", joinBubbles(b2)));

        // Turn 3: "thi thoảng" -> Must recommend & quote a scope-grounded option ("ngâm chân thảo dược ấm" or "massage body"), NEVER "massage cổ vai gáy"
        String u3 = "thi thoảng";
        ConversationState s3 = ConversationState.hydrate(pageId, "u-018", history, keywords, chunks);
        assertThat(s3.knownBodyAreas()).contains("chân", "gối");
        PrimaryActionRouter.RoutingDecision d3 = PrimaryActionRouter.route(u3, s3, chunks, keywords);
        assertThat(d3.effectiveResponseAction()).isIn(
                PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE,
                PrimaryActionRouter.PrimaryAction.RECOMMEND_ONE_PACKAGE
        );
        List<ReplyBubble> b3 = ReplyContractValidator.validateAndEnforce(
                PrimaryActionRouter.executeActionBubbles(d3, u3, s3, "mình", persona, chunks, null),
                s3, u3, d3.effectiveResponseAction(), d3.openingRequired(), 15_000
        ).validatedBubbles();
        assertThat(b3).hasSize(2);
        String b3TextLower = joinBubbles(b3).toLowerCase(Locale.ROOT);
        assertThat(b3TextLower).doesNotContain("cổ vai gáy");
        assertThat(b3TextLower).doesNotContain("280k");
        assertThat(b3TextLower).containsAnyOf("ngâm chân thảo dược ấm", "massage body thư giãn");
        history.add(new ConversationTurn("user", u3));
        history.add(new ConversationTurn("assistant", joinBubbles(b3)));

        // Turn 4: "Tao đau chân, mỏi gối tê tay mà mày đi tư vấn massage cổ là sao"
        // Even if prior bot turn had mistakenly recommended "massage cổ vai gáy", Turn 4 must detect ENTITY_MISMATCH_CORRECTION,
        // strip "mà mày đi tư vấn massage cổ là sao", NOT hijack service selection to "massage cổ", and trigger HARD_HANDOFF_COMPLAINT!
        String u4 = "Tao đau chân, mỏi gối tê tay mà mày đi tư vấn massage cổ là sao";
        assertThat(SentimentIntentLayer.isEntityMismatchCorrection(u4)).isTrue();
        assertThat(SentimentIntentLayer.hasHarshPronouns(u4)).isTrue();
        assertThat(SentimentIntentLayer.extractRejectedWrongOffer(u4)).contains("massage cổ");
        assertThat(ConversationState.extractTargetScopes(u4, keywords))
                .contains("chân", "gối", "tay")
                .doesNotContain("cổ");

        ConversationState s4 = ConversationState.hydrate(pageId, "u-018", history, keywords, chunks);
        PrimaryActionRouter.RoutingDecision d4 = PrimaryActionRouter.route(u4, s4, chunks, keywords);
        assertThat(d4.primaryAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.HARD_HANDOFF_COMPLAINT);
        assertThat(d4.effectiveResponseAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.HARD_HANDOFF_COMPLAINT);
        assertThat(d4.detectedIntent()).isEqualTo("MISMATCH_CORRECTION_HANDOFF");

        List<ReplyBubble> b4 = ReplyContractValidator.validateAndEnforce(
                PrimaryActionRouter.executeActionBubbles(d4, u4, s4, "mình", persona, chunks, null),
                s4, u4, d4.effectiveResponseAction(), d4.openingRequired(), 15_000
        ).validatedBubbles();
        assertThat(b4).hasSize(1);
        assertThat(b4.get(0).text())
                .doesNotContain("?")
                .doesNotContain("280k")
                .contains("xin lỗi")
                .contains("quản lý");
    }

    @Test
    @DisplayName("Ungrounded Scope Handoff: When Active Chunks have zero matching packages for customer targetScopes, bot triggers HARD_HANDOFF_COMPLAINT instead of forcing off-scope service")
    void ungroundedScopeHandoff_whenNoCatalogOptionMatchesCustomerTargetScopes() {
        UUID pageId = UUID.randomUUID();
        PagePersona persona = spaPersona();
        // Only off-scope neck package exists in chunks
        List<ReciprocalRankFusion.FusedChunk> offScopeChunks = List.of(
                chunk("Bảng giá dịch vụ Active",
                        "- Massage cổ vai gáy: thời lượng 45 phút có giá 280k (280.000đ).")
        );
        List<String> keywords = persona.resolvedConfig().safeDomainKeywords();
        List<ConversationTurn> history = List.of(
                new ConversationTurn("user", "Tôi bị chân đau, mỏi gối tê bì"),
                new ConversationTurn("assistant", "Dạ với tình trạng đau chân, mỏi gối và tê bì, mình đang mỏi vùng đó mới gần đây hay đã kéo dài ạ?")
        );

        ConversationState state = ConversationState.hydrate(pageId, "u-ungrounded", history, keywords, offScopeChunks);
        PrimaryActionRouter.RoutingDecision decision = PrimaryActionRouter.route("thi thoảng", state, offScopeChunks, keywords);
        assertThat(decision.effectiveResponseAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.HARD_HANDOFF_COMPLAINT);
        assertThat(decision.detectedIntent()).isEqualTo("UNGROUNDED_SCOPE_HANDOFF");

        List<ReplyBubble> bubbles = ReplyContractValidator.validateAndEnforce(
                PrimaryActionRouter.executeActionBubbles(decision, "thi thoảng", state, "mình", persona, offScopeChunks, null),
                state, "thi thoảng", decision.effectiveResponseAction(), decision.openingRequired(), 15_000
        ).validatedBubbles();
        assertThat(bubbles).hasSize(1);
        assertThat(bubbles.get(0).text())
                .doesNotContain("280k")
                .doesNotContain("cổ vai")
                .doesNotContain("?");
    }

    @Test
    @DisplayName("RT-MULTI-TENANT-001: 2nd Industry Page (Dental Clinic) executes full discovery, scope-grounded quote, and price objection defense with zero Spa leakage")
    void rtMultiTenant001_dentalClinicPageExecutesDomainAgnosticallyWithZeroSpaLeakage() {
        UUID pageId = UUID.randomUUID();
        PagePersona persona = dentalPersona();
        List<ReciprocalRankFusion.FusedChunk> chunks = dentalCatalogChunks();
        List<String> keywords = persona.resolvedConfig().safeDomainKeywords();
        List<ConversationTurn> history = new ArrayList<>();

        // Turn 1: "Giá"
        String u1 = "Giá";
        ConversationState s1 = ConversationState.hydrate(pageId, "u-dental", history, keywords, chunks);
        PrimaryActionRouter.RoutingDecision d1 = PrimaryActionRouter.route(u1, s1, chunks, keywords);
        assertThat(d1.effectiveResponseAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.CLARIFY_SERVICE_DURATION);
        List<ReplyBubble> b1 = ReplyContractValidator.validateAndEnforce(
                PrimaryActionRouter.executeActionBubbles(d1, u1, s1, "mình", persona, chunks, null),
                s1, u1, d1.effectiveResponseAction(), d1.openingRequired(), 15_000
        ).validatedBubbles();
        String reply1 = joinBubbles(b1);
        assertThat(reply1)
                .contains("Minh Anh")
                .contains("Nha Khoa Minh Châu")
                .contains("cạo vôi răng")
                .doesNotContainIgnoringCase("Nàng Sen")
                .doesNotContainIgnoringCase("An Nhiên")
                .doesNotContainIgnoringCase("massage")
                .doesNotContainIgnoringCase("thảo dược");
        history.add(new ConversationTurn("user", u1));
        history.add(new ConversationTurn("assistant", reply1));

        // Turn 2: "Tôi bị đau răng, ê buốt chân răng"
        String u2 = "Tôi bị đau răng, ê buốt chân răng";
        ConversationState s2 = ConversationState.hydrate(pageId, "u-dental", history, keywords, chunks);
        PrimaryActionRouter.RoutingDecision d2 = PrimaryActionRouter.route(u2, s2, chunks, keywords);
        assertThat(d2.effectiveResponseAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.ACKNOWLEDGE_AND_ASK_PREFERENCE);
        List<ReplyBubble> b2 = ReplyContractValidator.validateAndEnforce(
                PrimaryActionRouter.executeActionBubbles(d2, u2, s2, "mình", persona, chunks, null),
                s2, u2, d2.effectiveResponseAction(), d2.openingRequired(), 15_000
        ).validatedBubbles();
        String reply2 = joinBubbles(b2);
        assertThat(reply2.toLowerCase(Locale.ROOT))
                .contains("đau răng")
                .contains("ê buốt")
                .doesNotContain("massage")
                .doesNotContain("thảo dược");
        history.add(new ConversationTurn("user", u2));
        history.add(new ConversationTurn("assistant", reply2));

        // Turn 3: "thi thoảng" -> Scope-grounded recommendation selects "cạo vôi răng siêu âm 30 phút" (300k)
        String u3 = "thi thoảng";
        ConversationState s3 = ConversationState.hydrate(pageId, "u-dental", history, keywords, chunks);
        PrimaryActionRouter.RoutingDecision d3 = PrimaryActionRouter.route(u3, s3, chunks, keywords);
        assertThat(d3.effectiveResponseAction()).isIn(
                PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE,
                PrimaryActionRouter.PrimaryAction.RECOMMEND_ONE_PACKAGE
        );
        List<ReplyBubble> b3 = ReplyContractValidator.validateAndEnforce(
                PrimaryActionRouter.executeActionBubbles(d3, u3, s3, "mình", persona, chunks, null),
                s3, u3, d3.effectiveResponseAction(), d3.openingRequired(), 15_000
        ).validatedBubbles();
        assertThat(b3).hasSize(2);
        String reply3 = joinBubbles(b3);
        assertThat(reply3.toLowerCase(Locale.ROOT))
                .contains("cạo vôi răng siêu âm")
                .contains("300k")
                .doesNotContain("massage")
                .doesNotContain("thảo dược");
        assertThat(b3.get(1).typingDelayMs()).isGreaterThanOrEqualTo(15_000);
        history.add(new ConversationTurn("user", u3));
        history.add(new ConversationTurn("assistant", reply3));

        // Turn 4: "300k hơi đắt" -> Step-1 Value Defense holds 300k and uses dental value pillars from chunks
        String u4 = "300k hơi đắt";
        ConversationState s4 = ConversationState.hydrate(pageId, "u-dental", history, keywords, chunks);
        PrimaryActionRouter.RoutingDecision d4 = PrimaryActionRouter.route(u4, s4, chunks, keywords);
        assertThat(d4.effectiveResponseAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.HANDLE_PRICE_OBJECTION_FIRST_TIME);
        List<ReplyBubble> b4 = ReplyContractValidator.validateAndEnforce(
                PrimaryActionRouter.executeActionBubbles(d4, u4, s4, "mình", persona, chunks, null),
                s4, u4, d4.effectiveResponseAction(), d4.openingRequired(), 15_000
        ).validatedBubbles();
        String reply4 = joinBubbles(b4);
        assertThat(reply4.toLowerCase(Locale.ROOT))
                .contains("cạo vôi răng siêu âm 30 phút")
                .contains("làm sạch mảng bám chân răng")
                .doesNotContain("massage")
                .doesNotContain("thảo dược")
                .doesNotContain("vùng mỏi");
    }

    @Test
    @DisplayName("Critique-Before-Symptom & Chained Scopes: Polite mismatch correction where critique precedes true symptom never hijacks scope to rejected service")
    void politeMismatchCorrection_whenCritiquePrecedesSymptom_neverHijacksToRejectedService() {
        UUID pageId = UUID.randomUUID();
        PagePersona persona = spaPersona();
        List<ReciprocalRankFusion.FusedChunk> chunks = spaCatalogChunks();
        List<String> keywords = persona.resolvedConfig().safeDomainKeywords();

        // 1. Chained scopes after a single modifier ("đau chân, gối")
        assertThat(ConversationState.extractAllNeeds("Chị bị đau chân, gối", keywords))
                .contains("đau chân", "đau gối");
        assertThat(ConversationState.extractTargetScopes("Chị bị đau chân, gối", keywords))
                .containsExactly("chân", "gối");

        // 2. Critique clause precedes true symptom clause
        List<String> politeRebuttals = List.of(
                "Sao em lại tư vấn massage cổ trong khi chị bị đau chân mỏi gối",
                "Em đi tư vấn massage cổ là sao, chị bị đau chân mỏi gối mà",
                "Tư vấn massage cổ là sao, chị đau chân mỏi gối"
        );

        List<ConversationTurn> history = List.of(
                new ConversationTurn("user", "Giá"),
                new ConversationTurn("assistant", "Dạ gói massage cổ 45 phút bên em có giá 280k ạ.")
        );

        for (String msg : politeRebuttals) {
            assertThat(SentimentIntentLayer.isEntityMismatchCorrection(msg))
                    .as("Should detect mismatch correction for: %s", msg)
                    .isTrue();
            assertThat(SentimentIntentLayer.hasHarshPronouns(msg)).isFalse();
            assertThat(SentimentIntentLayer.extractRejectedWrongOffer(msg)).contains("massage cổ");
            assertThat(ConversationState.extractTargetScopes(msg, keywords))
                    .as("Scopes for: %s", msg)
                    .contains("chân", "gối")
                    .doesNotContain("cổ");

            ConversationState state = ConversationState.hydrate(pageId, "u-polite-" + msg.hashCode(), history, keywords, chunks);
            PrimaryActionRouter.RoutingDecision decision = PrimaryActionRouter.route(msg, state, chunks, keywords);
            assertThat(decision.effectiveResponseAction()).isIn(
                    PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE,
                    PrimaryActionRouter.PrimaryAction.RECOMMEND_ONE_PACKAGE
            );
            List<ReplyBubble> bubbles = ReplyContractValidator.validateAndEnforce(
                    PrimaryActionRouter.executeActionBubbles(decision, msg, state, "chị", persona, chunks, null),
                    state, msg, decision.effectiveResponseAction(), decision.openingRequired(), 15_000
            ).validatedBubbles();
            String replyLower = joinBubbles(bubbles).toLowerCase(Locale.ROOT);
            assertThat(replyLower)
                    .contains("xin lỗi")
                    .containsAnyOf("ngâm chân thảo dược ấm", "massage body thư giãn")
                    .doesNotContain("massage cổ")
                    .doesNotContain("280k");
        }
    }

    @Test
    @DisplayName("R05 Price Inquiry Scope-Grounded Gate: Explicit price inquiry with ungrounded body scope triggers UNGROUNDED_SCOPE_HANDOFF")
    void priceInquiryWithUngroundedScope_triggersUngroundedScopeHandoff() {
        UUID pageId = UUID.randomUUID();
        PagePersona persona = spaPersona();
        List<ReciprocalRankFusion.FusedChunk> offScopeChunks = List.of(
                chunk("Bảng giá dịch vụ Active",
                        "- Massage cổ: thời lượng 45 phút có giá 280k (280.000đ).")
        );
        List<String> keywords = persona.resolvedConfig().safeDomainKeywords();

        // Case A: Direct scope price inquiry ("Gói chăm sóc chân gối giá bao nhiêu shop?")
        String msgDirect = "Gói chăm sóc chân gối giá bao nhiêu shop?";
        ConversationState stateDirect = ConversationState.hydrate(pageId, "u-r05-direct", List.of(), keywords, offScopeChunks);
        PrimaryActionRouter.RoutingDecision dDirect = PrimaryActionRouter.route(msgDirect, stateDirect, offScopeChunks, keywords);
        assertThat(dDirect.effectiveResponseAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.HARD_HANDOFF_COMPLAINT);
        assertThat(dDirect.detectedIntent()).isEqualTo("UNGROUNDED_SCOPE_HANDOFF");

        // Case B: Post-condition price inquiry ("Thi thoảng thôi, giá bao nhiêu shop?")
        List<ConversationTurn> history = List.of(
                new ConversationTurn("user", "Tôi bị đau chân mỏi gối"),
                new ConversationTurn("assistant", "Dạ em ghi nhận mình đang đau chân và mỏi gối ạ. Mình bị lâu chưa hay mới gần đây ạ?")
        );
        ConversationState statePostCond = ConversationState.hydrate(pageId, "u-r05-postcond", history, keywords, offScopeChunks);
        PrimaryActionRouter.RoutingDecision dPostCond = PrimaryActionRouter.route("Thi thoảng thôi, giá bao nhiêu shop?", statePostCond, offScopeChunks, keywords);
        assertThat(dPostCond.effectiveResponseAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.HARD_HANDOFF_COMPLAINT);
        assertThat(dPostCond.detectedIntent()).isEqualTo("UNGROUNDED_SCOPE_HANDOFF");

        // Case C: Non-Spa Dental Service Detail FAQ ("Cạo vôi răng siêu âm là gì?") -> SERVICE_DETAIL_AND_FAQ_QA without Spa leakage or stutter
        PagePersona dentalPersona = dentalPersona();
        List<ReciprocalRankFusion.FusedChunk> dentalChunks = dentalCatalogChunks();
        List<String> dentalKws = dentalPersona.resolvedConfig().safeDomainKeywords();
        ConversationState dentalState = ConversationState.hydrate(
                UUID.randomUUID(), "u-dental-faq",
                List.of(new ConversationTurn("user", "Báo giá cạo vôi răng"),
                        new ConversationTurn("assistant", "Dạ cạo vôi răng siêu âm 30 phút bên em có giá 250k ạ.")),
                dentalKws, dentalChunks
        );
        String dentalFaqMsg = "Cạo vôi răng siêu âm là gì?";
        PrimaryActionRouter.RoutingDecision dDentalFaq = PrimaryActionRouter.route(dentalFaqMsg, dentalState, dentalChunks, dentalKws);
        assertThat(dDentalFaq.primaryAction()).isEqualTo(PrimaryActionRouter.PrimaryAction.SERVICE_DETAIL_AND_FAQ_QA);
        List<ReplyBubble> dentalFaqBubbles = PrimaryActionRouter.executeActionBubbles(
                dDentalFaq, dentalFaqMsg, dentalState, "mình", dentalPersona, dentalChunks, null
        );
        String dentalFaqReply = joinBubbles(dentalFaqBubbles).toLowerCase(Locale.ROOT);
        assertThat(dentalFaqReply)
                .contains("cạo vôi răng siêu âm")
                .contains("mảng bám")
                .doesNotContain("chi tiết chi tiết")
                .doesNotContain("massage")
                .doesNotContain("thảo dược");
    }

    private static String joinBubbles(List<ReplyBubble> bubbles) {
        StringBuilder sb = new StringBuilder();
        for (ReplyBubble b : bubbles) {
            if (!sb.isEmpty()) sb.append("\n\n");
            sb.append(b.text());
        }
        return sb.toString();
    }
}
