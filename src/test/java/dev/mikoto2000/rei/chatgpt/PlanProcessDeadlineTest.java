package dev.mikoto2000.rei.chatgpt;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class PlanProcessDeadlineTest {
  @Test void thirtyMinuteLifetimeIncludesStartupAndCleanupDoesNotResetItsDeadline() {
    var clock=new AtomicLong(100);
    var deadline=new PlanProcessDeadline(Duration.ofMinutes(30),clock::get);
    assertEquals(Duration.ofMinutes(30),deadline.remaining());
    assertEquals("1805s",deadline.wslWatchdogDuration());
    clock.addAndGet(Duration.ofSeconds(10).toNanos());
    assertEquals(Duration.ofSeconds(1790),deadline.remaining());
    assertEquals("1795s",deadline.wslWatchdogDuration());
    clock.addAndGet(Duration.ofSeconds(1790).toNanos());
    assertEquals(ChatGptException.Code.TIMEOUT,assertThrows(ChatGptException.class,deadline::remaining).code());
  }

  @Test void watchdogRoundsUpOnlyItsSubsecondCleanupWindow() {
    var deadline=new PlanProcessDeadline(Duration.ofMillis(1),()->0L);
    assertEquals(Duration.ofMillis(1),deadline.remaining());
    assertEquals("6s",deadline.wslWatchdogDuration());
  }

  @Test void invalidTimeoutNeverCreatesAnUnboundedProcess() {
    assertThrows(IllegalArgumentException.class,()->new PlanProcessDeadline(null));
    assertThrows(IllegalArgumentException.class,()->new PlanProcessDeadline(Duration.ZERO));
    assertThrows(IllegalArgumentException.class,()->new PlanProcessDeadline(Duration.ofSeconds(-1)));
    assertThrows(IllegalArgumentException.class,()->new PlanProcessDeadline(Duration.ofMinutes(30).plusNanos(1)));
  }
  @Test void probeWaitConsumesOnlyTheRemainingStartupBudget() {
    var clock=new AtomicLong();
    var deadline=new PlanProcessDeadline(Duration.ofSeconds(12),clock::get);
    assertEquals(10000,deadline.probeWaitMillis());
    clock.addAndGet(Duration.ofMillis(11500).toNanos());
    assertEquals(500,deadline.probeWaitMillis());
    clock.addAndGet(Duration.ofMillis(500).toNanos());
    assertEquals(ChatGptException.Code.TIMEOUT,assertThrows(ChatGptException.class,deadline::probeWaitMillis).code());
  }
}
