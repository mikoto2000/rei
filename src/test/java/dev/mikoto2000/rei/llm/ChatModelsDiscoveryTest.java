package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiConnectionDetails;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class ChatModelsDiscoveryTest {
  @Test
  void modelsUseTheSelectedProfileAndNeverAmbientModelOrCredentials() throws Exception {
    try (var first = new Fixture("first-result");
        var second = new Fixture("second-result");
        var service = service(first, second, false)) {
      var initial = service.models();
      assertThat(initial.selection().endpoint()).isEqualTo("first");
      assertThat(initial.selection().model()).isEqualTo("first-model");
      assertThat(initial.models()).containsExactly("first-result");
      service.select("second");
      var changed = service.models();
      assertThat(changed.selection().endpoint()).isEqualTo("second");
      assertThat(changed.models()).containsExactly("second-result");
      assertThat(first.authorizations).containsExactly("Bearer PRIVATE_FIRST_PROFILE");
      assertThat(second.authorizations).containsExactly("<none>");
      assertThat(first.paths).containsExactly("GET /v1/models");
      assertThat(second.paths).containsExactly("GET /v1/models");
    }
  }

  @Test
  void selectedModelIsPreservedAndUnsupportedGenerationDoesNotBlockMetadataDiscovery()
      throws Exception {
    try (var first = new Fixture("first-result");
        var second = new Fixture("second-result");
        var service = service(first, second, true)) {
      assertThat(service.endpoints().getFirst().unavailableReason()).contains("temperature");
      assertThat(service.models().models()).containsExactly("first-result");
      service.select("second");
      service.selectModel("explicit-other-model");
      assertThat(service.models().selection().model()).isEqualTo("explicit-other-model");
    }
  }

  @Test
  void aSelectionChangeDuringDiscoveryCannotRelabelTheOriginalCatalog() throws Exception {
    try (var first = new Fixture("first-result");
        var second = new Fixture("second-result");
        var service = service(first, second, false);
        var executor = Executors.newSingleThreadExecutor()) {
      first.block.set(true);
      var pending = executor.submit(service::models);
      try {
        assertThat(first.received.await(5, TimeUnit.SECONDS)).isTrue();
        service.select("second");
      } finally {
        first.release.countDown();
      }
      var result = pending.get(5, TimeUnit.SECONDS);
      assertThat(result.selection().endpoint()).isEqualTo("first");
      assertThat(result.selection().model()).isEqualTo("first-model");
      assertThat(result.models()).containsExactly("first-result");
      assertThat(service.selection().endpoint()).isEqualTo("second");
      assertThat(second.paths).isEmpty();
    }
  }

  @Test
  void failedDiscoveryDoesNotFallBackOrExposeRemoteErrorBodies() throws Exception {
    try (var first = new Fixture("first-result");
        var second = new Fixture("second-result");
        var service = service(first, second, false)) {
      first.unavailable.set(true);
      assertThatThrownBy(service::models)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageNotContaining("PRIVATE_REMOTE_ERROR");
      assertThat(first.paths).hasSize(1);
      assertThat(second.paths).isEmpty();
    }
  }

  private ChatConnectionService service(
      Fixture first, Fixture second, boolean unsupportedGeneration) {
    var values = new HashMap<String, String>();
    values.put("rei.llm.features.chat.active", "first");
    var prefix = "rei.llm.features.chat.endpoints.";
    values.put(prefix + "first.base-url", first.baseUrl());
    values.put(prefix + "first.model", "first-model");
    values.put(prefix + "first.authentication", "api-key");
    values.put(prefix + "first.api-key", "PRIVATE_FIRST_PROFILE");
    if (unsupportedGeneration) values.put(prefix + "first.temperature", "0.7");
    values.put(prefix + "second.base-url", second.baseUrl());
    values.put(prefix + "second.model", "second-model");
    values.put(prefix + "second.authentication", "none");
    var properties =
        new Binder(new MapConfigurationPropertySource(values))
            .bind("rei.llm", LlmProperties.class)
            .get();
    var root = mock(ChatModel.class);
    when(root.getOptions())
        .thenThrow(new AssertionError("Discovery read the ambient native model"));
    var holder = mock(ModelHolderService.class);
    when(holder.get())
        .thenThrow(new AssertionError("Named discovery read the ambient helper model"));
    var beans = new StaticListableBeanFactory();
    var common = new OpenAiCommonProperties();
    common.setBaseUrl("http://ambient.invalid");
    common.setApiKey("PRIVATE_AMBIENT_KEY");
    beans.addBean("common", common);
    return new ChatConnectionService(
        new ChatEndpointCatalog(properties),
        properties,
        new LlmModelProvider(root, properties),
        holder,
        beans.getBeanProvider(OpenAiConnectionDetails.class),
        beans.getBeanProvider(OpenAiCommonProperties.class),
        beans.getBeanProvider(OpenAiChatProperties.class),
        beans.getBeanProvider(dev.mikoto2000.rei.llm.capture.CaptureStore.class));
  }

  private static final class Fixture implements AutoCloseable {
    final com.sun.net.httpserver.HttpServer server;
    final List<String> paths = new CopyOnWriteArrayList<>();
    final List<String> authorizations = new CopyOnWriteArrayList<>();
    final AtomicBoolean block = new AtomicBoolean();
    final AtomicBoolean unavailable = new AtomicBoolean();
    final CountDownLatch received = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);

    Fixture(String model) throws Exception {
      server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          exchange -> {
            paths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            authorizations.add(
                java.util.Objects.toString(
                    exchange.getRequestHeaders().getFirst("Authorization"), "<none>"));
            received.countDown();
            try {
              if (block.get() && !release.await(5, TimeUnit.SECONDS))
                throw new java.io.IOException("Fixture deadline");
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw new java.io.IOException("Fixture interrupted", interrupted);
            }
            var text =
                unavailable.get()
                    ? "{\"error\":{\"message\":\"PRIVATE_REMOTE_ERROR\"}}"
                    : "{\"object\":\"list\",\"data\":[{\"id\":\""
                        + model
                        + "\",\"object\":\"model\",\"created\":0,\"owned_by\":\"fixture\"}]}";
            var bytes = text.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(unavailable.get() ? 503 : 200, bytes.length);
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

    @Override
    public void close() {
      release.countDown();
      server.stop(0);
    }
  }
}
