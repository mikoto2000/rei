package dev.mikoto2000.rei.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/** 失敗情報メッセージ一覧の結果と状態の契約を検証するテスト。 */
class FailureMessagesTest {
  /** 実行状況の通知で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void showsNestedCauseAndMasksSecretsBeforeClipping() {
    assertEquals(
        "保存に失敗 → disk full password=[REDACTED] retry",
        FailureMessages.describe(
            new IllegalStateException(
                "保存に失敗", new java.io.IOException("disk full password=hidden\nretry"))));
    assertFalse(FailureMessages.text("token=" + "s".repeat(900)).contains("sss"));
    assertEquals(600, FailureMessages.text("a".repeat(900)).length());
  }

  /** 空白のメッセージについて、例外種別を利用することを確認する。 */
  @Test
  void blankMessageUsesExceptionTypeAndCyclesTerminate() {
    var first = new IllegalStateException();
    var second = new RuntimeException("inner", first);
    first.initCause(second);
    assertEquals("IllegalStateException → inner", FailureMessages.describe(first));
    assertEquals("原因の詳細がありません。", FailureMessages.describe(null));
  }
}
