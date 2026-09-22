package dev.mikoto2000.rei.vectordocument;

import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

@Service
@EnableConfigurationProperties(RerankProperties.class)
public class RerankService {
  private static final Logger logger = LoggerFactory.getLogger(RerankService.class);
  private final RerankProperties properties;
  private final RestClient client;

  public RerankService(RerankProperties properties, RestClient.Builder builder) {
    this.properties = properties;
    if (!properties.enabled() || properties.baseUrl() == null || properties.baseUrl().isBlank()) {
      client = null;
      return;
    }
    if (properties.model() == null || properties.model().isBlank()) {
      throw new IllegalArgumentException("rei.rerank.model is required when reranking is enabled and rei.rerank.base-url is set");
    }
    var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).build());
    factory.setReadTimeout(Duration.ofSeconds(30));
    var clientBuilder = builder.clone().requestFactory(factory).baseUrl(properties.baseUrl());
    if (properties.apiKey() != null && !properties.apiKey().isBlank()) {
      clientBuilder.defaultHeaders(headers -> headers.setBearerAuth(properties.apiKey()));
    }
    client = clientBuilder.build();
  }

  /** Reorders all candidates; preserves their original retrieval scores and metadata. */
  public <T> List<T> rerank(String query, List<T> candidates, Function<T, String> text) {
    if (client == null || candidates.isEmpty()) return candidates;
    try {
      JsonNode response = client.post().uri(properties.path()).contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("model", properties.model(), "query", query,
              "documents", candidates.stream().map(text).toList()))
          .retrieve().body(JsonNode.class);
      JsonNode results = response == null ? null : response.get("results");
      if (results == null || !results.isArray() || results.size() != candidates.size()) {
        throw new IllegalStateException("Incomplete rerank response");
      }
      var seen = new HashSet<Integer>();
      var ranked = new java.util.ArrayList<Rank>();
      for (JsonNode result : results) {
        JsonNode index = result.get("index");
        JsonNode score = result.get("relevance_score");
        if (index == null || !index.isIntegralNumber() || !index.canConvertToInt()
            || index.intValue() < 0 || index.intValue() >= candidates.size()
            || !seen.add(index.intValue()) || score == null || !score.isNumber()
            || !Double.isFinite(score.doubleValue())) {
          throw new IllegalStateException("Invalid rerank response");
        }
        ranked.add(new Rank(index.intValue(), score.doubleValue()));
      }
      return ranked.stream().sorted(Comparator.comparingDouble(Rank::score).reversed()
          .thenComparingInt(Rank::index)).map(rank -> candidates.get(rank.index())).toList();
    } catch (RuntimeException exception) {
      logger.warn("Rerank request failed; using original search order ({})", exception.getClass().getSimpleName());
      return candidates;
    }
  }

  private record Rank(int index, double score) {}
}
