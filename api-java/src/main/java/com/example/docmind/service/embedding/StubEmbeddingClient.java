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

    private static final String[][] VI_SEMANTIC_CONCEPTS = new String[][]{
            {"__concept_neck_shoulder_pain__", "mỏi vai", "bả vai", "cổ vai gáy", "vai gáy", "căng cơ", "bó cơ", "nhức mỏi", "đau nhức", "đau vai", "đau cổ", "ngồi máy tính", "văn phòng", "ngồi làm việc", "ấn huyệt", "bấm huyệt", "giải cơ"},
            {"__concept_headache_sleep__", "đau đầu", "nặng đầu", "mất ngủ", "khó ngủ", "ngủ không sâu", "tiền đình", "căng thẳng", "stress", "áp lực công việc", "dưỡng sinh đầu"},
            {"__concept_herbal_therapy_process__", "ngâm bồn", "thảo dược", "xông hơi", "ngải cứu", "chườm nóng", "muối gừng", "ngâm chân", "quy trình", "các bước", "điểm tâm", "thực dưỡng"},
            {"__concept_pricing_packages__", "bảng giá", "mức giá", "chi phí", "bao nhiêu tiền", "niêm yết", "399.000", "530.000", "580.000", "129.000", "239.000", "trọn gói", "không nhận tip"},
            {"__concept_privacy_couple_vip__", "phòng riêng", "phòng vip", "phòng đôi", "cặp đôi", "vợ chồng", "riêng tư", "kín đáo", "2 người", "đi đôi"}
    };

    /**
     * Kết quả kiểm chứng Cổng tương đương vector {@code float[384]} (Unit P3.2).
     */
    public record VectorEquivalenceReport(
            boolean equivalent,
            int expectedDim,
            int leftDim,
            int rightDim,
            double leftL2Norm,
            double rightL2Norm,
            double cosineSimilarity,
            float maxAbsElementDiff,
            String failureReason
    ) {
    }

    /**
     * Kiểm chứng tương đương hình học & phần tử giữa hai vector {@code float[expectedDim]}:
     * kiểm tra kích thước chiều, hữu hạn (không NaN/Inf), chuẩn hóa đơn vị L2, cosine similarity và sai số tuyệt đối cực đại.
     */
    public static VectorEquivalenceReport evaluateEquivalence(
            float[] left,
            float[] right,
            int expectedDim,
            double minCosine,
            float maxAllowedElementDiff
    ) {
        if (left == null || right == null) {
            return new VectorEquivalenceReport(
                    false, expectedDim,
                    left == null ? -1 : left.length,
                    right == null ? -1 : right.length,
                    0.0, 0.0, 0.0, Float.POSITIVE_INFINITY,
                    "NULL_VECTOR"
            );
        }
        if (left.length != expectedDim || right.length != expectedDim) {
            return new VectorEquivalenceReport(
                    false, expectedDim, left.length, right.length,
                    0.0, 0.0, 0.0, Float.POSITIVE_INFINITY,
                    "DIMENSION_MISMATCH"
            );
        }
        double sumLeftSq = 0.0;
        double sumRightSq = 0.0;
        double dot = 0.0;
        float maxDiff = 0.0f;
        for (int i = 0; i < expectedDim; i++) {
            float a = left[i];
            float b = right[i];
            if (!Float.isFinite(a) || !Float.isFinite(b)) {
                return new VectorEquivalenceReport(
                        false, expectedDim, left.length, right.length,
                        0.0, 0.0, 0.0, Float.POSITIVE_INFINITY,
                        "NON_FINITE_ELEMENT_AT_" + i
                );
            }
            sumLeftSq += (double) a * a;
            sumRightSq += (double) b * b;
            dot += (double) a * b;
            float diff = Math.abs(a - b);
            if (diff > maxDiff) {
                maxDiff = diff;
            }
        }
        double leftNorm = Math.sqrt(sumLeftSq);
        double rightNorm = Math.sqrt(sumRightSq);
        if (Math.abs(leftNorm - 1.0) > 1e-4 || Math.abs(rightNorm - 1.0) > 1e-4) {
            return new VectorEquivalenceReport(
                    false, expectedDim, left.length, right.length,
                    leftNorm, rightNorm, 0.0, maxDiff,
                    "NOT_L2_NORMALIZED"
            );
        }
        double cosine = dot / (leftNorm * rightNorm);
        if (cosine < minCosine) {
            return new VectorEquivalenceReport(
                    false, expectedDim, left.length, right.length,
                    leftNorm, rightNorm, cosine, maxDiff,
                    "COSINE_BELOW_THRESHOLD:" + cosine
            );
        }
        if (maxDiff > maxAllowedElementDiff) {
            return new VectorEquivalenceReport(
                    false, expectedDim, left.length, right.length,
                    leftNorm, rightNorm, cosine, maxDiff,
                    "ELEMENT_DIFF_EXCEEDED:" + maxDiff
            );
        }
        return new VectorEquivalenceReport(
                true, expectedDim, left.length, right.length,
                leftNorm, rightNorm, cosine, maxDiff,
                null
        );
    }

    @Override
    public float[] embed(String text) {
        String src = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT);
        double[] acc = new double[dim];

        // Tách token tương đương chính xác 100% với Python _StubProvider (re.split(r"\W+", token)):
        // giữ chữ cái Unicode, chữ số Unicode và dấu gạch dưới '_'.
        StringBuilder tokenBuf = new StringBuilder();
        for (int offset = 0; offset < src.length(); ) {
            int cp = src.codePointAt(offset);
            if (Character.isLetterOrDigit(cp) || cp == '_') {
                tokenBuf.appendCodePoint(cp);
            } else if (tokenBuf.length() > 0) {
                addToken(acc, tokenBuf.toString(), 1.0);
                tokenBuf.setLength(0);
            }
            offset += Character.charCount(cp);
        }
        if (tokenBuf.length() > 0) {
            addToken(acc, tokenBuf.toString(), 1.0);
        }

        // Chiếu các cụm khái niệm ngữ nghĩa tiếng Việt vào không gian vector 384 chiều
        for (String[] concept : VI_SEMANTIC_CONCEPTS) {
            String conceptId = concept[0];
            int matches = 0;
            for (int k = 1; k < concept.length; k++) {
                if (src.contains(concept[k])) {
                    matches++;
                }
            }
            if (matches > 0) {
                double weight = Math.min(4.5, 2.0 + matches * 0.8);
                for (int salt = 0; salt < 3; salt++) {
                    addToken(acc, conceptId + ":" + salt, weight);
                }
            }
        }

        // Nếu text rỗng, tạo vector tất định nhỏ để tránh toàn 0 (chia 0 khi normalize).
        if (isAllZero(acc)) {
            int seed = Math.floorMod(src.hashCode(), dim);
            acc[seed] = 1.0;
        }

        return l2NormalizeToFloat(acc);
    }

    private void addToken(double[] v, String token, double weight) {
        int h = token.hashCode();
        int idx = Math.floorMod(h, dim);
        double sign = ((h >>> 31) & 1) == 0 ? weight : -weight;
        v[idx] += sign;
    }

    private static boolean isAllZero(double[] v) {
        for (double x : v) {
            if (x != 0.0) {
                return false;
            }
        }
        return true;
    }

    private static float[] l2NormalizeToFloat(double[] v) {
        double sumSq = 0.0;
        for (double x : v) {
            sumSq += x * x;
        }
        double norm = Math.sqrt(sumSq);
        float[] out = new float[v.length];
        if (norm > 0.0) {
            for (int i = 0; i < v.length; i++) {
                out[i] = (float) (v[i] / norm);
            }
        }
        return out;
    }
}
