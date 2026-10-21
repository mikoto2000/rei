package dev.mikoto2000.rei.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 補助エージェント依存関係仕様の結果と状態の契約を検証するテスト。 */
class SubAgentDagSpecTest {
  /** {@code node} の条件を再現するための補助情報を返す。 */
  SubAgentDagSpec.Node node(String id, String... dependencies) {
    return new SubAgentDagSpec.Node(id, "reviewer", "inspect " + id, null, List.of(dependencies));
  }

  /** 補助エージェントへの委譲で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void fanOutAndFanInProduceOnlyDependencyReadyFrontiers() {
    var spec =
        new SubAgentDagSpec(
            List.of(node("a"), node("b"), node("compare", "a", "b"), node("review", "compare")),
            SubAgentDagSpec.FailurePolicy.FAIL_FAST,
            2);
    assertEquals(List.of("a", "b"), spec.ready(Set.of(), Set.of()));
    assertEquals(List.of("b"), spec.ready(Set.of("a"), Set.of()));
    assertEquals(List.of("compare"), spec.ready(Set.of("a", "b"), Set.of()));
    assertEquals(List.of("review"), spec.ready(Set.of("a", "b", "compare"), Set.of()));
    assertEquals(List.of(), spec.ready(Set.of("a", "b", "compare", "review"), Set.of()));
  }

  /** 補助エージェントへの委譲で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void cyclesUnknownDependenciesAndUnboundedInputsAreRejectedBeforeExecution() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SubAgentDagSpec(
                List.of(node("a", "b"), node("b", "a")),
                SubAgentDagSpec.FailurePolicy.FAIL_FAST,
                2));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SubAgentDagSpec(
                List.of(node("a", "missing")), SubAgentDagSpec.FailurePolicy.FAIL_FAST, 2));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SubAgentDagSpec(
                List.of(node("a"), node("a")), SubAgentDagSpec.FailurePolicy.FAIL_FAST, 2));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SubAgentDagSpec(List.of(node("a")), SubAgentDagSpec.FailurePolicy.FAIL_FAST, 3));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SubAgentDagSpec(
                java.util.stream.IntStream.range(0, 17).mapToObj(i -> node("n" + i)).toList(),
                SubAgentDagSpec.FailurePolicy.FAIL_FAST,
                2));
  }

  /** 補助エージェントへの委譲で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void failFastStopsWhilePartialPolicyContinuesOnlyIndependentBranches() {
    var nodes = List.of(node("a"), node("b"), node("compare", "a", "b"));
    assertEquals(
        List.of(),
        new SubAgentDagSpec(nodes, SubAgentDagSpec.FailurePolicy.FAIL_FAST, 2)
            .ready(Set.of(), Set.of("a")));
    var partial = new SubAgentDagSpec(nodes, SubAgentDagSpec.FailurePolicy.CONTINUE_INDEPENDENT, 2);
    assertEquals(List.of("b"), partial.ready(Set.of(), Set.of("a")));
    assertEquals(List.of(), partial.ready(Set.of("b"), Set.of("a")));
  }
}
