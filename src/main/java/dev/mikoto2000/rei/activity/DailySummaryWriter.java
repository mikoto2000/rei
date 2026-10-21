package dev.mikoto2000.rei.activity;

/** 作業状況の把握に必要な日次要約書き込み先を扱う。 */
@FunctionalInterface
public interface DailySummaryWriter {
  /** 指定された内容を書き込み先へ反映する。 */
  DailySummary write(DailySummaryAggregate aggregate) throws Exception;
}
