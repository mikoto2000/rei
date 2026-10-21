package dev.mikoto2000.rei.application.session;

import java.time.Instant;

/**
 * 会話の継続管理で受け渡す続きの取得位置キーを表す。
 *
 * @param time 時刻
 * @param id 識別子
 */
public record CursorKey(Instant time, String id) {}
