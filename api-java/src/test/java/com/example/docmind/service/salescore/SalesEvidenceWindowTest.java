package com.example.docmind.service.salescore;

import java.util.*;
import org.junit.jupiter.api.Test;
import static com.example.docmind.service.salescore.SalesContracts.*;
import static org.assertj.core.api.Assertions.*;

class SalesEvidenceWindowTest {
    @Test void followUpKeepsApplicableTermsAmongManyMatchingLocationPrices() {
        var evidence = new ArrayList<Evidence>();
        for (int i = 0; i < 30; i++) evidence.add(new Evidence("offer-" + i, "Chi nhánh A B giá gói " + i,
                "Chi nhánh A B cung cấp gói dịch vụ. " + "Chi tiết dịch vụ. ".repeat(10), Map.of("record_kind", "PRICE")));
        var terms = new Evidence("terms", "Thẻ liệu trình", "Mua tại cơ sở nào chỉ sử dụng tại cơ sở đó.", Map.of("record_kind", "POLICY"));
        evidence.add(terms);
        var history = List.of(new Message("user", "Mua thẻ liệu trình có giảm không?"),
                new Message("assistant", "Thẻ liệu trình mua tại một cơ sở sử dụng tại cơ sở đó."));
        var input = new TurnInput("page", "Shop", "GENERIC", "em", "mình", "gọn", history,
                "Mua ở A dùng B được không?", evidence, List.of(), "", false);
        var selected = SalesEvidenceWindow.select(input, 1400);
        assertThat(selected.evidence()).contains(terms).anyMatch(e -> e.id().startsWith("offer-"));
        assertThat(selected.truncated()).isTrue();
    }
    @Test void priorAssistantClaimDoesNotCreateBusinessEvidenceOrOverrideCurrentSource() {
        var source = new Evidence("current", "Vision", "Vision 220k/ngày.");
        var input = new TurnInput("page", "Shop", "GENERIC", "em", "mình", "gọn",
                List.of(new Message("assistant", "Vision chỉ 50k, đã giữ xe.")), "Giá hiện tại?",
                List.of(source), List.of(), "", false);
        assertThat(SalesEvidenceWindow.select(input, 1400).evidence()).containsExactly(source);
        assertThat(SalesEvidenceWindow.select(input, 1400).evidence().getFirst().text()).doesNotContain("50k", "giữ xe");
    }
}
