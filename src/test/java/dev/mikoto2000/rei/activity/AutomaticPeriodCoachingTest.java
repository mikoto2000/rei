package dev.mikoto2000.rei.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.calls;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.topic.AgentActivityTracker;
import dev.mikoto2000.rei.topic.AgentMessage;
import dev.mikoto2000.rei.topic.AgentMessagePublisher;
import dev.mikoto2000.rei.topic.MessageOrigin;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 自動期間助言の結果と状態の契約を検証するテスト。 */
class AutomaticPeriodCoachingTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 日時や期限の条件を再現するために使う検証時刻。 */
  final Clock clock = Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC);

  /** 検証で使用する保存先を、指定された条件に合わせて用意する。 */
  PeriodCoachingStore store() {
    return new SqlitePeriodCoachingStore(
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            "jdbc:sqlite:" + root.resolve("coaching.db")));
  }

  /** 検証で使用する設定を、指定された条件に合わせて用意する。 */
  PeriodCoaching.Settings settings() {
    return new PeriodCoaching.Settings(true, Set.of("development"), .6, 60, .001, .25, 7);
  }

  /** {@code timeline} の条件を再現するための補助情報を返す。 */
  ActivityTimeline timeline() {
    var timeline = mock(ActivityTimeline.class);
    when(timeline.periodComparison(any(), isNull()))
        .thenAnswer(
            invocation -> {
              ActivityPeriodAnalysis.Period period = invocation.getArgument(0);
              var first =
                  period == ActivityPeriodAnalysis.Period.WEEK
                      ? LocalDate.of(2026, 9, 28)
                      : LocalDate.of(2026, 9, 1);
              var previous =
                  period == ActivityPeriodAnalysis.Period.WEEK
                      ? first.minusWeeks(1)
                      : first.minusMonths(1);
              return new ActivityTimeline.PeriodComparison(
                  aggregate(period, first, 1200, 6000),
                  aggregate(period, previous, 6000, 1200),
                  ZoneOffset.UTC);
            });
    return timeline;
  }

  /** {@code aggregate} の条件を再現するための補助情報を返す。 */
  ActivityPeriodAnalysis.Aggregate aggregate(
      ActivityPeriodAnalysis.Period period, LocalDate first, long target, long other) {
    var from = first.atStartOfDay(ZoneOffset.UTC).toInstant();
    var end =
        (period == ActivityPeriodAnalysis.Period.WEEK ? first.plusWeeks(1) : first.plusMonths(1))
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant();
    return new ActivityPeriodAnalysis.Aggregate(
        new ActivityPeriodAnalysis.Range(period, first, from, end, false),
        target + other,
        Duration.between(from, end).toSeconds() - target - other,
        Map.of("development", target, "research", other),
        Map.of(),
        Map.of(),
        Map.of(),
        0);
  }

  /** {@code properties} の条件を再現するための補助情報を返す。 */
  ActivityProperties properties() {
    var p = new ActivityProperties();
    p.setEnabled(true);
    p.getCoaching().setAutomaticEnabled(true);
    p.getCoaching().setMonthlyEnabled(false);
    return p;
  }

  /** 再開の後も対象の情報が残ることを確認する。 */
  @Test
  void disabledByDefaultAndStoredOptInRequiredThenReceiptSurvivesRestartAndSharesManualBudget() {
    var p = new ActivityProperties();
    var timeline = timeline();
    var store = store();
    var service = new PeriodCoachingService(timeline, store, clock);
    var tracker = mock(AgentActivityTracker.class);
    var messages = new ArrayList<AgentMessage>();
    var job = new AutomaticPeriodCoaching(p, service, messages::add, tracker, () -> false, clock);
    job.tick();
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(timeline);
    assertTrue(messages.isEmpty());
    p.setEnabled(true);
    p.getCoaching().setAutomaticEnabled(true);
    p.getCoaching().setMonthlyEnabled(false);
    job.tick();
    verifyNoInteractions(timeline);
    assertTrue(messages.isEmpty());
    store.configure(settings());
    job.tick();
    assertEquals(1, messages.size());
    assertTrue(messages.getFirst().content().startsWith("自動Coaching"));
    assertEquals(MessageOrigin.BEHAVIOR, messages.getFirst().origin());
    assertEquals("PERIOD_COACHING_WEEK", messages.getFirst().metadata().get("triggerType"));
    job.tick();
    assertEquals(1, messages.size());
    var restarted = new PeriodCoachingService(timeline, store(), clock);
    new AutomaticPeriodCoaching(p, restarted, messages::add, tracker, () -> false, clock).tick();
    assertEquals(1, messages.size());
    assertTrue(
        restarted.evaluate(ActivityPeriodAnalysis.Period.WEEK, null).contains("ALREADY_SHOWN"));
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void monthlyAndWeeklyShareCooldownAndOnlyOneAdviceCanBePublishedPerTick() {
    var p = properties();
    p.getCoaching().setMonthlyEnabled(true);
    var store = store();
    store.configure(settings());
    var timeline = timeline();
    var messages = new ArrayList<AgentMessage>();
    var job =
        new AutomaticPeriodCoaching(
            p,
            new PeriodCoachingService(timeline, store, clock),
            messages::add,
            mock(AgentActivityTracker.class),
            () -> false,
            clock);
    job.tick();
    assertEquals(1, messages.size());
    assertEquals("PERIOD_COACHING_MONTH", messages.getFirst().metadata().get("triggerType"));
    job.tick();
    assertEquals(1, messages.size());
    assertEquals("COOLDOWN", job.status());
  }

  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void busyPausedAndChangedSettingsSuppressDeliveryWithoutConsumingAnUnrelatedAdvice() {
    var p = properties();
    var store = store();
    store.configure(settings());
    var timeline = timeline();
    var tracker = mock(AgentActivityTracker.class);
    var messages = new ArrayList<AgentMessage>();
    var paused = new java.util.concurrent.atomic.AtomicBoolean(true);
    var service = new PeriodCoachingService(timeline, store, clock);
    var job = new AutomaticPeriodCoaching(p, service, messages::add, tracker, paused::get, clock);
    job.tick();
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(timeline);
    paused.set(false);
    when(tracker.isAgentBusy()).thenReturn(true);
    job.tick();
    verifyNoInteractions(timeline);
    when(tracker.isAgentBusy()).thenReturn(false);
    when(timeline.periodComparison(any(), isNull()))
        .thenAnswer(
            i -> {
              store.setEnabled(false);
              return new ActivityTimeline.PeriodComparison(
                  aggregate(
                      ActivityPeriodAnalysis.Period.WEEK, LocalDate.of(2026, 9, 28), 1200, 6000),
                  aggregate(
                      ActivityPeriodAnalysis.Period.WEEK, LocalDate.of(2026, 9, 21), 6000, 1200),
                  ZoneOffset.UTC);
            });
    job.tick();
    assertTrue(messages.isEmpty());
    assertEquals("SETTINGS_CHANGED", job.status());
  }

  /** 作業状況の把握で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void deliveryFailureConsumesReservationAndRestartDoesNotReplay() {
    var p = properties();
    var store = store();
    store.configure(settings());
    var timeline = timeline();
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    AgentMessagePublisher publisher =
        message -> {
          calls.incrementAndGet();
          throw new IllegalStateException("delivery unknown");
        };
    var job =
        new AutomaticPeriodCoaching(
            p,
            new PeriodCoachingService(timeline, store, clock),
            publisher,
            mock(AgentActivityTracker.class),
            () -> false,
            clock);
    job.tick();
    assertEquals("FAILED", job.status());
    assertEquals(1, calls.get());
    new AutomaticPeriodCoaching(
            p,
            new PeriodCoachingService(timeline, store(), clock),
            publisher,
            mock(AgentActivityTracker.class),
            () -> false,
            clock)
        .tick();
    assertEquals(1, calls.get());
  }

  /** 作業状況の把握で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void invalidIntervalsAreRejectedAndNoPeriodNotificationConfigurationIsEnabledByDefault() {
    var p = new ActivityProperties();
    assertFalse(p.getCoaching().isAutomaticEnabled());
    p.getCoaching().setCheckIntervalSeconds(0);
    assertThrows(IllegalArgumentException.class, p::validate);
  }
}
