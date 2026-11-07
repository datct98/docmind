package com.example.docmind.service.salescore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static com.example.docmind.service.salescore.SalesMemory.*;
import static org.assertj.core.api.Assertions.*;

/** Opt-in real PostgreSQL test, after Flyway migration. Uses and removes only its own synthetic page. */
@EnabledIfSystemProperty(named="sales.jdbc", matches="true")
class JdbcSalesMemoryStoreTest {
    @Test void newStoreRecoversMemoryAndConcurrentBeginsSerializeWithoutStaleCommit() throws Exception {
        var ds = new DriverManagerDataSource(System.getProperty("sales.jdbc.url", "jdbc:postgresql://localhost:5433/docmind"),
                System.getenv().getOrDefault("SALES_TEST_DB_USER", "docmind"), System.getenv().getOrDefault("SALES_TEST_DB_PASSWORD", "docmind"));
        var jdbc = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        UUID page = UUID.randomUUID();
        jdbc.update("INSERT INTO knowledge_pages(id,name) VALUES (?,?)", page, "Sales memory isolated test");
        var scope = new Scope(page, "test-customer", "test-session");
        try {
            var store = new JdbcSalesMemoryStore(jdbc);
            Lease first = tx.execute(s -> store.begin(scope, "Chỉ muốn Vision", false));
            assertThat(store.commit(first, SalesMemory.apply(first.view(), List.of(new Update("vehicle_preference", "Vision", "Chỉ muốn Vision", 1, false))))).isTrue();
            var restarted = new JdbcSalesMemoryStore(new JdbcTemplate(ds));
            Lease second = tx.execute(s -> restarted.begin(scope, "Nhận sáng chủ nhật", false));
            assertThat(second.view().facts()).extracting(Fact::value).containsExactly("Vision");
            assertThat(store.commit(first, first.view())).isFalse();
            try (var pool = Executors.newFixedThreadPool(2)) {
                var start = new CountDownLatch(1);
                Callable<Lease> task = () -> { start.await(); return tx.execute(s -> restarted.begin(scope, "Thêm thông tin", false)); };
                Future<Lease> a = pool.submit(task), b = pool.submit(task);
                start.countDown();
                var leases = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)).stream()
                        .sorted(Comparator.comparingLong(l -> l.view().revision())).toList();
                assertThat(leases).extracting(l -> l.view().revision()).containsExactly(3L, 4L);
                assertThat(store.commit(leases.getFirst(), leases.getFirst().view())).isFalse();
                assertThat(store.commit(leases.getLast(), leases.getLast().view())).isTrue();
                assertThat(leases.getLast().view().facts()).extracting(Fact::value).containsExactly("Vision");
            }
            Lease reset = tx.execute(s -> restarted.begin(scope, "Bắt đầu lại", true));
            assertThat(reset.view().facts()).isEmpty();
            assertThat(restarted.commit(second, second.view())).isFalse();
        } finally {
            jdbc.update("DELETE FROM knowledge_pages WHERE id=?", page);
        }
    }
}
