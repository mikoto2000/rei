package dev.mikoto2000.rei.secretary;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Registered projects share a briefing unless explicitly separated. */
@ConfigurationProperties(prefix = "rei.secretary.briefings", ignoreUnknownFields = false)
public record GlobalBriefingProperties(
    boolean enabled,
    String morningTime,
    String eveningTime,
    String zone,
    List<String> projects,
    List<String> independentProjects) {
  public GlobalBriefingProperties(
      boolean enabled, String morningTime, String eveningTime, String zone, List<String> projects) {
    this(enabled, morningTime, eveningTime, zone, projects, List.of());
  }

  @org.springframework.boot.context.properties.bind.ConstructorBinding
  public GlobalBriefingProperties {
    morningTime = morningTime == null ? "" : morningTime;
    eveningTime = eveningTime == null ? "" : eveningTime;
    zone = zone == null ? "" : zone;
    if (!morningTime.isEmpty()) LocalTime.parse(morningTime);
    if (!eveningTime.isEmpty()) LocalTime.parse(eveningTime);
    if (!zone.isEmpty()) ZoneId.of(zone);
    projects = projects == null ? List.of() : List.copyOf(projects);
    independentProjects =
        independentProjects == null ? List.of() : List.copyOf(independentProjects);
    validate(projects);
    validate(independentProjects);
    if (enabled && morningTime.isEmpty() && eveningTime.isEmpty())
      throw new IllegalArgumentException(
          "Enabled global briefings require an explicit morning-time or evening-time");
  }

  private static void validate(List<String> projects) {
    if (projects.size() > 16
        || projects.stream()
            .anyMatch(
                p ->
                    p.isBlank()
                        || p.length() > 128
                        || p.equals(SecretaryNotifications.GLOBAL_SCOPE))
        || projects.stream().distinct().count() != projects.size())
      throw new IllegalArgumentException(
          "Select at most 16 unique project IDs for global briefings");
  }
}
