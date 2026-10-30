package dev.mikoto2000.rei.skills;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** エージェントスキル一覧の設定値をまとめ、利用環境に応じた動作を指定できるようにする。 */
@ConfigurationProperties(prefix = "rei.skills")
public class AgentSkillsProperties {

  /** 有効状態。 */
  private boolean enabled = true;

  /** ディレクトリ一覧。 */
  private List<String> directories =
      new ArrayList<>(
          List.of(
              dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current()
                  .resolve("skills")
                  .toString()));

  /** 最大選択中。 */
  private int maxSelected = 3;

  /** 明示指定に暗黙選択を追加する互換設定。 */
  private boolean implicitWithExplicit;

  /** 暗黙選択のモデル応答完了までの上限。 */
  private java.time.Duration selectorTimeout = java.time.Duration.ofSeconds(30);

  public boolean isImplicitWithExplicit() {
    return implicitWithExplicit;
  }

  public void setImplicitWithExplicit(boolean implicitWithExplicit) {
    this.implicitWithExplicit = implicitWithExplicit;
  }

  public java.time.Duration getSelectorTimeout() {
    return selectorTimeout;
  }

  public void setSelectorTimeout(java.time.Duration selectorTimeout) {
    if (selectorTimeout == null
        || selectorTimeout.isZero()
        || selectorTimeout.isNegative()
        || selectorTimeout.compareTo(java.time.Duration.ofMinutes(10)) > 0)
      throw new IllegalArgumentException("selector-timeout must be positive and <= 10 minutes");
    this.selectorTimeout = selectorTimeout;
  }

  /** スキルの利用において、有効状態かどうかを判定する。 */
  public boolean isEnabled() {
    return enabled;
  }

  /** スキルの利用で使用する有効状態を設定する。 */
  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  /** スキルの利用で参照するディレクトリ一覧を返す。 */
  public List<String> getDirectories() {
    return directories;
  }

  /** スキルの利用で使用するディレクトリ一覧を設定する。 */
  public void setDirectories(List<String> directories) {
    this.directories = directories == null ? new ArrayList<>() : new ArrayList<>(directories);
  }

  /** スキルの利用で参照する最大選択中を返す。 */
  public int getMaxSelected() {
    return Math.max(1, maxSelected);
  }

  /** スキルの利用で使用する最大選択中を設定する。 */
  public void setMaxSelected(int maxSelected) {
    this.maxSelected = maxSelected;
  }
}
