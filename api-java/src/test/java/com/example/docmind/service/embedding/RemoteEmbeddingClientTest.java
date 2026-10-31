package com.example.docmind.service.embedding;

import com.example.docmind.client.model.EmbedResponse;
import com.example.docmind.config.EmbeddingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test fallback-to-stub của RemoteEmbeddingClient KHÔNG cần Python service sống
 * (mock toàn bộ fluent chain của RestClient). Stub fallback là nội bộ (dựng từ
 * EmbeddingProperties) nên ta chứng minh hành vi qua GIÁ TRỊ TRẢ VỀ quan sát được:
 *  - happy path: trả đúng vector từ response (khác hẳn vector stub);
 *  - lỗi HTTP (RestClientResponseException): rơi về stub, KHÔNG ném exception;
 *  - remote tắt: KHÔNG chạm RestClient, trả stub;
 *  - dim()=384.
 */
class RemoteEmbeddingClientTest {

    private EmbeddingProperties props;

    @BeforeEach
    void setUp() {
        props = new EmbeddingProperties();
        props.setDim(384);
    }

    @Test
    void dimIs384() {
        RemoteEmbeddingClient client =
                new RemoteEmbeddingClient(mock(RestClient.class), props);
        assertThat(client.dim()).isEqualTo(384);
    }

    @Test
    void happyPathReturnsRemoteVectorNotStubVector() {
        // Vector 384 chiều xác định (one-hot) — KHÁC hẳn vector stub của cùng text.
        List<Float> remoteVec = new ArrayList<>();
        for (int i = 0; i < 384; i++) {
            remoteVec.add(i == 0 ? 1.0f : 0.0f);
        }
        EmbedResponse resp =
                new EmbedResponse("paraphrase-multilingual-MiniLM-L12-v2", 384, List.of(remoteVec));

        RestClient restClient = mockRestClientReturning(resp);
        RemoteEmbeddingClient client = new RemoteEmbeddingClient(restClient, props);

        float[] out = client.embed("đạo hàm");

        assertThat(out).hasSize(384);
        assertThat(out[0]).isEqualTo(1.0f);
        assertThat(out[1]).isEqualTo(0.0f);
        // Chứng minh KHÔNG dùng stub: vector stub của cùng text khác vector remote.
        float[] stubVec = new StubEmbeddingClient(props).embed("đạo hàm");
        assertThat(out).isNotEqualTo(stubVec);
    }

    @Test
    void httpErrorFallsBackToStubAndDoesNotThrow() {
        RestClient restClient = mockRestClientThrowing(
                new RestClientResponseException(
                        "500 Internal Server Error",
                        HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        "Internal Server Error",
                        HttpHeaders.EMPTY,
                        "boom".getBytes(),
                        null));

        RemoteEmbeddingClient client = new RemoteEmbeddingClient(restClient, props);

        float[] out = client.embed("đạo hàm");
        float[] expected = new StubEmbeddingClient(props).embed("đạo hàm");

        assertThat(out).hasSize(384);
        assertThat(out).containsExactly(expected);
    }

    @Test
    void disabledRemoteReturnsStubWithoutCallingRestClient() {
        props.getRemote().setEnabled(false);
        RestClient restClient = mock(RestClient.class);
        RemoteEmbeddingClient client = new RemoteEmbeddingClient(restClient, props);

        float[] out = client.embed("đạo hàm");
        float[] expected = new StubEmbeddingClient(props).embed("đạo hàm");

        assertThat(out).containsExactly(expected);
        // RestClient không được chạm tới khi remote tắt.
        verifyNoInteractions(restClient);
    }

    @Test
    void queryCacheServesRepeatQueriesWithDefensiveCopyAndEvictsByLruBound() {
        props.setQueryCacheEnabled(true);
        props.setQueryCacheMaxEntries(2);

        List<Float> remoteVecA = new ArrayList<>();
        List<Float> remoteVecB = new ArrayList<>();
        List<Float> remoteVecC = new ArrayList<>();
        for (int i = 0; i < 384; i++) {
            remoteVecA.add(i == 0 ? 1.0f : 0.0f);
            remoteVecB.add(i == 1 ? 1.0f : 0.0f);
            remoteVecC.add(i == 2 ? 1.0f : 0.0f);
        }

        RestClient restClient = mock(RestClient.class);
        RestClient.RequestBodyUriSpec spec = mock(RestClient.RequestBodyUriSpec.class, Answers.RETURNS_SELF);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(restClient.post()).thenReturn(spec);
        when(spec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(EmbedResponse.class))
                .thenReturn(new EmbedResponse("stub", 384, List.of(remoteVecA)))
                .thenReturn(new EmbedResponse("stub", 384, List.of(remoteVecB)))
                .thenReturn(new EmbedResponse("stub", 384, List.of(remoteVecC)))
                .thenReturn(new EmbedResponse("stub", 384, List.of(remoteVecB)));

        RemoteEmbeddingClient client = new RemoteEmbeddingClient(restClient, props);

        float[] firstA = client.embed("Tư vấn");
        assertThat(firstA[0]).isEqualTo(1.0f);
        assertThat(client.cacheMissCount()).isEqualTo(1L);
        assertThat(client.cacheHitCount()).isEqualTo(0L);

        // Mutate caller's returned array — cached array MUST remain uncorrupted
        firstA[0] = -999.0f;
        float[] secondA = client.embed("Tư vấn");
        assertThat(secondA[0]).isEqualTo(1.0f);
        assertThat(client.cacheHitCount()).isEqualTo(1L);
        assertThat(client.cacheMissCount()).isEqualTo(1L);

        // Insert B, touch A (so B becomes LRU), then insert C -> B must be evicted while A stays cached
        assertThat(client.embed("thi thoang bị lại")[1]).isEqualTo(1.0f);
        assertThat(client.embed("Tư vấn")[0]).isEqualTo(1.0f); // refreshes A as MRU
        assertThat(client.embed("Nhưng sao đắt thế chỗ khác gội đầu có 50k")[2]).isEqualTo(1.0f);
        assertThat(client.cacheSize()).isEqualTo(2);

        long hitsBeforeRecheckA = client.cacheHitCount();
        assertThat(client.embed("Tư vấn")[0]).isEqualTo(1.0f);
        assertThat(client.cacheHitCount()).isEqualTo(hitsBeforeRecheckA + 1L);

        long missesBeforeRecheckB = client.cacheMissCount();
        assertThat(client.embed("thi thoang bị lại")[1]).isEqualTo(1.0f);
        assertThat(client.cacheMissCount()).isEqualTo(missesBeforeRecheckB + 1L);
    }

    @Test
    void transientHttpFailureDoesNotPoisonQueryCache() {
        List<Float> recoveredRemoteVec = new ArrayList<>();
        for (int i = 0; i < 384; i++) {
            recoveredRemoteVec.add(i == 7 ? 1.0f : 0.0f);
        }

        RestClient restClient = mock(RestClient.class);
        RestClient.RequestBodyUriSpec spec = mock(RestClient.RequestBodyUriSpec.class, Answers.RETURNS_SELF);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(restClient.post()).thenReturn(spec);
        when(spec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(EmbedResponse.class))
                .thenThrow(new RestClientResponseException(
                        "502 Bad Gateway",
                        HttpStatus.BAD_GATEWAY.value(),
                        "Bad Gateway",
                        HttpHeaders.EMPTY,
                        "upstream error".getBytes(),
                        null))
                .thenReturn(new EmbedResponse("paraphrase-multilingual-MiniLM-L12-v2", 384, List.of(recoveredRemoteVec)));

        RemoteEmbeddingClient client = new RemoteEmbeddingClient(restClient, props);

        float[] degradedOut = client.embed("Tư vấn");
        float[] stubVec = new StubEmbeddingClient(props).embed("Tư vấn");
        assertThat(degradedOut).containsExactly(stubVec);
        assertThat(client.cacheSize()).isZero();

        float[] recoveredOut = client.embed("Tư vấn");
        assertThat(recoveredOut[7]).isEqualTo(1.0f);
        assertThat(client.cacheSize()).isEqualTo(1);
    }

    // --- helpers: dựng fluent chain của RestClient vừa đủ để chạy 2 nhánh ---
    // Dùng Answers.RETURNS_SELF cho RequestBodyUriSpec để mọi method fluent
    // (post/uri/header/contentType/body) trả về chính nó — tránh phải khớp
    // varargs của header(String, String...) vốn mong manh với giá trị null.

    private RestClient mockRestClientReturning(EmbedResponse resp) {
        RestClient restClient = mock(RestClient.class);
        RestClient.RequestBodyUriSpec spec =
                mock(RestClient.RequestBodyUriSpec.class, Answers.RETURNS_SELF);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.post()).thenReturn(spec);
        when(spec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(EmbedResponse.class)).thenReturn(resp);
        return restClient;
    }

    private RestClient mockRestClientThrowing(RuntimeException toThrow) {
        RestClient restClient = mock(RestClient.class);
        RestClient.RequestBodyUriSpec spec =
                mock(RestClient.RequestBodyUriSpec.class, Answers.RETURNS_SELF);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.post()).thenReturn(spec);
        when(spec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(EmbedResponse.class)).thenThrow(toThrow);
        return restClient;
    }
}
