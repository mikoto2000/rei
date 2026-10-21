package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException;
import dev.mikoto2000.rei.core.stagnation.ProgressEvaluator;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.core.stagnation.StagnationChatModel;
import dev.mikoto2000.rei.llm.LlmFeature;
import dev.mikoto2000.rei.llm.LlmModelProvider;
import dev.mikoto2000.rei.llm.LlmProperties;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

/** 文脈要約トークン予算の結果と状態の契約を検証するテスト。 */
class ContextSummaryTokenBudgetTest {
  /** 検証に使うディレクトリ。 */
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir;

  /** 検証で使用する応答を、指定された条件に合わせて用意する。 */
  ChatResponse response(String text, Integer tokens) {
    var metadata = ChatResponseMetadata.builder();
    if (tokens != null) metadata.usage(new DefaultUsage(1, tokens - 1));
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), metadata.build());
  }

  /** {@code compressor} の条件を再現するための補助情報を返す。 */
  LlmConversationCompressor compressor(ChatModel model) {
    return compressor(model, new ContextCompressionProperties());
  }

  /** {@code compressor} の条件を再現するための補助情報を返す。 */
  LlmConversationCompressor compressor(ChatModel model, ContextCompressionProperties properties) {
    var provider = mock(LlmModelProvider.class);
    when(provider.chatModel(LlmFeature.CHAT)).thenReturn(model);
    when(provider.chatOptions(eq(LlmFeature.CHAT), any()))
        .thenAnswer(
            invocation -> org.springframework.ai.openai.OpenAiChatOptions.builder().build());
    @SuppressWarnings("unchecked")
    ObjectProvider<LlmModelProvider> models = mock(ObjectProvider.class);
    when(models.getObject()).thenReturn(provider);
    return new LlmConversationCompressor(models, properties);
  }

  /** {@code owner} の条件を再現するための補助情報を返す。 */
  Prompt owner(RunExecutionContext execution) {
    return new Prompt(
        "current",
        ToolCallingChatOptions.builder()
            .toolContext(Map.of(RunExecutionContext.KEY, execution))
            .build());
  }

  /** モデルへ渡す文脈の管理で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void summaryChargesAggregatedUsageOnceAndBlocksPrepaidParentAtLimit() {
    var budget = new OutputLimitRunBudget(0, 10, null, 5);
    var execution = new RunExecutionContext("run", budget, null, null, null);
    execution.consumeNextLlmCall();
    var model = mock(ChatModel.class);
    when(model.stream(any(Prompt.class)))
        .thenReturn(Flux.just(response("compact", null), response(" summary", 5)));
    assertThat(
            compressor(model)
                .summarize("", List.of(new UserMessage("history")), 100, owner(execution)))
        .isEqualTo("compact summary");
    assertThat(budget.totalTokens()).isEqualTo(5);
    var parent = mock(ChatModel.class);
    assertThatThrownBy(
            () -> new StagnationChatModel(parent).stream(owner(execution)).collectList().block())
        .hasMessageContaining("TOKEN_BUDGET_EXCEEDED");
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(parent);
  }

  /** モデルへ渡す文脈の管理で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void standaloneSummaryRejectsOvershootAndUnknownUsageWithoutRunContext() {
    var properties = new ContextCompressionProperties();
    properties.setStandaloneSummaryMaxTotalTokens(5);
    var model = mock(ChatModel.class);
    for (Integer tokens : Arrays.asList(6, null)) {
      when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("summary", tokens)));
      assertThatThrownBy(
              () ->
                  compressor(model, properties)
                      .summarize(
                          "", List.of(new UserMessage("history")), 100, new Prompt("current")))
          .hasMessageContaining(tokens == null ? "TOKEN_USAGE_UNKNOWN" : "TOKEN_BUDGET_EXCEEDED");
    }
  }

  /** モデルへ渡す文脈の管理で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void unknownUsageAndProviderFailureStopInsteadOfBecomingFreeFallback() {
    for (Flux<ChatResponse> responses :
        List.of(
            Flux.just(response("summary", null)),
            Flux.<ChatResponse>error(new IllegalStateException("offline")),
            Flux.<ChatResponse>empty())) {
      var execution =
          new RunExecutionContext(
              "run", new OutputLimitRunBudget(0, 10, null, 5), null, null, null);
      var model = mock(ChatModel.class);
      when(model.stream(any(Prompt.class))).thenReturn(responses);
      assertThatThrownBy(
              () ->
                  compressor(model)
                      .summarize("", List.of(new UserMessage("history")), 100, owner(execution)))
          .hasMessageContaining("TOKEN_USAGE_UNKNOWN");
      assertThat(execution.sharedLlmReservation().tryReserve()).isFalse();
    }
  }

  /** 要約を受け付けないことを確認する。 */
  @Test
  void overshootRejectsSummaryAndDisabledLimitPreservesUnknownUsage() {
    var model = mock(ChatModel.class);
    when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("summary", 6)));
    var execution =
        new RunExecutionContext("run", new OutputLimitRunBudget(0, 10, null, 5), null, null, null);
    assertThatThrownBy(
            () ->
                compressor(model)
                    .summarize("", List.of(new UserMessage("history")), 100, owner(execution)))
        .hasMessageContaining("TOKEN_BUDGET_EXCEEDED");
    var legacy = new RunExecutionContext("old", new OutputLimitRunBudget(0, 10), null, null, null);
    when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("summary", null)));
    assertThat(
            compressor(model)
                .summarize("", List.of(new UserMessage("history")), 100, owner(legacy)))
        .isEqualTo("summary");
  }

  /** モデルへ渡す文脈の管理で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void truncatedSummaryChargesKnownUsageBeforeOutputLimitFailure() {
    var model = mock(ChatModel.class);
    when(model.stream(any(Prompt.class)))
        .thenReturn(
            Flux.just(
                new ChatResponse(
                    List.of(
                        new Generation(
                            new AssistantMessage("partial"),
                            ChatGenerationMetadata.builder().finishReason("length").build())),
                    ChatResponseMetadata.builder().usage(new DefaultUsage(1, 1)).build())));
    var budget = new OutputLimitRunBudget(0, 10, null, 100);
    var execution = new RunExecutionContext("run", budget, null, null, null);
    assertThatThrownBy(
            () ->
                compressor(model)
                    .summarize("", List.of(new UserMessage("history")), 100, owner(execution)))
        .isInstanceOfSatisfying(
            ContextCompressionException.class,
            failure ->
                assertThat(failure.reason())
                    .isEqualTo(ContextCompressionException.Reason.OUTPUT_LIMIT));
    assertThat(budget.totalTokens()).isEqualTo(2);
    assertThat(budget.usageUnknown()).isFalse();
  }

  /** モデルへ渡す文脈の管理の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void summaryTimeoutCancelsProviderAndBlocksUnknownUsage() {
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    var model = mock(ChatModel.class);
    when(model.stream(any(Prompt.class)))
        .thenReturn(Flux.<ChatResponse>never().doOnCancel(() -> cancelled.set(true)));
    var execution =
        new RunExecutionContext(
            "run", new OutputLimitRunBudget(0, 10, null, 100), null, null, null);
    var properties = new ContextCompressionProperties();
    properties.setSummaryTimeoutSeconds(1);
    assertThatThrownBy(
            () ->
                compressor(model, properties)
                    .summarize("", List.of(new UserMessage("history")), 100, owner(execution)))
        .isInstanceOfSatisfying(
            ExecutionStoppedException.class,
            stopped -> {
              assertThat(stopped.reason())
                  .isEqualTo(ExecutionStoppedException.Reason.TOKEN_USAGE_UNKNOWN);
              assertThat(stopped.getSuppressed())
                  .singleElement()
                  .isInstanceOfSatisfying(
                      ContextCompressionException.class,
                      summaryFailure -> {
                        assertThat(summaryFailure.reason())
                            .isEqualTo(ContextCompressionException.Reason.TIMEOUT);
                        assertThat(summaryFailure.getCause()).isNull();
                        assertThat(summaryFailure.getSuppressed()).isEmpty();
                      });
            });
    assertThat(cancelled).isTrue();
    assertThat(execution.sharedLlmReservation().tryReserve()).isFalse();
  }

  /** {@code compressionConfig} の条件を再現するための補助情報を返す。 */
  ContextCompressionProperties compressionConfig() {
    var props = new ContextCompressionProperties();
    props.setThreshold(300);
    props.setHardLimit(600);
    props.setRecentTokens(80);
    props.setSummaryTokens(100);
    return props;
  }

  /** {@code standaloneHistorical} の条件を再現するための補助情報を返す。 */
  Prompt standaloneHistorical() {
    return new Prompt(
        List.of(
            ContextHistoryAdvisor.historical(new UserMessage("old ".repeat(500)), 1),
            ContextHistoryAdvisor.historical(new AssistantMessage("recent"), 2),
            new UserMessage("current"),
            new AssistantMessage("active ".repeat(500)),
            new AssistantMessage("recent active")),
        ToolCallingChatOptions.builder().build());
  }

  /** {@code assembler} の条件を再現するための補助情報を返す。 */
  ContextAssembler assembler(
      ContextCompressionProperties props,
      ConversationCompressor compressor,
      ConversationSummaryRepository summaries) {
    var tokens = TokenEstimator.conservative();
    return new ContextAssembler(
        props,
        tokens,
        summaries,
        new ToolResultCompressor(new RawToolResultStore(dir), tokens, 100, 70),
        compressor,
        null,
        null);
  }

  /** モデルへ渡す文脈の管理で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  @org.junit.jupiter.api.Tag("integration")
  void ownerlessHistoryAndActiveSummariesShareOneProjectionBudget() {
    for (boolean callsOnly : List.of(true, false)) {
      var props = compressionConfig();
      props.setStandaloneSummaryMaxLlmCalls(callsOnly ? 1 : 0);
      props.setStandaloneSummaryMaxTotalTokens(callsOnly ? 0 : 5);
      var model = mock(ChatModel.class);
      when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("compact", 3)));
      var summaries = new ConversationSummaryRepository(dir.resolve("shared-" + callsOnly));
      assertThatThrownBy(
              () ->
                  assembler(props, compressor(model, props), summaries)
                      .assemble(standaloneHistorical(), "chat", "run", () -> {}))
          .hasMessageContaining(callsOnly ? "LLM_CALL_BUDGET_EXCEEDED" : "TOKEN_BUDGET_EXCEEDED");
      verify(model, times(callsOnly ? 1 : 2)).stream(any(Prompt.class));
      assertThat(summaries.read("chat").throughSequence()).isEqualTo(1);
      assertThat(
              summaries.read(ConversationSummaryRepository.runKey("chat", "run")).throughSequence())
          .isZero();
    }
  }

  /** モデルへ渡す文脈の管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  @org.junit.jupiter.api.Tag("integration")
  void unknownStandaloneUsageCannotFallBackOrSaveSummary() {
    var props = compressionConfig();
    props.setStandaloneSummaryMaxTotalTokens(5);
    var model = mock(ChatModel.class);
    when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("compact", null)));
    var summaries = new ConversationSummaryRepository(dir);
    assertThatThrownBy(
            () ->
                assembler(props, compressor(model, props), summaries)
                    .assemble(standaloneHistorical(), "chat", "run", () -> {}))
        .hasMessageContaining("TOKEN_USAGE_UNKNOWN");
    verify(model, times(1)).stream(any(Prompt.class));
    assertThat(summaries.read("chat").throughSequence()).isZero();
  }

  /** モデルへ渡す文脈の管理で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void directStandaloneCallsHaveIndependentBudgetAndExactBoundarySucceeds() {
    var props = new ContextCompressionProperties();
    props.setStandaloneSummaryMaxLlmCalls(1);
    props.setStandaloneSummaryMaxTotalTokens(5);
    var model = mock(ChatModel.class);
    when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("compact", 5)));
    var compressor = compressor(model, props);
    for (int i = 0; i < 2; i++)
      assertThat(
              compressor.summarize(
                  "", List.of(new UserMessage("history")), 100, new Prompt("current")))
          .isEqualTo("compact");
    verify(model, times(2)).stream(any(Prompt.class));
  }

  /** モデルへ渡す文脈の管理の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void standaloneProviderFailureAndTimeoutFailClosedAndCancelStream() {
    var props = new ContextCompressionProperties();
    props.setStandaloneSummaryMaxTotalTokens(5);
    props.setSummaryTimeoutSeconds(1);
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    var model = mock(ChatModel.class);
    when(model.stream(any(Prompt.class)))
        .thenReturn(
            Flux.error(new IllegalStateException("offline")),
            Flux.<ChatResponse>never().doOnCancel(() -> cancelled.set(true)));
    var compressor = compressor(model, props);
    for (int i = 0; i < 2; i++)
      assertThatThrownBy(
              () ->
                  compressor.summarize(
                      "", List.of(new UserMessage("history")), 100, new Prompt("current")))
          .hasMessageContaining("TOKEN_USAGE_UNKNOWN");
    assertThat(cancelled).isTrue();
  }

  /** モデルへ渡す文脈の管理で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void standaloneLimitsDoNotReplaceExistingRunBudget() {
    var props = new ContextCompressionProperties();
    props.setStandaloneSummaryMaxTotalTokens(1);
    var model = mock(ChatModel.class);
    when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("compact", 3)));
    var budget = new OutputLimitRunBudget(0, 10, null, 5);
    var execution = new RunExecutionContext("run", budget, null, null, null);
    assertThat(
            compressor(model, props)
                .summarize("", List.of(new UserMessage("history")), 100, owner(execution)))
        .isEqualTo("compact");
    assertThat(budget.totalTokens()).isEqualTo(3);
  }

  /** モデルへ渡す文脈の管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void standaloneBindingValidationAndUnsupportedCompressorCannotBypassAccounting() {
    var source =
        new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
            Map.of(
                "rei.context-compression.standalone-summary-max-llm-calls", "2",
                "rei.context-compression.standalone-summary-max-total-tokens", "3000"));
    var props =
        new org.springframework.boot.context.properties.bind.Binder(source)
            .bind(
                "rei.context-compression",
                org.springframework.boot.context.properties.bind.Bindable.of(
                    ContextCompressionProperties.class))
            .get();
    props.validate();
    assertThat(props.getStandaloneSummaryMaxLlmCalls()).isEqualTo(2);
    assertThat(props.getStandaloneSummaryMaxTotalTokens()).isEqualTo(3000);
    props.setStandaloneSummaryMaxLlmCalls(1001);
    assertThatThrownBy(props::validate).isInstanceOf(IllegalArgumentException.class);
    props.setStandaloneSummaryMaxLlmCalls(1);
    props.setStandaloneSummaryMaxTotalTokens(-1);
    assertThatThrownBy(props::validate).isInstanceOf(IllegalArgumentException.class);
    props.setStandaloneSummaryMaxTotalTokens(5);
    var invoked = new java.util.concurrent.atomic.AtomicBoolean();
    ConversationCompressor legacy =
        (previous, messages, max, owner) -> {
          invoked.set(true);
          return "unaccounted";
        };
    assertThatThrownBy(
            () ->
                legacy.summarize(
                    "",
                    List.of(),
                    100,
                    new Prompt("current"),
                    StandaloneSummaryBudget.create(props)))
        .hasMessageContaining("TOKEN_USAGE_UNKNOWN");
    assertThat(invoked).isFalse();
  }

  /** {@code historical} の条件を再現するための補助情報を返す。 */
  Prompt historical(RunExecutionContext execution) {
    return new Prompt(
        List.of(
            ContextHistoryAdvisor.historical(new UserMessage("old ".repeat(500)), 1),
            ContextHistoryAdvisor.historical(new AssistantMessage("recent"), 2),
            new UserMessage("current")),
        owner(execution).getOptions());
  }

  /** モデルへ渡す文脈の管理で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  @org.junit.jupiter.api.Tag("integration")
  void actualCompressionChargesDurableGoalAndCannotFallBackIntoParentModel() {
    for (Integer usage : Arrays.asList(5, 6, null)) {
      var variant = dir.resolve("case-" + usage);
      var source =
          new org.springframework.jdbc.datasource.DriverManagerDataSource(
              "jdbc:sqlite:" + dir.resolve("goal-" + usage + ".db"));
      var properties = new LlmProperties();
      properties.getOutputLimit().setMaxTotalTokensPerGoal(5);
      var goals =
          new dev.mikoto2000.rei.goal.GoalRepository(
              source, java.time.Clock.systemUTC(), properties);
      var goal =
          goals.create(
              new dev.mikoto2000.rei.core.chat.AgentRunContext("source", "chat", dir, "project"),
              "Artifact",
              "out.txt",
              "a".repeat(64),
              3,
              10);
      var claim = goals.claim("project", goal.id());
      var runId = goals.beginAttempt(claim);
      var budget = new OutputLimitRunBudget(0, 10, goals.modelBudget(claim, runId));
      var events = new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC());
      var execution =
          new RunExecutionContext(
              runId, budget, new ProgressEvaluator(dir, null), events, event -> {});
      execution.setRunContext(
          new dev.mikoto2000.rei.core.chat.AgentRunContext(runId, "chat", dir, "project"));
      execution.consumeNextLlmCall();
      var summary = mock(ChatModel.class);
      when(summary.stream(any(Prompt.class))).thenReturn(Flux.just(response("summary", usage)));
      var summaries = new ConversationSummaryRepository(variant);
      var tokens = TokenEstimator.conservative();
      var assembler =
          new ContextAssembler(
              compressionConfig(),
              tokens,
              summaries,
              new ToolResultCompressor(new RawToolResultStore(variant), tokens, 100, 70),
              compressor(summary),
              events,
              event -> {});
      var parent = mock(ChatModel.class);
      assertThatThrownBy(
              () ->
                  new StagnationChatModel(parent, assembler)
                      .stream(historical(execution)).collectList().block())
          .hasMessageContaining(usage == null ? "TOKEN_USAGE_UNKNOWN" : "TOKEN_BUDGET_EXCEEDED");
      // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
      verifyNoInteractions(parent);
      var reopened =
          new dev.mikoto2000.rei.goal.GoalRepository(source, java.time.Clock.systemUTC());
      assertThat(reopened.get("project", goal.id()).totalTokens())
          .isEqualTo(usage == null ? 0 : usage);
      assertThat(reopened.get("project", goal.id()).llmCallsUsed()).isEqualTo(2);
      assertThat(reopened.get("project", goal.id()).tokenUsageUnknown()).isEqualTo(usage == null);
      assertThat(summaries.read("chat").throughSequence())
          .isEqualTo(Objects.equals(usage, 5) ? 1 : 0);
    }
  }

  /** モデルへ渡す文脈の管理で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  @org.junit.jupiter.api.Tag("integration")
  void rejectedLowQualitySummaryStillChargesUsageBeforeDegradedProjection() {
    var budget = new OutputLimitRunBudget(0, 10, null, 100);
    var execution = new RunExecutionContext("run", budget, null, null, null);
    execution.consumeNextLlmCall();
    var summary = mock(ChatModel.class);
    when(summary.stream(any(Prompt.class))).thenReturn(Flux.just(response("bad ".repeat(500), 2)));
    var summaries = new ConversationSummaryRepository(dir);
    var tokens = TokenEstimator.conservative();
    var assembler =
        new ContextAssembler(
            compressionConfig(),
            tokens,
            summaries,
            new ToolResultCompressor(new RawToolResultStore(dir), tokens, 100, 70),
            compressor(summary),
            null,
            null);
    assertThat(
            assembler
                .assemble(historical(execution), "chat", "run", execution::checkActive)
                .getContents())
        .contains("current");
    assertThat(budget.totalTokens()).isEqualTo(2);
    assertThat(budget.remainingLlmCalls()).isEqualTo(8);
    assertThat(summaries.read("chat").throughSequence()).isZero();
    verify(summary, times(1)).stream(any(Prompt.class));
  }
}
