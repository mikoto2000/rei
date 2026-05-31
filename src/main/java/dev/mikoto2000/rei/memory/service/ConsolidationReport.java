package dev.mikoto2000.rei.memory.service;

public record ConsolidationReport(int totalCandidates, int savedCount, int skippedCount) {

  public ConsolidationReport {
    if (totalCandidates < 0 || savedCount < 0 || skippedCount < 0) {
      throw new IllegalArgumentException("counts must be non-negative");
    }
  }

  public boolean isConsistent() {
    return savedCount + skippedCount == totalCandidates;
  }

  public static ConsolidationReport of(int totalCandidates, int savedCount, int skippedCount) {
    return new ConsolidationReport(totalCandidates, savedCount, skippedCount);
  }
}
