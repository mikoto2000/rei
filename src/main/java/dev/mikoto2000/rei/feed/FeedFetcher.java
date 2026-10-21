package dev.mikoto2000.rei.feed;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Component;

/** フィードに必要な情報を外部から取得する。 */
@Component
public class FeedFetcher {

  /** HTTP通信情報の取得元。 */
  private final FeedHttpFetcher httpFetcher;

  /** 指定された値や利用先を使って、FeedFetcherを初期化する。 */
  public FeedFetcher(FeedHttpFetcher httpFetcher) {
    this.httpFetcher = httpFetcher;
  }

  /** 後続の処理に必要な情報を、取得先から読み込む。 */
  public FetchedFeed fetch(String feedUrl) {
    URI uri;
    try {
      uri = FeedUrlSafety.parse(feedUrl);
    } catch (IllegalArgumentException e) {
      throw new FeedFetchException("フィード URL が不正です。認証情報を URL に含めないでください", null);
    }
    FeedHttpResponse response = httpFetcher.fetch(uri);
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new FeedFetchException("フィードの取得に失敗しました", response.statusCode());
    }

    try {
      Document document = Jsoup.parse(response.body(), "", Parser.xmlParser());
      Element root = document.children().first();
      if (root == null) {
        throw new FeedFetchException("未対応のフィード形式です", response.statusCode());
      }
      String rootName = localName(root);
      if ("rss".equals(rootName)) {
        return parseRss(root);
      }
      if ("feed".equals(rootName)) {
        return parseAtom(root);
      }
      throw new FeedFetchException("未対応のフィード形式です", response.statusCode());
    } catch (FeedFetchException e) {
      throw e;
    } catch (Exception e) {
      throw new FeedFetchException("フィードの解析に失敗しました", response.statusCode());
    }
  }

  /** 入力を機能内で扱えるよう、RSSを解釈する。 */
  private FetchedFeed parseRss(Element root) {
    Element channel = child(root, "channel");
    if (channel == null) {
      throw new FeedFetchException("必須要素がありません: channel", null);
    }
    String title = text(channel, "title");
    String siteUrl = text(channel, "link");
    String description = text(channel, "description");
    List<FetchedFeedItem> items =
        channel.children().stream()
            .filter(element -> "item".equals(localName(element)))
            .map(this::parseRssItem)
            .toList();
    return new FetchedFeed(title, siteUrl, description, items);
  }

  /** 入力を機能内で扱えるよう、RSS項目を解釈する。 */
  private FetchedFeedItem parseRssItem(Element item) {
    return new FetchedFeedItem(
        text(item, "title"),
        text(item, "link"),
        parseDate(firstNonBlank(text(item, "pubDate"), text(item, "date"))),
        text(item, "description"),
        firstNonBlank(text(item, "content:encoded"), text(item, "encoded")));
  }

  /** 入力を機能内で扱えるよう、Atomを解釈する。 */
  private FetchedFeed parseAtom(Element root) {
    String title = text(root, "title");
    String siteUrl = atomLink(root);
    String description = firstNonBlank(text(root, "subtitle"), text(root, "description"));
    List<FetchedFeedItem> items =
        root.children().stream()
            .filter(element -> "entry".equals(localName(element)))
            .map(this::parseAtomEntry)
            .toList();
    return new FetchedFeed(title, siteUrl, description, items);
  }

  /** 入力を機能内で扱えるよう、Atom項目を解釈する。 */
  private FetchedFeedItem parseAtomEntry(Element entry) {
    return new FetchedFeedItem(
        text(entry, "title"),
        atomLink(entry),
        parseDate(firstNonBlank(text(entry, "published"), text(entry, "updated"))),
        text(entry, "summary"),
        text(entry, "content"));
  }

  /** フィードの購読と収集で用いるAtomリンクを返す。 */
  private String atomLink(Element parent) {
    for (Element link : parent.children()) {
      if (!"link".equals(localName(link))) {
        continue;
      }
      String rel = link.attr("rel");
      String href = link.attr("href");
      if (href == null || href.isBlank()) {
        continue;
      }
      if (rel == null || rel.isBlank() || "alternate".equals(rel)) {
        return href;
      }
    }
    return null;
  }

  /** 入力を機能内で扱えるよう、日付を解釈する。 */
  private OffsetDateTime parseDate(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(value);
    } catch (DateTimeParseException ignored) {
    }
    try {
      return OffsetDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
    } catch (DateTimeParseException e) {
      throw new FeedFetchException("日時の解析に失敗しました", null);
    }
  }

  /** フィードの購読と収集で用いる最初以外空白を返す。 */
  private String firstNonBlank(String first, String second) {
    if (first != null && !first.isBlank()) {
      return first;
    }
    if (second != null && !second.isBlank()) {
      return second;
    }
    return null;
  }

  /** フィードの購読と収集で用いる子を返す。 */
  private Element child(Element parent, String name) {
    return parent.children().stream()
        .filter(element -> name.equals(localName(element)))
        .findFirst()
        .orElse(null);
  }

  /** 表示や受け渡しに用いるテキストを返す。 */
  private String text(Element parent, String name) {
    Element child = child(parent, name);
    if (child == null) {
      return null;
    }
    String value = child.text();
    return value == null || value.isBlank() ? null : value.trim();
  }

  /** フィードの購読と収集で用いるローカル名前を返す。 */
  private String localName(Element element) {
    return element.tagName();
  }
}
