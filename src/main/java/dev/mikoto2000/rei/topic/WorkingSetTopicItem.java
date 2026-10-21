package dev.mikoto2000.rei.topic;

import java.time.Instant;

/**
 * 話題の管理で受け渡す作業中集合話題項目を表す。
 *
 * @param identifier 識別子
 * @param kind 種類
 * @param lastAccessedAt 直近Accessed日時
 */
public record WorkingSetTopicItem(String identifier, String kind, Instant lastAccessedAt) {}
