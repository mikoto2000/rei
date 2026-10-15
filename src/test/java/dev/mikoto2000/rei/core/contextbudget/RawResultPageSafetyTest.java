package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.*;

import dev.mikoto2000.rei.core.chat.*;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class RawResultPageSafetyTest {
  @TempDir Path dir;

  @Test
  void requestedLargePageShrinksAndExposesExactContinuation() throws Exception {
    var store = new RawToolResultStore(dir);
    String raw = "教材🙂\\\"\r\n".repeat(4000);
    String ref = store.save("chat", "run", "reader", "call", raw);
    try (var scope = AgentRunScope.open(new AgentRunContext("run", "chat", dir))) {
      var page = new RawToolResultTools(store).readRawToolResult(ref, 0, 16000);
      assertThat(TokenEstimator.conservative().text(page)).isLessThanOrEqualTo(2048);
      assertThat(page).contains("nextOffset:", "hasMore: true");
    }
  }

  @Test
  void lowSurrogateCursorIsRejected() {
    var store = new RawToolResultStore(dir);
    String ref = store.save("chat", "run", "reader", "call", "a🙂b");
    try (var scope = AgentRunScope.open(new AgentRunContext("run", "chat", dir))) {
      assertThatThrownBy(() -> new RawToolResultTools(store).readRawToolResult(ref, 2, 1))
          .hasMessageContaining("INVALID_CURSOR");
    }
  }
}
