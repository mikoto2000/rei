package dev.mikoto2000.rei.paper;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class OpenAlexSearchProvider implements AcademicSearchProvider {
  private final PaperHttpClient http;
  private final PaperProperties config;

  public OpenAlexSearchProvider(PaperHttpClient http, PaperProperties config) {
    this.http = http;
    this.config = config;
  }

  static String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  static String text(JsonNode n, String name) {
    var v = n.path(name);
    return v.isMissingNode() || v.isNull() ? null : v.asString();
  }

  public List<Paper> search(PaperSearchQuery q, PaperOperation op) {
    List<String> filters = new ArrayList<>();
    if (q.fromYear() != null) filters.add("from_publication_date:" + q.fromYear() + "-01-01");
    if (q.toYear() != null) filters.add("to_publication_date:" + q.toYear() + "-12-31");
    if (q.openAccessOnly()) filters.add("open_access.is_oa:true");
    String url =
        "https://api.openalex.org/works?search=" + enc(q.query()) + "&per_page=" + q.limit();
    if (!filters.isEmpty()) url += "&filter=" + enc(String.join(",", filters));
    if (q.sort() != PaperSearchQuery.Sort.RELEVANCE)
      url +=
          "&sort="
              + (q.sort() == PaperSearchQuery.Sort.NEWEST
                  ? "publication_date:desc"
                  : "cited_by_count:desc");
    if (!config.getOpenAlexApiKey().isBlank()) url += "&api_key=" + enc(config.getOpenAlexApiKey());
    try {
      var root =
          PaperJson.MAPPER.readTree(
              http.get(URI.create(url), "application/json", config.getMaxResponseBytes(), op));
      if (!root.path("results").isArray()) throw new IllegalArgumentException();
      List<Paper> result = new ArrayList<>();
      for (var n : root.path("results")) {
        op.check();
        List<String> authors = new ArrayList<>();
        for (var a : n.path("authorships")) {
          String name = text(a.path("author"), "display_name");
          if (name != null) authors.add(name);
        }
        TreeMap<Integer, String> words = new TreeMap<>();
        for (var entry : n.path("abstract_inverted_index").properties()) {
          for (var i : entry.getValue())
            if (i.asInt() >= 0 && i.asInt() < 100000) words.put(i.asInt(), entry.getKey());
        }
        var loc = n.path("best_oa_location");
        if (loc.isNull() || loc.isMissingNode()) loc = n.path("primary_location");
        String landing = text(loc, "landing_page_url"), pdf = text(loc, "pdf_url");
        String arxiv = null;
        for (String candidate : List.of(landing == null ? "" : landing, pdf == null ? "" : pdf)) {
          var m =
              java.util.regex.Pattern.compile("https?://arxiv.org/(?:abs|pdf)/([^?#]+)")
                  .matcher(candidate);
          if (m.find()) arxiv = SqlitePaperRepository.arxiv(m.group(1));
        }
        Paper p =
            new Paper(
                null,
                text(n, "title"),
                authors,
                words.isEmpty() ? null : String.join(" ", words.values()),
                n.path("publication_year").isNumber() ? n.path("publication_year").asInt() : null,
                text(loc.path("source"), "display_name"),
                text(n, "doi"),
                arxiv,
                text(n, "id"),
                n.path("cited_by_count").isNumber() ? n.path("cited_by_count").asLong() : null,
                n.path("open_access").path("is_oa").isBoolean()
                    ? n.path("open_access").path("is_oa").asBoolean()
                    : null,
                landing,
                pdf,
                List.of(),
                text(n, "publication_date"),
                text(loc, "license") == null ? List.of() : List.of(text(loc, "license")));
        if (p.title() != null && matches(p, q)) result.add(p);
      }
      return result;
    } catch (PaperException | java.util.concurrent.CancellationException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new PaperException(PaperException.Code.SEARCH_FAILED, "OpenAlex レスポンス不正", e);
    }
  }

  static boolean matches(Paper p, PaperSearchQuery q) {
    return q.authors().stream()
            .allMatch(
                a ->
                    p.authors().stream()
                        .anyMatch(
                            v ->
                                SqlitePaperRepository.norm(v)
                                    .contains(SqlitePaperRepository.norm(a))))
        && q.venues().stream()
            .allMatch(
                v -> SqlitePaperRepository.norm(p.venue()).contains(SqlitePaperRepository.norm(v)));
  }
}
