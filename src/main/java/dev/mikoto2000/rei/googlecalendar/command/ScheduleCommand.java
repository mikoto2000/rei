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
      ScheduleCommand.RefreshTokenCommand.class,
      ScheduleCommand.ListCommand.class,
      ScheduleCommand.AddCommand.class
    })
public class ScheduleCommand {
  private abstract static class ReportCommand implements Runnable {
    private java.io.PrintWriter reportOutput;
    java.io.PrintWriter out(){return reportOutput==null?new java.io.PrintWriter(System.out,true):reportOutput;}
  }
  private static String report(ReportCommand command){var text=new java.io.StringWriter();command.reportOutput=new java.io.PrintWriter(text,true);command.run();return text.toString().replaceFirst("[\\r\\n]+$","");}
  public static String reportList(GoogleCalendarService service,LocalDate date,String from,String to){var command=new ListCommand(service);command.date=date;command.from=from;command.to=to;return report(command);}
  public static String reportAdd(GoogleCalendarService service,String title,String start,String end,String location,String description){var command=new AddCommand(service);command.titleParts=new String[]{title};command.start=start;command.end=end;command.location=location;command.description=description;return report(command);}
  public static String reportAuthorization(GoogleCalendarService service){return report(new AuthCommand(service));}
  public static String reportRefresh(GoogleCalendarService service){return report(new RefreshTokenCommand(service));}

  @Component
  @RequiredArgsConstructor
  @Command(name = "auth", description = "Google Calendar への OAuth 認可を実行します")
  static class AuthCommand extends ReportCommand {

    private final GoogleCalendarService googleCalendarService;

    @Override
    public void run() {
      try {
        googleCalendarService.authorize();
        out().println("Google Calendar の認可が完了しました");
      } catch (Exception e) {
        throw new RuntimeException("Google Calendar の認可に失敗しました", e);
      }
    }
  }

  @Component
  @RequiredArgsConstructor
  @Command(name = "refresh-token", description = "Google Calendar の OAuth トークンをリフレッシュします")
  static class RefreshTokenCommand extends ReportCommand {

    private final GoogleCalendarService googleCalendarService;

    @Override
    public void run() {
      try {
        googleCalendarService.refreshToken();
        out().println("Google Calendar のトークンを更新しました");
      } catch (Exception e) {
        throw new RuntimeException("Google Calendar のトークン更新に失敗しました", e);
      }
    }
  }

  @Component
  @RequiredArgsConstructor
  @Command(name = "list", description = "Google Calendar の予定を一覧します")
  static class ListCommand extends ReportCommand {

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
          out().println("予定はありません");
          return;
        }

        events.forEach(this::printEvent);
      } catch (Exception e) {
        throw new RuntimeException("Google Calendar の予定一覧取得に失敗しました", e);
      }
    }

    private void printEvent(GoogleCalendarEventSummary event) {
      out().println(String.format(
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
  static class AddCommand extends ReportCommand {

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
        out().println(String.format(
            "作成しました: %s | %s | %s",
            created.id(),
            created.summary(),
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(resolvedStart)
        ));
      } catch (Exception e) {
        out().println("[error] " + userFacingMessage(e, "Google Calendar への予定追加に失敗しました"));
      }
    }
  }

  private static String userFacingMessage(Throwable error, String fallback) {
    Throwable root = rootCause(error);
    String message = root.getMessage();
    if (message == null || message.isBlank()) {
      message = error.getMessage();
    }
    if (message == null || message.isBlank()) {
      return fallback;
    }
    if (fallback.equals(message) || message.startsWith(fallback + ":")) {
      return message;
    }
    return fallback + ": " + message;
  }

  private static Throwable rootCause(Throwable error) {
    Throwable current = error;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }
}
