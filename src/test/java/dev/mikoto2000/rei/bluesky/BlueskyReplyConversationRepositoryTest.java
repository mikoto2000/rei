package dev.mikoto2000.rei.bluesky;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class BlueskyReplyConversationRepositoryTest {

  @TempDir
  Path tempDir;

  private BlueskyReplyConversationRepository repository;

  @BeforeEach
  void setUp() {
    DataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("reply-conversation-test.db"));
    repository = new BlueskyReplyConversationRepository(dataSource);
  }

  @Test
  void storesConversationPerHandle() {
    repository.appendUserMessage("alice.bsky.social", "hello");
    repository.appendAssistantMessage("alice.bsky.social", "hi");
    repository.appendUserMessage("bob.bsky.social", "yo");

    List<BlueskyReplyConversationRepository.ConversationMessage> alice = repository.findRecent("alice.bsky.social", 10);
    List<BlueskyReplyConversationRepository.ConversationMessage> bob = repository.findRecent("bob.bsky.social", 10);

    assertEquals(2, alice.size());
    assertEquals("user", alice.get(0).role());
    assertEquals("hello", alice.get(0).content());
    assertEquals("assistant", alice.get(1).role());
    assertEquals("hi", alice.get(1).content());
    assertEquals(1, bob.size());
    assertEquals("yo", bob.get(0).content());
  }
}
