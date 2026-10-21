package dev.mikoto2000.rei.activity;

import java.util.List;

/**
 * 作業状況の把握で受け渡す作業状況役割一覧を表す。
 *
 * <p>Roles are uncertain projections of visible candidates, never measurements of engagement.
 *
 * @param primary 主要な
 * @param secondary 補助的な
 * @param background バックグラウンド
 * @param confidence 確信度
 * @param basis 判断の根拠
 */
public record ActivityRoles(
    ActivityRecord.Activity primary,
    List<ActivityRecord.Activity> secondary,
    List<ActivityRecord.Activity> background,
    double confidence,
    List<String> basis) {
  /** 受け取った情報からActivityRolesを作成する。 */
  public ActivityRoles {
    secondary = List.copyOf(secondary);
    background = List.copyOf(background);
    basis = List.copyOf(basis);
  }

  /** 作業状況の把握で用いる分類を返す。 */
  public String category() {
    return primary == null ? "unknown" : primary.type();
  }
}
