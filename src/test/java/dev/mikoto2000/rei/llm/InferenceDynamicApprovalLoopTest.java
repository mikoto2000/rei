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
    executeApprovedWorkflow(mode, plan, null);
  }

  static Stream<Arguments> interruptedRoutes() {
    return Stream.of(false, true)
        .flatMap(plan ->
            Stream.of("eof", "failed", "cancelled", "incomplete")
                .map(ending -> Arguments.of(plan, ending)));
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void cancellingAnInferenceWaitPreventsLaterApprovalAndAnyHttp(boolean plan) throws Exception {
    try (var fixture = new ResponsesLoopFixture(plan)) {
      var clock = Clock.systemUTC();
      var owner =
          new AgentRunContext(
              "cancel-run",
              "session",
              directory,
              UUID.randomUUID().toString(),
              AgentRunContext.RequestSource.SHELL,
              AgentRunContext.Mode.CONVERSATION);
      var data = new org.sqlite.SQLiteDataSource();
      data.setUrl("jdbc:sqlite:" + directory.resolve("cancel.db"));
      var consent = consent(data, owner, clock);
      var execution =
          context(data, owner, fixture, consent, List.of(), new AgentEventFactory(clock), false);
      var model =
          ResponsesAdapter.createForRun(
              fixture.pin,
              (org.springframework.ai.openai.responses.OpenAiResponsesChatOptions)
                  execution.modelOptions(),
              fixture.client);
      var loop = new StagnationChatModel(model);
      assertThatThrownBy(() ->
              loop.stream(new Prompt("request", execution.modelOptions()))
                  .blockLast(Duration.ofSeconds(10)))
          .isInstanceOf(RunWaitingException.class);
      var approval = consent.requests.list(owner.projectId()).getFirst();
      execution.cancel();
      assertThat(consent.registry.transition(owner.runId(), RunStatus.CANCELLED, null)).isTrue();
      assertThatThrownBy(
              () -> consent.continuations.decide(owner.projectId(), approval.id(), true, true))
          .isInstanceOf(IllegalStateException.class);
      consent.continuations.pump();
      assertThat(fixture.requests).isEmpty();
      assertThat(execution.budgetSnapshot().llmCalls()).isEqualTo(1);
      assertThat(execution.budgetSnapshot().totalTokens()).isZero();
      assertThat(consent.store.find(approval.id()).orElseThrow().state()).isEqualTo("CANCELLED");
    }
  }

  @ParameterizedTest
  @MethodSource("interruptedRoutes")
  void interruptedApprovedResponseNeverRepeatsTheCompletedBusinessEffect(
      boolean plan, String ending) throws Exception {
    executeApprovedWorkflow(AgentRunContext.Mode.EXCLUSIVE, plan, ending);
  }

  private void executeApprovedWorkflow(AgentRunContext.Mode mode, boolean plan, String ending)
      throws Exception {
    executeApprovedWorkflow(mode, plan, ending, null);
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void completedLocalOperationsContinueThroughPlanAndBackToLocalChatOnlyWithFreshConsent(
      boolean denied) throws Exception {
    executeApprovedWorkflow(AgentRunContext.Mode.EXCLUSIVE, false, null, denied ? "deny" : "allow");
  }

  private void executeApprovedWorkflow(
      AgentRunContext.Mode mode, boolean plan, String ending, String historySwitch)
      throws Exception {
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
            if (turn == 3 && ending != null) {
              if (ending.equals("eof")) return "";
              return fixture
                  .reply
                  .replace("response.completed", "response." + ending)
                  .replaceFirst("\"status\":\"completed\"", "\"status\":\"" + ending + "\"");
            }
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
        else if (ending == null) loop.stream(nextPrompt).blockLast(Duration.ofSeconds(10));
        else
          assertThatThrownBy(() -> loop.stream(nextPrompt).blockLast(Duration.ofSeconds(10)))
              .isNotInstanceOf(RunWaitingException.class);
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
      if (ending == null) {
        assertThat(execution.budgetSnapshot().totalTokens()).isEqualTo(requests * 5L);
        assertThat(execution.budgetSnapshot().usageUnknown()).isFalse();
      }
      if (historySwitch == null) execution.completeRun();
      else assertCrossRouteHistory(data, execution, executions, historySwitch.equals("deny"));
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
    return context(
        data,
        owner,
        new Route(fixture.pin, fixture.options()),
        consent,
        callbacks,
        events,
        resumed);
  }

  private record Route(
      RunLlmConnection pin, org.springframework.ai.model.tool.ToolCallingChatOptions options) {}

  private RunExecutionContext context(
      org.sqlite.SQLiteDataSource data,
      AgentRunContext owner,
      Route route,
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
    var values = new java.util.HashMap<>(route.options().getToolContext());
    values.put(RunExecutionContext.KEY, context);
    values.put(
        dev.mikoto2000.rei.modeloptions.ModelOptions.RUN_CONNECTION_CONTEXT_KEY, route.pin());
    values.put(ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY, exchange);
    values.put(
        ModelMessagePayloadStore.ORIGIN_CONTEXT_KEY,
        new ModelMessagePayloadStore.Origin(exchange.ownerKey(), route.pin().fingerprint()));
    values.put(ResponsesReasoningReplay.OWNER_CONTEXT_KEY, exchange.ownerKey());
    context.setModelOptions(
        route
            .options()
            .mutate()
            .toolContext(null)
            .toolContext(values)
            .toolCallbacks(callbacks)
            .build());
    return context;
  }

  private record HistoryFixture(
      org.sqlite.SQLiteDataSource data,
      dev.mikoto2000.rei.conversation.ConversationTurnStore turns,
      dev.mikoto2000.rei.conversation.ConversationLogStore logs,
      dev.mikoto2000.rei.core.contextbudget.ContextHistoryAdvisor advisor,
      ModelMessagePayloadStore payloads) {}

  private record HistoryHop(
      AgentRunContext owner,
      Route route,
      java.util.function.Function<
              org.springframework.ai.model.tool.ToolCallingChatOptions,
              org.springframework.ai.chat.model.ChatModel>
          model,
      java.util.function.IntSupplier requests,
      boolean streaming) {}

  private void assertCrossRouteHistory(
      org.sqlite.SQLiteDataSource data,
      RunExecutionContext source,
      AtomicInteger operations,
      boolean denied)
      throws Exception {
    var turns = dev.mikoto2000.rei.conversation.ConversationTurnStore.inMemory();
    var logs =
        new dev.mikoto2000.rei.conversation.ConversationLogStore(
            directory.resolve("cross-history"),
            Clock.systemUTC(),
            new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()));
    var payloads = new ModelMessagePayloadStore(data);
    var history =
        new HistoryFixture(
            data,
            turns,
            logs,
            new dev.mikoto2000.rei.core.contextbudget.ContextHistoryAdvisor(
                turns,
                dev.mikoto2000.rei.chatmemory.WindowedConversationMemory.builder().build(),
                new dev.mikoto2000.rei.core.contextbudget.ConversationSummaryRepository(directory),
                logs,
                () -> payloads),
            payloads);
    var sourcePointers = publishCompletedHistory(history, source);
    var scope = ModelMessagePayloadStore.Scope.forOwner(source.runContext());
    var codec = new dev.mikoto2000.rei.modelmessage.ModelMessageCodec();
    var sourceBytes =
        sourcePointers.stream()
            .map(pointer -> codec.encode(payloads.restoreHistory(scope, pointer).orElseThrow()))
            .toList();
    source.completeRun();
    try (var plan = new ResponsesLoopFixture(true);
        var local = new PinnedChatModelsTest.Fixture();
        var models =
            new PinnedChatModels(
                pin -> {
                  throw new AssertionError("No-auth history read credentials");
                },
                pin -> {},
                null,
                2)) {
      plan.replies =
          turn ->
              plan.reply.replace(
                  "\"output\":[",
                  "\"output\":[{\"type\":\"reasoning\",\"id\":\"rs_plan_private\",\"summary\":[],\"encrypted_content\":\"OPAQUE_PLAN_PRIVATE\"},");
      var original = source.runContext();
      var planOwner =
          new AgentRunContext(
              "cross-plan",
              original.conversationId(),
              directory,
              original.projectId(),
              AgentRunContext.RequestSource.SHELL,
              AgentRunContext.Mode.EXCLUSIVE);
      var planExecution =
          historyHop(
              history,
              new HistoryHop(
                  planOwner,
                  new Route(plan.pin, plan.options()),
                  options ->
                      ResponsesAdapter.createForRun(
                          plan.pin,
                          (org.springframework.ai.openai.responses.OpenAiResponsesChatOptions)
                              options,
                          plan.client),
                  () -> plan.requests.size(),
                  true),
              denied);
      assertThat(operations).hasValue(1);
      if (denied) {
        assertThat(plan.requests).isEmpty();
        assertThat(local.bodies).isEmpty();
      } else {
        assertThat(plan.requests).hasSize(1);
        assertThat(plan.requests.getFirst().path("input").toString())
            .contains("SYNTHETIC_CALENDAR_RESULT")
            .doesNotContain("\"type\":\"function_call\"");
        var planPointers = publishCompletedHistory(history, planExecution);
        var planScope = ModelMessagePayloadStore.Scope.forOwner(planOwner);
        var planBytes =
            planPointers.stream()
                .map(pointer ->
                    codec.encode(payloads.restoreHistory(planScope, pointer).orElseThrow()))
                .toList();
        assertThat(
                planBytes.stream()
                    .map(bytes -> new String(bytes, java.nio.charset.StandardCharsets.UTF_8)))
            .anyMatch(text -> text.contains("OPAQUE_PLAN_PRIVATE"));
        planExecution.completeRun();
        var base = local.pin("back-local", "fixture-model", LlmAuthentication.NONE);
        var pin =
            new RunLlmConnection(
                base.profileId(),
                true,
                base.provider(),
                base.authentication(),
                null,
                null,
                base.endpoint(),
                java.util.Map.of(
                    LlmEndpointCapabilities.Capability.CHAT_COMPLETIONS,
                    LlmEndpointCapabilities.Support.SUPPORTED,
                    LlmEndpointCapabilities.Capability.STREAMING,
                    LlmEndpointCapabilities.Support.SUPPORTED,
                    LlmEndpointCapabilities.Capability.MAX_OUTPUT_TOKENS,
                    LlmEndpointCapabilities.Support.SUPPORTED),
                base.generation());
        var localOwner =
            new AgentRunContext(
                "cross-local-chat",
                original.conversationId(),
                directory,
                original.projectId(),
                AgentRunContext.RequestSource.SHELL,
                AgentRunContext.Mode.EXCLUSIVE);
        var localExecution =
            historyHop(
                history,
                new HistoryHop(
                    localOwner,
                    new Route(pin, RunNativeOptions.options(pin)),
                    options -> models.model(pin),
                    () -> local.bodies.size(),
                    false),
                false);
        assertThat(local.bodies).hasSize(1);
        assertThat(local.headers).containsExactly("<none>");
        assertThat(local.bodies.getFirst().path("messages").toString())
            .contains("SYNTHETIC_CALENDAR_RESULT", "done")
            .doesNotContain(
                "OPAQUE_PLAN_PRIVATE", "encrypted_content", "tool_calls", "reasoning_content");
        assertThat(operations).hasValue(1);
        for (int i = 0; i < planPointers.size(); i++)
          assertThat(
                  codec.encode(
                      payloads.restoreHistory(planScope, planPointers.get(i)).orElseThrow()))
              .isEqualTo(planBytes.get(i));
        localExecution.completeRun();
      }
      for (int i = 0; i < sourcePointers.size(); i++)
        assertThat(
                codec.encode(payloads.restoreHistory(scope, sourcePointers.get(i)).orElseThrow()))
            .isEqualTo(sourceBytes.get(i));
    }
  }

  private java.util.List<java.util.Map<String, String>> publishCompletedHistory(
      HistoryFixture history, RunExecutionContext execution) {
    var owner = execution.runContext();
    var pointers = execution.protocolHistoryReferences();
    assertThat(pointers).isNotEmpty();
    history.turns().start(owner, "synthetic calendar request", java.time.Instant.now());
    history
        .logs()
        .append(
            owner.conversationId(),
            "user",
            "synthetic calendar request",
            java.util.Map.of(ModelMessagePayloadStore.HISTORY_RUN, owner.runId()));
    var metadata =
        new java.util.HashMap<>(
            history.logs().appendProtocolHistory(owner.conversationId(), owner.runId(), pointers));
    metadata.put(ModelMessagePayloadStore.HISTORY_ENDPOINT, execution.protocolHistoryEndpoint());
    history.turns().recordMetadata(owner, metadata);
    history
        .turns()
        .finish(
            owner, dev.mikoto2000.rei.conversation.ConversationTurnStore.Status.COMPLETED, "done");
    history
        .logs()
        .append(
            owner.conversationId(),
            "assistant",
            "done",
            java.util.Map.of(ModelMessagePayloadStore.HISTORY_RUN, owner.runId()));
    return pointers;
  }

  private RunExecutionContext historyHop(
      HistoryFixture history, HistoryHop destination, boolean denied) {
    var owner = destination.owner();
    var clock = Clock.systemUTC();
    var consent = consent(history.data(), owner, clock);
    var events = new AgentEventFactory(clock);
    var execution =
        context(history.data(), owner, destination.route(), consent, List.of(), events, false);
    var initial =
        history
            .advisor()
            .before(
                org.springframework.ai.chat.client.ChatClientRequest.builder()
                    .prompt(new Prompt("synthetic calendar request", execution.modelOptions()))
                    .context(
                        java.util.Map.of(
                            org.springframework.ai.chat.client.advisor.api.MemoryAdvisor
                                .CONVERSATION_ID,
                            owner.conversationId(),
                            AgentRunContext.class.getName(),
                            owner))
                    .build(),
                null)
            .prompt();
    var loop = new StagnationChatModel(destination.model().apply(execution.modelOptions()));
    int before = destination.requests().getAsInt();
    assertThatThrownBy(() -> invokeHistoryHop(loop, initial, destination.streaming()))
        .isInstanceOf(RunWaitingException.class);
    assertThat(destination.requests().getAsInt()).isEqualTo(before);
    var pending = consent.requests.list(owner.projectId());
    assertThat(pending).hasSize(1);
    var approval = pending.getFirst();
    var entry = consent.store.find(approval.id()).orElseThrow();
    assertThat(consent.store.document(entry).connection()).isEqualTo(destination.route().pin());
    var reviewed =
        consent.store.saved(entry).messages().stream()
            .map(message ->
                new String(
                    new dev.mikoto2000.rei.modelmessage.ModelMessageCodec().encode(message),
                    java.nio.charset.StandardCharsets.UTF_8))
            .collect(java.util.stream.Collectors.joining("\n"));
    assertThat(reviewed)
        .contains("SYNTHETIC_CALENDAR_RESULT")
        .doesNotContain("OPAQUE_PLAN_PRIVATE");
    consent.continuations.decide(owner.projectId(), approval.id(), !denied, !denied);
    if (denied) {
      assertThat(consent.requests.get(owner.projectId(), approval.id()).status())
          .isEqualTo("DENIED");
      assertThat(destination.requests().getAsInt()).isEqualTo(before);
      execution.completeRun();
      return null;
    }
    assertThat(consent.store.claim(approval.id())).isTrue();
    assertThat(consent.registry.transition(owner.runId(), RunStatus.RUNNING, null)).isTrue();
    execution =
        context(history.data(), owner, destination.route(), consent, List.of(), events, true);
    invokeHistoryHop(
        loop,
        new Prompt("synthetic calendar request", execution.modelOptions()),
        destination.streaming());
    assertThat(destination.requests().getAsInt()).isEqualTo(before + 1);
    assertThat(consent.requests.get(owner.projectId(), approval.id()).status())
        .isEqualTo("CONSUMED");
    return execution;
  }

  private static void invokeHistoryHop(
      StagnationChatModel model, Prompt prompt, boolean streaming) {
    if (streaming) model.stream(prompt).blockLast(Duration.ofSeconds(10));
    else model.call(prompt);
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
