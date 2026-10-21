package dev.mikoto2000.rei.bluesky;

/**
 * Blueskyとの連携で受け渡すBluesky投稿結果を表す。
 *
 * @param success 成功
 * @param message メッセージ
 * @param postUri 投稿URI
 * @param postUrl 投稿URL
 */
public record BlueskyPostResult(boolean success, String message, String postUri, String postUrl) {}
