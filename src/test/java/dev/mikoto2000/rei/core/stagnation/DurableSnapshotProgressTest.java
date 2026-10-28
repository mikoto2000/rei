package dev.mikoto2000.rei.core.stagnation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DurableSnapshotProgressTest {
  @Test
  void persistedSnapshotRevisionAdvancesAcquisitionWithoutClaimingReviewedWork() {
    var evaluator = new ProgressEvaluator(Path.of("."));
    String result = "{\"id\":\"task\",\"snapshot\":\"snapshot\",\"revision\":1,\"errors\":0,\"enumeration\":\"SCANNING\"}";
    var evidence = evaluator.afterTool("scanReviewSnapshot", "{}", result, evaluator.beforeTool("scanReviewSnapshot", "{}"));
    assertThat(evidence).anyMatch(ProgressEvidence::acquisitionProgress);
    assertThat(evidence).noneMatch(e -> e.category() == ProgressEvidence.Category.WORK_PRODUCT_UPDATED);
    var restored = new ProgressEvaluator(Path.of("."));
    restored.restore(evaluator.snapshot());
    assertThat(restored.afterTool("scanReviewSnapshot", "{}", result, restored.beforeTool("scanReviewSnapshot", "{}")))
        .noneMatch(ProgressEvidence::acquisitionProgress);
  }
}
