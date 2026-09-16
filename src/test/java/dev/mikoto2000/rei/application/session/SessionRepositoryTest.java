package dev.mikoto2000.rei.application.session;

import java.nio.file.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.conversation.FileSessionRepository;
import static org.assertj.core.api.Assertions.*;

class SessionRepositoryTest {
  @TempDir Path temp;
  final Instant now = Instant.parse("2026-09-16T08:00:00Z");
  SessionMetadata metadata(String id) { return new SessionMetadata(id, "project", "first", now, now); }

  @Test void acceptanceIsDurableBeforeDispatchAndSurvivesRestart() {
    var file = temp.resolve("sessions.json");
    var store = new FileSessionRepository(file);
    var first = metadata("one");
    store.accept(first, () -> assertThat(new FileSessionRepository(file).findById("one")).contains(first));
    assertThat(new FileSessionRepository(file).findById("one")).contains(first);
    assertThat(store.findById("missing")).isEmpty();
  }

  @Test void continuationPreservesIdentityAndTimeNeverMovesBackwards() {
    var store = new FileSessionRepository(temp.resolve("sessions.json"));
    var first = metadata("one");
    store.accept(first, () -> {});
    store.accept(first.touched(now.plusSeconds(10)), () -> {});
    store.accept(first.touched(now.plusSeconds(1)), () -> {});
    assertThat(store.findById("one")).contains(first.touched(now.plusSeconds(10)));
    assertThatThrownBy(() -> store.accept(new SessionMetadata("one", "other", "first", now, now), () -> {}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test void rejectionRestoresPreviousDurableState() {
    var file = temp.resolve("sessions.json");
    var store = new FileSessionRepository(file);
    assertThatThrownBy(() -> store.accept(metadata("one"), () -> { throw new IllegalStateException("rejected"); }))
        .hasMessage("rejected");
    assertThat(new FileSessionRepository(file).findById("one")).isEmpty();
    store.accept(metadata("one"), () -> {});
    assertThatThrownBy(() -> store.accept(metadata("one").touched(now.plusSeconds(10)), () -> { throw new IllegalStateException("rejected"); }))
        .hasMessage("rejected");
    assertThat(new FileSessionRepository(file).findById("one")).contains(metadata("one"));
  }

  @Test void failedPersistenceNeverDispatches() throws Exception {
    var file = Files.writeString(temp.resolve("not-directory"), "file").resolve("sessions.json");
    var store = new FileSessionRepository(file);
    assertThatThrownBy(() -> store.accept(metadata("one"), () -> { throw new AssertionError("must not enqueue"); }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(store.findById("one")).isEmpty();
  }

  @Test void concurrentAdmissionNeverLosesRowsOrRegressesUpdatedAt() throws Exception {
    var file = temp.resolve("sessions.json");
    var store = new FileSessionRepository(file);
    var first = metadata("one"); store.accept(first, () -> {});
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var jobs = new java.util.ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 1; i <= 30; i++) {
        int index = i;
        jobs.add(executor.submit(() -> {
          store.accept(first.touched(now.plusSeconds(index)), () -> {});
          store.accept(metadata("new-" + index), () -> {});
          assertThat(store.findPage(null, null, 100)).isNotEmpty();
        }));
      }
      for (var job : jobs) job.get(10, java.util.concurrent.TimeUnit.SECONDS);
    }
    var restored = new FileSessionRepository(file);
    assertThat(restored.findById("one")).contains(first.touched(now.plusSeconds(30)));
    assertThat(restored.findPage(null, null, 100)).hasSize(31);
  }
}
