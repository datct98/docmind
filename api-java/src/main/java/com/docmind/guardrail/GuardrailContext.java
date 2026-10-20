package com.docmind.guardrail;

import java.util.Collections;
import java.util.List;

/**
 * Ngữ cảnh đầu vào cho In-Loop Deterministic Guardrail Pipeline.
 * Chứa thông tin về danh xưng khách hàng, tên hiển thị, giai đoạn bán hàng,
 * lịch sử hội thoại và các đoạn trích tri thức liên quan.
 */
public class GuardrailContext {

    private final String customerHonorific;
    private final String customerName;
    private final boolean isFirstTurn;
    private final String salesStage;
    private final String customerMessage;
    private final List<String> conversationHistory;
    private final List<String> knowledgeChunks;

    public GuardrailContext(
            String customerHonorific,
            String customerName,
            boolean isFirstTurn,
            String salesStage,
            String customerMessage,
            List<String> conversationHistory,
            List<String> knowledgeChunks
    ) {
        this.customerHonorific = (customerHonorific != null && !customerHonorific.isBlank())
                ? customerHonorific.strip()
                : "Chị";
        this.customerName = customerName;
        this.isFirstTurn = isFirstTurn;
        this.salesStage = (salesStage != null && !salesStage.isBlank())
                ? salesStage.strip()
                : "DISCOVERY";
        this.customerMessage = customerMessage != null ? customerMessage : "";
        this.conversationHistory = conversationHistory != null ? conversationHistory : Collections.emptyList();
        this.knowledgeChunks = knowledgeChunks != null ? knowledgeChunks : Collections.emptyList();
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getCustomerHonorific() {
        return customerHonorific;
    }

    public boolean isMale() {
        return "Anh".equalsIgnoreCase(customerHonorific);
    }

    public String getCustomerName() {
        return customerName;
    }

    public boolean isFirstTurn() {
        return isFirstTurn;
    }

    public String getSalesStage() {
        return salesStage;
    }

    public boolean isDiscoveryStage() {
        return "DISCOVERY".equalsIgnoreCase(salesStage);
    }

    public String getCustomerMessage() {
        return customerMessage;
    }

    public List<String> getConversationHistory() {
        return conversationHistory;
    }

    public List<String> getKnowledgeChunks() {
        return knowledgeChunks;
    }

    public static class Builder {
        private String customerHonorific = "Chị";
        private String customerName;
        private boolean isFirstTurn = true;
        private String salesStage = "DISCOVERY";
        private String customerMessage = "";
        private List<String> conversationHistory = Collections.emptyList();
        private List<String> knowledgeChunks = Collections.emptyList();

        public Builder customerHonorific(String customerHonorific) {
            this.customerHonorific = customerHonorific;
            return this;
        }

        public Builder customerName(String customerName) {
            this.customerName = customerName;
            return this;
        }

        public Builder isFirstTurn(boolean isFirstTurn) {
            this.isFirstTurn = isFirstTurn;
            return this;
        }

        public Builder salesStage(String salesStage) {
            this.salesStage = salesStage;
            return this;
        }

        public Builder customerMessage(String customerMessage) {
            this.customerMessage = customerMessage;
            return this;
        }

        public Builder conversationHistory(List<String> conversationHistory) {
            this.conversationHistory = conversationHistory;
            return this;
        }

        public Builder knowledgeChunks(List<String> knowledgeChunks) {
            this.knowledgeChunks = knowledgeChunks;
            return this;
        }

        public GuardrailContext build() {
            return new GuardrailContext(
                    customerHonorific,
                    customerName,
                    isFirstTurn,
                    salesStage,
                    customerMessage,
                    conversationHistory,
                    knowledgeChunks
            );
        }
    }
}
