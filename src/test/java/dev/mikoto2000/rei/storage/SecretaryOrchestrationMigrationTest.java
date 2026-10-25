package dev.mikoto2000.rei.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.mikoto2000.rei.secretary.SecretaryFollowups;
import dev.mikoto2000.rei.secretary.SecretaryLedger;
import dev.mikoto2000.rei.secretary.SecretaryTask;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class SecretaryOrchestrationMigrationTest {
  @TempDir Path root;

  @Test
  void schemaTwelveUpgradePreservesSettingsLedgerAndDeliveredOutbox() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    var db = new SqliteSecretaryDatabase(root.toString());
    var clock = Clock.fixed(Instant.parse("2026-10-26T00:00:00Z"), ZoneOffset.UTC);
    var ledger = new SecretaryLedger(db, clock);
    var task =
        ledger.create(
            "p",
            "one",
            "release",
            SecretaryTask.Intent.TODO,
            LocalDate.of(2026, 10, 26),
            2,
            List.of(),
            List.of());
    try (var followups = new SecretaryFollowups(db, ledger, clock, n -> {}, false)) {
      var settings =
          new SecretaryFollowups.Settings("p", true, true, "", "", "UTC", 86400, 3, "INBOX");
      followups.configure(settings);
      followups.checkNow("p");
      var delivery = followups.history("p", 10).getFirst();
      try (var connection =
              DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db"));
          var sql = connection.createStatement()) {
        for (String table :
            List.of(
                "secretary_autonomy_settings",
                "secretary_jobs",
                "secretary_job_history",
                "secretary_model_budget",
                "secretary_orchestration",
                "secretary_suggestions",
                "secretary_suggestion_history")) sql.execute("DROP TABLE " + table);
        sql.execute("DROP INDEX secretary_notice_created");
        sql.execute("DROP INDEX secretary_notice_held");
        sql.execute("DELETE FROM storage_migrations WHERE version>=13");
        sql.execute("PRAGMA user_version=12");
      }
      try (var migration = new StorageMigrationCoordinator(root)) {
        assertNotNull(migration.prepare().backup());
      }
      assertEquals(task, ledger.get("p", task.id()));
      assertEquals(settings, followups.settings("p"));
      assertEquals(delivery, followups.history("p", 10).getFirst());
    }
  }
}
