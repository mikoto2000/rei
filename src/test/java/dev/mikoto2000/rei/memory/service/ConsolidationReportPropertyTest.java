package dev.mikoto2000.rei.memory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

class ConsolidationReportPropertyTest {

  // Feature: ai-memory-consolidation, Property 4: 整理結果レポートの件数一致
  @Property(tries = 100)
  void reportCountsAlwaysMatch(@ForAll @IntRange(min = 0, max = 100) int total,
      @ForAll @IntRange(min = 0, max = 100) int saved) {
    int normalizedSaved = Math.min(saved, total);
    int skipped = total - normalizedSaved;

    ConsolidationReport report = ConsolidationReport.of(total, normalizedSaved, skipped);

    assertTrue(report.isConsistent());
    assertEquals(total, report.savedCount() + report.skippedCount());
  }
}
