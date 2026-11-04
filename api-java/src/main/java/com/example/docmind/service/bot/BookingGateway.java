package com.example.docmind.service.bot;

import java.util.Locale;
import java.util.UUID;

/**
 * Hợp đồng tối giản cho cổng kiểm tra lịch trống và tạo lịch đặt (P3.2 — Mock Booking Integration).
 *
 * <p>Tuyệt đối không tự tạo framework đa nhà cung cấp phức tạp; chỉ cung cấp đúng 2 thao tác:
 * {@link #checkAvailability(BookingSlotRequest)} và {@link #createBooking(BookingSlotRequest)},
 * kèm cơ chế hủy hiệu lực kết quả kiểm tra lịch trống cũ khi khách thay đổi slot ({@link #invalidateAvailabilityForConversation(UUID, String)}).
 */
public interface BookingGateway {

    /**
     * Chế độ vận hành của cổng đặt lịch:
     * <ul>
     *   <li>{@link #DISABLED}: Mặc định an toàn trên Production khi chưa đấu nối hệ thống đặt lịch thật
     *       (không gọi MockBookingGateway, không tự lên CONFIRMED, không sinh bookingId giả).</li>
     *   <li>{@link #MOCK}: Chế độ mô phỏng phục vụ kiểm thử hội thoại đầu-cuối và UI Simulator.</li>
     * </ul>
     */
    enum Mode {
        DISABLED,
        MOCK;

        public static Mode fromProperty(String raw) {
            if (raw != null && "MOCK".equalsIgnoreCase(raw.strip())) {
                return MOCK;
            }
            return DISABLED;
        }
    }

    /**
     * Thông tin các slot đặt lịch cần kiểm tra chỗ trống hoặc xác nhận tạo lịch.
     */
    record BookingSlotRequest(
            UUID pageId,
            String senderId,
            String conversationId,
            String selectedProductId,
            String selectedProductLabel,
            String bookingDate,
            String resolvedIsoDate,
            String bookingTime,
            String timezoneId,
            String branch
    ) {
        public BookingSlotRequest(
                UUID pageId,
                String senderId,
                String selectedProductId,
                String selectedProductLabel,
                String bookingDate,
                String resolvedIsoDate,
                String bookingTime,
                String timezoneId,
                String branch
        ) {
            this(pageId, senderId, null, selectedProductId, selectedProductLabel, bookingDate, resolvedIsoDate, bookingTime, timezoneId, branch);
        }

        public String slotSignature() {
            return canonicalSlotSignature();
        }

        public String canonicalSlotSignature() {
            String prod = selectedProductId != null ? selectedProductId.strip().toUpperCase(Locale.ROOT) : "";
            String date = (resolvedIsoDate != null && !resolvedIsoDate.isBlank())
                    ? resolvedIsoDate.strip()
                    : (bookingDate != null ? bookingDate.strip().toLowerCase(Locale.ROOT) : "");
            String time = bookingTime != null ? bookingTime.strip() : "";
            String br = (branch != null && !branch.isBlank()) ? branch.strip().toLowerCase(Locale.ROOT) : "default";
            return prod + "|" + date + "|" + time + "|" + br;
        }

        public boolean hasAllRequiredSlots() {
            return selectedProductId != null && !selectedProductId.isBlank()
                    && bookingDate != null && !bookingDate.isBlank()
                    && bookingTime != null && !bookingTime.isBlank();
        }
    }

    /**
     * Bước 2: Kiểm tra lịch trống cho tổ hợp (sản phẩm/gói, ngày, giờ, chi nhánh).
     */
    BookingExecutionResult checkAvailability(BookingSlotRequest request);

    /**
     * Bước 5: Tạo lịch đặt sau khi đã kiểm tra còn chỗ trống và khách đã xác nhận tường minh.
     */
    BookingExecutionResult createBooking(BookingSlotRequest request);

    /**
     * Hủy hiệu lực kết quả kiểm tra lịch trống trước đó của phiên khi khách đổi dịch vụ, ngày, giờ hoặc chi nhánh.
     */
    default void invalidateAvailabilityForConversation(UUID pageId, String senderId) {
    }
}
