package dev.mikoto2000.rei.application.session;

/** Titles count Unicode code points, never UTF-16 units or encoded bytes. */
public final class SessionTitle {
  private SessionTitle() {}
  public static String from(String message) {
    return message.substring(0, message.offsetByCodePoints(0, Math.min(80, message.codePointCount(0, message.length()))));
  }
}
