package com.example.docmind.service.bot;

import com.docmind.guardrail.ReplyContractValidator;
import com.example.docmind.dto.bot.FanpageBotResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate 1 Mandatory Unit Tests (v3.6.7.1-MASTER):
 * - PendingBubbleCancelTest (Hủy Bubble 2 đang chờ delay 10s khi khách nhắn chen ngang ở giây thứ 3)
 * - testScopeNegationCanBeSupersededByLaterExplicitConfirmation (Vòng đời forbiddenScopes: NEGATED -> SUPERSEDED)
 * - testSameErrorMustNotBeApologizedTwice (ApologyLedger chặn lặp xin lỗi cùng 1 errorId)
 * - testNewErrorMayBeApologizedEvenIfPreviousTurnHadApology (Cho phép xin lỗi khi phát sinh errorId mới)
 * - testOperationalPromiseCreatesNoteArtifact (Tự động tạo CustomerCareNoteArtifact khi hứa vận hành & chặn hứa khi thiếu confirmedScopes)
 * - testGroundingCheckCannotOverrideKernelConfirmedStateWithHallucination (Quyền sở hữu State Bất đối xứng)
 */
class PendingBubbleCancelTest {

    private static final UUID TEST_TENANT_ID = UUID.fromString("b23cd382-4f7a-4829-8c54-1d410da14bf8");

    @Test
    @DisplayName("1. PendingBubbleCancelTest: Hủy Bubble 2 đang chờ delay 10s khi khách nhắn chen ngang ở giây thứ 3")
    void testPendingBubbleCancelledWhenNewCustomerMessageArrivesDuringPostPriceDelay() {
        String senderId = "user-cancel-bubble-" + UUID.randomUUID();
        int priceTurnSeq = 3;
        long priceShownAtNs = System.nanoTime() - 3_000_000_000L; // 3 seconds ago

        // Turn 3: Bot vừa gửi Bubble 1 báo giá và đặt lịch chờ Bubble 2 (delay >= 10000ms)
        ThinkingModePolicy.markPriceBubble1ShownAndBubble2Pending(TEST_TENANT_ID, senderId, priceTurnSeq, priceShownAtNs);
        assertFalse(ThinkingModePolicy.isPendingSecondBubbleSuperseded(TEST_TENANT_ID, senderId, priceTurnSeq),
                "Trước khi khách nhắn chen ngang, Bubble 2 chưa bị hủy");

        // Giây thứ 3: Khách nhắn chen ngang ở Turn 4 -> hủy ngay Bubble 2 đang chờ của Turn 3
        long newSeq = ThinkingModePolicy.registerIncomingCustomerTurn(TEST_TENANT_ID, senderId);
        assertTrue(newSeq > 0L, "Khi khách nhắn chen ngang ở giây thứ 3, lượt mới phải được ghi nhận");
        assertTrue(ThinkingModePolicy.isPendingSecondBubbleSuperseded(TEST_TENANT_ID, senderId, priceTurnSeq),
                "isPendingSecondBubbleSuperseded phải trả về true cho lượt báo giá cũ");

        List<FanpageBotResponse.ReplyBubble> candidateBubbles = List.of(
                new FanpageBotResponse.ReplyBubble("Dạ gói Massage trị liệu cơ bản 60 phút có giá 399k ạ.", 0),
                new FanpageBotResponse.ReplyBubble("Liệu trình giúp thư giãn vùng đầu và vai rất êm ạ. Mình muốn đặt lịch hôm nay hay ngày mai?", 10000)
        );
        ThinkingModePolicy.PostPriceDelayMetrics delayMetrics = ThinkingModePolicy.computeDynamicPostPriceDelay(
                10000, priceShownAtNs, System.nanoTime()
        );
        List<FanpageBotResponse.ReplyBubble> aligned = FanpageBotService.alignBubbleDelaysWithContract(
                candidateBubbles,
                List.of(),
                delayMetrics,
                ThinkingModePolicy.isPendingSecondBubbleSuperseded(TEST_TENANT_ID, senderId, priceTurnSeq)
        );
        assertEquals(1, aligned.size(), "Bubble 2 đang chờ delay 10s phải bị cắt bỏ khi khách nhắn chen ngang");
        assertTrue(aligned.get(0).text().contains("399k"));
    }

    @Test
    @DisplayName("2. testScopeNegationCanBeSupersededByLaterExplicitConfirmation: Phủ định ở T3 được gỡ bỏ khi khách chủ động xác nhận lại ở T9")
    void testScopeNegationCanBeSupersededByLaterExplicitConfirmation() {
        List<String> spaTaxonomy = List.of("đầu", "vai", "cổ", "gáy", "lưng", "chân", "bắp chân", "đầu gối", "giấc ngủ");
        ConversationState stateT2 = ConversationState.empty()
                .enrichWithCurrentMessage("Tôi bị đau đầu khó ngủ mỏi vai", spaTaxonomy);

        // Turn 3: Khách phủ định "Bị điên à, đau đầu chứ đầu gối đâu"
        ConversationState stateT3 = stateT2.enrichWithCurrentMessage("Bị điên à, đau đầu chứ đầu gối đâu", spaTaxonomy);
        assertTrue(stateT3.forbiddenScopes().contains("đầu gối"), "Turn 3: 'đầu gối' phải nằm trong forbiddenScopes (NEGATED)");
        assertFalse(stateT3.confirmedScopes().contains("đầu gối"), "Turn 3: 'đầu gối' không được nằm trong confirmedScopes");
        assertTrue(stateT3.confirmedScopes().containsAll(List.of("đầu", "vai", "giấc ngủ")));

        // Turn 9: Khách chủ động xác nhận lại trong một câu khẳng định không phủ định
        ConversationState stateT9 = stateT3.enrichWithCurrentMessage("À giờ tôi thấy đau cả đầu gối luôn, làm thêm cả đầu gối nữa nhé", spaTaxonomy);
        assertTrue(stateT9.confirmedScopes().contains("đầu gối"), "Turn 9: 'đầu gối' phải được nạp vào confirmedScopes");
        assertFalse(stateT9.forbiddenScopes().contains("đầu gối"),
                "Turn 9: 'đầu gối' phải được gỡ khỏi forbiddenScopes (trạng thái SUPERSEDED)");
    }

    @Test
    @DisplayName("3. testSameErrorMustNotBeApologizedTwice: Cấm lặp lại xin lỗi cho cùng một errorId đã nằm trong ApologyLedger")
    void testSameErrorMustNotBeApologizedTwice() {
        List<String> spaTaxonomy = List.of("đầu", "vai", "đầu gối", "giấc ngủ");
        ConversationState state = ConversationState.empty()
                .enrichWithCurrentMessage("Tôi bị đau đầu khó ngủ mỏi vai", spaTaxonomy)
                .enrichWithCurrentMessage("Đau đầu chứ đầu gối đâu", spaTaxonomy);

        // Giả lập Turn 3 bot đã xin lỗi lỗi WRONG_SCOPE:đầu gối
        PrimaryActionRouter.RoutingDecision repairDecision = new PrimaryActionRouter.RoutingDecision(
                PrimaryActionRouter.PrimaryAction.REPAIR_AND_RECONFIRM,
                PrimaryActionRouter.PrimaryAction.REPAIR_AND_RECONFIRM,
                false,
                2,
                "REPAIR_AND_RECONFIRM",
                "spa_frustration_empathy_rescue"
        );
        ConversationState afterTurn3 = ConversationState.updateAfterSend(
                TEST_TENANT_ID,
                "sender-apology-ledger",
                "conv-apology-ledger",
                state,
                "Đau đầu chứ đầu gối đâu",
                repairDecision,
                List.of(new FanpageBotResponse.ReplyBubble("Dạ em xin lỗi vì đã nghe nhầm sang vùng đầu gối của mình ạ. Bên em sẽ tập trung chăm sóc vùng đầu, vai và giấc ngủ cho mình nhé.", 0)),
                false,
                2
        );
        assertTrue(afterTurn3.apologizedErrorIds().contains("WRONG_SCOPE:đầu gối"),
                "ApologyLedger phải ghi nhận WRONG_SCOPE:đầu gối sau Turn 3");

        // Turn 4: Khách vẫn bực ("Dở vừa, ns linh ta linh tinh") nhưng KHÔNG có lỗi nhầm vùng mới
        List<FanpageBotResponse.ReplyBubble> duplicateApologyBubbles = List.of(
                new FanpageBotResponse.ReplyBubble("Dạ em thành thật xin lỗi mình một lần nữa vì sự sơ suất vừa rồi ạ.", 0),
                new FanpageBotResponse.ReplyBubble("Khi mình ghé, em sẽ dặn kỹ thuật viên chăm sóc kỹ vùng đầu và vai cho mình nha.", 2000)
        );
        ReplyContractValidator.ValidationResult valRes = ReplyContractValidator.validateAndEnforce(
                duplicateApologyBubbles,
                afterTurn3,
                "Dở vừa, ns linh ta linh tinh",
                PrimaryActionRouter.PrimaryAction.DIRECT_CONCRETE_SOLUTION,
                false,
                2000,
                ThinkingMode.FAST_NO_THINK,
                0L
        );
        assertTrue(valRes.errors().contains("DUPLICATE_ERROR_APOLOGY"),
                "ReplyContractValidator phải bắt lỗi Hard Gate DUPLICATE_ERROR_APOLOGY khi xin lỗi lặp lại cùng errorId");
    }

    @Test
    @DisplayName("4. testNewErrorMayBeApologizedEvenIfPreviousTurnHadApology: Cho phép nhận lỗi nếu phát sinh errorId MỚI")
    void testNewErrorMayBeApologizedEvenIfPreviousTurnHadApology() {
        List<String> spaTaxonomy = List.of("đầu", "vai", "lưng", "đầu gối", "giấc ngủ");
        ConversationState state = ConversationState.empty()
                .enrichWithCurrentMessage("Tôi bị đau đầu khó ngủ mỏi vai", spaTaxonomy)
                .enrichWithCurrentMessage("Đau đầu chứ đầu gối đâu", spaTaxonomy);

        PrimaryActionRouter.RoutingDecision repairDecision = new PrimaryActionRouter.RoutingDecision(
                PrimaryActionRouter.PrimaryAction.REPAIR_AND_RECONFIRM,
                PrimaryActionRouter.PrimaryAction.REPAIR_AND_RECONFIRM,
                false,
                2,
                "REPAIR_AND_RECONFIRM",
                "spa_frustration_empathy_rescue"
        );
        ConversationState afterTurn3 = ConversationState.updateAfterSend(
                TEST_TENANT_ID,
                "sender-new-error",
                "conv-new-error",
                state,
                "Đau đầu chứ đầu gối đâu",
                repairDecision,
                List.of(new FanpageBotResponse.ReplyBubble("Dạ em xin lỗi vì nhầm sang vùng đầu gối ạ.", 0)),
                false,
                2
        );
        assertTrue(afterTurn3.apologizedErrorIds().contains("WRONG_SCOPE:đầu gối"));

        // Lượt sau phát sinh lỗi MỚI: nhầm sang "lưng" ("Đau vai chứ lưng đâu") -> activeErrorId = WRONG_SCOPE:lưng
        List<FanpageBotResponse.ReplyBubble> newErrorApologyBubbles = List.of(
                new FanpageBotResponse.ReplyBubble("Dạ em xin lỗi vì vừa nhầm sang vùng lưng ạ, mình đang cần chăm sóc vùng đầu, vai và giấc ngủ thôi đúng không ạ?", 0)
        );
        ReplyContractValidator.ValidationResult valRes = ReplyContractValidator.validateAndEnforce(
                newErrorApologyBubbles,
                afterTurn3,
                "Đau vai chứ lưng đâu",
                PrimaryActionRouter.PrimaryAction.REPAIR_AND_RECONFIRM,
                false,
                2000,
                ThinkingMode.FAST_NO_THINK,
                0L
        );
        assertFalse(valRes.errors().contains("DUPLICATE_ERROR_APOLOGY"),
                "Khi phát sinh errorId mới (WRONG_SCOPE:lưng), bot được phép nhận lỗi đích danh mà không bị chặn DUPLICATE_ERROR_APOLOGY");
    }

    @Test
    @DisplayName("5. testOperationalPromiseCreatesNoteArtifact: Tự động tạo CustomerCareNoteArtifact & chặn hứa khi chưa có confirmedScopes")
    void testOperationalPromiseCreatesNoteArtifact() {
        List<String> spaTaxonomy = List.of("đầu", "vai", "giấc ngủ");
        ConversationState stateWithScopes = ConversationState.empty()
                .withTenantCareActor("kỹ thuật viên", spaTaxonomy)
                .enrichWithCurrentMessage("Tôi bị đau đầu khó ngủ mỏi vai", spaTaxonomy);

        PrimaryActionRouter.RoutingDecision concreteDecision = new PrimaryActionRouter.RoutingDecision(
                PrimaryActionRouter.PrimaryAction.DIRECT_CONCRETE_SOLUTION,
                PrimaryActionRouter.PrimaryAction.DIRECT_CONCRETE_SOLUTION,
                false,
                2,
                "DIRECT_CONCRETE_SOLUTION",
                "spa_frustration_empathy_rescue"
        );
        List<FanpageBotResponse.ReplyBubble> promisedBubbles = List.of(
                new FanpageBotResponse.ReplyBubble("Khi mình ghé, em sẽ dặn kỹ thuật viên ưu tiên chăm sóc kỹ vùng đầu, vai và hỗ trợ giấc ngủ thật êm cho mình nha.", 0)
        );
        ConversationState updated = ConversationState.updateAfterSend(
                TEST_TENANT_ID,
                "sender-care-note",
                "conv-care-note-01",
                stateWithScopes,
                "Rồi định chăm sóc cái j cho t",
                concreteDecision,
                promisedBubbles,
                false,
                4
        );
        assertNotNull(updated.customerCareNotes());
        assertEquals(1, updated.customerCareNotes().size(), "Phải tự động tạo 1 bản ghi CustomerCareNoteArtifact");
        ConversationState.CustomerCareNoteArtifact artifact = updated.customerCareNotes().get(0);
        assertEquals(TEST_TENANT_ID.toString(), artifact.tenantId());
        assertEquals("conv-care-note-01", artifact.conversationId());
        assertEquals("kỹ thuật viên", artifact.careActor());
        assertTrue(artifact.confirmedScopes().containsAll(List.of("đầu", "vai", "giấc ngủ")));
        assertTrue(artifact.noteText().contains("dặn kỹ thuật viên"));

        // Kiểm tra nhánh chưa có confirmedScopes mà hứa vận hành -> phải báo lỗi OPERATIONAL_PROMISE_WITHOUT_ARTIFACT
        ConversationState emptyScopeState = ConversationState.empty().withTenantCareActor("kỹ thuật viên", spaTaxonomy);
        ReplyContractValidator.ValidationResult invalidPromiseRes = ReplyContractValidator.validateAndEnforce(
                promisedBubbles,
                emptyScopeState,
                "Tư vấn đi",
                PrimaryActionRouter.PrimaryAction.GENERIC_SHORT_DISCOVERY,
                false,
                2000,
                ThinkingMode.FAST_NO_THINK,
                0L
        );
        assertTrue(invalidPromiseRes.errors().contains("OPERATIONAL_PROMISE_WITHOUT_ARTIFACT"),
                "Khi chưa có confirmedScopes mà hứa 'em sẽ dặn kỹ thuật viên...', Validator phải bắt lỗi OPERATIONAL_PROMISE_WITHOUT_ARTIFACT");
    }

    @Test
    @DisplayName("6. testGroundingCheckCannotOverrideKernelConfirmedStateWithHallucination: Quyền sở hữu State Bất đối xứng")
    void testGroundingCheckCannotOverrideKernelConfirmedStateWithHallucination() {
        List<String> spaTaxonomy = List.of("đầu", "vai", "đầu gối", "bắp chân");
        ConversationState kernelState = ConversationState.empty()
                .withTenantCareActor("kỹ thuật viên", spaTaxonomy)
                .enrichWithCurrentMessage("Tôi bị đau đầu và mỏi vai", spaTaxonomy);

        // Trường hợp 1: LLM tự ý điền thêm "bắp chân" vào customer_confirmed_scopes -> Kernel BẮT BUỘC từ chối ADD
        ConversationState.GroundingCheck hallucinatedAdd = new ConversationState.GroundingCheck(
                List.of("đầu", "vai", "bắp chân"),
                List.of(),
                "SYMPTOM_DISCOVERY",
                null
        );
        ConversationState afterHallucinatedAdd = kernelState.applyGroundingCheckAsymmetricUpdate(
                hallucinatedAdd,
                "Tôi bị đau đầu và mỏi vai"
        );
        assertFalse(afterHallucinatedAdd.confirmedScopes().contains("bắp chân"),
                "GroundingCheck của LLM tuyệt đối không được tự ý THÊM phạm vi chưa được Kernel xác thực vào confirmedScopes");
        assertEquals(Set.of("đầu", "vai"), Set.copyOf(afterHallucinatedAdd.confirmedScopes()));

        // Trường hợp 2: Ở câu Nhóm H ("tao nói đầu, mày nghe gối à"), giả sử trước đó state bị lẫn "đầu gối",
        // LLM phát hiện khách bác bỏ "đầu gối" (và chữ "gối" có mặt trong tin nhắn khách) -> Kernel cho phép gỡ khỏi confirmedScopes và đưa vào forbiddenScopes
        ConversationState pollutedState = kernelState.enrichWithCurrentMessage("Tôi bị đau đầu gối", spaTaxonomy);
        assertTrue(pollutedState.confirmedScopes().contains("đầu gối"));
        ConversationState.GroundingCheck dialectNegation = new ConversationState.GroundingCheck(
                List.of("đầu"),
                List.of("đầu gối"),
                "SCOPE_CORRECTION_REPAIR",
                "WRONG_SCOPE:đầu gối"
        );
        ConversationState cleanedState = pollutedState.applyGroundingCheckAsymmetricUpdate(
                dialectNegation,
                "tao nói đầu, mày nghe gối à"
        );
        assertFalse(cleanedState.confirmedScopes().contains("đầu gối"),
                "Kernel được phép tách phạm vi bị bác bỏ ('đầu gối') ra khỏi confirmedScopes");
        assertTrue(cleanedState.forbiddenScopes().contains("đầu gối"),
                "Kernel phải đưa phạm vi bị bác bỏ ('đầu gối') vào forbiddenScopes");
    }
}
