package dev.mikoto2000.rei.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.calls;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.llm.LlmFeature;
import dev.mikoto2000.rei.llm.LlmModelProvider;
import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.service.LlmMemoryProcessor;
import dev.mikoto2000.rei.memory.service.MemoryCandidateExtractor;
import dev.mikoto2000.rei.memory.service.MemoryRepository;
import dev.mikoto2000.rei.memory.service.MemoryResolver;
import dev.mikoto2000.rei.memory.service.MemoryService;
import dev.mikoto2000.rei.memory.service.SleepModelBudget;
import dev.mikoto2000.rei.memory.service.SleepService;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import reactor.core.publisher.Flux;

/** 待機モデル予算の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class SleepModelBudgetTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 検証に使う保存と検索の窓口。 */
  MemoryRepository repository;

  /** 検証に使うモデル。 */
  ChatModel model;

  /** 検証に使うやり取り一覧。 */
  ConversationTurnStore turns;

  /** 検証で使用するサービスを、指定された条件に合わせて用意する。 */
  SleepService service(
      int calls, long tokens, Integer extractionUsage, Integer resolutionUsage, boolean existing) {
    return service(calls, tokens, extractionUsage, resolutionUsage, existing, 0, 0);
  }

  /** 検証で使用するサービスを、指定された条件に合わせて用意する。 */
  SleepService service(
      int calls,
      long tokens,
      Integer extractionUsage,
      Integer resolutionUsage,
      boolean existing,
      long projectCalls,
      long projectTokens) {
    var props =
        new MemoryProperties(
            true,
            20,
            80,
            10,
            3,
            2000,
            60,
            null,
            null,
            new MemoryProperties.Sleep(
                .70, .50, 50, 12000, 120, calls, tokens, projectCalls, projectTokens));
    var source = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("memory.db"));
    repository = new MemoryRepository(source, new MemoryService(source, props));
    if (existing)
      repository.insert(
          LongTermMemoryTest.candidate("Previous unrelated note", MemoryScope.PROJECT), "p", "s");
    turns = ConversationTurnStore.inMemory();
    var owner = new AgentRunContext("turn1", "s", dir, "p");
    turns.start(owner, "Use vision first");
    turns.finish(owner, ConversationTurnStore.Status.COMPLETED, "agreed");
    model = mock(ChatModel.class);
    when(model.stream(any(Prompt.class)))
        .thenReturn(
            Flux.just(
                response(MemoryOutputTest.VALID.replace("\"t\"", "\"turn1\""), extractionUsage)),
            Flux.just(response("{\"action\":\"NEW\",\"targetIds\":[]}", resolutionUsage)));
    var provider = mock(LlmModelProvider.class);
    when(provider.memoryChatModel()).thenReturn(model);
    when(provider.chatOptions(eq(LlmFeature.MEMORY), any()))
        .thenAnswer(invocation -> OpenAiChatOptions.builder().build());
    var processor = new LlmMemoryProcessor(provider, props);
    return new SleepService(
        repository, turns, processor, new MemoryResolver(processor, props), props);
  }

  /** 検証で使用する応答を、指定された条件に合わせて用意する。 */
  ChatResponse response(String text, Integer tokens) {
    var metadata = ChatResponseMetadata.builder();
    if (tokens != null) metadata.usage(new DefaultUsage(1, tokens - 1));
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), metadata.build());
  }

  /** 長期記憶の活用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void extractionOvershootCannotWriteMemoryOrAdvanceCheckpoint() {
    var sleep = service(10, 3, 4, 1, false);
    var stopped = assertThrows(RuntimeException.class, () -> sleep.sleep("s", "p", false));
    assertTrue(stopped.getMessage().contains("TOKEN_BUDGET_EXCEEDED"));
    assertEquals(0, repository.lastProcessed("s"));
    assertTrue(repository.list("p", 10, 0).isEmpty());
    assertEquals("FAILED", repository.history("p", 10).getFirst().status());
  }

  /** 既存の記憶を保持することを確認する。 */
  @Test
  void extractionAndResolutionShareCallLimitAndPreserveExistingMemory() {
    var sleep = service(1, 0, 2, 2, true);
    var stopped = assertThrows(RuntimeException.class, () -> sleep.sleep("s", "p", false));
    assertTrue(stopped.getMessage().contains("LLM_CALL_BUDGET_EXCEEDED"));
    verify(model, times(1)).stream(any(Prompt.class));
    assertEquals(0, repository.lastProcessed("s"));
    assertEquals(1, repository.list("p", 10, 0).size());
  }

  /** 長期記憶の活用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void resolutionOvershootCannotCommitPartialPlans() {
    var sleep = service(10, 3, 2, 2, true);
    assertTrue(
        assertThrows(RuntimeException.class, () -> sleep.sleep("s", "p", false))
            .getMessage()
            .contains("TOKEN_BUDGET_EXCEEDED"));
    verify(model, times(2)).stream(any(Prompt.class));
    assertEquals(0, repository.lastProcessed("s"));
    assertEquals(1, repository.list("p", 10, 0).size());
  }

  /** 長期記憶の活用で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void exactLimitCanCommitWithoutAnotherModelCall() {
    var sleep = service(10, 3, 3, 1, false);
    var text = MemoryOutputTest.VALID.replace("\"t\"", "\"turn1\"");
    int middle = text.length() / 2;
    when(model.stream(any(Prompt.class)))
        .thenReturn(
            Flux.just(
                response(text.substring(0, middle), null), response(text.substring(middle), 3)));
    assertEquals("COMPLETED", sleep.sleep("s", "p", false).run().status());
    assertEquals(1, repository.lastProcessed("s"));
    assertEquals(1, repository.list("p", 10, 0).size());
    verify(model, times(1)).stream(any(Prompt.class));
  }

  /** 不明使用状況停止するおよび無効状態予算について、旧形式作業習慣を保持することを確認する。 */
  @Test
  void unknownUsageStopsAndDisabledBudgetPreservesLegacyBehavior() {
    var sleep = service(10, 3, null, null, false);
    assertTrue(
        assertThrows(RuntimeException.class, () -> sleep.sleep("s", "p", false))
            .getMessage()
            .contains("TOKEN_USAGE_UNKNOWN"));
    assertEquals(0, repository.lastProcessed("s"));
    var legacy = service(0, 0, null, null, false);
    assertEquals("COMPLETED", legacy.sleep("s", "p", false).run().status());
  }

  /** 長期記憶の活用で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void providerFailureDoesNotBecomeFreeRetryWithinSleep() {
    var sleep = service(10, 3, 2, 2, false);
    when(model.stream(any(Prompt.class)))
        .thenReturn(Flux.error(new IllegalStateException("offline")));
    assertTrue(
        assertThrows(RuntimeException.class, () -> sleep.sleep("s", "p", false))
            .getMessage()
            .contains("TOKEN_USAGE_UNKNOWN"));
    assertEquals(0, repository.lastProcessed("s"));
    assertTrue(repository.list("p", 10, 0).isEmpty());
    verify(model, times(1)).stream(any(Prompt.class));
  }

  /** 事前確認について、同一予算を利用することを確認する。 */
  @Test
  void previewUsesSameBudgetAndDoesNotWriteFailureAudit() {
    var sleep = service(10, 3, 4, 1, false);
    assertTrue(
        assertThrows(RuntimeException.class, () -> sleep.sleep("s", "p", true))
            .getMessage()
            .contains("TOKEN_BUDGET_EXCEEDED"));
    assertEquals(0, repository.lastProcessed("s"));
    assertTrue(repository.history("p", 10).isEmpty());
  }

  /** 長期記憶の活用で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void exactDuplicateDoesNotSpendResolutionCallAndNextInvocationGetsOwnBudget() {
    var sleep = service(1, 0, null, null, false);
    sleep.sleep("s", "p", false);
    var owner = new AgentRunContext("turn2", "s", dir, "p");
    turns.start(owner, "same lesson");
    turns.finish(owner, ConversationTurnStore.Status.COMPLETED, "agreed");
    when(model.stream(any(Prompt.class)))
        .thenReturn(
            Flux.just(response(MemoryOutputTest.VALID.replace("\"t\"", "\"turn2\""), null)));
    assertEquals("COMPLETED", sleep.sleep("s", "p", false).run().status());
    assertEquals(2, repository.lastProcessed("s"));
    assertEquals(1, repository.list("p", 10, 0).size());
    verify(model, times(2)).stream(any(Prompt.class));
  }

  /** 長期記憶の活用で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void invalidLimitsAreRejectedAndLegacyProcessorCannotBypassTokenCap() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new MemoryProperties.Sleep(.70, .50, 50, 12000, 120, -1, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MemoryProperties.Sleep(.70, .50, 50, 12000, 120, 1001, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MemoryProperties.Sleep(.70, .50, 50, 12000, 120, 0, -1));
    assertEquals(0, new MemoryProperties.Sleep(.70, .50, 50, 12000, 120).maxLlmCalls());
    MemoryCandidateExtractor legacy =
        batch -> {
          fail("Unaccounted extractor must not run");
          return List.of();
        };
    var budget =
        new SleepModelBudget(new MemoryProperties.Sleep(.70, .50, 50, 12000, 120, 0, 3), () -> {});
    assertThrows(IllegalStateException.class, () -> legacy.extract(List.of(), budget));
  }

  /** 機能の構成関連付けについて、待機既定値を維持することを確認する。 */
  @Test
  void configurationBindingKeepsSleepDefaultsAndBindsPositiveLimits() {
    var source =
        new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
            Map.of(
                "rei.memory.sleep.max-llm-calls",
                "2",
                "rei.memory.sleep.max-total-tokens",
                "3000"));
    var properties =
        new org.springframework.boot.context.properties.bind.Binder(source)
            .bind(
                "rei.memory",
                org.springframework.boot.context.properties.bind.Bindable.of(
                    MemoryProperties.class))
            .get();
    assertEquals(2, properties.sleep().maxLlmCalls());
    assertEquals(3000, properties.sleep().maxTotalTokens());
    assertEquals(.70, properties.sleep().minConfidence());
    assertEquals(.50, properties.sleep().minImportance());
    assertEquals(50, properties.sleep().maxTurns());
    assertEquals(12000, properties.sleep().maxInputTokens());
  }

  /** 長期記憶の活用で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void previewChargesProjectBudgetAndNewServiceCannotRetryForFree() {
    var sleep = service(0, 0, 2, 2, false, 1, 0);
    assertEquals("PREVIEW", sleep.sleep("s", "p", true).run().status());
    assertTrue(repository.history("p", 10).isEmpty());
    assertEquals(0, repository.lastProcessed("s"));
    var restarted = service(0, 0, 2, 2, false, 1, 0);
    assertTrue(
        assertThrows(RuntimeException.class, () -> restarted.sleep("s", "p", false))
            .getMessage()
            .contains("LLM_CALL_BUDGET_EXCEEDED"));
    verify(model, never()).stream(any(Prompt.class));
    assertTrue(repository.list("p", 10, 0).isEmpty());
  }

  /** 長期記憶の活用で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void lifetimeTokensChargeFailedPlanAndBlockProviderAfterRestart() {
    var sleep = service(0, 0, 2, 2, true, 0, 3);
    assertTrue(
        assertThrows(RuntimeException.class, () -> sleep.sleep("s", "p", false))
            .getMessage()
            .contains("TOKEN_BUDGET_EXCEEDED"));
    assertEquals(0, repository.lastProcessed("s"));
    assertEquals(1, repository.list("p", 10, 0).size());
    var restarted = service(0, 0, 2, 2, false, 0, 3);
    assertTrue(
        assertThrows(RuntimeException.class, () -> restarted.sleep("s", "p", false))
            .getMessage()
            .contains("TOKEN_BUDGET_EXCEEDED"));
    verify(model, never()).stream(any(Prompt.class));
  }

  /** 長期記憶の活用で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void lifetimeUnknownUsageAndProviderFailureBlockLaterInvocations() {
    var sleep = service(0, 0, 2, 2, false, 0, 10);
    when(model.stream(any(Prompt.class)))
        .thenReturn(Flux.error(new IllegalStateException("offline")));
    assertTrue(
        assertThrows(RuntimeException.class, () -> sleep.sleep("s", "p", false))
            .getMessage()
            .contains("TOKEN_USAGE_UNKNOWN"));
    assertTrue(
        assertThrows(RuntimeException.class, () -> sleep.sleep("s", "p", false))
            .getMessage()
            .contains("TOKEN_USAGE_UNKNOWN"));
    verify(model, times(1)).stream(any(Prompt.class));
    var restarted = service(0, 0, 2, 2, false, 0, 10);
    assertTrue(
        assertThrows(RuntimeException.class, () -> restarted.sleep("s", "p", false))
            .getMessage()
            .contains("TOKEN_USAGE_UNKNOWN"));
    verify(model, never()).stream(any(Prompt.class));
  }

  /** 長期記憶の活用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void lifetimeZeroReportedUsageStopsBeforeMemorySaveAndCannotBecomeFreeRetry() {
    var sleep = service(0, 0, 0, 0, false, 0, 10);
    assertTrue(
        assertThrows(RuntimeException.class, () -> sleep.sleep("s", "p", false))
            .getMessage()
            .contains("TOKEN_USAGE_UNKNOWN"));
    assertEquals(0, repository.lastProcessed("s"));
    assertTrue(repository.list("p", 10, 0).isEmpty());
    var restarted = service(0, 0, 1, 1, false, 0, 10);
    assertTrue(
        assertThrows(RuntimeException.class, () -> restarted.sleep("s", "p", false))
            .getMessage()
            .contains("TOKEN_USAGE_UNKNOWN"));
    verify(model, never()).stream(any(Prompt.class));
    var disabled = service(0, 0, 0, 0, false);
    assertEquals("COMPLETED", disabled.sleep("s", "p", false).run().status());
  }

  /** 長期記憶の活用の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationLeavesDurablePendingReservationWithoutAdvancingMemory() {
    var sleep = service(0, 0, 2, 2, false, 0, 10);
    when(model.stream(any(Prompt.class)))
        .thenReturn(Flux.error(new java.util.concurrent.CancellationException()));
    assertThrows(
        java.util.concurrent.CancellationException.class, () -> sleep.sleep("s", "p", false));
    assertEquals(0, repository.lastProcessed("s"));
    var restarted = service(0, 0, 2, 2, false, 0, 10);
    assertTrue(
        assertThrows(RuntimeException.class, () -> restarted.sleep("s", "p", false))
            .getMessage()
            .contains("TOKEN_USAGE_UNKNOWN"));
    verify(model, never()).stream(any(Prompt.class));
  }

  /** 長期記憶の活用で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void lifetimeBudgetBindingAndValidation() {
    var source =
        new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
            Map.of(
                "rei.memory.sleep.max-llm-calls-per-project",
                "12",
                "rei.memory.sleep.max-total-tokens-per-project",
                "3000"));
    var properties =
        new org.springframework.boot.context.properties.bind.Binder(source)
            .bind(
                "rei.memory",
                org.springframework.boot.context.properties.bind.Bindable.of(
                    MemoryProperties.class))
            .get();
    assertEquals(12, properties.sleep().maxLlmCallsPerProject());
    assertEquals(3000, properties.sleep().maxTotalTokensPerProject());
    assertThrows(
        IllegalArgumentException.class,
        () -> new MemoryProperties.Sleep(.70, .50, 50, 12000, 120, 0, 0, -1, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MemoryProperties.Sleep(.70, .50, 50, 12000, 120, 0, 0, 0, -1));
  }
}
