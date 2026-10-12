package dev.mikoto2000.rei.core.configuration;

/** Tool planning instructions shared without inheriting personality, history or authority. */
public final class ToolUsagePolicy {
  private ToolUsagePolicy() { }

  public static final String BATCHING = """
      For multiple independent, already-known targets of the same Multi tool, pass all targets in one array
      in a single call. Do not emit multiple one-item calls to the same tool in the same response.
      Split only when a later target depends on an earlier result or documented limits require it
      (file count, bytes, lines, tokens, or staged writes). A one-item array remains valid for a single target.
      """;
  public static final String INSTRUCTIONS = "Common tool usage policy:\n" + BATCHING;

  public static String appendTo(String prompt) {
    String base = prompt == null ? "" : prompt;
    if (base.contains(INSTRUCTIONS)) return base;
    return base.isBlank() ? INSTRUCTIONS : base.stripTrailing() + "\n\n" + INSTRUCTIONS;
  }
}
