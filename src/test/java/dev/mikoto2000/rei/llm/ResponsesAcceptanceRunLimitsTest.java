package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The owned harness must set the actual defensive-copy properties, before any paid call. */
@Tag("integration")
class ResponsesAcceptanceRunLimitsTest {
  @TempDir Path root;

  private ResponsesAcceptanceRuntime runtime() throws Exception {
    return new ResponsesAcceptanceRuntime(root, List.of(http -> {}));
  }

  @Test
  void runCallBoundIsAppliedRatherThanDiscardedOnAPropertiesCopy() throws Exception {
    assertThat(runtime().properties.getOutputLimit().getMaxLlmCallsPerRun()).isEqualTo(80);
  }

  @Test
  void runTokenBoundIsAppliedBeforeAnyTransportOrBudgetReservation() throws Exception {
    var runtime = runtime();
    assertThat(runtime.properties.getOutputLimit().getMaxTotalTokensPerRun()).isEqualTo(1000000);
    assertThat(runtime.budget.snapshot().calls()).isZero();
  }
}
