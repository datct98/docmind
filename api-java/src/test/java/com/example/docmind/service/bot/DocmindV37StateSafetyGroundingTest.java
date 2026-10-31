package com.example.docmind.service.bot;

import com.example.docmind.dto.bot.FanpageBotRequest.ConversationTurn;
import com.example.docmind.dto.bot.FanpageBotResponse.ReplyBubble;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v3.7 Quality Gates: state lifecycle độc lập, Safety 2 tầng, Catalog grounding (0 fallback cứng),
 * Referee enforcement (câu hỏi xác nhận gói, xin lỗi rỗng) và lịch sử hội thoại từ Backend ledger.
 */
class DocmindV37StateSafetyGroundingTest {

    private static PrimaryActionRouter.RoutingDecision route(String msg, ConversationState st) {
        return PrimaryActionRouter.route(msg, st, List.of(), List.of("vai", "gáy", "cổ", "chân", "đầu", "lưng"));
    }

    // ---------------------------------------------------------------- State lifecycle
    @Test
    @DisplayName("conditionTimeClarified chỉ bật khi khách trả lời thời gian/tần suất/diễn biến")
    void conditionTimeClarifiedOnlyOnTimeAnswer() {
        ConversationState s0 = ConversationState.initial();
        ConversationState s1 = s0.enrichWithCurrentMessage("Tôi bị đau mỏi vai gáy", List.of());
        assertThat(s1.conditionTimeClarified()).isFalse();
        ConversationState s1b = s1.enrichWithCurrentMessage("Sao hỏi lung tung thế", List.of());
        assertThat(s1b.conditionTimeClarified()).isFalse();
        ConversationState s2 = s1.enrichWithCurrentMessage("thỉnh thoảng mới bị", List.of());
        assertThat(s2.conditionTimeClarified()).isTrue();
    }

    @Test
    @DisplayName("priceRequestedInSession chỉ phản ánh khách chủ động hỏi giá")
    void priceRequestedOnlyWhenCustomerAsks() {
        ConversationState s = ConversationState.initial().enrichWithCurrentMessage("Tôi bị đau vai", List.of());
        assertThat(s.priceRequestedInSession()).isFalse();
        ConversationState s2 = s.enrichWithCurrentMessage("giá bao nhiêu vậy", List.of());
        assertThat(s2.priceRequestedInSession()).isTrue();
    }

    @Test
    @DisplayName("packageExplicitlySelected không bật khi khách chỉ hỏi giá/nêu triệu chứng hoặc chưa có gói bot đề xuất")
    void packageExplicitlySelectedNeedsEvidence() {
        assertThat(ConversationState.hasExplicitPackageSelectionSignal("cho mình gói đó")).isTrue();
        assertThat(ConversationState.hasExplicitPackageSelectionSignal("ok lấy gói này nhé")).isTrue();
        assertThat(ConversationState.hasExplicitPackageSelectionSignal("giá bao nhiêu vậy")).isFalse();
        assertThat(ConversationState.hasExplicitPackageSelectionSignal("tôi bị đau vai gáy")).isFalse();
        assertThat(ConversationState.hasExplicitPackageSelectionSignal("có gói nào khác không")).isFalse();
        // Chưa có gói bot đề xuất => dù khách nói "cho mình gói đó" cũng không được bật cờ chọn gói.
        ConversationState s = ConversationState.initial().enrichWithCurrentMessage("cho mình gói đó", List.of());
        assertThat(s.packageExplicitlySelected()).isFalse();
        assertThat(s.selectedPackageId()).isNull();
        // Dữ liệu cũ thiếu trường => mặc định không coi là đã chọn gói.
        assertThat(ConversationState.initial().packageExplicitlySelected()).isFalse();
        assertThat(ConversationState.initial().lastOfferedPackageId()).isNull();
    }

    // ---------------------------------------------------------------- Safety 2 tầng
    @Test
    @DisplayName("Tầng 1: tê/yếu đột ngột một bên (không dùng chữ 'yếu liệt') -> HARD_HANDOFF_SAFETY ngay")
    void acuteHighRiskImmediateHandoff() {
        String[] acute = {
                "Tự nhiên tôi tê một bên người, đau đầu dữ dội",
                "Tôi bị tê nửa người bên trái đột ngột",
                "Tay phải tôi tê đột ngột từ sáng",
                "Mặt tôi méo miệng và nói khó từ nãy",
                "Tự dưng tê chân một bên kèm đau đầu"
        };
        for (String m : acute) {
            PrimaryActionRouter.RoutingDecision d = route(m, ConversationState.initial());
            assertThat(d.effectiveResponseAction()).as(m).isEqualTo(PrimaryActionRouter.PrimaryAction.HARD_HANDOFF_SAFETY);
        }
    }

    @Test
    @DisplayName("Tầng 2: đau đầu kèm tê chân (chưa rõ cấp tính) -> trấn an + hỏi làm rõ, không handoff, không báo giá")
    void cautiousSymptomClarification() {
        String[] cautious = {
                "Tôi hay bị đau đầu, khó ngủ và tê chân",
                "Dạo này tôi bị nhức đầu, tê chân thỉnh thoảng",
                "chân tôi hay tê bì mỗi khi ngồi lâu"
        };
        for (String m : cautious) {
            PrimaryActionRouter.RoutingDecision d = route(m, ConversationState.initial());
            assertThat(d.effectiveResponseAction()).as(m)
                    .isEqualTo(PrimaryActionRouter.PrimaryAction.ACKNOWLEDGE_AND_ASK_PREFERENCE);
            assertThat(d.detectedIntent()).as(m).isEqualTo("CAUTIOUS_SYMPTOM_CLARIFICATION");
        }
    }

    @Test
    @DisplayName("Triệu chứng thường (đau vai gáy) không bị kéo vào nhánh an toàn")
    void ordinaryAchesAreNotSafetyRouted() {
        PrimaryActionRouter.RoutingDecision d = route("Tôi bị đau mỏi vai gáy", ConversationState.initial());
        assertThat(d.effectiveResponseAction()).isNotEqualTo(PrimaryActionRouter.PrimaryAction.HARD_HANDOFF_SAFETY);
        assertThat(d.detectedIntent()).isNotEqualTo("CAUTIOUS_SYMPTOM_CLARIFICATION");
    }

    // ---------------------------------------------------------------- Catalog grounding / Zero hardcode
    @Test
    @DisplayName("Java core không còn fallback cứng tên gói/giá/thời lượng và không vá câu chữ rule bằng replace()")
    void noHardcodedCatalogFallbacksOrRuntimePatching() throws Exception {
        for (String f : List.of(
                "src/main/java/com/example/docmind/service/bot/FanpageBotPromptBuilder.java",
                "src/main/java/com/example/docmind/service/bot/PrimaryActionRouter.java")) {
            String src = Files.readString(Path.of(f));
            assertThat(src).as(f).doesNotContain("\"Massage cơ bản\"");
            assertThat(src).as(f).doesNotContain("\"399k\"");
            assertThat(src).as(f).doesNotContain("\"239k\"");
            assertThat(src).as(f).doesNotContain("\"Gói dưỡng sinh cơ bản\"");
            assertThat(src).as(f).doesNotContain(".replace(\"…\"");
            assertThat(src).as(f).doesNotContain(".replace(\"CẤM tự gán chữ");
            assertThat(src).as(f).doesNotContain(".replace(\"hỗ trợ giấc ngủ\"");
        }
    }

    // ---------------------------------------------------------------- Validator enforcement
    private static ConversationState symptomState() {
        return ConversationState.initial().enrichWithCurrentMessage("Tôi bị đau mỏi vai gáy, thỉnh thoảng khó ngủ", List.of());
    }

    @Test
    @DisplayName("ACI V2 Referee chặn (BLOCKING) mức giá dịch vụ không có trong Catalog mà KHÔNG tự ý viết lại con số trong bubble")
    void validatorBlocksPackageConfirmationQuestionWithoutRewriting() {
        List<ReplyBubble> bad = List.of(new ReplyBubble("Dạ bên em đang có gói Massage trị liệu 60 phút giá 999k ạ.", 0));
        List<String> violations = CleanAgentOutputRefereeAndSanitizer.checkAciV2TruthAuthorityAndSafety(
                bad, List.of(), List.of(), List.of(), null, null, List.of(), null,
                "thỉnh thoảng", false, null, null, null);
        assertThat(violations).contains("UNGROUNDED_PRICE_MENTIONED:999k");
        assertThat(bad.get(0).text()).isEqualTo("Dạ bên em đang có gói Massage trị liệu 60 phút giá 999k ạ.");
    }

    @Test
    @DisplayName("ACI V2 Referee chặn lặp xin lỗi hành chính rỗng kiểu tổng đài khi khách phàn nàn cách nói chuyện")
    void validatorBlocksEmptyTemplateApology() {
        String prevBot = "Dạ em rất xin lỗi mình vì sự bất tiện này ạ, mong mình thông cảm giúp em.";
        List<ReplyBubble> repeatedApology = List.of(new ReplyBubble(
                "Dạ em rất xin lỗi mình vì sự bất tiện vừa rồi ạ, mong mình thông cảm giúp em.", 0));
        List<String> violations = CleanAgentOutputRefereeAndSanitizer.checkAciV2TruthAuthorityAndSafety(
                repeatedApology, List.of(), List.of(), List.of(), null, null, List.of(), null,
                "Xin lỗi kiểu văn mẫu thế", false, null, null, null, List.of(), null, prevBot, List.of());
        assertThat(violations).contains("REPEATED_BOILERPLATE_APOLOGY_ON_STYLE_COMPLAINT");

        List<ReplyBubble> naturalFix = List.of(new ReplyBubble(
                "Dạ vừa rồi em diễn đạt hơi cứng nhắc, để em nói thẳng vào phần giá và quy trình mình đang hỏi nha.", 0));
        List<String> cleanViolations = CleanAgentOutputRefereeAndSanitizer.checkAciV2TruthAuthorityAndSafety(
                naturalFix, List.of(), List.of(), List.of(), null, null, List.of(), null,
                "Xin lỗi kiểu văn mẫu thế", false, null, null, null, List.of(), null, prevBot, List.of());
        assertThat(cleanViolations).doesNotContain("REPEATED_BOILERPLATE_APOLOGY_ON_STYLE_COMPLAINT");
    }

    // ---------------------------------------------------------------- History ledger
    @Test
    @DisplayName("Ledger Backend vá bong bóng bị thiếu/cắt cụt '…' trong payload history của client")
    void ledgerRepairsTruncatedClientHistory() {
        String key = SentBubbleLedger.key("p1", "u1", "c1");
        SentBubbleLedger.reset(key);
        SentBubbleLedger.recordCustomerTurn(key, "t1", "Giá");
        SentBubbleLedger.recordAssistantTurn(key, "t1",
                List.of("Dạ chào mình, em là trợ lý ạ.", "Bên em có massage và gội đầu.", "Mình đang quan tâm dịch vụ nào để em gửi giá nhé?"),
                SentBubbleLedger.DeliveryStatus.DELIVERED);
        List<ConversationTurn> client = List.of(
                new ConversationTurn("user", "Giá"),
                new ConversationTurn("assistant", "Dạ chào mình, em là trợ lý ạ.…"),
                new ConversationTurn("user", "Tôi đau vai"));
        List<ConversationTurn> merged = SentBubbleLedger.reconcile(key, client);
        assertThat(merged.get(1).content())
                .contains("Bên em có massage và gội đầu.")
                .contains("Mình đang quan tâm dịch vụ nào")
                .doesNotContain("…");
        assertThat(merged.get(0).content()).isEqualTo("Giá");
        SentBubbleLedger.reset(key);
    }
}
