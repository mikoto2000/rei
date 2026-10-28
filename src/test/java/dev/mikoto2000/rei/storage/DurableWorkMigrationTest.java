package dev.mikoto2000.rei.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DurableWorkMigrationTest {
  @TempDir Path root;

  @Test
  void schema20UpgradePreservesSnapshotsAndRollsBackBeforeRetry() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) { migration.prepare(); }
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db")); var sql = db.createStatement()) {
      StorageSchemaFixture.removeDurableSchema(sql);
      DurableTaskMigration.apply(db);
      sql.execute("DELETE FROM storage_migrations WHERE version>=21");
      sql.execute("PRAGMA user_version=20");
      sql.execute("INSERT INTO durable_tasks(id,project,session,root,goal,snapshot,state) VALUES('task','p','s','root',NULL,'snapshot','ACTIVE')");
      sql.execute("INSERT INTO durable_attempts(task,run,started,state) VALUES('task','run',1,'ACTIVE')");
      try (var insert = db.prepareStatement("INSERT INTO durable_files(snapshot,path,version,content,text,bytes,reason,error) VALUES('snapshot','a.java','hash',?,?,?, '', '')")) {
        byte[] bytes = "a🙂".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        insert.setBytes(1, bytes);
        insert.setString(2, "a🙂");
        insert.setInt(3, bytes.length);
        insert.executeUpdate();
      }
    }
    try (var migration = new StorageMigrationCoordinator(root, (stage, backup) -> {
      if (stage == StorageMigrationCoordinator.Stage.SCHEMA_WRITTEN) throw new IOException("injected");
    })) { assertThatThrownBy(migration::prepare).isInstanceOf(IOException.class); }
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db")); var sql = db.createStatement()) {
      try (var row = sql.executeQuery("PRAGMA user_version")) { assertThat(row.getInt(1)).isEqualTo(20); }
      try (var row = sql.executeQuery("SELECT COUNT(*) FROM pragma_table_info('durable_files') WHERE name='chars'")) { assertThat(row.getInt(1)).isZero(); }
    }
    try (var migration = new StorageMigrationCoordinator(root)) { assertThat(migration.prepare().backup()).isNotNull(); }
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db")); var sql = db.createStatement()) {
      try (var row = sql.executeQuery("SELECT text,chars FROM durable_files")) {
        assertThat(row.getString(1)).isEqualTo("a🙂");
        assertThat(row.getInt(2)).isEqualTo(3); // Java coordinates, not SQLite Unicode code-point length 2
      }
      try (var row = sql.executeQuery("SELECT current_run FROM durable_tasks")) { assertThat(row.getString(1)).isEqualTo("run"); }
    }
  }
}
