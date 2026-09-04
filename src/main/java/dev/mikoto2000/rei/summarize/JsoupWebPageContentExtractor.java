package dev.mikoto2000.rei.summarize;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

@Component
public class JsoupWebPageContentExtractor implements WebPageContentExtractor {

  @Override
  public String extract(String url, String html) {
    Document document = Jsoup.parse(html == null ? "" : html, url);
    document.select("script,style,noscript,header,footer,nav,aside,form,iframe").remove();
    Element contentRoot = firstContentRoot(document);
    if (contentRoot == null) {
      contentRoot = document.body();
    }
    return normalize(contentRoot == null ? "" : contentRoot.text());
  }

  private Element firstContentRoot(Document document) {
    Element article = document.selectFirst("main article, article");
    if (article != null) {
      return article;
    }
    return document.selectFirst("main, [role=main]");
  }

  private String normalize(String value) {
    return value == null ? "" : value.replaceAll("\\s+", " ").trim();
  }
}
