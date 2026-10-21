package dev.mikoto2000.rei.reminder;

import java.time.OffsetDateTime;

/**
 * リマインダーの管理で受け渡すリマインダーを表す。
 *
 * @param id 識別子
 * @param message メッセージ
 * @param type 種別
 * @param remindAt remind日時
 * @param targetAt 対象日時
 * @param minutesBefore 分数変更前
 * @param notified 通知済み
 * @param createdAt 作成日時
 * @param notifiedAt 通知済み日時
 */
public record Reminder(
    long id,
    String message,
    ReminderType type,
    OffsetDateTime remindAt,
    OffsetDateTime targetAt,
    Integer minutesBefore,
    boolean notified,
    OffsetDateTime createdAt,
    OffsetDateTime notifiedAt) {}
