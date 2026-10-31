package com.example.docmind.service.bot;

import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.dto.bot.FanpageBotResponse.ReplyBubble;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Chuẩn hóa <b>Price Quote Reading Budget (10s)</b> &amp; mốc {@code quoteBubbleAcceptedAt} (Mục 6 — {@code LLM_COGNITIVE_V2}).
 *
 * <p>Các bất biến kỹ thuật bắt buộc:
 * <ol>
 *   <li><b>Xác định đúng bong bóng chứa báo giá ({@link #findActualPriceQuoteBubbleIndex}):</b>
 *       Quét thực tế từng bong bóng để tìm bong bóng chứa mức giá có căn cứ và thông tin gói dịch vụ
 *       (không gán cứng {@code bubble 0} nếu {@code bubble 0} chỉ là lời chào/đồng cảm).</li>
 *   <li><b>Mốc {@code quoteBubbleAcceptedAt}:</b>
 *       Chỉ ghi nhận sau khi kênh gửi (Messenger) xác nhận tiếp nhận gửi thành công bong bóng báo giá;
 *       nếu gửi thất bại ({@code sendAccepted == false}) thì KHÔNG kích hoạt ngân sách đọc 10 giây.
 *       Hỗ trợ ghi nhận riêng {@code quoteBubbleDeliveredAt} khi có webhook delivery độc lập.</li>
 *   <li><b>Cấm trừ {@code llmLatencyMs} vào ngân sách 10 giây:</b>
 *       Thời gian suy luận của LLM xảy ra trước khi gửi bong bóng báo giá tuyệt đối không được khấu trừ vào 10.000ms.</li>
 *   <li><b>Công thức lập lịch cho bong bóng ngay sau bong bóng báo giá:</b>
 *       {@code T_nextBubbleSend = max(T_nextBubbleReady, T_quoteBubbleAccepted + 10_000ms)},
 *       {@code remainingWaitMs = max(0, 10_000 - max(0, T_nextBubbleReady - T_quoteBubbleAccepted))}.</li>
 *   <li><b>Chống kích hoạt nhầm trên lượt phản đối giá:</b>
 *       Tuyệt đối không kích hoạt ở lượt khách đang chê đắt / so sánh ngày công (như {@code S03B} {@code "Bằng cả ngày đi làm"}).</li>
 *   <li><b>Tích hợp {@link SenderEpochRegistry} &amp; {@link SentBubbleLedger}:</b>
 *       Hủy gửi các bong bóng tiếp theo ngay khi khách nhắn chen ngang trong lúc chờ (epoch supersede) và chống gửi trùng.</li>
 * </ol>
 */
public final class PriceQuoteReadingBudgetScheduler {

    private static final Logger log = LoggerFactory.getLogger(PriceQuoteReadingBudgetScheduler.class);

    public static final long PRICE_QUOTE_READING_BUDGET_MS = 10_000L;
    public static final int DEFAULT_INTER_BUBBLE_TYPING_DELAY_MS = 2_000;

    private static final int MAX_DEDUPE_ENTRIES = 4_096;
    private static final Set<String> SENT_BUBBLE_DEDUPE_KEYS = ConcurrentHashMap.newKeySet();

    private static final Pattern PRICE_OBJECTION_OR_COMPARISON_TURN_PATTERN = Pattern.compile(
            "(?isU).*\\b("
                    + "đắt|mắc|chát\\s+quá|cao\\s+quá|hơi\\s+cao|giá\\s+căng|vượt\\s+ngân\\s+sách|"
                    + "(?:bằng\\s+)?(?:cả\\s+|1\\s+|một\\s+)?ngày\\s+(?:công|đi\\s+làm|lương)|"
                    + "bên\\s+kia\\s+rẻ\\s+hơn|chỗ\\s+khác\\s+rẻ\\s+hơn|spa\\s+khác\\s+rẻ\\s+hơn|"
                    + "biết\\s+thế"
                    + ")\\b.*"
    );

    private static final Pattern EXPLICIT_PRICE_OR_OPTION_INQUIRY_PATTERN = Pattern.compile(
            "(?isU).*\\b("
                    + "giá\\s+(?:bao\\s+nhiêu|sao|thế\\s+nào|như\\s+nào|ntn)|bao\\s+nhiêu\\s+tiền|báo\\s+giá|bảng\\s+giá|"
                    + "gói\\s+(?:nào\\s+)?(?:rẻ|mềm|tiết\\s+kiệm|ngắn|nhẹ)(?:\\s+tiền|\\s+hơn|\\s+chút)?|còn\\s+gói\\s+nào\\s+khác|"
                    + "(?:hỏi|xem|tư\\s+vấn)\\s+(?:thêm\\s+)?gói\\s+(?:[^.!?\\n]{0,30}\\s+)?\\d{2,4}\\s*k"
                    + ")\\b.*"
    );

    private static final Pattern NON_QUOTE_CONVERSATIONAL_TURN_PATTERN = Pattern.compile(
            "(?isU)^\\s*("
                    + "ừ|ư|ừm|uk|uki|ok|oke|okay|vâng|dạ|ờ|"
                    + "thôi[,\\s]+không\\s+cần\\s+nữa|không\\s+cần\\s+nữa|"
                    + "ừ[,\\s]+để\\s+(?:tôi|mình|chị|anh|em)\\s+xem|để\\s+(?:tôi|mình|chị|anh|em)\\s+(?:xem|nghĩ|cân\\s+nhắc)(?:\\s+thêm)?|"
                    + "tôi\\s+không\\s+hài\\s+lòng"
                    + ")[.!~\\s]*$"
    );

    private static final Pattern PACKAGE_SERVICE_SIGNAL_IN_BUBBLE_PATTERN = Pattern.compile(
            "(?isU).*\\b(gói|liệu\\s+trình|combo|dịch\\s+vụ|buổi|\\d{2,3}\\s*phút|chuyên\\s+sâu|dưỡng\\s+sinh|thư\\s+giãn|trị\\s+liệu|cổ\\s+vai\\s+gáy|toàn\\s+thân|body)\\b.*"
    );

    private PriceQuoteReadingBudgetScheduler() {
    }

    /**
     * Bản ghi xác nhận tiếp nhận bong bóng báo giá từ kênh gửi (Messenger).
     * Lưu ý: {@code quoteBubbleAcceptedAtMs} là thời điểm Messenger chấp nhận gửi, KHÔNG tuyên bố khách chắc chắn đã đọc.
     */
    public record QuoteBubbleAcceptanceRecord(
            int quoteBubbleIndex,
            boolean budgetEligible,
            boolean sendAccepted,
            String outboundMessageId,
            long llmLatencyMs,
            long quoteBubbleAcceptedAtMs,
            Long quoteBubbleDeliveredAtMs
    ) {
        public static QuoteBubbleAcceptanceRecord notApplicable(long llmLatencyMs) {
            return new QuoteBubbleAcceptanceRecord(-1, false, false, null, Math.max(0L, llmLatencyMs), -1L, null);
        }

        public static QuoteBubbleAcceptanceRecord accepted(
                int quoteBubbleIndex,
                String outboundMessageId,
                long llmLatencyMs,
                long quoteBubbleAcceptedAtMs
        ) {
            return new QuoteBubbleAcceptanceRecord(
                    quoteBubbleIndex,
                    quoteBubbleIndex >= 0,
                    true,
                    outboundMessageId,
                    Math.max(0L, llmLatencyMs),
                    quoteBubbleAcceptedAtMs,
                    null
            );
        }

        public static QuoteBubbleAcceptanceRecord sendFailed(
                int quoteBubbleIndex,
                long llmLatencyMs
        ) {
            return new QuoteBubbleAcceptanceRecord(
                    quoteBubbleIndex,
                    quoteBubbleIndex >= 0,
                    false,
                    null,
                    Math.max(0L, llmLatencyMs),
                    -1L,
                    null
            );
        }

        public QuoteBubbleAcceptanceRecord withDeliveredAt(long deliveredAtMs) {
            return new QuoteBubbleAcceptanceRecord(
                    quoteBubbleIndex,
                    budgetEligible,
                    sendAccepted,
                    outboundMessageId,
                    llmLatencyMs,
                    quoteBubbleAcceptedAtMs,
                    deliveredAtMs
            );
        }

        public boolean isReadingBudgetStarted() {
            return budgetEligible && quoteBubbleIndex >= 0 && sendAccepted && quoteBubbleAcceptedAtMs >= 0L;
        }
    }

    /**
     * Quyết định lập lịch gửi cho bong bóng ngay sau bong bóng báo giá.
     */
    public record NextBubbleScheduleDecision(
            boolean readingBudgetApplied,
            long quoteBubbleAcceptedAtMs,
            long nextBubbleReadyAtMs,
            long earliestAllowedSendAtMs,
            long scheduledSendAtMs,
            long elapsedSinceQuoteAcceptedMs,
            long remainingWaitMs
    ) {
    }

    public record OutboundSendReceipt(
            boolean accepted,
            String messageId,
            Long deliveredAtMs
    ) {
        public static OutboundSendReceipt ok(String messageId) {
            return new OutboundSendReceipt(true, messageId, null);
        }

        public static OutboundSendReceipt failed() {
            return new OutboundSendReceipt(false, null, null);
        }
    }

    @FunctionalInterface
    public interface OutboundBubbleSender {
        OutboundSendReceipt sendBubble(int bubbleIndex, String text);
    }

    @FunctionalInterface
    public interface BubbleReadinessHook {
        /**
         * Được gọi khi chuẩn bị tới lượt bong bóng {@code bubbleIndex} sẵn sàng nội dung và trả về mốc thời gian sẵn sàng (ms).
         */
        long awaitBubbleContentReadyAtMs(int bubbleIndex, String text);
    }

    @FunctionalInterface
    public interface EpochAwareWaiter {
        boolean awaitOrSupersede(String pageId, String senderPsid, long expectedEpoch, long waitMs);
    }

    public record SentBubbleExecutionLog(
            int bubbleIndex,
            String text,
            boolean sendAccepted,
            String messageId,
            long contentReadyAtMs,
            long waitAppliedMs,
            long sentAtMs,
            boolean canceledBySupersede,
            boolean skippedDuplicate
    ) {
    }

    public record CoordinatedSendSequenceResult(
            int quoteBubbleIndex,
            QuoteBubbleAcceptanceRecord quoteAcceptance,
            List<SentBubbleExecutionLog> bubbleLogs,
            long totalActualWaitMs,
            boolean canceledMidSequence,
            int canceledFromBubbleIndex
    ) {
    }

    /**
     * Tính toán thời điểm gửi hợp lệ cho bong bóng ngay sau bong bóng báo giá theo đúng công thức Mục 6.2:
     * <pre>
     *   T_nextBubbleSend = max(T_nextBubbleReady, T_quoteBubbleAccepted + 10_000ms)
     *   remainingWaitMs  = max(0, 10_000 - max(0, T_nextBubbleReady - T_quoteBubbleAccepted))
     * </pre>
     * Tuyệt đối KHÔNG trừ {@code llmLatencyMs} vào ngân sách đọc 10.000ms.
     */
    public static NextBubbleScheduleDecision computeNextBubbleSchedule(
            QuoteBubbleAcceptanceRecord quoteAcceptance,
            long nextBubbleReadyAtMs
    ) {
        return computeNextBubbleSchedule(quoteAcceptance, nextBubbleReadyAtMs, PRICE_QUOTE_READING_BUDGET_MS);
    }

    public static NextBubbleScheduleDecision computeNextBubbleSchedule(
            QuoteBubbleAcceptanceRecord quoteAcceptance,
            long nextBubbleReadyAtMs,
            long readingBudgetMs
    ) {
        long safeBudgetMs = Math.max(0L, readingBudgetMs);
        if (quoteAcceptance == null || !quoteAcceptance.isReadingBudgetStarted()) {
            return new NextBubbleScheduleDecision(
                    false,
                    -1L,
                    nextBubbleReadyAtMs,
                    nextBubbleReadyAtMs,
                    nextBubbleReadyAtMs,
                    0L,
                    0L
            );
        }
        long acceptedAt = quoteAcceptance.quoteBubbleAcceptedAtMs();
        long effectiveReadyAt = Math.max(acceptedAt, nextBubbleReadyAtMs);
        long earliestAllowed = acceptedAt + safeBudgetMs;
        long scheduledSendAt = Math.max(effectiveReadyAt, earliestAllowed);
        long elapsed = Math.max(0L, effectiveReadyAt - acceptedAt);
        long remainingWait = Math.max(0L, safeBudgetMs - elapsed);
        return new NextBubbleScheduleDecision(
                true,
                acceptedAt,
                nextBubbleReadyAtMs,
                earliestAllowed,
                scheduledSendAt,
                elapsed,
                remainingWait
        );
    }

    /**
     * Xác định xem lượt hội thoại hiện tại có thực sự là lượt <b>báo giá / giới thiệu gói dịch vụ</b> hay không.
     * Tuyệt đối trả về {@code false} ở các lượt khách đang phản đối giá (như {@code S03B} {@code "Bằng cả ngày đi làm"}),
     * bảo vệ giá trị cho mức giá đã báo trước đó, khiếu nại, báo chuyển khoản, hoặc từ chối/tạm dừng.
     */
    public static boolean isActualPriceQuoteIntroductionTurn(
            String originalCustomerMessage,
            List<FanpageBotRequest.ConversationTurn> conversationHistory,
            List<ReplyBubble> replyBubbles,
            List<PackageCatalogService.PackageSummary> allCatalog,
            AdaptiveConversationIntelligence.AdaptiveCognitiveReflection reflection
    ) {
        if (replyBubbles == null || replyBubbles.isEmpty()) {
            return false;
        }
        String normMsg = originalCustomerMessage != null
                ? Normalizer.normalize(originalCustomerMessage.strip(), Normalizer.Form.NFC)
                : "";

        // 1. Chặn ngay các lượt câu ngắn đóng hội thoại / đắn đo / bare ack / khiếu nại / báo thanh toán / tranh chấp lời hứa
        if (NON_QUOTE_CONVERSATIONAL_TURN_PATTERN.matcher(normMsg).matches()
                || AdaptiveConversationIntelligence.isPaymentClaimMessage(normMsg)
                || AdaptiveConversationIntelligence.isStaffPromiseClaimMessage(normMsg)) {
            return false;
        }

        // 2. Kiểm tra phản tư chiến lược từ LLM (nếu có)
        if (reflection != null) {
            if (reflection.strategy().adaptation() == AdaptiveConversationIntelligence.StrategyAdaptationMode.STOP) {
                return false;
            }
            String prio = reflection.priority().primary();
            List<String> secondaries = reflection.priority().secondary();
            boolean hasSecondaryPriceInfo = secondaries.stream()
                    .anyMatch(s -> s != null && (s.toUpperCase(Locale.ROOT).contains("PRICE")
                            || s.toUpperCase(Locale.ROOT).contains("PACKAGE")
                            || s.toUpperCase(Locale.ROOT).contains("BUDGET")));
            if (("POLITE_CLOSURE".equals(prio) || "SAFETY".equals(prio)) && !hasSecondaryPriceInfo) {
                return false;
            }
        }

        boolean explicitPriceOrAlternativeInquiry = EXPLICIT_PRICE_OR_OPTION_INQUIRY_PATTERN.matcher(normMsg).matches();
        boolean priceObjectionOrComparisonTurn = PRICE_OBJECTION_OR_COMPARISON_TURN_PATTERN.matcher(normMsg).matches();

        // 3. Nếu khách đang phản đối giá / ẩn dụ ngày công (VD: S01 "1 ngày công r", S03A, S03B "Bằng cả ngày đi làm", S04 "Bên kia rẻ hơn nhiều")
        // và KHÔNG chủ động hỏi xin báo giá gói khác -> TUYỆT ĐỐI KHÔNG kích hoạt Price Quote Reading Budget 10s!
        if (priceObjectionOrComparisonTurn && !explicitPriceOrAlternativeInquiry) {
            return false;
        }

        // 4. Đối chiếu tập mức giá Catalog có trong câu trả lời hiện tại với tập mức giá bot ĐÃ báo ở các lượt trước
        Set<Long> catalogPricesVnd = collectCatalogPricesVnd(allCatalog);
        Set<Long> quotedPricesInCurrentReply = new LinkedHashSet<>();
        for (ReplyBubble b : replyBubbles) {
            if (b == null || b.text() == null || b.text().isBlank()) continue;
            for (Long p : CleanAgentOutputRefereeAndSanitizer.extractPricesInVndFromText(b.text())) {
                if (catalogPricesVnd.isEmpty() || catalogPricesVnd.contains(p)) {
                    quotedPricesInCurrentReply.add(p);
                }
            }
        }
        if (quotedPricesInCurrentReply.isEmpty()) {
            return false;
        }

        Set<Long> previouslyQuotedByAssistant = extractPricesQuotedByAssistantInHistory(conversationHistory);
        Set<Long> customerAlreadyStatedPrices = CleanAgentOutputRefereeAndSanitizer.extractPricesInVndFromText(normMsg);
        boolean isPureBookingCompletionTurn = reflection != null
                && "BOOKING_COMPLETION".equals(reflection.priority().primary())
                && reflection.priority().secondary().stream().noneMatch(s -> s != null
                        && (s.toUpperCase(Locale.ROOT).contains("PRICE") || s.toUpperCase(Locale.ROOT).contains("BUDGET")));
        boolean introducesNewUnquotedPrice = false;
        for (Long price : quotedPricesInCurrentReply) {
            if (!previouslyQuotedByAssistant.contains(price)
                    && !(isPureBookingCompletionTurn && customerAlreadyStatedPrices.contains(price))) {
                introducesNewUnquotedPrice = true;
                break;
            }
        }

        // Chỉ kích hoạt khi bot thực sự giới thiệu một mức giá gói mới chưa báo trước đó,
        // HOẶC khách chủ động hỏi giá / hỏi gói cụ thể ở lượt này.
        return introducesNewUnquotedPrice || explicitPriceOrAlternativeInquiry;
    }

    /**
     * Tìm chỉ số (0-based) của bong bóng ĐẦU TIÊN trong lượt thực sự chứa mức giá gói dịch vụ và thông tin gói.
     * Nếu {@code bubble 0} chỉ là lời chào/đồng cảm và {@code bubble 1} mới chứa báo giá + tên/thông tin gói,
     * hàm trả về {@code 1} (không bao giờ mặc định cứng {@code 0}).
     *
     * @return chỉ số bong bóng báo giá ({@code >= 0}), hoặc {@code -1} nếu lượt này không phải lượt báo giá.
     */
    public static int findActualPriceQuoteBubbleIndex(
            List<ReplyBubble> replyBubbles,
            List<PackageCatalogService.PackageSummary> allCatalog,
            boolean isPriceQuoteIntroductionTurn
    ) {
        if (!isPriceQuoteIntroductionTurn || replyBubbles == null || replyBubbles.isEmpty()) {
            return -1;
        }
        Set<Long> catalogPrices = collectCatalogPricesVnd(allCatalog);

        // Ưu tiên 1: Bong bóng vừa chứa giá khớp Catalog vừa chứa tín hiệu tên gói / thời lượng / dịch vụ
        for (int i = 0; i < replyBubbles.size(); i++) {
            ReplyBubble b = replyBubbles.get(i);
            if (b == null || b.text() == null || b.text().isBlank()) {
                continue;
            }
            String text = Normalizer.normalize(b.text().strip(), Normalizer.Form.NFC);
            Set<Long> bubblePrices = CleanAgentOutputRefereeAndSanitizer.extractPricesInVndFromText(text);
            if (bubblePrices.isEmpty()) {
                continue;
            }
            boolean matchesCatalogPrice = catalogPrices.isEmpty()
                    || bubblePrices.stream().anyMatch(catalogPrices::contains);
            if (!matchesCatalogPrice) {
                continue;
            }
            if (containsPackageOrServiceIdentity(text, allCatalog)) {
                return i;
            }
        }

        // Ưu tiên 2: Nếu tên gói nằm ở bong bóng liền trước và bong bóng i nêu giá khớp Catalog
        for (int i = 0; i < replyBubbles.size(); i++) {
            ReplyBubble b = replyBubbles.get(i);
            if (b == null || b.text() == null || b.text().isBlank()) {
                continue;
            }
            Set<Long> bubblePrices = CleanAgentOutputRefereeAndSanitizer.extractPricesInVndFromText(b.text());
            if (!bubblePrices.isEmpty()
                    && (catalogPrices.isEmpty() || bubblePrices.stream().anyMatch(catalogPrices::contains))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Gắn {@code typingDelayMs} cho danh sách bong bóng trả về API/Simulator theo đúng chỉ số bong bóng báo giá:
     * <ul>
     *   <li>{@code i == 0}: {@code 0ms}</li>
     *   <li>{@code i == quoteBubbleIndex + 1} (khi {@code quoteBubbleIndex >= 0}): {@code 10_000ms}</li>
     *   <li>Các bong bóng khác {@code i >= 1}: {@code normalInterBubbleDelayMs} ({@code 2_000ms})</li>
     * </ul>
     */
    public static List<ReplyBubble> applyReadingBudgetTypingDelays(
            List<ReplyBubble> bubbles,
            int quoteBubbleIndex,
            int readingBudgetMs,
            int normalInterBubbleDelayMs
    ) {
        if (bubbles == null || bubbles.isEmpty()) {
            return List.of();
        }
        int safeBudget = Math.max(0, readingBudgetMs);
        int safeNormal = Math.max(0, normalInterBubbleDelayMs);
        List<ReplyBubble> out = new ArrayList<>(bubbles.size());
        for (int i = 0; i < bubbles.size(); i++) {
            ReplyBubble b = bubbles.get(i);
            if (b == null || b.text() == null || b.text().isBlank()) {
                continue;
            }
            int outIdx = out.size();
            int delay;
            if (outIdx == 0) {
                delay = 0;
            } else if (quoteBubbleIndex >= 0 && outIdx == quoteBubbleIndex + 1) {
                delay = safeBudget;
            } else {
                delay = safeNormal;
            }
            out.add(new ReplyBubble(b.text().strip(), delay));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Thực thi chuỗi gửi bong bóng có điều phối mốc {@code quoteBubbleAcceptedAt}, ngân sách đọc 10s,
     * kiểm tra hủy giữa chừng qua {@link SenderEpochRegistry} và chống gửi trùng.
     */
    public static CoordinatedSendSequenceResult executeCoordinatedSendSequence(
            String pageId,
            String senderPsid,
            String conversationId,
            String turnId,
            long expectedEpoch,
            long llmLatencyMs,
            List<ReplyBubble> bubbles,
            int quoteBubbleIndex,
            OutboundBubbleSender outboundSender,
            BubbleReadinessHook readinessHook,
            LongSupplier clockMs,
            EpochAwareWaiter epochWaiter
    ) {
        Objects.requireNonNull(outboundSender, "outboundSender must not be null");
        LongSupplier safeClock = clockMs != null ? clockMs : System::currentTimeMillis;
        BubbleReadinessHook safeReadiness = readinessHook != null
                ? readinessHook
                : (idx, txt) -> safeClock.getAsLong();
        EpochAwareWaiter safeWaiter = epochWaiter != null
                ? epochWaiter
                : SenderEpochRegistry::awaitBubbleDelayOrSupersede;

        if (bubbles == null || bubbles.isEmpty()) {
            return new CoordinatedSendSequenceResult(
                    -1,
                    QuoteBubbleAcceptanceRecord.notApplicable(llmLatencyMs),
                    List.of(),
                    0L,
                    false,
                    -1
            );
        }

        String ledgerKey = SentBubbleLedger.key(pageId, senderPsid, conversationId);
        QuoteBubbleAcceptanceRecord quoteAcceptance = QuoteBubbleAcceptanceRecord.notApplicable(llmLatencyMs);
        List<SentBubbleExecutionLog> logs = new ArrayList<>(bubbles.size());
        long totalWaitMs = 0L;
        boolean canceledMidSequence = false;
        int canceledFromIdx = -1;

        if (expectedEpoch > 0L) {
            SenderEpochRegistry.transitionToSendingBubbles(pageId, senderPsid, expectedEpoch);
        }

        for (int i = 0; i < bubbles.size(); i++) {
            ReplyBubble bubble = bubbles.get(i);
            if (bubble == null || bubble.text() == null || bubble.text().isBlank()) {
                continue;
            }
            String text = bubble.text().strip();

            // 1. Kiểm tra epoch trước khi chờ / chuẩn bị gửi bong bóng i
            if (!SenderEpochRegistry.checkEpochStillCurrent(
                    pageId, senderPsid, turnId, expectedEpoch,
                    SenderEpochRegistry.Checkpoint.BEFORE_OUTBOUND_BUBBLE_SEND)) {
                canceledMidSequence = true;
                canceledFromIdx = i;
                SentBubbleLedger.markLatestAssistantBubblesCanceledFrom(ledgerKey, i);
                logs.add(new SentBubbleExecutionLog(i, text, false, null, safeClock.getAsLong(), 0L, -1L, true, false));
                break;
            }

            // 2. Xác định thời điểm nội dung bong bóng i sẵn sàng
            long readyAtMs = safeReadiness.awaitBubbleContentReadyAtMs(i, text);

            // 3. Tính thời gian cần chờ trước khi gửi bong bóng i
            long waitMs = 0L;
            if (i > 0) {
                if (quoteBubbleIndex >= 0 && i == quoteBubbleIndex + 1) {
                    // Bong bóng ngay sau bong bóng báo giá:
                    // Nếu bong bóng báo giá được Messenger chấp nhận gửi -> áp dụng công thức T_next = max(T_ready, T_accepted + 10_000ms).
                    // Nếu bong bóng báo giá gửi THẤT BẠI -> KHÔNG kích hoạt ngân sách đọc 10s!
                    if (quoteAcceptance.isReadingBudgetStarted()) {
                        NextBubbleScheduleDecision schedule = computeNextBubbleSchedule(
                                quoteAcceptance, readyAtMs, PRICE_QUOTE_READING_BUDGET_MS);
                        waitMs = schedule.remainingWaitMs();
                    } else {
                        waitMs = 0L;
                    }
                } else {
                    int configuredDelay = Math.max(0, bubble.typingDelayMs());
                    waitMs = Math.min(configuredDelay, DEFAULT_INTER_BUBBLE_TYPING_DELAY_MS);
                }
            }

            if (waitMs > 0L) {
                boolean stillCurrent = safeWaiter.awaitOrSupersede(pageId, senderPsid, expectedEpoch, waitMs);
                if (!stillCurrent || !SenderEpochRegistry.isEpochCurrent(pageId, senderPsid, expectedEpoch)) {
                    canceledMidSequence = true;
                    canceledFromIdx = i;
                    SentBubbleLedger.markLatestAssistantBubblesCanceledFrom(ledgerKey, i);
                    logs.add(new SentBubbleExecutionLog(i, text, false, null, readyAtMs, waitMs, -1L, true, false));
                    break;
                }
                totalWaitMs += waitMs;
            }

            // 4. Chống gửi trùng bong bóng cho cùng (pageId, senderPsid, epoch, turnId, bubbleIndex)
            String dedupeKey = buildDedupeKey(pageId, senderPsid, expectedEpoch, turnId, i);
            if (!registerUniqueBubbleSend(dedupeKey)) {
                log.warn("[ACI-ReadingBudget] Prevented duplicate bubble send for key={}", dedupeKey);
                logs.add(new SentBubbleExecutionLog(i, text, false, null, readyAtMs, waitMs, -1L, false, true));
                continue;
            }

            // 5. Thực hiện gửi bong bóng ra kênh Messenger
            OutboundSendReceipt receipt = outboundSender.sendBubble(i, text);
            long afterSendClockMs = safeClock.getAsLong();
            boolean accepted = receipt != null && receipt.accepted();
            String mid = receipt != null ? receipt.messageId() : null;

            if (accepted && mid != null && !mid.isBlank()) {
                SentBubbleLedger.recordMessageId(pageId, senderPsid, mid, "assistant", text);
            }

            // 6. Nếu đây là bong bóng báo giá thực sự -> chỉ khởi động ngân sách đọc 10s khi sendAccepted == true!
            if (i == quoteBubbleIndex) {
                if (accepted) {
                    quoteAcceptance = QuoteBubbleAcceptanceRecord.accepted(
                            i, mid, llmLatencyMs, afterSendClockMs);
                    if (receipt.deliveredAtMs() != null) {
                        quoteAcceptance = quoteAcceptance.withDeliveredAt(receipt.deliveredAtMs());
                    }
                } else {
                    quoteAcceptance = QuoteBubbleAcceptanceRecord.sendFailed(i, llmLatencyMs);
                }
            }

            logs.add(new SentBubbleExecutionLog(
                    i,
                    text,
                    accepted,
                    mid,
                    readyAtMs,
                    waitMs,
                    accepted ? afterSendClockMs : -1L,
                    false,
                    false
            ));
        }

        return new CoordinatedSendSequenceResult(
                quoteBubbleIndex,
                quoteAcceptance,
                Collections.unmodifiableList(logs),
                totalWaitMs,
                canceledMidSequence,
                canceledFromIdx
        );
    }

    public static void clearDedupeRegistryForTesting() {
        SENT_BUBBLE_DEDUPE_KEYS.clear();
    }

    private static String buildDedupeKey(String pageId, String senderPsid, long epoch, String turnId, int bubbleIdx) {
        return (pageId != null ? pageId : "-")
                + "|" + (senderPsid != null ? senderPsid : "-")
                + "|ep=" + epoch
                + "|turn=" + (turnId != null ? turnId : "-")
                + "|b=" + bubbleIdx;
    }

    private static boolean registerUniqueBubbleSend(String dedupeKey) {
        if (SENT_BUBBLE_DEDUPE_KEYS.size() >= MAX_DEDUPE_ENTRIES) {
            SENT_BUBBLE_DEDUPE_KEYS.clear();
        }
        return SENT_BUBBLE_DEDUPE_KEYS.add(dedupeKey);
    }

    private static boolean containsPackageOrServiceIdentity(
            String bubbleText,
            List<PackageCatalogService.PackageSummary> allCatalog
    ) {
        if (PACKAGE_SERVICE_SIGNAL_IN_BUBBLE_PATTERN.matcher(bubbleText).matches()) {
            return true;
        }
        if (allCatalog != null) {
            String low = bubbleText.toLowerCase(Locale.ROOT);
            for (PackageCatalogService.PackageSummary p : allCatalog) {
                if (p == null) continue;
                String disp = p.displayName() != null ? p.displayName().strip().toLowerCase(Locale.ROOT) : "";
                String canon = p.canonicalName() != null ? p.canonicalName().strip().toLowerCase(Locale.ROOT) : "";
                if ((!disp.isEmpty() && low.contains(disp)) || (!canon.isEmpty() && low.contains(canon))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Set<Long> collectCatalogPricesVnd(List<PackageCatalogService.PackageSummary> allCatalog) {
        if (allCatalog == null || allCatalog.isEmpty()) {
            return Set.of();
        }
        Set<Long> out = new LinkedHashSet<>();
        for (PackageCatalogService.PackageSummary p : allCatalog) {
            if (p != null && p.priceAmountVnd() > 0L) {
                out.add(p.priceAmountVnd());
            }
        }
        return out;
    }

    private static Set<Long> extractPricesQuotedByAssistantInHistory(
            List<FanpageBotRequest.ConversationTurn> history
    ) {
        if (history == null || history.isEmpty()) {
            return Set.of();
        }
        Set<Long> out = new LinkedHashSet<>();
        for (FanpageBotRequest.ConversationTurn t : history) {
            if (t == null || t.role() == null || t.content() == null || t.content().isBlank()) {
                continue;
            }
            String role = t.role().strip().toLowerCase(Locale.ROOT);
            if ("assistant".equals(role) || "bot".equals(role)) {
                out.addAll(CleanAgentOutputRefereeAndSanitizer.extractPricesInVndFromText(t.content()));
            }
        }
        return out;
    }
}
