package dev.mikoto2000.rei.slack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Slack受付から終了までの管理の結果と状態の契約を検証するテスト。 */
class SlackLifecycleTest {
  /** Slackとの連携で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void enabledSlackConnectsInShellMode() {
    var p = new SlackProperties();
    p.setEnabled(true);
    var count = new java.util.concurrent.atomic.AtomicInteger();
    try (var lifecycle =
        new SlackSocketLifecycle(
            p,
            Map.of("SLACK_BOT_TOKEN", "dummy", "SLACK_APP_TOKEN", "dummy"),
            () ->
                new SlackSocketLifecycle.Connection() {
                  /** {@code pump} の条件を再現するため、検証用の操作や状態を扱う。 */
                  public void pump() {}

                  /** 検証で利用した対象の終了を扱う。 */
                  public void close() {
                    count.incrementAndGet();
                  }
                })) {
      assertTrue(lifecycle.connectOnce());
      assertTrue(lifecycle.connectOnce());
    }
    assertEquals(1, count.get());
  }

  /** Slackとの連携で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void disabledConfigurationNeverConnects() {
    var p = new SlackProperties();
    p.setEnabled(true);
    var factory =
        (java.util.function.Supplier<SlackSocketLifecycle.Connection>)
            () -> {
              fail("Must not connect");
              return null;
            };
    p.setEnabled(false);
    try (var lifecycle =
        new SlackSocketLifecycle(
            p, Map.of("SLACK_BOT_TOKEN", "dummy", "SLACK_APP_TOKEN", "dummy"), factory)) {
      assertFalse(lifecycle.connectOnce());
    }
  }

  /** Slackとの連携の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void missingTokensOrFailedConnectionNeverAbortBackend() {
    var p = new SlackProperties();
    p.setEnabled(true);
    try (var lifecycle =
        new SlackSocketLifecycle(
            p,
            Map.of(),
            () -> {
              fail("No tokens");
              return null;
            })) {
      assertFalse(lifecycle.connectOnce());
    }
    try (var lifecycle =
        new SlackSocketLifecycle(
            p,
            Map.of("SLACK_BOT_TOKEN", "dummy", "SLACK_APP_TOKEN", "dummy"),
            () -> {
              throw new IllegalStateException("secret must not be logged");
            })) {
      assertFalse(lifecycle.connectOnce());
    }
  }

  /** Slackとの連携で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void shutdownClosesConnectionOnce() {
    var p = new SlackProperties();
    p.setEnabled(true);
    var count = new java.util.concurrent.atomic.AtomicInteger();
    var lifecycle =
        new SlackSocketLifecycle(
            p,
            Map.of("SLACK_BOT_TOKEN", "dummy", "SLACK_APP_TOKEN", "dummy"),
            () ->
                new SlackSocketLifecycle.Connection() {
                  /** {@code pump} の条件を再現するため、検証用の操作や状態を扱う。 */
                  public void pump() {}

                  /** 検証で利用した対象の終了を扱う。 */
                  public void close() {
                    count.incrementAndGet();
                  }
                });
    assertTrue(lifecycle.connectOnce());
    assertTrue(lifecycle.connectOnce());
    lifecycle.close();
    lifecycle.close();
    assertEquals(1, count.get());
  }

  /** Slackとの連携で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void backendStartupFlagIsExplicit() {
    assertTrue(
        dev.mikoto2000.rei.StartupOptions.parse(
                new String[] {"--backend"}, java.nio.file.Path.of("."))
            .backend());
    assertTrue(
        dev.mikoto2000.rei.StartupOptions.parse(
                new String[] {"--mode=server"}, java.nio.file.Path.of("."))
            .backend());
    assertTrue(
        dev.mikoto2000.rei.StartupOptions.parse(
                new String[] {"--mode", "server"}, java.nio.file.Path.of("."))
            .backend());
    assertFalse(
        dev.mikoto2000.rei.StartupOptions.parse(new String[] {}, java.nio.file.Path.of("."))
            .backend());
    assertFalse(
        dev.mikoto2000.rei.StartupOptions.parse(
                new String[] {"--mode=legacy-shell"}, java.nio.file.Path.of("."))
            .backend());
  }

  /** なし待機を停止することを確認する。 */
  @Test
  void stopDoesNotWaitForSlowInitializationAndClosesLateConnection() throws Exception {
    var p = new SlackProperties();
    p.setEnabled(true);
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var closed = new java.util.concurrent.atomic.AtomicInteger();
    var lifecycle =
        new SlackSocketLifecycle(
            p,
            Map.of("SLACK_BOT_TOKEN", "dummy", "SLACK_APP_TOKEN", "dummy"),
            () -> {
              entered.countDown();
              try {
                release.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return new SlackSocketLifecycle.Connection() {
                /** {@code pump} の条件を再現するため、検証用の操作や状態を扱う。 */
                public void pump() {}

                /** 検証で利用した対象の終了を扱う。 */
                public void close() {
                  closed.incrementAndGet();
                }
              };
            });
    var connecting = java.util.concurrent.CompletableFuture.supplyAsync(lifecycle::connectOnce);
    assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
    try {
      assertTimeoutPreemptively(java.time.Duration.ofMillis(300), lifecycle::close);
    } finally {
      release.countDown();
    }
    connecting.get(2, java.util.concurrent.TimeUnit.SECONDS);
    assertEquals(1, closed.get());
  }
}
