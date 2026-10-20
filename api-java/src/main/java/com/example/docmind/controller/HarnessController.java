package com.example.docmind.controller;

import com.example.docmind.dto.harness.HarnessConversationAuditRequest;
import com.example.docmind.dto.harness.HarnessConversationAuditResponse;
import com.example.docmind.dto.harness.HarnessOptimizeRequest;
import com.example.docmind.dto.harness.HarnessOptimizeResponse;
import com.example.docmind.dto.harness.HarnessRunDto;
import com.example.docmind.service.harness.HarnessOptimizerService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST Controller serving the Harness² Studio, Whole-Conversation Audit, and 1-Click Auto-Optimization UI.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/harness/optimize}: Triggers the Propose–Probe–Anonymized Select cycle for a single turn.</li>
 *   <li>{@code POST /api/harness/audit-conversation}: Audits and optimizes an entire multi-turn conversation.</li>
 *   <li>{@code GET /api/harness/runs}: Retrieves optimization evolution history.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/harness")
@CrossOrigin(origins = "*")
public class HarnessController {

    private final HarnessOptimizerService optimizerService;

    @Autowired
    public HarnessController(HarnessOptimizerService optimizerService) {
        this.optimizerService = optimizerService;
    }

    /**
     * Executes the task-level contrastive optimization cycle.
     */
    @PostMapping("/optimize")
    public ResponseEntity<HarnessOptimizeResponse> optimize(@Valid @RequestBody HarnessOptimizeRequest request) {
        HarnessOptimizeResponse response = optimizerService.optimize(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Audits and optimizes an entire multi-turn conversation end-to-end.
     */
    @PostMapping("/audit-conversation")
    public ResponseEntity<HarnessConversationAuditResponse> auditConversation(
            @RequestBody HarnessConversationAuditRequest request
    ) {
        HarnessConversationAuditResponse response = optimizerService.auditConversation(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves the history of Harness² optimization runs.
     */
    @GetMapping("/runs")
    public ResponseEntity<List<HarnessRunDto>> getRuns(
            @RequestParam(name = "page_id", required = false) String pageId,
            @RequestParam(name = "verdict", required = false) String verdict,
            @RequestParam(name = "limit", required = false, defaultValue = "20") int limit
    ) {
        List<HarnessRunDto> runs = optimizerService.getRuns(pageId, verdict, limit);
        return ResponseEntity.ok(runs);
    }
}

