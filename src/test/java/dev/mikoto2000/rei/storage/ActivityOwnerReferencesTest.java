package dev.mikoto2000.rei.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.event.ProfileEventLogEntry;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class ActivityOwnerReferencesTest {
  @TempDir Path root;

  private StorageObjectRegistry registry() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    return new StorageObjectRegistry(root);
  }

  private Connection counted(Connection db, AtomicInteger prepared) {
    return (Connection)
        java.lang.reflect.Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("prepareStatement")) prepared.incrementAndGet();
              try {
                return method.invoke(db, arguments);
              } catch (java.lang.reflect.InvocationTargetException error) {
                throw error.getCause();
              }
            });
  }

  @Test
  void repeatedOwnerPairsPrepareThreeStatementsAndDoNotInflateRevision() throws Exception {
    var registry = registry();
    var owners = new ArrayList<StorageObjectRegistry.Reference>();
    for (int i = 0; i < 8396; i++) {
      owners.add(new StorageObjectRegistry.Reference("SESSION_HISTORY", "session"));
      owners.add(new StorageObjectRegistry.Reference("RUN_HISTORY", "run"));
    }
    registry.database.transaction(
        db -> {
          try (var sql = db.createStatement()) {
            sql.execute(
                "INSERT INTO stored_objects(id,kind,scope,relative_path,size,sha256,origin_verified) VALUES('segment','ACTIVITY_RAW','','segment',1,'hash',1)");
          }
          var prepared = new AtomicInteger();
          StorageObjectRegistry.addReferences(counted(db, prepared), "segment", owners);
          assertThat(prepared).hasValue(3);
          assertThat(StorageObjectRegistry.find(db, "segment").orElseThrow().revision())
              .isEqualTo(3);
          StorageObjectRegistry.addReferences(db, "segment", owners);
          assertThat(StorageObjectRegistry.find(db, "segment").orElseThrow().revision())
              .isEqualTo(3);
          return null;
        });
    assertThat(registry.references("segment")).hasSize(2);
  }

  @Test
  void segmentCloseRetainsAllDistinctOwnersAndInvalidOwnerRollsBackClose() throws Exception {
    var registry = registry();
    var log = new ManagedActivityLog(registry);
    for (int i = 0; i < 30; i++)
      log.append(
          null,
          new ProfileEventLogEntry(
              "e" + i,
              i,
              Instant.now(),
              "diagnostic",
              1,
              "s" + (i % 2),
              null,
              "r" + (i % 3),
              null,
              null,
              null,
              Map.of()));
    log.closeSegment(null);
    var id =
        registry.database.read(
            db -> {
              try (var q = db.createStatement();
                  var rows =
                      q.executeQuery("SELECT id FROM stored_objects WHERE kind='ACTIVITY_RAW'")) {
                rows.next();
                return rows.getString(1);
              }
            });
    assertThat(registry.references(id)).hasSize(5);
    log.append(
        null,
        new ProfileEventLogEntry(
            "bad",
            31,
            Instant.now(),
            "diagnostic",
            1,
            "",
            null,
            "new-run",
            null,
            null,
            null,
            Map.of()));
    assertThatThrownBy(() -> log.closeSegment(null)).isInstanceOf(IllegalArgumentException.class);
    assertThat(
            registry.database.<Integer>read(
                db -> {
                  try (var q = db.createStatement();
                      var rows =
                          q.executeQuery(
                              "SELECT COUNT(*) FROM activity_segments WHERE state='OPEN'")) {
                    rows.next();
                    return rows.getInt(1);
                  }
                }))
        .isEqualTo(1);
  }

  @Test
  void unavailableOrMalformedMetadataCannotBeProtected() throws Exception {
    var registry = registry();
    registry.database.transaction(
        db -> {
          try (var sql = db.createStatement()) {
            sql.execute(
                "INSERT INTO stored_objects(id,kind,scope,relative_path,size,sha256,origin_verified,status) VALUES('bad','ACTIVITY_RAW','','bad',1,'hash',1,'PURGED')");
          }
          return null;
        });
    var owners = List.of(new StorageObjectRegistry.Reference("RUN_HISTORY", "run"));
    assertThatThrownBy(
            () ->
                registry.database.transaction(
                    db -> {
                      StorageObjectRegistry.addReferences(db, "bad", owners);
                      return null;
                    }))
        .isInstanceOf(IllegalStateException.class);
    registry.database.transaction(
        db -> {
          try (var sql = db.createStatement()) {
            sql.execute("UPDATE stored_objects SET status='AVAILABLE',created='bad-date'");
          }
          return null;
        });
    assertThatThrownBy(
            () ->
                registry.database.transaction(
                    db -> {
                      StorageObjectRegistry.addReferences(db, "bad", owners);
                      return null;
                    }))
        .isInstanceOf(java.time.format.DateTimeParseException.class);
    assertThat(registry.references("bad")).isEmpty();
  }
}
