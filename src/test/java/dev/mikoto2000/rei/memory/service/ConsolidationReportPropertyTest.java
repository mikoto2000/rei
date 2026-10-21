package dev.mikoto2000.rei.memory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

/** 統合レポートPropertyの結果と状態の契約を検証するテスト。 */
class ConsolidationReportPropertyTest {

  // Feature: ai-memory-consolidation, Property 4: 整理結果レポートの件数一致
  /** 長期記憶の保存と検索で、指定した条件に対する結果と状態の変化を確認する。 */
  @Property(tries = 100)
  void reportCountsAlwaysMatch(
      @ForAll @IntRange(min = 0, max = 100) int total,
      @ForAll @IntRange(min = 0, max = 100) int saved) {
    int normalizedSaved = Math.min(saved, total);
    int skipped = total - normalizedSaved;

    ConsolidationReport report = ConsolidationReport.of(total, normalizedSaved, skipped);

    assertTrue(report.isConsistent());
    assertEquals(total, report.savedCount() + report.skippedCount());
  }
}
