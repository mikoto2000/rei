package dev.mikoto2000.rei.chatgpt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 計画プロセス期限の結果と状態の契約を検証するテスト。 */
class PlanProcessDeadlineTest {
  /** ChatGPTとの連携で、時刻や期限に応じて、待機・実行・保存の扱いを切り替えることを確認する。 */
  @Test
  void thirtyMinuteLifetimeIncludesStartupAndCleanupDoesNotResetItsDeadline() {
    var clock = new AtomicLong(100);
    var deadline = new PlanProcessDeadline(Duration.ofMinutes(30), clock::get);
    assertEquals(Duration.ofMinutes(30), deadline.remaining());
    assertEquals("1805s", deadline.wslWatchdogDuration());
    clock.addAndGet(Duration.ofSeconds(10).toNanos());
    assertEquals(Duration.ofSeconds(1790), deadline.remaining());
    assertEquals("1795s", deadline.wslWatchdogDuration());
    clock.addAndGet(Duration.ofSeconds(1790).toNanos());
    assertEquals(
        ChatGptException.Code.TIMEOUT,
        assertThrows(ChatGptException.class, deadline::remaining).code());
  }

  /** ChatGPTとの連携で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void watchdogRoundsUpOnlyItsSubsecondCleanupWindow() {
    var deadline = new PlanProcessDeadline(Duration.ofMillis(1), () -> 0L);
    assertEquals(Duration.ofMillis(1), deadline.remaining());
    assertEquals("6s", deadline.wslWatchdogDuration());
  }

  /** ChatGPTとの連携で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void invalidTimeoutNeverCreatesAnUnboundedProcess() {
    assertThrows(IllegalArgumentException.class, () -> new PlanProcessDeadline(null));
    assertThrows(IllegalArgumentException.class, () -> new PlanProcessDeadline(Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class, () -> new PlanProcessDeadline(Duration.ofSeconds(-1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PlanProcessDeadline(Duration.ofMinutes(30).plusNanos(1)));
  }

  /** ChatGPTとの連携で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void probeWaitConsumesOnlyTheRemainingStartupBudget() {
    var clock = new AtomicLong();
    var deadline = new PlanProcessDeadline(Duration.ofSeconds(12), clock::get);
    assertEquals(10000, deadline.probeWaitMillis());
    clock.addAndGet(Duration.ofMillis(11500).toNanos());
    assertEquals(500, deadline.probeWaitMillis());
    clock.addAndGet(Duration.ofMillis(500).toNanos());
    assertEquals(
        ChatGptException.Code.TIMEOUT,
        assertThrows(ChatGptException.class, deadline::probeWaitMillis).code());
  }
}
