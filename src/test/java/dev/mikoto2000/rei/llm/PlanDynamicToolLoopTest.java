package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.policy.ActionCapability;
import dev.mikoto2000.rei.core.policy.ToolPermissionGuard;
import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import dev.mikoto2000.rei.core.stagnation.ProgressEvaluator;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.core.stagnation.StagnationChatModel;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.llm.discovery.ToolDiscovery;
import dev.mikoto2000.rei.llm.discovery.ToolDiscoveryProperties;
import dev.mikoto2000.rei.llm.discovery.ToolDiscoveryStore;
import dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/** Real discovery, permissions, protected input and SDK transport; business tools are synthetic. */
class PlanDynamicToolLoopTest {
  @TempDir Path directory;
  private static final String SPECIALIST = "googleCalendarListEventsForDate";

  static Stream<Arguments> cases() {
    return java.util.Arrays.stream(AgentRunContext.Mode.values())
        .flatMap(mode ->
            mode == AgentRunContext.Mode.CONVERSATION
                ? Stream.of(Arguments.of(mode, false, true), Arguments.of(mode, false, false))
                : Stream.of(
                    Arguments.of(mode, false, true),
                    Arguments.of(mode, true, true),
                    Arguments.of(mode, false, false)));
  }

  @ParameterizedTest
  @MethodSource("cases")
  void searchesLocallyAndPublishesOnlyForTheNextRequest(
      AgentRunContext.Mode mode, boolean unpublished, boolean plan) throws Exception {
    try (var fixture = new Fixture(plan)) {
      var pin = fixture.pin;
      var owner =
          new AgentRunContext(
              "run", "session", directory, "project", AgentRunContext.RequestSource.SHELL, mode);
      var data = new org.sqlite.SQLiteDataSource();
      data.setUrl("jdbc:sqlite:" + directory.resolve("protocol.db"));
      var payloads = new ModelMessagePayloadStore(data);
      var exchange = payloads.newExchange(owner);
      var budget = new OutputLimitRunBudget(3, 6);
      assertThat(budget.tryConsumeLlmCall()).isTrue();
      var events = new AgentEventFactory(Clock.systemUTC());
      var execution =
          new RunExecutionContext(
              "run", budget, new ProgressEvaluator(directory), events, ignored -> {});
      execution.setRunContext(owner);
      execution.setContinuation(
          dev.mikoto2000.rei.testfixtures.FixtureInferencePermissions.automatic(data));
      execution.setToolPermissionGuard(
          new ToolPermissionGuard(
              new ToolPermissionPolicy(
                  new ToolPermissionProperties(
                      true,
                      Set.of(ActionCapability.READ, ActionCapability.NETWORK_READ),
                      null,
                      null)),
              events,
              ignored -> {}));
      var executions = new AtomicInteger();
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
                          return "SYNTHETIC_CALENDAR_RESULT";
                        }
                      })
              .toList();
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
              .toolCallbacks(mode == AgentRunContext.Mode.CONVERSATION ? List.of() : catalog)
              .build();
      execution.setModelOptions(options);
      var properties = new ToolDiscoveryProperties();
      properties.setEnabled(true);
      var discovery =
          new ToolDiscovery(
              properties,
              new DefaultListableBeanFactory().getBeanProvider(ToolDiscoveryStore.class));
      var done = fixture.reply;
      fixture.replies =
          turn -> {
            if (mode == AgentRunContext.Mode.CONVERSATION || turn > 2) return done;
            return completedCall(
                plan,
                turn,
                turn == 1 && !unpublished ? "searchTools" : SPECIALIST,
                turn == 1 && !unpublished
                    ? "{\"query\":\"" + SPECIALIST + "\",\"maxResults\":1}"
                    : "{}");
          };
      var loop =
          new StagnationChatModel(ResponsesAdapter.createForRun(pin, options, fixture.client))
              .withToolDiscovery(discovery);
      var prompt = new Prompt("synthetic calendar request", execution.modelOptions());
      if (unpublished) {
        assertThatThrownBy(() -> loop.stream(prompt).blockLast(Duration.ofSeconds(10)))
            .isInstanceOf(ResponsesTransportException.class)
            .hasMessageContaining("INVALID_RESPONSE");
        assertThat(executions).hasValue(0);
        assertThat(fixture.requests).hasSize(1);
      } else if (mode == AgentRunContext.Mode.CONVERSATION) {
        loop.stream(prompt).blockLast(Duration.ofSeconds(10));
        assertThat(executions).hasValue(0);
        assertThat(fixture.requests).hasSize(1);
        assertThat(names(fixture.requests.getFirst())).isEmpty();
      } else {
        loop.stream(prompt).blockLast(Duration.ofSeconds(10));
        assertThat(executions).hasValue(1);
        assertThat(fixture.requests).hasSize(3);
        assertThat(names(fixture.requests.get(0)))
            .contains("searchTools")
            .doesNotContain(SPECIALIST);
        assertThat(names(fixture.requests.get(1))).contains("searchTools", SPECIALIST);
        assertThat(names(fixture.requests.get(0))).doesNotContain(SPECIALIST);
        assertThat(fixture.requests.get(1).path("input").toString())
            .contains("searchTools", "function_call_output");
        assertThat(fixture.requests.get(2).path("input").toString())
            .contains(SPECIALIST, "SYNTHETIC_CALENDAR_RESULT");
        if (plan) {
          assertThat(fixture.requests.get(1).path("input").toString())
              .contains("\"namespace\":\"rei\"");
          assertThat(fixture.requests.get(2).path("input").toString())
              .contains("\"namespace\":\"rei\"");
        } else {
          assertThat(fixture.requests.get(1).toString()).doesNotContain("\"namespace\"");
          assertThat(fixture.requests.get(2).toString()).doesNotContain("\"namespace\"");
        }
        assertThat(execution.progressSnapshot().protocolHistory()).isNotNull();
      }
      for (var request : fixture.requests) {
        for (var tool : request.path("tools")) {
          assertThat(tool.path("type").asText()).isEqualTo(plan ? "namespace" : "function");
          if (plan) assertThat(tool.path("name").asText()).isEqualTo("rei");
        }
      }
    }
  }

  /** Production clients intercepted before DNS; the no-auth route must never read credentials. */
  private static final class Fixture implements AutoCloseable {
    final RunLlmConnection pin;
    final com.openai.client.OpenAIClient client;
    final okhttp3.OkHttpClient transport =
        new okhttp3.OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build();
    final List<JsonNode> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
    java.util.function.IntFunction<String> replies;
    final String reply =
        PlanResponsesRequestTest.Fixture.event(
            "response.completed",
            "\"response\":"
                + """
                {"id":"resp_done","object":"response","created_at":0,"status":"completed",
                 "model":"fixture-model","output":[{"type":"message","id":"msg_done",
                 "role":"assistant","status":"completed","content":[{"type":"output_text",
                 "text":"done","annotations":[]}]}],"usage":{"input_tokens":3,"output_tokens":2,"total_tokens":5}}
                """);

    Fixture(boolean plan) {
      String base = plan ? "https://api.openai.com/v1" : "http://127.0.0.1:1/v1";
      var support = LlmEndpointCapabilities.Support.SUPPORTED;
      var unsupported = LlmEndpointCapabilities.Support.UNSUPPORTED;
      pin =
          new RunLlmConnection(
              "fixture",
              true,
              plan ? LlmProvider.CHATGPT_PLAN : LlmProvider.OPENAI_COMPATIBLE,
              plan ? LlmAuthentication.CHATGPT_OAUTH : LlmAuthentication.NONE,
              plan ? "personal" : null,
              plan ? "fixture-registration" : null,
              new ResolvedLlmEndpoint(
                  "fixture",
                  1,
                  LlmApiType.RESPONSES,
                  base,
                  "chat-endpoint:fixture",
                  "fixture-model",
                  new LlmCapabilities(
                      plan ? ReasoningReplayMode.ENCRYPTED : ReasoningReplayMode.NONE,
                      36,
                      Set.of()),
                  plan ? null : 64,
                  null,
                  Duration.ofSeconds(5)),
              Map.of(
                  LlmEndpointCapabilities.Capability.RESPONSES,
                  support,
                  LlmEndpointCapabilities.Capability.STREAMING,
                  support,
                  LlmEndpointCapabilities.Capability.FUNCTION_CALLING,
                  support,
                  LlmEndpointCapabilities.Capability.TOOL_NAMESPACE,
                  plan ? support : unsupported,
                  LlmEndpointCapabilities.Capability.REASONING_REPLAY,
                  plan ? support : unsupported,
                  LlmEndpointCapabilities.Capability.MAX_OUTPUT_TOKENS,
                  plan ? unsupported : support),
              plan ? Map.of() : Map.of("max_output_tokens", 64));
      client =
          PinnedOpenAiClients.open(
              pin,
              () -> {
                if (!plan) throw new AssertionError("No-auth route accessed credentials");
                return "fixture-plan-access";
              },
              transport,
              List.of(chain -> {
                assertThat(chain.request().url().toString()).isEqualTo(base + "/responses");
                if (plan)
                  assertThat(chain.request().header("Authorization"))
                      .isEqualTo("Bearer fixture-plan-access");
                else
                  assertThat(chain.request().headers().names())
                      .noneMatch(name ->
                          Set.of("authorization", "api-key", "x-api-key", "cookie")
                              .contains(name.toLowerCase(java.util.Locale.ROOT)));
                var buffer = new okio.Buffer();
                chain.request().body().writeTo(buffer);
                requests.add(
                    new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(buffer.readByteArray()));
                return new okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("fixture")
                    .body(
                        okhttp3.ResponseBody.create(
                            replies.apply(requests.size()),
                            okhttp3.MediaType.get("text/event-stream")))
                    .build();
              }),
              () -> {});
    }

    OpenAiResponsesChatOptions options() {
      return ((OpenAiResponsesChatOptions) RunNativeOptions.options(pin))
          .mutate()
          .toolContext(Map.of(ResponsesReasoningReplay.OWNER_CONTEXT_KEY, "fixture-owner"))
          .build();
    }

    public void close() {
      client.close();
      transport.connectionPool().evictAll();
      transport.dispatcher().executorService().shutdown();
    }
  }

  private static List<String> names(JsonNode request) {
    var result = new java.util.ArrayList<String>();
    request
        .path("tools")
        .forEach(tool -> {
          if (tool.path("type").asText().equals("function")) result.add(tool.path("name").asText());
          else tool.path("tools").forEach(function -> result.add(function.path("name").asText()));
        });
    return result;
  }

  private static String completedCall(boolean plan, int turn, String name, String arguments) {
    try {
      var call = new java.util.LinkedHashMap<String, Object>();
      call.put("type", "function_call");
      call.put("id", "fc_" + turn);
      call.put("call_id", "call_" + turn);
      call.put("name", name);
      call.put("arguments", arguments);
      call.put("status", "completed");
      if (plan) call.put("namespace", "rei");
      return PlanResponsesRequestTest.Fixture.event(
          "response.completed",
          "\"response\":"
              + new com.fasterxml.jackson.databind.ObjectMapper()
                  .writeValueAsString(
                      Map.of(
                          "id",
                          "resp_" + turn,
                          "object",
                          "response",
                          "created_at",
                          0,
                          "status",
                          "completed",
                          "model",
                          "fixture-model",
                          "output",
                          List.of(call),
                          "usage",
                          Map.of("input_tokens", 3, "output_tokens", 2, "total_tokens", 5))));
    } catch (java.io.IOException failure) {
      throw new AssertionError(failure);
    }
  }
}
