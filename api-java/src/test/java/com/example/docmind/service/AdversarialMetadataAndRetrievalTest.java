package com.example.docmind.service;

import com.example.docmind.dto.ChunkMetadata;
import com.example.docmind.service.metadata.ChunkMetadataExtractor;
import com.example.docmind.service.text.VietnameseQueryNormalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Adversarial Challenger Test Suite for Features F2 and F4:
 * 1. Heading path formatting, null/empty/deeply nested headings, vector prefixes.
 * 2. Dynamic ChunkMetadata extraction under stress: no prices, multiple currencies, 20+ prices, non-standard codes.
 * 3. Branch tag resolution and query normalization for branch queries (e.g. "cơ sở Quận 1 Sài Gòn có gói nào?").
 * 4. Zero hardcoded package IDs (NS01, DS01, etc.).
 */
public class AdversarialMetadataAndRetrievalTest {

    @Nested
    @DisplayName("Adversarial Tests: Heading Path & Vector Prefix (F2)")
    class HeadingPathAdversarialTests {

        @Test
        @DisplayName("Stress: Null, empty, and whitespace-only heading paths produce empty prefix")
        void testNullAndEmptyHeadings() {
            assertThat(DocumentService.formatHeadingPrefix(null)).isEmpty();
            assertThat(DocumentService.formatHeadingPrefix(new String[0])).isEmpty();
            assertThat(DocumentService.formatHeadingPrefix(new String[]{""})).isEmpty();
            assertThat(DocumentService.formatHeadingPrefix(new String[]{"   ", "\t", "\n"})).isEmpty();
            assertThat(DocumentService.formatHeadingPrefix(new String[]{null, "", "  "})).isEmpty();
        }

        @Test
        @DisplayName("Stress: Single-element heading produces clean breadcrumb")
        void testSingleElementHeading() {
            String[] single = new String[]{"Bảng Giá Dịch Vụ 2026"};
            assertThat(DocumentService.formatHeadingPrefix(single))
                    .isEqualTo("[Bảng Giá Dịch Vụ 2026]");
        }

        @Test
        @DisplayName("Stress: Deeply nested (15 levels) heading hierarchy")
        void testDeeplyNestedHeading() {
            String[] levels = new String[]{
                    "Level 1 (Tập đoàn)", "Level 2 (Thương hiệu Nàng Sen)", "Level 3 (Miền Nam)",
                    "Level 4 (TP.HCM)", "Level 5 (Chi nhánh Quận 1)", "Level 6 (Tầng 3)",
                    "Level 7 (Khu VIP)", "Level 8 (Phòng Trị Liệu 801)", "Level 9 (Liệu trình Thảo Dược)",
                    "Level 10 (Gói Đặc Biệt)", "Level 11 (Quy trình ngâm bồn)", "Level 12 (Chi tiết bước 1)",
                    "Level 13 (Nhiệt độ nước)", "Level 14 (Dược liệu quý)", "Level 15 (Lưu ý an toàn)"
            };

            String prefix = DocumentService.formatHeadingPrefix(levels);
            assertThat(prefix).startsWith("[Level 1 (Tập đoàn) > Level 2 (Thương hiệu Nàng Sen) > ");
            assertThat(prefix).endsWith(" > Level 15 (Lưu ý an toàn)]");

            // Verify metadata extraction doesn't crash with 15 levels
            ChunkMetadata meta = ChunkMetadataExtractor.extract(levels, "Nhiệt độ nước 38 độ C");
            assertThat(meta).isNotNull();
            assertThat(meta.branchTags()).contains("quan_1");
        }

        @Test
        @DisplayName("Stress: Interspersed nulls, blanks, and special characters in heading")
        void testHeadingWithInterspersedNullsAndSpecialChars() {
            String[] dirty = new String[]{
                    "MASSAGE & SPA",
                    null,
                    "   ",
                    "Chi nhánh: \"Quận 1\" [Sài Gòn] - (Hotline: 079.811.2255)",
                    ""
            };
            String prefix = DocumentService.formatHeadingPrefix(dirty);
            assertThat(prefix).isEqualTo("[MASSAGE & SPA > Chi nhánh: \"Quận 1\" [Sài Gòn] - (Hotline: 079.811.2255)]");

            ChunkMetadata meta = ChunkMetadataExtractor.extract(dirty, "| Mã | Giá |\n| --- | --- |\n| SP01 | 500.000VNĐ |");
            assertThat(meta.branchTags()).contains("quan_1");
            assertThat(meta.minPrice()).isEqualTo(500_000L);
        }
    }

    @Nested
    @DisplayName("Adversarial Tests: ChunkMetadataExtractor Under Stress (F4)")
    class ChunkMetadataStressTests {

        @Test
        @DisplayName("Stress: Chunks with NO prices (FAQ, Process, Promo without numbers)")
        void testChunksWithNoPrices() {
            // 1. FAQ chunk without prices
            List<String> faqHeadings = List.of("HỎI ĐÁP", "Chính sách hủy lịch");
            String faqText = """
                    | Câu hỏi | Hướng dẫn |
                    | --- | --- |
                    | Tôi có thể hủy lịch trước bao lâu? | Quý khách vui lòng báo trước ít nhất 2 giờ để được hỗ trợ chuyển lịch. |
                    | Có bị mất phí hủy lịch không? | Không thu bất kỳ khoản phí hủy lịch nào. |
                    """;
            ChunkMetadata faqMeta = ChunkMetadataExtractor.extract(faqHeadings, faqText);
            assertThat(faqMeta.chunkRole()).isEqualTo(ChunkMetadata.ROLE_FAQ);
            assertThat(faqMeta.minPrice()).isNull();
            assertThat(faqMeta.maxPrice()).isNull();
            assertThat(faqMeta.isPriceTable()).isFalse();
            assertThat(faqMeta.isEntryLevel()).isFalse();
            assertThat(ChunkMetadataExtractor.isEntryLevelPriceChunk(faqHeadings, faqText)).isFalse();

            // 2. Process chunk without prices
            List<String> processHeadings = List.of("QUY TRÌNH KỸ THUẬT", "Các bước gội đầu dưỡng sinh");
            String processText = """
                    Bước 1: Khai thông huyệt đạo vùng đầu và vai gáy.
                    Bước 2: Rửa mặt và tẩy tế bào chết bằng thảo mộc tự nhiên.
                    Bước 3: Massage bấm huyệt chuyên sâu giải tỏa căng thẳng.
                    """;
            ChunkMetadata procMeta = ChunkMetadataExtractor.extract(processHeadings, processText);
            assertThat(procMeta.chunkRole()).isEqualTo(ChunkMetadata.ROLE_PROCESS);
            assertThat(procMeta.minPrice()).isNull();
            assertThat(procMeta.maxPrice()).isNull();
            assertThat(ChunkMetadataExtractor.isEntryLevelPriceChunk(processHeadings, processText)).isFalse();

            // 3. Promo chunk without numeric price figures
            List<String> promoHeadings = List.of("CHÍNH SÁCH ƯU ĐÃI", "Chiết khấu cho khách đoàn");
            String promoText = """
                    Khách đoàn từ 5 người: Giảm 10% tổng hóa đơn dịch vụ.
                    Khách đoàn từ 10 người: Giảm 20% tổng hóa đơn dịch vụ.
                    Tặng kèm trà dưỡng nhan và bánh ăn nhẹ sau liệu trình.
                    """;
            ChunkMetadata promoMeta = ChunkMetadataExtractor.extract(promoHeadings, promoText);
            assertThat(promoMeta.chunkRole()).isEqualTo(ChunkMetadata.ROLE_PROMO);
            assertThat(promoMeta.minPrice()).isNull();
            assertThat(promoMeta.maxPrice()).isNull();
        }

        @Test
        @DisplayName("Stress: Chunks with multiple currencies ($ USD vs VNĐ)")
        void testChunksWithMultipleCurrencies() {
            // Text contains both foreign currency ($ USD) and Vietnamese VNĐ
            String text = """
                    Dịch vụ dành cho khách quốc tế:
                    - Gói massage body cơ bản: $25 USD (tương đương 600.000 VNĐ) cho 60 phút.
                    - Gói massage trị liệu chuyên sâu: $45 USD (tương đương 1.100.000 VNĐ) cho 90 phút.
                    - Gói VIP Hoàng Gia: $80 USD (tương đương 1.950.000 VNĐ) cho 120 phút.
                    """;

            List<Long> prices = ChunkMetadataExtractor.extractAllPrices(text);

            // $25, $45, $80 should not be recognized as VND amounts (< 10,000 threshold)
            // Only 600.000, 1.100.000, and 1.950.000 should be extracted
            assertThat(prices).containsExactly(600_000L, 1_100_000L, 1_950_000L);

            ChunkMetadata meta = ChunkMetadataExtractor.extract(List.of("DỊCH VỤ QUỐC TẾ"), text);
            assertThat(meta.minPrice()).isEqualTo(600_000L);
            assertThat(meta.maxPrice()).isEqualTo(1_950_000L);
        }

        @Test
        @DisplayName("Stress: Large price table with 25+ distinct prices")
        void testLargePriceTableWithManyPrices() {
            StringBuilder sb = new StringBuilder();
            sb.append("| Mã gói | Tên gói | Đơn giá | Thời gian |\n");
            sb.append("| --- | --- | --- | --- |\n");

            // Generate 25 packages from 120.000 to 3.600.000
            for (int i = 1; i <= 25; i++) {
                long price = 100_000L + (i * 140_000L); // e.g. 240.000 to 3.600.000
                String code = String.format("PKG-%02d", i);
                sb.append(String.format("| %s | Gói dịch vụ số %d | %,d VNĐ | 60 phút |\n", code, i, price).replace(',', '.'));
            }

            String tableText = sb.toString();
            List<String> headings = List.of("BẢNG GIÁ TỔNG HỢP TOÀN BỘ 25 DỊCH VỤ");

            ChunkMetadata meta = ChunkMetadataExtractor.extract(headings, tableText);

            assertThat(meta.chunkRole()).isEqualTo(ChunkMetadata.ROLE_PRICE_TABLE);
            assertThat(meta.minPrice()).isEqualTo(240_000L);
            assertThat(meta.maxPrice()).isEqualTo(3_600_000L);
            assertThat(meta.serviceCodes()).hasSize(25);
            assertThat(meta.serviceCodes()).contains("PKG-01", "PKG-25");
            assertThat(meta.isEntryLevel()).isTrue(); // PKG-01 ends with 01
        }

        @Test
        @DisplayName("Stress: Arbitrary non-standard package codes (healthcare, education, dental)")
        void testNonStandardPackageCodes() {
            // Dental clinic
            String dentalText = """
                    | Mã DV | Thủ thuật nha khoa | Chi phí niêm yết |
                    | --- | --- | --- |
                    | NK-01 | Khám tổng quát & cạo vôi răng | 150.000 đ |
                    | IMPLANT_01 | Trồng răng Implant Dentium | 15.000.000 VNĐ |
                    | CERCON-HT | Bọc răng sứ Cercon HT cao cấp | 6.500.000 VNĐ |
                    """;
            ChunkMetadata dentalMeta = ChunkMetadataExtractor.extract(List.of("NHA KHOA QUỐC TẾ"), dentalText);
            assertThat(dentalMeta.serviceCodes()).contains("NK-01", "IMPLANT_01", "CERCON-HT");
            assertThat(dentalMeta.minPrice()).isEqualTo(150_000L);
            assertThat(dentalMeta.maxPrice()).isEqualTo(15_000_000L);

            // Fitness & Yoga center
            String gymText = """
                    | Code | Gói tập Gym & Yoga | Phí gói |
                    | --- | --- | --- |
                    | GYM_TRIAL | Tập thử 3 ngày trải nghiệm | 99k |
                    | YOGA-MONTH | Thẻ Yoga không giới hạn 1 tháng | 850k |
                    | PT-VIP10 | Huấn luyện viên cá nhân 10 buổi | 3.500.000 VNĐ |
                    """;
            ChunkMetadata gymMeta = ChunkMetadataExtractor.extract(List.of("FITNESS & YOGA CENTER"), gymText);
            assertThat(gymMeta.serviceCodes()).contains("GYM_TRIAL", "YOGA-MONTH", "PT-VIP10");
            assertThat(gymMeta.minPrice()).isEqualTo(99_000L);
            assertThat(gymMeta.maxPrice()).isEqualTo(3_500_000L);
            assertThat(ChunkMetadataExtractor.isEntryLevelPriceChunk(List.of("FITNESS CENTER"), gymText)).isTrue();
        }

        @Test
        @DisplayName("Stress: Branch tags extraction for diverse Vietnamese geographical locations")
        void testDiverseBranchTagExtraction() {
            // Test 1: Sài Gòn / TP.HCM / Quận 1
            List<String> tagsQ1 = ChunkMetadataExtractor.extractBranchTags(
                    "Cơ sở Nàng Sen Quận 1 (TP.HCM) — 74 Nguyễn Công Trứ, Sài Gòn",
                    "Vui lòng ghé chi nhánh Quận 1 để trải nghiệm."
            );
            assertThat(tagsQ1).contains("quan_1");

            // Test 2: Hà Nội - Bích Câu & Long Biên & Cầu Giấy
            List<String> tagsHn = ChunkMetadataExtractor.extractBranchTags(
                    "Chi nhánh Hà Nội (Bích Câu, Long Biên, Cầu Giấy)",
                    "Dịch vụ áp dụng tại tất cả cơ sở Hà Nội"
            );
            assertThat(tagsHn).contains("bich_cau", "long_bien", "cau_giay");

            // Test 3: Dynamic regex branch discovery for unlisted locations
            List<String> tagsDynamic = ChunkMetadataExtractor.extractBranchTags(
                    "Cơ sở Cần Thơ — Đường 30 Tháng 4, Phường Ninh Kiều",
                    "Dịch vụ tại miền Tây"
            );
            assertThat(tagsDynamic).contains("can_tho");
        }
    }

    @Nested
    @DisplayName("Adversarial Tests: Branch Query Resolution (cơ sở Quận 1 Sài Gòn có gói nào?)")
    class BranchQueryRetrievalTests {

        @Test
        @DisplayName("Query Normalization: 'cơ sở Quận 1 Sài Gòn có gói nào?' preserves essential keywords")
        void testQueryNormalizationForBranchQuery() {
            String rawQuery = "cơ sở Quận 1 Sài Gòn có gói nào?";
            String normalized = VietnameseQueryNormalizer.normalize(rawQuery);

            assertThat(normalized).contains("cơ sở");
            assertThat(normalized).contains("Quận 1");
            assertThat(normalized).contains("Sài Gòn");
            assertThat(normalized).contains("gói");
        }

        @Test
        @DisplayName("Query Normalization: Telex typos and abbreviations in branch inquiry")
        void testTypoNormalizationInBranchQuery() {
            String typoQuery = "cs Quan 1 Sai Gon co gois naof re khong shop, muon thu trai nghiem truoc";
            String normalized = VietnameseQueryNormalizer.normalize(typoQuery);

            // 'cs' -> 'cơ sở'
            assertThat(normalized).contains("cơ sở");
            // 'gois' -> 'gói'
            assertThat(normalized).contains("gói");
            // 'muon' / 'trai nghiem' -> 'trải nghiệm'
            assertThat(normalized).contains("trải nghiệm");
        }

        @Test
        @DisplayName("Branch Tag Match: Query 'cơ sở Quận 1 Sài Gòn có gói nào?' extracts 'quan_1'")
        void testBranchTagMatchingFromBranchQuery() {
            String query = "cơ sở Quận 1 Sài Gòn có gói nào?";
            List<String> branchTags = ChunkMetadataExtractor.extractBranchTags(query, "");
            assertThat(branchTags).contains("quan_1");
        }

        @Test
        @DisplayName("Domain Agnostic: No hardcoded package IDs (NS01, DS01, etc.) in ChunkMetadataExtractor")
        void testZeroHardcodedPackageIds() {
            // Check that ChunkMetadataExtractor does not rely on any NS01/DS01 literals.
            // A clinic with code MED01 or SPA01 works identically.
            List<String> headings = List.of("SPA TRỊ LIỆU", "Gói Chăm Sóc Da");
            String text = """
                    | Mã gói | Tên liệu trình | Giá niêm yết |
                    | --- | --- | --- |
                    | MED01 | Thải độc da cơ bản | 250.000 VNĐ |
                    | MED02 | Nâng cơ RF chuyên sâu | 890.000 VNĐ |
                    """;

            ChunkMetadata meta = ChunkMetadataExtractor.extract(headings, text);
            assertThat(meta.isPriceTable()).isTrue();
            assertThat(meta.serviceCodes()).containsExactly("MED01", "MED02");
            assertThat(meta.isEntryLevel()).isTrue();
            assertThat(meta.minPrice()).isEqualTo(250_000L);
            assertThat(meta.maxPrice()).isEqualTo(890_000L);
        }
    }
}
