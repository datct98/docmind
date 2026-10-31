package com.example.docmind.service.bot;

import com.example.docmind.config.ChatProperties;
import com.example.docmind.dto.PagePersonaConfig;
import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.dto.bot.FanpageBotResponse;
import com.example.docmind.entity.DocumentEntity;
import com.example.docmind.entity.KnowledgePageEntity;
import com.example.docmind.repository.DocumentRepository;
import com.example.docmind.repository.KnowledgePageRepository;
import com.example.docmind.repository.RetrievedChunk;
import com.example.docmind.service.llm.LlmClient;
import com.example.docmind.service.llm.ReasoningBudget;
import com.example.docmind.service.retrieval.PromptBuilder;
import com.example.docmind.service.retrieval.ReciprocalRankFusion;
import com.example.docmind.service.retrieval.RetrievalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Bộ kiểm thử đơn vị toàn diện cho Đợt 1 Cải tổ Kiến trúc Nhận thức ({@code LLM_COGNITIVE_V2} + {@code ACI}):
 * <ul>
 *   <li>Mục 1: Feature Flag {@code DETERMINISTIC_V1} vs {@code LLM_COGNITIVE_V2} & khả năng rollback tức thì</li>
 *   <li>Mục 2: Tách 3 luồng đầu vào, chống Context Poisoning, đánh mã {@code [T1]..[T_CURRENT]}, sửa cổng {@code S06}</li>
 *   <li>Mục 3: {@code ACI} Single-Pass Cognitive Contract, Epistemic Unblinding, Adaptive Cognitive Reflection</li>
 *   <li>Mục 4: Phân quyền Memory 5 tầng, vòng đời 5 thao tác {@code Working Hypothesis}, {@code CommitmentRecord} vs Task, Conditional Handoff</li>
 *   <li>Mục 5: Biên quyền hạn Referee trong {@code LLM_COGNITIVE_V2} (Sự thật / Quyền hạn / An toàn)</li>
 *   <li>Mục 6: {@code Price Quote Reading Budget (10s)} tính từ {@code quoteBubbleAcceptedAt} (đủ 4 ca thời gian bắt buộc + Epoch/Ledger)</li>
 * </ul>
 */
class AciCognitiveV2AndReadingBudgetTest {

    private static final UUID PAGE_ID = UUID.fromString("b23cd382-4f7a-4829-8c54-1d410da14bf8");
    private static final UUID DOC_ID = UUID.fromString("c9481efb-f3a9-4613-a2e0-fe50e9b99b39");

    private PromptBuilder.PagePersona nangSenPersona;
    private KnowledgePageEntity nangSenPageEntity;
    private List<ReciprocalRankFusion.FusedChunk> catalogChunks;
    private List<PackageCatalogService.PackageSummary> catalogPackages;

    @BeforeEach
    void setUp() throws Exception {
        SenderEpochRegistry.clearAll();
        ThinkingModePolicy.clearAciTurnTelemetry();
        SentBubbleLedger.useStore(new SentBubbleLedger.InMemoryStore());
        SentBubbleLedger.clear();
        PriceQuoteReadingBudgetScheduler.clearDedupeRegistryForTesting();

        PagePersonaConfig cfg = new PagePersonaConfig(
                "BEAUTY_SPA",
                "nhân viên chăm sóc",
                "em",
                "mình",
                "Dịu dàng, ấm áp, tinh tế, thấu cảm tình trạng khách trước rồi mới gợi ý gói",
                "NONE",
                List.of("chốt đơn", "cam kết khỏi hẳn", "chữa khỏi", "điều trị dứt điểm"),
                List.of("massage", "cổ vai gáy", "vai gáy", "mỏi", "đau", "nhức", "tê", "căng", "ngâm thảo dược", "gội đầu", "dưỡng sinh", "lưng", "toàn thân"),
                "spa-llm-native-guarded-v3.2.1-final",
                "BẠN LÀ: nhân viên chăm sóc của Nàng Sen Spa (tự nhiên, ấm áp, tinh tế). Xưng em - gọi mình.",
                List.of("Dạ chào mình, em là nhân viên chăm sóc của Nàng Sen Spa ạ."),
                10000,
                "Mình thích lực nhẹ thư giãn hay lực vừa để thả lỏng vùng này cho dễ chịu ạ?",
                "Mình bị lâu chưa hay mới gần đây ạ?",
                "Dạ em hiểu mình đang khó chịu ạ.",
                null,
                List.of("đau", "mỏi", "nhức", "tê", "căng cứng", "mất ngủ"),
                List.of("cổ", "vai", "gáy", "cổ vai gáy", "lưng", "chân", "đầu", "toàn thân"),
                Map.of(),
                Map.of()
        );
        nangSenPersona = new PromptBuilder.PagePersona("Nàng Sen Spa", "BEAUTY_SPA", "Khách hàng spa", "Ấm áp", cfg);
        nangSenPageEntity = KnowledgePageEntity.builder()
                .id(PAGE_ID)
                .name("Nàng Sen Spa")
                .industry("BEAUTY_SPA")
                .targetAudience("Khách hàng spa")
                .description("Ấm áp")
                .personaConfigJson(new ObjectMapper().writeValueAsString(cfg))
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();

        String hanoiMassageTable = """
                | Mã gói | Tên gói | Giá | Tổng thời gian | Bao gồm | Không gian | KTV | Điểm nổi bật | Phù hợp |
                | --- | --- | --- | --- | --- | --- | --- | --- | --- |
                | NS01 | Massage cơ bản | 399.000VNĐ | 60 phút | Full body massage trị liệu | Phòng cộng đồng có rèm riêng | KTV tiêu chuẩn | Tắm xông thảo dược + massage 60 phút + buffet thực dưỡng | Khách thư giãn nhanh, dân văn phòng |
                | NS03 | Massage phục hồi | 580.000VNĐ | 100 phút | 10p ngâm bồn thảo dược + 5p xông hơi + 85p massage phục hồi | Phòng VIP riêng | KTV phục hồi | Phòng VIP riêng tư + KTV phục hồi bấm huyệt chuyên sâu | Đau mỏi vai gáy, stress nặng |
                | ADD01 | Tẩy da chết cơ bản | 100.000VNĐ | Theo liệu trình | Tẩy da chết toàn thân | Theo phòng dịch vụ | KTV | Làm sạch & mềm da | Khách muốn chăm sóc da thêm |
                """;
        String hanoiHairWashTable = """
                | Mã gói | Tên gói | Giá | Tổng thời gian | Bao gồm | Không gian | KTV | Điểm nổi bật | Phù hợp |
                | --- | --- | --- | --- | --- | --- | --- | --- | --- |
                | DS02 | Dưỡng sinh cơ bản | 239.000VNĐ | 60 phút | Rửa mặt + massage mặt + gội thảo dược + chườm thảo dược cổ vai + ăn nhẹ | Khu dưỡng sinh chung | KTV dưỡng sinh | Thư giãn đầu cổ vai nhẹ nhàng | Khách cần thư giãn nhanh, tiết kiệm |
                | DS03 | Dưỡng sinh thư giãn | 339.000VNĐ | 80 phút | Ngâm chân thảo dược + massage cổ vai gáy + gội thảo dược + ăn nhẹ | Khu dưỡng sinh chung | KTV dưỡng sinh | Có massage cổ vai gáy giảm mỏi | Khách mỏi vai gáy nhẹ |
                """;
        String policyChunk = """
                [Thông tin vận hành & Chính sách Nàng Sen Spa]
                Địa chỉ Hà Nội: 6 Bích Câu – Đống Đa – Hà Nội. Giờ mở cửa: 09:00 - 22:00 hàng ngày (nhận khách cuối lúc 20:30).
                Có chỗ đỗ ô tô và xe máy miễn phí. Giá dịch vụ trọn gói, không thu phụ phí và không bắt buộc tiền tip.
                """;

        catalogChunks = List.of(
                new ReciprocalRankFusion.FusedChunk(
                        new RetrievedChunk(UUID.fromString("c9481efb-0001-4613-a2e0-fe50e9b99b39"), DOC_ID,
                                List.of("MASSAGE", "Khu vực Hà Nội"), List.of(), 1, new double[]{0, 0, 10, 10}, hanoiMassageTable, 0.96),
                        0.96),
                new ReciprocalRankFusion.FusedChunk(
                        new RetrievedChunk(UUID.fromString("c9481efb-0002-4613-a2e0-fe50e9b99b39"), DOC_ID,
                                List.of("GỘI ĐẦU DƯỠNG SINH", "Khu vực Hà Nội"), List.of(), 1, new double[]{0, 0, 10, 10}, hanoiHairWashTable, 0.94),
                        0.94),
                new ReciprocalRankFusion.FusedChunk(
                        new RetrievedChunk(UUID.fromString("c9481efb-0003-4613-a2e0-fe50e9b99b39"), DOC_ID,
                                List.of("CHÍNH SÁCH & GIỜ MỞ CỬA"), List.of(), 1, new double[]{0, 0, 10, 10}, policyChunk, 0.92),
                        0.92)
        );
        catalogPackages = PackageCatalogService.extractCatalog(catalogChunks, List.of());
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        SenderEpochRegistry.clearAll();
        ThinkingModePolicy.clearAciTurnTelemetry();
        PriceQuoteReadingBudgetScheduler.clearDedupeRegistryForTesting();
    }

    // =========================================================================
    // MỤC 6: 4 BÀI TEST THỜI GIAN BẮT BUỘC + EPOCH/LEDGER INTEGRATION
    // =========================================================================

    @Test
    @DisplayName("Mục 6.4 - Case 1: LLM mất 5s, quote bubble được Messenger xác nhận tại t=5s -> bong bóng kế tiếp lên lịch >= 15s (cấm trừ 5s LLM)")
    void readingBudgetCase1_Llm5s_QuoteAcceptedAt5s_NextScheduledAt15s() {
        long llmLatencyMs = 5_000L;

        PriceQuoteReadingBudgetScheduler.QuoteBubbleAcceptanceRecord acceptance =
                PriceQuoteReadingBudgetScheduler.QuoteBubbleAcceptanceRecord.accepted(0, "mid.quote.case1", llmLatencyMs, 5_000L);
        PriceQuoteReadingBudgetScheduler.NextBubbleScheduleDecision schedule =
                PriceQuoteReadingBudgetScheduler.computeNextBubbleSchedule(acceptance, 5_000L, 10_000L);

        assertThat(schedule.readingBudgetApplied()).isTrue();
        assertThat(schedule.remainingWaitMs()).isEqualTo(10_000L);
        assertThat(schedule.scheduledSendAtMs()).isGreaterThanOrEqualTo(15_000L);
        assertThat(schedule.scheduledSendAtMs()).isEqualTo(15_000L);
    }

    @Test
    @DisplayName("Mục 6.4 - Case 2: LLM mất 12s, quote bubble được Messenger xác nhận tại t=12s -> bong bóng kế tiếp lên lịch >= 22s (cấm xóa 10s vì LLM > 10s)")
    void readingBudgetCase2_Llm12s_QuoteAcceptedAt12s_NextScheduledAt22s() {
        long llmLatencyMs = 12_000L;
        assertThat(llmLatencyMs).isGreaterThan(10_000L);

        PriceQuoteReadingBudgetScheduler.QuoteBubbleAcceptanceRecord acceptance =
                PriceQuoteReadingBudgetScheduler.QuoteBubbleAcceptanceRecord.accepted(0, "mid.quote.case2", llmLatencyMs, 12_000L);
        PriceQuoteReadingBudgetScheduler.NextBubbleScheduleDecision schedule =
                PriceQuoteReadingBudgetScheduler.computeNextBubbleSchedule(acceptance, 12_000L, 10_000L);

        assertThat(schedule.readingBudgetApplied()).isTrue();
        assertThat(schedule.remainingWaitMs()).isEqualTo(10_000L);
        assertThat(schedule.scheduledSendAtMs()).isGreaterThanOrEqualTo(22_000L);
        assertThat(schedule.scheduledSendAtMs()).isEqualTo(22_000L);
    }

    @Test
    @DisplayName("Mục 6.4 - Case 3: quoteBubbleAcceptedAt = 0s, bong bóng kế tiếp sẵn sàng lúc t=7s -> chỉ chờ thêm 3s và gửi đúng mốc 10s")
    void readingBudgetCase3_AcceptedAt0s_NextReadyAt7s_Waits3sAndSendsAt10s() {
        PriceQuoteReadingBudgetScheduler.QuoteBubbleAcceptanceRecord acceptance =
                PriceQuoteReadingBudgetScheduler.QuoteBubbleAcceptanceRecord.accepted(0, "mid.quote.case3", 1_500L, 0L);
        PriceQuoteReadingBudgetScheduler.NextBubbleScheduleDecision schedule =
                PriceQuoteReadingBudgetScheduler.computeNextBubbleSchedule(acceptance, 7_000L, 10_000L);

        assertThat(schedule.readingBudgetApplied()).isTrue();
        assertThat(schedule.elapsedSinceQuoteAcceptedMs()).isEqualTo(7_000L);
        assertThat(schedule.remainingWaitMs()).isEqualTo(3_000L);
        assertThat(schedule.scheduledSendAtMs()).isEqualTo(10_000L);
    }

    @Test
    @DisplayName("Mục 6.4 - Case 4: quoteBubbleAcceptedAt = 0s, bong bóng kế tiếp sẵn sàng lúc t=13s -> remainingWaitMs = 0ms và gửi ngay tại 13s")
    void readingBudgetCase4_AcceptedAt0s_NextReadyAt13s_Waits0sAndSendsImmediatelyAt13s() {
        PriceQuoteReadingBudgetScheduler.QuoteBubbleAcceptanceRecord acceptance =
                PriceQuoteReadingBudgetScheduler.QuoteBubbleAcceptanceRecord.accepted(0, "mid.quote.case4", 1_500L, 0L);
        PriceQuoteReadingBudgetScheduler.NextBubbleScheduleDecision schedule =
                PriceQuoteReadingBudgetScheduler.computeNextBubbleSchedule(acceptance, 13_000L, 10_000L);

        assertThat(schedule.readingBudgetApplied()).isTrue();
        assertThat(schedule.elapsedSinceQuoteAcceptedMs()).isEqualTo(13_000L);
        assertThat(schedule.remainingWaitMs()).isZero();
        assertThat(schedule.scheduledSendAtMs()).isEqualTo(13_000L);
    }

    @Test
    @DisplayName("Mục 6.1 & 6.2: Bong bóng báo giá ở vị trí bubble 1 (không mặc định bubble 0), không kích hoạt ở lượt chê giá S03B, và không chạy khi gửi lỗi")
    void readingBudgetDetectsNonZeroQuoteBubble_SkipsOnFailedSendAndPriceObjectionTurns() {
        // 1. Gửi thất bại -> không bao giờ bắt đầu đồng hồ 10s
        PriceQuoteReadingBudgetScheduler.QuoteBubbleAcceptanceRecord failedSend =
                PriceQuoteReadingBudgetScheduler.QuoteBubbleAcceptanceRecord.sendFailed(0, 5_000L);
        PriceQuoteReadingBudgetScheduler.NextBubbleScheduleDecision failedSchedule =
                PriceQuoteReadingBudgetScheduler.computeNextBubbleSchedule(failedSend, 5_000L, 10_000L);
        assertThat(failedSchedule.readingBudgetApplied()).isFalse();
        assertThat(failedSchedule.remainingWaitMs()).isZero();

        // 2. Bubble 0 là câu đồng cảm, Bubble 1 mới là bong bóng báo giá + gói, Bubble 2 là câu hỏi dẫn dắt
        List<FanpageBotResponse.ReplyBubble> threeBubbles = List.of(
                new FanpageBotResponse.ReplyBubble("Dạ vừa bay đường dài 14 tiếng về chắc vùng cổ và đầu mình đang căng mỏi lắm ạ.", 0),
                new FanpageBotResponse.ReplyBubble("Với tình trạng này mình hợp gói Massage phục hồi 100 phút giá 580k ở phòng VIP riêng yên tĩnh ạ.", 2_000),
                new FanpageBotResponse.ReplyBubble("Tối nay mình muốn ghé khoảng mấy giờ để em kiểm tra phòng trống cho mình nha?", 2_000)
        );
        boolean shouldActivateOnIntro = PriceQuoteReadingBudgetScheduler.isActualPriceQuoteIntroductionTurn(
                "Chị vừa đáp chuyến bay 14 tiếng từ Mỹ về, đầu căng và cổ cứng đơ, tư vấn gói giúp chị",
                List.of(),
                threeBubbles,
                catalogPackages,
                null
        );
        assertThat(shouldActivateOnIntro).isTrue();

        int quoteIdx = PriceQuoteReadingBudgetScheduler.findActualPriceQuoteBubbleIndex(threeBubbles, catalogPackages, shouldActivateOnIntro);
        assertThat(quoteIdx).isEqualTo(1);

        List<FanpageBotResponse.ReplyBubble> scheduledBubbles =
                PriceQuoteReadingBudgetScheduler.applyReadingBudgetTypingDelays(threeBubbles, quoteIdx, 10_000, 2_000);
        assertThat(scheduledBubbles).hasSize(3);
        assertThat(scheduledBubbles.get(0).typingDelayMs()).isZero();
        assertThat(scheduledBubbles.get(1).typingDelayMs()).isEqualTo(2_000); // sau bubble 0 (đồng cảm) là nhịp gõ tự nhiên
        assertThat(scheduledBubbles.get(2).typingDelayMs()).isEqualTo(10_000); // ngay sau bubble 1 (báo giá) mới mở 10s reading budget!

        // 3. Tuyệt đối KHÔNG kích hoạt ở lượt chê giá/phản bác giá như S03B ("Bằng cả ngày đi làm"), S01 ("1 ngày công r"), S04 ("Bên kia rẻ hơn nhiều")
        List<FanpageBotRequest.ConversationTurn> historyAfterQuote = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Gói massage cổ vai gáy giá sao em?"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ bên em có gói Massage cơ bản 60 phút giá 399k ạ.")
        );
        List<FanpageBotResponse.ReplyBubble> valueDefenseBubbles = List.of(
                new FanpageBotResponse.ReplyBubble("Dạ em hiểu 399k bằng cả ngày mình đi làm vất vả nên mình cân nhắc kỹ là rất hợp lý ạ.", 0),
                new FanpageBotResponse.ReplyBubble("Gói Massage cơ bản 60 phút đã trọn gói xông thảo dược, bấm huyệt vùng mỏi và buffet thực dưỡng không phụ phí ạ.", 2_000)
        );
        assertThat(PriceQuoteReadingBudgetScheduler.isActualPriceQuoteIntroductionTurn(
                "Bằng cả ngày đi làm", historyAfterQuote, valueDefenseBubbles, catalogPackages, null)).isFalse();
        assertThat(PriceQuoteReadingBudgetScheduler.isActualPriceQuoteIntroductionTurn(
                "399k thì mất toi 1 ngày công r", historyAfterQuote, valueDefenseBubbles, catalogPackages, null)).isFalse();
        assertThat(PriceQuoteReadingBudgetScheduler.isActualPriceQuoteIntroductionTurn(
                "Bên kia rẻ hơn nhiều", historyAfterQuote, valueDefenseBubbles, catalogPackages, null)).isFalse();
    }

    @Test
    @DisplayName("Mục 6.3: Phối hợp SenderEpochRegistry & SentBubbleLedger — khách nhắn chen ngang giữa 10s chờ -> hủy ngay lập tức, đánh dấu CANCELED và chống gửi trùng")
    void readingBudgetCancelsImmediatelyWhenCustomerInterruptsDuring10sWaitAndPreventsDuplicateSend() throws Exception {
        String pageIdStr = PAGE_ID.toString();
        String senderId = "sender-epoch-10s-test";
        String convId = "conv-epoch-10s-test";
        String ledgerKey = SentBubbleLedger.key(pageIdStr, senderId, convId);
        String turnId = "turn-quote-interrupt-01";

        List<FanpageBotResponse.ReplyBubble> bubbles = List.of(
                new FanpageBotResponse.ReplyBubble("Dạ bên em có gói Massage cơ bản 60 phút giá 399k trọn gói ạ.", 0),
                new FanpageBotResponse.ReplyBubble("Sau liệu trình mình còn được dùng buffet thực dưỡng miễn phí ạ.", 10_000),
                new FanpageBotResponse.ReplyBubble("Tối nay mình muốn ghé khung giờ nào để em kiểm tra lịch trống nha?", 2_000)
        );
        SentBubbleLedger.recordAssistantTurn(
                ledgerKey,
                turnId,
                bubbles.stream().map(FanpageBotResponse.ReplyBubble::text).toList(),
                SentBubbleLedger.DeliveryStatus.SENT
        );

        SenderEpochRegistry.EpochTurnContext epochCtx = SenderEpochRegistry.startNewBatchEpoch(pageIdStr, senderId);
        AtomicInteger outboundSendCalls = new AtomicInteger(0);

        CompletableFuture<PriceQuoteReadingBudgetScheduler.CoordinatedSendSequenceResult> future = CompletableFuture.supplyAsync(() ->
                PriceQuoteReadingBudgetScheduler.executeCoordinatedSendSequence(
                        pageIdStr,
                        senderId,
                        convId,
                        turnId,
                        epochCtx.epoch(),
                        4_200L,
                        bubbles,
                        0,
                        (idx, text) -> {
                            int n = outboundSendCalls.incrementAndGet();
                            // Ngay sau khi bong bóng báo giá (bubble 0) được chấp nhận gửi, mô phỏng khách nhắn chen ngang sau 70ms
                            if (idx == 0) {
                                CompletableFuture.runAsync(() -> {
                                    try {
                                        Thread.sleep(70L);
                                    } catch (InterruptedException ignored) {
                                        Thread.currentThread().interrupt();
                                    }
                                    SenderEpochRegistry.supersedeCurrentEpoch(pageIdStr, senderId);
                                });
                            }
                            return PriceQuoteReadingBudgetScheduler.OutboundSendReceipt.ok("mid.sent." + n);
                        },
                        null,
                        System::currentTimeMillis,
                        null
                )
        );

        // Phải thức dậy ngay khi bị supersede (< 1.5s thay vì ngủ hết 10s!)
        long t0 = System.currentTimeMillis();
        PriceQuoteReadingBudgetScheduler.CoordinatedSendSequenceResult summary = future.get(2, TimeUnit.SECONDS);
        long wallElapsedMs = System.currentTimeMillis() - t0;
        assertThat(summary.canceledMidSequence()).isTrue();
        assertThat(summary.canceledFromBubbleIndex()).isEqualTo(1);
        assertThat(outboundSendCalls.get()).isEqualTo(1);
        assertThat(wallElapsedMs).isLessThan(1_500L);

        // Kiểm tra SentBubbleLedger: bubble 0 là SENT, bubble 1 & 2 đã bị đánh dấu CANCELED (khách chưa bao giờ nhìn thấy!)
        List<SentBubbleLedger.TurnRecord> ledgerTurns = SentBubbleLedger.snapshot(ledgerKey);
        assertThat(ledgerTurns).hasSize(1);
        List<SentBubbleLedger.BubbleRecord> recordedBubbles = ledgerTurns.get(0).bubbles();
        assertThat(recordedBubbles.get(0).status()).isEqualTo(SentBubbleLedger.DeliveryStatus.SENT);
        assertThat(recordedBubbles.get(1).status()).isEqualTo(SentBubbleLedger.DeliveryStatus.CANCELED);
        assertThat(recordedBubbles.get(2).status()).isEqualTo(SentBubbleLedger.DeliveryStatus.CANCELED);

        // Chống gửi trùng (Duplicate-send guard) khi gọi lại trên cùng (pageId, senderId, epoch, turnId, bubbleIndex)
        SenderEpochRegistry.EpochTurnContext dedupeEpoch = SenderEpochRegistry.startNewBatchEpoch(pageIdStr, senderId);
        AtomicInteger dedupeSendCalls = new AtomicInteger(0);
        String dedupeTurnId = "turn-dedupe-01";
        PriceQuoteReadingBudgetScheduler.executeCoordinatedSendSequence(
                pageIdStr, senderId, convId, dedupeTurnId, dedupeEpoch.epoch(), 1_000L,
                List.of(bubbles.get(0)), -1,
                (idx, text) -> {
                    dedupeSendCalls.incrementAndGet();
                    return PriceQuoteReadingBudgetScheduler.OutboundSendReceipt.ok("mid.dedupe.1");
                },
                null, System::currentTimeMillis, null
        );
        PriceQuoteReadingBudgetScheduler.CoordinatedSendSequenceResult duplicateAttempt =
                PriceQuoteReadingBudgetScheduler.executeCoordinatedSendSequence(
                        pageIdStr, senderId, convId, dedupeTurnId, dedupeEpoch.epoch(), 1_000L,
                        List.of(bubbles.get(0)), -1,
                        (idx, text) -> {
                            dedupeSendCalls.incrementAndGet();
                            return PriceQuoteReadingBudgetScheduler.OutboundSendReceipt.ok("mid.dedupe.2");
                        },
                        null, System::currentTimeMillis, null
                );
        assertThat(dedupeSendCalls.get()).isEqualTo(1);
        assertThat(duplicateAttempt.bubbleLogs().get(0).skippedDuplicate()).isTrue();
    }

    // =========================================================================
    // MỤC 1 & MỤC 2: 3 LUỒNG ĐẦU VÀO, CHỐNG CONTEXT POISONING, [T1]..[Tn], S06
    // =========================================================================

    @Test
    @DisplayName("Mục 2.1: Tách biệt 3 luồng đầu vào — giữ nguyên văn Original Message + tin nhắn dài 2.800 ký tự, cô lập Normalized Query, khóa Unverified Parser Candidates")
    void threeInputStreamsPreserveVerbatimAndLongMessagesAndPreventRegexFactPoisoning() {
        // 1. Kiểm tra S01 ("399k thì mất toi 1 ngày công r, mà nãy e nói 60p hả??") + burst messages
        FanpageBotRequest s01Req = buildBotRequest(
                "sender-s01",
                "Chị Lan",
                null,
                List.of("399k thì mất toi 1 ngày công r", "mà nãy e nói 60p hả??"),
                List.of(
                        new FanpageBotRequest.ConversationTurn("user", "Gói massage cơ bản giá sao e?"),
                        new FanpageBotRequest.ConversationTurn("assistant", "Dạ gói Massage cơ bản 60 phút bên em giá 399k ạ.")
                )
        );

        AdaptiveConversationIntelligence.ThreeStreamTurnInput s01Streams =
                AdaptiveConversationIntelligence.buildThreeStreamInput(
                        s01Req, catalogPackages, nangSenPersona.resolvedConfig(), ZoneId.of("Asia/Ho_Chi_Minh"));

        // Original Message giữ nguyên dấu câu "??", viết tắt "r", "e", "60p" và giữ nguyên 2 bong bóng burst gốc
        assertThat(s01Streams.originalBurstBubbles())
                .containsExactly("399k thì mất toi 1 ngày công r", "mà nãy e nói 60p hả??");
        assertThat(s01Streams.originalMessage()).contains("1 ngày công r").contains("mà nãy e nói 60p hả??");
        // Normalized Query chỉ phục vụ RAG (đã bung viết tắt)
        assertThat(s01Streams.normalizedRetrievalQuery()).isNotEqualTo(s01Streams.originalMessage());
        // Mọi ứng viên từ regex (bao gồm "1 ngày" nếu có) đều mang verified=false
        assertThat(s01Streams.unverifiedParserCandidates())
                .allSatisfy(cand -> assertThat(cand.verified()).isFalse());

        // 2. Kiểm tra S04 ("Bên kia rẻ hơn nhiều") -> tuyệt đối không có bất kỳ ứng viên nào lọt vào Verified Facts
        FanpageBotRequest s04Req = buildBotRequest("sender-s04", "Khách S04", "Bên kia rẻ hơn nhiều", null, List.of());
        AdaptiveConversationIntelligence.ThreeStreamTurnInput s04Streams =
                AdaptiveConversationIntelligence.buildThreeStreamInput(
                        s04Req, catalogPackages, nangSenPersona.resolvedConfig(), ZoneId.of("Asia/Ho_Chi_Minh"));
        AgentBrainService brainService = new AgentBrainService((com.example.docmind.client.AgentBrainClient) null);
        brainService.applyAciTurnMemoryUpdates(
                PAGE_ID, "sender-s04", "T1", s04Streams.originalMessage(),
                s04Streams.unverifiedParserCandidates(),
                null, ConversationState.StructuredBookingState.none(), null, false, null, "CLARIFY_DIFFERENTIATION"
        );
        assertThat(brainService.getAuthoritativeBusinessFacts(PAGE_ID, "sender-s04")).isEmpty();
        assertThat(brainService.getRecalledVerifiedFacts(PAGE_ID, "sender-s04")).isEmpty();

        // 3. Tin nhắn dài 2.800 ký tự KHÔNG bị cắt cụt xuống 300 ký tự và KHÔNG bị OVERSIZED_PAYLOAD_FAST_PATH chặn mất LLM!
        String long2800CharMsg = "Chị đang bị đau mỏi cổ vai gáy rất nhiều sau đợt công tác dài ngày. ".repeat(42).strip();
        assertThat(long2800CharMsg.length()).isGreaterThan(2_500);
        AtomicReference<String> capturedPrompt = new AtomicReference<>("");
        FanpageBotService v2Bot = createIsolatedBotService(
                AdaptiveConversationIntelligence.CognitiveMode.LLM_COGNITIVE_V2,
                (prompt, budget) -> {
                    capturedPrompt.set(prompt);
                    return """
                            {
                              "brain_reflection": {
                                "customer_understanding": {
                                  "detected_intent": "PAIN_CONSULTATION",
                                  "emotional_state": {"primary": "TIRED", "secondary": [], "confidence": 0.90}
                                },
                                "priority": {"primary": "EMPATHY_AND_RECOMMENDATION", "secondary": []},
                                "working_hypothesis": {
                                  "content": "Khách cần liệu trình phục hồi sâu vùng cổ vai gáy sau chuyến công tác",
                                  "lifecycle_op": "ADJUST",
                                  "confidence": 0.84,
                                  "evidence_turn_ids": ["T1"]
                                },
                                "strategy": {
                                  "chosen": "EMPATHIZE_AND_RECOMMEND",
                                  "adaptation": "CONTINUATION",
                                  "previous_outcome": "NOT_APPLICABLE",
                                  "evidence_turn_ids": ["T1"]
                                },
                                "uncertainties": [],
                                "customer_statements": ["Đau mỏi cổ vai gáy nhiều sau đợt công tác dài ngày"],
                                "commitment_delta": [],
                                "handoff_request": {"requested": false, "reason": null},
                                "proposed_booking_patch": {}
                              },
                              "bubbles": [
                                "Dạ đi công tác dài ngày liên tục như vậy chắc vùng cổ vai gáy của mình đang căng cứng và mệt lắm ạ.",
                                "Với tình trạng này mình tham khảo gói Massage phục hồi 100 phút giá 580k ở phòng VIP riêng để kỹ thuật viên bấm huyệt thả lỏng sâu cho mình nhé ạ."
                              ]
                            }
                            """;
                }
        );
        FanpageBotResponse longResp = v2Bot.handleCustomerMessage(
                buildBotRequest("sender-long-2800", "Chị Mai", long2800CharMsg, null, List.of())
        );
        assertThat(longResp.triggeredHooks()).doesNotContain("OVERSIZED_PAYLOAD_FAST_PATH");
        assertThat(capturedPrompt.get()).contains(long2800CharMsg);
    }

    @Test
    @DisplayName("Mục 2.2 & Mục 3.2: Prompt hợp nhất <SINGLE_PASS_COGNITIVE_CONTRACT>, đánh mã [T1]..[T_CURRENT] và mở khóa toàn bộ Catalog (PRIMARY + ECONOMICAL_OPTION)")
    void promptConsolidatesContractPrefixesTurnIdsAndUnblindsFullCatalog() {
        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Bên mình có massage cổ vai gáy không?"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ bên em có gói Massage cơ bản 60 phút giá 399k ạ.")
        );
        FanpageBotRequest req = buildBotRequest(
                "sender-prompt-v2",
                "Chị Hương",
                null,
                List.of("399k hơi cao nhỉ", "có gói nào nhẹ tiền hơn không em?"),
                history
        );
        AdaptiveConversationIntelligence.ThreeStreamTurnInput streams =
                AdaptiveConversationIntelligence.buildThreeStreamInput(
                        req, catalogPackages, nangSenPersona.resolvedConfig(), ZoneId.of("Asia/Ho_Chi_Minh"));

        String currentTurnId = CleanAgentCatalogAndPromptBuilder.resolveCurrentSequentialTurnId(history);
        assertThat(currentTurnId).isEqualTo("T3");

        List<PackageCatalogService.PackageSummary> primaryPkgs = catalogPackages.stream()
                .filter(p -> !p.isAddon() && p.commercialRole() != PackageCatalogService.CommercialRole.ECONOMICAL_OPTION)
                .toList();
        List<PackageCatalogService.PackageSummary> economicalPkgs = catalogPackages.stream()
                .filter(p -> !p.isAddon() && p.commercialRole() == PackageCatalogService.CommercialRole.ECONOMICAL_OPTION)
                .toList();

        String prompt = CleanAgentCatalogAndPromptBuilder.buildAciSinglePassPrompt(
                nangSenPersona,
                "mình",
                streams,
                primaryPkgs,
                economicalPkgs,
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                List.of(),
                AdaptiveConversationIntelligence.WorkingHypothesisRecord.initialEmpty(),
                List.of(),
                ConversationState.StructuredBookingState.none(),
                AdaptiveConversationIntelligence.OperationalStateSnapshot.initial(),
                AdaptiveConversationIntelligence.PreviousStrategyRecord.initialNone(),
                List.of(),
                null,
                false,
                catalogChunks,
                history
        );

        // 1. Có hợp đồng nhận thức hợp nhất duy nhất <SINGLE_PASS_COGNITIVE_CONTRACT>, không lặp 3 khối cũ
        assertThat(prompt).contains("<SINGLE_PASS_COGNITIVE_CONTRACT>");
        assertThat(prompt).doesNotContain("<STRUCTURED_TURN_CONTRACT>");
        assertThat(prompt).doesNotContain("<AGENT_BRAIN_INSIGHT>");
        assertThat(prompt).doesNotContain("<STRUCTURED_CONVERSATION_STATE>");

        // 2. Mở khóa nhận thức danh mục: LLM thấy cả role=PRIMARY (NS01 399k, NS03 580k) VÀ role=ECONOMICAL_OPTION (DS02 239k, DS03 339k)
        assertThat(prompt).contains("role=PRIMARY").contains("NS01").contains("580");
        assertThat(prompt).contains("role=ECONOMICAL_OPTION").contains("DS02").contains("239");

        // 3. Đánh mã lượt [T1], [T2] và [T_CURRENT] kèm từng bong bóng gốc trong burst
        assertThat(prompt).contains("[T1] Khách: Bên mình có massage cổ vai gáy không?");
        assertThat(prompt).contains("[T2] Bot: Dạ bên em có gói Massage cơ bản 60 phút giá 399k ạ.");
        assertThat(prompt).contains("[T_CURRENT]");
        assertThat(prompt).contains("399k hơi cao nhỉ");
        assertThat(prompt).contains("có gói nào nhẹ tiền hơn không em?");
    }

    @Test
    @DisplayName("Mục 1 & Mục 2.3: S06 ('Thôi, không cần nữa') đi qua LLM với opted_out=false, lệnh 'STOP' chặn ở System Gate với opted_out=true, và rollback DETERMINISTIC_V1 tức thì")
    void s06PoliteDeclineGoesToLlmWithOptedOutFalseWhileStrictStopTriggersSystemGateAndRollbackWorks() {
        AtomicInteger llmCallCount = new AtomicInteger(0);
        AgentBrainService sharedBrain = new AgentBrainService((com.example.docmind.client.AgentBrainClient) null);
        FanpageBotService botService = createIsolatedBotServiceWithBrain(
                AdaptiveConversationIntelligence.CognitiveMode.LLM_COGNITIVE_V2,
                sharedBrain,
                (prompt, budget) -> {
                    llmCallCount.incrementAndGet();
                    return """
                            {
                              "brain_reflection": {
                                "customer_understanding": {
                                  "detected_intent": "POLITE_DECLINE",
                                  "emotional_state": {"primary": "NEUTRAL", "secondary": [], "confidence": 0.85}
                                },
                                "priority": {"primary": "POLITE_CLOSURE", "secondary": []},
                                "working_hypothesis": {"content": "", "lifecycle_op": "MAINTAIN", "confidence": 0.4, "evidence_turn_ids": ["T3"]},
                                "strategy": {"chosen": "WARM_RESPECTFUL_CLOSURE", "adaptation": "STOP", "previous_outcome": "REJECTED", "evidence_turn_ids": ["T3"]},
                                "uncertainties": [],
                                "customer_statements": [],
                                "commitment_delta": [],
                                "handoff_request": {"requested": false, "reason": null},
                                "proposed_booking_patch": {}
                              },
                              "bubbles": [
                                "Dạ vâng ạ, em cảm ơn mình đã ghé nhắn tin cho Nàng Sen Spa nha. Khi nào mỏi người cần thư giãn mình cứ nhắn em bất cứ lúc nào nhé ạ!"
                              ]
                            }
                            """;
                }
        );

        // 1. S06: "Thôi, không cần nữa" trong LLM_COGNITIVE_V2 -> GỌI LLM, khép lại lịch sự, opted_out = false!
        FanpageBotResponse s06Resp = botService.handleCustomerMessage(buildBotRequest(
                "sender-s06",
                "Chị Lan",
                "Thôi, không cần nữa",
                null,
                List.of(
                        new FanpageBotRequest.ConversationTurn("user", "Giá gói massage bên em bao nhiêu?"),
                        new FanpageBotRequest.ConversationTurn("assistant", "Dạ gói Massage cơ bản 60 phút bên em giá 399k ạ.")
                )
        ));
        assertThat(llmCallCount.get()).isEqualTo(1);
        assertThat(s06Resp.triggeredHooks()).doesNotContain("EXPLICIT_OPT_OUT");
        assertThat(s06Resp.replyText()).doesNotContain("từ khóa \"tư vấn\"");
        assertThat(sharedBrain.getAciOperationalState(PAGE_ID, "sender-s06").optedOut()).isFalse();

        // 2. Lệnh opt-out tường minh "ĐỪNG NHẮN NỮA" -> chặn ngay tại System Gate (0 LLM calls mới) và opted_out = true!
        FanpageBotResponse stopResp = botService.handleCustomerMessage(
                buildBotRequest("sender-strict-stop", "Chị Lan", "ĐỪNG NHẮN NỮA", null, List.of())
        );
        assertThat(llmCallCount.get()).isEqualTo(1); // không gọi thêm LLM
        assertThat(stopResp.triggeredHooks()).contains("EXPLICIT_OPT_OUT");
        assertThat(sharedBrain.getAciOperationalState(PAGE_ID, "sender-strict-stop").optedOut()).isTrue();

        // 3. Rollback tức thì về DETERMINISTIC_V1 qua cấu hình cognitiveModeProperty -> hành vi cũ 100% được khôi phục
        botService.setCognitiveModeProperty("DETERMINISTIC_V1");
        assertThat(botService.isLlmCognitiveV2Mode()).isFalse();
        FanpageBotResponse v1RollbackS06 = botService.handleCustomerMessage(
                buildBotRequest("sender-v1-rollback", "Chị Lan", "Thôi, không cần nữa", null, List.of())
        );
        assertThat(llmCallCount.get()).isEqualTo(1); // V1 chặn cứng trước LLM như baseline cũ
        assertThat(v1RollbackS06.triggeredHooks()).contains("EXPLICIT_OPT_OUT");
    }

    // =========================================================================
    // MỤC 3 & MỤC 4: MEMORY 5 TẦNG, WORKING HYPOTHESIS 5 OP, COMMITMENT, HANDOFF, S10B & A03
    // =========================================================================

    @Test
    @DisplayName("Mục 4.2: Vòng đời 5 thao tác của Working Hypothesis (MAINTAIN, REINFORCE, ADJUST, DOWNGRADE, REJECT) không xóa trắng giả thuyết ngắn < 12 ký tự")
    void workingHypothesisFiveOperationLifecyclePreservesShortHypothesisAndHandlesConflict() {
        // 1. Khởi tạo giả thuyết ngắn < 12 ký tự ("Sợ đau") -> KHÔNG bị xóa trắng!
        AdaptiveConversationIntelligence.WorkingHypothesisRecord h1 =
                AdaptiveConversationIntelligence.applyWorkingHypothesisLifecycle(
                        AdaptiveConversationIntelligence.WorkingHypothesisRecord.initialEmpty(),
                        new AdaptiveConversationIntelligence.WorkingHypothesisReflection(
                                "Sợ đau",
                                AdaptiveConversationIntelligence.HypothesisLifecycleOp.ADJUST,
                                0.72,
                                List.of("T1")
                        ),
                        "T1"
                );
        assertThat(h1.content()).isEqualTo("Sợ đau");
        assertThat(h1.confidence()).isEqualTo(0.72);
        assertThat(h1.evidenceTurnIds()).containsExactly("T1");

        // 2. REINFORCE khi lời diễn đạt trùng từ mặt chữ -> tăng độ tin cậy, gộp mã lượt chứng cứ, không xóa!
        AdaptiveConversationIntelligence.WorkingHypothesisRecord h2 =
                AdaptiveConversationIntelligence.applyWorkingHypothesisLifecycle(
                        h1,
                        new AdaptiveConversationIntelligence.WorkingHypothesisReflection(
                                "Sợ đau",
                                AdaptiveConversationIntelligence.HypothesisLifecycleOp.REINFORCE,
                                0.86,
                                List.of("T3")
                        ),
                        "T3"
                );
        assertThat(h2.content()).isEqualTo("Sợ đau");
        assertThat(h2.confidence()).isGreaterThan(h1.confidence());
        assertThat(h2.evidenceTurnIds()).containsExactly("T1", "T3");

        // 3. MAINTAIN giữ nguyên nội dung qua lượt hỏi ngắn
        AdaptiveConversationIntelligence.WorkingHypothesisRecord h3 =
                AdaptiveConversationIntelligence.applyWorkingHypothesisLifecycle(
                        h2,
                        new AdaptiveConversationIntelligence.WorkingHypothesisReflection(
                                "",
                                AdaptiveConversationIntelligence.HypothesisLifecycleOp.MAINTAIN,
                                h2.confidence(),
                                List.of("T5")
                        ),
                        "T5"
                );
        assertThat(h3.content()).isEqualTo("Sợ đau");
        assertThat(h3.evidenceTurnIds()).contains("T1", "T3", "T5");

        // 4. DOWNGRADE hạ độ tin cậy khi có tín hiệu làm yếu giả thuyết
        AdaptiveConversationIntelligence.WorkingHypothesisRecord h4 =
                AdaptiveConversationIntelligence.applyWorkingHypothesisLifecycle(
                        h3,
                        new AdaptiveConversationIntelligence.WorkingHypothesisReflection(
                                "Có thể chỉ ngại lực bấm quá mạnh ở vùng cổ",
                                AdaptiveConversationIntelligence.HypothesisLifecycleOp.DOWNGRADE,
                                0.45,
                                List.of("T7")
                        ),
                        "T7"
                );
        assertThat(h4.confidence()).isLessThan(h3.confidence());
        assertThat(h4.content()).contains("ngại lực bấm");

        // 5. REJECT khi có bằng chứng bác bỏ rõ ràng (VD kịch bản A04) -> xóa giả thuyết cũ, không còn áp đặt lên lượt sau
        AdaptiveConversationIntelligence.WorkingHypothesisRecord h5 =
                AdaptiveConversationIntelligence.applyWorkingHypothesisLifecycle(
                        h4,
                        new AdaptiveConversationIntelligence.WorkingHypothesisReflection(
                                "",
                                AdaptiveConversationIntelligence.HypothesisLifecycleOp.REJECT,
                                0.15,
                                List.of("T9")
                        ),
                        "T9"
                );
        assertThat(h5.content()).isEmpty();
        assertThat(h5.lastLifecycleOp()).isEqualTo(AdaptiveConversationIntelligence.HypothesisLifecycleOp.REJECT);
    }

    @Test
    @DisplayName("Mục 4.3: CommitmentRecord tách biệt commitment_status vs execution_status — cấm LLM tự đánh dấu completed và cấm bịa SLA chưa xác minh")
    void commitmentLifecycleBlocksUnauthorizedLlmCompletionAndUnverifiedSlaUntilBackendAuthorityConfirms() {
        String rawLlmTryingToFakeCompletedAndFakeSla = """
                {
                  "brain_reflection": {
                    "customer_understanding": {"detected_intent": "PAYMENT_STATUS_FOLLOWUP"},
                    "priority": {"primary": "COMMITMENT_FOLLOWUP", "secondary": []},
                    "strategy": {"chosen": "REPORT_HONEST_PENDING_STATUS", "adaptation": "CONTINUATION", "previous_outcome": "NOT_APPLICABLE", "evidence_turn_ids": ["T3"]},
                    "working_hypothesis": {"content": "", "lifecycle_op": "MAINTAIN", "confidence": 0.5, "evidence_turn_ids": ["T3"]},
                    "customer_statements": ["Đã chuyển khoản 399k"],
                    "commitment_delta": [
                      {
                        "operation": "COMPLETED",
                        "content": "Kiểm tra giao dịch chuyển khoản 399k với bộ phận thu ngân",
                        "owner": "CASHIER",
                        "verified_timeframe": "trong 15 phút"
                      }
                    ],
                    "handoff_request": {"requested": true, "reason": "Đối soát chuyển khoản"},
                    "proposed_booking_patch": {}
                  },
                  "bubbles": ["Dạ yêu cầu kiểm tra khoản chuyển của mình vẫn đang được bộ phận thu ngân đối soát, ngay khi có kết quả bên em nhắn mình liền ạ."]
                }
                """;

        CleanAgentOutputRefereeAndSanitizer.StructuredLlmOutput parsed =
                CleanAgentOutputRefereeAndSanitizer.parseStructuredLlmJsonOutput(rawLlmTryingToFakeCompletedAndFakeSla, 10_000);
        assertThat(parsed.parsedFromJson()).isTrue();
        assertThat(parsed.aciReflection()).isNotNull();
        // LLM cố tình gửi "COMPLETED" -> bị chặn hoàn toàn (blockedUnauthorizedCompletionCount == 1, không cho lọt vào commitmentDelta)!
        assertThat(parsed.aciReflection().blockedUnauthorizedCompletionCount()).isEqualTo(1);
        assertThat(parsed.aciReflection().commitmentDelta()).isEmpty();

        AgentBrainService brain = new AgentBrainService((com.example.docmind.client.AgentBrainClient) null);
        // Lượt 1: Tạo cam kết đối soát thanh toán khi Page không có verifiedPageSlaPolicy (null)
        AdaptiveConversationIntelligence.AdaptiveCognitiveReflection createRef =
                CleanAgentOutputRefereeAndSanitizer.parseStructuredLlmJsonOutput("""
                        {
                          "brain_reflection": {
                            "commitment_delta": [
                              {"operation": "CREATE", "content": "Đối soát khoản chuyển 399k với thu ngân", "owner": "CASHIER", "verified_timeframe": "trong 15 phút"}
                            ]
                          },
                          "bubbles": ["Dạ để em chuyển bộ phận thu ngân kiểm tra giao dịch ngay ạ."]
                        }
                        """, 10_000).aciReflection();
        AdaptiveConversationIntelligence.ConditionalHandoffDecision handoffCreated =
                new AdaptiveConversationIntelligence.ConditionalHandoffDecision(
                        true, true, AdaptiveConversationIntelligence.HandoffLifecycleStatus.HANDOFF_CREATED,
                        "PAYMENT_VERIFICATION_HANDOFF", "Đối soát chuyển khoản", "TICKET-S08-001", null
                );
        brain.applyAciTurnMemoryUpdates(
                PAGE_ID, "sender-commit-test", "T1", "Tôi đã chuyển khoản rồi",
                List.of(), createRef, ConversationState.StructuredBookingState.none(),
                handoffCreated, false, null, "VERIFY_PAYMENT_CLAIM"
        );

        List<AdaptiveConversationIntelligence.CommitmentRecord> afterTurn1 =
                brain.getSessionCommitments(PAGE_ID, "sender-commit-test");
        assertThat(afterTurn1).hasSize(1);
        AdaptiveConversationIntelligence.CommitmentRecord rec1 = afterTurn1.get(0);
        assertThat(rec1.commitmentStatus()).isEqualTo(AdaptiveConversationIntelligence.CommitmentStatus.pending);
        assertThat(rec1.executionStatus()).isEqualTo(AdaptiveConversationIntelligence.TaskExecutionStatus.created);
        assertThat(rec1.taskId()).isEqualTo("TICKET-S08-001");
        assertThat(rec1.verifiedTimeframe()).isNull(); // SLA "trong 15 phút" tự bịa bị loại bỏ vì Page chưa cấu hình SLA!

        // Lượt 2 (A06/A09): LLM cố gửi COMPLETED -> trạng thái vẫn giữ nguyên pending!
        brain.applyAciTurnMemoryUpdates(
                PAGE_ID, "sender-commit-test", "T3", "Vụ kiểm tra tiền lúc nãy sao rồi em?",
                List.of(), parsed.aciReflection(), ConversationState.StructuredBookingState.none(),
                null, false, null, "REPORT_HONEST_PENDING_STATUS"
        );
        AdaptiveConversationIntelligence.CommitmentRecord rec2 =
                brain.getSessionCommitments(PAGE_ID, "sender-commit-test").get(0);
        assertThat(rec2.commitmentStatus()).isEqualTo(AdaptiveConversationIntelligence.CommitmentStatus.pending);

        // Chỉ khi Java Core xác nhận hoàn tất kèm bằng chứng xác thực mới chuyển sang completed!
        boolean confirmed = brain.confirmCommitmentCompletedByAuthority(
                PAGE_ID, "sender-commit-test", rec2.commitmentId(), "VCB_TXN_998877_VERIFIED"
        );
        assertThat(confirmed).isTrue();
        AdaptiveConversationIntelligence.CommitmentRecord rec3 =
                brain.getSessionCommitments(PAGE_ID, "sender-commit-test").get(0);
        assertThat(rec3.commitmentStatus()).isEqualTo(AdaptiveConversationIntelligence.CommitmentStatus.completed);
        assertThat(rec3.executionStatus()).isEqualTo(AdaptiveConversationIntelligence.TaskExecutionStatus.completed);
        assertThat(rec3.verificationEvidence()).isEqualTo("VCB_TXN_998877_VERIFIED");
    }

    @Test
    @DisplayName("Mục 4.4: Conditional Handoff — S05 ('Tôi không hài lòng') không tạo ticket mù quáng; S08/S09 tạo HANDOFF_CREATED + giữ nguyên bookingState; lỗi dispatch trả về HANDOFF_FAILED")
    void conditionalHandoffDistinguishesMildDissatisfactionFromVerificationEscalationsAndHandlesDispatchFailure() {
        AgentBrainService sharedBrain = new AgentBrainService((com.example.docmind.client.AgentBrainClient) null);

        // 1. Kịch bản S05 ("Tôi không hài lòng"): Chưa nêu sự cố cụ thể -> LLM lắng nghe & hỏi làm rõ, KHÔNG tạo handoff vô điều kiện!
        FanpageBotService s05Bot = createIsolatedBotServiceWithBrain(
                AdaptiveConversationIntelligence.CognitiveMode.LLM_COGNITIVE_V2,
                sharedBrain,
                (prompt, budget) -> """
                        {
                          "brain_reflection": {
                            "customer_understanding": {"detected_intent": "MILD_DISSATISFACTION"},
                            "priority": {"primary": "SERVICE_RECOVERY", "secondary": []},
                            "strategy": {"chosen": "EMPATHIZE_AND_CLARIFY_ISSUE", "adaptation": "PIVOT", "previous_outcome": "NOT_APPLICABLE", "evidence_turn_ids": ["T1"]},
                            "working_hypothesis": {"content": "Khách có điểm chưa hài lòng cần được lắng nghe cụ thể", "lifecycle_op": "ADJUST", "confidence": 0.75, "evidence_turn_ids": ["T1"]},
                            "uncertainties": ["specific_dissatisfaction_cause"],
                            "customer_statements": ["Tôi không hài lòng"],
                            "commitment_delta": [],
                            "handoff_request": {"requested": true, "reason": "Khách nói không hài lòng chung chung"},
                            "proposed_booking_patch": {}
                          },
                          "bubbles": [
                            "Dạ em rất tiếc khi làm mình chưa hài lòng ạ. Mình có thể chia sẻ giúp em điều đang làm mình phiền lòng để em hỗ trợ hoặc chuyển quản lý xử lý đúng ý mình nhất không ạ?"
                          ]
                        }
                        """
        );
        FanpageBotResponse s05Resp = s05Bot.handleCustomerMessage(
                buildBotRequest("sender-s05", "Chị Hà", "Tôi không hài lòng", null, List.of())
        );
        assertThat(s05Resp.needsHumanHandoff()).isFalse();
        assertThat(s05Bot.getCleanAgentOrchestratorService().getLastHandoffDecision().resultingStatus())
                .isEqualTo(AdaptiveConversationIntelligence.HandoffLifecycleStatus.HANDOFF_REQUESTED);

        // 2. Kịch bản S08 ("Tôi đã chuyển khoản rồi"): Đã có bookingState trước đó -> tạo HANDOFF_CREATED đối soát & KHÔNG reset bookingState!
        ConversationState.StructuredBookingState existingBooking = new ConversationState.StructuredBookingState(
                "NS01",
                "NS01 - Massage cơ bản",
                "tối nay",
                "2026-10-30",
                "19:00",
                "Asia/Ho_Chi_Minh",
                ConversationState.BookingStatus.AWAITING_AVAILABILITY_CHECK,
                List.of("NS01")
        );
        sharedBrain.applyAciTurnMemoryUpdates(
                PAGE_ID, "sender-s08", "T2", "Đặt cho chị gói cơ bản 19h tối nay",
                List.of(), null, existingBooking, null, false, null, "COLLECT_BOOKING_SLOT"
        );
        FanpageBotService s08Bot = createIsolatedBotServiceWithBrain(
                AdaptiveConversationIntelligence.CognitiveMode.LLM_COGNITIVE_V2,
                sharedBrain,
                (prompt, budget) -> """
                        {
                          "brain_reflection": {
                            "customer_understanding": {"detected_intent": "PAYMENT_CLAIM"},
                            "priority": {"primary": "TRUST_AND_PAYMENT_VERIFICATION", "secondary": ["PRESERVE_BOOKING_CONTEXT"]},
                            "strategy": {"chosen": "ACKNOWLEDGE_AND_DISPATCH_PAYMENT_CHECK", "adaptation": "PRIORITY_SHIFT", "previous_outcome": "ACCEPTED", "evidence_turn_ids": ["T3"]},
                            "working_hypothesis": {"content": "", "lifecycle_op": "MAINTAIN", "confidence": 0.7, "evidence_turn_ids": ["T3"]},
                            "uncertainties": ["payment_receipt_or_last4"],
                            "customer_statements": ["Khách báo đã chuyển khoản"],
                            "commitment_delta": [{"operation": "CREATE", "content": "Chuyển bộ phận thu ngân đối soát giao dịch chuyển khoản", "owner": "CASHIER", "verified_timeframe": null}],
                            "handoff_request": {"requested": true, "reason": "Đối soát chuyển khoản cho lịch hẹn 19:00"},
                            "proposed_booking_patch": {}
                          },
                          "bubbles": [
                            "Dạ em ghi nhận thông tin mình báo đã chuyển khoản cho lịch hẹn 19:00 hôm nay ạ.",
                            "Em đã chuyển yêu cầu tới bộ phận thu ngân kiểm tra giao dịch, mình cho em xin ảnh biên lai hoặc tên tài khoản chuyển để bên em đối soát nhanh hơn nhé ạ."
                          ]
                        }
                        """
        );
        FanpageBotResponse s08Resp = s08Bot.handleCustomerMessage(buildBotRequest(
                "sender-s08",
                "Chị Hà",
                "Tôi đã chuyển khoản rồi",
                null,
                List.of(
                        new FanpageBotRequest.ConversationTurn("user", "Đặt cho chị gói Massage cơ bản 19h tối nay"),
                        new FanpageBotRequest.ConversationTurn("assistant", "Dạ để em kiểm tra lịch trống khung 19:00 tối nay cho gói Massage cơ bản 399k ạ.")
                )
        ));
        assertThat(s08Resp.needsHumanHandoff()).isTrue();
        assertThat(s08Resp.handoffCategory()).isEqualTo("PAYMENT_VERIFICATION_HANDOFF");
        assertThat(s08Bot.getCleanAgentOrchestratorService().getLastHandoffDecision().isHandoffCreated()).isTrue();
        // Kiểm tra bookingState cũ KHÔNG bị xóa trắng về NONE!
        ConversationState.StructuredBookingState preservedBooking = sharedBrain.recallBookingState(PAGE_ID, "sender-s08");
        assertThat(preservedBooking.selectedProductId()).isEqualTo("NS01");
        assertThat(preservedBooking.bookingTime()).isEqualTo("19:00");
        assertThat(preservedBooking.status()).isEqualTo(ConversationState.BookingStatus.AWAITING_AVAILABILITY_CHECK);

        // 3. Khi HandoffEventDispatcher gặp sự cố -> HANDOFF_FAILED và Referee chặn bot nói dối "đã chuyển quản lý"
        AtomicInteger failedDispatchLlmCalls = new AtomicInteger(0);
        FanpageBotService failingHandoffBot = createIsolatedBotServiceWithBrain(
                AdaptiveConversationIntelligence.CognitiveMode.LLM_COGNITIVE_V2,
                sharedBrain,
                (prompt, budget) -> {
                    int attempt = failedDispatchLlmCalls.incrementAndGet();
                    if (attempt == 1) {
                        return """
                                {
                                  "brain_reflection": {
                                    "customer_understanding": {"detected_intent": "STAFF_PROMISE_CLAIM"},
                                    "handoff_request": {"requested": true, "reason": "Xác minh lời hứa nhân viên"}
                                  },
                                  "bubbles": ["Dạ em đã chuyển thông tin cho quản lý kiểm tra giúp mình rồi ạ."]
                                }
                                """;
                    }
                    return """
                            {
                              "brain_reflection": {
                                "customer_understanding": {"detected_intent": "STAFF_PROMISE_CLAIM"},
                                "handoff_request": {"requested": true, "reason": "Xác minh lời hứa nhân viên"}
                              },
                              "bubbles": ["Dạ hiện kết nối chuyển phiếu tự động bên em đang gián đoạn nhẹ, mình cho em xin số điện thoại và nội dung nhân viên đã trao đổi để quản lý kiểm tra và liên hệ trực tiếp cho mình nhé ạ."]
                            }
                            """;
                }
        );
        failingHandoffBot.getCleanAgentOrchestratorService().setHandoffEventDispatcher((p, s, cat, reason) ->
                AdaptiveConversationIntelligence.HandoffDispatchResult.failed("QUEUE_BROKER_TIMEOUT")
        );
        FanpageBotResponse s09FailedDispatchResp = failingHandoffBot.handleCustomerMessage(
                buildBotRequest("sender-s09-fail", "Chị Minh", "Nhân viên của bạn đã hứa với tôi giảm thêm 20%", null, List.of())
        );
        assertThat(failingHandoffBot.getCleanAgentOrchestratorService().getLastHandoffDecision().resultingStatus())
                .isEqualTo(AdaptiveConversationIntelligence.HandoffLifecycleStatus.HANDOFF_FAILED);
        assertThat(s09FailedDispatchResp.needsHumanHandoff()).isFalse();
        assertThat(failedDispatchLlmCalls.get()).isEqualTo(2); // Referee phát hiện UNVERIFIED_HANDOFF_SUCCESS_CLAIM ở lần 1 và sửa chuẩn ở lần 2!
        assertThat(s09FailedDispatchResp.replyText()).doesNotContain("em đã chuyển thông tin cho quản lý");
    }

    @Test
    @DisplayName("Mục 3.1 & Mục 4: Sửa triệt để lỗi Split-Brain S10B ('Ừ' không bị tự bịa chốt gói NS01) và hỗ trợ đổi gói -> đổi giờ liên tiếp (A03)")
    void fixesS10bSplitBrainOnBareAffirmAndTracksA03MultiTurnPackageAndTimeSwitches() {
        AgentBrainService sharedBrain = new AgentBrainService((com.example.docmind.client.AgentBrainClient) null);

        // 1. S10B: Bot vừa giải thích giá trị gói 399k (chưa đề xuất đặt lịch), khách đáp cụt "Ừ" -> bookingStatus = NONE, selectedProductId = null!
        FanpageBotService s10bBot = createIsolatedBotServiceWithBrain(
                AdaptiveConversationIntelligence.CognitiveMode.LLM_COGNITIVE_V2,
                sharedBrain,
                (prompt, budget) -> """
                        {
                          "brain_reflection": {
                            "customer_understanding": {"detected_intent": "PASSIVE_ACKNOWLEDGMENT"},
                            "priority": {"primary": "GENTLE_CHECKIN_OR_ALTERNATIVE", "secondary": []},
                            "strategy": {"chosen": "WARM_CHECKIN_WITH_ECONOMICAL_OPTION", "adaptation": "REFINEMENT", "previous_outcome": "PARTIAL", "evidence_turn_ids": ["T3"]},
                            "working_hypothesis": {"content": "Khách vẫn còn cân nhắc mức giá 399k", "lifecycle_op": "MAINTAIN", "confidence": 0.74, "evidence_turn_ids": ["T3"]},
                            "uncertainties": ["preferred_budget_or_package"],
                            "customer_statements": [],
                            "commitment_delta": [],
                            "handoff_request": {"requested": false, "reason": null},
                            "proposed_booking_patch": {}
                          },
                          "bubbles": [
                            "Dạ nếu mình muốn thư giãn nhẹ nhàng tiết kiệm hơn thì bên em có cả gói Dưỡng sinh cơ bản 60 phút giá 239k nữa ạ. Mình đang nghiêng về massage toàn thân hay thư giãn đầu cổ vai nhẹ nhàng hơn ạ?"
                          ]
                        }
                        """
        );
        s10bBot.handleCustomerMessage(buildBotRequest(
                "sender-s10b",
                "Chị Thảo",
                "Ừ",
                null,
                List.of(
                        new FanpageBotRequest.ConversationTurn("user", "399k hơi đắt nhỉ"),
                        new FanpageBotRequest.ConversationTurn("assistant", "Dạ gói Massage cơ bản 399k kéo dài 60 phút đã bao gồm tắm xông thảo dược và buffet thực dưỡng miễn phí sau buổi làm ạ.")
                )
        ));
        ConversationState.StructuredBookingState s10bBooking = sharedBrain.recallBookingState(PAGE_ID, "sender-s10b");
        assertThat(s10bBooking.status()).isEqualTo(ConversationState.BookingStatus.NONE);
        assertThat(s10bBooking.selectedProductId()).isNull();

        // 2. A03: Đổi gói -> đổi giờ liên tiếp qua 3 lượt (NS01 19:00 -> đổi sang DS02 giữ 19:00 -> đổi giờ sang 20:00 giữ DS02, trần AWAITING_AVAILABILITY_CHECK)
        ZoneId vnZone = ZoneId.of("Asia/Ho_Chi_Minh");
        ConversationState.StructuredBookingState step1 = AdaptiveConversationIntelligence.applyAuthoritativeBookingPatch(
                ConversationState.StructuredBookingState.none(),
                new AdaptiveConversationIntelligence.ProposedBookingPatch("NS01", "tối nay", "19:00"),
                "Đặt cho chị gói Massage cơ bản lúc 19h tối nay nhé",
                "Dạ bên em có gói Massage cơ bản 399k và Dưỡng sinh cơ bản 239k ạ.",
                catalogPackages,
                catalogPackages,
                vnZone
        );
        assertThat(step1.selectedProductId()).containsIgnoringCase("NS01");
        assertThat(step1.bookingDate()).isEqualTo("tối nay");
        assertThat(step1.bookingTime()).isEqualTo("19:00");
        assertThat(step1.status()).isEqualTo(ConversationState.BookingStatus.AWAITING_AVAILABILITY_CHECK);

        ConversationState.StructuredBookingState step2SwitchPkg = AdaptiveConversationIntelligence.applyAuthoritativeBookingPatch(
                step1,
                new AdaptiveConversationIntelligence.ProposedBookingPatch("DS02", null, null),
                "À thôi đổi sang gói dưỡng sinh 239k giúp chị nha",
                "Dạ để em kiểm tra lịch trống lúc 19:00 tối nay cho gói Massage cơ bản 399k ạ.",
                catalogPackages,
                catalogPackages,
                vnZone
        );
        assertThat(step2SwitchPkg.selectedProductId()).containsIgnoringCase("DS02");
        assertThat(step2SwitchPkg.bookingDate()).isEqualTo("tối nay");
        assertThat(step2SwitchPkg.bookingTime()).isEqualTo("19:00");

        ConversationState.StructuredBookingState step3SwitchTime = AdaptiveConversationIntelligence.applyAuthoritativeBookingPatch(
                step2SwitchPkg,
                new AdaptiveConversationIntelligence.ProposedBookingPatch(null, null, "20:00"),
                "Đổi giờ sang 20h tối nay luôn giúp chị nhé",
                "Dạ em đã cập nhật sang gói Dưỡng sinh cơ bản 239k lúc 19:00 tối nay để kiểm tra chỗ ạ.",
                catalogPackages,
                catalogPackages,
                vnZone
        );
        assertThat(step3SwitchTime.selectedProductId()).containsIgnoringCase("DS02");
        assertThat(step3SwitchTime.bookingDate()).isEqualTo("tối nay");
        assertThat(step3SwitchTime.bookingTime()).isEqualTo("20:00");
        assertThat(step3SwitchTime.status()).isEqualTo(ConversationState.BookingStatus.AWAITING_AVAILABILITY_CHECK);
    }

    // =========================================================================
    // MỤC 5: REFEREE V2 — SỰ THẬT / QUYỀN HẠN / AN TOÀN (KHÔNG ÁP ĐẶT CHIẾN THUẬT)
    // =========================================================================

    @Test
    @DisplayName("Mục 5.1 & 5.2: Referee V2 phân biệt 3 ngữ nghĩa thời lượng vs cam kết khỏi bệnh cực đoan, chặn tự xác nhận tiền/SLA bịa, và tắt luật cấm downsell sớm")
    void refereeV2EnforcesTruthAuthoritySafetyWithoutStrategicInterference() {
        // 1. Phân biệt chính xác 3 trường hợp ngữ nghĩa của EXTREME_MEDICAL_CURE_CLAIM:
        assertThat(CleanAgentOutputRefereeAndSanitizer.detectExtremeMedicalCureClaim(
                "Dạ bên em có gói Massage cơ bản chăm sóc cổ vai gáy 60 phút giá 399k ạ."))
                .isFalse();
        assertThat(CleanAgentOutputRefereeAndSanitizer.detectExtremeMedicalCureClaim(
                "Khoảng 15 phút đầu ngâm thảo dược ấm là chị đã thấy cơ thể thư giãn dễ chịu hơn rồi ạ."))
                .isFalse();
        assertThat(CleanAgentOutputRefereeAndSanitizer.detectExtremeMedicalCureClaim(
                "Dạ gói này bên em chỉ mất 15 phút là hết đau vai gáy luôn chị yên tâm nhé."))
                .isTrue();
        assertThat(CleanAgentOutputRefereeAndSanitizer.detectExtremeMedicalCureClaim(
                "Bên em cam kết khỏi hẳn đau mỏi lưng chỉ sau 1 buổi đầu tiên ạ."))
                .isTrue();

        // 2. Chặn tự ý xác nhận đã nhận tiền chuyển khoản khi chưa đối soát & chặn tự bịa SLA
        assertThat(CleanAgentOutputRefereeAndSanitizer.detectUnverifiedPaymentConfirmationClaim(
                "Dạ bên em đã nhận được tiền chuyển khoản 399k của mình rồi ạ!", false))
                .isTrue();
        assertThat(CleanAgentOutputRefereeAndSanitizer.detectUnverifiedPaymentConfirmationClaim(
                "Dạ em đã chuyển bộ phận thu ngân kiểm tra giao dịch chuyển khoản của mình ạ.", false))
                .isFalse();

        assertThat(CleanAgentOutputRefereeAndSanitizer.detectUnverifiedSlaTimeframeClaim(
                "Dạ quản lý bên em sẽ gọi lại hỗ trợ mình trong 15 phút nữa ạ.", null))
                .isTrue();
        assertThat(CleanAgentOutputRefereeAndSanitizer.detectUnverifiedSlaTimeframeClaim(
                "Dạ bên em đã chuyển quản lý kiểm tra và sẽ phản hồi mình sớm nhất ngay khi có kết quả ạ.", null))
                .isFalse();

        List<PackageCatalogService.PackageSummary> nonAddons = catalogPackages.stream()
                .filter(p -> !p.isAddon())
                .toList();

        // 3. Chặn sai giá Catalog (PRICE_ENTITY_MISMATCH) nhưng CHO PHÉP giới thiệu gói tiết kiệm DS02 239k ngay lần đầu khách băn khoăn giá!
        List<String> wrongPriceViolations =
                CleanAgentOutputRefereeAndSanitizer.checkAciV2TruthAuthorityAndSafety(
                        List.of(new FanpageBotResponse.ReplyBubble("Dạ gói Massage cơ bản 60 phút bên em giá 250k ạ.", 0)),
                        catalogPackages,
                        nonAddons,
                        List.of(),
                        CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                        ConversationState.StructuredBookingState.none(),
                        catalogPackages,
                        nangSenPersona.resolvedConfig(),
                        "Gói massage cơ bản giá sao em?",
                        false,
                        null,
                        null,
                        AdaptiveConversationIntelligence.AdaptiveCognitiveReflection.empty()
                );
        assertThat(wrongPriceViolations).contains("PRICE_ENTITY_MISMATCH");

        List<String> adaptiveDownsellViolations =
                CleanAgentOutputRefereeAndSanitizer.checkAciV2TruthAuthorityAndSafety(
                        List.of(new FanpageBotResponse.ReplyBubble(
                                "Dạ nếu mình muốn trải nghiệm nhẹ nhàng tiết kiệm hơn thì bên em có gói Dưỡng sinh cơ bản 60 phút giá 239k ở khu dưỡng sinh chung ạ.", 0)),
                        catalogPackages,
                        nonAddons,
                        List.of(),
                        CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                        ConversationState.StructuredBookingState.none(),
                        catalogPackages,
                        nangSenPersona.resolvedConfig(),
                        "399k hơi cao với chị",
                        false,
                        null,
                        null,
                        AdaptiveConversationIntelligence.AdaptiveCognitiveReflection.empty()
                );
        assertThat(adaptiveDownsellViolations)
                .isEmpty();
    }

    // =========================================================================
    // HELPER WIRING FOR ISOLATED IN-MEMORY FANPAGE BOT SERVICE
    // =========================================================================

    private static FanpageBotRequest buildBotRequest(
            String senderId,
            String customerName,
            String message,
            List<String> burstMessages,
            List<FanpageBotRequest.ConversationTurn> history
    ) {
        return new FanpageBotRequest(
                PAGE_ID.toString(),
                senderId,
                customerName,
                message,
                burstMessages,
                history,
                List.of(DOC_ID),
                6
        );
    }

    @FunctionalInterface
    private interface StubLlmResponder {
        String respond(String prompt, ReasoningBudget budget);
    }

    private FanpageBotService createIsolatedBotService(
            AdaptiveConversationIntelligence.CognitiveMode cognitiveMode,
            StubLlmResponder responder
    ) {
        return createIsolatedBotServiceWithBrain(
                cognitiveMode,
                new AgentBrainService((com.example.docmind.client.AgentBrainClient) null),
                responder
        );
    }

    private FanpageBotService createIsolatedBotServiceWithBrain(
            AdaptiveConversationIntelligence.CognitiveMode cognitiveMode,
            AgentBrainService brainService,
            StubLlmResponder responder
    ) {
        RetrievalService retrievalService = mock(RetrievalService.class);
        when(retrievalService.getMetadataCache()).thenReturn(null);
        when(retrievalService.retrieveWithRules(anyList(), anyString(), anyInt(), anyList(), any()))
                .thenReturn(new RetrievalService.RetrievalResult(catalogChunks, List.of()));

        DocumentRepository docRepo = mock(DocumentRepository.class);
        DocumentEntity readyDoc = mock(DocumentEntity.class);
        when(readyDoc.getId()).thenReturn(DOC_ID);
        when(readyDoc.getPageId()).thenReturn(PAGE_ID);
        when(readyDoc.getStatus()).thenReturn("READY");
        when(readyDoc.isCurrentlyEffective()).thenReturn(true);
        when(docRepo.findByPageIdOrderByCreatedAtDesc(PAGE_ID)).thenReturn(List.of(readyDoc));

        KnowledgePageRepository pageRepo = mock(KnowledgePageRepository.class);
        when(pageRepo.findById(PAGE_ID)).thenReturn(Optional.of(nangSenPageEntity));

        AgentSkillService skillService = mock(AgentSkillService.class);
        when(skillService.getActiveSkillsForPage(PAGE_ID)).thenReturn(CustomerInsightEngine.builtInDefaultSkills());

        LlmClient stubLlm = new LlmClient() {
            @Override
            public String name() {
                return "qwen:qwen3.8-flash-unit-test";
            }

            @Override
            public String complete(String prompt) {
                return responder.respond(prompt, null);
            }

            @Override
            public String complete(String prompt, ReasoningBudget budget) {
                return responder.respond(prompt, budget);
            }
        };

        CleanAgentOrchestratorService orchestrator = new CleanAgentOrchestratorService(stubLlm, brainService, skillService);
        orchestrator.setCognitiveMode(cognitiveMode);

        FanpageBotService service = new FanpageBotService(
                retrievalService,
                stubLlm,
                new ChatProperties(),
                docRepo,
                pageRepo,
                new CustomerInsightEngine(),
                skillService
        );
        service.setAgentMode("CLEAN");
        service.setCognitiveMode(cognitiveMode);
        try {
            java.lang.reflect.Field fBrain = FanpageBotService.class.getDeclaredField("agentBrainService");
            fBrain.setAccessible(true);
            fBrain.set(service, brainService);

            java.lang.reflect.Field fOrch = FanpageBotService.class.getDeclaredField("cleanAgentOrchestratorService");
            fOrch.setAccessible(true);
            fOrch.set(service, orchestrator);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return service;
    }

    @Test
    @DisplayName("An toàn vận hành: Lỗi mạng '[Qwen-error]' không bao giờ lộ ra bong bóng khách; báo CK kèm số tiền ('chuyển khoản 399k rồi') tạo HANDOFF_CREATED; câu xác minh lời hứa voucher không bị bắt nhầm CROSS_TIER_EXCLUSIVE_ATTRIBUTE_MISMATCH")
    void upstreamClientErrorPayloadNeverLeaksAndPaymentWithAmountAndVoucherPromisePassCleanly() {
        // 1. Khi upstream LLM trả về "[Qwen-error]..." hoặc chuỗi suy luận nội tâm CoT bị cắt cụt -> tuyệt đối không lộ ra khách, kích hoạt Safe Structural Fallback
        FanpageBotService timeoutBot = createIsolatedBotService(
                AdaptiveConversationIntelligence.CognitiveMode.LLM_COGNITIVE_V2,
                (prompt, budget) -> "[Qwen-error] Không thể kết nối hoặc nhận phản hồi từ qwen (Request cancelled)"
        );
        FanpageBotResponse timeoutResp = timeoutBot.handleCustomerMessage(
                buildBotRequest("sender-timeout-1", "Chị Lan", "Tư vấn gói massage giúp chị với", null, List.of())
        );
        assertThat(timeoutResp.replyText()).doesNotContain("[Qwen-error]");
        assertThat(timeoutResp.replyText()).doesNotContain("Request cancelled");
        assertThat(timeoutResp.replyBubbles()).isNotEmpty();

        FanpageBotService leakedCotBot = createIsolatedBotService(
                AdaptiveConversationIntelligence.CognitiveMode.LLM_COGNITIVE_V2,
                (prompt, budget) -> "We need answer user's request. Need produce final JSON only no markdown. brain_reflection"
        );
        FanpageBotResponse leakedCotResp = leakedCotBot.handleCustomerMessage(
                buildBotRequest("sender-cot-1", "Chị Lan", "Tư vấn gói massage giúp chị với", null, List.of())
        );
        assertThat(leakedCotResp.replyText()).doesNotContain("We need answer");
        assertThat(leakedCotResp.replyText()).doesNotContain("brain_reflection");
        assertThat(leakedCotResp.replyBubbles()).isNotEmpty();

        // 2. Báo chuyển khoản có chèn số tiền ở giữa ("Chị vừa chuyển khoản 399k rồi đó") -> isPaymentClaimMessage=true & HANDOFF_CREATED
        assertThat(AdaptiveConversationIntelligence.isPaymentClaimMessage(
                "Chị vừa chuyển khoản 399k rồi đó, em bảo thu ngân kiểm tra giúp chị nhé")).isTrue();
        FanpageBotService paymentWithAmtBot = createIsolatedBotService(
                AdaptiveConversationIntelligence.CognitiveMode.LLM_COGNITIVE_V2,
                (prompt, budget) -> """
                        {
                          "brain_reflection": {
                            "customer_understanding": {"detected_intent": "PAYMENT_CLAIM"},
                            "priority": {"primary": "BOOKING_COMPLETION", "secondary": ["CLARIFICATION"]},
                            "strategy": {"chosen": "VERIFY_PAYMENT_WITH_CASHIER", "adaptation": "CONTINUATION", "previous_outcome": "ACCEPTED", "evidence_turn_ids": ["T1"]},
                            "handoff_request": {"requested": true, "reason": "Đối soát chuyển khoản 399k"}
                          },
                          "bubbles": ["Dạ em đã ghi nhận chị báo chuyển khoản 399k và chuyển thu ngân đối soát giao dịch giúp chị ngay nhé ạ."]
                        }
                        """
        );
        FanpageBotResponse payResp = paymentWithAmtBot.handleCustomerMessage(
                buildBotRequest("sender-pay-399", "Chị Lan", "Chị vừa chuyển khoản 399k rồi đó, em bảo thu ngân kiểm tra giúp chị nhé", null, List.of())
        );
        assertThat(paymentWithAmtBot.getCleanAgentOrchestratorService().getLastHandoffDecision().resultingStatus())
                .isEqualTo(AdaptiveConversationIntelligence.HandoffLifecycleStatus.HANDOFF_CREATED);
        assertThat(payResp.needsHumanHandoff()).isTrue();

        // 3. Xác minh lời hứa voucher có nhắc từ khóa dịch vụ ("tẩy da chết") mà không gán sai vào gói cụ thể -> 0 vi phạm Referee
        List<PackageCatalogService.PackageSummary> nonAddons = catalogPackages.stream().filter(p -> !p.isAddon()).toList();
        List<String> violations = CleanAgentOutputRefereeAndSanitizer.checkAciV2TruthAuthorityAndSafety(
                List.of(new FanpageBotResponse.ReplyBubble(
                        "Dạ em đã ghi nhận thông tin bạn lễ tân hứa tặng voucher tẩy da chết miễn phí của chị và đang chuyển quản lý kiểm tra xác nhận lại cho chị nhé ạ.",
                        0
                )),
                catalogPackages,
                nonAddons,
                List.of(),
                null,
                ConversationState.StructuredBookingState.none(),
                List.of(),
                null,
                "Hôm trước bạn nhân viên lễ tân hứa tặng chị voucher tẩy da chết miễn phí cho lần ghé sau, em nhờ quản lý kiểm tra xác nhận giúp chị nhé",
                false,
                null,
                new AdaptiveConversationIntelligence.ConditionalHandoffDecision(
                        true, true, AdaptiveConversationIntelligence.HandoffLifecycleStatus.HANDOFF_CREATED,
                        "PROMISE_DISPUTE_HANDOFF", "Xác minh lời hứa nhân viên", "TICKET-A09", null
                ),
                AdaptiveConversationIntelligence.AdaptiveCognitiveReflection.empty()
        );
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Task 1: Tách bạch defaultCustomerPronoun vs inferredCustomerPronoun — chống ghi đè bởi customerName 'Chị ...', chống bẫy ngôi thứ ba, và nhận tức thì khi đổi cấu hình UI Page")
    void pronounSeparationPreventsThirdPersonTrapAndImmediatelyAppliesUiPageConfigUpdate() throws Exception {
        AgentBrainService sharedBrain = new AgentBrainService((com.example.docmind.client.AgentBrainClient) null);
        AtomicReference<String> lastPrompt = new AtomicReference<>("");
        AtomicReference<String> nextInferredJson = new AtomicReference<>("\"inferred_customer_pronoun\": null, \"pronoun_confidence\": 0.0");

        RetrievalService retrievalService = mock(RetrievalService.class);
        when(retrievalService.getMetadataCache()).thenReturn(null);
        when(retrievalService.retrieveWithRules(anyList(), anyString(), anyInt(), anyList(), any()))
                .thenReturn(new RetrievalService.RetrievalResult(catalogChunks, List.of()));
        when(retrievalService.getAllActiveApprovedRulesForPage(PAGE_ID)).thenReturn(List.of());

        DocumentRepository docRepo = mock(DocumentRepository.class);
        DocumentEntity readyDoc = mock(DocumentEntity.class);
        when(readyDoc.getId()).thenReturn(DOC_ID);
        when(readyDoc.getPageId()).thenReturn(PAGE_ID);
        when(readyDoc.getStatus()).thenReturn("READY");
        when(readyDoc.isCurrentlyEffective()).thenReturn(true);
        when(docRepo.findByPageIdOrderByCreatedAtDesc(PAGE_ID)).thenReturn(List.of(readyDoc));

        AtomicReference<KnowledgePageEntity> dynamicPageEntity = new AtomicReference<>(nangSenPageEntity);
        KnowledgePageRepository pageRepo = mock(KnowledgePageRepository.class);
        when(pageRepo.findById(PAGE_ID)).thenAnswer(inv -> Optional.of(dynamicPageEntity.get()));

        AgentSkillService skillService = mock(AgentSkillService.class);
        when(skillService.getActiveSkillsForPage(PAGE_ID)).thenReturn(CustomerInsightEngine.builtInDefaultSkills());

        LlmClient stubLlm = new LlmClient() {
            @Override
            public String name() {
                return "qwen:qwen3.8-flash-pronoun-test";
            }

            @Override
            public String complete(String prompt) {
                return complete(prompt, null);
            }

            @Override
            public String complete(String prompt, ReasoningBudget budget) {
                lastPrompt.set(prompt);
                return """
                        {
                          "brain_reflection": {
                            "customer_understanding": {
                              "detected_intent": "PRICE_INQUIRY",
                              "emotional_state": {"primary": "CALM", "secondary": [], "confidence": 0.90},
                              %s,
                              "cost_concern_expressed": false,
                              "verified_symptom_duration": null
                            },
                            "priority": {"primary": "PRICE_INFORMATION", "secondary": []},
                            "working_hypothesis": {"content": "Khách hỏi thông tin giá gói massage", "lifecycle_op": "ADJUST", "confidence": 0.80, "evidence_turn_ids": ["T_CURRENT"]},
                            "strategy": {"chosen": "DIRECT_GROUNDED_QUOTE", "adaptation": "CONTINUATION", "previous_outcome": "NOT_APPLICABLE", "evidence_turn_ids": ["T_CURRENT"]},
                            "uncertainties": [],
                            "customer_statements": [],
                            "commitment_delta": [],
                            "handoff_request": {"requested": false, "reason": null},
                            "proposed_booking_patch": {}
                          },
                          "bubbles": ["Dạ bên em có gói Massage cơ bản 60 phút giá 399k ạ."]
                        }
                        """.formatted(nextInferredJson.get());
            }
        };

        CleanAgentOrchestratorService orchestrator = new CleanAgentOrchestratorService(stubLlm, sharedBrain, skillService);
        orchestrator.setCognitiveMode(AdaptiveConversationIntelligence.CognitiveMode.LLM_COGNITIVE_V2);
        FanpageBotService service = new FanpageBotService(
                retrievalService, stubLlm, new ChatProperties(), docRepo, pageRepo, new CustomerInsightEngine(), skillService
        );
        service.setAgentMode("CLEAN");
        service.setCognitiveMode(AdaptiveConversationIntelligence.CognitiveMode.LLM_COGNITIVE_V2);
        java.lang.reflect.Field fBrain = FanpageBotService.class.getDeclaredField("agentBrainService");
        fBrain.setAccessible(true);
        fBrain.set(service, sharedBrain);
        java.lang.reflect.Field fOrch = FanpageBotService.class.getDeclaredField("cleanAgentOrchestratorService");
        fOrch.setAccessible(true);
        fOrch.set(service, orchestrator);

        // 1. Kể cả khi fixture customerName = "Chị Khách Thực Nghiệm" và câu nhắc tới ngôi thứ ba ("Chị gái mình muốn hỏi giá", "Anh kỹ thuật viên hôm trước") -> vẫn giữ "mình"!
        FanpageBotResponse thirdPersonResp = service.handleCustomerMessage(buildBotRequest(
                "sender-pronoun-trap",
                "Chị Khách Thực Nghiệm",
                "Chị gái mình muốn hỏi giá massage, anh kỹ thuật viên hôm trước làm tốt lắm",
                null,
                List.of()
        ));
        assertThat(lastPrompt.get()).contains("default_customer_pronoun: \"mình\"");
        assertThat(lastPrompt.get()).contains("mandatory_pronouns: { self: \"em\", customer: \"mình\" }");
        assertThat(sharedBrain.getInferredCustomerPronoun(PAGE_ID, "sender-pronoun-trap")).isNull();
        assertThat(thirdPersonResp.customerInsight().honorific()).isEqualTo("mình");

        // 2. Quản trị viên đổi default_customer_pronoun trên UI Page từ "mình" sang "bạn" -> lượt kế tiếp nhận ngay "bạn"!
        PagePersonaConfig updatedUiCfg = new PagePersonaConfig(
                "BEAUTY_SPA", "kỹ thuật viên", "em", "bạn",
                "Ấm áp, tôn trọng", "NONE", List.of("chốt đơn"),
                List.of("massage"), null, null, List.of(), 10000,
                null, null, null, null, List.of(), List.of(), Map.of(), Map.of()
        );
        dynamicPageEntity.set(KnowledgePageEntity.builder()
                .id(PAGE_ID)
                .name("Nàng Sen Spa")
                .industry("BEAUTY_SPA")
                .personaConfigJson(new ObjectMapper().writeValueAsString(updatedUiCfg))
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build());

        FanpageBotResponse afterUiSaveResp = service.handleCustomerMessage(buildBotRequest(
                "sender-pronoun-trap",
                "Chị Khách Thực Nghiệm",
                "Gói 399k làm trong bao lâu?",
                null,
                List.of()
        ));
        assertThat(lastPrompt.get()).contains("default_customer_pronoun: \"bạn\"");
        assertThat(lastPrompt.get()).contains("mandatory_pronouns: { self: \"em\", customer: \"bạn\" }");
        assertThat(afterUiSaveResp.customerInsight().honorific()).isEqualTo("bạn");

        // 3. Khi khách trực tiếp tự xưng ngôi thứ nhất với độ tin cậy cao >= 0.85 ("Tư vấn giúp anh nhé") -> inferredCustomerPronoun cập nhật thành "Anh"
        nextInferredJson.set("\"inferred_customer_pronoun\": \"anh\", \"pronoun_confidence\": 0.94");
        FanpageBotResponse speakerSelfIdResp = service.handleCustomerMessage(buildBotRequest(
                "sender-pronoun-trap",
                "Khách",
                "Tư vấn kỹ hơn gói này giúp anh nhé",
                null,
                List.of()
        ));
        assertThat(sharedBrain.getInferredCustomerPronoun(PAGE_ID, "sender-pronoun-trap")).isEqualTo("Anh");
        assertThat(speakerSelfIdResp.customerInsight().honorific()).isEqualTo("Anh");
    }

    @Test
    @DisplayName("Task 2 & Task 4.1: Nạp động 100% Compact Directives (agent_skills + learned_rules), cưỡng chế forbidden_words, và không ghi đè symptomDuration='1 ngày' ở ca '1 ngày công r'")
    void uiPageConfigPlaybookCompactDirectivesForbiddenWordsAndWageMetaphorWorkCleanly() {
        AgentBrainService sharedBrain = new AgentBrainService((com.example.docmind.client.AgentBrainClient) null);
        AtomicReference<String> capturedPrompt = new AtomicReference<>("");
        AtomicInteger llmAttempts = new AtomicInteger(0);

        com.example.docmind.dto.LearnedRuleDto activeRule = new com.example.docmind.dto.LearnedRuleDto(
                UUID.randomUUID(), "PRICE_STRATEGY", "Chị thấy đắt quá", "",
                "Đồng cảm chi phí và làm rõ giá trị trọn gói không phụ phí trước khi gợi ý gói tiết kiệm",
                List.of("đắt", "giá"), true, PAGE_ID.toString(), 0.95, 3, 3, 0, 1, null, null,
                Instant.now(), Instant.now(), Instant.now(), "DOM", "price_hesitation", null, true,
                "APPROVED", null, "NS_RULE_PRICE_01", "Giải thích giá trị trọn gói", "BUDGET_FIT",
                "VALUE_CONSULTATION", 92,
                "Đồng cảm chi phí và nhấn mạnh giá trọn gói không tip",
                "Không ép chốt vội khi khách đang so sánh ngân sách",
                "must_use_kb_document_context", "none", "MANUAL"
        );
        com.example.docmind.dto.LearnedRuleDto disabledRule = new com.example.docmind.dto.LearnedRuleDto(
                UUID.randomUUID(), "OLD_RULE", "", "", "Quy tắc đã tắt không được phép đưa vào prompt",
                List.of(), false, PAGE_ID.toString(), 0.80, 0, 0, 0, 1, null, null,
                Instant.now(), Instant.now(), Instant.now(), "DOM", null, null, false,
                "APPROVED", null, "NS_RULE_DISABLED_99", "Quy tắc đã tắt", null, null, 50,
                "Quy tắc đã tắt không được phép đưa vào prompt", null, null, null, "MANUAL"
        );

        com.example.docmind.dto.bot.AgentSkillDto enabledPageSkill = new com.example.docmind.dto.bot.AgentSkillDto(
                UUID.randomUUID(),
                "price_objection_sensory_value",
                "Thấu cảm giá & Khắc họa chiều sâu thư giãn giác quan (Nàng Sen)",
                "SALES_STRATEGY",
                "Đồng cảm nhẹ nhàng khi khách thấy giá cao và làm rõ chiều sâu trải nghiệm.",
                "đắt, giá cao, 1 ngày công",
                "[Tình huống tâm lý khách] Khách băn khoăn chi phí -> [Nghệ thuật dẫn dắt & Ranh giới sự thật] Đồng cảm nhẹ nhàng, không tranh cãi hơn thua và giới thiệu phương án tiết kiệm phù hợp -> [Các hướng diễn đạt gợi cảm xúc theo ngữ cảnh] \"Chị cứ thong thả cân nhắc ạ\"",
                null,
                "Không ép chốt vội hoặc lặp lại văn mẫu",
                97,
                true,
                PAGE_ID.toString(),
                Instant.now(),
                Instant.now()
        );
        com.example.docmind.dto.bot.AgentSkillDto disabledPageSkill = new com.example.docmind.dto.bot.AgentSkillDto(
                UUID.randomUUID(),
                "disabled_page_skill_99",
                "Kỹ năng đã tắt trên UI Page",
                "SALES_STRATEGY",
                "Không được nạp vào prompt khi active=false",
                "",
                "Không được xuất hiện trong prompt",
                null,
                null,
                40,
                false,
                PAGE_ID.toString(),
                Instant.now(),
                Instant.now()
        );

        FanpageBotService botService = createIsolatedBotServiceWithBrain(
                AdaptiveConversationIntelligence.CognitiveMode.LLM_COGNITIVE_V2,
                sharedBrain,
                (prompt, budget) -> {
                    capturedPrompt.set(prompt);
                    int n = llmAttempts.incrementAndGet();
                    // Lần 1 cố tình dùng từ cấm "chốt đơn" của Page -> Referee V2 phải bắt FORBIDDEN_PAGE_WORD_USED:chốt đơn và gọi repair lần 2!
                    String bubble = (n == 1)
                            ? "Dạ em hiểu 399k bằng cả ngày công nên mình cân nhắc kỹ ạ, mình chốt đơn luôn nha!"
                            : "Dạ em hiểu 399k tương đương cả ngày đi làm nên mình cân nhắc là rất hợp lý ạ. Nếu muốn nhẹ chi phí hơn mình có thể tham khảo gói Dưỡng sinh cơ bản 60 phút giá 239k ạ.";
                    return """
                            {
                              "brain_reflection": {
                                "customer_understanding": {
                                  "detected_intent": "COST_METAPHOR_HESITATION",
                                  "emotional_state": {"primary": "HESITANT", "secondary": [], "confidence": 0.91},
                                  "inferred_customer_pronoun": null,
                                  "pronoun_confidence": 0.0,
                                  "cost_concern_expressed": true,
                                  "verified_symptom_duration": null
                                },
                                "priority": {"primary": "BUDGET_FIT", "secondary": ["VALUE_ALIGNMENT"]},
                                "working_hypothesis": {
                                  "content": "Khách thấy mức 399k chiếm trọn thu nhập một ngày công nên cần phương án vừa túi tiền hơn",
                                  "lifecycle_op": "REINFORCE",
                                  "confidence": 0.89,
                                  "evidence_turn_ids": ["T3", "T_CURRENT"]
                                },
                                "strategy": {
                                  "chosen": "EMPATHIZE_WAGE_METAPHOR_AND_OFFER_239K_OPTION",
                                  "adaptation": "PIVOT",
                                  "previous_outcome": "REJECTED",
                                  "evidence_turn_ids": ["T_CURRENT"]
                                },
                                "uncertainties": [],
                                "customer_statements": ["1 ngày công r"],
                                "commitment_delta": [],
                                "handoff_request": {"requested": false, "reason": null},
                                "proposed_booking_patch": {}
                              },
                              "bubbles": ["%s"]
                            }
                            """.formatted(bubble);
                }
        );

        // Tiêm danh sách learned_rules và agent_skills thuộc Page vào mock RetrievalService & AgentSkillService
        try {
            java.lang.reflect.Field fRet = FanpageBotService.class.getDeclaredField("retrievalService");
            fRet.setAccessible(true);
            RetrievalService mockedRet = (RetrievalService) fRet.get(botService);
            when(mockedRet.getAllActiveApprovedRulesForPage(PAGE_ID)).thenReturn(List.of(activeRule, disabledRule));

            AgentSkillService mockedSkillSvc = mock(AgentSkillService.class);
            when(mockedSkillSvc.getActiveSkillsForPage(PAGE_ID)).thenReturn(List.of(enabledPageSkill, disabledPageSkill));
            java.lang.reflect.Field fSkill = FanpageBotService.class.getDeclaredField("agentSkillService");
            fSkill.setAccessible(true);
            fSkill.set(botService, mockedSkillSvc);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        List<FanpageBotRequest.ConversationTurn> priorTurns = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Giá massage toàn thân bao nhiêu?"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ bên em có gói Massage cơ bản 60 phút giá 399k ạ."),
                new FanpageBotRequest.ConversationTurn("user", "Đắt"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ gói Massage cơ bản 399k kéo dài 60 phút đã trọn gói xông thảo dược và ăn nhẹ thực dưỡng không phụ phí ạ.")
        );

        FanpageBotResponse turn3Resp = botService.handleCustomerMessage(buildBotRequest(
                "sender-1-ngay-cong",
                "Khách Web UI",
                "1 ngày công r",
                null,
                priorTurns
        ));

        // 1. Compact Directives có mặt đầy đủ cho rule đang active + skills đang enabled thuộc Page,
        //    không chứa skill/rule đã tắt, không nạp ~50 skill mẫu mặc định trong mã Java, và không gây phình prompt
        assertThat(capturedPrompt.get()).contains("<PAGE_PLAYBOOK_COMPACT_DIRECTIVES>");
        assertThat(capturedPrompt.get()).contains("price_objection_sensory_value");
        assertThat(capturedPrompt.get()).doesNotContain("disabled_page_skill_99");
        assertThat(capturedPrompt.get()).doesNotContain("PSYCH_CHRONIC_EMPATHY");
        assertThat(capturedPrompt.get()).doesNotContain("Chị cứ thong thả cân nhắc ạ");
        assertThat(capturedPrompt.get()).contains("NS_RULE_PRICE_01");
        assertThat(capturedPrompt.get()).doesNotContain("NS_RULE_DISABLED_99");
        assertThat(capturedPrompt.get()).doesNotContain("Chị thấy đắt quá");
        assertThat(capturedPrompt.get()).contains("bot_role: \"nhân viên chăm sóc\"");
        assertThat(capturedPrompt.get()).contains("care_actor: \"kỹ thuật viên\"");
        assertThat(capturedPrompt.get()).contains("forbidden_words: [\"chốt đơn\"");
        ThinkingModePolicy.AciTurnTelemetry turn3Telemetry = ThinkingModePolicy.getLastAciTurnTelemetry();
        assertThat(turn3Telemetry).isNotNull();
        assertThat(turn3Telemetry.promptChars()).isLessThan(turn3Telemetry.legacyEquivalentPromptChars());

        // 2. Referee V2 đã chặn từ cấm "chốt đơn" ở lần nháp 1 và sửa sạch ở lần 2
        assertThat(llmAttempts.get()).isEqualTo(2);
        assertThat(turn3Resp.replyText()).doesNotContainIgnoringCase("chốt đơn");

        // 3. Ẩn dụ "1 ngày công r" tăng priceObjectionCount qua LLM reflection và TUYỆT ĐỐI KHÔNG ghi đè symptomDuration = "1 ngày"!
        assertThat(sharedBrain.getPriceObjectionCount(PAGE_ID, "sender-1-ngay-cong")).isEqualTo(1);
        assertThat(sharedBrain.getVerifiedSymptomDuration(PAGE_ID, "sender-1-ngay-cong")).isNull();
        ConversationState sessionAfterTurn3 = ConversationState.getSessionState(PAGE_ID, "sender-1-ngay-cong", null);
        assertThat(sessionAfterTurn3).isNotNull();
        assertThat(sessionAfterTurn3.knownDurationOfProblem()).isNull();
    }

    @Test
    @DisplayName("Task 3: ConversationInboxService (/api/conversations/{id}/customer-turn) giữ nguyên typing_delay_ms trên từng bong bóng và dùng đúng đại từ mặc định của Page")
    void conversationInboxCustomerTurnPreservesTypingDelayMsAndPageDefaultPronoun() {
        com.example.docmind.repository.ConversationSessionRepository sessionRepo =
                mock(com.example.docmind.repository.ConversationSessionRepository.class);
        com.example.docmind.repository.ConversationMessageRepository msgRepo =
                mock(com.example.docmind.repository.ConversationMessageRepository.class);
        KnowledgePageRepository pageRepo = mock(KnowledgePageRepository.class);
        when(pageRepo.findById(PAGE_ID)).thenReturn(Optional.of(nangSenPageEntity));

        List<com.example.docmind.entity.ConversationMessageEntity> savedMsgs = new ArrayList<>();
        AtomicReference<com.example.docmind.entity.ConversationSessionEntity> savedSessionRef = new AtomicReference<>();

        when(sessionRepo.saveAndFlush(any())).thenAnswer(inv -> {
            com.example.docmind.entity.ConversationSessionEntity s = inv.getArgument(0);
            savedSessionRef.set(s);
            return s;
        });
        when(sessionRepo.save(any())).thenAnswer(inv -> {
            com.example.docmind.entity.ConversationSessionEntity s = inv.getArgument(0);
            savedSessionRef.set(s);
            return s;
        });
        when(sessionRepo.findById(any())).thenAnswer(inv -> Optional.ofNullable(savedSessionRef.get()));
        when(msgRepo.save(any())).thenAnswer(inv -> {
            com.example.docmind.entity.ConversationMessageEntity m = inv.getArgument(0);
            savedMsgs.add(m);
            return m;
        });
        when(msgRepo.findBySessionIdOrderByCreatedAtAsc(any())).thenAnswer(inv -> List.copyOf(savedMsgs));

        FanpageBotService botService = createIsolatedBotService(
                AdaptiveConversationIntelligence.CognitiveMode.LLM_COGNITIVE_V2,
                (prompt, budget) -> """
                        {
                          "brain_reflection": {
                            "customer_understanding": {
                              "detected_intent": "PRICE_QUOTE_REQUEST",
                              "emotional_state": {"primary": "CALM", "secondary": [], "confidence": 0.90},
                              "inferred_customer_pronoun": null,
                              "pronoun_confidence": 0.0,
                              "cost_concern_expressed": false,
                              "verified_symptom_duration": null
                            },
                            "priority": {"primary": "PRICE_INFORMATION", "secondary": []},
                            "working_hypothesis": {"content": "Khách hỏi giá massage toàn thân", "lifecycle_op": "ADJUST", "confidence": 0.85, "evidence_turn_ids": ["T_CURRENT"]},
                            "strategy": {"chosen": "QUOTE_399K_AND_ASK_PREFERENCE", "adaptation": "CONTINUATION", "previous_outcome": "NOT_APPLICABLE", "evidence_turn_ids": ["T_CURRENT"]},
                            "uncertainties": [],
                            "customer_statements": [],
                            "commitment_delta": [],
                            "handoff_request": {"requested": false, "reason": null},
                            "proposed_booking_patch": {}
                          },
                          "bubbles": [
                            "Dạ bên em có gói Massage cơ bản toàn thân 60 phút giá 399k trọn gói ạ.",
                            "Mình muốn ghé trải nghiệm vào khung giờ nào để em kiểm tra phòng trống cho mình nha?"
                          ]
                        }
                        """
        );

        ConversationInboxService inboxService = new ConversationInboxService(
                sessionRepo, msgRepo, botService, null, null, null, pageRepo
        );

        Map<String, Object> detail = inboxService.createNewCustomerSession(
                PAGE_ID, "Khách Web UI", "Giá massage toàn thân bao nhiêu?"
        );

        // 1. Đại từ phiên khởi tạo và sau lượt đầu đều là "mình" (không bị gán cứng "Chị")
        assertThat(detail.get("honorific")).isEqualTo("mình");

        // 2. Top-level reply_bubbles và messages[last].reply_bubbles đều chứa đầy đủ typing_delay_ms=[0, 10000]
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> topBubbles = (List<Map<String, Object>>) detail.get("reply_bubbles");
        assertThat(topBubbles).hasSize(2);
        assertThat(topBubbles.get(0).get("typing_delay_ms")).isEqualTo(0);
        assertThat(topBubbles.get(1).get("typing_delay_ms")).isEqualTo(10_000);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) detail.get("messages");
        assertThat(messages).hasSize(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> persistedBotBubbles = (List<Map<String, Object>>) messages.get(1).get("reply_bubbles");
        assertThat(persistedBotBubbles).hasSize(2);
        assertThat(persistedBotBubbles.get(0).get("typing_delay_ms")).isEqualTo(0);
        assertThat(persistedBotBubbles.get(1).get("typing_delay_ms")).isEqualTo(10_000);
    }

    @Test
    @DisplayName("Conversational UX Refinement: Tách availableSkills (6) vs activatedSkills (1-2), parse structured concise reflection, profiling đầy đủ & không tự handoff/chẩn đoán ở 'Tôi hay hoa mắt chóng mặt'")
    void conversationalUxRefinementSeparatesActivatedSkillsAndHandlesHealthSymptomWithoutPrematureHandoff() throws Exception {
        AgentBrainService sharedBrain = new AgentBrainService("LLM_COGNITIVE_V2");
        List<com.example.docmind.dto.bot.AgentSkillDto> sixPageSkills = List.of(
                new com.example.docmind.dto.bot.AgentSkillDto(UUID.randomUUID(), "discovery_empathy_and_scope", "Đồng cảm giác quan & Khám phá vùng mỏi", "PSYCHOLOGY", "", "Khám phá vùng mỏi", "", null, null, 98, true, PAGE_ID.toString(), Instant.now(), Instant.now()),
                new com.example.docmind.dto.bot.AgentSkillDto(UUID.randomUUID(), "price_objection_sensory_value", "Thấu cảm giá & Giá trị thư giãn", "PSYCHOLOGY", "", "Thấu cảm giá", "", null, null, 97, true, PAGE_ID.toString(), Instant.now(), Instant.now()),
                new com.example.docmind.dto.bot.AgentSkillDto(UUID.randomUUID(), "soft_economical_bridge_and_compare", "Nhịp cầu lựa chọn & So sánh tinh tế", "SALES_STRATEGY", "", "So sánh tinh tế", "", null, null, 96, true, PAGE_ID.toString(), Instant.now(), Instant.now()),
                new com.example.docmind.dto.bot.AgentSkillDto(UUID.randomUUID(), "warm_nod_to_gentle_scheduling", "Đón nhận gật đầu & Mời khung giờ", "COMMUNICATION", "", "Mời khung giờ", "", null, null, 95, true, PAGE_ID.toString(), Instant.now(), Instant.now()),
                new com.example.docmind.dto.bot.AgentSkillDto(UUID.randomUUID(), "booking_careful_preparation", "Chuẩn bị đón tiếp & Kiểm tra lịch trống", "SALES_STRATEGY", "", "Kiểm tra lịch trống", "", null, null, 94, true, PAGE_ID.toString(), Instant.now(), Instant.now()),
                new com.example.docmind.dto.bot.AgentSkillDto(UUID.randomUUID(), "graceful_pause_keep_warmth", "Tôn trọng khoảng nghỉ", "COMMUNICATION", "", "Khoảng nghỉ", "", null, null, 93, true, PAGE_ID.toString(), Instant.now(), Instant.now())
        );

        FanpageBotService botService = createIsolatedBotServiceWithBrain(
                AdaptiveConversationIntelligence.CognitiveMode.LLM_COGNITIVE_V2,
                sharedBrain,
                (prompt, budget) -> """
                        {
                          "brain_reflection": {
                            "customer_understanding": {
                              "detected_intent": "nêu biểu hiện hoa mắt chóng mặt sau hỏi giá",
                              "emotional_state": {"primary": "TIRED", "secondary": [], "confidence": 0.86},
                              "inferred_customer_pronoun": null,
                              "pronoun_confidence": 0.0,
                              "cost_concern_expressed": false,
                              "verified_symptom_duration": null
                            },
                            "priority": {"primary": "CLARIFICATION", "secondary": ["PRICE_INFORMATION"]},
                            "working_hypothesis": {
                              "hypothesis": "Khách hay hoa mắt chóng mặt chưa rõ hoàn cảnh xuất hiện, đang tham khảo dịch vụ thư giãn",
                              "evidence": ["T1", "T_CURRENT"],
                              "confidence": 0.82,
                              "lifecycle_op": "ADJUST"
                            },
                            "strategy": {
                              "next_strategy": "CLARIFY_SYMPTOM_GENTLY_AND_DESCRIBE_RELAXATION_SCOPE",
                              "adaptation": "REFINEMENT",
                              "previous_outcome": "ACCEPTED",
                              "evidence": ["T_CURRENT"]
                            },
                            "activated_skill_codes": ["discovery_empathy_and_scope"],
                            "uncertainties": ["chưa rõ nguyên nhân gây hoa mắt chóng mặt"],
                            "customer_statements": ["Tôi hay hoa mắt chóng mặt"],
                            "commitment_delta": [],
                            "handoff_request": {"requested": false, "reason": null},
                            "proposed_booking_patch": {"selected_product_id": null, "proposed_date": null, "proposed_time": null}
                          },
                          "bubbles": [
                            "Dạ mình thường bị hoa mắt chóng mặt lúc ngồi lâu hay khi đứng dậy ạ? Nếu mình cần thư giãn cơ thể êm dịu thì có gói dưỡng sinh 60 phút – 239k, khi ghé kỹ thuật viên sẽ hỏi kỹ thể trạng trực tiếp để chăm sóc nhẹ nhàng phù hợp, còn chóng mặt kéo dài mình nên kiểm tra nguyên nhân riêng nhé ạ."
                          ]
                        }
                        """
        );

        AgentSkillService mockedSkillSvc = mock(AgentSkillService.class);
        when(mockedSkillSvc.getActiveSkillsForPage(PAGE_ID)).thenReturn(sixPageSkills);
        java.lang.reflect.Field fSkill = FanpageBotService.class.getDeclaredField("agentSkillService");
        fSkill.setAccessible(true);
        fSkill.set(botService, mockedSkillSvc);

        List<FanpageBotRequest.ConversationTurn> history = List.of(
                new FanpageBotRequest.ConversationTurn("user", "Giá"),
                new FanpageBotRequest.ConversationTurn("assistant", "Dạ các gói massage và dưỡng sinh bên em từ 239k – 399k/60 phút ạ. Mình đang quan tâm massage toàn thân hay cổ vai gáy hơn ạ?")
        );
        FanpageBotResponse resp = botService.handleCustomerMessage(buildBotRequest(
                "sender-health-ux",
                "Khách Web UI",
                "Tôi hay hoa mắt chóng mặt",
                null,
                history
        ));

        // 1. Không bị hijack bởi pre-LLM HARD_HANDOFF_SAFETY và không tạo handoff khống
        assertThat(resp.needsHumanHandoff()).isFalse();
        assertThat(resp.replyBubbles()).hasSize(1);

        // 2. Tách bạch rõ availableSkills (6) vs activatedSkills (1) và activeSkills cho UI chỉ chứa đúng 1 skill kích hoạt
        assertThat(resp.availableSkills()).hasSize(6);
        assertThat(resp.activatedSkills()).hasSize(1);
        assertThat(resp.activatedSkills().get(0).code()).isEqualTo("discovery_empathy_and_scope");
        assertThat(resp.activeSkills()).hasSize(1);
        assertThat(resp.activeSkills().get(0).code()).isEqualTo("discovery_empathy_and_scope");

        // 3. Structured concise reflection fields (hypothesis, evidence, next_strategy) & TurnProfiling đầy đủ
        AdaptiveConversationIntelligence.AdaptiveCognitiveReflection lastRef =
                botService.getCleanAgentOrchestratorService().getLastAciReflection();
        assertThat(lastRef.workingHypothesis().hypothesis()).contains("hoa mắt chóng mặt");
        assertThat(lastRef.workingHypothesis().evidence()).containsExactly("T1", "T_CURRENT");
        assertThat(lastRef.strategy().nextStrategy()).isEqualTo("CLARIFY_SYMPTOM_GENTLY_AND_DESCRIBE_RELAXATION_SCOPE");
        assertThat(resp.turnProfiling()).isNotNull();
        assertThat(resp.turnProfiling().reflectionTokens()).isGreaterThan(0);
        assertThat(resp.turnProfiling().modelLatencyMs()).isGreaterThan(0L);
        assertThat(resp.turnProfiling().totalTurnMs()).isGreaterThan(0L);
    }

    @Test
    @DisplayName("Consultative Upgrade 1: Hospitality nạp configured_opening_bubbles ở lượt 1, khử trùng lặp 4 chi nhánh Catalog & nén 14 rules xuống <=7 rules")
    void consultativeHospitalityRendersOpeningBubblesOnTurn1AndSlashesDuplicateBranchCatalogAndRules() {
        // Giả lập bảng giá lặp lại trên 4 chi nhánh (Hà Nội, Hải Phòng, Quảng Ninh, Hồ Chí Minh) như DB thực tế
        List<String> branches = List.of("Khu vực Hà Nội", "Khu vực Hải Phòng", "Khu vực Quảng Ninh", "Khu vực Hồ Chí Minh");
        List<ReciprocalRankFusion.FusedChunk> fourBranchChunks = new ArrayList<>();
        for (String br : branches) {
            fourBranchChunks.add(new ReciprocalRankFusion.FusedChunk(
                    new RetrievedChunk(UUID.randomUUID(), DOC_ID, List.of("MASSAGE", br), List.of(), 1,
                            new double[]{0, 0, 10, 10}, catalogChunks.get(0).chunk().text(), 0.96),
                    0.96));
            fourBranchChunks.add(new ReciprocalRankFusion.FusedChunk(
                    new RetrievedChunk(UUID.randomUUID(), DOC_ID, List.of("GỘI ĐẦU DƯỠNG SINH", br), List.of(), 1,
                            new double[]{0, 0, 10, 10}, catalogChunks.get(1).chunk().text(), 0.94),
                    0.94));
        }
        fourBranchChunks.add(catalogChunks.get(2)); // policyChunk

        List<PackageCatalogService.PackageSummary> duplicatedPackages =
                PackageCatalogService.extractCatalog(fourBranchChunks, List.of()).stream()
                        .filter(p -> !p.isAddon())
                        .toList();
        assertThat(duplicatedPackages).hasSize(16);
        List<PackageCatalogService.PackageSummary> deduped =
                CleanAgentCatalogAndPromptBuilder.deduplicateCatalogByCanonicalSignature(duplicatedPackages);
        assertThat(deduped).hasSize(4); // 16 dòng lặp 4 chi nhánh rút gọn còn đúng 4 gói chuẩn!

        // Giả lập 14 rules của Page (gồm rule mở đầu PB-SPA-OPEN-001 + 13 rules tình huống)
        List<com.example.docmind.dto.LearnedRuleDto> fourteenRules = new ArrayList<>();
        fourteenRules.add(new com.example.docmind.dto.LearnedRuleDto(
                UUID.randomUUID(), "OPENING", "Hi shop", "",
                "Đón tiếp ấm áp theo 3 bong bóng cấu hình trên UI Page",
                List.of("hi shop", "tư vấn"), true, PAGE_ID.toString(), 0.99, 1, 1, 0, 1, null, null,
                Instant.now(), Instant.now(), Instant.now(), "DOM", "Khách chào hỏi lượt đầu", null, false,
                "APPROVED", null, "PB-SPA-OPEN-001", "Đón tiếp Hospitality", "OPENING",
                "OPENING", 99,
                "Xuất 2-3 bong bóng mở đầu ấm áp theo configured_opening_bubbles",
                "Cấm hỏi cụt lủn 1 câu khảo sát",
                "must_use_kb_document_context", "none", "PLAYBOOK"
        ));
        for (int i = 1; i <= 13; i++) {
            fourteenRules.add(new com.example.docmind.dto.LearnedRuleDto(
                    UUID.randomUUID(), "SALES_STRATEGY", "Tình huống " + i, "",
                    "Quy tắc tư vấn tinh tế số " + i,
                    List.of("gói", "239k", "đắt"), true, PAGE_ID.toString(), 0.95, 1, 1, 0, 1, null, null,
                    Instant.now(), Instant.now(), Instant.now(), "DOM", "Khách hỏi gói hoặc giá " + i, null, i <= 2,
                    "APPROVED", null, "PB-SPA-RULE-0" + i, "Quy tắc " + i, "VALUE_ALIGNMENT",
                    "CONSULTING", 95 - i,
                    "Giải thích giá trị thực chất " + i,
                    "Cấm thảo mai",
                    "must_use_kb_document_context", "none", "PLAYBOOK"
            ));
        }

        // Lượt 1 ("Hi shop, tôi cần tư vấn"): có configured_opening_bubbles + chọn <= 7 rules (có PB-SPA-OPEN-001)
        FanpageBotRequest turn1Req = buildBotRequest("sender-hosp-1", "Khách Mới", "Hi shop, tôi cần tư vấn", null, List.of());
        AdaptiveConversationIntelligence.ThreeStreamTurnInput turn1Streams =
                AdaptiveConversationIntelligence.buildThreeStreamInput(
                        turn1Req, duplicatedPackages, nangSenPersona.resolvedConfig(), ZoneId.of("Asia/Ho_Chi_Minh"));
        String turn1Prompt = CleanAgentCatalogAndPromptBuilder.buildAciSinglePassPrompt(
                nangSenPersona, "mình", null, "mình", turn1Streams,
                duplicatedPackages, List.of(),
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                List.of(), AdaptiveConversationIntelligence.WorkingHypothesisRecord.initialEmpty(),
                List.of(), ConversationState.StructuredBookingState.none(),
                AdaptiveConversationIntelligence.OperationalStateSnapshot.initial(),
                AdaptiveConversationIntelligence.PreviousStrategyRecord.initialNone(),
                List.of(), null, false, fourBranchChunks, List.of(), List.of(), fourteenRules
        );
        assertThat(turn1Prompt).contains("configured_opening_bubbles");
        assertThat(turn1Prompt).contains("PB-SPA-OPEN-001");
        // Không chèn bảng markdown thô "| Mã gói | Tên gói |" vào <DOCUMENT_CONTEXT> nhưng vẫn giữ policyChunk
        assertThat(turn1Prompt).doesNotContain("| Mã gói | Tên gói |");
        assertThat(turn1Prompt).contains("6 Bích Câu");

        // Lượt 2+ ("Mình muốn hỏi gói dưỡng sinh 239k"): tự động bỏ PB-SPA-OPEN-001 và giới hạn đúng 7 rules liên quan nhất
        List<com.example.docmind.dto.LearnedRuleDto> turn2SelectedRules =
                CleanAgentCatalogAndPromptBuilder.selectTurnRelevantCompactRules(
                        fourteenRules, "Mình muốn hỏi gói dưỡng sinh 239k", false, 7);
        assertThat(turn2SelectedRules).hasSize(7);
        assertThat(turn2SelectedRules).noneMatch(r -> "PB-SPA-OPEN-001".equals(r.ruleCode()));
    }

    @Test
    @DisplayName("Consultative Upgrade 2: Ethical Closing — Khách mới hỏi thông tin gói ('Mình muốn hỏi gói dưỡng sinh 239k') ở EXPLORING giữ bookingStatus = NONE và không bật 2 skill đặt lịch")
    void exploringPackageInquiryKeepsBookingStatusNoneAndStripsPrematureBookingSkills() throws Exception {
        AgentBrainService sharedBrain = new AgentBrainService("LLM_COGNITIVE_V2");
        List<com.example.docmind.dto.bot.AgentSkillDto> sixPageSkills = List.of(
                new com.example.docmind.dto.bot.AgentSkillDto(UUID.randomUUID(), "discovery_empathy_and_scope", "Đồng cảm giác quan & Khám phá vùng mỏi", "PSYCHOLOGY", "", "Khám phá vùng mỏi", "", null, null, 98, true, PAGE_ID.toString(), Instant.now(), Instant.now()),
                new com.example.docmind.dto.bot.AgentSkillDto(UUID.randomUUID(), "soft_economical_bridge_and_compare", "Nhịp cầu lựa chọn & So sánh tinh tế", "SALES_STRATEGY", "", "So sánh tinh tế", "", null, null, 96, true, PAGE_ID.toString(), Instant.now(), Instant.now()),
                new com.example.docmind.dto.bot.AgentSkillDto(UUID.randomUUID(), "warm_nod_to_gentle_scheduling", "Đón nhận gật đầu & Mời khung giờ", "COMMUNICATION", "", "Mời khung giờ", "", null, null, 95, true, PAGE_ID.toString(), Instant.now(), Instant.now()),
                new com.example.docmind.dto.bot.AgentSkillDto(UUID.randomUUID(), "booking_careful_preparation", "Chuẩn bị đón tiếp & Kiểm tra lịch trống", "SALES_STRATEGY", "", "Kiểm tra lịch trống", "", null, null, 94, true, PAGE_ID.toString(), Instant.now(), Instant.now())
        );

        // Kể cả khi LLM lỡ điền proposed_booking_patch.selected_product_id="DS02" và lỡ chọn 2 skill đặt lịch ở trạng thái EXPLORING,
        // chốt chặn thẩm quyền V2 phải giữ bookingStatus = NONE và gỡ bỏ 2 skill đặt lịch sớm!
        FanpageBotService botService = createIsolatedBotServiceWithBrain(
                AdaptiveConversationIntelligence.CognitiveMode.LLM_COGNITIVE_V2,
                sharedBrain,
                (prompt, budget) -> """
                        {
                          "brain_reflection": {
                            "customer_understanding": {
                              "detected_intent": "hỏi thông tin gói 239k",
                              "emotional_state": {"primary": "CALM", "secondary": [], "confidence": 0.90},
                              "inferred_customer_pronoun": null,
                              "pronoun_confidence": 0.0,
                              "cost_concern_expressed": false,
                              "verified_symptom_duration": null,
                              "booking_readiness": "EXPLORING",
                              "communication_style_preference": null
                            },
                            "priority": {"primary": "VALUE_ALIGNMENT", "secondary": ["PRICE_INFORMATION"]},
                            "working_hypothesis": {"hypothesis": "Khách tìm hiểu gói dưỡng sinh 239k", "evidence": ["T_CURRENT"], "confidence": 0.85, "lifecycle_op": "ADJUST"},
                            "strategy": {"next_strategy": "EXPLAIN_WHY_THIS_PACKAGE", "adaptation": "CONTINUATION", "previous_outcome": "ACCEPTED", "evidence": ["T_CURRENT"]},
                            "activated_skill_codes": ["soft_economical_bridge_and_compare", "warm_nod_to_gentle_scheduling", "booking_careful_preparation"],
                            "uncertainties": [],
                            "customer_statements": [],
                            "commitment_delta": [],
                            "handoff_request": {"requested": false, "reason": null},
                            "proposed_booking_patch": {"selected_product_id": "DS02", "proposed_date": null, "proposed_time": null}
                          },
                          "bubbles": [
                            "Dạ gói Dưỡng sinh cơ bản 60 phút – 239k gồm gội thảo dược, massage mặt và chườm thảo dược ấm vùng cổ vai kèm trà ăn nhẹ sau buổi, rất hợp để mình thả lỏng dịu nhẹ sau ngày ngồi máy tính ạ. Mình thấy liệu trình nhẹ nhàng này có đúng hướng mình đang tìm không ạ?"
                          ]
                        }
                        """
        );
        AgentSkillService mockedSkillSvc = mock(AgentSkillService.class);
        when(mockedSkillSvc.getActiveSkillsForPage(PAGE_ID)).thenReturn(sixPageSkills);
        java.lang.reflect.Field fSkill = FanpageBotService.class.getDeclaredField("agentSkillService");
        fSkill.setAccessible(true);
        fSkill.set(botService, mockedSkillSvc);

        FanpageBotResponse resp = botService.handleCustomerMessage(buildBotRequest(
                "sender-exploring-239k",
                "Khách Web UI",
                "Mình muốn hỏi gói dưỡng sinh 239k",
                null,
                List.of(
                        new FanpageBotRequest.ConversationTurn("user", "Mình ngồi máy tính nhiều mỏi cổ vai quá"),
                        new FanpageBotRequest.ConversationTurn("assistant", "Dạ ngồi máy tính liên tục mà cổ vai cứ căng mỏi âm ỉ thì khó tập trung lắm ạ. Mình đang mỏi nhiều ở cổ gáy hay lan xuống cả bả vai ạ?")
                )
        ));

        ConversationState.StructuredBookingState bookingAfter =
                sharedBrain.recallBookingState(PAGE_ID, "sender-exploring-239k");
        assertThat(bookingAfter.status()).isEqualTo(ConversationState.BookingStatus.NONE);
        assertThat(resp.activatedSkills())
                .extracting(com.example.docmind.dto.bot.AgentSkillDto::code)
                .containsExactly("soft_economical_bridge_and_compare")
                .doesNotContain("warm_nod_to_gentle_scheduling", "booking_careful_preparation");
    }

    @Test
    @DisplayName("Consultative Upgrade 3: Lưu bền vững communication_style_preference = CONCISE xuyên nhiều lượt sau 'Nói ngắn thôi' cho tới khi khách yêu cầu DETAILED")
    void persistsConciseCommunicationStylePreferenceAcrossSubsequentTurnsUntilDetailedRequested() {
        AgentBrainService sharedBrain = new AgentBrainService("LLM_COGNITIVE_V2");
        AtomicReference<String> capturedPrompt = new AtomicReference<>("");
        AtomicReference<String> prefToEmit = new AtomicReference<>("\"CONCISE\"");

        FanpageBotService botService = createIsolatedBotServiceWithBrain(
                AdaptiveConversationIntelligence.CognitiveMode.LLM_COGNITIVE_V2,
                sharedBrain,
                (prompt, budget) -> {
                    capturedPrompt.set(prompt);
                    return """
                            {
                              "brain_reflection": {
                                "customer_understanding": {
                                  "detected_intent": "hỏi nhanh thông tin",
                                  "emotional_state": {"primary": "IMPATIENT", "secondary": [], "confidence": 0.88},
                                  "booking_readiness": "EXPLORING",
                                  "communication_style_preference": %s
                                },
                                "priority": {"primary": "CLARIFICATION", "secondary": []},
                                "working_hypothesis": {"hypothesis": "Khách thích trả lời ngắn gọn", "evidence": ["T_CURRENT"], "confidence": 0.90, "lifecycle_op": "MAINTAIN"},
                                "strategy": {"next_strategy": "CONCISE_DIRECT_ANSWER", "adaptation": "REFINEMENT", "previous_outcome": "ACCEPTED", "evidence": ["T_CURRENT"]}
                              },
                              "bubbles": ["Dạ bên em mở cửa 09:00 – 22:00 hằng ngày, nhận khách cuối lúc 20:30 ạ."]
                            }
                            """.formatted(prefToEmit.get());
                }
        );

        // Lượt 1: Khách nhắn "Nói ngắn thôi" -> LLM phát hiện CONCISE -> lưu vào bộ nhớ phiên
        botService.handleCustomerMessage(buildBotRequest(
                "sender-concise-sticky", "Khách Bận Rộn", "Nói ngắn thôi", null, List.of()
        ));
        assertThat(sharedBrain.getCommunicationStylePreference(PAGE_ID, "sender-concise-sticky"))
                .isEqualTo("CONCISE");

        // Lượt 2: Khách hỏi câu mới ("Mở cửa mấy giờ?"), LLM trả về communication_style_preference: null -> CONCISE vẫn được bảo toàn!
        prefToEmit.set("null");
        botService.handleCustomerMessage(buildBotRequest(
                "sender-concise-sticky", "Khách Bận Rộn", "Mở cửa mấy giờ?", null,
                List.of(
                        new FanpageBotRequest.ConversationTurn("user", "Nói ngắn thôi"),
                        new FanpageBotRequest.ConversationTurn("assistant", "Dạ vâng, em xin phép trả lời thật ngắn gọn cho mình ạ.")
                )
        ));
        assertThat(capturedPrompt.get()).contains("communication_style_preference: \"CONCISE\"");
        assertThat(sharedBrain.getCommunicationStylePreference(PAGE_ID, "sender-concise-sticky"))
                .isEqualTo("CONCISE");

        // Lượt 3: Khách chủ động yêu cầu "Giải thích kỹ hơn quy trình gói 239k giúp mình" -> chuyển sang DETAILED
        prefToEmit.set("\"DETAILED\"");
        botService.handleCustomerMessage(buildBotRequest(
                "sender-concise-sticky", "Khách Bận Rộn", "Giải thích kỹ hơn quy trình gói 239k giúp mình", null,
                List.of(
                        new FanpageBotRequest.ConversationTurn("user", "Mở cửa mấy giờ?"),
                        new FanpageBotRequest.ConversationTurn("assistant", "Dạ bên em mở cửa 09:00 – 22:00 hằng ngày ạ.")
                )
        ));
        assertThat(sharedBrain.getCommunicationStylePreference(PAGE_ID, "sender-concise-sticky"))
                .isEqualTo("DETAILED");
    }

    @Test
    @DisplayName("Draft 2 Hot-Reload: Nạp prompt ngoài từ file cấu hình có version & cache theo lastModified, chịu ký tự '%' an toàn và tự fallback khi file rỗng/lỗi")
    void externalPromptHotReloadCachesByLastModifiedAndFallsBackSafelyOnCorruptFile() throws Exception {
        java.nio.file.Path tempPrompt = java.nio.file.Files.createTempFile("aci_v2_hotreload_", ".md");
        try {
            String validExternalV1 = "<!-- prompt_version: aci-v2.2-hotreload-a -->\n"
                    + "Bạn là {{BOT_ROLE}} của {{BRAND_NAME}} ({{INDUSTRY}}), xưng {{SELF_PRONOUN}} - {{CUSTOMER_PRONOUN}}, "
                    + "nhân sự {{CARE_ACTOR}}, phong cách {{TONE_STYLE}}, trọn gói 100% không nhận Tip, ưu đãi thẻ 10%. "
                    + "Trả về JSON hợp lệ gồm brain_reflection và bubbles theo đúng hợp đồng nhận thức ACI Draft 2 "
                    + "(độ dài tối thiểu >= 300 ký tự để qua cổng kiểm định an toàn hot-reload không bị đứt gãy JSON).";
            java.nio.file.Files.writeString(tempPrompt, validExternalV1, java.nio.charset.StandardCharsets.UTF_8);
            java.nio.file.Files.setLastModifiedTime(tempPrompt, java.nio.file.attribute.FileTime.fromMillis(1_700_000_000_000L));

            CleanAgentCatalogAndPromptBuilder.setExternalAciPromptPathForTest(tempPrompt);

            FanpageBotRequest req = buildBotRequest("sender-hotreload", "Khách", "Giá?", null, List.of());
            AdaptiveConversationIntelligence.ThreeStreamTurnInput streams =
                    AdaptiveConversationIntelligence.buildThreeStreamInput(
                            req, List.of(), nangSenPersona.resolvedConfig(), ZoneId.of("Asia/Ho_Chi_Minh"));

            String builtA = CleanAgentCatalogAndPromptBuilder.buildAciSinglePassPrompt(
                    nangSenPersona, "mình", streams, List.of(), List.of(),
                    CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                    List.of(), AdaptiveConversationIntelligence.WorkingHypothesisRecord.initialEmpty(),
                    List.of(), ConversationState.StructuredBookingState.none(),
                    AdaptiveConversationIntelligence.OperationalStateSnapshot.initial(),
                    AdaptiveConversationIntelligence.PreviousStrategyRecord.initialNone(),
                    List.of(), null, false, catalogChunks, List.of()
            );
            assertThat(CleanAgentCatalogAndPromptBuilder.getActiveAciPromptVersion()).isEqualTo("aci-v2.2-hotreload-a");
            assertThat(CleanAgentCatalogAndPromptBuilder.getActiveAciPromptSource()).startsWith("EXTERNAL_FILE:");
            assertThat(builtA).contains("trọn gói 100% không nhận Tip");

            // Cập nhật nội dung + tăng lastModified -> hot-reload tức thì không cần khởi động lại
            String validExternalV2 = validExternalV1
                    .replace("aci-v2.2-hotreload-a", "aci-v2.2-hotreload-b")
                    .replace("trọn gói 100% không nhận Tip", "trọn gói 100% cam kết 0 phụ phí");
            java.nio.file.Files.writeString(tempPrompt, validExternalV2, java.nio.charset.StandardCharsets.UTF_8);
            java.nio.file.Files.setLastModifiedTime(tempPrompt, java.nio.file.attribute.FileTime.fromMillis(1_700_000_005_000L));

            String builtB = CleanAgentCatalogAndPromptBuilder.buildAciSinglePassPrompt(
                    nangSenPersona, "mình", streams, List.of(), List.of(),
                    CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                    List.of(), AdaptiveConversationIntelligence.WorkingHypothesisRecord.initialEmpty(),
                    List.of(), ConversationState.StructuredBookingState.none(),
                    AdaptiveConversationIntelligence.OperationalStateSnapshot.initial(),
                    AdaptiveConversationIntelligence.PreviousStrategyRecord.initialNone(),
                    List.of(), null, false, catalogChunks, List.of()
            );
            assertThat(CleanAgentCatalogAndPromptBuilder.getActiveAciPromptVersion()).isEqualTo("aci-v2.2-hotreload-b");
            assertThat(builtB).contains("trọn gói 100% cam kết 0 phụ phí");

            // Ghi file rỗng/lỗi -> tự động fallback an toàn về bản in-code
            java.nio.file.Files.writeString(tempPrompt, "   ", java.nio.charset.StandardCharsets.UTF_8);
            java.nio.file.Files.setLastModifiedTime(tempPrompt, java.nio.file.attribute.FileTime.fromMillis(1_700_000_010_000L));

            String builtFallback = CleanAgentCatalogAndPromptBuilder.buildAciSinglePassPrompt(
                    nangSenPersona, "mình", streams, List.of(), List.of(),
                    CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                    List.of(), AdaptiveConversationIntelligence.WorkingHypothesisRecord.initialEmpty(),
                    List.of(), ConversationState.StructuredBookingState.none(),
                    AdaptiveConversationIntelligence.OperationalStateSnapshot.initial(),
                    AdaptiveConversationIntelligence.PreviousStrategyRecord.initialNone(),
                    List.of(), null, false, catalogChunks, List.of()
            );
            assertThat(CleanAgentCatalogAndPromptBuilder.getActiveAciPromptVersion())
                    .isEqualTo(CleanAgentCatalogAndPromptBuilder.IN_CODE_ACI_V2_PROMPT_VERSION);
            assertThat(CleanAgentCatalogAndPromptBuilder.getActiveAciPromptSource()).isEqualTo("IN_CODE_FALLBACK");
            assertThat(builtFallback).contains("CUSTOMER UNDERSTANDING MODEL (DRAFT 2");
        } finally {
            CleanAgentCatalogAndPromptBuilder.setExternalAciPromptPathForTest(null);
            java.nio.file.Files.deleteIfExists(tempPrompt);
        }
    }

    @Test
    @DisplayName("Customer Understanding Model (Draft 2): Giữ nguyên conversation_goal khi Topic Shift, chuyển CURRENT -> SUPERSEDED khi Need Revision, tách Fact vs Barrier Hypothesis khi chê 'Đắt', và Referee V2 cho phép 'ăn nhẹ thực dưỡng' + 'cam kết 0 Tip / 0 phụ phí'")
    void customerUnderstandingDraft2PreservesGoalOnTopicShiftAndVersionsFactsOnNeedRevision() {
        AgentBrainService sharedBrain = new AgentBrainService("LLM_COGNITIVE_V2");
        AtomicInteger turnCounter = new AtomicInteger(0);
        AtomicReference<String> lastPromptSeen = new AtomicReference<>("");

        FanpageBotService botService = createIsolatedBotServiceWithBrain(
                AdaptiveConversationIntelligence.CognitiveMode.LLM_COGNITIVE_V2,
                sharedBrain,
                (prompt, budget) -> {
                    lastPromptSeen.set(prompt);
                    int step = turnCounter.incrementAndGet();
                    return switch (step) {
                        case 1 -> """
                                {
                                  "brain_reflection": {
                                    "conversational_context": {
                                      "current_topic": "neck_shoulder_package",
                                      "unresolved_requests": [{"request": "gói phù hợp cổ vai gáy", "status": "ANSWERED"}],
                                      "pending_references": ["DS02 - 239k"],
                                      "last_bot_action": "Soft Recommendation"
                                    },
                                    "customer_understanding": {
                                      "turn_intent": "ASK_MATCHING_PACKAGE",
                                      "conversation_goal": "RELIEVE_NECK_SHOULDER_FATIGUE",
                                      "need_revision": false,
                                      "confirmed_facts_delta": [{"key": "primary_need", "value": "mỏi cổ vai gáy do ngồi máy tính", "op": "ADD"}],
                                      "self_reported_symptoms": ["mỏi cổ vai gáy"],
                                      "interested_package_id": "DS02",
                                      "emotional_state": {"primary": "TIRED", "secondary": [], "confidence": 0.88},
                                      "booking_readiness": "EXPLORING",
                                      "communication_style_preference": null
                                    },
                                    "priority": {"primary": "VALUE_ALIGNMENT", "secondary": []},
                                    "working_hypothesis": {"hypothesis": "Khách cần thả lỏng cổ vai gáy", "barrier_type": "NONE", "confidence_level": "HIGH", "evidence": ["T_CURRENT"], "confidence": 0.86, "lifecycle_op": "ADJUST"},
                                    "response_plan": {"objective": "Phản chiếu mỏi & gợi ý DS02", "strategy": "Soft Recommendation", "tone": "LEVEL_3_SENSORY_CONTRAST", "depth": "STANDARD", "next_action": "SOFT_EXPERIENCE_CHECK"},
                                    "strategy": {"next_strategy": "SOFT_RECOMMEND_DS02", "adaptation": "CONTINUATION", "previous_outcome": "NOT_APPLICABLE", "evidence": ["T_CURRENT"]},
                                    "proposed_booking_patch": {"selected_product_id": null, "proposed_date": null, "proposed_time": null}
                                  },
                                  "bubbles": ["Dạ ngồi máy tính lâu cả ngày thì vùng cổ vai gáy dễ căng mỏi thật ạ. Mình có thể tham khảo Gói dưỡng sinh cơ bản 60 phút – 239k nằm thư giãn trong không gian rèm riêng tĩnh lặng, gội thảo dược ấm và chăm sóc nhẹ nhàng vùng cổ vai gáy ạ."]
                                }
                                """;
                        case 2 -> """
                                {
                                  "brain_reflection": {
                                    "conversational_context": {
                                      "current_topic": "branch_location",
                                      "unresolved_requests": [{"request": "hỏi địa chỉ cơ sở", "status": "ANSWERED"}],
                                      "pending_references": ["6 Bích Câu", "Vinhomes Gardenia"],
                                      "last_bot_action": "Direct Answer"
                                    },
                                    "customer_understanding": {
                                      "turn_intent": "ASK_LOCATION",
                                      "conversation_goal": "ASK_LOCATION",
                                      "need_revision": false,
                                      "confirmed_facts_delta": [],
                                      "self_reported_symptoms": [],
                                      "interested_package_id": "DS02",
                                      "emotional_state": {"primary": "NEUTRAL", "secondary": [], "confidence": 0.90},
                                      "booking_readiness": "EXPLORING",
                                      "communication_style_preference": "CONCISE"
                                    },
                                    "priority": {"primary": "CLARIFICATION", "secondary": []},
                                    "working_hypothesis": {"hypothesis": "Khách hỏi địa chỉ trước khi ghé", "barrier_type": "NONE", "confidence_level": "MEDIUM", "evidence": ["T_CURRENT"], "confidence": 0.82, "lifecycle_op": "DOWNGRADE"},
                                    "response_plan": {"objective": "Báo gọn địa chỉ cụ thể", "strategy": "Direct Answer", "tone": "LEVEL_1_NATURAL_DIRECT", "depth": "CONCISE", "next_action": "NO_ADDITIONAL_ACTION"},
                                    "strategy": {"next_strategy": "DIRECT_LOCATION_ANSWER", "adaptation": "PRIORITY_SHIFT", "previous_outcome": "ACCEPTED", "evidence": ["T_CURRENT"]}
                                  },
                                  "bubbles": ["Dạ Nàng Sen có cơ sở tại 6 Bích Câu (Đống Đa), Vinhomes Gardenia (Mỹ Đình) và 319 Hồng Tiến (Long Biên) ạ."]
                                }
                                """;
                        case 3 -> """
                                {
                                  "brain_reflection": {
                                    "conversational_context": {
                                      "current_topic": "price_value_objection",
                                      "unresolved_requests": [],
                                      "pending_references": ["DS02 - 239k"],
                                      "last_bot_action": "Handle Objection"
                                    },
                                    "customer_understanding": {
                                      "turn_intent": "PRICE_OBJECTION",
                                      "conversation_goal": "RELIEVE_NECK_SHOULDER_FATIGUE",
                                      "need_revision": false,
                                      "confirmed_facts_delta": [{"key": "price_reaction", "value": "khách phản ứng giá đắt", "op": "ADD"}],
                                      "cost_concern_expressed": true,
                                      "emotional_state": {"primary": "HESITANT", "secondary": [], "confidence": 0.85},
                                      "booking_readiness": "CONSIDERING",
                                      "communication_style_preference": "DETAILED_ON_DEMAND"
                                    },
                                    "priority": {"primary": "VALUE_ALIGNMENT", "secondary": ["BUDGET_FIT"]},
                                    "working_hypothesis": {"hypothesis": "Khách chưa rõ giá 239k đã trọn gói 0 Tip", "barrier_type": "UNCLEAR_VALUE", "confidence_level": "MEDIUM", "evidence": ["T_CURRENT"], "confidence": 0.78, "lifecycle_op": "ADJUST"},
                                    "response_plan": {"objective": "Làm rõ giá trị trọn gói 0 Tip", "strategy": "Handle Objection", "tone": "LEVEL_2_REFLECTIVE_WARMTH", "depth": "DETAILED_ON_DEMAND", "next_action": "NO_ADDITIONAL_ACTION"},
                                    "strategy": {"next_strategy": "EXPLAIN_ALL_INCLUSIVE_ZERO_TIP", "adaptation": "REFINEMENT", "previous_outcome": "REJECTED", "evidence": ["T_CURRENT"]}
                                  },
                                  "bubbles": ["Dạ mức 239k cho Gói dưỡng sinh cơ bản 60 phút tại Nàng Sen là trọn gói 100%, đã gồm gội thảo dược, chăm sóc cổ vai gáy, trà & ăn nhẹ thực dưỡng trong không gian rèm riêng tĩnh lặng và cam kết 0 Tip / 0 phụ phí phát sinh ạ."]
                                }
                                """;
                        default -> """
                                {
                                  "brain_reflection": {
                                    "conversational_context": {
                                      "current_topic": "full_body_massage_revision",
                                      "unresolved_requests": [],
                                      "pending_references": ["NS01 - 399k"],
                                      "last_bot_action": "Soft Recommendation"
                                    },
                                    "customer_understanding": {
                                      "turn_intent": "REVISE_NEED_FULL_BODY",
                                      "conversation_goal": "FULL_BODY_RELAXATION",
                                      "need_revision": true,
                                      "confirmed_facts_delta": [{"key": "primary_need", "value": "massage toàn thân", "op": "REVISE"}],
                                      "interested_package_id": "NS01",
                                      "emotional_state": {"primary": "CALM", "secondary": [], "confidence": 0.90},
                                      "booking_readiness": "EXPLORING",
                                      "communication_style_preference": null
                                    },
                                    "priority": {"primary": "VALUE_ALIGNMENT", "secondary": []},
                                    "working_hypothesis": {"hypothesis": "Khách đổi sang muốn thư giãn toàn thân", "barrier_type": "NONE", "confidence_level": "HIGH", "evidence": ["T_CURRENT"], "confidence": 0.90, "lifecycle_op": "ADJUST"},
                                    "response_plan": {"objective": "Gợi ý gọn gói Toàn thân 399k", "strategy": "Soft Recommendation", "tone": "LEVEL_1_NATURAL_DIRECT", "depth": "CONCISE", "next_action": "NO_ADDITIONAL_ACTION"},
                                    "strategy": {"next_strategy": "RECOMMEND_NS01_CONCISE", "adaptation": "PIVOT", "previous_outcome": "ACCEPTED", "evidence": ["T_CURRENT"]},
                                    "proposed_booking_patch": {"selected_product_id": null, "proposed_date": null, "proposed_time": null}
                                  },
                                  "bubbles": ["Dạ vậy mình tham khảo gói Massage cơ bản toàn thân 60 phút – 399k trọn gói 0 Tip, chăm sóc giãn cơ toàn diện từ cổ vai lưng tới tay chân ạ."]
                                }
                                """;
                    };
                }
        );

        String sender = "sender-draft2-e2e";
        // Turn 1: Khởi tạo nhu cầu mỏi cổ vai gáy
        botService.handleCustomerMessage(buildBotRequest(
                sender, "Khách", "Mình ngồi máy tính nhiều, cổ vai gáy hay mỏi. Bên mình có gói nào phù hợp không?", null, List.of()));
        AdaptiveConversationIntelligence.CustomerUnderstandingDraft2Snapshot s1 =
                sharedBrain.getCustomerUnderstandingDraft2Snapshot(PAGE_ID, sender);
        assertThat(s1.consultativeProfile().conversationGoal()).isEqualTo("RELIEVE_NECK_SHOULDER_FATIGUE");
        assertThat(s1.consultativeProfile().selfReportedSymptoms())
                .anyMatch(f -> f.value().contains("mỏi cổ vai gáy"));
        assertThat(s1.businessCommitment().interestedPackageId()).isEqualTo("DS02");
        assertThat(s1.businessCommitment().selectedPackageId()).isNull();
        assertThat(s1.businessCommitment().bookingStatus()).isEqualTo("NONE");

        // Turn 2: Topic Shift ("À, bên mình ở đâu?") — kể cả khi LLM lỡ trả conversation_goal="ASK_LOCATION", Java Core giữ sticky goal cũ!
        botService.handleCustomerMessage(buildBotRequest(
                sender, "Khách", "À, bên mình ở đâu?", null,
                List.of(new FanpageBotRequest.ConversationTurn("user", "Mình ngồi máy tính nhiều, cổ vai gáy hay mỏi."))));
        AdaptiveConversationIntelligence.CustomerUnderstandingDraft2Snapshot s2 =
                sharedBrain.getCustomerUnderstandingDraft2Snapshot(PAGE_ID, sender);
        assertThat(s2.conversationalContext().currentTopic()).isEqualTo("branch_location");
        assertThat(s2.consultativeProfile().turnIntent()).isEqualTo("ASK_LOCATION");
        assertThat(s2.consultativeProfile().conversationGoal()).isEqualTo("RELIEVE_NECK_SHOULDER_FATIGUE");
        assertThat(s2.consultativeProfile().currentConfirmedFacts())
                .anyMatch(f -> "need_scope".equals(f.key()) && f.value().contains("cổ vai gáy"));
        assertThat(s2.lastResponsePlan().nextAction()).isEqualTo("NO_ADDITIONAL_ACTION");

        // Turn 3: Phản đối giá ("Đắt nhỉ") — tách Fact vs Barrier Hypothesis, DETAILED_ON_DEMAND không xóa mặc định CONCISE, Referee V2 PASS 100%!
        FanpageBotResponse r3 = botService.handleCustomerMessage(buildBotRequest(
                sender, "Khách", "Đắt nhỉ", null,
                List.of(new FanpageBotRequest.ConversationTurn("user", "À, bên mình ở đâu?"))));
        assertThat(r3.needsHumanHandoff()).isFalse();
        assertThat(r3.replyText()).contains("ăn nhẹ thực dưỡng").contains("cam kết 0 Tip / 0 phụ phí");
        assertThat(ThinkingModePolicy.getLastAciTurnTelemetry().refereeViolations()).isEmpty();
        AdaptiveConversationIntelligence.CustomerUnderstandingDraft2Snapshot s3 =
                sharedBrain.getCustomerUnderstandingDraft2Snapshot(PAGE_ID, sender);
        assertThat(s3.consultativeProfile().confirmedFacts())
                .anyMatch(f -> "price_reaction".equals(f.key()) && "khách phản ứng giá đắt".equals(f.value()));
        assertThat(s3.consultativeProfile().barrierHypothesis().barrierType()).isEqualTo("UNCLEAR_VALUE");
        assertThat(s3.consultativeProfile().barrierHypothesis().confidenceLevel()).isEqualTo("MEDIUM");
        assertThat(s3.consultativeProfile().communicationPreference()).isEqualTo("CONCISE");
        assertThat(s3.consultativeProfile().effectiveTurnDepth()).isEqualTo("DETAILED_ON_DEMAND");

        // Turn 4: Need Revision ("Thôi, mình muốn massage toàn thân hơn.") -> cổ vai gáy thành SUPERSEDED, toàn thân thành CURRENT, interested=NS01, selected=null!
        botService.handleCustomerMessage(buildBotRequest(
                sender, "Khách", "Thôi, mình muốn massage toàn thân hơn.", null,
                List.of(new FanpageBotRequest.ConversationTurn("user", "Đắt nhỉ"))));
        assertThat(lastPromptSeen.get()).contains("customer_understanding_model_draft2");
        AdaptiveConversationIntelligence.CustomerUnderstandingDraft2Snapshot s4 =
                sharedBrain.getCustomerUnderstandingDraft2Snapshot(PAGE_ID, sender);
        assertThat(s4.consultativeProfile().conversationGoal()).isEqualTo("FULL_BODY_RELAXATION");
        assertThat(s4.consultativeProfile().supersededFacts())
                .anyMatch(f -> "need_scope".equals(f.key())
                        && f.value().contains("cổ vai gáy")
                        && f.validity() == AdaptiveConversationIntelligence.FactValidity.SUPERSEDED);
        assertThat(s4.consultativeProfile().currentConfirmedFacts())
                .anyMatch(f -> "need_scope".equals(f.key())
                        && "massage toàn thân".equals(f.value())
                        && f.validity() == AdaptiveConversationIntelligence.FactValidity.CURRENT);
        assertThat(s4.businessCommitment().interestedPackageId()).isEqualTo("NS01");
        assertThat(s4.businessCommitment().selectedPackageId()).isNull();
        assertThat(s4.businessCommitment().bookingStatus()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("V2.3 I.3 (Mandatory Automated Sync): config/aci_v2_system_prompt.md và Java Fallback ACI_COGNITIVE_SYSTEM_PROMPT_TEMPLATE đồng bộ 100% version & nội dung cốt lõi")
    void aciV23ExternalPromptFileAndJavaFallbackAreStrictlySynchronized() throws Exception {
        java.nio.file.Path mdPath = java.nio.file.Path.of("../config/aci_v2_system_prompt.md");
        if (!java.nio.file.Files.isRegularFile(mdPath)) {
            mdPath = java.nio.file.Path.of("config/aci_v2_system_prompt.md");
        }
        assertThat(java.nio.file.Files.isRegularFile(mdPath))
                .as("External prompt file config/aci_v2_system_prompt.md must exist")
                .isTrue();

        String rawExternal = java.nio.file.Files.readString(mdPath, java.nio.charset.StandardCharsets.UTF_8);
        String extVersion = CleanAgentCatalogAndPromptBuilder.extractPromptVersionFromExternalContent(rawExternal);
        String extBody = CleanAgentCatalogAndPromptBuilder.stripPromptVersionComment(rawExternal).strip();
        String inCodeBody = CleanAgentCatalogAndPromptBuilder.getInCodeAciPromptTemplate().strip();

        assertThat(extVersion)
                .isEqualTo("aci-v2.7-expert-sales")
                .isEqualTo(CleanAgentCatalogAndPromptBuilder.IN_CODE_ACI_V2_PROMPT_VERSION);
        assertThat(extBody).isEqualTo(inCodeBody);
        assertThat(extBody)
                .contains("interaction_permission")
                .contains("buying_signal")
                .contains("cta_level")
                .contains("CURRENT|RESOLVED|PAST|NEGATED|SUPERSEDED")
                .contains("4-Tier Claim Taxonomy")
                .doesNotContain("50k–100k tiền tip")
                .doesNotContain("\"self_reported_symptoms\"");
    }

    @Test
    @DisplayName("V2.3 II.1: extractBubblesFromRootNode tách đoạn kép \\n\\n thành ReplyBubble độc lập (delay=2000ms), giữ nguyên \\n đơn trong cùng 1 ý và không tách hai lần")
    void aciV23BubbleNormalizationSplitsDoubleNewlinesPreservesSingleNewlinesAndAvoidsDoubleSplit() {
        String rawMergedWithSingleNewlineList = """
                {
                  "brain_reflection": {
                    "response_plan": {"strategy": "Explain Value", "tone": "LEVEL_1_NATURAL", "depth": "STANDARD", "cta_level": "SOFT_CTA", "next_action": "SOFT_FEELING_CHECK"}
                  },
                  "bubbles": [
                    {
                      "text": "Dạ gói Massage cơ bản 399k/60 phút gồm:\\n- 60 phút massage toàn thân\\n- Thưởng thức buffet thực dưỡng sau liệu trình\\n\\nHôm nay mình muốn ghé cơ sở nào để em kiểm tra lịch trống giúp mình ạ?",
                      "typing_delay_ms": 900
                    }
                  ]
                }
                """;
        CleanAgentOutputRefereeAndSanitizer.StructuredLlmOutput parsed =
                CleanAgentOutputRefereeAndSanitizer.parseStructuredLlmJsonOutput(rawMergedWithSingleNewlineList, 2000);
        assertThat(parsed.bubbles()).hasSize(2);
        assertThat(parsed.bubbles().get(0).text())
                .isEqualTo("Dạ gói Massage cơ bản 399k/60 phút gồm:\n- 60 phút massage toàn thân\n- Thưởng thức buffet thực dưỡng sau liệu trình");
        assertThat(parsed.bubbles().get(0).typingDelayMs()).isEqualTo(900);
        assertThat(parsed.bubbles().get(1).text())
                .isEqualTo("Hôm nay mình muốn ghé cơ sở nào để em kiểm tra lịch trống giúp mình ạ?");
        assertThat(parsed.bubbles().get(1).typingDelayMs()).isEqualTo(2000);

        String rawAlreadySeparate = """
                {
                  "brain_reflection": {"response_plan": {"strategy": "Direct Answer"}},
                  "bubbles": ["Bong bóng 1 gọn gàng.", "Bong bóng 2 độc lập."]
                }
                """;
        CleanAgentOutputRefereeAndSanitizer.StructuredLlmOutput parsedClean =
                CleanAgentOutputRefereeAndSanitizer.parseStructuredLlmJsonOutput(rawAlreadySeparate, 2000);
        assertThat(parsedClean.bubbles()).hasSize(2);
        assertThat(parsedClean.bubbles().get(0).text()).isEqualTo("Bong bóng 1 gọn gàng.");
        assertThat(parsedClean.bubbles().get(1).text()).isEqualTo("Bong bóng 2 độc lập.");
    }

    @Test
    @DisplayName("V2.3 III.1 & III.2: Tách nguyên tử triệu chứng theo 5 trạng thái (CURRENT/RESOLVED/PAST/NEGATED/SUPERSEDED), không đóng mù unresolved_requests và hợp nhất 1 khối Memory 3 Tầng")
    void aciV23FiveStateAtomicSymptomLifecycleInteractionPermissionAndUnresolvedRequests() {
        AgentBrainService brain = new AgentBrainService((com.example.docmind.client.AgentBrainClient) null);

        // Lượt 1: Khách liệt kê gộp "chóng mặt, đau đầu, khó ngủ" + 1 unresolved_request OPEN ("giá phòng VIP")
        AdaptiveConversationIntelligence.AdaptiveCognitiveReflection ref1 =
                CleanAgentOutputRefereeAndSanitizer.parseStructuredLlmJsonOutput("""
                        {
                          "brain_reflection": {
                            "conversational_context": {
                              "current_topic": "health_symptoms",
                              "unresolved_requests": [{"request": "chi tiết giá phòng VIP", "status": "OPEN"}]
                            },
                            "customer_understanding": {
                              "turn_intent": "SHARE_SYMPTOMS",
                              "conversation_goal": "HEAD_NECK_RELAXATION",
                              "interaction_permission": "OPEN",
                              "confirmed_facts_delta": [
                                {"key": "self_reported_symptom", "value": "Tôi hay chóng mặt, đau đầu, khó ngủ", "op": "ADD", "status": "CURRENT"},
                                {"key": "need_scope", "value": "gội đầu", "op": "ADD", "status": "CURRENT"}
                              ]
                            },
                            "response_plan": {"strategy": "Direct Answer", "tone": "LEVEL_2_REFLECTIVE", "depth": "STANDARD", "cta_level": "SOFT_CTA", "next_action": "SOFT_FEELING_CHECK"}
                          },
                          "bubbles": ["Dạ bên em ghi nhận tình trạng của mình ạ."]
                        }
                        """, 2000).aciReflection();

        brain.applyAciTurnMemoryUpdates(
                PAGE_ID, "sender-v23-5state", "T1", "Tôi hay chóng mặt, đau đầu, khó ngủ",
                List.of(), ref1, ConversationState.StructuredBookingState.none(),
                null, false, null, "Direct Answer"
        );

        AdaptiveConversationIntelligence.CustomerUnderstandingDraft2Snapshot snap1 =
                brain.getCustomerUnderstandingDraft2Snapshot(PAGE_ID, "sender-v23-5state");
        assertThat(snap1.consultativeProfile().currentSymptoms())
                .extracting(AdaptiveConversationIntelligence.VersionedCustomerFact::value)
                .containsExactly("chóng mặt", "đau đầu", "khó ngủ");
        // Không đóng mù request OPEN dù strategy == "Direct Answer" và lượt kế chưa đóng request
        AdaptiveConversationIntelligence.AdaptiveCognitiveReflection refMid =
                CleanAgentOutputRefereeAndSanitizer.parseStructuredLlmJsonOutput("""
                        {
                          "brain_reflection": {
                            "conversational_context": {"current_topic": "hours_question"},
                            "customer_understanding": {"turn_intent": "ASK_HOURS"},
                            "response_plan": {"strategy": "Direct Answer", "next_action": "NO_ADDITIONAL_ACTION"}
                          },
                          "bubbles": ["Dạ bên em mở cửa 09:00 - 22:00 ạ."]
                        }
                        """, 2000).aciReflection();
        brain.applyAciTurnMemoryUpdates(
                PAGE_ID, "sender-v23-5state", "T2", "Mấy giờ đóng cửa?",
                List.of(), refMid, ConversationState.StructuredBookingState.none(),
                null, false, null, "Direct Answer"
        );
        AdaptiveConversationIntelligence.CustomerUnderstandingDraft2Snapshot snapMid =
                brain.getCustomerUnderstandingDraft2Snapshot(PAGE_ID, "sender-v23-5state");
        assertThat(snapMid.conversationalContext().unresolvedRequests())
                .anyMatch(r -> "chi tiết giá phòng VIP".equals(r.item())
                        && r.status() == AdaptiveConversationIntelligence.UnresolvedRequestStatus.OPEN);

        // Lượt 3 (Group D): "Trước đây từng đau lưng nhưng khỏi lâu rồi, hôm nay cũng hết chóng mặt rồi, không bị hoa mắt, thôi mình không gội đầu nữa, muốn massage toàn thân hơn"
        AdaptiveConversationIntelligence.AdaptiveCognitiveReflection ref3 =
                CleanAgentOutputRefereeAndSanitizer.parseStructuredLlmJsonOutput("""
                        {
                          "brain_reflection": {
                            "conversational_context": {"current_topic": "full_body_massage"},
                            "customer_understanding": {
                              "turn_intent": "REVISE_TO_FULL_BODY",
                              "conversation_goal": "FULL_BODY_RELAXATION",
                              "need_revision": false,
                              "interaction_permission": "BOUNDED_INFO_ONLY",
                              "confirmed_facts_delta": [
                                {"key": "self_reported_symptom", "value": "đau lưng", "op": "ADD", "status": "PAST"},
                                {"key": "self_reported_symptom", "value": "chóng mặt", "op": "REVISE", "status": "RESOLVED"},
                                {"key": "self_reported_symptom", "value": "hoa mắt", "op": "ADD", "status": "NEGATED"},
                                {"key": "need_scope", "value": "gội đầu", "op": "REVISE", "status": "SUPERSEDED"},
                                {"key": "need_scope", "value": "massage toàn thân", "op": "REVISE", "status": "CURRENT"}
                              ],
                              "interested_package_id": "NS01"
                            },
                            "response_plan": {"strategy": "Soft Recommendation", "cta_level": "DIRECT_CTA", "next_action": "GENTLE_SCHEDULE_CHECK"}
                          },
                          "bubbles": ["Dạ mình tham khảo gói Massage cơ bản Toàn thân 399k/60 phút nhé ạ."]
                        }
                        """, 2000).aciReflection();

        brain.applyAciTurnMemoryUpdates(
                PAGE_ID, "sender-v23-5state", "T3",
                "Trước đây từng đau lưng nhưng khỏi lâu rồi, hôm nay cũng hết chóng mặt rồi, không bị hoa mắt, thôi mình không gội đầu nữa, muốn massage toàn thân hơn",
                List.of(), ref3, ConversationState.StructuredBookingState.none(),
                null, false, null, "Soft Recommendation"
        );

        AdaptiveConversationIntelligence.CustomerUnderstandingDraft2Snapshot snap3 =
                brain.getCustomerUnderstandingDraft2Snapshot(PAGE_ID, "sender-v23-5state");
        assertThat(snap3.consultativeProfile().interactionPermission())
                .isEqualTo(AdaptiveConversationIntelligence.InteractionPermission.BOUNDED_INFO_ONLY);
        assertThat(snap3.consultativeProfile().symptomsByValidity(AdaptiveConversationIntelligence.FactValidity.RESOLVED))
                .extracting(AdaptiveConversationIntelligence.VersionedCustomerFact::value)
                .containsExactly("chóng mặt");
        assertThat(snap3.consultativeProfile().symptomsByValidity(AdaptiveConversationIntelligence.FactValidity.CURRENT))
                .extracting(AdaptiveConversationIntelligence.VersionedCustomerFact::value)
                .containsExactly("đau đầu", "khó ngủ");
        assertThat(snap3.consultativeProfile().symptomsByValidity(AdaptiveConversationIntelligence.FactValidity.PAST))
                .extracting(AdaptiveConversationIntelligence.VersionedCustomerFact::value)
                .containsExactly("đau lưng");
        assertThat(snap3.consultativeProfile().symptomsByValidity(AdaptiveConversationIntelligence.FactValidity.NEGATED))
                .extracting(AdaptiveConversationIntelligence.VersionedCustomerFact::value)
                .containsExactly("hoa mắt");
        assertThat(snap3.consultativeProfile().supersededFacts())
                .anyMatch(f -> "need_scope".equals(f.key()) && "gội đầu".equals(f.value()));
        assertThat(snap3.consultativeProfile().currentConfirmedFacts())
                .anyMatch(f -> "need_scope".equals(f.key()) && "massage toàn thân".equals(f.value()));

        // Kiểm chứng render <SESSION_MEMORY_5_TIER> hợp nhất 1 khối 3 Tầng duy nhất (không lặp tier_1..tier_5 rời)
        FanpageBotRequest t4Req = buildBotRequest("sender-v23-5state", "Khách", "Gói 399k gồm gì?", null, List.of());
        AdaptiveConversationIntelligence.ThreeStreamTurnInput t4Streams =
                AdaptiveConversationIntelligence.buildThreeStreamInput(
                        t4Req, catalogPackages, nangSenPersona.resolvedConfig(), ZoneId.of("Asia/Ho_Chi_Minh"));
        String renderedPrompt = CleanAgentCatalogAndPromptBuilder.buildAciSinglePassPrompt(
                nangSenPersona, "mình", null, "mình", t4Streams,
                catalogPackages, List.of(),
                CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                List.of(),
                AdaptiveConversationIntelligence.WorkingHypothesisRecord.initialEmpty(),
                List.of(),
                ConversationState.StructuredBookingState.none(),
                brain.getAciOperationalState(PAGE_ID, "sender-v23-5state"),
                AdaptiveConversationIntelligence.PreviousStrategyRecord.initialNone(),
                List.of(),
                null, false, catalogChunks, List.of(), List.of(), List.of(),
                snap3
        );
        assertThat(renderedPrompt)
                .contains("customer_understanding_model_draft2 [UNIFIED_3_LAYER_MEMORY_V23]")
                .contains("interaction_permission: \"BOUNDED_INFO_ONLY\"")
                .contains("[status=RESOLVED | turn=T3]: \"chóng mặt\"")
                .contains("[status=PAST | turn=T3]: \"đau lưng\"")
                .doesNotContain("1. tier_1_customer_statements")
                .doesNotContain("5. tier_5_operational_state");
    }

    @Test
    @DisplayName("V2.3 IV.2: Deterministic Plan Consistency Guard tự khóa NO_CTA khi BOUNDED_INFO_ONLY/PAUSED/STOP và tự nâng DIRECT_CTA khi buying_signal=STRONG/ACTION")
    void aciV23DeterministicPlanConsistencyGuardEnforcesInteractionPermissionAndBuyingSignal() {
        // 1. LLM trả mâu thuẫn: interaction_permission = BOUNDED_INFO_ONLY nhưng lại điền cta_level = DIRECT_CTA, next_action = GENTLE_SCHEDULE_CHECK
        AdaptiveConversationIntelligence.AdaptiveCognitiveReflection boundedRef =
                CleanAgentOutputRefereeAndSanitizer.parseStructuredLlmJsonOutput("""
                        {
                          "brain_reflection": {
                            "customer_understanding": {
                              "turn_intent": "ASK_ADDRESS_LONG_BIEN",
                              "interaction_permission": "BOUNDED_INFO_ONLY",
                              "buying_signal": "NONE"
                            },
                            "response_plan": {
                              "strategy": "Direct Answer",
                              "cta_level": "DIRECT_CTA",
                              "next_action": "GENTLE_SCHEDULE_CHECK",
                              "adaptation": "CONTINUATION"
                            }
                          },
                          "bubbles": ["Dạ cơ sở Long Biên ở TTA11–TTA12 Chung cư HC Golden, 319 Hồng Tiến ạ."]
                        }
                        """, 2000).aciReflection();
        assertThat(boundedRef.responsePlan().ctaLevel()).isEqualTo(AdaptiveConversationIntelligence.CtaLevel.NO_CTA);
        assertThat(boundedRef.responsePlan().nextAction()).isEqualTo("NO_ADDITIONAL_ACTION");

        // 2. LLM trả mâu thuẫn: buying_signal = ACTION (khách muốn đặt tối nay) nhưng lại điền cta_level = NO_CTA, next_action = NO_ADDITIONAL_ACTION
        AdaptiveConversationIntelligence.AdaptiveCognitiveReflection strongBuyRef =
                CleanAgentOutputRefereeAndSanitizer.parseStructuredLlmJsonOutput("""
                        {
                          "brain_reflection": {
                            "customer_understanding": {
                              "turn_intent": "BOOK_TONIGHT",
                              "interaction_permission": "CONCISE_PREFERRED",
                              "buying_signal": "ACTION"
                            },
                            "response_plan": {
                              "strategy": "Soft Recommendation",
                              "cta_level": "NO_CTA",
                              "next_action": "NO_ADDITIONAL_ACTION",
                              "adaptation": "CONTINUATION"
                            }
                          },
                          "bubbles": ["Dạ tối nay mình muốn ghé cơ sở nào và khoảng mấy giờ để em kiểm tra lịch trống gói 399k ạ?"]
                        }
                        """, 2000).aciReflection();
        assertThat(strongBuyRef.responsePlan().ctaLevel()).isEqualTo(AdaptiveConversationIntelligence.CtaLevel.DIRECT_CTA);
        assertThat(strongBuyRef.responsePlan().nextAction()).isEqualTo("GENTLE_SCHEDULE_CHECK");
    }

    @Test
    @DisplayName("V2.3 V.1 & V.2: Ma trận Tri thức Có thẩm quyền (NS01..NS04 buffet CONFIRMED, chặn VIP sai gói, chặn PENDING_CONFIRMATION) & 4-Tier Claim Taxonomy")
    void aciV23AuthoritativeKnowledgeMatrixAndFourTierClaimTaxonomyReferee() {
        String realisticNangSenTable = """
                | Mã gói | Tên gói | Giá | Tổng thời gian | Bao gồm | Không gian | KTV | Điểm nổi bật | Phù hợp |
                | --- | --- | --- | --- | --- | --- | --- | --- | --- |
                | NS01 | Massage cơ bản | 399.000VNĐ | 60 phút | Full body massage trị liệu cơ bản | Phòng chung | KTV tiêu chuẩn | Phòng VIP + buffet thực dưỡng | Khách thư giãn toàn thân |
                | NS02 | Massage thư giãn | 530.000VNĐ | 80 phút | 10p ngâm bồn thảo dược + tắm tráng + 70p massage | Phòng chung | KTV tiêu chuẩn | Phòng VIP + buffet thực dưỡng | Khách muốn ngâm bồn |
                | NS03 | Massage phục hồi | 580.000VNĐ | 100 phút | 10p ngâm bồn thảo dược + 5p xông hơi + 85p massage phục hồi | Phòng VIP riêng | KTV phục hồi | Tẩy tế bào chết + Phòng VIP riêng + Buffet thực dưỡng cao cấp | Phục hồi sâu |
                | DS02 | Dưỡng sinh cơ bản | 239.000VNĐ | 60 phút | Gội thảo dược + massage đầu cổ vai gáy + ăn nhẹ & trái cây theo mùa | Phòng chung | KTV dưỡng sinh | Giường có rèm riêng | Dân văn phòng |
                | DS04 | Combo dưỡng sinh | 500.000VNĐ | 90 phút | Option 1: Gội 30p + Spa 60p / Option 2: Gội 60p + Spa 30p | Phòng chung | KTV dưỡng sinh | Kết hợp gội & spa | Thư giãn kết hợp |
                """;
        List<ReciprocalRankFusion.FusedChunk> matrixChunks = List.of(
                new ReciprocalRankFusion.FusedChunk(
                        new RetrievedChunk(UUID.fromString("c9481efb-0099-4613-a2e0-fe50e9b99b39"), DOC_ID,
                                List.of("MASSAGE & DƯỠNG SINH HÀ NỘI"), List.of(), 1, new double[]{0, 0, 10, 10}, realisticNangSenTable, 0.98),
                        0.98)
        );
        List<PackageCatalogService.PackageSummary> matrixCatalog = PackageCatalogService.extractCatalog(matrixChunks, List.of());

        java.util.function.BiFunction<String, String, List<String>> checkMatrixTruth = (botReply, userMsg) ->
                CleanAgentOutputRefereeAndSanitizer.checkAciV2TruthAuthorityAndSafety(
                        List.of(new FanpageBotResponse.ReplyBubble(botReply, 0)),
                        matrixCatalog,
                        matrixCatalog,
                        List.of(),
                        CleanAgentCatalogAndPromptBuilder.QuotedReplyResolution.empty(),
                        ConversationState.StructuredBookingState.none(),
                        matrixCatalog,
                        nangSenPersona.resolvedConfig(),
                        userMsg,
                        false,
                        null,
                        null,
                        AdaptiveConversationIntelligence.AdaptiveCognitiveReflection.empty(),
                        matrixChunks
                );

        // 1. NS01 (399k) được phép tư vấn buffet thực dưỡng + phòng chung có rèm riêng từng giường (PASS)
        List<String> ns01BuffetOk = checkMatrixTruth.apply(
                "Dạ gói Massage cơ bản 399k/60 phút là không gian phòng chung có rèm riêng từng giường và mình được thưởng thức buffet thực dưỡng sau liệu trình ạ.",
                "Gói 399k gồm gì?"
        );
        assertThat(ns01BuffetOk).isEmpty();

        // 2. NS01 (399k) hứa Phòng VIP riêng -> BỊ CHẶN dù cột H có chữ nhiễu "VIP"!
        List<String> ns01VipBlocked = checkMatrixTruth.apply(
                "Dạ gói Massage cơ bản 399k/60 phút bên em có Phòng VIP riêng tư và buffet thực dưỡng ạ.",
                "Gói 399k có phòng VIP không?"
        );
        assertThat(ns01VipBlocked).anyMatch(v -> v.startsWith("UNGROUNDED_PRIVATE_ROOM"));

        // 3. Group F: Đính chính đối chiếu 399k (phòng chung rèm riêng + buffet) vs 530k (ngâm bồn) vs 580k (Phòng VIP riêng + xông hơi) -> PASS!
        List<String> groupFContrastOk = checkMatrixTruth.apply(
                "Dạ gói 399k (60 phút) là massage toàn thân trong phòng chung có rèm riêng từng giường kèm buffet thực dưỡng, chưa có phòng VIP riêng và ngâm bồn ạ; nếu mình muốn ngâm bồn thảo dược thì có gói 530k (80 phút), còn muốn cả ngâm bồn, xông hơi và Phòng VIP riêng thì từ gói 580k (100 phút) mới đáp ứng trọn vẹn ạ.",
                "Gói 399k có phòng VIP riêng và ngâm bồn thảo dược đúng không shop?"
        );
        assertThat(groupFContrastOk).isEmpty();

        // 4. DS02 (239k) gán buffet -> BỊ CHẶN; DS04 (500k) hứa ăn nhẹ (PENDING_CONFIRMATION) -> BỊ CHẶN; NS03 (580k) hứa tẩy tế bào chết -> BỊ CHẶN
        assertThat(checkMatrixTruth.apply(
                "Dạ gói Dưỡng sinh cơ bản 239k/60 phút có kèm buffet thực dưỡng ạ.",
                "Gói 239k có buffet không?"))
                .anyMatch(v -> v.startsWith("UNGROUNDED_BUFFET_AMENITY"));
        assertThat(checkMatrixTruth.apply(
                "Dạ gói Combo dưỡng sinh 500k/90 phút có ăn nhẹ và trái cây theo mùa sau buổi làm ạ.",
                "Gói 500k có ăn nhẹ không?"))
                .contains("PENDING_CONFIRMATION_REFRESHMENT:DS04");
        assertThat(checkMatrixTruth.apply(
                "Dạ gói Massage phục hồi 580k/100 phút có kèm tẩy tế bào chết toàn thân trong phòng VIP riêng ạ.",
                "Gói 580k gồm gì?"))
                .contains("PENDING_CONFIRMATION_EXFOLIATION:NS03");

        // 5. 4-Tier Claim Taxonomy: CUSTOMER_FEEDBACK được phép (0 lỗi), MEDICAL_CLAIM ("chữa mất ngủ") bị chặn!
        List<String> feedbackOk = checkMatrixTruth.apply(
                "Dạ liệu trình 239k chăm sóc kỹ vùng đầu – cổ – vai – gáy, nhiều khách bên em chia sẻ sau buổi làm cảm giác nặng đầu dịu đi và dễ vào giấc ngủ hơn ạ.",
                "Dạo này mình hay khó ngủ và nặng đầu"
        );
        assertThat(feedbackOk).isEmpty();

        List<String> medicalClaimBlocked = checkMatrixTruth.apply(
                "Dạ gói dưỡng sinh 239k bên em giúp chữa mất ngủ và cam kết hết đau đầu ngay sau buổi đầu tiên ạ.",
                "Dạo này mình hay khó ngủ và nặng đầu"
        );
        assertThat(medicalClaimBlocked)
                .anyMatch(v -> v.startsWith("FORBIDDEN_MEDICAL_CLAIM") || "EXTREME_MEDICAL_CURE_CLAIM".equals(v));
    }

    // =========================================================================
    // UNIT P0.1 — V32 Atomic ON CONFLICT, Pure-mid Idempotency, 8-Cond WAL & 503
    // =========================================================================

    private static org.springframework.jdbc.datasource.DriverManagerDataSource buildLivePostgresDataSource() {
        String jdbcUrl = System.getProperty(
                "spring.datasource.url",
                System.getenv().getOrDefault("SPRING_DATASOURCE_URL", "jdbc:postgresql://localhost:5433/docmind")
        );
        String jdbcUser = System.getProperty(
                "spring.datasource.username",
                System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "docmind")
        );
        String jdbcPass = System.getProperty(
                "spring.datasource.password",
                System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "docmind")
        );
        org.springframework.jdbc.datasource.DriverManagerDataSource ds =
                new org.springframework.jdbc.datasource.DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(jdbcUrl);
        ds.setUsername(jdbcUser);
        ds.setPassword(jdbcPass);
        return ds;
    }

    @Test
    @DisplayName("P0.1-A: V32 Concurrent DB ON CONFLICT (CyclicBarrier) & 4-Row Pure-mid Idempotency Matrix")
    void testP01_V32AtomicConcurrentOnConflictAndPureMidIdempotencyMatrix(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempWalDir
    ) throws Exception {
        var ds = buildLivePostgresDataSource();
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(ds);
        var txMgr = new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds);

        // Apply idempotent V32 index DDL
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_conv_messages_session_ext_mid "
                + "ON conversation_messages (session_id, external_mid) WHERE external_mid IS NOT NULL");

        var walStore = new DurableInboundWalRecoveryStore(tempWalDir.resolve("wal_a"), 67_108_864L);
        var persistenceSvc = new InboundSessionPersistenceService(jdbc, txMgr, walStore);
        walStore.setDbReplayer(persistenceSvc::replayWalRecordToDatabase);

        String racePsid = "psid-p01-race-" + UUID.randomUUID();
        String pureMidPsid = "psid-p01-puremid-" + UUID.randomUUID();
        String tier3Psid = "psid-p01-tier3-" + UUID.randomUUID();

        try {
            // 1. Tier-1 pure-mid invariance proof (Spec Item I.2: mergedText NEVER mixed into Tier-1 key)
            String singleShortKey1 = InboundEventIdResolver.deriveTier1FromSingleMid("m_fb_short_001");
            assertThat(singleShortKey1).isEqualTo("fbmid:m_fb_short_001");

            String longMid = "m_" + "x".repeat(120);
            String singleLongKey = InboundEventIdResolver.deriveTier1FromSingleMid(longMid);
            assertThat(singleLongKey).startsWith("fbmid_sha256:").hasSize(77);

            String burstKeyOrderA = InboundEventIdResolver.deriveTier1FromBurstMids(List.of("m_burst_2", "m_burst_1"));
            String burstKeyOrderB = InboundEventIdResolver.deriveTier1FromBurstMids(List.of("m_burst_1", "m_burst_2"));
            assertThat(burstKeyOrderA).isEqualTo(burstKeyOrderB).startsWith("fbmid_sha256:");

            // Mutated text with identical Facebook mid MUST resolve to identical Tier-1 key & hit ON CONFLICT DO NOTHING
            try {
                org.slf4j.MDC.put("fb_message_id", "m_same_mid_diff_text");
                FanpageBotRequest reqV1 = new FanpageBotRequest(
                        PAGE_ID.toString(), pureMidPsid, "Khách Test Mid", "Xin chào shop",
                        null, List.of(), null, 4, null, null, null, null, false, null, null,
                        "fb_webhook_" + pureMidPsid, false, null, null
                );
                FanpageBotRequest reqMutatedText = new FanpageBotRequest(
                        PAGE_ID.toString(), pureMidPsid, "Khách Test Mid", "Xin chào shop   (re-normalized!)",
                        null, List.of(), null, 4, null, null, null, null, false, null, null,
                        "fb_webhook_" + pureMidPsid, false, null, null
                );
                var eval1 = persistenceSvc.evaluateEntryAndPersistCustomer(reqV1, "Xin chào shop");
                var eval2 = persistenceSvc.evaluateEntryAndPersistCustomer(reqMutatedText, "Xin chào shop   (re-normalized!)");
                assertThat(eval1.action()).isEqualTo(InboundSessionPersistenceService.EntryAction.PROCEED_TO_BOT);
                assertThat(eval2.action()).isEqualTo(InboundSessionPersistenceService.EntryAction.SUPPRESS_DUPLICATE_INBOUND);
                assertThat(eval1.resolvedEvent().externalMid()).isEqualTo("fbmid:m_same_mid_diff_text");
                assertThat(eval2.resolvedEvent().externalMid()).isEqualTo("fbmid:m_same_mid_diff_text");

                Integer countPureMid = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM conversation_messages m "
                                + "JOIN conversation_sessions s ON s.id = m.session_id "
                                + "WHERE s.customer_psid = ?",
                        Integer.class,
                        pureMidPsid
                );
                assertThat(countPureMid).isEqualTo(1);
            } finally {
                org.slf4j.MDC.remove("fb_message_id");
            }

            // 2. Concurrent CyclicBarrier(2) race on brand-new customer with same mid -> 1 session, 1 CUSTOMER row
            java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                java.util.concurrent.Callable<InboundSessionPersistenceService.EntryEvaluation> racer = () -> {
                    org.slf4j.MDC.put("fb_message_id", "m_race_concurrent_01");
                    try {
                        FanpageBotRequest raceReq = new FanpageBotRequest(
                                PAGE_ID.toString(), racePsid, "Khách Đua Luồng", "Gói 239k còn lịch không?",
                                null, List.of(), null, 4, null, null, null, null, false, null, null,
                                "fb_webhook_" + racePsid, false, null, null
                        );
                        barrier.await(5, TimeUnit.SECONDS);
                        return persistenceSvc.evaluateEntryAndPersistCustomer(raceReq, "Gói 239k còn lịch không?");
                    } finally {
                        org.slf4j.MDC.remove("fb_message_id");
                    }
                };
                var f1 = pool.submit(racer);
                var f2 = pool.submit(racer);
                var r1 = f1.get(10, TimeUnit.SECONDS);
                var r2 = f2.get(10, TimeUnit.SECONDS);

                List<InboundSessionPersistenceService.EntryAction> actions = List.of(r1.action(), r2.action());
                assertThat(actions).containsExactlyInAnyOrder(
                        InboundSessionPersistenceService.EntryAction.PROCEED_TO_BOT,
                        InboundSessionPersistenceService.EntryAction.SUPPRESS_DUPLICATE_INBOUND
                );

                Integer sessionRows = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM conversation_sessions WHERE customer_psid = ?",
                        Integer.class,
                        racePsid
                );
                Integer msgRows = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM conversation_messages m "
                                + "JOIN conversation_sessions s ON s.id = m.session_id "
                                + "WHERE s.customer_psid = ?",
                        Integer.class,
                        racePsid
                );
                assertThat(sessionRows).isEqualTo(1);
                assertThat(msgRows).isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }

            // 3. Tier-2 timestamp fallback & Tier-3b missing protocol ID telemetry + two consecutive "ok" turns
            try {
                org.slf4j.MDC.put("fb_message_id", "10987654321:1730000123456:Cho chị hỏi giá");
                FanpageBotRequest tier2Req = new FanpageBotRequest(
                        PAGE_ID.toString(), tier3Psid, "Khách Tier 2/3", "Cho chị hỏi giá",
                        null, List.of(), null, 4, null, null, null, null, false, null, null,
                        "fb_webhook_" + tier3Psid, false, null, null
                );
                var t2Eval = persistenceSvc.evaluateEntryAndPersistCustomer(tier2Req, "Cho chị hỏi giá");
                assertThat(t2Eval.resolvedEvent().tier())
                        .isEqualTo(InboundEventIdResolver.IdempotencyTier.TIER_2_FB_TIMESTAMP_HASH);
                assertThat(t2Eval.resolvedEvent().externalMid()).startsWith("fb_nomid_sha256:");
            } finally {
                org.slf4j.MDC.remove("fb_message_id");
            }

            assertThat(InboundEventIdResolver.deriveTier3aFromIdempotencyHeader("client-req-99"))
                    .isEqualTo("idem:client-req-99");

            long beforeMissing = InboundEventIdResolver.getMissingProtocolIdTotal();
            FanpageBotRequest okReq1 = new FanpageBotRequest(
                    PAGE_ID.toString(), tier3Psid, "Khách Tier 2/3", "ok",
                    null, List.of(), null, 4, null, null, null, null, false, null, null,
                    "sim_conv_" + tier3Psid, false, null, null
            );
            var okEval1 = persistenceSvc.evaluateEntryAndPersistCustomer(okReq1, "ok");
            var okEval2 = persistenceSvc.evaluateEntryAndPersistCustomer(okReq1, "ok");
            assertThat(InboundEventIdResolver.getMissingProtocolIdTotal()).isEqualTo(beforeMissing + 2);
            assertThat(okEval1.resolvedEvent().externalMid()).startsWith("turn:");
            assertThat(okEval2.resolvedEvent().externalMid())
                    .startsWith("turn:")
                    .isNotEqualTo(okEval1.resolvedEvent().externalMid());

            Integer okRows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM conversation_messages m "
                            + "JOIN conversation_sessions s ON s.id = m.session_id "
                            + "WHERE s.customer_psid = ? AND m.content = 'ok'",
                    Integer.class,
                    tier3Psid
            );
            assertThat(okRows).isEqualTo(2);
        } finally {
            jdbc.update("DELETE FROM conversation_sessions WHERE customer_psid IN (?, ?, ?)",
                    racePsid, pureMidPsid, tier3Psid);
        }
    }

    @Test
    @DisplayName("P0.1-B: DurableInboundWalRecoveryStore 8-Condition Crash-Point Matrix (a)-(e) & 0700/0600 POSIX")
    void testP01_DurableWalCrashPointMatrixAtoEAndPosixPermissions(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir
    ) throws Exception {
        var ds = buildLivePostgresDataSource();
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(ds);
        var txMgr = new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds);
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_conv_messages_session_ext_mid "
                + "ON conversation_messages (session_id, external_mid) WHERE external_mid IS NOT NULL");

        var realPersistence = new InboundSessionPersistenceService(jdbc, txMgr, null);

        String psidA = "psid-p01-wal-a-" + UUID.randomUUID();
        String psidB = "psid-p01-wal-b-" + UUID.randomUUID();
        String psidC = "psid-p01-wal-c-" + UUID.randomUUID();
        String psidD = "psid-p01-wal-d-" + UUID.randomUUID();
        String psidEBrandNew = "psid-p01-wal-e-new-" + UUID.randomUUID();

        try {
            // --- Crash-Point (a): Corrupted checksum + truncated partial line quarantined without hanging ---
            java.nio.file.Path dirA = tempDir.resolve("case_a");
            var storeA = new DurableInboundWalRecoveryStore(dirA, 67_108_864L);
            // Line 1: malformed/corrupted checksum JSON line
            storeA.appendAndFsync(PAGE_ID.toString(), psidA, "Khách A", "fb_webhook_" + psidA,
                    "fbmid:m_wal_a_valid", "Dạ cho chị đặt gói 399k 💆‍♀️🌿", System.currentTimeMillis());
            String validLine = java.nio.file.Files.readString(storeA.getWalFile());
            String badChecksumLine = "{\"walSeq\":99,\"pageId\":\"" + PAGE_ID + "\",\"senderId\":\"" + psidA
                    + "\",\"inboundExternalMid\":\"fbmid:m_bad\",\"verbatimText\":\"tampered\","
                    + "\"receivedAtEpochMs\":1730000000,\"sha256Checksum\":\"deadbeef\"}\n";
            String truncatedTailNoNewline = "{\"walSeq\":100,\"pageId\":\"" + PAGE_ID + "\",\"senderId\":\"cut_mid_write";
            java.nio.file.Files.writeString(
                    storeA.getWalFile(),
                    badChecksumLine + validLine + truncatedTailNoNewline,
                    java.nio.charset.StandardCharsets.UTF_8
            );

            var restartedStoreA = new DurableInboundWalRecoveryStore(dirA, 67_108_864L);
            var reportA = restartedStoreA.replayPendingRecords(realPersistence::replayWalRecordToDatabase);
            assertThat(reportA.replayedValidRecords()).isEqualTo(1);
            assertThat(reportA.quarantinedCorruptRecords()).isEqualTo(2);
            assertThat(reportA.compacted()).isTrue();
            assertThat(java.nio.file.Files.exists(restartedStoreA.getQuarantineFile())).isTrue();

            // Verify POSIX 0700 on dir and 0600 on files
            if (java.nio.file.Files.getFileAttributeView(dirA, java.nio.file.attribute.PosixFileAttributeView.class) != null) {
                var dirPerms = java.nio.file.Files.getPosixFilePermissions(dirA);
                assertThat(java.nio.file.attribute.PosixFilePermissions.toString(dirPerms)).isEqualTo("rwx------");
                var walPerms = java.nio.file.Files.getPosixFilePermissions(restartedStoreA.getWalFile());
                assertThat(java.nio.file.attribute.PosixFilePermissions.toString(walPerms)).isEqualTo("rw-------");
                var qPerms = java.nio.file.Files.getPosixFilePermissions(restartedStoreA.getQuarantineFile());
                assertThat(java.nio.file.attribute.PosixFilePermissions.toString(qPerms)).isEqualTo("rw-------");
            }

            // --- Crash-Point (b) & (e): Crash after fsync before DB commit + brand-new customer with 0 prior session ---
            java.nio.file.Path dirBE = tempDir.resolve("case_be");
            var storeBeforeCrashB = new DurableInboundWalRecoveryStore(dirBE, 67_108_864L);
            var brokenDs = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                    "jdbc:postgresql://127.0.0.1:1/unreachable_db?connectTimeout=1", "bad", "bad"
            );
            var outagePersistence = new InboundSessionPersistenceService(
                    new org.springframework.jdbc.core.JdbcTemplate(brokenDs),
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(brokenDs),
                    storeBeforeCrashB
            );
            try {
                org.slf4j.MDC.put("fb_message_id", "m_wal_brand_new_e01");
                FanpageBotRequest webhookDuringOutage = new FanpageBotRequest(
                        PAGE_ID.toString(), psidEBrandNew, "Khách Mới Tinh Chưa Có Session",
                        "Chị mới nhắn lần đầu hỏi gói 239k ạ",
                        null, List.of(), null, 4, null, null, null, null, false, null, null,
                        "fb_webhook_" + psidEBrandNew, false, null, null
                );
                var outageEval = outagePersistence.evaluateEntryAndPersistCustomer(
                        webhookDuringOutage,
                        "Chị mới nhắn lần đầu hỏi gói 239k ạ"
                );
                assertThat(outageEval.action())
                        .isEqualTo(InboundSessionPersistenceService.EntryAction.BLOCK_FAIL_CLOSED_WEBHOOK);
            } finally {
                org.slf4j.MDC.remove("fb_message_id");
            }
            assertThat(storeBeforeCrashB.hasPendingWalRecords()).isTrue();
            Integer preReplaySessions = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM conversation_sessions WHERE customer_psid = ?",
                    Integer.class,
                    psidEBrandNew
            );
            assertThat(preReplaySessions).isEqualTo(0);

            // Simulate container restart with fresh store instance & restored DB -> FK-safe session + message created!
            var restartedStoreBE = new DurableInboundWalRecoveryStore(dirBE, 67_108_864L);
            var reportBE = restartedStoreBE.replayPendingRecords(realPersistence::replayWalRecordToDatabase);
            assertThat(reportBE.replayedValidRecords()).isEqualTo(1);
            assertThat(reportBE.compacted()).isTrue();
            Integer postReplayMsgsE = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM conversation_messages m "
                            + "JOIN conversation_sessions s ON s.id = m.session_id "
                            + "WHERE s.customer_psid = ? AND m.external_mid = 'fbmid:m_wal_brand_new_e01'",
                    Integer.class,
                    psidEBrandNew
            );
            assertThat(postReplayMsgsE).isEqualTo(1);

            // --- Crash-Point (c): Crash AFTER DB commit BEFORE checkpoint update -> 0 duplicates on replay ---
            java.nio.file.Path dirC = tempDir.resolve("case_c");
            var storeC = new DurableInboundWalRecoveryStore(dirC, 67_108_864L);
            storeC.appendAndFsync(PAGE_ID.toString(), psidC, "Khách C", "fb_webhook_" + psidC,
                    "fbmid:m_wal_c_committed", "Tin nhắn đã commit nhưng chưa kịp ghi checkpoint",
                    System.currentTimeMillis());
            // Pre-commit the exact same record to DB while leaving checkpoint untouched at 0
            realPersistence.replayWalRecordToDatabase(new DurableInboundWalRecoveryStore.WalRecord(
                    1L, PAGE_ID.toString(), psidC, "Khách C", "fb_webhook_" + psidC,
                    "fbmid:m_wal_c_committed", "Tin nhắn đã commit nhưng chưa kịp ghi checkpoint",
                    System.currentTimeMillis(), "unused"
            ));
            assertThat(java.nio.file.Files.exists(storeC.getCheckpointFile())).isFalse();

            var restartedStoreC = new DurableInboundWalRecoveryStore(dirC, 67_108_864L);
            var reportC = restartedStoreC.replayPendingRecords(realPersistence::replayWalRecordToDatabase);
            assertThat(reportC.replayedValidRecords()).isEqualTo(1);
            assertThat(reportC.compacted()).isTrue();
            Integer countC = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM conversation_messages m "
                            + "JOIN conversation_sessions s ON s.id = m.session_id "
                            + "WHERE s.customer_psid = ?",
                    Integer.class,
                    psidC
            );
            assertThat(countC).isEqualTo(1);

            // --- Crash-Point (d): Crash mid-compact (checkpoint reset to 0 + orphaned .compact.tmp, wal.jsonl un-truncated) ---
            java.nio.file.Path dirD = tempDir.resolve("case_d");
            var storeD = new DurableInboundWalRecoveryStore(dirD, 67_108_864L);
            storeD.appendAndFsync(PAGE_ID.toString(), psidD, "Khách D", "fb_webhook_" + psidD,
                    "fbmid:m_wal_d_compact", "Tin nhắn kiểm thử crash giữa lúc compact",
                    System.currentTimeMillis());
            byte[] savedWalBytesD = java.nio.file.Files.readAllBytes(storeD.getWalFile());
            // Run normal replay first so DB has the record, then restore un-truncated wal.jsonl + orphaned .tmp
            storeD.replayPendingRecords(realPersistence::replayWalRecordToDatabase);
            java.nio.file.Files.write(storeD.getWalFile(), savedWalBytesD);
            storeD.writeCheckpointAtomically(0L);
            java.nio.file.Files.writeString(dirD.resolve("inbound_recovery_wal.jsonl.compact.tmp"), "orphan_tmp");
            java.nio.file.Files.writeString(dirD.resolve("inbound_recovery_wal.checkpoint.tmp"), "999999");

            var restartedStoreD = new DurableInboundWalRecoveryStore(dirD, 67_108_864L);
            restartedStoreD.onApplicationReady();
            var reportD = restartedStoreD.replayPendingRecords(realPersistence::replayWalRecordToDatabase);
            assertThat(java.nio.file.Files.size(restartedStoreD.getWalFile())).isEqualTo(0L);
            assertThat(java.nio.file.Files.exists(dirD.resolve("inbound_recovery_wal.jsonl.compact.tmp"))).isFalse();
            Integer countD = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM conversation_messages m "
                            + "JOIN conversation_sessions s ON s.id = m.session_id "
                            + "WHERE s.customer_psid = ?",
                    Integer.class,
                    psidD
            );
            assertThat(countD).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM conversation_sessions WHERE customer_psid IN (?, ?, ?, ?, ?)",
                    psidA, psidB, psidC, psidD, psidEBrandNew);
        }
    }

    @Test
    @DisplayName("P0.1-C: Sync REST HTTP 503 (Retry-After: 5), WAL Max-Bytes Overflow Counter & HUMAN_TAKEOVER 0-LLM Lifecycle")
    void testP01_SyncRestHttp503RetryAfterWalMaxBytesOverflowAndHumanTakeoverLifecycle(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir
    ) {
        // 1. Sync REST channel with unreachable DB -> throws InboundPersistenceUnavailableException -> HTTP 503 + Retry-After: 5
        var brokenDs = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:postgresql://127.0.0.1:1/unreachable_db?connectTimeout=1", "bad", "bad"
        );
        var tinyWal = new DurableInboundWalRecoveryStore(tempDir.resolve("tiny_wal"), 350L);
        var brokenPersistence = new InboundSessionPersistenceService(
                new org.springframework.jdbc.core.JdbcTemplate(brokenDs),
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(brokenDs),
                tinyWal
        );

        AtomicInteger llmCallCounter = new AtomicInteger(0);
        AgentBrainService sharedBrain = new AgentBrainService((com.example.docmind.client.AgentBrainClient) null);
        FanpageBotService outageBot = createIsolatedBotServiceWithBrain(
                AdaptiveConversationIntelligence.CognitiveMode.LLM_COGNITIVE_V2,
                sharedBrain,
                (prompt, budget) -> {
                    llmCallCounter.incrementAndGet();
                    return "{\"reply_bubbles\":[\"Dạ bên em chào chị ạ.\"]}";
                }
        );
        outageBot.setInboundSessionPersistenceService(brokenPersistence);

        FanpageBotRequest syncRestReq = new FanpageBotRequest(
                PAGE_ID.toString(), "psid-rest-503", "Khách REST", "Cho chị hỏi giá gói 239k",
                null, List.of(), null, 4, null, null, null, null, false, null, null,
                "rest_conv_503", false, null, null
        );
        InboundPersistenceUnavailableException thrown503 = org.junit.jupiter.api.Assertions.assertThrows(
                InboundPersistenceUnavailableException.class,
                () -> outageBot.handleCustomerMessage(syncRestReq)
        );
        var exceptionHandler = new com.example.docmind.controller.InboundPersistenceExceptionHandler();
        var responseEntity = exceptionHandler.handleInboundPersistenceUnavailable(thrown503);
        assertThat(responseEntity.getStatusCode().value()).isEqualTo(503);
        assertThat(responseEntity.getHeaders().getFirst("Retry-After")).isEqualTo("5");
        assertThat(llmCallCounter.get()).isEqualTo(0);

        // 2. Webhook channel hitting max-bytes cap (350 bytes) -> increments docmind_inbound_wal_overflow_total & stays Fail-Closed
        try {
            org.slf4j.MDC.put("fb_message_id", "m_overflow_1");
            FanpageBotRequest wh1 = new FanpageBotRequest(
                    PAGE_ID.toString(), "psid-wh-ovf", "Khách WH", "Tin ngắn 1",
                    null, List.of(), null, 4, null, null, null, null, false, null, null,
                    "fb_webhook_psid-wh-ovf", false, null, null
            );
            FanpageBotResponse r1 = outageBot.handleCustomerMessage(wh1);
            assertThat(r1.replyBubbles()).isEmpty();
            assertThat(r1.needsHumanHandoff()).isFalse();
            assertThat(r1.triggeredHooks()).contains("STATE_GUARD_FAIL_CLOSED");

            org.slf4j.MDC.put("fb_message_id", "m_overflow_2");
            FanpageBotRequest wh2 = new FanpageBotRequest(
                    PAGE_ID.toString(), "psid-wh-ovf", "Khách WH",
                    "Tin nhắn thứ hai vượt ngưỡng 350 bytes để kích hoạt bộ đếm tràn WAL bền vững trên đĩa!",
                    null, List.of(), null, 4, null, null, null, null, false, null, null,
                    "fb_webhook_psid-wh-ovf", false, null, null
            );
            FanpageBotResponse r2 = outageBot.handleCustomerMessage(wh2);
            assertThat(r2.replyBubbles()).isEmpty();
            assertThat(r2.needsHumanHandoff()).isFalse();
            assertThat(tinyWal.getWalOverflowTotal()).isGreaterThanOrEqualTo(1L);
            assertThat(llmCallCounter.get()).isEqualTo(0);
        } finally {
            org.slf4j.MDC.remove("fb_message_id");
        }

        // 3. Live Postgres HUMAN_TAKEOVER -> 0 LLM, 0 outbound, needsHumanHandoff=false, CUSTOMER saved, 0 AI_BOT rows
        var liveDs = buildLivePostgresDataSource();
        var liveJdbc = new org.springframework.jdbc.core.JdbcTemplate(liveDs);
        liveJdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_conv_messages_session_ext_mid "
                + "ON conversation_messages (session_id, external_mid) WHERE external_mid IS NOT NULL");
        var liveTx = new org.springframework.jdbc.datasource.DataSourceTransactionManager(liveDs);
        var liveWal = new DurableInboundWalRecoveryStore(tempDir.resolve("live_wal"), 67_108_864L);
        var livePersistence = new InboundSessionPersistenceService(liveJdbc, liveTx, liveWal);

        FanpageBotService liveBot = createIsolatedBotServiceWithBrain(
                AdaptiveConversationIntelligence.CognitiveMode.LLM_COGNITIVE_V2,
                sharedBrain,
                (prompt, budget) -> {
                    llmCallCounter.incrementAndGet();
                    return "{\"reply_bubbles\":[\"Dạ bên em chào chị ạ.\"]}";
                }
        );
        liveBot.setInboundSessionPersistenceService(livePersistence);

        String takeoverPsid = "psid-p01-takeover-" + UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        try {
            liveJdbc.update(
                    "INSERT INTO conversation_sessions ("
                            + "id, page_id, customer_psid, customer_name, honorific, active_topic, topic_family, "
                            + "control_mode, buying_temperature, temperature_delta, temperature_label, momentum, "
                            + "buyer_archetype, archetype_label, sales_stage, has_human_sale_reply, learned_in_cold_path, "
                            + "last_message_preview, last_sender_role, last_message_at, created_at, updated_at"
                            + ") VALUES (?, ?, ?, 'Khách Takeover', 'Chị', 'Tư vấn', 'GENERAL', "
                            + "'HUMAN_TAKEOVER', 50, 0, 'WARM', 'STABLE', "
                            + "'EXPLORING_CONSULTATION', 'Đang tìm hiểu', 'DISCOVERY', TRUE, FALSE, "
                            + "'Sale đang hỗ trợ', 'HUMAN_SALE', NOW(), NOW(), NOW())",
                    sessionId, PAGE_ID, takeoverPsid
            );

            FanpageBotRequest takeoverTurn = new FanpageBotRequest(
                    PAGE_ID.toString(), takeoverPsid, "Khách Takeover", "Em ơi chị chuyển khoản cọc rồi nhé",
                    null, List.of(), null, 4, null, null, null, null, false, null, null,
                    "fb_webhook_" + takeoverPsid, false, null, null
            );
            FanpageBotResponse blockedResp = liveBot.handleCustomerMessage(takeoverTurn);
            assertThat(llmCallCounter.get()).isEqualTo(0);
            assertThat(blockedResp.replyBubbles()).isEmpty();
            assertThat(blockedResp.replyText()).isEmpty();
            assertThat(blockedResp.needsHumanHandoff()).isFalse();
            assertThat(blockedResp.handoffCategory()).isNull();
            assertThat(blockedResp.triggeredHooks()).contains("HUMAN_TAKEOVER_SILENT_HOLD");

            Integer savedCustCount = liveJdbc.queryForObject(
                    "SELECT COUNT(*) FROM conversation_messages WHERE session_id = ? AND sender_role = 'CUSTOMER'",
                    Integer.class,
                    sessionId
            );
            Integer savedBotCount = liveJdbc.queryForObject(
                    "SELECT COUNT(*) FROM conversation_messages WHERE session_id = ? AND sender_role = 'AI_BOT'",
                    Integer.class,
                    sessionId
            );
            assertThat(savedCustCount).isEqualTo(1);
            assertThat(savedBotCount).isEqualTo(0);
        } finally {
            liveJdbc.update("DELETE FROM conversation_sessions WHERE id = ?", sessionId);
        }
    }
}
