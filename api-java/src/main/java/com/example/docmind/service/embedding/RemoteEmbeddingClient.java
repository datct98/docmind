package com.example.docmind.service.embedding;

import com.example.docmind.client.model.EmbedRequest;
import com.example.docmind.client.model.EmbedResponse;
import com.example.docmind.config.EmbeddingProperties;
import com.example.docmind.observability.CorrelationIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Embedding thật: gọi doc-service-py {@code POST /v1/embed} qua CÙNG RestClient
 * mà parser dùng ({@code docServiceRestClient} — same base URL). Propagate
 * X-Correlation-Id để log hai service chung một chuỗi.
 *
 * <p>Là bean {@code @Primary} (tên {@code primaryEmbeddingClient}) nên khi tồn
 * tại nó được inject thay cho {@link StubEmbeddingClient} (stub vẫn được tạo và
 * giữ làm fallback delegate — xem {@code @ConditionalOnMissingBean} trên stub).
 *
 * <p>Fallback theo TỪNG call: khi remote lỗi (hoặc bị tắt), rơi về stub cho
 * đúng call đó để ingest KHÔNG bao giờ fail cứng vì một trục trặc embedding.
 *
 * <p>Unit P3.2: Tích hợp In-JVM Query Embedding Cache giới hạn kích thước (LRU),
 * thread-safe, sao chép phòng thủ {@code float[384]} khi đọc/ghi và KHÔNG ghi bẩn
 * cache khi remote gặp lỗi mạng tạm thời.
 */
@Component("primaryEmbeddingClient")
@Primary
public class RemoteEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(RemoteEmbeddingClient.class);

    private record QueryCacheKey(boolean remoteEnabled, int dim, String text) {
    }

    private final RestClient restClient;
    private final StubEmbeddingClient stub;
    private final EmbeddingProperties props;
    private final Map<QueryCacheKey, float[]> queryCache;
    private final Object cacheLock = new Object();
    private final AtomicLong cacheHitCounter = new AtomicLong(0L);
    private final AtomicLong cacheMissCounter = new AtomicLong(0L);

    /**
     * Stub fallback được dựng NGAY trong constructor từ {@link EmbeddingProperties}
     * (không inject như bean) vì {@link StubEmbeddingClient} mang
     * {@code @ConditionalOnMissingBean(name = "primaryEmbeddingClient")} — khi bean
     * này (tên {@code primaryEmbeddingClient}) tồn tại, bean stub KHÔNG được tạo.
     * Giữ nguyên annotation của stub là thay đổi nhỏ nhất; stub chỉ là helper tất
     * định nên tạo trực tiếp là an toàn.
     */
    public RemoteEmbeddingClient(RestClient docServiceRestClient,
                                 EmbeddingProperties props) {
        this.restClient = docServiceRestClient;
        this.props = props;
        this.stub = new StubEmbeddingClient(props);
        this.queryCache = new LinkedHashMap<>(128, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<QueryCacheKey, float[]> eldest) {
                int maxEntries = Math.max(1, props.getQueryCacheMaxEntries());
                return size() > maxEntries;
            }
        };
    }

    @Override
    public int dim() {
        return props.getDim();
    }

    public long cacheHitCount() {
        return cacheHitCounter.get();
    }

    public long cacheMissCount() {
        return cacheMissCounter.get();
    }

    public int cacheSize() {
        synchronized (cacheLock) {
            return queryCache.size();
        }
    }

    public void clearCache() {
        synchronized (cacheLock) {
            queryCache.clear();
        }
        cacheHitCounter.set(0L);
        cacheMissCounter.set(0L);
    }

    @Override
    public float[] embed(String text) {
        return embedBatch(List.of(text == null ? "" : text)).get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<String> normalizedInputs = texts.stream()
                .map(t -> t == null ? "" : t)
                .toList();
        boolean remoteEnabled = props.getRemote().isEnabled();
        int expectedDim = props.getDim();
        boolean cacheEnabled = props.isQueryCacheEnabled() && props.getQueryCacheMaxEntries() > 0;
        int maxCacheTextChars = Math.max(64, props.getQueryCacheMaxTextChars());

        float[][] resolved = new float[normalizedInputs.size()][];
        List<String> uncachedUniqueTexts = new ArrayList<>();
        Map<String, List<Integer>> uncachedPositionsByText = new LinkedHashMap<>();

        for (int idx = 0; idx < normalizedInputs.size(); idx++) {
            String item = normalizedInputs.get(idx);
            boolean eligibleForCache = cacheEnabled && item.length() <= maxCacheTextChars;
            if (eligibleForCache) {
                float[] cached = getFromCache(new QueryCacheKey(remoteEnabled, expectedDim, item));
                if (cached != null) {
                    cacheHitCounter.incrementAndGet();
                    resolved[idx] = cached;
                    continue;
                }
            }
            cacheMissCounter.incrementAndGet();
            uncachedPositionsByText.computeIfAbsent(item, k -> {
                uncachedUniqueTexts.add(k);
                return new ArrayList<>();
            }).add(idx);
        }

        if (uncachedUniqueTexts.isEmpty()) {
            log.debug("embed-cache HIT batch_size={} dim={}", normalizedInputs.size(), expectedDim);
            return Arrays.asList(resolved);
        }

        if (!remoteEnabled) {
            log.debug("remote embedding disabled -> stub ({} uncached texts)", uncachedUniqueTexts.size());
            List<float[]> stubVectors = stub.embedBatch(uncachedUniqueTexts);
            populateResolvedAndCache(
                    uncachedUniqueTexts, stubVectors, uncachedPositionsByText, resolved,
                    cacheEnabled, maxCacheTextChars, false, expectedDim, true
            );
            return Arrays.asList(resolved);
        }

        CorrelationIds.setStage("embed-serve");
        try {
            String correlationId = CorrelationIds.currentCorrelationId();
            var reqSpec = restClient.post()
                    .uri(props.getRemote().getEndpointPath());
            if (correlationId != null && !correlationId.isBlank()) {
                reqSpec = reqSpec.header(CorrelationIds.HEADER, correlationId);
            }
            EmbedResponse resp = reqSpec
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new EmbedRequest(uncachedUniqueTexts))
                    .retrieve()
                    .body(EmbedResponse.class);

            List<float[]> vectors = mapResponse(resp, uncachedUniqueTexts.size());
            log.info("embed-serve OK provider_model={} batch_size={} dim={}",
                    resp.model(), uncachedUniqueTexts.size(), resp.dim());
            populateResolvedAndCache(
                    uncachedUniqueTexts, vectors, uncachedPositionsByText, resolved,
                    cacheEnabled, maxCacheTextChars, true, expectedDim, true
            );
            return Arrays.asList(resolved);
        } catch (RestClientResponseException e) {
            // Lỗi HTTP có response: log cả status + body thật (pattern RestDocServiceClient),
            // rồi fallback stub cho call này (KHÔNG ghi vào cache để không làm bẩn cache khi remote hồi phục).
            log.warn("embed-serve FAILED status={} body={} -> fallback stub ({} texts)",
                    e.getStatusCode(), e.getResponseBodyAsString(), uncachedUniqueTexts.size());
            List<float[]> fallbackVectors = stub.embedBatch(uncachedUniqueTexts);
            populateResolvedAndCache(
                    uncachedUniqueTexts, fallbackVectors, uncachedPositionsByText, resolved,
                    cacheEnabled, maxCacheTextChars, true, expectedDim, false
            );
            return Arrays.asList(resolved);
        } catch (RestClientException e) {
            // Không có response (timeout/connect) hoặc lỗi mapping/validate -> stub (không ghi bẩn cache).
            log.warn("embed-serve FAILED (no response): {} -> fallback stub ({} texts)",
                    e.getMessage(), uncachedUniqueTexts.size());
            List<float[]> fallbackVectors = stub.embedBatch(uncachedUniqueTexts);
            populateResolvedAndCache(
                    uncachedUniqueTexts, fallbackVectors, uncachedPositionsByText, resolved,
                    cacheEnabled, maxCacheTextChars, true, expectedDim, false
            );
            return Arrays.asList(resolved);
        } catch (RuntimeException e) {
            // Body rỗng / dim sai / độ dài vector sai (mapResponse ném IllegalState) -> stub (không ghi bẩn cache).
            log.warn("embed-serve FAILED (bad response): {} -> fallback stub ({} texts)",
                    e.getMessage(), uncachedUniqueTexts.size());
            List<float[]> fallbackVectors = stub.embedBatch(uncachedUniqueTexts);
            populateResolvedAndCache(
                    uncachedUniqueTexts, fallbackVectors, uncachedPositionsByText, resolved,
                    cacheEnabled, maxCacheTextChars, true, expectedDim, false
            );
            return Arrays.asList(resolved);
        } finally {
            CorrelationIds.clearStage();
        }
    }

    private float[] getFromCache(QueryCacheKey key) {
        synchronized (cacheLock) {
            float[] stored = queryCache.get(key);
            return stored == null ? null : Arrays.copyOf(stored, stored.length);
        }
    }

    private void putInCache(QueryCacheKey key, float[] vector) {
        if (vector == null) {
            return;
        }
        synchronized (cacheLock) {
            queryCache.put(key, Arrays.copyOf(vector, vector.length));
        }
    }

    private void populateResolvedAndCache(
            List<String> uncachedUniqueTexts,
            List<float[]> computedVectors,
            Map<String, List<Integer>> uncachedPositionsByText,
            float[][] resolved,
            boolean cacheEnabled,
            int maxCacheTextChars,
            boolean remoteEnabled,
            int expectedDim,
            boolean allowCacheWrite
    ) {
        for (int u = 0; u < uncachedUniqueTexts.size(); u++) {
            String text = uncachedUniqueTexts.get(u);
            float[] vec = computedVectors.get(u);
            if (allowCacheWrite && cacheEnabled && text.length() <= maxCacheTextChars) {
                putInCache(new QueryCacheKey(remoteEnabled, expectedDim, text), vec);
            }
            List<Integer> positions = uncachedPositionsByText.get(text);
            if (positions != null) {
                for (int pos : positions) {
                    resolved[pos] = Arrays.copyOf(vec, vec.length);
                }
            }
        }
    }

    /** Validate response + map {@code List<List<Float>>} -> {@code List<float[]>}. */
    private List<float[]> mapResponse(EmbedResponse resp, int expectedCount) {
        if (resp == null || resp.embeddings() == null) {
            throw new IllegalStateException("embed response rỗng");
        }
        int expectedDim = props.getDim();
        if (resp.dim() != expectedDim) {
            throw new IllegalStateException(
                    "embed dim không khớp: nhận " + resp.dim() + " kỳ vọng " + expectedDim);
        }
        if (resp.embeddings().size() != expectedCount) {
            throw new IllegalStateException(
                    "embed trả " + resp.embeddings().size() + " vector, kỳ vọng " + expectedCount);
        }
        return resp.embeddings().stream().map(vec -> toFloatArray(vec, expectedDim)).toList();
    }

    private static float[] toFloatArray(List<Float> vec, int expectedDim) {
        if (vec == null || vec.size() != expectedDim) {
            throw new IllegalStateException(
                    "vector dài " + (vec == null ? "null" : vec.size()) + ", kỳ vọng " + expectedDim);
        }
        float[] out = new float[expectedDim];
        for (int i = 0; i < expectedDim; i++) {
            out[i] = vec.get(i);
        }
        return out;
    }
}
