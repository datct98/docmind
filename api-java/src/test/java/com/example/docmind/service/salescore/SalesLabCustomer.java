package com.example.docmind.service.salescore;

import com.example.docmind.service.llm.LlmClient;
import com.example.docmind.service.llm.ReasoningBudget;
import com.example.docmind.service.reasoning.QueryComplexity;
import com.example.docmind.service.bot.ThinkingMode;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static com.example.docmind.service.salescore.SalesContracts.*;

/** Offline test actor. It sees only a persona and customer-visible history, never answer keys or the bot's internals. */
final class SalesLabCustomer {
    record Contribution(boolean done, String message) {}
    static Contribution next(LlmClient client, String persona, List<Message> visibleHistory) {
        var json = new ObjectMapper();
        String prompt = """
            CUSTOMER_SIMULATION: Đóng vai khách hàng Việt Nam trong một bài thử offline. Bạn không phải nhân viên bán hàng.
            Giữ nhu cầu, tính cách và giới hạn trong persona; chỉ phản hồi nội dung bot THỰC SỰ vừa nói. Có thể thắc mắc,
            phản đối, sửa thông tin, muốn chọn hoặc dừng nếu hợp trải nghiệm. Đừng giúp bot qua bài bằng cách đồng ý hết.
            Không biết giá/quyền lợi ngoài lời bot, không có đáp án mẫu; đừng tự tạo chứng nhận hay giao dịch.
            Viết như khách thật, một tin ngắn tự nhiên, không nói đây là bài test. Khi đã đủ thông tin hoặc muốn rời đi,
            có thể nói lời dừng ở message; lượt sau done=true. Không kéo dài cố tình, không tạo thông tin cá nhân thật.
            Chỉ JSON {"done":false,"message":"tin khách"}; done=true khi không cần thêm tin.
            """ + "\n<!-- SYSTEM_USER_SPLIT -->\nCUSTOMER_DATA:" + json.writeValueAsString(Map.of("persona", persona,
                "history", visibleHistory.stream().map(m -> Map.of("speaker", "user".equals(m.role()) ? "CUSTOMER" : "SHOP", "text", m.content())).toList()))
                + "\nBạn là CUSTOMER đang trả lời SHOP. Chỉ viết điều người mua muốn nói, không viết lời tư vấn thay SHOP.";
        String raw = client.complete(prompt, new ReasoningBudget(QueryComplexity.SIMPLE, "none", 30, 0, 2500, 350, 0.4, 0.9, null, ThinkingMode.FAST_NO_THINK));
        var result = json.readValue(SalesJson.objectText(raw, 2000), Contribution.class);
        if (!result.done() && (result.message() == null || result.message().isBlank() || result.message().length() > 700))
            throw new IllegalArgumentException("Invalid customer simulation output");
        return result;
    }
}
