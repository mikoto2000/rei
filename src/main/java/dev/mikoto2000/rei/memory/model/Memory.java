package dev.mikoto2000.rei.memory.model;

import java.time.OffsetDateTime;

/**
 * 長期記憶の表現で受け渡す記憶を表す。
 *
 * @param id 識別子
 * @param content 内容
 * @param type 種別
 * @param scope 対象範囲
 * @param status 状態
 * @param confidence 確信度
 * @param expiresAt 有効期限
 * @param createdAt 作成日時
 * @param updatedAt 更新日時
 * @param observedAt 観測日時
 * @param validUntil 有効終了日時
 */
public record Memory(
    String id,
    String content,
    MemoryType type,
    MemoryScope scope,
    MemoryStatus status,
    double confidence,
    OffsetDateTime expiresAt,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    OffsetDateTime observedAt,
    OffsetDateTime validUntil) {

  /** 長期記憶の表現を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public Memory(
      String id,
      String content,
      MemoryType type,
      MemoryScope scope,
      MemoryStatus status,
      double confidence,
      OffsetDateTime expiresAt,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {
    this(
        id,
        content,
        type,
        scope,
        status,
        confidence,
        expiresAt,
        createdAt,
        updatedAt,
        createdAt,
        expiresAt);
  }
}
