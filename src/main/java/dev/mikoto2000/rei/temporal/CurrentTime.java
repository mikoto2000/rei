package dev.mikoto2000.rei.temporal;

/**
 * 時刻と期間の解釈で受け渡す現在時刻を表す。
 *
 * @param timestamp 記録日時
 * @param timezone タイムゾーン
 */
public record CurrentTime(String timestamp, String timezone) {}
