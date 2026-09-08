package dev.mikoto2000.rei.core.stagnation;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class StagnationEpisodeTest {
  @Test
  void replanningGivesFourMoreAttemptsWithoutErasingEpisode() {
    StagnationDetector detector = new StagnationDetector();
    for (int episode = 0; episode < 3; episode++) {
      for (int i = 0; i < 4; i++) detector.recordIteration(false);
      assertTrue(detector.isReplanRequested());
      if (episode < 2) {
        detector.recordReplan();
        assertEquals(0, detector.stagnationCount());
        assertEquals(episode + 1, detector.replanCount());
      }
    }
    assertTrue(detector.isMaxReplanReached());
  }

  @Test
  void meaningfulProgressEndsEpisode() {
    StagnationDetector detector = new StagnationDetector();
    for (int i = 0; i < 4; i++) detector.recordIteration(false);
    detector.recordReplan();
    detector.recordIteration(false);
    detector.recordProgress(ProgressEvent.FILE_CHANGED);
    assertEquals(0, detector.stagnationCount());
    assertEquals(0, detector.replanCount());
    assertFalse(detector.isReplanRequested());
    for (int i = 0; i < 100; i++) detector.recordIteration(true);
    assertFalse(detector.isStagnant());
  }

  @Test
  void firstToolCallAndFirstFailureAreNotRepetitions() {
    StagnationDetector detector = new StagnationDetector();
    detector.recordToolCall("readFile", "A");
    detector.recordFailure("IO", "missing");
    assertFalse(detector.hasRepeatedToolCall());
    assertFalse(detector.hasRepeatedFailure());
  }
}
