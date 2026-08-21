package dev.mikoto2000.rei.core.checkpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

class CheckpointStoreTest {

  private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

  private CheckpointStore store(Instant start) {
    return new CheckpointStore(Clock.fixed(start, ZONE));
  }

  private TurnCheckpoint checkpoint(String reason) {
    return new TurnCheckpoint("task-1", "goal", "step-1", List.of(), List.of(), List.of(), null, null, null, reason, "2026-08-17T00:00:00Z");
  }

  @Test
  void emptyCheckpointStoreHasNoLatest() {
    CheckpointStore store = store(Instant.parse("2026-08-17T00:00:00Z"));
    assertTrue(store.isEmpty());
    assertTrue(store.latest().isEmpty());
  }

  @Test
  void emptyCheckpointRendersBlank() {
    CheckpointStore store = store(Instant.parse("2026-08-17T00:00:00Z"));
    assertEquals("", store.renderForPrompt());
  }

  @Test
  void checkpointCanBeSaved() {
    CheckpointStore store = store(Instant.parse("2026-08-17T00:00:00Z"));
    TurnCheckpoint checkpoint = new TurnCheckpoint("task-1", "UserService の Optional 対応", "step-2",
        List.of("再現テストを追加"), List.of("実装修正", "単体テスト"), List.of("src/UserService.java"),
        "再現テストが失敗した", null, "UserService.save() から再開", "TURN_END", "2026-08-17T00:00:00Z");
    store.save(checkpoint);
    assertFalse(store.isEmpty());
    assertTrue(store.latest().isPresent());
    assertEquals("task-1", store.latest().get().taskId());
  }

  @Test
  void latestCheckpointCanBeRetrieved() {
    CheckpointStore store = store(Instant.parse("2026-08-17T00:00:00Z"));
    store.save(checkpoint("TURN_END"));
    store.save(checkpoint("LENGTH"));
    assertEquals("LENGTH", store.latest().get().reason());
  }

  @Test
  void turnEndReasonIsDistinguishable() {
    CheckpointStore store = store(Instant.parse("2026-08-17T00:00:00Z"));
    store.save(checkpoint("TURN_END"));
    assertEquals("TURN_END", store.latest().get().reason());
  }

  @Test
  void lengthReasonIsDistinguishable() {
    CheckpointStore store = store(Instant.parse("2026-08-17T00:00:00Z"));
    store.save(checkpoint("LENGTH"));
    assertEquals("LENGTH", store.latest().get().reason());
  }

  @Test
  void blockedReasonIsDistinguishable() {
    CheckpointStore store = store(Instant.parse("2026-08-17T00:00:00Z"));
    store.save(checkpoint("BLOCKED"));
    assertEquals("BLOCKED", store.latest().get().reason());
  }

  @Test
  void completedReasonIsDistinguishable() {
    CheckpointStore store = store(Instant.parse("2026-08-17T00:00:00Z"));
    store.save(checkpoint("COMPLETED"));
    assertEquals("COMPLETED", store.latest().get().reason());
  }
}
