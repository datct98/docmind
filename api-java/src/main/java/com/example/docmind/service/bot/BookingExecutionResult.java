package com.example.docmind.service.bot;

/**
 * Kết quả thực thi kiểm tra lịch trống ({@code checkAvailability}) hoặc tạo lịch đặt ({@code createBooking})
 * từ {@link BookingGateway} (P3.2 — Mock Booking Integration).
 *
 * <ul>
 *   <li>{@code status}: {@link Status#SUCCESS} hoặc {@link Status#FAILURE}</li>
 *   <li>{@code available}: {@code true} nếu khung giờ/dịch vụ còn trống</li>
 *   <li>{@code bookingId}: Mã đặt lịch (chỉ khác {@code null} khi {@code createBooking} thành công; {@code null} nếu chưa tạo)</li>
 *   <li>{@code source}: {@link Source#MOCK}, {@link Source#REAL}, hoặc {@link Source#NONE}</li>
 *   <li>{@code failureReason}: Lý do thất bại ({@code null} nếu không có lỗi)</li>
 *   <li>{@code slotSignature}: Chữ ký định danh tổ hợp slot {@code productId|isoDate|time|branch}</li>
 *   <li>{@code branch}: Cơ sở / chi nhánh áp dụng (nếu có)</li>
 * </ul>
 */
public record BookingExecutionResult(
        Status status,
        boolean available,
        String bookingId,
        Source source,
        String failureReason,
        String slotSignature,
        String branch
) {
    public enum Status {
        SUCCESS,
        FAILURE
    }

    public enum Source {
        MOCK,
        REAL,
        NONE
    }

    public BookingExecutionResult {
        status = status != null ? status : Status.FAILURE;
        source = source != null ? source : Source.NONE;
        bookingId = (bookingId != null && !bookingId.isBlank()) ? bookingId.strip() : null;
        failureReason = (failureReason != null && !failureReason.isBlank()) ? failureReason.strip() : null;
        slotSignature = (slotSignature != null && !slotSignature.isBlank()) ? slotSignature.strip() : null;
        branch = (branch != null && !branch.isBlank()) ? branch.strip() : null;
    }

    public BookingExecutionResult(
            Status status,
            boolean available,
            String bookingId,
            Source source,
            String failureReason
    ) {
        this(status, available, bookingId, source, failureReason, null, null);
    }

    public static BookingExecutionResult none() {
        return new BookingExecutionResult(Status.FAILURE, false, null, Source.NONE, "GATEWAY_NOT_INVOKED", null, null);
    }

    public static BookingExecutionResult disabled() {
        return none();
    }

    public static BookingExecutionResult mockAvailable(String slotSignature, String branch) {
        return new BookingExecutionResult(Status.SUCCESS, true, null, Source.MOCK, null, slotSignature, branch);
    }

    public static BookingExecutionResult mockUnavailable(String reason, String slotSignature, String branch) {
        String effReason = (reason != null && !reason.isBlank()) ? reason.strip() : "SLOT_UNAVAILABLE";
        return new BookingExecutionResult(Status.SUCCESS, false, null, Source.MOCK, effReason, slotSignature, branch);
    }

    public static BookingExecutionResult mockCreated(String bookingId, String slotSignature, String branch) {
        return new BookingExecutionResult(Status.SUCCESS, true, bookingId, Source.MOCK, null, slotSignature, branch);
    }

    public static BookingExecutionResult mockFailure(String failureReason, boolean available, String slotSignature, String branch) {
        String effReason = (failureReason != null && !failureReason.isBlank()) ? failureReason.strip() : "BOOKING_EXECUTION_FAILED";
        return new BookingExecutionResult(Status.FAILURE, available, null, Source.MOCK, effReason, slotSignature, branch);
    }

    public boolean isBookingConfirmedSuccess() {
        return status == Status.SUCCESS && bookingId != null && !bookingId.isBlank();
    }

    public boolean isConfirmedSuccess() {
        return isBookingConfirmedSuccess();
    }
}
