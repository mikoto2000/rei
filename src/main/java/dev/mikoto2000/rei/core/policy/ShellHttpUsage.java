package dev.mikoto2000.rei.core.policy;

import java.util.regex.Pattern;

/** Best-effort shell HTTP recognition, never claimed as a general shell network sandbox. */
public final class ShellHttpUsage {
  private static final Pattern HTTP =
      Pattern.compile(
          "(?i)https?://|invoke-restmethod|invoke-webrequest|\\b(?:irm|iwr|curl|wget)(?:\\.exe)?\\b|system\\.net\\.http|httpclient");

  private ShellHttpUsage() {}

  /** Detect common commands including scripts with an explicit HTTP URL. */
  public static boolean recognizable(String command) {
    return command != null && HTTP.matcher(command).find();
  }

  /** Read only the host tool's command envelope; malformed arguments remain untrusted. */
  static boolean input(String input) {
    if (input == null || input.length() > 1_048_576) return false;
    try {
      var json = tools.jackson.databind.json.JsonMapper.builder().build().readTree(input);
      return recognizable(json.path("request").path("command").asText(""));
    } catch (RuntimeException invalid) {
      return false;
    }
  }
}
