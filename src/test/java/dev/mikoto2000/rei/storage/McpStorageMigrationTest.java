package dev.mikoto2000.rei.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/** MCP保存移行の結果と状態の契約を検証するテスト。 */
class McpStorageMigrationTest {
  /** 単一呼び出し元要求を保存することを確認する。 */
  @Test
  void storesOnePrincipalRequestAndPreservesExistingAdmission() throws Exception {
    try (var db = DriverManager.getConnection("jdbc:sqlite::memory:");
        var sql = db.createStatement()) {
      ConversationAdmissionMigration.apply(db);
      McpStorageMigration.apply(db);
      McpStorageMigration.verify(db);
      sql.execute(
          "INSERT INTO mcp_requests VALUES('local-mcp','key','fingerprint','project','session','run','2026-01-01','2026-04-01',NULL,NULL,NULL,0,'[]',NULL,NULL)");
      assertThrows(
          SQLException.class,
          () ->
              sql.execute(
                  "INSERT INTO mcp_requests VALUES('local-mcp','key','different','project','session','run2','2026-01-01','2026-04-01',NULL,NULL,NULL,0,'[]',NULL,NULL)"));
      try (var rows = sql.executeQuery("SELECT count(*) FROM conversation_admissions")) {
        assertTrue(rows.next());
        assertEquals(0, rows.getInt(1));
      }
    }
  }
}
