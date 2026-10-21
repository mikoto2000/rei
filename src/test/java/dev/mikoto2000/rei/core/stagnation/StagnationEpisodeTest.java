package dev.mikoto2000.rei.core.stagnation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 停滞経験の記録の結果と状態の契約を検証するテスト。 */
class StagnationEpisodeTest {
  /** 実行の停滞への対処で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
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

  /** 実行の停滞への対処で、指定した条件に対する結果と状態の変化を確認する。 */
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

  /** 実行の停滞への対処で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void firstToolCallAndFirstFailureAreNotRepetitions() {
    StagnationDetector detector = new StagnationDetector();
    detector.recordToolCall("readFile", "A");
    detector.recordFailure("IO", "missing");
    assertFalse(detector.hasRepeatedToolCall());
    assertFalse(detector.hasRepeatedFailure());
  }
}
