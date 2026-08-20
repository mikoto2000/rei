package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ConversationIdsTest {

  @Test
  void chatUsesChatPrefix() {
    assertThat(ConversationIds.chat()).isEqualTo("chat:main");
  }

  @Test
  void chatWithIdUsesChatPrefix() {
    assertThat(ConversationIds.chat("550e8400-e29b-41d4-a716-446655440000"))
        .isEqualTo("chat:550e8400-e29b-41d4-a716-446655440000");
  }

  @Test
  void blueskyReplyUsesHandle() {
    assertThat(ConversationIds.blueskyReply("alice.bsky.social"))
        .isEqualTo("bluesky-reply:alice.bsky.social");
  }

  @Test
  void blueskyReplyStripsWhitespace() {
    assertThat(ConversationIds.blueskyReply("  alice.bsky.social  "))
        .isEqualTo("bluesky-reply:alice.bsky.social");
  }

  @Test
  void blueskyManualUsesIdentifier() {
    assertThat(ConversationIds.blueskyManual("at://did:plc:xxx/app.bsky.feed.post/abc"))
        .isEqualTo("bluesky-manual:at://did:plc:xxx/app.bsky.feed.post/abc");
  }

  @Test
  void toolUsesToolName() {
    assertThat(ConversationIds.tool("web-search")).isEqualTo("tool:web-search");
  }

  @Test
  void rejectsBlankValues() {
    assertThatThrownBy(() -> ConversationIds.blueskyReply("  "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ConversationIds.blueskyManual(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ConversationIds.tool(""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void differentBlueskyUsersHaveDifferentIds() {
    assertThat(ConversationIds.blueskyReply("alice.bsky.social"))
        .isNotEqualTo(ConversationIds.blueskyReply("bob.bsky.social"));
  }

  @Test
  void sameBlueskyUserHasSameId() {
    assertThat(ConversationIds.blueskyReply("alice.bsky.social"))
        .isEqualTo(ConversationIds.blueskyReply("alice.bsky.social"));
  }

  @Test
  void prefixesAreDistinct() {
    assertThat(ConversationIds.chat()).startsWith("chat:");
    assertThat(ConversationIds.blueskyReply("alice.bsky.social")).startsWith("bluesky-reply:");
    assertThat(ConversationIds.blueskyManual("abc")).startsWith("bluesky-manual:");
    assertThat(ConversationIds.tool("search")).startsWith("tool:");
  }
}
