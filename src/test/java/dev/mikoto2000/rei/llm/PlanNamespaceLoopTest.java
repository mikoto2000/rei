package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.policy.ToolPermissionGuard;
import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import dev.mikoto2000.rei.core.stagnation.ProgressEvaluator;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.core.stagnation.StagnationChatModel;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore;
import dev.mikoto2000.rei.modeloptions.ChatPlanAccounts;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** The real normal-chat loop and protected protocol storage, with SDK-intercepted HTTP only. */
class PlanNamespaceLoopTest {
  @TempDir Path directory;

  static Stream<Arguments> modes() {
    return java.util.Arrays.stream(AgentRunContext.Mode.values())
        .flatMap(mode -> Stream.of(Arguments.of(mode, false), Arguments.of(mode, true)));
  }

  @ParameterizedTest
  @MethodSource("modes")
  void normalLoopPreservesAuthorityAndReleasesSlotsBeforeTools(
      AgentRunContext.Mode mode, boolean partial) throws Exception {
    try (var fixture = new PlanResponsesRequestTest.Fixture()) {
      var pin = PlanNamespaceRequestTest.pin(fixture);
      var run =
          new AgentRunContext(
              "fixture-run",
              "fixture-conversation",
              directory,
              "fixture-project",
              AgentRunContext.RequestSource.SHELL,
              mode);
      var source = new org.sqlite.SQLiteDataSource();
      source.setUrl("jdbc:sqlite:" + directory.resolve("protocol.db"));
      var store = new ModelMessagePayloadStore(source);
      var exchange = store.newExchange(run);
      var budget = new OutputLimitRunBudget(2, 4);
      assertThat(budget.tryConsumeLlmCall()).isTrue();
      var events = new AgentEventFactory(Clock.systemUTC());
      var execution =
          new RunExecutionContext(
              run.runId(), budget, new ProgressEvaluator(directory), events, ignored -> {});
      execution.setRunContext(run);
      execution.setToolPermissionGuard(
          new ToolPermissionGuard(
              new ToolPermissionPolicy(new ToolPermissionProperties(true, null, null, null)),
              events,
              ignored -> {}));
      var active = new AtomicInteger();
      var admitted = new AtomicInteger();
      var released = new AtomicInteger();
      var executions = new AtomicInteger();
      var model = new AtomicReference<PlanRequestChatModel>();
      var requestOptions = new AtomicReference<OpenAiResponsesChatOptions>();
      var done = fixture.reply;
      var callback =
          new ToolCallback() {
            public ToolDefinition getToolDefinition() {
              return ToolDefinition.builder()
                  .name("searchTools")
                  .description("Local test callback for the existing tool loop")
                  .inputSchema(
                      "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}")
                  .build();
            }

            public String call(String input) {
              assertThat(active).as("parent request released before tool dispatch").hasValue(0);
              executions.incrementAndGet();
              fixture.reply = done.replace("resp_fixture", "resp_nested");
              assertThat(
                      model
                          .get()
                          .call(new Prompt("nested fixture", requestOptions.get()))
                          .getResult()
                          .getOutput()
                          .getText())
                  .isEqualTo("done");
              fixture.reply = done;
              return "[]";
            }
          };
      var context = new java.util.HashMap<>(fixture.options().getToolContext());
      context.put(dev.mikoto2000.rei.modeloptions.ModelOptions.RUN_CONNECTION_CONTEXT_KEY, pin);
      context.put(ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY, exchange);
      context.put(
          ModelMessagePayloadStore.ORIGIN_CONTEXT_KEY,
          new ModelMessagePayloadStore.Origin(exchange.ownerKey(), pin.fingerprint()));
      context.put(ResponsesReasoningReplay.OWNER_CONTEXT_KEY, exchange.ownerKey());
      context.put(RunExecutionContext.KEY, execution);
      var options =
          ((OpenAiResponsesChatOptions) RunNativeOptions.options(pin))
              .mutate()
              .toolContext(context)
              .toolCallbacks(
                  mode == AgentRunContext.Mode.CONVERSATION ? List.of() : List.of(callback))
              .build();
      execution.setModelOptions(options);
      var accepted = (OpenAiResponsesChatOptions) execution.modelOptions();
      requestOptions.set(accepted);
      model.set(
          new PlanRequestChatModel(
              ResponsesAdapter.createForRun(pin, options, fixture.client),
              () -> {
                assertThat(active.incrementAndGet()).as("one shared request slot").isEqualTo(1);
                admitted.incrementAndGet();
                return new ChatPlanAccounts.Request() {
                  private final AtomicBoolean closed = new AtomicBoolean();
                  private final CompletableFuture<ChatPlanAccounts.Invalidation> invalidation =
                      new CompletableFuture<>();

                  public java.util.concurrent.CompletionStage<ChatPlanAccounts.Invalidation>
                      invalidated() {
                    return invalidation.minimalCompletionStage();
                  }

                  public void close() {
                    if (closed.compareAndSet(false, true)) {
                      active.decrementAndGet();
                      released.incrementAndGet();
                    }
                  }
                };
              }));
      if (partial) {
        fixture.reply =
            PlanResponsesRequestTest.Fixture.event(
                    "response.output_item.added",
                    "\"output_index\":0,\"item\":{\"type\":\"function_call\",\"id\":\"fc_fixture\","
                        + "\"call_id\":\"call_fixture\",\"namespace\":\"rei\",\"name\":\"searchTools\","
                        + "\"arguments\":\"\",\"status\":\"in_progress\"}")
                + PlanResponsesRequestTest.Fixture.event(
                    "response.function_call_arguments.delta",
                    "\"item_id\":\"fc_fixture\",\"output_index\":0,\"delta\":\"{\"");
      } else if (mode != AgentRunContext.Mode.CONVERSATION) {
        fixture.reply =
            PlanResponsesRequestTest.Fixture.event(
                "response.completed",
                "\"response\":"
                    + new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(
                            Map.of(
                                "id",
                                "resp_tools",
                                "object",
                                "response",
                                "created_at",
                                0,
                                "status",
                                "completed",
                                "model",
                                "fixture-model",
                                "output",
                                List.of(
                                    Map.of(
                                        "type",
                                        "function_call",
                                        "id",
                                        "fc_fixture",
                                        "call_id",
                                        "call_fixture",
                                        "namespace",
                                        "rei",
                                        "name",
                                        "searchTools",
                                        "arguments",
                                        "{\"query\":\"fixture\"}",
                                        "status",
                                        "completed")),
                                "usage",
                                Map.of("input_tokens", 3, "output_tokens", 2, "total_tokens", 5))));
      }
      var loop = new StagnationChatModel(model.get());
      var prompt = new Prompt("fixture", accepted);
      if (partial) {
        assertThatThrownBy(() -> loop.stream(prompt).blockLast(Duration.ofSeconds(10)))
            .isInstanceOf(ResponsesTransportException.class);
        assertThat(executions).hasValue(0);
      } else {
        loop.stream(prompt).blockLast(Duration.ofSeconds(10));
        assertThat(executions).hasValue(mode == AgentRunContext.Mode.CONVERSATION ? 0 : 1);
        assertThat(execution.progressSnapshot().protocolHistory()).isNotNull();
      }
      int requests = partial || mode == AgentRunContext.Mode.CONVERSATION ? 1 : 3;
      assertThat(fixture.requests).hasSize(requests);
      assertThat(active).hasValue(0);
      assertThat(admitted).hasValue(requests);
      assertThat(released).hasValue(requests);
      if (mode == AgentRunContext.Mode.CONVERSATION)
        assertThat(
                fixture.requests.getFirst().path("tools").isMissingNode()
                    || fixture.requests.getFirst().path("tools").isEmpty())
            .isTrue();
    }
  }
}
