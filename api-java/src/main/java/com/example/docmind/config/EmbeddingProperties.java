package com.example.docmind.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Cấu hình embedding. dim=384 khớp paraphrase-multilingual-MiniLM-L12-v2. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "docmind.embedding")
public class EmbeddingProperties {

    private int dim = 384;

    /** Bật/tắt In-JVM Query Embedding Cache cho truy vấn ngắn/lặp lại (Unit P3.2). */
    private boolean queryCacheEnabled = true;

    /** Số lượng entry tối đa trong LRU In-JVM Query Embedding Cache. */
    private int queryCacheMaxEntries = 512;

    /** Giới hạn độ dài chuỗi tối đa được phép cache để tránh chunk lớn đẩy bật query chat. */
    private int queryCacheMaxTextChars = 2048;

    /** Cấu hình gọi embedding thật qua doc-service-py (POST /v1/embed). */
    private Remote remote = new Remote();

    /** Nhóm key docmind.embedding.remote.* */
    @Getter
    @Setter
    public static class Remote {

        /** Bật/tắt remote embedding; false => chỉ dùng stub. */
        private boolean enabled = true;

        /** Đường dẫn endpoint embed trên doc-service-py. */
        private String endpointPath = "/v1/embed";
    }
}
