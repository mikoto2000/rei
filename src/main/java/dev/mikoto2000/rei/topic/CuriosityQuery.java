package dev.mikoto2000.rei.topic;

import java.time.Instant;

/**
 * 話題の管理で受け渡す情報への関心検索条件を表す。
 *
 * @param now 現在日時
 * @param limit 上限
 */
public record CuriosityQuery(Instant now, int limit) {}
