package dev.mikoto2000.rei.checkpoint;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 再開用の記録の設定値をまとめ、利用環境に応じた動作を指定できるようにする。 */
@Component
@ConfigurationProperties(prefix = "rei.checkpoint")
public class CheckpointProperties {
  /** 有効状態。 */
  private boolean enabled = true;

  /** 最大改訂一覧／最大状態の記録バイト数／文脈文字数／保持期間日数。 */
  private int maxRevisions = 10000,
      maxSnapshotBytes = 262144,
      contextCharacters = 12000,
      retentionDays = 90;

  /** バイト数の上限。 */
  private long maxBytes = 134217728;

  /** 作業の中断と再開において、有効状態かどうかを判定する。 */
  public boolean isEnabled() {
    return enabled;
  }

  /** 作業の中断と再開で使用する有効状態を設定する。 */
  public void setEnabled(boolean value) {
    enabled = value;
  }

  /** 作業の中断と再開で参照する最大改訂一覧を返す。 */
  public int getMaxRevisions() {
    return maxRevisions;
  }

  /** 作業の中断と再開で使用する最大改訂一覧を設定する。 */
  public void setMaxRevisions(int value) {
    if (value < 1) throw new IllegalArgumentException();
    maxRevisions = value;
  }

  /** 作業の中断と再開で参照する最大状態の記録バイト数を返す。 */
  public int getMaxSnapshotBytes() {
    return maxSnapshotBytes;
  }

  /** 作業の中断と再開で使用する最大状態の記録バイト数を設定する。 */
  public void setMaxSnapshotBytes(int value) {
    if (value < 1024) throw new IllegalArgumentException();
    maxSnapshotBytes = value;
  }

  /** 作業の中断と再開で参照する文脈文字数を返す。 */
  public int getContextCharacters() {
    return contextCharacters;
  }

  /** 作業の中断と再開で使用する文脈文字数を設定する。 */
  public void setContextCharacters(int value) {
    if (value < 1024) throw new IllegalArgumentException();
    contextCharacters = value;
  }

  /** 作業の中断と再開で参照する保持期間日数を返す。 */
  public int getRetentionDays() {
    return retentionDays;
  }

  /** 作業の中断と再開で使用する保持期間日数を設定する。 */
  public void setRetentionDays(int value) {
    if (value < 1) throw new IllegalArgumentException();
    retentionDays = value;
  }

  /** 作業の中断と再開で参照するバイト数の上限を返す。 */
  public long getMaxBytes() {
    return maxBytes;
  }

  /** 作業の中断と再開で使用するバイト数の上限を設定する。 */
  public void setMaxBytes(long value) {
    if (value < 1024) throw new IllegalArgumentException();
    maxBytes = value;
  }
}
