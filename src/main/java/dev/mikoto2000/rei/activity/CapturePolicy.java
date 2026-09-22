package dev.mikoto2000.rei.activity;

import java.util.Locale;
import java.util.regex.Pattern;

public record CapturePolicy(ActivityProperties properties) {
  public boolean excluded(ForegroundWindow window) {
    if (window == null || window.processName() == null || window.processName().isBlank()
        || window.windowTitle() == null || window.windowId() == null || window.processId() <= 0) return true;
    return properties.getExcludedProcesses().stream().anyMatch(p -> process(p).equals(process(window.processName())))
        || properties.getExcludedWindowTitlePatterns().stream().anyMatch(p -> glob(p).matcher(window.windowTitle()).matches());
  }
  private static String process(String value) { return value.toLowerCase(Locale.ROOT).replaceFirst("\\.exe$", ""); }
  private static Pattern glob(String value) {
    var pattern = new StringBuilder();
    for (char c : value.toCharArray()) pattern.append(c == '*' ? ".*" : c == '?' ? "." : Pattern.quote(String.valueOf(c)));
    return Pattern.compile(pattern.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
  }
}
