package dev.mikoto2000.rei.testsupport;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

@org.junit.jupiter.api.Tag("integration")
class SqliteTestDatabaseTest {
  @Test void retainsRealSqliteAcrossLogicalConnectionsAndIsolatesEachFixture() throws Exception {
    try (var first = new SqliteTestDatabase(); var second = new SqliteTestDatabase()) {
      try (var connection = first.dataSource().getConnection(); var statement = connection.createStatement()) {
        statement.execute("CREATE TABLE evidence(value TEXT)");
        statement.execute("INSERT INTO evidence VALUES('retained')");
      }
      try (var connection = first.dataSource().getConnection(); var statement = connection.createStatement();
          var result = statement.executeQuery("SELECT value FROM evidence")) {
        assertTrue(result.next());
        assertEquals("retained", result.getString(1));
      }
      try (var connection = second.dataSource().getConnection(); var statement = connection.createStatement()) {
        assertThrows(SQLException.class, () -> statement.executeQuery("SELECT * FROM evidence"));
      }
    }
  }

  @Test void closesThePhysicalConnectionAtTheEndOfTheSample() throws Exception {
    var database = new SqliteTestDatabase();
    var connection = database.dataSource().getConnection();
    database.close();
    assertTrue(connection.isClosed());
  }
}
