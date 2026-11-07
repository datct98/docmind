package com.example.docmind.service.salescore;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static com.example.docmind.service.salescore.SalesMemory.*;
import static org.assertj.core.api.Assertions.*;

class SalesMemoryTest {
    private final Scope scope = new Scope(UUID.randomUUID(), "customer", "session");
    @Test void factsOutliveWindowAndSerializedProcessStateThenCorrectionSupersedes() {
        var store = new InMemoryStore();
        var first = store.begin(scope, "Tôi chỉ muốn Vision, không lấy xe số", false);
        var saved = SalesMemory.apply(first.view(), List.of(new Update("vehicle_preference", "Chỉ Vision, không xe số",
                "Tôi chỉ muốn Vision, không lấy xe số", 1, false)));
        assertThat(store.commit(first, saved)).isTrue();
        Lease latest = first;
        for (int i=0; i<45; i++) latest = store.begin(scope, "Tôi đang cân nhắc thêm", false);
        var json = new ObjectMapper();
        View restarted = json.readValue(json.writeValueAsString(latest.view()), View.class);
        assertThat(restarted.customerSources()).noneMatch(s -> s.turn() == 1);
        assertThat(restarted.facts().getFirst().value()).isEqualTo("Chỉ Vision, không xe số");
        View next = SalesMemory.advance(restarted, "Đổi sang xe số nhé", false);
        View corrected = SalesMemory.apply(next, List.of(new Update("vehicle_preference", "xe số", "Đổi sang xe số nhé", next.revision(), false)));
        assertThat(corrected.facts()).extracting(Fact::value).containsExactly("xe số");
        assertThat(corrected.changes()).extracting(Change::previousValue).containsExactly("Chỉ Vision, không xe số");
    }
    @Test void lateResultCannotOverwriteNewTurnOrSessionReset() {
        var store = new InMemoryStore();
        var old = store.begin(scope, "Hà Nội", false);
        var current = store.begin(scope, "Sửa lại TP.HCM", false);
        assertThat(store.commit(old, old.view())).isFalse();
        var reset = store.begin(scope, "Chào", true);
        assertThat(store.commit(current, current.view())).isFalse();
        assertThat(reset.view().facts()).isEmpty();
        assertThat(reset.view().customerSources()).extracting(Source::text).containsExactly("Chào");
        assertThat(reset.view().revision()).isGreaterThan(current.view().revision());
    }
    @Test void pageSenderAndConversationAreIndependentAndDelimiterCannotAlias() {
        var store = new InMemoryStore();
        store.begin(scope, "Tôi muốn riêng tư", false);
        for (Scope other : List.of(new Scope(UUID.randomUUID(), scope.senderId(), scope.conversationId()),
                new Scope(scope.pageId(), "another", scope.conversationId()), new Scope(scope.pageId(), scope.senderId(), "another")))
            assertThat(store.begin(other, "Chào", false).view().customerSources()).extracting(Source::text).containsExactly("Chào");
        assertThat(new Scope(scope.pageId(), "a|b", "c").key()).isNotEqualTo(new Scope(scope.pageId(), "a", "b|c").key());
    }
    @Test void fabricatedQuotesFutureSourcesDuplicateKeysAndStaleCorrectionsAreRejectedAtomically() {
        View v = SalesMemory.advance(View.empty(), "Hà Nội", false);
        v = SalesMemory.advance(v, "TP.HCM", false);
        View memory = SalesMemory.apply(v, List.of(new Update("location", "TP.HCM", "TP.HCM", 2, false)));
        for (var patches : List.of(
                List.of(new Update("budget", "300k", "300k", 2, false)),
                List.of(new Update("location", "TP.HCM", "TP.HCM", 999, false)),
                List.of(new Update("location", "Hà Nội", "Hà Nội", 1, false)),
                List.of(new Update("location", "TP.HCM", "TP.HCM", 2, false), new Update("location", "TP.HCM", "TP.HCM", 2, false))))
            assertThatThrownBy(() -> SalesMemory.apply(memory, patches)).isInstanceOf(IllegalArgumentException.class);
        assertThat(memory.facts().getFirst().value()).isEqualTo("TP.HCM");
    }
    @Test void withdrawalNeedsAnExplicitCustomerSourceAndRetainsAudit() {
        View v = SalesMemory.advance(View.empty(), "Chỉ nhận ở Hoàn Kiếm", false);
        v = SalesMemory.apply(v, List.of(new Update("location", "Hoàn Kiếm", "Chỉ nhận ở Hoàn Kiếm", 1, false)));
        v = SalesMemory.advance(v, "Không cần giới hạn chỗ nhận nữa", false);
        v = SalesMemory.apply(v, List.of(new Update("location", null, "Không cần giới hạn chỗ nhận nữa", 2, true)));
        assertThat(v.facts()).isEmpty();
        assertThat(v.changes().getFirst().newValue()).isNull();
    }
}
