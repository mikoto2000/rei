package dev.mikoto2000.rei.ui.shell;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

/** Shared keyboard/voice presentation. The time is the local time of submission display. */
public final class UserInputFrame {
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
  private UserInputFrame() { }

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
