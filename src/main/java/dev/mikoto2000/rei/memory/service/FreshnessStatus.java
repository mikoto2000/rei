package dev.mikoto2000.rei.memory.service;

/** 長期記憶の保存と検索における情報の鮮度状態の選択肢。 */
public enum FreshnessStatus {
  /** 有効なキャッシュ。 */
  FRESH,
  /** 古い状態。 */
  STALE,
  /** NO有効期限。 */
  NO_EXPIRATION
}
