package dev.mikoto2000.rei.googlecalendar;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

/** Google予定表サービスの結果と状態の契約を検証するテスト。 */
class GoogleCalendarServiceTest {

  /** 入力の解釈日付時刻について、設定済み時刻タイムゾーンを利用することを確認する。 */
  @Test
  void parseDateTimeUsesConfiguredTimeZoneForLocalDateTime() {
    GoogleCalendarService service =
        new GoogleCalendarService(
            new GoogleCalendarProperties(
                "Rei",
                "",
                "",
                new GoogleCalendarProperties.CalendarProperties(false, "primary", "Asia/Tokyo"),
                new GoogleCalendarProperties.TaskProperties(true)));

    ZonedDateTime actual = service.parseDateTime("2026-03-23T09:00:00");

    assertEquals(ZonedDateTime.of(2026, 3, 23, 9, 0, 0, 0, ZoneId.of("Asia/Tokyo")), actual);
  }

  /** 入力の解釈日付時刻について、明示的な開始位置を保持することを確認する。 */
  @Test
  void parseDateTimePreservesExplicitOffset() {
    GoogleCalendarService service =
        new GoogleCalendarService(
            new GoogleCalendarProperties(
                "Rei",
                "",
                "",
                new GoogleCalendarProperties.CalendarProperties(false, "primary", "Asia/Tokyo"),
                new GoogleCalendarProperties.TaskProperties(true)));

    ZonedDateTime actual = service.parseDateTime("2026-03-23T09:00:00+09:00");

    assertEquals(ZonedDateTime.parse("2026-03-23T09:00:00+09:00"), actual);
  }
}
