package dev.mikoto2000.rei.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.application.run.RunFailure;
import dev.mikoto2000.rei.application.run.RunNotFoundException;
import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunStatus;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 実行登録台帳の結果と状態の契約を検証するテスト。 */
class RunRegistryTest {
  /** 検証条件を再現するための変更可能な時刻の取得元。 */
  static class MutableClock extends Clock {
    /** 日時や期限の条件を再現するために使う検証時刻。 */
    Instant now = Instant.parse("2026-09-15T00:00:00Z");

    /** 検証用に、web APIによる操作で参照するタイムゾーンを返す。 */
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    /** 検証用に、指定されたタイムゾーンを反映した情報を作成する。 */
    public Clock withZone(ZoneId zone) {
      return this;
    }

    /** {@code instant} の条件を再現するための補助情報を返す。 */
    public Instant instant() {
      return now;
    }
  }

  /** 検証で使用する文脈を、指定された条件に合わせて用意する。 */
  static AgentRunContext context(String id) {
    return new AgentRunContext(id, "session", Path.of("."), "project");
  }

  /** Web APIによる操作で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void lifecycleHasImmutableTerminalStateAndMetadata() {
    var clock = new MutableClock();
    var registry = new RunRegistry(clock);
    registry.register(context("run"));
    assertThat(registry.get("run").status()).isEqualTo(RunStatus.QUEUED);
    assertThat(registry.get("run").startedAt()).isNull();
    assertThat(registry.transition("run", RunStatus.COMPLETED, null)).isFalse();
    assertThat(registry.transition("run", RunStatus.RUNNING, null)).isTrue();
    assertThat(registry.get("run").startedAt()).isEqualTo(clock.now);
    assertThat(registry.get("run").completedAt()).isNull();
    clock.now = clock.now.plusSeconds(1);
    assertThat(registry.transition("run", RunStatus.FAILED, new RunFailure("failure", "message")))
        .isTrue();
    assertThat(registry.get("run").failure().type()).isEqualTo("failure");
    assertThat(registry.get("run").completedAt()).isEqualTo(clock.now);
    for (var status : RunStatus.values())
      assertThat(registry.transition("run", status, null)).isFalse();
    assertThatThrownBy(() -> registry.get("unknown")).isInstanceOf(RunNotFoundException.class);
  }

  /** Web APIによる操作の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancelAndCompleteRaceHasExactlyOneWinner() throws Exception {
    var registry = new RunRegistry(Clock.systemUTC());
    registry.register(context("run"));
    registry.transition("run", RunStatus.RUNNING, null);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var cancel =
          executor.submit(
              () -> {
                start.await();
                return registry.transition("run", RunStatus.CANCELLED, null);
              });
      var complete =
          executor.submit(
              () -> {
                start.await();
                return registry.transition("run", RunStatus.COMPLETED, null);
              });
      start.countDown();
      assertThat(cancel.get(5, TimeUnit.SECONDS) ^ complete.get(5, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(registry.get("run").status().isTerminal()).isTrue();
  }

  /** Web APIによる操作で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void retentionStartsAtTerminalAndDoesNotExtendOnReads() {
    var clock = new MutableClock();
    var registry = new RunRegistry(clock);
    registry.register(context("queued"));
    registry.register(context("done"));
    registry.transition("done", RunStatus.CANCELLED, null);
    clock.now = clock.now.plusSeconds(1799);
    assertThat(registry.purgeExpired()).isEmpty();
    assertThat(registry.get("done").status()).isEqualTo(RunStatus.CANCELLED);
    clock.now = clock.now.plusSeconds(1);
    assertThat(registry.purgeExpired()).containsExactly("done");
    assertThatThrownBy(() -> registry.get("done")).isInstanceOf(RunNotFoundException.class);
    assertThat(registry.get("queued").status()).isEqualTo(RunStatus.QUEUED);
  }
}
