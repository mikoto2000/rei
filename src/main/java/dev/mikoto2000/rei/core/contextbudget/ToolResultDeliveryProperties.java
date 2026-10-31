package dev.mikoto2000.rei.core.contextbudget;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** ツールの結果配信の設定値をまとめ、利用環境に応じた動作を指定できるようにする。 */
@Getter
@Setter
@ConfigurationProperties("rei.tool-result-delivery")
public class ToolResultDeliveryProperties {
  /** ページ対象トークン数。 */
  private int pageTargetTokens = 8000;

  /** ページ最小トークン数。 */
  private int pageMinTokens = 256;

  /** ページトークン数の上限。 */
  private int pageMaxTokens = 16000;

  /** 全文取得でも現在の入力残量を超えない、追加の上限。 */
  private int bulkMaxTokens = 64000;

  // ContextAssembler already reserves 4000 model tokens; this is additional optional page headroom.
  /** ページSafetyトークン数。 */
  private int pageSafetyTokens;

  /** 元の結果バイト数の上限。 */
  private long rawResultMaxBytes = 16L * 1024 * 1024;

  /** 元の保存先バイト数の上限。 */
  private long rawStoreMaxBytes = 256L * 1024 * 1024;

  /** 元のキャッシュバイト数の上限。 */
  private long rawCacheMaxBytes = 8L * 1024 * 1024;

  /** レビュー記録台帳。 */
  private ReviewLedger reviewLedger = new ReviewLedger();

  /** モデルへ渡す文脈の管理に必要なレビュー記録台帳を扱う。 */
  @Getter
  @Setter
  public static class ReviewLedger {
    /** 有効状態。 */
    private boolean enabled = true;

    /** 必須条件対象範囲。 */
    private boolean requireCoverage = true;
  }

  /** 利用条件に合わない指定を受け付けないよう、入力内容を検証する。 */
  public void validate() {
    if (pageMinTokens < 1
        || pageTargetTokens < pageMinTokens
        || pageMaxTokens < pageTargetTokens
        || pageMaxTokens > 16000
        || bulkMaxTokens < pageMaxTokens
        || bulkMaxTokens > 1_000_000
        || pageSafetyTokens < 0
        || rawResultMaxBytes < 1
        || rawStoreMaxBytes < 1
        || rawCacheMaxBytes < 0
        || reviewLedger == null)
      throw new IllegalArgumentException("Invalid result delivery budgets");
  }
}
