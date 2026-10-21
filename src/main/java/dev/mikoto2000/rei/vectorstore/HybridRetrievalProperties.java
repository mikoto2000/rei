package dev.mikoto2000.rei.vectorstore;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 関連文書の検索で受け渡すHybrid関連情報の取得設定を表す。
 *
 * @param enabled 有効状態
 * @param candidateLimit 候補上限
 * @param rankConstant 順位固定値
 * @param bm25Enabled bm25有効状態
 */
@ConfigurationProperties("rei.vector-document.retrieval")
public record HybridRetrievalProperties(
    boolean enabled, int candidateLimit, int rankConstant, boolean bm25Enabled) {
  /** 関連文書の検索を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public HybridRetrievalProperties(boolean enabled, int candidateLimit, int rankConstant) {
    this(enabled, candidateLimit, rankConstant, false);
  }

  /** 受け取った情報からHybridRetrievalPropertiesを作成する。 */
  @org.springframework.boot.context.properties.bind.ConstructorBinding
  public HybridRetrievalProperties {
    if (candidateLimit == 0) candidateLimit = 40;
    if (rankConstant == 0) rankConstant = 60;
    if (candidateLimit < 1 || candidateLimit > 256 || rankConstant < 1 || rankConstant > 1000)
      throw new IllegalArgumentException("Invalid hybrid retrieval limits");
  }
}
