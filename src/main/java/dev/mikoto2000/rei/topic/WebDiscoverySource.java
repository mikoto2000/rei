package dev.mikoto2000.rei.topic;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.websearch.WebSearchAndReadRequest;
import dev.mikoto2000.rei.websearch.WebSearchAndReadService;

@Component
@ConditionalOnBean(WebSearchAndReadService.class)
public class WebDiscoverySource implements DiscoverySource {
  private final WebSearchAndReadService service;

  public WebDiscoverySource(WebSearchAndReadService service) {
    this.service = service;
  }

  @Override
  public List<DiscoveryItem> discover(DiscoveryContext context) throws Exception {
    List<DiscoveryItem> items = new ArrayList<>();
    for (String seed : context.seeds()) {
      var response = service.searchAndRead(new WebSearchAndReadRequest(seed, 3, 1));
      response.results().stream()
          .filter(result -> result.title() != null && !result.title().isBlank())
          .map(result -> new DiscoveryItem(
              result.url(),
              result.title(),
              result.snippet(),
              result.url(),
              TopicSource.WEB,
              parseInstant(result.publishedAt()),
              relevance(seed, result.title() + " " + result.snippet()),
              seed))
          .forEach(items::add);
    }
    return items;
  }

  private double relevance(String seed, String text) {
    if (text == null || seed == null) return 0.0d;
    return text.toLowerCase(java.util.Locale.ROOT).contains(seed.toLowerCase(java.util.Locale.ROOT)) ? 0.80d : 0.40d;
  }

  private Instant parseInstant(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return Instant.parse(value);
    } catch (Exception e) {
      return null;
    }
  }
}
