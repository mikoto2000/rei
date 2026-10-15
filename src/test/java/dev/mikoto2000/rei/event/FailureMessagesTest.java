package dev.mikoto2000.rei.event;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FailureMessagesTest {
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

  @Test
  void blankMessageUsesExceptionTypeAndCyclesTerminate() {
    var first = new IllegalStateException();
    var second = new RuntimeException("inner", first);
    first.initCause(second);
    assertEquals("IllegalStateException → inner", FailureMessages.describe(first));
    assertEquals("原因の詳細がありません。", FailureMessages.describe(null));
  }
}
