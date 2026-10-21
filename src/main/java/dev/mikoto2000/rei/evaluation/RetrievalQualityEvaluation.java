package dev.mikoto2000.rei.evaluation;

import dev.mikoto2000.rei.core.chat.RunCancellation;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * 成果の評価に必要な関連情報の取得品質評価を扱う。
 *
 * <p>Labelled, provider-independent offline evaluation; does not install or invoke a model by
 * itself.
 */
public final class RetrievalQualityEvaluation {
  /** 成果の評価における動作モードの選択肢。 */
  public enum Mode {
    /** 語句による。 */
    LEXICAL,
    /** ベクトルによる。 */
    DENSE,
    /** 複数の検索順位を統合する方式。 */
    RRF
  }

  /**
   * 成果の評価で受け渡す事例を表す。
   *
   * @param id 識別子
   * @param query 検索条件
   * @param relevance 関連度
   * @param hardNegatives 厳しい条件一致しない事例
   * @param expectedTopK 期待する内容上位件数
   * @param topK 上位件数
   */
  public record Case(
      String id,
      String query,
      Map<String, Integer> relevance,
      List<String> hardNegatives,
      List<String> expectedTopK,
      int topK) {
    /** 受け取った情報からCaseを作成する。 */
    public Case {
      relevance = Map.copyOf(relevance);
      hardNegatives = List.copyOf(hardNegatives);
      expectedTopK = List.copyOf(expectedTopK);
      if (id == null
          || id.isBlank()
          || id.length() > 128
          || query == null
          || query.isBlank()
          || query.length() > 8192
          || relevance.isEmpty()
          || relevance.size() > 256
          || hardNegatives.size() > 256
          || topK < 1
          || topK > 256
          || expectedTopK.size() > topK
          || relevance.values().stream().anyMatch(v -> v < 1 || v > 3)
          || !Collections.disjoint(relevance.keySet(), hardNegatives)
          || new HashSet<>(hardNegatives).size() != hardNegatives.size()
          || new HashSet<>(expectedTopK).size() != expectedTopK.size())
        throw new IllegalArgumentException("Invalid bounded relevance fixture");
      for (String value :
          java.util.stream.Stream.concat(
                  java.util.stream.Stream.concat(
                      relevance.keySet().stream(), hardNegatives.stream()),
                  expectedTopK.stream())
              .toList()) identity(value);
    }
  }

  /**
   * 成果の評価で受け渡す計測値を表す。
   *
   * @param recall 検出率
   * @param precision 精度
   * @param mrr 平均逆順位
   * @param ndcg 検索順位の評価値
   * @param expectedTopKMatched 期待する内容上位件数一致した
   */
  public record Metrics(
      double recall, double precision, double mrr, double ndcg, boolean expectedTopKMatched) {}

  /**
   * 成果の評価で受け渡す実行を表す。
   *
   * @param fixtureId 検証用データ識別子
   * @param mode 動作モード
   * @param reranked 再評価した順位
   * @param ranking 順位付け
   * @param metrics 計測値
   */
  public record Run(
      String fixtureId, Mode mode, boolean reranked, List<String> ranking, Metrics metrics) {}

  /**
   * 成果の評価で受け渡す平均を表す。
   *
   * @param recall 検出率
   * @param precision 精度
   * @param mrr 平均逆順位
   * @param ndcg 検索順位の評価値
   * @param cases 事例一覧
   */
  public record Average(double recall, double precision, double mrr, double ndcg, int cases) {}

  /**
   * 成果の評価で受け渡す比較結果を表す。
   *
   * @param provider 提供元
   * @param runs 実行一覧
   * @param aggregate 集計結果
   * @param truthVerified 確定情報確認済み
   */
  public record Comparison(
      String provider, List<Run> runs, Map<String, Average> aggregate, boolean truthVerified) {}

  /** 成果の評価に必要なRetrieverを扱う。 */
  @FunctionalInterface
  public interface Retriever {
    /** 成果の評価で用いる取得を返す。 */
    List<String> retrieve(Case fixture, Mode mode, boolean rerank);
  }

  /** ナノ秒数。 */
  private final LongSupplier nanos;

  /** 上限。 */
  private final long limit;

  /** 成果の評価を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public RetrievalQualityEvaluation() {
    this(System::nanoTime, Duration.ofSeconds(30));
  }

  /** 指定された値や利用先を使って、RetrievalQualityEvaluationを初期化する。 */
  public RetrievalQualityEvaluation(LongSupplier nanos, Duration timeout) {
    if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(30)) > 0)
      throw new IllegalArgumentException("Evaluation deadline <=30s");
    this.nanos = nanos;
    limit = timeout.toNanos();
  }

  /** 成果の評価で用いる評価値を返す。 */
  public Metrics score(Case fixture, List<String> ranking) {
    RunCancellation.propagate(null);
    if (fixture == null
        || ranking == null
        || ranking.size() > 256
        || new HashSet<>(ranking).size() != ranking.size())
      throw new IllegalArgumentException("Bounded unique ranking required");
    ranking.forEach(RetrievalQualityEvaluation::identity);
    var selected = ranking.stream().limit(fixture.topK()).toList();
    int hits = 0;
    double reciprocal = 0, dcg = 0;
    for (int i = 0; i < selected.size(); i++) {
      int relevance = fixture.relevance().getOrDefault(selected.get(i), 0);
      if (relevance > 0) {
        hits++;
        if (reciprocal == 0) reciprocal = 1.0 / (i + 1);
        dcg += (Math.pow(2, relevance) - 1) / log2(i + 2);
      }
    }
    var ideal =
        fixture.relevance().values().stream()
            .sorted(Comparator.reverseOrder())
            .limit(fixture.topK())
            .toList();
    double idcg = 0;
    for (int i = 0; i < ideal.size(); i++) idcg += (Math.pow(2, ideal.get(i)) - 1) / log2(i + 2);
    return new Metrics(
        hits / (double) fixture.relevance().size(),
        hits / (double) fixture.topK(),
        reciprocal,
        idcg == 0 ? 0 : dcg / idcg,
        selected.equals(fixture.expectedTopK()));
  }

  /** 成果の評価で用いる比較を返す。 */
  public Comparison compare(List<Case> fixtures, String provider, Retriever retriever) {
    if (fixtures == null
        || fixtures.isEmpty()
        || fixtures.size() > 128
        || fixtures.stream().anyMatch(Objects::isNull)
        || fixtures.stream().map(Case::id).distinct().count() != fixtures.size()
        || provider == null
        || !provider.matches("[A-Za-z0-9._:-]{1,128}")
        || retriever == null)
      throw new IllegalArgumentException(
          "Bounded unique fixtures and explicit provider identity required");
    long started = nanos.getAsLong();
    var runs = new ArrayList<Run>();
    var aggregate = new LinkedHashMap<String, Average>();
    for (var mode : Mode.values())
      for (boolean rerank : List.of(false, true)) {
        var group = new ArrayList<Metrics>();
        for (var fixture : fixtures) {
          active(started);
          var ranking = List.copyOf(retriever.retrieve(fixture, mode, rerank));
          active(started);
          var metrics = score(fixture, ranking);
          group.add(metrics);
          runs.add(new Run(fixture.id(), mode, rerank, ranking, metrics));
        }
        aggregate.put(
            mode.name() + "_" + (rerank ? "RERANK" : "BASE"),
            new Average(
                group.stream().mapToDouble(Metrics::recall).average().orElseThrow(),
                group.stream().mapToDouble(Metrics::precision).average().orElseThrow(),
                group.stream().mapToDouble(Metrics::mrr).average().orElseThrow(),
                group.stream().mapToDouble(Metrics::ndcg).average().orElseThrow(),
                group.size()));
      }
    return new Comparison(provider, List.copyOf(runs), Map.copyOf(aggregate), false);
  }

  /** 成果の評価に関する活動中を処理する。 */
  private void active(long started) {
    RunCancellation.propagate(null);
    if (nanos.getAsLong() - started >= limit)
      throw new IllegalStateException(
          "Evaluation deadline exceeded; incomplete comparison is not success");
  }

  /** 成果の評価に関する識別情報を処理する。 */
  private static void identity(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 1024
        || value.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Bounded candidate identity required");
  }

  /** 成果の評価で用いるログ2を返す。 */
  private static double log2(double value) {
    return Math.log(value) / Math.log(2);
  }
}
