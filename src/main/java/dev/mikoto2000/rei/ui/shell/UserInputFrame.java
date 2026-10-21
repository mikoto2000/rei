package dev.mikoto2000.rei.ui.shell;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

/**
 * ターミナルでの対話に必要な利用者入力観測単位を扱う。
 *
 * <p>Shared keyboard/voice presentation. The time is the local time of submission display.
 */
public final class UserInputFrame {
  /** 時刻。 */
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private UserInputFrame() {}

  /** 受け取った情報を、表示や受け渡しに適した形式へ整える。 */
  public static AttributedString format(String input, LocalTime time) {
    var builder = new AttributedStringBuilder();
    var style = AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN);
    builder.append(System.lineSeparator());
    builder.append("┌ User (" + time.format(TIME) + ")", style);
    builder.append(System.lineSeparator());
    for (String line : input.split("\\R", -1)) {
      builder.append(line, style);
      builder.append(System.lineSeparator());
    }
    builder.append("└", style);
    builder.append(System.lineSeparator());
    builder.append(System.lineSeparator());
    return builder.toAttributedString();
  }
}
