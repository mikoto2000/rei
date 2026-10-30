package dev.mikoto2000.rei.websearch;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Responses wire format only; provider ordering and page retrieval remain in the pipeline. */
final class OpenAiWebSearch {
  private final JsonMapper mapper;

  OpenAiWebSearch(JsonMapper mapper) {
    this.mapper = mapper;
  }

  /** Deterministic JSON also supplies the POST cache identity. */
  String request(WebSearchProperties.ProviderProperties provider, String query) {
    if (provider.getModel() == null
        || !provider.getModel().matches("[A-Za-z0-9._:-]{1,100}")
        || !Set.of("low", "medium", "high").contains(provider.getSearchContextSize())
        || provider.getMaxUrls() < 1
        || provider.getMaxUrls() > 100) throw failure("CONFIGURATION");
    var tool = new LinkedHashMap<String, Object>();
    tool.put("type", "web_search");
    tool.put("search_context_size", provider.getSearchContextSize());
    var filters = new LinkedHashMap<String, Object>();
    var allowed = domains(provider.getAllowedDomains());
    var blocked = domains(provider.getBlockedDomains());
    if (!allowed.isEmpty()) filters.put("allowed_domains", allowed);
    if (!blocked.isEmpty()) filters.put("blocked_domains", blocked);
    if (!filters.isEmpty()) tool.put("filters", filters);
    var body = new LinkedHashMap<String, Object>();
    body.put("model", provider.getModel());
    body.put("input", query);
    body.put("tools", List.of(tool));
    body.put("tool_choice", "required");
    body.put("include", List.of("web_search_call.action.sources"));
    body.put("store", false);
    return mapper.writeValueAsString(body);
  }

  /**
   * Only structured URL sources/citations are candidates; generated prose is never page content.
   */
  List<WebSearchResult> parse(
      WebSearchProperties.ProviderProperties provider, String body, int limit) {
    JsonNode root;
    try {
      root = mapper.readTree(body);
    } catch (RuntimeException malformed) {
      // Do not attach parser exceptions: their messages can contain response fragments or secrets.
      throw failure("INVALID_JSON");
    }
    if (root == null || !root.isObject()) throw failure("INVALID_RESPONSE");
    String status = root.path("status").asString("");
    WebSearchMetrics.OBSERVED.openAiStatus(status);
    if (!"completed".equals(status)) throw failure("RESPONSE_NOT_COMPLETED");
    JsonNode output = root.path("output");
    if (!output.isArray()) throw failure("INVALID_RESPONSE");
    var candidates = new LinkedHashMap<String, WebSearchResult>();
    int calls = 0;
    for (JsonNode item : output) {
      if (!"web_search_call".equals(item.path("type").asString(""))) continue;
      calls++;
      if (!"completed".equals(item.path("status").asString("")))
        throw failure("TOOL_NOT_COMPLETED");
      JsonNode action = item.path("action");
      String type = action.path("type").asString("");
      if (!Set.of("search", "open_page", "find_in_page").contains(type))
        throw failure("INVALID_RESPONSE");
      if ("search".equals(type)) {
        JsonNode sources = action.path("sources");
        if (!sources.isArray()) throw failure("MISSING_SOURCES");
        for (JsonNode source : sources) add(provider, candidates, source);
      }
    }
    if (calls == 0) throw failure("TOOL_NOT_EXECUTED");
    for (JsonNode item : output) {
      if (!"message".equals(item.path("type").asString(""))) continue;
      for (JsonNode content : item.path("content"))
        for (JsonNode annotation : content.path("annotations"))
          if ("url_citation".equals(annotation.path("type").asString("")))
            add(provider, candidates, annotation);
    }
    return new ArrayList<>(candidates.values())
        .subList(0, Math.min(candidates.size(), Math.min(limit, provider.getMaxUrls())));
  }

  /** API-reported tokens only; called for network responses, never on cache replay. */
  void recordUsage(String body) {
    JsonNode root;
    try {
      root = mapper.readTree(body);
    } catch (RuntimeException invalid) {
      return;
    }
    if (root == null) return;
    for (String field : List.of("input_tokens", "output_tokens", "total_tokens")) {
      var value = root.path("usage").path(field);
      if (value.isIntegralNumber() && value.asLong(-1) >= 0)
        WebSearchMetrics.OBSERVED.add("openai_actual_" + field, value.asLong());
    }
    for (JsonNode item : root.path("output"))
      if ("web_search_call".equals(item.path("type").asString("")))
        WebSearchMetrics.OBSERVED.add("openai_web_search_calls", 1);
  }

  private void add(
      WebSearchProperties.ProviderProperties provider,
      Map<String, WebSearchResult> candidates,
      JsonNode source) {
    String raw = source.path("url").asString("");
    if (raw.isBlank()) return; // Non-URL feeds (oai-weather etc.) are not fetchable pages.
    WebSearchMetrics.OBSERVED.add("openai_received_urls", 1);
    String url = WebSearchSelection.normalizePublicUrl(raw);
    if (url == null) return;
    String host = URI.create(url).getHost().toLowerCase(Locale.ROOT);
    var allowed = domains(provider.getAllowedDomains());
    if ((!allowed.isEmpty() && !matches(host, allowed))
        || matches(host, domains(provider.getBlockedDomains()))) return;
    String title = source.path("title").asString("");
    var result = new WebSearchResult(title, url, "", null);
    candidates.merge(url, result, (old, next) -> old.title().isBlank() ? next : old);
  }

  private static boolean matches(String host, List<String> domains) {
    return domains.stream().anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
  }

  private static List<String> domains(List<String> values) {
    if (values == null || values.size() > 100) throw failure("DOMAIN_CONFIGURATION");
    return values.stream()
        .map(
            value -> {
              try {
                String domain =
                    java.net
                        .IDN
                        .toASCII(value, java.net.IDN.USE_STD3_ASCII_RULES)
                        .toLowerCase(Locale.ROOT);
                if (domain.isBlank()
                    || domain.length() > 253
                    || domain.startsWith(".")
                    || domain.endsWith(".")) throw failure("DOMAIN_CONFIGURATION");
                return domain;
              } catch (RuntimeException invalid) {
                throw failure("DOMAIN_CONFIGURATION");
              }
            })
        .distinct()
        .sorted()
        .toList();
  }

  static IllegalStateException failure(String code) {
    WebSearchMetrics.OBSERVED.add("openai_error_" + code.toLowerCase(Locale.ROOT), 1);
    return new OpenAiWebSearchException(code);
  }
}
