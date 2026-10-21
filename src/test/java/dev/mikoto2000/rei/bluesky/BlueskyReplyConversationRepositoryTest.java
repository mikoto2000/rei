package dev.mikoto2000.rei.bluesky;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Bluesky返信会話保存と検索の窓口の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class BlueskyReplyConversationRepositoryTest {

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path tempDir;

  /** 検証に使う保存と検索の窓口。 */
  private BlueskyReplyConversationRepository repository;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setUp() {
    DataSource dataSource =
        new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("reply-conversation-test.db"));
    repository = new BlueskyReplyConversationRepository(dataSource);
  }

  /** 会話ごとの操作対象を保存することを確認する。 */
  @Test
  void storesConversationPerHandle() {
    repository.appendUserMessage("alice.bsky.social", "hello");
    repository.appendAssistantMessage("alice.bsky.social", "hi");
    repository.appendUserMessage("bob.bsky.social", "yo");

    List<BlueskyReplyConversationRepository.ConversationMessage> alice =
        repository.findRecent("alice.bsky.social", 10);
    List<BlueskyReplyConversationRepository.ConversationMessage> bob =
        repository.findRecent("bob.bsky.social", 10);

    assertEquals(2, alice.size());
    assertEquals("user", alice.get(0).role());
    assertEquals("hello", alice.get(0).content());
    assertEquals("assistant", alice.get(1).role());
    assertEquals("hi", alice.get(1).content());
    assertEquals(1, bob.size());
    assertEquals("yo", bob.get(0).content());
  }

  /** Blueskyとの連携で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void listsDistinctHandles() {
    repository.appendUserMessage("alice.bsky.social", "hello");
    repository.appendAssistantMessage("alice.bsky.social", "hi");
    repository.appendUserMessage("bob.bsky.social", "yo");

    List<String> handles = repository.listHandles();

    assertEquals(2, handles.size());
    assertTrue(handles.contains("alice.bsky.social"));
    assertTrue(handles.contains("bob.bsky.social"));
  }
}
