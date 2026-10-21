package dev.mikoto2000.rei.memory.service;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * 長期記憶の保存と検索で受け渡す記憶情報の鮮度を表す。
 *
 * @param age 経過時間
 * @param validUntil 有効終了日時
 * @param status 状態
 */
public record MemoryFreshness(Duration age, OffsetDateTime validUntil, FreshnessStatus status) {}
