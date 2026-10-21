package com.example.docmind.service.bot;

import com.example.docmind.config.ChatProperties;
import com.example.docmind.dto.LearnedRuleDto;
import com.example.docmind.dto.bot.AgentSkillDto;
import com.example.docmind.dto.bot.CustomerInsightDto;
import com.example.docmind.dto.bot.FanpageBotRequest;
import com.example.docmind.dto.bot.FanpageBotResponse;
import com.example.docmind.entity.DocumentEntity;
import com.example.docmind.repository.DocumentRepository;
import com.example.docmind.repository.KnowledgePageRepository;
import com.example.docmind.service.llm.LlmClient;
import com.example.docmind.service.retrieval.PromptBuilder;
import com.example.docmind.service.retrieval.ReciprocalRankFusion;
import com.docmind.guardrail.GuardrailContext;
import com.docmind.guardrail.GuardrailPipeline;
import com.docmind.guardrail.GuardrailResult;
import com.example.docmind.service.retrieval.RetrievalService;
import com.example.docmind.service.security.PromptSecurityGuard;
import com.example.docmind.service.harness.HarnessOptimizerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Engine xử lý tin nhắn khách hàng cho BOT TRỰC FANPAGE FACEBOOK.
 *
 * <p>Thiết kế lấy cảm hứng từ kiến trúc Pipeline của AstrBot kết hợp Grounded Sales RAG:
 * <ol>
 *   <li>Gộp chùm tin nhắn ngắn liên tiếp của khách (Burst Message Debounce).</li>
 *   <li>Chốt chặn bảo mật chống Prompt Injection & Phát hiện Tình huống Khó Tầng 1 (Pre-LLM Handoff Gate).</li>
 *   <li>Nén ngữ cảnh đa lượt (Contextual Query Rewriting) để hiểu các câu hỏi cụt ("Giá sao em?", "Làm mấy buổi?").</li>
 *   <li>Truy xuất Kho tri thức cô lập theo {@code page_id} + Đọc {@code AI Playbook} (Khóa chiều ghi để khách không dạy sai Bot).</li>
 *   <li>Đọc vị Tâm lý & Insight Khách hàng ({@link CustomerInsightEngine}) + Chọn Top 3 Kỹ năng Agent ({@link AgentSkillService}).</li>
 *   <li>Điều tiết Nhịp độ tư vấn 3 giai đoạn (Không vội vã): {@code DISCOVERY -> VALUE_CONSULTATION -> SOFT_BOOKING_CLOSE}.</li>
 *   <li>Hậu xử lý Tầng 2: Bóc tách cờ {@code [[HANDOFF:...]]}, xóa sạch trích dẫn {@code [1]}, kiểm tra chống bịa số liệu,
 *       tách thành các bong bóng chat ngắn ({@code reply_bubbles}) và trích xuất SĐT / lịch hẹn ({@code extracted_lead}).</li>
 * </ol>
 */
@Service
public class FanpageBotService {

    private static final Logger log = LoggerFactory.getLogger(FanpageBotService.class);

    /** Mẫu phát hiện khách hàng khiếu nại, nóng giận, báo biến chứng y khoa hoặc đòi hoàn tiền gấp. */
    private static final Pattern COMPLAINT_OR_DISTRESS_PATTERN = Pattern.compile(
            "(?iU)\\b(kích\\s+ứng|dị\\s+ứng|bỏng\\s+rát|đỏ\\s+rát|sưng\\s+tấy|nổi\\s+mẩn|nhiễm\\s+trùng|"
            + "khiếu\\s+nại|bắt\\s+đền|lừa\\s+đảo|lừa\\s+gạt|tệ\\s+quá|quá\\s+tệ|bức\\s+xúc|bóc\\s+phốt|"
            + "đòi\\s+lại\\s+tiền|hoàn\\s+tiền\\s+ngay|kiện|báo\\s+công\\s+an|làm\\s+hỏng\\s+da)\\b"
    );

    /** Mẫu phát hiện khách hàng chủ động yêu cầu gặp nhân viên thật / quản lý / bác sĩ. */
    private static final Pattern EXPLICIT_HUMAN_REQUEST_PATTERN = Pattern.compile(
            "(?iU)(gặp\\s+nhân\\s+viên|gặp\\s+người\\s+thật|gặp\\s+quản\\s+lý|cho\\s+gặp\\s+bác\\s+sĩ|"
            + "nhân\\s+viên\\s+đâu|tư\\s+vấn\\s+viên\\s+đâu|bot\\s+à|máy\\s+trả\\s+lời\\s+à|ai\\s+đang\\s+chat|"
            + "gọi\\s+điện\\s+cho\\s+chị|gọi\\s+lại\\s+cho\\s+anh|nói\\s+chuyện\\s+với\\s+người|"
            + "(?:tư\\s+vấn|nhắn|gọi|liên\\s+hệ|add|kết\\s+bạn|inbox|gửi)?\\s*(?:qua|trên|vào)?\\s*zalo\\b|"
            + "qua\\s+zalo)"
    );

    /** Mẫu phát hiện thương lượng đặc thù vượt khung chính sách chuẩn (mua sỉ, đại lý, đòi giảm sâu). */
    private static final Pattern COMPLEX_NEGOTIATION_PATTERN = Pattern.compile(
            "(?iU)(làm\\s+đại\\s+lý|nhượng\\s+quyền|hợp\\s+tác\\s+kinh\\s+doanh|mua\\s+sỉ|xuất\\s+hóa\\s+đơn\\s+đỏ\\s+công\\s+ty|"
            + "giảm\\s+(30|40|50|60|70|80|90)\\s*%|bớt\\s+thêm\\s+đi|mặc\\s+cả)"
    );

    /** Mẫu câu hỏi cụt cần nối ngữ cảnh từ lịch sử chat. */
    private static final Pattern SHORT_FOLLOWUP_PATTERN = Pattern.compile(
            "(?iU)^(giá|rổ|chi\\s+phí|bao\\s+nhiêu|mấy\\s+buổi|bao\\s+lâu|có\\s+tặng|có\\s+đau|có\\s+bảo\\s+hành|còn\\s+gói|thế\\s+còn|vậy\\s+còn).*"
    );

    /** Mẫu bắt nhãn [[HANDOFF: lý do]] do LLM sinh ra. */
    private static final Pattern LLM_HANDOFF_TAG_PATTERN = Pattern.compile(
            "\\[\\[\\s*HANDOFF\\s*:\\s*([^\\]]+)\\]\\]", Pattern.CASE_INSENSITIVE
    );

    /** Mẫu phát hiện câu từ chối máy móc khi LLM không tìm thấy thông tin. */
    private static final Pattern FALLBACK_REFUSAL_PATTERN = Pattern.compile(
            "(?iU)(không\\s+tìm\\s+thấy\\s+thông\\s+tin\\s+này\\s+trong\\s+tài\\s+liệu|"
            + "trong\\s+tài\\s+liệu\\s+không\\s+đề\\s+cập|tài\\s+liệu\\s+không\\s+có\\s+thông\\s+tin|"
            + "ngoài\\s+phạm\\s+vi\\s+tài\\s+liệu)"
    );

    /** Mẫu bắt Số điện thoại Việt Nam trong tin nhắn của khách hàng (hỗ trợ 9-11 chữ số, kể cả khách bấm dư/thiếu 1 số). */
    private static final Pattern VN_PHONE_PATTERN = Pattern.compile(
            "(?:\\+84|84|0)(?:3[2-9]|5[25689]|7[06-9]|8[1-9]|9[0-46-9])(?:[.\\-\\s]?\\d){6,8}\\b"
    );

    /**
     * Che giấu số điện thoại khách hàng trong console/application log (VD: 0968***644).
     */
    public static String maskPhoneNumber(String rawPhone) {
        if (rawPhone == null || rawPhone.isBlank()) {
            return rawPhone;
        }
        String clean = rawPhone.trim();
        if (clean.length() >= 9) {
            int prefix = clean.startsWith("+84") ? 6 : 4;
            int suffix = 3;
            if (clean.length() > prefix + suffix) {
                return clean.substring(0, prefix) + "***" + clean.substring(clean.length() - suffix);
            }
        }
        if (clean.length() >= 6) {
            return clean.substring(0, 3) + "***" + clean.substring(clean.length() - 2);
        }
        return "***";
    }

    public static String maskPiiInText(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        return VN_PHONE_PATTERN.matcher(text).replaceAll(mr -> maskPhoneNumber(mr.group()));
    }

    /** Mẫu bắt khung giờ / ngày hẹn trong tin nhắn của khách hàng. */
    private static final Pattern BOOKING_TIME_PATTERN = Pattern.compile(
            "(?iU)(?<!\\p{L})((?:sáng|trưa|chiều|tối)\\s+(?:nay|mai|mốt|thứ\\s+[2-7]|chủ\\s+nhật|cuối\\s+tuần)|"
            + "(?:tầm|buổi|khoảng)\\s+(?:sáng|trưa|chiều|tối)(?:\\s+(?:nay|mai|mốt))?|"
            + "thứ\\s+[2-7]\\s+tuần\\s+này|cuối\\s+tuần\\s+này|"
            + "\\d{1,2}\\s*(?:h|giờ|:)\\s*\\d{0,2}(?:\\s*(?:sáng|trưa|chiều|tối))?(?:\\s*(?:hôm\\s+nay|nay|ngày\\s+mai|mai|mốt))?)(?!\\p{L})"
    );

    /** Mẫu bắt tín hiệu muốn đặt lịch / chốt đơn / đồng ý trải nghiệm thử của khách hàng. */
    private static final Pattern BUYING_OR_BOOKING_SIGNAL_PATTERN = Pattern.compile(
            "(?iU)\\b(đặt\\s+lịch|giữ\\s+lịch|giữ\\s+chỗ|giữ\\s+suất|đăng\\s+ký|chốt\\s+gói|chốt\\s+lịch|chị\\s+qua|anh\\s+qua|"
            + "thử\\s+xem(?:\\s+sao|\\s+thế\\s+nào|\\s+nhé|\\s+nha)?|sẽ\\s+thử|mình\\s+thử|t\\s+thử|qua\\s+thử|khám\\s+thử|soi\\s+da\\s+thử|trải\\s+nghiệm\\s+thử|làm\\s+thử|qua\\s+soi\\s+da|qua\\s+khám|"
            + "tầm\\s+trưa|buổi\\s+trưa|tới\\s+giờ\\s+tôi\\s+tự\\s+tới|cho\\s+chị\\s+làm|cho\\s+anh\\s+làm|sđt|số\\s+điện\\s+thoại|cọc\\s+trước|chuyển\\s+khoản)\\b"
    );

    /** Mẫu bắt các câu hỏi trực diện về dịch vụ không tồn tại hoặc thông tin vận hành cụ thể (địa chỉ, STK). */
    private static final Pattern SPECIFIC_FACT_QUERY_PATTERN = Pattern.compile(
            "(?iU)\\b(tiêm\\s+filler|tiêm\\s+botox|cắt\\s+mí|nâng\\s+mũi|hút\\s+mỡ|xăm\\s+môi|phun\\s+môi|triệt\\s+lông|"
            + "địa\\s+chỉ\\s+ở\\s+đâu|chi\\s+nhánh\\s+nào|số\\s+tài\\s+khoản|mấy\\s+giờ\\s+đóng\\s+cửa)\\b"
    );

    private final RetrievalService retrievalService;
    private final LlmClient llmClient;
    private final ChatProperties chatProps;
    private DocumentRepository documentRepository;
    private KnowledgePageRepository knowledgePageRepository;
    private CustomerInsightEngine customerInsightEngine;
    private AgentSkillService agentSkillService;
    private com.example.docmind.service.MetaLearningService metaLearningService;
    private final GuardrailPipeline guardrailPipeline;
    private final HarnessOptimizerService harnessOptimizerService;
    private FastApiAgentService fastApiAgentService;

    @Autowired
    public FanpageBotService(RetrievalService retrievalService,
                             LlmClient llmClient,
                             ChatProperties chatProps,
                             @Autowired(required = false) DocumentRepository documentRepository,
                             @Autowired(required = false) KnowledgePageRepository knowledgePageRepository,
                             @Autowired(required = false) CustomerInsightEngine customerInsightEngine,
                             @Autowired(required = false) AgentSkillService agentSkillService,
                             @Autowired(required = false) com.example.docmind.service.MetaLearningService metaLearningService,
                             @Autowired(required = false) GuardrailPipeline guardrailPipeline,
                             @Autowired(required = false) HarnessOptimizerService harnessOptimizerService,
                             @Autowired(required = false) FastApiAgentService fastApiAgentService) {
        this.retrievalService = retrievalService;
        this.llmClient = llmClient;
        this.chatProps = chatProps;
        this.documentRepository = documentRepository;
        this.knowledgePageRepository = knowledgePageRepository;
        this.customerInsightEngine = customerInsightEngine != null ? customerInsightEngine : new CustomerInsightEngine();
        this.agentSkillService = agentSkillService;
        this.metaLearningService = metaLearningService;
        this.guardrailPipeline = guardrailPipeline != null ? guardrailPipeline : new GuardrailPipeline();
        this.harnessOptimizerService = harnessOptimizerService;
        this.fastApiAgentService = fastApiAgentService != null
                ? fastApiAgentService
                : new FastApiAgentService(null, new com.example.docmind.config.DocServiceProperties());
    }

    public FanpageBotService(RetrievalService retrievalService,
                             LlmClient llmClient,
                             ChatProperties chatProps,
                             DocumentRepository documentRepository,
                             KnowledgePageRepository knowledgePageRepository,
                             CustomerInsightEngine customerInsightEngine,
                             AgentSkillService agentSkillService,
                             com.example.docmind.service.MetaLearningService metaLearningService,
                             GuardrailPipeline guardrailPipeline,
                             HarnessOptimizerService harnessOptimizerService) {
        this(retrievalService, llmClient, chatProps, documentRepository, knowledgePageRepository, customerInsightEngine, agentSkillService, metaLearningService, guardrailPipeline, harnessOptimizerService, null);
    }

    public FanpageBotService(RetrievalService retrievalService,
                             LlmClient llmClient,
                             ChatProperties chatProps,
                             DocumentRepository documentRepository,
                             KnowledgePageRepository knowledgePageRepository,
                             CustomerInsightEngine customerInsightEngine,
                             AgentSkillService agentSkillService,
                             com.example.docmind.service.MetaLearningService metaLearningService,
                             GuardrailPipeline guardrailPipeline) {
        this(retrievalService, llmClient, chatProps, documentRepository, knowledgePageRepository, customerInsightEngine, agentSkillService, metaLearningService, guardrailPipeline, null);
    }

    public FanpageBotService(RetrievalService retrievalService,
                             LlmClient llmClient,
                             ChatProperties chatProps,
                             DocumentRepository documentRepository,
                             KnowledgePageRepository knowledgePageRepository,
                             CustomerInsightEngine customerInsightEngine,
                             AgentSkillService agentSkillService,
                             com.example.docmind.service.MetaLearningService metaLearningService) {
        this(retrievalService, llmClient, chatProps, documentRepository, knowledgePageRepository, customerInsightEngine, agentSkillService, metaLearningService, null, null);
    }

    public GuardrailPipeline getGuardrailPipeline() {
        return guardrailPipeline;
    }

    public HarnessOptimizerService getHarnessOptimizerService() {
        return harnessOptimizerService;
    }

    public FanpageBotService(RetrievalService retrievalService,
                             LlmClient llmClient,
                             ChatProperties chatProps,
                             DocumentRepository documentRepository,
                             KnowledgePageRepository knowledgePageRepository,
                             CustomerInsightEngine customerInsightEngine,
                             AgentSkillService agentSkillService) {
        this(retrievalService, llmClient, chatProps, documentRepository, knowledgePageRepository, customerInsightEngine, agentSkillService, null);
    }

    public FanpageBotService(RetrievalService retrievalService,
                             LlmClient llmClient,
                             ChatProperties chatProps,
                             DocumentRepository documentRepository,
                             KnowledgePageRepository knowledgePageRepository) {
        this(retrievalService, llmClient, chatProps, documentRepository, knowledgePageRepository, new CustomerInsightEngine(), null, null);
    }

    public FanpageBotService(RetrievalService retrievalService,
                             LlmClient llmClient,
                             ChatProperties chatProps) {
        this(retrievalService, llmClient, chatProps, null, null, new CustomerInsightEngine(), null, null);
    }

    public FanpageBotResponse handleCustomerMessage(FanpageBotRequest request) {
        boolean isReengageAction = request != null && (
                "REENGAGE".equalsIgnoreCase(request.action())
                || (request.idleSeconds() != null && request.idleSeconds() >= 300)
        );
        String mergedMessage = mergeBurstMessages(request);
        if (mergedMessage.isBlank()) {
            if (isReengageAction) {
                mergedMessage = "Khách hàng tạm im lặng, bot chủ động gửi tin nhắn re-engage kéo lại tương tác.";
            } else {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tin nhắn của khách hàng không được để trống.");
            }
        }

        UUID pageId = parsePageId(request.pageId());
        List<UUID> documentIds = request.documentIds();
        if (pageId == null && documentIds != null && !documentIds.isEmpty() && documentRepository != null) {
            for (UUID docId : documentIds) {
                UUID inferred = documentRepository.findById(docId).map(DocumentEntity::getPageId).orElse(null);
                if (inferred != null) {
                    pageId = inferred;
                    break;
                }
            }
        }
        PromptBuilder.PagePersona pagePersona = resolvePagePersona(pageId);
        String honorific = FanpageBotPromptBuilder.resolveCustomerHonorific(
                mergedMessage, request.history(), request.customerName(), pagePersona);
        FanpageBotPromptBuilder.ConversationBookingState bookingState =
                FanpageBotPromptBuilder.analyzeConversationBookingState(mergedMessage, request.history());
        FanpageBotResponse.ExtractedLead extractedLead = extractLeadSignal(mergedMessage, request.history());
        FanpageBotPromptBuilder.SalesStage initialStage = determineSalesStage(mergedMessage, request.history(), extractedLead);
        CustomerInsightDto customerInsight = customerInsightEngine.analyzeInsight(
                mergedMessage,
                request.history(),
                honorific,
                initialStage,
                extractedLead,
                request.previousTemperature(),
                request.replyDelaySeconds(),
                request.idleSeconds()
        );
        List<AgentSkillDto> candidateSkills = agentSkillService != null
                ? agentSkillService.getActiveSkillsForPage(pageId)
                : CustomerInsightEngine.builtInDefaultSkills();
        List<AgentSkillDto> activeSkills = customerInsightEngine.selectActiveSkills(
                customerInsight, mergedMessage, request.history(), initialStage, candidateSkills);

        // 1. KIỂM TRA BẢO MẬT CHỐNG PROMPT INJECTION TRỰC TIẾP
        if (PromptSecurityGuard.isDirectJailbreakAttempt(mergedMessage)) {
            log.warn("stage=fanpage-bot-security blocked=direct_jailbreak page_id={} msg={}", pageId, maskPiiInText(mergedMessage));
            String safeReply = "Dạ em chỉ có thể hỗ trợ tư vấn thông tin dịch vụ và chương trình ưu đãi chính thức của Page thôi ạ. "
                    + "Để em kết nối ngay với Quản lý phụ trách hỗ trợ trực tiếp cho " + honorific + " nhé ạ!";
            return buildHandoffResponse(
                    safeReply,
                    "SECURITY_BLOCK",
                    "Phát hiện tin nhắn có dấu hiệu can thiệp chỉ thị hệ thống (Prompt Injection / Jailbreak).",
                    0.0,
                    extractedLead,
                    customerInsight,
                    List.of()
            );
        }

        // 2. PHÁT HIỆN TÌNH HUỐNG KHÓ TẦNG 1 (PRE-LLM DETERMINISTIC HANDOFF)
        if (COMPLAINT_OR_DISTRESS_PATTERN.matcher(mergedMessage).find()) {
            if (agentSkillService != null && activeSkills != null && !activeSkills.isEmpty()) {
                agentSkillService.recordSkillsApplied(activeSkills);
            }
            log.info("stage=fanpage-bot-handoff trigger=COMPLAINT_OR_DISTRESS page_id={}", pageId);
            String empathyReply = "Dạ em rất xin lỗi vì sự bất tiện và lo lắng của " + honorific + " ạ! "
                    + "Với tình trạng này, em xin phép chuyển thông tin khẩn cấp tới Quản lý và Bác sĩ/Chuyên viên trưởng của bên em để kiểm tra và liên hệ hỗ trợ trực tiếp cho " + honorific + " ngay lập tức ạ.\n\n"
                    + honorific + " cho em xin Số điện thoại hoặc giữ máy để chuyên viên bên em gọi hỗ trợ " + honorific + " chu đáo nhất ngay bây giờ nhé ạ!";
            return buildHandoffResponse(
                    empathyReply,
                    "COMPLAINT_OR_DISTRESS",
                    "Khách hàng phản ánh dấu hiệu kích ứng / khiếu nại chất lượng / yêu cầu xử lý khẩn cấp — cần Quản lý hoặc Bác sĩ tiếp nhận ngay.",
                    1.0,
                    extractedLead,
                    customerInsight,
                    activeSkills
            );
        }

        if (EXPLICIT_HUMAN_REQUEST_PATTERN.matcher(mergedMessage).find()) {
            if (agentSkillService != null && activeSkills != null && !activeSkills.isEmpty()) {
                agentSkillService.recordSkillsApplied(activeSkills);
            }
            log.info("stage=fanpage-bot-handoff trigger=EXPLICIT_HUMAN_REQUEST page_id={}", pageId);
            String humanReply = "Dạ vâng ạ! Em đã kết nối cuộc trò chuyện tới Chuyên viên tư vấn trực tiếp của bên em rồi ạ.\n\n"
                    + "Chuyên viên bên em sẽ vào nhắn tin hoặc gọi hỗ trợ " + honorific + " ngay trong ít phút, " + honorific + " đợi bên em một chút nhé ạ!";
            return buildHandoffResponse(
                    humanReply,
                    "EXPLICIT_HUMAN_REQUEST",
                    "Khách hàng chủ động yêu cầu gặp nhân viên thật / quản lý / bác sĩ tư vấn trực tiếp.",
                    1.0,
                    extractedLead,
                    customerInsight,
                    activeSkills
            );
        }

        if (COMPLEX_NEGOTIATION_PATTERN.matcher(mergedMessage).find()) {
            if (agentSkillService != null && activeSkills != null && !activeSkills.isEmpty()) {
                agentSkillService.recordSkillsApplied(activeSkills);
            }
            log.info("stage=fanpage-bot-handoff trigger=COMPLEX_NEGOTIATION page_id={}", pageId);
            String negReply = "Dạ với nhu cầu hợp tác / chính sách ưu đãi đặc biệt này, em xin phép chuyển trực tiếp cho Quản lý kinh doanh bên em để trao đổi mức quyền lợi tốt nhất cho mình nhé ạ!\n\n"
                    + honorific + " có thể để lại Số điện thoại để Quản lý bên em liên hệ trao đổi trực tiếp ngay hôm nay ạ?";
            return buildHandoffResponse(
                    negReply,
                    "COMPLEX_NEGOTIATION",
                    "Khách hàng đề xuất hợp tác đại lý / mua số lượng lớn / thương lượng mức chiết khấu ngoài khung tiêu chuẩn.",
                    0.95,
                    extractedLead,
                    customerInsight,
                    activeSkills
            );
        }

        // 3. XÁC ĐỊNH PHẠM VI TÀI LIỆU CỦA FANPAGE
        if ((documentIds == null || documentIds.isEmpty()) && documentRepository != null) {
            if (pageId != null) {
                documentIds = documentRepository.findByPageIdOrderByCreatedAtDesc(pageId).stream()
                        .filter(d -> "READY".equalsIgnoreCase(d.getStatus()))
                        .map(DocumentEntity::getId)
                        .toList();
            } else {
                List<DocumentEntity> allReady = documentRepository.findAllByOrderByCreatedAtDesc().stream()
                        .filter(d -> "READY".equalsIgnoreCase(d.getStatus()))
                        .toList();
                documentIds = allReady.stream().map(DocumentEntity::getId).toList();
                if (pagePersona == null) {
                    pageId = allReady.stream()
                            .map(DocumentEntity::getPageId)
                            .filter(java.util.Objects::nonNull)
                            .findFirst()
                            .orElse(null);
                    pagePersona = resolvePagePersona(pageId);
                    honorific = FanpageBotPromptBuilder.resolveCustomerHonorific(
                            mergedMessage, request.history(), request.customerName(), pagePersona);
                    customerInsight = customerInsightEngine.analyzeInsight(
                            mergedMessage,
                            request.history(),
                            honorific,
                            initialStage,
                            extractedLead,
                            request.previousTemperature(),
                            request.replyDelaySeconds(),
                            request.idleSeconds()
                    );
                    candidateSkills = agentSkillService != null
                            ? agentSkillService.getActiveSkillsForPage(pageId)
                            : CustomerInsightEngine.builtInDefaultSkills();
                    activeSkills = customerInsightEngine.selectActiveSkills(
                            customerInsight, mergedMessage, request.history(), initialStage, candidateSkills);
                }
            }
        }
        if (agentSkillService != null && activeSkills != null && !activeSkills.isEmpty()) {
            agentSkillService.recordSkillsApplied(activeSkills);
        }

        // Củng cố ngầm (Implicit Conversion Reinforcement): Khi khách để lại SĐT / tín hiệu đặt lịch sau hội thoại tư vấn
        if (extractedLead != null && extractedLead.hasAnyLead()
                && request.history() != null && !request.history().isEmpty()) {
            if (metaLearningService != null) {
                metaLearningService.recordConversionSuccess(pageId, request.history(), mergedMessage);
            } else if (agentSkillService != null) {
                StringBuilder convoBuilder = new StringBuilder();
                for (FanpageBotRequest.ConversationTurn t : request.history()) {
                    if (t != null && t.content() != null) {
                        convoBuilder.append(t.content()).append(' ');
                    }
                }
                convoBuilder.append(mergedMessage);
                agentSkillService.recordConversionSuccess(pageId, convoBuilder.toString());
            }
        }

        if (documentIds == null || documentIds.isEmpty()) {
            log.info("stage=fanpage-bot-handoff trigger=NO_READY_DOCUMENTS page_id={}", pageId);
            String noDocReply = "Dạ em chào " + honorific + " ạ! Hiện tại em xin phép kết nối ngay với Chuyên viên tư vấn của Page để kiểm tra thông tin chi tiết và báo lại cho " + honorific + " chuẩn xác nhất ngay bây giờ nhé ạ!";
            return buildHandoffResponse(
                    noDocReply,
                    "UNGROUNDED_OR_UNKNOWN",
                    "Page hiện chưa có tài liệu tri thức ở trạng thái READY để tra cứu tự động.",
                    0.0,
                    extractedLead,
                    customerInsight,
                    activeSkills
            );
        }

        // 4. NÉN NGỮ CẢNH ĐA LƯỢT QUA LANGGRAPH STATE ENGINE & HYBRID RETRIEVAL
        // Lưu ý quan trọng: Chỉ ĐỌC AI Playbook (extraRules = List.of()), KHÔNG BAO GIỜ chạy MetaLearningService từ tin nhắn khách hàng!
        long retrievalStartNs = System.nanoTime();
        List<java.util.Map<String, String>> historyMaps = new ArrayList<>();
        if (request.history() != null) {
            for (FanpageBotRequest.ConversationTurn t : request.history()) {
                if (t != null && t.role() != null && t.content() != null) {
                    historyMaps.add(java.util.Map.of("role", t.role(), "content", t.content()));
                }
            }
        }
        com.example.docmind.client.FastApiAgentClient.ConsultResponse consultResult = fastApiAgentService != null
                ? fastApiAgentService.consult(
                        request.senderId(),
                        pageId != null ? pageId.toString() : null,
                        mergedMessage,
                        historyMaps,
                        List.of(),
                        honorific)
                : null;
        if (consultResult != null && "Anh".equalsIgnoreCase(consultResult.honorific())) {
            honorific = "Anh";
        }
        String baseContextualQuery = buildContextualSearchQuery(mergedMessage, request.history());
        String contextualQuery = enrichContextualQueryWithLangGraph(baseContextualQuery, consultResult);
        int topK = request.topK() != null ? request.topK() : Math.max(6, chatProps.getTopK());
        RetrievalService.RetrievalResult retrievalResult = retrievalService.retrieveWithRules(
                documentIds,
                contextualQuery,
                topK,
                List.of(),
                pageId
        );
        long retrievalMs = (System.nanoTime() - retrievalStartNs) / 1_000_000L;

        List<ReciprocalRankFusion.FusedChunk> rawFused = retrievalResult != null && retrievalResult.chunks() != null
                ? retrievalResult.chunks()
                : List.of();
        List<ReciprocalRankFusion.FusedChunk> fused = scopeChunksToTopicFamily(rawFused, consultResult, mergedMessage);
        List<LearnedRuleDto> appliedRules = retrievalResult != null && retrievalResult.appliedRules() != null
                ? retrievalResult.appliedRules()
                : List.of();

        boolean leadReadyHandoff = bookingState.hasPhoneInCurrentTurn()
                && extractedLead.phoneNumber() != null && !extractedLead.phoneNumber().isBlank();
        boolean purePhoneLeadFastPath = leadReadyHandoff && !asksExplicitPriceInMessage(mergedMessage);

        // 5. KIỂM TRA ĐỘ TỰ TIN DỮ LIỆU (RETRIEVAL CONFIDENCE GATE)
        if (fused.isEmpty() && !purePhoneLeadFastPath) {
            log.info("stage=fanpage-bot-handoff trigger=EMPTY_RETRIEVAL page_id={}", pageId);
            String unknownReply = "Dạ về thông tin này để đảm bảo chuẩn xác nhất cho trường hợp của " + honorific + ", em xin phép nhờ Chuyên viên phụ trách của bên em kiểm tra và nhắn phản hồi lại " + honorific + " ngay trong ít phút nhé ạ!";
            return buildHandoffResponse(
                    unknownReply,
                    "UNGROUNDED_OR_UNKNOWN",
                    "Không tìm thấy đoạn tài liệu nào khớp với câu hỏi của khách hàng trong kho tri thức của Page.",
                    0.15,
                    extractedLead,
                    customerInsight,
                    activeSkills
            );
        }

        // Kiểm tra nếu khách hỏi đích danh một dịch vụ/thông tin cụ thể không hề xuất hiện trong các chunk của Page
        String ungroundedTopic = fused.isEmpty() ? null : detectUngroundedSpecificTopic(mergedMessage, fused);
        if (ungroundedTopic != null) {
            log.info("stage=fanpage-bot-handoff trigger=UNGROUNDED_TOPIC topic={} page_id={}", ungroundedTopic, pageId);
            String politePivotReply = "Dạ hiện tại bên em chưa có thông tin niêm yết cho yêu cầu về \"" + ungroundedTopic + "\" trong danh mục chuẩn ạ.\n\n"
                    + "Để không làm mất thời gian của " + honorific + ", em xin phép chuyển tin nhắn cho Chuyên viên tư vấn chính bên em kiểm tra xem có liệu trình/giải pháp nào phù hợp nhất với nhu cầu của " + honorific + " và phản hồi " + honorific + " ngay nhé ạ!";
            return buildHandoffResponse(
                    politePivotReply,
                    "UNGROUNDED_OR_UNKNOWN",
                    "Khách hàng hỏi về nội dung/dịch vụ ngoài danh mục tài liệu đã đào tạo: \"" + ungroundedTopic + "\".",
                    0.25,
                    extractedLead,
                    customerInsight,
                    activeSkills
            );
        }

        // 6. XÁC ĐỊNH NHỊP ĐỘ TƯ VẤN (3-STAGE CONSULTATIVE PACING)
        FanpageBotPromptBuilder.SalesStage salesStage = determineSalesStage(mergedMessage, request.history(), extractedLead);
        boolean hasPriorAssistantTurn = request.history() != null && request.history().stream()
                .anyMatch(t -> t != null && "assistant".equalsIgnoreCase(t.role()) && t.content() != null && !t.content().isBlank());
        boolean isFirstTurn = !hasPriorAssistantTurn;

        // Fast-path: Nếu khách gửi SĐT đặt lịch / yêu cầu gọi lại mà không hỏi thêm giá, xác nhận tức thì (0ms LLM)
        if (leadReadyHandoff && !asksExplicitPriceInMessage(mergedMessage)) {
            String fastPhoneReply = ensureCleanPhoneLeadReply("", extractedLead, honorific, appliedRules);
            List<FanpageBotResponse.ReplyBubble> fastBubbles = splitIntoBubbles(fastPhoneReply);
            double fastConfidence = computeConfidenceScore(fused);
            String fastReason = "Khách hàng đã để lại SĐT (" + extractedLead.phoneNumber() + ") / đặt lịch — Chuyển Chuyên viên liên hệ qua phone/Zalo ngay!";
            log.info("[LLM] FanpageBotService fast-path phone-lead response retrievalMs={} llmMs=0 status=ok page_id={} honorific={} sales_stage={} handoff=true phone={} bubbles={}",
                    retrievalMs, pageId, honorific, salesStage.code(), maskPhoneNumber(extractedLead.phoneNumber()), fastBubbles.size());
            FanpageBotResponse fastResp = new FanpageBotResponse(
                    fastPhoneReply,
                    fastBubbles,
                    salesStage.code(),
                    fastConfidence,
                    true,
                    "BOOKING_LEAD_CAPTURED",
                    fastReason,
                    extractedLead,
                    appliedRules,
                    customerInsight,
                    activeSkills
            );
            return applyGuardrails(fastResp, honorific, request.customerName(), isFirstTurn, salesStage.code(), mergedMessage, request.history(), fused);
        }

        // 7. DỰNG PROMPT RIÊNG CỦA BOT FANPAGE (KÈM CUSTOMER INSIGHT, AGENT SKILLS & LANGGRAPH DIRECTIVE) & GỌI LLM
        String basePrompt = FanpageBotPromptBuilder.build(
                mergedMessage,
                request.customerName(),
                request.history(),
                fused,
                appliedRules,
                pagePersona,
                salesStage,
                honorific,
                customerInsight,
                activeSkills
        );
        String prompt = basePrompt;

        long llmStartNs = System.nanoTime();
        log.info("[LLM] FanpageBotService calling engine={} (page_id={}, sales_stage={}, honorific={}, archetype={}, skills={}, retrievalMs={}, promptChars={})",
                llmClient.name(), pageId, salesStage.code(), honorific,
                customerInsight != null ? customerInsight.buyerArchetype() : "N/A",
                activeSkills != null ? activeSkills.size() : 0,
                retrievalMs, prompt.length());
        String rawReply = llmClient.complete(prompt);
        long llmLatencyMs = (System.nanoTime() - llmStartNs) / 1_000_000L;
        boolean isFallback = rawReply != null && rawReply.matches("(?s)^\\[[a-z0-9\\-]+-(?:error|offline|unavailable)\\].*");
        String sanitizedReply = PromptSecurityGuard.sanitizeModelOutput(rawReply);

        // 8. HẬU XỬ LÝ (POST-PROCESSING): BÓC TÁCH [[HANDOFF]], LỌC SẠCH TRÍCH DẪN [1], ĐỒNG NHẤT XƯNG HÔ & LÀM MƯỢT CÂU HỎI KẾT
        PostProcessedBotReply processed = postProcessReply(sanitizedReply, fused, honorific, isFirstTurn, salesStage, request.history(), mergedMessage);

        String finalCleanText = processed.cleanText();
        if (leadReadyHandoff) {
            if (asksExplicitPriceInMessage(mergedMessage)) {
                finalCleanText = ensurePhoneLeadConfirmationPreservingPrice(finalCleanText, extractedLead, honorific);
            } else {
                finalCleanText = ensureCleanPhoneLeadReply(finalCleanText, extractedLead, honorific, appliedRules);
            }
        } else if (!processed.needsHandoff()
                && FanpageBotPromptBuilder.isBroadUnspecifiedPriceInquiry(mergedMessage, request.history())) {
            finalCleanText = enforceAntiInformationDumpingOnBroadPrice(
                    finalCleanText, fused, honorific, request.customerName(), isFirstTurn);
        } else if (!processed.needsHandoff()) {
            finalCleanText = enforceConversationBookingConsistency(
                    finalCleanText, mergedMessage, bookingState, honorific, request.history(), isFirstTurn);
            if (consultResult != null && consultResult.isProfileUpdateTurn()) {
                finalCleanText = enforceTopicAnchorOnProfileUpdate(finalCleanText, consultResult, honorific);
            }
        }

        boolean hasCompletedBookingLead = bookingState.hasCapturedPhone() && bookingState.hasConfirmedTimeSlot();
        boolean finalHandoff = processed.needsHandoff() || leadReadyHandoff || hasCompletedBookingLead;
        String finalHandoffCategory = (leadReadyHandoff || hasCompletedBookingLead)
                ? "BOOKING_LEAD_CAPTURED"
                : (processed.needsHandoff() ? processed.handoffCategory() : null);
        String finalHandoffReason = (leadReadyHandoff || hasCompletedBookingLead)
                ? "Khách hàng đã để lại SĐT (" + extractedLead.phoneNumber()
                  + (extractedLead.preferredTime() != null ? " - Giờ hẹn: " + extractedLead.preferredTime() : "")
                  + ") / đặt lịch — Đã khóa lịch hẹn!"
                : (processed.needsHandoff() ? processed.handoffReason() : null);
        String finalStage = finalHandoff && processed.needsHandoff() && !leadReadyHandoff && !hasCompletedBookingLead ? "HUMAN_HANDOFF" : salesStage.code();
        double confidence = processed.needsHandoff() && !leadReadyHandoff ? 0.35 : computeConfidenceScore(fused);

        List<FanpageBotResponse.ReplyBubble> bubbles = splitIntoBubbles(finalCleanText);

        log.info("[LLM] FanpageBotService response from engine={} retrievalMs={} latencyMs={} status={} page_id={} honorific={} sales_stage={} handoff={} lead_phone={} bubbles={}",
                llmClient.name(), retrievalMs, llmLatencyMs, isFallback ? "fallback" : "ok",
                pageId, honorific, finalStage, finalHandoff, extractedLead != null ? maskPhoneNumber(extractedLead.phoneNumber()) : "none", bubbles.size());

        FanpageBotResponse rawResponse = new FanpageBotResponse(
                finalCleanText,
                bubbles,
                finalStage,
                confidence,
                finalHandoff,
                finalHandoffCategory,
                finalHandoffReason,
                extractedLead,
                appliedRules,
                customerInsight,
                activeSkills
        );

        FanpageBotResponse finalResponse = applyGuardrails(
                rawResponse,
                honorific,
                request.customerName(),
                isFirstTurn,
                finalStage,
                mergedMessage,
                request.history(),
                fused
        );

        // =========================================================================
        // HARNESS² BACKGROUND AUTO-TRIGGER HOOK (Milestone M3)
        // =========================================================================
        if (customerInsight != null && customerInsight.temperatureDelta() != null
                && customerInsight.temperatureDelta() <= -15
                && harnessOptimizerService != null) {
            try {
                final String pageIdStr = pageId != null ? pageId.toString() : null;
                final String convId = request.senderId() != null ? request.senderId() : "anon";
                final String capturedMessage = mergedMessage;
                final String capturedResponse = finalResponse != null ? finalResponse.replyText() : finalCleanText;
                final List<String> capturedHistory = (request.history() != null)
                        ? request.history().stream().filter(java.util.Objects::nonNull).map(t -> t.role() + ": " + t.content()).toList()
                        : List.of();
                final int tempDelta = customerInsight.temperatureDelta();

                harnessOptimizerService.triggerAsyncOptimization(
                        pageIdStr,
                        convId,
                        capturedMessage,
                        capturedResponse,
                        capturedHistory,
                        tempDelta,
                        "TEMPERATURE_DROP_BURST"
                );
            } catch (Throwable t) {
                log.warn("stage=fanpage-bot-harness-auto-trigger status=suppressed_error reason={}", t.getMessage());
            }
        }

        return finalResponse;
    }

    /**
     * Xử lý chủ động kéo lại tương tác (Proactive Re-engagement) khi khách hàng im lặng lâu hoặc hạ nhiệt.
     * Tự động giảm nhiệt độ mua hàng (decay), kích hoạt kỹ năng tâm lý Zero-Pressure hoặc Loss-Aversion
     * và sinh tin nhắn re-engage tự nhiên không thúc ép.
     */
    public FanpageBotResponse handleReengage(FanpageBotRequest request) {
        Integer idleSec = (request != null && request.idleSeconds() != null && request.idleSeconds() > 0)
                ? request.idleSeconds()
                : 300;
        Integer prevTemp = (request != null && request.previousTemperature() != null)
                ? request.previousTemperature()
                : null;
        String msg = (request != null && request.message() != null && !request.message().isBlank())
                ? request.message()
                : "Khách hàng tạm im lặng, bot chủ động gửi tin nhắn re-engage kéo lại tương tác.";
        FanpageBotRequest reengageReq = (request != null)
                ? new FanpageBotRequest(
                        request.pageId(),
                        request.senderId(),
                        request.customerName(),
                        msg,
                        request.messages(),
                        request.history(),
                        request.documentIds(),
                        request.topK(),
                        prevTemp,
                        request.replyDelaySeconds(),
                        idleSec,
                        "REENGAGE"
                )
                : new FanpageBotRequest(null, null, null, msg, null, null, null, 5, prevTemp, null, idleSec, "REENGAGE");
        return handleCustomerMessage(reengageReq);
    }

    /**
     * Chốt chặn Chống Xả Sạch Thông Tin (Anti-Information Dumping) ở Nấc 1:
     * Khi khách chỉ mới hỏi "giá", "báo giá", "chi phí sao" chung chung mà chưa nêu tình trạng/dịch vụ cụ thể,
     * nếu LLM vẫn liệt kê tràn lan >= 2 mức giá dịch vụ hoặc đổ cả gói Combo 10-15% ra cùng lúc,
     * tự động cô đọng lại theo đúng quy tắc "1 Khoảng giá khởi điểm + 1 Lợi ích 0đ rủi ro + 1 Câu hỏi khống chế A/B".
     */
    static String enforceAntiInformationDumpingOnBroadPrice(
            String cleanText,
            List<ReciprocalRankFusion.FusedChunk> chunks,
            String resolvedHonorific,
            String customerName,
            boolean isFirstTurn) {
        if (cleanText == null || cleanText.isBlank()) {
            return cleanText;
        }
        String honorific = (resolvedHonorific != null && !resolvedHonorific.isBlank())
                ? resolvedHonorific.strip()
                : "Chị";

        Pattern priceTokenPattern = Pattern.compile("\\b(\\d{1,3}(?:\\.\\d{3})+|\\d{2,4}k)\\s*(?:vnđ|đ|vnd|/buổi)?\\b", Pattern.CASE_INSENSITIVE);
        Matcher pm = priceTokenPattern.matcher(cleanText);
        Set<String> distinctPrices = new LinkedHashSet<>();
        while (pm.find()) {
            distinctPrices.add(pm.group(1).toLowerCase(Locale.ROOT));
        }

        String lower = cleanText.toLowerCase(Locale.ROOT);
        boolean dumpsComboOnBroadPrice = lower.contains("combo tiết kiệm") || lower.contains("combo toàn diện")
                || (lower.contains("3-4 buổi") && lower.contains("5-9 buổi"))
                || (lower.contains("giảm 10%") && lower.contains("giảm 15%"));
        boolean dumpsMultipleServices = distinctPrices.size() >= 3;

        if (!dumpsMultipleServices && !dumpsComboOnBroadPrice && cleanText.length() <= 340) {
            return cleanText;
        }

        String combinedChunks = chunks == null ? "" : chunks.stream()
                .map(fc -> fc.chunk().text() != null ? fc.chunk().text() : "")
                .reduce("", (a, b) -> a + " " + b);
        String combinedLower = (combinedChunks + " " + cleanText).toLowerCase(Locale.ROOT);

        String startingPrice = "650.000đ/buổi";
        if (!combinedLower.contains("650.000")) {
            long minVal = Long.MAX_VALUE;
            String minRaw = null;
            Matcher cm = priceTokenPattern.matcher(combinedChunks + " " + cleanText);
            while (cm.find()) {
                String token = cm.group(1).toLowerCase(Locale.ROOT);
                try {
                    long val;
                    String formatted;
                    if (token.endsWith("k")) {
                        val = Long.parseLong(token.substring(0, token.length() - 1)) * 1000L;
                        formatted = token;
                    } else {
                        val = Long.parseLong(token.replace(".", ""));
                        formatted = cm.group(1) + "đ";
                    }
                    if (val >= 90_000L && val < minVal) {
                        minVal = val;
                        minRaw = formatted;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            if (minRaw != null) {
                startingPrice = minRaw;
            }
        }

        String greetTarget = FanpageBotPromptBuilder.sanitizeCustomerDisplayName(customerName, honorific);
        String opening = isFirstTurn
                ? "Dạ em chào " + (greetTarget != null && !greetTarget.isBlank() ? greetTarget : honorific) + " ạ! "
                : "Dạ ";

        if (combinedLower.contains("soi da") || combinedLower.contains("trị mụn") || combinedLower.contains("trị nám") || combinedLower.contains("meso")) {
            return opening + "Chi phí các liệu trình chuẩn Y khoa bên em rất linh hoạt chỉ từ " + startingPrice
                    + " tùy tình trạng nền da, đã bao gồm Bác sĩ chuyên khoa soi da AI 3D miễn phí 100% và cam kết trọn gói không phát sinh phụ phí ạ.\n\n"
                    + "Hiện tại " + honorific + " đang muốn ưu tiên xử lý mụn, mờ nám tàn nhang hay dưỡng căng bóng trẻ hóa để em báo đúng gói hợp nhất cho mình ạ?";
        }

        if (combinedLower.contains("gội đầu dưỡng sinh") || combinedLower.contains("massage")) {
            return opening + "Chi phí các gói chăm sóc tại bên em rất linh hoạt chỉ từ " + startingPrice
                    + " tùy theo liệu trình và thời lượng, đã bao gồm ngâm chân thảo dược, trà bánh và cam kết không nhận Tip ạ.\n\n"
                    + "Hiện tại " + honorific + " đang quan tâm gói Massage trị liệu cổ vai gáy hay Gội đầu dưỡng sinh thư giãn để em báo giá chi tiết đúng gói hợp nhất cho mình ạ?";
        }

        return opening + "Mức chi phí bên em rất linh hoạt chỉ từ " + startingPrice
                + " tùy theo gói giải pháp và nhu cầu thực tế, cam kết minh bạch trọn gói không phát sinh phụ phí ạ.\n\n"
                + "Hiện tại " + honorific + " đang quan tâm ưu tiên hạng mục nào nhất để em báo giá chi tiết đúng gói hợp nhất cho mình ạ?";
    }

    private static boolean asksExplicitPriceInMessage(String msg) {
        if (msg == null || msg.isBlank()) {
            return false;
        }
        String lower = msg.toLowerCase(Locale.ROOT);
        return lower.contains("giá") || lower.contains("bao nhiêu tiền") || lower.contains("chi phí");
    }

    private static String ensurePhoneLeadConfirmationPreservingPrice(String currentReply,
                                                                     FanpageBotResponse.ExtractedLead lead,
                                                                     String honorific) {
        String h = (honorific != null && !honorific.isBlank()) ? honorific.strip() : "Chị";
        String phoneStr = (lead != null && lead.phoneNumber() != null) ? lead.phoneNumber() : "";
        if (currentReply == null || currentReply.isBlank()) {
            return ensureCleanPhoneLeadReply("", lead, honorific, List.of());
        }
        String trimmed = currentReply.strip();
        boolean mentionsPhone = !phoneStr.isBlank() && trimmed.contains(phoneStr);
        boolean mentionsCallback = trimmed.toLowerCase(Locale.ROOT).matches("(?s).*(?:liên\\s+hệ|gọi\\s+lại|zalo|số\\s+điện\\s+thoại|sđt).*");
        if (mentionsPhone && mentionsCallback) {
            return trimmed;
        }
        if (mentionsPhone) {
            return trimmed + "\n\nChuyên viên tư vấn bên em sẽ chủ động liên hệ sớm qua điện thoại/Zalo để hỗ trợ " + h + " chu đáo nhất nhé ạ!";
        }
        return trimmed + "\n\nDạ em cũng đã ghi nhận số điện thoại " + phoneStr
                + " của " + h + " rồi ạ! Chuyên viên tư vấn bên em sẽ liên hệ sớm qua điện thoại/Zalo để hỗ trợ " + h + " chu đáo nhất nhé ạ.";
    }

    private static String ensureCleanPhoneLeadReply(String currentReply,
                                                    FanpageBotResponse.ExtractedLead lead,
                                                    String honorific,
                                                    List<LearnedRuleDto> appliedRules) {
        String h = (honorific != null && !honorific.isBlank()) ? honorific.strip() : "Chị";
        String phoneStr = (lead != null && lead.phoneNumber() != null) ? lead.phoneNumber() : "";

        // Nếu có rule trong AI Playbook dạy mẫu câu trả lời khi khách để lại SĐT, ưu tiên dùng mẫu đó
        if (appliedRules != null) {
            Pattern quotePattern = Pattern.compile("['\"“”‘’]([^'\"“”‘’]{12,260})['\"“”‘’]");
            for (LearnedRuleDto rule : appliedRules) {
                if (rule != null && rule.generalizedRule() != null) {
                    String rText = rule.generalizedRule();
                    if (rText.toLowerCase(Locale.ROOT).contains("điện thoại")
                            || rText.toLowerCase(Locale.ROOT).contains("liên hệ")
                            || rText.toLowerCase(Locale.ROOT).contains("sđt")) {
                        Matcher qm = quotePattern.matcher(rText);
                        if (qm.find()) {
                            String template = qm.group(1).strip();
                            String polished = polishConversationalVietnamese(template, h, false, FanpageBotPromptBuilder.SalesStage.SOFT_BOOKING_CLOSE, null);
                            return stripPitchesAndDiscoveryQuestions(polished);
                        }
                    }
                }
            }
        }

        // BẮT BUỘC: Khi khách gửi SĐT để tư vấn thêm / đặt lịch:
        // 1. Phải xóa sạch bảng giá, đơn giá, combo (dù LLM có sinh ra hay không)
        // 2. Phải xóa sạch câu hỏi khảo sát ("ưu tiên cải thiện vấn đề nào nhất", "tình trạng da")
        // 3. Phải xác nhận nhận được SĐT, xưng hô đúng danh xưng ("Anh" / "Chị"), cam kết chuyên viên liên hệ qua phone/Zalo sớm.
        if (lead != null && lead.preferredTime() != null && !lead.preferredTime().isBlank()) {
            return "Dạ vâng, em đã ghi nhận số điện thoại " + phoneStr + " và khung giờ hẹn "
                    + lead.preferredTime() + " của " + h + " rồi ạ! Chuyên viên tư vấn bên em sẽ liên hệ sớm qua điện thoại/Zalo để xác nhận và hỗ trợ " + h + " chu đáo nhất nhé ạ.";
        }
        return "Dạ vâng, em đã nhận thông tin số điện thoại " + phoneStr
                + " của " + h + " rồi ạ! Chuyên viên tư vấn bên em sẽ chủ động liên hệ sớm qua điện thoại/Zalo để tư vấn chi tiết và hỗ trợ " + h + " chu đáo nhất nhé " + h + "!";
    }

    private static String stripPitchesAndDiscoveryQuestions(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text
                .replaceAll("(?isU)(?:\\b\\d{1,3}(?:[.,]\\d{3})+\\s*(?:vnđ|đ|vnd|/buổi)[^.!?\\n]*[.!?]?|combo\\s+(?:tiết\\s+kiệm|toàn\\s+diện)[^.!?\\n]*[.!?]?)", "")
                .replaceAll("(?isU)[^.!?\\n]*\\b(?:giá|1\\.500\\.000|combo|chiết\\s+khấu)[^.!?\\n]*[.!?]?", "")
                .replaceAll("(?isU)(?:\\n\\s*\\n|\\s+)[^.!?\n]*\\?(?:\\s*)$", "")
                .strip();
    }

    /**
     * Gộp tin nhắn đơn hoặc mảng tin nhắn liên tiếp (khi khách hàng gõ ngắt câu trên Messenger).
     */
    static String mergeBurstMessages(FanpageBotRequest request) {
        if (request == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (request.messages() != null) {
            for (String m : request.messages()) {
                if (m != null && !m.isBlank()) {
                    parts.add(m.strip());
                }
            }
        }
        if (request.message() != null && !request.message().isBlank()) {
            String single = request.message().strip();
            if (parts.isEmpty() || !parts.get(parts.size() - 1).equals(single)) {
                parts.add(single);
            }
        }
        return String.join(". ", parts).strip();
    }

    /**
     * Nén ngữ cảnh từ 2 lượt hỏi gần nhất của khách hàng nếu tin nhắn hiện tại quá ngắn hoặc thiếu chủ ngữ
     * (VD: Lượt 1: "Tư vấn chị gói trị nám", Lượt 2: "Giá sao em?").
     */
    static String buildContextualSearchQuery(String currentMessage, List<FanpageBotRequest.ConversationTurn> history) {
        if (currentMessage != null && VN_PHONE_PATTERN.matcher(currentMessage).find() && !asksExplicitPriceInMessage(currentMessage)) {
            return currentMessage + " số điện thoại liên hệ tư vấn";
        }
        if (history == null || history.isEmpty()) {
            return currentMessage;
        }
        boolean isShortOrElliptical = currentMessage.length() < 42
                || SHORT_FOLLOWUP_PATTERN.matcher(currentMessage).matches();
        if (!isShortOrElliptical) {
            return currentMessage;
        }

        List<String> recentUserInputs = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0 && recentUserInputs.size() < 2; i--) {
            FanpageBotRequest.ConversationTurn turn = history.get(i);
            if (turn != null && "user".equalsIgnoreCase(turn.role()) && turn.content() != null && !turn.content().isBlank()) {
                recentUserInputs.add(0, turn.content().strip());
            }
        }
        String joined = recentUserInputs.isEmpty()
                ? currentMessage
                : String.join(" ", recentUserInputs) + " " + currentMessage;
        if (FanpageBotPromptBuilder.isPriceObjectionOrDownsellMessage(currentMessage)
                || FanpageBotPromptBuilder.isTrialOrBookingAcceptanceMessage(currentMessage)) {
            joined = joined + " bảng giá dịch vụ gói cơ bản dưỡng sinh lấy ráy tai 129.000 239.000 399.000 ưu đãi";
        }
        return joined;
    }

    static String enrichContextualQueryWithLangGraph(
            String baseQuery,
            com.example.docmind.client.FastApiAgentClient.ConsultResponse consultResult) {
        String q = baseQuery != null ? baseQuery : "";
        if (consultResult == null || "GENERAL".equalsIgnoreCase(consultResult.topicFamily())) {
            return q;
        }
        if (consultResult.topicKeywords() != null && !consultResult.topicKeywords().isEmpty()) {
            String lowerQ = q.toLowerCase(Locale.ROOT);
            List<String> missingKeywords = new ArrayList<>();
            for (String kw : consultResult.topicKeywords()) {
                if (kw != null && !kw.isBlank() && !lowerQ.contains(kw.toLowerCase(Locale.ROOT))) {
                    missingKeywords.add(kw.strip());
                }
            }
            if (!missingKeywords.isEmpty()) {
                q = String.join(" ", missingKeywords) + " " + q;
            }
        }
        return q.strip();
    }

    static List<ReciprocalRankFusion.FusedChunk> scopeChunksToTopicFamily(
            List<ReciprocalRankFusion.FusedChunk> rawFused,
            com.example.docmind.client.FastApiAgentClient.ConsultResponse consultResult,
            String currentMessage) {
        if (rawFused == null || rawFused.isEmpty() || consultResult == null) {
            return rawFused == null ? List.of() : rawFused;
        }
        if (!"ACNE_THAM".equalsIgnoreCase(consultResult.topicFamily())) {
            return rawFused;
        }
        String lowerMsg = currentMessage != null ? currentMessage.toLowerCase(Locale.ROOT) : "";
        if (lowerMsg.contains("nám") || lowerMsg.contains("pico") || lowerMsg.contains("meso") || lowerMsg.contains("căng bóng")) {
            return rawFused;
        }
        Pattern offTopicLinePattern = Pattern.compile("(?iU)(trị\\s+nám|laser\\s+pico|meso\\s+collagen|căng\\s+bóng\\s+&\\s+trẻ\\s+hóa)");
        List<ReciprocalRankFusion.FusedChunk> scoped = new ArrayList<>(rawFused.size());
        for (ReciprocalRankFusion.FusedChunk fc : rawFused) {
            if (fc == null || fc.chunk() == null || fc.chunk().text() == null) {
                continue;
            }
            String text = fc.chunk().text();
            String[] lines = text.split("\\r?\\n");
            if (lines.length <= 1) {
                scoped.add(fc);
                continue;
            }
            List<String> keptLines = new ArrayList<>();
            for (String line : lines) {
                if (!offTopicLinePattern.matcher(line).find()) {
                    keptLines.add(line);
                }
            }
            String pruned = String.join("\n", keptLines).strip();
            if (!pruned.isEmpty() && !pruned.equals(text)) {
                com.example.docmind.repository.RetrievedChunk orig = fc.chunk();
                com.example.docmind.repository.RetrievedChunk updated = new com.example.docmind.repository.RetrievedChunk(
                        orig.chunkId(),
                        orig.documentId(),
                        orig.headingPath(),
                        orig.sourceBlockIds(),
                        orig.page(),
                        orig.bbox(),
                        pruned,
                        orig.score()
                );
                scoped.add(new ReciprocalRankFusion.FusedChunk(updated, fc.score()));
            } else {
                scoped.add(fc);
            }
        }
        return scoped;
    }

    static String enforceTopicAnchorOnProfileUpdate(
            String cleanText,
            com.example.docmind.client.FastApiAgentClient.ConsultResponse consultResult,
            String resolvedHonorific) {
        if (cleanText == null || cleanText.isBlank() || consultResult == null || !consultResult.isProfileUpdateTurn()) {
            return cleanText;
        }
        if (!"ACNE_THAM".equalsIgnoreCase(consultResult.topicFamily())) {
            return cleanText;
        }
        String lower = cleanText.toLowerCase(Locale.ROOT);
        boolean driftedToCatalog = lower.contains("laser pico")
                || lower.contains("meso collagen")
                || lower.contains("thâm nám hay dưỡng căng bóng")
                || lower.contains("mụn, nám")
                || lower.contains("đang cần ưu tiên cải thiện vấn đề nào nhất");
        if (!driftedToCatalog) {
            return cleanText;
        }
        String h = (resolvedHonorific != null && !resolvedHonorific.isBlank()) ? resolvedHonorific.strip() : "Anh";
        String kws = (consultResult.topicKeywords() != null && !consultResult.topicKeywords().isEmpty())
                ? String.join(" và ", consultResult.topicKeywords())
                : "điều trị mụn & mờ thâm";
        boolean hasBackAcne = kws.toLowerCase(Locale.ROOT).contains("lưng");
        String bodyPartNote = hasBackAcne
                ? "đặc biệt vùng da lưng và mặt của Nam giới có lớp sừng dày và tuyến bã nhờn hoạt động mạnh hơn"
                : "nền da Nam giới thường dày và tuyến bã nhờn hoạt động mạnh hơn";
        return "Dạ các phác đồ " + kws + " chuẩn Y khoa bên em hoàn toàn phù hợp và hiệu quả cao với " + h
                + " Nam giới ạ (bên em có bố trí khu vực phòng trị liệu riêng tư kín đáo 1-1 nên " + h + " hoàn toàn thoải mái ạ). "
                + "Vì " + bodyPartNote + ", Liệu trình Trị Mụn Chuẩn Y Khoa (từ 650.000đ - 1.500.000đ/buổi, cam kết 0 phụ phí) sẽ giúp tiêu viêm sâu và làm sáng vết thâm rất nhanh.\n\n"
                + h + " tiện ghé cơ sở vào khung giờ Sáng hay Chiều để Bác sĩ Chuyên khoa I trực tiếp soi da AI 3D miễn phí 100% và lên phác đồ xử lý dứt điểm cho mình ạ?";
    }

    /**
     * Xác định Nhịp độ tư vấn (SalesStage) để Bot tư vấn từ tốn, không vội vã:
     * - DISCOVERY: Mới bắt đầu (0-1 lượt trước) và chưa hỏi thẳng về giá gói cụ thể/chốt lịch (hoặc chỉ hỏi "giá" chung chung chưa rõ nhu cầu).
     * - SOFT_BOOKING_CLOSE: Khi khách có tín hiệu đặt lịch, để lại SĐT, hoặc đã trao đổi sâu (>= 4 lượt).
     * - VALUE_CONSULTATION: Khi khách hỏi về giá dịch vụ cụ thể, ưu đãi, bảo hành, so sánh hoặc ở lượt 2-3.
     */
    static FanpageBotPromptBuilder.SalesStage determineSalesStage(
            String message,
            List<FanpageBotRequest.ConversationTurn> history,
            FanpageBotResponse.ExtractedLead extractedLead) {
        if (FanpageBotPromptBuilder.isPriceObjectionOrDownsellMessage(message)) {
            return FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION;
        }
        if ((extractedLead != null && extractedLead.hasBuyingOrBookingSignal())
                || FanpageBotPromptBuilder.isTrialOrBookingAcceptanceMessage(message)) {
            return FanpageBotPromptBuilder.SalesStage.SOFT_BOOKING_CLOSE;
        }
        int turnCount = history != null ? history.size() : 0;
        String lower = message != null ? message.toLowerCase(Locale.ROOT) : "";
        boolean isBareBrushOff = lower.matches("(?isU)^\\s*(?:ừ|uk|uh|uhm|um|ok|oke|oki|okie|vâng|dạ|rồi)(?:\\s+(?:em|shop|ad|bạn|nhé|nha|ạ))?\\s*[.!,~?]*$");
        boolean isCoolingSignal = isBareBrushOff || lower.matches("(?isU).*\\b(để\\s+(?:chị|anh|em|mình|t|bạn)?\\s*(?:xem|coi|nghĩ|suy\\s+nghĩ|tính)|để\\s+xem|nghĩ\\s+thêm|thôi|khi\\s+khác|chưa\\s+cần|re-engage|im\\s+lặng)\\b.*");
        if (isCoolingSignal) {
            return FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION;
        }
        boolean asksPriceOrPolicy = lower.contains("giá") || lower.contains("bao nhiêu")
                || lower.contains("chiết khấu") || lower.contains("khuyến mãi") || lower.contains("combo")
                || lower.contains("tặng") || lower.contains("bảo hành") || lower.contains("đắt")
                || lower.contains("cao") || lower.contains("rẻ") || lower.contains("mềm")
                || lower.contains("tip") || lower.contains("phụ phí") || lower.contains("đổi gói");

        if (FanpageBotPromptBuilder.isBroadUnspecifiedPriceInquiry(message, history)) {
            return FanpageBotPromptBuilder.SalesStage.DISCOVERY;
        }
        if (asksPriceOrPolicy) {
            return FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION;
        }
        if (turnCount >= 4) {
            return FanpageBotPromptBuilder.SalesStage.SOFT_BOOKING_CLOSE;
        }
        if (turnCount >= 2) {
            return FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION;
        }
        return FanpageBotPromptBuilder.SalesStage.DISCOVERY;
    }

    /**
     * Phát hiện nhanh khi khách hỏi đích danh một dịch vụ/thông tin cụ thể (như tiêm filler, cắt mí, số tài khoản...)
     * mà toàn bộ các chunk truy xuất được hoàn toàn không chứa cụm từ đó.
     */
    static String detectUngroundedSpecificTopic(String message, List<ReciprocalRankFusion.FusedChunk> chunks) {
        Matcher m = SPECIFIC_FACT_QUERY_PATTERN.matcher(message);
        if (!m.find()) {
            return null;
        }
        String matchedTerm = m.group(1).toLowerCase(Locale.ROOT).strip();
        String combinedContext = chunks.stream()
                .map(fc -> fc.chunk().text() != null ? fc.chunk().text().toLowerCase(Locale.ROOT) : "")
                .reduce("", (a, b) -> a + " " + b);

        if (!combinedContext.contains(matchedTerm)) {
            return m.group(1).strip();
        }
        return null;
    }

    record PostProcessedBotReply(
            String cleanText,
            boolean needsHandoff,
            String handoffCategory,
            String handoffReason
    ) {
    }

    static PostProcessedBotReply postProcessReply(String rawReply, List<ReciprocalRankFusion.FusedChunk> chunks) {
        return postProcessReply(rawReply, chunks, "Chị", true, FanpageBotPromptBuilder.SalesStage.DISCOVERY);
    }

    static PostProcessedBotReply postProcessReply(
            String rawReply,
            List<ReciprocalRankFusion.FusedChunk> chunks,
            String resolvedHonorific) {
        return postProcessReply(rawReply, chunks, resolvedHonorific, true, FanpageBotPromptBuilder.SalesStage.DISCOVERY);
    }

    /**
     * Hậu xử lý câu trả lời của LLM:
     * 1. Bóc tách cờ [[HANDOFF: lý do]] hoặc câu từ chối máy móc ("Không tìm thấy thông tin...").
     * 2. Xóa sạch mọi trích dẫn [1], [2], mã nội bộ SPA-01, tiêu đề 📋/💬, cụm RAG máy móc ("Dựa trên tài liệu được cung cấp...").
     * 3. Sửa lỗi đảo ngược chủ ngữ ("Em có thể tham khảo..." -> "Chị/Anh có thể tham khảo..."), đồng nhất xưng hô Anh/Chị 100%,
     *    loại bỏ câu chào lặp lại ở lượt 2+, sửa lỗi nhầm tên Combo và gọt câu hỏi kết thúc dồn dập thành 1 câu tự nhiên.
     */
    static PostProcessedBotReply postProcessReply(
            String rawReply,
            List<ReciprocalRankFusion.FusedChunk> chunks,
            String resolvedHonorific,
            boolean isFirstTurn,
            FanpageBotPromptBuilder.SalesStage salesStage) {
        return postProcessReply(rawReply, chunks, resolvedHonorific, isFirstTurn, salesStage, null);
    }

    /**
     * Hậu xử lý câu trả lời của LLM:
     * 1. Bóc tách cờ [[HANDOFF: lý do]] hoặc câu từ chối máy móc ("Không tìm thấy thông tin...").
     * 2. Xóa sạch mọi trích dẫn [1], [2], mã nội bộ SPA-01, tiêu đề 📋/💬, cụm RAG máy móc ("Dựa trên tài liệu được cung cấp...").
     * 3. Sửa lỗi đảo ngược chủ ngữ ("Em có thể tham khảo..." -> "Chị/Anh có thể tham khảo..."), đồng nhất xưng hô Anh/Chị 100%,
     *    loại bỏ câu chào lặp lại ở lượt 2+, sửa lỗi nhầm tên Combo và gọt câu hỏi kết thúc dồn dập thành 1 câu tự nhiên.
     */
    static PostProcessedBotReply postProcessReply(
            String rawReply,
            List<ReciprocalRankFusion.FusedChunk> chunks,
            String resolvedHonorific,
            boolean isFirstTurn,
            FanpageBotPromptBuilder.SalesStage salesStage,
            List<FanpageBotRequest.ConversationTurn> history) {
        return postProcessReply(rawReply, chunks, resolvedHonorific, isFirstTurn, salesStage, history, null);
    }

    static PostProcessedBotReply postProcessReply(
            String rawReply,
            List<ReciprocalRankFusion.FusedChunk> chunks,
            String resolvedHonorific,
            boolean isFirstTurn,
            FanpageBotPromptBuilder.SalesStage salesStage,
            List<FanpageBotRequest.ConversationTurn> history,
            String currentMessage) {
        String honorific = (resolvedHonorific != null && !resolvedHonorific.isBlank())
                ? resolvedHonorific.strip()
                : "Chị";

        if (rawReply == null || rawReply.isBlank()) {
            return new PostProcessedBotReply(
                    "Dạ em xin phép chuyển tin nhắn cho Chuyên viên tư vấn trực tiếp của Page để kiểm tra và phản hồi " + honorific + " ngay nhé ạ!",
                    true,
                    "UNGROUNDED_OR_UNKNOWN",
                    "LLM trả về phản hồi rỗng."
            );
        }

        String working = rawReply.strip();
        boolean handoff = false;
        String handoffCategory = null;
        String handoffReason = null;

        // 1. Kiểm tra nhãn [[HANDOFF: ...]]
        Matcher tagMatcher = LLM_HANDOFF_TAG_PATTERN.matcher(working);
        if (tagMatcher.find()) {
            String candidateReason = tagMatcher.group(1).strip();
            working = tagMatcher.replaceAll("").strip();
            boolean mentionsHumanDeferral = working.toLowerCase(Locale.ROOT).matches(
                    "(?s).*(xin phép chuyển|nhờ chuyên viên|bác sĩ phụ trách|kiểm tra và nhắn|chưa có thông tin|ngoài danh mục).*"
            );
            boolean hasGroundedPriceList = working.matches("(?s).*\\b\\d{1,3}(?:[.,]\\d{3})+\\s*(?:VNĐ|đ|vnd|/buổi).*");
            if (mentionsHumanDeferral || !hasGroundedPriceList) {
                handoff = true;
                handoffCategory = "UNGROUNDED_OR_UNKNOWN";
                handoffReason = candidateReason;
            }
        }

        // 2. Nếu StubLlmClient hoặc LLM trả về mẫu Q&A nội bộ hoặc câu từ chối cứng nhắc
        if (FALLBACK_REFUSAL_PATTERN.matcher(working).find()) {
            handoff = true;
            handoffCategory = "UNGROUNDED_OR_UNKNOWN";
            if (handoffReason == null) {
                handoffReason = "Thông tin khách hàng hỏi không có trong tài liệu tri thức của Page.";
            }
            working = FALLBACK_REFUSAL_PATTERN.matcher(working)
                    .replaceAll("Dạ về nội dung này để có thông tin chính xác nhất cho trường hợp của " + honorific
                            + ", em xin phép báo Chuyên viên phụ trách kiểm tra và nhắn tư vấn trực tiếp cho " + honorific + " ngay ạ.");
        }

        // Nếu đầu ra vẫn vướng định dạng 2 phần của Q&A nội bộ (📋 / 💬), chỉ giữ phần tin nhắn gửi khách
        int saleReadyIdx = working.indexOf("💬");
        if (saleReadyIdx >= 0) {
            working = working.substring(saleReadyIdx);
        }

        // 3. Làm sạch toàn bộ ký hiệu trích dẫn nguồn, mã nội bộ, đại từ bị ngược, câu chào lặp và câu máy móc
        working = polishConversationalVietnamese(working, honorific, isFirstTurn, salesStage, history, currentMessage);

        if (working.isBlank()) {
            working = "Dạ em xin phép kết nối ngay với Chuyên viên phụ trách của Page để hỗ trợ chi tiết cho " + honorific + " liền nhé ạ!";
            handoff = true;
            handoffCategory = "UNGROUNDED_OR_UNKNOWN";
            if (handoffReason == null) {
                handoffReason = "Nội dung phản hồi cần nhân viên xác nhận.";
            }
        }

        return new PostProcessedBotReply(working, handoff, handoffCategory, handoffReason);
    }

    static String enforceConversationBookingConsistency(
            String cleanText,
            String currentMessage,
            FanpageBotPromptBuilder.ConversationBookingState bookingState,
            String resolvedHonorific) {
        return enforceConversationBookingConsistency(cleanText, currentMessage, bookingState, resolvedHonorific, null, false);
    }

    static String enforceConversationBookingConsistency(
            String cleanText,
            String currentMessage,
            FanpageBotPromptBuilder.ConversationBookingState bookingState,
            String resolvedHonorific,
            List<FanpageBotRequest.ConversationTurn> history,
            boolean isFirstTurn) {
        if (cleanText == null || cleanText.isBlank()) {
            return cleanText;
        }
        String h = (resolvedHonorific != null && !resolvedHonorific.isBlank()) ? resolvedHonorific.strip() : "Chị";

        // Chống ảo giác vị trí cơ thể (VD: tự bịa "vùng cằm và lưng" khi khách chỉ nói "mờ thâm và trị mụn lưng")
        StringBuilder userConvoSb = new StringBuilder();
        if (currentMessage != null) {
            userConvoSb.append(currentMessage.toLowerCase(Locale.ROOT)).append(' ');
        }
        if (history != null) {
            for (FanpageBotRequest.ConversationTurn t : history) {
                if (t != null && !"assistant".equalsIgnoreCase(t.role()) && t.content() != null) {
                    userConvoSb.append(t.content().toLowerCase(Locale.ROOT)).append(' ');
                }
            }
        }
        String allUserText = userConvoSb.toString();
        if (!allUserText.contains("cằm") && cleanText.toLowerCase(Locale.ROOT).contains("cằm")) {
            cleanText = cleanText
                    .replaceAll("(?iU)ở\\s+cả\\s+vùng\\s+cằm\\s+và\\s+lưng", "ở vùng lưng")
                    .replaceAll("(?iU)vùng\\s+cằm\\s+và\\s+lưng", "vùng lưng")
                    .replaceAll("(?iU)cằm\\s+và\\s+lưng", "vùng lưng");
        }

        // Trường hợp khách báo là Nam giới ở lượt tiếp theo ("nhưng tôi là nam", "tôi là nam", "mình là nam")
        // Nếu LLM bị mất ngữ cảnh (chào lại hoặc hỏi lại từ đầu danh mục chung) hoặc chưa khẳng định phù hợp cho Nam giới:
        boolean explicitMaleTurn = "Anh".equalsIgnoreCase(h)
                && "Anh".equalsIgnoreCase(FanpageBotPromptBuilder.detectExplicitGenderHonorific(currentMessage));
        if (!isFirstTurn && explicitMaleTurn) {
            String lowerClean = cleanText.toLowerCase(Locale.ROOT);
            boolean resetToGenericDiscovery = lowerClean.contains("mụn, nám laser pico")
                    || lowerClean.contains("ưu tiên cải thiện tình trạng mụn, thâm nám")
                    || lowerClean.contains("ưu tiên xử lý mụn, mờ nám")
                    || lowerClean.contains("ưu tiên xử lý dứt điểm mụn ẩn/mụn viêm, làm mờ sạm nám");
            boolean pureMaleDeclaration = currentMessage != null && currentMessage.strip().length() <= 36
                    && !currentMessage.toLowerCase(Locale.ROOT).matches("(?isU).*(?:giá|bao\\s+nhiêu|bao\\s+lâu|địa\\s+chỉ|ở\\s+đâu|sẹo|nám|mụn).*");
            boolean acknowledgesMale = lowerClean.contains("nam") || lowerClean.contains("phòng riêng") || lowerClean.contains("kín đáo");

            if (resetToGenericDiscovery || (pureMaleDeclaration && !acknowledgesMale)) {
                boolean hasBackAcne = allUserText.contains("lưng");
                boolean hasDarkSpot = allUserText.contains("thâm");
                if (hasBackAcne) {
                    return "Dạ bên em có khu vực phòng điều trị riêng biệt, kín đáo dành cho " + h
                            + " Nam giới nên " + h + " hoàn toàn yên tâm ạ! Đặc biệt nền da vùng lưng của nam giới thường dày và tuyến bã nhờn hoạt động mạnh hơn, rất phù hợp với Liệu trình Trị Mụn Chuẩn Y Khoa (1.500.000đ/buổi, tặng kèm 01 tuýp chấm mụn kháng viêm, trọn gói 0 phụ phí) để lấy sạch nhân mụn vô khuẩn và làm sáng vết thâm nhanh chóng.\n\n"
                            + h + " tiện ghé cơ sở vào khung giờ Sáng hay Chiều để Bác sĩ Da liễu soi da AI 3D miễn phí 100% trong phòng riêng trước ạ?";
                }
                if (hasDarkSpot || allUserText.contains("mụn")) {
                    return "Dạ Liệu trình Trị Mụn Chuẩn Y Khoa (1.500.000đ/buổi, tặng kèm 01 tuýp chấm mụn kháng viêm, cam kết 0 phụ phí) bên em hoàn toàn phù hợp và hiệu quả cao với nền da của "
                            + h + " Nam giới, có không gian phòng điều trị riêng tư kín đáo ạ.\n\n"
                            + h + " muốn đặt lịch qua Bác sĩ soi da AI 3D miễn phí 100% vào khung giờ Sáng hay Chiều để bên em giữ phòng riêng trước cho mình ạ?";
                }
                return "Dạ các phác đồ chăm sóc và điều trị da chuẩn Y khoa bên em đều thiết kế chuyên biệt cho cả nền da của "
                        + h + " Nam giới với không gian phòng riêng kín đáo, Bác sĩ Da liễu trực tiếp soi da AI 3D miễn phí 100% (chi phí chỉ từ 650.000đ/buổi, cam kết 0 phụ phí ạ).\n\n"
                        + "Hiện tại " + h + " đang muốn ưu tiên xử lý mụn thâm hay chăm sóc phục hồi da để em báo đúng phác đồ hợp nhất cho " + h + " ạ?";
            }
        }

        if (bookingState == null) {
            return cleanText;
        }
        String slotLabel = bookingState.hasConfirmedTimeSlot() ? bookingState.confirmedTimeSlot() : "12h30 trưa";
        String dateLabel = bookingState.confirmedDate() != null ? " " + bookingState.confirmedDate() : "";
        String phoneLabel = bookingState.hasCapturedPhone() ? " (SĐT " + bookingState.capturedPhone() + ")" : "";

        // Trường hợp 1: Khách phàn nàn vì Bot hỏi lặp lại Sáng/Chiều sau khi đã chốt giờ (VD: "12h30 là buổi trưa rồi còn hỏi sáng hay chiều, không lẽ đêm à")
        if (bookingState.isRedundantQuestionComplaint()) {
            return "Dạ em xin lỗi " + h + " vì sơ suất hỏi lặp lại ạ! Em đã khóa cố định lịch hẹn lúc "
                    + slotLabel + dateLabel + phoneLabel + " cùng Bác sĩ Da liễu và phòng riêng kín đáo cho "
                    + h + " rồi ạ, tới giờ " + h + " cứ ghé thẳng quầy lễ tân nhé ạ.";
        }

        // Trường hợp 2: Khách đã hoàn tất đặt lịch (đã có SĐT + giờ hẹn) hoặc dặn "tự sắp xếp / không cần gọi / tới giờ tự tới"
        if (bookingState.isPostBookingWrapUp()) {
            String stripped = cleanText
                    .replaceAll("(?isU)(?:\\n\\s*\\n|(?<=[.!?])\\s+)(?:dạ\\s+)?[^.!?\\n]*(?:sáng\\s+hay\\s+chiều|ngày\\s+thường\\s+hay\\s+cuối\\s+tuần|xin\\s+khung\\s+giờ|đặt\\s+lịch\\s+qua\\s+soi\\s+da|giữ\\s+lịch\\s+trước|nhận\\s+cuộc\\s+gọi\\s+xác\\s+nhận|muốn\\s+đổi\\s+sang\\s+khung)[^.!?]*(?:\\?|!|\\.)\\s*$", "")
                    .strip();
            // Nếu đoạn cuối vẫn kết thúc bằng câu hỏi '?' dư thừa thì cắt bỏ câu hỏi cuối hoặc trả về câu xác nhận gọn gàng
            if (stripped.endsWith("?") || stripped.isBlank()
                    || stripped.toLowerCase(Locale.ROOT).contains("sáng hay chiều")
                    || stripped.toLowerCase(Locale.ROOT).contains("ngày thường hay cuối tuần")) {
                if (bookingState.customerDeclinedCallOrSelfArranges()) {
                    return "Dạ vâng " + h + " ạ! Em đã khóa sẵn lịch hẹn lúc " + slotLabel + dateLabel + phoneLabel
                            + " cùng Bác sĩ Da liễu và phòng riêng cho " + h
                            + " mà không gọi làm phiền trước đâu ạ, tới giờ " + h + " cứ ghé thẳng cơ sở bên em đón tiếp chu đáo nhé ạ.";
                }
                return "Dạ vâng, em đã cập nhật và khóa cố định lịch hẹn lúc " + slotLabel + dateLabel + phoneLabel
                        + " cùng Bác sĩ Da liễu và phòng riêng kín đáo cho " + h
                        + " rồi ạ, hẹn đón tiếp " + h + " vào khung giờ này nhé ạ.";
            }
            return stripped;
        }

        return cleanText;
    }

    public static String polishConversationalVietnamese(String rawText, String resolvedHonorific) {
        return polishConversationalVietnamese(rawText, resolvedHonorific, true, FanpageBotPromptBuilder.SalesStage.DISCOVERY, null, null);
    }

    /**
     * Bộ lọc hậu xử lý chuyên sâu cho tiếng Việt hội thoại tư vấn khách hàng (dùng chung cho FanpageBotService & ChatService):
     * - Sửa lỗi đảo ngược đại từ chủ ngữ ("Em có thể tham khảo gói..." -> "Chị/Anh có thể tham khảo gói...").
     * - Đồng nhất 100% danh xưng Anh / Chị với cờ Unicode (?iU) chuẩn xác (không bao giờ sót "nha Chị.", "của Chị,").
     * - Loại bỏ câu chào lặp lại ở các lượt hội thoại thứ 2 trở đi ("Dạ em chào Chị ạ! Em rất vui được tư vấn...").
     * - Sửa lỗi ghép nhầm tên Combo ("Combo Toàn Diện (3 - 4 buổi)" -> "Combo Tiết Kiệm (3 - 4 buổi)").
     * - Xóa câu máy móc đọc lại tệp khách hàng ("bên em chuyên phục vụ khách hàng nữ giới từ 22-45 tuổi...") và câu "Em hiểu câu hỏi của...".
     * - Xóa mã nội bộ SPA-01, SPA-02... (kể cả khi có hoặc không có dấu ngoặc).
     * - Rút gọn các câu hỏi xin phép dồn dập, khô khan ở cuối tin nhắn thành đúng 1 câu hỏi gợi mở tự nhiên theo từng giai đoạn.
     */
    public static String polishConversationalVietnamese(
            String rawText,
            String resolvedHonorific,
            boolean isFirstTurn,
            FanpageBotPromptBuilder.SalesStage salesStage) {
        return polishConversationalVietnamese(rawText, resolvedHonorific, isFirstTurn, salesStage, null, null);
    }

    public static String polishConversationalVietnamese(
            String rawText,
            String resolvedHonorific,
            boolean isFirstTurn,
            FanpageBotPromptBuilder.SalesStage salesStage,
            List<FanpageBotRequest.ConversationTurn> history) {
        return polishConversationalVietnamese(rawText, resolvedHonorific, isFirstTurn, salesStage, history, null);
    }

    public static String polishConversationalVietnamese(
            String rawText,
            String resolvedHonorific,
            boolean isFirstTurn,
            FanpageBotPromptBuilder.SalesStage salesStage,
            List<FanpageBotRequest.ConversationTurn> history,
            String currentMessage) {
        if (rawText == null || rawText.isBlank()) {
            return "";
        }
        String honorific = (resolvedHonorific != null && !resolvedHonorific.isBlank())
                ? resolvedHonorific.strip()
                : "Chị";

        String working = rawText
                // Chuẩn hóa ký hiệu tách bong bóng ||MSG|| (nếu có) thành ngắt đoạn tự nhiên
                .replaceAll("\\s*\\|\\|MSG\\|\\|\\s*", "\n\n")
                // Xóa tiêu đề 📋 / 💬
                .replaceAll("(?m)^\\s*(\\*\\*)?[📋💬]\\s*\\[?[^\\n\\]]*\\]?(\\*\\*)?:?\\s*", "")
                // Xóa cụm mở đầu máy móc kiểu RAG ("Dựa trên tài liệu được cung cấp, ", "Theo đoạn trích tài liệu: ")
                .replaceAll("(?iU)\\b(?:dựa\\s+trên|theo|căn\\s+cứ\\s+vào)\\s+(?:các\\s+)?(?:đoạn\\s+trích\\s+)?(?:tài\\s+liệu(?:\\s+sop)?|đoạn\\s+trích|ngữ\\s+cảnh|văn\\s+bản(?:\\s+nguồn)?)(?:\\s+được\\s+cung\\s+cấp|\\s+đã\\s+tải\\s+lên|\\s+nội\\s+bộ|\\s+đính\\s+kèm)?\\s*[,:]\\s*", "")
                // Xóa câu bảo khách hàng "tham khảo ... trong tài liệu"
                .replaceAll("(?iU)[^.!?\\n]*\\b(tham\\s+khảo|xem\\s+thêm)[^.!?\\n]*\\btrong\\s+tài\\s+liệu[^.!?\\n]*[.!?]?\\s*", "")
                .replaceAll("(?iU)\\s*trong\\s+tài\\s+liệu\\b", "của bên em")
                // Xóa câu đọc thuộc lòng tệp khách hàng nội bộ ("Đối tượng khách hàng chủ yếu là..." hoặc "Dạ bên em chuyên phục vụ khách hàng nữ giới từ 22-45 tuổi, nhưng thực tế,")
                .replaceAll("(?iU)[^.!?\\n]*\\bđối\\s+tượng\\s+khách\\s+hàng\\s+(chủ\\s+yếu|mục\\s+tiêu)[^.!?\\n]*[.!?]?\\s*", "")
                .replaceAll("(?iU)(?:dạ[,\\s]*)?(?:bên\\s+em|chúng\\s+tôi|nàng\\s+sen|spa|page)\\s+chuyên\\s+(?:phục\\s+vụ|dành\\s+cho)\\s+khách\\s+hàng\\s+nữ[^.!?:\\n]*[,;.]?\\s*(?:nhưng\\s+thực\\s+tế[,\\s]*)?", "Dạ ")
                .replaceAll("(?iU)[^.!?\\n]*\\bchuyên\\s+phục\\s+vụ\\s+khách\\s+hàng\\s+nữ\\s+giới[^.!?\\n]*[.!?]?\\s*", "")
                // Xóa câu mở đầu máy móc kiểu dịch tiếng Anh ("Em hiểu câu hỏi của Chị.", "Em hiểu băn khoăn của Anh.")
                .replaceAll("(?iU)(?:^|(?<=[.!?\\n,;]\\s*))em\\s+(?:đã\\s+|rất\\s+)?hiểu\\s+(?:câu\\s+hỏi|thắc\\s+mắc|băn\\s+khoăn)\\s+của\\s+(?:chị|anh|anh/chị|bạn|mình)(?:\\s+ạ)?[.!,]?\\s*", "")
                // Xóa ký hiệu trích dẫn [1], [2], [1, 2], [SPA-01], (SPA-03) VÀ cả mã nội bộ trần SPA-01, SPA-02, SPA-03, SPA-04
                .replaceAll("\\[\\s*trích\\s*dẫn[^\\]]*\\]", "")
                .replaceAll("(?m)\\s*(?:\\[\\d{1,3}(?:\\s*[,;-]\\s*\\d{1,3})*])+(?=\\s*[.,;!?):\\n]|\\s*$)", "")
                .replaceAll("\\s*\\[(?:\\d+|SPA-\\d+|SP-?\\d+|DV-?\\d+|Mục:[^\\]]+)\\]", "")
                .replaceAll("\\s*\\((?:SPA-\\d+|SP-?\\d+|DV-?\\d+)\\)", "")
                .replaceAll("\\s+\\b(?:SPA|SP|DV)-\\d{1,3}\\b", "")
                // Xóa các placeholder kiểu [Tên Khách Hàng], [Họ và Tên] và trường hợp chào nhầm số điện thoại ("chào Chị 0982663255")
                .replaceAll("\\[\\s*(Tên\\s+Khách\\s+Hàng|Họ\\s+và\\s+Tên|Tên\\s+Chị|Tên\\s+Anh)\\s*\\]", "")
                .replaceAll("(?iU)(?<=\\b(?:chào\\s+anh|chào\\s+chị))\\s+0\\d{8,10}\\b", "")
                .replaceAll("\\s+,", ",")
                // Thay thế xưng hô hành chính "chúng tôi" -> "bên em"
                .replaceAll("(?iU)\\bchúng\\s+tôi\\b", "bên em")
                // SỬA LỖI ĐẢO NGƯỢC ĐẠI TỪ CHỦ NGỮ (Issue #1: "Em có thể tham khảo gói..." -> "Chị/Anh có thể tham khảo gói...")
                .replaceAll("(?iU)(^|(?<=[.!?\\n,;:\\-]\\s*))em\\s+(có\\s+thể\\s+(?:tham\\s+khảo|lựa\\s+chọn|chọn|trải\\s+nghiệm|dùng|sử\\s+dụng|đăng\\s+ký|cân\\s+nhắc)|nên\\s+(?:tham\\s+khảo|lựa\\s+chọn|chọn|dùng|trải\\s+nghiệm)|sẽ\\s+(?:nhận\\s+được|được\\s+tặng|được\\s+giảm|được\\s+hưởng|được\\s+miễn\\s+phí))", "$1" + honorific + " $2")
                // SỬA LỖI GHÉP NHẦM TÊN COMBO VỚI SỐ BUỔI VÀ CHIẾT KHẤU (Cả có và không có ngoặc đơn)
                .replaceAll("(?iU)combo\\s+toàn\\s+diện\\s*(?=\\(?\\s*(?:từ\\s+)?3\\s*[-–~đến]+\\s*4\\s*buổi)", "Combo Tiết Kiệm ")
                .replaceAll("(?iU)combo\\s+tiết\\s+kiệm\\s*(?=\\(?\\s*(?:từ\\s+)?5\\s*[-–~đến]+\\s*9\\s*buổi)", "Combo Toàn Diện ")
                .replaceAll("(?iU)combo\\s+toàn\\s+diện\\s*(?=\\(?\\s*(?:được\\s+)?giảm\\s+10\\s*%\\)?)", "Combo Tiết Kiệm ")
                .replaceAll("(?iU)combo\\s+tiết\\s+kiệm\\s*(?=\\(?\\s*(?:được\\s+)?giảm\\s+15\\s*%\\)?)", "Combo Toàn Diện ")
                // CHỐT CHẶN BẢO VỆ RANH GIỚI Y KHOA: Gói Trị Mụn Chuẩn Y Khoa không điều trị sẹo rỗ
                .replaceAll("(?iU)(?:gói|liệu\\s+trình)?\\s*trị\\s+mụn\\s*(?:chuẩn\\s+y\\s+khoa)?\\s*[^.!?\\n]{0,80}?\\b(?:điều\\s+trị|trị|chữa|chữa\\s+trị|làm\\s+đầy|giải\\s+quyết|xóa|hết|phục\\s+hồi|cải\\s+thiện)[^.!?\\n]{0,40}?\\bsẹo\\s+rỗ",
                        "gói Trị Mụn Chuẩn Y Khoa hỗ trợ làm dịu viêm và xử lý nhân mụn, còn tình trạng sẹo rỗ cần Bác sĩ Da liễu soi da AI 3D trực tiếp để có phác đồ riêng")
                // Xóa chữ ký kết thư kiểu email (Trân trọng, Thân ái, Kính chào...)
                .replaceAll("(?isU)\\n*\\s*(Trân\\s+trọng|Thân\\s+ái|Kính\\s+chào|Kính\\s+thư)\\s*,?\\s*(\\n.*)?$", "")
                // Xóa dấu tiêu đề Markdown ### và dấu đậm ** dư thừa trên Messenger
                .replaceAll("(?m)^#{1,6}\\s+", "")
                .replace("**", "");

        // ĐỒNG NHẤT DANH XƯNG 100% (dùng (?iU)(?<!\p{L})...(?!\p{L}) để bắt chính xác ký tự tiếng Việt có dấu như 'ị')
        if ("Anh".equalsIgnoreCase(honorific)) {
            working = working
                    .replaceAll("(?iU)(?<!\\p{L})anh\\s*/\\s*chị(?!\\p{L})", "Anh")
                    .replaceAll("(?iU)(?<!\\p{L})chị\\s*/\\s*anh(?!\\p{L})", "Anh")
                    .replaceAll("(?iU)(?<!\\p{L})chị(?!\\p{L})", "Anh")
                    .replaceAll("(?iU)(?<!\\p{L})anh\\s*,?\\s+anh(?!\\p{L})", "Anh");
        } else if ("Chị".equalsIgnoreCase(honorific)) {
            working = working
                    .replaceAll("(?iU)(?<!\\p{L})anh\\s*/\\s*chị(?!\\p{L})", "Chị")
                    .replaceAll("(?iU)(?<=\\b(?:chào|của|cho|với|hỗ\\s+trợ|tư\\s+vấn|mời|giúp)\\s+)bạn(?!\\p{L})", "Chị")
                    .replaceAll("(?iU)(?<!\\p{L})bạn(?=\\s+(?:có\\s+thể|muốn|cần|đang|sẽ|nên))", "Chị");
        }
        // Xóa lỗi LLM tưởng nhầm đại từ viết tắt đầu câu của khách ("T", "Mìn", "Mk") là tên riêng ("Dạ Chị T,", "Dạ Anh Mìn,")
        working = working.replaceAll("(?iU)(?<!\\p{L})(anh|chị)\\s+(?:[a-z]|mình|mìn|minh|mk|mik|tui|tớ)(?=[,\\s.!?:;]|$)", "$1");

        // NẾU LÀ LƯỢT HỘI THOẠI THỨ 2 TRỞ ĐI (!isFirstTurn): Xóa triệt để câu chào lặp lại ở đầu tin nhắn
        if (!isFirstTurn) {
            String prev;
            do {
                prev = working;
                working = working
                        .replaceAll("(?isU)^\\s*(?:dạ[,\\s]+)?(?:chào\\s+mừng|hoan\\s+nghênh)\\s+[^.!?\\n]+[.!?~]+\\s*", "")
                        .replaceAll("(?isU)^\\s*(?:dạ[,\\s]+)?(?:em\\s+|bên\\s+em\\s+)?(?:xin\\s+|kính\\s+)?chào\\s+(?:anh|chị|anh/chị|bạn|mình|quý\\s+khách)?(?:\\s+[^!.,~\\n]{1,25})?(?:\\s+ạ|\\s+nha|\\s+nhé)?\\s*[!.,~]*\\s*", "")
                        .replaceAll("(?isU)^\\s*(?:dạ[,\\s]+)?em\\s+rất\\s+vui\\s+được\\s+(?:tư\\s+vấn|hỗ\\s+trợ|đồng\\s+hành|phục\\s+vụ)[^.!?\\n]*[.!?]+\\s*", "")
                        .replaceAll("(?isU)^\\s*(?:dạ[,\\s]+)?(?:em\\s+|bên\\s+em\\s+)?(?:xin\\s+|kính\\s+)?chào\\s+[^!.,~\\n]{1,30}\\s*[!.,~]*\\s*", "")
                        .strip();
            } while (!working.equals(prev) && !working.isBlank());

            if (!working.isBlank()) {
                if (!working.toLowerCase(Locale.ROOT).startsWith("dạ")
                        && !working.toLowerCase(Locale.ROOT).startsWith(honorific.toLowerCase(Locale.ROOT))) {
                    working = "Dạ " + Character.toLowerCase(working.charAt(0)) + working.substring(1);
                }
            }
        } else {
            // Ở lượt đầu tiên (isFirstTurn): Gộp câu chào ngắn đứng riêng lẻ ("Dạ em chào Chị ạ!\n\n...") vào ngay đầu đoạn 1 để giữ đúng chuẩn tối đa 2 bong bóng
            working = working.replaceFirst(
                    "(?isU)^\\s*((?:dạ[,\\s]+)?(?:em\\s+|bên\\s+em\\s+)?(?:xin\\s+|kính\\s+)?chào\\s+[^!.,~\\n]{1,35}[.!~]*)\\s*\\n+\\s*",
                    "$1 "
            );
        }

        // GỌT GIŨA CÂU HỎI KẾT THÚC DỒN DẬP / KHÔ KHAN / LẶP LẠI THEO GIAI ĐOẠN VỚI LỊCH SỬ (Issue #2)
        working = polishClosingQuestions(working, honorific, salesStage, isFirstTurn, history, currentMessage);

        // Bảo đảm 100% không sót từ "Chị" / "chị" khi xưng hô là "Anh"
        if ("Anh".equalsIgnoreCase(honorific)) {
            working = working.replaceAll("(?iU)(?<!\\p{L})chị(?!\\p{L})", "Anh");
        }

        return working
                .replaceAll(" +([.,;!?])", "$1")
                .replaceAll("[ \\t]{2,}", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    static String polishClosingQuestions(String text, String honorific) {
        return polishClosingQuestions(text, honorific, FanpageBotPromptBuilder.SalesStage.DISCOVERY, true, null, null);
    }

    static String polishClosingQuestions(
            String text,
            String honorific,
            FanpageBotPromptBuilder.SalesStage salesStage,
            boolean isFirstTurn) {
        return polishClosingQuestions(text, honorific, salesStage, isFirstTurn, null, null);
    }

    static String polishClosingQuestions(
            String text,
            String honorific,
            FanpageBotPromptBuilder.SalesStage salesStage,
            boolean isFirstTurn,
            List<FanpageBotRequest.ConversationTurn> history) {
        return polishClosingQuestions(text, honorific, salesStage, isFirstTurn, history, null);
    }

    /**
     * Loại bỏ các câu hỏi xin phép máy móc, khô khan kiểu dịch tiếng Anh ở cuối tin nhắn,
     * thay thế câu hỏi lặp lại và luân chuyển câu hỏi kết thúc qua lịch sử để không trùng lặp.
     */
    static String polishClosingQuestions(
            String text,
            String honorific,
            FanpageBotPromptBuilder.SalesStage salesStage,
            boolean isFirstTurn,
            List<FanpageBotRequest.ConversationTurn> history,
            String currentMessage) {
        if (text == null || text.isBlank()) {
            return "";
        }

        FanpageBotPromptBuilder.ConversationBookingState bookingState =
                FanpageBotPromptBuilder.analyzeConversationBookingState(currentMessage, history);

        if (bookingState.isRedundantQuestionComplaint() || bookingState.isPostBookingWrapUp()) {
            return text.replaceAll(
                    "(?isU)(?:\\n\\s*\\n|(?<=[.!?])\\s+)(?:dạ\\s+)?[^.!?\\n]*(?:sáng\\s+hay\\s+chiều|ngày\\s+thường\\s+hay\\s+cuối\\s+tuần|xin\\s+khung\\s+giờ|đặt\\s+lịch\\s+qua\\s+soi\\s+da|giữ\\s+lịch\\s+trước|nhận\\s+cuộc\\s+gọi\\s+xác\\s+nhận|muốn\\s+đổi\\s+sang\\s+khung)[^.!?]*(?:\\?|!|\\.)\\s*$",
                    ""
            ).strip();
        }

        List<String> pastAssistantTexts = new ArrayList<>();
        if (history != null) {
            for (FanpageBotRequest.ConversationTurn turn : history) {
                if (turn != null && "assistant".equalsIgnoreCase(turn.role()) && turn.content() != null) {
                    pastAssistantTexts.add(turn.content().toLowerCase(Locale.ROOT));
                }
            }
        }

        List<String> questionPool;
        if (bookingState.hasConfirmedTimeSlot()) {
            String slotLabel = bookingState.confirmedTimeSlot();
            String dateSuffix = bookingState.confirmedDate() != null ? " (" + bookingState.confirmedDate() + ")" : "";
            questionPool = List.of(
                    honorific + " cho em xin Số điện thoại để bên em khóa lịch hẹn lúc " + slotLabel + dateSuffix
                            + " và giữ phòng riêng kín đáo cùng Bác sĩ cho mình nhé ạ?"
            );
            text = text.replaceAll(
                    "(?isU)(?:\\n\\s*\\n|(?<=[.!?])\\s+)(?:dạ\\s+)?[^.!?\\n]*(?:sáng\\s+hay\\s+chiều|ngày\\s+thường\\s+hay\\s+cuối\\s+tuần|xin\\s+khung\\s+giờ\\s+tiện\\s+nhất|muốn\\s+đổi\\s+sang\\s+khung)[^.!?]*(?:\\?|!|\\.)\\s*$",
                    ""
            ).strip();
        } else if (!isFirstTurn || (salesStage != null && salesStage != FanpageBotPromptBuilder.SalesStage.DISCOVERY)) {
            if (salesStage == FanpageBotPromptBuilder.SalesStage.SOFT_BOOKING_CLOSE) {
                questionPool = List.of(
                        honorific + " muốn đặt lịch vào khung giờ Sáng hay Chiều để bên em giữ lịch trước cho mình ạ?",
                        honorific + " tiện ghé cơ sở bên em vào ngày thường hay cuối tuần để bên em sắp xếp đón tiếp chu đáo nhất ạ?",
                        honorific + " cho em xin khung giờ tiện nhất hoặc Số điện thoại để bên em giữ suất ưu đãi trước cho mình ạ?"
                );
            } else {
                questionPool = List.of(
                        "Không biết " + honorific + " muốn trải nghiệm gói cơ bản trước hay cần em tư vấn kỹ hơn về quy trình các bước ạ?",
                        "Không biết " + honorific + " muốn trải nghiệm lẻ 1 buổi trước hay tham khảo theo gói ưu đãi để tiết kiệm chi phí nhất ạ?",
                        honorific + " muốn bên em giữ lịch trải nghiệm vào khung giờ Sáng hay Chiều để em lên lịch cho mình ạ?"
                );
            }
        } else {
            questionPool = List.of(
                    "Hiện tại " + honorific + " đang muốn ưu tiên trải nghiệm dịch vụ nào nhất để em gợi ý gói phù hợp nhất cho mình ạ?",
                    "Không biết hiện tại " + honorific + " đang muốn trải nghiệm thư giãn cơ bản hay liệu trình chăm sóc chuyên sâu hơn ạ?"
            );
        }

        // Chọn câu hỏi trong pool chưa từng xuất hiện trong các câu trả lời trước của assistant
        String naturalClosing = questionPool.get(0);
        for (String candidate : questionPool) {
            String normCand = normalizeQuestionForComparison(candidate);
            String candKey = normCand.length() > 30 ? normCand.substring(0, 30) : normCand;
            boolean alreadyAsked = pastAssistantTexts.stream().anyMatch(past -> {
                String normPast = normalizeQuestionForComparison(past);
                return normPast.contains(candKey);
            });
            if (!alreadyAsked) {
                naturalClosing = candidate;
                break;
            }
        }

        // 0. Nếu đã sang lượt thứ 2+ (!isFirstTurn) mà LLM vẫn lặp lại câu hỏi khảo sát lượt 1 ở cuối -> thay bằng câu hỏi luân chuyển
        if (!isFirstTurn) {
            Pattern repeatedTurn1QuestionPattern = Pattern.compile(
                    "(?isU)(?:\\n\\s*\\n|\\s+)(?:hiện\\s+tại\\s+)?da\\s+(?:của\\s+)?(?:anh|chị|bạn|mình)?\\s*(?:đang\\s*)?cần\\s+ưu\\s+tiên\\s+cải\\s+thiện[^?]*\\?\\s*$"
            );
            Matcher repMatcher = repeatedTurn1QuestionPattern.matcher(text);
            if (repMatcher.find()) {
                String prefixBeforeQuestion = text.substring(0, repMatcher.start()).strip();
                String sep = repMatcher.group().contains("\n") ? "\n\n" : " ";
                text = prefixBeforeQuestion + sep + naturalClosing;
            }
        }

        // 1. Bắt trực diện cặp câu hỏi xin phép máy móc hoặc câu hỏi đóng Yes/No ("Em có thể tư vấn thêm... không?", "Chị muốn em hỗ trợ đặt lịch... không ạ?")
        Pattern dryPermissionQuestionPattern = Pattern.compile(
                "(?iU)(?:\\s*(?:em\\s+có\\s+thể\\s+(?:tư\\s+vấn|giải\\s+thích|hỗ\\s+trợ|chia\\s+sẻ)\\s+thêm[^?]*\\?|"
                + "(?:chị|anh|anh/chị|bạn|mình)\\s+(?:có\\s+)?muốn\\s+(?:bên\\s+)?em\\s+(?:tư\\s+vấn|giải\\s+thích|chia\\s+sẻ|gửi|hỗ\\s+trợ|đặt\\s+lịch|giữ\\s+lịch)(?!.*\\bhay\\b)[^?]*\\?|"
                + "(?:chị|anh|anh/chị|bạn|mình)\\s+(?:có\\s+)?cần\\s+(?:bên\\s+)?em\\s+(?:tư\\s+vấn|giải\\s+thích|hỗ\\s+trợ)(?!.*\\bhay\\b)[^?]*\\?))+"
        );
        Matcher m = dryPermissionQuestionPattern.matcher(text);
        if (m.find()) {
            String matchedGroup = m.group();
            String withoutDryQuestions = m.replaceAll("").strip();
            if (withoutDryQuestions.endsWith("?")) {
                return withoutDryQuestions;
            }
            if (withoutDryQuestions.isEmpty()) {
                return "Dạ " + naturalClosing;
            }
            String separator = matchedGroup.contains("\n") ? "\n\n" : " ";
            return withoutDryQuestions + separator + naturalClosing;
        }

        // 2. Nếu đoạn văn cuối cùng vẫn có từ 2 câu hỏi liên tiếp trở lên (dồn dập), chỉ giữ lại câu hỏi đầu tiên
        int lastParaBreak = text.lastIndexOf("\n\n");
        String prefix = lastParaBreak >= 0 ? text.substring(0, lastParaBreak + 2) : "";
        String lastPara = lastParaBreak >= 0 ? text.substring(lastParaBreak + 2) : text;

        int firstQ = lastPara.indexOf('?');
        if (firstQ >= 0 && firstQ < lastPara.length() - 1) {
            int secondQ = lastPara.indexOf('?', firstQ + 1);
            if (secondQ > firstQ) {
                lastPara = lastPara.substring(0, firstQ + 1).strip();
                return prefix + lastPara;
            }
        }

        // 3. Nếu là lượt 2+ và câu hỏi cuối cùng của text trùng lặp với câu hỏi assistant đã hỏi trong quá khứ -> thay thế
        if (!isFirstTurn && !pastAssistantTexts.isEmpty() && text.endsWith("?")) {
            int qStart = Math.max(text.lastIndexOf("\n\n"), Math.max(text.lastIndexOf(". "), text.lastIndexOf("! ")));
            if (qStart > 0) {
                String trailingQuestion = text.substring(qStart).toLowerCase(Locale.ROOT);
                String normTrailing = normalizeQuestionForComparison(trailingQuestion);
                String trailingKey = normTrailing.length() > 25 ? normTrailing.substring(0, 25) : normTrailing;
                if (!trailingKey.isBlank()) {
                    boolean exactPastMatch = pastAssistantTexts.stream().anyMatch(past -> {
                        String normPast = normalizeQuestionForComparison(past);
                        return normPast.contains(trailingKey);
                    });
                    if (exactPastMatch) {
                        text = text.substring(0, qStart).strip() + " " + naturalClosing;
                    }
                }
            }
        }

        // 4. Nếu ở bước chốt lịch (SOFT_BOOKING_CLOSE) hoặc khách gật đầu đồng ý thử ("Ok thử xem sao")
        // mà câu trả lời hoàn toàn thiếu dấu hỏi chấm ở cuối -> bắt buộc nối thêm câu hỏi điều hướng đặt lịch
        if (!text.strip().endsWith("?")
                && (salesStage == FanpageBotPromptBuilder.SalesStage.SOFT_BOOKING_CLOSE
                    || FanpageBotPromptBuilder.isTrialOrBookingAcceptanceMessage(currentMessage))) {
            text = text.strip() + "\n\n" + naturalClosing;
        }

        return text;
    }

    private static String normalizeQuestionForComparison(String q) {
        if (q == null) {
            return "";
        }
        return q.toLowerCase(Locale.ROOT)
                .replaceAll("(?iU)\\b(anh|chị|bạn|mình|em)\\b", "")
                .replaceAll("[^\\p{L}\\p{Nd}]+", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }


    /**
     * Tách phản hồi thành 1-3 bong bóng tin nhắn ngắn gọn (kiểu ResultDecorateStage của AstrBot)
     * kèm độ trễ gõ phím tự nhiên (typing_delay_ms) để tích hợp gửi tuần tự qua Facebook Send API.
     */
    static List<FanpageBotResponse.ReplyBubble> splitIntoBubbles(String cleanText) {
        if (cleanText == null || cleanText.isBlank()) {
            return List.of();
        }
        String[] rawParagraphs = cleanText.split("\\n\\s*\\n");
        List<String> bubbleTexts = new ArrayList<>();
        for (String p : rawParagraphs) {
            String trimmed = p.strip();
            if (!trimmed.isEmpty()) {
                bubbleTexts.add(trimmed);
            }
        }

        // Nếu bong bóng đầu tiên chỉ là câu chào xã giao ngắn (<= 40 ký tự), gộp vào bong bóng kế tiếp để giữ nhịp 2 bong bóng
        if (bubbleTexts.size() >= 2 && bubbleTexts.get(0).length() <= 40
                && bubbleTexts.get(0).matches("(?isU)^(?:dạ[,\\s]*)?(?:em\\s+|bên\\s+em\\s+)?(?:xin\\s+|kính\\s+)?chào\\b[^?]*$")) {
            String greeting = bubbleTexts.remove(0);
            bubbleTexts.set(0, greeting + " " + bubbleTexts.get(0));
        }

        // Nếu chỉ có 1 đoạn dài hơn 220 ký tự, tách nhẹ câu hỏi chốt ở cuối thành bong bóng thứ 2
        if (bubbleTexts.size() == 1 && bubbleTexts.get(0).length() > 220) {
            String single = bubbleTexts.get(0);
            int lastQuestionSplit = findLastSentenceBoundaryBeforeQuestion(single);
            if (lastQuestionSplit > 60 && lastQuestionSplit < single.length() - 25) {
                bubbleTexts.clear();
                bubbleTexts.add(single.substring(0, lastQuestionSplit).strip());
                bubbleTexts.add(single.substring(lastQuestionSplit).strip());
            }
        }

        // Giới hạn tối đa 3 bong bóng để không spam thông báo Messenger của khách
        while (bubbleTexts.size() > 3) {
            String last = bubbleTexts.remove(bubbleTexts.size() - 1);
            int prevIdx = bubbleTexts.size() - 1;
            bubbleTexts.set(prevIdx, bubbleTexts.get(prevIdx) + "\n" + last);
        }

        List<FanpageBotResponse.ReplyBubble> bubbles = new ArrayList<>(bubbleTexts.size());
        for (int i = 0; i < bubbleTexts.size(); i++) {
            String text = bubbleTexts.get(i);
            int delayMs;
            if (i == 0) {
                delayMs = Math.min(450, Math.max(250, text.length() * 4));
            } else {
                delayMs = Math.min(600, Math.max(350, text.length() * 5));
            }
            bubbles.add(new FanpageBotResponse.ReplyBubble(text, delayMs));
        }
        return bubbles;
    }

    private static int findLastSentenceBoundaryBeforeQuestion(String text) {
        if (!text.endsWith("?") && !text.endsWith("ạ?") && !text.endsWith("nhé!")) {
            return -1;
        }
        int lastDot = Math.max(text.lastIndexOf(". "), text.lastIndexOf("! "));
        return lastDot > 0 ? lastDot + 2 : -1;
    }

    /**
     * Bóc tách tín hiệu mua hàng / đặt lịch (Số điện thoại, Khung giờ hẹn) từ tin nhắn hiện tại kết hợp lịch sử hội thoại.
     */
    static FanpageBotResponse.ExtractedLead extractLeadSignal(String message, List<FanpageBotRequest.ConversationTurn> history) {
        FanpageBotResponse.ExtractedLead current = extractLeadSignal(message);
        if (history == null || history.isEmpty()) {
            return current;
        }
        FanpageBotPromptBuilder.ConversationBookingState state =
                FanpageBotPromptBuilder.analyzeConversationBookingState(message, history);
        String phone = current.phoneNumber() != null ? current.phoneNumber() : state.capturedPhone();
        String timeText = current.preferredTime() != null ? current.preferredTime() : state.confirmedTimeSlot();
        if (timeText != null && state.confirmedDate() != null && !timeText.toLowerCase(Locale.ROOT).contains(state.confirmedDate().toLowerCase(Locale.ROOT))) {
            timeText = timeText + " (" + state.confirmedDate() + ")";
        }
        boolean hasSignal = current.hasBuyingOrBookingSignal() || phone != null || timeText != null;
        String summary = null;
        if (hasSignal) {
            List<String> notes = new ArrayList<>();
            if (phone != null) notes.add("SĐT: " + phone);
            if (timeText != null) notes.add("Khung giờ hẹn: " + timeText);
            if (notes.isEmpty()) notes.add("Khách thể hiện ý định đặt lịch / đăng ký dịch vụ");
            summary = String.join(" · ", notes);
        }
        return new FanpageBotResponse.ExtractedLead(hasSignal, phone, timeText, summary);
    }

    /**
     * Bóc tách tín hiệu mua hàng / đặt lịch (Số điện thoại, Khung giờ hẹn) từ tin nhắn của khách.
     */
    static FanpageBotResponse.ExtractedLead extractLeadSignal(String message) {
        if (message == null || message.isBlank()) {
            return new FanpageBotResponse.ExtractedLead(false, null, null, null);
        }
        String phone = null;
        Matcher phoneMatcher = VN_PHONE_PATTERN.matcher(message);
        if (phoneMatcher.find()) {
            phone = phoneMatcher.group().replaceAll("[.\\-\\s]", "");
        }

        String timeText = null;
        Matcher timeMatcher = BOOKING_TIME_PATTERN.matcher(message);
        if (timeMatcher.find()) {
            timeText = timeMatcher.group().strip();
        }

        boolean hasSignal = phone != null || timeText != null
                || BUYING_OR_BOOKING_SIGNAL_PATTERN.matcher(message).find();

        String summary = null;
        if (hasSignal) {
            List<String> notes = new ArrayList<>();
            if (phone != null) notes.add("SĐT: " + phone);
            if (timeText != null) notes.add("Khung giờ hẹn: " + timeText);
            if (notes.isEmpty()) notes.add("Khách thể hiện ý định đặt lịch / đăng ký dịch vụ");
            summary = String.join(" · ", notes);
        }

        return new FanpageBotResponse.ExtractedLead(hasSignal, phone, timeText, summary);
    }

    private FanpageBotResponse buildHandoffResponse(
            String replyText,
            String category,
            String reason,
            double confidence,
            FanpageBotResponse.ExtractedLead extractedLead,
            CustomerInsightDto customerInsight,
            List<AgentSkillDto> activeSkills) {
        FanpageBotResponse base = new FanpageBotResponse(
                replyText,
                splitIntoBubbles(replyText),
                "HUMAN_HANDOFF",
                confidence,
                true,
                category,
                reason,
                extractedLead,
                List.of(),
                customerInsight,
                activeSkills
        );
        return applyGuardrails(base, "Chị", null, false, "HUMAN_HANDOFF", "", null, null);
    }

    private FanpageBotResponse applyGuardrails(
            FanpageBotResponse response,
            String customerHonorific,
            String customerName,
            boolean isFirstTurn,
            String salesStage,
            String customerMessage,
            List<FanpageBotRequest.ConversationTurn> history,
            List<ReciprocalRankFusion.FusedChunk> fused) {
        if (response == null) {
            return null;
        }

        List<String> historyTexts = new ArrayList<>();
        if (history != null) {
            for (FanpageBotRequest.ConversationTurn turn : history) {
                if (turn != null && turn.content() != null) {
                    String rolePrefix = (turn.role() != null && !turn.role().isBlank())
                            ? turn.role().strip().toLowerCase(Locale.ROOT) + ": "
                            : "";
                    historyTexts.add(rolePrefix + turn.content());
                }
            }
        }

        List<String> chunkTexts = new ArrayList<>();
        if (fused != null) {
            for (ReciprocalRankFusion.FusedChunk fc : fused) {
                if (fc.chunk() != null && fc.chunk().text() != null) {
                    chunkTexts.add(fc.chunk().text());
                }
            }
        }

        String effectiveStage = (response.needsHumanHandoff() && response.handoffCategory() != null)
                ? response.handoffCategory()
                : salesStage;

        GuardrailContext ctx = GuardrailContext.builder()
                .customerHonorific(customerHonorific)
                .customerName(customerName)
                .isFirstTurn(isFirstTurn)
                .salesStage(effectiveStage)
                .customerMessage(customerMessage)
                .conversationHistory(historyTexts)
                .knowledgeChunks(chunkTexts)
                .build();

        return guardrailPipeline.process(ctx, response);
    }

    private static double computeConfidenceScore(List<ReciprocalRankFusion.FusedChunk> fused) {
        if (fused == null || fused.isEmpty()) {
            return 0.0;
        }
        double topRrf = fused.get(0).score();
        // Chuẩn hóa điểm RRF (thường ~0.016 - 0.033 với k=60) sang thang 0.70 - 0.96
        double normalized = Math.min(0.96, 0.65 + (topRrf * 8.5));
        return Math.round(normalized * 100.0) / 100.0;
    }

    private PromptBuilder.PagePersona resolvePagePersona(UUID pageId) {
        if (pageId == null || knowledgePageRepository == null) {
            return null;
        }
        try {
            return knowledgePageRepository.findById(pageId)
                    .map(p -> new PromptBuilder.PagePersona(
                            p.getName(),
                            p.getIndustry(),
                            p.getTargetAudience(),
                            p.getDescription()
                    ))
                    .orElse(null);
        } catch (Exception e) {
            log.warn("Không thể tải PagePersona cho FanpageBot page_id={}: {}", pageId, e.getMessage());
            return null;
        }
    }

    private static UUID parsePageId(String pageIdRaw) {
        if (pageIdRaw == null || pageIdRaw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(pageIdRaw.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
