package dev.mikoto2000.rei.core.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.ProcessDependencyTools;
import dev.mikoto2000.rei.core.policy.ActionCapability;
import dev.mikoto2000.rei.core.policy.ToolApprovalRepository;
import dev.mikoto2000.rei.core.policy.ToolPermissionGuard;
import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import dev.mikoto2000.rei.core.process.BackgroundProcessManager;
import dev.mikoto2000.rei.core.process.BackgroundProcessSnapshot;
import dev.mikoto2000.rei.core.process.BackgroundProcessStatus;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.core.stagnation.StagnationChatModel;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.ToolFailedPayload;
import dev.mikoto2000.rei.llm.FixedLlmChatClientProvider;
import dev.mikoto2000.rei.llm.FixedLlmModelProvider;
import dev.mikoto2000.rei.llm.LlmProperties;
import dev.mikoto2000.rei.llm.RunAwareToolCallingAdvisor;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.stream.Stream;
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
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 待機対象シェルプロセス引数補正の結果と状態の契約を検証するテスト。 */
class WaitForShellProcessArgumentCorrectionTest {
  /** 不正な待機について、案内を返すことを確認する。 */
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void invalidWaitReturnsGuidanceBeforeExecutionAndTheModelCanCorrectIt(boolean guarded) {
    var processes = mock(BackgroundProcessManager.class);
    when(processes.status("existing-process", null)).thenReturn(completedProcess());
    var calls = new AtomicInteger();
    var fixture =
        fixture(
            processes,
            guarded,
            2,
            calls,
            (prompt, invocation) -> {
              if (invocation == 1) {
                return waitCall("invalid-wait", 120);
              }
              if (invocation == 2) {
                // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
                verifyNoInteractions(processes);
                var correction = JsonMapper.builder().build().readTree(latestToolResponse(prompt));
                assertThat(correction.path("error").asText()).isEqualTo("INVALID_TOOL_ARGUMENTS");
                assertThat(correction.path("executed").asBoolean()).isFalse();
                assertThat(correction.path("guidance").asText()).contains("timeoutSeconds", "60");
                assertThat(correction.path("correctionsRemaining").asInt()).isEqualTo(1);
                return waitCall("corrected-wait", 60);
              }
              assertThat(invocation).isEqualTo(3);
              assertThat(latestToolResponse(prompt)).contains("COMPLETED", "existing-process");
              return answer("ビルドの正常終了を確認しました。");
            });

    var result = fixture.execute();

    assertThat(result.success()).isTrue();
    assertThat(result.text()).contains("ビルドの正常終了");
    assertThat(calls).hasValue(3);
    verify(processes, times(1)).status("existing-process", null);
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
  void repeatedInvalidWaitsStopAtTheExistingArgumentCorrectionLimit(boolean guarded) {
    var processes = mock(BackgroundProcessManager.class);
    var calls = new AtomicInteger();
    var remaining = new ArrayList<Integer>();
    var fixture =
        fixture(
            processes,
            guarded,
            2,
            calls,
            (prompt, invocation) -> {
              assertThat(invocation).isLessThanOrEqualTo(3);
              if (invocation > 1) {
                JsonNode correction =
                    JsonMapper.builder().build().readTree(latestToolResponse(prompt));
                assertThat(correction.path("error").asText()).isEqualTo("INVALID_TOOL_ARGUMENTS");
                assertThat(correction.path("maxCorrections").asInt()).isEqualTo(2);
                remaining.add(correction.path("correctionsRemaining").asInt());
              }
              return waitCall("invalid-wait-" + invocation, 120);
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
    verifyNoInteractions(processes, fixture.approvals());
  }

  /** 会話の実行で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @ParameterizedTest
  @MethodSource("runtimeFailures")
  void genuineProcessFailureIsNotTreatedAsAnArgumentCorrection(
      boolean guarded, RuntimeException failure, String expectedStopCode) {
    var processes = mock(BackgroundProcessManager.class);
    when(processes.status("existing-process", null)).thenThrow(failure);
    var calls = new AtomicInteger();
    var fixture =
        fixture(
            processes,
            guarded,
            2,
            calls,
            (prompt, invocation) -> {
              assertThat(invocation).isEqualTo(1);
              return waitCall("valid-wait", 0);
            });

    var result = fixture.execute();

    assertThat(result.status()).isEqualTo(ChatExecutionResult.Status.FAILED);
    assertThat(result.stopCode()).isEqualTo(expectedStopCode);
    assertThat(calls).hasValue(1);
    assertThat(invalidArgumentEvents(fixture.events())).isEmpty();
    verify(processes, times(1)).status("existing-process", null);
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(fixture.approvals());
  }

  /** {@code runtimeFailures} の条件を再現するための補助情報を返す。 */
  private static Stream<Arguments> runtimeFailures() {
    return Stream.of(
        Arguments.of(
            true,
            new IllegalStateException("Managed process storage unavailable"),
            "invalid_state"),
        Arguments.of(
            false,
            new IllegalStateException("Managed process storage unavailable"),
            "invalid_state"),
        Arguments.of(
            true,
            new IllegalArgumentException("Managed process record is invalid"),
            "invalid_arguments"),
        Arguments.of(
            false,
            new IllegalArgumentException("Managed process record is invalid"),
            "invalid_arguments"));
  }

  /** {@code fixture} の条件を再現するための補助情報を返す。 */
  private Fixture fixture(
      BackgroundProcessManager processes,
      boolean guarded,
      int correctionLimit,
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
        MethodToolCallbackProvider.builder()
            .toolObjects(new ProcessDependencyTools(processes))
            .build()
            .getToolCallbacks()[0];
    var client =
        ChatClient.builder(new StagnationChatModel(model))
            .defaultAdvisors(new RunAwareToolCallingAdvisor())
            .defaultToolCallbacks(callback)
            .build();
    var properties = new LlmProperties();
    properties.getOutputLimit().setMaxToolArgumentCorrectionsPerRun(correctionLimit);
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
      var policy =
          new ToolPermissionPolicy(
              new ToolPermissionProperties(
                  true, Set.of(ActionCapability.READ), Set.of(), Map.of()));
      var guard = new ToolPermissionGuard(policy, factory, events::add);
      guard.setApprovals(approvals);
      service.setToolPermissionGuard(guard);
    }
    return new Fixture(service, approvals, events);
  }

  /** 検証用に、必要な状態が整ってから先へ進むため、呼び出しを待つ。 */
  private static ChatResponse waitCall(String callId, int seconds) {
    String input = "{\"processId\":\"existing-process\",\"timeoutSeconds\":" + seconds + "}";
    var call = new AssistantMessage.ToolCall(callId, "function", "waitForShellProcess", input);
    return new ChatResponse(
        List.of(
            new Generation(
                AssistantMessage.builder().content("").toolCalls(List.of(call)).build())));
  }

  /** {@code answer} の条件を再現するための補助情報を返す。 */
  private static ChatResponse answer(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }

  /** {@code latestToolResponse} の条件を再現するための補助情報を返す。 */
  private static String latestToolResponse(Prompt prompt) {
    return prompt.getInstructions().stream()
        .filter(ToolResponseMessage.class::isInstance)
        .map(ToolResponseMessage.class::cast)
        .reduce((first, last) -> last)
        .orElseThrow()
        .getResponses()
        .getLast()
        .responseData();
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

  /** {@code completedProcess} の条件を再現するための補助情報を返す。 */
  private static BackgroundProcessSnapshot completedProcess() {
    return new BackgroundProcessSnapshot(
        "existing-process",
        42,
        BackgroundProcessStatus.EXITED,
        0,
        Instant.EPOCH,
        Instant.EPOCH,
        1,
        List.of("build succeeded"),
        List.of(),
        true,
        "");
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
      var context = new AgentRunContext("run", "session", Path.of("."), "project");
      return service.execute(context, "ビルド済みプロセスの終了を確認してください。", new UserInterventionQueue());
    }
  }
}
