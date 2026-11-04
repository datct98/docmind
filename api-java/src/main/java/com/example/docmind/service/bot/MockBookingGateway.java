package com.example.docmind.service.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Triển khai cổng đặt lịch giả lập ({@code MockBookingGateway}) phục vụ kiểm thử hội thoại đầu-cuối
 * và UI Simulator (P3.2 — Mock Booking Integration).
 *
 * <p>Các bất biến cốt lõi:
 * <ul>
 *   <li>Mọi kết quả trả về đều gắn {@link BookingExecutionResult.Source#MOCK} và mã đặt lịch có tiền tố
 *       {@code MOCK-BK-} để phân biệt rõ ràng với đặt lịch thật.</li>
 *   <li>Chỉ cho phép {@link #createBooking(BookingSlotRequest)} thành công khi đúng chữ ký slot
 *       {@code (productId|date|time|branch)} hiện tại đã được {@link #checkAvailability(BookingSlotRequest)}
 *       xác nhận {@code available = true} trước đó.</li>
 *   <li>Chống tạo trùng booking khi sự kiện xác nhận bị gửi lặp cho cùng một phiên và cùng chữ ký slot
 *       (Scenario G: trả lại đúng {@link BookingExecutionResult} với cùng {@code bookingId} đã tạo).</li>
 * </ul>
 */
@Component
public class MockBookingGateway implements BookingGateway {

    private static final Logger log = LoggerFactory.getLogger(MockBookingGateway.class);

    private final Map<String, String> verifiedAvailableSlotByConversation = new ConcurrentHashMap<>();
    private final Map<String, BookingExecutionResult> createdBookingsByConversationSlot = new ConcurrentHashMap<>();
    private final Set<String> unavailableSlotSignatures = ConcurrentHashMap.newKeySet();
    private final Set<String> unavailableTimes = ConcurrentHashMap.newKeySet();
    private volatile Boolean nextAvailabilityOverride = null;
    private volatile String simulateCreateFailureReason = null;
    private final AtomicInteger availabilityCheckCallCount = new AtomicInteger(0);
    private final AtomicInteger createBookingCallCount = new AtomicInteger(0);
    private final AtomicInteger createdBookingsCounter = new AtomicInteger(0);

    public static String conversationKey(UUID pageId, String senderId) {
        String p = pageId != null ? pageId.toString() : "default-page";
        String s = (senderId != null && !senderId.isBlank()) ? senderId.strip() : "unknown-sender";
        return p + "|" + s;
    }

    @Override
    public BookingExecutionResult checkAvailability(BookingSlotRequest request) {
        availabilityCheckCallCount.incrementAndGet();
        if (request == null || !request.hasAllRequiredSlots()) {
            log.info("[MOCK-BOOKING-GATEWAY] checkAvailability rejected: missing required slots (request={})", request);
            return BookingExecutionResult.mockFailure("MISSING_REQUIRED_SLOT", false,
                    request != null ? request.canonicalSlotSignature() : null,
                    request != null ? request.branch() : null);
        }

        String convKey = conversationKey(request.pageId(), request.senderId());
        String slotSig = request.canonicalSlotSignature();
        String normTime = request.bookingTime().strip();

        Boolean override = this.nextAvailabilityOverride;
        if (override != null) {
            this.nextAvailabilityOverride = null;
            if (!override) {
                verifiedAvailableSlotByConversation.remove(convKey);
                log.info("[MOCK-BOOKING-GATEWAY] source=MOCK op=checkAvailability conv={} slot={} available=false reason=CONFIGURED_UNAVAILABLE_OVERRIDE",
                        convKey, slotSig);
                return BookingExecutionResult.mockUnavailable("SLOT_UNAVAILABLE_AT_REQUESTED_TIME", slotSig, request.branch());
            }
        }

        if (unavailableSlotSignatures.contains(slotSig.toLowerCase(Locale.ROOT))
                || unavailableTimes.contains(normTime.toLowerCase(Locale.ROOT))) {
            verifiedAvailableSlotByConversation.remove(convKey);
            log.info("[MOCK-BOOKING-GATEWAY] source=MOCK op=checkAvailability conv={} slot={} available=false reason=SLOT_FULL",
                    convKey, slotSig);
            return BookingExecutionResult.mockUnavailable("SLOT_UNAVAILABLE_AT_REQUESTED_TIME", slotSig, request.branch());
        }

        verifiedAvailableSlotByConversation.put(convKey, slotSig);
        log.info("[MOCK-BOOKING-GATEWAY] source=MOCK op=checkAvailability conv={} slot={} available=true",
                convKey, slotSig);
        return BookingExecutionResult.mockAvailable(slotSig, request.branch());
    }

    @Override
    public BookingExecutionResult createBooking(BookingSlotRequest request) {
        createBookingCallCount.incrementAndGet();
        if (request == null || !request.hasAllRequiredSlots()) {
            return BookingExecutionResult.mockFailure("MISSING_REQUIRED_SLOT", false,
                    request != null ? request.canonicalSlotSignature() : null,
                    request != null ? request.branch() : null);
        }

        String convKey = conversationKey(request.pageId(), request.senderId());
        String slotSig = request.canonicalSlotSignature();
        String dedupKey = convKey + "#" + slotSig;

        // Scenario G: Chống tạo trùng khi sự kiện xác nhận bị gửi lặp cho cùng phiên & cùng chữ ký slot
        BookingExecutionResult existing = createdBookingsByConversationSlot.get(dedupKey);
        if (existing != null && existing.isBookingConfirmedSuccess()) {
            log.info("[MOCK-BOOKING-GATEWAY] source=MOCK op=createBooking deduplicated duplicate confirmation conv={} slot={} bookingId={}",
                    convKey, slotSig, existing.bookingId());
            return existing;
        }

        String verifiedSlotSig = verifiedAvailableSlotByConversation.get(convKey);
        if (verifiedSlotSig == null || !verifiedSlotSig.equalsIgnoreCase(slotSig)) {
            log.warn("[MOCK-BOOKING-GATEWAY] source=MOCK op=createBooking rejected: slot was not verified or changed since availability check (conv={} expectedVerified={} actual={})",
                    convKey, verifiedSlotSig, slotSig);
            return BookingExecutionResult.mockFailure("AVAILABILITY_NOT_VERIFIED_FOR_CURRENT_SLOT", false, slotSig, request.branch());
        }

        String failureReason = this.simulateCreateFailureReason;
        if (failureReason != null && !failureReason.isBlank()) {
            log.warn("[MOCK-BOOKING-GATEWAY] source=MOCK op=createBooking simulated FAILURE conv={} slot={} reason={}",
                    convKey, slotSig, failureReason);
            return BookingExecutionResult.mockFailure(failureReason, true, slotSig, request.branch());
        }

        BookingExecutionResult created = createdBookingsByConversationSlot.computeIfAbsent(dedupKey, k -> {
            createdBookingsCounter.incrementAndGet();
            String bookingId = generateDeterministicMockBookingId(convKey, slotSig);
            return BookingExecutionResult.mockCreated(bookingId, slotSig, request.branch());
        });
        log.info("[MOCK-BOOKING-GATEWAY] source=MOCK op=createBooking status=SUCCESS conv={} slot={} bookingId={}",
                convKey, slotSig, created.bookingId());
        return created;
    }

    @Override
    public void invalidateAvailabilityForConversation(UUID pageId, String senderId) {
        String convKey = conversationKey(pageId, senderId);
        String removed = verifiedAvailableSlotByConversation.remove(convKey);
        if (removed != null) {
            log.info("[MOCK-BOOKING-GATEWAY] source=MOCK op=invalidateAvailability conv={} invalidatedSlot={}",
                    convKey, removed);
        }
    }

    public boolean hasVerifiedAvailabilityForSlot(UUID pageId, String senderId, String slotSignature) {
        if (slotSignature == null || slotSignature.isBlank()) {
            return false;
        }
        String verified = verifiedAvailableSlotByConversation.get(conversationKey(pageId, senderId));
        return verified != null && verified.equalsIgnoreCase(slotSignature.strip());
    }

    public void markTimeUnavailable(String bookingTime) {
        if (bookingTime != null && !bookingTime.isBlank()) {
            unavailableTimes.add(bookingTime.strip().toLowerCase(Locale.ROOT));
        }
    }

    public void markSlotSignatureUnavailable(String slotSignature) {
        if (slotSignature != null && !slotSignature.isBlank()) {
            unavailableSlotSignatures.add(slotSignature.strip().toLowerCase(Locale.ROOT));
        }
    }

    public void setNextAvailabilityOverride(Boolean available) {
        this.nextAvailabilityOverride = available;
    }

    public void setSimulateCreateFailureReason(String failureReason) {
        this.simulateCreateFailureReason = (failureReason != null && !failureReason.isBlank()) ? failureReason.strip() : null;
    }

    public int getAvailabilityCheckCallCount() {
        return availabilityCheckCallCount.get();
    }

    public int getCheckAvailabilityCallCount() {
        return availabilityCheckCallCount.get();
    }

    public int getCreateBookingCallCount() {
        return createBookingCallCount.get();
    }

    public int getCreatedBookingsCount() {
        return createdBookingsCounter.get();
    }

    public int getActiveBookingCount() {
        return createdBookingsCounter.get();
    }

    public void reset() {
        verifiedAvailableSlotByConversation.clear();
        createdBookingsByConversationSlot.clear();
        unavailableSlotSignatures.clear();
        unavailableTimes.clear();
        nextAvailabilityOverride = null;
        simulateCreateFailureReason = null;
        availabilityCheckCallCount.set(0);
        createBookingCallCount.set(0);
        createdBookingsCounter.set(0);
    }

    private static String generateDeterministicMockBookingId(String convKey, String slotSig) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((convKey + "|" + slotSig).getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(digest, 0, 4).toUpperCase(Locale.ROOT);
            return "MOCK-BK-" + hex;
        } catch (Exception e) {
            return "MOCK-BK-" + Math.abs((convKey + "|" + slotSig).hashCode());
        }
    }
}
