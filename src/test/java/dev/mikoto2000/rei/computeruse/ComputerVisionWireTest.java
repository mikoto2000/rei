package dev.mikoto2000.rei.computeruse;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/** Exercises real SDK serialization against an in-memory HTTP transport, never a live provider. */
class ComputerVisionWireTest {
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();

  @Test void showUiOverviewAndRefinementSendInstructionImageThenTarget() throws Exception {
    String target = "X post input field with placeholder 'いまどうしてる？'";
    String response = """
        {"id":"test","object":"chat.completion","created":0,"model":"showui-2b",
         "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"[0.26,0.12]"}}]}
        """;
    var requests=new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
    var defaults=options("showui-2b").mutate().maxCompletionTokens(4096).build();
    var model=model(defaults,response,requests);
    var observation = SpringAiComputerVisionModelTest.observation();
    var display = observation.screenshot().displays().getFirst();
    var vision = TestGroundingModels.create(o -> new ComputerAction.Click(
        new ComputerAction.Target(display.geometry().id(), 10, 10, target), .9, ComputerAction.Risk.LOW),
        model, defaults::mutate, () -> false);
    assertInstanceOf(ComputerAction.Click.class, vision.decide(observation));
    assertEquals(2,requests.size());
    for(var request:requests) {
      assertEquals("showui-2b",request.path("model").asText());
      var messages=request.path("messages");assertEquals(1,messages.size());assertEquals("user",messages.get(0).path("role").asText());
      var content=messages.get(0).path("content");assertEquals(3,content.size());
      assertEquals(ShowUiRequestInterceptor.INSTRUCTION,content.get(0).path("text").asText());
      assertEquals("image_url",content.get(1).path("type").asText());
      assertTrue(content.get(1).path("image_url").path("url").asText().startsWith("data:image/png;base64,"));
      assertEquals("text",content.get(2).path("type").asText());assertEquals(target,content.get(2).path("text").asText());
      assertEquals(128,request.path("max_tokens").asInt());assertFalse(request.has("max_completion_tokens"));
      assertFalse(request.has("tools"));assertFalse(request.has("tool_choice"));assertFalse(request.has("response_format"));
    }
  }

  @Test void visionRequestOmitsToolFieldsAndRetainsImageAndStrictOutputSchema() throws Exception {
    String response = JSON.writeValueAsString(Map.of("id", "test", "object", "chat.completion", "created", 0,
        "model", "vision", "choices", List.of(Map.of("index", 0, "finish_reason", "stop",
            "message", Map.of("role", "assistant", "content", ActionValidationTest.json("DONE", "reason", "\"Visible\""))))));
    var requests=new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
    var defaults=options("vision").mutate().maxCompletionTokens(2048).temperature(.2).build();
    var vision = new SpringAiComputerVisionModel(model(defaults,response,requests), defaults::mutate, () -> false, 0);
    assertInstanceOf(ComputerAction.Done.class, vision.decide(SpringAiComputerVisionModelTest.observation()));
    assertEquals(1,requests.size());var request=requests.getFirst();
    assertEquals("vision",request.path("model").asText());assertEquals(2048,request.path("max_completion_tokens").asInt());
    assertEquals(.2,request.path("temperature").asDouble());assertFalse(request.has("tools"));assertFalse(request.has("tool_choice"));
    assertEquals("json_schema",request.path("response_format").path("type").asText());
    assertTrue(request.path("response_format").path("json_schema").path("strict").asBoolean());
    assertTrue(request.path("response_format").path("json_schema").path("schema").has("properties"));
    assertEquals("image_url",request.path("messages").get(1).path("content").get(1).path("type").asText());
  }

  private static OpenAiChatOptions options(String model) {
    return OpenAiChatOptions.builder().baseUrl("https://vision.invalid/v1").apiKey("test").model(model).maxRetries(0).build();
  }

  private static OpenAiChatModel model(OpenAiChatOptions options,String response,List<com.fasterxml.jackson.databind.JsonNode> requests) {
    return OpenAiChatModel.builder().options(options).httpClientBuilderCustomizer(builder -> builder
        .interceptor(new dev.mikoto2000.rei.llm.ShowUiSdkRequestInterceptor())
        .interceptor(chain -> {
          var request=chain.request();assertEquals("https://vision.invalid/v1/chat/completions",request.url().toString());
          assertEquals("POST",request.method());
          var body=new okio.Buffer();assertNotNull(request.body());request.body().writeTo(body);
          var bytes=body.readByteArray();assertEquals(bytes.length,request.body().contentLength());
          requests.add(JSON.readTree(bytes));
          return new okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
              .body(okhttp3.ResponseBody.create(response,okhttp3.MediaType.get("application/json"))).build();
        })).build();
  }
}
