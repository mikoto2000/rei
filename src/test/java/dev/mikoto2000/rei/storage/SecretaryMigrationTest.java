package dev.mikoto2000.rei.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class SecretaryMigrationTest {
  @TempDir Path root;

  @Test
  void versionTenUpgradeBacksUpAndPreservesExistingDataAndReopens() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db"));
        var sql = db.createStatement()) {
      // Reconstruct the immediately previous schema, retaining the existing migration markers.
      sql.execute("DROP TABLE secretary_tasks");
      sql.execute("DROP TABLE secretary_sources");
      sql.execute("DROP TABLE secretary_history");
      sql.execute("DROP TABLE secretary_inputs");
      sql.execute("DROP TABLE secretary_settings");
      sql.execute("DROP TABLE secretary_followup_state");
      sql.execute("DROP TABLE secretary_notices");
      sql.execute("DROP TABLE secretary_dependencies");
      sql.execute("DELETE FROM storage_migrations WHERE version>=11");
      sql.execute("PRAGMA user_version=10");
      sql.execute("INSERT INTO review_participants VALUES('job','project','conversation','run')");
    }
    try (var migration = new StorageMigrationCoordinator(root)) {
      var result = migration.prepare();
      assertEquals(StorageMigrationCoordinator.SCHEMA_VERSION, result.schemaVersion());
      assertNotNull(result.backup());
      StorageBackup.verify(result.backup());
    }
    var database = new StorageDatabase(root);
    assertEquals(
        "run",
        database.read(
            db -> {
              SecretaryMigration.verify(db);
              try (var sql = db.createStatement();
                  var rows =
                      sql.executeQuery("SELECT run FROM review_participants WHERE job='job'")) {
                rows.next();
                return rows.getString(1);
              }
            }));
    try (var migration = new StorageMigrationCoordinator(root)) {
      assertNull(migration.prepare().backup());
    }
  }
}
