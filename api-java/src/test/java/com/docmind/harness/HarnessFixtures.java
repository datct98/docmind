package com.docmind.harness;

import com.docmind.harness.HarnessContracts.*;

import java.util.List;
import java.util.UUID;

/**
 * High-fidelity test fixtures and scenario datasets for Harness² E2E testing.
 */
public final class HarnessFixtures {

    private HarnessFixtures() {}

    public static final UUID PAGE_ID_SPA = UUID.fromString("8ab6b97b-1c2d-4048-b602-b6b77d332b51");
    public static final UUID PAGE_ID_CLINIC = UUID.fromString("9bc7c08c-2d3e-5159-c713-c7c88e443c62");

    // =========================================================================
    // 1. VIETNAMESE CONVERSATIONAL DATASETS
    // =========================================================================

    public static final String MSG_DISCOVERY_PRICE_GENERIC = "Shop ơi giá dịch vụ bên mình thế nào vậy?";
    public static final String MSG_DISCOVERY_PRICE_SLANG = "giá sao b";
    public static final String MSG_MALE_WITH_TYPOS_1 = "mìn là nam, có dùng đc liệu trình k?";
    public static final String MSG_MALE_WITH_TYPOS_2 = "mk la nam muon hoi gia tri mun";
    public static final String MSG_MALE_WITH_TYPOS_3 = "t là nam giới bị mụn viêm lâu năm";
    public static final String MSG_COOLING_BRUSH_OFF_1 = "ừ để chị xem thêm đã nhé";
    public static final String MSG_COOLING_BRUSH_OFF_2 = "hơi đắt em ơi, để chị suy nghĩ thêm";
    public static final String MSG_COOLING_BRUSH_OFF_3 = "ok em";
    public static final String MSG_HIGH_INTENT_BOOKING = "Anh muốn đặt lịch làm liệu trình trị mụn chiều nay luôn lúc 14h";

    // =========================================================================
    // 2. RAW UNGUARDED ASSISTANT REPLIES (VIOLATING INVARIANTS)
    // =========================================================================

    public static final List<String> DUMPING_BUBBLES_DISCOVERY = List.of(
            "Dạ em chào Chị Lan Anh ạ! Bên em có Liệu trình Trị Mụn 1.500.000 VNĐ, Trị Nám Laser 4.500.000 VNĐ, Căng bóng da 2.800.000 VNĐ ạ.",
            "Ngoài ra có Combo Tiết Kiệm giảm 10% (3-4 buổi) và Combo Toàn Diện giảm 15% (5-9 buổi) nhé Chị.",
            "Mã dịch vụ SPA-01 [1] cam kết hiệu quả cao.",
            "Chị có muốn đặt lịch làm luôn hôm nay được không ạ?"
    );

    public static final List<String> MALE_LEAKAGE_BUBBLES = List.of(
            "Dạ em chào Chị Lan Anh ạ! Liệu trình trị mụn rất tốt cho nam giới.",
            "Em có thể tham khảo bảng giá chi tiết, Chị có tiện để lại SĐT không ạ?"
    );

    public static final List<String> WEAK_CLOSING_BUBBLES = List.of(
            "Dạ liệu trình trị mụn chuẩn Y khoa bên em có giá 1.500.000 VNĐ ạ.",
            "Sau khi làm da sẽ hồi phục rất nhanh. Chị có tiện qua khám không ạ?"
    );

    public static final List<String> CLEAN_OPTIMAL_BUBBLES = List.of(
            "Dạ em chào Anh ạ! Chi phí liệu trình trị mụn chuẩn Y khoa bên em rất linh hoạt chỉ từ 650.000đ/buổi tùy theo tình trạng da của Anh, đã bao gồm Bác sĩ soi da AI 3D miễn phí 100% ạ.",
            "Hiện tại Anh đang muốn ưu tiên điều trị mụn viêm hay mụn ẩn trước để em tư vấn phác đồ chuẩn nhất cho Anh nhé?"
    );

    // =========================================================================
    // 3. SAMPLE EXPERIENCE LIBRARY RULES (INV vs DOM)
    // =========================================================================

    public static ExperienceRule createInvariantRule(String code, String cue, String lesson, int length) {
        String paddedLesson = lesson + " ".repeat(Math.max(0, length - lesson.length() - cue.length()));
        return ExperienceRule.create(
                HarnessDimension.INV,
                "COMMUNICATION",
                cue,
                paddedLesson,
                "❌ Before: Lỗi vi phạm\n✅ After: Tối ưu chuẩn mực",
                true,
                0.95,
                null // Global scope
        );
    }

    public static ExperienceRule createDomainRule(String code, String cue, String lesson, int length) {
        String paddedLesson = lesson + " ".repeat(Math.max(0, length - lesson.length() - cue.length()));
        return ExperienceRule.create(
                HarnessDimension.DOM,
                "SERVICE_POLICY",
                cue,
                paddedLesson,
                "❌ Before: Báo sai chính sách Page\n✅ After: Báo đúng bảng giá niêm yết",
                true,
                0.85,
                PAGE_ID_SPA
        );
    }

    public static List<ExperienceRule> sampleBudgetOverflownRules() {
        return List.of(
                createInvariantRule("INV-1", "Khi khách hạ nhiệt", "Buông áp lực bán hàng ngay lập tức", 350),
                createInvariantRule("INV-2", "Khi khách nam tự nhận diện", "Đồng bộ 100% xưng hô Anh", 350),
                createDomainRule("DOM-1", "Khi khách hỏi mụn viêm", "Liệu trình chuẩn Y khoa 1.500.000đ không trị sẹo rỗ", 400),
                createDomainRule("DOM-2", "Khi khách hỏi chính sách combo", "Combo 3-4 buổi giảm 10%, 5-9 buổi giảm 15%", 450)
        );
    }

    // =========================================================================
    // 4. SAMPLE OPTIMIZER REQUESTS
    // =========================================================================

    public static HarnessOptimizeRequest createCoolingLeadRequest(Long seed) {
        return new HarnessOptimizeRequest(
                PAGE_ID_SPA,
                "conv-cooling-001",
                MSG_COOLING_BRUSH_OFF_1,
                "Dạ liệu trình Trị Mụn bên em 1.500.000 VNĐ đang có ưu đãi, Chị có muốn chốt lịch luôn chiều nay không ạ?",
                List.of(
                        "User: Tư vấn trị mụn",
                        "Assistant: Dạ em chào Chị, liệu trình 1.500.000 VNĐ ạ"
                ),
                -20, // significant temperature drop
                List.of(AntiInfoDumpingHook.CODE, ClosingQuestionHook.CODE),
                "TEMPERATURE_DROP_BURST",
                seed
        );
    }

    public static HarnessOptimizeRequest createOptimalBaseRequest(Long seed) {
        return new HarnessOptimizeRequest(
                PAGE_ID_SPA,
                "conv-optimal-002",
                MSG_HIGH_INTENT_BOOKING,
                "Dạ em chào Anh ạ! Em đã ghi nhận lịch hẹn khám da cho Anh lúc 14h chiều nay nhé ạ. "
                        + "Chuyên viên tư vấn sẽ chuẩn bị hồ sơ soi da AI 3D miễn phí sẵn cho Anh khi đến ạ.",
                List.of(
                        "User: Cho anh đặt lịch",
                        "Assistant: Dạ vâng Anh"
                ),
                15, // temperature rising
                List.of(),
                "MANUAL_1CLICK",
                seed
        );
    }
}
