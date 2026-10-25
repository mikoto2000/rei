package dev.mikoto2000.rei.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 状態表示移行の結果と状態の契約を検証するテスト。 */
class DotMigrationTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** {@code versionSeven} の条件を再現するため、検証用の操作や状態を扱う。 */
  void versionSeven() throws Exception {
    try (var gate = new StorageMigrationCoordinator(root)) {
      gate.prepare();
    }
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db"));
        var s = c.createStatement()) {
      s.execute("INSERT INTO mcp_settings VALUES('test_preserved','existing')");
      for (String table :
          new String[] {
            "dot_requests",
            "dot_receipts",
            "dot_resume_intents",
            "dot_audit",
            "review_participants",
            "review_jobs",
            "life_context",
            "secretary_tasks",
            "secretary_sources",
            "secretary_history",
            "secretary_inputs",
            "secretary_settings",
            "secretary_followup_state",
            "secretary_notices",
            "secretary_dependencies",
            "secretary_connections",
            "secretary_external_items",
            "secretary_external_events",
            "secretary_external_actions",
            "secretary_external_action_history",
            "secretary_external_waits",
            "secretary_autonomy_settings",
            "secretary_jobs",
            "secretary_job_history",
            "secretary_model_budget",
            "secretary_orchestration",
            "secretary_suggestions",
            "secretary_suggestion_history"
          }) s.execute("DROP TABLE " + table);
      s.execute("DELETE FROM storage_migrations WHERE version>=8");
      s.execute("PRAGMA user_version=7");
    }
  }

  /** 既存のデータを保持することを確認する。 */
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

  /** データ保存の共通処理の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
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
