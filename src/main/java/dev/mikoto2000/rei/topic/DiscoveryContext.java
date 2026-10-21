package dev.mikoto2000.rei.topic;

import java.time.Instant;
import java.util.List;

/**
 * 話題の管理で受け渡す利用先の検出文脈を表す。
 *
 * @param seeds 開始時の候補
 * @param currentTime 現在時刻
 */
public record DiscoveryContext(List<String> seeds, Instant currentTime) {
  /** 受け取った情報からDiscoveryContextを作成する。 */
  public DiscoveryContext {
    seeds = seeds == null ? List.of() : List.copyOf(seeds);
    if (currentTime == null) throw new IllegalArgumentException("currentTime must not be null");
  }
}
