package dev.mikoto2000.rei.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** 補助エージェントManualコマンドの結果と状態の契約を検証するテスト。 */
class SubAgentManualCommandTest {
  /** 既存のローカル実行の担当を利用することを確認する。 */
  @Test
  void omittedBackendUsesExistingLocalRunner() {
    var runner = mock(SubAgentRunner.class);
    when(runner.run("reviewer", "task", null))
        .thenReturn(
            new SubAgentResult(
                "reviewer",
                "run",
                SubAgentResult.Status.COMPLETED,
                "review",
                Instant.now(),
                Instant.now()));
    var parent = new SubAgentCommand(mock(SubAgentRegistry.class), null, null);
    parent.configureExecution(runner, null);
    var command = new CommandLine(parent);
    command.setOut(new PrintWriter(new StringWriter()));
    assertEquals(0, command.execute("run", "reviewer", "--task", "task"));
    verify(runner).run("reviewer", "task", null);
    verifyNoMoreInteractions(runner);
  }

  /** 外部バックエンドについて、明示的なモデル完了条件を必要とすることを確認する。 */
  @Test
  void externalBackendRequiresExplicitModelCompletionConditionAndMaterials() {
    var runner = mock(SubAgentRunner.class);
    var parent = new SubAgentCommand(mock(SubAgentRegistry.class), null, null);
    parent.configureExecution(runner, null);
    var command = new CommandLine(parent);
    command.setOut(new PrintWriter(new StringWriter()));
    assertEquals(
        2, command.execute("run", "reviewer", "--backend", "chatgpt-plan", "--task", "task"));
    assertEquals(
        2,
        command.execute(
            "run",
            "reviewer",
            "--backend",
            "chatgpt-plan",
            "--model",
            "model",
            "--expected-result",
            "{}",
            "--context",
            "unscoped data",
            "--task",
            "task"));
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(runner);
  }
}
