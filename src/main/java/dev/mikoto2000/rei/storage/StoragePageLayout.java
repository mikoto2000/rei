package dev.mikoto2000.rei.storage;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HexFormat;

/** 起動時の検証済みバックアップの後だけ、小更新向けのページ配置へ原子的に再構築する。 */
final class StoragePageLayout {
  static final int PAGE_BYTES = 1024;

  private StoragePageLayout() {}

  static void apply(Connection db, java.nio.file.Path database) throws Exception {
    try (var sql = db.createStatement();
        var rows = sql.executeQuery("PRAGMA page_size")) {
      if (rows.getInt(1) == PAGE_BYTES) return;
    }
    integrity(db);
    String before = contents(db);
    try (var sql = db.createStatement()) {
      try (var mode = sql.executeQuery("PRAGMA journal_mode=DELETE")) {
        if (!mode.next() || !"delete".equalsIgnoreCase(mode.getString(1)))
          throw new java.sql.SQLException("Cannot exclusively repack storage pages");
      }
      long required =
          Math.addExact(
              Math.multiplyExact(java.nio.file.Files.size(database), 2), 4L * 1024 * 1024);
      if (java.nio.file.Files.getFileStore(database).getUsableSpace() < required)
        throw new java.io.IOException(
            "Insufficient space to safely repack storage; backup retained");
      sql.execute("PRAGMA page_size=" + PAGE_BYTES);
      // SQLite自身のrollback journalとFULL同期で旧DBか再構築済みDBの一方を保持する。
      sql.execute("VACUUM");
      try (var rows = sql.executeQuery("PRAGMA page_size")) {
        if (rows.getInt(1) != PAGE_BYTES)
          throw new java.sql.SQLException("Storage page repack did not apply");
      }
    }
    integrity(db);
    if (!before.equals(contents(db)))
      throw new java.io.IOException(
          "Storage contents changed during page repack; retain verified backup");
  }

  private static void integrity(Connection db) throws java.sql.SQLException, java.io.IOException {
    try (var sql = db.createStatement();
        var rows = sql.executeQuery("PRAGMA integrity_check")) {
      if (!rows.next() || !"ok".equals(rows.getString(1)) || rows.next())
        throw new java.io.IOException("Storage integrity check failed; source and backup retained");
    }
  }

  /** 物理ページ番号や暗黙rowidに依存せず、schemaと型付き全列の論理内容を照合する。 */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE",
      justification =
          "Identifiers come only from sqlite_schema/table_info, are double-quote escaped, and cannot be bound as SQL parameters; quoted schema names are integration tested.")
  static String contents(Connection db)
      throws java.sql.SQLException, java.io.IOException, java.security.NoSuchAlgorithmException {
    var digest = MessageDigest.getInstance("SHA-256");
    var tables = new ArrayList<String>();
    try (var sql = db.createStatement();
        var rows =
            sql.executeQuery(
                "SELECT type,name,tbl_name,sql FROM sqlite_schema ORDER BY name COLLATE BINARY")) {
      while (rows.next()) {
        for (int i = 1; i <= 4; i++) put(digest, rows.getString(i));
        if ("table".equals(rows.getString(1))) tables.add(rows.getString(2));
      }
    }
    for (String table : tables) {
      put(digest, table);
      var columns = new ArrayList<String>();
      try (var sql = db.createStatement();
          var rows = sql.executeQuery("PRAGMA table_info(" + identifier(table) + ")")) {
        while (rows.next()) columns.add(rows.getString("name"));
      }
      var values = new ArrayList<String>();
      var order = new ArrayList<String>();
      for (String column : columns) {
        String name = identifier(column);
        values.add("typeof(" + name + ")");
        values.add(name);
        order.add("typeof(" + name + ") COLLATE BINARY");
        order.add(name + " COLLATE BINARY");
      }
      try (var sql = db.createStatement();
          var rows =
              sql.executeQuery(
                  "SELECT "
                      + String.join(",", values)
                      + " FROM "
                      + identifier(table)
                      + " ORDER BY "
                      + String.join(",", order))) {
        long count = 0;
        while (rows.next()) {
          count = Math.addExact(count, 1);
          for (int i = 0; i < columns.size(); i++) {
            String type = rows.getString(2 * i + 1);
            int value = 2 * i + 2;
            put(digest, type);
            switch (type) {
              case "null" -> put(digest, (byte[]) null);
              case "integer" ->
                  put(digest, ByteBuffer.allocate(8).putLong(rows.getLong(value)).array());
              case "real" -> {
                double number = rows.getDouble(value);
                // SQLiteの数値比較で同一な正負の0は同じ表現へ正規化する。
                put(
                    digest,
                    ByteBuffer.allocate(8)
                        .putLong(Double.doubleToLongBits(number == 0 ? 0 : number))
                        .array());
              }
              case "text" -> put(digest, rows.getString(value));
              case "blob" -> put(digest, rows.getBytes(value));
              default -> throw new java.io.IOException("Unsupported SQLite value type");
            }
          }
        }
        put(digest, Long.toString(count));
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static String identifier(String value) {
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }

  private static void put(MessageDigest digest, String value) {
    put(digest, value == null ? null : value.getBytes(StandardCharsets.UTF_8));
  }

  private static void put(MessageDigest digest, byte[] value) {
    digest.update(ByteBuffer.allocate(4).putInt(value == null ? -1 : value.length).array());
    if (value != null) digest.update(value);
  }
}
