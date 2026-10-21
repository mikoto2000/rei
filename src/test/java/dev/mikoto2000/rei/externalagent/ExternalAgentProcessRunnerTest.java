package dev.mikoto2000.rei.externalagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 外部エージェントプロセス実行の担当の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("e2e")
class ExternalAgentProcessRunnerTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** {@code command} の条件を再現するための補助情報を返す。 */
  List<String> command(String mode) {
    return dev.mikoto2000.rei.testsupport.JavaFixtureCommand.command(root, Fixture.class, mode);
  }

  /** 検証用に、外部エージェントへの委譲の操作を実行する。 */
  ExternalAgentProcessRunner.Output run(
      String mode, Duration total, Duration idle, int limit, AtomicBoolean cancel) {
    return new ExternalAgentProcessRunner()
        .run(command(mode), root, "hello", total, idle, limit, cancel::get);
  }

  /** 外部エージェントへの委譲で、状態の変化と結果を、通知・表示の契約に従って伝えることを確認する。 */
  @Test
  void capturesBothStreamsAndNonZeroExit() {
    var output =
        run("exit", Duration.ofSeconds(10), Duration.ofSeconds(5), 1024, new AtomicBoolean());
    assertEquals(ExternalAgentResult.Status.FAILED, output.status());
    assertEquals(7, output.exitCode());
    assertTrue(output.stdout().contains("hello"));
    assertTrue(output.stderr().contains("diagnostic"));
  }

  /** 外部エージェントへの委譲で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void preservesLiteralQuotesSpacesAndBackslashesInNativeArguments() {
    List<String> arguments =
        List.of(
            "permissions.rei_review={filesystem={\":minimal\"=\"read\"}}",
            "projects.\"C:/project with spaces\".trust_level=\"untrusted\"",
            "quote=\"x\\\"y\"",
            "\"trailing\\");
    List<String> invocation = new ArrayList<>(command("args"));
    invocation.addAll(arguments);
    var output =
        new ExternalAgentProcessRunner()
            .run(
                invocation,
                root,
                "",
                Duration.ofSeconds(10),
                Duration.ofSeconds(5),
                4096,
                () -> false);
    assertEquals(ExternalAgentResult.Status.SUCCESS, output.status());
    assertEquals(
        arguments.stream()
            .map(
                s ->
                    Base64.getEncoder()
                        .encodeToString(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .toList(),
        output.stdout().lines().toList());
  }

  /** 外部エージェントへの委譲で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void drainsLargeStreamsWithoutGrowingMemory() {
    var output =
        run("large", Duration.ofSeconds(15), Duration.ofSeconds(5), 4096, new AtomicBoolean());
    assertEquals(0, output.exitCode());
    assertTrue(output.truncated());
    assertTrue(output.stdout().length() + output.stderr().length() <= 4096);
  }

  /** 外部エージェントへの委譲で、時刻や期限に応じて、待機・実行・保存の扱いを切り替えることを確認する。 */
  @Test
  void distinguishesTimeouts() {
    assertEquals(
        ExternalAgentResult.Status.TOTAL_TIMEOUT,
        run("sleep", Duration.ofMillis(400), Duration.ofSeconds(10), 1024, new AtomicBoolean())
            .status());
    assertEquals(
        ExternalAgentResult.Status.INACTIVITY_TIMEOUT,
        run("sleep", Duration.ofSeconds(10), Duration.ofMillis(400), 1024, new AtomicBoolean())
            .status());
  }

  /** 外部エージェントへの委譲の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationCleansUpChildProcesses() throws Exception {
    AtomicBoolean cancel = new AtomicBoolean();
    try (var pool = Executors.newSingleThreadExecutor()) {
      var future =
          pool.submit(
              () -> run("child", Duration.ofSeconds(15), Duration.ofSeconds(10), 4096, cancel));
      Path pid = root.resolve("child.pid");
      long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
      while (!Files.exists(pid) && System.nanoTime() < deadline) Thread.sleep(20);
      assertTrue(Files.exists(pid));
      long child = Long.parseLong(Files.readString(pid));
      cancel.set(true);
      assertEquals(ExternalAgentResult.Status.CANCELLED, future.get(5, TimeUnit.SECONDS).status());
      assertFalse(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
    }
  }

  /** 外部エージェントへの委譲で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void missingCommandIsFriendlyFailure() {
    var result =
        new ExternalAgentProcessRunner()
            .run(
                List.of(root.resolve("missing-command").toString()),
                root,
                "",
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                1024,
                () -> false);
    assertEquals(ExternalAgentResult.Status.UNAVAILABLE, result.status());
    assertNull(result.exitCode());
    assertTrue(result.stderr().contains("missing-command"));
  }

  /** 外部エージェントへの委譲で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void normalExitAndPeriodicActivity() {
    var result =
        run("activity", Duration.ofSeconds(8), Duration.ofSeconds(2), 4096, new AtomicBoolean());
    assertEquals(ExternalAgentResult.Status.SUCCESS, result.status());
    assertTrue(result.stdout().contains("tick"));
    assertFalse(result.truncated());
  }

  /** 外部エージェントへの委譲の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void preCancelledDoesNotStartAndInvalidWorkingDirectoryFails() {
    assertEquals(
        ExternalAgentResult.Status.CANCELLED,
        run("child", Duration.ofSeconds(5), Duration.ofSeconds(5), 4096, new AtomicBoolean(true))
            .status());
    assertFalse(Files.exists(root.resolve("child.pid")));
    assertEquals(
        ExternalAgentResult.Status.UNAVAILABLE,
        new ExternalAgentProcessRunner()
            .run(
                command("exit"),
                root.resolve("missing"),
                "",
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                1024,
                () -> false)
            .status());
  }

  /** 検証条件を再現するための検証条件。 */
  public static class Fixture {
    /** 検証用に、外部エージェントへの委譲の起動要求を受け付け、実行を開始する。 */
    public static void main(String[] args) throws Exception {
      switch (args[0]) {
        case "args" -> {
          for (int i = 1; i < args.length; i++)
            System.out.println(
                Base64.getEncoder()
                    .encodeToString(args[i].getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        case "activity" -> {
          for (int i = 0; i < 8; i++) {
            System.out.println("tick");
            Thread.sleep(250);
          }
        }
        case "exit" -> {
          System.out.println(new String(System.in.readAllBytes()));
          System.err.println("diagnostic");
          System.exit(7);
        }
        case "large" -> {
          Thread err =
              Thread.ofPlatform()
                  .start(
                      () -> {
                        for (int i = 0; i < 10000; i++) System.err.println("e".repeat(1000));
                      });
          for (int i = 0; i < 10000; i++) System.out.println("o".repeat(1000));
          err.join();
        }
        case "child" -> {
          Process child =
              new ProcessBuilder(
                      dev.mikoto2000.rei.testsupport.JavaFixtureCommand.command(
                          Path.of("."), Fixture.class, "sleep"))
                  .start();
          // Publish only complete content; observing file creation alone otherwise races the write.
          Files.writeString(Path.of("child.pid.tmp"), Long.toString(child.pid()));
          Files.move(
              Path.of("child.pid.tmp"), Path.of("child.pid"), StandardCopyOption.ATOMIC_MOVE);
          Thread.sleep(60000);
        }
        default -> Thread.sleep(60000);
      }
    }
  }
}
