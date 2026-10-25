package dev.mikoto2000.rei.googlecalendar;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import dev.mikoto2000.rei.google.GoogleOAuthService;
import dev.mikoto2000.rei.secretary.SecretaryProvider.Auth;
import dev.mikoto2000.rei.secretary.SecretaryProvider.Failure;
import dev.mikoto2000.rei.secretary.SecretaryProvider.Item;
import dev.mikoto2000.rei.secretary.SecretaryProvider.Outcome;
import dev.mikoto2000.rei.secretary.SecretaryProvider.Page;
import dev.mikoto2000.rei.secretary.SecretaryProvider.Service;
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

/** Google予定表のユースケースを実現する。 */
@org.springframework.stereotype.Service
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

  /** Existing SDK and credential storage, with explicit noninteractive initialization. */
  protected Calendar secretaryCalendarClient()
      throws java.io.IOException, java.security.GeneralSecurityException {
    if (properties.calendar() == null || !properties.calendar().enabled())
      throw new Failure("CALENDAR_DISABLED", 0, false);
    return new Calendar.Builder(
            GoogleNetHttpTransport.newTrustedTransport(),
            JSON_FACTORY,
            googleOAuthService.secretaryInitializer())
        .setApplicationName(properties.applicationName())
        .build();
  }

  public java.util.Set<Service> services() {
    return java.util.Set.of(Service.CALENDAR);
  }

  public Auth status(Service service) {
    return googleOAuthService.secretaryGoogleStatus(
        properties.calendar() != null && properties.calendar().enabled());
  }

  public String identity(dev.mikoto2000.rei.secretary.SecretaryConnections.Connection connection) {
    return googleOAuthService.secretaryIdentity();
  }

  public Auth authorizeConnection(Service service, boolean force) {
    if (properties.calendar() == null || !properties.calendar().enabled())
      throw new Failure("CALENDAR_DISABLED", 0, false);
    return googleOAuthService.authorizeConnection(Service.CALENDAR, force);
  }

  private static void secretaryFields(java.util.Map<String, String> args, String... fields) {
    if (!java.util.Set.of(fields).containsAll(args.keySet()))
      throw new IllegalArgumentException("Unknown Calendar argument");
  }

  private static String secretaryId(String id) {
    if (id == null || !id.matches("[A-Za-z0-9_-]{1,512}"))
      throw new IllegalArgumentException("Invalid Calendar event id");
    return id;
  }

  private static String secretaryText(String text, int max, boolean required) {
    if (text == null
        || required && text.isBlank()
        || text.length() > max
        || text.indexOf('\r') >= 0 && max < 1000
        || text.indexOf('\n') >= 0 && max < 1000)
      throw new IllegalArgumentException("Invalid Calendar field");
    return text;
  }

  private static ZonedDateTime secretaryDate(String value, ZoneId zone) {
    try {
      return ZonedDateTime.parse(value);
    } catch (DateTimeParseException ignored) {
      return LocalDateTime.parse(value).atZone(zone);
    }
  }

  private static ZonedDateTime secretaryTime(EventDateTime value, ZoneId zone) {
    if (value == null) return null;
    if (value.getDateTime() != null)
      return Instant.ofEpochMilli(value.getDateTime().getValue()).atZone(zone);
    return value.getDate() == null
        ? null
        : LocalDate.parse(value.getDate().toStringRfc3339().substring(0, 10)).atStartOfDay(zone);
  }

  private Item secretaryItem(
      Event event,
      dev.mikoto2000.rei.secretary.SecretaryConnections.Connection connection,
      boolean detail) {
    var zone = ZoneId.of(connection.config().zone());
    var start = secretaryTime(event.getStart(), zone);
    var end = secretaryTime(event.getEnd(), zone);
    var data = new java.util.HashMap<String, String>();
    data.put("etag", Objects.toString(event.getEtag(), ""));
    data.put("start", start == null ? "" : start.toString());
    data.put("end", end == null ? "" : end.toString());
    data.put("location", Objects.toString(event.getLocation(), ""));
    String revision =
        Objects.toString(event.getEtag(), Objects.toString(event.getUpdated(), "unknown"));
    return new Item(
        secretaryId(event.getId()),
        revision,
        "CALENDAR",
        Objects.toString(event.getSummary(), "(no title)"),
        detail ? Objects.toString(event.getDescription(), "") : "",
        Objects.toString(event.getStatus(), "confirmed").toUpperCase(java.util.Locale.ROOT),
        event.getUpdated() == null
            ? Instant.now()
            : Instant.ofEpochMilli(event.getUpdated().getValue()),
        start == null ? null : start.toLocalDate(),
        false,
        data);
  }

  private static void secretaryRange(Instant from, Instant to) {
    if (!to.isAfter(from)
        || java.time.Duration.between(from, to).compareTo(java.time.Duration.ofDays(31)) > 0)
      throw new IllegalArgumentException("Calendar range must be positive and at most 31 days");
  }

  public Page read(
      dev.mikoto2000.rei.secretary.SecretaryConnections.Connection connection,
      String operation,
      java.util.Map<String, String> args) {
    var zone = ZoneId.of(connection.config().zone());
    if (operation.equals("DETAIL")) {
      secretaryFields(args, "id");
      return googleOAuthService.secretaryNetwork(
          () ->
              new Page(
                  List.of(
                      secretaryItem(
                          secretaryCalendarClient()
                              .events()
                              .get(connection.config().resource(), secretaryId(args.get("id")))
                              .execute(),
                          connection,
                          true)),
                  "",
                  false),
          false);
    }
    if (operation.equals("FREEBUSY")) {
      secretaryFields(args, "from", "to");
      var from = secretaryDate(args.get("from"), zone).toInstant();
      var to = secretaryDate(args.get("to"), zone).toInstant();
      secretaryRange(from, to);
      return googleOAuthService.secretaryNetwork(
          () -> {
            var request =
                new com.google.api.services.calendar.model.FreeBusyRequest()
                    .setTimeMin(new com.google.api.client.util.DateTime(from.toEpochMilli()))
                    .setTimeMax(new com.google.api.client.util.DateTime(to.toEpochMilli()))
                    .setTimeZone(zone.getId())
                    .setItems(
                        List.of(
                            new com.google.api.services.calendar.model.FreeBusyRequestItem()
                                .setId(connection.config().resource())));
            var response = secretaryCalendarClient().freebusy().query(request).execute();
            var calendar =
                response.getCalendars() == null
                    ? null
                    : response.getCalendars().get(connection.config().resource());
            if (calendar == null || calendar.getErrors() != null && !calendar.getErrors().isEmpty())
              throw new Failure("FREEBUSY_UNAVAILABLE", 0, false);
            var busy =
                calendar.getBusy() == null
                    ? List.<com.google.api.services.calendar.model.TimePeriod>of()
                    : calendar.getBusy();
            if (busy.size() > 1000) throw new Failure("FREEBUSY_CAPACITY", 0, false);
            var periods = new java.util.ArrayList<Instant[]>();
            for (var period : busy) {
              if (period.getStart() == null || period.getEnd() == null)
                throw new Failure("INVALID_RESPONSE", 0, false);
              periods.add(
                  new Instant[] {
                    Instant.ofEpochMilli(period.getStart().getValue()),
                    Instant.ofEpochMilli(period.getEnd().getValue())
                  });
            }
            periods.sort(java.util.Comparator.comparing(p -> p[0]));
            var items = new java.util.ArrayList<Item>();
            Instant position = from;
            for (var period : periods) {
              Instant start = period[0].isBefore(from) ? from : period[0];
              Instant end = period[1].isAfter(to) ? to : period[1];
              if (!end.isAfter(start) || !start.isBefore(to) || !end.isAfter(from)) continue;
              if (start.isAfter(position)) items.add(secretarySlot(position, start, zone));
              if (end.isAfter(position)) position = end;
            }
            if (position.isBefore(to)) items.add(secretarySlot(position, to, zone));
            return new Page(items, "", false);
          },
          false);
    }
    if (!java.util.Set.of("LIST", "TODAY").contains(operation))
      throw new IllegalArgumentException("Unknown Calendar read operation");
    secretaryFields(args, "from", "to", "date", "page");
    LocalDate date =
        args.containsKey("date") ? LocalDate.parse(args.get("date")) : LocalDate.now(zone);
    Instant from =
        operation.equals("TODAY") || !args.containsKey("from")
            ? date.atStartOfDay(zone).toInstant()
            : secretaryDate(args.get("from"), zone).toInstant();
    Instant to =
        operation.equals("TODAY") || !args.containsKey("to")
            ? date.plusDays(1).atStartOfDay(zone).toInstant()
            : secretaryDate(args.get("to"), zone).toInstant();
    secretaryRange(from, to);
    return googleOAuthService.secretaryNetwork(
        () -> {
          var request =
              secretaryCalendarClient()
                  .events()
                  .list(connection.config().resource())
                  .setTimeMin(new com.google.api.client.util.DateTime(from.toEpochMilli()))
                  .setTimeMax(new com.google.api.client.util.DateTime(to.toEpochMilli()))
                  .setSingleEvents(true)
                  .setShowDeleted(true)
                  .setOrderBy("startTime")
                  .setMaxResults(50)
                  .setPageToken(args.get("page"));
          var response = request.execute();
          var items =
              response.getItems() == null
                  ? List.<Item>of()
                  : response.getItems().stream()
                      .map(e -> secretaryItem(e, connection, false))
                      .toList();
          return new Page(
              items,
              Objects.toString(response.getNextPageToken(), ""),
              response.getNextPageToken() != null);
        },
        false);
  }

  private static Item secretarySlot(Instant from, Instant to, ZoneId zone) {
    return new Item(
        "slot:" + from,
        "1",
        "FREE_SLOT",
        "空き時間",
        "",
        "AVAILABLE",
        Instant.now(),
        from.atZone(zone).toLocalDate(),
        false,
        java.util.Map.of("start", from.atZone(zone).toString(), "end", to.atZone(zone).toString()));
  }

  public Page poll(
      dev.mikoto2000.rei.secretary.SecretaryConnections.Connection connection, String cursor) {
    var zone = ZoneId.of(connection.config().zone());
    java.util.Map<String, String> args;
    if (cursor.isBlank()) {
      var date = LocalDate.now(zone);
      args =
          java.util.Map.of(
              "from",
              date.minusDays(1).atStartOfDay(zone).toString(),
              "to",
              date.plusDays(30).atStartOfDay(zone).toString());
    } else
      try {
        args =
            JSON_FACTORY
                .fromString(
                    new String(
                        java.util.Base64.getUrlDecoder().decode(cursor),
                        java.nio.charset.StandardCharsets.UTF_8),
                    com.google.api.client.json.GenericJson.class)
                .entrySet()
                .stream()
                .collect(
                    java.util.stream.Collectors.toMap(
                        java.util.Map.Entry::getKey, e -> Objects.toString(e.getValue(), "")));
      } catch (java.io.IOException | IllegalArgumentException invalid) {
        throw new Failure("INVALID_CURSOR", 0, false);
      }
    var page = read(connection, "LIST", args);
    if (page.cursor().isBlank()) return new Page(page.items(), "", false);
    var next = new java.util.HashMap<>(args);
    next.put("page", page.cursor());
    try {
      return new Page(
          page.items(),
          java.util.Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(JSON_FACTORY.toByteArray(next)),
          true);
    } catch (java.io.IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public Outcome performSecretaryAction(
      dev.mikoto2000.rei.secretary.SecretaryConnections.Connection connection,
      String operation,
      java.util.Map<String, String> args,
      String key) {
    if (!java.util.Set.of("CALENDAR_CREATE", "CALENDAR_UPDATE", "CALENDAR_CANCEL")
        .contains(operation)) throw new IllegalArgumentException("Unknown Calendar action");
    secretaryFields(args, "id", "etag", "title", "start", "end", "description", "location");
    var zone = ZoneId.of(connection.config().zone());
    String eventId;
    try {
      eventId =
          operation.equals("CALENDAR_CREATE")
              ? java.util.HexFormat.of()
                  .formatHex(
                      java.security.MessageDigest.getInstance("SHA-256")
                          .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
              : secretaryId(args.get("id"));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
    String etag =
        operation.equals("CALENDAR_CREATE") ? "" : secretaryText(args.get("etag"), 512, true);
    var desired =
        operation.equals("CALENDAR_CREATE")
            ? new Event().setId(eventId)
            : googleOAuthService.secretaryNetwork(
                () ->
                    secretaryCalendarClient()
                        .events()
                        .get(connection.config().resource(), eventId)
                        .execute(),
                false);
    if (!operation.equals("CALENDAR_CREATE") && !etag.equals(desired.getEtag()))
      throw new Failure("PRECONDITION_FAILED", 0, false);
    if (!operation.equals("CALENDAR_CANCEL")) {
      if (args.containsKey("title"))
        desired.setSummary(secretaryText(args.get("title"), 500, true));
      if (args.containsKey("location"))
        desired.setLocation(secretaryText(args.get("location"), 500, false));
      if (args.containsKey("description"))
        desired.setDescription(secretaryText(args.get("description"), 8192, false));
      if (args.containsKey("start"))
        desired.setStart(toEventDateTime(secretaryDate(args.get("start"), zone)));
      if (args.containsKey("end"))
        desired.setEnd(toEventDateTime(secretaryDate(args.get("end"), zone)));
      var from = secretaryTime(desired.getStart(), zone);
      var to = secretaryTime(desired.getEnd(), zone);
      if (desired.getSummary() == null || from == null || to == null || !to.isAfter(from))
        throw new IllegalArgumentException("Calendar title/start/end required");
    }
    googleOAuthService.secretaryNetwork(
        () -> {
          if (operation.equals("CALENDAR_CREATE")) {
            try {
              secretaryCalendarClient()
                  .events()
                  .insert(connection.config().resource(), desired)
                  .execute();
            } catch (com.google.api.client.http.HttpResponseException conflict) {
              if (conflict.getStatusCode() != 409) throw conflict;
            }
          } else if (operation.equals("CALENDAR_UPDATE")) {
            var request =
                secretaryCalendarClient()
                    .events()
                    .patch(connection.config().resource(), eventId, desired);
            request.getRequestHeaders().setIfMatch(etag);
            request.execute();
          } else {
            var request =
                secretaryCalendarClient().events().delete(connection.config().resource(), eventId);
            request.getRequestHeaders().setIfMatch(etag);
            request.execute();
          }
          return true;
        },
        true);
    try {
      Event actual;
      try {
        actual =
            googleOAuthService.secretaryNetwork(
                () ->
                    secretaryCalendarClient()
                        .events()
                        .get(connection.config().resource(), eventId)
                        .execute(),
                false);
      } catch (Failure missing) {
        if (operation.equals("CALENDAR_CANCEL")
            && java.util.Set.of("NOT_FOUND", "GONE").contains(missing.code()))
          return new Outcome("COMPLETED", eventId, "cancelled", java.util.Map.of(), true);
        throw missing;
      }
      boolean verified =
          operation.equals("CALENDAR_CANCEL")
              ? "cancelled".equals(actual.getStatus())
              : Objects.equals(desired.getSummary(), actual.getSummary())
                  && Objects.equals(
                      secretaryTime(desired.getStart(), zone),
                      secretaryTime(actual.getStart(), zone))
                  && Objects.equals(
                      secretaryTime(desired.getEnd(), zone), secretaryTime(actual.getEnd(), zone))
                  && Objects.equals(desired.getDescription(), actual.getDescription())
                  && Objects.equals(desired.getLocation(), actual.getLocation());
      return new Outcome(
          verified ? "COMPLETED" : "UNCERTAIN",
          eventId,
          Objects.toString(actual.getEtag(), ""),
          java.util.Map.of(),
          verified);
    } catch (Failure unconfirmed) {
      throw new Failure("VERIFICATION_UNAVAILABLE", unconfirmed.retrySeconds(), true);
    }
  }

  public Outcome verify(
      dev.mikoto2000.rei.secretary.SecretaryConnections.Connection c,
      String operation,
      java.util.Map<String, String> args,
      String key,
      Outcome previous) {
    if (!java.util.Set.of("CALENDAR_CREATE", "CALENDAR_UPDATE", "CALENDAR_CANCEL")
        .contains(operation)) throw new IllegalArgumentException("Unknown Calendar action");
    String id;
    try {
      id =
          operation.equals("CALENDAR_CREATE")
              ? java.util.HexFormat.of()
                  .formatHex(
                      java.security.MessageDigest.getInstance("SHA-256")
                          .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
              : secretaryId(args.get("id"));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
    Event actual;
    try {
      actual =
          googleOAuthService.secretaryNetwork(
              () -> secretaryCalendarClient().events().get(c.config().resource(), id).execute(),
              false);
    } catch (Failure missing) {
      if (operation.equals("CALENDAR_CANCEL")
          && java.util.Set.of("NOT_FOUND", "GONE").contains(missing.code()))
        return new Outcome("COMPLETED", id, "cancelled", java.util.Map.of(), true);
      throw missing;
    }
    var zone = ZoneId.of(c.config().zone());
    boolean verified;
    if (operation.equals("CALENDAR_CANCEL")) verified = "cancelled".equals(actual.getStatus());
    else
      verified =
          !"cancelled".equals(actual.getStatus())
              && (!args.containsKey("title")
                  || Objects.equals(args.get("title"), actual.getSummary()))
              && (!args.containsKey("description")
                  || Objects.equals(
                      args.get("description"), Objects.toString(actual.getDescription(), "")))
              && (!args.containsKey("location")
                  || Objects.equals(
                      args.get("location"), Objects.toString(actual.getLocation(), "")))
              && (!args.containsKey("start")
                  || secretaryDate(args.get("start"), zone)
                      .toInstant()
                      .equals(secretaryTime(actual.getStart(), zone).toInstant()))
              && (!args.containsKey("end")
                  || secretaryDate(args.get("end"), zone)
                      .toInstant()
                      .equals(secretaryTime(actual.getEnd(), zone).toInstant()))
              && (!operation.equals("CALENDAR_UPDATE")
                  || !Objects.equals(args.get("etag"), actual.getEtag()));
    return new Outcome(
        verified ? "COMPLETED" : "UNCERTAIN",
        id,
        Objects.toString(actual.getEtag(), ""),
        java.util.Map.of(),
        verified);
  }

  /** 予定の管理で用いる空白終点未指定を返す。 */
  private String blankToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }

    return value;
  }
}
