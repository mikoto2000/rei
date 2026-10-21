package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.dependency.DependencyAwaiter;
import dev.mikoto2000.rei.core.dependency.DependencyState;
import dev.mikoto2000.rei.core.process.BackgroundProcessManager;
import dev.mikoto2000.rei.core.process.BackgroundProcessSnapshot;
import dev.mikoto2000.rei.core.process.BackgroundProcessStatus;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** プロセス依存条件ツール一覧の結果と状態の契約を検証するテスト。 */
class ProcessDependencyToolsTest {
  /** 完了時の処理について、実行文脈出力先所属データ形式を維持することを確認する。 */
  @Test
  void callbackKeepsExecutionContextOutOfSchemaAndReportsVerifiedWaiting() {
    var manager = mock(BackgroundProcessManager.class);
    var tools = tools(manager);
    when(manager.status("p", null))
        .thenReturn(snapshot(BackgroundProcessStatus.RUNNING, null, true));
    var context =
        new dev.mikoto2000.rei.core.stagnation.RunExecutionContext(
            "run",
            new dev.mikoto2000.rei.llm.OutputLimitRunBudget(1, 10),
            new dev.mikoto2000.rei.core.stagnation.ProgressEvaluator(Path.of(".")),
            new dev.mikoto2000.rei.event.AgentEventFactory(Clock.systemUTC()),
            event -> {});
    var callback =
        org.springframework.ai.tool.method.MethodToolCallbackProvider.builder()
            .toolObjects(tools)
            .build()
            .getToolCallbacks()[0];
    assertFalse(callback.getToolDefinition().inputSchema().contains("toolContext"));
    context.beginIteration();
    var before = context.evaluator().beforeTool("waitForShellProcess", "{}");
    var result =
        callback.call(
            "{\"processId\":\"p\",\"timeoutSeconds\":0}",
            new org.springframework.ai.chat.model.ToolContext(
                Map.of(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY, context)));
    assertTrue(result.contains("WAITING"));
    context.recordTool("waitForShellProcess", "{}", result, before);
    context.endIteration();
    assertEquals(0, context.detector().stagnationCount());
  }

  /** 検証で使用する状態の記録を、指定された条件に合わせて用意する。 */
  BackgroundProcessSnapshot snapshot(BackgroundProcessStatus status, Integer code, boolean found) {
    return new BackgroundProcessSnapshot(
        "p", 42, status, code, Instant.EPOCH, null, 1, List.of("log"), List.of(), found, "");
  }

  /** {@code tools} の条件を再現するための補助情報を返す。 */
  ProcessDependencyTools tools(BackgroundProcessManager manager) {
    var tools = new ProcessDependencyTools(manager);
    var time = new AtomicLong();
    tools.setDependencyAwaiter(new DependencyAwaiter(time::get, d -> time.addAndGet(d.toNanos())));
    return tools;
  }

  /** 共通の実行基盤で、時刻や期限に応じて、待機・実行・保存の扱いを切り替えることを確認する。 */
  @Test
  void processCompletionAndTimeoutAreDifferentAndNeverLaunchOrKill() {
    var manager = mock(BackgroundProcessManager.class);
    var tools = tools(manager);
    when(manager.status("p", null))
        .thenReturn(
            snapshot(BackgroundProcessStatus.RUNNING, null, true),
            snapshot(BackgroundProcessStatus.EXITED, 0, true));
    var completed = tools.waitForShellProcess("p", 10, null);
    assertEquals(DependencyState.COMPLETED, completed.dependency().state());
    assertEquals(List.of("log"), completed.process().stdout());
    when(manager.status("p", null))
        .thenReturn(snapshot(BackgroundProcessStatus.RUNNING, null, true));
    assertEquals(
        DependencyState.WAITING, tools.waitForShellProcess("p", 1, null).dependency().state());
    verify(manager, never()).spawnShell(anyString(), any());
    verify(manager, never()).kill(anyString());
  }

  /** 共通の実行基盤の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void failuresCancellationAndUnknownProcessHaveExplicitStates() {
    var manager = mock(BackgroundProcessManager.class);
    var tools = tools(manager);
    when(manager.status("p", null)).thenReturn(snapshot(BackgroundProcessStatus.EXITED, 1, true));
    assertEquals(
        DependencyState.FAILED, tools.waitForShellProcess("p", 10, null).dependency().state());
    when(manager.status("p", null))
        .thenReturn(snapshot(BackgroundProcessStatus.KILLED, null, true));
    assertEquals(
        DependencyState.CANCELLED, tools.waitForShellProcess("p", 10, null).dependency().state());
    when(manager.status("p", null))
        .thenReturn(snapshot(BackgroundProcessStatus.FAILED, null, false));
    assertEquals(
        DependencyState.BLOCKED, tools.waitForShellProcess("p", 10, null).dependency().state());
    assertThrows(IllegalArgumentException.class, () -> tools.waitForShellProcess("p", 61, null));
  }
}
