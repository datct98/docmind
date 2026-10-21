package com.example.docmind.controller;

import com.example.docmind.service.bot.ConversationInboxService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST Controller quản lý Hộp Thư Hội Thoại Tập Trung (Omnichannel Live Inbox & Human-in-the-Loop).
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationInboxController {

    private final ConversationInboxService conversationInboxService;

    public ConversationInboxController(ConversationInboxService conversationInboxService) {
        this.conversationInboxService = conversationInboxService;
    }

    @GetMapping
    @SuppressWarnings("unchecked")
    public ResponseEntity<List<Map<String, Object>>> listSessions(
            @RequestParam(name = "page_id", required = false) String pageId,
            @RequestParam(name = "filter", required = false, defaultValue = "ALL") String filter,
            @RequestParam(name = "search", required = false, defaultValue = "") String search) {
        UUID pageUuid = parseUuidOrNull(pageId);
        Map<String, Object> res = conversationInboxService.listSessions(pageUuid, filter, search);
        Object sessionsObj = res != null ? res.get("sessions") : null;
        if (sessionsObj instanceof List<?> list) {
            return ResponseEntity.ok((List<Map<String, Object>>) list);
        }
        return ResponseEntity.ok(List.of());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getSessionDetail(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(conversationInboxService.getSessionDetail(id));
    }

    @PostMapping("/new")
    public ResponseEntity<Map<String, Object>> createNewSession(@RequestBody Map<String, Object> body) {
        String pageId = body.get("page_id") != null ? String.valueOf(body.get("page_id")) : null;
        String customerName = body.get("customer_name") != null ? String.valueOf(body.get("customer_name")) : null;
        String firstMessage = body.get("message") != null ? String.valueOf(body.get("message")) : null;
        return ResponseEntity.ok(conversationInboxService.createNewCustomerSession(
                parseUuidOrNull(pageId),
                customerName,
                firstMessage
        ));
    }

    @PostMapping("/{id}/customer-turn")
    public ResponseEntity<Map<String, Object>> sendCustomerTurn(
            @PathVariable("id") UUID id,
            @RequestBody Map<String, Object> body) {
        String message = body.get("message") != null ? String.valueOf(body.get("message")) : "";
        return ResponseEntity.ok(conversationInboxService.sendCustomerTurnInSession(id, message));
    }

    @PostMapping("/{id}/sale-reply")
    public ResponseEntity<Map<String, Object>> sendHumanSaleReply(
            @PathVariable("id") UUID id,
            @RequestBody Map<String, Object> body) {
        String message = body.get("message") != null ? String.valueOf(body.get("message")) : "";
        boolean markConverted = Boolean.TRUE.equals(body.get("mark_converted"))
                || "true".equalsIgnoreCase(String.valueOf(body.get("mark_converted")));
        return ResponseEntity.ok(conversationInboxService.sendHumanSaleReply(id, message, markConverted));
    }

    @PostMapping("/{id}/control-mode")
    public ResponseEntity<Map<String, Object>> updateControlMode(
            @PathVariable("id") UUID id,
            @RequestBody Map<String, Object> body) {
        String status = body.get("status") != null ? String.valueOf(body.get("status")) : null;
        String controlMode = body.get("control_mode") != null ? String.valueOf(body.get("control_mode")) : null;
        String effectiveMode = ("CONVERTED".equalsIgnoreCase(status)) ? "CONVERTED" : (controlMode != null ? controlMode : status);
        return ResponseEntity.ok(conversationInboxService.updateControlMode(id, effectiveMode));
    }

    @PostMapping("/{id}/extract-rule")
    public ResponseEntity<Map<String, Object>> extractRuleFromSession(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(conversationInboxService.extractRuleFromSession(id));
    }

    private static UUID parseUuidOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
