package dev.mikoto2000.rei.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class SecretaryConnectionsMigrationTest {
  @TempDir Path root;

  @Test
  void upgradesSchemaFourteenWithoutChangingLedgerOrAutonomyBudgets() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    var ledger =
        new dev.mikoto2000.rei.secretary.SecretaryLedger(
            new SqliteSecretaryDatabase(root.toString()), java.time.Clock.systemUTC());
    var task =
        ledger.create(
            "p",
            "keep",
            "retained",
            dev.mikoto2000.rei.secretary.SecretaryTask.Intent.TODO,
            null,
            2,
            java.util.List.of(),
            java.util.List.of());
    try (var db =
            java.sql.DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db"));
        var sql = db.createStatement()) {
      for (var table :
          java.util.List.of(
              "secretary_briefings",
              "life_context",
              "secretary_connections",
              "secretary_external_items",
              "secretary_external_events",
              "secretary_external_actions",
              "secretary_external_action_history",
              "secretary_external_waits")) sql.execute("DROP TABLE " + table);
      sql.execute("DROP INDEX secretary_jobs_updated");
      sql.execute("INSERT INTO secretary_model_budget VALUES('p','2026-10-26',7)");
      sql.execute("DELETE FROM storage_migrations WHERE version>=15");
      sql.execute("PRAGMA user_version=14");
    }
    try (var migration = new StorageMigrationCoordinator(root)) {
      assertNotNull(migration.prepare().backup());
    }
    assertEquals(task, ledger.get("p", task.id()));
    try (var db =
            java.sql.DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db"));
        var sql = db.createStatement();
        var r = sql.executeQuery("SELECT calls FROM secretary_model_budget WHERE project='p'")) {
      assertTrue(r.next());
      assertEquals(7, r.getInt(1));
      SecretaryConnectionsMigration.verify(db);
    }
    try (var migration = new StorageMigrationCoordinator(root)) {
      assertNull(migration.prepare().backup());
    }
  }
}
