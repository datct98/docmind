package com.example.docmind.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Tham số luồng chat/RAG (Java-only). */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "docmind.chat")
public class ChatProperties {

    /** Số chunk cuối cùng đưa vào prompt. */
    private int topK = 5;

    /** Số ứng viên lấy mỗi arm (vector / full-text) trước khi RRF fuse. */
    private int candidateK = 20;

    /** Hằng số RRF (thường 60). */
    private int rrfK = 60;

    /** Số chunk backlink tối đa được mở rộng thêm vào retrieval (mặc định 2). */
    private int maxBacklinks = 2;
}
