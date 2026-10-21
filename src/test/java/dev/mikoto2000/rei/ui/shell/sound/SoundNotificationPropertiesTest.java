package dev.mikoto2000.rei.ui.shell.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/** 音通知通知設定の結果と状態の契約を検証するテスト。 */
class SoundNotificationPropertiesTest {

  /** ターミナルの音通知で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void enabledDefaultIsFalse() {
    SoundNotificationProperties props = new SoundNotificationProperties();
    assertFalse(props.isEnabled());
  }

  /** ターミナルの音通知で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void commandDefaultIsEmptyList() {
    SoundNotificationProperties props = new SoundNotificationProperties();
    assertEquals(0, props.getCommand().size());
  }
}
