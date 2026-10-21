package dev.mikoto2000.rei.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 期間助言の結果と状態の契約を検証するテスト。 */
class PeriodCoachingTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path directory;

  /** 日時や期限の条件を再現するために使う検証時刻。 */
  final Clock clock = Clock.fixed(Instant.parse("2026-10-04T12:00:00Z"), ZoneOffset.UTC);

  /** 検証で使用する設定を、指定された条件に合わせて用意する。 */
  PeriodCoaching.Settings settings() {
    return new PeriodCoaching.Settings(true, Set.of("development"), .6, 60, .001, .25, 7);
  }

  /** {@code aggregate} の条件を再現するための補助情報を返す。 */
  ActivityPeriodAnalysis.Aggregate aggregate(
      LocalDate first, long target, long other, boolean partial) {
    var start = first.atStartOfDay(ZoneOffset.UTC).toInstant();
    long observed = target + other;
    var range =
        new ActivityPeriodAnalysis.Range(
            ActivityPeriodAnalysis.Period.WEEK, first, start, start.plusSeconds(604800), partial);
    return new ActivityPeriodAnalysis.Aggregate(
        range,
        observed,
        604800 - observed,
        Map.of("development", target, "research", other),
        Map.of(),
        Map.of(),
        Map.of(),
        0);
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void userCriteriaAndPriorShareChooseConservativeAdvice() {
    var result =
        new PeriodCoaching()
            .evaluate(
                settings(),
                aggregate(LocalDate.of(2026, 9, 21), 1200, 6000, false),
                aggregate(LocalDate.of(2026, 9, 14), 6000, 1200, false));
    assertEquals("BELOW_TARGET_DECLINING", result.reason());
    assertTrue(result.advice());
    assertTrue(result.message().contains("推定"));
    assertFalse(result.message().contains("生産性が低下"));
    assertFalse(
        new PeriodCoaching()
            .evaluate(
                settings(),
                aggregate(LocalDate.of(2026, 9, 21), 6000, 1200, false),
                aggregate(LocalDate.of(2026, 9, 14), 6000, 1200, false))
            .advice());
  }

  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void disabledPartialAndInsufficientEvidenceNeverAdvise() {
    var policy = new PeriodCoaching();
    var good = aggregate(LocalDate.of(2026, 9, 14), 6000, 1200, false);
    assertEquals(
        "DISABLED", policy.evaluate(PeriodCoaching.Settings.defaults(), good, good).reason());
    assertEquals(
        "PARTIAL_PERIOD",
        policy
            .evaluate(settings(), aggregate(LocalDate.of(2026, 9, 21), 1200, 6000, true), good)
            .reason());
    assertEquals(
        "INSUFFICIENT_OBSERVATION",
        policy
            .evaluate(settings(), aggregate(LocalDate.of(2026, 9, 21), 0, 60, false), good)
            .reason());
    var unknown =
        new ActivityPeriodAnalysis.Aggregate(
            good.range(), 7200, 597600, Map.of("unknown", 7200L), Map.of(), Map.of(), Map.of(), 0);
    assertEquals("UNCERTAIN_CLASSIFICATION", policy.evaluate(settings(), unknown, good).reason());
  }

  /** 作業状況の把握で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void invalidCriteriaAreRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new PeriodCoaching.Settings(true, Set.of("unknown"), .6, 60, .1, .25, 7));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PeriodCoaching.Settings(true, Set.of("development"), Double.NaN, 60, .1, .25, 7));
  }

  /** 作業状況の把握で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void reservationsSurviveRestartRespectCooldownAndStaleSettings() {
    var source = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("coaching.db"));
    var store = new SqlitePeriodCoachingStore(source);
    assertFalse(store.load().settings().enabled());
    var configured = store.configure(settings());
    assertEquals(
        "RESERVED",
        store.reserve(configured, "week:2026-09-21:UTC", "BELOW_TARGET", clock.instant()));
    var restarted = new SqlitePeriodCoachingStore(source);
    assertEquals(configured, restarted.load());
    assertEquals(
        "ALREADY_SHOWN",
        restarted.reserve(
            configured,
            "week:2026-09-21:UTC",
            "BELOW_TARGET",
            clock.instant().plusSeconds(604800)));
    assertEquals(
        "COOLDOWN",
        restarted.reserve(
            configured, "month:2026-09-01:UTC", "BELOW_TARGET", clock.instant().plusSeconds(60)));
    assertEquals(
        "RESERVED",
        restarted.reserve(
            configured,
            "week:2026-09-28:UTC",
            "BELOW_TARGET",
            clock.instant().plusSeconds(604800)));
    restarted.setEnabled(false);
    assertEquals(
        "SETTINGS_CHANGED",
        store.reserve(
            configured,
            "week:2026-10-05:UTC",
            "BELOW_TARGET",
            clock.instant().plusSeconds(1209600)));
    assertEquals(
        "DISABLED",
        store.reserve(
            store.load(),
            "week:2026-10-05:UTC",
            "BELOW_TARGET",
            clock.instant().plusSeconds(1209600)));
  }

  /** 作業状況の把握で、競合する要求でも、実行順序と作業の分離を守ることを確認する。 */
  @Test
  void twoRepositoryInstancesCannotReserveTwoConcurrentAdvices() throws Exception {
    var source = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("concurrent.db"));
    var a = new SqlitePeriodCoachingStore(source);
    var b = new SqlitePeriodCoachingStore(source);
    var configured = a.configure(settings());
    b.load();
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var latch = new java.util.concurrent.CountDownLatch(1);
      var first =
          executor.submit(
              () -> {
                latch.await();
                return a.reserve(
                    configured, "week:2026-09-21:UTC", "BELOW_TARGET", clock.instant());
              });
      var second =
          executor.submit(
              () -> {
                latch.await();
                return b.reserve(
                    configured, "month:2026-09-01:UTC", "BELOW_TARGET", clock.instant());
              });
      latch.countDown();
      assertEquals(Set.of("RESERVED", "COOLDOWN"), Set.of(first.get(), second.get()));
    }
  }

  /** シェル機能の構成検証済み永続化済みおよびについて、明示的な有効化を必要とすることを確認する。 */
  @Test
  void shellConfigurationIsValidatedPersistedAndRequiresExplicitEnable() {
    var activity = org.mockito.Mockito.mock(ActivityStore.class);
    var repository =
        new SqlitePeriodCoachingStore(
            new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("shell.db")));
    var command = new ActivityCommand(new ActivityTimeline(activity, clock), null, null);
    command.periodCoaching(
        new PeriodCoachingService(new ActivityTimeline(activity, clock), repository, clock));
    var output = new java.io.StringWriter();
    var shell = new picocli.CommandLine(command);
    shell.setOut(new java.io.PrintWriter(output));
    shell.setErr(new java.io.PrintWriter(output));
    assertEquals(0, shell.execute("coaching", "weekly"));
    org.mockito.Mockito.verifyNoInteractions(activity);
    assertEquals(2, shell.execute("coaching", "configure", "--categories", "unknown"));
    assertEquals(0, repository.load().revision());
    assertEquals(
        0,
        shell.execute(
            "coaching", "configure", "--categories", "development", "--target-share", "0.8"));
    assertEquals(.8, repository.load().settings().targetShare());
    assertFalse(repository.load().settings().enabled());
    assertEquals(0, shell.execute("coaching", "on"));
    assertTrue(repository.load().settings().enabled());
    assertEquals(0, shell.execute("coaching", "weekly"));
    org.mockito.Mockito.verify(activity)
        .findRecordsBetween(
            Instant.parse("2026-09-21T00:00:00Z"), Instant.parse("2026-09-28T00:00:00Z"));
    org.mockito.Mockito.verify(activity)
        .findRecordsBetween(
            Instant.parse("2026-09-14T00:00:00Z"), Instant.parse("2026-09-21T00:00:00Z"));
    org.mockito.Mockito.verifyNoMoreInteractions(activity);
    assertTrue(output.toString().contains("INSUFFICIENT_OBSERVATION"));
    assertEquals(2, shell.execute("coaching", "status", "--target-share", "0.2"));
    assertEquals(0, shell.execute("coaching", "off"));
    assertFalse(repository.load().settings().enabled());
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void realSavedObservationsFeedAdviceAndSecondCallIsSuppressed() {
    var activity = org.mockito.Mockito.mock(ActivityStore.class);
    var base = ActivitySemanticTest.dev(0, "Terminal", "X");
    var previous =
        new ActivityRecord(
            "prior",
            Instant.parse("2026-09-14T09:00:00Z"),
            7200,
            List.of(),
            base.foreground(),
            base.inference(),
            .8,
            List.of(),
            0,
            false,
            "prior");
    var other =
        ActivitySemanticTest.record(
            0,
            "Firefox",
            "GitHub",
            ActivitySemanticTest.activity("research", "Firefox", "GitHub", "rei"));
    var current =
        new ActivityRecord(
            "current",
            Instant.parse("2026-09-21T09:00:00Z"),
            7200,
            List.of(),
            other.foreground(),
            other.inference(),
            .8,
            List.of(),
            0,
            false,
            "current");
    org.mockito.Mockito.when(
            activity.findRecordsBetween(
                Instant.parse("2026-09-21T00:00:00Z"), Instant.parse("2026-09-28T00:00:00Z")))
        .thenReturn(List.of(current));
    org.mockito.Mockito.when(
            activity.findRecordsBetween(
                Instant.parse("2026-09-14T00:00:00Z"), Instant.parse("2026-09-21T00:00:00Z")))
        .thenReturn(List.of(previous));
    var repository =
        new SqlitePeriodCoachingStore(
            new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("integrated.db")));
    repository.configure(settings());
    var service =
        new PeriodCoachingService(new ActivityTimeline(activity, clock), repository, clock);
    assertTrue(service.evaluate(ActivityPeriodAnalysis.Period.WEEK, null).contains("指定基準"));
    assertTrue(
        service.evaluate(ActivityPeriodAnalysis.Period.WEEK, null).contains("ALREADY_SHOWN"));
    org.mockito.Mockito.verify(activity, org.mockito.Mockito.never())
        .append(org.mockito.Mockito.any());
    org.mockito.Mockito.verify(activity, org.mockito.Mockito.never())
        .replace(org.mockito.Mockito.any());
  }
}
