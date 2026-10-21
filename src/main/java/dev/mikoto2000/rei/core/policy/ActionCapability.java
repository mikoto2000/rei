package dev.mikoto2000.rei.core.policy;

/** ツール利用の権限制御における操作操作権限の選択肢。 */
public enum ActionCapability {
  /** 読み取り。 */
  READ,
  /** ローカル書き込み。 */
  LOCAL_WRITE,
  /** 実行。 */
  EXECUTE,
  /** ネットワーク読み取り。 */
  NETWORK_READ,
  /** ネットワーク書き込み。 */
  NETWORK_WRITE,
  /** 外部SIDEEFFECT。 */
  EXTERNAL_SIDE_EFFECT,
  /** 削除など取り消しを伴う操作の権限。 */
  DESTRUCTIVE,
  /** INBOX読み取り。 */
  INBOX_READ,
  /** INBOX利用権の確保。 */
  INBOX_CLAIM,
  /** INBOX結果書き込み。 */
  INBOX_RESULT_WRITE
}
