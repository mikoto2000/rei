package dev.mikoto2000.rei.websearch;

import java.util.List;

public record WebSearchResponse(
    String summary,
    List<WebSearchResult> sources) {
}
