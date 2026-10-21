package dev.mikoto2000.rei.application.session;

import java.util.List;

/**
 * 会話の継続管理で受け渡す履歴ページを表す。
 *
 * @param items 項目一覧
 * @param nextCursor 次続きの取得位置
 */
public record HistoryPage<T>(List<T> items, String nextCursor) {
  /** 受け取った情報からHistoryPageを作成する。 */
  public HistoryPage {
    items = List.copyOf(items);
  }
}
