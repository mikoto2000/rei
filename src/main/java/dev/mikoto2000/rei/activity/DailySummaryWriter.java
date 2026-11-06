package dev.mikoto2000.rei.activity;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import edu.umd.cs.findbugs.annotations.SuppressMatchType;

/** 作業状況の把握に必要な日次要約書き込み先を扱う。 */
@FunctionalInterface
public interface DailySummaryWriter {
  /** 指定された内容を書き込み先へ反映する。 */
  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
      matchType = SuppressMatchType.EXACT,
      justification = "Summary writers can report checked serialization or generation failures to the summary service.")
  DailySummary write(DailySummaryAggregate aggregate) throws Exception;
}
