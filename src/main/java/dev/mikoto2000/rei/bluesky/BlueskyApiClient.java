package dev.mikoto2000.rei.bluesky;

import java.time.OffsetDateTime;
import java.util.List;

public interface BlueskyApiClient {

  AuthResult authenticate(String handle, String appPassword);

  PostResult createPost(String accessJwt, String did, String text);

  default String resolveHandle(String handle) {
    throw new UnsupportedOperationException("resolveHandle is not implemented");
  }

  default List<FeedPost> getAuthorFeed(String actorDid, int limit) {
    throw new UnsupportedOperationException("getAuthorFeed is not implemented");
  }

  default List<FeedPost> getAuthorFeed(String actorDid, int limit, String accessJwt) {
    return getAuthorFeed(actorDid, limit);
  }

  default PostResult createReply(String accessJwt, String did, String text, String parentUri, String parentCid, String rootUri,
      String rootCid) {
    throw new UnsupportedOperationException("createReply is not implemented");
  }

  default ReplyTarget resolveReplyTarget(String accessJwt, String targetPostUriOrUrl) {
    throw new UnsupportedOperationException("resolveReplyTarget is not implemented");
  }

  record AuthResult(boolean success, String accessJwt, String did) {
  }

  record PostResult(boolean success, String postUri) {
  }

  record FeedPost(
      String uri,
      String cid,
      String text,
      OffsetDateTime indexedAt,
      boolean repost,
      boolean reply,
      String rootUri,
      String rootCid) {
  }

  record ReplyTarget(String parentUri, String parentCid, String rootUri, String rootCid, String targetText) {
  }
}
