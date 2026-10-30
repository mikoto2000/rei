package dev.mikoto2000.rei.storage;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;

/** Removes only post-19 fixture tables when reconstructing a historical schema. */
final class StorageSchemaFixture {
  private StorageSchemaFixture() {}

  static void removeDurableSchema(Statement sql) throws SQLException {
    var names = new ArrayList<String>();
    try (var query = sql.getConnection().createStatement();
        var rows = query.executeQuery("SELECT name FROM sqlite_schema WHERE type='table' AND name GLOB 'durable_*'")) {
      while (rows.next()) names.add(rows.getString(1));
    }
    for (var name : names) sql.execute("DROP TABLE \"" + name.replace("\"", "\"\"") + "\"");
  }
}
