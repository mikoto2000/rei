package dev.mikoto2000.rei.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;

/** 利用者入力音声フレームの結果と状態の契約を検証するテスト。 */
class UserInputFrameTest {
  /** ターミナルでの対話で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void sameFrameRetainsKeyboardTimeColorAndMultilineFormatting() {
    var frame = UserInputFrame.format("音声入力のテスト\r\n2行目\n", LocalTime.of(1, 37, 16));
    String ls = System.lineSeparator();
    assertThat(frame.toString())
        .isEqualTo(
            ls + "┌ User (01:37:16)" + ls + "音声入力のテスト" + ls + "2行目" + ls + ls + "└" + ls + ls);
    var cyan = AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN);
    assertThat(frame.styleAt(frame.toString().indexOf("User"))).isEqualTo(cyan);
    assertThat(frame.styleAt(frame.toString().indexOf("音声"))).isEqualTo(cyan);
    assertThat(frame.styleAt(frame.toString().indexOf("└"))).isEqualTo(cyan);
  }
}
