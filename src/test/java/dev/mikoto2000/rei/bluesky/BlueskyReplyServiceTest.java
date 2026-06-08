package dev.mikoto2000.rei.bluesky;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlueskyReplyServiceTest {

  @Mock
  private BlueskyReplyPropertiesValidator validator;
  @Mock
  private BlueskyAuthorFeedClient authorFeedClient;
  @Mock
  private BlueskyReplyStateRepository repository;
  @Mock
  private BlueskyReplyConversationRepository conversationRepository;
  @Mock
  private BlueskyReplyTextGenerator replyTextGenerator;
  @Mock
  private BlueskyApiClient blueskyApiClient;

  @Test
  void doesNothingWhenReplyDisabled() {
    BlueskyProperties properties = baseProperties(false, false, 1.0d, 0);
    BlueskyReplyService service = new BlueskyReplyService(
        properties, validator, authorFeedClient, repository, conversationRepository, replyTextGenerator, blueskyApiClient, () -> 0.0d,
        fixedClock());

    service.runOnce();

    verify(authorFeedClient, never()).resolveDid(eq("alice.bsky.social"));
  }

  @Test
  void skipsByExcludeRulesAndProbability() {
    BlueskyProperties properties = baseProperties(true, false, 0.5d, 0);
    BlueskyReplyService service = new BlueskyReplyService(
        properties, validator, authorFeedClient, repository, conversationRepository, replyTextGenerator, blueskyApiClient, () -> 0.9d,
        fixedClock());
    OffsetDateTime now = OffsetDateTime.ofInstant(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
    when(authorFeedClient.resolveDid("alice.bsky.social")).thenReturn("did:plc:alice");
    when(blueskyApiClient.authenticate("rei.bsky.social", "app-pass"))
        .thenReturn(new BlueskyApiClient.AuthResult(true, "jwt", "did:plc:rei"));
    when(blueskyApiClient.getAuthorFeed("did:plc:alice", 30, "jwt")).thenReturn(List.of(
        new BlueskyApiClient.FeedPost("at://u/a", "cid-a", "t", now.minusMinutes(1), true, false, null, null),
        new BlueskyApiClient.FeedPost("at://u/b", "cid-b", "t", now.minusMinutes(1), false, true, null, null),
        new BlueskyApiClient.FeedPost("at://u/c", "cid-c", "t", now.minusMinutes(1), false, false, null, null)));
    when(repository.findLastSeen("alice.bsky.social")).thenReturn(Optional.empty());
    when(repository.isAlreadyReplied(anyString())).thenReturn(false);

    service.runOnce();

    verify(blueskyApiClient).authenticate(eq("rei.bsky.social"), eq("app-pass"));
    verify(repository, never()).markReplied(eq("at://u/c"), eq("alice.bsky.social"), eq("at://reply"));
  }

  @Test
  void runsDryRunWithoutPosting() {
    BlueskyProperties properties = baseProperties(true, true, 1.0d, 0);
    BlueskyReplyService service = new BlueskyReplyService(
        properties, validator, authorFeedClient, repository, conversationRepository, replyTextGenerator, blueskyApiClient, () -> 0.0d,
        fixedClock());
    OffsetDateTime now = OffsetDateTime.ofInstant(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
    when(authorFeedClient.resolveDid("alice.bsky.social")).thenReturn("did:plc:alice");
    when(blueskyApiClient.authenticate("rei.bsky.social", "app-pass"))
        .thenReturn(new BlueskyApiClient.AuthResult(true, "jwt", "did:plc:rei"));
    when(blueskyApiClient.getAuthorFeed("did:plc:alice", 30, "jwt")).thenReturn(List.of(
        new BlueskyApiClient.FeedPost("at://u/a", "cid-a", "text", now.minusMinutes(1), false, false, null, null)));
    when(repository.findLastSeen("alice.bsky.social")).thenReturn(Optional.empty());
    when(repository.isAlreadyReplied("at://u/a")).thenReturn(false);

    service.runOnce();

    verify(blueskyApiClient).authenticate(eq("rei.bsky.social"), eq("app-pass"));
    verify(repository, never()).markReplied(eq("at://u/a"), eq("alice.bsky.social"), eq("at://reply"));
  }

  @Test
  void postsReplyAndUpdatesState() {
    BlueskyProperties properties = baseProperties(true, false, 1.0d, 2);
    BlueskyReplyService service = new BlueskyReplyService(
        properties, validator, authorFeedClient, repository, conversationRepository, replyTextGenerator, blueskyApiClient, () -> 0.0d,
        fixedClock());
    OffsetDateTime now = OffsetDateTime.ofInstant(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
    BlueskyApiClient.FeedPost post = new BlueskyApiClient.FeedPost(
        "at://u/a", "cid-a", "text", now.minusMinutes(1), false, false, "at://u/root", "cid-root");
    when(authorFeedClient.resolveDid("alice.bsky.social")).thenReturn("did:plc:alice");
    when(blueskyApiClient.getAuthorFeed("did:plc:alice", 30, "jwt")).thenReturn(List.of(post));
    when(repository.findLastSeen("alice.bsky.social")).thenReturn(Optional.empty());
    when(repository.isAlreadyReplied("at://u/a")).thenReturn(false);
    when(repository.countToday("alice.bsky.social", LocalDate.of(2026, 6, 1))).thenReturn(0);
    when(conversationRepository.findRecent("alice.bsky.social", 10)).thenReturn(List.of());
    when(replyTextGenerator.generate(eq("alice.bsky.social"), eq("text"), any())).thenReturn("生成返信");
    when(blueskyApiClient.authenticate("rei.bsky.social", "app-pass"))
        .thenReturn(new BlueskyApiClient.AuthResult(true, "jwt", "did:plc:rei"));
    when(blueskyApiClient.createReply(eq("jwt"), eq("did:plc:rei"), eq("生成返信"),
        eq("at://u/a"), eq("cid-a"), eq("at://u/root"), eq("cid-root")))
        .thenReturn(new BlueskyApiClient.PostResult(true, "at://rei/reply"));

    service.runOnce();

    verify(repository).markReplied("at://u/a", "alice.bsky.social", "at://rei/reply");
    verify(repository).incrementToday("alice.bsky.social", LocalDate.of(2026, 6, 1));
    verify(repository).saveLastSeen("alice.bsky.social", "at://u/a", post.indexedAt());
    verify(conversationRepository).appendUserMessage("alice.bsky.social", "text");
    verify(conversationRepository).appendAssistantMessage("alice.bsky.social", "生成返信");
  }

  @Test
  void skipsWhenDailyLimitReached() {
    BlueskyProperties properties = baseProperties(true, false, 1.0d, 1);
    BlueskyReplyService service = new BlueskyReplyService(
        properties, validator, authorFeedClient, repository, conversationRepository, replyTextGenerator, blueskyApiClient, () -> 0.0d,
        fixedClock());
    OffsetDateTime now = OffsetDateTime.ofInstant(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
    when(authorFeedClient.resolveDid("alice.bsky.social")).thenReturn("did:plc:alice");
    when(blueskyApiClient.authenticate("rei.bsky.social", "app-pass"))
        .thenReturn(new BlueskyApiClient.AuthResult(true, "jwt", "did:plc:rei"));
    when(blueskyApiClient.getAuthorFeed("did:plc:alice", 30, "jwt")).thenReturn(List.of(
        new BlueskyApiClient.FeedPost("at://u/a", "cid-a", "text", now.minusMinutes(1), false, false, null, null)));
    when(repository.findLastSeen("alice.bsky.social")).thenReturn(Optional.empty());
    when(repository.isAlreadyReplied("at://u/a")).thenReturn(false);
    when(repository.countToday("alice.bsky.social", LocalDate.of(2026, 6, 1))).thenReturn(1);

    service.runOnce();

    verify(blueskyApiClient).authenticate(eq("rei.bsky.social"), eq("app-pass"));
    verify(repository, never()).incrementToday(eq("alice.bsky.social"), any(LocalDate.class));
  }

  @Test
  void skipsReplyPostFromConfiguredUser() {
    BlueskyProperties properties = baseProperties(true, false, 1.0d, 0);
    properties.getReply().setExcludeReplies(true);
    BlueskyReplyService service = new BlueskyReplyService(
        properties, validator, authorFeedClient, repository, conversationRepository, replyTextGenerator, blueskyApiClient, () -> 0.0d,
        fixedClock());
    OffsetDateTime now = OffsetDateTime.ofInstant(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
    when(authorFeedClient.resolveDid("alice.bsky.social")).thenReturn("did:plc:alice");
    when(blueskyApiClient.authenticate("rei.bsky.social", "app-pass"))
        .thenReturn(new BlueskyApiClient.AuthResult(true, "jwt", "did:plc:rei"));
    when(blueskyApiClient.getAuthorFeed("did:plc:alice", 30, "jwt")).thenReturn(List.of(
        new BlueskyApiClient.FeedPost("at://u/r1", "cid-r1", "reply text", now.minusMinutes(1), false, true, "at://u/root", "cid-root")));
    when(repository.findLastSeen("alice.bsky.social")).thenReturn(Optional.empty());

    service.runOnce();

    verify(blueskyApiClient, never()).createReply(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void alwaysRepliesToMentionToReiFromConfiguredUser() {
    BlueskyProperties properties = baseProperties(true, false, 0.0d, 1);
    properties.setHandle("rei.bsky.social");
    BlueskyReplyService service = new BlueskyReplyService(
        properties, validator, authorFeedClient, repository, conversationRepository, replyTextGenerator, blueskyApiClient, () -> 0.999d,
        fixedClock());
    OffsetDateTime now = OffsetDateTime.ofInstant(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
    when(authorFeedClient.resolveDid("alice.bsky.social")).thenReturn("did:plc:alice");
    when(blueskyApiClient.authenticate("rei.bsky.social", "app-pass"))
        .thenReturn(new BlueskyApiClient.AuthResult(true, "jwt", "did:plc:rei"));
    when(blueskyApiClient.getAuthorFeed("did:plc:alice", 30, "jwt")).thenReturn(List.of(
        new BlueskyApiClient.FeedPost("at://u/m1", "cid-m1", "@rei こんにちは", now.minusMinutes(1), false, false, "at://u/root", "cid-root")));
    when(repository.findLastSeen("alice.bsky.social")).thenReturn(Optional.empty());
    when(repository.isAlreadyReplied("at://u/m1")).thenReturn(false);
    when(conversationRepository.findRecent("alice.bsky.social", 10)).thenReturn(List.of());
    when(replyTextGenerator.generate(eq("alice.bsky.social"), eq("@rei こんにちは"), any())).thenReturn("返信します");
    when(blueskyApiClient.createReply(eq("jwt"), eq("did:plc:rei"), eq("返信します"),
        eq("at://u/m1"), eq("cid-m1"), eq("at://u/root"), eq("cid-root")))
        .thenReturn(new BlueskyApiClient.PostResult(true, "at://did:plc:rei/app.bsky.feed.post/m1"));

    service.runOnce();

    verify(blueskyApiClient).createReply(eq("jwt"), eq("did:plc:rei"), eq("返信します"),
        eq("at://u/m1"), eq("cid-m1"), eq("at://u/root"), eq("cid-root"));
  }

  @Test
  void skipsReplyPostEvenWhenItMentionsRei() {
    BlueskyProperties properties = baseProperties(true, false, 0.0d, 1);
    properties.setHandle("rei.bsky.social");
    BlueskyReplyService service = new BlueskyReplyService(
        properties, validator, authorFeedClient, repository, conversationRepository, replyTextGenerator, blueskyApiClient, () -> 0.0d,
        fixedClock());
    OffsetDateTime now = OffsetDateTime.ofInstant(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
    when(authorFeedClient.resolveDid("alice.bsky.social")).thenReturn("did:plc:alice");
    when(blueskyApiClient.authenticate("rei.bsky.social", "app-pass"))
        .thenReturn(new BlueskyApiClient.AuthResult(true, "jwt", "did:plc:rei"));
    when(blueskyApiClient.getAuthorFeed("did:plc:alice", 30, "jwt")).thenReturn(List.of(
        new BlueskyApiClient.FeedPost("at://u/m2", "cid-m2", "@rei こんにちは", now.minusMinutes(1), false, true, "at://u/root", "cid-root")));
    when(repository.findLastSeen("alice.bsky.social")).thenReturn(Optional.empty());

    service.runOnce();

    verify(blueskyApiClient, never()).createReply(any(), any(), any(), any(), any(), any(), any());
  }

  private BlueskyProperties baseProperties(boolean replyEnabled, boolean dryRun, double probability, int maxRepliesPerDay) {
    BlueskyProperties properties = new BlueskyProperties();
    properties.setEnabled(true);
    properties.setHandle("rei.bsky.social");
    properties.setAppPassword("app-pass");
    BlueskyProperties.BlueskyReplyProperties reply = new BlueskyProperties.BlueskyReplyProperties();
    reply.setEnabled(replyEnabled);
    reply.setDryRun(dryRun);
    reply.setExcludeReplies(true);
    reply.setExcludeReposts(true);
    reply.setFetchLimit(30);
    reply.setMaxPostAgeMinutes(120);
    BlueskyProperties.ReplyUser user = new BlueskyProperties.ReplyUser();
    user.setHandle("alice.bsky.social");
    user.setProbability(probability);
    user.setMaxRepliesPerDay(maxRepliesPerDay);
    reply.setUsers(List.of(user));
    properties.setReply(reply);
    return properties;
  }

  private Clock fixedClock() {
    return Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
  }
}
