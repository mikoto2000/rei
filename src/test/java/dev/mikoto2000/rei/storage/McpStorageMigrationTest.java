package dev.mikoto2000.rei.storage;
import java.sql.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class McpStorageMigrationTest {
  @Test void storesOnePrincipalRequestAndPreservesExistingAdmission() throws Exception {
    try(var db=DriverManager.getConnection("jdbc:sqlite::memory:"); var sql=db.createStatement()) {
      ConversationAdmissionMigration.apply(db);
      McpStorageMigration.apply(db);
      McpStorageMigration.verify(db);
      sql.execute("INSERT INTO mcp_requests VALUES('local-mcp','key','fingerprint','project','session','run','2026-01-01','2026-04-01',NULL,NULL,NULL,0,'[]',NULL,NULL)");
      assertThrows(SQLException.class,()->sql.execute("INSERT INTO mcp_requests VALUES('local-mcp','key','different','project','session','run2','2026-01-01','2026-04-01',NULL,NULL,NULL,0,'[]',NULL,NULL)"));
      try(var rows=sql.executeQuery("SELECT count(*) FROM conversation_admissions")){assertTrue(rows.next());assertEquals(0,rows.getInt(1));}
    }
  }
}
