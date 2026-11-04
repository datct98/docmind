package com.example.docmind.service.bot;

import com.example.docmind.dto.bot.FanpageBotRequest.ConversationTurn;
import com.example.docmind.dto.bot.FanpageBotResponse.ReplyBubble;
import com.example.docmind.service.retrieval.ReciprocalRankFusion;
import com.example.docmind.service.text.VietnameseQueryNormalizer;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Máy trạng thái hội thoại bán hàng đa ngành (Domain-Agnostic Conversation State v3.1.5.3).
 * <p>
 * Hỗ trợ chiến thuật xử lý chê đắt 2 bước của chuyên viên sale lâu năm:
 * <ul>
 *   <li>Chê đắt lần 1 (bình thường): Bảo vệ giá trị + mời thử 1 buổi 🥰, giữ nguyên {@code heldPackage}/{@code heldDuration},
 *       tuyệt đối KHÔNG đưa gói đang giữ vào {@code rejectedPackages}/{@code rejectedDurations}.</li>
 *   <li>Chê đắt lần 2 hoặc khách chủ động xin gói rẻ/ngắn hơn: Hạ đúng 1 bậc xuống gói thấp hơn còn hiệu lực trong tài liệu,
 *       đưa gói cũ vào {@code rejectedPackages}/{@code rejectedDurations}.</li>
 *   <li>Khách đồng ý thử ("Thử xem sao"): Chốt đúng {@code heldPackage} (nếu chưa downsell) hoặc {@code downsellPackage} (nếu đã downsell).</li>
 * </ul>
 */
public record ConversationState(
        boolean greetingSent,
        boolean introSent,
        ConversationStage stage,
        String lastIntent,
        String lastQuestion,
        String lastQuestionIntent,
        Set<String> usedQuestionIntents,
        String lastBotAction,
        KnownFacts known,
        boolean priceDiscussed,
        List<String> pricesShown,
        List<String> recommendedOptions,
        int objectionCount,
        int ackCount,
        int clarificationCount,
        int conditionAskedCount,
        int preferenceAskedCount,
        int clarifyServiceTypeCount,
        int clarifyDurationCount,
        int frustrationCount,
        int ambiguousCount,
        int recommendationCount,
        int priceRepeatGuardCount,
        boolean conditionClarified,
        boolean serviceFamilyKnown,
        boolean packageRecommended,
        boolean priceRequestedInSession,
        List<String> knownSymptoms,
        List<String> knownBodyAreas,
        String knownSeverity,
        String knownDurationOfProblem,
        String knownTrigger,
        String knownServiceFamily,
        String knownPreferredPressure,
        String knownRecommendedPackage,
        String lastQuotePackage,
        String lastSentiment,
        String lastEmotion,
        List<String> usedTemplates,
        boolean humanTakeover,
        boolean optedOut,
        // === v3.1.5.3 Veteran Sales Two-Step Objection & Safety Fields ===
        boolean downsellOffered,
        boolean trialInviteOffered,
        boolean explicitDownsellRequest,
        boolean packageRejectionDetected,
        String lastQuotedPackage,
        String lastQuotedDuration,
        BigDecimal lastQuotedPrice,
        String heldPackage,
        String heldDuration,
        String downsellPackage,
        String downsellDuration,
        BigDecimal downsellPrice,
        Set<String> rejectedDurations,
        Set<String> rejectedPackages,
        String objectionStage,
        String telemetrySubtype,
        boolean docsHasFeedback,
        boolean docsHasRelaxationBenefit,
        boolean docsHasPrivateRoom,
        boolean neurologicalRisk,
        // === v3.2.1-FINAL LLM-Native Guarded Runtime & Real HTML Catalog State Fields ===
        String preferredRegion,
        List<String> mentionedPackages,
        List<String> quotedPackages,
        BigDecimal heldPrice,
        int alternativeRequestCount,
        boolean customerCorrectionDetected,
        String rejectedWrongOffer,
        List<PackageCatalogService.PackageSummary> availablePackageCatalog,
        List<String> lastBotBubbleHashes,
        List<UnresolvedCustomerRequest> outstandingRequests,
        String lastAssistantMessage,
        // === v3.6.7.1-MASTER Scope Lifecycle, ApologyLedger & Auto-Note Artifact Fields ===
        Set<String> confirmedScopes,
        Set<String> forbiddenScopes,
        Set<String> apologizedErrorIds,
        String activeErrorId,
        List<CustomerCareNoteArtifact> customerCareNotes,
        List<String> scopeSupersessionEvents,
        boolean possibleCorrection,
        // === v3.7 Independent lifecycle flags: bot-offered package vs customer-selected package ===
        boolean packageExplicitlySelected,
        String selectedPackageId,
        // === v3.8 Semantic slots tách biệt: tần suất / phân bổ triệu chứng / câu hỏi đang chờ khách trả lời ===
        String knownFrequency,
        String knownSymptomDistribution,
        String pendingQuestionSlot,
        // === v3.9 Episode duration & Funnel state separation (packageInterestAccepted vs bookingStatus) ===
        String knownEpisodeDuration,
        boolean packageInterestAccepted,
        boolean bookingIntentConfirmed,
        String visitTime,
        String contactInfo,
        String bookingStatus,
        // === Checkpoint C3-B: 6-State Multi-Industry Booking / Ordering State Machine ===
        String selectedProductId,
        String bookingDate,
        String bookingTime,
        BookingStatus bookingStatusEnum
) {

    /**
     * 6 trạng thái Đặt lịch / Đặt hàng đa ngành tường minh (Checkpoint C3-B):
     * 1. NONE: Chưa có ý định đặt lịch/đặt hàng.
     * 2. AWAITING_PRODUCT_SELECTION: Khách muốn đặt nhưng chưa chọn rõ sản phẩm (khi lịch sử có >= 2 sản phẩm hoặc chưa rõ sản phẩm).
     * 3. AWAITING_DATE_OR_TIME: Đã xác định rõ selectedProductId, còn thiếu ngày hoặc khung giờ cụ thể.
     * 4. AWAITING_AVAILABILITY_CHECK: Đã thu thập đủ selectedProductId, bookingDate, bookingTime; đang chờ kiểm tra lịch trống / tồn kho thực tế.
     * 5. AWAITING_CUSTOMER_CONFIRMATION: Đã kiểm tra có lịch trống phù hợp và đang chờ khách chốt xác nhận cuối cùng.
     * 6. CONFIRMED: Chỉ chuyển sang trạng thái này khi có kết quả xác nhận thực tế từ công cụ đặt lịch hoặc nhân sự phụ trách.
     */
    public enum BookingStatus {
        NONE,
        AWAITING_PRODUCT_SELECTION,
        AWAITING_DATE_OR_TIME,
        AWAITING_AVAILABILITY_CHECK,
        AWAITING_CUSTOMER_CONFIRMATION,
        CONFIRMED;

        public boolean isActiveOrConfirmed() {
            return this != NONE;
        }

        public static boolean isSixStateCanonical(String raw) {
            if (raw == null || raw.isBlank()) {
                return false;
            }
            String u = raw.strip().toUpperCase(Locale.ROOT);
            for (BookingStatus bs : values()) {
                if (bs.name().equals(u)) {
                    return true;
                }
            }
            return false;
        }

        public static BookingStatus fromLegacyString(String raw, String selectedProductId, String visitTime) {
            if (raw == null || raw.isBlank()) {
                return NONE;
            }
            String u = raw.strip().toUpperCase(Locale.ROOT);
            for (BookingStatus bs : values()) {
                if (bs.name().equals(u)) {
                    return bs;
                }
            }
            if ("COLLECTING_INFO".equals(u) || "COLLECTING_VISIT_TIME".equals(u)) {
                return (selectedProductId != null && !selectedProductId.isBlank())
                        ? AWAITING_DATE_OR_TIME
                        : AWAITING_PRODUCT_SELECTION;
            }
            return NONE;
        }
    }

    /**
     * Trạng thái Đặt lịch / Đặt hàng cấu trúc đa ngành (Checkpoint C3-B).
     */
    public record StructuredBookingState(
            String selectedProductId,
            String selectedProductLabel,
            String bookingDate,
            String resolvedIsoDate,
            String bookingTime,
            String timezoneId,
            BookingStatus status,
            List<String> candidateProductIds
    ) {
        public static StructuredBookingState none() {
            return new StructuredBookingState(
                    null, null, null, null, null, "Asia/Ho_Chi_Minh", BookingStatus.NONE, List.of()
            );
        }
    }

    public record ParsedBookingDateTime(
            String bookingDate,
            String resolvedIsoDate,
            String bookingTime,
            String timezoneId
    ) {}

    private static final Pattern RELATIVE_BOOKING_DATE_PATTERN = Pattern.compile(
            "(?iU)\\b("
            + "(?:sáng|trưa|chiều|tối)\\s+(?:hôm\\s+nay|nay|ngày\\s+mai|mai|ngày\\s+kia|ngày\\s+mốt|thứ\\s+[2-7]|thứ\\s+(?:hai|ba|tư|năm|sáu|bảy)|chủ\\s+nhật)|"
            + "hôm\\s+nay|bữa\\s+nay|ngày\\s+mai|ngày\\s+kia|ngày\\s+mốt|"
            + "cuối\\s+tuần(?:\\s+này|\\s+sau)?|"
            + "thứ\\s+[2-7](?:\\s+tuần\\s+này|\\s+tuần\\s+sau)?|"
            + "thứ\\s+(?:hai|ba|tư|năm|sáu|bảy)(?:\\s+tuần\\s+này|\\s+tuần\\s+sau)?|"
            + "chủ\\s+nhật(?:\\s+tuần\\s+này|\\s+tuần\\s+sau)?|"
            + "(?:ngày\\s+)?\\d{1,2}[/-]\\d{1,2}(?:[/-]\\d{2,4})?"
            + ")\\b"
    );

    private static final Pattern EXPLICIT_CLOCK_TIME_PATTERN = Pattern.compile(
            "(?iU)(?:\\b(?:lúc|vào|tầm|khoảng|khung(?:\\s+giờ)?)\\s+)?"
            + "\\b([01]?\\d|2[0-3])\\s*(?:giờ|(?:g|h)(?=\\d|\\b)|:)\\s*([0-5]?\\d|rưỡi)?(?:\\s*(sáng|trưa|chiều|tối|đêm|am|pm))?(?=\\s|$|[.,!?;:)])"
    );

    /**
     * Phân tích ngày/giờ đặt lịch tương đối & tuyệt đối theo múi giờ của Page/cơ sở (mặc định Asia/Ho_Chi_Minh).
     * Xử lý tổ hợp như "tối nay" + "lúc 7 giờ" -> bookingDate="tối nay", bookingTime="19:00".
     */
    public static ParsedBookingDateTime parseBookingDateTime(
            String customerMessage,
            String priorBookingDate,
            String priorBookingTime,
            java.time.ZoneId pageZone,
            java.time.LocalDate referenceDate
    ) {
        java.time.ZoneId safeZone = pageZone != null ? pageZone : java.time.ZoneId.of("Asia/Ho_Chi_Minh");
        java.time.LocalDate refDate = referenceDate != null ? referenceDate : java.time.LocalDate.now(safeZone);
        String msg = customerMessage != null ? Normalizer.normalize(customerMessage.strip(), Normalizer.Form.NFC) : "";
        String lower = msg.toLowerCase(Locale.ROOT);

        String extractedDate = null;
        if (!lower.isEmpty()) {
            Matcher dm = RELATIVE_BOOKING_DATE_PATTERN.matcher(msg);
            if (dm.find()) {
                extractedDate = dm.group(1).replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
            } else if (lower.matches("(?isU).*\\b(?:đặt|giữ|hẹn|qua|ghé|sang|lấy|giao)\\s+mai\\b.*")
                    || lower.matches("(?isU)^\\s*mai\\s+(?:lúc|tầm|khoảng|mình|chị|anh|\\d{1,2}\\s*(?:h|giờ)).*")) {
                extractedDate = "ngày mai";
            }
        }

        String effectiveDate = (extractedDate != null && !extractedDate.isBlank())
                ? extractedDate
                : (priorBookingDate != null && !priorBookingDate.isBlank() ? priorBookingDate.strip() : null);

        String extractedTime = null;
        if (!lower.isEmpty()) {
            Matcher tm = EXPLICIT_CLOCK_TIME_PATTERN.matcher(lower);
            while (tm.find()) {
                int matchStart = tm.start();
                String prefixWindow = lower.substring(Math.max(0, matchStart - 16), matchStart);
                // Tránh bắt nhầm câu hỏi giờ mở/đóng cửa hoặc thời lượng liệu trình "1 tiếng / 2 giờ massage"
                if (prefixWindow.contains("mở cửa") || prefixWindow.contains("đóng cửa") || prefixWindow.contains("kéo dài")) {
                    continue;
                }
                int rawHour = Integer.parseInt(tm.group(1));
                String minToken = tm.group(2);
                String meridiem = tm.group(3) != null ? tm.group(3).strip().toLowerCase(Locale.ROOT) : "";
                int minute = 0;
                if (minToken != null && !minToken.isBlank()) {
                    if ("rưỡi".equalsIgnoreCase(minToken.strip())) {
                        minute = 30;
                    } else {
                        minute = Integer.parseInt(minToken.strip());
                    }
                }
                String dateHint = effectiveDate != null ? effectiveDate.toLowerCase(Locale.ROOT) : "";
                int hour24 = rawHour;
                boolean eveningOrAfternoon = meridiem.equals("tối") || meridiem.equals("chiều") || meridiem.equals("đêm") || meridiem.equals("pm")
                        || (meridiem.isEmpty() && (dateHint.contains("tối") || dateHint.contains("chiều")));
                boolean morningHint = meridiem.equals("sáng") || meridiem.equals("am")
                        || (meridiem.isEmpty() && dateHint.contains("sáng"));
                boolean noonHint = meridiem.equals("trưa") || (meridiem.isEmpty() && dateHint.contains("trưa"));
                if (eveningOrAfternoon && hour24 >= 1 && hour24 <= 11) {
                    hour24 += 12;
                } else if (noonHint && hour24 >= 1 && hour24 <= 2) {
                    hour24 += 12;
                } else if (morningHint && hour24 == 12) {
                    hour24 = 0;
                } else if (meridiem.isEmpty() && dateHint.isEmpty() && hour24 >= 1 && hour24 <= 6) {
                    // Khung giờ hành chính/dịch vụ buổi chiều mặc định khi khách nói "lúc 3 giờ" mà không kèm sáng/tối
                    hour24 += 12;
                }
                extractedTime = String.format(Locale.ROOT, "%02d:%02d", hour24, minute);
                break;
            }
        }

        boolean dateExplicitlyChangedWithoutNewTime = extractedDate != null
                && priorBookingDate != null
                && !extractedDate.equalsIgnoreCase(priorBookingDate)
                && extractedTime == null
                && lower.matches("(?isU).*\\b(đổi|chuyển|dời)\\b.*");
        String effectiveTime = (extractedTime != null && !extractedTime.isBlank())
                ? extractedTime
                : (!dateExplicitlyChangedWithoutNewTime && priorBookingTime != null && !priorBookingTime.isBlank()
                    ? priorBookingTime.strip()
                    : null);

        String resolvedIso = resolveRelativeDateToIsoString(effectiveDate, refDate);
        return new ParsedBookingDateTime(effectiveDate, resolvedIso, effectiveTime, safeZone.getId());
    }

    private static String resolveRelativeDateToIsoString(String datePhrase, java.time.LocalDate refDate) {
        if (datePhrase == null || datePhrase.isBlank() || refDate == null) {
            return null;
        }
        String d = datePhrase.toLowerCase(Locale.ROOT).strip();
        if (d.contains("hôm nay") || d.contains("bữa nay") || d.endsWith(" nay")) {
            return refDate.toString();
        }
        if (d.contains("ngày mai") || d.endsWith(" mai") || d.equals("mai")) {
            return refDate.plusDays(1).toString();
        }
        if (d.contains("ngày kia") || d.contains("ngày mốt")) {
            return refDate.plusDays(2).toString();
        }
        if (d.contains("cuối tuần")) {
            java.time.LocalDate sat = refDate.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.SATURDAY));
            return (d.contains("sau") ? sat.plusWeeks(1) : sat).toString();
        }
        java.time.DayOfWeek dow = null;
        if (d.contains("thứ 2") || d.contains("thứ hai")) dow = java.time.DayOfWeek.MONDAY;
        else if (d.contains("thứ 3") || d.contains("thứ ba")) dow = java.time.DayOfWeek.TUESDAY;
        else if (d.contains("thứ 4") || d.contains("thứ tư")) dow = java.time.DayOfWeek.WEDNESDAY;
        else if (d.contains("thứ 5") || d.contains("thứ năm")) dow = java.time.DayOfWeek.THURSDAY;
        else if (d.contains("thứ 6") || d.contains("thứ sáu")) dow = java.time.DayOfWeek.FRIDAY;
        else if (d.contains("thứ 7") || d.contains("thứ bảy")) dow = java.time.DayOfWeek.SATURDAY;
        else if (d.contains("chủ nhật")) dow = java.time.DayOfWeek.SUNDAY;
        if (dow != null) {
            java.time.LocalDate target = refDate.with(java.time.temporal.TemporalAdjusters.nextOrSame(dow));
            if (d.contains("tuần sau")) {
                target = target.plusWeeks(1);
            }
            return target.toString();
        }
        Matcher calM = Pattern.compile("(\\d{1,2})[/-](\\d{1,2})(?:[/-](\\d{2,4}))?").matcher(d);
        if (calM.find()) {
            try {
                int day = Integer.parseInt(calM.group(1));
                int month = Integer.parseInt(calM.group(2));
                int year = calM.group(3) != null
                        ? (calM.group(3).length() == 2 ? 2000 + Integer.parseInt(calM.group(3)) : Integer.parseInt(calM.group(3)))
                        : refDate.getYear();
                return java.time.LocalDate.of(year, month, day).toString();
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    public StructuredBookingState structuredBookingState() {
        BookingStatus resolvedEnum = bookingStatusEnum != null
                ? bookingStatusEnum
                : BookingStatus.fromLegacyString(bookingStatus, selectedProductId, visitTime);
        return new StructuredBookingState(
                selectedProductId,
                null,
                bookingDate,
                resolveRelativeDateToIsoString(bookingDate, java.time.LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))),
                bookingTime,
                "Asia/Ho_Chi_Minh",
                resolvedEnum,
                List.of()
        );
    }

    public ConversationState withStructuredBookingState(StructuredBookingState bs) {
        if (bs == null) {
            return this;
        }
        BookingStatus st = bs.status() != null ? bs.status() : BookingStatus.NONE;
        String combinedVisitTime = (bs.bookingDate() != null && bs.bookingTime() != null)
                ? (bs.bookingDate() + " " + bs.bookingTime())
                : (bs.bookingDate() != null ? bs.bookingDate() : (bs.bookingTime() != null ? bs.bookingTime() : this.visitTime));
        boolean hasSelectedProd = bs.selectedProductId() != null && !bs.selectedProductId().isBlank();
        return new ConversationState(
                greetingSent, introSent, stage, lastIntent, lastQuestion, lastQuestionIntent,
                usedQuestionIntents, lastBotAction, known, priceDiscussed, pricesShown, recommendedOptions,
                objectionCount, ackCount, clarificationCount, conditionAskedCount, preferenceAskedCount,
                clarifyServiceTypeCount, clarifyDurationCount, frustrationCount, ambiguousCount,
                recommendationCount, priceRepeatGuardCount, conditionClarified, serviceFamilyKnown,
                packageRecommended, priceRequestedInSession, knownSymptoms, knownBodyAreas,
                knownSeverity, knownDurationOfProblem, knownTrigger, knownServiceFamily,
                knownPreferredPressure, knownRecommendedPackage, lastQuotePackage, lastSentiment,
                lastEmotion, usedTemplates, humanTakeover, optedOut,
                downsellOffered, trialInviteOffered, explicitDownsellRequest, packageRejectionDetected,
                lastQuotedPackage, lastQuotedDuration, lastQuotedPrice, heldPackage, heldDuration,
                downsellPackage, downsellDuration, downsellPrice, rejectedDurations, rejectedPackages,
                objectionStage, telemetrySubtype, docsHasFeedback, docsHasRelaxationBenefit, docsHasPrivateRoom, neurologicalRisk,
                preferredRegion, mentionedPackages, quotedPackages, heldPrice, alternativeRequestCount,
                customerCorrectionDetected, rejectedWrongOffer, availablePackageCatalog, lastBotBubbleHashes,
                outstandingRequests, lastAssistantMessage,
                confirmedScopes, forbiddenScopes, apologizedErrorIds, activeErrorId,
                customerCareNotes, scopeSupersessionEvents, possibleCorrection,
                hasSelectedProd, hasSelectedProd ? bs.selectedProductId() : null,
                knownFrequency, knownSymptomDistribution, pendingQuestionSlot,
                knownEpisodeDuration,
                st.isActiveOrConfirmed() ? true : this.packageInterestAccepted,
                st == BookingStatus.CONFIRMED,
                combinedVisitTime,
                contactInfo,
                st.name(),
                bs.selectedProductId(),
                bs.bookingDate(),
                bs.bookingTime(),
                st
        );
    }

    public static ConversationState updateStructuredBookingStateInSession(
            UUID pageId,
            String senderId,
            String conversationId,
            StructuredBookingState bs
    ) {
        if (bs == null) {
            return null;
        }
        String sessionKey = buildSessionKey(pageId, senderId, conversationId);
        if (sessionKey == null) {
            return null;
        }
        ConversationState current = SESSION_STORE.get(sessionKey);
        if (current == null) {
            current = initial();
        }
        ConversationState updated = current.withStructuredBookingState(bs);
        SESSION_STORE.put(sessionKey, updated);
        LastTurnPreStateSnapshot snap = LAST_TURN_PRE_STATE_STORE.get(sessionKey);
        if (snap != null) {
            LAST_TURN_PRE_STATE_STORE.put(sessionKey, new LastTurnPreStateSnapshot(
                    snap.historySize(),
                    snap.normalizedUserMessage(),
                    snap.preTurnState(),
                    snap.enrichedTurnState(),
                    updated
            ));
        }
        return updated;
    }

    public ConversationState withOptedOut(boolean nextOptedOut) {
        if (this.optedOut == nextOptedOut) {
            return this;
        }
        return new ConversationState(
                greetingSent, introSent, stage, lastIntent, lastQuestion, lastQuestionIntent,
                usedQuestionIntents, lastBotAction, known, priceDiscussed, pricesShown, recommendedOptions,
                objectionCount, ackCount, clarificationCount, conditionAskedCount, preferenceAskedCount,
                clarifyServiceTypeCount, clarifyDurationCount, frustrationCount, ambiguousCount,
                recommendationCount, priceRepeatGuardCount, conditionClarified, serviceFamilyKnown,
                packageRecommended, priceRequestedInSession, knownSymptoms, knownBodyAreas,
                knownSeverity, knownDurationOfProblem, knownTrigger, knownServiceFamily,
                knownPreferredPressure, knownRecommendedPackage, lastQuotePackage, lastSentiment,
                lastEmotion, usedTemplates, humanTakeover, nextOptedOut,
                downsellOffered, trialInviteOffered, explicitDownsellRequest, packageRejectionDetected,
                lastQuotedPackage, lastQuotedDuration, lastQuotedPrice, heldPackage, heldDuration,
                downsellPackage, downsellDuration, downsellPrice, rejectedDurations, rejectedPackages,
                objectionStage, telemetrySubtype, docsHasFeedback, docsHasRelaxationBenefit, docsHasPrivateRoom, neurologicalRisk,
                preferredRegion, mentionedPackages, quotedPackages, heldPrice, alternativeRequestCount,
                customerCorrectionDetected, rejectedWrongOffer, availablePackageCatalog, lastBotBubbleHashes,
                outstandingRequests, lastAssistantMessage,
                confirmedScopes, forbiddenScopes, apologizedErrorIds, activeErrorId,
                customerCareNotes, scopeSupersessionEvents, possibleCorrection,
                packageExplicitlySelected, selectedPackageId,
                knownFrequency, knownSymptomDistribution, pendingQuestionSlot,
                knownEpisodeDuration, packageInterestAccepted, bookingIntentConfirmed, visitTime, contactInfo,
                bookingStatus, selectedProductId, bookingDate, bookingTime, bookingStatusEnum
        );
    }

    public static void syncOptedOutInSession(
            UUID pageId,
            String senderId,
            String conversationId,
            boolean nextOptedOut
    ) {
        for (String convIdCandidate : java.util.Arrays.asList(conversationId, null)) {
            String sessionKey = buildSessionKey(pageId, senderId, convIdCandidate);
            if (sessionKey == null) continue;
            ConversationState current = SESSION_STORE.get(sessionKey);
            if (current == null && !nextOptedOut) {
                continue;
            }
            ConversationState base = current != null ? current : initial();
            ConversationState updated = base.withOptedOut(nextOptedOut);
            SESSION_STORE.put(sessionKey, updated);
            LastTurnPreStateSnapshot snap = LAST_TURN_PRE_STATE_STORE.get(sessionKey);
            if (snap != null) {
                LAST_TURN_PRE_STATE_STORE.put(sessionKey, new LastTurnPreStateSnapshot(
                        snap.historySize(),
                        snap.normalizedUserMessage(),
                        snap.preTurnState() != null ? snap.preTurnState().withOptedOut(nextOptedOut) : null,
                        snap.enrichedTurnState() != null ? snap.enrichedTurnState().withOptedOut(nextOptedOut) : null,
                        updated
                ));
            }
        }
    }

    public ConversationState withAuthoritativeSymptomDuration(String authoritativeDuration) {
        String normalizedDur = (authoritativeDuration != null && !authoritativeDuration.isBlank())
                ? authoritativeDuration.strip()
                : null;
        return new ConversationState(
                greetingSent, introSent, stage, lastIntent, lastQuestion, lastQuestionIntent,
                usedQuestionIntents, lastBotAction, known, priceDiscussed, pricesShown, recommendedOptions,
                objectionCount, ackCount, clarificationCount, conditionAskedCount, preferenceAskedCount,
                clarifyServiceTypeCount, clarifyDurationCount, frustrationCount, ambiguousCount,
                recommendationCount, priceRepeatGuardCount, conditionClarified, serviceFamilyKnown,
                packageRecommended, priceRequestedInSession, knownSymptoms, knownBodyAreas,
                knownSeverity, normalizedDur, knownTrigger, knownServiceFamily,
                knownPreferredPressure, knownRecommendedPackage, lastQuotePackage, lastSentiment,
                lastEmotion, usedTemplates, humanTakeover, optedOut,
                downsellOffered, trialInviteOffered, explicitDownsellRequest, packageRejectionDetected,
                lastQuotedPackage, lastQuotedDuration, lastQuotedPrice, heldPackage, heldDuration,
                downsellPackage, downsellDuration, downsellPrice, rejectedDurations, rejectedPackages,
                objectionStage, telemetrySubtype, docsHasFeedback, docsHasRelaxationBenefit, docsHasPrivateRoom, neurologicalRisk,
                preferredRegion, mentionedPackages, quotedPackages, heldPrice, alternativeRequestCount,
                customerCorrectionDetected, rejectedWrongOffer, availablePackageCatalog, lastBotBubbleHashes,
                outstandingRequests, lastAssistantMessage,
                confirmedScopes, forbiddenScopes, apologizedErrorIds, activeErrorId,
                customerCareNotes, scopeSupersessionEvents, possibleCorrection,
                packageExplicitlySelected, selectedPackageId,
                knownFrequency, knownSymptomDistribution, pendingQuestionSlot,
                normalizedDur != null ? knownEpisodeDuration : null,
                packageInterestAccepted,
                bookingIntentConfirmed,
                visitTime,
                contactInfo,
                bookingStatus,
                selectedProductId,
                bookingDate,
                bookingTime,
                bookingStatusEnum
        );
    }

    /**
     * Trong chế độ {@code LLM_COGNITIVE_V2}: khóa chặt {@code knownDurationOfProblem} theo xác nhận ngữ nghĩa của LLM Brain
     * (ngăn chặn bộ phân tích regex cũ trong {@code updateAfterSend -> ensureTurnEnriched} ghi đè ẩn dụ ngày công như "1 ngày công r" thành "1 ngày").
     */
    public static ConversationState syncAuthoritativeSymptomDurationInSession(
            UUID pageId,
            String senderId,
            String conversationId,
            String authoritativeDuration
    ) {
        String sessionKey = buildSessionKey(pageId, senderId, conversationId);
        if (sessionKey == null) {
            return null;
        }
        ConversationState current = SESSION_STORE.get(sessionKey);
        if (current == null) {
            return null;
        }
        ConversationState updated = current.withAuthoritativeSymptomDuration(authoritativeDuration);
        SESSION_STORE.put(sessionKey, updated);
        LastTurnPreStateSnapshot snap = LAST_TURN_PRE_STATE_STORE.get(sessionKey);
        if (snap != null) {
            LAST_TURN_PRE_STATE_STORE.put(sessionKey, new LastTurnPreStateSnapshot(
                    snap.historySize(),
                    snap.normalizedUserMessage(),
                    snap.preTurnState(),
                    snap.enrichedTurnState(),
                    updated
            ));
        }
        return updated;
    }

    /**
     * Unit P1.1: Khôi phục trạng thái phiên tối giản (0 V1 regex enrichment) cho {@code LLM_COGNITIVE_V2}.
     */
    public static ConversationState hydrateMinimalForAciV2(
            UUID pageId,
            String senderId,
            String conversationId,
            boolean resetSession
    ) {
        String sessionKey = buildSessionKey(pageId, senderId, conversationId);
        String fallbackKey = (conversationId != null && !conversationId.isBlank())
                ? buildSessionKey(pageId, senderId, null)
                : null;
        if (resetSession) {
            if (sessionKey != null) {
                SESSION_STORE.remove(sessionKey);
                LAST_TURN_PRE_STATE_STORE.remove(sessionKey);
                PENDING_BUBBLE_STORE.remove(sessionKey);
            }
            if (fallbackKey != null) {
                SESSION_STORE.remove(fallbackKey);
                LAST_TURN_PRE_STATE_STORE.remove(fallbackKey);
                PENDING_BUBBLE_STORE.remove(fallbackKey);
            }
            return initial();
        }
        if (sessionKey != null) {
            ConversationState existing = SESSION_STORE.get(sessionKey);
            if (existing != null) {
                return existing;
            }
        }
        if (fallbackKey != null) {
            ConversationState existingFallback = SESSION_STORE.get(fallbackKey);
            if (existingFallback != null) {
                return existingFallback;
            }
        }
        return initial();
    }

    /**
     * ACI V3 (P3 Fix-A/B): trích xuất triệu chứng khách tự kể từ một đoạn text tự do.
     * Luồng {@code LLM_COGNITIVE_V2} hydrate state tối giản ({@link #hydrateMinimalForAciV2})
     * nên {@code knownSymptoms} thường rỗng — các guard tầng output dùng hàm này để echo lại
     * đúng triệu chứng khách đã chia sẻ trong lịch sử. Clause chứa phủ định
     * ("không/chẳng/chả/đâu/hết/đừng") được bỏ qua để không echo triệu chứng khách đã phủ nhận.
     */
    public static List<String> extractSymptomsMentionedInText(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String clean = Normalizer.normalize(text.strip(), Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        Set<String> out = new LinkedHashSet<>();
        for (String clause : clean.split("[,;.!?？\\n]+")) {
            if (clause == null || clause.isBlank()) {
                continue;
            }
            if (clause.matches("(?isU).*\\b(?:không|chẳng|chả|đâu|hết|đừng)\\b.*")) {
                continue;
            }
            Matcher m = SYMPTOM_TOKEN_PATTERN.matcher(clause);
            while (m.find()) {
                String tok = m.group(1).replaceAll("\\s+", " ").strip();
                if (tok.length() >= 2) {
                    out.add(tok);
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * Unit P1.1: Gắn danh mục Catalog đã trích xuất từ chunk cho {@code LLM_COGNITIVE_V2} mà không chạy V1 history/doc-flag regex.
     */
    public ConversationState withCatalogOnlyForAciV2(
            List<ReciprocalRankFusion.FusedChunk> fusedChunks,
            List<String> domainKeywords
    ) {
        List<PackageCatalogService.PackageSummary> fullCatalog = PackageCatalogService.extractCatalog(fusedChunks, domainKeywords);
        // Gộp danh mục đã phát hiện ở các lượt trước trong cùng phiên với danh mục mới từ chunk hiện tại
        // để không bao giờ đánh rơi gói đã biết (VD: SG_NS01 ở T1 bị mất khi T2 chỉ hỏi địa chỉ "Quận 1").
        Map<String, PackageCatalogService.PackageSummary> mergedByUid = new java.util.LinkedHashMap<>();
        if (this.availablePackageCatalog != null) {
            for (PackageCatalogService.PackageSummary p : this.availablePackageCatalog) {
                if (p != null && p.packageUid() != null) {
                    mergedByUid.put(p.packageUid(), p);
                }
            }
        }
        if (fullCatalog != null) {
            for (PackageCatalogService.PackageSummary p : fullCatalog) {
                if (p != null && p.packageUid() != null) {
                    mergedByUid.put(p.packageUid(), p);
                }
            }
        }
        List<PackageCatalogService.PackageSummary> mergedCatalog = new ArrayList<>(mergedByUid.values());
        // ACI V3 P2 (§4.7, cấm #6): không mặc định "HN" — filterByRegion(null) giữ đủ biến thể đa chi nhánh
        String reg = this.preferredRegion;
        List<PackageCatalogService.PackageSummary> regCat = PackageCatalogService.filterByRegion(mergedCatalog, reg);
        if (regCat.isEmpty() && !mergedCatalog.isEmpty()) {
            regCat = mergedCatalog;
        }
        return this.withCatalogAndRegion(regCat, reg);
    }

    /**
     * Unit P1.1: Ghi nhận nguyên tử trạng thái phiên tối giản sau lượt {@code LLM_COGNITIVE_V2}
     * mà KHÔNG gọi {@code updateAfterSend -> ensureTurnEnriched} (0 V1 regex execution).
     */
    public static ConversationState recordAciV2TurnMinimalState(
            UUID pageId,
            String senderId,
            String conversationId,
            ConversationState prevState,
            List<ReplyBubble> finalBubbles,
            boolean replyHasPrice,
            StructuredBookingState authoritativeBookingState,
            String authoritativeSymptomDuration
    ) {
        ConversationState base = prevState != null ? prevState : initial();
        boolean nextGreetingSent = base.greetingSent() || (finalBubbles != null && !finalBubbles.isEmpty());
        boolean nextPriceDiscussed = base.priceDiscussed() || replyHasPrice;
        String nextLastAssistant = base.lastAssistantMessage();
        if (finalBubbles != null && !finalBubbles.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (ReplyBubble b : finalBubbles) {
                if (b != null && b.text() != null && !b.text().isBlank()) {
                    if (sb.length() > 0) sb.append("\n\n");
                    sb.append(b.text().strip());
                }
            }
            if (sb.length() > 0) {
                nextLastAssistant = sb.toString();
            }
        }
        ConversationState updated = new ConversationState(
                nextGreetingSent,
                base.introSent(),
                base.stage(),
                base.lastIntent(),
                base.lastQuestion(),
                base.lastQuestionIntent(),
                base.usedQuestionIntents(),
                base.lastBotAction(),
                base.known(),
                nextPriceDiscussed,
                base.pricesShown(),
                base.recommendedOptions(),
                base.objectionCount(),
                base.ackCount(),
                base.clarificationCount(),
                base.conditionAskedCount(),
                base.preferenceAskedCount(),
                base.clarifyServiceTypeCount(),
                base.clarifyDurationCount(),
                base.frustrationCount(),
                base.ambiguousCount(),
                base.recommendationCount(),
                base.priceRepeatGuardCount(),
                base.conditionClarified(),
                base.serviceFamilyKnown(),
                base.packageRecommended(),
                base.priceRequestedInSession(),
                base.knownSymptoms(),
                base.knownBodyAreas(),
                base.knownSeverity(),
                base.knownDurationOfProblem(),
                base.knownTrigger(),
                base.knownServiceFamily(),
                base.knownPreferredPressure(),
                base.knownRecommendedPackage(),
                base.lastQuotePackage(),
                base.lastSentiment(),
                base.lastEmotion(),
                base.usedTemplates(),
                base.humanTakeover(),
                base.optedOut(),
                base.downsellOffered(),
                base.trialInviteOffered(),
                base.explicitDownsellRequest(),
                base.packageRejectionDetected(),
                base.lastQuotedPackage(),
                base.lastQuotedDuration(),
                base.lastQuotedPrice(),
                base.heldPackage(),
                base.heldDuration(),
                base.downsellPackage(),
                base.downsellDuration(),
                base.downsellPrice(),
                base.rejectedDurations(),
                base.rejectedPackages(),
                base.objectionStage(),
                base.telemetrySubtype(),
                base.docsHasFeedback(),
                base.docsHasRelaxationBenefit(),
                base.docsHasPrivateRoom(),
                base.neurologicalRisk(),
                base.preferredRegion(),
                base.mentionedPackages(),
                base.quotedPackages(),
                base.heldPrice(),
                base.alternativeRequestCount(),
                base.customerCorrectionDetected(),
                base.rejectedWrongOffer(),
                base.availablePackageCatalog(),
                base.lastBotBubbleHashes(),
                base.outstandingRequests(),
                nextLastAssistant,
                base.confirmedScopes(),
                base.forbiddenScopes(),
                base.apologizedErrorIds(),
                base.activeErrorId(),
                base.customerCareNotes(),
                base.scopeSupersessionEvents(),
                base.possibleCorrection(),
                base.packageExplicitlySelected(),
                base.selectedPackageId(),
                base.knownFrequency(),
                base.knownSymptomDistribution(),
                base.pendingQuestionSlot(),
                base.knownEpisodeDuration(),
                base.packageInterestAccepted(),
                base.bookingIntentConfirmed(),
                base.visitTime(),
                base.contactInfo(),
                base.bookingStatus(),
                base.selectedProductId(),
                base.bookingDate(),
                base.bookingTime(),
                base.bookingStatusEnum()
        );
        if (authoritativeBookingState != null) {
            updated = updated.withStructuredBookingState(authoritativeBookingState);
        }
        updated = updated.withAuthoritativeSymptomDuration(authoritativeSymptomDuration);
        String sessionKey = buildSessionKey(pageId, senderId, conversationId);
        if (sessionKey != null) {
            SESSION_STORE.put(sessionKey, updated);
        }
        return updated;
    }

    /**
     * Chuyển trạng thái sang AWAITING_CUSTOMER_CONFIRMATION sau khi công cụ kiểm tra lịch / nhân sự xác nhận còn chỗ trống.
     */
    public static ConversationState markSlotAvailableAwaitingCustomerConfirmation(
            UUID pageId,
            String senderId,
            String conversationId,
            String verifiedProductId,
            String verifiedDate,
            String verifiedTime
    ) {
        ConversationState existing = getSessionState(pageId, senderId, conversationId);
        StructuredBookingState base = existing != null ? existing.structuredBookingState() : StructuredBookingState.none();
        String prodId = (verifiedProductId != null && !verifiedProductId.isBlank()) ? verifiedProductId.strip() : base.selectedProductId();
        String bDate = (verifiedDate != null && !verifiedDate.isBlank()) ? verifiedDate.strip() : base.bookingDate();
        String bTime = (verifiedTime != null && !verifiedTime.isBlank()) ? verifiedTime.strip() : base.bookingTime();
        StructuredBookingState next = new StructuredBookingState(
                prodId,
                base.selectedProductLabel(),
                bDate,
                resolveRelativeDateToIsoString(bDate, java.time.LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))),
                bTime,
                base.timezoneId() != null ? base.timezoneId() : "Asia/Ho_Chi_Minh",
                BookingStatus.AWAITING_CUSTOMER_CONFIRMATION,
                base.candidateProductIds()
        );
        return updateStructuredBookingStateInSession(pageId, senderId, conversationId, next);
    }

    /**
     * Chỉ chuyển sang CONFIRMED khi có kết quả xác nhận thực tế từ công cụ đặt lịch hoặc nhân sự phụ trách (C3-B Rule 6).
     */
    public static ConversationState confirmBookingOfficiallyFromToolOrStaff(
            UUID pageId,
            String senderId,
            String conversationId,
            String confirmedProductId,
            String confirmedDate,
            String confirmedTime
    ) {
        ConversationState existing = getSessionState(pageId, senderId, conversationId);
        StructuredBookingState base = existing != null ? existing.structuredBookingState() : StructuredBookingState.none();
        String prodId = (confirmedProductId != null && !confirmedProductId.isBlank()) ? confirmedProductId.strip() : base.selectedProductId();
        String bDate = (confirmedDate != null && !confirmedDate.isBlank()) ? confirmedDate.strip() : base.bookingDate();
        String bTime = (confirmedTime != null && !confirmedTime.isBlank()) ? confirmedTime.strip() : base.bookingTime();
        StructuredBookingState next = new StructuredBookingState(
                prodId,
                base.selectedProductLabel(),
                bDate,
                resolveRelativeDateToIsoString(bDate, java.time.LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))),
                bTime,
                base.timezoneId() != null ? base.timezoneId() : "Asia/Ho_Chi_Minh",
                BookingStatus.CONFIRMED,
                base.candidateProductIds()
        );
        return updateStructuredBookingStateInSession(pageId, senderId, conversationId, next);
    }

    /** Tương thích ngược: chữ ký canonical v3.9 (96 tham số). */
    public ConversationState(
            boolean greetingSent,
            boolean introSent,
            ConversationStage stage,
            String lastIntent,
            String lastQuestion,
            String lastQuestionIntent,
            Set<String> usedQuestionIntents,
            String lastBotAction,
            KnownFacts known,
            boolean priceDiscussed,
            List<String> pricesShown,
            List<String> recommendedOptions,
            int objectionCount,
            int ackCount,
            int clarificationCount,
            int conditionAskedCount,
            int preferenceAskedCount,
            int clarifyServiceTypeCount,
            int clarifyDurationCount,
            int frustrationCount,
            int ambiguousCount,
            int recommendationCount,
            int priceRepeatGuardCount,
            boolean conditionClarified,
            boolean serviceFamilyKnown,
            boolean packageRecommended,
            boolean priceRequestedInSession,
            List<String> knownSymptoms,
            List<String> knownBodyAreas,
            String knownSeverity,
            String knownDurationOfProblem,
            String knownTrigger,
            String knownServiceFamily,
            String knownPreferredPressure,
            String knownRecommendedPackage,
            String lastQuotePackage,
            String lastSentiment,
            String lastEmotion,
            List<String> usedTemplates,
            boolean humanTakeover,
            boolean optedOut,
            boolean downsellOffered,
            boolean trialInviteOffered,
            boolean explicitDownsellRequest,
            boolean packageRejectionDetected,
            String lastQuotedPackage,
            String lastQuotedDuration,
            BigDecimal lastQuotedPrice,
            String heldPackage,
            String heldDuration,
            String downsellPackage,
            String downsellDuration,
            BigDecimal downsellPrice,
            Set<String> rejectedDurations,
            Set<String> rejectedPackages,
            String objectionStage,
            String telemetrySubtype,
            boolean docsHasFeedback,
            boolean docsHasRelaxationBenefit,
            boolean docsHasPrivateRoom,
            boolean neurologicalRisk,
            String preferredRegion,
            List<String> mentionedPackages,
            List<String> quotedPackages,
            BigDecimal heldPrice,
            int alternativeRequestCount,
            boolean customerCorrectionDetected,
            String rejectedWrongOffer,
            List<PackageCatalogService.PackageSummary> availablePackageCatalog,
            List<String> lastBotBubbleHashes,
            List<UnresolvedCustomerRequest> outstandingRequests,
            String lastAssistantMessage,
            Set<String> confirmedScopes,
            Set<String> forbiddenScopes,
            Set<String> apologizedErrorIds,
            String activeErrorId,
            List<CustomerCareNoteArtifact> customerCareNotes,
            List<String> scopeSupersessionEvents,
            boolean possibleCorrection,
            boolean packageExplicitlySelected,
            String selectedPackageId,
            String knownFrequency,
            String knownSymptomDistribution,
            String pendingQuestionSlot,
            String knownEpisodeDuration,
            boolean packageInterestAccepted,
            boolean bookingIntentConfirmed,
            String visitTime,
            String contactInfo,
            String bookingStatus
    ) {
        this(
                greetingSent, introSent, stage, lastIntent, lastQuestion, lastQuestionIntent,
                usedQuestionIntents, lastBotAction, known, priceDiscussed, pricesShown, recommendedOptions,
                objectionCount, ackCount, clarificationCount, conditionAskedCount, preferenceAskedCount,
                clarifyServiceTypeCount, clarifyDurationCount, frustrationCount, ambiguousCount,
                recommendationCount, priceRepeatGuardCount, conditionClarified, serviceFamilyKnown,
                packageRecommended, priceRequestedInSession, knownSymptoms, knownBodyAreas,
                knownSeverity, knownDurationOfProblem, knownTrigger, knownServiceFamily,
                knownPreferredPressure, knownRecommendedPackage, lastQuotePackage, lastSentiment,
                lastEmotion, usedTemplates, humanTakeover, optedOut,
                downsellOffered, trialInviteOffered, explicitDownsellRequest, packageRejectionDetected,
                lastQuotedPackage, lastQuotedDuration, lastQuotedPrice, heldPackage, heldDuration,
                downsellPackage, downsellDuration, downsellPrice, rejectedDurations, rejectedPackages,
                objectionStage, telemetrySubtype, docsHasFeedback, docsHasRelaxationBenefit, docsHasPrivateRoom, neurologicalRisk,
                preferredRegion, mentionedPackages, quotedPackages, heldPrice, alternativeRequestCount,
                customerCorrectionDetected, rejectedWrongOffer, availablePackageCatalog, lastBotBubbleHashes,
                outstandingRequests, lastAssistantMessage,
                confirmedScopes, forbiddenScopes, apologizedErrorIds, activeErrorId,
                customerCareNotes, scopeSupersessionEvents, possibleCorrection,
                packageExplicitlySelected, selectedPackageId,
                knownFrequency, knownSymptomDistribution, pendingQuestionSlot,
                knownEpisodeDuration, packageInterestAccepted, bookingIntentConfirmed, visitTime, contactInfo, bookingStatus,
                packageExplicitlySelected ? selectedPackageId : null,
                null,
                null,
                BookingStatus.fromLegacyString(bookingStatus, packageExplicitlySelected ? selectedPackageId : null, visitTime)
        );
    }

    public boolean inPriceObjectionContext() {
        BookingStatus resolvedEnum = bookingStatusEnum != null
                ? bookingStatusEnum
                : BookingStatus.fromLegacyString(bookingStatus, selectedProductId, visitTime);
        return this.objectionCount >= 1
                && !"BOOKED".equalsIgnoreCase(this.objectionStage)
                && !this.packageInterestAccepted
                && !resolvedEnum.isActiveOrConfirmed()
                && !"COLLECTING_INFO".equalsIgnoreCase(this.bookingStatus)
                && !"COLLECTING_VISIT_TIME".equalsIgnoreCase(this.bookingStatus)
                && !"CONFIRMED".equalsIgnoreCase(this.bookingStatus);
    }

    public boolean hasPresentedDownsell() {
        return this.downsellOffered;
    }

    /** Tương thích ngược: chữ ký canonical v3.8. */
    public ConversationState(
            boolean greetingSent,
            boolean introSent,
            ConversationStage stage,
            String lastIntent,
            String lastQuestion,
            String lastQuestionIntent,
            Set<String> usedQuestionIntents,
            String lastBotAction,
            KnownFacts known,
            boolean priceDiscussed,
            List<String> pricesShown,
            List<String> recommendedOptions,
            int objectionCount,
            int ackCount,
            int clarificationCount,
            int conditionAskedCount,
            int preferenceAskedCount,
            int clarifyServiceTypeCount,
            int clarifyDurationCount,
            int frustrationCount,
            int ambiguousCount,
            int recommendationCount,
            int priceRepeatGuardCount,
            boolean conditionClarified,
            boolean serviceFamilyKnown,
            boolean packageRecommended,
            boolean priceRequestedInSession,
            List<String> knownSymptoms,
            List<String> knownBodyAreas,
            String knownSeverity,
            String knownDurationOfProblem,
            String knownTrigger,
            String knownServiceFamily,
            String knownPreferredPressure,
            String knownRecommendedPackage,
            String lastQuotePackage,
            String lastSentiment,
            String lastEmotion,
            List<String> usedTemplates,
            boolean humanTakeover,
            boolean optedOut,
            boolean downsellOffered,
            boolean trialInviteOffered,
            boolean explicitDownsellRequest,
            boolean packageRejectionDetected,
            String lastQuotedPackage,
            String lastQuotedDuration,
            BigDecimal lastQuotedPrice,
            String heldPackage,
            String heldDuration,
            String downsellPackage,
            String downsellDuration,
            BigDecimal downsellPrice,
            Set<String> rejectedDurations,
            Set<String> rejectedPackages,
            String objectionStage,
            String telemetrySubtype,
            boolean docsHasFeedback,
            boolean docsHasRelaxationBenefit,
            boolean docsHasPrivateRoom,
            boolean neurologicalRisk,
            String preferredRegion,
            List<String> mentionedPackages,
            List<String> quotedPackages,
            BigDecimal heldPrice,
            int alternativeRequestCount,
            boolean customerCorrectionDetected,
            String rejectedWrongOffer,
            List<PackageCatalogService.PackageSummary> availablePackageCatalog,
            List<String> lastBotBubbleHashes,
            List<UnresolvedCustomerRequest> outstandingRequests,
            String lastAssistantMessage,
            Set<String> confirmedScopes,
            Set<String> forbiddenScopes,
            Set<String> apologizedErrorIds,
            String activeErrorId,
            List<CustomerCareNoteArtifact> customerCareNotes,
            List<String> scopeSupersessionEvents,
            boolean possibleCorrection,
            boolean packageExplicitlySelected,
            String selectedPackageId,
            String knownFrequency,
            String knownSymptomDistribution,
            String pendingQuestionSlot
    ) {
        this(
                greetingSent, introSent, stage, lastIntent, lastQuestion, lastQuestionIntent, usedQuestionIntents, lastBotAction, known, priceDiscussed, pricesShown, recommendedOptions, objectionCount, ackCount, clarificationCount, conditionAskedCount, preferenceAskedCount, clarifyServiceTypeCount, clarifyDurationCount, frustrationCount, ambiguousCount, recommendationCount, priceRepeatGuardCount, conditionClarified, serviceFamilyKnown, packageRecommended, priceRequestedInSession, knownSymptoms, knownBodyAreas, knownSeverity, knownDurationOfProblem, knownTrigger, knownServiceFamily, knownPreferredPressure, knownRecommendedPackage, lastQuotePackage, lastSentiment, lastEmotion, usedTemplates, humanTakeover, optedOut, downsellOffered, trialInviteOffered, explicitDownsellRequest, packageRejectionDetected, lastQuotedPackage, lastQuotedDuration, lastQuotedPrice, heldPackage, heldDuration, downsellPackage, downsellDuration, downsellPrice, rejectedDurations, rejectedPackages, objectionStage, telemetrySubtype, docsHasFeedback, docsHasRelaxationBenefit, docsHasPrivateRoom, neurologicalRisk, preferredRegion, mentionedPackages, quotedPackages, heldPrice, alternativeRequestCount, customerCorrectionDetected, rejectedWrongOffer, availablePackageCatalog, lastBotBubbleHashes, outstandingRequests, lastAssistantMessage, confirmedScopes, forbiddenScopes, apologizedErrorIds, activeErrorId, customerCareNotes, scopeSupersessionEvents, possibleCorrection, packageExplicitlySelected, selectedPackageId,
                knownFrequency, knownSymptomDistribution, pendingQuestionSlot,
                null, packageExplicitlySelected, false, null, null, packageExplicitlySelected ? "COLLECTING_INFO" : "NONE"
        );
    }

    /** Tương thích ngược: chữ ký canonical trước v3.8 (slot mới mặc định null). */
    public ConversationState(
            boolean greetingSent,
            boolean introSent,
            ConversationStage stage,
            String lastIntent,
            String lastQuestion,
            String lastQuestionIntent,
            Set<String> usedQuestionIntents,
            String lastBotAction,
            KnownFacts known,
            boolean priceDiscussed,
            List<String> pricesShown,
            List<String> recommendedOptions,
            int objectionCount,
            int ackCount,
            int clarificationCount,
            int conditionAskedCount,
            int preferenceAskedCount,
            int clarifyServiceTypeCount,
            int clarifyDurationCount,
            int frustrationCount,
            int ambiguousCount,
            int recommendationCount,
            int priceRepeatGuardCount,
            boolean conditionClarified,
            boolean serviceFamilyKnown,
            boolean packageRecommended,
            boolean priceRequestedInSession,
            List<String> knownSymptoms,
            List<String> knownBodyAreas,
            String knownSeverity,
            String knownDurationOfProblem,
            String knownTrigger,
            String knownServiceFamily,
            String knownPreferredPressure,
            String knownRecommendedPackage,
            String lastQuotePackage,
            String lastSentiment,
            String lastEmotion,
            List<String> usedTemplates,
            boolean humanTakeover,
            boolean optedOut,
            boolean downsellOffered,
            boolean trialInviteOffered,
            boolean explicitDownsellRequest,
            boolean packageRejectionDetected,
            String lastQuotedPackage,
            String lastQuotedDuration,
            BigDecimal lastQuotedPrice,
            String heldPackage,
            String heldDuration,
            String downsellPackage,
            String downsellDuration,
            BigDecimal downsellPrice,
            Set<String> rejectedDurations,
            Set<String> rejectedPackages,
            String objectionStage,
            String telemetrySubtype,
            boolean docsHasFeedback,
            boolean docsHasRelaxationBenefit,
            boolean docsHasPrivateRoom,
            boolean neurologicalRisk,
            String preferredRegion,
            List<String> mentionedPackages,
            List<String> quotedPackages,
            BigDecimal heldPrice,
            int alternativeRequestCount,
            boolean customerCorrectionDetected,
            String rejectedWrongOffer,
            List<PackageCatalogService.PackageSummary> availablePackageCatalog,
            List<String> lastBotBubbleHashes,
            List<UnresolvedCustomerRequest> outstandingRequests,
            String lastAssistantMessage,
            Set<String> confirmedScopes,
            Set<String> forbiddenScopes,
            Set<String> apologizedErrorIds,
            String activeErrorId,
            List<CustomerCareNoteArtifact> customerCareNotes,
            List<String> scopeSupersessionEvents,
            boolean possibleCorrection,
            boolean packageExplicitlySelected,
            String selectedPackageId
    ) {
        this(
                greetingSent, introSent, stage, lastIntent, lastQuestion, lastQuestionIntent, usedQuestionIntents, lastBotAction, known, priceDiscussed, pricesShown, recommendedOptions, objectionCount, ackCount, clarificationCount, conditionAskedCount, preferenceAskedCount, clarifyServiceTypeCount, clarifyDurationCount, frustrationCount, ambiguousCount, recommendationCount, priceRepeatGuardCount, conditionClarified, serviceFamilyKnown, packageRecommended, priceRequestedInSession, knownSymptoms, knownBodyAreas, knownSeverity, knownDurationOfProblem, knownTrigger, knownServiceFamily, knownPreferredPressure, knownRecommendedPackage, lastQuotePackage, lastSentiment, lastEmotion, usedTemplates, humanTakeover, optedOut, downsellOffered, trialInviteOffered, explicitDownsellRequest, packageRejectionDetected, lastQuotedPackage, lastQuotedDuration, lastQuotedPrice, heldPackage, heldDuration, downsellPackage, downsellDuration, downsellPrice, rejectedDurations, rejectedPackages, objectionStage, telemetrySubtype, docsHasFeedback, docsHasRelaxationBenefit, docsHasPrivateRoom, neurologicalRisk, preferredRegion, mentionedPackages, quotedPackages, heldPrice, alternativeRequestCount, customerCorrectionDetected, rejectedWrongOffer, availablePackageCatalog, lastBotBubbleHashes, outstandingRequests, lastAssistantMessage, confirmedScopes, forbiddenScopes, apologizedErrorIds, activeErrorId, customerCareNotes, scopeSupersessionEvents, possibleCorrection, packageExplicitlySelected, selectedPackageId,
                null, null, null
        );
    }


    public ConversationState(
            boolean greetingSent,
            boolean introSent,
            ConversationStage stage,
            String lastIntent,
            String lastQuestion,
            String lastQuestionIntent,
            Set<String> usedQuestionIntents,
            String lastBotAction,
            KnownFacts known,
            boolean priceDiscussed,
            List<String> pricesShown,
            List<String> recommendedOptions,
            int objectionCount,
            int ackCount,
            int clarificationCount,
            int conditionAskedCount,
            int preferenceAskedCount,
            int clarifyServiceTypeCount,
            int clarifyDurationCount,
            int frustrationCount,
            int ambiguousCount,
            int recommendationCount,
            int priceRepeatGuardCount,
            boolean conditionClarified,
            boolean serviceFamilyKnown,
            boolean packageRecommended,
            boolean priceRequestedInSession,
            List<String> knownSymptoms,
            List<String> knownBodyAreas,
            String knownSeverity,
            String knownDurationOfProblem,
            String knownTrigger,
            String knownServiceFamily,
            String knownPreferredPressure,
            String knownRecommendedPackage,
            String lastQuotePackage,
            String lastSentiment,
            String lastEmotion,
            List<String> usedTemplates,
            boolean humanTakeover,
            boolean optedOut,
            boolean downsellOffered,
            boolean trialInviteOffered,
            boolean explicitDownsellRequest,
            boolean packageRejectionDetected,
            String lastQuotedPackage,
            String lastQuotedDuration,
            BigDecimal lastQuotedPrice,
            String heldPackage,
            String heldDuration,
            String downsellPackage,
            String downsellDuration,
            BigDecimal downsellPrice,
            Set<String> rejectedDurations,
            Set<String> rejectedPackages,
            String objectionStage,
            String telemetrySubtype,
            boolean docsHasFeedback,
            boolean docsHasRelaxationBenefit,
            boolean docsHasPrivateRoom,
            boolean neurologicalRisk,
            String preferredRegion,
            List<String> mentionedPackages,
            List<String> quotedPackages,
            BigDecimal heldPrice,
            int alternativeRequestCount,
            boolean customerCorrectionDetected,
            String rejectedWrongOffer,
            List<PackageCatalogService.PackageSummary> availablePackageCatalog,
            List<String> lastBotBubbleHashes,
            List<UnresolvedCustomerRequest> outstandingRequests,
            String lastAssistantMessage
    ) {
        this(
                greetingSent, introSent, stage, lastIntent, lastQuestion, lastQuestionIntent,
                usedQuestionIntents, lastBotAction, known, priceDiscussed, pricesShown, recommendedOptions,
                objectionCount, ackCount, clarificationCount, conditionAskedCount, preferenceAskedCount,
                clarifyServiceTypeCount, clarifyDurationCount, frustrationCount, ambiguousCount,
                recommendationCount, priceRepeatGuardCount, conditionClarified, serviceFamilyKnown,
                packageRecommended, priceRequestedInSession, knownSymptoms, knownBodyAreas,
                knownSeverity, knownDurationOfProblem, knownTrigger, knownServiceFamily,
                knownPreferredPressure, knownRecommendedPackage, lastQuotePackage, lastSentiment,
                lastEmotion, usedTemplates, humanTakeover, optedOut,
                downsellOffered, trialInviteOffered, explicitDownsellRequest, packageRejectionDetected,
                lastQuotedPackage, lastQuotedDuration, lastQuotedPrice, heldPackage, heldDuration,
                downsellPackage, downsellDuration, downsellPrice, rejectedDurations, rejectedPackages,
                objectionStage, telemetrySubtype, docsHasFeedback, docsHasRelaxationBenefit,
                docsHasPrivateRoom, neurologicalRisk, preferredRegion, mentionedPackages, quotedPackages,
                heldPrice, alternativeRequestCount, customerCorrectionDetected, rejectedWrongOffer,
                availablePackageCatalog, lastBotBubbleHashes, outstandingRequests, lastAssistantMessage,
                knownBodyAreas != null ? Collections.unmodifiableSet(new LinkedHashSet<>(knownBodyAreas)) : Set.of(),
                Set.of(),
                Set.of(),
                null,
                List.of(),
                List.of(),
                false,
                false,
                null
        );
    }

    public ConversationState(
            boolean greetingSent,
            boolean introSent,
            ConversationStage stage,
            String lastIntent,
            String lastQuestion,
            String lastQuestionIntent,
            Set<String> usedQuestionIntents,
            String lastBotAction,
            KnownFacts known,
            boolean priceDiscussed,
            List<String> pricesShown,
            List<String> recommendedOptions,
            int objectionCount,
            int ackCount,
            int clarificationCount,
            int conditionAskedCount,
            int preferenceAskedCount,
            int clarifyServiceTypeCount,
            int clarifyDurationCount,
            int frustrationCount,
            int ambiguousCount,
            int recommendationCount,
            int priceRepeatGuardCount,
            boolean conditionClarified,
            boolean serviceFamilyKnown,
            boolean packageRecommended,
            boolean priceRequestedInSession,
            List<String> knownSymptoms,
            List<String> knownBodyAreas,
            String knownSeverity,
            String knownDurationOfProblem,
            String knownTrigger,
            String knownServiceFamily,
            String knownPreferredPressure,
            String knownRecommendedPackage,
            String lastQuotePackage,
            String lastSentiment,
            String lastEmotion,
            List<String> usedTemplates,
            boolean humanTakeover,
            boolean optedOut,
            boolean downsellOffered,
            boolean trialInviteOffered,
            boolean explicitDownsellRequest,
            boolean packageRejectionDetected,
            String lastQuotedPackage,
            String lastQuotedDuration,
            BigDecimal lastQuotedPrice,
            String heldPackage,
            String heldDuration,
            String downsellPackage,
            String downsellDuration,
            BigDecimal downsellPrice,
            Set<String> rejectedDurations,
            Set<String> rejectedPackages,
            String objectionStage,
            String telemetrySubtype,
            boolean docsHasFeedback,
            boolean docsHasRelaxationBenefit,
            boolean docsHasPrivateRoom,
            boolean neurologicalRisk,
            String preferredRegion,
            List<String> mentionedPackages,
            List<String> quotedPackages,
            BigDecimal heldPrice,
            int alternativeRequestCount,
            boolean customerCorrectionDetected,
            String rejectedWrongOffer,
            List<PackageCatalogService.PackageSummary> availablePackageCatalog,
            List<String> lastBotBubbleHashes
    ) {
        this(
                greetingSent, introSent, stage, lastIntent, lastQuestion, lastQuestionIntent,
                usedQuestionIntents, lastBotAction, known, priceDiscussed, pricesShown, recommendedOptions,
                objectionCount, ackCount, clarificationCount, conditionAskedCount, preferenceAskedCount,
                clarifyServiceTypeCount, clarifyDurationCount, frustrationCount, ambiguousCount,
                recommendationCount, priceRepeatGuardCount, conditionClarified, serviceFamilyKnown,
                packageRecommended, priceRequestedInSession, knownSymptoms, knownBodyAreas,
                knownSeverity, knownDurationOfProblem, knownTrigger, knownServiceFamily,
                knownPreferredPressure, knownRecommendedPackage, lastQuotePackage, lastSentiment,
                lastEmotion, usedTemplates, humanTakeover, optedOut,
                downsellOffered, trialInviteOffered, explicitDownsellRequest, packageRejectionDetected,
                lastQuotedPackage, lastQuotedDuration, lastQuotedPrice, heldPackage, heldDuration,
                downsellPackage, downsellDuration, downsellPrice, rejectedDurations, rejectedPackages,
                objectionStage, telemetrySubtype, docsHasFeedback, docsHasRelaxationBenefit,
                docsHasPrivateRoom, neurologicalRisk, preferredRegion, mentionedPackages, quotedPackages,
                heldPrice, alternativeRequestCount, customerCorrectionDetected, rejectedWrongOffer,
                availablePackageCatalog, lastBotBubbleHashes, List.of(), null
        );
    }

    /** Alias ngữ nghĩa: thời gian khởi phát (tách khỏi tần suất và mức độ). */
    public String knownOnsetDuration() {
        return knownDurationOfProblem;
    }

    /** Trường ngữ nghĩa 1 (Item 2.1): Thời điểm bắt đầu bị (onset). */
    public String onset() {
        return knownDurationOfProblem;
    }

    /** Trường ngữ nghĩa 2 (Item 2.1): Tần suất xảy ra (frequency). */
    public String frequency() {
        return knownFrequency;
    }

    /** Phân loại tần suất chuẩn hoá (OCCASIONAL | FREQUENT | SPECIFIED). */
    public String frequencyCategory() {
        if (knownFrequency == null || knownFrequency.isBlank()) {
            return null;
        }
        String f = knownFrequency.toLowerCase(Locale.ROOT);
        if (f.contains("thi thoảng") || f.contains("thỉnh thoảng") || f.contains("đôi khi") || f.contains("lâu lâu")) {
            return "OCCASIONAL";
        }
        if (f.contains("thường xuyên") || f.contains("liên tục") || f.contains("hay bị")
                || f.contains("mỗi ngày") || f.contains("hằng ngày") || f.contains("suốt ngày")) {
            return "FREQUENT";
        }
        return "SPECIFIED";
    }

    /** Trường ngữ nghĩa 3 (Item 2.1): Thời gian kéo dài của mỗi đợt (duration). */
    public String duration() {
        return knownEpisodeDuration;
    }

    /** Kiểm tra xem danh sách triệu chứng đã được bot xác nhận ở lượt trước hay chưa (chặn lặp câu mở đầu nhại triệu chứng). */
    public boolean symptomsAcknowledgedInPriorTurn() {
        return lastAssistantMessage != null
                && !lastAssistantMessage.isBlank()
                && knownSymptoms != null
                && !knownSymptoms.isEmpty()
                && (conditionAskedCount >= 1 || conditionClarified || packageRecommended || priceDiscussed);
    }

    public int priceObjectionCount() {
        return objectionCount;
    }

    /**
     * Danh sách các gói đã thực sự được bot giới thiệu/nhắc tên trong văn bản gửi cho khách (`finalBubbles`).
     */
    public Set<String> presentedPackages() {
        if (mentionedPackages == null || mentionedPackages.isEmpty()) {
            return quotedPackages != null ? Collections.unmodifiableSet(new LinkedHashSet<>(quotedPackages)) : Set.of();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(mentionedPackages));
    }

    /**
     * Gói gần nhất đã thực sự được bot giới thiệu kèm giá trong văn bản gửi cho khách.
     */
    public String lastPresentedPackage() {
        if (lastQuotedPackage != null && !lastQuotedPackage.isBlank()) {
            return lastQuotedPackage;
        }
        if (mentionedPackages != null && !mentionedPackages.isEmpty()) {
            String last = null;
            for (String p : mentionedPackages) {
                if (p != null && !p.isBlank()) {
                    last = p;
                }
            }
            return last;
        }
        return null;
    }

    /**
     * Gói mà khách hàng đã thực sự đồng thuận chọn/đặt lịch (chỉ khác null khi gói đó đã được giới thiệu
     * VÀ khách hàng đã thể hiện ý định đồng thuận thử/đặt lịch).
     */
    public String acceptedPackage() {
        if (!packageInterestAccepted && !bookingIntentConfirmed) {
            return null;
        }
        String candidate = heldPackage != null && !heldPackage.isBlank() ? heldPackage : lastQuotedPackage;
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        boolean wasPresented = (mentionedPackages != null && mentionedPackages.contains(candidate))
                || (quotedPackages != null && quotedPackages.contains(candidate))
                || candidate.equalsIgnoreCase(lastQuotedPackage)
                || candidate.equalsIgnoreCase(knownRecommendedPackage);
        return wasPresented ? candidate : null;
    }

    public boolean hasUnresolvedCustomerRequest() {
        return latestUnresolvedRequest() != null;
    }

    public UnresolvedCustomerRequest latestUnresolvedRequest() {
        if (outstandingRequests == null || outstandingRequests.isEmpty()) {
            return null;
        }
        for (int i = outstandingRequests.size() - 1; i >= 0; i--) {
            UnresolvedCustomerRequest req = outstandingRequests.get(i);
            if (req != null && !req.resolved()) {
                return req;
            }
        }
        return null;
    }

    public UnresolvedCustomerRequest mostRecentUnresolvedRequest() {
        return latestUnresolvedRequest();
    }

    public UnresolvedCustomerRequest latestRequest() {
        if (outstandingRequests == null || outstandingRequests.isEmpty()) {
            return null;
        }
        return outstandingRequests.get(outstandingRequests.size() - 1);
    }

    /**
     * Biểu diễn tường minh và kiểm thử được cho câu hỏi / yêu cầu chi tiết dịch vụ của khách hàng
     * đang chờ giải đáp hoặc đã được giải đáp trong hội thoại.
     */
    public record UnresolvedCustomerRequest(
            String rawCustomerMessage,
            String semanticQuestion,
            String semanticIntent,
            String referencedEntity,
            String contextBotExcerpt,
            int turnIndex,
            boolean answeredByBot,
            boolean answerValidatedAndDelivered,
            int repeatOrChallengeCount,
            boolean customerChallengedAnswer,
            boolean resolved,
            boolean requiresHumanAssistance
    ) {
        public String canonicalQuestion() {
            return semanticQuestion;
        }

        public UnresolvedCustomerRequest withBotAttempt(boolean answered, boolean validatedAndDelivered) {
            boolean nowResolved = answered && validatedAndDelivered;
            return new UnresolvedCustomerRequest(
                    rawCustomerMessage,
                    semanticQuestion,
                    semanticIntent,
                    referencedEntity,
                    contextBotExcerpt,
                    turnIndex,
                    answered,
                    validatedAndDelivered,
                    repeatOrChallengeCount,
                    customerChallengedAnswer,
                    nowResolved,
                    requiresHumanAssistance && !nowResolved
            );
        }

        public UnresolvedCustomerRequest withCustomerRepeatOrChallenge(
                String latestCustomerMsg,
                boolean challenged,
                boolean needsHuman
        ) {
            return new UnresolvedCustomerRequest(
                    latestCustomerMsg != null && !latestCustomerMsg.isBlank() ? latestCustomerMsg : rawCustomerMessage,
                    semanticQuestion,
                    semanticIntent,
                    referencedEntity,
                    contextBotExcerpt,
                    turnIndex,
                    answeredByBot,
                    answerValidatedAndDelivered,
                    repeatOrChallengeCount + 1,
                    customerChallengedAnswer || challenged,
                    false,
                    requiresHumanAssistance || needsHuman
            );
        }
    }

    public enum ConversationStage {
        OPEN,
        NEW,
        GREETING,
        DISCOVERY,
        CONDITION_CLARIFIED,
        RECOMMENDATION,
        PRICE_CLARIFY,
        PRICE_QUOTED,
        PRICE_ANSWERED,
        OBJECTION,
        COMPARISON,
        CHOICE_PENDING,
        BOOKING,
        BOOKING_INTENT,
        HANDOFF,
        HARD_HANDOFF
    }

    public record KnownFacts(
            String itemInterest,
            String customerProblemOrNeed,
            String preferenceAttribute,
            String preferredOption,
            String preferredTime
    ) {
        public KnownFacts {
            if (customerProblemOrNeed != null && isServiceOrModalityKeyword(customerProblemOrNeed)) {
                if (itemInterest == null || itemInterest.isBlank()) {
                    itemInterest = customerProblemOrNeed;
                }
                customerProblemOrNeed = null;
            }
        }

        public static KnownFacts empty() {
            return new KnownFacts(null, null, null, null, null);
        }

        public boolean hasPreferenceAttribute() {
            return preferenceAttribute != null && !preferenceAttribute.isBlank();
        }

        public boolean hasCustomerProblemOrNeed() {
            return customerProblemOrNeed != null && !customerProblemOrNeed.isBlank();
        }
    }

    public record CustomerCareNoteArtifact(
            String tenantId,
            String conversationId,
            String turnId,
            Set<String> confirmedScopes,
            String careActor,
            String noteText,
            java.time.Instant timestamp
    ) {
        public CustomerCareNoteArtifact(
                String tenantId,
                String conversationId,
                String turnId,
                java.util.Collection<String> confirmedScopes,
                String careActor,
                String noteText,
                String timestampIso
        ) {
            this(
                    tenantId,
                    conversationId,
                    turnId,
                    confirmedScopes != null ? Collections.unmodifiableSet(new LinkedHashSet<>(confirmedScopes)) : Set.of(),
                    careActor,
                    noteText,
                    java.time.Instant.now()
            );
        }
    }

    public record GroundingCheck(
            List<String> customerConfirmedScopes,
            List<String> customerNegatedScopes,
            String activeIntent,
            String errorToApologize
    ) {
    }

    public record PendingBubbleRecord(
            String sessionKey,
            String turnId,
            ReplyBubble pendingBubble,
            String status,
            ConversationState committedStateAfterBubble1,
            ConversationState uncommittedStateIfBubble2Delivered,
            long scheduledAtEpochMs
    ) {
        public boolean isCancelled() {
            return "CANCELLED".equalsIgnoreCase(status);
        }

        public boolean isDelivered() {
            return "DELIVERED".equalsIgnoreCase(status);
        }

        public ConversationState pendingPostState() {
            return uncommittedStateIfBubble2Delivered;
        }

        public PendingBubbleRecord withStatus(String newStatus) {
            return new PendingBubbleRecord(
                    sessionKey,
                    turnId,
                    pendingBubble,
                    newStatus,
                    committedStateAfterBubble1,
                    uncommittedStateIfBubble2Delivered,
                    scheduledAtEpochMs
            );
        }
    }

    private record LastTurnPreStateSnapshot(
            int historySize,
            String normalizedUserMessage,
            ConversationState preTurnState,
            ConversationState enrichedTurnState,
            ConversationState postTurnState
    ) {
    }

    private static final Map<String, ConversationState> SESSION_STORE = new ConcurrentHashMap<>();
    private static final Map<String, Object> WEBHOOK_EVENT_DEDUP_STORE = new ConcurrentHashMap<>();
    private static final Map<String, Long> WEBHOOK_MID_DEDUP_STORE = new ConcurrentHashMap<>();
    private static final Map<String, LastTurnPreStateSnapshot> LAST_TURN_PRE_STATE_STORE = new ConcurrentHashMap<>();
    private static final Map<String, PendingBubbleRecord> PENDING_BUBBLE_STORE = new ConcurrentHashMap<>();
    private static final class IdentityWeakMap {
        private final Map<IdentityKey, ConversationState> delegate = new ConcurrentHashMap<>();
        private final java.lang.ref.ReferenceQueue<ConversationState> queue = new java.lang.ref.ReferenceQueue<>();

        private static final class IdentityKey extends java.lang.ref.WeakReference<ConversationState> {
            private final int hash;

            IdentityKey(ConversationState referent) {
                super(referent);
                this.hash = System.identityHashCode(referent);
            }

            IdentityKey(ConversationState referent, java.lang.ref.ReferenceQueue<ConversationState> q) {
                super(referent, q);
                this.hash = System.identityHashCode(referent);
            }

            @Override
            public int hashCode() {
                return hash;
            }

            @Override
            public boolean equals(Object obj) {
                if (this == obj) return true;
                if (!(obj instanceof IdentityKey other)) return false;
                ConversationState a = this.get();
                ConversationState b = other.get();
                return a != null && a == b;
            }
        }

        private void expungeStaleEntries() {
            java.lang.ref.Reference<? extends ConversationState> ref;
            while ((ref = queue.poll()) != null) {
                delegate.remove(ref);
            }
        }

        boolean containsKey(ConversationState key) {
            if (key == null) return false;
            expungeStaleEntries();
            return delegate.containsKey(new IdentityKey(key));
        }

        ConversationState get(ConversationState key) {
            if (key == null) return null;
            expungeStaleEntries();
            return delegate.get(new IdentityKey(key));
        }

        ConversationState getOrDefault(ConversationState key, ConversationState defaultValue) {
            ConversationState val = get(key);
            return val != null ? val : defaultValue;
        }

        void put(ConversationState key, ConversationState value) {
            if (key == null || value == null) return;
            expungeStaleEntries();
            delegate.put(new IdentityKey(key, queue), value);
        }
    }

    private static final IdentityWeakMap ENRICHED_TO_PRIOR_MAP = new IdentityWeakMap();

    public boolean isAlreadyEnrichedForTurn() {
        return ENRICHED_TO_PRIOR_MAP.containsKey(this);
    }

    public ConversationState priorTurnState() {
        return ENRICHED_TO_PRIOR_MAP.getOrDefault(this, this);
    }

    public ConversationState ensureTurnEnriched(String message, List<String> domainKeywords) {
        if (isAlreadyEnrichedForTurn() || message == null || message.isBlank()) {
            return this;
        }
        return enrichWithCurrentMessage(message, domainKeywords);
    }

    private static final Pattern OPERATIONAL_CARE_PROMISE_PATTERN = Pattern.compile(
            "(?isU).*\\b(?:em\\s+sẽ\\s+dặn|em\\s+sẽ\\s+lưu\\s+ý|em\\s+xin\\s+phép\\s+ghi\\s+chú|em\\s+sẽ\\s+ghi\\s+chú|em\\s+ghi\\s+chú|em\\s+sẽ\\s+báo|để\\s+em\\s+dặn|để\\s+em\\s+ghi\\s+chú|dặn\\s+(?:kỹ\\s+thuật\\s+viên|bác\\s+sĩ|chuyên\\s+viên|nhân\\s+viên|ktv)).*"
    );

    private static final Pattern PRICE_NUMBER_PATTERN = Pattern.compile(
            "(?iU)\\b(\\d{2,3}(?:\\.\\d{3})+|\\d{2,4})\\s*(?:k\\b|đ\\b|vnđ\\b|vnd\\b)"
    );
    private static final Pattern OPTION_UNIT_PATTERN = Pattern.compile(
            "(?iU)\\b(\\d{2,3}\\s*(?:phút|p\\b|ml\\b|g\\b|buổi|tháng|ngày)|size\\s*[smlx]{1,3})\\b"
    );
    private static final Pattern DURATION_ONLY_PATTERN = Pattern.compile(
            "(?iU)\\b(15|20|30|40|45|50|60|70|75|80|90|100|120)\\s*(?:phút|phut|p)\\b"
    );
    private static final Pattern PREFERENCE_PATTERN = Pattern.compile(
            "(?iU)\\b(lực\\s+nhẹ|lực\\s+vừa|lực\\s+mạnh|nhẹ\\s+nhàng|ấn\\s+sâu|mạnh\\s+tay|"
            + "form\\s+rộng|form\\s+ôm|size\\s+[smlx]{1,3}|da\\s+dầu|da\\s+khô|da\\s+nhạy\\s+cảm|"
            + "làm\\s+nhanh|thư\\s+giãn\\s+lâu|chi\\s+phí\\s+gọn|tiết\\s+kiệm)\\b"
    );
    private static final String ATOMIC_SCOPE_REGEX =
            "(?:cổ\\s+vai\\s+" + "gáy|vai\\s+" + "gáy|cổ\\s+vai|cổ\\s+" + "gáy|"
            + "cổ|vai|gáy|thắt\\s+lưng|lưng|eo|hông|mông|chân\\s+răng|bắp\\s+chân|bàn\\s+chân|chân|cánh\\s+tay|bàn\\s+tay|tay|"
            + "đầu\\s+gối|khớp\\s+gối|gối|(?<!lần\\s|buổi\\s|bắt\\s|từ\\s|ban\\s)đầu|cơ|tai|toàn\\s+thân|body|"
            + "răng\\s+hàm|răng\\s+khôn|răng\\s+cửa|răng|nướu|(?<!quyền\\s|tiện\\s|thuận\\s|có\\s)lợi(?!\\s+ích)|tủy|hàm|"
            + "da\\s+mặt|da\\s+đầu|da|mụn|nám|tóc|sofa|ghế|đệm|nệm|giường|tủ)";
    // Không bao giờ bắt từ đơn "gối" đứng độc lập làm bộ phận cơ thể (chỉ bắt "đầu gối", "khớp gối" hoặc có động từ "đau/mỏi/nhức gối")
    private static final String STANDALONE_BODY_AREA_REGEX =
            "(?:cổ\\s+vai\\s+" + "gáy|vai\\s+" + "gáy|cổ\\s+vai|cổ\\s+" + "gáy|"
            + "cổ|vai|gáy|thắt\\s+lưng|lưng|eo|hông|mông|chân\\s+răng|bắp\\s+chân|bàn\\s+chân|chân|cánh\\s+tay|bàn\\s+tay|tay|"
            + "đầu\\s+gối|khớp\\s+gối|(?<!lần\\s|buổi\\s|bắt\\s|từ\\s|ban\\s)đầu|cơ|tai|toàn\\s+thân|body|"
            + "răng\\s+hàm|răng\\s+khôn|răng\\s+cửa|răng|nướu|(?<!quyền\\s|tiện\\s|thuận\\s|có\\s)lợi(?!\\s+ích)|tủy|hàm|"
            + "da\\s+mặt|da\\s+đầu|da|mụn|nám|tóc|sofa|ghế|đệm|nệm|giường|tủ)";
    private static final String ATOMIC_MODIFIER_REGEX =
            "(?:đau\\s+mỏi|mỏi\\s+nhức|đau\\s+nhức|tê\\s+rần|tê\\s+bì|tê\\s+buốt|ê\\s+buốt|căng\\s+cơ|căng\\s+thẳng|căng\\s+cứng|chảy\\s+máu|ố\\s+vàng|xỉn\\s+màu|chuột\\s+rút|co\\s+rút|"
            + "đau|mỏi|nhức|nhói|tê|căng|cứng|sưng|viêm|buốt|ê|sâu|mẻ)";
    private static final Pattern SYMPTOM_TOKEN_PATTERN = Pattern.compile(
            "(?iU)\\b(đau\\s+đầu\\s+dữ\\s+dội|đau\\s+đầu|đau\\s+cơ|chuột\\s+rút(?:\\s+bắp\\s+chân|\\s+chân)?|co\\s+rút(?:\\s+cơ)?|"
            + "(?:đau|mỏi|nhức)\\s+(?:cổ\\s+vai\\s+" + "gáy|vai\\s+" + "gáy|cổ\\s+vai)|"
            + "đau|mỏi|nhức|nhói|tê\\s+chân\\s+tay|tê\\s+tay|tê\\s+chân|tê\\s+rần|tê\\s+bì|tê|"
            + "yếu\\s+tay|yếu\\s+chân|mất\\s+cảm\\s+giác|chóng\\s+mặt|ù\\s+tai|hoa\\s+mắt|"
            + "căng\\s+cơ|căng\\s+thẳng|căng\\s+cứng|căng\\s+cổ|căng|cứng|sưng|viêm|ê\\s+buốt|buốt|chảy\\s+máu|ố\\s+vàng|xỉn\\s+màu|sâu\\s+răng|"
            + "mệt\\s+mỏi|mệt|stress|mất\\s+ngủ|khó\\s+ngủ|nặng\\s+đầu|khó\\s+chịu)\\b"
    );
    private static final Pattern BODY_AREA_TOKEN_PATTERN = Pattern.compile(
            "(?iU)\\b(" + STANDALONE_BODY_AREA_REGEX + ")\\b"
    );
    private static final Pattern MULTI_ENTITY_NEED_PATTERN = Pattern.compile(
            "(?iU)\\b(?:"
            + "(?<fwdMod>" + ATOMIC_MODIFIER_REGEX + ")\\s+(?:vùng\\s+|ở\\s+|phần\\s+|khớp\\s+|cả\\s+|hai\\s+|2\\s+|một\\s+|1\\s+|bên\\s+)*(?<fwdScope>" + ATOMIC_SCOPE_REGEX + ")"
            + "(?:\\s*(?:,|và)\\s*(?:vùng\\s+|ở\\s+|phần\\s+|khớp\\s+|cả\\s+|hai\\s+|2\\s+|một\\s+|1\\s+|bên\\s+)*(?<fwdScopeChain>" + ATOMIC_SCOPE_REGEX + ")(?!\\s+(?:bị\\s+|đang\\s+|hơi\\s+|rất\\s+|hay\\s+)?" + ATOMIC_MODIFIER_REGEX + "))?"
            + "(?:\\s+(?<fwdSide>2\\s+bên|hai\\s+bên))?"
            + "|(?<invScope>" + ATOMIC_SCOPE_REGEX + ")\\s+(?:bị\\s+|đang\\s+|hơi\\s+|rất\\s+|hay\\s+)?(?<invMod>" + ATOMIC_MODIFIER_REGEX + ")(?!\\s+(?:vùng\\s+|ở\\s+|phần\\s+|khớp\\s+|cả\\s+|hai\\s+|2\\s+)?" + ATOMIC_SCOPE_REGEX + ")"
            + "|(?<standalone>chuột\\s+rút(?:\\s+bắp\\s+chân|\\s+chân)?|co\\s+rút(?:\\s+cơ)?|mất\\s+ngủ|khó\\s+ngủ|nặng\\s+đầu|chóng\\s+mặt|hoa\\s+mắt|ù\\s+tai|căng\\s+cơ|căng\\s+thẳng|căng\\s+cứng|stress|tê\\s+rần|tê\\s+bì)"
            + ")\\b"
    );
    // v3.8: mỗi slot ngữ nghĩa có pattern riêng, KHÔNG lẫn tần suất / vị trí phân bổ vào mức độ nặng nhẹ hay thời gian khởi phát.
    private static final Pattern SEVERITY_TOKEN_PATTERN = Pattern.compile(
            "(?iU)\\b(nhiều\\s+lắm|rất\\s+mỏi|rất\\s+đau|khá\\s+nặng|dữ\\s+dội|nhiều|nặng|nhẹ)\\b"
    );
    private static final Pattern PROBLEM_DURATION_TOKEN_PATTERN = Pattern.compile(
            "(?iU)\\b(lâu\\s+rồi|lâu\\s+năm|cũng\\s+lâu|khá\\s+lâu|(?:cũng\\s+|được\\s+)*(?:một|1|vài)\\s+thời\\s+gian(?:\\s+rồi)?|thời\\s+gian\\s+rồi|mới\\s+gần\\s+đây|dạo\\s+gần\\s+đây|mới\\s+bị|dạo\\s+này|mấy\\s+ngày|mấy\\s+hôm|vài\\s+hôm|(?:từ\\s+)?(?:hôm\\s+qua|hôm\\s+kia|tuần\\s+trước|tháng\\s+trước|năm\\s+ngoái)|(?<!mỗi\\s+lần\\s|mỗi\\s+đợt\\s|mỗi\\s+cơn\\s|kéo\\s+dài\\s)\\d+\\s*(?:ngày|tuần|tháng|năm)|(?<!mỗi\\s+lần\\s|mỗi\\s+đợt\\s|mỗi\\s+cơn\\s|kéo\\s+dài\\s)(?:một|hai|ba|bốn|năm|vài|mấy)\\s+(?:ngày|tuần|tháng|năm))\\b"
    );
    private static final Pattern FREQUENCY_TOKEN_PATTERN = Pattern.compile(
            "(?iU)\\b(thi\\s+thoảng|thỉnh\\s+thoảng|thi\\s+thoải|thi\\s+thoảmg|thỉnh\\s+thoảmg|lâu\\s+lâu|đôi\\s+khi|đôi\\s+lúc|thường\\s+xuyên|liên\\s+tục|hay\\s+bị|mỗi\\s+ngày|hằng\\s+ngày|hàng\\s+ngày|suốt\\s+ngày)\\b"
    );
    private static final Pattern EPISODE_DURATION_TOKEN_PATTERN = Pattern.compile(
            "(?iU)\\b((?:mỗi\\s+lần|mỗi\\s+đợt|mỗi\\s+cơn|kéo\\s+dài)\\s+(?:khoảng\\s+|tầm\\s+|chừng\\s+)?(?:\\d+|một|hai|ba|vài|mấy)\\s*(?:phút|tiếng|giờ|hôm|ngày)|(?:khoảng\\s+|tầm\\s+)?\\d+\\s*(?:phút|tiếng|giờ)\\s*(?:mỗi\\s+lần|mỗi\\s+đợt|một\\s+lần|mới\\s+hết)|âm\\s+ỉ\\s+cả\\s+(?:ngày|buổi|đêm)|cả\\s+ngày\\s+không\\s+hết)\\b"
    );
    private static final Pattern VISIT_TIME_TOKEN_PATTERN = Pattern.compile(
            "(?iU)\\b((?:sáng|trưa|chiều|tối)\\s+(?:nay|mai|mốt|thứ\\s+[2-7]|chủ\\s+nhật|cuối\\s+tuần)|(?:hôm\\s+nay|ngày\\s+mai|thứ\\s+[2-7]|chủ\\s+nhật|cuối\\s+tuần)(?:\\s+(?:lúc|khoảng|tầm)?\\s*\\d{1,2}\\s*(?:h|giờ)(?:\\s*\\d{1,2}|\\s*rưỡi)?)?|\\d{1,2}\\s*(?:h|giờ)(?:\\s*\\d{1,2}|\\s*rưỡi)?(?:\\s+(?:sáng|trưa|chiều|tối|hôm\\s+nay|ngày\\s+mai))?|khung\\s+giờ\\s+\\d{1,2}\\s*(?:h|giờ))\\b"
    );
    private static final Pattern CONTACT_INFO_TOKEN_PATTERN = Pattern.compile(
            "(?iU)\\b(0\\d{8,10}|\\+?84\\d{8,10})\\b"
    );
    private static final Pattern SYMPTOM_DISTRIBUTION_TOKEN_PATTERN = Pattern.compile(
            "(?iU)\\b(cả\\s+hai\\s+bên|hai\\s+bên|2\\s+bên|một\\s+bên|1\\s+bên|bên\\s+trái|bên\\s+phải|hai\\s+chân|2\\s+chân|hai\\s+tay|2\\s+tay|hai\\s+vai|2\\s+vai)\\b"
    );

    public static final String SLOT_ONSET = "ONSET_DURATION";
    public static final String SLOT_FREQUENCY = "FREQUENCY";
    public static final String SLOT_EPISODE_DURATION = "EPISODE_DURATION";
    public static final String SLOT_SEVERITY = "SEVERITY";
    public static final String SLOT_TRIGGER = "TRIGGER";
    public static final String SLOT_DISTRIBUTION = "DISTRIBUTION";
    public static final String SLOT_BOOKING_TIME = "BOOKING_TIME";

    private static final Pattern ASK_ONSET_Q = Pattern.compile("(?isU)(lâu\\s+chưa|từ\\s+bao\\s+giờ|bắt\\s+đầu\\s+từ\\s+khi\\s+nào|từ\\s+khi\\s+nào|bị\\s+khi\\s+nào|bao\\s+lâu|mới\\s+gần\\s+đây|mới\\s+bị\\s+hay)");
    private static final Pattern ASK_FREQUENCY_Q = Pattern.compile("(?isU)(thường\\s+xuyên|hay\\s+bị|thỉnh\\s+thoảng\\s+hay|thi\\s+thoảng\\s+hay|mức\\s+độ\\s+thường|tần\\s+suất|thường\\s+xảy\\s+ra|xảy\\s+ra\\s+khi\\s+nào|hay\\s+xuất\\s+hiện)");
    private static final Pattern ASK_EPISODE_DURATION_Q = Pattern.compile("(?isU)(mỗi\\s+lần\\s+kéo\\s+dài|mỗi\\s+đợt\\s+kéo\\s+dài|kéo\\s+dài\\s+bao\\s+lâu\\s+mỗi\\s+lần)");
    private static final Pattern ASK_SEVERITY_Q = Pattern.compile("(?isU)(nặng\\s+hay\\s+nhẹ|mức\\s+độ|nhiều\\s+không)");
    private static final Pattern ASK_TRIGGER_Q = Pattern.compile("(?isU)(sau\\s+khi|do\\s+ngồi|ngồi\\s+lâu|vận\\s+động\\s+nhiều|khi\\s+làm\\s+việc)");
    private static final Pattern ASK_DISTRIBUTION_Q = Pattern.compile("(?isU)(một\\s+bên|hai\\s+bên|cả\\s+hai)");
    private static final Pattern ASK_BOOKING_TIME_Q = Pattern.compile("(?isU)(khung\\s+giờ\\s+nào|mấy\\s+giờ|giờ\\s+nào\\s+để\\s+em|dự\\s+kiến\\s+ghé)");

    /** Suy ra slot câu hỏi từ nội dung câu hỏi THỰC SỰ nằm trong bong bóng đã giao (null nếu không có câu hỏi). */
    public static String inferPendingQuestionSlot(String deliveredQuestionText) {
        if (deliveredQuestionText == null || !deliveredQuestionText.contains("?")) {
            return null;
        }
        String q = deliveredQuestionText;
        if (ASK_BOOKING_TIME_Q.matcher(q).find()) return SLOT_BOOKING_TIME;
        if (ASK_DISTRIBUTION_Q.matcher(q).find()) return SLOT_DISTRIBUTION;
        if (ASK_EPISODE_DURATION_Q.matcher(q).find()) return SLOT_EPISODE_DURATION;
        if (ASK_ONSET_Q.matcher(q).find()) return SLOT_ONSET;
        if (ASK_FREQUENCY_Q.matcher(q).find()) return SLOT_FREQUENCY;
        if (ASK_SEVERITY_Q.matcher(q).find()) return SLOT_SEVERITY;
        if (ASK_TRIGGER_Q.matcher(q).find()) return SLOT_TRIGGER;
        return null;
    }

    static String normalizeFrequency(String raw) {
        if (raw == null) return null;
        String v = raw.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
        if ("thi thoải".equals(v) || "thi thoảmg".equals(v) || "lâu lâu".equals(v)) {
            return "thi thoảng";
        }
        if ("thỉnh thoảmg".equals(v)) {
            return "thỉnh thoảng";
        }
        return v;
    }

    private static final Pattern TRIGGER_TOKEN_PATTERN = Pattern.compile(
            "(?iU)\\b(ngồi\\s+lâu|ngồi\\s+máy\\s+tính|máy\\s+tính|văn\\s+phòng|vận\\s+động|sai\\s+tư\\s+thế|mang\\s+vác|tập\\s+gym|chơi\\s+thể\\s+thao)\\b"
    );
    private static final Pattern CONDITION_TIME_FREQUENCY_PROGRESSION_PATTERN = Pattern.compile(
            "(?iU)\\b(lâu\\s+rồi|lâu\\s+năm|lâu\\s+lắm|khá\\s+lâu|cũng\\s+lâu|hơi\\s+lâu|(?:bị|đau|mỏi|nhức|tê)\\s+lâu|"
            + "(?:cũng\\s+|được\\s+)*(?:một|1|vài)\\s+thời\\s+gian(?:\\s+rồi)?|thời\\s+gian\\s+rồi|"
            + "(?<!bao\\s|ngồi\\s|đứng\\s)lâu(?!\\s+chưa)|"
            + "mới\\s+gần\\s+đây|dạo\\s+gần\\s+đây|mới\\s+bị|mới\\s+đây|vừa\\s+mới|mới|dạo\\s+này|gần\\s+đây|"
            + "hôm\\s+qua|hôm\\s+kia|hôm\\s+nay|mấy\\s+hôm|vài\\s+hôm|"
            + "mấy\\s+ngày|vài\\s+ngày|mấy\\s+tuần|vài\\s+tuần|mấy\\s+tháng|vài\\s+tháng|mấy\\s+năm|vài\\s+năm|"
            + "\\d+\\s*(?:hôm|ngày|tuần|tháng|năm)|(?:hôm|ngày|tuần|tháng|năm)\\s+(?:nay|trước|rồi|qua)|"
            + "thi\\s+thoảng|thỉnh\\s+thoảng|thi\\s+thoải|thi\\s+thoảmg|thỉnh\\s+thoảmg|lâu\\s+lâu|đôi\\s+khi|đôi\\s+lúc|thường\\s+xuyên|liên\\s+tục|suốt|"
            + "tái\\s+phát|bị\\s+lại|lại\\s+bị|kinh\\s+niên|mãn\\s+tính|"
            + "do\\s+ngồi|do\\s+đứng|do\\s+làm\\s+việc|ngồi\\s+lâu|đứng\\s+lâu|ngồi\\s+máy\\s+tính|làm\\s+việc\\s+nhiều|vận\\s+động\\s+nhiều|"
            + "không\\s+rõ\\s+từ\\s+bao\\s+giờ|k\\s+rõ)\\b"
    );

    public static boolean hasConditionTimeOrProgressionSignal(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (SentimentIntentLayer.isMetaConversationOnBotBehavior(text)
                || SentimentIntentLayer.isNegationOrPushbackOnAssumption(text)
                || SentimentIntentLayer.isEntityMismatchCorrection(text)
                || PrimaryActionRouter.isFrustrationMessage(text)) {
            return false;
        }
        String clean = SentimentIntentLayer.stripMismatchCritiqueClause(text);
        return CONDITION_TIME_FREQUENCY_PROGRESSION_PATTERN.matcher(clean).find();
    }
    private static final Pattern TRIAL_INVITE_BOT_PATTERN = Pattern.compile(
            "(?isU).*(?:thử\\s+1\\s+buổi|thử\\s+một\\s+buổi|trải\\s+nghiệm\\s+lẻ\\s+1\\s+buổi|thử\\s+xem\\s+sao|cảm\\s+nhận\\s+tay\\s+nghề|cảm\\s+nhận\\s+trực\\s+tiếp).*"
    );
    private static final Map<String, String> CANONICAL_TO_DISPLAY_CACHE = new ConcurrentHashMap<>();

    public List<String> quotedPrices() {
        return pricesShown;
    }

    private static final Pattern EXPLICIT_PACKAGE_SELECTION_PATTERN = Pattern.compile(
            "(?isU).*(?:\\b(?:cho|lấy|chọn|đặt|book|chốt|đăng\\s+ký)\\s+(?:mình|tôi|em|anh|chị)?\\s*(?:gói|liệu\\s+trình|suất)\\s*(?:đó|này|ấy|vừa\\s+rồi|trên)?\\b"
            + "|\\b(?:ok|oke|được|đồng\\s+ý|thế\\s+thì|vậy\\s+thì)\\s+(?:lấy|chọn|chốt|đặt)\\b"
            + "|\\bgói\\s+(?:đó|này|ấy)\\s+(?:đi|nhé|nha|ạ)\\b).*"
    );

    public static boolean hasExplicitPackageSelectionSignal(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (SentimentIntentLayer.isWordingOrExpressionFeedback(text)
                || SentimentIntentLayer.isConversationStyleComplaint(text)
                || SentimentIntentLayer.isAlreadyAnsweredSlotPushback(text)) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.matches("(?isU).*\\b(?:giá|bao\\s+nhiêu|đắt|mắc|rẻ|có\\s+gói\\s+nào|gói\\s+nào|gói\\s+khác)\\b.*")
                && !lower.matches("(?isU).*\\b(?:lấy|chọn|chốt|đặt)\\b.*")) {
            return false;
        }
        return EXPLICIT_PACKAGE_SELECTION_PATTERN.matcher(lower).matches();
    }

    /** Tương thích ngữ nghĩa: tình trạng đã được khách làm rõ về thời gian/tần suất/diễn biến (độc lập với giá và chọn gói). */
    public boolean conditionTimeClarified() {
        return conditionClarified;
    }

    public String lastOfferedPackageId() {
        if (downsellOffered && downsellPackage != null && !downsellPackage.isBlank()) {
            return downsellPackage;
        }
        return heldPackage != null && !heldPackage.isBlank() ? heldPackage : lastQuotedPackage;
    }

    public String lastOfferedDuration() {
        if (downsellOffered && downsellDuration != null && !downsellDuration.isBlank()) {
            return downsellDuration;
        }
        return heldDuration != null && !heldDuration.isBlank() ? heldDuration : lastQuotedDuration;
    }

    public String lastOfferedPackageName() {
        String id = lastOfferedPackageId();
        String dur = lastOfferedDuration();
        if (id != null && !id.isBlank()) {
            return toDisplayPackageName(id, dur);
        }
        return knownRecommendedPackage;
    }

    public BigDecimal lastOfferedPriceVnd() {
        if (downsellOffered && downsellPrice != null) {
            return downsellPrice;
        }
        return heldPrice != null ? heldPrice : lastQuotedPrice;
    }

    public String lastOfferedPrice() {
        BigDecimal bd = lastOfferedPriceVnd();
        if (bd != null && bd.compareTo(BigDecimal.ZERO) > 0) {
            long val = bd.longValue();
            if (val >= 1000 && val % 1000 == 0) {
                return (val / 1000) + "k";
            }
            return bd.toPlainString();
        }
        if (pricesShown != null && !pricesShown.isEmpty()) {
            return pricesShown.get(pricesShown.size() - 1);
        }
        return null;
    }

    public String offerStage() {
        return objectionStage != null ? objectionStage : "NONE";
    }

    public int offerTurnId() {
        return pricesShown != null ? pricesShown.size() : 0;
    }

    public static void clearSessionCache() {
        SESSION_STORE.clear();
        WEBHOOK_EVENT_DEDUP_STORE.clear();
        WEBHOOK_MID_DEDUP_STORE.clear();
        LAST_TURN_PRE_STATE_STORE.clear();
    }

    /**
     * Kiểm tra và đánh dấu nguyên tử (atomic) mã tin nhắn {@code mid} từ Facebook Webhook với TTL (mặc định 10 phút).
     * Trả về {@code true} nếu đây là lần đầu tiên gặp {@code mid} trong khoảng TTL (cho phép xử lý),
     * hoặc {@code false} nếu {@code mid} đang được xử lý hoặc đã xử lý rồi trong khoảng TTL (bỏ qua retry).
     */
    public static boolean tryAcquireWebhookMid(String mid, long ttlMs) {
        if (mid == null || mid.isBlank()) {
            return true;
        }
        String normalizedMid = mid.strip();
        long now = System.currentTimeMillis();
        long effectiveTtl = ttlMs > 0 ? ttlMs : 600_000L;
        if (WEBHOOK_MID_DEDUP_STORE.size() > 4000) {
            WEBHOOK_MID_DEDUP_STORE.entrySet().removeIf(e -> e.getValue() == null || (now - e.getValue()) > effectiveTtl);
        }
        final boolean[] acquired = {false};
        WEBHOOK_MID_DEDUP_STORE.compute(normalizedMid, (k, existingTs) -> {
            if (existingTs != null && (now - existingTs) <= effectiveTtl) {
                acquired[0] = false;
                return existingTs;
            }
            acquired[0] = true;
            return now;
        });
        return acquired[0];
    }

    public static void releaseWebhookMid(String mid) {
        if (mid != null && !mid.isBlank()) {
            WEBHOOK_MID_DEDUP_STORE.remove(mid.strip());
        }
    }

    public static ConversationState getSessionState(UUID pageId, String senderId) {
        return getSessionState(pageId, senderId, null);
    }

    public static ConversationState getSessionState(UUID pageId, String senderId, String conversationId) {
        String key = buildSessionKey(pageId, senderId, conversationId);
        return key != null ? SESSION_STORE.get(key) : null;
    }

    public static void resetSessionState(UUID pageId, String senderId, String conversationId) {
        String key = buildSessionKey(pageId, senderId, conversationId);
        if (key != null) {
            SESSION_STORE.remove(key);
            LAST_TURN_PRE_STATE_STORE.remove(key);
        }
        String legacyKey = buildSessionKey(pageId, senderId, null);
        if (legacyKey != null) {
            SESSION_STORE.remove(legacyKey);
            LAST_TURN_PRE_STATE_STORE.remove(legacyKey);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T getCachedWebhookResponse(String eventKey) {
        if (eventKey == null || eventKey.isBlank()) {
            return null;
        }
        return (T) WEBHOOK_EVENT_DEDUP_STORE.get(eventKey);
    }

    public static void cacheWebhookResponse(String eventKey, Object response) {
        if (eventKey == null || eventKey.isBlank() || response == null) {
            return;
        }
        if (WEBHOOK_EVENT_DEDUP_STORE.size() > 2000) {
            WEBHOOK_EVENT_DEDUP_STORE.clear();
        }
        WEBHOOK_EVENT_DEDUP_STORE.put(eventKey, response);
    }

    public static String buildWebhookEventKey(
            UUID pageId,
            String senderId,
            String message,
            List<ConversationTurn> history,
            String explicitEventId
    ) {
        return buildWebhookEventKey(pageId, senderId, null, false, message, history, explicitEventId);
    }

    public static String buildWebhookEventKey(
            UUID pageId,
            String senderId,
            String conversationId,
            boolean resetSession,
            String message,
            List<ConversationTurn> history,
            String explicitEventId
    ) {
        if (resetSession) {
            return null;
        }
        String pageStr = pageId != null ? pageId.toString() : "default";
        String senderStr = senderId != null && !senderId.isBlank() ? senderId.strip() : "";
        String convStr = conversationId != null && !conversationId.isBlank() ? "::conv::" + conversationId.strip() : "";
        if (explicitEventId != null && !explicitEventId.isBlank()) {
            return pageStr + "::" + senderStr + convStr + "::mid::" + explicitEventId.strip();
        }
        if ((senderStr.isEmpty() && convStr.isEmpty()) || message == null || message.isBlank()) {
            return null;
        }
        StringBuilder histSig = new StringBuilder();
        if (history != null && !history.isEmpty()) {
            histSig.append(history.size());
            for (ConversationTurn t : history) {
                if (t != null) {
                    histSig.append('|')
                            .append(t.role() != null ? t.role() : "")
                            .append(':')
                            .append(t.content() != null ? t.content().hashCode() : 0);
                }
            }
        } else {
            ConversationState existing = getSessionState(pageId, senderId, conversationId);
            if (existing != null && existing.lastBotBubbleHashes() != null && !existing.lastBotBubbleHashes().isEmpty()) {
                histSig.append("sess:").append(existing.lastBotBubbleHashes().size())
                        .append(':').append(existing.lastBotBubbleHashes().get(existing.lastBotBubbleHashes().size() - 1));
            } else {
                histSig.append("0");
            }
        }
        return pageStr + "::" + senderStr + convStr + "::" + histSig + "::" + message.strip();
    }

    public static ConversationState empty() {
        return initial();
    }

    public static ConversationState initial() {
        return new ConversationState(
                false,
                false,
                ConversationStage.OPEN,
                null,
                null,
                null,
                Set.of(),
                null,
                KnownFacts.empty(),
                false,
                List.of(),
                List.of(),
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                false,
                false,
                false,
                false,
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "NEUTRAL",
                "NONE",
                List.of(),
                false,
                false,
                false,
                false,
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Set.of(),
                Set.of(),
                "NONE",
                null,
                false,
                true,
                false,
                false,
                null, // ACI V3 P2 (§4.7, cấm #6): preferredRegion ban đầu = null (UNSPECIFIED), KHÔNG default "HN"
                List.of(),
                List.of(),
                null,
                0,
                false,
                null,
                List.of(),
                List.of(),
                List.of(),
                null,
                Set.of(),
                Set.of(),
                Set.of(),
                null,
                List.of(),
                List.of(),
                false,
                false,
                null
        );
    }

    public static ConversationState hydrate(
            UUID pageId,
            String senderId,
            List<ConversationTurn> history,
            List<String> domainKeywords
    ) {
        return hydrate(pageId, senderId, history, domainKeywords, List.of());
    }

    /**
     * Khôi phục trạng thái hội thoại tuần tự theo thời gian từ {@code history} kết hợp {@code SESSION_STORE} và {@code fusedChunks}.
     */
    public static ConversationState hydrate(
            UUID pageId,
            String senderId,
            List<ConversationTurn> history,
            List<String> domainKeywords,
            List<ReciprocalRankFusion.FusedChunk> fusedChunks
    ) {
        return hydrateForTurn(pageId, senderId, null, false, null, history, domainKeywords, fusedChunks);
    }

    public static ConversationState hydrate(
            UUID pageId,
            String senderId,
            String conversationId,
            boolean resetSession,
            List<ConversationTurn> history,
            List<String> domainKeywords,
            List<ReciprocalRankFusion.FusedChunk> fusedChunks
    ) {
        return hydrateForTurn(pageId, senderId, conversationId, resetSession, null, history, domainKeywords, fusedChunks);
    }

    public static ConversationState hydrateForTurn(
            UUID pageId,
            String senderId,
            String currentMessage,
            List<ConversationTurn> history,
            List<String> domainKeywords,
            List<ReciprocalRankFusion.FusedChunk> fusedChunks
    ) {
        return hydrateForTurn(pageId, senderId, null, false, currentMessage, history, domainKeywords, fusedChunks);
    }

    public static ConversationState hydrateForTurn(
            UUID pageId,
            String senderId,
            String conversationId,
            boolean resetSession,
            String currentMessage,
            List<ConversationTurn> history,
            List<String> domainKeywords,
            List<ReciprocalRankFusion.FusedChunk> fusedChunks
    ) {
        String sessionKey = buildSessionKey(pageId, senderId, conversationId);
        boolean hasStableConversationId = conversationId != null && !conversationId.isBlank();
        boolean docsFeedback = detectDocsHasFeedback(fusedChunks);
        boolean docsRelaxation = detectDocsHasRelaxationBenefit(fusedChunks);
        boolean docsPrivateRoom = detectDocsHasPrivateRoom(fusedChunks);
        List<PackageCatalogService.PackageSummary> fullCatalog = PackageCatalogService.extractCatalog(fusedChunks, domainKeywords);

        if (resetSession && sessionKey != null) {
            SESSION_STORE.remove(sessionKey);
            LAST_TURN_PRE_STATE_STORE.remove(sessionKey);
            PENDING_BUBBLE_STORE.remove(sessionKey);
        } else if (sessionKey != null && currentMessage != null && !currentMessage.isBlank()) {
            cancelPendingSecondBubbleIfPresent(sessionKey);
        }

        if (history == null || history.isEmpty()) {
            if (!resetSession && hasStableConversationId && sessionKey != null) {
                ConversationState existingSession = SESSION_STORE.get(sessionKey);
                if (existingSession != null) {
                    String reg = existingSession.preferredRegion();
                    List<PackageCatalogService.PackageSummary> regCat = PackageCatalogService.filterByRegion(fullCatalog, reg);
                    return existingSession
                            .withDocFlags(docsFeedback, docsRelaxation, docsPrivateRoom)
                            .withCatalogAndRegion(regCat, reg);
                }
            }
            if (!resetSession && sessionKey != null && currentMessage != null && !currentMessage.isBlank()) {
                LastTurnPreStateSnapshot snap = LAST_TURN_PRE_STATE_STORE.get(sessionKey);
                String normCurrent = VietnameseQueryNormalizer.normalize(currentMessage).toLowerCase(Locale.ROOT).strip();
                if (snap != null && snap.historySize() == 0 && normCurrent.equals(snap.normalizedUserMessage()) && snap.preTurnState() != null) {
                    List<PackageCatalogService.PackageSummary> regCat = PackageCatalogService.filterByRegion(fullCatalog, snap.preTurnState().preferredRegion());
                    return snap.preTurnState()
                            .withDocFlags(docsFeedback, docsRelaxation, docsPrivateRoom)
                            .withCatalogAndRegion(regCat, snap.preTurnState().preferredRegion());
                }
            }
            // v3.3.4 RC-01: Kênh webhook (Messenger) thường gửi history rỗng và không có conversationId ổn định.
            // Nếu phiên của đúng (page, sender) đã gửi lời chào, khôi phục phiên thay vì reset -> tránh chào lặp lại.
            // Phiên mới thật sự vẫn được tạo khi resetSession = true hoặc chưa từng chào.
            if (!resetSession && !hasStableConversationId && sessionKey != null) {
                ConversationState existingSession = SESSION_STORE.get(sessionKey);
                if (existingSession != null && existingSession.greetingSent()) {
                    String reg = existingSession.preferredRegion();
                    List<PackageCatalogService.PackageSummary> regCat = PackageCatalogService.filterByRegion(fullCatalog, reg);
                    return existingSession
                            .withDocFlags(docsFeedback, docsRelaxation, docsPrivateRoom)
                            .withCatalogAndRegion(regCat, reg);
                }
            }
            if (sessionKey != null) {
                SESSION_STORE.remove(sessionKey);
                LAST_TURN_PRE_STATE_STORE.remove(sessionKey);
            }
            // ACI V3 P2 (§4.7, cấm #6): phiên mới chưa có cue chi nhánh -> giữ đủ catalog đa chi nhánh (region null)
            return initial()
                    .withDocFlags(docsFeedback, docsRelaxation, docsPrivateRoom)
                    .withCatalogAndRegion(fullCatalog, null);
        }

        ConversationState cached = sessionKey != null ? SESSION_STORE.get(sessionKey) : null;
        if (sessionKey != null) {
            LastTurnPreStateSnapshot snap = LAST_TURN_PRE_STATE_STORE.get(sessionKey);
            if (snap != null && (snap.historySize() < 0 || snap.historySize() == history.size())) {
                if (currentMessage != null && !currentMessage.isBlank()
                        && VietnameseQueryNormalizer.normalize(currentMessage).toLowerCase(Locale.ROOT).strip().equals(snap.normalizedUserMessage())
                        && snap.preTurnState() != null) {
                    List<PackageCatalogService.PackageSummary> regCat = PackageCatalogService.filterByRegion(fullCatalog, snap.preTurnState().preferredRegion());
                    return snap.preTurnState()
                            .withDocFlags(docsFeedback, docsRelaxation, docsPrivateRoom)
                            .withCatalogAndRegion(regCat, snap.preTurnState().preferredRegion());
                }
                if (currentMessage == null || currentMessage.isBlank()) {
                    cached = snap.preTurnState();
                }
            }
        }
        boolean hasAssistantTurnInHistory = history.stream()
                .anyMatch(t -> t != null && t.role() != null
                        && ("assistant".equalsIgnoreCase(t.role().strip()) || "bot".equalsIgnoreCase(t.role().strip())));

        boolean greetingSent = !hasAssistantTurnInHistory && cached != null && cached.greetingSent();
        boolean introSent = !hasAssistantTurnInHistory && cached != null && cached.introSent();
        ConversationStage stage = !hasAssistantTurnInHistory && cached != null ? cached.stage() : ConversationStage.GREETING;
        String lastIntent = cached != null ? cached.lastIntent() : null;
        String lastQuestion = !hasAssistantTurnInHistory && cached != null ? cached.lastQuestion() : null;
        String lastQuestionIntent = cached != null ? cached.lastQuestionIntent() : null;
        Set<String> usedQuestionIntents = new LinkedHashSet<>();
        if (cached != null && cached.usedQuestionIntents() != null) {
            usedQuestionIntents.addAll(cached.usedQuestionIntents());
        }
        String lastBotAction = cached != null ? cached.lastBotAction() : null;
        String itemInterest = cached != null && cached.known() != null ? cached.known().itemInterest() : null;
        String problemOrNeed = cached != null && cached.known() != null ? cached.known().customerProblemOrNeed() : null;
        String preferenceAttr = cached != null && cached.known() != null ? cached.known().preferenceAttribute() : null;
        String preferredOption = cached != null && cached.known() != null ? cached.known().preferredOption() : null;
        String preferredTime = cached != null && cached.known() != null ? cached.known().preferredTime() : null;

        Set<String> pricesShown = new LinkedHashSet<>();
        if (!hasAssistantTurnInHistory && cached != null && cached.pricesShown() != null) {
            pricesShown.addAll(cached.pricesShown());
        }
        Set<String> recommendedOptions = new LinkedHashSet<>();
        if (!hasAssistantTurnInHistory && cached != null && cached.recommendedOptions() != null) {
            recommendedOptions.addAll(cached.recommendedOptions());
        }
        Set<String> usedTemplates = new LinkedHashSet<>();
        if (cached != null && cached.usedTemplates() != null) {
            usedTemplates.addAll(cached.usedTemplates());
        }

        Set<String> knownSymptoms = new LinkedHashSet<>();
        Set<String> knownBodyAreas = new LinkedHashSet<>();
        String knownSeverity = cached != null ? cached.knownSeverity() : null;
        String knownDurationOfProblem = cached != null ? cached.knownDurationOfProblem() : null;
        String knownTrigger = cached != null ? cached.knownTrigger() : null;
        String knownFrequencyL = cached != null ? cached.knownFrequency() : null;
        String knownSymptomDistributionL = cached != null ? cached.knownSymptomDistribution() : null;
        String knownEpisodeDurationL = cached != null ? cached.knownEpisodeDuration() : null;
        boolean packageInterestAcceptedL = cached != null && cached.packageInterestAccepted();
        boolean bookingIntentConfirmedL = cached != null && cached.bookingIntentConfirmed();
        String visitTimeL = cached != null ? cached.visitTime() : null;
        String contactInfoL = cached != null ? cached.contactInfo() : null;
        String bookingStatusL = cached != null && cached.bookingStatus() != null ? cached.bookingStatus() : "NONE";
        String pendingQuestionSlotL = cached != null ? cached.pendingQuestionSlot() : null;
        String knownServiceFamily = cached != null ? cached.knownServiceFamily() : null;
        String knownPreferredPressure = cached != null ? cached.knownPreferredPressure() : null;
        String knownRecommendedPackage = cached != null ? cached.knownRecommendedPackage() : null;
        String lastQuotePackage = cached != null ? cached.lastQuotePackage() : null;
        String lastSentiment = "NEUTRAL";
        String lastEmotion = "NONE";

        int objectionCount = 0;
        int ackCount = 0;
        int clarificationCount = 0;
        int conditionAskedCount = 0;
        int preferenceAskedCount = 0;
        int clarifyServiceTypeCount = 0;
        int clarifyDurationCount = 0;
        int frustrationCount = 0;
        int ambiguousCount = 0;
        int recommendationCount = 0;
        int priceRepeatGuardCount = 0;
        boolean priceRequestedInSession = false;
        boolean humanTakeover = cached != null && cached.humanTakeover();
        boolean optedOut = cached != null && cached.optedOut();
        int customerSymptomTurns = 0;

        // v3.1.5.3 + v3.2.1 state variables reconstructed sequentially
        boolean downsellOffered = cached != null && cached.downsellOffered();
        boolean trialInviteOffered = cached != null && cached.trialInviteOffered();
        boolean explicitDownsellRequest = false;
        boolean packageRejectionDetected = cached != null && cached.packageRejectionDetected();
        String lastQuotedPackage = cached != null ? cached.lastQuotedPackage() : null;
        String lastQuotedDuration = cached != null ? cached.lastQuotedDuration() : null;
        BigDecimal lastQuotedPrice = cached != null ? cached.lastQuotedPrice() : null;
        String heldPackage = cached != null ? cached.heldPackage() : null;
        String heldDuration = cached != null ? cached.heldDuration() : null;
        String downsellPackage = cached != null ? cached.downsellPackage() : null;
        String downsellDuration = cached != null ? cached.downsellDuration() : null;
        BigDecimal downsellPrice = cached != null ? cached.downsellPrice() : null;
        Set<String> rejectedDurations = new LinkedHashSet<>();
        if (cached != null && cached.rejectedDurations() != null) {
            rejectedDurations.addAll(cached.rejectedDurations());
        }
        Set<String> rejectedPackages = new LinkedHashSet<>();
        if (cached != null && cached.rejectedPackages() != null) {
            rejectedPackages.addAll(cached.rejectedPackages());
        }
        String objectionStage = cached != null && cached.objectionStage() != null ? cached.objectionStage() : "NONE";
        String telemetrySubtype = cached != null ? cached.telemetrySubtype() : null;
        boolean neurologicalRisk = cached != null && cached.neurologicalRisk();

        String preferredRegion = cached != null ? cached.preferredRegion() : null;
        Set<String> mentionedPackages = new LinkedHashSet<>();
        if (!hasAssistantTurnInHistory && cached != null && cached.mentionedPackages() != null) {
            mentionedPackages.addAll(cached.mentionedPackages());
        }
        Set<String> quotedPackages = new LinkedHashSet<>();
        if (!hasAssistantTurnInHistory && cached != null && cached.quotedPackages() != null) {
            quotedPackages.addAll(cached.quotedPackages());
        }
        int alternativeRequestCount = 0;
        boolean customerCorrectionDetected = cached != null && cached.customerCorrectionDetected();
        String rejectedWrongOffer = cached != null ? cached.rejectedWrongOffer() : null;
        List<String> lastBotBubbleHashes = new ArrayList<>();
        if (!hasAssistantTurnInHistory && cached != null && cached.lastBotBubbleHashes() != null) {
            lastBotBubbleHashes.addAll(cached.lastBotBubbleHashes());
        }
        List<UnresolvedCustomerRequest> outstandingRequests = new ArrayList<>();
        if (!hasAssistantTurnInHistory && cached != null && cached.outstandingRequests() != null) {
            outstandingRequests.addAll(cached.outstandingRequests());
        }
        String lastAssistantMessage = !hasAssistantTurnInHistory && cached != null ? cached.lastAssistantMessage() : null;
        Set<String> confirmedScopes = new LinkedHashSet<>();
        if (!hasAssistantTurnInHistory && cached != null && cached.confirmedScopes() != null) {
            confirmedScopes.addAll(cached.confirmedScopes());
        }
        Set<String> forbiddenScopes = new LinkedHashSet<>();
        if (!hasAssistantTurnInHistory && cached != null && cached.forbiddenScopes() != null) {
            forbiddenScopes.addAll(cached.forbiddenScopes());
        }
        Set<String> apologizedErrorIds = new LinkedHashSet<>();
        if (cached != null && cached.apologizedErrorIds() != null) {
            apologizedErrorIds.addAll(cached.apologizedErrorIds());
        }
        String activeErrorId = cached != null ? cached.activeErrorId() : null;
        List<CustomerCareNoteArtifact> customerCareNotes = new ArrayList<>();
        if (cached != null && cached.customerCareNotes() != null) {
            customerCareNotes.addAll(cached.customerCareNotes());
        }
        List<String> scopeSupersessionEvents = new ArrayList<>();
        if (cached != null && cached.scopeSupersessionEvents() != null) {
            scopeSupersessionEvents.addAll(cached.scopeSupersessionEvents());
        }
        boolean possibleCorrection = false;
        boolean prevTurnWasAssistant = false;
        boolean conditionTimeAnsweredInHistory = false;
        int turnIdx = 0;

        for (ConversationTurn turn : history) {
            if (turn == null || turn.content() == null || turn.content().isBlank()) {
                turnIdx++;
                continue;
            }
            String role = turn.role() != null ? turn.role().strip().toLowerCase(Locale.ROOT) : "user";
            String content = turn.content().strip();
            String lower = Normalizer.normalize(content, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);

            // Check optional test context overrides in history
            if (lower.contains("docs_has_feedback=true")) docsFeedback = true;
            if (lower.contains("docs_has_feedback=false")) docsFeedback = false;
            if (lower.contains("docs_has_private_room=true")) docsPrivateRoom = true;
            if (lower.contains("docs_has_private_room=false")) docsPrivateRoom = false;
            if (lower.contains("docs_has_relaxation_benefit=false")) docsRelaxation = false;

            if ("assistant".equals(role) || "bot".equals(role)) {
                greetingSent = true;
                introSent = true;
                usedTemplates.add("OPENING_GREETING_1");
                usedTemplates.add("OPENING_INTRO_2");
                appendBubbleHashes(lastBotBubbleHashes, content);
                if (prevTurnWasAssistant && lastAssistantMessage != null && !lastAssistantMessage.isBlank()) {
                    lastAssistantMessage = lastAssistantMessage + "\n" + content;
                } else {
                    lastAssistantMessage = content;
                }
                prevTurnWasAssistant = true;
                boolean resolvedFaqHere = markBotTurnAgainstOutstandingRequests(outstandingRequests, lastAssistantMessage);

                Matcher pm = PRICE_NUMBER_PATTERN.matcher(content);
                boolean botQuotedPriceHere = false;
                BigDecimal turnPriceVal = null;
                while (pm.find()) {
                    String rawPrice = pm.group().strip();
                    pricesShown.add(rawPrice);
                    botQuotedPriceHere = true;
                    BigDecimal parsed = parsePriceToBigDecimal(rawPrice);
                    if (parsed != null) {
                        turnPriceVal = parsed;
                    }
                }

                String turnDuration = extractDurationFromText(lower);
                boolean turnHasOptionUnit = false;
                Matcher om = OPTION_UNIT_PATTERN.matcher(content);
                while (om.find()) {
                    recommendedOptions.add(om.group(1).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip());
                    turnHasOptionUnit = true;
                }

                String turnPkgDisplay = extractPackageDisplayFromBotText(lower, turnDuration);
                String turnPkgCanonical = toCanonicalPackageCode(turnPkgDisplay, turnDuration);

                for (String mentionedCanon : extractAllCanonicalPackagesFromBotText(lower, turnDuration)) {
                    mentionedPackages.add(mentionedCanon);
                    if (botQuotedPriceHere) {
                        quotedPackages.add(mentionedCanon);
                    }
                }
                if (turnPkgCanonical != null) {
                    mentionedPackages.add(turnPkgCanonical);
                    if (botQuotedPriceHere) {
                        quotedPackages.add(turnPkgCanonical);
                    }
                }

                if (turnPkgDisplay != null && (botQuotedPriceHere || knownRecommendedPackage == null || !resolvedFaqHere)) {
                    knownRecommendedPackage = turnPkgDisplay;
                }

                List<PackageCatalogService.PackageSummary> activeRegionCatalog =
                        PackageCatalogService.filterByRegion(fullCatalog, preferredRegion);

                // Rule: extract_price_quote_history & downsell_detection_from_bot
                if (botQuotedPriceHere) {
                    String prevHeldPkg = heldPackage;
                    String prevHeldDur = heldDuration;
                    BigDecimal prevQuotedPrice = lastQuotedPrice;

                    boolean isDownsellBubble = (objectionCount > 0 || explicitDownsellRequest
                            || lower.contains("gọn chi phí") || lower.contains("gói ngắn")
                            || lower.contains("chi phí nhẹ hơn") || lower.contains("lăn tăn chủ yếu về chi phí"))
                            && ((prevHeldDur != null && turnDuration != null && parseDurationInt(turnDuration) < parseDurationInt(prevHeldDur))
                                || (prevQuotedPrice != null && turnPriceVal != null && turnPriceVal.compareTo(prevQuotedPrice) < 0));

                    if (isDownsellBubble) {
                        downsellOffered = true;
                        packageRejectionDetected = true;
                        if (prevHeldDur != null
                                && !prevHeldDur.equals(turnDuration)
                                && !hasAlternativePackageWithSameDuration(activeRegionCatalog, prevHeldDur, prevHeldPkg)) {
                            rejectedDurations.add(prevHeldDur);
                        }
                        if (prevHeldPkg != null) {
                            rejectedPackages.add(prevHeldPkg);
                        }
                        downsellPackage = turnPkgCanonical != null ? turnPkgCanonical : turnPkgDisplay;
                        downsellDuration = turnDuration;
                        downsellPrice = turnPriceVal;
                        heldPackage = downsellPackage;
                        heldDuration = downsellDuration;
                        lastQuotedPackage = downsellPackage;
                        lastQuotedDuration = downsellDuration;
                        lastQuotedPrice = turnPriceVal != null ? turnPriceVal : prevQuotedPrice;
                        lastQuotePackage = turnPkgDisplay != null ? turnPkgDisplay : downsellPackage;
                        objectionStage = "DOWNSELL_OFFERED";
                    } else {
                        if (turnPkgCanonical != null) {
                            lastQuotedPackage = turnPkgCanonical;
                            heldPackage = turnPkgCanonical;
                        } else if (turnPkgDisplay != null) {
                            lastQuotedPackage = turnPkgDisplay;
                            heldPackage = turnPkgDisplay;
                        }
                        if (turnDuration != null) {
                            lastQuotedDuration = turnDuration;
                            heldDuration = turnDuration;
                        }
                        if (turnPriceVal != null) {
                            lastQuotedPrice = turnPriceVal;
                        }
                        if (turnPkgDisplay != null) {
                            lastQuotePackage = turnPkgDisplay;
                        }
                    }
                } else if (turnPkgCanonical != null && heldPackage == null) {
                    heldPackage = turnPkgCanonical;
                    if (turnDuration != null) {
                        heldDuration = turnDuration;
                    }
                }

                // Rule: trial_invite_detection_from_bot
                if (TRIAL_INVITE_BOT_PATTERN.matcher(lower).matches()) {
                    trialInviteOffered = true;
                    lastQuestionIntent = "INVITE_ONE_SESSION_TRIAL";
                    usedQuestionIntents.add("INVITE_ONE_SESSION_TRIAL");
                    usedTemplates.add("VALUE_ANGLE_2");
                    if ("NONE".equals(objectionStage) || "VALUE_DEFENSE".equals(objectionStage)) {
                        objectionStage = "TRIAL_INVITED";
                    }
                }
                if ((botQuotedPriceHere && (content.contains("\n") || lower.contains("điều chỉnh lực") || lower.contains("chăm sóc êm ái")))
                        || (!botQuotedPriceHere && !pricesShown.isEmpty()
                            && (lower.contains("điều chỉnh lực") || lower.contains("chăm sóc êm ái") || lower.contains("thả lỏng thư giãn")))) {
                    usedTemplates.add("VALUE_ANGLE_1");
                }

                if (content.contains("?")) {
                    int qIdx = content.lastIndexOf('?');
                    int startSent = Math.max(
                            content.lastIndexOf('.', qIdx),
                            Math.max(content.lastIndexOf('!', qIdx), content.lastIndexOf('\n', qIdx))
                    );
                    lastQuestion = content.substring(startSent >= 0 ? startSent + 1 : 0, qIdx + 1).strip();
                    if (cached == null) {
                        String inferredSlot = inferPendingQuestionSlot(lastQuestion);
                        if (inferredSlot != null) {
                            pendingQuestionSlotL = inferredSlot;
                        }
                    }
                }

                if (lower.contains("lâu chưa") || lower.contains("mới gần đây")
                        || lower.contains("gần đây hay") || lower.contains("hay đã kéo dài")
                        || lower.contains("mới xuất hiện") || lower.contains("mấy hôm nay")
                        || lower.contains("thời điểm nào") || lower.contains("vận động/tập luyện")
                        || lower.contains("tự nhiên bị mỏi hay")
                        || lower.contains("ngồi lâu hay vận động")
                        || lower.contains("lâu rồi") || lower.contains("mới bị")
                        || lower.contains("máy tính") || lower.contains("bao lâu")
                        || lower.contains("bắt đầu từ khi nào") || lower.contains("từ khi nào")
                        || lower.contains("thường xảy ra") || lower.contains("tần suất")
                        || (lower.contains("mỏi vùng nào") && !knownSymptoms.isEmpty())) {
                    conditionAskedCount++;
                    lastQuestionIntent = "ASK_CONDITION";
                    usedQuestionIntents.add("ASK_CONDITION");
                } else if (lower.contains("mỏi vùng nào")) {
                    lastQuestionIntent = "ASK_INITIAL_NEED";
                    usedQuestionIntents.add("ASK_INITIAL_NEED");
                }
                if (lower.contains("lực nhẹ") || lower.contains("lực vừa") || lower.contains("lực mạnh")) {
                    preferenceAskedCount++;
                    lastQuestionIntent = "ASK_PRESSURE_PREFERENCE";
                    usedQuestionIntents.add("ASK_PRESSURE_PREFERENCE");
                    usedTemplates.add("ACKNOWLEDGE_AND_ASK_PREFERENCE");
                }
                if ((lower.contains("quan tâm") || lower.contains("đang hỏi") || lower.contains("nhóm dịch vụ"))
                        && lower.contains("hay") && lower.contains("?") && !botQuotedPriceHere
                        && !lower.contains("phút hay") && !lower.contains("phút và")) {
                    clarifyServiceTypeCount++;
                    clarificationCount++;
                    lastQuestionIntent = "CLARIFY_SERVICE_TYPE";
                    usedQuestionIntents.add("CLARIFY_SERVICE_TYPE");
                    lastBotAction = "CLARIFY_SERVICE_DURATION";
                    stage = ConversationStage.PRICE_CLARIFY;
                } else if ((lower.contains("phút và ") || lower.contains("phút hay ") || lower.contains("gửi mức giá gói nào"))
                        && !botQuotedPriceHere) {
                    clarifyDurationCount++;
                    clarificationCount++;
                    lastQuestionIntent = "CLARIFY_DURATION";
                    usedQuestionIntents.add("CLARIFY_DURATION");
                    lastBotAction = "CLARIFY_SERVICE_DURATION";
                    stage = ConversationStage.PRICE_CLARIFY;
                }

                if (lower.contains("vừa gửi ở tin trên") || lower.contains("tin nhắn trên")) {
                    priceRepeatGuardCount++;
                }
                if ((lower.contains("xin lỗi") || lower.contains("nhầm")) && activeErrorId != null && !activeErrorId.isBlank()) {
                    apologizedErrorIds.add(activeErrorId);
                }
                if (OPERATIONAL_CARE_PROMISE_PATTERN.matcher(content).find()
                        && (!confirmedScopes.isEmpty() || !knownBodyAreas.isEmpty())) {
                    List<String> noteScopes = !confirmedScopes.isEmpty()
                            ? new ArrayList<>(confirmedScopes)
                            : new ArrayList<>(knownBodyAreas);
                    String noteTxt = content.strip();
                    boolean exists = customerCareNotes.stream().anyMatch(n -> n != null && noteTxt.equals(n.noteText()));
                    if (!exists) {
                        customerCareNotes.add(new CustomerCareNoteArtifact(
                                pageId != null ? pageId.toString() : "default",
                                conversationId != null ? conversationId : (senderId != null ? senderId : "session"),
                                "turn-" + turnIdx,
                                Collections.unmodifiableList(noteScopes),
                                extractCareActorFromText(lower),
                                noteTxt,
                                java.time.Instant.now().toString()
                        ));
                    }
                }

                if (lower.contains("băn khoăn của mình về chi phí") || lower.contains("cân nhắc chi phí") || lower.contains("cân nhắc về chi phí")) {
                    usedTemplates.add("PRICE_OBJECTION_FIRST_TIME");
                    usedTemplates.add("VALUE_ANGLE_2");
                    lastBotAction = "HANDLE_PRICE_OBJECTION_FIRST_TIME";
                    stage = ConversationStage.OBJECTION;
                    if ("NONE".equals(objectionStage)) {
                        objectionStage = trialInviteOffered ? "TRIAL_INVITED" : "VALUE_DEFENSE";
                    }
                } else if (lower.contains("vẫn đang cân nhắc")
                        || lower.contains("gọn chi phí cho lần đầu")
                        || lower.contains("chi phí gọn trước")
                        || lower.contains("mức chi phí nhẹ hơn")
                        || lower.contains("lăn tăn chủ yếu về chi phí")) {
                    usedTemplates.add("PRICE_OBJECTION_REPEAT");
                    lastBotAction = "HANDLE_PRICE_OBJECTION_REPEAT";
                    stage = ConversationStage.OBJECTION;
                    downsellOffered = true;
                    objectionStage = "DOWNSELL_OFFERED";
                } else if (lower.contains("đơn giá") || lower.contains("tính theo phút") || lower.contains("trên mỗi phút")) {
                    usedTemplates.add("EXPLAIN_UNIT_VALUE");
                    lastBotAction = "EXPLAIN_UNIT_VALUE";
                    stage = ConversationStage.COMPARISON;
                } else if (botQuotedPriceHere) {
                    lastBotAction = "ANSWER_PRICE_ONCE";
                    stage = ConversationStage.PRICE_QUOTED;
                    recommendationCount++;
                } else if (resolvedFaqHere) {
                    lastBotAction = "SERVICE_DETAIL_AND_FAQ_QA";
                } else if (turnPkgDisplay != null || turnHasOptionUnit || lower.contains("em gợi ý") || lower.contains("hợp hơn")) {
                    lastBotAction = "RECOMMEND_ONE_PACKAGE";
                    stage = ConversationStage.RECOMMENDATION;
                    recommendationCount++;
                }
            } else {
                // User turn analysis
                prevTurnWasAssistant = false;
                String rawUserTurnContent = turn.content().strip();
                VietnameseScopeSignalExtractor.ScopeSignalResult scopeSignal =
                        VietnameseScopeSignalExtractor.analyze(rawUserTurnContent, domainKeywords);
                String normalizedTurnContent = VietnameseQueryNormalizer.normalizeForClassification(content);
                if (normalizedTurnContent != null && !normalizedTurnContent.isBlank()) {
                    content = normalizedTurnContent.strip();
                    lower = Normalizer.normalize(content, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
                }
                String activePkgForContext = heldPackage != null ? heldPackage : knownRecommendedPackage;
                recordUserTurnIntoOutstandingRequests(
                        outstandingRequests,
                        content,
                        lastAssistantMessage,
                        activePkgForContext,
                        turnIdx
                );
                preferredRegion = detectPreferredRegion(lower, preferredRegion);
                boolean isMismatchHere = SentimentIntentLayer.isEntityMismatchCorrection(content);
                String cleanContent = SentimentIntentLayer.stripMismatchCritiqueClause(content);
                String cleanLower = Normalizer.normalize(cleanContent, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);

                if (PrimaryActionRouter.isPriceInquiryMessage(content)
                        || lower.matches("(?isU).*\\b(giá|bảng\\s+giá|bao\\s+nhiêu|chi\\s+phí|phí|cost|fee|giá\\s+sao|giá\\s+vậy)\\b.*")) {
                    priceRequestedInSession = true;
                }

                if (SentimentIntentLayer.hasNeurologicalRisk(content, null)) {
                    neurologicalRisk = true;
                }

                boolean isPkgRejectHere = SentimentIntentLayer.isPackageRejection(content);
                boolean isExplicitDownsellHere = SentimentIntentLayer.isExplicitDownsellRequest(content, null);
                boolean isPriceObjHere = SentimentIntentLayer.isPriceObjection(content)
                        || CleanAgentOrchestratorService.isConfirmedCustomerPriceObjection(content, !pricesShown.isEmpty() || (cached != null && cached.priceDiscussed()));
                boolean isHesitationAfterObjHere = SentimentIntentLayer.isHesitationAfterObjection(content);
                boolean isAltReqHere = SentimentIntentLayer.isAlternativePackageRequest(content);
                boolean isDetailFaqHere = SentimentIntentLayer.isDetailOrFaqQuestion(content, lastAssistantMessage, activePkgForContext);
                boolean isComprehensionFailHere = SentimentIntentLayer.isComprehensionFailureMessage(content);
                boolean isPushbackHere = SentimentIntentLayer.isNegationOrPushbackOnAssumption(content)
                        || scopeSignal.explicitCorrectionGroupD()
                        || scopeSignal.possibleCorrection();

                if (isAltReqHere) {
                    alternativeRequestCount++;
                }

                if (scopeSignal.explicitCorrectionGroupD()) {
                    possibleCorrection = false;
                    customerCorrectionDetected = true;
                    if (scopeSignal.errorId() != null && !scopeSignal.errorId().isBlank()) {
                        activeErrorId = scopeSignal.errorId();
                    }
                    for (String negScope : scopeSignal.forbiddenScopes()) {
                        forbiddenScopes.add(negScope);
                        confirmedScopes.remove(negScope);
                        knownBodyAreas.remove(negScope);
                        if ("đầu gối".equals(negScope) || "khớp gối".equals(negScope)) {
                            confirmedScopes.remove("gối");
                            knownBodyAreas.remove("gối");
                            knownSymptoms.removeIf(s -> s.contains("gối"));
                        } else {
                            knownSymptoms.removeIf(s -> s.contains(negScope));
                        }
                    }
                    for (String posScope : scopeSignal.confirmedScopes()) {
                        forbiddenScopes.remove(posScope);
                        confirmedScopes.add(posScope);
                        addNormalizedAtomicScopes(knownBodyAreas, posScope);
                    }
                    lastIntent = "SCOPE_CORRECTION_PUSHBACK";
                } else if (scopeSignal.possibleCorrection()) {
                    possibleCorrection = true;
                    customerCorrectionDetected = true;
                    lastIntent = "COLLOQUIAL_CORRECTION_PUSHBACK";
                } else {
                    possibleCorrection = false;
                }

                if (isMismatchHere) {
                    customerCorrectionDetected = true;
                    if (heldPackage != null) rejectedPackages.add(heldPackage);
                    if (downsellPackage != null) rejectedPackages.add(downsellPackage);
                    if (lastQuotedPackage != null) rejectedPackages.add(lastQuotedPackage);
                    String wrongOffer = SentimentIntentLayer.extractRejectedWrongOffer(content);
                    if (wrongOffer != null && !wrongOffer.isBlank()) {
                        rejectedWrongOffer = wrongOffer.toLowerCase(Locale.ROOT).strip();
                        rejectedPackages.add(rejectedWrongOffer);
                        String canonicalWrong = toCanonicalPackageCode(wrongOffer, null);
                        if (canonicalWrong != null) {
                            rejectedPackages.add(canonicalWrong);
                        }
                    }
                    heldPackage = null;
                    heldDuration = null;
                    downsellPackage = null;
                    downsellDuration = null;
                    downsellPrice = null;
                    downsellOffered = false;
                    lastQuotedPackage = null;
                    lastQuotedDuration = null;
                    lastQuotedPrice = null;
                    knownRecommendedPackage = null;
                    lastQuotePackage = null;
                    knownServiceFamily = null;
                    lastIntent = "ENTITY_MISMATCH_CORRECTION";
                    if (SentimentIntentLayer.hasHarshPronouns(content) || PrimaryActionRouter.isFrustrationMessage(content)) {
                        frustrationCount++;
                        lastEmotion = "ANGER";
                        lastSentiment = "NEGATIVE";
                    }
                    // Reset polluted scopes/needs so true scopes from cleanContent take precedence
                    List<String> correctedScopes = extractTargetScopes(cleanContent, domainKeywords);
                    if (!correctedScopes.isEmpty()) {
                        knownBodyAreas.clear();
                        knownBodyAreas.addAll(correctedScopes);
                    }
                    String correctedProb = extractProblemOrNeedPhrase(cleanContent, domainKeywords);
                    if (correctedProb != null) {
                        problemOrNeed = correctedProb;
                    }
                } else if (isPkgRejectHere) {
                    packageRejectionDetected = true;
                    String mentionedDur = SentimentIntentLayer.extractDurationNumber(lower);
                    if (mentionedDur != null) {
                        rejectedDurations.add(mentionedDur);
                        if (heldPackage != null) {
                            rejectedPackages.add(toCanonicalPackageCode(heldPackage, mentionedDur));
                        }
                    } else {
                        if (heldDuration != null) rejectedDurations.add(heldDuration);
                        if (heldPackage != null) rejectedPackages.add(heldPackage);
                    }
                }
                if (isExplicitDownsellHere) {
                    explicitDownsellRequest = true;
                }

                boolean isStyleComplaintHere = SentimentIntentLayer.isConversationStyleComplaint(content)
                        || SentimentIntentLayer.isWordingOrExpressionFeedback(content)
                        || (PrimaryActionRouter.isFrustrationMessage(content) && !isPriceObjHere && !isExplicitDownsellHere);

                // Rule: extract_objection_history_corrected (chỉ tăng khi có từ khóa chê giá đắt/cao, cấm tăng khi phàn nàn cách nói chuyện/lặp lời)
                if (!isMismatchHere && !isStyleComplaintHere && downsellOffered
                        && (isPriceObjHere || isHesitationAfterObjHere || SentimentIntentLayer.isStrongRejection(content))
                        && !isAltReqHere && !isDetailFaqHere) {
                    objectionCount++;
                    lastIntent = "STAGE_3_GRACEFUL_EXIT";
                    objectionStage = "STAGE_3_GRACEFUL_EXIT";
                } else if (!isMismatchHere && !isStyleComplaintHere && (isPriceObjHere || isExplicitDownsellHere || isPkgRejectHere
                        || (objectionCount >= 1 && !downsellOffered && isHesitationAfterObjHere))
                        && (!pricesShown.isEmpty() || (cached != null && cached.priceDiscussed()))) {
                    objectionCount++;
                    lastIntent = isExplicitDownsellHere ? "EXPLICIT_DOWNSELL_REQUEST"
                            : (isPkgRejectHere ? "PACKAGE_REJECTION"
                                : (isHesitationAfterObjHere && !isPriceObjHere ? "HESITATION_AFTER_OBJECTION" : "PRICE_OBJECTION"));
                    usedTemplates.add("PRICE_OBJECTION_FIRST_TIME");
                } else if (!isMismatchHere && isPushbackHere) {
                    if (scopeSignal.explicitCorrectionGroupD()) {
                        lastIntent = "SCOPE_CORRECTION_PUSHBACK";
                    } else if (scopeSignal.possibleCorrection()) {
                        lastIntent = "COLLOQUIAL_CORRECTION_PUSHBACK";
                    } else {
                        lastIntent = "PUSHBACK_ON_ASSUMPTION";
                    }
                } else if (!isMismatchHere && isAltReqHere) {
                    lastIntent = "ALTERNATIVE_PACKAGE_REQUEST";
                } else if (!isMismatchHere && isDetailFaqHere) {
                    lastIntent = "DETAIL_OR_FAQ_QUESTION";
                } else if (!isMismatchHere && isComprehensionFailHere) {
                    lastIntent = "COMPREHENSION_FAILURE";
                } else if (!isMismatchHere && PrimaryActionRouter.isFrustrationMessage(content)) {
                    frustrationCount++;
                    lastEmotion = "FRUSTRATION";
                    lastSentiment = "NEGATIVE";
                    lastIntent = "FRUSTRATION";
                } else if (!isMismatchHere && SentimentIntentLayer.isTrialAcceptance(content, null)) {
                    lastIntent = "TRIAL_ACCEPTANCE";
                    objectionStage = "BOOKED";
                    packageInterestAcceptedL = true;
                    if ("NONE".equals(bookingStatusL)) {
                        bookingStatusL = "COLLECTING_INFO";
                    }
                } else if (!isMismatchHere && PrimaryActionRouter.isAcknowledgementMessage(content)) {
                    ackCount++;
                    lastIntent = "ACKNOWLEDGEMENT";
                } else if (!isMismatchHere && PrimaryActionRouter.isUnitPriceComparisonMessage(content)) {
                    lastIntent = "PRICE_COMPARISON";
                }

                if (!isPushbackHere && !isPkgRejectHere && hasExplicitPackageSelectionSignal(cleanContent)) {
                    packageInterestAcceptedL = true;
                    if ("NONE".equals(bookingStatusL)) {
                        bookingStatusL = "COLLECTING_INFO";
                    }
                }
                if (isPkgRejectHere || SentimentIntentLayer.isStrongRejection(content)) {
                    packageInterestAcceptedL = false;
                    bookingIntentConfirmedL = false;
                    bookingStatusL = "NONE";
                    visitTimeL = null;
                }

                if (!scopeSignal.possibleCorrection()) {
                    Matcher symM = SYMPTOM_TOKEN_PATTERN.matcher(cleanContent);
                    boolean hadSymptomInTurn = false;
                    while (symM.find()) {
                        knownSymptoms.add(symM.group(1).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip());
                        hadSymptomInTurn = true;
                    }
                    if (hadSymptomInTurn) {
                        customerSymptomTurns++;
                    }

                    if (!scopeSignal.explicitCorrectionGroupD()) {
                        for (String posScope : scopeSignal.confirmedScopes()) {
                            if (forbiddenScopes.remove(posScope)) {
                                scopeSupersessionEvents.add("SUPERSEDED:" + posScope);
                            }
                            if (("đầu gối".equals(posScope) || "khớp gối".equals(posScope)) && forbiddenScopes.remove("gối")) {
                                scopeSupersessionEvents.add("SUPERSEDED:gối");
                            }
                            confirmedScopes.add(posScope);
                        }
                        for (String scope : extractTargetScopes(cleanContent, domainKeywords)) {
                            if ("gối".equals(scope) && (forbiddenScopes.contains("đầu gối") || forbiddenScopes.contains("khớp gối"))) {
                                continue;
                            }
                            if (forbiddenScopes.contains(scope)) {
                                continue;
                            }
                            knownBodyAreas.add(scope);
                            if ("gối".equals(scope) && (confirmedScopes.contains("đầu gối") || confirmedScopes.contains("khớp gối"))) {
                                continue;
                            }
                            confirmedScopes.add(scope);
                        }
                        purgeNegatedSymptomsAndScopes(content, cleanContent, knownSymptoms, knownBodyAreas, confirmedScopes, forbiddenScopes, domainKeywords);
                    }
                }

                if (!isPushbackHere && hasConditionTimeOrProgressionSignal(cleanContent)) {
                    conditionTimeAnsweredInHistory = true;
                    if (cached == null && (SLOT_ONSET.equals(pendingQuestionSlotL) || SLOT_FREQUENCY.equals(pendingQuestionSlotL) || SLOT_EPISODE_DURATION.equals(pendingQuestionSlotL))) {
                        pendingQuestionSlotL = null;
                    }
                }

                Matcher sevM = SEVERITY_TOKEN_PATTERN.matcher(cleanContent);
                if (sevM.find()) {
                    String rawSev = sevM.group(1).toLowerCase(Locale.ROOT).strip();
                    knownSeverity = "thi thoải".equals(rawSev) ? "thi thoảng" : rawSev;
                }

                Matcher durM = PROBLEM_DURATION_TOKEN_PATTERN.matcher(cleanContent);
                if (durM.find()) {
                    knownDurationOfProblem = durM.group(1).toLowerCase(Locale.ROOT).strip();
                }

                Matcher epDurM = EPISODE_DURATION_TOKEN_PATTERN.matcher(cleanContent);
                if (epDurM.find()) {
                    knownEpisodeDurationL = epDurM.group(1).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
                }

                Matcher freqM = FREQUENCY_TOKEN_PATTERN.matcher(cleanContent);
                String lastFreq = null;
                while (freqM.find()) {
                    lastFreq = freqM.group(1);
                }
                if (lastFreq != null) {
                    knownFrequencyL = normalizeFrequency(lastFreq);
                }
                Matcher distM = SYMPTOM_DISTRIBUTION_TOKEN_PATTERN.matcher(cleanContent);
                if (distM.find()) {
                    knownSymptomDistributionL = distM.group(1).toLowerCase(Locale.ROOT).strip();
                }

                if (!isPushbackHere && (packageInterestAcceptedL || "COLLECTING_INFO".equals(bookingStatusL) || "CONFIRM_BOOKING_TIME".equals(lastQuestionIntent))) {
                    Matcher vtM = VISIT_TIME_TOKEN_PATTERN.matcher(cleanContent);
                    if (vtM.find()) {
                        visitTimeL = vtM.group(1).replaceAll("\\s+", " ").strip();
                        bookingIntentConfirmedL = true;
                        if (cached == null && SLOT_BOOKING_TIME.equals(pendingQuestionSlotL)) {
                            pendingQuestionSlotL = null;
                        }
                    }
                }
                Matcher ciM = CONTACT_INFO_TOKEN_PATTERN.matcher(cleanContent);
                if (ciM.find()) {
                    contactInfoL = ciM.group(1).strip();
                }
                if (bookingIntentConfirmedL && contactInfoL != null && !contactInfoL.isBlank()
                        && !"CONFIRMED".equalsIgnoreCase(bookingStatusL)
                        && !BookingStatus.isSixStateCanonical(bookingStatusL)) {
                    bookingStatusL = BookingStatus.AWAITING_AVAILABILITY_CHECK.name();
                }

                Matcher trigM = TRIGGER_TOKEN_PATTERN.matcher(cleanContent);
                if (trigM.find()) {
                    knownTrigger = trigM.group(1).toLowerCase(Locale.ROOT).strip();
                }

                Matcher prefM = PREFERENCE_PATTERN.matcher(cleanContent);
                if (prefM.find()) {
                    preferenceAttr = prefM.group(1).strip();
                    knownPreferredPressure = preferenceAttr;
                }

                Matcher optM = OPTION_UNIT_PATTERN.matcher(cleanContent);
                if (optM.find() && !PrimaryActionRouter.isUnitPriceComparisonMessage(cleanContent)) {
                    preferredOption = optM.group(1).strip();
                }

                String resolvedFam = resolveServiceFamilyFromText(cleanLower, knownBodyAreas, domainKeywords);
                if (resolvedFam != null) {
                    knownServiceFamily = resolvedFam;
                    itemInterest = resolvedFam;
                }

                String extractedProblem = extractProblemOrNeedPhrase(cleanContent, domainKeywords);
                if (extractedProblem != null) {
                    if (isMismatchHere || problemOrNeed == null || problemOrNeed.isBlank()) {
                        problemOrNeed = extractedProblem;
                    } else if (!problemOrNeed.contains(extractedProblem)) {
                        problemOrNeed = problemOrNeed + ", " + extractedProblem;
                    }
                } else if ((problemOrNeed == null || problemOrNeed.isBlank())
                        && !knownBodyAreas.isEmpty()
                        && cleanLower.contains("vùng")) {
                    problemOrNeed = "mỏi vùng " + String.join(" ", knownBodyAreas);
                }
            }
            turnIdx++;
        }

        if (cached != null) {
            objectionCount = Math.max(objectionCount, cached.objectionCount());
            downsellOffered = downsellOffered || cached.downsellOffered();
            ackCount = Math.max(ackCount, cached.ackCount());
            conditionAskedCount = Math.max(conditionAskedCount, cached.conditionAskedCount());
            preferenceAskedCount = Math.max(preferenceAskedCount, cached.preferenceAskedCount());
            clarifyServiceTypeCount = Math.max(clarifyServiceTypeCount, cached.clarifyServiceTypeCount());
            clarifyDurationCount = Math.max(clarifyDurationCount, cached.clarifyDurationCount());
            frustrationCount = Math.max(frustrationCount, cached.frustrationCount());
            priceRepeatGuardCount = Math.max(priceRepeatGuardCount, cached.priceRepeatGuardCount());
            priceRequestedInSession = priceRequestedInSession || cached.priceRequestedInSession();
            alternativeRequestCount = Math.max(alternativeRequestCount, cached.alternativeRequestCount());
            if (outstandingRequests.isEmpty() && cached.outstandingRequests() != null && !cached.outstandingRequests().isEmpty()) {
                outstandingRequests.addAll(cached.outstandingRequests());
            }
            if ((lastAssistantMessage == null || lastAssistantMessage.isBlank()) && cached.lastAssistantMessage() != null) {
                lastAssistantMessage = cached.lastAssistantMessage();
            }
        }

        if (knownServiceFamily == null && !knownBodyAreas.isEmpty()) {
            knownServiceFamily = resolveServiceFamilyFromText("", knownBodyAreas, domainKeywords);
        }
        boolean serviceFamilyKnown = knownServiceFamily != null && !knownServiceFamily.isBlank();

        boolean isCorrectionTurnNow = possibleCorrection
                || "SCOPE_CORRECTION_PUSHBACK".equals(lastIntent)
                || "COLLOQUIAL_CORRECTION_PUSHBACK".equals(lastIntent)
                || "PUSHBACK_ON_ASSUMPTION".equals(lastIntent);

        boolean conditionClarified = !isCorrectionTurnNow && ((cached != null && cached.conditionClarified())
                || conditionTimeAnsweredInHistory);

        boolean packageRecommended = !recommendedOptions.isEmpty()
                || recommendationCount > 0
                || (cached != null && cached.packageRecommended());

        if (knownRecommendedPackage == null && packageRecommended) {
            knownRecommendedPackage = inferDefaultPackageName(knownServiceFamily, knownBodyAreas, recommendedOptions);
        }
        if (heldPackage == null && knownRecommendedPackage != null) {
            heldDuration = extractDurationFromText(knownRecommendedPackage);
            heldPackage = toCanonicalPackageCode(knownRecommendedPackage, heldDuration);
        }

        boolean priceDiscussed = !pricesShown.isEmpty() || (cached != null && cached.priceDiscussed());
        List<PackageCatalogService.PackageSummary> availablePackageCatalog =
                PackageCatalogService.filterByRegion(fullCatalog, preferredRegion);
        if (availablePackageCatalog.isEmpty() && cached != null && cached.availablePackageCatalog() != null) {
            availablePackageCatalog = cached.availablePackageCatalog();
        }

        return new ConversationState(
                greetingSent,
                introSent,
                stage,
                lastIntent,
                lastQuestion,
                lastQuestionIntent,
                Collections.unmodifiableSet(new LinkedHashSet<>(usedQuestionIntents)),
                lastBotAction,
                new KnownFacts(itemInterest, problemOrNeed, preferenceAttr, preferredOption, preferredTime),
                priceDiscussed,
                Collections.unmodifiableList(new ArrayList<>(pricesShown)),
                Collections.unmodifiableList(new ArrayList<>(recommendedOptions)),
                objectionCount,
                ackCount,
                clarificationCount,
                conditionAskedCount,
                preferenceAskedCount,
                clarifyServiceTypeCount,
                clarifyDurationCount,
                frustrationCount,
                ambiguousCount,
                recommendationCount,
                priceRepeatGuardCount,
                conditionClarified,
                serviceFamilyKnown,
                packageRecommended,
                priceRequestedInSession,
                Collections.unmodifiableList(new ArrayList<>(knownSymptoms)),
                Collections.unmodifiableList(new ArrayList<>(knownBodyAreas)),
                knownSeverity,
                knownDurationOfProblem,
                knownTrigger,
                knownServiceFamily,
                knownPreferredPressure,
                knownRecommendedPackage,
                lastQuotePackage,
                lastSentiment,
                lastEmotion,
                Collections.unmodifiableList(new ArrayList<>(usedTemplates)),
                humanTakeover,
                optedOut,
                downsellOffered,
                trialInviteOffered,
                explicitDownsellRequest,
                packageRejectionDetected,
                lastQuotedPackage,
                lastQuotedDuration,
                lastQuotedPrice,
                heldPackage,
                heldDuration,
                downsellPackage,
                downsellDuration,
                downsellPrice,
                Collections.unmodifiableSet(new LinkedHashSet<>(rejectedDurations)),
                Collections.unmodifiableSet(new LinkedHashSet<>(rejectedPackages)),
                objectionStage,
                telemetrySubtype,
                docsFeedback,
                docsRelaxation,
                docsPrivateRoom,
                neurologicalRisk,
                preferredRegion,
                Collections.unmodifiableList(new ArrayList<>(mentionedPackages)),
                Collections.unmodifiableList(new ArrayList<>(quotedPackages)),
                lastQuotedPrice,
                alternativeRequestCount,
                customerCorrectionDetected,
                rejectedWrongOffer,
                Collections.unmodifiableList(new ArrayList<>(availablePackageCatalog)),
                Collections.unmodifiableList(new ArrayList<>(lastBotBubbleHashes)),
                Collections.unmodifiableList(new ArrayList<>(outstandingRequests)),
                lastAssistantMessage,
                Collections.unmodifiableSet(new LinkedHashSet<>(confirmedScopes)),
                Collections.unmodifiableSet(new LinkedHashSet<>(forbiddenScopes)),
                Collections.unmodifiableSet(new LinkedHashSet<>(apologizedErrorIds)),
                activeErrorId,
                Collections.unmodifiableList(new ArrayList<>(customerCareNotes)),
                Collections.unmodifiableList(new ArrayList<>(scopeSupersessionEvents)),
                possibleCorrection,
                cached != null && cached.packageExplicitlySelected(), cached != null ? cached.selectedPackageId() : null,
                knownFrequencyL, knownSymptomDistributionL, pendingQuestionSlotL,
                knownEpisodeDurationL, packageInterestAcceptedL, bookingIntentConfirmedL, visitTimeL, contactInfoL, bookingStatusL,
                cached != null ? cached.selectedProductId() : null,
                cached != null ? cached.bookingDate() : null,
                cached != null ? cached.bookingTime() : null,
                cached != null && cached.bookingStatusEnum() != null
                        ? cached.bookingStatusEnum()
                        : BookingStatus.fromLegacyString(bookingStatusL, cached != null ? cached.selectedProductId() : null, visitTimeL)
        );
    }

    public ConversationState withDocFlags(boolean hasFeedback, boolean hasRelaxation, boolean hasPrivateRoom) {
        ConversationState updated = new ConversationState(
                greetingSent, introSent, stage, lastIntent, lastQuestion, lastQuestionIntent,
                usedQuestionIntents, lastBotAction, known, priceDiscussed, pricesShown, recommendedOptions,
                objectionCount, ackCount, clarificationCount, conditionAskedCount, preferenceAskedCount,
                clarifyServiceTypeCount, clarifyDurationCount, frustrationCount, ambiguousCount,
                recommendationCount, priceRepeatGuardCount, conditionClarified, serviceFamilyKnown,
                packageRecommended, priceRequestedInSession, knownSymptoms, knownBodyAreas,
                knownSeverity, knownDurationOfProblem, knownTrigger, knownServiceFamily,
                knownPreferredPressure, knownRecommendedPackage, lastQuotePackage, lastSentiment,
                lastEmotion, usedTemplates, humanTakeover, optedOut,
                downsellOffered, trialInviteOffered, explicitDownsellRequest, packageRejectionDetected,
                lastQuotedPackage, lastQuotedDuration, lastQuotedPrice, heldPackage, heldDuration,
                downsellPackage, downsellDuration, downsellPrice, rejectedDurations, rejectedPackages,
                objectionStage, telemetrySubtype, hasFeedback, hasRelaxation, hasPrivateRoom, neurologicalRisk,
                preferredRegion, mentionedPackages, quotedPackages, heldPrice, alternativeRequestCount,
                customerCorrectionDetected, rejectedWrongOffer, availablePackageCatalog, lastBotBubbleHashes,
                outstandingRequests, lastAssistantMessage,
                confirmedScopes, forbiddenScopes, apologizedErrorIds, activeErrorId,
                customerCareNotes, scopeSupersessionEvents, possibleCorrection,
                packageExplicitlySelected, selectedPackageId,
                knownFrequency, knownSymptomDistribution, pendingQuestionSlot,
                knownEpisodeDuration, packageInterestAccepted, bookingIntentConfirmed, visitTime, contactInfo, bookingStatus,
                selectedProductId, bookingDate, bookingTime, bookingStatusEnum
        );
        ConversationState prior = ENRICHED_TO_PRIOR_MAP.get(this);
        if (prior != null && prior != this) {
            ENRICHED_TO_PRIOR_MAP.put(updated, prior.withDocFlags(hasFeedback, hasRelaxation, hasPrivateRoom));
        }
        return updated;
    }

    public ConversationState withCatalogAndRegion(
            List<PackageCatalogService.PackageSummary> catalog,
            String region
    ) {
        ConversationState updated = new ConversationState(
                greetingSent, introSent, stage, lastIntent, lastQuestion, lastQuestionIntent,
                usedQuestionIntents, lastBotAction, known, priceDiscussed, pricesShown, recommendedOptions,
                objectionCount, ackCount, clarificationCount, conditionAskedCount, preferenceAskedCount,
                clarifyServiceTypeCount, clarifyDurationCount, frustrationCount, ambiguousCount,
                recommendationCount, priceRepeatGuardCount, conditionClarified, serviceFamilyKnown,
                packageRecommended, priceRequestedInSession, knownSymptoms, knownBodyAreas,
                knownSeverity, knownDurationOfProblem, knownTrigger, knownServiceFamily,
                knownPreferredPressure, knownRecommendedPackage, lastQuotePackage, lastSentiment,
                lastEmotion, usedTemplates, humanTakeover, optedOut,
                downsellOffered, trialInviteOffered, explicitDownsellRequest, packageRejectionDetected,
                lastQuotedPackage, lastQuotedDuration, lastQuotedPrice, heldPackage, heldDuration,
                downsellPackage, downsellDuration, downsellPrice, rejectedDurations, rejectedPackages,
                objectionStage, telemetrySubtype, docsHasFeedback, docsHasRelaxationBenefit, docsHasPrivateRoom, neurologicalRisk,
                region != null ? region : preferredRegion,
                mentionedPackages, quotedPackages, heldPrice, alternativeRequestCount,
                customerCorrectionDetected, rejectedWrongOffer,
                catalog != null ? Collections.unmodifiableList(new ArrayList<>(catalog)) : availablePackageCatalog,
                lastBotBubbleHashes,
                outstandingRequests,
                lastAssistantMessage,
                confirmedScopes, forbiddenScopes, apologizedErrorIds, activeErrorId,
                customerCareNotes, scopeSupersessionEvents, possibleCorrection,
                packageExplicitlySelected, selectedPackageId,
                knownFrequency, knownSymptomDistribution, pendingQuestionSlot,
                knownEpisodeDuration, packageInterestAccepted, bookingIntentConfirmed, visitTime, contactInfo, bookingStatus,
                selectedProductId, bookingDate, bookingTime, bookingStatusEnum
        );
        ConversationState prior = ENRICHED_TO_PRIOR_MAP.get(this);
        if (prior != null && prior != this) {
            ENRICHED_TO_PRIOR_MAP.put(updated, prior.withCatalogAndRegion(catalog, region));
        }
        return updated;
    }

    public ConversationState withRetrievedChunks(
            List<ReciprocalRankFusion.FusedChunk> fusedChunks,
            List<String> domainKeywords,
            List<ConversationTurn> history
    ) {
        boolean docsFeedback = detectDocsHasFeedback(fusedChunks);
        boolean docsRelaxation = detectDocsHasRelaxationBenefit(fusedChunks);
        boolean docsPrivateRoom = detectDocsHasPrivateRoom(fusedChunks);
        if (history != null) {
            for (ConversationTurn turn : history) {
                if (turn == null || turn.content() == null) continue;
                String lower = Normalizer.normalize(turn.content().strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
                if (lower.contains("docs_has_feedback=true")) docsFeedback = true;
                if (lower.contains("docs_has_feedback=false")) docsFeedback = false;
                if (lower.contains("docs_has_private_room=true")) docsPrivateRoom = true;
                if (lower.contains("docs_has_private_room=false")) docsPrivateRoom = false;
                if (lower.contains("docs_has_relaxation_benefit=false")) docsRelaxation = false;
            }
        }
        List<PackageCatalogService.PackageSummary> fullCatalog = PackageCatalogService.extractCatalog(fusedChunks, domainKeywords);
        String reg = this.preferredRegion;
        List<PackageCatalogService.PackageSummary> regCat = PackageCatalogService.filterByRegion(fullCatalog, reg);
        if (regCat.isEmpty() && this.availablePackageCatalog != null && !this.availablePackageCatalog.isEmpty()) {
            regCat = this.availablePackageCatalog;
        }
        return this.withDocFlags(docsFeedback, docsRelaxation, docsPrivateRoom)
                .withCatalogAndRegion(regCat, reg);
    }

    /**
     * Phân tích tin nhắn hiện tại của khách hàng và kết hợp vào {@link ConversationState}
     * ngay trước khi chạy {@link PrimaryActionRouter#route}.
     */
    public ConversationState enrichWithCurrentMessage(String currentMessage, List<String> domainKeywords) {
        if (currentMessage == null || currentMessage.isBlank()) {
            return this;
        }
        String normalizedCurrent = VietnameseQueryNormalizer.normalizeForClassification(currentMessage);
        String content = (normalizedCurrent != null && !normalizedCurrent.isBlank() ? normalizedCurrent : currentMessage).strip();
        String lower = Normalizer.normalize(content, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        String nextRegion = detectPreferredRegion(lower, this.preferredRegion);
        VietnameseScopeSignalExtractor.ScopeSignalResult scopeSignal =
                VietnameseScopeSignalExtractor.analyze(currentMessage, domainKeywords);
        boolean isMismatch = SentimentIntentLayer.isEntityMismatchCorrection(content)
                // ACI V3 P1 §3.1: câu hỏi phản bác phạm vi ("sao lại cổ vai gáy?") là REJECTED_BOT_ASSUMPTION,
                // KHÔNG được đi nhánh mismatch (vốn sẽ clear vùng đã biết và bắt scope từ chính câu phản bác)
                && !scopeSignal.possibleCorrection()
                && !scopeSignal.explicitCorrectionGroupD();
        String cleanContent = SentimentIntentLayer.stripMismatchCritiqueClause(content);
        String cleanLower = Normalizer.normalize(cleanContent, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);

        Set<String> symptoms = new LinkedHashSet<>(this.knownSymptoms);
        Set<String> areas = new LinkedHashSet<>(this.knownBodyAreas);
        Set<String> nextConfirmedScopes = new LinkedHashSet<>(this.confirmedScopes != null ? this.confirmedScopes : List.of());
        Set<String> nextForbiddenScopes = new LinkedHashSet<>(this.forbiddenScopes != null ? this.forbiddenScopes : List.of());
        Set<String> nextApologizedErrors = new LinkedHashSet<>(this.apologizedErrorIds != null ? this.apologizedErrorIds : Set.of());
        String nextActiveErrorId = this.activeErrorId;
        List<CustomerCareNoteArtifact> nextCareNotes = new ArrayList<>(this.customerCareNotes != null ? this.customerCareNotes : List.of());
        List<String> nextSupersessionEvents = new ArrayList<>(this.scopeSupersessionEvents != null ? this.scopeSupersessionEvents : List.of());
        boolean nextPossibleCorrection = false;
        String severity = this.knownSeverity;
        String probDuration = this.knownDurationOfProblem;
        String trigger = this.knownTrigger;
        String serviceFam = this.knownServiceFamily;
        String prefPressure = this.knownPreferredPressure;
        String prefOption = this.known != null ? this.known.preferredOption() : null;
        String probPhrase = this.known != null ? this.known.customerProblemOrNeed() : null;
        boolean priceReq = this.priceRequestedInSession;
        String sentiment = "NEUTRAL";
        String emotion = "NONE";
        String nextHeldPkg = this.heldPackage;
        String nextHeldDur = this.heldDuration;
        String nextDownsellPkg = this.downsellPackage;
        String nextDownsellDur = this.downsellDuration;
        BigDecimal nextDownsellPrice = this.downsellPrice;
        boolean nextDownsellOffered = this.downsellOffered;
        String nextLastQuotedPkg = this.lastQuotedPackage;
        String nextLastQuotedDur = this.lastQuotedDuration;
        BigDecimal nextLastQuotedPrice = this.lastQuotedPrice;
        String recPkg = this.knownRecommendedPackage;
        String nextLastQuotePkg = this.lastQuotePackage;
        Set<String> nextRejectedPkgs = new LinkedHashSet<>(this.rejectedPackages);
        boolean nextCorrectionDetected = this.customerCorrectionDetected;
        String nextRejectedWrongOffer = this.rejectedWrongOffer;
        List<UnresolvedCustomerRequest> nextOutstanding = new ArrayList<>(
                this.outstandingRequests != null ? this.outstandingRequests : List.of()
        );
        String activePkgContext = nextHeldPkg != null ? nextHeldPkg : recPkg;
        recordUserTurnIntoOutstandingRequests(
                nextOutstanding,
                content,
                this.lastAssistantMessage,
                activePkgContext,
                nextOutstanding.size()
        );
        boolean isDetailFaqCurrent = SentimentIntentLayer.isDetailOrFaqQuestion(content, this.lastAssistantMessage, activePkgContext);
        boolean isComprehensionCurrent = SentimentIntentLayer.isComprehensionFailureMessage(content);

        if (PrimaryActionRouter.isPriceInquiryMessage(content)
                || lower.matches("(?isU).*\\b(giá|bảng\\s+giá|bao\\s+nhiêu|chi\\s+phí|phí|cost|fee|giá\\s+sao|giá\\s+vậy)\\b.*")) {
            priceReq = true;
        }

        if (isMismatch) {
            nextCorrectionDetected = true;
            if (nextHeldPkg != null) nextRejectedPkgs.add(nextHeldPkg);
            if (nextDownsellPkg != null) nextRejectedPkgs.add(nextDownsellPkg);
            if (nextLastQuotedPkg != null) nextRejectedPkgs.add(nextLastQuotedPkg);
            String wrongOffer = SentimentIntentLayer.extractRejectedWrongOffer(content);
            if (wrongOffer != null && !wrongOffer.isBlank()) {
                nextRejectedWrongOffer = wrongOffer.toLowerCase(Locale.ROOT).strip();
                nextRejectedPkgs.add(nextRejectedWrongOffer);
                String canonicalWrong = toCanonicalPackageCode(wrongOffer, null);
                if (canonicalWrong != null) {
                    nextRejectedPkgs.add(canonicalWrong);
                }
            }
            nextHeldPkg = null;
            nextHeldDur = null;
            nextDownsellPkg = null;
            nextDownsellDur = null;
            nextDownsellPrice = null;
            nextDownsellOffered = false;
            nextLastQuotedPkg = null;
            nextLastQuotedDur = null;
            nextLastQuotedPrice = null;
            recPkg = null;
            nextLastQuotePkg = null;
            serviceFam = null;
            if (SentimentIntentLayer.hasHarshPronouns(content) || PrimaryActionRouter.isFrustrationMessage(content)) {
                sentiment = "NEGATIVE";
                emotion = "ANGER";
            } else {
                sentiment = "NEGATIVE";
                emotion = "FRUSTRATION";
            }
            List<String> correctedScopes = extractTargetScopes(cleanContent, domainKeywords);
            if (!correctedScopes.isEmpty()) {
                areas.clear();
                areas.addAll(correctedScopes);
            }
        } else if (PrimaryActionRouter.isFrustrationMessage(content)) {
            sentiment = "NEGATIVE";
            emotion = "FRUSTRATION";
        } else if (CustomerInsightEngine.hasComplaintSignal(lower)) {
            sentiment = "NEGATIVE";
            emotion = "ANGER";
        }

        boolean neuroRisk = this.neurologicalRisk || SentimentIntentLayer.hasNeurologicalRisk(content, this);
        boolean expDownsell = SentimentIntentLayer.isExplicitDownsellRequest(content, this);
        boolean pkgReject = this.packageRejectionDetected || SentimentIntentLayer.isPackageRejection(content);

        if (scopeSignal.explicitCorrectionGroupD()) {
            nextPossibleCorrection = false;
            nextCorrectionDetected = true;
            if (scopeSignal.errorId() != null && !scopeSignal.errorId().isBlank()) {
                nextActiveErrorId = scopeSignal.errorId();
            }
            for (String negScope : scopeSignal.forbiddenScopes()) {
                nextForbiddenScopes.add(negScope);
                nextConfirmedScopes.remove(negScope);
                areas.remove(negScope);
                if ("đầu gối".equals(negScope) || "khớp gối".equals(negScope)) {
                    nextConfirmedScopes.remove("gối");
                    areas.remove("gối");
                    symptoms.removeIf(s -> s.contains("gối"));
                } else {
                    symptoms.removeIf(s -> s.contains(negScope));
                }
            }
            for (String posScope : scopeSignal.confirmedScopes()) {
                nextForbiddenScopes.remove(posScope);
                nextConfirmedScopes.add(posScope);
                addNormalizedAtomicScopes(areas, posScope);
            }
        } else if (scopeSignal.possibleCorrection()) {
            nextPossibleCorrection = true;
            nextCorrectionDetected = true;
        } else {
            nextPossibleCorrection = false;
            Matcher symM = SYMPTOM_TOKEN_PATTERN.matcher(cleanContent);
            while (symM.find()) {
                symptoms.add(symM.group(1).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip());
            }
            for (String posScope : scopeSignal.confirmedScopes()) {
                if (nextForbiddenScopes.remove(posScope)) {
                    nextSupersessionEvents.add("SUPERSEDED:" + posScope);
                }
                if (("đầu gối".equals(posScope) || "khớp gối".equals(posScope)) && nextForbiddenScopes.remove("gối")) {
                    nextSupersessionEvents.add("SUPERSEDED:gối");
                }
                nextConfirmedScopes.add(posScope);
            }
            for (String scope : extractTargetScopes(cleanContent, domainKeywords)) {
                if ("gối".equals(scope) && (nextForbiddenScopes.contains("đầu gối") || nextForbiddenScopes.contains("khớp gối"))) {
                    continue;
                }
                if (nextForbiddenScopes.contains(scope)) {
                    continue;
                }
                areas.add(scope);
                if ("gối".equals(scope) && (nextConfirmedScopes.contains("đầu gối") || nextConfirmedScopes.contains("khớp gối"))) {
                    continue;
                }
                nextConfirmedScopes.add(scope);
            }
            purgeNegatedSymptomsAndScopes(content, cleanContent, symptoms, areas, nextConfirmedScopes, nextForbiddenScopes, domainKeywords);
        }
        Matcher sevM = SEVERITY_TOKEN_PATTERN.matcher(cleanContent);
        if (sevM.find()) {
            String rawSev = sevM.group(1).toLowerCase(Locale.ROOT).strip();
            severity = "thi thoải".equals(rawSev) ? "thi thoảng" : rawSev;
        }
        Matcher durM = PROBLEM_DURATION_TOKEN_PATTERN.matcher(cleanContent);
        if (durM.find()) {
            probDuration = durM.group(1).toLowerCase(Locale.ROOT).strip();
        }
        String epDurationNow = this.knownEpisodeDuration;
        Matcher epDurM2 = EPISODE_DURATION_TOKEN_PATTERN.matcher(cleanContent);
        if (epDurM2.find()) {
            epDurationNow = epDurM2.group(1).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
        }
        // v3.8: tần suất luôn lấy giá trị MỚI NHẤT từ câu nói hiện tại (ghi đè, không nối chuỗi mâu thuẫn)
        String freqNow = this.knownFrequency;
        Matcher freqM2 = FREQUENCY_TOKEN_PATTERN.matcher(cleanContent);
        String lastFreq2 = null;
        while (freqM2.find()) {
            lastFreq2 = freqM2.group(1);
        }
        if (lastFreq2 != null) {
            freqNow = normalizeFrequency(lastFreq2);
        }
        String distNow = this.knownSymptomDistribution;
        Matcher distM2 = SYMPTOM_DISTRIBUTION_TOKEN_PATTERN.matcher(cleanContent);
        if (distM2.find()) {
            distNow = distM2.group(1).toLowerCase(Locale.ROOT).strip();
        }
        Matcher trigM = TRIGGER_TOKEN_PATTERN.matcher(cleanContent);
        if (trigM.find()) {
            trigger = trigM.group(1).toLowerCase(Locale.ROOT).strip();
        }
        Matcher prefM = PREFERENCE_PATTERN.matcher(cleanContent);
        if (prefM.find()) {
            prefPressure = prefM.group(1).strip();
        }
        Matcher optM = OPTION_UNIT_PATTERN.matcher(cleanContent);
        if (optM.find() && !PrimaryActionRouter.isUnitPriceComparisonMessage(cleanContent)) {
            prefOption = optM.group(1).strip();
        }

        String detectedFam = resolveServiceFamilyFromText(cleanLower, areas, domainKeywords);
        if (detectedFam != null) {
            serviceFam = detectedFam;
        } else if (serviceFam == null && !areas.isEmpty()) {
            serviceFam = resolveServiceFamilyFromText("", areas, domainKeywords);
        }
        boolean famKnown = serviceFam != null && !serviceFam.isBlank();

        String extractedProb = extractProblemOrNeedPhrase(cleanContent, domainKeywords);
        if (extractedProb != null) {
            if (isMismatch || probPhrase == null || probPhrase.isBlank()) {
                probPhrase = extractedProb;
            } else if (!probPhrase.contains(extractedProb)) {
                probPhrase = probPhrase + ", " + extractedProb;
            }
        } else if ((probPhrase == null || probPhrase.isBlank())
                && !areas.isEmpty()
                && cleanLower.contains("vùng")) {
            probPhrase = "mỏi vùng " + String.join(" ", areas);
        }

        boolean isPushbackCurrent = SentimentIntentLayer.isNegationOrPushbackOnAssumption(content)
                || scopeSignal.explicitCorrectionGroupD()
                || scopeSignal.possibleCorrection();
        boolean isNormalAnswerAfterConditionAsk = (this.conditionAskedCount >= 1
                || "ASK_CONDITION_ONCE".equals(this.lastIntent)
                || SLOT_ONSET.equals(this.pendingQuestionSlot))
                && !isPushbackCurrent
                && !SentimentIntentLayer.isConversationStyleComplaint(content)
                && !SentimentIntentLayer.isWordingOrExpressionFeedback(content)
                && !PrimaryActionRouter.isFrustrationMessage(content)
                && !SentimentIntentLayer.isDetailOrFaqQuestion(content, this);
        boolean condClarified = this.conditionClarified
                || isNormalAnswerAfterConditionAsk
                || (!isPushbackCurrent && hasConditionTimeOrProgressionSignal(cleanContent));

        // v3.7: packageExplicitlySelected chỉ bật khi KHÁCH chủ động chọn/chấp nhận gói bot đã đề xuất (kể cả nói quy chiếu).
        // Không bật khi bot mới đề xuất, khách chỉ hỏi giá hoặc nêu triệu chứng. Dữ liệu cũ thiếu trường => false.
        String explicitNamedDur = extractDurationFromText(cleanLower);
        String explicitNamedPkg = extractPackageDisplayFromBotText(cleanLower, explicitNamedDur);
        String explicitNamedCode = explicitNamedPkg != null ? toCanonicalPackageCode(explicitNamedPkg, explicitNamedDur) : null;
        String offeredIdNow = (explicitNamedCode != null && !explicitNamedCode.isBlank()) ? explicitNamedCode : lastOfferedPackageId();
        boolean isPkgRejectNow = SentimentIntentLayer.isPackageRejection(content);
        if (isPkgRejectNow) {
            if (this.selectedPackageId != null && !this.selectedPackageId.isBlank()) {
                nextRejectedPkgs.add(this.selectedPackageId);
            }
            if (nextHeldPkg != null && !nextHeldPkg.isBlank()) {
                nextRejectedPkgs.add(nextHeldPkg);
            }
            if (explicitNamedCode != null && !explicitNamedCode.isBlank()) {
                nextRejectedPkgs.add(explicitNamedCode);
            }
        }
        boolean selectionNow = !isPushbackCurrent && !isPkgRejectNow
                && offeredIdNow != null && !offeredIdNow.isBlank()
                && hasExplicitPackageSelectionSignal(cleanContent);
        // v3.8 lifecycle: chọn gói mới -> cập nhật; khách từ chối/hủy gói -> reset; còn lại (kể cả chỉ góp ý văn phong) giữ nguyên.
        boolean packageRejectedNow = !selectionNow
                && (SentimentIntentLayer.isStrongRejection(content)
                || isPkgRejectNow
                || SentimentIntentLayer.isAlternativePackageRequest(content));
        boolean nextPackageExplicitlySelected = selectionNow
                || (!packageRejectedNow && this.packageExplicitlySelected);
        boolean trialAcceptedNow = !isPushbackCurrent && !isPkgRejectNow && !packageRejectedNow
                && SentimentIntentLayer.isTrialAcceptance(content, this)
                && (offeredIdNow != null || this.packageRecommended || this.priceDiscussed || this.trialInviteOffered);
        boolean nextPackageInterestAccepted = selectionNow || trialAcceptedNow
                || (!packageRejectedNow && this.packageInterestAccepted);
        String nextSelectedPackageId = selectionNow
                ? offeredIdNow
                : (packageRejectedNow ? null : (trialAcceptedNow && (this.selectedPackageId == null || this.selectedPackageId.isBlank()) ? offeredIdNow : this.selectedPackageId));
        String nextVisitTime = packageRejectedNow ? null : this.visitTime;
        if (!isPushbackCurrent && !packageRejectedNow
                && (nextPackageInterestAccepted || "COLLECTING_INFO".equals(this.bookingStatus) || "CONFIRM_BOOKING_TIME".equals(this.lastQuestionIntent))) {
            Matcher vtM = VISIT_TIME_TOKEN_PATTERN.matcher(cleanContent);
            if (vtM.find()) {
                nextVisitTime = vtM.group(1).replaceAll("\\s+", " ").strip();
            }
        }
        String nextContactInfo = packageRejectedNow ? null : this.contactInfo;
        Matcher ciM = CONTACT_INFO_TOKEN_PATTERN.matcher(cleanContent);
        if (ciM.find()) {
            nextContactInfo = ciM.group(1).strip();
        }
        boolean hasExplicitBookingVerb = cleanLower.matches("(?isU).*\\b(đặt\\s+lịch|giữ\\s+lịch|giữ\\s+chỗ|book\\s+lịch)\\b.*");
        boolean nextBookingIntentConfirmed = !packageRejectedNow && (this.bookingIntentConfirmed
                || (nextPackageInterestAccepted && nextVisitTime != null && !nextVisitTime.isBlank())
                || (hasExplicitBookingVerb && nextVisitTime != null && !nextVisitTime.isBlank()));
        String nextBookingStatus;
        if (packageRejectedNow) {
            nextBookingStatus = "NONE";
        } else if (this.bookingStatus != null && BookingStatus.isSixStateCanonical(this.bookingStatus) && !"NONE".equalsIgnoreCase(this.bookingStatus)) {
            nextBookingStatus = this.bookingStatus;
        } else if (nextBookingIntentConfirmed && nextContactInfo != null && !nextContactInfo.isBlank()) {
            nextBookingStatus = BookingStatus.AWAITING_AVAILABILITY_CHECK.name();
        } else if (nextPackageInterestAccepted || nextBookingIntentConfirmed || "COLLECTING_INFO".equals(this.bookingStatus)) {
            nextBookingStatus = "COLLECTING_INFO";
        } else {
            nextBookingStatus = "NONE";
        }
        // v3.8 pendingQuestionSlot: chỉ xóa khi khách trả lời ĐÚNG slot đang chờ; phản hồi ngoài luồng giữ nguyên slot.
        String nextPendingSlot = this.pendingQuestionSlot;
        if (nextPendingSlot != null && !nextPendingSlot.isBlank()) {
            boolean slotAnswered = switch (nextPendingSlot) {
                case SLOT_ONSET -> PROBLEM_DURATION_TOKEN_PATTERN.matcher(cleanContent).find()
                        || FREQUENCY_TOKEN_PATTERN.matcher(cleanContent).find()
                        || (!isPushbackCurrent && hasConditionTimeOrProgressionSignal(cleanContent));
                case SLOT_FREQUENCY -> FREQUENCY_TOKEN_PATTERN.matcher(cleanContent).find()
                        || (!isPushbackCurrent && hasConditionTimeOrProgressionSignal(cleanContent));
                case SLOT_EPISODE_DURATION -> EPISODE_DURATION_TOKEN_PATTERN.matcher(cleanContent).find()
                        || (!isPushbackCurrent && hasConditionTimeOrProgressionSignal(cleanContent));
                case SLOT_SEVERITY -> SEVERITY_TOKEN_PATTERN.matcher(cleanContent).find();
                case SLOT_TRIGGER -> TRIGGER_TOKEN_PATTERN.matcher(cleanContent).find();
                case SLOT_DISTRIBUTION -> SYMPTOM_DISTRIBUTION_TOKEN_PATTERN.matcher(cleanContent).find();
                case SLOT_BOOKING_TIME -> VISIT_TIME_TOKEN_PATTERN.matcher(cleanContent).find();
                default -> false;
            };
            if (slotAnswered) {
                nextPendingSlot = null;
            }
        }
        String nextLastIntent = isMismatch ? "ENTITY_MISMATCH_CORRECTION"
                : (scopeSignal.explicitCorrectionGroupD() ? "SCOPE_CORRECTION_PUSHBACK"
                : (scopeSignal.possibleCorrection() ? "COLLOQUIAL_CORRECTION_PUSHBACK"
                : (isPushbackCurrent ? "PUSHBACK_ON_ASSUMPTION"
                : (isDetailFaqCurrent ? "DETAIL_OR_FAQ_QUESTION"
                : (isComprehensionCurrent ? "COMPREHENSION_FAILURE" : this.lastIntent)))));

        ConversationState enriched = new ConversationState(
                this.greetingSent,
                this.introSent,
                this.stage,
                nextLastIntent,
                this.lastQuestion,
                this.lastQuestionIntent,
                this.usedQuestionIntents,
                this.lastBotAction,
                new KnownFacts(
                        serviceFam != null ? serviceFam : (this.known != null ? this.known.itemInterest() : null),
                        probPhrase,
                        prefPressure,
                        prefOption,
                        nextVisitTime != null ? nextVisitTime : (this.known != null ? this.known.preferredTime() : null)
                ),
                this.priceDiscussed,
                this.pricesShown,
                this.recommendedOptions,
                this.objectionCount,
                this.ackCount,
                this.clarificationCount,
                this.conditionAskedCount,
                this.preferenceAskedCount,
                this.clarifyServiceTypeCount,
                this.clarifyDurationCount,
                this.frustrationCount,
                this.ambiguousCount,
                this.recommendationCount,
                this.priceRepeatGuardCount,
                condClarified,
                famKnown,
                this.packageRecommended,
                priceReq,
                Collections.unmodifiableList(new ArrayList<>(symptoms)),
                Collections.unmodifiableList(new ArrayList<>(areas)),
                severity,
                probDuration,
                trigger,
                serviceFam,
                prefPressure,
                recPkg,
                nextLastQuotePkg,
                sentiment,
                emotion,
                this.usedTemplates,
                this.humanTakeover,
                this.optedOut,
                nextDownsellOffered,
                this.trialInviteOffered,
                expDownsell,
                pkgReject,
                nextLastQuotedPkg,
                nextLastQuotedDur,
                nextLastQuotedPrice,
                nextHeldPkg,
                nextHeldDur,
                nextDownsellPkg,
                nextDownsellDur,
                nextDownsellPrice,
                this.rejectedDurations,
                Collections.unmodifiableSet(nextRejectedPkgs),
                this.objectionStage,
                this.telemetrySubtype,
                this.docsHasFeedback,
                this.docsHasRelaxationBenefit,
                this.docsHasPrivateRoom,
                neuroRisk,
                nextRegion,
                this.mentionedPackages,
                this.quotedPackages,
                nextLastQuotedPrice,
                this.alternativeRequestCount,
                nextCorrectionDetected,
                nextRejectedWrongOffer,
                this.availablePackageCatalog,
                this.lastBotBubbleHashes,
                Collections.unmodifiableList(nextOutstanding),
                this.lastAssistantMessage,
                Collections.unmodifiableSet(new LinkedHashSet<>(nextConfirmedScopes)),
                Collections.unmodifiableSet(new LinkedHashSet<>(nextForbiddenScopes)),
                Collections.unmodifiableSet(nextApologizedErrors),
                nextActiveErrorId,
                Collections.unmodifiableList(nextCareNotes),
                Collections.unmodifiableList(nextSupersessionEvents),
                nextPossibleCorrection,
                nextPackageExplicitlySelected, nextSelectedPackageId,
                freqNow, distNow, nextPendingSlot,
                epDurationNow, nextPackageInterestAccepted, nextBookingIntentConfirmed, nextVisitTime, nextContactInfo, nextBookingStatus,
                packageRejectedNow ? null : this.selectedProductId,
                packageRejectedNow ? null : this.bookingDate,
                packageRejectedNow ? null : this.bookingTime,
                packageRejectedNow
                        ? BookingStatus.NONE
                        : (this.bookingStatusEnum != null && this.bookingStatusEnum != BookingStatus.NONE
                            ? this.bookingStatusEnum
                            : BookingStatus.fromLegacyString(nextBookingStatus, this.selectedProductId, nextVisitTime))
        );
        ENRICHED_TO_PRIOR_MAP.put(enriched, this.priorTurnState());
        return enriched;
    }

    public static ConversationState updateAfterSend(
            UUID pageId,
            String senderId,
            ConversationState prevState,
            String userMessage,
            PrimaryActionRouter.RoutingDecision decision,
            List<ReplyBubble> finalBubbles,
            boolean needsHumanHandoff
    ) {
        return updateAfterSend(pageId, senderId, null, prevState, userMessage, decision, finalBubbles, needsHumanHandoff, -1);
    }

    public static ConversationState updateAfterSend(
            UUID pageId,
            String senderId,
            ConversationState prevState,
            String userMessage,
            PrimaryActionRouter.RoutingDecision decision,
            List<ReplyBubble> finalBubbles,
            boolean needsHumanHandoff,
            int incomingHistorySize
    ) {
        return updateAfterSend(pageId, senderId, null, prevState, userMessage, decision, finalBubbles, needsHumanHandoff, incomingHistorySize);
    }

    public static ConversationState updateAfterSend(
            UUID pageId,
            String senderId,
            String conversationId,
            ConversationState prevState,
            String userMessage,
            PrimaryActionRouter.RoutingDecision decision,
            List<ReplyBubble> finalBubbles,
            boolean needsHumanHandoff,
            int incomingHistorySize
    ) {
        String sessionKey = buildSessionKey(pageId, senderId, conversationId);
        String normUserMsg = VietnameseQueryNormalizer.normalize(userMessage != null ? userMessage : "")
                .toLowerCase(Locale.ROOT).strip();
        ConversationState base = prevState != null
                ? prevState.ensureTurnEnriched(userMessage, List.of())
                : initial().ensureTurnEnriched(userMessage, List.of());
        ConversationState effectivePrev = base.priorTurnState();
        if (sessionKey != null) {
            LastTurnPreStateSnapshot snap = LAST_TURN_PRE_STATE_STORE.get(sessionKey);
            if (snap != null && normUserMsg.equals(snap.normalizedUserMessage())) {
                boolean sameHistoryLen = incomingHistorySize >= 0 && incomingHistorySize == snap.historySize();
                boolean samePostState = prevState == snap.postTurnState()
                        || (prevState != null && snap.postTurnState() != null
                            && prevState.lastBotBubbleHashes().equals(snap.postTurnState().lastBotBubbleHashes())
                            && prevState.frustrationCount() == snap.postTurnState().frustrationCount()
                            && prevState.objectionCount() == snap.postTurnState().objectionCount()
                            && prevState.clarificationCount() == snap.postTurnState().clarificationCount());
                if (sameHistoryLen || samePostState) {
                    effectivePrev = snap.preTurnState() != null ? snap.preTurnState() : initial();
                    if (snap.enrichedTurnState() != null) {
                        base = snap.enrichedTurnState();
                    }
                }
            }
        }
        Set<String> prices = new LinkedHashSet<>(base.pricesShown());
        Set<String> options = new LinkedHashSet<>(base.recommendedOptions());
        Set<String> templates = new LinkedHashSet<>(base.usedTemplates());
        Set<String> usedQIntents = new LinkedHashSet<>(base.usedQuestionIntents());
        Set<String> nextRejectedDurations = new LinkedHashSet<>(base.rejectedDurations());
        Set<String> nextRejectedPackages = new LinkedHashSet<>(base.rejectedPackages());
        Set<String> nextMentionedPackages = new LinkedHashSet<>(base.mentionedPackages());
        Set<String> nextQuotedPackages = new LinkedHashSet<>(base.quotedPackages());
        List<String> nextBubbleHashes = new ArrayList<>(base.lastBotBubbleHashes());
        List<UnresolvedCustomerRequest> nextOutstanding = new ArrayList<>(
                base.outstandingRequests() != null ? base.outstandingRequests() : List.of()
        );

        boolean greetingNowSent = base.greetingSent()
                || (decision != null && decision.openingRequired())
                || (finalBubbles != null && !finalBubbles.isEmpty());
        if (greetingNowSent) {
            templates.add("OPENING_GREETING_1");
            templates.add("OPENING_INTRO_2");
        }

        String lastQ = base.lastQuestion();
        String deliveredQuestionThisTurn = null; // chỉ câu hỏi nằm trong bong bóng ĐÃ giao tới khách
        boolean anyPriceInReply = false;
        BigDecimal replyPriceVal = null;
        String combinedBotReplyRaw = "";
        String combinedBotReplyLower = "";
        if (finalBubbles != null) {
            StringBuilder sb = new StringBuilder();
            for (ReplyBubble b : finalBubbles) {
                if (b == null || b.text() == null) continue;
                String t = b.text();
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(t);
                appendBubbleHashes(nextBubbleHashes, t);
                Matcher pm = PRICE_NUMBER_PATTERN.matcher(t);
                while (pm.find()) {
                    String rawP = pm.group().strip();
                    prices.add(rawP);
                    anyPriceInReply = true;
                    BigDecimal parsed = parsePriceToBigDecimal(rawP);
                    if (parsed != null) {
                        replyPriceVal = parsed;
                    }
                }
                Matcher om = OPTION_UNIT_PATTERN.matcher(t);
                while (om.find()) {
                    options.add(om.group(1).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip());
                }
                if (t.contains("?")) {
                    lastQ = t;
                    deliveredQuestionThisTurn = t;
                }
            }
            combinedBotReplyRaw = sb.toString().strip();
            combinedBotReplyLower = combinedBotReplyRaw.toLowerCase(Locale.ROOT);
        }

        if (!combinedBotReplyRaw.isBlank()) {
            markDeliveredBotReplyAgainstOutstandingRequests(nextOutstanding, combinedBotReplyRaw, needsHumanHandoff);
        }

        String effAction = decision != null ? decision.effectiveResponseAction().name() : base.lastBotAction();
        if (effAction != null) {
            templates.add(effAction);
        }

        String skillCode = decision != null && decision.correspondingMicroSkillCode() != null
                ? decision.correspondingMicroSkillCode()
                : "";
        String detectedIntent = decision != null && decision.detectedIntent() != null
                ? decision.detectedIntent()
                : "";

        if (finalBubbles != null && !finalBubbles.isEmpty()) {
            if ((decision != null
                    && decision.effectiveResponseAction() == PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE
                    && finalBubbles.size() >= 2)
                    || "GENERATE_PRICE_ANCHOR_BUBBLE".equalsIgnoreCase(detectedIntent)) {
                templates.add("VALUE_ANGLE_1");
            }
            if ((decision != null
                    && decision.effectiveResponseAction() == PrimaryActionRouter.PrimaryAction.HANDLE_PRICE_OBJECTION_FIRST_TIME)
                    || "spa_objection_value_trial".equalsIgnoreCase(skillCode)
                    || "PRICE_OBJECTION_VALUE_TRIAL".equalsIgnoreCase(detectedIntent)) {
                templates.add("VALUE_ANGLE_2");
            }
        }

        int nextObjectionCount = base.objectionCount();
        boolean nextDownsellOffered = base.downsellOffered();
        boolean nextTrialInviteOffered = base.trialInviteOffered();
        boolean nextPkgRejectionDetected = base.packageRejectionDetected();
        String nextLastQuotedPkg = base.lastQuotedPackage();
        String nextLastQuotedDur = base.lastQuotedDuration();
        BigDecimal nextLastQuotedPrice = base.lastQuotedPrice();
        String nextHeldPkg = base.heldPackage();
        String nextHeldDur = base.heldDuration();
        String nextDownsellPkg = base.downsellPackage();
        String nextDownsellDur = base.downsellDuration();
        BigDecimal nextDownsellPrice = base.downsellPrice();
        String nextObjectionStage = base.objectionStage();
        String nextTelemetrySubtype = base.telemetrySubtype();
        String nextQuestionIntent = base.lastQuestionIntent();
        int nextAlternativeReqCount = base.alternativeRequestCount();

        if ("ALTERNATIVE_PACKAGE_REQUEST".equalsIgnoreCase(detectedIntent)
                || "spa_alternative_package_request".equalsIgnoreCase(skillCode)
                || SentimentIntentLayer.isAlternativePackageRequest(userMessage)) {
            nextAlternativeReqCount++;
        }

        String replyDuration = extractDurationFromText(combinedBotReplyLower);
        String replyPkgDisplay = extractPackageDisplayFromBotText(combinedBotReplyLower, replyDuration);
        if ((replyPkgDisplay == null || replyDuration == null)
                && replyPriceVal != null
                && base.availablePackageCatalog() != null
                && !base.availablePackageCatalog().isEmpty()) {
            long targetVnd = replyPriceVal.longValue();
            int parsedReplyDur = parseDurationInt(replyDuration);
            for (PackageCatalogService.PackageSummary s : base.availablePackageCatalog()) {
                if (s != null && s.priceVnd() == targetVnd
                        && (parsedReplyDur <= 0 || (s.durationMinutes() != null && s.durationMinutes() == parsedReplyDur))) {
                    if (replyDuration == null && s.durationMinutes() != null && s.durationMinutes() > 0) {
                        replyDuration = String.valueOf(s.durationMinutes());
                    }
                    if (replyPkgDisplay == null && s.displayName() != null && !s.displayName().isBlank()) {
                        String disp = s.displayName().toLowerCase(Locale.ROOT).strip();
                        replyPkgDisplay = (replyDuration != null && !disp.contains("phút"))
                                ? disp + " " + replyDuration + " phút"
                                : disp;
                    }
                    break;
                }
            }
        }
        String replyPkgCanonical = toCanonicalPackageCode(replyPkgDisplay, replyDuration);

        for (String mentionedCanon : extractAllCanonicalPackagesFromBotText(combinedBotReplyLower, replyDuration)) {
            nextMentionedPackages.add(mentionedCanon);
            if (anyPriceInReply) {
                nextQuotedPackages.add(mentionedCanon);
            }
        }
        if (replyPkgCanonical != null) {
            nextMentionedPackages.add(replyPkgCanonical);
            if (anyPriceInReply) {
                nextQuotedPackages.add(replyPkgCanonical);
            }
        }

        // === v3.1.5.3 State Transitions for R11, R12, R13B ===
        if ("spa_objection_value_trial".equalsIgnoreCase(skillCode)
                || "PRICE_OBJECTION_VALUE_TRIAL".equalsIgnoreCase(detectedIntent)) {
            // Normal first objection: Hold package, DO NOT add to rejectedPackages / rejectedDurations!
            nextObjectionCount = Math.max(1, base.objectionCount() + 1);
            nextTrialInviteOffered = true;
            nextDownsellOffered = false;
            nextPkgRejectionDetected = false;
            nextObjectionStage = "TRIAL_INVITED";
            nextQuestionIntent = "INVITE_ONE_SESSION_TRIAL";
            usedQIntents.add("INVITE_ONE_SESSION_TRIAL");
            templates.add("PRICE_OBJECTION_FIRST_TIME");
            if (finalBubbles != null && !finalBubbles.isEmpty()) {
                templates.add("VALUE_ANGLE_2");
            }
        } else if ("spa_price_hesitation_soft_downsell".equalsIgnoreCase(skillCode)
                || "PRICE_HESITATION_SOFT_DOWNSELL".equalsIgnoreCase(detectedIntent)) {
            nextObjectionCount = Math.max(1, base.objectionCount());
            boolean hasPresentedDownsellInBubbles = (replyPkgCanonical != null
                    && (base.heldPackage() == null || !replyPkgCanonical.equalsIgnoreCase(base.heldPackage())))
                    || (replyPriceVal != null
                    && (base.lastQuotedPrice() == null || replyPriceVal.compareTo(base.lastQuotedPrice()) != 0));
            if (hasPresentedDownsellInBubbles) {
                nextDownsellOffered = true;
                nextObjectionStage = "DOWNSELL_OFFERED";
                if (replyDuration != null) {
                    nextDownsellDur = replyDuration;
                    nextLastQuotedDur = replyDuration;
                }
                if (replyPkgCanonical != null) {
                    nextDownsellPkg = replyPkgCanonical;
                    nextLastQuotedPkg = replyPkgCanonical;
                }
                if (replyPriceVal != null) {
                    nextDownsellPrice = replyPriceVal;
                    nextLastQuotedPrice = replyPriceVal;
                }
            }
        } else if ("spa_objection_explicit_downsell".equalsIgnoreCase(skillCode)
                || "spa_objection_dynamic_downsell".equalsIgnoreCase(skillCode)
                || "EXPLICIT_DOWNSELL_WITH_PACKAGE".equalsIgnoreCase(detectedIntent)
                || "SECOND_OBJECTION_DOWNSELL".equalsIgnoreCase(detectedIntent)) {
            nextObjectionCount = base.objectionCount() + 1;
            nextPkgRejectionDetected = true;
            String prevDurToReject = base.heldDuration() != null ? base.heldDuration() : base.lastQuotedDuration();
            String prevPkgToReject = base.heldPackage() != null ? base.heldPackage() : base.lastQuotedPackage();
            boolean hasPresentedDownsellInBubbles = (replyPkgCanonical != null
                    && (prevPkgToReject == null || !replyPkgCanonical.equalsIgnoreCase(prevPkgToReject)))
                    || (replyPriceVal != null
                    && (base.lastQuotedPrice() == null || replyPriceVal.compareTo(base.lastQuotedPrice()) != 0));
            if (hasPresentedDownsellInBubbles) {
                nextDownsellOffered = true;
                nextObjectionStage = "DOWNSELL_OFFERED";
                if (prevDurToReject != null
                        && !prevDurToReject.equals(replyDuration)
                        && !hasAlternativePackageWithSameDuration(base.availablePackageCatalog(), prevDurToReject, prevPkgToReject)) {
                    nextRejectedDurations.add(prevDurToReject);
                }
                if (prevPkgToReject != null) {
                    nextRejectedPackages.add(prevPkgToReject);
                }
                if (replyDuration != null) {
                    nextDownsellDur = replyDuration;
                    nextHeldDur = replyDuration;
                    nextLastQuotedDur = replyDuration;
                }
                if (replyPkgCanonical != null) {
                    nextDownsellPkg = replyPkgCanonical;
                    nextHeldPkg = replyPkgCanonical;
                    nextLastQuotedPkg = replyPkgCanonical;
                }
                if (replyPriceVal != null) {
                    nextDownsellPrice = replyPriceVal;
                    nextLastQuotedPrice = replyPriceVal;
                }
            }
            templates.add("PRICE_OBJECTION_REPEAT");
        } else if ("spa_objection_no_lower_package_handoff".equalsIgnoreCase(skillCode)
                || "NO_LOWER_PACKAGE_HANDOFF".equalsIgnoreCase(detectedIntent)) {
            nextObjectionCount = base.objectionCount() + 1;
            nextDownsellOffered = false;
            nextObjectionStage = "NO_LOWER_PACKAGE_HANDOFF";
            nextTelemetrySubtype = "NO_LOWER_PACKAGE_HANDOFF";
        } else if ("spa_stage3_graceful_exit".equalsIgnoreCase(skillCode)
                || "STAGE_3_GRACEFUL_EXIT".equalsIgnoreCase(detectedIntent)) {
            nextObjectionCount = base.objectionCount() + 1;
            nextDownsellOffered = true;
            nextObjectionStage = "STAGE_3_GRACEFUL_EXIT";
            nextTelemetrySubtype = "STAGE_3_GRACEFUL_EXIT";
        } else if ("spa_price_escalation_handoff".equalsIgnoreCase(skillCode)
                || "PRICE_ESCALATION_HANDOFF".equalsIgnoreCase(detectedIntent)) {
            nextObjectionCount = base.objectionCount() + 1;
            nextObjectionStage = "PRICE_ESCALATION_HANDOFF";
            nextTelemetrySubtype = "PRICE_ESCALATION_HANDOFF";
        }
        boolean nextPkgInterestAccepted = base.packageInterestAccepted();
        boolean nextBookingConfirmed = base.bookingIntentConfirmed();
        String nextBookingStatus = base.bookingStatus() != null ? base.bookingStatus() : "NONE";
        if ("spa_trial_acceptance_booking".equalsIgnoreCase(skillCode)
                || "TRIAL_ACCEPTANCE".equalsIgnoreCase(detectedIntent)) {
            nextObjectionStage = "BOOKED";
            nextQuestionIntent = "CONFIRM_BOOKING_TIME";
            usedQIntents.add("CONFIRM_BOOKING_TIME");
            nextPkgInterestAccepted = true;
            if ("NONE".equals(nextBookingStatus)) {
                nextBookingStatus = "COLLECTING_INFO";
            }
        } else if (decision != null && decision.effectiveResponseAction() == PrimaryActionRouter.PrimaryAction.HARD_HANDOFF_SAFETY) {
            nextTelemetrySubtype = "SAFETY_HANDOFF";
        }

        int nextAckCount = base.ackCount();
        if (decision != null && decision.effectiveResponseAction() == PrimaryActionRouter.PrimaryAction.ADVANCE_AFTER_ACK) {
            nextAckCount++;
            if (base.packageRecommended() || base.priceDiscussed() || base.downsellOffered() || base.trialInviteOffered()) {
                nextPkgInterestAccepted = true;
                if ("NONE".equals(nextBookingStatus)) {
                    nextBookingStatus = "COLLECTING_INFO";
                }
            }
        }

        int nextCondAsked = base.conditionAskedCount();
        int nextPrefAsked = base.preferenceAskedCount();
        int nextClarifyService = base.clarifyServiceTypeCount();
        int nextClarifyDuration = base.clarifyDurationCount();
        int nextFrustration = base.frustrationCount();
        int nextAmbiguous = base.ambiguousCount();
        int nextRecCount = base.recommendationCount();
        int nextPriceRepeatGuard = base.priceRepeatGuardCount();
        boolean nextPkgRecommended = base.packageRecommended();
        String nextKnownRecPkg = base.knownRecommendedPackage();
        String nextLastQuotePkg = base.lastQuotePackage();

        if ("FRUSTRATION".equals(base.lastEmotion())) {
            nextFrustration++;
        }

        if ("spa_condition_first_discovery".equalsIgnoreCase(skillCode)
                || "spa_cautious_symptom_clarification".equalsIgnoreCase(skillCode)
                || "CAUTIOUS_SYMPTOM_CLARIFICATION".equalsIgnoreCase(detectedIntent)
                || "ASK_CONDITION_ONCE".equalsIgnoreCase(detectedIntent)
                || combinedBotReplyLower.contains("bị lâu chưa")
                || combinedBotReplyLower.contains("mới xuất hiện")
                || combinedBotReplyLower.contains("hay đã kéo dài")
                || combinedBotReplyLower.contains("thời điểm nào")
                || combinedBotReplyLower.contains("vận động/tập luyện")
                || combinedBotReplyLower.contains("tự nhiên bị mỏi hay")
                || combinedBotReplyLower.contains("bắt đầu từ khi nào")
                || combinedBotReplyLower.contains("từ khi nào")
                || combinedBotReplyLower.contains("thường xảy ra")
                || (combinedBotReplyLower.contains("mỏi vùng nào") && !base.knownSymptoms().isEmpty())) {
            nextCondAsked++;
            nextQuestionIntent = "ASK_CONDITION";
            usedQIntents.add("ASK_CONDITION");
        } else if (combinedBotReplyLower.contains("mỏi vùng nào")) {
            nextQuestionIntent = "ASK_INITIAL_NEED";
            usedQIntents.add("ASK_INITIAL_NEED");
        } else if (combinedBotReplyLower.contains("lực nhẹ") || combinedBotReplyLower.contains("lực vừa")) {
            nextPrefAsked++;
            nextQuestionIntent = "ASK_PRESSURE_PREFERENCE";
            usedQIntents.add("ASK_PRESSURE_PREFERENCE");
        } else if ("spa_clarify_service_type_once".equalsIgnoreCase(skillCode)
                || (decision != null && decision.effectiveResponseAction() == PrimaryActionRouter.PrimaryAction.CLARIFY_SERVICE_DURATION
                    && !base.serviceFamilyKnown())) {
            nextClarifyService++;
            nextQuestionIntent = "CLARIFY_SERVICE_TYPE";
            usedQIntents.add("CLARIFY_SERVICE_TYPE");
        } else if ("spa_clarify_duration_once".equalsIgnoreCase(skillCode)
                || (decision != null && decision.effectiveResponseAction() == PrimaryActionRouter.PrimaryAction.CLARIFY_SERVICE_DURATION
                    && base.serviceFamilyKnown())) {
            nextClarifyDuration++;
            nextQuestionIntent = "CLARIFY_DURATION";
            usedQIntents.add("CLARIFY_DURATION");
        } else if ("spa_price_repeat_guard".equalsIgnoreCase(skillCode)
                || combinedBotReplyLower.contains("vừa gửi ở tin trên")
                || combinedBotReplyLower.contains("tin nhắn trên")) {
            nextPriceRepeatGuard++;
        } else if ("spa_ambiguous_confirm_once".equalsIgnoreCase(skillCode)) {
            nextAmbiguous++;
            nextQuestionIntent = "CLARIFY_AMBIGUOUS";
            usedQIntents.add("CLARIFY_AMBIGUOUS");
        } else if (combinedBotReplyLower.contains("khung giờ nào") || combinedBotReplyLower.contains("khoảng mấy giờ")) {
            nextQuestionIntent = "CONFIRM_BOOKING_TIME";
            usedQIntents.add("CONFIRM_BOOKING_TIME");
        } else if (combinedBotReplyLower.contains("trải nghiệm thử")
                || combinedBotReplyLower.contains("thử 1 buổi")
                || combinedBotReplyLower.contains("trải nghiệm gói")
                || combinedBotReplyLower.contains("muốn trải nghiệm")) {
            nextQuestionIntent = "INVITE_ONE_SESSION_TRIAL";
            nextTrialInviteOffered = true;
            usedQIntents.add("INVITE_ONE_SESSION_TRIAL");
        }

        boolean isFaqOrDetailOrHandoffAction = decision != null
                && (decision.effectiveResponseAction() == PrimaryActionRouter.PrimaryAction.SERVICE_DETAIL_AND_FAQ_QA
                    || decision.effectiveResponseAction() == PrimaryActionRouter.PrimaryAction.SAFE_REFUSAL_OR_HANDOFF_IF_REPEATED
                    || decision.effectiveResponseAction() == PrimaryActionRouter.PrimaryAction.HARD_HANDOFF_COMPLAINT
                    || decision.effectiveResponseAction() == PrimaryActionRouter.PrimaryAction.HARD_HANDOFF_SAFETY
                    || needsHumanHandoff);
        boolean isHandoffReplyText = combinedBotReplyLower.contains("chuyển chuyên viên")
                || combinedBotReplyLower.contains("chuyển nhân viên")
                || combinedBotReplyLower.contains("chuyển quản lý")
                || combinedBotReplyLower.contains("nhờ chuyên viên");
        boolean detailFaqFulfilledPendingPriceQuote = decision != null
                && decision.effectiveResponseAction() == PrimaryActionRouter.PrimaryAction.SERVICE_DETAIL_AND_FAQ_QA
                && base.heldPackage() == null
                && anyPriceInReply
                && replyPkgDisplay != null;
        if ((!isFaqOrDetailOrHandoffAction || detailFaqFulfilledPendingPriceQuote)
                && !isHandoffReplyText
                && decision != null
                && (decision.effectiveResponseAction() == PrimaryActionRouter.PrimaryAction.RECOMMEND_ONE_PACKAGE
                    || decision.effectiveResponseAction() == PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE
                    || replyPkgDisplay != null)) {
            nextRecCount++;
            nextPkgRecommended = true;
            if (replyPkgDisplay != null) {
                nextKnownRecPkg = replyPkgDisplay;
            } else if (nextKnownRecPkg == null) {
                nextKnownRecPkg = inferDefaultPackageName(base.knownServiceFamily(), base.knownBodyAreas(), options);
            }
            if (anyPriceInReply) {
                nextLastQuotePkg = nextKnownRecPkg;
                if (replyPkgCanonical != null) {
                    nextLastQuotedPkg = replyPkgCanonical;
                    nextHeldPkg = replyPkgCanonical;
                }
                if (replyDuration != null) {
                    nextLastQuotedDur = replyDuration;
                    nextHeldDur = replyDuration;
                }
                if (replyPriceVal != null) {
                    nextLastQuotedPrice = replyPriceVal;
                }
            }
        }

        boolean isOptOut = PrimaryActionRouter.isExplicitOptOutMessage(userMessage);

        ConversationStage nextStage = switch (decision != null ? decision.effectiveResponseAction() : PrimaryActionRouter.PrimaryAction.GENERIC_SHORT_DISCOVERY) {
            case HARD_HANDOFF_SAFETY, HARD_HANDOFF_COMPLAINT, BOT_MUTED -> ConversationStage.HANDOFF;
            case CLARIFY_SERVICE_DURATION -> ConversationStage.PRICE_CLARIFY;
            case ANSWER_PRICE_ONCE -> ConversationStage.PRICE_QUOTED;
            case HANDLE_PRICE_OBJECTION_FIRST_TIME, HANDLE_PRICE_OBJECTION_REPEAT -> ConversationStage.OBJECTION;
            case EXPLAIN_UNIT_VALUE -> ConversationStage.COMPARISON;
            case RECOMMEND_ONE_PACKAGE -> ConversationStage.RECOMMENDATION;
            case ADVANCE_AFTER_ACK, CONFIRM_BOOKING_DETAILS -> ConversationStage.BOOKING;
            default -> base.priceDiscussed() ? ConversationStage.PRICE_QUOTED : ConversationStage.DISCOVERY;
        };

        String nextLastAssistantMsg = !combinedBotReplyRaw.isBlank() ? combinedBotReplyRaw : base.lastAssistantMessage();
        Set<String> nextApologizedErrors = new LinkedHashSet<>(base.apologizedErrorIds() != null ? base.apologizedErrorIds() : Set.of());
        if ((combinedBotReplyLower.contains("xin lỗi") || combinedBotReplyLower.contains("nhầm"))
                && base.activeErrorId() != null && !base.activeErrorId().isBlank()) {
            nextApologizedErrors.add(base.activeErrorId());
        }
        List<CustomerCareNoteArtifact> nextCareNotes = new ArrayList<>(base.customerCareNotes() != null ? base.customerCareNotes() : List.of());
        if (!combinedBotReplyRaw.isBlank()
                && containsOperationalCarePromise(combinedBotReplyRaw, null)
                && (!base.confirmedScopes().isEmpty() || !base.knownBodyAreas().isEmpty())) {
            List<String> noteScopes = !base.confirmedScopes().isEmpty()
                    ? new ArrayList<>(base.confirmedScopes())
                    : new ArrayList<>(base.knownBodyAreas());
            String noteTxt = combinedBotReplyRaw;
            boolean alreadyAdded = nextCareNotes.stream().anyMatch(n -> n != null && noteTxt.equals(n.noteText()));
            if (!alreadyAdded) {
                nextCareNotes.add(new CustomerCareNoteArtifact(
                        pageId != null ? pageId.toString() : "default",
                        conversationId != null ? conversationId : (senderId != null ? senderId : "session"),
                        "turn-" + (incomingHistorySize >= 0 ? incomingHistorySize : nextCareNotes.size() + 1),
                        Collections.unmodifiableList(noteScopes),
                        extractCareActorFromText(combinedBotReplyLower),
                        noteTxt,
                        java.time.Instant.now().toString()
                ));
            }
        }

        ConversationState updated = new ConversationState(
                greetingNowSent,
                greetingNowSent,
                nextStage,
                decision != null ? decision.detectedIntent() : base.lastIntent(),
                lastQ,
                nextQuestionIntent,
                Collections.unmodifiableSet(usedQIntents),
                effAction,
                base.known(),
                // v3.3.4 RC-01: chỉ coi là "đã báo giá" khi giá THỰC SỰ được giao tới khách (không tính lượt handoff)
                base.priceDiscussed() || (!needsHumanHandoff && anyPriceInReply),
                Collections.unmodifiableList(new ArrayList<>(prices)),
                Collections.unmodifiableList(new ArrayList<>(options)),
                nextObjectionCount,
                nextAckCount,
                base.clarificationCount() + (decision != null && decision.effectiveResponseAction() == PrimaryActionRouter.PrimaryAction.CLARIFY_SERVICE_DURATION ? 1 : 0),
                nextCondAsked,
                nextPrefAsked,
                nextClarifyService,
                nextClarifyDuration,
                nextFrustration,
                nextAmbiguous,
                nextRecCount,
                nextPriceRepeatGuard,
                base.conditionClarified(),
                base.serviceFamilyKnown(),
                nextPkgRecommended,
                base.priceRequestedInSession(),
                base.knownSymptoms(),
                base.knownBodyAreas(),
                base.knownSeverity(),
                base.knownDurationOfProblem(),
                base.knownTrigger(),
                base.knownServiceFamily(),
                base.knownPreferredPressure(),
                nextKnownRecPkg,
                nextLastQuotePkg,
                base.lastSentiment(),
                base.lastEmotion(),
                Collections.unmodifiableList(new ArrayList<>(templates)),
                (base.humanTakeover() || needsHumanHandoff) && !isOptOut,
                base.optedOut() || isOptOut,
                nextDownsellOffered,
                nextTrialInviteOffered,
                base.explicitDownsellRequest(),
                nextPkgRejectionDetected,
                nextLastQuotedPkg,
                nextLastQuotedDur,
                nextLastQuotedPrice,
                nextHeldPkg,
                nextHeldDur,
                nextDownsellPkg,
                nextDownsellDur,
                nextDownsellPrice,
                Collections.unmodifiableSet(nextRejectedDurations),
                Collections.unmodifiableSet(nextRejectedPackages),
                nextObjectionStage,
                nextTelemetrySubtype,
                base.docsHasFeedback(),
                base.docsHasRelaxationBenefit(),
                base.docsHasPrivateRoom(),
                base.neurologicalRisk(),
                base.preferredRegion(),
                Collections.unmodifiableList(new ArrayList<>(nextMentionedPackages)),
                Collections.unmodifiableList(new ArrayList<>(nextQuotedPackages)),
                nextLastQuotedPrice,
                nextAlternativeReqCount,
                base.customerCorrectionDetected(),
                base.rejectedWrongOffer(),
                base.availablePackageCatalog(),
                Collections.unmodifiableList(nextBubbleHashes),
                Collections.unmodifiableList(nextOutstanding),
                nextLastAssistantMsg,
                base.confirmedScopes(),
                base.forbiddenScopes(),
                Collections.unmodifiableSet(nextApologizedErrors),
                base.activeErrorId(),
                Collections.unmodifiableList(nextCareNotes),
                base.scopeSupersessionEvents(),
                base.possibleCorrection(),
                base.packageExplicitlySelected(), base.selectedPackageId(),
                base.knownFrequency(), base.knownSymptomDistribution(),
                deliveredQuestionThisTurn != null ? inferPendingQuestionSlot(deliveredQuestionThisTurn) : base.pendingQuestionSlot(),
                base.knownEpisodeDuration(), nextPkgInterestAccepted, nextBookingConfirmed, base.visitTime(), base.contactInfo(), nextBookingStatus,
                base.selectedProductId(), base.bookingDate(), base.bookingTime(),
                base.bookingStatusEnum() != null && base.bookingStatusEnum() != BookingStatus.NONE
                        ? base.bookingStatusEnum()
                        : BookingStatus.fromLegacyString(nextBookingStatus, base.selectedProductId(), base.visitTime())
        );

        if (sessionKey != null) {
            LAST_TURN_PRE_STATE_STORE.put(sessionKey, new LastTurnPreStateSnapshot(
                    incomingHistorySize,
                    normUserMsg,
                    effectivePrev,
                    base,
                    updated
            ));
            SESSION_STORE.put(sessionKey, updated);
        }
        return updated;
    }

    private static void recordUserTurnIntoOutstandingRequests(
            List<UnresolvedCustomerRequest> requests,
            String userMessage,
            String lastBotMessage,
            String activePkgContext,
            int turnIndex
    ) {
        if (requests == null || userMessage == null || userMessage.isBlank()) {
            return;
        }
        String trimmed = userMessage.strip();
        if (PrimaryActionRouter.isExplicitOptOutMessage(trimmed)
                || SentimentIntentLayer.isHardPackageRejection(trimmed)) {
            return;
        }
        if (SentimentIntentLayer.isDetailOrFaqQuestion(trimmed, lastBotMessage, activePkgContext)) {
            String refEntity = SentimentIntentLayer.extractReferencedDetailEntity(trimmed, lastBotMessage, activePkgContext);
            if (refEntity == null || refEntity.isBlank()) {
                refEntity = "service_detail";
            }
            String semIntent = resolveDetailSemanticIntent(trimmed, refEntity);
            String semQuestion = buildCanonicalSemanticQuestion(trimmed, refEntity);
            boolean hasContrast = SentimentIntentLayer.hasContrastOrClarificationPrefix(trimmed);

            int existingIdx = -1;
            for (int i = requests.size() - 1; i >= 0; i--) {
                UnresolvedCustomerRequest r = requests.get(i);
                if (r == null) continue;
                boolean sameEntity = r.referencedEntity() != null && r.referencedEntity().equalsIgnoreCase(refEntity);
                if (!r.resolved() && (sameEntity || hasContrast)) {
                    existingIdx = i;
                    break;
                }
            }
            if (existingIdx >= 0) {
                UnresolvedCustomerRequest prev = requests.get(existingIdx);
                boolean sameSemanticIntent = prev.semanticIntent() != null && prev.semanticIntent().equalsIgnoreCase(semIntent);
                int nextRepeat = sameSemanticIntent ? prev.repeatOrChallengeCount() + 1 : 0;
                requests.set(existingIdx, new UnresolvedCustomerRequest(
                        trimmed,
                        semQuestion,
                        semIntent,
                        refEntity,
                        lastBotMessage != null ? lastBotMessage : prev.contextBotExcerpt(),
                        prev.turnIndex(),
                        false,
                        false,
                        nextRepeat,
                        prev.customerChallengedAnswer() || hasContrast,
                        false,
                        prev.requiresHumanAssistance() || nextRepeat >= 3
                ));
            } else {
                requests.add(new UnresolvedCustomerRequest(
                        trimmed,
                        semQuestion,
                        semIntent,
                        refEntity,
                        lastBotMessage,
                        turnIndex,
                        false,
                        false,
                        0,
                        hasContrast,
                        false,
                        false
                ));
            }
            return;
        }

        if (SentimentIntentLayer.isComprehensionFailureMessage(trimmed)) {
            int targetIdx = -1;
            for (int i = requests.size() - 1; i >= 0; i--) {
                if (requests.get(i) != null && !requests.get(i).resolved()) {
                    targetIdx = i;
                    break;
                }
            }
            if (targetIdx < 0 && !requests.isEmpty()) {
                targetIdx = requests.size() - 1;
            }
            if (targetIdx >= 0) {
                UnresolvedCustomerRequest prev = requests.get(targetIdx);
                boolean needsHuman = prev.requiresHumanAssistance()
                        || (prev.answeredByBot() && prev.customerChallengedAnswer() && prev.repeatOrChallengeCount() >= 1);
                requests.set(targetIdx, prev.withCustomerRepeatOrChallenge(prev.rawCustomerMessage(), true, needsHuman));
            } else if (lastBotMessage != null && !lastBotMessage.isBlank()) {
                String refEntity = SentimentIntentLayer.extractReferencedDetailEntity(trimmed, lastBotMessage, activePkgContext);
                if (refEntity == null || refEntity.isBlank()) {
                    refEntity = activePkgContext != null && !activePkgContext.isBlank() ? activePkgContext : "bot_explanation";
                }
                requests.add(new UnresolvedCustomerRequest(
                        trimmed,
                        "Giải thích ngắn gọn, dễ hiểu hơn về " + refEntity,
                        "COMPREHENSION_CLARIFICATION",
                        refEntity,
                        lastBotMessage,
                        turnIndex,
                        true,
                        false,
                        1,
                        true,
                        false,
                        false
                ));
            }
            return;
        }

        if (PrimaryActionRouter.isFrustrationMessage(trimmed)) {
            for (int i = requests.size() - 1; i >= 0; i--) {
                UnresolvedCustomerRequest prev = requests.get(i);
                if (prev != null && !prev.resolved()) {
                    boolean needsHuman = prev.requiresHumanAssistance()
                            || (prev.answeredByBot() && prev.repeatOrChallengeCount() >= 2);
                    requests.set(i, prev.withCustomerRepeatOrChallenge(prev.rawCustomerMessage(), true, needsHuman));
                    break;
                }
            }
        }
    }

    private static boolean markBotTurnAgainstOutstandingRequests(
            List<UnresolvedCustomerRequest> requests,
            String botReply
    ) {
        if (requests == null || requests.isEmpty() || botReply == null || botReply.isBlank()) {
            return false;
        }
        for (int i = requests.size() - 1; i >= 0; i--) {
            UnresolvedCustomerRequest req = requests.get(i);
            if (req != null && !req.resolved()) {
                boolean answered = doesBotReplyAnswerDetailRequest(botReply, req);
                requests.set(i, req.withBotAttempt(answered, answered));
                return answered;
            }
        }
        return false;
    }

    private static void markDeliveredBotReplyAgainstOutstandingRequests(
            List<UnresolvedCustomerRequest> requests,
            String deliveredBotReply,
            boolean needsHumanHandoff
    ) {
        if (requests == null || requests.isEmpty() || deliveredBotReply == null || deliveredBotReply.isBlank()) {
            return;
        }
        for (int i = requests.size() - 1; i >= 0; i--) {
            UnresolvedCustomerRequest req = requests.get(i);
            if (req != null && !req.resolved()) {
                boolean answered = !needsHumanHandoff && doesBotReplyAnswerDetailRequest(deliveredBotReply, req);
                UnresolvedCustomerRequest updated = req.withBotAttempt(answered, answered);
                if (needsHumanHandoff && !answered) {
                    updated = new UnresolvedCustomerRequest(
                            updated.rawCustomerMessage(),
                            updated.semanticQuestion(),
                            updated.semanticIntent(),
                            updated.referencedEntity(),
                            updated.contextBotExcerpt(),
                            updated.turnIndex(),
                            false,
                            false,
                            updated.repeatOrChallengeCount(),
                            updated.customerChallengedAnswer(),
                            false,
                            true
                    );
                }
                requests.set(i, updated);
                break;
            }
        }
    }

    public static boolean doesBotReplyAnswerDetailRequest(String botReply, UnresolvedCustomerRequest req) {
        if (botReply == null || botReply.isBlank() || req == null) {
            return false;
        }
        String lower = Normalizer.normalize(botReply.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        if ((lower.contains("nhóm dịch vụ chính") && lower.contains("quan tâm"))
                || (lower.contains("gửi mức giá gói nào") && !lower.contains("bao gồm"))
                || lower.contains("chưa có thông tin chi tiết")
                || lower.contains("chưa ghi chú chi tiết mục này")
                || lower.contains("chưa được ghi rõ trong bảng thông tin")) {
            return false;
        }
        String entity = req.referencedEntity() != null ? req.referencedEntity().toLowerCase(Locale.ROOT) : "";
        String semIntent = req.semanticIntent() != null ? req.semanticIntent() : "";
        if ("COMPREHENSION_CLARIFICATION".equals(semIntent)) {
            return PRICE_NUMBER_PATTERN.matcher(lower).find()
                    || lower.contains("bao gồm")
                    || lower.contains("gồm")
                    || lower.contains("trọn gói")
                    || lower.contains("kết hợp")
                    || lower.contains("tập trung")
                    || lower.contains("quyền lợi")
                    || lower.contains("phục vụ")
                    || lower.contains("hỗ trợ");
        }
        if ("BUFFET_MENU_ITEMS_FAQ".equals(semIntent)) {
            return lower.contains("thực đơn")
                    || lower.contains("gồm các món")
                    || lower.contains("các món ăn nhẹ")
                    || (lower.contains("gồm:") && (lower.contains("ăn nhẹ") || lower.contains("buffet") || lower.contains("thực dưỡng") || lower.contains("điểm tâm")))
                    || (lower.contains("món") && lower.contains(","));
        }
        if (entity.contains("buffet") || entity.contains("thực dưỡng") || entity.contains("ăn nhẹ") || entity.contains("điểm tâm")) {
            // Post-price mechanical mention ("kèm buffet thực dưỡng rất dễ chịu ạ") is NOT an explanation of the concept
            boolean hasExplanation = lower.contains("điểm tâm")
                    || lower.contains("ăn nhẹ")
                    || lower.contains("phục hồi")
                    || lower.contains("hồi phục")
                    || lower.contains("miễn phí")
                    || lower.contains("sau liệu trình")
                    || lower.contains("sau khi làm")
                    || lower.contains("thực đơn")
                    || lower.contains("món")
                    || lower.contains("nước ép")
                    || lower.contains("trà dưỡng sinh")
                    || lower.contains("ấm bụng")
                    || lower.contains("bổ sung năng lượng");
            return hasExplanation;
        }
        if (entity.contains("phòng")) {
            return (lower.contains("phòng") || lower.contains("khu trị liệu") || lower.contains("không gian") || lower.contains("giường"))
                    && (lower.contains("riêng") || lower.contains("rèm") || lower.contains("cặp đôi") || lower.contains("tiêu chuẩn") || lower.contains("không gian"));
        }
        if (entity.contains("thời lượng")) {
            return lower.contains("phút") || lower.contains("thời lượng") || lower.contains("tiếng");
        }
        if (entity.contains("quy trình") || entity.contains("kỹ thuật") || entity.contains("ngâm") || entity.contains("xông") || entity.contains("chườm") || entity.contains("tai")) {
            return lower.contains("quy trình") || lower.contains("bước") || lower.contains("kỹ thuật")
                    || lower.contains("bấm huyệt") || lower.contains("massage") || lower.contains("ngâm")
                    || lower.contains("thảo dược") || lower.contains("xông") || lower.contains("gồm") || lower.contains("bao gồm");
        }
        if (entity.contains("kỹ thuật viên")) {
            return lower.contains("kỹ thuật viên") || lower.contains("ktv") || lower.contains("tay nghề");
        }
        if (entity.contains("tip") || entity.contains("phụ phí")) {
            return lower.contains("tip") || lower.contains("phụ phí") || lower.contains("tùy tâm") || lower.contains("trọn gói");
        }
        if (entity.contains("đặt lịch") || entity.contains("cọc")) {
            return lower.contains("đặt lịch") || lower.contains("cọc") || lower.contains("giữ chỗ") || lower.contains("giữ giường");
        }
        if (entity.contains("giờ") || entity.contains("mở cửa") || entity.contains("đóng cửa") || entity.contains("địa chỉ") || entity.contains("cơ sở")) {
            return lower.contains("giờ") || lower.contains("mở cửa") || lower.contains("đóng cửa")
                    || lower.contains("cơ sở") || lower.contains("địa chỉ") || lower.contains("kiểm tra");
        }
        return lower.contains("bao gồm") || lower.contains("gồm") || lower.contains("quyền lợi")
                || lower.contains("phục vụ") || lower.contains("hỗ trợ") || lower.contains("kiểm tra")
                || lower.contains("giờ") || lower.contains("mở cửa");
    }

    public static String resolveDetailSemanticIntent(String rawQuestion, String referencedEntity) {
        String norm = com.example.docmind.service.text.VietnameseQueryNormalizer.normalizeForIntentMatching(rawQuestion);
        String entity = referencedEntity != null ? referencedEntity.toLowerCase(Locale.ROOT) : "";
        if (norm.matches("(?isU).*\\b(món\\s+gì|những\\s+món\\s+gì|món\\s+nào|thực\\s+đơn|menu|ăn\\s+gì|uống\\s+gì)\\b.*")
                || entity.contains("thực đơn")) {
            return "BUFFET_MENU_ITEMS_FAQ";
        }
        if (entity.contains("buffet") || entity.contains("thực dưỡng") || entity.contains("ăn nhẹ") || entity.contains("điểm tâm")) {
            return "SERVICE_BENEFIT_OR_CONCEPT_DEFINITION";
        }
        if (entity.contains("phòng")) {
            return "SERVICE_FACILITY_OR_ROOM_FAQ";
        }
        if (entity.contains("thời lượng")) {
            return "SERVICE_DURATION_FAQ";
        }
        if (entity.contains("quy trình") || entity.contains("kỹ thuật") || entity.contains("ngâm") || entity.contains("xông") || entity.contains("chườm") || entity.contains("tai")) {
            return "SERVICE_PROCESS_OR_TECHNIQUE_FAQ";
        }
        if (entity.contains("tip") || entity.contains("cọc") || entity.contains("đặt lịch") || entity.contains("kỹ thuật viên") || entity.contains("địa chỉ")) {
            return "SERVICE_POLICY_OR_FACILITY_FAQ";
        }
        return "SERVICE_INCLUDED_BENEFITS_FAQ";
    }

    public static String buildCanonicalSemanticQuestion(String rawQuestion, String referencedEntity) {
        String semIntent = resolveDetailSemanticIntent(rawQuestion, referencedEntity);
        String entity = (referencedEntity != null && !referencedEntity.isBlank()) ? referencedEntity : "chi tiết dịch vụ";
        return switch (semIntent) {
            case "BUFFET_MENU_ITEMS_FAQ" -> "Thực đơn buffet thực dưỡng / điểm tâm ăn nhẹ gồm những món gì?";
            case "SERVICE_BENEFIT_OR_CONCEPT_DEFINITION" -> "Giải thích ý nghĩa và quyền lợi của " + entity + " trong gói dịch vụ";
            case "SERVICE_FACILITY_OR_ROOM_FAQ" -> "Thông tin về không gian phòng và sự riêng tư tại cơ sở";
            case "SERVICE_DURATION_FAQ" -> "Thông tin về thời lượng thực hiện của gói dịch vụ";
            case "SERVICE_PROCESS_OR_TECHNIQUE_FAQ" -> "Thông tin về quy trình và kỹ thuật thực hiện của " + entity;
            case "SERVICE_POLICY_OR_FACILITY_FAQ" -> "Thông tin chính sách và tiện ích liên quan đến " + entity;
            default -> "Thông tin chi tiết về các quyền lợi bao gồm trong " + entity;
        };
    }

    /**
     * ACI V3 P2 (§4.7, cấm #6): nhận diện vùng/chi nhánh CHỈ từ cue tường minh trong lời khách.
     * Khách chưa nêu chi nhánh và chưa có vùng đã lưu -> trả {@code null} (UNSPECIFIED),
     * TUYỆT ĐỐI không mặc định "HN".
     */
    public static String detectPreferredRegion(String lowerText, String currentRegion) {
        String fallback = (currentRegion != null && !currentRegion.isBlank()) ? currentRegion : null;
        if (lowerText == null || lowerText.isBlank()) {
            return fallback;
        }
        if (lowerText.matches("(?isU).*\\b(sài\\s+gòn|sai\\s+gon|quận\\s+1|quan\\s+1|tp\\.?\\s*hcm|hồ\\s+chí\\s+minh|nguyễn\\s+công\\s+trứ|miền\\s+nam)\\b.*")) {
            return "SG";
        }
        if (lowerText.matches("(?isU).*\\b(hà\\s+nội|ha\\s+noi|đống\\s+đa|cầu\\s+giấy|văn\\s+quán|hà\\s+đông|miền\\s+bắc)\\b.*")) {
            return "HN";
        }
        return fallback;
    }

    /**
     * ACI V3 P2 (§4.7): Phân giải vùng/chi nhánh CHỈ từ bằng chứng tường minh
     * (tin nhắn hiện tại -> lịch sử lượt user -> vùng đã lưu). Khách chưa nêu
     * chi nhánh -> trả {@code null} (UNSPECIFIED), TUYỆT ĐỐI không default "HN".
     */
    public static String resolveExplicitRegionAcrossTurns(
            String customerMessage,
            List<com.example.docmind.dto.bot.FanpageBotRequest.ConversationTurn> history,
            String currentRegion
    ) {
        if (customerMessage != null && !customerMessage.isBlank()) {
            String fromMessage = detectPreferredRegion(customerMessage.toLowerCase(Locale.ROOT), null);
            if (fromMessage != null) {
                return fromMessage;
            }
        }
        if (history != null) {
            for (int i = history.size() - 1; i >= 0; i--) {
                com.example.docmind.dto.bot.FanpageBotRequest.ConversationTurn turn = history.get(i);
                if (turn == null || turn.content() == null || turn.content().isBlank()) {
                    continue;
                }
                String role = turn.role() != null ? turn.role().strip() : "";
                if (!"user".equalsIgnoreCase(role) && !"customer".equalsIgnoreCase(role)) {
                    continue;
                }
                String fromHistory = detectPreferredRegion(turn.content().toLowerCase(Locale.ROOT), null);
                if (fromHistory != null) {
                    return fromHistory;
                }
            }
        }
        return (currentRegion != null && !currentRegion.isBlank()) ? currentRegion : null;
    }

    private static boolean hasAlternativePackageWithSameDuration(
            List<PackageCatalogService.PackageSummary> catalog,
            String durationStr,
            String rejectedPkgCanonical
    ) {
        if (catalog == null || catalog.size() < 2 || durationStr == null || durationStr.isBlank()) {
            return false;
        }
        int targetDur = parseDurationInt(durationStr);
        if (targetDur <= 0) {
            return false;
        }
        int matchCount = 0;
        for (PackageCatalogService.PackageSummary pkg : catalog) {
            if (pkg == null || pkg.durationMinutes() == null || pkg.durationMinutes() != targetDur) {
                continue;
            }
            String pkgCanon = toCanonicalPackageCode(pkg.displayName(), String.valueOf(pkg.durationMinutes()));
            if (rejectedPkgCanonical == null || !rejectedPkgCanonical.equalsIgnoreCase(pkgCanon)) {
                matchCount++;
            }
        }
        return matchCount > 0;
    }

    public static String shortHash(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String norm = Normalizer.normalize(text, Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .strip();
        return Integer.toHexString(norm.hashCode());
    }

    private static void appendBubbleHashes(List<String> hashes, String text) {
        if (hashes == null || text == null || text.isBlank()) {
            return;
        }
        String hash = shortHash(text);
        if (!hashes.contains(hash)) {
            hashes.add(hash);
            while (hashes.size() > 12) {
                hashes.remove(0);
            }
        }
    }

    public static String extractDurationFromText(String lowerText) {
        if (lowerText == null || lowerText.isBlank()) {
            return null;
        }
        if (lowerText.contains("1 tiếng rưỡi") || lowerText.contains("90 phút") || lowerText.contains("90p")) {
            return "90";
        }
        Matcher m = DURATION_ONLY_PATTERN.matcher(lowerText);
        String lastFound = null;
        while (m.find()) {
            lastFound = m.group(1);
        }
        return lastFound;
    }

    public static int parseDurationInt(String dur) {
        if (dur == null || dur.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(dur.replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static BigDecimal parsePriceToBigDecimal(String rawPrice) {
        if (rawPrice == null || rawPrice.isBlank()) {
            return null;
        }
        PackageCatalogService.ParsedPrice structured = PackageCatalogService.parseStructuredPrice(rawPrice);
        if (structured != null && structured.priceAmountVnd() > 0L) {
            return BigDecimal.valueOf(structured.priceAmountVnd());
        }
        String lower = rawPrice.toLowerCase(Locale.ROOT).strip();
        String digits = lower.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            return null;
        }
        try {
            long val = Long.parseLong(digits);
            if (val < 1_000_000L && lower.contains("k")) {
                val *= 1000L;
            }
            return BigDecimal.valueOf(val);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final Pattern BOT_PACKAGE_MENTION_PATTERN = Pattern.compile(
            "(?iU)(?:gói\\s+dịch\\s+vụ\\s+|dịch\\s+vụ\\s+|liệu\\s+trình\\s+|gói\\s+|thử\\s+1\\s+buổi\\s+|thử\\s+)"
            + "((?:(?:[a-z]{2,3}\\d{2}\\s+)?(?:massage|dưỡng\\s+sinh|ngâm|vệ\\s+sinh|chăm\\s+sóc|trị\\s+liệu|gội\\s+đầu|niềng|bọc|tẩy\\s+trắng|nhổ|trồng|cạo\\s+vôi|lấy\\s+tủy|combo|sofa|đệm|nệm|bàn|ghế))[^,.?!:;()\\n\\-–—]{2,85}?)"
            + "(?=\\s+(?:trọn\\s+gói|chỉ\\s+|bên\\s+em|có\\s+giá|với\\s+giá|giá\\s+|nhé|ạ|đi|nữa|trước|sẽ|giúp|là\\s+|\\d{2,4}\\s*k\\b|[\\-–—]|\\(|,|\\.|\\?|$))"
    );

    public static List<String> extractAllCanonicalPackagesFromBotText(String lower, String fallbackDuration) {
        if (lower == null || lower.isBlank()) {
            return List.of();
        }
        if ((lower.contains("quan tâm") || lower.contains("nhóm dịch vụ"))
                && lower.contains("hay") && lower.contains("?")
                && !lower.contains("có giá") && fallbackDuration == null) {
            return List.of();
        }
        Set<String> result = new LinkedHashSet<>();
        Matcher m = BOT_PACKAGE_MENTION_PATTERN.matcher(lower);
        while (m.find()) {
            String rawPkg = m.group(1).replaceAll("\\s+", " ").strip();
            rawPkg = rawPkg.replaceAll("(?iU)\\s+(?:bên\\s+em|trước|nhé|ạ)$", "").strip();
            String localDur = extractDurationFromText(rawPkg);
            if (localDur == null) {
                localDur = fallbackDuration;
            }
            if (localDur != null && !rawPkg.contains("phút") && !rawPkg.contains("tháng") && !rawPkg.contains("buổi")) {
                rawPkg = rawPkg + " " + localDur + " phút";
            }
            String canon = toCanonicalPackageCode(rawPkg, localDur);
            if (canon != null) {
                result.add(canon);
            }
        }
        return new ArrayList<>(result);
    }

    public static String extractPackageDisplayFromBotText(String lower, String duration) {
        if (lower == null || lower.isBlank()) {
            return null;
        }
        // Không trích xuất package từ câu hỏi menu nhóm dịch vụ rộng ở Turn 1
        if ((lower.contains("quan tâm") || lower.contains("nhóm dịch vụ"))
                && lower.contains("hay") && lower.contains("?")
                && !lower.contains("có giá") && duration == null) {
            return null;
        }
        String dur = duration != null ? duration : extractDurationFromText(lower);
        boolean hasRecommendationCue = dur != null
                || lower.contains("gợi ý")
                || lower.contains("phù hợp nhất")
                || lower.contains("có giá")
                || lower.contains("với giá")
                || lower.contains("trải nghiệm thử")
                || lower.contains("tham khảo")
                || lower.contains("chốt")
                || lower.contains("lấy")
                || lower.contains("chọn");
        if (!hasRecommendationCue) {
            return null;
        }
        if (dur != null && lower.contains("gói ngắn " + dur + " phút")) {
            String shortPkg = "gói ngắn " + dur + " phút";
            CANONICAL_TO_DISPLAY_CACHE.put("goi_ngan_" + dur, shortPkg);
            return shortPkg;
        }
        Matcher m = BOT_PACKAGE_MENTION_PATTERN.matcher(lower);
        if (m.find()) {
            String rawPkg = m.group(1).replaceAll("\\s+", " ").strip();
            rawPkg = rawPkg.replaceAll("(?iU)\\s+(?:bên\\s+em|trước|nhé|ạ)$", "").strip();
            String localDur = extractDurationFromText(rawPkg);
            if (localDur == null) {
                localDur = dur;
            }
            if (localDur != null && !rawPkg.contains("phút") && !rawPkg.contains("tháng") && !rawPkg.contains("buổi")) {
                rawPkg = rawPkg + " " + localDur + " phút";
            }
            toCanonicalPackageCode(rawPkg, localDur);
            return rawPkg;
        }
        return null;
    }

    public static String toCanonicalPackageCode(String pkgDisplay, String duration) {
        if ((pkgDisplay == null || pkgDisplay.isBlank()) && (duration == null || duration.isBlank())) {
            return null;
        }
        String lower = pkgDisplay != null ? pkgDisplay.toLowerCase(Locale.ROOT).strip() : "";
        String dur = duration != null ? duration : extractDurationFromText(lower);
        if (lower.matches("^[a-z0-9_]+$") && lower.contains("_")) {
            return lower;
        }
        String ascii = SentimentIntentLayer.removeDiacritics(lower);
        ascii = ascii.replaceFirst("^(?:goi\\s+dich\\s+vu|dich\\s+vu|lieu\\s+trinh|goi)\\s+", "");
        ascii = ascii.replaceAll("\\b(?:am|ngoai\\s+thu\\s+gian|toan\\s+than|thu\\s+gian)\\b", " ");
        ascii = ascii.replaceAll("\\b\\d{1,3}\\s*(?:phut|p)\\b", " ");
        String slug = ascii.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (slug.isEmpty()) {
            slug = "package";
        }
        String canonical = (dur != null && !slug.endsWith("_" + dur)) ? slug + "_" + dur : slug;
        if (!lower.isEmpty()) {
            String displayWithDur = (dur != null && !lower.contains("phút") && !lower.contains("tháng"))
                    ? lower + " " + dur + " phút"
                    : lower;
            CANONICAL_TO_DISPLAY_CACHE.put(canonical, displayWithDur);
        }
        return canonical;
    }

    public static String toDisplayPackageName(String canonicalOrDisplay, String fallbackDuration) {
        if (canonicalOrDisplay == null || canonicalOrDisplay.isBlank()) {
            return fallbackDuration != null ? "gói " + fallbackDuration + " phút" : "gói tiêu chuẩn";
        }
        String trimmed = canonicalOrDisplay.strip();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        String cached = CANONICAL_TO_DISPLAY_CACHE.get(lower);
        if (cached != null && !cached.isBlank()) {
            return cached;
        }
        if (!lower.contains("_")) {
            if (fallbackDuration != null && !lower.contains("phút") && !lower.contains("tháng")) {
                return trimmed + " " + fallbackDuration + " phút";
            }
            return trimmed;
        }
        String dur = extractDurationFromText(lower.replace('_', ' '));
        if (dur == null) {
            Matcher dm = Pattern.compile("_(\\d{2,3})$").matcher(lower);
            if (dm.find()) {
                dur = dm.group(1);
            } else {
                dur = fallbackDuration;
            }
        }
        for (Map.Entry<String, String> entry : CANONICAL_TO_DISPLAY_CACHE.entrySet()) {
            String keyPrefix = entry.getKey().replaceAll("_\\d+$", "");
            String targetPrefix = lower.replaceAll("_\\d+$", "");
            if (!keyPrefix.isEmpty() && keyPrefix.equals(targetPrefix) && dur != null) {
                return entry.getValue().replaceAll("\\b\\d{2,3}\\s*phút\\b", dur + " phút");
            }
        }
        String readable = lower.replaceAll("_\\d+$", "").replace('_', ' ').strip();
        return dur != null ? readable + " " + dur + " phút" : readable;
    }

    private static boolean detectDocsHasFeedback(List<ReciprocalRankFusion.FusedChunk> fusedChunks) {
        if (fusedChunks == null || fusedChunks.isEmpty()) {
            return false;
        }
        for (ReciprocalRankFusion.FusedChunk fc : fusedChunks) {
            if (fc == null || fc.chunk() == null || fc.chunk().text() == null) continue;
            String t = fc.chunk().text().toLowerCase(Locale.ROOT);
            if (t.contains("phản hồi khá tốt") || t.contains("phản hồi tốt") || t.contains("khách hàng đánh giá") || t.contains("testimonial")) {
                return true;
            }
        }
        return false;
    }

    private static boolean detectDocsHasRelaxationBenefit(List<ReciprocalRankFusion.FusedChunk> fusedChunks) {
        if (fusedChunks == null || fusedChunks.isEmpty()) {
            return true;
        }
        for (ReciprocalRankFusion.FusedChunk fc : fusedChunks) {
            if (fc == null || fc.chunk() == null || fc.chunk().text() == null) continue;
            String t = fc.chunk().text().toLowerCase(Locale.ROOT);
            if (t.contains("làm mềm cơ") || t.contains("thả lỏng") || t.contains("thư giãn") || t.contains("dễ chịu")) {
                return true;
            }
        }
        return false;
    }

    private static boolean detectDocsHasPrivateRoom(List<ReciprocalRankFusion.FusedChunk> fusedChunks) {
        if (fusedChunks == null || fusedChunks.isEmpty()) {
            return false;
        }
        for (ReciprocalRankFusion.FusedChunk fc : fusedChunks) {
            if (fc == null || fc.chunk() == null || fc.chunk().text() == null) continue;
            String t = fc.chunk().text().toLowerCase(Locale.ROOT);
            if (t.contains("phòng riêng") || t.contains("phòng vip riêng")) {
                return true;
            }
        }
        return false;
    }

    public static String normalizeScopeToken(String rawScope) {
        if (rawScope == null || rawScope.isBlank()) {
            return "";
        }
        String s = rawScope.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
        if (s.equals("bắp chân") || s.equals("bàn chân")) return "chân";
        if (s.equals("đầu gối") || s.equals("khớp gối")) return "gối";
        if (s.equals("cánh tay") || s.equals("bàn tay")) return "tay";
        if (s.equals("thắt lưng")) return "lưng";
        if (s.equals("chân răng") || s.equals("răng hàm") || s.equals("răng khôn") || s.equals("răng cửa")) return "răng";
        return s;
    }

    private static void addNormalizedAtomicScopes(Set<String> scopes, String rawScope) {
        if (rawScope == null || rawScope.isBlank()) {
            return;
        }
        String norm = normalizeScopeToken(rawScope);
        if ("cơ".equals(norm)) {
            return;
        }
        if (norm.contains(" ") && (norm.contains("cổ") || norm.contains("vai") || norm.contains("gáy"))) {
            for (String part : norm.split("\\s+")) {
                if (!part.isBlank()) {
                    scopes.add(part);
                }
            }
            return;
        }
        scopes.add(norm);
    }

    /**
     * Trích xuất toàn bộ vùng tác động / phạm vi mục tiêu ({@code targetScopes}) từ câu nói của khách
     * thông qua {@link ScopeMatcher} đa ngành (đọc từ {@code scopeTags} / {@code scopeTaxonomy} và lọc mệnh đề phản bác/rút lại).
     */
    public static List<String> extractTargetScopes(String text, List<String> domainKeywords) {
        return ScopeMatcher.extractScopesFromText(text, domainKeywords);
    }

    /**
     * Trích xuất toàn bộ danh sách vấn đề/nhu cầu của khách hàng ({@code extractedNeeds})
     * theo đúng thứ tự xuất hiện, tự động chuẩn hóa cấu trúc đảo tiếng Việt ("chân đau" -> "đau chân").
     */
    public static List<String> extractAllNeeds(String text, List<String> domainKeywords) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String cleanText = SentimentIntentLayer.stripMismatchCritiqueClause(text);
        String lower = Normalizer.normalize(cleanText, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        Set<String> needs = new LinkedHashSet<>();

        Matcher m = MULTI_ENTITY_NEED_PATTERN.matcher(lower);
        while (m.find()) {
            String fwdMod = m.group("fwdMod");
            String fwdScope = m.group("fwdScope");
            String fwdScopeChain = m.group("fwdScopeChain");
            String fwdSide = m.group("fwdSide");
            String invScope = m.group("invScope");
            String invMod = m.group("invMod");
            String standalone = m.group("standalone");

            if (fwdMod != null && fwdScope != null) {
                String mod = fwdMod.replaceAll("\\s+", " ").strip();
                String scope = fwdScope.replaceAll("\\s+", " ").strip();
                if (mod.contains("nhói") && "vai".equals(scope) && fwdSide != null) {
                    needs.add("mỏi và thỉnh thoảng nhói vùng vai");
                } else {
                    needs.add(mod + " " + scope);
                }
                if (fwdScopeChain != null && !fwdScopeChain.isBlank()) {
                    String chainScope = fwdScopeChain.replaceAll("\\s+", " ").strip();
                    needs.add(mod + " " + chainScope);
                }
            } else if (invScope != null && invMod != null) {
                String mod = invMod.replaceAll("\\s+", " ").strip();
                String scope = invScope.replaceAll("\\s+", " ").strip();
                if ("ố vàng".equals(mod) || "xỉn màu".equals(mod) || "mẻ".equals(mod)) {
                    needs.add(scope + " " + mod);
                } else {
                    needs.add(mod + " " + scope);
                }
            } else if (standalone != null) {
                String st = standalone.replaceAll("\\s+", " ").strip();
                if ("stress".equals(st)) {
                    needs.add("căng thẳng");
                } else {
                    needs.add(st);
                }
            }
        }

        if (needs.isEmpty() && domainKeywords != null) {
            List<String> sortedKws = new ArrayList<>();
            for (String kw : domainKeywords) {
                if (kw != null && !kw.isBlank()) {
                    sortedKws.add(kw.strip().toLowerCase(Locale.ROOT));
                }
            }
            sortedKws.sort(java.util.Comparator.comparingInt(String::length).reversed());
            for (String kwLower : sortedKws) {
                if (lower.contains(kwLower)) {
                    boolean alreadyCovered = needs.stream().anyMatch(existing -> existing.contains(kwLower));
                    if (!alreadyCovered) {
                        if (lower.contains("vùng " + kwLower)) {
                            needs.add("mỏi vùng " + kwLower);
                        } else if (!isServiceOrModalityKeyword(kwLower)
                                && (lower.contains("đau") || lower.contains("mỏi") || lower.contains("nhức")
                                    || lower.contains("tê") || lower.contains("bị") || lower.contains("ố")
                                    || lower.contains("sâu") || lower.contains("viêm") || lower.contains("sưng"))) {
                            needs.add(kwLower);
                        }
                    }
                }
            }
        }
        return new ArrayList<>(needs);
    }

    public static boolean isServiceOrModalityKeyword(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String low = Normalizer.normalize(text.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        return low.contains("massage") || low.contains("mát xa") || low.contains("xoa bóp")
                || low.contains("ngâm") || low.contains("thảo dược") || low.contains("bồn")
                || low.contains("xông") || low.contains("gội") || low.contains("dưỡng sinh")
                || low.contains("vệ sinh tai") || low.contains("lấy ráy") || low.contains("tẩy da")
                || low.contains("chăm sóc") || low.contains("liệu trình") || low.contains("buffet")
                || low.contains("thực dưỡng") || low.contains("niềng") || low.contains("bọc")
                || low.contains("tẩy trắng") || low.contains("trồng răng") || low.contains("nhổ răng")
                || low.contains("cạo vôi") || low.contains("lấy tủy") || low.contains("sofa");
    }

    public static String joinNeedsNaturally(List<String> needs) {
        if (needs == null || needs.isEmpty()) {
            return null;
        }
        List<String> filtered = new ArrayList<>();
        for (String n : needs) {
            if (n != null && !n.isBlank() && !isServiceOrModalityKeyword(n)) {
                filtered.add(n);
            }
        }
        if (filtered.isEmpty()) {
            return null;
        }
        if (filtered.size() == 1) {
            return filtered.get(0);
        }
        if (filtered.size() == 2) {
            return filtered.get(0) + " và " + filtered.get(1);
        }
        return String.join(", ", filtered.subList(0, filtered.size() - 1)) + " và " + filtered.get(filtered.size() - 1);
    }

    public static String extractProblemOrNeedPhrase(String text, List<String> domainKeywords) {
        List<String> needs = extractAllNeeds(text, domainKeywords);
        return joinNeedsNaturally(needs);
    }

    public static String resolveServiceFamilyFromText(String lowerText, Set<String> knownBodyAreas) {
        return resolveServiceFamilyFromText(lowerText, knownBodyAreas, List.of());
    }

    public static String resolveServiceFamilyFromText(String lowerText, Set<String> knownBodyAreas, List<String> domainKeywords) {
        String lower = lowerText != null ? lowerText : "";
        if (lower.matches("(?isU).*\\b(gội\\s+đầu(?:\\s+dưỡng\\s+sinh)?)\\b.*")) {
            return "gội đầu";
        }
        if (lower.matches("(?isU).*\\b(vệ\\s+sinh\\s+tai)\\b.*")) {
            return "ear_care";
        }
        if (lower.matches("(?isU).*\\b(ngâm\\s+chân|ngâm\\s+bồn|bồn\\s+thảo\\s+dược|thảo\\s+dược)\\b.*")) {
            return "herbal_soak";
        }
        if (lower.matches("(?isU).*\\b(massage\\s+body|toàn\\s+thân|đá\\s+nóng)\\b.*")) {
            return "body";
        }
        if (lower.matches("(?isU).*\\b(massage|mát\\s+xa|xoa\\s+bóp)\\b.*")) {
            if (knownBodyAreas != null && (knownBodyAreas.contains("vai") || knownBodyAreas.contains("gáy") || knownBodyAreas.contains("cổ"))) {
                return "neck_shoulder";
            }
            return "massage_generic";
        }
        if (lower.matches("(?isU).*\\b(niềng\\s+răng|bọc\\s+răng\\s+sứ|tẩy\\s+trắng\\s+răng|trồng\\s+răng|nhổ\\s+răng|cạo\\s+vôi\\s+răng|lấy\\s+tủy)\\b.*")) {
            Matcher dm = Pattern.compile("(?isU)\\b(niềng\\s+răng|bọc\\s+răng\\s+sứ|tẩy\\s+trắng\\s+răng|trồng\\s+răng|nhổ\\s+răng|cạo\\s+vôi\\s+răng|lấy\\s+tủy)\\b").matcher(lower);
            if (dm.find()) {
                return dm.group(1).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            }
        }
        if (domainKeywords != null) {
            for (String kw : domainKeywords) {
                if (kw == null || kw.isBlank()) continue;
                String kwLower = kw.strip().toLowerCase(Locale.ROOT);
                if (kwLower.split("\\s+").length >= 2 && lower.contains(kwLower)) {
                    return kwLower;
                }
            }
        }
        if (knownBodyAreas != null && !knownBodyAreas.isEmpty()) {
            boolean hasNeckShoulder = knownBodyAreas.contains("vai") || knownBodyAreas.contains("gáy") || knownBodyAreas.contains("cổ");
            boolean hasLowerLimb = knownBodyAreas.contains("chân") || knownBodyAreas.contains("gối");
            if (hasNeckShoulder && !hasLowerLimb) {
                return "neck_shoulder";
            }
            if (hasLowerLimb && !hasNeckShoulder) {
                return "lower_limb_care";
            }
            if (knownBodyAreas.contains("lưng") || knownBodyAreas.contains("toàn thân") || knownBodyAreas.contains("body")) {
                return "body";
            }
        }
        return null;
    }

    public String knownServiceInterest() {
        if ("herbal_soak".equals(knownServiceFamily)) {
            return "ngâm bồn thảo dược";
        }
        if ("ear_care".equals(knownServiceFamily)) {
            return "vệ sinh tai ngoài";
        }
        if ("gội đầu".equals(knownServiceFamily)) {
            return "gội đầu dưỡng sinh";
        }
        if ("body".equals(knownServiceFamily)) {
            return "massage toàn thân";
        }
        if ("neck_shoulder".equals(knownServiceFamily)) {
            return "massage cổ vai";
        }
        if (known != null && known.itemInterest() != null && !known.itemInterest().isBlank()) {
            return known.itemInterest();
        }
        return knownServiceFamily;
    }

    public String lastDiscussedPackageId() {
        String offered = lastOfferedPackageId();
        if (offered != null && !offered.isBlank()) {
            return offered;
        }
        if (knownRecommendedPackage != null && !knownRecommendedPackage.isBlank()) {
            String canon = toCanonicalPackageCode(knownRecommendedPackage, extractDurationFromText(knownRecommendedPackage));
            return canon != null ? canon : knownRecommendedPackage;
        }
        if ("herbal_soak".equals(knownServiceFamily) && availablePackageCatalog != null) {
            for (PackageCatalogService.PackageSummary s : availablePackageCatalog) {
                if (s != null && s.includesSummary() != null
                        && s.includesSummary().toLowerCase(Locale.ROOT).contains("ngâm bồn")) {
                    return s.packageUid() != null ? s.packageUid() : s.code();
                }
            }
        }
        return null;
    }

    public String lastQuotedPackageId() {
        if (!priceDiscussed) {
            return null;
        }
        return lastOfferedPackageId();
    }

    public String lastBotQuestionType() {
        String lastQ = lastQuestion != null ? lastQuestion.toLowerCase(Locale.ROOT) : "";
        String lastMsg = lastAssistantMessage != null ? lastAssistantMessage.toLowerCase(Locale.ROOT) : "";
        if (lastQ.contains("khung giờ") || lastQ.contains("mấy giờ") || lastQ.contains("giữ lịch")
                || lastQ.contains("giữ chỗ") || lastQ.contains("số điện thoại") || lastQ.contains("sđt")
                || "CONFIRM_BOOKING_DETAILS".equals(lastBotAction)) {
            return "ASK_BOOKING_TIME";
        }
        if (trialInviteOffered
                || "INVITE_ONE_SESSION_TRIAL".equals(lastQuestionIntent)
                || lastQ.contains("thử 1 buổi") || lastQ.contains("thử trải nghiệm")
                || lastQ.contains("phù hợp với thời gian") || lastQ.contains("muốn thử")
                || lastQ.contains("muốn trải nghiệm")) {
            return "INVITE_TRIAL";
        }
        if ("ASK_CONDITION".equals(lastQuestionIntent)
                || "ACKNOWLEDGE_AND_ASK_PREFERENCE".equals(lastBotAction)
                || lastQ.contains("mới bị") || lastQ.contains("lâu chưa") || lastQ.contains("kéo dài")
                || lastQ.contains("thi thoảng") || lastQ.contains("tần suất")) {
            return "ASK_CONDITION";
        }
        if ("CLARIFY_SERVICE_TYPE".equals(lastQuestionIntent)
                || "CLARIFY_DURATION".equals(lastQuestionIntent)
                || "CLARIFY_SERVICE_DURATION".equals(lastBotAction)
                || (lastQ.contains("dịch vụ nào") && lastQ.contains("hay"))) {
            return "CLARIFY_SERVICE_TYPE";
        }
        if (lastQ.contains("tìm hiểu thêm") || lastQ.contains("hỗ trợ gì") || lastQ.contains("đúng không")) {
            return "CONFIRM_INFO";
        }
        if (greetingSent && (lastQ.contains("dịch vụ gì") || lastQ.contains("dịch vụ nào") || lastMsg.contains("quan tâm"))) {
            return "ASK_SERVICE_INTEREST";
        }
        return "NONE";
    }

    public boolean botApologizedInLastTurn() {
        if (lastAssistantMessage == null || lastAssistantMessage.isBlank()) {
            return false;
        }
        return lastAssistantMessage.toLowerCase(Locale.ROOT).contains("xin lỗi");
    }

    public String stateSnapshotSummary() {
        return String.format(
                Locale.ROOT,
                "{serviceInterest=%s, symptoms=%s, bodyAreas=%s, discussedPkg=%s, quotedPkg=%s, quotedPrice=%s, priceReq=%s, priceDiscussed=%s, lastAction=%s, lastQType=%s}",
                knownServiceInterest(),
                knownSymptoms,
                knownBodyAreas,
                lastDiscussedPackageId(),
                lastQuotedPackageId(),
                priceDiscussed ? lastOfferedPrice() : null,
                priceRequestedInSession,
                priceDiscussed,
                lastBotAction,
                lastBotQuestionType()
        );
    }

    public static String inferDefaultPackageName(String serviceFamily, List<String> bodyAreas, Set<String> recommendedOptions) {
        return inferDefaultPackageName(serviceFamily, bodyAreas != null ? new LinkedHashSet<>(bodyAreas) : Set.of(), recommendedOptions);
    }

    public static String inferDefaultPackageName(String serviceFamily, Set<String> bodyAreas, Set<String> recommendedOptions) {
        for (Map.Entry<String, String> entry : CANONICAL_TO_DISPLAY_CACHE.entrySet()) {
            if (recommendedOptions != null) {
                for (String opt : recommendedOptions) {
                    if (entry.getValue().contains(opt)) {
                        return entry.getValue();
                    }
                }
            }
        }
        if (serviceFamily != null && !serviceFamily.isBlank()
                && !"neck_shoulder".equals(serviceFamily)
                && !"lower_limb_care".equals(serviceFamily)
                && !"body".equals(serviceFamily)
                && !"herbal_soak".equals(serviceFamily)
                && !"ear_care".equals(serviceFamily)
                && !"massage_generic".equals(serviceFamily)) {
            return serviceFamily;
        }
        if (recommendedOptions != null && !recommendedOptions.isEmpty()) {
            return "gói " + recommendedOptions.iterator().next();
        }
        return null;
    }

    private static String buildSessionKey(UUID pageId, String senderId) {
        return buildSessionKey(pageId, senderId, null);
    }

    public static String buildSessionKey(UUID pageId, String senderId, String conversationId) {
        String pagePart = pageId != null ? pageId.toString() : "default";
        String senderPart = (senderId != null && !senderId.isBlank()) ? senderId.strip() : null;
        String convPart = (conversationId != null && !conversationId.isBlank()) ? conversationId.strip() : null;
        if (senderPart == null && convPart == null) {
            return null;
        }
        if (convPart != null) {
            return pagePart + "::" + (senderPart != null ? senderPart : "anon") + "::" + convPart;
        }
        return pagePart + "::" + senderPart;
    }

    public int retentionAttemptCount() {
        return downsellOffered ? 1 : 0;
    }

    public String lastServiceOffered() {
        if (heldPackage != null && !heldPackage.isBlank()) {
            return heldPackage;
        }
        if (lastQuotedPackage != null && !lastQuotedPackage.isBlank()) {
            return lastQuotedPackage;
        }
        return knownRecommendedPackage;
    }

    public BigDecimal lastPriceOffered() {
        return lastQuotedPrice != null ? lastQuotedPrice : downsellPrice;
    }

    public String lastObjectionResponse() {
        if (stage == ConversationStage.OBJECTION
                || (lastBotAction != null && lastBotAction.contains("OBJECTION"))
                || "STAGE_3_GRACEFUL_EXIT".equals(objectionStage)) {
            return lastAssistantMessage;
        }
        return null;
    }

    public List<String> unresolvedQuestions() {
        if (outstandingRequests == null || outstandingRequests.isEmpty()) {
            return List.of();
        }
        List<String> unresolved = new ArrayList<>();
        for (UnresolvedCustomerRequest item : outstandingRequests) {
            if (item != null && !item.resolved() && item.rawCustomerMessage() != null && !item.rawCustomerMessage().isBlank()) {
                unresolved.add(item.rawCustomerMessage());
            }
        }
        return Collections.unmodifiableList(unresolved);
    }

    /**
     * Danh sách các góc độ giá trị (Progressive Value Reveal: VALUE_ANGLE_1, VALUE_ANGLE_2)
     * đã thực sự được gửi tới khách hàng trong hội thoại.
     */
    public List<String> deliveredValueAngles() {
        if (usedTemplates == null || usedTemplates.isEmpty()) {
            return List.of();
        }
        Set<String> angles = new LinkedHashSet<>();
        for (String tpl : usedTemplates) {
            if ("VALUE_ANGLE_1".equals(tpl) || "VALUE_ANGLE_2".equals(tpl)) {
                angles.add(tpl);
            }
        }
        return List.copyOf(angles);
    }

    private static void purgeNegatedSymptomsAndScopes(
            String rawMessage,
            String cleanContent,
            Set<String> symptoms,
            Set<String> areas,
            List<String> domainKeywords
    ) {
        purgeNegatedSymptomsAndScopes(rawMessage, cleanContent, symptoms, areas, null, null, domainKeywords);
    }

    private static void purgeNegatedSymptomsAndScopes(
            String rawMessage,
            String cleanContent,
            Set<String> symptoms,
            Set<String> areas,
            Set<String> confirmedScopes,
            Set<String> forbiddenScopes,
            List<String> domainKeywords
    ) {
        List<String> negatedClauses = SentimentIntentLayer.extractNegatedSymptomClauses(rawMessage);
        if (negatedClauses.isEmpty()) {
            return;
        }
        Set<String> positiveSymptoms = new LinkedHashSet<>();
        Matcher posSym = SYMPTOM_TOKEN_PATTERN.matcher(cleanContent);
        while (posSym.find()) {
            positiveSymptoms.add(posSym.group(1).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip());
        }
        Set<String> positiveScopes = new LinkedHashSet<>(extractTargetScopes(cleanContent, domainKeywords));
        for (String clause : negatedClauses) {
            Matcher negSym = SYMPTOM_TOKEN_PATTERN.matcher(clause);
            while (negSym.find()) {
                String ns = negSym.group(1).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
                if (!positiveSymptoms.contains(ns)) {
                    symptoms.remove(ns);
                }
            }
            for (String negScope : extractTargetScopes(clause, domainKeywords)) {
                if (!positiveScopes.contains(negScope)) {
                    areas.remove(negScope);
                    if (confirmedScopes != null) {
                        confirmedScopes.remove(negScope);
                    }
                    if (forbiddenScopes != null) {
                        forbiddenScopes.add("gối".equals(negScope) && clause.toLowerCase(Locale.ROOT).contains("đầu gối") ? "đầu gối" : negScope);
                    }
                }
            }
        }
    }

    public static boolean containsOperationalCarePromise(String botReply, String careActor) {
        if (botReply == null || botReply.isBlank()) {
            return false;
        }
        String norm = Normalizer.normalize(botReply.strip(), Normalizer.Form.NFC);
        if (OPERATIONAL_CARE_PROMISE_PATTERN.matcher(norm).find()) {
            return true;
        }
        String lower = norm.toLowerCase(Locale.ROOT);
        String actor = (careActor != null && !careActor.isBlank()) ? careActor.toLowerCase(Locale.ROOT).strip() : "";
        boolean hasActor = (!actor.isEmpty() && lower.contains(actor))
                || lower.contains("kỹ thuật viên")
                || lower.contains("ktv")
                || lower.contains("bác sĩ")
                || lower.contains("nha sĩ")
                || lower.contains("chuyên viên");
        boolean hasVerb = lower.contains("sẽ dặn") || lower.contains("sẽ lưu ý") || lower.contains("ghi chú")
                || lower.contains("sẽ báo") || lower.contains("dặn kỹ") || lower.contains("nhắc kỹ");
        return hasActor && hasVerb;
    }

    public static String extractCareActorFromText(String lowerText) {
        if (lowerText == null || lowerText.isBlank()) {
            return "chuyên viên phụ trách";
        }
        if (lowerText.contains("bác sĩ") || lowerText.contains("nha sĩ")) {
            return "bác sĩ";
        }
        if (lowerText.contains("kỹ thuật viên") || lowerText.contains("ktv")) {
            return "kỹ thuật viên";
        }
        if (lowerText.contains("chuyên viên")) {
            return "chuyên viên";
        }
        return "chuyên viên phụ trách";
    }

    public boolean hasAlreadyApologizedForError(String errorId) {
        if (errorId == null || errorId.isBlank() || apologizedErrorIds == null) {
            return false;
        }
        return apologizedErrorIds.contains(errorId.strip());
    }

    public boolean isDuplicateErrorApology(String botReply) {
        if (botReply == null || botReply.isBlank() || activeErrorId == null || activeErrorId.isBlank()) {
            return false;
        }
        if (!hasAlreadyApologizedForError(activeErrorId)) {
            return false;
        }
        String lower = Normalizer.normalize(botReply, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        return lower.contains("xin lỗi") || lower.contains("thứ lỗi") || lower.contains("mong mình thông cảm");
    }

    public ConversationState withApologizedError(String errorId) {
        if (errorId == null || errorId.isBlank()) {
            return this;
        }
        Set<String> nextApologized = new LinkedHashSet<>(this.apologizedErrorIds != null ? this.apologizedErrorIds : Set.of());
        nextApologized.add(errorId.strip());
        return new ConversationState(
                greetingSent, introSent, stage, lastIntent, lastQuestion, lastQuestionIntent,
                usedQuestionIntents, lastBotAction, known, priceDiscussed, pricesShown, recommendedOptions,
                objectionCount, ackCount, clarificationCount, conditionAskedCount, preferenceAskedCount,
                clarifyServiceTypeCount, clarifyDurationCount, frustrationCount, ambiguousCount,
                recommendationCount, priceRepeatGuardCount, conditionClarified, serviceFamilyKnown,
                packageRecommended, priceRequestedInSession, knownSymptoms, knownBodyAreas,
                knownSeverity, knownDurationOfProblem, knownTrigger, knownServiceFamily,
                knownPreferredPressure, knownRecommendedPackage, lastQuotePackage, lastSentiment,
                lastEmotion, usedTemplates, humanTakeover, optedOut,
                downsellOffered, trialInviteOffered, explicitDownsellRequest, packageRejectionDetected,
                lastQuotedPackage, lastQuotedDuration, lastQuotedPrice, heldPackage, heldDuration,
                downsellPackage, downsellDuration, downsellPrice, rejectedDurations, rejectedPackages,
                objectionStage, telemetrySubtype, docsHasFeedback, docsHasRelaxationBenefit, docsHasPrivateRoom, neurologicalRisk,
                preferredRegion, mentionedPackages, quotedPackages, heldPrice, alternativeRequestCount,
                customerCorrectionDetected, rejectedWrongOffer, availablePackageCatalog, lastBotBubbleHashes,
                outstandingRequests, lastAssistantMessage,
                confirmedScopes, forbiddenScopes, Collections.unmodifiableSet(nextApologized), errorId.strip(),
                customerCareNotes, scopeSupersessionEvents, possibleCorrection,
                packageExplicitlySelected, selectedPackageId,
                knownFrequency, knownSymptomDistribution, pendingQuestionSlot,
                knownEpisodeDuration, packageInterestAccepted, bookingIntentConfirmed, visitTime, contactInfo, bookingStatus
        );
    }

    public ConversationState withActiveError(String errorId) {
        return new ConversationState(
                greetingSent, introSent, stage, lastIntent, lastQuestion, lastQuestionIntent,
                usedQuestionIntents, lastBotAction, known, priceDiscussed, pricesShown, recommendedOptions,
                objectionCount, ackCount, clarificationCount, conditionAskedCount, preferenceAskedCount,
                clarifyServiceTypeCount, clarifyDurationCount, frustrationCount, ambiguousCount,
                recommendationCount, priceRepeatGuardCount, conditionClarified, serviceFamilyKnown,
                packageRecommended, priceRequestedInSession, knownSymptoms, knownBodyAreas,
                knownSeverity, knownDurationOfProblem, knownTrigger, knownServiceFamily,
                knownPreferredPressure, knownRecommendedPackage, lastQuotePackage, lastSentiment,
                lastEmotion, usedTemplates, humanTakeover, optedOut,
                downsellOffered, trialInviteOffered, explicitDownsellRequest, packageRejectionDetected,
                lastQuotedPackage, lastQuotedDuration, lastQuotedPrice, heldPackage, heldDuration,
                downsellPackage, downsellDuration, downsellPrice, rejectedDurations, rejectedPackages,
                objectionStage, telemetrySubtype, docsHasFeedback, docsHasRelaxationBenefit, docsHasPrivateRoom, neurologicalRisk,
                preferredRegion, mentionedPackages, quotedPackages, heldPrice, alternativeRequestCount,
                customerCorrectionDetected, rejectedWrongOffer, availablePackageCatalog, lastBotBubbleHashes,
                outstandingRequests, lastAssistantMessage,
                confirmedScopes, forbiddenScopes, apologizedErrorIds, errorId,
                customerCareNotes, scopeSupersessionEvents, possibleCorrection,
                packageExplicitlySelected, selectedPackageId,
                knownFrequency, knownSymptomDistribution, pendingQuestionSlot,
                knownEpisodeDuration, packageInterestAccepted, bookingIntentConfirmed, visitTime, contactInfo, bookingStatus
        );
    }

    public int turnIndex() {
        return Math.max(1, ackCount + clarificationCount + recommendationCount + objectionCount + 1);
    }

    public String careActor() {
        if (customerCareNotes != null && !customerCareNotes.isEmpty()) {
            String lastActor = customerCareNotes.get(customerCareNotes.size() - 1).careActor();
            if (lastActor != null && !lastActor.isBlank()) {
                return lastActor;
            }
        }
        return "kỹ thuật viên";
    }

    public ConversationState withTenantCareActor(String careActor, List<String> taxonomy) {
        return this;
    }

    public ConversationState withDynamicContext(
            List<PackageCatalogService.PackageSummary> catalog,
            boolean hasFeedback,
            boolean hasPrivateRoom
    ) {
        return new ConversationState(
                greetingSent, introSent, stage, lastIntent, lastQuestion, lastQuestionIntent,
                usedQuestionIntents, lastBotAction, known, priceDiscussed, pricesShown, recommendedOptions,
                objectionCount, ackCount, clarificationCount, conditionAskedCount, preferenceAskedCount,
                clarifyServiceTypeCount, clarifyDurationCount, frustrationCount, ambiguousCount,
                recommendationCount, priceRepeatGuardCount, conditionClarified, serviceFamilyKnown,
                packageRecommended, priceRequestedInSession, knownSymptoms, knownBodyAreas,
                knownSeverity, knownDurationOfProblem, knownTrigger, knownServiceFamily,
                knownPreferredPressure, knownRecommendedPackage, lastQuotePackage, lastSentiment,
                lastEmotion, usedTemplates, humanTakeover, optedOut,
                downsellOffered, trialInviteOffered, explicitDownsellRequest, packageRejectionDetected,
                lastQuotedPackage, lastQuotedDuration, lastQuotedPrice, heldPackage, heldDuration,
                downsellPackage, downsellDuration, downsellPrice, rejectedDurations, rejectedPackages,
                objectionStage, telemetrySubtype, hasFeedback, docsHasRelaxationBenefit, hasPrivateRoom, neurologicalRisk,
                preferredRegion, mentionedPackages, quotedPackages, heldPrice, alternativeRequestCount,
                customerCorrectionDetected, rejectedWrongOffer,
                catalog != null ? Collections.unmodifiableList(new ArrayList<>(catalog)) : List.of(),
                lastBotBubbleHashes, outstandingRequests, lastAssistantMessage,
                confirmedScopes, forbiddenScopes, apologizedErrorIds, activeErrorId,
                customerCareNotes, scopeSupersessionEvents, possibleCorrection,
                packageExplicitlySelected, selectedPackageId,
                knownFrequency, knownSymptomDistribution, pendingQuestionSlot,
                knownEpisodeDuration, packageInterestAccepted, bookingIntentConfirmed, visitTime, contactInfo, bookingStatus
        );
    }

    public ConversationState recordOperationalCareNoteIfPresent(
            String tenantId,
            String conversationId,
            String turnId,
            String careActor,
            String botReply
    ) {
        if (!containsOperationalCarePromise(botReply, careActor)) {
            return this;
        }
        Set<String> effectiveScopes = (confirmedScopes != null && !confirmedScopes.isEmpty())
                ? confirmedScopes
                : (knownBodyAreas != null ? new LinkedHashSet<>(knownBodyAreas) : Set.of());
        if (effectiveScopes.isEmpty()) {
            return this;
        }
        List<CustomerCareNoteArtifact> nextNotes = new ArrayList<>(customerCareNotes != null ? customerCareNotes : List.of());
        String noteTxt = botReply.strip();
        boolean exists = nextNotes.stream().anyMatch(n -> n != null && noteTxt.equals(n.noteText()));
        if (!exists) {
            nextNotes.add(new CustomerCareNoteArtifact(
                    tenantId != null && !tenantId.isBlank() ? tenantId : "default",
                    conversationId != null && !conversationId.isBlank() ? conversationId : "session",
                    turnId != null && !turnId.isBlank() ? turnId : "turn-" + (nextNotes.size() + 1),
                    Collections.unmodifiableSet(new LinkedHashSet<>(effectiveScopes)),
                    careActor != null && !careActor.isBlank() ? careActor : extractCareActorFromText(botReply.toLowerCase(Locale.ROOT)),
                    noteTxt,
                    java.time.Instant.now()
            ));
        }
        return new ConversationState(
                greetingSent, introSent, stage, lastIntent, lastQuestion, lastQuestionIntent,
                usedQuestionIntents, lastBotAction, known, priceDiscussed, pricesShown, recommendedOptions,
                objectionCount, ackCount, clarificationCount, conditionAskedCount, preferenceAskedCount,
                clarifyServiceTypeCount, clarifyDurationCount, frustrationCount, ambiguousCount,
                recommendationCount, priceRepeatGuardCount, conditionClarified, serviceFamilyKnown,
                packageRecommended, priceRequestedInSession, knownSymptoms, knownBodyAreas,
                knownSeverity, knownDurationOfProblem, knownTrigger, knownServiceFamily,
                knownPreferredPressure, knownRecommendedPackage, lastQuotePackage, lastSentiment,
                lastEmotion, usedTemplates, humanTakeover, optedOut,
                downsellOffered, trialInviteOffered, explicitDownsellRequest, packageRejectionDetected,
                lastQuotedPackage, lastQuotedDuration, lastQuotedPrice, heldPackage, heldDuration,
                downsellPackage, downsellDuration, downsellPrice, rejectedDurations, rejectedPackages,
                objectionStage, telemetrySubtype, docsHasFeedback, docsHasRelaxationBenefit, docsHasPrivateRoom, neurologicalRisk,
                preferredRegion, mentionedPackages, quotedPackages, heldPrice, alternativeRequestCount,
                customerCorrectionDetected, rejectedWrongOffer, availablePackageCatalog, lastBotBubbleHashes,
                outstandingRequests, lastAssistantMessage,
                confirmedScopes, forbiddenScopes, apologizedErrorIds, activeErrorId,
                Collections.unmodifiableList(nextNotes), scopeSupersessionEvents, possibleCorrection,
                packageExplicitlySelected, selectedPackageId,
                knownFrequency, knownSymptomDistribution, pendingQuestionSlot,
                knownEpisodeDuration, packageInterestAccepted, bookingIntentConfirmed, visitTime, contactInfo, bookingStatus
        );
    }

    public ConversationState applyGroundingCheckAsymmetricUpdate(
            GroundingCheck gc,
            String rawCustomerMessage
    ) {
        if (gc == null) {
            return this;
        }
        StringBuilder syntheticGc = new StringBuilder();
        if (gc.customerNegatedScopes() != null && !gc.customerNegatedScopes().isEmpty()) {
            syntheticGc.append("phủ định ").append(String.join(", ", gc.customerNegatedScopes()));
        }
        return applyGroundingCheckAsymmetricUpdate(syntheticGc.toString(), rawCustomerMessage, List.of());
    }

    /**
     * Asymmetric State Ownership (Section II.1):
     * LLM grounding_check CANNOT add any unverified scope into confirmedScopes/knownBodyAreas,
     * ONLY demote a negated scope B that actually appears in rawCustomerMessage into forbiddenScopes.
     */
    public ConversationState applyGroundingCheckAsymmetricUpdate(
            String llmGroundingCheck,
            String rawCustomerMessage,
            List<String> tenantScopeTaxonomy
    ) {
        if (llmGroundingCheck == null || llmGroundingCheck.isBlank()) {
            return this;
        }
        VietnameseScopeSignalExtractor.AsymmetricGroundingDelta updatedSignal =
                VietnameseScopeSignalExtractor.applyGroundingCheckNegationOnly(
                        this.confirmedScopes,
                        this.forbiddenScopes,
                        llmGroundingCheck,
                        rawCustomerMessage,
                        tenantScopeTaxonomy
                );
        Set<String> nextAreas = new LinkedHashSet<>(this.knownBodyAreas != null ? this.knownBodyAreas : List.of());
        String detectedErrId = null;
        for (String forb : updatedSignal.forbiddenScopes()) {
            nextAreas.remove(forb);
            if ("đầu gối".equals(forb) || "khớp gối".equals(forb)) {
                nextAreas.remove("gối");
            }
            if (detectedErrId == null) {
                detectedErrId = "WRONG_SCOPE:" + forb;
            }
        }
        String nextErrId = detectedErrId != null ? detectedErrId : this.activeErrorId;
        ConversationState next = new ConversationState(
                greetingSent, introSent, stage, lastIntent, lastQuestion, lastQuestionIntent,
                usedQuestionIntents, lastBotAction, known, priceDiscussed, pricesShown, recommendedOptions,
                objectionCount, ackCount, clarificationCount, conditionAskedCount, preferenceAskedCount,
                clarifyServiceTypeCount, clarifyDurationCount, frustrationCount, ambiguousCount,
                recommendationCount, priceRepeatGuardCount, conditionClarified, serviceFamilyKnown,
                packageRecommended, priceRequestedInSession, knownSymptoms,
                Collections.unmodifiableList(new ArrayList<>(nextAreas)),
                knownSeverity, knownDurationOfProblem, knownTrigger, knownServiceFamily,
                knownPreferredPressure, knownRecommendedPackage, lastQuotePackage, lastSentiment,
                lastEmotion, usedTemplates, humanTakeover, optedOut,
                downsellOffered, trialInviteOffered, explicitDownsellRequest, packageRejectionDetected,
                lastQuotedPackage, lastQuotedDuration, lastQuotedPrice, heldPackage, heldDuration,
                downsellPackage, downsellDuration, downsellPrice, rejectedDurations, rejectedPackages,
                objectionStage, telemetrySubtype, docsHasFeedback, docsHasRelaxationBenefit, docsHasPrivateRoom, neurologicalRisk,
                preferredRegion, mentionedPackages, quotedPackages, heldPrice, alternativeRequestCount,
                customerCorrectionDetected, rejectedWrongOffer, availablePackageCatalog, lastBotBubbleHashes,
                outstandingRequests, lastAssistantMessage,
                updatedSignal.confirmedScopes(),
                updatedSignal.forbiddenScopes(),
                apologizedErrorIds,
                nextErrId,
                customerCareNotes,
                scopeSupersessionEvents,
                possibleCorrection,
                packageExplicitlySelected, selectedPackageId,
                knownFrequency, knownSymptomDistribution, pendingQuestionSlot,
                knownEpisodeDuration, packageInterestAccepted, bookingIntentConfirmed, visitTime, contactInfo, bookingStatus
        );
        if (this.isAlreadyEnrichedForTurn()) {
            ENRICHED_TO_PRIOR_MAP.put(next, this.priorTurnState());
        }
        return next;
    }

    public static PendingBubbleRecord schedulePendingSecondBubble(
            String sessionKey,
            String turnId,
            ReplyBubble pendingBubble,
            ConversationState stateAfterFirstBubbleOnly,
            ConversationState stateAfterSecondBubbleDelivered
    ) {
        if (sessionKey == null || sessionKey.isBlank() || pendingBubble == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        long delayMs = Math.max(0, pendingBubble.typingDelayMs());
        PendingBubbleRecord record = new PendingBubbleRecord(
                sessionKey,
                turnId != null ? turnId : "turn-" + now,
                pendingBubble,
                "PENDING",
                stateAfterFirstBubbleOnly,
                stateAfterSecondBubbleDelivered,
                now + delayMs
        );
        PENDING_BUBBLE_STORE.put(sessionKey, record);
        if (stateAfterFirstBubbleOnly != null) {
            SESSION_STORE.put(sessionKey, stateAfterFirstBubbleOnly);
        }
        return record;
    }

    public static PendingBubbleRecord cancelPendingSecondBubbleIfPresent(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return null;
        }
        PendingBubbleRecord current = PENDING_BUBBLE_STORE.get(sessionKey);
        if (current != null && "PENDING".equals(current.status())) {
            PendingBubbleRecord cancelled = current.withStatus("CANCELLED");
            PENDING_BUBBLE_STORE.put(sessionKey, cancelled);
            return cancelled;
        }
        return current;
    }

    public static PendingBubbleRecord commitPendingSecondBubbleIfActive(String sessionKey, String turnId) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return null;
        }
        PendingBubbleRecord current = PENDING_BUBBLE_STORE.get(sessionKey);
        if (current != null && "PENDING".equals(current.status())
                && (turnId == null || turnId.equals(current.turnId()))) {
            PendingBubbleRecord delivered = current.withStatus("DELIVERED");
            PENDING_BUBBLE_STORE.put(sessionKey, delivered);
            if (current.pendingPostState() != null) {
                SESSION_STORE.put(sessionKey, current.pendingPostState());
            }
            return delivered;
        }
        return current;
    }

    public static PendingBubbleRecord getPendingBubbleRecord(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return null;
        }
        return PENDING_BUBBLE_STORE.get(sessionKey);
    }
}
