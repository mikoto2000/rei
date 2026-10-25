package dev.mikoto2000.rei.secretary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.life.LifeContext;
import dev.mikoto2000.rei.storage.SqliteLifeContextStore;
import dev.mikoto2000.rei.storage.SqliteSecretaryDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

@Tag("integration")
class SecretaryGlobalBriefingsTest {
  @TempDir Path root;
  final SecretaryFollowupsTest.TestClock clock = new SecretaryFollowupsTest.TestClock();
  final List<SecretaryNotifications.Notice> received = new ArrayList<>();

  record Fixture(
      SecretaryFollowups followups, SecretaryGlobalBriefings global, SecretaryLedger ledger)
      implements AutoCloseable {
    public void close() {
      followups.close();
    }
  }

  Fixture fixture(List<String> projects) throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    var db = new SqliteSecretaryDatabase(root.toString());
    var ledger = new SecretaryLedger(db, clock);
    var followups = new SecretaryFollowups(db, ledger, clock, received::add, true);
    var beans = new StaticListableBeanFactory();
    beans.addBean("reports", followups);
    var global =
        new SecretaryGlobalBriefings(
            db,
            beans.getBeanProvider(SecretaryReports.class),
            clock,
            new GlobalBriefingProperties(true, "08:00", "19:00", "Asia/Tokyo", projects));
    beans.addBean("global", global);
    followups.setGlobalBriefings(beans.getBeanProvider(SecretaryGlobalBriefings.class));
    return new Fixture(followups, global, ledger);
  }

  @Test
  void oneMorningAndOneEveningReportCombineOnlyTheSelectedProjectsWithoutLegacyDuplicates()
      throws Exception {
    clock.now = Instant.parse("2026-10-26T10:00:00Z");
    try (var f = fixture(List.of("p", "q"))) {
      for (String project : List.of("p", "q", "excluded"))
        f.ledger()
            .create(
                project,
                "one",
                project + " private task",
                SecretaryTask.Intent.TODO,
                null,
                2,
                List.of(),
                List.of());
      for (String project : List.of("p", "q"))
        f.followups()
            .configure(
                new SecretaryFollowups.Settings(
                    project, true, true, "08:00", "19:00", "Asia/Tokyo", 86400, 3, "INBOX"));
      f.followups().poll();
      f.followups().poll();
      assertEquals(2, received.size());
      assertTrue(received.stream().allMatch(SecretaryNotifications.Notice::global));
      assertEquals(2, f.global().history(100).size());
      var report = f.global().report(null, false);
      assertEquals(java.util.Set.of("p", "q"), report.projects().keySet());
      assertTrue(report.projects().values().stream().allMatch(r -> r.zone().equals("Asia/Tokyo")));
      assertFalse(report.toString().contains("excluded private task"));
      assertTrue(f.followups().briefingHistory("p", 100).isEmpty());
    }
    try (var f = fixture(List.of("p", "q"))) {
      f.followups().poll();
      assertEquals(2, received.size());
      assertEquals(2, f.global().history(100).size());
    }
  }

  @Test
  void workHoldsTheSingleGlobalNoticeAndReleaseDoesNotDependOnAProjectConfiguration()
      throws Exception {
    try (var f = fixture(List.of("p"))) {
      var life = new LifeContext(new SqliteLifeContextStore(root.toString()), clock);
      f.followups().setLifeContext(life);
      f.global().setLifeContext(life);
      f.followups().poll();
      f.followups().poll();
      assertTrue(received.isEmpty());
      var held = f.followups().history(SecretaryNotifications.GLOBAL_SCOPE, 100);
      assertEquals(1, held.size());
      assertEquals("HELD", held.getFirst().status());
      assertEquals(0, held.getFirst().attempts());
      clock.now = Instant.parse("2026-10-26T09:30:00Z"); // 18:30: before evening time
      f.followups().poll();
      f.followups().poll();
      assertEquals(1, received.size());
      assertEquals("GLOBAL_BRIEFING", received.getFirst().kind());
    }
  }

  @Test
  void manualGlobalGenerationIsIdempotentAndItsHistoryIsSeparateFromProjectHistory()
      throws Exception {
    try (var f = fixture(List.of("p"))) {
      var saved = f.global().generate(null, false, "manual-one");
      assertEquals(saved, f.global().generate(null, false, "manual-one"));
      assertEquals(saved, f.global().show(saved.id()));
      assertTrue(received.isEmpty());
      assertEquals(1, f.global().history(20).size());
      assertEquals(1, f.global().deleteHistory());
      assertTrue(f.global().history(20).isEmpty());
    }
  }

  @Test
  void removingASelectedProjectSuppressesItsPendingGlobalSnapshotAfterRestart() throws Exception {
    try (var f = fixture(List.of("p", "q"))) {
      var life = new LifeContext(new SqliteLifeContextStore(root.toString()), clock);
      f.followups().setLifeContext(life);
      f.global().setLifeContext(life);
      f.followups().poll();
      assertTrue(received.isEmpty());
    }
    clock.now = Instant.parse("2026-10-26T09:30:00Z");
    try (var f = fixture(List.of("p"))) {
      f.followups().poll();
      assertTrue(received.isEmpty());
      assertTrue(
          f.followups().history(SecretaryNotifications.GLOBAL_SCOPE, 100).stream()
              .allMatch(n -> n.status().equals("SUPPRESSED")));
    }
  }

  @Test
  void aFailedProjectIsNamedAndDoesNotDiscardTheOtherProject() throws Exception {
    try (var f = fixture(List.of("p", "q"))) {
      var beans = new StaticListableBeanFactory();
      beans.addBean(
          "reports",
          new SecretaryReports() {
            public SecretaryFollowups.Settings settings(String project) {
              return f.followups().settings(project);
            }

            public SecretaryFollowups.Report report(
                String project, java.time.LocalDate date, boolean review) {
              if (project.equals("q")) throw new IllegalStateException("unavailable");
              return f.followups().report(project, date, review);
            }
          });
      var global =
          new SecretaryGlobalBriefings(
              new SqliteSecretaryDatabase(root.toString()),
              beans.getBeanProvider(SecretaryReports.class),
              clock,
              new GlobalBriefingProperties(false, "", "", "", List.of("p", "q")));
      var report = global.report(null, false);
      assertTrue(report.partial());
      assertEquals(List.of("q"), report.unavailable());
      assertEquals(java.util.Set.of("p"), report.projects().keySet());
    }
  }

  @Test
  void normalCliAndApiOfferTheGlobalViewWithoutAProjectAndRetainExplicitProjectReads()
      throws Exception {
    try (var f = fixture(List.of("p", "q"))) {
      var beans = new StaticListableBeanFactory();
      beans.addBean("followups", f.followups());
      var command = new SecretaryCommand();
      command.setFollowups(beans.getBeanProvider(SecretaryFollowups.class));
      var cli = new picocli.CommandLine(command);
      var text = new java.io.StringWriter();
      cli.setOut(new java.io.PrintWriter(text));
      cli.setErr(new java.io.PrintWriter(text));
      assertEquals(0, cli.execute("briefing"));
      assertTrue(text.toString().contains("projects"));
      var mvc =
          org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                  new dev.mikoto2000.rei.web.SecretaryFollowupController(
                      beans.getBeanProvider(SecretaryFollowups.class)))
              .setControllerAdvice(new dev.mikoto2000.rei.web.ApiExceptionHandler())
              .build();
      mvc.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                  "/api/v1/secretary/briefing"))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                      "$.projects.p")
                  .exists());
      mvc.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                      "/api/v1/secretary/briefing")
                  .param("projectId", "p"))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                      "$.sections")
                  .exists());
    }
  }
}
