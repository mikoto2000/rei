package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PinnedModelsDiscoveryTest {
  private record Owned(com.openai.client.OpenAIClient client) implements AutoCloseable {
    @Override
    public void close() {
      client.close();
    }
  }

  @Test
  void noneDiscoveryNeverReadsCredentialsOrSendsAuthentication() throws Exception {
    try (var fixture = new Fixture();
        var client =
            new Owned(
                PinnedOpenAiClients.openModels(
                    fixture.pin(LlmAuthentication.NONE),
                    () -> {
                      throw new AssertionError("None profile read a credential");
                    },
                    fixture.transport,
                    List.of(),
                    () -> {}))) {
      assertThat(client.client().models().list().data())
          .extracting(com.openai.models.models.Model::id)
          .containsExactly("fixture-model");
      assertThat(fixture.paths).containsExactly("GET /v1/models");
      assertThat(fixture.authentication).containsExactly("<none>");
      assertThat(fixture.alternativeKeys).containsOnly("<none>");
    }
  }

  @Test
  void oneDiscoveryClientReadsRotatedCredentialsAtEveryRequest() throws Exception {
    var current = new AtomicReference<>("PRIVATE_FIRST");
    var verified = new AtomicInteger();
    try (var fixture = new Fixture();
        var client =
            new Owned(
                PinnedOpenAiClients.openModels(
                    fixture.pin(LlmAuthentication.API_KEY),
                    current::get,
                    fixture.transport,
                    List.of(),
                    verified::incrementAndGet))) {
      client.client().models().list();
      current.set("PRIVATE_ROTATED");
      client.client().models().list();
      assertThat(fixture.authentication)
          .containsExactly("Bearer PRIVATE_FIRST", "Bearer PRIVATE_ROTATED");
      assertThat(verified.get()).isGreaterThanOrEqualTo(2);
    }
  }

  @Test
  void discoveryPurposeCannotSendInferenceOrRetrieveOrDeleteModels() throws Exception {
    try (var fixture = new Fixture();
        var client =
            new Owned(
                PinnedOpenAiClients.openModels(
                    fixture.pin(LlmAuthentication.NONE),
                    () -> null,
                    fixture.transport,
                    List.of(),
                    () -> {}))) {
      assertThatThrownBy(() -> client.client().models().retrieve("fixture-model"))
          .isInstanceOf(RuntimeException.class);
      assertThatThrownBy(() -> client.client().models().delete("fixture-model"))
          .isInstanceOf(RuntimeException.class);
      assertThatThrownBy(() ->
              client
                  .client()
                  .chat()
                  .completions()
                  .create(
                      com.openai.models.chat.completions.ChatCompletionCreateParams.builder()
                          .model("fixture-model")
                          .addUserMessage("fixture-only")
                          .build()))
          .isInstanceOf(RuntimeException.class);
      assertThat(fixture.paths).isEmpty();
    }
  }

  @Test
  void injectedCredentialAndChangedDestinationAreRejectedBeforeHttp() throws Exception {
    try (var fixture = new Fixture();
        var foreign = new Fixture()) {
      for (okhttp3.Interceptor interceptor :
          List.<okhttp3.Interceptor>of(
              chain ->
                  chain.proceed(
                      chain
                          .request()
                          .newBuilder()
                          .header("Authorization", "Bearer PRIVATE_INJECTED")
                          .build()),
              chain ->
                  chain.proceed(
                      chain.request().newBuilder().url(foreign.baseUrl() + "/models").build()))) {
        try (var client =
            new Owned(
                PinnedOpenAiClients.openModels(
                    fixture.pin(LlmAuthentication.NONE),
                    () -> null,
                    fixture.transport,
                    List.of(interceptor),
                    () -> {}))) {
          assertThatThrownBy(() -> client.client().models().list())
              .isInstanceOf(RuntimeException.class);
        }
      }
      assertThat(fixture.paths).isEmpty();
      assertThat(foreign.paths).isEmpty();
    }
  }

  @Test
  void changedBindingStopsDiscoveryBeforeAnyNetworkRequest() throws Exception {
    var verified = new AtomicInteger();
    try (var fixture = new Fixture();
        var client =
            new Owned(
                PinnedOpenAiClients.openModels(
                    fixture.pin(LlmAuthentication.NONE),
                    () -> null,
                    fixture.transport,
                    List.of(),
                    () -> {
                      verified.incrementAndGet();
                      throw new IllegalStateException("binding changed");
                    }))) {
      assertThatThrownBy(() -> client.client().models().list())
          .isInstanceOf(RuntimeException.class);
      assertThat(fixture.paths).isEmpty();
      assertThat(verified).hasValue(1);
    }
  }

  @Test
  void redirectsCannotForwardTheSelectedProfilesCredential() throws Exception {
    try (var fixture = new Fixture();
        var foreign = new Fixture()) {
      fixture.redirect.set(foreign.baseUrl() + "/models");
      try (var client =
          new Owned(
              PinnedOpenAiClients.openModels(
                  fixture.pin(LlmAuthentication.API_KEY),
                  () -> "PRIVATE_LOCAL",
                  fixture.transport,
                  List.of(),
                  () -> {}))) {
        assertThatThrownBy(() -> client.client().models().list())
            .isInstanceOf(RuntimeException.class);
      }
      assertThat(fixture.paths).hasSize(1);
      assertThat(foreign.paths).isEmpty();
    }
  }

  private static final class Fixture implements AutoCloseable {
    final com.sun.net.httpserver.HttpServer server;
    final okhttp3.OkHttpClient transport =
        new okhttp3.OkHttpClient.Builder()
            .proxy(java.net.Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build();
    final List<String> paths = new CopyOnWriteArrayList<>();
    final List<String> authentication = new CopyOnWriteArrayList<>();
    final List<String> alternativeKeys = new CopyOnWriteArrayList<>();
    final AtomicReference<String> redirect = new AtomicReference<>();

    Fixture() throws Exception {
      server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          exchange -> {
            paths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            authentication.add(
                java.util.Objects.toString(
                    exchange.getRequestHeaders().getFirst("Authorization"), "<none>"));
            for (var header : List.of("api-key", "x-api-key", "Cookie"))
              alternativeKeys.add(
                  java.util.Objects.toString(
                      exchange.getRequestHeaders().getFirst(header), "<none>"));
            exchange.getRequestBody().readAllBytes();
            if (redirect.get() != null) {
              exchange.getResponseHeaders().set("Location", redirect.get());
              exchange.sendResponseHeaders(307, -1);
              exchange.close();
              return;
            }
            var bytes =
                "{\"object\":\"list\",\"data\":[{\"id\":\"fixture-model\",\"object\":\"model\",\"created\":0,\"owned_by\":\"fixture\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
              output.write(bytes);
            } finally {
              exchange.close();
            }
          });
      server.start();
    }

    String baseUrl() {
      return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    RunLlmConnection pin(LlmAuthentication authentication) {
      return new RunLlmConnection(
          "fixture",
          true,
          LlmProvider.OPENAI_COMPATIBLE,
          authentication,
          null,
          null,
          new ResolvedLlmEndpoint(
              "fixture",
              1,
              LlmApiType.CHAT_COMPLETIONS,
              baseUrl(),
              "fixture",
              "fixture-model",
              new LlmCapabilities(ReasoningReplayMode.NONE, 10, Set.of()),
              null,
              null,
              Duration.ofSeconds(5)),
          Map.of(),
          Map.of());
    }

    @Override
    public void close() {
      server.stop(0);
      transport.dispatcher().cancelAll();
      transport.connectionPool().evictAll();
      transport.dispatcher().executorService().shutdown();
    }
  }
}
