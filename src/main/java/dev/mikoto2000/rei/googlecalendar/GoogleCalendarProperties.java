package dev.mikoto2000.rei.googlecalendar;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 予定の管理で受け渡すGoogle予定表設定を表す。
 *
 * @param applicationName アプリケーション名前
 * @param credentialsPath 認証情報パス
 * @param tokensDirectory トークン数ディレクトリ
 * @param calendar 予定表
 * @param task タスク
 */
@ConfigurationProperties(prefix = "rei.google")
public record GoogleCalendarProperties(
    String applicationName,
    String credentialsPath,
    String tokensDirectory,
    CalendarProperties calendar,
    TaskProperties task) {

  /**
   * 予定の管理で受け渡す予定表設定を表す。
   *
   * @param enabled 有効状態
   * @param defaultCalendarId 既定予定表識別子
   * @param timeZone 時刻タイムゾーン
   */
  public record CalendarProperties(boolean enabled, String defaultCalendarId, String timeZone) {}

  /**
   * 予定の管理で受け渡すタスク設定を表す。
   *
   * @param enabled 有効状態
   */
  public record TaskProperties(boolean enabled) {}
}
