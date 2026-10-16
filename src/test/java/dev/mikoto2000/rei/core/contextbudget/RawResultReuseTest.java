package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RawResultReuseTest {
  @TempDir Path dir;

  @Test
  void identicalBodiesAreStoredOnceButCallIdentityIsPreserved() {
    var store = new RawToolResultStore(dir);
    String first = store.save("chat", "run", "reader", "one", "本文🙂".repeat(200));
    String second = store.save("chat", "run", "reader", "two", "本文🙂".repeat(200));
    assertThat(first).isNotEqualTo(second);
    assertThat(store.bodyWriteCount()).isEqualTo(1);
    assertThat(store.read("chat", first).toolCallId()).isEqualTo("one");
    assertThat(store.read("chat", second).toolCallId()).isEqualTo("two");
  }

  @Test
  void repeatedPagesReuseImmutableBodyOnlyWithinTheCapturedRun() {
    var store = new RawToolResultStore(dir);
    String ref = store.save("chat", "run", "reader", "one", "本文🙂".repeat(200));
    Object first;
    try (var scope = AgentRunScope.open(new AgentRunContext("run", "chat", dir))) {
      first = store.read("chat", ref);
      assertThat(store.read("chat", ref)).isSameAs(first);
      assertThat(store.bodyReadCount()).isEqualTo(1);
    }
    try (var scope = AgentRunScope.open(new AgentRunContext("another", "chat", dir))) {
      assertThat(store.read("chat", ref)).isNotSameAs(first);
      assertThat(store.bodyReadCount()).isEqualTo(2);
    }
  }

  @Test
  void capacityRejectsBeforeCreatingAnOversizedBody() {
    var store = new RawToolResultStore(dir, null, 100, 2048, 2048);
    assertThatThrownBy(() -> store.save("chat", "run", "reader", "one", "x".repeat(1000)))
        .hasMessageContaining("RAW_RESULT_CAPACITY_EXCEEDED");
    assertThat(store.bodyWriteCount()).isZero();
  }
}
