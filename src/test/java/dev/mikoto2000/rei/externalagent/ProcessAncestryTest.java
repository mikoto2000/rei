package dev.mikoto2000.rei.externalagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** プロセスAncestryの結果と状態の契約を検証するテスト。 */
class ProcessAncestryTest {
  /** 外部エージェントへの委譲で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void cyclicAndMissingParentSnapshotsNeverLoopOrForgetCurrentProcess() {
    assertThat(ExternalAgentProcessRunner.ancestorIds(Map.of(10L, 20L, 20L, 10L), 10))
        .containsExactlyInAnyOrder(10L, 20L);
    assertThat(ExternalAgentProcessRunner.ancestorIds(Map.of(10L, 20L), 10))
        .containsExactlyInAnyOrder(10L, 20L);
  }

  /** 外部エージェントへの委譲で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void implausiblyDeepAncestryFailsClosed() {
    var graph = new HashMap<Long, Long>();
    for (long i = 1; i < 100; i++) graph.put(i, i + 1);
    assertThatThrownBy(() -> ExternalAgentProcessRunner.ancestorIds(graph, 1))
        .hasMessageContaining("ancestry limit");
  }
}
