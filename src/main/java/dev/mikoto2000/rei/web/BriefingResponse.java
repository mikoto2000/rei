package dev.mikoto2000.rei.web;

import java.util.List;

/**
 * Web APIによる操作で受け渡す日次案内応答を表す。
 *
 * @param date 日付
 * @param overview 概要
 * @param events イベント一覧
 * @param openTasks 開始タスク一覧
 * @param overdueTasks overdueタスク一覧
 * @param relatedDocuments 関連保存済み文書
 * @param feedItems フィード項目一覧
 * @param interestUpdates 関心Updates
 * @param cautionPoints 確認時に注意する点
 * @param nextActions 次操作一覧
 */
public record BriefingResponse(
    String date,
    String overview,
    List<EventResponse> events,
    List<TaskResponse> openTasks,
    List<TaskResponse> overdueTasks,
    List<String> relatedDocuments,
    List<ArticleResponse> feedItems,
    List<String> interestUpdates,
    List<String> cautionPoints,
    List<String> nextActions) {
  /**
   * Web APIによる操作で受け渡すイベント応答を表す。
   *
   * @param id 識別子
   * @param summary 要約
   * @param start 開始
   * @param end 終了
   * @param location 場所
   * @param status 状態
   */
  public record EventResponse(
      String id, String summary, String start, String end, String location, String status) {}

  /**
   * Web APIによる操作で受け渡すタスク応答を表す。
   *
   * @param id 識別子
   * @param title タイトル
   * @param dueDate 期限日付
   * @param priority 優先度
   * @param status 状態
   */
  public record TaskResponse(long id, String title, String dueDate, int priority, String status) {
    /** Web APIによる操作で利用する情報を、受け取った形式から変換する。 */
    static TaskResponse from(dev.mikoto2000.rei.task.Task t) {
      return new TaskResponse(
          t.id(),
          t.title(),
          t.dueDate() == null ? null : t.dueDate().toString(),
          t.priority(),
          t.status().name());
    }
  }

  /**
   * Web APIによる操作で受け渡す記事応答を表す。
   *
   * @param id 識別子
   * @param title タイトル
   * @param url 参照先のURL
   * @param publishedAt 公開日時
   * @param feedName フィード名前
   */
  public record ArticleResponse(
      long id, String title, String url, String publishedAt, String feedName) {}

  /** Web APIによる操作で利用する情報を、受け取った形式から変換する。 */
  public static BriefingResponse from(dev.mikoto2000.rei.briefing.DailyBriefing b) {
    return new BriefingResponse(
        b.date().toString(),
        b.overview(),
        b.events().stream()
            .map(
                e ->
                    new EventResponse(
                        e.id(), e.summary(), e.start(), e.end(), e.location(), e.status()))
            .toList(),
        b.openTasks().stream().map(TaskResponse::from).toList(),
        b.overdueTasks().stream().map(TaskResponse::from).toList(),
        b.relatedDocuments(),
        b.feedItems().stream()
            .map(
                f ->
                    new ArticleResponse(
                        f.id(),
                        f.title(),
                        f.url(),
                        f.publishedAt() == null ? null : f.publishedAt().toString(),
                        f.feedName()))
            .toList(),
        b.interestUpdates(),
        b.cautionPoints(),
        b.nextActions());
  }
}
