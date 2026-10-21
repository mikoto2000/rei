package dev.mikoto2000.rei.vectorstore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;

/**
 * 関連文書の検索に必要なReciprocal順位Fusionを扱う。
 *
 * <p>Bounded vendor-independent rank arithmetic; scores from different retrievers are never added.
 */
public final class ReciprocalRankFusion {
  /**
   * 関連文書の検索で受け渡す順位を表す。
   *
   * @param stream 逐次データ
   * @param position 位置
   */
  public record Rank(int stream, int position) {}

  /**
   * 関連文書の検索で受け渡す順位を付けたを表す。
   *
   * @param item 項目
   * @param score 評価値
   * @param ranks 順位一覧
   */
  public record Ranked<T>(T item, double score, List<Rank> ranks) {
    /** 受け取った情報からRankedを作成する。 */
    public Ranked {
      ranks = List.copyOf(ranks);
    }
  }

  /** 固定値。 */
  private final int constant;

  /** 指定された値や利用先を使って、ReciprocalRankFusionを初期化する。 */
  public ReciprocalRankFusion(int constant) {
    if (constant < 1 || constant > 1000)
      throw new IllegalArgumentException("Invalid rank constant");
    this.constant = constant;
  }

  /** 関連文書の検索で用いるfuseを返す。 */
  public <T> List<Ranked<T>> fuse(List<List<T>> streams, Function<T, String> key, int limit) {
    if (limit < 1
        || limit > 256
        || streams.size() > 8
        || streams.stream().anyMatch(s -> s.size() > 256))
      throw new IllegalArgumentException("Invalid fusion limits");
    var items = new HashMap<String, T>();
    var scores = new HashMap<String, Double>();
    var ranks = new HashMap<String, List<Rank>>();
    for (int stream = 0; stream < streams.size(); stream++) {
      var seen = new HashSet<String>();
      int position = 0;
      for (var item : streams.get(stream)) {
        String id = key.apply(item);
        if (id == null || id.isBlank())
          throw new IllegalArgumentException("Missing candidate identity");
        if (!seen.add(id)) continue;
        position++;
        items.putIfAbsent(id, item);
        scores.merge(id, 1.0 / (constant + position), Double::sum);
        ranks.computeIfAbsent(id, k -> new ArrayList<>()).add(new Rank(stream, position));
      }
    }
    double scale = (constant + 1.0) / Math.max(1, streams.size());
    return scores.keySet().stream()
        .sorted(
            Comparator.<String>comparingDouble(scores::get)
                .reversed()
                .thenComparing(Function.identity()))
        .limit(limit)
        .map(id -> new Ranked<>(items.get(id), scores.get(id) * scale, ranks.get(id)))
        .toList();
  }
}
