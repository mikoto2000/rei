package dev.mikoto2000.rei.core.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.llm.ChatConnectionService;
import dev.mikoto2000.rei.llm.ChatEndpointCatalog;
import dev.mikoto2000.rei.llm.LlmChatClientProvider;
import dev.mikoto2000.rei.llm.LlmModelProvider;
import dev.mikoto2000.rei.llm.LlmProperties;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiConnectionDetails;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class ChatExecutionPinnedConnectionTest {
  @TempDir Path root;

  @ParameterizedTest
  @EnumSource(AgentRunContext.Mode.class)
  void acceptedChatKeepsItsReceiverAndDoesNotInjectUnconfirmedTokenSettings(AgentRunContext.Mode mode) throws Exception {
    try (var first = new Server(); var second = new Server(); var fixture = fixture(first, second)) {
      var owner = owner("accepted", mode);
      fixture.engine.accept(owner, "fixture");
      assertThat(fixture.connections.require(owner).profileId()).isEqualTo("first");
      fixture.connections.select("second");
      assertThat(fixture.engine.execute(owner, "fixture", new UserInterventionQueue()).success()).isTrue();
      assertThat(first.requests).hasSize(1);
      assertThat(second.requests).isEmpty();
      var body = first.requests.getFirst();
      assertThat(body.path("model").asText()).isEqualTo("first-model");
      assertThat(body.has("max_tokens")).isFalse();
      assertThat(body.has("max_completion_tokens")).isFalse();
      assertThatThrownBy(() -> fixture.connections.require(owner)).isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void directExecutionAlsoAcceptsBeforeItsFirstInference() throws Exception {
    try (var first = new Server(); var second = new Server(); var fixture = fixture(first, second)) {
      var result = fixture.engine.execute(owner("direct", AgentRunContext.Mode.CONVERSATION), "fixture", new UserInterventionQueue());
      assertThat(result.success()).isTrue();
      assertThat(first.requests).hasSize(1);
    }
  }

  @Test
  void checkpointHasTheAcceptedConnectionBeforeTheFirstHttpRequest() throws Exception {
    try (var first = new Server(); var second = new Server(); var fixture = fixture(first, second)) {
      var owner = owner("durable", AgentRunContext.Mode.EXCLUSIVE);
      fixture.engine.accept(owner, "fixture");
      var expected = fixture.connections.require(owner);
      var saved = new java.util.concurrent.atomic.AtomicReference<dev.mikoto2000.rei.core.stagnation.RunExecutionContext.ProgressState>();
      var observed = new java.util.concurrent.atomic.AtomicReference<dev.mikoto2000.rei.core.stagnation.RunExecutionContext.ProgressState>();
      var checkpoints = mock(dev.mikoto2000.rei.checkpoint.PersistentCheckpointService.class);
      org.mockito.Mockito.doAnswer(invocation -> {
        saved.set(invocation.getArgument(2));
        return null;
      }).when(checkpoints).preserveResults(org.mockito.ArgumentMatchers.eq(owner),
          org.mockito.ArgumentMatchers.anyList(),
          org.mockito.ArgumentMatchers.any(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.ProgressState.class));
      first.beforeReply = () -> observed.set(saved.get());
      fixture.engine.setCheckpoints(checkpoints);
      assertThat(fixture.engine.execute(owner, "fixture", new UserInterventionQueue()).success()).isTrue();
      assertThat(observed.get()).isNotNull();
      assertThat(observed.get().connection()).isEqualTo(expected);
      assertThat(observed.get().budget().llmCalls()).isEqualTo(1);
    }
  }

  @Test
  void anAvailableStructuredDoctorCommandDoesNotCaptureAnInferenceConnection() throws Exception {
    try (var first = new Server(); var second = new Server(); var fixture = fixture(first, second)) {
      fixture.engine.setDoctor(mock(dev.mikoto2000.rei.doctor.DoctorActiveService.class));
      var owner = owner("doctor", AgentRunContext.Mode.EXCLUSIVE);
      fixture.engine.accept(owner, "/doctor");
      assertThatThrownBy(() -> fixture.connections.require(owner)).isInstanceOf(IllegalStateException.class);
      assertThat(first.requests).isEmpty();
    }
  }

  @Test
  void anUnregisteredDoctorPrefixStillFollowsTheOrdinaryChatAdmissionPath() throws Exception {
    try (var first = new Server(); var second = new Server(); var fixture = fixture(first, second)) {
      var owner = owner("ordinary", AgentRunContext.Mode.EXCLUSIVE);
      fixture.engine.accept(owner, "/doctor");
      assertThat(fixture.connections.require(owner).profileId()).isEqualTo("first");
    }
  }

  @Test
  void approvalRestorationPrecedesSamplingTheCurrentSelection() throws Exception {
    try (var first = new Server(); var second = new Server(); var fixture = fixture(first, second)) {
      var saved = fixture.connections.accept(owner("old", AgentRunContext.Mode.EXCLUSIVE));
      fixture.connections.select("second");
      var owner = owner("resumed", AgentRunContext.Mode.EXCLUSIVE);
      var continuations = mock(RunContinuation.class);
      when(continuations.restore(owner)).thenReturn(new RunContinuation.Saved("fixture", List.of(),
          new OutputLimitRunBudget.Snapshot(0, 0, 0, false), null, List.of(), null, saved));
      fixture.engine.setContinuations(provider(RunContinuation.class, continuations));
      fixture.engine.accept(owner, "fixture");
      assertThat(fixture.connections.require(owner)).isEqualTo(saved);
    }
  }

  @Test
  void anOldContinuationWithoutConnectionProofCannotUseTheActiveProfile() throws Exception {
    try (var first = new Server(); var second = new Server(); var fixture = fixture(first, second)) {
      var owner = owner("old-schema", AgentRunContext.Mode.EXCLUSIVE);
      var continuations = mock(RunContinuation.class);
      when(continuations.restore(owner)).thenReturn(new RunContinuation.Saved("fixture", List.of(),
          new OutputLimitRunBudget.Snapshot(0, 0, 0, false)));
      fixture.engine.setContinuations(provider(RunContinuation.class, continuations));
      assertThatThrownBy(() -> fixture.engine.accept(owner, "fixture"))
          .isInstanceOf(IllegalStateException.class).hasMessageContaining("connection");
      assertThatThrownBy(() -> fixture.connections.require(owner)).isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void conflictingApprovalAndCheckpointConnectionsStopBeforeAdmission() throws Exception {
    try (var first = new Server(); var second = new Server(); var fixture = fixture(first, second)) {
      var saved = fixture.connections.accept(owner("old", AgentRunContext.Mode.EXCLUSIVE));
      fixture.connections.select("second");
      var different = fixture.connections.accept(owner("other", AgentRunContext.Mode.EXCLUSIVE));
      var owner = owner("conflict", AgentRunContext.Mode.EXCLUSIVE);
      var continuations = mock(RunContinuation.class);
      when(continuations.restore(owner)).thenReturn(new RunContinuation.Saved("fixture", List.of(),
          new OutputLimitRunBudget.Snapshot(0, 0, 0, false), null, List.of(), null, saved));
      fixture.engine.setContinuations(provider(RunContinuation.class, continuations));
      var checkpoints = mock(dev.mikoto2000.rei.checkpoint.PersistentCheckpointService.class);
      var progress = mock(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.ProgressState.class);
      when(progress.connection()).thenReturn(different);
      when(checkpoints.progress(owner)).thenReturn(progress);
      fixture.engine.setCheckpoints(checkpoints);
      assertThatThrownBy(() -> fixture.engine.accept(owner, "fixture"))
          .isInstanceOf(IllegalStateException.class).hasMessageContaining("connection");
      assertThatThrownBy(() -> fixture.connections.require(owner)).isInstanceOf(IllegalStateException.class);
    }
  }

  private Fixture fixture(Server first, Server second) {
    var properties = new Binder(new MapConfigurationPropertySource(Map.of(
        "rei.llm.features.chat.active", "first",
        "rei.llm.features.chat.endpoints.first.base-url", first.url(),
        "rei.llm.features.chat.endpoints.first.model", "first-model",
        "rei.llm.features.chat.endpoints.first.authentication", "none",
        "rei.llm.features.chat.endpoints.second.base-url", second.url(),
        "rei.llm.features.chat.endpoints.second.model", "second-model",
        "rei.llm.features.chat.endpoints.second.authentication", "none")))
        .bind("rei.llm", LlmProperties.class).get();
    var nativeModel = mock(ChatModel.class);
    when(nativeModel.getOptions()).thenThrow(new AssertionError("Main chat sampled ambient options"));
    var models = new LlmModelProvider(nativeModel, properties);
    var holder = new ModelHolderService("helper-model");
    var connections = new ChatConnectionService(new ChatEndpointCatalog(properties), properties, models, holder,
        provider(OpenAiConnectionDetails.class, null), provider(OpenAiCommonProperties.class, null),
        provider(OpenAiChatProperties.class, null), provider(dev.mikoto2000.rei.llm.capture.CaptureStore.class, null));
    var system = mock(dev.mikoto2000.rei.core.configuration.SystemPromptService.class);
    when(system.systemPrompt()).thenReturn("system-fixture");
    var clients = new LlmChatClientProvider(models,
        new dev.mikoto2000.rei.core.configuration.CoreProperties("system-fixture", 100), system,
        dev.mikoto2000.rei.chatmemory.WindowedConversationMemory.builder().build(),
        empty(), empty(), empty(), empty(), empty(), empty(), empty(), empty(), empty(), empty(),
        empty(), empty(), empty(), empty(), empty(), empty(), empty(), empty(), null, null);
    var engine = new ChatExecutionService(clients, holder, models, properties,
        new CommandCancellationService(), Optional.empty(), Optional.empty());
    engine.setChatConnections(connections);
    return new Fixture(engine, connections);
  }

  private record Fixture(ChatExecutionService engine, ChatConnectionService connections) implements AutoCloseable {
    public void close() { connections.close(); }
  }
  private <T> ObjectProvider<T> empty() { return mock(); }
  private <T> ObjectProvider<T> provider(Class<T> type, T value) {
    var beans = new StaticListableBeanFactory();
    if (value != null) beans.addBean("value", value);
    return beans.getBeanProvider(type);
  }
  private AgentRunContext owner(String id, AgentRunContext.Mode mode) {
    return new AgentRunContext(id, "chat:fixture", root, "project", AgentRunContext.RequestSource.SHELL, mode, false);
  }

  private static final class Server implements AutoCloseable {
    final com.sun.net.httpserver.HttpServer server;
    volatile Runnable beforeReply = () -> {};
    final List<com.fasterxml.jackson.databind.JsonNode> requests = new CopyOnWriteArrayList<>();
    Server() throws Exception {
      server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/v1/chat/completions", exchange -> {
        var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(exchange.getRequestBody().readAllBytes());
        requests.add(body);
        beforeReply.run();
        var reply = "data: {\"id\":\"fixture\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"fixture-answer\"},\"finish_reason\":null}]}\n\n"
            + "data: {\"id\":\"fixture\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
            + "data: [DONE]\n\n";
        var bytes = reply.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
        finally { exchange.close(); }
      });
      server.start();
    }
    String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }
    public void close() { server.stop(0); }
  }
}
