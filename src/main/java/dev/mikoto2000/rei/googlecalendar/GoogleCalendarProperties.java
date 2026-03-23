package dev.mikoto2000.rei.googlecalendar;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rei.google-calendar")
public record GoogleCalendarProperties(
    boolean enabled,
    String applicationName,
    String credentialsPath,
    String tokensDirectory,
    String defaultCalendarId
) {}
