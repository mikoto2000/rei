package dev.mikoto2000.rei.subagent;

import dev.mikoto2000.rei.chatgpt.ChatGptException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 補助エージェントへの委譲に必要な外部取得元Partitionerを扱う。
 *
 * <p>Prepare a bounded, ordered plan before dispatch; no provider retry or source mutation.
 */
final class ExternalSourcePartitioner {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private ExternalSourcePartitioner() {}

  /** 補助エージェントへの委譲で用いる分割を返す。 */
  static <T> List<List<T>> split(List<T> sources, Predicate<List<T>> fits) {
    var groups = new ArrayList<List<T>>();
    split(List.copyOf(sources), fits, groups);
    return List.copyOf(groups);
  }

  /** 補助エージェントへの委譲に関する分割を処理する。 */
  private static <T> void split(List<T> sources, Predicate<List<T>> fits, List<List<T>> groups) {
    if (fits.test(sources)) {
      groups.add(List.copyOf(sources));
      return;
    }
    if (sources.size() <= 1)
      throw ChatGptException.delegation(ChatGptException.DelegationFailure.UNSPLITTABLE_INPUT);
    int middle = sources.size() / 2;
    split(sources.subList(0, middle), fits, groups);
    split(sources.subList(middle, sources.size()), fits, groups);
  }
}
