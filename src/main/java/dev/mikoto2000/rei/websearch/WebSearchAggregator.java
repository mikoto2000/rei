package dev.mikoto2000.rei.websearch;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.stereotype.Component;

/** Web検索の情報を集計する。 */
@Component
public class WebSearchAggregator {

  /** 記録された情報を集計し、全体の傾向や結果をまとめる。 */
  public WebSearchContext aggregate(List<WebSearchPage> pages, int limit) {
    List<WebSearchPage> ranked = pages.stream().limit(Math.max(1, limit)).toList();

    List<WebSearchPage> primary = ranked.stream().filter(this::isPrimary).toList();
    List<WebSearchPage> secondary = ranked.stream().filter(page -> !isPrimary(page)).toList();
    return new WebSearchContext(primary, secondary, ranked);
  }

  /** Web上の情報の調査において、主要なかどうかを判定する。 */
  private boolean isPrimary(WebSearchPage page) {
    return "success".equals(page.fetchStatus())
        && (isOfficialHost(page.url()) || (hasPublishedAt(page) && contentLength(page) >= 80));
  }

  /** Web上の情報の調査において、公開日時の有無を判定する。 */
  private boolean hasPublishedAt(WebSearchPage page) {
    if (page.publishedAt() == null || page.publishedAt().isBlank()) {
      return false;
    }
    try {
      OffsetDateTime.parse(page.publishedAt());
      return true;
    } catch (DateTimeParseException e) {
      try {
        java.time.LocalDate.parse(page.publishedAt());
        return true;
      } catch (DateTimeParseException unknown) {
        return false;
      }
    }
  }

  /** Web上の情報の調査で用いる内容の長さを返す。 */
  private int contentLength(WebSearchPage page) {
    return page.content() == null ? 0 : page.content().trim().length();
  }

  /** Web上の情報の調査において、Officialホストかどうかを判定する。 */
  private boolean isOfficialHost(String url) {
    return WebSearchSelection.knownOfficial(url);
  }
}
