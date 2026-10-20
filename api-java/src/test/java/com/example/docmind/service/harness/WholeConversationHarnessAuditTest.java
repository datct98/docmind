package com.example.docmind.service.harness;

import com.docmind.guardrail.ClosingQuestionHook;
import com.docmind.guardrail.GuardrailContext;
import com.docmind.guardrail.GuardrailDraft;
import com.docmind.guardrail.GuardrailPipeline;
import com.example.docmind.dto.bot.FanpageBotRequest.ConversationTurn;
import com.example.docmind.dto.harness.HarnessConversationAuditRequest;
import com.example.docmind.dto.harness.HarnessConversationAuditRequest.AuditTurnInput;
import com.example.docmind.dto.harness.HarnessConversationAuditResponse;
import com.example.docmind.dto.harness.HarnessOptimizeRequest;
import com.example.docmind.dto.harness.HarnessOptimizeResponse;
import com.example.docmind.entity.HarnessRunEntity;
import com.example.docmind.entity.LearnedRuleEntity;
import com.example.docmind.repository.HarnessRunRepository;
import com.example.docmind.repository.LearnedRuleRepository;
import com.example.docmind.service.bot.FanpageBotPromptBuilder;
import com.example.docmind.service.embedding.EmbeddingClient;
import com.example.docmind.service.llm.LlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class WholeConversationHarnessAuditTest {

    @Mock
    private HarnessRunRepository harnessRunRepository;

    @Mock
    private LearnedRuleRepository learnedRuleRepository;

    @Mock
    private EmbeddingClient embeddingClient;

    @Mock
    private LlmClient llmClient;

    private HarnessOptimizerService harnessOptimizerService;
    private ClosingQuestionHook closingQuestionHook;

    @BeforeEach
    void setUp() {
        lenient().when(embeddingClient.embed(any())).thenReturn(new float[]{0.1f, 0.2f, 0.3f});
        lenient().when(learnedRuleRepository.save(any(LearnedRuleEntity.class))).thenAnswer(inv -> {
            LearnedRuleEntity entity = inv.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(UUID.randomUUID());
            }
            return entity;
        });
        lenient().when(harnessRunRepository.save(any(HarnessRunEntity.class))).thenAnswer(inv -> {
            HarnessRunEntity run = inv.getArgument(0);
            if (run.getId() == null) {
                run.setId(UUID.randomUUID().toString());
            }
            return run;
        });

        closingQuestionHook = new ClosingQuestionHook();
        GuardrailPipeline guardrailPipeline = new GuardrailPipeline();
        harnessOptimizerService = new HarnessOptimizerService(
                harnessRunRepository,
                learnedRuleRepository,
                guardrailPipeline,
                embeddingClient,
                llmClient
        );
    }

    @Test
    @DisplayName("1. ConversationBookingState detects 11-digit phone, 12h30 slot, self-arrangement, and redundant-question complaint")
    void conversationBookingStateDetectsMultiTurnBookingAndComplaint() {
        List<ConversationTurn> history = List.of(
                new ConversationTurn("user", "Tầm trưa có được không"),
                new ConversationTurn("assistant", "Dạ tầm trưa từ 11h30 - 13h30 bên em vẫn mở cửa xuyên trưa."),
                new ConversationTurn("user", "12h30 có đươc k"),
                new ConversationTurn("assistant", "Dạ 12h30 trưa hoàn toàn được ạ."),
                new ConversationTurn("user", "09836563213"),
                new ConversationTurn("assistant", "Dạ em đã ghi nhận số 09836563213 của Anh."),
                new ConversationTurn("user", "k cần đâu tới giờ tôi tự tới"),
                new ConversationTurn("assistant", "Dạ vâng Anh."),
                new ConversationTurn("user", "tự sx"),
                new ConversationTurn("assistant", "Dạ bên em giữ lịch sẵn cho Anh."),
                new ConversationTurn("user", "12h30 mai nhé")
        );

        String complaintMsg = "12h30 là buổi trưa rồi còn hỏi sáng hay chiều, không lẽ đêm à";
        FanpageBotPromptBuilder.ConversationBookingState state =
                FanpageBotPromptBuilder.analyzeConversationBookingState(complaintMsg, history);

        assertThat(state.hasCapturedPhone()).isTrue();
        assertThat(state.capturedPhone()).isEqualTo("09836563213");
        assertThat(state.hasConfirmedTimeSlot()).isTrue();
        assertThat(state.confirmedTimeSlot()).contains("12h30");
        assertThat(state.customerDeclinedCallOrSelfArranges()).isTrue();
        assertThat(state.isRedundantQuestionComplaint()).isTrue();
        assertThat(state.isPostBookingWrapUp()).isTrue();
    }

    @Test
    @DisplayName("2. ClosingQuestionHook strips redundant Sáng/Chiều or time questions when booking time & phone are already confirmed")
    void closingQuestionHookStripsRedundantQuestionsOnPostBookingAndComplaint() {
        List<String> history = List.of(
                "user: 12h30 có đươc k",
                "assistant: Dạ 12h30 trưa hoàn toàn được ạ.",
                "user: 09836563213",
                "assistant: Dạ em đã ghi nhận số 09836563213 của Anh.",
                "user: 12h30 mai nhé"
        );

        // Case A: Customer complains about redundant morning/afternoon question
        GuardrailContext complaintCtx = GuardrailContext.builder()
                .customerHonorific("Anh")
                .salesStage("SOFT_BOOKING_CLOSE")
                .isFirstTurn(false)
                .conversationHistory(history)
                .customerMessage("12h30 là buổi trưa rồi còn hỏi sáng hay chiều, không lẽ đêm à")
                .build();
        GuardrailDraft complaintDraft = new GuardrailDraft(
                "Dạ em xin lỗi Anh vì sơ suất hỏi lặp thông tin ạ, bên em đã ghi nhận lịch 12h30 trưa mai của Anh rồi ạ.\n\n"
                        + "Anh cho em xin khung giờ tiện nhất (Sáng hay Chiều) để bên em giữ lịch ưu tiên trước cho mình ạ?",
                null
        );

        closingQuestionHook.apply(complaintCtx, complaintDraft);

        assertThat(complaintDraft.getText().toLowerCase())
                .doesNotContain("sáng hay chiều")
                .doesNotContain("khung giờ tiện nhất")
                .doesNotContain("ngày thường hay cuối tuần");

        // Case B: Customer says "tự sx" after booking
        GuardrailContext wrapUpCtx = GuardrailContext.builder()
                .customerHonorific("Anh")
                .salesStage("SOFT_BOOKING_CLOSE")
                .isFirstTurn(false)
                .conversationHistory(history)
                .customerMessage("tự sx")
                .build();
        GuardrailDraft wrapUpDraft = new GuardrailDraft(
                "Dạ vâng Anh, bên em đã ghi nhận Anh tự sắp xếp ghé qua cơ sở rồi ạ.\n\n"
                        + "Anh tiện ghé cơ sở bên em vào ngày thường hay cuối tuần để bên em sắp xếp Bác sĩ tiếp đón chu đáo nhất ạ?",
                null
        );

        closingQuestionHook.apply(wrapUpCtx, wrapUpDraft);

        assertThat(wrapUpDraft.getText().toLowerCase())
                .doesNotContain("ngày thường hay cuối tuần")
                .doesNotContain("sáng hay chiều");

        // Case C: Customer selected "12h30 có đươc k" (time confirmed, phone not given yet)
        GuardrailContext timeSelectedCtx = GuardrailContext.builder()
                .customerHonorific("Anh")
                .salesStage("SOFT_BOOKING_CLOSE")
                .isFirstTurn(false)
                .conversationHistory(List.of(
                        "user: Tôi là nam, muốn trị mụn",
                        "assistant: Dạ liệu trình Trị Mụn Chuẩn Y Khoa phù hợp Anh Nam giới ạ."
                ))
                .customerMessage("12h30 có đươc k")
                .build();
        GuardrailDraft timeSelectedDraft = new GuardrailDraft(
                "Dạ khung giờ 12h30 trưa hoàn toàn được ạ, bên em có Bác sĩ trực xuyên trưa.\n\n"
                        + "Anh muốn đặt lịch qua soi da AI 3D miễn phí cùng Bác sĩ vào khung giờ Sáng hay Chiều để bên em giữ lịch trước cho mình ạ?",
                null
        );

        closingQuestionHook.apply(timeSelectedCtx, timeSelectedDraft);

        assertThat(timeSelectedDraft.getText().toLowerCase())
                .doesNotContain("sáng hay chiều")
                .contains("12h30")
                .contains("số điện thoại");
    }

    @Test
    @DisplayName("3. Single-turn Harness² optimize() uses multi-turn history to eliminate contradictory Sáng/Chiều questions")
    void singleTurnOptimizeUsesHistoryToFixContradictoryTimeQuestions() {
        List<String> history = List.of(
                "user: Tôi là nam",
                "assistant: Dạ liệu trình phù hợp Anh Nam giới.",
                "user: 12h30 có đươc k",
                "assistant: Dạ 12h30 trưa được ạ.",
                "user: 09836563213",
                "assistant: Dạ em đã lưu số 09836563213.",
                "user: 12h30 mai nhé",
                "assistant: Dạ bên em giữ lịch 12h30 mai."
        );

        HarnessOptimizeRequest req = new HarnessOptimizeRequest(
                null,
                "12h30 là buổi trưa rồi còn hỏi sáng hay chiều, không lẽ đêm à",
                "Dạ em xin lỗi Anh ạ, bên em đã giữ lịch 12h30 trưa mai.\n\nAnh cho em xin khung giờ tiện nhất (Sáng hay Chiều) để bên em giữ lịch ưu tiên trước cho mình ạ?",
                null,
                history,
                -15,
                List.of("HOOK_CLOSING_QUESTION_CONTROL"),
                "MANUAL_1CLICK",
                null,
                42L
        );

        HarnessOptimizeResponse resp = harnessOptimizerService.optimize(req);

        assertThat(resp.verdict()).isEqualTo("ADOPTED");
        assertThat(resp.improvedResponse().toLowerCase())
                .doesNotContain("sáng hay chiều")
                .doesNotContain("khung giờ tiện nhất")
                .contains("12h30");
    }

    @Test
    @DisplayName("4. Whole-Conversation Harness² auditConversation() audits the 14-turn transcript, detects redundant questions & price comparison, and rewrites faulty turns")
    void wholeConversationAuditDetectsRedundantBookingQuestionsAndPriceObjection() {
        List<AuditTurnInput> turns = List.of(
                new AuditTurnInput(0, "user", "giá", null),
                new AuditTurnInput(1, "assistant", "Dạ em chào Chị ạ! Chi phí bên em chỉ từ 650.000đ/buổi tùy phác đồ da.\n\nChị đang muốn ưu tiên xử lý mụn, mờ nám tàn nhang hay dưỡng căng bóng trẻ hóa ạ?", null),
                new AuditTurnInput(2, "user", "Tôi muốn trị thâm và trị mụn", null),
                new AuditTurnInput(3, "assistant", "Dạ liệu trình Trị Mụn Chuẩn Y Khoa bên em 1.500.000đ/buổi.\n\nChị muốn bên em đặt lịch soi da AI 3D miễn phí cùng Bác sĩ Da liễu trước hay cần em tư vấn kỹ hơn về lộ trình các buổi ạ?", null),
                new AuditTurnInput(4, "user", "Tư vấn thêm. tôi là nam", null),
                new AuditTurnInput(5, "assistant", "Dạ liệu trình Trị Mụn Chuẩn Y Khoa bên em hoàn toàn phù hợp với Anh Nam giới.\n\nAnh muốn đặt lịch qua soi da AI 3D miễn phí cùng Bác sĩ vào khung giờ Sáng hay Chiều để bên em giữ lịch trước cho mình ạ?", null),
                new AuditTurnInput(6, "user", "Hơi đắt nhỉ, tôi đi nặn mụn chỉ mất 200/buổi", null),
                new AuditTurnInput(7, "assistant", "Dạ Anh thấy hơi đắt so với nặn mụn thường là hoàn toàn đúng, Anh cứ thoải mái cân nhắc thêm không cần vội gì hết ạ.\n\nBên em vẫn giữ sẵn suất soi da AI 3D miễn phí 0đ không ràng buộc cho Anh trong 24h tới, Anh muốn em giữ khung Sáng hay Chiều để tiện hơn ạ?", null),
                new AuditTurnInput(8, "user", "Tầm trưa có được không", null),
                new AuditTurnInput(9, "assistant", "Dạ tầm trưa từ 11h30 - 13h30 bên em vẫn mở cửa đón khách bình thường.\n\nAnh tiện ghé cơ sở bên em vào ngày thường hay cuối tuần để bên em sắp xếp Bác sĩ tiếp đón chu đáo nhất ạ?", null),
                new AuditTurnInput(10, "user", "12h30 có đươc k", null),
                new AuditTurnInput(11, "assistant", "Dạ khung giờ 12h30 trưa hoàn toàn được ạ.\n\nAnh muốn đặt lịch qua soi da AI 3D miễn phí cùng Bác sĩ vào khung giờ Sáng hay Chiều để bên em giữ lịch trước cho mình ạ?", null),
                new AuditTurnInput(12, "user", "09836563213", null),
                new AuditTurnInput(13, "assistant", "Dạ em cảm ơn Anh nhiều ạ! Em đã ghi nhận số điện thoại 09836563213 của Anh và chuyển ngay cho Chuyên viên tư vấn liên hệ qua Zalo/SĐT trong ít phút nữa ạ.", null),
                new AuditTurnInput(14, "user", "k cần đâu tới giờ tôi tự tới", null),
                new AuditTurnInput(15, "assistant", "Dạ vâng Anh, vậy bên em không gọi làm phiền mà giữ sẵn suất soi da AI 3D miễn phí theo số 09836563213.\n\nAnh tiện ghé cơ sở bên em vào ngày thường hay cuối tuần để bên em sắp xếp Bác sĩ tiếp đón chu đáo nhất ạ?", null),
                new AuditTurnInput(16, "user", "tự sx", null),
                new AuditTurnInput(17, "assistant", "Dạ vâng Anh, bên em đã ghi nhận Anh tự sắp xếp thời gian ghé qua cơ sở rồi ạ.\n\nAnh muốn đặt lịch qua soi da AI 3D miễn phí cùng Bác sĩ vào khung giờ Sáng hay Chiều để bên em giữ lịch trước cho mình ạ?", null),
                new AuditTurnInput(18, "user", "12h30 mai nhé", null),
                new AuditTurnInput(19, "assistant", "Dạ vâng Anh, bên em đã chốt lịch hẹn 12h30 trưa mai cho số 09836563213 của Anh rồi ạ.\n\nAnh muốn đặt lịch qua soi da AI 3D miễn phí cùng Bác sĩ vào khung giờ Sáng hay Chiều để bên em giữ lịch trước cho mình ạ?", null),
                new AuditTurnInput(20, "user", "12h30 là buổi trưa rồi còn hỏi sáng hay chiều, không lẽ đêm à", null),
                new AuditTurnInput(21, "assistant", "Dạ em xin lỗi Anh vì sơ suất hỏi lặp thông tin ạ, bên em đã ghi nhận chuẩn lịch hẹn 12h30 trưa mai cho số 09836563213.\n\nAnh cho em xin khung giờ tiện nhất (Sáng hay Chiều) để bên em giữ lịch ưu tiên trước cho mình ạ?", null)
        );

        HarnessConversationAuditRequest auditReq = new HarnessConversationAuditRequest(null, "conv-test-1", null, turns, true);
        HarnessConversationAuditResponse auditResp = harnessOptimizerService.auditConversation(auditReq);

        assertThat(auditResp.totalExchanges()).isEqualTo(11);
        assertThat(auditResp.issuesFoundCount()).isGreaterThanOrEqualTo(5);
        assertThat(auditResp.overallScoreAfter()).isGreaterThan(auditResp.overallScoreBefore());
        assertThat(auditResp.stateSummary().resolvedHonorific()).isEqualTo("Anh");
        assertThat(auditResp.stateSummary().capturedPhone()).isEqualTo("09836563213");
        assertThat(auditResp.stateSummary().confirmedTimeSlot()).contains("12h30");
        assertThat(auditResp.learnedRules()).isNotEmpty();

        // Check that all rewritten replies for turns after "12h30" never ask "Sáng hay Chiều" or "ngày thường hay cuối tuần"
        for (HarnessConversationAuditResponse.TurnAuditFinding finding : auditResp.turnFindings()) {
            if (finding.exchangeIndex() >= 6 && !"OPTIMAL".equals(finding.status())) {
                assertThat(finding.rewrittenBotReply().toLowerCase())
                        .as("Turn #%d rewritten reply must not ask redundant Sáng/Chiều or ngày thường/cuối tuần", finding.exchangeIndex())
                        .doesNotContain("sáng hay chiều")
                        .doesNotContain("ngày thường hay cuối tuần")
                        .doesNotContain("khung giờ tiện nhất");
            }
        }
    }
}
