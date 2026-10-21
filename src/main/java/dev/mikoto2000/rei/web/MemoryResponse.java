package dev.mikoto2000.rei.web;

/**
 * Web APIによる操作で受け渡す記憶応答を表す。
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
 */
public record MemoryResponse(
    String id,
    String content,
    String type,
    String scope,
    String status,
    double confidence,
    String expiresAt,
    String createdAt,
    String updatedAt) {
  /** Web APIによる操作で利用する情報を、受け取った形式から変換する。 */
  public static MemoryResponse from(dev.mikoto2000.rei.memory.model.Memory m) {
    return new MemoryResponse(
        m.id(),
        m.content(),
        m.type().name(),
        m.scope().name(),
        m.status().name(),
        m.confidence(),
        m.expiresAt() == null ? null : m.expiresAt().toString(),
        m.createdAt().toString(),
        m.updatedAt().toString());
  }
}
