package dev.mikoto2000.rei.activity;

import java.util.Set;

/**
 * 作業状況の把握に必要な範囲作業状況規則一覧を扱う。
 *
 * <p>Process guards precede title rules; a document mentioning a service cannot become that
 * service.
 */
final class WindowActivityRules {
  /**
   * 作業状況の把握で受け渡す一致を表す。
   *
   * @param activity 作業状況
   * @param fields 項目一覧
   * @param rule 規則
   */
  record Match(ActivityRecord.Activity activity, ActivityFieldConfidence fields, String rule) {
    /** 作業状況の把握で用いる確信度を返す。 */
    double confidence() {
      return fields.overall();
    }
  }

  /** ブラウザーのタイトルから作業を判別する規則。 */
  private final BrowserTitleRules browsers = new BrowserTitleRules();

  /** 得られた情報を分類し、後続の判断で扱い分けられるようにする。 */
  Match classify(ForegroundWindow window, String monitor) {
    String process = ActivityRolePolicy.application(window.processName());
    String title = window.windowTitle().strip();
    if (process.equals("vscode")) {
      var parts = title.split(" - ");
      String project = "";
      if (parts.length >= 3 && parts[parts.length - 1].equalsIgnoreCase("Visual Studio Code"))
        project = parts[parts.length - 2];
      return match(
          monitor,
          "development",
          "Visual Studio Code",
          "",
          title,
          project,
          project.isBlank() ? .7 : .95,
          "EDITOR_TITLE");
    }
    if (Set.of("firefox", "chrome", "edge").contains(process))
      return browsers.classify(window, monitor);
    if (process.equals("chatgpt"))
      return new Match(
          new ActivityRecord.Activity(monitor, "unknown", window.processName(), "ChatGPT", "", ""),
          new ActivityFieldConfidence(0, 1, .99, 0, 0),
          "CHATGPT_PROCESS");
    return match(
        monitor,
        "unknown",
        window.processName(),
        "",
        "",
        "",
        0,
        process.equals("terminal") ? "GENERIC_TERMINAL" : "UNRECOGNIZED_PROCESS");
  }

  /** 作業状況の把握で用いる一致を返す。 */
  static Match match(
      String monitor,
      String type,
      String application,
      String service,
      String content,
      String project,
      double confidence,
      String reason) {
    return new Match(
        new ActivityRecord.Activity(monitor, type, application, service, content, project),
        new ActivityFieldConfidence(
            confidence,
            1,
            service.isBlank() ? 0 : confidence,
            project.isBlank() ? 0 : confidence,
            content.isBlank() ? 0 : confidence),
        reason);
  }
}
