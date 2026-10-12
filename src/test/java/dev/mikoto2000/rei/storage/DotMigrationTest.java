package dev.mikoto2000.rei.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class DotMigrationTest {
  @TempDir Path root;

  void versionSeven() throws Exception {
    try (var gate = new StorageMigrationCoordinator(root)) {
      gate.prepare();
    }
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db"));
        var s = c.createStatement()) {
      s.execute("INSERT INTO mcp_settings VALUES('test_preserved','existing')");
      for (String table :
          new String[] {"dot_requests", "dot_receipts", "dot_resume_intents", "dot_audit", "review_participants", "review_jobs"})
        s.execute("DROP TABLE " + table);
      s.execute("DELETE FROM storage_migrations WHERE version>=8");
      s.execute("PRAGMA user_version=7");
    }
  }

  @Test
  void verifiedSevenMigratesAtomicallyAndPreservesExistingData() throws Exception {
    versionSeven();
    try (var gate = new StorageMigrationCoordinator(root)) {
      assertEquals(StorageMigrationCoordinator.SCHEMA_VERSION, gate.prepare().schemaVersion());
    }
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db"));
        var s = c.createStatement();
        var r = s.executeQuery("SELECT value FROM mcp_settings WHERE name='test_preserved'")) {
      assertTrue(r.next());
      assertEquals("existing", r.getString(1));
    }
    try (var gate = new StorageMigrationCoordinator(root)) {
      assertEquals(StorageMigrationCoordinator.SCHEMA_VERSION, gate.prepare().schemaVersion());
    }
    assertTrue(Files.isDirectory(root.resolve(".storage/backups")));
  }

  @Test
  void interruptedMigrationRollsBackAndRestartDoesNotDuplicate() throws Exception {
    versionSeven();
    try (var gate =
        new StorageMigrationCoordinator(
            root,
            (stage, backup) -> {
              if (stage == StorageMigrationCoordinator.Stage.ROWS_IMPORTED)
                throw new IOException("injected crash");
            })) {
      assertThrows(IOException.class, gate::prepare);
    }
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db"));
        var s = c.createStatement();
        var r = s.executeQuery("PRAGMA user_version")) {
      assertTrue(r.next());
      assertEquals(7, r.getInt(1));
    }
    try (var gate = new StorageMigrationCoordinator(root)) {
      assertEquals(StorageMigrationCoordinator.SCHEMA_VERSION, gate.prepare().schemaVersion());
    }
  }
}
