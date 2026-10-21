package dev.mikoto2000.rei.activity;

import java.time.Instant;
import java.util.List;

/**
 * 作業状況の把握に必要な作業状況根拠取得元を扱う。
 *
 * <p>Optional app context ports must be bounded and must never invoke an LLM.
 */
@FunctionalInterface
public interface ActivityEvidenceSource {
  /** 判断に必要な情報を集め、後続の処理へ渡す。 */
  Contribution collect(Instant at) throws Exception;

  /**
   * 作業状況の把握で受け渡す貢献度を表す。
   *
   * @param projectName プロジェクト名前
   * @param projectId 所属プロジェクトの識別子
   * @param events イベント一覧
   * @param workContext 作業文脈
   */
  record Contribution(
      String projectName,
      String projectId,
      List<ActivityEvidence.RecentEvent> events,
      ActivityEvidence.WorkReference workContext) {
    /** 受け取った情報からContributionを作成する。 */
    public Contribution {
      events = List.copyOf(events);
    }

    /** 作業状況の把握を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public Contribution(String name, String id, List<ActivityEvidence.RecentEvent> events) {
      this(name, id, events, null);
    }
  }
}
