package dev.mikoto2000.rei.memory.model;

import java.util.List;
import java.util.Set;

/**
 * 長期記憶の表現で受け渡す記憶候補を表す。
 *
 * @param type 種別
 * @param scope 対象範囲
 * @param content 内容
 * @param summary 要約
 * @param confidence 確信度
 * @param importance 重要度
 * @param sourceTurnIds 取得元一回のやり取り識別子一覧
 * @param tags タグ一覧
 */
public record MemoryCandidate(
    MemoryType type,
    MemoryScope scope,
    String content,
    String summary,
    double confidence,
    double importance,
    List<String> sourceTurnIds,
    List<String> tags) {
  /** 受け取った情報からMemoryCandidateを作成する。 */
  public MemoryCandidate {
    if (type == null
        || !Set.of(
                MemoryType.FACT,
                MemoryType.PREFERENCE,
                MemoryType.DECISION,
                MemoryType.CONSTRAINT,
                MemoryType.PROJECT_STATE,
                MemoryType.PROCEDURE,
                MemoryType.LESSON,
                MemoryType.RELATION)
            .contains(type)) throw new IllegalArgumentException("Invalid memory type");
    if (scope != MemoryScope.GLOBAL && scope != MemoryScope.PROJECT)
      throw new IllegalArgumentException("Invalid scope");
    if (content == null
        || content.isBlank()
        || content.length() > 4000
        || summary == null
        || summary.isBlank()
        || summary.length() > 500) throw new IllegalArgumentException("Invalid memory text");
    if (!Double.isFinite(confidence)
        || confidence < 0
        || confidence > 1
        || !Double.isFinite(importance)
        || importance < 0
        || importance > 1) throw new IllegalArgumentException("Invalid score");
    sourceTurnIds = List.copyOf(sourceTurnIds);
    tags = List.copyOf(tags);
    if (sourceTurnIds.isEmpty()
        || sourceTurnIds.stream().anyMatch(String::isBlank)
        || tags.size() > 20
        || tags.stream().anyMatch(t -> t.isBlank() || t.length() > 100))
      throw new IllegalArgumentException("Invalid sources or tags");
  }
}
