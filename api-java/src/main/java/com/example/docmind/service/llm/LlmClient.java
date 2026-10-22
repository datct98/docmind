package com.example.docmind.service.llm;

/**
 * Gọi LLM sinh câu trả lời từ prompt. Impl mặc định là stub (tất định) để chat chạy
 * không cần API key. GeminiLlmClient chỉ kích hoạt khi gemini.api.keys có giá trị.
 */
public interface LlmClient {

    /** Trả về answer text. */
    String complete(String prompt);

    /** Trả về answer text với ngân sách suy luận thích ứng (ReasoningBudget F8). */
    default String complete(String prompt, ReasoningBudget budget) {
        return complete(prompt);
    }

    /** Tên impl (để log / debug). */
    String name();
}
