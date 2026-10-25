package dev.mikoto2000.rei.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.secretary.SecretaryLedger;
import dev.mikoto2000.rei.secretary.SecretaryTask;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class SecretaryAutonomyMigrationTest {
  @TempDir Path root;

  @Test
  void schemaThirteenUpgradePreservesAcceptedTasksAndProposalSettings() throws Exception {
    try (var migrations = new StorageMigrationCoordinator(root)) {
      migrations.prepare();
    }
    var db = new SqliteSecretaryDatabase(root.toString());
    var clock = Clock.systemUTC();
    var ledger = new SecretaryLedger(db, clock);
    var task =
        ledger.create(
            "p", "one", "research", SecretaryTask.Intent.TODO, null, 2, List.of(), List.of());
    try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db"));
        var sql = c.createStatement()) {
      for (var table :
          List.of(
              "life_context",
              "secretary_connections",
              "secretary_external_items",
              "secretary_external_events",
              "secretary_external_actions",
              "secretary_external_action_history",
              "secretary_external_waits",
              "secretary_autonomy_settings",
              "secretary_jobs",
              "secretary_job_history",
              "secretary_model_budget")) sql.execute("DROP TABLE " + table);
      sql.execute("DELETE FROM storage_migrations WHERE version>=14");
      sql.execute("PRAGMA user_version=13");
    }
    try (var migrations = new StorageMigrationCoordinator(root)) {
      assertNotNull(migrations.prepare().backup());
    }
    assertEquals(task, ledger.get("p", task.id()));
    try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db"));
        var sql = c.createStatement();
        var rows = sql.executeQuery("SELECT COUNT(*) FROM secretary_jobs")) {
      assertTrue(rows.next());
      assertEquals(0, rows.getInt(1));
    }
  }
}
