package dev.mikoto2000.rei.googlecalendar;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import dev.mikoto2000.rei.google.GoogleOAuthService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Google予定表のユースケースを実現する。 */
@Service
public class GoogleCalendarService {

  /** JSON変換生成元。 */
  private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();

  /** 日付時刻表示形式への変換。 */
  private static final DateTimeFormatter DATE_TIME_FORMATTER =
      DateTimeFormatter.ISO_OFFSET_DATE_TIME;

  /** この機能の動作設定。 */
  private final GoogleCalendarProperties properties;

  /** GoogleO認証サービス。 */
  private final GoogleOAuthService googleOAuthService;

  /** 指定された値や利用先を使って、GoogleCalendarServiceを初期化する。 */
  @Autowired
  public GoogleCalendarService(
      GoogleCalendarProperties properties, GoogleOAuthService googleOAuthService) {
    this.properties = properties;
    this.googleOAuthService = googleOAuthService;
  }

  /** 予定の管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public GoogleCalendarService(GoogleCalendarProperties properties) {
    this(properties, new GoogleOAuthService(properties));
  }

  /** 予定の管理に関する権限の確認を処理する。 */
  public void authorize() throws Exception {
    authorize(true);
  }

  /** 予定の管理に関する権限の確認を処理する。 */
  public void authorize(boolean forceReauthorize) throws Exception {
    if (!properties.calendar().enabled()) {
      throw new IllegalStateException("Google Calendar integration is disabled");
    }
    NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
    googleOAuthService.authorize(httpTransport, forceReauthorize);
  }

  /** 予定の管理に関する認証を更新するトークンを処理する。 */
  public void refreshToken() throws Exception {
    if (!properties.calendar().enabled()) {
      throw new IllegalStateException("Google Calendar integration is disabled");
    }

    googleOAuthService.refreshToken();
  }

  /** 利用可能な対象を確認できるよう、イベント一覧を一覧にする。 */
  public List<GoogleCalendarEventSummary> listEvents(Instant from, Instant to) throws Exception {
    Calendar client = getCalendarClient();

    return client
        .events()
        .list(defaultCalendarId())
        .setTimeMin(new com.google.api.client.util.DateTime(from.toEpochMilli()))
        .setTimeMax(new com.google.api.client.util.DateTime(to.toEpochMilli()))
        .setOrderBy("startTime")
        .setSingleEvents(true)
        .execute()
        .getItems()
        .stream()
        .map(this::toSummary)
        .toList();
  }

  /** 新たな対象を利用できるよう、イベントを作成する。 */
  public GoogleCalendarEventSummary createEvent(
      String summary, ZonedDateTime start, ZonedDateTime end, String location, String description)
      throws Exception {
    if (!end.isAfter(start)) {
      throw new IllegalArgumentException("end must be after start");
    }

    Event event =
        new Event()
            .setSummary(summary)
            .setLocation(blankToNull(location))
            .setDescription(blankToNull(description))
            .setStart(toEventDateTime(start))
            .setEnd(toEventDateTime(end));

    Event created = getCalendarClient().events().insert(defaultCalendarId(), event).execute();

    return toSummary(created);
  }

  /** 利用可能な対象を確認できるよう、イベント一覧対象日付を一覧にする。 */
  public List<GoogleCalendarEventSummary> listEventsForDate(LocalDate date) throws Exception {
    ZonedDateTime from = date.atStartOfDay(zoneId());
    ZonedDateTime to = date.plusDays(1).atStartOfDay(zoneId());
    return listEvents(from.toInstant(), to.toInstant());
  }

  /** 入力を機能内で扱えるよう、日付時刻を解釈する。 */
  public ZonedDateTime parseDateTime(String value) {
    try {
      return ZonedDateTime.parse(value);
    } catch (DateTimeParseException ignored) {
    }

    try {
      return OffsetDateTime.parse(value).toZonedDateTime();
    } catch (DateTimeParseException ignored) {
    }

    try {
      return LocalDateTime.parse(value).atZone(zoneId());
    } catch (DateTimeParseException ignored) {
    }

    throw new IllegalArgumentException(
        "日時の形式が不正です。ISO-8601 形式で指定してください。例: 2026-03-23T09:00:00+09:00 または 2026-03-23T09:00:00");
  }

  /** 予定の管理で用いるタイムゾーン識別子を返す。 */
  public ZoneId zoneId() {
    if (properties.calendar().timeZone() == null || properties.calendar().timeZone().isBlank()) {
      return ZoneId.systemDefault();
    }

    return ZoneId.of(properties.calendar().timeZone());
  }

  /** 予定の管理で参照する予定表クライアントを返す。 */
  private Calendar getCalendarClient() throws Exception {
    if (!properties.calendar().enabled()) {
      throw new IllegalStateException("Google Calendar integration is disabled");
    }

    NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
    return new Calendar.Builder(
            httpTransport, JSON_FACTORY, googleOAuthService.authorize(httpTransport, false))
        .setApplicationName(properties.applicationName())
        .build();
  }

  /** 利用先へ渡せるよう、要約の形式に変換する。 */
  private GoogleCalendarEventSummary toSummary(Event event) {
    return new GoogleCalendarEventSummary(
        event.getId(),
        Objects.toString(event.getSummary(), "(no title)"),
        formatEventTime(event.getStart()),
        formatEventTime(event.getEnd()),
        Objects.toString(event.getLocation(), ""),
        Objects.toString(event.getStatus(), ""));
  }

  /** 内容を表示・参照しやすくするため、イベント時刻を整形する。 */
  private String formatEventTime(EventDateTime eventDateTime) {
    if (eventDateTime == null) {
      return "";
    }

    if (eventDateTime.getDateTime() != null) {
      ZoneId eventZone =
          eventDateTime.getTimeZone() == null || eventDateTime.getTimeZone().isBlank()
              ? zoneId()
              : ZoneId.of(eventDateTime.getTimeZone());
      return DATE_TIME_FORMATTER.format(
          Instant.ofEpochMilli(eventDateTime.getDateTime().getValue()).atZone(eventZone));
    }

    if (eventDateTime.getDate() != null) {
      return eventDateTime.getDate().toStringRfc3339();
    }

    return "";
  }

  /** 利用先へ渡せるよう、イベント日付時刻の形式に変換する。 */
  private EventDateTime toEventDateTime(ZonedDateTime dateTime) {
    return new EventDateTime()
        .setDateTime(new com.google.api.client.util.DateTime(dateTime.toInstant().toEpochMilli()))
        .setTimeZone(dateTime.getZone().getId());
  }

  /** 予定の管理で用いる既定予定表識別子を返す。 */
  private String defaultCalendarId() {
    return properties.calendar().defaultCalendarId() == null
            || properties.calendar().defaultCalendarId().isBlank()
        ? "primary"
        : properties.calendar().defaultCalendarId();
  }

  /** 予定の管理で用いる空白終点未指定を返す。 */
  private String blankToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }

    return value;
  }
}
