package dev.mikoto2000.rei.googlecalendar;

import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/** Google予定表をモデルから呼び出せるツールとして提供する。 */
@Component
@RequiredArgsConstructor
public class GoogleCalendarTools {

  /** Google予定表サービス。 */
  private final GoogleCalendarService googleCalendarService;

  /** 利用可能な対象を確認できるよう、イベント一覧を一覧にする。 */
  @Tool(
      name = "googleCalendarListEvents",
      description = "Google Calendar の予定を指定期間で一覧します。日時は ISO-8601 形式で指定します。")
  List<GoogleCalendarEventSummary> listEvents(String from, String to) throws Exception {
    IO.println(String.format("Google Calendar の予定を %s から %s で一覧するよ", from, to));
    return googleCalendarService.listEvents(
        googleCalendarService.parseDateTime(from).toInstant(),
        googleCalendarService.parseDateTime(to).toInstant());
  }

  /** 利用可能な対象を確認できるよう、イベント一覧対象日付を一覧にする。 */
  @Tool(
      name = "googleCalendarListEventsForDate",
      description = "Google Calendar の予定を指定日で一覧します。日付は yyyy-MM-dd 形式で指定します。")
  List<GoogleCalendarEventSummary> listEventsForDate(String date) throws Exception {
    IO.println(String.format("Google Calendar の予定を %s で一覧するよ", date));
    return googleCalendarService.listEventsForDate(LocalDate.parse(date));
  }

  /** 新たな対象を利用できるよう、イベントを作成する。 */
  @Tool(
      name = "googleCalendarCreateEvent",
      description = "Google Calendar に予定を作成します。日時は ISO-8601 形式で指定します。")
  GoogleCalendarEventSummary createEvent(
      String summary, String start, String end, String location, String description)
      throws Exception {
    IO.println(String.format("Google Calendar に予定 %s を作成するよ", summary));
    try {
      return googleCalendarService.createEvent(
          summary,
          googleCalendarService.parseDateTime(start),
          googleCalendarService.parseDateTime(end),
          location,
          description);
    } catch (Exception e) {
      IO.println("[error] " + userFacingMessage(e, "Google Calendar への予定追加に失敗しました"));
      throw e;
    }
  }

  /** 予定の管理で用いる利用者Facingメッセージを返す。 */
  private String userFacingMessage(Throwable error, String fallback) {
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

  /** 予定の管理で用いる作業ルート原因を返す。 */
  private Throwable rootCause(Throwable error) {
    Throwable current = error;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }
}
