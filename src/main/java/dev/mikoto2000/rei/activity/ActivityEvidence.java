package dev.mikoto2000.rei.activity;

import java.time.Instant;
import java.util.List;

/**
 * 作業状況の把握で受け渡す作業状況根拠を表す。
 *
 * <p>OS/app facts, separate from inferred activities. No image bytes or tool arguments.
 *
 * @param capturedAt 観測日時
 * @param foreground 前面
 * @param visibleWindows 表示対象Windows
 * @param projectName プロジェクト名前
 * @param projectId 所属プロジェクトの識別子
 * @param events イベント一覧
 * @param history 履歴
 * @param workContext 作業文脈
 * @param input 入力
 */
public record ActivityEvidence(
    Instant capturedAt,
    ForegroundWindow foreground,
    List<VisibleWindow> visibleWindows,
    String projectName,
    String projectId,
    List<RecentEvent> events,
    History history,
    WorkReference workContext,
    InputReference input) {
  /**
   * 作業状況の把握で受け渡す入力参照先を表す。
   *
   * @param recent 直近
   * @param reliable 信頼できるかどうか
   */
  public record InputReference(boolean recent, boolean reliable) {}

  /** 作業状況の把握を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ActivityEvidence(
      Instant at,
      ForegroundWindow fg,
      List<VisibleWindow> windows,
      String project,
      String projectId,
      List<RecentEvent> events,
      History history,
      WorkReference work) {
    this(at, fg, windows, project, projectId, events, history, work, null);
  }

  /** 指定された入力を反映した情報を作成する。 */
  public ActivityEvidence withInput(InputReference input) {
    return new ActivityEvidence(
        capturedAt,
        foreground,
        visibleWindows,
        projectName,
        projectId,
        events,
        history,
        workContext,
        input);
  }

  /** 受け取った情報からActivityEvidenceを作成する。 */
  public ActivityEvidence {
    visibleWindows = List.copyOf(visibleWindows);
    events = List.copyOf(events);
  }

  /** 作業状況の把握を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ActivityEvidence(
      Instant at,
      ForegroundWindow foreground,
      List<VisibleWindow> windows,
      String projectName,
      String projectId,
      List<RecentEvent> events,
      History history) {
    this(at, foreground, windows, projectName, projectId, events, history, null);
  }

  /**
   * 作業状況の把握で受け渡す作業参照先を表す。
   *
   * <p>Immutable observation-time references; task association never proves user engagement or task
   * success.
   *
   * @param projectId 所属プロジェクトの識別子
   * @param capturedAt 観測日時
   * @param revision 改訂番号
   * @param contextUpdatedAt 文脈更新日時
   * @param git Gitの情報
   * @param items 項目一覧
   * @param partial 部分的な結果
   */
  public record WorkReference(
      String projectId,
      Instant capturedAt,
      long revision,
      Instant contextUpdatedAt,
      GitReference git,
      List<ItemReference> items,
      boolean partial) {
    /** 受け取った情報からWorkReferenceを作成する。 */
    public WorkReference {
      items = List.copyOf(items);
    }
  }

  /**
   * 作業状況の把握で受け渡すGit参照先を表す。
   *
   * @param branch ブランチ
   * @param commit 確定
   * @param capturedAt 観測日時
   */
  public record GitReference(String branch, String commit, Instant capturedAt) {}

  /**
   * 作業状況の把握で受け渡す項目参照先を表す。
   *
   * @param id 識別子
   * @param kind 種類
   * @param status 状態
   * @param certainty 確かさ
   * @param sources 取得元一覧
   */
  public record ItemReference(
      String id, String kind, String status, String certainty, List<SourceReference> sources) {
    /** 受け取った情報からItemReferenceを作成する。 */
    public ItemReference {
      sources = List.copyOf(sources);
    }
  }

  /**
   * 作業状況の把握で受け渡す取得元参照先を表す。
   *
   * @param eventId イベントの識別子
   * @param sessionId 会話の識別子
   * @param turnId やり取りの識別子
   * @param runId 実行の識別子
   * @param toolCallId ツール呼び出し識別子
   * @param file ファイル
   * @param observedAt 観測日時
   */
  public record SourceReference(
      String eventId,
      String sessionId,
      String turnId,
      String runId,
      String toolCallId,
      String file,
      Instant observedAt) {}

  /**
   * 作業状況の把握で受け渡す表示対象範囲を表す。
   *
   * @param window 範囲
   * @param visible 表示対象
   * @param minimized 最小化されているかどうか
   * @param offScreen 無効画面
   * @param monitor 排他制御
   */
  public record VisibleWindow(
      ForegroundWindow window,
      boolean visible,
      boolean minimized,
      boolean offScreen,
      String monitor) {}

  /**
   * 作業状況の把握で受け渡す直近イベントを表す。
   *
   * @param at 日時
   * @param projectId 所属プロジェクトの識別子
   * @param kind 種類
   * @param eventId イベントの識別子
   * @param sessionId 会話の識別子
   * @param turnId やり取りの識別子
   * @param runId 実行の識別子
   */
  public record RecentEvent(
      Instant at,
      String projectId,
      String kind,
      String eventId,
      String sessionId,
      String turnId,
      String runId) {
    /** 作業状況の把握を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public RecentEvent(Instant at, String projectId, String kind) {
      this(at, projectId, kind, null, null, null, null);
    }
  }

  /**
   * 作業状況の把握で受け渡す履歴を表す。
   *
   * @param at 日時
   * @param foreground 前面
   * @param inference 推定
   * @param confidence 確信度
   */
  public record History(
      Instant at,
      ForegroundWindow foreground,
      ActivityRecord.Inference inference,
      double confidence) {}
}
