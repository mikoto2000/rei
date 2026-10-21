package dev.mikoto2000.rei.application.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** 永続化された実行登録台帳の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class DurableRunRegistryTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 不明読み取り実行を復元することを確認する。 */
  @Test
  void independentJvmCrashThenRestartRestoresUnknownReadRun() throws Exception {
    for (String phase : java.util.List.of("crash", "recover")) {
      var output = root.resolve(phase + ".log");
      var child =
          new ProcessBuilder(
                  dev.mikoto2000.rei.testsupport.JavaFixtureCommand.command(
                      root, RestartProbe.class, root.toString(), phase))
              .redirectErrorStream(true)
              .redirectOutput(output.toFile())
              .start();
      try {
        assertThat(child.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(child.exitValue())
            .withFailMessage(java.nio.file.Files.readString(output))
            .isZero();
      } finally {
        if (child.isAlive()) child.destroyForcibly();
      }
    }
  }

  /** 検証条件を再現するための再開利用可否の確認。 */
  public static class RestartProbe {
    /** 検証用に、実行の受付と状態管理の起動要求を受け付け、実行を開始する。 */
    public static void main(String[] args) {
      Path root = Path.of(args[0]);
      var source = new SQLiteDataSource();
      source.setUrl("jdbc:sqlite:" + root.resolve("crash.db"));
      var registry = new RunRegistry(Clock.systemUTC(), source);
      if (args[1].equals("crash")) {
        registry.register(
            new AgentRunContext(
                "read",
                "session",
                root,
                "A",
                AgentRunContext.RequestSource.WEB,
                AgentRunContext.Mode.READ_ONLY));
        registry.transition("read", RunStatus.RUNNING, null);
        Runtime.getRuntime().halt(0);
      }
      var run = registry.get("read");
      if (run.status() != RunStatus.UNKNOWN
          || run.context().mode() != AgentRunContext.Mode.READ_ONLY
          || !run.context().projectRoot().equals(root)
          || !run.context().conversationId().equals("session"))
        throw new AssertionError("Lost recovery ownership");
    }
  }

  /** 再開について、所有権を復元することを確認する。 */
  @Test
  void restartRestoresOwnershipAndModeAndNeverSilentlyRestartsInflightWork() {
    var db = new SQLiteDataSource();
    db.setUrl("jdbc:sqlite:" + root.resolve("runs.db"));
    var first = new RunRegistry(Clock.systemUTC(), db);
    var owner =
        new AgentRunContext(
            "read",
            "session",
            root,
            "A",
            AgentRunContext.RequestSource.WEB,
            AgentRunContext.Mode.READ_ONLY);
    first.register(owner);
    first.transition("read", RunStatus.RUNNING, null);
    first.register(new AgentRunContext("queued", "session", root, "A"));
    first.register(new AgentRunContext("done", "session", root, "A"));
    first.transition("done", RunStatus.RUNNING, null);
    first.transition("done", RunStatus.COMPLETED, null);
    assertThat(first.restored("done")).isFalse();
    first.close();
    var reopened = new RunRegistry(Clock.systemUTC(), db);
    assertThat(reopened.get("read").context()).isEqualTo(owner);
    assertThat(reopened.get("read").status()).isEqualTo(RunStatus.UNKNOWN);
    assertThat(reopened.get("queued").status()).isEqualTo(RunStatus.UNKNOWN);
    assertThat(reopened.get("done").status()).isEqualTo(RunStatus.COMPLETED);
    assertThat(reopened.restored("done")).isTrue();
    assertThat(reopened.transition("read", RunStatus.RUNNING, null)).isFalse();
  }

  /** 実行の受付と状態管理で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void rejectedAdmissionIsForgottenDurably() {
    var db = new SQLiteDataSource();
    db.setUrl("jdbc:sqlite:" + root.resolve("runs.db"));
    var first = new RunRegistry(Clock.systemUTC(), db);
    first.register(new AgentRunContext("rejected", "session", root, "A"));
    first.forget("rejected");
    assertThatThrownBy(() -> new RunRegistry(Clock.systemUTC(), db).get("rejected"))
        .isInstanceOf(RunNotFoundException.class);
  }

  /** 実行の受付と状態管理の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void anotherLiveRegistryCannotTakeOverOrCancelItsExecutionOwner() {
    var db = new SQLiteDataSource();
    db.setUrl("jdbc:sqlite:" + root.resolve("runs.db"));
    var first = new RunRegistry(Clock.systemUTC(), db);
    first.register(new AgentRunContext("active", "session", root, "A"));
    first.transition("active", RunStatus.RUNNING, null);
    var observer = new RunRegistry(Clock.systemUTC(), db);
    assertThat(observer.get("active").status()).isEqualTo(RunStatus.RUNNING);
    assertThatThrownBy(() -> observer.transition("active", RunStatus.CANCELLED, null))
        .isInstanceOf(dev.mikoto2000.rei.application.state.OperationConflictException.class);
  }
}
