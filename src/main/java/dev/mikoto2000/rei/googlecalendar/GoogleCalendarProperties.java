package dev.mikoto2000.rei.googlecalendar;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rei.google")
public record GoogleCalendarProperties(
    String applicationName,
    String credentialsPath,
    String tokensDirectory,
    CalendarProperties calendar,
    TaskProperties task
) {

  public record CalendarProperties(
      boolean enabled,
      String defaultCalendarId,
      String timeZone
  ) {}

  public record TaskProperties(
      boolean enabled
  ) {}
}
