package dev.mikoto2000.rei.ui.projection;

import java.time.Instant;

/**
 * 実行状況の表示用整理で受け渡すメッセージ表示内容を表す。
 *
 * @param messageId メッセージ識別子
 * @param role 役割
 * @param status 状態
 * @param text テキスト
 * @param startedAt 開始日時
 * @param completedAt 完了日時
 */
public record MessageView(
    String messageId,
    String role,
    MessageStatus status,
    String text,
    Instant startedAt,
    Instant completedAt) {}
