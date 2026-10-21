package com.example.docmind.controller;

import com.example.docmind.dto.bot.AgentSkillDto;
import com.example.docmind.dto.bot.AgentSkillRequest;
import com.example.docmind.entity.AgentSkillEntity;
import com.example.docmind.repository.AgentSkillRepository;
import com.example.docmind.service.bot.AgentSkillService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentSkillControllerTest {

    private AgentSkillRepository repository;
    private AgentSkillService service;
    private AgentSkillController controller;

    @BeforeEach
    void setUp() {
        repository = mock(AgentSkillRepository.class);
        service = new AgentSkillService(repository);
        controller = new AgentSkillController(service);
    }

    @Test
    void getSkillsFallsBackToBuiltinSkillsWhenRepositoryEmpty() {
        when(repository.findAllOrdered()).thenReturn(List.of());

        ResponseEntity<List<AgentSkillDto>> resp = controller.getSkills(null, null, null, null, null, true);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody()).hasSize(12);
        assertThat(resp.getBody()).extracting(AgentSkillDto::code)
                .contains(
                        "PSYCH_CHRONIC_EMPATHY",
                        "SALES_SPIN_DIAGNOSIS",
                        "COMM_MIRROR_AND_PACE",
                        "SALES_ANTI_INFO_DUMPING_PACING",
                        "SALES_APPOINTMENT_HOOK_CLOSE"
                );
    }

    @Test
    void getSkillsWithoutPageIdReturnsOnlyGlobalSkills() {
        UUID pageA = UUID.randomUUID();
        AgentSkillEntity globalSkill = AgentSkillEntity.builder()
                .id(UUID.randomUUID())
                .code("GLOBAL_SKILL")
                .name("Kỹ năng Toàn Cục")
                .category("SALES_STRATEGY")
                .pageId(null)
                .active(true)
                .priority(90)
                .build();
        AgentSkillEntity tenantSkill = AgentSkillEntity.builder()
                .id(UUID.randomUUID())
                .code("TENANT_SKILL")
                .name("Kỹ năng Riêng Page")
                .category("SALES_STRATEGY")
                .pageId(pageA)
                .active(true)
                .priority(90)
                .build();

        when(repository.findAllOrdered()).thenReturn(List.of(globalSkill, tenantSkill));

        ResponseEntity<List<AgentSkillDto>> resp = controller.getSkills(null, null, null, null, null, true);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody()).hasSize(1);
        assertThat(resp.getBody().get(0).code()).isEqualTo("GLOBAL_SKILL");
        assertThat(resp.getBody().get(0).pageId()).isNull();
    }

    @Test
    void createAndUpdateAndDeleteSkillWorkCorrectly() {
        when(repository.save(any(AgentSkillEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        AgentSkillRequest createReq = new AgentSkillRequest(
                "CUSTOM_CLOSING_VIP",
                "Chốt lịch VIP Đặc Quyền",
                "SALES_STRATEGY",
                "Áp dụng khi khách VIP hỏi đặt lịch",
                "vip, đặt lịch",
                "Luôn đề xuất khung giờ vàng và phòng riêng",
                "Ví dụ chuẩn",
                "Không hỏi dồn dập",
                95,
                true,
                null
        );

        ResponseEntity<AgentSkillDto> createResp = controller.createSkill(createReq);
        assertThat(createResp.getStatusCode().value()).isEqualTo(201);
        AgentSkillDto created = createResp.getBody();
        assertThat(created).isNotNull();
        assertThat(created.code()).isEqualTo("CUSTOM_CLOSING_VIP");
        assertThat(created.name()).isEqualTo("Chốt lịch VIP Đặc Quyền");
        assertThat(created.category()).isEqualTo("SALES_STRATEGY");
        assertThat(created.priority()).isEqualTo(95);

        UUID skillId = created.id();
        AgentSkillEntity existingEntity = AgentSkillEntity.builder()
                .id(skillId)
                .code("CUSTOM_CLOSING_VIP")
                .name("Chốt lịch VIP Đặc Quyền")
                .category("SALES_STRATEGY")
                .description("Áp dụng khi khách VIP hỏi đặt lịch")
                .triggerSignals("vip, đặt lịch")
                .tacticalInstructions("Luôn đề xuất khung giờ vàng và phòng riêng")
                .goodExample("Ví dụ chuẩn")
                .antiPattern("Không hỏi dồn dập")
                .priority(95)
                .active(true)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(repository.findById(skillId)).thenReturn(Optional.of(existingEntity));
        when(repository.existsById(skillId)).thenReturn(true);

        AgentSkillRequest updateReq = new AgentSkillRequest(
                null,
                "Chốt lịch VIP Đẳng Cấp",
                "SALES_STRATEGY",
                "Mô tả mới",
                "vip, chốt lịch",
                "Hướng dẫn mới",
                "Ví dụ mới",
                "Anti pattern mới",
                99,
                false,
                null
        );

        ResponseEntity<AgentSkillDto> updateResp = controller.updateSkill(skillId, updateReq);
        assertThat(updateResp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(updateResp.getBody()).isNotNull();
        assertThat(updateResp.getBody().name()).isEqualTo("Chốt lịch VIP Đẳng Cấp");
        assertThat(updateResp.getBody().active()).isFalse();
        assertThat(updateResp.getBody().priority()).isEqualTo(99);

        ResponseEntity<Void> deleteResp = controller.deleteSkill(skillId);
        assertThat(deleteResp.getStatusCode().value()).isEqualTo(204);
        verify(repository).deleteById(skillId);
    }

    @Test
    void syncSkillFromFeedbackCreatesAdaptiveSkillWhenStrategicCorrectionDetected() {
        when(repository.save(any(AgentSkillEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        UUID pageId = UUID.randomUUID();
        service.syncSkillFromFeedbackIfApplicable(
                "Giá gói trị nám bao nhiêu?",
                "Khi khách hỏi giá phải đồng cảm tâm lý lo sợ tái phát và dùng kỹ năng trấn an trước khi báo giá",
                "Luôn đồng cảm tâm lý sợ tái phát và cam kết bảo hành trước khi báo giá chi tiết.",
                List.of("giá", "trị nám", "tái phát"),
                pageId
        );

        ArgumentCaptor<AgentSkillEntity> captor = ArgumentCaptor.forClass(AgentSkillEntity.class);
        verify(repository).save(captor.capture());
        AgentSkillEntity saved = captor.getValue();
        assertThat(saved.getCode()).startsWith("PSYCH_");
        assertThat(saved.getCategory()).isEqualTo("PSYCHOLOGY");
        assertThat(saved.getPageId()).isEqualTo(pageId);
        assertThat(saved.getTacticalInstructions()).contains("Luôn đồng cảm tâm lý sợ tái phát");

        // Gửi phản hồi lần 2 trên cùng câu hỏi & Page -> phải cập nhật in-place thay vì sinh bản ghi trùng lặp
        when(repository.findFirstByCodeIgnoreCase(saved.getCode())).thenReturn(Optional.of(saved));
        service.syncSkillFromFeedbackIfApplicable(
                "Giá gói trị nám bao nhiêu?",
                "Khi khách hỏi giá phải đồng cảm tâm lý lo sợ tái phát và báo kèm bảo hành 12 tháng",
                "Luôn đồng cảm tâm lý sợ tái phát và cam kết bảo hành 12 tháng bằng văn bản.",
                List.of("giá", "trị nám", "bảo hành 12 tháng"),
                pageId
        );
        assertThat(saved.getTacticalInstructions()).contains("bảo hành 12 tháng bằng văn bản");
        assertThat(saved.getTriggerSignals()).contains("bảo hành 12 tháng");
    }

    @Test
    void updateSkillAllowsResettingPageScopeToGlobalWhilePartialTogglePreservesPageScope() {
        UUID skillId = UUID.randomUUID();
        UUID initialPageId = UUID.randomUUID();
        AgentSkillEntity pageScopedEntity = AgentSkillEntity.builder()
                .id(skillId)
                .code("CUSTOM_PAGE_SKILL")
                .name("Kỹ năng Riêng của Page")
                .category("SALES_STRATEGY")
                .description("Mô tả")
                .triggerSignals("tư vấn")
                .tacticalInstructions("Chỉ dẫn")
                .priority(88)
                .active(true)
                .pageId(initialPageId)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(repository.findById(skillId)).thenReturn(Optional.of(pageScopedEntity));
        when(repository.save(any(AgentSkillEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        // 1. Partial toggle { active: false } (name == null) phải giữ nguyên pageId
        AgentSkillRequest toggleReq = new AgentSkillRequest(
                null, null, null, null, null, null, null, null, null, false, null
        );
        ResponseEntity<AgentSkillDto> toggled = controller.updateSkill(skillId, toggleReq);
        assertThat(toggled.getBody()).isNotNull();
        assertThat(toggled.getBody().active()).isFalse();
        assertThat(toggled.getBody().pageId()).isEqualTo(initialPageId.toString());

        // 2. Full form edit từ Modal chuyển phạm vi về Global (pageId = null, name != null) phải xóa pageId về null
        AgentSkillRequest resetToGlobalReq = new AgentSkillRequest(
                "CUSTOM_PAGE_SKILL",
                "Kỹ năng Đã Chuyển Thành Global",
                "SALES_STRATEGY",
                "Mô tả",
                "tư vấn",
                "Chỉ dẫn",
                null,
                null,
                90,
                true,
                null
        );
        ResponseEntity<AgentSkillDto> globalized = controller.updateSkill(skillId, resetToGlobalReq);
        assertThat(globalized.getBody()).isNotNull();
        assertThat(globalized.getBody().pageId()).isNull();
        assertThat(globalized.getBody().name()).isEqualTo("Kỹ năng Đã Chuyển Thành Global");
    }

    @Test
    void smartParseSkillAndOneStepCreateFromRawInstructionWorkWithoutManualFields() {
        when(repository.save(any(AgentSkillEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        AgentSkillRequest rawOnlyReq = new AgentSkillRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                true,
                null,
                "Khi khách mới hỏi \"giá\" chung chung mà chưa nói rõ tình trạng da: Tuyệt đối không xả sạch bảng giá 4 dịch vụ hay chiết khấu Combo. Chỉ báo giá từ 650.000đ/buổi kèm miễn phí Bác sĩ soi da AI 3D rồi đặt 1 câu hỏi khống chế A/B hỏi khách muốn ưu tiên trị mụn hay nám."
        );

        ResponseEntity<AgentSkillDto> parsedResp = controller.smartParseSkill(rawOnlyReq);
        assertThat(parsedResp.getStatusCode().is2xxSuccessful()).isTrue();
        AgentSkillDto parsed = parsedResp.getBody();
        assertThat(parsed).isNotNull();
        assertThat(parsed.name()).contains("Chống Xả Sạch Thông Tin");
        assertThat(parsed.category()).isEqualTo("SALES_STRATEGY");
        assertThat(parsed.triggerSignals()).contains("giá");
        assertThat(parsed.tacticalInstructions()).contains("650.000đ/buổi");
        assertThat(parsed.goodExample()).isNotBlank().doesNotContain("||MSG||");
        assertThat(parsed.antiPattern()).isNotBlank();

        ResponseEntity<AgentSkillDto> createdResp = controller.createSkill(rawOnlyReq);
        assertThat(createdResp.getStatusCode().value()).isEqualTo(201);
        AgentSkillDto created = createdResp.getBody();
        assertThat(created).isNotNull();
        assertThat(created.id()).isNotNull();
        assertThat(created.name()).contains("Chống Xả Sạch Thông Tin");
        assertThat(created.tacticalInstructions()).contains("650.000đ/buổi");

        // Auto-detect PSYCHOLOGY when category is AUTO or blank
        AgentSkillRequest psychReq = new AgentSkillRequest(
                null, null, "AUTO", null, null, null, null, null, null, true, null,
                "Khi khách hàng lo lắng sợ đau hoặc sợ biến chứng sưng đỏ, hãy đồng cảm trấn an tâm lý và cam kết phác đồ êm dịu."
        );
        AgentSkillDto parsedPsych = controller.smartParseSkill(psychReq).getBody();
        assertThat(parsedPsych).isNotNull();
        assertThat(parsedPsych.category()).isEqualTo("PSYCHOLOGY");
        assertThat(parsedPsych.name()).startsWith("[Tâm lý]");
    }
}

