package dev.mikoto2000.rei.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/** SQLite検証データベースの結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class SqliteTestDatabaseTest {
  /** 機能の提供で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void retainsRealSqliteAcrossLogicalConnectionsAndIsolatesEachFixture() throws Exception {
    try (var first = new SqliteTestDatabase();
        var second = new SqliteTestDatabase()) {
      try (var connection = first.dataSource().getConnection();
          var statement = connection.createStatement()) {
        statement.execute("CREATE TABLE evidence(value TEXT)");
        statement.execute("INSERT INTO evidence VALUES('retained')");
      }
      try (var connection = first.dataSource().getConnection();
          var statement = connection.createStatement();
          var result = statement.executeQuery("SELECT value FROM evidence")) {
        assertTrue(result.next());
        assertEquals("retained", result.getString(1));
      }
      try (var connection = second.dataSource().getConnection();
          var statement = connection.createStatement()) {
        assertThrows(SQLException.class, () -> statement.executeQuery("SELECT * FROM evidence"));
      }
    }
  }

  /** 機能の提供で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void closesThePhysicalConnectionAtTheEndOfTheSample() throws Exception {
    var database = new SqliteTestDatabase();
    var connection = database.dataSource().getConnection();
    database.close();
    assertTrue(connection.isClosed());
  }
}
