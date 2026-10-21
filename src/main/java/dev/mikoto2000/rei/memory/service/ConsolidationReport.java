package dev.mikoto2000.rei.memory.service;

/**
 * 長期記憶の保存と検索で受け渡す統合レポートを表す。
 *
 * @param totalCandidates 合計候補一覧
 * @param savedCount 保存済み件数
 * @param skippedCount 対象外件数
 */
public record ConsolidationReport(int totalCandidates, int savedCount, int skippedCount) {

  /** 受け取った情報からConsolidationReportを作成する。 */
  public ConsolidationReport {
    if (totalCandidates < 0 || savedCount < 0 || skippedCount < 0) {
      throw new IllegalArgumentException("counts must be non-negative");
    }
  }

  /** 長期記憶の保存と検索において、整合したかどうかを判定する。 */
  public boolean isConsistent() {
    return savedCount + skippedCount == totalCandidates;
  }

  /** 長期記憶の保存と検索で受け渡す情報を、指定された内容から作成する。 */
  public static ConsolidationReport of(int totalCandidates, int savedCount, int skippedCount) {
    return new ConsolidationReport(totalCandidates, savedCount, skippedCount);
  }
}
