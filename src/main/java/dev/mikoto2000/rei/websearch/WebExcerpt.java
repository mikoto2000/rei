package dev.mikoto2000.rei.websearch;

import java.util.List;

/** Source HTML offsets and ranges in returned content; no duplicate copy of the excerpt text. */
public record WebExcerpt(String kind, List<String> headings, int position, int sourceStart, int sourceEnd,
    String anchor, int contentStart, int contentEnd, boolean truncated) {
  public WebExcerpt { headings = List.copyOf(headings); }
}
