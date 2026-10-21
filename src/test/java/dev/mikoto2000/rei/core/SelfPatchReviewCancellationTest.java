package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Self変更差分レビューキャンセルの結果と状態の契約を検証するテスト。 */
class SelfPatchReviewCancellationTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 共通の実行基盤の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void parentCancellationStopsAnActiveTestWithoutWaitingForItsTimeout() throws Exception {
    for (var args :
        List.of(List.of("init", "--quiet"), List.of("config", "core.autocrlf", "false"))) git(args);
    Files.writeString(root.resolve("A.txt"), "before\n");
    git(List.of("add", "A.txt"));
    git(List.of("commit", "--quiet", "--no-gpg-sign", "-m", "fixture"));
    Files.writeString(root.resolve("A.txt"), "after\n");
    var cancelled = new AtomicBoolean();
    var service =
        new SelfPatchReviewService(
            new dev.mikoto2000.rei.core.service.SystemShellService(), cancelled::get);
    String command =
        System.getProperty("os.name").startsWith("Windows")
            ? "Start-Sleep -Seconds 20; exit 0"
            : "sleep 20";
    var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      var started = new java.util.concurrent.CountDownLatch(1);
      var result =
          executor.submit(
              () -> {
                started.countDown();
                return service.verify(root, new SelfPatchReviewService.Request(command, 30));
              });
      assertTrue(started.await(1, java.util.concurrent.TimeUnit.SECONDS));
      Thread.sleep(750);
      cancelled.set(true);
      var failure =
          assertThrows(
              java.util.concurrent.ExecutionException.class,
              () -> result.get(5, java.util.concurrent.TimeUnit.SECONDS));
      assertInstanceOf(java.util.concurrent.CancellationException.class, failure.getCause());
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
    }
  }

  /** {@code git} の条件を再現するため、検証用の操作や状態を扱う。 */
  void git(List<String> args) throws Exception {
    var cmd =
        new ArrayList<String>(
            List.of(
                "git",
                "-c",
                "core.hooksPath=",
                "-c",
                "user.name=Fixture",
                "-c",
                "user.email=fixture@example.invalid"));
    cmd.addAll(args);
    var p = new ProcessBuilder(cmd).directory(root.toFile()).redirectErrorStream(true).start();
    assertTrue(p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
    assertEquals(0, p.exitValue(), new String(p.getInputStream().readAllBytes()));
  }
}
