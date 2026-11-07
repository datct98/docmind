package com.example.docmind.service.salescore;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static com.example.docmind.service.salescore.SalesMemory.*;

/** One short row lock per inbound event. No transaction/lock is held while waiting for an LLM. */
@Repository
public class JdbcSalesMemoryStore implements Store {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    public JdbcSalesMemoryStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override @Transactional
    public Lease begin(Scope scope, String message, boolean reset) {
        jdbc.update("INSERT INTO sales_customer_memory(page_id,scope_key,revision,snapshot) VALUES (?,?,0,CAST(? AS jsonb)) ON CONFLICT DO NOTHING",
                scope.pageId(), scope.key(), json.writeValueAsString(View.empty()));
        String raw = jdbc.queryForObject("SELECT snapshot::text FROM sales_customer_memory WHERE page_id=? AND scope_key=? FOR UPDATE",
                String.class, scope.pageId(), scope.key());
        View next = SalesMemory.advance(json.readValue(raw, View.class), message, reset);
        jdbc.update("UPDATE sales_customer_memory SET revision=?,snapshot=CAST(? AS jsonb),updated_at=now() WHERE page_id=? AND scope_key=?",
                next.revision(), json.writeValueAsString(next), scope.pageId(), scope.key());
        return new Lease(scope, next);
    }
    @Override
    public boolean commit(Lease lease, View memory) {
        if (memory.revision() != lease.view().revision()) return false;
        return jdbc.update("UPDATE sales_customer_memory SET snapshot=CAST(? AS jsonb),updated_at=now() WHERE page_id=? AND scope_key=? AND revision=?",
                json.writeValueAsString(memory), lease.scope().pageId(), lease.scope().key(), lease.view().revision()) == 1;
    }
}
