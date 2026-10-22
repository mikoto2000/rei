package dev.mikoto2000.rei.core.stagnation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MultidimensionalProgressTest {
  @TempDir Path root;
  private final ObjectMapper json = new ObjectMapper();

  private java.util.List<ProgressEvidence> read(
      ProgressEvaluator evaluator, String path, String version, int start, int count) {
    var result = json.createObjectNode().put("path", path).put("version", version).put("startLine", start);
    result.set("content", json.valueToTree(Collections.nCopies(count, "line")));
    return evaluator.afterTool("readMultiFile", "{}", result.toString(), evaluator.beforeTool("readMultiFile", "{}"));
  }

  @Test
  void thousandLinesAreFetchedAsCoverageWithoutClaimingReviewedWork() {
    var evaluator = new ProgressEvaluator(root);
    for (int start = 1; start <= 1000; start += 100) {
      var evidence = read(evaluator, "A", "v1", start, 100);
      assertThat(evidence).hasSize(1);
      assertThat(evidence.getFirst().category().name()).isEqualTo("COVERAGE_ADVANCED");
    }
    assertThat(read(evaluator, "A", "v1", 1, 100)).isEmpty();
    assertThat(read(evaluator, "A", "v1", 951, 100).getFirst().description()).contains("newLines=50");
    assertThat(read(evaluator, "B", "v1", 1, 100)).hasSize(1);
    assertThat(read(evaluator, "A", "v2", 1, 100)).hasSize(1);
  }

  @Test
  void identityCapacityDoesNotPreventRangeTrackingAndRetainedRangesStayExact() {
    var evaluator = new ProgressEvaluator(root);
    for (int i = 0; i < 1500; i++)
      evaluator.afterTool("findFile", "{}", "[\"file" + i + "\"]", evaluator.beforeTool("findFile", "{}"));
    assertThat(evaluator.informationSnapshot().identities()).hasSize(InformationLedger.LIMIT);
    assertThat(read(evaluator, "A", "v1", 1, 100)).hasSize(1);
    assertThat(read(evaluator, "A", "v1", 50, 100).getFirst().description()).contains("newLines=49");
    assertThat(read(evaluator, "A", "v1", 1, 149)).isEmpty();
    assertThat(evaluator.informationSnapshot().files().getFirst().ranges()).containsExactly(new InformationLedger.LineRange(1, 150));
  }
}
