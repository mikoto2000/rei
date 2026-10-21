package dev.mikoto2000.rei.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.memory.configuration.AutoSleepProperties;
import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.memory.service.AutoSleepService;
import dev.mikoto2000.rei.memory.service.SleepService;
import dev.mikoto2000.rei.topic.DefaultAgentActivityTracker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 自動待機定期実行条件の結果と状態の契約を検証するテスト。 */
class AutoSleepCronTest {
  /** 検証に使う開始。 */
  final Instant start = Instant.parse("2026-10-04T00:00:00Z");

  /** 検証に使う待機。 */
  final SleepService sleep = mock(SleepService.class);

  /** 検証に使う作業状況。 */
  final DefaultAgentActivityTracker activity =
      new DefaultAgentActivityTracker(Clock.fixed(start.minusSeconds(600), ZoneOffset.UTC));

  /** 日時や期限の条件を再現するために使う検証時刻。 */
  final MutableClock clock = new MutableClock(start);

  /** 検証に使うサービス。 */
  AutoSleepService service;

  /** 次の検証へ状態を持ち越さないよう、利用した資源と保存先を片付ける。 */
  @AfterEach
  void close() {
    if (service != null) service.close();
  }

  /** 検証ごとに状態を分離し、対象を操作できる環境を用意する。 */
  void setup(String cron, String zone) {
    service =
        new AutoSleepService(
            stubState(sleep),
            new MemoryProperties(true, 20, 80, 10, 3, 2000, 60, null),
            new AutoSleepProperties(
                true, Duration.ofMinutes(5), 2, Duration.ofMinutes(10), cron, zone),
            activity,
            clock);
    service.afterTerminal(new AgentRunContext("r", "s", java.nio.file.Path.of("."), "p"));
    when(sleep.unsleptTurns("s")).thenReturn(2L);
  }

  /** 長期記憶の活用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void cronWaitsUntilDueAndRunsOnlyOneBatchPerOccurrence() throws Exception {
    setup("0 1 * * * *", "UTC");
    service.tick();
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(sleep);
    var done = new CountDownLatch(1);
    doAnswer(
            a -> {
              done.countDown();
              return emptyReport();
            })
        .when(sleep)
        .sleep(eq("s"), eq("p"), eq(false), any(), any());
    clock.time = start.plusSeconds(60);
    service.tick();
    assertTrue(done.await(2, TimeUnit.SECONDS));
    clock.time = start.plusSeconds(1200);
    service.tick();
    verify(sleep, times(1)).sleep(anyString(), anyString(), anyBoolean(), any(), any());
  }

  /** 日次定期実行条件について、明示的な時刻タイムゾーンを利用することを確認する。 */
  @Test
  void dailyCronUsesExplicitTimeZone() {
    setup("0 0 10 * * *", "Asia/Tokyo");
    clock.time = start.plusSeconds(3599);
    service.tick();
    verifyNoInteractions(sleep);
    clock.time = start.plusSeconds(3600);
    service.tick();
    verify(sleep).unsleptTurns("s");
  }

  /** 長期記憶の活用で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void invalidCronAndZoneFailConfiguration() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new AutoSleepProperties(true, null, 2, null, "broken", "UTC"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AutoSleepProperties(true, null, 2, null, "0 0 10 * * *", "invalid"));
  }

  /** 長期記憶の活用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void busyAndRecentActivityDeferDueOccurrenceWithoutCatchupStorm() throws Exception {
    setup("0 1 * * * *", "UTC");
    clock.time = start.plusSeconds(60);
    activity.recordAgentStarted(clock.time);
    service.tick();
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(sleep);
    clock.time = start.plusSeconds(7200);
    activity.recordAgentCompleted(clock.time);
    service.tick();
    verifyNoInteractions(sleep);
    var done = new CountDownLatch(1);
    doAnswer(
            a -> {
              done.countDown();
              return emptyReport();
            })
        .when(sleep)
        .sleep(eq("s"), eq("p"), eq(false), any(), any());
    clock.time = start.plusSeconds(7500);
    service.tick();
    assertTrue(done.await(2, TimeUnit.SECONDS));
    clock.time = start.plusSeconds(8400);
    service.tick();
    verify(sleep, times(1)).unsleptTurns("s");
  }

  /** 長期記憶の活用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void bindsCronAndLegacyDefaultsFromConfiguration() {
    var source =
        new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
            java.util.Map.of(
                "rei.memory.auto-sleep.enabled",
                "true",
                "rei.memory.auto-sleep.cron",
                "0 0 10 * * *",
                "rei.memory.auto-sleep.zone",
                "Asia/Tokyo"));
    var bound =
        new org.springframework.boot.context.properties.bind.Binder(source)
            .bind(
                "rei.memory.auto-sleep",
                org.springframework.boot.context.properties.bind.Bindable.of(
                    AutoSleepProperties.class))
            .get();
    assertTrue(bound.enabled());
    assertEquals("0 0 10 * * *", bound.cron());
    assertEquals("Asia/Tokyo", bound.zone());
    assertEquals(Duration.ofMinutes(5), bound.minimumIdle());
  }

  /** 検証条件を再現するための変更可能な時刻の取得元。 */
  static class MutableClock extends Clock {
    /** 検証に使う時刻。 */
    Instant time;

    /** 検証条件に必要な値を受け取り、MutableClockを用意する。 */
    MutableClock(Instant time) {
      this.time = time;
    }

    /** 検証用に、長期記憶の活用で参照するタイムゾーンを返す。 */
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    /** 検証用に、指定されたタイムゾーンを反映した情報を作成する。 */
    public Clock withZone(ZoneId zone) {
      return this;
    }

    /** {@code instant} の条件を再現するための補助情報を返す。 */
    public Instant instant() {
      return time;
    }
  }

  /** {@code stubState} の条件を再現するための補助情報を返す。 */
  static SleepService stubState(SleepService sleep) {
    when(sleep.autoState(anyString(), anyString()))
        .thenAnswer(
            a ->
                new dev.mikoto2000.rei.memory.service.MemoryRepository.AutoState(
                    a.getArgument(0), a.getArgument(1), 0, 0, null, null, null, null, 0));
    return sleep;
  }

  /** {@code emptyReport} の条件を再現するための補助情報を返す。 */
  static SleepService.Report emptyReport() {
    var report = mock(SleepService.Report.class);
    when(report.run()).thenReturn(mock(dev.mikoto2000.rei.memory.service.SleepRun.class));
    return report;
  }
}
