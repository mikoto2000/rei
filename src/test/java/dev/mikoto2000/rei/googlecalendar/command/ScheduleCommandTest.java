package dev.mikoto2000.rei.googlecalendar.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import dev.mikoto2000.rei.googlecalendar.GoogleCalendarService;
import picocli.CommandLine;

class ScheduleCommandTest {

  @Test
  void addCommandPrintsErrorWhenEventCreationFails() throws Exception {
    GoogleCalendarService service = Mockito.mock(GoogleCalendarService.class);
    when(service.parseDateTime("2026-03-23T09:00:00+09:00"))
        .thenReturn(ZonedDateTime.of(2026, 3, 23, 9, 0, 0, 0, ZoneId.of("Asia/Tokyo")));
    when(service.parseDateTime("2026-03-23T10:00:00+09:00"))
        .thenReturn(ZonedDateTime.of(2026, 3, 23, 10, 0, 0, 0, ZoneId.of("Asia/Tokyo")));
    when(service.createEvent(any(), any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("Google Calendar integration is disabled"));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      int exitCode = newCommand(service).execute("add",
          "--start", "2026-03-23T09:00:00+09:00",
          "--end", "2026-03-23T10:00:00+09:00",
          "設計レビュー");
      assertEquals(0, exitCode);
    } finally {
      System.setOut(originalOut);
    }

    assertTrue(out.toString().contains("[error] Google Calendar への予定追加に失敗しました: Google Calendar integration is disabled"));
  }

  private CommandLine newCommand(GoogleCalendarService service) {
    return new CommandLine(new ScheduleCommand(), new CommandLine.IFactory() {
      @Override
      public <K> K create(Class<K> cls) throws Exception {
        if (cls == ScheduleCommand.AddCommand.class) {
          return cls.cast(new ScheduleCommand.AddCommand(service));
        }
        return CommandLine.defaultFactory().create(cls);
      }
    });
  }
}