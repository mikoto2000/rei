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
  void acceptedChatKeepsItsReceiverAndDoesNotInjectUnconfirmedTokenSettings(
      AgentRunContext.Mode mode) throws Exception {
    try (var first = new Server();
        var second = new Server();
        var fixture = fixture(first, second)) {
      var owner = owner("accepted", mode);
      fixture.engine.accept(owner, "fixture");
      assertThat(fixture.connections.require(owner).profileId()).isEqualTo("first");
      fixture.connections.select("second");
      var result = fixture.engine.execute(owner, "fixture", new UserInterventionQueue());
      assertThat(result.success()).as("%s: %s", result.stopCode(), result.errorMessage()).isTrue();
      assertThat(first.requests).hasSize(1);
      assertThat(second.requests).isEmpty();
      var body = first.requests.getFirst();
      assertThat(body.path("model").asText()).isEqualTo("first-model");
      assertThat(body.has("max_tokens")).isFalse();
      assertThat(body.has("max_completion_tokens")).isFalse();
      assertThatThrownBy(() -> fixture.connections.require(owner))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void directExecutionAlsoAcceptsBeforeItsFirstInference() throws Exception {
    try (var first = new Server();
        var second = new Server();
        var fixture = fixture(first, second)) {
      var result =
          fixture.engine.execute(
              owner("direct", AgentRunContext.Mode.CONVERSATION),
              "fixture",
              new UserInterventionQueue());
      assertThat(result.success()).as("%s: %s", result.stopCode(), result.errorMessage()).isTrue();
      assertThat(first.requests).hasSize(1);
    }
  }

  @Test
  void checkpointHasTheAcceptedConnectionBeforeTheFirstHttpRequest() throws Exception {
    try (var first = new Server();
        var second = new Server();
        var fixture = fixture(first, second)) {
      var owner = owner("durable", AgentRunContext.Mode.EXCLUSIVE);
      fixture.engine.accept(owner, "fixture");
      var expected = fixture.connections.require(owner);
      var saved =
          new java.util.concurrent.atomic.AtomicReference<
              dev.mikoto2000.rei.core.stagnation.RunExecutionContext.ProgressState>();
      var observed =
          new java.util.concurrent.atomic.AtomicReference<
              dev.mikoto2000.rei.core.stagnation.RunExecutionContext.ProgressState>();
      var checkpoints = mock(dev.mikoto2000.rei.checkpoint.PersistentCheckpointService.class);
      org.mockito.Mockito.doAnswer(
              invocation -> {
                saved.set(invocation.getArgument(2));
                return null;
              })
          .when(checkpoints)
          .preserveResults(
              org.mockito.ArgumentMatchers.eq(owner),
              org.mockito.ArgumentMatchers.anyList(),
              org.mockito.ArgumentMatchers.any(
                  dev.mikoto2000.rei.core.stagnation.RunExecutionContext.ProgressState.class));
      first.beforeReply = () -> observed.set(saved.get());
      fixture.engine.setCheckpoints(checkpoints);
      var result = fixture.engine.execute(owner, "fixture", new UserInterventionQueue());
      assertThat(result.success()).as("%s: %s", result.stopCode(), result.errorMessage()).isTrue();
      assertThat(observed.get()).isNotNull();
      assertThat(observed.get().connection()).isEqualTo(expected);
      assertThat(observed.get().budget().llmCalls()).isEqualTo(1);
    }
  }

  @ParameterizedTest
  @EnumSource(AgentRunContext.Mode.class)
  void approvedInferenceResumesTheSameReceiverAndUsesItsAlreadyReservedCall(
      AgentRunContext.Mode mode) throws Exception {
    try (var first = new Server();
        var second = new Server();
        var fixture = fixture(first, second)) {
      var owner =
          new AgentRunContext(
              "inference-resume",
              "chat:fixture",
              root,
              java.util.UUID.randomUUID().toString(),
              AgentRunContext.RequestSource.SHELL,
              mode,
              false);
      var approval = approval(fixture, owner);
      var savedProgress =
          new java.util.concurrent.atomic.AtomicReference<
              dev.mikoto2000.rei.core.stagnation.RunExecutionContext.ProgressState>();
      var atHttp =
          new java.util.concurrent.atomic.AtomicReference<
              dev.mikoto2000.rei.core.stagnation.RunExecutionContext.ProgressState>();
      var checkpoints = mock(dev.mikoto2000.rei.checkpoint.PersistentCheckpointService.class);
      when(checkpoints.modelHandoff(org.mockito.ArgumentMatchers.anyString()))
          .thenReturn(
              new dev.mikoto2000.rei.checkpoint.PersistentCheckpointService.ModelHandoff(
                  "", false));
      org.mockito.Mockito.doAnswer(
              invocation -> {
                savedProgress.set(invocation.getArgument(2));
                return null;
              })
          .when(checkpoints)
          .preserveResults(
              org.mockito.ArgumentMatchers.eq(owner),
              org.mockito.ArgumentMatchers.anyList(),
              org.mockito.ArgumentMatchers.any(
                  dev.mikoto2000.rei.core.stagnation.RunExecutionContext.ProgressState.class));
      fixture.engine.setCheckpoints(checkpoints);
      first.beforeReply = () -> atHttp.set(savedProgress.get());
      var waiting = fixture.engine.execute(owner, "fixture", new UserInterventionQueue());
      assertThat(waiting.status())
          .as("%s: %s", waiting.stopCode(), waiting.errorMessage())
          .isEqualTo(ChatExecutionResult.Status.WAITING);
      assertThat(first.requests).isEmpty();
      assertThat(second.requests).isEmpty();
      var request = approval.requests.list(owner.projectId()).getFirst();
      var document = approval.store.document(approval.store.find(request.id()).orElseThrow());
      assertThat(document.budget().llmCalls()).isEqualTo(1);
      assertThat(document.budget().usageUnknown()).isFalse();
      assertThat(document.pending()).isNull();
      fixture.connections.select("second");
      approval.continuations.decide(owner.projectId(), request.id(), true, true);
      assertThat(approval.store.claim(request.id())).isTrue();
      assertThat(
              approval.registry.transition(
                  owner.runId(), dev.mikoto2000.rei.application.run.RunStatus.RUNNING, null))
          .isTrue();
      var result = fixture.engine.execute(owner, "fixture", new UserInterventionQueue());
      assertThat(result.success()).as("%s: %s", result.stopCode(), result.errorMessage()).isTrue();
      assertThat(first.requests).hasSize(1);
      assertThat(second.requests).isEmpty();
      assertThat(first.requests.getFirst().path("model").asText()).isEqualTo("first-model");
      assertThat(atHttp.get()).isNotNull();
      assertThat(atHttp.get().connection()).isEqualTo(document.connection());
      assertThat(atHttp.get().budget().llmCalls())
          .as("the pending request was already reserved")
          .isEqualTo(1);
    }
  }

  private ApprovalFixture approval(Fixture fixture, AgentRunContext owner) {
    var clock = java.time.Clock.systemUTC();
    var registry = new dev.mikoto2000.rei.application.run.RunRegistry(clock, fixture.data);
    var bus = new dev.mikoto2000.rei.event.InMemoryAgentEventBus();
    var runs =
        new dev.mikoto2000.rei.application.run.RunService(
            registry,
            bus,
            new dev.mikoto2000.rei.event.AgentEventFactory(clock),
            new CommandCancellationService(),
            id -> false);
    var projects = mock(dev.mikoto2000.rei.core.project.ProjectRegistry.class);
    when(projects.resolveById(owner.projectId()))
        .thenReturn(
            Optional.of(
                new dev.mikoto2000.rei.core.project.ProjectContext(
                    owner.projectId(), "fixture", root)));
    var sessions = mock(dev.mikoto2000.rei.application.session.SessionRepository.class);
    when(sessions.findById(owner.conversationId()))
        .thenReturn(
            Optional.of(
                new dev.mikoto2000.rei.application.session.SessionMetadata(
                    owner.conversationId(),
                    owner.projectId(),
                    "fixture",
                    clock.instant(),
                    clock.instant())));
    var requests = new dev.mikoto2000.rei.core.policy.ToolApprovalRepository(fixture.data, clock);
    var store = new dev.mikoto2000.rei.core.policy.ApprovalContinuationStore(fixture.data);
    var continuations =
        new dev.mikoto2000.rei.core.policy.ApprovalContinuationService(
            requests,
            store,
            provider(dev.mikoto2000.rei.application.run.RunRegistry.class, registry),
            provider(dev.mikoto2000.rei.application.run.RunService.class, runs),
            provider(ConversationInputRouter.class, mock(ConversationInputRouter.class)),
            provider(ChatExecutionService.class, fixture.engine),
            provider(dev.mikoto2000.rei.slack.SlackAgentService.class, null),
            provider(dev.mikoto2000.rei.core.project.ProjectRegistry.class, projects),
            provider(dev.mikoto2000.rei.application.session.SessionRepository.class, sessions),
            fixture.data,
            clock);
    fixture.engine.setContinuations(provider(RunContinuation.class, continuations));
    registry.register(owner);
    registry.transition(owner.runId(), dev.mikoto2000.rei.application.run.RunStatus.RUNNING, null);
    return new ApprovalFixture(requests, store, continuations, registry);
  }

  private record ApprovalFixture(
      dev.mikoto2000.rei.core.policy.ToolApprovalRepository requests,
      dev.mikoto2000.rei.core.policy.ApprovalContinuationStore store,
      dev.mikoto2000.rei.core.policy.ApprovalContinuationService continuations,
      dev.mikoto2000.rei.application.run.RunRegistry registry) {}

  @Test
  void anAvailableStructuredDoctorCommandDoesNotCaptureAnInferenceConnection() throws Exception {
    try (var first = new Server();
        var second = new Server();
        var fixture = fixture(first, second)) {
      fixture.engine.setDoctor(mock(dev.mikoto2000.rei.doctor.DoctorActiveService.class));
      var owner = owner("doctor", AgentRunContext.Mode.EXCLUSIVE);
      fixture.engine.accept(owner, "/doctor");
      assertThatThrownBy(() -> fixture.connections.require(owner))
          .isInstanceOf(IllegalStateException.class);
      assertThat(first.requests).isEmpty();
    }
  }

  @Test
  void anUnregisteredDoctorPrefixStillFollowsTheOrdinaryChatAdmissionPath() throws Exception {
    try (var first = new Server();
        var second = new Server();
        var fixture = fixture(first, second)) {
      var owner = owner("ordinary", AgentRunContext.Mode.EXCLUSIVE);
      fixture.engine.accept(owner, "/doctor");
      assertThat(fixture.connections.require(owner).profileId()).isEqualTo("first");
    }
  }

  @Test
  void approvalRestorationPrecedesSamplingTheCurrentSelection() throws Exception {
    try (var first = new Server();
        var second = new Server();
        var fixture = fixture(first, second)) {
      var saved = fixture.connections.accept(owner("old", AgentRunContext.Mode.EXCLUSIVE));
      fixture.connections.select("second");
      var owner = owner("resumed", AgentRunContext.Mode.EXCLUSIVE);
      var continuations = mock(RunContinuation.class);
      when(continuations.restore(owner))
          .thenReturn(
              new RunContinuation.Saved(
                  "fixture",
                  List.of(),
                  new OutputLimitRunBudget.Snapshot(0, 0, 0, false),
                  null,
                  List.of(),
                  null,
                  saved));
      fixture.engine.setContinuations(provider(RunContinuation.class, continuations));
      fixture.engine.accept(owner, "fixture");
      assertThat(fixture.connections.require(owner)).isEqualTo(saved);
    }
  }

  @Test
  void anOldContinuationWithoutConnectionProofCannotUseTheActiveProfile() throws Exception {
    try (var first = new Server();
        var second = new Server();
        var fixture = fixture(first, second)) {
      var owner = owner("old-schema", AgentRunContext.Mode.EXCLUSIVE);
      var continuations = mock(RunContinuation.class);
      when(continuations.restore(owner))
          .thenReturn(
              new RunContinuation.Saved(
                  "fixture", List.of(), new OutputLimitRunBudget.Snapshot(0, 0, 0, false)));
      fixture.engine.setContinuations(provider(RunContinuation.class, continuations));
      assertThatThrownBy(() -> fixture.engine.accept(owner, "fixture"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("connection");
      assertThatThrownBy(() -> fixture.connections.require(owner))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void conflictingApprovalAndCheckpointConnectionsStopBeforeAdmission() throws Exception {
    try (var first = new Server();
        var second = new Server();
        var fixture = fixture(first, second)) {
      var saved = fixture.connections.accept(owner("old", AgentRunContext.Mode.EXCLUSIVE));
      fixture.connections.select("second");
      var different = fixture.connections.accept(owner("other", AgentRunContext.Mode.EXCLUSIVE));
      var owner = owner("conflict", AgentRunContext.Mode.EXCLUSIVE);
      var continuations = mock(RunContinuation.class);
      when(continuations.restore(owner))
          .thenReturn(
              new RunContinuation.Saved(
                  "fixture",
                  List.of(),
                  new OutputLimitRunBudget.Snapshot(0, 0, 0, false),
                  null,
                  List.of(),
                  null,
                  saved));
      fixture.engine.setContinuations(provider(RunContinuation.class, continuations));
      var checkpoints = mock(dev.mikoto2000.rei.checkpoint.PersistentCheckpointService.class);
      var progress =
          mock(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.ProgressState.class);
      when(progress.connection()).thenReturn(different);
      when(checkpoints.progress(owner)).thenReturn(progress);
      fixture.engine.setCheckpoints(checkpoints);
      assertThatThrownBy(() -> fixture.engine.accept(owner, "fixture"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("connection");
      assertThatThrownBy(() -> fixture.connections.require(owner))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  private Fixture fixture(Server first, Server second) {
    var values = new java.util.HashMap<String, String>();
    values.put("rei.llm.features.chat.active", "first");
    for (var entry : Map.of("first", first.url(), "second", second.url()).entrySet()) {
      var prefix = "rei.llm.features.chat.endpoints." + entry.getKey() + ".";
      values.put(prefix + "base-url", entry.getValue());
      values.put(prefix + "model", entry.getKey() + "-model");
      values.put(prefix + "authentication", "none");
      values.put(prefix + "server-capabilities.chat-completions", "supported");
      values.put(prefix + "server-capabilities.streaming", "supported");
    }
    var properties =
        new Binder(new MapConfigurationPropertySource(values))
            .bind("rei.llm", LlmProperties.class)
            .get();
    var nativeModel = mock(ChatModel.class);
    when(nativeModel.getOptions())
        .thenThrow(new AssertionError("Main chat sampled ambient options"));
    var models = new LlmModelProvider(nativeModel, properties);
    var data = new org.sqlite.SQLiteDataSource();
    data.setUrl("jdbc:sqlite:" + root.resolve("native-input.db"));
    var wiring = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
    wiring.registerSingleton(
        "payloads", new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore(data));
    var injection =
        new org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor();
    injection.setBeanFactory(wiring);
    injection.processInjection(models);
    var holder = new ModelHolderService("helper-model");
    var connections =
        new ChatConnectionService(
            new ChatEndpointCatalog(properties),
            properties,
            models,
            holder,
            provider(OpenAiConnectionDetails.class, null),
            provider(OpenAiCommonProperties.class, null),
            provider(OpenAiChatProperties.class, null),
            provider(dev.mikoto2000.rei.llm.capture.CaptureStore.class, null));
    var system = mock(dev.mikoto2000.rei.core.configuration.SystemPromptService.class);
    when(system.systemPrompt()).thenReturn("system-fixture");
    var clients =
        new LlmChatClientProvider(
            models,
            new dev.mikoto2000.rei.core.configuration.CoreProperties("system-fixture", 100),
            system,
            dev.mikoto2000.rei.chatmemory.WindowedConversationMemory.builder().build(),
            empty(),
            empty(),
            empty(),
            empty(),
            empty(),
            empty(),
            empty(),
            empty(),
            empty(),
            empty(),
            empty(),
            empty(),
            empty(),
            empty(),
            empty(),
            empty(),
            empty(),
            empty(),
            null,
            null);
    var engine =
        new ChatExecutionService(
            clients,
            holder,
            models,
            properties,
            new CommandCancellationService(),
            Optional.empty(),
            Optional.empty());
    engine.setChatConnections(connections);
    engine.setConversationLogStore(
        new dev.mikoto2000.rei.conversation.ConversationLogStore(
            root.resolve("conversation-logs"),
            java.time.Clock.systemUTC(),
            new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())));
    engine.setContinuations(
        provider(
            RunContinuation.class,
            dev.mikoto2000.rei.testfixtures.FixtureInferencePermissions.automatic(data)));
    return new Fixture(engine, connections, data);
  }

  private record Fixture(
      ChatExecutionService engine,
      ChatConnectionService connections,
      org.sqlite.SQLiteDataSource data)
      implements AutoCloseable {
    public void close() {
      connections.close();
    }
  }

  private <T> ObjectProvider<T> empty() {
    return mock();
  }

  private <T> ObjectProvider<T> provider(Class<T> type, T value) {
    var beans = new StaticListableBeanFactory();
    if (value != null) beans.addBean("value", value);
    return beans.getBeanProvider(type);
  }

  private AgentRunContext owner(String id, AgentRunContext.Mode mode) {
    return new AgentRunContext(
        id, "chat:fixture", root, "project", AgentRunContext.RequestSource.SHELL, mode, false);
  }

  private static final class Server implements AutoCloseable {
    final com.sun.net.httpserver.HttpServer server;
    volatile Runnable beforeReply = () -> {};
    final List<com.fasterxml.jackson.databind.JsonNode> requests = new CopyOnWriteArrayList<>();

    Server() throws Exception {
      server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/v1/chat/completions",
          exchange -> {
            var body =
                new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(exchange.getRequestBody().readAllBytes());
            requests.add(body);
            beforeReply.run();
            var reply =
                "data: {\"id\":\"fixture\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"fixture-answer\"},\"finish_reason\":null}]}\n\n"
                    + "data: {\"id\":\"fixture\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n";
            var bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
              output.write(bytes);
            } finally {
              exchange.close();
            }
          });
      server.start();
    }

    String url() {
      return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    public void close() {
      server.stop(0);
    }
  }
}
