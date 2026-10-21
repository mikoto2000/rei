package dev.mikoto2000.rei.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 作業状況要約日付の結果と状態の契約を検証するテスト。 */
class ActivitySummaryDateTest {
  /** 検証に使うTOKYO。 */
  static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

  /** 日時や期限の条件を再現するために使う検証時刻。 */
  static final Instant NOW = Instant.parse("2026-09-25T03:00:00Z");

  /** 検証に使う保存先。 */
  final ActivityStore store = mock(ActivityStore.class);

  /** 検証に使う時系列の記録。 */
  final ActivityTimeline timeline = new ActivityTimeline(store, Clock.fixed(NOW, TOKYO));

  /** 検証に使う出力先、err。 */
  final StringWriter out = new StringWriter(), err = new StringWriter();

  /** 検証用に、要求された処理を実行する。 */
  int execute(String... args) {
    var command =
        new picocli.CommandLine(
            new ActivityCommand(timeline, mock(ActivityCapture.class), new ActivityProperties()));
    command.setOut(new PrintWriter(out));
    command.setErr(new PrintWriter(err));
    return command.execute(args);
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @ParameterizedTest
  @ValueSource(strings = {"", "today", "2026-09-25"})
  void todayStopsAtNow(String day) {
    assertEquals(0, day.isEmpty() ? execute("summary") : execute("summary", day));
    verify(store).findRecordsBetween(Instant.parse("2026-09-24T15:00:00Z"), NOW);
    assertTrue(out.toString().contains("2026-09-25 の Activity は記録されていません。"));
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @ParameterizedTest
  @ValueSource(strings = {"yesterday", "2026-09-24"})
  void pastDayUsesNextLocalMidnight(String day) {
    assertEquals(0, execute("summary", day));
    verify(store)
        .findRecordsBetween(
            Instant.parse("2026-09-23T15:00:00Z"), Instant.parse("2026-09-24T15:00:00Z"));
  }

  /** 作業状況の把握で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @ParameterizedTest
  @ValueSource(strings = {"2026-02-30", "2026/09/25", "foo", "2026-9-25", "+2026-09-25"})
  void invalidDateHasUsage(String day) {
    assertEquals(2, execute("summary", day));
    assertTrue(err.toString().contains("today、yesterday、または YYYY-MM-DD"));
    verifyNoInteractions(store);
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void futureDateIsExplicit() {
    assertEquals(2, execute("summary", "2026-09-26"));
    assertTrue(err.toString().contains("2026-09-26 は未来の日付です。"));
    verifyNoInteractions(store);
  }

  /** 作業状況の把握で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void extraArgumentsAreRejected() {
    assertEquals(2, execute("summary", "today", "foo"));
    verifyNoInteractions(store);
  }

  /** 詳細コマンド一覧について、全体日を維持することを確認する。 */
  @ParameterizedTest
  @ValueSource(strings = {"today", "yesterday", "2026-09-20"})
  void detailCommandsKeepFullDay(String day) {
    assertEquals(0, execute(day));
    var date =
        switch (day) {
          case "today" -> LocalDate.of(2026, 9, 25);
          case "yesterday" -> LocalDate.of(2026, 9, 24);
          default -> LocalDate.parse(day);
        };
    verify(store)
        .findRecordsBetween(
            date.atStartOfDay(TOKYO).toInstant(), date.plusDays(1).atStartOfDay(TOKYO).toInstant());
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void midnightIsAnEmptyRange() {
    var midnight =
        new ActivityTimeline(store, Clock.fixed(Instant.parse("2026-09-24T15:00:00Z"), TOKYO));
    assertTrue(midnight.trendSummary("today").contains("2026-09-25 の Activity は記録されていません。"));
    verifyNoInteractions(store);
  }
}
