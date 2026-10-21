package dev.mikoto2000.rei.skills;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * スキルの利用で受け渡す意味によるスキル設定を表す。
 *
 * @param enabled 有効状態
 * @param maxSkills 最大スキル一覧
 * @param minimumSimilarity 最小類似度
 * @param failureBackoffSeconds 失敗情報再試行の待ち時間秒数
 * @param persistentIndexEnabled 保存される位置有効状態
 * @param indexNamespace 位置識別範囲
 */
@ConfigurationProperties("rei.skills.semantic")
public record SemanticSkillProperties(
    boolean enabled,
    int maxSkills,
    double minimumSimilarity,
    int failureBackoffSeconds,
    boolean persistentIndexEnabled,
    String indexNamespace) {
  /** スキルの利用を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public SemanticSkillProperties(
      boolean enabled, int maxSkills, double minimumSimilarity, int failureBackoffSeconds) {
    this(enabled, maxSkills, minimumSimilarity, failureBackoffSeconds, false, null);
  }

  /** 受け取った情報からSemanticSkillPropertiesを作成する。 */
  @org.springframework.boot.context.properties.bind.ConstructorBinding
  public SemanticSkillProperties {
    if (maxSkills == 0) maxSkills = 64;
    if (minimumSimilarity == 0) minimumSimilarity = .55;
    if (failureBackoffSeconds == 0) failureBackoffSeconds = 30;
    if (maxSkills < 1
        || maxSkills > 256
        || !Double.isFinite(minimumSimilarity)
        || minimumSimilarity <= 0
        || minimumSimilarity > 1
        || failureBackoffSeconds < 1
        || failureBackoffSeconds > 3600)
      throw new IllegalArgumentException("Invalid semantic skill limits");
    if (persistentIndexEnabled
        && (indexNamespace == null || !indexNamespace.matches("[A-Za-z0-9._:-]{1,128}")))
      throw new IllegalArgumentException(
          "Persistent skill index requires an explicit model/version namespace");
  }
}
