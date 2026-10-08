package dev.mikoto2000.rei.websearch;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class WebSearchAggregator {

  public WebSearchContext aggregate(List<WebSearchPage> pages, int limit) {
    List<WebSearchPage> ranked = pages.stream()
        .limit(Math.max(1, limit))
        .toList();

    List<WebSearchPage> primary = ranked.stream()
        .filter(this::isPrimary)
        .toList();
    List<WebSearchPage> secondary = ranked.stream()
        .filter(page -> !isPrimary(page))
        .toList();
    return new WebSearchContext(primary, secondary, ranked);
  }

  private boolean isPrimary(WebSearchPage page) {
    return "success".equals(page.fetchStatus()) && (isOfficialHost(page.url()) || (hasPublishedAt(page) && contentLength(page) >= 80));
  }

  private boolean hasPublishedAt(WebSearchPage page) {
    if (page.publishedAt() == null || page.publishedAt().isBlank()) {
      return false;
    }
    try {
      OffsetDateTime.parse(page.publishedAt());
      return true;
    } catch (DateTimeParseException e) {
      try { java.time.LocalDate.parse(page.publishedAt()); return true; }
      catch (DateTimeParseException unknown) { return false; }
    }
  }

  private int contentLength(WebSearchPage page) {
    return page.content() == null ? 0 : page.content().trim().length();
  }

  private boolean isOfficialHost(String url) {
    return WebSearchSelection.knownOfficial(url);
  }
}
