package dev.mikoto2000.rei.websearch;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
@RequiredArgsConstructor
public class WebSearchService {

  private final WebSearchProperties properties;

  private final JsonMapper objectMapper;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  public WebSearchResponse search(String query, Integer limit) throws IOException, InterruptedException {
    if (!properties.isEnabled()) {
      throw new IllegalStateException("Web search is disabled. Set rei.web-search.enabled=true to enable it.");
    }
    if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
      throw new IllegalStateException(
          "Web search API key is not configured. Set rei.web-search.api-key or REI_OPENAI_API_KEY.");
    }

    int requestedLimit = limit == null ? properties.getMaxResults() : limit;
    int clampedLimit = Math.max(1, Math.min(requestedLimit, properties.getMaxResults()));
    URI uri = responsesUri(properties.getBaseUrl());

    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("model", properties.getModel());
    payload.put("input", prompt(query, clampedLimit));
    payload.put("max_output_tokens", properties.getMaxOutputTokens());
    ArrayNode include = payload.putArray("include");
    include.add("web_search_call.action.sources");
    ArrayNode tools = payload.putArray("tools");
    tools.addObject().put("type", "web_search");

    HttpRequest request = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .header("Authorization", "Bearer " + properties.getApiKey())
        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
        .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() >= 400) {
      throw new IllegalStateException("Web search failed with status " + response.statusCode() + ": " + response.body());
    }

    return parseResponse(response.body(), clampedLimit);
  }

  WebSearchResponse parseResponse(String responseBody, int limit) throws IOException {
    JsonNode root = objectMapper.readTree(responseBody);
    return new WebSearchResponse(extractOutputText(root), extractSources(root, limit));
  }

  static URI responsesUri(String baseUrl) {
    String normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    if (normalizedBaseUrl.endsWith("/responses")) {
      return URI.create(normalizedBaseUrl);
    }
    if (normalizedBaseUrl.endsWith("/v1")) {
      return URI.create(normalizedBaseUrl + "/responses");
    }
    return URI.create(normalizedBaseUrl + "/v1/responses");
  }

  private String prompt(String query, int limit) {
    return """
        Search the web for the user's query and answer briefly.
        Query: %s
        Use at most %d sources.
        Keep the answer concise and factual.
        """.formatted(query, limit);
  }

  private String extractOutputText(JsonNode root) {
    JsonNode output = root.path("output");
    if (!output.isArray()) {
      return "";
    }

    StringBuilder builder = new StringBuilder();
    for (JsonNode item : output) {
      if (!"message".equals(item.path("type").asText())) {
        continue;
      }
      JsonNode content = item.path("content");
      if (!content.isArray()) {
        continue;
      }
      for (JsonNode contentItem : content) {
        if ("output_text".equals(contentItem.path("type").asText())) {
          if (!builder.isEmpty()) {
            builder.append('\n');
          }
          builder.append(contentItem.path("text").asText(""));
        }
      }
    }

    return builder.toString().trim();
  }

  private List<WebSearchResult> extractSources(JsonNode root, int limit) {
    JsonNode output = root.path("output");
    List<WebSearchResult> sources = new ArrayList<>();
    if (!output.isArray()) {
      return sources;
    }

    for (JsonNode item : output) {
      if (!"web_search_call".equals(item.path("type").asText())) {
        continue;
      }
      JsonNode sourceNodes = item.path("action").path("sources");
      if (!sourceNodes.isArray()) {
        continue;
      }
      for (JsonNode source : sourceNodes) {
        if (sources.size() >= limit) {
          return sources;
        }
        sources.add(new WebSearchResult(
            source.path("title").asText(""),
            source.path("url").asText(""),
            textOrDefault(source, "snippet", "text", "description"),
            nullableText(source, "published_at", "publishedAt", "date")));
      }
    }

    return sources;
  }

  private String textOrDefault(JsonNode node, String... fieldNames) {
    String value = nullableText(node, fieldNames);
    return value == null ? "" : value;
  }

  private String nullableText(JsonNode node, String... fieldNames) {
    for (String fieldName : fieldNames) {
      JsonNode candidate = node.path(fieldName);
      if (!candidate.isMissingNode() && !candidate.isNull()) {
        String text = candidate.asText("");
        if (!text.isBlank()) {
          return text;
        }
      }
    }
    return null;
  }
}
