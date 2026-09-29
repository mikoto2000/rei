package dev.mikoto2000.rei.paper;

import java.util.List;

public record PaperSummary(
    String paperId,
    Mode mode,
    String model,
    String promptVersion,
    String createdAt,
    StructuredPaper.Availability availability,
    Content content,
    List<PaperEvidence> evidence,
    List<String> warnings) {
  public PaperSummary withPaperId(String id) {
    return new PaperSummary(
        id, mode, model, promptVersion, createdAt, availability, content, evidence, warnings);
  }

  public enum Mode {
    QUICK,
    STANDARD,
    DETAILED
  }

  public record Content(
      String problem,
      String background,
      List<String> contributions,
      String method,
      List<String> datasets,
      String evaluation,
      List<String> results,
      List<String> limitations,
      List<String> futureWork) {}
}
