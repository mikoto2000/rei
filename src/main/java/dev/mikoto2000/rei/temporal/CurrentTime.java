package dev.mikoto2000.rei.temporal;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 時刻と期間の解釈で受け渡す現在時刻を表す。
 *
 * @param timestamp 記録日時
 * @param timeZone タイムゾーン
 */
public record CurrentTime(String timestamp, @JsonProperty("timezone") String timeZone) {}
