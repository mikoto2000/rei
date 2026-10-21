package dev.mikoto2000.rei.memory.service;

import dev.mikoto2000.rei.memory.model.Memory;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** 記憶競合の指定を解決し、利用する対象を確定する。 */
@Component
public class MemoryConflictResolver {

  /** 長期記憶の保存と検索における競合種別の選択肢。 */
  public enum ConflictType {
    /** 該当なし。 */
    NONE,
    /** 重複の有無。 */
    DUPLICATE,
    /** 記憶の内容が矛盾する状態。 */
    CONTRADICTION
  }

  /**
   * 長期記憶の保存と検索で受け渡す競合結果を表す。
   *
   * @param type 種別
   * @param conflicting 競合する内容
   * @param similarityScore 類似度評価値
   */
  public record ConflictResult(ConflictType type, Memory conflicting, double similarityScore) {}

  /** 後続の処理を進められるか、現在の条件を確認する。 */
  public ConflictResult check(Memory candidate, List<Memory> existingMemories) {
    if (candidate == null || existingMemories == null || existingMemories.isEmpty()) {
      return new ConflictResult(ConflictType.NONE, null, 0.0d);
    }
    Memory best = null;
    double bestScore = 0.0d;
    for (Memory memory : existingMemories) {
      double score = computeSimilarity(candidate.content(), memory.content());
      if (score > bestScore) {
        bestScore = score;
        best = memory;
      }
    }
    if (bestScore >= 0.95d) {
      return new ConflictResult(ConflictType.DUPLICATE, best, bestScore);
    }
    if (bestScore >= 0.8d) {
      return new ConflictResult(ConflictType.CONTRADICTION, best, bestScore);
    }
    return new ConflictResult(ConflictType.NONE, best, bestScore);
  }

  /** 長期記憶の保存と検索で用いるcompute類似度を返す。 */
  double computeSimilarity(String left, String right) {
    Set<String> a = terms(left);
    Set<String> b = terms(right);
    if (a.isEmpty() && b.isEmpty()) {
      return 1.0d;
    }
    Set<String> intersection = new HashSet<>(a);
    intersection.retainAll(b);
    Set<String> union = new HashSet<>(a);
    union.addAll(b);
    if (union.isEmpty()) {
      return 0.0d;
    }
    return (double) intersection.size() / (double) union.size();
  }

  /** 長期記憶の保存と検索で用いる検索語句一覧を返す。 */
  private Set<String> terms(String content) {
    if (content == null || content.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(content.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
        .filter(token -> !token.isBlank())
        .collect(Collectors.toSet());
  }
}
