package com.example.docmind.service.graph;

import com.example.docmind.entity.ChunkEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MapOfContentServiceTest {

    private final MapOfContentService service = new MapOfContentService(null, null);

    @Test
    @DisplayName("Tổng hợp toàn diện MOC Page Nàng Sen với đầy đủ Category, Chi nhánh Hà Nội, Quận 1 và Promo")
    void extractsFullNangSenMocCorrectly() {
        UUID pageId = UUID.randomUUID();

        // 1. Chunks Massage Hà Nội (3 cơ sở: Mỹ Đình, Bích Câu, Long Biên)
        ChunkEntity myDinhChunk = ChunkEntity.builder()
                .id(UUID.randomUUID())
                .pageId(pageId)
                .headingPath(new String[]{"MASSAGE", "Cơ sở Nàng Sen Mỹ Đình (Hà Nội)"})
                .text("""
                        | Mã gói | Tên gói | Giá | Tổng thời gian |
                        | --- | --- | --- | --- |
                        | NS01 | Massage cơ bản | 399.000VNĐ | 60 phút |
                        | NS07 | Hoàng Gia Nàng Sen | 1.990.000VNĐ | 150 phút |
                        """)
                .build();

        ChunkEntity bichCauChunk = ChunkEntity.builder()
                .id(UUID.randomUUID())
                .pageId(pageId)
                .headingPath(new String[]{"MASSAGE", "Cơ sở Nàng Sen Bích Câu (Hà Nội)"})
                .text("""
                        | Mã gói | Tên gói | Giá | Tổng thời gian |
                        | --- | --- | --- | --- |
                        | NS01 | Massage cơ bản | 399.000VNĐ | 60 phút |
                        | NS04 | Trị liệu Chuyên sâu | 780.000VNĐ | 90 phút |
                        """)
                .build();

        ChunkEntity longBienChunk = ChunkEntity.builder()
                .id(UUID.randomUUID())
                .pageId(pageId)
                .headingPath(new String[]{"MASSAGE", "Cơ sở Nàng Sen Long Biên (Hà Nội)"})
                .text("""
                        | Mã gói | Tên gói | Giá | Tổng thời gian |
                        | --- | --- | --- | --- |
                        | NS02 | Massage nâng cao | 530.000VNĐ | 80 phút |
                        | NS07 | Hoàng Gia Nàng Sen | 1.990.000VNĐ | 150 phút |
                        """)
                .build();

        // 2. Chunks Massage TP.HCM (Quận 1)
        ChunkEntity q1MassageChunk = ChunkEntity.builder()
                .id(UUID.randomUUID())
                .pageId(pageId)
                .headingPath(new String[]{"MASSAGE", "Cơ sở Nàng Sen Quận 1 (TP.HCM)"})
                .text("""
                        | Mã gói | Tên gói | Giá | Tổng thời gian |
                        | --- | --- | --- | --- |
                        | NS01 | Massage cơ bản | 570.000VNĐ | 70 phút |
                        | NS06 | Trị liệu VIP Sài Gòn | 2.150.000VNĐ | 120 phút |
                        """)
                .build();

        // 3. Chunks Gội đầu dưỡng sinh (Hà Nội vs TP.HCM Quận 1)
        ChunkEntity hanoiGoiDauChunk = ChunkEntity.builder()
                .id(UUID.randomUUID())
                .pageId(pageId)
                .headingPath(new String[]{"GỘI ĐẦU DƯỠNG SINH", "Chi nhánh Hà Nội"})
                .text("""
                        | Mã gói | Tên gói | Giá | Thời gian |
                        | --- | --- | --- | --- |
                        | DS01 | Lấy ráy tai cơ bản | 129.000VNĐ | 30 phút |
                        | DS06 | Dưỡng sinh Hoàng Cung | 990.000VNĐ | 100 phút |
                        """)
                .build();

        ChunkEntity q1GoiDauChunk = ChunkEntity.builder()
                .id(UUID.randomUUID())
                .pageId(pageId)
                .headingPath(new String[]{"GỘI ĐẦU DƯỠNG SINH", "Chi nhánh TP.HCM (Quận 1)"})
                .text("""
                        | Mã gói | Tên gói | Giá | Thời gian |
                        | --- | --- | --- | --- |
                        | DS01 | Lấy ráy tai cơ bản | 129.000VNĐ | 30 phút |
                        | DS06 | Dưỡng sinh Cao Cấp Q1 | 1.100.000VNĐ | 120 phút |
                        """)
                .build();

        // 4. Chunk Ưu đãi & Khuyến mãi (PROMO)
        ChunkEntity promoChunk = ChunkEntity.builder()
                .id(UUID.randomUUID())
                .pageId(pageId)
                .headingPath(new String[]{"ƯU ĐÃI & KHUYẾN MÃI", "Chính sách chiết khấu Combo"})
                .text("""
                        | Chiết khấu theo số lượng buổi / voucher | Mức ưu đãi | Điều kiện |
                        | --- | --- | --- |
                        | Mua từ 5 buổi hoặc từ 5 voucher | Giảm 10% tổng hóa đơn | Toàn bộ cơ sở |
                        | Mua từ 10 buổi hoặc từ 10 voucher | Giảm 15% tổng hóa đơn | Toàn bộ cơ sở |
                        """)
                .build();

        // 5. Chunk FAQ (phải được loại trừ khỏi tính giá)
        ChunkEntity faqChunk = ChunkEntity.builder()
                .id(UUID.randomUUID())
                .pageId(pageId)
                .headingPath(new String[]{"FAQ", "Câu hỏi thường gặp"})
                .text("""
                        | Câu hỏi | Trả lời |
                        | --- | --- |
                        | Gói 670k và 780k khác gì nhau? | Gói 780k có phòng riêng khép kín |
                        """)
                .build();

        List<ChunkEntity> chunks = List.of(
                myDinhChunk, bichCauChunk, longBienChunk, q1MassageChunk,
                hanoiGoiDauChunk, q1GoiDauChunk, promoChunk, faqChunk
        );

        String moc = service.buildMocFromChunks(chunks);

        assertThat(moc).contains(MapOfContentService.MOC_HEADER);
        assertThat(moc).contains("- DỊCH VỤ MASSAGE:");
        assertThat(moc).contains("* Chi nhánh Hà Nội (Mỹ Đình, Bích Câu, Long Biên): 399.000 VNĐ - 1.990.000 VNĐ (NS01 - NS07)");
        assertThat(moc).contains("* Chi nhánh TP.HCM (Quận 1): 570.000 VNĐ - 2.150.000 VNĐ (NS01 - NS06)");
        assertThat(moc).contains("- GỘI ĐẦU DƯỠNG SINH:");
        assertThat(moc).contains("* Chi nhánh Hà Nội: 129.000 VNĐ - 990.000 VNĐ (DS01 - DS06)");
        assertThat(moc).contains("* Chi nhánh TP.HCM (Quận 1): 129.000 VNĐ - 1.100.000 VNĐ (DS01 - DS06)");
        assertThat(moc).contains("- CHÍNH SÁCH ƯU ĐÃI:");
        assertThat(moc).contains("Giảm 10% khi mua từ 5 buổi hoặc từ 5 voucher.");
        assertThat(moc).contains(MapOfContentService.MOC_FOOTER);
    }

    @Test
    @DisplayName("Hỗ trợ tài liệu phòng khám nha khoa không có từ khóa Nàng Sen (Zero Hardcode)")
    void handlesArbitraryDentalDomainWithoutHardcoding() {
        ChunkEntity dentalChunk1 = ChunkEntity.builder()
                .id(UUID.randomUUID())
                .headingPath(new String[]{"DỊCH VỤ NHA KHOA", "Cơ sở Cầu Giấy (Hà Nội)"})
                .text("""
                        | Mã gói | Dịch vụ | Giá |
                        | --- | --- | --- |
                        | NK01 | Lấy cao răng | 150.000VNĐ |
                        | NK04 | Trám răng thẩm mỹ | 800.000VNĐ |
                        """)
                .build();

        ChunkEntity dentalPromo = ChunkEntity.builder()
                .id(UUID.randomUUID())
                .headingPath(new String[]{"ƯU ĐÃI KHÁCH HÀNG"})
                .text("""
                        - Giảm 15% khi đặt hẹn online qua Fanpage.
                        """)
                .build();

        String moc = service.buildMocFromChunks(List.of(dentalChunk1, dentalPromo));

        assertThat(moc).contains("- DỊCH VỤ NHA KHOA:");
        assertThat(moc).contains("* Chi nhánh Hà Nội: 150.000 VNĐ - 800.000 VNĐ (NK01 - NK04)");
        assertThat(moc).contains("- CHÍNH SÁCH ƯU ĐÃI:");
        assertThat(moc).contains("Giảm 15% khi đặt hẹn online qua Fanpage.");
    }

    @Test
    @DisplayName("Kiểm tra định dạng khoảng mã gói linh hoạt (formatCodeSpan)")
    void formatsCodeSpanCorrectly() {
        assertThat(MapOfContentService.formatCodeSpan(Set.of("NS01", "NS02", "NS07"))).isEqualTo("(NS01 - NS07)");
        assertThat(MapOfContentService.formatCodeSpan(Set.of("DS01"))).isEqualTo("(DS01)");
        assertThat(MapOfContentService.formatCodeSpan(Set.of("A", "B"))).isEqualTo("(A, B)");
        assertThat(MapOfContentService.formatCodeSpan(Set.of())).isEmpty();
    }

    @Test
    @DisplayName("Xử lý an toàn khi không có bảng giá nào (chỉ có bài viết FAQ / Process)")
    void handlesEmptyAndFaqOnlyChunksGracefully() {
        ChunkEntity faqOnly = ChunkEntity.builder()
                .id(UUID.randomUUID())
                .headingPath(new String[]{"FAQ", "Câu hỏi thường gặp"})
                .text("| Câu hỏi | Trả lời |\n| --- | --- |\n| Đặt lịch trước bao lâu? | Dạ trước 1 tiếng ạ |")
                .build();

        String moc = service.buildMocFromChunks(List.of(faqOnly));
        assertThat(moc).isEmpty();

        String emptyMoc = service.buildMocFromChunks(List.of());
        assertThat(emptyMoc).isEmpty();
    }

    @Test
    @DisplayName("Kiểm tra xóa cache (invalidateCache) hoạt động chuẩn xác")
    void verifiesCacheInvalidation() {
        UUID pageId = UUID.randomUUID();
        service.invalidateCache(pageId);
        assertThat(service.getCacheSize()).isEqualTo(0);
    }
}
