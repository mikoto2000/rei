package dev.mikoto2000.rei.externalagent;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Codexの設定値をまとめ、利用環境に応じた動作を指定できるようにする。 */
@Data
@ConfigurationProperties("rei.external-agents.codex")
public class CodexProperties {
  /** 有効状態。 */
  private boolean enabled = true;

  /** 実装有効状態。 */
  private boolean implementationEnabled = false;

  /**
   * 実装検証コマンド。
   *
   * <p>Trusted administrator-selected recipe, never supplied by external model output.
   */
  private String implementationTestCommand = "";

  /** 実装検証タイムアウトの秒数。 */
  private int implementationTestTimeoutSeconds = 30;

  /**
   * 永続化会話一覧。
   *
   * <p>Opt-in: the native CLI retains its own session files; Rei stores only the opaque UUID.
   */
  private boolean persistSessions = false;

  /**
   * inherit実行モデル予算。
   *
   * <p>Opt-in accounting of native CLI turn usage in the parent Run/Goal budget.
   */
  private boolean inheritRunModelBudget = false;

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

  /** コマンド。 */
  private String command = "codex";

  /** 合計タイムアウト。 */
  private Duration totalTimeout = Duration.ofMinutes(20);

  /** inactivityタイムアウト。 */
  private Duration inactivityTimeout = Duration.ofMinutes(5);

  /** 最大出力バイト数。 */
  private int maxOutputBytes = 4194304;
}
