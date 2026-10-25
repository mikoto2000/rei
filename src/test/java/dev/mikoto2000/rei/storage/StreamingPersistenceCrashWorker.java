package dev.mikoto2000.rei.storage;

import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.sqlite.Function;

/** COMMIT完了後、またはJSONL同期後のDB未確定位置でプロセスを強制終了する。 */
public final class StreamingPersistenceCrashWorker {
  public static void main(String[] args) {
    Path root = Path.of(args[0]);
    var bus = new InMemoryAgentEventBus();
    var context = StreamingPersistenceTest.context(root, bus);
    bus.subscribe(
        event -> {
          try {
            Files.writeString(
                root.resolve("delivered.txt"),
                event.id() + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
          } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
          }
        });
    bus.publish(StreamingPersistenceTest.event("good", StreamingPersistenceTest.PROJECT, true));
    if (args[1].equals("before")) {
      new StorageDatabase(root)
          .transaction(
              db -> {
                Function.create(
                    db,
                    "terminate_writer",
                    new Function() {
                      @Override
                      protected void xFunc() {
                        Runtime.getRuntime().halt(0);
                      }
                    });
                try (var sql = db.createStatement()) {
                  sql.execute(
                      "CREATE TRIGGER crash_during_metadata AFTER UPDATE ON activity_segments BEGIN SELECT terminate_writer(); END");
                }
                return null;
              });
      bus.publish(
          StreamingPersistenceTest.event("pending", StreamingPersistenceTest.PROJECT, false));
      throw new AssertionError("Crash hook did not run");
    }
    Runtime.getRuntime().halt(0);
  }
}
