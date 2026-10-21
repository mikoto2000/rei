package dev.mikoto2000.rei.episode;

/**
 * 過去の経験の活用で受け渡す経験の記録設定を表す。
 *
 * @param enabled 有効状態
 * @param denseEnabled ベクトルによる有効状態
 */
@org.springframework.boot.context.properties.ConfigurationProperties("rei.memory.episodes")
public record EpisodeProperties(boolean enabled, boolean denseEnabled) {
  /** 受け取った情報からEpisodePropertiesを作成する。 */
  @org.springframework.boot.context.properties.bind.ConstructorBinding
  public EpisodeProperties {}

  /** 過去の経験の活用を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public EpisodeProperties(boolean enabled) {
    this(enabled, false);
  }
}
