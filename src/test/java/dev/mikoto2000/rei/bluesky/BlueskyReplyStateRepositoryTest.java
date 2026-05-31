package dev.mikoto2000.rei.bluesky;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class BlueskyReplyStateRepositoryTest {

  @TempDir
  java.nio.file.Path tempDir;

  private BlueskyReplyStateRepository repository;

  @BeforeEach
  void setUp() {
    repository = new BlueskyReplyStateRepository(
        new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("bluesky-reply-state.db")));
  }

  @Test
  void saveAndFindLastSeen() {
    OffsetDateTime now = OffsetDateTime.now();

    repository.saveLastSeen("alice.bsky.social", "at://post/1", now);

    var state = repository.findLastSeen("alice.bsky.social");
    assertTrue(state.isPresent());
    assertTrue(state.get().lastSeenPostUri().contains("at://post/1"));
  }

  @Test
  void markRepliedAndQuery() {
    assertFalse(repository.isAlreadyReplied("at://post/2"));

    repository.markReplied("at://post/2", "alice.bsky.social", "at://reply/2");

    assertTrue(repository.isAlreadyReplied("at://post/2"));
  }

  @Test
  void incrementTodayCount() {
    LocalDate today = LocalDate.now();
    assertTrue(repository.countToday("alice.bsky.social", today) == 0);

    repository.incrementToday("alice.bsky.social", today);
    repository.incrementToday("alice.bsky.social", today);

    assertTrue(repository.countToday("alice.bsky.social", today) == 2);
  }
}
