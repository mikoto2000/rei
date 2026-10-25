package dev.mikoto2000.rei.secretary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.mikoto2000.rei.attention.AttentionRepository;
import dev.mikoto2000.rei.storage.SqliteSecretaryDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import dev.mikoto2000.rei.web.ApiExceptionHandler;
import dev.mikoto2000.rei.web.SecretaryFollowupController;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@Tag("integration")
class SecretaryFollowupIntegrationTest {
  @Test
  void destinationChangesSuppressPendingNoticesAndExhaustedCrashLeasesStopWithinProject()
      throws Exception {
    var db = database();
    var ledger = new SecretaryLedger(db, clock);
    ledger.create(
        "p",
        "one",
        "release",
        SecretaryTask.Intent.TODO,
        LocalDate.of(2026, 10, 26),
        1,
        List.of(),
        List.of());
    try (var service =
        new SecretaryFollowups(
            db,
            ledger,
            clock,
            n -> {
              throw new IllegalStateException("offline");
            },
            true)) {
      service.configure(settings());
      service.poll();
    }
    Clock later = Clock.fixed(clock.instant().plusSeconds(3600), ZoneOffset.UTC);
    try (var service =
        new SecretaryFollowups(
            db,
            ledger,
            later,
            n -> {
              throw new AssertionError("Old destination must not be delivered");
            },
            true)) {
      service.configure(
          new SecretaryFollowups.Settings("p", true, true, "", "", "UTC", 86400, 1, "DELIVERY"));
      service.checkNow("p");
      assertEquals("SUPPRESSED", service.history("p", 100).getFirst().status());
      db.transaction(
          connection -> {
            try (var sql = connection.createStatement()) {
              sql.executeUpdate("UPDATE secretary_notices SET status='SENDING',attempts=5,next=0");
            }
            var other =
                new SecretaryNotifications.Notice(
                    java.util.UUID.randomUUID().toString(),
                    "q",
                    null,
                    "BRIEFING",
                    "brief",
                    3,
                    "INBOX",
                    clock.instant());
            try (var insert =
                connection.prepareStatement(
                    "INSERT INTO secretary_notices(id,project,task,kind,status,attempts,next,record,priority,created) VALUES(?,'q',NULL,'BRIEFING','SENDING',5,0,?,3,0)")) {
              insert.setString(1, other.id());
              insert.setString(2, SecretaryJson.JSON.writeValueAsString(other));
              insert.executeUpdate();
            }
            return null;
          });
      service.checkNow("p");
      assertEquals("FAILED", service.history("p", 100).getFirst().status());
      assertEquals("delivery_result_unconfirmed", service.history("p", 100).getFirst().error());
      assertEquals("SENDING", service.history("q", 100).getFirst().status());
    }
  }

  @TempDir Path root;
  final Clock clock = Clock.fixed(Instant.parse("2026-10-26T01:00:00Z"), ZoneOffset.UTC);

  SecretaryDatabase database() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    return new SqliteSecretaryDatabase(root.toString());
  }

  SecretaryFollowups.Settings settings() {
    return new SecretaryFollowups.Settings("p", true, true, "", "", "UTC", 86400, 1, "INBOX");
  }

  @Test
  void localIngressIsIdempotentAndConcurrentPollsHaveOneDurableNotice() throws Exception {
    var db = database();
    var ledger = new SecretaryLedger(db, clock);
    ledger.create(
        "p",
        "one",
        "release",
        SecretaryTask.Intent.TODO,
        LocalDate.of(2026, 10, 26),
        1,
        List.of(),
        List.of());
    var inbox =
        new AttentionRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + root.resolve("inbox.db")), clock);
    try (var a = new SecretaryFollowups(db, ledger, clock, inbox::createSecretary, true);
        var b = new SecretaryFollowups(db, ledger, clock, inbox::createSecretary, true)) {
      a.configure(settings());
      try (var workers = Executors.newFixedThreadPool(2)) {
        var results =
            workers.invokeAll(
                List.<java.util.concurrent.Callable<Void>>of(
                    () -> {
                      a.poll();
                      return null;
                    },
                    () -> {
                      b.poll();
                      return null;
                    }));
        for (var result : results) result.get();
      }
      assertEquals(1, a.history("p", 100).size());
      assertEquals(1, inbox.list("p").size());
      var notice = a.history("p", 100).getFirst().notice();
      inbox.createSecretary(notice);
      assertEquals(1, inbox.list("p").size());
      assertEquals(null, inbox.list("p").getFirst().sessionId());
    }
  }

  @Test
  void authoritativeDependencyRepositoryRepairsMissedEventsAndCannotUseStaleCacheAsProof()
      throws Exception {
    var db = database();
    var ledger = new SecretaryLedger(db, clock);
    var task =
        ledger.create(
            "p",
            "one",
            "release",
            SecretaryTask.Intent.TODO,
            null,
            1,
            List.of(),
            List.of("completion:dependency:d1"));
    try (var service = new SecretaryFollowups(db, ledger, clock, n -> {}, true)) {
      service.configure(settings());
      service.observeDependency("p", "d1", "COMPLETED", 100);
      var beans = new StaticListableBeanFactory();
      beans.addBean(
          "source",
          (SecretaryDependencies)
              (project, id) -> Optional.of(new SecretaryDependencies.Fact("WAITING", 1)));
      service.setDependencies(beans.getBeanProvider(SecretaryDependencies.class));
      service.poll();
      assertEquals(SecretaryTask.Status.OPEN, ledger.get("p", task.id()).status());
      var ready = new StaticListableBeanFactory();
      ready.addBean(
          "source",
          (SecretaryDependencies)
              (project, id) -> Optional.of(new SecretaryDependencies.Fact("COMPLETED", 2)));
      service.setDependencies(ready.getBeanProvider(SecretaryDependencies.class));
      service.poll();
      assertEquals(SecretaryTask.Status.DONE, ledger.get("p", task.id()).status());
    }
  }

  @Test
  void reportsAndStrictConfigurationApiAreAvailableWithoutEnablingBackgroundExecution()
      throws Exception {
    var db = database();
    try (var service =
        new SecretaryFollowups(db, new SecretaryLedger(db, clock), clock, n -> {}, false)) {
      var beans = new StaticListableBeanFactory();
      beans.addBean("service", service);
      var mvc =
          MockMvcBuilders.standaloneSetup(
                  new SecretaryFollowupController(beans.getBeanProvider(SecretaryFollowups.class)))
              .setControllerAdvice(new ApiExceptionHandler())
              .build();
      mvc.perform(get("/api/v1/secretary/briefing").param("projectId", "p"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.date").value("2026-10-26"));
      mvc.perform(
              put("/api/v1/secretary/settings")
                  .contentType("application/json")
                  .content("{\"projectId\":\"p\",\"enabled\":true,\"briefingTime\":\"09:00\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.zone").value("Z"));
      mvc.perform(
              put("/api/v1/secretary/settings")
                  .contentType("application/json")
                  .content("{\"projectId\":\"p\",\"zone\":\"invalid\"}"))
          .andExpect(status().isBadRequest());
      mvc.perform(
              put("/api/v1/secretary/settings")
                  .contentType("application/json")
                  .content("{\"projectId\":\"p\",\"root\":\"other\"}"))
          .andExpect(status().isBadRequest());
      assertTrue(service.history("p", 100).isEmpty());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              service.configure(
                  new SecretaryFollowups.Settings(
                      "p", true, true, "99:00", "", "UTC", 60, 1, "INBOX")));
    }
  }
}
