package dev.mikoto2000.rei.bluesky;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Bluesky返信状態保存と検索の窓口の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class BlueskyReplyStateRepositoryTest {

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir java.nio.file.Path tempDir;

  /** 検証に使う保存と検索の窓口。 */
  private BlueskyReplyStateRepository repository;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setUp() {
    repository =
        new BlueskyReplyStateRepository(
            new DriverManagerDataSource(
                "jdbc:sqlite:" + tempDir.resolve("bluesky-reply-state.db")));
  }

  /** Blueskyとの連携で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void saveAndFindLastSeen() {
    OffsetDateTime now = OffsetDateTime.now();

    repository.saveLastSeen("alice.bsky.social", "at://post/1", now);

    var state = repository.findLastSeen("alice.bsky.social");
    assertTrue(state.isPresent());
    assertTrue(state.get().lastSeenPostUri().contains("at://post/1"));
  }

  /** Blueskyとの連携で、検索条件に応じて、必要な候補と根拠を選ぶことを確認する。 */
  @Test
  void markRepliedAndQuery() {
    assertFalse(repository.isAlreadyReplied("at://post/2"));

    repository.markReplied("at://post/2", "alice.bsky.social", "at://reply/2");

    assertTrue(repository.isAlreadyReplied("at://post/2"));
  }

  /** Blueskyとの連携で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void incrementTodayCount() {
    LocalDate today = LocalDate.now();
    assertTrue(repository.countToday("alice.bsky.social", today) == 0);

    repository.incrementToday("alice.bsky.social", today);
    repository.incrementToday("alice.bsky.social", today);

    assertTrue(repository.countToday("alice.bsky.social", today) == 2);
  }

  /** Blueskyとの連携で、競合する要求でも、実行順序と作業の分離を守ることを確認する。 */
  @Test
  void claimIsExclusiveAcrossRepositoryInstances() throws Exception {
    var other =
        new BlueskyReplyStateRepository(
            new DriverManagerDataSource(
                "jdbc:sqlite:" + tempDir.resolve("bluesky-reply-state.db")));
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () -> {
                start.await();
                return repository.tryClaimReply("at://post/claim");
              });
      var second =
          executor.submit(
              () -> {
                start.await();
                return other.tryClaimReply("at://post/claim");
              });
      start.countDown();
      assertTrue(
          first.get(5, java.util.concurrent.TimeUnit.SECONDS)
              ^ second.get(5, java.util.concurrent.TimeUnit.SECONDS));
    }
  }

  /** Blueskyとの連携で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void existingReplyCannotBeClaimed() {
    repository.markReplied("at://post/existing", "alice", "at://reply");
    assertFalse(repository.tryClaimReply("at://post/existing"));
  }
}
