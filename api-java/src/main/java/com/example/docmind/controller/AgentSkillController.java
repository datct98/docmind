package com.example.docmind.controller;

import com.example.docmind.dto.bot.AgentSkillDto;
import com.example.docmind.dto.bot.AgentSkillRequest;
import com.example.docmind.service.bot.AgentSkillService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST Controller quản lý Kho Kỹ năng Agent (Agent Skill Registry: PSYCHOLOGY, SALES_STRATEGY, COMMUNICATION).
 * Hỗ trợ bật/tắt, chỉnh sửa và thêm mới kỹ năng như gắn plugin cho Agent.
 */
@RestController
@RequestMapping("/api/skills")
public class AgentSkillController {

    private final AgentSkillService agentSkillService;

    public AgentSkillController(AgentSkillService agentSkillService) {
        this.agentSkillService = agentSkillService;
    }

    @GetMapping
    public ResponseEntity<List<AgentSkillDto>> getSkills(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String search,
            @RequestParam(name = "page_id", required = false) String pageId,
            @RequestParam(name = "pageId", required = false) String pageIdAlias,
            @RequestParam(name = "include_global", required = false, defaultValue = "true") boolean includeGlobal
    ) {
        String effectivePageId = (pageId != null && !pageId.isBlank()) ? pageId : pageIdAlias;
        return ResponseEntity.ok(agentSkillService.getSkills(category, active, search, effectivePageId, includeGlobal));
    }

    @PostMapping
    public ResponseEntity<AgentSkillDto> createSkill(@RequestBody AgentSkillRequest request) {
        AgentSkillDto created = agentSkillService.createSkill(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<AgentSkillDto> updateSkill(
            @PathVariable UUID id,
            @RequestBody AgentSkillRequest request
    ) {
        AgentSkillDto updated = agentSkillService.updateSkill(id, request);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSkill(@PathVariable UUID id) {
        agentSkillService.deleteSkill(id);
        return ResponseEntity.noContent().build();
    }
}
