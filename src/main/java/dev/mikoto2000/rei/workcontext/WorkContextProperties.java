package dev.mikoto2000.rei.workcontext;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 作業文脈の引き継ぎで受け渡す作業文脈設定を表す。
 *
 * @param autoUpdate 自動更新
 * @param autoPresent 自動存在する
 * @param maxContextTokens 最大文脈トークン数
 * @param maxInputTokens 最大入力トークン数
 * @param timeoutSeconds タイムアウトの秒数
 * @param batchEvidenceTokens 一度に抽出する証拠の推定トークン数
 * @param maxExtractionBatches 一度の更新で許可する抽出回数
 * @param modelFeature 抽出モデルの機能設定名
 * @param maxTurns 最大やり取り一覧
 */
@ConfigurationProperties("rei.work-context")
public record WorkContextProperties(
    @DefaultValue("false") boolean autoUpdate,
    @DefaultValue("true") boolean autoPresent,
    @DefaultValue("1200") int maxContextTokens,
    @DefaultValue("12000") int maxInputTokens,
    @DefaultValue("240") int timeoutSeconds,
    @DefaultValue("20") int maxTurns,
    @DefaultValue("4000") int batchEvidenceTokens,
    @DefaultValue("16") int maxExtractionBatches,
    @DefaultValue("memory") String modelFeature) {
  public WorkContextProperties(
      boolean autoUpdate,
      boolean autoPresent,
      int maxContextTokens,
      int maxInputTokens,
      int timeoutSeconds,
      int maxTurns) {
    this(
        autoUpdate,
        autoPresent,
        maxContextTokens,
        maxInputTokens,
        timeoutSeconds,
        maxTurns,
        4000,
        16,
        "memory");
  }

  /** 受け取った情報からWorkContextPropertiesを作成する。 */
  @org.springframework.boot.context.properties.bind.ConstructorBinding
  public WorkContextProperties {
    if (maxContextTokens < 128
        || maxInputTokens < 1024
        || timeoutSeconds < 1
        || maxTurns < 1
        || maxTurns > 100
        || batchEvidenceTokens < 256
        || maxExtractionBatches < 1
        || maxExtractionBatches > 64
        || modelFeature == null
        || modelFeature.isBlank())
      throw new IllegalArgumentException("Invalid Work Context budgets");
  }
}
