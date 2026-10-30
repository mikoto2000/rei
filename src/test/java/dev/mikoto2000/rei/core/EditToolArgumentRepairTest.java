package dev.mikoto2000.rei.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ChatExecutionResult;
import dev.mikoto2000.rei.core.chat.ChatExecutionService;
import dev.mikoto2000.rei.core.chat.UserInterventionQueue;
import dev.mikoto2000.rei.core.contextbudget.DeliveryToolRegistration;
import dev.mikoto2000.rei.core.policy.ActionCapability;
import dev.mikoto2000.rei.core.policy.ToolApprovalRepository;
import dev.mikoto2000.rei.core.policy.ToolPermissionGuard;
import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.core.stagnation.StagnationChatModel;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.ToolEventCallbackDecorator;
import dev.mikoto2000.rei.event.ToolFailedPayload;
import dev.mikoto2000.rei.event.ToolStartedPayload;
import dev.mikoto2000.rei.llm.FixedLlmChatClientProvider;
import dev.mikoto2000.rei.llm.FixedLlmModelProvider;
import dev.mikoto2000.rei.llm.LlmProperties;
import dev.mikoto2000.rei.llm.RunAwareToolCallingAdvisor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import reactor.core.publisher.Flux;

/**
 * Real argument binding, delivery/event decorators, model repair loop and isolated proposal store.
 */
class EditToolArgumentRepairTest {
  @TempDir Path root;
  private final com.fasterxml.jackson.databind.ObjectMapper json =
      new com.fasterxml.jackson.databind.ObjectMapper();

  static Stream<Arguments> invalidProposals() {
    return Stream.of(false, true)
        .flatMap(
            guarded ->
                Stream.of(
                    Arguments.of(guarded, false, "missing"), Arguments.of(guarded, false, "null"),
                    Arguments.of(guarded, false, "blank"), Arguments.of(guarded, false, "type"),
                    Arguments.of(guarded, true, "missing"), Arguments.of(guarded, true, "null"),
                    Arguments.of(guarded, true, "blank"), Arguments.of(guarded, true, "kind")));
  }

  @ParameterizedTest
  @MethodSource("invalidProposals")
  void nativeProposalContinuesAfterInvalidPathWithoutExecutionOrApproval(
      boolean guarded, boolean multi, String kind) throws Exception {
    var setup = setup();
    String name = multi ? "proposeTextDocumentChangeSet" : "proposeTextChangeSet";
    String valid = proposal(setup, multi);
    var invalid = json.readTree(valid);
    var item =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            (multi ? invalid.path("request").path("operations").get(0) : invalid.path("request"));
    switch (kind) {
      case "missing" -> item.remove("path");
      case "null" -> item.putNull("path");
      case "blank" -> item.put("path", " ");
      case "type" -> item.put("path", 42);
      case "kind" -> item.put("kind", "update");
      default -> throw new AssertionError(kind);
    }
    var fixture =
        fixture(
            setup,
            guarded,
            false,
            (prompt, invocation) -> {
              if (invocation == 1) return response(call("bad", name, invalid.toString()));
              if (invocation == 2) {
                assertThat(proposals(setup)).isZero();
                var correction = latest(prompt);
                assertThat(correction.path("error").asText()).isEqualTo("INVALID_TOOL_ARGUMENTS");
                assertThat(correction.path("executed").asBoolean()).isFalse();
                assertThat(correction.path("correctionsRemaining").asInt()).isEqualTo(1);
                return response(call("fixed", name, valid));
              }
              assertThat(invocation).isEqualTo(3);
              assertThat(latest(prompt).path("status").asText()).isEqualTo("PROPOSED");
              return finished();
            });
    var result = fixture.execute();
    assertThat(result.success()).isTrue();
    assertThat(fixture.calls()).hasValue(3);
    assertThat(proposals(setup)).isEqualTo(1);
    assertThat(Files.readString(root.resolve("note.md"))).isEqualTo("old\n");
    assertThat(started(fixture, name)).isEqualTo(1);
    verifyNoInteractions(fixture.approvals());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void schemaPreflightRepairsAnotherRealToolAndPreservesSuccessfulSibling(boolean guarded)
      throws Exception {
    var setup = setup();
    String valid = proposal(setup, false);
    var fixture =
        fixture(
            setup,
            guarded,
            false,
            (prompt, invocation) -> {
              if (invocation == 1)
                return response(
                    call("sibling", "countFileLines", "{\"paths\":[\"note.md\"]}"),
                    call("bad", "proposeTextChangeSet", "{\"request\":{}}"));
              if (invocation == 2) return response(call("fixed", "proposeTextChangeSet", valid));
              if (invocation == 3) return response(call("bad-other", "countFileLines", "{}"));
              if (invocation == 4) {
                assertThat(latest(prompt).path("executed").asBoolean()).isFalse();
                assertThat(latest(prompt).path("guidance").asText()).contains("paths");
                return response(call("fixed-other", "countFileLines", "{\"paths\":[\"note.md\"]}"));
              }
              assertThat(invocation).isEqualTo(5);
              assertThat(latest(prompt).path("totalLines").asInt()).isEqualTo(1);
              return finished();
            });
    assertThat(fixture.execute().success()).isTrue();
    assertThat(started(fixture, "countFileLines")).isEqualTo(2);
    assertThat(started(fixture, "proposeTextChangeSet")).isEqualTo(1);
    assertThat(proposals(setup)).isEqualTo(1);
    verifyNoInteractions(fixture.approvals());
  }

  @Test
  void repairBudgetIsSharedAcrossToolsAndCannotRunAnInvalidCallAfterExhaustion() throws Exception {
    var setup = setup();
    var remaining = new ArrayList<Integer>();
    var fixture =
        fixture(
            setup,
            true,
            false,
            (prompt, invocation) -> {
              assertThat(invocation).isLessThanOrEqualTo(3);
              if (invocation > 1)
                remaining.add(latest(prompt).path("correctionsRemaining").asInt());
              return response(
                  call(
                      "bad-" + invocation,
                      invocation == 2 ? "countFileLines" : "proposeTextChangeSet",
                      "{}"));
            });
    var result = fixture.execute();
    assertThat(result.stopCode()).isEqualTo("tool_argument_repair_exhausted");
    assertThat(remaining).containsExactly(1, 0);
    assertThat(proposals(setup)).isZero();
    assertThat(fixture.events().stream().filter(e -> e.type() == AgentEventType.TOOL_STARTED))
        .isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"io", "unknown", "ownership"})
  void runtimeFailuresAreNotReclassifiedAsUnexecutedArgumentCorrections(String kind)
      throws Exception {
    var setup = setup();
    Exception failure =
        switch (kind) {
          case "io" -> new java.io.IOException("disk unavailable");
          case "unknown" -> new IllegalStateException("unknown operation outcome");
          default -> new IllegalArgumentException("foreign proposal owner");
        };
    doThrow(failure)
        .when(setup.service())
        .propose(any(ProjectContext.class), any(TextChangeSetService.Request.class));
    var fixture =
        fixture(
            setup,
            true,
            false,
            (prompt, invocation) -> {
              assertThat(invocation).isEqualTo(1);
              return response(call("runtime", "proposeTextChangeSet", proposalUnchecked(setup)));
            });
    assertThat(fixture.execute().success()).isFalse();
    assertThat(fixture.calls()).hasValue(1);
    assertThat(invalidEvents(fixture)).isZero();
    verify(setup.service(), times(1))
        .propose(any(ProjectContext.class), any(TextChangeSetService.Request.class));
  }

  @Test
  void validArgumentsDoNotBypassWriteDenial() throws Exception {
    var setup = setup();
    var fixture =
        fixture(
            setup,
            true,
            true,
            (prompt, invocation) -> {
              assertThat(invocation).isEqualTo(1);
              return response(call("denied", "proposeTextChangeSet", proposalUnchecked(setup)));
            });
    assertThat(fixture.execute().success()).isFalse();
    assertThat(invalidEvents(fixture)).isZero();
    verify(setup.service(), never())
        .propose(any(ProjectContext.class), any(TextChangeSetService.Request.class));
    verifyNoInteractions(fixture.approvals());
  }

  @Test
  void everyNativeRequiredContractRejectsMissingFieldsBeforeInvocation() {
    var required =
        Arrays.stream(DeliveryToolRegistration.provider(new Tools()).getToolCallbacks())
            .filter(
                c ->
                    dev.mikoto2000.rei.core.policy.ToolSchemaArguments.supports(
                        c.getToolDefinition().inputSchema()))
            .toList();
    assertThat(required.size()).isGreaterThan(15);
    for (var callback : required) {
      var definition = callback.getToolDefinition();
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () ->
                  dev.mikoto2000.rei.core.policy.ToolSchemaArguments.validate(
                      definition.name(), definition.inputSchema(), "{}"))
          .as(definition.name())
          .isInstanceOf(dev.mikoto2000.rei.core.policy.InvalidToolArgumentsException.class);
    }
  }

  private Setup setup() throws Exception {
    Files.writeString(root.resolve("note.md"), "old\n");
    var ds = new DriverManagerDataSource("jdbc:sqlite:" + root.resolve("changes.db"));
    var repository = new TextChangeSetRepository(ds);
    var service = spy(new TextChangeSetService(repository));
    service.setDocuments(new TextDocumentChangeSetService(repository, Clock.systemUTC()));
    var tools = new Tools();
    tools.setTextChangeSets(service);
    String version =
        service
            .readBase(
                new ProjectContext("bfb23e53-361d-4cb6-bc51-b93167bc5000", "test", root), "note.md")
            .sha256();
    return new Setup(tools, service, new JdbcTemplate(ds), version);
  }

  private int proposals(Setup setup) {
    return setup
        .db()
        .queryForObject(
            "SELECT (SELECT COUNT(*) FROM text_change_sets)+(SELECT COUNT(*) FROM text_document_change_sets)",
            Integer.class);
  }

  private String proposal(Setup setup, boolean multi) throws Exception {
    Map<String, Object> edit =
        new LinkedHashMap<>(
            Map.of(
                "path",
                "note.md",
                "baseVersion",
                setup.version(),
                "edits",
                List.of(Map.of("oldText", "old", "newText", "new"))));
    if (multi) edit.put("kind", "UPDATE");
    return json.writeValueAsString(
        Map.of("request", multi ? Map.of("operations", List.of(edit)) : edit));
  }

  private String proposalUnchecked(Setup setup) {
    try {
      return proposal(setup, false);
    } catch (Exception error) {
      throw new AssertionError(error);
    }
  }

  private Fixture fixture(
      Setup setup,
      boolean guarded,
      boolean denyWrite,
      BiFunction<Prompt, Integer, ChatResponse> responses) {
    var calls = new AtomicInteger();
    ChatModel model =
        new ChatModel() {
          @Override
          public ChatResponse call(Prompt prompt) {
            throw new AssertionError("stream only");
          }

          @Override
          public ToolCallingChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
          }

          @Override
          public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(responses.apply(prompt, calls.incrementAndGet()));
          }
        };
    var clock = Clock.systemUTC();
    var factory = new AgentEventFactory(clock);
    var events = new ArrayList<AgentEvent>();
    var callbacks =
        Arrays.stream(DeliveryToolRegistration.provider(setup.tools()).getToolCallbacks())
            .filter(
                c ->
                    Set.of("proposeTextChangeSet", "proposeTextDocumentChangeSet", "countFileLines")
                        .contains(c.getToolDefinition().name()))
            .<ToolCallback>map(c -> new ToolEventCallbackDecorator(c, factory, events::add))
            .toList();
    var client =
        ChatClient.builder(new StagnationChatModel(model))
            .defaultAdvisors(new RunAwareToolCallingAdvisor())
            .defaultToolCallbacks(callbacks)
            .build();
    var properties = new LlmProperties();
    properties.getOutputLimit().setMaxToolArgumentCorrectionsPerRun(2);
    var holder = mock(ModelHolderService.class);
    when(holder.get()).thenReturn("test");
    var execution =
        new ChatExecutionService(
            new FixedLlmChatClientProvider(client),
            holder,
            new FixedLlmModelProvider(),
            properties,
            new CommandCancellationService(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            clock,
            Optional.empty(),
            factory,
            events::add);
    var approvals = mock(ToolApprovalRepository.class);
    if (guarded) {
      var guard =
          new ToolPermissionGuard(
              new ToolPermissionPolicy(
                  new ToolPermissionProperties(
                      true,
                      denyWrite
                          ? Set.of(ActionCapability.READ)
                          : Set.of(ActionCapability.READ, ActionCapability.LOCAL_WRITE),
                      denyWrite ? Set.of(ActionCapability.LOCAL_WRITE) : Set.of(),
                      Map.of())),
              factory,
              events::add);
      guard.setApprovals(approvals);
      org.springframework.test.util.ReflectionTestUtils.invokeMethod(
          execution, "setToolPermissionGuard", guard);
    }
    return new Fixture(execution, calls, events, approvals, root);
  }

  private com.fasterxml.jackson.databind.JsonNode latest(Prompt prompt) {
    try {
      var message =
          prompt.getInstructions().stream()
              .filter(ToolResponseMessage.class::isInstance)
              .map(ToolResponseMessage.class::cast)
              .reduce((a, b) -> b)
              .orElseThrow();
      return json.readTree(message.getResponses().getLast().responseData());
    } catch (Exception error) {
      throw new AssertionError(error);
    }
  }

  private long started(Fixture fixture, String name) {
    return fixture.events().stream()
        .filter(
            e ->
                e.payload() instanceof ToolStartedPayload p
                    && e.type() == AgentEventType.TOOL_STARTED
                    && p.toolName().equals(name))
        .count();
  }

  private long invalidEvents(Fixture fixture) {
    return fixture.events().stream()
        .filter(
            e ->
                e.payload() instanceof ToolFailedPayload p
                    && "invalid_tool_arguments".equals(p.error().code()))
        .count();
  }

  private static AssistantMessage.ToolCall call(String id, String name, String input) {
    return new AssistantMessage.ToolCall(id, "function", name, input);
  }

  private static ChatResponse response(AssistantMessage.ToolCall... calls) {
    return new ChatResponse(
        List.of(new Generation(AssistantMessage.builder().toolCalls(List.of(calls)).build())));
  }

  private static ChatResponse finished() {
    return new ChatResponse(List.of(new Generation(new AssistantMessage("完了"))));
  }

  private record Setup(
      Tools tools, TextChangeSetService service, JdbcTemplate db, String version) {}

  private record Fixture(
      ChatExecutionService service,
      AtomicInteger calls,
      List<AgentEvent> events,
      ToolApprovalRepository approvals,
      Path root) {
    ChatExecutionResult execute() {
      return service.execute(
          new AgentRunContext("run", "session", root, "bfb23e53-361d-4cb6-bc51-b93167bc5000"),
          "編集を確認",
          new UserInterventionQueue());
    }
  }
}
