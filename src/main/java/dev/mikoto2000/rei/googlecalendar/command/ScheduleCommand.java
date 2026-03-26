package dev.mikoto2000.rei.googlecalendar.command;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.googlecalendar.GoogleCalendarEventSummary;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarService;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(
    name = "schedule",
    description = "Google Calendar の予定を操作します",
    subcommands = {
      ScheduleCommand.AuthCommand.class,
      ScheduleCommand.ListCommand.class,
      ScheduleCommand.AddCommand.class
    })
public class ScheduleCommand {

  @Component
  @RequiredArgsConstructor
  @Command(name = "auth", description = "Google Calendar への OAuth 認可を実行します")
  static class AuthCommand implements Runnable {

    private final GoogleCalendarService googleCalendarService;

    @Override
    public void run() {
      try {
        googleCalendarService.authorize();
        IO.println("Google Calendar の認可が完了しました");
      } catch (Exception e) {
        throw new RuntimeException("Google Calendar の認可に失敗しました", e);
      }
    }
  }

  @Component
  @RequiredArgsConstructor
  @Command(name = "list", description = "Google Calendar の予定を一覧します")
  static class ListCommand implements Runnable {

    private final GoogleCalendarService googleCalendarService;

    @Option(names = "--date", description = "対象日。形式: yyyy-MM-dd")
    LocalDate date;

    @Option(names = "--from", description = "開始日時。形式: 2026-03-23T09:00:00+09:00 または 2026-03-23T09:00:00")
    String from;

    @Option(names = "--to", description = "終了日時。形式: 2026-03-23T18:00:00+09:00 または 2026-03-23T18:00:00")
    String to;

    @Override
    public void run() {
      try {
        List<GoogleCalendarEventSummary> events;
        if (date != null) {
          events = googleCalendarService.listEventsForDate(date);
        } else {
          ZonedDateTime resolvedFrom = from != null
              ? googleCalendarService.parseDateTime(from)
              : LocalDate.now(googleCalendarService.zoneId()).atStartOfDay(googleCalendarService.zoneId());
          ZonedDateTime resolvedTo = to != null
              ? googleCalendarService.parseDateTime(to)
              : resolvedFrom.plusDays(1);
          events = googleCalendarService.listEvents(resolvedFrom.toInstant(), resolvedTo.toInstant());
        }

        if (events.isEmpty()) {
          IO.println("予定はありません");
          return;
        }

        events.forEach(this::printEvent);
      } catch (Exception e) {
        throw new RuntimeException("Google Calendar の予定一覧取得に失敗しました", e);
      }
    }

    private void printEvent(GoogleCalendarEventSummary event) {
      IO.println(String.format(
          "%s | %s | %s | %s | %s",
          event.id(),
          event.start(),
          event.end(),
          event.summary(),
          event.location()
      ));
    }
  }

  @Component
  @RequiredArgsConstructor
  @Command(name = "add", description = "Google Calendar に予定を追加します")
  static class AddCommand implements Runnable {

    private final GoogleCalendarService googleCalendarService;

    @Option(names = "--start", required = true, description = "開始日時。形式: 2026-03-23T09:00:00+09:00 または 2026-03-23T09:00:00")
    String start;

    @Option(names = "--end", required = true, description = "終了日時。形式: 2026-03-23T10:00:00+09:00 または 2026-03-23T10:00:00")
    String end;

    @Option(names = "--location", description = "場所")
    String location;

    @Option(names = "--description", description = "説明")
    String description;

    @Parameters(arity = "1..*", paramLabel = "TITLE", description = "予定タイトル")
    String[] titleParts;

    @Override
    public void run() {
      try {
        ZonedDateTime resolvedStart = googleCalendarService.parseDateTime(start);
        ZonedDateTime resolvedEnd = googleCalendarService.parseDateTime(end);
        GoogleCalendarEventSummary created = googleCalendarService.createEvent(
            String.join(" ", titleParts),
            resolvedStart,
            resolvedEnd,
            location,
            description
        );
        IO.println(String.format(
            "作成しました: %s | %s | %s",
            created.id(),
            created.summary(),
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(resolvedStart)
        ));
      } catch (Exception e) {
        throw new RuntimeException("Google Calendar への予定追加に失敗しました", e);
      }
    }
  }
}
