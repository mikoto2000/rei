package dev.mikoto2000.rei.googlecalendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import dev.mikoto2000.rei.google.GoogleOAuthService;
import dev.mikoto2000.rei.secretary.SecretaryConnections;
import dev.mikoto2000.rei.secretary.SecretaryProvider;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SecretaryCalendarTest {
  final GoogleCalendarProperties properties =
      new GoogleCalendarProperties(
          "test",
          null,
          null,
          new GoogleCalendarProperties.CalendarProperties(true, "primary", "Asia/Tokyo"),
          new GoogleCalendarProperties.TaskProperties(false));

  SecretaryConnections.Connection connection() {
    return new SecretaryConnections.Connection(
        "c",
        0,
        new SecretaryConnections.Config(
            "p",
            "s",
            "calendar",
            SecretaryProvider.Service.CALENDAR,
            "primary",
            true,
            300,
            false,
            false,
            "Asia/Tokyo"),
        "IDLE",
        "",
        "",
        Instant.EPOCH,
        0,
        Instant.EPOCH);
  }

  SecretaryProvider service(String response) {
    var transport =
        new MockHttpTransport.Builder()
            .setLowLevelHttpResponse(
                new MockLowLevelHttpResponse()
                    .setContentType("application/json")
                    .setContent(response))
            .build();
    return provider(
        new GoogleCalendarService(properties, new GoogleOAuthService(properties)) {
          @Override
          protected com.google.api.services.calendar.Calendar secretaryCalendarClient() {
            return new com.google.api.services.calendar.Calendar.Builder(
                    transport,
                    com.google.api.client.json.gson.GsonFactory.getDefaultInstance(),
                    null)
                .setApplicationName("test")
                .build();
          }
        });
  }

  SecretaryProvider provider(GoogleCalendarService service) {
    var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
    beans.addBean("calendar", service);
    return new CalendarSecretaryProvider(beans.getBeanProvider(GoogleCalendarService.class));
  }

  @Test
  void anEmptyCalendarDoesNotProvePersonalAvailability() throws Exception {
    var service = service("{\"calendars\":{\"primary\":{\"busy\":[]}}}");
    var result =
        service.read(
            connection(),
            "FREEBUSY",
            Map.of("from", "2026-10-26T09:00:00+09:00", "to", "2026-10-26T18:00:00+09:00"));
    assertEquals(1, result.items().size());
    assertEquals("UNKNOWN", result.items().getFirst().status());
  }

  @Test
  void confirmedDeleteWithGoneResponseIsVerifiedWithoutAnotherWrite() {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var transport =
        new MockHttpTransport() {
          @Override
          public com.google.api.client.http.LowLevelHttpRequest buildRequest(
              String method, String url) {
            return new com.google.api.client.testing.http.MockLowLevelHttpRequest(url) {
              @Override
              public com.google.api.client.http.LowLevelHttpResponse execute() {
                int call = calls.incrementAndGet();
                if (call == 1)
                  return new MockLowLevelHttpResponse()
                      .setContentType("application/json")
                      .setContent("{\"id\":\"event1\",\"etag\":\"v1\"}");
                if (call == 2) {
                  assertEquals("DELETE", method);
                  assertEquals("v1", getFirstHeaderValue("If-Match"));
                  return new MockLowLevelHttpResponse().setStatusCode(204);
                }
                assertEquals("GET", method);
                return new MockLowLevelHttpResponse()
                    .setStatusCode(410)
                    .setContentType("application/json")
                    .setContent("{\"error\":{\"code\":410,\"message\":\"Gone\"}}");
              }
            };
          }
        };
    var service =
        new GoogleCalendarService(properties, new GoogleOAuthService(properties)) {
          @Override
          protected com.google.api.services.calendar.Calendar secretaryCalendarClient() {
            return new com.google.api.services.calendar.Calendar.Builder(
                    transport,
                    com.google.api.client.json.gson.GsonFactory.getDefaultInstance(),
                    null)
                .setApplicationName("test")
                .build();
          }
        };
    assertTrue(
        provider(service)
            .write(connection(), "CALENDAR_CANCEL", Map.of("id", "event1", "etag", "v1"), "action")
            .verified());
    assertEquals(3, calls.get());
  }

  @Test
  void currentDayUsesConnectionTimezoneAndReturnsOwnedEventMetadata() {
    SecretaryProvider service =
        service(
            "{\"items\":[{\"id\":\"event1\",\"etag\":\"v1\",\"summary\":\"meeting\",\"status\":\"confirmed\",\"start\":{\"dateTime\":\"2026-10-26T09:00:00+09:00\"},\"end\":{\"dateTime\":\"2026-10-26T10:00:00+09:00\"}}]}");
    var page = service.read(connection(), "TODAY", Map.of("date", "2026-10-26"));
    assertEquals("event1", page.items().getFirst().id());
    assertEquals("v1", page.items().getFirst().revision());
  }

  @Test
  void unknownFreeBusyStateIsNeverReportedAsAnAvailableDay() {
    SecretaryProvider service =
        service("{\"calendars\":{\"primary\":{\"errors\":[{\"reason\":\"notFound\"}]}}}");
    assertThrows(
        SecretaryProvider.Failure.class,
        () ->
            service.read(
                connection(),
                "FREEBUSY",
                Map.of("from", "2026-10-26T09:00:00+09:00", "to", "2026-10-26T18:00:00+09:00")));
  }

  @Test
  void calendarMutationRequiresACapturedEtagAndCannotOverwriteAnUnrelatedRevision() {
    SecretaryProvider service =
        service(
            "{\"id\":\"event1\",\"etag\":\"newer\",\"summary\":\"meeting\",\"status\":\"confirmed\",\"start\":{\"dateTime\":\"2026-10-26T09:00:00+09:00\"},\"end\":{\"dateTime\":\"2026-10-26T10:00:00+09:00\"}}");
    assertThrows(
        IllegalArgumentException.class,
        () -> service.write(connection(), "CALENDAR_CANCEL", Map.of("id", "event1"), "action"));
    var failure =
        assertThrows(
            SecretaryProvider.Failure.class,
            () ->
                service.write(
                    connection(),
                    "CALENDAR_CANCEL",
                    Map.of("id", "event1", "etag", "old"),
                    "action"));
    assertEquals("PRECONDITION_FAILED", failure.code());
  }
}
