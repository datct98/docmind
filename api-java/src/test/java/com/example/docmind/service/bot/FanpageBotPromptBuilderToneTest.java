package com.example.docmind.service.bot;

import com.example.docmind.dto.LearnedRuleDto;
import com.example.docmind.dto.bot.AgentSkillDto;
import com.example.docmind.dto.bot.CustomerInsightDto;
import com.example.docmind.dto.bot.FanpageBotRequest.ConversationTurn;
import com.example.docmind.repository.RetrievedChunk;
import com.example.docmind.service.retrieval.PromptBuilder;
import com.example.docmind.service.retrieval.ReciprocalRankFusion;
import com.example.docmind.service.sales.PriceLadderTracker.PriceLadderResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature F9 Unit Test Suite: Empathetic Vietnamese Consultative Tone.
 *
 * <p>Verifies prompt building rules for:
 * <ul>
 *   <li>1. Consistent Vietnamese Honorifics (Em as self-reference, Chị/Anh as customer reference, zero phantom names, Turn 1 vs Turn 2+ greeting controls).</li>
 *   <li>2. High Empathy & Physical Symptom Validation (cổ vai gáy, mất ngủ, stress, đau lưng, differentiating wellness from dermatology).</li>
 *   <li>3. Smooth Consultative Down-sell Transitions (validating cost mindfulness, stepping down without defensive arguing, zero false handoffs).</li>
 *   <li>4. Conversational Conciseness (strictly 2–4 sentences per turn, maximum 2 paragraphs).</li>
 *   <li>5. Token Budget & Domain Agnosticism under full load.</li>
 * </ul>
 */
class FanpageBotPromptBuilderToneTest {

    private static ReciprocalRankFusion.FusedChunk sampleChunk(List<String> headingPath, String text) {
        RetrievedChunk rc = new RetrievedChunk(
                UUID.randomUUID(),
                UUID.randomUUID(),
                headingPath,
                List.of(UUID.randomUUID()),
                1,
                new double[]{0, 0, 100, 100},
                text,
                0.95
        );
        return new ReciprocalRankFusion.FusedChunk(rc, 0.035);
    }

    private static PromptBuilder.PagePersona sampleSpaPersona() {
        return new PromptBuilder.PagePersona(
                "Nàng Sen Spa & Dưỡng Sinh",
                "Spa & Dưỡng Sinh Đông Y",
                "Khách hàng văn phòng và gia đình",
                "Tư vấn nhẹ nhàng, ấm áp, chuyên nghiệp, gọi khách là Chị, xưng em."
        );
    }

    @Test
    @DisplayName("F9.1: Default / Female customer strictly enforces 'Em - Chị', bans 'chúng tôi'/'tôi'/'mình' as bot self-reference, and includes Turn 1 greeting")
    void honorific_FemaleDefault_EnforcesEmChiWithoutLeakage() {
        String prompt = FanpageBotPromptBuilder.build(
                "Em ơi gói dưỡng sinh giá sao",
                null,
                List.of(),
                List.of(sampleChunk(List.of("Dưỡng Sinh"), "Gói Dưỡng sinh cơ bản DS02: 239.000 VNĐ (60 phút).")),
                List.of(),
                sampleSpaPersona(),
                FanpageBotPromptBuilder.SalesStage.DISCOVERY,
                "Chị"
        );

        // Core honorific assertions
        assertThat(prompt).contains("Xưng 'em / bên em', gọi 'Chị' 100%");
        assertThat(prompt).contains("cấm gọi 'Anh'");
        assertThat(prompt).contains("cấm xưng 'chúng tôi', 'tôi', 'mình'");
        assertThat(prompt).contains("cấm bịa tên riêng");
        assertThat(prompt).contains("Lượt đầu: Mở đầu bằng 'Dạ em chào Chị ạ! '");
    }

    @Test
    @DisplayName("F9.2: Male customer self-identification enforces 'Em - Anh' 100% with zero 'Chị' leakage in prompt rules")
    void honorific_MaleExplicitIdentification_EnforcesEmAnhZeroChi() {
        String customerMsg = "mình là nam, có liệu trình nào giãn cơ đỡ mỏi không em";
        String resolved = FanpageBotPromptBuilder.resolveCustomerHonorific(customerMsg, List.of(), null, sampleSpaPersona());
        assertThat(resolved).isEqualTo("Anh");

        String prompt = FanpageBotPromptBuilder.build(
                customerMsg,
                "Anh Tuấn",
                List.of(),
                List.of(sampleChunk(List.of("Massage"), "Massage cổ vai gáy NS01: 399.000 VNĐ (60 phút).")),
                List.of(),
                sampleSpaPersona(),
                FanpageBotPromptBuilder.SalesStage.DISCOVERY,
                resolved
        );

        assertThat(prompt).contains("Xưng 'em / bên em', gọi 'Anh' 100%");
        assertThat(prompt).contains("cấm gọi 'Chị'");
        assertThat(prompt).contains("Lượt đầu: Mở đầu bằng 'Dạ em chào Anh ạ! '");
        assertThat(prompt).doesNotContain("gọi 'Chị' 100%");
    }

    @Test
    @DisplayName("F9.3: Multi-turn Turn 2+ strictly forbids repeated greeting and enforces direct polite 'Dạ ...' continuation")
    void honorific_MultiTurn_Turn1Greets_Turn2ProhibitsRepeatedGreeting() {
        List<ConversationTurn> history = List.of(
                new ConversationTurn("user", "giá massage sao em"),
                new ConversationTurn("assistant", "Dạ em chào Chị ạ! Bên em có gói Massage cơ bản từ 399.000VNĐ ạ.")
        );

        String prompt = FanpageBotPromptBuilder.build(
                "có gói nào thư giãn vai gáy không",
                "Chị Mai",
                history,
                List.of(sampleChunk(List.of("Massage"), "Gói Massage cơ bản 399.000 VNĐ.")),
                List.of(),
                sampleSpaPersona(),
                FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION,
                "Chị"
        );

        assertThat(prompt).contains("Lượt tiếp: CẤM chào lại, trả lời thẳng bằng 'Dạ ...'");
        assertThat(prompt).doesNotContain("Lượt đầu: Mở đầu bằng 'Dạ em chào");
    }

    @Test
    @DisplayName("F9.4: Customer display name is sanitized, removing phantom names ('Lan Anh') and generic placeholders ('Khách hàng')")
    void honorific_SanitizesPhantomNames_ZeroLanAnh() {
        String cleanGeneric = FanpageBotPromptBuilder.sanitizeCustomerDisplayName("Khách hàng", "Chị");
        assertThat(cleanGeneric).isNull();

        String cleanGuest = FanpageBotPromptBuilder.sanitizeCustomerDisplayName("Guest 123", "Anh");
        assertThat(cleanGuest).isNull();

        // If male customer has conflicting female prefix in FB profile name
        String cleanConflicting = FanpageBotPromptBuilder.sanitizeCustomerDisplayName("Chị Lan Anh", "Anh");
        assertThat(cleanConflicting).isNull();

        String prompt = FanpageBotPromptBuilder.build(
                "tư vấn em",
                "Khách hàng",
                List.of(),
                List.of(),
                List.of(),
                sampleSpaPersona(),
                FanpageBotPromptBuilder.SalesStage.DISCOVERY,
                "Chị"
        );

        assertThat(prompt).doesNotContain("Khách: Khách hàng");
        assertThat(prompt).doesNotContain("Lan Anh");
    }

    @Test
    @DisplayName("F9.5: Wellness discomfort symptoms (đau mỏi vai gáy, mất ngủ, stress) trigger high-empathy validation before solution recommendations")
    void empathy_WellnessDiscomfortPattern_AcknowledgeDiscomfortFirst() {
        String msg = "Dạo này chị bị đau mỏi vai gáy với mất ngủ nhiều quá em ơi";
        assertThat(FanpageBotPromptBuilder.isWellnessOrDiscomfortMessage(msg)).isTrue();

        String prompt = FanpageBotPromptBuilder.build(
                msg,
                null,
                List.of(),
                List.of(sampleChunk(List.of("Dưỡng Sinh"), "Gói Dưỡng sinh thông kinh lạc DS02: 239.000 VNĐ.")),
                List.of(),
                sampleSpaPersona(),
                FanpageBotPromptBuilder.SalesStage.DISCOVERY,
                "Chị"
        );

        // Core Empathy Directives
        assertThat(prompt).contains("THẤU CẢM TRIỆU CHỨNG (EMPATHY FIRST)");
        assertThat(prompt).contains("Khách đang gặp triệu chứng mệt mỏi thể chất (đau mỏi vai gáy, mất ngủ, stress, đau lưng...)");
        assertThat(prompt).contains("BẮT BUỘC dành câu đầu tiên để đồng cảm chân thành và công nhận sự khó chịu");
        assertThat(prompt).contains("CẤM xả thông tin dồn dập hay chốt lịch ép buộc");
    }

    @Test
    @DisplayName("F9.6: Wellness symptom combined with price inquiry is classified as specific symptom need, not broad price dump")
    void empathy_WellnessDiscomfort_PreventsUnspecifiedPriceDumping() {
        String msg = "chị bị đau mỏi vai gáy mất ngủ thì chi phí bao nhiêu em";
        boolean isBroad = FanpageBotPromptBuilder.isBroadUnspecifiedPriceInquiry(msg, List.of());
        assertThat(isBroad).isFalse();

        assertThat(FanpageBotPromptBuilder.isWellnessOrDiscomfortMessage(msg)).isTrue();
    }

    @Test
    @DisplayName("F9.7: Differentiates physical wellness discomfort (vai gáy, stress, mất ngủ) from dermatological conditions (mụn, nám, sẹo)")
    void empathy_DermatologicalVsWellnessSymptomSeparation() {
        String dermMsg = "da mặt em bị mụn viêm nặng với thâm lâu năm";
        assertThat(FanpageBotPromptBuilder.isComplexOrSevereSymptomMessage(dermMsg)).isTrue();
        assertThat(FanpageBotPromptBuilder.isWellnessOrDiscomfortMessage(dermMsg)).isFalse();

        String wellnessMsg = "anh làm văn phòng ngồi nhiều bị đau lưng mỏi cổ và căng thẳng";
        assertThat(FanpageBotPromptBuilder.isWellnessOrDiscomfortMessage(wellnessMsg)).isTrue();
        assertThat(FanpageBotPromptBuilder.isComplexOrSevereSymptomMessage(wellnessMsg)).isFalse();
    }

    @Test
    @DisplayName("F9.8: Price objections ('cao thế', 'hơi đắt') enforce warm, non-defensive consultative down-sell transitions")
    void priceObjection_ConsultativeDownsellGuidance_WarmAndNonDefensive() {
        String objectionMsg = "cao thế em, có gói nào rẻ hơn không";
        assertThat(FanpageBotPromptBuilder.isPriceObjectionOrDownsellMessage(objectionMsg)).isTrue();

        List<ConversationTurn> history = List.of(
                new ConversationTurn("user", "giá massage sao em"),
                new ConversationTurn("assistant", "Dạ gói Massage toàn thân bên em là 399.000 VNĐ/60 phút ạ.")
        );

        String prompt = FanpageBotPromptBuilder.build(
                objectionMsg,
                null,
                history,
                List.of(sampleChunk(List.of("Dưỡng sinh"), "Gói Dưỡng sinh cơ bản DS02: 239.000 VNĐ.")),
                List.of(),
                sampleSpaPersona(),
                FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION,
                "Chị"
        );

        assertThat(prompt).contains("DOWN-SELL TƯ VẤN KHÉO LÉO & HANDOFF");
        assertThat(prompt).contains("thấu hiểu tâm lý giá, CẤM thanh minh hay tranh cãi");
        assertThat(prompt).contains("TUYỆT ĐỐI CẤM xuất [[HANDOFF]] và CẤM buông xuôi 'cứ cân nhắc thêm'");
        assertThat(prompt).contains("Quy tắc chuyển tiếp hạ bậc (Consultative Down-sell Transition)");
        assertThat(prompt).contains("xác nhận lần đầu thử dịch vụ thì chọn gói vừa vặn để trải nghiệm tay nghề trước là hoàn toàn hợp lý");
    }

    @Test
    @DisplayName("F9.9: Stateful PriceLadder guidance for intermediate down-sell strictly forbids defending prices or upward price rebound")
    void priceObjection_StatefulPriceLadder_DownsellStepEnforced() {
        PriceLadderResult ladderResult = new PriceLadderResult(
                true,
                false,
                399000L,
                239000L,
                "Gói Dưỡng sinh cơ bản (DS02)",
                null,
                false,
                "Down-sell from 399k to 239k"
        );

        String guidance = FanpageBotPromptBuilder.formatPriceLadderGuidance(ladderResult, "Chị");

        assertThat(guidance).contains("KHÁCH CHÊ GIÁ CAO / HỎI GÓI RẺ HƠN");
        assertThat(guidance).contains("Mức giá đã báo lượt trước: 399.000 VNĐ");
        assertThat(guidance).contains("MỨC GIÁ HẠ BẬC MỤC TIÊU BẮT BUỘC: 239.000 VNĐ (Gói Dưỡng sinh cơ bản (DS02))");
        assertThat(guidance).contains("Đồng cảm ấm áp, thấu hiểu khách muốn chọn mức phí vừa vặn để trải nghiệm trước");
        assertThat(guidance).contains("TUYỆT ĐỐI CẤM thanh minh, bảo vệ giá hay bảo khách 'cứ cân nhắc thêm'");
        assertThat(guidance).contains("TUYỆT ĐỐI CẤM quay ngược lên báo lại gói có giá cao hơn (>= 399.000đ)");
        assertThat(guidance).contains("TUYỆT ĐỐI CẤM xuất [[HANDOFF]]");
    }

    @Test
    @DisplayName("F9.10: Stateful PriceLadder guidance at catalog floor (129.000 VNĐ) confirms floor, offers 10% promo, and preserves warmth")
    void priceObjection_StatefulPriceLadder_CatalogFloorAndPromoEnforced() {
        PriceLadderResult floorResult = new PriceLadderResult(
                true,
                false,
                239000L,
                129000L,
                "Gói Lấy ráy tai cơ bản (DS01)",
                "Giảm 10% khi mua từ 5 buổi hoặc từ 5 voucher",
                true,
                "Catalog Floor Reached"
        );

        String guidance = FanpageBotPromptBuilder.formatPriceLadderGuidance(floorResult, "Anh");

        assertThat(guidance).contains("ĐÃ Ở MỨC GIÁ SÀN DANH MỤC (129.000 VNĐ)");
        assertThat(guidance).contains("Gói dịch vụ: Gói Lấy ráy tai cơ bản (DS01)");
        assertThat(guidance).contains("BẮT BUỘC xác nhận đây là mức giá mềm nhất bên em hiện tại");
        assertThat(guidance).contains("Giới thiệu chính sách ưu đãi Giảm 10% khi mua từ 5 buổi hoặc từ 5 voucher");
        assertThat(guidance).contains("TUYỆT ĐỐI CẤM xuất [[HANDOFF]] và CẤM từ chối phục vụ");
    }

    @Test
    @DisplayName("F9.11: Conversational conciseness (strictly 2–4 sentences per turn) is explicitly mandated across all sales stages")
    void conciseness_EnforcesTwoToFourSentencesAcrossAllStages() {
        for (FanpageBotPromptBuilder.SalesStage stage : FanpageBotPromptBuilder.SalesStage.values()) {
            assertThat(stage.guidance()).matches(".*(?:2-4 câu|2-3 câu).*");
        }

        String prompt = FanpageBotPromptBuilder.build(
                "tư vấn chị",
                null,
                List.of(),
                List.of(),
                List.of(),
                sampleSpaPersona(),
                FanpageBotPromptBuilder.SalesStage.DISCOVERY,
                "Chị"
        );

        assertThat(prompt).contains("Mỗi lượt phản hồi BẮT BUỘC chỉ từ 2 đến 4 câu văn ngắn gọn, súc tích (tối đa 2 đoạn ngắn)");
        assertThat(prompt).contains("Tuyệt đối không viết thành bài văn dài");
    }

    @Test
    @DisplayName("F9.12: Token budget remains compact (< 3,200 chars) under full multi-turn load with persona, insight, skills, rules, and history")
    void promptBudget_RemainsSlimAndCompliantUnderFullLoad() {
        CustomerInsightDto insight = new CustomerInsightDto(
                "ANALYTICAL_SKEPTIC",
                "Khách kỹ tính",
                70,
                "WARM",
                "RISING",
                List.of("Đau mỏi vai gáy", "Mất ngủ"),
                List.of("Sợ đắt"),
                "Đồng cảm ấm áp với tình trạng đau mỏi và giải tỏa áp lực giá bằng gói cơ bản."
        );

        List<AgentSkillDto> activeSkills = List.of(
                new AgentSkillDto(UUID.randomUUID(), "SKILL_EMPATHY", "Thấu cảm triệu chứng", "PSYCHOLOGY",
                        "Mô tả", "đau mỏi", "Đồng cảm chân thành với sự mệt mỏi của khách trước khi gợi ý liệu trình", "VD", "Cấm", 100, true, null, 10, 5, 0.95, Instant.now(), Instant.now())
        );

        List<LearnedRuleDto> rules = List.of(
                new LearnedRuleDto(
                        UUID.randomUUID(),
                        "INV",
                        "Khách đau mỏi vai gáy",
                        "Thiếu đồng cảm",
                        "Luôn công nhận sự khó chịu trước khi báo giá.",
                        List.of("vai gáy"),
                        true,
                        Instant.now(),
                        Instant.now()
                )
        );

        List<ConversationTurn> history = List.of(
                new ConversationTurn("user", "dạo này chị đau mỏi vai gáy quá"),
                new ConversationTurn("assistant", "Dạ em chào Chị ạ! Ngồi làm việc nhiều đau mỏi mệt lắm đúng không Chị, bên em có gói Dưỡng sinh cổ vai gáy 239k rất êm ái ạ.")
        );

        String prompt = FanpageBotPromptBuilder.build(
                "cao thế em, có gói nào rẻ hơn không",
                "Chị Lan",
                history,
                List.of(sampleChunk(List.of("Dưỡng sinh"), "DS01 Lấy ráy tai cơ bản: 129.000 VNĐ (30 phút).")),
                rules,
                sampleSpaPersona(),
                FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION,
                "Chị",
                insight,
                activeSkills
        );

        // Must remain slim and responsive (< 3,200 chars)
        assertThat(prompt.length()).isLessThan(3200);
        assertThat(prompt).contains("THẤU CẢM TRIỆU CHỨNG (EMPATHY FIRST)");
        assertThat(prompt).contains("DOWN-SELL TƯ VẤN KHÉO LÉO & HANDOFF");
    }
}
