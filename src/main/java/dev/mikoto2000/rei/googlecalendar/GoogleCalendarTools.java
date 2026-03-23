package dev.mikoto2000.rei.googlecalendar;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class GoogleCalendarTools {

  private final GoogleCalendarService googleCalendarService;

  @Tool(name = "googleCalendarListEvents", description = "Google Calendar の予定を指定期間で一覧します。日時は ISO-8601 形式で指定します。")
  List<GoogleCalendarEventSummary> listEvents(String from, String to) throws Exception {
    IO.println(String.format("Google Calendar の予定を %s から %s で一覧するよ", from, to));
    return googleCalendarService.listEvents(Instant.parse(from), Instant.parse(to));
  }

  @Tool(name = "googleCalendarListEventsForDate", description = "Google Calendar の予定を指定日で一覧します。日付は yyyy-MM-dd 形式で指定します。")
  List<GoogleCalendarEventSummary> listEventsForDate(String date) throws Exception {
    IO.println(String.format("Google Calendar の予定を %s で一覧するよ", date));
    return googleCalendarService.listEventsForDate(LocalDate.parse(date));
  }

  @Tool(name = "googleCalendarCreateEvent", description = "Google Calendar に予定を作成します。日時は ISO-8601 形式で指定します。")
  GoogleCalendarEventSummary createEvent(String summary, String start, String end, String location, String description) throws Exception {
    IO.println(String.format("Google Calendar に予定 %s を作成するよ", summary));
    return googleCalendarService.createEvent(
        summary,
        Instant.parse(start),
        Instant.parse(end),
        location,
        description
    );
  }
}
