package dev.mikoto2000.rei.externalagent;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Claudeコードの設定値をまとめ、利用環境に応じた動作を指定できるようにする。 */
@Data
@ConfigurationProperties("rei.external-agents.claude")
public class ClaudeCodeProperties {
  /** 有効状態。 */
  private boolean enabled = false;

  /** 永続化会話一覧。 */
  private boolean persistSessions = false;

  /** 修正提案一覧有効状態。 */
  private boolean fixProposalsEnabled = false;

  /** 実装有効状態。 */
  private boolean implementationEnabled = false;

  /** 実装検証コマンド。 */
  private String implementationTestCommand = "";

  /** 実装検証タイムアウトの秒数。 */
  private int implementationTestTimeoutSeconds = 30;

  /** 並列レビュー有効状態。 */
  private boolean parallelReviewEnabled = false;

  /** 並列レビュータイムアウト。 */
  private Duration parallelReviewTimeout = Duration.ofSeconds(120);

  /** 外部エージェントへの委譲で使用する並列レビュータイムアウトを設定する。 */
  public void setParallelReviewTimeout(Duration value) {
    if (value == null
        || value.compareTo(Duration.ofMillis(100)) < 0
        || value.compareTo(Duration.ofSeconds(120)) > 0)
      throw new IllegalArgumentException("Parallel review timeout must be 100ms to 120s");
    parallelReviewTimeout = value;
  }

  /** OSの機能会話ディレクトリ。 */
  private java.nio.file.Path nativeSessionDirectory =
      dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current()
          .resolve("claude-review-sessions");

  /** コマンド。 */
  private String command = "claude";

  /** 合計タイムアウト。 */
  private Duration totalTimeout = Duration.ofMinutes(5);

  /** inactivityタイムアウト。 */
  private Duration inactivityTimeout = Duration.ofMinutes(2);

  /** 最大出力バイト数。 */
  private int maxOutputBytes = 1048576;
}
