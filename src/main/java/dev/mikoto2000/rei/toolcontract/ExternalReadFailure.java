package dev.mikoto2000.rei.toolcontract;

/** 外部読み取りの失敗を、機能間の依存を増やさず安全に診断するための契約。 */
public interface ExternalReadFailure {
  /** HTTP応答がある場合にだけ、そのステータスを返す。 */
  default Integer httpStatus() {
    return null;
  }

  /** サーバーで定義した診断コードを返す。 */
  default String diagnosticCode() {
    return "fetch_failed";
  }

  /** URL・応答本文・認証情報を含まない固定の案内を返す。 */
  default String diagnosticMessage() {
    return "外部情報を取得できませんでした。取得制限と接続設定を確認してください。";
  }
}
