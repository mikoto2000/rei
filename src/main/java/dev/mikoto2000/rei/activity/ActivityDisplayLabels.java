package dev.mikoto2000.rei.activity;

/** Presentation aliases only: do not change foreground matching or original evidence. */
public final class ActivityDisplayLabels {
  private ActivityDisplayLabels() {}
  public static String label(String value) {
    String text=value==null?"":value.replaceAll("[\\p{Cntrl}\\s]+"," ").strip();
    String label=switch(ActivityRolePolicy.normalize(text)) {
      case "browser" -> "ブラウザ";
      case "cmd","cmd.exe","terminal","local terminal","powershell","pwsh","windows terminal" -> "ターミナル";
      case "gradle" -> "ビルド";
      case "python" -> "Python関連";
      case "shopping" -> "ショッピング";
      case "video" -> "動画";
      case "openai" -> "AIツール";
      case "chatgpt" -> "ChatGPT";
      case "notion" -> "Notion";
      case "asus" -> "ASUS";
      case "twitter","x","x (twitter)" -> "X";
      case "youtube" -> "YouTube";
      case "youtube music","youtube-music" -> "YouTube Music";
      case "github" -> "GitHub";
      case "visual studio code","vscode","vs code","code" -> "VS Code";
      case "slack" -> "Slack";case "discord" -> "Discord";case "amazon" -> "Amazon";
      case "firefox" -> "Firefox";case "chrome" -> "Chrome";
      case "gvim","vim","local editor","editor" -> "エディタ";
      case "local","unknown","other","" -> "";
      default -> text;
    };
    return label.substring(0,Math.min(60,label.length()));
  }
  public static String candidate(ActivityRecord.Activity a) {
    String service=ActivityVocabulary.service(a.service());
    return label(service.isBlank()?a.application():a.service());
  }
}
