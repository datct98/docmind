package com.example.docmind.service.bot;

import com.example.docmind.dto.bot.FanpageBotRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SBR-01 FIX-2: history serialization must not use " | " between bot bubbles.
 */
class Sbr01Fix2HistorySerializationTest {

    private static String render(List<FanpageBotRequest.ConversationTurn> history) {
        StringBuilder sb = new StringBuilder();
        CleanAgentCatalogAndPromptBuilder.appendTokenBudgetedConversationHistory(
                sb, history, "</CONVERSATION_HISTORY>", null, null, List.of(), null, false);
        return sb.toString();
    }

    @Test
    @DisplayName("Multi-bubble bot turn is rendered on continuation lines, never with ' | '")
    void multiBubbleBotTurnUsesNewlinesNotPipe() {
        String out = render(List.of(
                new FanpageBotRequest.ConversationTurn("user", "giá"),
                new FanpageBotRequest.ConversationTurn("assistant",
                        "Nàng Sen chào mình ạ 🌷 Rất vui vì hôm nay mình ghé qua đây.\n\nGói dưỡng sinh cơ bản 60 phút — 239k. Mình ưu tiên gói nào ạ?"),
                new FanpageBotRequest.ConversationTurn("user", "tôi hoa mắt ù tai mệt mỏi"),
                new FanpageBotRequest.ConversationTurn("assistant", "Bubble A.\n\n\nBubble B.\n \nBubble C?")
        ));
        assertThat(out).doesNotContain(" | ");
        assertThat(out).contains("[T1] Khách: giá\n");
        assertThat(out).contains("[T2] Bot: Nàng Sen chào mình ạ 🌷 Rất vui vì hôm nay mình ghé qua đây.\n    Gói dưỡng sinh cơ bản 60 phút — 239k. Mình ưu tiên gói nào ạ?\n");
        assertThat(out).contains("[T3] Khách: tôi hoa mắt ù tai mệt mỏi\n");
        assertThat(out).contains("[T4] Bot: Bubble A.\n    Bubble B.\n    Bubble C?\n");
        // order of turns and of bubbles preserved
        assertThat(out.indexOf("[T1]")).isLessThan(out.indexOf("[T2]"));
        assertThat(out.indexOf("Bubble A.")).isLessThan(out.indexOf("Bubble B.")).isLessThan(out.indexOf("Bubble C?"));
        assertThat(out).startsWith("<CONVERSATION_HISTORY>\n").endsWith("</CONVERSATION_HISTORY>");
    }

    @Test
    @DisplayName("Literal '|' typed by the customer (or inside a bot bubble) is preserved verbatim")
    void literalPipeContentIsPreserved() {
        String out = render(List.of(
                new FanpageBotRequest.ConversationTurn("user", "gói A | gói B khác gì nhau?"),
                new FanpageBotRequest.ConversationTurn("assistant", "Bảng: 239k | 399k\n\nMình chọn gói nào ạ?")
        ));
        assertThat(out).contains("[T1] Khách: gói A | gói B khác gì nhau?\n");
        assertThat(out).contains("[T2] Bot: Bảng: 239k | 399k\n    Mình chọn gói nào ạ?\n");
        // the only ' | ' occurrences are the ones the users/bot actually typed (2), none added as delimiter
        assertThat(out.split(" \\| ", -1).length - 1).isEqualTo(2);
    }

    @Test
    @DisplayName("Single-bubble and blank/edge inputs")
    void edgeCases() {
        assertThat(CleanAgentCatalogAndPromptBuilder.renderBotBubblesForHistory(null)).isEmpty();
        assertThat(CleanAgentCatalogAndPromptBuilder.renderBotBubblesForHistory("  Một bong bóng.  ")).isEqualTo("Một bong bóng.");
        assertThat(CleanAgentCatalogAndPromptBuilder.renderBotBubblesForHistory("Dòng 1\nDòng 2")).isEqualTo("Dòng 1\nDòng 2");
        assertThat(CleanAgentCatalogAndPromptBuilder.renderBotBubblesForHistory("A\r\n\r\nB")).isEqualTo("A\n    B");
    }
}
