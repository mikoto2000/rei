package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sun.net.httpserver.HttpServer;
import dev.mikoto2000.rei.autonomous.AutonomousActionProperties;
import dev.mikoto2000.rei.autonomous.AutonomousPublicSources;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** A complete but slowly delivered body must not evade the per-call generation deadline. */
@Tag("integration")
class AutonomousInferenceDeadlineTest {
  private static final Duration LIMIT = Duration.ofMillis(600);
  private static final byte[] RESPONSE =
      ("{\"id\":\"fixture\",\"object\":\"chat.completion\",\"created\":1,"
              + "\"model\":\"fixture\",\"choices\":[{\"index\":0,\"finish_reason\":\"stop\","
              + "\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"decision\\\":\\\"SKIP\\\",\\\"reason\\\":\\\"skip\\\"}\"}}],"
              + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}")
          .getBytes(StandardCharsets.UTF_8);
  private final List<String> paths = new CopyOnWriteArrayList<>();
  private final List<String> hooks = new CopyOnWriteArrayList<>();
  private final List<String> credentials = new CopyOnWriteArrayList<>();
  private final AgentEventFactory events = mock(AgentEventFactory.class);
  private final AgentEventPublisher publisher = mock(AgentEventPublisher.class);
  private HttpServer server;
  private ExecutorService handlers;
  private volatile boolean primaryFailure;

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(AutonomousActionProperties.class)
  @Import(LlmModelProvider.class)
  static class Module {}

  @BeforeEach
  void start() throws Exception {
    handlers = Executors.newVirtualThreadPerTaskExecutor();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(handlers);
    server.createContext(
        "/",
        exchange -> {
          paths.add(exchange.getRequestURI().getPath());
          hooks.add(exchange.getRequestHeaders().getFirst("X-Fixture-Transport"));
          credentials.add(exchange.getRequestHeaders().getFirst("Authorization"));
          exchange.getRequestBody().readAllBytes();
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          try {
            if (primaryFailure && exchange.getRequestURI().getPath().startsWith("/primary/")) {
              byte[] failure =
                  "{\"error\":{\"message\":\"fixture unavailable\",\"type\":\"server_error\"}}"
                      .getBytes(StandardCharsets.UTF_8);
              exchange.sendResponseHeaders(500, failure.length);
              try (var output = exchange.getResponseBody()) {
                output.write(failure);
              }
              return;
            }
            exchange.sendResponseHeaders(200, RESPONSE.length);
            try (var output = exchange.getResponseBody()) {
              for (byte value : RESPONSE) {
                output.write(value);
                output.flush();
                Thread.sleep(10);
              }
            }
          } catch (IOException expectedAfterDeadline) {
            // The client's whole-call timeout closes the socket while this fixture is still
            // sending.
          } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
    handlers.shutdownNow();
  }

  private String endpoint(String path) {
    return "http://127.0.0.1:" + server.getAddress().getPort() + path + "/v1";
  }

  private ApplicationContextRunner context(String mode) {
    var properties = new LlmProperties();
    properties.setReadTimeout(LIMIT);
    if (!mode.equals("default")) {
      var feature = new LlmProperties.Server();
      feature.setBaseUrl(endpoint("/primary"));
      feature.setApiKey("fixture-primary-key");
      feature.setModel("primary-model");
      feature.setFallbackToDefault(mode.equals("fallback"));
      properties.setFeature(LlmFeature.AUTONOMOUS_BLUESKY, feature);
    }
    OpenAiHttpClientBuilderCustomizer transport =
        builder ->
            builder
                .interceptor(new ChatStreamTimeoutInterceptor(LIMIT))
                .interceptor(chain ->
                    chain.proceed(
                        chain
                            .request()
                            .newBuilder()
                            .header("X-Fixture-Transport", "preserved")
                            .build()));
    var model =
        OpenAiChatModel.builder()
            .options(
                OpenAiChatOptions.builder()
                    .baseUrl(endpoint("/default"))
                    .apiKey("fixture-default-key")
                    .model("default-model")
                    .maxRetries(0)
                    .build())
            .httpClientBuilderCustomizer(transport)
            .build();
    return new ApplicationContextRunner()
        .withUserConfiguration(Module.class)
        .withPropertyValues(
            "rei.autonomous-action.enabled=true", "rei.autonomous-action.bluesky.enabled=true")
        .withBean(ChatModel.class, () -> model)
        .withBean(LlmProperties.class, () -> properties)
        .withBean(AgentEventFactory.class, () -> events)
        .withBean(AgentEventPublisher.class, () -> publisher)
        .withBean(OpenAiHttpClientBuilderCustomizer.class, () -> transport);
  }

  @ParameterizedTest
  @ValueSource(strings = {"default", "custom", "fallback"})
  void continuousResponseBytesCannotExtendAutonomousInferencePastItsDeadline(String mode) {
    primaryFailure = mode.equals("fallback");
    assertThat(RESPONSE.length * 10).isGreaterThan(2500);
    context(mode)
        .run(context -> {
          assertThat(context).hasNotFailed();
          var generator =
              new IsolatedAutonomousPostGenerator(
                  context.getBean(LlmModelProvider.class),
                  context.getBean(AutonomousActionProperties.class));
          long start = System.nanoTime();
          assertThatThrownBy(() ->
                  generator.generate(
                      List.of(
                          new AutonomousPublicSources.PublicSource(
                              "topic:public:1", "公開利用を許可した話題"))))
              .isInstanceOf(RuntimeException.class);
          assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
          if (mode.equals("default")) {
            assertThat(paths).containsExactly("/default/v1/chat/completions");
            assertThat(hooks).containsExactly("preserved");
            assertThat(credentials).containsExactly("Bearer fixture-default-key");
          } else if (mode.equals("custom")) {
            assertThat(paths).containsExactly("/primary/v1/chat/completions");
            assertThat(hooks).containsExactly((String) null);
            assertThat(credentials).containsExactly("Bearer fixture-primary-key");
          } else {
            assertThat(paths)
                .containsExactly("/primary/v1/chat/completions", "/default/v1/chat/completions");
            assertThat(hooks).containsExactly(null, "preserved");
            assertThat(credentials)
                .containsExactly("Bearer fixture-primary-key", "Bearer fixture-default-key");
          }
          verifyNoInteractions(events, publisher);
        });
  }

  @Test
  void autonomousTimeoutDoesNotChangeTheNextOrdinaryCallsLegacyTimeoutContract() {
    context("default")
        .run(context -> {
          assertThat(context).hasNotFailed();
          var generator =
              new IsolatedAutonomousPostGenerator(
                  context.getBean(LlmModelProvider.class),
                  context.getBean(AutonomousActionProperties.class));
          assertThatThrownBy(() ->
                  generator.generate(
                      List.of(
                          new AutonomousPublicSources.PublicSource(
                              "topic:public:1", "公開利用を許可した話題"))))
              .isInstanceOf(RuntimeException.class);
          long start = System.nanoTime();
          var response =
              context
                  .getBean(ChatModel.class)
                  .call(
                      new Prompt(
                          "ordinary request",
                          OpenAiChatOptions.builder()
                              .model("default-model")
                              .timeout(LIMIT)
                              .maxRetries(0)
                              .build()));
          assertThat(response.getResult()).isNotNull();
          assertThat(response.getResult().getOutput().getText()).contains("SKIP");
          assertThat(Duration.ofNanos(System.nanoTime() - start))
              .isGreaterThan(Duration.ofMillis(2500));
          assertThat(paths)
              .containsExactly("/default/v1/chat/completions", "/default/v1/chat/completions");
          assertThat(hooks).containsExactly("preserved", "preserved");
          verifyNoInteractions(events, publisher);
        });
  }
}
