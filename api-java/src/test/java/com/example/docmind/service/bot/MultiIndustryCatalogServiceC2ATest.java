package com.example.docmind.service.bot;

import com.example.docmind.repository.RetrievedChunk;
import com.example.docmind.service.bot.PackageCatalogService.CommercialRole;
import com.example.docmind.service.bot.PackageCatalogService.PackageSummary;
import com.example.docmind.service.bot.PackageCatalogService.ParseStatus;
import com.example.docmind.service.bot.PackageCatalogService.ParsedPrice;
import com.example.docmind.service.bot.PackageCatalogService.PriceType;
import com.example.docmind.service.bot.PackageCatalogService.RegionScope;
import com.example.docmind.service.retrieval.ReciprocalRankFusion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MultiIndustryCatalogServiceC2ATest {

    private static ReciprocalRankFusion.FusedChunk chunk(List<String> headings, String text) {
        return new ReciprocalRankFusion.FusedChunk(
                new RetrievedChunk(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        headings,
                        List.of(),
                        1,
                        new double[]{0, 0, 10, 10},
                        text,
                        0.95
                ),
                0.95
        );
    }

    @Test
    @DisplayName("C2-A.1: Bộ đọc giá tất định xử lý chuẩn 450k/m², 3.500k/khóa, 3,5 triệu, 3tr5, 3.500.000đ, Từ 450k/m² và từ chối giá mơ hồ")
    void deterministicPriceParserHandlesMultiIndustryFormatsAndRejectsAmbiguousInputs() {
        ParsedPrice pTile = PackageCatalogService.parseStructuredPrice("450k/m²");
        assertThat(pTile.parseStatus()).isEqualTo(ParseStatus.VERIFIED);
        assertThat(pTile.priceAmountVnd()).isEqualTo(450_000L);
        assertThat(pTile.priceType()).isEqualTo(PriceType.PER_UNIT);
        assertThat(pTile.billingUnit()).isEqualTo("/m²");
        assertThat(pTile.formattedCompactWithUnit()).isEqualTo("450k/m²");

        ParsedPrice pCourse = PackageCatalogService.parseStructuredPrice("3.500k/khóa");
        assertThat(pCourse.parseStatus()).isEqualTo(ParseStatus.VERIFIED);
        assertThat(pCourse.priceAmountVnd()).isEqualTo(3_500_000L);
        assertThat(pCourse.priceType()).isEqualTo(PriceType.PER_UNIT);
        assertThat(pCourse.billingUnit()).isEqualTo("/khóa");
        assertThat(pCourse.formattedCompactWithUnit()).isEqualTo("3.500k/khóa");

        ParsedPrice pCommaMillion = PackageCatalogService.parseStructuredPrice("3,5 triệu");
        assertThat(pCommaMillion.parseStatus()).isEqualTo(ParseStatus.VERIFIED);
        assertThat(pCommaMillion.priceAmountVnd()).isEqualTo(3_500_000L);

        ParsedPrice pSplitTr = PackageCatalogService.parseStructuredPrice("3tr5");
        assertThat(pSplitTr.parseStatus()).isEqualTo(ParseStatus.VERIFIED);
        assertThat(pSplitTr.priceAmountVnd()).isEqualTo(3_500_000L);

        ParsedPrice pFullVnd = PackageCatalogService.parseStructuredPrice("3.500.000đ");
        assertThat(pFullVnd.parseStatus()).isEqualTo(ParseStatus.VERIFIED);
        assertThat(pFullVnd.priceAmountVnd()).isEqualTo(3_500_000L);
        assertThat(pFullVnd.priceType()).isEqualTo(PriceType.FIXED);

        ParsedPrice pStartingFrom = PackageCatalogService.parseStructuredPrice("Từ 450k/m²");
        assertThat(pStartingFrom.parseStatus()).isEqualTo(ParseStatus.VERIFIED);
        assertThat(pStartingFrom.priceAmountVnd()).isEqualTo(450_000L);
        assertThat(pStartingFrom.priceType()).isEqualTo(PriceType.STARTING_FROM);
        assertThat(pStartingFrom.billingUnit()).isEqualTo("/m²");
        assertThat(pStartingFrom.formattedCompactWithUnit()).isEqualTo("Từ 450k/m²");

        // Giá không đáng tin cậy phải đánh dấu NEEDS_VERIFICATION hoặc FAILED, không âm thầm biến thành số sai
        ParsedPrice pContact = PackageCatalogService.parseStructuredPrice("Liên hệ");
        assertThat(pContact.priceType()).isEqualTo(PriceType.CONTACT);
        assertThat(pContact.parseStatus()).isEqualTo(ParseStatus.NEEDS_VERIFICATION);
        assertThat(pContact.priceAmountVnd()).isZero();

        ParsedPrice pAmbiguous = PackageCatalogService.parseStructuredPrice("3.50k");
        assertThat(pAmbiguous.parseStatus()).isEqualTo(ParseStatus.NEEDS_VERIFICATION);
        assertThat(pAmbiguous.priceAmountVnd()).isZero();

        ParsedPrice pMalformed = PackageCatalogService.parseStructuredPrice("3.5000k");
        assertThat(pMalformed.parseStatus()).isEqualTo(ParseStatus.FAILED);
        assertThat(pMalformed.priceAmountVnd()).isZero();

        ParsedPrice pGibberish = PackageCatalogService.parseStructuredPrice("Tư vấn theo bản vẽ");
        assertThat(pGibberish.parseStatus()).isEqualTo(ParseStatus.FAILED);
        assertThat(pGibberish.priceAmountVnd()).isZero();
    }

    @Test
    @DisplayName("C2-A.2: Parse bảng Markdown có SKU đa ngành (GACH-8080, TILE_80X80, IELTS-24B), không ép <580k thành phòng chung, không gán ECONOMICAL_OPTION mù quáng")
    void parsesMultiIndustrySkuTablesWithoutSpaContaminationOrBlindEconomicalRole() {
        String tileTable = """
                | Mã SP | Tên sản phẩm | Kích thước | Đơn giá | Ứng dụng phù hợp | Bao gồm |
                | --- | --- | --- | --- | --- | --- |
                | GACH-6060 | Gạch Porcelain Mờ | 60x60cm | 320k/m² | Phòng bếp, nhà tắm, chống trơn | Tư vấn phối màu |
                | GACH-8080 | Gạch ốp lát Granite Vân Đá | 80x80cm | 450k/m² | Phòng khách, sảnh lớn | Keo dán gạch tiêu chuẩn |
                | TILE_80X80 | Gạch Bóng Kiếng Cao Cấp | 80x80cm | Từ 520k/m² | Phòng khách, biệt thự | Giao hàng tận công trình |
                | GACH-ERR | Gạch Đặt Riêng Theo Mẫu | 120x120cm | Liên hệ | Đại sảnh | Báo giá riêng |
                """;
        List<String> tileDomainKeywords = List.of("phòng khách", "chống trơn", "nhà tắm", "vân đá");

        List<PackageSummary> verified = PackageCatalogService.extractCatalog(
                List.of(chunk(List.of("BẢNG GIÁ GẠCH ỐP LÁT"), tileTable)),
                tileDomainKeywords
        );
        List<PackageSummary> withDiag = PackageCatalogService.extractCatalogWithDiagnostics(
                List.of(chunk(List.of("BẢNG GIÁ GẠCH ỐP LÁT"), tileTable)),
                tileDomainKeywords
        );

        assertThat(verified).hasSize(3);
        assertThat(withDiag).hasSize(4);

        PackageSummary g8080 = verified.stream().filter(p -> "GACH-8080".equals(p.code())).findFirst().orElseThrow();
        assertThat(g8080.priceAmountVnd()).isEqualTo(450_000L);
        assertThat(g8080.priceType()).isEqualTo(PriceType.PER_UNIT);
        assertThat(g8080.billingUnit()).isEqualTo("/m²");
        assertThat(g8080.priceLabel()).isEqualTo("Đơn giá").doesNotContain("Giá trọn gói");
        assertThat(g8080.formattedPriceWithUnit()).isEqualTo("450k/m²");
        // Không bị ép thành phòng chung / rèm che dù giá 450k < 580k
        assertThat(g8080.roomType()).isEmpty();
        assertThat(g8080.customerFacingSpaceNote()).isEmpty();
        // Không bị gán vùng "HN" mặc định, mà là UNSPECIFIED
        assertThat(g8080.regionScope()).isEqualTo(RegionScope.UNSPECIFIED);
        assertThat(g8080.region()).isEqualTo("UNSPECIFIED");
        // "Keo dán gạch" không bị bắt nhầm thành bộ phận cơ thể "eo"
        assertThat(g8080.scopeTags()).contains("phòng khách", "vân đá").doesNotContain("eo");
        assertThat(g8080.specifications())
                .anySatisfy(s -> {
                    assertThat(s.rawValue()).isEqualTo("80x80cm");
                    assertThat(s.unit()).isEqualTo("cm");
                });

        // GACH-6060 (320k/m²) là sản phẩm ngang hàng kích thước khác -> KHÔNG được tự gán ECONOMICAL_OPTION chỉ vì giá thấp nhất!
        PackageSummary g6060 = verified.stream().filter(p -> "GACH-6060".equals(p.code())).findFirst().orElseThrow();
        assertThat(g6060.commercialRole()).isEqualTo(CommercialRole.STANDARD);
        assertThat(g8080.commercialRole()).isEqualTo(CommercialRole.STANDARD);

        // Dòng "Liên hệ" nằm trong diagnostics với NEEDS_VERIFICATION
        PackageSummary gErr = withDiag.stream().filter(p -> "GACH-ERR".equals(p.code())).findFirst().orElseThrow();
        assertThat(gErr.parseStatus()).isEqualTo(ParseStatus.NEEDS_VERIFICATION);
        assertThat(gErr.priceType()).isEqualTo(PriceType.CONTACT);

        // Bộ lọc vùng không làm rơi sản phẩm UNSPECIFIED
        assertThat(PackageCatalogService.filterByRegion(verified, "HN")).hasSize(3);
    }

    @Test
    @DisplayName("C2-A.3: Parse bảng Markdown KHÔNG có cột SKU, danh sách Bullet chuẩn, và phân biệt rõ UNSPECIFIED vs GLOBAL_CONFIRMED vs BRANCH_SPECIFIC")
    void parsesSkuLessTablesAndStructuredBulletsAndDistinguishesRegionScopes() {
        String skuLessCourseTable = """
                | Khóa học | Số buổi | Học phí | Mục tiêu đầu ra | Hình thức |
                | --- | --- | --- | --- | --- |
                | Khóa Tiếng Anh Giao Tiếp Phản Xạ | 24 buổi | 3.500k/khóa | Giao tiếp công sở, phản xạ tự nhiên | Học trực tuyến qua video & Zoom |
                | Khóa Luyện Thi IELTS Tăng Tốc | 36 buổi | 6,5 triệu/khóa | IELTS 6.5, học thuật | Lớp 8 học viên |
                """;
        List<PackageSummary> courses = PackageCatalogService.extractCatalog(
                List.of(chunk(List.of("CHƯƠNG TRÌNH ĐÀO TẠO", "Áp dụng toàn quốc"), skuLessCourseTable)),
                List.of("ielts 6.5", "giao tiếp công sở")
        );
        assertThat(courses).hasSize(2);
        PackageSummary commCourse = courses.get(0);
        assertThat(commCourse.canonicalName()).isEqualTo("Khóa Tiếng Anh Giao Tiếp Phản Xạ");
        assertThat(commCourse.productId()).startsWith("ITEM_");
        assertThat(commCourse.priceAmountVnd()).isEqualTo(3_500_000L);
        assertThat(commCourse.billingUnit()).isEqualTo("/khóa");
        assertThat(commCourse.regionScope()).isEqualTo(RegionScope.GLOBAL_CONFIRMED);
        assertThat(commCourse.scopeTags()).contains("giao tiếp công sở").doesNotContain("eo"); // "video" không dính chữ "eo"
        assertThat(commCourse.specifications())
                .anySatisfy(s -> {
                    assertThat(s.rawValue()).isEqualTo("24 buổi");
                    assertThat(s.numericValue()).isEqualTo(24.0);
                    assertThat(s.unit()).isEqualTo("buổi");
                });
        assertThat(commCourse.durationMinutes()).isNull();

        String bulletCatalog = """
                Danh sách sản phẩm nổi bật tháng này:
                - Gạch ốp lát 80x80cm — 450k/m² — Phù hợp: phòng khách, chống trơn
                - [IELTS-24B] Khóa học Tiếng Anh 24 buổi — 3.500k/khóa — Phù hợp: người đi làm, IELTS 6.5
                """;
        List<PackageSummary> fromBullets = PackageCatalogService.extractCatalog(
                List.of(chunk(List.of("DANH MỤC NIÊM YẾT"), bulletCatalog)),
                List.of("chống trơn", "ielts 6.5")
        );
        assertThat(fromBullets).hasSize(2);
        assertThat(fromBullets.get(0).priceAmountVnd()).isEqualTo(450_000L);
        assertThat(fromBullets.get(0).billingUnit()).isEqualTo("/m²");
        assertThat(fromBullets.get(0).canonicalLabel()).isEqualTo("Gạch ốp lát 80x80cm");
        assertThat(fromBullets.get(1).code()).isEqualTo("IELTS-24B");
        assertThat(fromBullets.get(1).priceAmountVnd()).isEqualTo(3_500_000L);
        assertThat(fromBullets.get(1).billingUnit()).isEqualTo("/khóa");

        // Bảng FAQ / Khuyến mãi không được parse nhầm thành sản phẩm
        String faqAndPromo = """
                | Câu hỏi | Trả lời |
                | --- | --- |
                | Gói 670k và 780k khác gì nhau? | Gói 780k có phòng riêng tư |
                """;
        assertThat(PackageCatalogService.extractCatalog(List.of(chunk(List.of("FAQ"), faqAndPromo)), List.of())).isEmpty();
    }
}
