package dev.mikoto2000.rei.googlecalendar;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

class GoogleCalendarServiceTest {

  @Test
  void parseDateTimeUsesConfiguredTimeZoneForLocalDateTime() {
    GoogleCalendarService service = new GoogleCalendarService(
        new GoogleCalendarProperties(false, "Rei", "", "", "primary", "Asia/Tokyo"));

    ZonedDateTime actual = service.parseDateTime("2026-03-23T09:00:00");

    assertEquals(ZonedDateTime.of(2026, 3, 23, 9, 0, 0, 0, ZoneId.of("Asia/Tokyo")), actual);
  }

  @Test
  void parseDateTimePreservesExplicitOffset() {
    GoogleCalendarService service = new GoogleCalendarService(
        new GoogleCalendarProperties(false, "Rei", "", "", "primary", "Asia/Tokyo"));

    ZonedDateTime actual = service.parseDateTime("2026-03-23T09:00:00+09:00");

    assertEquals(ZonedDateTime.parse("2026-03-23T09:00:00+09:00"), actual);
  }
}
