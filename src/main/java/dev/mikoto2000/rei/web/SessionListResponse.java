package dev.mikoto2000.rei.web;

import java.util.List;

/**
 * Web APIによる操作で受け渡す会話一覧応答を表す。
 *
 * @param items 項目一覧
 * @param nextCursor 次続きの取得位置
 */
public record SessionListResponse(List<SessionResponse> items, String nextCursor) {}
