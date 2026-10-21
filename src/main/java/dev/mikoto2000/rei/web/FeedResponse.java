package dev.mikoto2000.rei.web;

/**
 * Web APIによる操作で受け渡すフィード応答を表す。
 *
 * @param id 識別子
 * @param url 参照先のURL
 * @param title タイトル
 * @param displayName 表示名前
 * @param enabled 有効状態
 */
public record FeedResponse(long id, String url, String title, String displayName, boolean enabled) {
  /** Web APIによる操作で利用する情報を、受け取った形式から変換する。 */
  public static FeedResponse from(dev.mikoto2000.rei.feed.Feed f) {
    return new FeedResponse(f.id(), f.url(), f.title(), f.displayName(), f.enabled());
  }
}
