package dev.mikoto2000.rei.bluesky;

import java.util.List;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class BlueskyAuthorFeedClient {

  private final BlueskyApiClient blueskyApiClient;

  public String resolveDid(String handle) {
    return blueskyApiClient.resolveHandle(handle);
  }

  public List<BlueskyApiClient.FeedPost> getAuthorFeed(String actorDid, int limit) {
    return blueskyApiClient.getAuthorFeed(actorDid, limit);
  }
}
