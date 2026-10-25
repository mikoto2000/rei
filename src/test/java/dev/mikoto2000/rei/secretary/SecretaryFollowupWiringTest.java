package dev.mikoto2000.rei.secretary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.mikoto2000.rei.attention.AttentionRepository;
import dev.mikoto2000.rei.attention.AttentionService;
import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.secretaryevents.SecretaryDependencyIntake;
import dev.mikoto2000.rei.storage.SqliteSecretaryDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationConfiguration;
import dev.mikoto2000.rei.temporal.MonotonicTimeSource;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("integration")
class SecretaryFollowupWiringTest {
  @TempDir Path root;

  @Test
  void springResolvesTheRegisteredProjectsAndIndependentConfigurationWithoutAnAllowlist()
      throws Exception {
    var registry =
        new dev.mikoto2000.rei.core.project.ProjectRegistry(root.resolve("projects.json"));
    var p = registry.resolve(java.nio.file.Files.createDirectories(root.resolve("p"))).id();
    var q = registry.resolve(java.nio.file.Files.createDirectories(root.resolve("q"))).id();
    var received = new java.util.ArrayList<SecretaryNotifications.Notice>();
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
                      "rei.secretary.followups-enabled",
                      true,
                      "rei.secretary.briefings.enabled",
                      true,
                      "rei.secretary.briefings.morning-time",
                      "08:30",
                      "rei.secretary.briefings.zone",
                      "Asia/Tokyo",
                      "rei.secretary.briefings.independent-projects[0]",
                      q)));
      context.registerBean(
          Clock.class,
          () ->
              Clock.fixed(
                  java.time.Instant.parse("2026-10-25T23:30:00Z"),
                  java.time.ZoneId.of("Asia/Tokyo")));
      context.registerBean(SecretaryNotifications.class, () -> received::add);
      context.register(
          StorageMigrationConfiguration.class,
          SqliteSecretaryDatabase.class,
          SecretaryLedger.class,
          SecretaryFollowups.class,
          SecretaryGlobalBriefings.class,
          dev.mikoto2000.rei.application.project.ProjectQueryService.class);
      context.refresh();
      var global = context.getBean(SecretaryGlobalBriefings.class);
      assertEquals(java.util.Set.of(p, q), java.util.Set.copyOf(global.settings().projects()));
      assertEquals(List.of(q), global.settings().independentProjects());
      context.getBean(SecretaryFollowups.class).poll();
      assertEquals(2, received.size());
      assertEquals(java.util.Set.of(p), global.report(null, false).projects().keySet());
    }
  }

  @Test
  void springResolvesExistingAttentionIngressAndPublishesProjectOnlyNotifications() {
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
                      "rei.secretary.followups-enabled",
                      true)));
      context.registerBean(Clock.class, Clock::systemUTC);
      context.registerBean(MonotonicTimeSource.class, MonotonicTimeSource::system);
      context.registerBean(AgentEventBus.class, InMemoryAgentEventBus::new);
      context.registerBean(
          "memoryConsolidationDataSource",
          javax.sql.DataSource.class,
          () -> new DriverManagerDataSource("jdbc:sqlite:" + root.resolve("inbox.db")));
      context.register(
          StorageMigrationConfiguration.class,
          SqliteSecretaryDatabase.class,
          SecretaryLedger.class,
          SecretaryFollowups.class,
          SecretaryGlobalBriefings.class,
          SecretaryOrchestrator.class,
          AttentionRepository.class,
          AttentionService.class,
          SecretaryDependencyIntake.class);
      context.refresh();
      assertSame(
          context.getBean(AttentionService.class), context.getBean(SecretaryNotifications.class));
      var ledger = context.getBean(SecretaryLedger.class);
      ledger.create(
          "p",
          "one",
          "release",
          SecretaryTask.Intent.TODO,
          LocalDate.now(Clock.systemUTC()),
          1,
          List.of(),
          List.of());
      var service = context.getBean(SecretaryFollowups.class);
      service.configure(
          new SecretaryFollowups.Settings("p", true, true, "", "", "UTC", 86400, 1, "INBOX"));
      service.checkNow("p");
      assertEquals("DELIVERED", service.history("p", 10).getFirst().status());
      assertEquals(1, context.getBean(AttentionRepository.class).list("p").size());
    }
  }
}
