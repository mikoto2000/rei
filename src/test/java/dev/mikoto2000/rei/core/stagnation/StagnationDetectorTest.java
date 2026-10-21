package dev.mikoto2000.rei.core.stagnation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 停滞検出の窓口の結果と状態の契約を検証するテスト。 */
class StagnationDetectorTest {

  /** 実行の停滞への対処で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void initialStagnationCountIsZero() {
    StagnationDetector detector = new StagnationDetector();
    assertEquals(0, detector.stagnationCount());
    assertFalse(detector.isStagnant());
  }

  /** 実行の停滞への対処で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void noProgressIncrementsCount() {
    StagnationDetector detector = new StagnationDetector(4);
    detector.recordIteration(false);
    assertEquals(1, detector.stagnationCount());
  }

  /** 実行の停滞への対処で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void progressResetsCount() {
    StagnationDetector detector = new StagnationDetector(4);
    detector.recordIteration(false);
    detector.recordIteration(false);
    detector.recordIteration(true);
    assertEquals(0, detector.stagnationCount());
  }

  /** 実行の停滞への対処で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void stepCompletedIsProgress() {
    StagnationDetector detector = new StagnationDetector(4);
    detector.recordIteration(false);
    detector.recordProgress(ProgressEvent.STEP_COMPLETED);
    assertEquals(0, detector.stagnationCount());
  }

  /** 実行の停滞への対処で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void fileChangedIsProgress() {
    StagnationDetector detector = new StagnationDetector(4);
    detector.recordIteration(false);
    detector.recordProgress(ProgressEvent.FILE_CHANGED);
    assertEquals(0, detector.stagnationCount());
  }

  /** 実行の停滞への対処で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void taskCompletedItemAddedIsProgress() {
    StagnationDetector detector = new StagnationDetector(4);
    detector.recordIteration(false);
    detector.recordProgress(ProgressEvent.TASK_COMPLETED_ITEM_ADDED);
    assertEquals(0, detector.stagnationCount());
  }

  /** 実行の停滞への対処で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void belowThresholdIsNotStagnant() {
    StagnationDetector detector = new StagnationDetector(4);
    detector.recordIteration(false);
    detector.recordIteration(false);
    detector.recordIteration(false);
    assertFalse(detector.isStagnant());
  }

  /** 実行の停滞への対処で、指定した条件に対する結果と状態の変化を確認する。 */
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

  /** 実行の停滞への対処で、指定した条件に対する結果と状態の変化を確認する。 */
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

  /** 実行の停滞への対処で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void replanCountIsTracked() {
    StagnationDetector detector = new StagnationDetector(4, 2);
    detector.recordReplan();
    assertEquals(1, detector.replanCount());
  }

  /** 実行の停滞への対処で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void maxReplanExceededBlocks() {
    StagnationDetector detector = new StagnationDetector(4, 2);
    detector.recordReplan();
    detector.recordReplan();
    detector.recordReplan();
    assertTrue(detector.isMaxReplanReached());
  }

  /** 実行の停滞への対処で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void repeatedToolCallFingerprintIsDetected() {
    StagnationDetector detector = new StagnationDetector(4);
    detector.recordToolCall("readFile", "src/Foo.java");
    detector.recordToolCall("readFile", "src/Foo.java");
    assertTrue(detector.hasRepeatedToolCall());
  }

  /** 実行の停滞への対処で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void repeatedFailureFingerprintIsDetected() {
    StagnationDetector detector = new StagnationDetector(4);
    detector.recordFailure("IllegalArgumentException", "path must not be blank");
    detector.recordFailure("IllegalArgumentException", "path must not be blank");
    assertTrue(detector.hasRepeatedFailure());
  }
}
