package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 会話識別子一覧の結果と状態の契約を検証するテスト。 */
class ConversationIdsTest {

  /** チャットについて、チャット先行部分を利用することを確認する。 */
  @Test
  void chatUsesChatPrefix() {
    assertThat(ConversationIds.chat()).isEqualTo("chat:main");
  }

  /** チャット付き識別子について、チャット先行部分を利用することを確認する。 */
  @Test
  void chatWithIdUsesChatPrefix() {
    assertThat(ConversationIds.chat("550e8400-e29b-41d4-a716-446655440000"))
        .isEqualTo("chat:550e8400-e29b-41d4-a716-446655440000");
  }

  /** Bluesky返信について、操作対象を利用することを確認する。 */
  @Test
  void blueskyReplyUsesHandle() {
    assertThat(ConversationIds.blueskyReply("alice.bsky.social"))
        .isEqualTo("bluesky-reply:alice.bsky.social");
  }

  /** 用途に応じたモデル利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void blueskyReplyStripsWhitespace() {
    assertThat(ConversationIds.blueskyReply("  alice.bsky.social  "))
        .isEqualTo("bluesky-reply:alice.bsky.social");
  }

  /** 識別子を利用することを確認する。 */
  @Test
  void blueskyManualUsesIdentifier() {
    assertThat(ConversationIds.blueskyManual("at://did:plc:xxx/app.bsky.feed.post/abc"))
        .isEqualTo("bluesky-manual:at://did:plc:xxx/app.bsky.feed.post/abc");
  }

  /** ツールについて、ツール名を利用することを確認する。 */
  @Test
  void toolUsesToolName() {
    assertThat(ConversationIds.tool("web-search")).isEqualTo("tool:web-search");
  }

  /** 空白の値一覧を受け付けないことを確認する。 */
  @Test
  void rejectsBlankValues() {
    assertThatThrownBy(() -> ConversationIds.blueskyReply("  "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ConversationIds.blueskyManual(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ConversationIds.tool("")).isInstanceOf(IllegalArgumentException.class);
  }

  /** 用途に応じたモデル利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void differentBlueskyUsersHaveDifferentIds() {
    assertThat(ConversationIds.blueskyReply("alice.bsky.social"))
        .isNotEqualTo(ConversationIds.blueskyReply("bob.bsky.social"));
  }

  /** 用途に応じたモデル利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void sameBlueskyUserHasSameId() {
    assertThat(ConversationIds.blueskyReply("alice.bsky.social"))
        .isEqualTo(ConversationIds.blueskyReply("alice.bsky.social"));
  }

  /** 用途に応じたモデル利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void prefixesAreDistinct() {
    assertThat(ConversationIds.chat()).startsWith("chat:");
    assertThat(ConversationIds.blueskyReply("alice.bsky.social")).startsWith("bluesky-reply:");
    assertThat(ConversationIds.blueskyManual("abc")).startsWith("bluesky-manual:");
    assertThat(ConversationIds.tool("search")).startsWith("tool:");
  }
}
