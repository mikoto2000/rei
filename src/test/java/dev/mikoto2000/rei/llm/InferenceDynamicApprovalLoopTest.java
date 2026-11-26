package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunService;
import dev.mikoto2000.rei.application.run.RunStatus;
import dev.mikoto2000.rei.application.session.SessionMetadata;
import dev.mikoto2000.rei.application.session.SessionRepository;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ChatExecutionService;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.chat.RunWaitingException;
import dev.mikoto2000.rei.core.policy.ActionCapability;
import dev.mikoto2000.rei.core.policy.ApprovalContinuationService;
import dev.mikoto2000.rei.core.policy.ApprovalContinuationStore;
import dev.mikoto2000.rei.core.policy.ToolApprovalRepository;
import dev.mikoto2000.rei.core.policy.ToolPermissionGuard;
import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.stagnation.ProgressEvaluator;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.core.stagnation.StagnationChatModel;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.llm.discovery.ToolDiscovery;
import dev.mikoto2000.rei.llm.discovery.ToolDiscoveryProperties;
import dev.mikoto2000.rei.llm.discovery.ToolDiscoveryStore;
import dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** Real inference consent, discovery persistence and SDK transport; business effects stay local. */
class InferenceDynamicApprovalLoopTest {
  @TempDir Path directory;
  private static final String SPECIALIST = "googleCalendarListEventsForDate";

  static Stream<Arguments> routesAndModes() {
    return java.util.Arrays.stream(AgentRunContext.Mode.values())
        .flatMap(mode -> Stream.of(false, true).map(plan -> Arguments.of(mode, plan)));
  }

  @ParameterizedTest
  @MethodSource("routesAndModes")
  void explicitApprovalsRestorePublishedToolsAndNeverRepeatTheSuccessfulOperation(
      AgentRunContext.Mode mode, boolean plan) throws Exception {
    try (var fixture = new ResponsesLoopFixture(plan)) {
      var clock = Clock.systemUTC();
      var owner =
          new AgentRunContext(
              "fixture-run",
              "fixture-session",
              directory,
              UUID.randomUUID().toString(),
              AgentRunContext.RequestSource.SHELL,
              mode);
      var data = new org.sqlite.SQLiteDataSource();
      data.setUrl("jdbc:sqlite:" + directory.resolve("continuation.db"));
      var consent = consent(data, owner, clock);
      var events = new AgentEventFactory(clock);
      var discoveryProperties = new ToolDiscoveryProperties();
      discoveryProperties.setEnabled(true);
      var ledger =
          new ToolDiscoveryStore(
              data,
              provider(dev.mikoto2000.rei.review.ReviewCoverageLedger.class, null),
              provider(dev.mikoto2000.rei.durable.DurableTaskStore.class, null));
      var discovery =
          new ToolDiscovery(discoveryProperties, provider(ToolDiscoveryStore.class, ledger));
      var executions = new AtomicInteger();
      var effects = directory.resolve("effects.txt");
      List<ToolCallback> catalog =
          Files.readAllLines(Path.of("docs/tool-discovery-classification.csv")).stream()
              .skip(1)
              .filter(row -> !row.isBlank())
              .map(row -> row.split(",")[0])
              .map(name ->
                  (ToolCallback)
                      new ToolCallback() {
                        public ToolDefinition getToolDefinition() {
                          return ToolDefinition.builder()
                              .name(name)
                              .description(name)
                              .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                              .build();
                        }

                        public String call(String input) {
                          assertThat(name).isEqualTo(SPECIALIST);
                          executions.incrementAndGet();
                          try {
                            Files.writeString(
                                effects,
                                "completed\n",
                                java.nio.file.StandardOpenOption.CREATE,
                                java.nio.file.StandardOpenOption.APPEND);
                          } catch (java.io.IOException failure) {
                            throw new AssertionError(failure);
                          }
                          return "SYNTHETIC_CALENDAR_RESULT";
                        }
                      })
              .toList();
      var callbacks = mode == AgentRunContext.Mode.CONVERSATION ? List.<ToolCallback>of() : catalog;
      fixture.replies =
          turn -> {
            if (mode == AgentRunContext.Mode.CONVERSATION || turn > 2) return fixture.reply;
            return PlanDynamicToolLoopTest.completedCall(
                plan,
                turn,
                turn == 1 ? "searchTools" : SPECIALIST,
                turn == 1 ? "{\"query\":\"" + SPECIALIST + "\",\"maxResults\":1}" : "{}");
          };
      var execution = context(data, owner, fixture, consent, callbacks, events, false);
      var model =
          ResponsesAdapter.createForRun(
              fixture.pin,
              (org.springframework.ai.openai.responses.OpenAiResponsesChatOptions)
                  execution.modelOptions(),
              fixture.client);
      var loop = new StagnationChatModel(model).withToolDiscovery(discovery);
      var firstPrompt = new Prompt("synthetic calendar request", execution.modelOptions());
      assertThatThrownBy(() -> loop.stream(firstPrompt).blockLast(Duration.ofSeconds(10)))
          .isInstanceOf(RunWaitingException.class);
      assertThat(fixture.requests).isEmpty();
      assertThat(executions).hasValue(0);
      int requests = mode == AgentRunContext.Mode.CONVERSATION ? 1 : 3;
      for (int round = 0; round < requests; round++) {
        assertThat(fixture.requests).hasSize(round);
        var pending = consent.requests.list(owner.projectId());
        assertThat(pending).hasSize(1);
        var approval = pending.getFirst();
        var entry = consent.store.find(approval.id()).orElseThrow();
        var document = consent.store.document(entry);
        assertThat(document.connection()).isEqualTo(fixture.pin);
        assertThat(document.pending()).isNull();
        assertThat(document.budget().llmCalls()).isEqualTo(round + 1);
        assertThat(document.budget().totalTokens()).isEqualTo(round * 5L);
        assertThat(document.budget().usageUnknown()).isFalse();
        if (round > 0)
          assertThat(document.inference().tools()).anyMatch(t -> t.name().equals(SPECIALIST));
        if (round == 2) {
          assertThat(executions).hasValue(1);
          assertThat(
                  consent.store.saved(entry).messages().stream()
                      .filter(
                          org.springframework.ai.chat.messages.ToolResponseMessage.class
                              ::isInstance)
                      .map(org.springframework.ai.chat.messages.ToolResponseMessage.class::cast)
                      .flatMap(message -> message.getResponses().stream())
                      .map(
                          org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse
                              ::responseData)
                      .toList())
              .anyMatch(result -> result.contains("SYNTHETIC_CALENDAR_RESULT"));
        }
        consent.continuations.decide(owner.projectId(), approval.id(), true, true);
        assertThat(consent.store.claim(approval.id())).isTrue();
        assertThat(consent.registry.transition(owner.runId(), RunStatus.RUNNING, null)).isTrue();
        execution = context(data, owner, fixture, consent, callbacks, events, true);
        var nextPrompt = new Prompt("synthetic calendar request", execution.modelOptions());
        if (round < requests - 1)
          assertThatThrownBy(() -> loop.stream(nextPrompt).blockLast(Duration.ofSeconds(10)))
              .isInstanceOf(RunWaitingException.class);
        else loop.stream(nextPrompt).blockLast(Duration.ofSeconds(10));
        assertThat(consent.requests.get(owner.projectId(), approval.id()).status())
            .isEqualTo("CONSUMED");
      }
      assertThat(fixture.requests).hasSize(requests);
      assertThat(executions).hasValue(mode == AgentRunContext.Mode.CONVERSATION ? 0 : 1);
      if (mode != AgentRunContext.Mode.CONVERSATION) {
        assertThat(Files.readString(effects)).isEqualTo("completed\n");
        assertThat(fixture.requests.get(2).path("input").toString())
            .contains("SYNTHETIC_CALENDAR_RESULT");
      }
      assertThat(consent.requests.list(owner.projectId())).isEmpty();
      assertThat(execution.budgetSnapshot().llmCalls()).isEqualTo(requests);
      assertThat(execution.budgetSnapshot().totalTokens()).isEqualTo(requests * 5L);
      assertThat(execution.budgetSnapshot().usageUnknown()).isFalse();
      execution.completeRun();
    }
  }

  private RunExecutionContext context(
      org.sqlite.SQLiteDataSource data,
      AgentRunContext owner,
      ResponsesLoopFixture fixture,
      Consent consent,
      List<ToolCallback> callbacks,
      AgentEventFactory events,
      boolean resumed) {
    var context =
        new RunExecutionContext(
            owner.runId(),
            new OutputLimitRunBudget(3, 8, null, 1000),
            new ProgressEvaluator(directory),
            events,
            ignored -> {});
    context.setRunContext(owner);
    context.setUserRequest("synthetic calendar request");
    context.setContinuation(consent.continuations);
    context.setToolPermissionGuard(
        new ToolPermissionGuard(
            new ToolPermissionPolicy(
                new ToolPermissionProperties(
                    true,
                    Set.of(ActionCapability.READ, ActionCapability.NETWORK_READ),
                    null,
                    null)),
            events,
            ignored -> {}));
    if (resumed) {
      context.resume(consent.continuations.restore(owner));
      context.beginContextRestoration();
    } else context.consumeNextLlmCall();
    var exchange = new ModelMessagePayloadStore(data).newExchange(owner);
    var values = new java.util.HashMap<>(fixture.options().getToolContext());
    values.put(RunExecutionContext.KEY, context);
    values.put(
        dev.mikoto2000.rei.modeloptions.ModelOptions.RUN_CONNECTION_CONTEXT_KEY, fixture.pin);
    values.put(ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY, exchange);
    values.put(
        ModelMessagePayloadStore.ORIGIN_CONTEXT_KEY,
        new ModelMessagePayloadStore.Origin(exchange.ownerKey(), fixture.pin.fingerprint()));
    values.put(ResponsesReasoningReplay.OWNER_CONTEXT_KEY, exchange.ownerKey());
    context.setModelOptions(
        fixture
            .options()
            .mutate()
            .toolContext(null)
            .toolContext(values)
            .toolCallbacks(callbacks)
            .build());
    return context;
  }

  private Consent consent(org.sqlite.SQLiteDataSource data, AgentRunContext owner, Clock clock) {
    var registry = new RunRegistry(clock, data);
    var bus = new InMemoryAgentEventBus();
    var runs =
        new RunService(
            registry,
            bus,
            new AgentEventFactory(clock),
            new CommandCancellationService(),
            id -> false);
    var projects = mock(ProjectRegistry.class);
    when(projects.resolveById(owner.projectId()))
        .thenReturn(Optional.of(new ProjectContext(owner.projectId(), "fixture", directory)));
    var sessions = mock(SessionRepository.class);
    when(sessions.findById(owner.conversationId()))
        .thenReturn(
            Optional.of(
                new SessionMetadata(
                    owner.conversationId(),
                    owner.projectId(),
                    "fixture",
                    clock.instant(),
                    clock.instant())));
    var requests = new ToolApprovalRepository(data, clock);
    var store = new ApprovalContinuationStore(data);
    var service =
        new ApprovalContinuationService(
            requests,
            store,
            provider(RunRegistry.class, registry),
            provider(RunService.class, runs),
            provider(ConversationInputRouter.class, mock(ConversationInputRouter.class)),
            provider(ChatExecutionService.class, mock(ChatExecutionService.class)),
            provider(dev.mikoto2000.rei.slack.SlackAgentService.class, null),
            provider(ProjectRegistry.class, projects),
            provider(SessionRepository.class, sessions),
            data,
            clock);
    registry.register(owner);
    registry.transition(owner.runId(), RunStatus.RUNNING, null);
    return new Consent(requests, store, registry, service);
  }

  private record Consent(
      ToolApprovalRepository requests,
      ApprovalContinuationStore store,
      RunRegistry registry,
      ApprovalContinuationService continuations) {}

  private static <T> ObjectProvider<T> provider(Class<T> type, T value) {
    var beans = new StaticListableBeanFactory();
    if (value != null) beans.addBean("value", value);
    return beans.getBeanProvider(type);
  }
}
