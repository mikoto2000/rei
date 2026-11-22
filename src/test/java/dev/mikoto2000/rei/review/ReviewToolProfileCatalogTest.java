package dev.mikoto2000.rei.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.mikoto2000.rei.core.stagnation.ReviewToolProfile;
import dev.mikoto2000.rei.storage.StorageDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

class ReviewToolProfileCatalogTest {
  @Test
  void actualReviewProtocolRemainsAvailableAndSchemasShrink() throws Exception {
    var full = ReviewToolProfileLiveTest.catalog();
    var selected = ReviewToolProfile.reviewCallbacks(full);
    var protocol =
        List.of(
            MethodToolCallbackProvider.builder()
                .toolObjects(mock(ReviewTools.class))
                .build()
                .getToolCallbacks());
    assertThat(selected.stream().map(c -> c.getToolDefinition().name()).toList())
        .containsAll(protocol.stream().map(c -> c.getToolDefinition().name()).toList())
        .contains(
            "readMultiFile",
            "searchAndRead",
            "readRawToolResult",
            "checkpointPlan",
            ReviewToolProfile.TOOL)
        .doesNotContain("runCommand", "writeTextFile");
    String fullJson =
        StorageDatabase.JSON.writeValueAsString(ReviewToolProfileLiveTest.schemas(full));
    String selectedJson =
        StorageDatabase.JSON.writeValueAsString(ReviewToolProfileLiveTest.schemas(selected));
    assertThat(selectedJson.length()).isLessThan(fullJson.length());
    Files.createDirectories(Path.of("target"));
    Files.writeString(
        Path.of("target/review-tool-profile-catalog.json"),
        StorageDatabase.JSON
            .writerWithDefaultPrettyPrinter()
            .writeValueAsString(
                Map.of(
                    "fullCount",
                    full.size(),
                    "selectedCount",
                    selected.size(),
                    "fullChars",
                    fullJson.length(),
                    "selectedChars",
                    selectedJson.length(),
                    "selectedNames",
                    selected.stream().map(c -> c.getToolDefinition().name()).toList())));
  }
}
