package com.example.docmind.service.embedding;

import com.example.docmind.config.EmbeddingProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Embedding stub: vector giả TẤT ĐỊNH (hash-seeded) + L2-normalize.
 *
 * <p>Mục tiêu: toán retrieval (cosine) chạy được KHÔNG cần model nào. Vì cùng text
 * luôn ra cùng vector, cosine giữa text giống/khác nhau vẫn có ý nghĩa tương đối,
 * đủ để kiểm thử và chạy pipeline. KHÔNG dùng cho chất lượng ngữ nghĩa thật.
 *
 * <p>Active mặc định; nhường chỗ cho impl thật qua @ConditionalOnMissingBean.
 *
 * <p>TODO: thay bằng impl thật dùng paraphrase-multilingual-MiniLM-L12-v2 (dim 384),
 * có thể gọi sang doc-service-py hoặc nạp ONNX in-JVM.
 */
@Component
@ConditionalOnMissingBean(name = "primaryEmbeddingClient")
public class StubEmbeddingClient implements EmbeddingClient {

    private final int dim;

    public StubEmbeddingClient(EmbeddingProperties props) {
        this.dim = props.getDim();
    }

    @Override
    public int dim() {
        return dim;
    }

    @Override
    public float[] embed(String text) {
        String src = text == null ? "" : text.toLowerCase();
        float[] v = new float[dim];

        // Băm theo từng token để vector phản ánh nội dung text (hỗ trợ Unicode tiếng Việt đầy đủ).
        String[] tokens = src.split("(?U)[^\\p{L}\\p{Nd}]+");
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if (token.isEmpty()) {
                continue;
            }
            addToken(v, token, 1.0f);
            if (i < tokens.length - 1 && !tokens[i + 1].isEmpty()) {
                addToken(v, token + "_" + tokens[i + 1], 1.5f);
            }
        }

        // Nếu text rỗng, tạo vector tất định nhỏ để tránh toàn 0 (chia 0 khi normalize).
        if (isAllZero(v)) {
            int seed = Math.floorMod(src.hashCode(), dim);
            v[seed] = 1f;
        }

        l2Normalize(v);
        return v;
    }

    private void addToken(float[] v, String token, float weight) {
        int h = token.hashCode();
        int idx = Math.floorMod(h, dim);
        float sign = ((h >>> 31) & 1) == 0 ? weight : -weight;
        v[idx] += sign;
    }

    private static boolean isAllZero(float[] v) {
        for (float x : v) {
            if (x != 0f) {
                return false;
            }
        }
        return true;
    }

    private static void l2Normalize(float[] v) {
        double sumSq = 0;
        for (float x : v) {
            sumSq += (double) x * x;
        }
        double norm = Math.sqrt(sumSq);
        if (norm > 0) {
            for (int i = 0; i < v.length; i++) {
                v[i] = (float) (v[i] / norm);
            }
        }
    }
}
