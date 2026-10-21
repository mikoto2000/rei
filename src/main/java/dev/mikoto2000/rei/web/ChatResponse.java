package dev.mikoto2000.rei.web;

/**
 * Web APIによる操作で受け渡すチャット応答を表す。
 *
 * @param runId 実行の識別子
 * @param sessionId 会話の識別子
 * @param turnId やり取りの識別子
 */
public record ChatResponse(String runId, String sessionId, String turnId) {}
