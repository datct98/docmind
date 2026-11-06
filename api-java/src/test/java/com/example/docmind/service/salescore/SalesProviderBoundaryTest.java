package com.example.docmind.service.salescore;

import com.example.docmind.config.LlmProperties;
import com.example.docmind.service.llm.OpenAiCompatibleLlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.hamcrest.Matchers.*;

class SalesProviderBoundaryTest {
    @Test void actualProviderPayloadSeparatesSystemPolicyFromUntrustedBusinessData() {
        var props = new LlmProperties(); props.setProvider("custom"); props.setModel("test"); props.setBaseUrl("https://example.test/v1");
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var json = new ObjectMapper();
        var client = new OpenAiCompatibleLlmClient(props, json, builder.build());
        var input = SalesCoreTest.input("BEAUTY_SPA", "CUSTOMER_UNTRUSTED_CONTENT");
        var profile = new IndustryProfileRegistry().load("BEAUTY_SPA");
        for (String prompt : java.util.List.of(SalesPromptBuilder.build(input, profile),
                SalesSemanticReview.prompt(input, profile, json.readValue(SalesCoreTest.answer("Xin chào", "ANSWER", "[]"), SalesContracts.TurnDecision.class)))) {
            server.expect(requestTo("https://example.test/v1/chat/completions"))
                    .andExpect(jsonPath("$.messages[0].role").value("system"))
                    .andExpect(jsonPath("$.messages[0].content").value(not(containsString("CUSTOMER_UNTRUSTED_CONTENT"))))
                    .andExpect(jsonPath("$.messages[1].role").value("user"))
                    .andExpect(jsonPath("$.messages[1].content").value(containsString("CUSTOMER_UNTRUSTED_CONTENT")))
                    .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}", MediaType.APPLICATION_JSON));
        }
        client.complete(SalesPromptBuilder.build(input, profile));
        client.complete(SalesSemanticReview.prompt(input, profile, json.readValue(SalesCoreTest.answer("Xin chào", "ANSWER", "[]"), SalesContracts.TurnDecision.class)));
        server.verify();
    }
}
