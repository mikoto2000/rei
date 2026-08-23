package dev.mikoto2000.rei.core;

import java.util.List;

public record FindFileRequest(List<String> keywords, Integer maxResults) {

  public FindFileRequest {
    if (keywords == null || keywords.stream().noneMatch(value -> value != null && !value.isBlank())) {
      throw new IllegalArgumentException("keywords must contain at least one non-blank value");
    }
    if (maxResults != null && (maxResults < 1 || maxResults > 200)) {
      throw new IllegalArgumentException("maxResults must be between 1 and 200");
    }
  }
}
