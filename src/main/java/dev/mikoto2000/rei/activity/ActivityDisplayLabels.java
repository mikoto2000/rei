package dev.mikoto2000.rei.activity;

/**
 * 作業状況の把握に必要な作業状況表示表示名一覧を扱う。
 *
 * <p>Presentation aliases only: do not change foreground matching or original evidence.
 */
public final class ActivityDisplayLabels {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private ActivityDisplayLabels() {}

  /**
   * 作業状況の把握で用いる正規を返す。
   *
   * <p>Known aliases tolerate separator differences; unknown proper names retain their spelling.
   */
  public static String canonical(String value) {
    String normalized = ActivityRolePolicy.normalize(value);
    String compact = normalized.replaceAll("[\\s\\p{Punct}]+", "");
    return switch (compact) {
      case "windowsterminal", "terminal", "localterminal", "cmd", "cmdexe", "powershell", "pwsh" ->
          "terminal";
      case "texteditor", "localeditor", "editor", "gvim", "vim" -> "editor";
      case "locallogfile", "logfile" -> "log-file";
      case "twitter", "x", "xtwitter", "x旧twitter", "xbrowsing" -> "x";
      case "youtubemusic" -> "youtube-music";
      case "visualstudiocode", "vscode", "code" -> "vscode";
      default -> normalized;
    };
  }

  /** 作業状況の把握で用いる表示名を返す。 */
  public static String label(String value) {
    String text = value == null ? "" : value.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
    String label =
        switch (canonical(text)) {
          case "browser" -> "ブラウザ";
          case "cmd",
              "cmd.exe",
              "terminal",
              "local terminal",
              "powershell",
              "pwsh",
              "windows terminal" ->
              "ターミナル";
          case "gradle" -> "ビルド";
          case "python" -> "Python関連";
          case "shopping" -> "ショッピング";
          case "video" -> "動画";
          case "openai" -> "AIツール";
          case "chatgpt" -> "ChatGPT";
          case "notion" -> "Notion";
          case "asus" -> "ASUS";
          case "twitter", "x", "x (twitter)" -> "X";
          case "youtube" -> "YouTube";
          case "youtube music", "youtube-music" -> "YouTube Music";
          case "github" -> "GitHub";
          case "visual studio code", "vscode", "vs code", "code" -> "VS Code";
          case "slack" -> "Slack";
          case "discord" -> "Discord";
          case "amazon" -> "Amazon";
          case "firefox" -> "Firefox";
          case "chrome" -> "Chrome";
          case "gvim", "vim", "local editor", "editor" -> "エディタ";
          case "log-file" -> "ローカルログ";
          case "local", "unknown", "other", "" -> "";
          default -> text;
        };
    return label.substring(0, Math.min(60, label.length()));
  }

  /**
   * 作業状況の把握で用いるプロジェクトを返す。
   *
   * <p>Summary-only project validation. Never reinterpret a topic, app or service as a project.
   */
  public static String project(ActivityRecord.Activity candidate, ActivityRecord record) {
    String project = ActivityRolePolicy.normalize(candidate.projectCandidate());
    if (!project.matches("[a-z][a-z0-9_.-]{1,59}")) return "";
    if (ActivityVocabulary.CATEGORIES.contains(project)
        || !ActivityVocabulary.category(project).equals("unknown")) return "";
    String key = canonical(project);
    if (java.util.Set.of(
            "terminal",
            "editor",
            "log-file",
            "browser",
            "x",
            "github",
            "youtube",
            "youtube-music",
            "chatgpt",
            "openai",
            "firefox",
            "chrome",
            "edge",
            "vscode",
            "slack",
            "discord",
            "amazon",
            "python",
            "gradle",
            "notion",
            "asus",
            "local",
            "other",
            "unknown",
            "web",
            "browsing",
            "coding")
        .contains(key)) return "";
    if (record.inference().activities().stream()
        .anyMatch(
            a -> key.equals(canonical(a.application())) || key.equals(canonical(a.service()))))
      return "";
    return project;
  }

  /** 作業状況の把握で用いる候補を返す。 */
  public static String candidate(ActivityRecord.Activity a) {
    String service = ActivityVocabulary.service(a.service());
    return label(service.isBlank() ? a.application() : a.service());
  }
}
