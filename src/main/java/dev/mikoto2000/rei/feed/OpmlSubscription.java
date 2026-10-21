package dev.mikoto2000.rei.feed;

import java.util.List;

/**
 * フィードの購読と収集で受け渡すOPML購読を表す。
 *
 * <p>Raw OPML metadata; invalid URLs are retained for per-subscription reporting.
 *
 * @param title タイトル
 * @param text テキスト
 * @param xmlUrl XML形式URL
 * @param htmlUrl HTMLURL
 * @param categories 分類一覧
 */
public record OpmlSubscription(
    String title, String text, String xmlUrl, String htmlUrl, List<String> categories) {
  /** 受け取った情報からOpmlSubscriptionを作成する。 */
  public OpmlSubscription {
    categories = List.copyOf(categories);
  }

  /** フィードの購読と収集で用いる表示名前を返す。 */
  public String displayName() {
    return !title.isBlank() ? title : !text.isBlank() ? text : xmlUrl;
  }
}
