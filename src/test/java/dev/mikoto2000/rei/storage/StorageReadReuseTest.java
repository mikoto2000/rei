package dev.mikoto2000.rei.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** 読み取り再利用と変更のないカーソルの省書き込みを実SQLiteで検証する。 */
@Tag("integration")
class StorageReadReuseTest {
  @TempDir Path root;

  private AnnotationConfigApplicationContext context() {
    var context = new AnnotationConfigApplicationContext();
    context
        .getEnvironment()
        .getPropertySources()
        .addFirst(new MapPropertySource("test", Map.of("rei.data-dir", root.toString())));
    context.register(StorageMigrationConfiguration.class);
    context.refresh();
    return context;
  }

  private int count(StorageDatabase database) {
    return database.read(
        db -> {
          try (var sql = db.createStatement();
              var rows = sql.executeQuery("SELECT count(*) FROM event_sequences")) {
            assertThat(rows.next()).isTrue();
            return rows.getInt(1);
          }
        });
  }

  @Test
  void reusesReaderAcrossRepositoriesAndSeesExternalCommitsWithoutHoldingSnapshot()
      throws Exception {
    Connection reader;
    try (var context = context()) {
      var database = new StorageDatabase(root);
      var connections = new ArrayList<Connection>();
      for (int i = 0; i < 100; i++) {
        new StorageDatabase(root)
            .read(
                db -> {
                  connections.add(db);
                  return null;
                });
        assertThat(count(database)).isZero();
      }
      var firstReader = connections.getFirst();
      assertThat(connections).allMatch(connection -> connection == firstReader);
      assertThatThrownBy(
              () ->
                  database.read(
                      db -> {
                        try (var sql = db.createStatement()) {
                          sql.executeUpdate("INSERT INTO event_sequences VALUES('forbidden',1)");
                        }
                        return null;
                      }))
          .hasRootCauseInstanceOf(java.sql.SQLException.class);
      assertThat(firstReader.isClosed()).isTrue();

      try (var external = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db"));
          var sql = external.createStatement()) {
        sql.executeUpdate("INSERT INTO event_sequences VALUES('external',1)");
        assertThat(count(database)).isEqualTo(1);
        sql.executeUpdate("INSERT INTO event_sequences VALUES('external2',2)");
        assertThat(count(database)).isEqualTo(2);
        try (var rows = sql.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getInt(1)).isZero();
        }
      }
      new StorageMaintenance(context.getBean(StorageObjectRegistry.class))
          .vacuum(java.time.Duration.ofSeconds(5));
      reader = database.read(db -> db);
    }
    assertThat(reader.isClosed()).isTrue();
    assertThat(Files.exists(root.resolve("storage.db-wal"))).isFalse();
  }

  @Test
  void concurrentAndNestedReadsNeverWaitForAnOccupiedReader() throws Exception {
    try (var context = context();
        var executor = Executors.newFixedThreadPool(2)) {
      var database = new StorageDatabase(root);
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var first =
          executor.submit(
              () ->
                  database.read(
                      db -> {
                        entered.countDown();
                        assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                        assertThat(count(database)).isZero();
                        return db;
                      }));
      try {
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        var other = executor.submit(() -> database.transaction(db -> count(database)));
        assertThat(other.get(10, TimeUnit.SECONDS)).isZero();
      } finally {
        release.countDown();
      }
      var returned = first.get(10, TimeUnit.SECONDS);
      // 競合して戻る接続のうち、保持できないものは確実に閉じる。
      assertThat(returned.isClosed()).isTrue();
    }
  }

  @Test
  void failedOrTransactionalReaderIsDiscardedAndSchemaIsRechecked() throws Exception {
    try (var context = context()) {
      var database = new StorageDatabase(root);
      var reader = database.read(db -> db);
      assertThatThrownBy(
              () ->
                  database.read(
                      db -> {
                        db.setAutoCommit(false);
                        throw new java.io.IOException("failed read");
                      }))
          .hasRootCauseMessage("failed read");
      assertThat(reader.isClosed()).isTrue();
      var transactional =
          database.read(
              db -> {
                db.setAutoCommit(false);
                return db;
              });
      assertThat(transactional.isClosed()).isTrue();
      assertThat(count(database)).isZero();
      try (var external = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db"));
          var sql = external.createStatement()) {
        sql.execute("PRAGMA user_version=999");
      }
      assertThatThrownBy(() -> count(database)).hasRootCauseInstanceOf(java.sql.SQLException.class);
    }
  }

  @Test
  void borrowedReaderCannotReturnToAnotherApplicationLifetime() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
      var database = new StorageDatabase(root);
      var restarted = new java.util.concurrent.atomic.AtomicReference<AutoCloseable>();
      try (var first = database.keepWalOpen()) {
        var late =
            database.read(
                db -> {
                  first.close();
                  restarted.set(database.keepWalOpen());
                  return db;
                });
        assertThat(late.isClosed()).isTrue();
        assertThat(count(database)).isZero();
      } finally {
        if (restarted.get() != null) restarted.get().close();
      }
    }
    assertThat(Files.exists(root.resolve("storage.db-wal"))).isFalse();
  }

  @Test
  void unchangedCursorDoesNotWriteButStillChecksGenerationExistenceAndEventBounds()
      throws Exception {
    String project = UUID.randomUUID().toString();
    try (var context = context()) {
      var cursor = new SqliteEventCursorStore(root);
      var initial = new SqliteEventCursorStore.Cursor(0, 0);
      assertThat(cursor.compareAndSet(project, initial, 0)).isFalse();
      cursor.reset(project);
      var database = new StorageDatabase(root);
      database.transaction(
          db -> {
            try (var sql = db.createStatement()) {
              sql.execute(
                  "CREATE TRIGGER reject_cursor_update BEFORE UPDATE ON event_cursors BEGIN SELECT RAISE(ABORT,'unexpected write'); END");
            }
            return null;
          });
      long walBytes = Files.size(root.resolve("storage.db-wal"));
      for (int i = 0; i < 100; i++) {
        assertThat(cursor.compareAndSet(project, initial, 0)).isTrue();
      }
      assertThat(Files.size(root.resolve("storage.db-wal"))).isEqualTo(walBytes);
      assertThat(cursor.compareAndSet(project, new SqliteEventCursorStore.Cursor(0, 1), 0))
          .isFalse();
      database.transaction(
          db -> {
            try (var sql = db.createStatement()) {
              sql.execute("DROP TRIGGER reject_cursor_update");
              sql.executeUpdate("INSERT INTO event_sequences VALUES('" + project + "',1)");
            }
            return null;
          });
      assertThat(cursor.compareAndSet(project, initial, 1)).isTrue();
      assertThat(cursor.get(project).sequence()).isEqualTo(1);
      assertThat(cursor.compareAndSet(project, initial, 0)).isFalse();
      cursor.reset(project);
      assertThat(cursor.compareAndSet(project, initial, 0)).isFalse();
      assertThat(cursor.compareAndSet(project, cursor.get(project), 0)).isTrue();
      // 保存済みの末尾より先を指す不正カーソルを、同値でも成功にしない。
      database.transaction(
          db -> {
            try (var sql = db.createStatement()) {
              sql.executeUpdate("UPDATE event_cursors SET sequence=2");
            }
            return null;
          });
      assertThat(cursor.compareAndSet(project, cursor.get(project), 2)).isFalse();
    }
  }

  /** 明示実行時だけプロセスの読み取り量を比較し、実ユーザーデータは使用しない。 */
  @Test
  @EnabledIfSystemProperty(named = "rei.io.read-benchmark", matches = "true")
  void measureRepeatedReads() throws Exception {
    try (var context = context()) {
      new StorageDatabase(root)
          .transaction(
              db -> {
                try (var sql = db.prepareStatement("INSERT INTO event_sequences VALUES(?,1)")) {
                  for (int i = 0; i < 1000; i++) {
                    sql.setString(1, "fixture-" + i);
                    sql.addBatch();
                  }
                  sql.executeBatch();
                }
                return null;
              });
    }
    var results = new ArrayList<Map<String, Object>>();
    for (int repeat = 0; repeat < 3; repeat++) {
      for (boolean reuse : new boolean[] {false, true}) {
        try (var context = reuse ? context() : null) {
          var database = new StorageDatabase(root);
          assertThat(count(database)).isEqualTo(1000);
          var before = AnswerGenerationIoBenchmarkTest.io();
          long start = System.nanoTime();
          for (int i = 0; i < 1000; i++) assertThat(count(database)).isEqualTo(1000);
          long elapsed = System.nanoTime() - start;
          var after = AnswerGenerationIoBenchmarkTest.io();
          var result = new java.util.LinkedHashMap<String, Object>();
          result.put("reuse", reuse);
          result.put("repeat", repeat);
          result.put("queries", 1000);
          result.put("elapsedMs", elapsed / 1_000_000.0);
          if (before != null && after != null) {
            result.put("readOperations", after.readOperations - before.readOperations);
            result.put("readBytes", after.readBytes - before.readBytes);
            result.put("writeOperations", after.writeOperations - before.writeOperations);
            result.put("writeBytes", after.writeBytes - before.writeBytes);
          }
          results.add(result);
        }
      }
    }
    Path output = Path.of("target/io-read-reuse.json");
    Files.writeString(
        output,
        StorageDatabase.JSON
            .writerWithDefaultPrettyPrinter()
            .writeValueAsString(
                Map.of(
                    "notes",
                    "Process I/O includes native SQLite and other JVM activity; not physical SSD I/O. Standalone uses short-lived reads; application context retains at most one idle reader.",
                    "results",
                    results)));
  }
}
