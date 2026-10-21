package dev.mikoto2000.rei.vectordocument;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 検索対象文書の管理で受け渡す再評価設定を表す。
 *
 * @param enabled 有効状態
 * @param baseUrl 接続先の基準URL
 * @param apiKey APIキー
 * @param model モデル
 * @param path パス
 * @param inheritRunModelBudget inherit実行モデル予算
 */
@ConfigurationProperties(prefix = "rei.rerank")
public record RerankProperties(
    @DefaultValue("true") boolean enabled,
    String baseUrl,
    String apiKey,
    String model,
    String path,
    @DefaultValue("false") boolean inheritRunModelBudget) {
  /** 検索対象文書の管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public RerankProperties(
      boolean enabled, String baseUrl, String apiKey, String model, String path) {
    this(enabled, baseUrl, apiKey, model, path, false);
  }

  /** 受け取った情報からRerankPropertiesを作成する。 */
  @org.springframework.boot.context.properties.bind.ConstructorBinding
  public RerankProperties {
    if (path == null || path.isBlank()) path = "/v1/rerank";
  }
}
