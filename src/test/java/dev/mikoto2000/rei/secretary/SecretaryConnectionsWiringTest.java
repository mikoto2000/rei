package dev.mikoto2000.rei.secretary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.mikoto2000.rei.github.SecretaryGitHubProvider;
import dev.mikoto2000.rei.google.GoogleOAuthService;
import dev.mikoto2000.rei.googlecalendar.CalendarSecretaryProvider;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarProperties;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarService;
import dev.mikoto2000.rei.slack.SecretarySlackProvider;
import dev.mikoto2000.rei.storage.SqliteSecretaryDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationConfiguration;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

@Tag("integration")
class SecretaryConnectionsWiringTest {
  @TempDir Path root;

  @Test
  void allRealAdaptersStartTogetherWithoutCredentialsOrEnablingAnyService() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "test",
                  Map.of(
                      "rei.data-dir",
                      root.toString(),
                      "rei.secretary.enabled",
                      true,
                      "rei.secretary.github.enabled",
                      false,
                      "rei.secretary.slack.enabled",
                      false)));
      context.registerBean(Clock.class, Clock::systemUTC);
      context.registerBean(
          GoogleCalendarProperties.class,
          () ->
              new GoogleCalendarProperties(
                  "test",
                  root.resolve("missing-client.json").toString(),
                  root.resolve("missing-tokens").toString(),
                  new GoogleCalendarProperties.CalendarProperties(false, "primary", "UTC"),
                  new GoogleCalendarProperties.TaskProperties(false)));
      context.register(
          StorageMigrationConfiguration.class,
          SqliteSecretaryDatabase.class,
          SecretaryLedger.class,
          SecretaryConnections.class,
          GoogleOAuthService.class,
          GoogleCalendarService.class,
          CalendarSecretaryProvider.class,
          SecretaryGitHubProvider.class,
          SecretarySlackProvider.class);
      context.refresh();
      var providers = context.getBeansOfType(SecretaryProvider.class);
      assertEquals(4, providers.size());
      for (var provider : providers.values())
        for (var service : provider.services())
          assertEquals("DISABLED", provider.status(service).state());
      var service = context.getBean(SecretaryConnections.class);
      var connection =
          service.configure(
              new SecretaryConnections.Config(
                  "p",
                  "s",
                  "mail",
                  SecretaryProvider.Service.GMAIL,
                  "me",
                  false,
                  300,
                  false,
                  false,
                  "UTC"),
              -1);
      assertEquals("DISABLED", service.status("p", connection.id()).state());
      service.check();
      assertFalse(java.nio.file.Files.exists(root.resolve("missing-tokens")));
    }
  }
}
