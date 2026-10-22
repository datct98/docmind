package com.example.docmind.service.metadata;

import com.example.docmind.dto.ChunkMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkMetadataExtractorTest {

    @Test
    @DisplayName("Trích xuất bảng giá Quận 1 với NS01/NS02 và mức giá min/max chính xác")
    void extractsPriceTableWithBranchAndServiceCodes() {
        List<String> headingPath = List.of(
                "MASSAGE",
                "Cơ sở Nàng Sen Quận 1 (TP.HCM) — Địa chỉ: 74 Nguyễn Công Trứ, Phường Nguyễn Thái Bình, Quận 1, TP Hồ Chí Minh"
        );
        String text = """
                | Mã gói | Tên gói | Giá | Tổng thời gian | Bao gồm |
                | --- | --- | --- | --- | --- |
                | NS01 | Massage cơ bản | 570.000VNĐ | 70 phút | Full body |
                | NS02 | Massage nâng cao | 670.000VNĐ | 90 phút | 10p ngâm bồn |
                """;

        ChunkMetadata meta = ChunkMetadataExtractor.extract(headingPath, text);

        assertThat(meta.chunkRole()).isEqualTo(ChunkMetadata.ROLE_PRICE_TABLE);
        assertThat(meta.minPrice()).isEqualTo(570_000L);
        assertThat(meta.maxPrice()).isEqualTo(670_000L);
        assertThat(meta.branchTags()).contains("quan_1");
        assertThat(meta.serviceCodes()).containsExactly("NS01", "NS02");
        assertThat(meta.isEntryLevel()).isTrue();
    }

    @Test
    @DisplayName("Trích xuất bảng giá Hà Nội (Bích Câu) với min_price = 399.000VNĐ")
    void extractsHanoiBranchPriceTable() {
        List<String> headingPath = List.of(
                "MASSAGE",
                "Cơ sở Nàng Sen Bích Câu (Hà Nội)"
        );
        String text = """
                | Mã gói | Tên gói | Giá | Tổng thời gian |
                | --- | --- | --- | --- |
                | NS01 | Massage cơ bản | 399.000VNĐ | 70 phút |
                | NS02 | Massage nâng cao | 500.000VNĐ | 90 phút |
                """;

        ChunkMetadata meta = ChunkMetadataExtractor.extract(headingPath, text);

        assertThat(meta.chunkRole()).isEqualTo(ChunkMetadata.ROLE_PRICE_TABLE);
        assertThat(meta.minPrice()).isEqualTo(399_000L);
        assertThat(meta.maxPrice()).isEqualTo(500_000L);
        assertThat(meta.branchTags()).contains("bich_cau");
        assertThat(meta.serviceCodes()).contains("NS01");
        assertThat(meta.isEntryLevel()).isTrue();
    }

    @Test
    @DisplayName("Trích xuất bảng ưu đãi / khuyến mãi (PROMO) không có giá niêm yết")
    void extractsPromoChunk() {
        List<String> headingPath = List.of("ƯU ĐÃI & KHUYẾN MÃI", "Chính sách chiết khấu Combo");
        String text = """
                | Chiết khấu theo số lượng buổi / voucher | Mức ưu đãi | Điều kiện |
                | --- | --- | --- |
                | Mua từ 5 buổi hoặc từ 5 voucher | Giảm 10% tổng hóa đơn | Toàn bộ cơ sở |
                | Mua từ 10 buổi hoặc từ 10 voucher | Giảm 15% tổng hóa đơn | Toàn bộ cơ sở |
                """;

        ChunkMetadata meta = ChunkMetadataExtractor.extract(headingPath, text);

        assertThat(meta.chunkRole()).isEqualTo(ChunkMetadata.ROLE_PROMO);
        assertThat(meta.minPrice()).isNull();
        assertThat(meta.maxPrice()).isNull();
    }

    @Test
    @DisplayName("Trích xuất bảng FAQ so sánh 2 gói dịch vụ")
    void extractsFaqChunk() {
        List<String> headingPath = List.of("FAQ - CÂU HỎI THƯỜNG GẶP");
        String text = """
                | Câu hỏi | Trả lời |
                | --- | --- |
                | Gói 670k và 780k khác gì nhau? | Gói 780k có phòng riêng tư cho gội đầu dưỡng sinh |
                """;

        ChunkMetadata meta = ChunkMetadataExtractor.extract(headingPath, text);

        assertThat(meta.chunkRole()).isEqualTo(ChunkMetadata.ROLE_FAQ);
        assertThat(meta.minPrice()).isEqualTo(670_000L);
        assertThat(meta.maxPrice()).isEqualTo(780_000L);
    }

    @Test
    @DisplayName("Kiểm tra động isEntryLevelPriceChunk cho các ngành kinh doanh khác (zero hardcode)")
    void verifiesDynamicEntryLevelCheckForArbitraryBusinesses() {
        // Phòng khám nha khoa: mã NK01, 150.000 VNĐ
        List<String> dentalHeading = List.of("DỊCH VỤ NHA KHOA", "Bảng giá lấy cao răng");
        String dentalText = """
                | Mã dịch vụ | Tên dịch vụ | Đơn giá |
                | --- | --- | --- |
                | NK01 | Lấy cao răng cơ bản | 150.000VNĐ |
                """;
        assertThat(ChunkMetadataExtractor.isEntryLevelPriceChunk(dentalHeading, dentalText)).isTrue();

        // Gói VIP nha khoa: NK05, 5.000.000 VNĐ
        List<String> vipHeading = List.of("DỊCH VỤ NHA KHOA", "Bảng giá bọc sứ VIP");
        String vipText = """
                | Mã dịch vụ | Tên dịch vụ | Đơn giá |
                | --- | --- | --- |
                | NK05 | Bọc sứ Cercon HT | 5.000.000VNĐ |
                """;
        assertThat(ChunkMetadataExtractor.isEntryLevelPriceChunk(vipHeading, vipText)).isFalse();
    }

    @Test
    @DisplayName("Trích xuất service codes không phân biệt hoa thường (case-insensitive) từ văn bản")
    void extractsCaseInsensitiveServiceCodes() {
        List<String> heading = List.of("TƯ VẤN DỊCH VỤ");
        String text = "Khách hàng có thể trải nghiệm gói sp01 hoặc ns02 tại chi nhánh. Mã dịch vụ ds-03.";
        ChunkMetadata meta = ChunkMetadataExtractor.extract(heading, text);

        assertThat(meta.serviceCodes()).contains("SP01", "NS02", "DS-03");
    }
}
