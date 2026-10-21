package dev.mikoto2000.rei.memory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.calls;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException;
import dev.mikoto2000.rei.memory.configuration.MemoryConsolidationProperties;
import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 記憶統合予算の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.extension.ExtendWith(
    org.springframework.boot.test.system.OutputCaptureExtension.class)
class MemoryConsolidationBudgetTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証に使うモデル。 */
  ChatModel model;

  /** 検証で使用するサービスを、指定された条件に合わせて用意する。 */
  MemoryConsolidatorService service(int calls, long tokens, ChatResponse... responses) {
    var data =
        new DriverManagerDataSource("jdbc:sqlite:" + root.resolve(UUID.randomUUID() + ".db"));
    var jdbc = JdbcClient.create(data);
    jdbc.sql("CREATE TABLE SPRING_AI_CHAT_MEMORY(type TEXT,content TEXT,timestamp TEXT)").update();
    jdbc.sql("INSERT INTO SPRING_AI_CHAT_MEMORY VALUES('USER','use Java','2026-10-06')").update();
    model = mock(ChatModel.class);
    when(model.getOptions()).thenReturn(ChatOptions.builder().build());
    when(model.call(any(Prompt.class)))
        .thenReturn(responses[0], Arrays.copyOfRange(responses, 1, responses.length));
    var props = new MemoryProperties(true, 20, 80, 10, 3, 2000, 60, null);
    var service = new MemoryConsolidatorService(ChatClient.create(model), data, props);
    service.setConsolidationProperties(new MemoryConsolidationProperties(calls, tokens));
    return service;
  }

  /** 検証で使用する応答を、指定された条件に合わせて用意する。 */
  ChatResponse response(String text, Integer tokens) {
    return new ChatResponse(
        List.of(new Generation(new AssistantMessage(text))),
        ChatResponseMetadata.builder()
            .usage(tokens == null ? new EmptyUsage() : new DefaultUsage(tokens, 0))
            .build());
  }

  /** {@code candidates} の条件を再現するための補助情報を返す。 */
  String candidates() {
    return "[{\"content\":\"use Java\"}]";
  }

  /** 長期記憶の保存と検索で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void extractAndSummarizeShareOneCallBudget() {
    var service = service(1, 0, response(candidates(), 4), response("summary", 4));
    var stopped = assertThrows(ExecutionStoppedException.class, service::summarizeCandidates);
    assertEquals(ExecutionStoppedException.Reason.LLM_CALL_BUDGET_EXCEEDED, stopped.reason());
    verify(model, times(1)).call(any(Prompt.class));
  }

  /** 長期記憶の保存と検索で、記録した情報を、定められた分類と集約の規則に従ってまとめることを確認する。 */
  @Test
  void reportedUsageIsSummedAcrossBothPhasesBeforeReturningSummary() {
    var service = service(2, 7, response(candidates(), 4), response("summary", 4));
    var stopped = assertThrows(ExecutionStoppedException.class, service::summarizeCandidates);
    assertEquals(ExecutionStoppedException.Reason.TOKEN_BUDGET_EXCEEDED, stopped.reason());
    verify(model, times(2)).call(any(Prompt.class));
  }

  /** 次呼び出しを停止することを確認する。 */
  @Test
  void exactExtractionTokenLimitStopsTheNextCallAndUsagePrecedesFallbackAndClipping() {
    var service = service(2, 4, response(candidates(), 4), response("summary", 1));
    assertEquals(
        ExecutionStoppedException.Reason.TOKEN_BUDGET_EXCEEDED,
        assertThrows(ExecutionStoppedException.class, service::summarizeCandidates).reason());
    verify(model, times(1)).call(any(Prompt.class));
    service = service(0, 3, response("invalid JSON", 4));
    assertEquals(
        ExecutionStoppedException.Reason.TOKEN_BUDGET_EXCEEDED,
        assertThrows(ExecutionStoppedException.class, service::extractCandidates).reason());
    service = service(0, 3, response("x".repeat(2100), 4));
    var limited = service;
    assertEquals(
        ExecutionStoppedException.Reason.TOKEN_BUDGET_EXCEEDED,
        assertThrows(ExecutionStoppedException.class, () -> limited.summarize(List.of("input")))
            .reason());
  }

  /** 長期記憶の保存と検索で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void unknownUsageAndProviderFailureStopWithoutFallbackCandidates() {
    var service = service(0, 10, response(candidates(), null));
    assertEquals(
        ExecutionStoppedException.Reason.TOKEN_USAGE_UNKNOWN,
        assertThrows(ExecutionStoppedException.class, service::extractCandidates).reason());
    service = service(0, 10, response(candidates(), 1));
    when(model.call(any(Prompt.class)))
        .thenThrow(new IllegalStateException("private provider diagnostic"));
    assertEquals(
        ExecutionStoppedException.Reason.TOKEN_USAGE_UNKNOWN,
        assertThrows(ExecutionStoppedException.class, service::summarizeCandidates).reason());
    service = service(0, 0, response("plain fallback", null));
    assertFalse(service.extractCandidates().isEmpty());
  }

  /** 完了済みのコマンド一覧について、完全一致の合計上限を受け付けられることを確認する。 */
  @Test
  void completedCommandsAcceptExactTotalLimitAndDirectInvocationsStartFresh() {
    var service = service(2, 8, response(candidates(), 4), response("summary", 4));
    var summary = service.summarizeCandidates();
    assertTrue(summary.hasCandidates());
    assertEquals("summary", summary.summary());
    service = service(1, 4, response("x".repeat(2100), 4));
    assertEquals(2000, service.summarize(List.of("first")).length());
    assertEquals(2000, service.summarize(List.of("second")).length());
    verify(model, times(2)).call(any(Prompt.class));
    assertEquals("", service.summarize(List.of()));
    verify(model, times(2)).call(any(Prompt.class));
  }

  /** 長期記憶の保存と検索で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void approvedCommandsDoNotSaveAfterBudgetStop(
      org.springframework.boot.test.system.CapturedOutput output) {
    var service = service(1, 0, response(candidates(), 4), response("summary", 4));
    var memories = mock(MemoryService.class);
    new picocli.CommandLine(
            new dev.mikoto2000.rei.memory.command.MemoryCommand.SummarizeCommand(service, memories))
        .execute("--approve");
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(memories);
    verify(model, times(1)).call(any(Prompt.class));
    assertTrue(output.getOut().contains("[stopped] LLM_CALL_BUDGET_EXCEEDED"));
    service = service(0, 1, response(candidates(), null));
    new picocli.CommandLine(
            new dev.mikoto2000.rei.memory.command.MemoryCommand.ConsolidateCommand(
                service,
                memories,
                mock(dev.mikoto2000.rei.memory.util.SensitiveInfoDetector.class),
                mock(MemoryConflictResolver.class)))
        .execute("--approve");
    verifyNoInteractions(memories);
    assertTrue(output.getOut().contains("[stopped] TOKEN_USAGE_UNKNOWN"));
    assertFalse(output.getOut().contains("private provider diagnostic"));
  }

  /** 承認済み完了要約について、既存の保存契約を維持することを確認する。 */
  @Test
  void approvedCompleteSummaryKeepsTheExistingSaveContract() {
    var service = service(2, 8, response(candidates(), 4), response("summary", 4));
    var memories = mock(MemoryService.class);
    new picocli.CommandLine(
            new dev.mikoto2000.rei.memory.command.MemoryCommand.SummarizeCommand(service, memories))
        .execute("--approve");
    var saved = org.mockito.ArgumentCaptor.forClass(dev.mikoto2000.rei.memory.model.Memory.class);
    verify(memories).save(saved.capture());
    assertEquals("summary", saved.getValue().content());
    assertEquals(dev.mikoto2000.rei.memory.model.MemoryStatus.CANDIDATE, saved.getValue().status());
  }

  /** 長期記憶の保存と検索の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationIsPreservedBeforeInvocationAndFromWrappedProviderInterruption() {
    var service = service(0, 10, response(candidates(), 1));
    Thread.currentThread().interrupt();
    try {
      assertThrows(java.util.concurrent.CancellationException.class, service::summarizeCandidates);
      verify(model, never()).call(any(Prompt.class));
    } finally {
      Thread.interrupted();
    }
    when(model.call(any(Prompt.class)))
        .thenThrow(new IllegalStateException(new InterruptedException()));
    try {
      assertThrows(java.util.concurrent.CancellationException.class, service::summarizeCandidates);
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  /** 長期記憶の保存と検索で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void consolidationSettingsBindWithIndependentZeroDefaults() {
    var defaults = new MemoryConsolidationProperties(0, 0);
    assertEquals(0, defaults.maxTotalTokens());
    var bound =
        new org.springframework.boot.context.properties.bind.Binder(
                new org.springframework.boot.context.properties.source
                    .MapConfigurationPropertySource(
                    Map.of(
                        "rei.memory.consolidation.max-llm-calls",
                        "2",
                        "rei.memory.consolidation.max-total-tokens",
                        "50")))
            .bind("rei.memory.consolidation", MemoryConsolidationProperties.class)
            .get();
    assertEquals(2, bound.maxLlmCalls());
    assertEquals(50, bound.maxTotalTokens());
    assertThrows(IllegalArgumentException.class, () -> new MemoryConsolidationProperties(1001, 0));
    assertThrows(IllegalArgumentException.class, () -> new MemoryConsolidationProperties(0, -1));
  }
}
