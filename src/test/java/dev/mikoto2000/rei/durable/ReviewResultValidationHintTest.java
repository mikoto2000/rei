package dev.mikoto2000.rei.durable;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewResultValidationHintTest {
  @Test
  void invalidFieldGuidanceNeverEchoesPrivateSuppliedText() {
    assertThat(WorkItemStore.resultValidationHint("PRIVATE".repeat(3000), List.of(), false))
        .contains("assessment", "1..16384")
        .doesNotContain("PRIVATE");
    assertThat(WorkItemStore.resultValidationHint("valid", null, false)).contains("findings", "[]");
    var noCoordinates =
        new WorkItemStore.Finding("PRIVATE-ID", "warning", null, null, "PRIVATE-QUOTE", "reason");
    assertThat(WorkItemStore.resultValidationHint("valid", List.of(noCoordinates), false))
        .contains("findings[0].start/end", "UTF-16")
        .doesNotContain("PRIVATE");
    assertThat(
            WorkItemStore.resultValidationHint(
                "valid", List.of(noCoordinates, noCoordinates), true))
        .contains("findings[1].id", "unique")
        .doesNotContain("PRIVATE");
    assertThat(WorkItemStore.resultValidationHint("valid", List.of(noCoordinates), true))
        .doesNotContain("findings[0].start/end");
  }
}
