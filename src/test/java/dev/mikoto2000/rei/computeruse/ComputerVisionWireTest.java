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
  @Test void showUiOverviewAndRefinementSendInstructionImageThenTarget() throws Exception {
    var builder = RestClient.builder().requestInterceptor(new ShowUiRequestInterceptor());
    var server = MockRestServiceServer.bindTo(builder).build();
    String target = "X post input field with placeholder 'いまどうしてる？'";
    String response = """
        {"id":"test","object":"chat.completion","created":0,"model":"showui-2b",
         "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"[0.26,0.12]"}}]}
        """;
    for (int stage = 0; stage < 2; stage++) {
      server.expect(requestTo("https://vision.invalid/v1/chat/completions"))
          .andExpect(jsonPath("$.messages.length()").value(1))
          .andExpect(jsonPath("$.messages[0].role").value("user"))
          .andExpect(jsonPath("$.messages[0].content.length()").value(3))
          .andExpect(jsonPath("$.messages[0].content[0].text").value(ShowUiRequestInterceptor.INSTRUCTION))
          .andExpect(jsonPath("$.messages[0].content[1].type").value("image_url"))
          .andExpect(jsonPath("$.messages[0].content[1].image_url.url").value(org.hamcrest.Matchers.startsWith("data:image/png;base64,")))
          .andExpect(jsonPath("$.messages[0].content[2].type").value("text"))
          .andExpect(jsonPath("$.messages[0].content[2].text").value(target))
          .andExpect(jsonPath("$.max_tokens").value(128))
          .andExpect(jsonPath("$.tools").doesNotExist())
          .andExpect(jsonPath("$.response_format").doesNotExist())
          .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }
    var api = OpenAiApi.builder().baseUrl("https://vision.invalid").apiKey("test").restClientBuilder(builder).build();
    var model = OpenAiChatModel.builder().openAiApi(api)
        .defaultOptions(OpenAiChatOptions.builder().model("showui-2b").build()).build();
    var observation = SpringAiComputerVisionModelTest.observation();
    var display = observation.screenshot().displays().getFirst();
    var vision = TestGroundingModels.create(o -> new ComputerAction.Click(
        new ComputerAction.Target(display.geometry().id(), 10, 10, target), .9, ComputerAction.Risk.LOW),
        model, OpenAiChatOptions::builder, () -> false);
    assertInstanceOf(ComputerAction.Click.class, vision.decide(observation));
    server.verify();
  }

  @Test void visionRequestOmitsToolFieldsAndRetainsImageAndStrictOutputSchema() throws Exception {
    var builder = RestClient.builder().requestInterceptor(new ShowUiRequestInterceptor());
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
