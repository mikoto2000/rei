package dev.mikoto2000.rei.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 関連情報の取得品質評価の結果と状態の契約を検証するテスト。 */
class RetrievalQualityEvaluationTest {
  /** {@code fixture} の条件を再現するための補助情報を返す。 */
  RetrievalQualityEvaluation.Case fixture() {
    return new RetrievalQualityEvaluation.Case(
        "graded", "query", Map.of("a", 3, "b", 1), List.of("negative"), List.of("a", "b"), 2);
  }

  /** 成果の評価で、競合する要求でも、実行順序と作業の分離を守ることを確認する。 */
  @Test
  void gradedMetricsUseCutoffFullRelevantSetAndIdealOrdering() {
    var evaluator = new RetrievalQualityEvaluation();
    var result = evaluator.score(fixture(), List.of("negative", "b", "a"));
    assertEquals(.5, result.recall());
    assertEquals(.5, result.precision());
    assertEquals(.5, result.mrr());
    assertTrue(result.ndcg() > 0 && result.ndcg() < .1);
    assertFalse(result.expectedTopKMatched());
    var perfect = evaluator.score(fixture(), List.of("a", "b"));
    assertEquals(1, perfect.recall());
    assertEquals(1, perfect.precision());
    assertEquals(1, perfect.mrr());
    assertEquals(1, perfect.ndcg());
    assertTrue(perfect.expectedTopKMatched());
  }

  /** 成果の評価で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void shortAndEmptyListsDoNotInflatePrecisionAndDuplicatesAreInvalid() {
    var evaluator = new RetrievalQualityEvaluation();
    assertEquals(.5, evaluator.score(fixture(), List.of("a")).precision());
    assertEquals(0, evaluator.score(fixture(), List.of()).ndcg());
    assertThrows(
        IllegalArgumentException.class, () -> evaluator.score(fixture(), List.of("a", "a")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RetrievalQualityEvaluation.Case(
                "bad", "q", Map.of("a", 3), List.of("a"), List.of("a"), 1));
  }

  /** 成果の評価で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void comparingModesRecordsProviderIdentityAndDoesNotClaimLiveQuality() {
    var result =
        new RetrievalQualityEvaluation()
            .compare(
                List.of(fixture()),
                "deterministic-fixture",
                (f, mode, rerank) ->
                    rerank
                        ? List.of("a", "b")
                        : mode == RetrievalQualityEvaluation.Mode.LEXICAL
                            ? List.of("negative", "b")
                            : List.of("b", "a"));
    assertEquals(6, result.runs().size());
    assertEquals("deterministic-fixture", result.provider());
    assertFalse(result.truthVerified());
    assertTrue(result.runs().stream().anyMatch(r -> !r.metrics().expectedTopKMatched()));
    assertEquals(6, result.aggregate().size());
  }

  /** 成果の評価の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationAndProviderFailureDoNotBecomeZeroScoreSuccess() {
    var evaluator = new RetrievalQualityEvaluation();
    assertThrows(
        java.util.concurrent.CancellationException.class,
        () ->
            evaluator.compare(
                List.of(fixture()),
                "fixture",
                (f, m, r) -> {
                  throw new java.util.concurrent.CancellationException();
                }));
    assertThrows(
        IllegalStateException.class,
        () ->
            evaluator.compare(
                List.of(fixture()),
                "fixture",
                (f, m, r) -> {
                  throw new IllegalStateException("unavailable");
                }));
  }

  /** 合計期限について、比較結果を停止することを確認する。 */
  @Test
  void totalDeadlineStopsComparisonWithoutPublishingPartialMetrics() {
    var nanos = new java.util.concurrent.atomic.AtomicLong();
    var evaluator = new RetrievalQualityEvaluation(nanos::get, java.time.Duration.ofMillis(1));
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    assertThrows(
        IllegalStateException.class,
        () ->
            evaluator.compare(
                List.of(fixture()),
                "fixture",
                (f, m, r) -> {
                  calls.incrementAndGet();
                  nanos.addAndGet(1000001);
                  return List.of("a", "b");
                }));
    assertEquals(1, calls.get());
  }
}
