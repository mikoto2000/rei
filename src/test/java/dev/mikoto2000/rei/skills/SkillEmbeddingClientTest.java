package dev.mikoto2000.rei.skills;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SkillEmbeddingClientTest {
  @Test void timeoutCancelsProviderWork() throws Exception {
    var interrupted = new CountDownLatch(1);
    try (var client = new SkillEmbeddingClient(() -> input -> {
      try { new CountDownLatch(1).await(); }
      catch (InterruptedException error) { interrupted.countDown(); throw new CancellationException(); }
      return List.of();
    }, Duration.ofMillis(100))) {
      assertThrows(IllegalStateException.class, () -> client.embed(List.of("metadata")));
      assertTrue(interrupted.await(2, TimeUnit.SECONDS));
    }
  }
  @Test void disabledSearchNeverResolvesProvider() {
    try (var client = new SkillEmbeddingClient(() -> { fail("Provider must stay lazy"); return null; }, Duration.ofSeconds(1))) {
      var search = new SemanticSkillSearch(new SemanticSkillProperties(false,0,0,0), () -> client, () -> null, System::nanoTime);
      assertTrue(search.select("query", List.of(), List.of(), 5).isEmpty());
    }
  }
}
