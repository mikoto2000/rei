package dev.mikoto2000.rei.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.checkpoint.PersistentCheckpoint;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class CheckpointReferenceSqlTest {
  @TempDir Path root;
  private final String project = UUID.randomUUID().toString();

  private StorageObjectRegistry registry() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    return new StorageObjectRegistry(root);
  }

  private PersistentCheckpoint checkpoint(int count, int distinct) {
    var initial = PersistentCheckpoint.initial("task", project, "chat", "run", root, "request");
    var tree =
        (com.fasterxml.jackson.databind.node.ObjectNode) StorageDatabase.JSON.valueToTree(initial);
    var evidence = new ArrayList<Map<String, String>>();
    for (int i = 0; i < count; i++)
      evidence.add(
          Map.of("origin", "EVENT", "summary", "confirmed", "eventId", "e" + i % distinct));
    tree.set("evidence", StorageDatabase.JSON.valueToTree(evidence));
    return StorageDatabase.JSON.convertValue(tree, PersistentCheckpoint.class);
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
  void hundredsOfRepeatedReferencesPrepareOnlyThreeStatementsAndStayIdempotent() throws Exception {
    var registry = registry();
    var state = checkpoint(500, 100);
    registry.database.transaction(
        db -> {
          try (var insert =
              db.prepareStatement(
                  "INSERT INTO stored_objects(id,kind,scope,relative_path,size,sha256,origin_verified) VALUES(?,'EVENT','','available',1,'hash',1)")) {
            insert.setString(1, "event:" + project + ":e0");
            insert.executeUpdate();
          }
          return null;
        });
    var prepared = new AtomicInteger();
    registry.database.transaction(
        db -> {
          StorageObjectRegistry.protectCheckpoint(counted(db, prepared), root, state);
          return null;
        });
    assertThat(prepared).hasValue(3);
    for (int i = 0; i < 100; i++)
      assertThat(registry.references("event:" + project + ":e" + i)).hasSize(1);
    registry.protectCheckpoint(state);
    assertThat(registry.references("event:" + project + ":e0")).hasSize(1);
    long revision =
        registry.database.read(
            db ->
                StorageObjectRegistry.find(db, "event:" + project + ":e0")
                    .orElseThrow()
                    .revision());
    assertThat(revision).isEqualTo(2);
  }

  @Test
  void unavailableLaterReferenceRollsBackEarlierProtection() throws Exception {
    var registry = registry();
    registry.database.transaction(
        db -> {
          try (var insert =
              db.prepareStatement(
                  "INSERT INTO stored_objects(id,kind,scope,relative_path,size,sha256,origin_verified,status) VALUES(?,'EVENT','','purged',1,'hash',1,'PURGED')")) {
            insert.setString(1, "event:" + project + ":e1");
            insert.executeUpdate();
          }
          return null;
        });
    assertThatThrownBy(() -> registry.protectCheckpoint(checkpoint(2, 2)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(registry.references("event:" + project + ":e0")).isEmpty();
    assertThat(registry.references("event:" + project + ":e1")).isEmpty();
    registry.database.transaction(
        db -> {
          try (var sql = db.createStatement()) {
            sql.execute("UPDATE stored_objects SET status='AVAILABLE',created='invalid-timestamp'");
          }
          return null;
        });
    assertThatThrownBy(() -> registry.protectCheckpoint(checkpoint(2, 2)))
        .isInstanceOf(java.time.format.DateTimeParseException.class);
    assertThat(registry.references("event:" + project + ":e0")).isEmpty();
  }
}
