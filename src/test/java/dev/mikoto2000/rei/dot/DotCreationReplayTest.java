package dev.mikoto2000.rei.dot;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class DotCreationReplayTest {
  @TempDir Path root;

  @Test
  void sameCreationIdSurvivesLostResponseAndRejectsChangedOwnerOrContent() throws Exception {
    try (var gate = new dev.mikoto2000.rei.storage.StorageMigrationCoordinator(root)) {
      gate.prepare();
    }
    var clock = new DotRequestRepositoryTest.Time();
    var repo = new DotRequestRepository(root, clock);
    String key = UUID.randomUUID().toString();
    var input = DotPayload.publication(Map.of("instruction", "research"));
    var first = repo.create("p", null, null, root.toString(), input, Duration.ofHours(1), key);
    clock.now = clock.now.plusSeconds(1);
    assertEquals(
        first,
        new DotRequestRepository(root, clock)
            .create("p", null, null, root.toString(), input, Duration.ofHours(1), key));
    assertEquals(1, repo.list(Set.of("p"), "PENDING", null, 20).size());
    assertThrows(
        DotException.class,
        () -> repo.create("other", null, null, root.toString(), input, Duration.ofHours(1), key));
    assertThrows(
        DotException.class,
        () ->
            repo.create(
                "p",
                null,
                null,
                root.toString(),
                DotPayload.publication(Map.of("instruction", "different")),
                Duration.ofHours(1),
                key));
  }
}
