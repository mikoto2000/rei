package dev.mikoto2000.rei.googlecalendar;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.google.api.client.auth.oauth2.AuthorizationCodeFlow;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.CalendarScopes;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;

import dev.mikoto2000.rei.googlecalendar.GoogleCalendarProperties;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class GoogleCalendarService {

  private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
  private static final List<String> SCOPES = List.of(CalendarScopes.CALENDAR_EVENTS);
  private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

  private final GoogleCalendarProperties properties;

  public void authorize() throws Exception {
    getCalendarClient();
  }

  public List<GoogleCalendarEventSummary> listEvents(Instant from, Instant to) throws Exception {
    Calendar client = getCalendarClient();

    return client.events()
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

  public GoogleCalendarEventSummary createEvent(
      String summary,
      Instant start,
      Instant end,
      String location,
      String description
  ) throws Exception {
    if (!end.isAfter(start)) {
      throw new IllegalArgumentException("end must be after start");
    }

    Event event = new Event()
      .setSummary(summary)
      .setLocation(blankToNull(location))
      .setDescription(blankToNull(description))
      .setStart(toEventDateTime(start))
      .setEnd(toEventDateTime(end));

    Event created = getCalendarClient()
      .events()
      .insert(defaultCalendarId(), event)
      .execute();

    return toSummary(created);
  }

  public List<GoogleCalendarEventSummary> listEventsForDate(LocalDate date) throws Exception {
    Instant from = date.atStartOfDay().toInstant(ZoneOffset.UTC);
    Instant to = date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    return listEvents(from, to);
  }

  private Calendar getCalendarClient() throws Exception {
    if (!properties.enabled()) {
      throw new IllegalStateException("Google Calendar integration is disabled");
    }

    NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
    return new Calendar.Builder(httpTransport, JSON_FACTORY, authorizeCredential(httpTransport))
      .setApplicationName(properties.applicationName())
      .build();
  }

  private com.google.api.client.auth.oauth2.Credential authorizeCredential(NetHttpTransport httpTransport) throws Exception {
    Path credentialsPath = Path.of(properties.credentialsPath());
    if (!Files.exists(credentialsPath)) {
      throw new IllegalStateException("Google OAuth credentials file was not found: " + credentialsPath);
    }

    Files.createDirectories(Path.of(properties.tokensDirectory()));

    try (InputStream in = Files.newInputStream(credentialsPath)) {
      GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));
      AuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
          httpTransport,
          JSON_FACTORY,
          clientSecrets,
          SCOPES
      )
        .setDataStoreFactory(new FileDataStoreFactory(Path.of(properties.tokensDirectory()).toFile()))
        .setAccessType("offline")
        .build();

      LocalServerReceiver receiver = new LocalServerReceiver.Builder()
        .setHost("127.0.0.1")
        .setPort(8888)
        .build();

      AuthorizationCodeInstalledApp app = new AuthorizationCodeInstalledApp(flow, receiver, this::browse);
      return app.authorize("user");
    }
  }

  private void browse(String url) throws IOException {
    IO.println("Google OAuth を開始します。ブラウザが開かない場合は次の URL を開いてください:");
    IO.println(url);

    if (Desktop.isDesktopSupported()) {
      Desktop desktop = Desktop.getDesktop();
      if (desktop.isSupported(Desktop.Action.BROWSE)) {
        desktop.browse(URI.create(url));
      }
    }
  }

  private GoogleCalendarEventSummary toSummary(Event event) {
    return new GoogleCalendarEventSummary(
        event.getId(),
        Objects.toString(event.getSummary(), "(no title)"),
        formatEventTime(event.getStart()),
        formatEventTime(event.getEnd()),
        Objects.toString(event.getLocation(), ""),
        Objects.toString(event.getStatus(), "")
    );
  }

  private String formatEventTime(EventDateTime eventDateTime) {
    if (eventDateTime == null) {
      return "";
    }

    if (eventDateTime.getDateTime() != null) {
      return DATE_TIME_FORMATTER.format(Instant.ofEpochMilli(eventDateTime.getDateTime().getValue()).atOffset(ZoneOffset.UTC));
    }

    if (eventDateTime.getDate() != null) {
      return eventDateTime.getDate().toStringRfc3339();
    }

    return "";
  }

  private EventDateTime toEventDateTime(Instant instant) {
    return new EventDateTime().setDateTime(new com.google.api.client.util.DateTime(instant.toEpochMilli()));
  }

  private String defaultCalendarId() {
    return properties.defaultCalendarId() == null || properties.defaultCalendarId().isBlank()
        ? "primary"
        : properties.defaultCalendarId();
  }

  private String blankToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }

    return value;
  }
}
