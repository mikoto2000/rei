package dev.mikoto2000.rei.feed;

import java.net.URI;

/** フィードHTTP通信に必要な情報を外部から取得する。 */
@FunctionalInterface
public interface FeedHttpFetcher {
  /** 後続の処理に必要な情報を、取得先から読み込む。 */
  FeedHttpResponse fetch(URI uri);
}
