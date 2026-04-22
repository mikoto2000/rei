package dev.mikoto2000.rei.feed;

import java.io.StringReader;
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

@Component
public class FeedFetcher {

  private final FeedHttpFetcher httpFetcher;

  public FeedFetcher(FeedHttpFetcher httpFetcher) {
    this.httpFetcher = httpFetcher;
  }

  public FetchedFeed fetch(String feedUrl) {
    FeedHttpResponse response = httpFetcher.fetch(URI.create(feedUrl));
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
      throw new FeedFetchException("フィードの解析に失敗しました", response.statusCode(), e);
    }
  }

  private FetchedFeed parseRss(Element root) {
    Element channel = child(root, "channel");
    if (channel == null) {
      throw new FeedFetchException("必須要素がありません: channel", null);
    }
    String title = text(channel, "title");
    String siteUrl = text(channel, "link");
    String description = text(channel, "description");
    List<FetchedFeedItem> items = channel.children().stream()
        .filter(element -> "item".equals(localName(element)))
        .map(this::parseRssItem)
        .toList();
    return new FetchedFeed(title, siteUrl, description, items);
  }

  private FetchedFeedItem parseRssItem(Element item) {
    return new FetchedFeedItem(
        text(item, "title"),
        text(item, "link"),
        parseDate(firstNonBlank(text(item, "pubDate"), text(item, "date"))));
  }

  private FetchedFeed parseAtom(Element root) {
    String title = text(root, "title");
    String siteUrl = atomLink(root);
    String description = firstNonBlank(text(root, "subtitle"), text(root, "description"));
    List<FetchedFeedItem> items = root.children().stream()
        .filter(element -> "entry".equals(localName(element)))
        .map(this::parseAtomEntry)
        .toList();
    return new FetchedFeed(title, siteUrl, description, items);
  }

  private FetchedFeedItem parseAtomEntry(Element entry) {
    return new FetchedFeedItem(
        text(entry, "title"),
        atomLink(entry),
        parseDate(firstNonBlank(text(entry, "published"), text(entry, "updated"))));
  }

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
      throw new FeedFetchException("日時の解析に失敗しました: " + value, null, e);
    }
  }

  private String firstNonBlank(String first, String second) {
    if (first != null && !first.isBlank()) {
      return first;
    }
    if (second != null && !second.isBlank()) {
      return second;
    }
    return null;
  }

  private Element child(Element parent, String name) {
    return parent.children().stream()
        .filter(element -> name.equals(localName(element)))
        .findFirst()
        .orElse(null);
  }

  private String text(Element parent, String name) {
    Element child = child(parent, name);
    if (child == null) {
      return null;
    }
    String value = child.text();
    return value == null || value.isBlank() ? null : value.trim();
  }

  private String localName(Element element) {
    return element.tagName();
  }
}
