package dev.mikoto2000.rei.websearch;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

@Component
public class WebPageExtractor {

  private static final int MAX_CONTENT_LENGTH = 2000;

  public WebSearchPage extract(WebSearchResult result, String html) {
    Document document = Jsoup.parse(html, result.url());
    document.select("script,style,noscript,header,footer,nav,aside,form").remove();

    String title = blankToFallback(document.title(), result.title());
    String publishedAt = firstNonBlank(
        metaContent(document, "article:published_time"),
        metaContent(document, "article:modified_time"),
        metaContent(document, "og:updated_time"),
        timeDatetime(document),
        result.publishedAt());

    String content = normalize(document.body() == null ? "" : document.body().text());
    if (content.isBlank()) {
      content = normalize(result.snippet());
    }
    if (content.length() > MAX_CONTENT_LENGTH) {
      content = content.substring(0, MAX_CONTENT_LENGTH);
    }

    return new WebSearchPage(
        title,
        result.url(),
        result.snippet(),
        publishedAt,
        content);
  }

  private String metaContent(Document document, String property) {
    Elements elements = document.select("meta[property=" + property + "], meta[name=" + property + "]");
    for (Element element : elements) {
      String content = element.attr("content");
      if (!content.isBlank()) {
        return content;
      }
    }
    return null;
  }

  private String timeDatetime(Document document) {
    Element time = document.selectFirst("time[datetime]");
    return time == null ? null : blankToFallback(time.attr("datetime"), null);
  }

  private String normalize(String value) {
    if (value == null) {
      return "";
    }
    return value.replaceAll("\\s+", " ").trim();
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private String blankToFallback(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
