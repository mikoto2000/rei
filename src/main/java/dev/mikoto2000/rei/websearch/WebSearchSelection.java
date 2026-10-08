package dev.mikoto2000.rei.websearch;

import dev.mikoto2000.rei.http.*;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

/** Shared bounded planning. Candidate sufficiency is not a verified answer. */
public final class WebSearchSelection {
  public record Candidate(WebSearchResult result, List<WebSourceAlias> aliases, int rank) {
    public Candidate { aliases = List.copyOf(aliases); }
  }
  private static final Pattern TERMS = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}._-]*");
  private static final Set<String> STOP = Set.of("the", "a", "of", "and", "for", "in", "to", "how", "what", "is", "official", "latest", "current", "docs", "documentation", "reference", "compare", "sources", "vs");
  private static final Map<String, List<String>> OFFICIAL = Map.ofEntries(
      Map.entry("oracle.com", List.of("java", "jdk", "oracle")), Map.entry("spring.io", List.of("spring")),
      Map.entry("python.org", List.of("python")), Map.entry("mozilla.org", List.of("javascript", "html", "css", "mdn", "web")),
      Map.entry("kubernetes.io", List.of("kubernetes", "k8s")), Map.entry("go.dev", List.of("golang", "go")),
      Map.entry("rust-lang.org", List.of("rust")), Map.entry("nodejs.org", List.of("node", "nodejs")),
      Map.entry("microsoft.com", List.of("azure", "windows", "dotnet", "csharp", ".net", "microsoft")),
      Map.entry("docs.github.com", List.of("github")), Map.entry("react.dev", List.of("react")),
      Map.entry("openai.com", List.of("openai", "chatgpt", "codex")), Map.entry("rfc-editor.org", List.of("http", "rfc", "ietf")));
  private WebSearchSelection() {}
  public static List<Candidate> search(WebSearchService service, WebSearchQueryPlanner planner, String query,
      int limit, WebSearchProperties properties) throws IOException, InterruptedException {
    if (query == null || query.isBlank() || query.length() > 2048) throw new IllegalArgumentException("query must contain 1..2048 characters");
    properties.validateSelection();
    Map<String, Candidate> unique = new LinkedHashMap<>(); int calls = 0, rank = 0, observations = 0;
    try (var budget = SearchRequestBudget.enter(properties.getMaxSearchApiCalls())) {
      for (String planned : planner.plan(query)) {
        if (calls >= properties.getMaxSearchQueries() || SearchRequestBudget.exhausted()) break;
        FetchScope.current().check();
        if (!WebResearchScope.registerQuery(planned)) continue;
        if (calls++ > 0 || WebResearchScope.queryCount() > 1) WebSearchMetrics.OBSERVED.add("additional_searches", 1);
        List<WebSearchResult> results;
        try { results = service.search(planned, limit); }
        catch (RuntimeException failed) {
          FetchOperation.propagateControls(failed);
          if (failed instanceof HttpFetchException http && http.code() == HttpFetchException.Code.REQUEST_BUDGET) break;
          if (unique.isEmpty()) throw failed; else continue;
        } catch (IOException failed) { if (unique.isEmpty()) throw failed; else continue; }
        for (WebSearchResult result : results) {
          observations++; String key = normalizeUrl(result.url()); if (key == null) continue;
          Candidate existing = unique.get(key);
          if (existing == null) unique.put(key, new Candidate(result, List.of(WebSourceAlias.from(result)), rank++));
          else {
            WebSearchMetrics.OBSERVED.add("duplicate_urls", 1);
            var aliases = new ArrayList<>(existing.aliases());
            if (!aliases.contains(WebSourceAlias.from(result))) aliases.add(WebSourceAlias.from(result));
            unique.put(key, new Candidate(priority(query, result) > priority(query, existing.result()) ? result : existing.result(), aliases, existing.rank()));
          }
        }
        if (observations > 0 && unique.size() * 2 >= observations
            && sufficient(query, unique.values().stream().map(Candidate::result).toList(), limit)) break;
      }
    }
    return choose(query, new ArrayList<>(unique.values()), limit);
  }
  public static List<Candidate> choose(String query, List<Candidate> candidates, int limit) {
    var ordered = new ArrayList<>(candidates);
    ordered.sort(Comparator.<Candidate>comparingDouble(c -> priority(query, c.result())).reversed().thenComparingInt(Candidate::rank));
    List<Candidate> selected = new ArrayList<>(); Set<String> domains = new HashSet<>();
    for (Candidate candidate : ordered) { if (selected.size() >= limit) break; if (domains.add(domain(candidate.result().url()))) selected.add(candidate); }
    for (Candidate candidate : ordered) { if (selected.size() >= limit) break; if (!selected.contains(candidate)) selected.add(candidate); }
    return List.copyOf(selected);
  }
  static double priority(String query, WebSearchResult result) {
    return relevance(query, result) * 4 + (official(query, result.url()) ? 2 : 0)
        + (matchesVersion(query, result) ? 1 : 0) + (needsFreshness(query) && fresh(result.publishedAt()) ? 1 : 0);
  }
  public static boolean sufficient(String query, List<WebSearchResult> candidates, int limit) {
    var unique = new LinkedHashMap<String, WebSearchResult>();
    for (var result : candidates) { String key = normalizeUrl(result.url()); if (key != null) unique.putIfAbsent(key, result); }
    var relevant = unique.values().stream().filter(result -> relevance(query, result) >= 0.5).filter(result -> matchesVersion(query, result)).toList();
    if (relevant.size() < Math.max(1, limit)) return false;
    String lower = query.toLowerCase(Locale.ROOT);
    if (lower.matches("(?s).*(official|公式|documentation|reference|\\bdocs\\b|\\bapi\\b).*") && relevant.stream().noneMatch(result -> official(query, result.url()))) return false;
    if (needsFreshness(query) && relevant.stream().noneMatch(result -> fresh(result.publishedAt()))) return false;
    if (lower.matches("(?s).*(\\bvs\\b|compar|比較|複数|sources).*") && relevant.stream().map(result -> domain(result.url())).distinct().count() < 2) return false;
    return true;
  }
  public static boolean needsFreshness(String query) { return query.toLowerCase(Locale.ROOT).matches("(?s).*(latest|current|recent|today|最新|直近|今日|今年).*"); }
  private static boolean fresh(String value) {
    if (value == null) return false;
    try { LocalDate day = LocalDate.parse(value.substring(0, Math.min(10, value.length()))); LocalDate today = LocalDate.now(ZoneOffset.UTC);
      return !day.isBefore(today.minusDays(30)) && !day.isAfter(today.plusDays(1));
    } catch (RuntimeException unknown) { return false; }
  }
  static boolean matchesVersion(String query, WebSearchResult result) {
    var matcher = Pattern.compile("(?i)(?:\\bv?(\\d+\\.\\d+(?:\\.\\d+){0,3})|(?:java|jdk|python|spring|version|バージョン)\\s*v?(\\d+(?:\\.\\d+){0,3}))").matcher(query);
    while (matcher.find()) { String version = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
      if (!Pattern.compile("(?<![\\d.])" + Pattern.quote(version) + "(?![\\d.])").matcher(text(result)).find()) return false; }
    return true;
  }
  private static String text(WebSearchResult result) { return (Objects.toString(result.title(), "") + " " + Objects.toString(result.snippet(), "") + " " + Objects.toString(result.url(), "")).toLowerCase(Locale.ROOT); }
  public static double relevance(String query, WebSearchResult result) {
    var matcher = TERMS.matcher(query.toLowerCase(Locale.ROOT)); Set<String> terms = new LinkedHashSet<>();
    while (matcher.find()) if (!STOP.contains(matcher.group())) terms.add(matcher.group());
    if (terms.isEmpty()) return 0; String evidence = text(result);
    return terms.stream().filter(evidence::contains).count() / (double) terms.size();
  }
  public static boolean knownOfficial(String url) {
    String host = host(url);
    return OFFICIAL.keySet().stream().anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain)) || host.endsWith(".gov") || host.endsWith(".edu");
  }
  public static boolean official(String query, String url) {
    String host = host(url), lower = query.toLowerCase(Locale.ROOT);
    return OFFICIAL.entrySet().stream().anyMatch(entry -> (host.equals(entry.getKey()) || host.endsWith("." + entry.getKey())) && entry.getValue().stream().anyMatch(term -> Pattern.compile("(?<![a-z0-9])" + Pattern.quote(term) + "(?![a-z0-9])").matcher(lower).find()))
        || host.endsWith(".gov") || host.endsWith(".edu");
  }
  private static String host(String url) { try { return Objects.toString(URI.create(url).getHost(), "").toLowerCase(Locale.ROOT); } catch (RuntimeException invalid) { return ""; } }
  static String domain(String url) { String host = host(url); String[] labels = host.split("\\."); return labels.length < 2 ? host : labels[labels.length - 2] + "." + labels[labels.length - 1]; }
  public static String normalizeUrl(String value) {
    try {
      URI uri = URI.create(value).normalize(); PublicNetworkPolicy.validateSyntax(uri);
      String scheme = uri.getScheme().toLowerCase(Locale.ROOT), host = uri.getHost().toLowerCase(Locale.ROOT);
      int port = uri.getPort(); String authority = host + (port < 0 || port == (scheme.equals("https") ? 443 : 80) ? "" : ":" + port);
      String path = uri.getRawPath(); if (path == null || path.isEmpty()) path = "/";
      List<String> query = new ArrayList<>();
      if (uri.getRawQuery() != null) for (String pair : uri.getRawQuery().split("&", -1)) {
        String key = URLDecoder.decode(pair.split("=", 2)[0], StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        if (!key.startsWith("utm_") && !Set.of("gclid", "fbclid", "msclkid").contains(key)) query.add(pair);
      }
      return scheme + "://" + authority + path + (query.isEmpty() ? "" : "?" + String.join("&", query));
    } catch (RuntimeException invalid) { return null; }
  }
}
