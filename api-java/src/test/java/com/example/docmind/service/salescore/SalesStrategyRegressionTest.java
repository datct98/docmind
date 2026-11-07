package com.example.docmind.service.salescore;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.example.docmind.service.salescore.SalesContracts.*;
import static org.assertj.core.api.Assertions.*;

class SalesStrategyRegressionTest {
    @Test void proseOutcomeCannotEraseAQuotedStopSignalFromAnObservedModelResponse() throws Exception {
        String initial = "Vai gáy tôi mỏi, ở Long Biên. Báo giúp gói phù hợp.";
        String stop = "Bạn hỏi nhiều quá. Thôi khỏi tư vấn, dừng ở đây đi.";
        var memory = SalesMemory.advance(SalesMemory.advance(SalesMemory.View.empty(), initial, false), stop, false);
        try (var source = getClass().getResourceAsStream("/salescore/stop-malformed-outcome.json")) {
            var draft = SalesConsultationDraft.parse(new String(source.readAllBytes(), StandardCharsets.UTF_8));
            assertThat(draft.brief().memoryProposalRejected()).isTrue();
            var reviewed = SalesJourney.validate(draft.brief().journey(), memory);
            assertThat(reviewed.engagement()).isEqualTo(SalesJourney.Engagement.STOPPED);
            assertThat(reviewed.engagementSignal().sourceTurn()).isEqualTo(2);
            assertThat(reviewed.observedOutcome()).isNull();
            // Dropping malformed annotations cannot turn an invented STOP quote into authority.
            var foreign = new SalesJourney.State(reviewed.primaryGoal(), reviewed.openLoops(), null, List.of(),
                    reviewed.engagement(), new SalesJourney.Claim("stop", "lời không có", 2), "", null, "");
            assertThatThrownBy(() -> SalesJourney.validate(foreign, memory)).hasMessageContaining("Unsupported journey claim");
        }
    }

    @Test void customerAmountNeedsExplicitAttributionAndCannotAuthorizeAnUncitedMerchantPrice() {
        var input = new TurnInput("p", "Shop", "GENERIC", "em", "mình", "gọn", List.of(),
                "Bên cạnh báo 300 nghìn", List.of(new Evidence("price", "Giá", "Gói cơ bản 399k")), List.of(), "", false);
        var strategy = new Strategy(Action.COMPARE, "So quyền lợi", "Có căn cứ lựa chọn");
        var understanding = new Understanding("So sánh", List.of(), List.of(), "chưa rõ");
        var attributed = new TurnDecision(understanding, strategy, List.of(new Bubble("Mức 300 nghìn mình nói bên kia bao gồm gì?",
                List.of(new Citation("CUSTOMER", input.message())))), "", List.of());
        var validator = new SalesDecisionValidator();
        assertThat(validator.validate(attributed, input)).doesNotContain("UNSUPPORTED_NUMBER", "INVALID_CUSTOMER_REFERENCE");
        var uncited = new TurnDecision(understanding, strategy, List.of(new Bubble("Giá bên em 300 nghìn.", List.of())), "", List.of());
        assertThat(validator.validate(uncited, input)).contains("UNSUPPORTED_NUMBER");
        var forged = new TurnDecision(understanding, strategy, List.of(new Bubble("Mức 250k mình nói", List.of(new Citation("CUSTOMER", "250k")))), "", List.of());
        assertThat(validator.validate(forged, input)).contains("INVALID_CUSTOMER_REFERENCE", "UNSUPPORTED_NUMBER");
    }
}

