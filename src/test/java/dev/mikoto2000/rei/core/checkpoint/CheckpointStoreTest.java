package dev.mikoto2000.rei.core.checkpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CheckpointStoreTest {

  @Test
  void emptyCheckpointStoreHasNoLatest() {
    CheckpointStore store = new CheckpointStore();
    assertTrue(store.isEmpty());
    assertTrue(store.latest().isEmpty());
  }

  @Test
  void emptyCheckpointRendersBlank() {
    CheckpointStore store = new CheckpointStore();
    assertEquals("", store.renderForPrompt());
  }
}
