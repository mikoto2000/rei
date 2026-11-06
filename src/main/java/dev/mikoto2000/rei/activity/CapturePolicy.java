package dev.mikoto2000.rei.activity;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 作業状況の把握で受け渡す取得記録判断基準を表す。
 */
public final class CapturePolicy {
  /** 取得対象から除外するプロセス名を読む操作。 */
  private final Supplier<List<String>> excludedProcesses;

  /** 取得対象から除外するウィンドウ名のパターンを読む操作。 */
  private final Supplier<List<String>> excludedWindowTitlePatterns;

  /** 除外条件に必要な設定を受け取る。 */
  public CapturePolicy(ActivityProperties properties) {
    excludedProcesses = () -> properties.getExcludedProcesses();
    excludedWindowTitlePatterns = () -> properties.getExcludedWindowTitlePatterns();
  }

  /** 作業状況の把握において、対象外に該当するか判定する。 */
  public boolean excluded(ForegroundWindow window) {
    if (window == null
        || window.processName() == null
        || window.processName().isBlank()
        || window.windowTitle() == null
        || window.windowId() == null
        || window.processId() <= 0) return true;
    return excludedProcesses.get().stream()
            .anyMatch(p -> process(p).equals(process(window.processName())))
        || excludedWindowTitlePatterns.get().stream()
            .anyMatch(p -> glob(p).matcher(window.windowTitle()).matches());
  }

  /** 作業状況の把握で用いるプロセスを返す。 */
  private static String process(String value) {
    return value.toLowerCase(Locale.ROOT).replaceFirst("\\.exe$", "");
  }

  /** 作業状況の把握で用いるパスの照合規則を返す。 */
  private static Pattern glob(String value) {
    var pattern = new StringBuilder();
    for (char c : value.toCharArray())
      pattern.append(c == '*' ? ".*" : c == '?' ? "." : Pattern.quote(String.valueOf(c)));
    return Pattern.compile(
        pattern.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
  }
}
