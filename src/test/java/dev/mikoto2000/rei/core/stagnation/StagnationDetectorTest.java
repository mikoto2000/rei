package dev.mikoto2000.rei.core.stagnation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StagnationDetectorTest {

  @Test
  void initialStagnationCountIsZero() {
    StagnationDetector detector = new StagnationDetector();
    assertEquals(0, detector.stagnationCount());
    assertFalse(detector.isStagnant());
  }

  @Test
  void noProgressIncrementsCount() {
    StagnationDetector detector = new StagnationDetector(4);
    detector.recordIteration(false);
    assertEquals(1, detector.stagnationCount());
  }

  @Test
  void progressResetsCount() {
    StagnationDetector detector = new StagnationDetector(4);
    detector.recordIteration(false);
    detector.recordIteration(false);
    detector.recordIteration(true);
    assertEquals(0, detector.stagnationCount());
  }

  @Test
  void stepCompletedIsProgress() {
    StagnationDetector detector = new StagnationDetector(4);
    detector.recordIteration(false);
    detector.recordProgress(ProgressEvent.STEP_COMPLETED);
    assertEquals(0, detector.stagnationCount());
  }

  @Test
  void fileChangedIsProgress() {
    StagnationDetector detector = new StagnationDetector(4);
    detector.recordIteration(false);
    detector.recordProgress(ProgressEvent.FILE_CHANGED);
    assertEquals(0, detector.stagnationCount());
  }

  @Test
  void taskCompletedItemAddedIsProgress() {
    StagnationDetector detector = new StagnationDetector(4);
    detector.recordIteration(false);
    detector.recordProgress(ProgressEvent.TASK_COMPLETED_ITEM_ADDED);
    assertEquals(0, detector.stagnationCount());
  }

  @Test
  void belowThresholdIsNotStagnant() {
    StagnationDetector detector = new StagnationDetector(4);
    detector.recordIteration(false);
    detector.recordIteration(false);
    detector.recordIteration(false);
    assertFalse(detector.isStagnant());
  }

  @Test
  void thresholdReachedRequestsReplan() {
    StagnationDetector detector = new StagnationDetector(4);
    detector.recordIteration(false);
    detector.recordIteration(false);
    detector.recordIteration(false);
    detector.recordIteration(false);
    assertTrue(detector.isStagnant());
    assertTrue(detector.isReplanRequested());
  }

  @Test
  void progressClearsReplanRequested() {
    StagnationDetector detector = new StagnationDetector(4);
    detector.recordIteration(false);
    detector.recordIteration(false);
    detector.recordIteration(false);
    detector.recordIteration(false);
    assertTrue(detector.isReplanRequested());
    detector.recordProgress(ProgressEvent.STEP_COMPLETED);
    assertFalse(detector.isReplanRequested());
    assertEquals(0, detector.stagnationCount());
  }

  @Test
  void replanCountIsTracked() {
    StagnationDetector detector = new StagnationDetector(4, 2);
    detector.recordReplan();
    assertEquals(1, detector.replanCount());
  }

  @Test
  void maxReplanExceededBlocks() {
    StagnationDetector detector = new StagnationDetector(4, 2);
    detector.recordReplan();
    detector.recordReplan();
    detector.recordReplan();
    assertTrue(detector.isMaxReplanReached());
  }

  @Test
  void repeatedToolCallFingerprintIsDetected() {
    StagnationDetector detector = new StagnationDetector(4);
    detector.recordToolCall("readFile", "src/Foo.java");
    detector.recordToolCall("readFile", "src/Foo.java");
    assertTrue(detector.hasRepeatedToolCall());
  }

  @Test
  void repeatedFailureFingerprintIsDetected() {
    StagnationDetector detector = new StagnationDetector(4);
    detector.recordFailure("IllegalArgumentException", "path must not be blank");
    detector.recordFailure("IllegalArgumentException", "path must not be blank");
    assertTrue(detector.hasRepeatedFailure());
  }
}
