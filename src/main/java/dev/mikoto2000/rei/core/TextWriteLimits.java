package dev.mikoto2000.rei.core;

/** Independent bounded text-edit budgets. System properties can lower the documented ceilings. */
final class TextWriteLimits {
  private TextWriteLimits() {}

  static int fileBytes() { return limit("file-bytes", 1048576, 1048576); }
  static int editBytes() { return limit("edit-bytes", 65536, 65536); }
  static int requestBytes() { return limit("request-bytes", 262144, 1048576); }
  static int totalBytes() { return limit("total-bytes", 8388608, 8388608); }
  static int payloadBytes() { return limit("payload-bytes", 16777216, 16777216); }
  static int diffBytes() { return limit("diff-bytes", 16384, 65536); }

  private static int limit(String name, int fallback, int ceiling) {
    int value = Integer.getInteger("rei.text-write." + name, fallback);
    if (value < 256 || value > ceiling) throw new IllegalArgumentException("Invalid text-write budget: " + name);
    return value;
  }
}
