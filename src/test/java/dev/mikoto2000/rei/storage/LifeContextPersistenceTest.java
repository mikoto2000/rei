package dev.mikoto2000.rei.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.mikoto2000.rei.life.LifeContext;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class LifeContextPersistenceTest {
  @TempDir Path root;

  @Test
  void settingsAreRestoredAndStaleWritersCannotReplaceThem() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    var store = new SqliteLifeContextStore(root.toString());
    var first = new LifeContext(store, Clock.systemUTC());
    var second = new LifeContext(store, Clock.systemUTC());
    var s = first.settings();
    var value =
        new LifeContext.Settings(
            0,
            "UTC",
            s.workDays(),
            "10:00",
            "19:00",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null);
    first.configure(value);
    assertEquals("10:00", new LifeContext(store, Clock.systemUTC()).settings().workStart());
    assertThrows(IllegalArgumentException.class, () -> second.configure(value));
  }
}
