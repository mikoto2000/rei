package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

class PinnedChatModelsTest {
  @Test
  void cachedModelsKeepTheirOwnReceiverAndReadTheFreshKey() throws Exception {
    try (var first = new Fixture(); var second = new Fixture()) {
      var key = new AtomicReference<>("PRIVATE_FIRST");
      try (var models = new PinnedChatModels(pin -> key.get(), pin -> {}, null, 2)) {
        var a = first.pin("first", "model-a", LlmAuthentication.API_KEY);
        var b = second.pin("second", "model-b", LlmAuthentication.NONE);
        var aModel = models.model(a);
        assertThat(models.model(a)).isSameAs(aModel);
        infer(aModel, a);
        infer(models.model(b), b);
        key.set("PRIVATE_REFRESHED");
        infer(aModel, a);
        assertThat(first.headers).containsExactly("Bearer PRIVATE_FIRST", "Bearer PRIVATE_REFRESHED");
        assertThat(second.headers).containsExactly("<none>");
        assertThat(first.bodies).allSatisfy(body -> {
          assertThat(body.path("model").asText()).isEqualTo("model-a");
          assertThat(body.path("max_tokens").asInt()).isEqualTo(60);
        });
        assertThat(second.bodies.getFirst().path("model").asText()).isEqualTo("model-b");
      }
    }
  }

  @Test
  void evictionDoesNotCloseTheTransportOfAnAlreadyDispatchedModel() throws Exception {
    try (var fixture = new Fixture(); var models = new PinnedChatModels(pin -> null, pin -> {}, null, 1)) {
      var first = fixture.pin("first", "model-a", LlmAuthentication.NONE);
      var held = models.model(first);
      infer(models.model(fixture.pin("second", "model-b", LlmAuthentication.NONE)),
          fixture.pin("second", "model-b", LlmAuthentication.NONE));
      assertThat(models.model(first)).isNotSameAs(held);
      infer(held, first);
      assertThat(fixture.bodies).hasSize(2);
    }
  }

  @Test
  void cachedNoAuthModelsStillCheckTheBindingBeforeHttp() throws Exception {
    var valid = new AtomicBoolean(true);
    try (var fixture = new Fixture(); var models = new PinnedChatModels(
        pin -> { throw new AssertionError("No-auth read a credential"); },
        pin -> { if (!valid.get()) throw new IllegalStateException("Binding removed"); }, null, 2)) {
      var pin = fixture.pin("first", "model-a", LlmAuthentication.NONE);
      var model = models.model(pin);
      valid.set(false);
      assertThatThrownBy(() -> infer(model, pin)).isInstanceOf(RuntimeException.class);
      assertThat(fixture.bodies).isEmpty();
    }
  }

  @Test
  void closedOwnerDoesNotCreateAnotherModel() throws Exception {
    try (var fixture = new Fixture()) {
      var models = new PinnedChatModels(pin -> null, pin -> {}, null, 1);
      models.close();
      assertThatThrownBy(() -> models.model(fixture.pin("first", "model", LlmAuthentication.NONE)))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  private void infer(ChatModel model, RunLlmConnection pin) {
    assertThat(model.call(new Prompt(List.of(new UserMessage("fixture")), RunNativeOptions.options(pin)))
        .getResult().getOutput().getText()).isEqualTo("fixture-answer");
  }

  private static final class Fixture implements AutoCloseable {
    final com.sun.net.httpserver.HttpServer server;
    final List<String> headers = new CopyOnWriteArrayList<>();
    final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
    Fixture() throws Exception {
      server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/v1/chat/completions", exchange -> {
        headers.add(java.util.Objects.toString(exchange.getRequestHeaders().getFirst("Authorization"), "<none>"));
        bodies.add(new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes()));
        var body = ("{\"id\":\"fixture\",\"object\":\"chat.completion\",\"created\":0,\"model\":\"fixture\","
            + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"fixture-answer\"},\"finish_reason\":\"stop\"}]}")
            .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (var output = exchange.getResponseBody()) { output.write(body); }
        finally { exchange.close(); }
      });
      server.start();
    }
    RunLlmConnection pin(String profile, String model, LlmAuthentication auth) {
      return new RunLlmConnection(profile, true, LlmProvider.OPENAI_COMPATIBLE, auth, null, null,
          new ResolvedLlmEndpoint(profile, 1, LlmApiType.CHAT_COMPLETIONS,
              "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", profile,
              model, new LlmCapabilities(ReasoningReplayMode.NONE, 36, Set.of()), 60, null, Duration.ofSeconds(5)),
          Map.of(), Map.of("max_tokens", 60));
    }
    public void close() { server.stop(0); }
  }
}
