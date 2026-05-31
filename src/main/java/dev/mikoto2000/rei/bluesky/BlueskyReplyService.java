package dev.mikoto2000.rei.bluesky;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.DoubleSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class BlueskyReplyService {

  private static final Logger log = LoggerFactory.getLogger(BlueskyReplyService.class);

  private final BlueskyProperties properties;
  private final BlueskyReplyPropertiesValidator validator;
  private final BlueskyAuthorFeedClient authorFeedClient;
  private final BlueskyReplyStateRepository repository;
  private final BlueskyReplyConversationRepository conversationRepository;
  private final BlueskyReplyTextGenerator replyTextGenerator;
  private final BlueskyApiClient blueskyApiClient;
  private final DoubleSupplier randomSupplier;
  private final Clock clock;

  @Autowired
  public BlueskyReplyService(
      BlueskyProperties properties,
      BlueskyReplyPropertiesValidator validator,
      BlueskyAuthorFeedClient authorFeedClient,
      BlueskyReplyStateRepository repository,
      BlueskyReplyConversationRepository conversationRepository,
      BlueskyReplyTextGenerator replyTextGenerator,
      BlueskyApiClient blueskyApiClient) {
    this(properties, validator, authorFeedClient, repository, conversationRepository, replyTextGenerator, blueskyApiClient, Math::random,
        Clock.systemUTC());
  }

  BlueskyReplyService(
      BlueskyProperties properties,
      BlueskyReplyPropertiesValidator validator,
      BlueskyAuthorFeedClient authorFeedClient,
      BlueskyReplyStateRepository repository,
      BlueskyReplyConversationRepository conversationRepository,
      BlueskyReplyTextGenerator replyTextGenerator,
      BlueskyApiClient blueskyApiClient,
      DoubleSupplier randomSupplier,
      Clock clock) {
    this.properties = properties;
    this.validator = validator;
    this.authorFeedClient = authorFeedClient;
    this.repository = repository;
    this.conversationRepository = conversationRepository;
    this.replyTextGenerator = replyTextGenerator;
    this.blueskyApiClient = blueskyApiClient;
    this.randomSupplier = randomSupplier;
    this.clock = clock;
  }

  public void runOnce() {
    BlueskyProperties.BlueskyReplyProperties reply = properties.getReply();
    if (reply == null || !reply.isEnabled()) {
      return;
    }
    validator.validate(reply);
    for (BlueskyProperties.ReplyUser user : reply.getUsers()) {
      processUser(user, reply);
    }
  }

  private void processUser(BlueskyProperties.ReplyUser user, BlueskyProperties.BlueskyReplyProperties reply) {
    try {
      String handle = user.getHandle();
      String did = authorFeedClient.resolveDid(handle);
      if (did == null || did.isBlank()) {
        log.warn("Bluesky reply skipped: failed to resolve handle={}", handle);
        return;
      }
      BlueskyApiClient.AuthResult botAuth = authenticateBot();
      if (!botAuth.success()) {
        log.warn("Bluesky reply skipped: authentication failed for handle={}", handle);
        return;
      }
      List<BlueskyApiClient.FeedPost> feed = blueskyApiClient.getAuthorFeed(did, reply.getFetchLimit(), botAuth.accessJwt());
      Optional<BlueskyApiClient.FeedPost> newest = feed.stream()
          .filter(p -> p.indexedAt() != null)
          .max(Comparator.comparing(BlueskyApiClient.FeedPost::indexedAt));
      List<BlueskyApiClient.FeedPost> candidates = feed.stream()
          .filter(post -> isNewPost(handle, post))
          .sorted(Comparator.comparing(BlueskyApiClient.FeedPost::indexedAt))
          .toList();

      int replied = 0;
      int skipped = 0;
      for (BlueskyApiClient.FeedPost post : candidates) {
        boolean forceReply = post.reply();
        String skipReason = skipReason(post, user, reply, forceReply);
        if (skipReason != null) {
          skipped++;
          log.debug("Bluesky reply skipped: handle={}, postUri={}, reason={}", handle, post.uri(), skipReason);
          continue;
        }
        if (reply.isDryRun()) {
          replied++;
          log.info("Bluesky reply dry-run: handle={}, postUri={}", handle, post.uri());
          continue;
        }
        conversationRepository.appendUserMessage(handle, post.text() == null ? "" : post.text());
        List<BlueskyReplyConversationRepository.ConversationMessage> history = conversationRepository.findRecent(handle, 10);
        String text = replyTextGenerator.generate(handle, post.text(), history);
        BlueskyApiClient.PostResult result = blueskyApiClient.createReply(
            botAuth.accessJwt(),
            botAuth.did(),
            text,
            post.uri(),
            post.cid(),
            post.rootUri() == null ? post.uri() : post.rootUri(),
            post.rootCid() == null ? post.cid() : post.rootCid());
        if (result.success()) {
          repository.markReplied(post.uri(), handle, result.postUri());
          repository.incrementToday(handle, LocalDate.now(clock));
          conversationRepository.appendAssistantMessage(handle, text);
          replied++;
        } else {
          skipped++;
        }
      }

      newest.ifPresent(post -> repository.saveLastSeen(handle, post.uri(), post.indexedAt()));
      log.info("Bluesky reply summary: handle={}, fetched={}, candidates={}, replied={}, skipped={}",
          handle, feed.size(), candidates.size(), replied, skipped);
    } catch (Exception e) {
      log.warn("Bluesky reply failed for handle={}: {}", user.getHandle(), e.getMessage(), e);
    }
  }

  private BlueskyApiClient.AuthResult authenticateBot() {
    if (properties.getHandle() == null || properties.getHandle().isBlank()
        || properties.getAppPassword() == null || properties.getAppPassword().isBlank()) {
      return new BlueskyApiClient.AuthResult(false, null, null);
    }
    try {
      return blueskyApiClient.authenticate(properties.getHandle(), properties.getAppPassword());
    } catch (Exception e) {
      log.warn("Bluesky reply bot authentication failed before processing: {}", e.getMessage());
      return new BlueskyApiClient.AuthResult(false, null, null);
    }
  }

  private boolean isNewPost(String handle, BlueskyApiClient.FeedPost post) {
    if (post == null || post.uri() == null || post.indexedAt() == null) {
      return false;
    }
    Optional<BlueskyReplyStateRepository.UserState> state = repository.findLastSeen(handle);
    if (state.isEmpty()) {
      return true;
    }
    BlueskyReplyStateRepository.UserState last = state.get();
    if (last.lastSeenIndexedAt() == null) {
      return true;
    }
    if (post.indexedAt().isAfter(last.lastSeenIndexedAt())) {
      return true;
    }
    return post.indexedAt().isEqual(last.lastSeenIndexedAt()) && !post.uri().equals(last.lastSeenPostUri());
  }

  private String skipReason(
      BlueskyApiClient.FeedPost post,
      BlueskyProperties.ReplyUser user,
      BlueskyProperties.BlueskyReplyProperties reply,
      boolean forceReply) {
    if (!forceReply && reply.isExcludeReplies() && post.reply()) {
      return "reply";
    }
    if (reply.isExcludeReposts() && post.repost()) {
      return "repost";
    }
    OffsetDateTime cutoff = OffsetDateTime.now(clock).minusMinutes(reply.getMaxPostAgeMinutes());
    if (!forceReply && post.indexedAt().isBefore(cutoff)) {
      return "old";
    }
    if (repository.isAlreadyReplied(post.uri())) {
      return "already_replied";
    }
    if (!forceReply) {
      double random = randomSupplier.getAsDouble();
      if (random >= user.getProbability()) {
        return "probability";
      }
      int maxRepliesPerDay = user.getMaxRepliesPerDay();
      if (maxRepliesPerDay > 0 && repository.countToday(user.getHandle(), LocalDate.now(clock)) >= maxRepliesPerDay) {
        return "daily_limit";
      }
    }
    return null;
  }

}
