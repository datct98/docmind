package com.example.docmind.controller;

import com.example.docmind.dto.ChatRequest;
import com.example.docmind.dto.ChatResponse;
import com.example.docmind.dto.FeedbackRequest;
import com.example.docmind.dto.FeedbackResponse;
import com.example.docmind.dto.LearnedRuleDto;
import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.dto.bot.FanpageBotResponse;
import com.example.docmind.service.ChatService;
import com.example.docmind.service.MetaLearningService;
import com.example.docmind.service.bot.FanpageBotService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import com.example.docmind.service.security.PromptSecurityGuard;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Controller xử lý luồng phản hồi sửa sai tường minh (Explicit Feedback Loop - F9).
 * POST /api/feedback: tiếp nhận lỗi sai, trích xuất quy tắc, lưu DB và sinh lại câu trả lời.
 */
@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    private final MetaLearningService metaLearningService;
    private final ChatService chatService;
    private final FanpageBotService fanpageBotService;

    public FeedbackController(MetaLearningService metaLearningService,
                              ChatService chatService,
                              @Autowired(required = false) FanpageBotService fanpageBotService) {
        this.metaLearningService = metaLearningService;
        this.chatService = chatService;
        this.fanpageBotService = fanpageBotService;
    }

    @PostMapping
    public ResponseEntity<FeedbackResponse> submitFeedback(@Valid @RequestBody FeedbackRequest request) {
        if (request != null && (
                PromptSecurityGuard.isPoisonedRuleAttempt(request.userCorrection())
                || PromptSecurityGuard.isPoisonedRuleAttempt(request.question()))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Phản hồi bị từ chối do vi phạm tiêu chuẩn an toàn / cố ý đầu độc quy tắc."
            );
        }

        // 0. Selective Blame Attribution & Auto-Quarantine Circuit Breaker nếu có danh sách rule đã áp dụng ở câu trả lời sai
        if (request.appliedRuleIds() != null && !request.appliedRuleIds().isEmpty()) {
            metaLearningService.recordNegativeFeedbackAndAutoQuarantine(
                    request.appliedRuleIds(),
                    request.question(),
                    request.faultyAnswer(),
                    request.userCorrection()
            );
        }

        // 1. Trích xuất và lưu trữ quy tắc rút kinh nghiệm
        LearnedRuleDto learnedRule;
        if (request.pageId() != null && !request.pageId().isBlank()) {
            learnedRule = metaLearningService.learnFromFeedback(
                    request.question(),
                    request.faultyAnswer(),
                    request.userCorrection(),
                    request.conversationId(),
                    request.retrievedContext(),
                    request.pageId()
            );
        } else {
            learnedRule = metaLearningService.learnFromFeedback(
                    request.question(),
                    request.faultyAnswer(),
                    request.userCorrection(),
                    request.conversationId(),
                    request.retrievedContext()
            );
        }

        // 2. Tái sinh câu trả lời với quy tắc mới được kích hoạt
        boolean isFanpage = request.source() != null
                && ("FANPAGE_BOT".equalsIgnoreCase(request.source().strip())
                    || "fanpage".equalsIgnoreCase(request.source().strip()));

        if (isFanpage && fanpageBotService != null) {
            List<UUID> docUuids = null;
            if (request.documentIds() != null && !request.documentIds().isEmpty()) {
                docUuids = request.documentIds().stream()
                        .filter(id -> id != null && !id.isBlank())
                        .map(id -> {
                            try {
                                return UUID.fromString(id.strip());
                            } catch (Exception e) {
                                return null;
                            }
                        })
                        .filter(java.util.Objects::nonNull)
                        .toList();
            }
            FanpageBotRequest botReq = new FanpageBotRequest(
                    request.pageId(),
                    null,
                    null,
                    request.question(),
                    null,
                    request.history(),
                    docUuids,
                    5
            );
            FanpageBotResponse botResp = fanpageBotService.handleCustomerMessage(botReq);
            List<com.example.docmind.dto.LearnedRuleDto> applied = (botResp.appliedRules() != null && !botResp.appliedRules().isEmpty())
                    ? botResp.appliedRules()
                    : List.of(learnedRule);
            return ResponseEntity.ok(new FeedbackResponse(
                    learnedRule,
                    botResp.replyText(),
                    applied,
                    List.of(),
                    botResp.customerInsight(),
                    botResp.activeSkills()
            ));
        }

        if (request.documentIds() != null && !request.documentIds().isEmpty()) {
            ChatRequest chatReq = new ChatRequest(
                    request.documentIds(),
                    request.question(),
                    5,
                    request.question(),
                    request.faultyAnswer(),
                    request.conversationId(),
                    request.pageId()
            );
            ChatResponse chatResp = chatService.chat(chatReq);
            return ResponseEntity.ok(new FeedbackResponse(
                    learnedRule,
                    chatResp.answer(),
                    chatResp.appliedRules(),
                    chatResp.citations()
            ));
        }

        // Trường hợp không cung cấp document_ids: trả về xác nhận quy tắc
        return ResponseEntity.ok(new FeedbackResponse(
                learnedRule,
                "Hệ thống đã đúc kết quy tắc: " + learnedRule.generalizedRule(),
                List.of(learnedRule),
                List.of()
        ));
    }
}
