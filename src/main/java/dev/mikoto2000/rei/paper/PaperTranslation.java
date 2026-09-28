package dev.mikoto2000.rei.paper;

import java.util.*;

public record PaperTranslation(
    String paperId,
    Mode mode,
    String model,
    String promptVersion,
    String glossaryVersion,
    String createdAt,
    String section,
    StructuredPaper.Availability availability,
    Map<String, String> glossary,
    List<Chunk> chunks,
    List<String> warnings) {
  public PaperTranslation withPaperId(String id) {
    return new PaperTranslation(
        id,
        mode,
        model,
        promptVersion,
        glossaryVersion,
        createdAt,
        section,
        availability,
        glossary,
        chunks,
        warnings);
  }

  public enum Mode {
    LITERAL,
    NATURAL,
    TECHNICAL
  }

  public record Chunk(String section, int page, int index, String content) {}
}
