package dev.mikoto2000.rei.activity;

import dev.mikoto2000.rei.activity.ActivityRecord.Activity;
import java.util.Set;

/**
 * 作業状況の把握に必要な作業状況語彙を扱う。
 *
 * <p>Canonical values belong to the semantic projection; original Vision candidates remain
 * evidence.
 */
public final class ActivityVocabulary {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private ActivityVocabulary() {}

  /** 分類一覧。 */
  public static final Set<String> CATEGORIES =
      Set.of(
          "development",
          "research",
          "documentation",
          "communication",
          "social",
          "media",
          "shopping",
          "gaming",
          "monitoring",
          "navigation",
          "idle",
          "other",
          "unknown");

  /** 作業状況の把握で用いる分類を返す。 */
  public static String category(String value) {
    String normalized = ActivityRolePolicy.normalize(value);
    return switch (normalized) {
      case "coding", "debugging", "programming", "devops" -> "development";
      case "sns" -> "social";
      case "video", "music" -> "media";
      case "dashboard", "system_monitor" -> "monitoring";
      default -> CATEGORIES.contains(normalized) ? normalized : "unknown";
    };
  }

  /** 作業状況の把握で用いるサービスを返す。 */
  public static String service(String value) {
    return switch (ActivityRolePolicy.normalize(value)) {
      case "twitter", "x (twitter)", "x(twitter)", "x" -> "x";
      case "youtube music", "youtube-music" -> "youtube-music";
      case "web",
          "local",
          "shell",
          "terminal",
          "local terminal",
          "local editor",
          "powershell",
          "development",
          "java" ->
          "";
      default -> ActivityRolePolicy.normalize(value);
    };
  }

  /** 作業状況の把握で用いる正規を返す。 */
  public static Activity canonical(Activity a) {
    String category = category(a.type());
    if (ActivityRolePolicy.backgroundCandidate(a)) category = "monitoring";
    return new Activity(
        a.monitor(),
        category,
        ActivityRolePolicy.application(a.application()),
        service(a.service()),
        a.contentTitle(),
        ActivityRolePolicy.normalize(a.projectCandidate()));
  }

  /** 作業状況の把握で用いるサービス表示名を返す。 */
  public static String serviceLabel(String value) {
    return switch (service(value)) {
      case "x" -> "X";
      case "youtube" -> "YouTube";
      case "youtube-music" -> "YouTube Music";
      case "github" -> "GitHub";
      case "amazon" -> "Amazon";
      case "slack" -> "Slack";
      case "discord" -> "Discord";
      default -> service(value);
    };
  }

  /** 作業状況の把握で用いるアプリケーション表示名を返す。 */
  public static String applicationLabel(String value) {
    return switch (ActivityRolePolicy.application(value)) {
      case "terminal" -> "ターミナル";
      case "gvim", "vim", "editor" -> "エディタ";
      case "firefox" -> "Firefox";
      case "chrome" -> "Chrome";
      case "edge" -> "Edge";
      case "vscode" -> "VS Code";
      case "", "local", "unknown" -> "";
      default -> value;
    };
  }
}
