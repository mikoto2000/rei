package dev.mikoto2000.rei.bluesky;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Bluesky著者フィードを外部の接続先へ依頼する窓口。 */
@Component
@RequiredArgsConstructor
public class BlueskyAuthorFeedClient {

  /** BlueskyAPIクライアント。 */
  private final BlueskyApiClient blueskyApiClient;

  /** 指定や設定に応じた対象を利用するため、分散識別子を確定する。 */
  public String resolveDid(String handle) {
    return blueskyApiClient.resolveHandle(handle);
  }

  /** Blueskyとの連携で参照する著者フィードを返す。 */
  public List<BlueskyApiClient.FeedPost> getAuthorFeed(String actorDid, int limit) {
    return blueskyApiClient.getAuthorFeed(actorDid, limit);
  }
}
