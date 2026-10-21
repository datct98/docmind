package com.example.docmind.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Bean RestClient cho DocServiceClient (base-url + timeouts từ config).
 *
 * <p>Dùng {@link SimpleClientHttpRequestFactory} (HttpURLConnection, có sẵn trong
 * spring-web core) thay vì JDK HttpClient: JDK HttpClient tuần tự hoá multipart
 * KHÔNG tương thích với cách FastAPI/Starlette parse form (part bị hỏng ->
 * doc-service trả 422 cho /v1/parse dù field 'file' đã gửi). HttpURLConnection
 * build multipart boundary chuẩn nên FastAPI nhận diện đúng field file.
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient docServiceRestClient(DocServiceProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(props.getReadTimeoutMs()));

        var builder = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(factory);
        if (props.getInternalToken() != null && !props.getInternalToken().isBlank()) {
            builder.defaultHeader("X-Internal-Token", props.getInternalToken().trim());
        }
        return builder.build();
    }
}
