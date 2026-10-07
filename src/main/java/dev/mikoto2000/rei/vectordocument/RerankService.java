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
public class RerankService implements CandidateReranker {
  private static final Logger logger = LoggerFactory.getLogger(RerankService.class);
  private final RerankProperties properties;
  private final RestClient client;
  private static final tools.jackson.databind.json.JsonMapper BUDGET_JSON=tools.jackson.databind.json.JsonMapper.builder(
      tools.jackson.core.json.JsonFactory.builder().streamReadConstraints(tools.jackson.core.StreamReadConstraints.builder()
          .maxNestingDepth(32).maxNumberLength(32).maxStringLength(65536).build())
          .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
      .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

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
  @Override public <T> List<T> rerank(String query, List<T> candidates, Function<T, String> text) {
    return rerank(query,candidates,text,false);
  }
  @Override public <T> List<T> rerankForEvaluation(String query,List<T> candidates,Function<T,String> text){return rerank(query,candidates,text,true);}
  private <T> List<T> rerank(String query,List<T> candidates,Function<T,String> text,boolean strict){
    if(strict){dev.mikoto2000.rei.core.chat.RunCancellation.propagate(null);if(client==null)throw new IllegalStateException("Rerank evaluation provider unavailable");}
    if (client == null || candidates.isEmpty()) return candidates;
    var budget=properties.inheritRunModelBudget()?dev.mikoto2000.rei.llm.ModelCallBudgetScope.current():null;
    boolean invoked=false,reported=false;
    try {
      var request = client.post().uri(properties.path()).contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("model", properties.model(), "query", query,
              "documents", candidates.stream().map(text).toList()));
      if(budget!=null)budget.run();
      invoked=true;
      JsonNode response=budget==null?request.retrieve().body(JsonNode.class):request.exchange((sent,received)->{
        if(!received.getStatusCode().is2xxSuccessful())throw new IllegalStateException("Rerank HTTP request unsuccessful");
        var body=received.getBody().readNBytes(65537);
        if(body.length>65536)throw new IllegalStateException("Rerank response exceeds budget evidence limit");
        return BUDGET_JSON.readTree(body);
      });
      if(budget!=null) {
        var usage=response==null?null:response.get("usage");var total=usage==null?null:usage.get("total_tokens");
        Integer tokens=total!=null&&total.isIntegralNumber()&&total.canConvertToInt()?total.intValue():null;
        reported=true;budget.recordTotalTokens(tokens);
      }
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
      if(budget!=null) {
        dev.mikoto2000.rei.core.chat.RunCancellation.propagate(exception);
        if(invoked&&!reported)budget.recordTotalTokens(null);
        if(exception instanceof dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException)throw exception;
      }
      logger.warn("Rerank request failed; using original search order ({})", exception.getClass().getSimpleName());
      if(strict){dev.mikoto2000.rei.core.chat.RunCancellation.propagate(exception);throw new IllegalStateException("Rerank evaluation provider failed");}
      return candidates;
    }
  }

  private record Rank(int index, double score) {}
}
