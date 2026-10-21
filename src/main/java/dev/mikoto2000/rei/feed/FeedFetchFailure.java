package dev.mikoto2000.rei.feed;

import java.time.OffsetDateTime;

/**
 * フィードの購読と収集で受け渡すフィード取得失敗情報を表す。
 *
 * @param id 識別子
 * @param feedId フィード識別子
 * @param failedAt 失敗日時
 * @param errorMessage エラーメッセージ
 * @param httpStatus HTTP通信状態
 */
public record FeedFetchFailure(
    long id, long feedId, OffsetDateTime failedAt, String errorMessage, Integer httpStatus) {}
