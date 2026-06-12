package dev.mikoto2000.rei.googlecalendar;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class GoogleCalendarToolsTest {

  @Test
  void createEventPrintsErrorAndRethrowsWhenServiceFails() throws Exception {
    GoogleCalendarService service = Mockito.mock(GoogleCalendarService.class);
    GoogleCalendarTools tools = new GoogleCalendarTools(service);
    when(service.parseDateTime("2026-03-23T09:00:00+09:00"))
        .thenReturn(ZonedDateTime.of(2026, 3, 23, 9, 0, 0, 0, ZoneId.of("Asia/Tokyo")));
    when(service.parseDateTime("2026-03-23T10:00:00+09:00"))
        .thenReturn(ZonedDateTime.of(2026, 3, 23, 10, 0, 0, 0, ZoneId.of("Asia/Tokyo")));
    IllegalStateException failure = new IllegalStateException("Google Calendar integration is disabled");
    when(service.createEvent(any(), any(), any(), any(), any())).thenThrow(failure);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      IllegalStateException actual = assertThrows(IllegalStateException.class,
          () -> tools.createEvent("設計レビュー", "2026-03-23T09:00:00+09:00", "2026-03-23T10:00:00+09:00", null, null));
      assertSame(failure, actual);
    } finally {
      System.setOut(originalOut);
    }

    assertTrue(out.toString().contains("[error] Google Calendar への予定追加に失敗しました: Google Calendar integration is disabled"));
  }
}