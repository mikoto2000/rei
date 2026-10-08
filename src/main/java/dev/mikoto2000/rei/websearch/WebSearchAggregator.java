package dev.mikoto2000.rei.websearch;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

@Component
public class WebSearchAggregator {

  public WebSearchContext aggregate(List<WebSearchPage> pages, int limit) {
    List<WebSearchPage> ranked = pages.stream()
        .sorted(Comparator.comparingDouble(this::score).reversed())
        .limit(Math.max(1, limit))
        .toList();

    List<WebSearchPage> primary = ranked.stream()
        .filter(this::isPrimary)
        .toList();
    List<WebSearchPage> secondary = ranked.stream()
        .filter(page -> !isPrimary(page))
        .toList();
    return new WebSearchContext(primary, secondary);
  }

  private boolean isPrimary(WebSearchPage page) {
    return isOfficialHost(page.url()) || (hasPublishedAt(page) && contentLength(page) >= 80);
  }

  private double score(WebSearchPage page) {
    double score = 0.0d;
    if (isOfficialHost(page.url())) {
      score += 3.0d;
    }
    if (hasPublishedAt(page)) {
      score += 1.0d;
    }
    score += Math.min(contentLength(page), 500) / 500.0d;
    return score;
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
