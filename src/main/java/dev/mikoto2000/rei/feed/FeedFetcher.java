package dev.mikoto2000.rei.feed;

import java.io.StringReader;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

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
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(response.body())));
      Element root = document.getDocumentElement();
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
    String title = text(channel, "title");
    String siteUrl = text(channel, "link");
    String description = text(channel, "description");
    List<FetchedFeedItem> items = children(channel, "item").stream()
        .map(item -> new FetchedFeedItem(
            text(item, "title"),
            text(item, "link"),
            parseDate(firstNonBlank(text(item, "pubDate"), text(item, "date")))))
        .toList();
    return new FetchedFeed(title, siteUrl, description, items);
  }

  private FetchedFeed parseAtom(Element root) {
    String title = text(root, "title");
    String siteUrl = atomLink(root);
    String description = firstNonBlank(text(root, "subtitle"), text(root, "description"));
    List<FetchedFeedItem> items = children(root, "entry").stream()
        .map(entry -> new FetchedFeedItem(
            text(entry, "title"),
            atomLink(entry),
            parseDate(firstNonBlank(text(entry, "published"), text(entry, "updated")))))
        .toList();
    return new FetchedFeed(title, siteUrl, description, items);
  }

  private String atomLink(Element parent) {
    for (Element link : children(parent, "link")) {
      String rel = link.getAttribute("rel");
      if (rel == null || rel.isBlank() || "alternate".equals(rel)) {
        String href = link.getAttribute("href");
        if (href != null && !href.isBlank()) {
          return href;
        }
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

  private Element child(Element parent, String name) {
    return children(parent, name).stream().findFirst().orElseThrow(() -> new FeedFetchException("必須要素がありません: " + name, null));
  }

  private List<Element> children(Element parent, String name) {
    List<Element> result = new ArrayList<>();
    for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node.getNodeType() == Node.ELEMENT_NODE && name.equals(localName((Element) node))) {
        result.add((Element) node);
      }
    }
    return result;
  }

  private String text(Element parent, String name) {
    return children(parent, name).stream()
        .findFirst()
        .map(node -> {
          String value = node.getTextContent();
          return value == null ? null : value.trim();
        })
        .orElse(null);
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

  private String localName(Element element) {
    return element.getLocalName() == null ? element.getTagName() : element.getLocalName();
  }
}
