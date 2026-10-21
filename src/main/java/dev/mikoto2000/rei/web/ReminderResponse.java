package dev.mikoto2000.rei.web;

/**
 * Web APIによる操作で受け渡すリマインダー応答を表す。
 *
 * @param id 識別子
 * @param message メッセージ
 * @param type 種別
 * @param remindAt remind日時
 * @param targetAt 対象日時
 * @param minutesBefore 分数変更前
 * @param notified 通知済み
 */
public record ReminderResponse(
    long id,
    String message,
    String type,
    String remindAt,
    String targetAt,
    Integer minutesBefore,
    boolean notified) {
  /** Web APIによる操作で利用する情報を、受け取った形式から変換する。 */
  public static ReminderResponse from(dev.mikoto2000.rei.reminder.Reminder r) {
    return new ReminderResponse(
        r.id(),
        r.message(),
        r.type().name(),
        r.remindAt().toString(),
        r.targetAt() == null ? null : r.targetAt().toString(),
        r.minutesBefore(),
        r.notified());
  }
}
