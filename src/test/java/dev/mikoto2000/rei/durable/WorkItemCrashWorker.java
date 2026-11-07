package dev.mikoto2000.rei.durable;

import dev.mikoto2000.rei.storage.StorageDatabase;
import java.nio.file.Path;
import java.util.List;

/** Test-only process: terminate without shutdown hooks at an actual SQLite commit boundary. */
public final class WorkItemCrashWorker {
  public static void main(String[] args) {
    var database = new StorageDatabase(Path.of(args[0]).resolve("data"));
    var store = new DurableTaskStore(new DurableTaskStore.Access() {
      @Override
      public <T> T run(boolean write, DurableTaskStore.Work<T> operation) {
        if (!write) return database.read(operation::run);
        T result = database.transaction(db -> {
          T value = operation.run(db);
          if (args[4].equals("before")) Runtime.getRuntime().halt(71);
          return value;
        });
        Runtime.getRuntime().halt(72);
        return result;
      }
    });
    var scope = new DurableTaskStore.Scope("project", "session", Path.of(args[0]).resolve("repository").toString());
    WorkItemStore.Item item = store.read(db -> WorkItemStore.item(db, args[2]));
    new WorkItemStore(store).commit(scope, new WorkItemStore.Claim(item, "run", args[3]), "checked", List.of());
    throw new AssertionError("Worker did not halt");
  }
}
