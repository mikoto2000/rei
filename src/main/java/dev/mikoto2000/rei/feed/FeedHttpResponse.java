package dev.mikoto2000.rei.feed;

/**
 * フィードの購読と収集で受け渡すフィードHTTP通信応答を表す。
 *
 * @param statusCode 応答の状態コード
 * @param body 本文
 */
public record FeedHttpResponse(int statusCode, String body) {}
