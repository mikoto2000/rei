package dev.mikoto2000.rei.bluesky;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class BlueskyReplyDeduplicationTest {
  private static final String TARGET = "at://did:plc:alice/app.bsky.feed.post/target";
  private static final String URL = "https://bsky.app/profile/alice.bsky.social/post/target";
  @TempDir Path tempDir;
  private BlueskyReplyStateRepository repository;
  private BlueskyReplyConversationRepository conversation;
  private BlueskyReplyTextGenerator generator;
  private BlueskyApiClient api;
  private BlueskyProperties properties;
  private BlueskyPostService manual;

  @BeforeEach
  void setUp() {
    repository = new BlueskyReplyStateRepository(
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
        .thenReturn(new BlueskyApiClient.PostResult(true, "at://did:plc:rei/app.bsky.feed.post/reply"));
    manual = new BlueskyPostService(properties, api, generator, conversation, repository);
  }

  @Test
  void repeatedManualReplyUsingUrlAndUriSendsOnlyOnce() {
    assertThat(manual.reply(URL, "first").success()).isTrue();
    assertThat(manual.reply(TARGET, "second").success()).isFalse();
    verify(api, times(1)).createReply(any(), any(), any(), any(), any(), any(), any());
  }

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
        .thenReturn(List.of(new BlueskyApiClient.FeedPost(TARGET, "cid", "hello",
            OffsetDateTime.now(), false, false, null, null)));
    when(generator.generate(any(), any(), any())).thenAnswer(invocation -> {
      assertThat(manual.reply(URL, "manual").success()).isTrue();
      return "automatic";
    });
    var scheduled = new BlueskyReplyService(properties, mock(BlueskyReplyPropertiesValidator.class),
        feedClient, repository, conversation, generator, api, () -> 0.0, Clock.systemUTC());

    scheduled.runOnce();

    verify(api, times(1)).createReply(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void uncertainSendIsNotRepeatedAfterRepositoryReopens() {
    when(api.createReply(any(), any(), any(), any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("response lost after send"));
    assertThat(manual.reply(URL, "first").success()).isFalse();

    var reopened = new BlueskyReplyStateRepository(
        new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("state.db")));
    var restarted = new BlueskyPostService(properties, api, generator, conversation, reopened);
    assertThat(restarted.reply(TARGET, "second").success()).isFalse();
    verify(api, times(1)).createReply(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void explicitFailureAllowsRetry() {
    when(api.createReply(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(new BlueskyApiClient.PostResult(false, null))
        .thenReturn(new BlueskyApiClient.PostResult(true, "at://reply"));
    assertThat(manual.reply(URL, "first").success()).isFalse();
    assertThat(manual.reply(URL, "second").success()).isTrue();
    verify(api, times(2)).createReply(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void historyFailureAfterSendDoesNotCauseAnotherSend() {
    doThrow(new IllegalStateException("history unavailable"))
        .when(conversation).appendUserMessage(anyString(), anyString());
    assertThat(manual.reply(URL, "first").success()).isFalse();
    assertThat(repository.isAlreadyReplied(TARGET)).isTrue();
    assertThat(manual.reply(URL, "second").success()).isFalse();
    verify(api, times(1)).createReply(any(), any(), any(), any(), any(), any(), any());
  }
}
