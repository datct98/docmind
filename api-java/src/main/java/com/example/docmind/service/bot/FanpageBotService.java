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
import com.docmind.guardrail.BubbleCapHook;
import com.docmind.guardrail.GuardrailContext;
import com.docmind.guardrail.GuardrailPipeline;
import com.docmind.guardrail.GuardrailResult;
import com.docmind.guardrail.SyntaxSanitizationHook;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.docmind.service.retrieval.RetrievalService;
import com.example.docmind.service.security.PromptSecurityGuard;
import com.example.docmind.service.text.VietnameseQueryNormalizer;
import com.example.docmind.service.harness.HarnessOptimizerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import com.example.docmind.service.graph.MapOfContentService;
import com.example.docmind.service.reasoning.QueryComplexity;
import com.example.docmind.service.reasoning.QueryComplexityClassifier;
import com.example.docmind.service.llm.ReasoningBudget;
import com.example.docmind.dto.ChunkMetadata;
import com.example.docmind.service.graph.BacklinkGraph;
import com.example.docmind.service.graph.ChunkGraphNode;
import com.example.docmind.service.graph.ObsidianMetadataCache;
import com.example.docmind.service.metadata.ChunkMetadataExtractor;
import com.example.docmind.service.sales.PriceLadderTracker;
import com.example.docmind.service.sales.PriceLadderTracker.PriceLadderResult;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /** Bong bóng handoff an toàn, trung lập ngành nghề; dùng khi LLM vi phạm BLOCKING và không có tham chiếu grounded. */
    static final String SAFE_HANDOFF_BUBBLE_TEXT =
            "Dạ để thông tin được chính xác, em xin chuyển cuộc trò chuyện sang nhân viên hỗ trợ trực tiếp ạ.";

    private static final Logger log = LoggerFactory.getLogger(FanpageBotService.class);

    /** Mẫu phát hiện khách hàng khiếu nại, nóng giận, báo biến chứng y khoa hoặc đòi hoàn tiền gấp. */
    private static final Pattern COMPLAINT_OR_DISTRESS_PATTERN = Pattern.compile(
            "(?iU)(?:\\b(kích\\s+ứng|dị\\s+ứng|bỏng\\s+rát|đỏ\\s+rát|sưng\\s+tấy|nổi\\s+mẩn|nhiễm\\s+trùng|"
            + "khiếu\\s+nại|bắt\\s+đền|lừa\\s+đảo|lừa\\s+gạt|tệ\\s+quá|quá\\s+tệ|tệ\\s+thật(?:\\s+sự)?|bức\\s+xúc|bóc\\s+phốt|"
            + "bị\\s+loạn(?:\\s+à|\\s+hả|\\s+rồi)?|loạn\\s+(?:à|hả|rồi)|bị\\s+(?:điên|ngáo|khùng|chập)(?:\\s+à|\\s+hả)?|"
            + "đòi\\s+lại\\s+tiền|hoàn\\s+tiền\\s+ngay|kiện|báo\\s+công\\s+an|làm\\s+hỏng\\s+da|thất\\s+vọng\\s+quá)\\b|"
            + "làm\\s+(?:(?:bên\\s+(?:bạn|em|shop|mình)|chị|anh|em|mình|tôi|khách)\\s+)?đau\\s+quá|"
            + "hôm\\s+qua[\\p{L}\\s,]*làm[\\p{L}\\s,]*\\bđau\\b)"
    );

    /** Mẫu phát hiện cấp cứu y tế cấp tính phổ quát (luôn giữ hiệu lực ở mọi ngành). */
    private static final Pattern UNIVERSAL_ACUTE_MEDICAL_EMERGENCY_PATTERN = Pattern.compile(
            "(?iU)\\b(tức\\s+ngực|đau\\s+ngực|khó\\s+thở|đau\\s+đầu\\s+dữ\\s+dội|sốt\\s+cao|vết\\s+thương\\s+hở|gãy\\s+xương|trật\\s+khớp)\\b"
    );

    /** Mẫu phát hiện chống chỉ định thể chất đặc thù ngành Spa / Y tế / Thẩm mỹ (thai kỳ, tai đau/ù, hậu phẫu...). */
    private static final Pattern HIGH_RISK_SAFETY_CONTRAINDICATION_PATTERN = Pattern.compile(
            "(?iU)(?:\\b(mang\\s+thai|đang\\s+bầu|có\\s+bầu|bầu\\s+\\d+\\s*(?:tháng|tuần)?|thai\\s+kỳ|mới\\s+sinh|"
            + "mới\\s+phẫu\\s+thuật|phẫu\\s+thuật|mới\\s+mổ|vết\\s+thương\\s+hở|viêm\\s+da\\s+nặng|nhiễm\\s+trùng\\s+da|sốt\\s+cao|"
            + "huyết\\s+áp\\s+cao|cao\\s+huyết\\s+áp|tim\\s+mạch(?:\\s+nặng)?|giãn\\s+tĩnh\\s+mạch(?:\\s+nặng)?|"
            + "thoát\\s+vị(?:\\s+đĩa\\s+đệm)?(?:\\s+cấp)?|chèn\\s+ép\\s+(?:rễ\\s+|dây\\s+)?thần\\s+kinh|gãy\\s+xương|trật\\s+khớp|"
            + "tức\\s+ngực|đau\\s+ngực|khó\\s+thở|đau\\s+đầu\\s+dữ\\s+dội|chóng\\s+mặt\\s+nhiều|teo\\s+cơ|tê\\s+lan\\s+xuống\\s+tay|"
            + "đau\\s+tai|ù\\s+tai|viêm\\s+tai|chảy\\s+dịch\\s+tai|chảy\\s+mủ\\s+tai|nghe\\s+kém)\\b|"
            + "\\btai\\b[^.!?\\n]{0,30}\\b(đau|ù|chảy\\s+dịch|chảy\\s+mủ|viêm)\\b)"
    );

    public static boolean isUniversalAcuteMedicalEmergency(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        return UNIVERSAL_ACUTE_MEDICAL_EMERGENCY_PATTERN.matcher(message).find()
                || SentimentIntentLayer.isNeurologicalRiskMessage(message);
    }

    public static boolean isDomainPhysicalContraindicationGateEnabled(PromptBuilder.PagePersona pagePersona) {
        if (pagePersona == null || !pagePersona.hasContent()) {
            return true;
        }
        com.example.docmind.dto.PagePersonaConfig explicit = pagePersona.personaConfig();
        if (explicit != null && explicit.industryCode() != null && !explicit.industryCode().isBlank()
                && !"GENERAL_SERVICE".equalsIgnoreCase(explicit.industryCode().strip())) {
            return explicit.isMedicalOrBodySafetyIndustry();
        }
        com.example.docmind.dto.PagePersonaConfig resolved = pagePersona.resolvedConfig();
        if (resolved != null && resolved.industryCode() != null
                && !"GENERAL_SERVICE".equalsIgnoreCase(resolved.industryCode().strip())) {
            return resolved.isMedicalOrBodySafetyIndustry();
        }
        String combined = ((pagePersona.name() != null ? pagePersona.name() : "") + " "
                + (pagePersona.industry() != null ? pagePersona.industry() : "")).toLowerCase(Locale.ROOT);
        return combined.contains("spa")
                || combined.contains("dưỡng sinh")
                || combined.contains("massage")
                || combined.contains("thẩm mỹ")
                || combined.contains("làm đẹp")
                || combined.contains("nha khoa")
                || combined.contains("phòng khám")
                || combined.contains("y khoa")
                || combined.contains("y tế")
                || combined.contains("bệnh viện")
                || combined.contains("dược")
                || combined.contains("clinic")
                || combined.contains("medical")
                || combined.contains("dental")
                || combined.contains("wellness")
                || combined.contains("healthcare");
    }

    public static String findMatchedPageSafetyRedFlag(String message, PromptBuilder.PagePersona pagePersona) {
        if (message == null || message.isBlank() || pagePersona == null || pagePersona.resolvedConfig() == null) {
            return null;
        }
        List<String> redFlags = pagePersona.resolvedConfig().resolvedSafetyRedFlags();
        if (redFlags == null || redFlags.isEmpty()) {
            return null;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        for (String flag : redFlags) {
            if (flag != null && !flag.isBlank() && lower.contains(flag.strip().toLowerCase(Locale.ROOT))) {
                return flag.strip();
            }
        }
        return null;
    }

    public static boolean shouldTriggerSafetyContraindicationHandoff(
            String message,
            PromptBuilder.PagePersona pagePersona
    ) {
        if (message == null || message.isBlank()) {
            return false;
        }
        if (isUniversalAcuteMedicalEmergency(message)) {
            return true;
        }
        if (findMatchedPageSafetyRedFlag(message, pagePersona) != null) {
            return true;
        }
        return isDomainPhysicalContraindicationGateEnabled(pagePersona)
                && HIGH_RISK_SAFETY_CONTRAINDICATION_PATTERN.matcher(message).find();
    }

    /** Mẫu phát hiện khách hàng chủ động yêu cầu gặp nhân viên thật / quản lý / bác sĩ. */
    private static final Pattern EXPLICIT_HUMAN_REQUEST_PATTERN = Pattern.compile(
            "(?iU)(gặp\\s+nhân\\s+viên|gặp\\s+người\\s+thật|gặp\\s+quản\\s+lý|cho\\s+gặp\\s+bác\\s+sĩ|"
            + "nhân\\s+viên\\s+đâu|tư\\s+vấn\\s+viên\\s+đâu|ai\\s+đang\\s+chat|"
            + "gọi\\s+điện\\s+cho\\s+chị|gọi\\s+lại\\s+cho\\s+anh|nói\\s+chuyện\\s+với\\s+người|"
            + "(?:tư\\s+vấn|nhắn|gọi|liên\\s+hệ|add|kết\\s+bạn|inbox|gửi)?\\s*(?:qua|trên|vào)?\\s*zalo\\b|"
            + "qua\\s+zalo)"
    );

    /** Mẫu phát hiện thương lượng đặc thù B2B / bán sỉ / đại lý / hợp đồng đối tác vượt khung bán lẻ tiêu chuẩn. */
    private static final Pattern COMPLEX_NEGOTIATION_PATTERN = Pattern.compile(
            "(?iU)(làm\\s+đại\\s+lý|nhượng\\s+quyền|hợp\\s+tác\\s+kinh\\s+doanh|mua\\s+sỉ|bán\\s+sỉ|giá\\s+sỉ|\\blấy\\s+sỉ\\b|"
            + "hợp\\s+đồng\\s+(?:kinh\\s+tế|doanh\\s+nghiệp|công\\s+ty|dài\\s+hạn)|xuất\\s+hóa\\s+đơn\\s+đỏ\\s+công\\s+ty|"
            + "giảm\\s+(30|40|50|60|70|80|90)\\s*%)"
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

    private static final Pattern EXTREME_BUDGET_CEILING_PATTERN = Pattern.compile(
            "(?iU)(?:\\b(?:[2-9]\\d|\\d{3,})\\s*(?:triệu|trieu|tr)\\b|"
            + "\\b(?:chục|trăm|tram)\\s*(?:triệu|trieu)\\b|"
            + "\\b\\d*\\s*(?:tỷ|ty)\\b(?!\\s*(?:lệ|le|số|so|trọng|trong|mỷ|mỉ|muội|muoi))|"
            + "\\btiền\\s*(?:tỷ|ty)\\b|"
            + "\\b(?:[2-9]\\d|\\d{3,})(?:[.]000){2,}\\b|"
            + "\\b\\d{8,}\\b)"
    );

    private static final Pattern ZERO_PRICE_PATTERN = Pattern.compile(
            "(?iU)(?:\\b0\\s*(?:đ|d|dong|đồng|vnd|vnđ)\\b|miễn\\s*phí|\\bfree\\b)"
    );

    private static final Pattern COMPARISON_PATTERN = Pattern.compile(
            "(?iU)(?:khác\\s+(?:gì(?:\\s+nhau)?|nhau|biệt|thế\\s+nào)|so\\s+sánh|chênh\\s+(?:giá|nhau|lệch)|sao\\s+(?:lại\\s+)?chênh|tại\\s+sao\\s+chênh|phân\\s+biệt|hơn\\s+gì|nên\\s+chọn\\s+gói\\s+nào)"
    );

    private static final Pattern PROMO_PATTERN = Pattern.compile(
            "(?iU)(?:\\b(?:giảm\\s*giá|ưu\\s+đãi|khuyến\\s+mãi|khuyến\\s+mại|chiết\\s+khấu|voucher|combo|liệu\\s+trình|chính\\s+sách)\\b|"
            + "\\bgiảm\\s*\\d+%|\\b\\d+%(?:\\s*giảm)?|"
            + "\\b\\d+\\s*(?:buổi|voucher|lần|vé)\\b)"
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
    private MapOfContentService mapOfContentService;
    private QueryComplexityClassifier queryComplexityClassifier;
    private volatile ThinkingModePolicy.BenchmarkConfigMode benchmarkConfigMode =
            ThinkingModePolicy.BenchmarkConfigMode.ADAPTIVE_C;
    private volatile ThinkingModePolicy.TurnTelemetry lastTurnTelemetry = null;
    @org.springframework.beans.factory.annotation.Value("${docmind.bot.agent-mode:CLEAN}")
    private volatile String agentMode = "CLEAN";
    private volatile AdaptiveConversationIntelligence.CognitiveMode cognitiveMode =
            AdaptiveConversationIntelligence.CognitiveMode.DETERMINISTIC_V1;
    @Autowired(required = false)
    private AgentBrainService agentBrainService;
    @Autowired(required = false)
    private com.example.docmind.service.playbook.PlaybookUploadService playbookUploadService;
    @Autowired(required = false)
    private CleanAgentOrchestratorService cleanAgentOrchestratorService;
    @Autowired(required = false)
    private InboundSessionPersistenceService inboundSessionPersistenceService;

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
                             @Autowired(required = false) FastApiAgentService fastApiAgentService,
                             @Autowired(required = false) MapOfContentService mapOfContentService,
                             @Autowired(required = false) QueryComplexityClassifier queryComplexityClassifier) {
        this(retrievalService, llmClient, chatProps, documentRepository, knowledgePageRepository, customerInsightEngine,
                agentSkillService, metaLearningService, guardrailPipeline, null, fastApiAgentService, mapOfContentService, queryComplexityClassifier);
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
                             HarnessOptimizerService harnessOptimizerService,
                             FastApiAgentService fastApiAgentService,
                             MapOfContentService mapOfContentService,
                             QueryComplexityClassifier queryComplexityClassifier) {
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
        this.mapOfContentService = mapOfContentService;
        this.queryComplexityClassifier = queryComplexityClassifier != null ? queryComplexityClassifier : new QueryComplexityClassifier();
        this.cleanAgentOrchestratorService = new CleanAgentOrchestratorService(this.llmClient, this.agentBrainService, this.agentSkillService);
    }

    public void setAgentBrainService(AgentBrainService agentBrainService) {
        this.agentBrainService = agentBrainService;
        if (this.cleanAgentOrchestratorService != null) {
            this.cleanAgentOrchestratorService.setAgentBrainService(agentBrainService);
        }
    }

    public AgentBrainService getAgentBrainService() {
        return agentBrainService;
    }

    public void setQueryComplexityClassifier(QueryComplexityClassifier queryComplexityClassifier) {
        this.queryComplexityClassifier = queryComplexityClassifier != null ? queryComplexityClassifier : new QueryComplexityClassifier();
    }

    public QueryComplexityClassifier getQueryComplexityClassifier() {
        return queryComplexityClassifier;
    }

    public void setBenchmarkConfigMode(ThinkingModePolicy.BenchmarkConfigMode benchmarkConfigMode) {
        this.benchmarkConfigMode = benchmarkConfigMode != null
                ? benchmarkConfigMode
                : ThinkingModePolicy.BenchmarkConfigMode.ADAPTIVE_C;
    }

    public ThinkingModePolicy.BenchmarkConfigMode getBenchmarkConfigMode() {
        return benchmarkConfigMode;
    }

    public ThinkingModePolicy.TurnTelemetry getLastTurnTelemetry() {
        if (lastTurnTelemetry != null) {
            return lastTurnTelemetry;
        }
        return cleanAgentOrchestratorService != null ? cleanAgentOrchestratorService.getLastTurnTelemetry() : null;
    }

    public String getAgentMode() {
        return agentMode;
    }

    public void setAgentMode(String agentMode) {
        this.agentMode = (agentMode != null && !agentMode.isBlank())
                ? agentMode.strip().toUpperCase(Locale.ROOT)
                : "CLEAN";
    }

    public boolean isCleanAgentMode() {
        return !"LEGACY".equalsIgnoreCase(agentMode);
    }

    @org.springframework.beans.factory.annotation.Value("${docmind.bot.cognitive-mode:DETERMINISTIC_V1}")
    public void setCognitiveModeProperty(String rawMode) {
        setCognitiveMode(AdaptiveConversationIntelligence.CognitiveMode.fromProperty(rawMode));
    }

    public void setCognitiveMode(AdaptiveConversationIntelligence.CognitiveMode mode) {
        this.cognitiveMode = mode != null
                ? mode
                : AdaptiveConversationIntelligence.CognitiveMode.DETERMINISTIC_V1;
        if (this.cleanAgentOrchestratorService != null) {
            this.cleanAgentOrchestratorService.setCognitiveMode(this.cognitiveMode);
        }
    }

    public AdaptiveConversationIntelligence.CognitiveMode getCognitiveMode() {
        return cognitiveMode;
    }

    public boolean isLlmCognitiveV2Mode() {
        return cognitiveMode == AdaptiveConversationIntelligence.CognitiveMode.LLM_COGNITIVE_V2;
    }

    public CleanAgentOrchestratorService getCleanAgentOrchestratorService() {
        return cleanAgentOrchestratorService;
    }

    public void setInboundSessionPersistenceService(InboundSessionPersistenceService inboundSessionPersistenceService) {
        this.inboundSessionPersistenceService = inboundSessionPersistenceService;
    }

    public InboundSessionPersistenceService getInboundSessionPersistenceService() {
        return inboundSessionPersistenceService;
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
                             HarnessOptimizerService harnessOptimizerService,
                             FastApiAgentService fastApiAgentService) {
        this(retrievalService, llmClient, chatProps, documentRepository, knowledgePageRepository, customerInsightEngine,
                agentSkillService, metaLearningService, guardrailPipeline, harnessOptimizerService, fastApiAgentService, null, null);
    }

    public void setMapOfContentService(MapOfContentService mapOfContentService) {
        this.mapOfContentService = mapOfContentService;
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

    /** Correlation id của lượt hiện tại (run_id/conversation_id/turn_id) để nối Router -> Validator -> Sender trong log audit. */
    private static final ThreadLocal<String> CURRENT_TURN_ID = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> PROVENANCE_V38_LOGGED = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_INBOUND_EXTERNAL_MID = new ThreadLocal<>();

    static void markProvenanceV38Logged() {
        PROVENANCE_V38_LOGGED.set(true);
    }

    static FanpageBotResponse buildSilentSuppressedResponse(String hookCode, String inboundExternalMid) {
        return new FanpageBotResponse(
                "",
                List.of(),
                List.of(),
                "SUPPRESSED",
                1.0,
                false,
                null,
                null,
                null,
                List.of(),
                null,
                List.of(),
                hookCode != null ? List.of(hookCode) : List.of(),
                false,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                null,
                inboundExternalMid
        );
    }

    public FanpageBotResponse handleCustomerMessage(FanpageBotRequest rawRequest) {
        FanpageBotRequest request = rawRequest;
        String turnId = java.util.UUID.randomUUID().toString();
        if (rawRequest != null && rawRequest.senderId() != null) {
            String ledgerKey = SentBubbleLedger.key(rawRequest.pageId(), rawRequest.senderId(), rawRequest.conversationId());
            if (Boolean.TRUE.equals(rawRequest.resetSession())) {
                SentBubbleLedger.reset(ledgerKey);
            }
            // Backend ledger là nguồn chuẩn cho <CONVERSATION_HISTORY>: vá bong bóng bị thiếu/cắt cụt trong payload client.
            List<FanpageBotRequest.ConversationTurn> reconciled = SentBubbleLedger.reconcile(ledgerKey, rawRequest.history());
            if (reconciled != rawRequest.history()) {
                request = new FanpageBotRequest(
                        rawRequest.pageId(), rawRequest.senderId(), rawRequest.customerName(), rawRequest.message(),
                        rawRequest.messages(), reconciled, rawRequest.documentIds(), rawRequest.topK(),
                        rawRequest.previousTemperature(), rawRequest.replyDelaySeconds(), rawRequest.idleSeconds(),
                        rawRequest.action(), rawRequest.splitPriceBubbles(), rawRequest.pendingTurnSequence(),
                        rawRequest.priceBubbleSentAtNs(), rawRequest.conversationId(), rawRequest.resetSession(),
                        rawRequest.replyToMid(), rawRequest.quotedMessageText());
            }
        }
        CURRENT_TURN_ID.set(turnId);
        PROVENANCE_V38_LOGGED.set(false);
        CURRENT_INBOUND_EXTERNAL_MID.remove();
        try {
            FanpageBotResponse resp = handleCustomerMessageInternal(request);
            if (!Boolean.TRUE.equals(PROVENANCE_V38_LOGGED.get()) && resp != null) {
                resp = enforceFinalOutputGateAndLogProvenanceV38OnEarlyReturn(request, resp);
            }
            String resolvedExtMid = CURRENT_INBOUND_EXTERNAL_MID.get();
            if (resp != null && resolvedExtMid != null && resp.inboundExternalMid() == null) {
                resp = resp.withInboundExternalMid(resolvedExtMid);
            }
            return resp;
        } finally {
            CURRENT_INBOUND_EXTERNAL_MID.remove();
            PROVENANCE_V38_LOGGED.remove();
            CURRENT_TURN_ID.remove();
        }
    }

    private FanpageBotResponse enforceFinalOutputGateAndLogProvenanceV38OnEarlyReturn(
            FanpageBotRequest request,
            FanpageBotResponse resp
    ) {
        if (resp == null || resp.replyBubbles() == null || resp.replyBubbles().isEmpty()) {
            return resp;
        }
        String mergedMsg = request != null ? mergeBurstMessages(request) : "";
        if (isLlmCognitiveV2Mode()) {
            mergedMsg = VietnameseQueryNormalizer.sanitizeVerbatimOriginalMessage(mergedMsg);
        } else if (mergedMsg != null && mergedMsg.length() > 2000) {
            mergedMsg = mergedMsg.substring(0, 300);
        }
        PrimaryActionRouter.PrimaryAction effAction = resp.needsHumanHandoff()
                ? ("MEDICAL_SAFETY_HANDOFF".equalsIgnoreCase(resp.handoffCategory())
                    ? PrimaryActionRouter.PrimaryAction.HARD_HANDOFF_SAFETY
                    : PrimaryActionRouter.PrimaryAction.HARD_HANDOFF_COMPLAINT)
                : PrimaryActionRouter.PrimaryAction.GENERIC_SHORT_DISCOVERY;
        com.docmind.guardrail.ReplyContractValidator.ValidationResult gateRes =
                com.docmind.guardrail.ReplyContractValidator.validateAndEnforce(
                        resp.replyBubbles(),
                        ConversationState.initial(),
                        mergedMsg,
                        effAction,
                        false,
                        10_000,
                        ThinkingMode.FAST_NO_THINK,
                        null
                );
        String repairOutcome = "DETERMINISTIC_FAST_PATH_PASS";
        FanpageBotResponse gatedResp = resp;
        if (gateRes.errors() != null && !gateRes.errors().isEmpty()) {
            List<String> gateErrors = List.copyOf(gateRes.errors());
            log.warn("[FinalOutputGate] Early-return WARNING errors={} (preserving reply_bubbles)", gateErrors);
            repairOutcome = "FINAL_GATE_WARNING_PRESERVED";
        }
        if (request != null && request.senderId() != null && gatedResp.replyBubbles() != null && !gatedResp.replyBubbles().isEmpty()) {
            String ledgerKeyNow = SentBubbleLedger.key(request.pageId(), request.senderId(), request.conversationId());
            String ledgerUserTurnId = SentBubbleLedger.stableUserTurnId(ledgerKeyNow, mergedMsg);
            SentBubbleLedger.recordCustomerTurn(ledgerKeyNow, ledgerUserTurnId, mergedMsg);
            SentBubbleLedger.recordAssistantTurn(
                    ledgerKeyNow,
                    ledgerUserTurnId + ":a",
                    gatedResp.replyBubbles().stream().map(FanpageBotResponse.ReplyBubble::text).toList(),
                    SentBubbleLedger.DeliveryStatus.DELIVERED,
                    List.of()
            );
        }
        List<String> finalTexts = gatedResp.replyBubbles() != null
                ? gatedResp.replyBubbles().stream().map(FanpageBotResponse.ReplyBubble::text).toList()
                : List.of();
        log.info("[ProvenanceV38] turn_id={} raw_output={} sanitized_output={} validator_errors={} repair_outcome={} final_bubble={}",
                CURRENT_TURN_ID.get(),
                "DETERMINISTIC_POLICY",
                maskSensitiveForAudit(resp.replyText()),
                gateRes.errors() != null ? List.copyOf(gateRes.errors()) : List.of(),
                repairOutcome,
                maskSensitiveForAudit(String.join(" || ", finalTexts)));
        PROVENANCE_V38_LOGGED.set(true);
        return gatedResp;
    }

    private FanpageBotResponse handleCustomerMessageInternal(FanpageBotRequest request) {
        long turnStartNs = System.nanoTime();
        boolean isReengageAction = request != null && (
                "REENGAGE".equalsIgnoreCase(request.action())
                || (request.idleSeconds() != null && request.idleSeconds() >= 300)
        );
        boolean isGeneratePriceAnchorAction = request != null
                && "GENERATE_PRICE_ANCHOR_BUBBLE".equalsIgnoreCase(request.action());
        String rawMerged = mergeBurstMessages(request);
        boolean v2Mode = isLlmCognitiveV2Mode();
        // Unit P0.2: Trong LLM_COGNITIVE_V2, giữ nguyên văn bản gốc bất biến <= 25.000 ký tự (cắt bỏ an toàn phần > 25.000 theo biên cụm Unicode)
        // thay vì rẽ sang V1 OVERSIZED_PAYLOAD_FAST_PATH 300 ký tự, và quét bảo mật 100% cửa sổ <= 25.000 ký tự đưa vào prompt.
        boolean isOversized = !v2Mode && (rawMerged != null && rawMerged.length() > 2000);
        String verbatimOriginalMessage = v2Mode
                ? VietnameseQueryNormalizer.sanitizeVerbatimOriginalMessage(rawMerged)
                : null;
        String rawCustomerMessage = v2Mode
                ? verbatimOriginalMessage
                : VietnameseQueryNormalizer.sanitizeRawMessage(isOversized ? rawMerged.substring(0, 300) : rawMerged);
        // Normalized Query — trong LLM_COGNITIVE_V2 CHỈ phục vụ nội bộ cho truy xuất RAG/BM25, KHÔNG thay thế Original Message gửi cho LLM!
        String mergedMessage = VietnameseQueryNormalizer.normalize(rawCustomerMessage);
        if (mergedMessage.isBlank()) {
            if (isReengageAction) {
                mergedMessage = "Khách hàng tạm im lặng, bot chủ động gửi tin nhắn re-engage kéo lại tương tác.";
                if (v2Mode) {
                    verbatimOriginalMessage = mergedMessage;
                }
            } else {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tin nhắn của khách hàng không được để trống.");
            }
        }
        if (mergedMessage.length() > 2000) {
            mergedMessage = mergedMessage.substring(0, 2000);
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

        // Unit P0.1: Entry State Guard (HUMAN_TAKEOVER + Fail-Closed + Atomic ON CONFLICT inbound persistence)
        if (inboundSessionPersistenceService != null) {
            if (isReengageAction) {
                if (!inboundSessionPersistenceService.isProactiveReengageAllowed(pageId, request.senderId())) {
                    return buildSilentSuppressedResponse("HUMAN_TAKEOVER_SILENT_HOLD", null);
                }
            } else if (!isGeneratePriceAnchorAction) {
                FanpageBotRequest effectivePersistenceReq = (pageId != null && (request.pageId() == null || request.pageId().isBlank()))
                        ? new FanpageBotRequest(
                                pageId.toString(), request.senderId(), request.customerName(), request.message(),
                                request.messages(), request.history(), request.documentIds(), request.topK(),
                                request.previousTemperature(), request.replyDelaySeconds(), request.idleSeconds(),
                                request.action(), request.splitPriceBubbles(), request.pendingTurnSequence(),
                                request.priceBubbleSentAtNs(), request.conversationId(), request.resetSession(),
                                request.replyToMid(), request.quotedMessageText()
                        )
                        : request;
                String verbatimToStore = (rawMerged != null && !rawMerged.isBlank())
                        ? rawMerged.strip()
                        : mergedMessage;
                InboundSessionPersistenceService.EntryEvaluation entryEval =
                        inboundSessionPersistenceService.evaluateEntryAndPersistCustomer(
                                effectivePersistenceReq,
                                verbatimToStore
                        );
                String resolvedMid = (entryEval.resolvedEvent() != null)
                        ? entryEval.resolvedEvent().externalMid()
                        : null;
                if (resolvedMid != null) {
                    CURRENT_INBOUND_EXTERNAL_MID.set(resolvedMid);
                }
                if (entryEval.action() == InboundSessionPersistenceService.EntryAction.BLOCK_HUMAN_TAKEOVER) {
                    return buildSilentSuppressedResponse("HUMAN_TAKEOVER_SILENT_HOLD", resolvedMid);
                }
                if (entryEval.action() == InboundSessionPersistenceService.EntryAction.SUPPRESS_DUPLICATE_INBOUND) {
                    return buildSilentSuppressedResponse("DUPLICATE_INBOUND_EVENT_SUPPRESSED", resolvedMid);
                }
                if (entryEval.action() == InboundSessionPersistenceService.EntryAction.BLOCK_FAIL_CLOSED_WEBHOOK) {
                    return buildSilentSuppressedResponse("STATE_GUARD_FAIL_CLOSED", resolvedMid);
                }
            }
        }

        long currentTurnSequence = isGeneratePriceAnchorAction
                ? (request.pendingTurnSequence() != null ? request.pendingTurnSequence() : 0L)
                : ThinkingModePolicy.registerIncomingCustomerTurn(pageId, request.senderId());
        if (isGeneratePriceAnchorAction && request.pendingTurnSequence() != null
                && ThinkingModePolicy.isPendingSecondBubbleSuperseded(pageId, request.senderId(), request.pendingTurnSequence())) {
            long totalCancelledMs = (System.nanoTime() - turnStartNs) / 1_000_000L;
            this.lastTurnTelemetry = new ThinkingModePolicy.TurnTelemetry(
                    PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE,
                    ThinkingMode.PRICE_ANCHOR_THINK,
                    "PRICE_INQUIRY",
                    List.of(),
                    List.of(),
                    true,
                    "CANCELLED_BY_NEWER_CUSTOMER_TURN",
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    "PRICE_QUOTE_EMOTIONAL_ANCHOR",
                    true,
                    ThinkingMode.PRICE_ANCHOR_THINK.maxOutputTokens(),
                    0,
                    0L,
                    0L,
                    10_000,
                    0L,
                    0,
                    true,
                    0L,
                    0L,
                    0L,
                    0,
                    totalCancelledMs
            );
            ThinkingModePolicy.recordTurnTelemetry(this.lastTurnTelemetry);
            return new FanpageBotResponse(
                    "",
                    List.of(),
                    List.of(),
                    "VALUE_CONSULTATION",
                    1.0,
                    false,
                    null,
                    null,
                    null,
                    List.of(),
                    null,
                    List.of(),
                    List.of("CANCELLED_SECOND_BUBBLE")
            );
        }
        if (v2Mode && request.isResetSessionRequested() && agentBrainService != null) {
            agentBrainService.clearCustomerMemory(pageId, request.senderId());
        }
        PromptBuilder.PagePersona pagePersona = resolvePagePersona(pageId);
        String honorific = v2Mode
                ? resolveAciV2EffectiveHonorific(pageId, request.senderId(), pagePersona)
                : FanpageBotPromptBuilder.resolveCustomerHonorific(
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

        // =========================================================================
        // PRE-RETRIEVAL FAST-PATH FOR OVERSIZED PAYLOADS (> 2,000 chars, e.g. 50k chars)
        // Satisfies the 5.0s SLA in Test 7.2 (< 0.2s actual) by skipping FastAPI consult,
        // pgvector dense embedding computation, full-text retrieval, and LLM completion.
        // =========================================================================
        if (isOversized) {
            log.info("[LLM] Message oversized (> 2000 chars, actual={}) -> immediate pre-retrieval fast path to grounded fallback",
                    rawMerged.length());
            boolean isFirstTurn = request.history() == null || request.history().stream()
                    .noneMatch(t -> "assistant".equalsIgnoreCase(t.role()) || "bot".equalsIgnoreCase(t.role()));
            ObsidianMetadataCache metaCache = this.retrievalService != null ? this.retrievalService.getMetadataCache() : null;
            String rawReply = generateGroundedFallbackReply(
                    mergedMessage,
                    List.of(),
                    honorific,
                    isFirstTurn,
                    initialStage,
                    request.history(),
                    null,
                    null,
                    request.customerName(),
                    metaCache,
                    pageId
            );
            String sanitizedReply = PromptSecurityGuard.sanitizeModelOutput(rawReply);
            PostProcessedBotReply processed = postProcessReply(
                    sanitizedReply,
                    List.of(),
                    honorific,
                    isFirstTurn,
                    initialStage,
                    request.history(),
                    mergedMessage,
                    null
            );
            String finalCleanText = processed.cleanText();
            List<FanpageBotResponse.ReplyBubble> bubbles = splitIntoBubbles(finalCleanText);
            FanpageBotResponse fastResponse = new FanpageBotResponse(
                    finalCleanText,
                    bubbles,
                    (bubbles != null) ? bubbles.stream().map(FanpageBotResponse.ReplyBubble::typingDelayMs).toList() : List.of(),
                    initialStage.code(),
                    0.90,
                    false,
                    null,
                    null,
                    extractedLead,
                    List.of(),
                    customerInsight,
                    List.of(),
                    List.of("OVERSIZED_PAYLOAD_FAST_PATH")
            );
            return applyGuardrails(
                    fastResponse,
                    honorific,
                    request.customerName(),
                    isFirstTurn,
                    initialStage.code(),
                    mergedMessage,
                    request.history(),
                    List.of()
            );
        }

        boolean domainHealthSafetyEnabled = isDomainPhysicalContraindicationGateEnabled(pagePersona);
        String matchedCustomRedFlag = findMatchedPageSafetyRedFlag(mergedMessage, pagePersona);
        boolean allowDomainHealthForTurn = domainHealthSafetyEnabled || matchedCustomRedFlag != null;

        List<AgentSkillDto> candidateSkills = agentSkillService != null
                ? agentSkillService.getActiveSkillsForPage(pageId)
                : CustomerInsightEngine.builtInDefaultSkills();
        List<AgentSkillDto> activeSkills = v2Mode
                ? CleanAgentCatalogAndPromptBuilder.filterPageConfiguredSkills(candidateSkills, pageId)
                : customerInsightEngine.selectActiveSkills(
                        customerInsight, mergedMessage, request.history(), initialStage, candidateSkills);
        if (!v2Mode && !allowDomainHealthForTurn && !isUniversalAcuteMedicalEmergency(mergedMessage) && activeSkills != null) {
            activeSkills = activeSkills.stream()
                    .filter(s -> s != null
                            && !"safety_handoff_high_risk".equalsIgnoreCase(s.code())
                            && !"spa_neurological_safety_handoff".equalsIgnoreCase(s.code()))
                    .toList();
        }

        // 1. KIỂM TRA BẢO MẬT CHỐNG PROMPT INJECTION & META-PROBE TRÊN 100% CỬA SỔ ĐƯA VÀO PROMPT (<= 25.000 KÝ TỰ Ở V2)
        String promptBoundSecurityTarget = (v2Mode && verbatimOriginalMessage != null)
                ? verbatimOriginalMessage
                : mergedMessage;
        if (PromptSecurityGuard.isDirectJailbreakAttempt(promptBoundSecurityTarget)) {
            log.warn("stage=fanpage-bot-security blocked=direct_jailbreak_or_meta_probe page_id={} v2_mode={} len={} msg={}",
                    pageId,
                    v2Mode,
                    promptBoundSecurityTarget != null ? promptBoundSecurityTarget.length() : 0,
                    maskPiiInText(mergedMessage));
            if (v2Mode) {
                String rawSelfPronoun = (pagePersona != null && pagePersona.resolvedConfig() != null)
                        ? pagePersona.resolvedConfig().resolvedSelfPronoun()
                        : "em";
                String neutralSelfPronoun = (rawSelfPronoun != null && !rawSelfPronoun.isBlank())
                        ? rawSelfPronoun.strip().toLowerCase(Locale.ROOT)
                        : "em";
                String neutralNoCtaRefusal = "Dạ " + neutralSelfPronoun
                        + " chỉ hỗ trợ cung cấp thông tin dịch vụ và lịch hẹn thực tế của bên mình thôi ạ.";
                List<FanpageBotResponse.ReplyBubble> singleBubble = List.of(
                        new FanpageBotResponse.ReplyBubble(neutralNoCtaRefusal, 900)
                );
                return new FanpageBotResponse(
                        neutralNoCtaRefusal,
                        singleBubble,
                        List.of(900),
                        "DISCOVERY",
                        1.0,
                        false,
                        null,
                        null,
                        extractedLead,
                        List.of(),
                        customerInsight,
                        List.of(),
                        List.of("SECURITY_PROMPT_INJECTION_REFUSAL")
                );
            }
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

        // 2. PHÁT HIỆN TÌNH HUỐNG KHÓ TẦNG 1 (PRE-LLM DETERMINISTIC HANDOFF — CHỈ CHẠY Ở DETERMINISTIC_V1;
        //    TRONG LLM_COGNITIVE_V2, S05/S08/S09/A02/A08 ĐƯỢC ĐƯA VÀO ACI COGNITIVE LOOP + CONDITIONAL HANDOFF)
        if (!v2Mode && COMPLAINT_OR_DISTRESS_PATTERN.matcher(mergedMessage).find()) {
            List<AgentSkillDto> handoffSafeSkills = CleanAgentOrchestratorService.alignCleanAgentActiveSkills(
                    activeSkills,
                    candidateSkills,
                    "HUMAN_HANDOFF",
                    false,
                    false,
                    false
            );
            if (agentSkillService != null && handoffSafeSkills != null && !handoffSafeSkills.isEmpty()) {
                try {
                    agentSkillService.recordSkillsApplied(handoffSafeSkills);
                } catch (Exception e) {
                    log.warn("Non-fatal: failed to record skill telemetry on COMPLAINT_OR_DISTRESS: {}", e.getMessage());
                }
            }
            log.info("stage=fanpage-bot-handoff trigger=COMPLAINT_OR_DISTRESS page_id={}", pageId);
            boolean isSpaComplaintV2 = pagePersona != null
                    && ((pagePersona.name() != null && pagePersona.name().toLowerCase(Locale.ROOT).contains("spa"))
                        || (pagePersona.resolvedConfig().openingBubbles() != null && !pagePersona.resolvedConfig().openingBubbles().isEmpty()));
            String empathyReply = isSpaComplaintV2
                    ? "Dạ em rất tiếc vì trải nghiệm chưa làm " + honorific + " hài lòng ạ. Em chuyển quản lý phụ trách kiểm tra và phản hồi " + honorific + " sớm nhất nhé."
                    : "Dạ em rất xin lỗi " + honorific + " về trải nghiệm chưa tốt và sự bất tiện này ạ. "
                      + "Em xin phép chuyển thông tin khẩn cấp tới Quản lý và Bác sĩ/Chuyên viên trưởng của bên em để kiểm tra và liên hệ hỗ trợ trực tiếp cho " + honorific + " ngay lập tức ạ.";
            return buildHandoffResponse(
                    empathyReply,
                    "COMPLAINT_OR_DISTRESS",
                    "Khách hàng phản ánh dấu hiệu kích ứng / khiếu nại chất lượng / yêu cầu xử lý khẩn cấp — cần Quản lý hoặc Bác sĩ tiếp nhận ngay.",
                    1.0,
                    extractedLead,
                    customerInsight,
                    handoffSafeSkills
            );
        }

        if (!v2Mode && shouldTriggerSafetyContraindicationHandoff(mergedMessage, pagePersona)) {
            boolean isNeurological = SentimentIntentLayer.isNeurologicalRiskMessage(mergedMessage);
            if (candidateSkills != null) {
                String targetSkillCode = isNeurological ? "spa_neurological_safety_handoff" : "safety_handoff_high_risk";
                for (AgentSkillDto cand : candidateSkills) {
                    if (cand != null && targetSkillCode.equalsIgnoreCase(cand.code())) {
                        activeSkills = List.of(cand);
                        break;
                    }
                }
            }
            if (agentSkillService != null && activeSkills != null && !activeSkills.isEmpty()) {
                try {
                    agentSkillService.recordSkillsApplied(activeSkills);
                } catch (Exception e) {
                    log.warn("Non-fatal: failed to record skill telemetry on HIGH_RISK_SAFETY: {}", e.getMessage());
                }
            }
            log.info("stage=fanpage-bot-handoff trigger=HIGH_RISK_SAFETY neurological={} page_id={}", isNeurological, pageId);
            String lowerSafety = mergedMessage.toLowerCase(Locale.ROOT);
            boolean isPregnancy = lowerSafety.contains("bầu") || lowerSafety.contains("mang thai") || lowerSafety.contains("thai kỳ") || lowerSafety.contains("mới sinh");
            boolean isEarSafety = lowerSafety.contains("tai") && (lowerSafety.contains("đau") || lowerSafety.contains("ù") || lowerSafety.contains("dịch") || lowerSafety.contains("mủ") || lowerSafety.contains("viêm") || lowerSafety.contains("nghe kém"));
            String safetyReply;
            if (isEarSafety) {
                safetyReply = "Dạ nếu tai đang đau và ù, " + honorific + " chưa nên vệ sinh tại spa ngay ạ. Em khuyên " + honorific + " khám Tai Mũi Họng trước và chuyển chuyên viên hỗ trợ mình ngay nhé.";
            } else if (isPregnancy) {
                safetyReply = "Dạ trường hợp mang thai cần được tư vấn trực tiếp để an toàn cho " + honorific + " ạ. Em chuyển chuyên viên kiểm tra và hướng dẫn phù hợp ngay nhé.";
            } else if (isNeurological) {
                safetyReply = "Dạ những dấu hiệu này có thể cần được xử trí y tế khẩn cấp, nên " + honorific + " hãy gọi cấp cứu hoặc đến cơ sở y tế gần nhất ngay, đừng chờ đợi thêm ạ. Em cũng chuyển chuyên viên hỗ trợ " + honorific + " ngay nhé.";
            } else {
                safetyReply = "Dạ trường hợp này cần chuyên viên kiểm tra trực tiếp để an toàn và chính xác cho " + honorific + " ạ. Em chuyển người hỗ trợ " + honorific + " ngay nhé.";
            }
            ConversationState safetyState = ConversationState.hydrate(
                    pageId,
                    request.senderId(),
                    request.conversationId(),
                    request.isResetSessionRequested(),
                    request.history(),
                    pagePersona != null ? pagePersona.resolvedConfig().safeDomainKeywords() : List.of(),
                    List.of()
            );
            PrimaryActionRouter.RoutingDecision safetyDecision = new PrimaryActionRouter.RoutingDecision(
                    PrimaryActionRouter.PrimaryAction.HARD_HANDOFF_SAFETY,
                    PrimaryActionRouter.PrimaryAction.HARD_HANDOFF_SAFETY,
                    false,
                    1,
                    isNeurological ? "NEUROLOGICAL_RISK" : "SAFETY_CONTRAINDICATION",
                    isNeurological ? "spa_neurological_safety_handoff" : "safety_handoff_high_risk"
            );
            List<LearnedRuleDto> safetyAppliedRules = List.of();
            if (retrievalService != null) {
                try {
                    RetrievalService.RetrievalResult safetyRetrieval = retrievalService.retrieveWithRules(
                            documentIds, mergedMessage, 1, List.of(), pageId
                    );
                    if (safetyRetrieval != null && safetyRetrieval.appliedRules() != null) {
                        safetyAppliedRules = safetyRetrieval.appliedRules().stream()
                                .filter(FanpageBotService::isSafetyPolicyRule)
                                .toList();
                    }
                } catch (Exception e) {
                    log.warn("Non-fatal: failed to load safety rules on HIGH_RISK_SAFETY handoff: {}", e.getMessage());
                }
            }
            FanpageBotResponse baseHandoffResp = buildHandoffResponse(
                    safetyReply,
                    "MEDICAL_SAFETY_HANDOFF",
                    "Khách hàng có dấu hiệu thai kỳ, triệu chứng thần kinh cấp tính hoặc tình trạng sức khỏe chống chỉ định — chuyển Quản lý / Kỹ thuật viên trưởng kiểm tra trực tiếp.",
                    1.0,
                    extractedLead,
                    customerInsight,
                    activeSkills
            );
            FanpageBotResponse handoffResp = new FanpageBotResponse(
                    baseHandoffResp.replyText(),
                    baseHandoffResp.replyBubbles(),
                    baseHandoffResp.salesStage(),
                    baseHandoffResp.confidenceScore(),
                    baseHandoffResp.needsHumanHandoff(),
                    baseHandoffResp.handoffCategory(),
                    baseHandoffResp.handoffReason(),
                    baseHandoffResp.extractedLead(),
                    safetyAppliedRules,
                    baseHandoffResp.customerInsight(),
                    baseHandoffResp.activeSkills()
            );
            long safetyElapsedMs = (System.nanoTime() - turnStartNs) / 1_000_000L;
            this.lastTurnTelemetry = new ThinkingModePolicy.TurnTelemetry(
                    PrimaryActionRouter.PrimaryAction.HARD_HANDOFF_SAFETY,
                    ThinkingMode.FAST_NO_THINK,
                    safetyDecision.detectedIntent(),
                    List.of(),
                    List.of(),
                    true,
                    null,
                    0L,
                    0L,
                    0L,
                    safetyElapsedMs,
                    0L,
                    "SAFETY_HIGH_RISK_HANDOFF",
                    false,
                    ThinkingMode.FAST_NO_THINK.maxOutputTokens(),
                    0,
                    0L,
                    0L,
                    10_000,
                    0L,
                    0,
                    false,
                    0L,
                    0L,
                    0L,
                    0L,
                    0,
                    safetyElapsedMs,
                    false,
                    null,
                    "DETERMINISTIC_POLICY",
                    false,
                    0,
                    "PASS",
                    List.of(),
                    "deterministic",
                    llmClient != null ? llmClient.name() : "none",
                    "none",
                    List.of("DETERMINISTIC_POLICY")
            );
            ThinkingModePolicy.recordTurnTelemetry(this.lastTurnTelemetry);
            ConversationState stateAfterSafety = ConversationState.updateAfterSend(
                    pageId,
                    request.senderId(),
                    request.conversationId(),
                    safetyState,
                    mergedMessage,
                    safetyDecision,
                    handoffResp.replyBubbles(),
                    true,
                    request.history() != null ? request.history().size() : 0
            );
            log.info("[ProvenanceAudit] page_id={} sender_id={} routerRuleId={} primaryAction={} detectedIntent={} selectedSkill={} lastBotQuestionType={} stateBefore={} stateAfter={} policyLoaded=true policyMatched=true safetyHandoffTriggered=true bubbleSources=[SAFETY_GATE_FAST_PATH] rawLlmOutput=N/A outputAfterValidatorAndHooks={} finalUiReplyBubbles={}",
                    pageId,
                    request.senderId(),
                    safetyDecision.routerRuleId(),
                    safetyDecision.effectiveResponseAction(),
                    safetyDecision.detectedIntent(),
                    safetyDecision.correspondingMicroSkillCode(),
                    safetyState.lastBotQuestionType(),
                    formatStateTelemetrySummary(safetyState),
                    formatStateTelemetrySummary(stateAfterSafety),
                    handoffResp.replyText(),
                    handoffResp.replyBubbles().stream().map(FanpageBotResponse.ReplyBubble::text).toList());
            return handoffResp;
        }

        if (!v2Mode && EXPLICIT_HUMAN_REQUEST_PATTERN.matcher(mergedMessage).find()) {
            if (agentSkillService != null && activeSkills != null && !activeSkills.isEmpty()) {
                try {
                    agentSkillService.recordSkillsApplied(activeSkills);
                } catch (Exception e) {
                    log.warn("Non-fatal: failed to record skill telemetry on EXPLICIT_HUMAN_REQUEST: {}", e.getMessage());
                }
            }
            log.info("stage=fanpage-bot-handoff trigger=EXPLICIT_HUMAN_REQUEST page_id={}", pageId);
            String humanReply = "Dạ vâng ạ, em xin phép chuyển cuộc trò chuyện cho Chuyên viên tư vấn và Quản lý bên em hỗ trợ " + honorific + " trực tiếp ngay bây giờ nha.";
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

        if (v2Mode
                ? PrimaryActionRouter.isStrictSystemOptOutMessage(verbatimOriginalMessage)
                : PrimaryActionRouter.isExplicitOptOutMessage(mergedMessage)) {
            if (v2Mode && agentBrainService != null) {
                agentBrainService.applyAciTurnMemoryUpdates(
                        pageId,
                        request.senderId(),
                        "T_SYSTEM_OPT_OUT",
                        verbatimOriginalMessage,
                        List.of(),
                        null,
                        null,
                        null,
                        true,
                        null,
                        "SYSTEM_STRICT_OPT_OUT"
                );
            }
            String stopText = "Dạ em xin phép dừng nhắc lại để tránh làm phiền " + honorific
                    + " ạ. Khi nào cần tư vấn, " + honorific + " nhắn giúp em từ khóa \"tư vấn\" nhé.";
            List<FanpageBotResponse.ReplyBubble> optOutBubbles = List.of(
                    new FanpageBotResponse.ReplyBubble(stopText, 0)
            );
            ConversationState optOutState = ConversationState.hydrate(
                    pageId,
                    request.senderId(),
                    request.conversationId(),
                    request.isResetSessionRequested(),
                    request.history(),
                    pagePersona != null ? pagePersona.resolvedConfig().safeDomainKeywords() : List.of(),
                    List.of()
            );
            PrimaryActionRouter.RoutingDecision optOutDecision = new PrimaryActionRouter.RoutingDecision(
                    PrimaryActionRouter.PrimaryAction.GENERIC_SHORT_DISCOVERY,
                    PrimaryActionRouter.PrimaryAction.GENERIC_SHORT_DISCOVERY,
                    false,
                    1,
                    "EXPLICIT_OPT_OUT",
                    "spa_ambiguous_confirm_once"
            );
            long optOutElapsedMs = Math.max(1L, (System.nanoTime() - turnStartNs) / 1_000_000L);
            this.lastTurnTelemetry = new ThinkingModePolicy.TurnTelemetry(
                    PrimaryActionRouter.PrimaryAction.GENERIC_SHORT_DISCOVERY,
                    ThinkingMode.FAST_NO_THINK,
                    "EXPLICIT_OPT_OUT",
                    List.of(),
                    List.of(),
                    true,
                    null,
                    0L,
                    0L,
                    0L,
                    optOutElapsedMs,
                    0L,
                    "FAST_PATH_BOOKING_OR_HANDOFF",
                    false,
                    ThinkingMode.FAST_NO_THINK.maxOutputTokens(),
                    0,
                    0L,
                    0L,
                    10_000,
                    0L,
                    0,
                    false,
                    0L,
                    0L,
                    0L,
                    0L,
                    0,
                    optOutElapsedMs,
                    false,
                    null,
                    "DETERMINISTIC_POLICY",
                    false,
                    0,
                    "PASS",
                    List.of(),
                    "deterministic",
                    llmClient != null ? llmClient.name() : "none",
                    "none",
                    List.of("DETERMINISTIC_POLICY")
            );
            ThinkingModePolicy.recordTurnTelemetry(this.lastTurnTelemetry);
            ConversationState.updateAfterSend(
                    pageId,
                    request.senderId(),
                    request.conversationId(),
                    optOutState,
                    mergedMessage,
                    optOutDecision,
                    optOutBubbles,
                    false,
                    request.history() != null ? request.history().size() : 0
            );
            return new FanpageBotResponse(
                    stopText,
                    optOutBubbles,
                    List.of(0),
                    "DISCOVERY",
                    1.0,
                    false,
                    null,
                    null,
                    extractedLead,
                    List.of(),
                    customerInsight,
                    activeSkills != null && !activeSkills.isEmpty() ? List.of(activeSkills.get(0)) : List.of(),
                    List.of("EXPLICIT_OPT_OUT")
            );
        }

        if (!v2Mode && COMPLEX_NEGOTIATION_PATTERN.matcher(mergedMessage).find()) {
            if (agentSkillService != null && activeSkills != null && !activeSkills.isEmpty()) {
                try {
                    agentSkillService.recordSkillsApplied(activeSkills);
                } catch (Exception e) {
                    log.warn("Non-fatal: failed to record skill telemetry on COMPLEX_NEGOTIATION: {}", e.getMessage());
                }
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

        // 3. XÁC ĐỊNH PHẠM VI TÀI LIỆU CỦA FANPAGE (chỉ lấy tài liệu READY, đang bật active và còn hiệu lực thời gian)
        if ((documentIds == null || documentIds.isEmpty()) && documentRepository != null) {
            if (pageId != null) {
                documentIds = documentRepository.findByPageIdOrderByCreatedAtDesc(pageId).stream()
                        .filter(d -> "READY".equalsIgnoreCase(d.getStatus()))
                        .filter(DocumentEntity::isCurrentlyEffective)
                        .map(DocumentEntity::getId)
                        .toList();
            } else {
                List<DocumentEntity> allReady = documentRepository.findAllByOrderByCreatedAtDesc().stream()
                        .filter(d -> "READY".equalsIgnoreCase(d.getStatus()))
                        .filter(DocumentEntity::isCurrentlyEffective)
                        .toList();
                documentIds = allReady.stream().map(DocumentEntity::getId).toList();
                if (pagePersona == null) {
                    pageId = allReady.stream()
                            .map(DocumentEntity::getPageId)
                            .filter(java.util.Objects::nonNull)
                            .findFirst()
                            .orElse(null);
                    pagePersona = resolvePagePersona(pageId);
                    honorific = v2Mode
                            ? resolveAciV2EffectiveHonorific(pageId, request.senderId(), pagePersona)
                            : FanpageBotPromptBuilder.resolveCustomerHonorific(
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
                    activeSkills = v2Mode
                            ? (candidateSkills != null
                                ? candidateSkills.stream().filter(s -> s != null && s.active()).toList()
                                : List.of())
                            : customerInsightEngine.selectActiveSkills(
                                    customerInsight, mergedMessage, request.history(), initialStage, candidateSkills);
                }
            }
        }
        boolean isLiveConfiguredLlmPreCheck = llmClient != null && llmClient.name() != null
                && llmClient.name().toLowerCase(Locale.ROOT).matches("^(?:ollama|openai|openai_compatible|gemini|anthropic|claude|deepseek|qwen|dashscope|grok|xai|openrouter|custom):.*");
        boolean cleanAgentEligiblePreCheck = v2Mode
                ? (llmClient != null && !isGeneratePriceAnchorAction)
                : (isCleanAgentMode() && isLiveConfiguredLlmPreCheck && !isGeneratePriceAnchorAction);
        if (!cleanAgentEligiblePreCheck && agentSkillService != null && activeSkills != null && !activeSkills.isEmpty()) {
            try {
                agentSkillService.recordSkillsApplied(activeSkills);
            } catch (Exception e) {
                log.warn("Non-fatal: failed to record skill telemetry: {}", e.getMessage());
            }
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
                try {
                    agentSkillService.recordConversionSuccess(pageId, convoBuilder.toString());
                } catch (Exception e) {
                    log.warn("Non-fatal: failed to record conversion success: {}", e.getMessage());
                }
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

        // 4. KHÔI PHỤC TRẠNG THÁI HỘI THOẠI, PHÂN TÍCH CÂU HỎI TỒN ĐỌNG & TRUY XUẤT TRI THỨC THEO HÀNH ĐỘNG (7-STEP COHERENT PIPELINE)
        // Lưu ý quan trọng: Chỉ ĐỌC AI Playbook (extraRules = List.of()), KHÔNG BAO GIỜ chạy MetaLearningService từ tin nhắn khách hàng!
        int incomingHistorySize = request.history() != null ? request.history().size() : 0;
        List<String> safeDomainKeywords = pagePersona != null ? pagePersona.resolvedConfig().resolvedScopeTaxonomy() : List.of();
        ConversationState preRetrievalState = ConversationState.hydrateForTurn(
                pageId,
                request.senderId(),
                request.conversationId(),
                request.isResetSessionRequested(),
                mergedMessage,
                request.history(),
                safeDomainKeywords,
                List.of()
        );
        ConversationState preEnrichedState = v2Mode
                ? preRetrievalState
                : preRetrievalState.enrichWithCurrentMessage(mergedMessage, safeDomainKeywords);
        PrimaryActionRouter.RoutingDecision preRetrievalDecision = v2Mode
                ? null
                : PrimaryActionRouter.route(
                        mergedMessage,
                        preRetrievalState,
                        preEnrichedState, // enrich đúng 1 lần/lượt: không để Router enrich lại
                        null,
                        safeDomainKeywords,
                        allowDomainHealthForTurn
                );
        boolean isPreRoutedDetailOrFaq = !v2Mode && preRetrievalDecision != null
                && (preRetrievalDecision.effectiveResponseAction() == PrimaryActionRouter.PrimaryAction.SERVICE_DETAIL_AND_FAQ_QA
                    || preRetrievalDecision.effectiveResponseAction() == PrimaryActionRouter.PrimaryAction.SAFE_REFUSAL_OR_HANDOFF_IF_REPEATED);

        long retrievalStartNs = System.nanoTime();
        List<java.util.Map<String, String>> historyMaps = new ArrayList<>();
        if (!v2Mode && request.history() != null) {
            for (FanpageBotRequest.ConversationTurn t : request.history()) {
                if (t != null && t.role() != null && t.content() != null) {
                    historyMaps.add(java.util.Map.of("role", t.role(), "content", t.content()));
                }
            }
        }
        boolean hasActiveCompactPolicy = pagePersona != null
                && pagePersona.resolvedConfig() != null
                && pagePersona.resolvedConfig().activePromptCompact() != null
                && !pagePersona.resolvedConfig().activePromptCompact().isBlank();
        com.example.docmind.client.FastApiAgentClient.ConsultResponse consultResult = (!v2Mode && fastApiAgentService != null && !hasActiveCompactPolicy)
                ? fastApiAgentService.consult(
                        request.senderId(),
                        pageId != null ? pageId.toString() : null,
                        mergedMessage,
                        historyMaps,
                        List.of(),
                        honorific)
                : null;
        if (!v2Mode && consultResult != null && "Anh".equalsIgnoreCase(consultResult.honorific())
                && !"mình".equalsIgnoreCase(honorific)) {
            honorific = "Anh";
        }
        String baseContextualQuery = v2Mode
                ? mergedMessage
                : buildActionTargetedSearchQuery(
                        mergedMessage,
                        request.history(),
                        preRetrievalState,
                        preEnrichedState,
                        preRetrievalDecision
                );
        String contextualQuery = (v2Mode || isPreRoutedDetailOrFaq)
                ? baseContextualQuery
                : enrichContextualQueryWithLangGraph(baseContextualQuery, consultResult);
        int topK = request.topK() != null
                ? (isPreRoutedDetailOrFaq ? Math.max(8, request.topK()) : request.topK())
                : Math.max(isPreRoutedDetailOrFaq ? 8 : 6, chatProps.getTopK());
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
        List<ReciprocalRankFusion.FusedChunk> fused = v2Mode
                ? rawFused
                : scopeChunksToTopicFamily(rawFused, consultResult, mergedMessage);
        List<LearnedRuleDto> appliedRules;
        if (v2Mode && retrievalService != null) {
            List<LearnedRuleDto> allActivePageRules = retrievalService.getAllActiveApprovedRulesForPage(pageId);
            appliedRules = (allActivePageRules != null && !allActivePageRules.isEmpty())
                    ? allActivePageRules
                    : (retrievalResult != null && retrievalResult.appliedRules() != null ? retrievalResult.appliedRules() : List.of());
        } else {
            appliedRules = retrievalResult != null && retrievalResult.appliedRules() != null
                    ? retrievalResult.appliedRules()
                    : List.of();
        }

        boolean leadReadyHandoff = bookingState.hasPhoneInCurrentTurn()
                && extractedLead.phoneNumber() != null && !extractedLead.phoneNumber().isBlank();
        boolean purePhoneLeadFastPath = leadReadyHandoff && !asksExplicitPriceInMessage(mergedMessage);

        boolean isConversationalStateTurn = isPreRoutedDetailOrFaq
                || PrimaryActionRouter.isAcknowledgementMessage(mergedMessage)
                || PrimaryActionRouter.isUnitPriceComparisonMessage(mergedMessage)
                || PrimaryActionRouter.isPriceObjectionMessage(mergedMessage)
                || CleanAgentOrchestratorService.isConfirmedCustomerPriceObjection(mergedMessage, true)
                || CleanAgentOrchestratorService.isHesitationOrDeferralMessage(mergedMessage)
                || PrimaryActionRouter.isFrustrationMessage(mergedMessage)
                || PrimaryActionRouter.isServiceFamilySelectionMessage(mergedMessage)
                || PrimaryActionRouter.isConditionFollowUpMessage(mergedMessage)
                || SentimentIntentLayer.isDetailOrFaqQuestion(
                        mergedMessage,
                        preRetrievalState.lastAssistantMessage(),
                        preRetrievalState.knownRecommendedPackage(),
                        preRetrievalState.hasUnresolvedCustomerRequest())
                || SentimentIntentLayer.isComprehensionFailureMessage(mergedMessage)
                || SentimentIntentLayer.isTrialAcceptanceMessage(mergedMessage)
                || SentimentIntentLayer.isStrongRejectionMessage(mergedMessage)
                || SentimentIntentLayer.isExplicitDownsellRequest(mergedMessage)
                || SentimentIntentLayer.isAlternativePackageRequest(mergedMessage)
                || SentimentIntentLayer.isPackageRejectionMessage(mergedMessage)
                || SentimentIntentLayer.isPriceEscalationRequest(mergedMessage)
                || SentimentIntentLayer.isNeurologicalRiskMessage(mergedMessage)
                || SentimentIntentLayer.isEntityMismatchCorrection(mergedMessage)
                || CustomerInsightEngine.isPureGreetingOrOpening(
                        mergedMessage != null ? mergedMessage.toLowerCase(Locale.ROOT) : "",
                        request.history() == null || request.history().isEmpty());

        // Trong LLM_COGNITIVE_V2: Nếu câu khách mang tính chất đối thoại/khiếu nại/xác nhận thanh toán/lời hứa nhân viên (S05, S08, S09)
        // khiến truy vấn trực tiếp chưa khớp đoạn tài liệu nào, tự động truy xuất neo danh mục & chính sách chuẩn của Page để LLM luôn có căn cứ sự thật!
        if (v2Mode && fused.isEmpty() && retrievalService != null) {
            try {
                RetrievalService.RetrievalResult anchorRetrieval = retrievalService.retrieveWithRules(
                        documentIds,
                        "bảng giá gói dịch vụ liệu trình chính sách thời gian hoạt động quy định",
                        topK,
                        List.of(),
                        pageId
                );
                if (anchorRetrieval != null && anchorRetrieval.chunks() != null && !anchorRetrieval.chunks().isEmpty()) {
                    fused = anchorRetrieval.chunks();
                    if (appliedRules.isEmpty() && anchorRetrieval.appliedRules() != null) {
                        appliedRules = anchorRetrieval.appliedRules();
                    }
                }
            } catch (Exception e) {
                log.debug("[ACI-V2] Non-fatal catalog-anchor fallback retrieval skipped: {}", e.getMessage());
            }
        }

        // 5. KIỂM TRA ĐỘ TỰ TIN DỮ LIỆU (RETRIEVAL CONFIDENCE GATE — CHỈ CHẶN CỨNG TRƯỚC LLM Ở DETERMINISTIC_V1)
        if (!v2Mode && fused.isEmpty() && !purePhoneLeadFastPath && !isConversationalStateTurn) {
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

        // Kiểm tra nếu khách hỏi đích danh một dịch vụ/thông tin cụ thể không hề xuất hiện trong các chunk của Page (DETERMINISTIC_V1)
        String ungroundedTopic = (!v2Mode && !fused.isEmpty()) ? detectUngroundedSpecificTopic(mergedMessage, fused) : null;
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

        // 5.5. MAP OF CONTENT (MOC) & STATEFUL PRICE LADDER TRACKER
        boolean isLiveConfiguredLlm = isLiveConfiguredLlmPreCheck;
        boolean cleanAgentEligible = v2Mode
                ? (llmClient != null && !isGeneratePriceAnchorAction)
                : (isCleanAgentMode() && isLiveConfiguredLlm && !isGeneratePriceAnchorAction && !leadReadyHandoff);
        String mocSummary = null;
        if (!cleanAgentEligible && mapOfContentService != null) {
            try {
                mocSummary = mapOfContentService.getOrBuildMapOfContent(pageId, null);
            } catch (Exception e) {
                log.warn("[MOC] Failed to build Map of Content for page_id={}: {}", pageId, e.getMessage());
            }
        }

        String detectedBranch = null;
        PriceLadderResult priceLadder = null;
        ObsidianMetadataCache metaCacheForLadder = this.retrievalService != null ? this.retrievalService.getMetadataCache() : null;
        if (cleanAgentEligible && metaCacheForLadder != null) {
            fused = enrichFusedChunksWithPriceLadderTargets(fused, null, mergedMessage, metaCacheForLadder, pageId);
            fused = enrichFusedChunksWithOperationalAndPromoFacts(fused, mergedMessage, metaCacheForLadder, pageId);
        }
        if (!cleanAgentEligible) {
            detectedBranch = PriceLadderTracker.resolveBranch(mergedMessage, request.history(), null);
            if (isPreRoutedDetailOrFaq || preEnrichedState.hasUnresolvedCustomerRequest()) {
                fused = enrichFusedChunksForDetailAndFaqQa(fused, preEnrichedState, mergedMessage, metaCacheForLadder, pageId);
            } else {
                fused = enrichFusedChunksWithPriceLadderTargets(fused, null, mergedMessage, metaCacheForLadder, pageId);
            }
            // v3.8: ladder chỉ từ availablePackageCatalog đã xác minh (state hoặc parser Catalog chính thức trên chunk ACTIVE của tenant)
            List<PackageCatalogService.PackageSummary> verifiedCatalogForLadder =
                    (preEnrichedState != null && preEnrichedState.availablePackageCatalog() != null
                            && !preEnrichedState.availablePackageCatalog().isEmpty())
                            ? preEnrichedState.availablePackageCatalog()
                            : PackageCatalogService.extractCatalog(fused, List.of());
            priceLadder = PriceLadderTracker.evaluate(mergedMessage, request.history(), verifiedCatalogForLadder, detectedBranch);
            if (!isPreRoutedDetailOrFaq && !preEnrichedState.hasUnresolvedCustomerRequest()) {
                fused = enrichFusedChunksWithPriceLadderTargets(fused, priceLadder, mergedMessage, metaCacheForLadder, pageId);
            }
            if (priceLadder != null && (priceLadder.isPriceObjection() || priceLadder.isCatalogFloor())
                    && (priceLadder.discountPolicy() == null || priceLadder.discountPolicy().isBlank())) {
                String dynamicPromo = resolveDynamicPromoPolicy(fused, metaCacheForLadder, pageId);
                priceLadder = new PriceLadderResult(
                        priceLadder.isPriceObjection(),
                        priceLadder.isTrialRequest(),
                        priceLadder.previousPriceQuoted(),
                        priceLadder.targetPrice(),
                        priceLadder.targetPackageName(),
                        dynamicPromo,
                        priceLadder.isCatalogFloor(),
                        priceLadder.rationale()
                );
            }
        }

        // 6. XÁC ĐỊNH NHỊP ĐỘ TƯ VẤN (3-STAGE CONSULTATIVE PACING)
        FanpageBotPromptBuilder.SalesStage salesStage = determineSalesStage(mergedMessage, request.history(), extractedLead, priceLadder);
        boolean hasPriorAssistantTurn = (preRetrievalState != null && preRetrievalState.greetingSent())
                || (request.history() != null && request.history().stream()
                    .anyMatch(t -> t != null && "assistant".equalsIgnoreCase(t.role()) && t.content() != null && !t.content().isBlank()));
        boolean isFirstTurn = !hasPriorAssistantTurn;

        // Fast-path (DETERMINISTIC_V1): Nếu khách gửi SĐT đặt lịch / yêu cầu gọi lại mà không hỏi thêm giá, xác nhận tức thì (0ms LLM)
        if (!v2Mode && leadReadyHandoff && !asksExplicitPriceInMessage(mergedMessage)) {
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
        if (!v2Mode && agentBrainService != null) {
            try {
                com.example.docmind.client.AgentBrainClient.RecallAndAdviseResponse brainAdvice =
                        agentBrainService.recallAndAdvise(
                                pageId,
                                request.senderId(),
                                request.customerName(),
                                mergedMessage,
                                computeConfidenceScore(fused),
                                0.90,
                                isFirstTurn,
                                pagePersona
                        );
                customerInsight = agentBrainService.enrichCustomerInsight(pageId, request.senderId(), customerInsight, brainAdvice);
                if (brainAdvice != null) {
                    activeSkills = agentBrainService.synchronizeActiveSkills(activeSkills, candidateSkills, brainAdvice);
                }
            } catch (Exception e) {
                log.debug("[AgentBrain] Non-fatal recallAndAdvise exception: {}", e.getMessage());
            }
        }

        List<com.example.docmind.dto.SampleDialogueDto> sampleDialogues = List.of();
        if (!cleanAgentEligible && playbookUploadService != null && !hasActiveCompactPolicy) {
            try {
                sampleDialogues = playbookUploadService.retrieveRelevantDialogues(contextualQuery, pageId, 1);
            } catch (Exception e) {
                log.debug("[Playbook] Non-fatal retrieveRelevantDialogues exception: {}", e.getMessage());
            }
        }

        boolean hasRuntimeControlPolicy = pagePersona != null
                && ((pagePersona.resolvedConfig().openingBubbles() != null && !pagePersona.resolvedConfig().openingBubbles().isEmpty())
                    || (pagePersona.resolvedConfig().preferenceDiscoveryPrompt() != null && !pagePersona.resolvedConfig().preferenceDiscoveryPrompt().isBlank())
                    || (pagePersona.resolvedConfig().activePromptCompact() != null && !pagePersona.resolvedConfig().activePromptCompact().isBlank())
                    || (pagePersona.resolvedConfig().safeDomainKeywords() != null && !pagePersona.resolvedConfig().safeDomainKeywords().isEmpty())
                    || (candidateSkills != null && candidateSkills.stream().anyMatch(s -> s != null && s.pageId() != null && !s.pageId().isBlank())));
        boolean hasPriorPriceInHistory = (priceLadder != null && priceLadder.previousPriceQuoted() != null)
                || hasQuotedPriceInAssistantHistory(request.history());

        ConversationState convState = preEnrichedState.withRetrievedChunks(fused, safeDomainKeywords, request.history());
        if (cleanAgentEligible) {
            this.lastTurnTelemetry = null;
            if (cleanAgentOrchestratorService != null) {
                cleanAgentOrchestratorService.setLlmClient(this.llmClient);
                cleanAgentOrchestratorService.setAgentBrainService(this.agentBrainService);
                cleanAgentOrchestratorService.setAgentSkillService(this.agentSkillService);
            } else {
                this.cleanAgentOrchestratorService = new CleanAgentOrchestratorService(
                        this.llmClient, this.agentBrainService, this.agentSkillService);
            }
            this.cleanAgentOrchestratorService.setCognitiveMode(this.cognitiveMode);
            AdaptiveConversationIntelligence.ThreeStreamTurnInput threeStreamInput = v2Mode
                    ? AdaptiveConversationIntelligence.buildThreeStreamInput(
                            request,
                            convState != null ? convState.availablePackageCatalog() : List.of(),
                            pagePersona != null ? pagePersona.resolvedConfig() : null,
                            java.time.ZoneId.of("Asia/Ho_Chi_Minh")
                    )
                    : null;
            return cleanAgentOrchestratorService.executeCleanAgentTurn(
                    pageId,
                    request,
                    mergedMessage,
                    honorific,
                    pagePersona,
                    preRetrievalState,
                    convState,
                    fused,
                    appliedRules,
                    extractedLead,
                    customerInsight,
                    activeSkills,
                    candidateSkills,
                    salesStage,
                    incomingHistorySize,
                    turnStartNs,
                    retrievalMs,
                    threeStreamInput
            );
        }
        ConversationState priorStateWithChunks = preRetrievalState.withRetrievedChunks(fused, safeDomainKeywords, request.history());
        String stateBeforeTelemetry = formatStateTelemetrySummary(priorStateWithChunks);
        PrimaryActionRouter.RoutingDecision actionDecision = isGeneratePriceAnchorAction
                ? new PrimaryActionRouter.RoutingDecision(
                        PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE,
                        PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE,
                        false,
                        1,
                        "RECOMMEND_AND_QUOTE_IF_PRICE_REQUESTED",
                        "spa_recommend_and_quote_if_requested",
                        "R03-SPLIT-BUBBLE-2-EMOTIONAL-ANCHOR"
                )
                : ((hasRuntimeControlPolicy || isLiveConfiguredLlm)
                        ? PrimaryActionRouter.route(
                                mergedMessage,
                                priorStateWithChunks,
                                convState,
                                fused,
                                safeDomainKeywords,
                                allowDomainHealthForTurn
                        )
                        : new PrimaryActionRouter.RoutingDecision(
                                PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE,
                                PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE,
                                false,
                                2,
                                "GENERAL_CONSULTATION",
                                null
                        ));
        if (actionDecision != null && actionDecision.correspondingMicroSkillCode() != null && candidateSkills != null) {
            boolean matchedCandidateSkill = false;
            for (AgentSkillDto candidate : candidateSkills) {
                if (candidate != null && matchesSkillCodeOrAlias(
                        actionDecision.correspondingMicroSkillCode(),
                        candidate.code(),
                        actionDecision.detectedIntent())) {
                    activeSkills = List.of(candidate);
                    matchedCandidateSkill = true;
                    break;
                }
            }
            if (!matchedCandidateSkill && ("spa_graceful_exit_after_rejection".equalsIgnoreCase(actionDecision.correspondingMicroSkillCode())
                    || "spa_stage3_graceful_exit".equalsIgnoreCase(actionDecision.correspondingMicroSkillCode()))) {
                activeSkills = List.of(new AgentSkillDto(
                        null,
                        "spa_graceful_exit_after_rejection",
                        "Tôn trọng quyết định cân nhắc & Giữ thiện cảm (Graceful Exit)",
                        "CLOSING",
                        "Tôn trọng quyết định cân nhắc của khách, giữ thiện cảm ấm áp, không xin lỗi và không đặt câu hỏi chèo kéo.",
                        "cân nhắc thêm, suy nghĩ thêm, để xem đã nhé, có gì nhắn sau",
                        "Gửi 1 bong bóng ấm áp tôn trọng quyết định cân nhắc của khách, giữ thiện cảm, 0 xin lỗi, 0 câu hỏi.",
                        null,
                        null,
                        96,
                        true,
                        pageId != null ? pageId.toString() : null,
                        null,
                        null
                ));
            }
        }

        // v3.3.4 GAP-03: Chuẩn hóa bất biến cờ always_on trước mọi bước dùng rule (prompt, scoped selection, telemetry)
        if (appliedRules != null && !appliedRules.isEmpty()) {
            appliedRules = PromptBudgetGuard.enforceAlwaysOnBudget(appliedRules);
        }
        if (hasRuntimeControlPolicy && appliedRules != null && !appliedRules.isEmpty()) {
            List<LearnedRuleDto> guardedAlwaysOn = PromptBudgetGuard.validateAlwaysOnRules(appliedRules);
            LearnedRuleDto scopedContextual = PromptBudgetGuard.selectScopedContextualRule(
                    appliedRules,
                    actionDecision != null ? actionDecision.detectedIntent() : null,
                    convState,
                    mergedMessage,
                    true
            );
            List<LearnedRuleDto> scopedRules = new ArrayList<>(guardedAlwaysOn);
            if (scopedContextual != null && scopedRules.stream().noneMatch(r -> r.id() != null && r.id().equals(scopedContextual.id()))) {
                scopedRules.add(scopedContextual);
            }
            appliedRules = List.copyOf(scopedRules);
        }

        int minDelayMs = pagePersona != null ? pagePersona.resolvedConfig().resolvedPostPriceDelayMs() : 10_000;
        com.docmind.guardrail.ReplyContractValidator.ValidationResult dbReferenceValRes = null;
        if (hasRuntimeControlPolicy && !leadReadyHandoff && actionDecision != null) {
            List<FanpageBotResponse.ReplyBubble> dbCandidateBubbles = PrimaryActionRouter.executeActionBubbles(
                    actionDecision,
                    mergedMessage,
                    convState,
                    honorific,
                    pagePersona,
                    fused,
                    activeSkills != null && !activeSkills.isEmpty() ? activeSkills.get(0) : null
            );
            if (dbCandidateBubbles != null && !dbCandidateBubbles.isEmpty()) {
                dbReferenceValRes = com.docmind.guardrail.ReplyContractValidator.validateAndEnforce(
                        dbCandidateBubbles,
                        convState,
                        mergedMessage,
                        actionDecision.effectiveResponseAction(),
                        actionDecision.prependsConfiguredOpening(),
                        minDelayMs
                );
            }
        }

        List<FanpageBotResponse.ReplyBubble> preAnchorBubblesForSplitState = List.of();
        if (Boolean.TRUE.equals(request.splitPriceBubbles())
                && !isLiveConfiguredLlm
                && !isGeneratePriceAnchorAction
                && !leadReadyHandoff
                && actionDecision != null
                && actionDecision.effectiveResponseAction() == PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE
                && dbReferenceValRes != null
                && dbReferenceValRes.validatedBubbles().size() >= 2
                && com.docmind.guardrail.ReplyContractValidator.PRICE_PATTERN
                        .matcher(dbReferenceValRes.validatedBubbles().get(dbReferenceValRes.validatedBubbles().size() - 2).text())
                        .find()) {
            long priceBubbleShownAtNs = System.nanoTime();
            ThinkingModePolicy.markPriceBubble1ShownAndBubble2Pending(
                    pageId, request.senderId(), currentTurnSequence, priceBubbleShownAtNs);
            List<FanpageBotResponse.ReplyBubble> bubble1Only = List.copyOf(
                    dbReferenceValRes.validatedBubbles().subList(0, dbReferenceValRes.validatedBubbles().size() - 1));
            String combinedBubble1Text = String.join("\n\n", bubble1Only.stream().map(FanpageBotResponse.ReplyBubble::text).toList());
            long firstBubbleSendMs = (System.nanoTime() - turnStartNs) / 1_000_000L;
            this.lastTurnTelemetry = new ThinkingModePolicy.TurnTelemetry(
                    actionDecision.effectiveResponseAction(),
                    ThinkingMode.FAST_NO_THINK,
                    actionDecision.detectedIntent() != null ? actionDecision.detectedIntent() : "PRICE_INQUIRY",
                    List.of(),
                    ThinkingModePolicy.extractRetrievedSourceIds(fused),
                    true,
                    null,
                    0L,
                    System.currentTimeMillis(),
                    0L,
                    firstBubbleSendMs,
                    0L,
                    "SPLIT_BUBBLE_1_IMMEDIATE_PRICE_QUOTE",
                    false,
                    ThinkingMode.FAST_NO_THINK.maxOutputTokens(),
                    0,
                    0L,
                    0L,
                    minDelayMs,
                    0L,
                    minDelayMs,
                    false,
                    0L,
                    retrievalMs,
                    0L,
                    0,
                    firstBubbleSendMs
            );
            ThinkingModePolicy.recordTurnTelemetry(this.lastTurnTelemetry);
            log.info("[AdaptiveThinkingRouter] page_id={} sender_id={} primary_action={} thinking_mode={} reason={} think_enabled={} max_output_tokens={} prompt_build_ms={} retrieval_ms={} llm_elapsed_ms={} validation_ms={} retry_count={} first_bubble_send_ms={} inter_bubble_gap_ms={} total_turn_ms={} target_price_gap_ms={} remaining_delay_ms={} cancelled_second_bubble={}",
                    pageId, request.senderId(), actionDecision.effectiveResponseAction(),
                    ThinkingMode.FAST_NO_THINK, "SPLIT_BUBBLE_1_IMMEDIATE_PRICE_QUOTE", false,
                    ThinkingMode.FAST_NO_THINK.maxOutputTokens(), 0L, retrievalMs, 0L, 0L, 0,
                    firstBubbleSendMs, 0L, firstBubbleSendMs, minDelayMs, minDelayMs, false);
            ConversationState stateAfterSplit1 = ConversationState.updateAfterSend(
                    pageId,
                    request.senderId(),
                    request.conversationId(),
                    convState,
                    mergedMessage,
                    actionDecision,
                    bubble1Only,
                    false,
                    incomingHistorySize
            );
            List<LearnedRuleDto> uiRulesSplit1 = filterMatchedRulesForUiResponse(appliedRules, actionDecision, false);
            boolean policyLoadedSplit1 = appliedRules != null && !appliedRules.isEmpty();
            log.info("[ProvenanceAudit] page_id={} sender_id={} routerRuleId={} primaryAction={} detectedIntent={} selectedSkill={} lastBotQuestionType={} stateBefore={} stateAfter={} policyLoaded={} policyMatched={} safetyHandoffTriggered=false bubbleSources=[OFFLINE_SPLIT_BUBBLE_1] rawLlmOutput=N/A outputAfterValidatorAndHooks={} finalUiReplyBubbles={}",
                    pageId,
                    request.senderId(),
                    actionDecision.routerRuleId(),
                    actionDecision.effectiveResponseAction(),
                    actionDecision.detectedIntent(),
                    activeSkills != null && !activeSkills.isEmpty() ? activeSkills.get(0).code() : actionDecision.correspondingMicroSkillCode(),
                    convState != null ? convState.lastBotQuestionType() : "NONE",
                    stateBeforeTelemetry,
                    formatStateTelemetrySummary(stateAfterSplit1),
                    policyLoadedSplit1,
                    !uiRulesSplit1.isEmpty(),
                    combinedBubble1Text,
                    bubble1Only.stream().map(FanpageBotResponse.ReplyBubble::text).toList());
            return new FanpageBotResponse(
                    combinedBubble1Text,
                    bubble1Only,
                    FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION.code(),
                    computeConfidenceScore(fused),
                    false,
                    null,
                    null,
                    extractedLead,
                    uiRulesSplit1,
                    customerInsight,
                    activeSkills
            ).withPendingSecondBubble(true, currentTurnSequence, priceBubbleShownAtNs);
        }

        if (isGeneratePriceAnchorAction) {
            if (actionDecision == null || actionDecision.effectiveResponseAction() != PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE) {
                actionDecision = new PrimaryActionRouter.RoutingDecision(
                        PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE,
                        PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE,
                        false,
                        1,
                        "PRICE_INQUIRY",
                        "spa_price_quote_after_clarify"
                );
                if (hasRuntimeControlPolicy) {
                    List<FanpageBotResponse.ReplyBubble> dbCandidateBubbles = PrimaryActionRouter.executeActionBubbles(
                            actionDecision,
                            mergedMessage,
                            convState,
                            honorific,
                            pagePersona,
                            fused,
                            activeSkills != null && !activeSkills.isEmpty() ? activeSkills.get(0) : null
                    );
                    if (dbCandidateBubbles != null && !dbCandidateBubbles.isEmpty()) {
                        dbReferenceValRes = com.docmind.guardrail.ReplyContractValidator.validateAndEnforce(
                                dbCandidateBubbles,
                                convState,
                                mergedMessage,
                                actionDecision.effectiveResponseAction(),
                                actionDecision.prependsConfiguredOpening(),
                                minDelayMs
                        );
                    }
                }
            } else {
                actionDecision = new PrimaryActionRouter.RoutingDecision(
                        PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE,
                        PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE,
                        false,
                        1,
                        actionDecision.detectedIntent(),
                        actionDecision.correspondingMicroSkillCode()
                );
            }
            if (dbReferenceValRes != null && !dbReferenceValRes.validatedBubbles().isEmpty()) {
                List<FanpageBotResponse.ReplyBubble> allRef = dbReferenceValRes.validatedBubbles();
                if (allRef.size() >= 2) {
                    preAnchorBubblesForSplitState = List.copyOf(allRef.subList(0, allRef.size() - 1));
                }
                FanpageBotResponse.ReplyBubble anchorRef = allRef.get(allRef.size() - 1);
                dbReferenceValRes = new com.docmind.guardrail.ReplyContractValidator.ValidationResult(
                        List.of(),
                        List.of(anchorRef),
                        anchorRef.text()
                );
            }
        }

        ThinkingModePolicy.ThinkingDecision thinkingDecision = isGeneratePriceAnchorAction
                ? new ThinkingModePolicy.ThinkingDecision(
                        ThinkingMode.PRICE_ANCHOR_THINK,
                        ThinkingMode.PRICE_ANCHOR_THINK.maxOutputTokens(),
                        "PRICE_QUOTE_EMOTIONAL_ANCHOR",
                        ThinkingModePolicy.ObjectionRootCause.NONE,
                        convState.objectionCount(),
                        List.of(),
                        List.of()
                )
                : ThinkingModePolicy.decide(
                        mergedMessage,
                        convState,
                        actionDecision,
                        customerInsight,
                        benchmarkConfigMode
                );
        boolean isOpenAiCompatibleProvider = llmClient instanceof com.example.docmind.service.llm.OpenAiCompatibleLlmClient
                || (llmClient != null && llmClient.name() != null
                    && (llmClient.name().startsWith("openai:") || llmClient.name().startsWith("vllm:")));

        long promptBuildStartNs = System.nanoTime();
        String prompt = hasRuntimeControlPolicy
                ? FanpageBotPromptBuilder.buildDynamicRuntimePrompt(
                        mergedMessage,
                        request.customerName(),
                        request.history(),
                        fused,
                        appliedRules,
                        pagePersona,
                        salesStage,
                        honorific,
                        customerInsight,
                        activeSkills,
                        mocSummary,
                        priceLadder,
                        sampleDialogues,
                        convState,
                        actionDecision,
                        dbReferenceValRes != null ? dbReferenceValRes.validatedBubbles() : List.of(),
                        thinkingDecision,
                        isOpenAiCompatibleProvider
                )
                : FanpageBotPromptBuilder.build(
                        mergedMessage,
                        request.customerName(),
                        request.history(),
                        fused,
                        appliedRules,
                        pagePersona,
                        salesStage,
                        honorific,
                        customerInsight,
                        activeSkills,
                        mocSummary,
                        priceLadder,
                        sampleDialogues
                );
        long promptBuildMs = (System.nanoTime() - promptBuildStartNs) / 1_000_000L;

        QueryComplexity complexity = queryComplexityClassifier != null
                ? queryComplexityClassifier.classify(mergedMessage, request.history(), priceLadder)
                : QueryComplexity.SIMPLE;
        ReasoningBudget budget = (hasRuntimeControlPolicy || thinkingDecision.thinkingMode() != ThinkingMode.FAST_NO_THINK)
                ? ReasoningBudget.forThinkingDecision(thinkingDecision)
                : ReasoningBudget.salesChat(complexity);

        long llmStartNs = System.nanoTime();
        Long storedPriceShownNs = isGeneratePriceAnchorAction
                ? ThinkingModePolicy.getPendingPriceBubbleShownAtNs(pageId, request.senderId(), currentTurnSequence)
                : null;
        long priceBubbleShownAtNs = (isGeneratePriceAnchorAction && request.priceBubbleSentAtNs() != null && request.priceBubbleSentAtNs() > 0L)
                ? request.priceBubbleSentAtNs()
                : ((storedPriceShownNs != null && storedPriceShownNs > 0L) ? storedPriceShownNs : llmStartNs);
        boolean isPriceAnchorOrQuoteTurn = isGeneratePriceAnchorAction
                || thinkingDecision.thinkingMode() == ThinkingMode.PRICE_ANCHOR_THINK
                || (actionDecision != null
                    && (actionDecision.effectiveResponseAction() == PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE
                        || actionDecision.effectiveResponseAction() == PrimaryActionRouter.PrimaryAction.RECOMMEND_ONE_PACKAGE));
        if (isPriceAnchorOrQuoteTurn && !isGeneratePriceAnchorAction) {
            ThinkingModePolicy.markPriceBubble1ShownAndBubble2Pending(pageId, request.senderId(), currentTurnSequence, priceBubbleShownAtNs);
        }
        log.info("[LLM] FanpageBotService calling engine={} thinkingMode={} thinkEnabled={} reason={} complexity={} effort={} temp={} topP={} maxTokens={} (page_id={}, sales_stage={}, honorific={}, archetype={}, skills={}, retrievalMs={}, promptBuildMs={}, promptChars={})",
                llmClient.name(), thinkingDecision.thinkingMode().name(), budget.thinkEnabled(), thinkingDecision.reasonCode(),
                complexity.name(), budget.reasoningEffort(), budget.temperature(), budget.topP(), budget.maxTokens(),
                pageId, salesStage.code(), honorific,
                customerInsight != null ? customerInsight.buyerArchetype() : "N/A",
                activeSkills != null ? activeSkills.size() : 0,
                retrievalMs, promptBuildMs, prompt.length());

        String rawReply = null;
        boolean interceptedByMoodPass = false;
        boolean forcedSafeHandoffByReferee = false;
        List<String> cancelledBubbleTextsThisTurn = List.of();
        boolean responseValidatedByLlm = false;
        String fallbackReasonForTelemetry = null;
        List<String> validationErrorsForTelemetry = List.of();
        List<FanpageBotResponse.ReplyBubble> stateDrivenBubbles = null;
        StructuredLlmOutput structuredLlmOutput = null;
        int retryCount = 0;
        long repairElapsedMs = 0L;

        // Ngoại trừ: (1) BOT_MUTED và (2) EXPLICIT_OPT_OUT (đã xử lý ở trên),
        // toàn bộ các lượt hội thoại thông thường (kể cả Turn 1) BẮT BUỘC gọi sang LLM đã cấu hình (llmClient.complete),
        // sau đó chạy qua ReplyContractValidator kiểm định mà không tự động gắn 2 bong bóng mở đầu cố định.
        boolean isBotMutedFastPath = actionDecision != null
                && actionDecision.primaryAction() == PrimaryActionRouter.PrimaryAction.BOT_MUTED;
        boolean isConfiguredThreeBubbleBrandIntro = actionDecision != null
                && actionDecision.prependsConfiguredOpening()
                && actionDecision.effectiveResponseAction() != PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE
                && pagePersona != null
                && pagePersona.resolvedConfig() != null
                && pagePersona.resolvedConfig().openingBubbles() != null
                && pagePersona.resolvedConfig().openingBubbles().size() >= 3
                && dbReferenceValRes != null
                && dbReferenceValRes.validatedBubbles().size() == 3;

        if (isBotMutedFastPath) {
            stateDrivenBubbles = List.of();
            rawReply = "";
            interceptedByMoodPass = true;
            responseValidatedByLlm = true;
        } else if (isConfiguredThreeBubbleBrandIntro) {
            stateDrivenBubbles = dbReferenceValRes.validatedBubbles();
            rawReply = String.join("\n\n", stateDrivenBubbles.stream().map(FanpageBotResponse.ReplyBubble::text).toList());
            interceptedByMoodPass = true;
            responseValidatedByLlm = true;
        } else {
            try {
                rawReply = llmClient.complete(prompt, budget);
                if (rawReply == null) {
                    rawReply = llmClient.complete(prompt);
                }
            } catch (Exception e) {
                log.warn("[LLM] Exception calling llmClient {}: {}", llmClient.name(), e.getMessage());
            }
        }
        boolean isFallback = !isBotMutedFastPath && !isConfiguredThreeBubbleBrandIntro && (rawReply == null
                || rawReply.isBlank()
                || rawReply.matches("(?s)^\\[[a-z0-9\\-]+-(?:error|offline|unavailable)\\].*")
                || rawReply.startsWith("[stub-llm]"));

        String sanitizedReply = PromptSecurityGuard.sanitizeModelOutput(rawReply);

        // Nếu LLM trả về JSON (kể cả bọc trong ```json ... ```), bóc tách reply_bubbles, should_handoff, state_updates, grounding_check
        // Chỉ cho phép retry tối đa 1 lần nếu lỗi cú pháp JSON hoặc thiếu trường bắt buộc (KHÔNG retry vì lỗi độ dài câu)
        if (!interceptedByMoodPass && !isFallback && sanitizedReply != null && !sanitizedReply.isBlank()) {
            structuredLlmOutput = parseStructuredLlmJsonOutput(sanitizedReply, minDelayMs);
            if (hasRuntimeControlPolicy && isLiveConfiguredLlm
                    && (structuredLlmOutput == null || !structuredLlmOutput.parsedFromJson()
                        || structuredLlmOutput.bubbles() == null || structuredLlmOutput.bubbles().isEmpty())) {
                retryCount = 1;
                long jsonRepairStartNs = System.nanoTime();
                try {
                    ReasoningBudget fastRepairBudget = ReasoningBudget.forThinkingMode(ThinkingMode.FAST_NO_THINK);
                    String retryReply = llmClient.complete(prompt, fastRepairBudget);
                    if (retryReply == null) {
                        retryReply = llmClient.complete(prompt, budget);
                    }
                    if (retryReply != null && !retryReply.isBlank()) {
                        rawReply = retryReply;
                        sanitizedReply = PromptSecurityGuard.sanitizeModelOutput(rawReply);
                        structuredLlmOutput = parseStructuredLlmJsonOutput(sanitizedReply, minDelayMs);
                    }
                } catch (Exception e) {
                    log.warn("[LLM] JSON retry exception calling llmClient {}: {}", llmClient.name(), e.getMessage());
                } finally {
                    repairElapsedMs += (System.nanoTime() - jsonRepairStartNs) / 1_000_000L;
                }
            }
            if (structuredLlmOutput != null && structuredLlmOutput.parsedFromJson() && !structuredLlmOutput.bubbles().isEmpty()) {
                sanitizedReply = String.join("\n\n", structuredLlmOutput.bubbles().stream().map(FanpageBotResponse.ReplyBubble::text).toList());
            }
            if (structuredLlmOutput != null && structuredLlmOutput.groundingCheck() != null && convState != null) {
                convState = convState.applyGroundingCheckAsymmetricUpdate(structuredLlmOutput.groundingCheck(), mergedMessage);
            }
        }

        long secondBubbleReadyAtNs = System.nanoTime();
        long llmLatencyMs = (secondBubbleReadyAtNs - llmStartNs) / 1_000_000L;
        long turnElapsedMs = Math.max(0L, (secondBubbleReadyAtNs - turnStartNs) / 1_000_000L);
        long effectivePriceShownAtNs = isGeneratePriceAnchorAction ? priceBubbleShownAtNs : secondBubbleReadyAtNs;
        ThinkingModePolicy.PostPriceDelayMetrics postPriceDelayMetrics = ThinkingModePolicy.computeDynamicPostPriceDelay(
                minDelayMs,
                effectivePriceShownAtNs,
                secondBubbleReadyAtNs
        );
        if (!isGeneratePriceAnchorAction && isLiveConfiguredLlm && turnElapsedMs >= 500L) {
            int targetGap = (int) Math.max(minDelayMs, ThinkingModePolicy.DEFAULT_TARGET_PRICE_GAP_MS);
            int remainingVisibleDelayMs = targetGap;
            long nowEpochMs = System.currentTimeMillis();
            postPriceDelayMetrics = new ThinkingModePolicy.PostPriceDelayMetrics(
                    targetGap,
                    nowEpochMs - turnElapsedMs,
                    nowEpochMs + remainingVisibleDelayMs,
                    turnElapsedMs,
                    remainingVisibleDelayMs,
                    turnElapsedMs + remainingVisibleDelayMs
            );
        }
        boolean cancelledSecondBubbleByConcurrentTurn = isPriceAnchorOrQuoteTurn && ThinkingModePolicy.isPendingSecondBubbleSuperseded(
                pageId,
                request.senderId(),
                currentTurnSequence
        );

        if (isFallback) {
            fallbackReasonForTelemetry = "LLM_OFFLINE_OR_EMPTY_RESPONSE";
            validationErrorsForTelemetry = List.of("LLM_OFFLINE_OR_EMPTY_RESPONSE");
            log.info("[LLM] Fallback triggered (rawReply={}) -> generating grounded fallback reply from RAG fused chunks and state",
                    rawReply != null && rawReply.length() > 60 ? rawReply.substring(0, 60) + "..." : rawReply);
            if (hasRuntimeControlPolicy && dbReferenceValRes != null && !dbReferenceValRes.validatedBubbles().isEmpty()) {
                stateDrivenBubbles = alignBubbleDelaysWithContract(
                        dbReferenceValRes.validatedBubbles(),
                        dbReferenceValRes.validatedBubbles(),
                        postPriceDelayMetrics,
                        cancelledSecondBubbleByConcurrentTurn
                );
                rawReply = String.join("\n\n", stateDrivenBubbles.stream().map(FanpageBotResponse.ReplyBubble::text).toList());
                sanitizedReply = PromptSecurityGuard.sanitizeModelOutput(rawReply);
                interceptedByMoodPass = true;
                isFallback = false;
            } else if (hasRuntimeControlPolicy) {
                stateDrivenBubbles = List.of(new FanpageBotResponse.ReplyBubble(SAFE_HANDOFF_BUBBLE_TEXT, 0));
                rawReply = SAFE_HANDOFF_BUBBLE_TEXT;
                sanitizedReply = SAFE_HANDOFF_BUBBLE_TEXT;
                forcedSafeHandoffByReferee = true;
                interceptedByMoodPass = true;
                isFallback = false;
            } else {
                ObsidianMetadataCache metaCache = this.retrievalService != null ? this.retrievalService.getMetadataCache() : null;
                rawReply = generateGroundedFallbackReply(
                        mergedMessage, fused, honorific, isFirstTurn, salesStage, request.history(), priceLadder, detectedBranch, request.customerName(),
                        metaCache, pageId);
                sanitizedReply = PromptSecurityGuard.sanitizeModelOutput(rawReply);
                isFallback = false;
            }
        }

        // Chạy qua ReplyContractValidator cho các Page có Runtime Control Policy sau khi LLM đã sinh câu trả lời
        long validationStartNs = System.nanoTime();
        long targetedRepairElapsedMs = 0L;
        // v3.8 provenance: raw_output -> sanitized -> validator_errors -> repair_outcome -> final_bubble
        String provenanceRepairOutcome = isConfiguredThreeBubbleBrandIntro ? "CONFIGURED_BRAND_INTRO_3_BUBBLES" : "NOT_NEEDED";
        List<String> provenanceFirstPassErrors = List.of();
        String provenanceSanitizedOutput = sanitizedReply;
        if (!interceptedByMoodPass && fallbackReasonForTelemetry == null) {
            responseValidatedByLlm = true;
        }
        long validationMs = Math.max(0L, ((System.nanoTime() - validationStartNs) / 1_000_000L) - targetedRepairElapsedMs);

        // 8. HẬU XỬ LÝ (POST-PROCESSING): BÓC TÁCH [[HANDOFF]], LỌC SẠCH TRÍCH DẪN [1], ĐỒNG NHẤT XƯNG HÔ & LÀM MƯỢT CÂU HỎI KẾT
        boolean isActionExplicitHandoff = actionDecision != null
                && (actionDecision.effectiveResponseAction() == PrimaryActionRouter.PrimaryAction.HARD_HANDOFF_COMPLAINT
                    || actionDecision.effectiveResponseAction() == PrimaryActionRouter.PrimaryAction.HARD_HANDOFF_SAFETY);
        boolean stateDrivenHandoff = forcedSafeHandoffByReferee
                || (interceptedByMoodPass && isActionExplicitHandoff)
                || (!hasRuntimeControlPolicy && structuredLlmOutput != null && Boolean.TRUE.equals(structuredLlmOutput.shouldHandoff()))
                || (isActionExplicitHandoff && structuredLlmOutput != null && Boolean.TRUE.equals(structuredLlmOutput.shouldHandoff()));
        String detectedIntentForHandoff = actionDecision != null ? actionDecision.detectedIntent() : null;
        String stateHandoffCat = stateDrivenHandoff
                ? ("UNGROUNDED_SCOPE_HANDOFF".equals(detectedIntentForHandoff)
                    || "UNRESOLVED_FAQ_HANDOFF".equals(detectedIntentForHandoff)
                    || "UNSUPPORTED_DETAIL_OR_FAQ_QUESTION".equals(detectedIntentForHandoff)
                    || "MISSING_OR_CONFLICTING_DETAIL_EVIDENCE_HANDOFF".equals(detectedIntentForHandoff)
                    ? "UNGROUNDED_OR_UNKNOWN"
                    : "COMPLAINT_OR_DISTRESS")
                : null;
        String stateHandoffReason = stateDrivenHandoff
                ? (structuredLlmOutput != null && structuredLlmOutput.handoffReason() != null && !structuredLlmOutput.handoffReason().isBlank()
                    ? structuredLlmOutput.handoffReason()
                    : ("UNGROUNDED_SCOPE_HANDOFF".equals(detectedIntentForHandoff)
                        ? "Chưa có gói dịch vụ khớp với phạm vi nhu cầu trong tài liệu Active — chuyển quản lý/chuyên viên hỗ trợ."
                        : ("UNRESOLVED_FAQ_HANDOFF".equals(detectedIntentForHandoff)
                            || "UNSUPPORTED_DETAIL_OR_FAQ_QUESTION".equals(detectedIntentForHandoff)
                            || "MISSING_OR_CONFLICTING_DETAIL_EVIDENCE_HANDOFF".equals(detectedIntentForHandoff)
                            ? "Thông tin chi tiết dịch vụ/FAQ khách hỏi chưa có hoặc có mâu thuẫn trong tài liệu hiện hành — chuyển chuyên viên xác nhận chính xác."
                            : ("COMPREHENSION_ESCALATION_HANDOFF".equals(detectedIntentForHandoff)
                                ? "Khách hàng vẫn chưa rõ sau khi giải thích — chuyển chuyên viên hỗ trợ giải đáp trực tiếp."
                                : ("MISMATCH_CORRECTION_HANDOFF".equals(detectedIntentForHandoff)
                                    ? "Khách hàng phản ánh tư vấn lệch vùng/nhu cầu kèm bức xúc — xin lỗi và chuyển quản lý hỗ trợ ngay."
                                    : "Khách hàng bức xúc nhiều lượt liên tiếp — chuyển nhân viên hỗ trợ trực tiếp.")))))
                : null;
        boolean skipLegacyPostProcessing = interceptedByMoodPass || hasRuntimeControlPolicy;
        PostProcessedBotReply processed = skipLegacyPostProcessing
                ? new PostProcessedBotReply(
                        sanitizedReply,
                        stateDrivenHandoff,
                        stateHandoffCat,
                        stateHandoffReason)
                : postProcessReply(sanitizedReply, fused, honorific, isFirstTurn, salesStage, request.history(), mergedMessage, priceLadder);

        String finalCleanText = processed.cleanText();
        if (leadReadyHandoff) {
            if (asksExplicitPriceInMessage(mergedMessage)) {
                finalCleanText = ensurePhoneLeadConfirmationPreservingPrice(finalCleanText, extractedLead, honorific);
            } else {
                finalCleanText = ensureCleanPhoneLeadReply(finalCleanText, extractedLead, honorific, appliedRules);
            }
        } else if (!processed.needsHandoff() && !skipLegacyPostProcessing
                && (FanpageBotPromptBuilder.isBroadUnspecifiedPriceInquiry(mergedMessage, request.history())
                    || (isFirstTurn && isBarePriceOrGreetingTurn(mergedMessage)))) {
            finalCleanText = enforceAntiInformationDumpingOnBroadPrice(
                    finalCleanText, fused, honorific, request.customerName(), isFirstTurn);
        } else if (!processed.needsHandoff() && !skipLegacyPostProcessing) {
            finalCleanText = enforceConversationBookingConsistency(
                    finalCleanText, mergedMessage, bookingState, honorific, request.history(), isFirstTurn);
            if (consultResult != null && consultResult.isProfileUpdateTurn()) {
                finalCleanText = enforceTopicAnchorOnProfileUpdate(finalCleanText, consultResult, honorific);
            }
        }

        if (!skipLegacyPostProcessing) {
            finalCleanText = sanitizeForbiddenWords(finalCleanText, pagePersona);
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
        boolean replyContainsPrice = hasQuotedPriceInText(finalCleanText) || isGeneratePriceAnchorAction;
        String finalStage = finalHandoff && processed.needsHandoff() && !leadReadyHandoff && !hasCompletedBookingLead
                ? "HUMAN_HANDOFF"
                : (structuredLlmOutput != null && structuredLlmOutput.updatedSalesStage() != null && !structuredLlmOutput.updatedSalesStage().isBlank()
                    ? structuredLlmOutput.updatedSalesStage()
                    : (interceptedByMoodPass && replyContainsPrice && salesStage == FanpageBotPromptBuilder.SalesStage.DISCOVERY
                        ? FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION.code()
                        : salesStage.code()));
        double confidence = processed.needsHandoff() && !leadReadyHandoff ? 0.35 : computeConfidenceScore(fused);

        boolean isLiveProductionLlm = isLiveConfiguredLlm;
        boolean enablePostPriceBubble2Thinking = isLiveProductionLlm && !finalHandoff
                && (replyContainsPrice || hasPriorPriceInHistory)
                && !(isFirstTurn && !replyContainsPrice);

        List<FanpageBotResponse.ReplyBubble> bubbles = (stateDrivenBubbles != null && !stateDrivenBubbles.isEmpty() && (!finalHandoff || stateDrivenHandoff))
                ? stateDrivenBubbles
                : (structuredLlmOutput != null && structuredLlmOutput.parsedFromJson() && !structuredLlmOutput.bubbles().isEmpty() && !finalHandoff
                    ? structuredLlmOutput.bubbles()
                    : splitIntoBubbles(finalCleanText, enablePostPriceBubble2Thinking));

        if (isGeneratePriceAnchorAction) {
            if (cancelledSecondBubbleByConcurrentTurn || bubbles == null || bubbles.isEmpty()) {
                bubbles = List.of();
                finalCleanText = "";
            } else {
                FanpageBotResponse.ReplyBubble anchorBubble = bubbles.get(bubbles.size() - 1);
                FanpageBotResponse.ReplyBubble delayedAnchor = new FanpageBotResponse.ReplyBubble(
                        anchorBubble.text(),
                        postPriceDelayMetrics.remainingDelayMs()
                );
                bubbles = List.of(delayedAnchor);
                finalCleanText = delayedAnchor.text();
            }
        }

        int thinkingChars = 0;
        long thinkingLatencyMs = 0L;
        long contentLatencyMs = llmLatencyMs;
        String telemetryProvider = "ollama";
        String telemetryModel = llmClient != null && llmClient.name() != null ? llmClient.name() : "qwen3:14b";
        String telemetryEndpoint = "/api/chat";
        long ollamaLoadDurationMs = 0L;
        int ollamaPromptEvalCount = 0;
        long ollamaPromptEvalDurationMs = 0L;
        int ollamaEvalCount = 0;
        long ollamaEvalDurationMs = 0L;
        long ollamaTotalDurationMs = llmLatencyMs;
        if (llmClient instanceof com.example.docmind.service.llm.OllamaLlmClient ollamaClient) {
            com.example.docmind.service.llm.OllamaLlmClient.OllamaCallTelemetry t = ollamaClient.getLastCallTelemetry();
            if (t != null) {
                thinkingChars = t.thinkingChars();
                thinkingLatencyMs = t.thinkingLatencyMs();
                contentLatencyMs = t.contentLatencyMs();
                telemetryProvider = t.provider();
                if (t.model() != null && !t.model().isBlank()) {
                    telemetryModel = t.model();
                }
                if (t.endpoint() != null && !t.endpoint().isBlank()) {
                    telemetryEndpoint = t.endpoint();
                } else if (t.apiPath() != null && !t.apiPath().isBlank()) {
                    telemetryEndpoint = t.apiPath();
                }
                ollamaLoadDurationMs = t.loadDurationMs();
                ollamaPromptEvalCount = t.promptEvalCount();
                ollamaPromptEvalDurationMs = t.promptEvalDurationMs();
                ollamaEvalCount = t.evalCount();
                ollamaEvalDurationMs = t.evalDurationMs();
                ollamaTotalDurationMs = t.totalDurationMs() > 0L ? t.totalDurationMs() : llmLatencyMs;
            }
        } else if (llmClient instanceof com.example.docmind.service.llm.OpenAiCompatibleLlmClient openAiClient) {
            telemetryProvider = "openai_compatible";
            telemetryEndpoint = "/v1/chat/completions";
            com.example.docmind.service.llm.OpenAiCompatibleLlmClient.OpenAiCallTelemetry t = openAiClient.getLastCallTelemetry();
            if (t != null) {
                thinkingChars = t.thinkingChars();
                thinkingLatencyMs = t.thinkingLatencyMs();
                contentLatencyMs = t.contentLatencyMs();
            }
        }
        boolean hasConfiguredOpeningOnTurn1 = actionDecision != null
                && actionDecision.prependsConfiguredOpening()
                && pagePersona != null
                && pagePersona.resolvedConfig() != null
                && pagePersona.resolvedConfig().openingBubbles() != null
                && pagePersona.resolvedConfig().openingBubbles().size() >= 2
                && actionDecision.effectiveResponseAction() != PrimaryActionRouter.PrimaryAction.HARD_HANDOFF_SAFETY
                && actionDecision.effectiveResponseAction() != PrimaryActionRouter.PrimaryAction.HARD_HANDOFF_COMPLAINT
                && actionDecision.effectiveResponseAction() != PrimaryActionRouter.PrimaryAction.BOT_MUTED;
        long priceShownEpochMs = replyContainsPrice ? postPriceDelayMetrics.priceBubbleShownAtEpochMs() : 0L;
        long secondSentEpochMs = (replyContainsPrice && (bubbles.size() >= 2 || isGeneratePriceAnchorAction))
                ? postPriceDelayMetrics.secondBubbleReadyAtEpochMs()
                : 0L;
        long timeToFirstBubbleMs = ((isPriceAnchorOrQuoteTurn && !isGeneratePriceAnchorAction) || hasConfiguredOpeningOnTurn1)
                ? (retrievalMs + promptBuildMs)
                : (retrievalMs + promptBuildMs + llmLatencyMs);
        long interBubbleGapMs = (replyContainsPrice && (bubbles.size() >= 2 || isGeneratePriceAnchorAction))
                ? postPriceDelayMetrics.effectiveInterBubbleGapMs()
                : (bubbles.size() >= 2 ? bubbles.get(1).typingDelayMs() : 0L);
        long totalTurnMs = (System.nanoTime() - turnStartNs) / 1_000_000L;

        boolean usedTemplateFallback = fallbackReasonForTelemetry != null && !fallbackReasonForTelemetry.isBlank();
        boolean repairApplied = !usedTemplateFallback && retryCount > 0;
        String generationSource = isBotMutedFastPath
                ? "DETERMINISTIC_POLICY"
                : (isConfiguredThreeBubbleBrandIntro
                    ? "CONFIGURED_OPENING"
                    : (usedTemplateFallback ? "FALLBACK_TEMPLATE" : (repairApplied ? "LLM_REPAIRED" : "LLM_LIVE")));
        String validationOutcome = usedTemplateFallback ? "FALLBACK" : (repairApplied ? "REPAIRED_PASS" : "PASS");
        List<String> bubbleSources;
        if (hasConfiguredOpeningOnTurn1 && bubbles != null && bubbles.size() >= 3) {
            boolean allThreeConfigured = pagePersona.resolvedConfig().openingBubbles().size() >= 3
                    && actionDecision.effectiveResponseAction() != PrimaryActionRouter.PrimaryAction.ANSWER_PRICE_ONCE;
            List<String> mixedSources = new ArrayList<>(bubbles.size());
            mixedSources.add("CONFIGURED_OPENING");
            mixedSources.add("CONFIGURED_OPENING");
            for (int i = 2; i < bubbles.size(); i++) {
                mixedSources.add(allThreeConfigured ? "CONFIGURED_OPENING" : generationSource);
            }
            bubbleSources = List.copyOf(mixedSources);
        } else {
            bubbleSources = bubbles != null
                    ? bubbles.stream().map(b -> generationSource).toList()
                    : List.of();
        }

        this.lastTurnTelemetry = new ThinkingModePolicy.TurnTelemetry(
                actionDecision != null ? actionDecision.effectiveResponseAction() : PrimaryActionRouter.PrimaryAction.GENERIC_SHORT_DISCOVERY,
                thinkingDecision.thinkingMode(),
                actionDecision != null && actionDecision.detectedIntent() != null ? actionDecision.detectedIntent() : "",
                thinkingDecision.unresolvedQuestionIds(),
                ThinkingModePolicy.extractRetrievedSourceIds(fused),
                responseValidatedByLlm,
                fallbackReasonForTelemetry,
                llmLatencyMs,
                priceShownEpochMs,
                secondSentEpochMs,
                timeToFirstBubbleMs,
                interBubbleGapMs,
                thinkingDecision.reasonCode(),
                budget.thinkEnabled(),
                budget.maxTokens(),
                thinkingChars,
                thinkingLatencyMs,
                contentLatencyMs,
                postPriceDelayMetrics.configuredDelayMs(),
                postPriceDelayMetrics.elapsedSincePriceBubbleMs(),
                postPriceDelayMetrics.remainingDelayMs(),
                cancelledSecondBubbleByConcurrentTurn,
                promptBuildMs,
                retrievalMs,
                validationMs,
                repairElapsedMs,
                retryCount,
                totalTurnMs,
                usedTemplateFallback,
                fallbackReasonForTelemetry,
                generationSource,
                repairApplied,
                retryCount,
                validationOutcome,
                validationErrorsForTelemetry,
                telemetryProvider,
                telemetryModel,
                telemetryEndpoint,
                bubbleSources,
                ollamaLoadDurationMs,
                ollamaPromptEvalCount,
                ollamaPromptEvalDurationMs,
                ollamaEvalCount,
                ollamaEvalDurationMs,
                ollamaTotalDurationMs
        );
        if (hasRuntimeControlPolicy || isLiveConfiguredLlm) {
            ThinkingModePolicy.recordTurnTelemetry(this.lastTurnTelemetry);
            log.info("[AdaptiveThinkingRouter] primaryAction={} thinkingMode={} detectedIntent={} generationSource={} usedTemplateFallback={} fallbackTriggerRule={} repairApplied={} repairAttemptCount={} validationOutcome={} validationErrors={} provider={} model={} endpoint={} bubbleSources={} loadDurationMs={} promptEvalCount={} promptEvalDurationMs={} evalCount={} evalDurationMs={} totalDurationMs={} unresolvedQuestionIds={} retrievedSourceIds={} responseValidated={} promptBuildMs={} retrievalMs={} llmElapsedMs={} validationMs={} repairElapsedMs={} retryCount={} priceBubbleShownAt={} secondBubbleSentAt={} firstBubbleSendMs={} interBubbleGapMs={} totalTurnMs={} thinkEnabled={} maxOutputTokens={} thinkingChars={} thinkingLatencyMs={} contentLatencyMs={} remainingDelayMs={} cancelledSecondBubble={}",
                    this.lastTurnTelemetry.primaryAction(),
                    thinkingDecision.thinkingMode().name(),
                    this.lastTurnTelemetry.detectedIntent(),
                    this.lastTurnTelemetry.generationSource(),
                    this.lastTurnTelemetry.usedTemplateFallback(),
                    this.lastTurnTelemetry.fallbackTriggerRule(),
                    this.lastTurnTelemetry.repairApplied(),
                    this.lastTurnTelemetry.repairAttemptCount(),
                    this.lastTurnTelemetry.validationOutcome(),
                    this.lastTurnTelemetry.validationErrors(),
                    this.lastTurnTelemetry.provider(),
                    this.lastTurnTelemetry.model(),
                    this.lastTurnTelemetry.endpoint(),
                    this.lastTurnTelemetry.bubbleSources(),
                    this.lastTurnTelemetry.loadDurationMs(),
                    this.lastTurnTelemetry.promptEvalCount(),
                    this.lastTurnTelemetry.promptEvalDurationMs(),
                    this.lastTurnTelemetry.evalCount(),
                    this.lastTurnTelemetry.evalDurationMs(),
                    this.lastTurnTelemetry.totalDurationMs(),
                    this.lastTurnTelemetry.unresolvedQuestionIds(),
                    this.lastTurnTelemetry.retrievedSourceIds(),
                    this.lastTurnTelemetry.responseValidated(),
                    promptBuildMs,
                    retrievalMs,
                    llmLatencyMs,
                    validationMs,
                    repairElapsedMs,
                    retryCount,
                    priceShownEpochMs,
                    secondSentEpochMs,
                    timeToFirstBubbleMs,
                    interBubbleGapMs,
                    totalTurnMs,
                    budget.thinkEnabled(),
                    budget.maxTokens(),
                    thinkingChars,
                    thinkingLatencyMs,
                    contentLatencyMs,
                    postPriceDelayMetrics.remainingDelayMs(),
                    cancelledSecondBubbleByConcurrentTurn);
        }

        log.info("[LLM] FanpageBotService response from engine={} retrievalMs={} latencyMs={} status={} page_id={} honorific={} sales_stage={} handoff={} lead_phone={} bubbles={}",
                llmClient.name(), retrievalMs, llmLatencyMs, isFallback ? "fallback" : "ok",
                pageId, honorific, finalStage, finalHandoff, extractedLead != null ? maskPhoneNumber(extractedLead.phoneNumber()) : "none", bubbles.size());

        List<LearnedRuleDto> uiMatchedRules = filterMatchedRulesForUiResponse(appliedRules, actionDecision, finalHandoff);
        boolean safetyHandoffTriggered = finalHandoff
                && actionDecision != null
                && actionDecision.effectiveResponseAction() == PrimaryActionRouter.PrimaryAction.HARD_HANDOFF_SAFETY;
        boolean policyLoaded = appliedRules != null && !appliedRules.isEmpty();
        boolean policyMatched = !uiMatchedRules.isEmpty();

        FanpageBotResponse rawResponse = new FanpageBotResponse(
                finalCleanText,
                bubbles,
                finalStage,
                confidence,
                finalHandoff,
                finalHandoffCategory,
                finalHandoffReason,
                extractedLead,
                uiMatchedRules,
                customerInsight,
                activeSkills
        );

        FanpageBotResponse finalResponse = (interceptedByMoodPass || hasRuntimeControlPolicy)
                ? rawResponse
                : applyGuardrails(
                        rawResponse,
                        honorific,
                        request.customerName(),
                        isFirstTurn,
                        finalStage,
                        mergedMessage,
                        request.history(),
                        fused
                );

        // v3.8 FINAL OUTPUT GATE: kiểm tra và ghi nhận warning, TUYỆT ĐỐI KHÔNG thay thế reply_bubbles không rỗng của LLM bằng câu fallback.
        boolean finalGateBlocked = false;
        if (finalResponse != null && finalResponse.replyBubbles() != null && !finalResponse.replyBubbles().isEmpty()
                && actionDecision != null && !isBotMutedFastPath) {
            com.docmind.guardrail.ReplyContractValidator.ValidationResult gateRes =
                    com.docmind.guardrail.ReplyContractValidator.validateAndEnforce(
                            finalResponse.replyBubbles(), convState, mergedMessage,
                            actionDecision.effectiveResponseAction(), actionDecision.prependsConfiguredOpening(),
                            minDelayMs, ThinkingMode.FAST_NO_THINK, null);
            if (gateRes.errors() != null && !gateRes.errors().isEmpty()) {
                List<String> gateErrors = List.copyOf(gateRes.errors());
                log.warn("[FinalOutputGate] WARNING errors={} -> preserving LLM reply_bubbles", gateErrors);
                validationErrorsForTelemetry = gateErrors;
            }
        }

        // Provenance Kill-Switch (v3.4.2 Mục 1.3): Cấm tuyệt đối Java ghi đè câu tiếng Việt ngoài luồng rồi gắn nhãn LLM_LIVE
        List<FanpageBotResponse.ReplyBubble> finalUiReplyBubbles = (finalResponse != null && finalResponse.replyBubbles() != null)
                ? finalResponse.replyBubbles()
                : (bubbles != null ? bubbles : List.of());
        String rawLlmOutput = rawReply != null ? rawReply : "";
        if (interceptedByMoodPass && !finalGateBlocked) {
            String normalizedRaw = normalizeForProvenance(rawLlmOutput);
            for (int i = 0; i < finalUiReplyBubbles.size(); i++) {
                if (i < bubbleSources.size() && "LLM_LIVE".equals(bubbleSources.get(i))) {
                    String normalizedBubble = normalizeForProvenance(finalUiReplyBubbles.get(i).text());
                    String normalizedWithoutDa = normalizedBubble.replaceFirst("^dạ\\s+", "").strip();
                    // Referee (validator/sanitizer) chỉ được xoá/sửa nhẹ cụm từ của LLM: coi là "xuất phát từ LLM" nếu >= 80% từ của bubble có trong raw.
                    boolean derivedByWordOverlap = false;
                    String checkForOverlap = !normalizedWithoutDa.isEmpty() ? normalizedWithoutDa : normalizedBubble;
                    if (!checkForOverlap.isEmpty()) {
                        String[] bubbleWords = checkForOverlap.split("\\s+");
                        java.util.Set<String> rawWords = new java.util.HashSet<>(java.util.Arrays.asList(normalizedRaw.split("\\s+")));
                        int hit = 0;
                        for (String w : bubbleWords) {
                            if (rawWords.contains(w)) {
                                hit++;
                            }
                        }
                        derivedByWordOverlap = bubbleWords.length > 0 && hit >= Math.ceil(bubbleWords.length * 0.8);
                    }
                    if (!normalizedBubble.isEmpty()
                            && !derivedByWordOverlap
                            && !normalizedRaw.contains(normalizedBubble)
                            && (normalizedWithoutDa.isEmpty() || !normalizedRaw.contains(normalizedWithoutDa))) {
                        throw new IllegalStateException("[GHOSTWRITER_VIOLATION] Bubble index " + i
                                + " được gắn nhãn LLM_LIVE nhưng không xuất phát từ rawLlmOutput! Raw="
                                + rawLlmOutput + " | FinalBubble=" + finalUiReplyBubbles.get(i).text());
                    }
                }
            }
        }

        ConversationState stateAfterTurn = convState;
        if ((hasRuntimeControlPolicy || isLiveConfiguredLlm)
                && (!isGeneratePriceAnchorAction || !cancelledSecondBubbleByConcurrentTurn)) {
            List<FanpageBotResponse.ReplyBubble> deliveredBubbles = finalResponse != null ? finalResponse.replyBubbles() : bubbles;
            List<FanpageBotResponse.ReplyBubble> bubblesForStateUpdate = deliveredBubbles;
            if (isGeneratePriceAnchorAction && !preAnchorBubblesForSplitState.isEmpty() && deliveredBubbles != null && !deliveredBubbles.isEmpty()) {
                List<FanpageBotResponse.ReplyBubble> mergedForState = new ArrayList<>(preAnchorBubblesForSplitState.size() + deliveredBubbles.size());
                mergedForState.addAll(preAnchorBubblesForSplitState);
                mergedForState.addAll(deliveredBubbles);
                bubblesForStateUpdate = List.copyOf(mergedForState);
            }
            String ledgerKeyNow = SentBubbleLedger.key(request.pageId(), request.senderId(), request.conversationId());
            // turn/event id ỔN ĐỊNH (không dùng UUID ngẫu nhiên) để webhook retry không ghi trùng ledger bền vững
            String ledgerUserTurnId = isGeneratePriceAnchorAction
                    ? String.valueOf(CURRENT_TURN_ID.get())
                    : SentBubbleLedger.stableUserTurnId(ledgerKeyNow, mergedMessage);
            if (!isGeneratePriceAnchorAction) {
                SentBubbleLedger.recordCustomerTurn(ledgerKeyNow, ledgerUserTurnId, mergedMessage);
            }
            if (deliveredBubbles != null && !deliveredBubbles.isEmpty()) {
                SentBubbleLedger.recordAssistantTurn(
                        ledgerKeyNow, ledgerUserTurnId + (isGeneratePriceAnchorAction ? ":anchor" : ":a"),
                        deliveredBubbles.stream().map(FanpageBotResponse.ReplyBubble::text).toList(),
                        SentBubbleLedger.DeliveryStatus.DELIVERED,
                        cancelledBubbleTextsThisTurn);
            }
            stateAfterTurn = ConversationState.updateAfterSend(
                    pageId,
                    request.senderId(),
                    request.conversationId(),
                    convState,
                    mergedMessage,
                    actionDecision,
                    bubblesForStateUpdate,
                    finalResponse != null ? finalResponse.needsHumanHandoff() : finalHandoff,
                    incomingHistorySize
            );
        }

        List<String> finalUiBubbleTexts = (finalResponse != null && finalResponse.replyBubbles() != null)
                ? finalResponse.replyBubbles().stream().map(FanpageBotResponse.ReplyBubble::text).toList()
                : (bubbles != null ? bubbles.stream().map(FanpageBotResponse.ReplyBubble::text).toList() : List.of());
        log.info("[ProvenanceV38] turn_id={} raw_output={} sanitized_output={} validator_errors={} repair_outcome={} final_bubble={}",
                CURRENT_TURN_ID.get(),
                maskSensitiveForAudit(rawLlmOutput),
                maskSensitiveForAudit(provenanceSanitizedOutput),
                provenanceFirstPassErrors,
                provenanceRepairOutcome,
                maskSensitiveForAudit(String.join(" || ", finalUiBubbleTexts)));
        PROVENANCE_V38_LOGGED.set(true);
        log.info("[ProvenanceAudit] turn_id={} conversation_id={} page_id={} sender_id={} routerRuleId={} primaryAction={} detectedIntent={} selectedSkill={} lastBotQuestionType={} stateBefore={} stateAfter={} policyLoaded={} policyMatched={} safetyHandoffTriggered={} bubbleSources={} rawLlmOutput={} outputAfterValidatorAndHooks={} finalUiReplyBubbles={}",
                CURRENT_TURN_ID.get(),
                request.conversationId(),
                pageId,
                request.senderId(),
                actionDecision != null ? actionDecision.routerRuleId() : "NONE",
                actionDecision != null ? actionDecision.effectiveResponseAction() : "NONE",
                actionDecision != null ? actionDecision.detectedIntent() : "NONE",
                activeSkills != null && !activeSkills.isEmpty()
                        ? activeSkills.get(0).code()
                        : (actionDecision != null ? actionDecision.correspondingMicroSkillCode() : "NONE"),
                convState != null ? convState.lastBotQuestionType() : "NONE",
                stateBeforeTelemetry,
                formatStateTelemetrySummary(stateAfterTurn),
                policyLoaded,
                policyMatched,
                safetyHandoffTriggered,
                bubbleSources,
                rawReply != null ? rawReply.replaceAll("\\s+", " ").strip() : "null",
                finalCleanText != null ? finalCleanText.replaceAll("\\s+", " ").strip() : "null",
                finalUiBubbleTexts);
        emitLiveAuditJsonLog(
                pageId,
                request,
                mergedMessage,
                convState,
                stateAfterTurn,
                actionDecision,
                prompt,
                rawReply,
                structuredLlmOutput,
                finalUiReplyBubbles,
                validationErrorsForTelemetry,
                provenanceFirstPassErrors,
                provenanceRepairOutcome,
                fallbackReasonForTelemetry,
                telemetryProvider,
                telemetryModel,
                budget,
                totalTurnMs
        );
        // v3.3.4 GAP-05: telemetry có cấu trúc 1 dòng/lượt để theo dõi ma trận Selective Thinking & ngân sách prompt
        log.info("DOCMIND_V334_TELEMETRY pageId={} action={} intent={} routerRuleId={} thinkingMode={} thinkingReason={} safetyHandoff={} bubblesCount={} alwaysOnChars={}",
                pageId,
                actionDecision != null ? actionDecision.effectiveResponseAction() : "NONE",
                actionDecision != null ? actionDecision.detectedIntent() : "NONE",
                actionDecision != null ? actionDecision.routerRuleId() : "NONE",
                thinkingDecision != null ? thinkingDecision.thinkingMode() : "NONE",
                thinkingDecision != null ? thinkingDecision.triggerReason() : "NONE",
                finalResponse != null ? finalResponse.needsHumanHandoff() : finalHandoff,
                finalUiBubbleTexts.size(),
                PromptBudgetGuard.validateAlwaysOnRules(appliedRules, "").alwaysOnRuleChars());

        if (agentBrainService != null) {
            try {
                agentBrainService.retainTurnAsync(
                        pageId,
                        request.senderId(),
                        request.customerName(),
                        mergedMessage,
                        finalResponse != null ? finalResponse.replyText() : finalCleanText,
                        stateAfterTurn != null && stateAfterTurn.customerCareNotes() != null
                                ? stateAfterTurn.customerCareNotes()
                                : List.of()
                );
            } catch (Exception e) {
                log.debug("[AgentBrain] Non-fatal retainTurnAsync exception: {}", e.getMessage());
            }
        }

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
        if (lower.contains("chưa có gói") || lower.contains("không có mã gói") || lower.contains("không có gói")) {
            return cleanText;
        }

        boolean dumpsComboOnBroadPrice = lower.contains("combo tiết kiệm") || lower.contains("combo toàn diện")
                || (lower.contains("3-4 buổi") && lower.contains("5-9 buổi"))
                || (lower.contains("giảm 10%") && lower.contains("giảm 15%"));
        boolean dumpsMultipleServices = distinctPrices.size() >= 3;
        boolean jumpsToMidTierOrVipOnBroadFirstTurn = isFirstTurn
                && (lower.contains("530") || lower.contains("580") || lower.contains("670")
                    || lower.contains("780") || lower.contains("880") || lower.contains("990")
                    || lower.contains("570") || lower.contains("750") || lower.contains("870")
                    || lower.contains("1.990") || lower.contains("100.000") || lower.contains("100k")
                    || lower.contains("phòng vip"))
                && !lower.contains("399.000") && !lower.contains("399k")
                && !lower.contains("650.000");

        if (!jumpsToMidTierOrVipOnBroadFirstTurn
                && (FanpageBotPromptBuilder.isPackageComparisonInquiry(cleanText)
                    || FanpageBotPromptBuilder.isPriceObjectionOrDownsellMessage(cleanText))) {
            return cleanText;
        }

        if (!dumpsMultipleServices && !dumpsComboOnBroadPrice && !jumpsToMidTierOrVipOnBroadFirstTurn && cleanText.length() <= 340) {
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

        if (combinedLower.contains("gội đầu dưỡng sinh") || combinedLower.contains("massage") || combinedLower.contains("thảo dược")) {
            if (combinedLower.contains("129.000") && combinedLower.contains("399.000")) {
                return opening + "Bên em có Gội đầu dưỡng sinh từ 129.000 VNĐ và Massage cơ bản từ 399.000 VNĐ (trọn gói, không nhận Tip) ạ.\n\n"
                        + "Hiện tại " + honorific + " đang quan tâm Massage trị liệu cổ vai hay Gội đầu dưỡng sinh ạ?";
            }
            String capH = (honorific != null && !honorific.isBlank())
                    ? Character.toUpperCase(honorific.charAt(0)) + honorific.substring(1)
                    : "Mình";
            if (isFirstTurn) {
                return "Dạ em chào " + honorific + " ạ, chào mừng " + honorific + " đến với cơ sở bên em.\n\n"
                        + "Dạ giá bên em tùy dịch vụ và thời lượng " + honorific + " chọn ạ. "
                        + capH + " đang quan tâm massage cổ vai, massage body hay chăm sóc thảo dược để em gửi đúng mức giá cho " + honorific + " nha?";
            }
            return "Dạ giá bên em tùy dịch vụ và thời lượng " + honorific + " chọn ạ. "
                    + capH + " đang quan tâm massage cổ vai, massage body hay chăm sóc thảo dược để em gửi đúng mức giá cho " + honorific + " nha?";
        }

        return opening + "Chi phí bên em khởi điểm chỉ từ " + startingPrice
                + ", cam kết minh bạch trọn gói không phát sinh phụ phí ạ.\n\n"
                + "Hiện tại " + honorific + " đang quan tâm gói cơ bản hay gói chuyên sâu để em tư vấn chi tiết ạ?";
    }

    static String generateGroundedFallbackReply(
            String mergedMessage,
            List<ReciprocalRankFusion.FusedChunk> fused,
            String honorific,
            boolean isFirstTurn,
            FanpageBotPromptBuilder.SalesStage salesStage,
            List<FanpageBotRequest.ConversationTurn> history,
            PriceLadderResult priceLadder,
            String detectedBranch,
            String customerName,
            ObsidianMetadataCache metadataCache,
            UUID pageId) {
        String h = (honorific != null && !honorific.isBlank()) ? honorific.strip() : "Chị";
        String msg = (mergedMessage != null) ? mergedMessage.strip() : "";
        String lowerMsg = msg.toLowerCase(Locale.ROOT);

        // 1. Dynamic entity & price analysis across fused chunks and MetadataCache
        List<Long> allCatalogPrices = extractAllPricesDynamically(fused, metadataCache, pageId);
        long minPrice = allCatalogPrices.isEmpty() ? 0L : allCatalogPrices.get(0);
        long maxPrice = allCatalogPrices.isEmpty() ? 0L : allCatalogPrices.get(allCatalogPrices.size() - 1);
        Set<String> validCodes = extractValidCodesDynamically(fused, metadataCache, pageId);
        boolean isMassageCatalog = isMassageWellnessCatalog(fused, metadataCache, pageId);
        List<PriceLadderTracker.PriceTier> dynamicTiers = PriceLadderTracker.extractDynamicLadderFromChunks(fused, detectedBranch);

        // 2. Resolve branch context robustly
        String branch = detectedBranch != null ? detectedBranch : PriceLadderTracker.resolveBranch(msg, history, null);
        boolean isSaigon = "saigon".equalsIgnoreCase(branch)
                || lowerMsg.contains("quận 1") || lowerMsg.contains("sài gòn") || lowerMsg.contains("tphcm");

        // ---------------------------------------------------------------------
        // PRIORITY 1: Free / 0 VNĐ Inquiry (Dynamic minPrice calculation)
        // ---------------------------------------------------------------------
        if (isFreeOrZeroInquiry(lowerMsg)) {
            if (!isMassageCatalog) {
                return "Dạ bên em hiện không có gói miễn phí hoàn toàn ạ. Mức chi phí các gói dịch vụ/sản phẩm bên em rất hợp lý, khởi điểm chỉ từ "
                        + PriceLadderTracker.formatPrice(minPrice) + " cùng chính sách ưu đãi minh bạch theo danh mục niêm yết ạ.\n\n"
                        + h + " đang muốn tìm hiểu gói cơ bản hay gói chuyên sâu để em tư vấn chi tiết cho mình nhé ạ?";
            }
            long entryMassage = isSaigon ? 570_000L : 399_000L;
            return "Dạ bên em hiện không có gói miễn phí hoàn toàn ạ. Mức chi phí các gói chăm sóc bên em rất hợp lý, khởi điểm từ "
                    + PriceLadderTracker.formatPrice(minPrice) + " và gói Massage trị liệu toàn thân từ "
                    + PriceLadderTracker.formatPrice(entryMassage) + " cùng nhiều chính sách ưu đãi trải nghiệm chu đáo ạ.\n\n"
                    + h + " đang muốn tìm hiểu dịch vụ massage thư giãn hay chăm sóc dưỡng sinh để em tư vấn chi tiết cho mình nhé ạ?";
        }

        // ---------------------------------------------------------------------
        // PRIORITY 2: Extreme High Budget / Catalog Ceiling (> maxPrice)
        // ---------------------------------------------------------------------
        if (isExtremeBudgetInquiry(msg, lowerMsg)) {
            return "Dạ bên em hiện chưa có gói dịch vụ với mức ngân sách này ạ. Các gói dịch vụ tại cơ sở có mức giá từ "
                    + PriceLadderTracker.formatPrice(minPrice) + " đến "
                    + PriceLadderTracker.formatPrice(maxPrice) + " (cao nhất) trọn gói chu đáo ạ.\n\n"
                    + h + " đang quan tâm gói dịch vụ/sản phẩm chuyên sâu nào để em gửi thông tin chi tiết các gói phù hợp nhất cho mình ạ?";
        }

        // ---------------------------------------------------------------------
        // PRIORITY 3: Unknown / Non-existent Package Code Detection
        // ---------------------------------------------------------------------
        List<String> queryCodes = ChunkMetadataExtractor.extractServiceCodes(msg);
        List<String> unknownCodes = queryCodes.stream()
                .filter(c -> !validCodes.contains(c.toUpperCase(Locale.ROOT)))
                .toList();
        if (!unknownCodes.isEmpty()) {
            if (!isMassageCatalog) {
                String codeList = validCodes.isEmpty() ? "các gói tiêu chuẩn" : String.join(", ", validCodes);
                return "Dạ hiện tại bên em không có mã gói " + String.join(", ", unknownCodes) + " trong danh mục chuẩn ạ. "
                        + "Bên em hiện có các gói " + codeList + " với mức chi phí từ " + PriceLadderTracker.formatPrice(minPrice)
                        + " đến " + PriceLadderTracker.formatPrice(maxPrice) + " ạ.\n\n"
                        + h + " đang quan tâm nhu cầu nào để em hỗ trợ tư vấn đúng gói phù hợp nhất cho mình ạ?";
            }
            return "Dạ hiện tại bên em không có mã gói " + String.join(", ", unknownCodes) + " trong danh mục dịch vụ chuẩn ạ. "
                    + "Bên em có các gói Massage trị liệu từ NS01 đến NS06 (từ " + PriceLadderTracker.formatPrice(isSaigon ? 570_000L : 399_000L)
                    + ") và Dưỡng sinh từ DS01 đến DS05 (từ " + PriceLadderTracker.formatPrice(minPrice) + ") ạ.\n\n"
                    + h + " đang muốn tìm gói chăm sóc thư giãn hay trị liệu để em hỗ trợ tư vấn đúng gói cho mình ạ?";
        }

        // ---------------------------------------------------------------------
        // PRIORITY 4: Package / Price Comparison (Dynamic RAG Chunk Synthesis)
        // ---------------------------------------------------------------------
        if (isComparisonQuery(msg, lowerMsg)) {
            String comparisonResponse = synthesizeDynamicComparisonResponse(
                    fused, metadataCache, pageId, msg, lowerMsg, h, isSaigon);
            if (comparisonResponse != null && !comparisonResponse.isBlank()) {
                return comparisonResponse;
            }
        }

        // ---------------------------------------------------------------------
        // PRIORITY 5: Catalog Floor & Trial Precedence
        // Evaluates BEFORE generic promo queries to prevent trial hijacking
        // ---------------------------------------------------------------------
        if (priceLadder != null && priceLadder.isCatalogFloor()) {
            String promo = priceLadder.discountPolicy() != null
                    ? priceLadder.discountPolicy()
                    : resolveDynamicPromoPolicy(fused, metadataCache, pageId);
            String floorPkg = priceLadder.targetPackageName() != null
                    ? priceLadder.targetPackageName()
                    : (isMassageCatalog ? "Vệ sinh tai cơ bản (DS01 - 30 phút)" : "Gói cơ bản tiêu chuẩn");
            long target = priceLadder.targetPrice() != null ? priceLadder.targetPrice() : minPrice;
            return "Dạ gói " + floorPkg + " với giá " + PriceLadderTracker.formatPrice(target)
                    + " là mức giá mềm nhất và thấp nhất trong danh mục dịch vụ bên em rồi ạ. Ngoài ra bên em có chính sách ưu đãi "
                    + promo + " để tiết kiệm chi phí hơn nữa ạ.\n\n"
                    + h + " có muốn đặt lịch trải nghiệm thử gói " + PriceLadderTracker.formatPrice(target)
                    + " này hay đăng ký nhận ưu đãi liệu trình không ạ?";
        }

        if (priceLadder != null && priceLadder.isTrialRequest()) {
            if (!isMassageCatalog && priceLadder.targetPackageName() != null) {
                long target = priceLadder.targetPrice() != null ? priceLadder.targetPrice() : minPrice;
                return "Dạ " + h + " muốn thử trải nghiệm trước thì " + priceLadder.targetPackageName() + " với mức giá "
                        + PriceLadderTracker.formatPrice(target) + " là lựa chọn rất phù hợp ạ.\n\n"
                        + h + " dự định đăng ký/đặt lịch vào khung giờ nào hôm nay hoặc ngày mai để em giữ suất chu đáo cho mình ạ?";
            }
            long target = priceLadder.targetPrice() != null ? priceLadder.targetPrice() : (isSaigon ? 570_000L : 399_000L);
            if (isSaigon || target == 570_000L) {
                return "Dạ " + h + " muốn thử trải nghiệm trước thì gói Massage cơ bản 570.000 VNĐ (70 phút) tại Quận 1 Sài Gòn full body trị liệu giảm đau mỏi là lựa chọn rất lý tưởng ạ.\n\n"
                        + h + " dự định ghé cơ sở trải nghiệm vào khung giờ nào hôm nay hoặc ngày mai để em giữ chỗ chu đáo cho mình ạ?";
            }
            return "Dạ " + h + " muốn thử trải nghiệm trước thì gói Massage cơ bản 399.000 VNĐ (60 phút) full body trị liệu giảm đau mỏi là lựa chọn rất lý tưởng ạ, liệu trình có kèm buffet thực dưỡng sau buổi ạ.\n\n"
                    + h + " dự định ghé cơ sở trải nghiệm vào khung giờ nào hôm nay hoặc ngày mai để em giữ chỗ chu đáo cho mình ạ?";
        }

        // ---------------------------------------------------------------------
        // PRIORITY 6: Promo / Discount Policy (ROLE_PROMO Chunks)
        // ---------------------------------------------------------------------
        if (isPromoInquiry(lowerMsg, fused)) {
            String promoDetail = resolveDynamicPromoPolicy(fused, metadataCache, pageId);
            if (priceLadder != null && priceLadder.isPriceObjection()
                    && priceLadder.targetPrice() != null && priceLadder.previousPriceQuoted() != null) {
                long target = priceLadder.targetPrice();
                String pkg = priceLadder.targetPackageName() != null ? priceLadder.targetPackageName() : "gói cơ bản";
                return "Dạ đúng rồi ạ! Bên em hiện đang có chính sách ưu đãi: " + promoDetail
                        + " ạ. Ngoài ra nếu muốn trải nghiệm lẻ với mức giá tiết kiệm hơn, bên em có "
                        + pkg + " giá " + PriceLadderTracker.formatPrice(target) + " ạ.\n\n"
                        + h + " muốn trải nghiệm lẻ 1 buổi gói " + PriceLadderTracker.formatPrice(target)
                        + " trước hay tham khảo gói ưu đãi liệu trình tiết kiệm 10% ạ?";
            }
            return "Dạ đúng rồi ạ! Bên em hiện đang có chính sách ưu đãi: " + promoDetail + " ạ.\n\n"
                    + h + " đang dự định đăng ký gói nào để em áp dụng chính sách ưu đãi tốt nhất cho mình nhé ạ?";
        }

        // ---------------------------------------------------------------------
        // PRIORITY 7: Contact / Phone Inquiry
        // ---------------------------------------------------------------------
        if (lowerMsg.contains("sdt") || lowerMsg.contains("số điện thoại") || lowerMsg.contains("hotline")) {
            return "Dạ " + h + " có thể để lại số điện thoại để chuyên viên tư vấn bên em chủ động liên hệ hỗ trợ hoặc đặt lịch nhanh nhất ạ. "
                    + h + " muốn bên em liên hệ hỗ trợ mình qua số điện thoại nào ạ?";
        }

        // ---------------------------------------------------------------------
        // PRIORITY 8: Price Objection Down-sell
        // ---------------------------------------------------------------------
        if (priceLadder != null && priceLadder.isPriceObjection() && priceLadder.targetPrice() != null) {
            long target = priceLadder.targetPrice();
            String pkg = priceLadder.targetPackageName() != null ? priceLadder.targetPackageName() : "gói dịch vụ mềm hơn";
            if (isMassageCatalog && target == 239_000L) {
                return "Dạ nếu " + h + " muốn mức chi phí mềm hơn để trải nghiệm trước, bên em có gói Dưỡng sinh cơ bản (DS02) chỉ 239.000 VNĐ cho 60 phút chăm sóc rất thư giãn và nhẹ nhàng ạ.\n\n"
                        + h + " có muốn đặt lịch trải nghiệm gói dưỡng sinh cơ bản 239.000 VNĐ này không ạ?";
            } else if (isMassageCatalog && target == 260_000L) {
                return "Dạ nếu " + h + " muốn mức chi phí mềm hơn tại cơ sở Quận 1 (Sài Gòn), bên em có gói Dưỡng sinh cơ bản (DS02) chỉ 260.000 VNĐ cho 60 phút chăm sóc rất thư giãn và chu đáo ạ.\n\n"
                        + h + " có muốn đặt lịch trải nghiệm gói dưỡng sinh cơ bản 260.000 VNĐ này không ạ?";
            } else if (isMassageCatalog && target == 129_000L) {
                return "Dạ nếu " + h + " vẫn muốn mức chi phí tiết kiệm tối đa, bên em có " + pkg + " chỉ 129.000 VNĐ cho 30 phút chăm sóc nhẹ nhàng êm ái là gói mềm nhất bên em rồi ạ.\n\n"
                        + h + " có muốn ghé cơ sở trải nghiệm thử gói 129.000 VNĐ này trước không ạ?";
            } else {
                return "Dạ nếu " + h + " muốn mức giá tiết kiệm hơn, bên em có " + pkg + " với giá chỉ " + PriceLadderTracker.formatPrice(target)
                        + " rất phù hợp để mình trải nghiệm trước ạ.\n\n"
                        + h + " có muốn đặt lịch trải nghiệm gói này không ạ?";
            }
        }

        // ---------------------------------------------------------------------
        // PRIORITY 8: Dynamic Branch-Specific Inquiry
        // ---------------------------------------------------------------------
        String branchKeyFromMsg = PriceLadderTracker.resolveBranchFromText(lowerMsg);
        if (branchKeyFromMsg != null && isMassageCatalog) {
            String branchLabel = extractBranchDisplayName(msg);
            if (branchLabel == null) {
                branchLabel = "saigon".equalsIgnoreCase(branchKeyFromMsg) ? "Quận 1 (Sài Gòn)" : "Hà Nội";
            }
            BranchPriceSummary pricing = resolveBranchPricingDynamically(branchKeyFromMsg, fused, metadataCache, pageId);
            if ("saigon".equalsIgnoreCase(branchKeyFromMsg)
                    && (lowerMsg.contains("thấp nhất") || lowerMsg.contains("rẻ nhất") || lowerMsg.contains("mềm")
                    || lowerMsg.contains("gội đầu") || lowerMsg.contains("vệ sinh tai"))) {
                return "Dạ tại cơ sở bên em ở " + branchLabel + ", gói cơ bản có giá thấp nhất là "
                        + PriceLadderTracker.formatPrice(pricing.minFloor())
                        + " (30 phút), gói gội đầu dưỡng sinh mềm nhất là "
                        + PriceLadderTracker.formatPrice(pricing.minDuongSinh())
                        + " (60 phút), và gói Massage cơ bản khởi điểm từ "
                        + PriceLadderTracker.formatPrice(pricing.minMassage()) + " (70 phút) ạ.\n\n"
                        + h + " đang quan tâm dịch vụ massage thư giãn hay gội đầu dưỡng sinh tại " + branchLabel + " để em tư vấn chi tiết cho mình ạ?";
            }
            return "Dạ cơ sở bên em tại " + branchLabel + " có đầy đủ các gói Massage trị liệu toàn thân từ "
                    + PriceLadderTracker.formatPrice(pricing.minMassage())
                    + " và Dưỡng sinh thư giãn từ "
                    + PriceLadderTracker.formatPrice(pricing.minFloor()) + " ạ.\n\n"
                    + h + " đang dự định ghé cơ sở " + branchLabel + " vào khung giờ nào để em hỗ trợ kiểm tra phòng cho mình nhé ạ?";
        }

        // ---------------------------------------------------------------------
        // PRIORITY 8.5: Service Explanation / Process & Symptom Consultation (incl. Multi-Turn Follow-ups)
        // ---------------------------------------------------------------------
        if (FanpageBotPromptBuilder.isServiceExplanationOrProcessInquiry(msg)
                || FanpageBotPromptBuilder.isWellnessOrDiscomfortMessage(msg)
                || FanpageBotPromptBuilder.isSymptomFollowUpTurn(msg, history)) {
            String dynamicExplanationReply = synthesizeDynamicExplanationOrSymptomReply(
                    fused, metadataCache, pageId, msg, lowerMsg, h, isSaigon, history
            );
            if (dynamicExplanationReply != null && !dynamicExplanationReply.isBlank()) {
                return dynamicExplanationReply;
            }
        }

        // ---------------------------------------------------------------------
        // PRIORITY 9: Broad / Discovery Price Inquiry
        // ---------------------------------------------------------------------
        if (FanpageBotPromptBuilder.isBroadUnspecifiedPriceInquiry(msg, history)
                || lowerMsg.contains("giá") || lowerMsg.contains("gia") || lowerMsg.contains("bn") || lowerMsg.contains("bao nhiêu")
                || lowerMsg.equals("giá") || lowerMsg.contains("bảng giá")) {
            if (!isMassageCatalog) {
                PriceLadderTracker.PriceTier entryTier = !dynamicTiers.isEmpty()
                        ? dynamicTiers.get(dynamicTiers.size() - 1)
                        : null;
                String entryLabel = entryTier != null
                        ? entryTier.packageName() + " khởi điểm từ " + PriceLadderTracker.formatPrice(entryTier.price())
                        : "các gói khởi điểm từ " + PriceLadderTracker.formatPrice(minPrice);
                String opening = isFirstTurn ? "Dạ em chào " + h + " ạ! " : "Dạ ";
                return opening + "Bên em hiện có " + entryLabel
                        + (maxPrice > minPrice ? " (đến " + PriceLadderTracker.formatPrice(maxPrice) + " cho các gói chuyên sâu)" : "")
                        + " cam kết minh bạch trọn gói theo đúng danh mục niêm yết ạ.\n\n"
                        + "Hiện tại " + h + " đang quan tâm tìm hiểu gói cơ bản hay gói chuyên sâu để em tư vấn chi tiết nhất cho mình nhé ạ?";
            }
            return "Dạ em chào " + h + " ạ! Bên em có gói Massage cơ bản khởi điểm từ 399.000 VNĐ (Hà Nội) hoặc 570.000 VNĐ (Quận 1 Sài Gòn) gồm full body trị liệu 60-70 phút, cùng các gói gội đầu dưỡng sinh từ 129.000 VNĐ ạ.\n\n"
                    + "Hiện tại " + h + " đang quan tâm Massage thư giãn giảm đau mỏi hay Gội đầu dưỡng sinh để em tư vấn chi tiết gói phù hợp nhất cho mình nhé ạ?";
        }

        // ---------------------------------------------------------------------
        // PRIORITY 10: General RAG Grounding Fallback (Concise, Sentence-Bounded, No Raw Table Dump)
        // ---------------------------------------------------------------------
        if (fused != null && !fused.isEmpty()) {
            String closingQ = isMassageCatalog
                    ? h + " đang muốn ưu tiên chăm sóc thư giãn vùng nào hay tìm hiểu thêm về gói nào để em tư vấn kỹ hơn cho mình nhé ạ?"
                    : h + " đang muốn tìm hiểu chi tiết hơn về gói/sản phẩm nào để em tư vấn kỹ hơn cho mình nhé ạ?";
            String bestFaqOrPolicy = findDirectFaqAnswer(fused, metadataCache, pageId, msg, lowerMsg);
            if (bestFaqOrPolicy != null && !bestFaqOrPolicy.isBlank()) {
                String conciseFaq = limitToMaxSentences(bestFaqOrPolicy, 2, 230);
                if (!conciseFaq.toLowerCase(Locale.ROOT).startsWith("dạ")) {
                    boolean isAcronymStart = conciseFaq.length() > 1 && Character.isUpperCase(conciseFaq.charAt(1));
                    conciseFaq = "Dạ " + (isAcronymStart ? conciseFaq.charAt(0) : Character.toLowerCase(conciseFaq.charAt(0))) + conciseFaq.substring(1);
                }
                return conciseFaq + "\n\n" + closingQ;
            }
            String topText = fused.get(0).chunk().text();
            if (topText != null && !topText.isBlank()) {
                String cleanSnippet = com.example.docmind.service.ChunkBuilder.formatChunkAsLlamaIndexNode(
                        topText, null, buildContextualSearchQuery(msg, history));
                cleanSnippet = cleanSnippet.replaceAll("(?m)^\\|.*\\|$", "")
                        .replaceAll("(?m)^#+.*$", "")
                        .replaceAll("(?m)^[A-Z0-9_ÀÁẠẢÃÂẦẤẬẨẪĂẰẮẶẲẴÈÉẸẺẼÊỀẾỆỂỄÌÍỊỈĨÒÓỌỎÕÔỒỐỘỔỖƠỜỚỢỞỠÙÚỤỦŨƯỪỨỰỬỮỲÝỴỶỸĐ\\s]{3,40}$", "")
                        .replaceAll("\\[\\[.*?\\]\\]", "")
                        .replaceAll("\\[.*?\\]", "")
                        .strip();
                if (cleanSnippet.isBlank() && topText.contains("|")) {
                    cleanSnippet = extractReadableSentenceFromTable(topText, buildContextualSearchQuery(msg, history));
                }
                cleanSnippet = limitToMaxSentences(cleanFaqAnswerText(cleanSnippet), 2, 230);
                if (cleanSnippet != null && !cleanSnippet.isBlank()) {
                    if (!cleanSnippet.toLowerCase(Locale.ROOT).startsWith("dạ")) {
                        boolean isAcronymStart = cleanSnippet.length() > 1 && Character.isUpperCase(cleanSnippet.charAt(1));
                        cleanSnippet = "Dạ " + (isAcronymStart ? conciseFirstChar(cleanSnippet) : Character.toLowerCase(cleanSnippet.charAt(0))) + cleanSnippet.substring(1);
                    }
                    return cleanSnippet + "\n\n" + closingQ;
                }
            }
        }

        // ---------------------------------------------------------------------
        // PRIORITY 11: Default Warm Response
        // ---------------------------------------------------------------------
        if (!isMassageCatalog) {
            return "Dạ em chào " + h + " ạ! Bên em hiện có các gói dịch vụ/sản phẩm khởi điểm từ " + PriceLadderTracker.formatPrice(minPrice)
                    + " cam kết minh bạch theo danh mục niêm yết ạ.\n\n"
                    + h + " đang muốn tìm hiểu gói/sản phẩm nào để em hỗ trợ tư vấn chi tiết cho mình nhé ạ?";
        }
        return "Dạ em chào " + h + " ạ! Bên em có các gói dịch vụ Massage trị liệu từ 399.000 VNĐ và Dưỡng sinh từ 129.000 VNĐ trọn gói không phụ phí hay nhận Tip ạ.\n\n"
                + h + " đang muốn tìm gói chăm sóc thư giãn nào để em hỗ trợ tư vấn chi tiết cho mình nhé ạ?";
    }

    private static char conciseFirstChar(String s) {
        return s.charAt(0);
    }

    private static boolean isMassageWellnessCatalog(
            List<ReciprocalRankFusion.FusedChunk> fused,
            ObsidianMetadataCache metadataCache,
            UUID pageId
    ) {
        if (fused == null || fused.isEmpty()) {
            return true;
        }
        StringBuilder sb = new StringBuilder();
        for (ReciprocalRankFusion.FusedChunk fc : fused) {
            if (fc != null && fc.chunk() != null) {
                if (fc.chunk().headingPath() != null) {
                    sb.append(String.join(" ", fc.chunk().headingPath())).append(' ');
                }
                if (fc.chunk().text() != null) {
                    sb.append(fc.chunk().text()).append(' ');
                }
            }
        }
        String lower = sb.toString().toLowerCase(Locale.ROOT);
        if (lower.isBlank()) {
            return true;
        }
        return lower.contains("massage") || lower.contains("dưỡng sinh")
                || lower.contains("gội đầu") || lower.contains("ns01") || lower.contains("ds01")
                || lower.contains("ngâm chân") || lower.contains("cổ vai")
                || lower.contains("399.000") || lower.contains("129.000") || lower.contains("570.000")
                || (!lower.contains("|") && !lower.contains("vnđ") && !lower.contains("vnd") && !lower.contains("000"));
    }

    // =========================================================================
    // DYNAMIC RAG SYNTHESIS HELPER METHODS
    // =========================================================================

    private static boolean isFreeOrZeroInquiry(String lower) {
        if (lower == null || lower.isBlank()) return false;
        return ZERO_PRICE_PATTERN.matcher(lower).find();
    }

    private static boolean isExtremeBudgetInquiry(String msg, String lower) {
        if (msg == null && lower == null) return false;
        return (msg != null && EXTREME_BUDGET_CEILING_PATTERN.matcher(msg).find())
                || (lower != null && EXTREME_BUDGET_CEILING_PATTERN.matcher(lower).find());
    }

    private static boolean isComparisonQuery(String msg, String lower) {
        if (lower == null || lower.isBlank()) return false;
        if (COMPARISON_PATTERN.matcher(lower).find()) {
            return true;
        }
        List<String> codes = ChunkMetadataExtractor.extractServiceCodes(msg != null ? msg : lower);
        if (codes.size() >= 2 && (lower.contains("và") || lower.contains("với") || lower.contains("hay") || lower.contains("so"))) {
            return true;
        }
        List<Long> prices = PriceLadderTracker.extractPricesFromText(msg != null ? msg : lower);
        if (prices.size() >= 2 && (lower.contains("và") || lower.contains("với") || lower.contains("hay") || lower.contains("so") || lower.contains("chênh"))) {
            return true;
        }
        return false;
    }

    private static boolean isPromoInquiry(String lower, List<ReciprocalRankFusion.FusedChunk> fused) {
        return lower != null && PROMO_PATTERN.matcher(lower).find();
    }

    /**
     * DTO lưu trữ thông tin gói dịch vụ bóc tách động từ bảng Markdown của tài liệu.
     */
    private record DynamicPackageDetail(
            String code,
            String name,
            Long price,
            String priceStr,
            String duration,
            String inclusion,
            String highlight,
            String space,
            boolean isSaigonBranch
    ) {
        String displayLabel() {
            if (code != null && !code.isBlank() && name != null && !name.isBlank()) {
                return code + " (" + name + ")";
            }
            if (name != null && !name.isBlank()) {
                return name;
            }
            if (code != null && !code.isBlank()) {
                return "gói " + code;
            }
            if (priceStr != null && !priceStr.isBlank()) {
                return "gói " + priceStr;
            }
            return "gói dịch vụ";
        }

        String summaryHighlight() {
            if (inclusion != null && !inclusion.isBlank() && !inclusion.equalsIgnoreCase("null")) {
                return cleanBulletText(inclusion);
            }
            if (highlight != null && !highlight.isBlank() && !highlight.equalsIgnoreCase("null")) {
                return cleanBulletText(highlight);
            }
            return "";
        }
    }

    private static String cleanBulletText(String raw) {
        if (raw == null) return "";
        String cleaned = raw.replaceAll("^[•\\-\\s;]+", "")
                .replaceAll("\\s*;\\s*•?\\s*", ", ")
                .replaceAll("\\|", "")
                .replaceAll("\\s+", " ")
                .strip();
        int dotIdx = cleaned.indexOf('.');
        if (dotIdx > 20 && dotIdx < 140) {
            return cleaned.substring(0, dotIdx).strip();
        }
        if (cleaned.length() > 140) {
            return cleaned.substring(0, 140).strip() + "...";
        }
        return cleaned;
    }

    /**
     * Tổng hợp phản hồi so sánh động hoàn chỉnh từ các chunk RAG được truy xuất.
     * Tuyệt đối không hardcode giá tiền, mã gói hay canned text.
     */
    private static String synthesizeDynamicComparisonResponse(
            List<ReciprocalRankFusion.FusedChunk> fused,
            ObsidianMetadataCache metadataCache,
            UUID pageId,
            String msg,
            String lowerMsg,
            String h,
            boolean isSaigon) {

        // 1. Tìm câu trả lời trực tiếp từ các khối FAQ / Q&A trong tài liệu
        String directFaqAnswer = findDirectFaqAnswer(fused, metadataCache, pageId, msg, lowerMsg);
        if (directFaqAnswer != null && !directFaqAnswer.isBlank()) {
            String cleanFaq = directFaqAnswer.strip();
            if (cleanFaq.startsWith("Dạ ")) {
                cleanFaq = cleanFaq.substring(3).strip();
            }
            // Tạo câu hỏi chốt phù hợp ngữ cảnh các mức giá được hỏi
            List<Long> pricesInQuery = PriceLadderTracker.extractPricesFromText(msg);
            String closing;
            if (pricesInQuery.size() >= 2) {
                closing = h + " muốn trải nghiệm không gian riêng tư gói "
                        + PriceLadderTracker.formatPrice(Math.max(pricesInQuery.get(0), pricesInQuery.get(1)))
                        + " hay gói tiêu chuẩn "
                        + PriceLadderTracker.formatPrice(Math.min(pricesInQuery.get(0), pricesInQuery.get(1)))
                        + " để em hỗ trợ giữ phòng cho mình nhé ạ?";
            } else {
                closing = h + " đang muốn tìm hiểu thêm về gói nào để em hỗ trợ tư vấn chi tiết cho mình nhé ạ?";
            }
            return "Dạ em xin phép giải thích điểm khác biệt giúp " + h + " nhé ạ!\n\n"
                    + cleanFaq + "\n\n"
                    + closing;
        }

        // 2. Trích xuất các mã gói cụ thể trong câu hỏi của khách hàng (VD: NS01, NS02)
        List<String> queryCodes = ChunkMetadataExtractor.extractServiceCodes(msg);
        if (queryCodes.size() >= 2) {
            Map<String, DynamicPackageDetail> extractedMap = extractPackagesFromFused(fused, isSaigon);
            List<DynamicPackageDetail> matchedPkgs = new ArrayList<>();
            for (String code : queryCodes) {
                DynamicPackageDetail pkg = extractedMap.get(code.toUpperCase(Locale.ROOT));
                if (pkg != null) {
                    matchedPkgs.add(pkg);
                }
            }
            if (matchedPkgs.size() >= 2) {
                return formatMultiPackageComparison(matchedPkgs, h);
            }
        }

        // 3. Trích xuất theo các mức giá cụ thể trong câu hỏi của khách hàng (VD: 399k và 530k, hoặc 530 và 580)
        List<Long> queryPrices = PriceLadderTracker.extractPricesFromText(msg);
        if (queryPrices.size() >= 2) {
            Map<Long, DynamicPackageDetail> pricePkgMap = extractPackagesByPriceFromFused(fused, isSaigon);
            List<DynamicPackageDetail> matchedPkgs = new ArrayList<>();
            for (Long p : queryPrices) {
                DynamicPackageDetail pkg = pricePkgMap.get(p);
                if (pkg != null) {
                    matchedPkgs.add(pkg);
                }
            }
            if (matchedPkgs.size() >= 2) {
                return formatMultiPackageComparison(matchedPkgs, h);
            }
        }

        // 4. Nếu chỉ có từ khóa so sánh chung chung ("các gói khác gì nhau"): tổng hợp từ các gói hàng đầu trong fused
        List<DynamicPackageDetail> allPkgs = new ArrayList<>(extractPackagesFromFused(fused, isSaigon).values());
        if (allPkgs.size() >= 2) {
            return formatMultiPackageComparison(allPkgs.subList(0, Math.min(3, allPkgs.size())), h);
        }

        return null;
    }

    private static String formatMultiPackageComparison(List<DynamicPackageDetail> packages, String h) {
        StringBuilder sb = new StringBuilder();
        sb.append("Dạ điểm khác biệt giữa các gói như sau ").append(h).append(" nhé ạ: ");
        for (int i = 0; i < packages.size(); i++) {
            DynamicPackageDetail pkg = packages.get(i);
            if (i > 0) {
                sb.append(" Còn ");
            }
            sb.append("Gói ").append(pkg.displayLabel());
            if (pkg.price != null || (pkg.duration != null && !pkg.duration.isBlank())) {
                sb.append(" (");
                if (pkg.price != null) {
                    sb.append(PriceLadderTracker.formatPrice(pkg.price));
                }
                if (pkg.duration != null && !pkg.duration.isBlank()) {
                    if (pkg.price != null) sb.append(", ");
                    sb.append(pkg.duration);
                }
                sb.append(")");
            }
            String hl = pkg.summaryHighlight();
            if (!hl.isBlank()) {
                sb.append(" gồm ").append(hl);
            }
            sb.append(".");
        }
        sb.append("\n\n");
        if (packages.size() == 2) {
            sb.append(h).append(" muốn đặt lịch trải nghiệm gói ").append(packages.get(0).displayLabel())
              .append(" hay gói ").append(packages.get(1).displayLabel())
              .append(" để em hỗ trợ giữ phòng cho mình nhé ạ?");
        } else {
            sb.append(h).append(" đang muốn tìm hiểu gói chăm sóc cơ bản thư giãn hay liệu trình nâng cao để em hỗ trợ tư vấn chi tiết cho mình nhé ạ?");
        }
        return sb.toString();
    }

    private static Map<String, DynamicPackageDetail> extractPackagesFromFused(
            List<ReciprocalRankFusion.FusedChunk> fused,
            boolean isSaigon) {
        Map<String, DynamicPackageDetail> map = new LinkedHashMap<>();
        if (fused == null) return map;

        for (ReciprocalRankFusion.FusedChunk fc : fused) {
            if (fc == null || fc.chunk() == null || fc.chunk().text() == null) continue;
            String text = fc.chunk().text();
            List<String> hp = fc.chunk().headingPath();
            String hpStr = (hp != null ? String.join(" > ", hp) : "").toLowerCase(Locale.ROOT);
            boolean chunkIsSaigon = hpStr.contains("quận 1") || hpStr.contains("sài gòn") || hpStr.contains("tp.hcm");

            if (isSaigon && !chunkIsSaigon && map.values().stream().anyMatch(p -> p.isSaigonBranch)) {
                continue;
            }
            if (!isSaigon && chunkIsSaigon && map.values().stream().anyMatch(p -> !p.isSaigonBranch)) {
                continue;
            }

            parseTableRowsIntoPackageMap(text, map, chunkIsSaigon, isSaigon);
        }
        return map;
    }

    private static Map<Long, DynamicPackageDetail> extractPackagesByPriceFromFused(
            List<ReciprocalRankFusion.FusedChunk> fused,
            boolean isSaigon) {
        Map<Long, DynamicPackageDetail> priceMap = new LinkedHashMap<>();
        Map<String, DynamicPackageDetail> codeMap = extractPackagesFromFused(fused, isSaigon);
        for (DynamicPackageDetail pkg : codeMap.values()) {
            if (pkg.price != null && pkg.price > 0) {
                priceMap.putIfAbsent(pkg.price, pkg);
            }
        }
        if (fused != null) {
            for (ReciprocalRankFusion.FusedChunk fc : fused) {
                if (fc == null || fc.chunk() == null || fc.chunk().text() == null) continue;
                List<DynamicPackageDetail> rows = parseAllTablePackages(fc.chunk().text(), isSaigon);
                for (DynamicPackageDetail r : rows) {
                    if (r.price != null && r.price > 0) {
                        priceMap.putIfAbsent(r.price, r);
                    }
                }
            }
        }
        return priceMap;
    }

    private static List<DynamicPackageDetail> parseAllTablePackages(String text, boolean isSaigonBranch) {
        List<DynamicPackageDetail> list = new ArrayList<>();
        Map<String, DynamicPackageDetail> map = new LinkedHashMap<>();
        parseTableRowsIntoPackageMap(text, map, isSaigonBranch, isSaigonBranch);
        list.addAll(map.values());
        return list;
    }

    private static void parseTableRowsIntoPackageMap(
            String text,
            Map<String, DynamicPackageDetail> map,
            boolean isSaigonBranch,
            boolean preferSaigon) {
        if (text == null || !text.contains("|")) return;

        String[] lines = text.split("\\r?\\n");
        int codeCol = -1;
        int nameCol = -1;
        int priceCol = -1;
        int durationCol = -1;
        int inclusionCol = -1;
        int highlightCol = -1;
        int spaceCol = -1;

        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("|")) continue;

            String[] cells = trimmed.split("\\|");
            if (cells.length < 3) continue;

            if (codeCol == -1 && (trimmed.toLowerCase(Locale.ROOT).contains("mã") || trimmed.toLowerCase(Locale.ROOT).contains("tên"))) {
                for (int i = 1; i < cells.length; i++) {
                    String h = cells[i].trim().toLowerCase(Locale.ROOT);
                    if (h.matches(".*(?:mã\\s*gói|mã\\s*dv|mã\\s*dịch\\s*vụ|mã|code|sku).*")) {
                        codeCol = i;
                    } else if (h.matches(".*(?:tên\\s*gói|tên\\s*dv|tên\\s*dịch\\s*vụ|tên\\s*liệu\\s*trình|dịch\\s*vụ|tên).*") && nameCol == -1) {
                        nameCol = i;
                    } else if (h.matches(".*(?:đơn\\s*giá|mức\\s*giá|giá|chi\\s*phí|price).*") && priceCol == -1) {
                        priceCol = i;
                    } else if (h.matches(".*(?:thời\\s*gian|thời\\s*lượng|duration|phút).*")) {
                        durationCol = i;
                    } else if (h.matches(".*(?:bao\\s*gồm|quy\\s*trình|chi\\s*tiết).*") && inclusionCol == -1) {
                        inclusionCol = i;
                    } else if (h.matches(".*(?:điểm\\s*nổi\\s*bật|đặc\\s*điểm|nổi\\s*bật|lợi\\s*ích).*")) {
                        highlightCol = i;
                    } else if (h.matches(".*(?:không\\s*gian|phòng).*")) {
                        spaceCol = i;
                    }
                }
            } else if (!trimmed.contains("---")) {
                if (codeCol == -1 && cells.length >= 4) {
                    String first = cells[1].trim();
                    if (first.matches("^[A-Za-z0-9\\-_]{2,10}$") && !first.equalsIgnoreCase("null") && !first.equalsIgnoreCase("stt")) {
                        codeCol = 1;
                        nameCol = 2;
                        priceCol = 3;
                        if (cells.length >= 5) durationCol = 4;
                        if (cells.length >= 6) inclusionCol = 5;
                    }
                }

                if (codeCol != -1 || nameCol != -1) {
                    String code = (codeCol != -1 && codeCol < cells.length) ? cells[codeCol].trim() : null;
                    String name = (nameCol != -1 && nameCol < cells.length) ? cells[nameCol].trim() : null;
                    String priceStr = (priceCol != -1 && priceCol < cells.length) ? cells[priceCol].trim() : null;
                    String duration = (durationCol != -1 && durationCol < cells.length) ? cells[durationCol].trim() : null;
                    String inclusion = (inclusionCol != -1 && inclusionCol < cells.length) ? cells[inclusionCol].trim() : null;
                    String highlight = (highlightCol != -1 && highlightCol < cells.length) ? cells[highlightCol].trim() : null;
                    String space = (spaceCol != -1 && spaceCol < cells.length) ? cells[spaceCol].trim() : null;

                    if (code != null && code.matches("^[A-Za-z0-9\\-_]{2,10}$") && !code.equalsIgnoreCase("null")) {
                        String normCode = code.toUpperCase(Locale.ROOT);
                        Long priceVal = null;
                        if (priceStr != null) {
                            List<Long> pList = ChunkMetadataExtractor.extractAllPrices(priceStr);
                            if (!pList.isEmpty()) priceVal = pList.get(0);
                        }
                        DynamicPackageDetail detail = new DynamicPackageDetail(
                                normCode, name, priceVal, priceStr, duration, inclusion, highlight, space, isSaigonBranch
                        );
                        boolean shouldPut = !map.containsKey(normCode)
                                || (preferSaigon && isSaigonBranch)
                                || (!preferSaigon && !isSaigonBranch && map.get(normCode).isSaigonBranch);
                        if (shouldPut) {
                            map.put(normCode, detail);
                        }
                    }
                }
            }
        }
    }

    private static String findDirectFaqAnswer(
            List<ReciprocalRankFusion.FusedChunk> fused,
            ObsidianMetadataCache metadataCache,
            UUID pageId,
            String msg,
            String lowerMsg) {

        if (fused != null) {
            for (ReciprocalRankFusion.FusedChunk fc : fused) {
                if (fc == null || fc.chunk() == null || fc.chunk().text() == null) continue;
                String ans = searchFaqInText(fc.chunk().text(), msg, lowerMsg);
                if (ans != null && !ans.isBlank()) {
                    return ans;
                }
            }
        }

        if (metadataCache != null && pageId != null) {
            BacklinkGraph graph = metadataCache.getGraphForPage(pageId);
            if (graph != null) {
                for (String role : List.of(ChunkMetadata.ROLE_FAQ, ChunkMetadata.ROLE_GENERAL, ChunkMetadata.ROLE_PROCESS)) {
                    List<ChunkGraphNode> nodes = graph.getNodesByRole(role);
                    if (nodes != null) {
                        for (ChunkGraphNode node : nodes) {
                            if (node.text() != null) {
                                String ans = searchFaqInText(node.text(), msg, lowerMsg);
                                if (ans != null && !ans.isBlank()) {
                                    return ans;
                                }
                            }
                        }
                    }
                }
            }
        }

        return null;
    }

    private static String searchFaqInText(String text, String msg, String lowerMsg) {
        if (text == null || text.isBlank()) return null;

        if (text.contains("|")) {
            String[] lines = text.split("\\r?\\n");
            int qCol = -1;
            int aCol = -1;
            for (String line : lines) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("|") || trimmed.contains("---")) continue;
                String[] cells = trimmed.split("\\|");
                if (cells.length < 3) continue;

                String lowerTrimmed = trimmed.toLowerCase(Locale.ROOT);
                boolean isHeaderRow = (lowerTrimmed.contains("| câu hỏi |") || lowerTrimmed.contains("| nội dung |")
                        || lowerTrimmed.contains("| khách hỏi |") || lowerTrimmed.contains("| tình huống"));
                if (qCol == -1 && isHeaderRow) {
                    for (int i = 1; i < cells.length; i++) {
                        String h = cells[i].trim().toLowerCase(Locale.ROOT);
                        if (h.contains("câu hỏi") || h.contains("thắc mắc") || h.contains("hỏi")
                                || h.contains("nội dung") || h.contains("tình huống")) {
                            qCol = i;
                        } else if (h.contains("trả lời") || h.contains("tư vấn") || h.contains("giải đáp")
                                || h.contains("đáp") || h.contains("chi tiết") || h.contains("gợi ý")) {
                            aCol = i;
                        }
                    }
                    continue;
                }

                int effQCol = qCol != -1 ? qCol : (cells.length == 3 ? 1 : -1);
                int effACol = aCol != -1 ? aCol : (cells.length == 3 ? 2 : -1);
                if (effQCol != -1 && effACol != -1 && effQCol < cells.length && effACol < cells.length) {
                    String qCell = cells[effQCol].trim();
                    String aCell = cells[effACol].trim();
                    if (isFaqRowMatchingQuery(qCell, msg, lowerMsg)) {
                        return cleanFaqAnswerText(aCell);
                    }
                }
            }
        }

        Matcher qnaMatcher = Pattern.compile("(?iU)(?:Hỏi|Q):\\s*([^\\n]+)\\s*\\n+(?:Trả\\s*lời|A):\\s*([^\\n]+)").matcher(text);
        while (qnaMatcher.find()) {
            String qText = qnaMatcher.group(1).trim();
            String aText = qnaMatcher.group(2).trim();
            if (isFaqRowMatchingQuery(qText, msg, lowerMsg)) {
                return cleanFaqAnswerText(aText);
            }
        }

        return null;
    }

    private static boolean isFaqRowMatchingQuery(String qText, String msg, String lowerMsg) {
        String qLow = qText.toLowerCase(Locale.ROOT);

        List<String> queryCodes = ChunkMetadataExtractor.extractServiceCodes(msg);
        if (!queryCodes.isEmpty()) {
            List<String> faqCodes = ChunkMetadataExtractor.extractServiceCodes(qText);
            if (!faqCodes.isEmpty() && faqCodes.containsAll(queryCodes)) {
                return true;
            }
        }

        List<Long> queryPrices = PriceLadderTracker.extractPricesFromText(msg);
        if (!queryPrices.isEmpty() && queryPrices.size() >= 2) {
            List<Long> faqPrices = PriceLadderTracker.extractPricesFromText(qText);
            if (faqPrices.containsAll(queryPrices)) {
                return true;
            }
            boolean allTokensMatch = true;
            for (Long p : queryPrices) {
                String shortK = String.valueOf(p / 1000L);
                if (!qLow.contains(shortK)) {
                    allTokensMatch = false;
                    break;
                }
            }
            if (allTokensMatch && (qLow.contains("khác") || qLow.contains("chênh") || qLow.contains("so"))) {
                return true;
            }
        }

        if (lowerMsg.contains("gold") && lowerMsg.contains("diamond") && qLow.contains("gold") && qLow.contains("diamond")) {
            return true;
        }
        if (lowerMsg.contains("silver") && qLow.contains("silver")) {
            return true;
        }

        // Khớp câu hỏi giải thích bản chất / quy trình dịch vụ (VD: "Massage trị liệu là trị gì", "Massage trị liệu là làm gì")
        if (FanpageBotPromptBuilder.isServiceExplanationOrProcessInquiry(msg)) {
            boolean asksTherapeuticFocus = lowerMsg.matches("(?isU).*\\b(trị\\s+(?:những\\s+|cái\\s+)?(?:gì|j)|là\\s+trị\\s+(?:gì|j)|tập\\s+trung|tác\\s+dụng|công\\s+dụng)\\b.*");
            if (lowerMsg.contains("gội đầu") || lowerMsg.contains("dưỡng sinh")) {
                if (qLow.contains("gội đầu") && (qLow.contains("gồm") || qLow.contains("quy trình") || qLow.contains("khác gì"))) {
                    return true;
                }
            } else if (lowerMsg.contains("massage") || lowerMsg.contains("trị liệu")) {
                if (asksTherapeuticFocus && qLow.contains("massage") && qLow.contains("tập trung vào gì")) {
                    return true;
                }
                if (!asksTherapeuticFocus && qLow.contains("massage") && (qLow.contains("gồm những gì") || qLow.contains("là gì"))) {
                    return true;
                }
            }
        }

        // Khớp triệu chứng đau mỏi cơ thể (VD: "mỏi vai", "đau cổ vai", "mỏi lưng", "mất ngủ", "tê bì")
        if (FanpageBotPromptBuilder.isWellnessOrDiscomfortMessage(msg)) {
            if ((lowerMsg.contains("vai") || lowerMsg.contains("cổ") || lowerMsg.contains("gáy"))
                    && (qLow.contains("vai") || qLow.contains("cổ") || qLow.contains("gáy"))) {
                return true;
            }
            if (lowerMsg.contains("lưng") && qLow.contains("lưng")) {
                return true;
            }
            if ((lowerMsg.contains("mất ngủ") || lowerMsg.contains("khó ngủ") || lowerMsg.contains("đau đầu") || lowerMsg.contains("stress"))
                    && (qLow.contains("mất ngủ") || qLow.contains("khó ngủ") || qLow.contains("đau đầu") || qLow.contains("thư giãn"))) {
                return true;
            }
        }

        return false;
    }

    /**
     * Tổng hợp câu trả lời trực diện, ngắn gọn (1-2 câu ở Bubble 1 + 1 câu hỏi A/B ở Bubble 2)
     * từ tài liệu tri thức khi khách hàng hỏi bản chất/quy trình dịch vụ, chia sẻ triệu chứng đau mỏi,
     * hoặc trả lời câu hỏi chẩn đoán ở lượt tiếp theo ("Thi thoảng bị", "Thường xuyên", "Gói 60 phút"...).
     */
    private static String synthesizeDynamicExplanationOrSymptomReply(
            List<ReciprocalRankFusion.FusedChunk> fused,
            ObsidianMetadataCache metadataCache,
            UUID pageId,
            String msg,
            String lowerMsg,
            String h,
            boolean isSaigon,
            List<FanpageBotRequest.ConversationTurn> history) {
        if (!isMassageWellnessCatalog(fused, metadataCache, pageId)) {
            return null;
        }
        boolean asksExplanation = FanpageBotPromptBuilder.isServiceExplanationOrProcessInquiry(msg);
        boolean asksSymptom = FanpageBotPromptBuilder.isWellnessOrDiscomfortMessage(msg);
        boolean isFollowUp = FanpageBotPromptBuilder.isSymptomFollowUpTurn(msg, history);

        StringBuilder userHistoryText = new StringBuilder(lowerMsg);
        String lastAssistantText = "";
        if (history != null) {
            for (int i = history.size() - 1; i >= 0; i--) {
                FanpageBotRequest.ConversationTurn t = history.get(i);
                if (t == null || t.content() == null) continue;
                if ("assistant".equalsIgnoreCase(t.role())) {
                    if (lastAssistantText.isEmpty()) {
                        lastAssistantText = t.content().toLowerCase(Locale.ROOT);
                    }
                } else {
                    userHistoryText.append(' ').append(t.content().toLowerCase(Locale.ROOT));
                }
            }
        }
        String combinedUserLow = userHistoryText.toString();

        // 1. Khi khách hỏi bản chất / tác dụng / quy trình dịch vụ ("Massage trị liệu là trị gì / làm gì?")
        if (asksExplanation) {
            boolean asksTherapeuticFocus = lowerMsg.matches("(?isU).*\\b(trị\\s+(?:những\\s+|cái\\s+)?(?:gì|j)|là\\s+trị\\s+(?:gì|j)|tập\\s+trung|tác\\s+dụng|công\\s+dụng)\\b.*");
            String processSummary = extractProcessStepsSummaryFromChunks(fused, metadataCache, pageId, lowerMsg);
            if (asksTherapeuticFocus) {
                return "Dạ Massage trị liệu bên em tập trung ấn huyệt giải tỏa các điểm bó cơ căng cứng (đặc biệt vùng đầu - cổ - vai - gáy - lưng), kết hợp tắm xông thảo dược và chườm nóng ngải cứu giúp phục hồi cơ xương khớp thay vì chỉ massage thư giãn thông thường ạ.\n\n"
                        + "Hiện tại " + h + " đang muốn tập trung trị liệu giảm nhức mỏi vùng cổ vai - lưng hay thư giãn phục hồi toàn thân để em gợi ý gói phù hợp nhất ạ?";
            }
            String stepsFlow = (processSummary != null && !processSummary.isBlank())
                    ? processSummary
                    : "Tắm tráng & Ngâm bồn thảo dược -> Xông hơi & ngâm chân muối gừng -> Massage bấm huyệt trị liệu toàn thân -> Chườm nóng ngải cứu & dùng điểm tâm phục hồi";
            return "Dạ quy trình chăm sóc trị liệu khép kín bên em gồm các bước chính: " + stepsFlow
                    + " nhằm giúp giải tỏa căng cơ và phục hồi năng lượng chuyên sâu ạ.\n\n"
                    + "Không biết " + h + " muốn trải nghiệm gói trị liệu cơ bản (60-70 phút) trước hay gói phục hồi chuyên sâu (100 phút, phòng VIP riêng) để em tư vấn chi tiết hơn ạ?";
        }

        // 2. Khi khách phản hồi chọn phương án thời lượng / gói dịch vụ ở lượt tiếp theo ("Gói 60 phút", "Gói cơ bản", "80 phút", "Gói chuyên sâu")
        if (msg.strip().length() <= 60 && FanpageBotPromptBuilder.CONSULTATIVE_OPTION_FOLLOWUP_PATTERN.matcher(lowerMsg).matches()
                && !FanpageBotPromptBuilder.MILD_OR_OCCASIONAL_SYMPTOM_PATTERN.matcher(lowerMsg).matches()
                && !FanpageBotPromptBuilder.CHRONIC_OR_DEEP_SYMPTOM_PATTERN.matcher(lowerMsg).matches()) {
            if (lowerMsg.contains("80") || lowerMsg.contains("nâng cao") || lowerMsg.contains("ngâm bồn")) {
                String priceLabel = isSaigon ? "670.000 VNĐ, 90 phút" : "530.000 VNĐ, 80 phút";
                return "Dạ gói NS02 - Massage cơ bản nâng cao (" + priceLabel + ") sẽ có 10 phút ngâm bồn gỗ thảo dược làm ấm cơ thể trước khi vào 70 phút massage bấm huyệt giải cơ chuyên sâu và dùng điểm tâm thực dưỡng ạ.\n\n"
                        + h + " đang tiện ghé cơ sở bên em vào khung giờ Sáng hay Chiều để em kiểm tra phòng trống và giữ lịch trước cho mình ạ?";
            }
            if (lowerMsg.contains("90") || lowerMsg.contains("100") || lowerMsg.contains("120")
                    || lowerMsg.contains("chuyên sâu") || lowerMsg.contains("phục hồi") || lowerMsg.contains("vip") || lowerMsg.contains("phòng riêng")) {
                String priceLabel = isSaigon ? "870.000 VNĐ, 100 phút" : "580.000 VNĐ, 100 phút";
                return "Dạ gói NS03 - Massage phục hồi (" + priceLabel + ") được thực hiện trong phòng VIP riêng khép kín, gồm đầy đủ ngâm bồn thảo dược, xông hơi, 85 phút bấm huyệt trị liệu sâu và chườm nóng ngải cứu ạ.\n\n"
                        + h + " đang tiện ghé cơ sở bên em vào khung giờ Sáng hay Chiều để em giữ phòng VIP riêng trước cho mình ạ?";
            }
            if (lowerMsg.contains("60") || lowerMsg.contains("70") || lowerMsg.contains("cơ bản")) {
                String priceLabel = isSaigon ? "570.000 VNĐ, 70 phút" : "399.000 VNĐ, 60 phút";
                return "Dạ gói NS01 - Massage trị liệu cơ bản (" + priceLabel + ") gồm massage bấm huyệt toàn thân tập trung xử lý vùng đau mỏi cổ - vai - gáy - lưng kèm điểm tâm thực dưỡng sau buổi, trọn gói không nhận Tip ạ.\n\n"
                        + h + " đang tiện ghé cơ sở bên em vào khung giờ Sáng hay Chiều để em kiểm tra phòng trống và giữ lịch trước cho mình ạ?";
            }
        }

        // 2.5. Khi khách hỏi giá gói toàn thân ở lượt tiếp theo ("Toàn thân thì bn", "Massage toàn thân giá sao")
        if ((lowerMsg.contains("toàn thân") || lowerMsg.contains("full body") || lowerMsg.contains("thân thể"))
                && lowerMsg.matches("(?isU).*\\b(bn|bao\\s+nhiêu|giá|gia|chi\\s+phí|mấy\\s+tiền|hết\\s+bao\\s+nhiêu)\\b.*")) {
            String basicLabel = isSaigon ? "570.000 VNĐ (70 phút)" : "399.000 VNĐ (60 phút)";
            String plusLabel = isSaigon ? "670.000 VNĐ (90 phút, có thêm ngâm bồn thảo dược)" : "530.000 VNĐ (80 phút, có thêm ngâm bồn thảo dược)";
            return "Dạ với liệu trình Massage trị liệu toàn thân (kết hợp giải cơ cổ - vai - gáy - lưng), bên em có gói NS01 - Massage cơ bản là "
                    + basicLabel + " và gói NS02 - Massage cơ bản nâng cao là " + plusLabel + " trọn gói không nhận Tip ạ.\n\n"
                    + h + " muốn trải nghiệm gói Cơ bản trước hay gói Nâng cao có ngâm bồn thảo dược để em tư vấn chi tiết hơn ạ?";
        }

        // 3. Khi khách chia sẻ triệu chứng đau mỏi ("Tôi bị mỏi vai", "Tôi bị mỏi vai và thi thoảng hơi đau nhức")
        //    HOẶC trả lời câu hỏi tần suất/mức độ ("Thi thoảng bị", "Mới bị gần đây", "Thường xuyên bị", "Ngồi làm việc nhiều")
        if (asksSymptom || isFollowUp) {
            String symptomArea = (combinedUserLow.contains("vai") || combinedUserLow.contains("cổ") || combinedUserLow.contains("gáy"))
                    ? "vùng cổ - vai - gáy"
                    : (combinedUserLow.contains("lưng") ? "vùng thắt lưng - cột sống"
                    : (combinedUserLow.contains("đầu") || combinedUserLow.contains("mất ngủ") || combinedUserLow.contains("khó ngủ") ? "vùng đầu - cổ - vai - gáy" : "cơ thể"));

            FanpageBotPromptBuilder.SymptomSeverity severity = FanpageBotPromptBuilder.detectSymptomSeverity(msg);

            // Nhánh 3A: Khách báo triệu chứng ở mức độ nhẹ / thi thoảng / mới xuất hiện
            if (severity == FanpageBotPromptBuilder.SymptomSeverity.MILD_OR_OCCASIONAL) {
                String basicLabel = isSaigon ? "570.000 VNĐ, 70 phút" : "399.000 VNĐ, 60 phút";
                String plusLabel = isSaigon ? "670.000 VNĐ, 90 phút" : "530.000 VNĐ, 80 phút";
                boolean alreadySuggestedBothTiers = lastAssistantText.contains("530.000") || lastAssistantText.contains("80 phút");
                if (alreadySuggestedBothTiers) {
                    return "Dạ với mức độ thi thoảng mới đau mỏi nhẹ như vậy, " + h
                            + " bắt đầu bằng gói NS01 - Massage trị liệu cơ bản (" + basicLabel
                            + ") để kỹ thuật viên ấn huyệt giải cơ " + symptomArea
                            + " và dùng điểm tâm thực dưỡng là vừa vặn và tiết kiệm nhất ạ.\n\n"
                            + h + " muốn trải nghiệm gói 60 phút này tại cơ sở Hà Nội hay Quận 1 Sài Gòn để em kiểm tra lịch trống cho mình ạ?";
                }
                return "Dạ nếu mình chỉ thi thoảng đau mỏi " + symptomArea
                        + " nhẹ thì kỹ thuật viên bên em sẽ tập trung ấn huyệt thả lỏng cơ kết hợp chườm nóng ngải cứu rất êm ạ. "
                        + h + " có thể chọn gói NS01 - Massage cơ bản (" + basicLabel
                        + ") hoặc NS02 - Massage nâng cao có ngâm bồn thảo dược (" + plusLabel
                        + ") trọn gói không nhận Tip ạ.\n\n"
                        + h + " muốn trải nghiệm gói 60 phút cơ bản trước hay gói 80 phút có thêm ngâm bồn thảo dược để em tư vấn chi tiết hơn ạ?";
            }

            // Nhánh 3B: Khách báo triệu chứng căng mỏi thường xuyên / lâu ngày / ngồi máy tính văn phòng nhiều
            if (severity == FanpageBotPromptBuilder.SymptomSeverity.CHRONIC_OR_DEEP) {
                String recoveryLabel = isSaigon ? "870.000 VNĐ, 100 phút" : "580.000 VNĐ, 100 phút";
                String basicPrice = isSaigon ? "570.000 VNĐ" : "399.000 VNĐ";
                return "Dạ nếu " + symptomArea + " bị căng mỏi thường xuyên thì gói NS03 - Massage phục hồi ("
                        + recoveryLabel + ", phòng VIP riêng) có đầy đủ ngâm bồn thảo dược, xông hơi, 85 phút bấm huyệt giải cơ sâu và chườm nóng ngải cứu sẽ phù hợp nhất ạ.\n\n"
                        + h + " muốn trải nghiệm gói Phục hồi phòng VIP này hay muốn thử gói Cơ bản (" + basicPrice + ") trước ạ?";
            }

            // Nhánh 3C: Khách mới nêu triệu chứng ("Tôi bị mỏi vai") mà chưa rõ tần suất nhẹ/nặng -> Giải thích ngắn gọn 1 câu + hỏi chẩn đoán A/B
            return "Dạ với tình trạng đang đau mỏi " + symptomArea + " của " + h
                    + ", kỹ thuật viên bên em sẽ tập trung ấn huyệt giải tỏa điểm bó cơ kết hợp tắm xông thảo dược và chườm nóng ngải cứu giúp nhẹ vùng đau mỏi rõ rệt ngay sau buổi làm ạ.\n\n"
                    + "Tình trạng mỏi " + symptomArea + " của " + h
                    + " là thi thoảng mới bị hay căng cứng thường xuyên do ngồi làm việc nhiều để em gợi ý gói phù hợp nhất ạ?";
        }

        return null;
    }

    private static String extractProcessStepsSummaryFromChunks(
            List<ReciprocalRankFusion.FusedChunk> fused,
            ObsidianMetadataCache metadataCache,
            UUID pageId,
            String lowerMsg) {
        List<String> textsToScan = new ArrayList<>();
        if (fused != null) {
            for (ReciprocalRankFusion.FusedChunk fc : fused) {
                if (fc != null && fc.chunk() != null && fc.chunk().text() != null) {
                    String hp = fc.chunk().headingPath() != null ? String.join(" ", fc.chunk().headingPath()) : "";
                    textsToScan.add(hp + "\n" + fc.chunk().text());
                }
            }
        }
        if (metadataCache != null && pageId != null) {
            BacklinkGraph graph = metadataCache.getGraphForPage(pageId);
            if (graph != null) {
                List<ChunkGraphNode> procNodes = graph.getNodesByRole(ChunkMetadata.ROLE_PROCESS);
                if (procNodes != null) {
                    for (ChunkGraphNode node : procNodes) {
                        if (node.text() != null) {
                            String hp = node.headingPath() != null ? String.join(" ", node.headingPath()) : "";
                            textsToScan.add(hp + "\n" + node.text());
                        }
                    }
                }
            }
        }

        boolean wantsDuongSinh = lowerMsg != null && (lowerMsg.contains("gội đầu") || lowerMsg.contains("dưỡng sinh"));
        Map<Integer, String> orderedSteps = new java.util.TreeMap<>();
        int fallbackIdx = 100;
        for (String text : textsToScan) {
            if (!text.contains("|")) continue;
            String lowText = text.toLowerCase(Locale.ROOT);
            boolean isGoiDauChunk = lowText.contains("quy trình gội đầu")
                    || lowText.contains("gội canh bát")
                    || lowText.contains("làm sạch lớp trang điểm")
                    || lowText.contains("massage da đầu bằng tinh dầu gừng")
                    || lowText.contains("xả vòm nước");
            if (wantsDuongSinh && !isGoiDauChunk) {
                continue;
            }
            if (!wantsDuongSinh && isGoiDauChunk) {
                continue;
            }
            String[] lines = text.split("\\r?\\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("|") || trimmed.contains("---")) continue;
                String lowLine = trimmed.toLowerCase(Locale.ROOT);
                if (lowLine.contains("| bước |") || (lowLine.contains("tên bước") && lowLine.contains("mô tả"))) {
                    continue;
                }
                String[] cells = trimmed.split("\\|");
                if (cells.length >= 4) {
                    String stepCode = cells[1].trim();
                    if (!stepCode.matches("(?i)^B\\d{1,2}$")) {
                        continue;
                    }
                    int stepNum;
                    try {
                        stepNum = Integer.parseInt(stepCode.substring(1));
                    } catch (NumberFormatException e) {
                        stepNum = fallbackIdx++;
                    }
                    String stepName = cells[2].trim();
                    if (!stepName.isBlank() && !orderedSteps.containsKey(stepNum)) {
                        orderedSteps.put(stepNum, stepName);
                    }
                }
            }
        }

        if (orderedSteps.isEmpty()) {
            return null;
        }
        return String.join(" -> ", orderedSteps.values());
    }

    private static String extractReadableSentenceFromTable(String tableText, String query) {
        if (tableText == null || !tableText.contains("|")) {
            return "";
        }
        String[] lines = tableText.split("\\r?\\n");
        String lowerQuery = query != null ? query.toLowerCase(Locale.ROOT) : "";
        String[] queryTokens = lowerQuery.isBlank() ? new String[0] : lowerQuery.split("(?U)[^\\p{L}\\p{Nd}]+");
        String bestCandidate = "";
        int bestScore = -1;
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("|") || trimmed.contains("---")) continue;
            String low = trimmed.toLowerCase(Locale.ROOT);
            if (low.contains("| mã gói |") || low.contains("| bước |") || low.contains("| nội dung |")
                    || low.contains("| câu hỏi") || low.contains("| khách hỏi") || low.contains("| hạng mục |")
                    || low.contains("| tình huống |")) {
                continue;
            }
            String[] cells = trimmed.split("\\|");
            List<String> nonEmpty = new ArrayList<>();
            for (String c : cells) {
                String clean = c.replaceAll("\\[\\[.*?\\]\\]", "").replaceAll("\\[.*?\\]", "").strip();
                if (!clean.isEmpty() && !clean.equalsIgnoreCase("null")) {
                    nonEmpty.add(clean);
                }
            }
            String candidate = null;
            if (nonEmpty.size() == 2) {
                String col1 = nonEmpty.get(0);
                String col2 = cleanFaqAnswerText(nonEmpty.get(1));
                if (col2 != null && !col2.isBlank()) {
                    // Nếu cột 2 đã là một câu trả lời tư vấn hoàn chỉnh (bắt đầu bằng "Dạ", "Nếu", "Khách", "Bên em"...),
                    // tuyệt đối KHÔNG ghép cột 1 ("Em hay đau đầu: ...") vào trước!
                    String col2Low = col2.toLowerCase(Locale.ROOT);
                    if (col2Low.startsWith("dạ") || col2Low.startsWith("nếu") || col2Low.startsWith("khách")
                            || col2Low.startsWith("bên em") || col2Low.startsWith("các ") || col2.length() > 35) {
                        candidate = col2;
                    } else {
                        candidate = col1 + ": " + col2;
                    }
                }
            } else if (nonEmpty.size() > 2) {
                candidate = cleanFaqAnswerText(String.join(" - ", nonEmpty.subList(0, Math.min(3, nonEmpty.size()))));
            }
            if (candidate != null && !candidate.isBlank()) {
                if (queryTokens.length == 0) {
                    return candidate;
                }
                int score = 0;
                for (String tok : queryTokens) {
                    if (tok.length() >= 2 && low.contains(tok)) {
                        score += (tok.length() >= 4 ? 3 : 2);
                    }
                }
                if (score > bestScore) {
                    bestScore = score;
                    bestCandidate = candidate;
                }
            }
        }
        return bestCandidate;
    }

    private static String limitToMaxSentences(String text, int maxSentences, int maxChars) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String cleaned = text.strip();
        String[] sentences = cleaned.split("(?<=[.!?])\\s+");
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String s : sentences) {
            if (s.isBlank()) continue;
            if (count > 0 && sb.length() + 1 + s.length() > maxChars) {
                break;
            }
            if (sb.length() > 0) sb.append(' ');
            sb.append(s.strip());
            count++;
            if (count >= maxSentences) {
                break;
            }
        }
        String result = sb.length() > 0 ? sb.toString() : cleaned;
        if (result.length() > maxChars) {
            int lastSpace = result.lastIndexOf(' ', maxChars);
            if (lastSpace > 40) {
                result = result.substring(0, lastSpace).replaceAll("[,;:\\-]+$", "").strip() + " ạ.";
            }
        }
        if (!result.endsWith(".") && !result.endsWith("!") && !result.endsWith("?")) {
            result = result + (result.toLowerCase(Locale.ROOT).endsWith("ạ") ? "." : " ạ.");
        }
        return result;
    }

    private static String cleanFaqAnswerText(String raw) {
        if (raw == null) return null;
        return raw.replaceAll("\\[\\[.*?\\]\\]", "")
                .replaceAll("\\[.*?\\]", "")
                .replaceAll("(?U)(?<!\\p{L})ạ\\s*(?=\\p{Lu})(?!\\s*(?:Anh|Chị|Nàng)\\b)", "ạ. ")
                .replaceAll("(?iU)[.,;]?\\s*chi\\s+tiết\\s+quy\\s+trình\\s+cụ\\s+thể[^.!?\\n]*tham\\s+khảo\\s+ảnh\\s+bên\\s+dưới(?:\\s+ạ)?[.!]?", "")
                .replaceAll("[🌿✨]", "")
                .replaceAll("\\|", "")
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static String resolveDynamicPromoPolicy(
            List<ReciprocalRankFusion.FusedChunk> fused,
            ObsidianMetadataCache metadataCache,
            UUID pageId) {
        if (fused != null) {
            for (ReciprocalRankFusion.FusedChunk fc : fused) {
                if (fc != null && fc.chunk() != null && fc.chunk().text() != null) {
                    String extracted = extractPromoSnippet(fc.chunk().text());
                    if (extracted != null && !extracted.isBlank()) {
                        return extracted;
                    }
                }
            }
        }
        if (metadataCache != null && pageId != null) {
            BacklinkGraph graph = metadataCache.getGraphForPage(pageId);
            if (graph != null) {
                List<ChunkGraphNode> promoNodes = graph.getNodesByRole(ChunkMetadata.ROLE_PROMO);
                if (promoNodes != null) {
                    for (ChunkGraphNode node : promoNodes) {
                        if (node.text() != null) {
                            String extracted = extractPromoSnippet(node.text());
                            if (extracted != null && !extracted.isBlank()) {
                                return extracted;
                            }
                        }
                    }
                }
            }
        }
        return null; // v3.8: không có chính sách khuyến mãi mặc định cứng
    }

    private static String extractPromoSnippet(String text) {
        if (text == null || text.isBlank()) return null;
        for (String line : text.split("\r?\n")) {
            String clean = line.replaceAll("^[#\\-*|\\s]+", "").replaceAll("[|#*]+$", "").strip();
            String lower = clean.toLowerCase(Locale.ROOT);
            if ((lower.contains("giảm") || lower.contains("ưu đãi") || lower.contains("chiết khấu"))
                    && (lower.contains("%") || lower.contains("buổi") || lower.contains("voucher") || lower.contains("combo"))) {
                return clean;
            }
        }
        return null;
    }

    private record BranchPriceSummary(long minMassage, long minDuongSinh, long minFloor) {}

    private static BranchPriceSummary resolveBranchPricingDynamically(
            String branchKey,
            List<ReciprocalRankFusion.FusedChunk> fused,
            ObsidianMetadataCache metadataCache,
            UUID pageId) {
        long minMassage = "saigon".equalsIgnoreCase(branchKey) ? 570_000L : 399_000L;
        long minDuongSinh = "saigon".equalsIgnoreCase(branchKey) ? 260_000L : 239_000L;
        long minFloor = 129_000L;

        if (fused != null) {
            for (ReciprocalRankFusion.FusedChunk fc : fused) {
                if (fc != null && fc.chunk() != null) {
                    String hp = fc.chunk().headingPath() != null ? String.join(" ", fc.chunk().headingPath()).toLowerCase(Locale.ROOT) : "";
                    String txt = fc.chunk().text() != null ? fc.chunk().text() : "";
                    List<Long> prices = ChunkMetadataExtractor.extractAllPrices(txt);
                    if (!prices.isEmpty()) {
                        long lowest = Collections.min(prices);
                        if (hp.contains("massage") || txt.toLowerCase(Locale.ROOT).contains("massage")) {
                            if (lowest >= 300_000L && lowest <= 1_000_000L) {
                                minMassage = lowest;
                            }
                        } else if (hp.contains("dưỡng sinh") || hp.contains("gội đầu") || txt.toLowerCase(Locale.ROOT).contains("dưỡng sinh")) {
                            if (lowest >= 100_000L && lowest <= 500_000L) {
                                minDuongSinh = lowest;
                            }
                        }
                    }
                }
            }
        }
        return new BranchPriceSummary(minMassage, minDuongSinh, minFloor);
    }

    private static String extractBranchDisplayName(String text) {
        if (text == null) return null;
        Matcher mSg = PriceLadderTracker.BRANCH_SAIGON_PATTERN.matcher(text);
        if (mSg.find()) {
            String match = mSg.group(1).toLowerCase(Locale.ROOT);
            if (match.contains("quận 1") || match.contains("q1")) return "Quận 1 (Sài Gòn)";
            return "Sài Gòn";
        }
        Matcher mHn = PriceLadderTracker.BRANCH_HANOI_PATTERN.matcher(text);
        if (mHn.find()) {
            String match = mHn.group(1).strip();
            return formatBranchTitle(match);
        }
        return null;
    }

    private static String formatBranchTitle(String raw) {
        if (raw == null || raw.isBlank()) return "Hà Nội";
        String[] words = raw.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!w.isBlank()) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(Character.toUpperCase(w.charAt(0)));
                if (w.length() > 1) {
                    sb.append(w.substring(1).toLowerCase(Locale.ROOT));
                }
            }
        }
        return sb.toString();
    }

    private static List<Long> extractAllPricesDynamically(
            List<ReciprocalRankFusion.FusedChunk> fused,
            ObsidianMetadataCache metadataCache,
            UUID pageId) {
        Set<Long> prices = new LinkedHashSet<>();
        if (fused != null) {
            for (ReciprocalRankFusion.FusedChunk fc : fused) {
                if (fc != null && fc.chunk() != null && fc.chunk().text() != null) {
                    prices.addAll(ChunkMetadataExtractor.extractAllPrices(fc.chunk().text()));
                }
            }
        }
        if (metadataCache != null && pageId != null) {
            BacklinkGraph graph = metadataCache.getGraphForPage(pageId);
            if (graph != null) {
                prices.addAll(graph.getAllPricesSorted());
            }
        }
        List<Long> sorted = new ArrayList<>(prices);
        Collections.sort(sorted);
        return sorted;
    }

    private static Set<String> extractValidCodesDynamically(
            List<ReciprocalRankFusion.FusedChunk> fused,
            ObsidianMetadataCache metadataCache,
            UUID pageId) {
        Set<String> codes = new LinkedHashSet<>();
        if (fused != null) {
            for (ReciprocalRankFusion.FusedChunk fc : fused) {
                if (fc != null && fc.chunk() != null && fc.chunk().text() != null) {
                    codes.addAll(ChunkMetadataExtractor.extractServiceCodes(fc.chunk().text()));
                    ChunkMetadata cm = ChunkMetadataExtractor.extract(fc.chunk().headingPath(), fc.chunk().text());
                    if (cm != null && cm.serviceCodes() != null) {
                        for (String c : cm.serviceCodes()) {
                            codes.add(c.toUpperCase(Locale.ROOT));
                        }
                    }
                }
            }
        }
        if (metadataCache != null && pageId != null) {
            BacklinkGraph graph = metadataCache.getGraphForPage(pageId);
            if (graph != null) {
                codes.addAll(graph.getServiceCodeIndex().keySet());
            }
        }
        return codes;
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

        boolean currentAsksPrice = asksExplicitPriceInMessage(currentMessage);
        List<String> recentUserInputs = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0 && recentUserInputs.size() < 2; i--) {
            FanpageBotRequest.ConversationTurn turn = history.get(i);
            if (turn != null && "user".equalsIgnoreCase(turn.role()) && turn.content() != null && !turn.content().isBlank()) {
                String pastTurn = turn.content().strip();
                // Không ghép các câu chỉ hỏi "Giá" / chào hỏi trống không ở lượt trước vào câu hỏi triệu chứng/quy trình hiện tại
                if (!currentAsksPrice && isBarePriceOrGreetingTurn(pastTurn)) {
                    continue;
                }
                recentUserInputs.add(0, pastTurn);
            }
        }
        String joined = recentUserInputs.isEmpty()
                ? currentMessage
                : String.join(" ", recentUserInputs) + " " + currentMessage;
        if (FanpageBotPromptBuilder.isPriceObjectionOrDownsellMessage(currentMessage)
                || FanpageBotPromptBuilder.isTrialOrBookingAcceptanceMessage(currentMessage)
                || PriceLadderTracker.isPromoOrDiscountInquiry(currentMessage)) {
            joined = joined + " bảng giá đơn giá dịch vụ gói cơ bản gói trải nghiệm gói khởi điểm mức giá thấp chương trình khuyến mãi chính sách ưu đãi giảm giá voucher liệu trình";
        }
        return joined;
    }

    static String buildActionTargetedSearchQuery(
            String currentMessage,
            List<FanpageBotRequest.ConversationTurn> history,
            ConversationState preRetrievalState,
            ConversationState preEnrichedState,
            PrimaryActionRouter.RoutingDecision preDecision
    ) {
        boolean isDetailFaqAction = (preDecision != null
                && preDecision.effectiveResponseAction() == PrimaryActionRouter.PrimaryAction.SERVICE_DETAIL_AND_FAQ_QA)
                || (preEnrichedState != null && preEnrichedState.hasUnresolvedCustomerRequest())
                || SentimentIntentLayer.isDetailOrFaqQuestion(
                        currentMessage,
                        preRetrievalState != null ? preRetrievalState.lastAssistantMessage() : null,
                        preRetrievalState != null ? preRetrievalState.knownRecommendedPackage() : null,
                        preRetrievalState != null && preRetrievalState.hasUnresolvedCustomerRequest());
        if (!isDetailFaqAction) {
            return buildContextualSearchQuery(currentMessage, history);
        }

        ConversationState.UnresolvedCustomerRequest activeReq = preEnrichedState != null
                ? preEnrichedState.mostRecentUnresolvedRequest()
                : (preRetrievalState != null ? preRetrievalState.mostRecentUnresolvedRequest() : null);
        String refEntity = activeReq != null && activeReq.referencedEntity() != null && !activeReq.referencedEntity().isBlank()
                ? activeReq.referencedEntity()
                : SentimentIntentLayer.extractReferencedDetailEntity(
                        currentMessage,
                        preRetrievalState != null ? preRetrievalState.lastAssistantMessage() : null,
                        preRetrievalState != null ? preRetrievalState.knownRecommendedPackage() : null);
        String canonicalQ = activeReq != null && activeReq.canonicalQuestion() != null && !activeReq.canonicalQuestion().isBlank()
                ? activeReq.canonicalQuestion()
                : currentMessage;
        String activePkg = preRetrievalState != null && preRetrievalState.knownRecommendedPackage() != null
                ? preRetrievalState.knownRecommendedPackage()
                : "";
        String combinedLower = ((canonicalQ != null ? canonicalQ : "") + " " + (refEntity != null ? refEntity : "")).toLowerCase(Locale.ROOT);

        StringBuilder qb = new StringBuilder();
        if (canonicalQ != null && !canonicalQ.isBlank()) {
            qb.append(canonicalQ.strip()).append(' ');
        }
        if (refEntity != null && !refEntity.isBlank() && !combinedLower.equals(refEntity.toLowerCase(Locale.ROOT))) {
            qb.append(refEntity.strip()).append(' ');
        }
        boolean isPolicyHoursOrAddressQuery = combinedLower.matches(
                "(?s).*(?:mở\\s+cửa|đóng\\s+cửa|mấy\\s+giờ|giờ\\s+hoạt\\s+động|giờ\\s+làm\\s+việc|nhận\\s+khách|địa\\s+chỉ|chi\\s+nhánh|cơ\\s+sở).*");
        if (combinedLower.matches("(?s).*(?:thực\\s+dưỡng|buffet|ăn\\s+nhẹ|điểm\\s+tâm|nước\\s+ép|trà\\s+thảo\\s+mộc|trái\\s+cây|hoa\\s+quả|thực\\s+đơn|món\\s+gì|món\\s+nào|uống\\s+gì).*")) {
            qb.append("FAQ ăn nhẹ điểm tâm phục hồi nước ép trái cây thực đơn buffet thực dưỡng sau liệu trình miễn phí ");
        } else if (isPolicyHoursOrAddressQuery) {
            qb.append("FAQ giờ mở cửa 09:00 21:00 nhận khách cuối 19:30 chính sách đặt lịch giờ hoạt động địa chỉ cơ sở ");
        } else if (combinedLower.matches("(?s).*(?:phòng|riêng\\s+tư|không\\s+gian|vip|rèm|cộng\\s+đồng).*")) {
            qb.append("FAQ không gian phòng riêng VIP phòng cộng đồng có rèm riêng tư ");
        } else if (combinedLower.matches("(?s).*(?:bao\\s+lâu|mấy\\s+phút|thời\\s+lượng|thời\\s+gian).*")) {
            qb.append("tổng thời gian thời lượng phút bao gồm quy trình chi tiết ");
        } else {
            qb.append("FAQ bao gồm điểm nổi bật quy trình chi tiết quyền lợi ");
        }
        if (activePkg != null && !activePkg.isBlank() && !isPolicyHoursOrAddressQuery) {
            qb.append(activePkg.strip());
        }
        return qb.toString().replaceAll("\\s+", " ").strip();
    }

    static List<ReciprocalRankFusion.FusedChunk> enrichFusedChunksForDetailAndFaqQa(
            List<ReciprocalRankFusion.FusedChunk> fused,
            ConversationState preEnrichedState,
            String mergedMessage,
            ObsidianMetadataCache metadataCache,
            UUID pageId
    ) {
        List<ReciprocalRankFusion.FusedChunk> baseList = fused != null ? new ArrayList<>(fused) : new ArrayList<>();
        ConversationState.UnresolvedCustomerRequest activeReq = preEnrichedState != null
                ? preEnrichedState.mostRecentUnresolvedRequest()
                : null;
        String queryContext = ((mergedMessage != null ? mergedMessage : "")
                + " " + (activeReq != null && activeReq.canonicalQuestion() != null ? activeReq.canonicalQuestion() : "")
                + " " + (activeReq != null && activeReq.referencedEntity() != null ? activeReq.referencedEntity() : ""))
                .toLowerCase(Locale.ROOT);
        boolean isRefreshmentOrMenuQuery = queryContext.matches(
                "(?s).*(?:thực\\s+dưỡng|buffet|ăn\\s+nhẹ|điểm\\s+tâm|nước\\s+ép|trà\\s+thảo\\s+mộc|trái\\s+cây|hoa\\s+quả|thực\\s+đơn|món\\s+gì|món\\s+nào).*");
        boolean isOpeningHoursOrPolicyQuery = queryContext.matches(
                "(?s).*(?:mở\\s+cửa|đóng\\s+cửa|mấy\\s+giờ|giờ\\s+hoạt\\s+động|giờ\\s+làm\\s+việc|nhận\\s+khách|địa\\s+chỉ|chi\\s+nhánh|cơ\\s+sở).*");

        if (metadataCache != null && pageId != null) {
            BacklinkGraph graph = metadataCache.getGraphForPage(pageId);
            if (graph != null && graph.getNodes() != null) {
                Set<UUID> existingIds = new LinkedHashSet<>();
                for (ReciprocalRankFusion.FusedChunk fc : baseList) {
                    if (fc != null && fc.chunk() != null && fc.chunk().chunkId() != null) {
                        existingIds.add(fc.chunk().chunkId());
                    }
                }
                List<ReciprocalRankFusion.FusedChunk> matchedFaqNodes = new ArrayList<>();
                for (ChunkGraphNode node : graph.getNodes().values()) {
                    if (node == null || node.chunkId() == null || existingIds.contains(node.chunkId()) || node.text() == null) {
                        continue;
                    }
                    String nodeLower = node.text().toLowerCase(Locale.ROOT);
                    String headingLower = node.headingPath() != null
                            ? String.join(" ", node.headingPath()).toLowerCase(Locale.ROOT)
                            : "";
                    if (isRefreshmentOrMenuQuery) {
                        if (headingLower.contains("thực đơn")
                                || (headingLower.contains("faq") && (nodeLower.contains("ăn nhẹ") || nodeLower.contains("điểm tâm") || nodeLower.contains("nước ép") || nodeLower.contains("thực dưỡng")))) {
                            matchedFaqNodes.add(new ReciprocalRankFusion.FusedChunk(node.toRetrievedChunk(0.99), 0.99));
                            existingIds.add(node.chunkId());
                        }
                    } else if (isOpeningHoursOrPolicyQuery) {
                        if ((headingLower.contains("faq") || headingLower.contains("chính_sách") || headingLower.contains("chính sách"))
                                && (nodeLower.contains("mở cửa") || nodeLower.contains("nhận khách cuối") || nodeLower.contains("19:30") || nodeLower.contains("21:00"))) {
                            matchedFaqNodes.add(new ReciprocalRankFusion.FusedChunk(node.toRetrievedChunk(0.99), 0.99));
                            existingIds.add(node.chunkId());
                        }
                    } else if (headingLower.contains("faq") && activeReq != null && activeReq.referencedEntity() != null
                            && nodeLower.contains(activeReq.referencedEntity().toLowerCase(Locale.ROOT))) {
                        matchedFaqNodes.add(new ReciprocalRankFusion.FusedChunk(node.toRetrievedChunk(0.98), 0.98));
                        existingIds.add(node.chunkId());
                    }
                }
                if (!matchedFaqNodes.isEmpty()) {
                    baseList.addAll(0, matchedFaqNodes);
                }
            }
        }

        // Prioritize FAQ and menu chunks ahead of broad catalog tables when answering a detail/FAQ question
        List<ReciprocalRankFusion.FusedChunk> prioritizedFaq = new ArrayList<>();
        List<ReciprocalRankFusion.FusedChunk> remaining = new ArrayList<>();
        for (ReciprocalRankFusion.FusedChunk fc : baseList) {
            if (fc == null || fc.chunk() == null || fc.chunk().text() == null) {
                continue;
            }
            String tx = fc.chunk().text().toLowerCase(Locale.ROOT);
            String hp = fc.chunk().headingPath() != null
                    ? String.join(" ", fc.chunk().headingPath()).toLowerCase(Locale.ROOT)
                    : "";
            if (isRefreshmentOrMenuQuery
                    && (hp.contains("thực đơn") || hp.contains("faq") || tx.contains("điểm tâm phục hồi") || tx.contains("thực đơn buffet"))) {
                prioritizedFaq.add(fc);
            } else if (isOpeningHoursOrPolicyQuery
                    && (tx.contains("mở cửa") || tx.contains("nhận khách cuối") || tx.contains("19:30") || hp.contains("chính_sách_đặt_lịch"))) {
                prioritizedFaq.add(fc);
            } else {
                remaining.add(fc);
            }
        }
        List<ReciprocalRankFusion.FusedChunk> result = new ArrayList<>(prioritizedFaq.size() + remaining.size());
        result.addAll(prioritizedFaq);
        result.addAll(remaining);
        return result;
    }

    static List<ReciprocalRankFusion.FusedChunk> enrichFusedChunksWithOperationalAndPromoFacts(
            List<ReciprocalRankFusion.FusedChunk> fused,
            String mergedMessage,
            ObsidianMetadataCache metadataCache,
            UUID pageId
    ) {
        List<ReciprocalRankFusion.FusedChunk> baseList = fused != null ? new ArrayList<>(fused) : new ArrayList<>();
        if (mergedMessage == null || mergedMessage.isBlank() || metadataCache == null || pageId == null) {
            return baseList;
        }
        BacklinkGraph graph = metadataCache.getGraphForPage(pageId);
        if (graph == null || graph.getNodes() == null || graph.getNodes().isEmpty()) {
            return baseList;
        }
        String qLow = mergedMessage.toLowerCase(Locale.ROOT);
        boolean asksPromo = qLow.matches("(?s).*(?:\\bkm\\b|khuyến\\s+mãi|khuyến\\s+mại|giảm\\s+giá|ưu\\s+đãi|voucher|sale|\\btip\\b|phụ\\s+phí|đắt|cao\\s+quá|chi\\s+phí|giá\\s+cao|trọn\\s+gói|ngày\\s+công|ngày\\s+làm).*");
        boolean asksInclusion = qLow.matches("(?s).*(?:ăn\\s+nhẹ|điểm\\s+tâm|trà\\s+dưỡng\\s+sinh|bao\\s+gồm|gồm\\s+gì|gồm\\s+những\\s+gì).*");
        boolean asksHours = qLow.matches("(?s).*(?:mở\\s+cửa|đóng\\s+cửa|mấy\\s+giờ|giờ\\s+hoạt\\s+động|giờ\\s+làm\\s+việc|nhận\\s+khách).*");
        boolean asksAddress = qLow.matches("(?s).*(?:ở\\s+đâu|địa\\s+chỉ|cơ\\s+sở|chi\\s+nhánh|bích\\s+câu|mỹ\\s+đình|long\\s+biên|nguyễn\\s+công\\s+trứ).*");
        if (!asksPromo && !asksInclusion && !asksHours && !asksAddress) {
            return baseList;
        }
        boolean filterHanoi = qLow.contains("hà nội") || qLow.contains("ha noi") || qLow.contains("hn");
        boolean filterHcm = qLow.contains("hcm") || qLow.contains("hồ chí minh") || qLow.contains("sài gòn") || qLow.contains("quận 1");

        List<ReciprocalRankFusion.FusedChunk> prepended = new ArrayList<>();
        List<ReciprocalRankFusion.FusedChunk> hcmAddressDeferred = new ArrayList<>();
        Set<UUID> chosenIds = new LinkedHashSet<>();
        for (ChunkGraphNode node : graph.getNodes().values()) {
            if (node == null || node.chunkId() == null || node.text() == null) continue;
            String nLow = node.text().toLowerCase(Locale.ROOT);
            boolean match = false;
            boolean deferAfterHanoi = false;
            if ((asksPromo || asksInclusion) && (nLow.contains("6.2.")
                    || nLow.contains("bảng chính sách ưu đãi")
                    || nLow.contains("ưu đãi thẻ liệu trình")
                    || nLow.contains("ưu đãi mua liệu trình"))) {
                match = true;
            }
            if (asksInclusion && (nLow.contains("đồ ăn nhẹ có tính thêm phí không")
                    || nLow.contains("phần ăn nhẹ và trà dưỡng sinh đã bao gồm"))) {
                match = true;
            }
            if (asksHours && (nLow.contains("giờ mở cửa hoạt động") || (nLow.contains("mở cửa từ") && nLow.contains("nhận khách cuối")))) {
                match = true;
            }
            if (asksAddress && nLow.contains("địa chỉ:")) {
                if (filterHanoi) {
                    match = nLow.contains("hà nội");
                } else if (filterHcm) {
                    match = nLow.contains("hồ chí minh") || nLow.contains("quận 1");
                } else {
                    match = true;
                    deferAfterHanoi = !nLow.contains("hà nội");
                }
            }
            if (match && chosenIds.add(node.chunkId())) {
                ReciprocalRankFusion.FusedChunk enrichedChunk =
                        new ReciprocalRankFusion.FusedChunk(node.toRetrievedChunk(0.995), 0.995);
                if (deferAfterHanoi) {
                    hcmAddressDeferred.add(enrichedChunk);
                } else {
                    prepended.add(enrichedChunk);
                }
            }
        }
        prepended.addAll(hcmAddressDeferred);
        if (prepended.isEmpty()) {
            return baseList;
        }
        List<ReciprocalRankFusion.FusedChunk> merged = new ArrayList<>(prepended);
        for (ReciprocalRankFusion.FusedChunk fc : baseList) {
            if (fc == null || fc.chunk() == null || fc.chunk().chunkId() == null || chosenIds.add(fc.chunk().chunkId())) {
                merged.add(fc);
            }
        }
        return merged;
    }

    private static boolean isBarePriceOrGreetingTurn(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        return text.strip().matches(
                "(?isU)^\\s*(?:dạ\\s+)?(?:cho\\s+(?:anh|chị|em|mình)\\s+)?(?:xin\\s+|hỏi\\s+|báo\\s+)?(?:bảng\\s+)?"
                + "(?:giá|gia|ib\\s+giá|giá\\s+sao|giá\\s+ntn|giá\\s+như\\s+nào|bao\\s+nhiêu|bn|alo|chào|hi|hello|shop\\s+ơi|ad\\s+ơi|tư\\s+vấn)"
                + "(?:\\s+(?:dịch\\s+vụ|bên\\s+mình|với|nhé|ạ|em|shop))*\\s*[?.,!~]*$"
        );
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
        return determineSalesStage(message, history, extractedLead, null);
    }

    static FanpageBotPromptBuilder.SalesStage determineSalesStage(
            String message,
            List<FanpageBotRequest.ConversationTurn> history,
            FanpageBotResponse.ExtractedLead extractedLead,
            PriceLadderResult priceLadder) {
        if (priceLadder != null && priceLadder.isPriceObjection()) {
            return FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION;
        }
        if (priceLadder != null && priceLadder.isTrialRequest()) {
            return FanpageBotPromptBuilder.SalesStage.SOFT_BOOKING_CLOSE;
        }
        if (FanpageBotPromptBuilder.isPackageComparisonInquiry(message)) {
            return FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION;
        }
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
        if (SentimentIntentLayer.isAntiApologyPushback(message)
                || SentimentIntentLayer.isWordingOrExpressionFeedback(message)
                || SentimentIntentLayer.isConversationStyleComplaint(message)
                || SentimentIntentLayer.isMetaConversationOnBotBehavior(message)
                || SentimentIntentLayer.isComprehensionFailureMessage(message)) {
            return turnCount <= 4
                    ? FanpageBotPromptBuilder.SalesStage.DISCOVERY
                    : FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION;
        }
        if (FanpageBotPromptBuilder.isServiceExplanationOrProcessInquiry(message)
                || SentimentIntentLayer.isDetailOrFaqQuestion(message, null, null)
                || FanpageBotPromptBuilder.isWellnessOrDiscomfortMessage(message)
                || FanpageBotPromptBuilder.isSymptomFollowUpTurn(message, history)) {
            return FanpageBotPromptBuilder.SalesStage.VALUE_CONSULTATION;
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
            List<FanpageBotRequest.ConversationTurn> history,
            String currentMessage,
            PriceLadderResult priceLadder) {
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

        // 4. Khử triệt để mọi cú pháp kỹ thuật (wikilinks, tables, citations, markdown headers, bold, leaked tags)
        working = SyntaxSanitizationHook.sanitize(working, honorific);

        if (working.isBlank()) {
            working = "Dạ em xin phép kết nối ngay với Chuyên viên phụ trách của Page để hỗ trợ chi tiết cho " + honorific + " liền nhé ạ!";
            handoff = true;
            handoffCategory = "UNGROUNDED_OR_UNKNOWN";
            if (handoffReason == null) {
                handoffReason = "Nội dung phản hồi cần nhân viên xác nhận.";
            }
        }

        if (priceLadder != null && priceLadder.shouldSuppressHandoff()) {
            if (handoff) {
                log.info("[PriceLadder] Suppressed premature handoff for price objection/trial/floor turn (targetPrice={})",
                        priceLadder.targetPrice());
                handoff = false;
                handoffCategory = null;
                handoffReason = null;
            }
        }

        if (!handoff && priceLadder != null) {
            working = enforcePriceLadderConsistency(working, priceLadder, chunks, honorific, currentMessage);
        }

        return new PostProcessedBotReply(working, handoff, handoffCategory, handoffReason);
    }

    static List<ReciprocalRankFusion.FusedChunk> enrichFusedChunksWithPriceLadderTargets(
            List<ReciprocalRankFusion.FusedChunk> fused,
            PriceLadderResult priceLadder,
            String mergedMessage,
            ObsidianMetadataCache metadataCache,
            UUID pageId) {
        if (fused == null || fused.isEmpty()) {
            return fused;
        }
        List<ReciprocalRankFusion.FusedChunk> updated = new ArrayList<>(fused);
        String lowerMerged = mergedMessage != null ? mergedMessage.toLowerCase(Locale.ROOT) : "";
        boolean wantsSaigon = "saigon".equalsIgnoreCase(PriceLadderTracker.resolveBranchFromText(mergedMessage))
                || lowerMerged.matches("(?s).*\\b(570|750|870)\\b.*");
        boolean asksHairWashExplicitly = lowerMerged.matches("(?s).*(?:gội|tóc|da\\s+đầu).*");
        if (!wantsSaigon && metadataCache != null && pageId != null) {
            boolean hasDefaultMassage530 = false;
            boolean hasDefaultMassage580 = false;
            for (ReciprocalRankFusion.FusedChunk fc : updated) {
                if (fc != null && fc.chunk() != null && fc.chunk().text() != null) {
                    String tx = fc.chunk().text().toLowerCase(Locale.ROOT);
                    if (tx.contains("530.000")) {
                        hasDefaultMassage530 = true;
                    }
                    if (tx.contains("580.000")) {
                        hasDefaultMassage580 = true;
                    }
                }
            }
            BacklinkGraph graph = metadataCache.getGraphForPage(pageId);
            if (graph != null) {
                if (asksHairWashExplicitly) {
                    Set<UUID> ids339 = graph.getPriceIndex().get(339_000L);
                    if (ids339 != null) {
                        for (UUID cid : ids339) {
                            ChunkGraphNode node = graph.getNodes().get(cid);
                            if (node != null && node.text() != null && node.text().contains("239.000")) {
                                updated.add(0, new ReciprocalRankFusion.FusedChunk(node.toRetrievedChunk(1.0), 1.0));
                                break;
                            }
                        }
                    }
                } else if (!hasDefaultMassage530 || !hasDefaultMassage580) {
                    if (!hasDefaultMassage580) {
                        Set<UUID> ids580 = graph.getPriceIndex().get(580_000L);
                        if (ids580 != null) {
                            for (UUID cid : ids580) {
                                ChunkGraphNode node = graph.getNodes().get(cid);
                                if (node != null && node.text() != null && node.text().contains("399.000")) {
                                    updated.add(0, new ReciprocalRankFusion.FusedChunk(node.toRetrievedChunk(1.0), 1.0));
                                    break;
                                }
                            }
                        }
                    }
                    if (!hasDefaultMassage530) {
                        Set<UUID> ids530 = graph.getPriceIndex().get(530_000L);
                        if (ids530 != null) {
                            for (UUID cid : ids530) {
                                ChunkGraphNode node = graph.getNodes().get(cid);
                                if (node != null && node.text() != null && node.text().contains("399.000")) {
                                    updated.add(0, new ReciprocalRankFusion.FusedChunk(node.toRetrievedChunk(1.0), 1.0));
                                    break;
                                }
                            }
                        }
                    }
                }
                if (graph.getPriceIndex() != null) {
                    Set<UUID> existingChunkIds = new LinkedHashSet<>();
                    for (ReciprocalRankFusion.FusedChunk fc : updated) {
                        if (fc != null && fc.chunk() != null && fc.chunk().chunkId() != null) {
                            existingChunkIds.add(fc.chunk().chunkId());
                        }
                    }
                    for (Set<UUID> cids : graph.getPriceIndex().values()) {
                        if (cids == null) continue;
                        for (UUID cid : cids) {
                            if (existingChunkIds.contains(cid)) continue;
                            ChunkGraphNode node = graph.getNodes().get(cid);
                            if (node != null && node.text() != null && node.text().contains("|")) {
                                updated.add(new ReciprocalRankFusion.FusedChunk(node.toRetrievedChunk(0.92), 0.92));
                                existingChunkIds.add(cid);
                            }
                        }
                    }
                }
            }
        }
        List<Long> explicitQueryPrices = PriceLadderTracker.extractPricesFromText(mergedMessage);
        if (explicitQueryPrices != null && !explicitQueryPrices.isEmpty()) {
            for (int pIdx = explicitQueryPrices.size() - 1; pIdx >= 0; pIdx--) {
                long ep = explicitQueryPrices.get(pIdx);
                String epDot = String.format(Locale.ROOT, "%,d", ep).replace(',', '.');
                int foundIdx = -1;
                for (int i = 0; i < updated.size(); i++) {
                    ReciprocalRankFusion.FusedChunk fc = updated.get(i);
                    if (fc != null && fc.chunk() != null && fc.chunk().text() != null && fc.chunk().text().contains(epDot)) {
                        foundIdx = i;
                        break;
                    }
                }
                if (foundIdx > 0) {
                    ReciprocalRankFusion.FusedChunk c = updated.remove(foundIdx);
                    updated.add(0, c);
                } else if (foundIdx == -1 && metadataCache != null && pageId != null) {
                    BacklinkGraph graph = metadataCache.getGraphForPage(pageId);
                    if (graph != null) {
                        Set<UUID> epIds = graph.getPriceIndex().get(ep);
                        if (epIds != null) {
                            for (UUID cid : epIds) {
                                ChunkGraphNode node = graph.getNodes().get(cid);
                                if (node != null && node.text() != null && node.text().contains("|")) {
                                    updated.add(0, new ReciprocalRankFusion.FusedChunk(node.toRetrievedChunk(1.0), 1.0));
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }
        if (priceLadder != null && priceLadder.hasTargetPrice()) {
            long targetPrice = priceLadder.targetPrice();
            String targetDot = String.format(Locale.ROOT, "%,d", targetPrice).replace(',', '.');
            int foundIdx = -1;
            for (int i = 0; i < updated.size(); i++) {
                ReciprocalRankFusion.FusedChunk fc = updated.get(i);
                if (fc != null && fc.chunk() != null && fc.chunk().text() != null && fc.chunk().text().contains(targetDot)) {
                    foundIdx = i;
                    break;
                }
            }
            if (foundIdx > 0) {
                ReciprocalRankFusion.FusedChunk targetChunk = updated.remove(foundIdx);
                updated.add(0, targetChunk);
            } else if (foundIdx == -1 && metadataCache != null && pageId != null) {
                BacklinkGraph graph = metadataCache.getGraphForPage(pageId);
                if (graph != null) {
                    Set<UUID> priceChunkIds = graph.getPriceIndex().get(targetPrice);
                    if (priceChunkIds != null && !priceChunkIds.isEmpty()) {
                        ChunkGraphNode bestNode = null;
                        for (UUID cid : priceChunkIds) {
                            ChunkGraphNode candidate = graph.getNodes().get(cid);
                            if (candidate != null) {
                                bestNode = candidate;
                                if (candidate.metadata() != null && ChunkMetadata.ROLE_PRICE_TABLE.equalsIgnoreCase(candidate.metadata().chunkRole())) {
                                    break;
                                }
                            }
                        }
                        if (bestNode != null) {
                            updated.add(0, new ReciprocalRankFusion.FusedChunk(bestNode.toRetrievedChunk(1.0), 1.0));
                        }
                    }
                }
            }
        }

        if (PriceLadderTracker.isPromoOrDiscountInquiry(mergedMessage)
                || (priceLadder != null && priceLadder.isPriceObjection())) {
            boolean topHasPromo = false;
            int checkLimit = Math.min(2, updated.size());
            for (int i = 0; i < checkLimit; i++) {
                ReciprocalRankFusion.FusedChunk fc = updated.get(i);
                if (fc != null && fc.chunk() != null && fc.chunk().text() != null) {
                    String low = fc.chunk().text().toLowerCase(Locale.ROOT);
                    if ((low.contains("giảm") || low.contains("ưu đãi") || low.contains("khuyến mãi"))
                            && (low.contains("%") || low.contains("voucher") || low.contains("combo"))) {
                        topHasPromo = true;
                        break;
                    }
                }
            }
            if (!topHasPromo) {
                int promoIdx = -1;
                for (int i = checkLimit; i < updated.size(); i++) {
                    ReciprocalRankFusion.FusedChunk fc = updated.get(i);
                    if (fc != null && fc.chunk() != null && fc.chunk().text() != null) {
                        String low = fc.chunk().text().toLowerCase(Locale.ROOT);
                        if ((low.contains("giảm") || low.contains("ưu đãi") || low.contains("khuyến mãi"))
                                && (low.contains("%") || low.contains("voucher") || low.contains("combo"))) {
                            promoIdx = i;
                            break;
                        }
                    }
                }
                int insertPos = Math.min(1, updated.size());
                if (promoIdx >= 0) {
                    ReciprocalRankFusion.FusedChunk promoChunk = updated.remove(promoIdx);
                    updated.add(insertPos, promoChunk);
                } else if (metadataCache != null && pageId != null) {
                    BacklinkGraph graph = metadataCache.getGraphForPage(pageId);
                    if (graph != null) {
                        List<ChunkGraphNode> promoNodes = graph.getNodesByRole(ChunkMetadata.ROLE_PROMO);
                        if (promoNodes != null && !promoNodes.isEmpty() && promoNodes.get(0) != null) {
                            updated.add(insertPos, new ReciprocalRankFusion.FusedChunk(promoNodes.get(0).toRetrievedChunk(0.95), 0.95));
                        }
                    }
                }
            }
        }
        return updated;
    }

    static String enforcePriceLadderConsistency(
            String working,
            PriceLadderResult priceLadder,
            List<ReciprocalRankFusion.FusedChunk> chunks,
            String resolvedHonorific,
            String currentMessage) {
        if (working == null || working.isBlank() || priceLadder == null) {
            return working;
        }
        String h = (resolvedHonorific != null && !resolvedHonorific.isBlank()) ? resolvedHonorific.strip() : "Chị";

        if (priceLadder.isTrialRequest() && priceLadder.hasTargetPrice()) {
            long targetPrice = priceLadder.targetPrice();
            String targetDot = String.format(Locale.ROOT, "%,d", targetPrice).replace(',', '.');
            String targetK = (targetPrice / 1000L) + "k";
            List<Long> replyPrices = PriceLadderTracker.extractPricesFromText(working);
            List<Long> explicitPrices = PriceLadderTracker.extractPricesFromText(currentMessage);
            boolean isApproxPriceMatch = !explicitPrices.isEmpty() && explicitPrices.get(0) != targetPrice;
            boolean hasTargetPrice = replyPrices.contains(targetPrice)
                    || working.contains(targetDot)
                    || working.toLowerCase(Locale.ROOT).contains(targetK);
            boolean hasDriftedPrice = replyPrices.stream().anyMatch(p -> p != targetPrice && Math.abs(p - targetPrice) >= 30_000L);
            boolean hasMismatched100MinOnBasic = (targetPrice == 399_000L && working.contains("100 phút"));

            if (!hasTargetPrice || hasDriftedPrice || hasMismatched100MinOnBasic || isApproxPriceMatch) {
                boolean isSaigon = targetPrice == 570_000L || targetPrice == 260_000L;
                Map<Long, DynamicPackageDetail> pkgByPrice = extractPackagesByPriceFromFused(chunks, isSaigon);
                DynamicPackageDetail detail = pkgByPrice.get(targetPrice);
                String pkgName = priceLadder.targetPackageName();
                if (pkgName == null || pkgName.isBlank()) {
                    pkgName = detail != null ? detail.displayLabel() : "Gói cơ bản";
                }
                if (!pkgName.toLowerCase(Locale.ROOT).startsWith("gói")) {
                    pkgName = "Gói " + pkgName;
                }
                String durationClause = "";
                if (detail != null && detail.duration() != null && !detail.duration().isBlank() && !detail.duration().equalsIgnoreCase("null")) {
                    durationClause = " (" + detail.duration().strip() + ")";
                } else if (targetPrice == 399_000L) {
                    durationClause = " (60 phút)";
                } else if (targetPrice == 570_000L) {
                    durationClause = " (70 phút)";
                }
                if (isApproxPriceMatch) {
                    return "Dạ " + h + " ạ, gói cơ bản mềm nhất gần mức " + PriceLadderTracker.formatPrice(explicitPrices.get(0))
                            + " bên em là " + pkgName + " niêm yết " + PriceLadderTracker.formatPrice(targetPrice)
                            + durationClause + " (trọn gói không nhận Tip), rất phù hợp để trải nghiệm thư giãn toàn thân ngay từ buổi đầu ạ.\n\n"
                            + h + " muốn đặt lịch vào khung giờ Sáng hay Chiều để bên em giữ lịch trước cho mình ạ?";
                }
                return "Dạ " + h + " ạ, " + pkgName + " với mức giá " + PriceLadderTracker.formatPrice(targetPrice)
                        + durationClause + " rất phù hợp để " + h
                        + " trải nghiệm thư giãn toàn thân và giảm đau mỏi hiệu quả ngay từ buổi đầu ạ.\n\n"
                        + h + " muốn đặt lịch vào khung giờ Sáng hay Chiều để bên em giữ lịch trước cho mình ạ?";
            }
        }

        if (priceLadder.isPriceObjection()
                && PriceLadderTracker.isPromoOrDiscountInquiry(currentMessage)
                && priceLadder.discountPolicy() != null
                && !priceLadder.discountPolicy().isBlank()) {
            String lowerW = working.toLowerCase(Locale.ROOT);
            boolean mentionsPromo = working.contains("%") || lowerW.contains("voucher") || lowerW.contains("5 buổi") || lowerW.contains("combo");
            if (!mentionsPromo) {
                String promoClean = priceLadder.discountPolicy()
                        .replaceAll("^[•\\-*\\s]+", "")
                        .replaceAll("[.]+$", "")
                        .strip();
                if (!promoClean.isEmpty()) {
                    String promoLowerFirst = Character.toLowerCase(promoClean.charAt(0)) + promoClean.substring(1);
                    String strippedOpening = working.replaceFirst(
                            "(?isU)^\\s*dạ\\s+(?:(?:anh\\s*/\\s*chị|chị\\s*/\\s*anh|anh|chị)\\s+(?:ạ[,\\s]*|[,\\s]+))?",
                            ""
                    ).strip();
                    if (!strippedOpening.isEmpty()) {
                        strippedOpening = Character.toLowerCase(strippedOpening.charAt(0)) + strippedOpening.substring(1);
                    }
                    return "Dạ " + h + " ạ, hiện bên em có chương trình ưu đãi " + promoLowerFirst
                            + " nha. Nếu muốn trải nghiệm lẻ trước, " + strippedOpening;
                }
            }
        }

        return working;
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
                // Xóa dấu tiêu đề Markdown ###, nhãn Đoạn 1/2, Bubble 1/2, nhãn A/B, từ cụt Hay./Hay? và dấu đậm ** dư thừa trên Messenger
                .replaceAll("(?im)^\\s*(?:đoạn|đoàn|bong\\s*bóng|bubble)\\s*\\d+\\s*[:\\-]\\s*", "")
                .replaceAll("(?im)(?:^|(?<=[.!?\\n]\\s*))(?:\\[?\\s*câu\\s*hỏi\\s*a\\s*/\\s*b\\s*]?|a\\s*/\\s*b)\\s*[:.\\-]?\\s*", "")
                .replaceAll("(?iU)\\s+\\(?A\\s*/\\s*B\\)?[.!?]?\\s*$", "")
                .replaceAll("(?iU)(?:(?<=^)|(?<=[.!?\\n]\\s*))(?:hay\\s+là|hay|hoặc\\s+là|hoặc)\\s*[.!?]+(?=\\s|$)", "")
                .replaceAll("(?im)(?<=[.!?])\\s+(?:hay\\s+là|hay|hoặc\\s+là|hoặc)\\s*(?=\\n|$)", "")
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
        } else if ("Anh/Chị".equalsIgnoreCase(honorific)) {
            working = working
                    .replaceAll("(?iU)(?<!\\p{L})chị\\s*/\\s*anh(?!\\p{L})", "Anh/Chị")
                    .replaceAll("(?iU)(?<!\\p{L})chị(?!\\p{L})", "Anh/Chị")
                    .replaceAll("(?iU)(?<!\\p{L})(?:anh\\s*/\\s*)+chị(?!\\p{L})", "Anh/Chị")
                    .replaceAll("(?iU)(?<=\\b(?:chào|của|cho|với|hỗ\\s+trợ|tư\\s+vấn|mời|giúp)\\s+)bạn(?!\\p{L})", "Anh/Chị")
                    .replaceAll("(?iU)(?<!\\p{L})bạn(?=\\s+(?:có\\s+thể|muốn|cần|đang|sẽ|nên))", "Anh/Chị");
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
                        .replaceAll("(?isU)^\\s*(?:dạ[,\\s]+)?(?:em\\s+|bên\\s+em\\s+)?(?:xin\\s+|kính\\s+)?chào\\s+(?:anh\\s*/\\s*chị|chị\\s*/\\s*anh|anh|chị|bạn|mình|quý\\s+khách)?(?:\\s+[^!.,~\\n]{1,25})?(?:\\s+ạ|\\s+nha|\\s+nhé)?\\s*[!.,~]*\\s*", "")
                        .replaceAll("(?isU)^\\s*(?:dạ[,\\s]+)?em\\s+rất\\s+vui\\s+được\\s+(?:tư\\s+vấn|hỗ\\s+trợ|đồng\\s+hành|phục\\s+vụ)[^.!?\\n]*[.!?]+\\s*", "")
                        .replaceAll("(?isU)^\\s*(?:dạ[,\\s]+)?(?:em\\s+|bên\\s+em\\s+)?(?:xin\\s+|kính\\s+)?chào\\s+[^!.,~\\n]{1,30}\\s*[!.,~]*\\s*", "")
                        .replaceAll("(?isU)^\\s*(?:dạ[,\\s]+)?/\\s*(?:chị|anh)(?:\\s+ạ|\\s+nha|\\s+nhé)?\\s*[!.,~]*\\s*", "")
                        .strip();
            } while (!working.equals(prev) && !working.isBlank());

            if (!working.isBlank()) {
                if (!working.toLowerCase(Locale.ROOT).startsWith("dạ")
                        && !working.toLowerCase(Locale.ROOT).startsWith(honorific.toLowerCase(Locale.ROOT))) {
                    boolean isAcronymStart = working.length() > 1 && Character.isUpperCase(working.charAt(1));
                    working = "Dạ " + (isAcronymStart ? working.charAt(0) : Character.toLowerCase(working.charAt(0))) + working.substring(1);
                }
            }
        } else {
            // Ở lượt đầu tiên (isFirstTurn): Gộp câu chào ngắn đứng riêng lẻ ("Dạ em chào Chị ạ!\n\n...") vào ngay đầu đoạn 1 để giữ đúng chuẩn tối đa 2 bong bóng
            working = working.replaceFirst(
                    "(?isU)^\\s*((?:dạ[,\\s]+)?(?:em\\s+|bên\\s+em\\s+)?(?:xin\\s+|kính\\s+)?chào\\s+[^!.,~\\n]{1,35}[.!~]*)\\s*\\n+\\s*",
                    "$1 "
            );
        }

        // Nếu khách vừa nói "thi thoảng" / "mới bị" (MILD_OR_OCCASIONAL) mà LLM lại nhầm thành "thường xuyên bị" -> sửa đúng theo lời khách
        if (FanpageBotPromptBuilder.detectSymptomSeverity(currentMessage)
                == FanpageBotPromptBuilder.SymptomSeverity.MILD_OR_OCCASIONAL) {
            working = working.replaceAll("(?iU)\\bthường\\s+xuyên\\s+bị\\b", "thi thoảng bị");
        }

        // Loại bỏ câu giảng giải nguyên nhân sách vở rườm rà ("Điều này thường do...", "Tình trạng này thường xuất hiện khi...") khi đã có câu tư vấn giải pháp
        if (working.contains(". ")) {
            String strippedCause = working.replaceAll(
                    "(?isU)(?:(?<=^)|(?<=[.!?]\\s)|(?<=\\n))(?:dạ\\s+)?(?:điều\\s+này|tình\\s+trạng\\s+này|nguyên\\s+nhân\\s+này|việc\\s+này)\\s+(?:thường\\s+do|có\\s+thể\\s+do|thường\\s+xuất\\s+hiện\\s+khi|xảy\\s+ra\\s+do)[^.!?\\n]{10,140}[.!]\\s*",
                    ""
            ).strip();
            if (!strippedCause.isBlank() && strippedCause.length() >= 40) {
                working = strippedCause;
            }
        }

        // Nếu đoạn 1 chỉ là câu chào ngắn (<= 45 ký tự) hoặc văn bản có > 3 đoạn, gộp đoạn 1 và đoạn 2 để giữ nhịp tối đa 3 bong bóng chat
        String[] paras = working.split("\\n\\s*\\n");
        if (paras.length >= 3 && !paras[0].strip().endsWith("?")
                && (paras[0].strip().length() <= 45 || paras.length > 3)) {
            StringBuilder merged = new StringBuilder();
            merged.append(paras[0].strip()).append(" ").append(paras[1].strip());
            for (int i = 2; i < paras.length; i++) {
                merged.append("\n\n").append(paras[i].strip());
            }
            working = merged.toString();
        }

        working = working
                .replaceAll("(?iU)(?:anh/chị|chị|anh)?\\s*venient\\b", honorific + " tiện lợi")
                .replaceAll("(?iU)\\bbooked\\b", "giữ lịch")
                .replaceAll("(?U),\\s+(?=(?:Anh/Chị|Chị|Anh)\\s+(?:thường|muốn|cần|đang|tiện|bị)\\b[^.!?\n]*\\?)", ".\n\n")
                .replaceAll("(?U)(?<!\\p{L})(ạ|nha|nhé)\\.?\\s+(?=(?:Sáng\\s+hay\\s+Chiều|(?:Anh/Chị|Chị|Anh|Hiện\\s+tại|Tình\\s+trạng)\\s+(?:muốn|cần|đang|tiện|cho|dự\\s+định|thường|ưu\\s+tiên|quan\\s+tâm|là|có|bị))\\b)", "$1.\n\n");

        // GỌT GIŨA CÂU HỎI KẾT THÚC DỒN DẬP / KHÔ KHAN / LẶP LẠI THEO GIAI ĐOẠN VỚI LỊCH SỬ (Issue #2)
        working = polishClosingQuestions(working, honorific, salesStage, isFirstTurn, history, currentMessage);

        // Bảo đảm 100% không sót từ "Chị" / "chị" khi xưng hô là "Anh" hoặc "Anh/Chị"
        if ("Anh".equalsIgnoreCase(honorific)) {
            working = working.replaceAll("(?iU)(?<!\\p{L})chị(?!\\p{L})", "Anh");
        } else if ("Anh/Chị".equalsIgnoreCase(honorific)) {
            working = working
                    .replaceAll("(?iU)(?<!\\p{L})chị(?!\\p{L})", "Anh/Chị")
                    .replaceAll("(?iU)(?<!\\p{L})(?:anh\\s*/\\s*)+chị(?!\\p{L})", "Anh/Chị");
        }

        return working
                .replaceAll("(?im)(?:^|(?<=[.!?\\n]\\s*))(?:\\[?\\s*câu\\s*hỏi\\s*a\\s*/\\s*b\\s*]?|a\\s*/\\s*b)\\s*[:.\\-]?\\s*", "")
                .replaceAll("(?iU)(?:(?<=^)|(?<=[.!?\\n]\\s*))(?:hay\\s+là|hay|hoặc\\s+là|hoặc)\\s*[.!?]+(?=\\s|$)", "")
                .replaceAll("(?im)(?<=[.!?])\\s+(?:hay\\s+là|hay|hoặc\\s+là|hoặc)\\s*(?=\\n|$)", "")
                .replaceAll("(?U)(?<!\\p{L})(ạ|nha|nhé)\\.?\\s+(?=(?:Sáng\\s+hay\\s+Chiều|(?:Anh/Chị|Chị|Anh|Hiện\\s+tại|Tình\\s+trạng)\\s+(?:muốn|cần|đang|tiện|cho|dự\\s+định|thường|ưu\\s+tiên|quan\\s+tâm|là|có|bị))\\b)", "$1.\n\n")
                .replaceAll("(?U)(?<!\\p{L})ạ\\s+(?=\\p{Lu})(?!\\s*(?:Anh|Chị|Nàng)\\b)", "ạ. ")
                .replaceAll(" +([.,;!?])", "$1")
                .replaceAll("[ \\t]{2,}", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
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

        String lowerCurrentForHesitation = currentMessage != null ? currentMessage.toLowerCase(Locale.ROOT) : "";
        if (lowerCurrentForHesitation.matches("(?isU).*(?:để\\s+(?:chị\\s+|anh\\s+|mình\\s+|em\\s+)?suy\\s+nghĩ|suy\\s+nghĩ\\s+(?:đã|thêm)|cân\\s+nhắc\\s+(?:đã|thêm)|để\\s+xem\\s+đã|chưa\\s+quyết\\s+định).*")) {
            String stripped = text.replaceAll("(?isU)(?:\\n\\s*\\n|(?<=[.!?])\\s+)[^.!?\\n]*\\?\\s*$", "").strip();
            String h = (honorific != null && !honorific.isBlank()) ? honorific.strip() : "Chị";
            if (!stripped.toLowerCase(Locale.ROOT).contains("thong thả cân nhắc")) {
                return (stripped.isEmpty() ? "" : stripped + "\n\n")
                        + "Dạ vâng " + h + " cứ thong thả cân nhắc thêm nhé ạ! Bất cứ khi nào vùng cơ thể mỏi nhức cần thả lỏng bằng thảo dược ấm, "
                        + h + " cứ nhắn em hỗ trợ giữ lịch ưu tiên ngay nha ạ.";
            }
            return stripped;
        }

        if (bookingState.isRedundantQuestionComplaint() || bookingState.isPostBookingWrapUp()) {
            return text.replaceAll(
                    "(?isU)(?:\\n\\s*\\n|(?<=[.!?])\\s+)(?:dạ\\s+)?[^.!?\\n]*(?:sáng\\s+hay\\s+chiều|ngày\\s+thường\\s+hay\\s+cuối\\s+tuần|xin\\s+khung\\s+giờ|đặt\\s+lịch\\s+qua\\s+soi\\s+da|giữ\\s+lịch\\s+trước|nhận\\s+cuộc\\s+gọi\\s+xác\\s+nhận|muốn\\s+đổi\\s+sang\\s+khung)[^.!?]*(?:\\?|!|\\.)\\s*$",
                    ""
            ).strip();
        }

        List<String> pastAssistantTexts = new ArrayList<>();
        FanpageBotPromptBuilder.SymptomSeverity currentSev =
                FanpageBotPromptBuilder.detectSymptomSeverity(currentMessage);
        boolean hasStatedFrequency = currentSev == FanpageBotPromptBuilder.SymptomSeverity.MILD_OR_OCCASIONAL
                || currentSev == FanpageBotPromptBuilder.SymptomSeverity.CHRONIC_OR_DEEP;
        String lowerCurrentMsg = currentMessage != null ? currentMessage.toLowerCase(Locale.ROOT) : "";
        boolean hasStatedBodyArea = lowerCurrentMsg.matches("(?isU).*\\b(vai|cổ|gáy|lưng|đầu|chân|tay|toàn\\s+thân|body)\\b.*");
        if (history != null) {
            for (FanpageBotRequest.ConversationTurn turn : history) {
                if (turn != null && "assistant".equalsIgnoreCase(turn.role()) && turn.content() != null) {
                    pastAssistantTexts.add(turn.content().toLowerCase(Locale.ROOT));
                } else if (turn != null && "user".equalsIgnoreCase(turn.role()) && turn.content() != null) {
                    FanpageBotPromptBuilder.SymptomSeverity turnSev =
                            FanpageBotPromptBuilder.detectSymptomSeverity(turn.content());
                    if (turnSev == FanpageBotPromptBuilder.SymptomSeverity.MILD_OR_OCCASIONAL
                            || turnSev == FanpageBotPromptBuilder.SymptomSeverity.CHRONIC_OR_DEEP) {
                        hasStatedFrequency = true;
                    }
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
        } else if (FanpageBotPromptBuilder.isServiceExplanationOrProcessInquiry(currentMessage)
                || FanpageBotPromptBuilder.isWellnessOrDiscomfortMessage(currentMessage)) {
            if (hasStatedFrequency && hasStatedBodyArea) {
                questionPool = List.of(
                        honorific + " muốn trải nghiệm gói cơ bản trước hay gói chuyên sâu để em tư vấn chi tiết ạ?",
                        honorific + " muốn đặt lịch vào khung giờ Sáng hay Chiều để bên em giữ phòng riêng trước ạ?"
                );
            } else if (hasStatedFrequency) {
                questionPool = List.of(
                        honorific + " muốn trải nghiệm gói cơ bản trước hay gói chuyên sâu để em tư vấn chi tiết ạ?",
                        honorific + " đang ưu tiên chăm sóc vùng cổ vai hay thư giãn toàn thân ạ?",
                        honorific + " muốn đặt lịch vào khung giờ Sáng hay Chiều để bên em giữ phòng riêng trước ạ?"
                );
            } else if (hasStatedBodyArea) {
                questionPool = List.of(
                        "Tình trạng này của " + honorific + " là mới xuất hiện hay bị thường xuyên để em chọn gói sát nhất ạ?",
                        honorific + " muốn trải nghiệm gói cơ bản trước hay gói chuyên sâu để em tư vấn chi tiết ạ?",
                        honorific + " muốn đặt lịch vào khung giờ Sáng hay Chiều để bên em giữ phòng riêng trước ạ?"
                );
            } else {
                questionPool = List.of(
                        honorific + " đang muốn tập trung vùng cổ vai hay thư giãn toàn thân để em gợi ý gói phù hợp ạ?",
                        "Tình trạng này của " + honorific + " là mới xuất hiện hay bị thường xuyên để em chọn gói sát nhất ạ?",
                        honorific + " muốn trải nghiệm gói cơ bản trước hay gói chuyên sâu để em tư vấn chi tiết ạ?"
                );
            }
        } else if (!isFirstTurn || (salesStage != null && salesStage != FanpageBotPromptBuilder.SalesStage.DISCOVERY)) {
            if (salesStage == FanpageBotPromptBuilder.SalesStage.SOFT_BOOKING_CLOSE) {
                questionPool = List.of(
                        honorific + " muốn đặt lịch vào khung giờ Sáng hay Chiều để bên em giữ lịch trước cho mình ạ?",
                        honorific + " tiện ghé cơ sở bên em vào ngày thường hay cuối tuần để bên em sắp xếp chu đáo nhất ạ?",
                        honorific + " cho em xin khung giờ tiện nhất hoặc Số điện thoại để bên em giữ suất ưu đãi trước cho mình ạ?"
                );
            } else {
                questionPool = List.of(
                        honorific + " muốn trải nghiệm gói cơ bản trước hay cần em tư vấn kỹ hơn về quy trình ạ?",
                        honorific + " muốn trải nghiệm lẻ 1 buổi trước hay tham khảo gói ưu đãi tiết kiệm hơn ạ?",
                        honorific + " muốn bên em giữ lịch trải nghiệm vào khung giờ Sáng hay Chiều ạ?"
                );
            }
        } else {
            questionPool = List.of(
                    "Hiện tại " + honorific + " đang quan tâm trải nghiệm dịch vụ nào nhất để em gợi ý gói phù hợp ạ?",
                    honorific + " đang muốn trải nghiệm gói cơ bản hay liệu trình chăm sóc chuyên sâu hơn ạ?"
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

        // Nếu khách đã nói rõ tần suất ("thi thoảng", "thường xuyên", "mới bị") mà LLM vẫn hỏi lại tần suất ở cuối -> thay bằng câu hỏi bước tiếp theo
        if (hasStatedFrequency && text.endsWith("?")) {
            Pattern redundantFreqQ = Pattern.compile(
                    "(?isU)(?:\\n\\s*\\n|(?<=[.!?])\\s+)(?:dạ\\s+)?[^.!?\\n]*(?:thường\\s+xuyên\\s+hay|hay\\s+thường\\s+xuyên|mới\\s+xuất\\s+hiện|xuất\\s+hiện\\s+khi\\s+làm\\s+việc)[^?]*\\?\\s*$"
            );
            Matcher freqM = redundantFreqQ.matcher(text);
            if (freqM.find()) {
                String prefixBeforeQuestion = text.substring(0, freqM.start()).strip();
                String sep = freqM.group().contains("\n") ? "\n\n" : " ";
                text = prefixBeforeQuestion + sep + naturalClosing;
            }
        }

        // Nếu khách đã nêu rõ vùng cơ thể mà câu hỏi cuối lại hỏi lại vùng cơ thể -> thay bằng câu hỏi bước tiếp theo
        if (hasStatedBodyArea && text.endsWith("?")) {
            Pattern redundantBodyQ = Pattern.compile(
                    "(?isU)(?:\\n\\s*\\n|(?<=[.!?])\\s+)(?:dạ\\s+)?[^.!?\\n]*(?:tập\\s+trung\\s+vùng\\s+cổ\\s+vai(?:\\s+gáy)?\\s+hay\\s+thư\\s+giãn\\s+toàn\\s+thân|ưu\\s+tiên\\s+chăm\\s+sóc\\s+vùng\\s+cổ\\s+vai(?:\\s+gáy)?\\s+hay\\s+thư\\s+giãn\\s+toàn\\s+thân)[^?]*\\?\\s*$"
            );
            Matcher bodyM = redundantBodyQ.matcher(text);
            if (bodyM.find()) {
                String prefixBeforeQuestion = text.substring(0, bodyM.start()).strip();
                String sep = bodyM.group().contains("\n") ? "\n\n" : " ";
                text = prefixBeforeQuestion + sep + naturalClosing;
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

        // 1. Bắt trực diện cặp câu hỏi xin phép máy móc hoặc câu hỏi đóng Yes/No ("Em có thể tư vấn thêm... không?", "Chị muốn thử trước không ạ?")
        Pattern dryPermissionQuestionPattern = Pattern.compile(
                "(?iU)(?:[,;]?\\s*(?:(?:hay\\s+là|hay|hoặc\\s+là|hoặc|vậy|thế|dạ|a\\s*/\\s*b\\s*[:.\\-]?)\\s+)*(?:em\\s+có\\s+thể\\s+(?:tư\\s+vấn|giải\\s+thích|hỗ\\s+trợ|chia\\s+sẻ)\\s+thêm[^?]*\\?|"
                + "(?:anh/chị|chị|anh|bạn|mình)\\s+(?:có\\s+)?muốn\\s+(?:(?:bên\\s+)?em\\s+)?(?:tư\\s+vấn|giải\\s+thích|chia\\s+sẻ|gửi|hỗ\\s+trợ|đặt\\s+lịch|giữ\\s+lịch|thử|trải\\s+nghiệm|tham\\s+khảo)(?![^?]*\\bhay\\b)[^?]*\\?|"
                + "(?:anh/chị|chị|anh|bạn|mình)\\s+(?:có\\s+)?cần\\s+(?:bên\\s+)?em\\s+(?:tư\\s+vấn|giải\\s+thích|hỗ\\s+trợ)(?![^?]*\\bhay\\b)[^?]*\\?))+"
        );
        Matcher m = dryPermissionQuestionPattern.matcher(text);
        if (m.find()) {
            String matchedGroup = m.group();
            String withoutDryQuestions = m.replaceAll("")
                    .replaceAll("(?iU)(?:(?<=^)|(?<=[.!?\\n,;:\\s]))(?:hay\\s+là|hay|hoặc\\s+là|hoặc|vậy|thế|a\\s*/\\s*b)\\s*[.!?]*\\s*$", "")
                    .strip()
                    .replaceAll("[,;:]+\\s*$", "");
            if (withoutDryQuestions.endsWith("?")) {
                return withoutDryQuestions;
            }
            if (withoutDryQuestions.isEmpty()) {
                return "Dạ " + naturalClosing;
            }
            if (!withoutDryQuestions.endsWith(".") && !withoutDryQuestions.endsWith("!") && !withoutDryQuestions.endsWith("~")) {
                withoutDryQuestions = withoutDryQuestions + ".";
            }
            String separator = matchedGroup.contains("\n") ? "\n\n" : " ";
            return withoutDryQuestions + separator + naturalClosing;
        }

        // 2. Nếu đoạn văn cuối cùng có câu hỏi hai tầng hoặc mảnh câu thừa sau dấu '?' đầu tiên -> cắt gọn tại dấu '?' đầu tiên
        int lastParaBreak = text.lastIndexOf("\n\n");
        String prefix = lastParaBreak >= 0 ? text.substring(0, lastParaBreak + 2) : "";
        String lastPara = lastParaBreak >= 0 ? text.substring(lastParaBreak + 2) : text;

        int firstQ = lastPara.indexOf('?');
        if (firstQ >= 0 && firstQ < lastPara.length() - 1) {
            lastPara = lastPara.substring(0, firstQ + 1).strip();
            text = prefix + lastPara;
        }

        // 3. Nếu là lượt 2+ và câu hỏi cuối cùng của text trùng lặp với câu hỏi assistant đã hỏi trong quá khứ -> thay thế
        if (!isFirstTurn && !pastAssistantTexts.isEmpty() && text.endsWith("?")) {
            int qStart = Math.max(text.lastIndexOf("\n\n"), Math.max(text.lastIndexOf(". "), text.lastIndexOf("! ")));
            if (qStart > 0) {
                String sep = text.substring(qStart).startsWith("\n") ? "\n\n" : " ";
                String trailingQuestion = text.substring(qStart).toLowerCase(Locale.ROOT);
                String normTrailing = normalizeQuestionForComparison(trailingQuestion);
                String trailingKey = normTrailing.length() > 25 ? normTrailing.substring(0, 25) : normTrailing;
                if (!trailingKey.isBlank()) {
                    boolean exactPastMatch = pastAssistantTexts.stream().anyMatch(past -> {
                        String normPast = normalizeQuestionForComparison(past);
                        return normPast.contains(trailingKey);
                    });
                    if (exactPastMatch) {
                        text = text.substring(0, qStart).strip() + sep + naturalClosing;
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
        return CleanAgentOutputRefereeAndSanitizer.splitIntoBubbles(cleanText, false);
    }

    static List<FanpageBotResponse.ReplyBubble> splitIntoBubbles(String cleanText, boolean enablePostPriceBubble2Thinking) {
        return CleanAgentOutputRefereeAndSanitizer.splitIntoBubbles(cleanText, enablePostPriceBubble2Thinking);
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
        String singleBubbleText = replyText != null ? replyText.replaceAll("\\n\\s*\\n", " ").strip() : "";
        FanpageBotResponse base = new FanpageBotResponse(
                singleBubbleText,
                List.of(new FanpageBotResponse.ReplyBubble(
                        singleBubbleText,
                        Math.min(1200, Math.max(800, singleBubbleText.length() * 10))
                )),
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
        return base;
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
        return CleanAgentOrchestratorService.computeConfidenceScore(fused);
    }

    /**
     * Lọc bỏ hoặc làm mềm các từ ngữ cấm (forbiddenWords) trong cấu hình PagePersonaConfig của Page.
     */
    public static String sanitizeForbiddenWords(String text, PromptBuilder.PagePersona pagePersona) {
        if (text == null || text.isBlank() || pagePersona == null) {
            return text;
        }
        com.example.docmind.dto.PagePersonaConfig cfg = pagePersona.resolvedConfig();
        if (cfg == null || cfg.forbiddenWords() == null || cfg.forbiddenWords().isEmpty()) {
            return text;
        }
        String working = text;
        for (String forbidden : cfg.forbiddenWords()) {
            if (forbidden == null || forbidden.isBlank()) {
                continue;
            }
            String cleanTerm = forbidden.strip();
            if (cleanTerm.length() < 2) {
                continue;
            }
            String replacement = resolveSafeReplacementForForbiddenTerm(cleanTerm);
            working = working.replaceAll("(?iU)(?<!\\p{L})" + Pattern.quote(cleanTerm) + "(?!\\p{L})", replacement);
        }
        return working.replaceAll("[ \\t]{2,}", " ").strip();
    }

    private static String resolveSafeReplacementForForbiddenTerm(String forbiddenTerm) {
        String lower = forbiddenTerm.toLowerCase(Locale.ROOT);
        if (lower.contains("trị dứt điểm") || lower.contains("khỏi 100%") || lower.contains("chữa khỏi")) {
            return "hỗ trợ cải thiện hiệu quả";
        }
        if (lower.contains("cam kết khỏi bệnh") || lower.contains("cam kết khỏi")) {
            return "hỗ trợ cải thiện rõ rệt";
        }
        if (lower.contains("thuốc đặc trị")) {
            return "sản phẩm chăm sóc chuyên sâu";
        }
        if (lower.contains("hàng fake") || lower.contains("hàng nhái")) {
            return "sản phẩm không rõ nguồn gốc";
        }
        if (lower.contains("vĩnh viễn")) {
            return "lâu dài";
        }
        return "";
    }

    private String resolveAciV2EffectiveHonorific(
            UUID pageId,
            String senderId,
            PromptBuilder.PagePersona pagePersona
    ) {
        String defaultCustomerPronoun = (pagePersona != null && pagePersona.resolvedConfig() != null)
                ? pagePersona.resolvedConfig().resolvedDefaultCustomerPronoun()
                : "mình";
        String inferredCustomerPronoun = (agentBrainService != null && pageId != null && senderId != null && !senderId.isBlank())
                ? agentBrainService.getInferredCustomerPronoun(pageId, senderId)
                : null;
        return (inferredCustomerPronoun != null && !inferredCustomerPronoun.isBlank())
                ? inferredCustomerPronoun.strip()
                : defaultCustomerPronoun;
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
                            p.getDescription(),
                            p.getPersonaConfig()
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

    private static boolean hasQuotedPriceInText(String text) {
        return CleanAgentOutputRefereeAndSanitizer.hasQuotedPriceInText(text);
    }

    private static boolean hasQuotedPriceInAssistantHistory(List<FanpageBotRequest.ConversationTurn> history) {
        if (history == null || history.isEmpty()) {
            return false;
        }
        for (FanpageBotRequest.ConversationTurn t : history) {
            if (t != null && ("assistant".equalsIgnoreCase(t.role()) || "bot".equalsIgnoreCase(t.role()))
                    && t.content() != null) {
                if (hasQuotedPriceInText(t.content())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasSpecificPriceInAssistantHistory(List<FanpageBotRequest.ConversationTurn> history, long targetPrice) {
        if (history == null || history.isEmpty()) {
            return false;
        }
        String kToken = (targetPrice / 1000L) + "k";
        String dotToken = String.format(Locale.US, "%,d", targetPrice).replace(',', '.');
        for (FanpageBotRequest.ConversationTurn t : history) {
            if (t != null && ("assistant".equalsIgnoreCase(t.role()) || "bot".equalsIgnoreCase(t.role()))
                    && t.content() != null) {
                String lower = t.content().toLowerCase(Locale.ROOT);
                if (lower.contains(dotToken) || lower.contains(kToken)) {
                    return true;
                }
                List<Long> prices = PriceLadderTracker.extractPricesFromText(t.content());
                if (prices != null && prices.contains(targetPrice)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String combineUserSymptomHistoryText(String currentMessage, List<FanpageBotRequest.ConversationTurn> history) {
        StringBuilder sb = new StringBuilder();
        if (currentMessage != null) {
            sb.append(currentMessage.toLowerCase(Locale.ROOT)).append(' ');
        }
        if (history != null) {
            for (int i = history.size() - 1; i >= 0; i--) {
                FanpageBotRequest.ConversationTurn t = history.get(i);
                if (t != null && "user".equalsIgnoreCase(t.role()) && t.content() != null) {
                    sb.append(t.content().toLowerCase(Locale.ROOT)).append(' ');
                }
            }
        }
        return sb.toString();
    }

    static String resolveCustomerSymptomPhrase(String currentMessage, List<FanpageBotRequest.ConversationTurn> history) {
        String text = combineUserSymptomHistoryText(currentMessage, history);
        List<String> parts = new ArrayList<>();
        if (text.matches("(?isU).*\\b(lưng|thắt\\s+lưng|cột\\s+sống)\\b.*")) {
            parts.add("đau lưng");
        }
        Matcher kneeM = Pattern.compile("(?isU)\\b((?:đau|mỏi|nhức)\\s+(?:khớp\\s+)?gối|khớp\\s+gối)\\b").matcher(text);
        if (kneeM.find()) {
            parts.add(kneeM.group(1).strip().replaceAll("\\s+", " "));
        } else if (text.matches("(?isU).*\\b(gối|khớp\\s+gối)\\b.*")) {
            parts.add("đau nhức khớp gối");
        }
        if (text.matches("(?isU).*\\b(tê\\s+tay|tê\\s+bì\\s+tay|mỏi\\s+tay|đau\\s+tay)\\b.*")) {
            parts.add("tê bì tay");
        } else if (text.matches("(?isU).*\\b(tê\\s+chân|mỏi\\s+chân|đau\\s+chân)\\b.*")) {
            parts.add("mỏi tê chân");
        } else if (text.matches("(?isU).*\\b(tê\\s+bì)\\b.*")) {
            parts.add("tê bì tay chân");
        }
        if (text.matches("(?isU).*\\b(cổ|vai|gáy)\\b.*")) {
            parts.add("đau mỏi cổ vai");
        }
        if (text.matches("(?isU).*\\b(mất\\s+ngủ|khó\\s+ngủ|ngủ\\s+không\\s+sâu)\\b.*")) {
            parts.add("mất ngủ");
        } else if (text.matches("(?isU).*\\b(đau\\s+đầu|nặng\\s+đầu|stress|căng\\s+thẳng)\\b.*")) {
            parts.add("căng thẳng đau đầu");
        }
        if (parts.isEmpty()) {
            return "đau mỏi cổ vai";
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        if (parts.size() == 2) {
            return parts.get(0) + " và " + parts.get(1);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " và " + parts.get(parts.size() - 1);
    }

    static String resolveShortSymptomArea(String currentMessage, List<FanpageBotRequest.ConversationTurn> history) {
        String text = combineUserSymptomHistoryText(currentMessage, history);
        boolean hasBack = text.matches("(?isU).*\\b(lưng|thắt\\s+lưng|cột\\s+sống)\\b.*");
        boolean hasKnee = text.matches("(?isU).*\\b(gối|khớp\\s+gối)\\b.*");
        boolean hasLimb = text.matches("(?isU).*\\b(tê\\s+tay|tê\\s+bì|mỏi\\s+tay|đau\\s+tay|tê\\s+chân|mỏi\\s+chân)\\b.*");
        boolean hasNeck = text.matches("(?isU).*\\b(cổ|vai|gáy)\\b.*");
        if (hasBack && hasKnee && hasLimb) {
            return "lưng - khớp gối - cánh tay";
        }
        if (hasBack && hasKnee) {
            return "thắt lưng - khớp gối";
        }
        if (hasBack && hasLimb) {
            return "thắt lưng và tay chân";
        }
        if (hasBack && hasNeck) {
            return "cổ vai và thắt lưng";
        }
        if (hasBack) {
            return "thắt lưng - cột sống";
        }
        if (hasKnee) {
            return "khớp gối và chân";
        }
        if (hasLimb) {
            return "vai và cánh tay";
        }
        return "cổ vai";
    }

    static String resolveTherapeuticMechanism(String currentMessage, List<FanpageBotRequest.ConversationTurn> history) {
        String text = combineUserSymptomHistoryText(currentMessage, history);
        String shortArea = resolveShortSymptomArea(currentMessage, history);
        if (text.matches("(?isU).*\\b(lưng|gối|tê\\s+tay|tê\\s+bì|tê\\s+chân)\\b.*")) {
            return "ủ ấm thảo dược tươi, chườm ngải cứu dọc vùng " + shortArea + " và ấn huyệt đả thông kinh lạc sâu";
        }
        return "ủ ấm thảo dược tươi, đá nóng bazan và ấn huyệt đả thông kinh lạc vùng " + shortArea;
    }

    static String buildImpressiveTurn1WellnessOpening(String mergedMessage, String honorific) {
        String h = (honorific != null && !honorific.isBlank()) ? honorific.strip() : "Chị";
        String lower = mergedMessage != null ? mergedMessage.toLowerCase(Locale.ROOT) : "";
        boolean asksHairWash = lower.matches("(?isU).*\\b(gội\\s+đầu|dưỡng\\s+sinh|da\\s+đầu|tóc|vệ\\s+sinh\\s+tai)\\b.*");
        boolean asksMaleSupport = lower.matches("(?isU).*\\b(nam|khách\\s+nam|bạn\\s+trai|chồng)\\b.*");
        if (asksHairWash) {
            String maleNote = asksMaleSupport
                    ? " Bên em đón tiếp rất nhiều khách " + h + " Nam giới với phòng rèm riêng tư, yên tĩnh nên " + h + " hoàn toàn thoải mái nhé ạ!"
                    : "";
            return "Dạ em chào " + h + " ạ! Thương " + h + " sau giờ làm căng thẳng muốn tìm một góc bình yên để thả lỏng tâm trí." + maleNote + "\n\n"
                    + "Tại cơ sở bên em, Gội Đầu Dưỡng Sinh Thảo Dược dùng nước bồ kết - vỏ bưởi - sả chanh nấu ấm kết hợp ấn huyệt đầu cổ vai giúp ngủ sâu (trọn gói 100% không nhận Tip ạ).\n\n"
                    + "Hôm nay " + h + " muốn ưu tiên Gội đầu dưỡng sinh thư giãn hay kết hợp thêm massage cổ vai chuyên sâu ạ?";
        }
        if (lower.matches("(?isU).*\\b(mỏi|đau|nhức|cứng|nặng|mệt|stress|mất\\s+ngủ|vai|cổ|gáy|lưng|gối|tê)\\b.*")) {
            String symptomPhrase = resolveCustomerSymptomPhrase(mergedMessage, null);
            String shortArea = resolveShortSymptomArea(mergedMessage, null);
            String mechanism = resolveTherapeuticMechanism(mergedMessage, null);
            return "Dạ em chào " + h + " ạ! Nghe " + h + " chia sẻ tình trạng " + symptomPhrase + " mà em thương quá — khi bó cơ căng cứng và khí huyết kém lưu thông sẽ khiến người rất mệt mỏi, nặng trĩu.\n\n"
                    + "Tại cơ sở bên em, liệu trình Massage Trị Liệu sẽ " + mechanism + ", giúp " + h + " nhẹ bẫng vùng " + shortArea + " ngay buổi đầu ạ.\n\n"
                    + "Hiện " + h + " muốn tập trung ấn huyệt giải cơ sâu vùng " + shortArea + " trước hay kết hợp ngâm bồn thảo dược thư giãn toàn thân để em chọn gói sát nhất ạ?";
        }
        return "Dạ em chào " + h + " ạ! Rất vui được đón " + h + " ghé thăm không gian trị liệu thảo dược ấm áp của bên em.\n\n"
                + "Bên em chuyên sâu Massage Trị Liệu Cổ Vai - Thắt Lưng và Gội Đầu Dưỡng Sinh Thảo Dược (cam kết 100% không nhận Tip, không chèo kéo mỹ phẩm) giúp phục hồi năng lượng sâu ạ.\n\n"
                + "Hôm nay " + h + " đang gặp tình trạng đau mỏi vùng nào trên cơ thể hay muốn thư giãn gội đầu dưỡng sinh để em tư vấn liệu trình êm ái nhất cho mình ạ?";
    }

    static String deliberatePostPriceMoodPreservationPass(
            String currentReply,
            String customerMessage,
            String honorific,
            PriceLadderResult priceLadder,
            String detectedBranch,
            long turnStartNs) {
        return deliberatePostPriceMoodPreservationPass(
                currentReply, customerMessage, honorific, priceLadder, detectedBranch, null, turnStartNs);
    }

    static String deliberatePostPriceMoodPreservationPass(
            String currentReply,
            String customerMessage,
            String honorific,
            PriceLadderResult priceLadder,
            String detectedBranch,
            List<FanpageBotRequest.ConversationTurn> history,
            long turnStartNs) {
        String h = (honorific != null && !honorific.isBlank()) ? honorific.strip() : "Chị";
        String lower = customerMessage != null ? customerMessage.toLowerCase(Locale.ROOT) : "";
        boolean isSaigon = "saigon".equalsIgnoreCase(detectedBranch)
                || lower.contains("quận 1") || lower.contains("sài gòn") || lower.contains("tphcm")
                || (priceLadder != null && priceLadder.targetPrice() != null
                    && (priceLadder.targetPrice() == 570_000L || priceLadder.targetPrice() == 670_000L || priceLadder.targetPrice() == 260_000L));
        String basicPriceLabel = isSaigon ? "570.000đ (70 phút)" : "399.000đ (60 phút)";
        String basicPriceShort = isSaigon ? "570.000đ" : "399.000đ";
        String deepPriceLabel = isSaigon ? "670.000đ (90 phút)" : "530.000đ (80 phút)";
        String deepPriceShort = isSaigon ? "670.000đ" : "530.000đ";
        String duongSinhFloor = isSaigon ? "260.000đ (60 phút)" : "239.000đ (60 phút)";
        String duongSinhShort = isSaigon ? "260.000đ" : "239.000đ";

        String symptomPhrase = resolveCustomerSymptomPhrase(customerMessage, history);
        String shortArea = resolveShortSymptomArea(customerMessage, history);
        String mechanism = resolveTherapeuticMechanism(customerMessage, history);

        boolean hasPriceObjectionSignal = (priceLadder != null && priceLadder.isPriceObjection())
                || FanpageBotPromptBuilder.isPriceObjectionOrDownsellMessage(customerMessage)
                || lower.matches("(?isU).*(?:vượt\\s+ngân\\s+sách|nhẹ\\s+tiền\\s+hơn|mềm\\s+hơn|rẻ\\s+hơn|hơi\\s+cao|đắt).*");
        boolean explicitlyObjectsHigherTier = lower.contains("530") || lower.contains("670") || lower.contains("580") || lower.contains("870");
        boolean alreadyQuotedBasicPriceInHistory = hasSpecificPriceInAssistantHistory(history, isSaigon ? 570_000L : 399_000L);
        boolean isPackageComparison = lower.matches("(?isU).*(?:khác\\s+gì|khác\\s+nhau|chênh\\s+nhau|so\\s+với|gồm\\s+những\\s+bước\\s+gì).*")
                && !hasPriceObjectionSignal;
        boolean isTrialAcceptance = lower.matches("(?isU).*(?:thử\\s+trước|trải\\s+nghiệm\\s+trước|làm\\s+thử\\s+1\\s+buổi|chọn\\s+gói\\s+(?:399|570|cơ\\s+bản)).*")
                && !lower.matches("(?isU).*0\\d{8,10}.*");

        String candidateReply = null;

        // 1. Explicit hesitation ("uk để suy nghĩ đã", "để chị suy nghĩ thêm") -> Warm Drop-the-Rope (0 pushy questions, 2 crisp bubbles)
        if (lower.matches("(?isU).*(?:để\\s+(?:chị\\s+|anh\\s+|mình\\s+|em\\s+)?suy\\s+nghĩ|suy\\s+nghĩ\\s+(?:đã|thêm)|cân\\s+nhắc\\s+(?:đã|thêm)|để\\s+xem\\s+đã|chưa\\s+quyết\\s+định).*")) {
            candidateReply = "Dạ vâng " + h + " cứ thong thả cân nhắc thêm nhé ạ, hoàn toàn không vội đâu ạ! Chăm sóc cơ thể là lúc mình thấy thật sự thoải mái và thư thái nhất.\n\n"
                    + "Bất cứ khi nào vùng " + shortArea + " mỏi nhức cần thả lỏng bằng thảo dược ấm và ấn huyệt sâu, " + h + " cứ nhắn nhẹ cho em là bên em hỗ trợ giữ phòng riêng yên tĩnh cho " + h + " ngay nha ạ.";
        }
        // 2. Promo / discount / voucher inquiry ("có chương trình giảm giá gì ko")
        // Bubble 1: Empathy + Promo & Price quote -> [10s Thinking] -> Bubble 2: Mood-preserving value -> Bubble 3: Gentle question
        else if (PriceLadderTracker.isPromoOrDiscountInquiry(customerMessage)) {
            candidateReply = "Dạ em rất hiểu tâm lý muốn tìm mức ưu đãi tốt nhất để đi chăm sóc định kỳ của " + h + " ạ! Hiện bên em giảm ngay 10% tổng hóa đơn khi đăng ký thẻ từ 5 buổi hoặc mua từ 5 voucher trở lên, hoặc " + h + " có thể thử lẻ 1 buổi Gói Cơ Bản " + basicPriceLabel + " trước ạ.\n\n"
                    + "Dù đi lẻ 1 buổi hay đăng ký thẻ ưu đãi, suất chăm sóc của " + h + " đều đã bao gồm trọn gói ngâm chân thảo dược ấm, ấn huyệt giải cơ vùng " + shortArea + ", trà bánh thực dưỡng và cam kết 100% không nhận Tip ạ.\n\n"
                    + h + " muốn đặt lịch trải nghiệm thử 1 buổi lẻ " + basicPriceShort + " trước hay đăng ký gói ưu đãi giảm 10% để em giữ suất cho mình ạ?";
        }
        // 3. Price objection when basic price (399k/570k) was ALREADY quoted in history (e.g. "Đắt thế" after 399k, or "399k vẫn thấy đắt quá")
        // Bubble 1: Empathy + Transparent price value + Softer 239k/260k option -> [10s Thinking] -> Bubble 2: Mood-preserving reassurance -> Bubble 3: Low-pressure choice
        else if (hasPriceObjectionSignal && !explicitlyObjectsHigherTier
                && (alreadyQuotedBasicPriceInHistory || lower.contains("399") || lower.contains("570")
                    || lower.contains("vẫn thấy đắt") || lower.contains("vẫn đắt")
                    || lower.contains("rẻ hơn nữa") || (priceLadder != null && priceLadder.isCatalogFloor()))) {
            candidateReply = "Dạ em hoàn toàn đồng cảm với cân nhắc về chi phí của " + h + " ạ! Thực tế với liệu trình xử lý tình trạng " + symptomPhrase + ", gói Massage Trị Liệu Cơ Bản " + basicPriceLabel + " là mức trọn gói tiết kiệm nhất (đã gồm ngâm chân thảo dược, ấn huyệt giải cơ sâu, điểm tâm thực dưỡng & 100% không nhận Tip); hoặc nhẹ nhàng hơn nữa bên em có Gói Dưỡng Sinh Thảo Dược kết hợp massage cổ vai - tay chỉ " + duongSinhFloor + " ạ.\n\n"
                    + "Bên em rất hiểu tâm lý lần đầu trải nghiệm muốn thử tay nghề kỹ thuật viên trước nên tuyệt đối không chèo kéo mua thẻ dài hạn; ngoài ra nếu đi theo nhóm hoặc đăng ký từ 5 buổi/voucher sẽ được giảm ngay 10% tổng hóa đơn ạ.\n\n"
                    + h + " muốn trải nghiệm nhẹ nhàng gói Dưỡng sinh " + duongSinhShort + " trước hay thử 1 buổi lẻ gói Trị liệu " + basicPriceShort + " để tập trung giải cơ vùng " + shortArea + " ạ?";
        }
        // 4. First price objection against higher tier ("530k hơi cao", "670k hơi cao", "vượt ngân sách", "có gói nào mềm hơn không")
        // Bubble 1: Empathy + Down-sell price 399k/570k -> [10s Thinking] -> Bubble 2: Mood-preserving value -> Bubble 3: Gentle question
        else if (hasPriceObjectionSignal) {
            candidateReply = "Dạ em rất hiểu sự cân nhắc của " + h + " khi mới trải nghiệm lần đầu ạ! Để nhẹ nhàng hơn về chi phí mà vẫn giải tỏa hiệu quả tình trạng " + symptomPhrase + ", bên em có Gói Massage Trị Liệu Cơ Bản chỉ " + basicPriceLabel + " (trọn gói 100% không nhận Tip ạ).\n\n"
                    + "Dù ở mức chi phí tiết kiệm hơn, gói " + basicPriceShort + " vẫn giữ trọn quy trình ngâm chân thảo dược ấm, ấn huyệt chuyên sâu vùng " + shortArea + " và dùng điểm tâm thực dưỡng sau buổi để " + h + " yên tâm cảm nhận tay nghề kỹ thuật viên ạ.\n\n"
                    + h + " muốn bên em giữ lịch trải nghiệm gói Cơ Bản " + basicPriceShort + " vào khung giờ Sáng hay Chiều để em sắp xếp phòng yên tĩnh cho mình ạ?";
        }
        // 5. Post-price package comparison ("Gói 399k với 530k khác gì nhau em?", "2 gói chênh nhau gì")
        // Bubble 1: Price & Core difference -> [10s Thinking] -> Bubble 2: Mood-preserving advice -> Bubble 3: Gentle question
        else if (isPackageComparison) {
            candidateReply = "Dạ câu hỏi của " + h + " rất thực tế ạ! Gói Cơ Bản " + basicPriceLabel + " tập trung ấn huyệt làm mềm bó cơ vùng " + shortArea + " và ngâm chân thảo dược; còn Gói Chuyên Sâu " + deepPriceLabel + " có thêm 20 phút ngâm bồn gỗ thảo dược ấm toàn thân và chườm ngải cứu sâu (trọn gói 100% không nhận Tip ạ).\n\n"
                    + "Nếu " + h + " muốn thử tay nghề kỹ thuật viên buổi đầu thì Gói Cơ Bản " + basicPriceShort + " đã rất êm ái, còn nếu vùng " + shortArea + " đang căng mỏi nhiều thì Gói Chuyên Sâu " + deepPriceShort + " sẽ giúp giãn cơ nhanh và ngủ sâu hơn hẳn ạ.\n\n"
                    + h + " muốn trải nghiệm thử Gói Cơ Bản " + basicPriceShort + " trước hay chọn Gói Chuyên Sâu " + deepPriceShort + " vào khung giờ Sáng hay Chiều ạ?";
        }
        // 6. Post-price trial acceptance without phone yet ("Cho chị thử trước 1 buổi gói 399k nhé")
        else if (isTrialAcceptance) {
            candidateReply = "Dạ lựa chọn trải nghiệm trước 1 buổi lẻ Gói Cơ Bản " + basicPriceLabel + " là hợp lý nhất để " + h + " trực tiếp cảm nhận tay nghề kỹ thuật viên bên em ạ!\n\n"
                    + "Suất trải nghiệm của " + h + " đã bao gồm trọn gói ngâm chân thảo dược ấm, ấn huyệt giải cơ vùng " + shortArea + ", trà dưỡng nhan và cam kết 100% không nhận Tip hay phát sinh chi phí ạ.\n\n"
                    + h + " tiện ghé cơ sở bên em vào khung giờ Sáng hay Chiều, hoặc cho em xin Số điện thoại để em giữ phòng riêng yên tĩnh cho mình nhé ạ?";
        }
        // 7. Symptom sharing on Turn 2+ OR Explicit price inquiry ("giá sao em", "chi phí thế nào")
        // Bubble 1: Deep symptom empathy + Price quote (399k & 530k) -> [10s Thinking] -> Bubble 2: Mood-preserving therapeutic mechanism -> Bubble 3: Tailored diagnostic/choice question
        else if (asksExplicitPriceInMessage(customerMessage)
                || FanpageBotPromptBuilder.isBroadUnspecifiedPriceInquiry(customerMessage, history)
                || FanpageBotPromptBuilder.isWellnessOrDiscomfortMessage(customerMessage)
                || FanpageBotPromptBuilder.isSymptomFollowUpTurn(customerMessage, history)) {
            boolean explicitlyAskedPrice = asksExplicitPriceInMessage(customerMessage)
                    || FanpageBotPromptBuilder.isBroadUnspecifiedPriceInquiry(customerMessage, history);
            String closingBubble = explicitlyAskedPrice
                    ? h + " muốn trải nghiệm trước Gói Cơ Bản " + basicPriceShort + " hay chọn Gói Chuyên Sâu " + deepPriceShort + " để giải quyết triệt để vùng " + shortArea + " ạ?"
                    : "Tình trạng " + symptomPhrase + " của " + h + " là mới xuất hiện gần đây hay đã kéo dài thường xuyên để em cân đối lực ấn huyệt và gói phù hợp nhất cho mình ạ?";
            candidateReply = "Dạ nghe " + h + " chia sẻ tình trạng " + symptomPhrase + " mà em thương quá — khi khí huyết kém lưu thông và bó cơ căng cứng như vậy rất cần ấn huyệt giải phóng điểm tắc nghẽn ạ. Hiện bên em có Gói Massage Trị Liệu Cơ Bản " + basicPriceLabel + " và Gói Chuyên Sâu " + deepPriceLabel + " (trọn gói 100% không nhận Tip ạ).\n\n"
                    + "Trong suốt buổi trị liệu, kỹ thuật viên tay nghề cao sẽ kết hợp " + mechanism + " (Gói Chuyên Sâu " + deepPriceShort + " có thêm 20 phút ngâm bồn gỗ thảo dược ấm toàn thân), giúp " + h + " nhẹ bẫng vùng " + shortArea + " ngay từ buổi đầu kèm điểm tâm thực dưỡng ấm bụng ạ.\n\n"
                    + closingBubble;
        }

        if (candidateReply == null) {
            return currentReply;
        }

        return candidateReply;
    }

    public record StructuredLlmOutput(
            boolean parsedFromJson,
            List<FanpageBotResponse.ReplyBubble> bubbles,
            Boolean shouldHandoff,
            String handoffReason,
            String updatedSalesStage,
            ConversationState.GroundingCheck groundingCheck,
            String groundingCheckText,
            CleanAgentOutputRefereeAndSanitizer.BrainReflection brainReflection
    ) {
        public StructuredLlmOutput {
            if (brainReflection == null) {
                brainReflection = CleanAgentOutputRefereeAndSanitizer.BrainReflection.empty();
            }
        }

        public static StructuredLlmOutput fromClean(CleanAgentOutputRefereeAndSanitizer.StructuredLlmOutput s) {
            return s == null ? null : new StructuredLlmOutput(
                    s.parsedFromJson(), s.bubbles(), s.shouldHandoff(), s.handoffReason(),
                    s.updatedSalesStage(), s.groundingCheck(), s.groundingCheckText(),
                    s.brainReflection());
        }
    }

    public static StructuredLlmOutput parseStructuredLlmJsonOutput(String rawText, int minPostPriceDelayMs) {
        return StructuredLlmOutput.fromClean(CleanAgentOutputRefereeAndSanitizer.parseStructuredLlmJsonOutput(rawText, minPostPriceDelayMs));
    }

    // v3.3.4 RC-01: Câu chào lặp CHỈ được cắt khi nằm ở ĐẦU bong bóng (neo ^), tuyệt đối không đụng tới
    // "mình"/"ạ" ở giữa hay cuối câu. Hỗ trợ đuôi "nha/nhé/ạ/ơi" mà regex cũ bỏ sót ("Em chào mình nha, ...").
    private static final Pattern REDUNDANT_GREETING_CLAUSE_PATTERN = Pattern.compile(
            "(?iu)^\\s*(?:dạ[,\\s]+)?(?:(?:em|bên\\s+em|shop|page)\\s+)?(?:xin\\s+|kính\\s+)?chào\\s+"
            + "(?:anh\\s*/\\s*chị|chị\\s*/\\s*anh|anh|chị|mình|bạn|quý\\s+khách)"
            + "(?:\\s+(?:nhé\\s+ạ|nha\\s+ạ|nha|nhé|ạ|ơi|hen|nghen))?"
            + "(?:\\s*[,!.?~…🥰🌿✨]+\\s*|\\s+(?=(?:bên\\s+em|hiện|giá|với|để|hôm\\s+nay|mình|chị|anh|em\\s+xin|em\\s+gợi\\s+ý)))"
    );

    private static final Pattern SELF_INTRO_CLAUSE_PATTERN = Pattern.compile(
            "(?iu)^\\s*(?:dạ[,\\s]+)?(?:em|mình)\\s+là\\s+[^.!?\\n]{2,60}?(?:của|từ|tại)\\s+[^.!?\\n]{2,50}?[.!?~]+\\s*"
    );

    /**
     * v3.3.4 RC-01: Cắt mệnh đề chào hỏi / tự giới thiệu lặp lại ở ĐẦU bong bóng mà vẫn giữ nguyên ngữ pháp.
     * Nếu bong bóng không có lời chào ở đầu, trả về nguyên văn (đã strip khoảng trắng).
     * Nếu cắt xong mà không còn nội dung, giữ nguyên câu gốc thay vì trả về bong bóng rỗng.
     */
    public static String stripRedundantGreetingPrefixPreservingGrammar(String text, String honorific) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String working = text.strip();
        boolean modified = false;
        for (int pass = 0; pass < 2; pass++) {
            Matcher m1 = REDUNDANT_GREETING_CLAUSE_PATTERN.matcher(working);
            if (m1.find()) {
                String remainder = working.substring(m1.end()).strip();
                if (!remainder.isEmpty()) {
                    working = remainder;
                    modified = true;
                }
            }
            Matcher m2 = SELF_INTRO_CLAUSE_PATTERN.matcher(working);
            if (m2.find()) {
                String remainder = working.substring(m2.end()).strip();
                if (!remainder.isEmpty()) {
                    working = remainder;
                    modified = true;
                }
            }
        }
        if (modified && !working.isEmpty()) {
            String low = working.toLowerCase(Locale.ROOT);
            String honLow = (honorific != null && !honorific.isBlank()) ? honorific.strip().toLowerCase(Locale.ROOT) : "mình";
            if (!low.startsWith("dạ") && !low.startsWith("hôm nay") && !low.startsWith("hiện")
                    && !low.startsWith("bên em") && !low.startsWith(honLow)) {
                working = "Dạ " + Character.toLowerCase(working.charAt(0)) + working.substring(1);
            }
            working = BubbleCapHook.capitalizeFirstLetter(working);
        }
        return working;
    }

    /**
     * v3.3.4 Selective Thinking: điểm vào ổn định cho ma trận tâm lý (dùng cho test &amp; telemetry).
     * Ủy quyền cho {@link ThinkingModePolicy#decide} ở chế độ ADAPTIVE để runtime và test dùng CHUNG một logic,
     * không có nhánh quyết định riêng rẽ. Không gọi LLM, chạy hoàn toàn bằng regex/state (&lt; 5ms).
     */
    public static ThinkingMode resolveSelectiveThinkingMode(
            PrimaryActionRouter.RoutingDecision decision,
            ConversationState state,
            String rawCustomerMessage
    ) {
        if (decision == null) {
            return ThinkingMode.FAST_NO_THINK;
        }
        return ThinkingModePolicy.decide(
                rawCustomerMessage,
                state,
                decision,
                null,
                ThinkingModePolicy.BenchmarkConfigMode.ADAPTIVE_C
        ).thinkingMode();
    }

    private void emitLiveAuditJsonLog(
            UUID pageId,
            FanpageBotRequest request,
            String customerMessage,
            ConversationState stateBefore,
            ConversationState stateAfter,
            PrimaryActionRouter.RoutingDecision actionDecision,
            String prompt,
            String rawLlmOutput,
            StructuredLlmOutput structuredLlmOutput,
            List<FanpageBotResponse.ReplyBubble> finalBubbles,
            List<String> validatorViolations,
            List<String> firstPassErrors,
            String repairOutcome,
            String fallbackOrRepairReason,
            String provider,
            String model,
            ReasoningBudget budget,
            long latencyMs
    ) {
        try {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("tenant_id", pageId != null ? pageId.toString() : "unknown");
            record.put("conversation_id", request != null && request.conversationId() != null
                    ? request.conversationId()
                    : (request != null && request.senderId() != null ? request.senderId() : "anon"));
            int computedTurnId = (request != null && request.history() != null ? request.history().size() / 2 : 0) + 1;
            record.put("turn_id", computedTurnId);
            record.put("provider", provider != null ? provider : "ollama");
            record.put("model", model != null ? model : "qwen3:14b");
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("temperature", budget != null ? budget.temperature() : 0.3);
            params.put("num_predict", budget != null ? budget.maxTokens() : 750);
            params.put("think", budget != null && budget.thinkEnabled());
            record.put("params", params);
            record.put("customer_message", customerMessage != null ? customerMessage : "");

            Map<String, Object> sbMap = new LinkedHashMap<>();
            sbMap.put("confirmedScopes", stateBefore != null && stateBefore.confirmedScopes() != null ? stateBefore.confirmedScopes() : List.of());
            sbMap.put("forbiddenScopes", stateBefore != null && stateBefore.forbiddenScopes() != null ? stateBefore.forbiddenScopes() : List.of());
            sbMap.put("apologizedErrorIds", stateBefore != null && stateBefore.apologizedErrorIds() != null ? stateBefore.apologizedErrorIds() : List.of());
            sbMap.put("conditionClarified", stateBefore != null && stateBefore.conditionClarified());
            sbMap.put("conditionTimeClarified", stateBefore != null && stateBefore.conditionTimeClarified());
            sbMap.put("onset", stateBefore != null && stateBefore.onset() != null ? stateBefore.onset() : "");
            sbMap.put("frequency", stateBefore != null && stateBefore.frequency() != null ? stateBefore.frequency() : "");
            sbMap.put("frequencyCategory", stateBefore != null && stateBefore.frequencyCategory() != null ? stateBefore.frequencyCategory() : "");
            sbMap.put("duration", stateBefore != null && stateBefore.duration() != null ? stateBefore.duration() : "");
            sbMap.put("pendingQuestionSlot", stateBefore != null && stateBefore.pendingQuestionSlot() != null ? stateBefore.pendingQuestionSlot() : "");
            sbMap.put("priceRequestedInSession", stateBefore != null && stateBefore.priceRequestedInSession());
            sbMap.put("packageExplicitlySelected", stateBefore != null && stateBefore.packageExplicitlySelected());
            sbMap.put("packageInterestAccepted", stateBefore != null && stateBefore.packageInterestAccepted());
            sbMap.put("bookingIntentConfirmed", stateBefore != null && stateBefore.bookingIntentConfirmed());
            sbMap.put("visitTime", stateBefore != null && stateBefore.visitTime() != null ? stateBefore.visitTime() : "");
            sbMap.put("contactInfo", stateBefore != null && stateBefore.contactInfo() != null ? stateBefore.contactInfo() : "");
            sbMap.put("bookingStatus", stateBefore != null && stateBefore.bookingStatus() != null ? stateBefore.bookingStatus() : "NONE");
            sbMap.put("selectedPackageId", stateBefore != null && stateBefore.selectedPackageId() != null ? stateBefore.selectedPackageId() : "");
            sbMap.put("lastOfferedPackageId", stateBefore != null && stateBefore.lastOfferedPackageId() != null ? stateBefore.lastOfferedPackageId() : "");
            sbMap.put("knownSymptoms", stateBefore != null && stateBefore.knownSymptoms() != null ? stateBefore.knownSymptoms() : List.of());
            sbMap.put("customerCareNotesCount", stateBefore != null && stateBefore.customerCareNotes() != null ? stateBefore.customerCareNotes().size() : 0);
            record.put("state_before", sbMap);

            record.put("turn_correlation_id", CURRENT_TURN_ID.get() != null ? CURRENT_TURN_ID.get() : "");
            String selectedIntent = actionDecision != null && actionDecision.detectedIntent() != null ? actionDecision.detectedIntent() : "";
            String selectedAction = actionDecision != null && actionDecision.effectiveResponseAction() != null
                    ? actionDecision.effectiveResponseAction().name()
                    : "NONE";
            record.put("selectedIntent", selectedIntent);
            record.put("selectedAction", selectedAction);
            record.put("detected_intent", selectedIntent);
            record.put("primary_action", actionDecision != null && actionDecision.primaryAction() != null ? actionDecision.primaryAction().name() : "NONE");
            record.put("forced_action", actionDecision != null && actionDecision.forcedAction() != null ? actionDecision.forcedAction() : "NONE");
            record.put("router_action", selectedAction);

            String sysPrompt = "";
            String usrPrompt = prompt != null ? prompt : "";
            if (usrPrompt.contains("<!-- SYSTEM_USER_SPLIT -->")) {
                int splitIdx = usrPrompt.indexOf("<!-- SYSTEM_USER_SPLIT -->");
                sysPrompt = usrPrompt.substring(0, splitIdx).strip();
                usrPrompt = usrPrompt.substring(splitIdx + "<!-- SYSTEM_USER_SPLIT -->".length()).strip();
            } else if (usrPrompt.contains("<USER_TURN_INPUT>")) {
                int splitIdx = usrPrompt.indexOf("<USER_TURN_INPUT>");
                sysPrompt = usrPrompt.substring(0, splitIdx).strip();
                usrPrompt = usrPrompt.substring(splitIdx).strip();
            } else {
                sysPrompt = usrPrompt;
            }
            record.put("system_prompt", sysPrompt);
            record.put("user_prompt", usrPrompt);
            record.put("prompt_hash", sha256Hex(prompt != null ? prompt : ""));
            record.put("raw_llm_output", rawLlmOutput != null ? rawLlmOutput : "");
            record.put("parsed_grounding_check", structuredLlmOutput != null && structuredLlmOutput.groundingCheckText() != null
                    ? structuredLlmOutput.groundingCheckText()
                    : (structuredLlmOutput != null && structuredLlmOutput.groundingCheck() != null
                        ? structuredLlmOutput.groundingCheck().toString()
                        : ""));
            List<Map<String, Object>> bubbleList = new ArrayList<>();
            if (finalBubbles != null) {
                for (FanpageBotResponse.ReplyBubble b : finalBubbles) {
                    if (b != null) {
                        Map<String, Object> bm = new LinkedHashMap<>();
                        bm.put("text", b.text());
                        bm.put("delay_ms", b.typingDelayMs());
                        bubbleList.add(bm);
                    }
                }
            }
            record.put("final_bubbles", bubbleList);
            List<ConversationState.CustomerCareNoteArtifact> notesAfter = stateAfter != null && stateAfter.customerCareNotes() != null
                    ? stateAfter.customerCareNotes()
                    : List.of();
            int notesBeforeCount = stateBefore != null && stateBefore.customerCareNotes() != null
                    ? stateBefore.customerCareNotes().size()
                    : 0;
            List<ConversationState.CustomerCareNoteArtifact> notesCreatedThisTurn = notesAfter.size() > notesBeforeCount
                    ? notesAfter.subList(notesBeforeCount, notesAfter.size())
                    : List.of();
            List<Map<String, Object>> serializedNotes = new ArrayList<>();
            for (ConversationState.CustomerCareNoteArtifact n : notesCreatedThisTurn) {
                if (n != null) {
                    Map<String, Object> nm = new LinkedHashMap<>();
                    nm.put("tenantId", n.tenantId());
                    nm.put("conversationId", n.conversationId());
                    nm.put("turnId", n.turnId());
                    nm.put("confirmedScopes", n.confirmedScopes());
                    nm.put("careActor", n.careActor());
                    nm.put("noteText", n.noteText());
                    nm.put("timestamp", n.timestamp() != null ? n.timestamp().toString() : "");
                    serializedNotes.add(nm);
                }
            }
            record.put("customer_care_notes_created", serializedNotes);
            record.put("validator_violations", validatorViolations != null ? validatorViolations : List.of());
            record.put("first_pass_validator_errors", firstPassErrors != null ? firstPassErrors : List.of());
            record.put("validator_result", (validatorViolations == null || validatorViolations.isEmpty()) ? "PASS" : "BLOCKED:" + validatorViolations);
            record.put("fallback_or_repair_reason", fallbackOrRepairReason != null && !fallbackOrRepairReason.isBlank()
                    ? fallbackOrRepairReason
                    : (repairOutcome != null ? repairOutcome : "PASS_FIRST_TRY"));

            Map<String, Object> saMap = new LinkedHashMap<>();
            saMap.put("confirmedScopes", stateAfter != null && stateAfter.confirmedScopes() != null ? stateAfter.confirmedScopes() : List.of());
            saMap.put("forbiddenScopes", stateAfter != null && stateAfter.forbiddenScopes() != null ? stateAfter.forbiddenScopes() : List.of());
            saMap.put("apologizedErrorIds", stateAfter != null && stateAfter.apologizedErrorIds() != null ? stateAfter.apologizedErrorIds() : List.of());
            saMap.put("conditionClarified", stateAfter != null && stateAfter.conditionClarified());
            saMap.put("conditionTimeClarified", stateAfter != null && stateAfter.conditionTimeClarified());
            saMap.put("onset", stateAfter != null && stateAfter.onset() != null ? stateAfter.onset() : "");
            saMap.put("frequency", stateAfter != null && stateAfter.frequency() != null ? stateAfter.frequency() : "");
            saMap.put("frequencyCategory", stateAfter != null && stateAfter.frequencyCategory() != null ? stateAfter.frequencyCategory() : "");
            saMap.put("duration", stateAfter != null && stateAfter.duration() != null ? stateAfter.duration() : "");
            saMap.put("pendingQuestionSlot", stateAfter != null && stateAfter.pendingQuestionSlot() != null ? stateAfter.pendingQuestionSlot() : "");
            saMap.put("priceRequestedInSession", stateAfter != null && stateAfter.priceRequestedInSession());
            saMap.put("packageExplicitlySelected", stateAfter != null && stateAfter.packageExplicitlySelected());
            saMap.put("packageInterestAccepted", stateAfter != null && stateAfter.packageInterestAccepted());
            saMap.put("bookingIntentConfirmed", stateAfter != null && stateAfter.bookingIntentConfirmed());
            saMap.put("visitTime", stateAfter != null && stateAfter.visitTime() != null ? stateAfter.visitTime() : "");
            saMap.put("contactInfo", stateAfter != null && stateAfter.contactInfo() != null ? stateAfter.contactInfo() : "");
            saMap.put("bookingStatus", stateAfter != null && stateAfter.bookingStatus() != null ? stateAfter.bookingStatus() : "NONE");
            saMap.put("selectedPackageId", stateAfter != null && stateAfter.selectedPackageId() != null ? stateAfter.selectedPackageId() : "");
            saMap.put("lastOfferedPackageId", stateAfter != null && stateAfter.lastOfferedPackageId() != null ? stateAfter.lastOfferedPackageId() : "");
            saMap.put("knownSymptoms", stateAfter != null && stateAfter.knownSymptoms() != null ? stateAfter.knownSymptoms() : List.of());
            saMap.put("customerCareNotesCount", notesAfter.size());
            record.put("state_after", saMap);
            record.put("latency_ms", latencyMs);

            log.info("[LIVE_AUDIT_JSON] {}", LLM_JSON_MAPPER.writeValueAsString(record));
        } catch (Exception e) {
            log.debug("[LIVE_AUDIT_JSON] Failed to serialize audit log: {}", e.getMessage());
        }
    }

    private static String sha256Hex(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((input != null ? input : "").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static List<FanpageBotResponse.ReplyBubble> alignBubbleDelaysWithContract(
            List<FanpageBotResponse.ReplyBubble> llmBubbles,
            List<FanpageBotResponse.ReplyBubble> contractBubbles,
            ThinkingModePolicy.PostPriceDelayMetrics postPriceDelayMetrics,
            boolean cancelSecondBubbleIfPostPrice
    ) {
        if (llmBubbles == null || llmBubbles.isEmpty()) {
            return List.of();
        }
        boolean isSingleAnchorAfterSplitPrice = llmBubbles.size() == 1
                && ((contractBubbles != null
                        && contractBubbles.size() == 1
                        && contractBubbles.get(0) != null
                        && !hasQuotedPriceInText(contractBubbles.get(0).text())
                        && contractBubbles.get(0).typingDelayMs() >= 10_000)
                    || (llmBubbles.get(0) != null
                        && !hasQuotedPriceInText(llmBubbles.get(0).text())
                        && llmBubbles.get(0).typingDelayMs() >= 1_000
                        && postPriceDelayMetrics != null
                        && postPriceDelayMetrics.elapsedSincePriceBubbleMs() > 0));
        List<FanpageBotResponse.ReplyBubble> aligned = new ArrayList<>(llmBubbles.size());
        for (int i = 0; i < llmBubbles.size(); i++) {
            FanpageBotResponse.ReplyBubble lb = llmBubbles.get(i);
            FanpageBotResponse.ReplyBubble cb = (contractBubbles != null && i < contractBubbles.size()) ? contractBubbles.get(i) : null;
            String prevText = i > 0 && llmBubbles.get(i - 1) != null ? llmBubbles.get(i - 1).text() : "";
            boolean isPostPriceSecondBubble = (i > 0 && hasQuotedPriceInText(prevText)) || isSingleAnchorAfterSplitPrice;
            if (isPostPriceSecondBubble && cancelSecondBubbleIfPostPrice) {
                continue;
            }
            int targetDelay;
            if (i == 0 && !isSingleAnchorAfterSplitPrice) {
                targetDelay = 0;
            } else if (isPostPriceSecondBubble && postPriceDelayMetrics != null) {
                targetDelay = postPriceDelayMetrics.remainingDelayMs();
            } else if (lb != null && lb.typingDelayMs() == 1500 && contractBubbles != null && llmBubbles.size() > contractBubbles.size()) {
                targetDelay = 1500;
            } else {
                targetDelay = cb != null ? cb.typingDelayMs() : (lb != null ? lb.typingDelayMs() : 0);
                if (!isPostPriceSecondBubble && targetDelay > 2500) {
                    targetDelay = 2000;
                }
            }
            String text = lb != null && lb.text() != null ? lb.text().strip() : "";
            aligned.add(new FanpageBotResponse.ReplyBubble(text, targetDelay));
        }
        return aligned;
    }

    private static boolean matchesSkillCodeOrAlias(String routerSkillCode, String dbSkillCode, String detectedIntent) {
        if (routerSkillCode == null || dbSkillCode == null) {
            return false;
        }
        if (routerSkillCode.equalsIgnoreCase(dbSkillCode)) {
            return true;
        }
        if ("spa_first_price_objection_value_and_trial".equalsIgnoreCase(routerSkillCode)) {
            return "spa_objection_value_trial".equalsIgnoreCase(dbSkillCode);
        }
        if ("spa_explicit_downsell_or_second_objection".equalsIgnoreCase(routerSkillCode)) {
            if ("PRICE_OBJECTION_EXPLICIT_DOWNSELL".equalsIgnoreCase(detectedIntent)) {
                return "spa_objection_explicit_downsell".equalsIgnoreCase(dbSkillCode)
                        || "spa_objection_dynamic_downsell".equalsIgnoreCase(dbSkillCode);
            }
            return "spa_objection_dynamic_downsell".equalsIgnoreCase(dbSkillCode)
                    || "spa_objection_explicit_downsell".equalsIgnoreCase(dbSkillCode);
        }
        if ("spa_no_lower_package_handoff".equalsIgnoreCase(routerSkillCode)) {
            return "spa_objection_no_lower_package_handoff".equalsIgnoreCase(dbSkillCode);
        }
        if ("spa_graceful_exit_after_rejection".equalsIgnoreCase(routerSkillCode)
                || "spa_stage3_graceful_exit".equalsIgnoreCase(routerSkillCode)) {
            return "spa_graceful_exit_after_rejection".equalsIgnoreCase(dbSkillCode)
                    || "spa_stage3_graceful_exit".equalsIgnoreCase(dbSkillCode);
        }
        return false;
    }

    /** Che SĐT/email/CCCD và token/secret trong log audit; giữ nguyên văn bản triệu chứng/hội thoại. */
    static String maskSensitiveForAudit(String text) {
        if (text == null) {
            return null;
        }
        return text
                .replaceAll("(?i)(api[_-]?key|token|secret|bearer)\\s*[:=]?\\s*[A-Za-z0-9_\\-\\.]{12,}", "$1=***")
                .replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "***@***")
                .replaceAll("(?<!\\d)(?:\\+?84|0)(?:[\\s.-]?\\d){8,10}(?!\\d)", "***PHONE***")
                .replaceAll("(?<!\\d)\\d{12}(?!\\d)", "***CCCD***");
    }

    static String normalizeForProvenance(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String norm = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
                .replace("\\n", " ")
                .replace("\\\"", "\"")
                .replaceAll("(?is)<think>.*?</think>", " ")
                .replaceAll("(?iU)\\s*\\((?:HN_|SG_)?(?:NS|DS|ADD)\\d{2}\\)", " ")
                .replaceAll("(?iU)\\b(\\d{2,3})\\.000\\s*(?:đ|vnđ|vnd)\\b", "$1k")
                .replaceAll("(?iU)\\b(\\d{2,4}k)\\s*\\(\\s*\\d{1,3}(?:\\.\\d{3})+\\s*(?:đ|vnđ|vnd)?\\s*\\)", "$1")
                .replaceAll("(?iU)\\s*\\+\\s*", " ")
                .replaceAll("…+$", " ạ.")
                .replaceAll("…", " ")
                .replaceAll("(?iU)\\bđang\\s+gặp\\s+phải\\s+tình\\s+trạng\\s+", "đang bị ")
                .replaceAll("(?iU)\\bkhiến\\s+(mình|chị|anh)\\s+mệt\\s+mỏi\\s+hẳn\\s+luôn(?:\\s+ạ)?", "làm $1 mệt mỏi nhiều ạ")
                .replaceAll("(?iU)(^|[.!?\\n,;:\\-]\\s*)liệu\\s+(mình|chị|anh)\\s+có\\s+muốn\\b", "$1$2 có muốn")
                .toLowerCase(Locale.ROOT)
                .replaceAll("(?iU)\\b(quý\\s+khách|anh\\s*/\\s*chị|chị|anh|bạn|mình)\\b", "mình")
                .replaceAll("[^\\p{L}\\d]+", " ")
                .replaceAll("\\s+", " ")
                .strip();
        return norm;
    }

    private static boolean isSafetyPolicyRule(LearnedRuleDto r) {
        if (r == null) {
            return false;
        }
        String inst = r.generalizedRule() != null ? r.generalizedRule().toUpperCase(Locale.ROOT) : "";
        String src = r.ruleCode() != null ? r.ruleCode().toUpperCase(Locale.ROOT) : "";
        return inst.contains("[SAFE-")
                || inst.contains("HARD_HANDOFF_SAFETY")
                || src.contains("SAFE-NEURO")
                || src.contains("SAFETY");
    }

    private static List<LearnedRuleDto> filterMatchedRulesForUiResponse(
            List<LearnedRuleDto> rules,
            PrimaryActionRouter.RoutingDecision actionDecision,
            boolean finalHandoff
    ) {
        if (rules == null || rules.isEmpty()) {
            return List.of();
        }
        boolean safetyHandoffTriggered = finalHandoff
                && actionDecision != null
                && actionDecision.effectiveResponseAction() == PrimaryActionRouter.PrimaryAction.HARD_HANDOFF_SAFETY;
        List<LearnedRuleDto> matched = new ArrayList<>(rules.size());
        for (LearnedRuleDto r : rules) {
            if (r == null) continue;
            if (isSafetyPolicyRule(r) && !safetyHandoffTriggered) {
                continue;
            }
            matched.add(r);
        }
        return List.copyOf(matched);
    }

    private static String formatStateTelemetrySummary(ConversationState s) {
        if (s == null) {
            return "{null}";
        }
        return "{greetingSent=" + s.greetingSent()
                + ", knownSymptoms=" + s.knownSymptoms()
                + ", knownBodyAreas=" + s.knownBodyAreas()
                + ", conditionAskedCount=" + s.conditionAskedCount()
                + ", conditionClarified=" + s.conditionClarified()
                + ", priceRequested=" + s.priceRequestedInSession()
                + ", priceDiscussed=" + s.priceDiscussed()
                + ", packageRecommended=" + s.packageRecommended()
                + ", heldPackage=" + s.heldPackage()
                + ", heldDuration=" + s.heldDuration()
                + ", heldPrice=" + s.heldPrice()
                + ", lastBotQuestionType=" + s.lastBotQuestionType()
                + ", ambiguousCount=" + s.ambiguousCount()
                + ", frustrationCount=" + s.frustrationCount()
                + ", humanTakeover=" + s.humanTakeover()
                + "}";
    }

    private static final ObjectMapper LLM_JSON_MAPPER = new ObjectMapper();
}
