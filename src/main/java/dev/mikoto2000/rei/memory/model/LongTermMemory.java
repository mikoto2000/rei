package dev.mikoto2000.rei.memory.model;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 長期記憶の表現で受け渡す長期記憶を表す。
 *
 * <p>Extended view of the existing memories table; legacy rows remain available to legacy APIs.
 *
 * @param id 識別子
 * @param scope 対象範囲
 * @param projectId 所属プロジェクトの識別子
 * @param type 種別
 * @param content 内容
 * @param summary 要約
 * @param confidence 確信度
 * @param importance 重要度
 * @param status 状態
 * @param createdAt 作成日時
 * @param updatedAt 更新日時
 * @param lastAccessedAt 直近Accessed日時
 * @param validFrom 有効起点
 * @param validUntil 有効終了日時
 * @param supersededBy 置き換え済みの内容別
 * @param sources 取得元一覧
 * @param tags タグ一覧
 */
public record LongTermMemory(
    String id,
    MemoryScope scope,
    String projectId,
    MemoryType type,
    String content,
    String summary,
    double confidence,
    double importance,
    MemoryStatus status,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    OffsetDateTime lastAccessedAt,
    OffsetDateTime validFrom,
    OffsetDateTime validUntil,
    String supersededBy,
    List<MemorySource> sources,
    List<String> tags) {
  /** 受け取った情報からLongTermMemoryを作成する。 */
  public LongTermMemory {
    sources = List.copyOf(sources);
    tags = List.copyOf(tags);
  }
}
