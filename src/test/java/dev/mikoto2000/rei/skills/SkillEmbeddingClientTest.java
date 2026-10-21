package dev.mikoto2000.rei.skills;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** スキル埋め込み表現クライアントの結果と状態の契約を検証するテスト。 */
class SkillEmbeddingClientTest {
  /** スキルの利用の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void timeoutCancelsProviderWork() throws Exception {
    var interrupted = new CountDownLatch(1);
    try (var client =
        new SkillEmbeddingClient(
            () ->
                input -> {
                  try {
                    new CountDownLatch(1).await();
                  } catch (InterruptedException error) {
                    interrupted.countDown();
                    throw new CancellationException();
                  }
                  return List.of();
                },
            Duration.ofMillis(100))) {
      assertThrows(IllegalStateException.class, () -> client.embed(List.of("metadata")));
      assertTrue(interrupted.await(2, TimeUnit.SECONDS));
    }
  }

  /** スキルの利用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void disabledSearchNeverResolvesProvider() {
    try (var client =
        new SkillEmbeddingClient(
            () -> {
              fail("Provider must stay lazy");
              return null;
            },
            Duration.ofSeconds(1))) {
      var search =
          new SemanticSkillSearch(
              new SemanticSkillProperties(false, 0, 0, 0),
              () -> client,
              () -> null,
              System::nanoTime);
      assertTrue(search.select("query", List.of(), List.of(), 5).isEmpty());
    }
  }
}
