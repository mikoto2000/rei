package dev.mikoto2000.rei.core.policy;

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
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.stagnation.ProgressEvaluator;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.core.stagnation.StagnationChatModel;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.llm.LlmApiType;
import dev.mikoto2000.rei.llm.LlmAuthentication;
import dev.mikoto2000.rei.llm.LlmCapabilities;
import dev.mikoto2000.rei.llm.LlmEndpointCapabilities;
import dev.mikoto2000.rei.llm.LlmProvider;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import dev.mikoto2000.rei.llm.ReasoningReplayMode;
import dev.mikoto2000.rei.llm.ResolvedLlmEndpoint;
import dev.mikoto2000.rei.llm.RunLlmConnection;
import dev.mikoto2000.rei.modelmessage.ModelMessageCodec;
import dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore;
import dev.mikoto2000.rei.modelmessage.ModelTokenUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.part.OpaquePayload;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.ToolCallPart;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.responses.OpenAiResponsesMetadata;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.publisher.Flux;

/** Two real JVMs share only SQLite and fixture files; the model is an isolated local stub. */
@Tag("integration")
class InferenceProcessRestartTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(
      strings = {
        "plan:encrypted",
        "responses:encrypted",
        "responses:plaintext",
        "responses:none",
        "chat:none"
      })
  void anotherJvmRestoresTheExactApprovedInputAndOnlyTheReservedInference(String profile)
      throws Exception {
    worker("write", profile);
    worker("read", profile);
    assertThat(Files.readString(directory.resolve("effects.txt"))).isEqualTo("completed\n");
    assertThat(Files.readString(directory.resolve("result.txt"))).isEqualTo("1:2:10");
  }

  private void worker(String action, String profile) throws Exception {
    var output = directory.resolve(action + ".log");
    var arguments = directory.resolve(action + ".args");
    var values =
        List.of(
            "--enable-native-access=ALL-UNNAMED",
            "-cp",
            System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
            Worker.class.getName(),
            action,
            directory.toString(),
            profile);
    Files.write(
        arguments,
        values.stream()
            .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
            .toList());
    var builder =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "@" + arguments)
            .redirectErrorStream(true)
            .redirectOutput(output.toFile());
    builder.environment().put("REI_DATA_DIR", directory.resolve("data").toString());
    var child = builder.start();
    boolean completed = child.waitFor(40, TimeUnit.SECONDS);
    if (!completed) {
      child.destroyForcibly();
      child.waitFor(5, TimeUnit.SECONDS);
    }
    assertThat(completed).as("owned fixture JVM completed: %s", action).isTrue();
    assertThat(child.exitValue()).as("fixture JVM: %s", Files.readString(output)).isZero();
  }

  public static class Worker {
    public static void main(String[] arguments) throws Exception {
      Path directory = Path.of(arguments[1]);
      if (!directory.resolve("data").toString().equals(System.getenv("REI_DATA_DIR")))
        throw new IllegalStateException("Isolated fixture data directory required");
      var clock = Clock.systemUTC();
      var owner =
          new AgentRunContext(
              "fixture-run", "fixture-session", directory, "00000000-0000-0000-0000-000000000001");
      var pin = pin(arguments[2]);
      var source = new org.sqlite.SQLiteDataSource();
      source.setUrl("jdbc:sqlite:" + directory.resolve("inference.db"));
      var payloads = new ModelMessagePayloadStore(source);
      var registry = new RunRegistry(clock, source);
      var bus = new InMemoryAgentEventBus();
      var events = new AgentEventFactory(clock);
      var runs =
          new RunService(registry, bus, events, new CommandCancellationService(), id -> false);
      var requests = new ToolApprovalRepository(source, clock);
      var store = new ApprovalContinuationStore(source);
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
              source,
              clock);
      var context =
          new RunExecutionContext(
              owner.runId(),
              new OutputLimitRunBudget(3, 8, null, 1000),
              new ProgressEvaluator(directory),
              events,
              ignored -> {});
      context.setRunContext(owner);
      context.setUserRequest("original fixture input");
      context.setContinuation(service);
      var callback =
          new ToolCallback() {
            public ToolDefinition getToolDefinition() {
              return ToolDefinition.builder()
                  .name("readFile")
                  .description("Completed fixture operation")
                  .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                  .build();
            }

            public String call(String input) {
              throw new AssertionError(
                  "The previously successful tool must never be executed again");
            }
          };
      context.setToolPermissionGuard(
          new ToolPermissionGuard(
              new ToolPermissionPolicy(
                  new ToolPermissionProperties(true, Set.of(ActionCapability.READ), null, null)),
              events,
              ignored -> {}));
      var exchange = payloads.newExchange(owner);
      var origin = new ModelMessagePayloadStore.Origin(exchange.ownerKey(), pin.fingerprint());
      var values =
          Map.<String, Object>of(
              RunExecutionContext.KEY,
              context,
              dev.mikoto2000.rei.modeloptions.ModelOptions.RUN_CONNECTION_CONTEXT_KEY,
              pin,
              ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY,
              exchange,
              ModelMessagePayloadStore.ORIGIN_CONTEXT_KEY,
              origin);
      ToolCallingChatOptions options =
          pin.endpoint().apiType() == LlmApiType.RESPONSES
              ? org.springframework.ai.openai.responses.OpenAiResponsesChatOptions.builder()
                  .model("fixture-model")
                  .maxOutputTokens(pin.endpoint().maxOutputTokens())
                  .toolCallbacks(List.of(callback))
                  .toolContext(values)
                  .build()
              : org.springframework.ai.openai.OpenAiChatOptions.builder()
                  .model("fixture-model")
                  .maxTokens(64)
                  .toolCallbacks(List.of(callback))
                  .toolContext(values)
                  .build();
      if (arguments[0].equals("write")) {
        registry.register(owner);
        registry.transition(owner.runId(), RunStatus.RUNNING, null);
        context.consumeNextLlmCall();
        context.recordTotalTokens(5);
        context.consumeNextLlmCall();
        context.setModelOptions(options);
        var parts =
            new java.util.ArrayList<org.springframework.ai.chat.messages.part.MessagePart>();
        if (pin.endpoint().capabilities().reasoningReplay() != ReasoningReplayMode.NONE)
          parts.add(
              new ReasoningPart(
                  pin.endpoint().capabilities().reasoningReplay() == ReasoningReplayMode.PLAINTEXT
                      ? "PLAINTEXT FIXTURE REASONING"
                      : null,
                  null,
                  pin.endpoint().capabilities().reasoningReplay() == ReasoningReplayMode.ENCRYPTED
                      ? new OpaquePayload(
                          OpenAiResponsesMetadata.PROVIDER,
                          OpenAiResponsesMetadata.ENCRYPTED_CONTENT_KIND,
                          "OPAQUE FIXTURE BYTES")
                      : null,
                  Map.of(
                      OpenAiResponsesMetadata.ITEM_ID_ATTRIBUTE,
                      "rs_fixture",
                      OpenAiResponsesMetadata.ITEM_STATUS_ATTRIBUTE,
                      "completed")));
        parts.add(new TextPart("Completed operation", null, Map.of()));
        var attributes = new java.util.HashMap<String, String>();
        attributes.put(OpenAiResponsesMetadata.ITEM_ID_ATTRIBUTE, "fc_fixture");
        attributes.put(OpenAiResponsesMetadata.ITEM_STATUS_ATTRIBUTE, "completed");
        if (pin.provider() == LlmProvider.CHATGPT_PLAN)
          attributes.put("rei.responses.tool-namespace", "rei");
        parts.add(
            new ToolCallPart(
                new AssistantMessage.ToolCall("already_done", "function", "readFile", "{}"),
                null,
                attributes));
        var assistant =
            AssistantMessage.builder()
                .parts(parts)
                .properties(
                    Map.of(
                        "rei.llm.replay-origin-owner",
                        origin.owner(),
                        "rei.llm.replay-origin-endpoint",
                        origin.endpoint(),
                        ModelTokenUsage.METADATA_KEY,
                        new ModelTokenUsage(3L, 2L, 5L, null, null)))
                .build();
        var messages =
            List.<Message>of(
                SystemMessage.builder()
                    .text("original fixture context")
                    .metadata(Map.of("rei.workContext", true))
                    .build(),
                new UserMessage("original fixture input"),
                assistant,
                ToolResponseMessage.builder()
                    .responses(
                        List.of(
                            new ToolResponseMessage.ToolResponse(
                                "already_done", "readFile", "SAVED_SUCCESSFUL_EFFECT")))
                    .build());
        Files.writeString(directory.resolve("effects.txt"), "completed\n");
        var unsent =
            new ChatModel() {
              public ChatResponse call(Prompt prompt) {
                throw new AssertionError("Inference before human approval");
              }

              public Flux<ChatResponse> stream(Prompt prompt) {
                throw new AssertionError("Inference before human approval");
              }
            };
        assertThatThrownBy(() ->
                new StagnationChatModel(unsent)
                    .stream(new Prompt(messages, context.modelOptions()))
                        .blockLast(Duration.ofSeconds(10)))
            .isInstanceOf(dev.mikoto2000.rei.core.chat.RunWaitingException.class);
        var approval = requests.list(owner.projectId()).getFirst();
        var saved = store.saved(store.find(approval.id()).orElseThrow());
        Files.write(directory.resolve("input-hashes.txt"), hashes(saved.messages()));
        Files.writeString(directory.resolve("approval-id.txt"), approval.id());
        assertThat(saved.inferencePending()).isTrue();
        assertThat(saved.budget().llmCalls()).isEqualTo(2);
        return;
      }
      String id = Files.readString(directory.resolve("approval-id.txt"));
      assertThat(requests.get(owner.projectId(), id).status()).isEqualTo("PENDING");
      service.decide(owner.projectId(), id, true, true);
      assertThat(registry.claimWaiting(owner)).isTrue();
      assertThat(store.claim(id)).isTrue();
      assertThat(registry.transition(owner.runId(), RunStatus.RUNNING, null)).isTrue();
      var saved = service.restore(owner);
      assertThat(saved.connection()).isEqualTo(pin);
      assertThat(hashes(saved.messages()))
          .isEqualTo(Files.readAllLines(directory.resolve("input-hashes.txt")));
      context.resume(saved);
      context.beginContextRestoration();
      context.setModelOptions(options);
      var calls = new AtomicInteger();
      var sent = new java.util.concurrent.atomic.AtomicReference<Prompt>();
      var model =
          new ChatModel() {
            public ChatResponse call(Prompt prompt) {
              throw new AssertionError("Streaming loop only");
            }

            public Flux<ChatResponse> stream(Prompt prompt) {
              calls.incrementAndGet();
              sent.set(prompt);
              var assistant =
                  AssistantMessage.builder()
                      .content("done")
                      .properties(
                          Map.of(
                              "rei.llm.replay-origin-owner",
                              origin.owner(),
                              "rei.llm.replay-origin-endpoint",
                              origin.endpoint(),
                              ModelTokenUsage.METADATA_KEY,
                              new ModelTokenUsage(3L, 2L, 5L, null, null)))
                      .build();
              return Flux.just(new ChatResponse(List.of(new Generation(assistant))));
            }
          };
      new StagnationChatModel(model)
          .stream(new Prompt("original fixture input", context.modelOptions()))
              .blockLast(Duration.ofSeconds(10));
      assertThat(hashes(sent.get().getInstructions()))
          .isEqualTo(Files.readAllLines(directory.resolve("input-hashes.txt")));
      assertThat(requests.get(owner.projectId(), id).status()).isEqualTo("CONSUMED");
      assertThatThrownBy(
              () -> new InferenceExportGuard(requests, clock).authorize(owner, pin, sent.get()))
          .isInstanceOf(SecurityException.class);
      Files.writeString(
          directory.resolve("result.txt"),
          calls.get()
              + ":"
              + context.budgetSnapshot().llmCalls()
              + ":"
              + context.budgetSnapshot().totalTokens());
      assertThat(Files.readString(directory.resolve("effects.txt"))).isEqualTo("completed\n");
      context.completeRun();
    }

    private static RunLlmConnection pin(String profile) {
      boolean plan = profile.startsWith("plan:");
      var api = profile.startsWith("chat:") ? LlmApiType.CHAT_COMPLETIONS : LlmApiType.RESPONSES;
      var replay =
          ReasoningReplayMode.valueOf(profile.split(":")[1].toUpperCase(java.util.Locale.ROOT));
      var supported = LlmEndpointCapabilities.Support.SUPPORTED;
      var unsupported = LlmEndpointCapabilities.Support.UNSUPPORTED;
      return new RunLlmConnection(
          "fixture",
          true,
          plan ? LlmProvider.CHATGPT_PLAN : LlmProvider.OPENAI_COMPATIBLE,
          plan ? LlmAuthentication.CHATGPT_OAUTH : LlmAuthentication.NONE,
          plan ? "personal" : null,
          plan ? "fixture-registration" : null,
          new ResolvedLlmEndpoint(
              "fixture",
              1,
              api,
              plan ? "https://api.openai.com/v1" : "http://127.0.0.1:1/v1",
              "chat-endpoint:fixture",
              "fixture-model",
              new LlmCapabilities(replay, 8, Set.of()),
              plan ? null : 64,
              null,
              Duration.ofSeconds(5)),
          Map.of(
              LlmEndpointCapabilities.Capability.CHAT_COMPLETIONS,
              api == LlmApiType.CHAT_COMPLETIONS ? supported : unsupported,
              LlmEndpointCapabilities.Capability.RESPONSES,
              api == LlmApiType.RESPONSES ? supported : unsupported,
              LlmEndpointCapabilities.Capability.STREAMING,
              supported,
              LlmEndpointCapabilities.Capability.FUNCTION_CALLING,
              supported,
              LlmEndpointCapabilities.Capability.TOOL_NAMESPACE,
              plan ? supported : unsupported,
              LlmEndpointCapabilities.Capability.REASONING_REPLAY,
              replay == ReasoningReplayMode.NONE ? unsupported : supported,
              LlmEndpointCapabilities.Capability.MAX_OUTPUT_TOKENS,
              plan ? unsupported : supported),
          plan
              ? Map.of()
              : Map.of(api == LlmApiType.RESPONSES ? "max_output_tokens" : "max_tokens", 64));
    }

    private static List<String> hashes(List<Message> messages) {
      return messages.stream()
          .map(message -> {
            try {
              return HexFormat.of()
                  .formatHex(
                      MessageDigest.getInstance("SHA-256")
                          .digest(new ModelMessageCodec().encode(message)));
            } catch (java.security.NoSuchAlgorithmException failure) {
              throw new AssertionError(failure);
            }
          })
          .toList();
    }

    private static <T> ObjectProvider<T> provider(Class<T> type, T value) {
      var beans = new StaticListableBeanFactory();
      if (value != null) beans.addBean("value", value);
      return beans.getBeanProvider(type);
    }
  }
}
