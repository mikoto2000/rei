package dev.mikoto2000.rei.computeruse;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Exercises real SDK serialization against an in-memory HTTP transport, never a live provider. */
class ComputerVisionWireTest {
  @Test void visionRequestOmitsToolFieldsAndRetainsImageAndStrictOutputSchema() throws Exception {
    var builder = RestClient.builder();
    var server = MockRestServiceServer.bindTo(builder).build();
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    String response = json.writeValueAsString(Map.of("id", "test", "object", "chat.completion", "created", 0,
        "model", "vision", "choices", java.util.List.of(Map.of("index", 0, "finish_reason", "stop",
            "message", Map.of("role", "assistant", "content", ActionValidationTest.json("DONE", "reason", "\"Visible\""))))));
    server.expect(requestTo("https://vision.invalid/v1/chat/completions"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.tools").doesNotExist())
        .andExpect(jsonPath("$.tool_choice").doesNotExist())
        .andExpect(jsonPath("$.response_format.type").value("json_schema"))
        .andExpect(jsonPath("$.response_format.json_schema.strict").value(true))
        .andExpect(jsonPath("$.messages[1].content[1].type").value("image_url"))
        .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    var api = OpenAiApi.builder().baseUrl("https://vision.invalid").apiKey("test")
        .restClientBuilder(builder).build();
    var model = OpenAiChatModel.builder().openAiApi(api)
        .defaultOptions(OpenAiChatOptions.builder().model("vision").build()).build();
    var vision = new SpringAiComputerVisionModel(model, OpenAiChatOptions::builder, () -> false, 0);
    assertInstanceOf(ComputerAction.Done.class, vision.decide(SpringAiComputerVisionModelTest.observation()));
    server.verify();
  }
}
