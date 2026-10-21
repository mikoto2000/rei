package dev.mikoto2000.rei.memory.service;

import java.util.List;

/**
 * 長期記憶の保存と検索で受け渡す記憶解決結果を表す。
 *
 * @param action 操作
 * @param targetIds 対象識別子一覧
 */
public record MemoryResolution(MemoryAction action, List<String> targetIds) {
  /** 受け取った情報からMemoryResolutionを作成する。 */
  public MemoryResolution {
    java.util.Objects.requireNonNull(action);
    targetIds = List.copyOf(targetIds);
  }
}
