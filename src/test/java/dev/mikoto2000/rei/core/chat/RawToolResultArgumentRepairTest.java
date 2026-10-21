package dev.mikoto2000.rei.core.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.contextbudget.DeliveryToolRegistration;
import dev.mikoto2000.rei.core.contextbudget.RawToolResultStore;
import dev.mikoto2000.rei.core.contextbudget.RawToolResultTools;
import dev.mikoto2000.rei.core.contextbudget.ResultDelivery;
import dev.mikoto2000.rei.core.policy.ActionCapability;
import dev.mikoto2000.rei.core.policy.ToolApprovalRepository;
import dev.mikoto2000.rei.core.policy.ToolPermissionGuard;
import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException;
import dev.mikoto2000.rei.core.stagnation.ProgressEvaluator;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.core.stagnation.StagnationChatModel;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.ToolFailedPayload;
import dev.mikoto2000.rei.llm.FixedLlmChatClientProvider;
import dev.mikoto2000.rei.llm.FixedLlmModelProvider;
import dev.mikoto2000.rei.llm.LlmProperties;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import dev.mikoto2000.rei.llm.RunAwareToolCallingAdvisor;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 加工前のツールの結果引数修復の結果と状態の契約を検証するテスト。 */
class RawToolResultArgumentRepairTest {
  /** 検証に使う参照先。 */
  private static final String REF = "73cb1803-2daf-3eaf-aaab-45f2b64a66c9";

  /** 検証に使う不正な。 */
  private static final String INVALID =
      "{\"rawResultRef\":\"" + REF + "\",\"nextOffset\":\"3433\",\"limit\":16000}";

  /** 検証に使う有効な。 */
  private static final String VALID =
      "{\"rawResultRef\":\"" + REF + "\",\"offset\":3433,\"limit\":16000}";

  /** 検証に使う所有者。 */
  private static final AgentRunContext OWNER =
      new AgentRunContext("run", "session", Path.of("."), "project");

  /** 案内を返すことを確認する。 */
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void incorrectNextOffsetReturnsGuidanceAndTheModelCorrectsItBeforeReading(boolean guarded) {
    var store = mock(RawToolResultStore.class);
    when(store.read("session", REF))
        .thenReturn(
            new RawToolResultStore.Result(
                "runCommand", "checkstyle", "x".repeat(3433) + "checkstyle finding details"));
    var calls = new AtomicInteger();
    var fixture =
        fixture(
            store,
            guarded,
            false,
            calls,
            (prompt, invocation) -> {
              if (invocation == 1) {
                return response(toolCall("invalid-read", "readRawToolResult", INVALID));
              }
              var message = latestToolMessage(prompt);
              if (invocation == 2) {
                // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
                verifyNoInteractions(store);
                var correction = correction(message);
                assertThat(correction.path("error").asText()).isEqualTo("INVALID_TOOL_ARGUMENTS");
                assertThat(correction.path("executed").asBoolean()).isFalse();
                assertThat(correction.path("guidance").asText()).contains("offset", "nextOffset");
                assertThat(correction.path("correctionsRemaining").asInt()).isEqualTo(1);
                assertDelivery(message, "invalid-read", ResultDelivery.Kind.GENERAL);
                return response(toolCall("corrected-read", "readRawToolResult", VALID));
              }
              assertThat(invocation).isEqualTo(3);
              assertThat(message.getResponses().getLast().responseData())
                  .contains("offset: 3433", "checkstyle finding details", "hasMore: false");
              assertDelivery(message, "corrected-read", ResultDelivery.Kind.RAW_RESULT_PAGE);
              return new ChatResponse(List.of(new Generation(new AssistantMessage("指摘を確認しました。"))));
            });

    var result = fixture.execute();

    assertThat(result.success()).isTrue();
    assertThat(result.text()).contains("指摘を確認しました");
    assertThat(calls).hasValue(3);
    verify(store, times(1)).read("session", REF);
    verifyNoInteractions(fixture.approvals());
    assertThat(invalidArgumentEvents(fixture.events())).hasSize(1);
    assertThat(
            fixture.events().stream()
                .filter(event -> event.type() == AgentEventType.AGENT_RUN_FAILED))
        .isEmpty();
  }

  /** 会話の実行で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void repeatedIncorrectCursorsStopAtTheExistingCorrectionLimit(boolean guarded) {
    var store = mock(RawToolResultStore.class);
    var calls = new AtomicInteger();
    var remaining = new ArrayList<Integer>();
    var fixture =
        fixture(
            store,
            guarded,
            false,
            calls,
            (prompt, invocation) -> {
              assertThat(invocation).isLessThanOrEqualTo(3);
              if (invocation > 1) {
                var correction = correction(latestToolMessage(prompt));
                assertThat(correction.path("error").asText()).isEqualTo("INVALID_TOOL_ARGUMENTS");
                assertThat(correction.path("maxCorrections").asInt()).isEqualTo(2);
                remaining.add(correction.path("correctionsRemaining").asInt());
              }
              return response(toolCall("invalid-read-" + invocation, "readRawToolResult", INVALID));
            });

    var result = fixture.execute();

    assertThat(result.status()).isEqualTo(ChatExecutionResult.Status.FAILED);
    assertThat(result.stopCode()).isEqualTo("tool_argument_repair_exhausted");
    assertThat(result.errorMessage())
        .contains("2回", "rei.llm.output-limit.max-tool-argument-corrections-per-run");
    assertThat(calls).hasValue(3);
    assertThat(remaining).containsExactly(1, 0);
    assertThat(invalidArgumentEvents(fixture.events())).hasSize(2);
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(store, fixture.approvals());
  }

  /** 失敗情報を読み取ることを確認する。 */
  @ParameterizedTest
  @MethodSource("runtimeFailures")
  void readFailureAfterPreflightIsNotAutomaticallyRetried(
      boolean guarded, RuntimeException failure, String expectedStopCode) {
    var store = mock(RawToolResultStore.class);
    when(store.read("session", REF)).thenThrow(failure);
    var calls = new AtomicInteger();
    var fixture =
        fixture(
            store,
            guarded,
            false,
            calls,
            (prompt, invocation) -> {
              assertThat(invocation).isEqualTo(1);
              return response(toolCall("valid-read", "readRawToolResult", VALID));
            });

    var result = fixture.execute();

    assertThat(result.status()).isEqualTo(ChatExecutionResult.Status.FAILED);
    assertThat(result.stopCode()).isEqualTo(expectedStopCode);
    assertThat(calls).hasValue(1);
    assertThat(invalidArgumentEvents(fixture.events())).isEmpty();
    verify(store, times(1)).read("session", REF);
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(fixture.approvals());
  }

  /** 会話の実行の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void permissionDenialIsNotAutomaticallyRetriedAsAnArgumentError() {
    var store = mock(RawToolResultStore.class);
    var calls = new AtomicInteger();
    var fixture =
        fixture(
            store,
            true,
            true,
            calls,
            (prompt, invocation) -> {
              assertThat(invocation).isEqualTo(1);
              return response(toolCall("denied-read", "readRawToolResult", VALID));
            });

    var result = fixture.execute();

    assertThat(result.status()).isEqualTo(ChatExecutionResult.Status.FAILED);
    assertThat(calls).hasValue(1);
    assertThat(invalidArgumentEvents(fixture.events())).isEmpty();
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(store, fixture.approvals());
  }

  /** 会話の実行で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void successfulSiblingIsPreservedAndNeverReexecutedForTheCorrection(boolean guarded) {
    var context = context(new OutputLimitRunBudget(2, 20, null, 0, 2), guarded);
    var reads = new AtomicInteger();
    var siblings = new AtomicInteger();
    var raw = callback("readRawToolResult", input -> "page " + reads.incrementAndGet());
    var sibling = callback("readMultiFile", input -> "sibling " + siblings.incrementAndGet());
    var prompt = prompt(context, raw, sibling);
    var loop = new ToolLoopSupport();

    var result =
        loop.executeResumable(
            prompt,
            response(
                toolCall("successful", "readMultiFile", "{}"),
                toolCall("invalid", "readRawToolResult", INVALID)),
            context);

    var feedback = (ToolResponseMessage) result.conversationHistory().getLast();
    assertThat(feedback.getResponses()).hasSize(2);
    assertThat(feedback.getResponses().getFirst().responseData()).isEqualTo("sibling 1");
    assertThat(feedback.getResponses().getLast().responseData()).contains("INVALID_TOOL_ARGUMENTS");
    assertThat(reads).hasValue(0);
    assertThat(siblings).hasValue(1);
    loop.executeResumable(
        prompt, response(toolCall("corrected", "readRawToolResult", VALID)), context);
    assertThat(reads).hasValue(1);
    assertThat(siblings).hasValue(1);
  }

  /** 会話の実行で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void savedPendingToolIsRevalidatedBeforeReplay(boolean guarded) {
    var context = context(new OutputLimitRunBudget(2, 20, null, 0, 2), guarded);
    var reads = new AtomicInteger();
    var raw = callback("readRawToolResult", input -> "page " + reads.incrementAndGet());
    var pending =
        new RunContinuation.PendingTools(
            "", List.of(toolCall("saved-read", "readRawToolResult", INVALID)), List.of());

    var result = new ToolLoopSupport().executePending(prompt(context, raw), pending, context);

    var feedback = (ToolResponseMessage) result.conversationHistory().getLast();
    assertThat(feedback.getResponses().getLast().responseData()).contains("INVALID_TOOL_ARGUMENTS");
    assertThat(context.budgetSnapshot().argumentCorrections()).isEqualTo(1);
    assertThat(reads).hasValue(0);
  }

  /** 会話の実行で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void correctionLimitRemainsConsumedAfterBudgetRestoration() {
    var budget = new OutputLimitRunBudget(2, 20, null, 0, 2);
    var context = context(budget, false);
    var reads = new AtomicInteger();
    var raw = callback("readRawToolResult", input -> "page " + reads.incrementAndGet());
    var loop = new ToolLoopSupport();
    for (int i = 0; i < 2; i++) {
      loop.executeResumable(
          prompt(context, raw),
          response(toolCall("invalid-" + i, "readRawToolResult", INVALID)),
          context);
    }
    var restored = new OutputLimitRunBudget(2, 20, null, 0, 2);
    restored.restore(budget.snapshot());
    var resumed = context(restored, false);

    assertThatThrownBy(
            () ->
                loop.executeResumable(
                    prompt(resumed, raw),
                    response(toolCall("invalid-after-resume", "readRawToolResult", INVALID)),
                    resumed))
        .isInstanceOfSatisfying(
            ExecutionStoppedException.class,
            stopped ->
                assertThat(stopped.reason())
                    .isEqualTo(ExecutionStoppedException.Reason.TOOL_ARGUMENT_REPAIR_EXHAUSTED));
    assertThat(reads).hasValue(0);
  }

  /** {@code runtimeFailures} の条件を再現するための補助情報を返す。 */
  private static Stream<Arguments> runtimeFailures() {
    return Stream.of(
        Arguments.of(true, new IllegalStateException("RAW_RESULT_NOT_FOUND"), "invalid_state"),
        Arguments.of(false, new IllegalStateException("RAW_RESULT_NOT_FOUND"), "invalid_state"),
        Arguments.of(true, new IllegalArgumentException("INVALID_CURSOR"), "invalid_arguments"),
        Arguments.of(false, new IllegalArgumentException("INVALID_CURSOR"), "invalid_arguments"));
  }

  /** {@code fixture} の条件を再現するための補助情報を返す。 */
  private static Fixture fixture(
      RawToolResultStore store,
      boolean guarded,
      boolean denyRead,
      AtomicInteger calls,
      BiFunction<Prompt, Integer, ChatResponse> responses) {
    ChatModel model =
        new ChatModel() {
          /** 検証用に、会話の実行で参照する選択可能な設定を返す。 */
          @Override
          public ToolCallingChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
          }

          /** 検証用に、会話の実行の要求を実行し、呼び出し元へ結果を返す。 */
          @Override
          public ChatResponse call(Prompt prompt) {
            throw new AssertionError("Only the streaming model path is expected");
          }

          /** {@code stream} の条件を再現するための補助情報を返す。 */
          @Override
          public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(responses.apply(prompt, calls.incrementAndGet()));
          }
        };
    var callback =
        DeliveryToolRegistration.provider(new RawToolResultTools(store)).getToolCallbacks()[0];
    var client =
        ChatClient.builder(new StagnationChatModel(model))
            .defaultAdvisors(new RunAwareToolCallingAdvisor())
            .defaultToolCallbacks(callback)
            .build();
    var properties = new LlmProperties();
    properties.getOutputLimit().setMaxToolArgumentCorrectionsPerRun(2);
    var holder = mock(ModelHolderService.class);
    when(holder.get()).thenReturn("test");
    var events = new ArrayList<AgentEvent>();
    var clock = Clock.systemUTC();
    var factory = new AgentEventFactory(clock);
    var service =
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
                      denyRead ? Set.of() : Set.of(ActionCapability.READ),
                      denyRead ? Set.of(ActionCapability.READ) : Set.of(),
                      Map.of())),
              factory,
              events::add);
      guard.setApprovals(approvals);
      service.setToolPermissionGuard(guard);
    }
    return new Fixture(service, approvals, events);
  }

  /** 検証で使用する文脈を、指定された条件に合わせて用意する。 */
  private static RunExecutionContext context(OutputLimitRunBudget budget, boolean guarded) {
    var factory = new AgentEventFactory(Clock.systemUTC());
    var context =
        new RunExecutionContext(
            "run", budget, new ProgressEvaluator(Path.of("."), null), factory, event -> {});
    context.setRunContext(OWNER);
    if (guarded) {
      context.setToolPermissionGuard(
          new ToolPermissionGuard(
              new ToolPermissionPolicy(
                  new ToolPermissionProperties(
                      true, Set.of(ActionCapability.READ), Set.of(), Map.of())),
              factory,
              event -> {}));
    }
    return context;
  }

  /** {@code callback} の条件を再現するための補助情報を返す。 */
  private static ToolCallback callback(String name, Function<String, String> effect) {
    return new ToolCallback() {
      /** 検証用に、会話の実行で参照するツール定義を返す。 */
      @Override
      public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
      }

      /** 検証用に、会話の実行の要求を実行し、呼び出し元へ結果を返す。 */
      @Override
      public String call(String input) {
        return effect.apply(input);
      }
    };
  }

  /** 検証で使用するプロンプトを、指定された条件に合わせて用意する。 */
  private static Prompt prompt(RunExecutionContext context, ToolCallback... callbacks) {
    return new Prompt(
        List.of(new UserMessage("checkstyle の指摘の続きを確認してください。")),
        ToolCallingChatOptions.builder()
            .toolCallbacks(List.of(callbacks))
            .toolContext(Map.of(RunExecutionContext.KEY, context))
            .build());
  }

  /** {@code toolCall} の条件を再現するための補助情報を返す。 */
  private static AssistantMessage.ToolCall toolCall(String id, String name, String input) {
    return new AssistantMessage.ToolCall(id, "function", name, input);
  }

  /** 検証で使用する応答を、指定された条件に合わせて用意する。 */
  private static ChatResponse response(AssistantMessage.ToolCall... calls) {
    return new ChatResponse(
        List.of(
            new Generation(
                AssistantMessage.builder().content("").toolCalls(List.of(calls)).build())));
  }

  /** {@code latestToolMessage} の条件を再現するための補助情報を返す。 */
  private static ToolResponseMessage latestToolMessage(Prompt prompt) {
    return prompt.getInstructions().stream()
        .filter(ToolResponseMessage.class::isInstance)
        .map(ToolResponseMessage.class::cast)
        .reduce((first, last) -> last)
        .orElseThrow();
  }

  /** {@code correction} の条件を再現するための補助情報を返す。 */
  private static JsonNode correction(ToolResponseMessage message) {
    return JsonMapper.builder().build().readTree(message.getResponses().getLast().responseData());
  }

  /** 複数の検証で共通する期待結果を確認し、要件に合わない状態を検出する。 */
  private static void assertDelivery(
      ToolResponseMessage message, String callId, ResultDelivery.Kind kind) {
    assertThat(message.getMetadata().get("rei.deliveryPolicies")).isInstanceOf(Map.class);
    var policies = (Map<?, ?>) message.getMetadata().get("rei.deliveryPolicies");
    assertThat(policies.get(callId)).isEqualTo(kind);
  }

  /** {@code invalidArgumentEvents} の条件を再現するための補助情報を返す。 */
  private static List<ToolFailedPayload> invalidArgumentEvents(List<AgentEvent> events) {
    return events.stream()
        .map(AgentEvent::payload)
        .filter(ToolFailedPayload.class::isInstance)
        .map(ToolFailedPayload.class::cast)
        .filter(payload -> "invalid_tool_arguments".equals(payload.error().code()))
        .toList();
  }

  /**
   * 検証条件の再現に使う検証条件の情報。
   *
   * @param service 検証に使うサービス
   * @param approvals 検証に使う承認一覧
   * @param events 検証に使うイベント一覧
   */
  private record Fixture(
      ChatExecutionService service, ToolApprovalRepository approvals, List<AgentEvent> events) {
    /** 検証用に、要求された処理を実行する。 */
    ChatExecutionResult execute() {
      return service.execute(OWNER, "checkstyle の指摘の続きを確認してください。", new UserInterventionQueue());
    }
  }
}
