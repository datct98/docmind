package com.example.docmind.service.bot;

import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.dto.bot.FanpageBotResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SBR-01 FIX-3: Java must not force '.'<->'?' on LLM sentences.
 */
class Sbr01Fix3PunctuationPreservationTest {

    private static String join(List<FanpageBotResponse.ReplyBubble> b) {
        return String.join(" ", b.stream().map(FanpageBotResponse.ReplyBubble::text).toList());
    }

    @Test
    @DisplayName("sanitizeBubbleText keeps a trailing '?' on a declarative-looking sentence (no '?' -> '.')")
    void sanitizeBubbleTextKeepsQuestionMark() {
        assertThat(BubbleTextSanitizer.sanitizeBubbleText("gói 239k là gội đầu và massage cổ vai gáy 60 phút ạ?"))
                .isEqualTo("Gói 239k là gội đầu và massage cổ vai gáy 60 phút ạ?");
        assertThat(BubbleTextSanitizer.sanitizeBubbleText("Mình muốn em tư vấn gói chăm sóc toàn thân hơn ạ?"))
                .endsWith("ạ?");
    }

    @Test
    @DisplayName("sanitizeBubbleText keeps a trailing '.' and still strips markdown / capitalizes")
    void sanitizeBubbleTextKeepsPeriodAndOtherHygiene() {
        assertThat(BubbleTextSanitizer.sanitizeBubbleText("**gói** 399k [1] là massage toàn thân ạ."))
                .isEqualTo("Gói 399k là massage toàn thân ạ.");
    }

    @Test
    @DisplayName("P37B2 on CASE-01 T11 input keeps the LLM's final '.' (baseline turned it into '?')")
    void p37b2KeepsTerminalPeriodOnT11() {
        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("user", "giá"),
                new FanpageBotRequest.ConversationTurn("assistant", "Gói dưỡng sinh cơ bản 60 phút 239k, Massage cơ bản 60 phút 399k. Mình ưu tiên gói nào ạ?"),
                new FanpageBotRequest.ConversationTurn("user", "thế rốt cuộc 2 gói khác j nhau"),
                new FanpageBotRequest.ConversationTurn("assistant", "Gói 239k là gội đầu + massage cổ vai gáy 60 phút, gói 399k là massage trị liệu toàn thân 60 phút ạ. Mình ưu tiên vùng nào hơn ạ?"));
        String llm = "Em xin lỗi nếu cách tư vấn khiến mình bực bực ạ. Gói 399k massage trị liệu toàn thân 60 phút sẽ chăm sóc cả cơ thể, không bỏ vùng nào ạ.";
        List<FanpageBotResponse.ReplyBubble> out = CleanAgentOutputRefereeAndSanitizer.enforceP37B2TurnActionAndBubbleIdeaDisciplineInPlace(
                List.of(new FanpageBotResponse.ReplyBubble(llm, 0)),
                "Đã mệt rồi còn hỏi vùng nào chăm sóc hơn, chăm 1 vùng rồi bỏ các vùng khác à",
                history, null, List.of(), "em", "mình", "kỹ thuật viên");
        assertThat(join(out)).endsWith("không bỏ vùng nào ạ.").doesNotContain("vùng nào ạ?");
    }

    @Test
    @DisplayName("P37B2 keeps a declarative choice sentence ending with '.' as '.' on a recommendation turn")
    void p37b2DoesNotRestoreQuestionMark() {
        String llm = "Gói 580k là Massage phục hồi 100 phút, gói 670k là Massage phục hồi + dưỡng sinh 120 phút. Mình muốn trải nghiệm nhẹ nhàng hay chuyên sâu hơn ạ.";
        List<FanpageBotResponse.ReplyBubble> out = CleanAgentOutputRefereeAndSanitizer.enforceP37B2TurnActionAndBubbleIdeaDisciplineInPlace(
                List.of(new FanpageBotResponse.ReplyBubble(llm, 0)),
                "tôi mỏi cổ vai gáy, có gói nào", List.of(), null, List.of(), "em", "mình", "kỹ thuật viên");
        assertThat(join(out)).endsWith("chuyên sâu hơn ạ.");
    }

    @Test
    @DisplayName("P37B2 keeps a genuine LLM question '?' and the bubble structure")
    void p37b2KeepsGenuineQuestion() {
        List<FanpageBotResponse.ReplyBubble> in = List.of(
                new FanpageBotResponse.ReplyBubble("Gói 399k là massage toàn thân 60 phút ạ.", 0),
                new FanpageBotResponse.ReplyBubble("Mình muốn đặt khung giờ nào ạ?", 0));
        List<FanpageBotResponse.ReplyBubble> out = CleanAgentOutputRefereeAndSanitizer.enforceP37B2TurnActionAndBubbleIdeaDisciplineInPlace(
                in, "giá massage toàn thân", List.of(), null, List.of(), "em", "mình", "kỹ thuật viên");
        assertThat(out).hasSize(2);
        assertThat(out.get(0).text()).isEqualTo("Gói 399k là massage toàn thân 60 phút ạ.");
        assertThat(out.get(1).text()).isEqualTo("Mình muốn đặt khung giờ nào ạ?");
    }
}
