package com.example.docmind.service.llm;

import com.example.docmind.config.LlmProperties;
import com.example.docmind.repository.FeedbackLogRepository;
import com.example.docmind.repository.LearnedRuleRepository;
import com.example.docmind.service.MetaLearningService;
import com.example.docmind.service.embedding.EmbeddingClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import org.springframework.http.HttpStatus;

/**
 * Hermetic unit test suite cho {@link OpenAiCompatibleLlmClient} và tích hợp MetaLearningService.
 * Sử dụng MockRestServiceServer để kiểm thử hoàn toàn độc lập, 0 kết nối mạng ngoài.
 */
class OpenAiCompatibleLlmClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testDeepSeekChatResponseParsingAndHeaders() {
        LlmProperties props = new LlmProperties();
        props.setProvider("deepseek");
        props.setApiKey("sk-deepseek-test-key-12345");

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(props, objectMapper, builder.build());

        server.expect(requestTo("https://api.deepseek.com/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer sk-deepseek-test-key-12345"))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.model").value("deepseek-chat"))
                .andExpect(jsonPath("$.messages[0].content").value("Câu hỏi: Giá liệu trình trị mụn là bao nhiêu?"))
                .andExpect(jsonPath("$.temperature").value(0.1))
                .andRespond(withSuccess(
                        """
                        {
                          "id": "chatcmpl-test-01",
                          "choices": [
                            {
                              "index": 0,
                              "message": {
                                "role": "assistant",
                                "content": "Giá liệu trình trị mụn là 1.500.000 VNĐ [1]."
                              },
                              "finish_reason": "stop"
                            }
                          ]
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));

        String answer = client.complete("Câu hỏi: Giá liệu trình trị mụn là bao nhiêu?");
        assertThat(answer).isEqualTo("Giá liệu trình trị mụn là 1.500.000 VNĐ [1].");
        assertThat(client.name()).isEqualTo("deepseek:deepseek-chat");
        server.verify();
    }

    @Test
    void testDeepSeekR1ThinkTagStripping() {
        LlmProperties props = new LlmProperties();
        props.setProvider("deepseek");
        props.setModel("deepseek-reasoner");
        props.setApiKey("sk-r1-secret");

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(props, objectMapper, builder.build());

        server.expect(requestTo("https://api.deepseek.com/v1/chat/completions"))
                .andRespond(withSuccess(
                        """
                        {
                          "choices": [
                            {
                              "message": {
                                "role": "assistant",
                                "content": "<think>\\nĐang suy luận logic...\\nTra cứu bảng giá combo spa...\\n</think>\\nGiá combo toàn diện là 3.000.000 VNĐ."
                              }
                            }
                          ]
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));

        String answer = client.complete("Câu hỏi: Giá combo toàn diện?");
        assertThat(answer).isEqualTo("Giá combo toàn diện là 3.000.000 VNĐ.");
        assertThat(answer).doesNotContain("<think>");
        assertThat(answer).doesNotContain("</think>");
        assertThat(answer).doesNotContain("Đang suy luận logic");
        server.verify();
    }

    @Test
    void testDeepSeekReasoningContentFallbackWhenContentEmpty() {
        LlmProperties props = new LlmProperties();
        props.setProvider("deepseek");
        props.setModel("deepseek-reasoner");

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(props, objectMapper, builder.build());

        server.expect(requestTo("https://api.deepseek.com/v1/chat/completions"))
                .andRespond(withSuccess(
                        """
                        {
                          "choices": [
                            {
                              "message": {
                                "role": "assistant",
                                "content": "",
                                "reasoning_content": "<think>Phân tích dữ kiện</think>Nội dung câu trả lời trích xuất từ reasoning_content."
                              }
                            }
                          ]
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));

        String answer = client.complete("Câu hỏi: Test reasoning fallback?");
        assertThat(answer).isEqualTo("Nội dung câu trả lời trích xuất từ reasoning_content.");
        server.verify();
    }

    @Test
    void testGrokResponseAndEndpoint() {
        LlmProperties props = new LlmProperties();
        props.setProvider("grok");
        props.setApiKey("xai-grok-test-key");

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(props, objectMapper, builder.build());

        server.expect(requestTo("https://api.x.ai/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer xai-grok-test-key"))
                .andRespond(withSuccess(
                        """
                        {
                          "choices": [
                            {
                              "message": {
                                "role": "assistant",
                                "content": "Grok trả lời: Bạn cần 5-9 buổi cho Combo Toàn Diện."
                              }
                            }
                          ]
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));

        String answer = client.complete("Câu hỏi: Cần bao nhiêu buổi?");
        assertThat(answer).isEqualTo("Grok trả lời: Bạn cần 5-9 buổi cho Combo Toàn Diện.");
        assertThat(client.name()).isEqualTo("grok:grok-2-latest");
        server.verify();
    }

    @Test
    void testOpenAiResponse() {
        LlmProperties props = new LlmProperties();
        props.setProvider("openai");
        props.setApiKey("sk-openai-test-key");

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(props, objectMapper, builder.build());

        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer sk-openai-test-key"))
                .andRespond(withSuccess(
                        """
                        {
                          "choices": [
                            {
                              "message": {
                                "role": "assistant",
                                "content": "OpenAI gpt-4o-mini trả lời chính xác."
                              }
                            }
                          ]
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));

        String answer = client.complete("Câu hỏi: Hello OpenAI?");
        assertThat(answer).isEqualTo("OpenAI gpt-4o-mini trả lời chính xác.");
        assertThat(client.name()).isEqualTo("openai:gpt-4o-mini");
        server.verify();
    }

    @Test
    void testGeminiOpenAiModeResponse() {
        LlmProperties props = new LlmProperties();
        props.setProvider("gemini");
        props.setApiKey("AIzaSy-gemini-test-key");

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(props, objectMapper, builder.build());

        server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer AIzaSy-gemini-test-key"))
                .andRespond(withSuccess(
                        """
                        {
                          "choices": [
                            {
                              "message": {
                                "role": "assistant",
                                "content": "Gemini 2.5 Flash trả lời qua OpenAI-compatible endpoint."
                              }
                            }
                          ]
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));

        String answer = client.complete("Câu hỏi: Hello Gemini OpenAI mode?");
        assertThat(answer).isEqualTo("Gemini 2.5 Flash trả lời qua OpenAI-compatible endpoint.");
        assertThat(client.name()).isEqualTo("gemini:gemini-2.5-flash");
        server.verify();
    }

    @Test
    void testOpenRouterResponse() {
        LlmProperties props = new LlmProperties();
        props.setProvider("openrouter");
        props.setApiKey("sk-or-test-key");

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(props, objectMapper, builder.build());

        server.expect(requestTo("https://openrouter.ai/api/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer sk-or-test-key"))
                .andRespond(withSuccess(
                        """
                        {
                          "choices": [
                            {
                              "message": {
                                "role": "assistant",
                                "content": "OpenRouter routed response thành công."
                              }
                            }
                          ]
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));

        String answer = client.complete("Câu hỏi: Hello OpenRouter?");
        assertThat(answer).isEqualTo("OpenRouter routed response thành công.");
        assertThat(client.name()).isEqualTo("openrouter:openai/gpt-4o-mini");
        server.verify();
    }

    @Test
    void testCustomProviderWithTrailingSlashNormalization() {
        LlmProperties props = new LlmProperties();
        props.setProvider("custom");
        props.setBaseUrl("http://localhost:8000/v1/");
        props.setModel("meta-llama-3-8b");

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(props, objectMapper, builder.build());

        server.expect(requestTo("http://localhost:8000/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {
                          "choices": [
                            {
                              "message": {
                                "role": "assistant",
                                "content": "Custom vLLM response."
                              }
                            }
                          ]
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));

        String answer = client.complete("Câu hỏi: Custom model test?");
        assertThat(answer).isEqualTo("Custom vLLM response.");
        assertThat(client.name()).isEqualTo("custom:meta-llama-3-8b");
        server.verify();
    }

    @Test
    void testCustomProviderAlreadyHavingChatCompletionsPath() {
        LlmProperties props = new LlmProperties();
        props.setProvider("custom");
        props.setBaseUrl("http://my-host:8080/v1/chat/completions");
        props.setModel("my-model");

        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(props, objectMapper);
        assertThat(client.getEndpoint()).isEqualTo("http://my-host:8080/v1/chat/completions");
    }

    @Test
    void testErrorHandling500ServerFallback() {
        LlmProperties props = new LlmProperties();
        props.setProvider("deepseek");

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(props, objectMapper, builder.build());

        server.expect(requestTo("https://api.deepseek.com/v1/chat/completions"))
                .andRespond(withServerError());

        String answer = client.complete("Câu hỏi: Chính sách bảo hành dịch vụ?");
        assertThat(answer).contains("[deepseek-error]");
        assertThat(answer).contains("Chính sách bảo hành dịch vụ?");
        server.verify();
    }

    @Test
    void testErrorHandling401Unauthorized() {
        LlmProperties props = new LlmProperties();
        props.setProvider("openai");
        props.setApiKey("invalid-key");

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(props, objectMapper, builder.build());

        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON));

        String answer = client.complete("Câu hỏi: Kiểm tra 401?");
        assertThat(answer).contains("[openai-error]");
        server.verify();
    }

    @Test
    void testErrorHandlingEmptyChoicesFallback() {
        LlmProperties props = new LlmProperties();
        props.setProvider("deepseek");

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(props, objectMapper, builder.build());

        server.expect(requestTo("https://api.deepseek.com/v1/chat/completions"))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        String answer = client.complete("Câu hỏi: Rỗng choices?");
        assertThat(answer).contains("[deepseek-error]");
        assertThat(answer).contains("Không tìm thấy trường 'choices'");
        server.verify();
    }

    @Test
    void testLlmPropertiesAutoPresets() {
        LlmProperties props = new LlmProperties();

        props.setProvider("deepseek");
        assertThat(props.resolveBaseUrl()).isEqualTo("https://api.deepseek.com/v1");
        assertThat(props.resolveModel()).isEqualTo("deepseek-chat");

        props.setProvider("grok");
        assertThat(props.resolveBaseUrl()).isEqualTo("https://api.x.ai/v1");
        assertThat(props.resolveModel()).isEqualTo("grok-2-latest");

        props.setProvider("openai");
        assertThat(props.resolveBaseUrl()).isEqualTo("https://api.openai.com/v1");
        assertThat(props.resolveModel()).isEqualTo("gpt-4o-mini");

        props.setProvider("gemini");
        assertThat(props.resolveBaseUrl()).isEqualTo("https://generativelanguage.googleapis.com/v1beta/openai");
        assertThat(props.resolveModel()).isEqualTo("gemini-2.5-flash");

        props.setProvider("openrouter");
        assertThat(props.resolveBaseUrl()).isEqualTo("https://openrouter.ai/api/v1");
        assertThat(props.resolveModel()).isEqualTo("openai/gpt-4o-mini");

        props.setProvider("ollama");
        assertThat(props.resolveBaseUrl()).isEqualTo("http://localhost:11434");
        assertThat(props.resolveModel()).isEqualTo("qwen2.5:7b");

        // Custom override
        props.setProvider("custom");
        props.setBaseUrl("http://custom-llm:8000/v1");
        props.setModel("custom-qwen");
        assertThat(props.resolveBaseUrl()).isEqualTo("http://custom-llm:8000/v1");
        assertThat(props.resolveModel()).isEqualTo("custom-qwen");
    }

    @Test
    void testLlmPropertiesIgnoresLeftoverOllamaConfigWhenCloudProviderSelected() {
        LlmProperties props = new LlmProperties();

        // Simulate user switching LLM_PROVIDER=grok while LLM_BASE_URL and LLM_MODEL still have Ollama values
        props.setProvider("grok");
        props.setBaseUrl("http://host.docker.internal:11434");
        props.setModel("qwen2.5:7b");
        assertThat(props.resolveBaseUrl()).isEqualTo("https://api.x.ai/v1");
        assertThat(props.resolveModel()).isEqualTo("grok-2-latest");

        // Also test xai alias and qwen2.5:latest
        props.setProvider("xai");
        props.setBaseUrl("http://127.0.0.1:11434");
        props.setModel("qwen2.5:latest");
        assertThat(props.getNormalizedProvider()).isEqualTo("grok");
        assertThat(props.resolveBaseUrl()).isEqualTo("https://api.x.ai/v1");
        assertThat(props.resolveModel()).isEqualTo("grok-2-latest");

        // Also test localhost:11434 with deepseek
        props.setProvider("deepseek");
        props.setBaseUrl("http://localhost:11434");
        props.setModel("qwen2.5:3b");
        assertThat(props.resolveBaseUrl()).isEqualTo("https://api.deepseek.com/v1");
        assertThat(props.resolveModel()).isEqualTo("deepseek-chat");

        // When explicit valid Grok model is set alongside leftover Ollama URL, preserve Grok model and fix URL
        props.setProvider("grok");
        props.setBaseUrl("http://localhost:11434");
        props.setModel("grok-3-mini");
        assertThat(props.resolveBaseUrl()).isEqualTo("https://api.x.ai/v1");
        assertThat(props.resolveModel()).isEqualTo("grok-3-mini");
    }

    @Test
    void testMetaLearningJsonRuleExtractionIntegrationWithDeepSeek() {
        LlmProperties props = new LlmProperties();
        props.setProvider("deepseek");
        props.setApiKey("sk-deepseek-rule-extractor");

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(props, objectMapper, builder.build());

        server.expect(requestTo("https://api.deepseek.com/v1/chat/completions"))
                .andRespond(withSuccess(
                        """
                        {
                          "choices": [
                            {
                              "message": {
                                "role": "assistant",
                                "content": "{\\"category\\":\\"PRICING_CALCULATION\\",\\"trigger_condition\\":\\"Khi khách hàng hỏi về chiết khấu theo doanh số\\",\\"root_cause\\":\\"Nhầm lẫn bậc thang chiết khấu tháng và quý\\",\\"generalized_rule\\":\\"Luôn kiểm tra kỹ ngưỡng doanh số theo Quý của đại lý trước khi xác định mức chiết khấu.\\",\\"retrieval_keywords\\":[\\"chiết khấu\\",\\"đại lý\\",\\"doanh số\\"]}"
                              }
                            }
                          ]
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));

        MetaLearningService metaService = new MetaLearningService(
                mock(LearnedRuleRepository.class),
                mock(FeedbackLogRepository.class),
                mock(EmbeddingClient.class),
                client,
                objectMapper
        );

        MetaLearningService.ExtractedRule rule = metaService.extractRule(
                "Đại lý cấp 1 đạt 500 triệu trong Quý 4 được chiết khấu bao nhiêu?",
                "Đại lý cấp 1 được chiết khấu 15%.",
                "Sai rồi, theo bảng chính sách Quý 4, doanh số từ 500 triệu trở lên được 18% chứ không phải 15%.",
                "Bảng chính sách Q4: Đại lý cấp 1 doanh số >= 500 triệu hưởng chiết khấu 18%."
        );

        assertThat(rule).isNotNull();
        assertThat(rule.category()).isEqualTo("PRICING_CALCULATION");
        assertThat(rule.generalizedRule()).contains("ngưỡng doanh số theo Quý");
        assertThat(rule.retrievalKeywords()).contains("chiết khấu", "đại lý");
        server.verify();
    }

    @Test
    void testGrok45ReasoningEffortAndEmptyReasoningRetry() {
        LlmProperties props = new LlmProperties();
        props.setProvider("grok");
        props.setModel("grok-4.5");
        props.setBaseUrl("https://gpt.teamsoclo.site/v1");
        props.setApiKey("sk-third-party-key");

        assertThat(props.resolveReasoningEffort()).isEqualTo("low");

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(props, objectMapper, builder.build());

        // First call sends reasoning_effort=low, returns empty content with reasoning_tokens > 0 -> triggers retry
        server.expect(requestTo("https://gpt.teamsoclo.site/v1/chat/completions"))
                .andExpect(jsonPath("$.model").value("grok-4.5"))
                .andExpect(jsonPath("$.reasoning_effort").value("low"))
                .andRespond(withSuccess(
                        """
                        {
                          "choices": [
                            {
                              "message": {
                                "role": "assistant",
                                "content": ""
                              }
                            }
                          ],
                          "usage": {
                            "prompt_tokens": 1200,
                            "completion_tokens": 86,
                            "completion_tokens_details": {
                              "reasoning_tokens": 86
                            }
                          }
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));

        // Second call (automatic retry without reasoning_effort) returns valid content
        server.expect(requestTo("https://gpt.teamsoclo.site/v1/chat/completions"))
                .andExpect(jsonPath("$.model").value("grok-4.5"))
                .andRespond(withSuccess(
                        """
                        {
                          "choices": [
                            {
                              "message": {
                                "role": "assistant",
                                "content": "Dạ em chào Chị ạ! Gói Trị Mụn giá 1.500.000 VNĐ/buổi."
                              }
                            }
                          ],
                          "usage": {
                            "prompt_tokens": 1200,
                            "completion_tokens": 45,
                            "completion_tokens_details": {
                              "reasoning_tokens": 18
                            }
                          }
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));

        String answer = client.complete("Câu hỏi: Tư vấn gói trị mụn?");
        assertThat(answer).isEqualTo("Dạ em chào Chị ạ! Gói Trị Mụn giá 1.500.000 VNĐ/buổi.");
        server.verify();
    }

    @Test
    void testUpstreamGatewayTimeout504RetriesAndSucceeds() {
        LlmProperties props = new LlmProperties();
        props.setProvider("grok");
        props.setModel("grok-4.5");
        props.setBaseUrl("https://gpt.teamsoclo.site/v1");
        props.setApiKey("sk-third-party-key");

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(props, objectMapper, builder.build());

        // Attempt 1 hits a stalled upstream channel returning 504 Gateway Timeout
        server.expect(requestTo("https://gpt.teamsoclo.site/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT));

        // Attempt 2 automatically retries and succeeds on a healthy upstream channel
        server.expect(requestTo("https://gpt.teamsoclo.site/v1/chat/completions"))
                .andRespond(withSuccess(
                        """
                        {
                          "choices": [
                            {
                              "message": {
                                "role": "assistant",
                                "content": "Dạ gói Detox Oxy có giá 650.000 VNĐ/buổi ạ."
                              }
                            }
                          ]
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));

        String answer = client.complete("Câu hỏi: Giá gói Detox Oxy?");
        assertThat(answer).isEqualTo("Dạ gói Detox Oxy có giá 650.000 VNĐ/buổi ạ.");
        server.verify();
    }
}
