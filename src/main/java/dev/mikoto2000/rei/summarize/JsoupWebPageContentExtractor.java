package dev.mikoto2000.rei.summarize;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/** JsoupWebページ内容に必要な情報を入力から取り出す。 */
@Component
public class JsoupWebPageContentExtractor implements WebPageContentExtractor {

  /** 受け取った内容から、後続の処理に必要な情報を取り出す。 */
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

  /** 情報の要約で用いる最初内容作業ルートを返す。 */
  private Element firstContentRoot(Document document) {
    Element article = document.selectFirst("main article, article");
    if (article != null) {
      return article;
    }
    return document.selectFirst("main, [role=main]");
  }

  /** 同じ意味の指定を共通に扱えるよう、表記や形式をそろえる。 */
  private String normalize(String value) {
    return value == null ? "" : value.replaceAll("\\s+", " ").trim();
  }
}
