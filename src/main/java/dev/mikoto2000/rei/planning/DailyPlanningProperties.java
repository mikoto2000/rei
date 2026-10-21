package dev.mikoto2000.rei.planning;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Set;

/** 日次計画の設定値をまとめ、利用環境に応じた動作を指定できるようにする。 */
@org.springframework.boot.context.properties.ConfigurationProperties("rei.today")
@lombok.Getter
@lombok.Setter
public class DailyPlanningProperties {
  /** 有効状態。 */
  private boolean enabled;

  /** プロジェクト一覧。 */
  private Set<String> projects = Set.of();

  /** タイムゾーン。 */
  private String zone = "Asia/Tokyo";

  /** 最大タスク一覧ごとのプロジェクト／最大項目一覧ごとのプロジェクト／集約対象予算秒数。 */
  private int maxTasksPerProject = 1000, maxItemsPerProject = 128, collectionBudgetSeconds = 5;

  /** 古い状態変更後。 */
  private Duration staleAfter = Duration.ofDays(7);

  /** 利用条件に合わない指定を受け付けないよう、入力内容を検証する。 */
  public void validate() {
    if (projects == null
        || projects.isEmpty()
        || projects.size() > 16
        || projects.stream().anyMatch(id -> id == null || !id.matches("[a-fA-F0-9-]{36}"))
        || maxTasksPerProject < 1
        || maxTasksPerProject > 2000
        || maxItemsPerProject < 1
        || maxItemsPerProject > 256
        || collectionBudgetSeconds < 1
        || collectionBudgetSeconds > 10
        || staleAfter == null
        || staleAfter.compareTo(Duration.ofHours(1)) < 0
        || staleAfter.compareTo(Duration.ofDays(366)) > 0)
      throw new IllegalArgumentException("Invalid Today allowlist or collection limits");
    try {
      ZoneId.of(zone);
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Invalid Today calendar zone");
    }
  }
}
