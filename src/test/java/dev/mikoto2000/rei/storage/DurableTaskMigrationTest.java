package dev.mikoto2000.rei.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DurableTaskMigrationTest {
  @TempDir Path root;

  private void schema19() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) { migration.prepare(); }
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db")); var sql = db.createStatement()) {
      StorageSchemaFixture.removeDurableSchema(sql);
      sql.execute("DELETE FROM storage_migrations WHERE version>=20");
      sql.execute("PRAGMA user_version=19");
      sql.execute("INSERT INTO review_jobs VALUES('legacy','project','session','2026-10-29','preserved legacy bytes')");
    }
  }

  @Test
  void schema19UpgradeRollbackAndRetryPreserveLegacyReviewRows() throws Exception {
    schema19();
    try (var failed = new StorageMigrationCoordinator(root, (stage, backup) -> {
      if (stage == StorageMigrationCoordinator.Stage.SCHEMA_WRITTEN) throw new IOException("injected");
    })) {
      assertThatThrownBy(failed::prepare).isInstanceOf(IOException.class);
    }
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db")); var sql = db.createStatement()) {
      try (var rows = sql.executeQuery("PRAGMA user_version")) { assertThat(rows.getInt(1)).isEqualTo(19); }
      try (var rows = sql.executeQuery("SELECT COUNT(*) FROM sqlite_schema WHERE name='durable_tasks'")) { assertThat(rows.getInt(1)).isZero(); }
    }
    try (var migration = new StorageMigrationCoordinator(root)) { assertThat(migration.prepare().backup()).isNotNull(); }
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db")); var sql = db.createStatement();
        var rows = sql.executeQuery("SELECT record FROM review_jobs WHERE id='legacy'")) {
      assertThat(rows.getString(1)).isEqualTo("preserved legacy bytes");
    }
  }

  @Test
  void foreignPreexistingTableIsRejectedInsteadOfAdopted() throws Exception {
    schema19();
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db")); var sql = db.createStatement()) {
      sql.execute("CREATE TABLE durable_tasks(foreign_data TEXT)");
      sql.execute("INSERT INTO durable_tasks VALUES('retain')");
    }
    try (var migration = new StorageMigrationCoordinator(root)) { assertThatThrownBy(migration::prepare).isInstanceOf(IOException.class); }
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db")); var sql = db.createStatement();
        var rows = sql.executeQuery("SELECT foreign_data FROM durable_tasks")) { assertThat(rows.getString(1)).isEqualTo("retain"); }
  }
}
