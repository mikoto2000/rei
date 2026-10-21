package dev.mikoto2000.rei.core.stagnation;

/**
 * 実行の停滞への対処で受け渡す進捗根拠を表す。
 *
 * <p>Immutable explanation; revision and acquisition progress never imply completed work.
 *
 * @param kind 種類
 * @param description 説明
 * @param source 取得元
 * @param revision 改訂番号
 * @param acquisitionProgress 情報の取得進捗
 */
public record ProgressEvidence(
    ProgressEvent kind,
    String description,
    String source,
    String revision,
    boolean acquisitionProgress) {
  /** 実行の停滞への対処を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ProgressEvidence(ProgressEvent kind, String description, String source, String revision) {
    this(kind, description, source, revision, false);
  }

  /** 実行の停滞への対処を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ProgressEvidence(ProgressEvent kind, String description, String source) {
    this(kind, description, source, null, false);
  }

  /**
   * 実行の停滞への対処で用いる確保済み対象範囲を返す。
   *
   * <p>An exact, previously unseen delivered range, rather than a new query or changing metadata.
   */
  public static ProgressEvidence acquiredRange(String description, String source, String revision) {
    return new ProgressEvidence(ProgressEvent.NEW_INFORMATION, description, source, revision, true);
  }
}
