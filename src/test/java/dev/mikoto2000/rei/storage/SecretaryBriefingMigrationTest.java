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
class SecretaryBriefingMigrationTest {
  @TempDir Path root;

  @Test
  void upgradesSixteenWithBackupAndPreservesLifeSettings() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    var store = new SqliteLifeContextStore(root.toString());
    var life = new dev.mikoto2000.rei.life.LifeContext(store, java.time.Clock.systemUTC());
    life.configure(life.settings());
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db"));
        var q = db.createStatement()) {
      q.execute("DROP TABLE secretary_briefings");
      q.execute("DELETE FROM storage_migrations WHERE version>=17");
      q.execute("DROP TABLE reading_state");
      StorageSchemaFixture.removeDurableSchema(q);
      q.execute("PRAGMA user_version=16");
    }
    try (var migration = new StorageMigrationCoordinator(root)) {
      assertNotNull(migration.prepare().backup());
    }
    assertEquals(life.settings(), store.load());
    try (var migration = new StorageMigrationCoordinator(root)) {
      assertNull(migration.prepare().backup());
    }
  }
}
