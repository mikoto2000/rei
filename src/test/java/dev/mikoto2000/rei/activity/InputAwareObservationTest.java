package dev.mikoto2000.rei.activity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 入力Aware観測内容の結果と状態の契約を検証するテスト。 */
class InputAwareObservationTest {
  /** 日時や期限の条件を再現するために使う検証時刻。 */
  private final ActivityChangeScopeTest.Time clock = new ActivityChangeScopeTest.Time();

  /** 検証に使う実行可否の判断。 */
  private final InputAwareObservation gate = new InputAwareObservation(clock, 15, 300);

  /** {@code sample} の条件を再現するための補助情報を返す。 */
  private DesktopActivityObserver.Lightweight sample(long input, long ticks, String window) {
    return new DesktopActivityObserver.Lightweight(input, ticks, window, "Default", false, true);
  }

  /** 作業状況の把握で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void firstSuccessSkipsUnchangedUntilMaximum() {
    var s = sample(100, 1000, "1");
    assertTrue(gate.begin(s));
    gate.saved();
    gate.end(s);
    clock.advance(15);
    assertFalse(gate.begin(sample(100, 16000, "1")));
    clock.advance(285);
    assertTrue(gate.begin(sample(100, 301000, "1")));
  }

  /** 作業状況の把握で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void failuresRetryAndChangesDuringObservationRemainPending() {
    var s = sample(100, 1000, "1");
    assertTrue(gate.begin(s));
    gate.end(s);
    assertTrue(gate.begin(s));
    gate.saved();
    gate.end(sample(200, 1100, "2"));
    assertTrue(gate.begin(sample(200, 1200, "2")));
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void windowAndInputChangesAndForceTriggerObservation() {
    var s = sample(100, 1000, "1");
    gate.begin(s);
    gate.saved();
    gate.end(s);
    assertTrue(gate.begin(sample(200, 1200, "1")));
    gate.saved();
    gate.end(sample(200, 1200, "1"));
    assertTrue(gate.begin(sample(200, 1300, "2")));
    gate.saved();
    gate.end(sample(200, 1300, "2"));
    gate.force();
    assertTrue(gate.begin(sample(200, 1400, "2")));
  }

  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void unsignedWrapAndNonMonotonicInputNeverMissChanges() {
    var s = sample(0xfffffff0L, 0xfffffff8L, "1");
    gate.begin(s);
    gate.saved();
    gate.end(s);
    assertTrue(gate.begin(sample(5, 0x100000010L, "1")));
    gate.saved();
    gate.end(sample(5, 0x100000010L, "1"));
    assertTrue(gate.begin(sample(3, 0x100000020L, "1")));
  }

  /** 作業状況の把握で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void unknownRestartResumeAndUnlockAreConservative() {
    var s = sample(100, 1000, "1");
    gate.begin(s);
    gate.saved();
    gate.end(s);
    assertTrue(gate.begin(null));
    gate.end(null);
    assertTrue(gate.begin(sample(100, 10, "1")));
    gate.end(s);
    clock.advance(60);
    assertTrue(gate.begin(sample(100, 61000, "1")));
    gate.end(s);
    assertFalse(
        gate.begin(
            new DesktopActivityObserver.Lightweight(100, 62000, "1", "Winlogon", true, true)));
    assertTrue(gate.begin(sample(100, 63000, "1")));
  }

  /** 作業状況の把握で、競合する要求でも、実行順序と作業の分離を守ることを確認する。 */
  @Test
  void concurrentObservationCannotStartAndFailureCannotCommitBaseline() {
    var s = sample(100, 1000, "1");
    assertTrue(gate.begin(s));
    assertFalse(gate.begin(s));
    gate.end(s);
    assertTrue(gate.begin(s));
  }

  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void transientChangeAndSignalDuringObservationCannotBeAcknowledgedByAnOldSave() {
    var s = sample(100, 1000, "1");
    gate.begin(s);
    assertFalse(gate.begin(sample(200, 1200, "2")));
    gate.saved();
    gate.end(s);
    assertTrue(gate.begin(s));
    gate.force();
    gate.saved();
    gate.end(s);
    assertTrue(gate.begin(s));
  }

  /** 短い待機について、作業中時刻を利用することを確認する。 */
  @Test
  void shortSleepUsesWorkingTimeRatherThanInferringIdleFromInput() {
    var s = new DesktopActivityObserver.Lightweight(100, 1000, "1", "Default", false, true, 1000);
    gate.begin(s);
    gate.saved();
    gate.end(s);
    clock.advance(15);
    assertTrue(
        gate.begin(
            new DesktopActivityObserver.Lightweight(
                100, 16000, "1", "Default", false, true, 11000)));
  }
}
