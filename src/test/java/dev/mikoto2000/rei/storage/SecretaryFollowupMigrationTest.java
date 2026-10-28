package dev.mikoto2000.rei.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.mikoto2000.rei.secretary.SecretaryLedger;
import dev.mikoto2000.rei.secretary.SecretaryTask;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class SecretaryFollowupMigrationTest {
  @TempDir Path root;

  @Test
  void versionElevenUpgradePreservesLedgerHistoryAndSources() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    var ledger =
        new SecretaryLedger(new SqliteSecretaryDatabase(root.toString()), Clock.systemUTC());
    var task =
        ledger.create(
            "p", "source", "keep me", SecretaryTask.Intent.TODO, null, 3, List.of(), List.of());
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db"));
        var sql = db.createStatement()) {
      sql.execute("DROP TABLE secretary_settings");
      sql.execute("DROP TABLE secretary_followup_state");
      sql.execute("DROP TABLE secretary_notices");
      sql.execute("DROP TABLE secretary_dependencies");
      sql.execute("DROP TABLE secretary_briefings");
      sql.execute("DROP TABLE life_context");
      sql.execute("DROP TABLE secretary_connections");
      sql.execute("DROP TABLE secretary_external_items");
      sql.execute("DROP TABLE secretary_external_events");
      sql.execute("DROP TABLE secretary_external_actions");
      sql.execute("DROP TABLE secretary_external_action_history");
      sql.execute("DROP TABLE secretary_external_waits");
      sql.execute("DROP TABLE secretary_autonomy_settings");
      sql.execute("DROP TABLE secretary_jobs");
      sql.execute("DROP TABLE secretary_job_history");
      sql.execute("DROP TABLE secretary_model_budget");
      sql.execute("DROP TABLE secretary_orchestration");
      sql.execute("DROP TABLE secretary_suggestions");
      sql.execute("DROP TABLE secretary_suggestion_history");
      sql.execute("DELETE FROM storage_migrations WHERE version>=12");
      sql.execute("DROP TABLE reading_state");
      StorageSchemaFixture.removeDurableSchema(sql);
      sql.execute("PRAGMA user_version=11");
    }
    try (var migration = new StorageMigrationCoordinator(root)) {
      assertNotNull(migration.prepare().backup());
    }
    assertEquals(task, ledger.get("p", task.id()));
    assertEquals(1, ledger.history("p", task.id()).size());
    assertEquals(List.of("source#0"), ledger.sources("p", task.id()));
  }
}
