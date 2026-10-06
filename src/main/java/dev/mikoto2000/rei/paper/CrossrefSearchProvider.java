package dev.mikoto2000.rei.paper;

import java.net.*;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class CrossrefSearchProvider implements AcademicSearchProvider {
  private final PaperHttpClient http;
  private final PaperProperties config;

  public CrossrefSearchProvider(PaperHttpClient http, PaperProperties config) {
    this.http = http;
    this.config = config;
  }

  public List<Paper> search(PaperSearchQuery q, PaperOperation op) {
    String url =
        "https://api.crossref.org/works?query="
            + OpenAlexSearchProvider.enc(q.query())
            + "&rows="
            + q.limit();
    List<String> f = new ArrayList<>();
    if (q.fromYear() != null) f.add("from-pub-date:" + q.fromYear() + "-01-01");
    if (q.toYear() != null) f.add("until-pub-date:" + q.toYear() + "-12-31");
    if (!f.isEmpty()) url += "&filter=" + OpenAlexSearchProvider.enc(String.join(",", f));
    if (q.sort() != PaperSearchQuery.Sort.RELEVANCE)
      url +=
          "&sort="
              + (q.sort() == PaperSearchQuery.Sort.NEWEST ? "published" : "is-referenced-by-count")
              + "&order=desc";
    var root = fetch(url, op).path("message").path("items");
    if (!root.isArray())
      throw new PaperException(PaperException.Code.SEARCH_FAILED, "Crossref レスポンス不正");
    List<Paper> result = new ArrayList<>();
    for (var n : root) {
      op.check();
      var p = normalize(n);
      if (p.title() != null && OpenAlexSearchProvider.matches(p, q)) result.add(p);
      if(result.size()>=q.limit())break;
    }
    return result;
  }

  public Paper metadata(String doi, PaperOperation op) {
    return normalize(
        fetch("https://api.crossref.org/works/" + OpenAlexSearchProvider.enc(doi), op)
            .path("message"));
  }

  private JsonNode fetch(String url, PaperOperation op) {
    try {
      return PaperJson.MAPPER.readTree(
          http.get(URI.create(url), "application/json", config.getMaxResponseBytes(), op));
    } catch (PaperException | java.util.concurrent.CancellationException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new PaperException(PaperException.Code.SEARCH_FAILED, "Crossref レスポンス不正", e);
    }
  }

  private Paper normalize(JsonNode n) {
    List<String> authors = new ArrayList<>();
    for (var a : n.path("author")) {
      String name = (a.path("given").asString("") + " " + a.path("family").asString("")).strip();
      if (!name.isBlank()) authors.add(name);
    }
    var dates = n.path("published").path("date-parts").path(0).path(0);
    String title = n.path("title").path(0).asString(null);
    if (title == null)
      throw new PaperException(PaperException.Code.SEARCH_FAILED, "Crossref title がありません");
    List<String> licenses = new ArrayList<>();
    for (var license : n.path("license")) {
      String url = OpenAlexSearchProvider.text(license, "URL");
      if (url != null) licenses.add(url);
    }
    var parts = n.path("published").path("date-parts").path(0);
    String published = null;
    if (parts.isArray() && parts.size() >= 3)
      try {
        published =
            java.time.LocalDate.of(parts.get(0).asInt(), parts.get(1).asInt(), parts.get(2).asInt())
                .toString();
      } catch (java.time.DateTimeException ignored) {
      }
    return new Paper(
        null,
        title,
        authors,
        n.has("abstract") ? org.jsoup.Jsoup.parse(n.path("abstract").asString()).text() : null,
        dates.isNumber() ? dates.asInt() : null,
        n.path("container-title").path(0).asString(null),
        OpenAlexSearchProvider.text(n, "DOI"),
        null,
        null,
        n.path("is-referenced-by-count").isNumber()
            ? n.path("is-referenced-by-count").asLong()
            : null,
        null,
        OpenAlexSearchProvider.text(n, "URL"),
        null,
        List.of(),
        published,
        licenses);
  }
}
