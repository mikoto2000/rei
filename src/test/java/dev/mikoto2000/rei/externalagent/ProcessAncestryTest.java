package dev.mikoto2000.rei.externalagent;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class ProcessAncestryTest {
  @Test
  void cyclicAndMissingParentSnapshotsNeverLoopOrForgetCurrentProcess() {
    assertThat(ExternalAgentProcessRunner.ancestorIds(Map.of(10L, 20L, 20L, 10L), 10))
        .containsExactlyInAnyOrder(10L, 20L);
    assertThat(ExternalAgentProcessRunner.ancestorIds(Map.of(10L, 20L), 10))
        .containsExactlyInAnyOrder(10L, 20L);
  }

  @Test
  void implausiblyDeepAncestryFailsClosed() {
    var graph = new HashMap<Long, Long>();
    for (long i = 1; i < 100; i++) graph.put(i, i + 1);
    assertThatThrownBy(() -> ExternalAgentProcessRunner.ancestorIds(graph, 1))
        .hasMessageContaining("ancestry limit");
  }
}
