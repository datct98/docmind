package com.example.docmind.service.bot;

import com.example.docmind.dto.bot.FanpageBotRequest.ConversationTurn;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kiểm thử bền vững THẬT sau restart: ghi bằng một store/JdbcTemplate, xoá cache, dựng store MỚI trên cùng PostgreSQL
 * rồi khôi phục transcript. Cần PostgreSQL thật (docmind-postgres); không có thì test bị SKIP (báo riêng, không tính pass).
 */
class SentBubbleLedgerDurabilityTest {

    private static String reachableUrl() {
        String fromEnv = System.getenv("SENT_LEDGER_TEST_JDBC_URL");
        List<String> candidates = fromEnv != null ? List.of(fromEnv) : List.of(
                "jdbc:postgresql://localhost:5433/docmind",
                "jdbc:postgresql://host.docker.internal:5433/docmind");
        for (String url : candidates) {
            try (Connection c = DriverManager.getConnection(url, "docmind", "docmind")) {
                return url;
            } catch (Exception ignored) {
                // thử ứng viên kế tiếp
            }
        }
        return null;
    }

    @Test
    @DisplayName("Ledger bền vững: khôi phục đủ user/assistant đúng thứ tự sau restart thật, chống ghi trùng, reset xoá cả store")
    void durableLedgerSurvivesRestart() throws Exception {
        String url = reachableUrl();
        Assumptions.assumeTrue(url != null, "SKIPPED: không kết nối được PostgreSQL thật cho test bền vững");

        DriverManagerDataSource ds = new DriverManagerDataSource(url, "docmind", "docmind");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/V19__sent_bubble_ledger.sql")));

        String key = SentBubbleLedger.key("page-" + UUID.randomUUID(), "sender-1", "conv-1");
        try {
            JdbcSentBubbleStore store1 = new JdbcSentBubbleStore(jdbc);
            store1.install();
            SentBubbleLedger.recordCustomerTurn(key, "t1", "Giá");
            SentBubbleLedger.recordAssistantTurn(key, "t1:a", List.of("B1", "B2", "B3"), SentBubbleLedger.DeliveryStatus.DELIVERED);
            SentBubbleLedger.recordCustomerTurn(key, "t2", "Tôi đau vai");
            SentBubbleLedger.recordAssistantTurn(key, "t2:a", List.of("Đồng cảm"), SentBubbleLedger.DeliveryStatus.DELIVERED,
                    List.of("Bong bóng huỷ?"));
            // webhook retry: cùng turn id ghi lại
            SentBubbleLedger.recordCustomerTurn(key, "t2", "Tôi đau vai");
            SentBubbleLedger.recordAssistantTurn(key, "t2:a", List.of("Đồng cảm"), SentBubbleLedger.DeliveryStatus.DELIVERED,
                    List.of("Bong bóng huỷ?"));

            Integer rows = jdbc.queryForObject("SELECT count(*) FROM sent_bubble_ledger WHERE ledger_key = ?", Integer.class, key);
            assertThat(rows).as("1+3 + 1+2 bubble, không nhân đôi khi retry").isEqualTo(7);

            // ---- RESTART THẬT: mất cache + store mới trên cùng database ----
            SentBubbleLedger.dropCacheOnly();
            JdbcSentBubbleStore store2 = new JdbcSentBubbleStore(new JdbcTemplate(ds));
            store2.install();

            List<ConversationTurn> restored = SentBubbleLedger.reconcile(key, List.of());
            assertThat(restored).extracting(ConversationTurn::role).containsExactly("user", "assistant", "user", "assistant");
            assertThat(restored.get(0).content()).isEqualTo("Giá");
            assertThat(restored.get(1).content()).isEqualTo("B1\nB2\nB3");
            assertThat(restored.get(3).content()).isEqualTo("Đồng cảm");
            assertThat(store2.load(key).get(3).bubbles()).extracting(SentBubbleLedger.BubbleRecord::status)
                    .containsExactly(SentBubbleLedger.DeliveryStatus.DELIVERED, SentBubbleLedger.DeliveryStatus.CANCELED);

            // client truncated + non-empty vẫn được vá từ DB sau restart
            SentBubbleLedger.dropCacheOnly();
            List<ConversationTurn> merged = SentBubbleLedger.reconcile(key, List.of(
                    new ConversationTurn("user", "Giá"), new ConversationTurn("assistant", "B1…")));
            assertThat(merged.get(1).content()).isEqualTo("B1\nB2\nB3");

            // resetSession xoá cả storage lẫn cache
            SentBubbleLedger.reset(key);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM sent_bubble_ledger WHERE ledger_key = ?", Integer.class, key)).isZero();
            assertThat(SentBubbleLedger.snapshot(key)).isEmpty();
        } finally {
            jdbc.update("DELETE FROM sent_bubble_ledger WHERE ledger_key = ?", key);
            SentBubbleLedger.useStore(new SentBubbleLedger.InMemoryStore());
        }
    }
}
