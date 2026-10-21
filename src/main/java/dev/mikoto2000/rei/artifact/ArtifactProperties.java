package dev.mikoto2000.rei.artifact;

import java.time.Duration;

/** 成果物の設定値をまとめ、利用環境に応じた動作を指定できるようにする。 */
@org.springframework.boot.context.properties.ConfigurationProperties("rei.artifacts")
public class ArtifactProperties {
  /** 有効状態。 */
  private boolean enabled;

  /** バイト数の上限／最大合計バイト数。 */
  private long maxBytes = 32L * 1024 * 1024, maxTotalBytes = 512L * 1024 * 1024;

  /** 最大成果物一覧。 */
  private int maxArtifacts = 1024;

  /** 保持期間。 */
  private Duration retention = Duration.ofDays(30);

  /** 成果物の管理において、有効状態かどうかを判定する。 */
  public boolean isEnabled() {
    return enabled;
  }

  /** 成果物の管理で使用する有効状態を設定する。 */
  public void setEnabled(boolean value) {
    enabled = value;
  }

  /** 成果物の管理で参照するバイト数の上限を返す。 */
  public long getMaxBytes() {
    return maxBytes;
  }

  /** 成果物の管理で使用するバイト数の上限を設定する。 */
  public void setMaxBytes(long value) {
    maxBytes = value;
  }

  /** 成果物の管理で参照する最大合計バイト数を返す。 */
  public long getMaxTotalBytes() {
    return maxTotalBytes;
  }

  /** 成果物の管理で使用する最大合計バイト数を設定する。 */
  public void setMaxTotalBytes(long value) {
    maxTotalBytes = value;
  }

  /** 成果物の管理で参照する最大成果物一覧を返す。 */
  public int getMaxArtifacts() {
    return maxArtifacts;
  }

  /** 成果物の管理で使用する最大成果物一覧を設定する。 */
  public void setMaxArtifacts(int value) {
    maxArtifacts = value;
  }

  /** 成果物の管理で参照する保持期間を返す。 */
  public Duration getRetention() {
    return retention;
  }

  /** 成果物の管理で使用する保持期間を設定する。 */
  public void setRetention(Duration value) {
    retention = value;
  }

  /** 利用条件に合わない指定を受け付けないよう、入力内容を検証する。 */
  public void validate() {
    if (maxBytes < 1
        || maxBytes > 32L * 1024 * 1024
        || maxTotalBytes < maxBytes
        || maxTotalBytes > 1024L * 1024 * 1024
        || maxArtifacts < 1
        || maxArtifacts > 10000
        || retention == null
        || retention.compareTo(Duration.ofMinutes(1)) < 0
        || retention.compareTo(Duration.ofDays(90)) > 0)
      throw new IllegalArgumentException("Invalid Artifact limits");
  }
}
