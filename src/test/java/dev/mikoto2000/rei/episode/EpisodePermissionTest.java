package dev.mikoto2000.rei.episode;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;
class EpisodePermissionTest {
  @Test void retrievalToolsAreIntrinsicallyReadOnly() {
    for(String tool:java.util.List.of("searchMemoryHistory","episodeGet","episodeSources","episodeProcessingStatus"))
      assertTrue(ToolPermissionPolicy.intrinsicallyReadOnly(tool),tool);
  }
}
