package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.NamespaceTool;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputItem;
import io.micrometer.observation.ObservationRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.setup.OpenAiSetup;

/** Characterizes the bundled SDK at a loopback HTTP boundary; never uses real credentials. */
class ChatGptPlanSdkFeasibilityTest {
  @Test
  void typedSdkCanSendThePlanWhitelistAndNamespaceWithoutRewritingHttpJson() throws Exception {
    var request = new AtomicReference<JsonNode>();
    var authorization = new AtomicReference<String>();
    var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/responses",
        exchange -> {
          request.set(new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes()));
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          var bytes =
              ("data: {\"type\":\"response.completed\",\"sequence_number\":0,\"response\":"
                      + "{\"id\":\"resp_fixture\",\"object\":\"response\",\"created_at\":0,"
                      + "\"status\":\"completed\",\"model\":\"fixture-model\",\"output\":[]}}\n\n")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, bytes.length);
          try (var body = exchange.getResponseBody()) {
            body.write(bytes);
          } finally {
            exchange.close();
          }
        });
    server.start();
    com.openai.client.OpenAIClient client = null;
    try {
      client =
          OpenAiSetup.setupSyncClient(
              "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
              "fixture-oauth-access",
              null,
              null,
              null,
              null,
              false,
              false,
              "fixture-model",
              Duration.ofSeconds(5),
              0,
              null,
              null,
              ObservationRegistry.NOOP,
              null,
              List.of());
      var tool =
          NamespaceTool.Tool.Function.builder()
              .name("searchTools")
              .parameters(
                  JsonValue.from(
                      Map.of(
                          "type", "object", "properties", Map.of(), "additionalProperties", false)))
              .build();
      var params =
          ResponseCreateParams.builder()
              .model("fixture-model")
              .instructions("fixture system instruction")
              .inputOfResponse(
                  List.of(
                      ResponseInputItem.ofEasyInputMessage(
                          EasyInputMessage.builder()
                              .role(EasyInputMessage.Role.USER)
                              .content("fixture")
                              .build())))
              .addTool(
                  NamespaceTool.builder()
                      .name("rei")
                      .description("Rei tools")
                      .addTool(tool)
                      .build())
              .store(false)
              .build();
      try (var stream = client.responses().createStreaming(params)) {
        assertThat(stream.stream().toList())
            .singleElement()
            .satisfies(event -> assertThat(event.isCompleted()).isTrue());
      }
      var sent = request.get();
      assertThat(authorization.get()).isEqualTo("Bearer fixture-oauth-access");
      assertThat(sent.properties())
          .extracting(Map.Entry::getKey)
          .containsExactlyInAnyOrder("model", "instructions", "input", "tools", "store", "stream");
      assertThat(sent.path("store").asBoolean()).isFalse();
      assertThat(sent.path("stream").asBoolean()).isTrue();
      assertThat(sent.path("input").isArray()).isTrue();
      assertThat(sent.at("/input/0/role").asText()).isEqualTo("user");
      assertThat(sent.at("/tools/0/type").asText()).isEqualTo("namespace");
      assertThat(sent.at("/tools/0/name").asText()).isEqualTo("rei");
      assertThat(sent.at("/tools/0/tools/0/name").asText()).isEqualTo("searchTools");
    } finally {
      try {
        if (client != null) client.close();
      } finally {
        server.stop(0);
      }
    }
  }
}
