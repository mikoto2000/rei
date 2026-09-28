package dev.mikoto2000.rei.paper;

import java.util.*;

public record PaperSearchQuery(
    String query,
    Integer fromYear,
    Integer toYear,
    List<String> authors,
    List<String> venues,
    boolean openAccessOnly,
    Sort sort,
    int limit) {
  public enum Sort {
    RELEVANCE,
    NEWEST,
    CITATION_COUNT
  }

  public PaperSearchQuery {
    if (query == null
        || query.isBlank()
        || query.length() > 500
        || limit < 1
        || limit > 100
        || fromYear != null && (fromYear < 1000 || fromYear > 9999)
        || toYear != null && (toYear < 1000 || toYear > 9999)
        || fromYear != null && toYear != null && fromYear > toYear)
      throw new PaperException(PaperException.Code.INVALID_QUERY, "検索語・年・件数を確認してください (1〜100件)");
    authors = authors == null ? List.of() : List.copyOf(authors);
    venues = venues == null ? List.of() : List.copyOf(venues);
    sort = sort == null ? Sort.RELEVANCE : sort;
  }
}
