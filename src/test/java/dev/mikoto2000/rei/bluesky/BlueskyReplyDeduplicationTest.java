package dev.mikoto2000.rei.bluesky;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Bluesky返信Deduplicationの結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class BlueskyReplyDeduplicationTest {
  /** 検証に使う対象。 */
  private static final String TARGET = "at://did:plc:alice/app.bsky.feed.post/target";

  /** 検証に使う参照先のURL。 */
  private static final String URL = "https://bsky.app/profile/alice.bsky.social/post/target";

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path tempDir;

  /** 検証に使う保存と検索の窓口。 */
  private BlueskyReplyStateRepository repository;

  /** 検証に使う会話。 */
  private BlueskyReplyConversationRepository conversation;

  /** 検証に使う生成。 */
  private BlueskyReplyTextGenerator generator;

  /** 検証に使うAPI。 */
  private BlueskyApiClient api;

  /** 検証に使うこの機能の動作設定。 */
  private BlueskyProperties properties;

  /** 検証に使うmanual。 */
  private BlueskyPostService manual;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setUp() {
    repository =
        new BlueskyReplyStateRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("state.db")));
    conversation = mock(BlueskyReplyConversationRepository.class);
    generator = mock(BlueskyReplyTextGenerator.class);
    api = mock(BlueskyApiClient.class);
    properties = new BlueskyProperties();
    properties.setEnabled(true);
    properties.setHandle("rei.bsky.social");
    properties.setAppPassword("test");
    when(api.authenticate(anyString(), anyString()))
        .thenReturn(new BlueskyApiClient.AuthResult(true, "jwt", "did:plc:rei"));
    when(api.resolveReplyTarget(anyString(), anyString()))
        .thenReturn(new BlueskyApiClient.ReplyTarget(TARGET, "cid", TARGET, "cid", "hello"));
    when(api.createReply(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new BlueskyApiClient.PostResult(true, "at://did:plc:rei/app.bsky.feed.post/reply"));
    manual = new BlueskyPostService(properties, api, generator, conversation, repository);
  }

  /** Blueskyとの連携で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void repeatedManualReplyUsingUrlAndUriSendsOnlyOnce() {
    assertThat(manual.reply(URL, "first").success()).isTrue();
    assertThat(manual.reply(TARGET, "second").success()).isFalse();
    verify(api, times(1)).createReply(any(), any(), any(), any(), any(), any(), any());
  }

  /** Blueskyとの連携で、状態の変化と結果を、通知・表示の契約に従って伝えることを確認する。 */
  @Test
  void manualReplyDuringScheduledGenerationPreventsSecondSend() {
    var reply = properties.getReply();
    reply.setEnabled(true);
    reply.setDryRun(false);
    var user = new BlueskyProperties.ReplyUser();
    user.setHandle("alice.bsky.social");
    user.setProbability(1.0);
    reply.setUsers(List.of(user));
    var feedClient = mock(BlueskyAuthorFeedClient.class);
    when(feedClient.resolveDid(user.getHandle())).thenReturn("did:plc:alice");
    when(api.getAuthorFeed("did:plc:alice", reply.getFetchLimit(), "jwt"))
        .thenReturn(
            List.of(
                new BlueskyApiClient.FeedPost(
                    TARGET, "cid", "hello", OffsetDateTime.now(), false, false, null, null)));
    when(generator.generate(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              assertThat(manual.reply(URL, "manual").success()).isTrue();
              return "automatic";
            });
    var scheduled =
        new BlueskyReplyService(
            properties,
            mock(BlueskyReplyPropertiesValidator.class),
            feedClient,
            repository,
            conversation,
            generator,
            api,
            () -> 0.0,
            Clock.systemUTC());

    scheduled.runOnce();

    verify(api, times(1)).createReply(any(), any(), any(), any(), any(), any(), any());
  }

  /** 不確実なについて、なし繰り返されたを送信することを確認する。 */
  @Test
  void uncertainSendIsNotRepeatedAfterRepositoryReopens() {
    when(api.createReply(any(), any(), any(), any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("response lost after send"));
    assertThat(manual.reply(URL, "first").success()).isFalse();

    var reopened =
        new BlueskyReplyStateRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("state.db")));
    var restarted = new BlueskyPostService(properties, api, generator, conversation, reopened);
    assertThat(restarted.reply(TARGET, "second").success()).isFalse();
    verify(api, times(1)).createReply(any(), any(), any(), any(), any(), any(), any());
  }

  /** 明示的な失敗情報について、再試行を許可することを確認する。 */
  @Test
  void explicitFailureAllowsRetry() {
    when(api.createReply(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(new BlueskyApiClient.PostResult(false, null))
        .thenReturn(new BlueskyApiClient.PostResult(true, "at://reply"));
    assertThat(manual.reply(URL, "first").success()).isFalse();
    assertThat(manual.reply(URL, "second").success()).isTrue();
    verify(api, times(2)).createReply(any(), any(), any(), any(), any(), any(), any());
  }

  /** 履歴失敗情報変更後について、なし原因別の送信を送信することを確認する。 */
  @Test
  void historyFailureAfterSendDoesNotCauseAnotherSend() {
    doThrow(new IllegalStateException("history unavailable"))
        .when(conversation)
        .appendUserMessage(anyString(), anyString());
    assertThat(manual.reply(URL, "first").success()).isFalse();
    assertThat(repository.isAlreadyReplied(TARGET)).isTrue();
    assertThat(manual.reply(URL, "second").success()).isFalse();
    verify(api, times(1)).createReply(any(), any(), any(), any(), any(), any(), any());
  }
}
