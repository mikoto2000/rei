package dev.mikoto2000.rei.paper;

import java.util.List;

public record StructuredPaper(
    String title,
    Availability availability,
    List<PaperSection> sections,
    List<String> references,
    List<String> warnings) {
  public enum Availability {
    FULL_TEXT,
    ABSTRACT_ONLY,
    METADATA_ONLY
  }

  public StructuredPaper {
    sections = List.copyOf(sections);
    references = List.copyOf(references);
    warnings = List.copyOf(warnings);
  }
}
