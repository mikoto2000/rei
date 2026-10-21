package dev.mikoto2000.rei.web;

import java.util.List;

/**
 * Web APIによる操作で受け渡す会話一回のやり取り一覧応答を表す。
 *
 * @param sessionId 会話の識別子
 * @param items 項目一覧
 * @param nextCursor 次続きの取得位置
 */
public record SessionTurnListResponse(
    String sessionId, List<SessionTurnResponse> items, String nextCursor) {}
