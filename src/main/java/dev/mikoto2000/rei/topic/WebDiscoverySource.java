package dev.mikoto2000.rei.topic;

import dev.mikoto2000.rei.websearch.WebSearchAndReadRequest;
import dev.mikoto2000.rei.websearch.WebSearchAndReadService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/** 話題の管理に必要なWeb利用先の検出取得元を扱う。 */
@Component
@ConditionalOnBean(WebSearchAndReadService.class)
public class WebDiscoverySource implements DiscoverySource {
  /** サービス。 */
  private final WebSearchAndReadService service;

  /** 指定された値や利用先を使って、WebDiscoverySourceを初期化する。 */
  public WebDiscoverySource(WebSearchAndReadService service) {
    this.service = service;
  }

  /** 話題の管理で用いる利用先の検出を返す。 */
  @Override
  public List<DiscoveryItem> discover(DiscoveryContext context) throws Exception {
    List<DiscoveryItem> items = new ArrayList<>();
    for (String seed : context.seeds()) {
      var response = service.searchAndRead(new WebSearchAndReadRequest(seed, 3, 1));
      response.results().stream()
          .filter(result -> result.title() != null && !result.title().isBlank())
          .map(
              result ->
                  new DiscoveryItem(
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

  /** 話題の管理で用いる関連度を返す。 */
  private double relevance(String seed, String text) {
    if (text == null || seed == null) return 0.0d;
    return text.toLowerCase(java.util.Locale.ROOT).contains(seed.toLowerCase(java.util.Locale.ROOT))
        ? 0.80d
        : 0.40d;
  }

  /** 入力を機能内で扱えるよう、日時を解釈する。 */
  private Instant parseInstant(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return Instant.parse(value);
    } catch (Exception e) {
      return null;
    }
  }
}
