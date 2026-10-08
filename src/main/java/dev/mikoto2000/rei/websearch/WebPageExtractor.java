package dev.mikoto2000.rei.websearch;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

@Component
public class WebPageExtractor {

  private final WebSearchProperties properties;
  public WebPageExtractor() { this(new WebSearchProperties()); }
  @org.springframework.beans.factory.annotation.Autowired
  public WebPageExtractor(WebSearchProperties properties) { this.properties = properties; }

  public WebSearchPage extract(WebSearchResult result, String html) {
    return extract(result, html, WebExtractionScope.query());
  }
  public WebSearchPage extract(WebSearchResult result, String html, String query) {
    properties.validateSelection();
    Document document;
    try (var reader = new WebHtmlReader(html, properties.getMaxDecodedBytes())) {
      document = org.jsoup.parser.Parser.htmlParser().setTrackPosition(true).parseInput(reader, result.url());
    }
    dev.mikoto2000.rei.http.FetchScope.current().check();
    document.select("script,style,noscript,header,footer,nav,aside,form").remove();

    String title = blankToFallback(document.title(), result.title());
    String publishedAt = firstNonBlank(
        metaContent(document, "article:published_time"),
        metaContent(document, "article:modified_time"),
        metaContent(document, "og:updated_time"),
        timeDatetime(document),
        result.publishedAt());

    String content = normalize(document.body() == null ? "" : document.body().text());
    String fingerprint = content.isBlank() ? null : WebContentDeduplication.fingerprint(content + "\n"
        + document.select("pre,code").stream().filter(element -> element.parents().stream().noneMatch(parent -> parent.normalName().equals("pre")))
            .map(Element::wholeText).collect(java.util.stream.Collectors.joining("\n")));
    if (content.isBlank()) {
      content = normalize(result.snippet());
    }
    var selected = query == null || query.isBlank() ? null : WebSectionExtractor.select(document, query, properties.getPageMaxCharacters(), properties.getPageMaxTokens());
    boolean truncated = selected == null ? content.length() > properties.getPageMaxCharacters() : selected.truncated();
    content = selected == null ? WebSectionExtractor.clip(content, properties.getPageMaxCharacters()) : selected.content();

    var page = new WebSearchPage(
        title,
        result.url(),
        result.snippet(),
        publishedAt,
        content,
        truncated,
        fingerprint,
        java.util.List.of(WebSourceAlias.from(result))).withEvidence(null, null,
            selected == null ? java.util.List.of() : selected.excerpts(), selected == null ? java.util.List.of() : selected.omissions());
    Element canonical = document.selectFirst("link[rel=canonical][href]");
    if (canonical != null) {
      String claimed = WebSearchSelection.normalizeUrl(canonical.absUrl("href"));
      String original = WebSearchSelection.normalizeUrl(result.url());
      if (claimed != null && original != null) {
        var claimedUri = java.net.URI.create(claimed); var originalUri = java.net.URI.create(original);
        if (claimedUri.getScheme().equals(originalUri.getScheme()) && claimedUri.getRawAuthority().equals(originalUri.getRawAuthority()))
          page = page.withAliases(java.util.List.of(new WebSourceAlias(claimed, title, publishedAt, "page_canonical_claim")));
      }
    }
    return page;
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
